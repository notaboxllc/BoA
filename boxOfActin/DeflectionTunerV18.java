package boxOfActin;

/**
 * DeflectionTunerV18 — empirical-sensitivity overshoot controller.
 *
 * Inherits v17.1's alternation structure (FRAC_R / FRAC_MT, STEPS_PER_TURN=2,
 * strict alternation) and signal pipeline (EMA + linear-regression velocity,
 * running-avg |v|). Replaces three mechanisms from v17.1:
 *
 *   1. Step sizing: v17.1 used HALVE_FRACTION × distToLimit as the default step.
 *      v18 uses |err| / sens (ideal step to land on target), clamped to
 *      [MIN_STEP_FRAC × |param|, MAX_STEP_FRAC × distToLimit]. The first step
 *      in each parameter uses a hardcoded initial guess (SENS_R_INIT, SENS_M_INIT);
 *      subsequent steps use the empirically measured value.
 *
 *   2. Settling detection for SETTLE_CHECK entry: v17.1 used an absolute
 *      SETTLE_ENTRY_FRAC × V_NOISE threshold. v18 uses chainHasSettledRelative():
 *      runningAvgV < max(PEAK_REL_THRESHOLD × peakVAbsThisBurst, ABSOLUTE_V_FLOOR).
 *
 *   3. Sensitivity measurement: after each step settles, updateSensitivityFromCompletedStep()
 *      records |Δsmoothed / Δparam| as the empirical sensitivity for that parameter.
 *
 * Everything else — PHASE enum, handleSettleCheck() (four branches), alternation
 * budget logic, pinned-skip, RETRY_LOWER_FRACMOVE, soft-start — is identical to v17.1.
 *
 * ALL algorithm constants marked PLACEHOLDER — calibrate after first trace.
 */
class DeflectionTunerV18 {

    enum Phase { R_ADJUST, M_ADJUST, SETTLE_CHECK, RETRY_LOWER_FRACMOVE, CONVERGED, FAILED }

    enum ActiveParam { FRAC_R, FRAC_MT }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── hardware limits (same as v14/v15/v16/v17) ──────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── EMA constant (same as v15/v16/v17) ────────────────────────────────────
    static final double EMA_ALPHA         = 0.05;
    static final double SECONDS_PER_FRAME = 0.01;  // toFileInterval=100 × deltaT=1e-4

    // ── signal pipeline constant ───────────────────────────────────────────────
    static final int    W_A       = 12;              // PLACEHOLDER: regression base window
    static final int    SLOPE_WIN = Math.max(5, W_A); // = 12; actual window used for v

    // ── sensitivity bootstrap (initial guesses, used only before first measurement) ──
    // SENS_R_INIT: µm per unit-fracR. PLACEHOLDER.
    //   Rough estimate from v17.1 trace: Δs ≈ 5 nm over ΔfracR ≈ 0.17 → sens ≈ 0.03.
    //   Using 0.005 here (conservative underestimate) so first step is large but bounded.
    static final double SENS_R_INIT = 0.005;  // PLACEHOLDER
    // SENS_M_INIT: µm per unit-fracMoveTorq. PLACEHOLDER.
    static final double SENS_M_INIT = 0.05;   // PLACEHOLDER

    // ── step magnitude bounds ──────────────────────────────────────────────────
    // MAX_STEP_FRAC: step capped at this fraction of distance-to-limit.
    //   Replaces v17.1's HALVE_FRACTION conceptually — now a ceiling, not the default.
    static final double MAX_STEP_FRAC        = 0.5;   // PLACEHOLDER
    // MIN_STEP_FRAC: step floor as fraction of current parameter value.
    //   Step below MIN_STEP_FRAC × |param| is treated as zero and triggers a switch.
    static final double MIN_STEP_FRAC        = 0.001; // PLACEHOLDER
    // MIN_MEANINGFUL_DELTA: actual |delta| < this × |param| → pinned (same meaning
    //   as v17.1's MIN_STEP_DELTA_FRAC; renamed for clarity).
    static final double MIN_MEANINGFUL_DELTA = 0.001; // PLACEHOLDER

