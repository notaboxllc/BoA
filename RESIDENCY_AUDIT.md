# Device-residency audit — per-step CPU consumers of pose state

Date: 2026-06-02
Status: SURVEY ONLY (no code changes, no compile, no runs).
Purpose: enumerate every CPU computation that reads canonical pose state
(`coord` / `uVec` / `yVec` and the derived fields `end1Pt` / `end2Pt` / `zVec` /
`transXTox`) on a per-step basis, so the residency campaign can scope what
must move to the GPU before the per-step CPU↔GPU pose transfer can be
eliminated.

**The residency goal in one sentence.** Keep the canonical pose state for
GPU-handled Things resident on the device across steps, downloading only at
output frames. The per-step download is eliminated only when NO per-step CPU
computation needs to read those poses — so the blocking set is the COMPLETE
set of CPU consumers, not just the expensive ones.

The "complete vs most" framing matches **Lessons 1 + 2** from
`GPU_MIGRATION_LESSONS.md`: an incomplete migration leaves a quiet CPU
consumer that silently forces a per-step download (the residency analogue of
a silently dropped force). The catalog below aims to be the full list, so
nothing is forgotten.

References:
- `STEP_PORT_SURVEY.md` is the ground truth for `Thing.step()`'s F1–F12
  forces; this audit cites them by ID rather than re-surveying.
- `BoxOfActin.doLoop()` lines 695–880 are the per-step orchestration; phase
  IDs are defined in `Env.java` lines 70–107.

---

## Part 1 — Per-step CPU consumers of pose state

Walking `BoxOfActin.doLoop()` in order. Each phase below RUNS EVERY STEP
unless flagged otherwise. "Reads pose" means it reads `soaCoord` / `soaUVec`
/ `soaYVec`, or the derived `soaEnd1` / `soaEnd2` / `soaZVec` /
`soaTransXTox`, or the per-Thing Pt3D snapshots (`end1Pt` / `end2Pt`,
`MyoMotor.bindTip`) that mirror them.

### A1. Pre-step SoA sync (every step, CPU)

- **`MyoMotor.fillSoaArrays()`** (`BoxOfActin.doLoop:722`) — reads
  `Thing.soaCoord` / `soaUVec` for every motor, derives end2 (= `coord +
  halfLen·uVec`), and stores into `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodUX/RodUY/
  RodUZ`. Pose consumer.
- **`FilSegment.fillSoaArrays()`** (`BoxOfActin.doLoop:723`) — reads
  `Thing.soaCoord` / `soaUVec` for every filament segment, derives end1/end2
  with `coord ± (length/2)·uVec`, stores into `FilSegment.soaEnd1X..soaEnd2Z`.
  Pose consumer.
- **`GPUMoveThing.onStepStart()`** (`BoxOfActin.doLoop:727`, GPU path only)
  — no-op when topology stable; recomputes the GPU slot map. Does not read
  pose itself; the FIRST_EXECUTION upload is handled separately.

### A2. Mesh fills + meshColl (cadence: every `collisionCheckInt` steps; default = 1)

`collisionCheckInt = collisionDeltaT / deltaT`; both default to 1e-4 →
**every step**. Param files can decimate.

- **`Mesh.MeshThreads` / meshFils** (`Mesh.java:128`, jobId
  `meshFilsStart`) — reads `curSeg.end1AsPt3D()` / `end2AsPt3D()` for every
  filament segment to bin into the 2D `FILSEG_MESH`. Pose consumer.
- **meshNodes** (`Mesh.java:136`) — reads `node.getCoordX()/Y()` /
  `node.getRadius()` to bin every `ProteinNode`. Pose consumer.
- **meshMotors** (`Mesh.java:141`) — reads `motor.bindTip.x/y` (the Pt3D
  snapshot of end2) to bin every `MyoMotor`. Pose consumer.
- **`Mesh.CkMeshThreads` / meshColl** (`Mesh.java:175`) — runs
  `FilSegment.filSegMeshCollisions` (FilLink creation broad-phase),
  optionally `ProteinNode.nodeMeshCollisions` and
  `FilSegment.membraneFilMeshCollisions`. Each reads `uVec`, `end1Pt`,
  `end2Pt` to do line-segment intersect / pair-collision tests, then calls
  `FilLink.makeLink` / `NodeLink.makeNodeLink` on hits. Pose consumer.

### A3. `MotorBindGrid3D.FillThreads` + motor-binding (every step)

- **`MotorBindGrid3D.FillThreads`** (`MotorBindGrid3D.java:307`) — reads
  `fs.end1AsPt3D()` / `fs.end2AsPt3D()` and `m.bindTip` for every filament
  and motor; bins them into a 3D cubic grid. Pose consumer, every step
  (motor positions change every integration step).
- **CPU path: `Mesh.CkMotsThreads` / motCollStart**
  (`Mesh.java:215` → `MotorBindGrid3D.motorFilCollisions`) — reads
  `MyoMotor.soaX/Y/Z` and `filCells[][][]` (the bin contents) to scan 27
  neighbour cells per motor and call `MyoMotor.checkFilSegCollision`. Pose
  consumer.
- **GPU path: `GPUMotorBinding.detectBindings()`** (`GPUMotorBinding.java:250`)
  — packs `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodUX..RodUZ/OnFil` and
  `FilSegment.soaEnd1X..soaEnd2Z/NodeAtEnd2` and the CSR-flattened
  `MotorBindGrid3D` grid into FloatArray/IntArray, then runs the device
  `bindKernel`. The CPU-side pack reads pose every step, and the CPU-side
  grid build is still the source of the CSR data. The kernel itself is on
  the device, but the inputs are sourced from CPU pose. Pose consumer
  (transitively).

### A4. Brownian forces (cadence: every `brownianApplyInt` steps; default = 1)

- **`Thing.calcRandomForces()`** (`Thing.java:873`) — reads `bTransDiff`,
  `bRotDiff`, `bTransGam`, `bRotGam` only. **Does NOT read pose**
  directly. The random forces it produces are body-frame and stored in
  `randForces` / `randTorques`; they get rotated into the lab frame inside
  `moveThing()` via `xToX`, which reads `soaTransXTox`. So Brownian
  *generation* is not a per-step pose consumer; *application* (inside
  moveThing) is.
- On the GPU path, GPU-handled Things are skipped in CPU Brownian
  generation (Wang-hash inline in the move kernel); CPU-fallback Things
  still run CPU Brownian.

