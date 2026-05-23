package boxOfActin;

/**
 * DeflectionTunerV20 — sensitivity tracking and velocity-trend gating for fast-path.
 *
 * Fixes two regressions introduced by V19's fast path:
 *
 * FIX 1 — Sensitivity tracking across fast-path steps.
 *   V19's sensitivity update (updateSensitivityFromCompletedStep) was only reachable
 *   via the near-target SETTLE_CHECK entry; fast-path steps bypassed it entirely.
 *   V20 adds updateSensFromPendingSnapshot(), called at the start of EVERY
 *   stepActiveParam() call (fast or slow). It consumes the pending snapshot
 *   (sBeforeStep / paramBeforeStep / stepParam) from the previous step and updates
 *   sensitivity before committing the next step. The SETTLE_CHECK path retains
 *   updateSensitivityFromCompletedStep() as a backstop: stepParam is typically null
 *   by then (cleared by the most recent stepActiveParam), so it becomes a no-op on
 *   fast-path runs. On slow-path runs (where SETTLE_CHECK fires before the next step)
 *   it still provides the full-settling accuracy from V18/V19.
 *
 * FIX 2 — Velocity-trend gating for fast-path triggering.
 *   V19 fired at MIN_FRAMES_FAR=10 using whatever v happened to be at that moment,
 *   often during the ramp-up phase before |v| peaks. V20 waits for |v| to pass its
 *   peak: requires V_PEAK_WIN=3 consecutive non-NaN frames where |v[i]| <=
 *   |v[i-1]| * (1 + V_PEAK_TOL=0.05). Falls back to a timeout at
 *   FAR_TIMEOUT_FRAMES=30 if the peak condition never fires (logs reason=timeout).
 *   Drops V19's sign_stable and v_small reasons.
 *
 * Everything else (signal pipeline, PHASE/ActiveParam enums, handleSettleCheck,
 * alternation budget, step sizing, parameter pinning, switchActiveParam) is unchanged
 * from V19.
 *
 * ALL algorithm constants marked PLACEHOLDER — calibrate after first trace.
 */
class DeflectionTunerV20 {

    enum Phase { R_ADJUST, M_ADJUST, SETTLE_CHECK, RETRY_LOWER_FRACMOVE, CONVERGED, FAILED }

    enum ActiveParam { FRAC_R, FRAC_MT }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── hardware limits (same as v14–v19) ─────────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── EMA constant (same as v15–v19) ────────────────────────────────────────
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

    // ── far-from-target fast path ─────────────────────────────────────────────
    // FAR_FROM_TARGET_THRESHOLD: fast path active when |err| exceeds this value.
    static final double FAR_FROM_TARGET_THRESHOLD = 10.0 * CONV_TOL_UM;  // 1e-4 µm
    // MIN_FRAMES_FAR: minimum framesSinceLastStep before fast path can fire.
    static final int    MIN_FRAMES_FAR        = 10;  // PLACEHOLDER
    // V_PEAK_WIN: |v| must be non-increasing for this many consecutive frames.
    //   isPeakPassed() requires vMagBuf full (V_PEAK_WIN+1 entries) and each
    //   successive |v| value satisfying |v[k]| <= |v[k-1]| * (1 + V_PEAK_TOL).
    static final int    V_PEAK_WIN            = 3;   // PLACEHOLDER
    // V_PEAK_TOL: relative slack for "non-increasing" check (absorbs noise).
    static final double V_PEAK_TOL            = 0.05; // PLACEHOLDER
    // FAR_TIMEOUT_FRAMES: fire unconditionally if peak condition never fires.
    static final int    FAR_TIMEOUT_FRAMES    = 30;  // PLACEHOLDER

    // ── sensitivity EMA smoothing (0 = use most recent measurement directly) ───
    static final double SENS_SMOOTHING = 0.0; // PLACEHOLDER

    // ── fracMove fallback (unchanged from v17.1/v18/v19) ─────────────────────
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
    // stepParam is nulled after updateSensFromPendingSnapshot() or
    // updateSensitivityFromCompletedStep() to prevent double-update.
    // May differ from activeParam if a budget-exhausted switch happened between steps.
    private double      sBeforeStep;
    private double      paramBeforeStep;
    private ActiveParam stepParam;

