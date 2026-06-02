# step() GPU port — pre-port survey

Date: 2026-06-01
Status: SURVEY ONLY (no code changes, no compile, no runs).
Purpose: ground-truth catalog of everything `Thing.step()` does, so the port plan
and validation plan can be designed from verified facts — not assumptions — and
so no force is silently dropped (see `GPU_MIGRATION_LESSONS.md` Lessons 1, 2, 5).

Scope of "step()" in this document is the dispatch fanned out by
`ThingStepThreads` at `Env.stepStart` in `BoxOfActin.doLoop()` (line 798):
each non-removed `Thing` has its `step()` method invoked in parallel. The
methods called from `step()` are the surface of the port.

**What this survey does NOT cover.** Other phases that also accumulate into
`soaForceSum` / `soaTorqueSum` (Brownian, xLink/Arp23, membrane links, myosin
joints 1 & 2) run BEFORE `step()` in the same timestep. Their ordering and the
fact that they share the same accumulators are documented in Part 3 because the
port has to respect them, but they are not the subject of this survey.

---

## Part 1 — Force inventory

The `step()` dispatch hits every `Thing` once. Each subclass has a different
`step()` body; the per-subclass inventories below cover every CPU `step()`
override. Anything not listed has an empty (no-op) `step()` and contributes
nothing to forces or torques.

### Table A — Force inventory per subclass

