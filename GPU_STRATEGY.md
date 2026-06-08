# GPU Acceleration Strategy for Actin/Myosin Simulation

> **Provenance.** This doc originated in the Sim3D teaching simulation as the
> running record of GPU experiments + the architectural strategy for the
> actin/myosin research code. Copied verbatim into the BoA repo on 2026-06-08
> as the BoA-resident copy that travels with the project (Sim3D's copy is left
> in place; updates from this point continue here). See `JOURNAL.md`
> 2026-06-08 for the move note.

This document records lessons learned from GPU work on the Sim3D teaching simulation
and the architectural strategy for GPU-accelerating the actin/myosin research code
(~807K monomers, thousands of myosin heads).

---

## Lessons from Sim3D

### What we measured

| Computation | Result | Bottleneck |
|---|---|---|
| GPU collision detection (M muttons × G gluttons) | **6× speedup** | Compute-bound |
| GPU physics kernel (Brownian + integration) | **1.6× speedup** | Transfer-bound |
| Box-Muller vs Irwin-Hall RNG | **No measurable difference** | Transfer-bound (GPU idle) |
| CPU spatial grid | Scales well to 1000 gluttons | — |

### The diagnostic result

The fact that replacing `sqrt/log/cos` with integer-only Irwin-Hall made no difference
is not a minor finding — it means the GPU was idle waiting for data, not busy computing.
No amount of kernel optimization can escape a transfer bottleneck.

The root cause is architectural: we built a physics kernel that round-trips position
data to the CPU every step. At 900K particles that is ~22 MB of position data per step
regardless of what the kernel does internally.

### Why collision detection worked but physics didn't

Collision detection gave 6× because the O(M×G) work ratio is high enough that compute
dominates even after paying the transfer cost. The physics kernel's compute-to-transfer
ratio was too low: the work per particle (a few force additions and an integration step)
does not justify uploading and downloading 22 MB.

The takeaway: **GPU acceleration pays when compute-to-transfer ratio is high.** The
physics kernel's ratio was marginal on Sim3D's particle count. With 807K monomers and
richer per-particle computation (elastic forces, myosin interactions) the ratio improves,
but only if the transfer problem is solved architecturally.

---

## The Fundamental Shift: GPU-Resident Simulation

### The round-trip problem

In Sim3D:
```
each step:  upload positions (22 MB) → run kernel → download positions (22 MB)
```
100 steps = 4.4 GB transferred. The GPU spends most of its time on the PCIe bus.

### The fix: persistent residency

Positions should never leave the GPU between output frames:

```
CPU: "run N steps"  (N = toFileInterval, e.g. 100)
GPU: runs 100 steps entirely on-device — positions never move
CPU: downloads positions once for output, then repeats
```

Transfer budget changes from `22 MB × 100 steps = 2.2 GB` to `22 MB × 1 output = 22 MB`
per 100 steps — a **100× reduction in transfer volume**. The GPU now earns its keep from
pure compute.

TornadoVM supports this: upload positions with `FIRST_EXECUTION`, build a separate
explicit download plan called only at output time, and execute the step plan N times
with zero position transfer between calls.

---

## Two-Plan Architecture

Build two `TornadoExecutionPlan`s backed by the same underlying arrays:

**stepPlan** — called every simulation step:
- Uploads: `count`, myosin states (small — kilobytes, not megabytes)
- Runs: force computation + position integration kernel
- Downloads: nothing

**outputPlan** — called every `toFileInterval` steps:
- Downloads: positions (and any other fields needed for visualization/analysis)
- No kernel execution

```
for each output frame:
    for i in 0..toFileInterval:
        stepPlan.execute()       // GPU-only, no transfer
    outputPlan.execute()         // one download per output frame
```

This makes the GPU a persistent simulation engine. The CPU interacts with it only at
output boundaries.

---

## Data Organization: Structure of Arrays

Use **Structure of Arrays (SoA)** layout, not interleaved:

```java
// SoA — correct for GPU
FloatArray xPos, yPos, zPos;    // separate arrays per coordinate
FloatArray xForce, yForce, zForce;

// AoS / interleaved — what Sim3D used, suboptimal
FloatArray pos;   // [m*3]=x, [m*3+1]=y, [m*3+2]=z
```

When threads `m` and `m+1` run simultaneously and both read `xPos`, they access
adjacent memory addresses (`xPos[m]` and `xPos[m+1]`) — coalesced memory access,
full bandwidth. With interleaved layout, adjacent threads read 12 bytes apart,
fragmenting memory transactions and cutting effective bandwidth by ~3×.

