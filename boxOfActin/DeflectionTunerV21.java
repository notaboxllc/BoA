package boxOfActin;

/**
 * DeflectionTunerV21 — bounded sensitivity learning and single-parameter near-target mode.
 *
 * Fixes two failure modes introduced or revealed by V20:
 *
 * FIX 1 — Bounded sensitivity learner.
 *   V20's SENS_SMOOTHING=0.0 replaced sens outright on every update. When deltaParam
 *   was tiny (parameter near limit, step clamped, or pinned logic produced a tiny
 *   change), deltaS/deltaParam exploded and produced sens values spanning ~4 orders
 *   of magnitude in the empirical trace.
 *   Three sub-fixes applied in updateSensFromPendingSnapshot() (and the backstop):
 *   (a) DELTA_PARAM_FLOOR_FRAC=0.01: skip update if |deltaParam| < 1% of param value;
 *       logs [V21:SENS_SKIP].
 *   (b) Clamp raw deltaS/deltaParam to [SENS_MIN, SENS_MAX] before blending;
 *       logs [V21:SENS_CLAMP] when clamping fires.
 *   (c) BLEND_ALPHA=0.3 EMA: each update moves 30% toward the new measurement.
 *   (d) Final blended sens also clamped to [SENS_MIN, SENS_MAX] (belt-and-suspenders).
 *   SENS_SMOOTHING constant removed; BLEND_ALPHA replaces it.
 *
 * FIX 2 — Single-parameter near-target mode.
 *   Near target, both parameters were nudged every alternation cycle, and cross-coupling
 *   produced oscillation preventing lock-in. When |err| < NEAR_TARGET_THRESHOLD=5e-5
 *   at a budget-exhausted switch point, the controller enters NEAR_TARGET_SINGLE_PARAM
 *   phase: one parameter is chosen (the one with more remaining headroom) and locked
 *   until convergence or re-arm. Sensitivity learning remains active.
 *
 * Everything else (signal pipeline, fast-path, alternation budget, step sizing, pinning,
 * handleSettleCheck structure) is unchanged from V20.
 *
 * ALL algorithm constants marked PLACEHOLDER — calibrate after first trace.
 */
class DeflectionTunerV21 {

    enum Phase { R_ADJUST, M_ADJUST, SETTLE_CHECK, NEAR_TARGET_SINGLE_PARAM,
                 RETRY_LOWER_FRACMOVE, CONVERGED, FAILED }

    enum ActiveParam { FRAC_R, FRAC_MT }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── hardware limits (same as v14–v20) ─────────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── EMA constant (same as v15–v20) ────────────────────────────────────────
    static final double EMA_ALPHA         = 0.05;
    static final double SECONDS_PER_FRAME = 0.01;  // toFileInterval=100 × deltaT=1e-4

    // ── signal pipeline constant ───────────────────────────────────────────────
    static final int    W_A       = 12;
    static final int    SLOPE_WIN = Math.max(5, W_A);  // = 12

    // ── sensitivity bootstrap (initial guesses, only before first measurement) ──
    static final double SENS_R_INIT = 0.005;  // PLACEHOLDER: µm per unit-fracR
    static final double SENS_M_INIT = 0.05;   // PLACEHOLDER: µm per unit-fracMoveTorq

    // ── V21 Fix 1: bounded sensitivity learning ───────────────────────────────
    // (a) Floor on |deltaParam| relative to current param value before dividing.
    static final double DELTA_PARAM_FLOOR_FRAC = 0.01;  // skip update if deltaParam < 1% of param
    // (b/d) Clamp range for raw measurement and final blended sens, per parameter.
    //   SENS_MIN = 0.1 * SENS_INIT, SENS_MAX = 20.0 * SENS_INIT
    static final double SENS_MIN_R = 0.1  * SENS_R_INIT;  // = 0.0005
    static final double SENS_MAX_R = 20.0 * SENS_R_INIT;  // = 0.1
    static final double SENS_MIN_M = 0.1  * SENS_M_INIT;  // = 0.005
    static final double SENS_MAX_M = 20.0 * SENS_M_INIT;  // = 1.0
    // (c) EMA blend weight toward the new measurement (replaces SENS_SMOOTHING=0.0 from V20).
    //   V20 used outright replacement (SENS_SMOOTHING=0.0 → newSens directly).
    //   V21 uses: sens_new = (1 - BLEND_ALPHA) * sens_old + BLEND_ALPHA * clamped_raw
    static final double BLEND_ALPHA = 0.3;  // PLACEHOLDER

