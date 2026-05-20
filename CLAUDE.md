# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

**BoxOfActin** — a Java biophysics simulation of actin filament network dynamics in cells. Simulates filaments, myosin motors, crosslinkers, Arp2/3-branched networks, membrane nodes, and Listeria motility using overdamped Langevin dynamics with Brownian motion. Visualization is handled out-of-process by a Three.js browser viewer fed by per-frame JSON files written from the simulation (`ThreeJSWriter`).

## Documentation Layout

| File | Contents |
|---|---|
| `CLAUDE.md` | This file — build/run commands, architecture, conventions for Claude Code sessions |
| `JOURNAL.md` | Active journal — most recent 10–20 substantive entries; open questions; current known issues |
| `JOURNAL_ARCHIVE.md` | Older entries verbatim (oldest at top); safe to read for history but not loaded by default |
| `RUN_LOGS/` | Bulky pasted simulation output; plain `.txt` files named `YYYY-MM-DD_short-topic.txt` |

When restructuring documentation: entries move to `JOURNAL_ARCHIVE.md` verbatim (no summarizing); log blocks > ~20 lines go to `RUN_LOGS/`. Do not delete content.

## Documentation Conventions

When adding to JOURNAL.md, keep entries terse. An entry should describe what was done, what was learned, and what's open. Avoid restating context that's clear from the surrounding entries.

Do not paste simulation output more than ~20 lines long inline. Write the output to `RUN_LOGS/YYYY-MM-DD_short-topic.txt` and reference it by filename from the journal entry. Short snippets (a few lines highlighting a specific value or message) are fine inline.

Do not move entries to `JOURNAL_ARCHIVE.md` on your own. Archival happens via explicit cleanup prompts from the planner. If JOURNAL.md feels too long, flag it for the planner rather than reorganizing autonomously.

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
{"topic": "benchmark",     "payload": { ...deflection/relaxation JSON... }}
{"topic": "lpBenchmark",   "payload": { ...persistence-length JSON... }}
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

**Authoritative whitelist:** `grep setMutableAtRuntime boxOfActin/Env.java` — the list has grown significantly and `Env.java` is the source of truth. As of Session 18+, includes `toFileInterval`, `fracMove`, `fracR`, `fracMoveTorq`, `aeta` (with drag-tensor recomputation hook in `drainParamQueue`), `BTransCoeff`, `BRotCoeff`, `lpEwmaAlpha`, `lpActive`, `benchmarkForceFrac`, `benchmarkForceOn`.

**Promotion criteria for future sessions:** A parameter can be added to the whitelist if: (a) every usage is `getValue()` at call time (not a stored-once derivative), and (b) no per-run initialization captures the value into a local or static field used for the rest of the run. Grep for `p.label` across all source files and trace each hit. Parameters with cached derivatives (e.g. `deltaT` drives `Thing.biochemCheckInt`) cannot be safely promoted without a recomputation hook.

**`aeta` note:** When `aeta` changes mid-run, `drainParamQueue()` calls `calculateProperties()` on all `FilSegment` instances to refresh their drag tensors, and recalculates `tauTheo` for the deflection benchmark. Static finals `bTransGamViscBlob`/`bRotGamViscBlob` and `nodeTransDiff_init`/`nodeRotDiff_init` are NOT updated — viscous-blob and protein-node runs would have stale values after a mid-run aeta change.

## Architecture

### Entry point

There is a top-level `BoxOfActin.java` (default package) and a `boxOfActin.BoxOfActin` class. The default-package version is the entry point; its `main()` calls `boxOfActin.BoxOfActin.begin(args)`. The `main()` inside `boxOfActin.BoxOfActin` has been removed (replaced with a comment at line 123). This split is brittle but works.

### Core simulation classes

