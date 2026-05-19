package boxOfActin;

/**
 * Automated deflection tuning controller for the F1 benchmark chain.
 * Third implementation: online predictive controller with sensitivity estimation.
 *
 * Pure logic: no Env, no FilSegment, no I/O. Feed one output-frame deflection
 * sample at a time via feed(). Returns a non-null ParamTriple at each parameter
 * step. After isDone(), getPhase() is CONVERGED or FAILED.
 *
 * Direction convention (confirmed empirically):
 *   Larger fracR        → softer (more deflection)
 *   Larger fracMoveTorq → stiffer (less deflection)
 *
 * COARSE joint coordinate s:
 *   s=0 → (fracR=FRAC_R_MIN, fracMoveTorq=FRAC_MT_MAX)  stiffest
 *   s=1 → (fracR=FRAC_R_MAX, fracMoveTorq=FRAC_MT_MIN)  softest
 *   fracR(s)        = FRAC_R_MIN  + s * FRAC_R_RANGE
 *   fracMoveTorq(s) = FRAC_MT_MAX - s * FRAC_MT_RANGE
 *   Expected sensitivity dDeflection/ds > 0 (larger s → more deflection).
 *
 * FINE: fracR frozen; only fracMoveTorq moves.
 *   Expected sensitivity dDeflection/dFracMoveTorq < 0.
 */
class DeflectionTuner {

    enum Phase { COARSE, FINE, CONVERGED, FAILED }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── constants ─────────────────────────────────────────────────────────────
    static final double EMA_ALPHA            = 0.05;
    static final int    OBS_WINDOW           = 20;   // frames to observe after each step
    static final double INIT_PROBE_STEP      = 0.05; // first step on joint coord (no sensitivity yet)
    static final double MAX_STEP             = 0.2;  // max |step| on s or fracMoveTorq
    static final double PRED_STEP_TOL        = 0.001;// predicted step below this → convergence eligible
    static final double CONV_TOL_UM          = 5e-6; // ±0.005 nm in µm
    static final int    CONV_FRAMES          = 50;   // consecutive in-tolerance frames → CONVERGED
    static final double FRAC_R_RANGE         = 1.4;  // FRAC_R_MAX - FRAC_R_MIN
    static final double FRAC_MT_RANGE        = 0.49; // FRAC_MT_MAX - FRAC_MT_MIN
    // Enter FINE when |raw predicted step on s| * FRAC_R_RANGE < this
    static final double COARSE_TO_FINE_FRACR = 0.05;
    static final double FRAC_MOVE_STEP       = 0.05;

    // ── parameter limits ──────────────────────────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── state ─────────────────────────────────────────────────────────────────
    private Phase  phase;
    private double fracMove, fracR, fracMoveTorq;
    private double expected;
    private double smoothed;

    // Observation window
    private int    obsFrames;             // frames since last step (or start)
    private double smoothedAtWindowOpen;  // smoothed value when current window began

    // Last step applied (on s in COARSE, on fracMoveTorq in FINE)
    private double  lastStep;
    private boolean hasLastStep;

    // Sensitivity estimate (dDeflection/dParam; +ve in COARSE, -ve in FINE)
    private double  sensitivity;
    private boolean hasSensitivity;

    // Brackets on active parameter.
    // COARSE: loBound = largest "too-stiff" s seen; hiBound = smallest "too-soft" s seen.
    // FINE:   loBound = largest "too-soft" fmt seen; hiBound = smallest "too-stiff" fmt seen.
    // In both cases target is in [loBound, hiBound].
    private double  loBound, hiBound;
    private boolean hasLoBound, hasHiBound;

    // COARSE joint coordinate
    private double s;
    private double frozenFracR;

    // Convergence
    private int    convCount;
    private double lastRawPredStep; // |raw predicted step| from most recent evaluation

    // Logging
    private int stepCount;

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Arms the controller. Resets all state. Chain need not be reset —
     * EMA accumulates from zero as the chain deflects naturally.
     */
    void start(double fm, double fr, double fmt, double expectedMicrons) {
        fracMove     = fm;
        fracR        = fr;
        fracMoveTorq = fmt;
        expected     = expectedMicrons;
        phase        = Phase.COARSE;
        smoothed     = 0.0;

        // Initial s derived from fracMoveTorq (keeps fmt continuous across first step)
        s = clamp01((FRAC_MT_MAX - fmt) / FRAC_MT_RANGE);

        frozenFracR  = fr;
        obsFrames    = 0;
        smoothedAtWindowOpen = 0.0;
        lastStep     = 0;   hasLastStep    = false;
        sensitivity  = 0;   hasSensitivity = false;
        loBound      = 0;   hasLoBound     = false;
        hiBound      = 0;   hasHiBound     = false;
        convCount    = 0;
        lastRawPredStep = Double.NaN;
        stepCount    = 0;
    }

