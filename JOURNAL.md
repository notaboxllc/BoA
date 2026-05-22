# BoxOfActin Project Journal

Last updated: 2026-05-21

Older entries are in `JOURNAL_ARCHIVE.md`. Run logs and pasted simulation output go in `RUN_LOGS/`.

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
