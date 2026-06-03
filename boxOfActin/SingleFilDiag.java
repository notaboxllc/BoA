package boxOfActin;

// SingleFilDiag (2026-06-02 Phase 2 F3/F4): minimal-system reproduction for the
// chain F3/F4 device port — the F3/F4 analogue of SingleMyoDiag.
//
// Activated by the -singleFilDiag CLI flag (BoxOfActin.begin). Reuses the
// deflection benchmark's pinned-end straight chain (FilSegment.makeBenchmarkChain
// builds an 11-segment line along +X with both ends pinned), but turns the
// midpoint transverse force OFF (benchmarkForceOn = 0) and per-segment
// Brownian is already off via FilSegment.brownianOff = true. With no external
// force and pinned ends, the chain MUST stay numerically straight — every
// segment center's y and z deviation from the pin line should stay near
// machine precision.
//
// Any drift in y or z is direct evidence of an F3/F4 bug — either a
// missing force, a sign error, or a magnitude mismatch in the kernel. The
// gate is mechanical (per-step coordinate check), not statistical (no
// EWMA / Welford / ensemble), so a one-arm run can detect a regression
// without needing a baseline distribution. Lesson 3 + Lesson 5: minimal
// isolated system, observable sensitive to the exact subsystem changed.
//
// A/B usage: arm A (default) runs with the device chainPairForcesKernel
// active; arm B (BOA_DIAG_CPU_F3F4=1) runs with the device kernel forced
// off and the CPU pair restored. PASS criterion: arm A's max|y|, max|z|
// stay below ~1e-5 µm over 100k steps, and arm A vs arm B agree to
// within float32 trajectory noise (~1e-5 µm in this regime).

public class SingleFilDiag {

    public static boolean ENABLED = false;

    private static int reportInterval = 5000;
    private static int stepCount = 0;

    private static double maxAbsY = 0.0;
    private static double maxAbsZ = 0.0;
    private static long   nSamples = 0;
    private static int    worstSegIdx = -1;
    private static int    worstSegStep = -1;
    private static double worstSegValue = 0.0;
    private static char   worstSegAxis = ' ';

    // Pinning baseline: ends are clamped to anchor1/anchor2 (in the
    // benchmark's pin-correction pass), so the y and z of every segment
    // SHOULD be zero (anchors and the initial chain both live on y=z=0
    // for the standard bench chain).

    public static void initFromEnv() {
        // Mirror SingleMyoDiag's env-var hook so jba can also enable the
        // diagnostic without the -singleFilDiag flag (useful for one-off
        // characterization of an already-configured bench run).
        String enabledEnv = System.getenv("BOA_DIAG_SINGLE_FIL");
        if (enabledEnv != null && !enabledEnv.isEmpty()
            && !enabledEnv.equals("0") && !enabledEnv.equalsIgnoreCase("false")) {
            ENABLED = true;
        }
        String intervalEnv = System.getenv("BOA_DIAG_SINGLE_FIL_INTERVAL");
        if (intervalEnv != null && !intervalEnv.isEmpty()) {
            try {
                int n = Integer.parseInt(intervalEnv.trim());
                if (n > 0) reportInterval = n;
            } catch (NumberFormatException ignored) {}
        }
        if (ENABLED) {
            System.err.println("[SINGLE_FIL_DIAG] enabled; reportInterval=" + reportInterval);
        }
    }

    // Called from BoxOfActin.doLoop() after each step (alongside SingleMyoDiag.sample).
    // O(nSegs) work, sub-microsecond for the 11-segment bench chain.
    public static void sample() {
        if (!ENABLED) return;
        BoxOfActin.DeflFil df = BoxOfActin.deflFil;
        if (df == null || df.segs == null) return;
        stepCount++;
        FilSegment[] segs = df.segs;
        int n = segs.length;
        for (int i = 0; i < n; i++) {
            double cy = segs[i].getCoordY();
            double cz = segs[i].getCoordZ();
            double ay = Math.abs(cy);
            double az = Math.abs(cz);
            if (ay > maxAbsY) {
                maxAbsY = ay;
                if (ay > worstSegValue) {
                    worstSegValue = ay;
                    worstSegIdx = i;
                    worstSegStep = stepCount;
                    worstSegAxis = 'y';
                }
            }
            if (az > maxAbsZ) {
                maxAbsZ = az;
                if (az > worstSegValue) {
                    worstSegValue = az;
                    worstSegIdx = i;
                    worstSegStep = stepCount;
                    worstSegAxis = 'z';
                }
            }
            nSamples++;
        }
        if (stepCount % reportInterval == 0) {
            System.out.printf("[SINGLE_FIL_DIAG] step=%d  maxAbsY=%.3e µm  maxAbsZ=%.3e µm  worst=seg%d.%c=%.3e µm at step %d%n",
                stepCount, maxAbsY, maxAbsZ, worstSegIdx, worstSegAxis,
                worstSegValue, worstSegStep);
            System.out.flush();
        }
    }

    // Print the final summary on shutdown (called from BoxOfActin.reportSingleFilDiagFinal).
    public static void reportFinal() {
        if (!ENABLED) return;
        System.out.println("=================================================================");
        System.out.println("[SINGLE_FIL_DIAG] FINAL:");
        System.out.printf("  total steps      = %d%n", stepCount);
        System.out.printf("  samples          = %d%n", nSamples);
        System.out.printf("  max |coord.y|    = %.6e µm%n", maxAbsY);
        System.out.printf("  max |coord.z|    = %.6e µm%n", maxAbsZ);
        System.out.printf("  worst segment    = idx %d, axis %c, |value| = %.6e µm at step %d%n",
            worstSegIdx, worstSegAxis, worstSegValue, worstSegStep);
        System.out.println("  pass criterion   = max < 1.0e-5 µm");
        boolean pass = maxAbsY < 1.0e-5 && maxAbsZ < 1.0e-5;
        System.out.println("  verdict          = " + (pass ? "PASS" : "FAIL"));
        System.out.println("=================================================================");
        System.out.flush();
    }
}