- **`boxOfActin/BoxOfActin.java`** — `begin()` parses args and sets up the simulation; `doLoop()` is the per-timestep orchestration. One `TimeLoop` thread drives everything.
- **`boxOfActin/Env.java`** — global singleton with all physical constants, `Parameter` instances, thread counts, and timestep-phase integer constants (`meshFilsStart`, `stepStart`, etc.). Has a few legacy graphics-flag fields (`paintOn`, `viewRotation`, etc.) that are harmless leftovers — they don't pull in Java3D and are never read.
- **`boxOfActin/Thing.java`** — abstract superclass for every simulated object. Holds position, orientation, forces/torques, drag/diffusion tensors, and Brownian force calculation. All simulated objects live in the static `theThings[]` array. `drawYourself(Graphics, double, double[])` is an empty AWT-typed stub retained as a rendering hook — don't delete it. `thingInstanceId` is a stable, monotonically increasing ID assigned at construction and never reassigned (unlike `myThingNumber`, which changes on swap-compact cleanup). `findByInstanceId(int)` provides O(1) lookup via a static `ConcurrentHashMap` maintained in sync with the `theThings[]` lifecycle.
- **`boxOfActin/Pt3D.java`** — 3D point / vector with explicit `public double x, y, z;` fields and ~600 lines of pure-Java math (cross, dot, unit-vector, body-frame transforms via `Thing.transXTox` / `Thing.transxToX`, etc.).

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

### Bundled libraries

- `ec/util/` — `MersenneTwister` / `MersenneTwisterFast` PRNG
- `edu/cornell/lassp/houle/RngPack/` — `RanMT` and related RNG classes
- `infoCCD/` — `Info` and `ResourceGetter` (a small remnant; most of this package's GUI utilities were removed with Phase 4)

---

## GPU Acceleration Strategy

GPU acceleration via TornadoVM is planned. The only remaining prerequisite is `brew install openjdk@21` on the MBP (Phase 5), followed by a clean compile under Java 21 with `--enable-preview`. Full implementation strategy, phase priorities, write-write hazard analysis, and TornadoVM specifics are in `~/Dropbox/CodeSync/Sim3D/GPU_STRATEGY.md` and the JOURNAL.md GPU sections.

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

## Benchmark Modes

| Flag | Mode |
|---|---|
| `-bm` | Automated bisection search for PAIRS bending coefficients |
| `-bmManual` | Manual tuning: deflection chain + LP chain, live WebSocket HUD |
| `-bmDiag` | Diagnostic: fixed parameters, print ratio every 5000 steps, cap 5M steps |
| `-bmMonomer <N>` | Override monomers/segment (otherwise uses param-file `filSegLength`) |

In all benchmark modes, population objects (myosins, minifilaments, protein nodes, ActA) are suppressed automatically in `begin()`. The chamber wireframe is hidden in the viewer.

**Deflection chain** (all benchmark modes): 11-segment, pinned ends, midpoint transverse force, measures static deflection ratio obs/exp and dynamic relaxation time τ_meas vs τ_theo. Calibrated defaults target ratio ≈ 1 at aeta = 0.1 Pa·s: `fracMove=0.5`, `fracR=0.1`, `fracMoveTorq=0.265`. All three are runtime-mutable via the Params panel. Parameter intuition: smaller `fracR` → weaker restoring force → softer chain; larger `fracMoveTorq` → stiffer in torsion.

**Persistence-length (LP) chain** (`-bmManual` only): free boundary conditions, Brownian forces active, tangent-vector correlation C(s) measured via EWMA. Chain is ~8 µm long (n = round(8.0 / segLen) segments), placed at Y = −1.5 µm, Z = −0.5 µm. Lp_meas converges toward `Env.persistenceLength` (15 µm, compile-time constant). BTransCoeff and BRotCoeff control Brownian forcing magnitude and are runtime-mutable for LP calibration. `lpActive` (Params panel) suspends/resumes the LP chain and resets the EWMA accumulator on resume.

**Launch:**
```
java -Xmx800M -cp ".:libs/*" BoxOfActin -bmManual -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -bmManual -3jsLive 8081 -pf ParameterFiles/boa10-64Seg
```