### Transfer modes by array lifetime

| Array | Transfer mode | Rationale |
|---|---|---|
| Positions (x, y, z) | `FIRST_EXECUTION` upload; explicit download only | GPU-resident; downloaded at output |
| Forces (x, y, z) | Zero on GPU at step start; never upload | Zeroed by GPU, no CPU involvement |
| Bond topology (neighbor indices) | `FIRST_EXECUTION` | Static; re-upload only on topology change |
| Monomer type / state flags | `FIRST_EXECUTION` | Rarely changes |
| Drag coefficients, radii | `FIRST_EXECUTION` | Constant within a run |
| Myosin states | `EVERY_EXECUTION` (small) | Changes each step |
| Count / step counter | `EVERY_EXECUTION` (tiny) | Step counter for RNG seeding |

Force zeroing: add a zeroing pass at the start of each kernel dispatch, entirely on GPU.
Eliminates the forceSum upload entirely (the pattern used in Sim3D was a workaround).

---

## What Moves to GPU

### 1. Force computation + position integration (full step kernel)

Elastic forces, crosslinker forces, Brownian motion, and overdamped Langevin integration
are all per-monomer and embarrassingly parallel. With 807K monomers and positions
GPU-resident, compute-to-transfer ratio is excellent. Fuse all force contributions into
one kernel pass to avoid intermediate force read-backs.

### 2. Myosin binding/unbinding geometry

This is the research code's equivalent of collision detection — and where the largest
speedup will come from.

- Each myosin head searches nearby actin monomers within binding radius
- With thousands of myosin heads × 807K actin, this is O(H×M) — the same structure
  as the Glutton×Mutton detection that gave 6×
- Both myosin and actin positions are already GPU-resident: **zero transfer cost**
- GPU-side Wang hash RNG handles stochastic bind/unbind decisions per thread

Start with brute-force GPU search (parallel over myosin heads, serial inner loop over
actin candidates in a spatial neighborhood). Profile before adding GPU spatial structure.

### 3. Filament elasticity (stencil computation)

Each monomer reads its own position plus 2 bonded neighbors, computes spring and bending
forces, writes force contributions. Bond topology (neighbor indices) is static — upload
once at `FIRST_EXECUTION` and reuse forever. Classic GPU stencil with good
compute/memory ratio.

### 4. Crosslinker forces

Each crosslinker reads two monomer positions and computes a restoring force. Fully
parallel over crosslinkers. Crosslinker endpoint indices upload as static topology.

---

## What Stays on CPU

### Topology changes

Filament polymerization/depolymerization and crosslinker binding events that alter the
bond graph itself require updating neighbor index arrays. These are rare events; paying
an occasional re-upload cost (via `invalidatePlan()`) is fine. The same pattern was
used in Sim3D when a Glutton grew and changed its radius.

### Myosin state machine (if complex)

If myosin heads have multi-state mechanochemical cycles (detached → pre-powerstroke →
post-powerstroke → rigor), the transition logic may be easier to audit and tune on CPU
initially. If states can be encoded cleanly as integers, moving them to GPU is
straightforward. Start on CPU; move to GPU only if it becomes a bottleneck.

### Output and analysis

Already occasional. Not a bottleneck.

---

## Spatial Grid on GPU: When and Why

The original Sim3D-vintage advice in this slot was "don't port the CPU spatial grid —
brute-force GPU search is fast enough." That advice was correct for Sim3D's scale and
single query type, and part of the underlying reasoning still holds: on CPU the grid
exists to compensate for transfer overhead by reducing per-step work, and with
persistent GPU residency the transfer-driven motivation disappears. For a single
proximity query at modest scale — motor-binding search at gliding-assay scale, thousands
of motors against tens of filament segments — brute force is the right starting point.

At BoA's target scale and entity diversity, the trade flips. Two reasons the Sim3D
analysis did not have to face:

1. **Asymptotic scaling.** At thousands of filaments and tens of thousands of motor
   heads, brute-force motor-binding is O(H × M) per step regardless of transfer cost.
   Persistent residency removes the bottleneck the CPU grid was paid to relieve, but it
   does not remove the O(H × M) wall. A spatial grid restores near-linear scaling and
   becomes necessary at the target workload.

