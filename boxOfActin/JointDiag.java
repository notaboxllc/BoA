package boxOfActin;

// JointDiag (2026-05-31): instrumentation to measure intermediate
// conformational quantities (joint angles, head height, joint elastic
// energy) for the float32-vs-double joint precision diagnostic.
//
// Sampling reads CURRENT pose state every SAMPLE_INTERVAL steps using
// double-precision math regardless of how forces are computed upstream.
// The same measurement code runs in both configs so any difference in
// the dumped distributions is caused purely by the upstream joint-force
// precision difference (CPU-double vs GPU-float32).
//
// Gated by ENABLED (default false). Set BOA_DIAG_JOINT_STATS=1 in the
// environment to enable for a run.
public class JointDiag {

    public static boolean ENABLED = false;
    public static int SAMPLE_INTERVAL = 50;

    private static int stepCounter = 0;

    // Welford accumulators (mean, M2 for variance) over all sampled
    // (motor, time) tuples.
    private static long n = 0;
    private static double mThetaLM, m2ThetaLM;
    private static double mThetaRL, m2ThetaRL;
    private static double mHeadZ,   m2HeadZ;
    private static double mEjoint,  m2Ejoint;

    // E_joint trend: per-sample mean across motors, kept as first/last
    // pair plus a midpoint to detect monotonic drift over the run.
    private static double ejointFirst = Double.NaN, ejointMid = Double.NaN, ejointLast = Double.NaN;
    private static int firstSampleStep = -1, midSampleStep = -1, lastSampleStep = -1;
    private static int sampleCount = 0;

    // 2026-05-31 pose-normalization diagnostic — tests whether float32 pose
    // storage drifts off unit magnitude or off orthogonality over the run.
    // |uVec|, |yVec| should both stay ≈ 1.0; uVec·yVec should stay ≈ 0.
    // Welford accumulators over all (segment × sample) tuples; max deviation
    // captures the worst-case drift, mean captures the central tendency.
    // posN counts every measured pose (3 per Myosin: rod, lever, motor).
    private static long posN = 0;
    private static double mAbsU, m2AbsU, maxDevU;       // |uVec|
    private static double mAbsY, m2AbsY, maxDevY;       // |yVec|
    private static double mAbsDot, m2AbsDot, maxAbsDot; // |uVec · yVec|

    // Drift trackers: first and last per-sample-mean of |uVec|, for the
    // step-0 → step-N comparison the planner asked for.
    private static double absUFirst = Double.NaN, absULast = Double.NaN;
    private static double absDotFirst = Double.NaN, absDotLast = Double.NaN;

    public static void initFromEnv() {
        String v = System.getenv("BOA_DIAG_JOINT_STATS");
        if (v != null && !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("false")) {
            ENABLED = true;
            System.err.println("[JOINT_DIAG] enabled (sample interval = " + SAMPLE_INTERVAL + " steps)");
        }
        String iv = System.getenv("BOA_DIAG_JOINT_STATS_INTERVAL");
        if (iv != null && !iv.isEmpty()) {
            try { SAMPLE_INTERVAL = Integer.parseInt(iv); } catch (NumberFormatException ignored) {}
        }
    }

