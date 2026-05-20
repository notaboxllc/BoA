# BoxOfActin Project Journal

Last updated: 2026-05-19

Older entries are in `JOURNAL_ARCHIVE.md`. Run logs and pasted simulation output go in `RUN_LOGS/`.

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
