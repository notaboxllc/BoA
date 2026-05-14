# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

**BoxOfActin** — a Java biophysics simulation of actin filament network dynamics in cells. Simulates filaments, myosin motors, crosslinkers, Arp2/3-branched networks, membrane nodes, and Listeria motility using overdamped Langevin dynamics with Brownian motion.

As of Phase 4 (commit `dd4314f`), Java3D has been fully removed from the codebase. The simulation no longer requires `vecmath.jar`, `j3dcore.jar`, `j3dutils.jar`, or `jogamp-fat.jar` on the classpath. Visualization is now handled out-of-process by a Three.js browser viewer fed by per-frame JSON files written from the simulation (`ThreeJSWriter`).

## Build and Run

### Build (current, post-Phase 4)

The codebase compiles with stock Java — no Java3D, no JOGLAndj3D classpath. Source lives in the default package (top-level `BoxOfActin.java`) and the `boxOfActin/` subpackage, plus bundled libraries in `ec/util/`, `edu/cornell/lassp/houle/RngPack/`, and `infoCCD/`.

The WebSocket server (`-3jsLive`) requires two jars in `libs/`. The `*.jar` rule in `.gitignore` excludes them from the repository — download them on a fresh checkout:

```
mkdir -p libs
curl -L -o libs/Java-WebSocket-1.5.7.jar \
  https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.7/Java-WebSocket-1.5.7.jar
curl -L -o libs/slf4j-api-2.0.6.jar \
  https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.6/slf4j-api-2.0.6.jar
```

- `libs/Java-WebSocket-1.5.7.jar` — org.java-websocket WebSocket server/client (140 KB)
- `libs/slf4j-api-2.0.6.jar` — SLF4J API required by java-websocket (63 KB); prints a harmless NOP warning to stderr at startup if no SLF4J provider is on the classpath

```
javac -XDignore.symbol.file -cp ".:libs/Java-WebSocket-1.5.7.jar:libs/slf4j-api-2.0.6.jar" boxOfActin/*.java *.java
```

`-XDignore.symbol.file` suppresses warnings about internal-API usage from the bundled libraries.

If you are not using `-3jsLive`, you can still compile and run without the jars on the classpath (the server class is loaded lazily). But the simplest practice is to always include them.

### Build (post-Phase 5, once Java 21 is installed)

```
javac --release 21 --enable-preview -XDignore.symbol.file -cp ".:libs/Java-WebSocket-1.5.7.jar:libs/slf4j-api-2.0.6.jar" boxOfActin/*.java *.java
```

