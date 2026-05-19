package boxOfActin;

/**
 * Automated deflection tuning — v4: independent per-parameter predictive controllers.
 *
 * Two separate online predictive controllers, one for fracR and one for fracMoveTorq.
 * Each maintains its own sensitivity estimate, brackets, and step history.
 * No joint scalar; parameters start wherever the user left them.
 *
 * Direction convention (confirmed empirically):
 *   Larger fracR        → softer (more deflection); sens_fr > 0
 *   Larger fracMoveTorq → stiffer (less deflection); sens_fmt < 0
 *
 * Phase order: PROBE_FMT → PROBE_FR → COARSE → FINE → CONVERGED (or FAILED anywhere)
 *
 * Pure logic: no Env, no FilSegment, no I/O beyond System.out.
 * Feed one output-frame deflection sample via feed(). Returns a non-null
 * ParamTriple when a parameter change should be applied.
 */
class DeflectionTuner {

    enum Phase { PROBE_FMT, PROBE_FR, COARSE, FINE, CONVERGED, FAILED }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── constants ─────────────────────────────────────────────────────────────
    static final double EMA_ALPHA          = 0.05;  // COARSE/FINE: slow, stable
    static final double PROBE_EMA_ALPHA    = 0.2;   // PROBE phases: fast convergence (98.8% in 20 frames)
    static final int    OBS_WINDOW         = 20;
    static final double INIT_PROBE_STEP    = 0.05;
    static final double MAX_STEP           = 0.2;
    static final double PRED_STEP_TOL      = 0.001;
    static final double CONV_TOL_UM        = 5e-6;
    static final int    CONV_FRAMES        = 50;
    static final double FR_FINE_THRESH     = 0.02;
    static final double ALPHA_DIST         = 0.5;   // fracR share of gap-closure
    static final double FRAC_MOVE_STEP      = 0.05;
    static final int    MAX_PROBE_RETRIES   = 3;
    // Skip bracket update after a large step; EMA hasn't converged to new equilibrium yet
    static final double BRACKET_SKIP_THRESH = 0.05;

    // parameter limits
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── current parameter values ──────────────────────────────────────────────
    private double fracMove, fracR, fracMoveTorq;
    private double expected;

    // ── EMA ───────────────────────────────────────────────────────────────────
    private double  smoothed;
    private boolean smoothedInit;

    // ── observation window ────────────────────────────────────────────────────
    private int    obsFrames;
    private double smoothedAtWindowOpen;
    private double frAtWindowOpen;
    private double fmtAtWindowOpen;

    // ── fracR controller ──────────────────────────────────────────────────────
    private double  frSens;        // dDefl/dFracR; expected > 0
    private boolean frHasSens;
    private double  frLoBound;     // largest "too-stiff" fracR seen (target above this)
    private boolean frHasLo;
    private double  frHiBound;     // smallest "too-soft" fracR seen (target below this)
    private boolean frHasHi;
    private double  frPredStep;    // |predicted step| at last evaluation (for FINE transition)

    // ── fracMoveTorq controller ───────────────────────────────────────────────
    private double  fmtSens;       // dDefl/dFracMoveTorq; expected < 0
    private boolean fmtHasSens;
    private double  fmtLoBound;    // largest "too-soft" fmt seen (target above this)
    private boolean fmtHasLo;
    private double  fmtHiBound;    // smallest "too-stiff" fmt seen (target below this)
    private boolean fmtHasHi;

    // ── phase ─────────────────────────────────────────────────────────────────
    private Phase phase;

    // ── convergence ───────────────────────────────────────────────────────────
    private int    convCount;
    private double lastPredStepMag;  // max(|frPredStep|, |fmtPredStep|) at last step

    // ── COARSE→FINE detection ─────────────────────────────────────────────────
    private int fineReadyCount;      // consecutive steps with |frPredStep| < FR_FINE_THRESH

    // ── last actual step sizes (for bracket-skip logic) ───────────────────────
    private double lastFrActualStep;  // actual Δfr applied in last step
    private double lastFmtActualStep; // actual Δfmt applied in last step

    // ── PROBE sub-state ───────────────────────────────────────────────────────
    private boolean probeStepped;    // false=waiting to take step; true=waiting to evaluate
    private double  probeStep;       // current probe step magnitude
    private int     probeRetryCount;

    // ── logging ───────────────────────────────────────────────────────────────
    private int stepCount;

    // ── public API ────────────────────────────────────────────────────────────

