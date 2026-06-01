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
 * GPU-accelerated Thing.moveThing() + per-Myosin jointConstraints() via
 * TornadoVM — chained TaskGraph (2026-05-30 — chaining iter).
 *
 * Single TaskGraph with two tasks executed in sequence per .execute():
 *   1. "joints" — per-Myosin jointConstraints kernel (one thread per Myosin)
 *      reads coord/uVec from device, ADDS rod/lever/motor joint forces and
 *      torques in-place into the shared forceSum / torqueSum FloatArrays
 *      using move-slot indexing.
 *   2. "move"   — per-Thing integration kernel (one thread per move slot).
 *      Reads the now-complete forceSum / torqueSum (CPU contributions from
 *      this step's gather + GPU joint contributions from the joints task),
 *      generates Brownian forces inline via Wang hash, integrates pose, and
 *      writes new coord/uVec/yVec into the shared FloatArrays.
 *
 * Wins vs the previous two-plan architecture:
 *   - coord/uVec uploaded ONCE per step (shared between tasks).
 *   - Joint forces stay on device — no download → host add → re-upload round
 *     trip; the move kernel reads forceSum directly.
 *   - One kernel launch + buffer setup, not two.
 *
 * Slot model: both tasks index pose/force/torque buffers by GPU move-slot
 * (capacity slotCap). classifyThings() assigns slot indices and writes the
 * reverse map thingNumberToMoveSlot[myThingNumber] = slot. The joint pack
 * maps each Myosin's rod/lever/motor (Thing index) → move slot. If any of
 * the three is in cpuFallback (not GPU-handled), the Myosin is omitted from
 * the joint slot list and its joints fall back to CPU (Myosin.myoThreads,
 * which short-circuits on useGPU at the per-Myosin level).
 *
 * Scope (gliding-assay first pass): MyoMotor, MyoRod, MyoLever, and root
 * (motherFil == null) FilSegment instances. Ineligible Things (Bug,
 * Chamber, Crucible, AnchorNode, ProteinNode, MyoMiniFilament, StickyNode,
 * FillNode, branch FilSegment, actA-bound FilSegment, isLpSeg-suspended
 * FilSegment, anything when myosinsOff) fall back to CPU moveThing().
 */
public class GPUMoveThing {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation. */
    public static final int KERNEL_ID = 2;

    /**
     * Diagnostic flag (2026-05-31). When true and Env.useGPU is true, the GPU
     * joints kernel is reduced to a no-op (myoJointCt forced to 0 in
     * classifyThings, so the joints task runs but every thread early-returns
     * via the m >= M guard and writes nothing to jointForceSum/jointTorqueSum,
     * which stay at the per-step zero-init from packRange). The CPU
     * Myosin.MyosinThreads dispatch is re-enabled (it normally
     * short-circuits when Env.useGPU is set), so CPU joints write into the
     * per-thread accumulators → gather → soaForceSum → cpuForceSum (read by
     * the move kernel). Net effect: iter2c topology (GPU moveThing + GPU
     * binding + CPU joints), carried forward through the SoA/chained-plan
     * infrastructure. Used to disambiguate whether the residual GPU
     * bindEvents drop after the fastAcosF fix is in the joints kernel or in
     * the moveThing/Brownian/float32 path.
     */
    public static boolean DIAG_CPU_JOINTS = false;

    /**
     * Diagnostic dump step (2026-05-31). When >= 0, the CPU joints path
     * (Myosin.jointConstraints) and the GPU joints kernel path
     * (GPUMoveThing.moveThings, post-.execute()) log per-Myosin joint
     * force/torque outputs for myoIdx < DIAG_DUMP_MYO_LIMIT at the matching
     * step. For the GPU path, this requires jointForceSum/jointTorqueSum to
     * be in the chained plan's transferToHost list — gated on this flag at
     * plan build time. -1 = off, no overhead.
     */
    public static int DIAG_DUMP_JOINTS_STEP = -1;
    public static final int DIAG_DUMP_MYO_LIMIT = 5;

    /** Public step counter for the CPU joint dump to align with the GPU dump. */
    public static int getStepCounter() { return stepCounter; }

    private static final int runSeed = Env.mtRNG.nextInt();

    // Brownian-rule codes (cached per slot in classifyThings).
    private static final int RULE_FIL   = 0;
    private static final int RULE_MYO   = 1;
    private static final int RULE_LEVER = 2;

    // ----- capacity / current count -----
    private static int slotCap   = 0;
    private static int slotCount = 0;
    private static int myoCap    = 0;
    private static int myoJointCt = 0;   // Myosins with all 3 sub-Things GPU-handled

    // ----- shared per-Thing SoA buffers (capacity slotCap) -----
    private static FloatArray coord;          // slotCap * 3
    private static FloatArray uVec;           // slotCap * 3
    private static FloatArray yVec;           // slotCap * 3
    // CPU contributions are uploaded fresh each step (input only). The joints
    // task writes its rod/lever/motor force/torque additions to a SEPARATE
    // device-side delta buffer (jointForceSum / jointTorqueSum) so the move
    // task can read both inputs as plain reads — avoids the inter-task
    // read-modify-write semantics on a single shared buffer that TornadoVM
    // does not always honor (writes from task 1 not visible to task 2 when
    // the buffer is in transferToDevice EVERY_EXECUTION).
    private static FloatArray cpuForceSum;    // slotCap * 3 -- transferToDevice (read by move)
    private static FloatArray cpuTorqueSum;   // slotCap * 3 -- transferToDevice (read by move)
    private static FloatArray jointForceSum;  // slotCap * 3 -- transferToDevice (host zero-init, joint writes, move reads)
    private static FloatArray jointTorqueSum; // slotCap * 3 -- transferToDevice (host zero-init, joint writes, move reads)
    private static FloatArray bTransGam;      // slotCap * 3
    private static FloatArray bRotGam;        // slotCap * 3
    private static FloatArray brownianScales; // slotCap * 2
    private static FloatArray velMask;        // slotCap * 3

    // ----- per-Myosin joint inputs (capacity myoCap) -----
    private static IntArray   rodSlots;       // myoCap -- move slot of myoRod
    private static IntArray   leverSlots;     // myoCap -- move slot of myoLever
    private static IntArray   motorSlots;     // myoCap -- move slot of myoMotor
    private static FloatArray myoDrags;       // myoCap * 9 -- packed drag tensors for rod/lever/motor
    private static IntArray   cockedFlags;    // myoCap

    // ----- small inputs -----
    private static FloatArray params;         // move kernel: [0]=deltaT, [1]=brownianForceMag
    private static FloatArray jointParams;    // joints kernel: 13 floats
    private static IntArray   counts;         // [0]=slotCount, [1]=stepCount, [2]=runSeed, [3]=myoJointCt

    // ----- CPU-side index of packed Things, by slot -----
    private static int[]   gpuThingIndices;   // slot -> Thing.theThings[] index
    private static int[]   brownianRule;      // slot -> RULE_FIL / RULE_MYO / RULE_LEVER
    private static int[]   thingNumberToMoveSlot;  // Thing.myThingNumber -> move slot (-1 if not GPU-handled)
    private static int[]   jointSlotToMyoIdx;      // joint slot -> Myosin.theMyosins[] index
    private static Thing[] cpuFallback;
    private static int     cpuFallbackCt = 0;
    private static int     lastThingCt   = -1;
    private static boolean topologyDirty = true;
    private static boolean coordsDirty   = true;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;
    private static GridScheduler        gridScheduler;

    private static int stepCounter = 0;

    private static final int MOVE_KERNEL_BLOCK_SIZE = 64;
    private static final int JOINTS_KERNEL_BLOCK_SIZE = 64;

    // Timing accumulators
    private static long packNanos       = 0;
    private static long execNanos       = 0;
    private static long unpackNanos     = 0;
    private static long totalNanos      = 0;
    private static long jointPackNanos  = 0;
    private static int  callCount       = 0;

    // ---------------- Worker pool -----------------------------------------
    private static final int N_WORKERS = Math.max(1,
            Math.min(Env.allThreadCt, Runtime.getRuntime().availableProcessors()));
    private static final int OP_PACK_FULL          = 0;
    private static final int OP_PACK_RESIDENT      = 1;
    private static final int OP_UNPACK             = 2;
    private static final int OP_DERIVED_AND_BRIDGE = 3;
    private static final int OP_PACK_JOINTS        = 4;  // per-Myosin: slots+drags+cocked
    private static final int DERIVED_BRIDGE_PARALLEL_THRESHOLD = 8000;
    private static Thread[] workers;
    private static final Object phaseLock = new Object();
    private static int     currentPhase   = 0;
    private static int     workersDone    = 0;
    private static int     workOp         = 0;
    private static int     workSlotCount  = 0;
    private static int     workChunkSize  = 0;
    private static float   sBTransCoeff;
    private static float   sBRotCoeff;
    private static float   sXLinkTAttn;
    private static float   sXLinkRAttn;
    private static float   sMyoBrownian;
    private static boolean sBrownianFilOff;
    private static boolean sBrownianMyoOff;

    // -------------------------------------------------------------------------
    // Wang hash — 32-bit integer mixer.
    // -------------------------------------------------------------------------
    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    // -------------------------------------------------------------------------
    // fastAcos approximation matching CPU Pt3D.fastAcos: small-angle form
    // sqrt(2(1-|x|)) outside the ±0.95 band (<0.6% error at threshold), and
    // the Abramowitz & Stegun 4.4.46 polynomial in the middle. The original
    // CPU code uses Math.acos in the middle band but PTX can't lower it
    // (ReinterpretNode unimplemented). Caller must clamp x to [-1, 1].
    // -------------------------------------------------------------------------
    private static float fastAcosF(float x) {
        if (x > 0.95f) {
            return (float) Math.sqrt(2.0f * (1.0f - x));
        } else if (x < -0.95f) {
            return 3.14159265f - (float) Math.sqrt(2.0f * (1.0f + x));
        } else {
            float ax = (x < 0f) ? -x : x;
            float p = (-0.0187293f * ax + 0.0742610f) * ax - 0.2121144f;
            p = (p * ax + 1.5707963f);
            p = p * (float) Math.sqrt(1.0f - ax);
            return (x < 0f) ? (3.14159265f - p) : p;
        }
    }

    // -------------------------------------------------------------------------
    // Joint kernel: one thread per Myosin. Reads rod/lever/motor pose from
    // the SHARED coord/uVec FloatArrays (move-slot indexing), computes four
    // joint contributions (lever-motor force, lever-motor torque, rod-lever
    // force, rod-lever torque), and WRITES (set, not add) them to a separate
    // jointForceSum / jointTorqueSum delta buffer. The host uploads a
    // zero-initialised delta buffer each step, so the kernel's writes are
    // the per-step joint contribution. The move task reads cpuForceSum +
    // jointForceSum (and cpuTorqueSum + jointTorqueSum) as plain reads.
    // Each Myosin's three sub-slots are unique, so writes are conflict-free
    // without atomics.
    // -------------------------------------------------------------------------
    private static void jointsKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray jointForceSum,
            FloatArray jointTorqueSum,
            IntArray   rodSlots,
            IntArray   leverSlots,
            IntArray   motorSlots,
            FloatArray myoDrags,
            IntArray   cockedFlags,
            FloatArray jointParams,
            IntArray   counts) {

        int M = counts.get(3);

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

            float rcx = coord.get(r3),  rcy = coord.get(r3 + 1), rcz = coord.get(r3 + 2);
            float rux = uVec.get(r3),   ruy = uVec.get(r3 + 1),  ruz = uVec.get(r3 + 2);
            float lcx = coord.get(l3),  lcy = coord.get(l3 + 1), lcz = coord.get(l3 + 2);
            float lux = uVec.get(l3),   luy = uVec.get(l3 + 1),  luz = uVec.get(l3 + 2);
            float mcx = coord.get(mo3), mcy = coord.get(mo3 + 1), mcz = coord.get(mo3 + 2);
            float mux = uVec.get(mo3),  muy = uVec.get(mo3 + 1),  muz = uVec.get(mo3 + 2);

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

            float halfRod   = 0.5f * rodLen;
            float halfLever = 0.5f * leverLen;
            float halfMotor = 0.5f * motorLen;

            float le1x = lcx - halfLever * lux, le1y = lcy - halfLever * luy, le1z = lcz - halfLever * luz;
            float le2x = lcx + halfLever * lux, le2y = lcy + halfLever * luy, le2z = lcz + halfLever * luz;
            float me1x = mcx - halfMotor * mux, me1y = mcy - halfMotor * muy, me1z = mcz - halfMotor * muz;
            float re2x = rcx + halfRod * rux,   re2y = rcy + halfRod * ruy,   re2z = rcz + halfRod * ruz;

            float rodFx = 0f,   rodFy = 0f,   rodFz = 0f;
            float rodTx = 0f,   rodTy = 0f,   rodTz = 0f;
            float leverFx = 0f, leverFy = 0f, leverFz = 0f;
            float leverTx = 0f, leverTy = 0f, leverTz = 0f;
            float motorFx = 0f, motorFy = 0f, motorFz = 0f;
            float motorTx = 0f, motorTy = 0f, motorTz = 0f;

            // applyLeverMotorJointForce
            {
                float dx = le2x - me1x, dy = le2y - me1y, dz = le2z - me1z;
                float dist2 = dx * dx + dy * dy + dz * dz;
                float strainDist = (float) Math.sqrt(dist2);
                float invStrain = (strainDist > 0f) ? (1.0f / strainDist) : 0f;
                float l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                float l2x = -l1x, l2y = -l1y, l2z = -l1z;

                float cosBh = -(mux * l1x + muy * l1y + muz * l1z);
                if (cosBh > 1.0f)  cosBh = 1.0f;
                if (cosBh < -1.0f) cosBh = -1.0f;
                float cosAlphH  = (float) Math.sin(fastAcosF(cosBh));
                float cosAlphH2 = cosAlphH * cosAlphH;
                float lSqH     = 1.0e-12f * motorLen * motorLen;
                float CxH      = cosBh * cosBh / mBTGx;
                float CperpH   = cosAlphH2 / mBTGy;
                float CthetaH  = lSqH * cosAlphH2 / (4.0f * mBRGy);
                float moveCh   = CxH + CperpH + CthetaH;

                float cosBt = lux * l2x + luy * l2y + luz * l2z;
                if (cosBt > 1.0f)  cosBt = 1.0f;
                if (cosBt < -1.0f) cosBt = -1.0f;
                float cosAlphT  = (float) Math.sin(fastAcosF(cosBt));
                float cosAlphT2 = cosAlphT * cosAlphT;
                float lSqT     = 1.0e-12f * leverLen * leverLen;
                float CxT      = cosBt * cosBt / lBTGx;
                float CperpT   = cosAlphT2 / lBTGy;
                float CthetaT  = lSqT * cosAlphT2 / (4.0f * lBRGy);
                float moveCt   = CxT + CperpT + CthetaT;

                float denom = dt * (moveCh + moveCt);
                float forceMag = (denom > 0f) ? (j1FracMove * 1.0e-6f * strainDist / denom) : 0f;

                float Fx = forceMag * l1x, Fy = forceMag * l1y, Fz = forceMag * l1z;

                motorFx += Fx; motorFy += Fy; motorFz += Fz;
                float Rms = -0.5e-6f * motorLen * j1FracR;
                float Rmx = Rms * mux, Rmy = Rms * muy, Rmz = Rms * muz;
                motorTx += Rmy * Fz - Rmz * Fy;
                motorTy += Rmz * Fx - Rmx * Fz;
                motorTz += Rmx * Fy - Rmy * Fx;

                Fx = -Fx; Fy = -Fy; Fz = -Fz;
                leverFx += Fx; leverFy += Fy; leverFz += Fz;
                float Rls = 0.5e-6f * leverLen * j1FracR;
                float Rlx = Rls * lux, Rly = Rls * luy, Rlz = Rls * luz;
                leverTx += Rly * Fz - Rlz * Fy;
                leverTy += Rlz * Fx - Rlx * Fz;
                leverTz += Rlx * Fy - Rly * Fx;
            }

            // applyLeverMotorJointTorque
            {
                float tvx = luy * muz - luz * muy;
                float tvy = luz * mux - lux * muz;
                float tvz = lux * muy - luy * mux;
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

            // applyRodLeverJointForce
            {
                float dx = re2x - le1x, dy = re2y - le1y, dz = re2z - le1z;
                float dist2 = dx * dx + dy * dy + dz * dz;
                float strainDist = (float) Math.sqrt(dist2);
                float invStrain = (strainDist > 0f) ? (1.0f / strainDist) : 0f;
                float l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                float l2x = -l1x, l2y = -l1y, l2z = -l1z;

                float cosB1 = -(lux * l1x + luy * l1y + luz * l1z);
                if (cosB1 > 1.0f)  cosB1 = 1.0f;
                if (cosB1 < -1.0f) cosB1 = -1.0f;
                float cosAlph1  = (float) Math.sin(fastAcosF(cosB1));
                float cosAlph1_2 = cosAlph1 * cosAlph1;
                float lSq1     = 1.0e-12f * leverLen * leverLen;
                float Cx1      = cosB1 * cosB1 / lBTGx;
                float Cperp1   = cosAlph1_2 / lBTGy;
                float Ctheta1  = lSq1 * cosAlph1_2 / (4.0f * lBRGy);
                float moveC1   = Cx1 + Cperp1 + Ctheta1;

                float cosB2 = rux * l2x + ruy * l2y + ruz * l2z;
                if (cosB2 > 1.0f)  cosB2 = 1.0f;
                if (cosB2 < -1.0f) cosB2 = -1.0f;
                float cosAlph2  = (float) Math.sin(fastAcosF(cosB2));
                float cosAlph2_2 = cosAlph2 * cosAlph2;
                float lSq2     = 1.0e-12f * rodLen * rodLen;
                float Cx2      = cosB2 * cosB2 / rBTGx;
                float Cperp2   = cosAlph2_2 / rBTGy;
                float Ctheta2  = lSq2 * cosAlph2_2 / (4.0f * rBRGy);
                float moveC2   = Cx2 + Cperp2 + Ctheta2;

                float denom = dt * (moveC1 + moveC2);
                float forceMag = (denom > 0f) ? (j2FracMove * 1.0e-6f * strainDist / denom) : 0f;

                float Fx = forceMag * l1x, Fy = forceMag * l1y, Fz = forceMag * l1z;

                leverFx += Fx; leverFy += Fy; leverFz += Fz;
                float Rls = -0.5e-6f * leverLen * j2FracR;
                float Rlx = Rls * lux, Rly = Rls * luy, Rlz = Rls * luz;
                leverTx += Rly * Fz - Rlz * Fy;
                leverTy += Rlz * Fx - Rlx * Fz;
                leverTz += Rlx * Fy - Rly * Fx;

                Fx = -Fx; Fy = -Fy; Fz = -Fz;
                rodFx += Fx; rodFy += Fy; rodFz += Fz;
                float Rrs = 0.5e-6f * rodLen * j2FracR;
                float Rrx = Rrs * rux, Rry = Rrs * ruy, Rrz = Rrs * ruz;
                rodTx += Rry * Fz - Rrz * Fy;
                rodTy += Rrz * Fx - Rrx * Fz;
                rodTz += Rrx * Fy - Rry * Fx;
            }

            // applyRodLeverJointTorque
            {
                float tvx = ruy * luz - ruz * luy;
                float tvy = ruz * lux - rux * luz;
                float tvz = rux * luy - ruy * lux;
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

            // Write joint contributions to the device-side delta buffers.
            // Host uploaded zeros; this is the per-step contribution per slot.
            // Each Myosin's three sub-slots are unique, so writes are conflict-free.
            jointForceSum.set(r3,      rodFx);
            jointForceSum.set(r3 + 1,  rodFy);
            jointForceSum.set(r3 + 2,  rodFz);
            jointTorqueSum.set(r3,     rodTx);
            jointTorqueSum.set(r3 + 1, rodTy);
            jointTorqueSum.set(r3 + 2, rodTz);

            jointForceSum.set(l3,      leverFx);
            jointForceSum.set(l3 + 1,  leverFy);
            jointForceSum.set(l3 + 2,  leverFz);
            jointTorqueSum.set(l3,     leverTx);
            jointTorqueSum.set(l3 + 1, leverTy);
            jointTorqueSum.set(l3 + 2, leverTz);

            jointForceSum.set(mo3,      motorFx);
            jointForceSum.set(mo3 + 1,  motorFy);
            jointForceSum.set(mo3 + 2,  motorFz);
            jointTorqueSum.set(mo3,     motorTx);
            jointTorqueSum.set(mo3 + 1, motorTy);
            jointTorqueSum.set(mo3 + 2, motorTz);
        }
    }

    // -------------------------------------------------------------------------
    // Move kernel — sums the CPU-contributed forceSum and the device-side
    // joint-delta buffer to get the complete per-Thing force/torque, then
    // generates Brownian inline via Wang hash and integrates pose.
    // -------------------------------------------------------------------------
    private static void moveThingKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray cpuForceSum,
            FloatArray cpuTorqueSum,
            FloatArray jointForceSum,
            FloatArray jointTorqueSum,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray brownianScales,
            FloatArray velMask,
            FloatArray params,
            IntArray   counts) {

        int   N         = counts.get(0);
        int   stepCount = counts.get(1);
        int   runSeed   = counts.get(2);
        float dt        = params.get(0);
        float brownianForceMag = params.get(1);

        for (@Parallel int m = 0; m < coord.getSize() / 3; m++) {
            if (m >= N) { return; }

            int i3 = m * 3;
            int i2 = m * 2;

            float cx = coord.get(i3);
            float cy = coord.get(i3 + 1);
            float cz = coord.get(i3 + 2);
            float ux = uVec.get(i3);
            float uy = uVec.get(i3 + 1);
            float uz = uVec.get(i3 + 2);
            float yx = yVec.get(i3);
            float yy = yVec.get(i3 + 1);
            float yz = yVec.get(i3 + 2);

            float zx = uy * yz - uz * yy;
            float zy = uz * yx - ux * yz;
            float zz = ux * yy - uy * yx;
            float zlen = 1.0f / (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx *= zlen; zy *= zlen; zz *= zlen;

            float fx = cpuForceSum.get(i3)     + jointForceSum.get(i3);
            float fy = cpuForceSum.get(i3 + 1) + jointForceSum.get(i3 + 1);
            float fz = cpuForceSum.get(i3 + 2) + jointForceSum.get(i3 + 2);
            float tx = cpuTorqueSum.get(i3)     + jointTorqueSum.get(i3);
            float ty = cpuTorqueSum.get(i3 + 1) + jointTorqueSum.get(i3 + 1);
            float tz = cpuTorqueSum.get(i3 + 2) + jointTorqueSum.get(i3 + 2);

            float bfx = ux * fx + uy * fy + uz * fz;
            float bfy = yx * fx + yy * fy + yz * fz;
            float bfz = zx * fx + zy * fy + zz * fz;
            float btx = ux * tx + uy * ty + uz * tz;
            float bty = yx * tx + yy * ty + yz * tz;
            float btz = zx * tx + zy * ty + zz * tz;

            int base = (m * 1000003) ^ (stepCount * 999983) ^ (runSeed * 7919);
            int h1 = wangHash(base);
            int h2 = wangHash(base ^ 0x9e3779b9);
            int h3 = wangHash(base ^ 0x85ebca6b);
            int h4 = wangHash(base ^ 0xc2b2ae35);
            int h5 = wangHash(base ^ 0x517cc1b7);
            int h6 = wangHash(base ^ 0x1f0a7ed5);

            float u1 = Math.max(1.0e-7f, (h1 >>> 1) / 2147483647.0f);
            float u2 = (h2 >>> 1) / 2147483647.0f;
            float u3 = Math.max(1.0e-7f, (h3 >>> 1) / 2147483647.0f);
            float u4 = (h4 >>> 1) / 2147483647.0f;
            float u5 = Math.max(1.0e-7f, (h5 >>> 1) / 2147483647.0f);
            float u6 = (h6 >>> 1) / 2147483647.0f;

            float r1 = (float) Math.sqrt(-2.0f * (float) Math.log(u1));
            float theta1 = 2.0f * 3.14159265f * u2;
            float gfx = r1 * (float) Math.cos(theta1);
            float gtx = r1 * (float) Math.sin(theta1);

            float r2 = (float) Math.sqrt(-2.0f * (float) Math.log(u3));
            float theta2 = 2.0f * 3.14159265f * u4;
            float gfy = r2 * (float) Math.cos(theta2);
            float gty = r2 * (float) Math.sin(theta2);

            float r3v = (float) Math.sqrt(-2.0f * (float) Math.log(u5));
            float theta3 = 2.0f * 3.14159265f * u6;
            float gfz = r3v * (float) Math.cos(theta3);
            float gtz = r3v * (float) Math.sin(theta3);

            float btgX = bTransGam.get(i3);
            float btgY = bTransGam.get(i3 + 1);
            float btgZ = bTransGam.get(i3 + 2);
            float brgX = bRotGam.get(i3);
            float brgY = bRotGam.get(i3 + 1);
            float brgZ = bRotGam.get(i3 + 2);

            float tScale = brownianScales.get(i2);
            float rScale = brownianScales.get(i2 + 1);

            bfx += tScale * brownianForceMag * (float) Math.sqrt(btgX) * gfx;
            bfy += tScale * brownianForceMag * (float) Math.sqrt(btgY) * gfy;
            bfz += tScale * brownianForceMag * (float) Math.sqrt(btgZ) * gfz;
            btx += rScale * brownianForceMag * (float) Math.sqrt(brgX) * gtx;
            bty += rScale * brownianForceMag * (float) Math.sqrt(brgY) * gty;
            btz += rScale * brownianForceMag * (float) Math.sqrt(brgZ) * gtz;

            float bvx = 1.0e6f * bfx / btgX;
            float bvy = 1.0e6f * bfy / btgY;
            float bvz = 1.0e6f * bfz / btgZ;
            float bwx = btx / brgX;
            float bwy = bty / brgY;
            float bwz = btz / brgZ;

            float vx = ux * bvx + yx * bvy + zx * bvz;
            float vy = uy * bvx + yy * bvy + zy * bvz;
            float vz = uz * bvx + yz * bvy + zz * bvz;

            vx *= velMask.get(i3);
            vy *= velMask.get(i3 + 1);
            vz *= velMask.get(i3 + 2);

            coord.set(i3,     cx + dt * vx);
            coord.set(i3 + 1, cy + dt * vy);
            coord.set(i3 + 2, cz + dt * vz);

            float uTransInZ = -bwy * dt;
            float uTransInY =  bwz * dt;
            float nuX = ux + yx * uTransInY + zx * uTransInZ;
            float nuY = uy + yy * uTransInY + zy * uTransInZ;
            float nuZ = uz + yz * uTransInY + zz * uTransInZ;
            float nuInv = 1.0f / (float) Math.sqrt(nuX * nuX + nuY * nuY + nuZ * nuZ);
            uVec.set(i3,     nuX * nuInv);
            uVec.set(i3 + 1, nuY * nuInv);
            uVec.set(i3 + 2, nuZ * nuInv);

            float yTransInX = -uTransInY;
            float yTransInZ =  bwx * dt;
            float nyX = ux * yTransInX + yx + zx * yTransInZ;
            float nyY = uy * yTransInX + yy + zy * yTransInZ;
            float nyZ = uz * yTransInX + yz + zz * yTransInZ;
            float nyInv = 1.0f / (float) Math.sqrt(nyX * nyX + nyY * nyY + nyZ * nyZ);
            yVec.set(i3,     nyX * nyInv);
            yVec.set(i3 + 1, nyY * nyInv);
            yVec.set(i3 + 2, nyZ * nyInv);
        }
    }

    // -------------------------------------------------------------------------
    // Lazy allocation + chained plan build.
    // -------------------------------------------------------------------------
    private static void allocateAndBuildPlan(int newSlotCap, int newMyoCap) {
        slotCap = newSlotCap;
        myoCap  = Math.max(1, newMyoCap);

        coord          = new FloatArray(slotCap * 3);
        uVec           = new FloatArray(slotCap * 3);
        yVec           = new FloatArray(slotCap * 3);
        cpuForceSum    = new FloatArray(slotCap * 3);
        cpuTorqueSum   = new FloatArray(slotCap * 3);
        jointForceSum  = new FloatArray(slotCap * 3);  // host zeroed each step (sparse joint writes on device)
        jointTorqueSum = new FloatArray(slotCap * 3);
        bTransGam      = new FloatArray(slotCap * 3);
        bRotGam        = new FloatArray(slotCap * 3);
        brownianScales = new FloatArray(slotCap * 2);
        velMask        = new FloatArray(slotCap * 3);

        rodSlots       = new IntArray(myoCap);
        leverSlots     = new IntArray(myoCap);
        motorSlots     = new IntArray(myoCap);
        myoDrags       = new FloatArray(myoCap * 9);
        cockedFlags    = new IntArray(myoCap);
        jointSlotToMyoIdx = new int[myoCap];

        params      = new FloatArray(2);
        jointParams = new FloatArray(13);
        counts      = new IntArray(4);

        gpuThingIndices       = new int[slotCap];
        brownianRule          = new int[slotCap];
        cpuFallback           = new Thing[Math.max(64, slotCap)];

        // Chained TaskGraph: joints task writes per-step joint contributions
        // to a SEPARATE device-side delta buffer (jointForceSum / jointTorqueSum
        // are uploaded zero-initialised each step); move task reads
        // cpuForceSum + jointForceSum (and cpuTorqueSum + jointTorqueSum) as
        // plain reads. coord/uVec/yVec are shared between tasks (read by both,
        // move writes new pose).
        TaskGraph tg = new TaskGraph("chained")
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                              coord, uVec, yVec,
                              cpuForceSum, cpuTorqueSum,
                              jointForceSum, jointTorqueSum,
                              bTransGam, bRotGam,
                              brownianScales, velMask,
                              rodSlots, leverSlots, motorSlots,
                              myoDrags, cockedFlags,
                              jointParams, params, counts)
            .task("joints",
                  GPUMoveThing::jointsKernel,
                  coord, uVec,
                  jointForceSum, jointTorqueSum,
                  rodSlots, leverSlots, motorSlots,
                  myoDrags, cockedFlags,
                  jointParams, counts)
            .task("move",
                  GPUMoveThing::moveThingKernel,
                  coord, uVec, yVec,
                  cpuForceSum, cpuTorqueSum,
                  jointForceSum, jointTorqueSum,
                  bTransGam, bRotGam,
                  brownianScales, velMask,
                  params, counts)
            ;

        if (DIAG_DUMP_JOINTS_STEP >= 0) {
            tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                                   coord, uVec, yVec,
                                   jointForceSum, jointTorqueSum);
        } else {
            tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                                   coord, uVec, yVec);
        }

        itg  = tg.snapshot();
        plan = new TornadoExecutionPlan(itg);

        WorkerGrid moveWorker = new WorkerGrid1D(slotCap);
        moveWorker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
        WorkerGrid jointWorker = new WorkerGrid1D(myoCap);
        jointWorker.setLocalWork(JOINTS_KERNEL_BLOCK_SIZE, 1, 1);

        gridScheduler = new GridScheduler("chained.move", moveWorker);
        gridScheduler.addWorkerGrid("chained.joints", jointWorker);

        topologyDirty = true;
        coordsDirty   = true;

        // Ensure thingNumberToMoveSlot is sized to cover all current Things.
        ensureThingNumberMapCapacity();

        System.out.printf("GPUMoveThing: slotCap=%d myoCap=%d moveBlock=%d jointBlock=%d runSeed=%d nWorkers=%d%n",
                          slotCap, myoCap, MOVE_KERNEL_BLOCK_SIZE, JOINTS_KERNEL_BLOCK_SIZE,
                          runSeed, N_WORKERS);
    }

    private static void ensureThingNumberMapCapacity() {
        int needed = Math.max(slotCap, Thing.thingCt) + 8;
        if (thingNumberToMoveSlot == null || thingNumberToMoveSlot.length < needed) {
            thingNumberToMoveSlot = new int[needed * 2];
        }
    }

    private static void closePlan() {
        if (plan != null) {
            try { plan.close(); } catch (Exception e) { /* best effort */ }
            plan = null;
            itg  = null;
        }
    }

    public static void invalidatePlan() {
        closePlan();
        topologyDirty = true;
        coordsDirty   = true;
    }

    // -------------------------------------------------------------------------
    // Classify Things into GPU vs CPU fallback, and build Myosin joint list.
    // -------------------------------------------------------------------------
    private static void classifyThings() {
        int n  = 0;
        int cn = 0;
        int tc = Thing.thingCt;

        double lpActiveV   = Env.lpActive.getValue();
        boolean myosinsOff = Env.myosinsOff;

        ensureThingNumberMapCapacity();
        // Sentinel -1 = "not GPU-handled".
        java.util.Arrays.fill(thingNumberToMoveSlot, 0, Math.min(thingNumberToMoveSlot.length, tc + 1), -1);

        for (int i = 0; i < tc; i++) {
            Thing t = Thing.theThings[i];
            if (t != null) t.gpuHandled = false;
        }

        for (int i = 0; i < tc; i++) {
            Thing t = Thing.theThings[i];
            if (t == null || t.removeMe) continue;

            int rule;

            if (t instanceof FilSegment) {
                FilSegment f = (FilSegment) t;
                if (f.motherFil != null || f.actAOn
                        || (f.isLpSeg && lpActiveV == 0)) {
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                rule = RULE_FIL;
            } else if (t instanceof MyoMotor || t instanceof MyoRod) {
                if (myosinsOff) {
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                rule = RULE_MYO;
            } else if (t instanceof MyoLever) {
                if (myosinsOff) {
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                rule = RULE_LEVER;
            } else {
                if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                continue;
            }

            if (n >= slotCap) {
                if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                continue;
            }

            gpuThingIndices[n] = i;
            brownianRule[n]    = rule;
            t.gpuHandled       = true;
            thingNumberToMoveSlot[t.myThingNumber] = n;

            int i3 = n * 3;
            velMask.set(i3,     1.0f);
            velMask.set(i3 + 1, 1.0f);
            velMask.set(i3 + 2, 1.0f);

            n++;
        }

        slotCount     = n;
        cpuFallbackCt = cn;
        lastThingCt   = tc;
        topologyDirty = false;
        coordsDirty   = true;

        // Build the Myosin joint slot list. Each Myosin whose rod/lever/motor
        // are ALL GPU-handled gets entries at index [0..myoJointCt) in
        // rodSlots/leverSlots/motorSlots. CPU-fallback Myosins (myosinsOff,
        // or any sub-Thing not GPU-handled) are omitted; their joints are
        // handled by the CPU Myosin.myoThreads dispatch (which short-circuits
        // on Env.useGPU at the per-Myosin level — see Myosin.jointConstraints).
        int mj = 0;
        Myosin[] myos = Myosin.theMyosins;
        int myoCt = Myosin.myoCt;
        for (int m = 0; m < myoCt; m++) {
            Myosin myo = myos[m];
            if (myo == null) continue;
            int rIdx = myo.myoRod.myThingNumber;
            int lIdx = myo.myoLever.myThingNumber;
            int mIdx = myo.myoMotor.myThingNumber;
            if (rIdx >= thingNumberToMoveSlot.length
             || lIdx >= thingNumberToMoveSlot.length
             || mIdx >= thingNumberToMoveSlot.length) continue;
            int rs = thingNumberToMoveSlot[rIdx];
            int ls = thingNumberToMoveSlot[lIdx];
            int ms = thingNumberToMoveSlot[mIdx];
            if (rs < 0 || ls < 0 || ms < 0) continue;
            if (mj >= myoCap) continue;
            rodSlots.set(mj,   rs);
            leverSlots.set(mj, ls);
            motorSlots.set(mj, ms);
            jointSlotToMyoIdx[mj] = m;
            mj++;
        }
        myoJointCt = mj;
        // DIAG_CPU_JOINTS: force the GPU joints task to a no-op by zeroing
        // the count the kernel reads (counts[3]). Pack is skipped (gated on
        // myoJointCt > 0 in moveThings()). jointForceSum/jointTorqueSum
        // remain at their per-slot zero-init from packRange so the move
        // kernel reads cpuForceSum + 0 = cpuForceSum (which holds the CPU
        // joint contributions from Myosin.MyosinThreads).
        if (DIAG_CPU_JOINTS) {
            myoJointCt = 0;
        }
    }

    public static void onStepStart() {
        int desiredSlotCap = Math.max(1024, Thing.thingCt * 2);
        int desiredMyoCap  = Math.max(1024, Myosin.myoCt   * 2);
        if (plan == null) {
            allocateAndBuildPlan(desiredSlotCap, desiredMyoCap);
        } else if (Thing.thingCt > slotCap || Myosin.myoCt > myoCap) {
            closePlan();
            int ns = (Thing.thingCt > slotCap) ? Math.max(slotCap * 2, Thing.thingCt * 2) : slotCap;
            int nm = (Myosin.myoCt  > myoCap)  ? Math.max(myoCap  * 2, Myosin.myoCt  * 2) : myoCap;
            allocateAndBuildPlan(ns, nm);
        }
        if (topologyDirty || Thing.thingCt != lastThingCt) {
            classifyThings();
        }
    }

    // -------------------------------------------------------------------------
    // Parallel pack / unpack worker loop.
    // -------------------------------------------------------------------------
    private static void ensureWorkers() {
        if (workers != null) return;
        workers = new Thread[N_WORKERS];
        for (int w = 0; w < N_WORKERS; w++) {
            final int id = w;
            Thread t = new Thread(() -> workerLoop(id),
                                  "GPUMoveThing-worker-" + w);
            t.setDaemon(true);
            workers[w] = t;
            t.start();
        }
    }

    private static void workerLoop(int id) {
        int lastPhase = 0;
        while (true) {
            int op, sc, chunk;
            synchronized (phaseLock) {
                while (currentPhase == lastPhase) {
                    try { phaseLock.wait(); }
                    catch (InterruptedException e) { return; }
                }
                lastPhase = currentPhase;
                op    = workOp;
                sc    = workSlotCount;
                chunk = workChunkSize;
            }
            int start = id * chunk;
            int end   = Math.min(start + chunk, sc);
            if (start < end) {
                switch (op) {
                    case OP_PACK_FULL:     packRange(start, end, true);  break;
                    case OP_PACK_RESIDENT: packRange(start, end, false); break;
                    case OP_UNPACK:        unpackRange(start, end);      break;
                    case OP_DERIVED_AND_BRIDGE:
                        Thing.recomputeDerivedSoA(start, end);
                        break;
                    case OP_PACK_JOINTS:   packJointsRange(start, end);  break;
                    default: /* no-op */ break;
                }
            }
            synchronized (phaseLock) {
                workersDone++;
                phaseLock.notifyAll();
            }
        }
    }

    private static void dispatchAndWait(int op, int sc) {
        ensureWorkers();
        int chunk = (sc + N_WORKERS - 1) / N_WORKERS;
        if (chunk < 1) chunk = 1;
        synchronized (phaseLock) {
            workOp        = op;
            workSlotCount = sc;
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
    // Pack per-slot move-kernel inputs (pose, force/torque, drags, brownian
    // scales) for a contiguous slot range.
    // -------------------------------------------------------------------------
    private static void packRange(int slotStart, int slotEnd, boolean packCoords) {
        Thing[] theThings  = Thing.theThings;
        int[]   indices    = gpuThingIndices;
        int[]   rules      = brownianRule;
        float[] soaForce   = Thing.soaForceSum;
        float[] soaTorque  = Thing.soaTorqueSum;
        float[] soaCoordArr = Thing.soaCoord;
        float[] soaUVecArr  = Thing.soaUVec;
        float[] soaYVecArr  = Thing.soaYVec;
        float   bTransCoef = sBTransCoeff;
        float   bRotCoef   = sBRotCoeff;
        float   xLnT       = sXLinkTAttn;
        float   xLnR       = sXLinkRAttn;
        float   myoBr      = sMyoBrownian;
        boolean bFilOff    = sBrownianFilOff;
        boolean bMyoOff    = sBrownianMyoOff;

        for (int slot = slotStart; slot < slotEnd; slot++) {
            int thingIdx = indices[slot];
            Thing t = theThings[thingIdx];
            int i3 = slot * 3;
            int i2 = slot * 2;
            int s3 = thingIdx * 3;
            int rule = rules[slot];

            // FilSegment coord can change between steps via biochemStep
            // poly/depoly; always re-pack to stay coherent. Myosin types
            // only mutate coord inside the move kernel, so the FloatArray
            // already matches the SoA state on steady steps. Joints task
            // reads the same coord/uVec — myosins always need a valid pose
            // (which they have from the previous kernel write) so this
            // pack-skip is safe across both tasks.
            if (packCoords || rule == RULE_FIL) {
                coord.set(i3,     soaCoordArr[s3]);
                coord.set(i3 + 1, soaCoordArr[s3 + 1]);
                coord.set(i3 + 2, soaCoordArr[s3 + 2]);
                uVec.set(i3,      soaUVecArr[s3]);
                uVec.set(i3 + 1,  soaUVecArr[s3 + 1]);
                uVec.set(i3 + 2,  soaUVecArr[s3 + 2]);
                yVec.set(i3,      soaYVecArr[s3]);
                yVec.set(i3 + 1,  soaYVecArr[s3 + 1]);
                yVec.set(i3 + 2,  soaYVecArr[s3 + 2]);
            }
            cpuForceSum.set(i3,     soaForce[s3]);
            cpuForceSum.set(i3 + 1, soaForce[s3 + 1]);
            cpuForceSum.set(i3 + 2, soaForce[s3 + 2]);
            cpuTorqueSum.set(i3,     soaTorque[s3]);
            cpuTorqueSum.set(i3 + 1, soaTorque[s3 + 1]);
            cpuTorqueSum.set(i3 + 2, soaTorque[s3 + 2]);
            // Zero the joint-delta buffers for this slot; the joints kernel
            // overwrites the rod/lever/motor entries on device. Other slots
            // (FilSegments) keep zero, so the move kernel's cpuForceSum +
            // jointForceSum sum is just cpuForceSum for non-Myosin slots.
            jointForceSum.set(i3,      0f);
            jointForceSum.set(i3 + 1,  0f);
            jointForceSum.set(i3 + 2,  0f);
            jointTorqueSum.set(i3,     0f);
            jointTorqueSum.set(i3 + 1, 0f);
            jointTorqueSum.set(i3 + 2, 0f);
            bTransGam.set(i3,     (float) t.bTransGam.x);
            bTransGam.set(i3 + 1, (float) t.bTransGam.y);
            bTransGam.set(i3 + 2, (float) t.bTransGam.z);
            bRotGam.set(i3,       (float) t.bRotGam.x);
            bRotGam.set(i3 + 1,   (float) t.bRotGam.y);
            bRotGam.set(i3 + 2,   (float) t.bRotGam.z);

            float tScale, rScale;
            if (rule == RULE_FIL) {
                FilSegment f = (FilSegment) t;
                if (bFilOff || f.brownianOff) {
                    tScale = 0f; rScale = 0f;
                } else {
                    float ts = bTransCoef;
                    float rs = bRotCoef;
                    if (f.linkedToCt > 0) {
                        ts = ts / (1.0f + xLnT * f.linkedToCt);
                        rs = rs / (1.0f + xLnR * f.linkedToCt);
                    }
                    tScale = ts;
                    rScale = (f.filAtEnd1 && f.filAtEnd2) ? 0f : rs;
                }
            } else if (rule == RULE_MYO) {
                if (bMyoOff) { tScale = 0f; rScale = 0f; }
                else         { tScale = myoBr; rScale = myoBr; }
            } else {  // RULE_LEVER
                tScale = 0f;
                rScale = 0f;
            }
            brownianScales.set(i2,     tScale);
            brownianScales.set(i2 + 1, rScale);
        }
    }

    // -------------------------------------------------------------------------
    // Pack per-Myosin joint inputs (drags, cocked flag) for myosins in
    // [start, end). Slot mappings (rodSlots/leverSlots/motorSlots) are
    // populated by classifyThings() and reused across steps.
    // -------------------------------------------------------------------------
    private static void packJointsRange(int start, int end) {
        Myosin[] myos = Myosin.theMyosins;
        int[] toMyo = jointSlotToMyoIdx;
        for (int mj = start; mj < end; mj++) {
            Myosin myo = myos[toMyo[mj]];
            MyoRod   rod   = myo.myoRod;
            MyoLever lever = myo.myoLever;
            MyoMotor motor = myo.myoMotor;
            int d9 = mj * 9;
            myoDrags.set(d9,     (float) rod.bTransGam.x);
            myoDrags.set(d9 + 1, (float) rod.bTransGam.y);
            myoDrags.set(d9 + 2, (float) rod.bRotGam.y);
            myoDrags.set(d9 + 3, (float) lever.bTransGam.x);
            myoDrags.set(d9 + 4, (float) lever.bTransGam.y);
            myoDrags.set(d9 + 5, (float) lever.bRotGam.y);
            myoDrags.set(d9 + 6, (float) motor.bTransGam.x);
            myoDrags.set(d9 + 7, (float) motor.bTransGam.y);
            myoDrags.set(d9 + 8, (float) motor.bRotGam.y);
            cockedFlags.set(mj, motor.isCocked() ? 1 : 0);
        }
    }

    private static void unpackRange(int slotStart, int slotEnd) {
        int[]   indices   = gpuThingIndices;
        float[] soaCoordArr = Thing.soaCoord;
        float[] soaUVecArr  = Thing.soaUVec;
        float[] soaYVecArr  = Thing.soaYVec;
        for (int slot = slotStart; slot < slotEnd; slot++) {
            int thingIdx = indices[slot];
            int i3 = slot * 3;
            int s3 = thingIdx * 3;
            soaCoordArr[s3]     = coord.get(i3);
            soaCoordArr[s3 + 1] = coord.get(i3 + 1);
            soaCoordArr[s3 + 2] = coord.get(i3 + 2);
            soaUVecArr[s3]      = uVec.get(i3);
            soaUVecArr[s3 + 1]  = uVec.get(i3 + 1);
            soaUVecArr[s3 + 2]  = uVec.get(i3 + 2);
            soaYVecArr[s3]      = yVec.get(i3);
            soaYVecArr[s3 + 1]  = yVec.get(i3 + 1);
            soaYVecArr[s3 + 2]  = yVec.get(i3 + 2);
        }
    }

    // -------------------------------------------------------------------------
    // Public entry point — chained joints + move.
    // -------------------------------------------------------------------------
    public static void moveThings() {
        long t0 = System.nanoTime();

        if (plan == null || topologyDirty || Thing.thingCt != lastThingCt
            || Myosin.myoCt > myoCap) {
            onStepStart();
        }

        sBTransCoeff   = (float) Env.BTransCoeff.getValue();
        sBRotCoeff     = (float) Env.BRotCoeff.getValue();
        sXLinkTAttn    = (float) Env.xLinkTransAttn.getValue();
        sXLinkRAttn    = (float) Env.xLinkRotAttn.getValue();
        sMyoBrownian   = (float) Env.myoBrownianAttn.getValue();
        sBrownianFilOff = Env.brownianFilMotionOff;
        sBrownianMyoOff = Env.brownianMyoMotionOff;

        long packStart = System.nanoTime();
        int sc = slotCount;
        if (sc > 0) {
            int op = coordsDirty ? OP_PACK_FULL : OP_PACK_RESIDENT;
            dispatchAndWait(op, sc);
            coordsDirty = false;
        }

        // Per-Myosin joint pack — drags + cocked flags only; slot maps were
        // set up by classifyThings() and reused. Skip when no joint Myosins.
        long jointPackStart = System.nanoTime();
        if (myoJointCt > 0) {
            dispatchAndWait(OP_PACK_JOINTS, myoJointCt);
        }
        long jointPackEnd = System.nanoTime();

        params.set(0, (float) Env.deltaT.getValue());
        double kT = Env.Boltz * Env.tempK;
        double dt = Env.deltaT.getValue();
        params.set(1, (float) Math.sqrt(2.0 * kT / dt));

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

        counts.set(0, slotCount);
        counts.set(1, stepCounter);
        counts.set(2, runSeed);
        counts.set(3, myoJointCt);

        long packEnd = System.nanoTime();

        if (slotCount > 0) {
            plan.withGridScheduler(gridScheduler).execute();
        }
        long execEnd = System.nanoTime();

        if (DIAG_DUMP_JOINTS_STEP >= 0 && stepCounter == DIAG_DUMP_JOINTS_STEP) {
            int n = Math.min(DIAG_DUMP_MYO_LIMIT, myoJointCt);
            for (int mj = 0; mj < n; mj++) {
                int rs = rodSlots.get(mj);
                int ls = leverSlots.get(mj);
                int ms = motorSlots.get(mj);
                int rs3 = rs * 3, ls3 = ls * 3, ms3 = ms * 3;
                int myoIdx = jointSlotToMyoIdx[mj];
                System.err.printf("[DIAG_GPU_JOINT step=%d myoIdx=%d] rodF=(%.6e,%.6e,%.6e) leverF=(%.6e,%.6e,%.6e) motorF=(%.6e,%.6e,%.6e)%n",
                    stepCounter, myoIdx,
                    jointForceSum.get(rs3), jointForceSum.get(rs3 + 1), jointForceSum.get(rs3 + 2),
                    jointForceSum.get(ls3), jointForceSum.get(ls3 + 1), jointForceSum.get(ls3 + 2),
                    jointForceSum.get(ms3), jointForceSum.get(ms3 + 1), jointForceSum.get(ms3 + 2));
                System.err.printf("[DIAG_GPU_JOINT step=%d myoIdx=%d] rodT=(%.6e,%.6e,%.6e) leverT=(%.6e,%.6e,%.6e) motorT=(%.6e,%.6e,%.6e)%n",
                    stepCounter, myoIdx,
                    jointTorqueSum.get(rs3), jointTorqueSum.get(rs3 + 1), jointTorqueSum.get(rs3 + 2),
                    jointTorqueSum.get(ls3), jointTorqueSum.get(ls3 + 1), jointTorqueSum.get(ls3 + 2),
                    jointTorqueSum.get(ms3), jointTorqueSum.get(ms3 + 1), jointTorqueSum.get(ms3 + 2));
            }
        }

        if (sc > 0) {
            dispatchAndWait(OP_UNPACK, sc);
        }
        for (int i = 0; i < cpuFallbackCt; i++) {
            cpuFallback[i].moveThing();
        }
        int tc = Thing.thingCt;
        if (tc > 0) {
            if (tc < DERIVED_BRIDGE_PARALLEL_THRESHOLD) {
                Thing.recomputeDerivedSoA(0, tc);
            } else {
                dispatchAndWait(OP_DERIVED_AND_BRIDGE, tc);
            }
            int filCt = FilSegment.filSegmentCt;
            FilSegment[] fils = FilSegment.theFilSegments;
            for (int i = 0; i < filCt; i++) {
                FilSegment fs = fils[i];
                if (fs == null || fs.removeMe) continue;
                fs.xRange = Math.abs(fs.getCoordX() - fs.getEnd2X());
                fs.yRange = Math.abs(fs.getCoordY() - fs.getEnd2Y());
                fs.zRange = Math.abs(fs.getCoordZ() - fs.getEnd2Z());
                fs.end1Pt.x = fs.getEnd1X(); fs.end1Pt.y = fs.getEnd1Y(); fs.end1Pt.z = fs.getEnd1Z();
                fs.end2Pt.x = fs.getEnd2X(); fs.end2Pt.y = fs.getEnd2Y(); fs.end2Pt.z = fs.getEnd2Z();
            }
            int motorCt = MyoMotor.motorCt;
            MyoMotor[] motors = MyoMotor.theMotors;
            for (int i = 0; i < motorCt; i++) {
                MyoMotor m = motors[i];
                if (m == null || m.removeMe || m.bindTip == null) continue;
                m.bindTip.x = m.getEnd2X(); m.bindTip.y = m.getEnd2Y(); m.bindTip.z = m.getEnd2Z();
            }
        }
        long unpackEnd = System.nanoTime();

        packNanos      += packEnd       - packStart;
        jointPackNanos += jointPackEnd  - jointPackStart;
        execNanos      += execEnd       - packEnd;
        unpackNanos    += unpackEnd     - execEnd;
        totalNanos     += unpackEnd     - t0;
        callCount++;
        stepCounter++;
    }

    public static long getTotalNanos()     { return totalNanos;     }
    public static long getPackNanos()      { return packNanos;      }
    public static long getJointPackNanos() { return jointPackNanos; }
    public static long getExecNanos()      { return execNanos;      }
    public static long getUnpackNanos()    { return unpackNanos;    }
    public static int  getCallCount()      { return callCount;      }

    public static int getSlotCount()     { return slotCount;     }
    public static int getCpuFallbackCt() { return cpuFallbackCt; }
    public static int getSlotCap()       { return slotCap;       }
    public static int getMyoCap()        { return myoCap;        }
    public static int getMyoJointCt()    { return myoJointCt;    }
    public static int getNumWorkers()    { return N_WORKERS;     }

    public static void reset() {
        closePlan();
        packNanos = execNanos = unpackNanos = totalNanos = jointPackNanos = 0;
        callCount = 0;
        stepCounter = 0;
        topologyDirty = true;
        coordsDirty   = true;
    }
}
