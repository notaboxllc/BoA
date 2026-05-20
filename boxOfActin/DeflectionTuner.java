package boxOfActin;

/**
 * Automated deflection tuning — v11: physics-scaled settling detector.
 *
 * v8: actual-step convergence, fmt-saturated fracR rescue, crossing-triggered stepping, CONV_FRAMES=10.
 * v9: clear fmt brackets in fr-rescue branch (stale bounds after fracR shift).
 * v10: in normal FINE branch, detect when a fmt bracket has eaten >98% of the desired trust-region
 *      step and sensitivity confirms the direction; clear the obstructing bound and retry once.
 * v11: SLOPE_WIN, MIN_FRAMES_PER_STEP, MAX_FRAMES_PER_STEP are now instance fields computed from
 *      τ_theo at start() so the settling detector covers ~2τ of physical time. Fixes limit-cycle
 *      oscillation on slow chains (48-monomer) where the static window covered only 0.4τ.
 * v12: symmetric saturation rescue — the soft-end case (params at softest extremes, chain still
 *      too stiff) now fires saturationRescue() in both COARSE and FINE, mirroring the existing
 *      stiff-end rescue. stiffEndRescue() renamed saturationRescue().
 * v13: saturation rescue check added to PROBE_FMT "not yet stepped" branch. Previously, a
 *      fully-saturated-soft configuration produced a zero probe step, which silently advanced
 *      through PROBE_FMT → PROBE_FR → COARSE before the rescue fired. Now fires immediately.
 *
 * Phase order: PROBE_FMT → PROBE_FR → COARSE → FINE → CONVERGED (or FAILED anywhere)
 *
 * Direction convention (empirically confirmed):
 *   Larger fracR        → softer (more deflection); frSens > 0
 *   Larger fracMoveTorq → stiffer (less deflection); fmtSens < 0
 */
class DeflectionTuner {

    enum Phase { PROBE_FMT, PROBE_FR, COARSE, FINE, CONVERGED, FAILED }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── constants ──────────────────────────────────────────────────────────────
    static final double EMA_ALPHA         = 0.05;
    static final double SETTLE_TOL_FRAC   = 0.01;
    static final double SLOPE_TAU_FRAC    = 2.0;   // observation window covers ~2τ of physical time
    static final double SECONDS_PER_FRAME = 0.01;  // drawInterval × deltaT
    static final int    MIN_SLOPE_WIN     = 10;    // floor: never narrower than v10's SLOPE_WIN
    static final double INIT_PROBE_STEP     = 0.05;
    static final double INIT_TRUST          = 0.1;
    static final double MIN_TRUST           = 0.005;
    static final double MAX_TRUST           = 0.3;
    static final double TRUST_GROW          = 1.5;
    static final double TRUST_SHRINK        = 0.5;
    static final double PREDICTED_FRACTION  = 0.5;
    static final double PRED_STEP_TOL       = 0.001;
    static final double CONV_TOL_UM         = 5e-6;
    static final int    CONV_FRAMES         = 10;   // was 50; 10 suffices with settling/crossing triggers
    static final double FR_FINE_THRESH      = 0.02;
    static final double ALPHA_DIST          = 0.5;
    static final double FRAC_MOVE_STEP      = 0.05;
    static final int    MAX_PROBE_RETRIES   = 3;

    // parameter limits
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // rescue guard: stiff-end rescue fires only when error exceeds this fraction of expected
    static final double RESCUE_ERR_FRAC = 0.05;

    // bracket obstruction: clear an fmt bracket if it ate >98% of the desired trust-region step
    static final double OBSTRUCTION_FRAC = 0.02;

    // ── current parameter values ───────────────────────────────────────────────
    private double fracMove, fracR, fracMoveTorq;
    private double expected;

    // ── EMA ───────────────────────────────────────────────────────────────────
    private double  smoothed;
    private boolean smoothedInit;

    // ── slope ring buffer ──────────────────────────────────────────────────────
    // slopeWin and slopeBuf are sized in start() based on τ_theo (physics-scaled).
    private int    slopeWin;           // physics-scaled equivalent of v10's SLOPE_WIN = 10
    private double[] slopeBuf;         // allocated in start()
    private int slopeHead;             // next write position; when full, also points to oldest
    private int slopeCount;            // 0..slopeWin

