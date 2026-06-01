package boxOfActin;

// SingleMyoDiag (2026-05-31 pivot): minimal-system reproduction diagnostic.
// Characterizes the thermal conformational ensemble of ONE anchored myosin
// with no filaments and no binding. Built to answer the question that seven
// joint-only diagnostics never addressed: does an isolated myosin diverge
// between CPU and GPU, or does the divergence require binding?
//
// The J2=0.1 entry (2026-06-01) showed that with theta_RL converged
// (1.3 deg gap) AND theta_LM always converged (~4e-4 rad), the myosin's
// INTERNAL shape is nearly identical CPU vs GPU — yet motor-head height
// still differs by 55 nm. Internal joint angles cannot move the head while
// fixed; only the ABSOLUTE pose of the assembly (the rod's tilt in lab frame)
// can. This diagnostic measures absolute pose explicitly.
//
// Activated by env var BOA_DIAG_SINGLE_MYO=1. Sampling interval defaults to
// 10 steps; override via BOA_DIAG_SINGLE_MYO_INTERVAL=N.
//
// Companion to the singleMyoDiag parameter (Env.java) which sets up the
// one-myosin / zero-filament topology. The two are independent: the
// parameter controls scene setup, the env var controls measurement.

public class SingleMyoDiag {

    public static boolean ENABLED = false;
    public static int SAMPLE_INTERVAL = 10;

    private static int stepCounter = 0;
    private static long n = 0;
    private static long histN = 0;

    // Welford accumulators — internal joint angles.
    private static double mThetaLM, m2ThetaLM;
    private static double mThetaRL, m2ThetaRL;

    // Welford — absolute orientation tilt (angle vs +z lab axis, the
    // surface normal at the anchor plane).
    private static double mRodTilt,   m2RodTilt;
    private static double mLeverTilt, m2LeverTilt;
    private static double mMotorTilt, m2MotorTilt;

    // Welford — head (motor end2) position in lab frame.
    private static double mHeadX, m2HeadX;
    private static double mHeadY, m2HeadY;
    private static double mHeadZ, m2HeadZ;
    private static double mHeadR, m2HeadR;       // radial = sqrt(x^2 + y^2)
    private static double maxHeadR;

    // Histograms.
    private static final int ANG_BINS = 180;     // 1-degree bins for angles
    private static final double ANG_BIN_DEG = 1.0;
    private static final int[] hThetaRL   = new int[ANG_BINS];
    private static final int[] hThetaLM   = new int[ANG_BINS];
    private static final int[] hRodTilt   = new int[ANG_BINS];
    private static final int[] hLeverTilt = new int[ANG_BINS];
    private static final int[] hMotorTilt = new int[ANG_BINS];

    private static final double HEADZ_MIN = -0.20, HEADZ_MAX = 0.20;
    private static final int HEADZ_BINS = 80;    // 5 nm bins across -200..+200 nm
    private static final int[] hHeadZ = new int[HEADZ_BINS];

    private static final double HEADR_MIN = 0.0, HEADR_MAX = 0.20;
    private static final int HEADR_BINS = 40;    // 5 nm bins, 0..200 nm
    private static final int[] hHeadR = new int[HEADR_BINS];

    // Cocked-state population tally (cocked vs uncocked counts of samples).
    private static long nCocked = 0;

    public static void initFromEnv() {
        String v = System.getenv("BOA_DIAG_SINGLE_MYO");
        if (v != null && !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("false")) {
            ENABLED = true;
        }
        String iv = System.getenv("BOA_DIAG_SINGLE_MYO_INTERVAL");
        if (iv != null && !iv.isEmpty()) {
            try { SAMPLE_INTERVAL = Integer.parseInt(iv); } catch (NumberFormatException ignored) {}
        }
        if (ENABLED) {
            System.err.println("[SINGLE_MYO_DIAG] enabled (sample interval = " + SAMPLE_INTERVAL + " steps)");
        }
    }

