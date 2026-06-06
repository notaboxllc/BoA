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
