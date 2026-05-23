package boxOfActin;

/**
 * DeflectionTunerV22 — Broyden's method with explicit settling.
 *
 * Treats tuning as 2D root-finding: find (fracR, fracMoveTorq) such that the
 * settled deflection equals target. No phase machine, no alternation, no active
 * parameter, no sensitivity learning. One settling routine, one Newton-step
 * routine; they share no state beyond the obvious parameters.
 *
 * Algorithm:
 *   1. Settle at three seed points to build an initial 1×2 Jacobian J.
 *   2. Newton step: dp = -J^T * r / (J·J^T),  r = s - target.
 *      (pseudoinverse of underdetermined system; minimum-norm step)
 *   3. Settle at new point, Broyden rank-1 update to J.
 *   4. Repeat until |r| < CONV_TOL_UM or failure.
 */
class DeflectionTunerV22 {

    // ── Constants ─────────────────────────────────────────────────────────────
    static final double CONV_TOL_UM       = 1.0e-5;  // convergence tolerance µm
    static final int    SETTLE_WIN        = 20;       // frames in std window
    static final double SETTLE_TOL_REL    = 0.005;   // std/mean threshold
    static final int    SETTLE_MIN_FRAMES = 30;       // min frames before settling
    static final int    SETTLE_MAX_FRAMES = 300;      // give-up timeout
    static final double PERT              = 0.10;     // relative perturbation for seeds
    static final double PERT_ABS          = 0.10;     // absolute perturbation when fmt near 0
    static final double DAMPING           = 0.7;      // Newton step damping
    static final double MAX_REL_STEP      = 0.5;      // max |dp[i]| / |p[i]|
    static final int    MAX_ITERATIONS    = 30;       // hard limit on Newton steps
    static final double FR_LO  = 0.01, FR_HI  = 2.0;
    static final double FMT_LO = 0.01, FMT_HI = 0.99;

