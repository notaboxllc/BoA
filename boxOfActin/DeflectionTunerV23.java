package boxOfActin;

/**
 * DeflectionTunerV23 — Broyden's method with corrected bounds, stiffest-corner start,
 * and fracMove outer loop for infeasibility handling.
 *
 * Changes from V22:
 *  - Corrected bounds: FR=[0.1,1.5], FMT=[0.01,0.5]
 *  - Always starts from stiffest corner (fr=0.1, fmt=0.5); arm block must set Env params first.
 *  - Seed perturbations are absolute (PERT_FR/FMT) and direction-aware: always perturb
 *    toward the interior of the legal box from whatever corner we are at.
 *  - fracMove outer loop: on 2D infeasibility (Newton stuck at bounds for 3 consecutive
 *    iterations), drop fracMove by 0.05 and restart 2D from the corner.
 */
class DeflectionTunerV23 {

    // ── Constants ─────────────────────────────────────────────────────────────
    static final double CONV_TOL_UM             = 1.0e-5;
    static final int    SETTLE_WIN              = 20;
    static final double SETTLE_TOL_REL          = 0.005;
    static final int    SETTLE_MIN_FRAMES       = 30;
    static final int    SETTLE_MAX_FRAMES       = 300;
    static final double PERT_FR                 = 0.15;  // absolute fr perturbation for seed-2
    static final double PERT_FMT                = 0.10;  // absolute fmt perturbation for seed-3
    static final double DAMPING                 = 0.7;
    static final double MAX_REL_STEP            = 0.5;
    static final int    MAX_ITERATIONS          = 30;    // per-fracMove Newton cap
    static final int    MAX_FRACMOVE_DROPS      = 8;
    static final int    INFEASIBILITY_THRESHOLD = 3;
    static final double FRACMOVE_DECREMENT      = 0.05;
    static final double FRACMOVE_MIN            = 0.1;
    static final double FR_LO   = 0.1,  FR_HI  = 1.5;
    static final double FMT_LO  = 0.01, FMT_HI = 0.5;
    static final double BOUND_EPS = 1e-6;  // epsilon for "at bound" detection in setSeedTriplet

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
        return String.format(
            "[V23] %s  fracR=%.4f  fracMoveTorq=%.4f  fracMove=%.4f  iters=%d  drops=%d  frames=%d",
            phase, fr, fmt, fracMove, newtonIter, fracMoveDrops, totalFrames);
    }

    // ── Parameters ────────────────────────────────────────────────────────────
    private double fracMove;
    private double fr, fmt;
    private double target;

    // ── Seed coordinates ──────────────────────────────────────────────────────
    private double fr1, fmt1, s1;
    private double fr2, fmt2, s2;
    private double fr3, fmt3;

    // ── Jacobian J (1×2): J[0]=∂s/∂fr, J[1]=∂s/∂fmt ─────────────────────────
    private final double[] J = new double[2];

    // ── Previous point for Broyden update ─────────────────────────────────────
    private final double[] pPrev = new double[2];
    private double sPrev;

    // ── Settling window (circular) ────────────────────────────────────────────
    private final double[] settleWin = new double[SETTLE_WIN];
    private int    settleHead, settleCount;
    private int    framesAtParams;

    // ── Newton iteration tracking (per fracMove level) ────────────────────────
    private Phase  phase;
    private int    newtonIter;
    private int    totalFrames;        // cumulative across all fracMove levels
    private int    stagnantCount;
    private double prevResidualAbs;

    // ── Outer loop (fracMove iteration) ──────────────────────────────────────
    private int    fracMoveDrops;
    private int    iterationsAtCorner; // consecutive Newton steps landing on a bound

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Arm block must have already set Env.fracR = FR_LO and Env.fracMoveTorq = FMT_HI
     * before calling start(), so the simulation begins at the stiffest corner.
     * fr_init and fmt_init are accepted for signature compatibility but not used.
     */
    void start(double fm, double frUnused, double fmtUnused, double targetMicrons, double tauUnused) {
        fracMove      = fm;
        target        = targetMicrons;
        fracMoveDrops = 0;
        totalFrames   = 0;

        initTwoDimSearch(FR_LO, FMT_HI);   // always start from stiffest corner

        System.out.printf("[V23] armed: fr=%.4f  fmt=%.4f  fracMove=%.4f  target=%.6f µm%n",
            fr, fmt, fracMove, target);
        System.out.printf("[V23]   bounds: FR=[%.2f,%.2f]  FMT=[%.2f,%.2f]%n",
            FR_LO, FR_HI, FMT_LO, FMT_HI);
        System.out.printf("[V23]   CONV_TOL=%.1e  SETTLE_WIN=%d  SETTLE_MIN=%d  SETTLE_MAX=%d%n",
            CONV_TOL_UM, SETTLE_WIN, SETTLE_MIN_FRAMES, SETTLE_MAX_FRAMES);
        System.out.printf("[V23]   PERT_FR=%.2f  PERT_FMT=%.2f  DAMPING=%.2f  MAX_ITER=%d  MAX_FM_DROPS=%d%n",
            PERT_FR, PERT_FMT, DAMPING, MAX_ITERATIONS, MAX_FRACMOVE_DROPS);
        System.out.printf("[V23] settling seed-1  fr=%.4f  fmt=%.4f%n", fr, fmt);
    }

    ParamTriple feed(double observed) {
        if (phase == Phase.CONVERGED || phase == Phase.FAILED) return null;

        totalFrames++;
        framesAtParams++;
        settleWin[settleHead] = observed;
        settleHead = (settleHead + 1) % SETTLE_WIN;
        if (settleCount < SETTLE_WIN) settleCount++;

        boolean timedOut = (framesAtParams >= SETTLE_MAX_FRAMES);
        boolean normalOk = (framesAtParams >= SETTLE_MIN_FRAMES)
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
                System.out.printf("[V23] settling seed-2  fr=%.4f  fmt=%.4f%n", fr, fmt);
                return new ParamTriple(fracMove, fr, fmt);

            case SEEDING_2:
                s2 = s;
                changeParams(fr3, fmt3);
                phase = Phase.SEEDING_3;
                System.out.printf("[V23] settling seed-3  fr=%.4f  fmt=%.4f%n", fr, fmt);
                return new ParamTriple(fracMove, fr, fmt);

            case SEEDING_3: {
                double dFr  = fr2 - fr1;
                double dFmt = fmt3 - fmt1;
                J[0] = (dFr  != 0.0) ? (s2 - s1) / dFr  : 0.0;
                J[1] = (dFmt != 0.0) ? (s  - s1) / dFmt  : 0.0;
                System.out.printf("[V23] Jacobian seeded: J=[%.4e, %.4e]  s1=%.6f  s2=%.6f  s3=%.6f%n",
                    J[0], J[1], s1, s2, s);
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
            double ds           = s - sPrev;
            double predicted_ds = J[0]*dp0 + J[1]*dp1;
            double fac          = (ds - predicted_ds) / dpdp;
            double newJ0        = J[0] + fac * dp0;
            double newJ1        = J[1] + fac * dp1;
            double denom        = Math.max(Math.abs(ds), Math.max(Math.abs(predicted_ds), 1e-20));
            double quality      = 1.0 - Math.abs(ds - predicted_ds) / denom;
            System.out.printf("[V23:JACOBIAN] J=[%.4e, %.4e]  ds=%.4e  pred_ds=%.4e  quality=%.3f%n",
                newJ0, newJ1, ds, predicted_ds, quality);
            J[0] = newJ0;  J[1] = newJ1;
        }

        double residualAbs = Math.abs(s - target);
        stagnantCount = (residualAbs > 0.9 * prevResidualAbs) ? stagnantCount + 1 : 0;
        prevResidualAbs = residualAbs;
        pPrev[0] = fr;  pPrev[1] = fmt;
        sPrev = s;

        if (stagnantCount >= 3) {
            System.out.printf("[V23:JACOBIAN_RESET] stalled=true  re-seeding from (fr=%.4f  fmt=%.4f)%n", fr, fmt);
            stagnantCount = 0;
            iterationsAtCorner = 0;
            // Current settled point becomes seed-1 (no re-settle needed)
            setSeedTriplet(fr, fmt);
            s1 = s;
            changeParams(fr2, fmt2);
            phase = Phase.SEEDING_2;
            System.out.printf("[V23] settling seed-2 (re-seed)  fr=%.4f  fmt=%.4f%n", fr, fmt);
            return new ParamTriple(fracMove, fr, fmt);
        }

        return applyNewtonStep(s);
    }

    private ParamTriple applyNewtonStep(double s) {
        double r = s - target;
        if (Math.abs(r) < CONV_TOL_UM) {
            System.out.printf(
                "[V23:CONVERGED] iterations=%d  fracMove_drops=%d  total_frames=%d  final=(fr=%.4f  fmt=%.4f  fracMove=%.4f)  s=%.6f%n",
                newtonIter, fracMoveDrops, totalFrames, fr, fmt, fracMove, s);
            phase = Phase.CONVERGED;
            return null;
        }

        newtonIter++;
        if (newtonIter > MAX_ITERATIONS) {
            System.out.printf("[V23:FAILED] reason=iteration_limit  iterations=%d  fracMove=%.4f%n",
                newtonIter, fracMove);
            phase = Phase.FAILED;
            return null;
        }

        double JJT = J[0]*J[0] + J[1]*J[1];
        if (JJT < 1e-30) {
            System.out.printf("[V23:FAILED] reason=jacobian_zero  fracMove=%.4f%n", fracMove);
            phase = Phase.FAILED;
            return null;
        }

        double dp0 = -J[0] * r / JJT * DAMPING;
        double dp1 = -J[1] * r / JJT * DAMPING;

        double maxDp0 = MAX_REL_STEP * Math.abs(fr);
        double maxDp1 = MAX_REL_STEP * Math.abs(fmt);
        if (Math.abs(dp0) > maxDp0) {
            System.out.printf("[V23:CLAMPED] param=fr  requested=%.6f  applied=%.6f%n",
                dp0, Math.signum(dp0) * maxDp0);
            dp0 = Math.signum(dp0) * maxDp0;
        }
        if (Math.abs(dp1) > maxDp1) {
            System.out.printf("[V23:CLAMPED] param=fmt  requested=%.6f  applied=%.6f%n",
                dp1, Math.signum(dp1) * maxDp1);
            dp1 = Math.signum(dp1) * maxDp1;
        }

        double newFr  = fr  + dp0;
        double newFmt = fmt + dp1;

        // Bounds projection; track whether this step lands on a bound
        boolean atBound = false;
        if (newFr < FR_LO) {
            System.out.printf("[V23:BOUND] param=fr  hit=lo  value=%.4f%n", newFr);
            newFr = FR_LO;   atBound = true;
        } else if (newFr > FR_HI) {
            System.out.printf("[V23:BOUND] param=fr  hit=hi  value=%.4f%n", newFr);
            newFr = FR_HI;   atBound = true;
        }
        if (newFmt < FMT_LO) {
            System.out.printf("[V23:BOUND] param=fmt  hit=lo  value=%.4f%n", newFmt);
            newFmt = FMT_LO; atBound = true;
        } else if (newFmt > FMT_HI) {
            System.out.printf("[V23:BOUND] param=fmt  hit=hi  value=%.4f%n", newFmt);
            newFmt = FMT_HI; atBound = true;
        }

        iterationsAtCorner = atBound ? iterationsAtCorner + 1 : 0;

        System.out.printf("[V23:NEWTON#%d] r=%.4e  fracMove=%.4f  fr: %.4f→%.4f  fmt: %.4f→%.4f%n",
            newtonIter, r, fracMove, fr, newFr, fmt, newFmt);

        if (iterationsAtCorner >= INFEASIBILITY_THRESHOLD && Math.abs(r) > CONV_TOL_UM) {
            return handleInfeasible(r, newFr, newFmt);
        }

        changeParams(newFr, newFmt);
        return new ParamTriple(fracMove, fr, fmt);
    }

    private ParamTriple handleInfeasible(double r, double frCorner, double fmtCorner) {
        if (r > 0) {
            // Too soft; needs stiffer fracMove, but fracMove ceiling at 0.5
            System.out.printf(
                "[V23:INFEASIBLE_2D] fracMove=%.4f  corner=(fr=%.4f, fmt=%.4f)  residual=%.4e  direction=stiff%n",
                fracMove, frCorner, fmtCorner, r);
            System.out.printf(
                "[V23:FAILED] reason=needs_stiffer  fracMove_at_ceiling=0.5  raise_above_0.5_for_solution=true%n");
            phase = Phase.FAILED;
            return null;
        }

        // Too stiff; drop fracMove to soften the chain
        System.out.printf(
            "[V23:INFEASIBLE_2D] fracMove=%.4f  corner=(fr=%.4f, fmt=%.4f)  residual=%.4e  direction=soft%n",
            fracMove, frCorner, fmtCorner, r);

        if (fracMoveDrops >= MAX_FRACMOVE_DROPS) {
            System.out.printf("[V23:FAILED] reason=fracmove_iteration_limit  drops=%d%n", fracMoveDrops);
            phase = Phase.FAILED;
            return null;
        }

        double oldFm = fracMove;
        fracMove = Math.max(FRACMOVE_MIN, fracMove - FRACMOVE_DECREMENT);
        fracMoveDrops++;
        System.out.printf("[V23:FRACMOVE_DROP] fracMove: %.2f → %.2f  restarting 2D from (fr=%.4f  fmt=%.4f)%n",
            oldFm, fracMove, frCorner, fmtCorner);

        initTwoDimSearch(frCorner, fmtCorner);
        System.out.printf("[V23] settling seed-1  fr=%.4f  fmt=%.4f  fracMove=%.4f%n", fr, fmt, fracMove);
        return new ParamTriple(fracMove, fr, fmt);
    }

    // ── 2D search initialization ──────────────────────────────────────────────

    private void initTwoDimSearch(double frCorner, double fmtCorner) {
        fr  = frCorner;
        fmt = fmtCorner;
        phase          = Phase.SEEDING_1;
        newtonIter     = 0;
        stagnantCount  = 0;
        prevResidualAbs   = Double.MAX_VALUE;
        iterationsAtCorner = 0;
        resetSettle();
        setSeedTriplet(frCorner, fmtCorner);
    }

    /**
     * Set seed-2 and seed-3 by perturbing fr and fmt toward the interior of the
     * legal box from the given corner.  Direction depends on which bound the corner
     * is against; defaults to "softer" (increase fr, decrease fmt) for interior points.
     */
    private void setSeedTriplet(double frCorner, double fmtCorner) {
        fr1 = frCorner;  fmt1 = fmtCorner;

        // fr: at FR_HI (soft bound) → perturb stiffer (down); else perturb softer (up)
        double frPertDir = (frCorner >= FR_HI - BOUND_EPS) ? -1.0 : 1.0;
        fr2  = Math.max(FR_LO, Math.min(FR_HI, frCorner + frPertDir * PERT_FR));
        fmt2 = fmtCorner;

        // fmt: at FMT_LO (soft bound) → perturb stiffer (up); else perturb softer (down)
        double fmtPertDir = (fmtCorner <= FMT_LO + BOUND_EPS) ? 1.0 : -1.0;
        fr3  = frCorner;
        fmt3 = Math.max(FMT_LO, Math.min(FMT_HI, fmtCorner + fmtPertDir * PERT_FMT));
    }

    // ── Settling helpers (identical to V22) ───────────────────────────────────

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
        double mean   = settleWinMean();
        double relStd = (mean != 0.0) ? settleWinStd() / Math.abs(mean) : Double.NaN;
        if (timedOut) {
            System.out.printf(
                "[V23:SETTLE_TIMEOUT] fr=%.4f  fmt=%.4f  fracMove=%.4f  frames=%d  std/mean=%.4f%n",
                fr, fmt, fracMove, framesAtParams, relStd);
        }
        System.out.printf(
            "[V23:SETTLED] params=(fr=%.4f  fmt=%.4f  fracMove=%.4f)  s=%.6f  frames=%d  std/mean=%.4f%n",
            fr, fmt, fracMove, s, framesAtParams, relStd);
    }
}
