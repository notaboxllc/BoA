package boxOfActin;

/**
 * DeflectionTunerV19 — fast-path for far-from-target adjustments.
 *
 * Inherits all of V18's structure (empirical-sensitivity overshoot controller,
 * FRAC_R/FRAC_MT alternation, peak-relative settling, handleSettleCheck).
 * Adds a "far-from-target" fast path in R_ADJUST/M_ADJUST that bypasses the
 * normal chainHasSettledRelative() quiescence requirement when
 * |err| > FAR_FROM_TARGET_THRESHOLD (10 × CONV_TOL_UM):
 *
 *   Fast path fires when ALL of the following hold:
 *     (a) |err| > FAR_FROM_TARGET_THRESHOLD
 *     (b) v is non-NaN (regression buffer full)
 *     (c) framesSinceLastStep >= MIN_FRAMES_FAR (10)
 *     (d) EITHER sign(v) has been consistent for the last SIGN_STABLE_WIN (5) frames
 *             OR |v| * dtFrame < V_SMALL_FRAC (0.5) * |err|
 *
 * Condition (d) gives two routes to firing: sign_stable means we trust the
 * velocity direction; v_small means the chain is barely moving relative to
 * the error (nearly settled at a non-target value) so we can step now.
 *
 * When |err| <= FAR_FROM_TARGET_THRESHOLD the existing chainHasSettledRelative()
 * logic is used unchanged — near-target settling is intentionally careful.
 *
 * The alternation logic, sensitivity-update logic, and handleSettleCheck() are
 * byte-identical to V18. The fast path only changes *when* an adjustment fires.
 *
 * ALL algorithm constants marked PLACEHOLDER — calibrate after first trace.
 */
class DeflectionTunerV19 {

    enum Phase { R_ADJUST, M_ADJUST, SETTLE_CHECK, RETRY_LOWER_FRACMOVE, CONVERGED, FAILED }

    enum ActiveParam { FRAC_R, FRAC_MT }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── hardware limits (same as v14–v18) ─────────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── EMA constant (same as v15–v18) ────────────────────────────────────────
    static final double EMA_ALPHA         = 0.05;
    static final double SECONDS_PER_FRAME = 0.01;  // toFileInterval=100 × deltaT=1e-4

    // ── signal pipeline constant ───────────────────────────────────────────────
    static final int    W_A       = 12;
    static final int    SLOPE_WIN = Math.max(5, W_A);  // = 12

    // ── sensitivity bootstrap (initial guesses, only before first measurement) ──
    static final double SENS_R_INIT = 0.005;  // PLACEHOLDER: µm per unit-fracR
    static final double SENS_M_INIT = 0.05;   // PLACEHOLDER: µm per unit-fracMoveTorq

    // ── step magnitude bounds ──────────────────────────────────────────────────
    static final double MAX_STEP_FRAC        = 0.5;   // PLACEHOLDER
    static final double MIN_STEP_FRAC        = 0.001; // PLACEHOLDER
    static final double MIN_MEANINGFUL_DELTA = 0.001; // PLACEHOLDER

    // ── settling (peak-relative) ───────────────────────────────────────────────
    static final double PEAK_REL_THRESHOLD = 0.10;  // PLACEHOLDER
    static final double ABSOLUTE_V_FLOOR   = 1e-5;  // µm/s
    static final int    SETTLE_FRAMES      = 5;     // PLACEHOLDER (declared; handleSettleCheck resolves in one frame)

    // ── alternation and pacing ─────────────────────────────────────────────────
    static final int    FRAMES_BETWEEN_STEPS = 5;   // PLACEHOLDER
    static final int    STEPS_PER_TURN       = 2;   // PLACEHOLDER

    // ── convergence ───────────────────────────────────────────────────────────
    static final double CONV_TOL_UM = 1e-5; // PLACEHOLDER: 0.01 nm

    // ── far-from-target fast path (V19-specific) ──────────────────────────────
    // FAR_FROM_TARGET_THRESHOLD: fast path active when |err| exceeds this value.
    //   Near-target settling (|err| <= threshold) uses chainHasSettledRelative().
    static final double FAR_FROM_TARGET_THRESHOLD = 10.0 * CONV_TOL_UM;  // 1e-4 µm
    // MIN_FRAMES_FAR: minimum framesSinceLastStep before fast path can fire.
    //   Gives the chain time to respond to the previous step. Stronger than
    //   FRAMES_BETWEEN_STEPS=5; the fast path adds this as the binding gate.
    static final int    MIN_FRAMES_FAR   = 10;   // PLACEHOLDER
    // SIGN_STABLE_WIN: sign(v) must be consistent for this many consecutive frames.
    //   When the last SIGN_STABLE_WIN non-NaN v values all have the same sign, we
    //   consider the velocity direction reliable enough to act on.
    static final int    SIGN_STABLE_WIN  = 5;    // PLACEHOLDER
    // V_SMALL_FRAC: fast path fires if |v| * dtFrame < V_SMALL_FRAC * |err|.
    //   Compares per-frame displacement (µm) to the error (µm). When |v| is tiny
    //   relative to |err|, the chain is nearly settled at the wrong value.
    static final double V_SMALL_FRAC     = 0.5;  // PLACEHOLDER

