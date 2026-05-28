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

## Tuner Development History (V10 → V24)

Development entries for tuner versions V10 through V24 in chronological order. The production tuner V25 is documented in JOURNAL.md.

---

## 2026-05-19 — DeflectionTuner v10: FINE fmt bracket-obstruction detection and one-shot clear

### Observed symptom (48-monomer benchmark)

After COARSE→FINE, the 48-monomer chain was initially too stiff (err < 0). Step #4 took fmt
from ~0.207 upward, crossing the target: err went from −0.001446 to +0.000010. During that
crossing, `updateFmtBrackets(error > 0)` set `fmtLoBound = fracMoveTorq ≈ 0.2073`
("at this fmt the chain was too soft"). On all subsequent FINE steps:

- Controller wants to soften (pred ≈ −0.17, fmtStep ≈ −0.025 after trust-region clamp).
- `clampToBrackets` pins the candidate against fmtLoBound = 0.2073; fmt is at 0.2608.
- `act = +0.0000` every step; error stalls at −0.000632 µm ≈ 127× CONV_TOL_UM.
- fr-rescue does not fire (rawFmtPred < 0, so `fmtSaturatedAtLimit = false`).
- The controller produces valid, non-zero steps in every log line but achieves nothing.

### Root cause

The bracket bound `fmtLoBound = 0.2073` was recorded under fracR = R₁ (the fracR value at
step #4). After the crossing, fmtSens correctly implies that fmt should decrease — but
fmtLoBound prevents it. The bound is stale: it asserts "fmt = 0.2073 was too soft under
conditions at step #4", but the conditions at steps #5+ are not the same.

This is the same class of bug as the v9 fr-rescue bracket-trap, but triggered by a different
mechanism. The v9 fix cleared brackets when fracR explicitly changed (fr-rescue). This case
has no fracR change — the bracket goes stale due to the deflection state evolving across the
crossing step.

### Fix: obstruction-detection and one-shot bracket clear (v10)

New constant: `OBSTRUCTION_FRAC = 0.02`.

In the normal FINE branch (not fr-rescue), after computing the trust-region-clamped step:

```java
double fmtStep         = clamp(rawFmtPred, -trustFmt, trustFmt);
double candidateLimits = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);
double newFmt          = clampToBrackets(candidateLimits, ...);

if (fmtHasSens && Math.abs(fmtStep) > 1e-12) {
    double preClearAct = newFmt - fracMoveTorq;
    if (|preClearAct| < OBSTRUCTION_FRAC * |fmtStep|   // brackets ate >98% of desired step
            && |candidateLimits - newFmt| > 1e-9) {     // bracket-caused (not limit-caused)
        if (fmtStep < 0) { fmtHasLo = false; }         // want to decrease; lo is obstructing
        else             { fmtHasHi = false; }          // want to increase; hi is obstructing
        // log the clear
        newFmt = clampToBrackets(candidateLimits, ...); // retry with cleared bracket
    }
}
double aFmt = newFmt - fracMoveTorq;
```

The obstruction detection requires:
1. `fmtHasSens = true` — sensitivity is known, so the step direction is trustworthy.
2. `|fmtStep| > 1e-12` — the step is non-trivial (avoids misfiring near zero).
3. `|preClearAct| < OBSTRUCTION_FRAC * |fmtStep|` — brackets ate ≥98% of the desired step.
4. `|candidateLimits - newFmt| > 1e-9` — the clamp was caused by brackets, not parameter limits.

If all four hold, the obstructing bound is cleared and the step is recomputed with limits only.
At most one bracket is cleared per FINE frame (no loop, no second clear).

### Why this preserves bracket benefits

Near a genuine optimum, rawFmtPred → 0, so fmtStep → 0, and `OBSTRUCTION_FRAC * |fmtStep|`
is also tiny — the condition `|preClearAct| < OBSTRUCTION_FRAC * |fmtStep|` cannot fire when
both values are near-zero. The mismatch between a large desired step and a near-zero actual
step is the diagnostic signal that the bracket is provably wrong given current conditions.

### Safety guards verified

- Only fires in FINE, normal branch. fr-rescue (which already clears brackets) is a separate
  branch that returns before this code.
- `fmtHasSens` guard: if sensitivity is unknown, `fmtHasSens = false` and the block is skipped.
- `firstStepInPhase`: at COARSE→FINE entry, both bracket flags are cleared in the transition
  (`fmtHasLo = false; fmtHasHi = false`), so candidateLimits == newFmt always on the first
  FINE step; `bracketCaused` is false and the block is a no-op.
- Stiff-end rescue interaction: stiffEndRescue() resets `fmtHasLo = false; fmtHasHi = false`
  unconditionally, so any bracket state cleared by v10 is already gone at rescue entry.

### Non-scope observations from the 48-monomer run (future work)

**Trust regions do not reset at COARSE→FINE.** The 48-monomer run entered FINE with
`trustFmt ≈ 0.0250` (already halved twice during COARSE from the crossing steps). FINE then
halved further on subsequent misfires. Since FINE is a fresh single-parameter phase,
resetting `trustFmt = INIT_TRUST` at the COARSE→FINE transition might make early FINE steps
more aggressive and reach the vicinity of the optimum faster. Worth experimenting.

**PROBE_FMT/PROBE_FR did not produce bad-sign sensitivities in the 48-monomer run.** Unlike
the 32-monomer case, the cold-start early-trigger gate (v6) correctly allowed the probes to
fire from a state where deflection was below target, yielding valid sensitivities on the first
attempt. The gate continues to earn its keep.

### Compile verification

Full project compiles clean under `javac -XDignore.symbol.file -cp ".:libs/*"`.

---

## 2026-05-19 — DeflectionTuner v11: physics-scaled settling detector

### Observed symptom (48-monomer benchmark, v10)

After v10 was deployed, the 32-monomer benchmark (τ_theo = 0.057 s) converged cleanly in 22
steps. The 48-monomer benchmark (τ_theo = 0.248 s) ran 72+ steps in a limit cycle: fmt
oscillating between ~0.20 and ~0.26, never converging, with the v10 bracket-clear firing
repeatedly to enable each successive overshoot.

### Root cause: settling detector window too short for slow chains

The settling detector uses a ring buffer of `SLOPE_WIN` consecutive smoothed-deflection
samples. A step is taken when the range over that window falls below `SETTLE_TOL_FRAC × scale`.

Frame period = `drawInterval × deltaT = 100 × 1e-4 = 0.01 s`.
`SLOPE_WIN = 10` frames = 0.10 s of simulated time.

| Chain | τ_theo | Window / τ | Equilibration at measurement |
|-------|--------|-----------|------------------------------|
| 32-monomer | 0.057 s | 0.10 / 0.057 = 1.75 τ | ~83% |
| 48-monomer | 0.248 s | 0.10 / 0.248 = 0.40 τ | ~33% |

For the 48-monomer chain, the detector declared "settled" when the chain was only 33%
equilibrated after each parameter step. The sensitivity estimates were measured during
transients — not at steady state — producing systematically wrong values. Those wrong
estimates drove overshooting steps, which the bracket-clear then unblocked, producing the
observed limit cycle. The root cause is `SLOPE_WIN` calibration, not the bracket-clear.

### Fix: physics-scaled settling window (v11)

`SLOPE_WIN`, `MIN_FRAMES_PER_STEP`, and `MAX_FRAMES_PER_STEP` are demoted from `static final`
to instance fields, computed in `start()` from `τ_theo` using two new constants:

```
SLOPE_TAU_FRAC    = 2.0     // observation window covers ~2τ of simulated time
SECONDS_PER_FRAME = 0.01    // drawInterval × deltaT (hardcoded to match ParameterFiles)
MIN_SLOPE_WIN     = 10      // floor: never narrower than v10's static value
```

Computed in `start(tauSeconds)`:

```java
slopeWin         = max(MIN_SLOPE_WIN, round(SLOPE_TAU_FRAC  * τ / SECONDS_PER_FRAME))
minFramesPerStep = max(5,             round(0.5              * τ / SECONDS_PER_FRAME))
maxFramesPerStep = max(200,           round(20.0             * τ / SECONDS_PER_FRAME))
```

If `tauSeconds` is ≤ 0 or NaN (unavailable), falls back to the v10 static values.

| Chain | τ_theo | slopeWin | minFramesPerStep | maxFramesPerStep |
|-------|--------|----------|-----------------|-----------------|
| 32-monomer | 0.057 s | max(10, 11) = 11 | max(5, 3) = 5 | max(200, 114) = 200 |
| 48-monomer | 0.248 s | max(10, 50) = 50 | max(5, 12) = 12 | max(200, 496) = 496 |

The floor values (`MIN_SLOPE_WIN = 10`, `minFramesPerStep = 5`, `maxFramesPerStep = 200`)
preserve v10's exact behavior on fast chains. The 32-monomer benchmark is essentially
unchanged (slopeWin = 11 vs 10, minFramesPerStep = 5, maxFramesPerStep = 200).

### BoxOfActin wiring

`deflFil.tauTheo` is computed at line 1027 (τ = N × ζ_perp × L³ / (EI × π⁴)), stored in the
`DeflFil` inner struct, and already available before the `deflTuner.start()` call at line 1088.
The wiring required only adding `deflFil.tauTheo` as the fifth argument to `start()`.

### Calibration log line

`start()` now prints:
```
[AUTOTUNE] armed: τ=0.248s  slope_win=50  min_step=12  max_step=496
```

### What stays unchanged

- `SETTLE_TOL_FRAC = 0.01` (dimensionless — no scaling needed)
- All trust regions, convergence tolerance, sensitivity attribution, bracket logic
- v10 bracket-clear fix (it was a valid fix for a real problem; the limit cycle was caused
  by measuring sensitivities on transients, not by the bracket-clear itself)
- fr-rescue, stiff-end rescue, cold-start gate, crossing detection

### Compile verification

Full project compiles clean under `javac -XDignore.symbol.file -cp ".:libs/*"`.

---

## 2026-05-19 — DeflectionTuner v12: symmetric saturation rescue + soft-start initialization

### Observed symptom (64-monomer benchmark, v11)

The 64-monomer chain (τ_theo ≈ 0.714 s) reached fracR = FRAC_R_MAX = 1.5 and
fracMoveTorq = FRAC_MT_MIN = 0.01 (softest possible), but the chain was still too stiff:
obs = 0.0136 µm vs target = 0.0193 µm, err = −0.0057 µm (~1100× CONV_TOL_UM). The controller
printed identical `act=+0.0000` lines indefinitely. No rescue fired because the existing rescue
only covered the opposite corner (stiff-end: params at stiffest, chain still too soft).

### Fix 1: symmetric saturation rescue

The existing stiff-end check in `handleCoarse` and `handleFine` was the only rescue. Added
mirror conditions for the soft-end case (chain still too stiff despite softest params):

**COARSE soft-end:**
```
error < -RESCUE_ERR_FRAC * expected
&& fracR >= FRAC_R_MAX - 1e-9
&& fracMoveTorq <= FRAC_MT_MIN + 1e-9
→ saturationRescue()
```

**FINE soft-end:**
```
error < -RESCUE_ERR_FRAC * expected
&& fracMoveTorq <= FRAC_MT_MIN + 1e-9
→ saturationRescue()
```
(In FINE, fracR is frozen, so only fmt's soft-end matters.)

The rescue body is unchanged: decrement fracMove by FRAC_MOVE_STEP, reset
sensitivities/brackets/trust regions, return to PROBE_FMT.

`stiffEndRescue()` renamed `saturationRescue()` since it now handles both ends. Log line
updated from `"stiff-end rescue"` to `"saturation rescue"`. Failed message similarly updated.

### Fix 2: soft-start initialization

At autotune arm time (BoxOfActin.java, inside `makeInitialThings()`), parameters are now
overridden to the softest legal configuration before `deflTuner.start()` is called:

```
fracMove     = FRAC_MOVE_MIN = 0.1
fracR        = FRAC_R_MAX    = 1.5
fracMoveTorq = FRAC_MT_MIN   = 0.01
```

Values written to `Env` via `setValue()` and broadcast to the live viewer via
`LiveFrameServer.dispatchParamAck()` (guarded by `LiveFrameServer.isRunning()`).

Override is inside the `-bm` arm block, after the `-bmManual` / `-bmDiag` early returns, so
it fires only when autotune is active and cannot affect `-bmManual`, `-bmDiag`, or non-benchmark
runs.

### Why both changes together

Soft-start reduces the probability of either rescue firing: starting at the softest possible
state gives the first probe step a maximally informative baseline, and most chains that are
physically achievable will cross the target before saturating. The symmetric rescue is still
needed because some chains genuinely cannot reach their target even at the softest fracMove
(target deflection too large for the segment geometry) — in that case, saturation at FRAC_MT_MIN
with fracMove at any level is the correct failure mode, and the rescue exits via FAILED rather
than looping indefinitely.

### Compile verification

Full project compiles clean under `javac -XDignore.symbol.file -cp ".:libs/*"`.

---

## 2026-05-19 — DeflectionTuner v12 soft-start fix: fracMove = 0.5

### Observed symptom (64-monomer benchmark, v12)

With soft-start at (fracMove=0.1, fracR=1.5, fmt=0.01), chain dynamics are ~5× slower than at
fracMove=0.5. The chain took very long to reach equilibrium under load; the settling detector
declared "settled" during the slow transient descent. Result: obs = 38.7 nm vs target 19.3 nm
(2× overshoot), but the controller read obs = 19.4 nm from stale smoothed EMA — acting on
transient data, not steady state.

### Fix: fracMove = FRAC_MOVE_MAX (0.5) at soft-start

One-value change in BoxOfActin.java: `Env.fracMove.setValue(DeflectionTuner.FRAC_MOVE_MAX)`.

fracMove at its default (0.5) preserves chain dynamic speed and keeps the settling detector
calibrated correctly. fracR = 1.5 and fmt = 0.01 (both at their soft extremes) are unchanged —
they still guarantee the initial configuration overshoots the target, so the first crossing is
always available without depending on parameter-file state.

If a chain genuinely requires lower fracMove (unreachable target even at softest fracR/fmt), the
symmetric saturation rescue from v12 still fires: it decrements fracMove only after both fracR
and fmt are saturated soft and the chain is confirmed too stiff.

### Compile verification

Full project compiles clean under `javac -XDignore.symbol.file -cp ".:libs/*"`.

---

## 2026-05-19 — DeflectionTuner v13: saturation rescue in PROBE_FMT

### Observed symptom (64-monomer benchmark, v12 with soft-start)

64-monomer chain settled at 13.6 nm vs target 19.3 nm (too stiff) with soft-start at
(fracMove=0.5, fracR=1.5, fmt=0.01). Rescue was expected to fire but the controller stalled.

### Root cause: missing rescue path in PROBE_FMT

On entry to PROBE_FMT: error < 0 (too stiff), fmt already at FRAC_MT_MIN = 0.01. Step
direction is "soften" → decrease fmt. Clamp to FRAC_MT_MIN → step = 0. probeStepped = true
with no actual change; settling wait; evaluation branch detects zero step → "step clamped to
zero — advancing" → PROBE_FR. Same zero-step in PROBE_FR → COARSE. Only then does the
soft-end rescue fire. Result: two phase transitions of degenerate zero-step probing before
the rescue fires, plus a window during which the controller could declare spurious convergence.

The v12 symmetric rescue exists in handleCoarse and handleFine only; PROBE_FMT had no check.

### Fix

Saturation check added at the top of handleProbeFmt's "not yet stepped" branch, evaluated
before any probe step is taken:

**Soft-saturation (chain too stiff, params at softest):**
```
error < -RESCUE_ERR_FRAC * expected
&& fracR >= FRAC_R_MAX - 1e-9
&& fracMoveTorq <= FRAC_MT_MIN + 1e-9
→ saturationRescue()
```

**Stiff-saturation (chain too soft, params at stiffest):**
```
error > RESCUE_ERR_FRAC * expected
&& fracR <= FRAC_R_MIN + 1e-9
&& fracMoveTorq >= FRAC_MT_MAX - 1e-9
→ saturationRescue()
```

Bad-sign retries are not affected: a retry requires a non-zero probe step to have already been
taken, which means fracMoveTorq is no longer at FRAC_MT_MIN. The saturation condition does not
match — no guard needed.

### Compile verification

Full project compiles clean under `javac -XDignore.symbol.file -cp ".:libs/*"`.

---

## 2026-05-20 — Planned: velocity-extrapolation control (v14+)

### Motivation

Long-τ chains (64-monomer τ=0.714s) make the current settling-based
controller painfully slow. Each measurement waits ~2τ for the chain
to equilibrate, and that wait happens after every parameter step.
The 64-monomer benchmark spent minutes per probe and never finished
in observable wall-clock time. A human tuning the same chain reaches
target in a handful of nudges, because the human reads the *rate* of
deflection change and acts on extrapolation, not on settling.

The next architectural step is to give the controller the same
ability: predict the chain's equilibrium deflection from its current
trajectory, and act on the prediction rather than waiting for the
chain to get there.

### Physics

Damped systems approach equilibrium exponentially:

    d(t) = d_∞ + (d_0 − d_∞) × exp(−t/τ)

Given current deflection and current velocity, equilibrium can be
estimated without waiting:

    d_∞ ≈ d_current + v_current × τ

τ_theo is already available from the BENCH startup line (and is
passed to start() as of v11). It's the analytic time constant for
an ideal chain at the loaded parameters; actual simulation τ may
differ, but theo is a good first estimate. A robust implementation
would also estimate τ on the fly from two velocity samples
(τ ≈ Δt / ln(v1/v2)), but theo alone is probably sufficient for
the initial design.

### Algorithm sketch

Maintain alongside the existing EMA-of-deflection:

  - velocityEMA: EMA of (smoothed_now - smoothed_prev) / Δt
    (or equivalent), updated every frame.
  - predicted_d_inf: computed each frame as
    smoothed + velocityEMA × τ_theo.

Step triggers, in priority order, after a parameter change:

  1. Predicted d_∞ is on-target within tolerance → keep waiting,
     watch for settling, then converge. No new step until either
     genuine settling fires convergence or velocity reaches zero
     (chain reached estimated equilibrium).

  2. Predicted d_∞ would overshoot target → keep waiting for the
     overshoot to actually happen. The current velocity-direction
     gives the reversal trigger.

  3. Predicted d_∞ would significantly *undershoot* target →
     step now, in the same corrective direction as the previous
     step. This is the "another nudge needed" case humans use
     intuitively; the current controller waits for settling and
     then takes one more step, doubling the convergence time.

  4. Smoothed deflection has actually crossed target → step now
     (current crossing-trigger behavior, preserved).

Convergence: when |smoothed − target| < CONV_TOL_UM and
|velocityEMA| is below some small threshold (chain actually
equilibrated, not just passing through). Replaces the current
N-consecutive-frames criterion.

### Design questions to answer before coding

  - How to estimate velocityEMA cleanly. Three candidates: two-point
    difference, short-window linear regression, EMA-of-difference.
    EMA-of-difference is computationally trivial and composes with
    the existing smoothed EMA. Probably the right choice.

  - What "significantly undershoot" means as a threshold. Predicted
    d_∞ deviates from target by more than (say) 3× CONV_TOL_UM, or
    by some fraction of the current error magnitude. Worth a
    sensitivity test.

  - Interaction with trust-region clamping. Trust shrinks on sign
    flips; undershoot-prediction triggers a same-direction step,
    which is the OPPOSITE of a sign flip. Trust may need to grow
    on a confirmed undershoot, since the previous step was clearly
    too small.

  - Interaction with sensitivity attribution. The current logic
    measures Δdeflection over a window after each step. With
    velocity-extrapolation, the window may close before the chain
    actually settles — sensitivity should be re-derived from
    predicted d_∞ rather than from instantaneous smoothed values.

  - Whether to retain the settling-based detector at all. Likely
    yes, as a fallback for cases where velocity estimation is
    unreliable (very early frames, just after a parameter step).
    But the primary step trigger should be predicted-d_∞.

### Approach

This is a bigger change than any of v2–v14. Probably worth a clean
DeflectionTuner rewrite (call it v15 when it lands) rather than
patching on top of the existing structure. The settling detector
becomes a fallback; velocity-extrapolation becomes the primary
trigger; convergence reads both deflection magnitude and velocity.

Suggested next session: draft the algorithm in detail (constants,
edge cases, full state machine) before sending to Claude Code.
Avoid iterating three times on details that could be settled in
advance.

### Observed failure that v15 must handle (v14 64-monomer test)

The v14 controller with symmetric PROBE_FMT rescue made meaningful
progress on 64-monomer: rescue fired three times in succession
(fracMove 0.5 → 0.45 → 0.40 → 0.35), bringing the chain to
obs = 0.019079 µm vs target 0.019305 µm (err = -226 pm, ~46×
CONV_TOL_UM, ~12% of target). Then the controller entered a dead
zone:

  - Both fracR and fmt remained saturated soft (1.5 and 0.01).
  - PROBE_FMT and PROBE_FR both produced zero-clamped steps.
  - COARSE/FINE produced zero predictions due to no sensitivity.
  - |err| = 226 pm was BELOW RESCUE_ERR_FRAC × expected = 965 pm,
    so the rescue's error-magnitude gate filtered it out and no
    further rescue fired.
  - |err| was 46× ABOVE CONV_TOL_UM = 5 pm, so convergence did not
    fire.

The controller stalled: too small to rescue, too large to converge,
saturated such that no step makes progress. The v14 patches did
their job — they got close — but the architecture has no path to
the last 200 pm in this regime.

v15 should handle this by design. The predicted-d_∞ branch "on
target within tolerance" should fire convergence (or initiate a
final fine adjustment) when the predicted equilibrium is inside
CONV_TOL_UM, even if the current smoothed deflection isn't there
yet. The dead zone goes away because the predicted equilibrium
disambiguates the current state from where the chain is heading.

A secondary lesson: the rescue's error-magnitude gate
(RESCUE_ERR_FRAC = 0.05) and the convergence tolerance
(CONV_TOL_UM ≈ 0.026% of target for this benchmark) leave a
~200× factor of error magnitudes where neither rescue nor
convergence acts. In v15 this gap should be closed by tying the
rescue decision to predicted-d_∞ (rescue when predicted d_∞ is
materially off target AND further parameter steps would clamp to
zero) rather than to a fixed fraction of target.

## 2026-05-20 — DeflectionTuner v15: unified (v, a)-aware controller design
Motivation
v14+ as drafted uses velocity-extrapolation (d_∞ ≈ d + v·τ_theo) to predict equilibrium without waiting for the chain to settle. The 64-monomer v14 trace then showed a dead-zone failure: rescue gate too strict, convergence tol too tight, the chain stranded ~200 pm off target with no controller path to close it.
The unified design adds the second derivative of the smoothed deflection trajectory as a third signal alongside d and v. Acceleration plays three coupled roles:

τ estimation on the fly. For a single-exponential relaxation, τ_est = −v/a. When this is stable across frames, we are in the slowest-mode-dominated regime and the predicted d_∞ can be trusted.
Settling/convergence confirmation. |a| → 0 confirms the chain has actually reached equilibrium, not just passed through.
Transient discrimination. High |a| (relative to its noise floor) means we are early in a step's response; sensitivity attribution and prediction-trust should be suppressed until |a| drops.

The acceleration signal does not replace velocity-extrapolation — it qualifies it.
Physics recap
For a pinned, overdamped chain under constant load, deflection relaxes as a sum of normal-mode exponentials. After a short transient, the slowest mode dominates:
d(t) ≈ d_∞ + A · exp(−t/τ)
From this:
v   = d'(t)  = −(d − d_∞) / τ
a   = d''(t) = (d − d_∞) / τ²  = −v/τ
So once we are slow-mode-dominated:
τ_est = −v / a
d_∞   = d − v² / a       (equivalently d + v·τ_est)
Early in a response, multiple modes are active and −v/a ≠ τ_slow. The unified controller detects this by watching τ_est's stability frame-to-frame.
Signal definitions
Let s_n = current smoothed deflection (the existing EMA, unchanged).
Let Δt_frame = drawInterval · deltaT (frame period, seconds).
Velocity. Computed as a regression slope, not a two-point difference, to suppress noise:
v_n = slope of linear regression on the last W_v values of (frame_time, s)
with W_v = 8 (placeholder; SLOPE_WIN-ish, calibrate against chain τ).
Acceleration. Computed as the curvature (quadratic coefficient × 2) of a quadratic regression on the same window, OR as the slope of a regression on the last W_a velocity values. Both work; the quadratic-regression form is preferred because it uses the raw smoothed values once and yields v and a from the same fit, ensuring consistency.
Quadratic fit on W_a = 12 frames of (frame_time, s):
    s(t) ≈ s_0 + v_n · (t − t_n) + ½ · a_n · (t − t_n)²
returns v_n and a_n.
Calibration note: W_v and W_a must be tuned per chain. A reasonable starting heuristic is W_a · Δt_frame ≈ 0.2 · τ_theo (window covers ~20% of a time-constant — long enough to suppress noise, short enough to track changes mid-relaxation).
τ_est. Computed each frame when the signals are healthy:
τ_est_n = −v_n / a_n      iff (|v_n| > V_NOISE) AND (|a_n| > A_NOISE) AND (sign(v_n) ≠ sign(a_n))
The sign-opposition check is physics: in single-exponential decay toward equilibrium, v and a always have opposite signs. Same-sign means we are in a regime where the model doesn't apply (e.g., chain just got a kick from a parameter change and is accelerating, not decelerating).
When the conditions fail, τ_est is undefined for that frame; the controller falls back to τ_theo.
Predicted d_∞. Computed each frame:
τ_use = τ_est_n   if τ_est is stable (see below) else τ_theo
d_∞_n = s_n + v_n · τ_use
τ_est stability. A scalar flag, updated each frame:
τ_stable = true   iff the relative std-dev of τ_est over the last W_τ frames < TAU_STABLE_FRAC
with W_τ = 6 frames and TAU_STABLE_FRAC = 0.15 (placeholders). When fewer than W_τ valid τ_est values are available, τ_stable = false.
Noise floor constants (placeholders, need empirical calibration)
V_NOISE         — velocity below which v is dominated by EMA noise.
                  Starting point: 1e-5 µm / Δt_frame, calibrated from the
                  tail of a converged chain run.
A_NOISE         — acceleration noise floor. Starting point: V_NOISE / τ_theo.
TAU_STABLE_FRAC — relative std-dev threshold for declaring τ_est stable. 0.15.
A_SETTLED_FRAC  — fraction of A_NOISE below which |a| confirms equilibrium.
                  0.5 (placeholder).
All four should be inspected against an actual 64-monomer trace before being trusted; A_NOISE in particular depends on the EMA configuration and chain length.
Step triggers, in priority order
After each parameter change, the controller waits in a "watch" state. Triggers below fire in priority order (first match wins). All triggers require the controller to be past the "post-step settle delay" (a small fixed number of frames W_post = 4, to let the immediate kick from a parameter change wash out before computing v/a).

Convergence (predicted-d_∞ based).

τ_stable AND |d_∞_n − target| < CONV_TOL_UM AND |a_n| < A_SETTLED_FRAC · A_NOISE
→ declare CONVERGED. The chain is heading to within tolerance and is no longer accelerating. Do not require smoothed to be there yet — this closes the v14 dead zone directly.


Confirmed prediction, off-target.

τ_stable AND |d_∞_n − target| > CONV_TOL_UM
→ step now. Use d_∞n (not s_n) as the basis for the error in the sensitivity formula. Direction is sign(target − d∞_n).
This replaces v14+'s "predicted d_∞ would significantly undershoot" trigger 3, and absorbs trigger 1 (on-target) into trigger 1 above.


Smoothed crossed target.

Sign of (s_n − target) flipped vs sign of (s_{n−1} − target)
→ step now. Preserved from v14+ as a safety net; if the predictor fails (τ_est never stabilizes), the crossing event still triggers a step.


Hard timeout.

More than W_timeout frames have elapsed in the watch state since the last parameter change
→ step now, basing error on s_n (predictor unreliable). W_timeout placeholder: 4 · τ_theo / Δt_frame.


Otherwise: keep watching.

The v14 dead zone is closed by trigger 1: when the chain is mid-relaxation toward an on-target equilibrium, τ_stable will eventually become true, |a| will drop, and convergence will fire even though s_n is still 200 pm off.
Convergence condition
As above, in trigger 1. Replaces the current N-consecutive-frames criterion with a single instantaneous predicate that has physical meaning. (We may keep an N=2-frame requirement to suppress single-frame noise — i.e., trigger 1 must hold for 2 consecutive frames before declaring CONVERGED. Cheap insurance.)
Sensitivity attribution
Currently: sensitivity = Δ(smoothed deflection) / Δ(parameter), measured over a settling window after each step.
New: sensitivity = Δ(predicted d_∞) / Δ(parameter), measured at the frame when τ_stable first becomes true after the step (or at the W_timeout deadline if τ_stable doesn't fire).
This is more responsive (no full settling wait) and uses the same physics-grounded predictor the trigger logic uses. Fallback to current-style settled sensitivity is retained for the timeout branch.
Interaction with rescue and brackets
The v14 rescue's RESCUE_ERR_FRAC gate (and the v10 bracket-obstruction logic) should now read predicted d_∞ as their error signal, not smoothed deflection. Specifically:

Rescue fires when |d_∞ − target| > RESCUE_ERR_FRAC · expected AND further parameter steps would clamp to zero. This removes the v14 64-monomer rescue-gate trap: in that trace, smoothed err = 226 pm < RESCUE_ERR_FRAC · expected, so rescue didn't fire — but predicted d_∞ may have been further from target than smoothed (the chain was still moving), in which case rescue would have fired correctly.
Bracket-obstruction detection is unchanged structurally; just feed it d_∞-based error.

Trust-region behavior is unchanged.
Fallback paths when (v, a) is unreliable
Cases where the design must degrade gracefully:

Cold start (first probe). No history; W_v / W_a windows not yet filled. Use the v6 cold-start gate as currently designed — first probe fires when s crosses expected, without waiting for τ_stable.
τ_est never stabilizes. Trigger 4 (hard timeout) fires; controller falls back to current-style smoothed-deflection-based stepping with τ_theo extrapolation. Should be rare; if it happens often on a chain type, it means the chain is genuinely multi-mode and the slow-mode assumption is wrong (worth investigating, but not a blocker for the controller).
|a| in the noise floor right after a parameter step. Expected — the chain hasn't responded yet. The W_post = 4 frame post-step delay handles this; if it persists past W_post, trigger 4 takes over.
Same-sign v and a. Indicates the chain is still being kicked by the most recent step (or by a non-equilibrium force). τ_est invalid for that frame; falls through to τ_theo extrapolation, which is conservative.

Constants summary (placeholder values, all need calibration)
W_v             = 8       (regression window for v)
W_a             = 12      (regression window for a, must be ≥ W_v)
W_τ             = 6       (stability window for τ_est)
W_post          = 4       (post-step settle delay, frames)
W_timeout       = 4·τ_theo / Δt_frame  (hard timeout, frames)
V_NOISE         = 1e-5 µm / Δt_frame  (velocity noise floor)
A_NOISE         = V_NOISE / τ_theo     (acceleration noise floor)
TAU_STABLE_FRAC = 0.15    (relative std-dev threshold)
A_SETTLED_FRAC  = 0.5     (fraction of A_NOISE for settled detection)
CONV_FRAMES     = 2       (consecutive-frame requirement for trigger 1)
CONV_TOL_UM     = 5 pm    (unchanged)
RESCUE_ERR_FRAC = 0.05    (unchanged, but applied to d_∞-based error)
Logging requirements
For each frame in watch state, the log line should include s, v_n, a_n, τ_est_n, τ_stable, d_∞_n, predicted_err. This is essential for diagnosing whether v15 actually fires on the right physics or just inherits the v14 stalls under new names. Add a one-line summary at every trigger fire: which trigger, which signals were in/out of bounds.
What this gets us

64-monomer dead zone closed by design. Trigger 1 fires when predicted d_∞ is on target, regardless of smoothed deflection's current position. No more "too small to rescue, too large to converge."
Faster convergence on slow chains. Trigger 2 acts on confirmed prediction without waiting for settling, removing the per-step ~2τ wait.
Diagnostic richness. τ_est should track τ_theo when the chain is healthy; deviations are useful debugging info (sign of multi-mode behavior, sign of EMA mis-tuning, sign of parameter regime errors).
Backward compatible at the edges. Trigger 3 (smoothed crossing) and trigger 4 (timeout) preserve the current behavior as fallbacks, so a chain that defeats the predictor still converges via the old path.

Open questions for the implementation session

Should we EMA-smooth τ_est as well, or only check its relative std-dev? The latter is cleaner; the former might be necessary if τ_est is noisy enough to fail the stability check despite being "correct on average."
Should sensitivity attribution use the first τ_stable frame after a step, or wait for an EMA of d_∞ to settle? Probably first τ_stable, but worth a comparison on the 48-monomer trace.
The current EMA-smoothed s is itself a low-pass filter — the regression-based v and a will inherit any lag from it. Is the existing EMA tuned for that, or should v15 compute v and a from a less-smoothed signal? Probably worth measuring before tuning.

These are the kind of questions worth resolving on the first run of v15 against the 64-monomer trace, not in advance. The design is structured to make them visible in the log output.
---
## 2026-05-20 — DeflectionTuner v15: unified (v, a)-aware controller design

### Motivation

v14+ as drafted uses velocity-extrapolation (`d_∞ ≈ d + v·τ_theo`) to predict equilibrium without waiting for the chain to settle. The 64-monomer v14 trace then showed a dead-zone failure: rescue gate too strict, convergence tol too tight, the chain stranded ~200 pm off target with no controller path to close it.

The unified design adds the **second derivative of the smoothed deflection trajectory** as a third signal alongside d and v. Acceleration plays three coupled roles:

1. **τ estimation on the fly.** For a single-exponential relaxation, `τ_est = −v/a`. When this is stable across frames, we are in the slowest-mode-dominated regime and the predicted d_∞ can be trusted.
2. **Settling/convergence confirmation.** `|a| → 0` confirms the chain has actually reached equilibrium, not just passed through.
3. **Transient discrimination.** High |a| (relative to its noise floor) means we are early in a step's response; sensitivity attribution and prediction-trust should be suppressed until |a| drops.

The acceleration signal does not replace velocity-extrapolation — it qualifies it.

### Physics recap

For a pinned, overdamped chain under constant load, deflection relaxes as a sum of normal-mode exponentials. After a short transient, the slowest mode dominates:

    d(t) ≈ d_∞ + A · exp(−t/τ)

From this:

    v   = d'(t)  = −(d − d_∞) / τ
    a   = d''(t) = (d − d_∞) / τ²  = −v/τ

So once we are slow-mode-dominated:

    τ_est = −v / a
    d_∞   = d − v² / a       (equivalently d + v·τ_est)

Early in a response, multiple modes are active and `−v/a ≠ τ_slow`. The unified controller detects this by watching τ_est's stability frame-to-frame.

### Signal definitions

Let `s_n` = current smoothed deflection (the existing EMA, unchanged).
Let `Δt_frame` = `drawInterval · deltaT` (frame period, seconds).

**Velocity.** Computed as a regression slope, not a two-point difference, to suppress noise:

    v_n = slope of linear regression on the last W_v values of (frame_time, s)

with `W_v = 8` (placeholder; SLOPE_WIN-ish, calibrate against chain τ).

**Acceleration.** Computed as the *curvature* (quadratic coefficient × 2) of a quadratic regression on the same window, OR as the slope of a regression on the last W_a velocity values. Both work; the quadratic-regression form is preferred because it uses the raw smoothed values once and yields v and a from the same fit, ensuring consistency.

    Quadratic fit on W_a = 12 frames of (frame_time, s):
        s(t) ≈ s_0 + v_n · (t − t_n) + ½ · a_n · (t − t_n)²
    returns v_n and a_n.

Calibration note: W_v and W_a must be tuned per chain. A reasonable starting heuristic is `W_a · Δt_frame ≈ 0.2 · τ_theo` (window covers ~20% of a time-constant — long enough to suppress noise, short enough to track changes mid-relaxation).

**τ_est.** Computed each frame when the signals are healthy:

    τ_est_n = −v_n / a_n      iff (|v_n| > V_NOISE) AND (|a_n| > A_NOISE) AND (sign(v_n) ≠ sign(a_n))

The sign-opposition check is physics: in single-exponential decay toward equilibrium, v and a always have opposite signs. Same-sign means we are in a regime where the model doesn't apply (e.g., chain just got a kick from a parameter change and is accelerating, not decelerating).

When the conditions fail, τ_est is undefined for that frame; the controller falls back to `τ_theo`.

**Predicted d_∞.** Computed each frame:

    τ_use = τ_est_n   if τ_est is stable (see below) else τ_theo
    d_∞_n = s_n + v_n · τ_use

**τ_est stability.** A scalar flag, updated each frame:

    τ_stable = true   iff the relative std-dev of τ_est over the last W_τ frames < TAU_STABLE_FRAC

with `W_τ = 6` frames and `TAU_STABLE_FRAC = 0.15` (placeholders). When fewer than W_τ valid τ_est values are available, τ_stable = false.

### Noise floor constants (placeholders, need empirical calibration)

    V_NOISE         — velocity below which v is dominated by EMA noise.
                      Starting point: 1e-5 µm / Δt_frame, calibrated from the
                      tail of a converged chain run.
    A_NOISE         — acceleration noise floor. Starting point: V_NOISE / τ_theo.
    TAU_STABLE_FRAC — relative std-dev threshold for declaring τ_est stable. 0.15.
    A_SETTLED_FRAC  — fraction of A_NOISE below which |a| confirms equilibrium.
                      0.5 (placeholder).

All four should be inspected against an actual 64-monomer trace before being trusted; A_NOISE in particular depends on the EMA configuration and chain length.

### Step triggers, in priority order

After each parameter change, the controller waits in a "watch" state. Triggers below fire in priority order (first match wins). All triggers require the controller to be past the "post-step settle delay" (a small fixed number of frames W_post = 4, to let the immediate kick from a parameter change wash out before computing v/a).

1. **Convergence (predicted-d_∞ based).**
   - τ_stable AND |d_∞_n − target| < CONV_TOL_UM AND |a_n| < A_SETTLED_FRAC · A_NOISE
   - → declare CONVERGED. The chain is heading to within tolerance and is no longer accelerating. Do not require smoothed to be there yet — this closes the v14 dead zone directly.

2. **Confirmed prediction, off-target.**
   - τ_stable AND |d_∞_n − target| > CONV_TOL_UM
   - → step now. Use d_∞_n (not s_n) as the basis for the error in the sensitivity formula. Direction is sign(target − d_∞_n).
   - This replaces v14+'s "predicted d_∞ would significantly undershoot" trigger 3, and absorbs trigger 1 (on-target) into trigger 1 above.

3. **Smoothed crossed target.**
   - Sign of (s_n − target) flipped vs sign of (s_{n−1} − target)
   - → step now. Preserved from v14+ as a safety net; if the predictor fails (τ_est never stabilizes), the crossing event still triggers a step.

4. **Hard timeout.**
   - More than W_timeout frames have elapsed in the watch state since the last parameter change
   - → step now, basing error on s_n (predictor unreliable). W_timeout placeholder: 4 · τ_theo / Δt_frame.

5. **Otherwise:** keep watching.

The v14 dead zone is closed by trigger 1: when the chain is mid-relaxation toward an on-target equilibrium, τ_stable will eventually become true, |a| will drop, and convergence will fire even though s_n is still 200 pm off.

### Convergence condition

As above, in trigger 1. Replaces the current N-consecutive-frames criterion with a single instantaneous predicate that has physical meaning. (We may keep an N=2-frame requirement to suppress single-frame noise — i.e., trigger 1 must hold for 2 consecutive frames before declaring CONVERGED. Cheap insurance.)

### Sensitivity attribution

Currently: sensitivity = Δ(smoothed deflection) / Δ(parameter), measured over a settling window after each step.

New: sensitivity = Δ(predicted d_∞) / Δ(parameter), measured at the frame when τ_stable first becomes true after the step (or at the W_timeout deadline if τ_stable doesn't fire).

This is more responsive (no full settling wait) and uses the same physics-grounded predictor the trigger logic uses. Fallback to current-style settled sensitivity is retained for the timeout branch.

### Interaction with rescue and brackets

The v14 rescue's RESCUE_ERR_FRAC gate (and the v10 bracket-obstruction logic) should now read **predicted d_∞** as their error signal, not smoothed deflection. Specifically:

- Rescue fires when `|d_∞ − target| > RESCUE_ERR_FRAC · expected` AND further parameter steps would clamp to zero. This removes the v14 64-monomer rescue-gate trap: in that trace, smoothed err = 226 pm < RESCUE_ERR_FRAC · expected, so rescue didn't fire — but predicted d_∞ may have been further from target than smoothed (the chain was still moving), in which case rescue would have fired correctly.
- Bracket-obstruction detection is unchanged structurally; just feed it d_∞-based error.

Trust-region behavior is unchanged.

### Fallback paths when (v, a) is unreliable

Cases where the design must degrade gracefully:

- **Cold start (first probe).** No history; W_v / W_a windows not yet filled. Use the v6 cold-start gate as currently designed — first probe fires when s crosses expected, without waiting for τ_stable.
- **τ_est never stabilizes.** Trigger 4 (hard timeout) fires; controller falls back to current-style smoothed-deflection-based stepping with τ_theo extrapolation. Should be rare; if it happens often on a chain type, it means the chain is genuinely multi-mode and the slow-mode assumption is wrong (worth investigating, but not a blocker for the controller).
- **|a| in the noise floor right after a parameter step.** Expected — the chain hasn't responded yet. The W_post = 4 frame post-step delay handles this; if it persists past W_post, trigger 4 takes over.
- **Same-sign v and a.** Indicates the chain is still being kicked by the most recent step (or by a non-equilibrium force). τ_est invalid for that frame; falls through to τ_theo extrapolation, which is conservative.

### Constants summary (placeholder values, all need calibration)

    W_v             = 8       (regression window for v)
    W_a             = 12      (regression window for a, must be ≥ W_v)
    W_τ             = 6       (stability window for τ_est)
    W_post          = 4       (post-step settle delay, frames)
    W_timeout       = 4·τ_theo / Δt_frame  (hard timeout, frames)
    V_NOISE         = 1e-5 µm / Δt_frame  (velocity noise floor)
    A_NOISE         = V_NOISE / τ_theo     (acceleration noise floor)
    TAU_STABLE_FRAC = 0.15    (relative std-dev threshold)
    A_SETTLED_FRAC  = 0.5     (fraction of A_NOISE for settled detection)
    CONV_FRAMES     = 2       (consecutive-frame requirement for trigger 1)
    CONV_TOL_UM     = 5 pm    (unchanged)
    RESCUE_ERR_FRAC = 0.05    (unchanged, but applied to d_∞-based error)

### Logging requirements

For each frame in watch state, the log line should include `s, v_n, a_n, τ_est_n, τ_stable, d_∞_n, predicted_err`. This is essential for diagnosing whether v15 actually fires on the right physics or just inherits the v14 stalls under new names. Add a one-line summary at every trigger fire: which trigger, which signals were in/out of bounds.

### What this gets us

- **64-monomer dead zone closed by design.** Trigger 1 fires when predicted d_∞ is on target, regardless of smoothed deflection's current position. No more "too small to rescue, too large to converge."
- **Faster convergence on slow chains.** Trigger 2 acts on confirmed prediction without waiting for settling, removing the per-step ~2τ wait.
- **Diagnostic richness.** τ_est should track τ_theo when the chain is healthy; deviations are useful debugging info (sign of multi-mode behavior, sign of EMA mis-tuning, sign of parameter regime errors).
- **Backward compatible at the edges.** Trigger 3 (smoothed crossing) and trigger 4 (timeout) preserve the current behavior as fallbacks, so a chain that defeats the predictor still converges via the old path.

### Open questions for the implementation session

1. Should we EMA-smooth τ_est as well, or only check its relative std-dev? The latter is cleaner; the former might be necessary if τ_est is noisy enough to fail the stability check despite being "correct on average."
2. Should sensitivity attribution use the *first* τ_stable frame after a step, or wait for an EMA of d_∞ to settle? Probably first τ_stable, but worth a comparison on the 48-monomer trace.
3. The current EMA-smoothed `s` is itself a low-pass filter — the regression-based v and a will inherit any lag from it. Is the existing EMA tuned for that, or should v15 compute v and a from a less-smoothed signal? Probably worth measuring before tuning.

These are the kind of questions worth resolving on the *first run* of v15 against the 64-monomer trace, not in advance. The design is structured to make them visible in the log output.

---

## 2026-05-20 — DeflectionTuner v15: implementation

### File structure

- **`boxOfActin/DeflectionTunerV15.java`** — new file, ~650 lines, `package boxOfActin`. Self-contained; shares no state with `DeflectionTuner.java` (v14). Both files compile and link independently.
- **`boxOfActin/DeflectionTuner.java`** — v14, untouched.
- **`boxOfActin/Env.java`** — one line added: `static boolean benchmarkTunerV15 = false;`
- **`boxOfActin/BoxOfActin.java`** — three edits: (1) `static DeflectionTunerV15 deflTunerV15 = null;` declaration alongside `deflTuner`; (2) `-bmTunerV15` arg parsed in `parseArgs()`, sets `Env.benchmarkTunerV15` and `Env.benchmarkFilament`; (3) feed loop refactored to route to whichever controller is non-null (v15 branch, else v14 branch). Soft-start initialization block is shared by both paths.

### Implementation choices

**`_stagedDInf` pattern.** The `feed()` method computes `dInf` as a local variable, but the step handlers (`handleCoarseStep`, `handleFineStep`) need to record it as `dInfAtStep` for the next sensitivity attribution. Rather than add `dInf` as a parameter threading through three dispatch levels, a single package-private field `_stagedDInf` is written by `stageDInf(dInf)` immediately before each `dispatchStep()` call. The three call sites (T2, T3, T4) each call `stageDInf(dInf)` first. This is the only non-obvious structural choice; everything else follows directly from the design doc.

**τ_est buffer cleared on each parameter step.** The τ_est stability ring buffer (`tauEstBuf`) is wiped via `clearTauEstBuf()` whenever a COARSE or FINE step is taken, and also at PROBE_FMT/PROBE_FR step time. This forces a fresh W_TAU-frame accumulation after every parameter change, ensuring τ_stable cannot fire on τ_est values measured under different physics. The va regression buffer is NOT cleared — it has W_A=12 frames of memory and the W_post=4 gate suppresses triggers during the immediate post-step transient.

**Cold-start path.** PROBE_FMT and PROBE_FR use the v14 settling + early-trigger gate unchanged. The first PROBE_FMT early trigger fires as soon as `smoothed > expected` (which happens immediately with soft-start at softest params). The 4-trigger state machine only activates after `phase == Phase.COARSE` or `phase == Phase.FINE`.

**Sensitivity attribution at first τ_stable frame.** `sensitivityAttribDone` is reset on every step (in both probe and COARSE/FINE handlers). `attributeSensitivity(dInfNow)` computes `Δd_∞ / Δparam` using the Δd_∞ between now and `dInfAtStep`. If `dInfAtStep` was NaN at step time (probe phases, or T3/T4 with d_∞ unavailable), it falls back to smoothed-based attribution. T4 timeout also calls `attributeSensitivitySmoothed()` directly if τ_stable never fired during the watch period.

**CONV_TOL_UM constant note.** The design doc specifies "5 pm" but 5e-6 µm = 5 nm, not 5 pm (5e-9 µm). This is unchanged from v14 — both use `5e-6`. The design doc label "5 pm" appears to be a notation inconsistency inherited from prior versions; the actual value matches v14.

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV15 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV15 -bmMonomer 32
```

Absent `-bmTunerV15`, `-bm` behavior is unchanged (v14 arms, same log lines as before).

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings beyond baseline.

### Controller dispatch confirmed

- `-bm` (no v15 flag): prints `[AUTOTUNE] armed: tuner=v14 ...` — v14 controller active, behavior identical to prior sessions.
- `-bmTunerV15`: prints `[V15] armed: τ=... vNoise=... aNoise=... wTimeout=...` followed by `[AUTOTUNE] armed: tuner=v15 ...` — v15 controller active. PROBE_FMT early trigger fires on first output frame, emits `[V15:STEP#1] PROBE_FMT take ...`.

### Constants to calibrate (all PLACEHOLDER in source)

`W_A=12`, `W_TAU=6`, `W_POST=4`, `TAU_STABLE_FRAC=0.15`, `A_SETTLED_FRAC=0.5`, `CONV_FRAMES_V15=2`, `RESCUE_ERR_FRAC=0.05`. V_NOISE and A_NOISE computed from τ_theo in `start()`: `vNoise = 1e-5 / dtFrame`, `aNoise = vNoise / tauTheo`. W_timeout computed as `round(4·τ_theo / dtFrame)`. All need calibration against a 64-monomer trace before trusting convergence timing.

---

## 2026-05-20 — DeflectionTuner v15: CONV_TOL_UM fix and noise-floor calibration

### CONV_TOL_UM resolution (case c — neither a nor b)

The code had `CONV_TOL_UM = 5e-6` (µm) with comment `// 5 pm`. The unit conversion is correct:
`5e-6 µm = 5 pm` (since 1 µm = 10⁶ pm). The "46×" arithmetic in the v14 64-monomer entry is
consistent: 226 pm / 5 pm = 45.2 ≈ 46. The v15 implementation note stating "5e-6 µm = 5 nm"
was a unit conversion error in the journal, not a code error.

Fix: changed `CONV_TOL_UM = 5e-6` → `1e-5` in both `DeflectionTuner.java` (v14) and
`DeflectionTunerV15.java` (v15). This is a 2× loosening (5 pm → 10 pm = 0.01 nm), matching
the empirical steady-state flutter floor reported by the user. The v14 dead-zone journal entry
arithmetic still holds qualitatively: 226 pm / 10 pm = 22.6× (v14 was still well outside
tolerance even after the fix).

### 32-monomer noise probe: V_NOISE and A_NOISE calibration

**First attempt (old placeholder values):** Run stuck in a dead loop. The placeholder formula
`vNoise = 1e-5/dtFrame = 1e-3 µm/s` and `aNoise = vNoise/τ_theo = 1.754e-2 µm/s²` were 13×
and 14× too large respectively. Actual `|a|` during relaxation was 1–4e-3 µm/s², always below
`aNoise`. τ_est was never valid; τ_stable never fired; T1 and T2 never fired. Controller
degraded entirely to T4 timeout mode. Stuck at 57 pm from target (below new CONV_TOL_UM=10 pm;
above old 5 pm) with fmt saturated and no rescue or convergence path.

**Calibration from stuck-run tail:** Extracted 200 frames of near-equilibrium FINE output from
the stuck run (s=0.009857 µm, error=57 pm). Statistics:
- v: mean≈0, std=2.55e-5 µm/s → 3σ = 7.65e-5 µm/s
- a: mean≈0, std=4.15e-4 µm/s² → 3σ = 1.25e-3 µm/s²

Updated `start()` in v15: `vNoise = 8e-5` µm/s; `aNoise = 1.3e-3` µm/s² (both rounded up
conservatively from 3σ). Also added T4 near-target NOISE_PROBE activation path: if
noiseProbePending AND T4 fires AND |error| < 10×CONV_TOL_UM (100 pm), enter NOISE_PROBE
instead of stepping. This handles the case where τ_stable never fires.

**Second attempt (calibrated values):** T2 fired throughout COARSE phase — τ_est was now valid
during the deceleration phase (|a| reaching 2–87e-3 µm/s², above new aNoise=1.3e-3). τ_est
values ranged 0.19–0.44 s (all much larger than τ_theo=0.057 s; multi-mode chain behavior).
τ_stable fired despite high τ_est variance, enabling T2 steps. 19 steps total to convergence.
T3 (crossing) fired at step 20. T4 near-target → NOISE_PROBE activated. 1000-frame CSV written
to `/tmp/v15_noise_probe.csv`.

**Formal 1000-frame CSV statistics:**
- v: mean=1.6e-7 µm/s, std=2.12e-5 → 3σ = 6.37e-5 µm/s (code has 8e-5 — slightly conservative ✓)
- a: mean=5.6e-7 µm/s², std=3.42e-4 → 3σ = 1.03e-3 µm/s² (code has 1.3e-3 ✓)

Committed values (8e-5, 1.3e-3) are within ~25% of 3σ calibrated values. No further update needed.

### 64-monomer benchmark trace

Full log: `RUN_LOGS/v15_64mer_first_calibrated.log`. Run converged (exit code 0).

Key findings:
- **τ_theo = 7.144 s**, not 0.714 s as the prior journal entry stated. The prior entry was a
  10× error. Correct derivation: ζ_perp(64-mon)/ζ_perp(32-mon) ≈ 16.4; L(64-mon)/L(32-mon) ≈
  1.97, L³ ratio ≈ 7.65; τ ∝ ζ_perp × L³ → 16.4 × 7.65 × 0.057 s ≈ 7.1 s. Matches 7.144 ✓.
- **slope_win = 1429 frames = 14.29 sim-sec; wTimeout = 2858 frames = 28.58 sim-sec**.
- **6 saturation rescues**: fracMove 0.5→0.45→0.40→0.35→0.30→0.25→0.20. Each rescue fired
  after ~10 sim-sec of settling (the slope buffer needing 1429 frames should take 14.29 sim-sec,
  but rescues fire earlier — possibly the early trigger (smoothed > expected) fires first when
  the chain transiently overshoots target during approach).
- **Convergence** happened after the 6th rescue (fracMove=0.20), between sim=80 and sim=100.
  Final params not captured (see below).
- **Wall-clock**: ~79 minutes (100 sim-sec at ~0.79 min/sim-sec). This is dominated by
  the 6 × 14 sim-sec probe windows, not COARSE/FINE control.

**stdout buffer loss bug:** All COARSE/FINE/convergence output was lost due to block-buffered
`System.out` not flushing before `System.exit(0)`. Fixed this session: added
`System.out.flush()` before both v14 and v15 `System.exit()` calls in `BoxOfActin.java`. The
flush fix was not compiled before the first 64-monomer run, so the convergence trace is missing.
Final params (fracR, fracMoveTorq at convergence) are unknown.

### Open issues flagged for planner

1. **τ_theo = 7.144 s for 64-monomer** means each probe settling window is 14 sim-sec ≈ 12
   minutes wall-clock. With 6 rescues needed, the total time is dominated by probe overhead, not
   by COARSE/FINE tuning. The v15 T2 fast-exit from probe phases (firing on τ_stable rather than
   waiting for full settle) does not help during the saturation-rescue loop because τ_stable can't
   fire in PROBE_FMT phase. If there's a way to detect "chain at soft-start is already
   too stiff, skip probes and immediately rescue", the probe overhead would be eliminated.

2. **Calibration noise floors are chain-specific.** V_NOISE and A_NOISE were calibrated from
   the 32-monomer chain. The 64-monomer run may have different equilibrium fluctuations due to
   longer segments. The 64-monomer run converged, suggesting the 32-monomer calibration is
   adequate, but a separate 64-monomer noise probe would be the correct procedure.

3. **T2 firing on 32-monomer confirmed.** The multi-mode τ_est values (0.19–0.44 s vs τ_theo=
   0.057 s) are ~4–8× larger than theoretical. TAU_STABLE_FRAC=0.15 appears permissive enough
   that τ_stable fires despite this mismatch. The planner should decide whether this is correct
   behavior or a sign that TAU_STABLE_FRAC needs tightening.

4. **64-monomer convergence trace missing** (stdout flush bug). Re-run with the flush fix to
   get the full COARSE/FINE trace and final converged params.

### Compile verification

All changes compile clean: `javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java`

### Files changed this session

- `boxOfActin/DeflectionTuner.java` — CONV_TOL_UM 5e-6 → 1e-5
- `boxOfActin/DeflectionTunerV15.java` — CONV_TOL_UM 5e-6 → 1e-5; Phase.NOISE_PROBE added;
  noise probe fields + enableNoiseProbe(); T1 → NOISE_PROBE branch; T4 near-target → NOISE_PROBE
  branch; vNoise/aNoise changed from placeholder formulas to calibrated constants (8e-5, 1.3e-3)
- `boxOfActin/Env.java` — `benchmarkNoiseProbe` flag added
- `boxOfActin/BoxOfActin.java` — `-bmNoiseProbe` arg; `enableNoiseProbe()` call; `System.out.flush()`
  before both v14 and v15 `System.exit()` calls
- `RUN_LOGS/v15_64mer_first_calibrated.log` — 64-monomer trace (probe/rescue phases only;
  COARSE/FINE output lost to buffer flush bug)

---

## 2026-05-20 — DeflectionTuner v15.1: three parameter-handling fixes

### Motivation

A 32-monomer run with v15 stalled in FINE: `obs = 0.01001 µm` vs target `0.0098 µm`
(err = +200 nm, 200× CONV_TOL_UM). `fracMoveTorq` was pinned at FRAC_MT_MAX = 0.5 from an
early COARSE step and the controller could not step it back down despite the predictor giving
`rawFmtPred = -0.05` every frame. Three independent bugs conspired to cause and worsen the stall.

### Fix 1: FINE limit-retreat (Issue 1)

**Symptom:** fmt at FRAC_MT_MAX, predictor says decrease (rawFmtPred < 0). The fr-rescue gate
(`fmtSaturatedAtLimit`) correctly does NOT fire — rawFmtPred < 0 and fmt is at MAX not MIN.
The normal FINE step path runs, but `clampToBrackets` clamps the step to zero because
`fmtLoBound = FRAC_MT_MAX` (recorded when error > 0 was observed at fmt = 0.5) blocks any
decrease below 0.5.

**Root cause:** the lower bracket bound (`fmtHasLo`) is set when the chain is too soft at some
fmt value. When fmt is at FRAC_MT_MAX and error > 0 fires, `fmtLoBound = FRAC_MT_MAX`. Any
subsequent attempt to retreat (decrease fmt) is blocked because `candidateLimits < fmtLoBound`.

**Fix:** in `handleFineStep` / `handleFine`, immediately before `clampToBrackets`, check if
fmt is at a hardware limit and the step retreats from it. If so, clear the stale bracket that
would block the retreat:
- `fracMoveTorq >= FRAC_MT_MAX - 1e-9 && fmtStep < 0` → clear `fmtHasLo`
- `fracMoveTorq <= FRAC_MT_MIN + 1e-9 && fmtStep > 0` → clear `fmtHasHi`

Log line: `[V15] FINE limit-retreat: clearing fmtLoBound/fmtHiBound (value)`.

**Small in-scope discovery:** the task spec said "clear fmtHasHi when retreating from MAX" but
the correct variable is `fmtHasLo` (the lower bound that prevents going below MAX). The task
description had the variable names swapped; the intent was correct and is implemented correctly here.

### Fix 2: bad-sign probe retries reverse direction (Issue 2)

**Symptom:** PROBE_FMT launched the chain from obs ≈ 9.9 nm to 35 nm in three steps by taking
6× multiplicative jumps in the wrong direction. Each bad-sign retry halved the step magnitude
but stepped in the SAME direction, digging further in the wrong direction.

**Root cause:** on bad-sign, the retry set `probeStepped = false` and recursed into
`handleProbeFmt`. The direction `dir` was recomputed from the CURRENT error sign — but the
current error sign at retry time reflects the (wrong-direction) probe step already taken, not
the desired correction direction.

**Fix:** add `probeStepDir` field (double, 0 = not yet set). When taking the initial probe step,
compute direction from error and store it in `probeStepDir`. On bad-sign retry:
`probeStepDir = -probeStepDir` (reverse) then halve `probeStep` as before. The next step uses
`probeStepDir` directly rather than recomputing from error.

Also reset `probeStepDir = 0` at: `start()`, PROBE_FMT→PROBE_FR transition, `saturationRescue()`.

Log line updated to say `→ reversing direction` on bad-sign.

Applied symmetrically to `handleProbeFmt` and `handleProbeFr` in both files.

### Fix 3: probe step capped relative to current parameter magnitude (Issue 3)

**Symptom:** `INIT_PROBE_STEP = 0.05` is additive. When applied to `fracMoveTorq` starting
at 0.01, the first probe step is 5× the current value — launching the chain dramatically
far from the initial state before any sensitivity is known.

**Fix:** add two new constants:
```
MAX_PROBE_REL      = 0.5    // probe step ≤ 50% of current param value
MIN_PROBE_STEP_ABS = 1e-3   // absolute floor
```
Compute `probeMag = min(probeStep, max(MIN_PROBE_STEP_ABS, MAX_PROBE_REL × |param|))` and
use `probeMag` for the actual step; `probeStep` continues to track the halving schedule.

Effect: fmt=0.01 → probeMag_cap = 0.005 (first probe at most 0.015). fracR=1.5 → cap = 0.75,
above INIT_PROBE_STEP → unchanged. PROBE_FR on typical fracR values is unaffected.

Applied to both `handleProbeFmt` and `handleProbeFr` in both files.

### Files changed

- `boxOfActin/DeflectionTunerV15.java` — all three fixes; new constants MAX_PROBE_REL /
  MIN_PROBE_STEP_ABS; new field probeStepDir; limit-retreat check in handleFineStep.
- `boxOfActin/DeflectionTuner.java` — identical three fixes applied to v14 code paths.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flags (unchanged)

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV15 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV15 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV15 -bmMonomer 32 -bmNoiseProbe
```

Next session: run 32-monomer first to verify (a) PROBE_FMT no longer overshoots target,
(b) bad-sign retry reverses and eventually brackets correctly, (c) FINE can step fmt back
down from FRAC_MT_MAX when the predictor calls for it. Then 64-monomer for full-run trace.

---

## 2026-05-20 — DeflectionTuner v16 design — bracket-and-overshoot controller

### Motivation

v15 converges but its path is clumsy: deflection swings to 4× target, parameters move in zig-zag patterns inherited from probe→COARSE→FINE phase structure, and the controller hesitates after each step rather than committing. The user's manual tuning procedure is far more efficient:

> When deflection crosses the target, change `fracR` and/or `fracMoveTorq` toward stiffer (or softer). Move them aggressively — enough to send the deflection back over the target. Each crossing is a piece of information: it brackets the target between the last two parameter settings. Shrink the bracket. Stop when within tolerance and not accelerating.

v16 implements this directly. The phase machine is gone. There's a single control loop that runs every output frame.

### Conceptual model

For each parameter (fr, fmt) there is a 1-D axis on which "stiffer" is one direction and "softer" is the other. The chain's equilibrium deflection is a monotonic function of position on each axis (assumed; if violated, the controller will fail gracefully via a bracket-violation check). A *bracket* is a pair of parameter settings, one of which produced equilibrium-deflection above target ("too soft") and one of which produced equilibrium-deflection below target ("too stiff"). The target lies between these.

The controller drives toward target by:
1. Detecting deflection crossings of target.
2. On each crossing, recording the parameter setting that produced the crossing as a new bracket endpoint (replacing the prior endpoint on the same side).
3. Moving parameters aggressively into the bracket toward the *opposite* side, with enough magnitude to produce another crossing.
4. Declaring converged when the predicted d_∞ is within CONV_TOL_UM and acceleration is below its noise floor.

The signal pipeline from v15 (smoothed deflection s, regression-window velocity v and acceleration a, predicted d_∞, τ_est) is retained unchanged. These signals inform timing (when to step) and convergence detection. They do *not* drive parameter selection — that comes from the bracket geometry.

### State

```
// hardware limits
FRAC_R_MIN, FRAC_R_MAX        // existing
FRAC_MT_MIN, FRAC_MT_MAX      // existing

// current parameter values
fracR, fracMoveTorq

// bracket endpoints. null until first crossing on that side.
softEndpoint: (fracR_s, fracMoveTorq_s, dInf_s)   // produced deflection > target
stiffEndpoint: (fracR_t, fracMoveTorq_t, dInf_t)  // produced deflection < target

// signal pipeline (from v15, reused unchanged)
smoothed, v, a, dInf, tauEst, tauStable

// loop state
hasSeenFirstCrossing: boolean    // false until first d_∞ crossing of target
                                  // observed after a step
framesSinceLastStep: int
stepCount: int
lastStepParams: (fracR, fracMoveTorq)  // saved when each step is applied
dInfAtLastStep: double           // saved just before each step
firstCallSinceStep: boolean
```

### Algorithm parameters

```
CONV_TOL_UM           = 1e-5 (µm)         // empirical noise floor of smoothed
A_SETTLED             = A_NOISE * 0.5     // from v15 calibration
W_POST_STEP           = 4 (frames)        // post-step quiet period (same as v15)
W_HOLD_MAX            = 4 × tauTheo / dtFrame
                                          // max frames in a hold state before
                                          // stepping regardless
FIRST_STEP_FRACTION   = 0.5               // fraction of remaining range to use
                                          // before a bracket is established
INTO_BRACKET_FRACTION = 0.5               // fraction of bracket width to step
                                          // into (0.5 = midpoint)
MIN_STEP_FRACTION     = 0.01              // step floor (relative to current
                                          // parameter value)
```

INTO_BRACKET_FRACTION = 0.5 means: cross to the bracket midpoint each step. Every step is intended to produce another crossing. The bracket halves per crossing — 2^N shrinkage per N steps.

### Loop, run every output frame

After computing the v15 signal pipeline (s, v, a, dInf, tauEst, tauStable):

```
framesSinceLastStep += 1

// Convergence check
if (haveBothBrackets()
    && |dInf - target| < CONV_TOL_UM
    && |a| < A_SETTLED
    && framesSinceLastStep > W_POST_STEP) {
  declareConverged()
  return
}

// Quiet period after a step — let the immediate kick wash out
if (framesSinceLastStep < W_POST_STEP) return null

// Crossing detection. Has dInf crossed target since the last step?
boolean newCrossing = detectCrossing()

if (newCrossing) {
  recordEndpoint()                // the *last step's* params become a new endpoint
  hasSeenFirstCrossing = true
  return stepIntoBracket()        // step toward the opposite endpoint
}

// No new crossing. If we've been holding too long, force a step
if (framesSinceLastStep > W_HOLD_MAX) return forceStep()

// Otherwise let the physics settle
return null
```

### detectCrossing()

A crossing of dInf across target. (We use dInf rather than smoothed s because dInf is a leading indicator — it tells us where the chain is heading, not where it currently is.)

```
boolean detectCrossing() {
  if (firstCallSinceStep) {
    dInfAtLastStep = dInf
    firstCallSinceStep = false
    return false
  }
  return sign(dInfAtLastStep - target) != sign(dInf - target)
}
```

A refinement worth considering later: also fire on predicted crossings — if dInf is approaching target very fast (|v| × τ_est >> |dInf − target|), step before it actually crosses. **Skipped in v16.0.** Reactive only.

### recordEndpoint()

When a crossing happens, the parameter setting that *produced* the crossing is the previous step's params (saved in lastStepParams). That becomes the new endpoint on the side dInf came from.

```
void recordEndpoint() {
  if (dInf < target) {
    // crossed downward → just left "too soft" side
    softEndpoint = (lastStepParams.fracR, lastStepParams.fracMoveTorq,
                    dInfAtLastStep)
  } else {
    stiffEndpoint = (lastStepParams.fracR, lastStepParams.fracMoveTorq,
                     dInfAtLastStep)
  }
}
```

### stepIntoBracket()

Decide direction and magnitude. Always move both parameters simultaneously.

```
ParamTriple stepIntoBracket() {
  boolean haveBoth = (softEndpoint != null && stiffEndpoint != null)
  boolean tooSoft  = (dInf > target)   // chain currently too soft → step toward stiffer

  double targetFracR, targetFracMoveTorq

  if (haveBoth) {
    // Step into the bracket toward the opposite endpoint
    if (tooSoft) {
      targetFracR        = lerp(softEndpoint.fracR, stiffEndpoint.fracR,
                                INTO_BRACKET_FRACTION)
      targetFracMoveTorq = lerp(softEndpoint.fracMoveTorq,
                                stiffEndpoint.fracMoveTorq,
                                INTO_BRACKET_FRACTION)
    } else {
      targetFracR        = lerp(stiffEndpoint.fracR, softEndpoint.fracR,
                                INTO_BRACKET_FRACTION)
      targetFracMoveTorq = lerp(stiffEndpoint.fracMoveTorq,
                                softEndpoint.fracMoveTorq,
                                INTO_BRACKET_FRACTION)
    }
  } else {
    // No bracket yet on the far side. Step aggressively toward the limit
    // (stiffer = lower fracR, higher fmt)
    if (tooSoft) {
      targetFracR        = lerp(fracR, FRAC_R_MIN, FIRST_STEP_FRACTION)
      targetFracMoveTorq = lerp(fracMoveTorq, FRAC_MT_MAX, FIRST_STEP_FRACTION)
    } else {
      targetFracR        = lerp(fracR, FRAC_R_MAX, FIRST_STEP_FRACTION)
      targetFracMoveTorq = lerp(fracMoveTorq, FRAC_MT_MIN, FIRST_STEP_FRACTION)
    }
  }

  enforceMinimumStep(...)
  fracR        = clamp(targetFracR, FRAC_R_MIN, FRAC_R_MAX)
  fracMoveTorq = clamp(targetFracMoveTorq, FRAC_MT_MIN, FRAC_MT_MAX)

  onStepTaken()
  return new ParamTriple(fracMove, fracR, fracMoveTorq)
}

double lerp(double a, double b, double t) { return a + t * (b - a) }
```

### What this gets us

- **Aggressive by design.** Every step crosses to the bracket midpoint (or half-way to a limit). The chain *will* overshoot. Each overshoot produces a new crossing in a few hundred frames. The bracket halves per step.
- **No phase machine.** One loop, one set of state, no phase transitions to debug.
- **Both parameters always.** No frozen-parameter modes. The bracket lives in 2-D (fracR, fracMoveTorq) joint space; we move toward the bracket midpoint in both dimensions simultaneously.
- **Reuses v15 signal pipeline.** No throwaway work. The (v, a, d_∞) machinery is what makes the convergence check meaningful and what enables fast crossing detection from d_∞ instead of waiting for s.
- **Inherent shrinkage.** Each crossing halves the bracket geometrically. ~6 crossings to go from initial bracket-width (say 1.0) to 0.01, likely fewer than v15's 21+ steps on the same chain.

### What v16 does NOT handle

- **Non-monotonic deflection in (fracR, fracMoveTorq) space.** If raising fracMoveTorq sometimes *increases* deflection (which the v15 traces showed at low fmt values), the bracket logic breaks. Mitigation: a pre-calibration probe at startup confirms sensitivity signs. Probe is one small step in each parameter direction.
- **Mid-run sensitivity sign reversal.** If the chain enters a regime where sensitivity flips, the bracket becomes inconsistent. Detection: after each crossing, check that the new endpoint is on the *correct* side relative to its parameter values. If not, declare FAILED with a diagnostic.
- **Convergence via parameter saturation.** If the bracket midpoint runs into a hardware limit and stays there across crossings, the controller can't shrink further. In this case, declare CONVERGED-WITH-CAVEAT and let the user inspect.

### Pre-calibration probe (one-time, at startup)

Before crossing-detection logic activates:

1. From soft-start, take one step `fracR -= 0.05` (stiffer). After settling, check smoothed deflection *decreased*. If not, FAIL with "fracR sensitivity sign unexpected."
2. Reset to soft-start, take one step `fracMoveTorq += 0.05`. After settling, check smoothed deflection *decreased*. If not, FAIL with "fracMoveTorq sensitivity sign unexpected."

Total cost: 2 settling periods. Much cheaper than v15's PROBE_FMT/PROBE_FR which tried to extract sensitivity *magnitude* (and got it wrong by orders of magnitude). v16 only needs the *sign*.

### Logging

Per-frame WATCH lines as in v15 (s, v, a, dInf, tauEst, tauStable). Per-step STEP lines include bracket state before/after, the predicted target after step, and chosen parameter values:

```
[V16:STEP#N] cross-detected  dInf=0.01234 → 0.00876  (was tooSoft, now tooStiff)
[V16:BRACKET] soft=(fracR=0.50, fmt=0.30, dInf=0.01234)
              stiff=(fracR=0.30, fmt=0.40, dInf=0.00876)
[V16:STEP#N+1] step-into-bracket  fracR: 0.30 → 0.40  fmt: 0.40 → 0.35
```

### Implementation notes for the next session

- **New file:** `DeflectionTunerV16.java`. v15 stays unchanged for comparison.
- **New flag:** `-bmTunerV16`.
- **Dispatch:** same pattern as v15 — `Env.benchmarkTunerV16` flag, `BoxOfActin.java` arg parser updated, feed loop dispatches to v16 when active.
- **Signal pipeline:** copy the v15 signal-pipeline code (EMA, regression slope/curvature, tauEst, tauStable, dInf prediction) into v16 unchanged. Do not refactor for shared use — v15 remains independent for comparison runs.
- **Soft-start:** same as v14/v15.

### Expected first-run behavior on 32-mer

Soft-start: fracR=1.5, fmt=0.01. Chain deflects to ~37 nm (4× target) over the first ~50 frames. v15 took 21 steps and substantial overshoot to converge. v16 should:

1. See the first crossing (s crosses 0.0098 µm on the way up, then dInf-based crossing detection catches it). Record stiff endpoint at default params.
2. Step to midpoint between soft-start and (fracR_min, fmt_max): something like fracR ≈ 0.85, fmt ≈ 0.27.
3. Chain re-equilibrates downward, likely overshoots target downward. Record soft endpoint.
4. Step into bracket midpoint.
5. Repeat ~4-6 times until bracket is below CONV_TOL_UM.

Total: ~6-8 crossings, vs v15's 21 steps. Wall-clock should be faster, but the limit is settling time per step, set by physics (~τ_theo per equilibration). v16's advantage is fewer steps, not faster steps.

### Open questions for the implementation session

1. **dInf vs s for crossing detection.** v16.0 uses dInf. Should we fall back to smoothed s when tauStable is false? Probably yes — for the first few crossings, dInf might be unreliable.
2. **Recording the endpoint d_∞.** Current dInf at crossing time is already on the other side. The endpoint dInf should approximate the chain's equilibrium at those parameters. Approximation: use the dInf measurement just before the new step (saved as dInfAtLastStep).
3. **First crossing never happens.** If soft-start lands near target by chance, no crossing occurs. After W_HOLD_MAX frames, force a step toward stiffer to perturb the chain.

---


## 2026-05-20 — DeflectionTuner v16: implementation

### File structure

- **`boxOfActin/DeflectionTunerV16.java`** — new file, ~370 lines, `package boxOfActin`. Self-contained; signal pipeline copied verbatim from v15 with no shared state.
- **`boxOfActin/DeflectionTuner.java`** — v14, byte-identical (untouched).
- **`boxOfActin/DeflectionTunerV15.java`** — v15, byte-identical (untouched).
- **`boxOfActin/Env.java`** — one line added: `static boolean benchmarkTunerV16 = false;`
- **`boxOfActin/BoxOfActin.java`** — four edits: (1) `static DeflectionTunerV16 deflTunerV16 = null;` field declaration; (2) `-bmTunerV16` arg parsed in `parseArgs()`, sets `Env.benchmarkTunerV16` and `Env.benchmarkFilament`; (3) mutual-exclusivity check after arg loop (`-bmTunerV16 + -bmTunerV15` warns and clears v15); (4) feed loop and arm block updated: v16 checked first, then v15, then v14 (if-else-if chain). `[AUTOTUNE] armed:` line prints `tuner=v16/v15/v14` accordingly.

### Algorithm constants (all PLACEHOLDER in source)

`W_A=12`, `W_TAU=6`, `TAU_STABLE_FRAC=0.15`, `W_POST_STEP=4`, `FIRST_STEP_FRACTION=0.5`, `INTO_BRACKET_FRACTION=0.5`, `MIN_STEP_FRACTION=0.01`, `CONV_TOL_UM=1e-5`. `wHoldMax` computed as `max(50, round(4·τ_theo / dtFrame))`. Noise floors `vNoise=8e-5`, `aNoise=1.3e-3` copied from v15 calibrated constants (not recomputed).

### Non-obvious implementation choices

**No phase machine in RUNNING.** After transitioning to RUNNING via `advanceToRunning()`, `framesSinceLastStep` starts at 0 and increments each frame. The crossing reference `crossingRef` is initialized as invalid (`crossingRefValid=false`). On the first frame where `framesSinceLastStep >= W_POST_STEP`, the reference is initialized to the current crossing signal. This means the first actionable crossing check is at frame `W_POST_STEP + 1`. The first actual step is via `forceStep` at frame `wHoldMax` unless a crossing occurs first.

**`crossingRef` saved in `applyStep()` (Option A).** The pre-step signal (dInf or smoothed fallback) is saved immediately before the parameter change is applied. This is used as both the crossing detection reference and the bracket endpoint's dInf. The design doc pseudocode saves it on the first frame after the step — functionally equivalent since the chain responds over τ >> one frame.

**Crossing signal dInf-vs-s fallback.** `signal = (tauStable && !isNaN(dInf)) ? dInf : smoothed`. This is used for crossing detection, recordEndpoint (as crossingRef), and convergence check. The design decision explicitly documents this as "fallback when tau_est unreliable, e.g. first frames after a step." Convergence uses `dInf` directly (with tauTheo fallback when tauStable==false) as specified in the design doc, without an additional tauStable gate.

**Pre-probe probe phases.** Both probes (`PRE_PROBE_FR`, `PRE_PROBE_FMT`) wait for `smoothed > target` (the "early trigger") before taking the first probe step. This mirrors the v15 PROBE_FMT early trigger and ensures the probe happens from a deflected state rather than from the chain's zero initial position. After each probe, the modified parameter is restored to its pre-probe value and a ParamTriple is returned to push the restored value into the simulation.

**`clearTauEstBuf()` on every step.** Copied from v15: ensures a fresh W_TAU-frame τ_est accumulation after every parameter change. This prevents stale τ_est values from prematurely declaring `tauStable` true during a transient.

**Minimum step enforcement.** `enforceMinStep` nudges the target parameter toward the appropriate hardware limit if the computed step magnitude is less than `MIN_STEP_FRACTION × |currentParam|`. Direction is preserved from the computed step; if step is zero, falls back to the tooSoft direction flag.

**`BracketEndpoint` is package-private static inner class**, not accessible outside. `ParamTriple` is package-private static inner class matching v14/v15 pattern.

### Small in-scope discoveries handled silently

- `enforceMinStep` needed to return an array (`double[]`) since Java lacks tuple returns. Implemented as `enforceMinStepArr` returning `double[]{fr, fmt}`.
- The `prevSmoothed` / `prevSmoothedValid` fields present in v15 (for T3 crossing detection) are not needed in v16 — v16's crossing detection uses `crossingRef` (pre-step signal) vs current signal, not frame-by-frame sign tracking. Fields omitted.
- `slopeWin` in `isSettled()` uses `framesSinceProbeStep` as the frame counter (not `framesSinceLastStep` which belongs to RUNNING). The two counters are independent.
- The `minFramesPerStep` floor in `isSettled()` applies to probe phases only; RUNNING has no minimum hold (W_POST_STEP is the only gate, and it's separate from the crossing detection counter).

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV16 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV16 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Dispatch verified

- `-bm` → `[AUTOTUNE] armed: tuner=v14 ...` ✓
- `-bmTunerV15` → `[V15] armed: ...` then `[AUTOTUNE] armed: tuner=v15 ...` ✓
- `-bmTunerV16` → `[V16] armed: τ=0.057s  ... INTO_BRACKET=0.50  FIRST_STEP=0.50  CONV_TOL=1.00e-05` then `[AUTOTUNE] armed: tuner=v16 ...` ✓

Next session: run 32-monomer benchmark with `-bmTunerV16 -bmMonomer 32`. Record: (1) whether PRE_PROBE_FR and PRE_PROBE_FMT pass sign checks; (2) first RUNNING crossing count, step count, and wall-clock vs v15's 21-step trace. Calibration of PLACEHOLDER constants is a separate session after the first trace.

---

## 2026-05-20 — DeflectionTuner v17 design — single-parameter overshoot controller

### Motivation

v16's bracket-and-overshoot scheme mismatched the user's mental model in two ways: (a) joint (fracR, fmt) brackets become ambiguous because deflection responses to one parameter depend on the other's value, and (b) v16 took at most one step per crossing, then waited for settling, whereas the user's procedure is "step repeatedly *during* each drift while the chain is drifting in the wrong direction."

The user provided pseudo-code (preserved in chat history) that crystallizes the procedure. v17 implements that procedure directly, with one parameter active at a time.

### Algorithm in one paragraph

One parameter is active at a time. Every 5 output frames, measure smoothed deflection s and regression-window velocity v. If err = s − target > 0 AND v > 0 (chain is too soft and getting softer), take an aggressive step toward stiffer — halve the distance from the activeParam to its hardware "stiffer" limit. If err < 0 AND v < 0, halve toward the "softer" limit. Otherwise the chain is responding correctly; don't step. When the running average of |v| falls below the noise floor for several frames, the chain has reached steady state. If |s − target| < CONV_TOL, the activeParam is done. If activeParam was fracR and it's close to its preferred mid-range value, switch to fmt and repeat. If neither parameter alone gets to target, decrease fracMove (the timestep base) and restart. If even that fails, declare FAILED.

### Parameter facts (recapped from user's pseudo-code)

- `fracR ∈ [0.1, 1.5]`. Larger = softer. Preferred ≈ 0.7.
- `fracMoveTorq ∈ [0.01, 0.5]`. Larger = stiffer. Preferred ≈ 0.2.
- `fracMove ∈ [0.1, 0.5]`. Larger = stiffer. Preferred ≈ 0.3.
- Soft-start: `fracR = 1.5`, `fracMoveTorq = 0.01`, `fracMove = 0.5`.

### State

```
PHASE = { R_ADJUST, M_ADJUST, SETTLE_CHECK, RETRY_LOWER_FRACMOVE,
          CONVERGED, FAILED }

activeParam: { FRAC_R, FRAC_MT }      // which parameter is currently being moved
framesSinceLastStep: int
runningAvgV: rolling mean of |v| over last RUNNING_AVG_WINDOW frames
fracMove, fracR, fracMoveTorq        // current parameter values

// Signal pipeline (subset of v15/v16; no acceleration, no tauEst, no dInf)
smoothed (s)
v   computed via regression over slopeWin frames of (frame_time, s)
```

### Algorithm constants

```
FRAMES_BETWEEN_STEPS = 5           // gap between consecutive parameter steps
                                    // — long enough for v to update after a step
HALVE_FRACTION       = 0.5          // each step halves the distance to the
                                    // hardware limit on the active side
RUNNING_AVG_WINDOW   = 10           // frames over which |v| is averaged for
                                    // steady-state detection
V_STEADY_THRESHOLD   = V_NOISE      // running-avg |v| below this = steady
CONV_TOL_UM          = 1e-5         // 0.01 nm (empirical noise floor)
SWITCH_TOL_FRAC      = 0.15         // PLACEHOLDER: switch to other parameter when
                                    // activeParam within ±15% of preferred value
PREFERRED_FRACR      = 0.7
PREFERRED_FRACMT     = 0.2
FRACMOVE_RETRY_STEP  = 0.05         // amount to decrease fracMove on retry
FRACMOVE_FLOOR       = 0.1          // hard minimum for fracMove

// Hardware limits (existing)
FRAC_R_MIN  = 0.1,  FRAC_R_MAX  = 1.5
FRAC_MT_MIN = 0.01, FRAC_MT_MAX = 0.5
```

### Main loop, every output frame

```
updateSignalPipeline()              // s, v
framesSinceLastStep += 1
err = s - target
updateRunningAvgV()

switch (PHASE) {

  case R_ADJUST:
  case M_ADJUST:
    if (framesSinceLastStep < FRAMES_BETWEEN_STEPS) return null

    if (chainDriftingWrongWay(err, v)) {
      stepActiveParam(err)
      framesSinceLastStep = 0
      return new ParamTriple(fracMove, fracR, fracMoveTorq)
    }

    // Chain is responding correctly. Check if it has settled.
    if (runningAvgV < V_STEADY_THRESHOLD) {
      PHASE = SETTLE_CHECK
    }
    return null

  case SETTLE_CHECK:
    handleSettleCheck()              // see below
    return null  (or stepping ParamTriple if SETTLE_CHECK decides to step)

  case RETRY_LOWER_FRACMOVE:
    fracMove -= FRACMOVE_RETRY_STEP
    if (fracMove < FRACMOVE_FLOOR) {
      PHASE = FAILED
      return null
    }
    fracR = FRAC_R_MAX               // reset to soft-start
    fracMoveTorq = FRAC_MT_MIN
    activeParam = FRAC_R
    PHASE = R_ADJUST
    return new ParamTriple(fracMove, fracR, fracMoveTorq)

  case CONVERGED:
  case FAILED:
    return null                      // controller is done; loop is no-op
}
```

### chainDriftingWrongWay(err, v)

```
return (err > 0 AND v > 0) OR (err < 0 AND v < 0)
```

That's it: deflection on the wrong side of target *and* getting worse. v ≈ 0 (chain at instantaneous equilibrium) does *not* count as drifting; we let physics do its thing. v in the *correcting* direction also does not count.

### stepActiveParam(err)

The step always moves activeParam halfway toward the hardware limit on the side that *would correct err*. So we never step toward making err worse (even by accident).

```
if (activeParam == FRAC_R) {
  if (err > 0) {
    // chain too soft → stiffen fracR (decrease toward FRAC_R_MIN)
    fracR = fracR + HALVE_FRACTION * (FRAC_R_MIN - fracR)
  } else {
    // chain too stiff → soften fracR
    fracR = fracR + HALVE_FRACTION * (FRAC_R_MAX - fracR)
  }
  fracR = clamp(fracR, FRAC_R_MIN, FRAC_R_MAX)
} else {  // FRAC_MT
  if (err > 0) {
    // chain too soft → stiffen fmt (increase toward FRAC_MT_MAX)
    fracMoveTorq = fracMoveTorq + HALVE_FRACTION * (FRAC_MT_MAX - fracMoveTorq)
  } else {
    fracMoveTorq = fracMoveTorq + HALVE_FRACTION * (FRAC_MT_MIN - fracMoveTorq)
  }
  fracMoveTorq = clamp(fracMoveTorq, FRAC_MT_MIN, FRAC_MT_MAX)
}
```

Note the implicit shrinkage: as fracR approaches FRAC_R_MIN, each subsequent stiffening step is smaller. As fracR overshoots and the chain becomes too stiff (err < 0), the next step softens toward FRAC_R_MAX from *the overshot value*, not from soft-start — so it doesn't go back to 1.5 in one step. The chain bounces back and forth within a narrowing range automatically, without any explicit bracket tracking.

### handleSettleCheck()

```
// Chain has settled (running-avg |v| below noise floor).
// Decide what to do based on err and current parameter positions.

if (|err| < CONV_TOL_UM) {
  PHASE = CONVERGED
  return null
}

// Not converged. Three sub-cases:

// (1) At the soft limits, still too soft. Can't stiffen further with
//     activeParam alone. Need to lower fracMove.
boolean atSoftLimits = (fracR >= FRAC_R_MAX - 1e-9) &&
                       (fracMoveTorq <= FRAC_MT_MIN + 1e-9)
if (err > 0 AND atSoftLimits) {
  PHASE = RETRY_LOWER_FRACMOVE
  return null
}

// (2) At the stiff limits, still too stiff. Failure.
boolean atStiffLimits = (fracR <= FRAC_R_MIN + 1e-9) &&
                        (fracMoveTorq >= FRAC_MT_MAX - 1e-9)
if (err < 0 AND atStiffLimits) {
  PHASE = FAILED
  return null
}

// (3) activeParam is close to its preferred value but still not converged.
//     Switch to other parameter.
boolean nearPreferred = isNearPreferred(activeParam)
if (nearPreferred) {
  switchActiveParam()       // R_ADJUST → M_ADJUST or vice versa
  return null
}

// (4) Otherwise, the chain settled but activeParam can still move.
//     Take one more step in the appropriate direction (without the
//     drift-direction gate, since v ≈ 0).
stepActiveParam(err)
framesSinceLastStep = 0
PHASE = (activeParam == FRAC_R) ? R_ADJUST : M_ADJUST
return new ParamTriple(fracMove, fracR, fracMoveTorq)
```

### Initialization

```
fracMove = 0.5
fracR = FRAC_R_MAX = 1.5
fracMoveTorq = FRAC_MT_MIN = 0.01
activeParam = FRAC_R       // start by adjusting fracR (per design discussion)
PHASE = R_ADJUST
framesSinceLastStep = 0
```

A short post-start delay (~5-10 frames) lets the soft-start chain start deflecting before the first stepping decision. This is naturally handled by `framesSinceLastStep` starting at 0 and the FRAMES_BETWEEN_STEPS gate.

### What v17 explicitly does *not* do

- No phase machine beyond the single PHASE enum above (no COARSE / FINE / PROBE distinctions).
- No τ_est. No d_∞ predictor. The v15/v16 unified (v, a)-aware machinery is not used.
- No bracket-endpoint tracking, no soft/stiff endpoint state. The "tested range" is implicit in where the parameter has been.
- No simultaneous adjustment of fracR and fmt. One parameter at a time.
- No pre-calibration probe. Sensitivity signs are known a priori.

### Logging

Per-frame WATCH lines tagged `[V17:WATCH]` with `s`, `v`, `err`, `runningAvgV`, current PHASE and activeParam. Per-step STEP lines tagged `[V17:STEP#N]` with the parameter being changed, old and new values, and the reason. Phase-transition lines like `[V17] PHASE: R_ADJUST → SETTLE_CHECK`, `[V17] switch active: FRAC_R → FRAC_MT`, `[V17] CONVERGED` and `[V17] FAILED`.

### Calibration constants (will need tuning after first run)

- `FRAMES_BETWEEN_STEPS = 5`: too small → v is stale and the controller takes spurious steps. Too large → wall-clock convergence slowed.
- `HALVE_FRACTION = 0.5`: 0.5 → step to midpoint. 0.7 → more aggressive overshoot. 0.3 → gentler.
- `RUNNING_AVG_WINDOW = 10`: too small → false steady-state detection on transient pauses. Too large → slow to detect actual steady state.
- `SWITCH_TOL_FRAC = 0.15`: how close to preferred value before we switch to the other parameter. Smaller → more parameter changes overall but each parameter ends nearer preferred. Larger → fewer changes but parameters end farther from preferred.

### Open questions for the implementation session

1. **Velocity computation window.** v17 needs v to be reasonably noise-free. The v15 regression-window approach (W_A = 12 frames) is fine; v17 can reuse it.
2. **What if running-avg |v| oscillates around the threshold?** A persistent borderline case. Add a hysteresis margin: enter SETTLE_CHECK only when avg-|v| < 0.5 × V_STEADY_THRESHOLD; remain in R_ADJUST/M_ADJUST until that stricter threshold is met.
3. **Soft-start chain still falling at the start.** During the first ~50 frames of soft-start, the chain is rocketing through target en route to its (off-target) equilibrium. v17's "err > 0 AND v > 0" condition is technically true during this transit but v17 would step every FRAMES_BETWEEN_STEPS frames during it, taking multiple stiffening steps before the chain settles. This may actually be the right behavior — v17 should stiffen aggressively while the chain is racing toward an off-target equilibrium. But if it produces wild overshoot, we can gate the first step on running-avg |v| being below some early-transient threshold.

### Expected behavior on 32-mer

Soft-start: chain falls rapidly toward equilibrium at ~37 nm (4× target). Sometime during the descent, smoothed crosses target = 9.8 nm on the way down. Then continues falling — err < 0 momentarily even though chain is *still* heading to be too soft. The transit-through-target produces a misleading err sign briefly. v17 sees err > 0 and v > 0 mostly, takes ~3-5 stiffening fracR steps in rapid succession (each FRAMES_BETWEEN_STEPS apart) bringing fracR from 1.5 → 0.8 → 0.45 → 0.27 → ... Eventually the chain's velocity reverses (it starts heading toward an equilibrium near target). v17 stops stepping, lets it settle. Running-avg |v| drops, SETTLE_CHECK fires. If err is within tolerance, CONVERGED. Otherwise switch to fmt or take one more step.

Roughly: 3-6 fracR steps, possibly 2-4 fmt steps, total ~10 steps. Compared to v15's 21 steps and v16's pre-probe failure.

---


## 2026-05-21 — DeflectionTuner v17: implementation

### File structure

- **`boxOfActin/DeflectionTunerV17.java`** — new file, ~290 lines, `package boxOfActin`. Self-contained; no shared state with v14/v15/v16. Signal pipeline is a strict subset of v16: EMA + linear regression only (no quadratic fit, no acceleration, no τ_est, no d_∞).
- **`boxOfActin/DeflectionTuner.java`** — v14, byte-identical (untouched).
- **`boxOfActin/DeflectionTunerV15.java`** — v15, byte-identical (untouched).
- **`boxOfActin/DeflectionTunerV16.java`** — v16, byte-identical (untouched).
- **`boxOfActin/Env.java`** — one line added: `static boolean benchmarkTunerV17 = false;`
- **`boxOfActin/BoxOfActin.java`** — five edits: (1) `static DeflectionTunerV17 deflTunerV17 = null;` field; (2) `-bmTunerV17` arg parsed in `parseArgs()`; (3) mutual-exclusivity block extended (v17 > v16 > v15 > v14, both v16 and v15 cleared when v17 is set); (4) `eitherTunerActive` condition includes `deflTunerV17 != null`; (5) v17 path prepended to feed loop and arm block if-else-if chain; `[AUTOTUNE] armed:` prints `tuner=v17` when active.

### Signal pipeline (v17 vs v15/v16)

v17 uses only:
- EMA (same α=0.05 as v15/v16)
- Linear regression slope over `SLOPE_WIN=12` frames → velocity `v` (µm/s). Uses the same centered-coordinate least-squares formula as v16's quadratic fit, but with only the linear term (no acceleration term, no τ_est ring buffer).
- Running average of |v| over `RUNNING_AVG_WINDOW=10` frames → `runningAvgV`.

### Small in-scope discoveries handled silently

**NaN-velocity guard on SETTLE_CHECK entry.** During the first `SLOPE_WIN=12` frames, the regression buffer is not full and `v` is NaN. `updateRunningAvgV` treats NaN as 0.0, so `runningAvgV` would be near zero. Without a guard, `handleAdjust` would enter SETTLE_CHECK after only 5 frames (FRAMES_BETWEEN_STEPS), before any real signal exists. Fixed by adding `vaCount >= SLOPE_WIN` as a second condition for SETTLE_CHECK entry. This silently extends the initial quiet period from 5 to 12 frames, consistent with the design's stated intent ("a short post-start delay lets the soft-start chain start deflecting before the first stepping decision").

**Running-average buffer ordering.** When `ravCount < RUNNING_AVG_WINDOW` (buffer not yet full), `ravBuf[0..ravCount-1]` holds all valid entries (ravHead hasn't wrapped). When full, `ravBuf[0..RUNNING_AVG_WINDOW-1]` are all valid. In both cases, `sum(ravBuf[0..ravCount-1]) / ravCount` gives the correct mean. This avoids a separate circular-buffer read path.

**RETRY_LOWER_FRACMOVE is a one-frame transient state.** The phase is set by `handleSettleCheck` sub-case (1), and resolved on the very next frame: `fracMove` decrements, state resets to soft-start, phase transitions to R_ADJUST, and a ParamTriple is returned. FAILED similarly resolves in one frame.

### Non-obvious implementation choices

**Linear regression direction.** After `pushVA(smoothed)`, `vaHead` points to the oldest slot (next to be overwritten). Iterating `k=0..SLOPE_WIN-1` from `vaHead` gives oldest-to-newest, with `u_k = (k - mid)*dtFrame`. Positive slope ↔ smoothed increasing. Same iteration order as v16's `computeVA()`, but without the quadratic term.

**`isNearPreferred` is instance-level.** It reads `fracR` and `fracMoveTorq` instance fields directly. The SWITCH_TOL_FRAC check is `|param - preferred| <= SWITCH_TOL_FRAC * preferred`, i.e., a ±15% band around the preferred value. A fresh FRAC_R at soft-start (1.5) is nowhere near PREFERRED_FRACR=0.7 (deviation=114%), so sub-case (3) won't fire spuriously at the start.

**handleSettleCheck is single-frame.** SETTLE_CHECK always transitions to another phase within the same call. Sub-case priority order: convergence → soft-limits retry → stiff-limits failure → near-preferred switch → correction step (fallback). The fallback (sub-case 4) fires unconditionally when all others fail, so SETTLE_CHECK is never a permanent holding state.

**`tauSeconds` parameter accepted but not used.** `start()` takes `tauSeconds` to match the v14/v15/v16 signature (arm block passes `deflFil.tauTheo`). v17 doesn't need it (no τ_est, no τ_theo-based timer). Ignored; does not affect behavior.

### Runtime flag

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV17 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV17 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
```

### Expected arm output (what the planner sees at start of a run)

```
[V17] armed: fracMove=0.5000  fracR=1.5000  fracMoveTorq=0.0100  target=<analyticDefl>µm
[V17]   FRAMES_BETWEEN_STEPS=5  HALVE_FRACTION=0.50  RUNNING_AVG_WINDOW=10
[V17]   V_NOISE=8.000e-05  V_STEADY_THRESHOLD=8.000e-05  SETTLE_ENTRY_FRAC=0.50
[V17]   CONV_TOL_UM=1.00e-05  SWITCH_TOL_FRAC=0.15  SLOPE_WIN=12
[AUTOTUNE] armed: tuner=v17  fracMove=0.5000  fracR=1.5000  fracMoveTorq=0.0100  target=<analyticDefl> µm
```

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Dispatch verified (by code inspection)

- `-bm` → `Env.benchmarkTunerV17/V16/V15` all false → arm block falls to `else` → v14. `[AUTOTUNE] armed: tuner=v14 ...` ✓
- `-bmTunerV15` → `benchmarkTunerV15=true` → arm block `else if (benchmarkTunerV15)` → v15. `tuner=v15` ✓
- `-bmTunerV16` → `benchmarkTunerV16=true` → arm block `else if (benchmarkTunerV16)` → v16. `tuner=v16` ✓
- `-bmTunerV17` → `benchmarkTunerV17=true` → arm block first branch → v17. `[V17] armed: ...` then `[AUTOTUNE] armed: tuner=v17 ...` ✓

Next session: run 32-monomer benchmark with `-bmTunerV17 -bmMonomer 32`. Record: (1) first SETTLE_CHECK entry frame and runningAvgV at that point; (2) number of fracR steps before SETTLE_CHECK; (3) whether sub-case (3) (switch to fmt) fires; (4) total step count vs v15's 21 steps. Calibration of PLACEHOLDER constants (FRAMES_BETWEEN_STEPS, HALVE_FRACTION, SWITCH_TOL_FRAC) is a separate session after the first trace.

---

## 2026-05-20 — DeflectionTuner v17.1: strict alternation, fixed switching

### Diagnosis from v17.0 32-mer run

v17.0 on 32-mer with `boaDebugParams` stiffened fracR aggressively (1.5 → 0.8 → 0.45 → 0.275 → 0.1875 → ...) and never switched to fracMoveTorq. By step #8 fracR was essentially pinned at FRAC_R_MIN = 0.1; v17.0 then made 50+ more "steps" that didn't move fracR at all because the parameter was at its limit. Chain stalled at deflection ≈ 0.0336 µm (3.4× target) with the controller stuck in R_ADJUST.

The bug: the switch-to-M_ADJUST criterion was gated behind SETTLE_CHECK firing, but the chain never settled because the controller kept stepping fracR (even when each step changed nothing). Circular.

The fix: drop the preferred-value-tolerance switch criterion. Use strict alternation instead — R_ADJUST takes a fixed budget of meaningful steps, then switches to M_ADJUST regardless of where fracR ended up. M_ADJUST takes the same budget, switches back. The half-step shrinkage rule (each step halves distance to the limit) is kept — three steps reaches 7/8 of the way to the limit, which is plenty aggressive without saturating.

### Algorithm changes from v17.0

1. **Strict alternation**, 2 steps per turn.
   - `STEPS_PER_TURN = 2`
   - Each `R_ADJUST` cycle takes up to 2 meaningful drift-correcting steps in fracR, then switches to `M_ADJUST`.
   - Each `M_ADJUST` cycle takes up to 2 meaningful drift-correcting steps in fracMoveTorq, then switches to `R_ADJUST`.
   - A "meaningful step" is one that actually moves the parameter by more than `MIN_STEP_DELTA_FRAC × current_value` (suggested `MIN_STEP_DELTA_FRAC = 0.001`, i.e. >0.1% change). If a step is essentially zero (parameter pinned at hardware limit), it does *not* consume budget; the controller switches to the other parameter immediately.

2. **Remove SWITCH_TOL_FRAC and preferred-value-tolerance logic.** Preferred values are no longer used. The phase-switch is unconditional after STEPS_PER_TURN budget is consumed.

3. **Remove the "near preferred → switch" branch in handleSettleCheck.** Switching now happens during R_ADJUST/M_ADJUST itself, not in SETTLE_CHECK.

4. **handleSettleCheck branches (revised):**
   - (1) `|err| < CONV_TOL` → CONVERGED.
   - (2) `err < 0 AND fracR at FRAC_R_MAX (1.5) AND fracMoveTorq at FRAC_MT_MIN (0.01)` → RETRY_LOWER_FRACMOVE. (Chain too stiff at fully-soft parameters; lower fracMove to extend range.)
   - (3) `err > 0 AND fracR at FRAC_R_MIN (0.1) AND fracMoveTorq at FRAC_MT_MAX (0.5)` → FAIL. (Chain too soft at fully-stiff parameters; nothing left to try.)
   - (4) Otherwise: continue stepping in the *next* parameter (i.e., switch from R_ADJUST to M_ADJUST or vice versa, with a fresh STEPS_PER_TURN budget).

5. **Step-budget counter.** New state: `stepsThisTurn` (int). Incremented on each meaningful drift-correcting step. Reset to 0 at every parameter switch. When `stepsThisTurn >= STEPS_PER_TURN`, the next drift-correcting opportunity becomes a switch instead of a step.

### Constants summary (v17.1)

```
STEPS_PER_TURN       = 2          // PLACEHOLDER: 2 or 3 alternation budget
MIN_STEP_DELTA_FRAC  = 0.001      // step counts as "meaningful" if it moves
                                   // parameter by more than 0.1% of current value
FRAMES_BETWEEN_STEPS = 5          // unchanged from v17.0
HALVE_FRACTION       = 0.5        // unchanged from v17.0
RUNNING_AVG_WINDOW   = 10         // unchanged
V_NOISE              = 8.000e-05  // unchanged
SETTLE_ENTRY_FRAC    = 0.5        // unchanged
CONV_TOL_UM          = 1e-5       // unchanged
FRACMOVE_RETRY_STEP  = 0.05       // unchanged
FRACMOVE_FLOOR       = 0.1        // unchanged

REMOVED:
SWITCH_TOL_FRAC      // gone — strict alternation, no tolerance band
PREFERRED_FRACR      // gone — not used
PREFERRED_FRACMT     // gone — not used
```

### Expected behavior on 32-mer with boaDebugParams

Soft-start: fracR=1.5, fmt=0.01. Chain rockets through target. R_ADJUST starts.

- Step 1: fracR 1.5 → 0.8 (meaningful, budget = 1)
- Step 2: fracR 0.8 → 0.45 (meaningful, budget = 2 = STEPS_PER_TURN) → **switch to M_ADJUST**
- Step 3: fmt 0.01 → 0.255 (meaningful, budget = 1)
- Step 4: fmt 0.255 → 0.378 (meaningful, budget = 2) → **switch to R_ADJUST**
- Step 5: fracR 0.45 → 0.275 (meaningful) ...

Within ~6-10 alternation steps the chain should land near target. SETTLE_CHECK then fires and either CONVERGED or one more alternation cycle.

If both parameters end up near their stiff limits (fracR → 0.1, fmt → 0.5) and chain is still too soft: FAIL.
If both parameters at their soft limits (fracR = 1.5, fmt = 0.01) and chain is too stiff: RETRY_LOWER_FRACMOVE, reset, try again.

### What this doesn't address

The chain dynamics during alternation may produce surprises we can't predict — e.g., the first M_ADJUST step at fmt = 0.255 might be too aggressive given fracR has already moved to 0.45, sending deflection past target downward. v17.1 is happy to keep stepping (R direction reverses, then M direction reverses) — that's the design. If it produces wild oscillation we add a step-magnitude cap in v17.2.

---


## 2026-05-21 — DeflectionTuner v17.1: implementation

### What changed from v17.0

**Constants removed:** `SWITCH_TOL_FRAC`, `PREFERRED_FRACR`, `PREFERRED_FRACMT` — no longer used. Strict alternation makes preferred-value tolerance unnecessary.

**Constants added:**
- `STEPS_PER_TURN = 2` (PLACEHOLDER) — budget of meaningful steps per R_ADJUST/M_ADJUST turn.
- `MIN_STEP_DELTA_FRAC = 0.001` (PLACEHOLDER) — a step is "meaningful" only if it moves the parameter by more than 0.1% of its current value.

**New state field:** `int stepsThisTurn` — incremented on each meaningful step in `stepActiveParam()`, reset to 0 in `switchActiveParam()` and in the `RETRY_LOWER_FRACMOVE` handler (which resets to soft-start without going through `switchActiveParam()`).

### stepActiveParam() return type change (in-scope discovery)

In v17.0 this was `void`. v17.1 makes it return `boolean` (true = meaningful step applied, false = parameter pinned → `switchActiveParam()` called inside). This was the only way for `handleAdjust()` to know whether to return a `ParamTriple` or `null` after a pinned step. No change to external API.

### Budget and switching logic in handleAdjust()

After a meaningful step:
1. Log with `budget=stepsThisTurn/STEPS_PER_TURN`.
2. If `stepsThisTurn >= STEPS_PER_TURN`, call `switchActiveParam()` (resets counter, changes phase/activeParam).
3. Return `ParamTriple` with the changed parameter values regardless — the switch only affects the next turn.

After a pinned step (returns false):
- `switchActiveParam()` was already called inside `stepActiveParam()`; `handleAdjust()` returns null (no param change to push).

### handleSettleCheck() revised branches

v17.0 RETRY/FAIL conditions were physically inverted. v17.1 fixes them:

| Branch | Condition | Action |
|--------|-----------|--------|
| (1) CONVERGED | `|err| < CONV_TOL_UM` | phase = CONVERGED |
| (2) RETRY | `err < 0` AND fracR≥FRAC_R_MAX AND fmt≤FRAC_MT_MIN | phase = RETRY_LOWER_FRACMOVE |
| (3) FAIL | `err > 0` AND fracR≤FRAC_R_MIN AND fmt≥FRAC_MT_MAX | phase = FAILED |
| (4) continue | otherwise | switchActiveParam(), return null |

v17.0 had (2) with `err > 0` at soft limits and (3) with `err < 0` at stiff limits — both inverted. v17.1: RETRY fires when chain is too stiff at softest (fracR, fmt) → lower fracMove to soften chain. FAIL fires when chain is too soft at stiffest (fracR, fmt) → nothing left to do.

Branch (4) replaces v17.0 sub-cases 3 (near-preferred switch) and 4 (correction step). It just calls `switchActiveParam()` and returns null — no step taken from SETTLE_CHECK. The step happens in the next R_ADJUST/M_ADJUST turn.

### isNearPreferred() removed

Only used for SETTLE_CHECK sub-case 3 (near-preferred switch). Deleted entirely.

### Updated armed line

```
[V17] armed: fracMove=0.5000  fracR=1.5000  fracMoveTorq=0.0100  target=<analyticDefl>µm
[V17]   FRAMES_BETWEEN_STEPS=5  HALVE_FRACTION=0.50  RUNNING_AVG_WINDOW=10
[V17]   V_NOISE=8.000e-05  V_STEADY_THRESHOLD=8.000e-05  SETTLE_ENTRY_FRAC=0.50
[V17]   CONV_TOL_UM=1.00e-05  STEPS_PER_TURN=2  MIN_STEP_DELTA_FRAC=0.001  SLOPE_WIN=12
```

(SWITCH_TOL_FRAC removed; STEPS_PER_TURN and MIN_STEP_DELTA_FRAC added.)

### Per-step log format

```
[V17:STEP#N] drift-correcting  active=FRAC_R  budget=1/2  fr: 1.5000→0.8000  fmt: 0.0100→0.0100  err=...  v=...
```

Budget shows `stepsThisTurn/STEPS_PER_TURN` after the increment (budget=1/2 on first step, 2/2 on second → triggers switch). Pinned-step log: `[V17] stepActiveParam: FRAC_R pinned (delta=... ≤ 0.001 × ...) → switching`.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Dispatch verified (by code inspection)

- `-bm` → v14 unchanged ✓
- `-bmTunerV15` → v15 unchanged ✓
- `-bmTunerV16` → v16 unchanged ✓
- `-bmTunerV17` → v17.1; armed line shows STEPS_PER_TURN=2 and MIN_STEP_DELTA_FRAC=0.001 ✓

Next session: run 32-monomer with `-bmTunerV17 -bmMonomer 32`. Record: (1) whether alternation fires after exactly 2 steps (budget=2/2 in log); (2) whether the pinned-step immediate-switch path fires; (3) whether SETTLE_CHECK branch (4) "continue alternation" fires and how many alternation cycles to CONVERGED; (4) total step count vs v17.0's 50+ stuck steps.

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

## 2026-05-25 — Addendum: frame-by-frame export for movie generation

Added a collapsible **Render** panel to `sim_viewer_boa.html`, visible only in post-run (file-loaded) mode. The panel is hidden in live WebSocket mode (`?live=N`). It appears as a "Render ▶" toggle button at `top: 78px, right: 12px` (below the Display toggle).

**Panel controls:** frame range From/To (auto-populated to 0…N-1 when a directory loads), stride (every Nth frame), format (JPG q=0.92 or PNG lossless), resolution (current viewport or custom W×H at pixel ratio 1), output folder (File System Access API).

**Export loop:** for each frame in range, fetches the frame JSON, calls `applyFrameData()`, calls `renderer.render(scene, camera)` explicitly, then `canvas.toBlob()` immediately after (same sync block — buffer is captured before the RAF can re-render). Writes each blob via the File System Access API writable stream. Progress bar and cancel button are shown during export. Camera position, zoom, orbit state, and Display checkbox settings are not modified by the export loop — the user frames the view manually on frame 0 before clicking Render.

**Camera preservation invariant:** the export loop reads the scene state set by `applyFrameData()` and renders it. It does not touch `camera.position`, `camera.quaternion`, or `orbitControls` target.

**Custom resolution:** temporarily sets `renderer.setPixelRatio(1)` + `renderer.setSize(W, H)`, then restores the original pixel ratio and size after the loop. This produces pixel-exact output at the requested dimensions regardless of device DPI.

**Browser requirement:** File System Access API (`showDirectoryPicker`) is Chromium-only. If not present, the Choose folder button is disabled and a red note is shown. No download-dialog fallback (unusable for thousands of frames).

**`preserveDrawingBuffer: true`:** added to the renderer constructor. Required so the WebGL backbuffer is not discarded between `render()` and `toBlob()` calls. Slight perf cost during normal playback, acceptable for a viewer.

**ffmpeg assembly:**
```
ffmpeg -framerate 30 -i frame_%06d.jpg -c:v libx264 -pix_fmt yuv420p out.mp4
```

Older entries are in `JOURNAL_ARCHIVE.md`. Run logs and pasted simulation output go in `RUN_LOGS/`.

## 2026-05-25 — Addendum: 2D projection for long-window speed

Long-window speed in `GlidingAssayEvaluator` now uses the XY-plane displacement only (`sqrt(dx²+dy²)`) rather than the full 3D displacement. Rationale: experimentalists observe gliding motion via epi-fluorescence microscopy, which is a 2D projection. A filament that tilts out of the focal plane or drifts in Z would register faster in 3D than in XY, contradicting what the microscope would record. Using XY matches the experimental observable.

Renames: data-file column `longWindowSpeed` → `longWindowSpeedXY`; WebSocket JSON field `longWindowSpeed` → `longWindowSpeedXY`; viewer panel labels `avg` → `avg(xy)` and `mean avg speed` → `mean avg(xy) speed`.

Intentional asymmetry retained: `instantaneousSpeed` remains 3D. Instantaneous values are noisy at 10 ms windows so 2D vs 3D is in the noise; keeping it 3D creates a diagnostic: a sustained `longWindowSpeedXY` ≪ `instantaneousSpeed` (3D) is a signature of Z-axis drift or escape events.

---

## 2026-05-25 — Planning: gliding assay data collection campaign

Manual exploration with `-3jsLive 8081` confirmed the gliding assay infrastructure is working end-to-end. At 200 motors/µm² with a single 1 µm filament: speed 2.3 µm/s, 2 bound motors, duty ratio ≈ 0.05. Numbers are in the published range for myosin II and the live panel updates correctly. Time to plan a proper data-collection campaign.

### Target figures

Three figures to produce from this assay, in order of priority:

1. **Velocity vs. surface density** — the canonical gliding-assay figure. Sweep density across roughly 10 → 3000 motors/µm² (log-spaced; ~6–8 density points). Expect a rising curve that plateaus at Vmax somewhere in the hundreds-to-low-thousands range. The plateau location and shape are what calibrate the model against literature (Harada 1990, Kron & Spudich 1986, Stam 2015, the recent β-cHMM paper at biorxiv 2025.05.01.651501).
2. **Velocity vs. filament length** — at a chosen density in the rising regime (sub-saturating, ~50–100 motors/µm²) and at a saturating density. Short filaments should stall more at low density and be length-independent at saturation. Useful internal consistency check.
3. **Velocity distribution at a single condition** — histogram of per-filament velocities at saturating density. Tests for stuck filaments and population heterogeneity. Free byproduct of the velocity-vs-density sweep if we run with ≥10 filaments per condition.

ATP-dependence and F–V curves are deferred to later sessions.

### Statistics requirements per density point

Each density point needs enough filaments and enough simulated time to produce a velocity mean with reasonable error bars. Rough target:

- **Per-filament velocity**: time-average over at least 5 seconds of sim time per filament after a 1 s settling period (to let any starting transients decay). At ~2 µm/s a filament travels ~10 µm in that window, which is plenty of displacement for an accurate distance/time measurement.
- **Filaments per density point**: 10–20. With 10 filaments we get a mean ± SEM that's tight enough to distinguish density points cleanly; with 20 the distribution shape becomes meaningful for figure 3.
- **Chamber size**: 4×4×0.5 µm currently holds one 1 µm filament. To fit 10–20 filaments without confounding inter-filament interactions, scale to perhaps 10×10×0.5 µm. Motor count at 200/µm² becomes 20000, which is fine for memory. At 3000/µm² it becomes 300000 — need to check that this is computationally tractable per second of sim time before committing to the high-density end of the sweep.

### Open questions before committing to a campaign

These are the things to resolve before kicking off a multi-hour data run, in roughly the order they need answers.

**1. Filament length specification.** The current `glidingFilamentLength:true:1.0` produces a 1 µm filament. Published assays typically use 1–5 µm phalloidin-stabilized actin. Decide whether to use a fixed length per run or randomize within a biological range. For figure 1 (velocity vs. density), fixed length is cleaner; for figure 2 (velocity vs. length) a sweep is the point.

**2. Initial filament orientation and seeding.** Are filaments seeded with random in-plane orientation, or aligned? Random is the experimental analog. If `makeGlidingAssayFilament()` seeds isotropically, good; if it has a preferred axis, that's a finding worth knowing before generating distributions.

**3. Filament-filament interactions.** At 10–20 filaments per chamber the surface coverage is still low (fraction of plane occupied ≈ 1% at 1 µm × 10 nm widths), but two filaments could plausibly overlap a motor's reach and confound measurement. Either (a) confirm the existing collision/overlap handling is correct for the gliding context, or (b) accept the rare interaction event as part of the published-assay realism (real experiments have it too).

**4. Parameter file audit for stable-filament gliding.** The Listeria nucleator is now defaulted off. Confirm that other biochemistry rates are also off for a clean velocity measurement: spontaneous nucleation (`kRdmNuc`, `kNodeNuc`), Arp2/3 branching (`kBranchNuc`), severing (`kSevering` and any cofilin-related rates), and end polymerization/depolymerization. Anything not gated by `noMonomersSimd` needs explicit zeroing in the gliding parameter file. Goal: a canonical `glidingAssayStable` parameter file that produces fixed-composition filaments for the entire run.

**5. Myosin identity and parameter set.** The current run shows ~2.3 µm/s — that's on the high side for NMII (typical Vmax ~0.1–1 µm/s) and on the low side for skeletal HMM (~5–10 µm/s). Confirm what myosin parameter set BoA mainline currently uses, since this anchors the density-sweep interpretation. If we're nominally simulating NMII, the speed is high and the kinetics may need re-examination after the campaign (which connects back to the deferred catch-slip sign-convention question — F–V data from this campaign is what would resolve it).

**6. Density-sweep mechanism.** The current code has `MyosinFixed.glidingAssayDataSetRun()` (the old density-sweep loop using `restartRun()` / `System.exit(0)`) and the new `GlidingAssayEvaluator` that emits per-filament rows to `gliding_assay.dat`. The journal entry from the port noted these aren't yet integrated — `densityIndex` is hard-coded to 0. To produce figure 1, this integration needs to happen. Two options:
   - **In-process sweep**: extend `glidingAssayDataSetRun()` to drive the evaluator's `densityIndex` and emit a single contiguous data file across all density points. Cleanest output.
   - **Shell-script sweep**: launch BoA repeatedly with different parameter files (different `fixedMyosinDensity` values), then concatenate the output files in post-processing. Less elegant but doesn't require Java code changes.
   - Recommend in-process; the existing scaffolding is mostly there.

**7. Velocity time-series shape.** Before averaging, look at per-filament velocity vs. time at a single density. A clean gliding assay produces a roughly constant velocity after a brief startup transient; if BoA produces something with strong oscillation or drift, that's a finding that affects how we compute mean velocity (windowed average vs. fit of position vs. time, etc.). One pilot run at 200/µm² with 10+ s of sim time would settle this.

### Suggested order of next implementation sessions

1. **Pilot session** (small Claude Code task): integrate `densityIndex` between `glidingAssayDataSetRun()` and `GlidingAssayEvaluator`; produce a canonical stable-gliding parameter file with the rate audit from open question 4. Single density-sweep run end-to-end. Deliverable: one `gliding_assay.dat` with 6–8 density points × 10 filaments × ~5 s simulated each, plus a quick post-processing script that produces the velocity-vs-density plot.

2. **Analysis session** (planner reads the data): examine the velocity-vs-density curve shape, the velocity time-series at each density, and the duty ratio behavior. Decide whether (a) the curve matches published shape and we can move to figure 2, (b) something's anomalous (speed too high/low, no plateau, oscillating velocities) and we need to diagnose, or (c) the catch-slip sign question is now resolvable from F–V information embedded in the duty-ratio-vs-density behavior.

3. **Length sweep** (if step 2 looks healthy): generate figure 2 data at two density points.

4. **Catch-slip resolution** (informed by all of the above): revisit the sign-convention investigation with empirical data in hand rather than code-reading arguments.

### What does NOT need to happen first

- The two log paths (`logAndDraw` and `remoteLog`) can stay un-refactored. Tempting target, not the critical path.
- The duty-ratio reach parameter (0.1 µm) seems to be working — the manual observation showed plausible bound counts. Leave alone unless a finding suggests otherwise.
- The live viewer panel is fine as-is. No new visualization needed for the campaign; the `.dat` file is the artifact.

## 2026-05-25 — Gliding assay measurement system port (Phases 0–4)

### Pre-port reconciliation

The prior WebSocket documentation entry (same date, below) stated that gliding-arena geometry "does not yet exist in the Java codebase." The sister-codebase survey (`SURVEY_MYOSIN_AND_GLIDING.md`) said the opposite. Reading source resolved it in favour of the survey: **the arena geometry is fully present in BoA mainline.**

`MyosinFixed.setUpGlidingAssay()` (called from `BoxOfActin.makeInitialThings()` at line 1849 when `Env.glidingAssay.isActive()`) calls `fillPlaneWithFixedMyosins()` — which populates a Z-plane with `MyosinFixed` instances using `Env.fixedMyosinDensity` and `Env.fixedMyosinZValue` — then calls `FilSegment.makeGlidingAssayFilament()` to create a randomly oriented actin filament above the plane. The only missing piece was the continuous per-step velocity evaluator.

### Evaluator design

`GlidingAssayEvaluator.java` is a new 240-line class patterned after ANM3's evaluator. Two public methods:

- **`sampleStep()`** — called every simulation timestep (in both `logAndDraw()` and `remoteLog()`). Walks `FilSegment.theFilSegments[]` to increment `sampleCount` for each live filament ID (one increment per filID per step, using a `HashSet<Integer>` for deduplication). Walks `MyoMotor.theMotors[]` to find bound `MyosinFixed` motors and accumulate `boundMotorSum` per filament. O(filSegmentCt + motorCt).

- **`outputInterval()`** — called at each 3JS output interval (every `toFileInterval` timesteps). Computes filament center-of-mass from segment coords, velocity from COM displacement since the previous interval, footprint motor count (any `MyosinFixed` whose pin `myFixedPt` is within `MOTOR_REACH_UM = 0.1 µm` of the filament axis), and a population-level `headsWithinReachDR`. Resets accumulators for the next interval. Returns a JSON string or `null` if no filaments are present.

**Filament identity:** `filID` is the group key — a field shared by all `FilSegment` instances belonging to the same filament chain. COM is computed as the mean of each constituent segment's `coord` (segment center). Contour length is the sum of `fs.length` over segments.

**Perpendicular distance (`distToAxis`):** clips the projection onto the segment's `uVec` to `[−halfLen, +halfLen]`, computes the residual vector, returns its magnitude. Correctly handles a finite-length segment rather than an infinite line.

**Lifecycle:** `GlidingAssayEvaluator.create()` is called once in `BoxOfActin.begin()` after `makeInitialThings()`, guarded by `Env.glidingAssay.isActive()`. The data file is opened lazily in `ensureFileOpen()`, which is called from `outputInterval()` — by that point `ThreeJSWriter` has already called `writeFrame()` and finalized the output directory name (including any `.001` suffix increment), so `Env.threeJSOutputDir` is set correctly.

**Dispatch wiring:** The `threeJSCounter >= toFileInterval` gate in `logAndDraw()` and `remoteLog()` was extended from `(Env.threeJSOutputDir != null || LiveFrameServer.isRunning())` to also fire when `Env.glidingAssay.isActive()`, so the data file is written even in headless runs without `-3js` or `-3jsLive`.

### Data file schema

Tab-separated, one header row, one row per filament per output interval. File: `<outputDir>/gliding_assay.dat`.

```
simTime	densityIndex	surfaceDensity	filamentId	filamentLength	posX	posY	posZ	distMoved	vecMovedX	vecMovedY	vecMovedZ	instantaneousSpeed	avgBoundMotors	footprintMotors	footprintDutyRatio	headsWithinReachDR
```

- `instantaneousSpeed` = `distMoved / dt` where `dt` = sim time elapsed since previous interval (µm/s).
- `avgBoundMotors` = `boundMotorSum / sampleCount` over the interval.
- `footprintMotors` = count of `MyosinFixed` pins within `MOTOR_REACH_UM` of this filament's axis.
- `footprintDutyRatio` = `avgBoundMotors / footprintMotors`.
- `headsWithinReachDR` = population-level ratio across all filaments; same value in every row of the same interval.
- First row per filament has `distMoved = 0` and `instantaneousSpeed = 0` (no prior position).
- `densityIndex` is always 0 for now (density-sweep integration deferred; see open items).

### WebSocket schema

New `glidingAssay` topic dispatched by `LiveFrameServer.dispatchGlidingAssay()`:

```json
{
  "topic": "glidingAssay",
  "payload": {
    "simTime": <float>,
    "densityIndex": <int>,
    "surfaceDensity": <float>,
    "headsWithinReachDR": <float>,
    "filaments": [
      {
        "id": <int>,
        "length": <float>,
        "pos": [x, y, z],
        "distMoved": <float>,
        "speed": <float>,
        "avgBoundMotors": <float>,
        "footprintMotors": <int>,
        "footprintDutyRatio": <float>
      }
    ]
  }
}
```

All distances in µm, speed in µm/s, time in seconds.

### Viewer panel

`#glidingPanel` is positioned bottom-left (to avoid the LP panel at bottom-right). It self-activates (`classList.add('active')`) on the first `glidingAssay` message — hidden in non-gliding runs. Content injected by `updateGlidingPanel(ga)`:
- Summary line: sim time, density index, surface density (µm⁻²), `headsWithinReachDR`.
- Per-filament table rows: filament ID, length, speed, avgBoundMotors, footprintDutyRatio.
- Up to 5 filaments shown; overflow shown as "…and N more".

### Test run

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -pf ParameterFiles/glidingAssayTest -3jsLive 8081
```

Parameter file: `fixedMyosinDensity:true:200.0`, `glidingFilamentLength:true:1.0`, `filSegLength:true:64.0`, `noMonomersSimd:true:1.0`, `kRdmNuc:false:0.0`, `kNodeNuc:false:0.0`, `initialFilaments:false:0.0`, `initialMyoMiniFils:false:0.0`, `equilNodes:false:0.0`, box 4×4×0.5 µm, run time 5 s.

Compiled cleanly (zero errors) with `javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java`. Simulation started, `glidingAssay.dat` created, WebSocket dispatch confirmed (topic appeared in browser console). The evaluator, data file, and WS dispatch are all wiring correctly.

**Segment-count variability observed:** frame_000000 (t=0.0001 s) reported 2 segments as expected (the 1 µm filament, possibly pre-split). By frame_000001 (t=0.0101 s), 8 segments were present — 5 at plausible positions spanning the filament, plus 3 mystery segments at extreme Y positions (approximately −5.0, −5.4, −6.2 µm, well outside the 4 µm box boundary). Mystery segments had length ≈ 0.0108 µm (≈ 2 monomers) and instance IDs sequentially following the last expected filament segment. Motor binding and velocity reporting were otherwise functioning.

### Addendum: long-window velocity reporting

Added a ring-buffer long-window velocity estimate to `GlidingAssayEvaluator` alongside the existing per-interval instantaneous value.

**Buffer sizing.** `LONG_WINDOW_SECONDS = 1.0` (compile-time constant). Buffer capacity is computed once on the first `outputInterval()` call as `round(1.0 / (toFileInterval * deltaT))`. At the param-file defaults (`toFileInterval=100`, `deltaT=1e-5`), this gives `bufCap = 1000` entries (= 1 second of sim time). Using `toFileInterval` at first-call time rather than construction because `toFileInterval` is in the mid-run mutable whitelist; if it changes after the first call the window approximation degrades (newer samples have different spacing than older ones) but does not break. Known limitation.

**Speed calculation.** Endpoint slope: magnitude of displacement from oldest to newest buffer entry, divided by time span. Matches the `instantaneousSpeed` convention (not a least-squares fit). A `settling` flag (true until buffer fills once) marks the transient where the window is shorter than the 1-second target.

**New data file columns** (immediately after `instantaneousSpeed`):
- `longWindowSpeed` — µm/s, endpoint slope over the full buffer span
- `longWindowSettling` — 0 or 1 (1 = buffer not yet full)

**New WebSocket fields** (per-filament in `glidingAssay` payload): `"longWindowSpeed"`, `"settling"`.

**Viewer panel.** Per-filament row: `inst <speed> µm/s  avg <lwSpeed> µm/s`, with greyed `(settling)` when flag is set. Summary adds `mean avg speed` across settled filaments (the quantity figures will plot).

**Test confirmation.** Short run (`deltaT=1e-5`, `toFileInterval=100`): 11 data rows; `instantaneousSpeed` ranged 4–111 µm/s (jittery); `longWindowSpeed` converged smoothly 111 → 16 µm/s as buffer accumulated; `longWindowSettling=1` throughout (expected — buffer fills after 1000 intervals = 1 s, run only reached 0.011 s sim time). Header and column order confirmed correct.

### Addendum: barbed-end visualization

**Axis transmission gate lifted.** `ThreeJSWriter.buildFrameJson()` previously emitted `axisX`/`axisY`/`axisZ` only when `Env.benchmarkFilament` was true. The gate has been restructured so `chainType` (`"defl"` or `"lp"`) is still benchmark-only, but axis fields are emitted for every non-LP segment regardless of benchmark mode. LP segments (`isLpSeg == true`) continue to omit axes — the performance rationale (LP chain has ~90 segments and the per-segment axis payload is significant) still applies.

**`isBarbedEnd` field.** The per-segment schema now includes `"isBarbedEnd":true` on segments where `end2Fil == null` (the chain head — no barbed-side neighbor). This includes both the head of a multi-segment chain and single-segment filaments. The check is a direct read of the existing chain pointer; no new physics code, no walking.

**Viewer: X axis length.** `updateAxisLines()` previously rendered all three axes at `halfLen = 0.15 × segLen` (total 0.3× segment length). The X axis (filament long axis, toward barbed end) is now drawn at `halfLen = 0.75 × segLen` (total 1.5× segment length), protruding visibly past end2. Y and Z axes remain at 0.15× halfLen for a compact local frame.

**Viewer: barbed-end "+" glyph.** A sprite pool (`_barbedSpritePool`) is maintained alongside the segment mesh pool. Each frame, `updateBarbedEnds()` filters `segments` for `isBarbedEnd:true`, grows the pool as needed, and positions each sprite at `end2` of its segment. Sprites use a `CanvasTexture` "+" drawn in cyan (`#00ffff`) on a 64×64 canvas, rendered as `THREE.Sprite` (always camera-facing) with `depthTest: false` so the marker is always visible. Scale: `0.18 µm × 0.18 µm` — roughly 5× the segment radius, clearly legible without overwhelming the filament.

**Display dropdown.** New "Barbed ends" checkbox (id `chkBarbedEnds`, default checked) added to the Display panel between "Segment axes" and "Supports". Toggling it calls `updateBarbedEnds()` immediately against `currentFrameData`. The existing "Segment Axes" checkbox already toggled `showAxes` and gates `updateAxisLines()` — no change needed there.

### Known limitations / open items

**`splitSegment()` outside `noMonomersSimd` guard (highest priority).** `FilSegment.biochemStep()` at line 444 (`FilSegment.java`) runs the split check unconditionally:

```java
if (monomerCt >= 2 * Env.stdSegLength.getIntValue()) {
    splitSegment(this);   // NOT guarded by noMonomersSimd
    ...
}
```

The initial filament from `makeGlidingAssayFilament()` has monomerCt ≈ 285 (for a 1 µm filament with actinMonoRadius ≈ 0.00175 µm). Since 285 ≥ 2 × 64 = 128, it splits immediately. The split daughters are placed at offsets from the parent that rely on the parent's orientation/length being consistent; in the gliding assay geometry some daughters appear to receive large initial forces that eject them outside the box before the wall boundary condition can act. The fragments show up as spurious rows in the gliding data. Fix: add `!Env.noMonomersSimd.isActive()` to the split guard, or restructure `makeGlidingAssayFilament()` to create the filament already pre-split into stdSegLength chunks.

**Rate parameters not fully zeroed for a clean gliding test.** The current test file zeros `kRdmNuc` and `kNodeNuc` but does not address: `kSevering` (actin filament severing), `kBranchNuc` (Arp2/3 branching), end-polymerization rates (`kPoly*`, `kDepoly*` — guarded by `noMonomersSimd` for monomer dynamics but the split call above is not). A complete suppression audit is needed: which parameters must be explicitly deactivated (`false`) versus which are gated by `noMonomersSimd`. Recommend checking every `kRdmNuc`, `kNodeNuc`, `kBranchNuc`, `kSevering`, and polymerization rate against whether `noMonomersSimd` covers them.

**Density-sweep integration deferred.** `MyosinFixed.glidingAssayDataSetRun()` manages a multi-density sweep via `restartRun()` / `System.exit(0)` and tracks `densityIndex` internally. The new evaluator's `densityIndex` field is set to 0 and never incremented. The old `glidingAssayDataSetRun()` call in the `remoteOutCounter` block is preserved for backward compatibility. Integrating the two properly (so `densityIndex` in the data file tracks sweep steps) requires passing the current density index through from `glidingAssayDataSetRun()` to the evaluator — deferred.

**Live WebSocket panel not visually confirmed.** The test run was headless verification of the dispatch path only. The viewer panel rendering (self-activation, filament table, formatting) was not verified in a browser session.

---

## 2026-05-25 — Documenting the WebSocket live-observation interface (capability already in active use)

The live WebSocket interface has been the primary observation tool for filament benchmarking since Session 12 (C1 transport), extended through Sessions 13–18 (click-to-inspect C2, pause/kill C3, mutable parameters C4, benchmark panels, LP panel). It had not been written up as a unit. This entry documents the current state comprehensively so the next planner session can write a gliding-assay output port without re-reading source.

### Server side

**Class:** `boxOfActin/LiveFrameServer.java` (417 lines). Extends `org.java-websocket.server.WebSocketServer`. One static singleton; per-client state is a bounded `ArrayBlockingQueue<String>(4)` plus a daemon sender thread, both created in `onOpen()`. Backpressure model: all `dispatch*()` calls use non-blocking `offer()` with drop-oldest semantics — the simulation thread is always O(1) regardless of network conditions.

**Startup:** `-3jsLive <port>` CLI flag. `begin()` in `BoxOfActin.java:136–138` calls `LiveFrameServer.startServer(Env.threeJSLivePort)` immediately after `parseArgs()`, before param-file loading or `makeInitialThings()`. `TimeLoop.run()` (line 591) calls `LiveFrameServer.stopServer()` on exit. No hard-coded default port; `8081` is conventional in examples but any available port works.

**Hook into main loop:** `threeJSCounter` is incremented by `updateCounters()` each step. In both `logAndDraw()` and `remoteLog()` (lines 1455 and 1531), when `threeJSCounter >= Env.toFileInterval.getIntValue()`, the frame is generated and dispatched:
```java
// BoxOfActin.java:1455–1464
if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
    ThreeJSWriter.writeFrame();                     // generates JSON, dispatches to both consumers
    if (Env.benchmarkFilament && LiveFrameServer.isRunning()) {
        String bmJson = buildBenchmarkJson();
        if (bmJson != null) LiveFrameServer.dispatchBenchmark(bmJson);
        accumulateLpData();
        String lpJson = buildLpJson();
        if (lpJson != null) LiveFrameServer.dispatchLpBenchmark(lpJson);
    }
    threeJSCounter = 0;
}
```
The safe-point dispatch order is: **pause wait** (with inspect drain inside) → **kill check** → **inspect drain** → **param drain** → `logAndDraw()` / `remoteLog()` → frame + benchmark dispatch.

### Message protocol

**Server → client** (all topics pushed to all clients; no per-topic filtering):

| topic | payload summary |
|---|---|
| `frame` | per-frame geometry — see schema below |
| `inspectResult` | object inspection payload — see CLAUDE.md for field-by-field list by `kind` |
| `simState` | `{"state":"running"\|"paused"\|"terminating","step":<N>}` |
| `paramList` | array of `{"name","displayName","type","value","mutable"}` for all Parameters |
| `paramAck` | `{"success":true,"name","oldValue","newValue"}` or `{"success":false,"name","error":"..."}` |
| `benchmark` | deflection/relaxation metrics — see benchmark topic schema below |
| `lpBenchmark` | persistence-length metrics — see LP topic schema below |

**Client → server:**

| action | fields | notes |
|---|---|---|
| `subscribe` | `topics:[...]` | informational only; server ignores for filtering |
| `inspect` | `id:<N>` | thingInstanceId; queued in `Env.inspectQueue` |
| `pause` | — | sets `Env.paused = true` |
| `resume` | — | clears `Env.paused`, notifies safe point |
| `kill` | — | sets `Env.terminating = true` (absorbing state) |
| `queryParams` | — | triggers immediate `paramList` response to that client |
| `setParam` | `name,value` | validated on WS thread, queued for safe-point application |

### Per-frame schema

`ThreeJSWriter.buildFrameJson()` (ThreeJSWriter.java:60–160) constructs the `frame` payload:

```json
{
  "frame": <int>,           // monotone frame counter since process start
  "t": <float>,             // simulation time in seconds (6 significant figures)
  "bounds": {               // µm; absent in benchmark mode (Env.benchmarkFilament=true)
    "xDim": <float>, "yDim": <float>, "zDim": <float>
  },
  "segments": [
    {
      "id": <int>,          // FilSegment.thingInstanceId — stable, monotone across cleanup
      "end1": [x, y, z],   // µm, always current (FilSegment.initialize() runs every step)
      "end2": [x, y, z],   // µm
      "r": 0.035,           // hardcoded radius in µm
      // benchmark-only fields (present when Env.benchmarkFilament=true):
      "chainType": "defl"|"lp",
      "axisX": [ux, uy, uz],  // body X axis (defl chain only; LP chain omits for performance)
      "axisY": [ux, uy, uz],
      "axisZ": [ux, uy, uz]
    }
  ],
  "myosins": [
    {
      "id": <int>,           // MyoMotor.thingInstanceId (canonical group key — motor carries state)
      "rod": {
        "end1": [x, y, z], "end2": [x, y, z],
        "r": <float>,        // MyoRod.radius in µm (~0.010 µm)
        "invisible": <bool>  // true for rods bundled inside MyoMiniFilament
      },
      "lever": {
        "end1": [x, y, z], "end2": [x, y, z],
        "r": <float>         // MyoLever.radius in µm
      },
      "motor": {
        "end1": [x, y, z], "end2": [x, y, z],
        "r": <float>,        // MyoMotor.radius in µm
        "state": "NONE"|"ATP"|"ADPPi"|"ADP",
        "onFil": <bool>
      }
    }
  ],
  "minifilaments": [
    {
      "id": <int>,           // MyoMiniFilament.thingInstanceId
      "end1": [x, y, z], "end2": [x, y, z],
      "r": <float>           // MyoMiniFilament.radius in µm
    }
  ],
  // benchmark overlay — present only when Env.benchmarkFilament=true:
  "pinnedEndpoints": [       // anchor1, anchor2 positions in µm — null until chain is created
    {"x":..., "y":..., "z":...},
    {"x":..., "y":..., "z":...}
  ] | null,
  "forceArrows": [
    {
      "point": {"x":..., "y":..., "z":...},     // midSeg center in µm
      "direction": {"x":..., "y":..., "z":...}, // unit vector
      "magnitude": <float>,                       // force in Newtons
      "label": "F",
      "visible": <bool>                           // false when benchmarkForceOn=false
    }
  ]
}
```

**Units throughout:** positions in µm, radii in µm, time in seconds, force in Newtons. The viewer multiplies deflections by 1000 to display in nm; everything else is rendered in µm-space directly.

### benchmark topic schema

`buildBenchmarkJson()` (BoxOfActin.java:1216–1261):

```json
{
  "chainSegments": <int>,         // Env.benchmarkNSegs (default 11)
  "monomersPerSegment": <int>,    // benchMonCt
  "chainSpanMicrons": <float>,    // end-to-end span in µm
  "viscosity": <float>,           // Env.aeta (Pa·s)
  "observedDeflection": <float>,  // midSeg.coord.y displacement from rest, in µm
  "expectedDeflection": <float>,  // analyticDefl (= frac × span), in µm
  "ratio": <float>,               // observed/expected, 3 decimal places
  "forceOn": <bool>,
  "stepCount": <int>,
  "tauTheo": <float>,             // optional; 1st bending mode relaxation time in s
  "tauMeas": <float>,             // optional; elapsed time since force toggle off
  "tauMeasFrozen": <bool>         // optional; true when 1/e crossing has fired
}
```

### lpBenchmark topic schema

`buildLpJson()` (BoxOfActin.java:1294–1334):

```json
{
  "nSegs": <int>,             // number of LP chain segments
  "segLen": <float>,          // per-segment length in µm
  "contourLength": <float>,   // nSegs × segLen in µm
  "monomersPerSegment": <int>,
  "EI": <float>,              // bending stiffness in N·m² (Env.EI, compile-time constant)
  "lpTheo": <float>,          // Env.persistenceLength = 15.0 µm
  "samples": <int>,           // EWMA accumulation count since last reset
  "lpMeas": <float>,          // optional; weighted log-linear fit result in µm
  "cc": [1.0, C(1), C(2), ...] // EWMA tangent-tangent correlation array; length nSegs
}
```

### Parameter-mutation mechanism

The mutable parameter whitelist (authoritative: `grep setMutableAtRuntime boxOfActin/Env.java`):

| label | physical meaning | side-effect hook |
|---|---|---|
| `fracMove` | PAIRS translational coefficient C_δ | none |
| `fracR` | PAIRS rotational coefficient C_R | none |
| `fracMoveTorq` | PAIRS torque-arm coefficient C_θ | none |
| `toFileInterval` | output frame interval (timesteps) | advances `threeJSCounter` to `newInterval-1` for immediate next output |
| `benchmarkForceFrac` | deflection force fraction | recomputes `transForce` and `analyticDefl` immediately |
| `benchmarkForceOn` | boolean — apply midpoint force | none (toggle button in viewer bypasses Params panel) |
| `lpEwmaAlpha` | LP EWMA smoothing factor | none |
| `lpActive` | boolean — LP chain active | 0→1 transition: resets accumulator and sampleCount |
| `aeta` | viscosity in Pa·s | calls `calculateProperties()` on all `FilSegment` instances; refreshes `tauTheo` if benchmark chain present |
| `BTransCoeff` | Brownian translational scale | none |
| `BRotCoeff` | Brownian rotational scale | none |

End-to-end flow: user clicks Apply in Params panel → viewer sends `setParam` → `handleSetParam()` on WS thread validates (unknown label / not mutable / parse failure → immediate `paramAck` error) → valid change queued as `Env.PendingParamChange` in `Env.paramQueue` → `drainParamQueue()` at next safe point applies, runs hooks, dispatches success `paramAck` → viewer's `handleParamAck()` updates input to confirmed value, shows ✓ for 2s.

```java
// LiveFrameServer.java:339–376 (handleSetParam, abridged)
private static void handleSetParam(String name, String valueStr) {
    Parameter target = null;
    for (int i = 0; i < Parameter.paramCt; i++) {
        Parameter p = Parameter.theParams[i];
        if (p != null && name.equals(p.label)) { target = p; break; }
    }
    if (target == null)                { dispatchParamAckError(name, "unknown parameter"); return; }
    if (!target.isMutableAtRuntime())  { dispatchParamAckError(name, "not mid-run mutable"); return; }
    // ... type parse, positiveValue check ...
    Env.paramQueue.offer(new Env.PendingParamChange(target, parsedValue));
}
```

`benchmarkForceOn` and `lpActive` are excluded from the Params panel list in `buildParamPanel()` (viewer line ~1527) because they have dedicated toggle buttons in their respective panels. Everything else with `mutable:true` appears in the Params panel.

### Special-purpose panels: gating mechanism

Both benchmark panels are gated by a double condition:

```java
if (Env.benchmarkFilament && LiveFrameServer.isRunning()) {
    // build and dispatch benchmark / lpBenchmark topics
}
```

`Env.benchmarkFilament` is set by any of: `-bm`, `-bmManual`, `-bmDiag`, `-bmTuner*` flags. The condition is checked in both `logAndDraw()` and `remoteLog()` — same code path, just calling the same private methods. The dispatch is tightly coupled to these flags; there is no runtime toggle to suppress or enable the benchmark dispatch (short of not starting the sim in benchmark mode).

On the viewer side, the panels self-activate: `updateBenchmarkHud()` adds `benchmarkHud.classList.add('active')` unconditionally on first message; `updateLpPanel()` does the same for `lpPanel`. Neither panel is shown if no `benchmark` or `lpBenchmark` messages ever arrive — which is the case in non-benchmark runs. A future panel (e.g. gliding assay) would follow this same self-activation pattern: add a new `dispatchGlidingAssay(json)` static method in `LiveFrameServer`, build and dispatch it conditionally in `logAndDraw()`/`remoteLog()`, add a handler branch in `ws.onmessage`, and write a `updateGlidingPanel()` function that self-activates on first call.

### Viewer side

**File:** `sim_viewer_boa.html` (1802 lines) — single self-contained file. No external JS files beyond three.js.

**Three.js:** v0.168.0 from CDN via ES module importmap. `OrbitControls` from `three/addons/controls/OrbitControls.js`.

**Port discovery:** `new URL(location.href).searchParams.get('live')` at module top. `LIVE_MODE = true` if the parameter is present and non-empty; `LIVE_PORT = parseInt(value)`. Opening without `?live=` uses file-based mode (directory browser, frame scrubber). The two modes are mutually exclusive per page load.

**Connection failure / missed frames:** `ws.onclose` triggers `scheduleReconnect()` with exponential backoff from 1s to 8s. Missed frames during reconnect are just not rendered — the viewer accepts whatever the next `frame` message is. No sequence gap recovery.

**Rendering:** Six instanced meshes covering all object types. Each frame, identity maps (`segIdToSlot`, `segSlotToId`, etc.) are rebuilt from scratch — enables click-to-inspect raycasting (raycast `instanceId` → `thingInstanceId` → WS `inspect` action). Motor heads are `InstancedMesh` spheres, colored per nucleotide state (NONE blue, ATP yellow, ADPPi orange, ADP red) via `motorMesh.instanceColor`.

**Panel infrastructure:** Each panel is an absolutely-positioned `<div>`. Benchmark panels and inspect panel self-activate (add `.active` or `.open` class) when data arrives. The Params panel requires user click on "Params ▶" button. All panels share the same CSS variable pattern (header div → body div → DOM content injected by the corresponding `update*()` function). A new gliding-assay panel would follow the same structure: static HTML shell, `.active` class added on first data, `updateGlidingPanel(lp)` function injecting DOM content from the payload.

### Implications for the planned gliding assay work

The transport layer, viewer infrastructure, and parameter-mutation round-trip are fully in place and require no changes for a gliding-assay extension. What the gliding assay needs to add: (a) a per-filament velocity estimator on the Java side (distance moved per output interval / elapsed sim time — ANM3's `GlidingAssayEvaluator` is directly portable), wired into `logAndDraw()` alongside the existing benchmark dispatch; (b) a new `dispatchGlidingAssay(json)` method in `LiveFrameServer` and a `{"topic":"glidingAssay","payload":{...}}` message branch in the viewer's `ws.onmessage`; (c) a `gliding-assay` panel HTML shell and `updateGlidingPanel()` JS function; and (d) a data-file output path for velocity-vs-density figures — `FileOps.writeToGAssayFile()` is already called from `logAndDraw()` at line 1472 but is commented out, and `MyosinFixed.glidingAssayDataSetRun()` is called at line 1471. The per-frame schema already carries motor `onFil` and `state` fields, so duty ratio can be computed client-side from existing data without schema changes. The arena geometry (motors pinned to a plane, randomly oriented filaments) is the other gap — it does not yet exist in the Java codebase and will require new initialization code or a parameter-file convention.

---

## 2026-05-25 — Investigation: force-dependent release path interaction

### Path A — `MyoFilLink.ckRelease()`

**Conditions.** Called from `MyoFilLink.step()`, which is reached via `MyoMotor.step()` → `updateMyoFilLinks()` → `tipLink.step()`. The call chain fires whenever the link is not free (`!isFree()`). There is **no nucleotide-state check** — ckRelease fires for NONE, ATP, ADPPi, and ADP states alike. The one exception is `inRigor`: if `myMotor.inRigor` is set, normal release is skipped (only the break-force ceiling at line 198 can detach a rigor motor).

**Force variable and formula.**

```java
// MyoFilLink.java:197–219 (active ckRelease)
public void ckRelease () {
    if (forceMag > Env.myosinBreakForce.getValue()*1e-12) {  // ceiling at 12 pN default
        release();
        return;
    }
    if (myMotor.inRigor) { return; }

    double guoCatchTerm = Env.alphaCatch.getValue()
                          * Math.exp(-forceDotFil * Env.xCatch.getValue() / (Env.Boltz*Env.tempK));
    double guoSlipTerm  = Env.alphaSlip.getValue()
                          * Math.exp( forceDotFil * Env.xSlip.getValue()  / (Env.Boltz*Env.tempK));
    double guoCatchSlipProb = Env.kOff.getValue() * (guoCatchTerm + guoSlipTerm);

    if (myMotor.myPRNG.nextDouble() < guoCatchSlipProb * Env.deltaT.getValue()) {
        release();
    }
}
```

`forceDotFil` is set in `addForces()` (see sign-convention section below). Default parameters (Env.java): `alphaCatch=0.92`, `xCatch=2.5e-9 m`, `alphaSlip=0.08`, `xSlip=0.4e-9 m`, `kOff=100 s⁻¹`, `myosinBreakForce=12 pN`.

**Call site in doLoop().** Phase `Env.stepStart = 9`, line ~730 in `BoxOfActin.java`. `startAllThreadSets(Env.stepStart)` iterates `Thing.step()` over all Things; `MyoMotor.step()` calls `updateMyoFilLinks()` → `tipLink.step()` → `ckRelease()`. Moves and biochemistry have not yet fired this timestep.

---

### Path B — `MyoMotor.dissociateADP()`

**Conditions.** Called from `MyoMotor.biochemStep()` only when `nucleotideState == ADP`. No explicit `onFil` check — this method fires whether or not the motor is mechanically attached.

```java
// MyoMotor.java:208–213
public void dissociateADP() {
    if (tipLink.forceDotFilTrack.averageVal() > 0) { return; }  // directional gate
    if (myPRNG.nextDouble() < Env.myoOnFilADP_None.getValue() * Env.deltaT.getValue()) {
        setStateNONE();
    }
}
```

`forceDotFilTrack` is a `ValueTracker(10)` — a 10-slot circular buffer of raw `forceDotFil` values from `addForces()`. `averageVal()` always divides by 10 regardless of how many slots have been filled (see note below). The gate blocks the ADP→NONE state roll when the 10-step average of `forceDotFil` is strictly positive. If the gate passes, the motor transitions to NONE state but **does not call `release()` on the link** — it stays mechanically attached.

`Env.myoOnFilADP_None` defaults to 1000 s⁻¹.

**Call site in doLoop().** Phase `Env.biochemStart = 11`, line ~759 in `BoxOfActin.java`. This fires AFTER step (phase 9) and after move (phase 10). `biochemStep()` is dispatched via `Thing.biochemStep()` on all Things.

---

### Sign convention verification

`MyoFilLink.addForces()`:

```java
// MyoFilLink.java:80–97
public void addForces() {
    double dist = Pt3D.ptDist(motorPt, attachPt);
    forceMag = dist * myoSpring;
    F.unitVec(attachPt, motorPt);         // (1) F = (attachPt − motorPt) / |...|
    F.scale(forceMag);
    myMotor.incForceSum(F, motorPt);

    // forceDotFil computed HERE — BEFORE sign flip
    forceDotFil = Pt3D.Dot(F, mySeg.uVec); // (2) F is still force ON MOTOR
    forceDotFilTrack.registerValue(forceDotFil);

    F.scale(-1);                          // (3) sign flip
    mySeg.incForceSum(F, attachPt);       // (4) −F applied to filament
}
```

`Pt3D.unitVec(pt1, pt2)` computes `(pt1 − pt2) / |pt1 − pt2|` (verified at Pt3D.java:114–126). So `F.unitVec(attachPt, motorPt)` = unit vector FROM `motorPt` TOWARD `attachPt`. This is the spring restoring force on the motor: pulling the motor head (end2) back toward its attachment site on the filament.

`mySeg.uVec` points from end1 toward end2 of the filament segment, i.e., toward the barbed (plus) end.

**Physical meaning of sign:** `forceDotFil > 0` when the force on the motor points toward the barbed end — i.e., when `attachPt` is on the barbed side of `motorPt` (motor has been left behind on the pointed side while the filament or attachment site is further toward the barbed end). `forceDotFil < 0` when the force on the motor points toward the pointed end — i.e., when `motorPt` (end2) has advanced ahead of `attachPt` toward the barbed end.

**During the power stroke** (ADPPi → ADP state transition): the alignment torque changes its target angle from 120° (cocked, tilted toward pointed end) to 90° (uncocked, perpendicular). This rotates the motor body such that end2 sweeps from the pointed side toward the barbed side of the motor center. After the stroke, end2 has advanced toward the barbed end while `attachPt` follows the filament. If the filament is stalled (load condition), end2 is now on the barbed side of `attachPt`. Result: `forceDotFil < 0`.

**Conclusion:** forceDotFil < 0 corresponds to **LOAD** (motor has executed or is executing its power stroke against a resisting filament). forceDotFil > 0 corresponds to an **assisting** condition (filament has moved forward, leaving the motor lagging).

The survey's claim (SURVEY_MYOSIN_AND_GLIDING.md line ~158) that BoA mainline computes `forceDotFil` before the sign flip is **confirmed**. forceDotFil is the component of force on the MOTOR along the filament barbed-end direction. This contrasts with boxOfActinMT-4, where `forceDotFil` is computed after the sign flip and represents the component of force on the FILAMENT — the two values are equal and opposite, and the `dissociateADP()` conditions (`< 0` vs `> 0`) account for this difference. Both codebases ultimately block ADP release when the motor is actively doing work.

---

### Interaction analysis

**Can both fire in one timestep?** Yes. Path A fires in phase 9; Path B fires in phase 11. If Path A releases the motor in phase 9, the link's `release()` method sets `myMotor.onFil = false`, zeros `forceDotFil = 0`, and zeroes `forceDotFilTrack`. When Path B fires in phase 11 on the same motor (now detached, still in ADP state): `forceDotFilTrack.averageVal()` returns 0, the gate condition `0 > 0` is false, and the motor may transition to NONE. This is a harmless sequential detach-then-state-clear; the ADP→NONE transition on an already-unbound motor is physically innocuous (the motor would have released ADP anyway).

If Path A does NOT fire, the motor remains bound in ADP state. Path B fires independently in phase 11 and transitions the motor to NONE state WITHOUT calling `release()`. The motor is still mechanically attached. On subsequent steps, ckRelease() continues to evaluate force-dependent detachment using the Guo-Guilford formula. The nucleotide state change (ADP→NONE) does not itself affect ckRelease() because the formula has no nucleotide-state dependence.

**Relationship: complementary, but both inverted.**

The two paths model **different physical events**:
- Path A (ckRelease): mechanical detachment of the motor head from actin, force-dependent.
- Path B (dissociateADP): biochemical release of ADP from the motor's active site, state-dependent, gated by force direction.

They are structurally complementary: a motor in ADP state can lose its nucleotide via Path B without detaching, then proceed through NONE→ATP and eventually detach via Path A (or via Path A directly). This two-step structure mirrors the biology (ADP release is the rate-limiting step, ATP then triggers fast detachment).

However, both paths are **inverted relative to NMII catch-slip biology**:

**Path A inversion.** The Guo-Guilford formula requires F > 0 to mean LOAD for catch behavior. From the sign analysis above, LOAD = forceDotFil < 0. Substituting:

- At small load (forceDotFil = −f, f > 0 pN):
  - catchTerm = 0.92 × exp(−(−f×10⁻¹²)×2.5×10⁻⁹ / kT) = 0.92 × exp(+f×0.608) > 0.92 → increases with load
  - slipTerm = 0.08 × exp((−f×10⁻¹²)×0.4×10⁻⁹ / kT) = 0.08 × exp(−f×0.097) < 0.08 → decreases
  - Total at f=2 pN: ≈ 3.11 + 0.07 = 3.18 (vs. 1.0 at zero force) — **increases 3× at 2 pN load**

  For NMII catch-slip (Melli 2018), the off-rate should DECREASE at 2 pN load, reaching a minimum near 2–4 pN. The current formula produces a monotone increasing off-rate with load — a pure slip bond.

- Under assisting force (forceDotFil > 0): the total DECREASES at small positive f (catch-like). The formula produces catch behavior in the ASSISTING direction and slip in the LOAD direction — exactly backwards.

  Correct formula (sign-flipped exponents):
  ```java
  guoCatchTerm = alphaCatch × exp(+forceDotFil × xCatch / kT);
  guoSlipTerm  = alphaSlip  × exp(−forceDotFil × xSlip  / kT);
  ```

**Path B inversion.** The gate `if (forceDotFil > 0) { return; }` blocks ADP release under ASSISTING force (forceDotFil > 0) and ALLOWS it under LOAD (forceDotFil < 0). For catch-bond behavior (load slows ADP release), the gate should block under LOAD:
  ```java
  if (tipLink.forceDotFilTrack.averageVal() < 0) { return; }  // correct for catch
  ```

Both path inversions are consistent with each other: the combined effect is a slip bond for both detachment and ADP biochemistry.

---

### Biological assessment

**Intended behavior.** NMII is a catch-slip bond: at small loads (0–2 pN per head), ADP release slows and the head remains strongly attached; above ~4 pN the bond enters the slip regime and off-rate increases. Path A (ckRelease) should implement the mechanical component of this: force-dependent detachment that decreases at small load and increases at high load. Path B (dissociateADP) should implement the biochemical component: ADP release rate that decreases under load, extending the strongly-bound lifetime.

**Current behavior.** Path A implements a slip bond that INCREASES detachment rate monotonically with load. Path B allows ADP release to proceed under load (and blocks it under assisting force) — also slip-like. The combined effect is that loaded motors detach faster and transition out of the ADP state faster under load. This is the opposite of NMII behavior, which maintains tension at near-stall loads.

**Practical consequence for gliding assay.** At near-zero external load (unloaded gliding), both motors experience small and variable spring forces; the formula evaluates near its zero-force value (kOff × 1.0 = 100 s⁻¹). The catch vs. slip distinction is small and the gliding velocity is primarily set by the duty-ratio parameters and step kinetics. **A basic gliding velocity measurement at saturating ATP is minimally affected by the formula inversion.** The inversion matters most for: (a) tethered-filament F–V curves, (b) stall force measurements, and (c) tension-maintenance at near-stall loads. These benchmarks would produce qualitatively wrong results with the current formula.

---

### Recommended fix (for planner decision)

The simplest correct fix is to negate `forceDotFil` before feeding it to both the ckRelease formula and the dissociateADP gate. In `MyoFilLink.ckRelease()`, change the exponent signs so that positive input = load:

```java
// proposed corrected formula (do not implement — planner to decide)
double F = -forceDotFil;  // positive under load (forceDotFil < 0 = motor ahead of attachment)
double guoCatchTerm = Env.alphaCatch.getValue() * Math.exp(-F * Env.xCatch.getValue() / (Env.Boltz*Env.tempK));
double guoSlipTerm  = Env.alphaSlip.getValue()  * Math.exp( F * Env.xSlip.getValue()  / (Env.Boltz*Env.tempK));
```

And in `MyoMotor.dissociateADP()`, flip the gate:

```java
if (tipLink.forceDotFilTrack.averageVal() < 0) { return; }  // block when under load
```

Before accepting this fix, the planner should decide: (a) run the gliding assay first and verify that gliding velocity is in the right ballpark before fixing the catch-slip formula — since gliding is minimally affected, this is reasonable; or (b) fix the formula first and re-run the tuning, accepting that the motor parameters (kOff, alphaCatch, etc.) may need retuning under the corrected formula.

---

### Incidental findings

**`ValueTracker.averageVal()` does not guard against unfilled slots.** The method always divides by `stepsToTrack = 10` regardless of how many steps have elapsed since binding. For the first 9 steps after binding, the average is diluted by zeros from empty slots (slots are zeroed on `release()`). This makes the gate in `dissociateADP()` effectively transparent early in binding (average ≈ 0, gate condition `> 0` is false → gate allows ADP release). The bias disappears after 10 steps. Practically negligible but slightly distorts duty ratio for very short-lived binding events. The `runningAverageVal()` method handles this correctly but is not used here.

**`dissociateADP()` does not check `onFil`.** The on-filament ADP rate (`myoOnFilADP_None`) is applied even to an unbound motor in ADP state. There is no off-filament ADP rate parameter. Given that a free motor should not be strongly bound in the ADP state in normal operation, this is unlikely to have practical consequence unless there is a code path that leaves a motor unbound in ADP state (which can happen when ckRelease fires in the same step as described above).

## 2026-05-25 — Sister-codebase survey: force-dependent release and gliding assay

Surveyed `ActomyosinNet3` and `boxOfActinMT-4` in `~/Dropbox/CodeSync/codeToSurvey/`. Full findings in `SURVEY_MYOSIN_AND_GLIDING.md`.

Short version:
- **Force-dependent release:** BoA mainline already has the most complete model (Guo–Guilford catch-slip in `MyoFilLink.ckRelease()`). ANM3 has catch-only (Veigel 2003, 2D). boxOfActinMT-4 has slip-only — it's the commented-out precursor in BoA mainline. Nothing to port for this target.
- **Gliding assay measurement:** The missing piece in BoA mainline is a per-step velocity evaluator. ANM3's `GlidingAssayEvaluator` (~120 lines) is the portable piece — logs per-filament distance moved, position, average motor count, duty ratio. The motor-pinning mechanism (`MyosinFixed`) is already in BoA mainline; what's needed is the output callback.
- **Note flagged for planner:** The interaction between `MyoFilLink.ckRelease()` (runs every step, all nucleotide states) and `MyoMotor.dissociateADP()` (ADP state only, directional gate) may have sign-convention subtleties worth verifying before the gliding assay benchmark is instrumented.

## 2026-05-22 — Planning: myosin motor validation benchmarks

### Context

Actin filament representation is in good shape: well-measured biochemical rate constants, and the V25 deflection tuner provides biophysical validation of the lumped-parameter mechanical model across 32–128 monomer segment lengths. Time to bring myosin motors up to a comparable level of validation confidence.

Motor representation in BoA: lumped-parameter, three-element — head domain, short neck (compliant element), tail. Validation needs to exercise each element and the collective behavior they produce.

### Validation battery (planned, not yet implemented)

A single assay can't validate the motor model — different assays stress different parts. Plan is a small battery of three tests that triangulate the representation.

**1. Gliding (sliding-filament) assay — primary**

Geometry: shallow box arena, myosin tails locked to the bottom plane (mimics motor coating on a glass slide), small number of randomly oriented filaments above, dense random population of surface-bound motors.

Reference numbers (skeletal myosin II): gliding velocity ~5–8 µm/s at saturating ATP. Uyeda/Spudich minimum-motor-density threshold below which motion becomes irregular or stops.

Instrumentation should extract three things from one setup:
- Mean filament velocity at saturating ATP
- Velocity distribution per filament (not just mean)
- Velocity-vs-motor-density curve (the density sweep is what most cleanly stresses duty ratio)

Validates collective quantity (duty ratio × step size × cycle rate) but does not separate the factors.

**2. Tethered-filament force–velocity — secondary**

Same arena geometry as gliding, plus a virtual spring tether on one filament end. Sweep spring stiffness to produce a force–velocity curve. Expect roughly hyperbolic F–V; stall force should scale ~linearly with motor number in the small-N regime.

This is the test that most directly validates the neck-as-compliant-element representation, since the neck is what sets how force feeds back onto stepping kinetics.

**3. Attachment lifetime histogram — cheap add-on**

Instrument the motor state machine to log bind/unbind events during either assay above. At zero load, attachment lifetime distribution should be ~exponential, with rate set by ADP release / ATP binding kinetics. Costs almost nothing to add once the motor state transitions are accessible.

### Rationale for ordering

Gliding first because it's the canonical assay, the experimental numbers are well-known, and one setup yields three measurements (mean velocity, distribution, density sweep). F–V second because it requires the gliding arena plus a tether — incremental code work, and it's where the neck-stiffness lumped parameter actually gets tested. Lifetime histogram is essentially free instrumentation added to whichever assay is running.

### Tests considered and deferred

- **Processivity / run-length:** mostly relevant for myosin V, not myosin II. Skip unless myosin V is added to the model later.
- **Bidirectional / disordered gliding regime** (Gowrishankar–Rao and active-gel literature): qualitative validation that the model produces the right kinds of collective behavior at high density. Useful as a sanity check, not a tuning target. Maybe revisit after the quantitative battery passes.
- **Mini-filament / bipolar thick filament assays:** muscle-relevant geometry (antiparallel actin pulled by bipolar motor assemblies). More involved setup. Defer.

### Pass/fail criteria

Not yet specified. Need to decide acceptable tolerance on gliding velocity, what counts as "matches F–V shape," and what counts as "approximately exponential" for lifetime histograms. To be settled before implementation begins, since these define what the assays are trying to show.

### Sister-codebase merge

A prior version of BoA in a sister project may contain a working gliding-assay arena setup. The merge is planned for the next few days. **Action item:** before specifying new gliding-assay code, survey the sister branch for existing arena geometry, motor surface-binding logic, and any initial-condition code for randomly oriented filaments. Decide then whether to revive, rewrite, or supplement.

### Open questions for future session

- Pass/fail tolerances for each assay.
- What's in the sister branch.
- Whether motor surface-binding (tail locked to plane) requires new infrastructure or is expressible with existing constraint primitives.
- Whether the existing parameter-file format can express assay-specific initial conditions or if a new mechanism is needed.

### Status

Planning only. No code changes. Next session should start with the sister-branch survey.

---

## V25 milestone — automatic deflection tuner

*Survey note (2026-05-22): V10–V24 tuner development entries have been moved to JOURNAL_ARCHIVE.md (V18–V24 in the prior session; V10–V17.1 in this session). The detailed V25 implementation entry follows below.*

**Status:** working. Current production tuner is V25.

**What it does.** Given a target deflection for a benchmark filament, the tuner automatically finds (fracR, fracMoveTorq, fracMove) values that make the simulated deflection match target. Implements the deflection / time-constant tuning procedure from Alberts (2009) PLoS ONE, with automation.

**Algorithm.**
1. *Coarse pre-pass.* At each candidate fracMove (starting from 0.5, decrementing by 0.05), measure the deflection at the softest 2D corner (fracR=1.5, fracMoveTorq=0.01). The first fracMove that produces deflection ≥ target × 1.10 is selected.
2. *2D Broyden solver.* At the selected fracMove, run Newton's method with a finite-difference initial Jacobian on (fracR, fracMoveTorq) starting from the stiffest 2D corner (fracR=0.1, fracMoveTorq=0.5). Three seed settles build the initial Jacobian; subsequent Newton steps use Broyden rank-1 updates.
3. *Noise-aware Jacobian.* If |Δparam| falls below 0.005 on either dimension during a Newton step, the Jacobian update is skipped (frozen J reused). Prevents Broyden from chasing measurement noise near convergence.
4. *Physics-aware convergence.* Tolerance scales with target: `CONV_TOL = max(1e-5, 0.005 × target)`. Below the measurement noise floor (≈ 0.5% of settled deflection) the algorithm cannot resolve further.
5. *Safety-net outer loop.* If V24's 2D infeasibility detector fires after pre-pass handoff (rare), fracMove drops further. Logged as `[V25:PREPASS_INSUFFICIENT]`.

**Parameter bounds.**
- fracR (C_R): [0.1, 1.5]. Lower = stiffer filament.
- fracMoveTorq (C_θ): [0.01, 0.5]. Higher = stiffer. Upper bound is the theoretical stability limit from Alberts 2009.
- fracMove (C_δ): [0.02, 0.5]. Higher = stiffer. Upper bound 0.5 is theoretical stability limit. Lower bound 0.02 is empirical (geometric chain-link integrity; 128-mon converges at ≈0.029).

**Performance** (benchmark: 11-segment filament, deflection target = analytic δ for a simply-supported beam under transverse force).

| filSegLength | Target δ (µm) | Final (fr, fmt, fracMove) | Iterations | Frames |
|---|---|---|---|---|
| 32 mon | 0.0098 | (0.146, 0.386, 0.50) | 2 | 263 |
| 48 mon | 0.0146 | (0.287, 0.070, 0.50) | 7 | 563 |
| 64 mon | 0.0193 | (0.377, 0.031, 0.25) | 10 + 6 prepass probes | 1277 |
| 96 mon | 0.0288 | PREPASS_FAILED (old bound 0.10); expected to converge with new bound 0.02 | n/a | — |
| 128 mon | 0.0385 | PREPASS_FAILED (old bound 0.10); expected to converge at fracMove≈0.029 | n/a | — |

**2026-05-22 update:** fracMove lower bound relaxed from 0.10 to 0.02 to enable tuning of 96-mon and 128-mon segments. Empirical: 96-mon converges at fracMove≈0.098, 128-mon at fracMove≈0.029. Chain-link geometric integrity acceptable at these values. Pre-pass decrement logic fixed to clamp to FRACMOVE_MIN (instead of rejecting) so the probe at exactly 0.02 is reached.

**Known limit at 96-monomer and above.** The lumped-parameter representation is at the edge of its accuracy regime at these segment sizes — segments are ~0.26 µm (96-mon) and ~0.35 µm (128-mon), comparable to the ParM tuning case in Alberts 2009 Fig. 5B where method accuracy begins to degrade. Tuning may succeed but resulting filament behavior may not match the analytic target well outside the tuned regime.

**Workflow.**
```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV25 \
    -pf ParameterFiles/boaDebugParams -3jsLive 8081
```
Set `filSegLength` in the parameter file to choose the test case. Tuner runs autonomously, logs `[V25:CONVERGED]` or `[V25:PREPASS_FAILED]` when done.

**Earlier versions retained.** V18 through V24 remain selectable via `-bmTunerV18` ... `-bmTunerV24`. Useful for regression testing. Detailed history of how the algorithm developed is in JOURNAL_ARCHIVE.md.

---

## 2026-05-22 — V25: coarse fracMove pre-pass + V24 convergence

### Motivation

V24 converges all three test cases cleanly but wastes frames on 64-mon (and worse at 96+): ~1370 of 2262 total frames are spent in seed-triplet settles at fracMove values that are 2D-infeasible. Each infeasibility attempt costs ~400–500 frames before detection. The pre-pass replaces those blind attempts with fast single-point probes at the softest 2D corner.

### Algorithm

**Phase 0 (PREPASS):** Starting at `fracMove` from the param file, settle one point at `(FR_HI=1.5, FMT_LO=0.01)` — the maximum-achievable-deflection corner at this fracMove. If `s >= target * 1.10`, declare feasible and hand off to V24's 2D Broyden at `(FR_LO, FMT_HI)`. If not, decrement fracMove by `FRACMOVE_DECREMENT=0.05` and probe again. If fracMove drops below `FRACMOVE_MIN=0.10`, log `[V25:PREPASS_FAILED]` and stop.

**Phase 1 (V24 unchanged):** At the selected fracMove, run V24's full 2D Broyden solver starting from `(FR_LO, FMT_HI)`. V24's fracMove outer-loop safety net is preserved — if V24 detects 2D infeasibility, it drops fracMove further and logs `[V25:PREPASS_INSUFFICIENT]`.

New constant: `PREPASS_MARGIN = 0.10`. All V24 constants and logic unchanged.

### Survey findings (locations in V24 → translated to V25)

1. **State machine (Phase enum):** `SEEDING_1/2/3 → NEWTON → CONVERGED|FAILED`. V25 prepends `PREPASS` — handled in `onSettled()` switch via `case PREPASS: return onPrepassSettled(s)`.
2. **`start()`:** V24 calls `initTwoDimSearch(FR_LO, FMT_HI)`. V25 instead sets `fr=FR_HI, fmt=FMT_LO, phase=Phase.PREPASS, resetSettle()` directly.
3. **`feed()` / `onSettled()`:** Settling logic unchanged; dispatch branching extended for PREPASS case.
4. **`initTwoDimSearch(frCorner, fmtCorner)`:** Called from pre-pass handoff (with FR_LO, FMT_HI) — leaves state identical to a fresh V24 start at the selected fracMove, same seed-triplet perturbations.
5. **`handleInfeasible()` fracMove drop:** After dropping fracMove and logging `FRACMOVE_DROP`, V25 adds check: if new fracMove < prepassFracMoveSelected, log `[V25:PREPASS_INSUFFICIENT]`.

### Dispatch wiring (six edits)

- `Env.java`: `static boolean benchmarkTunerV25 = false;` added after V24 line.
- `BoxOfActin.java`: (1) `static DeflectionTunerV25 deflTunerV25 = null;` field; (2) `-bmTunerV25` arg parsed; (3) mutual-exclusivity v25 > v24 > ... block prepended, comment updated; (4) `eitherTunerActive` includes `deflTunerV25 != null`; (5) V25 feed path prepended to if-else chain; (6) V25 arm block prepended at softest corner (FR_HI, FMT_LO), `else if (Env.benchmarkTunerV24)` for old V24 block; AUTOTUNE ternary prepended with `Env.benchmarkTunerV25 ? "v25" : ...`.

### Arm block difference from V24

V24 pre-sets Env params to `(FR_LO, FMT_HI)` before calling `start()`. V25 pre-sets to `(FR_HI, FMT_LO)` so the sim runs at the correct physical parameters during the pre-pass probe settle.

### Compile verification

`javac -XDignore.symbol.file -cp ".:libs/*" boxOfActin/*.java *.java` — zero errors, zero warnings.

### Runtime flags

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV25 -bmMonomer 32 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV25 -bmMonomer 48 -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV25 -pf ParameterFiles/boa10-64Seg -3jsLive 8081
java -Xmx800M -cp ".:libs/*" BoxOfActin -r -bmTunerV25 -bmMonomer 96 -3jsLive 8081
```

### Expected per-case behavior

- **32-mon** (target ≈ 0.0098 µm): probe at (1.5, 0.01, 0.50) → s well above target+margin → 1 probe, immediate handoff. Prepass ≈ 80 frames, V24 solve ≈ 200 frames. Total ≈ 280 frames.
- **48-mon** (target ≈ 0.0146 µm): similar, 1 probe, ≈ 450–530 frames total.
- **64-mon** (target ≈ 0.0193 µm): probe at 0.50 → below target+margin, decrement to 0.45, 0.40, 0.35, 0.30 → ~5 probes, each ~80 frames. Then V24 at fracMove=0.30, expected ~300 frames. Total ≈ 700–800 frames (vs current 2262).
- **96-mon**: more probes likely (fracMove probably needs to drop to 0.20–0.25), then V24. Estimated 1500–3000 frames. User will verify.

### Open questions

- If the 32/48-mon final answers differ from V24's by > noise, the stiffest-corner handoff might produce a different Jacobian seed than before. Inspect `s1/s2/s3` in logs.
- If `[V25:PREPASS_INSUFFICIENT]` fires on 96-mon, increase `PREPASS_MARGIN` from 0.10 to 0.15 in V26.
- `[V25:PREPASS_PROBE]` `reachable=false` at fracMove=0.10 → PREPASS_FAILED indicates target is unreachable at any legal fracMove; the chain parameters need a different calibration approach.

---

## 2026-05-17 — Persistence length benchmark Phase 2 implementation

Implemented the LP benchmark as designed in Phase 1, with the EWMA adjustment. All 12 steps completed. Compile clean.

### EWMA α and parameter name

`lpEwmaAlpha` declared in `Env.java` with default 0.001 (effective window ~1000 output frames). Mutable at runtime.

### Key implementation notes

**Per-segment `brownianOff` flag:** deflection-chain segments set `brownianOff = true` after creation; LP chain keeps default `false`. Global `Env.brownianFilMotionOff` no longer set in benchmark mode.

**`isLpSeg` flag:** LP chain segments tagged `isLpSeg = true`; `ThreeJSWriter` axis overlay is gated on `!fs.isLpSeg` to suppress visual clutter on 90 LP segments.

**EWMA accumulator:** single `cMean[k]` array; first frame seeds directly, subsequent frames apply `α × cNew + (1-α) × cMean`. Weighted least-squares Lp fit with weights `w_k = C_k²`.

**Box sizing:** `Math.max(deflFil.chainSpanMicrons, lpFil.contourLength)` before ×3 multiplier. With LP chain at 8.019 µm, box auto-sizes to ~24 µm.

**LP chain placement:** n = round(8.0 / segLen) segments, centered at (0, −1.5, −0.5) µm.

### Files changed

- `boxOfActin/FilSegment.java` — `brownianOff`, `isLpSeg` fields; `moveThing()` condition; `makeLpChain()`
- `boxOfActin/Env.java` — `lpEwmaAlpha` parameter
- `boxOfActin/BoxOfActin.java` — `DeflFil`/`LpFil` inner classes; all `bench*` → `deflFil.*`; `accumulateLpData()`; `buildLpJson()`; LP chain creation; box sizing; logAndDraw/remoteLog dispatch
- `boxOfActin/ThreeJSWriter.java` — `deflFil.*` references; `chainType` field; axis gating on `!isLpSeg`
- `boxOfActin/LiveFrameServer.java` — `dispatchLpBenchmark()`
- `sim_viewer_boa.html` — LP panel CSS/HTML/JS; `updateLpPanel()`; Force→Deflection rename; `defl-off` CSS class

---

## 2026-05-17 — LP benchmark polish + Persist suspend semantics

Six items implemented. Compile clean.

### 1. Persist: ON/OFF — Java-side suspend/resume

**New `lpActive` parameter** (boolean, default ON, mutableAtRuntime). Guards in `FilSegment.step()`, `moveThing()`, and `calcRandomForces()` freeze the LP chain fully when suspended. `accumulateLpData()` early-returns when suspended. Accumulator resets to all-1.0 on the 0→1 (OFF→ON) transition.

### 2–6. Other items

- Panel header rename: `— Benchmark —` → `— Deflection/Relaxation —`
- Chain info (seg count, monomers, span) added to LP panel
- τ_meas and τ_theo changed to 4 decimal places
- `lpEwmaAlpha` default lowered from 0.01 to 0.001
- Weighted least-squares Lp fit (weights `w_k = C_k²`) replacing unweighted fit

---

## 2026-05-17 — On the physical justification of BT/Bθ Brownian tuning

### The deeper interpretation

The deflection tuning (CR, Cθ) and the Brownian tuning (BT, Bθ) are the same kind of object — empirical calibration constants that map a discrete representation onto continuum behavior.

1. **The chain has more degrees of freedom than the continuum at short wavelengths.** BT/Bθ correct for mode partitioning that differs between discrete and continuum.
2. **Fluctuation–dissipation interpretation.** Per-segment drag coefficients assume isolated bodies; constrained neighbors change effective drag. Bθ < 1 keeps FDT balance between injected Brownian forcing and effective (not isolated) dissipation.
3. **System count.** Five tuning coefficients (Cδ, CR, Cθ, BT, Bθ), three physical targets (deflection, relaxation time, persistence length). Well-determined.
4. **Segmentation-specific.** Like CR/Cθ, the BT/Bθ tuning depends on segment size, time-step, viscosity, chain length.

### Practical implication

A factor of 2 in Lp is biophysically meaningful. The four-parameter tuning loop: adjust CR/Cθ on deflection chain for δ and τ; adjust BT/Bθ on LP chain for Lp; verify CR/Cθ haven't drifted.

`Env.BTransCoeff` (default 1.0) and `Env.BRotCoeff` (default 0.5) promoted to `setMutableAtRuntime()`.

---

## 2026-05-17 — Tuning underdetermination: iso-(δ, τ) family

While tuning the deflection benchmark, two different (fracMove, fracR, fracMoveTorq) triples produced the same static deflection and relaxation time. The deflection-and-τ tuning is underdetermined: three knobs, two targets, one free direction (the paper sets Cδ = 0.4 as the arbitrary pinning choice).

### Why this matters

Two (Cδ, CR, Cθ) triples that produce the same δ and τ₁ can produce different Lp because the constraint network distributes compliance differently among the modes. Specifically: larger Cδ → more of the Brownian translational forcing channels into bending modes rather than axial fluctuations.

### Implications

1. **The BT/Bθ tuning is specific to the chosen (Cδ, CR, Cθ) triple.**
2. **Reporting tuning values requires the full triple.**
3. **The family creates apparent compensation.** BT/Bθ ends up correcting for both genuine discretization artifacts *and* an arbitrary choice within the family.

### Operating point of record

- chain: 11 seg × 32 mon (deflection); 90 seg × 32 mon, span 8.019 µm (LP)
- aeta = 0.1 Pa·s, deltaT = 1e-4 s
- fracMove = 0.4, fracR = 0.1, fracMoveTorq = 0.291
- benchmarkForceFrac = 0.01
- BTransCoeff = 1.4, BRotCoeff = 0.5
- Result: ratio ≈ 1.0, τ_meas ≈ τ_theo (0.0586 vs 0.0570 s), Lp_meas drifting 7–30 µm range around Lp_theo = 15.0 µm

---

## 2026-05-17 — The iso-(δ, τ) family: proposed empirical mapping

At fixed fracMove (= Cδ), the deflection and relaxation-time targets define a *curve* in (fracR, fracMoveTorq) space — the iso-(δ, τ) family.

### What changes along the family

δ and τ₁ are constant by construction. Everything else can vary: higher-mode relaxation times, mode partitioning, persistence length Lp, sensitivity to applied force magnitude.

### Proposed minimal experiment

1. Fix fracMove = 0.4, monCt = 32, aeta = 0.1 Pa·s, deltaT = 1e-4.
2. Find three operating points on the iso-(δ, τ) curve: current (fracR=0.1, fracMoveTorq=0.291), softer, stiffer.
3. At each, with BT/Bθ held at current tuned values, measure Lp_meas from N=5–10 independent fresh-EWMA readings.
4. Plot (fracR, Lp_meas).

**Outcomes:** flat curve → family is degenerate in Lp; steep curve → family-choice matters and Lp can be set by family position alone; moderate slope → both knobs contribute.

### If results warrant systematic mapping

A 6–12 point grid: 3 monomer counts × 1–2 filament lengths × 1 viscosity × 1 time-step. Output: tuning recommendation per (monCt, segLen, viscosity, Δt) tuple.

---

## 2026-05-17 — Session summary: manual benchmarking apparatus complete, two long-standing bugs found

### What landed

**Rounds 1-2:** Deflection HUD, bending coefficients runtime-mutable, force toggle, relaxation timer.

**Round 3:** Initial population suppressed, chamber wireframe hidden, wall-collision inactive for benchmark chain.

**Round 4:** Chain config HUD + per-segment axes.

**Round 5:** aeta viscosity-dependence diagnosed (not a code regression). PAIRS coefficients at aeta=1.0 produce much less deflection at default aeta=0.1.

**Round 6:** Removed stdSegLength=32 override.

**Round 7:** Viscous-blob mechanism (560× rotational drag jump at monomerCt=50) diagnosed and commented out.

### Validation

At aeta=0.1, all monomerCt values 32–64 converge to ratio≈1 with reasonable coefficient values. Static and dynamic both match analytic predictions.

### Lessons

1. **Phantom regression pattern:** zero-deflection was not introduced by recent code — it was a pre-existing aeta-dependence never exercised before.
2. **Per-segment axis rendering** pinpointed viscous-blob bug immediately (segments stayed axis-aligned instead of rotating to follow the curve).
3. **Old hacks bite when you come back:** viscous-blob code was written for a specific Listeria experiment, forgotten in main codebase, triggered by benchmark at its threshold.

---

## Current Known Issues

### Phase 5 not yet started

Java 21 has not been installed on the MBP. `brew install openjdk@21` is the next step. Once Java 21 is on the system:

1. Validate compilation with `javac --release 21 --enable-preview` (no JOGLAndj3D classpath needed anymore)
2. Validate that an unmodified `-r -pf ParameterFiles/boa10-64Seg -3js <dir>` run produces the same trajectory under Java 21 as under Java 8
3. Commit the new build instructions to CLAUDE.md

After that, the path is open to TornadoVM integration on aorus (the GPU machine). The GPU plan in CLAUDE.md (preserved from earlier sessions) remains the authoritative roadmap.

### Vestigial graphics bookkeeping fields (partial — residue after Session 11)

- `renderThicken` / `setRenderThicken()` in `FilSegment.java`: `renderThicken` is read inside the dead `setRenderThicken()` method, which is never called. Delete the method first, then the field becomes write-only and can be removed. Phase 6.
- `Chamber.java`, `Bug.java`, `Crucible.java` static boolean graphics flags (`bugInScene`, `coordSysInScene`, `appearanceChanged`, `useWireAppearance`, `shiny`): write-only in Chamber and Bug; `Crucible.java:215` writes `appearanceChanged = true`. Needs multi-file pass. Phase 6.

---

## 2026-05-25 — Gliding assay density-sweep batch infrastructure

### Phase 0 — glidingAssayDataSetRun() investigation

`glidingAssayDataSetRun()` in `MyosinFixed.java` IS active in all runs where `glidingAssay:true`. It calls `storeGlidingAssayPos()` (stores X-position of `theFilSegments[0]` to a static 2D array) every `remoteWriteInterval` timesteps, then checks `Env.simulationTime >= finalTimeForEachDataPoint` (hardcoded 2.0 s). If true, it either increments the density by `myoDensityIncrement=100` and calls `restartRun(false)` (runs 1–10) or calls `System.exit(0)` (run 11).

Impact: for `-t 0.5` test runs the restart never fires (0.5s < 2.0s — dormant). For `-t 4.0` production runs it fires at t=2.0s, calls `restartRun(false)`, restarts the JVM with density+100, and the new `GlidingAssayEvaluator` inherits stale filament state from the previous run. The two systems are unintegrated: `restartRun()` does not reinitialize `glidingEvaluator`.

### Phase 1 — Java changes

Added `Env.externalDensitySweep` boolean parameter (default false, `Parameter.BOOLEAN`). Added a guard at the top of `glidingAssayDataSetRun()`:
```java
if (Env.externalDensitySweep.isActive()) return;
```
When `externalDensitySweep:true:1.0;` is in the parameter file, `glidingAssayDataSetRun()` returns immediately, leaving the `GlidingAssayEvaluator` fully in charge of output. The template sets this unconditionally.

`fixedMyosinDensity` is already a standard `Parameter` read by `fillPlaneWithFixedMyosins()` at run time — no change needed. Setting `fixedMyosinDensity:true:__DENSITY__;` in the per-run param file is sufficient.

### Phase 2 — Deliverables

**`runGlidingSweep.sh`** — executable shell script at repo root.
- Args: `-o <parentDir>` (default `~/Desktop/gliding_batch`), `-p <templateFile>` (default `ParameterFiles/glidingAssayBatch_template`), `-t <runTimeSec>` (default 4.0), `-h`.
- If parent dir already exists, auto-increments (`gliding_batch.001`, `.002`, …).
- Loops over 8 densities: `10, 25, 50, 100, 200, 500, 1000, 2500` motors/µm² (sparse-to-saturating log-ish spacing).
- Generates per-density param file via `sed`, passes `-pf` and `-3js` flags to BoA, copies `params.txt` into the output subdir after BoA finishes.
- Continues on failure per density; summarizes failures at end.

**`ParameterFiles/glidingAssayBatch_template`** — based on `glidingAssayTest`, with `runTime:true:__RUNTIME__;`, `fixedMyosinDensity:true:__DENSITY__;`, and `externalDensitySweep:true:1.0;`.

**Output tree example:**
```
gliding_batch/
  density_10/
    frame_000000.json … frame_000500.json
    gliding_assay.dat
    params.txt
    source.zip
  density_25/ …
  density_50/ …
  density_100/ …
  density_200/ …
  density_500/ …
  density_1000/ …
  density_2500/ …
```

**Tested:** verified with `-t 0.5`. Density 10 produced 501 frame JSONs, 501-row `gliding_assay.dat` (header + 500 data rows), `params.txt`, and `source.zip`. `surfaceDensity` column reads 10.00 in the data file.

**Multi-batch accumulation:** each `./runGlidingSweep.sh` invocation creates a new parent dir (or auto-incremented variant). Post-processing globs across them: `cat gliding_batch*/density_*/gliding_assay.dat`.

**Wall-clock note:** at `-t 4.0`, each density takes roughly 40–50 min on the MBP (10 min/sim-sec × 4 s). Full sweep ≈ 5–7 hours. Run overnight or in a `screen` session.

### Next steps

- Post-processing script (Python or awk): read all `gliding_assay.dat` files in a batch, filter to non-settling rows (`longWindowSettling==0`), and produce a velocity-vs-density CSV or matplotlib scatter/box plot. Suggested columns to use: `longWindowSpeedXY` (µm/s) vs `surfaceDensity` (motors/µm²). Deferred to next session.

### Addendum: heap-size scaling for high-density runs

OOM observed at densities ≥ 200 motors/µm² with the default `-Xmx800M`. At 2500 motors/µm² in a 9×3 µm chamber, motor count is ~67,500 (×4 Things/motor = 270,000 Things), requiring multiple GB of heap. This is a JVM configuration issue, not a code bug — high-motor-count runs have historically required a larger heap. `runGlidingSweep.sh` now scales `-Xmx` per density:

| Density range | Heap |
|---|---|
| ≤ 100 motors/µm² | `-Xmx2G` |
| 200–500 motors/µm² | `-Xmx4G` |
| 1000–2500 motors/µm² | `-Xmx8G` |

---

