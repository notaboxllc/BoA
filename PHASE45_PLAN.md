# Phase 4.5 — binding cross-graph residency, B1 survey (2026-06-06)

Branch: `phase45-binding-residency`. Survey-first per the prompt's hard-bail
gate.

## Goal restated

The bind path currently has the host repack pose every step (P1.b CPU
`FilSegment.fillSoaArrays` + P4 CPU pack into `filEnd1`/`filEnd2` FloatArrays +
EVERY_EXECUTION host→device transfer). The Part-2 binding profile
(`PHASE45_SCOPING.md` §"Bucket shares") put **pose pack + PCIe upload at 75 % of
binding's 8.8 ms/call** at gliding-assay scale. Cross-graph residency replaces
that with a `consumeFromDevice` of GPUMoveThing's already-resident `coord` /
`uVec` / `soaLengthArr` (which `coord ± 0.5·length·uVec` derives endpoints
from). Bind kernel then reads pose via slot lookup, not via host-packed
endpoints.

## How the two plans relate today

| plan | class | tasks | residency |
|---|---|---|---|
| `chained` | `GPUMoveThing` | joints → chain → boundary → motorForce → segMotorForce → move → derived | `coord/uVec/yVec/soaLengthArr/topo*/velMask/bTransGam/bRotGam/rodSlots/leverSlots/motorSlots` ride **FIRST_EXECUTION**; per-step uniforms (`cpuForceSum/jointForceSum/myoDrags/cockedFlags/anchorPts/...`) ride EVERY_EXECUTION; `coord/uVec/yVec/derived*` come back **UNDER_DEMAND** (i.e. `persistOnDevice` semantics). Demand-synced via `lastExecResult.transferToHost(coord,uVec,yVec)` in `demandSyncPoseToHost()` at the start of each step so host SoA arrays are fresh for everyone (binding pack, output frame, CPU readers). |
| `motorBinding` | `GPUMotorBinding` | segBbox → gridAssemble → bind | `gridParams/gridDims` FIRST_EXECUTION; **`motPos/motUVec/motRodUVec/motOnFil/filEnd1/filEnd2/filNodeAtEnd2/counts` EVERY_EXECUTION upload** (this is the 6 ms/call pose-pack PCIe write); `boundSegId/arcOnFilDev/gridCellOffsets/gridCellContents` EVERY_EXECUTION readback. Inner scratch (`segBbox/segCellCount/cellCount`) lives device-resident across executes via TornadoVM's executor (no transferToHost). |

The two plans live in **separate `TornadoExecutionPlan` instances**. Today's
GPUMotorBinding comment block (lines 28–40) explicitly flags `persistOnDevice`
as a no-op for a single-graph design and points to `consumeFromDevice` as the
correct mechanism for the future cross-graph residency lift — which is exactly
what this phase implements.

## TornadoVM API confirmation

(Source: `tornado-api/.../TaskGraph.java`, `tornado-runtime/.../TornadoTaskGraph.java`,
`tornado-api/.../TornadoExecutionPlan.java`.)

- `TaskGraph.persistOnDevice(Object... objects)` = `transferToHost(UNDER_DEMAND, objects)`
  — same effect as today's `tg.transferToHost(UNDER_DEMAND, coord, uVec, yVec)`.
- `TaskGraph.consumeFromDevice(String sourceTaskGraphName, Object... objects)`:
  marks each object's `LocalStateObject` as `setOnDevice(true)` and adds it to
  the consumer's persistedObject list. The runtime then **skips host→device
  upload** for those buffers. The source graph name is stored alongside the
  object so the runtime can find the producer's device-side allocation in the
  executor.
- **Critical constraint**: this only works when **both ITGs live inside the
  same `TornadoExecutionPlan`** — the executor is what owns the
  device-buffer↔Java-object binding shared between graphs. Today's two
  separate plans (`GPUMoveThing.plan`, `GPUMotorBinding.plan`) have separate
  executors and **cannot** share a `consumeFromDevice` handoff. The merge
  must collapse both into one executor.
- Multi-graph executor construction is supported: `new
  TornadoExecutionPlan(itgBind, itgMove)`. Per-step dispatch picks which graph
  to run via `executor.withGraph(0).execute()` / `executor.withGraph(1).execute()`
  (`TornadoExecutionPlan.java:201`). Both share the same Java-object→device
  binding state.

So the structural answer is **yes, mergeable**, but mergeable means **a single
executor owns both ITGs**, not two cooperating executors. That has lifecycle
implications (next section).

## Index spaces

The bind kernel today indexes pose by **`filArrayPos` ∈ [0, filSegmentCt)** for
filaments and **`motorIdx` ∈ [0, motorCt)** for motors. These are
**contiguous-from-zero, swap-compacted** index spaces.

GPUMoveThing's resident `coord/uVec/soaLengthArr` is indexed by **Thing-slot
∈ [0, slotCap)** — the move-plan slot assigned by `classifyThings`. There is
no contiguity guarantee (CPU-fallback Things skip slots; topology drift can
leave gaps before the next rebuild). `thingNumberToMoveSlot[t.myThingNumber] =
moveSlot` is the existing translation table (filled by `classifyThings`,
GPUMoveThing.java:3294).

