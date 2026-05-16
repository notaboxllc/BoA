# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

**BoxOfActin** — a Java biophysics simulation of actin filament network dynamics in cells. Simulates filaments, myosin motors, crosslinkers, Arp2/3-branched networks, membrane nodes, and Listeria motility using overdamped Langevin dynamics with Brownian motion.

As of Phase 4 (commit `dd4314f`), Java3D has been fully removed from the codebase. The simulation no longer requires `vecmath.jar`, `j3dcore.jar`, `j3dutils.jar`, or `jogamp-fat.jar` on the classpath. Visualization is now handled out-of-process by a Three.js browser viewer fed by per-frame JSON files written from the simulation (`ThreeJSWriter`).

## Build and Run

### Build (current, post-Phase 4)

The codebase compiles with stock Java — no Java3D, no JOGLAndj3D classpath. Source lives in the default package (top-level `BoxOfActin.java`) and the `boxOfActin/` subpackage, plus bundled libraries in `ec/util/`, `edu/cornell/lassp/houle/RngPack/`, and `infoCCD/`.

The WebSocket server (`-3jsLive`) requires three jars in `libs/`. The `*.jar` rule in `.gitignore` excludes them from the repository — download them on a fresh checkout:

```
mkdir -p libs
curl -L -o libs/Java-WebSocket-1.5.7.jar \
  https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.7/Java-WebSocket-1.5.7.jar
curl -L -o libs/slf4j-api-2.0.6.jar \
  https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.6/slf4j-api-2.0.6.jar
curl -L -o libs/json-20231013.jar \
  https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
```

- `libs/Java-WebSocket-1.5.7.jar` — org.java-websocket WebSocket server/client (140 KB)
- `libs/slf4j-api-2.0.6.jar` — SLF4J API required by java-websocket (63 KB); prints a harmless NOP warning to stderr at startup if no SLF4J provider is on the classpath
- `libs/json-20231013.jar` — org.json JSON parser used for C4 setParam/queryParams actions (75 KB)

```
javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java
```

`-XDignore.symbol.file` suppresses warnings about internal-API usage from the bundled libraries. The `libs/*` glob picks up all three jars.

If you are not using `-3jsLive`, you can still compile and run without the jars on the classpath (the server class is loaded lazily). But the simplest practice is to always include them.

### Build (post-Phase 5, once Java 21 is installed)

