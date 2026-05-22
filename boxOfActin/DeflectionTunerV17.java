package boxOfActin;

/**
 * DeflectionTunerV17 — single-parameter overshoot controller (v17.1).
 *
 * One parameter is active at a time (fracR or fracMoveTorq). Every FRAMES_BETWEEN_STEPS
 * output frames: if the chain is drifting in the wrong direction (err and v same sign),
 * step activeParam halfway toward the hardware limit that would correct err.
 *
 * v17.1 strict alternation: each R_ADJUST/M_ADJUST turn gets up to STEPS_PER_TURN
 * meaningful drift-correcting steps, then switches unconditionally. A "meaningful step"
 * moves the parameter by more than MIN_STEP_DELTA_FRAC × |current_value|; a pinned step
 * (parameter at hardware limit) does NOT consume budget and triggers an immediate switch.
 *
 * When the running average of |v| falls below the noise floor, enter SETTLE_CHECK to
 * decide convergence, parameter switch, fracMove retry, or continue with alternation.
 *
 * Signal pipeline (subset of v15/v16; no acceleration, no τ_est, no d_∞):
 *   smoothed    — EMA of observed deflection (same EMA_ALPHA as v15/v16)
 *   v           — linear regression slope over last SLOPE_WIN frames of (frame_time, smoothed)
 *   runningAvgV — rolling mean of |v| over RUNNING_AVG_WINDOW frames
 *
 * Hysteresis on SETTLE_CHECK entry: enter only when
 *   runningAvgV < SETTLE_ENTRY_FRAC * V_STEADY_THRESHOLD   (= 0.5 × V_NOISE).
 * After switchActiveParam(), phase returns to R_ADJUST/M_ADJUST and SETTLE_CHECK
 * re-entry requires the same strict threshold.
 *
 * Guard: SETTLE_CHECK entry is further blocked until vaCount >= SLOPE_WIN (12 frames).
 * Before the regression buffer fills, v is NaN → treated as 0.0 in runningAvgV,
 * which would give spuriously low runningAvgV and premature SETTLE_CHECK entry.
 *
 * ALL algorithm constants marked PLACEHOLDER — calibrate after first trace.
 */
class DeflectionTunerV17 {

    enum Phase { R_ADJUST, M_ADJUST, SETTLE_CHECK, RETRY_LOWER_FRACMOVE, CONVERGED, FAILED }

    enum ActiveParam { FRAC_R, FRAC_MT }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── hardware limits (same as v14/v15/v16) ─────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── EMA constant (same as v15/v16) ────────────────────────────────────────
    static final double EMA_ALPHA         = 0.05;
    static final double SECONDS_PER_FRAME = 0.01;  // toFileInterval=100 × deltaT=1e-4

    // ── signal pipeline constant ──────────────────────────────────────────────
    // Linear regression window: max(5, W_A) where W_A=12 matches v15/v16.
    static final int    W_A       = 12;             // PLACEHOLDER: regression base window
    static final int    SLOPE_WIN = Math.max(5, W_A); // = 12; actual window used for v

