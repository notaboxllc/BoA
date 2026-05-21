package boxOfActin;

/**
 * DeflectionTunerV16 — bracket-and-overshoot controller.
 *
 * Replaces v15's phase machine with a single crossing-detection loop:
 *   1. Detect crossings of target by the predicted d_∞ (or smoothed s as fallback).
 *   2. On each crossing, record the previous step's parameters as a bracket endpoint.
 *   3. Step aggressively to the bracket midpoint to force another crossing.
 *   4. Declare CONVERGED when dInf is within CONV_TOL_UM of target and |a| < A_SETTLED.
 *
 * Signal pipeline copied verbatim from v15 (EMA, quadratic regression v/a, τ_est ring
 * buffer, dInf prediction). No shared state with v15 — both files remain independent.
 *
 * Pre-calibration probe (one-time at startup before RUNNING):
 *   PRE_PROBE_FR:  step fracR -= PROBE_STEP_FR; confirm smoothed deflection decreases.
 *   PRE_PROBE_FMT: step fracMoveTorq += PROBE_STEP_FMT; confirm deflection decreases.
 *   FAIL with clear message if either sign check fails.
 *
 * Bracket geometry:
 *   softEndpoint  — params that produced dInf > target ("too soft")
 *   stiffEndpoint — params that produced dInf < target ("too stiff")
 *   Both are null until observed. INTO_BRACKET_FRACTION=0.5 steps to the midpoint,
 *   halving the bracket per crossing. ~6 crossings to shrink a unit bracket to 0.01.
 *
 * Crossing signal: dInf when tauStable==true, smoothed s as fallback when tau_est
 * is unreliable (e.g. first frames after a step).
 *
 * ALL v16-specific constants marked PLACEHOLDER — calibrate against trace data.
 */
class DeflectionTunerV16 {

    enum Phase { PRE_PROBE_FR, PRE_PROBE_FMT, RUNNING, CONVERGED, FAILED }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    private static class BracketEndpoint {
        final double fracR, fracMoveTorq, dInf;
        BracketEndpoint(double fr, double fmt, double d) {
            fracR = fr; fracMoveTorq = fmt; dInf = d;
        }
    }

    // ── hardware limits (same as v14/v15) ──────────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── signal pipeline constants (copied from v15, unchanged) ─────────────────
    static final double EMA_ALPHA        = 0.05;
    static final double SECONDS_PER_FRAME = 0.01;  // toFileInterval=100 × deltaT=1e-4
    static final int    W_A              = 12;      // PLACEHOLDER: regression window
    static final int    W_TAU            = 6;       // PLACEHOLDER: τ_est stability window
    static final double TAU_STABLE_FRAC  = 0.15;   // PLACEHOLDER: max relative std-dev of τ_est

    // ── v16 algorithm constants ────────────────────────────────────────────────
    // Convergence: dInf within CONV_TOL_UM of target AND |a| below A_SETTLED.
    static final double CONV_TOL_UM          = 1e-5;  // PLACEHOLDER: 0.01 nm = 10 pm (empirical noise floor)
    static final double A_SETTLED_FRAC       = 0.5;   // PLACEHOLDER: A_SETTLED = A_SETTLED_FRAC * aNoise
    static final int    W_POST_STEP          = 4;     // PLACEHOLDER: post-step quiet frames
    // W_HOLD_MAX = round(4 * tauTheo / dtFrame); computed in start().           PLACEHOLDER
    // Step magnitude: FIRST_STEP_FRACTION of remaining range (no bracket yet);
    // INTO_BRACKET_FRACTION of bracket width (both endpoints known).
    static final double FIRST_STEP_FRACTION  = 0.5;   // PLACEHOLDER
    static final double INTO_BRACKET_FRACTION = 0.5;  // PLACEHOLDER: 0.5 = bracket midpoint
    static final double MIN_STEP_FRACTION    = 0.01;  // PLACEHOLDER: step floor relative to param

    // ── pre-calibration probe constants ───────────────────────────────────────
    // One stiffer step in each parameter direction; abort if sign is wrong.
    static final double PROBE_STEP_FR  = 0.05;  // fracR decrease; stiffer = lower fracR
    static final double PROBE_STEP_FMT = 0.05;  // fracMoveTorq increase; stiffer = higher fmt
    // Probe settling uses same slope-buffer logic as v14/v15 probe phases.
    static final double SETTLE_TOL_FRAC = 0.01;
    static final double SLOPE_TAU_FRAC  = 2.0;
    static final int    MIN_SLOPE_WIN   = 10;