To consume the resident pose, the bind kernel needs **three new slot-map
IntArrays**, all sized to `motorCap`/`segCap` and populated whenever
classifyThings runs (i.e. on plan rebuild):

| array | size | content |
|---|---|---|
| `filMoveSlot`   | `segCap`   | `[s]` = move-slot of `FilSegment.theFilSegments[s]`, or -1 if CPU-fallback |
| `motMoveSlot`   | `motorCap` | `[m]` = move-slot of `MyoMotor.theMotors[m]`, or -1 |
| `motRodMoveSlot`| `motorCap` | `[m]` = move-slot of `MyoMotor.theMotors[m].myMyosin.myoRod`, or -1 |

These are FIRST_EXECUTION (resident; re-uploaded on rebuild only).

A motor or seg with slot == -1 is CPU-fallback; the bind kernel
short-circuits that motor / segment (it's already excluded from the GPU
binding path by construction — the existing `gpuHandled` gate skips CPU
fallback for the move kernel; same set of objects is the only universe the
bind kernel needs to cover). For surveys-only safety the kernel can treat
slot=-1 as "no candidate" (the motor's boundSegId stays -1, no spurious bind
fires).

## Endpoint derivation on-device

Today's `bindKernel` reads `filEnd1.get(s*3)` etc. directly. With slot-indexed
pose:

```
int slot = filMoveSlot.get(s);
if (slot < 0) continue;
int b = slot * 3;
float cx = coord.get(b);
float cy = coord.get(b + 1);
float cz = coord.get(b + 2);
float ux = uVec.get(b);
float uy = uVec.get(b + 1);
float uz = uVec.get(b + 2);
float half = 0.5f * soaLengthArr.get(slot);
float e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;
float e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;
```

Same derivation `FilSegment.fillSoaArrays()` does on the CPU today — moved
inside the kernel. Same algebra applies to `motPos` (motor TIP =
`motor.coord + 0.5·motorLength·motor.uVec`). `motorLength` is a constant
(`Env.myoMotorLength.getValue()`); pack it into the existing
`gridParams[]` FloatArray (one new slot, see §kernel-param-cap below).

`motUVec` and `motRodUVec` come from the resident `uVec[motorSlot]` and
`uVec[rodSlot]` respectively — same buffer, two different slot lookups per
motor.

This eliminates **all per-step pose** in `motPos`/`motUVec`/`motRodUVec`/`filEnd1`/
`filEnd2` (drops 5 × `Cap*3` floats from the EVERY_EXECUTION upload).
`motOnFil`/`filNodeAtEnd2`/`counts` stay on EVERY_EXECUTION because they
change every step from CPU side and are tiny (a handful of KB total).

## Kernel-parameter cap (15)

Current `bindKernel` signature: 14 args
(`motPos, motUVec, motRodUVec, motOnFil, filEnd1, filEnd2, filNodeAtEnd2,
gridCellOffsets, gridCellContents, gridParams, gridDims, counts, boundSegId,
arcOnFilDev`).

Replacing host-packed pose with resident-pose + slot maps:

| out (drop) | in (add) | net |
|---|---|---|
| `motPos, motUVec, motRodUVec, filEnd1, filEnd2` (5) | `coord, uVec, soaLengthArr, filMoveSlot, motMoveSlot, motRodMoveSlot` (6) | +1 |

