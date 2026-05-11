# BoxOfActin Project Journal

Last updated: 2026-05-07

---

## Project Goal

Replace Java3D visualization with a JSON + Three.js browser viewer, then GPU-accelerate the simulation using TornadoVM. The two goals are linked: Java3D must be removed before Java 21 can be used, and Java 21 is required by TornadoVM.

---

## Accomplished

### 1. Architecture documentation (CLAUDE.md)

Created `CLAUDE.md` from scratch. It covers:
- Build and run commands (current Java3D-dependent setup)
- Full simulation object hierarchy (Thing, FilSegment, Monomer, connectors)
- ThreadSet multithreading model and 12-phase loop with `Env` phase constants
- GPU acceleration strategy derived from Sim3D lessons: persistent residency,
  two-plan architecture, SoA layout, phase-by-phase GPU suitability, write-write
  hazard analysis (ownership + atomics vs. graph coloring), 4-step implementation
  sequence, TornadoVM specifics
- Java3D removal as a prerequisite for TornadoVM

### 2. JSON frame output (ThreeJSWriter)

**New file:** `boxOfActin/ThreeJSWriter.java`

Writes per-frame JSON files (`frame_000000.json`, `frame_000001.json`, ...) in a
format compatible with the Three.js viewer:

```json
{"frame":N, "t":T, "bounds":{"xDim":X,"yDim":Y,"zDim":Z},
 "segments":[{"end1":[x,y,z],"end2":[x,y,z],"r":0.035},...]}
```

On first write: resolves the output directory (auto-incrementing `.001`, `.002`...
if the requested name already exists), then archives all `.java` sources to
`source.zip` in the output directory for reproducibility.

Only live FilSegments (`removeMe == false`) are written. `end1` and `end2` are
kept current by `FilSegment.initialize()`, which is called every step inside
`moveThing()`, so they are always valid at output time.

**Modified:** `boxOfActin/Env.java`
- Added `static String threeJSOutputDir = null`

**Modified:** `boxOfActin/BoxOfActin.java`
- `parseArgs()`: `-3js <dir>` flag sets `Env.threeJSOutputDir`; added to `-help`
- `updateCounters()`: increments `threeJSCounter`
- `logAndDraw()` and `remoteLog()`: both check `threeJSCounter >= toFileInterval`
  and call `ThreeJSWriter.writeFrame()`, so output works in both GUI and headless
  (`-r`) modes
- Initial counter value `(int)1e6` ensures frame 0 is written at time zero

**Usage:**
```
java -Xmx800M BoxOfActin -r -pf ParameterFiles/boa10-64Seg -3js myrun
```

Output confirmed working by user.

### 3. Three.js viewer (sim_viewer_boa.html)

**New file:** `sim_viewer_boa.html` (also copied to `~/Desktop/sim_viewer_boa.html`)

Adapted from `~/Dropbox/CodeSync/Sim3D/sim_viewer.html`. Key changes:

- **Geometry:** Instanced `CylinderGeometry(1,1,1,8)` instead of spheres. Each
  instance is scaled to `(r, length, r)` and rotated via
  `quaternion.setFromUnitVectors(yAxis, dir)` where `dir = end2 - end1`. All
  vector temporaries (`_end1`, `_end2`, `_dir`) are allocated once outside the
  render loop to avoid GC pressure.
- **Bounds:** `BoxGeometry(xDim, yDim, zDim)` → `EdgesGeometry` → `LineSegments`
  wireframe, centered at origin. The intermediate BoxGeometry is disposed
  immediately; only the EdgesGeometry is retained on the scene object.