    // ── peak-relative settling ─────────────────────────────────────────────────
    private double peakVAbsThisBurst;

    // ── step sizing diagnostics (set in stepActiveParam, read in handleAdjust) ──
    private double lastIdealMag, lastClampedMag, lastStepSens;

    // ── v-magnitude ring buffer (V20: fast-path peak_passed check) ────────────
    // Holds the last V_PEAK_WIN+1 consecutive |v| values since the most recent step.
    // isPeakPassed() checks that each of the V_PEAK_WIN most recent consecutive pairs
    // is non-increasing (within V_PEAK_TOL). Reset when any step is taken or when
    // switchActiveParam() fires.
    private final double[] vMagBuf = new double[V_PEAK_WIN + 1];
    private int vMagHead, vMagCount;

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
        return String.format("[V20] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  steps=%d",
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
        framesSinceLastStep = 0;
        stepCount           = 0;
        stepsThisTurn       = 0;

        System.out.printf(
            "[V20] armed: fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  target=%.6fµm%n"
            + "[V20]   FRAMES_BETWEEN_STEPS=%d  MAX_STEP_FRAC=%.2f  MIN_STEP_FRAC=%.3f  RUNNING_AVG_WINDOW=%d%n"
            + "[V20]   SENS_R_INIT=%.4f  SENS_M_INIT=%.4f  PEAK_REL_THRESHOLD=%.2f  ABSOLUTE_V_FLOOR=%.1e%n"
            + "[V20]   CONV_TOL_UM=%.2e  STEPS_PER_TURN=%d  MIN_MEANINGFUL_DELTA=%.3f  SLOPE_WIN=%d%n"
            + "[V20]   FAR_THRESHOLD=%.2e  MIN_FRAMES_FAR=%d  V_PEAK_WIN=%d  V_PEAK_TOL=%.2f  FAR_TIMEOUT_FRAMES=%d%n",
            fracMove, fracR, fracMoveTorq, target,
            FRAMES_BETWEEN_STEPS, MAX_STEP_FRAC, MIN_STEP_FRAC, RUNNING_AVG_WINDOW,
            SENS_R_INIT, SENS_M_INIT, PEAK_REL_THRESHOLD, ABSOLUTE_V_FLOOR,
            CONV_TOL_UM, STEPS_PER_TURN, MIN_MEANINGFUL_DELTA, SLOPE_WIN,
            FAR_FROM_TARGET_THRESHOLD, MIN_FRAMES_FAR, V_PEAK_WIN, V_PEAK_TOL, FAR_TIMEOUT_FRAMES);
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
            trackVMag(v);  // V20: accumulate |v| for fast-path peak_passed check
        }

        framesSinceLastStep++;
        double err = smoothed - target;

