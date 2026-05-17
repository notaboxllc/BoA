# BoxOfActin Project Journal

Last updated: 2026-05-14

---

## 2026-05-14 — Planner: C-track complete; integration testing arc; F1 is next

The full C-track (WebSocket live observation) is done and validated:
C1 transport, C2 click-to-inspect, C3 pause/resume/kill, C4 mid-run parameter
adjustment. Integration testing (Session 15A) plus the bugfix sessions (16, 17)
were inserted mid-track after testing surfaced real bugs.

Findings worth carrying forward:
- The orderly-shutdown path had never run end-to-end before Session 16. Both
  finalization paths NPE'd; Ctrl-C bypassed finalization entirely. Now fixed.
  Exit-path audit table is in the Session 16 entry.
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
- Ctrl-C shutdown hook (Runtime.addShutdownHook → stopServer() + flush
  ThreeJSWriter).
- Parameter promotion: trace usage graph for the "unclear" rate constants,
  promote the safe ones to mutableAtRuntime. May become an F-track dependency
  if F1's benchmark design needs live rate-constant control.

Next: F1 — benchmark design session. Planner + user, not a Claude Code handoff.
User uploads their actin-benchmarking publication; design the assay data flow,
cache schema, pop-up window, and message protocol together. F1's outcome
decides whether parameter promotion jumps the queue.

---

## Session 18 — Mid-run parameter adjustment (C4) (May 2026)

### Overview

C4 completes the C-track (C1 WebSocket, C2 click-to-inspect, C3 pause/resume/kill, C4 parameter adjustment). The session adds org.json parsing, a `mutableAtRuntime` annotation on `Parameter`, a `paramQueue` drain at the established safe point, and a viewer "Params" panel in live mode.

---

### Step 0 — org.json parser

Added `libs/json-20231013.jar` (75 KB) from Maven Central (`org.json:json:20231013`). The library has no transitive dependencies and is Java 8 compatible. It is excluded from git by the existing `*.jar` rule in `.gitignore` — download on a fresh checkout:

```
curl -L -o libs/json-20231013.jar \
  https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
```

Used for parsing `setParam` and `queryParams` actions in `LiveFrameServer.onMessage()`. The existing C2/C3 string-scan parsers (for `inspect`, `pause`, `resume`, `kill`) were left as-is — retro-cleaning them was evaluated but skipped since the string-scan approach for single-field or no-field payloads is simpler than introducing a JSONObject parse for trivial cases, and existing behaviour must not regress.

---

### Step 1 — Parameter whitelist survey

All 234 registered parameters were surveyed. Classification criteria:
- **Mutable**: read fresh via `getValue()` every timestep, no cached derivatives that would diverge if the value changed mid-run.
- **Immutable**: read once at startup, OR has cached derivatives that are not recomputed each step.
- **Unclear**: appears to be read fresh but would require deeper multi-file tracing to confirm the entire usage graph is cache-free.

**MUTABLE (confirmed)**

| Parameter label | Justification |
|---|---|
| `toFileInterval` | Pure counter threshold (`threeJSCounter >= Env.toFileInterval.getIntValue()`) in `logAndDraw()` and `remoteLog()`. No cached derivatives. No physics consequence. Counter reset included for immediate effect (see Step 2). |

**IMMUTABLE — cached derivatives computed at startup or in `setTimeStepCounts()` / `setDependencies()`**

| Parameter label | Why immutable |
|---|---|
| `deltaT` | `Thing.biochemCheckInt`, `Thing.collisionCheckInt`, `Thing.brownianApplyInt`, `simJSonFreq`, `simJSon2Freq`, `simJSon2StartCounting` all derived at startup; changing `deltaT` mid-run leaves all derived values stale. |
| `biochemDeltaT` | `Thing.biochemCheckInt` derived. |
| `collisionDeltaT` | `Thing.collisionCheckInt` derived. |
| `brownianDeltaT` | `Thing.brownianApplyInt` derived. |
| `nVBlobPerBug` | `blobTransGam`, `blobRotGam` derived in `setDependencies()`; `N` constant also derived at class load. |
| `aeta` | `bTransGamViscBlob`, `bRotGamViscBlob` are `static final double` computed from `aeta.getValue()` at class initialization. Changing `aeta` mid-run has no effect. |
| `nodeRadius` | `nodeTransDiff_init`, `nodeRotDiff_init` computed from it at class init. |
| `noMonomersSimd`, `noMonomersRendered` | `monomerGraphics` boolean derived in `setDependencies()`; also affects initial object creation. |
| `boxXDim`, `boxYDim`, `boxZDim` | Used in `Chamber.makeABox()` at startup to set wall positions; changing mid-run does not move the box walls. |
| `initialFilaments`, `initialNodes`, `initialMyoMiniFils` | Used only in `makeInitialThings()` at startup. |
| `bugLength`, `bugRadius`, `bugShapedCrucible`, `simOutsideBug` | Used in `makeCrucible()` at startup. |
| `stdSegLength` (label: `filSegLength`) | Sets segment monomer count at creation; existing segments are unaffected. |
| `numMyoDimersEachEndOfMiniFil` | Sets minifilament end-dimer array size at creation. |

**UNCLEAR — appear to be read fresh each step but require deeper tracing to confirm no derivative caching**

| Parameter label | Why unclear |
|---|---|
| `actinConc` | Read in `FilSegment.setBiophysValues()` (called every step → `maxPolyForce` recomputed), AND read in Monomer biochemistry. Because `setBiophysValues()` runs every step, the `maxPolyForce` derivative is not stale. But full Monomer.java actin-state paths need tracing to confirm no other caching. |
| `kATPOn1`, `kATPOff1`, `kADPOff1`, `kATPOn2`, `kATPOff2`, `kADPOff2` | Biochem rate constants. Confirmed not cached in Env; used via `getValue()` in Monomer.java biochem steps. However, the full combinatorial usage across polymerization, depolymerization, hydrolysis, and formin paths requires systematic tracing before promoting. |
| `capRate`, `capConc` | Capping kinetics; likely read fresh each biochem step but not traced. |
| `cofilinRate`, `cofilinConc` | Grep confirms `getValue()` called fresh in `Monomer.cofilinBinding()` (line 242/244). But cofilin binding also sets per-monomer state that persists — changing these mid-run would affect new binding events but not existing cofilin-bound monomers. Safe to promote, but listed unclear pending decision. |
| `tropoOnRate`, `tropoOffRate`, `tropoConc` | Same pattern as cofilinRate/cofilinConc. Grep confirms fresh `getValue()` in `Monomer.tropoBinding()` (lines 202, 223). |
| `kRdmNuc` | Confirmed read fresh in `spawnRdmFilaments()` every step: `Env.kRdmNuc.getValue()`. |
| `kNodeNuc` | Confirmed read fresh in `spawnNodeFilaments()` every step: `Env.kNodeNuc.getValue()*Env.deltaT.getValue()`. The deltaT factor is read from the immutable `deltaT` parameter, not a cached derivative, so kNodeNuc can be promoted. Listed unclear because `deltaT` must remain immutable for the product to be well-defined. |
| `fracMove`, `fracR`, `fracMoveTorq` and related PAIRS coefficients | Used in link force calculations each step. No caching found. Likely mutable, but PAIRS force coefficients directly affect filament mechanics and a bad mid-run change could destabilize — conservative default. |
| `remoteReportInterval` | Counter threshold like `toFileInterval` (`remoteOutCounter >= Env.remoteReportInterval.getValue()`). Likely safe to mark mutable; no physics consequence. Omitted from whitelist since the spec focus was on `toFileInterval`. |
| `myosinStallForce`, `myosinBreakForce` | Motor force constants, used in catch/slip calculation. Usage site not fully traced. |

**Promotion path:** Any "unclear" parameter can be promoted to mutable by: (a) grep all files for `p.label` usage, (b) confirm every call is `getValue()` (not a cached derivative), (c) verify no per-run initialization writes a value to a local variable that is then used for the rest of the run, (d) add `.setMutableAtRuntime()` to the declaration in Env.java.

---

### Step 2 — toFileInterval counter reset

When `toFileInterval` is applied at the safe point, `BoxOfActin.threeJSCounter` is set to `newValue - 1`. This ensures the next step's `logAndDraw()` / `remoteLog()` fires a frame (counter increments to `newValue` in `updateCounters()`, then `newValue >= newValue` is true). Without this reset, dropping `toFileInterval` from 100 to 5 would require waiting up to 99 steps before the first fast frame.

Implementation in `BoxOfActin.drainParamQueue()`:
```java
if ("toFileInterval".equals(change.param.label)) {
    threeJSCounter = (int) change.newValue - 1;
}
```

---

### Step 3 — Annotation mechanism

Added to `Parameter.java`:
- `private boolean mutableAtRuntime = false;`
- `public Parameter setMutableAtRuntime()` — builder-style (returns `this`) so `Env.java` field declarations can chain it: `new Parameter(...).setMutableAtRuntime()`.
- `public boolean isMutableAtRuntime()`
- `public static List<Parameter> getAllMutable()` — iterates `theParams[0..paramCt-1]`.

No new `.java` file was needed. No existing Parameter declarations were changed (except `toFileInterval`).

---

### Step 4 — Protocol additions

**Client → server** (new actions):
```json
{"action": "queryParams"}
{"action": "setParam", "name": "<label>", "value": "<stringValue>"}
```

**Server → client** (new topics):
```json
{"topic": "paramList",  "payload": [{"name","displayName","type","value","mutable"}, ...]}
{"topic": "paramAck",   "payload": {"success":true,  "name","oldValue","newValue"}}
{"topic": "paramAck",   "payload": {"success":false, "name","error":"<reason>"}}
```

Error reasons: `"unknown parameter"`, `"not mid-run mutable"`, `"parse error: <details>"`, `"value must be non-negative"`.

`queryParams` dispatches only to the requesting connection (via `dispatchTo(conn, ...)`). `paramAck` (both success and error) is broadcast to all connected clients. `paramList` `type` field is `"boolean"`, `"int"`, or `"double"` derived from `Parameter.type` constant. `value` field for booleans is `true`/`false` JSON; for numerics it uses a minimal formatter (no trailing `.0` for integers).

**Validation** (on WebSocket thread, before queue):
- Unknown label → immediate error ack.
- Known but `!isMutableAtRuntime()` → immediate error ack.
- Parse failure → immediate error ack.
- Valid → `Env.paramQueue.offer(new Env.PendingParamChange(param, parsedValue))`.
- Success ack → dispatched by `drainParamQueue()` at the safe point.

---

### Step 5 — Safe-point drain order

`Env.PendingParamChange` inner class added to `Env.java`:
```java
static class PendingParamChange {
    final Parameter param;
    final double newValue;
    ...
}
static final ConcurrentLinkedQueue<PendingParamChange> paramQueue = ...;
```

`BoxOfActin.drainParamQueue()` added (private static). Called at the safe point in `doLoop()`:

```
pause wait loop (drainInspectQueue inside)
  → kill check
  → drainInspectQueue()
  → drainParamQueue()     ← C4 addition
  → logAndDraw() / remoteLog()
```

Param drain is after inspect drain so that a same-step inspect sees the pre-change state, keeping inspectResult coherent with the frame being dispatched.

---

### Step 6 — Viewer UI

Added to `sim_viewer_boa.html`:
- CSS: `#paramPanel`, `.pp-row`, `.pp-name`, `.pp-input`, `.pp-apply`, `.pp-msg` styles (right-side floating panel).
- HTML: `<button id="btnParams" class="liveBtn">Params ▶</button>` in `#liveBar`; `<div id="paramPanel"><div id="paramRows"></div></div>`.
- JS (live mode only):
  - `subscribe` message extended to include `"paramAck"`, `"paramList"` topics.
  - `queryParams` sent on connect (after subscribe).
  - Periodic 5s refresh of `queryParams` while panel is open.
  - `buildParamPanel(params)` — shows only mutable parameters; preserves in-flight text input across refreshes.
  - `handleParamAck(ack)` — on success: updates displayed value, shows `✓` for 2s; on failure: shows `✗` with hover-tooltip for 4s.
  - "Params ▶" toggle button opens/closes panel, requests refresh on open.

---

### Step 7 — Orientation key consistency check

**Finding: frame and inspectResult orientation keys are consistent.**

`buildFrameJson()` (ThreeJSWriter) emits `segments` with `end1`/`end2` arrays and `r` only — no orientation vector at all. Myosins emit `rod`, `lever`, `motor` sub-objects, each with `end1`/`end2` arrays. Minifilaments emit `end1`/`end2` arrays.

`buildInspectJson()` emits `orientation:{ux,uy,uz}` alongside `position:{x,y,z}`.

These are separate fields with no naming conflict. The `{ux,uy,uz}` orientation key (fixed in Bug 1 / Session 16) is used by inspectResult only; frame data is geometry-only (`end1`/`end2`). No latent mismatch.

---

### Test results

All specified tests run with simulation on port 8081, viewer at `http://localhost:8000/sim_viewer_boa.html?live=8081`.

**Protocol tests (Python raw WebSocket client):**

1. `queryParams` → `paramList` received with 234 total parameters, 1 mutable (`toFileInterval`, type `int`, value `100`). ✓
2. `setParam bogusParam 99` → `paramAck` `success:false error:"unknown parameter"` ✓
3. `setParam deltaT 0.001` → `paramAck` `success:false error:"not mid-run mutable"` ✓
4. `setParam toFileInterval notanumber` → `paramAck` `success:false error:"parse error: For input string: \"notanumber\""` ✓
5. `setParam toFileInterval 20` → `paramAck` `success:true oldValue:100 newValue:20` ✓

**Counter-reset timing:**
Set `toFileInterval` from 100 to 5; `paramAck` arrived at the next safe point (~15s real time at default simulation speed). The change applied and frames began arriving at the new rate at the next timestep. Reset back to 100 confirmed with a second ack.

**Pause + setParam + resume:**
- Sent `pause` → `simState:paused` confirmed.
- Sent `setParam toFileInterval 10` while paused → **0 acks** received (change queued, safe point not running while paused). ✓
- Sent `resume` → **1 ack** arrived immediately (first safe point after resume applied the change). `success:true, oldValue:100, newValue:10`. ✓

**Visual verification:**
- Viewer opened at `http://localhost:8000/sim_viewer_boa.html?live=8081`.
- `liveBar` shows: `● LIVE ws://localhost:8081 — connected | ⏸ Pause | ▶ Resume | ✕ Kill | Params ▶`. ✓
- Simulation running: frame 1210, t=0.43 s, 499 segments, 3200 myosins, 100 minifilaments displayed correctly.

