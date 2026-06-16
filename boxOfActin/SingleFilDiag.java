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

    // Tip-flexibility mode (2026-06-15): reuse the bench chain as a CANTILEVER (one end pinned,
    // free tip) with Brownian ON, and accumulate the transverse (y,z) fluctuation variance of every
    // segment — the membrane-normal tip excursion the elastic ratchet rides. Sweeping benchmarkNSegs
    // × benchmarkMonomerCt at fixed contour length maps how the discretization (stdSegLength) and the
    // free tip's compliance scale. The membrane-normal projection is then sin^2(theta) × this.
    public static boolean TIPFLEX = false;
    private static double[] meanY, m2Y, meanZ, m2Z;  // per-segment Welford accumulators (transverse)
    private static long   wfN = 0;                    // Welford sample count (steps after warmup)
    private static long   warmupSteps = 0;            // skip equilibration transient before accumulating

    // Static-compliance sub-mode (BOA_TIPFLEX_FORCE>0): Brownian OFF, a known transverse force is
    // applied at the free tip, and the steady-state tip deflection gives k_eff = F/δ. The thermal tip
    // fluctuation then follows from equipartition: <y^2> = kT/k_eff (exact for the harmonic chain, no
    // statistics needed) — far cleaner than time-averaging the soft cantilever's long-correlation noise.
    public static boolean STATIC = false;
    public static double  STATIC_FORCE_PN = 0.0;      // applied tip force magnitude (pN, in -y)
    private static double tipYSum = 0;                // running sum of free-tip end2 y (um) post-warmup
    private static long   tipCount = 0;

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
        // Tip-flexibility characterization: BOA_TIPFLEX=1 enables the cantilever/Brownian/variance
        // mode (the makeInitialThings benchmark block leaves Brownian on and pins only the first end).
        // BOA_TIPFLEX_N / BOA_TIPFLEX_MON set the chain discretization; BOA_TIPFLEX_WARMUP skips the
        // equilibration transient before variance accumulation begins.
        String tipflexEnv = System.getenv("BOA_TIPFLEX");
        if (tipflexEnv != null && !tipflexEnv.isEmpty()
            && !tipflexEnv.equals("0") && !tipflexEnv.equalsIgnoreCase("false")) {
            TIPFLEX = true;
            ENABLED = true;
        }
        if (TIPFLEX) {
            Integer n = intEnv("BOA_TIPFLEX_N");
            if (n != null && n > 1) Env.benchmarkNSegs = n;
            Integer m = intEnv("BOA_TIPFLEX_MON");
            if (m != null && m > 0) Env.benchmarkMonomerCt = m;
            Integer w = intEnv("BOA_TIPFLEX_WARMUP");
            if (w != null && w >= 0) warmupSteps = w;
            String fEnv = System.getenv("BOA_TIPFLEX_FORCE");
            if (fEnv != null && !fEnv.isEmpty()) {
                try { double f = Double.parseDouble(fEnv.trim()); if (f > 0) { STATIC_FORCE_PN = f; STATIC = true; } }
                catch (NumberFormatException ignored) {}
            }
            System.err.printf("[TIPFLEX] %s mode: nSegs=%d monCt=%d warmup=%d%s%n",
                STATIC ? "STATIC-compliance" : "fluctuation", Env.benchmarkNSegs, Env.benchmarkMonomerCt, warmupSteps,
                STATIC ? (" tipForce=" + STATIC_FORCE_PN + "pN") : "");
        }

        if (ENABLED) {
            System.err.println("[SINGLE_FIL_DIAG] enabled; reportInterval=" + reportInterval);
        }
    }

    private static Integer intEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) return null;
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
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
        // Static compliance: average the free-tip end2 y deflection after the warmup (deterministic).
        if (STATIC && stepCount > warmupSteps) {
            tipYSum += segs[n - 1].getEnd2Y();
            tipCount++;
        }
        // Tip-flexibility: accumulate per-segment transverse (y,z) variance after the warmup.
        if (TIPFLEX && !STATIC && stepCount > warmupSteps) {
            if (meanY == null || meanY.length != n) {
                meanY = new double[n]; m2Y = new double[n];
                meanZ = new double[n]; m2Z = new double[n]; wfN = 0;
            }
            wfN++;
            for (int i = 0; i < n; i++) {
                double cy = segs[i].getCoordY(), cz = segs[i].getCoordZ();
                double dy = cy - meanY[i]; meanY[i] += dy / wfN; m2Y[i] += dy * (cy - meanY[i]);
                double dz = cz - meanZ[i]; meanZ[i] += dz / wfN; m2Z[i] += dz * (cz - meanZ[i]);
            }
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
        System.out.println("  verdict          = " + (pass ? "PASS" : "FAIL") + (TIPFLEX ? " (N/A in tipflex mode)" : ""));
        if (STATIC && tipCount > 0) {
            int n = (BoxOfActin.deflFil != null && BoxOfActin.deflFil.segs != null) ? BoxOfActin.deflFil.segs.length : 0;
            int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
            double span = BoxOfActin.deflFil != null ? BoxOfActin.deflFil.chainSpanMicrons : Double.NaN;
            double F = STATIC_FORCE_PN * 1e-12;                 // N
            double deflM = Math.abs(tipYSum / tipCount) * 1e-6; // m (initial tip y ~ 0; force in -y)
            double kEff = F / deflM;                             // N/m, 1D transverse tip stiffness
            double var1D = Env.Boltz * Env.tempK / kEff;         // m^2 (equipartition)
            double rms1D_nm = Math.sqrt(var1D) * 1e9;
            System.out.println("  --- TIPFLEX STATIC (cantilever compliance, Brownian off) ---");
            System.out.printf("[TIPFLEX_STATIC] nSegs=%d monCt=%d contourUm=%.4f F_pN=%.5f tipDefl_nm=%.4f kEff_Npm=%.4e tipSigma1D_nm=%.3f%n",
                n, monCt, span, STATIC_FORCE_PN, deflM * 1e9, kEff, rms1D_nm);
        }
        if (TIPFLEX && !STATIC && meanY != null && wfN > 1) {
            int n = meanY.length;
            int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
            double span = BoxOfActin.deflFil != null ? BoxOfActin.deflFil.chainSpanMicrons : Double.NaN;
            System.out.println("  --- TIPFLEX (cantilever transverse fluctuation) ---");
            System.out.printf("  nSegs=%d monCt=%d contour=%.4f um  wfN=%d%n", n, monCt, span, wfN);
            // Per-segment RMS transverse displacement profile (root-clamp i=0 -> free tip i=n-1).
            System.out.print("  RMS_perp[nm] by seg:");
            for (int i = 0; i < n; i++) {
                double rms = Math.sqrt((m2Y[i] + m2Z[i]) / wfN);   // 2D transverse RMS (nm; coords are um)
                System.out.printf(" %.2f", rms * 1e3);
            }
            System.out.println();
            // Free tip = last segment (only the first end is pinned in tipflex mode).
            int t = n - 1;
            double varY = m2Y[t] / wfN, varZ = m2Z[t] / wfN;
            double sigma2D = Math.sqrt(varY + varZ) * 1e3;            // nm, full transverse
            double sigma1D = Math.sqrt((varY + varZ) / 2.0) * 1e3;    // nm, per-axis (membrane-normal projection uses sin(theta)*this)
            // machine-parseable line for cross-run aggregation
            System.out.printf("[TIPFLEX_RESULT] nSegs=%d monCt=%d contourUm=%.4f tipSigma2D_nm=%.3f tipSigma1D_nm=%.3f tipVarY_nm2=%.4f tipVarZ_nm2=%.4f wfN=%d%n",
                n, monCt, span, sigma2D, sigma1D, varY * 1e6, varZ * 1e6, wfN);
        }
        System.out.println("=================================================================");
        System.out.flush();
    }
}