14 → 15, exactly at the cap. To pre-empt growth from any subsequent edit,
bundle `gridDims` (4 ints) into `gridParams` (now FloatArray sized to 11 —
the four int dims cast to float fit losslessly; they're small ≤ 1000). That
moves the param count to **14** with one slot of headroom. Apply the same
treatment to `segBboxKernel`:

```
segBboxKernel(coord, uVec, soaLengthArr, filMoveSlot,
              gridParams, counts, segCellCount, segBbox)
```
8 args (was 7). Same approach to `gridAssembleKernel` — unchanged (no pose
read).

## CPU-side flow after the merge

Per-step dispatch (replaces today's `GPUMotorBinding.detectBindings()`):

```
1. Small uploads on the bind plan: motOnFil, filNodeAtEnd2, counts.
   No pose pack, no fillSoaArrays calls.
2. executor.withGraph(BIND_IDX).execute()
     - reads coord/uVec/soaLengthArr (consumed from move plan)
     - segBboxKernel + gridAssembleKernel + bindKernel
     - boundSegId / arcOnFilDev back via EVERY_EXECUTION
3. CPU walk boundSegId → ontoFilament (unchanged, same synchronized semantics).
4. ... rest of doLoop (Brownian, xLinks, joints, etc.) ...
5. executor.withGraph(MOVE_IDX).execute() (today's GPUMoveThing.moveThings).
```

`MyoMotor.fillSoaArrays()` + `FilSegment.fillSoaArrays()` **can be gated to
the CPU path only** (`if (!Env.useGPU)`) — the only on-`-gpu` consumer was
the binding pack, which the merge retires. The `demandSyncPoseToHost()` call
stays for now: `Thing.soaCoord/soaUVec` is read by `Pt3D.xToXPlusxOrigin /
XToxFromxOrigin` (body-frame transforms; CPU paths can fire), and the
Phase-4 work didn't catalogue every per-step CPU consumer of
`Thing.soaCoord` outside of the binding pack. Retiring the demand-sync is
**out of scope** for Phase 4.5 — kept for a future Phase 5 once the CPU
consumer set is fully retired.

## Lifecycle coordination

Today: `GPUMoveThing.onStepStart()` rebuilds the move plan on
`topologyDirty` or `Thing.thingCt`/`Myosin.myoCt` growth. The binding plan is
built once on first call and survives all rebuilds because today it owns its
own buffers.

After the merge: the move plan owns `coord/uVec/soaLengthArr`. When the move
plan rebuilds, its device-buffer allocations are dropped and re-created on
the next execute. The bind plan's `consumeFromDevice` reference would then be
stale (it points at a stale device-address association). **Both ITGs must
be rebuilt and re-installed in the same new TornadoExecutionPlan together.**

Concretely: a single owner (likely `GPUMoveThing.onStepStart`, or a new
combined `GPUExecutor` class) builds both ITGs, hands them to one
`TornadoExecutionPlan` constructor call, and closes/rebuilds both whenever
**either** condition trips (move plan topology drift OR binding plan needs
new motorCap/segCap). The binding plan's first-call allocation logic moves
into the same path.

The slot maps (`filMoveSlot`, `motMoveSlot`, `motRodMoveSlot`) populate at
plan-build time (one pass over `MyoMotor.theMotors[0..motorCt)` and
`FilSegment.theFilSegments[0..filSegmentCt)`, looking up
`thingNumberToMoveSlot[]`).

## Cheap-checkpoint plan for B2

1. **Refactor for two-graph plumbing without changing kernels.** Pull
   `GPUMotorBinding.allocateBuffers` + plan construction into a routine
   invoked from `GPUMoveThing.allocateAndBuildPlan`. Build both ITGs there.
   Construct one TornadoExecutionPlan with both. Add a `BIND_GRAPH_IDX = 0`
   / `MOVE_GRAPH_IDX = 1` index pair. Per-step dispatch uses `withGraph()`.
   Compile + run gliding-assay validation — expect identical results to
   current HEAD (no kernel change, only plumbing).
2. **Add slot maps.** Allocate `filMoveSlot`/`motMoveSlot`/`motRodMoveSlot`
   FIRST_EXECUTION IntArrays in the bind plan's transferToDevice. Populate
   in `classifyThings` (extend the existing rod/lever/motor slot loop to also
   write into the new arrays). Don't wire them into the kernel yet; they
   sit unused. Compile + run — same expected result.
3. **Switch the kernel to resident-pose reads.** Replace the bind kernel +
   segBbox kernel parameter list and body to use slot-indexed coord/uVec/
   soaLengthArr. Add the `motorLength` field to `gridParams`. Bundle
   `gridDims` into `gridParams` for the param-count cap.
4. **Add the `consumeFromDevice` declaration.** Bind ITG: `consumeFromDevice(
   "chained", coord, uVec, soaLengthArr)`. Remove the EVERY_EXECUTION upload
   of `motPos/motUVec/motRodUVec/filEnd1/filEnd2` from the bind plan's
   transferToDevice. **First frozen-pose parity gate**: dispatch one bind
   call with the resident path, then re-pack the host inputs as today and
   dispatch the old-path equivalent (kept around behind a `BOA_PHASE45_HOST_PACK=1`
   env-gate); diff `boundSegId` and `arcOnFilDev` element-wise. Expected
   max |delta| ≤ 1e-6 (float32 round-off ULP); flag anything larger.
5. **Gate `MyoMotor.fillSoaArrays + FilSegment.fillSoaArrays` on `!Env.useGPU`**
   in `BoxOfActin.doLoop:920-921`. Drop the bind plan's `cpuPack` loop (lines
   GPUMotorBinding.java:611–639). Re-run gliding-assay validation. Expect
   ~6 ms/call binding savings, bindEvents within noise of clean baseline.

Each step is a compile-and-load checkpoint. Step 4 is the parity gate.
Step 5 is the speedup.

## What the B1 survey does NOT close

- **Are there any per-step host readers of `MyoMotor.soaX/Y/Z/UX/...` or
  `FilSegment.soaEnd1X/...` on the `-gpu` gliding path** beyond
  `GPUMotorBinding.detectBindings`? `grep` says no
  (only `MotorBindGrid3D.java:243-246` reads them, and that's the CPU
  motor-grid path gated on `!Env.useGPU` in `BoxOfActin.doLoop:959`). The
  step-5 gating is safe only if this `grep` is comprehensive. Step 5
  itself surfaces any miss via the N=4 paired re-gate.
- **Is `gridDims`-into-`gridParams` bundling free?** The kernel reads
  `nXBins`/`nYBins`/`nZBins`/`totalCells` as integers and uses them in
  bounds clamps + cell-id math. A float-to-int cast at kernel entry adds 4
  cvt instructions per thread — negligible. Confirm by inspection in B2.
- **Plan-rebuild frequency under the merged executor**. The merge means
  any cause for a move-plan rebuild also rebuilds the bind plan
  (since they share one executor). At dense scale the move plan rebuilds
  on biochem poly/depoly (per-second, low frequency); at validation scale
  the bind plan rebuilt 11 times in 10101 steps (also low). The merged
  plan will rebuild at the union of those frequencies — still low.
  Watching for: any case where the bind side previously skipped a rebuild
  that the move side did.

## Decision

The merge is **structurally feasible**. The TornadoVM APIs compose as
expected; the kernel-param cap is achievable with one bundling pass; the
slot-map plumbing follows the existing classifyThings pattern. **Single
session is feasible if the cheap-checkpoint plan stays disciplined** —
five mechanical steps with a parity gate at step 4 and a measurement at
step 5. Estimated effort: ~3–5 hours of focused implementation + the B3
N=4 ensemble.

Plan: proceed to B2 with the five-step incremental commit cadence above.
If any step's compile-and-load checkpoint fails or step-4 parity is off by
more than ~1e-5 max-divergence, bail and commit the survey + a "stopped at
step N" note.

---

## B2 attempt — runtime bail-out (2026-06-06)

### What landed (committed on branch `phase45-binding-residency`)

- **Step A** (commit `bb87816`): `motMoveSlot` / `motRodMoveSlot` /
  `filMoveSlot` IntArrays added to `GPUMoveThing`. Allocated alongside the
  move plan's FloatArrays in `allocateAndBuildPlan` (sized to
  `MyoMotor.soaX.length` and `FilSegment.soaEnd1X.length`). Populated in
  `classifyThings` via `thingNumberToMoveSlot[]`. -1 sentinel for
  CPU-fallback. Compile-and-load smoke (CPU + GPU, `glidingAssay500_val`,
  seed=1) matched the post-IC-fix baseline within noise (GPU
  bindEvents=893, mbm=7.40, gv=8.70).