### A5. xLink phase — `FilLink`, `Arp23`, `ActA` (every step, CPU)

All three run in the same wave (xLinkStart = actAStart = 6).

- **`FilLink.XLinkThreads`** → `FilLink.enforceFilLink()`
  (`FilLink.java:354`) — every active link reads `fil1.uVecAsPt3D()`,
  `fil2.uVecAsPt3D()`, `fil1.end1AsPt3D()`, `fil2.end1AsPt3D()` (via
  `updatePts` then `applyTransForce` / `applyTorsionForce`). Also does
  `checkForAnnealing` which reads `end2Pt` / `end1Pt` of both filaments.
  Pose consumer. Forces are pointer-traversed (fixed `fil1`/`fil2`
  references), not broad-phase.
- **`Arp23.Arp23Threads`** → `Arp23.enforceFilLink()` (`Arp23.java:111`)
  — reads `motherFil.end1AsPt3D()`, `motherFil.uVecAsPt3D()`,
  `daughterFil.end1AsPt3D()`, `daughterFil.uVecAsPt3D()` and the body-frame
  pose of the mother (`curDUVec.xToX(motherFil, relaxDUVec)`). Pose
  consumer. Pointer-traversed.
- **`ActA.ActAThreads`** → `ActA.step()` (`ActA.java:111`, dispatched at
  actAStart) — when `bound`, reads `boundFil.coord` / endpoint near the
  binding location and `lmBug.coord`. Note: ActA is NOT a `Thing`; its
  `step()` method runs in the actAStart wave, not in the `Thing.step()`
  wave. (This is the F11 entry in the step survey; see Part 4.) Pose
  consumer.

### A6. Membrane links — `NodeLink.enforceNodeLink` (every step, CPU)

- **`NodeLink.NodeLinkThreads`** (`NodeLink.java:102`) — reads
  `node1.getCurrentLinkLoc(pt1,loc1)` / `node2.getCurrentLinkLoc(pt2,loc2)`
  (= node pose), computes `linkLength = ptDist(pt1, pt2)` and applies a
  spring force. Pose consumer. StickyNode pose is CPU-resident already (not
  in the GPU-handled set).
- Also re-runs **N times in the membrane relaxation loop** at the end of
  each timestep (`BoxOfActin.doLoop:863–878`), up to
  `Env.maxMembranePasses` per step. Each pass: links + gather + membraneMove
  (CPU integration of node coords). All on CPU; reads/writes CPU node
  pose only.

### A7. Myosin joints — `myoJoints1` (every step)

- **CPU path: `Myosin.MyosinThreads`** → `Myosin.jointConstraints()`
  (`Myosin.java:342` + Myosin.java:164 onwards) — for every Myosin reads
  `myoLever.end2AsPt3D()`, `myoMotor.end1AsPt3D()`, rod/lever/motor
  `uVec` / `yVec` / `uVecR`. Six force/torque applications. Pose consumer.