    // ── v17 algorithm constants ───────────────────────────────────────────────
    // FRAMES_BETWEEN_STEPS: gap between consecutive parameter steps.
    // Too small → v is stale after a step; too large → slow wall-clock convergence.
    static final int    FRAMES_BETWEEN_STEPS = 5;    // PLACEHOLDER
    // HALVE_FRACTION: each step moves activeParam this fraction of the remaining
    // distance to its hardware limit on the correcting side. 0.5 = midpoint.
    static final double HALVE_FRACTION       = 0.5;  // PLACEHOLDER
    // RUNNING_AVG_WINDOW: frames over which |v| is averaged for steady-state detection.
    // Too small → false steady-state on transient pauses; too large → slow to detect settling.
    static final int    RUNNING_AVG_WINDOW   = 10;   // PLACEHOLDER
    // V_NOISE: velocity noise floor calibrated from 32-monomer near-equilibrium tail
    // (v15, 2026-05-20): v std=2.55e-5 µm/s → 3σ=7.65e-5; conservative round-up = 8e-5.
    static final double V_NOISE              = 8e-5; // µm/s
    // V_STEADY_THRESHOLD: running-avg |v| below this → chain considered at steady state.
    static final double V_STEADY_THRESHOLD   = V_NOISE; // PLACEHOLDER (= V_NOISE)
    // SETTLE_ENTRY_FRAC: hysteresis multiplier for entering SETTLE_CHECK.
    // Enter only when runningAvgV < SETTLE_ENTRY_FRAC * V_STEADY_THRESHOLD.
    static final double SETTLE_ENTRY_FRAC    = 0.5;  // PLACEHOLDER
    // CONV_TOL_UM: convergence tolerance (µm). Empirical noise floor of smoothed deflection.
    static final double CONV_TOL_UM          = 1e-5; // PLACEHOLDER: 0.01 nm = 10 pm
    // STEPS_PER_TURN: budget of meaningful drift-correcting steps per R_ADJUST/M_ADJUST turn.
    // After this many meaningful steps, the controller switches to the other parameter
    // unconditionally. A pinned step (parameter at hardware limit) does NOT consume budget
    // and triggers an immediate switch instead.
    static final int    STEPS_PER_TURN       = 2;    // PLACEHOLDER: 2 or 3 alternation budget
    // MIN_STEP_DELTA_FRAC: a step counts as "meaningful" only if it moves the parameter
    // by more than MIN_STEP_DELTA_FRAC × |current_value| (i.e. more than 0.1% change).
    static final double MIN_STEP_DELTA_FRAC  = 0.001; // PLACEHOLDER
    // FRACMOVE_RETRY_STEP: amount to decrease fracMove on each retry.
    static final double FRACMOVE_RETRY_STEP  = 0.05;
    // FRACMOVE_FLOOR: minimum fracMove before declaring FAILED.
    static final double FRACMOVE_FLOOR       = 0.1;

    // ── run-time scalars ───────────────────────────────────────────────────────
    private double fracMove, fracR, fracMoveTorq;
    private double target;   // analyticDefl (µm)
    private double dtFrame;  // output-frame period (s)

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

    // ── phase state ───────────────────────────────────────────────────────────
    private Phase       phase;
    private ActiveParam activeParam;
    private int         framesSinceLastStep;
    private int         stepCount;
    private int         stepsThisTurn;  // meaningful steps taken in current R_ADJUST/M_ADJUST turn