| # | Force / torque | Subclass.step() method | Per-entity vs PAIRWISE | Reads | Writes (`incForceSum` / `incTorqueSum` targets) | Stiff? |
|---|---|---|---|---|---|---|
| F1 | Filament–boundary collision (inside chamber) | `FilSegment.checkBugOrBoxCollision` → `checkBugCollisionFromInside` → `bugForcesFromInside` | per-entity (segment vs static boundary) | own `end1Pt`, `end2Pt`, `coord`, `bRotGam.y`, `bTransGam.x`, `Chamber.dims` | self.forceSum, self.torqueSum, `end1AxialForce`/`end2AxialForce` | N (drag-scaled spring; no analytic match required) |
| F2 | Filament–bug collision (outside, Listeria mode) | `FilSegment.checkBugCollisionFromOutside` → `bugForcesFromOutside` | PAIRWISE: segment ↔ `lmBug` | segment ends, segment radius, `lmBug.coord`, `lmBug.bTransGam.x`, geometry of bug | self.forceSum (at endpoint), `lmBug.forceSum`, `lmBug.addPathColForceOnBug`, `lmBug.addNormalForce` | N |
| F3 | Filament chain link force (translational) | `FilSegment.addLinkForces` | PAIRWISE: this segment ↔ `end2Fil` (chain neighbour) and ↔ `end1Fil` | own/neighbour `end1Pt`/`end2Pt`/`uVec`/`uVecR`, `length`, `bTransGam.x/y`, `bRotGam.y`, `Env.fracMove`, `Env.fracR`, `Env.deltaT` | self.forceSum, neighbour.forceSum, self.torqueSum, neighbour.torqueSum, `incEnd*AxialForce` on both | **Y** (Lagrange-multiplier stiff constraint pinning segment-to-segment) |
| F4 | Filament chain torsion spring (bending rigidity) | `FilSegment.addTorsionSpringForces` | PAIRWISE: chain-neighbour pair | own/neighbour `uVec`/`uVecR`, `bRotGam.y`, `Env.fracMoveTorq`, `Env.filTorqSpring` (active=false → uses drag-formulation, see Part 5 note) | self.torqueSum, neighbour.torqueSum | **Y** (this is the filament bending stiffness; matches `persistenceLength = 15 µm`) |
| F5 | Filament–formin/plasmid node tether spring (force) | `FilSegment.addNodeForces` | PAIRWISE: segment ↔ `end1Node`/`end2Node` | own endpoint, `node.coord`, `node.bTransGam.x`, `bTransGam.x`, `Env.fracMove`, `Env.deltaT` | self.forceSum (at endpoint), `node.forceSum`, `incEnd*AxialForce` | Y (linear spring at strain) |
| F6 | Filament–formin/plasmid node alignment torque | `FilSegment.addNodeForces` (inside `Env.nodeTorqSpring.isActive()`) | PAIRWISE: segment ↔ end2Node | own `uVec`, `forminVecInx` (body-frame on node), `transXTox` on node, `Env.nodeTorqSpring` | self.torqueSum, `node.torqueSum` | Y (Hookean angular spring) |
| F7 | MyoMiniFilament–bug/boundary collision | `MyoMiniFilament.step` → `checkOuterBugCollision` | per-entity (mini-filament centerline endpoints vs boundary) | own `end1`, `end2`, `radius`, `bTransGam`, `Env.nodeFracMove`, `Env.collisionDeltaT` | self.forceSum | N |
| F8 | MyoMotor–filament tether translational spring | `MyoMotor.step` → `updateMyoFilLinks` → `tipLink.step()` (`MyoFilLink.addForces`) | PAIRWISE: motor head ↔ bound `FilSegment` (via `MyoFilLink.mySeg`) | `motorPt` (= `bindTip`), `attachPt` (= `seg.end1 + posOnSeg*seg.uVec`), `MyoFilLink.myoSpring` (1e-9 N/µm) | `myMotor.forceSum`, `mySeg.forceSum` | Y (motor spring; small constant `myoSpring`) |
| F9 | MyoMotor–filament uVec-alignment torque | `MyoFilLink.alignUVecTorque` | PAIRWISE: motor head ↔ bound seg | `mySeg.uVec`, `myMotor.uVec`, `myMotor.isCocked`, motor/seg `bRotGam.y`, `Myosin.uncockedMotor_ActinAngle` / `cockedMotor_ActinAngle`, `Env.myoJ1FracMoveTorq`, `Env.deltaT` | `mySeg.torqueSum`, `myMotor.torqueSum` | Y (joint angle restoring) |
| F10 | MyoMotor–filament yVec-alignment torque | `MyoFilLink.alignYVecTorque` | PAIRWISE: motor head ↔ bound seg | `mySeg.yVec`, `myMotor.yVec`, motor/seg `bRotGam.x`, `Env.myoJ1FracMoveTorq` | `mySeg.torqueSum`, `myMotor.torqueSum` | Y |
| F11 | ActA tether spring (filament ↔ Listeria surface) | `ActA.step` → `applyTetherForce` (only when `bound`) | PAIRWISE: `boundFil` ↔ `lmBug` (the Listeria) | `ActA.actAPtInX`, `boundFil.coord`/endpoint near binding, `lmBug.coord` | `boundFil.forceSum`, `lmBug.forceSum` (probable — verify in `applyTetherForce`) | Y |
| F12 | ProteinNode–boundary collision | `ProteinNode.step` → `checkBugOrBoxCollision` | per-entity (node center vs boundary) | own `coord`, `radius`, drags | self.forceSum | N |
| F13 | StickyNode tethering / membrane interactions | StickyNode inherits `ProteinNode.step()` (no override). Its membrane-link forces run in the `membraneLinksStart` phase (`NodeLink.enforceLink`), NOT in step(). | — | — | — | — |

#### Things whose `step()` does no force computation

These are all the remaining `Thing` subclasses, listed so the port can confirm
the silent-drop checklist is complete.

| Subclass | step() body | Note |
|---|---|---|
| `Thing` (base) | empty (`// put the code here...`) | most base-class instances are `Crucible`/`Chamber`/`AnchorNode` etc. |
| `Bug.step` | `setViscousDrag()` only — recomputes own drag tensors based on `tipsInShell`. **No force or torque incremented**, but it mutates `bTransGam`/`bRotGam`/`bTransDiff`/`bRotDiff`. | Side-effect on drag tensors — moveThing() reads these. NOT trivially a "force." Flag for port: drag-tensor side effects on Bug must be preserved or the boundary-collision drag scaling silently breaks. |
| `AnchorNode.step` | empty | overrides ProteinNode to suppress its boundary check |
| `MyoRod.step` | empty | rod forces come from joints (myoJoints1 phase) — not from step() |
| `MyoLever.step` | empty | same — joint phase |
| `StaticFilSegment.step` | mirror of `FilSegment.step` minus `addNodeForces` | duplicates F1, F3, F4 |
| `FillNode.step` | `super.step()` + radius growth | calls ProteinNode.step (F12) then mutates `radius` |
| `Crucible.step` / `Chamber.step` | none (inherit base no-op) | boundary lives in their `amICollidingOuter`, queried by other Things |