- **GPU path: per-Myosin joints kernel** runs inside the chained
  `GPUMoveThing` TaskGraph. The kernel reads device-side rod/lever/motor
  pose (already on device from the prior step's move kernel). On the GPU
  path the CPU `MyosinThreads` short-circuits the full
  `jointConstraints()` and runs only `applyGPUDroppedForces()` =
  `MyosinFixed.applyRodFixedPtForce()` (the rod-tail anchor spring) —
  which **does** read `myoRod.end1AsPt3D()` on the CPU every step. This
  is the "anchor force lesson" residue: the GPU path still has a CPU
  pose consumer for the anchor.
- **`MyosinDimer.MyoDimerThreads`** → `MyosinDimer.jointConstraints()`
  (`MyosinDimer.java:268`) — cross-Myosin coupling (parallel /
  antiparallel rod-rod). Reads rod poses of both Myosins in the dimer.
  CPU only on both paths. Pose consumer.

### A8. Myosin chamber tethers — `myoJoints2` (every step, CPU)

- **`Crucible.ChamberMyoThreads`** → `Crucible.keepMyosinOnSurface(i)`
  (`Crucible.java:115`) — reads `curRod.end1AsPt3D()` for every
  chamber-fixed myosin and applies a spring to `theBox.myoPtsInX[i]`. Pose
  consumer.
- **`Crucible.ChamberMyoDThreads`** → `keepMyosinDimerOnSurface(i)`
  (`Crucible.java:137`) — same idea for chamber-fixed dimers. Pose
  consumer.

### A9. `Thing.step()` — the F1–F12 forces (every step, CPU)

Catalogued in `STEP_PORT_SURVEY.md` Table A; referenced here by ID rather
than re-surveyed.

- **F1** `FilSegment.checkBugOrBoxCollision` (inside chamber) — reads
  `end1Pt`, `end2Pt`, `coord`. Per-entity vs static boundary.
- **F2** `FilSegment.checkBugCollisionFromOutside` (Listeria) — reads
  `end1Pt`, `end2Pt`, `lmBug.coord`. Pairwise filament ↔ bug.
- **F3** `FilSegment.addLinkForces` — chain link translational spring.
  Reads own/neighbour `end1Pt` / `end2Pt` / `uVec` / `uVecR`. Pairwise
  pointer-traversed.
- **F4** `FilSegment.addTorsionSpringForces` — chain torsion / bending
  rigidity. Reads own/neighbour `uVec`. Pairwise pointer-traversed.
- **F5** `FilSegment.addNodeForces` (translational) — reads own endpoint
  and `node.coord`. Pairwise pointer-traversed.
- **F6** `FilSegment.addNodeForces` (alignment torque, inside
  `nodeTorqSpring.isActive()`) — reads own `uVec`, `forminVecInx` body-
  frame, `transXTox` on node.
- **F7** `MyoMiniFilament.checkOuterBugCollision` — reads own `end1` /
  `end2`. Per-entity vs boundary.
- **F8** `MyoFilLink.addForces` — motor head tether spring. Reads
  `motorPt` (= `bindTip`), `seg.end1` + `posOnSeg·seg.uVec`. Pairwise
  pointer-traversed (motor's `tipLink.mySeg`).
- **F9** `MyoFilLink.alignUVecTorque` — reads motor/seg `uVec`,
  `isCocked`.
- **F10** `MyoFilLink.alignYVecTorque` — reads motor/seg `yVec`.
- **F11** ActA tether (`ActA.applyTetherForce`) — **already accounted for
  in A5**: runs in actAStart, NOT in `Thing.step()`. The STEP_PORT_SURVEY
  table A includes it as F11 because it is conceptually a step-like force,
  but its dispatch lives in the xLink wave. Confirm in Part 4 below.
- **F12** `ProteinNode.checkBugOrBoxCollision` — reads `coord`,
  `radius`.
- **F13** StickyNode — inherits `ProteinNode.step()` (no override); F13 is
  effectively F12 for sticky nodes. The sticky tether forces run in
  membraneLinks (A6), not in step().

All F1–F10, F12 are dispatched by `Thing.ThingStepThreads` at `stepStart`,
on **every Thing** (CPU). Pose consumer.

### A10. Gather forces — `gatherThreadAccumulators` (every step, CPU)

- **`Thing.gatherThreadAccumulators()`** (called from
  `ThingStepThreads.execute` at `gatherForcesStart`) — sums per-thread
  `taForce[t]` / `taTorque[t]` doubles into the `soaForceSum` /
  `soaTorqueSum` floats. **Does NOT read pose** — only reads/writes the
  force accumulator buffers. Force buffers, not pose. Not a pose
  consumer.

### A11. Benchmark force injection / pin restoration (bench mode only)

- **`midSeg.incForceSum(deflFil.transForce)`** (`BoxOfActin.doLoop:813`)
  — writes a midpoint force into `soaForceSum`. Reads pose only to
  diagnose (a `getCoordY` printf inside `benchStepCount < 10` guard,
  line 818–821). Pose consumer only in early-step diagnostic; once past
  step 10, no pose read.
- **`applyBenchmarkPins()`** (`BoxOfActin.java:1336`) — reads
  `firstSeg.getEnd1X/Y/Z()`, `lastSeg.getEnd2X/Y/Z()` to compute the
  pin correction `coord -=` delta, then calls `initialize()` on both
  pinned segments. Runs every step in bench mode. Pose consumer.

### A12. Integration — `Thing.moveThing()` (every step)

- **CPU path: `Thing.ThingStepThreads` at `moveStart`** — for every
  Thing, reads its current pose (`bForceSum.XToxFromFloats(this, ...)`
  uses the body-frame transform = `soaTransXTox`; `bForceSum` etc. body-
  frame conversions read `soaUVec` / `soaYVec` indirectly), integrates
  position/orientation, and writes the new pose. Pose consumer **and**
  pose producer.
- **GPU path: `GPUMoveThing.moveThings()`** — runs the move kernel on
  device for GPU-handled Things (`gpuHandled = true`). After
  `plan.execute()`:
  - `OP_UNPACK` copies device pose → host `soaCoord` / `soaUVec` /
    `soaYVec` for every GPU-handled Thing. **This is the per-step
    download that residency aims to eliminate.**
  - CPU `moveThing()` runs serially for every `cpuFallback[]` entry
    (Bug, ProteinNode, MyoMiniFilament, branch FilSegments,
    ActA-bound FilSegments, StickyNode, etc.). These read+write CPU
    pose; their poses are CPU-resident throughout.
  - `recomputeDerivedSoA(0, tc)` for ALL Things, then per-FilSegment
    refresh of `xRange/yRange/zRange` + `end1Pt`/`end2Pt` Pt3D, and
    per-MyoMotor refresh of `bindTip` Pt3D. These are CPU consumers of
    CPU pose — but they exist *because downstream CPU consumers
    (mesh fill, FilLink, etc.) read these fields*. Once those consumers
    are gone, this whole post-move CPU sync block can be deleted too.

### A13. Biochem — `Thing.biochemStep()` (every step, CPU)

- **`FilSegment.biochemStep()`** (`FilSegment.java:485`) — does not read
  pose directly; runs poly/depoly probability rolls, calls
  `hydrolizeInFilaments`, `checkCofilinDissolve`, `end1BiochemSim`,
  `end2BiochemSim`. When `lengthChanged`, calls `calculateProperties`,
  `pushCoordToSoa`, `initialize`. The poly/depoly path **writes** pose
  (segment grows/shrinks → `coord` shifts). Conditional pose writer; not
  a pose reader in the steady case.
- **`MyoMotor.biochemStep()`** (`MyoMotor.java:213`) — nucleotide-state
  transitions only. Does not read pose.
- **`MyoMiniFilament.biochemStep`**, **`ProteinNode.biochemStep`** —
  stochastic state transitions. No pose reads in either.

The conditional pose **writes** (poly/depoly, split) need to be
considered separately: when they fire, the device pose for that segment
goes stale unless the same edit happens on the device. Today the path is
CPU-only (`pushCoordToSoa` writes the host SoA; the next step's GPU pack
picks it up). For residency, this becomes a "device write on poly/depoly"
event — but it is rare (gated by `biochemCheckInt`) and topology-changing,
so a topology-dirty bit + targeted device update is a clean handling.

### A14. resetCounters (every step, CPU)

- **`Thing.resetCounters()`** (`Thing.java:913`) — zeros `bFricForceSum`,
  `bFricTorqueSum`, `collisionCt`. Per-subclass overrides (`FilSegment`,
  `ProteinNode`, `MyoMiniFilament`, `Bug`) add their own bookkeeping
  zeros. **No pose reads.**

### A15. updateCounters (every step, CPU)

- **`BoxOfActin.updateCounters()`** (`BoxOfActin.java:1630`) — increments
  global step counters. No pose reads.

### A16. Per-step diagnostic samplers (opt-in, off by default)

- **`JointDiag.sample()`** — reads rod/lever/motor `uVec` / `yVec` /
  `end2` for the single diagnostic Myosin. Guarded by `JointDiag.ENABLED`;
  off in production.
- **`JointParamDiag.sample()`** — same, guarded by `BOA_DIAG_PARAMS`.
- **`SingleMyoDiag.sample()`** — reads `mt.getEnd2X/Y/Z()`, `uVec.z`.
  Guarded by `BOA_DIAG_SINGLE_MYO`. Off in production.

These are diagnostic-mode CPU pose consumers. In production runs they
don't fire; for the residency campaign they can stay CPU (gate on flag,
or accept that turning them on forces a download).

### A17. Per-step assay sampler (gliding assay only)

- **`GlidingAssayEvaluator.sampleStep()`**
  (`GlidingAssayEvaluator.java:89`) — every step when
  `Env.glidingAssay.isActive()`. Iterates filaments and motors counting
  `filID` and `onFil`; **does NOT read pose**. The pose-heavy
  `outputInterval()` work runs at output cadence, not per step. Safe.

### A18. ThreeJSWriter (output cadence — NOT per step)

- **`ThreeJSWriter.writeFrame()`** (`ThreeJSWriter.java:170`) — reads
  pose for every segment / myosin / minifilament to build the per-frame
  JSON. Called from `BoxOfActin.logAndDraw()` gated on `threeJSCounter
  >= Env.toFileInterval.getIntValue()` (`BoxOfActin.java:1678`). Not a
  per-step consumer. Pose download at output frames is exactly the
  residency model.
- **`ThreeJSWriter.buildInspectJson(id)`** (called from
  `drainInspectQueue()` at the safe point) — reads pose for the clicked
  Thing. Bounded by user-driven inspect requests, not per-step.

### A19. C4 paramQueue drain / `aeta` hook (rare)

- **`drainParamQueue`** at the safe point — for `aeta` changes calls
  `calculateProperties()` on every FilSegment, which reads `length` but
  **not pose** (drag tensor refresh is from `length` + `bRotCoeff`
  family). Not a per-step pose consumer.

### A20. WebSocket / inspect / cleanup (not per step)

- LiveFrameServer dispatch, cleanup1..4, simJsons writers: all output
  cadence or post-safe-point. Not per-step pose consumers.

---

## Part 2 — Classification

Each per-step pose consumer from Part 1 gets one tag. The **{must port}**
set is the residency blocking list; until every entry on it is moved off
CPU pose, the per-step pose download cannot be eliminated.

| # | Consumer | Phase | Classification | Notes |
|---|---|---|---|---|
| A1.a | `MyoMotor.fillSoaArrays` | pre-step | **{must port}** | Trivial host loop, but every read is from `Thing.soaCoord/soaUVec`. Either delete (its output feeds binding pack — A3 — which is also must-port; consolidate) or run on device. |
| A1.b | `FilSegment.fillSoaArrays` | pre-step | **{must port}** | Same shape. Trivial in cost but a pose reader. |
| A1.c | `GPUMoveThing.onStepStart` | pre-step | {already on GPU} | Topology check + slot map; no pose read. |
| A2.a | meshFils (`FILSEG_MESH` fill) | meshFils | **{must port}** OR **{can reduce frequency}** | Default cadence is every step. Param files often run `collisionCheckInt > 1`; in that case decimation is genuine. But default config = per-step pose read. Porting means a device-side spatial grid for filaments. |
| A2.b | meshNodes (`NODE_MESH` fill) | meshNodes | **{can reduce frequency}** OR keep CPU | StickyNode / ProteinNode are CPU-resident anyway; reads CPU pose. Not a blocker for GPU-handled-Thing residency. |
| A2.c | meshMotors (`MYOHEADS_MESH` fill) | meshMotors | **{must port}** | Reads `motor.bindTip.x/y`. Marked dead-ish in the survey ("legacy 2D" superseded by 3D), but still runs unconditionally — confirm whether anything still reads `MYOHEADS_MESH` before classifying as `delete`. |
| A2.d | meshColl (`filSegMeshCollisions` etc.) | meshColl | **{must port}** OR **{can reduce frequency}** | Broad-phase pairwise: reads `iSeg.uVecAsPt3D()`, `jSeg.uVecAsPt3D()`, `end1Pt`, `end2Pt` of both filaments and calls `checkToLink` / `lineSegmentIntersectTest`. New-FilLink creation. Frequency can be relaxed if FilLink turnover physics tolerates it; observable to validate would be FilLink count + crosslinker dynamics. |
| A3.a | `MotorBindGrid3D.FillThreads` (grid build) | motorBindGrid3D | **{must port}** | Reads every filament's `end1/end2` and every motor's `bindTip`. The grid itself is consumed on GPU (A3.c); the build is still on CPU. Device-side grid build is the genuine residency blocker for binding detection. |
| A3.b | `Mesh.CkMotsThreads` (`motorFilCollisions`) | motColl (CPU path only) | {already on GPU} | Skipped on `-gpu`. |
| A3.c | `GPUMotorBinding.detectBindings` pack | motCollStart (GPU path) | **{must port}** | The kernel runs on device, but the per-step pack reads `MyoMotor.soa*` and `FilSegment.soa*` and the CPU-built grid CSR. Once A1, A2.a, A3.a are device-resident, this pack disappears too. |
| A4 | `Thing.calcRandomForces` | bForces | {already on GPU} for GPU-handled Things (Wang-hash inline in move kernel); CPU-fallback Things run CPU (their pose is CPU-resident already, OK). |
| A5.a | `FilLink.enforceFilLink` | xLink | **{must port}** | Pointer-traversed pair force; every active link reads `fil1.uVecAsPt3D`, `fil2.uVecAsPt3D`, `fil1.end1AsPt3D`, `fil2.end1AsPt3D`. Big per-step pose reader. |
| A5.b | `Arp23.enforceFilLink` | xLink | **{must port}** | Pointer-traversed mother↔daughter; reads both filaments' pose. |
| A5.c | `ActA.step` (`applyTetherForce`) | actA | **{must port}** for Listeria runs; **{not present}** in gliding/box configs. Gating: `actACt > 0` (i.e. Listeria mode only). For gliding-assay residency this is a no-op; for Listeria residency it's a pose reader. |
| A6 | `NodeLink.enforceNodeLink` (+ membrane relaxation loop) | membraneLinks (×N) | {output-frame only — pose is CPU-resident} | StickyNode pose lives on CPU (StickyNode is in `cpuFallback[]`). NodeLink reads CPU pose, integrates on CPU, never touches GPU-resident state. Not a blocker. |
| A7.a | `Myosin.jointConstraints` (CPU path) | myoJoints1 | {already on GPU} | Short-circuited on `-gpu`; kernel handles all four joint forces. |
| A7.b | `MyosinFixed.applyRodFixedPtForce` (anchor) | myoJoints1 (GPU path, reduced pass) | **{must port}** | Currently the LAST anchor-spring CPU pose reader on the GPU path. Reads `myoRod.end1AsPt3D()`. The "anchor lesson" residue. Single force; easiest residency port. |
| A7.c | `MyosinDimer.jointConstraints` | myoJoints1 | **{must port}** | Cross-Myosin coupling; always CPU. Reads both Myosins' rod pose. Off in gliding-assay-MyosinFixed-only runs (`myoDimerCt == 0`). For density-sweep gliding it's effectively zero, but for any minifilament/dimer config it's a per-step pose reader. |
| A8.a | `Crucible.keepMyosinOnSurface` | myoJoints2 | **{must port}** for chamber-fixed myosin configs; **{not present}** in gliding (gliding uses MyosinFixed not Crucible-tethered). Reads `curRod.end1AsPt3D()`. |
| A8.b | `Crucible.keepMyosinDimerOnSurface` | myoJoints2 | **{must port}** for dimer-on-surface configs. Same shape as A8.a. |
| A9.F1 | `FilSegment.checkBugOrBoxCollision` | step | **{must port}** | Every segment every step. Per-entity vs static boundary; easy device port (uniform boundary geometry). |
| A9.F2 | `checkBugCollisionFromOutside` | step | **{must port}** for Listeria; not present in gliding. Pairwise segment↔bug. |
| A9.F3 | `addLinkForces` | step | **{must port}** | Chain link force. Big consumer; the deflection benchmark's primary observable. |
| A9.F4 | `addTorsionSpringForces` | step | **{must port}** | Chain torsion (bending rigidity). LP benchmark's observable. |
| A9.F5 | `addNodeForces` (translational) | step | **{must port}** for node-tethered runs (formin/plasmid); often not present in gliding. |
| A9.F6 | `addNodeForces` (alignment) | step | **{must port}** for node-tethered runs, gated `nodeTorqSpring.isActive()`. |
| A9.F7 | `MyoMiniFilament.checkOuterBugCollision` | step | MyoMiniFilament is CPU-resident (in `cpuFallback[]`). Its pose is CPU-resident; reading it on the CPU is **{not a blocker}** for GPU residency — but the CPU step() call must still happen for these Things. |
| A9.F8 | `MyoFilLink.addForces` | step | **{must port}** | Motor tether spring. Gliding-assay primary observable. |
| A9.F9 | `MyoFilLink.alignUVecTorque` | step | **{must port}** | Motor uVec alignment. |
| A9.F10 | `MyoFilLink.alignYVecTorque` | step | **{must port}** | Motor yVec alignment. |
| A9.F12 | `ProteinNode.checkBugOrBoxCollision` | step | ProteinNode is CPU-resident. **{not a blocker}** for GPU residency. |
| A10 | gatherThreadAccumulators | gatherForces | {already on GPU} (no pose read; only forces). Stays CPU on CPU-fallback Things; not a blocker. |
| A11.a | benchmark force injection | step→move | **{must port}** for bench-mode residency. Single `incForceSum` call; trivial to push to device. |
| A11.b | `applyBenchmarkPins` | post-move | **{must port}** for bench-mode residency. Reads four endpoint coordinates after integration, writes back coord delta. Two affected Things; can be a single device-side fixup. |
| A12.a | `Thing.moveThing` (CPU-fallback Things) | move | CPU-fallback things stay CPU; their pose is CPU-resident. **{not a blocker}**. |
| A12.b | `GPUMoveThing.moveThings` OP_UNPACK + recomputeDerivedSoA + per-Thing post-sync (`end1Pt`, `bindTip`) | move | **THE BLOCKER ITSELF**. This is the per-step download to eliminate. Once everything above it is device-resident, OP_UNPACK becomes "only at output frames" and the CPU post-move sync block (`recomputeDerivedSoA(0,tc)` and the FilSegment `end1Pt`/`end2Pt` Pt3D refresh and the MyoMotor `bindTip` refresh) can be deleted — those Pt3D snapshots exist only because the per-step CPU consumers above need them. |
| A13 | `Thing.biochemStep` | biochem | Conditional pose **writer** on poly/depoly, not a reader. **{must port}** in the sense that the writes must end up on the device; on the residency model it's a topology-dirty event that re-uploads / re-packs the affected segment. Rare. |
| A14 | `Thing.resetCounters` | resetCt | {already on GPU} (no pose read). |
| A15 | `updateCounters` | safe-point | No pose read. |
| A16 | per-step diagnostic samplers | safe-point | Off in production; gate keeps them out of residency budget. |
| A17 | `GlidingAssayEvaluator.sampleStep` | logAndDraw | No pose read. |
| A18 | `ThreeJSWriter.writeFrame` | output cadence | {output-frame only} — exactly the residency model. |

### {must port} blocking set — explicit

Sorted by location in the loop; this is the residency campaign scope.

For a **gliding-assay configuration** (the current validation rotation):

1. `MyoMotor.fillSoaArrays` + `FilSegment.fillSoaArrays` (A1.a, A1.b)
2. `Mesh.meshFils` fill + meshColl crosslink broad-phase (A2.a, A2.d) —
   if crosslinkers are off in gliding, A2.d collapses to a no-op; A2.a
   may still be feeding `MYOHEADS_MESH` consumers (verify).
3. `MotorBindGrid3D` fill + `GPUMotorBinding.detectBindings` pack
   (A3.a, A3.c) — the binding-detection residency target.
4. `FilLink.enforceFilLink` (A5.a) — only if FilLinks are present;
   gliding assay typically has none.
5. `MyosinFixed.applyRodFixedPtForce` anchor spring on the GPU reduced
   pass (A7.b) — present in every gliding-assay run.
6. `FilSegment.step` F1/F3/F4 (A9.F1/F3/F4) — present in every run.
7. `MyoFilLink.addForces` / `alignUVecTorque` / `alignYVecTorque`
   (A9.F8/F9/F10) — present in every gliding-assay run that has motors.
8. Benchmark-mode-only: A11.a force injection, A11.b pin restoration.

For a **Listeria configuration**, add: A5.b (Arp23), A5.c (ActA),
A9.F2 (bug-outside collision).

For a **membrane-network configuration**: A6 stays CPU; not a blocker
(StickyNode pose is CPU-resident).

For a **chamber-fixed-myosin / dimer configuration**: add A7.c, A8.a,
A8.b.

For a **node-tethered (formin/plasmid) configuration**: add A9.F5, A9.F6.

### Things that look like blockers but are not

- `Mesh.NODE_MESH` (A2.b), `NodeLink.enforceNodeLink` (A6),
  `MyoMiniFilament.checkOuterBugCollision` (A9.F7),
  `ProteinNode.checkBugOrBoxCollision` (A9.F12), CPU-fallback
  `moveThing` (A12.a): all read CPU-resident poses of CPU-only Things.
  Their CPU pose stays on CPU regardless of residency strategy.
- Brownian generation (A4): zero pose reads; only `bTrans/RotGam/Diff`.
- Gather (A10), resetCounters (A14), updateCounters (A15),
  `GlidingAssayEvaluator.sampleStep` (A17): no pose reads.

---

## Part 3 — Binding-detection architecture

Current state (after iter1–iter2a, JOURNAL): binding detection is on the
GPU kernel side, but its **inputs are still sourced from CPU pose each
step**.

### Per-step flow on the `-gpu` path

1. Pre-step (CPU): `MyoMotor.fillSoaArrays`, `FilSegment.fillSoaArrays`
   read `Thing.soaCoord` / `soaUVec` and derive motor end2 and segment
   end1/end2 into `MyoMotor.soaX/Y/Z/UX/...` and
   `FilSegment.soaEnd1X/Y/Z/soaEnd2X/Y/Z`.
2. `motorBindGrid3DStart` phase (CPU): `MotorBindGrid3D.FillThreads`
   reads `fs.end1AsPt3D()` / `fs.end2AsPt3D()` for every segment and
   `m.bindTip` for every motor; bins them into per-cell `int[][][]`
   arrays.
3. `GPUMotorBinding.detectBindings()` (CPU + GPU):
   - **Pack motor + segment SoA** (CPU): walks
     `MyoMotor.soaX..soaRodUZ/OnFil` and
     `FilSegment.soaEnd1..NodeAtEnd2` into `FloatArray` /
     `IntArray` (every step).
   - **Pack grid CSR** (CPU): `packForGPU` walks every cell of the
     3D grid and writes a flat CSR (`gridCellOffsets` /
     `gridCellContents`) for the kernel to read.
   - **Execute** `bindKernel` on device, with `transferToDevice
     EVERY_EXECUTION` for all motor/seg/grid arrays — the device side
     gets a fresh copy each step.
   - **Unpack** (CPU): walks the device `boundSegId` array, fires
     `MyoMotor.ontoFilament` for each hit.

### Where it sits in the loop

After meshes (so MotorBindGrid3D grid is filled), before
`bForcesStart` (so binding decisions are made on this-step poses, before
Brownian forces apply). Force computation (`step` phase) consumes the
new bindings via `tipLink.mySeg` — the motor binding decision is the
same-step input to F8/F9/F10.

### Verdict for residency

**Binding detection reads CPU poses every step in two distinct places:**

- the FillThreads grid build (`fs.end1AsPt3D()` / `fs.end2AsPt3D()` /
  `m.bindTip`),
- and the GPUMotorBinding SoA pack (`MyoMotor.soa*`, `FilSegment.soa*`).

Both go away when poses are device-resident, IF the grid build is
moved to the device. Doing so cleanly is non-trivial — the
timestamp-based per-cell synchronization in `addFilToCell` /
`addMotorToCell` is a CPU pattern. A device-side grid build is the
natural shape, but it's a real port: one kernel per Thing-type-to-cell
write, with atomic counters per cell (or per-cell histogram +
prefix-sum to assign write slots). This is the genuine device-grid
work that step() does not need but binding detection does — confirming
the lesson 5 caveat from the survey: binding detection is "the one
phase that legitimately uses spatial proximity (not fixed topology),
so it's the place a device-side grid may genuinely be needed for
residency."

The same device-grid would also serve the FilLink broad-phase
(meshColl / `filSegMeshCollisions`) — they share a structural need.
One device grid, two consumers.

---

## Part 4 — F2 / F11 / F13 and the general-collision question

### F2

**Confirmed**: `FilSegment.checkBugCollisionFromOutside` →
`bugForcesFromOutside`. Pairwise force between a filament endpoint and
the static `lmBug` (Listeria bacterium). Reads segment `end1Pt` /
`end2Pt`, segment radius, and `lmBug.coord` + bug geometry. Inactive in
gliding-assay configs (`simOutsideBug` is false); active in Listeria
runs. Dispatched inside `FilSegment.step()` → `checkBugOrBoxCollision`
at `stepStart`.

### F11

**Confirmed and clarified**: `ActA.step()` → `applyTetherForce`. Reads
`ActA.actAPtInX`, `boundFil.coord` / endpoint near the tether anchor,
and `lmBug.coord`. Applies spring force to `boundFil` and (reverse) to
`lmBug`.

The STEP_PORT_SURVEY's Table A lists F11 in the "step()" inventory, but
**ActA is NOT a `Thing` subclass** — its `step()` method is called by
`ActA.ActAThreads` at `actAStart` (which is `Env.actAStart = 6`, the
same wave as `xLinkStart = 6`). So F11 runs in the xLink wave, BEFORE
`Thing.step()`. The survey's classification (stiff, pairwise) is
correct; the dispatch location is one wave earlier than its placement
in Table A suggests.

For residency: F11 is in scope only for Listeria configs. Its pose
reads are pointer-traversed (`boundFil` and `lmBug` are fixed
references), not broad-phase.

### F13

**Confirmed**: there is no F13 force in `step()`. `StickyNode`
inherits `ProteinNode.step()` with no override, so its step-phase
behaviour is exactly F12 (`checkBugOrBoxCollision`). The
*sticky-tether* force (what F13 might informally name) lives in
`NodeLink.enforceNodeLink` at `membraneLinksStart`, **not** in
`stepStart`. The survey marks this explicitly (Table A note on F13).

### The general-collision question

> Does `step()` — or any per-step phase — compute a force between
> NON-connected, spatially-proximate objects (general steric /
> excluded-volume collision)?

**`Thing.step()` itself: NO.** All step() forces F1–F12 are either:

- **Per-entity vs static geometry**: F1, F7, F12 (boundary
  collisions), and the boundary side of F2 / F11 (the bug is treated
  as static geometry from the segment's POV).
- **Pairwise via fixed topology (pointer)**: F3, F4 (chain
  neighbours via `end1Fil` / `end2Fil`), F5, F6 (node tether via
  `end1Node` / `end2Node`), F8, F9, F10 (motor tip via
  `MyoFilLink.mySeg`), F11 (ActA via `boundFil`).

There is no segment ↔ segment excluded-volume force, no motor ↔ motor
collision, no node ↔ node excluded-volume force inside `step()`.

**The per-step loop AS A WHOLE: YES, in three phases.** These are
broad-phase spatial neighbour queries that find non-pre-connected
proximate pairs and act on them:

1. **`meshColl` / `FilSegment.filSegMeshCollisions`** — bins
   filament segments via `Mesh.FILSEG_MESH`, scans bin contents,
   calls `checkToLink(iSeg, jSeg)` to potentially create a new
   `FilLink` between proximate non-co-filament segments. Reads
   pose every step. Cadence: default `collisionCheckInt = 1` →
   every step.
2. **`meshColl` / `FilSegment.membraneFilMeshCollisions`** — same
   shape but filament ↔ membrane-node proximity. Optional.
3. **`motorBindGrid3DStart` + binding detection
   (`Mesh.CkMotsThreads` CPU or `GPUMotorBinding`)** — the only
   per-step phase that *demands* spatial-proximity neighbour
   finding for its core physics (motor-fil binding cannot be
   pointer-traversed; a motor must discover nearby filaments).
   Reads pose every step. This is the genuine device-grid need.

So a device-side grid is NOT needed for the `step()` port itself, but
it IS needed for full residency because the loop contains three
broad-phase queries that today read CPU pose. The binding-detection
grid (3D, 0.2 µm cubes) and the FilLink-creation grid (2D `FILSEG_MESH`,
0.2 µm XY) overlap in shape and could be served by a single device
grid — assuming the meshColl FilLink-creation phase needs to keep its
current cadence. If FilLink-creation can be decimated to every-N steps
(observable: total FilLink count vs time, crosslinker turnover rate),
the device-grid is needed for binding detection only.

---

## Part 5 — Dependencies and residency architecture sketch

### Ordering dependencies among `{must port}` consumers

Within one step (using gliding-assay slice as base — generalize to other
configs by adding the optional members):

```
pre-step :  fillSoaArrays   ─── reads pose ──► (replaced by: pose already on device)
            (A1.a, A1.b)
                │
meshFils    :  build FILSEG_MESH               (A2.a)         ──┐
                │                                                │ both feed
meshColl    :  filSegMeshCollisions → checkToLink (A2.d)        │ pose-based
                │                                                │ broad-phase
motorBindGrid3D : 3D grid build              (A3.a)         ────┤
motCollStart    : kernel pack + execute      (A3.c)             │
                                                                │ output: motor↔seg bindings
xLinkStart  :  FilLink enforce  (A5.a)        ──┐  pointer-traversed pose readers;
              :  Arp23 enforce  (A5.b)         ──┤  no new neighbour-finding
              :  ActA step      (A5.c)         ──┘
                │
myoJoints1  :  CPU anchor pass (A7.b)         ── reads rod pose
              :  MyoDimer joint  (A7.c)        ── reads rod poses of both heads
                │
myoJoints2  :  Crucible.keepMyosinOnSurface (A8.a) ── reads rod pose
                │
step        :  FilSegment.step  (F1, F3, F4, F5, F6)
              :  MyoMotor.step → tipLink.step (F8, F9, F10)
              :  ProteinNode.step (F12, CPU-fallback)
              :  MyoMiniFilament.step (F7, CPU-fallback)
                │
gatherForces :  per-thread → soaForceSum    (no pose read)
                │
bench       :  benchmark force injection / pin (A11.a, A11.b)
                │
move        :  GPUMoveThing.moveThings → kernel writes new pose; today
                OP_UNPACK downloads pose for every CPU consumer above.
                                                ↓
                  (residency goal: NO OP_UNPACK except at output frames)
```

Key dependencies:

- **Anything reading derived fields (`end1`/`end2`/`zVec`/`transXTox`)
  requires `recomputeDerivedSoA` to have run after the previous step's
  `move`.** Today this happens inside `GPUMoveThing.moveThings`
  immediately after unpack, so CPU consumers always see fresh derived
  fields. In a residency world, derived-field recompute must happen on
  the device (a kernel; the data lives there). The post-move CPU
  refresh loop for `xRange/yRange/zRange`, `end1Pt`, `end2Pt`,
  `bindTip` Pt3D snapshots (`GPUMoveThing.moveThings:1462–1480`)
  disappears with the consumers that read them.
- **Mesh and binding broad-phase both consume freshly-integrated
  poses.** They sit between move-N and step-N+1 conceptually; today
  they're at the top of step N+1 (the model is: integrate to get pose
  at end of N, then start N+1 by binning that pose).
- **`Thing.biochemStep` is the only per-step pose writer that's not
  the integrator.** Its writes are conditional on poly/depoly events.
  A residency design needs a "device write on poly/depoly" hook (or a
  topology-dirty flag → re-upload affected slot range), and this is
  rare enough that occasional pose pushes on biochem events are fine.

### Residency architecture sketch (what stays where)

#### Device-resident across steps (FIRST_EXECUTION upload + in-place updates)

- `soaCoord`, `soaUVec`, `soaYVec` for GPU-handled Things.
- `soaEnd1`, `soaEnd2`, `soaZVec`, `soaTransXTox` (derived; recomputed
  on device by a kernel after each integration step).
- `soaForceSum`, `soaTorqueSum` (already device-side inside the chained
  TaskGraph; the per-Thing CPU accumulator pattern would need to be
  rethought when force-producing phases move to the device).
- One `MyoFilLink` index per motor: `boundSegId`, `posOnSeg`, cocked
  flags. Already used by the binding kernel; persist across steps and
  update in place when binding/release events fire.
- One `FilLink` table: `(fil1Slot, fil2Slot, loc1, loc2, active)` rows.
  Updated by the FilLink-creation kernel (broad-phase) and by
  `enforceFilLink` (force application).
- Chain-topology index per FilSegment: `end1Fil`, `end2Fil`,
  `ptAtEnd1Side` flag, `end1Node`, `end2Node`. Stable except on
  topology-changing biochem events; topology-dirty bit triggers a
  re-upload of the affected slots.
- Per-Thing drag tensors (`bTransGam`, `bRotGam`, `bTransDiff`,
  `bRotDiff`). Already needed by the move kernel.
- Per-Thing `myoJoint` slot map (`rodSlots`, `leverSlots`, `motorSlots`,
  `cockedFlag`).
- One device-side spatial grid (3D cubic, 0.2 µm cell, BIN_DEPTH=1000)
  serving binding detection and FilLink-creation broad-phase. Built on
  device every step (or every N steps if cadence relaxed).

#### Downloads at output frames only

- `soaCoord`, `soaUVec`, `soaYVec` for GPU-handled Things (read by
  `ThreeJSWriter.writeFrame`).
- `MyoMotor.onFil` / `nucleotideState` for the writer.
- `FilSegment.length` / `cofilinCt` etc. for the writer (these are
  biochem-state fields, mostly CPU already; touched only when poly/depoly
  events fire).
- Optional periodic full-state download for log files (rare).

#### Stays on CPU forever

- CPU-fallback Things' pose (`Bug`, `ProteinNode`, `StickyNode`,
  `FillNode`, `MyoMiniFilament`, branch FilSegments, ActA-bound
  FilSegments). They have their own CPU-only step / move / mesh
  pipeline; never moved to GPU. Their pose CPU-resident is fine.
