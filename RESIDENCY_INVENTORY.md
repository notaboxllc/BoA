# Residency inventory — what still needs porting (2026-06-04)

Read-only survey. No code edits, no compile, no runs. Scope: the gliding-assay
slice (`-gpu -pf glidingAssay500_val`-class runs), after the Phase-2 force
ports (anchor + F3/F4 + F1 box + tipC + F8/F9/F10 motor cross-bridge).
Companion to `RESIDENCY_AUDIT.md` (2026-06-02) — refreshes the audit's
classification table against the four ports landed since.

This is the authoritative "what still needs porting before the residency flip"
list as of HEAD = `a0fe20f`.

> Concurrent caveat: a sibling Claude Code instance owns `JOURNAL.md` /
> `.last_run_status` / `RUN_LOGS/` writes for the lag-confirmation ensemble.
> This document is the only write from this instance. Fold-in to JOURNAL.md
> belongs to a later session.

---

## 1. Current per-step data flow (the transfer inventory the flip must eliminate)

`BoxOfActin.doLoop()` is the per-step orchestrator. Inside
`synchronized(Env.safeO)` it walks the canonical wave sequence
(`meshFils → meshNodes → meshMotors → meshColl → motorBindGrid3D →
motCollStart | GPUMotorBinding | bForces → xLink → membraneLinks → myoJoints1
→ myoJoints2 → step → gatherForces → (bench-inject) → move → biochem →
resetCt → membraneRelax`). On `-gpu`, the `move` wave is `GPUMoveThing.moveThings()`
and the per-step CPU↔device traffic is concentrated in the chained TaskGraph
inside it.

### 1.a Upload to device, every step (chained TaskGraph `transferToDevice` list)

`GPUMoveThing.allocateAndBuildPlan` (the lazy build) registers every buffer
below as `DataTransferMode.EVERY_EXECUTION`, so each `plan.execute()` re-uploads
all of it:

| Buffer | Shape | Producer (CPU each step) | Read by kernels |
|---|---|---|---|
| `coord`, `uVec`, `yVec` | `slotCap*3` floats each | `dispatchAndWait(OP_PACK_FULL/OP_PACK_RESIDENT, slotCount)` — packs `Thing.soaCoord/UVec/YVec` for GPU-handled slots | joints, chain, boundary, motorForce, segMotorForce, move |
| `cpuForceSum`, `cpuTorqueSum` | `slotCap*3` floats | packed from `Thing.soaForceSum/TorqueSum` (CPU-side step forces + Brownian for non-GPU Things) | move |
| `jointForceSum`, `jointTorqueSum` | `slotCap*3` floats | host-zeroed each step; device-side delta-accumulator | joints/chain/boundary/motorForce/segMotorForce write; move reads |
| `bTransGam`, `bRotGam` | `slotCap*3` floats | packed each step from per-Thing drag tensors | move, chain (F4 denom), motorForce, boundary |
| `brownianScales`, `velMask` | per-slot | classifyThings + per-Thing flags | move kernel (Brownian + integration) |
| `rodSlots`, `leverSlots`, `motorSlots` | `myoCap` ints | classifyThings packs joint slot maps (only on topology-dirty) | joints, motorForce, segMotorForce |
| `myoDrags`, `cockedFlags`, `anchorPts`, `anchoredFlags` | per-Myosin | classifyThings + per-step joint pack | joints (anchor folded in via `anchoredFlags`/`anchorPts`) |
| `topoEnd2Slot/Side`, `topoEnd1Slot/Side`, `soaLengthArr` | `slotCap` per | classifyThings (topology-dirty only) | chain |
| `boundaryActive`, `boundaryParams`, `boundaryTipC` | per-slot int + 6 scalars + `slotCap*2` floats | classifyThings + per-step param refresh; `boundaryTipC` host-init'd to `1e6` sentinels | boundary (when `BOUNDARY_SHAPE_BOX`) |
| `boundSegSlot`, `posOnSegArr`, `segMotorOffsets`, `segMotorMyo` | per-Myo + CSR (`slotCap+1`, `myoCap`) | `packMotorBinding()` each step (CSR build of motor↔seg binding) | motorForce, segMotorForce |
| `motorWriteback`, `motorForceParams` | `myoCap*2`, 6 | host-zeroed each step; receives `(forceMag, forceDotFil)` | motorForce writes; readback drain |
| `jointParams`, `chainParams`, `params`, `counts` | scalars | refreshed each step from `Env.*.getValue()` | all kernels |

### 1.b Kernels (chained TaskGraph, in execution order)

Per `allocateAndBuildPlan` (`GPUMoveThing.java:2391–2495`):

1. **`joints`** — one thread per Myosin joint slot; rod-lever, lever-motor force/torque
   pairs, **plus the anchor spring** folded in via `anchoredFlags[mj]`/`anchorPts[mj*3..]`
   (Phase 1 port). Writes `jointForceSum/jointTorqueSum` at rod/lever/motor slots.
2. **`chain`** — one thread per FilSegment move slot; F3 chain link + F4 chain torsion
   forces/torques using `topoEnd1Slot/topoEnd2Slot` pointer traversal (Phase 2 F3/F4
   port). Writes `jointForceSum/jointTorqueSum` at the seg slot.
3. **`boundary`** (`BOUNDARY_SHAPE_BOX` only) — one thread per FilSegment slot; F1 box
   wall force/torque + per-endpoint `boundaryTipC` writeback (Phase 2 F1 + tipC ports).
   RMW into `jointForceSum/jointTorqueSum`. Pill is **not** wired; `BOUNDARY_SHAPE_PILL`
   slot reserved but no kernel.
4. **`motorForce`** — one thread per Myosin joint slot; F8 cross-bridge spring + F9
   uVec align + F10 yVec align (motor side). RMW into motor slot's
   `jointForceSum/jointTorqueSum`. Writes per-motor `forceMag` and signed `forceDotFil`
   into `motorWriteback` (Phase 2 motor port).
