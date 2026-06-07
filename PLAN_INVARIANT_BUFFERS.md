# Design — plan-invariant resident buffers for dynamic-topology GPU residency

Status: design / pre-implementation. Anchors the refactor deferred at PHASE45_PLAN.md Part 2.
The Phase 4.5 bind kernel math is validated (frozen-pose parity, 11.9 M decisions, 0
mismatches, `{kernel-math-correct}`), so this work is now purely about the buffer lifecycle
the verified kernel plugs into.

## The problem, stated precisely

Today every topology change — filament split, depoly, length change, spawn, and the
swap-compaction cleanup — trips `markTopologyDirty` → `closePlan` → `allocateAndBuildPlan`,
which does two things at once:

1. **Reallocates** the move plan's pose FloatArrays as new Java objects and re-snapshots the
   ITG, and
2. **Re-uploads** the (possibly mutated) host pose to the device via a fresh FIRST_EXECUTION.

Item 2 is load-bearing for correctness — it is currently the *only* path by which CPU-mutated
pose reaches the device. Item 1 is the damage: in the merged executor, the new Java identities
orphan the bind ITG's `consumeFromDevice` device association, leaking ~363 MB/rebuild (Part 1).

Two independent facts make rebuild-on-topology-change untenable as the residency substrate:

- **Correctness/leak:** the reallocation breaks the cross-graph handoff (measured).
- **Dynamics:** in a real simulation, topology churns continuously (filaments constantly
  splitting, vanishing, gaining/shedding segments). So rebuild-on-change is not a startup
  artifact — it is the normal per-step beat, which makes the leak fatal at any budget and the
  rebuild cost a permanent per-step tax even if the leak were fixed.

Conclusion: the execution plan must become **invariant to topology change**. Topology change
has to be expressed as *data written into resident buffers*, not *structure that rebuilds the
graph*.

## Target model

Standard dynamic-count particle/MD pattern (LAMMPS/GROMACS/HOOMD; reactive/agent codes for
creation–destruction), which is also the v2 residency direction, so the work ports:

- **Allocate device buffers once to a capacity** (max expected count + headroom). Identities
  are stable for the whole run.
- **Active-count + slot maps** track which slots are live. New entities take a free/next slot;
  removed entities are marked dead (or swap-compacted with stable instance IDs). The slot maps
  (`filMoveSlot/motMoveSlot/motRodMoveSlot`, already built in step A) are the translation.
- **Kernels mask dead slots** (the `-1`/liveness short-circuit the parity kernel already
  uses) and iterate over capacity or active-count.
- **Topology change becomes a per-step data update**: slot-map/liveness writes plus pose for
  the *changed* slots (new spawns, biochem-mutated, compaction reshuffles). Existing entities'
  `coord/uVec` stay device-resident and must **not** be clobbered.
- **Reallocate only on capacity-exceed**, growing with headroom (~1.5–2×) like a vector — rare,
  not per-step.

Because the buffers never move except on rare growth, the bind ITG's `consumeFromDevice`
reference stays valid for the whole run. The leak, the re-upload coupling, and the per-rebuild
cost all dissolve in the same move. This is also the efficient answer (no per-change rebuild,
no realloc churn) — the criterion from earlier.

## The pivotal scope question (decides whether this is a small fix or a real refactor)

The size of this work hinges on one thing the survey must settle: **are the move plan's device
buffers already capacity-allocated to a fixed over-size that does not grow during a run?**

There's a hint they might be — step A sized the slot maps to `MyoMotor.soaX.length` /
`FilSegment.soaEnd1X.length`, and the parity harness used motor capacity 500 000. If the SoA
arrays (and the device buffers built from them) are already a fixed large capacity that a
normal run never outgrows, then:

- The per-rebuild reallocation isn't doing capacity growth at all — it's reallocating
  *same-sized* buffers on every topology change purely to force the re-upload.
- The fix collapses to: **stop reallocating** (reuse the FloatArray identities across
  rebuilds) **+ a separate re-upload path** for mutated pose. No capacity/growth redesign, no
  new masking (the `-1` slot handling already exists).