- `NodeLink.enforceNodeLink` and the membrane relaxation loop —
  all CPU, all operating on CPU-only StickyNode pose. No interaction
  with the device-resident set.
- Diagnostic samplers (`JointDiag`, `JointParamDiag`,
  `SingleMyoDiag`) — when enabled they force a device-to-host pose
  read for the diagnostic Thing. Acceptable: diagnostic mode is not
  the production residency budget.
- ThreeJSWriter, FileOps, LiveFrameServer — all output-frame.

#### What the per-step loop becomes (once blocking set empty)

```
synchronized(safeO) {
   // No fillSoaArrays — device pose IS the canonical pose.
   // No CPU-side classify (gpuHandled set stable until topology event).

   if (collisionCkCounter >= collisionCheckInt) {
      device.buildSpatialGrid();            // 3D grid, on device
      device.findCrosslinkPairs();          // FilLink-creation (if active)
      collisionCkCounter = 0;
   }
   device.motorBindKernel();                // reads device grid + pose
   // (Brownian inline in move kernel for GPU-handled Things; CPU-fallback
   //  runs CPU calcRandomForces as today.)
   device.filLinkForceKernel();             // F-on-existing FilLinks
   device.arp23ForceKernel();               // Arp23 forces (if active)
   device.actAForceKernel();                // ActA tether (Listeria only)
   device.nodeLinkForceKernel();   // NO — NodeLink stays CPU (StickyNode)
   cpu.nodeLinkEnforce();                   // CPU-resident StickyNode pose

   device.myoJointsKernel();                // already device today
   device.anchorSpringKernel();             // ports MyosinFixed.applyRodFixedPtForce
   cpu.myoDimerJointConstraints();          // OR device-port; if present

   device.crucibleSurfaceTetherKernel();    // ports keepMyosinOnSurface (if present)

   device.stepForcesKernel();               // F1, F3, F4, F5, F6, F8, F9, F10, F12
                                            //   for GPU-handled Things
   cpu.stepForcesForFallback();             // F1/F7/F12 etc. for CPU-fallback Things,
                                            //   reading their CPU-resident pose

   if (benchmarkFilament) {
      device.benchmarkInjectForce();
   }
   device.integrateKernel();                // move; updates device pose in place
   device.recomputeDerivedKernel();         // updates device-side end1/end2/zVec/transXTox
   if (benchmarkFilament) {
      device.applyBenchmarkPinsKernel();
   }
   cpu.moveThingForCpuFallback();           // Bug, ProteinNode, MyoMiniFilament...

   cpu.biochemStep();                       // per-Thing biochem rolls; rare pose writes
                                            //   trigger device-write-back for affected slots
   cpu.resetCounters();                     // no pose

   // membrane relaxation loop — entirely CPU, StickyNode only

   updateCounters();
   // safe-point: pause/inspect/param drain.
   // Inspect path: lazy single-Thing pose download.

   if (threeJSCounter >= toFileInterval) {
      device.downloadPosesToHost();         // bulk device → host transfer
      ThreeJSWriter.writeFrame();           // reads host SoA as today
      threeJSCounter = 0;
   }
}
```

