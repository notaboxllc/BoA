package boxOfActin;

/**
 * Automated deflection tuning controller for the F1 benchmark chain.
 *
 * Pure logic: no Env, no FilSegment, no I/O. Feed one output-frame deflection
 * sample at a time via feed(). When feed() returns a non-null ParamTriple, the
 * caller applies the new parameter values and (if resetChain==true) resets the
 * deflection chain. When isDone() is true, getPhase() is CONVERGED or FAILED.
 *
 * Direction convention (confirmed empirically):
 *   Larger fracR      → softer (more deflection)
 *   Smaller fracR     → stiffer (less deflection)
 *   Larger fracMoveTorq → stiffer (less deflection)
 *   Smaller fracMoveTorq → softer (more deflection)
 *
 * Coarse stiffening step: fracR down 0.1, fracMoveTorq up 0.1.
 * Stiff limit: fracR=0.1 (min) AND fracMoveTorq=0.5 (max).
 * Failure: at stiff limit AND still too stiff (error<0) AND fracMove at min.
 */
class DeflectionTuner {

    enum Phase { COARSE, FINE, CONVERGED, FAILED }

    /** What the caller should apply when feed() returns non-null. */
    static class ParamTriple {
        final double fracMove, fracR, fracMoveTorq;
        final boolean resetChain;
        ParamTriple(double fm, double fr, double fmt, boolean reset) {
            fracMove = fm; fracR = fr; fracMoveTorq = fmt; resetChain = reset;
        }
    }

    // ── tuning constants ──────────────────────────────────────────────────────
    static final int    WINDOW_N       = 30;     // averaging window (output frames)
    static final double COARSE_STEP      = 0.1;   // |ΔfracR| = |ΔfracMoveTorq| per coarse step
    static final double COARSE_FINE_FRAC = 0.20; // enter fine when |error|/expected < this fraction
    static final double FINE_STEP_INIT   = 0.05; // initial fracMoveTorq bisection step
    static final double CONV_TOL_UM      = 5e-6; // ±0.005 nm expressed in µm
    static final int    CONV_WINDOWS   = 3;      // consecutive windows within tolerance → CONVERGED
    static final int    STUCK_LIMIT    = 3;      // fine-phase windows at a clamped limit → FAILED

    // ── parameter limits ──────────────────────────────────────────────────────
    static final double FRAC_MOVE_MIN = 0.1,  FRAC_MOVE_MAX = 0.5;
    static final double FRAC_R_MIN    = 0.1,  FRAC_R_MAX    = 1.5;
    static final double FRAC_MT_MIN   = 0.01, FRAC_MT_MAX   = 0.5;

    // ── internal state ────────────────────────────────────────────────────────
    private Phase  phase;
    private double fracMove, fracR, fracMoveTorq;
    private double fracMoveInit, fracRInit, fracMoveTorqInit; // values at start()
    private double expected;      // analytic deflection (µm), fixed at start()
    private double fineStep;      // current fine bisection step size
    private double frozenFracR;   // fracR value frozen on coarse→fine transition
    private double prevSmoothed;  // smoothed value from previous window (NaN = none)

    private int settleSkip;         // frames to discard after each param change (chain-length-aware)
    private final double[] sampleBuf = new double[WINDOW_N];
    private int sampleHead;       // next write index (mod WINDOW_N gives slot)
    private int sampleCount;      // valid samples in ring (0..WINDOW_N)
    private int skipRemaining;    // frames still in settle-skip period
    private int convCount;        // consecutive windows within CONV_TOL (fine phase)
    private int stuckCount;       // consecutive windows where fracMoveTorq was clamped (fine phase)

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Arms the controller with current parameter values, the analytic target, and the
     * initial settle-frame count (computed by BoxOfActin from monomerCt and fracMoveTorq).
     * Must be called before feed(). Resets all internal state.
     */
    void start(double fm, double fr, double fmt, double expectedMicrons, int initialSettleFrames) {
        fracMove = fracMoveInit = fm;
        fracR    = fracRInit    = fr;
        fracMoveTorq = fracMoveTorqInit = fmt;
        expected = expectedMicrons;
        phase        = Phase.COARSE;
        fineStep     = FINE_STEP_INIT;
        frozenFracR  = fr;
        prevSmoothed = Double.NaN;
        sampleHead   = 0;
        sampleCount  = 0;
        settleSkip    = initialSettleFrames;
        skipRemaining = initialSettleFrames;
        convCount    = 0;
    }

    /**
     * Updates the settle-frame count for the current and all future resets.
     * Call this after applying a ParamTriple so the next settle uses the updated formula.
     * Also replaces skipRemaining so the current settle period reflects the new length.
     */
    void setSettleSkip(int frames) {
        settleSkip    = frames;
        skipRemaining = frames;
    }

    /**
     * Feed one output-frame deflection sample (in µm, force must be ON).
     * Returns null while settling or accumulating.
     * Returns a ParamTriple when a parameter change is decided.
     * After isDone(), always returns null; caller should check isDone() each call.
     */
    ParamTriple feed(double observedMicrons) {
        if (isDone()) return null;

        // Settle period: discard samples immediately after a parameter change.
        if (skipRemaining > 0) { skipRemaining--; return null; }

        // Accumulate into ring buffer.
        sampleBuf[sampleHead % WINDOW_N] = observedMicrons;
        sampleHead++;
        sampleCount = Math.min(sampleCount + 1, WINDOW_N);
        if (sampleCount < WINDOW_N) return null;  // window not yet full

        // Window full — compute running mean.
        double sum = 0;
        for (double v : sampleBuf) sum += v;
        double smoothed = sum / WINDOW_N;

        return phase == Phase.COARSE ? doCoarse(smoothed) : doFine(smoothed);
    }