If instead the buffers are sized to the current count and genuinely grow on spawn, the fuller
capacity + active-count + growth-policy design above is needed. **The survey decides which.**
Don't assume; this is exactly the kind of code-spread question CC should enumerate.

## The re-upload decision

Whatever the scope, mutated/new pose must reach the device each step without a plan rebuild.
The options, to be chosen against what the survey finds:

1. **EVERY_EXECUTION on the pose buffers.** Simplest. Safe *only if* `demandSyncPoseToHost`
   already refreshes host pose from the device every step (so the host holds the authoritative
   value and an upload round-trips rather than clobbers device integration). Cost was estimated
   at ~3% wall. Philosophically backward for "residency," acceptable as an interim, retired in
   Phase 5 with the demand-sync.
2. **Targeted dirty-slice upload** — upload only the slots that changed. Cleaner and cheaper on
   PCIe, but needs a dirty-set mechanism and a per-buffer/partial upload primitive that
   TornadoVM 4.0.1-dev may or may not expose without a rebuild (survey item — Part 2 found no
   public runtime `transferToDevice`).

Option 1's safety hinges entirely on the demand-sync cadence, so that's a survey question, not
an assumption.

## Survey first (CC, survey-only, no edits — append findings to this doc)

1. **Capacity:** are `MyoMotor.soaX`/`FilSegment.soaEnd1X` (and the device buffers built from
   them) a fixed over-allocated capacity, or sized to current count and grown on spawn? Does a
   normal `glidingAssay500_val` run (and a dense run) ever actually grow them, or only
   re-snapshot same-sized buffers? *This decides the scope.*
2. **Topology mutation entry points:** where are filaments/motors/segments created and
   destroyed (split, depoly, spawn, swap-compaction cleanup), and do they all funnel through
   `markTopologyDirty`? Is the swap-with-last-and-decrement reshuffle per-step, and are stable
   `thingInstanceId` fields in place?
3. **Sync cadence:** does `demandSyncPoseToHost` run every step today, or only at output
   frames? (Decides whether EVERY_EXECUTION re-upload is clobber-safe.)
4. **Masking surface:** which kernels read pose and would need dead-slot masking, and which
   already short-circuit on `slot == -1`?
5. **TornadoVM API:** any 4.0.1-dev mechanism for per-step partial/dirty re-upload without a
   plan rebuild (EVERY_EXECUTION granularity; any partial-transfer hook).

Hard bail-out: if the survey shows the buffers genuinely grow mid-run *and* there is no
non-rebuild re-upload primitive, stop and report — that combination changes the plan and is
worth a design pass before code.

## Migration + validation

Incremental, compile-and-load at each step, on `phase45-binding-residency`, branch only:

1. Survey (above) → settle scope and the re-upload option.
2. Make buffer identities persistent across rebuilds (stop reallocating; reuse references).
   Re-run with `BOA_PHASE45_MEM_TRACE=1` — memory must be **flat** across topology rebuilds.
3. Add the chosen re-upload path so biochem-mutated pose still reaches the device. Confirm CPU
   and GPU pose agree after a forced poly/depoly.
4. Add the merged executor + `consumeFromDevice`. Re-run the **frozen-pose parity harness**
   (already built) now through the real handoff — must stay **0 boundSegId mismatches**,
   `|Δarc|` ≤ 1e-6. This is the plumbing check the kernel-math check couldn't cover.
5. Full validation gate: full-run no-OOM with flat memory; N=4 paired binding ensemble vs the
   clean baseline (~880–893, gv ~8); poison-inert (structural confirmation the read is on
   device); CPU sanity; binding speedup (~6 ms/call pose-pack/PCIe gone).

The parity harness and the memory trace are both already in the branch, so steps 2 and 4 have
their instruments ready.

## Survey findings (2026-06-06)

Survey-only pass, no code edits. Light grep + javap + re-read of the existing baseline log.