#### Forces NOT in step() but accumulating into the same buffers (context)

These are listed only so the port doesn't accidentally claim "step() covers all
forces" — it does not. They run in earlier phases:

- **Brownian random force/torque** — `Thing.calcRandomForces` in `bForcesStart` phase. Per-Thing. On GPU path, GPU-handled Things use Wang-hash inline.
- **Filament crosslinks (xLinks)** — `FilLink.enforceFilLink` in `xLinkStart`. Spatially-resolved pair force/torque between filaments not in the same chain. CPU only.
- **Arp2/3 branches** — `Arp23.enforce*` in `xLinkStart`. Pair force/torque, mother↔daughter filament.
- **NodeLink (membrane)** — `NodeLink.enforceLink` in `membraneLinksStart`. Pair force, node↔node.
- **Myosin internal joints (lever-motor, rod-lever)** — `Myosin.jointConstraints` (CPU) **or** `GPUMyosinJoints` kernel + `MyosinFixed.applyGPUDroppedForces` (anchor spring, GPU path). In `myoJoints1Start`.
- **MyosinDimer rod-coupling joints (parallel/antiparallel)** — `MyosinDimer.jointConstraints` in `myoJoints1Start`. CPU only.
- **Chamber-fixed myosin/myodimer surface tethers** — `Crucible.keepMyosinOnSurface` / `keepMyosinDimerOnSurface` in `myoJoints2Start`.

### Stiff-constraint flags (Lesson 5 relevance)

Forces marked stiff (F3, F4, F5, F6, F8, F9, F10, F11) drive the simulation's
mechanical equilibrium and are most likely to surface subtle bias if a GPU port
silently changes their magnitude or drops one of them. Of these, **F3 and F4
are the only ones validated by the standing deflection + LP benchmarks** (Part
5). F5/F6/F8/F9/F10/F11 have weaker direct validation coverage.

---

## Part 2 — Spatial data structures

### Structures that exist in the codebase

| Structure | Class | Built when | Purpose | Cell size |
|---|---|---|---|---|
| `Mesh.FILSEG_MESH` | `Mesh` (2D) | `meshFilsStart` phase, per step when `collisionCkCounter >= collisionCheckInt` | XY-binned filament-segment endpoints, used by `filSegMeshCollisions` (xLink phase) | 0.2 µm XY; no Z bins (2D) |
| `Mesh.NODE_MESH` | `Mesh` (2D) | `meshNodesStart` phase | Same as above for membrane nodes | 0.2 µm XY |
| `Mesh.MYOHEADS_MESH` | `Mesh` (2D) | `meshMotorsStart` phase | Legacy 2D motor-head grid; the 3D path supersedes this for motor binding | 0.2 µm XY |
| `MotorBindGrid3D.INSTANCE` | `MotorBindGrid3D` (true 3D) | `motorBindGrid3DStart` phase, per step | 3D cubic grid of filament cells + motor-head cells; consumed by `CkMotsThreads` (CPU) **or** packed CSR-style and shipped to GPU in `GPUMotorBinding.detectBindings` | 0.2 µm cube; `BIN_DEPTH` = 1000 |

### Which of these does `step()` actually consume?

**None directly.**

This is the central structural finding for the port plan.