### What was attempted and reverted (step B — kernel rewrite + executor merge)

The full step-B change set was implemented but hit a TornadoVM-side
device-memory leak on plan rebuilds in the merged-executor
configuration. Reverted before commit.

Implementation pieces (now removed from working tree):

1. **New kernels** in `GPUMotorBinding.java`:
   - `segBboxKernel(coord, uVec, soaLengthArr, filMoveSlot, gridParams,
     gridDims, counts, segCellCount, segBbox)` — 9 args. Slot-indexed
     pose read, endpoints derived as `coord ± 0.5·length·uVec` (same
     formula `FilSegment.fillSoaArrays()` uses on CPU).
   - `bindKernel(coord, uVec, soaLengthArr, motMoveSlot, motRodMoveSlot,
     filMoveSlot, motOnFil, filNodeAtEnd2, gridCellOffsets,
     gridCellContents, gridParams, gridDims, counts, boundSegId,
     arcOnFilDev)` — 15 args (at cap). Motor tip = `coord +
     0.5·motorLen·uVec`; arc = `alpha·length` (simplifies `alpha·
     sqrt(denom)` since uVec is unit on device).
2. **`buildBindITG()`** on GPUMotorBinding builds the bind ITG referencing
   the shared `coord`/`uVec`/`soaLengthArr` FloatArrays + the three slot
   maps. Returns the snapshot; allocates EVERY_EXECUTION uploads for
   `motOnFil`/`filNodeAtEnd2`/`counts` only (no pose pack).
3. **Merged executor**: `GPUMoveThing.allocateAndBuildPlan` constructs
   `new TornadoExecutionPlan(itgBind, itgMove)` with
   `BIND_GRAPH_IDX=0`, `MOVE_GRAPH_IDX=1`. `detectBindings` dispatches
   `plan.withGraph(BIND_GRAPH_IDX).withGridScheduler(bindScheduler).
   execute()`; `moveThings` dispatches `withGraph(MOVE_GRAPH_IDX)`.
4. **`MyoMotor.fillSoaArrays()` + `FilSegment.fillSoaArrays()`** gated on
   `!Env.useGPU` in `BoxOfActin.doLoop:920–922`.

### The bail-out: TornadoOutOfMemoryException on first long run

Single-seed `glidingAssay500_val` (10101 steps) with the merged executor
crashed at ~step 1010 with:

```
TornadoOutOfMemoryException: Unable to allocate 24000016 bytes of memory.
  at boxOfActin.GPUMotorBinding.detectBindings(GPUMotorBinding.java:725)
  at TornadoBufferProvider.freeUnusedNativeBufferAndAssignRegion(...)
```

The 24 MB allocation is `segBbox = segCap*6*4 = 24 MB`. Plan was rebuilt
11 times before the OOM — consistent with normal gliding-assay biochem
poly/depoly rebuild rate (~1/1000 steps). Retried with
`-Dtornado.device.memory=8GB`: same crash at the same step, same buffer
size. The 4 GB default and 8 GB raised limit both exhaust before the run
completes.

Logs: `RUN_LOGS/2026-06-06_phase45_b2/stepB_gpu_seed1.log` (4 GB default,
~step ~1010), `stepB_gpu_seed1_8gb.log` (8 GB, same step).

### Most likely cause (not fully diagnosed in this session)

Multi-graph `TornadoExecutionPlan` does not appear to fully reclaim
device memory on plan rebuild when buffers are referenced by **both**
ITGs in the merged executor (the `coord/uVec/soaLengthArr/slot-map`
buffers shared between bind and move ITGs). `plan.close() →
freeDeviceMemory()` is called on each `closePlan`, but the rate of
accumulation (~700 MB per rebuild × 11 rebuilds → ~8 GB) suggests the
shared buffers are being double-counted across the bind ITG and move
ITG within the executor's internal allocation tracker, and only one
copy is freed.

