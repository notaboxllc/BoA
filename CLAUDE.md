# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

**BoxOfActin** — a Java biophysics simulation of actin filament network dynamics in cells. Simulates filaments, myosin motors, crosslinkers, Arp2/3-branched networks, membrane nodes, and Listeria motility using overdamped Langevin dynamics with Brownian motion.

## Build and Run

Requires Java and Java3D. The Eclipse project classpath expects jars at `/Library/JOGLAndj3D/` (`vecmath.jar`, `j3dutils.jar`, `j3dcore.jar`, `jogamp-fat.jar`).

**Compile** (from project root, all source is in the default package and `boxOfActin/`):
```
javac BoxOfActin.java
```
The Eclipse builder handles incremental compilation.

**Run** (large heap required):
```
java -Xmx800M BoxOfActin
java -Xmx800M BoxOfActin -r                          # headless/remote, no graphics
java -Xmx800M BoxOfActin -pf ParameterFiles/boa10-64Seg   # load parameter file
java -Xmx800M BoxOfActin -ic myState.qk              # resume from saved state
java -Xmx800M BoxOfActin -o myOutputDir              # save logs, QK frames, source
java -Xmx800M BoxOfActin -qk myQKDir -qkN 100        # QK snapshot output every 100 steps
java -Xmx800M BoxOfActin -help                       # full option list
```

## Architecture

### Core simulation classes

- **`boxOfActin/BoxOfActin.java`** — entry point (`begin()`), main simulation loop (`doLoop()`), and per-timestep orchestration. One `TimeLoop` thread drives everything.
- **`boxOfActin/Env.java`** — global singleton with all physical constants, simulation parameters (`Parameter` instances), thread counts, and timestep-phase integer constants (`meshFilsStart`, `stepStart`, etc.).
- **`boxOfActin/Thing.java`** — abstract superclass for every simulated object. Holds position, orientation, forces/torques, drag/diffusion tensors, Java3D graphics nodes, and Brownian force calculation. All simulated objects live in the static `theThings[]` array.

### Simulated objects (all extend `Thing`)

| Class | Role |
|---|---|
| `FilSegment` | Actin filament segment; filaments are chains of these |
| `ProteinNode` | Generic protein/membrane node |
| `StickyNode` | Membrane node that can tether to filaments |
| `Myosin` / `MyosinDimer` / `MyoMiniFilament` | Myosin motor types |
| `MyoMotor` / `MyoLever` / `MyoRod` | Subparts of a myosin molecule |
| `Bug` | Listeria bacterium geometry for motility simulations |
| `Crucible` / `Chamber` | Simulation boundary/container |

### Connectors (not `Thing` subclasses)

- **`FilLink`** — crosslinks between filament segments (including Arp2/3 branches)
- **`NodeLink`** — links between membrane nodes
- **`Arp23`** — Arp2/3 complex anchoring branch junctions
- **`ActA`** — ActA protein on Listeria surface

### Multithreading

**`ThreadSet`** is the worker-pool abstraction. Each biological operation (mesh fill, collision detection, Brownian forces, crosslinks, `step()`, `moveThing()`, `biochemStep()`, etc.) has its own `ThreadSet` subclass. `BoxOfActin.doLoop()` calls `startAllThreadSets(phaseId)` / `waitOnAllThreadSets(phaseId)` pairs to fan out and collect work. The `phaseId` constants are defined in `Env` (e.g., `Env.stepStart`, `Env.stepStop`).

### Simulation loop order (one timestep)

1. Mesh update — bin filament segments, nodes, and motor heads into spatial grid (`Mesh`)
2. Motor–filament collision detection
3. Brownian forces (`calcRandomForces()` on all Things)
4. Crosslinkers, Arp2/3, and ActA tethers
5. Membrane node links
6. Myosin joint forces (two passes)
7. `Thing.step()` — integrate equations of motion (forces → velocities)
8. `Thing.moveThing()` — update positions/orientations
9. `Thing.biochemStep()` — stochastic biochemistry (binding/unbinding)
10. Membrane relaxation loop
11. Cleanup — remove dead Things, compact arrays
12. Spawn new filaments/nodes as needed

### Parameters

`Parameter` objects in `Env` represent all tuneable values. They carry a string label used for serialization, enabling save/load of parameter configurations via `FileOps.loadParamConfig()` / `FileOps.remoteParamConfigSave()`. Sample parameter files are in `ParameterFiles/`.

### Output

- **Log files** — time-series data written by `FileOps.writeToOutFile()`
- **QK files** — binary quickstate snapshots (save/resume full simulation state)
- **JSON output** — `FileOps.writeSimJSonsFrame()` for rendering/analysis
- **Image capture** — `CapturingCanvas3D` grabs Java3D frames to disk

### Bundled libraries

- `ec/util/` — `MersenneTwister` / `MersenneTwisterFast` PRNG
- `edu/cornell/lassp/houle/RngPack/` — `RanMT` and related RNG classes
- `infoCCD/` — GUI utilities (`InfoViewer`, `ParamLoader`, `Console`) for the parameter browser and help system