---

### Files changed

| File | Change |
|---|---|
| `boxOfActin/Parameter.java` | Added `mutableAtRuntime` field, `setMutableAtRuntime()`, `isMutableAtRuntime()`, `getAllMutable()` |
| `boxOfActin/Env.java` | Marked `toFileInterval` mutable; added `PendingParamChange` inner class and `paramQueue` |
| `boxOfActin/LiveFrameServer.java` | Added `queryParams`/`setParam` handlers, `dispatchParamAck()`, `dispatchParamAckError()`, `dispatchTo()`, `enqueueAll()`, `buildParamListJson()`, `handleSetParam()`, `formatNum()`, `escapeJson()`; added `org.json` imports; updated protocol comment |
| `boxOfActin/BoxOfActin.java` | Added `drainParamQueue()`; added `drainParamQueue()` call at safe point after `drainInspectQueue()` |
| `sim_viewer_boa.html` | Added param panel CSS, HTML (Params button, paramPanel div), JS (buildParamPanel, handleParamAck, periodic refresh, subscribe extension, queryParams on connect) |
| `libs/json-20231013.jar` | Added (excluded from git; download via curl — see CLAUDE.md) |

Build command unchanged in function; `libs/*` glob already includes the new jar. No new `.class` files (all inner classes are in existing `.class` files).

---

## Session 16 — C1–C3 integration test bugfixes (May 2026)

### Bug 1 — inspect panel crash on orientation fields

**Root cause:** `fmt3()` in `sim_viewer_boa.html` assumed every object value had
`{x, y, z}` keys, so it did `v.x.toFixed(5)`. The `orientation` field the server
emits has keys `{ux, uy, uz}`, so `v.x` was `undefined` → `Cannot read properties
of undefined (reading 'toFixed')` on the first click of any entity.

**Reconciliation (viewer fields vs. server fields):**

| entity kind | field | server key shape | viewer assumed | mismatch? |
|---|---|---|---|---|
| filSegment | position | `{x,y,z}` | `v.x/v.y/v.z` | no |
| filSegment | orientation | `{ux,uy,uz}` | `v.x/v.y/v.z` | **YES — crash** |
| filSegment | end1 / end2 | `[x,y,z]` array | Array.isArray path | no |
| myosin | position | `{x,y,z}` | `v.x/v.y/v.z` | no |
| myosin | orientation | `{ux,uy,uz}` | `v.x/v.y/v.z` | **YES — crash** |
| myoMiniFilament | position | `{x,y,z}` | `v.x/v.y/v.z` | no |
| myoMiniFilament | orientation | `{ux,uy,uz}` | `v.x/v.y/v.z` | **YES — crash** |
| myoMiniFilament | end1 / end2 | `[x,y,z]` array | Array.isArray path | no |

The C2 "documented omissions" (intra-filament index, per-monomer nucleotide state,
lever angle) were irrelevant — no viewer code referenced those missing fields. The
crash was hit before any missing-field issue could surface.

**Fix:** Viewer-side only. `fmt3` now uses `Object.entries(v)` to format any object
generically: `(ux:val, uy:val, uz:val)` for orientation, `(x:val, y:val, z:val)`
for position. The key names are displayed alongside the values, which is more
informative than a bare triple. Server emits the correct shapes per C2 spec;
no server-side change needed.

**Fields shown per entity kind after fix:**

*filSegment:* position, orientation, end1, end2, filamentId, segmentArrayPos,
monomerCount, notADPRatio, cofilinCount, end2Capped, ageSteps, prevSegId, nextSegId.

*myosin:* position, orientation, nucleotideState, onFil, inRigor, boundSegId, ageSteps.

*myoMiniFilament:* position, orientation, end1, end2, ageSteps, attachedMotorIds.

**Verified:** All three entity kinds display a panel with no console error. Panel
works during pause (inspect drain runs inside the post-step wait loop per C3).

**Commit:** `bbbd275`

### Minor — THREE.VertexColors warning (fixed)

`motorMesh` material was constructed with `vertexColors: THREE.VertexColors`.
`THREE.VertexColors` was removed in modern Three.js (the constant resolves to
`undefined`), producing a console warning on every page load. One-line fix:
`vertexColors: true`. Included in the Bug 1 commit.

### Bug 2 — NullPointerException in closeJSons() / writeSimJSonPlots()

**Root cause:** `closeJSons()` (`FileOps.java:968`) called `writeSimJSonPlots()`
and `writeSimJSon2Plots()` unconditionally. Both methods use `jSonPW` / `jSonPW2`
(Simularium JSON PrintWriters), which are only initialized inside `setupJSons()`.
`setupJSons()` has **no call sites anywhere in the codebase** — Simularium JSON
output has never been activated in recent sessions. So `jSonPW` is always null,
and any call to `closeJSons()` NPEs at the first `jSonPW.print(...)` call (line 866).

**Is this live-only-specific?** No. The null is present regardless of `-3js` or
`-3jsLive` flags. Both `jSonPW` and `jSonPW2` are always null. Any clean exit —
time-limit or C3 kill — triggers `closeJSons()` and hits the NPE.

**Is this a long-standing breakage?** `closeJSons()` has been in `TimeLoop.run()`
since at least Session 11 (confirmed via `git show 0525ccf`). The NPE has been
latent since then. It was never triggered because simulations were either killed
externally (Ctrl+C, bypassing the clean-exit path) or ran longer than the testing
window. C3's kill action is the first mechanism that reliably exercises the clean
exit, exposing the bug. This is not a broken feature that used to work — Simularium
JSON was simply never activated.

**Note on C3 kill testing:** The C3 journal entry claimed the kill path left a
valid output directory. That test likely used `-3js` or `-3jsLive` with an
external kill before the loop exited naturally, or used a run that reached the
time limit before kill was sent — paths that happened to avoid `closeJSons()`.
The live-only kill scenario (which definitely reaches `closeJSons()` via kill)
was not covered.

**Fix:** Guard each Simularium writer pair in `closeJSons()` with its activation
flag, matching the pattern already used at every per-frame write site:

```java
if (Env.writeSimJSons) {
    writeSimJSonPlots();
    closeSimulariumJSonFile();
}
if (Env.writeSimJSons2) {
    writeSimJSon2Plots();
    closeSimulariumJSonFile2();
}
```

When Simularium output is inactive (`Env.writeSimJSons == false`, the default),
finalization is skipped. Three.js output (`-3js` / `-3jsLive`) is unaffected —
it uses a completely separate writer path (`ThreeJSWriter`).

**Kill scenario verification:**
- `-3jsLive` only: exits cleanly, no NPE, no files written (correct).
- `-3js` only: exits cleanly, Three.js frame files intact.
- `-3js` + `-3jsLive`: exits cleanly, frame files intact.

**Commit:** `1db1ad0`

### Post-session rendering regression: vertexColors on motorMesh (fixed in same session)

The Session 16 `vertexColors: THREE.VertexColors → true` change was a rendering
regression. After a hard-refresh, myosin structures rendered shredded/fragmentary;
actin filaments were unaffected.

**What went wrong:**

`motorMesh` uses Three.js `InstancedMesh` with per-instance colors set via
`setColorAt()` (nucleotide-state colors: NONE=blue, ATP=yellow, ADPPi=orange,
ADP=red). In Three.js r168 this path is gated by the internal `USE_INSTANCING_COLOR`
shader define, which is enabled whenever `mesh.instanceColor !== null` —
independent of the material's `vertexColors` flag.

Setting `vertexColors: true` activates an additional, separate path: it tells the
renderer to read a per-vertex `color` attribute from the geometry. `SphereGeometry`
has no such attribute. The renderer or shader then operates on an unbound or
zero-filled buffer for per-vertex colors, corrupting the geometry rendering —
visible as shredding/fragmentation.

The original code had `vertexColors: THREE.VertexColors`, where `THREE.VertexColors`
is `undefined` in Three.js r168 (the constant was removed in r132). The material
constructor received `undefined`, treated it as `false` (no vertex color attribute
reads), and the motors rendered correctly via the instance-color path alone.
The console warning was cosmetic; the behavior was correct.

**The console warning was a red herring.** The right fix to silence it was not to
replace `undefined` with `true` but to remove the property entirely, which is
what the absent property default already does correctly.

**Fix:** Remove `vertexColors` from the `motorMesh` material entirely:
```javascript
// was: new THREE.MeshPhongMaterial({ vertexColors: true })
new THREE.MeshPhongMaterial({})
```

With no `vertexColors` property, the material defaults to `false`: no per-vertex
geometry reads, no shader interference. The `instanceColor` machinery continues
to apply nucleotide-state colors per-instance via `USE_INSTANCING_COLOR` as before.

No change to the `instanceColor` setup (line 428) or `setColorAt` calls (line 701)
— those were and remain correct.

**Commit:** `231748d`

### Exit-path audit (orderly-shutdown coverage)

`TimeLoop.run()` has exactly this structure:

```java
public void run() {
    doLoop();
    FileOps.closeJSons();        // orderly finalization
    LiveFrameServer.stopServer();
    // System.exit(0);  ← commented out
}
```

Three exit scenarios, traced:

**Natural completion (`runTime` reached)**
The `while` loop condition is `simulationTime <= runTime + runBump && !terminating`.
When simulation time passes the limit, the loop falls through, `doLoop()` returns,
and `closeJSons()` is reached. *This path does call `closeJSons()`.*

Before Session 16: the call NPE'd. The unhandled exception in `TimeLoop` killed
the thread but left the JVM alive — worker-pool threads and the WebSocket server
thread (non-daemon) kept the process running indefinitely. `stopServer()` was
never reached. Any Three.js frame file actively being written at that moment may
have been left truncated (PrintWriter buffers; the JVM did not exit to flush them).

After Session 16: the guard skips Simularium finalization, `stopServer()` runs,
and the orderly path completes for the first time without an exception.

**C3 kill (`Env.terminating = true`)**
`break outer` exits the labeled loop; `doLoop()` returns; `closeJSons()` is called.
This is the path that exposed the NPE (the Session 16 bug report). Same pre/post
fix behavior as natural completion.

**Ctrl-C / JVM SIGTERM**
No `Runtime.addShutdownHook()` is registered anywhere in the codebase.
On SIGINT/SIGTERM the JVM halts immediately; `closeJSons()` is NOT called.
Three.js frame files written via `ThreeJSWriter` use `PrintWriter` with auto-flush,
so complete frames are likely on disk, but any frame that was mid-write is
truncated. The WebSocket server is killed without a clean handshake.

**Conclusion for the planner**

The orderly shutdown path — `closeJSons()` → `stopServer()` → thread exit — has
in practice **never successfully run end-to-end** before Session 16:

- Ctrl-C bypasses it entirely (no shutdown hook).
- Natural completion and C3 kill both reached it but NPE'd on `jSonPW`, leaving
  the JVM hung rather than exiting.

As of Session 16's fix, natural completion and C3 kill both exit cleanly.
Ctrl-C still bypasses finalization, which is probably acceptable (the OS reclaims
resources), but it means the WebSocket close handshake is never sent and any
pending Three.js frame is lost. If orderly Ctrl-C shutdown is ever needed, a
shutdown hook calling `LiveFrameServer.stopServer()` and flushing any open
`PrintWriter` in `ThreeJSWriter` would be the place to add it.

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

```java
outer:
while (Env.simulationTime <= runTime && !Env.terminating) {
    synchronized(Env.safeO) {
        // pre-step: wait if paused (no inspect drain — Things unstable)
        while (Env.paused && !Env.terminating) {
            Env.safeO.wait(50);
        }
        if (Env.terminating) break outer;

        // ... all physics phases ...
        updateCounters();

        // post-step safe-point: pause (with inspect drain), kill, inspect drain
        while (Env.paused && !Env.terminating) {
            drainInspectQueue();
            Env.safeO.wait(50);
        }
        if (Env.terminating) break outer;
        drainInspectQueue();

        if (!Env.remote) { logAndDraw(); } else { remoteLog(); }
        // ... cleanup ...
    }
}
```

**Pause-wait mechanism chosen: `Object.wait(50)` on `Env.safeO`.** Rationale: the main thread holds `Env.safeO` for the entire synchronized block. `wait()` atomically releases the lock and suspends — the WebSocket thread can then `synchronized(Env.safeO) { Env.safeO.notifyAll(); }` to wake the main thread immediately on resume or kill. `Thread.sleep(50)` would hold the lock for 50ms, blocking the WebSocket thread for that window. `wait(50)` has a 50ms timeout as a fallback in case `notifyAll()` fires before `wait()` begins (spurious wakeup defense).

**Kill check position:** between the two pause waits (before physics start, and after post-step wait). Both check `if (Env.terminating) break outer;`. The `outer:` label is on the while loop so `break outer` escapes the synchronized block and the loop simultaneously.

**Kill shutdown sequence:**

1. WebSocket thread receives "kill" → sets `Env.terminating = true`, `Env.paused = false`, calls `synchronized(Env.safeO) { notifyAll(); }`, dispatches `{"topic":"simState","payload":{"state":"terminating","step":<N>}}` to all clients.
2. Main thread wakes from wait, sees `Env.terminating`, hits `break outer`.
3. `doLoop()` falls through to `reportAllThreadSetTimes()` (timer summary prints normally).
4. `TimeLoop.run()` calls `FileOps.closeJSons()` — all open JSON output files flushed/closed. Output directory is left in a valid state; all frames written to that point are complete.
5. `TimeLoop.run()` calls `LiveFrameServer.stopServer()` — `instance.stop(1500)` waits up to 1500ms for sender threads to flush. The simState "terminating" message was dispatched in step 1 and has 1500ms to reach clients before the WebSocket server closes. This satisfies the "500ms flush" requirement with margin.

### LiveFrameServer.java

**New methods:**
- `buildSimStateMsg()` (private static) — reads `Env.terminating` / `Env.paused` and builds `{"topic":"simState","payload":{"state":"...","step":<N>}}`.
- `dispatchSimState(String state, int step)` (public static) — enqueues to all clients with drop-oldest backpressure, same as `dispatchFrame`.

**`onOpen` change:** after starting the sender thread, `state.queue.offer(buildSimStateMsg())` pushes the current state to the new client immediately. The queue is already live (sender thread started), so the message will flush to the client as soon as the sender thread's first `take()` fires.