    // ── run-time scalars ───────────────────────────────────────────────────────
    private double fracMove, fracR, fracMoveTorq;
    private double target;    // analyticDefl (µm)
    private double tauTheo;
    private double dtFrame;
    private double vNoise;    // µm/s  — velocity noise floor
    private double aNoise;    // µm/s² — acceleration noise floor
    private int    wHoldMax;  // PLACEHOLDER: max frames between steps before forceStep

    // ── EMA (copied from v15) ─────────────────────────────────────────────────
    private double  smoothed;
    private boolean smoothedInit;

    // ── quadratic-regression ring buffer (W_A frames, copied from v15) ─────────
    private final double[] vaBuf = new double[W_A];
    private int vaHead, vaCount;

    // ── τ_est ring buffer (W_TAU valid τ_est values, copied from v15) ──────────
    private final double[] tauEstBuf = new double[W_TAU];
    private int tauEstHead, tauEstCount;

    // ── pre-calibration probe settling buffer ──────────────────────────────────
    private double[] slopeBuf;
    private int slopeWin, slopeHead, slopeCount;
    private int minFramesPerStep, maxFramesPerStep;
    private int framesSinceProbeStep;

    // ── pre-calibration probe state ────────────────────────────────────────────
    private boolean probeStepped;
    private boolean probeEarlyFired;  // true once smoothed first exceeds target
    private double  probeBaseSmoothed;
    private double  probeBaseFracR;
    private double  probeBaseFmt;

    // ── bracket state ─────────────────────────────────────────────────────────
    private BracketEndpoint softEndpoint;   // params that produced signal > target
    private BracketEndpoint stiffEndpoint;  // params that produced signal < target

    // ── RUNNING loop state ────────────────────────────────────────────────────
    private Phase  phase;
    private int    framesSinceLastStep;
    private int    stepCount;
    private double lastStepFracR, lastStepFmt;  // params when last step was applied
    private double crossingRef;                 // signal saved just before last step
    private boolean crossingRefValid;

