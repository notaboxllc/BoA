package boxOfActin;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * GPU-accelerated Myosin per-Myosin jointConstraints() via TornadoVM.
 *
 * Ports all four CPU methods in {@code Myosin.jointConstraints()}:
 *   - applyLeverMotorJointForce  (rod-side force, R×F torque on motor + lever)
 *   - applyLeverMotorJointTorque (torsion-spring torque on motor + lever)
 *   - applyRodLeverJointForce    (force, R×F torque on lever + rod)
 *   - applyRodLeverJointTorque   (torsion-spring torque on lever + rod)
 *
 * One GPU thread per Myosin. Each thread reads its rod/lever/motor SoA pose
 * (via slot indices into soaCoord/soaUVec FloatArrays) plus per-Myosin packed
 * drag tensors and cocked-state flag, accumulates the resulting force/torque
 * contributions in 18 thread-local floats (6 per Thing × 3 Things), and
 * writes them sparsely into jointForceSumOut / jointTorqueSumOut at the
 * rod/lever/motor slot indices. Each Myosin's three sub-Things are unique,
 * so writes are conflict-free without atomics.
 *
 * The kernel's output is added into the canonical Thing.soaForceSum /
 * Thing.soaTorqueSum on CPU after download. The CPU loop iterates by Myosin
 * (touching only the 9 floats per Myosin actually written), so untouched
 * slots in the thingCount-sized output buffers carry stale values harmlessly.
 *
 * MyosinDimer joints (alignment, rod coupling) stay on CPU — they involve
 * cross-Myosin reads and are handled by MyosinDimer.myoDimerThreads in the
 * same phase wave.
 */
public class GPUMyosinJoints {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation.
     *  This kernel consumes no RNG, but the slot is reserved. */
    public static final int KERNEL_ID = 3;

    private static final int JOINTS_KERNEL_BLOCK_SIZE = 64;

    // ----- capacities -----
    private static int myoCap     = 0;   // capacity of per-Myosin arrays
    private static int thingCap   = 0;   // capacity of per-Thing SoA arrays

    // ----- per-Thing SoA inputs (sized thingCap*3) -----
    private static FloatArray soaUVecFA;
    private static FloatArray soaCoordFA;

    // ----- per-Thing SoA outputs (sized thingCap*3) -----
    private static FloatArray jointForceSumOut;
    private static FloatArray jointTorqueSumOut;

    // ----- per-Myosin inputs -----
    private static IntArray   rodSlots;     // myoCap
    private static IntArray   leverSlots;   // myoCap
    private static IntArray   motorSlots;   // myoCap
    private static FloatArray myoDrags;     // myoCap*9: [rodBTGx,rodBTGy,rodBRGy, leverBTGx,..., motorBRGy]
    private static IntArray   cockedFlags;  // myoCap

    // ----- small scalar inputs -----
    // jointParams layout:
    //   [ 0] deltaT
    //   [ 1] myoJ1FracMove        (lever-motor joint force fraction)
    //   [ 2] myoJ1FracR           (lever-motor moment-arm fraction)
    //   [ 3] myoJ1FracMoveTorq    (lever-motor torsion fraction)
    //   [ 4] myoJ2FracMove        (rod-lever joint force fraction)
    //   [ 5] myoJ2FracR           (rod-lever moment-arm fraction)
    //   [ 6] myoJ2FracMoveTorq    (rod-lever torsion fraction)
    //   [ 7] myoMotorLength
    //   [ 8] myoLeverLength
    //   [ 9] myoRodLength
    //   [10] myosinStallForce (pN)
    //   [11] uncockedAngleDeg (=  0)
    //   [12] cockedAngleDeg   (= 60)
    private static FloatArray jointParams;
    // counts: [0]=myoCt, [1]=thingCt
    private static IntArray   counts;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;
    private static GridScheduler        gridScheduler;

    // Timing accumulators
    private static long packNanos   = 0;
    private static long execNanos   = 0;
    private static long unpackNanos = 0;
    private static long totalNanos  = 0;
    private static int  callCount   = 0;

    // ---------------- Worker pool (mirrors GPUMoveThing iter2d) --------------
    // Persistent daemon threads parallelise the per-step pack of canonical
    // SoA pose into the GPU FloatArrays AND the sparse download+add into
    // soaForceSum/soaTorqueSum. FloatArray.set/get on disjoint indices is
    // safe under concurrent access (single-word writes; no header munging).
    private static final int N_WORKERS = Math.max(1,
            Math.min(Env.allThreadCt, Runtime.getRuntime().availableProcessors()));
    private static final int OP_PACK_POSE   = 0;  // copy soaCoord/soaUVec [0..tcCount*3)
    private static final int OP_PACK_MYO    = 1;  // pack rod/lever/motor slots, drags, cocked
    private static final int OP_UNPACK_ADD  = 2;  // sparse download + add into soaForceSum/soaTorqueSum
    private static Thread[] workers;
    private static final Object phaseLock = new Object();
    private static int currentPhase  = 0;
    private static int workersDone   = 0;
    private static int workOp        = 0;
    private static int workCount     = 0;   // tcEntries for OP_PACK_POSE, M for the others
    private static int workChunkSize = 0;