```
javac --release 21 --enable-preview -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java
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

## WebSocket Protocol (Sessions 12–18 — C1 + C2 + C3 + C4)

Implemented in `boxOfActin/LiveFrameServer.java` using the `org.java-websocket` library.

### Message shapes

**Server → client:**
```json
{"topic": "frame",         "payload": { ...per-frame JSON... }}
{"topic": "inspectResult", "payload": { ...inspection JSON... }}
{"topic": "simState",      "payload": {"state": "running"|"paused"|"terminating", "step": <N>}}
{"topic": "paramList",     "payload": [{"name":"<label>","displayName":"<name>","type":"double"|"int"|"boolean","value":<v>,"mutable":<bool>}, ...]}
{"topic": "paramAck",      "payload": {"success":true,  "name":"<label>","oldValue":<v>,"newValue":<v>}}
{"topic": "paramAck",      "payload": {"success":false, "name":"<label>","error":"<reason>"}}
```

**Client → server:**
```json
{"action": "subscribe", "topics": ["frame", "inspectResult", "simState", "paramAck", "paramList"]}
{"action": "inspect",   "id": <thingInstanceId>}
{"action": "pause"}
{"action": "resume"}
{"action": "kill"}
{"action": "queryParams"}
{"action": "setParam", "name": "<label>", "value": "<stringValue>"}
```

The server pushes all topics to all clients; `subscribe` is informational only (no per-topic filtering on the server side). Unknown actions are logged and discarded.

**setParam validation** (on WebSocket thread, before safe point):
- Unknown label → immediate `paramAck` error: `"unknown parameter"`.
- Known but not `mutableAtRuntime` → immediate error: `"not mid-run mutable"`.
- Parse failure → immediate error: `"parse error: <details>"`.
- Valid → queued in `Env.paramQueue`; success ack dispatched at next safe point.

**Orientation key consistency:** The `frame` and `inspectResult` payloads represent segment geometry differently by design: `frame` uses `end1`/`end2` endpoint pairs (the viewer derives orientation from them for rendering); `inspectResult` uses an explicit `{ux, uy, uz}` long-axis unit vector (for the inspection text readout). These are complementary, not redundant — do not "unify" them.

### C3: pause / resume / kill

`simState` is pushed to all clients on every state transition and to each new client on connect (so a late-joining viewer immediately knows whether the simulation is paused or running).

State machine: `running` → `paused` (via `pause`) → `running` (via `resume`); either → `terminating` (via `kill`, absorbing). Redundant actions (pause-while-paused, resume-while-running) are no-ops.

`kill` → server dispatches simState "terminating" → main loop exits at next safe-point check → `FileOps.closeJSons()` runs (output directory left valid) → `LiveFrameServer.stopServer()` waits 1500ms (flush window) → JVM exits.

Viewer: Pause / Resume / Kill buttons in the live bar. Button enable/disable tracks simState. Kill prompts `confirm()` before sending. On simState "terminating", reconnect is suppressed; reload the page to connect to a new simulation.

### C4: mid-run parameter adjustment (Session 18)

The viewer shows a "Params ▶" button in the live bar. Clicking it opens a floating panel populated from `queryParams`. Only parameters marked `mutableAtRuntime` appear with an editable input + Apply button.

`setParam` validation is immediate on the WebSocket thread (unknown name, immutable, or parse error → instant error ack). Valid changes are queued in `Env.paramQueue` and applied at the safe point. Success ack is dispatched after application.

The panel refreshes automatically every 5s while open, and on each reconnect. `paramAck` error tooltips appear for 4s. `paramAck` success updates the displayed value and shows `✓` for 2s.

## Safe-Point Coordination Pattern

The safe point is in `BoxOfActin.doLoop()`, inside `synchronized(Env.safeO)`, after `updateCounters()` completes for each timestep. All physics phases have finished for that step; cleanup has not yet run; all `Thing` objects are in a stable, inspectable state.

Coordination at the safe point uses `Env.safeO.wait(50)` (releases the lock for up to 50ms; `notifyAll()` from the WebSocket thread wakes it immediately on state change). The order at the safe point is: **pause wait** (with inspect drain inside) → **kill check** → **inspect drain** → **param drain** → **logAndDraw**.

The param drain is after the inspect drain so that a same-step inspect sees the pre-change state, keeping inspectResult coherent with the frame being dispatched. Any further async coordination (F-track benchmarks, GPU triggers, etc.) should use this same point.

### C2: click-to-inspect

Client clicks an object → raycasts → resolves `instanceId` to a stable `thingInstanceId` via the reverse slot maps → sends `{action:"inspect", id:<N>}`. The simulation queues the ID in `Env.inspectQueue`, drains it at the next loop-boundary (after all phases complete, inside `synchronized(Env.safeO)`, before cleanup), builds the inspect JSON via `ThreeJSWriter.buildInspectJson(id)`, and dispatches it as an `inspectResult` topic message.

**inspectResult payload shape by `kind`:**

`filSegment`: `id`, `kind`, `position` {x,y,z}, `orientation` {ux,uy,uz}, `end1`/`end2` [x,y,z], `filamentId`, `segmentArrayPos`, `monomerCount`, `notADPRatio`, `cofilinCount`, `end2Capped`, `ageSteps`, `prevSegId`/`nextSegId` (null at chain ends).

`myosin`: `id`, `kind`, `position`, `orientation`, `nucleotideState` (NONE/ATP/ADPPi/ADP), `onFil`, `inRigor`, `boundSegId` (null if unbound), `ageSteps`.

`myoMiniFilament`: `id`, `kind`, `position`, `orientation`, `end1`/`end2`, `ageSteps`, `attachedMotorIds` (array of motor IDs where `onFil==true`).

`notFound`: `id`, `kind` — sent when the ID was destroyed between click and drain.

### Backpressure

Each client has a bounded `ArrayBlockingQueue<String>(4)` and a per-client daemon sender thread. Both `dispatchFrame()` and `dispatchInspectResult()` use non-blocking `offer()` — if the queue is full the oldest item is dropped. The simulation thread is always O(1) and never blocks, regardless of network conditions.

## Mid-run Mutable Parameters

Parameters are annotated with `.setMutableAtRuntime()` in their `Env.java` declaration. The `Parameter.getAllMutable()` method returns all annotated parameters. `LiveFrameServer.buildParamListJson()` serializes the full list for `queryParams` responses. `LiveFrameServer.handleSetParam()` validates and queues changes; `BoxOfActin.drainParamQueue()` applies them at the safe point.

**Whitelist (Session 18) — conservative by design:**

| Parameter label | Type | Justification |
|---|---|---|
| `toFileInterval` | int | Pure counter threshold (`threeJSCounter >= value`) in `logAndDraw`/`remoteLog`. No cached derivatives. No physics effect. Counter reset to `newValue-1` on application so the next step fires immediately. |

**Promotion criteria for future sessions:** A parameter can be added to the whitelist if: (a) every usage is `getValue()` at call time (not a stored-once derivative), and (b) no per-run initialization captures the value into a local or static field used for the rest of the run. Grep for `p.label` across all source files and trace each hit.

**Confirmed immutable (cached derivatives prevent safe mid-run change):** `deltaT`, `biochemDeltaT`, `collisionDeltaT`, `brownianDeltaT` (all drive `Thing.biochemCheckInt`/`collisionCheckInt`/`brownianApplyInt`), `nVBlobPerBug` (drives `blobTransGam`/`blobRotGam`), `aeta` (drives `bTransGamViscBlob`/`bRotGamViscBlob`), `nodeRadius` (drives `nodeTransDiff_init`/`nodeRotDiff_init`), all box/bug/topology parameters (applied only in `makeCrucible()`/`makeInitialThings()`).

**Unclear (likely mutable after audit):** `kNodeNuc`, `kRdmNuc`, `cofilinRate`, `cofilinConc`, `tropoOnRate`, `tropoOffRate`, `tropoConc`, `fracMove`/`fracR`/`fracMoveTorq`, `remoteReportInterval`, and the actin biochem rate constants (`kATPOn1/2`, `kATPOff1/2`, `kADPOff1/2`). These appear to be read fresh each step but require a complete usage-graph trace before promoting.

## Architecture

### Entry point

There is a top-level `BoxOfActin.java` (default package) and a `boxOfActin.BoxOfActin` class. The default-package version is the entry point; its `main()` calls `boxOfActin.BoxOfActin.begin(args)`. The `main()` inside `boxOfActin.BoxOfActin` is currently commented out. This is brittle and worth tidying eventually, but it works.

### Core simulation classes

- **`boxOfActin/BoxOfActin.java`** — `begin()` parses args and sets up the simulation; `doLoop()` is the per-timestep orchestration. One `TimeLoop` thread drives everything. After Phase 3, this file no longer contains graphics orchestration, QK code, or the Swing timer.
- **`boxOfActin/Env.java`** — global singleton with all physical constants, `Parameter` instances, thread counts, and timestep-phase integer constants (`meshFilsStart`, `stepStart`, etc.). Still has a few legacy graphics-flag fields (`paintOn`, `viewRotation`, etc.) that are harmless leftovers; they don't pull in Java3D.
- **`boxOfActin/Thing.java`** — abstract superclass for every simulated object. Holds position, orientation, forces/torques, drag/diffusion tensors, and Brownian force calculation. All simulated objects live in the static `theThings[]` array. After Phase 1, this class has no Java3D fields. `drawYourself(Graphics, double, double[])` is an empty AWT-typed stub retained as the rendering hook for future use. `thingInstanceId` is a stable, monotonically increasing ID assigned at construction and never reassigned (unlike `myThingNumber`, which changes on swap-compact cleanup). `findByInstanceId(int)` provides O(1) lookup via a static `ConcurrentHashMap` maintained in sync with the `theThings[]` lifecycle.
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

- **Actin filaments**: ~7 nm diameter (~3.5 nm radius), modeled as rigid rods (FilSegment chains). The radius is derived in `FilSegment.java:29` as `Env.actinWidth / 2`; `Env.actinWidth` is the filament *diameter* in microns (0.007 µm).
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

## F1 Benchmark Journal

### F1 sidebar — monomerCt=64 equilibrium diagnostic (2026-05-15)

**Question:** At fracMoveTorq=0.02, monomerCt=64, fracR=0.3 — is the plateau ratio≈1.54 seen in the stuck bisection loop true equilibrium, or a false plateau?

**Procedure:** Added `-bmDiag` CLI flag (no bisection, fixed parameters, print ratio every 5000 steps, cap 5M steps). Run: `java -Xmx800M -cp ".:libs/*" BoxOfActin -bmDiag -bmMonomer 64`.  Parameters: fracMoveTorq=0.02, monomerCt=64, fracR=0.3, span=1.9305 µm, box auto-sized to 5.79×5.79 µm.

**Per-5000-step data (steps 5000–125000):**

| step | simT (s) | ratio | defl (µm) | dRatio/5000 |
|------|----------|-------|-----------|-------------|
| 5000 | 0.50 | 0.5197 | 0.010032 | N/A |
| 10000 | 1.00 | 0.7115 | 0.013735 | +0.1918 |
| 15000 | 1.50 | 0.7993 | 0.015430 | +0.0878 |
| 20000 | 2.00 | 0.8396 | 0.016209 | +0.0403 |
| 25000 | 2.50 | 0.8588 | 0.016580 | +0.0192 |
| 30000 | 3.00 | 0.8671 | 0.016740 | +0.0083 |
| 35000 | 3.50 | 0.8708 | 0.016811 | +0.0037 |
| 40000 | 4.00 | 0.8738 | 0.016868 | +0.0030 |
| 45000 | 4.50 | 0.8745 | 0.016882 | +0.0007 |
| 50000 | 5.00 | 0.8747 | 0.016886 | +0.0002 |
| 55000–125000 | — | 0.875 ± 0.001 | — | noise floor (±0.001) |

**Two-timescale transient:** The ratio shows a fast initial rise (0→0.52 in 5000 steps, driven by rapid local segment straightening) followed by a slow exponential approach to equilibrium. Two-point fit from steps 35000–45000 gives τ_slow ≈ 3500 steps (0.35 simulated seconds).

**Interpretation: TRUE EQUILIBRIUM at ratio ≈ 0.875.** The derivative reached the noise floor (±0.001) by step 50000 and showed no trend through step 125000. The plateau ratio≈1.54 reported by the stuck bisection loop was a **false plateau**: the convergence criterion (0.5% between consecutive 1000-step checks) fired at ~21000 steps while the chain was in a fast-mode quasi-steady state around ratio=0.85, not at the true equilibrium.

**Implication:** At fracMoveTorq=0.02, the monomerCt=64 chain is stiffer than the analytic target (ratio < 1). The calibrated fracMoveTorq satisfying ratio=1 is below 0.02, estimated ~0.0175 from r_eq ≈ f_cal/f (linear spring model). The bisection was searching in the correct direction [0, 0.02], but the settle window (benchMinSettleSteps=20000 = ~5.7τ at 0.02, but only ~2.9τ at 0.01) was insufficient for candidates well below 0.02.

**Root cause of bisection failure (monomerCt=64/128):** The torsion-spring formula divides by `(mob_self + mob_neighbor) × deltaT`, which causes drag to cancel. The net angular change per step is exactly `fracMoveTorq × θ`, independent of L_seg. Consequently τ_slow ≈ N²/fracMoveTorq ≈ 100/fracMoveTorq steps (N=10 joints), independent of monomerCt. The bisection searches toward smaller fracMoveTorq where τ_slow grows proportionally. A fixed settle window fails as soon as candidates reach ~f/2 of the initial value.

**Fix (2026-05-15):** Dynamic settle: `benchMinSettleSteps = max(5×100/f, monomerCt³-base)` updated after each candidate selection. This gives 5τ_slow throughout the search (5×10000=50000 at f=0.01, 5×20000=100000 at f=0.005, etc.). The monomerCt³ base floors the initial candidate to the same scale as before.

### F1 sidebar — monomerCt sensitivity sweep results (2026-05-14)

**monomerCt=32:** Converged in 9 iterations. fracMoveTorq_cal ≈ 1.010, ratio=1.000 ± 0.01.

**monomerCt=64:** Stuck at lo=hi=0.02 in old code (false convergence at ratio≈1.544). After L³ scaling fix: pending rerun.

**monomerCt=128:** Did not complete initial settle in old code. After L³ scaling fix: pending rerun.

### Manual benchmarking round 2: nm display, in-plane force, relaxation time (2026-05-16)

**Drag formula survey.** BoA uses Slender Body Theory for perpendicular drag per segment:

```
ζ_perp_seg = bTransGam.y = 4π η L_seg / (ln(L_seg / 2r) + 0.84)
```

where η = `Env.aeta` (Pa·s), L_seg = segment length (m), r = `actinWidth/2` (m), and 0.84 = `aOrthog` (empirical fit constant in `FilSegment.java`). This is the body-frame y-direction drag (perpendicular to the segment axis); `bTransGam.z = bTransGam.y` by symmetry.

For a pinned-pinned beam with distributed transverse drag c [N·s/m²] and bending stiffness EI, the first-mode relaxation time is τ = c L⁴ / (EI π⁴). Writing c = N_segs × ζ_perp_seg / L_span:

```
τ_theo = N_segs × ζ_perp_seg × L_span³ / (EI × π⁴)
```

This is computed once in `makeInitialThings()` after the chain is created and printed in the `[BENCH] τ_theo=…` startup line.

**Persistence-length parameter used.** `Env.persistenceLength = 15` µm (static final constant in `Env.java`, line ≈503). Used as `EI = kT × Lp ≈ 6.2e-26 N·m²` at 25°C. No user-tunable Parameter wraps this; it is compile-time constant.

**Force direction change.** Old vector: `benchTransForce.setVals(0, 0, forceN)` (Z axis, into/out of screen). New vector: `benchTransForce.setVals(0, forceN, 0)` (Y axis, in-plane). Axis convention: chain aligned along world X, camera at (0, 0, 15) looking at origin; Y is up and in-plane with the default view. Z force produced deflection into the screen — not visible without rotating the camera. Y force produces a visible curve in the X-Y plane without any camera adjustment.

The deflection measurement (`computeBenchmarkSnapshot()`) computes the full perpendicular distance from the chord regardless of direction, so no change to measurement code was needed.

**Payload schema change.** Fields removed: `relaxationStepsElapsed` (int, step count), `relaxationFraction` (float, obs/release ratio). Fields added: `tauTheo` (float, seconds, always present once chain is initialized), `tauMeas` (float, seconds, only present when force is off and a release event is active), `tauMeasFrozen` (boolean, alongside `tauMeas`). WebSocket protocol still carries `observedDeflection` and `expectedDeflection` in µm; nm conversion is display-layer only in the viewer.

**Display changes.** Benchmark HUD now shows deflection in nm (multiply µm × 1000). Two amber rows (τ_meas and τ_theo) replace the old "Relax: N steps, X%" row. Both are hidden when force is on or no release event is active (`tauMeas` absent from payload). τ_meas counts up in simulated seconds; after the deflection decays to 1/e of the release value it freezes and shows "(crossed)". τ_theo is a fixed value from chain initialization. Params panel moved from `right: 12px` to `left: 12px` to avoid overlap with the benchmark HUD.

**Calibration intuition (from user).** At fracR ≈ 0.3, increasing fracR softens the chain while increasing fracMoveTorq stiffens it. This is counterintuitive from naming alone and worth recording: fracR controls the fractional displacement applied per timestep by the link restoring force, so smaller fracR → weaker restoring force → softer. fracMoveTorq controls torsional stiffness; larger → stiffer.

**Expected τ_theo magnitude.** For the default boa10-64Seg parameter file (monCt=64, span≈1.93 µm, η=0.1 Pa·s, Lp=15 µm): τ_theo ≈ 0.7 s. This is the first bending mode. The measured τ_meas will include higher modes and may differ; compare them to assess how well the single-mode approximation holds.

**Smoke test.** Compiled clean with `javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java`. Code paths verified by inspection. End-to-end verification (τ_meas vs τ_theo comparison, force direction visual) deferred to user as in Round 1.

### Manual benchmarking round 3: clean benchmark environment (2026-05-16)

**Problem.** Launching `-bmManual -pf ParameterFiles/boa10-64Seg` crowded the benchmark chain with unrelated objects (100 minifilaments, active kNodeNuc, etc.), making the deflection ratio meaningless.

**Survey — population sites.**

| Creation site | Mechanism | Guarded by benchmarkFilament? |
|---|---|---|
| `makeInitialThings()` non-benchmark path: `makeInitialFilaments`, `makeInitialMyoMiniFils`, `makeInitialProteinNodes` | Called in the else-of-benchmark block | Yes — already returns early |
| `Chamber()` constructor: `makeMyosinHeads()`, `makeMyosinDimers()` | Always called in `makeCrucible()` via constructor | **No** |
| `doLoop()`: `MyoMiniFilament.equilibrateMyoMiniNumber()` | Runs unconditionally every step; adds minifilaments until count reaches `initialMyoMiniFils` | **No** |
| `doLoop()`: `ProteinNode.equilibrateNodeNumber()` | Runs unconditionally every step; adds nodes until count reaches `equilNodes` | **No** |
| `doLoop()`: `FilSegment.spawnRdmFilaments()` | Guarded by `kRdmNuc.isActive()` | Yes (flag basis) |
| `doLoop()`: `ProteinNode.spawnNodeFilaments()` | Guarded by `kNodeNuc.isActive()` | Yes (flag basis) |
| ActA | Created only inside `Bug.makeListeriaBug()` | Yes — `simOutsideBug.setActive(false)` already set |
| Arp2/3 | Created dynamically during branching events; requires filaments+nodes to branch from | Indirectly suppressed by clearing filaments/nodes |

For `boa10-64Seg`: `numChamberFixedMyos/Dimers` default to 0 (not in that param file). The live issues were `initialMyoMiniFils:100`, `kNodeNuc:true`, and `equilNodes` (defaulting to 0 but worth zeroing defensively).

**Fix 1 — population suppression.** In `begin()`, after `FileOps.loadParamConfig()` (so it overrides whatever the param file set) and before `makeCrucible()`:

```java
if (Env.benchmarkFilament) {
    Env.numChamberFixedMyos.setValue(0);
    Env.numChamberFixedMyoDimers.setValue(0);
    Env.initialMyoMiniFils.setValue(0);
    Env.equilNodes.setValue(0);
    Env.kRdmNuc.setActive(false);
    Env.kNodeNuc.setActive(false);
}
```

Param-file *physical* values (box dims, viscosity, temperature, Lp, deltaT, monomerCt, etc.) continue to be honored — only the population counts are zeroed.

**Survey — wall collision code.** `FilSegment.checkBugOrBoxCollision()` is called unconditionally in `step()` for every FilSegment. No feature flag. With `simOutsideBug.setActive(false)` (already set in benchmark mode), it uses `checkBugCollisionFromInside()` → `Chamber.amICollidingOuter()`. That method computes a penetration vector; if the endpoint is inside all walls, `delta = 0` and `bugForcesFromInside()` is never called. The benchmark auto-sizes the box to 3× chain span, so the chain (max deflection ≈ 1% of span) never contacts the walls. **No code change needed.**

**Survey — chamber wireframe.** Built in `applyFrameData()` (viewer) from `data.bounds = {xDim, yDim, zDim}`. The JSON source is `ThreeJSWriter.buildFrameJson()` which always emitted this field. Note: the emitted dims are from `Env.boxXDim/YDim/ZDim` (param-file values), not from `Chamber.dimX/dimY` (which the benchmark auto-sizes to 3× span). So even before this fix, the wireframe showed the param-file box, not the actual collision boundary — a pre-existing inconsistency.

**Fix 3 — suppress wireframe.** `ThreeJSWriter.buildFrameJson()` now omits the `"bounds"` field when `Env.benchmarkFilament` is true. The viewer's `applyFrameData()` now guards wireframe creation with `if (!boundsObj && data.bounds)` to handle the absent field gracefully.

**Smoke test.** Compiled clean. Population suppression verified by inspection of the call graph. End-to-end visual verification (chain only, no minifilaments, no wireframe) deferred to user.

### Manual benchmarking round 4: revert to 11×32 chain, add config HUD, add per-segment axes (2026-05-16)

**Prior calibrated chain config.** The F1 bisection search (Step 2 final) confirmed: 11-segment × 32-monomer chain, span = 0.9801 µm, analytic δ = 0.0098 µm. Calibrated: `fracMoveTorq = 1.010`, `fracR = 0.3` (from the auto-search with `brownianFilMotionOff`, actinWidth=0.007 µm). The user's historical hand-tuning values are `fracMove=0.4, fracR=0.14`; the search-derived value (`fracR=0.3, fracMoveTorq=1.010`) supersedes these.

**Bug context.** After Round 3 (clean environment), running `-bmManual -pf boa10-64Seg` produced obs ≈ 0 nm at ratio 0.005 even with maximally soft coefficients (fracMoveTorq=0.00001, fracR=1.0). This is not a tuning problem — the chain was not deflecting at all. The 64-monomer configuration is unverified; the 32-monomer config has a confirmed working calibration. Round 4 reverts to the known-good baseline while the bug is investigated.

**Deliverable 1 — monomerCt=32 override.** Mechanism: `makeBenchmarkChain()` reads `benchmarkMonomerCt` if > 0, else falls back to `stdSegLength.getIntValue()`. The param file `boa10-64Seg` sets `filSegLength:true:64.0`, which loads into `stdSegLength`. Fix: in `begin()`, after `loadParamConfig()`, the existing benchmark override block now also sets `Env.stdSegLength.setValue(32)` when `benchmarkMonomerCt <= 0`. The `-bmMonomer` flag (explicit override) still takes priority because it sets `benchmarkMonomerCt > 0`. Physical parameters from the param file (viscosity, deltaT, box dims, etc.) are all still honored — only `filSegLength` is clamped for benchmark mode.

**Deliverable 2 — `benchmark` topic payload additions.** Three new fields emitted from `buildBenchmarkJson()` on every frame:
```json
"chainSegments":      11,
"monomersPerSegment": 32,
"chainSpanMicrons":   0.9801
```
`chainSegments` = `Env.benchmarkNSegs` (static). `monomersPerSegment` = `benchMonCt` (resolved at chain construction, after `-bmMonomer` override). `chainSpanMicrons` = stored in new static `benchChainSpanMicrons` set in `makeInitialThings()`. Viewer HUD: a new `#bmChainInfo` div (class `bm-chain`, grey text) appears above a thin `bm-divider` rule and the existing metric rows. Shows:
```
chain: 11 seg × 32 mon
span: 0.98 µm
─────
obs: 9.81 nm
…
```