    // ── step-timing bounds (physics-scaled) ────────────────────────────────────
    private int minFramesPerStep;      // physics-scaled equivalent of v10's MIN_FRAMES_PER_STEP = 5
    private int maxFramesPerStep;      // physics-scaled equivalent of v10's MAX_FRAMES_PER_STEP = 200

    // ── step timing ───────────────────────────────────────────────────────────
    private int framesSinceStep;

    // ── baselines (recorded when step is taken) ────────────────────────────────
    private double smoothedAtStep;
    private double frAtStep;
    private double fmtAtStep;
    private double errorAtStep;

    // ── trust regions ─────────────────────────────────────────────────────────
    private double trustFr;
    private double trustFmt;

    // ── fracR controller ──────────────────────────────────────────────────────
    private double  frSens;
    private boolean frHasSens;
    private double  frLoBound;
    private boolean frHasLo;
    private double  frHiBound;
    private boolean frHasHi;
    private double  frPredStep;  // |rawFrPred| at last COARSE step; drives COARSE→FINE

    // ── fracMoveTorq controller ───────────────────────────────────────────────
    private double  fmtSens;
    private boolean fmtHasSens;
    private double  fmtLoBound;
    private boolean fmtHasLo;
    private double  fmtHiBound;
    private boolean fmtHasHi;

    // ── phase ─────────────────────────────────────────────────────────────────
    private Phase phase;

    // ── convergence ───────────────────────────────────────────────────────────
    private int    convCount;
    private double lastActStepMag;  // max |actual step| in most recent step; NaN until first step

    // ── COARSE→FINE detection ─────────────────────────────────────────────────
    private int fineReadyCount;

    // ── first-step flag: skip sensitivity/trust update on first step of phase ──
    private boolean firstStepInPhase;

    // ── PROBE sub-state ───────────────────────────────────────────────────────
    private boolean probeStepped;    // false=waiting to take step; true=waiting to evaluate
    private double  probeStep;
    private int     probeRetryCount;
    private boolean firstProbeFired; // true after first PROBE_FMT step taken; gates cold-start early trigger

    // ── crossing detection ────────────────────────────────────────────────────
    private boolean lastErrorSign;      // sign of (error > 0) at the time of the last step
    private boolean lastErrorSignValid; // false until at least one step has been taken

    // ── logging ───────────────────────────────────────────────────────────────
    private int stepCount;

    // ── public API ────────────────────────────────────────────────────────────

    void start(double fm, double fr, double fmt, double expectedMicrons, double tauSeconds) {
        fracMove     = fm;
        fracR        = fr;
        fracMoveTorq = fmt;
        expected     = expectedMicrons;

        // Physics-scaled settling detector: window covers ~2τ of simulated time.
        // Falls back to v10 static values when τ is unavailable (zero or NaN).
        if (tauSeconds > 0 && !Double.isNaN(tauSeconds)) {
            slopeWin          = (int) Math.max(MIN_SLOPE_WIN,
                                    Math.round(SLOPE_TAU_FRAC * tauSeconds / SECONDS_PER_FRAME));
            minFramesPerStep  = (int) Math.max(5,
                                    Math.round(0.5 * tauSeconds / SECONDS_PER_FRAME));
            maxFramesPerStep  = (int) Math.max(200,
                                    Math.round(20.0 * tauSeconds / SECONDS_PER_FRAME));
        } else {
            slopeWin         = MIN_SLOPE_WIN;
            minFramesPerStep = 5;
            maxFramesPerStep = 200;
        }
        slopeBuf = new double[slopeWin];

        phase        = Phase.PROBE_FMT;
        smoothed     = 0.0;
        smoothedInit = false;

        slopeHead = 0; slopeCount = 0;
        framesSinceStep = 0;

        trustFr  = INIT_TRUST;
        trustFmt = INIT_TRUST;

        frSens  = 0; frHasSens = false; frHasLo = false; frHasHi = false;
        fmtSens = 0; fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
        frPredStep = Double.NaN;

        smoothedAtStep = 0; frAtStep = fr; fmtAtStep = fmt; errorAtStep = 0;

        convCount      = 0;
        lastActStepMag = Double.NaN;
        fineReadyCount = 0;
        firstStepInPhase = true;

        probeStepped    = false;
        probeStep       = INIT_PROBE_STEP;
        probeRetryCount = 0;
        firstProbeFired = false;

        lastErrorSign      = false;
        lastErrorSignValid = false;

        stepCount = 0;

        System.out.printf("[AUTOTUNE] armed: τ=%.3fs  slope_win=%d  min_step=%d  max_step=%d%n",
            tauSeconds, slopeWin, minFramesPerStep, maxFramesPerStep);
    }