    // ── accessors ─────────────────────────────────────────────────────────────
    Phase   getPhase()        { return phase; }
    boolean isDone()          { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()     { return fracMove; }
    double  getFracR()        { return fracR; }
    double  getFracMoveTorq() { return fracMoveTorq; }
    double  getSmoothed()     { return smoothed; }

    String resultSummary() {
        return String.format("[V17] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  steps=%d",
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

        phase               = Phase.R_ADJUST;
        activeParam         = ActiveParam.FRAC_R;
        framesSinceLastStep = 0;
        stepCount           = 0;
        stepsThisTurn       = 0;

        System.out.printf(
            "[V17] armed: fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  target=%.6fµm%n"
            + "[V17]   FRAMES_BETWEEN_STEPS=%d  HALVE_FRACTION=%.2f  RUNNING_AVG_WINDOW=%d%n"
            + "[V17]   V_NOISE=%.3e  V_STEADY_THRESHOLD=%.3e  SETTLE_ENTRY_FRAC=%.2f%n"
            + "[V17]   CONV_TOL_UM=%.2e  STEPS_PER_TURN=%d  MIN_STEP_DELTA_FRAC=%.3f  SLOPE_WIN=%d%n",
            fracMove, fracR, fracMoveTorq, target,
            FRAMES_BETWEEN_STEPS, HALVE_FRACTION, RUNNING_AVG_WINDOW,
            V_NOISE, V_STEADY_THRESHOLD, SETTLE_ENTRY_FRAC,
            CONV_TOL_UM, STEPS_PER_TURN, MIN_STEP_DELTA_FRAC, SLOPE_WIN);
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

        // Running average of |v|. NaN v → treated as 0.0 (conservative, but SETTLE_CHECK
        // entry is independently guarded by vaCount >= SLOPE_WIN in handleAdjust).
        updateRunningAvgV(v);

        framesSinceLastStep++;
        double err = smoothed - target;

        // ── Per-frame WATCH log ───────────────────────────────────────────────
        System.out.printf(
            "[V17:WATCH] wf=%d  s=%.6f  v=%s  err=%.6f  rav=%.3e  phase=%s  active=%s%n",
            framesSinceLastStep, smoothed,
            Double.isNaN(v) ? "    ---   " : String.format("%.3e", v),
            err, runningAvgV, phase, activeParam);

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
                        "[V17] FAILED: fracMove %.4f - %.4f = %.4f < floor %.4f — giving up%n",
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
                System.out.printf(
                    "[V17] PHASE: RETRY_LOWER_FRACMOVE → R_ADJUST"
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
                "[V17:STEP#%d] drift-correcting  active=%s  budget=%d/%d"
                + "  fr: %.4f→%.4f  fmt: %.4f→%.4f  err=%.6f  v=%.3e%n",
                stepCount, activeParam, stepsThisTurn, STEPS_PER_TURN,
                oldFr, fracR, oldFmt, fracMoveTorq, err, v);
            if (stepsThisTurn >= STEPS_PER_TURN) {
                switchActiveParam();
            }
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        // Chain is responding correctly. Check if it has settled.
        // Guard: don't enter SETTLE_CHECK until regression buffer is full (vaCount >= SLOPE_WIN),
        // to avoid premature entry when NaN velocity is treated as 0.0 in runningAvgV.
        if (vaCount >= SLOPE_WIN && runningAvgV < SETTLE_ENTRY_FRAC * V_STEADY_THRESHOLD) {
            System.out.printf(
                "[V17] PHASE: %s → SETTLE_CHECK  rav=%.3e < %.3e%n",
                phase, runningAvgV, SETTLE_ENTRY_FRAC * V_STEADY_THRESHOLD);
            phase = Phase.SETTLE_CHECK;
        }
        return null;
    }

    // ── SETTLE_CHECK handler ──────────────────────────────────────────────────

    // Called every frame while phase == SETTLE_CHECK. Always transitions out of
    // SETTLE_CHECK in one call (to CONVERGED, FAILED, RETRY_LOWER_FRACMOVE, or
    // back to R_ADJUST/M_ADJUST via switchActiveParam). Always returns null.
    private ParamTriple handleSettleCheck(double err) {
        // (1) Convergence: chain settled within tolerance
        if (Math.abs(err) < CONV_TOL_UM) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V17] CONVERGED  s=%.6f  target=%.6f  |err|=%.3e < %.3e  steps=%d%n",
                smoothed, target, Math.abs(err), CONV_TOL_UM, stepCount);
            return null;
        }

        // (2) At soft limits and chain too stiff — lower fracMove and restart
        boolean atSoftLimits = (fracR >= FRAC_R_MAX - 1e-9)
                            && (fracMoveTorq <= FRAC_MT_MIN + 1e-9);
        if (err < 0 && atSoftLimits) {
            System.out.printf(
                "[V17] PHASE: SETTLE_CHECK → RETRY_LOWER_FRACMOVE  at soft limits  err=%.6f%n",
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
                "[V17] FAILED: at stiff limits, still too soft  err=%.6f%n", err);
            return null;
        }