- **Camera:** Initial `z = 15` (vs. Sim3D's `z = 3`) for a 10×10 micron box.
- **HUD:** Shows frame number, simulation time, segment count.
- **MAX_SEGMENTS:** 50,000 (covers the ~25K expected in large runs with headroom).

Everything else is unchanged from Sim3D: play/pause, slider, 2/10/30 fps, Cmd+O
to focus directory input, ArrowLeft/Right stepping, `/api/simulations` auto-
discovery, recent-directory dropdown.

### 4. HTTP server

**New file:** `sim_server.py` — copied verbatim from Sim3D. Scans subdirectories
for `frame_000000.json`, serves them as static files, and exposes
`GET /api/simulations` for the viewer's auto-discovery dropdown.

**Usage:** `python3 sim_server.py` from the directory containing the run output
folders, then open `http://localhost:8000/sim_viewer_boa.html`.

---

## Current Known Issues

### Java3D still present throughout codebase

**This is the critical blocker for GPU work.** Java3D imports appear in 36 source
files. The class-level field declarations in `Thing.java` —

```java
BranchGroup G = new BranchGroup();
TransformGroup g3d = new TransformGroup();
```

— prevent the JVM from loading `Thing` (and therefore everything) even under `-r`
headless mode unless the Java3D jars are on the classpath. This means:

- The simulation currently requires the Java3D jars at `/Library/JOGLAndj3D/`
- Java 21 cannot be used until Java3D is removed
- TornadoVM requires Java 21

The simulation compiles and runs correctly under the current Java3D setup. JSON
output works. But no GPU work can begin until this dependency is cleared.

### Viewer not yet tested against live output

`sim_viewer_boa.html` has been written but not yet confirmed working in a browser
against actual BoA frame files. The cylinder orientation math (`setFromUnitVectors`
aligning Y-axis to each segment's direction) needs visual verification.

### toFileInterval controls Three.js cadence

The `-3js` output interval reuses `Env.toFileInterval` (default: 100 steps).
There is currently no separate parameter to control Three.js output frequency
independently of the image-capture interval. This may be fine for now; revisit
if the cadences need to differ.

### No `-3jsN` flag

There is no equivalent of `-qkN` to set a custom frame interval from the command
line. To change output frequency, modify `toFileInterval` in the param file or
add a `-3jsN` flag later.

### ckElasticityCounter and ckPersistenceCounter are dead but intentional

`BoxOfActin.updateCounters()` increments two fields — `ckElasticityCounter` 
and `ckPersistenceCounter` — that have no consumers anywhere in the code. 
They are scaffolding from a benchmark subsystem (filament static deflection, 
relaxation time, and persistence length) that existed in an earlier version 
and was lost when Java3D-coupled GUI controls were removed.

These fields are deliberately retained as breadcrumbs. The benchmark 
subsystem will be rebuilt post-Java3D-removal (see "Future: filament 
calibration benchmark suite" below), and these counters will become its 
sampling-cadence counters at that time. Do not remove them.

---

## Next Steps

### Immediate: verify the viewer

Run a short simulation with `-3js` and open the output in `sim_viewer_boa.html`
served by `sim_server.py`. Confirm:
- Box wireframe dimensions match `boxXDim`/`boxYDim`/`boxZDim`
- Cylinders appear and orient correctly
- Play/pause and scrubbing work

### Short term: Java3D removal

This is the prerequisite for everything GPU-related. The approach (established
in Sim3D):

1. Remove `BranchGroup G` and `TransformGroup g3d` field declarations from
   `Thing.java` and stub out all methods that reference them.
2. Work through the 36 affected files. For classes that only use Java3D for
   rendering (most of the `*Control.java`, `BoxOfActin_Graphics.java`,
   `CapturingCanvas3D.java`, `ViewerBehavior.java`, etc.), either delete or
   stub them behind a compile flag.
3. FilSegment, Monomer, and the other simulation classes use Java3D mainly for
   per-object graphics nodes. These need to be stubbed or conditioned on
   `Env.remote`.
4. Validate that `javac` succeeds with Java 21 (`--enable-preview --release 21`)
   after removal.

The `-r` headless flag already suppresses all graphics calls in `doLoop()`. The
remaining blocker is field declarations at class-load time.

### Future (post-Java3D, possibly post-GPU): real-time viewer with command channel

The current Three.js viewer is post-hoc playback only — `ThreeJSWriter` writes 
frames to disk, the browser reads them later. This works well for long headless
runs but isn't suited to interactive debugging workflows like:

- Toggle `brownianFilMotionOff` on/off and watch elastic bonds relax/jostle
- Flip individual test flags without restarting the simulation
- Inspect a running sim from a different machine (e.g. browser on MBP, sim on aorus)

The architectural fix is a WebSocket bridge alongside the existing file-write path:

- **Server side:** a small embedded WebSocket server in the Java process (Jetty, 
  or hand-rolled — the protocol is small). On each step (throttled to ~30 fps), 
  serialize the same JSON frame that `ThreeJSWriter` already produces and push 
  it. Listen for inbound command messages; dispatch them at the next step 
  boundary.
- **Client side:** `sim_viewer_boa.html` gains a data-source toggle (file vs 
  socket). All existing rendering and scale-slider code is unchanged.
- **Command channel:** generic `{cmd, name, value}` protocol. UI checkboxes 
  for any `Env` flag or `Parameter`; routes through the same dispatcher used 
  by the consolidated test-mode CLI flags.

Why this is the right architecture:

1. Reuses everything — frame format, viewer code, scale sliders, myosin 
   coloring. Live and recorded modes are the same viewer.
2. No graphics class-loading dependency in the Java sim — the browser is a 
   separate process. Stays compatible with headless TornadoVM runs.
3. The command channel pattern is general: any future interactive debugging 
   feature (highlight high-strain segments, freeze a filament, change a 
   parameter live) plugs in the same way.

Why this is deferred until after Java3D removal:

1. Java3D removal is the gating item for GPU work; everything else is lower 
   priority.
2. The test-mode flag consolidation that follows Java3D removal produces the 
   clean dispatcher that the command channel will reuse — building the 
   WebSocket layer before that consolidation means doing the dispatcher 
   work twice.
3. Once GPU step 1 is in place, the value of an interactive viewer goes up 
   substantially (debugging GPU/CPU divergence visually is much easier than 
   from logs).

Estimated scope: ~200–300 lines of Java for the embedded server + dispatcher,
modest HTML/JS additions for the data-source toggle and command UI. Jetty 
adds ~2MB as a dependency; hand-rolled WebSocket avoids that at the cost of 
a weekend of protocol implementation.

### Future (post-Java3D): filament calibration benchmark suite

The simulation models actin filaments as chains of rigid FilSegments connected
by linear and torsional springs. This is a discretized approximation of a 
continuously flexible filament. The spring constants required to reproduce 
correct actin biophysics — persistence length Lp ≈ 15 µm and relaxation 
time constants matching continuous-beam theory — depend on the chosen 
segment length. Changing `filSegLength` (monomers per segment) without 
re-tuning leaves the simulation biophysically miscalibrated.

The original simulation had a benchmark suite for this purpose, accessible 
through the Java3D GUI. The Three.js / headless rebuild has not yet replaced
it. The dead fields `BoxOfActin.ckElasticityCounter` and 
`ckPersistenceCounter` are breadcrumbs from the original implementation.

Once Java3D removal and test-mode consolidation are complete, rebuild the
benchmarks as a first-class subsystem.

#### Test 1 — Static deflection + relaxation time

Two sub-cases, selected by a parameter:

**Simply-supported beam (both ends pinned):**
- Pin endpoint positions (zero translation), allow rotation.
- Apply transverse point force F at midspan.
- Analytic steady-state deflection: δ = F·L³ / (48·EI)

**Cantilever (one end clamped):**
- Clamp endpoint position AND orientation at one end.
- Apply transverse point force F at the free end.
- Analytic steady-state deflection: δ = F·L³ / (3·EI)

For both: Brownian motion off (set `brownianFilMotionOff` true). Run to 
steady state, measure δ, compare to analytic — this calibrates EI 
(effective bending stiffness) for the chosen segment length.

Then remove F and measure free relaxation. The displacement of a tracked 
point decays as a sum of exponentials e^(-t/τₙ). The lowest two modes are:

  τₙ ≈ Cₐ · L⁴ / (EI · π · (n + ½))⁴      (continuous-beam theory)

Reference values from earlier tuning (single 1-µm filament, default actin 
parameters): τ₁ ≈ 0.498 s, τ₂ ≈ 0.065 s. Fit a two-mode exponential to the 
relaxation curve and verify both τ₁ and τ₂ match analytic — this validates 
the damping coefficients, since τ depends on both EI and the per-segment 
drag tensor.

#### Test 2 — Persistence length

- Single filament, free in space, Brownian motion on.
- After equilibration, sample over many timesteps the unit tangent vectors 
  ũₛ of every FilSegment along the filament.
- Compute the ensemble-averaged tangent autocorrelation as a function of 
  arclength s from the first segment:

    ⟨ũ₀ · ũₛ⟩ = exp(−s / Lₚ)

- Fit to extract Lₚ. For correctly-tuned actin parameters: Lₚ ≈ 15 µm.

#### Calibration workflow

The combination of Tests 1 and 2 yields three independent measurements 
(δ, τ₁, τ₂, Lₚ) that constrain the linear spring stiffness, torsional 
spring stiffness, and damping. Tuning workflow:

1. Choose target `filSegLength` (e.g. 16, 32, 64 monomers per segment).
2. Run Test 1 cantilever case → adjust torsional spring K to match δ.
3. Run Test 1 relaxation → check τ₁, τ₂; adjust drag if needed.
4. Run Test 2 → check Lₚ; iterate on K if needed.
5. Record calibrated `linearSpringK` / `torsionalSpringK` values for that 
   segment length.

Eventually this should be automatable: a `-tune <segLength>` mode that 
runs both tests in sequence and writes recommended spring constants to a 
file. For now, manual is fine — the goal is to get the measurement 
infrastructure in place.

#### Implementation shape

- New file `Benchmark.java`: owns measurement state, sampling cadence, 
  curve fitting, CSV emission.
- Two new parameter-file-driven setups (analogous to the test modes):
  - `staticDeflectionBenchmark` (with sub-flags for pinned vs cantilever, 
    force magnitude, force location)
  - `persistenceLengthBenchmark` (with equilibration time, sample count)
- Setup methods in FilSegment (or a new BenchmarkFilament class) that 
  build the appropriate filament with the appropriate boundary conditions 
  (pinned/clamped endpoints).
- Output: CSV files alongside the existing JSON frame output, with 
  per-frame measurements and a final fitted-parameter summary.
- Repurpose `ckElasticityCounter` / `ckPersistenceCounter` as the 
  measurement-cadence counters they were originally intended to be.

#### Why this is important

These benchmarks aren't optional polish. They're the regression test for 
every change to the physics: GPU port, spring-force changes, integrator 
changes, drag tensor changes. Without them, "the simulation still runs" 
is the only verification, which is much weaker than "the simulation still 
produces Lp = 15 µm and τ₁ = 0.5 s."

### Medium term: GPU Step 0 — SoA shadow arrays

Create `FloatArray xPos, yPos, zPos, xUVec, yUVec, zUVec, xYVec, yYVec, zYVec`
etc. alongside the existing `FilSegment[]` array. Write sync utilities:
- Upload: `FilSegment.theFilSegments[i].coord.x → xPos[i]` (at startup and after
  topology changes)
- Download: `xPos[i] → FilSegment.theFilSegments[i].coord.x` (at output time)

No simulation logic changes; validate round-trip losslessness in isolation.

### Medium term: GPU Step 1 — Brownian + integration kernel

Minimum viable GPU: positions stay on device between output frames. Fuse
`bForcesStart` and `moveStart` phases into one TornadoVM kernel. Add bounds
collision (`checkBugOrBoxCollision`) while there — it has no write conflicts.

Expected: converts the Sim3D 1.6× transfer-bound result into a compute-bound
5–15× speedup for this phase.

### Longer term: GPU Step 2 — motor-filament search

Once positions are GPU-resident, the O(H×S) motor-filament search has zero
transfer cost. Start with brute-force (parallel over motor heads, serial inner
loop over segments). Expected 10–30× for this phase alone.

---

## Session 2 — Myosin Visualization & Viewer Polish (May 2026)

### JSON output extended (ThreeJSWriter.java)

Frame JSON now includes three arrays:

```json
{
  "segments": [{"end1":[x,y,z], "end2":[x,y,z], "r":0.008}],
  "myosins":  [{"rod":  {"end1":[x,y,z], "end2":[x,y,z], "r":R, "invisible":bool},
                "lever":{"end1":[x,y,z], "end2":[x,y,z], "r":R},
                "motor":{"end1":[x,y,z], "end2":[x,y,z], "r":0.01,
                         "state":"NONE|ATP|ADPPi|ADP", "onFil":bool}}],
  "minifilaments": [{"end1":[x,y,z], "end2":[x,y,z], "r":R}]
}
```

- `rod.invisible` reflects `MyoRod.rodInvisible` — viewer skips rendering these
  (they are the bundled inner rods of mini-filaments, represented instead by the
  single `minifilaments` cylinder)
- `motor.state` string maps `MyoMotor.nucleotideState` byte constants
- Mini-filament rods use `MyoMiniFilament.end1/end2/radius`, kept current by
  `initialize()`

### Parameter file gotchas

The `Parameter` class uses two independent fields:
- **isActive()** — whether the parameter is applied (the `true`/`false` in the
  file). If `false`, the parameter falls back to its Java default regardless of
  the value field.
- **getValue()** — the actual value (`1.0` = boolean true, `0.0` = boolean false)

Key parameters for simulation mode (in `makeCrucible()`):
bugShapedCrucible:true:0.0;   // active, value=false → use rectangular Chamber
simOutsideBug:false:0.0;      // inactive → no Listeria created (falls back to default=false)
Setting `simOutsideBug:false:0.0` suppresses `Bug.makeListeriaBug()`.
The `bugOff` parameter does NOT control bug creation — it's a red herring.

`makeCrucible()` logic:
```java
if (bugShapedCrucible.isActive() && !simOutsideBug.isActive())
    Bug.makeABugCrucible();   // pill-shaped arena
else
    Chamber.makeABox();        // rectangular box
if (simOutsideBug.isActive())
    Bug.makeListeriaBug();     // Listeria inside box, with ActA
```

### Viewer (sim_viewer_boa.html) — current feature set

**Geometry:**
- Actin segments: magenta `#cc44bb` instanced cylinders
- Myosin rods (dimers only, non-invisible): gray `#888888` instanced cylinders
- Lever arms: light blue `#88ccff` instanced cylinders; rendered as ellipsoids
  when scaled, with minimum length enforcement to avoid flat discs
- Motor heads: orange/colored instanced spheres scaled as ellipsoids aligned to
  motor axis; colored by nucleotide state:
  - NONE: `#4444ff` (blue)
  - ATP: `#ffff00` (yellow)
  - ADPPi: `#ff9900` (orange)
  - ADP: `#ff0000` (red)
- Mini-filament rods: white `#ffffff` instanced cylinders (one per
  MyoMiniFilament, replaces individual myosin rods)

**Controls:**
- Left-drag: rotate
- Pinch (trackpad): zoom
- Shift + two-finger scroll: pan
- Shift + right-drag: pan
- Play/pause, frame scrubber, 2/10/30 fps buttons
- Display panel (top-right, collapsible): live sliders for actin/minifilament/
  motor/lever/rod scale factors — changes apply immediately without frame reload

**Biological rendering note:**
Java3D rendered actin at `actinWidth/4 = 0.002 µm` (4x reduction from true
`0.008 µm` radius). Three.js viewer now uses true biological scale with slider
override. The `/4` convention in Java3D was a visual clarity choice, not
biological.

**Motor head shape:**
Rendered as ellipsoids using `SphereGeometry` with non-uniform scale:
equatorial radius `= mo.r * motorScale`, polar radius `= equatorial * 1.5`,
oriented along motor axis via `setFromUnitVectors`.

### Workflow note
This project uses a two-Claude workflow:
- **Claude.ai Projects** (this chat): architecture, strategy, debugging,
  biological context, prompt generation
- **Claude Code**: file editing, compilation, execution

Restart Claude Code at task boundaries to avoid context bloat. The CLAUDE.md
and JOURNAL.md files carry context forward across Claude Code sessions.

---

## Session 3 — Java3D Removal Survey (May 2026)

This section is the mechanical removal map for a future Claude Code session.
Every "what file?", "what line?", "what depends on what?" question is answered
here. Read §1–§5 before touching a single file.

---

### §1 — The Pt3D Problem

**Pt3D extends javax.vecmath.Point3d** (`Pt3D.java:12`). This is the root
of the transitive vecmath reach that the prior survey missed.

`Point3d` extends `Tuple3d` from vecmath. The `x`, `y`, `z` fields that appear
to be native to Pt3D are actually inherited from `Tuple3d`. Every class in the
codebase that instantiates a Pt3D therefore transitively references vecmath
even if it has no explicit `import javax.vecmath.*` of its own.

#### Pt3D methods that use vecmath types in their signatures or bodies

| Method | vecmath involvement |
|---|---|
| `copyToVector3d(Vector3d vec3d)` line 648 | parameter type `Vector3d` |
| `static CopyToVector3d(Pt3D pt, Vector3d vec3d)` line 642 | parameter type `Vector3d` |
| `copyToPoint3d(Point3d point3d)` line 660 | parameter type `Point3d` |
| `static CopyToPoint3d(Pt3D pt, Point3d point3d)` line 654 | parameter type `Point3d` |
| `static writeVec3d(DataOutputStream ds, Vector3d vec)` line 615 | parameter type `Vector3d` |

All five methods exist only to bridge Pt3D to vecmath types needed by the
Java3D scene graph. None have callers outside of graphics methods — with one
exception: `writeVec3d` is called from `FileOps.java:855` (QK state
serialization), detailed in §3.

#### Non-graphics vecmath usage that needs replacement — across the codebase

| File | Line | Type | Used in |
|---|---|---|---|
| `Thing.java` | 34 | `Matrix3d mxToX` | `transMat()` (sim) and `updateGraphics()` in all subclasses (graphics) |
| `Thing.java` | 35 | `Matrix3d mXTox` | `transMat()` (sim) only |
| `Thing.java` | 409–410 | `mXTox.setElement()`, `mxToX.setElement()` | inside `transMat()` |

`mxToX` / `mXTox` are `Matrix3d` objects populated in `transMat()` (sim code)
and consumed exclusively in `updateGraphics()` calls across 14 subclass files
(`t3d.setRotation(mxToX)`). After Java3D removal, `updateGraphics()` is deleted
so its consumers go away. `transMat()` keeps only the `transXTox`/`transxToX`
double[][] arrays; the Matrix3d updates (lines 409–410) are deleted.

#### Strategy recommendation for Pt3D

**Option (a) is correct**: Drop `extends Point3d`, add explicit
`public double x, y, z` fields. This is a two-line change to Pt3D itself.
The five vecmath bridge methods are deleted simultaneously. Their only
non-graphics caller (`FileOps:855`) is fixed in Phase 0 as described in §4.
This is lower risk than making Pt3D a fresh standalone class because it
changes no existing method signatures — just removes the inheritance and adds
explicit fields.

The `setElement`-style Matrix3d calls in `transMat()` (Thing.java:409–410)
are straightforward deletions after the Matrix3d fields are removed.

---

### §2 — Complete File Inventory

#### 2a — Pure rendering: delete entirely (Phase D)

These files have no simulation logic. Deleting them produces no callers to
clean up in simulation files (simulation files never call into them; all
dependency flows the other way).

| File | Lines | Notes |
|---|---|---|
| `BoxOfActin_Graphics.java` | 1622 | Java3D universe, scene graph, full GUI |
| `CapturingCanvas3D.java` | 170 | Canvas3D subclass for PNG capture |
| `ExamineViewerBehavior.java` | 404 | Mouse examine behavior for 3D view |
| `ViewerBehavior.java` | 519 | Viewer behavior base |
| `ArchShape.java` | 242 | 3D arch geometry builder (SDSC sample code) |
| `SphereSection.java` | 145 | 3D sphere section geometry (SDSC sample code) |
| `InitControl.java` | 288 | Swing param panel |
| `EnvControl.java` | 263 | Swing param panel |
| `EndRatesControl.java` | 284 | Swing param panel |
| `MechControl.java` | 258 | Swing param panel |
| `GraphicsControl.java` | 258 | Swing param panel |
| `XLinkControl.java` | 276 | Swing param panel |
| `ActAControl.java` | 252 | Swing param panel |
| `MyoRatesControl.java` | 292 | Swing param panel |
| `CellShapeControl.java` | 268 | Swing param panel |
| `MiscRatesControl.java` | 263 | Swing param panel |
| `RenderControl.java` | 593 | QK playback renderer UI |

**Note on control panel files**: The `*Control.java` files reference `Env.*`
parameters and call `BoxOfActin.restartRun()` / `BoxOfActin.setPaused()`.
These calls originate only from menu handlers in `BoxOfActin_Graphics.java`
(which is also deleted). No simulation code calls into any control panel file.

**Note on RenderControl.java**: Calls `FileOps.loadQuickPicture()` and
`BoxOfActin_Graphics.updateQKBugScene()` — both sides of the dependency are
in the deleted set.

#### 2b — Simulation files: strip graphics, keep everything else

For each file: line ranges of imports to delete, fields to delete, and
graphics methods to delete are given. Everything not listed is kept.

---

**`Pt3D.java`** (Phase 0 — first)

| What | Lines | Action |
|---|---|---|
| `import javax.vecmath.*` | 7 | delete |
| `extends Point3d` | 12 | remove; add `public double x, y, z;` fields |
| `writeVec3d(DataOutputStream, Vector3d)` | 615–621 | delete |
| `CopyToVector3d(Pt3D, Vector3d)` | 642–646 | delete |
| `copyToVector3d(Vector3d)` | 648–652 | delete |
| `CopyToPoint3d(Pt3D, Point3d)` | 654–658 | delete |
| `copyToPoint3d(Point3d)` | 660–664 | delete |

After Phase 0, Pt3D has no vecmath imports or types anywhere in its API.

---

**`Thing.java`** (Phase A — after Phase 0)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 8 | delete |
| `import javax.vecmath.*` | 9 | delete |
| `Matrix3d mxToX` | 34 | delete |
| `Matrix3d mXTox` | 35 | delete |
| `mxToX = null; mXTox = null;` in `sepaku()` | 246–247 | delete |
| `mXTox.setElement(j,i,curVal)` in `transMat()` | 409 | delete |
| `mxToX.setElement(i,j,curVal)` in `transMat()` | 410 | delete |
| `boolean inGroup = false;` | 98 | delete (graphics bookkeeping) |
| `boolean graphicsMade = false;` | 99 | delete (graphics bookkeeping) |
| `BranchGroup G = new BranchGroup();` | 100 | delete |
| `TransformGroup g3d = new TransformGroup();` | 101 | delete |
| `Transform3D t3d = new Transform3D();` | 102 | delete |
| `Appearance a = new Appearance();` | 103 | delete |
| `Material m;` | 104 | delete |
| `G = null; g3d = null; t3d = null; a = null; m = null;` in `sepaku()` | 293–297 | delete |
| G.detach() guard in `removeDeadThings()` | 447–448 | delete both lines |
| `setGraphicsCapabilities()` method | 510–528 | delete entire method |
| `makeGraphics()` stub | 530 | delete |
| `updateGraphics()` stub | 531 | delete |
| `getGraphicsNode()` method | 533–537 | delete |
| `detachGraphics()` method | 539–543 | delete |

**Phase A compile checkpoint**: After Phase A, Thing.java is clean. Subclass
files still reference the deleted fields (`G`, `g3d`, `t3d`, `a`, `mxToX`)
in their `makeGraphics()`/`updateGraphics()` methods — so the full project
won't compile until Phase B strips those methods too. Phase A can be verified
by compiling Thing.java in isolation with `javac -cp .:... boxOfActin/Thing.java`.

---

**`FilSegment.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.*` | 21–26 | delete (5 lines) |
| `import javax.media.j3d.*` | 28 | delete |
| `import javax.vecmath.*` | 29 | delete |
| `Vector3d end1Vec3d, end2Vec3d, coordVec3d` | 175–177 | delete |
| `TransformGroup cylTG, plusCapTG` | 189 | delete |
| `Transform3D cylT3D, cylRot, plusCapT3D` | 190 | delete |
| All `static Color3f` fields | 196–244 | delete (19 static class-load-time Color3f) |
| All `static Material` fields | 214–237 | delete (10 Material objects) |
| All `static TransparencyAttributes` | 225–228 | delete |
| `Appearance cylApp = new Appearance()` | 232 | delete |
| All `static Appearance` fields | 234–240 | delete |
| `static ColoringAttributes` fields | 245–247 | delete |
| `LineArray xLine, yLine, zLine` | 249 | delete |
| `Shape3D xLineShape, yLineShape, zLineShape` | 250 | delete |
| `BranchGroup cylBG, coordSysG, plusCapBG` | 191 | delete |
| `Cylinder myCyl; Cone myArrow; Box plusCapBox` | 192–194 | delete |
| `boolean updateCylGraphicsFlag` | 187 | delete |
| `double renderThicken` | 188 | delete |
| `boolean coordSysOn, plusCapMarkOn` | 254–255 | delete |
| `resetGraphics()` method | 3424–3435 | delete entire method |
| `resetGraphicsAll()` method | 3437–3441 | delete entire method |
| Graphics-helper method block | ~3540–3670 | delete (cylinder construction helper methods) |
| `makeGraphics()` method | 3744–3757 | delete |
| `updateGraphics()` method | 3759–3795 | delete |
| `detachGraphics()` method | 3797–3810 | delete |

Also remove callers of `detachGraphics()` and `resetGraphics()` within the
file: e.g., `curSeg.detachGraphics()` at line 2798 (guarded block, remove
the call; check if the `if` block has other content to keep).

---

**`Monomer.java`** (Phase B — **critical: has static Java3D at class load**)

The static `Color3f`, `Material`, `Appearance`, `LineAttributes`,
`ColoringAttributes` fields at lines 37–57 initialize Java3D objects when
the `Monomer` class is first loaded. This is a class-load-time blocker even
before any `-r` flag can be checked.

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.geometry.*` | 4–6 | delete |
| `import javax.media.j3d.*` | 7 | delete |
| `import javax.vecmath.*` | 8 | delete |
| All `static Color3f` fields | 37–42 | delete (6 class-load static Color3f) |
| All `static ColoringAttributes` | 43–46 | delete (4 static, class-load) |
| All `static Material` fields | 47–52 | delete (6 static Materials, class-load) |
| `static Appearance` fields | 53–56 | delete |
| `static LineAttributes monLineAttributes` | 57 | delete |
| `boolean graphicsIn/Initialized/cofilinMark/tropoMark/plusCapMark` | 58–62 | delete |
| `BranchGroup monHelixG, monSphereG` | 63–64 | delete |
| `LineArray theMonLine; Shape3D monShape; Sphere monSphere; Box capBox` | 65–68 | delete |
| `TransformGroup monTG` | 69 | delete |
| `Transform3D monT3D` | 70 | delete |
| `Vector3d monVec3D` | 71 | delete |
| `BranchGroup cofilinMarkBG, tropoMarkBG, plusCapMarkBG` | 72–74 | delete |
| `Transform3D rotT3D; Matrix3d rotMat; Matrix3d curMat` | 77–79 | delete |
| All `Monomer()` constructor graphics calls | in constructors | remove `addGraphics()` and `initializeGraphics()` calls |
| `initializeGraphics()` method | ~370+ | delete |
| `updateGraphics(FilSegment, Pt3D, Pt3D, boolean)` | 491–544 | delete |
| `addGraphics(FilSegment)` | 546–554 | delete |
| `addGraphics(BranchGroup)` | 556–563 | delete |
| `detachGraphics()` | 565–604 | delete |
| `resetGraphics()` | anywhere | delete |

After Phase B, `monVec3D` is gone. The `FileOps.java:855` QK write is handled
in Phase C (replace with direct `Pt3D` write of the monomer center position).

---

**`Bug.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 16–17 | delete |
| `import javax.media.j3d.*` | 19 | delete |
| `import javax.vecmath.*` | 20 | delete |
| Graphics field declarations (Vector3d coordVec3d, Transform3D t3d local, etc.) | near top of class | delete |
| `makeGraphics()` method | 796–1011 | delete (~215 lines) |
| `updateGraphics()` method | 1012+ | delete |
| `copyToVector3d` calls in updateGraphics | 1013, 1017 | gone with the method |

---

**`Chamber.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 15–17 | delete |
| `import javax.media.j3d.*` | 19 | delete |
| `import javax.vecmath.*` | 20 | delete |
| `makeGraphics()` method | 199–357 | delete (~158 lines) |
| `updateGraphics()` method | 359+ | delete |

---

**`Crucible.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.BranchGroup` | 3 | delete |
| `BranchGroup infoG;` field | 32 | delete |
| `appearanceChange()` method | 219–222 | delete (calls `updateGraphics()`) |
| `updateTextInfoState()` method | 224–229 | delete (uses `infoG.detach()`, `g3d.addChild()`) |

Note: `BranchGroup infoG` is declared but not initialized inline (no
`new BranchGroup()`) so it is NOT a class-load-time trigger. However, the
`updateTextInfoState()` method would NPE if called before `infoG` is set. Just
delete both the field and both methods.

---

**`ProteinNode.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 14–17 | delete |
| `import javax.media.j3d.*` | 18 | delete |
| `import javax.vecmath.*` | 19 | delete |
| Graphics field declarations (Vector3d coordVec3d, etc.) | near top | delete |
| `makeGraphics()` method | 754–848 | delete (~94 lines) |
| `updateGraphics()` method | 850+ | delete |

---

**`StickyNode.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 6–16 | delete (11 specific imports) |
| `import javax.vecmath.Color3f, Vector3d` | 17–18 | delete |
| `import com.sun.j3d.utils.geometry.Sphere` | 20 | delete |
| Graphics fields (Vector3d coordVec3d, etc.) | near top | delete |
| `setGraphicsCapabilities()` override | 366–379 | delete |
| `makeGraphics()` method | 381–458 | delete |
| `updateGraphics()` method | 460+ | delete |

---

**`FillNode.java`** (Phase B — **simulation file, not pure rendering**)

FillNode has real simulation logic (`calculateProperties()`, `moveThing()`,
`step()`, `rdmPtInside()`, `fillCellWithSpheres()`, `addFillNodeToCell()`).
Strip graphics only; keep the simulation methods.

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 6–15 | delete |
| `import javax.vecmath.Color3f, Vector3d` | 16–17 | delete |
| `import com.sun.j3d.utils.geometry.Sphere` | 19 | delete |
| `static Color3f ambientC/diffuseC/specularC/emissiveC` | 29–32 | delete (class-load-time) |
| `static float shiny` | 33 | delete |
| `makeGraphics()` method | 73–101 | delete |
| `updateGraphics()` method | 103–110 | delete |

Keep: `removeAll()` (line 69), `calculateProperties()` (43), `moveThing()` (58),
`step()` (64), `rdmPtInside()` (118), `fillCellWithSpheres()` (132),
`addFillNodeToCell()` (140).

---

**`ActA.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 19–21 | delete |
| `import javax.media.j3d.*` | 26 | delete |
| `import javax.vecmath.*` | 27 | delete |
| `BranchGroup G = new BranchGroup();` instance field | 65 | delete |
| `boolean graphicsMade` | 68 | delete |
| G.detach() calls in `remove(ActA)` and `removeAll()` | 380, 390 | delete |
| `makeGraphics()` method | 427–452 | delete |
| `updateGraphics()` method | 454–469 | delete |
| `getGraphicsNode()` method | 470–472 | delete |

---

**`Arp23.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 21–23 | delete |
| `import javax.media.j3d.*` | 27 | delete |
| `import javax.vecmath.*` | 28 | delete |
| `BranchGroup G = new BranchGroup();` instance field | 66 | delete |
| `Transform3D t3d = new Transform3D();` | 68 | delete |
| `Vector3d coordVec3d = new Vector3d();` | 70 | delete |
| `boolean graphicsMade` | 73 | delete |
| `setGraphicsCapabilities()` override | 345–363 | delete |
| `makeGraphics()` method | 364–402 | delete |
| `updateGraphics()` method | 404–422 | delete |
| `getGraphicsNode()` method | 424–426 | delete |

---

**`FilLink.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 18–20 | delete |
| `import javax.media.j3d.*` | 26 | delete |
| `import javax.vecmath.*` | 27 | delete |
| `BranchGroup G = new BranchGroup();` instance field | 64 | delete |
| `boolean graphicsMade` | 67 | delete |
| G.detach() call in remove method | 398 | delete |
| `makeGraphics()` method | 471–493 | delete |
| `updateGraphics()` method | 495–512 | delete |
| `getGraphicsNode()` | 511–512 block | delete |

---

**`NodeLink.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 13–15 | delete |
| `import javax.media.j3d.*` | 21 | delete |
| `import javax.vecmath.*` | 22 | delete |
| `BranchGroup G = new BranchGroup();` instance field | 59 | delete |
| `boolean graphicsMade` | 62 | delete |
| G.detach() calls | 255 | delete |
| `makeGraphics()` method | 327–349 | delete |
| `updateGraphics()` method | 351+ | delete |
| `getGraphicsNode()` | ~368–370 | delete |

---

**`MyoFilLink.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 2–7 | delete |
| `import javax.vecmath.Color3f, Point3d` | 8–9 | delete |
| `BranchGroup G = new BranchGroup();` instance field | 52 | delete |
| `LineArray myoLine; Shape3D myoShape;` | 53–54 | delete |
| `boolean graphicsMade` | 55 | delete |
| `G = null; myoLine = null; myoShape = null;` in `sepaku()` | 81–83 | delete |
| G.detach() call in `removeAll()` | 295 | delete |
| `makeGraphics()` method | 312–334 | delete |
| `updateGraphics()` method | 336–344 | delete |
| `getGraphicsNode()` method | 346–350 | delete |

---

**`MyoRod.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 3–10 | delete |
| `import javax.vecmath.TransparencyAttributes, Color3f, Vector3d` | 10–12 | delete |
| `import com.sun.j3d.utils.*` | 14–15 | delete |
| Graphics field declarations (cylTG, cylT3D, coordVec3d, etc.) | near top of class | delete |
| `makeGraphics()` method | 234–254 | delete |
| `updateGraphics()` method | 257+ | delete |

---

**`MyoLever.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 3–9 | delete |
| `import javax.vecmath.TransparencyAttributes, Color3f, Vector3d` | 10–12 | delete |
| `import com.sun.j3d.utils.*` | 14–15 | delete |
| Graphics field declarations | near top | delete |
| `makeGraphics()` method | 232–252 | delete |
| `updateGraphics()` method | 255+ | delete |

---

**`MyoMotor.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.*` | 3–9 | delete |
| `import javax.vecmath.Color3f, Vector3d` | 10–11 | delete |
| `import com.sun.j3d.utils.*` | 13–15 | delete |
| Graphics field declarations (sphT3D, coordVec3d, etc.) | near top | delete |
| `makeGraphics()` method | 507–539 | delete |
| `updateGraphics()` method | 542+ | delete |

---

**`MyoMiniFilament.java`** (Phase B)

| What | Lines | Action |
|---|---|---|
| `import com.sun.j3d.utils.*` | 14–17 | delete |
| `import javax.media.j3d.*` | 18 | delete |
| `import javax.vecmath.*` | 19 | delete |
| Graphics field declarations (cylT3D, coordVec3d, etc.) | near top | delete |
| `makeGraphics()` method | 526–540 | delete |
| `updateGraphics()` method | 542+ | delete |

---

**`Env.java`** (Phase C)

`Env` is loaded very early — before any `begin()` is called. Its static field
initializers run at JVM class-load time. These are the earliest class-load-time
Java3D triggers in the codebase.

| What | Lines | Action |
|---|---|---|
| `import javax.vecmath.Color3f` | 8 | delete |
| `import javax.vecmath.Point3d` | 9 | delete (unused — no Point3d field in class body) |
| `static Color3f dumC = new Color3f(1.0f, 0f, .2f)` | 56 | delete (unused) |
| `static final Color3f universeColor3f = ...` | 886 | delete |
| `static final Color universeColor = universeColor3f.get()` | 888 | replace with `static final Color universeColor = Color.BLACK` |
| `static final Color3f headlightColor` | 889 | delete (only used in BoxOfActin_Graphics, which is deleted) |
| `static final Color3f cellColor3f` | 891 | delete (only used in graphics files) |
| `static final Color cellColor = cellColor3f.get()` | 897 | replace with literal `java.awt.Color` if any non-graphics file uses it; otherwise delete |
| `static final Color3f nodeColor3f` | 893 | delete |
| `static final Color3f membraneColor3f` | 894 | delete |
| `static final Color3f membraneActivatorColor3f` | 895 | delete |
| `static final Color3f parRparCColor3f` | 896 | delete |
| `static final Color3f controlBackColor3f` | 903 | delete |
| `static final Color controlBackColor = controlBackColor3f.get()` | 904 | delete (only used in *Control.java files, which are deleted) |

**Note**: `Env.controlForeColor` is also used only by `*Control.java` files.
Check for it and delete. After deleting all control panel files in Phase D,
any remaining `controlForeColor`/`controlBackColor` references in Env.java
are dead and should be deleted.

---

**`FileOps.java`** (Phase C)

| What | Lines | Action |
|---|---|---|
| `import javax.media.j3d.Transform3D` | 12 | delete |
| `import javax.vecmath.Matrix4d` | 17 | delete |
| `saveView(Transform3D viewTrans)` method | 322–334 | delete |
| `readView(String viewFileStr)` method | 336–358 | delete |
| `Pt3D.writeVec3d(ds, mon.monVec3D)` at line 855 | 855 | replace — see below |

**Fixing line 855**: After Phase B strips Monomer graphics, `monVec3D` (a
`Vector3d`) is gone. Replace the QK write with a direct `Pt3D.writePt3D()`
using a new `Pt3D monCenter` field added to `Monomer` in Phase B. That field
is populated wherever `monVec3D` was populated (in the old
`updateGraphics()` method — i.e., by copying the monomer center from the
filament geometry). Specifically: when writing QK state, the monomer center
is the position along the filament helix. The simplest replacement is to
write a sentinel (three large floats, like `farfarAway`) when monomer graphics
are disabled, and write the computed center otherwise. In practice, monomer
positions in the QK file are only used when loading QK for rendering — since
rendering is now Three.js, QK monomer positions can be deprecated or kept as
a raw float triple without the `Vector3d` type.

---

**`BoxOfActin.java`** (Phase C)

| What | Lines | Action |
|---|---|---|
| `static BoxOfActin_Graphics boaGraphics;` | 16 | delete |
| `if (!Env.remote) { boaGraphics = ...; boaGraphics.initialize(); ... }` | 79–85 | delete entire `if (!Env.remote)` block |
| `if (!Env.remote) { boaGraphics.updateBugScene(); }` | 275 | delete |
| `boaGraphics.rotateViewY()` | 360, 524, 537–539 | delete |
| `boaGraphics.updateBugScene()` | 525, 539 | delete |
| `boaGraphics.theCanvas.firstRender()` guard | 533 | delete/simplify surrounding `if` |
| `boaGraphics.captureImage()` | 543 | delete |
| `boaGraphics.waitOnCapture()` | 544 | delete |
| `if (!Env.remote) { Thing.theBox.G.detach(); }` | 754 | delete |
| `if (!Env.remote & Env.simOutsideBug...) { Thing.lmBug.G.detach(); }` | 755 | delete |
| `if (!Env.remote) { boaGraphics.canvasSizeCurrent()... boaGraphics.updateBugScene()... }` | 765–775 | delete entire block |
| `FillNode.removeAll()` in `restartRun()` | 743 | keep — FillNode is a sim class |
| `boaGraphics.pausedMenuItem.setState(...)` in `setPaused()`/`setRunning()` | 783–785, 791–793 | delete |
| `startUpdateTimer()` method | 796–804 | delete (drives control panel updates) |

Note: `restartRun()` body (lines 740–763) is pure simulation reset and stays.

---

**`BoxOfActin.java` (top-level, project root)** — check if this is a separate
file from `boxOfActin/BoxOfActin.java`:
The file listing shows both `BoxOfActin.java` (root) and
`boxOfActin/BoxOfActin.java`. The root `BoxOfActin.java` is the entry point
with `main()`. Its content may differ. Confirm by reading it before Phase C.

---

#### 2c — Clean files: no changes needed

These files have zero `javax.media.j3d`, `com.sun.j3d`, or `javax.vecmath`
imports. No action required.

`Myosin.java`, `MyosinDimer.java`, `MyosinFixed.java`, `AnchorNode.java`,
`StaticFilSegment.java`, `Barrier.java`, `CollisionEvent.java`, `NameValue.java`,
`Parameter.java`, `ParamGui.java`, `RunTimer.java`, `Stat.java`,
`ThreadSet.java`, `ThreeJSWriter.java`, `Mesh.java`, `UCircRnd.java`,
`ValueTracker.java`, `BareButton.java`, `CheckboxMenu.java`,
`CheckboxMenuListener.java`, `InertGuiHead.java`, `HistoPlotPanel.java`,
`HistogramPlus.java`, `HistogramPlus2.java`, all `infoCCD/*.java`, all
`ec/util/*.java`, all `edu/cornell/.../*.java`.

---

### §3 — Caller Graph

#### makeGraphics / updateGraphics / detachGraphics / getGraphicsNode / setGraphicsCapabilities

All call sites in the codebase:

| Call site | File:Line | Guard | Action |
|---|---|---|---|
| `Thing.theThings[i].getGraphicsNode()` | BoxOfActin_Graphics.java:466 | inside class being deleted | gone with Phase D |
| `curThing.getGraphicsNode()` | BoxOfActin_Graphics.java:486 | inside class being deleted | gone with Phase D |
| `curFL.getGraphicsNode()` | BoxOfActin_Graphics.java:498 | inside class being deleted | gone with Phase D |
| `curNL.getGraphicsNode()` | BoxOfActin_Graphics.java:508 | inside class being deleted | gone with Phase D |
| `curActA.getGraphicsNode()` | BoxOfActin_Graphics.java:521 | inside class being deleted | gone with Phase D |
| `curArp.getGraphicsNode()` | BoxOfActin_Graphics.java:534 | inside class being deleted | gone with Phase D |
| `curThing.updateGraphics()` | BoxOfActin_Graphics.java:488 | inside class being deleted | gone with Phase D |
| `curFL.updateGraphics()` | BoxOfActin_Graphics.java:500 | inside class being deleted | gone with Phase D |
| `curNL.updateGraphics()` | BoxOfActin_Graphics.java:512 | inside class being deleted | gone with Phase D |
| `curActA.updateGraphics()` | BoxOfActin_Graphics.java:524 | inside class being deleted | gone with Phase D |
| `curArp.updateGraphics()` | BoxOfActin_Graphics.java:537 | inside class being deleted | gone with Phase D |
| `theThings[i].G.detach()` | Thing.java:448 | inside `removeDeadThings()` | delete in Phase A |
| `theThings[i].graphicsMade` | Thing.java:447 | inside `removeDeadThings()` | delete in Phase A |
| `Thing.theBox.G.detach()` | BoxOfActin.java:754 | `if (!Env.remote)` | delete in Phase C |
| `Thing.lmBug.G.detach()` | BoxOfActin.java:755 | `if (!Env.remote)` | delete in Phase C |
| `Thing.theBox.G.detach()` | BoxOfActin_Graphics.java:606 | inside `clearForRun()` | gone with Phase D |
| `Thing.theBox.G.detach()` | BoxOfActin_Graphics.java:623 | inside `clearForRender()` | gone with Phase D |
| `rmMe.G.detach()` | FilLink.java:398 | inside `removeFilLink()` | delete in Phase B |
| `rmMe.graphicsMade` | FilLink.java:398 | same guard | delete in Phase B |
| `theMyoFilLinks[i].G.detach()` | MyoFilLink.java:295 | inside `removeAll()` | delete in Phase B |
| `theMyoFilLinks[i].graphicsMade` | MyoFilLink.java:295 | same guard | delete in Phase B |
| `theActAs[i].G.detach()` | ActA.java:390 | inside `removeAllActAs()` | delete in Phase B |
| `rmMe.G.detach()` | ActA.java:380 | inside `remove(ActA)` | delete in Phase B |
| `FilSegment.theFilSegments[i].detachGraphics()` | BoxOfActin_Graphics.java:565 | inside class being deleted | gone with Phase D |
| `Monomer.theMonomers[x].detachGraphics()` | BoxOfActin_Graphics.java:584 | inside class being deleted | gone with Phase D |
| `curMon.addGraphics(bugState)` | BoxOfActin_Graphics.java:592 | inside class being deleted | gone with Phase D |
| `curSeg.detachGraphics()` | FilSegment.java:2798 | inside `removeAll()` | delete in Phase B |
| `FilSegment.resetGraphicsAll()` | BoxOfActin_Graphics.java:1280/1287/1294 | inside itemStateChanged | gone with Phase D |
| `infoG.detach()` | Crucible.java:225 | inside `updateTextInfoState()` | delete in Phase B |
| `g3d.addChild(infoG)` | Crucible.java:227 | inside `updateTextInfoState()` | delete in Phase B |
| `theBox.updateGraphics()` | Crucible.java:221 | `if (Env.paused)` | delete in Phase B |
| `Arp23.getGraphicsNode()` | BoxOfActin_Graphics.java | inside class being deleted | gone with Phase D |
| `FileOps.saveView(Transform3D)` | BoxOfActin_Graphics.java:424 | inside `saveAsView()` | gone with Phase D |
| `FileOps.readView(String)` | BoxOfActin_Graphics.java:414 | inside `setView()` | gone with Phase D |

#### Pt3D vecmath bridge method call sites

| Call site | File:Line | Context | Action |
|---|---|---|---|
| `coord.copyToVector3d(coordVec3d)` | FilSegment.java:3760 | in `updateGraphics()` | gone with Phase B |
| `start.copyToVector3d(monVec3D)` | Monomer.java:511 | in `updateGraphics()` | gone with Phase B |
| `loc.copyToVector3d(monVec3D)` | Monomer.java:521, 542 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | MyoRod.java:263 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | MyoMiniFilament.java:544 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | StickyNode.java:475 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | MyoLever.java:256 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | ProteinNode.java:851 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | MyoMotor.java:543 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | Bug.java:1013 | in `updateGraphics()` | gone with Phase B |
| `curDTipLoc.copyToVector3d(coordVec3d)` | Arp23.java:406 | in `updateGraphics()` | gone with Phase B |
| `coord.copyToVector3d(coordVec3d)` | FillNode.java:92, 105 | in `makeGraphics/updateGraphics` | gone with Phase B |
| `Pt3D.writeVec3d(ds, mon.monVec3D)` | FileOps.java:855 | QK state write — **non-graphics** | fix in Phase C (see §2b) |

#### BoxOfActin.java graphics instantiation call sites

| Call site | Lines | Action |
|---|---|---|
| `boaGraphics = new BoxOfActin_Graphics()` | 80 | delete (in `!Env.remote` block) |
| `boaGraphics.initialize()` | 81 | delete |
| `boaGraphics.buildUniverse()` | 82 | delete |
| `boaGraphics.showFrame()` | 83 | delete |
| `boaGraphics.setView(viewFileStr)` | 84 | delete |
| `boaGraphics.updateBugScene()` | 275, 525, 539 | delete |
| `boaGraphics.rotateViewY()` | 360, 537 | delete |
| `boaGraphics.captureImage(boaGraphics.theCanvas)` | 543 | delete |
| `boaGraphics.waitOnCapture(boaGraphics.theCanvas)` | 544 | delete |
| `boaGraphics.canvasSizeCurrent()` | 767, 771 | delete |
| `boaGraphics.makeCanvasSizeCurrent()` | 767 | delete |
| `boaGraphics.updateCanvasSizeParams()` | 769 | delete |
| `BoxOfActin_Graphics.updateBugScene()` | 772 | delete |
| `BoxOfActin_Graphics.updateAllControls()` | 773 | delete |
| `BoxOfActin_Graphics.theCanvas.resetFileCt()` | 774 | delete |
| `boaGraphics.pausedMenuItem.setState(...)` | 783, 792 | delete |
| `boaGraphics.runMenuItem.setState(...)` | 784, 791 | delete |
| `boaGraphics.updateAllControls()` | 800 | delete (in `startUpdateTimer()`) |
| `boaGraphics.rotateViewY(...)` | 538 | delete |

---

### §4 — Revised Phase Plan

#### Phase 0 — Pt3D surgery

**Goal**: Remove the `extends Point3d` inheritance so Pt3D no longer depends
on vecmath for its `x`/`y`/`z` storage.

**Files touched**: `Pt3D.java` only.

**Changes**:
1. Remove `import javax.vecmath.*` (line 7)
2. Change `public class Pt3D extends Point3d {` → `public class Pt3D {`
3. Add `public double x, y, z;` as the first field declarations in the class body
4. Remove all five vecmath bridge methods (`copyToVector3d`, `CopyToVector3d`,
   `copyToPoint3d`, `CopyToPoint3d`, `writeVec3d`)

**Compile checkpoint**: `javac --class-path . boxOfActin/Pt3D.java` with no
vecmath jar. Other files still fail — expected.

**Bail-out criterion**: Any compile error *inside* Pt3D.java after the change
(e.g., an inherited `Point3d` method call remaining in the body). Audit for
stray `Point3d` method calls before proceeding.

**Estimated time**: 30 minutes.

---

#### Phase 1 — Env statics + Thing.java + class-load blockers

**Goal**: Make the JVM able to load `BoxOfActin` with no Java3D jars on the
classpath. This phase removes all static-initializer-time Java3D triggers —
the class-load blocker that the prior Phase A checkpoint missed entirely.

**Files touched**: `boxOfActin/Env.java`, `boxOfActin/Thing.java`,
`boxOfActin/FilSegment.java` (static declarations only),
`boxOfActin/Monomer.java` (static declarations only),
`boxOfActin/FillNode.java` (static declarations only).

**Changes per file**:

*Env.java*:
- Delete `import javax.vecmath.Color3f` (line 8) and `import javax.vecmath.Point3d`
  (line 9, unused)
- Delete `static Color3f dumC = new Color3f(1.0f, 0f, .2f)` (line 56)
- Delete `static final Color3f universeColor3f` (line 886); replace
  `static final Color universeColor = universeColor3f.get()` (line 888) with
  `static final Color universeColor = Color.BLACK`; add `import java.awt.Color`
- Delete remaining `static final Color3f` fields at lines 889–904:
  `headlightColor3f`, `cellColor3f`, `nodeColor3f`, `membraneColor3f`,
  `controlBackColor3f`, and any others in that block
- Delete all QK fields (lines 192–239): `qkFilePath`, `fromQKFilePath`,
  `fromQKFileName`, `fromQKStatePath`, `toQKFile`, `fromQKFile`,
  `loadingQKFile`, `toQKFileInterval_init`, `Parameter toQKFileInterval`,
  and the commented-out `toQKFilePath`/`toQKFileName` lines

*Thing.java*:
- Delete `import javax.media.j3d.*` (line 8) and `import javax.vecmath.*` (line 9)
- Delete `Matrix3d mxToX = new Matrix3d()` (line 34) and
  `Matrix3d mXTox = new Matrix3d()` (line 35)
- Delete graphics instance fields: `BranchGroup G = new BranchGroup()` (line 100),
  `TransformGroup g3d` (line 101), `Transform3D t3d` (line 102),
  `Appearance a` (line 103), `Material m` (line 104)
- Delete graphics methods: `setGraphicsCapabilities()` (lines 510–528),
  `makeGraphics()` (line 530), `updateGraphics()` (line 531),
  `getGraphicsNode()` (lines 533–537), `detachGraphics()` (lines 539–543)
- Delete `G.detach()` guard in `removeDeadThings()` (lines 447–448)

*FilSegment.java* (static declarations only — leave instance fields and
method bodies for Phase 2):
- Delete `import com.sun.j3d.utils.*` (lines 21–26), `import javax.media.j3d.*`
  (line 28), `import javax.vecmath.*` (line 29)
- Delete all 19 static `Color3f`, 10 static `Material`, static `Appearance`,
  static `TransparencyAttributes` at lines 196–258
- Leave `Vector3d end1Vec3d/end2Vec3d/coordVec3d`, `TransformGroup cylTG`,
  and all graphics methods for Phase 2

*Monomer.java* (static declarations only — leave instance fields and method
bodies for Phase 2):
- Delete `import com.sun.j3d.utils.geometry.*` (lines 4–6),
  `import javax.media.j3d.*` (line 7), `import javax.vecmath.*` (line 8)
- Delete all static `Color3f`, `ColoringAttributes`, `Material`, `Appearance`,
  `LineAttributes` at lines 37–57
- Delete `static int monRenderCt = 0` (line 16)
- Delete `Vector3d monVec3D` field (line 71) — **clean delete, no replacement**
  (QK is gone; see §5 Addendum §1a)
- The `static Monomer plusGhost = new Monomer()` (line 88) **must remain**.
  After removing the static Color3f fields, audit the default `Monomer()`
  constructor: if it calls `initializeGraphics()`, add a no-op guard so the
  constructor does not crash without Java3D at class-init time. The goal is
  that constructing the `plusGhost` sentinel cannot throw.
- Leave all other instance fields and method bodies for Phase 2

*FillNode.java* (static declarations only):
- Delete static `Color3f ambientC`, `diffuseC`, `specularC`, `emissiveC`
  (lines 29–32)
- Leave `makeGraphics()`, `updateGraphics()`, and all simulation methods
  for Phase 2

**Compile checkpoint after Phase 1**:
```
javac --class-path . boxOfActin/Env.java boxOfActin/Thing.java boxOfActin/Pt3D.java boxOfActin/FilSegment.java boxOfActin/Monomer.java boxOfActin/FillNode.java
java -cp . boxOfActin.BoxOfActin -h    # or some flag that exits immediately
```
With **no Java3D jars** in the classpath. The `javac` line must succeed
cleanly for all six files. The `java` line tests whether the class-load-time
blockers in these specific files are resolved: if the JVM can invoke `-h`
(which loads Env early) without a `NoClassDefFoundError` on a vecmath or j3d
type from one of the six Phase 1 files, Phase 1 is sound.

The `java` line may still fail on Java3D references in *other* files not yet
touched — that is expected and not a bail-out signal. The bail-out condition
is specifically failure caused by the six Phase 1 files.

**Bail-out criterion**: If `javac --class-path .` fails on any of the six
Phase 1 files after removing their static initializers, stop and report the
exact error. There is a missed Java3D reference in a static context. Do not
proceed to Phase 2.

**Estimated time**: 1.5–2 hours.

---

#### Phase 2 — Simulation file strips, batched

**Goal**: Remove all Java3D instance fields, instance-field initializers, and
graphics method bodies from the 21 simulation files in §2b; delete QK-related
methods from simulation files.

**Files touched**: All 21 simulation files from §2b. FilSegment, Monomer, and
FillNode have partial work from Phase 1; Phase 2 handles their remaining
instance fields and method bodies.

**Key changes from the prior Phase B plan**:
- `Monomer.monVec3D` (`Vector3d`) is already deleted in Phase 1.
  **No `Pt3D monCenter` field is added** — QK is deleted entirely, not
  preserved. The FileOps:855 `writeVec3d` call disappears with
  `takeQuickPicture()` in Phase 3.
- Delete QK methods from simulation files:
  - `Monomer.setFromQKInfo()` (lines 520–533) — references deleted `monVec3D`
    and Java3D graphics; delete entirely
  - `FilSegment.setFromQKInfo()` (lines 328–334) — delete
  - `FilSegment.end2LinkThingNumber` field (line 125) — delete
  - `FilLink` QK constructor `public FilLink(Pt3D, Pt3D)` (line 69) — delete
  - `FilLink.setPtsFromQKFile()` (line 438) — delete
  - `Arp23` QK constructor `public Arp23(Pt3D, Pt3D)` (lines 76–79) — delete
  - `Arp23.setPtsFromQKFile()` (line 337) — delete
  - `NodeLink` QK constructor `public NodeLink(Pt3D, Pt3D)` (line 64) — delete
  - `NodeLink.setPtsFromQKFile()` (line 294) — delete
  - `MyoFilLink.setPtsFromQKFile()` (line 302) and QK comment (line 64) — delete
  - `ProteinNode.setFromQK()` (line 321) — delete
  - `ProteinNode` constructors (lines 88, 100): both have a `boolean fromQKFile`
    parameter used to skip graphics init; after graphics removal this flag is
    meaningless — simplify to a single constructor without the parameter

**Compile-and-load checkpoints**: After every 3–5 files:
```
javac --class-path . boxOfActin/<edited_file>.java
```
No Java3D jars. Uses existing `.class` files as classpath context. Each
stripped file must compile cleanly before moving to the next batch.

**Bail-out criterion**: If a stripped simulation file fails to compile with no
Java3D jars on the classpath, stop and identify the missed reference before
editing the next file.

**Estimated time**: 6–8 hours (21 files; static-field surprises may surface;
conservative estimate to account for per-file investigation).

---

#### Phase 3 — Cross-file cleanup

**Goal**: Remove all remaining Java3D/vecmath references from FileOps and
BoxOfActin; delete the entire QK code block from FileOps.

**Files touched**: `boxOfActin/FileOps.java`, `boxOfActin/BoxOfActin.java`,
root `BoxOfActin.java`.

**FileOps.java changes**:
- Delete `import javax.media.j3d.Transform3D` (line 12) and
  `import javax.vecmath.Matrix4d` (line 17)
- Delete `saveView()` (lines 322–334) and `readView()` (lines 336–358) — no
  replacement; Three.js viewer manages camera state
- Delete the entire QK block (full catalog in §5 Addendum §1a):
  - `QKFileFilter` inner class (lines 203–222)
  - `getQKFileList()` (lines 238–239)
  - `takeQuickPicture()` (lines 772–912)
  - `writeFilLinks()` helper (lines 914–928)
  - `writeMyosins()` helper (lines 930–957) — dead code, never called anywhere
  - `loadQuickPicture()` (lines 959–1173)
  - `loadQuickState()` (lines 1175–1299)

Note: `loadQuickState()` at line 1177 calls `BoxOfActin_Graphics.clearForRun()`.
That reference vanishes when `loadQuickState()` is deleted — no separate action.

**BoxOfActin.java (boxOfActin/) changes**:
- Delete `static BoxOfActin_Graphics boaGraphics` field (line 16)
- Delete `if (!Env.remote) { boaGraphics = new BoxOfActin_Graphics(); ... }`
  block (lines 79–85)
- Delete QK fields: `static int qkCounter` (line 52),
  `static int qkFilesMadeCounter` (line 57)
- Delete `-ic` flag parsing (lines 149–152), `-qk` parsing (lines 230–243),
  `-qkN` parsing (lines 246–252)
- Delete help text for `-ic`/`-qk`/`-qkN` (lines 112–117)
- Delete QK subfolder creation in `-o` handler (lines 183–187) and
  `-outMade` handler (lines 201–205)
- Delete `qkCounter` initialization (line 349)
- Simplify loop condition (line 357): remove `| Env.fromQKFile` → condition
  becomes `while (Env.simulationTime <= (Env.runTime.getValue() + Env.runBump))`
- Delete `Env.fromQKFile = false` (line 366)
- Delete `qkCounter++` (line 508)
- Delete both QK write blocks: lines 550–555 and lines 645–650
- Delete `loadQuickState` call block (lines 660–661)
- Delete `boaGraphics.*` calls in `restartRun()` (lines 754–755, 765–775)
- Delete `startUpdateTimer()` method and its call site
- Delete `import javax.swing.Timer` (line 8)

**Compile checkpoint after Phase 3**:
```
javac --class-path . boxOfActin/FileOps.java boxOfActin/BoxOfActin.java boxOfActin/Env.java boxOfActin/Thing.java boxOfActin/Pt3D.java
```
These five files must compile cleanly with no Java3D jars. Any residual
failure in a non-Phase-4 file is a missed deletion — stop and report.

**Bail-out criterion**: Any unresolved reference in FileOps or BoxOfActin that
is *not* a reference to a Phase 4 file (i.e., not `BoxOfActin_Graphics.*`)
means a missed QK or Java3D removal. Stop before Phase 4.

**Estimated time**: 2–3 hours.

---

#### Phase 4 — Pure rendering file deletion

**Goal**: Delete the 18 pure rendering files. After this phase, a full compile
with no Java3D jars must succeed — this is the integration test for the entire
removal effort.

**Files deleted**: `BoxOfActin_Graphics.java`, `CapturingCanvas3D.java`,
`ExamineViewerBehavior.java`, `ViewerBehavior.java`, `ArchShape.java`,
`SphereSection.java`, `InitControl.java`, `EnvControl.java`,
`EndRatesControl.java`, `MechControl.java`, `GraphicsControl.java`,
`XLinkControl.java`, `ActAControl.java`, `MyoRatesControl.java`,
`CellShapeControl.java`, `MiscRatesControl.java`, `RenderControl.java`.

(17 named above; cross-check §2a for the complete 18-file list.)

**Compile checkpoint after Phase 4**:
```
javac --class-path . boxOfActin/*.java *.java infoCCD/*.java ec/util/*.java
```
With **no Java3D jars** on the classpath. Zero errors = Java3D fully removed.

**JVM load test**:
```
java -cp . BoxOfActin -h
java -cp . -Xmx800M BoxOfActin -r -pf ParameterFiles/boa10-64Seg
```
First: should print help and exit cleanly. Second: should run headless with
no `ClassNotFoundException` or `NoClassDefFoundError`.

**Bail-out criterion**: Any compile error in a non-deleted file indicates a
missed reference not in §3's caller graph. Stop and audit before Phase 5.

**Estimated time**: 30 minutes (file deletion + compile + load test).

---

#### Phase 5 — Java 21 switch

**Goal**: Compile and run under Java 21, prerequisite for TornadoVM.

**Changes**:
1. Update Eclipse `.classpath` to remove `/Library/JOGLAndj3D/*.jar` entries
2. Update CLAUDE.md build instructions (remove Java3D jars; add `--enable-preview`
   and `-g` flags per TornadoVM requirements)
3. Test: `java --enable-preview -Xmx800M BoxOfActin -r -pf ParameterFiles/boa10-64Seg`

**Bail-out criterion**: Any Java source that uses API removed between Java 8
and Java 21, or that fails under `--enable-preview`. Note the exact error and
stop; do not attempt workarounds without understanding the root cause.

**Estimated time**: 30 minutes.

---

#### Phase ordering rationale

The prior plan split into Phase A (Thing.java) and Phase C (Env/FileOps/
BoxOfActin) with Phase B (simulation files) between them. This ordering was
wrong: Phase A's compile checkpoint (`javac boxOfActin/Thing.java` in
isolation) could pass while the JVM still failed to load at runtime, because
`Env`, `FilSegment`, `Monomer`, and `FillNode` all have static field
initializers that fire at class load before any `-r` flag is checked, and
those were deferred to later phases. The new Phase 1 collapses all
class-load-time blockers into one phase and verifies with a JVM load test
rather than an isolated `javac`. Phases 2–4 can then proceed without
discovering a new static-init blocker mid-flight.

---

### §5 — Risks and Unknowns

#### The static Java3D initialization problem is broader than JOURNAL.md stated

The current JOURNAL.md ("Session 2") says the blocker is specifically
`Thing.java`'s instance field declarations:
```java
BranchGroup G = new BranchGroup();
TransformGroup g3d = new TransformGroup();
```
This is incomplete. The actual class-load-time problem is worse:

1. **`Env.java` static fields** (lines 56, 886–904): `Env` is loaded first
   (every other class references it). It has `static final Color3f` and
   `static Color3f` fields that trigger vecmath loading before any simulation
   code runs.

2. **`FilSegment.java` static fields** (lines 196–258): At least 19 static
   `Color3f`, 10 static `Material`, and several static `Appearance` objects
   are initialized at class load. `FilSegment` is loaded early because
   `Thing.java` has no direct FilSegment reference, but `makeCrucible()` and
   `makeInitialThings()` reference FilSegment immediately.

3. **`Monomer.java` static fields** (lines 37–57): Six static `Color3f`, four
   `ColoringAttributes`, six `Material`, two `Appearance` objects. The class
   also has `static Monomer plusGhost = new Monomer()` (line 88) that runs
   the constructor at class-init time, which calls `initializeGraphics()` if
   `!Env.noMonomersRendered.isActive() & !Env.remote`. However, the static
   Color3f/Material fields run BEFORE the constructor, unconditionally.

4. **`FillNode.java` static fields** (lines 29–32): Four `Color3f` at class
   load.

The bottom line: even if `Thing.java`'s instance fields were behind a
`if (!Env.remote)` guard, the sim would still fail to load without vecmath.jar
because of these static initializers.

#### Instance field initializers in connector classes

`FilLink.java:64`, `NodeLink.java:59`, `MyoFilLink.java:52`, `Arp23.java:66`
all have `BranchGroup G = new BranchGroup()` as **instance** field
initializers (not static). These run when each object is constructed. In the
current codebase, any call to `new FilLink(...)` triggers `new BranchGroup()`
which triggers Java3D initialization. After removal, these fields are simply
deleted.

#### FileOps.saveView/readView persist the Java3D camera transform to disk

`FileOps.saveView()` (line 322) serializes a `Matrix4d` to a binary file.
Saved `.view` files (referenced via `-vf` flag) are **Java3D object streams**
and will be unreadable after Phase C. The `-vf` CLI flag and the `readView`
method must both be deleted (the feature becomes permanently unsupported).
The Three.js viewer already manages its own camera state in the browser.

#### Env.Point3d import is unused

`import javax.vecmath.Point3d` at `Env.java:9` — there is no `Point3d` field
or local variable anywhere in Env.java's body. It is a dead import leftover
from an older version. Delete in Phase C.

#### FillNode.removeAll() is called from BoxOfActin.restartRun() (line 743)

`FillNode` is a simulation class (not deleted in Phase D), so this call
remains after Phase D. No action needed on this call site.

#### No static {} initializer blocks found

Grep for `static {` found zero results across all simulation files. There are
no hidden static initializer blocks that would do surprise Java3D initialization
outside the static field declarations already catalogued.

#### infoCCD files do not touch Java3D

All seven `infoCCD/*.java` files (`Console`, `CopyingNotice`, `Info`,
`InfoViewer`, `ParamLoader`, `ResourceGetter`, `URLPoint`) use only Swing.
No changes required.

#### The root BoxOfActin.java vs boxOfActin/BoxOfActin.java

The file listing shows `BoxOfActin.java` at the project root AND
`boxOfActin/BoxOfActin.java`. These are different files:
the root file is the outer entry point (contains `main()`); the inner
`boxOfActin/BoxOfActin.java` contains the simulation class with `begin()`,
`doLoop()`, `parseArgs()`, etc. Both must be checked in Phase C. The root
file likely just calls `BoxOfActin.begin(args)` with minimal Java3D exposure.
Read both before editing.

#### QK file format will silently change

After Phase B removes `Monomer.monVec3D` and Phase C replaces the
`writeVec3d` call, the binary format of QK files changes at the monomer
position record. Old QK files will be unreadable by the new code and vice
versa. This is acceptable since the goal is headless JSON output, not
QK playback. Document this change in a comment near the write code.

#### Swing imports survive Phase D

After deleting all `*Control.java` and `BoxOfActin_Graphics.java`, several
Swing imports in simulation files become dead:
- `BoxOfActin.java:8`: `import javax.swing.Timer` (for `startUpdateTimer()`,
  deleted in Phase C)
- `FilSegment.java`, `Bug.java`, etc. have `import javax.swing.*` that were
  only used by GUI interaction methods. Check for and remove these in Phase B.

Swing is a standard JDK library and does not block Java 21. However, dead
imports should be cleaned up for clarity.

---

### Session 3 Addendum — Resolved Open Questions

Two facts clarified after the Session 3 survey changed the phase plan:
(1) QK files are fully replaced by Three.js JSON output — all QK code is
deleted, not preserved. (2) `Monomer.plusGhost` is a pure linked-list sentinel
whose constructor side effects are irrelevant to simulation correctness.

#### §1a — QK is deletable: complete deletion catalog

The prior §5 entry "QK file format will silently change" treated QK as a
preserve-and-adapt problem. That is now obsolete. All QK write/read/render
code paths are deleted in Phases 1–4.

**Effect on §2b Monomer.java instructions**: The prior plan said to replace
`Monomer.monVec3D` with `Pt3D monCenter` and update `FileOps.java:855`.
Both steps are cancelled. `monVec3D` is a clean delete in Phase 1. The
FileOps:855 `writeVec3d` call disappears with `takeQuickPicture()` in Phase 3.
No replacement field is added.

| File | Line(s) | Item | Action |
|---|---|---|---|
| `Env.java` | 192 | `static String qkFilePath` | DELETE (Phase 1) |
| `Env.java` | 193 | `static String fromQKFilePath` | DELETE (Phase 1) |
| `Env.java` | 194 | `static String fromQKFileName` | DELETE (Phase 1) |
| `Env.java` | 195 | `static String fromQKStatePath` | DELETE (Phase 1) |
| `Env.java` | 210 | `static boolean toQKFile` | DELETE (Phase 1) |
| `Env.java` | 211 | `static boolean fromQKFile` | DELETE (Phase 1) |
| `Env.java` | 212 | `static boolean loadingQKFile` | DELETE (Phase 1) |
| `Env.java` | 219 | `toQKFileInterval_init` constant | DELETE (Phase 1) |
| `Env.java` | 227 | `Parameter toQKFileInterval` | DELETE (Phase 1) |
| `Env.java` | 236–237 | commented-out `toQKFilePath`/`toQKFileName` | DELETE (Phase 1) |
| `FilSegment.java` | 125 | `int end2LinkThingNumber` field | DELETE (Phase 2) |
| `FilSegment.java` | 328–334 | `setFromQKInfo()` method | DELETE (Phase 2) |
| `Monomer.java` | 11 | comment "used only in drawing from QK files" | DELETE (Phase 1) |
| `Monomer.java` | 16 | `static int monRenderCt` | DELETE (Phase 1) |
| `Monomer.java` | 71 | `Vector3d monVec3D` field | DELETE (Phase 1) — clean delete, no replacement |
| `Monomer.java` | 520–533 | `setFromQKInfo()` method | DELETE (Phase 2) |
| `FilLink.java` | 69 | `public FilLink(Pt3D, Pt3D)` QK constructor | DELETE (Phase 2) |
| `FilLink.java` | 438 | `setPtsFromQKFile()` | DELETE (Phase 2) |
| `Arp23.java` | 76–79 | `public Arp23(Pt3D, Pt3D)` QK constructor | DELETE (Phase 2) |
| `Arp23.java` | 337 | `setPtsFromQKFile()` | DELETE (Phase 2) |
| `NodeLink.java` | 64 | `public NodeLink(Pt3D, Pt3D)` QK constructor | DELETE (Phase 2) |
| `NodeLink.java` | 294 | `setPtsFromQKFile()` | DELETE (Phase 2) |
| `MyoFilLink.java` | 64 | QK comment; constructor note | DELETE comment (Phase 2) |
| `MyoFilLink.java` | 302 | `setPtsFromQKFile()` | DELETE (Phase 2) |
| `ProteinNode.java` | 88, 100 | `boolean fromQKFile` constructor parameter | SIMPLIFY — remove param, merge constructors (Phase 2) |
| `ProteinNode.java` | 321 | `setFromQK()` | DELETE (Phase 2) |
| `FileOps.java` | 203–222 | `QKFileFilter` inner class | DELETE (Phase 3) |
| `FileOps.java` | 238–239 | `getQKFileList()` | DELETE (Phase 3) |
| `FileOps.java` | 772–912 | `takeQuickPicture()` | DELETE (Phase 3) |
| `FileOps.java` | 914–928 | `writeFilLinks()` helper | DELETE (Phase 3) |
| `FileOps.java` | 930–957 | `writeMyosins()` helper | DELETE (Phase 3) — **dead code, never called** |
| `FileOps.java` | 959–1173 | `loadQuickPicture()` | DELETE (Phase 3) |
| `FileOps.java` | 1175–1299 | `loadQuickState()` | DELETE (Phase 3) |
| `BoxOfActin.java` | 52 | `static int qkCounter` | DELETE (Phase 3) |
| `BoxOfActin.java` | 57 | `static int qkFilesMadeCounter` | DELETE (Phase 3) |
| `BoxOfActin.java` | 112–117 | help text `-ic`/`-qk`/`-qkN` | DELETE (Phase 3) |
| `BoxOfActin.java` | 149–152 | `-ic` flag parsing | DELETE (Phase 3) |
| `BoxOfActin.java` | 183–187 | QK subfolder creation in `-o` handler | DELETE (Phase 3) |
| `BoxOfActin.java` | 201–205 | QK subfolder creation in `-outMade` handler | DELETE (Phase 3) |
| `BoxOfActin.java` | 230–243 | `-qk` flag parsing | DELETE (Phase 3) |
| `BoxOfActin.java` | 246–252 | `-qkN` flag parsing | DELETE (Phase 3) |
| `BoxOfActin.java` | 349 | `qkCounter` initialization | DELETE (Phase 3) |
| `BoxOfActin.java` | 357 | `\| Env.fromQKFile` in loop condition | SIMPLIFY (Phase 3) — see Flag 2 below |
| `BoxOfActin.java` | 366 | `Env.fromQKFile = false` | DELETE (Phase 3) |
| `BoxOfActin.java` | 508 | `qkCounter++` | DELETE (Phase 3) |
| `BoxOfActin.java` | 550–555 | QK write block #1 in `doLoop()` | DELETE (Phase 3) |
| `BoxOfActin.java` | 645–650 | QK write block #2 in `doLoop()` | DELETE (Phase 3) |
| `BoxOfActin.java` | 660–661 | `loadQuickState()` call for `-ic` | DELETE (Phase 3) |
| `BoxOfActin_Graphics.java` | entire file | all QK GUI (`writeQKFileMenuItem`, `renderQKFileMenuItem`, `prepForQKWriting()`, `prepForQKRender()`, `prepForQKStateLoad()`, `updateQKBugScene()`) | DELETE (whole file, Phase 4) |
| `GraphicsControl.java` | 183 | `addParam(new ParamGui(Env.toQKFileInterval))` | DELETE (whole file, Phase 4) |
| `RenderControl.java` | entire file | QK rendering loop | DELETE (whole file, Phase 4) |

**Surprises found during QK search (executor must read before editing)**:

**Flag 1 — `writeMyosins()` is dead code**: `FileOps.java:930–957` defines
`writeMyosins()` which is never called anywhere in the codebase. It was
presumably intended for `takeQuickPicture()` but was never wired in. The only
grep hits are its own definition and closing brace. Delete with no callers.

**Flag 2 — `Env.fromQKFile` is a loop-termination mechanism, not just a flag**:
`BoxOfActin.java:357` — the main sim loop condition is:
```java
while (Env.simulationTime <= (Env.runTime.getValue()+Env.runBump) | Env.fromQKFile) {
```
The `|` is **bitwise OR** (not short-circuit `||`). In QK render mode,
`fromQKFile=true` kept the loop alive regardless of `simulationTime`;
`RenderControl` loaded picture files one by one, then set `Env.fromQKFile =
false` (line 366) to terminate. After QK deletion the condition becomes the
straightforward `while (Env.simulationTime <= (Env.runTime.getValue() +
Env.runBump))`. No other logic depends on the bitwise-OR behavior.

**Flag 3 — Two separate QK write blocks in `doLoop()`**: Lines 550–555 and
645–650 both call `FileOps.takeQuickPicture()`. These are two independent
trigger points within a single `doLoop()` call (likely pre-equilibration and
main sim phases). Both must be deleted independently; neither is a copy/paste
error in the existing code.

#### §1b — Monomer.plusGhost: confirmed linked-list sentinel

`Monomer.plusGhost` (declared at `Monomer.java:88` as
`static Monomer plusGhost = new Monomer()`) is the end-of-chain sentinel for
the `frontMon`/`backMon` monomer linked list. It functions as an identity
token only — the invariant is `while (curMon != Monomer.plusGhost)`. Its
position and simulation state are never read. After Phase 2 strips Java3D from
the Monomer constructor, `new Monomer()` at class-init time is safe;
`plusGhost` continues to function identically. No simulation logic depends on
`plusGhost`'s constructor side effects.

Complete reference inventory (27 sites):

| File | Lines | Context |
|---|---|---|
| `Monomer.java` | 88 | Declaration: `static Monomer plusGhost = new Monomer()` |
| `Monomer.java` | 105, 126 | Constructor: `this.frontMon = plusGhost` (list terminator) |
| `Monomer.java` | 175, 182 | Instance methods: list-end guard and assignment |
| `Monomer.java` | 264, 285 | Instance methods: `if (plusMon.frontMon != plusGhost)` |
| `FilSegment.java` | 718, 745, 757, 764, 772, 785, 792, 800, 808, 866, 868, 874 | Simulation code (filament split/removal) — **keep** |
| `FilSegment.java` | 2783 | Simulation code (biochem loop) — **keep** |
| `FilSegment.java` | 3427, 3446, 3491, 3519, 3830, 3876 | Inside graphics methods (`resetGraphics`, `updateGraphics`, etc.) — disappear when those methods are deleted in Phase 2 |
| `FileOps.java` | 841 | Inside `takeQuickPicture()` — disappears with QK deletion in Phase 3 |

After Phase 2 and Phase 3, the remaining `plusGhost` references are the 13
Monomer.java sites plus the 13 FilSegment simulation-code sites — all correct
simulation logic that is unchanged.

---

## Session 4 — Phase 0 execution: Pt3D surgery (May 2026)

### Pre-flight findings

Scanned Pt3D.java in full before editing. No method body calls any inherited
`Point3d` or `Tuple3d` method — every operation uses direct `.x`/`.y`/`.z`
field access. The commented-out `//double x,y,z;` at line 13 was the original
explicit field declaration, commented out when `extends Point3d` was added.
No reimplementation needed beyond uncommenting and adding the `public` modifier.

`writeNullVec3d` (lines 607–613 original) uses only Pt3D fields (`farfarAway.x`
etc.) — not a vecmath bridge method, kept intact. Only `writeVec3d` (which took
a `Vector3d` parameter) was deleted.

### Exact edits made

**Edit 1** — import and class declaration (lines 7–13 original):
- Removed `import javax.vecmath.*;`
- Changed `public class Pt3D extends Point3d {` → `public class Pt3D {`
- Changed `\t//double x,y,z;` → `\tpublic double x, y, z;`

**Edit 2** — `writeVec3d` method (lines 615–621 original):
- Deleted the entire method body; kept the `// ****...` separator line that
  followed it (it serves as the end-of-section marker for `writeNullVec3d`)

**Edit 3** — COPY bridge methods section (lines 641–664 original):
- Deleted section comment `//**** COPY TO OTHER DATA STRUCTURES ****`
- Deleted `CopyToVector3d(Pt3D, Vector3d)` (static)
- Deleted `copyToVector3d(Vector3d)` (instance)
- Deleted `CopyToPoint3d(Pt3D, Point3d)` (static)
- Deleted `copyToPoint3d(Point3d)` (instance)

### Compile checkpoint

```
javac -cp . boxOfActin/Pt3D.java
```
(Java 8 on this machine — `--class-path` is Java 9+ syntax; `-cp` used instead.)

Result: **exit 0, zero errors, zero warnings.** No other files compiled.

### Post-edit verification

```
grep -n "vecmath\|Point3d\|Vector3d\|javax.media" boxOfActin/Pt3D.java
```
Result: **zero hits.** The file has no remaining `javax.vecmath` or
`javax.media.j3d` references.

### State after Phase 0

`Pt3D.java` is now a standalone class with explicit `public double x, y, z`
fields and no vecmath dependency. The five bridge methods are gone. All callers
of those bridge methods are in graphics methods (deleted in Phase 2) or QK code
(deleted in Phase 3) — no surprise non-graphics callers were found, confirming
the §3 caller graph.

Phase 1 (Env statics + Thing.java + class-load blockers) begins in a fresh
session.

---

### Session 3 — Commit commands

Run at the end of this session (survey only, no code edited):

```
git add JOURNAL.md
git commit -m "Session 3: Java3D removal survey — complete file inventory, phase plan, caller graph"
git push
```