5. **`segMotorForce`** — one thread per FilSegment slot; F8/F9/F10 seg side via CSR walk
   over bound motors. RMW into seg slot's `jointForceSum/jointTorqueSum`.
6. **`move`** — one thread per move slot; **inline Wang-hash Brownian** (six hashes per
   slot → three Box–Muller Gaussian pairs, scaled by `brownianForceMag = sqrt(2kT/dt)`
   and `sqrt(bTransGam_i / bRotGam_i)` and per-slot `brownianScales`); body-frame force
   integration → coord/uVec/yVec update; pose written back to `coord/uVec/yVec` device
   buffers in place. SoA variant kept behind `BOA_SOA_POSE` (off; Phase 0 decided AoS).

### 1.c Download to host, every step (chained TaskGraph `transferToHost` list)

| Buffer | Drained by | Consumer |
|---|---|---|
| `coord`, `uVec`, `yVec` | `dispatchAndWait(OP_UNPACK, slotCount)` (`GPUMoveThing.java:3610`) | **everything CPU-side that reads pose next step** — this is the residency-blocker download |
| `boundaryTipC` | `bridgeBoundaryTipC()` (`GPUMoveThing.java:3630`) | min-combined into `FilSegment.end{1,2}TipC` for `stericHindranceEnd2` polymerization gate |
| `motorWriteback` | `bridgeMotorForceWriteback()` (`GPUMoveThing.java:3642`) | `MyoFilLink.forceMag` + `forceDotFil`; feeds `forceDotFilTrack.registerValue()` for next-step `ckRelease` (this is the source of the documented 1-step release lag) |
| `jointForceSum`, `jointTorqueSum` | diag dumps only (`DIAG_DUMP_JOINTS_STEP`/`DIAG_DUMP_CHAIN_STEP`) | inspection; not on the production hot path |

### 1.d Per-step CPU work that still runs (gliding `-gpu` default)

In doLoop order, with what reads pose:

- **A1. `MyoMotor.fillSoaArrays()` + `FilSegment.fillSoaArrays()`** (BoxOfActin.java:860-861)
  — read `Thing.soaCoord`/`soaUVec`, derive `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodU*` and
  `FilSegment.soaEnd1X..End2Z`. **Pose readers, every step.**
- **A2a-c. Mesh fills** (`Mesh.FILSEG_MESH.fillFilSegMesh`, `NODE_MESH.fillNodeMesh`,
  `MYOHEADS_MESH.fillMotorMesh`) — read `curSeg.end1AsPt3D()/end2AsPt3D()`, node pose,
  `motor.bindTip`. **Pose readers, every step (`collisionCheckInt == 1` default).** In
  gliding, `meshColl → filSegMeshCollisions` walks the FILSEG_MESH bins but the inner
  `checkToLink` call is gated `Env.xLinks.isActive()` (off in gliding) — so the *bin
  fill* happens unconditionally but the *pair work* is a no-op for the gliding rotation.
  `membraneFilMeshCollisions` similarly inert (no nodes). The mesh-fill cost remains.
- **A3a. `MotorBindGrid3D.FillThreads`** (motorBindGrid3DStart) — reads
  `fs.end1AsPt3D()`/`fs.end2AsPt3D()` for every fil and `m.bindTip` for every motor
  to build the per-cell 3D bin arrays. **Pose reader, every step.**
- **A3c. `GPUMotorBinding.detectBindings()`** (motCollStart, on `-gpu`):
  - CPU **pack** of motor + seg SoA into FloatArrays/IntArrays — reads
    `MyoMotor.soa*` / `FilSegment.soa*` produced by A1.
  - CPU **CSR pack** of `MotorBindGrid3D` — reads the per-cell int arrays.
  - device `bindKernel` — runs on device but with CPU-sourced inputs.
  - CPU **unpack** of `boundSegId` → `MyoMotor.ontoFilament` per hit (which
    runs CPU code that reads `soaEnd1X/Y/Z`, `soaEnd2X/Y/Z`, `MyoMotor.soaX/Y/Z`
    to compute `arcOnFil`, the binding arclength).
- **A4. `Thing.calcRandomForces`** — runs CPU only for `!gpuHandled` Things on `-gpu`
  (Thing.java:285-298). **Does not read pose** (only `bTransDiff/bRotDiff` /
  `bTransGam/bRotGam` drag tensors). For `gpuHandled` Things the kernel does it inline.
- **A5. xLinkStart wave** — `FilLink.XLinkThreads` / `Arp23.Arp23Threads` / `ActA.ActAThreads`.
  All three iterate "active" pair indices. In gliding (`xLinks` off, `Arp23` off,
  Listeria off), all three execute() bodies degenerate to "no active links" no-ops.
  Non-blocking for the gliding residency slice.
- **A6. `NodeLink.enforceNodeLink` + membrane relaxation pass** — CPU only, reads
  CPU-resident StickyNode pose. Not in the GPU-handled set; not a blocker.
- **A7a. `Myosin.MyosinThreads` (myoJoints1)** — on `-gpu` with `DIAG_CPU_JOINTS=false`
  (default) and `DIAG_CPU_ANCHOR=false` (default), `execute()` runs **NO** per-Myosin
  CPU work (Myosin.java:121-125: only `applyGPUDroppedForces()` if `DIAG_CPU_ANCHOR=true`).
  The anchor-spring residue (audit A7.b) is **closed** — confirmed by reading
  `Myosin.java:108-130` plus `MyosinFixed.applyGPUDroppedForces()` which itself only
  calls `applyRodFixedPtForce()`. The jointsKernel uses `anchoredFlags[mj]`/`anchorPts[mj*3..]`
  uploads to fold the anchor into the same kernel run. **No CPU pose read in this wave
  on the gliding path.**
- **A7c. `MyosinDimer.jointConstraints`** — CPU only. `myoDimerCt == 0` in gliding
  (no dimers). No-op.
- **A8. `Crucible.ChamberMyoThreads`/`ChamberMyoDThreads`** — chamber-fixed myosin
  surface tethers. Gliding uses `MyosinFixed` (anchor-spring path), not Crucible
  tethering. No-op for gliding.