        // (4) Continue with alternation — switch to the other parameter with a fresh
        // STEPS_PER_TURN budget. switchActiveParam resets stepsThisTurn to 0.
        System.out.printf(
            "[V17] PHASE: SETTLE_CHECK → continue alternation  err=%.6f%n", err);
        switchActiveParam();
        return null;
    }

    // ── chainDriftingWrongWay(err, v) ─────────────────────────────────────────

    // Returns true when deflection is on the wrong side of target AND getting worse.
    // v ≈ 0 (chain at instantaneous equilibrium) does NOT count as drifting.
    // v in the correcting direction also does NOT count.
    private static boolean chainDriftingWrongWay(double err, double v) {
        if (Double.isNaN(v)) return false;
        return (err > 0 && v > 0) || (err < 0 && v < 0);
    }

    // ── stepActiveParam(err) ──────────────────────────────────────────────────

    // Moves activeParam halfway toward the hardware limit that corrects err.
    // Returns true if the step was meaningful (|delta| > MIN_STEP_DELTA_FRAC × |old|),
    // in which case the parameter is updated and stepsThisTurn is incremented.
    // Returns false if the parameter is effectively pinned at its hardware limit;
    // in that case the parameter is NOT updated and switchActiveParam() is called
    // immediately (which resets stepsThisTurn to 0 and changes phase).
    private boolean stepActiveParam(double err) {
        if (activeParam == ActiveParam.FRAC_R) {
            double oldFracR = fracR;
            double newFracR;
            if (err > 0) {
                // chain too soft → stiffen fracR (decrease toward FRAC_R_MIN)
                newFracR = fracR + HALVE_FRACTION * (FRAC_R_MIN - fracR);
            } else {
                // chain too stiff → soften fracR (increase toward FRAC_R_MAX)
                newFracR = fracR + HALVE_FRACTION * (FRAC_R_MAX - fracR);
            }
            newFracR = clamp(newFracR, FRAC_R_MIN, FRAC_R_MAX);
            if (Math.abs(newFracR - oldFracR) > MIN_STEP_DELTA_FRAC * Math.abs(oldFracR)) {
                fracR = newFracR;
                stepCount++;
                stepsThisTurn++;
                return true;
            } else {
                System.out.printf(
                    "[V17] stepActiveParam: FRAC_R pinned (delta=%.4e ≤ %.3f × %.4f) → switching%n",
                    Math.abs(newFracR - oldFracR), MIN_STEP_DELTA_FRAC, Math.abs(oldFracR));
                switchActiveParam();
                return false;
            }
        } else {  // FRAC_MT
            double oldFmt = fracMoveTorq;
            double newFmt;
            if (err > 0) {
                // chain too soft → stiffen fmt (increase toward FRAC_MT_MAX)
                newFmt = fracMoveTorq + HALVE_FRACTION * (FRAC_MT_MAX - fracMoveTorq);
            } else {
                // chain too stiff → soften fmt (decrease toward FRAC_MT_MIN)
                newFmt = fracMoveTorq + HALVE_FRACTION * (FRAC_MT_MIN - fracMoveTorq);
            }
            newFmt = clamp(newFmt, FRAC_MT_MIN, FRAC_MT_MAX);
            if (Math.abs(newFmt - oldFmt) > MIN_STEP_DELTA_FRAC * Math.abs(oldFmt)) {
                fracMoveTorq = newFmt;
                stepCount++;
                stepsThisTurn++;
                return true;
            } else {
                System.out.printf(
                    "[V17] stepActiveParam: FRAC_MT pinned (delta=%.4e ≤ %.3f × %.4f) → switching%n",
                    Math.abs(newFmt - oldFmt), MIN_STEP_DELTA_FRAC, Math.abs(oldFmt));
                switchActiveParam();
                return false;
            }
        }
    }

    // ── switchActiveParam() ───────────────────────────────────────────────────

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
        System.out.printf("[V17] switch active: %s → %s  fr=%.4f  fmt=%.4f%n",
            from, activeParam, fracR, fracMoveTorq);
    }

    // ── Signal pipeline helpers ───────────────────────────────────────────────

    private void pushVA(double s) {
        vaBuf[vaHead] = s;
        vaHead = (vaHead + 1) % SLOPE_WIN;
        if (vaCount < SLOPE_WIN) vaCount++;
    }

    /**
     * Linear regression slope on the last SLOPE_WIN frames (evenly spaced at dtFrame).
     * Uses centered coordinates so the cross-term sum vanishes.
     * After pushVA(), vaHead points to the oldest slot; iterating k=0..SLOPE_WIN-1
     * from vaHead gives entries from oldest (k=0) to newest (k=SLOPE_WIN-1), with
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
        // (ravHead hasn't wrapped yet). When full: ravBuf[0..RUNNING_AVG_WINDOW-1] are
        // all valid. In both cases ravBuf[0..ravCount-1] gives exactly the valid entries.
        double sum = 0;
        for (int k = 0; k < ravCount; k++) sum += ravBuf[k];
        runningAvgV = ravCount > 0 ? sum / ravCount : 0.0;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