    // ── sensitivity EMA smoothing (0 = use most recent measurement directly) ───
    static final double SENS_SMOOTHING = 0.0; // PLACEHOLDER

    // ── fracMove fallback (unchanged from v17.1/v18) ──────────────────────────
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
    // stepParam is nulled after updateSensitivityFromCompletedStep() to prevent
    // double-update. May differ from activeParam if budget-exhausted switch happened.
    private double      sBeforeStep;
    private double      paramBeforeStep;
    private ActiveParam stepParam;

    // ── peak-relative settling ─────────────────────────────────────────────────
    private double peakVAbsThisBurst;

    // ── step sizing diagnostics (set in stepActiveParam, read in handleAdjust) ──
    private double lastIdealMag, lastClampedMag, lastStepSens;

    // ── v-sign ring buffer (V19: fast-path sign_stable check) ─────────────────
    // Tracks sign(v) for the last SIGN_STABLE_WIN non-NaN frames since the most
    // recent step. Values: +1 (positive), -1 (negative), 0 (zero velocity).
    // Reset in stepActiveParam() when a step is applied, and in switchActiveParam().
    private final int[] vSignBuf = new int[SIGN_STABLE_WIN];
    private int vSignHead, vSignCount;

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
        return String.format("[V19] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  steps=%d",
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

        vSignHead = 0; vSignCount = 0;

        phase               = Phase.R_ADJUST;
        activeParam         = ActiveParam.FRAC_R;
        framesSinceLastStep = 0;
        stepCount           = 0;
        stepsThisTurn       = 0;

        System.out.printf(
            "[V19] armed: fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  target=%.6fµm%n"
            + "[V19]   FRAMES_BETWEEN_STEPS=%d  MAX_STEP_FRAC=%.2f  MIN_STEP_FRAC=%.3f  RUNNING_AVG_WINDOW=%d%n"
            + "[V19]   SENS_R_INIT=%.4f  SENS_M_INIT=%.4f  PEAK_REL_THRESHOLD=%.2f  ABSOLUTE_V_FLOOR=%.1e%n"
            + "[V19]   CONV_TOL_UM=%.2e  STEPS_PER_TURN=%d  MIN_MEANINGFUL_DELTA=%.3f  SLOPE_WIN=%d%n"
            + "[V19]   FAR_THRESHOLD=%.2e  MIN_FRAMES_FAR=%d  SIGN_STABLE_WIN=%d  V_SMALL_FRAC=%.2f%n",
            fracMove, fracR, fracMoveTorq, target,
            FRAMES_BETWEEN_STEPS, MAX_STEP_FRAC, MIN_STEP_FRAC, RUNNING_AVG_WINDOW,
            SENS_R_INIT, SENS_M_INIT, PEAK_REL_THRESHOLD, ABSOLUTE_V_FLOOR,
            CONV_TOL_UM, STEPS_PER_TURN, MIN_MEANINGFUL_DELTA, SLOPE_WIN,
            FAR_FROM_TARGET_THRESHOLD, MIN_FRAMES_FAR, SIGN_STABLE_WIN, V_SMALL_FRAC);
    }