The per-step CPU pose transfer is now zero (gliding-assay slice). The
`OP_UNPACK` is replaced by a device-side derived-field recompute; pose
data never leaves the device except at output frames or for diagnostics.

### Ordering notes for the campaign

- **A7.b (anchor spring)** is the lowest-risk first port: smallest
  diff, single force, the lesson-postmortem already singled it out,
  and it gets the GPU path to "zero CPU pose reads inside myoJoints1
  for gliding".
- **A1.a + A1.b (fillSoaArrays)** are trivial to retire once the
  downstream consumers (A3.a, A3.c) move to device — they exist only
  to feed those.
- **A3.a + A3.c (binding detection)** is the genuine
  device-grid-build work. Big effort, but it's the residency
  enabler for the most important per-step work.
- **A9 step-force kernel** is the `STEP_PORT_SURVEY` plan; F3/F4
  first (validated by deflection + LP benchmarks), then F1/F8/F9/F10.
  Each force ported should be paired with its applicable probe per
  Lesson 5.
- **A5.a (FilLink), A7.c (MyosinDimer), A8 (Crucible surface tethers)**
  are config-specific; defer to when a config that needs them is on the
  validation rotation. They are pointer-traversed pair forces — same
  shape as F8/F9/F10, so the kernel pattern transfers.

