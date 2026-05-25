# BoxOfActin Project Journal

Last updated: 2026-05-25

Older entries are in `JOURNAL_ARCHIVE.md`. Run logs and pasted simulation output go in `RUN_LOGS/`.

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

## Workflow note

This project uses a two-Claude workflow:
- **Claude.ai Projects** (planner): architecture, strategy, debugging hypotheses, biological context, prompt generation, journal updates
- **Claude Code** (implementer): file editing, compilation, execution, multi-file refactors

Restart Claude Code at task boundaries to avoid context bloat. `CLAUDE.md` and `JOURNAL.md` carry context forward across Claude Code sessions and across the planner / Claude Code boundary. Push them to GitHub at the end of any session that changed them, so the planner's next session can fetch a current view.