    Phase   getPhase()       { return phase; }
    boolean isDone()         { return phase == Phase.CONVERGED || phase == Phase.FAILED; }
    double  getFracMove()    { return fracMove; }
    double  getFracR()       { return fracR; }
    double  getFracMoveTorq(){ return fracMoveTorq; }

    /** One-line result for stdout / HUD display. */
    String resultSummary() {
        return String.format("[AUTOTUNE] %s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f",
            phase, fracMove, fracR, fracMoveTorq);
    }

    // ── private control logic ─────────────────────────────────────────────────

    private ParamTriple doCoarse(double smoothed) {
        double error = smoothed - expected;  // >0 = too soft, <0 = too stiff

        // Already close enough for fine-phase bisection — skip coarse stepping.
        if (Math.abs(error) <= COARSE_FINE_FRAC * expected) {
            phase        = Phase.FINE;
            frozenFracR  = fracR;
            fineStep     = FINE_STEP_INIT;
            convCount    = 0;
            prevSmoothed = Double.NaN;
            sampleCount  = 0;
            sampleHead   = 0;
            skipRemaining = 0;
            return null;
        }

        // Limit-hit failure: at the stiffening limit AND still too stiff.
        // Stiffening limit: fracR at min (0.1) AND fracMoveTorq at max (0.5).
        if (fracR <= FRAC_R_MIN + 1e-9 && fracMoveTorq >= FRAC_MT_MAX - 1e-9 && error < 0) {
            if (fracMove <= FRAC_MOVE_MIN + 1e-9) {
                phase = Phase.FAILED;
                return null;
            }
            // Lower fracMove and restart coarse from initial fracR/fracMoveTorq.
            fracMove     = Math.max(fracMove - 0.05, FRAC_MOVE_MIN);
            fracR        = fracRInit;
            fracMoveTorq = fracMoveTorqInit;
            prevSmoothed = Double.NaN;
            return resetAndReturn(true);
        }

        // Overshoot detection: smoothed crossed theoretical since last evaluation.
        if (!Double.isNaN(prevSmoothed)) {
            double prevError = prevSmoothed - expected;
            if (prevError > 0 && error <= 0 || prevError < 0 && error >= 0) {
                // Sign flip → enter fine phase. Params unchanged; no chain reset.
                phase        = Phase.FINE;
                frozenFracR  = fracR;
                fineStep     = FINE_STEP_INIT;
                convCount    = 0;
                prevSmoothed = Double.NaN;
                sampleCount  = 0;
                sampleHead   = 0;
                skipRemaining = 0;  // no settle; params unchanged
                return null;
            }
        }
        prevSmoothed = smoothed;

        // Step in the direction that reduces |error|.
        double newFracR, newFracMoveTorq;
        if (error > 0) {
            // Too soft → stiffen: fracR down, fracMoveTorq up.
            newFracR        = Math.max(fracR - COARSE_STEP, FRAC_R_MIN);
            newFracMoveTorq = Math.min(fracMoveTorq + COARSE_STEP, FRAC_MT_MAX);
        } else {
            // Too stiff → soften: fracR up, fracMoveTorq down.
            newFracR        = Math.min(fracR + COARSE_STEP, FRAC_R_MAX);
            newFracMoveTorq = Math.max(fracMoveTorq - COARSE_STEP, FRAC_MT_MIN);
        }
        fracR        = newFracR;
        fracMoveTorq = newFracMoveTorq;
        return resetAndReturn(true);
    }

    private ParamTriple doFine(double smoothed) {
        double error = smoothed - expected;

        // Convergence: |error| within tolerance for CONV_WINDOWS consecutive windows.
        if (Math.abs(error) <= CONV_TOL_UM) {
            convCount++;
            if (convCount >= CONV_WINDOWS) {
                phase = Phase.CONVERGED;
                return null;
            }
        } else {
            convCount = 0;
        }

        // Bisection: halve step on sign reversal.
        if (!Double.isNaN(prevSmoothed)) {
            double prevError = prevSmoothed - expected;
            if (prevError * error < 0) fineStep /= 2.0;
        }
        prevSmoothed = smoothed;

        // Adjust fracMoveTorq. fracR stays frozen at frozenFracR.
        double newFmt;
        if (error > 0) {
            // Too soft → stiffen: increase fracMoveTorq.
            newFmt = Math.min(fracMoveTorq + fineStep, FRAC_MT_MAX);
        } else {
            // Too stiff → soften: decrease fracMoveTorq.
            newFmt = Math.max(fracMoveTorq - fineStep, FRAC_MT_MIN);
        }
        boolean clamped = (newFmt == fracMoveTorq);
        fracMoveTorq = newFmt;
        fracR = frozenFracR;  // keep frozen

        if (clamped && convCount == 0) {
            stuckCount++;
            if (stuckCount >= STUCK_LIMIT) { phase = Phase.FAILED; return null; }
        } else {
            stuckCount = 0;
        }
        return resetAndReturn(true);
    }

    private ParamTriple resetAndReturn(boolean resetChain) {
        sampleCount   = 0;
        sampleHead    = 0;
        skipRemaining = settleSkip;  // BoxOfActin updates settleSkip via setSettleSkip() after applying each triple
        return new ParamTriple(fracMove, fracR, fracMoveTorq, resetChain);
    }
}