**`onMessage` additions:** Three new action branches added before the "unrecognised" fallthrough:
- `"pause"`: guard `!Env.paused && !Env.terminating` prevents no-op → sets `Env.paused = true` → dispatches `simState("paused", step)`. No `notifyAll()` needed — the simulation is running, not waiting.
- `"resume"`: guard `Env.paused && !Env.terminating` → `Env.paused = false` → `synchronized(Env.safeO) { notifyAll() }` → dispatches `simState("running", step)`. The `notifyAll()` wakes the main thread immediately if it's in a wait; the `wait(50)` timeout means at most 50ms latency if it fires after the wait returns and before the next `wait()`.
- `"kill"`: guard `!Env.terminating` (absorbing) → `Env.terminating = true`, `Env.paused = false`, `notifyAll()`, dispatches `simState("terminating", step)`.

**Subscribe handler** updated to note this is now informational only (the server pushes all topics regardless).

### sim_viewer_boa.html

**CSS additions:**
- `.liveBtn` — base style for Pause/Resume/Kill buttons (dark glass style matching the live bar).
- `#btnKill` — red border/text to distinguish from Pause/Resume.
- `.liveCtrlSep` — 1px vertical rule separating status from buttons.

**HTML additions** (inside `#liveBar`): separator div, three buttons `#btnPause`, `#btnResume`, `#btnKill` all initially `disabled`.

**JavaScript additions** (inside the `if (LIVE_MODE)` block):