    /**
     * Feed one output-frame deflection sample (µm, force must be ON).
     * Returns null most frames. Returns a ParamTriple at each parameter step.
     * After isDone(), always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        smoothed = EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed;
        double error = smoothed - expected;

        obsFrames++;
        if (obsFrames < OBS_WINDOW) {
            checkConvergence(error);
            return null;
        }
        obsFrames = 0;

        // Estimate sensitivity from the window just completed
        if (hasLastStep && Math.abs(lastStep) > 1e-12) {
            double trend   = smoothed - smoothedAtWindowOpen;
            double rawSens = trend / lastStep;
            // Monotonicity: COARSE expects +ve, FINE expects -ve
            boolean signOk = (phase == Phase.COARSE) ? (rawSens > 0) : (rawSens < 0);
            if (signOk) {
                sensitivity    = rawSens;
                hasSensitivity = true;
            } else {
                System.out.printf(
                    "[AUTOTUNE] bad-sign sens=%.4f (phase=%s trend=%+.6f step=%.5f) — %s%n",
                    rawSens, phase, trend, lastStep,
                    hasSensitivity ? "keeping prior" : "using probe");
            }
        }

        // Update brackets, record window-open baseline, take next step
        if (phase == Phase.COARSE) updateBracketsCoarse(error);
        else                       updateBracketsFine(error);

        smoothedAtWindowOpen = smoothed;

        ParamTriple result = (phase == Phase.COARSE) ? stepCoarse(error) : stepFine(error);
        if (!isDone()) checkConvergence(error);
        return result;
    }

    Phase   getPhase()        { return phase; }
    boolean isDone()          { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()     { return fracMove; }
    double  getFracR()        { return fracR; }
    double  getFracMoveTorq() { return fracMoveTorq; }
    double  getSmoothed()     { return smoothed; }

    String resultSummary() {
        return String.format("[AUTOTUNE] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f",
            phase, fracMove, fracR, fracMoveTorq);
    }

    // ── brackets ─────────────────────────────────────────────────────────────

    // COARSE: larger s = softer. Too soft (error>0) → target s is lower → upper bound.
    //                            Too stiff (error<0) → target s is higher → lower bound.
    private void updateBracketsCoarse(double error) {
        if (error > 0 && (!hasHiBound || s < hiBound)) { hiBound = s; hasHiBound = true; }
        if (error < 0 && (!hasLoBound || s > loBound)) { loBound = s; hasLoBound = true; }
    }

    // FINE: larger fmt = stiffer. Too soft (error>0) → need larger fmt → lower bound.
    //                             Too stiff (error<0) → need smaller fmt → upper bound.
    private void updateBracketsFine(double error) {
        if (error > 0 && (!hasLoBound || fracMoveTorq > loBound)) { loBound = fracMoveTorq; hasLoBound = true; }
        if (error < 0 && (!hasHiBound || fracMoveTorq < hiBound)) { hiBound = fracMoveTorq; hasHiBound = true; }
    }

    // ── step logic ────────────────────────────────────────────────────────────

    private ParamTriple stepCoarse(double error) {
        // Stiff-end failure: s at minimum and deflection still below target
        if (s < 1e-6 && error < 0) {
            if (fracMove <= FRAC_MOVE_MIN + 1e-9) {
                phase = Phase.FAILED;
                System.out.println("[AUTOTUNE] FAILED: s=0 (stiff limit) and fracMove at minimum");
                return null;
            }
            double oldFm = fracMove;
            fracMove = Math.max(fracMove - FRAC_MOVE_STEP, FRAC_MOVE_MIN);
            hasSensitivity = false; hasLastStep = false; hasLoBound = false; hasHiBound = false;
            System.out.printf("[AUTOTUNE] stiff-limit rescue: fracMove %.4f → %.4f%n", oldFm, fracMove);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        double rawPred = hasSensitivity
            ? (expected - smoothed) / sensitivity          // +ve sensitivity; sign self-correcting
            : (error < 0 ? INIT_PROBE_STEP : -INIT_PROBE_STEP); // initial probe
        lastRawPredStep = Math.abs(rawPred);

        double stepS = clamp(rawPred, -MAX_STEP, MAX_STEP);
        double nextS = clampWithBrackets(s + stepS, true);
        double actualStep = nextS - s;
        if (Math.abs(actualStep) < 1e-12) {
            System.out.printf("[AUTOTUNE] COARSE zero-step at s=%.4f (brackets tight?)%n", s);
        }

        boolean enterFine = lastRawPredStep * FRAC_R_RANGE < COARSE_TO_FINE_FRACR;

        stepCount++;
        s            = nextS;
        fracR        = clamp(FRAC_R_MIN + s * FRAC_R_RANGE, FRAC_R_MIN, FRAC_R_MAX);
        fracMoveTorq = clamp(FRAC_MT_MAX - s * FRAC_MT_RANGE, FRAC_MT_MIN, FRAC_MT_MAX);
        lastStep     = actualStep;
        hasLastStep  = true;

        System.out.printf(
            "[AUTOTUNE:STEP #%d] COARSE  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
            + "  s=%.4f(fr=%.3f,fmt=%.3f)  sens=%s  pred=%.4f  act=%.4f%n",
            stepCount, smoothed, expected, error, s, fracR, fracMoveTorq,
            hasSensitivity ? String.format("%.4f", sensitivity) : "---",
            rawPred, actualStep);

        if (enterFine) {
            frozenFracR    = fracR;
            phase          = Phase.FINE;
            hasSensitivity = false; hasLastStep = false; hasLoBound = false; hasHiBound = false;
            convCount      = 0;
            System.out.printf("[AUTOTUNE] COARSE→FINE: fracR=%.4f frozen  fracMoveTorq=%.4f%n",
                frozenFracR, fracMoveTorq);
        }

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    private ParamTriple stepFine(double error) {
        double rawPred = hasSensitivity
            ? (expected - smoothed) / sensitivity          // -ve sensitivity; sign self-correcting
            : (error > 0 ? INIT_PROBE_STEP : -INIT_PROBE_STEP); // initial fine probe
        lastRawPredStep = Math.abs(rawPred);

        double stepFmt = clamp(rawPred, -MAX_STEP, MAX_STEP);
        double nextFmt = clampWithBrackets(fracMoveTorq + stepFmt, false);
        nextFmt = clamp(nextFmt, FRAC_MT_MIN, FRAC_MT_MAX);
        double actualStep = nextFmt - fracMoveTorq;
        if (Math.abs(actualStep) < 1e-12) {
            System.out.printf("[AUTOTUNE] FINE zero-step at fmt=%.4f (brackets tight?)%n", fracMoveTorq);
        }

        stepCount++;
        fracMoveTorq = nextFmt;
        fracR        = frozenFracR;
        lastStep     = actualStep;
        hasLastStep  = true;

        System.out.printf(
            "[AUTOTUNE:STEP #%d] FINE  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
            + "  fracMoveTorq=%.4f  sens=%s  pred=%.4f  act=%.4f%n",
            stepCount, smoothed, expected, error, fracMoveTorq,
            hasSensitivity ? String.format("%.4f", sensitivity) : "---",
            rawPred, actualStep);

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // Clamp candidate parameter value to bracket [loBound, hiBound].
    // forS=true: COARSE (brackets are on s). forS=false: FINE (brackets are on fracMoveTorq).
    private double clampWithBrackets(double candidate, boolean forS) {
        if (!hasLoBound && !hasHiBound) return candidate;
        double lo = hasLoBound ? loBound : -Double.MAX_VALUE;
        double hi = hasHiBound ? hiBound :  Double.MAX_VALUE;
        if (lo > hi) return (lo + hi) / 2.0; // degenerate bracket — take midpoint
        return clamp(candidate, lo, hi);
    }

    // ── convergence ───────────────────────────────────────────────────────────

    private void checkConvergence(double error) {
        if (Double.isNaN(lastRawPredStep) || lastRawPredStep >= PRED_STEP_TOL) {
            convCount = 0;
            return;
        }
        if (Math.abs(error) <= CONV_TOL_UM) {
            if (++convCount >= CONV_FRAMES) {
                phase = Phase.CONVERGED;
                System.out.printf("[AUTOTUNE] CONVERGED: smoothed=%.6fµm  expected=%.6fµm  steps=%d%n",
                    smoothed, expected, stepCount);
            }
        } else {
            convCount = 0;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
