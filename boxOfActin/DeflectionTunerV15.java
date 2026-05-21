package boxOfActin;

/**
 * DeflectionTunerV15 — unified (v,a)-aware controller.
 *
 * Adds quadratic regression on a W_A-frame rolling buffer of smoothed deflection
 * to produce velocity (v) and acceleration (a) signals each output frame.
 *
 *   τ_est  = -v/a          (valid when sign-opposed and above noise floors)
 *   τ_stable               (relative std-dev of last W_TAU valid τ_est < TAU_STABLE_FRAC)
 *   d_∞    = s + v·τ_use   (τ_use = τ_est_mean when stable, else τ_theo)
 *
 * Four triggers replace the v14 settling+crossing criterion for COARSE/FINE:
 *   T1 Convergence:     τ_stable AND |d∞-target|<CONV_TOL AND |a|<A_SETTLED_FRAC·A_NOISE (×2 frames)
 *   T2 Confirmed off:   τ_stable AND |d∞-target|>CONV_TOL → step using d∞-based error
 *   T3 Crossing:        smoothed sign flipped vs previous frame
 *   T4 Hard timeout:    watchFrames >= wTimeout
 *
 * All triggers are gated by W_POST=4 frames after the most recent parameter change.
 * PROBE phases retain the v14 cold-start gate and settling logic unchanged.
 * Rescue gates read d_∞-based error instead of smoothed error.
 * Sensitivity attribution uses Δd_∞/Δparam at the first τ_stable frame after a step;
 * falls back to smoothed-based attribution on timeout.
 *
 * ALL v15-specific constants are PLACEHOLDERS — calibrate against trace data before
 * trusting convergence timing.
 */
class DeflectionTunerV15 {