### Q1 — Capacity (the scope-decider)

Two distinct sizing regimes, both relevant:

**Class-level SoA arrays (truly fixed).** Statically allocated at class load and never resized:

- `MyoMotor.soaX/Y/Z/UX/UY/UZ/OnFil/RodUX/RodUY/RodUZ` — `new double[500000]` /
  `new boolean[500000]` at `boxOfActin/MyoMotor.java:11-20`.
- `FilSegment.soaEnd1X/Y/Z/End2X/Y/Z/UX/UY/UZ/FilID/NodeAtEnd2` — `new double[1000000]` /
  `new int[1000000]` / `new boolean[1000000]` at `boxOfActin/FilSegment.java:29-39`.
- `MyoMotor.theMotors[500000]` and `FilSegment.theFilSegments[1000000]` — the object arrays
  themselves are likewise fixed at class load (`MyoMotor.java:4`, `FilSegment.java:23`).

These are the capacities the Phase 4.5 step-A slot maps (`motMoveSlot`, `filMoveSlot`) match
(`GPUMoveThing.java:2559-2563`: `bindMotorCap = MyoMotor.soaX.length`,
`bindSegCap = FilSegment.soaEnd1X.length`). They never grow during a run; overflow would
require >500 K motors or >1 M segments, which would AIOOB at creation
(`Thing.addThing` / `MyoMotor.split…/theMotors[motorCt]=…`) before any plan logic ran.

**Move-plan device FloatArrays (dynamic-2x, but in practice never grow mid-run).** Sized to
`slotCap = max(1024, Thing.thingCt * 2)` and `myoCap = max(1024, Myosin.myoCt * 2)` at first
build (`GPUMoveThing.onStepStart`, line 3594-3598; `allocateAndBuildPlan`, line 2534-2576).
Growth path = `Thing.thingCt > slotCap || Myosin.myoCt > myoCap` (`onStepStart`, line 3601):
`closePlan()` + `allocateAndBuildPlan(max(slotCap*2, thingCt*2), ...)`. **The
topology-dirty rebuild path (line 3610-3633) calls `allocateAndBuildPlan(slotCap, myoCap)` —
same caps, same-sized buffers, just new Java identities.**

What the existing instrumented baseline shows
(`RUN_LOGS/2026-06-06_phase45_p1_baseline/head_4gb_seed1.log`,
`glidingAssay500_val`, 10101 steps):

- Initial build: `slotCap=84004`, `myoCap=28000` (= 2× Thing.thingCt=42002 / Myosin.myoCt=14000).
- **0 `capGrow` events** across the entire run (grep `capGrow` → zero hits).
- **11 `topoDirty` rebuilds**, all in steps 1–11 — the spawning ramp where
  `Thing.thingCt` grows by exactly +1 per step from 42003 to 42012, tripping
  `Thing.thingCt != lastThingCt`. After step 11 there are zero further rebuilds for the
  full 10101 steps (periodic mem ticks at steps 500…10000 all report `rebuildIdx=11`).
- Every topoDirty rebuild reallocates same-sized buffers (`moveMem` cycles 7,056,416 → 0 →
  7,056,416 exactly, per the trace).

**Plain answer: the device buffers do NOT grow during a normal `glidingAssay500_val` run.**
They are dynamic-2x at first build, but the 2x headroom + the slow per-step growth of
`thingCt` (biochem splits at ~1/1000 steps in steady state per the existing Part-1 notes)
keeps `Thing.thingCt < slotCap` for the entire run. Topology-dirty rebuilds are *same-sized
re-snapshots* whose sole effect is to force a fresh FIRST_EXECUTION upload — exactly the
"reallocating to force re-upload" pattern this document predicted as the hint for the
small-fix verdict.

Caveats: a denser parameter file or a run with sustained nucleation/random-spawn could in
principle trip capGrow. The fix should preserve the capGrow path as a rare-event fallback;
it does not need to optimise for it.

### Q2 — Mutation entry points