2. **Surface geometry.** BoA's planned deformable-membrane subsystem (see "Deformable
   Membranes" below) introduces surface geometry — thousands of triangular faces, not
   sparse points or lines. Point/segment-vs-triangle tests cannot be brute-forced over
   the full mesh: a broad-phase to cull is mandatory for tractability, independent of
   any transfer argument.

The original Sim3D insight stands as a constraint on how the grid is built: a GPU grid
build is not the trivial CPU rebuild. It requires a parallel scatter — typically
count → prefix-sum → scatter, or a sort-by-cell pass. This is real complexity, to be
added when the asymptotic argument actually demands it, not casually as a default. The
first GPU kernel should still ship brute-force; the grid arrives in the second kernel
iteration, deliberately and with measurement in hand.

The grid prototyped on CPU as `MotorBindGrid3D` (BoA journal, 2026-05-27, "CPU rewrite
step 1a") is the staging ground for that GPU port — the algorithm debugged in a
tractable environment before TornadoVM's constraints (FloatArray, restricted control
flow, kernel boundaries) are layered on.

---

## Expected Payoffs

| Computation | Expected speedup | Reasoning |
|---|---|---|
| Myosin binding search | **10–30×** | O(H×M) like collision detection (6×); zero transfer with persistent residency |
| Filament elasticity + integration | **5–15×** | Stencil with static topology; persistent residency eliminates transfer |
| Full step (combined) | **10–20× wall time** | Dominated by above; output transfer amortized over N steps |

The actin/myosin simulation is a fundamentally better GPU target than Sim3D's physics
kernel for three reasons:

1. **Richer per-particle computation** — elastic forces, crosslinker forces, and myosin
   interactions give more compute per byte of position data than Sim3D's pure Brownian motion.

2. **Inherently O(H×M) dominant operation** — myosin binding search has the same
   structure as the collision detection that gave 6×, but with zero transfer cost since
   all data is GPU-resident.

3. **Persistent residency eliminates the bottleneck** — the architectural fix turns a
   transfer-bound 1.6× into a compute-bound 10–20×. This is the difference between
   incrementally optimizing a bad design and adopting the right design.

---

## Collision Architecture: Broad-Phase / Narrow-Phase

The collision/proximity stack is structured as two layers: an entity-agnostic
broad-phase that produces candidate co-occupancy pairs, and a small family of
entity-specific narrow-phase contact tests run only on those candidates. This is
conventional graphics/physics architecture, but with a BoA-specific twist on the
broad-phase shape and a tuning agenda driven by the GPU port.

### Entity-agnostic broad-phase

The broad-phase is a single 3D spatial grid into which all entity types — filament
segments, motor heads, membrane nodes and faces, future microtubules — scatter. It
operates on positions and bounding extents, not on Java class identities. A cell's
contents are array IDs and a tag for what kind of entity they refer to; the query
returns the union of co-occupants and lets the caller decide which pairings are
interesting. This matches the entity-dynamic schema principle already in use elsewhere
in the project: the grid does not know what the entities are, only where they are.

There is one grid serving all query types, not one grid per entity type. The current
CPU collision detection maintains three separate `Mesh` instances (FILSEG_MESH,
NODE_MESH, MYOHEADS_MESH); the GPU port consolidates these into a single tagged grid.
On-device memory is the binding constraint, and the per-type duplication buys nothing
once all entities share an address space.

This entity-agnostic shape is what the current GPU port is building. The 3D grid
prototyped as `MotorBindGrid3D` is the first concrete instance — single-type, single
query — and the second iteration generalizes it across entity types as the narrow-phase
catalog grows.

### Narrow-phase test inventory

Each (entity-type, entity-type) pair has a small focused contact-test kernel, run only
on the candidate pairs the broad-phase emits. Current and anticipated:

| Pair | Test | Status |
|---|---|---|
| motor head ↔ filament segment | point/sphere vs line segment | current — `checkFilSegCollision`, SoA-rewritten in step 1b |
| filament segment ↔ filament segment | line vs line | current — crosslinking, `checkToLink` |
| node ↔ node | Euclidean distance | current — `forceCollision` |
| filament segment ↔ membrane face | segment vs triangle | anticipated with membrane |
| motor / node ↔ membrane face | point/sphere vs triangle | anticipated with membrane |
| containment vs closed membrane | signed-distance or ray-cast / winding | anticipated with membrane |

The containment test is the one place where a "render-to-buffer / depth-based" GPU
technique — the classical collision-as-rendering approach — may be the right tool. It
suits closed surfaces and exploits the GPU's rasterization hardware. It is NOT
appropriate for the sparse line/point broad-phase, where the entity density is far too
low to amortize a depth-buffer pass over. Record it as a candidate technique scoped to
membrane containment specifically, not as a general broad-phase replacement.