    public static void sample() {
        if (!ENABLED) return;
        stepCounter++;
        if (stepCounter % SAMPLE_INTERVAL != 0) return;
        if (Myosin.myoCt == 0) return;
        Myosin myo = Myosin.theMyosins[0];
        if (myo == null) return;
        MyoMotor mt = myo.myoMotor;
        MyoLever lv = myo.myoLever;
        MyoRod   rd = myo.myoRod;
        if (mt == null || lv == null || rd == null) return;

        double mux = mt.getUVecX(), muy = mt.getUVecY(), muz = mt.getUVecZ();
        double lux = lv.getUVecX(), luy = lv.getUVecY(), luz = lv.getUVecZ();
        double rux = rd.getUVecX(), ruy = rd.getUVecY(), ruz = rd.getUVecZ();

        // Internal joint angles (rad).
        double dotLM = lux*mux + luy*muy + luz*muz;
        double dotRL = rux*lux + ruy*luy + ruz*luz;
        if (dotLM >  1.0) dotLM =  1.0; else if (dotLM < -1.0) dotLM = -1.0;
        if (dotRL >  1.0) dotRL =  1.0; else if (dotRL < -1.0) dotRL = -1.0;
        double thetaLM = Math.acos(dotLM);
        double thetaRL = Math.acos(dotRL);

        // Absolute tilt relative to +z (the surface-normal axis at the
        // anchor plane). acos(uVec.z) gives the polar angle in [0, pi].
        double rodCosZ   = ruz; if (rodCosZ >  1.0) rodCosZ =  1.0; else if (rodCosZ < -1.0) rodCosZ = -1.0;
        double leverCosZ = luz; if (leverCosZ >  1.0) leverCosZ =  1.0; else if (leverCosZ < -1.0) leverCosZ = -1.0;
        double motorCosZ = muz; if (motorCosZ >  1.0) motorCosZ =  1.0; else if (motorCosZ < -1.0) motorCosZ = -1.0;
        double rodTilt   = Math.acos(rodCosZ);
        double leverTilt = Math.acos(leverCosZ);
        double motorTilt = Math.acos(motorCosZ);

        // Head position (motor end2 = distal motor head tip).
        double hx = mt.getEnd2X();
        double hy = mt.getEnd2Y();
        double hz = mt.getEnd2Z();
        double hr = Math.sqrt(hx*hx + hy*hy);

        // Welford updates.
        n++;
        double d;
        d = thetaLM   - mThetaLM;   mThetaLM   += d / n; m2ThetaLM   += d * (thetaLM   - mThetaLM);
        d = thetaRL   - mThetaRL;   mThetaRL   += d / n; m2ThetaRL   += d * (thetaRL   - mThetaRL);
        d = rodTilt   - mRodTilt;   mRodTilt   += d / n; m2RodTilt   += d * (rodTilt   - mRodTilt);
        d = leverTilt - mLeverTilt; mLeverTilt += d / n; m2LeverTilt += d * (leverTilt - mLeverTilt);
        d = motorTilt - mMotorTilt; mMotorTilt += d / n; m2MotorTilt += d * (motorTilt - mMotorTilt);
        d = hx        - mHeadX;     mHeadX     += d / n; m2HeadX     += d * (hx        - mHeadX);
        d = hy        - mHeadY;     mHeadY     += d / n; m2HeadY     += d * (hy        - mHeadY);
        d = hz        - mHeadZ;     mHeadZ     += d / n; m2HeadZ     += d * (hz        - mHeadZ);
        d = hr        - mHeadR;     mHeadR     += d / n; m2HeadR     += d * (hr        - mHeadR);
        if (hr > maxHeadR) maxHeadR = hr;
        if (mt.isCocked()) nCocked++;

        // Histograms.
        histAdd(hThetaLM,   thetaLM   * 180.0 / Math.PI);
        histAdd(hThetaRL,   thetaRL   * 180.0 / Math.PI);
        histAdd(hRodTilt,   rodTilt   * 180.0 / Math.PI);
        histAdd(hLeverTilt, leverTilt * 180.0 / Math.PI);
        histAdd(hMotorTilt, motorTilt * 180.0 / Math.PI);
        histAddRange(hHeadZ, hz, HEADZ_MIN, HEADZ_MAX, HEADZ_BINS);
        histAddRange(hHeadR, hr, HEADR_MIN, HEADR_MAX, HEADR_BINS);
        histN++;
    }

    private static void histAdd(int[] hist, double valDeg) {
        if (valDeg < 0) valDeg = 0;
        if (valDeg >= ANG_BINS) valDeg = ANG_BINS - 1e-9;
        int bin = (int)(valDeg / ANG_BIN_DEG);
        if (bin < 0) bin = 0; else if (bin >= ANG_BINS) bin = ANG_BINS - 1;
        hist[bin]++;
    }

    private static void histAddRange(int[] hist, double v, double lo, double hi, int bins) {
        double span = hi - lo;
        if (v <= lo) { hist[0]++; return; }
        if (v >= hi) { hist[bins - 1]++; return; }
        int bin = (int)((v - lo) / span * bins);
        if (bin < 0) bin = 0; else if (bin >= bins) bin = bins - 1;
        hist[bin]++;
    }