    public static void sample() {
        if (!ENABLED) return;
        stepCounter++;
        if (stepCounter % SAMPLE_INTERVAL != 0) return;

        double dt = Env.deltaT.getValue();
        double j1 = Env.myoJ1FracMoveTorq.getValue();
        double j2 = Env.myoJ2FracMoveTorq.getValue();
        double restLM_uncocked = Math.toRadians(Myosin.uncockedLever_MotorAngle);
        double restLM_cocked   = Math.toRadians(Myosin.cockedLever_MotorAngle);

        double sumE = 0;
        long   cntE = 0;
        double sumAbsUSample = 0, sumAbsDotSample = 0;
        long   sumPosSample = 0;

        for (int i = 0; i < Myosin.myoCt; i++) {
            Myosin myo = Myosin.theMyosins[i];
            if (myo == null) continue;
            MyoMotor mt = myo.myoMotor;
            MyoLever lv = myo.myoLever;
            MyoRod   rd = myo.myoRod;
            if (mt == null || lv == null || rd == null) continue;

            // Cast float SoA pose to double for measurement-grade math.
            double mux = mt.getUVecX(), muy = mt.getUVecY(), muz = mt.getUVecZ();
            double lux = lv.getUVecX(), luy = lv.getUVecY(), luz = lv.getUVecZ();
            double rux = rd.getUVecX(), ruy = rd.getUVecY(), ruz = rd.getUVecZ();

            // Pose-normalization measurement (rod + lever + motor):
            // |uVec|, |yVec|, |uVec·yVec| for each of the three segments.
            double myx = mt.getYVecX(), myy = mt.getYVecY(), myz = mt.getYVecZ();
            double lyx = lv.getYVecX(), lyy = lv.getYVecY(), lyz = lv.getYVecZ();
            double ryx = rd.getYVecX(), ryy = rd.getYVecY(), ryz = rd.getYVecZ();

            double[] absU = new double[3];
            double[] absY = new double[3];
            double[] absDot = new double[3];
            absU[0] = Math.sqrt(mux*mux + muy*muy + muz*muz);
            absU[1] = Math.sqrt(lux*lux + luy*luy + luz*luz);
            absU[2] = Math.sqrt(rux*rux + ruy*ruy + ruz*ruz);
            absY[0] = Math.sqrt(myx*myx + myy*myy + myz*myz);
            absY[1] = Math.sqrt(lyx*lyx + lyy*lyy + lyz*lyz);
            absY[2] = Math.sqrt(ryx*ryx + ryy*ryy + ryz*ryz);
            absDot[0] = Math.abs(mux*myx + muy*myy + muz*myz);
            absDot[1] = Math.abs(lux*lyx + luy*lyy + luz*lyz);
            absDot[2] = Math.abs(rux*ryx + ruy*ryy + ruz*ryz);
            for (int s = 0; s < 3; s++) {
                posN++;
                double devU = Math.abs(absU[s] - 1.0);
                double dAU  = absU[s]   - mAbsU;   mAbsU   += dAU / posN; m2AbsU   += dAU * (absU[s]   - mAbsU);
                if (devU > maxDevU) maxDevU = devU;
                double devY = Math.abs(absY[s] - 1.0);
                double dAY  = absY[s]   - mAbsY;   mAbsY   += dAY / posN; m2AbsY   += dAY * (absY[s]   - mAbsY);
                if (devY > maxDevY) maxDevY = devY;
                double dAD  = absDot[s] - mAbsDot; mAbsDot += dAD / posN; m2AbsDot += dAD * (absDot[s] - mAbsDot);
                if (absDot[s] > maxAbsDot) maxAbsDot = absDot[s];
                sumAbsUSample += absU[s];
                sumAbsDotSample += absDot[s];
                sumPosSample++;
            }

            double dotLM = lux*mux + luy*muy + luz*muz;
            double dotRL = rux*lux + ruy*luy + ruz*luz;
            if (dotLM >  1.0) dotLM =  1.0; else if (dotLM < -1.0) dotLM = -1.0;
            if (dotRL >  1.0) dotRL =  1.0; else if (dotRL < -1.0) dotRL = -1.0;
            double thetaLM = Math.acos(dotLM);
            double thetaRL = Math.acos(dotRL);

            double restLM = mt.isCocked() ? restLM_cocked : restLM_uncocked;
            double angD_LM = thetaLM - restLM;
            double angD_RL = thetaRL; // rod-lever rest = 0

            // Effective rotational spring constants derived from the
            // overdamped torsionMag formula:
            //   torsionMag = j * angD_rad / ((1/gMm + 1/gMl) * dt)
            // → effK = j / ((1/gMm + 1/gMl) * dt)  [N·m/rad]
            double effK_LM = j1 / ((1.0/mt.bRotGam.y + 1.0/lv.bRotGam.y) * dt);
            double effK_RL = j2 / ((1.0/lv.bRotGam.y + 1.0/rd.bRotGam.y) * dt);
            double eJoint  = 0.5 * effK_LM * angD_LM * angD_LM
                           + 0.5 * effK_RL * angD_RL * angD_RL;

            double headZ = mt.getEnd2Z();

            // Welford updates (one stream over all motors and times)
            n++;
            double dLM = thetaLM - mThetaLM; mThetaLM += dLM / n; m2ThetaLM += dLM * (thetaLM - mThetaLM);
            double dRL = thetaRL - mThetaRL; mThetaRL += dRL / n; m2ThetaRL += dRL * (thetaRL - mThetaRL);
            double dHZ = headZ   - mHeadZ;   mHeadZ   += dHZ / n; m2HeadZ   += dHZ * (headZ   - mHeadZ);
            double dEJ = eJoint  - mEjoint;  mEjoint  += dEJ / n; m2Ejoint  += dEJ * (eJoint  - mEjoint);

            sumE += eJoint;
            cntE++;
        }

        if (cntE > 0) {
            double meanE = sumE / cntE;
            sampleCount++;
            if (firstSampleStep < 0) { firstSampleStep = stepCounter; ejointFirst = meanE; }
            lastSampleStep = stepCounter; ejointLast = meanE;
            // Update midpoint estimate: store value seen at the chronological middle
            // of all samples so far (sample index = sampleCount/2). Cheap and adequate
            // for "is energy drifting" detection.
            if (sampleCount == 1 || sampleCount == 2) { midSampleStep = stepCounter; ejointMid = meanE; }
        }

        // Per-sample drift trackers for the pose-normalization diagnostic.
        if (sumPosSample > 0) {
            double meanAbsU = sumAbsUSample / sumPosSample;
            double meanAbsDot = sumAbsDotSample / sumPosSample;
            if (Double.isNaN(absUFirst)) { absUFirst = meanAbsU; absDotFirst = meanAbsDot; }
            absULast = meanAbsU;
            absDotLast = meanAbsDot;
        }
    }