    // ── V21 Fix 2: single-parameter near-target mode ──────────────────────────
    // Entry: |err| < NEAR_TARGET_THRESHOLD at a budget-exhausted switch point.
    // Exit: convergence (|err| < CONV_TOL_UM) or re-arm (|err| > 2 × threshold).
    static final double NEAR_TARGET_THRESHOLD = 5.0 * 1e-5;  // = 5e-5 µm  (5 × CONV_TOL_UM)

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

    // ── far-from-target fast path ─────────────────────────────────────────────
    static final double FAR_FROM_TARGET_THRESHOLD = 10.0 * CONV_TOL_UM;  // 1e-4 µm
    static final int    MIN_FRAMES_FAR        = 10;  // PLACEHOLDER
    static final int    V_PEAK_WIN            = 3;   // PLACEHOLDER
    static final double V_PEAK_TOL            = 0.05; // PLACEHOLDER
    static final int    FAR_TIMEOUT_FRAMES    = 30;  // PLACEHOLDER

    // ── fracMove fallback (unchanged from v17.1–v20) ──────────────────────────
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

    // ── step snapshot (for sensitivity measurement) ────────────────────────────
    private double      sBeforeStep;
    private double      paramBeforeStep;
    private ActiveParam stepParam;

    // ── peak-relative settling ─────────────────────────────────────────────────
    private double peakVAbsThisBurst;

    // ── step sizing diagnostics (set in stepActiveParam, read in handleAdjust) ──
    private double lastIdealMag, lastClampedMag, lastStepSens;

    // ── v-magnitude ring buffer (fast-path peak_passed check, from V20) ────────
    private final double[] vMagBuf = new double[V_PEAK_WIN + 1];
    private int vMagHead, vMagCount;