- **A9. `Thing.ThingStepThreads` at `stepStart`** — calls `step()` on **every** Thing.
  Per-subclass behaviour on `-gpu`:
  - `FilSegment.step()` (FilSegment.java:474-512):
    - `checkBugOrBoxCollision()` → on `-gpu` with `DIAG_CPU_F1=false` (default), the CPU
      pair short-circuits (gate inside `FilSegment.gpuBoundaryHandled`); the box kernel
      ran on the device. **F1 is on device, no CPU pose read here on the gliding path.**
    - `addLinkForces()` (F3): SKIPPED when `gpuChainHandled == true` (default for chain
      neighbours). **On device.**
    - `addTorsionSpringForces()` (F4): SKIPPED on `gpuChainHandled`. **On device.**
    - `addNodeForces()` (F5/F6): **always CPU.** Gliding has no nodes, so the inner loop
      degenerates — but the method is still called per FilSegment.
  - `MyoMotor.step()` (MyoMotor.java:170-183) → `updateMyoFilLinks()` → for each bound
    motor, `tipLink.step()`:
    - `MyoFilLink.updatePos()` (MyoFilLink.java:215-221) — **CPU pose read**:
      `attachPt.add(mySeg.end1AsPt3D(), posOnSeg, mySeg.uVecAsPt3D())`. Runs even on the
      GPU path because `updatePos()` is called before the `gpuMotorHandled()` gate
      (MyoFilLink.java:78). On the device path the `attachPt` it computes is **unused**
      (only `addForces`/`align*Torque` read it, and those are skipped) — but the read
      itself still happens. **Small per-motor pose read, every step, even on the
      fully-device path.** Not enumerated as a standalone row in the 2026-06-02 audit;
      flagging here.
    - F8/F9/F10 (`addForces`/`alignUVecTorque`/`alignYVecTorque`): SKIPPED when
      `gpuMotorHandled()` (= MyosinFixed + GPU-handled seg + `!DIAG_CPU_MOTOR`). **On
      device** for gliding default.
    - `ckRelease()` — **always CPU**, reads `forceMag` (drained from `motorWriteback`
      of the PREVIOUS step's `plan.execute()` — the documented 1-step lag). No pose read.
  - `MyoRod.step()`/`MyoLever.step()` — empty bodies.
  - `Bug.step()`/`ProteinNode.step()` etc. (CPU-fallback Things) — read CPU-resident
    pose; not residency-blocking.
- **A10. `Thing.gatherThreadAccumulators()`** — narrows per-thread double accumulators
  to the SoA float buffers. **No pose read.**
- **A11. Benchmark-mode force injection / `applyBenchmarkPins()`** — pose readers in
  bench mode only; not in the gliding rotation.
- **A12. `GPUMoveThing.moveThings()` — the **device-then-CPU-sync** sequence:
  1. `OP_PACK_*` (CPU → device coord/uVec/yVec/cpuForceSum/cpuTorqueSum/drags/...).
  2. `OP_PACK_JOINTS` (joint slot data).
  3. `packMotorBinding()` (CPU CSR build for motor F8/F9/F10).
  4. `plan.execute()` — joints → chain → boundary → motorForce → segMotorForce → move.
  5. `OP_UNPACK` (**device → host coord/uVec/yVec** — the per-step download to eliminate).
  6. `cpuFallback[i].moveThing()` (Bug, MyoMiniFilament, ProteinNode, etc. — CPU integration).
  7. `bridgeBoundaryTipC()` — min-combine `boundaryTipC` into `FilSegment.end{1,2}TipC`.
  8. `bridgeMotorForceWriteback()` — drain `motorWriteback` into `MyoFilLink.forceMag`/
     `forceDotFil`, feed `forceDotFilTrack`.
  9. `Thing.recomputeDerivedSoA(0, thingCt)` — **CPU recompute of `soaEnd1`/`soaEnd2`/
     `soaZVec`/`soaTransXTox`** from the freshly-unpacked coord/uVec/yVec.
  10. Per-FilSegment refresh of `xRange/yRange/zRange` + `end1Pt`/`end2Pt` Pt3D snapshots
     (GPUMoveThing.java:3653-3661).
  11. Per-MyoMotor refresh of `bindTip` Pt3D snapshot (GPUMoveThing.java:3663-3668).
- **A13. `Thing.biochemStep()`** — CPU. For FilSegments, **does not read pose** in the
  steady case; on poly/depoly (`lengthChanged==true`), mutates `coord` via
  `Monomer.polymerize`, then calls `pushCoordToSoa()`, `calculateProperties()`,
  `initialize()`. **Conditional pose writer**, NOT a steady-state pose reader. Same for
  `MyoMotor.biochemStep` (nucleotide state only, no pose).
- **A14. `Thing.resetCounters()`** — no pose. SoA force/torque buffers are zeroed in
  bulk via `Thing.clearSoaForcesTorques` at the start of each step, not here.
- **A15. `updateCounters()`** — no pose.
- **A16-20.** Diagnostic samplers (off in production), gliding evaluator (no pose), and
  the safe-point/output paths — output-cadence or off, not blockers.

### 1.e Summary of per-step CPU↔device traffic, today

- **Up**: pose (`coord/uVec/yVec`), force accumulators (`cpuForceSum/TorqueSum`),
  joint deltas (zeroed), drags, brownianScales/velMask, joint slot maps, chain topo,
  boundary uniforms, motor binding (per-Myo + CSR), all scalar params. Plus the
  full GPUMotorBinding pack: motor SoA (`motPos`/`motUVec`/`motRodUVec`/`motOnFil`),
  seg SoA (`filEnd1`/`filEnd2`/`filNodeAtEnd2`), grid CSR (`gridCellOffsets`/
  `gridCellContents`), counts.
- **Down**: pose (`OP_UNPACK`), `boundaryTipC`, `motorWriteback`. Plus from the
  binding plan: `boundSegId` per motor.
- **CPU-only per step**: pre-step `fillSoaArrays` (A1), all mesh fills (A2),
  `MotorBindGrid3D.FillThreads` (A3a), `GPUMotorBinding` pack/unpack wrappers
  around its kernel (A3c), `MyoFilLink.updatePos` for every bound motor (small),
  step() for CPU-fallback Things, `recomputeDerivedSoA` over all Things,
  per-FilSegment Pt3D refresh, per-MyoMotor `bindTip` refresh, biochem, reset.

---

## 2. ThingStep — where it ran, where it runs now

The audit's "ThingStep ~29%" cost predates the Phase 1 anchor port (2026-06-02), the
Phase 2 F3/F4 port, the Phase 2 F1 port + tipC writeback, and the Phase 2 motor port.
Mapping the old number onto current code:

| Old ThingStep sub-cost | Current home |
|---|---|
| Per-Myosin `Myosin.jointConstraints()` — four inter-segment joints | Device, `jointsKernel` (chained TaskGraph) |
| `MyosinFixed.applyRodFixedPtForce` anchor spring | Device, folded into `jointsKernel` via `anchoredFlags[mj]`/`anchorPts[mj*3..]` (Phase 1) |
| `FilSegment.addLinkForces` (F3) | Device, `chainPairForcesKernel` |
| `FilSegment.addTorsionSpringForces` (F4) | Device, `chainPairForcesKernel` |
| `FilSegment.checkBugOrBoxCollision` (F1, box) | Device, `boundaryBoxKernel` (+ tipC writeback) |
| `MyoFilLink.addForces` (F8) | Device, `motorForceKernel` + `segMotorForceKernel` |
| `MyoFilLink.alignUVecTorque` (F9) | Device, motor/seg kernel pair |
| `MyoFilLink.alignYVecTorque` (F10) | Device, motor/seg kernel pair |
| **`Thing.moveThing()`** integration | Device, `moveThingKernel` (the move task) |
| Brownian generation (per-Thing) for GPU-handled Things | Device, inline Wang hash in `moveThingKernel` |
| `FilSegment.addNodeForces` (F5/F6) | **Still CPU** — but inactive in gliding (no nodes) |
| `MyoFilLink.updatePos` (re-derive `attachPt` from `end1AsPt3D() + posOnSeg·uVec`) | **Still CPU** every step on every bound motor (small) |
| `MyoFilLink.ckRelease` / `MyoMotor.dissociateADP` | **Still CPU** by design; reads device-written `forceMag`/`forceDotFil` from previous step (1-step lag) |
| `FilSegment.checkBugOrBoxCollision` pill path (F1b) | **Still CPU**, deferred port |
| `Thing.recomputeDerivedSoA` over `thingCt` Things | **Still CPU**, every step, post-move |
| Per-FilSegment `end1Pt`/`end2Pt` Pt3D refresh | **Still CPU**, every step, post-move |
| Per-MyoMotor `bindTip` Pt3D refresh | **Still CPU**, every step, post-move |
| CPU-fallback `step()` + `moveThing()` (Bug, MyoMiniFilament, ProteinNode, branches, ActA-bound segs, StickyNode) | **CPU forever** — these Things' pose is CPU-resident, not in the residency set |

**Verdict.** The old ~29% has been split: the bulk of the force compute and the
integration kernel itself are on the device today. What remains on the CPU side of
ThingStep is (1) the small per-motor `updatePos` CPU pose read, (2) the post-move CPU
sync block (`recomputeDerivedSoA` + Pt3D refresh) which exists *only* to feed the
remaining CPU pose readers, and (3) the F5/F6 node forces (no-op in gliding). The
post-move CPU sync block (item 2) is the cost the residency flip directly retires.

**Updated per-step cost percentages are stale.** Recommend (see §8) a fresh
`StepProfiler` run at gliding scale to refresh the apportionment — the priority
question (port residency infrastructure first vs port the next force first) hinges
on where the current dominant cost lives, and the 2026-05 profile predates four
ports.

---

## 3. Brownian forces — already device-resident for GPU-handled Things

This was the open question in the prompt; the survey answer is clean.

- **Generation (per Thing, per step) — already inline on the device kernel** for
  every GPU-handled slot. `moveThingKernel` (GPUMoveThing.java:2041-2086) computes:
  - `base = (slot * 1000003) ^ (stepCount * 999983) ^ (runSeed * 7919)`.
  - Six `wangHash` calls (with xor salts `0x9e3779b9`, `0x85ebca6b`, `0xc2b2ae35`,
    `0x517cc1b7`, `0x1f0a7ed5`) → six uniform deviates.
  - Three Box–Muller pairs → six Gaussian deviates `gfx/gfy/gfz`, `gtx/gty/gtz`.
  - Scaled by `brownianForceMag = sqrt(2kT/dt)` × `sqrt(bTransGam_i)` ×
    per-slot `tScale`/`rScale` (`brownianScales`). The per-slot scales bake in
    `xLinkTransAttn`/`myoBrownianAttn`/etc.
- **Determinism**: seeded by `(slot, stepCount, runSeed)`. `runSeed` is the
  Mersenne-Twister-derived seed cached on first execute. Same seed across the
  paired DEVICE vs CPU arms in the recent paired ensembles — that's how the
  motor-port and F3/F4-port paired-t analyses were possible.
- **The CPU `Thing.calcRandomForces`** runs **only for CPU-fallback Things** on
  `-gpu`. It reads no pose; only drag tensors. So even for those Things it is not a
  residency-blocking pose consumer.

**Verdict for residency.** Brownian is **NOT a residency-blocker**, and **no
greenfield device-RNG work is required**. The Wang-hash RNG and the deterministic
`(slot, stepCount, runSeed)` keying are already deployed and validated by the
paired ensembles. The audit (RESIDENCY_AUDIT.md A4) had this correct.

The slot-keying does have one tight coupling to the residency flip: a slot index
that is reassigned mid-run (topology change, swap-compact cleanup) would change
the per-slot Brownian draw sequence. This is the same constraint the
device-resident pose buffers already have. As long as the slot map is rebuilt
only on `topologyDirty` (which classifyThings already enforces), Brownian
determinism is preserved.

---

## 4. Grid fill / binding detection — the genuine Phase-3 work

Two interlocking CPU consumers, both real residency blockers:

### 4.a `MotorBindGrid3D.FillThreads`

`MotorBindGrid3D.java:282-321`. Runs in `motorBindGrid3DStart` wave every step.
- Reads `fs.end1AsPt3D()`/`fs.end2AsPt3D()` for every fil seg and `m.bindTip` for
  every motor.
- Bins them into `filCells[x][y][z][BIN_DEPTH]` / `motorCells[x][y][z][BIN_DEPTH]`
  via per-cell `synchronized (filCellSync[x][y][z])` with a timestamp-driven
  reset (cells whose `filTimeStamps != Env.counter` are treated as empty).

The timestamp-clear pattern doesn't map cleanly to a device kernel; the natural
device replacement is per-cell atomic counter writes (or per-cell histogram +
prefix-sum to assign write slots). This is the "highest-uncertainty port"
RESIDENCY_PLAN.md flagged as the v2-gate signal.

### 4.b `GPUMotorBinding.detectBindings()`

`GPUMotorBinding.java:250-408`. Runs in `motCollStart` wave on `-gpu`.

Per-step CPU work (every step):
- **Pack** `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodU*` (read by A1 from `Thing.soaCoord`/`soaUVec`)
  and `FilSegment.soaEnd1*/End2*/NodeAtEnd2` into FloatArrays/IntArrays — **transitively
  a pose reader.**
- **CSR-flatten** the `MotorBindGrid3D` per-cell arrays into `gridCellOffsets` +
  `gridCellContents` (`MotorBindGrid3D.packForGPU`).
- Both buffers and the motor+seg SoA are uploaded `EVERY_EXECUTION`.

Device:
- `bindKernel` walks 27 neighbour cells per motor, computes line-segment
  closest-point check, writes per-motor `boundSegId` (sentinel `-1` if no hit).

Per-step CPU work (after kernel):
- Walks `boundSegId` for every motor, calls `MyoMotor.theMotors[i].ontoFilament(seg,
  arcOnFil)` — and `ontoFilament` itself reads `soaEnd1X/Y/Z`, `soaEnd2X/Y/Z`,
  `MyoMotor.soaX/Y/Z` to compute the per-hit `arcOnFil` (MyoMotor.java:424-429).

So binding detection has CPU pose reads in **three** distinct sub-steps: the grid
build, the SoA pack, and the unpack-and-arcOnFil-compute. All three retire when
pose is device-resident AND the grid is on device — and the `arcOnFil` calc would
need to move into the bind kernel too (or to a second post-bind device kernel).

Note: `MotorBindGrid3D` is **distinct** from `Mesh.FILSEG_MESH`. The 2D `FILSEG_MESH`
(`Mesh.java:128-135`) is still filled every step but in gliding it only drives
`filSegMeshCollisions` for FilLink creation (`xLinks.isActive()` gated; off in
gliding) — so its inner-loop work is a no-op. The fill cost remains though.

---

## 5. Biochem / polymerization / release

Read-state per step (CPU-only):

- `FilSegment.biochemStep` (FilSegment.java:514-550): does **not** read pose in the
  steady (non-`lengthChanged`) case. Probability rolls + state machines only.
- `MyoMotor.biochemStep` (MyoMotor.java:213): nucleotide state transitions. No pose.
- `MyoFilLink.ckRelease` (MyoFilLink.java:247): reads `forceMag` (device-computed,
  drained at end of previous step) and `forceDotFil` / `forceDotFilTrack.averageVal()`
  (likewise device-computed). No direct pose read.
- `stericHindranceEnd2()` (FilSegment.java:2872-2875): reads `end2TipC` (now
  device-computed and bridged in via `bridgeBoundaryTipC` at end of previous
  `moveThings`). No direct pose read.

Write-state per step (CPU-only, conditional):

- `Monomer.polymerize`/`depolymerize` in end1/end2 BiochemSim mutates `coord` via
  `Pt3D.inc` and `length`; `FilSegment.biochemStep` then runs `pushCoordToSoa()`
  (flushes mutated Pt3D into `soaCoord`), `calculateProperties()` (drag tensor
  refresh), `initialize()` (per-Thing matrix refresh via `recomputeDerivedSoA`).
- `splitSegment` (FilSegment.java:537-541): topology change.

For residency: these conditional pose **writes** are the "topology-dirty re-upload"
mechanism Phase 4 calls out. They are rare (gated on biochem rolls), so a
topology-dirty bit + targeted device update is the canonical handling. Today the
path is CPU-only; the next step's pre-step `OP_PACK_*` picks up the CPU mutations.

Once Phase 4 lands (pose device-resident across steps), these CPU mutations need to
flow to the device. The cleanest design (consistent with the plan) is the
topology-dirty bit: any FilSegment whose `lengthChanged` or `splitSegment` fires
sets the dirty bit; at end-of-biochem, `dispatchAndWait(OP_PACK_RANGE, dirtySlots)`
re-uploads only the affected slots.

---

## 6. The residency-blocking list (post-Phase-2)

Sorted by location in the per-step loop. The {must port} set is what's left to
move off CPU pose before `OP_UNPACK` can become "output-frame only" — the binary
condition of the Phase 4 flip.

### Already ported (and validated)

| Audit ID | Where | Phase |
|---|---|---|
| A7.b | `MyosinFixed.applyRodFixedPtForce` anchor spring | Phase 1 — DONE 2026-06-02 |
| A9.F3 | `FilSegment.addLinkForces` (chain link) | Phase 2 — DONE 2026-06-03 |
| A9.F4 | `FilSegment.addTorsionSpringForces` (chain torsion) | Phase 2 — DONE 2026-06-03 |
| A9.F1 (box) | `FilSegment.checkBugOrBoxCollision` (Chamber from-inside) + tipC writeback | Phase 2 — DONE 2026-06-03 |
| A9.F8 | `MyoFilLink.addForces` (cross-bridge spring) | Phase 2 motor — DONE 2026-06-04 (borderline PASS, 1-step `ckRelease` lag caveat) |
| A9.F9 | `MyoFilLink.alignUVecTorque` | Phase 2 motor — DONE 2026-06-04 |
| A9.F10 | `MyoFilLink.alignYVecTorque` | Phase 2 motor — DONE 2026-06-04 |
| A4 (GPU-handled Things) | Brownian inline in `moveThingKernel` | already on device pre-campaign |
| A12 (move integration) | `Thing.moveThing` per-entity → `moveThingKernel` | already on device pre-campaign |
| A9.F1 (tipC writeback for box) | `bridgeBoundaryTipC` | Phase 2 — DONE 2026-06-03 |

### Still-to-port — gliding rotation (what blocks the flip today)

| # | Consumer | Where | Notes |
|---|---|---|---|
| **P1** | `MyoMotor.fillSoaArrays` + `FilSegment.fillSoaArrays` | BoxOfActin.java:860-861 | Trivial CPU loops reading `Thing.soaCoord/UVec` to derive motor end2 and seg end1/end2. Retire when their consumers (mesh fill A2, grid build A3a, GPUMotorBinding pack A3c, the unpack-side `arcOnFil` calc) are device-resident — they exist only to feed those. |
| **P2** | `Mesh.MeshThreads.meshFils` (FILSEG_MESH fill) | Mesh.java:128-135 | Reads `curSeg.end1AsPt3D()/end2AsPt3D()`. In gliding the bin contents drive `filSegMeshCollisions`, whose inner-loop work is gated `xLinks.isActive()` (off). Could be deleted in the gliding rotation, or moved to a device-side spatial grid that also serves binding detection (the audit's "one unified grid" recommendation). |
| **P3** | `MotorBindGrid3D.FillThreads` (3D grid build) | MotorBindGrid3D.java:282-321 | Reads every fil's `end1Pt`/`end2Pt` and every motor's `bindTip`. **The Phase-3 device-grid-build target.** Highest-uncertainty port per RESIDENCY_PLAN.md. |
| **P4** | `GPUMotorBinding.detectBindings()` CPU pack + CPU unpack | GPUMotorBinding.java:330-399 | The pack reads motor + seg SoA every step; the unpack walks `boundSegId` and computes `arcOnFil` per hit (which itself reads `soaEnd1/End2` and `MyoMotor.soa*`). Eliminated when P1 + P3 land and the bind kernel reads device pose directly; `arcOnFil` needs to be either computed in the bind kernel or in a second small post-bind kernel writing a per-motor `posOnSeg`. |
| **P5** | `MyoFilLink.updatePos` for every bound motor on the GPU path | MyoFilLink.java:215-221 | Reads `mySeg.end1AsPt3D() + posOnSeg·mySeg.uVecAsPt3D()` to populate `attachPt`. On the device path the `attachPt` is unused (only addForces/align* read it, and those are skipped) but the read still happens. Small per-motor pose read, every step. Fix: gate `updatePos()` on `gpuMotorHandled()` inside `MyoFilLink.step()`. **Not enumerated as a row in the 2026-06-02 audit — flagging here as a small but real residency blocker.** |
| **P6** | `Thing.recomputeDerivedSoA(0, thingCt)` | Thing.java:736-784 (called from `GPUMoveThing.moveThings`:3647) | Refreshes `soaEnd1`/`soaEnd2`/`soaZVec`/`soaTransXTox` on the CPU after `OP_UNPACK`. Phase 4 must port this to a device kernel run after `move` and before any kernel that reads derived fields (today: only the same step's downstream consumers, but cross-step the `chain` kernel reads `soaLengthArr` and the `boundary` kernel reads the seg's end-derived box-fit — all currently re-uploaded from CPU). **Phase 4 prerequisite.** |
| **P7** | Per-FilSegment `xRange/yRange/zRange` + `end1Pt`/`end2Pt` Pt3D refresh | GPUMoveThing.java:3653-3661 | Same shape as P6; these Pt3D snapshots exist purely to back `end1AsPt3D()`/`end2AsPt3D()` for CPU consumers. Delete entirely when CPU consumers retire. |
| **P8** | Per-MyoMotor `bindTip` Pt3D refresh | GPUMoveThing.java:3663-3668 | Same — `bindTip` backs `MyoMotor.bindTip.x/y/z` for the binding grid and `updatePos`. Delete when P2/P3/P5 retire. |
| **P9** | `OP_UNPACK` (device → host coord/uVec/yVec) | GPUMoveThing.java:3610 | **The blocker itself.** Becomes "every output frame only" when P1-P8 land. Pose persists on device across steps. |
| **P10** | Pre-step `OP_PACK_*` upload of coord/uVec/yVec | GPUMoveThing.java:3452-3454 | Becomes FIRST_EXECUTION only (Phase 4); pose persists on device across steps. Topology-dirty / biochem-pose-write events trigger a targeted slot re-upload. |
| **B1** | `Thing.biochemStep` poly/depoly pose writes | FilSegment.java:530-541 | Conditional pose **writer** (not reader). Phase 4 handling: topology-dirty bit + `OP_PACK_RANGE` for affected slots. Rare; no per-step cost. |

### Deferred — config-specific (not in the gliding-rotation residency scope)

These are pose readers in OTHER configs but no-op or absent in gliding:

| Audit ID | Consumer | Condition |
|---|---|---|
| A9.F1 (pill) | `Bug.amICollidingInner/Outer` | `Env.bugShapedCrucible == true`. CPU pill revival landed 2026-06-03 (commit `8451ff9`); device `boundaryPillKernel` not built. |
| A5.a | `FilLink.enforceFilLink` (xLink force) | `Env.xLinks.isActive() == true`. Off in gliding. |
| A2.d | `filSegMeshCollisions`/`checkToLink` (xLink creation broad-phase) | `Env.xLinks.isActive()`. Off in gliding. |
| A5.b | `Arp23.enforceFilLink` | Listeria configs. |
| A5.c, A9.F2 | `ActA.step` / `checkBugCollisionFromOutside` | `simOutsideBug == true` (Listeria). |
| A6 | `NodeLink.enforceNodeLink` + membrane relaxation | StickyNode-network configs; pose CPU-resident regardless — not a residency blocker. |
| A7.c | `MyosinDimer.jointConstraints` | `myoDimerCt > 0`. Gliding has none. |
| A8.a/b | `Crucible.keepMyosinOnSurface[Dimer]` | Chamber-fixed-myosin configs. Gliding uses `MyosinFixed`. |
| A9.F5/F6 | `FilSegment.addNodeForces` | Node-tethered (formin/plasmid) configs. Gliding has no nodes. |
| A9.F7, A9.F12 | `MyoMiniFilament.checkOuterBugCollision`, `ProteinNode.checkBugOrBoxCollision` | These Things are CPU-fallback (pose CPU-resident); not residency blockers. |
| Diagnostic samplers (A16) | JointDiag/JointParamDiag/SingleMyoDiag | Gated by env flags. |

---

## 7. Derived-field recompute (Phase 4 scope)

The pose state fans out into several **derived** SoA arrays that downstream consumers
read every step. Today they are recomputed CPU-side after `OP_UNPACK`:

| Derived field | Recomputed by | Read by (steady state) |
|---|---|---|
| `Thing.soaEnd1` (`coord − halfLen·uVec`) | `Thing.recomputeDerivedSoA` | mesh fills, MotorBindGrid3D, GPUMotorBinding pack/unpack, `end1AsPt3D()` |
| `Thing.soaEnd2` (`coord + halfLen·uVec`) | same | same |
| `Thing.soaZVec` (`uVec × yVec`, normalised) | same | body-frame transforms, `transXTox` |
| `Thing.soaTransXTox` (3×3 fixed→body row-major) | same | `bForceSum.XToxFromFloats` in CPU `moveThing`, `Pt3D.XTox/xToX` |
| `FilSegment.soaEnd1X/Y/Z` + `soaEnd2X/Y/Z` (CPU-side mirrors) | `FilSegment.fillSoaArrays` | mesh fill, MotorBindGrid3D, GPUMotorBinding pack |
| `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodUX/RodUY/RodUZ` | `MyoMotor.fillSoaArrays` | same |
| `FilSegment.end1Pt/end2Pt` Pt3D | post-move CPU refresh | `MyoFilLink.updatePos`, `bugForcesFromInside`, `addLinkForces` (CPU fallback), `addNodeForces`, FilLink/NodeLink/Arp23 force methods |
| `FilSegment.xRange/yRange/zRange` | post-move CPU refresh | mesh binning heuristics |
| `MyoMotor.bindTip` Pt3D | post-move CPU refresh | mesh fill, MotorBindGrid3D, `MyoFilLink.updatePos` |

Phase 4 work item: **a `recomputeDerivedKernel`** that runs on device immediately
after `move`, populating device-side `soaEnd1`/`soaEnd2`/`soaZVec`/`soaTransXTox`
from the new `coord/uVec/yVec`. The Pt3D snapshots and CPU SoA mirrors disappear
entirely once their CPU readers are gone — they exist as CPU caches for now.

The kernel itself is structurally trivial (one thread per slot, the exact body of
`Thing.recomputeDerivedSoA`'s inner loop, runs after `move` writes `coord/uVec/yVec`).
Its prerequisite is that every device-side kernel that today reads `soaLengthArr`
or `cockedFlags` etc. continue to do so from device buffers — already true.

---

## 8. Plan reconciliation — does RESIDENCY_PLAN.md still match reality?

**Substantially yes, with three callouts.**

### 8.a Phase 1 — DONE
Anchor spring is on device, validated. The `MyosinFixed.applyGPUDroppedForces` CPU
residue is now a flag-controlled fallback (`DIAG_CPU_ANCHOR=true`), gated correctly
in `Myosin.MyosinThreads.execute()` (Myosin.java:121-125). No live CPU pose read in
the myoJoints1 wave on gliding default.

### 8.b Phase 2 — DONE for gliding slice
F3/F4 + F1 (box) + F8/F9/F10 ported. Phase 2 motor port flagged a 1-step `ckRelease`
lag — a Phase-2 follow-up, NOT a Phase 4 item. Two remediation paths called out in
the 2026-06-04 journal entry (move release to a post-`moveThings` phase, or move
motor force to a pre-step-phase device task). **Not blocking residency.**

F1b (pill) is deferred CPU; needs `boundaryPillKernel`. Not in the gliding rotation.

### 8.c Phase 3 — NOT STARTED. Still the genuinely hard piece.
`MotorBindGrid3D.FillThreads` is still CPU. The plan calls for either atomic
per-cell counters or histogram + prefix-sum to assign write slots. Suggests building
"one unified 3D grid (0.2 µm cubes) designed to also serve the FilLink broad-phase
later" — consistent with the audit's recommendation. The shared-grid design is for
post-residency; building the binding-only device grid in Phase 3 is the next port.

Note that the binding-detection path has **three** CPU pose-reading sub-steps today,
not two as the plan emphasised: (1) the grid build (A3.a), (2) the SoA pack (A3.c),
and (3) the post-unpack `arcOnFil` computation (`ontoFilament` reads `soaEnd1/2X/Y/Z`
and `MyoMotor.soa*` — GPUMotorBinding.java:386-398). The `arcOnFil` calc either folds
into the bind kernel or moves to a second post-bind device kernel writing
`posOnSeg[motor]` for the CPU `ontoFilament` to consume. Worth scoping in Phase 3
explicitly because it's the third pose-read that the unpack would otherwise leave
behind.

### 8.d Phase 4 — NOT STARTED. Scope is correctly bounded by the plan.
Plan calls out: remove `OP_UNPACK`, delete post-move CPU sync block, remove pre-step
pose upload (FIRST_EXECUTION only), biochem pose writes via topology-dirty re-upload.

The derived-field device kernel (§7 above) is the implicit Phase 4 work item the plan
gestures at — "derived fields now recomputed on device" — and is the one piece of
new kernel code Phase 4 needs beyond the bridge deletions and the OP_UNPACK gating.

### 8.e Gaps to flag prominently

These are places the plan's structure does NOT cleanly cover the remaining work, or
where reality has shifted underneath:

1. **`MyoFilLink.updatePos` is a residency blocker not enumerated in the 2026-06-02
   audit table.** It runs CPU-side every step on every bound motor on the GPU path,
   reads `mySeg.end1AsPt3D()` and `mySeg.uVecAsPt3D()`, but produces an `attachPt`
   that is unused on the device path. Small per-motor pose read. Fix shape: gate
   `updatePos()` on `gpuMotorHandled()` inside `MyoFilLink.step()` (essentially free)
   OR retire the field if no other code path reads `attachPt` outside of
   addForces/align*. Belongs in the Phase 4 cleanup, not Phase 3.

2. **The post-Phase-2 ThingStep cost profile is unknown.** The audit / plan rest on
   ~29% / ~16% / ~15% numbers from before four device ports. The "ports do not move
   wall-clock until the flip" framing (RESIDENCY_PLAN.md §Goal and milestone) is
   correct in principle, but the *prioritisation* between Phase 3 (binding grid) and
   Phase 4 (flip) depends on whether the residency-blocking CPU costs are still the
   plurality of step time. A 60-second `StepProfiler` run at gliding-assay scale with
   `-gpu` would refresh the apportionment. **Recommended as a separate follow-up run,
   not in this survey.** The `StepProfiler` instrumentation already exists
   (referenced in FilSegment.step lines 486-508); flip its env-flag enable and let
   the next gliding ensemble print the new breakdown.

3. **Brownian + device RNG are NOT a gap.** The plan and audit both correctly treat
   Brownian as not a residency blocker. The Wang-hash device RNG is already
   committed in `moveThingKernel` and validated by every paired ensemble since the
   campaign began. No new RNG infrastructure is needed; the residency campaign does
   not need to slot a "device RNG" phase. (Calling this out explicitly because the
   prompt asked.)

4. **`MyoMotor.ontoFilament`'s post-bind `arcOnFil` calc** (point in 8.c above) is the
   third Phase-3 CPU pose-read sub-step. Worth folding into the Phase 3 design rather
   than leaving as a separate Phase-4 cleanup. Either fold into the bind kernel, or
   compute device-side and write `posOnSeg[motor]` for the CPU `ontoFilament` to read.

5. **The plan's Phase 3 "one unified grid serving binding + FilLink broad-phase"** is
   aspirational for the gliding rotation, since gliding has no FilLink turnover. The
   Phase 3 build can be binding-only; the unified-grid refactor is post-residency,
   when a config with crosslinker turnover enters the validation rotation. The plan
   already notes this; flagging for emphasis so the v2-gate is read as "binding-only
   grid build", not "build the unified grid before residency".

6. **The "1-step `ckRelease` lag"** from the Phase 2 motor port is a Phase 2 follow-up
   that exists OUTSIDE the residency campaign sequence. It should be tracked
   separately so it isn't conflated with the residency flip. Two-line note from the
   2026-06-04 journal: address either by reordering release to post-move, or by
   moving motor force to a pre-step-phase device task. Neither path is on the
   residency critical chain.

---

## 9. Recommended next executable steps (planner-decides)

Not a green light, just the structural read:

1. **(separate follow-up run)** Re-run gliding with `StepProfiler` enabled — refresh
   the per-step cost apportionment after the four Phase-2 ports. Output: an updated
   breakdown of ThingStep / Brownian (now near-zero CPU) / grid fill / mesh fills /
   biochem / move-bridge / fallback-step. Set port priority on that.
2. **(Phase 3 design — not coding)** Build the device-side binding-detection 3D grid
   spec, including the `arcOnFil` device-side computation (§8 callout 4). Decide
   on atomic counters vs histogram+prefix-sum for the grid build. Scope as
   binding-only; defer unified-grid to post-residency.
3. **(small Phase 4 prep)** Gate `MyoFilLink.updatePos` on `gpuMotorHandled()` to
   eliminate the un-enumerated per-motor pose read (§8 callout 1). Cheap, isolatable,
   no observable change (verifies in any gliding ensemble).
4. Develop the **derived-field device kernel** (the `recomputeDerivedKernel`) and
   wire it into the chained TaskGraph after `move` and before the next step's
   reading kernels. This is Phase 4 prerequisite; can be done before Phase 3 lands.
5. Phase 3 implementation (binding-grid + bind-kernel rewrite reading device pose).
6. Phase 4 flip: delete `OP_UNPACK` per-step, delete post-move CPU sync block,
   FIRST_EXECUTION pose upload, biochem topology-dirty `OP_PACK_RANGE`. Validate
   against the gliding ensemble — this is where wall-clock finally drops.

---

## Confidence and constraints

- Read-only code/doc analysis. No edits, no compile, no `java` invocation.
- This document does not modify `JOURNAL.md`, `.last_run_status`, `RUN_LOGS/`, or
  any other artifact the sibling lag-confirmation Claude Code instance may be
  writing.
- The CPU consumer enumeration in §1.d and the still-to-port table in §6 are by
  direct inspection of the cited line numbers / file paths against HEAD `a0fe20f`,
  cross-checked with `RESIDENCY_AUDIT.md` and the recent JOURNAL.md entries
  (2026-06-02 anchor through 2026-06-04 motor). Where this survey adds to the audit
  (notably P5 / `MyoFilLink.updatePos` and the §8.d Phase-3 `arcOnFil` callout) the
  divergence is flagged explicitly rather than silently appended to the audit table.
- The ThingStep mapping in §2 is structural (which method runs where), not
  numerical (what fraction of wall-time). Recommend the §8.d follow-up for the
  numbers.
- The plan reconciliation in §8 treats RESIDENCY_PLAN.md as the canonical sequence
  and flags places reality has shifted, rather than re-deriving a new sequence.