---

## GPU Acceleration Strategy

Lessons from GPU experiments on Sim3D (see `~/Dropbox/CodeSync/Sim3D/GPU_STRATEGY.md`) combined with analysis of this codebase.

### Lessons from Sim3D

- GPU wins when compute-to-transfer ratio is high.
- The critical insight: keep positions GPU-resident between output frames — only download once per `toFileInterval` steps, not every step. This reduces transfer volume 100×.
- **Two-plan architecture:** `stepPlan` (no download, runs N times) + `outputPlan` (downloads positions, runs once per output frame).
- Use **Structure of Arrays (SoA)** layout: separate `FloatArray xPos[], yPos[], zPos[]` rather than interleaved x,y,z per object. Adjacent GPU threads read adjacent memory = coalesced access = much faster.
- Static arrays (drag coefficients, bond topology) upload once at `FIRST_EXECUTION`. Only positions and forces need `EVERY_EXECUTION`.

### What the simulation actually contains

- **~25K FilSegments** (not 807K monomers — monomers don't move independently).
- Each FilSegment has **6 DOF**: `coord` (Pt3D, 3 translational) + orientation frame (`uVec`, `yVec`, `zVec`, 3 rotational).
- Monomers track biochemical state only (`nucleotideState`, `cofilinOn`, etc.); their 3D positions are computed from the FilSegment body frame at output time only, in `updateAllMonomerPositions()`.
- The SoA buffer for all FilSegment mechanical state (position, orientation frame, drag tensors, force accumulators) is a few MB total — very manageable.
- The `boa10-64Seg` parameter file starts with 1,000 initial filaments. Large-scale runs reach ~25K segments (807K monomers ÷ 32 monomers/segment).
- Monomers can be disabled entirely with `noMonomersSimd` for pure-mechanics runs.

### Simulation loop phases and GPU suitability

Phases in priority order, using the `Env` phase-constant names from `doLoop()`.

**`motCollStart/Stop` — motor-filament search**
GPU fit: **Best.** O(motors × segments), same structure as the collision detection that gave 6× speedup in Sim3D. With positions GPU-resident this is zero-transfer-cost pure compute. Target: 10–30× speedup.

**`bForcesStart/Stop` — Brownian forces**
GPU fit: **Excellent.** Embarrassingly parallel per FilSegment. With persistent residency becomes compute-bound. Must fuse with `moveStart` into one kernel.

**`moveStart/Stop` — `moveThing()` integration**
GPU fit: **Excellent.** Per-segment 6-DOF overdamped Langevin integration (forces → body-fixed velocities → position + orientation frame update). Must fuse with `bForcesStart`. These two phases **must move together** — they collectively own the position update.

**`stepStart/Stop` — `FilSegment.step()`**
GPU fit: **Mixed.** This phase does three things with different GPU profiles:
- `checkBugOrBoxCollision()`: good — per-segment endpoint test against container, no write conflicts, can fuse into the step-1 kernel.
- `addLinkForces()`: harder — pairwise Lagrange multiplier between end-linked segment pairs. Segment A reads segment B's state and writes forces to both A and B. Two threads can simultaneously process the same A–B link before either sets the visitor flag (`end2LinkCkd`), causing double-counted forces. On CPU this is masked by per-object `synchronized (forceSync)` monitors; on GPU those monitors don't exist.
- `addTorsionSpringForces()`: same bidirectional write hazard as link forces.

Recommended approach: port bounds collision first; for link and torsion forces use one of:
- **Ownership assignment + atomics**: the lower-index segment always owns each link and applies forces to both ends using `atomicAdd`. At ~25K segments each force accumulator is written by at most 2–3 neighbours, so contention is negligible. Simpler to implement than graph coloring.
- **Graph coloring**: colour the end-link graph (max degree 2, so at most 3 colours needed — paths and even cycles need 2, odd cycles need 3). Each colour-pass kernel is conflict-free with no atomics needed. More setup but zero atomic overhead.

Note: the CPU ThreadSet approach (visitor flags + per-object synchronized monitors) is *not* formally race-free — it relies on low probability of simultaneous processing of the same pair. Do not carry this approach to GPU.

**`xLinkStart/Stop` — crosslinker / Arp2/3 / ActA forces**
GPU fit: **Good** for force computation. Each `FilLink` reads two segment positions and writes restoring forces to both. Same bidirectional write hazard as link forces — use ownership assignment or graph coloring. Endpoint index pairs upload as static topology; re-upload via `invalidatePlan()` only on binding/unbinding events.

**`meshFilsStart/Stop` — spatial grid rebuild**
GPU fit: **Skip.** Building a GPU spatial hash requires atomic scatter or sort — significant complexity. Brute-force GPU motor-filament search (O(H×S)) may be fast enough without a grid. Profile before adding GPU spatial structure.

**`biochemStart/Stop` — polymerization / depolymerization**
GPU fit: **CPU only.** Per-monomer biochemistry is a pointer-chasing linked-list walk (`frontMon` / `backMon`), highly conditional (ATP→ADP-Pi→ADP state machine, cofilin/tropomyosin competition spanning 7 monomers). Not GPU-suitable.

**`membraneLinksStart/Stop` — membrane relaxation**
GPU fit: **CPU only.** Iterative loop with convergence check on `NodeLink.maxStrain`; CPU must read `maxStrain` each iteration to decide whether to continue. Leave on CPU.

### Write-write hazard: how the CPU solves it and what GPU needs

`addLinkForces()` and `addTorsionSpringForces()` both use the visitor-flag pattern: whichever thread processes a linked pair first marks both segments' flags (`end2LinkCkd`, `end1LinkCkd`, etc.) and applies forces to both. The CPU backs this with `synchronized (forceSync)` / `synchronized (torqueSync)` per-object monitors in `Thing.incForceSum()` / `Thing.incTorqueSum()` — preventing memory corruption but not formally preventing double-counting (the flag read/write is unsynchronized).

GPU translation:
- Per-object Java monitors → `atomicAdd` on force accumulator components.
- Visitor flags → static ownership: lower array-index segment owns each link; the owner's kernel applies both sides; the partner's kernel skips that link. No flag state needed.
- Graph coloring is the alternative: assign colours to segments such that no two same-colour segments share a link, then run one kernel pass per colour. Conflict-free, no atomics. At most 3 passes for the end-link graph.

### Implementation sequence

**Step 0 — SoA shadow arrays (no GPU yet)**

Create `FloatArray xPos, yPos, zPos, xUVec, yUVec, zUVec, xYVec, yYVec, zYVec` etc. for FilSegments alongside the existing `Thing[]` structure. Write sync utilities:
- Upload sync: `FilSegment.theFilSegments[i].coord.x → xPos[i]` (called once at startup and after topology changes)
- Download sync: `xPos[i] → FilSegment.theFilSegments[i].coord.x` (called at output time)

No simulation logic changes. Validate in isolation that a sync round-trip is lossless. This is the enabling infrastructure for all subsequent steps.

**Step 1 — Fused Brownian + integration kernel (`bForcesStart` + `moveStart`)**

Minimum viable persistent residency: positions go up at `FIRST_EXECUTION`, kernel runs N times per output frame, positions come down once via `outputPlan`. Fuse bounds collision (`checkBugOrBoxCollision`) into this kernel while at it — it has no write conflicts.

Validate by comparing GPU and CPU trajectories (same `deltaT`, same initial conditions, same RNG seed). This is the architectural fix that converts the Sim3D 1.6× transfer-bound result into a compute-bound speedup.

**Step 2 — Motor-filament search (`motCollStart`)**

Once positions are GPU-resident this is zero-transfer-cost O(H×S). Start with a brute-force kernel (parallel over motor heads, serial inner loop over nearby segments). Profile before adding GPU spatial structure. Expected 10–30× for this phase alone.

**Step 3 — Bounds collision + crosslinker / link / torsion forces**

Bounds collision is already clean (no write conflicts) — fuse into the Step 1 kernel.

For crosslinker, end-link, and torsion spring forces: implement ownership assignment (lower-index owns each link) with `atomicAdd` on force components. At ~25K segments with ≤ 3 writers per accumulator, atomic contention is negligible. Re-upload bond topology via `invalidatePlan()` after polymerization events.

### TornadoVM specifics (from Sim3D experience)

- Compile with `-g` flag — required for PTX local variable tables; TornadoVM PTX compiler reads these from bytecode. Without `-g` the kernel will not compile.
- Run with `@tornado-argfile` — loads all Graal/JVMCI flags.
- Use `--enable-preview` — `FloatArray` uses Java 21 preview features.
- Use `FloatArray` / `IntArray` only inside kernels — no Java primitive arrays.
- `@Parallel` annotation on the outer loop is sufficient for embarrassingly parallel kernels.
- `FIRST_EXECUTION` vs `EVERY_EXECUTION` transfer modes are the primary performance lever.
- `invalidatePlan()` pattern: when topology changes (poly/depoly, crosslinker bind/unbind), close and rebuild the execution plan so `FIRST_EXECUTION` arrays get re-uploaded with fresh topology.

### Java3D removal (prerequisite)

BoA currently depends on Java3D for graphics. This must be removed (or fully isolated behind the `-r` headless flag) before TornadoVM can be integrated — TornadoVM requires Java 21 and Java3D does not support it. Follow the approach used in Sim3D: remove Java3D imports, replace graphics output with a JSON / Three.js rendering system. The `-r` flag already suppresses all graphics calls; the remaining blocker is that Java3D classes are referenced in field declarations (e.g., `BranchGroup G`, `TransformGroup g3d` in `Thing.java`) which prevent the JVM from loading even in headless mode.
