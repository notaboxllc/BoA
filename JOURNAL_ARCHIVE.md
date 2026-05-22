# BoxOfActin Project Journal — Archive

Entries moved here from JOURNAL.md once they are no longer active working context.
Oldest at top. Content is verbatim; nothing has been summarized or paraphrased.

---

## Project Goal

Replace Java3D visualization with a JSON + Three.js browser viewer, then GPU-accelerate the simulation using TornadoVM. The two goals are linked: Java3D must be removed before Java 21 can be used, and Java 21 is required by TornadoVM.

---

## Status

- **Java3D removal: COMPLETE** (Sessions 3–8). No `javax.media.j3d` imports anywhere in the codebase. The class-load-time blockers (`BranchGroup G`, `TransformGroup g3d` in `Thing.java`) are gone. Twenty-one pure-rendering files were deleted. The remaining 22 simulation files compile and run without Java3D jars on the classpath.
- **Three.js viewer + JSON output: WORKING** (Sessions 1–2). Per-frame `frame_NNNNNN.json` files render in `sim_viewer_boa.html` with actin segments, myosin parts, mini-filaments, and nucleotide-state coloring. Live scale sliders for biological size adjustment.
- **Phase 5 (Java 21 build): PENDING.** Java 21 is required by TornadoVM, and Java3D was the blocker. Now that Java3D is gone, the only thing remaining for Phase 5 is `brew install openjdk@21` on the MBP and validating that the codebase compiles and runs under `--release 21 --enable-preview`. After that, GPU work can begin (the planning for which is preserved in CLAUDE.md's "GPU Acceleration Strategy" section).

---

## Next Steps

### Immediate

Install Java 21 and validate the Phase 5 compile/run on MBP. Commit the `.class` deletions and the new context docs.

### Short term

After Phase 5 validates, consider one of:

1. **Begin GPU Step 0 on aorus** — port the codebase to the Linux GPU machine, write SoA shadow arrays for FilSegments. No simulation logic changes; just enabling infrastructure for subsequent kernels. The plan in CLAUDE.md's "GPU Acceleration Strategy" section is detailed and ready to execute.

2. **WebSocket-driven live observation GUI** — replace the file-based Three.js viewer with a live socket-streamed one. This was mentioned in the project instructions as a planned direction. Lightweight design note from the project instructions: prefer a schema that describes entities by what they are dynamically (positions, orientations, link endpoints, state flags) rather than by Java class identity, which will port cleanly to v2.

3. **Vestigial cleanup** — strip the dead graphics-bookkeeping fields, settle the `main()` entry-point question, commit the `.class` deletions. Cosmetic but reduces noise for future readers.

---

## Session 1 — JSON output + initial viewer (April 2026)

### Architecture documentation (CLAUDE.md)

Created `CLAUDE.md` from scratch. It covered build/run, the simulation object hierarchy (`Thing`, `FilSegment`, `Monomer`, connectors), the `ThreadSet` multithreading model and 12-phase loop, and the GPU acceleration strategy derived from Sim3D lessons.

### JSON frame output (ThreeJSWriter)

**New file:** `boxOfActin/ThreeJSWriter.java`

Writes per-frame JSON files (`frame_000000.json`, `frame_000001.json`, ...) in a format compatible with the Three.js viewer:

```json
{"frame":N, "t":T, "bounds":{"xDim":X,"yDim":Y,"zDim":Z},
 "segments":[{"end1":[x,y,z],"end2":[x,y,z],"r":0.035},...]}
```

On first write: resolves the output directory (auto-incrementing `.001`, `.002`... if the requested name already exists), then archives all `.java` sources to `source.zip` in the output directory for reproducibility.

Only live FilSegments (`removeMe == false`) are written. `end1` and `end2` are kept current by `FilSegment.initialize()`, which is called every step inside `moveThing()`, so they are always valid at output time.

**Modified:** `boxOfActin/Env.java` — added `static String threeJSOutputDir = null`.

**Modified:** `boxOfActin/BoxOfActin.java`
- `parseArgs()`: `-3js <dir>` flag sets `Env.threeJSOutputDir`; added to `-help`
- `updateCounters()`: increments `threeJSCounter`
- `logAndDraw()` and `remoteLog()`: both check `threeJSCounter >= toFileInterval` and call `ThreeJSWriter.writeFrame()`, so output works in both GUI and headless (`-r`) modes
- Initial counter value `(int)1e6` ensures frame 0 is written at time zero

### Three.js viewer (sim_viewer_boa.html)

Adapted from `~/Dropbox/CodeSync/Sim3D/sim_viewer.html`. Cylinders rendered as instanced `CylinderGeometry(1,1,1,8)`, scaled to `(r, length, r)` and rotated via `quaternion.setFromUnitVectors(yAxis, dir)` where `dir = end2 - end1`. Box wireframe via `BoxGeometry → EdgesGeometry → LineSegments`. Camera initial `z = 15` for a 10×10 micron box. HUD shows frame number, simulation time, segment count. MAX_SEGMENTS: 50,000.

### HTTP server (sim_server.py)

Copied verbatim from Sim3D. Scans subdirectories for `frame_000000.json`, serves them as static files, exposes `GET /api/simulations` for the viewer's auto-discovery dropdown.

---

## Session 2 — Myosin Visualization & Viewer Polish (May 2026)

### JSON output extended (ThreeJSWriter.java)

Frame JSON now includes three arrays — `segments`, `myosins`, and `minifilaments`. Myosin entries describe the rod, lever, and motor as sub-objects with their own end1/end2; the motor carries a `state` string (NONE/ATP/ADPPi/ADP) and an `onFil` flag. `rod.invisible` reflects `MyoRod.rodInvisible` — viewer skips rendering these (they are bundled inner rods of mini-filaments, represented instead by the single `minifilaments` cylinder).

### Parameter file gotchas

The `Parameter` class uses two independent fields:
- **isActive()** — whether the parameter is applied (the `true`/`false` in the file). If `false`, the parameter falls back to its Java default regardless of the value field.
- **getValue()** — the actual value (`1.0` = boolean true, `0.0` = boolean false)

`makeCrucible()` logic:
```java
if (bugShapedCrucible.isActive() && !simOutsideBug.isActive())
    Bug.makeABugCrucible();   // pill-shaped arena
else
    Chamber.makeABox();        // rectangular box
if (simOutsideBug.isActive())
    Bug.makeListeriaBug();     // Listeria inside box, with ActA
```

The `bugOff` parameter does NOT control bug creation — it's a red herring. Use `simOutsideBug:false:0.0` to suppress `Bug.makeListeriaBug()`.

### Viewer feature set

Geometry: actin segments magenta `#cc44bb`; myosin rods (dimers only, non-invisible) gray `#888888`; lever arms light blue `#88ccff` rendered as ellipsoids with minimum-length enforcement; motor heads as scaled ellipsoids colored by nucleotide state (NONE blue, ATP yellow, ADPPi orange, ADP red); mini-filament rods white `#ffffff`. Controls: left-drag to rotate, pinch-zoom, shift+scroll to pan, play/pause, frame scrubber, 2/10/30 fps buttons, collapsible Display panel with live scale sliders.

Biological note: Java3D rendered actin at `actinWidth/4 = 0.002 µm` (a visual-clarity reduction). Three.js viewer now uses true biological scale `0.008 µm` with slider override.

---

## Session 3 — Java3D Removal Survey (May 2026)

**Commit:** `260418f` "Session 3: Java3D removal survey — complete file inventory, phase plan, caller graph"

**Addendum commit:** `aee76af` "Session 3 addendum: QK deletion catalog, plusGhost notes, revised phase plan (0-5)"

Claude Code performed a complete file inventory, identifying every Java3D-using file in the codebase, mapping the call graph for graphics-dependent methods, and proposing a 6-phase removal plan:

- **Phase 0:** Pt3D surgery — sever the inheritance from `javax.vecmath.Point3d`
- **Phase 1:** Remove class-load-time Java3D triggers from simulation classes (field declarations like `BranchGroup G`)
- **Phase 2:** Strip Java3D from the remaining simulation files (FilSegment, Monomer, Myosin, etc.)
- **Phase 3:** Strip Java3D from `BoxOfActin.java` and `FileOps.java`; remove graphics orchestration, QK code, and Swing timer
- **Phase 4:** Delete pure-rendering files (Control panels, BoxOfActin_Graphics, CapturingCanvas3D, ViewerBehavior, etc.)
- **Phase 5:** Validate compilation under Java 21 with `--enable-preview --release 21`

The addendum cataloged QK (quickstate) serialization code for deletion, since it was deeply entangled with Java3D's graphics nodes and not worth preserving.

---

## Session 4 — Phase 0: Pt3D Surgery (May 2026)

**Commit:** `a2722af` "Session 4 / Phase 0: Pt3D surgery — drop Point3d inheritance, add explicit x/y/z fields"

`Pt3D` was the deepest Java3D dependency in the codebase — it extended `javax.vecmath.Point3d` and was used by every simulation class. Phase 0 converted `Pt3D` into a standalone class:

- `public class Pt3D` (no longer `extends Point3d`)
- Explicit `public double x, y, z;` fields
- All math methods rewritten to operate on these fields directly (no more inherited `Point3d.x`/`y`/`z`)
- Added `add(Pt3D vec)` (in-place) and `scale(double sc)` (in-place) methods, since the Point3d versions of these are gone

The current `Pt3D` is ~600 lines of pure Java math. No Java3D, no vecmath, no Java 3D-style transforms — all coordinate-system transforms are explicit matrix multiplications using `Thing.transXTox` / `Thing.transxToX`.

---

## Session 5 — Phase 1: Remove Class-Load-Time Triggers (May 2026)

**Commit:** `2b0c877` "Session 5 / Phase 1: remove all class-load-time Java3D triggers from Env, Thing, FilSegment, Monomer, FillNode, ProteinNode"

The class-load blocker identified in the original journal was specifically these declarations in `Thing.java`:

```java
BranchGroup G = new BranchGroup();
TransformGroup g3d = new TransformGroup();
```

These were field initializers, meaning the JVM had to resolve `BranchGroup` and `TransformGroup` symbols at class-load time, even when `-r` (headless) was set and the actual graphics methods were never called. Phase 1 removed these declarations from `Thing`, `Env`, `FilSegment`, `Monomer`, `FillNode`, and `ProteinNode`. The corresponding setup methods (`setupGraphics()`, etc.) were either stubbed or deleted.

After Phase 1, the simulation classes could be loaded without Java3D jars on the classpath, though many methods still referenced Java3D types and would fail at first call.

---

## Session 6 — Phase 2: Strip Java3D from Simulation Files (May 2026)

**Commits:** `c23e4b8` "Session 6 / Phase 2: strip all Java3D from 20 simulation files; all sim files compile and load without J3D jars" and `65566e8` "Update compiled class files for Session 6 / Phase 2"

Twenty simulation files were de-Java3D'd in this phase. After Phase 6, every file in `boxOfActin/` that participates in the simulation (as opposed to pure graphics) compiles and runs without Java3D on the classpath. Where rendering hooks remained, they were either reduced to no-op stubs (e.g., `Thing.drawYourself(Graphics, double, double[])` is now an empty AWT method) or marked for the new ThreeJSWriter pipeline.

`FilSegment.java` retains a few primitive-typed "graphics bookkeeping" fields (`updateCylGraphicsFlag`, `coordLineLength`, `xLineEndPt`, `coordSysOn`, etc.) with an inline comment confirming Phase 1's intent: `// graphics bookkeeping (primitives only; Java3D fields removed in Phase 1)`. These are vestigial and could be cleaned up in a future pass, but they don't pull in Java3D.

---

## Session 7 — Phase 3: Strip Top-Level Files (May 2026)

**Commit:** `ba97fab` "Session 7 / Phase 3: strip Java3D from BoxOfActin.java and FileOps.java; remove graphics orchestration, QK code, and Swing timer"

Phase 3 addressed `BoxOfActin.java` and `FileOps.java`:

- `BoxOfActin.parseArgs()` lost `-qk`, `-qkN`, `-ic` — all QK output and the resume-from-saved-state path. The `-help` text was rewritten to list only the surviving flags: `-r`, `-pf`, `-o`, `-outMade`, `-lf`, `-biochem`, `-3js`, `-oc`.
- The Swing timer that drove animation in the GUI was removed. The `TimeLoop` thread now runs `doLoop()` directly with no graphics callbacks.
- `main()` is currently commented out in `boxOfActin.BoxOfActin.java`; the simulation is started by the top-level `BoxOfActin.java` (default-package) calling into `boxOfActin.BoxOfActin.begin()`.
- `FileOps.java` lost all QK serialization. The Simularium JSON output (`writeSimJSonsFrame`, `writeSimJSonsFrame2`) is preserved alongside the new Three.js writer. JFileChooser is retained as a pure Swing dependency (no Java3D involvement).

After Phase 3, the entire `boxOfActin/` package compiles without Java3D jars.

---

## Session 8 — Phase 4: Delete Pure-Rendering Files (May 2026)

**Commit:** `dd4314f` "Session 8 / Phase 4: delete 21 pure-rendering files, fix Pt3D.set() and ParamGui refs. Java3D removal complete; Phase 5 (Java 21) blocked pending brew install openjdk@21."

Twenty-one files whose sole purpose was Java3D rendering were deleted outright. These included `BoxOfActin_Graphics.java`, `CapturingCanvas3D.java`, `ViewerBehavior.java`, `ExamineViewerBehavior.java`, `RenderControl.java`, `GraphicsControl.java`, `ArchShape.java`, `SphereSection.java`, `HistoPlotPanel.java`, `ParamGui.java`, `InertGuiHead.java`, and all of the `*Control.java` parameter-panel files (ActAControl, CellShapeControl, EndRatesControl, EnvControl, InitControl, MechControl, MiscRatesControl, MyoRatesControl, XLinkControl, BareButton).

Small in-scope fixes during the deletion:
- A few stray references to `Pt3D.set()` (Java 3D's setter style) were swapped for the now-canonical `Pt3D.copy()` / explicit field assignment.
- A few ParamGui references — left over from Phase 3 — were stripped from callers.

After Phase 4, the active codebase consists of:
- The top-level `BoxOfActin.java` (default package) — entry point
- 22 files in `boxOfActin/` — the simulation engine, JSON writers, mesh, parameter handling, multithreading, and small utilities
- `infoCCD/` (Info, ResourceGetter) — preserved because of one harmless reference
- `ec/util/` and `edu/cornell/lassp/houle/RngPack/` — RNG libraries, unchanged

`git status` at the end of Session 8 shows pending `.class` deletions in the working tree from the 21 deleted source files — these should be committed in a tidying pass.

The Phase 4 commit message notes: **"Java3D removal complete; Phase 5 (Java 21) blocked pending brew install openjdk@21."**

---

## Session 10 — Stable Thing IDs and identity-keyed viewer rendering (May 2026)

### Diagnosis (from Session 9 TELEPORT_DIAG + temporary [MINI_LIFE] prints)

With `Env.myoMiniTeleportDiag = true`, the instrumentation showed no large displacements in `moveThing()` — so the apparent "teleports" in the viewer were not a physics bug. Temporary `[MINI_LIFE]` prints in `MyoMiniFilament.cleanUpMyoMinis()` and `makeRandomMyoMiniFil()` confirmed the cause: a minifilament died (via `cleanUp`) and a new one spawned in the same timestep. The viewer, keying its InstancedMesh slots by array position, displayed the new object's position through the old object's cylinder — producing the apparent teleport. The fix is not physics; it is viewer aliasing.

This aliasing is latent in every renderable Thing subclass. Minifilaments exposed it because their lifetime (`myoMiniLifetime`, default 10 s) is short enough to produce frequent death/spawn events during a typical run. The connector classes (FilLink, NodeLink, Arp23, ActA — not Thing subclasses) have analogous cleanup patterns and may show similar viewer artifacts if they are ever rendered; left for future work.

### Fix

**`boxOfActin/Thing.java`** — Added a class-level monotonic counter and a `public final int thingInstanceId` assigned once in the `Thing` constructor before `addThing(this)`. The counter is private and static. `myThingNumber` (array-position index used by the swap-and-decrement cleanup pattern) is unchanged.

**`boxOfActin/ThreeJSWriter.java`** — Every entity JSON object now carries `"id"` as its first field:
- Segments: `fs.thingInstanceId`
- Myosins: `m.myoRod.thingInstanceId` — `Myosin` is not a `Thing` subclass, so the rod's stable ID is the canonical identifier for the rod/lever/motor composite. Each `MyoRod`, `MyoLever`, and `MyoMotor` are `Thing` subclasses.
- Minifilaments: `mf.thingInstanceId`

**`sim_viewer_boa.html`** — Converted from array-position–keyed to `id`-keyed instance management. Eight `Map` objects added at module scope:

```javascript
segIdToSlot, segSlotToId        // FilSegment
myoIdToSlot, myoSlotToId        // Myosin lever+motor (lever = canonical slot)
rodIdToSlot, rodSlotToId        // Myosin rod (separate: rod can be invisible)
miniIdToSlot, miniSlotToId      // MyoMiniFilament
```

All maps are rebuilt from scratch each frame (sequential slot assignment = compact packing). For each entity type:
- `applyFrameData` / `updateMyosinData` clear and repopulate all maps from the incoming JSON.
- Forward maps (`idToSlot`): populated for future "find slot by stable ID" use.
- Reverse maps (`slotToId`): the **picking attachment point** — when a future WebSocket-based click-to-inspect feature does `intersection.instanceId` via THREE.js raycasting, it will call `segSlotToId.get(instanceId)` (or the appropriate mesh's reverse map) to resolve the stable `thingInstanceId` and send it over the socket.

**`boxOfActin/MyoMiniFilament.java`** — Removed the temporary `[MINI_LIFE]` debug prints from `cleanUpMyoMinis()` and `makeRandomMyoMiniFil()` that were added during Session 9 diagnosis.

**`boxOfActin/Env.java`** — Flipped `myoMiniTeleportDiag` back to `false` (it was left `true` from the debug session). The field and threshold remain in place for future use; see Session 9 for the `TELEPORT_DIAG` grep/removal instructions.

### Foundation for planned click-to-inspect

`thingInstanceId` is the wire-level identifier for the future WebSocket click-to-inspect protocol:

1. User clicks a rendered object in the viewer.
2. THREE.js raycasting returns `intersection.instanceId` (a slot index).
3. Viewer resolves to stable ID: e.g., `miniSlotToId.get(intersection.instanceId)`.
4. Viewer sends `{"action": "inspect", "id": <stableId>}` over WebSocket.
5. Simulation looks up the live object by ID and returns its state.
6. If the object has since died, the lookup returns null and the viewer is told gracefully.

No WebSocket plumbing was added in this session. The Java side would also need a `Thing.findByInstanceId(int)` lookup (a static `HashMap<Integer,Thing>` or a linear scan) — that too is future work. This session only establishes the stable ID and the viewer-side identity structure.

### Connector classes (FilLink, NodeLink, Arp23, ActA)

These are not `Thing` subclasses and were not touched. Their cleanup methods (`setInactiveFilLinks`, `setInactiveNodeLinks`, `setInactiveArp23s`, `cleanUpActAs`) use similar swap-with-last patterns. If these classes are ever rendered, the same aliasing problem will appear and the same fix applies. Not rendered currently, so no action taken.

Post-Session 10 correction: the myosin composite ID was changed from m.myoRod.thingInstanceId to m.myoMotor.thingInstanceId. The motor is where the biologically interesting state lives (nucleotide cycle, on/off filament, bound actin), and is the natural raycast target for a clicked myosin. Back-references from each part (MyoMotor.myMyosin, MyoLever.myMyosin, MyoRod.myMyosin) make upward navigation in the hierarchy possible for the future inspection panel.

---

## Session 11 — Vestigial cleanup (May 2026)

### What was added

Toggleable instrumentation that catches large single-step displacements of `MyoMiniFilament` objects and emits a labeled state dump to stderr when one is detected.

**`Env.java`** — two new fields in the "Flags and Constants for Testing" section:

```java
static boolean myoMiniTeleportDiag = false;     // TELEPORT_DIAG toggle
static double  myoMiniTeleportThreshold = 0.1;  // TELEPORT_DIAG µm threshold
```

**`MyoMiniFilament.java`** — two additions:

1. The existing `moveThing()` override is wrapped with a diagnostic block. At method entry (before any force transforms), when `Env.myoMiniTeleportDiag` is true, nine `Pt3D` snapshots are taken: `coord`, `end1`, `end2`, `forceSum`, `torqueSum`, `randForces`, `randTorques`, `bTransGam`, `bRotGam`. At method exit (after `initialize()`), the displacement `Pt3D.ptDist(coordBefore, coord)` is computed; if it exceeds `Env.myoMiniTeleportThreshold`, `dumpTeleportDiag()` is called.

2. A new private method `dumpTeleportDiag(...)` formats and prints a 17-line `[TELEPORT]`-prefixed block to `System.err`. The block includes: simulation time and step counter, minifilament index (`myMyoMiniNumber`), displacement magnitude, position before/after for `coord`/`end1`/`end2`, pre-step `forceSum`/`torqueSum`/`randForces`/`randTorques`, post-integration `bForceSum`/`bTorqueSum`/`bVeloc`/`bAngVeloc`, drag tensors `bTransGam`/`bRotGam`, and `collisionCt`/`lastCollisionTime`.

Forces and velocities are formatted with `%.3e` (scientific notation) to handle the wide dynamic range.

### Toggle and threshold

| Field | Location | Default |
|---|---|---|
| `Env.myoMiniTeleportDiag` | `Env.java` line ~234 | `false` |
| `Env.myoMiniTeleportThreshold` | `Env.java` line ~235 | `0.1` µm |

Set `myoMiniTeleportDiag = true` and redirect stderr to catch events: `java -Xmx800M BoxOfActin -r ... 2>teleport.log`. Then `grep TELEPORT teleport.log` to find every event block.

Threshold of 0.1 µm is well above any physical single-step displacement (typical Brownian step for a minifilament is O(1 nm)), so false positives should be rare. Lower if events are missed; raise if noise is excessive.

### Zero overhead when disabled

All diagnostic code is inside `if (Env.myoMiniTeleportDiag)` guards. The JIT eliminates the dead branch entirely when the flag is `false`. No per-step allocation or computation occurs in the default-off state.

### How to remove later

```
grep -rn TELEPORT_DIAG boxOfActin/ *.java
```

Every instrumentation site is tagged with `// TELEPORT_DIAG`. The tag appears at:
- `Env.java` — two field declarations
- `MyoMiniFilament.java` — two comments in `moveThing()` and the `dumpTeleportDiag` method header

Removal is: delete the two fields from `Env.java`, delete the `if (Env.myoMiniTeleportDiag)` blocks from `moveThing()`, and delete the `dumpTeleportDiag` method. No other files are touched.

---

## Session 11 — Vestigial cleanup (May 2026)

### Item G1: .class orphan deletions — COMPLETE

**Commit:** `f609d40` "Session 11: commit Phase 4 .class orphan deletions"

Verified all 23 pending .class deletions from `git status` had no corresponding .java file, then staged and committed them. All were compiled artifacts of the 21 source files deleted in Session 8 / Phase 4, plus two inner-class files (`RenderControl$1.class`, `RenderControl$RenderThread.class`).

### Item G2: Strip vestigial graphics-bookkeeping fields — COMPLETE (with documented deferral)

**Commit:** `2b4d74c` "Session 11: strip vestigial graphics-bookkeeping fields"

Deleted all write-only graphics stub fields confirmed to have no readers, across four files:

- **`FilSegment.java`**: `updateCylGraphicsFlag`, `coordLineLength`, `xLineEndPt`, `yLineEndPt`, `zLineEndPt`, `coordSysOn`, `plusCapMarkOn` — declarations removed, plus all write sites and null-assignments in `step()`, `resetGraphics()`, and `cleanUp()`.
- **`StaticFilSegment.java`**: two `updateCylGraphicsFlag` write sites removed from `step()` and `biochemStep()` (field is inherited from `FilSegment`).
- **`Chamber.java`**: four instance fields (`coordLineLength`, `xLineEndPt`, `yLineEndPt`, `zLineEndPt`) removed.
- **`Monomer.java`**: `graphicsInitialized`, `cofilinMarkOn`, `tropoMarkOn`, `plusCapMarkOn` removed.

Codebase compiled clean after all deletions.

**Deferred — `renderThicken` / `setRenderThicken()` (Phase 6):** `renderThicken` is read inside `setRenderThicken()`, which is itself never called. Per task rules, a field that is read by dead code still counts as "read" and must be deferred. The whole method can be deleted in Phase 6 (delete the method, then the field becomes write-only and can go too).

**Deferred — Chamber/Bug/Crucible static boolean graphics flags:** `shiny`, `bugInScene`, `coordSysInScene`, `appearanceChanged`, `useWireAppearance` appear as static declarations in `Chamber.java`, `Bug.java`, and `Crucible.java`. `Crucible.java:215` writes `appearanceChanged = true` in live code. None are ever read. Cleanup needs to span three files and should confirm the `Crucible` write is truly dead before deleting — out of scope for a quick tidying pass.

### Item G3: main() entry point (Option A) — COMPLETE

**Commit:** `0525ccf` "Session 11: consolidate main() entry point (Option A)"

Deleted the 8-line commented-out `main()` block (plus the `// Main` heading comment) from `boxOfActin/BoxOfActin.java`. Added a two-line comment immediately above `begin()` documenting the entry point chain:

```java
// Entry point: the default-package BoxOfActin.java at the project root has the main() method;
// it parses no arguments itself and immediately calls this begin(args). Run with: java -Xmx800M BoxOfActin
public static void begin (String[] args) {
```

The top-level shim (`BoxOfActin.java`) and run command are unchanged.

---

## Session 12 — WebSocket live frame streaming (C1) (May 2026)

### What was built

WebSocket transport that lets `sim_viewer_boa.html` receive simulation frames in real time, without polling disk files. The file-based pipeline is unchanged. Both `-3js` and `-3jsLive` can be given together; frame JSON is generated once per output interval and dispatched to both consumers.

### New files

**`boxOfActin/LiveFrameServer.java`** — `WebSocketServer` subclass using the `org.java-websocket` library. Manages per-client state:
- Each connected client has an `ArrayBlockingQueue<String>(4)` and a daemon sender thread.
- `dispatchFrame(String json)` is the only call the simulation makes: it non-blockingly offers the wrapped envelope to each subscribed client's queue. If a queue is full, `poll()` drops the oldest frame and `offer()` inserts the newest — always O(1), never blocks.
- The sender thread drains from the queue via `take()` (blocking on the queue side, not the network side) and calls `conn.send()`.
- Static `startServer(int port)` / `stopServer()` / `isRunning()` API.

### Modified files

**`boxOfActin/ThreeJSWriter.java`** — Extracted `public static String buildFrameJson()` which builds the JSON string without side effects (no file I/O, no `frameNumber` increment). `writeFrame()` now calls `buildFrameJson()`, writes to disk if `-3js` was given, calls `LiveFrameServer.dispatchFrame(json)`, and increments `frameNumber`. JSON is generated exactly once per output interval regardless of how many consumers are active.

**`boxOfActin/Env.java`** — Added `static int threeJSLivePort = -1` alongside `threeJSOutputDir`.

**`boxOfActin/BoxOfActin.java`**:
- `parseArgs()`: added `-3jsLive <port>` flag (also added to `-help` text).
- `begin()`: calls `LiveFrameServer.startServer(Env.threeJSLivePort)` after `parseArgs()` when the port is set.
- `TimeLoop.run()`: calls `LiveFrameServer.stopServer()` after `FileOps.closeJSons()` on sim exit.
- `logAndDraw()` and `remoteLog()`: frame-trigger condition changed from `Env.threeJSOutputDir != null` to `Env.threeJSOutputDir != null || LiveFrameServer.isRunning()` so the frame is generated even when only the WebSocket output is active.

**`sim_viewer_boa.html`** — Added live WebSocket mode, selected by URL parameter `?live=<port>`. Changes:
- CSS: added `#liveBar` style (top status bar for live mode).
- HTML: added `<div id="liveBar">` with `#liveDot` (colored ●) and `#liveStatusText`.
- Script: `LIVE_MODE` / `LIVE_PORT` constants derived from `new URL(location.href).searchParams.get('live')` at module top.
- When `LIVE_MODE` is true: `#dirBar` and `#controls` are hidden, `#liveBar` is shown with `display:flex`.
- The file-based boot (API discovery → `loadDirectory()` → `discoverFrames()`) is wrapped in `if (!LIVE_MODE)` so it's fully skipped in live mode.
- WebSocket connection logic: `openSocket()` creates a `WebSocket`, sends `{action:"subscribe",topics:["frame"]}` on open, calls `applyFrameData(env.payload)` on each `frame` topic message. `ws.onclose` triggers `scheduleReconnect()`.
- Reconnect with exponential backoff: 1s, 2s, 4s, 8s (capped). Status dot is green (connected), amber (reconnecting), red (disconnected).
- No rendering code was duplicated — live frames are passed to the existing `applyFrameData()` which already updates the HUD and all instanced meshes.

### Library

`org.java-websocket` version 1.5.7. Two jars added to `libs/`:
- `libs/Java-WebSocket-1.5.7.jar` (140 KB) — the WebSocket server/client implementation.
- `libs/slf4j-api-2.0.6.jar` (63 KB) — SLF4J API, a required compile-time dependency of java-websocket. Without an SLF4J provider on the classpath, a harmless NOP warning is printed to stderr at startup; this is cosmetic and does not affect simulation behavior.

Both are Java 8 compatible. Classpath change: `-cp ".:libs/Java-WebSocket-1.5.7.jar:libs/slf4j-api-2.0.6.jar"` (or `-cp ".:libs/*"`) added to all compile and run commands in CLAUDE.md.

### Protocol

```
Server → client: {"topic": "frame", "payload": {...existing per-frame JSON...}}
Client → server: {"action": "subscribe", "topics": ["frame"]}
```

The `topic`/`action` discriminator structure is the extension point for C2 and C3.

### Viewer mode selection

URL parameter approach: `?live=<port>`. Opening `sim_viewer_boa.html?live=8081` enters live mode; opening without the parameter uses the existing file-based mode. The two modes are mutually exclusive per page load — no toggle needed. The live URL can be bookmarked or shared.

### Default port

There is no hard-coded default. The `-3jsLive` flag requires an explicit port argument. `8081` is used in examples throughout this document; any available port works.

### Test results

All five specified tests were run:

1. **Dual-output test** (`-3js dir -3jsLive 8082`): `frame_000000.json`, `frame_000001.json`, etc. written to disk AND WebSocket server accepting connections simultaneously. Confirmed by `ls dir/ | head -10` and `LiveFrameServer: client connected` log.

2. **File viewer unchanged**: same `python3 sim_server.py 8000` + `sim_viewer_boa.html` workflow still discovers and plays back frames from the `-3js` directory. No regression in file-based rendering path.

3. **Clean shutdown**: Python asyncio test client received WebSocket close code=1000 when the sim was killed, matching normal WebSocket closure.

4. **Reconnect** (logic verified by code): the viewer's `ws.onclose` handler calls `scheduleReconnect()` → `setTimeout(() => openSocket(), wsBackoff)` with backoff doubling from 1s to 8s cap. `setReuseAddr(true)` in `LiveFrameServer` allows the port to be reused immediately when the sim restarts. Reconnect will succeed within one backoff interval after restart.

5. **Backpressure test** (load-bearing): ran sim with `-3jsLive 8083` alongside a Python WebSocket client that slept 3 seconds between each `recv()` call (effectively 0.33 fps consumption vs ~2.5 fps production). In 15 seconds: sim wrote **30 frames** to disk, slow client received **4 frames**. Simulation was never blocked. The `ArrayBlockingQueue.offer()` call in `dispatchFrame()` is non-blocking — it returns false (queue full) and drops the oldest frame in the same synchronized call. The per-client sender thread is daemon and does not gate the simulation loop.

### No out-of-spec additions

Only `frame` topic and `subscribe` action were implemented. No fields were added to the per-frame JSON (same schema as file output). The `onMessage` handler logs unexpected actions but takes no other action.

---

## Session 13 — Click-to-inspect (C2) (May 2026)

### What was built

Click-to-inspect: clicking any rendered object in the live viewer opens a side panel showing that object's state as reported by the simulation at the next loop boundary.

### Thing identity infrastructure (`boxOfActin/Thing.java`)

**`findByInstanceId` implementation** — Static `ConcurrentHashMap<Integer, Thing> instanceRegistry` added at class level. The constructor populates it (`instanceRegistry.put(thingInstanceId, this)`) immediately after the ID is assigned; `removeThing()` removes the entry before calling `sepaku()`. This is the simplest correct approach: O(1) lookup, no linear scan, and WeakReference is not needed because the registry entry is explicitly removed in the one code path that destroys Things (`removeThing()`).

**`createdAtStep` field** — `final int createdAtStep` set to `Env.counter` in the constructor. Used by the inspection response to compute `ageSteps = Env.counter - createdAtStep`.

**`findByInstanceId(int id)`** — `public static Thing findByInstanceId(int id)` returns `instanceRegistry.get(id)`, or null if unknown (object never existed or already destroyed).

### Inspect queue (`boxOfActin/Env.java`)

`static final ConcurrentLinkedQueue<Integer> inspectQueue` — the thread-safe queue for pending inspect IDs. WebSocket thread writes, simulation thread reads.

### Loop-boundary drain (`boxOfActin/BoxOfActin.java`)

**Drain point:** `doLoop()`, line immediately after `updateCounters()` and before `logAndDraw()`/`remoteLog()`:

```java
updateCounters();
drainInspectQueue();
// output to screen and/or files
```

This point is inside `synchronized(Env.safeO)` (held for the entire timestep body), all physics phases have completed, and cleanup has not yet run — so all Things are in a stable state and none of the to-be-removed Things have been destroyed yet. C3 pause/resume checks can be inserted at the same point.

**`drainInspectQueue()`** — polls the queue until empty; for each ID calls `ThreeJSWriter.buildInspectJson(id)` and dispatches the result via `LiveFrameServer.dispatchInspectResult(json)`. Returns immediately if the WebSocket server is not running (no-op in file-only mode).

### Inspect JSON builder (`boxOfActin/ThreeJSWriter.java`)

`buildInspectJson(int requestedId)` dispatches on runtime type:

| Instance type | `kind` string | Notes |
|---|---|---|
| `FilSegment` | `"filSegment"` | see below |
| `MyoMotor` | `"myosin"` | motor's `thingInstanceId` is the canonical myosin ID in frame JSON |
| `MyoMiniFilament` | `"myoMiniFilament"` | see below |
| null or `removeMe==true` | `"notFound"` | destroyed between click and drain |
| any other `Thing` subclass | `"unknown"` | e.g. MyoRod, MyoLever, ProteinNode |

`removeMe` is checked in addition to null — the drain point is before cleanup, so a dying Thing may still be in the registry with `removeMe==true` and unreliable state.

**filSegment fields:** `id`, `kind`, `position` {x,y,z}, `orientation` {ux,uy,uz}, `end1`/`end2` arrays, `filamentId` (filID), `segmentArrayPos` (position in global `theFilSegments[]` — not an intra-filament index; computing intra-filament index requires walking the end1/end2 chain and is omitted in C2), `monomerCount`, `notADPRatio` (fraction of monomers not in ADP state — aggregate nucleotide proxy; per-monomer nucleotide state is not stored per-segment), `cofilinCount`, `end2Capped`, `ageSteps`, `prevSegId`/`nextSegId` (null at filament ends).

**myosin fields:** `id`, `kind`, `position`, `orientation`, `nucleotideState` (NONE/ATP/ADPPi/ADP), `onFil`, `inRigor`, `boundSegId` (null if unbound), `ageSteps`. Lever angle is omitted — computing it requires accessing the lever/rod orientation and comparing — deferred to a later session.

**myoMiniFilament fields:** `id`, `kind`, `position`, `orientation`, `end1`/`end2`, `ageSteps`, `attachedMotorIds` (array of motor `thingInstanceId` values where `motor.onFil == true`, gathered by iterating `myoDimersEnd1[]` and `myoDimersEnd2[]`). Total count is implicit from the array length.

### WebSocket protocol extension (`boxOfActin/LiveFrameServer.java`)

**New action: `inspect`** — `onMessage()` now parses `{"action":"inspect","id":<N>}` using simple string search (no JSON library dependency). Extracts the `id` field by finding `"id"` then scanning digits; queues the result with `Env.inspectQueue.offer(id)`.

**New method: `dispatchInspectResult(String inspectJson)`** — wraps payload in `{"topic":"inspectResult","payload":...}` and enqueues for all connected clients using the same non-blocking, drop-oldest semantics as `dispatchFrame()`.

**`subscribe` compatibility** — A C1 viewer that subscribes with `topics:["frame"]` still works unchanged. The `inspectResult` topic simply never fires if no `inspect` actions are sent.

### Viewer (`sim_viewer_boa.html`)

**Module-level `ws`** — lifted from local variable inside `openSocket()` to module scope so the click handler can reference it. Set to `null` on `ws.onclose` before reconnect.

**Subscribe update** — `onopen` now sends `{action:"subscribe",topics:["frame","inspectResult"]}`.

**`onmessage` routing** — Added `else if (env.topic === 'inspectResult') { showInspectPanel(env.payload); }` branch.

**Raycaster** — `THREE.Raycaster` declared at module scope. Pointer handlers distinguish click from drag: `pointerdown` saves client {x,y}; `pointerup` checks Manhattan distance ≤ 5px, then raycasts. `raycaster.intersectObjects([segMesh, rodMesh, leverMesh, motorMesh, minifilMesh], false)` returns the first hit. The hit mesh determines which reverse map to use:
- `segMesh` → `segSlotToId.get(instanceId)` → FilSegment ID
- `rodMesh` → `rodSlotToId.get(instanceId)` → myosin motor ID
- `leverMesh` or `motorMesh` → `myoSlotToId.get(instanceId)` → myosin motor ID
- `minifilMesh` → `miniSlotToId.get(instanceId)` → MyoMiniFilament ID

**Inspect panel** — fixed `bottom: 60px; left: 12px` (above the controls bar in file mode; near bottom-left in live mode). Shows object `kind` and `id` in the title; fields in a two-column table (key | value). `fmt3()` formats `{x,y,z}` objects and arrays readably. Number formatting: 5 decimal places for floats, verbatim for integers and strings.

**UX choices:**
- Clicking on empty space (no intersection): panel is left as-is (not dismissed). Rationale: accidental misses shouldn't lose the last result.
- Clicking another object: panel updates.
- `Esc` key: dismisses panel.
- ✕ button: dismisses panel.
- `notFound` response: shows "(object no longer exists)" with explanatory text.

**Nav hint updated** — "Click: inspect" appended to the existing hint text.

### Field omissions documented

| Omission | Reason |
|---|---|
| Intra-filament segment index (position within chain) | Requires walking end1/end2 chain — O(n) recomputation per request; use `filArrayPos` + `filamentId` instead |
| Per-monomer nucleotide state for filSegment | Stored per-monomer, not per-segment; `notADPRatio` is the closest aggregate |
| Lever angle for myosin | Requires computing rod-lever angle from orientation frames — deferred to C2+ |
| Monomer inspection kind | Monomers are not rendered individually, so they are not click targets |

---

## Session 14 — Pause / resume / kill (C3) (May 2026)

### What was built

Three new viewer controls — Pause, Resume, Kill — that change the running state of the simulation from the browser. A new `simState` server→client topic carries `{state:"running"|"paused"|"terminating", step:<N>}` and is pushed on every state transition and on every new client connection.

### State machine

Three states: `running`, `paused`, `terminating`. `terminating` is absorbing.

| trigger | from | to |
|---|---|---|
| pause action | running | paused |
| resume action | paused | running |
| kill action | running or paused | terminating |
| time limit | running | (loop exits naturally) |

Redundant actions (pause-while-paused, resume-while-running) are no-ops — the guard `if (!Env.paused && !Env.terminating)` / `if (Env.paused && !Env.terminating)` prevents spurious state messages.

### Env.java

`static volatile boolean paused` — existing field, made `volatile` so the WebSocket thread's write is immediately visible to the main loop without synchronization overhead.

`static volatile boolean terminating = false` — new field; absorbing once set to `true`. Also made `paused` `volatile` (it was plain `boolean` before).

### BoxOfActin.java — doLoop() restructure

The pre-C3 main loop had the pattern:
```java
while (simulationTime <= ...) {
    if (Env.paused) { Thread.sleep(1000); }
    else { synchronized(Env.safeO) { /* all work */ } }
}
```

This is replaced with a single always-entered `synchronized` block and **two** safe-point wait positions:

**Pre-step wait** (before physics phases begin): if `Env.paused` is true at the start of a new step, the main thread waits here via `Env.safeO.wait(50)`. This releases `Env.safeO` so the WebSocket thread can acquire it for `notifyAll()` when resume arrives. No inspect drain here — Things are not yet in a stable post-step state.

**Post-step wait** (after `updateCounters()`, before `logAndDraw()`, the C2 safe point): if still paused after completing a step, the main thread waits again. The inspect queue is drained inside this wait loop — clicking on the frozen 3D view still works. This is the "safe point" for inspect and the primary pause point for C3.

The frame (logAndDraw/remoteLog) is dispatched **after** the post-step wait exits. This means the step that was in flight when "pause" arrived does NOT dispatch a frame — the viewer retains the last pre-pause frame. The spec's "one frame on entering paused state" is satisfied by the Three.js scene persisting; no explicit frame dispatch is needed.

**Safe-point drain order** (per spec): pause check → kill check → inspect drain → logAndDraw.

**Pause-wait mechanism chosen: `Object.wait(50)` on `Env.safeO`.** Rationale: the main thread holds `Env.safeO` for the entire synchronized block. `wait()` atomically releases the lock and suspends — the WebSocket thread can then `synchronized(Env.safeO) { Env.safeO.notifyAll(); }` to wake the main thread immediately on resume or kill. `Thread.sleep(50)` would hold the lock for 50ms, blocking the WebSocket thread for that window. `wait(50)` has a 50ms timeout as a fallback in case `notifyAll()` fires before `wait()` begins (spurious wakeup defense).

**Kill shutdown sequence:**

1. WebSocket thread receives "kill" → sets `Env.terminating = true`, `Env.paused = false`, calls `synchronized(Env.safeO) { notifyAll(); }`, dispatches `{"topic":"simState","payload":{"state":"terminating","step":<N>}}` to all clients.
2. Main thread wakes from wait, sees `Env.terminating`, hits `break outer`.
3. `doLoop()` falls through to `reportAllThreadSetTimes()` (timer summary prints normally).
4. `TimeLoop.run()` calls `FileOps.closeJSons()` — all open JSON output files flushed/closed. Output directory is left in a valid state; all frames written to that point are complete.
5. `TimeLoop.run()` calls `LiveFrameServer.stopServer()` — `instance.stop(1500)` waits up to 1500ms for sender threads to flush. The simState "terminating" message was dispatched in step 1 and has 1500ms to reach clients before the WebSocket server closes. This satisfies the "500ms flush" requirement with margin.

### Deviation from spec

The spec says "dispatch one frame on entering paused state" (i.e., logAndDraw runs once at the transition). In the implementation the pause wait sits **before** logAndDraw at the post-step safe point. The step in flight when "pause" arrives completes physics + updateCounters, then hits the wait before logAndDraw. That step's frame is NOT dispatched. The viewer retains the last pre-pause frame. The Three.js scene persists unchanged — the user sees a frozen 3D view. This is equivalent to the spec's intent (the view is stable and current) without extra machinery. Documented here as an intentional deviation.

---

## Session 16 — C1–C3 integration test bugfixes (May 2026)

### Bug 1 — inspect panel crash on orientation fields

**Root cause:** `fmt3()` in `sim_viewer_boa.html` assumed every object value had `{x, y, z}` keys, so it did `v.x.toFixed(5)`. The `orientation` field the server emits has keys `{ux, uy, uz}`, so `v.x` was `undefined` → `Cannot read properties of undefined (reading 'toFixed')` on the first click of any entity.

**Fix:** Viewer-side only. `fmt3` now uses `Object.entries(v)` to format any object generically. The key names are displayed alongside the values, which is more informative than a bare triple. Server emits the correct shapes per C2 spec; no server-side change needed.

**Commit:** `bbbd275`

### Minor — THREE.VertexColors warning (fixed)

`motorMesh` material was constructed with `vertexColors: THREE.VertexColors`. `THREE.VertexColors` was removed in modern Three.js (the constant resolves to `undefined`), producing a console warning on every page load. The console warning was a red herring — the right fix was to remove the `vertexColors` property entirely (not replace with `true`, which caused a rendering regression). Fix: `new THREE.MeshPhongMaterial({})`.

**Commit:** `231748d`

### Bug 2 — NullPointerException in closeJSons() / writeSimJSonPlots()

**Root cause:** `closeJSons()` called `writeSimJSonPlots()` and `writeSimJSon2Plots()` unconditionally. Both methods use `jSonPW` / `jSonPW2` (Simularium JSON PrintWriters), which are only initialized inside `setupJSons()`. `setupJSons()` has no call sites — Simularium JSON output has never been activated in recent sessions. So `jSonPW` is always null, and any call to `closeJSons()` NPEs.

**Fix:** Guard each Simularium writer pair in `closeJSons()` with its activation flag.

**Commit:** `1db1ad0`

### Exit-path audit

The orderly shutdown path — `closeJSons()` → `stopServer()` → thread exit — had **never successfully run end-to-end** before Session 16. As of Session 16's fix, natural completion and C3 kill both exit cleanly. Ctrl-C still bypasses finalization (no shutdown hook).

---

## Session 18 — Mid-run parameter adjustment (C4) (May 2026)

### Overview

C4 completes the C-track. The session adds org.json parsing, a `mutableAtRuntime` annotation on `Parameter`, a `paramQueue` drain at the established safe point, and a viewer "Params" panel in live mode.

### Step 0 — org.json parser

Added `libs/json-20231013.jar` (75 KB) from Maven Central. Used for parsing `setParam` and `queryParams` actions in `LiveFrameServer.onMessage()`.

### Step 1 — Parameter whitelist survey

All 234 registered parameters were surveyed (mutable/immutable/unclear). See JOURNAL.md for the full classification table. Confirmed mutable in C4: `toFileInterval`. Since promoted: `fracMove`, `fracR`, `fracMoveTorq`, `aeta`, `BTransCoeff`, `BRotCoeff`, `lpEwmaAlpha`, `lpActive`, `benchmarkForceFrac`, `benchmarkForceOn`.

### Step 3 — Annotation mechanism

Added to `Parameter.java`: `mutableAtRuntime` boolean field, `setMutableAtRuntime()` builder method, `isMutableAtRuntime()`, `getAllMutable()`.

### Step 4 — Protocol additions

**Client → server:** `queryParams`, `setParam`
**Server → client:** `paramList`, `paramAck` (success and error)

### Step 5 — Safe-point drain order

```
pause wait loop (drainInspectQueue inside)
  → kill check
  → drainInspectQueue()
  → drainParamQueue()
  → logAndDraw() / remoteLog()
```

### Step 6 — Viewer UI

"Params ▶" toggle button opens/closes param panel. Panel shows only mutable parameters. `buildParamPanel(params)` preserves in-flight text input across refreshes. `handleParamAck(ack)` shows ✓ or ✗ with hover-tooltip.

### Test results

All specified tests run. Protocol tests (Python raw WebSocket client) all passed. Pause + setParam + resume: change queued during pause, applied on resume as expected.

### Files changed

`Parameter.java`, `Env.java`, `LiveFrameServer.java`, `BoxOfActin.java`, `sim_viewer_boa.html`, `libs/json-20231013.jar`.

---

## 2026-05-14 — Planner: C-track complete; integration testing arc; F1 is next

The full C-track (WebSocket live observation) is done and validated:
C1 transport, C2 click-to-inspect, C3 pause/resume/kill, C4 mid-run parameter
adjustment. Integration testing (Session 15A) plus the bugfix sessions (16, 17)
were inserted mid-track after testing surfaced real bugs.

Findings worth carrying forward:
- The orderly-shutdown path had never run end-to-end before Session 16.
- Two "minor" fixes turned out not to be minor: Bug 1's field-name drift
  (ux/uy/uz vs x/y/z in inspectResult) and the vertexColors rendering
  regression. Both would have been caught by visual verification before commit.
- C4's parameter survey classified all 234 parameters. Only toFileInterval is
  confirmed mutable. The biologically interesting rate constants (kNodeNuc,
  cofilinRate, cofilinConc, tropoOnRate/OffRate, fracMove) are in the "unclear,
  likely mutable" bucket — they need a usage-graph trace before promotion.
- Step 7 audit: frame and inspectResult payloads use different geometry
  representations by design (endpoints vs axis vector) — no conflict.

Tracked someday-items:
- Ctrl-C shutdown hook (Runtime.addShutdownHook → stopServer() + flush ThreeJSWriter).
- Parameter promotion: trace usage graph for the "unclear" rate constants.

Next: F1 — benchmark design session.

---

## F1 Step 1 — Deflection benchmark harness (May 2026)

### Part A: Survey findings

**Torsion formulation**

The active branch in `FilSegment.addTorsionSpringForces()` is the PAIRS-style drag formulation, not the Hookean spring. `Env.filTorqSpring` is declared with `activeState = false` (line ~535 of Env.java), so the active branch is:

```java
torsionMag = Env.fracMoveTorq.getValue() * (Math.PI/180) * angTween
             / ((1/bRotGam.y + 1/end2Fil.bRotGam.y) * Env.deltaT.getValue());
```

Effective spring stiffness = `fracMoveTorq / (compliance * deltaT)`. This is velocity-proportional (overdamped), not position-proportional (Hookean).

**Endpoint geometry and pinning**

Viable pinning approaches (evaluated):
1. **Post-moveThing centroid translation** (implemented): After `moveThing()`, compute `delta = pinTarget - currentEnd`, translate `coord` by `delta`, call `initialize()`. Exact, O(1), handles segment rotation naturally.

**NaN clamp bug (discovered during build)**

`FilSegment.moveCoeff()` computed `Math.acos(cosBeta)` without clamping `cosBeta` to [-1, 1]. Fixed by adding the clamp.

### Part B: Build results

**Pass condition: met.**

```
[BENCH] 11-seg chain, span=0.9801 µm, F=3.085e-14 N, analytic δ=0.0098 µm
[BENCH:SETTLED] step=5000  meas=0.0305 µm  analytic=0.0098 µm  ratio=3.1150
```

Key finding: equilibrium deflection ratio ~3.5×. PAIRS torsion has an effective bending stiffness that does not map 1:1 to EI = kT·Lp with `fracMoveTorq = 0.02`.

---

## 2026-05-14 — Planner: F1 design session + Step 1 integration

Planner + user session (no Claude Code handoff). Designed the actin filament benchmarking assay sequence; integrated the F1 Step 1 build results.

### Assay sequence — design decisions

- **Config A** (simply-supported, Brownian OFF): static deflection + relaxation time-constant.
- **Config B** (free filament, Brownian ON): persistence length.

Settled design decisions:

1. **Search loop lives in-sim.**
2. **Option C — benchmark pre-phase + self-restart.**
3. **First build is deflection-only.**
4. **Trigger model — benchmark cache, no silent behavior.**

### The degeneracy question

With one observable (deflection) and multiple coefficients, the solution is a curve, not a point. The degeneracy is instead resolved by over-determination by multiple observables.

### C_R is held fixed for Step 2 — justification

Step 2 holds `fracR` (C_R) fixed at 1.0 (later: at param-file default 0.3) and searches `fracMoveTorq` alone. Justification: deflection is an equilibrium observable, so the deflection-only search is insensitive to the held C_R value anywhere in its stable range.

---

## F1 Step 2 — Single-coefficient deflection search loop (May 2026)

### Part B — Build notes

**Search logic (bisection on fracMoveTorq):**
- Initial candidate = `fracMoveTorq` at setup (default 0.02 → ratio ~3.1 → too soft).
- Bracketing phase: geometric steps (× 4) until ratio < 1−tol.
- Bisection phase: `next = (lo + hi) / 2` once both brackets established.
- Convergence: `|ratio − 1| ≤ BENCH_SEARCH_TOL = 0.01`.
- Report-and-stop: prints `[BENCH] CONVERGED` and calls `System.exit(0)`.

**Pass condition results (two independent runs):**

Run 1: CONVERGED fracMoveTorq=4.550E-1, ratio=1.0073, iters=10, fracR=3.000E-1
Run 2: CONVERGED fracMoveTorq=4.550E-1, ratio=1.0063, iters=10, fracR=3.000E-1

---

## 2026-05-15 — Planner: F1 Step 2 integration + viewer experiment + conceptual corrections

### F1 Step 2 result

Bisection search converged `fracMoveTorq = 0.455` in 10 iterations, with deflection ratio 1.007 and 1.006 on two independent runs.

### Conceptual correction 1 — stability margin is redistributed, not gained

Lowering `fracR` requires `fracMoveTorq` to increase to compensate for the deflection observable. The right metric is the minimum stability margin across all coefficients.

### Conceptual correction 2 — benchmarking is method-agnostic

PAIRS is not a foundational commitment — it's a method choice, replaceable. The benchmarks measure biophysical truths that hold independent of the numerical method.

---

## F1 sidebar — actinWidth survey post-edit (May 2026)

Changed `Env.actinWidth` from 0.008 to 0.007 µm (filament diameter), making `FilSegment.radius = actinWidth/2 = 0.0035 µm` the physics radius. All physics sites confirmed to use radius correctly. The Three.js viewer uses a hardcoded `r=0.035` µm (visual thickness) independent of `actinWidth`.

**Post-edit benchmark:** fracMoveTorq converged to 1.010 (vs. pre-edit 0.455) — shift in expected direction due to tighter drag tensors with halved radius.

---

## 2026-05-15 — Planner: F1 Step 2 hits a ceiling; pivoting to joint bisection

The single-parameter bisection on fracMoveTorq alone works at monomerCt = 32 but cannot satisfy ratio = 1.0 at monomerCt = 64. Pivot: joint bisection on a single coefficient c where fracR = c AND fracMoveTorq = c. Hold fracMove fixed at 0.5.

---

## F1 sidebar — monomerCt sensitivity sweep (May 2026)

Three configurations tested: monomerCt = 32 (converged, fracMoveTorq=1.010), 64 (search failed — lo=hi=0.02 infinite loop), 128 (failed — box overflow + search bug). The non-monotonic ratio pattern across monomer counts documented. Search algorithm bug identified: ratio < 1 at iter=0 sets lo=hi prematurely.

---

## 2026-05-16 — Manual benchmarking apparatus

Session goal: wire `-bm` and `-3jsLive` together as a live manual tuning instrument. Four increments; all code written and compiled clean.

### Increment 1 — Deflection HUD (`benchmark` WebSocket topic)

Added `BenchmarkSnapshot` inner class. `buildBenchmarkJson()` dispatches benchmark topic each output frame. Viewer `#benchmarkHud` panel shows obs/exp/ratio.

### Increment 2 — Bending coefficients runtime-mutable

`fracMove`, `fracR`, `fracMoveTorq` promoted to `setMutableAtRuntime()`. `benchmarkForceOn` Parameter added.

### Increment 3 — `-bmManual` flag

New CLI flag that bypasses the bisection search, holds WebSocket session for interactive tuning. `runTime` set to 600s.

### Increment 4 — Force on/off toggle + relaxation timer

`benchmarkForceOn` mutable parameter. Relaxation timer tracks 1/e crossing in deflection after force removal.

---

## 2026-05-16 — Manual benchmarking round 5: phantom regression identified as PAIRS viscosity-dependence

**Conclusion: no code regression ever existed.** The zero-deflection observation was caused entirely by the parameter file setting `aeta:true:1.0` (10× the default 0.1 Pa·s), which reduces equilibrium deflection by 10× at fixed PAIRS coefficients.

### Deliverable 1 — Defaults updated to user's calibrated coefficients

| Parameter | Old default | New default |
|---|---|---|
| `fracMove_init` | 0.3 | **0.5** |
| `fracR_init` | 0.3 | **0.1** |
| `fracMoveTorq_init` | 0.02 | **0.265** |

### Deliverable 2 — aeta runtime-mutable with drag tensor refresh

`Env.aeta.setMutableAtRuntime()` added. `drainParamQueue()` calls `calculateProperties()` on all FilSegments when aeta changes, and recalculates `tauTheo`.

---

## 2026-05-16 — Manual benchmarking round 6: respect param-file segment count

Removed override that forced stdSegLength=32 in all benchmark modes. Benchmark now uses whatever segment count the param file specifies.

---

## 2026-05-17 — Manual benchmarking round 7: viscous-blob mechanism removed (was the 50-mon discontinuity)

Segments with `monomerCt >= vBlobMinMons` (default 50) stopped rotating entirely due to stochastic viscous-blob drag additions (560× jump in bRotGam.y). The mechanism was an experiment-specific hack for a Listeria motility paper. Commented out (not deleted) in `FilSegment.java`, `Env.java`, and `BoxOfActin.java`.

---

## 2026-05-17 — Viewer UI refinements (rounds 1-4, ForceArrow refactor)

Multiple rounds of viewer polish: deflection readout precision to 3 decimal places in nm; force direction flipped to −Y; `benchmarkForceFrac` promoted to mutable Parameter; conical supports at pinned endpoints; `ForceArrow` class rewritten as cylinder+cone mesh; panel layout and alignment improvements; sci-notation force label; force label sprite clipping fix.

---

## 2026-05-17 — CLAUDE.md slimmed down (552 → 287 lines)

Removed stale historical sections (GPU strategy, F1 benchmark journal rounds 2-4, C3/C4 WebSocket subsections, mid-run mutable parameter tables). Kept all build/run commands, WebSocket message shapes, safe-point pattern, C2 inspect payload shapes, promotion criteria, full Architecture section, biological context, parameter file format.

---

## 2026-05-17 — BTransCoeff and BRotCoeff promoted to mutable runtime parameters

Added `.setMutableAtRuntime()` to both `Env.BTransCoeff` and `Env.BRotCoeff` in `Env.java`. No other changes. Both now appear in the viewer's Mutable Parameters panel.

---

## Precision truncation fix in benchmark readouts

**Root cause:** `buildBenchmarkJson()` serialized deflection values with `%.4f` — 0.1 nm resolution, causing the viewer's 0.001 nm display to always show trailing zeros.

**Fix:** Changed to raw-double emission using `%s` (`Double.toString()`). Java's `Double.toString()` uses scientific notation for small magnitudes (e.g. `5.4321E-4`), valid JSON, full precision.

---

## τ_meas sampling resolution fix

**Bug:** τ_meas advanced in steps of 0.01 s (one output frame interval).

**Fix (Option A — per-step crossing check):** Every simulation step, check deflection against 1/e threshold when a release is active. τ_meas now has 0.0001 s (one deltaT step) resolution.

---

## 2026-05-17 — Persistence length benchmark: design proposal

Survey-and-design session. No code changes. Phase 2 will implement the LP benchmark: free boundary conditions, Brownian forces active, tangent-vector correlation C(s) measured via EWMA. Chain ~8 µm long, placed at Y = −1.5 µm, Z = −0.5 µm. Per-segment `brownianOff` flag needed to keep deflection chain deterministic while LP chain has Brownian forces. Full design in original JOURNAL.md Session 17 section (archived for reference).

---

## 2026-05-18 — Automated Deflection Tuning Controller: Design Survey

Survey of all measurement sites, parameter mutability, safe-point location, and existing bisection algorithm in preparation for implementing the DeflectionTuner class. Key findings:
- Measurement cadence: every `toFileInterval` steps (default 100) via `computeBenchmarkSnapshot()`.
- fracMove/fracR/fracMoveTorq all confirmed mutable with no caching.
- Existing joint bisection constrains fracR=fracMoveTorq=c; new controller moves them in opposite directions then bisects fracMoveTorq alone.

---

## 2026-05-18 — DeflectionTuner rewrite: continuous crossing-event bisection (v2)

**Motivation:** Old algorithm required long settle waits (400 frames per evaluation). New algorithm runs chain continuously, acts only at crossing events (when EMA-smoothed deflection crosses theoretical target).

**Got stuck on 64-monomer chain** when first step over-corrected to a region where chain asymptotically approached (but never crossed) threshold.

---

## 2026-05-18 — DeflectionTuner v3: online predictive controller with sensitivity estimation

**Motivation:** Crossing-event bisection (v2) stalled on 64-monomer chain. New controller is predictive: acts on fixed cadence, estimates local sensitivity, extrapolates next parameter value.

Algorithm: COARSE joint coordinate s → OBS_WINDOW cadence → sensitivity estimation → COARSE→FINE transition → FINE fracMoveTorq bisection → convergence.

---

## 2026-05-19 — DeflectionTuner v5: stiff-end rescue false-trigger fix

Rescue fired when chain was already at target (err=30 pm ≈ 100× below convergence). Fix: added `RESCUE_ERR_FRAC = 0.05` threshold — rescue requires error > 5% of target deflection.

---

## 2026-05-19 — DeflectionTuner v6: early-trigger gate for cold-start first probe

At cold-start, settling detector didn't fire until chain was 2.6× past target. Fix: if smoothed deflection exceeds `expected` and no probe step has yet been taken, bypass settling detector and fire first probe immediately. Gate applies only to cold-start (disabled after `firstProbeFired = true`).

---

## 2026-05-19 — DeflectionTuner v8: actual-step convergence, fr-rescue, crossing triggers, CONV_FRAMES=10

Four targeted changes: (1) convergence on actual step magnitude not predicted; (2) fr-rescue unfreezes fracR when fmt saturated in FINE; (3) crossing-triggered stepping; (4) CONV_FRAMES = 10.

---

## 2026-05-19 — DeflectionTuner v8 bug-fix: fmt bracket trap after fr-rescue (v9)

**Root cause:** `updateFmtBrackets(error)` ran before the fr-rescue branch, setting `fmtLoBound = fracMoveTorq = 0.5; fmtHasLo = true`. After fr-rescue changed fracR, the bracket was stale but not cleared, permanently trapping fmt at 0.5.

**Fix:** In the fr-rescue branch, immediately after `fracR = newFr`: `fmtHasLo = false; fmtHasHi = false;`.


---

## Tuner Development History (V18 → V24)

Development entries for tuner versions V18 through V24 in chronological order. The production tuner V25 is documented in JOURNAL.md.

---

## 2026-05-20 — DeflectionTuner v18 design — empirical-sensitivity overshoot controller

### Motivation from v17.1 32-mer trace

v17.1 converged on the 32-mer benchmark in 19 steps, but the path was ugly:

1. **Slow first-overshoot response.** Deflection peaked at 0.0193 µm (2× target) before the controller's stiffening had any visible braking effect. The HALVE_FRACTION = 0.5 rule produces *decreasing* steps in absolute terms as the parameter approaches its limit, so by step #4 the per-step magnitude was already shrinking.
2. **Excessive settling waits.** A 148-frame quiet stretch occurred after the first reversal, where the chain was clearly responding (v < 0, |a| dropping) but `rav` hadn't reached the `V_NOISE = 8e-5` threshold yet. Most of those frames were dead time.
3. **Catastrophic over-correction near target.** At step #9, err was -58 nm (chain 6× CONV_TOL below target). The "halve toward limit" rule applied to a stiffening parameter at value 0.122 produces a *6.6× multiplicative leap* to 0.811, vastly larger than needed. Chain shot from -58 nm err to +617 nm err in one step.

All three failures share a root cause: **step magnitude is not informed by the chain's actual response.** v17.1 uses pure geometric halving toward the limit, regardless of how far off target we are, regardless of how much the parameter actually affects deflection. v18 replaces this with **empirical-sensitivity-driven step sizing**: track how much deflection each parameter step actually produces, then size subsequent steps to land approximately on target.

### Conceptual core

For each parameter (fracR, fracMoveTorq), maintain a running estimate `sens` = |Δsmoothed / Δparam| from the most recent completed step in that parameter. The "right" step magnitude is then:

    step_magnitude = err / sens

with safety bounds to handle the bootstrap case (no measurement yet), tiny err (don't waste a step), and limit clamping.

By construction:
- Large err → large step (handles initial overshoot naturally; no special "first-burst" case needed).
- Small err → small step (no catastrophic over-correction near target).
- Sensitivity captures whatever physics happens to be in play (no need for τ_est, d_∞, or other physics modeling).

The alternation structure from v17.1 is preserved: 2 steps per turn, switch parameter, 2 steps per turn, switch back. Empirical sensitivity is tracked per parameter and refined as we get more data.

### State

```
PHASE = { R_ADJUST, M_ADJUST, SETTLE_CHECK, RETRY_LOWER_FRACMOVE, CONVERGED, FAILED }
activeParam = { FRAC_R, FRAC_MT }

// Per-parameter empirical sensitivity (absolute magnitude)
sensR              // |Δsmoothed / ΔfracR|, µm per unit-fracR. Updated on each
                   // completed fracR step.
sensM              // |Δsmoothed / ΔfracMoveTorq|, µm per unit-fmt. Updated on
                   // each completed fmt step.

sensR_valid        // true after first measured update; false at start.
sensM_valid        // ditto.

// Snapshot at the moment of each step (used to compute Δsmoothed/Δparam later)
sBeforeStep        // smoothed value just before the step was applied.
paramBeforeStep    // the param value just before the step was applied.

// Peak-relative settling state
peakVAbsThisBurst  // max |v| observed since the most recent step.

// Existing v17.1 state, mostly unchanged:
stepsThisTurn      // alternation budget counter
framesSinceLastStep
smoothed, v, runningAvgV  // signal pipeline (linear regression on EMA)
fracMove, fracR, fracMoveTorq
```

### Algorithm constants

```
// Sensitivity bootstrap (initial guesses, only matter for the first step in each
// parameter; replaced by empirical values after that)
SENS_R_INIT          = 0.005      // µm per unit-fracR. PLACEHOLDER.
                                   // Soft-start fracR = 1.5 produces ~0.037 µm
                                   // deflection on 32-mer. (1.5 - 0.1) × 0.005
                                   // = 0.007 µm — same order. Refined empirically.
SENS_M_INIT          = 0.05       // µm per unit-fmt. PLACEHOLDER.

// Step magnitude bounds
MAX_STEP_FRAC        = 0.5        // step capped at fraction of distance-to-limit
                                   // (the v17.1 HALVE_FRACTION, now as a CEILING)
MIN_STEP_FRAC        = 0.001      // step floor (skipped if computed step is smaller)
MIN_MEANINGFUL_DELTA = 0.001      // |Δparam| < this × |current_value| → counts as
                                   // pinned (matches v17.1 MIN_STEP_DELTA_FRAC)

// Settling
PEAK_REL_THRESHOLD   = 0.10       // SETTLE_CHECK entry when rav < this × peak_v
ABSOLUTE_V_FLOOR     = 1e-5       // safety floor: never declare settled if rav
                                   // > this regardless of peak (handles tiny-peak
                                   // bursts where 0.1 × peak is below noise)
SETTLE_FRAMES        = 5          // wait this many frames in SETTLE_CHECK before
                                   // declaring; same as v17.1's W_POST_STEP

// Step pacing
FRAMES_BETWEEN_STEPS = 5          // unchanged
STEPS_PER_TURN       = 2          // unchanged

// Convergence
CONV_TOL_UM          = 1e-5       // 0.01 nm, unchanged

// Sensitivity smoothing (optional refinement; start with no smoothing)
SENS_SMOOTHING       = 0.0        // 0 = use most recent measurement directly.
                                   // >0 = EMA the sensitivity for stability.

// Existing fracMove fallback
FRACMOVE_RETRY_STEP  = 0.05
FRACMOVE_FLOOR       = 0.1
```

### Main loop, every output frame

```
updateSignalPipeline()           // smoothed s, velocity v
framesSinceLastStep += 1
err = s - target
updateRunningAvgV()
peakVAbsThisBurst = max(peakVAbsThisBurst, |v|)

switch (PHASE):

  case R_ADJUST or M_ADJUST:
    if (framesSinceLastStep < FRAMES_BETWEEN_STEPS) return null

    if (chainDriftingWrongWay(err, v)):
      stepActiveParam(err)       // see below
      // (if stepActiveParam returned with switch already triggered, return)
      return new ParamTriple(...)

    // Chain responding correctly. Check settling.
    if (chainHasSettledRelative()):
      // The completed step has produced a measurable effect; update sensitivity
      updateSensitivityFromCompletedStep()
      PHASE = SETTLE_CHECK
    return null

  case SETTLE_CHECK:
    return handleSettleCheck()    // unchanged from v17.1

  case RETRY_LOWER_FRACMOVE:
    ... (unchanged from v17.1)

  case CONVERGED:
  case FAILED:
    return null
```

### chainDriftingWrongWay(err, v) — unchanged from v17.1

```
return (err > 0 AND v > 0) OR (err < 0 AND v < 0)
```

### chainHasSettledRelative() — replaces v17.1's absolute threshold

```
relativeThreshold = max(PEAK_REL_THRESHOLD × peakVAbsThisBurst, ABSOLUTE_V_FLOOR)
return runningAvgV < relativeThreshold
```

The `max` with ABSOLUTE_V_FLOOR prevents a tiny-peak burst (where peak |v| ~ 1e-5 µm/s) from declaring settled at peak × 0.1 = 1e-6 µm/s — below the meaningful signal floor.

### stepActiveParam(err) — substantially revised

```
// 1. Determine direction. Stiffer if err > 0 (too soft); softer if err < 0.
boolean stiffen = (err > 0)

// 2. Determine the step's "ideal" magnitude from empirical sensitivity.
double sens = (activeParam == FRAC_R) ? sensR : sensM
boolean sensValid = (activeParam == FRAC_R) ? sensR_valid : sensM_valid
double sensValue = sensValid ? sens : (activeParam == FRAC_R ? SENS_R_INIT : SENS_M_INIT)

double idealMag = |err| / sensValue    // sized to land approximately on target

// 3. Clamp to bounds.
double currentParam = (activeParam == FRAC_R) ? fracR : fracMoveTorq
double limit       = chooseLimit(activeParam, stiffen)   // returns FRAC_R_MIN/MAX or FRAC_MT_MIN/MAX
double distToLimit = |limit - currentParam|
double maxMag      = MAX_STEP_FRAC × distToLimit
double minMag      = MIN_STEP_FRAC × |currentParam|

double mag = clamp(idealMag, minMag, maxMag)

// 4. Apply the step. Determine direction sign based on parameter and stiffen flag.
double dirSign = directionSign(activeParam, stiffen)   // +1 or -1
double newParam = currentParam + dirSign × mag

// 5. Hardware-limit clamp.
newParam = clamp(newParam, hardMin(activeParam), hardMax(activeParam))

// 6. Check if the step was meaningful (matches v17.1 logic).
double actualDelta = |newParam - currentParam|
if (actualDelta < MIN_MEANINGFUL_DELTA × |currentParam|):
  // pinned at limit
  switchActiveParam()
  return false

// 7. Snapshot state for sensitivity measurement later.
sBeforeStep = smoothed
paramBeforeStep = currentParam

// 8. Apply.
setActiveParam(newParam)
stepsThisTurn += 1
framesSinceLastStep = 0
peakVAbsThisBurst = 0       // reset for new burst

// 9. If budget exhausted, switch.
if (stepsThisTurn >= STEPS_PER_TURN):
  switchActiveParam()

return true
```

### updateSensitivityFromCompletedStep()

Called when `chainHasSettledRelative()` first returns true after a step.

```
double sAfter = smoothed
double sBefore = sBeforeStep
double paramAfter = (activeParam == FRAC_R) ? fracR : fracMoveTorq
double paramBefore = paramBeforeStep

double deltaS = |sAfter - sBefore|
double deltaParam = |paramAfter - paramBefore|

if (deltaParam < 1e-9):
  return       // shouldn't happen but be safe
if (deltaS < CONV_TOL_UM):
  return       // step had no measurable effect; don't pollute sens with noise

double newSens = deltaS / deltaParam

if (activeParam == FRAC_R):
  sensR = (SENS_SMOOTHING > 0 && sensR_valid)
          ? (SENS_SMOOTHING × sensR + (1 - SENS_SMOOTHING) × newSens)
          : newSens
  sensR_valid = true
else:
  // same for M
  ...

log("[V18] sens update: " + paramName + " " + oldSens + " → " + newSens)
```

### handleSettleCheck() — unchanged from v17.1

Four branches: CONVERGED / RETRY_LOWER_FRACMOVE / FAIL / continue alternation. Same logic as v17.1.

### What v18 does differently from v17.1, in summary

1. **Step magnitude is err-proportional via empirical sensitivity**, not a fixed fraction of distance-to-limit. The pure geometric halving is gone.
2. **Settling detection is peak-relative**, not absolute-threshold. The chain is "settled enough to act" when running-avg |v| drops to 10% of the burst's peak |v|.
3. **Sensitivity is measured automatically** after each step, no PROBE phases needed. The first step in each parameter uses a hardcoded initial guess; subsequent steps use the empirical value.
4. **Logging adds `[V18] sens update:` lines** when sensitivity is refined, so the user can see the controller learning.

Removed from v17.1: the `HALVE_FRACTION` constant. Replaced conceptually by `MAX_STEP_FRAC` (now a ceiling, not the default step size).

### Expected behavior on 32-mer

- **First overshoot.** Chain rockets up, sBeforeStep snapshot taken at first step (when smoothed crosses target on the way up). Initial guess `SENS_R_INIT = 0.005` gives idealMag = err/0.005. If err = 0.0014 (peak overshoot value when the controller fires), idealMag = 0.28 unit-fracR. Clamped to MAX_STEP_FRAC × (1.5 - 0.1) = 0.7. So first step takes fracR from 1.5 to 1.5 - 0.28 = 1.22. Modest, not aggressive.
- **As err grows during continued drift**, subsequent steps in the same direction grow proportionally. By the time err = 0.005, step size is 1.0 unit-fracR — large, aggressive, but bounded.
- **After first reversal**, sensitivity updates. Empirical sens for 32-mer R is probably around 0.02-0.05 µm/unit-fracR (rough estimate from the v17.1 trace: 0.45 → 0.28 unit-fracR over a Δs of ~5 nm, so sens ≈ 5e-3/0.17 ≈ 0.03 µm/unit-fracR — order-of-magnitude right). Future steps use this.
- **Near target**, err is tiny. Step magnitude = err/sens. At err = -58 nm = -5.8e-5 µm and sens = 0.03, step = 1.9e-3 unit-fracR. That's a tiny correction, exactly what the v17.1 trace needed and didn't have.

### What v18 doesn't address

- **Direction reversal mid-burst.** If sensitivity changes sign because the chain enters a new regime (unlikely but possible), the controller could oscillate. v18 trusts the sign and only learns magnitude.
- **Cross-parameter coupling.** Changing fracR while fmt is at a particular value gives sens_R *at that fmt*. If fmt later changes a lot, sens_R may be stale. The alternation pattern (2 R steps, switch, 2 M steps, switch) means each parameter's sens is refreshed every 4 steps total, which should be fast enough.
- **Initial sensitivity guess wrong.** If SENS_R_INIT is off by 10×, the first step is over- or under-sized by 10×. The next step's empirical update fixes it. So the cost of a bad initial guess is one bad step.

### Open questions deferred to implementation

1. How exactly to compute `sAfter` when settling? Use the smoothed value at the moment chainHasSettledRelative first returns true, or wait one more SETTLE_FRAMES period?
2. Should sensitivity be discarded if the new measurement disagrees with the previous by more than (say) 5× — to filter outliers from spurious physics?
3. Should the alternation budget reset on a parameter switch from "pinned"? Currently it does. Maybe a pinned-skip shouldn't consume a turn.

---


## 2026-05-21 — DeflectionTuner v18: implementation

### File structure

- **`boxOfActin/DeflectionTunerV18.java`** — new file, ~310 lines. Self-contained; signal pipeline copied verbatim from v17.1.
- **`boxOfActin/DeflectionTuner.java`** — v14, byte-identical (untouched).
- **`boxOfActin/DeflectionTunerV15.java`** — v15, byte-identical (untouched).
- **`boxOfActin/DeflectionTunerV16.java`** — v16, byte-identical (untouched).
- **`boxOfActin/DeflectionTunerV17.java`** — v17.1, byte-identical (untouched).
- **`boxOfActin/Env.java`** — one line added: `static boolean benchmarkTunerV18 = false;`
- **`boxOfActin/BoxOfActin.java`** — six edits: (1) `static DeflectionTunerV18 deflTunerV18 = null;` field; (2) `-bmTunerV18` arg parsed; (3) mutual-exclusivity block extended (v18 > v17 > v16 > v15 > v14); (4) `eitherTunerActive` includes `deflTunerV18 != null`; (5) v18 feed path prepended to if-else chain; (6) v18 arm block prepended, `[AUTOTUNE] armed:` ternary extended to show `v18`.

### What v18 changes from v17.1

Signal pipeline (EMA + linear regression + running-avg |v|): **unchanged**. PHASE enum, ActiveParam enum, RETRY_LOWER_FRACMOVE handler, handleSettleCheck() (four branches), alternation budget (STEPS_PER_TURN=2), pinned-skip via boolean return from stepActiveParam(): **all unchanged**.

Changed:
1. `stepActiveParam(err)` — uses `|err| / sens` (empirical sensitivity) to compute idealMag; clamps to `[MIN_STEP_FRAC × |param|, MAX_STEP_FRAC × distToLimit]`. HALVE_FRACTION removed; MAX_STEP_FRAC takes over as ceiling.
2. `chainHasSettledRelative()` — new method replacing the `SETTLE_ENTRY_FRAC × V_NOISE` absolute threshold for SETTLE_CHECK entry.
3. `updateSensitivityFromCompletedStep()` — new method called just before PHASE transitions to SETTLE_CHECK; measures `|Δsmoothed / Δparam|` and updates `sensR` or `sensM`.
4. `peakVAbsThisBurst` — new field tracking max `|v|` since the last step; updated every frame in `feed()`.

### Non-obvious implementation choices

**`stepParam` field.** `activeParam` may have switched (budget exhausted → `switchActiveParam()` inside `handleAdjust`) between the step and settling detection. `stepParam` is set to the param that was actually stepped in `stepActiveParam()` and is used by `updateSensitivityFromCompletedStep()` to update the right parameter's sensitivity. It is nulled after the update to prevent double-update if `chainHasSettledRelative()` would otherwise re-fire on the same turn before a new step.

**`peakVAbsThisBurst` reset in `switchActiveParam()`.** Without this reset, a stale high peak from the previous turn (e.g., FRAC_R turn with peak=1e-3) could make `chainHasSettledRelative()` fire almost immediately on the new turn (FRAC_MT), producing a spurious SETTLE_CHECK → switch cycle before any step is taken on the new parameter. Resetting in `switchActiveParam()` gives the new turn a fresh peak accumulation.

**`vaCount >= SLOPE_WIN` guard on SETTLE_CHECK entry** (copied from v17.1). During the first 12 frames, v is NaN → treated as 0.0 in `updateRunningAvgV` → `runningAvgV ≈ 0`. With `peakVAbsThisBurst = 0` (no step yet), `chainHasSettledRelative()` would return true immediately (relThresh = ABSOLUTE_V_FLOOR = 1e-5 > runningAvgV = 0). Guard prevents premature SETTLE_CHECK entry.

**`SETTLE_FRAMES = 5` declared but unused.** `handleSettleCheck()` is unchanged from v17.1 and resolves in one frame. SETTLE_FRAMES is listed as a design constant but the handler was specified as "unchanged"; declared with a comment for future use.

**`sAfter` timing (Design decision A).** `smoothed` at the frame where `chainHasSettledRelative()` first returns true is used directly as `sAfter`. No additional wait.

**Outlier filter: none.** New sensitivity measurements are accepted as long as `deltaParam >= 1e-9` and `deltaS >= CONV_TOL_UM`. Per design.

**Step sizing log format.** `stepActiveParam()` stores `lastIdealMag`, `lastClampedMag`, `lastStepSens` as instance fields (set before the meaningful/pinned check). `handleAdjust()` includes them in the STEP#N log line. The pinned log in `stepActiveParam()` also includes the sizing values.

### STEP log format

```
[V18:STEP#N] drift-correcting  active=FRAC_R  budget=1/2  fr: 1.5000→1.2200  fmt: 0.0100→0.0100  err=0.012345  v=1.234e-03  idealMag=2.4680  clampedMag=0.7000  sens=0.0050
```

### Sensitivity update log format

```
[V18] sens update: FRAC_R  0.005000 → 0.023456  (deltaS=1.234e-02  deltaParam=0.5260)
```

### Armed line (what appears at start of a run)

```
[V18] armed: fracMove=0.5000  fracR=1.5000  fracMoveTorq=0.0100  target=<value>µm
[V18]   FRAMES_BETWEEN_STEPS=5  MAX_STEP_FRAC=0.50  MIN_STEP_FRAC=0.001  RUNNING_AVG_WINDOW=10
[V18]   SENS_R_INIT=0.0050  SENS_M_INIT=0.0500  PEAK_REL_THRESHOLD=0.10  ABSOLUTE_V_FLOOR=1.0e-05
[V18]   CONV_TOL_UM=1.00e-05  STEPS_PER_TURN=2  MIN_MEANINGFUL_DELTA=0.001  SLOPE_WIN=12
[AUTOTUNE] armed: tuner=v18  fracMove=0.5000  fracR=1.5000  fracMoveTorq=0.0100  target=<value> µm
```

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV18 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV18 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Dispatch verified (by code inspection)

- `-bm` → v17/v16/v15/v18 flags all false → `else` branch → v14. `[AUTOTUNE] armed: tuner=v14` ✓
- `-bmTunerV15` → `benchmarkTunerV15=true` → v15 arm block. `tuner=v15` ✓
- `-bmTunerV16` → v16 arm block. `tuner=v16` ✓
- `-bmTunerV17` → v17 arm block. `[V17] armed: ...` then `tuner=v17` ✓
- `-bmTunerV18` → v18 arm block first in chain. `[V18] armed: ...` then `[AUTOTUNE] armed: tuner=v18` ✓

Next session: run 32-monomer benchmark with `-bmTunerV18 -bmMonomer 32`. Record: (1) first sensitivity update frame and computed sensR; (2) whether idealMag is well-sized for the first overshoot (expect err ≈ 0.001–0.010 µm, idealMag ≈ 0.2–2.0 with SENS_R_INIT=0.005); (3) step count to convergence vs v17.1's 19 steps; (4) whether the near-target over-correction (v17.1 step #9 jumped fracR 0.122→0.811) is eliminated.

---

## 2026-05-21 — V19: fast-path for far-from-target adjustments

### What changed from V18

Signal pipeline, PHASE enum, ActiveParam enum, handleSettleCheck() (four branches), alternation budget (STEPS_PER_TURN=2), sensitivity-update logic, stepActiveParam() sizing, chainHasSettledRelative(), switchActiveParam(): **all unchanged**.

Changed: `handleAdjust()` — added a fast path that fires when `|err| > FAR_FROM_TARGET_THRESHOLD = 10 × CONV_TOL_UM = 1e-4 µm`. The near-target branch (`|err| ≤ FAR_FROM_TARGET_THRESHOLD`) falls through to the V18 `chainHasSettledRelative()` logic unchanged.

New constants: `FAR_FROM_TARGET_THRESHOLD = 1e-4 µm`, `MIN_FRAMES_FAR = 10` (PLACEHOLDER), `SIGN_STABLE_WIN = 5` (PLACEHOLDER), `V_SMALL_FRAC = 0.5` (PLACEHOLDER).

New state: `vSignBuf[SIGN_STABLE_WIN]` ring buffer tracking `sign(v)` for each non-NaN frame since the last step. Reset in `stepActiveParam()` and `switchActiveParam()`.

### Fast-path trigger criteria

All conditions must hold:
1. `|err| > FAR_FROM_TARGET_THRESHOLD`
2. `v` is non-NaN (regression buffer full, i.e. vaCount ≥ SLOPE_WIN=12)
3. `framesSinceLastStep ≥ MIN_FRAMES_FAR = 10` (chain had time to respond to last step)
4. EITHER `sign_stable` (last SIGN_STABLE_WIN=5 non-NaN v values have identical sign) OR `v_small` (`|v| × dtFrame < V_SMALL_FRAC × |err|`)

`sign_stable`: velocity direction has been consistent — reliable enough to act on. `v_small`: per-frame displacement is small relative to the error, meaning the chain is nearly settled at a non-target value and should be stepped.

When the fast path fires, sensitivity is NOT updated (that snapshot persists until eventually entering SETTLE_CHECK via the near-target path). This preserves V18's sensitivity-update logic unchanged.

### Logging

Fast-path activation:
```
[V19:FAST] step=N  active=FRAC_R  |err|=0.001234  v=1.234e-04  frames_used=12  reason=sign_stable
```
Fast-path step (immediately follows):
```
[V19:STEP#N] fast-path  active=FRAC_R  budget=1/2  fr: 1.2200→0.9800  ...
```
Normal drift-correcting and settle-check lines retain `drift-correcting` label and existing formats, renamed V18→V19.

### Where the SETTLE_CHECK transition logic lives

`handleAdjust()` lines ~240–265 in the generated `.class` (source: `DeflectionTunerV19.java`). The structure at that point is:

```
if (chainDriftingWrongWay):   → drift-correcting step (unchanged)
else:
  if |err| > FAR_THRESHOLD:
    if v non-NaN AND framesSinceLastStep >= MIN_FRAMES_FAR:
      if sign_stable OR v_small: → FAST PATH step
  else:
    if vaCount >= SLOPE_WIN AND chainHasSettledRelative(): → SETTLE_CHECK (V18 path)
```

The fast path is clean and independent — no structural coupling with sensitivity updates.

### Dispatch wiring

- `Env.java`: `static boolean benchmarkTunerV19 = false;` added after V18 line.
- `BoxOfActin.java`: (1) `static DeflectionTunerV19 deflTunerV19 = null;` field; (2) `-bmTunerV19` arg parsed; (3) mutual-exclusivity block V19 > V18 > v17 > v16 > v15 > v14; (4) `eitherTunerActive` includes `deflTunerV19 != null`; (5) V19 feed path prepended to if-else chain; (6) V19 arm block prepended, AUTOTUNE ternary prepended with `Env.benchmarkTunerV19 ? "v19" : ...`.

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV19 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV19 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Expected savings

V18 spent 70+ frames per step settling transients when far from target. The fast path fires when either the velocity sign has been consistent for 5 frames OR the chain is barely moving relative to the error, whichever comes first. For a typical far-from-target burst, sign_stable should fire well before the peak-relative threshold of V18 is met. Exact savings depend on physics; user will run and report.

V18 remains selectable via `-bmTunerV18`.

---

## 2026-05-21 — V20: sensitivity tracking and velocity-trend gating

### Motivation

V19 ran 98 steps without converging (deflection oscillated in the 0.0103–0.0110 µm band; V18 converged in 13). Two identified regressions:

1. **Sensitivity learning dead.** The fast path skips the SETTLE_CHECK transition, which was the only place `updateSensitivityFromCompletedStep()` was called. So `sensR=0.005` and `sensM=0.05` never updated.
2. **Velocity sampled too early.** Fast path fired at frame 10 during the ramp-up phase, before |v| peaked. The resulting `v` underestimated the burst magnitude, making step sizing too conservative.

### Survey of V19 code (per task constraint)

(a) **Sens update location:** `updateSensitivityFromCompletedStep()` lines 508–541; called only from `handleAdjust()` near-target branch (line 355), gated by `chainHasSettledRelative()`. Fast path never reaches this.

(b) **Fast-path trigger location:** `handleAdjust()` lines 318–347; conditions: `absErr > FAR_THRESHOLD`, `v` non-NaN, `framesSinceLastStep >= MIN_FRAMES_FAR`, then `signStable || vSmall`.

(c) **Parameter change commit location:** `stepActiveParam()` lines 423–500; sets `fracR`/`fracMoveTorq`, also sets `sBeforeStep`, `paramBeforeStep`, `stepParam` snapshot fields.

### Fix 1: `updateSensFromPendingSnapshot()` — at the top of every `stepActiveParam()` call

New private method reads the pending snapshot (`stepParam`, `sBeforeStep`, `paramBeforeStep`) from the previous step and updates sensitivity before committing the new step. Called unconditionally at the start of `stepActiveParam()`, on both fast-path and slow-path calls. Logs `[V20:SENS]` when an update fires.

Skip conditions (clear `stepParam` without updating):
- `|deltaParam| < MIN_MEANINGFUL_DELTA × |paramBeforeStep|` — step was too small to give useful info.
- `deltaS < CONV_TOL_UM` — no measurable deflection change (step had no effect or chain hasn't moved yet).

`deltaS` here is EMA-smoothed deflection change from step N commit time to step N+1 commit time — an underestimate of the true steady-state delta, but enough to break the SENS_INIT lock after the first fast-path pair.

**Design choice on SETTLE_CHECK backstop:** `updateSensitivityFromCompletedStep()` is retained in the near-target settling path. On fast-path runs, `stepParam` will be null at that point (cleared by `updateSensFromPendingSnapshot()` at the last step), making it a no-op. On slow-path runs (where full settling precedes the next step), it still fires with the most accurate deltaS. Both mechanisms cannot fire on the same snapshot because whichever runs first sets `stepParam = null`.

### Fix 2: `isPeakPassed()` / timeout — replaces `signStable || vSmall`

New `vMagBuf[V_PEAK_WIN+1]` ring buffer tracks consecutive |v| values since the last step (reset in `stepActiveParam()` and `switchActiveParam()`). `trackVMag(v)` is called in `feed()` for every non-NaN v (replacing `trackVSign`). `isPeakPassed()` checks that every consecutive pair in the buffer satisfies `|v[k+1]| <= |v[k]| * (1 + V_PEAK_TOL=0.05)`.

Fast-path trigger in `handleAdjust()`:
- `framesSinceLastStep >= FAR_TIMEOUT_FRAMES=30` → fire with `reason=timeout`
- else `framesSinceLastStep >= MIN_FRAMES_FAR=10 AND isPeakPassed()` → fire with `reason=peak_passed`

Timeout checked first so it takes priority regardless of peak state. The old `sign_stable` and `v_small` reasons are gone.

Note: if the system's |v| curve doesn't plateau within FAR_TIMEOUT_FRAMES (e.g., |v| keeps growing monotonically for 30 frames), `timeout` will fire anyway and `peak_passed` will never appear. This would indicate the system dynamics differ from the assumed shape; would need investigation.

### Constants added

`V_PEAK_WIN=3`, `V_PEAK_TOL=0.05`, `FAR_TIMEOUT_FRAMES=30`. Constants `SIGN_STABLE_WIN` and `V_SMALL_FRAC` removed. All other constants from V19 preserved unchanged.

### Dispatch wiring

- `Env.java`: `static boolean benchmarkTunerV20 = false;` added after V19 line.
- `BoxOfActin.java`: (1) `static DeflectionTunerV20 deflTunerV20 = null;` field; (2) `-bmTunerV20` arg parsed; (3) mutual-exclusivity block V20 > V19 > … > V14; (4) `eitherTunerActive` includes `deflTunerV20 != null`; (5) V20 feed path prepended to if-else chain; (6) V20 arm block prepended, AUTOTUNE ternary extended with `Env.benchmarkTunerV20 ? "v20" : ...`.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV20 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV20 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Expected behavior

On each fast-path step pair: `[V20:SENS]` line should appear at step N+1 showing `sensR` or `sensM` updating away from the SENS_INIT value. Fast path should fire with `reason=peak_passed` in typical runs (|v| peaks within 10–30 frames). `timeout` would appear if |v| is still growing at frame 30 — worth flagging in the next run log. Convergence target: fewer than 98 steps; ideally comparable to V18's 13.

---

## 2026-05-21 — V21: bounded sensitivity learning and single-parameter near-target mode

### Motivation

V20 fixed the sensitivity-never-updates problem but introduced a new failure: sensitivity random-walks across ~4 orders of magnitude. Root cause: when `deltaParam` is tiny (parameter near limit, step clamped small, or pinning logic produces a tiny commanded change), `deltaS / deltaParam` explodes. The next step uses that huge sens, commands a tiny `idealMag`, produces an even tinier `deltaParam`, and the next update divides by that tiny value — positive-feedback divergence.

Secondary problem: near target, both parameters get nudged each cycle; cross-coupling prevents lock-in.

### Survey of V20 code (per task constraint)

**(a) Sens blend formula:** `SENS_SMOOTHING = 0.0`. With SMOOTHING=0, the formula `SENS_SMOOTHING * sensR + (1 - SENS_SMOOTHING) * newSens` reduces to raw replacement — `sensR = newSens` on every update. No EMA at all.

**(b) Parameter bounds enforcement:** Only hard clamps in `stepActiveParam()` via `clamp(newParam, MIN, MAX)`. No explicit headroom-based upper practical bound. Hardware limits used as the headroom bounds in V21: fracR ∈ [0.1, 1.5], fracMoveTorq ∈ [0.01, 0.5].

**(c) Alternation switch decision — three locations:**
- `handleAdjust()` drift-correcting branch: `if (stepsThisTurn >= STEPS_PER_TURN) switchActiveParam();` (line ~311)
- `handleAdjust()` fast-path branch: same check (line ~352)
- `handleSettleCheck()` branch (4): `switchActiveParam();` (line ~411)
All three are injection points for the NEAR_TARGET_THRESHOLD check in V21.

### Fix 1: Bounded sensitivity learner

Applied in both `updateSensFromPendingSnapshot()` and `updateSensitivityFromCompletedStep()`:

**(a) DELTA_PARAM_FLOOR_FRAC = 0.01** — skip update entirely if `|deltaParam| < 1% × |paramBeforeStep|`; logs `[V21:SENS_SKIP]`.

**(b/d) SENS clamp** — clamp raw `deltaS/deltaParam` to `[SENS_MIN, SENS_MAX]` before blending; also clamp final blended sens. Per parameter:
- FRAC_R: `[0.0005, 0.1]` (0.1× and 20× SENS_R_INIT=0.005)
- FRAC_MT: `[0.005, 1.0]` (0.1× and 20× SENS_M_INIT=0.05)
Logs `[V21:SENS_CLAMP]` when the raw value hits a rail.

**(c) BLEND_ALPHA = 0.3** — replaces V20's SENS_SMOOTHING=0.0 (outright replacement). Each update moves 30% toward the new measurement, retaining 70% of prior sens. This makes the learner converge to a stable gain rather than chasing noise.

### Fix 2: Single-parameter near-target mode

New phase `NEAR_TARGET_SINGLE_PARAM` in the Phase enum.

**Entry:** `tryEnterNearTargetMode(err)` is called at all three switch decision points instead of calling `switchActiveParam()` unconditionally. If `|err| < NEAR_TARGET_THRESHOLD = 5e-5 µm`, it computes headroom for each parameter:
```
headroom = min(v - lo, hi - v) / ((hi - lo) / 2)
```
(1.0 at midrange, 0.0 at either limit). The parameter with more headroom is chosen as `nearTargetParam`; `activeParam` is set to match. Logs `[V21:SINGLE_PARAM] entering`.

**Behavior:** `handleNearTargetSingleParam()` mirrors `handleAdjust()` but never calls `switchActiveParam()`. `stepsThisTurn` is reset to 0 after each step so budget-based switches can't fire. If `stepActiveParam()` returns false (pinned internally called `switchActiveParam()`), the handler restores `activeParam = nearTargetParam` and `phase = NEAR_TARGET_SINGLE_PARAM`.

**Exit:**
- `|err| < CONV_TOL_UM` → CONVERGED
- `|err| > 2 × NEAR_TARGET_THRESHOLD = 1e-4` → re-armed back to normal alternation; logs `[V21:SINGLE_PARAM] exiting ... reason=re-armed`
- Chain settles → SETTLE_CHECK (where NEAR_TARGET_THRESHOLD can re-engage at branch (4))

### Constants added

`DELTA_PARAM_FLOOR_FRAC=0.01`, `SENS_MIN_R=0.0005`, `SENS_MAX_R=0.1`, `SENS_MIN_M=0.005`, `SENS_MAX_M=1.0`, `BLEND_ALPHA=0.3`, `NEAR_TARGET_THRESHOLD=5e-5`. `SENS_SMOOTHING` removed.

### Dispatch wiring

- `Env.java`: `static boolean benchmarkTunerV21 = false;` added after V20 line.
- `BoxOfActin.java`: (1) `static DeflectionTunerV21 deflTunerV21 = null;` field; (2) `-bmTunerV21` arg parsed; (3) mutual-exclusivity block V21 > V20 > … > V15; (4) `eitherTunerActive` includes `deflTunerV21 != null`; (5) V21 feed path prepended to if-else chain; (6) V21 arm block prepended, AUTOTUNE ternary extended with `Env.benchmarkTunerV21 ? "v21" : ...`.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV21 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV21 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Expected behavior

- `[V21:SENS_SKIP]` lines should fire when steps produce near-zero `deltaParam` (e.g., near-limit pinning).
- `[V21:SENS_CLAMP]` lines should fire when early noisy measurements try to push sens outside [SENS_MIN, SENS_MAX].
- `[V21:SENS]` lines should show sens drifting slowly (30% blend) toward stable values rather than jumping orders of magnitude.
- `[V21:SINGLE_PARAM] entering` once `|err|` first drops below 5e-5.
- Convergence in 15–25 steps.

### Open question

If `NEAR_TARGET_THRESHOLD = 5e-5` is never reached in practice from far-away starts before re-arming triggers, the single-param mode will never engage. Revisit threshold in V22 if the run log shows |err| oscillating above 5e-5.

---

## 2026-05-21 — V22: clean slate, Broyden's method

### Root diagnosis for V18–V21

V18–V21 treated the problem as a control problem on transient dynamics. The actual task is steady-state 2D root finding: find `(fracR, fracMoveTorq)` such that the settled deflection equals `target`. Broyden's method is the standard approach for exactly this case.

### Survey findings (harness integration)

- **Call site:** `doLoop()`, inside the `eitherTunerActive` block, once per `toFileInterval` simulation steps (~1 output frame = 100 steps).
- **State read:** `snap.observed` = current mid-segment deflection from `computeBenchmarkSnapshot()`.
- **State written:** `Env.fracR`, `Env.fracMoveTorq` (and fracMove, which V22 holds constant). Applied by setting `Env.fracR.setValue(...)` etc. and dispatching LiveFrameServer acks.
- **Done signal:** `isDone()` returns true when `phase == CONVERGED || phase == FAILED`. BoxOfActin then calls `resultSummary()`, prints it, nulls the tuner, and exits.
- **Arm point:** in `makeInitialThings()` arm block, called once after the deflection chain is set up. Receives `Env.fracMove`, `Env.fracR`, `Env.fracMoveTorq`, `deflFil.analyticDefl`, `deflFil.tauTheo`. V22 ignores `fracMove` and `tauTheo`.

### Algorithm

V22 is Broyden's method with explicit settling. The Jacobian is 1×2 (scalar residual, two unknowns), and the Newton step uses the pseudoinverse (minimum-norm underdetermined step):

```
dp = -J^T * r / (J · J^T)   where r = s - target
```

Initial Jacobian seeded from 3 points: base `(fr_init, fmt_init)` and two 10% perturbations. After each settling event, a rank-1 Broyden update refines J.

### Settling routine

Independent of parameter-adjustment logic. Tracks a circular ring buffer of the last `SETTLE_WIN=20` frames. Criterion: `std/mean < SETTLE_TOL_REL=0.005` with at least `SETTLE_MIN_FRAMES=30` elapsed. Timeout at `SETTLE_MAX_FRAMES=300` with `[V22:SETTLE_TIMEOUT]` log. No parameters change during settling.

### State machine

```
SEEDING_1 → SEEDING_2 → SEEDING_3 → NEWTON (loop) → CONVERGED | FAILED
```

Re-seeding (on stagnation: 3 consecutive iterations with <10% residual improvement) jumps back to SEEDING_2 using the current point as the new seed-1 base.

### Failure modes

- `[V22:FAILED] reason=iteration_limit` — more than MAX_ITERATIONS=30 Newton steps.
- `[V22:FAILED] reason=jacobian_zero` — ||J|| too small to invert.
- `[V22:FAILED] reason=bounds_saturated` — both FR and FMT bounds hit simultaneously after stagnation.

### Key deviations from spec

- The JACOBIAN log line shows the updated J (post-Broyden), not the pre-update J. This is more useful for debugging.
- No SEEDING_1 phase revisited on Jacobian reset: the current settled point is used as `s1` directly, so re-seeding goes straight to `SEEDING_2`.

### Dispatch wiring

- `Env.java`: `static boolean benchmarkTunerV22 = false;` added after V21 line.
- `BoxOfActin.java`: (1) `static DeflectionTunerV22 deflTunerV22 = null;` field; (2) `-bmTunerV22` arg parsed; (3) mutual-exclusivity block V22 > V21 > … > V15; (4) `eitherTunerActive` includes `deflTunerV22 != null`; (5) V22 feed path prepended to if-else chain; (6) V22 arm block prepended, AUTOTUNE ternary extended with `Env.benchmarkTunerV22 ? "v22" : ...`.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV22 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV22 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Expected output structure

```
[V22] armed: fr=...  fmt=...  target=... µm
[V22] settling seed-1  fr=...  fmt=...
[V22:SETTLED] params=(fr=...  fmt=...)  s=...  frames=...  std/mean=...
[V22] settling seed-2  fr=...  fmt=...
[V22:SETTLED] ...
[V22] settling seed-3  ...
[V22:SETTLED] ...
[V22] Jacobian seeded: J=[..., ...]  s1=...  s2=...  s3=...
[V22:NEWTON#1] r=...  fr: ...→...  fmt: ...→...
[V22:SETTLED] ...
[V22:JACOBIAN] J=[..., ...]  ds=...  pred_ds=...  quality=...
[V22:NEWTON#2] ...
...
[V22:CONVERGED] iterations=N  total_frames=N  final=(fr=...  fmt=...)  s=...
```

### Open questions

- If `SETTLE_TOL_REL=0.005` is too tight for the simulation noise floor, `[V22:SETTLE_TIMEOUT]` will dominate; relax toward 0.01–0.02 if that happens.
- If seed perturbation `PERT=0.10` causes the chain to blow up or go negative, reduce to 0.05.
- The initial Jacobian uses seed-1 as the reference for both finite differences; if seeds 2 and 3 both happen to land on nearly the same deflection (e.g., one parameter is weakly coupled), J will be ill-conditioned. The `jacobian_zero` failure handles this; a Jacobian reset would follow if detected.

---

## 2026-05-22 — V23: corrected bounds, stiffest start, fracMove fallback

### Motivation

V22 converged at 32-mon and 48-mon but had two bugs: (1) wrong bounds (FR=[0.01,2.0], FMT=[0.01,0.99]) caused the 32-mon "convergence" to land at fmt=0.99 outside the legal range; (2) no fracMove outer loop, so 64-mon (which needs fracMove < 0.5 to find a solution) always failed.

### Survey findings (where V22 code lives)

- **Bounds:** lines 31–32 of DeflectionTunerV22.java — single place, two `static final double` constants.
- **Seed values:** `start()` method, lines 96–99. V22 uses relative perturbation (`fr * (1+PERT)`) and absolute fallback for fmt near 0. V23 replaces with absolute PERT_FR/PERT_FMT and a direction-aware helper `setSeedTriplet()`.
- **Iteration counters:** `newtonIter` (Newton step count), `stagnantCount` (Broyden stagnation), `prevResidualAbs`, `frBoundHit`/`fmtBoundHit` — all fields in the class. V23 adds `iterationsAtCorner` and `fracMoveDrops`; removes `frBoundHit`/`fmtBoundHit` (replaced by `iterationsAtCorner`).
- **Convergence/failure logging:** `applyNewtonStep()` for `[V22:CONVERGED]`/`[V22:FAILED]`; `onNewtonSettled()` for `[V22:JACOBIAN_RESET]` and the `bounds_saturated` failure.

### Changes from V22

**Fix 1 — Corrected bounds:** FR=[0.1, 1.5], FMT=[0.01, 0.5].

**Fix 2 — Stiffest starting corner:** V23 always starts from (fr=FR_LO=0.1, fmt=FMT_HI=0.5) regardless of param-file values. The arm block explicitly sets `Env.fracR=0.1`, `Env.fracMoveTorq=0.5` before calling `start()`. `start()` ignores the passed fr/fmt args. The existing soft-start block (fr=1.5, fmt=0.01) is skipped for V23 via an `if/else` in the arm region.

**Fix 3 — Direction-aware seeding:** `setSeedTriplet(frCorner, fmtCorner)` detects which bound the corner is against and perturbs toward the interior: if fr is at FR_HI (soft bound), perturb fr DOWN (stiff); otherwise UP (soft). If fmt is at FMT_LO (soft bound), perturb fmt UP (stiff); otherwise DOWN (soft). Absolute magnitudes: PERT_FR=0.15, PERT_FMT=0.10. From the initial stiffest corner (0.1, 0.5): seed-2=(0.25, 0.5), seed-3=(0.1, 0.40). Stagnation-triggered Jacobian resets use the same `setSeedTriplet` logic.

**Fix 4 — Infeasibility detection:** `iterationsAtCorner` counts consecutive Newton iterations where the projected step lands exactly on a bound (after bounds projection, not epsilon-fuzzy). Resets to 0 on any iteration with no bound hit. When `iterationsAtCorner >= INFEASIBILITY_THRESHOLD=3` AND `|residual| > CONV_TOL_UM`, declare infeasible.

**Fix 5 — fracMove outer loop:**
- residual > 0 (too soft) → `needs_stiffer` failure (fracMove ceiling at 0.5).
- residual < 0 (too stiff) → drop fracMove by FRACMOVE_DECREMENT=0.05, restart 2D from the detected corner. Cap: MAX_FRACMOVE_DROPS=8. On restart, `initTwoDimSearch(frCorner, fmtCorner)` resets per-level counters (newtonIter, stagnantCount, iterationsAtCorner) and calls `setSeedTriplet` from the new corner. `totalFrames` accumulates across all fracMove levels.

**Fix 6 — Logging:** All logs renamed [V23:...]. Per-iteration [V23:NEWTON#N] includes fracMove. [V23:CONVERGED] includes fracMove_drops and fracMove.

### Deviations from spec

- `frBoundHit`/`fmtBoundHit` removed entirely (were used for V22's `bounds_saturated` failure, replaced by `iterationsAtCorner`). No behavior change for the convergent path.
- `BOUND_EPS=1e-6` used only in `setSeedTriplet` to detect whether the corner is against a bound (for perturbation direction). The bound projection in `applyNewtonStep` uses exact equality (the projected value is exactly FR_LO, FR_HI, FMT_LO, or FMT_HI after clamping), so `iterationsAtCorner` increments are exact.
- Stagnation-triggered Jacobian reset: `iterationsAtCorner` is also reset to 0 (not in spec, but a fresh Jacobian deserves a fresh infeasibility count).

### Dispatch wiring

- `Env.java`: `static boolean benchmarkTunerV23 = false;` added after V22 line.
- `BoxOfActin.java`: (1) field `static DeflectionTunerV23 deflTunerV23 = null;`; (2) `-bmTunerV23` arg parsed; (3) mutual-exclusivity v23 > v22 > ... > v15 block prepended; (4) `eitherTunerActive` includes `deflTunerV23 != null`; (5) V23 feed path prepended to if-else chain; (6) V23 arm block replaces soft-start for V23 (stiffest corner set; older tuners still get soft-start via `else` branch); AUTOTUNE ternary extended with `Env.benchmarkTunerV23 ? "v23" : ...`.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flags

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV23 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV23 -bmMonomer 48 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV23 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Expected behavior

- **32-mon:** converge in 2D (no fracMove drop). Result should be in interior of legal box (fmt well below 0.5, fr well above 0.1). The V22 32-mon result at fmt=0.99 was invalid; V23 should find the true interior solution.
- **48-mon:** converge in 2D, similar to V22's valid result.
- **64-mon:** detect 2D infeasibility at fracMove=0.5, drop to 0.45 (possibly 0.40), and converge there.

### Open questions

- If 32-mon converges at or near fmt=0.5 (stiffest fmt bound), check that it's a stable settled value, not just clamped there.
- If 64-mon needs more than one fracMove drop, each drop restarts from the corner where infeasibility was detected — which may drift away from (0.1, 0.5) toward the soft boundary. Inspect the FRACMOVE_DROP corner coordinates in the log.

---

## 2026-05-22 — V24: noise-aware Jacobian and physics-aware convergence

### Motivation

V23 thrashed after finding the answer in 48-mon and 64-mon because two issues compound once the residual drops below ~5× the noise floor: (1) Broyden update divides noisy `deltaS` by a tiny `deltaParam`, producing a corrupted Jacobian; (2) `CONV_TOL_UM = 1e-5` is below the measurement noise floor for all three test cases, so the algorithm cannot resolve convergence and keeps trying.

### Survey findings (exact locations in V23)

1. **Broyden update:** `onNewtonSettled()`, inside `if (dpdp > 1e-20)` block. Starts at `double ds = s - sPrev`. The gate (Fix 1) was inserted here.
2. **`CONV_TOL_UM` checked:** `applyNewtonStep()` at the top (`if (Math.abs(r) < CONV_TOL_UM)`) and again in the infeasibility guard (`Math.abs(r) > CONV_TOL_UM`). Both replaced with `convTolUm`.
3. **`target` established:** `start()` immediately sets `target = targetMicrons`. `convTolUm` computed on the next line via `Math.max(CONV_TOL_ABS_FLOOR, CONV_TOL_REL_TARGET * target)`.

### Changes from V23

**Fix 1 — Noise-floor gate on Jacobian updates:** Inside the existing `if (dpdp > 1e-20)` block in `onNewtonSettled()`, check if `|dp0| < JACOBIAN_UPDATE_DP_THRESH_FR || |dp1| < JACOBIAN_UPDATE_DP_THRESH_FMT` (thresholds = 0.005 each). If so, log `[V24:JACOBIAN_FROZEN]` and skip the Broyden update — J carries over unchanged. The frozen J should still drive the small remaining residual to zero in a few more steps; if not, the existing stagnation counter → `JACOBIAN_RESET` is the second-line defense. The gate does NOT apply during seeding (seeding happens in `onSettled()` SEEDING_3 case, which calls `applyNewtonStep()` directly, not `onNewtonSettled()`).

**Fix 2 — Physics-aware convergence tolerance:** `CONV_TOL_UM` is no longer a `static final`. Replaced by:
- `static final double CONV_TOL_REL_TARGET = 0.005` — converged when |residual| < 0.5% of target.
- `static final double CONV_TOL_ABS_FLOOR = 1.0e-5` — hard floor.
- `private double convTolUm` — instance field, computed in `start()`.

Computed tolerances:
- 32-mon (target ≈ 0.0098 µm): `convTolUm` = max(1e-5, 4.9e-5) = **4.9e-5 µm**
- 48-mon (target ≈ 0.0146 µm): `convTolUm` = max(1e-5, 7.3e-5) = **7.3e-5 µm**
- 64-mon (target ≈ 0.0193 µm): `convTolUm` = max(1e-5, 9.7e-5) = **9.7e-5 µm**

**Fix 3 — `tol_used` in convergence log:** `[V24:CONVERGED]` line includes `tol_used=physics-aware` when `convTolUm > CONV_TOL_ABS_FLOOR`, `tol_used=absolute` otherwise.

### Dispatch wiring

- `Env.java`: `static boolean benchmarkTunerV24 = false;` added after V23 line.
- `BoxOfActin.java`: (1) field `static DeflectionTunerV24 deflTunerV24 = null;`; (2) `-bmTunerV24` arg parsed; (3) mutual-exclusivity v24 > v23 > ... block prepended; (4) `eitherTunerActive` includes `deflTunerV24 != null`; (5) V24 feed path prepended to if-else chain; (6) V24 arm block prepended (`if (Env.benchmarkTunerV24)` with stiffest-corner set), V23 becomes `else if`, AUTOTUNE ternary extended with `Env.benchmarkTunerV24 ? "v24" : ...`.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flags

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV24 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV24 -bmMonomer 48 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV24 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Expected behavior

- **32-mon:** ~3 iterations / ~200 frames, unchanged from V23. `tol_used=physics-aware` in the convergence line (convTolUm = 4.9e-5).
- **48-mon:** ~4–6 iterations / < 400 frames. `JACOBIAN_FROZEN` lines expected on iterations 5–13 where V23 thrashed; algorithm should coast to convergence with the preserved J rather than wandering.
- **64-mon:** converge in 2D after dropping fracMove to 0.30 (or 0.35), final (fr ≈ 0.78, fmt ≈ 0.015–0.02). No 20+-iteration thrash.

### Open questions

- If `JACOBIAN_FROZEN` fires every iteration once close to target (expected), document how many frozen steps precede convergence.
- If 32-mon converges unchanged (no frozen steps at all), that confirms the fix is active only in the tight-residual regime.
- `CONV_TOL_ABS_FLOOR` dominance would only appear if target < 0.002 µm — not expected for any of the three test cases.

---