    /**
     * Feed one output-frame smoothed deflection sample (µm; force must be ON).
     * Returns a ParamTriple when a parameter change is needed, null otherwise.
     * After isDone() returns true, always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        // ── Signal pipeline ───────────────────────────────────────────────────
        smoothed = smoothedInit
            ? EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed
            : observedMicrons;
        smoothedInit = true;

        pushVA(smoothed);
        double v = (vaCount >= SLOPE_WIN) ? computeV() : Double.NaN;

        // NaN v → treated as 0.0 in updateRunningAvgV (conservative; SETTLE_CHECK
        // entry is independently guarded by vaCount >= SLOPE_WIN in handleAdjust).
        updateRunningAvgV(v);

        if (!Double.isNaN(v)) {
            peakVAbsThisBurst = Math.max(peakVAbsThisBurst, Math.abs(v));
            trackVSign(v);  // V19: accumulate sign for fast-path sign_stable check
        }

        framesSinceLastStep++;
        double err = smoothed - target;

        // ── Per-frame WATCH log ───────────────────────────────────────────────
        System.out.printf(
            "[V19:WATCH] wf=%d  s=%.6f  v=%s  err=%.6f  rav=%.3e  peak=%.3e  phase=%s  active=%s%n",
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
                        "[V19] FAILED: fracMove %.4f - %.4f = %.4f < floor %.4f — giving up%n",
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
                vSignHead = 0; vSignCount = 0;
                stepParam = null;
                System.out.printf(
                    "[V19] PHASE: RETRY_LOWER_FRACMOVE → R_ADJUST"
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
    // V19: adds fast path when |err| > FAR_FROM_TARGET_THRESHOLD.

    private ParamTriple handleAdjust(double err, double v) {
        // Gate: must wait FRAMES_BETWEEN_STEPS since last step before any action.
        if (framesSinceLastStep < FRAMES_BETWEEN_STEPS) return null;

        if (chainDriftingWrongWay(err, v)) {
            double oldFr  = fracR;
            double oldFmt = fracMoveTorq;
            boolean meaningful = stepActiveParam(err);
            if (!meaningful) {
                // parameter pinned; switchActiveParam() called inside
                return null;
            }
            framesSinceLastStep = 0;
            System.out.printf(
                "[V19:STEP#%d] drift-correcting  active=%s  budget=%d/%d"
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

        // Chain is responding correctly (not drifting wrong way).
        double absErr = Math.abs(err);

        if (absErr > FAR_FROM_TARGET_THRESHOLD) {
            // V19 FAST PATH: skip full settling when far from target.
            // Requires v non-NaN and framesSinceLastStep >= MIN_FRAMES_FAR; then
            // fires if sign_stable (v direction consistent) OR v_small (barely moving).
            if (!Double.isNaN(v) && framesSinceLastStep >= MIN_FRAMES_FAR) {
                boolean signStable = isVSignConsistent();
                boolean vSmall = Math.abs(v) * SECONDS_PER_FRAME < V_SMALL_FRAC * absErr;
                if (signStable || vSmall) {
                    String reason = signStable ? "sign_stable" : "v_small";
                    System.out.printf(
                        "[V19:FAST] step=%d  active=%s  |err|=%.6f  v=%.3e  frames_used=%d  reason=%s%n",
                        stepCount, activeParam, absErr, v, framesSinceLastStep, reason);
                    double oldFr  = fracR;
                    double oldFmt = fracMoveTorq;
                    boolean meaningful = stepActiveParam(err);
                    if (!meaningful) return null;
                    framesSinceLastStep = 0;
                    System.out.printf(
                        "[V19:STEP#%d] fast-path  active=%s  budget=%d/%d"
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
            }
            // else: conditions not met — wait for more frames
        } else {
            // NEAR TARGET (|err| <= FAR_FROM_TARGET_THRESHOLD): use V18's careful
            // settling logic unchanged. Guard: vaCount >= SLOPE_WIN prevents premature
            // SETTLE_CHECK when v is NaN (runningAvgV near zero → chainHasSettledRelative
            // would always return true).
            if (vaCount >= SLOPE_WIN && chainHasSettledRelative()) {
                updateSensitivityFromCompletedStep();
                System.out.printf(
                    "[V19] PHASE: %s → SETTLE_CHECK  rav=%.3e  peak=%.3e%n",
                    phase, runningAvgV, peakVAbsThisBurst);
                phase = Phase.SETTLE_CHECK;
            }
        }
        return null;
    }

    // ── SETTLE_CHECK handler — byte-identical to v18 (unchanged from v17.1) ───
    // Always transitions out in one call.

    private ParamTriple handleSettleCheck(double err) {
        // (1) Convergence: chain settled within tolerance
        if (Math.abs(err) < CONV_TOL_UM) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V19] CONVERGED  s=%.6f  target=%.6f  |err|=%.3e < %.3e  steps=%d%n",
                smoothed, target, Math.abs(err), CONV_TOL_UM, stepCount);
            return null;
        }

        // (2) At soft limits and chain too stiff — lower fracMove and restart
        boolean atSoftLimits = (fracR >= FRAC_R_MAX - 1e-9)
                            && (fracMoveTorq <= FRAC_MT_MIN + 1e-9);
        if (err < 0 && atSoftLimits) {
            System.out.printf(
                "[V19] PHASE: SETTLE_CHECK → RETRY_LOWER_FRACMOVE  at soft limits  err=%.6f%n",
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
                "[V19] FAILED: at stiff limits, still too soft  err=%.6f%n", err);
            return null;
        }

        // (4) Continue alternation — switch to the other parameter
        System.out.printf(
            "[V19] PHASE: SETTLE_CHECK → continue alternation  err=%.6f%n", err);
        switchActiveParam();
        return null;
    }

    // ── chainDriftingWrongWay — unchanged from v17.1/v18 ─────────────────────

    private static boolean chainDriftingWrongWay(double err, double v) {
        if (Double.isNaN(v)) return false;
        return (err > 0 && v > 0) || (err < 0 && v < 0);
    }

    // ── chainHasSettledRelative — v18 peak-relative settling ─────────────────
    // Only reached in the near-target branch (|err| <= FAR_FROM_TARGET_THRESHOLD).

    private boolean chainHasSettledRelative() {
        double relThresh = Math.max(PEAK_REL_THRESHOLD * peakVAbsThisBurst, ABSOLUTE_V_FLOOR);
        return runningAvgV < relThresh;
    }

    // ── stepActiveParam — v18 empirical-sensitivity step sizing, + vSign reset ─

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
                peakVAbsThisBurst = 0;
                vSignHead = 0; vSignCount = 0;  // V19: fresh sign tracking for new burst
                return true;
            } else {
                System.out.printf(
                    "[V19] stepActiveParam: FRAC_R pinned"
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
                peakVAbsThisBurst = 0;
                vSignHead = 0; vSignCount = 0;  // V19: fresh sign tracking for new burst
                return true;
            } else {
                System.out.printf(
                    "[V19] stepActiveParam: FRAC_MT pinned"
                    + " (delta=%.4e ≤ %.3f × %.4f  idealMag=%.4f  clampedMag=%.4f  sens=%.4f) → switching%n",
                    actualDelta, MIN_MEANINGFUL_DELTA, Math.abs(currentParam),
                    idealMag, mag, sens);
                switchActiveParam();
                return false;
            }
        }
    }

    // ── updateSensitivityFromCompletedStep — byte-identical to v18 ───────────
    // Only reached via the near-target settling path (SETTLE_CHECK entry). Fast-
    // path steps skip sensitivity measurement; the pending snapshot persists until
    // the chain eventually reaches near-target and settling fires naturally.

    private void updateSensitivityFromCompletedStep() {
        if (stepParam == null) return;

        double sAfter     = smoothed;
        double deltaS     = Math.abs(sAfter - sBeforeStep);
        double paramNow   = (stepParam == ActiveParam.FRAC_R) ? fracR : fracMoveTorq;
        double deltaParam = Math.abs(paramNow - paramBeforeStep);

        if (deltaParam < 1e-9) { stepParam = null; return; }
        if (deltaS < CONV_TOL_UM) { stepParam = null; return; }

        double newSens = deltaS / deltaParam;

        if (stepParam == ActiveParam.FRAC_R) {
            double oldSens = sensR;
            sensR = (SENS_SMOOTHING > 0 && sensR_valid)
                    ? (SENS_SMOOTHING * sensR + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensR_valid = true;
            System.out.printf(
                "[V19] sens update: FRAC_R  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensR, deltaS, deltaParam);
        } else {
            double oldSens = sensM;
            sensM = (SENS_SMOOTHING > 0 && sensM_valid)
                    ? (SENS_SMOOTHING * sensM + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensM_valid = true;
            System.out.printf(
                "[V19] sens update: FRAC_MT  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensM, deltaS, deltaParam);
        }
        stepParam = null;
    }

    // ── switchActiveParam — v18 logic + vSign reset ───────────────────────────
    // Resetting vSignBuf prevents a stale sign history from the previous turn from
    // triggering the fast path immediately on the new turn before any step is taken.

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
        vSignHead = 0; vSignCount = 0;
        System.out.printf("[V19] switch active: %s → %s  fr=%.4f  fmt=%.4f%n",
            from, activeParam, fracR, fracMoveTorq);
    }

    // ── V19-specific: velocity-sign ring buffer ───────────────────────────────

    // Records sign(v) for each non-NaN frame. Called from feed() after computing v.
    private void trackVSign(double v) {
        int s = (v > 0) ? 1 : (v < 0) ? -1 : 0;
        vSignBuf[vSignHead] = s;
        vSignHead = (vSignHead + 1) % SIGN_STABLE_WIN;
        if (vSignCount < SIGN_STABLE_WIN) vSignCount++;
    }

    // Returns true when the buffer is full and all recorded signs agree (non-zero).
    // The circular buffer has indices [0..SIGN_STABLE_WIN-1]; all are valid when
    // vSignCount == SIGN_STABLE_WIN (it then stays at that value).
    private boolean isVSignConsistent() {
        if (vSignCount < SIGN_STABLE_WIN) return false;
        int s0 = vSignBuf[0];
        if (s0 == 0) return false;  // zero velocity is not a stable sign
        for (int i = 1; i < SIGN_STABLE_WIN; i++) {
            if (vSignBuf[i] != s0) return false;
        }
        return true;
    }

    // ── Signal pipeline helpers (copied verbatim from v17.1/v18) ─────────────

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
        // ravBuf[0..ravCount-1] always holds all valid entries.
        double sum = 0;
        for (int k = 0; k < ravCount; k++) sum += ravBuf[k];
        runningAvgV = ravCount > 0 ? sum / ravCount : 0.0;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