    // ── Inner types ───────────────────────────────────────────────────────────
    enum Phase { SEEDING_1, SEEDING_2, SEEDING_3, NEWTON, CONVERGED, FAILED }

    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        ParamTriple(double fm, double fr, double fmt) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt;
        }
    }

    // ── Public accessors ──────────────────────────────────────────────────────
    Phase   getPhase()        { return phase; }
    boolean isDone()          { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()     { return fracMove; }
    double  getFracR()        { return fr; }
    double  getFracMoveTorq() { return fmt; }

    String resultSummary() {
        return String.format("[V22] %s  fracR=%.4f  fracMoveTorq=%.4f  iters=%d  frames=%d",
            phase, fr, fmt, newtonIter, totalFrames);
    }

    // ── Parameters ────────────────────────────────────────────────────────────
    private double fracMove;   // held constant (fracMove not tuned by V22)
    private double fr, fmt;    // current fracR and fracMoveTorq
    private double target;

    // ── Seed coordinates ──────────────────────────────────────────────────────
    private double fr1, fmt1, s1;   // seed 1 (base)
    private double fr2, fmt2, s2;   // seed 2 (fracR perturbed)
    private double fr3, fmt3;       // seed 3 (fracMoveTorq perturbed)

    // ── Jacobian J (1×2): J[0]=∂s/∂fr, J[1]=∂s/∂fmt ─────────────────────────
    private final double[] J = new double[2];

    // ── Previous point for Broyden update ─────────────────────────────────────
    private final double[] pPrev = new double[2];   // [fr, fmt]
    private double sPrev;

    // ── Settling window (circular) ────────────────────────────────────────────
    private final double[] settleWin = new double[SETTLE_WIN];
    private int    settleHead, settleCount;
    private int    framesAtParams;

    // ── Newton iteration tracking ─────────────────────────────────────────────
    private Phase  phase;
    private int    newtonIter;
    private int    totalFrames;
    private int    stagnantCount;
    private double prevResidualAbs;
    private boolean frBoundHit, fmtBoundHit;

    // ── Public API ────────────────────────────────────────────────────────────

    void start(double fm, double fr_init, double fmt_init, double targetMicrons, double tauUnused) {
        fracMove = fm;
        fr = fr_init;
        fmt = fmt_init;
        target = targetMicrons;
        phase = Phase.SEEDING_1;
        resetSettle();

        fr1 = fr_init;  fmt1 = fmt_init;
        fr2 = fr_init * (1.0 + PERT);  fmt2 = fmt_init;
        fr3 = fr_init;
        fmt3 = (fmt_init < 0.05) ? fmt_init + PERT_ABS : fmt_init * (1.0 + PERT);

        System.out.printf("[V22] armed: fr=%.4f  fmt=%.4f  target=%.6f µm%n", fr, fmt, target);
        System.out.printf("[V22]   CONV_TOL=%.1e  SETTLE_WIN=%d  SETTLE_MIN=%d  SETTLE_MAX=%d%n",
            CONV_TOL_UM, SETTLE_WIN, SETTLE_MIN_FRAMES, SETTLE_MAX_FRAMES);
        System.out.printf("[V22]   PERT=%.2f  DAMPING=%.2f  MAX_REL_STEP=%.2f  MAX_ITER=%d%n",
            PERT, DAMPING, MAX_REL_STEP, MAX_ITERATIONS);
        System.out.printf("[V22] settling seed-1  fr=%.4f  fmt=%.4f%n", fr, fmt);
    }

    ParamTriple feed(double observed) {
        if (phase == Phase.CONVERGED || phase == Phase.FAILED) return null;

        totalFrames++;
        framesAtParams++;
        settleWin[settleHead] = observed;
        settleHead = (settleHead + 1) % SETTLE_WIN;
        if (settleCount < SETTLE_WIN) settleCount++;

        boolean timedOut  = (framesAtParams >= SETTLE_MAX_FRAMES);
        boolean normalOk  = (framesAtParams >= SETTLE_MIN_FRAMES)
                          && (settleCount >= SETTLE_WIN)
                          && settleOk();
        if (!timedOut && !normalOk) return null;

        double sSettled = settleWinMean();
        logSettled(sSettled, timedOut);
        return onSettled(sSettled);
    }

    // ── Settling-complete dispatch ─────────────────────────────────────────────

    private ParamTriple onSettled(double s) {
        switch (phase) {
            case SEEDING_1:
                s1 = s;
                changeParams(fr2, fmt2);
                phase = Phase.SEEDING_2;
                System.out.printf("[V22] settling seed-2  fr=%.4f  fmt=%.4f%n", fr, fmt);
                return new ParamTriple(fracMove, fr, fmt);

            case SEEDING_2:
                s2 = s;
                changeParams(fr3, fmt3);
                phase = Phase.SEEDING_3;
                System.out.printf("[V22] settling seed-3  fr=%.4f  fmt=%.4f%n", fr, fmt);
                return new ParamTriple(fracMove, fr, fmt);

            case SEEDING_3: {
                // Build initial Jacobian from finite differences
                J[0] = (s2 - s1) / (fr2 - fr1);
                J[1] = (s  - s1) / (fmt3 - fmt1);
                System.out.printf("[V22] Jacobian seeded: J=[%.4e, %.4e]  s1=%.6f  s2=%.6f  s3=%.6f%n",
                    J[0], J[1], s1, s2, s);
                // Use seed-3 point as the initial "current" for Newton
                pPrev[0] = fr;  pPrev[1] = fmt;
                sPrev = s;
                prevResidualAbs = Math.abs(s - target);
                phase = Phase.NEWTON;
                return applyNewtonStep(s);
            }

            case NEWTON:
                return onNewtonSettled(s);

            default:
                return null;
        }
    }

    // ── Newton iteration ───────────────────────────────────────────────────────

    private ParamTriple onNewtonSettled(double s) {
        // Broyden rank-1 update
        double dp0 = fr - pPrev[0], dp1 = fmt - pPrev[1];
        double dpdp = dp0*dp0 + dp1*dp1;
        if (dpdp > 1e-20) {
            double ds = s - sPrev;
            double predicted_ds = J[0]*dp0 + J[1]*dp1;
            double fac = (ds - predicted_ds) / dpdp;
            double newJ0 = J[0] + fac * dp0;
            double newJ1 = J[1] + fac * dp1;
            double denom = Math.max(Math.abs(ds), Math.max(Math.abs(predicted_ds), 1e-20));
            double quality = 1.0 - Math.abs(ds - predicted_ds) / denom;
            System.out.printf("[V22:JACOBIAN] J=[%.4e, %.4e]  ds=%.4e  pred_ds=%.4e  quality=%.3f%n",
                newJ0, newJ1, ds, predicted_ds, quality);
            J[0] = newJ0;  J[1] = newJ1;
        }

        double residualAbs = Math.abs(s - target);
        stagnantCount = (residualAbs > 0.9 * prevResidualAbs) ? stagnantCount + 1 : 0;
        prevResidualAbs = residualAbs;
        pPrev[0] = fr;  pPrev[1] = fmt;
        sPrev = s;

        if (stagnantCount >= 3) {
            System.out.printf("[V22:JACOBIAN_RESET] stalled=true  re-seeding from (fr=%.4f  fmt=%.4f)%n", fr, fmt);
            stagnantCount = 0;
            frBoundHit = false;  fmtBoundHit = false;
            // Re-seed: treat current point as seed-1, re-probe from here
            fr1 = fr;  fmt1 = fmt;  s1 = s;
            fr2 = fr * (1.0 + PERT);   fmt2 = fmt;
            fr3 = fr;
            fmt3 = (fmt < 0.05) ? fmt + PERT_ABS : fmt * (1.0 + PERT);
            changeParams(fr2, fmt2);
            phase = Phase.SEEDING_2;
            System.out.printf("[V22] settling seed-2 (re-seed)  fr=%.4f  fmt=%.4f%n", fr, fmt);
            return new ParamTriple(fracMove, fr, fmt);
        }

        if (frBoundHit && fmtBoundHit) {
            System.out.printf("[V22:FAILED] reason=bounds_saturated%n");
            phase = Phase.FAILED;
            return null;
        }

        return applyNewtonStep(s);
    }

    private ParamTriple applyNewtonStep(double s) {
        double r = s - target;
        if (Math.abs(r) < CONV_TOL_UM) {
            System.out.printf("[V22:CONVERGED] iterations=%d  total_frames=%d  final=(fr=%.4f  fmt=%.4f)  s=%.6f%n",
                newtonIter, totalFrames, fr, fmt, s);
            phase = Phase.CONVERGED;
            return null;
        }

        newtonIter++;
        if (newtonIter > MAX_ITERATIONS) {
            System.out.printf("[V22:FAILED] reason=iteration_limit  iterations=%d%n", newtonIter);
            phase = Phase.FAILED;
            return null;
        }

        // Pseudoinverse Newton step for 1×2 Jacobian (minimum-norm solution):
        //   dp = -J^T * r / (J · J^T)   where J·J^T = ||J||^2 (scalar)
        double JJT = J[0]*J[0] + J[1]*J[1];
        if (JJT < 1e-30) {
            System.out.printf("[V22:FAILED] reason=jacobian_zero%n");
            phase = Phase.FAILED;
            return null;
        }
        double dp0 = -J[0] * r / JJT * DAMPING;
        double dp1 = -J[1] * r / JJT * DAMPING;

        // Clamp to MAX_REL_STEP × |current param|
        double maxDp0 = MAX_REL_STEP * Math.abs(fr);
        double maxDp1 = MAX_REL_STEP * Math.abs(fmt);
        if (Math.abs(dp0) > maxDp0) {
            System.out.printf("[V22:CLAMPED] param=fr  requested=%.6f  applied=%.6f%n",
                dp0, Math.signum(dp0) * maxDp0);
            dp0 = Math.signum(dp0) * maxDp0;
        }
        if (Math.abs(dp1) > maxDp1) {
            System.out.printf("[V22:CLAMPED] param=fmt  requested=%.6f  applied=%.6f%n",
                dp1, Math.signum(dp1) * maxDp1);
            dp1 = Math.signum(dp1) * maxDp1;
        }

        double newFr  = fr  + dp0;
        double newFmt = fmt + dp1;

        // Bounds projection
        if (newFr < FR_LO) {
            System.out.printf("[V22:BOUND] param=fr  hit=lo  value=%.4f%n", newFr);
            newFr = FR_LO;  frBoundHit = true;
        } else if (newFr > FR_HI) {
            System.out.printf("[V22:BOUND] param=fr  hit=hi  value=%.4f%n", newFr);
            newFr = FR_HI;  frBoundHit = true;
        }
        if (newFmt < FMT_LO) {
            System.out.printf("[V22:BOUND] param=fmt  hit=lo  value=%.4f%n", newFmt);
            newFmt = FMT_LO;  fmtBoundHit = true;
        } else if (newFmt > FMT_HI) {
            System.out.printf("[V22:BOUND] param=fmt  hit=hi  value=%.4f%n", newFmt);
            newFmt = FMT_HI;  fmtBoundHit = true;
        }

        System.out.printf("[V22:NEWTON#%d] r=%.4e  fr: %.4f→%.4f  fmt: %.4f→%.4f%n",
            newtonIter, r, fr, newFr, fmt, newFmt);
        changeParams(newFr, newFmt);
        return new ParamTriple(fracMove, fr, fmt);
    }

    // ── Settling helpers ──────────────────────────────────────────────────────

    private void resetSettle() {
        settleHead = 0;  settleCount = 0;  framesAtParams = 0;
    }

    private void changeParams(double newFr, double newFmt) {
        fr = newFr;  fmt = newFmt;
        resetSettle();
    }

    private double settleWinMean() {
        if (settleCount == 0) return 0.0;
        int n = Math.min(settleCount, SETTLE_WIN);
        double sum = 0.0;
        for (int i = 0; i < n; i++) sum += settleWin[i];
        return sum / n;
    }

    private double settleWinStd() {
        int n = Math.min(settleCount, SETTLE_WIN);
        if (n < 2) return 0.0;
        double mean = settleWinMean();
        double var = 0.0;
        for (int i = 0; i < n; i++) { double d = settleWin[i] - mean; var += d*d; }
        return Math.sqrt(var / n);
    }

    private boolean settleOk() {
        double mean = settleWinMean();
        if (mean == 0.0) return false;
        return (settleWinStd() / Math.abs(mean)) < SETTLE_TOL_REL;
    }

    private void logSettled(double s, boolean timedOut) {
        double mean = settleWinMean();
        double relStd = (mean != 0.0) ? settleWinStd() / Math.abs(mean) : Double.NaN;
        if (timedOut) {
            System.out.printf("[V22:SETTLE_TIMEOUT] fr=%.4f  fmt=%.4f  frames=%d  std/mean=%.4f%n",
                fr, fmt, framesAtParams, relStd);
        }
        System.out.printf("[V22:SETTLED] params=(fr=%.4f  fmt=%.4f)  s=%.6f  frames=%d  std/mean=%.4f%n",
            fr, fmt, s, framesAtParams, relStd);
    }
}