Phase 5 has not yet been performed. The MBP needs `brew install openjdk@21` before this command will work. Once Java 21 is installed and the codebase is validated under it, this becomes the canonical build command and `--enable-preview` is required for TornadoVM compatibility (TornadoVM's `FloatArray` is a Java 21 preview feature).

### Run

```
java -Xmx800M -cp ".:libs/Java-WebSocket-1.5.7.jar:libs/slf4j-api-2.0.6.jar" BoxOfActin
java -Xmx800M -cp ".:libs/*" BoxOfActin -r
java -Xmx800M -cp ".:libs/*" BoxOfActin -pf ParameterFiles/boa10-64Seg
java -Xmx800M -cp ".:libs/*" BoxOfActin -3js myThreeJSDir
java -Xmx800M -cp ".:libs/*" BoxOfActin -3jsLive 8081          # live WebSocket streaming
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -pf ParameterFiles/boa10-64Seg -3js ~/Desktop/run1 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -help                  # full option list
```

The full command-line option list, as of Session 12:

| flag | effect |
|---|---|
| `-r` | run remotely / headless, no graphics output (no longer means anything in practice — there is no GUI mode — but the flag is retained because it gates some bookkeeping) |
| `-pf <file>` (also `-paramfile`, `-paramFile`) | load parameter values from file |
| `-o <dir>` (also `-out`, `-outfile`) | create output directory, save logs and copy of source |
| `-outMade <dir>` | like `-out` but assumes directory already exists |
| `-lf <dir>` (also `-logfile`, `-logFile`) | save log files to this directory |
| `-biochem` | run without collisions, forces, or Brownian motion |
| `-3js <dir>` | write Three.js per-frame JSON files; directory auto-increments `.001` suffix if it exists |
| `-3jsLive <port>` | start WebSocket server on the given port; viewer connects via `?live=<port>` |
| `-oc` | ordered filaments (in a biochem-only run) are centered |

Both `-3js` and `-3jsLive` can be given together. Frame JSON is generated once and dispatched to both consumers.

The QK (quickstate) save/resume flags (`-qk`, `-qkN`, `-ic`) were removed in Phase 3 along with the QK serialization code in `FileOps.java`.

### Viewing Three.js output

**File-based (existing):** In the directory containing the `-3js` output folder:

```
python3 sim_server.py 8000
```

Then open `http://localhost:8000/sim_viewer_boa.html`. The viewer auto-discovers run directories via `/api/simulations`.

**Live WebSocket (Session 12):** Start the sim with `-3jsLive <port>` (e.g. `8081`), then open the viewer with:

```
http://localhost:8000/sim_viewer_boa.html?live=8081
```

The viewer connects to `ws://localhost:8081`, displays incoming frames as they are generated, and reconnects automatically if the simulation restarts. Connection status (connected / reconnecting / disconnected) is shown in the top bar.

## WebSocket Protocol (Session 12 — C1)

Implemented in `boxOfActin/LiveFrameServer.java` using the `org.java-websocket` library.

### Message shapes

**Server → client:**
```json
{"topic": "frame", "payload": { ...per-frame JSON... }}
```

**Client → server (C1 only):**
```json
{"action": "subscribe", "topics": ["frame"]}
```

The `topic` / `action` discriminators are defined so future sessions can extend the protocol:
- **C2 (click-to-inspect):** adds `inspectResult` topic and `inspect` action
- **C3 (pause/resume):** adds `simState` topic and `pause` / `resume` / `kill` actions

The server defaults to subscribing all clients to all topics on connect, so `subscribe` is optional but good practice.

### Backpressure

Each client has a bounded `ArrayBlockingQueue<String>(4)` and a per-client daemon sender thread. `dispatchFrame()` uses non-blocking `offer()` — if the queue is full it drops the oldest frame and inserts the newest. The simulation's `dispatchFrame()` call is always O(1) and never blocks, regardless of network conditions.

## Architecture

### Entry point

There is a top-level `BoxOfActin.java` (default package) and a `boxOfActin.BoxOfActin` class. The default-package version is the entry point; its `main()` calls `boxOfActin.BoxOfActin.begin(args)`. The `main()` inside `boxOfActin.BoxOfActin` is currently commented out. This is brittle and worth tidying eventually, but it works.

### Core simulation classes

- **`boxOfActin/BoxOfActin.java`** — `begin()` parses args and sets up the simulation; `doLoop()` is the per-timestep orchestration. One `TimeLoop` thread drives everything. After Phase 3, this file no longer contains graphics orchestration, QK code, or the Swing timer.
- **`boxOfActin/Env.java`** — global singleton with all physical constants, `Parameter` instances, thread counts, and timestep-phase integer constants (`meshFilsStart`, `stepStart`, etc.). Still has a few legacy graphics-flag fields (`paintOn`, `viewRotation`, etc.) that are harmless leftovers; they don't pull in Java3D.
- **`boxOfActin/Thing.java`** — abstract superclass for every simulated object. Holds position, orientation, forces/torques, drag/diffusion tensors, and Brownian force calculation. All simulated objects live in the static `theThings[]` array. After Phase 1, this class has no Java3D fields. `drawYourself(Graphics, double, double[])` is an empty AWT-typed stub retained as the rendering hook for future use.
- **`boxOfActin/Pt3D.java`** — 3D point / vector. After Phase 0, this is a standalone class with explicit `public double x, y, z;` fields and ~600 lines of pure-Java math (cross, dot, unit-vector, body-frame transforms via `Thing.transXTox` / `Thing.transxToX`, etc.). It is no longer `extends javax.vecmath.Point3d`.

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

**`ThreadSet`** is the worker-pool abstraction. Each biological operation (mesh fill, collision detection, Brownian forces, crosslinks, `step()`, `moveThing()`, `biochemStep()`, etc.) has its own `ThreadSet` subclass. `BoxOfActin.doLoop()` calls `startAllThreadSets(phaseId)` / `waitOnAllThreadSets(phaseId)` pairs to fan out and collect work. The `phaseId` constants are defined in `Env`.

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

Two-field model:
- **isActive()** — whether the parameter is applied. If `false`, the value falls back to the Java default regardless of what's in the file.
- **getValue()** — the actual value. For booleans, `1.0` = true, `0.0` = false.

`bugOff` does NOT control bug creation — to suppress Listeria, set `simOutsideBug:false:0.0`.

### Output

- **Log files** — time-series data written by `FileOps.writeToOutFile()`
- **Three.js JSON frames** — `ThreeJSWriter.writeFrame()` writes `frame_NNNNNN.json` files in a Three.js-renderable format. Includes `segments`, `myosins` (rod/lever/motor parts with nucleotide-state), and `minifilaments` arrays. Source code is zipped to `source.zip` in the output directory on first write.
- **Simularium JSON** — `FileOps.writeSimJSonsFrame()` and `writeSimJSonsFrame2()` produce coarse/fine Simularium-format JSON for the Simularium Viewer (separate ecosystem from the Three.js viewer).
- **QK files** — REMOVED in Phase 3. No longer supported.
- **Image capture** — REMOVED. Was Java3D-dependent; replaced by Three.js viewer.

### Bundled libraries

- `ec/util/` — `MersenneTwister` / `MersenneTwisterFast` PRNG
- `edu/cornell/lassp/houle/RngPack/` — `RanMT` and related RNG classes
- `infoCCD/` — `Info` and `ResourceGetter` (a small remnant; most of this package's GUI utilities were removed with Phase 4)

---

## GPU Acceleration Strategy

Lessons from GPU experiments on Sim3D (see `~/Dropbox/CodeSync/Sim3D/GPU_STRATEGY.md`) combined with analysis of this codebase. Java3D removal (Sessions 3–8) cleared the Java 21 prerequisite for TornadoVM, so this plan is now executable as soon as Phase 5 validates.

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

### Phase 5 (Java 21) — the only remaining prerequisite

Java3D removal is complete (Sessions 3–8). The only remaining blocker to TornadoVM is `brew install openjdk@21` on the MBP, followed by a clean compile/run under Java 21 with `--enable-preview --release 21`. After that, GPU work can begin on aorus (Linux GPU machine with NVIDIA driver 595.58.03, CUDA 13.2, TornadoVM 4.0.1-dev PTX backend installed).

## Biological Context

- **Actin filaments**: ~8 nm radius, modeled as rigid rods (FilSegment chains)
- **Myosin II structure**: rod (~200nm) → lever/neck (~8nm) → motor head (~20nm). Each Myosin object has MyoRod, MyoLever, MyoMotor sub-objects with end1/end2
- **MyoMiniFilament**: bundles multiple Myosin dimers; its own end1/end2 spans the full structure. Individual MyoRod objects inside have `rodInvisible=true`
- **Motor nucleotide cycle**: NONE→ATP(unbound)→ADPPi(cocked)→ADP(power stroke)→NONE
- **Simulation modes** (set in parameter files via `makeCrucible()`):
  - Box of actin: `Chamber.makeABox()`, no bug
  - Pill-shaped arena: `Bug.makeABugCrucible()` as `theBox`
  - Listeria motility: Chamber + `Bug.makeListeriaBug()` with ActA proteins

## Parameter File Format

```
paramName:isActive:value;   // isActive=true/false, value=1.0/0.0 for booleans
// If isActive=false, parameter falls back to Java default regardless of value
// bugOff does NOT control bug creation — use simOutsideBug:false:0.0
```