**Creation paths:**

- `Thing.addThing()` (`Thing.java:926`) — appends `theThings[thingCt++]`, assigns
  `myThingNumber`, ensures `taCapacity`, stamps stable `thingInstanceId` (set once in
  `Thing` ctor, never reassigned — `Thing.java:28, 156`). All Things (FilSegment, MyoMotor,
  MyoRod, MyoLever, ProteinNode, …) funnel here via their constructors.
- `MyoMotor.addMotor` (`MyoMotor.java:498-500`) — appends to the class-local `theMotors[]`,
  parallel to Thing's table; mirrored counter.
- `FilSegment.splitSegment` (callsite `FilSegment.java:554`) — creates a new FilSegment via
  ctor → `addThing` → `addFilSegment`; followed by `markTopologyDirty()` on line 562.
- `FilSegment.biochemStep` length-change branch (lines 539–550) — when poly/depoly mutates
  length without splitting, `pushCoordToSoa()` flushes the new pose to `Thing.soaCoord` and
  `markTopologyDirty()` fires on line 549.

**Destruction:**

- `Thing.removeThing(byeThing)` (`Thing.java:939-993`) — **swap-with-last-and-decrement
  compaction**, executed PER STEP from `Thing.removeDeadThings()` (line 999) as part of the
  cleanup phase. The swap reassigns `myThingNumber` of the surviving Thing to the dead
  slot, and explicitly moves `soaCoord/soaUVec/soaYVec/soaLength/soaTransXTox` (lines
  956-988) so the host SoA stays consistent with the new slot numbering. `instanceRegistry`
  is updated to drop the dead `thingInstanceId` (line 990); the surviving Thing's
  `thingInstanceId` is unchanged.
- `MyoMotor.removeMotor` (`MyoMotor.java:509-518`) — analogous swap-compact for
  `theMotors[]`.

**Marking the GPU plan dirty.** Two explicit `markTopologyDirty()` callsites in
`FilSegment.biochemStep` (lines 549, 562). Beyond that, the rebuild gate in `onStepStart`
(line 3610) also fires on `Thing.thingCt != lastThingCt`, which catches every add/remove —
i.e. removeThing's swap-compact does not need its own `markTopologyDirty` call because the
thingCt decrement is detected. This is the actual coverage: it's not that every mutation
funnels through one `markTopologyDirty` call, but that the rebuild gate `(topologyDirty ||
Thing.thingCt != lastThingCt)` catches every case in practice.

Stable identity: `Thing.thingInstanceId` is in place (final field, monotonic, never
reassigned, registry-backed via `ConcurrentHashMap`). It survives swap-compaction and is
sufficient to back any future stable-slot-map design that needs to identify which
device-resident slot belongs to which surviving Thing.

### Q3 — Sync cadence

`demandSyncPoseToHost()` is called **once per step, unconditionally** (when `slotCount > 0`)
from inside `GPUMoveThing.moveThings()`:

```
4533    if (sc > 0) {
4534        if (DIAG_CPU_DELTA_ADD) {
4535            dispatchAndWait(OP_UNPACK, sc);     // diag path only
4536        } else {
4537            demandSyncPoseToHost();             // production path
4538        }
4539    }
```

(`boxOfActin/GPUMoveThing.java:4533-4541`, inside `moveThings()`.) `moveThings()` is the
end-of-step phase that drives every active slot's pose forward; the demand-sync is its
trailing step. The doc comment at line 3046-3053 confirms the contract: "Call from the
doLoop's pre-step block BEFORE fillSoaArrays + motor binding so the per-class SoA arrays
... read fresh pose." In practice the doLoop's pre-step block sees the host pose that the
PRIOR step's `moveThings()` demand-synced.

The other call sites are not per-step: `refreshHostMirrorsForOutput()` is frame-only
(`GPUMoveThing.java:3090`); `snapshotResidentPoseToCpu()` is a diagnostic
(`GPUMoveThing.java:3126`); `demandSyncPoseToHostForParity()` is the Phase 4.5 parity
harness (`BoxOfActin.java:1008`).