    // ── settling (peak-relative) ───────────────────────────────────────────────
    // PEAK_REL_THRESHOLD: chain is settled when runningAvgV drops to this fraction
    //   of the burst's peak |v|. 0.10 = 10% of peak.
    static final double PEAK_REL_THRESHOLD = 0.10;  // PLACEHOLDER
    // ABSOLUTE_V_FLOOR: safety floor — never declare settled if runningAvgV > this,
    //   regardless of peak (prevents tiny-peak bursts from triggering at 1e-6 µm/s).
    static final double ABSOLUTE_V_FLOOR   = 1e-5;  // µm/s
    // SETTLE_FRAMES: declared for documentation; handleSettleCheck() is unchanged
    //   from v17.1 and resolves in one frame without a wait counter.
    static final int    SETTLE_FRAMES      = 5;     // PLACEHOLDER (currently unused)

    // ── alternation and pacing ─────────────────────────────────────────────────
    static final int    FRAMES_BETWEEN_STEPS = 5;   // PLACEHOLDER
    static final int    STEPS_PER_TURN       = 2;   // PLACEHOLDER

    // ── convergence ───────────────────────────────────────────────────────────
    static final double CONV_TOL_UM = 1e-5; // PLACEHOLDER: 0.01 nm = 10 pm

    // ── sensitivity EMA smoothing (0 = use most recent measurement directly) ───
    static final double SENS_SMOOTHING = 0.0; // PLACEHOLDER

    // ── fracMove fallback (unchanged from v17.1) ───────────────────────────────
    static final double FRACMOVE_RETRY_STEP = 0.05;
    static final double FRACMOVE_FLOOR      = 0.1;

    // ── running average window ─────────────────────────────────────────────────
    static final int RUNNING_AVG_WINDOW = 10; // PLACEHOLDER

    // ── run-time scalars ───────────────────────────────────────────────────────
    private double fracMove, fracR, fracMoveTorq;
    private double target;
    private double dtFrame;

    // ── EMA ───────────────────────────────────────────────────────────────────
    private double  smoothed;
    private boolean smoothedInit;

    // ── linear regression ring buffer (SLOPE_WIN frames of smoothed values) ───
    private final double[] vaBuf = new double[SLOPE_WIN];
    private int vaHead, vaCount;

    // ── running average of |v| ring buffer ────────────────────────────────────
    private final double[] ravBuf = new double[RUNNING_AVG_WINDOW];
    private int ravHead, ravCount;
    private double runningAvgV;

    // ── empirical sensitivity per parameter ────────────────────────────────────
    private double  sensR, sensM;
    private boolean sensR_valid, sensM_valid;

    // ── step snapshot (for sensitivity measurement when chain settles) ─────────
    // sBeforeStep / paramBeforeStep: captured at the moment a step is applied.
    // stepParam: which parameter was stepped — may differ from activeParam if the
    // budget-exhausted switch happened between the step and settling detection.
    // Set to null after updateSensitivityFromCompletedStep() to prevent double-update.
    private double      sBeforeStep;
    private double      paramBeforeStep;
    private ActiveParam stepParam;

    // ── peak-relative settling ─────────────────────────────────────────────────
    // peakVAbsThisBurst: max |v| since the most recent step.
    // Reset in stepActiveParam() when a step is applied, and in switchActiveParam()
    // so each turn starts with a clean peak accumulation.
    private double peakVAbsThisBurst;

    // ── step sizing diagnostics (set in stepActiveParam, read in handleAdjust) ──
    private double lastIdealMag, lastClampedMag, lastStepSens;

    // ── phase state ───────────────────────────────────────────────────────────
    private Phase       phase;
    private ActiveParam activeParam;
    private int         framesSinceLastStep;
    private int         stepCount;
    private int         stepsThisTurn;