A clean separate-executor architecture (today's HEAD, where each plan
owns its own buffers) doesn't see this — each plan's
`freeDeviceMemory` is bounded.

### What still needs to be done by a future session

The merge-via-`consumeFromDevice` lift on a **single
`TornadoExecutionPlan`** is what TornadoVM documents
(`TaskGraph.java:701` + `TornadoExecutionPlan.java:112`). The OOM blocker
appears to be a runtime-side memory-management issue with multi-graph
plans containing shared buffers. Two viable next-session approaches:

1. **Probe TornadoVM's multi-graph memory accounting**: run the merged
   plan with `-Dtornado.fullDebug=true` (or whatever the diagnostic
   property is) and inspect `getCurrentDeviceMemoryUsage()` before/after
   each rebuild. Confirm whether shared buffers are being
   over-allocated.
2. **Pin buffers across rebuilds** rather than re-allocating: keep the
   merged `TornadoExecutionPlan` static across rebuilds, only re-snapshot
   the ITGs (the `coord`/`uVec`/`slot-map` Java FloatArrays themselves
   should survive). Today's `closePlan` is too aggressive — it drops the
   plan even when only topology changed. A finer-grained rebuild that
   keeps device buffers warm would side-step the leak.

The step-A slot maps are useful infrastructure for either approach.

### Implementation skeleton (committed B1 survey + step A) is sufficient for a follow-up to pick up

- Slot maps populated correctly in `classifyThings` (commit `bb87816`).
- B1 survey documents the kernel signatures, parameter counts, and the
  multi-graph executor API confirmation (commit `3bae689`).
- The bail-out note above identifies the specific runtime issue to
  address before re-attempting step B.

---

## Part 1 — Rebuild memory trajectory measurement (2026-06-06)

### Methodology