---

## Notes on confidence

- Phase walk in Part 1 is exhaustive across the dispatches in
  `BoxOfActin.doLoop()` and the per-subclass `step()` / `moveThing()` /
  `biochemStep()` / `resetCounters()` overrides found in
  `boxOfActin/*.java`.
- "Pose consumer" classification is by direct code inspection of the
  cited line ranges; I have not run the code to confirm.
- Specific config questions (does meshMotors still feed any consumer
  after `MotorBindGrid3D` superseded it for binding? is FilLink
  active in current gliding assay parameter files? are
  `nodeTorqSpring` / `xLinks` active in current configs?) need a quick
  param-file scan and a grep to confirm before scoping the blocking
  set per config. These are flagged inline in the table.
- The "anchor spring" residue (A7.b) is the one place where Lesson 1
  has a current live data point: it's a known per-step CPU pose
  consumer on the GPU path, in production, today.

## What this audit deliberately does not commit to

- Port order or sequencing — the planner will sequence based on this
  blocking list and the validation-probe coverage (Lesson 5).
- Whether to build one unified device-side spatial grid or two
  (binding + FilLink-broad-phase) — open design question.
- Whether to relax `collisionCheckInt` cadence — depends on whether
  FilLink turnover physics tolerates per-N-step broad-phase, and the
  planner should request the relevant observable comparison first.
- How `Thing.biochemStep`'s rare pose writes are propagated to the
  device — open design question (event-driven upload vs topology-dirty
  bit vs deferred reconciliation at next mesh rebuild).