- `FilSegment.step()`'s `addLinkForces` / `addTorsionSpringForces` / `addNodeForces` traverse **chain-topology pointers** (`end1Fil`, `end2Fil`, `end1Node`, `end2Node`, `ptAtEnd1`, `ptAtEnd2`) stored on the segment itself. These are fixed per filament topology, not a spatial query.
- `FilSegment.checkBugOrBoxCollision` is a point-vs-boundary check against the single static `theBox` (Crucible / Chamber / Bug). No grid.
- `MyoMotor.step()` → `tipLink.step()` reaches the bound filament through `MyoFilLink.mySeg` — a fixed pointer set at binding time. No grid.
- `MyoMiniFilament.step()` → `checkOuterBugCollision` is point-vs-boundary against the chamber.
- `ProteinNode.step()` → `checkBugOrBoxCollision` is point-vs-boundary.
- `ActA.step()` reaches its `boundFil` through a fixed pointer.

### Implication for GPU port

Because step() forces are pointer-traversed (chain or fixed binding), **the
hard part of porting pairwise forces — a GPU spatial grid + neighbour-list
build — is not on the critical path**. What the port needs instead is:

- A **filament-segment chain index** on the device: per segment, the slot IDs
  of `end1Fil` and `end2Fil` (and `end1Node`/`end2Node`), and `ptAtEnd1Side`
  flag (= `ptAtEnd2 == end2Fil.end1Pt` vs `end2Pt`). Topology is stable across
  steps except after split/anneal/break events; rebuild on topology-change
  steps, otherwise reuse.
- A **MyoFilLink index** on the device: per motor, the slot ID of the bound
  filament segment, `posOnSeg`, and the current cocked/state bits. The CPU
  `MotorBindGrid3D` pipeline already maintains motor-↔-seg binding decisions
  on a per-step cadence; the port can hand the GPU joint-style kernel the
  resulting bound-pair list directly.
- The boundary (Bug/Chamber) is a single static object — supply its geometry
  to the kernel via uniform / scalar arguments, as the existing GPU work
  already does for box dims.

The CPU 2D `Mesh.FILSEG_MESH` and the 3D `MotorBindGrid3D` are **not consumed
by step()** and don't need GPU mirrors *for the step() port*. They are
consumed by the xLink phase and the motor-binding phase respectively, which
already run before step() and either stay CPU (xLink) or are already on GPU
(`GPUMotorBinding`).

### Write-conflict analysis

Pairwise writes in step() target *both* members of the pair:
- F3/F4 write to self AND chain-neighbour
- F8/F9/F10 write to self (motor) AND bound segment

In the existing CPU path, the dedup is the `end2LinkCkd`/`end1LinkCkd` /
`end2TorqCkd`/`end1TorqCkd` flags + the worker-thread accumulators
(`taForce`/`taTorque`) drained by `gatherThreadAccumulators` at
`gatherForcesStart`. On GPU, the cleanest mapping is the same pattern as
GPU joints (Lesson postmortem § C): assign each pair to a single owning
thread (e.g., the segment with lower `myThingNumber` owns the link), have it
write both sides directly into the device-side accumulators, and rely on
unique ownership for atomic-free correctness. This works because chain
topology gives a natural canonical owner per pair. The MyoFilLink case
already has unique ownership (motor↔seg, one tipLink per motor).

---

## Part 3 — Dependency and ordering

### Within step() (single-Thing): order forces are accumulated

Inside `FilSegment.step()` the order is fixed and matters only because some
sites also write `incEnd*AxialForce` (a separate per-end axial-load
accumulator used by biochemistry):
1. `checkBugOrBoxCollision` (boundary forces)
2. `addLinkForces` (chain link forces — also writes axial)
3. `addTorsionSpringForces` (chain torsion)
4. `addNodeForces` (formin/plasmid tether — also writes axial)

`MyoMotor.step()` calls `updateMyoFilLinks` → `tipLink.step()` which runs
`updatePos` → `addForces` → `alignUVecTorque` → `alignYVecTorque` →
`ckRelease`. `ckRelease` is a state mutation, not a force; if it fires, the
forces already added this step persist.

### Across timestep — the buffers step() writes into