**Consequence for the re-upload question:** the host `coord/uVec/yVec` FloatArrays (and the
`Thing.soaCoord/UVec/YVec` mirrors) are authoritative every step — the device-output pose
is round-tripped to host every step. An EVERY_EXECUTION host→device upload at the start of
the next step would therefore upload exactly the device's prior-step output (plus any CPU
mutations between moveThings() and the next execute) — not stale CPU data, not a clobber.
The path is clobber-safe.

### Q4 — Masking surface

Pose-reading kernels in the `chained` (move) plan and their dead-slot behaviour:

| kernel | iterator | dead-slot guard |
|---|---|---|
| `jointsKernel` (`GPUMoveThing.java:775-791`) | `m < cockedFlags.getSize()` (myoCap) | `if (m >= M) return;` — M = active myo count from `counts.get(0)` |
| `chainPairForcesKernel` (`GPUMoveThing.java:1134-1140`) | `i < coord.getSize()/3` (slotCap) | `if (i >= N) return;` AND `if (e2Slot < 0 && e1Slot < 0) return;` — explicit `-1` short-circuit on topology slot indices |
| `boundaryBoxKernel` (`GPUMoveThing.java:1520-1528`) | `i < coord.getSize()/3` | `if (i >= N) return;` AND `if (boundaryActive.get(i) == 0) return;` — explicit liveness flag |
| `motorForceKernel` (`GPUMoveThing.java:1810+`) | over motor slots | guarded by `boundSegSlot[m] < 0` short-circuit |
| `segMotorForceKernel` (`GPUMoveThing.java:1979+`) | `i < coord.getSize()/3` | `i >= N` guard, plus CSR `segMotorOffsets` |
| `moveKernel` (`GPUMoveThing.java:2134-2138`) | `m < coord.getSize()/3` | `if (m >= N) return;` — relies on contiguous active count from `counts.get(0)` |

Active counts ride EVERY_EXECUTION via `counts` (`GPUMoveThing.java:2693`); the kernels
already over-iterate up to capacity and early-return past `N`. The bind-plan resident
kernels (Phase 4.5 step B / parity work, `GPUMotorBinding.bindKernelResident` and
`segBboxKernelResident`) already short-circuit on `filMoveSlot.get(s) < 0` and
`motMoveSlot.get(m) < 0` (per `PHASE45_PLAN.md` §"Phase 4.5 frozen-pose kernel-parity").

The masking is uniform and already in place. **No new masking surface is required for the
persistent-buffer fix** — kernels treat dead slots as no-ops today (either because
`m >= N` returns early, or because the topology slot indices the kernel reads are -1).

### Q5 — TornadoVM 4.0.1-dev API

Restating Part 2's finding with `javap` confirmation, plus one nuance not previously
called out.

`uk.ac.manchester.tornado.api.TornadoExecutionPlan` public methods (verified via
`javap -cp tornado-api-4.0.1-dev.jar`):

```
execute, withGraph(int), withAllGraphs, withPreCompilation, withDevice,
withConcurrentDevices/withoutConcurrentDevices, freeDeviceMemory, withGridScheduler,
withDefaultScheduler, withBatch, withProfiler/withoutProfiler, withMemoryLimit/
withoutMemoryLimit, resetDevice, withThreadInfo/withoutThreadInfo, withPrintKernel/
withoutPrintKernel, withCompilerFlags, getCurrentDeviceMemoryUsage, getPlanResult,
mapOnDeviceMemoryRegion, withWarmUpTime/withWarmUpIterations, withCUDAGraph,
close, clearProfiles, getId.
```

**No `transferToDevice` runtime method.** `freeDeviceMemory()` is present but only drops
all device buffers (next execute re-uploads everything via the declared transfer modes).
`mapOnDeviceMemoryRegion(Object, Object, long, int, int)` is an aliasing primitive (one
Java object's memory aliases another's), not a re-upload primitive.