        // ── Per-frame WATCH log ───────────────────────────────────────────────
        System.out.printf(
            "[V20:WATCH] wf=%d  s=%.6f  v=%s  err=%.6f  rav=%.3e  peak=%.3e  phase=%s  active=%s%n",
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
                        "[V20] FAILED: fracMove %.4f - %.4f = %.4f < floor %.4f — giving up%n",
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
                vMagHead = 0; vMagCount = 0;
                stepParam = null;
                System.out.printf(
                    "[V20] PHASE: RETRY_LOWER_FRACMOVE → R_ADJUST"
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
    // V20: fast path uses peak_passed/timeout instead of sign_stable/v_small.

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
                "[V20:STEP#%d] drift-correcting  active=%s  budget=%d/%d"
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
            // V20 FAST PATH: skip full settling when far from target.
            // Requires v non-NaN; then fires when |v| has passed its peak
            // (non-increasing for V_PEAK_WIN frames) OR timeout fires.
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
                        "[V20:FAST] step=%d  active=%s  |err|=%.6f  v=%.3e  frames_used=%d  reason=%s%n",
                        stepCount, activeParam, absErr, v, framesSinceLastStep, reason);
                    double oldFr  = fracR;
                    double oldFmt = fracMoveTorq;
                    boolean meaningful = stepActiveParam(err);
                    if (!meaningful) return null;
                    framesSinceLastStep = 0;
                    System.out.printf(
                        "[V20:STEP#%d] fast-path  active=%s  budget=%d/%d"
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
                    "[V20] PHASE: %s → SETTLE_CHECK  rav=%.3e  peak=%.3e%n",
                    phase, runningAvgV, peakVAbsThisBurst);
                phase = Phase.SETTLE_CHECK;
            }
        }
        return null;
    }

    // ── SETTLE_CHECK handler — byte-identical to v18/v19 (unchanged from v17.1) ─
    // Always transitions out in one call.

    private ParamTriple handleSettleCheck(double err) {
        // (1) Convergence: chain settled within tolerance
        if (Math.abs(err) < CONV_TOL_UM) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V20] CONVERGED  s=%.6f  target=%.6f  |err|=%.3e < %.3e  steps=%d%n",
                smoothed, target, Math.abs(err), CONV_TOL_UM, stepCount);
            return null;
        }

        // (2) At soft limits and chain too stiff — lower fracMove and restart
        boolean atSoftLimits = (fracR >= FRAC_R_MAX - 1e-9)
                            && (fracMoveTorq <= FRAC_MT_MIN + 1e-9);
        if (err < 0 && atSoftLimits) {
            System.out.printf(
                "[V20] PHASE: SETTLE_CHECK → RETRY_LOWER_FRACMOVE  at soft limits  err=%.6f%n",
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
                "[V20] FAILED: at stiff limits, still too soft  err=%.6f%n", err);
            return null;
        }

        // (4) Continue alternation — switch to the other parameter
        System.out.printf(
            "[V20] PHASE: SETTLE_CHECK → continue alternation  err=%.6f%n", err);
        switchActiveParam();
        return null;
    }

    // ── chainDriftingWrongWay — unchanged from v17.1/v18/v19 ─────────────────

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

    // ── stepActiveParam — unchanged step sizing from v18/v19; adds two V20 changes:
    //   (a) updateSensFromPendingSnapshot() at top — updates sensitivity from the
    //       previous step's snapshot before committing this step, on every call
    //       regardless of whether the previous step was fast-path or slow-path;
    //   (b) vMagBuf reset instead of vSignBuf reset.

    private boolean stepActiveParam(double err) {
        // V20 FIX 1: update sensitivity from pending previous-step snapshot before
        // committing this step. Clears stepParam so SETTLE_CHECK backstop is a no-op.
        updateSensFromPendingSnapshot();

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
                vMagHead = 0; vMagCount = 0;  // V20: fresh |v| tracking for new burst
                return true;
            } else {
                System.out.printf(
                    "[V20] stepActiveParam: FRAC_R pinned"
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
                vMagHead = 0; vMagCount = 0;  // V20: fresh |v| tracking for new burst
                return true;
            } else {
                System.out.printf(
                    "[V20] stepActiveParam: FRAC_MT pinned"
                    + " (delta=%.4e ≤ %.3f × %.4f  idealMag=%.4f  clampedMag=%.4f  sens=%.4f) → switching%n",
                    actualDelta, MIN_MEANINGFUL_DELTA, Math.abs(currentParam),
                    idealMag, mag, sens);
                switchActiveParam();
                return false;
            }
        }
    }

    // ── updateSensFromPendingSnapshot — V20 FIX 1 ────────────────────────────
    // Called at the top of every stepActiveParam() call. Consumes the pending
    // snapshot (sBeforeStep / paramBeforeStep / stepParam) from the previous step
    // and updates the appropriate sensitivity estimate. Clears stepParam so the
    // SETTLE_CHECK backstop (updateSensitivityFromCompletedStep) is a no-op on
    // fast-path runs where all sensitivity was already updated here.
    //
    // deltaS is measured from sBeforeStep (EMA at step N commit time) to the
    // current smoothed (EMA at step N+1 commit time). With MIN_FRAMES_FAR=10
    // frames between fast steps, deltaS captures the early transient response —
    // an underestimate of the eventual steady-state delta, but enough to refine
    // sensitivity from its initial guess.

    private void updateSensFromPendingSnapshot() {
        if (stepParam == null) return;

        double sNow       = smoothed;
        double deltaS     = Math.abs(sNow - sBeforeStep);
        double paramNow   = (stepParam == ActiveParam.FRAC_R) ? fracR : fracMoveTorq;
        double deltaParam = Math.abs(paramNow - paramBeforeStep);

        // Skip if the parameter barely moved (pinned or floating-point noise).
        if (deltaParam < MIN_MEANINGFUL_DELTA * Math.abs(paramBeforeStep)) {
            stepParam = null;
            return;
        }

        // Skip if deflection change is below noise floor — don't corrupt sens.
        if (deltaS < CONV_TOL_UM) {
            stepParam = null;
            return;
        }

        double newSens = deltaS / deltaParam;

        if (stepParam == ActiveParam.FRAC_R) {
            double oldSens = sensR;
            sensR = (SENS_SMOOTHING > 0 && sensR_valid)
                    ? (SENS_SMOOTHING * sensR + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensR_valid = true;
            System.out.printf(
                "[V20:SENS] active=FRAC_R  deltaS=%.3e  deltaParam=%.4f  sens: %.6f→%.6f%n",
                deltaS, deltaParam, oldSens, sensR);
        } else {
            double oldSens = sensM;
            sensM = (SENS_SMOOTHING > 0 && sensM_valid)
                    ? (SENS_SMOOTHING * sensM + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensM_valid = true;
            System.out.printf(
                "[V20:SENS] active=FRAC_MT  deltaS=%.3e  deltaParam=%.4f  sens: %.6f→%.6f%n",
                deltaS, deltaParam, oldSens, sensM);
        }

        stepParam = null;
    }

    // ── updateSensitivityFromCompletedStep — backstop for slow-path settling ──
    // Called when chainHasSettledRelative() fires (near-target SETTLE_CHECK entry).
    // On fast-path runs, stepParam is typically null here (cleared by the most
    // recent updateSensFromPendingSnapshot call in stepActiveParam), so this becomes
    // a no-op. Retained for slow-path runs where full settling occurs before the
    // next step is taken, providing the most accurate deltaS measurement.

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
                "[V20] sens update (settle): FRAC_R  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensR, deltaS, deltaParam);
        } else {
            double oldSens = sensM;
            sensM = (SENS_SMOOTHING > 0 && sensM_valid)
                    ? (SENS_SMOOTHING * sensM + (1.0 - SENS_SMOOTHING) * newSens)
                    : newSens;
            sensM_valid = true;
            System.out.printf(
                "[V20] sens update (settle): FRAC_MT  %.6f → %.6f  (deltaS=%.3e  deltaParam=%.4f)%n",
                oldSens, sensM, deltaS, deltaParam);
        }
        stepParam = null;
    }

    // ── switchActiveParam — v18/v19 logic + vMag reset ───────────────────────
    // Resetting vMagBuf prevents stale |v| history from a previous burst from
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
        vMagHead = 0; vMagCount = 0;
        System.out.printf("[V20] switch active: %s → %s  fr=%.4f  fmt=%.4f%n",
            from, activeParam, fracR, fracMoveTorq);
    }

    // ── V20-specific: velocity-magnitude ring buffer ───────────────────────────

    // Records |v| for each non-NaN frame since the last step. Buffer size is
    // V_PEAK_WIN+1 to allow V_PEAK_WIN consecutive pair comparisons.
    private void trackVMag(double v) {
        double absV = Math.abs(v);
        vMagBuf[vMagHead] = absV;
        vMagHead = (vMagHead + 1) % (V_PEAK_WIN + 1);
        if (vMagCount < V_PEAK_WIN + 1) vMagCount++;
    }

    // Returns true when the buffer is full and every consecutive pair of |v| values
    // (oldest to newest) satisfies |v[k+1]| <= |v[k]| * (1 + V_PEAK_TOL), indicating
    // that |v| has passed its peak and been non-increasing for V_PEAK_WIN frames.
    // After pushes, vMagHead points to the oldest slot (next to be overwritten).
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

    // ── Signal pipeline helpers (copied verbatim from v17.1/v18/v19) ──────────

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