### Tuning parameters

Three knobs are hardware-dependent and should be set empirically once the GPU kernel
runs. The first-kernel job is to ship something correct; the tuning agenda is what
comes next.

**Cell size.** The classical broad-phase trade: finer cells mean more build and memory
cost but fewer false candidates and less narrow-phase work. On CPU the optimum sits
coarser, because the build is expensive and the exact tests are tolerated; on GPU,
where parallel scatter makes the build cheap, the optimum slides finer to eliminate
exact tests. There is a second GPU-specific reason to favor finer grids: smaller
candidate sets per cell mean less warp divergence in the narrow-phase, since a warp
processes one cell's pairs more coherently. Floor: cell size should not drop below the
largest interaction range in play (capture radius, segment thickness, membrane face
size). Below that floor, segments paint into many cells and bookkeeping inflates
without removing candidates. With membranes present, the binding capture radius
(~6 nm) and the membrane face size (likely 10–100×) compete; a hierarchical or
two-level grid may be the right answer rather than a single global cell size.

**Staleness window.** BoA currently rebuilds the grid every `collisionCheckInt = 10`
steps and queries it every step against current positions, exploiting the fact that
motors and segments move only a small fraction of a cell per step. The correctness
condition is

> (max per-step displacement) × (staleness window) < (cell size × padding margin)

where the padding margin is the existing Bresenham OVERLAP ±1 band — i.e., no pair on
the verge of colliding may have been excluded from the candidate cells over the
staleness window. This window is currently synchronized with the biochemistry clock
(the polymerization dice roll fires every N steps with N = `collisionCheckInt`), so it
is not a free knob on CPU.

On GPU, if parallel grid-build is cheap, rebuilding every step may be cheaper than
maintaining and reasoning about the staleness invariant. The decision is open for the
GPU-port phase. Concrete prerequisite: measure max per-step displacement at the
densest target config and check the padding margin the current 10-step window leaves,
before deciding whether to lengthen, shorten, or eliminate the staleness window on GPU.

**Memory layout for coalescing.** Beyond the SoA requirement already covered, borrow
the graphics-world broad-phase optimizations: Morton / Z-order codes to keep spatially
near cells contiguous in memory (improves GPU coalescing when a thread gathers
neighboring cells), and sorting objects by cell ID so that a warp processes one cell's
contents coherently. These are post-first-kernel optimizations — list them as the
broad-phase optimization agenda, not first-pass work. Ship a correct, naive layout
first; reorder once profiling identifies the relevant traffic patterns.

### Relationship to persistent residency

This architecture sits inside the persistent-residency framework, not alongside it.
Positions stay on-device; the grid is rebuilt on-device by parallel scatter from the
resident position arrays; the narrow-phase runs on-device on the candidate pairs the
grid produced; only output frames download. The grid arrays are GPU-resident derived
state, recomputed from the resident position arrays each rebuild — never round-tripped
to CPU.

---

---

# Campaign landed state & scaling conclusions (2026-06)

> Append to GPU_STRATEGY.md. Durable conclusions from the device-residency campaign and the two
> measurement studies (retrospective sweep `bcc1c7c`, scaling study `0cbfa20`). For the chronological
> narrative and the open delta-cap item see the 2026-06-08 JOURNAL entry.

## What is resident now (gliding)

The canonical pose lives on the GPU across steps — FIRST_EXECUTION, device-owned, kernel-written —
and the host syncs only at output-frame cadence. Per-step pose transfer, which dominated the early
GPU path, is eliminated for gliding (`demandSyncPose` −99.7 %, gated on `Env.noMonomersSimd`). The
bind kernel reads the resident pose; move + bind run in one unified TaskGraph (which also routes
around the TornadoVM 4.0.1-dev multi-graph-executor OOM). Per-step uploads are now only genuinely-
per-step quantities (forces / uniforms) plus a small pose **delta** (changed slots) scattered into
the resident buffers by a kernel — not the whole pose.

## Throughput: a steady ~1.5×, not a widening lead