`step()` writes into the **per-thread `taForce[t]` / `taTorque[t]` double
accumulators** (`Thing.incForceSum` switches on `tlsThreadId.get()`). These
are folded down into the float `soaForceSum`/`soaTorqueSum` arrays by
`gatherThreadAccumulators` in the `gatherForcesStart` phase (line 807) — AFTER
step() and BEFORE `moveThing()`.

### Phase ordering (one timestep — extracted from `BoxOfActin.doLoop` and `Env.*Start` constants)

```
0  Pre-step:  setBiophysValues, ensureAccumCapacity, clearSoaForcesTorques(thingCt),
              MyoMotor.fillSoaArrays, FilSegment.fillSoaArrays,
              GPUMoveThing.onStepStart (GPU classification)
1  meshFils    — Mesh.FILSEG_MESH fill   (every collisionCheckInt steps)
2  meshNodes   — Mesh.NODE_MESH fill
3  meshMotors  — Mesh.MYOHEADS_MESH fill
4  meshColl    — filSegMeshCollisions → checkToLink (registers FilLinks)
5  motorBindGrid3D — fill 3D grid
6  motColl     — CPU motor-fil collision (skipped on GPU path)
   GPUMotorBinding.detectBindings (GPU path only)
7  sampleBoundMotors
8  bForces     — Thing.calcRandomForces (Brownian); GPU-handled Things skipped
9  xLink       — FilSegment.zeroAllLinkCts then FilLink.enforceFilLink + Arp23.enforce + ActA register
10 membraneLinks — NodeLink.enforceLink (one pass)
11 myoJoints1  — Myosin.jointConstraints (CPU) OR GPU joints kernel chained inside GPUMoveThing;
                  MyosinDimer.jointConstraints (always CPU);
                  on GPU path, CPU still runs Myosin.applyGPUDroppedForces (anchor spring)
12 myoJoints2  — Crucible/Chamber surface-tether (keepMyosinOnSurface)
13 step        — *** THIS SURVEY'S TARGET ***
                  Thing.step() for every Thing — see Part 1
14 gatherForces — fold per-thread doubles into soaForceSum/soaTorqueSum floats
15 (benchmark F-injection — bench mode only)
16 move        — Thing.moveThing() (CPU) OR GPUMoveThing.moveThings (GPU);
                  this is the integration step that consumes forceSum/torqueSum
17 (benchmark pin restore — bench mode only)
18 biochem     — Thing.biochemStep
19 resetCt     — Thing.resetCounters (zero per-Thing bookkeeping)
20 membrane relaxation loop — membraneLinks → gatherForces → membraneMove ×N
21 updateCounters, JointDiag/JointParamDiag/SingleMyoDiag samples
22 safe-point (pause/inspect/param drain)
23 (benchmark cadence work)
24 logAndDraw / cleanup1..4
```

### What must be true BEFORE step() runs

1. `Thing.clearSoaForcesTorques(thingCt)` has been called this step.
2. Poses (`soaCoord` / `soaUVec` / `soaYVec`) are current. They were last
   touched at step N-1's `moveThing` (CPU) or `GPUMoveThing.moveThings` (GPU);
   nothing between then and step() at step N mutates them.
3. Derived fields (`soaEnd1` / `soaEnd2` / `soaZVec` / `soaTransXTox`) are
   current.
   - On CPU path: `Thing.moveThing()` → `initialize()` → `transMat` →
     `recomputeDerivedSoA(self)` for each Thing at the end of N-1.
   - On GPU path: `GPUMoveThing.moveThings` calls `Thing.recomputeDerivedSoA(0,
     tc)` (line 1458) after the unpack. For GPU-handled Things, this happens
     INSIDE `moveThings`. For CPU-fallback Things, their own `initialize()`
     does it.
4. `MyoMotor.fillSoaArrays` and `FilSegment.fillSoaArrays` have populated the
   slot-major SoA mirrors used by motor-binding and the GPU pack/unpack
   classification. These run unconditionally at the top of the step (line
   721–722).
5. All earlier-phase forces (Brownian, xLink, Arp23, NodeLink, joints) have
   ALREADY been deposited into the per-thread accumulators that step() will
   add to. The gatherForces phase that flattens them runs AFTER step(), so
   step() can rely on its per-thread writes being independently accumulated.