**Deliverable 3 — per-segment local axes.** `ThreeJSWriter.buildFrameJson()` now appends `axisX`, `axisY`, `axisZ` (each a 3-element float array in world coordinates) to each segment JSON entry when `Env.benchmarkFilament` is true. The vectors are `fs.uVec` (long axis / body-X), `fs.yVec` (body-Y), `fs.zVec` (body-Z) — package-accessible fields declared in `Thing`. Bandwidth: 9 floats × 11 segments = 99 extra floats per frame; negligible at benchmark cadence.

Viewer: `updateAxisLines(segments)` creates or updates three `THREE.LineSegments` objects (X=red #ff5555, Y=green #55ff55, Z=blue #5555ff). Each axis indicator is a line from centroid−0.3×halfLen×axis to centroid+0.3×halfLen×axis. The `#chkAxes` checkbox in the Display panel controls `showAxes`; it defaults OFF and is automatically enabled (checked) on the first `benchmark` topic message.

**Startup log (updated).** Now prints:
```
[BENCH] 11-seg × 32-mon/seg chain, span=0.9801 µm, F=3.085e-14 N, analytic δ=0.0098 µm
```

**Smoke test.** Compiled clean. End-to-end visual verification (axes visible, config HUD, 32-monomer span) deferred to user. Launch:
```
java -Xmx800M -cp ".:libs/*" BoxOfActin -bmManual -3jsLive 8081 -pf ParameterFiles/boa10-64Seg
```
Expected: HUD shows `chain: 11 seg × 32 mon`, `span: 0.98 µm`, `exp: 9.8 nm`. Three colored axis indicators visible at each segment centroid.