    /**
     * Feed one output-frame deflection sample (µm; force must be ON).
     * Returns null most frames. Returns a ParamTriple at each parameter step.
     * After isDone(), always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        smoothed = smoothedInit
            ? EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed
            : observedMicrons;
        smoothedInit = true;

        pushSlope(smoothed);
        framesSinceStep++;

        double error = smoothed - expected;
        checkConvergence(error);
        if (isDone()) return null;

        boolean force = framesSinceStep >= maxFramesPerStep;
        if (force) {
            System.out.printf("[AUTOTUNE:WARN] %s unsettled after %d frames — forcing step%n",
                phase, maxFramesPerStep);
        }
        // Early trigger: if this is the very first probe and deflection already exceeds
        // expected, skip the settling detector — the chain is too soft right now and
        // waiting for settling wastes time without providing any useful information.
        boolean earlyTrigger = !firstProbeFired && phase == Phase.PROBE_FMT && smoothed > expected;

        // Crossing trigger: fires when the error sign has flipped since the last step,
        // at least MIN_FRAMES_PER_STEP after the last step. Applies in all phases
        // once at least one step has been taken (lastErrorSignValid guards the cold start).
        boolean crossingFired = lastErrorSignValid
            && framesSinceStep >= minFramesPerStep
            && ((error > 0) != lastErrorSign);

        if (!isSettled() && !force && !earlyTrigger && !crossingFired) return null;

        switch (phase) {
            case PROBE_FMT: return handleProbeFmt(error);
            case PROBE_FR:  return handleProbeFr(error);
            case COARSE:    return handleCoarse(error, crossingFired);
            case FINE:      return handleFine(error, crossingFired);
            default:        return null;
        }
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

    // ── slope ring buffer ──────────────────────────────────────────────────────

    private void resetSlopeBuffer() {
        slopeHead = 0;
        slopeCount = 0;
    }

    private void pushSlope(double val) {
        slopeBuf[slopeHead] = val;
        slopeHead = (slopeHead + 1) % slopeWin;
        if (slopeCount < slopeWin) slopeCount++;
    }

    private boolean isSettled() {
        if (framesSinceStep < minFramesPerStep) return false;
        if (slopeCount < slopeWin) return false;
        // When full: oldest is at slopeHead; newest is at (slopeHead-1+slopeWin)%slopeWin
        double oldest = slopeBuf[slopeHead];
        double newest = slopeBuf[(slopeHead - 1 + slopeWin) % slopeWin];
        double scale  = Math.max(Math.abs(smoothed), Math.abs(expected));
        if (scale < 1e-12) scale = 1e-12;
        return Math.abs(newest - oldest) < SETTLE_TOL_FRAC * scale;
    }

    // ── probe phase: fracMoveTorq ──────────────────────────────────────────────

    private ParamTriple handleProbeFmt(double error) {
        if (!probeStepped) {
            if (error < -RESCUE_ERR_FRAC * expected
                    && fracR >= FRAC_R_MAX - 1e-9
                    && fracMoveTorq <= FRAC_MT_MIN + 1e-9) {
                return saturationRescue();
            }
            if (error > RESCUE_ERR_FRAC * expected
                    && fracR <= FRAC_R_MIN + 1e-9
                    && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
                return saturationRescue();
            }
            // Stage 1: take probe step
            updateFmtBrackets(error);
            double dir    = (error >= 0) ? +1.0 : -1.0;
            double newFmt = clamp(fracMoveTorq + dir * probeStep, FRAC_MT_MIN, FRAC_MT_MAX);
            double step   = newFmt - fracMoveTorq;

            smoothedAtStep  = smoothed;
            fmtAtStep       = fracMoveTorq;
            errorAtStep     = error;
            fracMoveTorq    = newFmt;
            probeStepped    = true;
            firstProbeFired = true;
            stepCount++;
            framesSinceStep = 0;
            lastActStepMag     = Math.abs(step);
            lastErrorSign      = (error > 0);
            lastErrorSignValid = true;
            resetSlopeBuffer();

            System.out.printf(
                "[AUTOTUNE:STEP#%d] PROBE_FMT take  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fmt: %.4f→%.4f (step=%+.4f)  retry=%d%n",
                stepCount, framesSinceStep, smoothed, expected, error,
                fmtAtStep, fracMoveTorq, step, probeRetryCount);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);

        } else {
            // Stage 2: evaluate sensitivity
            double trend   = smoothed - smoothedAtStep;
            double step    = fracMoveTorq - fmtAtStep;
            double rawSens = Math.abs(step) > 1e-12 ? trend / step : 0.0;
            boolean signOk = rawSens < 0;

            if (Math.abs(step) < 1e-12) {
                System.out.printf("[AUTOTUNE] PROBE_FMT step clamped to zero — advancing%n");
                signOk = true;
            } else if (!signOk) {
                System.out.printf(
                    "[AUTOTUNE] PROBE_FMT bad-sign: rawSens=%.4f (trend=%+.6f step=%+.4f) retry=%d%n",
                    rawSens, trend, step, probeRetryCount);
                if (probeRetryCount >= MAX_PROBE_RETRIES) {
                    phase = Phase.FAILED;
                    System.out.println("[AUTOTUNE] FAILED: PROBE_FMT exhausted retries");
                    return null;
                }
                probeRetryCount++;
                probeStep   /= 2.0;
                probeStepped = false;
                return handleProbeFmt(error);
            }

            if (signOk && Math.abs(step) > 1e-12) {
                fmtSens    = rawSens;
                fmtHasSens = true;
            }
            updateFmtBrackets(error);

            System.out.printf(
                "[AUTOTUNE] PROBE_FMT eval: trend=%+.6f  step=%+.4f  fmtSens=%.4f  → PROBE_FR%n",
                trend, step, fmtSens);

            phase           = Phase.PROBE_FR;
            probeStepped    = false;
            probeStep       = INIT_PROBE_STEP;
            probeRetryCount = 0;
            return handleProbeFr(error);
        }
    }

    // ── probe phase: fracR ─────────────────────────────────────────────────────

    private ParamTriple handleProbeFr(double error) {
        if (!probeStepped) {
            // Stage 1: take probe step
            updateFrBrackets(error);
            double dir   = (error >= 0) ? -1.0 : +1.0;
            double newFr = clamp(fracR + dir * probeStep, FRAC_R_MIN, FRAC_R_MAX);
            double step  = newFr - fracR;

            smoothedAtStep = smoothed;
            frAtStep       = fracR;
            errorAtStep    = error;
            fracR          = newFr;
            probeStepped   = true;
            stepCount++;
            framesSinceStep = 0;
            lastActStepMag     = Math.abs(step);
            lastErrorSign      = (error > 0);
            lastErrorSignValid = true;
            resetSlopeBuffer();

            System.out.printf(
                "[AUTOTUNE:STEP#%d] PROBE_FR take  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fr: %.4f→%.4f (step=%+.4f)  retry=%d%n",
                stepCount, framesSinceStep, smoothed, expected, error,
                frAtStep, fracR, step, probeRetryCount);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);

        } else {
            // Stage 2: evaluate sensitivity
            double trend   = smoothed - smoothedAtStep;
            double step    = fracR - frAtStep;
            double rawSens = Math.abs(step) > 1e-12 ? trend / step : 0.0;
            boolean signOk = rawSens > 0;

            if (Math.abs(step) < 1e-12) {
                System.out.printf("[AUTOTUNE] PROBE_FR step clamped to zero — advancing%n");
                signOk = true;
            } else if (!signOk) {
                System.out.printf(
                    "[AUTOTUNE] PROBE_FR bad-sign: rawSens=%.4f (trend=%+.6f step=%+.4f) retry=%d%n",
                    rawSens, trend, step, probeRetryCount);
                if (probeRetryCount >= MAX_PROBE_RETRIES) {
                    phase = Phase.FAILED;
                    System.out.println("[AUTOTUNE] FAILED: PROBE_FR exhausted retries");
                    return null;
                }
                probeRetryCount++;
                probeStep   /= 2.0;
                probeStepped = false;
                return handleProbeFr(error);
            }

            if (signOk && Math.abs(step) > 1e-12) {
                frSens    = rawSens;
                frHasSens = true;
            }
            updateFrBrackets(error);

            System.out.printf(
                "[AUTOTUNE] PROBE_FR eval: trend=%+.6f  step=%+.4f  frSens=%.4f  → COARSE%n",
                trend, step, frSens);

            phase            = Phase.COARSE;
            firstStepInPhase = true;
            fineReadyCount   = 0;
            return null;
        }
    }

    // ── COARSE phase ──────────────────────────────────────────────────────────

    private ParamTriple handleCoarse(double error, boolean crossing) {
        // Stiff-end: params at stiffest extremes, chain still too soft.
        if (error > RESCUE_ERR_FRAC * expected && fracR <= FRAC_R_MIN + 1e-9 && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
            return saturationRescue();
        }
        // Soft-end: params at softest extremes, chain still too stiff.
        if (error < -RESCUE_ERR_FRAC * expected && fracR >= FRAC_R_MAX - 1e-9 && fracMoveTorq <= FRAC_MT_MIN + 1e-9) {
            return saturationRescue();
        }

        if (!firstStepInPhase) {
            double defl    = smoothed     - smoothedAtStep;
            double deltaFr  = fracR        - frAtStep;
            double deltaFmt = fracMoveTorq - fmtAtStep;
            // Trust update before sensitivity so it uses the sensitivity that drove the step
            updateCoarseTrust(defl, deltaFr, deltaFmt, error);
            updateCoarseSensitivities(defl, deltaFr, deltaFmt);
        }

        updateFrBrackets(error);
        updateFmtBrackets(error);

        double rawFrPred  = frHasSens  ? ALPHA_DIST         * (expected - smoothed) / frSens  : 0.0;
        double rawFmtPred = fmtHasSens ? (1.0 - ALPHA_DIST) * (expected - smoothed) / fmtSens : 0.0;

        frPredStep = Math.abs(rawFrPred);

        double frStep = clamp(rawFrPred,  -trustFr,  trustFr);
        double newFr  = clamp(fracR   + frStep,  FRAC_R_MIN, FRAC_R_MAX);
        newFr  = clampToBrackets(newFr,  frHasLo,  frLoBound,  frHasHi,  frHiBound);
        double aFr = newFr - fracR;

        double fmtStep = clamp(rawFmtPred, -trustFmt, trustFmt);
        double newFmt  = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);
        newFmt = clampToBrackets(newFmt, fmtHasLo, fmtLoBound, fmtHasHi, fmtHiBound);
        double aFmt = newFmt - fracMoveTorq;

        System.out.printf(
            "[AUTOTUNE:STEP#%d] COARSE [%s]  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
            + "  fr=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)"
            + "  fmt=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)%n",
            stepCount + 1, crossing ? "crossing" : "settled",
            framesSinceStep, smoothed, expected, error,
            fracR,        frHasSens  ? String.format("%.4f", frSens)  : "---", rawFrPred,  aFr,  trustFr,
            fracMoveTorq, fmtHasSens ? String.format("%.4f", fmtSens) : "---", rawFmtPred, aFmt, trustFmt);

        smoothedAtStep     = smoothed;
        frAtStep           = fracR;
        fmtAtStep          = fracMoveTorq;
        errorAtStep        = error;
        fracR              = newFr;
        fracMoveTorq       = newFmt;
        stepCount++;
        firstStepInPhase   = false;
        framesSinceStep    = 0;
        lastActStepMag     = Math.max(Math.abs(aFr), Math.abs(aFmt));
        lastErrorSign      = (error > 0);
        lastErrorSignValid = true;
        resetSlopeBuffer();

        if (frPredStep < FR_FINE_THRESH) {
            if (++fineReadyCount >= 2) {
                phase            = Phase.FINE;
                firstStepInPhase = true;
                fineReadyCount   = 0;
                fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
                System.out.printf("[AUTOTUNE] COARSE→FINE: fracR=%.4f frozen  fracMoveTorq=%.4f%n",
                    fracR, fracMoveTorq);
            }
        } else {
            fineReadyCount = 0;
        }

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── FINE phase ────────────────────────────────────────────────────────────

    private ParamTriple handleFine(double error, boolean crossing) {
        // Stiff-end: fmt at stiffest extreme, chain still too soft.
        if (error > RESCUE_ERR_FRAC * expected && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
            return saturationRescue();
        }
        // Soft-end: fmt at softest extreme, chain still too stiff.
        if (error < -RESCUE_ERR_FRAC * expected && fracMoveTorq <= FRAC_MT_MIN + 1e-9) {
            return saturationRescue();
        }

        if (!firstStepInPhase) {
            double defl    = smoothed     - smoothedAtStep;
            double deltaFmt = fracMoveTorq - fmtAtStep;
            // Trust update before sensitivity so it uses the sensitivity that drove the step
            updateFineTrust(defl, deltaFmt, error);
            updateFineSensitivity(defl, deltaFmt);
        }

        updateFmtBrackets(error);

        double rawFmtPred = fmtHasSens
            ? (expected - smoothed) / fmtSens
            : ((error > 0) ? INIT_PROBE_STEP : -INIT_PROBE_STEP);

        // fr-rescue: fmt is saturated against a limit in the desired direction AND the
        // error is large enough that we're not already converged — use fracR instead.
        boolean fmtSaturatedAtLimit =
            (rawFmtPred > 0 && fracMoveTorq >= FRAC_MT_MAX - 1e-9) ||
            (rawFmtPred < 0 && fracMoveTorq <= FRAC_MT_MIN + 1e-9);

        if (fmtSaturatedAtLimit && Math.abs(error) > CONV_TOL_UM) {
            double rawFrPred    = frHasSens ? (expected - smoothed) / frSens : 0.0;
            double frRescueStep = clamp(rawFrPred, -trustFr, trustFr);
            double newFr        = clamp(fracR + frRescueStep, FRAC_R_MIN, FRAC_R_MAX);
            newFr = clampToBrackets(newFr, frHasLo, frLoBound, frHasHi, frHiBound);
            double aFr = newFr - fracR;

            System.out.printf(
                "[AUTOTUNE:STEP#%d] FINE [fr-rescue]  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fr=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)"
                + "  fmt=%.4f(saturated)%n",
                stepCount + 1, framesSinceStep, smoothed, expected, error,
                fracR, frHasSens ? String.format("%.4f", frSens) : "---", rawFrPred, aFr, trustFr,
                fracMoveTorq);

            smoothedAtStep     = smoothed;
            frAtStep           = fracR;
            fmtAtStep          = fracMoveTorq;
            errorAtStep        = error;
            fracR              = newFr;
            // Changing fracR shifts the deflection landscape; any fmt bracket recorded
            // under the old fracR is no longer a valid bound. Clear so the next FINE
            // step accumulates fresh brackets under the new fracR.
            fmtHasLo = false;
            fmtHasHi = false;
            stepCount++;
            firstStepInPhase   = false;
            framesSinceStep    = 0;
            lastActStepMag     = Math.abs(aFr);
            lastErrorSign      = (error > 0);
            lastErrorSignValid = true;
            resetSlopeBuffer();

            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        // Normal FINE step: move fracMoveTorq only.
        double fmtStep         = clamp(rawFmtPred, -trustFmt, trustFmt);
        double candidateLimits = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);
        double newFmt          = clampToBrackets(candidateLimits, fmtHasLo, fmtLoBound, fmtHasHi, fmtHiBound);

        // If sensitivity confirms direction and brackets ate >98% of the desired step,
        // the obstructing bracket is stale (recorded under a different fracR). Clear once and retry.
        if (fmtHasSens && Math.abs(fmtStep) > 1e-12) {
            double preClearAct = newFmt - fracMoveTorq;
            if (Math.abs(preClearAct) < OBSTRUCTION_FRAC * Math.abs(fmtStep)
                    && Math.abs(candidateLimits - newFmt) > 1e-9) {
                if (fmtStep < 0) {
                    System.out.printf(
                        "[AUTOTUNE] FINE bracket-clear: fmtLoBound (%.4f) discarded as obstruction."
                        + " prev_step would have been act=%+.6f; recomputed newFmt=",
                        fmtLoBound, preClearAct);
                    fmtHasLo = false;
                } else {
                    System.out.printf(
                        "[AUTOTUNE] FINE bracket-clear: fmtHiBound (%.4f) discarded as obstruction."
                        + " prev_step would have been act=%+.6f; recomputed newFmt=",
                        fmtHiBound, preClearAct);
                    fmtHasHi = false;
                }
                newFmt = clampToBrackets(candidateLimits, fmtHasLo, fmtLoBound, fmtHasHi, fmtHiBound);
                System.out.printf("%.4f%n", newFmt);
            }
        }

        double aFmt = newFmt - fracMoveTorq;

        System.out.printf(
            "[AUTOTUNE:STEP#%d] FINE [%s]  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
            + "  fr=%.4f(frozen)"
            + "  fmt=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)%n",
            stepCount + 1, crossing ? "crossing" : "settled",
            framesSinceStep, smoothed, expected, error,
            fracR,
            fracMoveTorq, fmtHasSens ? String.format("%.4f", fmtSens) : "---", rawFmtPred, aFmt, trustFmt);

        smoothedAtStep     = smoothed;
        fmtAtStep          = fracMoveTorq;
        errorAtStep        = error;
        fracMoveTorq       = newFmt;
        stepCount++;
        firstStepInPhase   = false;
        framesSinceStep    = 0;
        lastActStepMag     = Math.abs(aFmt);
        lastErrorSign      = (error > 0);
        lastErrorSignValid = true;
        resetSlopeBuffer();

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── sensitivity updates ───────────────────────────────────────────────────

    private void updateCoarseSensitivities(double defl, double deltaFr, double deltaFmt) {
        if (!frHasSens || !fmtHasSens) return;
        double expFr  = Math.abs(deltaFr  * frSens);
        double expFmt = Math.abs(deltaFmt * fmtSens);
        double total  = expFr + expFmt;
        if (total < 1e-12) return;

        if (Math.abs(deltaFr) > 1e-12) {
            double raw = defl * (expFr / total) / deltaFr;
            if (raw > 0) { frSens = raw; }
            else System.out.printf(
                "[AUTOTUNE] bad-sign frSens=%.4f (defl=%+.6f Δfr=%+.4f) — keeping prior%n",
                raw, defl, deltaFr);
        }
        if (Math.abs(deltaFmt) > 1e-12) {
            double raw = defl * (expFmt / total) / deltaFmt;
            if (raw < 0) { fmtSens = raw; }
            else System.out.printf(
                "[AUTOTUNE] bad-sign fmtSens=%.4f (defl=%+.6f Δfmt=%+.4f) — keeping prior%n",
                raw, defl, deltaFmt);
        }
    }

    private void updateFineSensitivity(double defl, double deltaFmt) {
        if (Math.abs(deltaFmt) < 1e-12) return;
        double raw = defl / deltaFmt;
        if (raw < 0) { fmtSens = raw; fmtHasSens = true; }
        else System.out.printf(
            "[AUTOTUNE] FINE bad-sign fmtSens=%.4f (defl=%+.6f Δfmt=%+.4f) — %s%n",
            raw, defl, deltaFmt, fmtHasSens ? "keeping prior" : "using probe next");
    }

    // ── trust region updates ──────────────────────────────────────────────────

    private void updateCoarseTrust(double defl, double deltaFr, double deltaFmt, double errorAfter) {
        if (!frHasSens && !fmtHasSens) return;
        boolean signFlipped = signFlipped(errorAtStep, errorAfter);

        double predFrAbs  = frHasSens  ? Math.abs(deltaFr  * frSens)  : 0.0;
        double predFmtAbs = fmtHasSens ? Math.abs(deltaFmt * fmtSens) : 0.0;
        double total      = predFrAbs + predFmtAbs;
        if (total < 1e-12) return;

        if (frHasSens && Math.abs(deltaFr) > 1e-12) {
            trustFr = updateTrustRadius(trustFr,
                defl * (predFrAbs / total), deltaFr * frSens, signFlipped, "fracR");
        }
        if (fmtHasSens && Math.abs(deltaFmt) > 1e-12) {
            trustFmt = updateTrustRadius(trustFmt,
                defl * (predFmtAbs / total), deltaFmt * fmtSens, signFlipped, "fracMoveTorq");
        }
    }

    private void updateFineTrust(double defl, double deltaFmt, double errorAfter) {
        if (!fmtHasSens || Math.abs(deltaFmt) < 1e-12) return;
        trustFmt = updateTrustRadius(trustFmt,
            defl, deltaFmt * fmtSens, signFlipped(errorAtStep, errorAfter), "fracMoveTorq");
    }

    private static boolean signFlipped(double eBefore, double eAfter) {
        return Math.abs(eBefore) > 1e-12 && Math.abs(eAfter) > 1e-12
            && (eBefore > 0) != (eAfter > 0);
    }

    private double updateTrustRadius(double trust, double actual, double predicted,
                                     boolean signFlipped, String name) {
        if (signFlipped) {
            double nt = Math.max(trust * TRUST_SHRINK, MIN_TRUST);
            System.out.printf("[AUTOTUNE] trust %s sign-flip  %.4f→%.4f%n", name, trust, nt);
            return nt;
        }
        double ratio = Math.abs(predicted) > 1e-12 ? actual / predicted : 0.0;
        if (ratio >= PREDICTED_FRACTION) {
            double nt = Math.min(trust * TRUST_GROW, MAX_TRUST);
            if (nt > trust + 1e-9)
                System.out.printf("[AUTOTUNE] trust %s grow  %.4f→%.4f%n", name, trust, nt);
            return nt;
        }
        if (ratio < 0.25) {
            double nt = Math.max(trust * TRUST_SHRINK, MIN_TRUST);
            System.out.printf("[AUTOTUNE] trust %s weak  %.4f→%.4f%n", name, trust, nt);
            return nt;
        }
        return trust;
    }

    // ── bracket updates ───────────────────────────────────────────────────────

    // fracR: larger = softer; too soft (err>0) → upper bound; too stiff → lower bound
    private void updateFrBrackets(double error) {
        if (error > 0 && (!frHasHi || fracR < frHiBound)) { frHiBound = fracR; frHasHi = true; }
        if (error < 0 && (!frHasLo || fracR > frLoBound)) { frLoBound = fracR; frHasLo = true; }
    }

    // fracMoveTorq: larger = stiffer; too soft (err>0) → lower bound; too stiff → upper bound
    private void updateFmtBrackets(double error) {
        if (error > 0 && (!fmtHasLo || fracMoveTorq > fmtLoBound)) { fmtLoBound = fracMoveTorq; fmtHasLo = true; }
        if (error < 0 && (!fmtHasHi || fracMoveTorq < fmtHiBound)) { fmtHiBound = fracMoveTorq; fmtHasHi = true; }
    }

    // ── saturation rescue (stiff-end or soft-end) ────────────────────────────

    private ParamTriple saturationRescue() {
        if (fracMove <= FRAC_MOVE_MIN + 1e-9) {
            phase = Phase.FAILED;
            System.out.println("[AUTOTUNE] FAILED: parameter saturation and fracMove at minimum");
            return null;
        }
        double oldFm = fracMove;
        fracMove = Math.max(fracMove - FRAC_MOVE_STEP, FRAC_MOVE_MIN);

        frHasSens  = false; frHasLo  = false; frHasHi  = false;
        fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
        fineReadyCount   = 0;
        firstStepInPhase = true;
        trustFr  = INIT_TRUST;
        trustFmt = INIT_TRUST;
        framesSinceStep  = 0;
        resetSlopeBuffer();

        phase           = Phase.PROBE_FMT;
        probeStepped    = false;
        probeStep       = INIT_PROBE_STEP;
        probeRetryCount = 0;

        System.out.printf(
            "[AUTOTUNE] saturation rescue: fracMove %.4f→%.4f  trust reset  → PROBE_FMT%n",
            oldFm, fracMove);
        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── convergence ───────────────────────────────────────────────────────────

    private void checkConvergence(double error) {
        if (phase == Phase.PROBE_FMT || phase == Phase.PROBE_FR) return;
        // Converge on actual step magnitude: when the last actual parameter change was
        // smaller than PRED_STEP_TOL, the controller has effectively stopped moving.
        if (Double.isNaN(lastActStepMag) || lastActStepMag >= PRED_STEP_TOL) {
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

    private static double clampToBrackets(double v,
            boolean hasLo, double lo, boolean hasHi, double hi) {
        if (hasLo && v < lo) v = lo;
        if (hasHi && v > hi) v = hi;
        if (hasLo && hasHi && lo > hi) v = (lo + hi) / 2.0;
        return v;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