### Force dependencies inside step()

No force in step() depends on the output of another step() force at the same
timestep. They all read **derived geometry** (end1/end2/uVec from
recomputeDerivedSoA) and **parameters**, then write independently. The only
order-sensitivity is the axial-load accumulator (`incEnd*AxialForce`),
which is informational (consumed by biochemistry), not feedback.

### Silent-drop risk surface (Lessons 1 + 2 applied to step())

Each subclass's `step()` is a separate code path. The pattern that bit the
joints port was: short-circuit *one* subclass's CPU dispatch (Myosin) when
GPU takes over its forces, and silently drop a force that the kernel didn't
replicate (the anchor spring). **The same risk exists for every subclass
listed in Table A.** Specifically:

- If a future port short-circuits `FilSegment.step()` on the GPU path but the
  kernel doesn't replicate ALL of F1, F2, F3, F4, F5, F6 — silent drop.
- If a port handles F3/F4 (filament chain forces) on the GPU but
  short-circuits the entire FilSegment dispatch, then F1 (boundary collision)
  and F5/F6 (node tether) are silently dropped.
- The GPU-dropped-forces reduced CPU pass pattern (the `applyGPUDroppedForces`
  hook) is the established mitigation. Any port should follow the same shape:
  reduced CPU pass that runs the unported forces alongside the kernel.
- `Bug.step()`'s `setViscousDrag` mutates drag tensors, not forces. If the bug
  is GPU-handled and its CPU step() is skipped, the drag-tensor update
  silently doesn't happen — symptom: `bTransGam`/`bRotGam` go stale, then
  `moveThing` integrates with the wrong drag. Flag as a non-force silent-drop
  risk.

---

## Part 4 — Cost breakdown

### What we know from existing profiling

`RUN_LOGS/2026-05-30_dense98K_*_chained.txt` and the post-anchor-fix entry in
JOURNAL_ARCHIVE.md give per-phase wall time at M=98K dense (~98K motors,
thingCt≈588K, 1101 steps):

| Phase | CPU dense (s) | GPU dense (s) | Notes |
|---|---|---|---|
| **ThingStep Threads (= step+bio+resetCt aggregate timer)** | 53.1 | 21.2 → 26.29 (post anchor-fix) | this survey's target |
| ThingBrownian | 29.2 | 2.85 | bForces phase; GPU inline Wang hash |
| Myosin (per-Myosin joints CPU) | 13.4 | 0.88 → 8.22 (anchor-pass post-fix) | myoJoints1 |
| MyoDimer | 7.1 | 0.79 | myoJoints1 (always CPU) |
| Mesh | 6.4 | 4.97 | meshFils/Nodes/Motors |
| Ck Mots (CPU motor-fil collision) | 5.1 | 0.00 | motColl phase |
| MotorBindGrid3D Fill | 3.6 | 3.45 | motorBindGrid3D phase |
| gpuMotorBinding total | — | 12.4 | replaces Ck Mots |
| gpuMoveThing total (chained joints+move) | — | 43.4 | replaces moveThing + joints kernel |
| Wall | 148 | 128 (GPU 14% faster) | |

**Reading the numbers:** at M=98K on the GPU path, ThingStep (≈22–26 s over
1101 steps ≈ 20–24 ms/step) is the single biggest remaining CPU phase. That
is what step() corresponds to plus biochemStep + resetCounters — they share a
timer.

### What we do NOT know (this is the survey gap)

The existing profiling does not break down WHICH force inside step() dominates
the per-call cost. We don't know whether F3 (`addLinkForces`, with its
`moveCoeff` containing `fastAcos`+`Math.sin` and Pt3D allocations) dominates,
or F4 (`addTorsionSpringForces`, with `fastAcos` per pair), or F1/F2 (boundary
collisions per segment per step).

We also don't know how much of "ThingStep Threads" is `step()` itself vs
`biochemStep` (which is much heavier on poly/depoly steps) vs `resetCounters`
(should be cheap but is per-Thing).