    // ── accessors ─────────────────────────────────────────────────────────────
    Phase   getPhase()        { return phase; }
    boolean isDone()          { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()     { return fracMove; }
    double  getFracR()        { return fracR; }
    double  getFracMoveTorq() { return fracMoveTorq; }
    double  getSmoothed()     { return smoothed; }

    String resultSummary() {
        return String.format("[V18] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  steps=%d",
            phase, fracMove, fracR, fracMoveTorq, stepCount);
    }

    // ── public API ────────────────────────────────────────────────────────────

    void start(double fm, double fr, double fmt, double targetMicrons, double tauSeconds) {
        fracMove     = fm;
        fracR        = fr;
        fracMoveTorq = fmt;
        target       = targetMicrons;
        dtFrame      = SECONDS_PER_FRAME;

        smoothed = 0; smoothedInit = false;
        vaHead = 0; vaCount = 0;
        ravHead = 0; ravCount = 0; runningAvgV = 0;

        sensR = SENS_R_INIT; sensM = SENS_M_INIT;
        sensR_valid = false; sensM_valid = false;

        sBeforeStep = 0; paramBeforeStep = 0; stepParam = null;
        peakVAbsThisBurst = 0;
        lastIdealMag = 0; lastClampedMag = 0; lastStepSens = 0;

        phase               = Phase.R_ADJUST;
        activeParam         = ActiveParam.FRAC_R;
        framesSinceLastStep = 0;
        stepCount           = 0;
        stepsThisTurn       = 0;

        System.out.printf(
            "[V18] armed: fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  target=%.6fµm%n"
            + "[V18]   FRAMES_BETWEEN_STEPS=%d  MAX_STEP_FRAC=%.2f  MIN_STEP_FRAC=%.3f  RUNNING_AVG_WINDOW=%d%n"
            + "[V18]   SENS_R_INIT=%.4f  SENS_M_INIT=%.4f  PEAK_REL_THRESHOLD=%.2f  ABSOLUTE_V_FLOOR=%.1e%n"
            + "[V18]   CONV_TOL_UM=%.2e  STEPS_PER_TURN=%d  MIN_MEANINGFUL_DELTA=%.3f  SLOPE_WIN=%d%n",
            fracMove, fracR, fracMoveTorq, target,
            FRAMES_BETWEEN_STEPS, MAX_STEP_FRAC, MIN_STEP_FRAC, RUNNING_AVG_WINDOW,
            SENS_R_INIT, SENS_M_INIT, PEAK_REL_THRESHOLD, ABSOLUTE_V_FLOOR,
            CONV_TOL_UM, STEPS_PER_TURN, MIN_MEANINGFUL_DELTA, SLOPE_WIN);
    }

    /**
     * Feed one output-frame smoothed deflection sample (µm; force must be ON).
     * Returns a ParamTriple when a parameter change is needed, null otherwise.
     * After isDone() returns true, always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        // ── Signal pipeline ───────────────────────────────────────────────────
        // EMA
        smoothed = smoothedInit
            ? EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed
            : observedMicrons;
        smoothedInit = true;

        // Linear regression velocity (NaN until buffer full after SLOPE_WIN frames)
        pushVA(smoothed);
        double v = (vaCount >= SLOPE_WIN) ? computeV() : Double.NaN;

        // Running average of |v|. NaN v → treated as 0.0 (conservative; SETTLE_CHECK
        // entry is independently guarded by vaCount >= SLOPE_WIN in handleAdjust).
        updateRunningAvgV(v);

        // Peak-velocity tracker: only update when v is not NaN.
        if (!Double.isNaN(v)) {
            peakVAbsThisBurst = Math.max(peakVAbsThisBurst, Math.abs(v));
        }

        framesSinceLastStep++;
        double err = smoothed - target;

        // ── Per-frame WATCH log ───────────────────────────────────────────────
        System.out.printf(
            "[V18:WATCH] wf=%d  s=%.6f  v=%s  err=%.6f  rav=%.3e  peak=%.3e  phase=%s  active=%s%n",
            framesSinceLastStep, smoothed,
            Double.isNaN(v) ? "    ---   " : String.format("%.3e", v),
            err, runningAvgV, peakVAbsThisBurst, phase, activeParam);

        // ── Phase dispatch ────────────────────────────────────────────────────
        switch (phase) {
            case R_ADJUST:
            case M_ADJUST:
                return handleAdjust(err, v);

            case SETTLE_CHECK:
                return handleSettleCheck(err);

            case RETRY_LOWER_FRACMOVE: {
                fracMove -= FRACMOVE_RETRY_STEP;
                if (fracMove < FRACMOVE_FLOOR) {
                    phase = Phase.FAILED;
                    System.out.printf(
                        "[V18] FAILED: fracMove %.4f - %.4f = %.4f < floor %.4f — giving up%n",
                        fracMove + FRACMOVE_RETRY_STEP, FRACMOVE_RETRY_STEP,
                        fracMove, FRACMOVE_FLOOR);
                    return null;
                }
                fracR        = FRAC_R_MAX;
                fracMoveTorq = FRAC_MT_MIN;
                activeParam  = ActiveParam.FRAC_R;
                phase        = Phase.R_ADJUST;
                framesSinceLastStep = 0;
                stepsThisTurn = 0;
                peakVAbsThisBurst = 0;
                stepParam = null;
                System.out.printf(
                    "[V18] PHASE: RETRY_LOWER_FRACMOVE → R_ADJUST"
                    + "  fracMove=%.4f  fracR=%.4f  fmt=%.4f%n",
                    fracMove, fracR, fracMoveTorq);
                return new ParamTriple(fracMove, fracR, fracMoveTorq);
            }

            case CONVERGED:
            case FAILED:
            default:
                return null;
        }
    }

    // ── R_ADJUST / M_ADJUST handler ───────────────────────────────────────────

    private ParamTriple handleAdjust(double err, double v) {
        // Gate: must wait FRAMES_BETWEEN_STEPS since last step before any action
        if (framesSinceLastStep < FRAMES_BETWEEN_STEPS) return null;

        if (chainDriftingWrongWay(err, v)) {
            double oldFr  = fracR;
            double oldFmt = fracMoveTorq;
            boolean meaningful = stepActiveParam(err);
            if (!meaningful) {
                // parameter pinned at hardware limit; switchActiveParam() called inside
                return null;
            }
            framesSinceLastStep = 0;
            System.out.printf(
                "[V18:STEP#%d] drift-correcting  active=%s  budget=%d/%d"
                + "  fr: %.4f→%.4f  fmt: %.4f→%.4f  err=%.6f  v=%.3e"
                + "  idealMag=%.4f  clampedMag=%.4f  sens=%.4f%n",
                stepCount, stepParam, stepsThisTurn, STEPS_PER_TURN,
                oldFr, fracR, oldFmt, fracMoveTorq, err, v,
                lastIdealMag, lastClampedMag, lastStepSens);
            if (stepsThisTurn >= STEPS_PER_TURN) {
                switchActiveParam();
            }
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        // Chain is responding correctly. Check settling.
        // Guard: vaCount >= SLOPE_WIN prevents premature SETTLE_CHECK entry when v is
        // NaN (treated as 0.0 in runningAvgV, keeping runningAvgV and peakVAbsThisBurst
        // both near zero, which would make chainHasSettledRelative() always true).
        if (vaCount >= SLOPE_WIN && chainHasSettledRelative()) {
            updateSensitivityFromCompletedStep();
            System.out.printf(
                "[V18] PHASE: %s → SETTLE_CHECK  rav=%.3e  peak=%.3e%n",
                phase, runningAvgV, peakVAbsThisBurst);
            phase = Phase.SETTLE_CHECK;
        }
        return null;
    }

    // ── SETTLE_CHECK handler — unchanged from v17.1 ───────────────────────────

    // Always transitions out of SETTLE_CHECK in one call. SETTLE_FRAMES is declared
    // as a constant but not used here — handleSettleCheck() is a one-frame resolver.
    private ParamTriple handleSettleCheck(double err) {
        // (1) Convergence: chain settled within tolerance
        if (Math.abs(err) < CONV_TOL_UM) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V18] CONVERGED  s=%.6f  target=%.6f  |err|=%.3e < %.3e  steps=%d%n",
                smoothed, target, Math.abs(err), CONV_TOL_UM, stepCount);
            return null;
        }

        // (2) At soft limits and chain too stiff — lower fracMove and restart
        boolean atSoftLimits = (fracR >= FRAC_R_MAX - 1e-9)
                            && (fracMoveTorq <= FRAC_MT_MIN + 1e-9);
        if (err < 0 && atSoftLimits) {
            System.out.printf(
                "[V18] PHASE: SETTLE_CHECK → RETRY_LOWER_FRACMOVE  at soft limits  err=%.6f%n",
                err);
            phase = Phase.RETRY_LOWER_FRACMOVE;
            return null;
        }

        // (3) At stiff limits and chain too soft — failure
        boolean atStiffLimits = (fracR <= FRAC_R_MIN + 1e-9)
                             && (fracMoveTorq >= FRAC_MT_MAX - 1e-9);
        if (err > 0 && atStiffLimits) {
            phase = Phase.FAILED;
            System.out.printf(
                "[V18] FAILED: at stiff limits, still too soft  err=%.6f%n", err);
            return null;
        }

        // (4) Continue with alternation — switch to the other parameter with a fresh
        // STEPS_PER_TURN budget. switchActiveParam() resets stepsThisTurn to 0.
        System.out.printf(
            "[V18] PHASE: SETTLE_CHECK → continue alternation  err=%.6f%n", err);
        switchActiveParam();
        return null;
    }

    // ── chainDriftingWrongWay(err, v) — unchanged from v17.1 ─────────────────

    private static boolean chainDriftingWrongWay(double err, double v) {
        if (Double.isNaN(v)) return false;
        return (err > 0 && v > 0) || (err < 0 && v < 0);
    }

    // ── chainHasSettledRelative() — v18 replacement for absolute threshold ─────

    // Peak-relative settling: chain is "settled enough to act" when running-avg |v|
    // drops to PEAK_REL_THRESHOLD of the burst's peak |v|. The ABSOLUTE_V_FLOOR
    // prevents declaring settled at noise-floor × 0.1 when the burst peak was tiny.
    private boolean chainHasSettledRelative() {
        double relThresh = Math.max(PEAK_REL_THRESHOLD * peakVAbsThisBurst, ABSOLUTE_V_FLOOR);
        return runningAvgV < relThresh;
    }

    // ── stepActiveParam(err) — v18 revised (empirical-sensitivity step sizing) ─

    // Computes idealMag = |err| / sens, clamps to [MIN_STEP_FRAC × |param|,
    // MAX_STEP_FRAC × distToLimit], and applies. Snapshots sBeforeStep and
    // paramBeforeStep before applying so sensitivity can be measured on settling.
    // Returns true if a meaningful step was applied (|delta| > MIN_MEANINGFUL_DELTA × |param|);
    // false if pinned at hardware limit (switchActiveParam() called internally, no snapshot).
    private boolean stepActiveParam(double err) {
        boolean stiffen = (err > 0);

        if (activeParam == ActiveParam.FRAC_R) {
            double currentParam = fracR;
            double sens         = sensR_valid ? sensR : SENS_R_INIT;
            double idealMag     = Math.abs(err) / sens;
            // stiffen = decrease fracR toward FRAC_R_MIN; soften = increase toward FRAC_R_MAX
            double limit        = stiffen ? FRAC_R_MIN : FRAC_R_MAX;
            double distToLimit  = Math.abs(limit - currentParam);
            double maxMag       = MAX_STEP_FRAC * distToLimit;
            double minMag       = MIN_STEP_FRAC * Math.abs(currentParam);
            double mag          = clamp(idealMag, minMag, maxMag);
            double dirSign      = stiffen ? -1.0 : +1.0;
            double newFracR     = clamp(currentParam + dirSign * mag, FRAC_R_MIN, FRAC_R_MAX);
            double actualDelta  = Math.abs(newFracR - currentParam);

            // Store sizing diagnostics for the STEP#N log line in handleAdjust.
            lastIdealMag   = idealMag;
            lastClampedMag = mag;
            lastStepSens   = sens;

            if (actualDelta > MIN_MEANINGFUL_DELTA * Math.abs(currentParam)) {
                sBeforeStep     = smoothed;
                paramBeforeStep = currentParam;
                stepParam       = ActiveParam.FRAC_R;
                fracR           = newFracR;
                stepCount++;
                stepsThisTurn++;
                peakVAbsThisBurst = 0;  // fresh burst starts now
                return true;
            } else {
                System.out.printf(
                    "[V18] stepActiveParam: FRAC_R pinned"
                    + " (delta=%.4e ≤ %.3f × %.4f  idealMag=%.4f  clampedMag=%.4f  sens=%.4f) → switching%n",
                    actualDelta, MIN_MEANINGFUL_DELTA, Math.abs(currentParam),
                    idealMag, mag, sens);
                switchActiveParam();
                return false;
            }
        } else {  // FRAC_MT
            double currentParam = fracMoveTorq;
            double sens         = sensM_valid ? sensM : SENS_M_INIT;
            double idealMag     = Math.abs(err) / sens;
            // stiffen = increase fmt toward FRAC_MT_MAX; soften = decrease toward FRAC_MT_MIN
            double limit        = stiffen ? FRAC_MT_MAX : FRAC_MT_MIN;
            double distToLimit  = Math.abs(limit - currentParam);
            double maxMag       = MAX_STEP_FRAC * distToLimit;
            double minMag       = MIN_STEP_FRAC * Math.abs(currentParam);
            double mag          = clamp(idealMag, minMag, maxMag);
            double dirSign      = stiffen ? +1.0 : -1.0;
            double newFmt       = clamp(currentParam + dirSign * mag, FRAC_MT_MIN, FRAC_MT_MAX);
            double actualDelta  = Math.abs(newFmt - currentParam);

            lastIdealMag   = idealMag;
            lastClampedMag = mag;
            lastStepSens   = sens;

            if (actualDelta > MIN_MEANINGFUL_DELTA * Math.abs(currentParam)) {
                sBeforeStep     = smoothed;
                paramBeforeStep = currentParam;
                stepParam       = ActiveParam.FRAC_MT;
                fracMoveTorq    = newFmt;
                stepCount++;
                stepsThisTurn++;
                peakVAbsThisBurst = 0;  // fresh burst starts now
                return true;
            } else {
                System.out.printf(
                    "[V18] stepActiveParam: FRAC_MT pinned"
                    + " (delta=%.4e ≤ %.3f × %.4f  idealMag=%.4f  clampedMag=%.4f  sens=%.4f) → switching%n",
                    actualDelta, MIN_MEANINGFUL_DELTA, Math.abs(currentParam),
                    idealMag, mag, sens);
                switchActiveParam();
                return false;
            }
        }
    }

    // ── updateSensitivityFromCompletedStep() ──────────────────────────────────

    // Called when chainHasSettledRelative() first returns true after a step.
    // sAfter is the smoothed value at the moment of settling (Design decision A:
    // use the settling frame's smoothed directly, no additional wait).
    // stepParam is nulled after update to prevent double-update if chainHasSettledRelative()
    // fires again on the next turn before a new step is taken.
    private void updateSensitivityFromCompletedStep() {
        if (stepParam == null) return;

        double sAfter    = smoothed;
        double deltaS    = Math.abs(sAfter - sBeforeStep);
        double paramNow  = (stepParam == ActiveParam.FRAC_R) ? fracR : fracMoveTorq;
        double deltaParam = Math.abs(paramNow - paramBeforeStep);

        if (deltaParam < 1e-9) { stepParam = null; return; }  // degenerate step
        if (deltaS < CONV_TOL_UM) { stepParam = null; return; } // no measurable effect

        double newSens = deltaS / deltaParam;

        if (stepParam == ActiveParam.FRAC_R) {
            double oldSens = sensR;
            sensR = (SENS_SMOOTHING > 0 && sensR_valid)
                    ? (SENS_SMOOTHING * sensR + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensR_valid = true;
            System.out.printf(
                "[V18] sens update: FRAC_R  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensR, deltaS, deltaParam);
        } else {
            double oldSens = sensM;
            sensM = (SENS_SMOOTHING > 0 && sensM_valid)
                    ? (SENS_SMOOTHING * sensM + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensM_valid = true;
            System.out.printf(
                "[V18] sens update: FRAC_MT  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensM, deltaS, deltaParam);
        }
        stepParam = null;  // prevent double-update on subsequent settling checks
    }

    // ── switchActiveParam() — unchanged from v17.1 except peakVAbsThisBurst reset ──

    // Resets peakVAbsThisBurst so the new parameter's turn starts with a clean peak.
    // Without this reset, a stale high peak from the previous turn could cause
    // chainHasSettledRelative() to fire at the start of the new turn (before any step),
    // producing a spurious SETTLE_CHECK → switch cycle.
    private void switchActiveParam() {
        ActiveParam from = activeParam;
        if (activeParam == ActiveParam.FRAC_R) {
            activeParam = ActiveParam.FRAC_MT;
            phase       = Phase.M_ADJUST;
        } else {
            activeParam = ActiveParam.FRAC_R;
            phase       = Phase.R_ADJUST;
        }
        framesSinceLastStep = 0;
        stepsThisTurn = 0;
        peakVAbsThisBurst = 0;
        System.out.printf("[V18] switch active: %s → %s  fr=%.4f  fmt=%.4f%n",
            from, activeParam, fracR, fracMoveTorq);
    }

    // ── Signal pipeline helpers (copied verbatim from v17.1) ──────────────────

    private void pushVA(double s) {
        vaBuf[vaHead] = s;
        vaHead = (vaHead + 1) % SLOPE_WIN;
        if (vaCount < SLOPE_WIN) vaCount++;
    }

    /**
     * Linear regression slope on the last SLOPE_WIN frames (evenly spaced at dtFrame).
     * After pushVA(), vaHead points to the oldest slot; iterating k=0..SLOPE_WIN-1
     * from vaHead gives entries from oldest (k=0) to newest (k=SLOPE_WIN-1).
     * u_k = (k - mid)*dtFrame in [-half, +half]. Slope = sumUS / sumU2 in µm/s.
     */
    private double computeV() {
        double mid   = (SLOPE_WIN - 1) * 0.5;
        double sumUS = 0, sumU2 = 0;
        for (int k = 0; k < SLOPE_WIN; k++) {
            int    idx = (vaHead + k) % SLOPE_WIN;
            double u   = (k - mid) * dtFrame;
            double s   = vaBuf[idx];
            sumUS += u * s;
            sumU2 += u * u;
        }
        return sumU2 < 1e-30 ? 0.0 : sumUS / sumU2;
    }

    private void updateRunningAvgV(double v) {
        double absV = Double.isNaN(v) ? 0.0 : Math.abs(v);
        ravBuf[ravHead] = absV;
        ravHead = (ravHead + 1) % RUNNING_AVG_WINDOW;
        if (ravCount < RUNNING_AVG_WINDOW) ravCount++;
        // When ravCount < RUNNING_AVG_WINDOW: valid entries are ravBuf[0..ravCount-1]
        // (ravHead hasn't wrapped yet). When full: ravBuf[0..RUNNING_AVG_WINDOW-1]
        // are all valid. Sum ravBuf[0..ravCount-1] gives the correct mean in both cases.
        double sum = 0;
        for (int k = 0; k < ravCount; k++) sum += ravBuf[k];
        runningAvgV = ravCount > 0 ? sum / ravCount : 0.0;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