    enum Phase { PROBE_FMT, PROBE_FR, COARSE, FINE, CONVERGED, FAILED, NOISE_PROBE }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) { fracMove=fm; fracR=fr; fracMoveTorq=fmt; }
    }

    // ── constants shared with v14 (values unchanged) ───────────────────────────
    static final double EMA_ALPHA          = 0.05;
    static final double SETTLE_TOL_FRAC    = 0.01;   // probe settling only
    static final double SLOPE_TAU_FRAC     = 2.0;
    static final double SECONDS_PER_FRAME  = 0.01;   // toFileInterval=100 × deltaT=1e-4
    static final int    MIN_SLOPE_WIN      = 10;
    static final double INIT_PROBE_STEP    = 0.05;
    static final double MAX_PROBE_REL      = 0.5;    // probe step ≤ 50% of current param value
    static final double MIN_PROBE_STEP_ABS = 1e-3;   // probe step absolute floor
    static final double INIT_TRUST         = 0.1;
    static final double MIN_TRUST          = 0.005;
    static final double MAX_TRUST          = 0.3;
    static final double TRUST_GROW         = 1.5;
    static final double TRUST_SHRINK       = 0.5;
    static final double PREDICTED_FRACTION = 0.5;
    static final double PRED_STEP_TOL      = 0.001;
    static final double CONV_TOL_UM        = 1e-5;   // 0.01 nm = 10 pm (empirical noise floor)
    static final double FR_FINE_THRESH     = 0.02;
    static final double ALPHA_DIST         = 0.5;
    static final double FRAC_MOVE_STEP     = 0.05;
    static final int    MAX_PROBE_RETRIES  = 3;
    static final double OBSTRUCTION_FRAC   = 0.02;
    static final double FRAC_MOVE_MIN = 0.1, FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1, FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX  = 0.5;

    // ── v15-specific constants (PLACEHOLDER — calibrate against trace data) ────
    // W_A: regression window. Rule of thumb: W_A·dtFrame ≈ 0.2·τ_theo.
    static final int    W_A             = 12;    // PLACEHOLDER
    // W_TAU: stability window for τ_est ring buffer.
    static final int    W_TAU           = 6;     // PLACEHOLDER
    // W_POST: post-step quiet frames before any trigger can fire.
    static final int    W_POST          = 4;     // PLACEHOLDER
    // TAU_STABLE_FRAC: max relative std-dev of τ_est buffer for stability.
    static final double TAU_STABLE_FRAC = 0.15;  // PLACEHOLDER
    // A_SETTLED_FRAC: |a| < A_SETTLED_FRAC·A_NOISE confirms chain at rest (T1).
    static final double A_SETTLED_FRAC  = 0.5;   // PLACEHOLDER
    // CONV_FRAMES_V15: consecutive T1 frames required to declare CONVERGED.
    static final int    CONV_FRAMES_V15 = 2;     // PLACEHOLDER: cheap single-frame noise guard
    // RESCUE_ERR_FRAC: applied to d_∞-based error (same value as v14, different signal).
    static final double RESCUE_ERR_FRAC = 0.05;  // PLACEHOLDER

    // V_NOISE = 1e-5/dtFrame (µm/s); A_NOISE = V_NOISE/τ_theo (µm/s²); computed in start().
    // W_timeout = round(4·τ_theo/dtFrame); computed in start().

    // ── run-time scalars ───────────────────────────────────────────────────────
    private double fracMove, fracR, fracMoveTorq;
    private double expected;
    private double tauTheo;   // theoretical relaxation time (s)
    private double dtFrame;   // output-frame period (s)
    private double vNoise;    // µm/s  — velocity noise floor   (PLACEHOLDER)
    private double aNoise;    // µm/s² — acceleration noise floor (PLACEHOLDER)
    private int    wTimeout;  // frames — hard-timeout window    (PLACEHOLDER)

    // ── EMA ───────────────────────────────────────────────────────────────────
    private double  smoothed;
    private boolean smoothedInit;

    // ── probe-phase settling buffer (v14 slope buffer, probe phases only) ──────
    private int      slopeWin;
    private double[] slopeBuf;
    private int      slopeHead, slopeCount;
    private int      minFramesPerStep, maxFramesPerStep;

    // ── quadratic-regression ring buffer (W_A frames of smoothed values) ──────
    private final double[] vaBuf = new double[W_A];
    private int vaHead;    // next write slot; oldest = vaBuf[vaHead] when vaCount==W_A
    private int vaCount;

    // ── τ_est stability ring buffer (last W_TAU valid τ_est values) ───────────
    private final double[] tauEstBuf = new double[W_TAU];
    private int tauEstHead;
    private int tauEstCount;

    // ── watch-state (COARSE / FINE) ───────────────────────────────────────────
    private int     watchFrames;        // frames since last COARSE/FINE step
    private int     framesSinceStep;    // frames since last PROBE step
    private double  prevSmoothed;       // smoothed at previous frame (crossing detection)
    private boolean prevSmoothedValid;
    private int     convCountV15;       // consecutive T1 frames

    // ── sensitivity attribution ────────────────────────────────────────────────
    private double  dInfAtStep;            // d_∞ when step was taken (NaN if unavailable)
    private boolean sensitivityAttribDone; // true once attributed after current step

    // ── step baselines ────────────────────────────────────────────────────────
    private double smoothedAtStep;
    private double frAtStep, fmtAtStep;
    private double errorAtStep;

    // ── trust regions ─────────────────────────────────────────────────────────
    private double trustFr, trustFmt;

    // ── fracR controller ──────────────────────────────────────────────────────
    private double  frSens;
    private boolean frHasSens;
    private double  frLoBound;
    private boolean frHasLo;
    private double  frHiBound;
    private boolean frHasHi;
    private double  frPredStep;

    // ── fracMoveTorq controller ───────────────────────────────────────────────
    private double  fmtSens;
    private boolean fmtHasSens;
    private double  fmtLoBound;
    private boolean fmtHasLo;
    private double  fmtHiBound;
    private boolean fmtHasHi;

    // ── phase bookkeeping ─────────────────────────────────────────────────────
    private Phase   phase;
    private boolean firstStepInPhase;
    private int     fineReadyCount;
    private double  lastActStepMag;

    // ── probe sub-state ───────────────────────────────────────────────────────
    private boolean probeStepped;
    private double  probeStep;
    private double  probeStepDir;   // direction sign (+1/-1) of current probe; 0 = not yet set
    private int     probeRetryCount;
    private boolean firstProbeFired;

    // ── crossing detection (probe phases) ─────────────────────────────────────
    private boolean lastErrorSign;
    private boolean lastErrorSignValid;

    // ── logging ───────────────────────────────────────────────────────────────
    private int stepCount;

    // ── noise probe (post-convergence tail capture, enabled by -bmNoiseProbe) ─
    static final int NOISE_PROBE_FRAMES   = 1000;
    private boolean noiseProbePending     = false;
    private int     noiseProbeCount;
    private java.io.PrintWriter noiseProbePw;

    // ── public API ────────────────────────────────────────────────────────────

    /** Call before start() to capture 1000 tail frames of v/a after convergence. */
    void enableNoiseProbe() { noiseProbePending = true; }

    void start(double fm, double fr, double fmt, double expectedMicrons, double tauSeconds) {
        fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        expected = expectedMicrons;
        tauTheo  = (tauSeconds > 0 && !Double.isNaN(tauSeconds)) ? tauSeconds : 0.1;
        dtFrame  = SECONDS_PER_FRAME;

        // Physics-scaled probe settling (same formula as v14)
        if (tauSeconds > 0 && !Double.isNaN(tauSeconds)) {
            slopeWin         = (int) Math.max(MIN_SLOPE_WIN,
                                   Math.round(SLOPE_TAU_FRAC * tauSeconds / dtFrame));
            minFramesPerStep = (int) Math.max(5,
                                   Math.round(0.5 * tauSeconds / dtFrame));
            maxFramesPerStep = (int) Math.max(200,
                                   Math.round(20.0 * tauSeconds / dtFrame));
        } else {
            slopeWin = MIN_SLOPE_WIN; minFramesPerStep = 5; maxFramesPerStep = 200;
        }
        slopeBuf = new double[slopeWin];

        // v15 noise floors — calibrated from 32-monomer near-equilibrium FINE tail
        // (200-frame stuck-run tail, 2026-05-20): v std=2.55e-5 → 3σ=7.65e-5; a std=4.15e-4 → 3σ=1.25e-3
        vNoise   = 8e-5;    // µm/s   — 3σ of v at equilibrium
        aNoise   = 1.3e-3;  // µm/s²  — 3σ of a at equilibrium
        wTimeout = (int) Math.max(50, Math.round(4.0 * tauTheo / dtFrame));

        // reset all state
        phase = Phase.PROBE_FMT;
        smoothed = 0; smoothedInit = false;
        slopeHead = 0; slopeCount = 0;
        vaHead = 0; vaCount = 0;
        tauEstHead = 0; tauEstCount = 0;
        watchFrames = 0; framesSinceStep = 0;
        prevSmoothed = 0; prevSmoothedValid = false;
        convCountV15 = 0;
        dInfAtStep = Double.NaN; sensitivityAttribDone = false;
        smoothedAtStep = 0; frAtStep = fr; fmtAtStep = fmt; errorAtStep = 0;
        trustFr = INIT_TRUST; trustFmt = INIT_TRUST;
        frSens = 0; frHasSens = false; frHasLo = false; frHasHi = false;
        frPredStep = Double.NaN;
        fmtSens = 0; fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
        firstStepInPhase = true; fineReadyCount = 0; lastActStepMag = Double.NaN;
        probeStepped = false; probeStep = INIT_PROBE_STEP; probeStepDir = 0;
        probeRetryCount = 0; firstProbeFired = false;
        lastErrorSign = false; lastErrorSignValid = false;
        stepCount = 0;

        System.out.printf("[V15] armed: τ=%.3fs  dtFrame=%.4fs  vNoise=%.3e  aNoise=%.3e"
            + "  wTimeout=%d  W_A=%d  slope_win=%d%n",
            tauTheo, dtFrame, vNoise, aNoise, wTimeout, W_A, slopeWin);
    }

    /**
     * Feed one output-frame smoothed deflection sample (µm; force must be ON).
     * Returns null most frames. Returns a ParamTriple when a parameter change is needed.
     * After isDone(), always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        // ── NOISE_PROBE: 1000-frame tail capture after convergence ────────────
        if (phase == Phase.NOISE_PROBE) {
            smoothed = EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed;
            pushVA(smoothed);
            double[] vap = (vaCount >= W_A) ? computeVA() : null;
            double vp = vap != null ? vap[0] : Double.NaN;
            double ap = vap != null ? vap[1] : Double.NaN;
            noiseProbeCount++;
            if (noiseProbePw != null && !Double.isNaN(vp) && !Double.isNaN(ap)) {
                noiseProbePw.printf("%d,%.8f,%.6e,%.6e%n", noiseProbeCount, smoothed, vp, ap);
            }
            if (noiseProbeCount >= NOISE_PROBE_FRAMES) {
                if (noiseProbePw != null) {
                    noiseProbePw.flush(); noiseProbePw.close(); noiseProbePw = null;
                }
                System.out.printf("[V15] NOISE_PROBE done: %d frames logged to /tmp/v15_noise_probe.csv%n",
                    noiseProbeCount);
                phase = Phase.CONVERGED;
            }
            return null;
        }

        // EMA (unchanged from v14)
        smoothed = smoothedInit
            ? EMA_ALPHA * observedMicrons + (1.0 - EMA_ALPHA) * smoothed
            : observedMicrons;
        smoothedInit = true;

        // Push to quadratic regression buffer (always, even in probe phases)
        pushVA(smoothed);

        // Compute (v, a) via quadratic fit; null if buffer not yet full
        double[] va = (vaCount >= W_A) ? computeVA() : null;
        double   v  = va != null ? va[0] : Double.NaN;
        double   a  = va != null ? va[1] : Double.NaN;

        // τ_est: valid when |v|>vNoise, |a|>aNoise, opposite signs, positive result, sanity cap
        double tauEst = Double.NaN;
        if (!Double.isNaN(v) && !Double.isNaN(a)
                && Math.abs(v) > vNoise && Math.abs(a) > aNoise
                && (v > 0) != (a > 0)) {
            double cand = -v / a;
            if (cand > 0 && cand < 100.0) {
                tauEst = cand;
                pushTauEst(tauEst);
            }
        }

        boolean tauStable = isTauStable();
        double  tauMean   = tauEstMean();
        double  tauUse    = (tauStable && !Double.isNaN(tauMean)) ? tauMean : tauTheo;
        double  dInf      = !Double.isNaN(v) ? smoothed + v * tauUse : Double.NaN;
        // dInfErr: prediction error when d_∞ is available; falls back to smoothed error
        double  dInfErr   = !Double.isNaN(dInf) ? (dInf - expected) : (smoothed - expected);

        // ── PROBE phases: v14 settling + cold-start gate (unchanged logic) ────
        if (phase == Phase.PROBE_FMT || phase == Phase.PROBE_FR) {
            pushSlope(smoothed);
            framesSinceStep++;
            double error     = smoothed - expected;
            boolean early    = !firstProbeFired && phase == Phase.PROBE_FMT && smoothed > expected;
            boolean crossing = lastErrorSignValid && framesSinceStep >= minFramesPerStep
                               && ((error > 0) != lastErrorSign);
            boolean force    = framesSinceStep >= maxFramesPerStep;
            if (!isSettled() && !force && !early && !crossing) return null;
            return phase == Phase.PROBE_FMT ? handleProbeFmt(error) : handleProbeFr(error);
        }

        // ── COARSE / FINE: v15 trigger logic ──────────────────────────────────
        watchFrames++;
        double error = smoothed - expected;

        // Sensitivity attribution at first τ_stable frame after a step (d_∞-based)
        if (!sensitivityAttribDone && tauStable && !Double.isNaN(dInf)) {
            attributeSensitivity(dInf);
            sensitivityAttribDone = true;
        }

        // Per-frame watch log (key diagnostic: verify physics before trusting triggers)
        System.out.printf("[V15:WATCH] %s  wf=%d  s=%.6f  v=%.3e  a=%.3e"
            + "  τe=%s  stable=%b  d∞=%s  d∞err=%+.6f%n",
            phase, watchFrames, smoothed,
            v, a,
            Double.isNaN(tauEst) ? "---" : String.format("%.4f", tauEst),
            tauStable,
            Double.isNaN(dInf)   ? "---" : String.format("%.6f", dInf),
            dInfErr);

        // W_post gate: suppress all triggers immediately after a parameter change
        if (watchFrames < W_POST) {
            prevSmoothed = smoothed; prevSmoothedValid = true;
            return null;
        }

        // ── Trigger 1: Convergence (d_∞ on-target AND acceleration settled) ────
        if (tauStable && !Double.isNaN(dInf)
                && Math.abs(dInfErr) < CONV_TOL_UM
                && !Double.isNaN(a) && Math.abs(a) < A_SETTLED_FRAC * aNoise) {
            convCountV15++;
            if (convCountV15 >= CONV_FRAMES_V15) {
                if (noiseProbePending) {
                    phase = Phase.NOISE_PROBE;
                    noiseProbeCount = 0;
                    try {
                        noiseProbePw = new java.io.PrintWriter(
                            new java.io.FileWriter("/tmp/v15_noise_probe.csv"));
                        noiseProbePw.println("frame,s,v,a");
                    } catch (java.io.IOException e) {
                        System.err.println("[V15] NOISE_PROBE: cannot open CSV: " + e);
                        phase = Phase.CONVERGED;
                    }
                    System.out.printf("[V15] T1:CONVERGED → NOISE_PROBE (1000 frames)"
                        + "  d∞=%.6fµm  target=%.6fµm  steps=%d%n",
                        dInf, expected, stepCount);
                } else {
                    phase = Phase.CONVERGED;
                    System.out.printf("[V15] T1:CONVERGED  d∞=%.6fµm  target=%.6fµm"
                        + "  v=%.3e  a=%.3e  τ_est=%.4f  steps=%d%n",
                        dInf, expected, v, a, tauEst, stepCount);
                }
            } else {
                System.out.printf("[V15] T1:conv-cand %d/%d  d∞err=%+.6f  |a|=%.3e < %.3e%n",
                    convCountV15, CONV_FRAMES_V15, dInfErr, Math.abs(a), A_SETTLED_FRAC * aNoise);
            }
            prevSmoothed = smoothed; prevSmoothedValid = true;
            return null;
        }
        convCountV15 = 0;
        if (isDone()) return null;

        // ── Trigger 2: Confirmed off-target (τ_stable, d_∞ known, needs a step) ─
        if (tauStable && !Double.isNaN(dInf) && Math.abs(dInfErr) > CONV_TOL_UM) {
            System.out.printf("[V15] T2:confirmed-off  d∞=%.6f  d∞err=%+.6f%n", dInf, dInfErr);
            stageDInf(dInf);
            ParamTriple pt = dispatchStep(error, dInfErr, "T2:confirmed", false);
            prevSmoothed = smoothed; prevSmoothedValid = true;
            return pt;
        }

        // ── Trigger 3: Smoothed crossed target ────────────────────────────────
        if (prevSmoothedValid && watchFrames >= minFramesPerStep
                && (error > 0) != ((prevSmoothed - expected) > 0)) {
            System.out.printf("[V15] T3:crossing  s=%.6f  prev=%.6f  d∞=%s%n",
                smoothed, prevSmoothed,
                Double.isNaN(dInf) ? "---" : String.format("%.6f", dInf));
            stageDInf(dInf);
            ParamTriple pt = dispatchStep(error, dInfErr, "T3:crossing", false);
            prevSmoothed = smoothed; prevSmoothedValid = true;
            return pt;
        }

        // ── Trigger 4: Hard timeout ────────────────────────────────────────────
        if (watchFrames >= wTimeout) {
            // If noise probe is pending and the chain is near-target, start probe
            // instead of taking another step. Threshold: 10×CONV_TOL_UM (100 pm).
            if (noiseProbePending && Math.abs(error) < 10.0 * CONV_TOL_UM) {
                phase = Phase.NOISE_PROBE;
                noiseProbeCount = 0;
                try {
                    noiseProbePw = new java.io.PrintWriter(
                        new java.io.FileWriter("/tmp/v15_noise_probe.csv"));
                    noiseProbePw.println("frame,s,v,a");
                } catch (java.io.IOException e) {
                    System.err.println("[V15] NOISE_PROBE: cannot open CSV: " + e);
                    phase = Phase.CONVERGED;
                }
                System.out.printf("[V15] T4:near-target → NOISE_PROBE (1000 frames)"
                    + "  s=%.6fµm  err=%+.6f%n", smoothed, error);
                prevSmoothed = smoothed; prevSmoothedValid = true;
                return null;
            }
            if (!sensitivityAttribDone) {
                // τ_stable never fired — fall back to smoothed-based attribution
                attributeSensitivitySmoothed();
                sensitivityAttribDone = true;
            }
            System.out.printf("[V15] T4:timeout  wf=%d  s=%.6f  err=%+.6f%n",
                watchFrames, smoothed, error);
            stageDInf(dInf);
            ParamTriple pt = dispatchStep(error, dInfErr, "T4:timeout", true);
            prevSmoothed = smoothed; prevSmoothedValid = true;
            return pt;
        }

        prevSmoothed = smoothed; prevSmoothedValid = true;
        return null;
    }

    Phase   getPhase()        { return phase; }
    boolean isDone()          { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()     { return fracMove; }
    double  getFracR()        { return fracR; }
    double  getFracMoveTorq() { return fracMoveTorq; }
    double  getSmoothed()     { return smoothed; }

    String resultSummary() {
        return String.format("[V15] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f",
            phase, fracMove, fracR, fracMoveTorq);
    }

    // ── PROBE phase: fracMoveTorq (copied from v14, dInfAtStep initialised) ────

    private ParamTriple handleProbeFmt(double error) {
        if (!probeStepped) {
            // Saturation rescue before taking any step
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

            updateFmtBrackets(error);
            if (probeStepDir == 0) probeStepDir = (error >= 0) ? +1.0 : -1.0;
            double probeMag = Math.min(probeStep,
                                  Math.max(MIN_PROBE_STEP_ABS, MAX_PROBE_REL * Math.abs(fracMoveTorq)));
            double newFmt = clamp(fracMoveTorq + probeStepDir * probeMag, FRAC_MT_MIN, FRAC_MT_MAX);
            double step   = newFmt - fracMoveTorq;

            smoothedAtStep = smoothed; fmtAtStep = fracMoveTorq; errorAtStep = error;
            dInfAtStep = Double.NaN; sensitivityAttribDone = false;
            fracMoveTorq   = newFmt;
            probeStepped   = true; firstProbeFired = true;
            stepCount++; framesSinceStep = 0; watchFrames = 0;
            lastActStepMag = Math.abs(step);
            lastErrorSign = (error > 0); lastErrorSignValid = true;
            resetSlopeBuffer(); clearTauEstBuf();

            System.out.printf(
                "[V15:STEP#%d] PROBE_FMT take  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fmt: %.4f→%.4f (step=%+.4f)  retry=%d%n",
                stepCount, framesSinceStep, smoothed, expected, error,
                fmtAtStep, fracMoveTorq, step, probeRetryCount);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);

        } else {
            // Evaluate probe sensitivity (smoothed-based, same as v14)
            double trend   = smoothed - smoothedAtStep;
            double step    = fracMoveTorq - fmtAtStep;
            double rawSens = Math.abs(step) > 1e-12 ? trend / step : 0.0;
            boolean signOk = rawSens < 0;

            if (Math.abs(step) < 1e-12) {
                System.out.printf("[V15] PROBE_FMT step clamped to zero — advancing%n");
                signOk = true;
            } else if (!signOk) {
                System.out.printf(
                    "[V15] PROBE_FMT bad-sign: rawSens=%.4f (trend=%+.6f step=%+.4f) retry=%d → reversing direction%n",
                    rawSens, trend, step, probeRetryCount);
                if (probeRetryCount >= MAX_PROBE_RETRIES) {
                    phase = Phase.FAILED;
                    System.out.println("[V15] FAILED: PROBE_FMT exhausted retries");
                    return null;
                }
                probeRetryCount++;
                probeStepDir = -probeStepDir;
                probeStep  /= 2.0;
                probeStepped = false;
                return handleProbeFmt(error);
            }

            if (signOk && Math.abs(step) > 1e-12) { fmtSens = rawSens; fmtHasSens = true; }
            updateFmtBrackets(error);

            System.out.printf(
                "[V15] PROBE_FMT eval: trend=%+.6f  step=%+.4f  fmtSens=%.4f  → PROBE_FR%n",
                trend, step, fmtSens);

            phase = Phase.PROBE_FR;
            probeStepped = false; probeStep = INIT_PROBE_STEP; probeRetryCount = 0; probeStepDir = 0;
            return handleProbeFr(error);
        }
    }

    // ── PROBE phase: fracR (copied from v14, dInfAtStep initialised) ──────────

    private ParamTriple handleProbeFr(double error) {
        if (!probeStepped) {
            updateFrBrackets(error);
            if (probeStepDir == 0) probeStepDir = (error >= 0) ? -1.0 : +1.0;
            double probeMag = Math.min(probeStep,
                                  Math.max(MIN_PROBE_STEP_ABS, MAX_PROBE_REL * Math.abs(fracR)));
            double newFr = clamp(fracR + probeStepDir * probeMag, FRAC_R_MIN, FRAC_R_MAX);
            double step  = newFr - fracR;

            smoothedAtStep = smoothed; frAtStep = fracR; errorAtStep = error;
            dInfAtStep = Double.NaN; sensitivityAttribDone = false;
            fracR          = newFr;
            probeStepped   = true;
            stepCount++; framesSinceStep = 0; watchFrames = 0;
            lastActStepMag = Math.abs(step);
            lastErrorSign = (error > 0); lastErrorSignValid = true;
            resetSlopeBuffer(); clearTauEstBuf();

            System.out.printf(
                "[V15:STEP#%d] PROBE_FR take  frames=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fr: %.4f→%.4f (step=%+.4f)  retry=%d%n",
                stepCount, framesSinceStep, smoothed, expected, error,
                frAtStep, fracR, step, probeRetryCount);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);

        } else {
            double trend   = smoothed - smoothedAtStep;
            double step    = fracR - frAtStep;
            double rawSens = Math.abs(step) > 1e-12 ? trend / step : 0.0;
            boolean signOk = rawSens > 0;

            if (Math.abs(step) < 1e-12) {
                System.out.printf("[V15] PROBE_FR step clamped to zero — advancing%n");
                signOk = true;
            } else if (!signOk) {
                System.out.printf(
                    "[V15] PROBE_FR bad-sign: rawSens=%.4f (trend=%+.6f step=%+.4f) retry=%d → reversing direction%n",
                    rawSens, trend, step, probeRetryCount);
                if (probeRetryCount >= MAX_PROBE_RETRIES) {
                    phase = Phase.FAILED;
                    System.out.println("[V15] FAILED: PROBE_FR exhausted retries");
                    return null;
                }
                probeRetryCount++;
                probeStepDir = -probeStepDir;
                probeStep  /= 2.0;
                probeStepped = false;
                return handleProbeFr(error);
            }

            if (signOk && Math.abs(step) > 1e-12) { frSens = rawSens; frHasSens = true; }
            updateFrBrackets(error);

            System.out.printf(
                "[V15] PROBE_FR eval: trend=%+.6f  step=%+.4f  frSens=%.4f  → COARSE%n",
                trend, step, frSens);

            phase = Phase.COARSE;
            firstStepInPhase = true; fineReadyCount = 0;
            watchFrames = 0; prevSmoothedValid = false;
            return null;
        }
    }

    // ── dispatch to COARSE or FINE handler based on current phase ─────────────

    private ParamTriple dispatchStep(double error, double dInfErr,
                                     String trigger, boolean timeout) {
        return phase == Phase.COARSE
            ? handleCoarseStep(error, dInfErr, trigger, timeout)
            : handleFineStep(error, dInfErr, trigger, timeout);
    }

    // ── COARSE step (v15: rescue and prediction use dInfErr) ──────────────────

    private ParamTriple handleCoarseStep(double error, double dInfErr,
                                          String trigger, boolean timeout) {
        // Rescue gates read d_∞-based error
        if (dInfErr > RESCUE_ERR_FRAC * expected
                && fracR <= FRAC_R_MIN + 1e-9 && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
            return saturationRescue();
        }
        if (dInfErr < -RESCUE_ERR_FRAC * expected
                && fracR >= FRAC_R_MAX - 1e-9 && fracMoveTorq <= FRAC_MT_MIN + 1e-9) {
            return saturationRescue();
        }

        updateFrBrackets(error);
        updateFmtBrackets(error);

        // Step prediction: use dInfErr (which is already d_∞-based for T2,
        // smoothed-based fallback for T3/T4 when d_∞ unavailable)
        double rawFrPred  = frHasSens  ? ALPHA_DIST         * (-dInfErr) / frSens  : 0.0;
        double rawFmtPred = fmtHasSens ? (1.0 - ALPHA_DIST) * (-dInfErr) / fmtSens : 0.0;

        frPredStep = Math.abs(rawFrPred);

        double frStep = clamp(rawFrPred,  -trustFr,  trustFr);
        double newFr  = clamp(fracR + frStep, FRAC_R_MIN, FRAC_R_MAX);
        newFr  = clampToBrackets(newFr,  frHasLo,  frLoBound,  frHasHi,  frHiBound);
        double aFr = newFr - fracR;

        double fmtStep = clamp(rawFmtPred, -trustFmt, trustFmt);
        double newFmt  = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);
        newFmt = clampToBrackets(newFmt, fmtHasLo, fmtLoBound, fmtHasHi, fmtHiBound);
        double aFmt = newFmt - fracMoveTorq;

        // Trust update from previous step (not first step in phase)
        if (!firstStepInPhase) {
            double defl    = smoothed - smoothedAtStep;
            double deltaFr  = fracR - frAtStep;     // will be updated below
            double deltaFmt = fracMoveTorq - fmtAtStep;
            updateCoarseTrust(defl, deltaFr, deltaFmt, error);
        }

        System.out.printf(
            "[V15:STEP#%d] COARSE [%s]  wf=%d  obs=%.6fµm  exp=%.6fµm  err=%+.6f  d∞err=%+.6f%n"
            + "  fr=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)"
            + "  fmt=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)%n",
            stepCount + 1, trigger,
            watchFrames, smoothed, expected, error, dInfErr,
            fracR,        frHasSens  ? String.format("%.4f", frSens)  : "---", rawFrPred,  aFr,  trustFr,
            fracMoveTorq, fmtHasSens ? String.format("%.4f", fmtSens) : "---", rawFmtPred, aFmt, trustFmt);

        // Record baselines before updating params
        smoothedAtStep = smoothed; frAtStep = fracR; fmtAtStep = fracMoveTorq;
        errorAtStep = error;
        // dInfAtStep: record current d_∞ for the next sensitivity attribution
        // (we can't access dInf here directly; it was computed in feed() above the dispatch call.
        // We approximate: if d_∞ was available it drove dInfErr, so we use dInfErr+expected.
        // If dInfErr == error (smoothed fallback), dInfAtStep = smoothed (which is fine;
        // attributeSensitivity will compare dInf_at_τ_stable vs dInfAtStep).
        // We set dInfAtStep = NaN here and let feed() set it each time it computes dInf.
        // The cleanest approach: pass dInf explicitly. We use a package-private field as a
        // one-frame staging variable set by feed() before dispatch.
        // Implementation note: dInfAtStep is set from the dInf local in feed() via the
        // _stagedDInf field pattern. Since Java doesn't allow closures over locals, we use
        // a dedicated helper field _stagedDInf that feed() populates before calling dispatchStep().
        dInfAtStep = _stagedDInf;
        sensitivityAttribDone = false;

        fracR = newFr; fracMoveTorq = newFmt;
        stepCount++; firstStepInPhase = false;
        watchFrames = 0; convCountV15 = 0;
        lastActStepMag     = Math.max(Math.abs(aFr), Math.abs(aFmt));
        lastErrorSign      = (error > 0); lastErrorSignValid = true;
        clearTauEstBuf();

        // COARSE→FINE: freeze fracR when frPredStep has been small for 2 consecutive steps
        if (frPredStep < FR_FINE_THRESH) {
            if (++fineReadyCount >= 2) {
                phase = Phase.FINE;
                firstStepInPhase = true; fineReadyCount = 0;
                fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
                System.out.printf("[V15] COARSE→FINE: fracR=%.4f frozen  fracMoveTorq=%.4f%n",
                    fracR, fracMoveTorq);
            }
        } else {
            fineReadyCount = 0;
        }

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── FINE step (v15: rescue and prediction use dInfErr) ────────────────────

    private ParamTriple handleFineStep(double error, double dInfErr,
                                        String trigger, boolean timeout) {
        // Rescue gates read d_∞-based error
        if (dInfErr > RESCUE_ERR_FRAC * expected && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
            return saturationRescue();
        }
        if (dInfErr < -RESCUE_ERR_FRAC * expected && fracMoveTorq <= FRAC_MT_MIN + 1e-9) {
            return saturationRescue();
        }

        // Trust update from previous step
        if (!firstStepInPhase) {
            double defl    = smoothed - smoothedAtStep;
            double deltaFmt = fracMoveTorq - fmtAtStep;
            updateFineTrust(defl, deltaFmt, error);
        }

        updateFmtBrackets(error);

        double rawFmtPred = fmtHasSens
            ? (-dInfErr) / fmtSens
            : ((dInfErr < 0) ? INIT_PROBE_STEP : -INIT_PROBE_STEP);

        // fr-rescue: fmt saturated at the limit the predictor wants to cross
        boolean fmtSaturatedAtLimit =
            (rawFmtPred > 0 && fracMoveTorq >= FRAC_MT_MAX - 1e-9) ||
            (rawFmtPred < 0 && fracMoveTorq <= FRAC_MT_MIN + 1e-9);

        if (fmtSaturatedAtLimit && Math.abs(dInfErr) > CONV_TOL_UM) {
            double rawFrPred    = frHasSens ? (-dInfErr) / frSens : 0.0;
            double frRescueStep = clamp(rawFrPred, -trustFr, trustFr);
            double newFr        = clamp(fracR + frRescueStep, FRAC_R_MIN, FRAC_R_MAX);
            newFr = clampToBrackets(newFr, frHasLo, frLoBound, frHasHi, frHiBound);
            double aFr = newFr - fracR;

            System.out.printf(
                "[V15:STEP#%d] FINE [fr-rescue/%s]  wf=%d  obs=%.6fµm  exp=%.6fµm"
                + "  err=%+.6f  d∞err=%+.6f"
                + "  fr=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)  fmt=%.4f(saturated)%n",
                stepCount + 1, trigger, watchFrames, smoothed, expected, error, dInfErr,
                fracR, frHasSens ? String.format("%.4f", frSens) : "---",
                rawFrPred, aFr, trustFr, fracMoveTorq);

            smoothedAtStep = smoothed; frAtStep = fracR; fmtAtStep = fracMoveTorq;
            errorAtStep = error; dInfAtStep = _stagedDInf; sensitivityAttribDone = false;
            fracR = newFr;
            fmtHasLo = false; fmtHasHi = false; // brackets stale after fracR shift
            stepCount++; firstStepInPhase = false;
            watchFrames = 0; convCountV15 = 0;
            lastActStepMag = Math.abs(aFr);
            lastErrorSign = (error > 0); lastErrorSignValid = true;
            clearTauEstBuf();
            return new ParamTriple(fracMove, fracR, fracMoveTorq);
        }

        // Normal FINE step: move fracMoveTorq only
        double fmtStep         = clamp(rawFmtPred, -trustFmt, trustFmt);
        double candidateLimits = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);

        // Limit-retreat: when fmt sits at a hardware limit and the predictor calls for
        // a step AWAY from it, the Lo/Hi bracket recorded at that limit is stale and
        // would otherwise clamp the step back to zero.  Clear it before clamping.
        if (fracMoveTorq >= FRAC_MT_MAX - 1e-9 && fmtStep < 0 && fmtHasLo) {
            System.out.printf("[V15] FINE limit-retreat: clearing fmtLoBound (%.4f)%n", fmtLoBound);
            fmtHasLo = false;
        }
        if (fracMoveTorq <= FRAC_MT_MIN + 1e-9 && fmtStep > 0 && fmtHasHi) {
            System.out.printf("[V15] FINE limit-retreat: clearing fmtHiBound (%.4f)%n", fmtHiBound);
            fmtHasHi = false;
        }

        double newFmt          = clampToBrackets(candidateLimits, fmtHasLo, fmtLoBound,
                                                 fmtHasHi, fmtHiBound);

        // Bracket obstruction detection (same as v14)
        if (fmtHasSens && Math.abs(fmtStep) > 1e-12) {
            double preClearAct = newFmt - fracMoveTorq;
            if (Math.abs(preClearAct) < OBSTRUCTION_FRAC * Math.abs(fmtStep)
                    && Math.abs(candidateLimits - newFmt) > 1e-9) {
                if (fmtStep < 0) {
                    System.out.printf(
                        "[V15] FINE bracket-clear: fmtLoBound (%.4f) discarded. newFmt=",
                        fmtLoBound);
                    fmtHasLo = false;
                } else {
                    System.out.printf(
                        "[V15] FINE bracket-clear: fmtHiBound (%.4f) discarded. newFmt=",
                        fmtHiBound);
                    fmtHasHi = false;
                }
                newFmt = clampToBrackets(candidateLimits, fmtHasLo, fmtLoBound,
                                         fmtHasHi, fmtHiBound);
                System.out.printf("%.4f%n", newFmt);
            }
        }

        double aFmt = newFmt - fracMoveTorq;

        System.out.printf(
            "[V15:STEP#%d] FINE [%s]  wf=%d  obs=%.6fµm  exp=%.6fµm"
            + "  err=%+.6f  d∞err=%+.6f"
            + "  fr=%.4f(frozen)  fmt=%.4f(s=%s pred=%+.4f act=%+.4f tru=%.4f)%n",
            stepCount + 1, trigger, watchFrames, smoothed, expected, error, dInfErr,
            fracR, fracMoveTorq,
            fmtHasSens ? String.format("%.4f", fmtSens) : "---", rawFmtPred, aFmt, trustFmt);

        smoothedAtStep = smoothed; fmtAtStep = fracMoveTorq;
        errorAtStep = error; dInfAtStep = _stagedDInf; sensitivityAttribDone = false;
        fracMoveTorq = newFmt;
        stepCount++; firstStepInPhase = false;
        watchFrames = 0; convCountV15 = 0;
        lastActStepMag = Math.abs(aFmt);
        lastErrorSign = (error > 0); lastErrorSignValid = true;
        clearTauEstBuf();

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // One-frame staging field: feed() sets this to the current dInf before calling
    // dispatchStep(), so handlers can record it as dInfAtStep without passing it
    // through multiple dispatch levels.
    private double _stagedDInf = Double.NaN;

    // feed() must call this before dispatchStep():
    private void stageDInf(double dInf) { _stagedDInf = dInf; }

    // ── sensitivity attribution ────────────────────────────────────────────────

    /** d_∞-based: called at first τ_stable frame after a step. */
    private void attributeSensitivity(double dInfNow) {
        if (Double.isNaN(dInfAtStep)) {
            // dInfAtStep unavailable at step time — fall back to smoothed-based
            attributeSensitivitySmoothed();
            return;
        }
        double deltaDInf = dInfNow - dInfAtStep;
        double deltaFr   = fracR - frAtStep;
        double deltaFmt  = fracMoveTorq - fmtAtStep;

        if (phase == Phase.COARSE && frHasSens && fmtHasSens) {
            // Attribute proportionally (same weighting as v14 updateCoarseSensitivities)
            double expFr  = Math.abs(deltaFr  * frSens);
            double expFmt = Math.abs(deltaFmt * fmtSens);
            double total  = expFr + expFmt;
            if (total > 1e-12) {
                if (Math.abs(deltaFr) > 1e-12) {
                    double raw = deltaDInf * (expFr / total) / deltaFr;
                    if (raw > 0) { frSens = raw; }
                    else System.out.printf("[V15] τ-stable attr: bad-sign frSens=%.4f — keeping prior%n", raw);
                }
                if (Math.abs(deltaFmt) > 1e-12) {
                    double raw = deltaDInf * (expFmt / total) / deltaFmt;
                    if (raw < 0) { fmtSens = raw; }
                    else System.out.printf("[V15] τ-stable attr: bad-sign fmtSens=%.4f — keeping prior%n", raw);
                }
            }
        } else if (phase == Phase.FINE && Math.abs(deltaFmt) > 1e-12) {
            double raw = deltaDInf / deltaFmt;
            if (raw < 0) { fmtSens = raw; fmtHasSens = true; }
            else System.out.printf("[V15] τ-stable attr: FINE bad-sign fmtSens=%.4f — %s%n",
                raw, fmtHasSens ? "keeping prior" : "using probe next");
        }
        System.out.printf("[V15] τ-stable attr (d∞-based)  ΔdInf=%+.6f  Δfr=%+.4f  Δfmt=%+.4f"
            + "  frSens=%s  fmtSens=%s%n",
            deltaDInf, deltaFr, deltaFmt,
            frHasSens ? String.format("%.4f", frSens) : "---",
            fmtHasSens ? String.format("%.4f", fmtSens) : "---");
    }

    /** Smoothed-based fallback: used when τ_stable never fired (T4 timeout). */
    private void attributeSensitivitySmoothed() {
        double defl    = smoothed - smoothedAtStep;
        double deltaFr  = fracR - frAtStep;
        double deltaFmt = fracMoveTorq - fmtAtStep;

        if (phase == Phase.COARSE && frHasSens && fmtHasSens) {
            double expFr  = Math.abs(deltaFr  * frSens);
            double expFmt = Math.abs(deltaFmt * fmtSens);
            double total  = expFr + expFmt;
            if (total > 1e-12) {
                if (Math.abs(deltaFr) > 1e-12) {
                    double raw = defl * (expFr / total) / deltaFr;
                    if (raw > 0) frSens = raw;
                }
                if (Math.abs(deltaFmt) > 1e-12) {
                    double raw = defl * (expFmt / total) / deltaFmt;
                    if (raw < 0) fmtSens = raw;
                }
            }
        } else if (phase == Phase.FINE && Math.abs(deltaFmt) > 1e-12) {
            double raw = defl / deltaFmt;
            if (raw < 0) { fmtSens = raw; fmtHasSens = true; }
        }
        System.out.printf("[V15] timeout attr (smoothed-based)  Δs=%+.6f  Δfr=%+.4f  Δfmt=%+.4f%n",
            defl, deltaFr, deltaFmt);
    }

    // ── trust region updates (identical to v14) ────────────────────────────────

    private void updateCoarseTrust(double defl, double deltaFr, double deltaFmt, double errorAfter) {
        if (!frHasSens && !fmtHasSens) return;
        boolean sf = signFlipped(errorAtStep, errorAfter);
        double predFrAbs  = frHasSens  ? Math.abs(deltaFr  * frSens)  : 0.0;
        double predFmtAbs = fmtHasSens ? Math.abs(deltaFmt * fmtSens) : 0.0;
        double total      = predFrAbs + predFmtAbs;
        if (total < 1e-12) return;
        if (frHasSens  && Math.abs(deltaFr)  > 1e-12)
            trustFr  = updateTrust(trustFr,  defl * (predFrAbs  / total), deltaFr  * frSens,  sf, "fracR");
        if (fmtHasSens && Math.abs(deltaFmt) > 1e-12)
            trustFmt = updateTrust(trustFmt, defl * (predFmtAbs / total), deltaFmt * fmtSens, sf, "fracMoveTorq");
    }

    private void updateFineTrust(double defl, double deltaFmt, double errorAfter) {
        if (!fmtHasSens || Math.abs(deltaFmt) < 1e-12) return;
        trustFmt = updateTrust(trustFmt, defl, deltaFmt * fmtSens, signFlipped(errorAtStep, errorAfter), "fracMoveTorq");
    }

    private static boolean signFlipped(double eBefore, double eAfter) {
        return Math.abs(eBefore) > 1e-12 && Math.abs(eAfter) > 1e-12 && (eBefore > 0) != (eAfter > 0);
    }

    private double updateTrust(double trust, double actual, double predicted,
                               boolean sf, String name) {
        if (sf) {
            double nt = Math.max(trust * TRUST_SHRINK, MIN_TRUST);
            System.out.printf("[V15] trust %s sign-flip  %.4f→%.4f%n", name, trust, nt);
            return nt;
        }
        double ratio = Math.abs(predicted) > 1e-12 ? actual / predicted : 0.0;
        if (ratio >= PREDICTED_FRACTION) {
            double nt = Math.min(trust * TRUST_GROW, MAX_TRUST);
            if (nt > trust + 1e-9)
                System.out.printf("[V15] trust %s grow  %.4f→%.4f%n", name, trust, nt);
            return nt;
        }
        if (ratio < 0.25) {
            double nt = Math.max(trust * TRUST_SHRINK, MIN_TRUST);
            System.out.printf("[V15] trust %s weak  %.4f→%.4f%n", name, trust, nt);
            return nt;
        }
        return trust;
    }

    // ── bracket updates (identical to v14) ────────────────────────────────────

    private void updateFrBrackets(double error) {
        if (error > 0 && (!frHasHi || fracR < frHiBound)) { frHiBound = fracR; frHasHi = true; }
        if (error < 0 && (!frHasLo || fracR > frLoBound)) { frLoBound = fracR; frHasLo = true; }
    }

    private void updateFmtBrackets(double error) {
        if (error > 0 && (!fmtHasLo || fracMoveTorq > fmtLoBound)) { fmtLoBound = fracMoveTorq; fmtHasLo = true; }
        if (error < 0 && (!fmtHasHi || fracMoveTorq < fmtHiBound)) { fmtHiBound = fracMoveTorq; fmtHasHi = true; }
    }

    // ── saturation rescue (identical to v14) ──────────────────────────────────

    private ParamTriple saturationRescue() {
        if (fracMove <= FRAC_MOVE_MIN + 1e-9) {
            phase = Phase.FAILED;
            System.out.println("[V15] FAILED: parameter saturation and fracMove at minimum");
            return null;
        }
        double oldFm = fracMove;
        fracMove = Math.max(fracMove - FRAC_MOVE_STEP, FRAC_MOVE_MIN);
        frHasSens  = false; frHasLo  = false; frHasHi  = false;
        fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
        fineReadyCount = 0; firstStepInPhase = true;
        trustFr = INIT_TRUST; trustFmt = INIT_TRUST;
        watchFrames = 0; convCountV15 = 0;
        clearTauEstBuf(); resetSlopeBuffer();
        phase = Phase.PROBE_FMT;
        probeStepped = false; probeStep = INIT_PROBE_STEP; probeRetryCount = 0; probeStepDir = 0;
        System.out.printf("[V15] saturation rescue: fracMove %.4f→%.4f  → PROBE_FMT%n",
            oldFm, fracMove);
        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── quadratic regression ring buffer ──────────────────────────────────────

    private void pushVA(double s) {
        vaBuf[vaHead] = s;
        vaHead = (vaHead + 1) % W_A;
        if (vaCount < W_A) vaCount++;
    }

    /**
     * Quadratic fit on the W_A rolling buffer (evenly spaced at dtFrame).
     * Returns {v_n, a_n} in µm/s and µm/s² at the most recent frame, or null on error.
     *
     * Uses centered coordinates u_i = (i - mid)*dtFrame, i=0..W_A-1 (oldest to newest),
     * so that odd-order sums vanish and the 3×3 normal equations decouple.
     *
     *   b = Σ(u_i·s_i) / Σ(u_i²)          — slope at centroid
     *   c = [W_A·Σ(u_i²·s_i) - Σ(u_i²)·Σs_i] / [W_A·Σ(u_i⁴) - (Σu_i²)²]
     *   v at latest frame = b + 2c·u_last   where u_last = (W_A-1)/2 · dtFrame
     *   a = 2c
     */
    private double[] computeVA() {
        double mid    = (W_A - 1) * 0.5;
        double sumU2  = 0, sumU4 = 0, sumS = 0, sumUS = 0, sumU2S = 0;
        for (int k = 0; k < W_A; k++) {
            // vaHead points to the next write slot; oldest = vaBuf[vaHead] when full
            int    idx = (vaHead + k) % W_A;
            double u   = (k - mid) * dtFrame;
            double s   = vaBuf[idx];
            double u2  = u * u;
            sumU2  += u2;
            sumU4  += u2 * u2;
            sumS   += s;
            sumUS  += u * s;
            sumU2S += u2 * s;
        }
        double b = sumUS / sumU2;
        double denom = (double) W_A * sumU4 - sumU2 * sumU2;
        if (Math.abs(denom) < 1e-30) return null;
        double c = ((double) W_A * sumU2S - sumU2 * sumS) / denom;
        double uLast = (W_A - 1) * 0.5 * dtFrame;
        double vn = b + 2.0 * c * uLast;
        double an = 2.0 * c;
        return new double[]{vn, an};
    }

    // ── τ_est stability ring buffer ───────────────────────────────────────────

    private void pushTauEst(double tau) {
        tauEstBuf[tauEstHead] = tau;
        tauEstHead = (tauEstHead + 1) % W_TAU;
        if (tauEstCount < W_TAU) tauEstCount++;
    }

    private void clearTauEstBuf() { tauEstHead = 0; tauEstCount = 0; }

    /** True iff W_TAU valid τ_est values are present and their relative std-dev < TAU_STABLE_FRAC. */
    private boolean isTauStable() {
        if (tauEstCount < W_TAU) return false;
        double sum = 0;
        for (int k = 0; k < W_TAU; k++) sum += tauEstBuf[k];
        double mean = sum / W_TAU;
        if (mean <= 0) return false;
        double varSum = 0;
        for (int k = 0; k < W_TAU; k++) { double d = tauEstBuf[k] - mean; varSum += d * d; }
        double relStd = Math.sqrt(varSum / W_TAU) / mean;
        return relStd < TAU_STABLE_FRAC;
    }

    /** Mean of the τ_est ring buffer (W_TAU values). NaN if buffer not full. */
    private double tauEstMean() {
        if (tauEstCount < W_TAU) return Double.NaN;
        double sum = 0;
        for (int k = 0; k < W_TAU; k++) sum += tauEstBuf[k];
        return sum / W_TAU;
    }

    // ── probe settling buffer (v14 slope buffer; probe phases only) ───────────

    private void resetSlopeBuffer() { slopeHead = 0; slopeCount = 0; }

    private void pushSlope(double val) {
        slopeBuf[slopeHead] = val;
        slopeHead = (slopeHead + 1) % slopeWin;
        if (slopeCount < slopeWin) slopeCount++;
    }

    private boolean isSettled() {
        if (framesSinceStep < minFramesPerStep) return false;
        if (slopeCount < slopeWin) return false;
        double oldest = slopeBuf[slopeHead];
        double newest = slopeBuf[(slopeHead - 1 + slopeWin) % slopeWin];
        double scale  = Math.max(Math.abs(smoothed), Math.abs(expected));
        if (scale < 1e-12) scale = 1e-12;
        return Math.abs(newest - oldest) < SETTLE_TOL_FRAC * scale;
    }

    // ── helpers (identical to v14) ────────────────────────────────────────────

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