    // -------------------------------------------------------------------------
    // acos approximation. PTX backend has no acos intrinsic — Graal's fallback
    // polynomial uses Float.floatToIntBits for range reduction, which triggers
    // a ReinterpretNode that PTXArithmeticTool.emitReinterpret bails on.
    //
    // We use Abramowitz & Stegun 4.4.46, valid for x ∈ [0, 1]:
    //   acos(x) ≈ sqrt(1 - x) * (a0 + a1*x + a2*x² + a3*x³)
    //   acos(-x) = π - acos(x)                          for x ∈ [0, 1]
    // Max error ~5e-5 over [-1, 1]. CPU's Pt3D.fastAcos uses Math.acos in the
    // (-0.95, 0.95) band, so the GPU vs CPU per-call angle differs by up to
    // ~5e-5 there — orders of magnitude below the angD-driven torsionMag
    // scale, comparable to float32 round-off elsewhere in the kernel.
    // Caller must clamp x to [-1, 1] before calling.
    // -------------------------------------------------------------------------
    private static float fastAcosF(float x) {
        float absx = (x < 0f) ? -x : x;
        float poly = 1.5707288f
                   + absx * (-0.2121144f
                   + absx * ( 0.0742610f
                   + absx * (-0.0187293f)));
        float ret = poly * (float) Math.sqrt(1.0f - absx);
        return (x < 0f) ? (3.14159265f - ret) : ret;
    }

