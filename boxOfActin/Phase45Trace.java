package boxOfActin;

// Phase 4.5 propagation trace (2026-06-05).
//
// Diagnostic-only. Env-gated via BOA_PHASE45_TRACE=1. When armed, snapshots
// representative slots of Thing.soaEnd1, Thing.soaCoord, Thing.soaUVec, and
// FilSegment.soaEnd1X at four ordered points inside one doLoop iteration:
//   1 = immediately after GPUMoveThing.poisonFrameOnlyMirrors()
//   2 = immediately before MyoMotor.fillSoaArrays / FilSegment.fillSoaArrays
//   3 = immediately after FilSegment.fillSoaArrays
//   4 = immediately before GPUMotorBinding.detectBindings()
//
// Poison state is detected by comparing the current value to a baseline
// captured at the very first snapshot:
//   - Thing.soaCoord[b] poisoned iff |now - baseline_coord[b]| > 0.5 um.
//   - Thing.soaUVec[b]  poisoned iff |now - baseline_uVec[b]|  > 0.5 um.
//   - Thing.soaEnd1[b]  poisoned iff |now - canonical_e1| > 0.5 um,
//     where canonical_e1 = baseline_coord[b] - 0.5*length*baseline_uVec[b].
//   - FilSegment.soaEnd1X[slot] poisoned iff |now - canonical_e1| > 0.5 um.
//
// The 0.5 um threshold is well above the natural per-step displacement
// (~7e-3 um/step at gliding velocities) and well below the +1.0 um poison.
public class Phase45Trace {
    public static final boolean ENABLED =
        "1".equals(System.getenv("BOA_PHASE45_TRACE"));
    public static final int    START_STEP = parseInt("BOA_PHASE45_TRACE_START", 200);
    public static final int    TRACE_STEPS = parseInt("BOA_PHASE45_TRACE_STEPS", 3);
    public static final double POISON_THRESH = 0.5;
    public static final int[]  SLOTS = {0, 1, 2};

    private static double[] baseCoord  = new double[SLOTS.length * 3];
    private static double[] baseUVec   = new double[SLOTS.length * 3];
    private static double[] canonE1    = new double[SLOTS.length * 3];
    private static boolean  baseSet    = false;
    private static int      stepsSeen  = 0;
    private static int      lastStepIdx = -1;

    private static int parseInt(String envVar, int def) {
        String v = System.getenv(envVar);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static int curStep() {
        // Env.counter is the step number (incremented in updateCounters()).
        return Env.counter;
    }

    private static boolean active() {
        if (!ENABLED) return false;
        int s = curStep();
        if (s < START_STEP) return false;
        if (stepsSeen >= TRACE_STEPS) return false;
        return true;
    }

    public static void snapshot(String label) {
        if (!active()) return;
        int step = curStep();
        if (step != lastStepIdx) {
            // New step boundary — only count if we have completed all four points
            // for the prior step; safer to just track unique step IDs.
            if (lastStepIdx >= 0) stepsSeen++;
            if (stepsSeen >= TRACE_STEPS) { lastStepIdx = step; return; }
            lastStepIdx = step;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[PHASE45_TRACE step=%d point=%s]", step, label));

        for (int k = 0; k < SLOTS.length; k++) {
            int slot = SLOTS[k];
            FilSegment fs = (slot < FilSegment.filSegmentCt) ? FilSegment.theFilSegments[slot] : null;
            if (fs == null) { sb.append(String.format(" s%d=NULL", slot)); continue; }
            int tn = fs.myThingNumber;
            int b = tn * 3;
            double cx = Thing.soaCoord[b];
            double cy = Thing.soaCoord[b + 1];
            double cz = Thing.soaCoord[b + 2];
            double ux = Thing.soaUVec[b];
            double uy = Thing.soaUVec[b + 1];
            double uz = Thing.soaUVec[b + 2];
            double e1x = Thing.soaEnd1[b];
            double e1y = Thing.soaEnd1[b + 1];
            double e1z = Thing.soaEnd1[b + 2];
            double sex = FilSegment.soaEnd1X[slot];
            double sey = FilSegment.soaEnd1Y[slot];
            double sez = FilSegment.soaEnd1Z[slot];
            double half = 0.5 * fs.length;

            if (!baseSet) {
                int kk = k * 3;
                baseCoord[kk]     = cx; baseCoord[kk + 1] = cy; baseCoord[kk + 2] = cz;
                baseUVec[kk]      = ux; baseUVec[kk + 1]  = uy; baseUVec[kk + 2]  = uz;
                canonE1[kk]       = cx - half * ux;
                canonE1[kk + 1]   = cy - half * uy;
                canonE1[kk + 2]   = cz - half * uz;
            }

            int kk = k * 3;
            double dCoord = Math.max(Math.max(Math.abs(cx - baseCoord[kk]),
                                              Math.abs(cy - baseCoord[kk + 1])),
                                     Math.abs(cz - baseCoord[kk + 2]));
            double dUVec  = Math.max(Math.max(Math.abs(ux - baseUVec[kk]),
                                              Math.abs(uy - baseUVec[kk + 1])),
                                     Math.abs(uz - baseUVec[kk + 2]));
            double dEnd1  = Math.max(Math.max(Math.abs(e1x - canonE1[kk]),
                                              Math.abs(e1y - canonE1[kk + 1])),
                                     Math.abs(e1z - canonE1[kk + 2]));
            double dSex   = Math.max(Math.max(Math.abs(sex - canonE1[kk]),
                                              Math.abs(sey - canonE1[kk + 1])),
                                     Math.abs(sez - canonE1[kk + 2]));

            char tE1 = (dEnd1  > POISON_THRESH) ? 'P' : '.';
            char tCx = (dCoord > POISON_THRESH) ? 'P' : '.';
            char tUx = (dUVec  > POISON_THRESH) ? 'P' : '.';
            char tSx = (dSex   > POISON_THRESH) ? 'P' : '.';

            sb.append(String.format(
                " s%d:e1[%c|%.4f,%.4f,%.4f|d=%.4f] c[%c|%.4f,%.4f,%.4f|d=%.4f] u[%c|%.4f,%.4f,%.4f|d=%.4f] sex[%c|%.4f,%.4f,%.4f|d=%.4f]",
                slot,
                tE1, e1x, e1y, e1z, dEnd1,
                tCx, cx, cy, cz, dCoord,
                tUx, ux, uy, uz, dUVec,
                tSx, sex, sey, sez, dSex));
        }
        if (!baseSet) baseSet = true;
        System.err.println(sb.toString());
    }
}