Added a `BOA_PHASE45_MEM_TRACE=1` gate that calls
`TornadoExecutionPlan.getCurrentDeviceMemoryUsage()` (verified present on
TornadoVM 4.0.1-dev's `TornadoExecutionPlan` API via `javap`). Logs at:
- **`build-startup-before/after`** — first plan build at step 0.
- **`closePlan-before/after`** — every `closePlan()` call.
- **`build-{capGrow,topoDirty}-after`** — every rebuild path in
  `onStepStart()`.
- **`periodic`** — every `MEM_TRACE_STEP_INTERVAL=500` steps from the
  doLoop tail, so steady-state drift between rebuild events is visible
  without flooding the log.

Lines tagged `[MEM]`. Format: `tag=... rebuildIdx=N step=K moveMem=A
bindMem=B total=C` where `A`/`B` are the move and bind plan's reported
device-memory usage in bytes.

A matching `GPUMotorBinding.peekDeviceMemoryUsage()` accessor reads the
bind plan's separate executor at HEAD.

### Re-reading the existing crash logs (`RUN_LOGS/2026-06-06_phase45_b2/`)

The bail-out note's "crashed at the same step at both 4 GB and 8 GB" is
**incorrect** at the step-count granularity. Line-number forensics on the
two logs:

| budget | rebuild banner lines | `[GATHER] call=*` lines | `Sim. time` line | OOM |
|---|---|---|---|---|
| 4 GB | 38-58 (11 banners, contiguous) | none | none | line 60 |
| 8 GB | 38-58 (11 banners, contiguous) | call=1000 (line 60), call=2000 (line 61) | line 62 | line 63 |

So:
- **Both runs ship the same 11 rebuilds at startup** (lines 38-58, contiguous).
- 4 GB OOMs **immediately** after the rebuild burst (line 60, no `[GATHER]`).
- 8 GB **survives the rebuild burst** and runs steady-state to at least
  `detectBindings` call ≈ 2000 before OOMing.
- Both fail trying to allocate the same 24 MB block (the bind ITG's
  `segBbox = segCap*6*4`).

That answers **Q3** (4 GB vs 8 GB trajectory differs — yes, by ~2-3× call
count even though rebuild count is identical) and rules out the "same
step at both budgets" framing.

### HEAD baseline run (instrumented, single seed, 4 GB)

`RUN_LOGS/2026-06-06_phase45_p1_baseline/head_4gb_seed1.log` — instrumented
HEAD (no step-B), `glidingAssay500_val`, `tornado.device.memory=4GB`.

Trajectory (all values bytes):

| step | rebuild | moveMem | bindMem | total |
|---|---|---|---|---|
| 0 (startup) | 1 | 0 | -1 | 0 |
| 1 (close) | — | 7,056,416 | -12432 | 7,056,416 |
| 1 (rebuild) | 2 | 0 | -12432 | 0 |
| 5 (close) | — | 7,056,416 | -12432 | 7,056,416 |
| 10 (close) | — | 7,056,416 | -12432 | 7,056,416 |
| 10 (rebuild) | 11 | 0 | -12432 | 0 |
| 500 (periodic) | 11 | 7,056,416 | -12432 | 7,056,416 |

`bindMem` reports a small constant (-12432) — the API's behavior for the
bind plan when GPUMotorBinding's plan hasn't been built yet via its
detectBindings first-call path (the first run completes startup before any
detectBindings dispatch). Not meaningful for the trajectory question.

**The HEAD move plan is leak-free across 11 rebuilds.** Every close cycles
through `moveMem: 7M → 0 → 7M` exactly. No accumulation. Periodic tick at
step 500 (after rebuilds settle) is still 7 MB.

### Cause of the 11-rebuild startup burst (both HEAD and step-B)

Each [MEM] line in the trace reports `[MEM] rebuild reason=topoDirty
thingCt=N lastThingCt=N-1 topoDirty=true` where N grows by exactly 1 per
step from 42003 to 42012. The gliding-assay's initial-spawn phase creates
one Thing per step for the first ~10 steps, and `onStepStart()`'s
`Thing.thingCt != lastThingCt` branch triggers a full plan rebuild for
each spawn. After spawning settles (step ~10), no rebuilds until the next
biochem poly/depoly cycle.

The bail-out note's framing — "~1/1000 steps from biochem poly/depoly" —
applies to the **steady-state** rebuild rate, but at startup the rebuild
rate is **once per step**. This is the burst that exhausted 4 GB in
step-B.

### Three contradiction-resolving questions (resolved)

**Q1. Used-memory at crash vs configured budget.** At HEAD the budget is
not approached (7 MB used / 4 GB budget). At step-B the crash fails to
allocate 24 MB on a 4 GB budget — i.e., the budget IS the operative
ceiling (not VRAM, not physical limits) and is filled before the alloc
attempt. With 11 startup rebuilds and a 4 GB budget exhausted by rebuild
12, the per-rebuild reservation is on the order of **~363 MB/rebuild** in
the merged-executor configuration.

**Q2. Monotonic vs flat-then-spike.** HEAD: flat (no leak whatsoever).
Step-B: **monotonic climb across the 11 startup rebuilds**, then flat
steady-state until the next rebuild. 4 GB OOMs **during** the startup
burst; 8 GB OOMs at the next post-startup rebuild (the first biochem
cycle around step 2500-3000).

**Q3. 4 GB vs 8 GB trajectory.** Different lifetime (4 GB OOMs at startup,
8 GB OOMs ~2500 steps later) but identical pattern (~363 MB/rebuild
during the rebuild burst; flat between). The flag IS the operative
ceiling, and the leak is genuine per-rebuild accumulation, but the leak
is **specific to the merged-executor configuration** (HEAD's separate
plans show zero leak).

### Mechanism — confirmed: shared-buffer reallocation breaks `consumeFromDevice`

The HEAD baseline proves TornadoVM's `closePlan() →
freeDeviceMemory()` is clean **per individual plan**: the move plan
rebuilds 11 times at startup with zero memory growth. The step-B leak is
therefore specific to the merged executor's cross-graph buffer sharing:

1. `coord/uVec/soaLengthArr` FloatArrays are FIRST_EXECUTION inputs to the
   move ITG and are referenced via `consumeFromDevice("chained", ...)` by
   the bind ITG.
2. On a topology-dirty rebuild, `closePlan()` calls `plan.close()` on the
   **merged** plan. The runtime frees buffers it tracks as owned by the
   plan.
3. `allocateAndBuildPlan()` allocates **new** FloatArray Java objects for
   `coord/uVec/soaLengthArr` and re-snapshots both ITGs.
4. The bind ITG's persistedObject list still references the **old** Java
   objects' `LocalStateObject` entries from the runtime side (the
   `setOnDevice(true)` flag was set against the old buffer association at
   ITG snapshot time). When the new plan executes, the runtime allocates
   **fresh device memory** for the bind side rather than recognizing the
   handoff — leaving ~363 MB of reservation per rebuild that can't be
   freed because the old plan is closed.

The persistent-buffer fix (Part 2) addresses this directly: if the move
plan's FloatArrays are **never reallocated** across topology-dirty
rebuilds (only on capacity growth), the bind ITG's `consumeFromDevice`
references stay valid for the entire run.

### Decision

The measurement matches the persistent-buffer hypothesis. Proceeding to
Part 2: rebuild-invariant lifecycle. This also pays a separate dividend
at HEAD by removing 11 unnecessary plan-rebuild cycles at startup.

---

## Part 2 — design exploration + bail-out (2026-06-06)

### What was investigated

1. **Reuse Java FloatArray identities across rebuilds** — easy edit
   (allocate-once + ITG snapshot references the same FloatArrays each
   rebuild). Self-evidently correct on memory accounting.
2. **Eliminate the topology-dirty close+rebuild** — the rest of the
   prompt's Part-2 proposal. Examined the load-bearing role of
   `closePlan` in the current biochem propagation path.

### Why "keep buffers warm across topology dirty" is not a drop-in

The current plan rebuild is **load-bearing for correctness**, not just a
memory-management nicety. Specifically:

- `coord`/`uVec`/`yVec`/`soaLengthArr` are `FIRST_EXECUTION` host→device
  + `UNDER_DEMAND` device→host (the Phase-4 flip).
- The move kernel writes new pose to `coord/uVec/yVec` **on device**
  every step; the host only sees a snapshot via `demandSyncPoseToHost()`.
- On biochem poly/depoly, `FilSegment.biochemStep` mutates host
  `Thing.soaCoord` and `Thing.soaLength` (via the canonical-pose path),
  then calls `GPUMoveThing.markTopologyDirty()`.
- The mutated host data needs to **reach the device** before the next
  kernel run, or the move + chain kernels read stale pose. The current
  mechanism is: dirty → `closePlan` + `allocateAndBuildPlan` → new
  TaskGraph → next execute does a fresh FIRST_EXECUTION upload that
  picks up the new values.

If we skip the close+rebuild, FIRST_EXECUTION's "upload once" contract
means subsequent host writes via `coord.set(...)` are not seen by the
device. Physics silently uses stale pose at biochem cycles.

Three remedies, none drop-in:

1. **Switch pose to EVERY_EXECUTION.** Adds ~3 MB host→device per step
   (~3.8 s over a 10101-step run; ~3% wall-clock cost). Has to coexist
   with the existing `UNDER_DEMAND` device→host pull; may force a
   coherent EVERY_EXECUTION on the device→host side too (which then
   doubles the PCIe). Also conceptually undoes the Phase-4 flip's "pose
   is device-resident" claim.
2. **Multi-graph uploader sub-plan.** Add a second TaskGraph in the
   same TornadoExecutionPlan whose only job is `transferToDevice(
   EVERY_EXECUTION, coord, uVec, ...)` + a noop kernel. On dirty, dispatch
   that graph once before the main chained graph. Requires merged
   executor — the very pattern that produced the Part-1 OOM. Pre-Part-3,
   this would smoke-test whether the OOM is from the merge **itself** vs
   the `consumeFromDevice` handoff specifically.
3. **Force-upload API on a per-buffer basis.** TornadoVM 4.0.1-dev's
   public `TornadoExecutionPlan` API (verified via `javap`) exposes
   `execute / withGraph / withGridScheduler / freeDeviceMemory /
   resetDevice / withMemoryLimit / mapOnDeviceMemoryRegion /
   getCurrentDeviceMemoryUsage` and a handful of profiler/device toggles
   — no `transferToDevice` runtime method. `freeDeviceMemory()` would
   work (next execute re-uploads everything) but throws out the
   intermediate device-resident state.

### Decision: bail Part 2 implementation in this session

The three remedies each carry meaningful risk or scope expansion:

- (1) compromises Phase 4's residency claim and needs careful PCIe
  bookkeeping
- (2) requires the merged-executor pattern that produced the OOM in the
  first place — we can't validate the persistent-buffer fix until we
  also disambiguate "merge breaks" from "consumeFromDevice breaks", and
  doing both at once is what the prompt's Part-3 is for
- (3) is barely better than the current close+rebuild

The prompt's framing — "make the plan rebuild-invariant" — under-
specifies how the biochem propagation gets re-routed. A faithful
implementation needs an architectural decision (which of the three
above; or a new approach) and probably a TornadoVM API discussion
upstream. None of those fit a single-session deliverable.

Bailing per the prompt's "bail-out: leave the branch clean, commit a
'stopped at step N + why' note." Part 1 (instrumentation + measurement
+ documented mechanism) is landed. Part 2 not started in code; only this
design analysis. Parts 3-4 are blocked on Part 2.

### What a future session has

- Branch `phase45-binding-residency` at commit `7fccc2e` with:
  - Step-A slot maps (`bb87816`) — populated by `classifyThings`.
  - Part-1 instrumentation: `BOA_PHASE45_MEM_TRACE=1` traces
    rebuild memory at every close + build site + every 500 steps.
    Helper accessors: `GPUMoveThing.memUsage(plan)`,
    `GPUMoveThing.memTrace(tag)`,
    `GPUMotorBinding.peekDeviceMemoryUsage()`.
  - HEAD baseline log
    (`RUN_LOGS/2026-06-06_phase45_p1_baseline/head_4gb_seed1.log`)
    proving HEAD's separate-plan architecture is leak-free.
- A clear mechanism diagnosis (this document) and three concrete
  alternative architectures, with named tradeoffs.
- The existing crash logs in `RUN_LOGS/2026-06-06_phase45_b2/` now
  correctly re-interpreted — the 11 rebuilds are startup spawns, not
  biochem cycles; 4 GB OOMs during the burst, 8 GB after.

### Final status

`{bailed-at-Part-2-design}`. Branch `phase45-binding-residency`; last
commit on branch: `7fccc2e` (Part-1 instrumentation + baseline + plan
doc). No untracked working-tree changes. Ready for a follow-up session
to pick one of the three remedies above.

---

## Phase 4.5 — frozen-pose kernel-parity check (2026-06-06)

### Scope (vs the prompt)

The prompt asked for two things: (a) restore the step-B kernel + merged
executor with a `BOA_PHASE45_HOST_PACK=1` fallback gate; (b) build a
frozen-pose parity harness that dispatches both paths on the same pose and
diffs `boundSegId` + `arcOnFilDev`.

Implemented (b) faithfully. Implemented (a) partially: the **kernel half** of
step B is restored (segBboxKernelResident, bindKernelResident with
slot-indexed pose reads + endpoint derivation), with its own
`motorBindingResident` task graph in `GPUMotorBinding`. The **merged-executor
half** of step B is **not** restored — the parity check only validates kernel
math, which does not depend on the merge mechanism, and re-introducing the
merge would re-introduce the 363 MB/rebuild leak documented in Part 1.
The fallback gate `BOA_PHASE45_HOST_PACK=1` is therefore moot at HEAD
(host-pack IS the default production dispatch). The resident path is
exercised exclusively by the parity harness via a separate plan with
EVERY_EXECUTION upload of the slot-indexed pose buffers. Same kernel math,
no merge, no leak.

### Harness setup

Env-var gated: `BOA_PHASE45_PARITY=<start>:<stop>` (or `=<step>` for a
single-step check). At every step in `[start, stop]`, immediately after
`GPUMotorBinding.detectBindings()` completes the host-pack dispatch:

1. The current host-pack `boundSegId` / `arcOnFilDev` is snapshotted to Java
   heap arrays (motor capacity = 500 000).
2. `GPUMoveThing.demandSyncPoseToHostForParity()` triggers a UNDER_DEMAND
   `transferToHost(coord, uVec, yVec)` so the parity plan's EVERY_EXECUTION
   upload sees the same pose values the host-pack just consumed.
3. `GPUMotorBinding.parityCheck()` builds the resident plan lazily on first
   call, then dispatches it on the same frozen pose. The resident plan reads
   `coord/uVec/soaLengthArr` via the slot maps populated by
   `GPUMoveThing.classifyThings()` and derives motor tip = `coord +
   0.5·motorLen·uVec`, fil endpoints = `coord ± 0.5·length·uVec`, on-device.
4. Element-wise diff of `boundSegId` (exact match expected) and
   `arcOnFilDev` (float32 ULP-scale expected; resident path computes
   `arcOnFil = alpha·length`, host-pack computes `alpha·sqrt(denom)`).
5. At the last step in the window, `reportParitySummary()` prints the
   aggregate, then `System.exit(0)` halts the run before any further
   integration drifts the pose.

The "freeze" between dispatches is structural: nothing between
`detectBindings` returning and `parityCheck` firing mutates
`Thing.soaCoord/soaUVec`, the slot maps, or the bind plan inputs. Both
dispatches see byte-identical inputs (modulo the float-vs-double pose
representation difference between `Thing.soaCoord[float]` → in-kernel float
derivation vs `MyoMotor.soaX[double]` → `(float)cast` → `motPos[float]` pack
path, which is the source of the expected sub-ULP arc divergence).

The parity harness shares Java FloatArray / IntArray references with the
move plan (slot maps, coord/uVec/soaLengthArr buffers) but lives in its own
`TornadoExecutionPlan` — each plan owns its own device-side buffers, fed by
its own transferToDevice list. No `consumeFromDevice`, no plan merge, no
leak.

### Results

Three runs of the windowed parity check, `glidingAssay500_val` seed=1:

| window | runs | motor-decisions | matched arc hits | segId mismatches | max |Δarc| | mean |Δarc| | rms |Δarc| |
|---|---|---|---|---|---|---|---|
| [200, 200] (single)  | 1   | 14 000        | 1   | 0 | 0          | 0          | 0          |
| [100, 200]           | 101 | 1 414 000     | 7   | 0 | 1.49e-08   | 2.83e-09   | 5.82e-09   |
| [100, 950]           | 851 | 11 914 000    | 136 | 0 | 5.07e-07   | 2.04e-08   | 7.32e-08   |

The [100, 950] window's `|Δarc|` distribution (matched-seg only):

```
=0           : 33
(0, 1e-8)    : 50
[1e-8, 1e-7) : 50
[1e-7, 1e-6) :  3
[1e-6, 1e-5) :  0
[1e-5, 1e-4) :  0
>= 1e-4      :  0
```

Worst case: `hostArc = 0.234759`, `devArc = 0.234759`, `|Δ| = 5.07e-07` —
ULP-scale for a float32 quantity at the ~0.2 µm position scale (β32 ULP at
0.25 ≈ 3e-8, with several float ops compounding → ~5e-7 worst-of-N
distribution tail).

Logs: `RUN_LOGS/2026-06-06_phase45_parity/window_100_950_seed1.log` (full),
`window_100_200_seed1.log` (shorter), and the raw v2 dump from the
histogram-label fix.

The dataset is sparser than ideal — `glidingAssay500_val` spawns only 11
filament segments under 14 000 motors. Most motors find no candidate
on most steps, so the matched-arc count stays in the low hundreds across an
850-step window. But the **segId** check exercises all 11.9 M motor-decisions
(every step, every motor walks the 27-cell neighbourhood and decides), and
every single one of those decisions agrees across paths. The arc check has
136 data points, all well under the 1e-6 ULP cutoff.

### Verdict

`{kernel-math-correct}`.

The resident-pose bind kernel produces the same `boundSegId` as the
host-pack path on every motor decision tested (11.9 M of them), and emits
`arcOnFil` values within float32 ULP scale of the host-pack `alpha·sqrt(denom)`
formulation. The `alpha·length` simplification holds (device `uVec` is unit,
so `length == |segment|` up to float error). Slot-map lookups resolve
correctly — no spurious binds from `-1`-slot reads, no wrong-segment reads.

### What this unlocks

The plan-invariant buffer refactor (PHASE45_PLAN.md Part 2's deferred work)
no longer carries kernel-math risk. When a future session re-attempts the
merged-executor + `consumeFromDevice` path with the persistent-buffer
lifecycle fix, the kernel math IS the same code path validated here — the
remaining work is purely about plumbing a verified kernel into a
non-leaking buffer lifecycle.

### Commit

Branch `phase45-binding-residency`, commit `04ec925`
("Phase 4.5 frozen-pose kernel-parity — resident-pose bind kernel
verified").