At production-relevant scale (≥1× ≈ 98K motors / 400 filaments) the GPU runs the gliding step
~1.5× faster than the CPU (GPU÷CPU ≈ 0.66–0.68×). This ratio is **flat across scale**, not
divergent: the GPU's fixed launch/transfer overhead amortizes from ~0.84× at quarter scale to the
~0.67× plateau by 1×, then holds through 4×. Both paths scale **near-linearly** in object count
(exponents ≈0.97–0.98). So the residency work bought a constant throughput multiplier across the
sustainable range; it did not buy a scaling-divergent advantage. (Numbers in BENCHMARK_dense.md →
Scaling study.)

Separately, the CPU itself is ~24 % faster than pre-campaign — but that gain is attributable to one
math-optimization commit (cpuopt batch 1, `61c2391`: pow/acos/sqrt, Env-cache, transpose-flatten),
**not** the residency campaign. There may be more cheap CPU hot-path headroom of the same kind,
independent of any GPU work.

## The real ceiling is the host object graph, not the GPU

Two empirical ceilings from the scaling study:

- **GPU ≈ 8×** — engine-side, not silicon. The per-step pose-delta dirty count overruns
  `POSE_DELTA_CAP`, forcing a per-step plan rebuild that leaks host memory → SIGKILL, with VRAM 98 %
  free. Fixable in software (in flight; see JOURNAL). Lifting it moves the GPU ceiling out to ~16×.
- **CPU ≈ 16×** — host-heap OOM constructing the OOP object graph (Mesh / Thing) at -Xmx28G against
  31 GB host RAM.

The decisive point: **the GPU run carries the same host object graph as the CPU run.** `-gpu` changes
the integration path, not the object model — every Thing / Mesh / FilSegment / MyoMotor is still
allocated on the JVM heap; residency mirrors only hot state onto the device. So both paths are bounded
at ~16× by host RAM, and device VRAM (roomy — hundreds of MB at 8×, far from the 12 GB wall) is never
the constraint at any reachable scale.

**Implication for v2.** Scale beyond ~16× is therefore not a tuning problem for this engine — it is an
object-model problem. It requires the host state to live in Structure-of-Arrays primitive arrays (and
resident on the device), not in per-object Java fields. This is exactly v2's data-oriented / ECS core,
and the scaling study is direct empirical justification for it: the OOP host graph, not the GPU, is
what caps BoA's reach. v2's SoA arrays should be treated as the canonical storage (the residency work
already proved out the device-resident half of this); the per-object "view" layer is convenience only.

## Remaining GPU levers (priority order)

1. **POSE_DELTA_CAP** — startup-sized cap + non-leaky fallback (in flight). Unlocks 8× → ~16×.
2. **High-churn biochem residency** — for production (high topology churn, unlike gliding): gate the
   pose pull to biochem-event cadence first (cheap), then move biochem's relative pose writes onto the
   device via scatterPose (fuller fix). Gliding already retired the pull; biochem-active still pulls.
3. **1-step bind-lag** in the single unified graph — A/B the bind-task position vs legacy; likely a
   task-ordering artifact, not a physics error.
4. **float32 binding (both paths)** — confine to the binding *decision*, leave double Langevin
   integration untouched; then calibrate to experiment.

Beyond these, the engine is host-RAM-bound and the next gain in *reach* is architectural (v2), not
incremental.

## Deformable Membranes (forthcoming subsystem)

A deformable membrane is a surface of force-connected, force-movable nodes with
tunable stiffness, representing biological boundaries — cell membrane, organelle
boundaries, vesicles, and similar surface structures. An existing prototype lives in a
separate code package and will be brought into BoA as a live research subsystem:
explored, improved, possibly rewritten against the literature, rather than deferred to
the v2 generalized-arena project.

The reason this subsystem is noted in the GPU strategy is architectural: membranes are
*surface* geometry, not points or chains. That is what drives the broad-phase to be
entity-agnostic and the narrow-phase to be extensible with surface contact tests
(segment-vs-triangle, point-vs-triangle, containment). Designing the broad-phase now
with membranes anticipated means not baking motor/filament-specific assumptions into
the grid's contents or query shape.

The hard part of the membrane work is the mechanics, not the collision detection.
Stiff membranes create fast force modes, which push toward small timesteps or implicit
integration; bending resistance needs a dihedral or comparable second-order term, not
just node-node springs; area and volume conservation and self-intersection avoidance
are nontrivial. The collision-detection contribution from the GPU port is bounded —
extending the existing broad-phase with surface contact tests — and should not be
confused with the membrane's main robustness challenge.

A mature BoA membrane is a natural future contribution to the v2 project as a
parameterized boundary primitive, but development of it happens in BoA and is not
gated on v2.
