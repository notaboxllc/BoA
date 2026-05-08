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