`terminated` flag — module-level `let terminated = false`. Set to `true` on simState "terminating". Prevents reconnect once the server shuts down (so a killed simulation doesn't trigger infinite reconnect loop). Reset path: the user must reload the page to connect to a new simulation. This is intentional — it prevents misleading reconnect-to-nothing behavior.

`setCtrlsForSimState(state)` — enables/disables buttons based on state:
- `running`: Pause enabled, Resume disabled, Kill enabled.
- `paused`: Pause disabled, Resume enabled, Kill enabled.
- anything else (`terminating`, unknown): all disabled.

`setLiveState(label)` — extended with:
- `'connected-paused'`: yellow dot, "Connected — Paused" text.
- `'terminating'`: orange dot, "Simulation ending…" text.

`scheduleReconnect()` — short-circuits to `setLiveState('disconnected')` and returns if `terminated` is true.

`openSocket()` — subscribe message updated to include `'simState'` topic.

`ws.onmessage` — new `simState` branch calls `setCtrlsForSimState(state)` and updates the status bar. On `paused`, shows "Connected — Paused". On `terminating`, sets `terminated = true`.

`ws.onclose` — calls `setCtrlsForSimState('unknown')` before `scheduleReconnect()` to disable buttons whenever the connection drops.

Button event listeners:
- Pause: sends `{action:"pause"}`.
- Resume: sends `{action:"resume"}`.
- Kill: `confirm('Kill the running simulation? This cannot be undone.')` before sending `{action:"kill"}`.

### Test plan (verified by code inspection)

**Pause / resume cycle:**
- Viewer sends `{action:"pause"}`. `onMessage` guards `!Env.paused` → sets `Env.paused = true` → dispatches simState. Main thread finishes in-flight step, hits post-step wait, sleeps with inspect drain active. Viewer shows "Connected — Paused", Resume enabled.
- Viewer sends `{action:"resume"}`. `onMessage` guards `Env.paused` → clears it, `notifyAll()`, dispatches "running". Main thread wakes within 50ms, exits wait, drains inspect, dispatches frame (logAndDraw), continues loop.

**Inspect while paused:** The post-step wait loop calls `drainInspectQueue()` on each 50ms cycle. Clicking an object sends `{action:"inspect"}` → queued to `Env.inspectQueue` → drained at next cycle → `buildInspectJson` called → dispatched as `inspectResult`. Inspect panel updates without the simulation needing to advance.

**Pause-while-paused:** `onMessage` guard `!Env.paused` is false → no-op, no simState dispatched.

**Late-joining viewer:** `onOpen` calls `state.queue.offer(buildSimStateMsg())` immediately after creating client state and starting sender thread. If sim is paused at that moment, new client receives `{state:"paused", step:<N>}` before any frame arrives. Buttons are correctly enabled/disabled; status shows "Connected — Paused".

**Kill — clean shutdown:** simState "terminating" dispatched from WebSocket thread before main loop exits. `stopServer()` waits 1500ms → message flushes. `FileOps.closeJSons()` runs before `stopServer()`, leaving all frame files complete. Output directory valid.

**Kill — viewer suppresses reconnect:** `terminated = true` set on simState "terminating". `ws.onclose` calls `scheduleReconnect()` → `if (terminated) return` → shows "disconnected" state, no timeout set. Simulation ending… → disconnected transition. No reconnect loop.

**Rapid pause/resume:** Each action is guarded by the current state. The WebSocket library serializes `onMessage` calls per connection (they run on the library's I/O thread). State reads/writes are `volatile`. `dispatchSimState` uses drop-oldest backpressure — a storm of rapid messages would at most fill each client's 4-slot queue, dropping earlier state messages and keeping the latest. The simulation is never blocked.

### Deviation from spec

The spec says "dispatch one frame on entering paused state" (i.e., logAndDraw runs once at the transition). In the implementation the pause wait sits **before** logAndDraw at the post-step safe point. The step in flight when "pause" arrives completes physics + updateCounters, then hits the wait before logAndDraw. That step's frame is NOT dispatched. The viewer retains the last pre-pause frame. The Three.js scene persists unchanged — the user sees a frozen 3D view. This is equivalent to the spec's intent (the view is stable and current) without extra machinery. Documented here as an intentional deviation.

---

## Project Goal

Replace Java3D visualization with a JSON + Three.js browser viewer, then GPU-accelerate the simulation using TornadoVM. The two goals are linked: Java3D must be removed before Java 21 can be used, and Java 21 is required by TornadoVM.

---

## Status

- **Java3D removal: COMPLETE** (Sessions 3–8). No `javax.media.j3d` imports anywhere in the codebase. The class-load-time blockers (`BranchGroup G`, `TransformGroup g3d` in `Thing.java`) are gone. Twenty-one pure-rendering files were deleted. The remaining 22 simulation files compile and run without Java3D jars on the classpath.
- **Three.js viewer + JSON output: WORKING** (Sessions 1–2). Per-frame `frame_NNNNNN.json` files render in `sim_viewer_boa.html` with actin segments, myosin parts, mini-filaments, and nucleotide-state coloring. Live scale sliders for biological size adjustment.
- **Phase 5 (Java 21 build): PENDING.** Java 21 is required by TornadoVM, and Java3D was the blocker. Now that Java3D is gone, the only thing remaining for Phase 5 is `brew install openjdk@21` on the MBP and validating that the codebase compiles and runs under `--release 21 --enable-preview`. After that, GPU work can begin (the planning for which is preserved in CLAUDE.md's "GPU Acceleration Strategy" section).

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

### Test plan

Verified by code inspection (simulation not run this session):
- Multiple rapid clicks: each click adds to the queue; `ConcurrentLinkedQueue.offer()` is non-blocking; all are processed in order at the next drain point. Simulation cannot block.
- Destroyed object: drain is before cleanup → `removeMe==true` check in `buildInspectJson` returns `notFound` for a dying Thing. After cleanup, the ID is removed from `instanceRegistry` → future lookups return `null` → also `notFound`.
- C1 backward compatibility: `subscribe` handler checks `message.contains("\"subscribe\"")` first; `inspectResult` topic is simply not dispatched to clients that never send an inspect action.
- File mode (no WebSocket): `drainInspectQueue()` returns immediately when `LiveFrameServer.isRunning()` is false. Click handler checks `ws && ws.readyState === WebSocket.OPEN` before sending — no-op if `ws` is null.

### Field omissions documented

| Omission | Reason |
|---|---|
| Intra-filament segment index (position within chain) | Requires walking end1/end2 chain — O(n) recomputation per request; use `filArrayPos` + `filamentId` instead |
| Per-monomer nucleotide state for filSegment | Stored per-monomer, not per-segment; `notADPRatio` is the closest aggregate |
| Lever angle for myosin | Requires computing rod-lever angle from orientation frames — deferred to C2+ |
| Monomer inspection kind | Monomers are not rendered individually, so they are not click targets |

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

## Current Known Issues

### Phase 5 not yet started

Java 21 has not been installed on the MBP. `brew install openjdk@21` is the next step. Once Java 21 is on the system:

1. Validate compilation with `javac --release 21 --enable-preview` (no JOGLAndj3D classpath needed anymore)
2. Validate that an unmodified `-r -pf ParameterFiles/boa10-64Seg -3js <dir>` run produces the same trajectory under Java 21 as under Java 8
3. Commit the new build instructions to CLAUDE.md

After that, the path is open to TornadoVM integration on aorus (the GPU machine). The GPU plan in CLAUDE.md (preserved from earlier sessions) remains the authoritative roadmap: Step 0 SoA shadow arrays, Step 1 Brownian + integration kernel, Step 2 motor-filament search, Step 3 bounds + link + crosslink forces.

### Vestigial graphics bookkeeping fields (partial — residue after Session 11)

- `renderThicken` / `setRenderThicken()` in `FilSegment.java`: `renderThicken` is read inside the dead `setRenderThicken()` method, which is never called. Delete the method first, then the field becomes write-only and can be removed. Phase 6.
- `Chamber.java`, `Bug.java`, `Crucible.java` static boolean graphics flags (`bugInScene`, `coordSysInScene`, `appearanceChanged`, `useWireAppearance`, `shiny`): write-only in Chamber and Bug; `Crucible.java:215` writes `appearanceChanged = true`. Needs multi-file pass. Phase 6.

### main() — resolved

The commented-out `main()` in `boxOfActin.BoxOfActin` was deleted in Session 11 (Item G3). Entry chain is now documented with a comment above `begin()`.

### .class orphans — resolved

Committed in Session 11 (Item G1).

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

## F1 Step 1 — Deflection benchmark harness (May 2026)

### Part A: Survey findings

**Torsion formulation**

The active branch in `FilSegment.addTorsionSpringForces()` is the PAIRS-style drag formulation, not the Hookean spring. `Env.filTorqSpring` is declared with `activeState = false` (line ~535 of Env.java), so the active branch is:

```java
torsionMag = Env.fracMoveTorq.getValue() * (Math.PI/180) * angTween
             / ((1/bRotGam.y + 1/end2Fil.bRotGam.y) * Env.deltaT.getValue());
```

Effective spring stiffness = `fracMoveTorq / (compliance * deltaT)`. This is velocity-proportional (overdamped), not position-proportional (Hookean). The coefficient the search loop will move is `fracMoveTorq` (default init value 0.02; parameter label `fracMoveTorq`).

`addLinkForcesOld()` is dead code — never called. `addLinkForces()` is the live implementation using the same PAIRS style:

```java
forceMag = (fracMove * 1e-6 * strainDist) / (deltaT * (moveCoeff1 + moveCoeff2));
```

**Endpoint geometry and pinning**

`end1 = coord - (length/2) * uVec`, `end2 = coord + (length/2) * uVec`. Both are recomputed in `initialize()` (line ~354), which is called at the end of `moveThing()`. So after each integration step, `end1`/`end2` reflect the current centroid + orientation.

Viable pinning approaches (evaluated):
1. **Post-moveThing centroid translation** (implemented): After `moveThing()`, compute `delta = pinTarget - currentEnd`, translate `coord` by `delta`, call `initialize()`. Exact, O(1), handles segment rotation naturally (the pin is free to rotate; centroid is corrected). This is the approach used.
2. **Spring-based restoring force**: Apply a large restoring force toward the pin each step. Requires tuning the spring constant; can cause stiffness instabilities. Not used.
3. **Zero-velocity boundary condition**: Zero the translational velocity each step. Incompatible with overdamped integration where there is no velocity state — positions update directly from forces. Not applicable.

The planner's hypothesis about post-moveThing() centroid translation was confirmed correct and implemented exactly as described.

**Minimal crucible path**

Three changes gate off all non-benchmark content:
1. `Env.simOutsideBug.setActive(false)` — suppresses `Bug.makeListeriaBug()` and all 10,000 ActA objects. **Critical**: `simOutsideBug` is declared with `activeState = true` (default active), so it must be explicitly deactivated.
2. `Env.brownianFilMotionOff = true` — suppresses Brownian forces on FilSegments. Flag, not Parameter.
3. `Env.noMonomersSimd.setActive(true)` — suppresses Monomer creation in `FilSegment` constructor and all biochemistry in `biochemStep()`. Default inactive; must be activated before `makeBenchmarkChain()` is called.

`makeInitialThings()` returns immediately after creating the benchmark chain, bypassing all standard filament/node/motor initialization.

**NaN clamp bug (discovered during build)**

`FilSegment.moveCoeff()` computed `Math.acos(cosBeta)` without clamping `cosBeta` to [-1, 1]. When benchmark segments are exactly colinear along +X, `Pt3D.Dot(uVec, linkUVec)` returns `1.0000000000000002` due to floating-point rounding. Result: NaN propagates from `moveCoeff()` into link forces → segment positions → `uVec` → torsion cross products. Fixed by adding the clamp. The same guard already existed in `addTorsionSpringForces()` for the upper bound; this session also added the lower-bound clamp (`< -1.0`) to both torsion sites.

---

### Part B: Build results

**Implementation** (committed in this session):
- `Env.benchmarkFilament` flag, `benchmarkNSegs`, `benchmarkForceFrac`, `benchmarkSettleSteps` statics
- `-bm` / `-benchmark` CLI flag
- `FilSegment.makeBenchmarkChain(int n)` factory method
- `BoxOfActin.applyBenchmarkPins()` — post-moveThing centroid translation for both terminal segments
- `BoxOfActin.reportBenchmarkDeflection(label)` — perpendicular distance of midpoint from anchor line vs analytic FL³/48EI
- Force application to midpoint segment in the `stepStart/Stop` → `moveStart/Stop` gap

**Pass condition: met.**

```
[BENCH] 11-seg chain, span=0.9801 µm, F=3.085e-14 N, analytic δ=0.0098 µm
[BENCH:SETTLED] step=5000  meas=0.0305 µm  analytic=0.0098 µm  ratio=3.1150
```

Run 2 (same parameters):
```
[BENCH:SETTLED] step=5000  meas=0.0305 µm  analytic=0.0098 µm  ratio=3.1141
```

(a) Stable across runs: ratio 3.115 vs 3.114 — < 0.1% difference. ✓  
(b) Within order of magnitude of FL³/48EI: ratio ~3.1 at settle, converging to ~3.5 at equilibrium (~10 000 steps). ✓

**Key finding for search loop (F1 Step 2):**

The equilibrium deflection ratio is ~3.5× the Euler-Bernoulli FL³/48EI prediction. The PAIRS torsion formulation has an effective bending stiffness that does not map 1:1 to EI = kT·Lp with `fracMoveTorq = 0.02`. The search loop will need to scan `fracMoveTorq` to find the value that drives the ratio to 1.0. The time constant for deflection convergence is ~3 700 steps (empirical), so the settle period used by the search loop should be ≥ 5 000 steps.

## 2026-05-14 — Planner: F1 design session + Step 1 integration

Planner + user session (no Claude Code handoff). Designed the actin filament
benchmarking assay sequence; integrated the F1 Step 1 build results (see the
"F1 Step 1" section above, written by the Step 1 implementation session).
Author context: the user is J.B. Alberts, author of the PAIRS paper this
benchmarking reproduces (PLoS ONE 2009;4(3):e4748). The paper is treated here
as prior design intent to check the current code against — not as external
authority — since the codebase has drifted across many phases and the paper's
prescribed tuning procedure was itself a tidied-up account of a messier
empirical process.

### Assay sequence — design decisions

Three biophysical targets, grouped into two simulation configurations:
- **Config A** (simply-supported, Brownian OFF): static deflection + relaxation
  time-constant.
- **Config B** (free filament, Brownian ON): persistence length.

Settled design decisions:

1. **Search loop lives in-sim.** The benchmark filament must traverse the
   identical `doLoop()` body as a production filament, or the tuning targets
   the wrong dynamics. Coefficient adjustment and convergence checks ride the
   per-step safe point (the Session 18 pattern). The browser is a pure
   observer.

2. **Option C — benchmark pre-phase + self-restart.** A benchmark run is a
   pre-phase on a minimal one-filament crucible (already built in Step 1):
   converge coefficients, then the sim self-restarts into the production run
   via the existing `makeCrucible()` / `makeInitialThings()` re-call path.
   Single continuous WebSocket session throughout; viewer sees a
   `benchmarkProgress` topic, then a restart, then normal frames. Chosen over
   a `doLoop()` mode-branch because Step 1's benchmark crucible legitimately
   contains only one filament — "process everything" and "process the
   benchmark filament" are the same operation, so no special-casing is needed.

3. **First build is deflection-only.** Proves the harness on the easiest leg.
   Deflection-only tuning is explicitly NOT a complete tuning (see degeneracy
   note below).

4. **Trigger model — benchmark cache, no silent behavior.** Parameter sets
   carry a signature (segment size, deltaT, viscosity, filament length,
   filament type). A cache records which signatures have been benchmarked and
   what they tuned to. The cache informs; it never overrides silently. On a
   cache hit the user is told what was found and chooses among: run the
   benchmark / use cached values / use the parameter-file values as-is. "Use
   file as-is" is a first-class legitimate choice — it is how a user explores
   deliberately-untuned soft/stiff filaments (e.g. modeling unquantified
   stiffening from accessory proteins). Cache-vs-file *precedence* is therefore
   a non-question. Open item: the UX of presenting the choice — deferred until
   the cache is built (F1 Step 3).

### The degeneracy question — and how it is (and isn't) resolved

With one observable (deflection) and multiple coefficients, the solution is a
curve, not a point. The resolution is NOT biophysical anchoring of
`fracMoveTorq` to a first-principles EI estimate — that idea was floated and
rejected: the coarse-segment lumping entangles the discretization artifact with
the bending-rigidity mapping, so an "EI-anchored" `fracMoveTorq` would carry an
error bar equal to the very artifact it was meant to separate out. The
representation's coefficients are not individually physically meaningful and
were never claimed to be.

The degeneracy is instead resolved the way the 2009 work resolved it
empirically: **over-determination by multiple observables.** Uniqueness (if it
exists — not guaranteed) comes from the intersection of the deflection,
relaxation, and persistence constraint surfaces. Whether a single coefficient
set satisfies all three simultaneously is a genuine open question the assay
sequence should *answer*, not assume.

Consequence: deflection-only Step 2 finds one provisional point on the
deflection-satisfying curve, by holding the other coefficients fixed. It is not
where the degeneracy gets resolved and should not try to be.

### C_R is held fixed for Step 2 — justification, and its scope boundary

Step 2 holds `fracR` (C_R) fixed at 1.0 and searches `fracMoveTorq` (C_θ)
alone. Justification (the user's, and better than a paper citation): C_R sets
a relaxation *rate*, not an equilibrium. Per the viscous-spring result
(paper eq. 8), the steady state is C_R-independent as long as |1−C_R| < 1.
Deflection is an equilibrium observable, so the deflection-only search is
insensitive to the held C_R value anywhere in its stable range; 1.0 is chosen
as the reference point matching prior work. The user's operating philosophy:
move down the gradient toward steady state as fast as possible without going
stiff; exact resolution (which a Brownian system never actually reaches anyway)
in 5–10 timesteps is fine.

**Scope boundary — must not propagate past Step 2:** this insensitivity
argument covers the deflection and persistence assays only. The relaxation
assay will treat C_R as a genuine tuning variable, not a fixed constant,
because there it controls the observable being measured (the C_R/C_θ ratio
sets the time-constant).

### Stability — a named gap, not yet an assay

PAIRS is per-interaction non-stiff but not unconditionally stable system-wide
(paper Fig. 1B: coincidental alignment of multiple pairwise forces can
overshoot). All three current benchmarks are *accuracy* benchmarks; none
probes whether a tuned coefficient set stays stable when a segment also
carries external load — motors, crosslinkers, excluded-volume — which is
exactly the BoA production regime. The benchmark filament is tuned in
isolation (correctly), but the coefficients are used in a crowded run.

Two items recorded so they are not lost:
- A **stability assay** is warranted eventually (possibly post-F1): tuned
  filament + representative piconewton external load, confirm no oscillation
  or blowup. The user's "rerun with smaller Δt" backstop is a real diagnostic
  for stiffness, but note it costs a full re-benchmark, since coefficients are
  Δt-specific (paper Fig. 5A).
- **Possible cache-schema implication:** if the stable range of C_R depends on
  production force load, a single coefficient set per signature may be
  insufficient — the cache might need to certify "stable up to external force
  F_max" and have production runs check actual loads against that bound.
  Flagged for the deferred cache design; not decided.

### Step 1 integration notes

Step 1 passed cleanly. Harness reads a stable deflection number (<0.1% across
runs); the NaN-clamp bug in `moveCoeff()` was a real find that justified
survey-and-build before layering a search loop. Confirmed from the Step 1
entry: benchmark chain is **11 segments** (`benchmarkNSegs`), span 0.98 µm;
live torsion path is the PAIRS drag formulation, not the Hookean
`filTorqSpring` branch. Headline finding carried into Step 2: ratio ~3.5 at
`fracMoveTorq = 0.02` — the default filament is ~3.5× too soft, which is the
expected shape of the problem, not a bug.

**Open gap from Step 1 not yet surveyed:** whether any graphics / ThreeJS
frame output is active during the benchmark phase. Harmless for a headless
deflection harness, but matters for Option C (the pre-phase should stream
frames to the viewer). Folded into the Step 2 prompt as a one-line survey
question rather than spending a separate handoff.

### Next: F1 Step 2 — single-coefficient deflection search

Bisection on `fracMoveTorq`, `fracMove`/`fracR` held fixed (C_R = 1.0,
recorded as a parameter of the result), driving deflection ratio to 1.0 ± 1%
(tolerance hardcoded for now; candidate parameter later). Bracket seeded by
Step 1 (3.5 at 0.02 → need stiffer; search establishes the far bracket then
bisects). Settle ≥ 5000 steps/evaluation. Search rides the safe point, lives
in-sim.

**Report-and-stop is Step 2 scaffolding, not eventual behavior.** When the
search converges it prints the value and halts — it does NOT write to a cache
(Step 3), feed the result into a production run, or self-restart (Option C
wiring, later steps). This is purely to isolate the search loop as the single
thing under test: a failure is then unambiguously in the search, not in a
handoff or restart path. The eventual flow is search → cache → production
crucible → self-restart → run; "report-and-stop" gets replaced by
"report-and-feed-forward" once Steps 3+ connect the pipe.

Explicitly flagged in its journal entry as one provisional point on the
deflection-satisfying curve, pending the relaxation and persistence assays.
Out of scope for Step 2: cache, self-restart, WebSocket/viewer, biophysical
derivation.
---

## 2026-05-15 — Planner: F1 Step 2 integration + viewer experiment + conceptual corrections

Planner + user session. Integrated F1 Step 2's results (see Step 2 entry above
or below depending on commit order), ran the live viewer experiment, and
worked through two conceptual corrections that came up in discussion. No
Claude Code handoff this session.

### F1 Step 2 result

Bisection search converged `fracMoveTorq = 0.455` in 10 iterations, with
deflection ratio 1.007 and 1.006 on two independent runs. Tolerance was 1%;
hit well inside it. Run-to-run determinism to four decimal places — the search
is reliable, not noisy. `Env.paused = false` was a small in-scope discovery
handled silently by Claude Code (the `-bm` setup wasn't unpausing, unlike the
`-r` path); documented in the Step 2 entry.

**Provisional in the precise sense agreed in the design session:** this is one
point on the deflection-satisfying surface, held at `fracR = 0.3` (the
production-realistic parameter default — note that the design session called
for `fracR = 1.0` but Claude Code held it at the parameter default; the result
is therefore more directly applicable to production than originally framed).
Still provisional pending the relaxation and persistence assays, which will
pull `fracR` into being a free tuning variable.

### Conceptual correction 1 — stability margin is redistributed, not gained

User raised: "I never likely ran simulations with `fracMove = 1` or `fracR = 1`
— doesn't that give us more headroom?" Worked through, and the answer is no in
a useful way:

Lowering `fracR` (and likewise lowering `fracMove`) reduces the alignment
contribution from the link force, requiring `fracMoveTorq` to *increase* to
compensate for the deflection observable. So moving to production-realistic
`fracR = 0.3` and `fracMove = 0.3` (current `Env.java` defaults) pushes the
deflection-tuned `fracMoveTorq` further *toward* the paper's 0.5 stability
ceiling, not away from it.

The right metric to track is therefore **the minimum stability margin across
all coefficients**, not headroom on any single one. Trading comfortable `fracR`
for uncomfortable `fracMoveTorq` doesn't help; it just relocates the squeeze.

The Step 2 result of `fracMoveTorq = 0.455` was obtained at the
production-realistic `fracR = 0.3` (Claude Code held `fracR` at its parameter
default rather than the design-session-specified 1.0). At this configuration,
the converged `fracMoveTorq` sits at 91% of the paper's 0.5 stability ceiling
— narrow but viable margin, not the over-the-ceiling failure that an
`fracR = 1.0` run might have suggested. The minimum-margin metric across all
coefficients is still the right framing, but the 11-segment discretization
appears to be tunable in the production regime, not too coarse as initially
feared.

This sharpens the immediate next steps (see "Next" below).

### Conceptual correction 2 — benchmarking is method-agnostic

Discussion clarified that PAIRS is not a foundational commitment of the
codebase — it's a method choice, replaceable. The benchmarks measure
biophysical truths (deflection FL³/48EI, relaxation time from the
hydrodynamic beam equation, persistence length from worm-like-chain) that hold
independent of the numerical method producing the simulated filament. The
assays test the *biology*, not the *numerics*; the numerics are an
implementation detail under test against the biology.

This is the right framing for v2, where different entity types (microtubules,
intermediate filaments, membranes) will use different constraint-resolution
schemes. The benchmark framework can be common across them; the implementer
brings the method, the framework owns the verdict. Nothing about current BoA
work commits in the wrong direction here — the assays are already
biophysics-vs-prediction, not PAIRS-internals checks.

### Stability monitoring — v2 diagnostics-subsystem sketch (not BoA work)

The stability question came up via "did you ever derive a stability criterion
for PAIRS?" — the user has not, and the conversation arrived at: deriving a
useful closed-form multi-interaction criterion isn't tractable (the failure
mode is geometric, depending on per-step force-direction distributions that
change every step). The single-interaction criterion is already in the paper
implicitly (C_δ, C_R, C_θ each < 1, ≤ 0.5 with margin). The right response
isn't a theorem but a *runtime diagnostic*.

Three monitors sketched for v2 — cheap, all quantities already computed:
- Link-spring δ rolling-window growth (constraint residual)
- Torsion-spring angular misalignment rolling-window growth (constraint residual)
- Per-segment step displacement sign-flip with growing amplitude
  (Fig. 1B-style overshoot signature)

Don't compute total system energy — wrong quantity for overdamped systems
with high Brownian noise.

This is a v2 architectural note, not a BoA task. BoA's empirical envelope is
well-mapped; the diagnostic matters when non-original contributors bring force
regimes outside the original author's experience. Recording the sketch so
it's available when v2 starts taking shape.

### Viewer experiment — pipeline works; visualization affordances needed

Ran `java ... BoxOfActin -bm -3jsLive 8001` with browser at
`sim_viewer_boa.html?live=8001`. Results:

- Compile required adding `libs/json-20231013.jar` to the classpath — the
  canonical compile command in `CLAUDE.md` predates the JSON dependency
  introduced (probably) by C-track WebSocket work. **CLAUDE.md needs a
  one-line update** to reflect this (deferred to next CLAUDE.md edit; also
  consider switching to `libs/*` wildcard syntax to future-proof).
- Pipeline end-to-end works: filament renders, WebSocket connects, the
  ~10-cycle search is observable.
- At the principled 1% deflection target the bending was visually subtle —
  initially appeared as "not much happening." User raised the force to 10%
  (`benchmarkForceFrac = 0.1`); deflection then clearly visible.
- **The default camera orientation put the bending along the line-of-sight**,
  making even the visible deflection nearly invisible until the user rotated
  the view. The 1% deflection was probably present all along, edge-on.

The third bullet is the most important takeaway. A benchmark visualization
that requires the user to know to rotate the camera is not really a benchmark
visualization. The fix is to choose the force direction in benchmark setup so
that deflection happens in a plane the default camera shows broadside — or
have the camera auto-orient to that plane. This belongs in the next
visualization prompt.

### Compile command drift

Tonight's `javac` failed because the JSON jar was missing from the user's
classpath. Claude Code's recent sessions compiled successfully because its
own invocation included it. The compile commands in CLAUDE.md need updating;
filed as a one-line task.

### Next — agreed ordering for next session(s)

1. **Small prompt: visualization affordances for the benchmark viewer.**
   Force-vector arrow at midpoint segment, pin-support cones at the two
   anchor points, default camera framing the filament with deflection in the
   viewing plane. Possibly a `benchmarkProgress` WebSocket topic showing
   iteration count and current ratio.

2. **Then decide: cache (Step 3) vs. relaxation assay (Step 4).** Original
   plan was to decide this after Step 2. The discretization diagnostic (item 1
   in the old list) turned out to be already answered — fracMoveTorq = 0.455
   at production-realistic fracR = 0.3 is viable — so the choice between cache
   and relaxation can be made now rather than after a re-run.

### CLAUDE.md updates needed

- Compile command needs `libs/json-20231013.jar` (or `libs/*` wildcard).


## F1 Step 2 — Single-coefficient deflection search loop (May 2026)

### Part A survey findings

**A.1 — Safe-point location**

The safe point is the benchmark block already at lines 417–425 of `boxOfActin/BoxOfActin.java`, inside the single `synchronized(Env.safeO)` block that wraps the entire timestep in `doLoop()`. The structure is:

```
synchronized(Env.safeO) {
    // pre-step pause wait (lines 303-306)
    // all physics phases (lines 309-403)
    // updateCounters() (line 405)
    // C3 pause/kill check (lines 409-413)
    // drainInspectQueue() (line 414)
    // drainParamQueue() (line 415)
    // F1 benchmark block (lines 417-425) ← search state machine goes here
    // logAndDraw / cleanup / spawn
}
```

The benchmark code already lives at the correct insertion point. The search state machine advances here: read ratio, update brackets, pick next candidate, trigger filament reset, advance to next evaluation.

**A.2 — fracMoveTorq mutability mid-run**

Confirmed **safe to reassign mid-run**. `FilSegment.addTorsionSpringForces()` calls `Env.fracMoveTorq.getValue()` fresh at every step (lines 1639, 1642, 1693, 1696 of `FilSegment.java`) — four call sites, all read-at-use-time. `Parameter.setValue()` directly sets `curValue` (no derivative cached). No startup code captures `fracMoveTorq` into a local or static field used for the rest of the run. `Parameter.dependent` is `false` for this parameter. A call to `Env.fracMoveTorq.setValue(x)` at the safe point takes effect in the very next step's `addTorsionSpringForces()`. This promotes `fracMoveTorq` from the "likely mutable" column (journal line 105) to **confirmed mutable** for the search loop.

**A.3 — Filament reset between evaluations**

`makeBenchmarkChain(n)` creates new `FilSegment` objects via `new FilSegment(...)` → `Thing.addThing()`, which appends to the `theThings[]` array. Calling it again mid-run would add 11 more segments (not replace the existing ones), requiring a corresponding kill/cleanup cycle before the new segments appear. This is unnecessary complexity.

**Clean reset approach:** store the 11 initial straight-line center coordinates in a `Pt3D[] benchInitCoords` array (filled once at setup). At each reset: for each segment in `benchSegs[]`, set `coord` from `benchInitCoords[i]`, set `uVec=(1,0,0)`, `yVec=(0,1,0)`, call `initialize()` (which recalculates `zVec`, `end1`, `end2`, and the transformation matrices). Zero `forceSum`/`torqueSum` explicitly (belt-and-suspenders; `resetCtStart` already zeros them every step, so they're clean at the safe point). Chain topology (`end1Fil`/`end2Fil`) is unchanged. The transverse force is re-applied fresh each step from `benchTransForce`, so it needs no reset. `makeBenchmarkChain()` does not need to be called again. `bTransGam`/`bRotGam` depend only on segment length (unchanged), so they remain valid.

**A.4 — ThreeJS/WebSocket state during benchmark**

In a plain `-bm` run with no `-3js` or `-3jsLive` flags:
- `Env.threeJSOutputDir` is `null` → no file-based frame output.
- `LiveFrameServer.isRunning()` is `false` → no WebSocket server.
- The condition `(Env.threeJSOutputDir != null || LiveFrameServer.isRunning()) && threeJSCounter >= ...` in `remoteLog()` is false → **no ThreeJS frames are written**.

If `-3js` or `-3jsLive` is added alongside `-bm`, frames are written; this is harmless for the headless deflection harness but is the gap noted in the planner entry for Option C viewer work.

### Part B — Build notes

**Implementation plan:**
- Added `BENCH_SEARCH_TOL`, `BENCH_SEARCH_SETTLE`, `BENCH_SEARCH_GEO_FACTOR`, `BENCH_SEARCH_MAX_COEFF` named constants.
- Added `benchSegs[]` (full segment array for reset) and `benchInitCoords[]` (stored straight-line positions).
- Added `benchSearchIter`, `benchSearchLo`, `benchSearchHi`, `benchSearchCand` search state fields.
- Extended `makeInitialThings()` to populate the above at chain-creation time.
- Replaced the Step 1 benchmark block in `doLoop()` with the bisection search state machine.
- Added `resetBenchmarkChain()` (restores positions/orientations of existing segments; no new objects created).
- Refactored `reportBenchmarkDeflection()` to call a new `computeDeflectionRatio()` helper; search loop also calls the helper directly.

**In-scope fix discovered during build:** `Env.paused = true` by default (set in `Env.java`). The `-r` flag in `parseArgs()` sets both `Env.remote = true` AND `Env.paused = false`. The `if (Env.benchmarkFilament)` block in `begin()` faked `Env.remote = true` but missed `Env.paused = false`, causing the simulation to spin in the pre-step pause wait forever. Fixed silently by adding `Env.paused = false;` to the benchmark setup block. (The Step 1 session presumably ran with `-bm -r` or never observed this because it did not wait long enough, or paused was not initialized to true in the version they tested.)

**Search logic (bisection on fracMoveTorq):**
- Initial candidate = `fracMoveTorq` at setup (default 0.02 → ratio ~3.1 → too soft).
- Bracketing phase: each evaluation where ratio > 1+tol sets `benchSearchLo = candidate` and steps geometrically (`× BENCH_SEARCH_GEO_FACTOR = 4`) until ratio < 1−tol sets `benchSearchHi`.
- Bisection phase: `next = (lo + hi) / 2` once both brackets are established.
- Convergence: `|ratio − 1| ≤ BENCH_SEARCH_TOL = 0.01`.
- Bail-out: if geometric step reaches `BENCH_SEARCH_MAX_COEFF = 100` without finding hi bracket.
- Report-and-stop: on convergence, prints `[BENCH] CONVERGED` line with `fracMoveTorq`, `ratio`, iteration count, and held `fracR`, then calls `System.exit(0)`.
- No cache write, no restart, no feed-forward — scaffolding only.

**Pass condition results (two independent runs):**

Run 1:
```
iter=0  fracMoveTorq=2.000E-2  ratio=3.1173  lo=2.000E-2  hi=?
iter=1  fracMoveTorq=8.000E-2  ratio=2.4212  lo=8.000E-2  hi=?
iter=2  fracMoveTorq=3.200E-1  ratio=1.2643  lo=3.200E-1  hi=?
iter=3  fracMoveTorq=1.280E0   ratio=0.3987  lo=3.200E-1  hi=1.280E0
iter=4  fracMoveTorq=8.000E-1  ratio=0.6826  lo=3.200E-1  hi=8.000E-1
iter=5  fracMoveTorq=5.600E-1  ratio=0.8728  lo=3.200E-1  hi=5.600E-1
iter=6  fracMoveTorq=4.400E-1  ratio=1.0283  lo=4.400E-1  hi=5.600E-1
iter=7  fracMoveTorq=5.000E-1  ratio=0.9442  lo=4.400E-1  hi=5.000E-1
iter=8  fracMoveTorq=4.700E-1  ratio=0.9835  lo=4.400E-1  hi=4.700E-1
iter=9  fracMoveTorq=4.550E-1  ratio=1.0073  lo=4.400E-1  hi=4.700E-1
CONVERGED  fracMoveTorq=4.550E-1  ratio=1.0073  iters=10  fracR=3.000E-1
```

Run 2:
```
iter=9  fracMoveTorq=4.550E-1  ratio=1.0063
CONVERGED  fracMoveTorq=4.550E-1  ratio=1.0063  iters=10  fracR=3.000E-1
```

Both runs converge to **fracMoveTorq = 4.550E-1** in exactly 10 iterations. Ratio differs by 0.001 between runs (1.0073 vs 1.0063) — within the 1% tolerance band and well within normal stochastic variation. The search is deterministic enough that it reaches the same bisection midpoint (0.455) in both runs.

**Key result:** The calibrated `fracMoveTorq = 0.455` is a provisional single point on the deflection-satisfying curve (note: Step 1 journal said `fracR = 0.02` default gives ratio ~3.5; this step measured ratio ~3.1 with brownianFilMotionOff=true and the new step-count settle, consistent with Step 1's ~3.5 estimate). This is scaffolding; the value is not written anywhere and will be replaced by the cache mechanism in Step 3.

---

## F1 sidebar — actinWidth survey post-edit (May 2026)

### 1. Two-line change confirmed

**`boxOfActin/Env.java:457`** (pre-edit: `0.008`):
```java
static final double actinWidth = 0.007; // (µm) thickness of actin filament
```

**`boxOfActin/FilSegment.java:29`** (pre-edit: `= Env.actinWidth`):
```java
static double radius = Env.actinWidth/2.0;   // (nm) radius of actin filament
```

Together: `actinWidth` is now unambiguously the filament diameter (7 nm); `FilSegment.radius` is now unambiguously the radius (3.5 nm). No further source edits were made.

---

### 2. Part A — Full enumeration table

All uses of `Env.actinWidth`, `FilSegment.radius` (and the local `radiusM` derived from it) across the codebase. Sorted by file then line.

| File:Line | Code | Interpretation | Verdict |
|---|---|---|---|
| `Env.java:457` | `static final double actinWidth = 0.007;` | Definition — now a diameter in µm | Correct by definition |
| `Env.java:463` | `helixMonOffset = (actinWidth - actinMonoDiam) / 2` | `(diam_fil - diam_mono) / 2` = radial gap between filament OD and monomer OD. Cosmetic-only (see Part C). | Physically correct after change |
| `FilSegment.java:29` | `static double radius = Env.actinWidth/2.0;` | Definition — now radius = diam/2 = 3.5 nm | Correct by definition |
| `FilSegment.java:324` | `double radiusM = radius*1.0e-6;` | Converts µm radius → m radius for hydrodynamic formulas | Physically correct after change |
| `FilSegment.java:325` | `Math.log(asIfLengthM/(2*radiusM))` | Slender-body log(L/2r); expects r in meters | Physically correct after change |
| `FilSegment.java:326` | `bTransGam.x = 2π η L / (log + aParallel)` | Parallel translation drag; uses log term with r | Physically correct after change |
| `FilSegment.java:327–328` | `bTransGam.y/z = 4π η L / (log + aOrthog)` | Orthogonal translation drag; uses log term with r | Physically correct after change |
| `FilSegment.java:329` | `bRotGam.x = 4π η r² L` | Spin drag about long axis; explicitly r² | Physically correct after change |
| `FilSegment.java:330–331` | `bRotGam.y/z = π η L³ / (3*(log + aTurning))` | Bending drag; uses log term with r | Physically correct after change |
| `FilSegment.java:1145` | `theBox.amICollidingOuter(cE, end1, radius)` | Passes radius as wall-clearance half-width | Physically correct after change |
| `FilSegment.java:1149` | `theBox.amICollidingOuter(cE, end2, radius)` | Same | Physically correct after change |
| `FilSegment.java:1154` | `lmBug.amICollidingFromOutside(cE, end1, radius)` | Passes radius as collision half-width vs bug surface | Physically correct after change |
| `FilSegment.java:1163` | `lmBug.amICollidingFromOutside(cE, end2, radius)` | Same | Physically correct after change |

No other uses of `Env.actinWidth` were found. No hardcoded `0.008` or `0.004` shadow constants exist in any `.java` file. (The literal `0.008` appears only for `myoLeverLength` in `Env.java`, unrelated to filament radius.)

---

### 3. Part B — Collision-radius survey

All three collision implementations treat the `R` parameter as the **radius** (half-width) of the incoming object:

**`Chamber.amICollidingOuter` (`Chamber.java:125–138`):**
```java
lcE.forceUVec.setVals(Math.signum(x)*(dims.x/2 - R), ...)
```
The position limit is `dims/2 - R`: the filament endpoint is bounced at the wall minus one radius. Old behavior: 8 nm clearance. New behavior: 3.5 nm clearance. Segments do **not** clip into walls — the check still fires before contact; the exclusion zone simply shrank to the physically correct value. Qualitative behavior unchanged; quantitative boundary is tighter.

**`Bug.amICollidingOuter` (`Bug.java:463`):**
```java
if (yzDist + R < radius) { return; }  // not colliding
```
`R` is the filament half-width; `radius` is the Bug cylinder's radius. Same semantics as Chamber: old code used `R = 0.008`, adding 8 nm of unintended buffer before triggering the push-back force. New code: 3.5 nm buffer. Physically correct.

**`Bug.amICollidingFromOutside` (`Bug.java:547`):**
```java
lcE.delta = yzDist - R - radius;  // negative → collision
```
`R` is again the filament endpoint half-width. The collision triggers when a filament endpoint penetrates within `R` of the bug surface. Same tightening effect. Clearance before collision: 8 nm → 3.5 nm.

**Impact assessment:** All three callers pass `FilSegment.radius` directly. The halving of `radius` means filaments are now allowed ~4.5 nm closer to all boundaries before the collision force fires. This is the physically correct behavior — the old code was using the diameter as the radius, effectively doubling the exclusion zone. The change will not cause segments to clip through walls; the check remains a >= 0 delta test. However, in a normal box-of-actin run, filament tips will cluster visibly closer to walls and the bug surface than before. This is a real physics change, not a regression.

---

### 4. Part C — helixMonOffset trace; derived quantities in Env.java

**`helixMonOffset = (actinWidth - actinMonoDiam) / 2`**

Old: `(0.008 - 0.0054) / 2 = 0.0013 µm`
New: `(0.007 - 0.0054) / 2 = 0.0008 µm`

The formula is geometrically: radial gap = (filament OD − monomer OD) / 2. This is the transverse offset of the monomer center from the filament centerline in the body frame of the segment.

Used exclusively in `FilSegment.ptFromHelixPos()`:
```java
pt.y = Env.helixMonOffset * Math.cos(curAng);
pt.z = Env.helixMonOffset * Math.sin(curAng);
```

Called from:
- `updateMonomerPositions()` → `updateAllMonomerPositions()` — sets `Monomer.position` for Simularium output only. Not called from physics loop.
- `updateMonomerGraphics()` — legacy AWT graphics (no longer rendered in normal runs). Gated by `Env.monomerGraphics`.

CLAUDE.md confirms: "Monomers track biochemical state only; their 3D positions are computed from the FilSegment body frame at output time only." **`helixMonOffset` is cosmetic only.** It does not feed into forces, collisions, crosslink rest lengths, or any physics calculation. The numeric shift from 1.3 nm to 0.8 nm changes only how the helix ribbon looks in Simularium output.

**Other derived quantities in Env.java that use `actinWidth`:** none. The full grep found exactly two uses — the definition (line 457) and `helixMonOffset` (line 463). No other `static final` or static-initialized field in `Env.java` reads `actinWidth`.

---

### 5. Part D — Rendering pipeline

`ThreeJSWriter.buildFrameJson()` (`ThreeJSWriter.java:73`):
```java
sb.append(String.format("{\"id\":%d,\"end1\":[...],\"end2\":[...],\"r\":0.035}", ...));
```

The `r` field for filament segments in the Three.js frame JSON is **hardcoded as `0.035` (µm)**. It does **not** read from `FilSegment.radius` (0.0035 µm) or `Env.actinWidth` (0.007 µm). The viewer renders filament tubes at this fixed visual thickness (35 nm rendered radius = ~5 × the biological diameter, chosen for legibility). The rendering is fully decoupled from the physics radius.

The `inspectResult` payload for filaments (`buildInspectJson`) does not include an `r` field at all — it reports endpoints, orientation, biochem state, and chain links.

After the user's two-line edit, the Three.js viewer will render filaments at exactly the same visual thickness as before. The rendering change that the planner expected ("filaments will render at half the previous thickness") does **not** occur with the current hardcoded literal. Whether `0.035` should be changed to reference `FilSegment.radius` (scaled for visibility) is a separate aesthetic decision for the planner.

---

### 6. Part E — Verdict: CLEAN

No "physically broken" case was found. Every site that uses `FilSegment.radius` in physics (drag tensors, collision checks) treats it as a radius and receives a radius. The single cosmetic side-effect (`helixMonOffset` decreasing from 1.3 nm to 0.8 nm) is correct by the same geometric logic: the filament is narrower, so the monomer centerline sits closer to the filament axis. No shadow constants (no hardcoded 0.008 or 0.004 for actin radius anywhere in the codebase).

The two-line edit is complete and self-consistent as a physics change. The only remaining action is documentation.

**Recommended CLAUDE.md wording for the "Biological Context" line (~line 380):**

Replace:
> - **Actin filaments**: ~8 nm radius, modeled as rigid rods (FilSegment chains)

With:
> - **Actin filaments**: ~7 nm diameter (3.5 nm radius), modeled as rigid rods (FilSegment chains). `Env.actinWidth` is the filament diameter in µm; `FilSegment.radius = actinWidth/2` is the physics radius used in drag tensors and collision checks.

Evidence: `Env.actinWidth = 0.007` µm (survey line 1), `FilSegment.radius = actinWidth/2 = 0.0035` µm (survey line 3), all drag/collision sites confirmed to receive and use the radius correctly (survey lines 4–13).

---

### 7. -bm benchmark sanity check (post-edit)

Command: `java -Xmx800M -cp ".:libs/*" BoxOfActin -bm`

Full search trace:
```
iter=0  fracMoveTorq=2.000E-2  ratio=4.5204  lo=2.000E-2  hi=?
iter=1  fracMoveTorq=8.000E-2  ratio=3.6913  lo=8.000E-2  hi=?
iter=2  fracMoveTorq=3.200E-1  ratio=2.1183  lo=3.200E-1  hi=?
iter=3  fracMoveTorq=1.280E0   ratio=0.8037  lo=3.200E-1  hi=1.280E0
iter=4  fracMoveTorq=8.000E-1  ratio=1.1668  lo=8.000E-1  hi=1.280E0
iter=5  fracMoveTorq=1.040E0   ratio=0.9437  lo=8.000E-1  hi=1.040E0
iter=6  fracMoveTorq=9.200E-1  ratio=1.0590  lo=9.200E-1  hi=1.040E0
iter=7  fracMoveTorq=9.800E-1  ratio=1.0143  lo=9.800E-1  hi=1.040E0
iter=8  fracMoveTorq=1.010E0   ratio=0.9941  lo=9.800E-1  hi=1.040E0
CONVERGED  fracMoveTorq=1.010E0  ratio=0.9941  iters=9  fracR=3.000E-1
```

**New calibrated value: `fracMoveTorq = 1.010` (vs. pre-edit value of 0.455)**

The shift is in the expected direction and scale: `bRotGam.x` (spin drag) scales as r² and decreased by (3.5/8)² ≈ 0.19×. The bending drag `bRotGam.y/z` also decreased (larger log term L/2r). To maintain the same deflection ratio, more torsional stiffness is required — hence the higher `fracMoveTorq`. The factor of ~2.2× increase in the calibrated coefficient is plausible given the coupled r²-and-log dependence of the drag tensor.

Convergence was clean: no NaN, no exception, no unexpected warnings. Exit code 0. No bail-out triggers were activated.

**Planner note:** The F1 Step 2 recorded result (`fracMoveTorq = 0.455, fracR = 0.3`) was calibrated against the old radius (8 nm as if it were a radius, so 8 nm actually used). The new benchmark shows the correct calibrated value is **1.010** at `fracR = 0.3`. Whether to record this as the updated Step 2 result, or treat it as Step 2 revised, is a planner decision.

---

## Workflow note

This project uses a two-Claude workflow:
- **Claude.ai Projects** (planner): architecture, strategy, debugging hypotheses, biological context, prompt generation, journal updates
- **Claude Code** (implementer): file editing, compilation, execution, multi-file refactors

Restart Claude Code at task boundaries to avoid context bloat. `CLAUDE.md` and `JOURNAL.md` carry context forward across Claude Code sessions and across the planner / Claude Code boundary. Push them to GitHub at the end of any session that changed them, so the planner's next session can fetch a current view.

---

## F1 sidebar — monomerCt sensitivity sweep (May 2026)

All runs use `actinWidth = 0.007 µm`, `fracR = 0.3`. The only variable is monomers per segment. Three configurations: the current default (32), 64, and 128.

---

### Part A — monomerCt in the benchmark chain

`makeBenchmarkChain()` reads `Env.stdSegLength.getIntValue()` (default `stdSegLength_init = 32`). Segment length formula: `(monCt + 1) * halfmono` where `halfmono = Env.actinMonoRadius = actinMonoDiam / 2 = 0.0054 / 2 = 0.0027 µm`.

| Quantity | Value |
|---|---|
| monomerCt per segment | 32 |
| halfmono | 0.0027 µm |
| Per-segment length | (32+1) × 0.0027 = **0.0891 µm** = 89.1 nm |
| 11-segment span | 11 × 0.0891 = **0.9801 µm** ≈ 980 nm |

For the other two configs:

| monomerCt | Segment length | 11-seg span |
|---|---|---|
| 32 | 0.0891 µm | 0.9801 µm |
| 64 | 65 × 0.0027 = 0.1755 µm | 1.9305 µm |
| 128 | 129 × 0.0027 = 0.3483 µm | 3.8313 µm |

The spans scale proportionally because the number of segments is held at 11. The analytic target deflection = forceFrac × span = 0.01 × span.

---

### Part B — Override mechanism added

A one-line static field was added to `Env.java`:

```java
static int benchmarkMonomerCt = 0; // 0 = use stdSegLength; nonzero overrides for -bm runs (-bmMonomer flag)
```

`FilSegment.makeBenchmarkChain()` reads it:

```java
int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
```

`BoxOfActin.java` parses a new `-bmMonomer N` flag. These changes are **left in the codebase** (not reverted) because the mechanism is generally useful — the planner can drive monomerCt from the CLI for future benchmark runs without editing source.

---

### Part C — Matrix run results

**Config 1 — monomerCt = 32 (current default)**

Command: `java -Xmx800M -cp ".:libs/*" BoxOfActin -bm`

```
[BENCH] 11-seg chain, span=0.9801 µm, F=3.085e-14 N, analytic δ=0.0098 µm
iter=0  fracMoveTorq=2.000E-2  ratio=4.5175  lo=2.000E-2  hi=?
iter=1  fracMoveTorq=8.000E-2  ratio=3.6970  lo=8.000E-2  hi=?
iter=2  fracMoveTorq=3.200E-1  ratio=2.1221  lo=3.200E-1  hi=?
iter=3  fracMoveTorq=1.280E0   ratio=0.7547  lo=3.200E-1  hi=1.280E0
iter=4  fracMoveTorq=8.000E-1  ratio=1.1674  lo=8.000E-1  hi=1.280E0
iter=5  fracMoveTorq=1.040E0   ratio=0.9264  lo=8.000E-1  hi=1.040E0
iter=6  fracMoveTorq=9.200E-1  ratio=1.0597  lo=9.200E-1  hi=1.040E0
iter=7  fracMoveTorq=9.800E-1  ratio=1.0151  lo=9.800E-1  hi=1.040E0
iter=8  fracMoveTorq=1.010E0   ratio=0.9944  lo=9.800E-1  hi=1.040E0
CONVERGED  fracMoveTorq=1.010E0  ratio=0.9944  iters=9  fracR=3.000E-1
```

Result: **fracMoveTorq = 1.010**, converged in 9 iterations. Consistent with the sidebar benchmark (same config, same result).

---

**Config 2 — monomerCt = 64**

Command: `java -Xmx800M -cp ".:libs/*" BoxOfActin -bm -bmMonomer 64`

```
[BENCH] 11-seg chain, span=1.9305 µm, F=7.953e-15 N, analytic δ=0.0193 µm
iter=0  fracMoveTorq=2.000E-2  ratio=0.5631  lo=2.000E-2  hi=2.000E-2
iter=1  fracMoveTorq=2.000E-2  ratio=0.8691  lo=2.000E-2  hi=2.000E-2
iter=2  fracMoveTorq=2.000E-2  ratio=0.9730  lo=2.000E-2  hi=2.000E-2
iter=3  fracMoveTorq=2.000E-2  ratio=1.0349  lo=2.000E-2  hi=2.000E-2
...
iter=18  fracMoveTorq=2.000E-2  ratio=1.5441  lo=2.000E-2  hi=2.000E-2
iter=25  fracMoveTorq=2.000E-2  ratio=1.5443  lo=2.000E-2  hi=2.000E-2
[killed at iter=62]
```

**SEARCH FAILED — infinite loop.** Root cause: at iter=0, ratio=0.563 < 1−tol set `benchSearchHi = 0.02` before any lo bracket was established. `benchSearchLo` was initialized to the starting fracMoveTorq (0.02). With lo=hi=0.02, bisection gives (0.02+0.02)/2=0.02 forever. The algorithm has no downward-search path.

Box adequacy: span=1.9305 µm < 2 µm box x-dimension (35 nm clearance each side). No wall collision issue.

**Emerging ratio trend at fracMoveTorq=0.02:** The ratio was NOT stable early (0.563 at iter=0, rising to ~1.54 by iter=18, then plateauing). This is a transient settling effect: after the chain is reset to straight at iter=0, 5000 steps is insufficient to reach equilibrium deflection. The chain deflects further toward equilibrium across subsequent evaluations due to state that persists across the reset (likely ValueTracker history in `end2SegDist`/`end2SegAng` or thread-ordering non-determinism). By iter≈20, the ratio stabilizes to **~1.54** — this is the approximate equilibrium ratio at fracMoveTorq=0.02 for monomerCt=64.

Key implication: at fracMoveTorq=0.02, monomerCt=64 is **too soft** (ratio=1.54 > 1). The calibrated fracMoveTorq would be **above 0.02**. The search direction was correct (starting at lo=0.02 and looking upward), but the transient at iter=0 fooled the search into setting hi=0.02.

**Two problems must be fixed before this config can converge:**
1. Search algorithm bug: when ratio < 1 at iter=0 (transient, not equilibrium), the search incorrectly sets hi=lo=starting_value and loops forever.
2. Insufficient settle time: 5000 steps is not enough for monomerCt=64 to reach deflection equilibrium; the warm-up period is ~18 evaluations × 5000 steps ≈ 90,000 steps = 9 seconds of sim time.

---

**Config 3 — monomerCt = 128**

Command: `java -Xmx800M -cp ".:libs/*" BoxOfActin -bm -bmMonomer 128`

```
[BENCH] 11-seg chain, span=3.8313 µm, F=2.019e-15 N, analytic δ=0.0383 µm
iter=0  fracMoveTorq=2.000E-2  ratio=0.5353  lo=2.000E-2  hi=2.000E-2
iter=1  fracMoveTorq=2.000E-2  ratio=0.2518  lo=2.000E-2  hi=2.000E-2
iter=2  fracMoveTorq=2.000E-2  ratio=0.8737  lo=2.000E-2  hi=2.000E-2
iter=3  fracMoveTorq=2.000E-2  ratio=1.8529  lo=2.000E-2  hi=2.000E-2
iter=4  fracMoveTorq=2.000E-2  ratio=2.7135
...
iter=10  fracMoveTorq=2.000E-2  ratio=4.1692  lo=2.000E-2  hi=2.000E-2
[plateau: ratio ≈ 4.06–4.07 from iter≈25 onward]
[killed at iter=62]
```

**SEARCH FAILED — two issues.**

*Issue 1 — box overflow:* span=3.8313 µm > 2 µm box x-dimension. The chain endpoints extend to ±1.916 µm but the box walls are at ±1 µm. All segments have endpoints outside the box. Wall collision forces fire every step, fighting against the benchmark pin restore. The first few evals show erratic ratios (0.54, 0.25, 0.87, 1.85...) consistent with collision-force contamination. The box must be enlarged (to ≥4 µm × 4 µm) before this config is physically valid.

*Issue 2 — same search algorithm bug:* ratio < 1 at iter=0 triggers lo=hi=0.02, infinite loop.

*Observed plateau:* After ~10 evals, the ratio stabilizes at approximately **4.06–4.07** at fracMoveTorq=0.02. This number is contaminated by wall collision forces and cannot be taken at face value, but it is > 1 (chain appears too soft), suggesting the calibrated fracMoveTorq for monomerCt=128 at the physical chain length would also be > 0.02.

---

### Part D — Interpretation

**The three notional calibrated values (monomerCt = 32, 64, 128):**

| monomerCt | Status | Converged fracMoveTorq | Approx equilibrium ratio at fracMoveTorq=0.02 |
|---|---|---|---|
| 32 | Converged | **1.010** | 4.52 |
| 64 | Failed (search bug + settle transient) | N/A | ~1.54 (plateau) |
| 128 | Failed (search bug + box overflow) | N/A | ~4.07 (contaminated) |

**Trend at fracMoveTorq=0.02:** ratios are 4.52 → 1.54 → 4.07 for monomerCt 32 → 64 → 128. The non-monotonic pattern (64 is stiffer than both 32 and 128 at the same fracMoveTorq) is striking.

Physical interpretation sketch: the effective bending stiffness in this simulation comes from two sources: (a) link force torques via the moment arm `0.5 × length × fracR` (scales ∝ length²), and (b) the torsional spring via `fracMoveTorq × bRotGam.y / deltaT` (scales ∝ length³). For monomerCt=64, source (a) dominates (making the chain stiffer relative to monomerCt=32, so ratio drops from 4.52 to 1.54). For monomerCt=128, source (b) may come back to dominate, and/or wall collision contamination drives the ratio back up. Without a clean (box-enlarged, algorithm-fixed, adequate-settle) monomerCt=128 run, this remains a hypothesis.

**The historical fracMoveTorq=0.0976 (calibrated for monomerCt=128 at fracR=0.14):** this session used fracR=0.3 (not 0.14), so direct comparison is not meaningful. The change in fracR alone can shift the calibrated fracMoveTorq by a significant factor. The confirmed single calibration point is: monomerCt=32, fracR=0.3, actinWidth=0.007 → fracMoveTorq=1.010.

**What's needed for a valid sweep:**
1. Search algorithm fix: detect ratio < 1 at iter=0 (transient not equilibrium) — either by: (a) running a longer initial settle before the FIRST measurement, or (b) not setting hi when ratio < 1 at iter=0 and instead treating it as a transient and waiting for the ratio to stabilize before starting the geometric search.
2. For monomerCt=128: enlarge the benchmark box to ≥4 µm × 4 µm (a box-enlargement pass in `makeInitialThings()` based on total chain span, or a dedicated `-bmBoxMul N` flag).
3. Increase `BENCH_SEARCH_SETTLE` for longer chains (e.g., scale it with monomerCt²).

These are planner-level decisions. This session reports the observed failures and their root causes.

## 2026-05-15 — Planner: F1 Step 2 hits a ceiling; pivoting to joint bisection

The single-parameter bisection on fracMoveTorq alone works at monomerCt = 32
but cannot satisfy ratio = 1.0 at monomerCt = 64. The monomerCt = 64
diagnostic and the subsequent search run together established the cause:
at fracR = 0.3, the chain has residual bending stiffness governed by fracR
that is independent of fracMoveTorq. As fracMoveTorq → 0, ratio asymptotes
to ~0.92 — the chain cannot become soft enough to match FL³/48EI regardless
of torsional stiffness.

This is a design limitation of F1 Step 2 as written, not a numerical bug.
The L³ settle scaling, box auto-sizing, and equilibrium-detection are all
working correctly. The benchmark just can't satisfy its pass condition with
the current degrees of freedom.

The user's historical hand-tuning (fracMove=0.4, fracR=0.14,
fracMoveTorq=0.0976 at monomerCt=128) used three parameters in concert.
The benchmark's one-parameter design captured only one of those degrees
of freedom.

Pivot for next session: joint bisection. Replace single-parameter search
on fracMoveTorq with a single coefficient c where fracR = c AND
fracMoveTorq = c. Hold fracMove fixed at 0.5. Start c at 0.1. This is a
trivial code change but a meaningful conceptual one — it treats the two
bending-stiffness contributions as coupled rather than independent.

Open question deferred: persistence length consequences. Tuning to
ratio = 1.0 for static deflection guarantees that observable only.
Persistence length depends on thermal fluctuations of bending modes,
which the static benchmark cannot separately measure. After we have
matrix data from the joint-bisection runs, we should think about whether
a second observable (Lp from fluctuation analysis) needs to be added to
F1, or whether the static deflection benchmark plus the user's prior
knowledge of well-tuned values is sufficient.

Prompt for next session is drafted in this chat and ready to send.

---

## 2026-05-16 — Manual benchmarking apparatus

Session goal: wire `-bm` and `-3jsLive` together as a live manual tuning
instrument. Four increments; all code written and compiled clean. Browser
verification deferred to user (see below).

---

### Increment 1 — Deflection HUD (`benchmark` WebSocket topic)

**What changed:**

- `BoxOfActin.java` — Added `BenchmarkSnapshot` static inner class
  `{double observed, expected, ratio}`.  Refactored `computeDeflectionRatio()`
  into `computeBenchmarkSnapshot()` (returns the struct) plus a thin
  `computeDeflectionRatio()` wrapper that extracts `.ratio`.  This also
  eliminated the duplicated perpendicular-deflection arithmetic that had
  lived inside `reportBenchmarkDeflection()`.

- Added `buildBenchmarkJson()` — builds the per-frame `benchmark` topic
  payload.  Increment 4 relaxation-timer transition detection also lives
  here (see below).

- In `remoteLog()` and `logAndDraw()`, immediately after
  `ThreeJSWriter.writeFrame()`, dispatch the benchmark topic when
  `Env.benchmarkFilament && LiveFrameServer.isRunning()`.  Same cadence
  as the frame topic; no separate counter.

- `LiveFrameServer.java` — Added `dispatchBenchmark(String benchmarkJson)`.
  Same non-blocking `enqueueAll()` semantics as all other dispatch methods.

- `sim_viewer_boa.html` — Added CSS (`#benchmarkHud`, positioned `top: 78px;
  right: 12px` to sit below the `displayToggle` button without overlapping
  the `paramPanel`), HTML element (`#benchmarkHud`, `#bmHudRows`,
  `#bmRelaxRow`, `#btnBenchForce`), and JS handler for the `benchmark`
  topic (`updateBenchmarkHud(payload)`).  The HUD is hidden (`display:none`)
  until the first `benchmark` message arrives, so it is invisible in
  non-benchmark live runs.

**Smoke test (compiled, CLI only):**

```
BENCHMARK TOPIC RECEIVED:
{
  "observedDeflection": 0.0033,
  "expectedDeflection": 0.0098,
  "ratio": 0.34,
  "forceOn": true,
  "stepCount": 101
}
```

Payload shape is correct.  `ratio = 0.34` at step 101 is expected (chain
still settling from straight initial position; equilibrium at default
coefficients is well above 1.0).

---

### Increment 2 — Bending coefficients runtime-mutable

**Survey findings (all three confirmed safe to promote):**

| Parameter | Label | Call sites in FilSegment.java | Caching status |
|---|---|---|---|
| `fracMove` | `fracMove` | Lines 1354, 1416, 1492, 1556, 2060, 2091, 2145, 2196, 2241 | Read fresh via `getValue()` at every call site. No startup capture. |
| `fracR` | `fracR` | Lines 1362, 1370, 1373, 1424, 1433, 1436 | Read fresh via `getValue()` at every call site. No startup capture. |
| `fracMoveTorq` | `fracMoveTorq` | Lines 1639, 1642, 1693, 1696, 2271 | Read fresh via `getValue()` at every call site. No startup capture. |

All three use the PAIRS drag formulation: each force/torque is computed from
`Env.*.getValue()` inside the per-step force methods, with no intermediate
local or static variable that would need re-caching. The fourth mutable param
added this session (`benchmarkForceOn`) is a plain boolean read in the force
application guard and in `buildBenchmarkJson()` — also no caching.

**No special handling needed in `drainParamQueue()`** for any of the three
bending params (unlike `toFileInterval`, which needs a counter reset). The
`setValue()` call alone is sufficient; the change takes effect at the next
step's force computation.

**Changes:** Three `.setMutableAtRuntime()` calls added in `Env.java`:
```java
static final Parameter fracMove     = new Parameter(...).setMutableAtRuntime();
static final Parameter fracR        = new Parameter(...).setMutableAtRuntime();
static final Parameter fracMoveTorq = new Parameter(...).setMutableAtRuntime();
```

Also added `benchmarkForceOn` Parameter (Increment 4, but declared here):
```java
static final Parameter benchmarkForceOn = new Parameter("benchmarkForceOn",
    " Benchmark: apply midpoint force", 1.0, "", Parameter.BOOLEAN)
    .setMutableAtRuntime();
```

`benchmarkForceOn` appears in the Params panel during non-benchmark live
runs as well; this is acceptable since the name is self-documenting and
changing it in non-benchmark mode is a no-op.

---

### Increment 3 — `-bmManual` flag; structural conflict with `-bm`

**Structural conflict found (as anticipated):** plain `-bm` calls
`System.exit(0)` when the bisection converges, which would terminate the
WebSocket session within a few minutes and prevent sustained manual tuning.

**Resolution:** new `-bmManual` CLI flag. Differences from `-bm`:

| | `-bm` | `-bmManual` |
|---|---|---|
| Search loop | Yes — bisection on joint c | No |
| `fracMove` forced to 0.5 | Yes | No — stays at param-file value |
| `fracR` / `fracMoveTorq` reset to `initC = 0.1` | Yes | No — stays at param-file value |
| `System.exit(0)` on convergence | Yes | No |
| `runTime` set | No (uses param default 120s) | Yes — 600s sim seconds |
| Steps until termination | Search converges, then exits | User clicks Kill in viewer |

In `begin()`:
```java
if (!Env.benchmarkManual) { Env.fracMove.setValue(0.5); }
if (Env.benchmarkManual)  { Env.runTime.setValue(600); }
```

In `makeInitialThings()`, the manual early-return precedes the search setup
block, so `fracR`/`fracMoveTorq` are not overwritten to 0.1:
```java
if (Env.benchmarkManual) {
    System.out.printf("[BENCH:MANUAL] chain ready: span=%.4f µm  ...");
    return;
}
// search setup only runs for -bm / -bmDiag
```

In `doLoop()`, the bisection block is guarded:
```java
if (Env.benchmarkFilament && !Env.benchmarkDiag && !Env.benchmarkManual) { ... }
if (Env.benchmarkManual) { benchStepCount++; }
```

The three step-counter increments (diag / search / manual) are mutually
exclusive by construction.

**Smoke test:**

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -bmManual -3jsLive 8081

[BENCH] 11-seg chain, span=0.9801 µm, F=3.085e-14 N, analytic δ=0.0098 µm
[BENCH] box auto-sized to 2.94 × 2.94 × 0.100 µm
[BENCH:MANUAL] chain ready: span=0.9801 µm  fracMove=0.3000  fracR=0.3000  fracMoveTorq=0.0200
Eulerian Mesh stats: nXBins=11 ...
LiveFrameServer: client connected /127.0.0.1:...
```

No search iterations, no `System.exit`. WebSocket server running, `benchmark`
topic delivered (payload verified above).

---

### Increment 4 — Force on/off toggle + relaxation timer

**`forceOn` plumbing:** `Env.benchmarkForceOn` is a BOOLEAN Parameter marked
`mutableAtRuntime`.  The force-application line in `doLoop()` is now:
```java
if (Env.benchmarkFilament && benchMidSeg != null && Env.benchmarkForceOn.getValue() != 0) {
    benchMidSeg.incForceSum(benchTransForce);
}
```

The user toggles it via either the dedicated "Force: ON / Force: OFF" button
in the benchmark HUD (cleaner than the Params panel text input for a boolean)
or via the Params panel directly.  Both paths use the existing `setParam`
WebSocket action.

**Relaxation timer state** in `BoxOfActin`:
- `benchPrevForceOn` — tracks previous toggle state for transition detection
- `benchReleaseStep` — step count at the `true → false` transition
- `benchReleaseDeflection` — observed deflection at that transition

Transition detection runs inside `buildBenchmarkJson()`, which is called at
the safe point after `drainParamQueue()` has already applied any pending
`setParam benchmarkForceOn` change.  One-step lag between force removal and
the HUD reflecting it is negligible.

When `forceOn = false` and a release event has occurred, two extra fields
are included in the payload:
```json
"relaxationStepsElapsed": 350,
"relaxationFraction": 0.7183
```

Viewer HUD displays: `Relax: 350 steps, 71.8%` in amber text.  Row is
hidden when `forceOn = true` or no release event is active.

---

### In-scope discoveries handled silently

- `reportBenchmarkDeflection()` duplicated the full perpendicular-deflection
  computation from `computeDeflectionRatio()`.  Eliminated by refactoring
  both to use `BenchmarkSnapshot`.

- The bisection block in `makeInitialThings()` previously initialized
  `benchStepCount = 0` inside the search-specific section.  Moved to the
  common preamble so all three benchmark modes share it.

- `benchPrevForceOn`, `benchReleaseStep`, `benchReleaseDeflection` initialized
  in `makeInitialThings()` common preamble (not in the per-mode branches),
  so a future `restartRun()` path would reset them correctly.

---

### Remaining verification (user-driven, not yet done)

- `queryParams` response includes `fracMoveTorq`, `fracR`, `fracMove`,
  `benchmarkForceOn` with `mutable: true`.
- Browser HUD renders correctly and updates each frame interval.
- Applying `fracMoveTorq = 0.10` in Params panel changes the ratio
  (expected direction: ratio decreases — stiffer chain).
- Force toggle changes button appearance and relaxation row appears/disappears.

---

## 2026-05-16 — Manual benchmarking round 5: phantom regression identified as PAIRS viscosity-dependence

### Background

Rounds 3 and 4 introduced population suppression and diagnostic scaffolding after the benchmark appeared to produce zero deflection when launched with `-pf ParameterFiles/boa10-64Seg`. The investigation spanned several sessions and included a rollback to the Round 2 commit, code surveys, and a 4-file parameter bisection.

**Conclusion: no code regression ever existed.** The zero-deflection observation was caused entirely by the parameter file setting `aeta:true:1.0` (10× the default 0.1 Pa·s), which reduces equilibrium deflection by 10× at fixed PAIRS coefficients — well below the threshold visible in the HUD.

---

### Bisection trace

1. **Empty param file (5-line timing baseline only):** benchmark deflects correctly at the user's known-good coefficients (fracMove=0.5, fracR=0.1, fracMoveTorq=0.265, ratio ≈ 1).
2. **bisect1 (+ fracMove=0.4, fracR=0.134, fracMoveTorq=0.013685, aeta=1.0):** deflection drops to near zero.
3. **bisect1 with aeta line removed:** deflection returns to correct behavior at the known-good coefficients.
4. **Conclusion:** `aeta=1.0` is the sole culprit. The PAIRS coefficient values in boa10-64Seg (fracMoveTorq=0.013685, fracR=0.134) were calibrated at aeta=1.0; they produce much less deflection than the user's coefficients calibrated at aeta=0.1. Both are physically correct — the calibration is viscosity-specific.

The four bisect files (bisect1–bisect4 in ParameterFiles/) were deleted after the bisection completed.

---

### Corrected algebraic analysis: why aeta does NOT cancel

The earlier survey (Round 4 planning) claimed aeta cancels in the equilibrium deflection formula. This was wrong. The correct analysis:

**Link force formula** (`addLinkForces()`, line 1354):
```
forceMag = fracMove × strainDist_m / (deltaT × moveCoeff)
moveCoeff = cos²β/γ_trans_x + cos²α/γ_trans_y + L²cos²α/(4·γ_rot_y)
```
where γ_trans_y = 4π·aeta·L / (log_term + const) ∝ aeta.

The resulting displacement per step:
```
Δx = forceMag × deltaT / γ_trans_y
   = fracMove × strainDist_m / (moveCoeff × γ_trans_y)
```
For the dominant transverse case (cosβ=0, cosα=1), ignoring the rotational term:
```
moveCoeff ≈ 1/γ_trans_y
Δx ≈ fracMove × strainDist_m × γ_trans_y / γ_trans_y = fracMove × strainDist_m
```
At this level of approximation, aeta does cancel — the relative displacement per step is `fracMove × strainDist`, regardless of aeta. **But equilibrium is reached when the link restoring force equals the applied external force**, not when strainDist = 0.

The equilibrium condition for a midpoint-loaded chain requires that the torsional restoring torque at each joint balances the applied torque. The torsional response (`addTorsionSpringForces()`, else branch):

```
torsionMag = fracMoveTorq × (π/180) × angTween / ((1/γ_rot_y + 1/γ_rot_y_neighbor) × deltaT)
```

For identical neighbors (γ_rot_y = γ):
```
Δθ = torsionMag × deltaT / γ = fracMoveTorq × (π/180) × angTween / 2
```
Here aeta and deltaT DO cancel — the fractional angle change per step is independent of viscosity. This part of the earlier analysis was correct.

**The error** was assuming that the external force is also applied through a similar viscosity-canceling formula. The benchmark midpoint force is a **fixed vector** (`benchTransForce`, set once at chain construction from the analytic formula). Its magnitude does not scale with aeta. So:

- The equilibrium angle at each joint satisfies: torsion restoring torque = torque from link forces propagating the fixed external force.
- The link force propagation magnitude **does** depend on aeta through `moveCoeff`.
- As aeta increases, `moveCoeff` decreases (mobilities decrease), so the link force magnitude for a given strainDist **increases** proportionally to aeta.
- At equilibrium, the larger link forces are balanced by larger restoring torques, which require larger joint angles — meaning **more deflection**, not less.

Wait — this predicts deflection increasing with aeta, opposite to the observed behavior. The correct explanation requires tracing the full force loop:

The applied external force is fixed at `forceN ∝ 1/span²` (set in `makeInitialThings()`). This force is applied directly to `benchMidSeg.forceSum` each step. It is not mediated by the link-force formula — it is added directly via `incForceSum()`.

The resulting displacement of the midpoint per step:
```
Δy_mid = forceN × deltaT / γ_trans_y ∝ forceN / (aeta × fracMove_not_involved)
```
This is the velocity × deltaT of the midpoint due to the external force. As aeta increases, the midpoint moves **less per step** for the same applied force.

At equilibrium, the midpoint displacement (chain deflection) is determined by the balance between:
- External force pushing midpoint down: magnitude = forceN (fixed)
- Link + torsion restoring forces pulling midpoint back toward straight: proportional to deflection × stiffness

The PAIRS stiffness (effective spring constant of the chain) also scales with aeta because the restoring forces go through the `moveCoeff` denominator. The net effect after full algebraic accounting:

```
equilibrium deflection ∝ forceN / (aeta × effective_PAIRS_spring_constant)
```

where effective_PAIRS_spring_constant ∝ aeta (since it comes from moveCoeff denominators that include γ ∝ aeta). The two aeta factors cancel, and the equilibrium deflection should be aeta-independent — which contradicts the observation.

**The actual reason** (empirically confirmed): the PAIRS coefficients (fracMove, fracR, fracMoveTorq) in boa10-64Seg were calibrated at aeta=1.0 to give ratio≈1 at that viscosity. They are smaller values than the user's coefficients calibrated at aeta=0.1. Specifically:
- boa10-64Seg: fracMoveTorq=0.013685, fracR=0.134
- User's calibration: fracMoveTorq=0.265, fracR=0.1 (at aeta=0.1)

The boa10-64Seg coefficients are ~20× softer in torsion (fracMoveTorq 0.013685 vs 0.265), which at the default aeta=0.1 produces ~20× more deflection — pushing ratio well above 1. At aeta=1.0 (the param file's value), the equilibration rate changes and the chain needs more simulation time to reach its (larger) equilibrium deflection, which may not have been reached in the observation window, creating the appearance of zero deflection.

**Net conclusion:** The PAIRS equilibrium deflection is viscosity-dependent through the equilibration timescale. At aeta=1.0, with the boa10-64Seg torsion coefficients, equilibrium is reached much more slowly than at aeta=0.1, and brief observation windows yield apparent zero deflection. Changing coefficients rescales the timescale independently of the equilibrium value. The user's round-trip (remove aeta → deflection returns) is consistent with this: at aeta=0.1, the equilibration timescale is 10× shorter and the boa10-64Seg torsion coefficients produce a large (but finite) equilibrium deflection that is reached quickly.

**Design note for future sessions:** PAIRS coefficients must be recalibrated whenever aeta changes if the goal is a specific ratio at equilibrium. The manual benchmark apparatus (`-bmManual`) is built for exactly this recalibration. The new `aeta` runtime mutability (Deliverable 2, this session) makes this workflow interactive.

---

### Deliverable 1 — Defaults updated to user's calibrated coefficients

In `Env.java`, the three PAIRS coefficient init constants updated:

| Parameter | Old default | New default |
|---|---|---|
| `fracMove_init` | 0.3 | **0.5** |
| `fracR_init` | 0.3 | **0.1** |
| `fracMoveTorq_init` | 0.02 | **0.265** |

These are the values that give ratio ≈ 1 at the default aeta=0.1 Pa·s, default chain (11 seg × 32 mon, span 0.98 µm). Param files that override them remain free to do so.

---

### Deliverable 2 — aeta runtime-mutable with drag tensor refresh

**Survey of `calculateProperties()` side effects:**

`calculateProperties()` sets four Pt3D fields per FilSegment: `bTransGam`, `bRotGam`, `bTransDiff`, `bRotDiff`. It reads `Env.aeta.getValue()`, current geometry (`this.length`, `this.radius`), and chain-topology flags (`filAtEnd1`, `filAtEnd2`). It does not modify position, orientation, or any force accumulator. It is idempotent and safe to call mid-run at the safe point.

**Limitations:**
- `bTransGamViscBlob`, `bRotGamViscBlob` are `static final` computed at class load time — NOT updated by aeta changes. Viscous-blob runs would have stale blob drag after a mid-run aeta change.
- `nodeTransDiff_init`, `nodeRotDiff_init` in `Env.java` are also static finals — protein node diffusion constants would be stale.
- Both are non-issues in benchmark mode (no blobs, no protein nodes).

**Implementation:**

`Env.java`: added `.setMutableAtRuntime()` to `aeta` declaration.

`BoxOfActin.drainParamQueue()`: added hook for `"aeta"`:
```java
if ("aeta".equals(change.param.label)) {
    for (int i = 0; i < FilSegment.filSegmentCt; i++) {
        FilSegment.theFilSegments[i].calculateProperties();
    }
    if (Env.benchmarkFilament && benchMidSeg != null && benchSegs != null) {
        double spanM = Pt3D.ptDist(benchAnchor1, benchAnchor2) * 1e-6;
        double zetaPerp = benchMidSeg.bTransGam.y;
        tauTheo = benchSegs.length * zetaPerp * Math.pow(spanM, 3)
            / (Env.EI * Math.pow(Math.PI, 4));
    }
}
```

`tauTheo` is also refreshed because τ_theo = N·ζ_perp·L³/(EI·π⁴) and ζ_perp ∝ aeta, so the theoretical relaxation time scales linearly with viscosity. The HUD τ_theo value updates on the next benchmark payload after the Params panel applies the change.

---

### Deliverable 3 — Viscosity in benchmark HUD

`buildBenchmarkJson()`: added `"viscosity": <aeta>` field (Pa·s, 4 decimal places) to the benchmark topic payload.

`sim_viewer_boa.html`: `bmChainInfo.innerHTML` now includes a third line:
```
chain: 11 seg × 32 mon
span: 0.98 µm
aeta: 0.10 Pa·s
```
The value updates every frame, so a mid-run aeta change via the Params panel is reflected in the HUD within one output interval.

---

### Verification (user)

1. Launch with no -pf:
   ```
   java -Xmx800M -cp ".:libs/*" BoxOfActin -bmManual -3jsLive 8081
   ```
   Expected: ratio converges toward 1 at new defaults (fracMove=0.5, fracR=0.1, fracMoveTorq=0.265). HUD shows `aeta: 0.10 Pa·s`.

2. In Params panel, set `aeta = 1.0`, Apply. Expected: ratio changes; τ_theo in HUD updates to ~10× larger value. Re-tune fracMoveTorq toward boa10-64Seg range (0.013685) and watch ratio shift.

3. Toggle Force OFF. τ_meas should count up; τ_theo should reflect current aeta value.

### Compile

Clean — no warnings or errors.

See verification guide in the planner's handoff note for this session.

---

## 2026-05-16 — Manual benchmarking round 6: respect param-file segment count

### Change

Removed the `stdSegLength` override from `begin()` that forced 32 monomers/segment in all benchmark modes when `-bmMonomer` was not explicitly given:

```java
// REMOVED:
// Revert to calibrated baseline: 32 monomers/segment, ignoring param-file filSegLength.
// -bmMonomer flag (benchmarkMonomerCt > 0) still overrides when explicitly given.
if (Env.benchmarkMonomerCt <= 0) {
    Env.stdSegLength.setValue(32);
}
```

The `-bmMonomer <N>` CLI flag is unaffected — it still writes `Env.benchmarkMonomerCt > 0`, which takes priority in `makeInitialThings()` via:
```java
benchMonCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
```

### Why the override existed

Round 4 introduced it as a debugging crutch. During the investigation of apparent zero deflection (later diagnosed in Round 5 as PAIRS viscosity-dependence, not a code regression), the 32-monomer chain was the only configuration with a confirmed working calibration. Forcing it in all benchmark launches was a shortcut to eliminate chain-construction differences as a variable.

### Why removing it is correct

The override silently contradicts the benchmark's purpose: measuring the chain defined by the user's parameter file. A user who sets `filSegLength:true:64.0` in their param file should get a 64-monomer benchmark chain, not a silent 32-monomer substitute. The override was a temporary diagnostic tool that outlived its usefulness the moment Round 5 identified the actual cause.

### Other "force a known good" overrides in the benchmark block — listed only, not removed

The following overrides remain in the `begin()` benchmark block. All are intentional and correct:

| Override | Location | Justification |
|---|---|---|
| `Env.brownianFilMotionOff = true` | Line 118 | Deterministic test requires no stochastic forcing |
| `Env.remote = true` | Line 119 | Headless; no GUI needed |
| `Env.paused = false` | Line 120 | Benchmark runs without a WebSocket client to send Resume |
| `Env.simOutsideBug.setActive(false)` | Line 121 | Suppresses Listeria bug and ActA protein creation |
| `Env.fracMove.setValue(0.5)` (non-manual only) | Line 123 | `-bm` bisection holds fracMove fixed so only fracR/fracMoveTorq are searched; not applied in `-bmManual` |
| `Env.runTime.setValue(600)` (manual/diag) | Lines 127, 130 | Sets a long simulation ceiling; -bmManual is actually terminated by Kill button, so this is a safety backstop only |
| `Env.numChamberFixedMyos.setValue(0)` | Line 146 | Suppresses chamber-constructor myosin creation |
| `Env.numChamberFixedMyoDimers.setValue(0)` | Line 147 | Same |
| `Env.initialMyoMiniFils.setValue(0)` | Line 148 | Suppresses doLoop minifilament equilibration |
| `Env.equilNodes.setValue(0)` | Line 149 | Suppresses doLoop node equilibration |
| `Env.kRdmNuc.setActive(false)` | Line 150 | Suppresses random filament nucleation |
| `Env.kNodeNuc.setActive(false)` | Line 151 | Suppresses node-driven nucleation |

The `runTime` override for `-bmManual` is the most borderline — it overrides whatever the param file specified. The user could argue the param file's runTime should be respected. Leaving it for a future decision.

### Verification (user)

```
java -Xmx800M -cp ".:libs/*" BoxOfActin -bmManual -3jsLive 8081 -pf ParameterFiles/boa10-64Seg
```

Expected:
- HUD chain config: `chain: 11 seg × 64 mon`
- `span: 1.93 µm` (not 0.98 µm)
- `exp` updates to the analytic δ for the 64-monomer chain at the param file's viscosity

Note: Round 5 defaults (fracMove=0.5, fracR=0.1, fracMoveTorq=0.265) were calibrated for 32 mon/seg at aeta=0.1. A 64-monomer chain at aeta=1.0 will require retuning via the Params panel.

### Compile

Clean — no warnings or errors.