### Suggested profiling pass before committing port order

Before deciding port order, instrument:
1. Split the aggregated `ThingStep` timer into separate `stepTimer` /
   `biochemTimer` / `resetCtTimer` — the timers already exist as named
   `RunTimer`s but the dense-run log lumps them. Confirm step()
   isn't being skewed by biochemistry.
2. Inside `FilSegment.step()`, add a 1-of-N (e.g., once per 1000 calls)
   per-force-method timer to estimate the F1/F3/F4/F5 split. Use the existing
   `RunTimer` pattern. The forces are well-isolated by method boundary so
   timing is cheap.
3. Re-run `glidingDense_demo_smoke` and `glidingScale400K` for the
   per-method breakdown.

### Expected ranking (hypothesis, to be verified)

- **F3 (addLinkForces)** — touches every linked segment pair every step,
  allocates Pt3D scratch via member fields, calls `fastAcos` inside
  `moveCoeff` twice per pair (once per endpoint of each segment in the
  pair). Likely the largest single force.
- **F4 (addTorsionSpringForces)** — same pair count as F3, simpler math
  (one `fastAcos` per pair, one cross + unit vector). Probably second.
- **F1 (checkBugOrBoxCollision)** — every segment every step (no
  `collisionCheckInt` gate as of current code, since the gate is
  commented out at line 458–463). Should be cheap (point-vs-box) but
  multiplied by segment count.
- **F5/F6 (addNodeForces)** — only segments with active formin/plasmid
  tethers. Population depends on parameter file. Likely small in
  gliding-assay configs.

This ranking is unverified; do the profiling pass before committing.

---

## Part 5 — Validation probes per force

### Coverage table

| Force | Direct probe today | Notes |
|---|---|---|
| F1 (filament–boundary inside chamber) | None direct. Gliding assay exercises the chamber boundary indirectly through filaments riding the floor. | Gap: no observable that scales monotonically with this force. |
| F2 (filament–bug outside, Listeria) | Listeria motility runs (`ListeriaWavyMotion`/similar) — bug velocity / actin pushing. Not exercised by gliding assay or benchmarks. | Gap if no Listeria observable is in the current validation rotation. |
| **F3 (filament chain link spring)** | **Deflection benchmark** (`-bmManual` / `-bmDiag`): 11-segment pinned chain, midpoint transverse force, measures static deflection ratio obs/exp. The link spring is what propagates the midpoint force out to the pinned ends — broken/weakened/missing link forces show up as deflection ratio drift. **Relaxation-time benchmark**: τ_meas vs τ_theo from chain rebound. | Robust probe. Per Lesson 5, neither has run on the GPU path yet. |
| **F4 (filament chain torsion spring / bending rigidity)** | Same: **deflection benchmark** + **persistence-length (LP) benchmark** in `-bmManual` (free-end chain, EWMA on tangent correlation, target Lp = 15 µm). Torsion stiffness sets bending rigidity → Lp directly. | Robust probe. Per Lesson 5, neither has run on the GPU path yet — these are the must-run probes when F3/F4 move to GPU. |
| F5 (node tether spring) | Indirect via filament–formin nucleation behavior. No targeted probe. | **Gap.** Need a single-formin diagnostic (analogous to `SingleMyoDiag`) — one filament tethered at one end, measure end-position drift vs analytic. |
| F6 (node alignment torque) | None direct. | **Gap.** Same diagnostic could cover it (measure angular relaxation). |
| F7 (MyoMiniFilament–boundary collision) | Implicit in gliding-assay frame coverage. No quantitative probe. | Minor — same force shape as F1. |
| F8 (motor tip spring) | **Gliding assay** (bindEvents, meanBoundMotors, glidingVelocity) — this is the force the assay was designed for. | Strong probe, already in 10-seed ensemble protocol. |
| F9 / F10 (motor–filament alignment torques) | Gliding assay — these set how motors orient onto filaments. Indirect coverage. | Reasonable. |
| F11 (ActA tether) | Listeria motility runs. | Same gap as F2 — verify a Listeria observable is in current validation rotation. |
| F12 (ProteinNode–boundary collision) | None direct. | Minor. |