    public static void dump() {
        if (!ENABLED) return;
        if (n == 0) {
            System.out.println("[SINGLE_MYO_DIAG] No samples accumulated.");
            return;
        }
        String config;
        if (GPUMoveThing.DIAG_CPU_JOINTS) config = "DOUBLE (CPU joints, double precision)";
        else if (Env.useGPU)              config = "FLOAT32 (GPU joints kernel)";
        else                              config = "CPU baseline (double, no GPU)";

        double sdLM      = Math.sqrt(m2ThetaLM   / n);
        double sdRL      = Math.sqrt(m2ThetaRL   / n);
        double sdRodT    = Math.sqrt(m2RodTilt   / n);
        double sdLeverT  = Math.sqrt(m2LeverTilt / n);
        double sdMotorT  = Math.sqrt(m2MotorTilt / n);
        double sdHX      = Math.sqrt(m2HeadX     / n);
        double sdHY      = Math.sqrt(m2HeadY     / n);
        double sdHZ      = Math.sqrt(m2HeadZ     / n);
        double sdHR      = Math.sqrt(m2HeadR     / n);
        double cockedFr  = (double) nCocked / n;

        System.out.println();
        System.out.println("=== SINGLE MYOSIN THERMAL CHARACTERIZATION [config: " + config + "] ===");
        System.out.printf("samples:           n=%d   interval=%d steps%n", n, SAMPLE_INTERVAL);
        System.out.printf("cocked fraction:   %.4f%n", cockedFr);
        System.out.printf("J2 (rod-lever):    %.4f   J1 (lever-motor): %.4f%n",
            Env.myoJ2FracMoveTorq.getValue(), Env.myoJ1FracMoveTorq.getValue());
        System.out.println();
        System.out.printf("theta_RL  mean   %.6f rad   (= %.3f deg)%n", mThetaRL, mThetaRL * 180.0 / Math.PI);
        System.out.printf("theta_RL  SD     %.6f%n", sdRL);
        System.out.printf("theta_LM  mean   %.6f rad   (= %.3f deg)%n", mThetaLM, mThetaLM * 180.0 / Math.PI);
        System.out.printf("theta_LM  SD     %.6f%n", sdLM);
        System.out.println();
        System.out.printf("rod   tilt  mean %.6f rad   (= %.3f deg)   SD %.6f%n",
            mRodTilt, mRodTilt * 180.0 / Math.PI, sdRodT);
        System.out.printf("lever tilt  mean %.6f rad   (= %.3f deg)   SD %.6f%n",
            mLeverTilt, mLeverTilt * 180.0 / Math.PI, sdLeverT);
        System.out.printf("motor tilt  mean %.6f rad   (= %.3f deg)   SD %.6f%n",
            mMotorTilt, mMotorTilt * 180.0 / Math.PI, sdMotorT);
        System.out.println();
        System.out.printf("head_x  mean %.6f um   SD %.6f%n", mHeadX, sdHX);
        System.out.printf("head_y  mean %.6f um   SD %.6f%n", mHeadY, sdHY);
        System.out.printf("head_z  mean %.6f um   SD %.6f%n", mHeadZ, sdHZ);
        System.out.printf("head_r  mean %.6f um   SD %.6f   max %.6f%n", mHeadR, sdHR, maxHeadR);

        // Histograms.
        System.out.println();
        dumpAngHist("theta_RL",   hThetaRL);
        dumpAngHist("theta_LM",   hThetaLM);
        dumpAngHist("rod_tilt",   hRodTilt);
        dumpAngHist("lever_tilt", hLeverTilt);
        dumpAngHist("motor_tilt", hMotorTilt);
        dumpRangeHist("head_z (um)", hHeadZ, HEADZ_MIN, HEADZ_MAX, HEADZ_BINS);
        dumpRangeHist("head_r (um)", hHeadR, HEADR_MIN, HEADR_MAX, HEADR_BINS);

        System.out.println("=== END SINGLE MYOSIN THERMAL CHARACTERIZATION ===");
        System.out.flush();
    }

    private static void dumpAngHist(String name, int[] hist) {
        System.out.println("histogram " + name + " (deg, 1 deg bins, skipping empty):");
        long total = 0;
        for (int c : hist) total += c;
        if (total == 0) { System.out.println("  (empty)"); return; }
        for (int i = 0; i < hist.length; i++) {
            if (hist[i] == 0) continue;
            System.out.printf("  %3d-%3d deg : %10d  (%.4f)%n",
                i, i + 1, hist[i], (double) hist[i] / total);
        }
    }

    private static void dumpRangeHist(String name, int[] hist, double lo, double hi, int bins) {
        System.out.println("histogram " + name + " (skipping empty):");
        long total = 0;
        for (int c : hist) total += c;
        if (total == 0) { System.out.println("  (empty)"); return; }
        double span = hi - lo;
        for (int i = 0; i < bins; i++) {
            if (hist[i] == 0) continue;
            double binLo = lo + i * span / bins;
            double binHi = lo + (i + 1) * span / bins;
            System.out.printf("  [% .5f, % .5f) : %10d  (%.4f)%n",
                binLo, binHi, hist[i], (double) hist[i] / total);
        }
    }

    public static void reset() {
        stepCounter = 0;
        n = 0;
        histN = 0;
        mThetaLM = m2ThetaLM = 0;
        mThetaRL = m2ThetaRL = 0;
        mRodTilt = m2RodTilt = 0;
        mLeverTilt = m2LeverTilt = 0;
        mMotorTilt = m2MotorTilt = 0;
        mHeadX = m2HeadX = 0;
        mHeadY = m2HeadY = 0;
        mHeadZ = m2HeadZ = 0;
        mHeadR = m2HeadR = 0;
        maxHeadR = 0;
        nCocked = 0;
        for (int i = 0; i < ANG_BINS; i++) {
            hThetaLM[i] = 0; hThetaRL[i] = 0;
            hRodTilt[i] = 0; hLeverTilt[i] = 0; hMotorTilt[i] = 0;
        }
        for (int i = 0; i < HEADZ_BINS; i++) hHeadZ[i] = 0;
        for (int i = 0; i < HEADR_BINS; i++) hHeadR[i] = 0;
    }
}