    // -------------------------------------------------------------------------
    // GPU kernel: one thread per Myosin.
    // -------------------------------------------------------------------------
    private static void jointsKernel(
            FloatArray soaUVec,
            FloatArray soaCoord,
            IntArray   rodSlots,
            IntArray   leverSlots,
            IntArray   motorSlots,
            FloatArray myoDrags,
            IntArray   cockedFlags,
            FloatArray jointForceSumOut,
            FloatArray jointTorqueSumOut,
            FloatArray jointParams,
            IntArray   counts) {

        int M = counts.get(0);

        float dt              = jointParams.get(0);
        float j1FracMove      = jointParams.get(1);
        float j1FracR         = jointParams.get(2);
        float j1FracMoveTorq  = jointParams.get(3);
        float j2FracMove      = jointParams.get(4);
        float j2FracR         = jointParams.get(5);
        float j2FracMoveTorq  = jointParams.get(6);
        float motorLen        = jointParams.get(7);
        float leverLen        = jointParams.get(8);
        float rodLen          = jointParams.get(9);
        float stallForce      = jointParams.get(10);
        float uncockedAng     = jointParams.get(11);
        float cockedAng       = jointParams.get(12);

        float DEG2RAD = 0.017453292f;
        float RAD2DEG = 57.29578f;

        for (@Parallel int m = 0; m < cockedFlags.getSize(); m++) {
            if (m >= M) { return; }

            int rodSlot   = rodSlots.get(m);
            int leverSlot = leverSlots.get(m);
            int motorSlot = motorSlots.get(m);

            int r3  = rodSlot   * 3;
            int l3  = leverSlot * 3;
            int mo3 = motorSlot * 3;

            // Read poses (coord + uVec) for rod, lever, motor.
            float rcx = soaCoord.get(r3),  rcy = soaCoord.get(r3 + 1), rcz = soaCoord.get(r3 + 2);
            float rux = soaUVec.get(r3),   ruy = soaUVec.get(r3 + 1),  ruz = soaUVec.get(r3 + 2);
            float lcx = soaCoord.get(l3),  lcy = soaCoord.get(l3 + 1), lcz = soaCoord.get(l3 + 2);
            float lux = soaUVec.get(l3),   luy = soaUVec.get(l3 + 1),  luz = soaUVec.get(l3 + 2);
            float mcx = soaCoord.get(mo3), mcy = soaCoord.get(mo3 + 1), mcz = soaCoord.get(mo3 + 2);
            float mux = soaUVec.get(mo3),  muy = soaUVec.get(mo3 + 1),  muz = soaUVec.get(mo3 + 2);

            // Read per-Myosin packed drag tensors.
            int md9 = m * 9;
            float rBTGx  = myoDrags.get(md9);
            float rBTGy  = myoDrags.get(md9 + 1);
            float rBRGy  = myoDrags.get(md9 + 2);
            float lBTGx  = myoDrags.get(md9 + 3);
            float lBTGy  = myoDrags.get(md9 + 4);
            float lBRGy  = myoDrags.get(md9 + 5);
            float mBTGx  = myoDrags.get(md9 + 6);
            float mBTGy  = myoDrags.get(md9 + 7);
            float mBRGy  = myoDrags.get(md9 + 8);

            int cocked = cockedFlags.get(m);

            // Half-lengths for end-point computation (end1 = coord - L/2 * uVec; end2 = coord + L/2 * uVec).
            float halfRod   = 0.5f * rodLen;
            float halfLever = 0.5f * leverLen;
            float halfMotor = 0.5f * motorLen;

            float le1x = lcx - halfLever * lux, le1y = lcy - halfLever * luy, le1z = lcz - halfLever * luz;
            float le2x = lcx + halfLever * lux, le2y = lcy + halfLever * luy, le2z = lcz + halfLever * luz;
            float me1x = mcx - halfMotor * mux, me1y = mcy - halfMotor * muy, me1z = mcz - halfMotor * muz;
            float re2x = rcx + halfRod * rux,   re2y = rcy + halfRod * ruy,   re2z = rcz + halfRod * ruz;

            // Local accumulators per Thing slot. Cleared each thread, written once at end.
            float rodFx = 0f,   rodFy = 0f,   rodFz = 0f;
            float rodTx = 0f,   rodTy = 0f,   rodTz = 0f;
            float leverFx = 0f, leverFy = 0f, leverFz = 0f;
            float leverTx = 0f, leverTy = 0f, leverTz = 0f;
            float motorFx = 0f, motorFy = 0f, motorFz = 0f;
            float motorTx = 0f, motorTy = 0f, motorTz = 0f;

            // ===================================================================
            // applyLeverMotorJointForce
            //   strainDist = ptDist(lever.end2, motor.end1)
            //   linkUVec1 = unit(lever.end2 - motor.end1)
            //   moveCoeffHead = motor.moveCoeff(end=1, linkUVec1)
            //   moveCoeffTail = lever.moveCoeff(end=2, linkUVec2 = -linkUVec1)
            //   forceMag = (J1FracMove * 1e-6 * strainDist) / (dt * (moveC_h + moveC_t))
            //   F  = forceMag * linkUVec1; motor.incForceSum(F); motor.incTorqueSum(R×F)
            //   F → −F;  lever.incForceSum(F); lever.incTorqueSum(R×F)
            //   R for motor: 0.5e-6 * motorDim * J1FracR * motor.uVecR
            //   R for lever: 0.5e-6 * leverDim * J1FracR * lever.uVec
            // ===================================================================
            {
                float dx = le2x - me1x, dy = le2y - me1y, dz = le2z - me1z;
                float dist2 = dx * dx + dy * dy + dz * dz;
                float strainDist = (float) Math.sqrt(dist2);
                float invStrain = (strainDist > 0f) ? (1.0f / strainDist) : 0f;
                float l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                float l2x = -l1x, l2y = -l1y, l2z = -l1z;

                // moveCoeffHead: motor end=1 → cosBeta = Dot(motor.uVecR, linkUVec1)
                // CPU does `beta = fastAcos(cosBeta); cosAlpha = sin(beta)`.
                // sin(acos(x)) = sqrt(1 - x²), so we skip both acos and sin.
                float cosBh = -(mux * l1x + muy * l1y + muz * l1z);
                if (cosBh > 1.0f)  cosBh = 1.0f;
                if (cosBh < -1.0f) cosBh = -1.0f;
                float cosAlphH2 = 1.0f - cosBh * cosBh;
                if (cosAlphH2 < 0f) cosAlphH2 = 0f;
                float lSqH     = 1.0e-12f * motorLen * motorLen;
                float CxH      = cosBh * cosBh / mBTGx;
                float CperpH   = cosAlphH2 / mBTGy;
                float CthetaH  = lSqH * cosAlphH2 / (4.0f * mBRGy);
                float moveCh   = CxH + CperpH + CthetaH;

                // moveCoeffTail: lever end=2 → cosBeta = Dot(lever.uVec, linkUVec2)
                float cosBt = lux * l2x + luy * l2y + luz * l2z;
                if (cosBt > 1.0f)  cosBt = 1.0f;
                if (cosBt < -1.0f) cosBt = -1.0f;
                float cosAlphT2 = 1.0f - cosBt * cosBt;
                if (cosAlphT2 < 0f) cosAlphT2 = 0f;
                float lSqT     = 1.0e-12f * leverLen * leverLen;
                float CxT      = cosBt * cosBt / lBTGx;
                float CperpT   = cosAlphT2 / lBTGy;
                float CthetaT  = lSqT * cosAlphT2 / (4.0f * lBRGy);
                float moveCt   = CxT + CperpT + CthetaT;

                float denom = dt * (moveCh + moveCt);
                float forceMag = (denom > 0f) ? (j1FracMove * 1.0e-6f * strainDist / denom) : 0f;

                float Fx = forceMag * l1x, Fy = forceMag * l1y, Fz = forceMag * l1z;

                // motor: force = +F, R = 0.5e-6 * motorLen * J1FracR * motor.uVecR (= −uVec)
                motorFx += Fx; motorFy += Fy; motorFz += Fz;
                float Rms = -0.5e-6f * motorLen * j1FracR;
                float Rmx = Rms * mux, Rmy = Rms * muy, Rmz = Rms * muz;
                motorTx += Rmy * Fz - Rmz * Fy;
                motorTy += Rmz * Fx - Rmx * Fz;
                motorTz += Rmx * Fy - Rmy * Fx;

                // F → −F for lever
                Fx = -Fx; Fy = -Fy; Fz = -Fz;
                leverFx += Fx; leverFy += Fy; leverFz += Fz;
                float Rls = 0.5e-6f * leverLen * j1FracR;
                float Rlx = Rls * lux, Rly = Rls * luy, Rlz = Rls * luz;
                leverTx += Rly * Fz - Rlz * Fy;
                leverTy += Rlz * Fx - Rlx * Fz;
                leverTz += Rlx * Fy - Rly * Fx;
            }

            // ===================================================================
            // applyLeverMotorJointTorque
            //   torsionVec = lever.uVec × motor.uVec
            //   if NaN: skip (matches CPU early-return after the iter2b-polish fix)
            //   else normalise, compute angTween = fastAcos(lever.uVec · motor.uVec) * 180/π
            //        angRelaxed = isCocked ? cockedAng (60°) : uncockedAng (0°)
            //        torsionMag = J1FracMoveTorq * (π/180) * (angTween − angRelaxed)
            //                     / ((1/motor.bRotGam.y + 1/lever.bRotGam.y) * dt)
            //        maxMag = stallForce * 0.5 * motorDim * 1e-18  (pN→N, µm→m)
            //        torsionMag = min(torsionMag, maxMag)   ← CPU uses Math.min (no negative cap)
            //   lever.incTorqueSum(torsionVec * torsionMag)
            //   motor.incTorqueSum(−torsionVec * torsionMag)
            // ===================================================================
            {
                float tvx = luy * muz - luz * muy;
                float tvy = luz * mux - lux * muz;
                float tvz = lux * muy - luy * mux;
                // Combined NaN + mag=0 guard. The CPU code has two stages
                // (Double.isNaN → return; unitVec → randomUnitVec on mag=0).
                // On GPU, `tvMag2 > 0f` covers both: if any component is NaN,
                // tvMag2 is NaN and NaN > 0f is false; if cross is exactly
                // zero, tvMag2 == 0f and 0f > 0f is false. The CPU's random-
                // direction kick for mag=0 is dropped — see GPUMyosinJoints
                // JOURNAL note. PTX backend can't lower Float.isNaN/x==x
                // self-comparison (ReinterpretNode unimplemented), so we
                // rely on the magnitude branch alone.
                float tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 0f) {
                    float invMag = 1.0f / (float) Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;

                    float dotVecs = lux * mux + luy * muy + luz * muz;
                    if (dotVecs > 1.0f)  dotVecs = 1.0f;
                    if (dotVecs < -1.0f) dotVecs = -1.0f;
                    float angTween = fastAcosF(dotVecs) * RAD2DEG;

                    float angRelaxed = (cocked == 1) ? cockedAng : uncockedAng;
                    float angD = angTween - angRelaxed;

                    float invBRG = 1.0f / mBRGy + 1.0f / lBRGy;
                    float torsionMag = j1FracMoveTorq * DEG2RAD * angD / (invBRG * dt);
                    float maxMag = stallForce * 0.5f * motorLen * 1.0e-18f;
                    if (torsionMag > maxMag) torsionMag = maxMag;

                    leverTx += tvx * torsionMag;
                    leverTy += tvy * torsionMag;
                    leverTz += tvz * torsionMag;
                    motorTx -= tvx * torsionMag;
                    motorTy -= tvy * torsionMag;
                    motorTz -= tvz * torsionMag;
                }
            }

            // ===================================================================
            // applyRodLeverJointForce
            //   strainDist = ptDist(rod.end2, lever.end1)
            //   linkUVec1 = unit(rod.end2 - lever.end1)
            //   moveC1 = lever.moveCoeff(end=1, linkUVec1)
            //   moveC2 = rod.moveCoeff(end=2, linkUVec2 = -linkUVec1)
            //   forceMag = (J2FracMove * 1e-6 * strainDist) / (dt * (moveC1 + moveC2))
            //   F  = forceMag * linkUVec1; lever += F + torque; F → -F; rod += F + torque
            //   R for lever: 0.5e-6 * leverLen * J2FracR * lever.uVecR
            //   R for rod  : 0.5e-6 * rodLen   * J2FracR * rod.uVec
            // ===================================================================
            {
                float dx = re2x - le1x, dy = re2y - le1y, dz = re2z - le1z;
                float dist2 = dx * dx + dy * dy + dz * dz;
                float strainDist = (float) Math.sqrt(dist2);
                float invStrain = (strainDist > 0f) ? (1.0f / strainDist) : 0f;
                float l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                float l2x = -l1x, l2y = -l1y, l2z = -l1z;

                // moveC1: lever end=1 → cosBeta = Dot(lever.uVecR, linkUVec1) = -Dot(lever.uVec, linkUVec1)
                // sin(acos(x)) = sqrt(1 - x²); use the squared form directly.
                float cosB1 = -(lux * l1x + luy * l1y + luz * l1z);
                if (cosB1 > 1.0f)  cosB1 = 1.0f;
                if (cosB1 < -1.0f) cosB1 = -1.0f;
                float cosAlph1_2 = 1.0f - cosB1 * cosB1;
                if (cosAlph1_2 < 0f) cosAlph1_2 = 0f;
                float lSq1     = 1.0e-12f * leverLen * leverLen;
                float Cx1      = cosB1 * cosB1 / lBTGx;
                float Cperp1   = cosAlph1_2 / lBTGy;
                float Ctheta1  = lSq1 * cosAlph1_2 / (4.0f * lBRGy);
                float moveC1   = Cx1 + Cperp1 + Ctheta1;

                // moveC2: rod end=2 → cosBeta = Dot(rod.uVec, linkUVec2)
                float cosB2 = rux * l2x + ruy * l2y + ruz * l2z;
                if (cosB2 > 1.0f)  cosB2 = 1.0f;
                if (cosB2 < -1.0f) cosB2 = -1.0f;
                float cosAlph2_2 = 1.0f - cosB2 * cosB2;
                if (cosAlph2_2 < 0f) cosAlph2_2 = 0f;
                float lSq2     = 1.0e-12f * rodLen * rodLen;
                float Cx2      = cosB2 * cosB2 / rBTGx;
                float Cperp2   = cosAlph2_2 / rBTGy;
                float Ctheta2  = lSq2 * cosAlph2_2 / (4.0f * rBRGy);
                float moveC2   = Cx2 + Cperp2 + Ctheta2;

                float denom = dt * (moveC1 + moveC2);
                float forceMag = (denom > 0f) ? (j2FracMove * 1.0e-6f * strainDist / denom) : 0f;

                float Fx = forceMag * l1x, Fy = forceMag * l1y, Fz = forceMag * l1z;

                // lever: force = +F, R = 0.5e-6 * leverLen * J2FracR * lever.uVecR (= −uVec)
                leverFx += Fx; leverFy += Fy; leverFz += Fz;
                float Rls = -0.5e-6f * leverLen * j2FracR;
                float Rlx = Rls * lux, Rly = Rls * luy, Rlz = Rls * luz;
                leverTx += Rly * Fz - Rlz * Fy;
                leverTy += Rlz * Fx - Rlx * Fz;
                leverTz += Rlx * Fy - Rly * Fx;

                // F → −F for rod
                Fx = -Fx; Fy = -Fy; Fz = -Fz;
                rodFx += Fx; rodFy += Fy; rodFz += Fz;
                float Rrs = 0.5e-6f * rodLen * j2FracR;
                float Rrx = Rrs * rux, Rry = Rrs * ruy, Rrz = Rrs * ruz;
                rodTx += Rry * Fz - Rrz * Fy;
                rodTy += Rrz * Fx - Rrx * Fz;
                rodTz += Rrx * Fy - Rry * Fx;
            }

            // ===================================================================
            // applyRodLeverJointTorque
            //   torsionVec = rod.uVec × lever.uVec
            //   NaN guard, unit, dot, fastAcos as above; angRelaxed = 0
            //   torsionMag = J2FracMoveTorq * (π/180) * angTween
            //                / ((1/lever.bRotGam.y + 1/rod.bRotGam.y) * dt)
            //   NO maxMag cap (matches CPU)
            //   rod   .incTorqueSum(torsionVec * torsionMag)
            //   lever .incTorqueSum(−torsionVec * torsionMag)
            // ===================================================================
            {
                float tvx = ruy * luz - ruz * luy;
                float tvy = ruz * lux - rux * luz;
                float tvz = rux * luy - ruy * lux;
                // Combined NaN + mag=0 guard — see lever-motor torque block above.
                float tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 0f) {
                    float invMag = 1.0f / (float) Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;

                    float dotVecs = rux * lux + ruy * luy + ruz * luz;
                    if (dotVecs > 1.0f)  dotVecs = 1.0f;
                    if (dotVecs < -1.0f) dotVecs = -1.0f;
                    float angTween = fastAcosF(dotVecs) * RAD2DEG;

                    float invBRG = 1.0f / lBRGy + 1.0f / rBRGy;
                    float torsionMag = j2FracMoveTorq * DEG2RAD * angTween / (invBRG * dt);

                    rodTx += tvx * torsionMag;
                    rodTy += tvy * torsionMag;
                    rodTz += tvz * torsionMag;
                    leverTx -= tvx * torsionMag;
                    leverTy -= tvy * torsionMag;
                    leverTz -= tvz * torsionMag;
                }
            }