    void start(double fm, double fr, double fmt, double expectedMicrons) {
        fracMove     = fm;
        fracR        = fr;
        fracMoveTorq = fmt;
        expected     = expectedMicrons;

        phase        = Phase.PROBE_FMT;
        smoothed     = 0.0;
        smoothedInit = false;

        frSens  = 0; frHasSens = false; frHasLo = false; frHasHi = false;
        fmtSens = 0; fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
        frPredStep = Double.NaN;

        obsFrames            = 0;
        smoothedAtWindowOpen = 0.0;
        frAtWindowOpen       = fr;
        fmtAtWindowOpen      = fmt;

        convCount      = 0;
        lastPredStepMag = Double.NaN;
        fineReadyCount = 0;

        lastFrActualStep  = 0.0;
        lastFmtActualStep = 0.0;

        probeStepped    = false;
        probeStep       = INIT_PROBE_STEP;
        probeRetryCount = 0;
        stepCount       = 0;
    }

    /**
     * Feed one output-frame deflection sample (µm; force must be ON).
     * Returns null most frames. Returns a ParamTriple at each parameter step.
     * After isDone(), always returns null.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        double alpha = (phase == Phase.PROBE_FMT || phase == Phase.PROBE_FR)
            ? PROBE_EMA_ALPHA : EMA_ALPHA;
        smoothed = smoothedInit
            ? alpha * observedMicrons + (1.0 - alpha) * smoothed
            : observedMicrons;
        smoothedInit = true;

        double error = smoothed - expected;
        checkConvergence(error);
        if (isDone()) return null;

        obsFrames++;
        if (obsFrames < OBS_WINDOW) return null;
        obsFrames = 0;

        switch (phase) {
            case PROBE_FMT: return handleProbeFmt(error);
            case PROBE_FR:  return handleProbeFr(error);
            case COARSE:    return handleCoarse(error);
            case FINE:      return handleFine(error);
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

    // ── probe phase: fracMoveTorq ─────────────────────────────────────────────

    private ParamTriple handleProbeFmt(double error) {
        if (!probeStepped) {
            updateFmtBrackets(error);
            // too soft (error>0) → stiffen → increase fmt; too stiff → soften → decrease fmt
            double dir = (error >= 0) ? +1.0 : -1.0;
            double step = dir * probeStep;
            double newFmt = clamp(fracMoveTorq + step, FRAC_MT_MIN, FRAC_MT_MAX);
            double actualStep = newFmt - fracMoveTorq;

            smoothedAtWindowOpen = smoothed;
            fmtAtWindowOpen      = fracMoveTorq;
            fracMoveTorq         = newFmt;
            probeStepped         = true;
            stepCount++;

            System.out.printf(
                "[AUTOTUNE:STEP#%d] PROBE_FMT take  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fracMoveTorq: %.4f→%.4f (step=%+.4f)  probeRetry=%d%n",
                stepCount, smoothed, expected, error,
                fmtAtWindowOpen, fracMoveTorq, actualStep, probeRetryCount);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);

        } else {
            // evaluate the window just completed
            double trend      = smoothed - smoothedAtWindowOpen;
            double actualStep = fracMoveTorq - fmtAtWindowOpen;
            double rawSens    = (Math.abs(actualStep) > 1e-12) ? trend / actualStep : 0.0;
            boolean signOk    = rawSens < 0; // expected: larger fmt → less deflection

            if (Math.abs(actualStep) < 1e-12) {
                System.out.printf("[AUTOTUNE] PROBE_FMT: probe step clamped to zero — advancing%n");
                signOk = true; // treat as degenerate; keep existing fmtSens if any
            } else if (!signOk) {
                System.out.printf(
                    "[AUTOTUNE] PROBE_FMT bad-sign: rawSens=%.4f (trend=%+.6f step=%+.4f) retry=%d%n",
                    rawSens, trend, actualStep, probeRetryCount);
                if (probeRetryCount >= MAX_PROBE_RETRIES) {
                    phase = Phase.FAILED;
                    System.out.println("[AUTOTUNE] FAILED: PROBE_FMT exhausted retries");
                    return null;
                }
                probeRetryCount++;
                probeStep /= 2.0;
                probeStepped = false;
                return handleProbeFmt(error); // retry immediately
            }

            if (signOk && Math.abs(actualStep) > 1e-12) {
                fmtSens    = rawSens;
                fmtHasSens = true;
            }
            updateFmtBrackets(error);

            System.out.printf(
                "[AUTOTUNE] PROBE_FMT eval: trend=%+.6f  step=%+.4f  fmtSens=%.4f  → PROBE_FR%n",
                trend, actualStep, fmtSens);

            // transition to PROBE_FR
            phase           = Phase.PROBE_FR;
            probeStepped    = false;
            probeStep       = INIT_PROBE_STEP;
            probeRetryCount = 0;
            return handleProbeFr(error); // take FR probe step immediately
        }
    }

    // ── probe phase: fracR ────────────────────────────────────────────────────

    private ParamTriple handleProbeFr(double error) {
        if (!probeStepped) {
            updateFrBrackets(error);
            // too soft (error>0) → stiffen → decrease fracR; too stiff → soften → increase fracR
            double dir = (error >= 0) ? -1.0 : +1.0;
            double step = dir * probeStep;
            double newFr = clamp(fracR + step, FRAC_R_MIN, FRAC_R_MAX);
            double actualStep = newFr - fracR;

            smoothedAtWindowOpen = smoothed;
            frAtWindowOpen       = fracR;
            fmtAtWindowOpen      = fracMoveTorq;
            fracR                = newFr;
            probeStepped         = true;
            stepCount++;

            System.out.printf(
                "[AUTOTUNE:STEP#%d] PROBE_FR take  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
                + "  fracR: %.4f→%.4f (step=%+.4f)  probeRetry=%d%n",
                stepCount, smoothed, expected, error,
                frAtWindowOpen, fracR, actualStep, probeRetryCount);
            return new ParamTriple(fracMove, fracR, fracMoveTorq);

        } else {
            double trend      = smoothed - smoothedAtWindowOpen;
            double actualStep = fracR - frAtWindowOpen;
            double rawSens    = (Math.abs(actualStep) > 1e-12) ? trend / actualStep : 0.0;
            boolean signOk    = rawSens > 0; // expected: larger fracR → more deflection

            if (Math.abs(actualStep) < 1e-12) {
                System.out.printf("[AUTOTUNE] PROBE_FR: probe step clamped to zero — advancing%n");
                signOk = true;
            } else if (!signOk) {
                System.out.printf(
                    "[AUTOTUNE] PROBE_FR bad-sign: rawSens=%.4f (trend=%+.6f step=%+.4f) retry=%d%n",
                    rawSens, trend, actualStep, probeRetryCount);
                if (probeRetryCount >= MAX_PROBE_RETRIES) {
                    phase = Phase.FAILED;
                    System.out.println("[AUTOTUNE] FAILED: PROBE_FR exhausted retries");
                    return null;
                }
                probeRetryCount++;
                probeStep /= 2.0;
                probeStepped = false;
                return handleProbeFr(error); // retry immediately
            }

            if (signOk && Math.abs(actualStep) > 1e-12) {
                frSens    = rawSens;
                frHasSens = true;
            }
            updateFrBrackets(error);

            System.out.printf(
                "[AUTOTUNE] PROBE_FR eval: trend=%+.6f  step=%+.4f  frSens=%.4f  → COARSE%n",
                trend, actualStep, frSens);

            // transition to COARSE; record baselines so first COARSE call can compute Δ
            phase          = Phase.COARSE;
            fineReadyCount = 0;
            smoothedAtWindowOpen = smoothed;
            frAtWindowOpen       = fracR;
            fmtAtWindowOpen      = fracMoveTorq;
            return null; // wait OBS_WINDOW frames before first COARSE step
        }
    }

    // ── COARSE phase ──────────────────────────────────────────────────────────

    private ParamTriple handleCoarse(double error) {
        // Stiff-end saturation: at stiff limits, still too soft → rescue via fracMove
        if (error > 0 && fracR <= FRAC_R_MIN + 1e-9 && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
            return stiffEndRescue();
        }

        // Update sensitivity estimates from the window just completed
        double defl  = smoothed - smoothedAtWindowOpen;
        double deltaFr  = fracR        - frAtWindowOpen;
        double deltaFmt = fracMoveTorq - fmtAtWindowOpen;
        updateCoarseSensitivities(defl, deltaFr, deltaFmt);

        if (Math.abs(lastFrActualStep)  <= BRACKET_SKIP_THRESH) updateFrBrackets(error);
        if (Math.abs(lastFmtActualStep) <= BRACKET_SKIP_THRESH) updateFmtBrackets(error);

        // Predictive steps
        double rawFrPred  = frHasSens
            ? ALPHA_DIST * (expected - smoothed) / frSens
            : 0.0; // shouldn't happen in COARSE, but safe fallback
        double rawFmtPred = fmtHasSens
            ? (1.0 - ALPHA_DIST) * (expected - smoothed) / fmtSens
            : 0.0;

        frPredStep = Math.abs(rawFrPred);
        lastPredStepMag = Math.max(frPredStep, Math.abs(rawFmtPred));

        // Clamp and apply fracR step
        double frStep  = clamp(rawFrPred,  -MAX_STEP, MAX_STEP);
        double newFr   = clamp(fracR   + frStep,  FRAC_R_MIN,  FRAC_R_MAX);
        newFr  = clampToBrackets(newFr,  frHasLo,  frLoBound,  frHasHi,  frHiBound);
        double actFrStep = newFr - fracR;

        // Clamp and apply fracMoveTorq step
        double fmtStep = clamp(rawFmtPred, -MAX_STEP, MAX_STEP);
        double newFmt  = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);
        newFmt = clampToBrackets(newFmt, fmtHasLo, fmtLoBound, fmtHasHi, fmtHiBound);
        double actFmtStep = newFmt - fracMoveTorq;

        System.out.printf(
            "[AUTOTUNE:STEP#%d] COARSE"
            + "  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
            + "  fr=%.4f(%s pred=%+.4f act=%+.4f)"
            + "  fmt=%.4f(%s pred=%+.4f act=%+.4f)%n",
            stepCount + 1, smoothed, expected, error,
            fracR,        frHasSens  ? String.format("%.3f", frSens)  : "---", rawFrPred,  actFrStep,
            fracMoveTorq, fmtHasSens ? String.format("%.3f", fmtSens) : "---", rawFmtPred, actFmtStep);

        // Record baselines before applying step
        smoothedAtWindowOpen = smoothed;
        frAtWindowOpen       = fracR;
        fmtAtWindowOpen      = fracMoveTorq;

        fracR        = newFr;
        fracMoveTorq = newFmt;
        lastFrActualStep  = actFrStep;
        lastFmtActualStep = actFmtStep;
        stepCount++;

        // Check COARSE→FINE: two consecutive steps with small |frPredStep|
        if (frPredStep < FR_FINE_THRESH) {
            if (++fineReadyCount >= 2) {
                double frozenFr = fracR;
                phase          = Phase.FINE;
                fineReadyCount = 0;
                // Reset fmt controller state for fresh FINE estimation
                fmtHasSens        = false;
                fmtHasLo          = false;
                fmtHasHi          = false;
                lastFmtActualStep = 0.0; // allow first FINE bracket update
                smoothedAtWindowOpen = smoothed;
                frAtWindowOpen       = fracR;
                fmtAtWindowOpen      = fracMoveTorq;
                System.out.printf(
                    "[AUTOTUNE] COARSE→FINE: fracR=%.4f frozen  fracMoveTorq=%.4f%n",
                    frozenFr, fracMoveTorq);
            }
        } else {
            fineReadyCount = 0;
        }

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── FINE phase ────────────────────────────────────────────────────────────

    private ParamTriple handleFine(double error) {
        // Stiff-end saturation: fracMoveTorq at stiff limit, still too soft
        if (error > 0 && fracMoveTorq >= FRAC_MT_MAX - 1e-9) {
            return stiffEndRescue();
        }

        double defl     = smoothed - smoothedAtWindowOpen;
        double deltaFmt = fracMoveTorq - fmtAtWindowOpen;
        updateFineSensitivity(defl, deltaFmt);

        if (Math.abs(lastFmtActualStep) <= BRACKET_SKIP_THRESH) updateFmtBrackets(error);

        double rawFmtPred = fmtHasSens
            ? (expected - smoothed) / fmtSens
            : ((error > 0) ? INIT_PROBE_STEP : -INIT_PROBE_STEP); // initial FINE probe
        lastPredStepMag = Math.abs(rawFmtPred);

        double fmtStep    = clamp(rawFmtPred, -MAX_STEP, MAX_STEP);
        double newFmt     = clamp(fracMoveTorq + fmtStep, FRAC_MT_MIN, FRAC_MT_MAX);
        newFmt = clampToBrackets(newFmt, fmtHasLo, fmtLoBound, fmtHasHi, fmtHiBound);
        double actFmtStep = newFmt - fracMoveTorq;

        System.out.printf(
            "[AUTOTUNE:STEP#%d] FINE"
            + "  obs=%.6fµm  exp=%.6fµm  err=%+.6f"
            + "  fr=%.4f(frozen)"
            + "  fmt=%.4f(%s pred=%+.4f act=%+.4f)%n",
            stepCount + 1, smoothed, expected, error,
            fracR,
            fracMoveTorq, fmtHasSens ? String.format("%.3f", fmtSens) : "---", rawFmtPred, actFmtStep);

        smoothedAtWindowOpen  = smoothed;
        fmtAtWindowOpen       = fracMoveTorq;
        fracMoveTorq          = newFmt;
        lastFmtActualStep     = actFmtStep;
        stepCount++;

        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── sensitivity updates ───────────────────────────────────────────────────

    private void updateCoarseSensitivities(double defl, double deltaFr, double deltaFmt) {
        if (!frHasSens || !fmtHasSens) return; // need prior estimates for attribution
        double expFr  = Math.abs(deltaFr  * frSens);
        double expFmt = Math.abs(deltaFmt * fmtSens);
        double total  = expFr + expFmt;
        if (total < 1e-12) return;

        if (Math.abs(deltaFr) > 1e-12) {
            double rawFrSens = defl * (expFr / total) / deltaFr;
            if (rawFrSens > 0) {
                frSens = rawFrSens;
            } else {
                System.out.printf("[AUTOTUNE] bad-sign frSens=%.4f (defl=%+.6f Δfr=%+.4f) — keeping prior%n",
                    rawFrSens, defl, deltaFr);
            }
        }

        if (Math.abs(deltaFmt) > 1e-12) {
            double rawFmtSens = defl * (expFmt / total) / deltaFmt;
            if (rawFmtSens < 0) {
                fmtSens = rawFmtSens;
            } else {
                System.out.printf("[AUTOTUNE] bad-sign fmtSens=%.4f (defl=%+.6f Δfmt=%+.4f) — keeping prior%n",
                    rawFmtSens, defl, deltaFmt);
            }
        }
    }

    private void updateFineSensitivity(double defl, double deltaFmt) {
        if (Math.abs(deltaFmt) < 1e-12) return;
        double rawSens = defl / deltaFmt;
        if (rawSens < 0) {
            fmtSens    = rawSens;
            fmtHasSens = true;
        } else {
            System.out.printf("[AUTOTUNE] FINE bad-sign fmtSens=%.4f (defl=%+.6f Δfmt=%+.4f) — %s%n",
                rawSens, defl, deltaFmt, fmtHasSens ? "keeping prior" : "using probe next");
        }
    }

    // ── bracket updates ───────────────────────────────────────────────────────

    // fracR: larger = softer.
    //   too soft (error>0): fracR too large → upper bound
    //   too stiff (error<0): fracR too small → lower bound
    private void updateFrBrackets(double error) {
        if (error > 0 && (!frHasHi || fracR < frHiBound)) { frHiBound = fracR; frHasHi = true; }
        if (error < 0 && (!frHasLo || fracR > frLoBound)) { frLoBound = fracR; frHasLo = true; }
    }

    // fracMoveTorq: larger = stiffer.
    //   too soft (error>0): fmt too small → lower bound (target is above current fmt)
    //   too stiff (error<0): fmt too large → upper bound
    private void updateFmtBrackets(double error) {
        if (error > 0 && (!fmtHasLo || fracMoveTorq > fmtLoBound)) { fmtLoBound = fracMoveTorq; fmtHasLo = true; }
        if (error < 0 && (!fmtHasHi || fracMoveTorq < fmtHiBound)) { fmtHiBound = fracMoveTorq; fmtHasHi = true; }
    }

    // ── stiff-end saturation rescue ───────────────────────────────────────────

    private ParamTriple stiffEndRescue() {
        if (fracMove <= FRAC_MOVE_MIN + 1e-9) {
            phase = Phase.FAILED;
            System.out.println("[AUTOTUNE] FAILED: stiff-end saturation and fracMove at minimum");
            return null;
        }
        double oldFm = fracMove;
        fracMove = Math.max(fracMove - FRAC_MOVE_STEP, FRAC_MOVE_MIN);

        frHasSens  = false; frHasLo  = false; frHasHi  = false;
        fmtHasSens = false; fmtHasLo = false; fmtHasHi = false;
        fineReadyCount = 0;

        phase           = Phase.PROBE_FMT;
        probeStepped    = false;
        probeStep       = INIT_PROBE_STEP;
        probeRetryCount = 0;
        smoothedAtWindowOpen = smoothed;
        frAtWindowOpen       = fracR;
        fmtAtWindowOpen      = fracMoveTorq;

        System.out.printf(
            "[AUTOTUNE] stiff-end rescue: fracMove %.4f→%.4f  reset→PROBE_FMT%n", oldFm, fracMove);
        return new ParamTriple(fracMove, fracR, fracMoveTorq);
    }

    // ── convergence ───────────────────────────────────────────────────────────

    private void checkConvergence(double error) {
        if (phase == Phase.PROBE_FMT || phase == Phase.PROBE_FR) return;
        if (Double.isNaN(lastPredStepMag) || lastPredStepMag >= PRED_STEP_TOL) {
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
        if (hasLo && hasHi && lo > hi) v = (lo + hi) / 2.0; // degenerate → midpoint
        return v;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