`TornadoExecutionResult.transferToHost(Object...)` / `transferToHost(DataRange)` — runtime
**device→host** transfer, fine-grained via `DataRange.withOffset/withSize`. **No
mirror-named `transferToDevice` on `TornadoExecutionResult`.**

`TaskGraph.transferToDevice(int mode, Object...)` is **declarative** — called at TaskGraph
construction; `mode` is `FIRST_EXECUTION | EVERY_EXECUTION | UNDER_DEMAND` (from
`api.enums.DataTransferMode`). The mode is baked into the snapshotted `ImmutableTaskGraph`;
you cannot change it without rebuilding the graph. There is a package-private
`syncRuntimeTransferToHost(Object, long, long)` with offset+size, but it's host-bound, not
public, and there is no device-bound counterpart.

**Conclusion:** the only mechanism for repeated host→device upload of a buffer without a
plan rebuild is declaring it `EVERY_EXECUTION` at TaskGraph-build time. There is no
dirty-slice/partial host→device transfer primitive in the public 4.0.1-dev API. Part 2's
finding stands; no missed primitive.

### Scope verdict and re-upload option

**Verdict: `{small-fix}`.**

Reasoning: Q1 establishes that the move-plan device pose buffers do not grow during a
normal validated run (0 capGrow events in 10101 steps of `glidingAssay500_val`; 2x initial
headroom is sufficient for the lifetime of the run). The class-level SoA arrays
(`MyoMotor.soaX`, `FilSegment.soaEnd1X`) are truly fixed capacity at 500K / 1M and never
grow. The 11 topoDirty rebuilds observed in baseline are same-sized reallocations whose
*sole* effect is forcing a fresh FIRST_EXECUTION upload — exactly the pattern this doc's
"fix collapses to" framing predicted.

The fix path therefore is:

1. **Persist Java FloatArray identities across topoDirty rebuilds.** Allocate once in a
   first-build path; reuse the same `coord/uVec/yVec/soaLengthArr/topo*/...` Java refs on
   every subsequent rebuild. Reseat them into a fresh TaskGraph if/when the plan must be
   rebuilt for other reasons (kernel signature change, graph topology change, capGrow). The
   bind ITG's `consumeFromDevice` references then stay valid for the lifetime of the run
   *between capGrow events*.
2. **Switch pose buffers from FIRST_EXECUTION to EVERY_EXECUTION** so biochem-mutated host
   pose reaches the device without a plan rebuild. `bTransGam`, `bRotGam`, `soaLengthArr`
   should also flip (they also change on biochem poly/depoly + aeta mutation, today only
   reach the device via rebuild). The slot/topology IntArrays (`topo*Slot/Side`,
   `rodSlots/leverSlots/motorSlots`, `velMask`, `filMoveSlot/motMoveSlot/motRodMoveSlot`)
   also need EVERY_EXECUTION since topology mutations rewrite their contents.
3. **Preserve the capGrow rebuild path** as a rare-event fallback (full close + rebuild +
   merged-executor re-attach). Each capGrow occurrence still leaks ~363 MB in the merged
   model, but at 0 events / 10101 steps in validation the leak budget is trivially bounded.
   No active-count growth-policy redesign needed.

**Re-upload option:** **Option 1 (EVERY_EXECUTION on the pose buffers)**, per Q5 (no
dirty-slice primitive exists) and Q3 (host pose is authoritative every step, so EVERY_-
EXECUTION upload is clobber-safe). Option 2 (dirty-slice) is not available without a plan
rebuild — the survey confirms Part 2's finding. The ~3% wall-clock estimate from Part 2
for pose-only EVERY_EXECUTION stands; the additional EVERY_EXECUTION buffers (`bTransGam`,
`bRotGam`, `soaLengthArr`, slot/topology IntArrays) are each <1 MB and contribute fractions
of a percent on top.

The persistent-buffer fix dissolves the leak, the re-upload coupling, and the per-rebuild
cost in the same move, exactly as the target-model section of this doc predicted.