### Critical takeaway (re-stating Lesson 5)

When F3 and F4 move to the GPU — the heart of the step() port — the **deflection
benchmark and the LP benchmark are the probes that test them**, and they have
NEVER run on the GPU path. They must be runnable end-to-end on `-gpu` before
any chain-force kernel lands. If they don't run cleanly on the GPU path today
(e.g., because the benchmark setup paths don't classify benchmark Things as
GPU-handled), making them runnable is a prerequisite to the port, not
optional follow-up.

### Coverage gaps that need probes built BEFORE porting

1. **Node tether (F5/F6)** — build a `SingleFilNodeDiag` analogue: a single
   filament tethered to one fixed node, measure end-position drift, tangent
   alignment relaxation, and ensemble drift vs analytic.
2. **Boundary collisions (F1/F7/F12)** — a "wall-push" diagnostic: one filament
   pressed into a Chamber wall with a constant transverse force; verify the
   restoring drag balances at the expected indentation.
3. **ActA tether (F11)** — if Listeria isn't already in the current validation
   rotation, add a single-ActA diagnostic (bound filament, tether spring
   alone, no Arp/biochem).

The pattern is the SingleMyoDiag pattern — minimal isolated body, run
long, characterize.

### Cross-check against Table A

Stiff constraint × no direct probe (the highest-risk cell):

- F5 — stiff, no direct probe → build one before F5 ports.
- F6 — stiff, no direct probe → covered if F5 probe also exercises rotation.
- F8 — stiff, gliding-assay covers; OK to port.
- F9/F10 — stiff, gliding-assay covers indirectly; consider a single-motor-on-fixed-fil torque probe before porting.
- F11 — stiff, Listeria covers if Listeria is in rotation.

---

## Open questions for the planner

1. **Port order.** Forces F3 + F4 (filament chain) are the highest expected
   cost AND the best-validated (deflection + LP benchmarks). Recommend
   porting them first — high impact, lowest validation risk. After the
   profiling pass in Part 4 confirms the ranking.
2. **Reduced CPU pass shape.** Anchor-fix established the
   `applyGPUDroppedForces` hook pattern at the Myosin level. For FilSegment,
   we'd want an analogous reduced step() that runs F1/F2/F5/F6 (unported) and
   skips F3/F4 (GPU). Decide whether the reduced pass lives on a per-subclass
   hook or as a flag inside step() body.
3. **Device residency.** The 2026-06-01 bulk-memcpy entry concluded
   pack/unpack ISN'T the bottleneck; per-element parallel writes are
   bandwidth-bound at multi-core. Device residency (keep
   `soaCoord`/`soaUVec`/forces on-device across the joint→step→move chain)
   IS the lever — but it requires the step() port FIRST so the chain has all
   intermediate phases on-device. Planner should confirm this is the
   intended motivation.
4. **Benchmark runnability on `-gpu`.** Per Lesson 5, the deflection / LP /
   relaxation benchmarks need to be confirmed runnable on the GPU path
   before F3/F4 port. Verify the benchmark setup (pinned-endpoint chain,
   midpoint force injection at line 812–814 of BoxOfActin.java) survives the
   GPU classification — specifically, that midpoint segments get
   `incForceSum` applied AFTER the GPU pack and that pinned endpoint
   restoration works on top of the GPU integration result.
5. **Probe gaps to fill first.** Build SingleFilNodeDiag and a wall-push
   diagnostic before the F5/F6 ports, even if they're not the first phase.

---

## What this survey deliberately does not cover

- xLink / Arp23 / NodeLink / Joints forces (run before step()) — already
  surveyed implicitly by the JOURNAL.md history and the joints saga; out of
  scope here.
- The biochemStep() and moveThing() phases — different dispatches, different
  port considerations.
- Mesh / MotorBindGrid3D rebuild costs — relevant to xLink and motor-binding
  ports, not to step().