    // ── phase state ───────────────────────────────────────────────────────────
    private Phase       phase;
    private ActiveParam activeParam;
    private ActiveParam nearTargetParam;  // V21: locked param in NEAR_TARGET_SINGLE_PARAM
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
        return String.format("[V21] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  steps=%d",
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

        vMagHead = 0; vMagCount = 0;

        phase               = Phase.R_ADJUST;
        activeParam         = ActiveParam.FRAC_R;
        nearTargetParam     = null;
        framesSinceLastStep = 0;
        stepCount           = 0;
        stepsThisTurn       = 0;

        System.out.printf(
            "[V21] armed: fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  target=%.6fµm%n"
            + "[V21]   FRAMES_BETWEEN_STEPS=%d  MAX_STEP_FRAC=%.2f  MIN_STEP_FRAC=%.3f  RUNNING_AVG_WINDOW=%d%n"
            + "[V21]   SENS_R_INIT=%.4f  SENS_M_INIT=%.4f  PEAK_REL_THRESHOLD=%.2f  ABSOLUTE_V_FLOOR=%.1e%n"
            + "[V21]   CONV_TOL_UM=%.2e  STEPS_PER_TURN=%d  MIN_MEANINGFUL_DELTA=%.3f  SLOPE_WIN=%d%n"
            + "[V21]   FAR_THRESHOLD=%.2e  MIN_FRAMES_FAR=%d  V_PEAK_WIN=%d  V_PEAK_TOL=%.2f  FAR_TIMEOUT_FRAMES=%d%n"
            + "[V21]   DELTA_PARAM_FLOOR_FRAC=%.3f  SENS_MIN_R=%.4f  SENS_MAX_R=%.3f  SENS_MIN_M=%.4f  SENS_MAX_M=%.3f%n"
            + "[V21]   BLEND_ALPHA=%.2f  NEAR_TARGET_THRESHOLD=%.2e%n",
            fracMove, fracR, fracMoveTorq, target,
            FRAMES_BETWEEN_STEPS, MAX_STEP_FRAC, MIN_STEP_FRAC, RUNNING_AVG_WINDOW,
            SENS_R_INIT, SENS_M_INIT, PEAK_REL_THRESHOLD, ABSOLUTE_V_FLOOR,
            CONV_TOL_UM, STEPS_PER_TURN, MIN_MEANINGFUL_DELTA, SLOPE_WIN,
            FAR_FROM_TARGET_THRESHOLD, MIN_FRAMES_FAR, V_PEAK_WIN, V_PEAK_TOL, FAR_TIMEOUT_FRAMES,
            DELTA_PARAM_FLOOR_FRAC, SENS_MIN_R, SENS_MAX_R, SENS_MIN_M, SENS_MAX_M,
            BLEND_ALPHA, NEAR_TARGET_THRESHOLD);
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

        updateRunningAvgV(v);

        if (!Double.isNaN(v)) {
            peakVAbsThisBurst = Math.max(peakVAbsThisBurst, Math.abs(v));
            trackVMag(v);
        }

        framesSinceLastStep++;
        double err = smoothed - target;

        // ── Per-frame WATCH log ───────────────────────────────────────────────
        System.out.printf(
            "[V21:WATCH] wf=%d  s=%.6f  v=%s  err=%.6f  rav=%.3e  peak=%.3e  phase=%s  active=%s%n",
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

            case NEAR_TARGET_SINGLE_PARAM:
                return handleNearTargetSingleParam(err, v);

            case RETRY_LOWER_FRACMOVE: {
                fracMove -= FRACMOVE_RETRY_STEP;
                if (fracMove < FRACMOVE_FLOOR) {
                    phase = Phase.FAILED;
                    System.out.printf(
                        "[V21] FAILED: fracMove %.4f - %.4f = %.4f < floor %.4f — giving up%n",
                        fracMove + FRACMOVE_RETRY_STEP, FRACMOVE_RETRY_STEP,
                        fracMove, FRACMOVE_FLOOR);
                    return null;
                }
                fracR        = FRAC_R_MAX;
                fracMoveTorq = FRAC_MT_MIN;
                activeParam  = ActiveParam.FRAC_R;
                nearTargetParam = null;
                phase        = Phase.R_ADJUST;
                framesSinceLastStep = 0;
                stepsThisTurn = 0;
                peakVAbsThisBurst = 0;
                vMagHead = 0; vMagCount = 0;
                stepParam = null;
                System.out.printf(
                    "[V21] PHASE: RETRY_LOWER_FRACMOVE → R_ADJUST"
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
        if (framesSinceLastStep < FRAMES_BETWEEN_STEPS) return null;

        if (chainDriftingWrongWay(err, v)) {
            double oldFr  = fracR;
            double oldFmt = fracMoveTorq;
            boolean meaningful = stepActiveParam(err);
            if (!meaningful) {
                return null;
            }
            framesSinceLastStep = 0;
            System.out.printf(
                "[V21:STEP#%d] drift-correcting  active=%s  budget=%d/%d"
                + "  fr: %.4f→%.4f  fmt: %.4f→%.4f  err=%.6f  v=%.3e"
                + "  idealMag=%.4f  clampedMag=%.4f  sens=%.4f%n",
                stepCount, stepParam, stepsThisTurn, STEPS_PER_TURN,
                oldFr, fracR, oldFmt, fracMoveTorq, err, v,
                lastIdealMag, lastClampedMag, lastStepSens);
            // V21: check near-target entry before switching on budget exhaustion
            if (stepsThisTurn >= STEPS_PER_TURN) {
                if (!tryEnterNearTargetMode(err)) {
                    switchActiveParam();
                }
            }
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        double absErr = Math.abs(err);

        if (absErr > FAR_FROM_TARGET_THRESHOLD) {
            // Fast path: skip full settling when far from target.
            if (!Double.isNaN(v)) {
                boolean trigger = false;
                String  reason  = null;

                if (framesSinceLastStep >= FAR_TIMEOUT_FRAMES) {
                    trigger = true;
                    reason  = "timeout";
                } else if (framesSinceLastStep >= MIN_FRAMES_FAR && isPeakPassed()) {
                    trigger = true;
                    reason  = "peak_passed";
                }

                if (trigger) {
                    System.out.printf(
                        "[V21:FAST] step=%d  active=%s  |err|=%.6f  v=%.3e  frames_used=%d  reason=%s%n",
                        stepCount, activeParam, absErr, v, framesSinceLastStep, reason);
                    double oldFr  = fracR;
                    double oldFmt = fracMoveTorq;
                    boolean meaningful = stepActiveParam(err);
                    if (!meaningful) return null;
                    framesSinceLastStep = 0;
                    System.out.printf(
                        "[V21:STEP#%d] fast-path  active=%s  budget=%d/%d"
                        + "  fr: %.4f→%.4f  fmt: %.4f→%.4f  err=%.6f  v=%.3e"
                        + "  idealMag=%.4f  clampedMag=%.4f  sens=%.4f%n",
                        stepCount, stepParam, stepsThisTurn, STEPS_PER_TURN,
                        oldFr, fracR, oldFmt, fracMoveTorq, err, v,
                        lastIdealMag, lastClampedMag, lastStepSens);
                    // V21: check near-target entry before switching on budget exhaustion
                    if (stepsThisTurn >= STEPS_PER_TURN) {
                        if (!tryEnterNearTargetMode(err)) {
                            switchActiveParam();
                        }
                    }
                    return new ParamTriple(fracMove, fracR, fracMoveTorq);
                }
            }
        } else {
            // Near target: use careful settling logic.
            if (vaCount >= SLOPE_WIN && chainHasSettledRelative()) {
                updateSensitivityFromCompletedStep();
                System.out.printf(
                    "[V21] PHASE: %s → SETTLE_CHECK  rav=%.3e  peak=%.3e%n",
                    phase, runningAvgV, peakVAbsThisBurst);
                phase = Phase.SETTLE_CHECK;
            }
        }
        return null;
    }

    // ── SETTLE_CHECK handler ──────────────────────────────────────────────────

    private ParamTriple handleSettleCheck(double err) {
        // (1) Convergence
        if (Math.abs(err) < CONV_TOL_UM) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V21] CONVERGED  s=%.6f  target=%.6f  |err|=%.3e < %.3e  steps=%d%n",
                smoothed, target, Math.abs(err), CONV_TOL_UM, stepCount);
            return null;
        }

        // (2) At soft limits and chain too stiff — lower fracMove and restart
        boolean atSoftLimits = (fracR >= FRAC_R_MAX - 1e-9)
                            && (fracMoveTorq <= FRAC_MT_MIN + 1e-9);
        if (err < 0 && atSoftLimits) {
            System.out.printf(
                "[V21] PHASE: SETTLE_CHECK → RETRY_LOWER_FRACMOVE  at soft limits  err=%.6f%n",
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
                "[V21] FAILED: at stiff limits, still too soft  err=%.6f%n", err);
            return null;
        }

        // (4) Continue alternation — V21: check near-target mode before switching
        System.out.printf(
            "[V21] PHASE: SETTLE_CHECK → continue alternation  err=%.6f%n", err);
        if (!tryEnterNearTargetMode(err)) {
            switchActiveParam();
        }
        return null;
    }

    // ── V21: single-parameter near-target handler ─────────────────────────────
    // Entered when |err| < NEAR_TARGET_THRESHOLD at a switch point.
    // Never switches active parameter; stays locked on nearTargetParam.
    // Exits on convergence or re-arm.

    private ParamTriple handleNearTargetSingleParam(double err, double v) {
        double absErr = Math.abs(err);

        // Exit: convergence
        if (absErr < CONV_TOL_UM) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V21] CONVERGED  s=%.6f  target=%.6f  |err|=%.3e < %.3e  steps=%d%n",
                smoothed, target, absErr, CONV_TOL_UM, stepCount);
            return null;
        }

        // Exit: re-arm if error has grown too large
        if (absErr > 2.0 * NEAR_TARGET_THRESHOLD) {
            System.out.printf(
                "[V21:SINGLE_PARAM] exiting near-target single-param mode  |err|=%.6f  reason=re-armed%n",
                absErr);
            activeParam = nearTargetParam;
            stepsThisTurn = 0;
            peakVAbsThisBurst = 0;
            vMagHead = 0; vMagCount = 0;
            phase = (activeParam == ActiveParam.FRAC_R) ? Phase.R_ADJUST : Phase.M_ADJUST;
            return null;
        }

        // Ensure activeParam tracks the chosen locked parameter
        activeParam = nearTargetParam;

        // Gate
        if (framesSinceLastStep < FRAMES_BETWEEN_STEPS) return null;

        if (chainDriftingWrongWay(err, v)) {
            double oldFr  = fracR;
            double oldFmt = fracMoveTorq;
            boolean meaningful = stepActiveParam(err);
            if (!meaningful) {
                // Pinned: stepActiveParam called switchActiveParam internally.
                // Restore single-param mode state — we don't switch here.
                activeParam = nearTargetParam;
                phase = Phase.NEAR_TARGET_SINGLE_PARAM;
                stepsThisTurn = 0;
                return null;
            }
            framesSinceLastStep = 0;
            System.out.printf(
                "[V21:STEP#%d] single-param  active=%s"
                + "  fr: %.4f→%.4f  fmt: %.4f→%.4f  err=%.6f  v=%.3e"
                + "  idealMag=%.4f  clampedMag=%.4f  sens=%.4f%n",
                stepCount, nearTargetParam,
                oldFr, fracR, oldFmt, fracMoveTorq, err, v,
                lastIdealMag, lastClampedMag, lastStepSens);
            // Reset budget counter — no alternation switch in single-param mode
            stepsThisTurn = 0;
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        // Chain responding correctly — check settling
        if (vaCount >= SLOPE_WIN && chainHasSettledRelative()) {
            updateSensitivityFromCompletedStep();
            System.out.printf(
                "[V21] PHASE: NEAR_TARGET_SINGLE_PARAM → SETTLE_CHECK  rav=%.3e  peak=%.3e%n",
                runningAvgV, peakVAbsThisBurst);
            phase = Phase.SETTLE_CHECK;
        }

        return null;
    }

    // ── V21: try to enter near-target single-param mode ──────────────────────
    // Called at each switch point. Returns true and enters NEAR_TARGET_SINGLE_PARAM
    // if |err| < NEAR_TARGET_THRESHOLD; returns false if |err| is too large.
    // When returning true, the caller must NOT call switchActiveParam().

    private boolean tryEnterNearTargetMode(double err) {
        if (Math.abs(err) >= NEAR_TARGET_THRESHOLD) return false;

        // Compute headroom for each parameter.
        // headroom = min(v - lo, hi - v) / ((hi - lo) / 2)
        // 1.0 when v is exactly midrange; 0.0 at either limit.
        double halfSpanR = (FRAC_R_MAX - FRAC_R_MIN) / 2.0;      // 0.7
        double headroomR = Math.min(fracR - FRAC_R_MIN, FRAC_R_MAX - fracR) / halfSpanR;

        double halfSpanM = (FRAC_MT_MAX - FRAC_MT_MIN) / 2.0;    // 0.245
        double headroomM = Math.min(fracMoveTorq - FRAC_MT_MIN, FRAC_MT_MAX - fracMoveTorq) / halfSpanM;

        ActiveParam chosen = (headroomR >= headroomM) ? ActiveParam.FRAC_R : ActiveParam.FRAC_MT;
        nearTargetParam = chosen;
        activeParam     = chosen;
        stepsThisTurn   = 0;
        peakVAbsThisBurst = 0;
        vMagHead = 0; vMagCount = 0;
        phase = Phase.NEAR_TARGET_SINGLE_PARAM;

        System.out.printf(
            "[V21:SINGLE_PARAM] entering near-target single-param mode  active=%s"
            + "  headroom_R=%.3f  headroom_MT=%.3f  chose=%s  |err|=%.6f%n",
            activeParam, headroomR, headroomM, chosen, Math.abs(err));

        return true;
    }

    // ── chainDriftingWrongWay — unchanged from v17.1–v20 ─────────────────────

    private static boolean chainDriftingWrongWay(double err, double v) {
        if (Double.isNaN(v)) return false;
        return (err > 0 && v > 0) || (err < 0 && v < 0);
    }

    // ── chainHasSettledRelative — v18 peak-relative settling ─────────────────

    private boolean chainHasSettledRelative() {
        double relThresh = Math.max(PEAK_REL_THRESHOLD * peakVAbsThisBurst, ABSOLUTE_V_FLOOR);
        return runningAvgV < relThresh;
    }

    // ── stepActiveParam — unchanged step sizing from v18–v20 ─────────────────
    // V21 changes: updateSensFromPendingSnapshot now applies Fix-1 bounds before
    // blending, so no structural change needed here.

    private boolean stepActiveParam(double err) {
        // V21 (via V20 Fix 1): update sensitivity from pending previous-step snapshot
        // before committing this step.
        updateSensFromPendingSnapshot();

        boolean stiffen = (err > 0);

        if (activeParam == ActiveParam.FRAC_R) {
            double currentParam = fracR;
            double sens         = sensR_valid ? sensR : SENS_R_INIT;
            double idealMag     = Math.abs(err) / sens;
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
                vMagHead = 0; vMagCount = 0;
                return true;
            } else {
                System.out.printf(
                    "[V21] stepActiveParam: FRAC_R pinned"
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
                vMagHead = 0; vMagCount = 0;
                return true;
            } else {
                System.out.printf(
                    "[V21] stepActiveParam: FRAC_MT pinned"
                    + " (delta=%.4e ≤ %.3f × %.4f  idealMag=%.4f  clampedMag=%.4f  sens=%.4f) → switching%n",
                    actualDelta, MIN_MEANINGFUL_DELTA, Math.abs(currentParam),
                    idealMag, mag, sens);
                switchActiveParam();
                return false;
            }
        }
    }

    // ── updateSensFromPendingSnapshot — V21 Fix 1 ────────────────────────────
    // Called at the top of every stepActiveParam() call. Applies three bounding
    // fixes to prevent sensitivity divergence from small-deltaParam steps:
    // (a) skip if deltaParam < DELTA_PARAM_FLOOR_FRAC × |param|;
    // (b) clamp raw measurement to [SENS_MIN, SENS_MAX];
    // (c) EMA blend with BLEND_ALPHA=0.3 instead of outright replacement;
    // (d) clamp final blended sens to [SENS_MIN, SENS_MAX].

    private void updateSensFromPendingSnapshot() {
        if (stepParam == null) return;

        double sNow       = smoothed;
        double deltaS     = Math.abs(sNow - sBeforeStep);
        double paramNow   = (stepParam == ActiveParam.FRAC_R) ? fracR : fracMoveTorq;
        double deltaParam = Math.abs(paramNow - paramBeforeStep);
        double sensMin    = (stepParam == ActiveParam.FRAC_R) ? SENS_MIN_R : SENS_MIN_M;
        double sensMax    = (stepParam == ActiveParam.FRAC_R) ? SENS_MAX_R : SENS_MAX_M;

        // V21 Fix 1a: skip if deltaParam is too small relative to param value
        double paramFloor = DELTA_PARAM_FLOOR_FRAC * Math.abs(paramBeforeStep);
        if (deltaParam < paramFloor) {
            System.out.printf(
                "[V21:SENS_SKIP] active=%s  deltaParam=%.4e  floor=%.4e  reason=too_small%n",
                stepParam, deltaParam, paramFloor);
            stepParam = null;
            return;
        }

        // Skip if deflection change is below noise floor
        if (deltaS < CONV_TOL_UM) {
            stepParam = null;
            return;
        }

        double rawSens = deltaS / deltaParam;

        // V21 Fix 1b: clamp raw measurement before blending
        double clampedSens = clamp(rawSens, sensMin, sensMax);
        if (clampedSens != rawSens) {
            System.out.printf(
                "[V21:SENS_CLAMP] active=%s  raw=%.6f  clamped=%.6f%n",
                stepParam, rawSens, clampedSens);
        }

        if (stepParam == ActiveParam.FRAC_R) {
            double oldSens = sensR;
            // V21 Fix 1c: EMA blend (30% toward measurement, 70% retain old)
            double blended = sensR_valid
                ? ((1.0 - BLEND_ALPHA) * sensR + BLEND_ALPHA * clampedSens)
                : clampedSens;
            // V21 Fix 1d: clamp final blended value
            sensR = clamp(blended, SENS_MIN_R, SENS_MAX_R);
            sensR_valid = true;
            System.out.printf(
                "[V21:SENS] active=FRAC_R  deltaS=%.3e  deltaParam=%.4f  raw=%.6f  sens: %.6f→%.6f%n",
                deltaS, deltaParam, rawSens, oldSens, sensR);
        } else {
            double oldSens = sensM;
            double blended = sensM_valid
                ? ((1.0 - BLEND_ALPHA) * sensM + BLEND_ALPHA * clampedSens)
                : clampedSens;
            sensM = clamp(blended, SENS_MIN_M, SENS_MAX_M);
            sensM_valid = true;
            System.out.printf(
                "[V21:SENS] active=FRAC_MT  deltaS=%.3e  deltaParam=%.4f  raw=%.6f  sens: %.6f→%.6f%n",
                deltaS, deltaParam, rawSens, oldSens, sensM);
        }

        stepParam = null;
    }

    // ── updateSensitivityFromCompletedStep — backstop for slow-path settling ──
    // Same Fix-1 bounds as updateSensFromPendingSnapshot.
    // On fast-path runs, stepParam is typically null here (cleared by the most
    // recent updateSensFromPendingSnapshot), so this is a no-op. Retained for
    // slow-path runs where full settling precedes the next step.

    private void updateSensitivityFromCompletedStep() {
        if (stepParam == null) return;

        double sAfter     = smoothed;
        double deltaS     = Math.abs(sAfter - sBeforeStep);
        double paramNow   = (stepParam == ActiveParam.FRAC_R) ? fracR : fracMoveTorq;
        double deltaParam = Math.abs(paramNow - paramBeforeStep);
        double sensMin    = (stepParam == ActiveParam.FRAC_R) ? SENS_MIN_R : SENS_MIN_M;
        double sensMax    = (stepParam == ActiveParam.FRAC_R) ? SENS_MAX_R : SENS_MAX_M;

        // Fix 1a: skip if deltaParam too small
        double paramFloor = DELTA_PARAM_FLOOR_FRAC * Math.abs(paramBeforeStep);
        if (deltaParam < paramFloor) {
            System.out.printf(
                "[V21:SENS_SKIP] active=%s  deltaParam=%.4e  floor=%.4e  reason=too_small (settle)%n",
                stepParam, deltaParam, paramFloor);
            stepParam = null;
            return;
        }
        if (deltaS < CONV_TOL_UM) { stepParam = null; return; }

        double rawSens = deltaS / deltaParam;

        // Fix 1b: clamp raw measurement
        double clampedSens = clamp(rawSens, sensMin, sensMax);
        if (clampedSens != rawSens) {
            System.out.printf(
                "[V21:SENS_CLAMP] active=%s  raw=%.6f  clamped=%.6f  (settle)%n",
                stepParam, rawSens, clampedSens);
        }

        if (stepParam == ActiveParam.FRAC_R) {
            double oldSens = sensR;
            double blended = sensR_valid
                ? ((1.0 - BLEND_ALPHA) * sensR + BLEND_ALPHA * clampedSens)
                : clampedSens;
            sensR = clamp(blended, SENS_MIN_R, SENS_MAX_R);
            sensR_valid = true;
            System.out.printf(
                "[V21] sens update (settle): FRAC_R  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensR, deltaS, deltaParam);
        } else {
            double oldSens = sensM;
            double blended = sensM_valid
                ? ((1.0 - BLEND_ALPHA) * sensM + BLEND_ALPHA * clampedSens)
                : clampedSens;
            sensM = clamp(blended, SENS_MIN_M, SENS_MAX_M);
            sensM_valid = true;
            System.out.printf(
                "[V21] sens update (settle): FRAC_MT  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensM, deltaS, deltaParam);
        }
        stepParam = null;
    }

    // ── switchActiveParam — v20 logic ────────────────────────────────────────

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
        vMagHead = 0; vMagCount = 0;
        System.out.printf("[V21] switch active: %s → %s  fr=%.4f  fmt=%.4f%n",
            from, activeParam, fracR, fracMoveTorq);
    }

    // ── velocity-magnitude ring buffer (fast-path peak_passed check) ──────────

    private void trackVMag(double v) {
        double absV = Math.abs(v);
        vMagBuf[vMagHead] = absV;
        vMagHead = (vMagHead + 1) % (V_PEAK_WIN + 1);
        if (vMagCount < V_PEAK_WIN + 1) vMagCount++;
    }

    private boolean isPeakPassed() {
        int size = V_PEAK_WIN + 1;
        if (vMagCount < size) return false;
        for (int k = 0; k < V_PEAK_WIN; k++) {
            int prevIdx = (vMagHead + k)     % size;
            int currIdx = (vMagHead + k + 1) % size;
            if (vMagBuf[currIdx] > vMagBuf[prevIdx] * (1.0 + V_PEAK_TOL)) return false;
        }
        return true;
    }

    // ── Signal pipeline helpers (copied verbatim from v17.1–v20) ─────────────

    private void pushVA(double s) {
        vaBuf[vaHead] = s;
        vaHead = (vaHead + 1) % SLOPE_WIN;
        if (vaCount < SLOPE_WIN) vaCount++;
    }

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
        double sum = 0;
        for (int k = 0; k < ravCount; k++) sum += ravBuf[k];
        runningAvgV = ravCount > 0 ? sum / ravCount : 0.0;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