    // ── phase ─────────────────────────────────────────────────────────────────
    Phase   getPhase()        { return phase; }
    boolean isDone()          { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()     { return fracMove; }
    double  getFracR()        { return fracR; }
    double  getFracMoveTorq() { return fracMoveTorq; }
    double  getSmoothed()     { return smoothed; }

    String resultSummary() {
        return String.format("[V16] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  steps=%d",
            phase, fracMove, fracR, fracMoveTorq, stepCount);
    }

    // ── public API ────────────────────────────────────────────────────────────

    void start(double fm, double fr, double fmt, double targetMicrons, double tauSeconds) {
        fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        target   = targetMicrons;
        tauTheo  = (tauSeconds > 0 && !Double.isNaN(tauSeconds)) ? tauSeconds : 0.1;
        dtFrame  = SECONDS_PER_FRAME;

        // Noise floors calibrated from 32-monomer near-equilibrium tail (v15, 2026-05-20):
        // v std=2.55e-5 → 3σ=7.65e-5; a std=4.15e-4 → 3σ=1.25e-3. Conservative round-up.
        vNoise   = 8e-5;   // µm/s
        aNoise   = 1.3e-3; // µm/s²
        wHoldMax = (int) Math.max(50, Math.round(4.0 * tauTheo / dtFrame));  // PLACEHOLDER

        // Probe settling window (same physics-scaled formula as v14/v15)
        if (tauSeconds > 0 && !Double.isNaN(tauSeconds)) {
            slopeWin         = (int) Math.max(MIN_SLOPE_WIN,
                                   Math.round(SLOPE_TAU_FRAC * tauSeconds / dtFrame));
            minFramesPerStep = (int) Math.max(5, Math.round(0.5 * tauSeconds / dtFrame));
            maxFramesPerStep = (int) Math.max(200, Math.round(20.0 * tauSeconds / dtFrame));
        } else {
            slopeWin = MIN_SLOPE_WIN; minFramesPerStep = 5; maxFramesPerStep = 200;
        }
        slopeBuf = new double[slopeWin];

        // reset all state
        phase = Phase.PRE_PROBE_FR;
        smoothed = 0; smoothedInit = false;
        vaHead = 0; vaCount = 0;
        tauEstHead = 0; tauEstCount = 0;
        slopeHead = 0; slopeCount = 0; framesSinceProbeStep = 0;
        probeStepped = false; probeEarlyFired = false;
        probeBaseSmoothed = 0; probeBaseFracR = fr; probeBaseFmt = fmt;
        softEndpoint = null; stiffEndpoint = null;
        framesSinceLastStep = 0; stepCount = 0;
        lastStepFracR = fr; lastStepFmt = fmt;
        crossingRef = Double.NaN; crossingRefValid = false;

        System.out.printf(
            "[V16] armed: τ=%.3fs  dtFrame=%.4fs  vNoise=%.3e  aNoise=%.3e"
            + "  wHoldMax=%d  W_A=%d  slopeWin=%d"
            + "  INTO_BRACKET=%.2f  FIRST_STEP=%.2f  CONV_TOL=%.2e%n",
            tauTheo, dtFrame, vNoise, aNoise, wHoldMax, W_A, slopeWin,
            INTO_BRACKET_FRACTION, FIRST_STEP_FRACTION, CONV_TOL_UM);
    }

    /**
     * Feed one output-frame smoothed deflection sample (µm; force must be ON).
     * Returns a ParamTriple when a parameter change is needed, null otherwise.
     * After isDone() returns true, always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        // EMA (copied from v15)
        smoothed = smoothedInit
            ? EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed
            : observedMicrons;
        smoothedInit = true;

        // Quadratic regression buffer
        pushVA(smoothed);
        double[] va = (vaCount >= W_A) ? computeVA() : null;
        double   v  = va != null ? va[0] : Double.NaN;
        double   a  = va != null ? va[1] : Double.NaN;

        // τ_est: valid when |v|>vNoise, |a|>aNoise, opposite signs, positive, sane cap
        double tauEst = Double.NaN;
        if (!Double.isNaN(v) && !Double.isNaN(a)
                && Math.abs(v) > vNoise && Math.abs(a) > aNoise && (v > 0) != (a > 0)) {
            double cand = -v / a;
            if (cand > 0 && cand < 100.0) { tauEst = cand; pushTauEst(tauEst); }
        }
        boolean tauStable = isTauStable();
        double  tauMean   = tauEstMean();
        double  tauUse    = (tauStable && !Double.isNaN(tauMean)) ? tauMean : tauTheo;
        double  dInf      = !Double.isNaN(v) ? smoothed + v * tauUse : Double.NaN;

        // ── Pre-calibration probe phases ──────────────────────────────────────
        if (phase == Phase.PRE_PROBE_FR || phase == Phase.PRE_PROBE_FMT) {
            return handlePreProbe();
        }

        // ── RUNNING ───────────────────────────────────────────────────────────
        framesSinceLastStep++;

        // Crossing signal: dInf when tauStable, smoothed s as fallback when tau_est unreliable
        // (e.g. first frames after a step when the τ_est ring buffer has been cleared)
        double signal = (tauStable && !Double.isNaN(dInf)) ? dInf : smoothed;

        // Initialize crossing reference on first eligible frame (before any step is taken)
        if (!crossingRefValid && framesSinceLastStep >= W_POST_STEP) {
            crossingRef      = signal;
            crossingRefValid = true;
        }

        // Per-frame WATCH log
        System.out.printf(
            "[V16:WATCH] wf=%d  s=%.6f  v=%s  a=%s  τe=%s  stable=%b"
            + "  d∞=%s  sig=%.6f  target=%.6f%n",
            framesSinceLastStep, smoothed,
            Double.isNaN(v)      ? "  ---  " : String.format("%.3e", v),
            Double.isNaN(a)      ? "  ---  " : String.format("%.3e", a),
            Double.isNaN(tauEst) ? " --- " : String.format("%.4f", tauEst),
            tauStable,
            Double.isNaN(dInf)   ? "   ---  " : String.format("%.6f", dInf),
            signal, target);

        // Convergence check: both brackets established, dInf within tolerance, chain settled
        if (softEndpoint != null && stiffEndpoint != null
                && !Double.isNaN(dInf) && Math.abs(dInf - target) < CONV_TOL_UM
                && !Double.isNaN(a) && Math.abs(a) < A_SETTLED_FRAC * aNoise
                && framesSinceLastStep > W_POST_STEP) {
            phase = Phase.CONVERGED;
            System.out.printf(
                "[V16] CONVERGED  dInf=%.6f  target=%.6f  |dInf-target|=%.3e"
                + "  |a|=%.3e < %.3e  steps=%d%n",
                dInf, target, Math.abs(dInf - target),
                Math.abs(a), A_SETTLED_FRAC * aNoise, stepCount);
            return null;
        }

        // Post-step quiet period: let the immediate parameter-change kick wash out
        if (framesSinceLastStep < W_POST_STEP) return null;

        // Crossing detection: has the crossing signal crossed target since the last step?
        if (crossingRefValid && (crossingRef - target) * (signal - target) < 0) {
            // Record the previous step's params as a bracket endpoint
            recordEndpoint(signal);
            System.out.printf(
                "[V16:STEP#%d] cross-detected  ref=%.6f→sig=%.6f  %s%n",
                stepCount + 1, crossingRef, signal,
                signal < target ? "(was tooSoft, now tooStiff)" : "(was tooStiff, now tooSoft)");
            logBracketState();
            return stepIntoBracket(signal, dInf);
        }

        // Hold-timeout: no crossing for too long — force a step toward stiffer to perturb the chain
        if (framesSinceLastStep > wHoldMax) {
            System.out.printf(
                "[V16] forceStep: hold timeout (%d > %d), perturbing toward stiffer%n",
                framesSinceLastStep, wHoldMax);
            return forceStep(signal, dInf);
        }

        return null;
    }

    // ── Pre-calibration probe ─────────────────────────────────────────────────

    private ParamTriple handlePreProbe() {
        pushSlope(smoothed);
        framesSinceProbeStep++;

        // Wait for chain to deflect above target before taking the probe step.
        // Soft-start params guarantee this will happen quickly.
        if (!probeStepped && !probeEarlyFired) {
            if (smoothed > target) probeEarlyFired = true;
            else return null;
        }

        if (!probeStepped) {
            if (phase == Phase.PRE_PROBE_FR) {
                probeBaseSmoothed = smoothed;
                probeBaseFracR    = fracR;
                double newFr = Math.max(fracR - PROBE_STEP_FR, FRAC_R_MIN);
                if (Math.abs(newFr - fracR) < 1e-9) {
                    // Already at min; skip this probe
                    System.out.printf("[V16] PRE_PROBE_FR: fracR already at min (%.4f), skipping%n",
                        fracR);
                    advancePhaseAfterFrProbe();
                    return null;
                }
                fracR = newFr;
                probeStepped = true; framesSinceProbeStep = 0; resetSlopeBuffer();
                System.out.printf(
                    "[V16] PRE_PROBE_FR: fracR %.4f→%.4f  base=%.6fµm%n",
                    probeBaseFracR, fracR, probeBaseSmoothed);
                return new ParamTriple(fracMove, fracR, fracMoveTorq);
            } else {
                probeBaseSmoothed = smoothed;
                probeBaseFmt      = fracMoveTorq;
                double newFmt = Math.min(fracMoveTorq + PROBE_STEP_FMT, FRAC_MT_MAX);
                if (Math.abs(newFmt - fracMoveTorq) < 1e-9) {
                    System.out.printf("[V16] PRE_PROBE_FMT: fmt already at max (%.4f), skipping%n",
                        fracMoveTorq);
                    advanceToRunning();
                    return null;
                }
                fracMoveTorq = newFmt;
                probeStepped = true; framesSinceProbeStep = 0; resetSlopeBuffer();
                System.out.printf(
                    "[V16] PRE_PROBE_FMT: fracMoveTorq %.4f→%.4f  base=%.6fµm%n",
                    probeBaseFmt, fracMoveTorq, probeBaseSmoothed);
                return new ParamTriple(fracMove, fracR, fracMoveTorq);
            }
        }

        // Waiting for settling after probe step
        if (!isSettled() && framesSinceProbeStep < maxFramesPerStep) return null;

        // Evaluate sign: stiffer parameters should decrease deflection
        boolean signOk = smoothed < probeBaseSmoothed;

        if (phase == Phase.PRE_PROBE_FR) {
            System.out.printf(
                "[V16] PRE_PROBE_FR eval: base=%.6f  now=%.6f  decreased=%b (frames=%d)%n",
                probeBaseSmoothed, smoothed, signOk, framesSinceProbeStep);
            if (!signOk) {
                phase = Phase.FAILED;
                System.out.println(
                    "[V16] FAILED: fracR sensitivity sign unexpected"
                    + " (smoothed did not decrease when fracR decreased by " + PROBE_STEP_FR + ")");
                return null;
            }
            fracR = probeBaseFracR;  // restore
            advancePhaseAfterFrProbe();
            System.out.printf("[V16] PRE_PROBE_FR OK  fracR reset to %.4f  → PRE_PROBE_FMT%n",
                fracR);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        } else {
            System.out.printf(
                "[V16] PRE_PROBE_FMT eval: base=%.6f  now=%.6f  decreased=%b (frames=%d)%n",
                probeBaseSmoothed, smoothed, signOk, framesSinceProbeStep);
            if (!signOk) {
                phase = Phase.FAILED;
                System.out.println(
                    "[V16] FAILED: fracMoveTorq sensitivity sign unexpected"
                    + " (smoothed did not decrease when fracMoveTorq increased by " + PROBE_STEP_FMT + ")");
                return null;
            }
            fracMoveTorq = probeBaseFmt;  // restore
            advanceToRunning();
            System.out.printf("[V16] PRE_PROBE_FMT OK  fracMoveTorq reset to %.4f  → RUNNING%n",
                fracMoveTorq);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }
    }

    private void advancePhaseAfterFrProbe() {
        phase = Phase.PRE_PROBE_FMT;
        probeStepped = false; probeEarlyFired = false;
        framesSinceProbeStep = 0; resetSlopeBuffer();
    }

    private void advanceToRunning() {
        phase = Phase.RUNNING;
        framesSinceLastStep = 0;
        crossingRef = Double.NaN; crossingRefValid = false;
        System.out.printf("[V16] → RUNNING  fracR=%.4f  fracMoveTorq=%.4f  target=%.6fµm%n",
            fracR, fracMoveTorq, target);
    }

    // ── Bracket endpoint recording ────────────────────────────────────────────

    private void recordEndpoint(double currentSignal) {
        // lastStepFracR/fmt were the params active when the last step was applied.
        // crossingRef is the signal at that moment — approximates equilibrium at those params.
        BracketEndpoint ep = new BracketEndpoint(lastStepFracR, lastStepFmt, crossingRef);
        if (currentSignal < target) {
            // Crossed downward: just left "too soft" side → record soft endpoint
            softEndpoint = ep;
        } else {
            // Crossed upward: just left "too stiff" side → record stiff endpoint
            stiffEndpoint = ep;
        }
    }

    private void logBracketState() {
        if (softEndpoint != null)
            System.out.printf("[V16:BRACKET] soft=(fracR=%.4f, fmt=%.4f, dInf=%.6f)%n",
                softEndpoint.fracR, softEndpoint.fracMoveTorq, softEndpoint.dInf);
        if (stiffEndpoint != null)
            System.out.printf("[V16:BRACKET] stiff=(fracR=%.4f, fmt=%.4f, dInf=%.6f)%n",
                stiffEndpoint.fracR, stiffEndpoint.fracMoveTorq, stiffEndpoint.dInf);
    }

    // ── Step into bracket ─────────────────────────────────────────────────────

    private ParamTriple stepIntoBracket(double currentSignal, double currentDInf) {
        boolean tooSoft  = currentSignal > target;
        boolean haveBoth = softEndpoint != null && stiffEndpoint != null;
        double targetFr, targetFmt;

        if (haveBoth) {
            // Step toward the opposite endpoint (midpoint = INTO_BRACKET_FRACTION=0.5)
            if (tooSoft) {
                targetFr  = lerp(softEndpoint.fracR,        stiffEndpoint.fracR,        INTO_BRACKET_FRACTION);
                targetFmt = lerp(softEndpoint.fracMoveTorq, stiffEndpoint.fracMoveTorq, INTO_BRACKET_FRACTION);
            } else {
                targetFr  = lerp(stiffEndpoint.fracR,        softEndpoint.fracR,        INTO_BRACKET_FRACTION);
                targetFmt = lerp(stiffEndpoint.fracMoveTorq, softEndpoint.fracMoveTorq, INTO_BRACKET_FRACTION);
            }
        } else {
            // No bracket yet on the far side: step aggressively toward the hardware limit
            if (tooSoft) {
                // too soft → step toward stiffer (lower fracR, higher fmt)
                targetFr  = lerp(fracR,        FRAC_R_MIN,  FIRST_STEP_FRACTION);
                targetFmt = lerp(fracMoveTorq, FRAC_MT_MAX, FIRST_STEP_FRACTION);
            } else {
                // too stiff → step toward softer (higher fracR, lower fmt)
                targetFr  = lerp(fracR,        FRAC_R_MAX,  FIRST_STEP_FRACTION);
                targetFmt = lerp(fracMoveTorq, FRAC_MT_MIN, FIRST_STEP_FRACTION);
            }
        }

        double[] enforced = enforceMinStep(targetFr, targetFmt, tooSoft);
        targetFr  = clamp(enforced[0], FRAC_R_MIN, FRAC_R_MAX);
        targetFmt = clamp(enforced[1], FRAC_MT_MIN, FRAC_MT_MAX);
        return applyStep(targetFr, targetFmt, "stepIntoBracket", currentSignal);
    }

    // ── Force step on hold timeout ────────────────────────────────────────────

    private ParamTriple forceStep(double currentSignal, double currentDInf) {
        // Step toward stiffer using FIRST_STEP_FRACTION, without needing a bracket.
        // This fires when soft-start lands near target by chance and no crossing occurs.
        boolean tooSoft = currentSignal > target;
        double targetFr, targetFmt;
        if (tooSoft) {
            targetFr  = lerp(fracR,        FRAC_R_MIN,  FIRST_STEP_FRACTION);
            targetFmt = lerp(fracMoveTorq, FRAC_MT_MAX, FIRST_STEP_FRACTION);
        } else {
            targetFr  = lerp(fracR,        FRAC_R_MAX,  FIRST_STEP_FRACTION);
            targetFmt = lerp(fracMoveTorq, FRAC_MT_MIN, FIRST_STEP_FRACTION);
        }
        double[] enforced = enforceMinStep(targetFr, targetFmt, tooSoft);
        targetFr  = clamp(enforced[0], FRAC_R_MIN, FRAC_R_MAX);
        targetFmt = clamp(enforced[1], FRAC_MT_MIN, FRAC_MT_MAX);
        return applyStep(targetFr, targetFmt, "forceStep", currentSignal);
    }

    // ── Apply step: record baselines, update params, reset counters ────────────

    private ParamTriple applyStep(double newFr, double newFmt, String reason,
                                   double currentSignal) {
        // Save the current signal as the crossing reference (pre-step equilibrium approx).
        // This becomes the endpoint dInf when a crossing is later detected.
        crossingRef      = currentSignal;
        crossingRefValid = true;
        lastStepFracR    = fracR;
        lastStepFmt      = fracMoveTorq;

        double oldFr  = fracR;
        double oldFmt = fracMoveTorq;
        fracR         = newFr;
        fracMoveTorq  = newFmt;

        stepCount++;
        framesSinceLastStep = 0;
        clearTauEstBuf();  // force fresh τ_est accumulation after parameter change

        System.out.printf(
            "[V16:STEP#%d] %s  fr: %.4f→%.4f  fmt: %.4f→%.4f"
            + "  signal=%.6f  target=%.6f  bracket=%s%n",
            stepCount, reason, oldFr, fracR, oldFmt, fracMoveTorq,
            currentSignal, target,
            softEndpoint != null && stiffEndpoint != null ? "both"
                : softEndpoint != null ? "soft-only"
                : stiffEndpoint != null ? "stiff-only" : "none");
        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── Minimum-step enforcement ───────────────────────────────────────────────

    private double[] enforceMinStep(double targetFr, double targetFmt, boolean tooSoft) {
        double stepFr  = targetFr  - fracR;
        double stepFmt = targetFmt - fracMoveTorq;
        double minFr   = Math.max(MIN_STEP_FRACTION * Math.abs(fracR),  1e-9);
        double minFmt  = Math.max(MIN_STEP_FRACTION * Math.abs(fracMoveTorq), 1e-9);

        if (Math.abs(stepFr) < minFr) {
            // Use the direction from the original step; if zero, use tooSoft direction
            double dir = Math.abs(stepFr) > 1e-12 ? Math.signum(stepFr) : (tooSoft ? -1.0 : +1.0);
            targetFr = fracR + dir * minFr;
        }
        if (Math.abs(stepFmt) < minFmt) {
            double dir = Math.abs(stepFmt) > 1e-12 ? Math.signum(stepFmt) : (tooSoft ? +1.0 : -1.0);
            targetFmt = fracMoveTorq + dir * minFmt;
        }
        return new double[]{targetFr, targetFmt};
    }

    // ── Quadratic regression ring buffer (copied verbatim from v15) ───────────

    private void pushVA(double s) {
        vaBuf[vaHead] = s;
        vaHead = (vaHead + 1) % W_A;
        if (vaCount < W_A) vaCount++;
    }

    /**
     * Quadratic fit on W_A rolling buffer (evenly spaced at dtFrame).
     * Returns {v_n, a_n} in µm/s and µm/s² at the most recent frame, or null on error.
     * Uses centered coordinates so odd-order sums vanish.
     */
    private double[] computeVA() {
        double mid    = (W_A - 1) * 0.5;
        double sumU2  = 0, sumU4 = 0, sumS = 0, sumUS = 0, sumU2S = 0;
        for (int k = 0; k < W_A; k++) {
            int    idx = (vaHead + k) % W_A;
            double u   = (k - mid) * dtFrame;
            double s   = vaBuf[idx];
            double u2  = u * u;
            sumU2  += u2; sumU4  += u2 * u2;
            sumS   += s;  sumUS  += u * s;  sumU2S += u2 * s;
        }
        double b = sumUS / sumU2;
        double denom = (double) W_A * sumU4 - sumU2 * sumU2;
        if (Math.abs(denom) < 1e-30) return null;
        double c     = ((double) W_A * sumU2S - sumU2 * sumS) / denom;
        double uLast = (W_A - 1) * 0.5 * dtFrame;
        return new double[]{b + 2.0 * c * uLast, 2.0 * c};
    }

    // ── τ_est ring buffer (copied verbatim from v15) ──────────────────────────

    private void pushTauEst(double tau) {
        tauEstBuf[tauEstHead] = tau;
        tauEstHead = (tauEstHead + 1) % W_TAU;
        if (tauEstCount < W_TAU) tauEstCount++;
    }

    private void clearTauEstBuf() { tauEstHead = 0; tauEstCount = 0; }

    private boolean isTauStable() {
        if (tauEstCount < W_TAU) return false;
        double sum = 0;
        for (int k = 0; k < W_TAU; k++) sum += tauEstBuf[k];
        double mean = sum / W_TAU;
        if (mean <= 0) return false;
        double varSum = 0;
        for (int k = 0; k < W_TAU; k++) { double d = tauEstBuf[k] - mean; varSum += d * d; }
        return Math.sqrt(varSum / W_TAU) / mean < TAU_STABLE_FRAC;
    }

    private double tauEstMean() {
        if (tauEstCount < W_TAU) return Double.NaN;
        double sum = 0;
        for (int k = 0; k < W_TAU; k++) sum += tauEstBuf[k];
        return sum / W_TAU;
    }

    // ── Probe settling buffer (same logic as v14/v15 probe phases) ─────────────

    private void resetSlopeBuffer() { slopeHead = 0; slopeCount = 0; }

    private void pushSlope(double val) {
        slopeBuf[slopeHead] = val;
        slopeHead = (slopeHead + 1) % slopeWin;
        if (slopeCount < slopeWin) slopeCount++;
    }

    private boolean isSettled() {
        if (framesSinceProbeStep < minFramesPerStep || slopeCount < slopeWin) return false;
        double oldest = slopeBuf[slopeHead];
        double newest = slopeBuf[(slopeHead - 1 + slopeWin) % slopeWin];
        double scale  = Math.max(Math.abs(smoothed), Math.abs(target));
        if (scale < 1e-12) scale = 1e-12;
        return Math.abs(newest - oldest) < SETTLE_TOL_FRAC * scale;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double lerp(double a, double b, double t) { return a + t * (b - a); }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