            // Sparse write: each Myosin's three Thing slots are unique, so a
            // direct '=' write is safe (no other thread targets the same slot).
            jointForceSumOut.set(r3,      rodFx);
            jointForceSumOut.set(r3 + 1,  rodFy);
            jointForceSumOut.set(r3 + 2,  rodFz);
            jointTorqueSumOut.set(r3,     rodTx);
            jointTorqueSumOut.set(r3 + 1, rodTy);
            jointTorqueSumOut.set(r3 + 2, rodTz);

            jointForceSumOut.set(l3,      leverFx);
            jointForceSumOut.set(l3 + 1,  leverFy);
            jointForceSumOut.set(l3 + 2,  leverFz);
            jointTorqueSumOut.set(l3,     leverTx);
            jointTorqueSumOut.set(l3 + 1, leverTy);
            jointTorqueSumOut.set(l3 + 2, leverTz);

            jointForceSumOut.set(mo3,      motorFx);
            jointForceSumOut.set(mo3 + 1,  motorFy);
            jointForceSumOut.set(mo3 + 2,  motorFz);
            jointTorqueSumOut.set(mo3,     motorTx);
            jointTorqueSumOut.set(mo3 + 1, motorTy);
            jointTorqueSumOut.set(mo3 + 2, motorTz);
        }
    }

    // -------------------------------------------------------------------------
    // Lazy allocation + plan build. Two capacity tiers:
    //   myoCap   — per-Myosin arrays (rodSlots/leverSlots/motorSlots/myoDrags/cockedFlags)
    //   thingCap — per-Thing SoA arrays (soaUVecFA/soaCoordFA/jointForceSumOut/jointTorqueSumOut)
    // Plan is rebuilt when either capacity grows.
    // -------------------------------------------------------------------------
    private static void allocateAndBuildPlan(int newMyoCap, int newThingCap) {
        myoCap   = newMyoCap;
        thingCap = newThingCap;

        soaUVecFA         = new FloatArray(thingCap * 3);
        soaCoordFA        = new FloatArray(thingCap * 3);
        jointForceSumOut  = new FloatArray(thingCap * 3);
        jointTorqueSumOut = new FloatArray(thingCap * 3);

        rodSlots    = new IntArray(myoCap);
        leverSlots  = new IntArray(myoCap);
        motorSlots  = new IntArray(myoCap);
        myoDrags    = new FloatArray(myoCap * 9);
        cockedFlags = new IntArray(myoCap);

        jointParams = new FloatArray(13);
        counts      = new IntArray(2);

        TaskGraph tg = new TaskGraph("myosinJoints")
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                              soaUVecFA, soaCoordFA,
                              rodSlots, leverSlots, motorSlots,
                              myoDrags, cockedFlags,
                              jointParams, counts)
            .task("joints",
                  GPUMyosinJoints::jointsKernel,
                  soaUVecFA, soaCoordFA,
                  rodSlots, leverSlots, motorSlots,
                  myoDrags, cockedFlags,
                  jointForceSumOut, jointTorqueSumOut,
                  jointParams, counts)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                            jointForceSumOut, jointTorqueSumOut);

        itg  = tg.snapshot();
        plan = new TornadoExecutionPlan(itg);

        WorkerGrid worker = new WorkerGrid1D(myoCap);
        worker.setLocalWork(JOINTS_KERNEL_BLOCK_SIZE, 1, 1);
        gridScheduler = new GridScheduler("myosinJoints.joints", worker);

        System.out.printf("GPUMyosinJoints: myoCap=%d thingCap=%d blockSize=%d%n",
                          myoCap, thingCap, JOINTS_KERNEL_BLOCK_SIZE);
    }

    private static void closePlan() {
        if (plan != null) {
            try { plan.close(); } catch (Exception e) { /* best effort */ }
            plan = null;
            itg  = null;
        }
    }

    /** Invalidate the plan — used when myosin drag tensors change (aeta
     *  mutation) so the next call rebuilds the plan and re-uploads. */
    public static void invalidatePlan() {
        closePlan();
    }

    // -------------------------------------------------------------------------
    // Worker pool — spin-up on first dispatchAndWait, persist for the run.
    // -------------------------------------------------------------------------
    private static void ensureWorkers() {
        if (workers != null) return;
        workers = new Thread[N_WORKERS];
        for (int w = 0; w < N_WORKERS; w++) {
            final int id = w;
            Thread t = new Thread(() -> workerLoop(id),
                                  "GPUMyosinJoints-worker-" + w);
            t.setDaemon(true);
            workers[w] = t;
            t.start();
        }
    }

    private static void workerLoop(int id) {
        int lastPhase = 0;
        while (true) {
            int op, cnt, chunk;
            synchronized (phaseLock) {
                while (currentPhase == lastPhase) {
                    try { phaseLock.wait(); }
                    catch (InterruptedException e) { return; }
                }
                lastPhase = currentPhase;
                op    = workOp;
                cnt   = workCount;
                chunk = workChunkSize;
            }
            int start = id * chunk;
            int end   = Math.min(start + chunk, cnt);
            if (start < end) {
                switch (op) {
                    case OP_PACK_POSE:  packPoseRange(start, end);  break;
                    case OP_PACK_MYO:   packMyoRange(start, end);   break;
                    case OP_UNPACK_ADD: unpackAddRange(start, end); break;
                    default: break;
                }
            }
            synchronized (phaseLock) {
                workersDone++;
                phaseLock.notifyAll();
            }
        }
    }

    private static void dispatchAndWait(int op, int cnt) {
        ensureWorkers();
        int chunk = (cnt + N_WORKERS - 1) / N_WORKERS;
        if (chunk < 1) chunk = 1;
        synchronized (phaseLock) {
            workOp        = op;
            workCount     = cnt;
            workChunkSize = chunk;
            workersDone   = 0;
            currentPhase++;
            phaseLock.notifyAll();
        }
        synchronized (phaseLock) {
            while (workersDone < N_WORKERS) {
                try { phaseLock.wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pack range: copy soaCoord[start..end) and soaUVec[start..end) into the
    // FloatArrays the kernel reads. Indexes by the flat float array index
    // (Thing index * 3 + axis).
    // -------------------------------------------------------------------------
    private static void packPoseRange(int start, int end) {
        final float[] coordArr = Thing.soaCoord;
        final float[] uVecArr  = Thing.soaUVec;
        for (int i = start; i < end; i++) {
            soaCoordFA.set(i, coordArr[i]);
            soaUVecFA.set(i,  uVecArr[i]);
        }
    }

    // -------------------------------------------------------------------------
    // Pack per-Myosin slot/drags/cocked for myosins in [start, end).
    // -------------------------------------------------------------------------
    private static void packMyoRange(int start, int end) {
        Myosin[] myos = Myosin.theMyosins;
        for (int m = start; m < end; m++) {
            Myosin myo = myos[m];
            MyoRod   rod   = myo.myoRod;
            MyoLever lever = myo.myoLever;
            MyoMotor motor = myo.myoMotor;
            rodSlots.set(m,   rod.myThingNumber);
            leverSlots.set(m, lever.myThingNumber);
            motorSlots.set(m, motor.myThingNumber);
            int d9 = m * 9;
            myoDrags.set(d9,     (float) rod.bTransGam.x);
            myoDrags.set(d9 + 1, (float) rod.bTransGam.y);
            myoDrags.set(d9 + 2, (float) rod.bRotGam.y);
            myoDrags.set(d9 + 3, (float) lever.bTransGam.x);
            myoDrags.set(d9 + 4, (float) lever.bTransGam.y);
            myoDrags.set(d9 + 5, (float) lever.bRotGam.y);
            myoDrags.set(d9 + 6, (float) motor.bTransGam.x);
            myoDrags.set(d9 + 7, (float) motor.bTransGam.y);
            myoDrags.set(d9 + 8, (float) motor.bRotGam.y);
            cockedFlags.set(m, motor.isCocked() ? 1 : 0);
        }
    }

    // -------------------------------------------------------------------------
    // Sparse download + add: for each Myosin in [start, end), read its three
    // Thing's kernel-written force/torque from the FloatArrays and add into
    // the canonical Thing.soaForceSum / Thing.soaTorqueSum slots. Each
    // worker writes to distinct slots (Myosin-to-Thing is unique) so the
    // float[] += is conflict-free across workers.
    // -------------------------------------------------------------------------
    private static void unpackAddRange(int start, int end) {
        final float[] soaForce  = Thing.soaForceSum;
        final float[] soaTorque = Thing.soaTorqueSum;
        for (int m = start; m < end; m++) {
            int r3  = rodSlots.get(m)   * 3;
            int l3  = leverSlots.get(m) * 3;
            int mo3 = motorSlots.get(m) * 3;

            soaForce[r3]      += jointForceSumOut.get(r3);
            soaForce[r3 + 1]  += jointForceSumOut.get(r3 + 1);
            soaForce[r3 + 2]  += jointForceSumOut.get(r3 + 2);
            soaTorque[r3]     += jointTorqueSumOut.get(r3);
            soaTorque[r3 + 1] += jointTorqueSumOut.get(r3 + 1);
            soaTorque[r3 + 2] += jointTorqueSumOut.get(r3 + 2);

            soaForce[l3]      += jointForceSumOut.get(l3);
            soaForce[l3 + 1]  += jointForceSumOut.get(l3 + 1);
            soaForce[l3 + 2]  += jointForceSumOut.get(l3 + 2);
            soaTorque[l3]     += jointTorqueSumOut.get(l3);
            soaTorque[l3 + 1] += jointTorqueSumOut.get(l3 + 1);
            soaTorque[l3 + 2] += jointTorqueSumOut.get(l3 + 2);

            soaForce[mo3]      += jointForceSumOut.get(mo3);
            soaForce[mo3 + 1]  += jointForceSumOut.get(mo3 + 1);
            soaForce[mo3 + 2]  += jointForceSumOut.get(mo3 + 2);
            soaTorque[mo3]     += jointTorqueSumOut.get(mo3);
            soaTorque[mo3 + 1] += jointTorqueSumOut.get(mo3 + 1);
            soaTorque[mo3 + 2] += jointTorqueSumOut.get(mo3 + 2);
        }
    }

    // -------------------------------------------------------------------------
    // Public entry — replaces Myosin.myoThreads dispatch on the GPU path.
    // -------------------------------------------------------------------------
    public static void computeJoints() {
        int M  = Myosin.myoCt;
        int tc = Thing.thingCt;
        if (M == 0) { return; }

        long t0 = System.nanoTime();

        // Ensure capacity. Grow with headroom; rebuild plan when either tier exceeded.
        boolean rebuild = false;
        if (plan == null) {
            rebuild = true;
        } else {
            if (M  > myoCap)   rebuild = true;
            if (tc > thingCap) rebuild = true;
        }
        if (rebuild) {
            closePlan();
            int newMyoCap   = Math.max(1024, Math.max(M  * 2, myoCap   * 2));
            int newThingCap = Math.max(4096, Math.max(tc * 2, thingCap * 2));
            allocateAndBuildPlan(newMyoCap, newThingCap);
        }

        long packStart = System.nanoTime();

        // ----- Per-Thing SoA pose upload (parallel) -----
        // Copy from canonical Thing.soaCoord[] / Thing.soaUVec[] into the
        // FloatArrays the kernel reads. The arrays may carry stale tail
        // contents past tc*3 — harmless, kernel only reads at slot indices < tc.
        final int tcEntries = tc * 3;
        if (tcEntries > 0) {
            dispatchAndWait(OP_PACK_POSE, tcEntries);
        }

        // ----- Per-Myosin pack: slots, drags, cocked flag (parallel) -----
        dispatchAndWait(OP_PACK_MYO, M);

        // ----- Scalar params -----
        jointParams.set(0,  (float) Env.deltaT.getValue());
        jointParams.set(1,  (float) Env.myoJ1FracMove.getValue());
        jointParams.set(2,  (float) Env.myoJ1FracR.getValue());
        jointParams.set(3,  (float) Env.myoJ1FracMoveTorq.getValue());
        jointParams.set(4,  (float) Env.myoJ2FracMove.getValue());
        jointParams.set(5,  (float) Env.myoJ2FracR.getValue());
        jointParams.set(6,  (float) Env.myoJ2FracMoveTorq.getValue());
        jointParams.set(7,  (float) Env.myoMotorLength.getValue());
        jointParams.set(8,  (float) Env.myoLeverLength.getValue());
        jointParams.set(9,  (float) Env.myoRodLength.getValue());
        jointParams.set(10, (float) Env.myosinStallForce.getValue());
        jointParams.set(11, (float) Myosin.uncockedLever_MotorAngle);
        jointParams.set(12, (float) Myosin.cockedLever_MotorAngle);
        counts.set(0, M);
        counts.set(1, tc);

        long packEnd = System.nanoTime();

        plan.withGridScheduler(gridScheduler).execute();

        long execEnd = System.nanoTime();

        // ----- Sparse download + add into canonical SoA (parallel) -----
        // Iterate by Myosin (touches only 3 Things per Myosin). Untouched
        // slots in the thingCap*3 output arrays carry stale values but are
        // never read here, so no pre-zero pass is needed. Each worker's
        // contiguous Myosin range writes to distinct Thing slots (the
        // Myosin → rod/lever/motor map is unique), so float[] += is safe
        // across workers.
        dispatchAndWait(OP_UNPACK_ADD, M);

        long unpackEnd = System.nanoTime();

        packNanos   += packEnd   - packStart;
        execNanos   += execEnd   - packEnd;
        unpackNanos += unpackEnd - execEnd;
        totalNanos  += unpackEnd - t0;
        callCount++;
    }

    /** Reset accumulators; arrays survive across resets. */
    public static void reset() {
        closePlan();
        packNanos = execNanos = unpackNanos = totalNanos = 0;
        callCount = 0;
    }

    /** Diagnostic timing accessors — read by BoxOfActin at run end. */
    public static long getTotalNanos()  { return totalNanos;  }
    public static long getPackNanos()   { return packNanos;   }
    public static long getExecNanos()   { return execNanos;   }
    public static long getUnpackNanos() { return unpackNanos; }
    public static int  getCallCount()   { return callCount;   }

    public static int getMyoCap()   { return myoCap;   }
    public static int getThingCap() { return thingCap; }
}