    public static void dump() {
        if (!ENABLED) return;
        if (n == 0) {
            System.out.println("[JOINT_DIAG] No samples accumulated.");
            return;
        }
        String config;
        if (GPUMoveThing.DIAG_CPU_JOINTS) config = "DOUBLE (CPU joints, double precision)";
        else if (Env.useGPU)              config = "FLOAT32 (GPU joints kernel)";
        else                              config = "CPU baseline (double, no GPU)";

        double sdLM = Math.sqrt(m2ThetaLM / n);
        double sdRL = Math.sqrt(m2ThetaRL / n);
        double sdHZ = Math.sqrt(m2HeadZ   / n);
        double sdEJ = Math.sqrt(m2Ejoint  / n);
        double restLM_uncocked = Math.toRadians(Myosin.uncockedLever_MotorAngle);
        double restLM_cocked   = Math.toRadians(Myosin.cockedLever_MotorAngle);
        double slope = Double.NaN;
        if (lastSampleStep > firstSampleStep) {
            slope = (ejointLast - ejointFirst) / (lastSampleStep - firstSampleStep);
        }

        System.out.println();
        System.out.println("=== JOINT DIAGNOSTIC [config: " + config + "] ===");
        System.out.printf("samples:        n=%d  (motor x time)  sampleEvents=%d  interval=%d steps%n",
            n, sampleCount, SAMPLE_INTERVAL);
        System.out.printf("theta_LM:       mean=%.6f rad  SD=%.6f  (rest_uncocked=%.4f, rest_cocked=%.4f)%n",
            mThetaLM, sdLM, restLM_uncocked, restLM_cocked);
        System.out.printf("theta_RL:       mean=%.6f rad  SD=%.6f  (rest=0.0000)%n",
            mThetaRL, sdRL);
        System.out.printf("head_z:         mean=%.6f um   SD=%.6f%n",
            mHeadZ, sdHZ);
        System.out.printf("E_joint:        mean=%.4e J    SD=%.4e%n",
            mEjoint, sdEJ);
        System.out.printf("E_joint trend:  step %d -> %d -> %d : %.4e -> %.4e -> %.4e  (slope %.4e /step)%n",
            firstSampleStep, midSampleStep, lastSampleStep,
            ejointFirst, ejointMid, ejointLast, slope);

        if (posN > 0) {
            double sdAbsU   = Math.sqrt(m2AbsU   / posN);
            double sdAbsY   = Math.sqrt(m2AbsY   / posN);
            double sdAbsDot = Math.sqrt(m2AbsDot / posN);
            System.out.println();
            System.out.println("=== POSE NORMALIZATION DIAGNOSTIC [config: " + config + "] ===");
            System.out.printf("samples:        posN=%d  (3 per Myosin x time)%n", posN);
            System.out.printf("|uVec|:         mean=%.8f  SD=%.2e  max_dev_from_1=%.2e%n",
                mAbsU, sdAbsU, maxDevU);
            System.out.printf("|yVec|:         mean=%.8f  SD=%.2e  max_dev_from_1=%.2e%n",
                mAbsY, sdAbsY, maxDevY);
            System.out.printf("|uVec.yVec|:    mean=%.2e   SD=%.2e  max=%.2e%n",
                mAbsDot, sdAbsDot, maxAbsDot);
            System.out.printf("drift |uVec|:      step %d -> %d : %.10f -> %.10f%n",
                firstSampleStep, lastSampleStep, absUFirst, absULast);
            System.out.printf("drift |uVec.yVec|: step %d -> %d : %.3e -> %.3e%n",
                firstSampleStep, lastSampleStep, absDotFirst, absDotLast);
            System.out.println("=== END POSE NORMALIZATION DIAGNOSTIC ===");
        }

        System.out.println("=== END JOINT DIAGNOSTIC ===");
        System.out.flush();
    }

    public static void reset() {
        posN = 0;
        mAbsU = m2AbsU = maxDevU = 0;
        mAbsY = m2AbsY = maxDevY = 0;
        mAbsDot = m2AbsDot = maxAbsDot = 0;
        absUFirst = absULast = absDotFirst = absDotLast = Double.NaN;
        stepCounter = 0;
        n = 0;
        mThetaLM = m2ThetaLM = 0;
        mThetaRL = m2ThetaRL = 0;
        mHeadZ   = m2HeadZ   = 0;
        mEjoint  = m2Ejoint  = 0;
        ejointFirst = ejointMid = ejointLast = Double.NaN;
        firstSampleStep = midSampleStep = lastSampleStep = -1;
        sampleCount = 0;
    }
}
