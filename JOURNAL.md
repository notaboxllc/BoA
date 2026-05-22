# BoxOfActin Project Journal

Last updated: 2026-05-22

Older entries are in `JOURNAL_ARCHIVE.md`. Run logs and pasted simulation output go in `RUN_LOGS/`.

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
- fracMove (C_δ): [0.1, 0.5]. Higher = stiffer. Upper bound 0.5 is theoretical stability limit. Lower bound 0.1 is empirical (geometric chain-link integrity).

**Performance** (benchmark: 11-segment filament, deflection target = analytic δ for a simply-supported beam under transverse force).

| filSegLength | Target δ (µm) | Final (fr, fmt, fracMove) | Iterations | Frames |
|---|---|---|---|---|
| 32 mon | 0.0098 | (0.146, 0.386, 0.50) | 2 | 263 |
| 48 mon | 0.0146 | (0.287, 0.070, 0.50) | 7 | 563 |
| 64 mon | 0.0193 | (0.377, 0.031, 0.25) | 10 + 6 prepass probes | 1277 |
| 96 mon | 0.0288 | PREPASS_FAILED — target unreachable in legal parameter box | n/a | 1112 |

**Known limit at 96-monomer segments.** The maximum reachable deflection at the softest legal parameter corner (fracMove=0.10) is 0.0238 µm, vs target 0.0288 µm. This is 21% short of target. The lumped-parameter representation appears to be at the edge of its accuracy regime at this segment size — segments are ~0.26 µm, comparable to the ParM tuning case in Alberts 2009 Fig. 5B where method accuracy begins to degrade.

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
