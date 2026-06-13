package boxOfActin;

// =========================================================================
// Phase-1 diagnostic for V1_FINISH_LINE item A2 (GRIDSCATTER_RESIDENCY).
//
// The bind-grid scatter (GPUMotorBinding.gridScatterKernel) rebuilds the ENTIRE
// spatial-grid CSR every step from scratch on a SINGLE device thread. Whether
// that full rebuild is redundant turns on the per-step CELL-CROSSING RATE: the
// fraction of FilSegments that actually change their AABB grid-cell footprint
// step-to-step.
//
//   - Low crossing  -> the grid is mostly stable; re-scattering everything every
//                      step is redundant -> incremental re-bin of crossers only.
//   - High crossing -> the full rebuild is genuine -> parallelise / restructure.
//
// Cell-crossing is a PHYSICS property (set by dt, forces, and CELL_SIZE), not a
// device-path property, so measuring it on a CPU run (where the host pose is
// fresh every step) characterises the GPU scatter's workload exactly. The AABB
// math here is a byte-for-byte replica of segBboxKernelResident (coord +- half *
// uVec -> min/max -> floor((p - origin) * invCellSize), clamped to [0, nBins)).
//
// Gated by env BOA_CROSS_PROBE; warmup-skips BOA_PROFILE_WARMUP steps (default
// 300, matching the StepProfiler window). Per-segment previous-state lives on the
// FilSegment object so it travels through swap-compaction; a freshly created
// segment carries the sentinel and is tallied as 'new', not 'cross'.
// =========================================================================
public final class CrossProbe {

    public static final boolean ENABLED;
    private static final int    WARMUP;
    static {
        String v = System.getenv("BOA_CROSS_PROBE");
        ENABLED = (v != null && !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("false"));
        int w = 300;
        String ws = System.getenv("BOA_PROFILE_WARMUP");
        if (ws != null) { try { w = Integer.parseInt(ws.trim()); } catch (NumberFormatException e) {} }
        WARMUP = w;
    }

    // Window accumulators.
    private static long steps            = 0;
    private static long comparedSegSteps = 0;   // seg-steps with a valid prev (denominator)
    private static long aabbCross        = 0;   // AABB cell-set changed (drives scatter)
    private static long centerCross      = 0;   // center cell changed
    private static long newSegs          = 0;   // first-seen (no prev)
    private static long zeroCrossSteps   = 0;   // steps with 0 AABB-crossers
    private static long liveSum          = 0;   // sum of live segCt over window
    private static long cellSpanSum      = 0;   // sum of AABB cell-counts (avg fan-out)

    private static final long SENTINEL = Long.MIN_VALUE;

    public static void sample() {
        if (!ENABLED) return;
        if (Env.counter < WARMUP) return;
        MotorBindGrid3D g = MotorBindGrid3D.INSTANCE;
        if (g == null) return;

        final float xMin = g.xMin, yMin = g.yMin, zMin = g.zMin;
        final float inv  = 1.0f / MotorBindGrid3D.CELL_SIZE;
        final int nX = g.nXBins, nY = g.nYBins, nZ = g.nZBins;
        final int nXY = nX * nY;

        final int live = FilSegment.filSegmentCt;
        long stepAabbCross = 0;
        long stepCompared  = 0;

        for (int i = 0; i < live; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null) continue;

            double cx = fs.getCoordX(), cy = fs.getCoordY(), cz = fs.getCoordZ();
            double ux = fs.getUVecX(),  uy = fs.getUVecY(),  uz = fs.getUVecZ();
            double half = 0.5 * fs.length;

            double e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;
            double e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;

            float xLo = (float)(e1x < e2x ? e1x : e2x), xHi = (float)(e1x > e2x ? e1x : e2x);
            float yLo = (float)(e1y < e2y ? e1y : e2y), yHi = (float)(e1y > e2y ? e1y : e2y);
            float zLo = (float)(e1z < e2z ? e1z : e2z), zHi = (float)(e1z > e2z ? e1z : e2z);

            int ix0 = clamp((int)((xLo - xMin) * inv), nX);
            int ix1 = clamp((int)((xHi - xMin) * inv), nX);
            int iy0 = clamp((int)((yLo - yMin) * inv), nY);
            int iy1 = clamp((int)((yHi - yMin) * inv), nY);
            int iz0 = clamp((int)((zLo - zMin) * inv), nZ);
            int iz1 = clamp((int)((zHi - zMin) * inv), nZ);

            long packed = packAabb(ix0, ix1, iy0, iy1, iz0, iz1);
            int  center = ix0 + iy0 * nX + iz0 * nXY;   // min-corner cell (stable key)
            cellSpanSum += (long)(ix1 - ix0 + 1) * (iy1 - iy0 + 1) * (iz1 - iz0 + 1);

            long prev = fs.probePrevAabb;
            if (prev == SENTINEL) {
                newSegs++;
            } else {
                stepCompared++;
                if (packed != prev)            { aabbCross++; stepAabbCross++; }
                if (center != fs.probePrevCenter) { centerCross++; }
            }
            fs.probePrevAabb   = packed;
            fs.probePrevCenter = center;
        }

        steps++;
        liveSum += live;
        comparedSegSteps += stepCompared;
        if (stepAabbCross == 0) zeroCrossSteps++;
    }

    public static void report() {
        if (!ENABLED) return;
        if (steps == 0) {
            System.out.println("[CROSS_PROBE] no window captured (run did not reach warmup boundary)");
            return;
        }
        double avgLive   = (double) liveSum / steps;
        double avgFanout = comparedSegSteps > 0 ? (double) cellSpanSum / (liveSum) : 0.0;
        double aabbFrac  = comparedSegSteps > 0 ? 100.0 * aabbCross   / comparedSegSteps : 0.0;
        double ctrFrac   = comparedSegSteps > 0 ? 100.0 * centerCross / comparedSegSteps : 0.0;
        System.out.println();
        System.out.printf("*** [CROSS_PROBE] steps=%d warmup=%d avgLiveSegs=%.0f avgAabbCells/seg=%.2f ***%n",
                          steps, WARMUP, avgLive, avgFanout);
        System.out.printf("    comparedSegSteps=%d  newSegs=%d%n", comparedSegSteps, newSegs);
        System.out.printf("    AABB-cell-set changed : %d  = %.2f%% of segs/step  (drives scatter rebuild)%n",
                          aabbCross, aabbFrac);
        System.out.printf("    center-cell changed   : %d  = %.2f%% of segs/step%n", centerCross, ctrFrac);
        System.out.printf("    steps with 0 AABB-crossers: %d / %d  (%.1f%%)%n",
                          zeroCrossSteps, steps, 100.0 * zeroCrossSteps / steps);
    }

    private static int clamp(int b, int n) {
        if (b < 0) return 0;
        if (b >= n) return n - 1;
        return b;
    }

    // 6 cell indices, 10 bits each (supports nBins up to 1024 -> good past 16x).
    private static long packAabb(int ix0, int ix1, int iy0, int iy1, int iz0, int iz1) {
        return ((long)(ix0 & 0x3FF) << 50) | ((long)(ix1 & 0x3FF) << 40)
             | ((long)(iy0 & 0x3FF) << 30) | ((long)(iy1 & 0x3FF) << 20)
             | ((long)(iz0 & 0x3FF) << 10) |  (long)(iz1 & 0x3FF);
    }

    private CrossProbe() {}
}
