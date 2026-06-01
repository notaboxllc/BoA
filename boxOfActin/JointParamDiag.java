package boxOfActin;

// JointParamDiag (2026-05-31): Parts 1 & 3 of the joint parameter +
// signed-torque diagnostic. Static analysis confirms parameters match
// modulo float32 narrowing; this tool emits the bit-level values for
// the record, plus a per-myosin signed restoring-torque comparison at
// a late step.
//
// Part 1 (dumpParams): Print every numeric parameter feeding the joint
// torque computation, CPU-double and GPU-float32-narrowed, side by
// side with full precision. One-shot at startup.
//
// Part 3 (sample): At a target step (late equilibrium), iterate the
// first N myosins; for each, compute the SIGNED restoring-torque
// component along the joint axis using BOTH the CPU formula
// (Pt3D.fastAcos -> Math.acos in mid-band) and the GPU formula
// (accurateAcos). Both run in double precision on the SAME pose read
// from the float32 SoA. Mean signed difference exposes any
// formula-level bias the prior magnitude/cosine comparison missed.
//
// Gated by BOA_DIAG_PARAMS=1 (default off). Optional
// BOA_DIAG_PARAM_STEP=<N> sets the late-step dump (default 8000).
// Optional BOA_DIAG_PARAM_N=<N> sets the myosin sample size (default 20).
public class JointParamDiag {

    public static boolean ENABLED = false;
    public static int TARGET_STEP = 8000;
    public static int SAMPLE_MYOS = 20;

    private static int stepCounter = 0;
    private static boolean sampledOnce = false;

    public static void initFromEnv() {
        String v = System.getenv("BOA_DIAG_PARAMS");
        if (v != null && !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("false")) {
            ENABLED = true;
            System.err.println("[PARAM_DIAG] enabled");
        }
        String ts = System.getenv("BOA_DIAG_PARAM_STEP");
        if (ts != null && !ts.isEmpty()) {
            try { TARGET_STEP = Integer.parseInt(ts); } catch (NumberFormatException ignored) {}
        }
        String ns = System.getenv("BOA_DIAG_PARAM_N");
        if (ns != null && !ns.isEmpty()) {
            try { SAMPLE_MYOS = Integer.parseInt(ns); } catch (NumberFormatException ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // Part 1: Parameter byte-for-byte CPU-double vs GPU-float32 dump
    // ---------------------------------------------------------------------
    public static void dumpParams() {
        if (!ENABLED) return;

        System.out.println();
        System.out.println("=== PART 1: PARAMETER TABLE (CPU double vs GPU float32 narrow) ===");
        System.out.printf("%-22s %22s %22s %22s%n", "param", "CPU(double)", "GPU(float widened)", "abs diff");

        dumpScalar("deltaT",            Env.deltaT.getValue());
        dumpScalar("myoJ1FracMove",     Env.myoJ1FracMove.getValue());
        dumpScalar("myoJ1FracR",        Env.myoJ1FracR.getValue());
        dumpScalar("myoJ1FracMoveTorq", Env.myoJ1FracMoveTorq.getValue());
        dumpScalar("myoJ2FracMove",     Env.myoJ2FracMove.getValue());
        dumpScalar("myoJ2FracR",        Env.myoJ2FracR.getValue());
        dumpScalar("myoJ2FracMoveTorq", Env.myoJ2FracMoveTorq.getValue());
        dumpScalar("myoMotorLength",    Env.myoMotorLength.getValue());
        dumpScalar("myoLeverLength",    Env.myoLeverLength.getValue());
        dumpScalar("myoRodLength",      Env.myoRodLength.getValue());
        dumpScalar("myosinStallForce",  Env.myosinStallForce.getValue());
        dumpScalar("uncockedLM_deg",    Myosin.uncockedLever_MotorAngle);
        dumpScalar("cockedLM_deg",      Myosin.cockedLever_MotorAngle);

        // First myosin's drag values
        if (Myosin.myoCt > 0) {
            Myosin myo = Myosin.theMyosins[0];
            if (myo != null && myo.myoRod != null && myo.myoLever != null && myo.myoMotor != null) {
                System.out.println("-- drag tensors (first Myosin, packed into myoDrags[0..8]) --");
                dumpScalar("rod.bTransGam.x",   myo.myoRod.bTransGam.x);
                dumpScalar("rod.bTransGam.y",   myo.myoRod.bTransGam.y);
                dumpScalar("rod.bRotGam.y",     myo.myoRod.bRotGam.y);
                dumpScalar("lever.bTransGam.x", myo.myoLever.bTransGam.x);
                dumpScalar("lever.bTransGam.y", myo.myoLever.bTransGam.y);
                dumpScalar("lever.bRotGam.y",   myo.myoLever.bRotGam.y);
                dumpScalar("motor.bTransGam.x", myo.myoMotor.bTransGam.x);
                dumpScalar("motor.bTransGam.y", myo.myoMotor.bTransGam.y);
                dumpScalar("motor.bRotGam.y",   myo.myoMotor.bRotGam.y);
            }
        }

        // Derived: maxMag cap for LM torsion
        double stallSI   = Env.myosinStallForce.getValue();
        double motorLen  = Env.myoMotorLength.getValue();
        double maxMag_CPU = stallSI * 0.5 * motorLen * 1e-18;
        double maxMag_GPU = ((double)(float)stallSI) * 0.5 * ((double)(float)motorLen) * 1e-18;
        System.out.println("-- derived: LM torsion maxMag cap (N*m, both formulas) --");
        System.out.printf("%-22s %22.10e %22.10e %22.10e%n", "maxMag_LM_cap",
            maxMag_CPU, maxMag_GPU, Math.abs(maxMag_CPU - maxMag_GPU));

        System.out.println();
        System.out.println("=== PART 2: THRESHOLDS / CLAMPS / GUARDS ===");
        System.out.println("CPU applyLeverMotorJointForce  : no threshold/cap. cosBeta clamped to [-1,1] in moveCoeff.");
        System.out.println("GPU applyLeverMotorJointForce  : strainDist>0, denom>0 guards (zero force if zero). cosB clamped to [-1,1].");
        System.out.println("CPU applyLeverMotorJointTorque : isNaN(torsion)->return; unitVec() (random kick if mag==0);");
        System.out.println("                                  dotVecs clamp [-1,1]; maxMag = stall*0.5*motorLen*1e-18 N*m (positive cap only).");
        System.out.println("GPU applyLeverMotorJointTorque : tvMag2>0 skip (no kick); dotVecs clamp; maxMag identical formula.");
        System.out.println("CPU applyRodLeverJointForce    : no threshold/cap.");
        System.out.println("GPU applyRodLeverJointForce    : strainDist>0, denom>0 guards.");
        System.out.println("CPU applyRodLeverJointTorque   : isNaN(torsion)->return; unitVec(); dotVecs clamp; NO maxMag cap.");
        System.out.println("GPU applyRodLeverJointTorque   : tvMag2>0 skip; dotVecs clamp; NO maxMag cap.");
        System.out.println("Difference: zero-magnitude branch (CPU = randomUnitVec kick, GPU = skip). Tiny per-step kick.");
        System.out.println("            Already isolated as <=16 events of the ~330 by 2026-05-31 §Test 1.");

        System.out.println("=== END PART 1+2 ===");
        System.out.flush();
    }

    private static void dumpScalar(String name, double v) {
        float fv = (float) v;
        double widened = (double) fv;
        double diff = Math.abs(v - widened);
        System.out.printf("%-22s %22.10e %22.10e %22.10e%n", name, v, widened, diff);
    }

    // ---------------------------------------------------------------------
    // Part 3: Signed restoring-torque comparison at late step
    // ---------------------------------------------------------------------
    public static void sample() {
        if (!ENABLED) return;
        stepCounter++;
        if (sampledOnce || stepCounter != TARGET_STEP) return;
        sampledOnce = true;

        double dt              = Env.deltaT.getValue();
        double j1FracMoveTorq  = Env.myoJ1FracMoveTorq.getValue();
        double j2FracMoveTorq  = Env.myoJ2FracMoveTorq.getValue();
        double motorLen        = Env.myoMotorLength.getValue();
        double stallForce      = Env.myosinStallForce.getValue();
        double uncockedAng_deg = Myosin.uncockedLever_MotorAngle;
        double cockedAng_deg   = Myosin.cockedLever_MotorAngle;

        // GPU side reads float-narrowed copies. Mimic that here for fairness.
        double dtG             = (double)(float) dt;
        double j1FracMoveTorqG = (double)(float) j1FracMoveTorq;
        double j2FracMoveTorqG = (double)(float) j2FracMoveTorq;
        double motorLenG       = (double)(float) motorLen;
        double stallForceG     = (double)(float) stallForce;
        double uncockedAng_degG = (double)(float) uncockedAng_deg;
        double cockedAng_degG   = (double)(float) cockedAng_deg;

        double DEG2RAD = Math.PI / 180.0;
        double RAD2DEG = 180.0 / Math.PI;

        System.out.println();
        System.out.printf("=== PART 3: SIGNED RESTORING-TORQUE COMPARISON @ step=%d ===%n", stepCounter);
        System.out.println("Reads float32 SoA pose; CPU formula uses Pt3D.fastAcos (Math.acos in [-0.95,0.95]);");
        System.out.println("GPU formula uses accurateAcos (Newton-refined) and float-narrowed params.");
        System.out.println("torsionMag is the SIGNED scalar applied to lever (+) / motor (-) for LM, or rod (+) / lever (-) for RL.");
        System.out.println();
        System.out.printf("%-4s %4s %-7s %12s %14s %14s %14s%n",
            "idx", "cock", "joint", "theta_deg", "tau_CPU(N*m)", "tau_GPU(N*m)", "diff(GPU-CPU)");

        double sumDiffLM = 0, sumDiffRL = 0;
        int nLM = 0, nRL = 0;
        int n = Math.min(SAMPLE_MYOS, Myosin.myoCt);

        for (int i = 0; i < n; i++) {
            Myosin myo = Myosin.theMyosins[i];
            if (myo == null) continue;
            MyoMotor mt = myo.myoMotor;
            MyoLever lv = myo.myoLever;
            MyoRod   rd = myo.myoRod;
            if (mt == null || lv == null || rd == null) continue;

            // Pose read from float32 SoA, widened to double (same for both formulas)
            double mux = mt.getUVecX(), muy = mt.getUVecY(), muz = mt.getUVecZ();
            double lux = lv.getUVecX(), luy = lv.getUVecY(), luz = lv.getUVecZ();
            double rux = rd.getUVecX(), ruy = rd.getUVecY(), ruz = rd.getUVecZ();

            double mBRGy = mt.bRotGam.y;
            double lBRGy = lv.bRotGam.y;
            double rBRGy = rd.bRotGam.y;
            // GPU side reads narrowed drag values
            double mBRGyG = (double)(float) mBRGy;
            double lBRGyG = (double)(float) lBRGy;
            double rBRGyG = (double)(float) rBRGy;

            boolean cocked = mt.isCocked();

            // --- LM joint ---
            {
                double dotLM = lux*mux + luy*muy + luz*muz;
                if (dotLM > 1.0) dotLM = 1.0; if (dotLM < -1.0) dotLM = -1.0;
                double thetaLM_deg_CPU = Pt3D.fastAcos(dotLM) * RAD2DEG;
                double thetaLM_deg_GPU = accurateAcos(dotLM) * RAD2DEG;
                double angRelaxedCPU = cocked ? cockedAng_deg  : uncockedAng_deg;
                double angRelaxedGPU = cocked ? cockedAng_degG : uncockedAng_degG;
                double angD_CPU = thetaLM_deg_CPU - angRelaxedCPU;
                double angD_GPU = thetaLM_deg_GPU - angRelaxedGPU;

                double torsionCPU = j1FracMoveTorq * (Math.PI/180.0) * angD_CPU
                                  / ((1.0/mBRGy + 1.0/lBRGy) * dt);
                double torsionGPU = j1FracMoveTorqG * DEG2RAD * angD_GPU
                                  / ((1.0/mBRGyG + 1.0/lBRGyG) * dtG);
                double maxMagCPU = stallForce * 0.5 * motorLen * 1e-18;
                double maxMagGPU = stallForceG * 0.5 * motorLenG * 1e-18;
                if (torsionCPU > maxMagCPU) torsionCPU = maxMagCPU;
                if (torsionGPU > maxMagGPU) torsionGPU = maxMagGPU;

                double diff = torsionGPU - torsionCPU;
                sumDiffLM += diff;
                nLM++;
                System.out.printf("%-4d %4s %-7s %12.4f %14.6e %14.6e %14.6e%n",
                    i, cocked ? "Y" : "n", "LM",
                    thetaLM_deg_CPU, torsionCPU, torsionGPU, diff);
            }

            // --- RL joint ---
            {
                double dotRL = rux*lux + ruy*luy + ruz*luz;
                if (dotRL > 1.0) dotRL = 1.0; if (dotRL < -1.0) dotRL = -1.0;
                double thetaRL_deg_CPU = Pt3D.fastAcos(dotRL) * RAD2DEG;
                double thetaRL_deg_GPU = accurateAcos(dotRL) * RAD2DEG;
                double angD_CPU = thetaRL_deg_CPU;  // angRelaxed = 0
                double angD_GPU = thetaRL_deg_GPU;

                double torsionCPU = j2FracMoveTorq * (Math.PI/180.0) * angD_CPU
                                  / ((1.0/lBRGy + 1.0/rBRGy) * dt);
                double torsionGPU = j2FracMoveTorqG * DEG2RAD * angD_GPU
                                  / ((1.0/lBRGyG + 1.0/rBRGyG) * dtG);
                // no maxMag cap on RL torsion

                double diff = torsionGPU - torsionCPU;
                sumDiffRL += diff;
                nRL++;
                System.out.printf("%-4d %4s %-7s %12.4f %14.6e %14.6e %14.6e%n",
                    i, "-", "RL",
                    thetaRL_deg_CPU, torsionCPU, torsionGPU, diff);
            }
        }

        System.out.println();
        if (nLM > 0) System.out.printf("LM mean (tau_GPU - tau_CPU) = %.6e N*m  over %d myosins%n",
            sumDiffLM / nLM, nLM);
        if (nRL > 0) System.out.printf("RL mean (tau_GPU - tau_CPU) = %.6e N*m  over %d myosins%n",
            sumDiffRL / nRL, nRL);
        System.out.println("=== END PART 3 ===");
        System.out.flush();
    }

    // Mirror of GPUMoveThing.accurateAcos. Inlined here so the diag is
    // self-contained and not dependent on the kernel-callable signature.
    private static double accurateAcos(double x) {
        if (x > 1.0) x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) {
            double t = 1.0 - x; if (t < 0) t = 0;
            y = Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x; if (t < 0) t = 0;
            y = Math.PI - Math.sqrt(2.0 * t);
        } else {
            // Abramowitz & Stegun 4.4.46 seed
            double ax = Math.abs(x);
            double r = Math.sqrt(1.0 - ax);
            double p = ((-0.0187293 * ax + 0.0742610) * ax - 0.2121144) * ax + 1.5707288;
            y = r * p;
            if (x < 0) y = Math.PI - y;
        }
        // 2 Newton iterations on f(y) = cos(y) - x
        for (int k = 0; k < 2; k++) {
            double s = Math.sin(y);
            if (s == 0.0) break;
            y = y + (Math.cos(y) - x) / s;
        }
        return y;
    }
}
