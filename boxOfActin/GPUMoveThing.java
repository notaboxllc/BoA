package boxOfActin;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.TornadoProfilerResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
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

    // -------------------------------------------------------------------------
    // Phase-0 layout-decision spike (2026-06-02). Two independent env flags:
    //
    //   BOA_SOA_POSE         — when truthy, the move kernel reads/writes pose
    //                          + forces/drags/velMask as separate-axis SoA
    //                          (within the same FloatArrays: x's first, then
    //                          y's, then z's; stride = slotCap). Pack/unpack
    //                          switch to the SoA layout. Forces DIAG_CPU_JOINTS
    //                          true (the joints kernel still expects AoS pose
    //                          and is bypassed by routing joints to CPU). The
    //                          move-task accesses become coalesced (consecutive
    //                          threads read consecutive floats) instead of
    //                          stride-3.
    //
    //   BOA_MOVE_AB_PROFILE  — enables clean A/B measurement of the move
    //                          kernel in isolation. Forces DIAG_CPU_JOINTS and
    //                          DIAG_CPU_DELTA_ADD so the only GPU task per
    //                          step is the move kernel (run via moveOnlyPlan).
    //                          The plan is built with TornadoVM's SILENT
    //                          profiler; per-execute device-kernel /
    //                          read / write times and byte counts are
    //                          accumulated and reported at run end.
    //
    // Either flag works alone (BOA_MOVE_AB_PROFILE alone gives the AoS
    // baseline; both together gives the SoA arm). Defaults are off — no
    // production impact when unset.
    // -------------------------------------------------------------------------
    public static final boolean SOA_POSE;
    public static final boolean MOVE_AB_PROFILE;
    static {
        String s = System.getenv("BOA_SOA_POSE");
        SOA_POSE = (s != null && !s.isEmpty()
                && !s.equals("0") && !s.equalsIgnoreCase("false"));
        String p = System.getenv("BOA_MOVE_AB_PROFILE");
        MOVE_AB_PROFILE = (p != null && !p.isEmpty()
                && !p.equals("0") && !p.equalsIgnoreCase("false"));
        // NOTE: DIAG_CPU_JOINTS / DIAG_CPU_DELTA_ADD are forced below in a
        // SECOND static block placed AFTER their `= false` field initializers
        // (Java runs declarations and static blocks in textual order, so any
        // assignment made here would be overwritten by the later `= false`).
    }

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
     *
     * On the GPU dump path (with DIAG_CPU_JOINTS=false), the dump also runs
     * Myosin.jointConstraints in DRY_RUN mode (Myosin.DIAG_DRY_RUN=true)
     * over the SAME pose state visible to the GPU joints kernel — produces
     * matching CPU joint values for direct per-Myosin comparison without
     * corrupting Thing force/torque accumulators.
     */
    public static int DIAG_DUMP_JOINTS_STEP = -1;
    public static final int DIAG_DUMP_MYO_LIMIT = 5;

    /**
     * Phase 2 F3/F4 debug: dump per-FilSegment jointForceSum / jointTorqueSum
     * at the specified step. Activated via env var BOA_DIAG_DUMP_CHAIN_STEP.
     * Triggers transferToHost of jointForceSum/jointTorqueSum (gated at plan
     * build time, same mechanism as DIAG_DUMP_JOINTS_STEP).
     * Kept in for jba's continued investigation of the 26% bench stiffness
     * deficit (see JOURNAL "Phase 2 F3/F4 — implementation").
     */
    public static int DIAG_DUMP_CHAIN_STEP = -1;

    /**
     * Diagnostic flag (2026-05-31 — delta-buffer transport isolation).
     * When true and Env.useGPU is true:
     *   1. GPU joints kernel still runs and writes jointForceSum / jointTorqueSum
     *      on the device (jointsOnlyPlan).
     *   2. jointForceSum / jointTorqueSum are downloaded to host between the
     *      joints execution and the move execution.
     *   3. Host adds the delta to cpuForceSum / cpuTorqueSum, then zeros the
     *      delta buffer.
     *   4. moveOnlyPlan runs the move kernel with the combined cpuForceSum
     *      (which now contains the joint contributions) and zero jointForceSum.
     *      Net effect: the move kernel sees the joint forces through the same
     *      cpuForceSum path the PASSING DIAG_CPU_JOINTS config uses, instead
     *      of through the device-side delta sum.
     * Default off — no production impact.
     */
    public static boolean DIAG_CPU_DELTA_ADD = false;

    /**
     * Diagnostic flag (2026-06-02 — Phase 1 anchor-spring port). When true
     * and Env.useGPU is true, the device anchor-spring kernel contribution
     * (folded into jointsKernel) is SKIPPED — anchoredFlags is forced to 0
     * for every Myosin in packJointsRange — and Myosin.MyosinThreads keeps
     * dispatching MyosinFixed.applyGPUDroppedForces() so the anchor is
     * applied on CPU (today's behaviour). When false (default), the device
     * kernel applies the anchor and the CPU pass is skipped. Lets jba do a
     * clean CPU-vs-device A/B on the identical build. CPU-only (non-`-gpu`)
     * runs are unaffected either way.
     */
    public static boolean DIAG_CPU_ANCHOR = false;

    /**
     * Diagnostic flag (2026-06-02 — Phase 2 F3/F4 port). Mirrors DIAG_CPU_ANCHOR
     * in shape. Default false: device chainPairForcesKernel applies F3+F4 to
     * every GPU-handled chain segment and the CPU pair is gated off in
     * FilSegment.step(). Set true (via BOA_DIAG_CPU_F3F4=1) to force the kernel
     * to a no-op (classifyThings packs all topology slots as -1, every thread
     * returns early) and re-route F3+F4 through the CPU pair for clean A/B.
     * CPU-only (non-`-gpu`) runs are unaffected either way.
     * See JOURNAL "Phase 2 F3/F4 — fix" (default flipped 2026-06-03 after the
     * Newton-3 leak fix landed bench ratio on the CPU value).
     */
    public static boolean DIAG_CPU_F3F4 = false;

    /**
     * Diagnostic flag (2026-06-03 — Phase 2 F1 box-boundary port). Mirrors
     * DIAG_CPU_F3F4 in shape. Default false: device boundaryBoxKernel applies
     * the rigid-box wall force/torque to every GPU-handled FilSegment and
     * FilSegment.checkBugOrBoxCollision() skips its CPU
     * checkBugCollisionFromInside() call. Set true (via BOA_DIAG_CPU_F1=1) to
     * force boundaryActive[i] = 0 for every slot — the kernel still runs but
     * every thread early-returns — and re-route F1 through the CPU pair for
     * clean A/B. CPU-only (non-`-gpu`) runs are unaffected either way. The
     * Listeria from-outside path (`Env.simOutsideBug` active) always stays on
     * CPU; only the from-inside box wall is gated here.
     * See JOURNAL "Phase 2 F1 (box) — implementation" (2026-06-03).
     */
    public static boolean DIAG_CPU_F1 = false;

    /**
     * Diagnostic flag (2026-06-03 — tipC device writeback). When true (default),
     * the box boundaryBoxKernel writes a per-endpoint wall-clearance into the
     * boundaryTipC FloatArray, and bridgeBoundaryTipC() min-combines it into
     * FilSegment.end{1,2}TipC after plan.execute() so the existing CPU
     * polymerization gate (stericHindranceEnd2 → end2BiochemSim) sees the
     * device's wall result. Set false (via BOA_DIAG_DEVICE_BOUNDARY_TIPC=0)
     * to disable the writeback for the A/B that reproduces the pre-fix
     * "tips grow through the wall" behaviour — independent of DIAG_CPU_F1
     * (both A/B arms keep the device boundary FORCE/TORQUE active; only
     * the tipC writeback toggles). On CPU runs (non-`-gpu`) and on the
     * Listeria from-outside path, this flag has no effect (CPU
     * bugForcesFromInside / checkBugCollisionFromOutside continue to write
     * tipC=0 on hit unconditionally).
     */
    public static boolean DIAG_DEVICE_BOUNDARY_TIPC = true;

    /**
     * Diagnostic flag (2026-06-03 — Phase 2 F8/F9/F10 motor-force port).
     * Mirrors DIAG_CPU_F3F4 in shape. Default false: the per-step myosin
     * cross-bridge force (F8), uVec alignment torque (F9), and yVec
     * alignment torque (F10) are computed on the device by motorForceKernel
     * + segMotorForceKernel, and the CPU MyoFilLink path
     * (addForces/alignUVecTorque/alignYVecTorque) early-returns inside
     * MyoFilLink.step() when the motor's seg slot maps to the device path.
     * Set true (via BOA_DIAG_CPU_MOTOR=1) to force boundSegSlot[mj] = -1 for
     * every Myosin — the device kernels still launch but every motor
     * early-returns and the per-MyoFilLink force/torque is computed entirely
     * by the CPU pair via the unchanged tipLink.step() chain. CPU-only
     * (non-`-gpu`) runs are unaffected either way. ckRelease / dissociateADP /
     * binding detection (Phase 3) always stay on CPU regardless of this flag.
     * See JOURNAL "Motor force port (F8–F10) — implementation".
     */
    public static boolean DIAG_CPU_MOTOR = false;

    /**
     * Diagnostic flag (2026-06-04 — motor-port borderline release-lag probe).
     * Default false: CPU MyoFilLink.addForces feeds the *current* step's
     * forceDotFil to the tracker (registerValue) AND to link.forceDotFil
     * (the field ckRelease reads). When true, addForces instead feeds the
     * *previous* step's forceDotFil — the per-link prevForceDotFil buffer
     * holds last step's value until next step's addForces consumes it. This
     * induces on the CPU release path the same one-step lag the device path
     * has inherently (ckRelease in step N reads forceDotFil written by
     * bridgeMotorForceWriteback at end of moveThings N-1). Flag is set via
     * BOA_DIAG_RELEASE_LAG=1; default-off behaviour is identical to the
     * existing CPU pair. No effect on the device-handled motors path
     * (gpuMotorHandled gates the CPU pair off, so addForces never runs and
     * prevForceDotFil is never read). Toggle is reversible and contained:
     * only the lag toggle changes; force formula, ckRelease formula, and
     * dissociateADP formula are untouched.
     */
    public static boolean DIAG_RELEASE_LAG = false;

    /**
     * Diagnostic instrumentation (2026-06-04 — release-read divergence probe).
     * Pure observation, behind an env flag. When non-null, MyoFilLink.ckRelease
     * emits one CSV record per call to this writer:
     *   step,motorId,segId,forceMag,forceDotFil,trackedAvg,releaseFired
     * Decision logic (the catch+slip roll, the breakForce gate, the inRigor
     * gate) is unchanged — only the values the existing roll already reads are
     * captured at function exit. Logging happens regardless of whether the
     * release actually fired. PRNG state and trajectory are unperturbed when
     * BOA_DIAG_RELEASE_READ is unset (writer stays null). Set via
     * BOA_DIAG_RELEASE_READ=<path> in BoxOfActin.begin(). Thread-safe via
     * synchronized on the writer.
     */
    public static java.io.PrintWriter DIAG_RELEASE_READ_WRITER = null;
    private static final Object DIAG_RELEASE_READ_LOCK = new Object();

    public static void diagReleaseReadLog(int step, int motorId, int segId,
                                          double forceMag, double forceDotFil,
                                          double trackedAvg, int releaseFired) {
        java.io.PrintWriter w = DIAG_RELEASE_READ_WRITER;
        if (w == null) return;
        synchronized (DIAG_RELEASE_READ_LOCK) {
            w.printf("%d,%d,%d,%.10e,%.10e,%.10e,%d%n",
                     step, motorId, segId,
                     forceMag, forceDotFil, trackedAvg, releaseFired);
        }
    }

    public static void diagReleaseReadFlush() {
        java.io.PrintWriter w = DIAG_RELEASE_READ_WRITER;
        if (w == null) return;
        synchronized (DIAG_RELEASE_READ_LOCK) { w.flush(); }
    }

    /**
     * Phase 3 gating-validation companion to DIAG_RELEASE_READ_WRITER.
     * Logs every per-motor writeback from bridgeMotorForceWriteback at the
     * moment the bridge stores forceMag/forceDotFil into MyoFilLink. Schema:
     *   step,motorId,segId,wbForceMag,wbForceDotFil
     * Default null. Enabled via BOA_DIAG_RELEASE_READ_WB=<path> in
     * BoxOfActin.begin(). Pairing rule: post-fix, for every (step, motorId)
     * pair present in BOTH the writeback log and the ckRelease read log,
     * wbForceDotFil should equal forceDotFil (ckRelease reads the just-written
     * value). Pre-fix the ckRelease read at step N matches the writeback at
     * step N-1.
     */
    public static java.io.PrintWriter DIAG_RELEASE_WB_WRITER = null;
    private static final Object DIAG_RELEASE_WB_LOCK = new Object();

    public static void diagReleaseWbLog(int step, int motorId, int segId,
                                        double forceMag, double forceDotFil) {
        java.io.PrintWriter w = DIAG_RELEASE_WB_WRITER;
        if (w == null) return;
        synchronized (DIAG_RELEASE_WB_LOCK) {
            w.printf("%d,%d,%d,%.10e,%.10e%n",
                     step, motorId, segId, forceMag, forceDotFil);
        }
    }

    public static void diagReleaseWbFlush() {
        java.io.PrintWriter w = DIAG_RELEASE_WB_WRITER;
        if (w == null) return;
        synchronized (DIAG_RELEASE_WB_LOCK) { w.flush(); }
    }

    // Phase-0 dependency forcing (must run AFTER the `= false` initializers
    // above so we win the ordering race).
    static {
        // SOA_POSE only converts the move kernel + pack/unpack; the joints,
        // chain, and boundary kernels still expect AoS pose. Route them all to
        // CPU so the GPU joints + chain + boundary tasks are bypassed (CPU
        // writes into cpuForceSum, which the SoA move kernel reads correctly
        // — force buffers ARE converted to axis-major in pack/unpack).
        if (SOA_POSE) {
            DIAG_CPU_JOINTS = true;
            DIAG_CPU_F3F4   = true;
            DIAG_CPU_F1     = true;
            DIAG_CPU_MOTOR  = true;
        }
        // MOVE_AB_PROFILE isolates the move kernel as the ONLY GPU task:
        // joints + chain + boundary routed to CPU AND split-plan execution
        // so the moveOnly plan can be profiled in isolation.
        if (MOVE_AB_PROFILE) {
            DIAG_CPU_JOINTS    = true;
            DIAG_CPU_F3F4      = true;
            DIAG_CPU_F1        = true;
            DIAG_CPU_MOTOR     = true;
            DIAG_CPU_DELTA_ADD = true;
        }
        if (SOA_POSE || MOVE_AB_PROFILE) {
            System.err.printf(
                "[PHASE0] SOA_POSE=%s MOVE_AB_PROFILE=%s -> DIAG_CPU_JOINTS=%s DIAG_CPU_F3F4=%s DIAG_CPU_F1=%s DIAG_CPU_DELTA_ADD=%s%n",
                SOA_POSE, MOVE_AB_PROFILE, DIAG_CPU_JOINTS, DIAG_CPU_F3F4, DIAG_CPU_F1, DIAG_CPU_DELTA_ADD);
        }
    }

    /** Public step counter for the CPU joint dump to align with the GPU dump. */
    public static int getStepCounter() { return stepCounter; }

    // Lazy-initialized so that `-seed N` (parsed AFTER class load) governs
    // the run-seed deterministically. Initialized on first moveThings() call
    // via ensureRunSeed(), which happens AFTER all arg parsing.
    private static int     runSeed            = 0;
    private static boolean runSeedInitialized = false;

    private static void ensureRunSeed() {
        if (!runSeedInitialized) {
            runSeed = Env.mtRNG.nextInt();
            runSeedInitialized = true;
        }
    }

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
    // 2026-06-02 Phase 1 — anchor spring (A7.b). anchorPts carries
    // MyosinFixed.myFixedPt (x,y,z) per Myosin; anchoredFlags is 1 for
    // MyosinFixed and 0 for other Myosin subclasses. Packed in
    // packJointsRange() each step (same cadence as drags / cockedFlags) so
    // any future mid-run anchor-point change just shows up at the next step.
    // The CPU MyosinFixed.applyGPUDroppedForces() reduced pass is gated off
    // unless DIAG_CPU_ANCHOR is true; otherwise it would double-apply.
    private static FloatArray anchorPts;      // myoCap * 3
    private static IntArray   anchoredFlags;  // myoCap

    // ----- Phase 2 F8/F9/F10 — motor cross-bridge force port (2026-06-03) -----
    // Per-Myosin binding state uploaded each step:
    //   boundSegSlot[mj]   = move-slot of the FilSegment that motor mj is
    //                        bound to; -1 if motor is unbound, the seg is
    //                        CPU-fallback, or DIAG_CPU_MOTOR is on. The motor
    //                        kernel and seg kernel both early-return when
    //                        boundSegSlot[mj] < 0 and the CPU MyoFilLink
    //                        addForces/alignUVec/alignYVec pair runs for that
    //                        motor (gated in MyoFilLink.step()).
    //   posOnSegArr[mj]    = arclength position of motor mj's attachment from
    //                        seg.end1AsPt3D(), in µm. Constant per binding;
    //                        re-packed every step for simplicity.
    // Per-FilSegment CSR for the seg-side reaction force kernel:
    //   segMotorOffsets[s] = prefix sum; motors bound to seg-slot s are at
    //                        segMotorMyo[segMotorOffsets[s]..segMotorOffsets[s+1]).
    //                        Built CPU-side in one pass over the boundSegSlot
    //                        array (a transitional Phase-2 view that the
    //                        Phase-3 device-side binding build will eliminate;
    //                        until then it is the structurally-dual inverse
    //                        of boundSegSlot, built in the same binding pass
    //                        so the two cannot disagree).
    //   segMotorMyo[k]     = joint index mj of a bound motor; len ≤ myoCap.
    // Per-Myosin readback (device → host):
    //   motorWriteback[mj*2+0] = forceMag (N), written by motorForceKernel.
    //   motorWriteback[mj*2+1] = forceDotFil = Dot(F, seg.uVec) (N), signed,
    //                            sign-equivalent to CPU's pre-flip dot product.
    //                            Drained by bridgeMotorForceWriteback() into
    //                            MyoFilLink.forceMag / forceDotFil, after
    //                            which forceDotFilTrack.registerValue() is
    //                            called to keep the 10-sample running average
    //                            fed for MyoMotor.dissociateADP.
    private static IntArray   boundSegSlot;     // myoCap
    private static FloatArray posOnSegArr;      // myoCap
    private static IntArray   segMotorOffsets;  // slotCap + 1
    private static IntArray   segMotorMyo;      // myoCap (worst case)
    private static FloatArray motorWriteback;   // myoCap * 2
    private static FloatArray motorForceParams; // 6 floats: dt, motorLen, myoSpring, j1FracMoveTorq, uncockedAng, cockedAng

    // ----- Phase 2 F1 — per-slot boundary-kernel gate + box geometry -----
    // boundaryActive[i] = 1 when slot i holds a GPU-handled FilSegment, the
    // container is a Chamber (Survey Option A — separate kernels per shape;
    // pill is F1b, not wired), DIAG_CPU_F1 is false, and simOutsideBug is
    // inactive (Listeria from-outside stays on CPU). Otherwise 0; the
    // boundary kernel early-returns and the CPU pair runs for that segment
    // via FilSegment.gpuBoundaryHandled = false.
    private static IntArray   boundaryActive;   // slotCap
    // boundaryParams: [0]=dt, [1]=boxXDim, [2]=boxYDim, [3]=boxZDim,
    //                 [4]=actinRadius, [5]=fnormScale (= 0.1, hardcoded in
    //                 FilSegment.bugForcesFromInside line `fnorm = 0.1 *
    //                 Math.min(fturn, ftrans)`).
    private static FloatArray boundaryParams;
    // Per-segment per-endpoint wall clearance written by boundaryBoxKernel
    // and consumed by bridgeBoundaryTipC() (called from moveThings() after
    // plan.execute() returns, before BoxOfActin's biochem phase reads tipC).
    // Layout: boundaryTipC[i*2+0] = end1 clearance, [i*2+1] = end2 clearance.
    // Units: µm. Convention matches the existing Listeria from-outside path
    // (checkBugCollisionFromOutside, FilSegment.java:1275/1293) — clearance
    // is (wall_distance − radius), 0 on contact/penetration. Sentinel 1e6
    // is written for inactive slots (non-FilSegment, DIAG_CPU_F1, or the
    // boundaryActive[i]=0 gate) so bridgeBoundaryTipC()'s min-combine is a
    // no-op for them, leaving CPU writes (registerATipClearance, the CPU
    // bugForcesFromInside on the CPU-fallback / DIAG_CPU_F1 paths) untouched.
    private static FloatArray boundaryTipC;     // slotCap * 2
    // Plan-build-time shape selector (Survey Option A). Today only BOX is
    // wired; PILL (Bug) becomes a sibling task as F1b after the pill CPU
    // revival flagged in the 2026-06-03 boundary survey. The shape is picked
    // from `Thing.theBox instanceof ...` in allocateAndBuildPlan(); switching
    // shape mid-run would require closePlan() + allocateAndBuildPlan() but
    // Env.bugShapedCrucible is fixed at startup (no mid-run hook).
    private static final int BOUNDARY_SHAPE_NONE = -1;
    private static final int BOUNDARY_SHAPE_BOX  = 0;
    // private static final int BOUNDARY_SHAPE_PILL = 1;   // future F1b
    private static int boundaryShape = BOUNDARY_SHAPE_NONE;

    // ----- Phase 2 F3/F4 — per-FilSegment chain topology + length -----
    // Populated by classifyThings(): for each slot i, if the slot holds a
    // GPU-handled FilSegment whose chain neighbour at the corresponding end
    // is ALSO a GPU-handled FilSegment, topoEnd{1,2}Slot[i] = neighbour's
    // move-slot and topoEnd{1,2}Side[i] = 0 if my endPt == neighbour.end1Pt
    // (ptAtEndN reference identity), 1 if == neighbour.end2Pt. Sentinel -1
    // in topoEnd{1,2}Slot means "no chain neighbour on this side, or
    // neighbour is CPU-fallback, or DIAG_CPU_F3F4 forces the kernel off".
    // The kernel returns early per-slot when both ends are -1.
    // soaLengthArr mirrors Thing.soaLength[thingIdx] per slot; refreshed in
    // packRange every step (length may change at biochem poly/depoly
    // boundaries without setting topologyDirty).
    private static IntArray   topoEnd2Slot;   // slotCap
    private static IntArray   topoEnd2Side;   // slotCap
    private static IntArray   topoEnd1Slot;   // slotCap
    private static IntArray   topoEnd1Side;   // slotCap
    private static FloatArray soaLengthArr;   // slotCap

    // ----- small inputs -----
    private static FloatArray params;         // move kernel: [0]=deltaT, [1]=brownianForceMag
    private static FloatArray jointParams;    // joints kernel: 13 floats
    // chain kernel: [0]=dt, [1]=fracMove, [2]=fracR, [3]=fracMoveTorq,
    // [4]=filTorqSpringActive (1.0/0.0), [5]=filTorqSpring, [6]=actinMonoRadius.
    private static FloatArray chainParams;
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

    // Split-plan execution for DIAG_CPU_DELTA_ADD. Built lazily when the flag
    // is on; shares the same FloatArrays as the chained plan.
    private static ImmutableTaskGraph   jointsOnlyItg;
    private static TornadoExecutionPlan jointsOnlyPlan;
    private static GridScheduler        jointsOnlyScheduler;
    private static ImmutableTaskGraph   moveOnlyItg;
    private static TornadoExecutionPlan moveOnlyPlan;
    private static GridScheduler        moveOnlyScheduler;

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

    // -------------------------------------------------------------------------
    // Phase-0 A/B profiler accumulators — populated only when MOVE_AB_PROFILE
    // is on. moveAB* tally the moveOnly plan's TornadoVM profiler readouts
    // across all per-step executions; reported once by BoxOfActin's stats block.
    // Also captures the per-call slot count so bandwidth can be computed
    // honestly from actual workload size (not slot cap).
    // -------------------------------------------------------------------------
    private static long moveAB_DeviceKernelNanos  = 0;
    private static long moveAB_DeviceWriteNanos   = 0;
    private static long moveAB_DeviceReadNanos    = 0;
    private static long moveAB_TotalNanos         = 0;
    private static long moveAB_BytesCopyIn        = 0;
    private static long moveAB_BytesCopyOut       = 0;
    private static long moveAB_SlotCountSum       = 0;
    private static int  moveAB_CallCount          = 0;

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
    // High-accuracy acos in double, PTX-compatible. Math.acos itself does not
    // lower on the PTX backend (Graal's software path uses ReinterpretNode).
    // PTX does provide native sin/cos/sqrt for f64, so we seed with the same
    // branched polynomial that CPU's Pt3D.fastAcos uses outside the ±0.95
    // band and the Abramowitz & Stegun 4.4.46 polynomial inside it, then run
    // two Newton iterations on f(y) = cos(y) − x using f'(y) = −sin(y):
    //     y_{n+1} = y_n + (cos(y_n) − x) / sin(y_n).
    // Each iteration roughly squares the error, so the ~5e-5 polynomial seed
    // reaches machine precision after two refinements. This matches Math.acos
    // closely enough that the joint-arithmetic equilibrium bias seen in the
    // 2026-05-31 conformation diagnostic should vanish. Caller may pass any
    // double; the helper clamps to [−1, 1] internally.
    // -------------------------------------------------------------------------
    static double accurateAcos(double x) {
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) {
            double t = 1.0 - x;
            if (t < 0.0) t = 0.0;
            y = Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x;
            if (t < 0.0) t = 0.0;
            y = 3.141592653589793 - Math.sqrt(2.0 * t);
        } else {
            double ax = (x < 0.0) ? -x : x;
            double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
            p = (p * ax + 1.5707963);
            p = p * Math.sqrt(1.0 - ax);
            y = (x < 0.0) ? (3.141592653589793 - p) : p;
        }
        // Newton refinement: each pass roughly squares the relative error.
        // Two passes from a 5e-5 seed lands near machine precision.
        double s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) {
            y = y + (Math.cos(y) - x) / s;
        }
        s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) {
            y = y + (Math.cos(y) - x) / s;
        }
        return y;
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
    //
    // 2026-06-02 Phase 1 — folded the MyosinFixed rod-tail anchor spring
    // (A7.b) into this kernel. anchorPts (myoCap*3) carries each anchored
    // Myosin's fixed point; anchoredFlags (myoCap) is 1 for MyosinFixed,
    // 0 otherwise. When anchored, the kernel ADDS the anchor force to the
    // rod's joint-force write (force only, no torque — mirroring the CPU
    // formula in MyosinFixed.applyRodFixedPtForce where the torque lines
    // are commented out). When not anchored, no anchor contribution.
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
            FloatArray anchorPts,
            IntArray   anchoredFlags,
            FloatArray jointParams,
            IntArray   counts) {

        int M = counts.get(3);

        // 2026-05-31 — joint torque chain widened to double precision; uVec
        // and coord stay float32 in device storage and integration, but the
        // joint arithmetic is hoisted to double the moment values are read
        // from the FloatArrays. Closes the 4° rod-lever conformation gap
        // identified by the JointDiag instrumentation. fastAcosF replaced
        // with accurateAcos (Newton-refined; PTX sin/cos in double native).
        double dt              = (double) jointParams.get(0);
        double j1FracMove      = (double) jointParams.get(1);
        double j1FracR         = (double) jointParams.get(2);
        double j1FracMoveTorq  = (double) jointParams.get(3);
        double j2FracMove      = (double) jointParams.get(4);
        double j2FracR         = (double) jointParams.get(5);
        double j2FracMoveTorq  = (double) jointParams.get(6);
        double motorLen        = (double) jointParams.get(7);
        double leverLen        = (double) jointParams.get(8);
        double rodLen          = (double) jointParams.get(9);
        double stallForce      = (double) jointParams.get(10);
        double uncockedAng     = (double) jointParams.get(11);
        double cockedAng       = (double) jointParams.get(12);

        double DEG2RAD = Math.PI / 180.0;
        double RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int m = 0; m < cockedFlags.getSize(); m++) {
            if (m >= M) { return; }

            int rodSlot   = rodSlots.get(m);
            int leverSlot = leverSlots.get(m);
            int motorSlot = motorSlots.get(m);

            int r3  = rodSlot   * 3;
            int l3  = leverSlot * 3;
            int mo3 = motorSlot * 3;

            double rcx = coord.get(r3),  rcy = coord.get(r3 + 1), rcz = coord.get(r3 + 2);
            double rux = uVec.get(r3),   ruy = uVec.get(r3 + 1),  ruz = uVec.get(r3 + 2);
            double lcx = coord.get(l3),  lcy = coord.get(l3 + 1), lcz = coord.get(l3 + 2);
            double lux = uVec.get(l3),   luy = uVec.get(l3 + 1),  luz = uVec.get(l3 + 2);
            double mcx = coord.get(mo3), mcy = coord.get(mo3 + 1), mcz = coord.get(mo3 + 2);
            double mux = uVec.get(mo3),  muy = uVec.get(mo3 + 1),  muz = uVec.get(mo3 + 2);

            int md9 = m * 9;
            double rBTGx  = (double) myoDrags.get(md9);
            double rBTGy  = (double) myoDrags.get(md9 + 1);
            double rBRGy  = (double) myoDrags.get(md9 + 2);
            double lBTGx  = (double) myoDrags.get(md9 + 3);
            double lBTGy  = (double) myoDrags.get(md9 + 4);
            double lBRGy  = (double) myoDrags.get(md9 + 5);
            double mBTGx  = (double) myoDrags.get(md9 + 6);
            double mBTGy  = (double) myoDrags.get(md9 + 7);
            double mBRGy  = (double) myoDrags.get(md9 + 8);

            int cocked = cockedFlags.get(m);

            double halfRod   = 0.5 * rodLen;
            double halfLever = 0.5 * leverLen;
            double halfMotor = 0.5 * motorLen;

            double le1x = lcx - halfLever * lux, le1y = lcy - halfLever * luy, le1z = lcz - halfLever * luz;
            double le2x = lcx + halfLever * lux, le2y = lcy + halfLever * luy, le2z = lcz + halfLever * luz;
            double me1x = mcx - halfMotor * mux, me1y = mcy - halfMotor * muy, me1z = mcz - halfMotor * muz;
            double re2x = rcx + halfRod * rux,   re2y = rcy + halfRod * ruy,   re2z = rcz + halfRod * ruz;

            double rodFx = 0.0,   rodFy = 0.0,   rodFz = 0.0;
            double rodTx = 0.0,   rodTy = 0.0,   rodTz = 0.0;
            double leverFx = 0.0, leverFy = 0.0, leverFz = 0.0;
            double leverTx = 0.0, leverTy = 0.0, leverTz = 0.0;
            double motorFx = 0.0, motorFy = 0.0, motorFz = 0.0;
            double motorTx = 0.0, motorTy = 0.0, motorTz = 0.0;

            // applyLeverMotorJointForce
            {
                double dx = le2x - me1x, dy = le2y - me1y, dz = le2z - me1z;
                double dist2 = dx * dx + dy * dy + dz * dz;
                double strainDist = Math.sqrt(dist2);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                double l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                double l2x = -l1x, l2y = -l1y, l2z = -l1z;

                double cosBh = -(mux * l1x + muy * l1y + muz * l1z);
                if (cosBh > 1.0)  cosBh = 1.0;
                if (cosBh < -1.0) cosBh = -1.0;
                double cosAlphH  = Math.sin(accurateAcos(cosBh));
                double cosAlphH2 = cosAlphH * cosAlphH;
                double lSqH     = 1.0e-12 * motorLen * motorLen;
                double CxH      = cosBh * cosBh / mBTGx;
                double CperpH   = cosAlphH2 / mBTGy;
                double CthetaH  = lSqH * cosAlphH2 / (4.0 * mBRGy);
                double moveCh   = CxH + CperpH + CthetaH;

                double cosBt = lux * l2x + luy * l2y + luz * l2z;
                if (cosBt > 1.0)  cosBt = 1.0;
                if (cosBt < -1.0) cosBt = -1.0;
                double cosAlphT  = Math.sin(accurateAcos(cosBt));
                double cosAlphT2 = cosAlphT * cosAlphT;
                double lSqT     = 1.0e-12 * leverLen * leverLen;
                double CxT      = cosBt * cosBt / lBTGx;
                double CperpT   = cosAlphT2 / lBTGy;
                double CthetaT  = lSqT * cosAlphT2 / (4.0 * lBRGy);
                double moveCt   = CxT + CperpT + CthetaT;

                double denom = dt * (moveCh + moveCt);
                double forceMag = (denom > 0.0) ? (j1FracMove * 1.0e-6 * strainDist / denom) : 0.0;

                double Fx = forceMag * l1x, Fy = forceMag * l1y, Fz = forceMag * l1z;

                motorFx += Fx; motorFy += Fy; motorFz += Fz;
                double Rms = -0.5e-6 * motorLen * j1FracR;
                double Rmx = Rms * mux, Rmy = Rms * muy, Rmz = Rms * muz;
                motorTx += Rmy * Fz - Rmz * Fy;
                motorTy += Rmz * Fx - Rmx * Fz;
                motorTz += Rmx * Fy - Rmy * Fx;

                Fx = -Fx; Fy = -Fy; Fz = -Fz;
                leverFx += Fx; leverFy += Fy; leverFz += Fz;
                double Rls = 0.5e-6 * leverLen * j1FracR;
                double Rlx = Rls * lux, Rly = Rls * luy, Rlz = Rls * luz;
                leverTx += Rly * Fz - Rlz * Fy;
                leverTy += Rlz * Fx - Rlx * Fz;
                leverTz += Rlx * Fy - Rly * Fx;
            }

            // applyLeverMotorJointTorque
            {
                double tvx = luy * muz - luz * muy;
                double tvy = luz * mux - lux * muz;
                double tvz = lux * muy - luy * mux;
                double tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 0.0) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;

                    double dotVecs = lux * mux + luy * muy + luz * muz;
                    if (dotVecs > 1.0)  dotVecs = 1.0;
                    if (dotVecs < -1.0) dotVecs = -1.0;
                    double angTween = accurateAcos(dotVecs) * RAD2DEG;

                    double angRelaxed = (cocked == 1) ? cockedAng : uncockedAng;
                    double angD = angTween - angRelaxed;

                    double invBRG = 1.0 / mBRGy + 1.0 / lBRGy;
                    double torsionMag = j1FracMoveTorq * DEG2RAD * angD / (invBRG * dt);
                    double maxMag = stallForce * 0.5 * motorLen * 1.0e-18;
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
                double dx = re2x - le1x, dy = re2y - le1y, dz = re2z - le1z;
                double dist2 = dx * dx + dy * dy + dz * dz;
                double strainDist = Math.sqrt(dist2);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                double l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                double l2x = -l1x, l2y = -l1y, l2z = -l1z;

                double cosB1 = -(lux * l1x + luy * l1y + luz * l1z);
                if (cosB1 > 1.0)  cosB1 = 1.0;
                if (cosB1 < -1.0) cosB1 = -1.0;
                double cosAlph1  = Math.sin(accurateAcos(cosB1));
                double cosAlph1_2 = cosAlph1 * cosAlph1;
                double lSq1     = 1.0e-12 * leverLen * leverLen;
                double Cx1      = cosB1 * cosB1 / lBTGx;
                double Cperp1   = cosAlph1_2 / lBTGy;
                double Ctheta1  = lSq1 * cosAlph1_2 / (4.0 * lBRGy);
                double moveC1   = Cx1 + Cperp1 + Ctheta1;

                double cosB2 = rux * l2x + ruy * l2y + ruz * l2z;
                if (cosB2 > 1.0)  cosB2 = 1.0;
                if (cosB2 < -1.0) cosB2 = -1.0;
                double cosAlph2  = Math.sin(accurateAcos(cosB2));
                double cosAlph2_2 = cosAlph2 * cosAlph2;
                double lSq2     = 1.0e-12 * rodLen * rodLen;
                double Cx2      = cosB2 * cosB2 / rBTGx;
                double Cperp2   = cosAlph2_2 / rBTGy;
                double Ctheta2  = lSq2 * cosAlph2_2 / (4.0 * rBRGy);
                double moveC2   = Cx2 + Cperp2 + Ctheta2;

                double denom = dt * (moveC1 + moveC2);
                double forceMag = (denom > 0.0) ? (j2FracMove * 1.0e-6 * strainDist / denom) : 0.0;

                double Fx = forceMag * l1x, Fy = forceMag * l1y, Fz = forceMag * l1z;

                leverFx += Fx; leverFy += Fy; leverFz += Fz;
                double Rls = -0.5e-6 * leverLen * j2FracR;
                double Rlx = Rls * lux, Rly = Rls * luy, Rlz = Rls * luz;
                leverTx += Rly * Fz - Rlz * Fy;
                leverTy += Rlz * Fx - Rlx * Fz;
                leverTz += Rlx * Fy - Rly * Fx;

                Fx = -Fx; Fy = -Fy; Fz = -Fz;
                rodFx += Fx; rodFy += Fy; rodFz += Fz;
                double Rrs = 0.5e-6 * rodLen * j2FracR;
                double Rrx = Rrs * rux, Rry = Rrs * ruy, Rrz = Rrs * ruz;
                rodTx += Rry * Fz - Rrz * Fy;
                rodTy += Rrz * Fx - Rrx * Fz;
                rodTz += Rrx * Fy - Rry * Fx;
            }

            // applyRodLeverJointTorque
            {
                double tvx = ruy * luz - ruz * luy;
                double tvy = ruz * lux - rux * luz;
                double tvz = rux * luy - ruy * lux;
                double tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 0.0) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;

                    double dotVecs = rux * lux + ruy * luy + ruz * luz;
                    if (dotVecs > 1.0)  dotVecs = 1.0;
                    if (dotVecs < -1.0) dotVecs = -1.0;
                    double angTween = accurateAcos(dotVecs) * RAD2DEG;

                    double invBRG = 1.0 / lBRGy + 1.0 / rBRGy;
                    double angD = angTween - 96.0;   // rest angle 96°, mirror of Myosin.applyRodLeverJointTorque
                    double torsionMag = j2FracMoveTorq * DEG2RAD * angD / (invBRG * dt);

                    rodTx += tvx * torsionMag;
                    rodTy += tvy * torsionMag;
                    rodTz += tvz * torsionMag;
                    leverTx -= tvx * torsionMag;
                    leverTy -= tvy * torsionMag;
                    leverTz -= tvz * torsionMag;
                }
            }

            // applyRodFixedPtForce (MyosinFixed anchor spring — A7.b).
            // Mirrors MyosinFixed.applyRodFixedPtForce on the CPU. Force only;
            // CPU torque lines are commented out and the kernel matches that.
            // anchoredFlags[m] == 0 → not anchored → skip.
            if (anchoredFlags.get(m) == 1) {
                int a3 = m * 3;
                double apx = (double) anchorPts.get(a3);
                double apy = (double) anchorPts.get(a3 + 1);
                double apz = (double) anchorPts.get(a3 + 2);

                // rod end1 = rod coord - 0.5 * rodLen * rod uVec (matches recomputeDerivedSoA)
                double re1x = rcx - halfRod * rux;
                double re1y = rcy - halfRod * ruy;
                double re1z = rcz - halfRod * ruz;

                double dx = re1x - apx, dy = re1y - apy, dz = re1z - apz;
                double dist2 = dx * dx + dy * dy + dz * dz;
                double strainDist = Math.sqrt(dist2);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                // linkUVec1 = unit(rod.end1 - fixedPt). Matches CPU
                // Pt3D.unitVec(Pt3D from, Pt3D to) = unit(from - to).
                double l1x = dx * invStrain, l1y = dy * invStrain, l1z = dz * invStrain;
                // linkUVec2 = -linkUVec1, used as the rod's moveCoeff(2, linkUVec2)
                // input on the CPU. Same magnitude either way through cosBeta^2.
                double l2x = -l1x, l2y = -l1y, l2z = -l1z;

                // moveC1 = 0 (fixed point doesn't move). Only the rod's
                // mobility along linkUVec2 contributes to the denominator.
                // Reproduces MyoRod.moveCoeff(2, linkUVec2):
                //   cosBeta = dot(uVec, linkUVec2)
                //   cosAlpha = sin(acos(cosBeta))     [= sqrt(1 - cosBeta^2)]
                //   lSq   = 1e-12 * rodLen^2
                //   Cx    = cosBeta^2 / bTransGam.x
                //   Cperp = cosAlpha^2 / bTransGam.y
                //   Ctheta = lSq * cosAlpha^2 / (4 * bRotGam.y)
                //   moveC = Cx + Cperp + Ctheta
                double cosBeta = rux * l2x + ruy * l2y + ruz * l2z;
                if (cosBeta > 1.0)  cosBeta = 1.0;
                if (cosBeta < -1.0) cosBeta = -1.0;
                double cosAlphA  = Math.sin(accurateAcos(cosBeta));
                double cosAlphA2 = cosAlphA * cosAlphA;
                double lSqA     = 1.0e-12 * rodLen * rodLen;
                double CxA      = cosBeta * cosBeta / rBTGx;
                double CperpA   = cosAlphA2 / rBTGy;
                double CthetaA  = lSqA * cosAlphA2 / (4.0 * rBRGy);
                double moveC2A  = CxA + CperpA + CthetaA;

                double denomA = dt * moveC2A;   // moveC1 = 0
                double forceMagA = (denomA > 0.0) ? (j2FracMove * 1.0e-6 * strainDist / denomA) : 0.0;

                // CPU applies F = -forceMag * linkUVec1 to the rod (after the
                // F.scale(-1, F) line). Equivalently: rod gets +forceMag*l2.
                rodFx += forceMagA * l2x;
                rodFy += forceMagA * l2y;
                rodFz += forceMagA * l2z;
                // No torque contribution — CPU's myoRod.incTorqueSum line is
                // commented out. See MyosinFixed.applyRodFixedPtForce.
            }

            // Write joint contributions to the device-side delta buffers.
            // Host uploaded zeros; this is the per-step contribution per slot.
            // Each Myosin's three sub-slots are unique, so writes are conflict-free.
            // Cast back to float at the storage boundary.
            jointForceSum.set(r3,      (float) rodFx);
            jointForceSum.set(r3 + 1,  (float) rodFy);
            jointForceSum.set(r3 + 2,  (float) rodFz);
            jointTorqueSum.set(r3,     (float) rodTx);
            jointTorqueSum.set(r3 + 1, (float) rodTy);
            jointTorqueSum.set(r3 + 2, (float) rodTz);

            jointForceSum.set(l3,      (float) leverFx);
            jointForceSum.set(l3 + 1,  (float) leverFy);
            jointForceSum.set(l3 + 2,  (float) leverFz);
            jointTorqueSum.set(l3,     (float) leverTx);
            jointTorqueSum.set(l3 + 1, (float) leverTy);
            jointTorqueSum.set(l3 + 2, (float) leverTz);

            jointForceSum.set(mo3,      (float) motorFx);
            jointForceSum.set(mo3 + 1,  (float) motorFy);
            jointForceSum.set(mo3 + 2,  (float) motorFz);
            jointTorqueSum.set(mo3,     (float) motorTx);
            jointTorqueSum.set(mo3 + 1, (float) motorTy);
            jointTorqueSum.set(mo3 + 2, (float) motorTz);
        }
    }

    // -------------------------------------------------------------------------
    // Chain kernel — Phase 2 F3/F4 port. One thread per move slot. Each
    // thread owns its segment: reads ITS own pose + the chain neighbour at
    // each connected end, computes the F3 (chain link force/torque) and F4
    // (chain torsion spring) contributions for THIS segment only, and writes
    // them into jointForceSum/jointTorqueSum at its own slot.
    //
    // Unique ownership: the CPU code uses "owner does both sides of one pair,
    // mark visited" (end{1,2}LinkCkd / end{1,2}TorqCkd dedup flags). The GPU
    // pattern is "each thread does its own side of both its pairs, no visited
    // flags." Forceµmag is pair-symmetric (same strainDist, same
    // moveCoeff sum regardless of which side computes), so both threads
    // compute the same magnitude. Each applies +F (or +torsionVec*torsionMag)
    // to itself only; the neighbour's thread applies the equal-and-opposite
    // contribution to itself. Net result: identical to CPU paired application,
    // no dedup state needed, no cross-writes, no atomics.
    //
    // Slot disjointness with jointsKernel: chainPairForcesKernel writes only
    // to FilSegment slots (topo{1,2}Slot[i] >= 0 implies slot i is a
    // GPU-handled FilSegment). jointsKernel writes only to rod/lever/motor
    // slots. The two writes never collide on the same jointForceSum entry.
    //
    // Early exit: if both topoEnd2Slot < 0 and topoEnd1Slot < 0, the slot
    // contributes nothing — either it's a Myo slot (always -1), a chain end
    // with no neighbours on either side, or DIAG_CPU_F3F4 forced it off.
    // packRange() pre-zeroed jointForceSum at this slot so early-return
    // leaves the zero in place.
    //
    // Reads soaLengthArr (uploaded every step from Thing.soaLength to handle
    // biochem length changes that don't trigger topologyDirty) and the
    // topology IntArrays (rebuilt in classifyThings on topologyDirty; same
    // EVERY_EXECUTION pattern as anchorPts — small per-step transfer, no
    // plan invalidation needed when topology changes).
    // -------------------------------------------------------------------------
    private static void chainPairForcesKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray soaLengthArr,
            IntArray   topoEnd2Slot,
            IntArray   topoEnd2Side,
            IntArray   topoEnd1Slot,
            IntArray   topoEnd1Side,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray jointForceSum,
            FloatArray jointTorqueSum,
            FloatArray chainParams,
            IntArray   counts) {

        int N = counts.get(0);

        double dt                  = (double) chainParams.get(0);
        double fracMove            = (double) chainParams.get(1);
        double fracR               = (double) chainParams.get(2);
        double fracMoveTorq        = (double) chainParams.get(3);
        double filTorqSpringActive = (double) chainParams.get(4);
        double filTorqSpring       = (double) chainParams.get(5);
        double actinMonoRadius     = (double) chainParams.get(6);

        double DEG2RAD = Math.PI / 180.0;
        double RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int i = 0; i < coord.getSize() / 3; i++) {
            if (i >= N) { return; }

            int e2Slot = topoEnd2Slot.get(i);
            int e1Slot = topoEnd1Slot.get(i);
            if (e2Slot < 0 && e1Slot < 0) { return; }

            int i3 = i * 3;
            double cx  = (double) coord.get(i3);
            double cy  = (double) coord.get(i3 + 1);
            double cz  = (double) coord.get(i3 + 2);
            double ux  = (double) uVec.get(i3);
            double uy  = (double) uVec.get(i3 + 1);
            double uz  = (double) uVec.get(i3 + 2);
            double len = (double) soaLengthArr.get(i);
            double halfLen_um = 0.5 * len;
            double lSqSelf    = 1.0e-12 * len * len;

            double rBTGx = (double) bTransGam.get(i3);
            double rBTGy = (double) bTransGam.get(i3 + 1);
            double rBRGy = (double) bRotGam.get(i3 + 1);

            double fx = 0.0, fy = 0.0, fz = 0.0;
            double tx = 0.0, ty = 0.0, tz = 0.0;

            // --- end2 side ---
            if (e2Slot >= 0) {
                int e2Side = topoEnd2Side.get(i);
                int n3     = e2Slot * 3;
                double ncx = (double) coord.get(n3);
                double ncy = (double) coord.get(n3 + 1);
                double ncz = (double) coord.get(n3 + 2);
                double nux = (double) uVec.get(n3);
                double nuy = (double) uVec.get(n3 + 1);
                double nuz = (double) uVec.get(n3 + 2);
                double nlen = (double) soaLengthArr.get(e2Slot);
                double nHalfLen_um = 0.5 * nlen;
                double lSqN  = 1.0e-12 * nlen * nlen;
                double nBTGx = (double) bTransGam.get(n3);
                double nBTGy = (double) bTransGam.get(n3 + 1);
                double nBRGy = (double) bRotGam.get(n3 + 1);

                // F3 link force/torque. Both threads must compute the SAME
                // linkUVec line so per-thread forces are exactly anti-parallel
                // (Newton-3). Owner = segment with the lower slot index;
                // canonical from the topology indices, no atomic/visited-flag
                // coordination. Each thread independently identifies whether
                // it is the owner via slot comparison, computes the OWNER's
                // perspective linkUVec, then applies +F (owner) or −F (non-
                // owner) to itself.

                // My end2 tip in microns (matches Thing.recomputeDerivedSoA).
                double e2x = cx + halfLen_um * ux;
                double e2y = cy + halfLen_um * uy;
                double e2z = cz + halfLen_um * uz;
                // Self's offset linkPt (if self is owner): end2 - r·uVec_self.
                double selfLpx = e2x - actinMonoRadius * ux;
                double selfLpy = e2y - actinMonoRadius * uy;
                double selfLpz = e2z - actinMonoRadius * uz;
                // Neighbour's connecting tip (side=0 → end1, side=1 → end2).
                double nbrTipx, nbrTipy, nbrTipz;
                if (e2Side == 0) {
                    nbrTipx = ncx - nHalfLen_um * nux;
                    nbrTipy = ncy - nHalfLen_um * nuy;
                    nbrTipz = ncz - nHalfLen_um * nuz;
                } else {
                    nbrTipx = ncx + nHalfLen_um * nux;
                    nbrTipy = ncy + nHalfLen_um * nuy;
                    nbrTipz = ncz + nHalfLen_um * nuz;
                }
                // Neighbour's offset linkPt (if neighbour is owner): tip ± r·uVec_nbr,
                // offset INTO the neighbour from its connecting tip.
                //   e2Side==0 (nbr connects via end1): +r·uVec_nbr.
                //   e2Side==1 (nbr connects via end2): −r·uVec_nbr.
                double nbrLpx, nbrLpy, nbrLpz;
                if (e2Side == 0) {
                    nbrLpx = nbrTipx + actinMonoRadius * nux;
                    nbrLpy = nbrTipy + actinMonoRadius * nuy;
                    nbrLpz = nbrTipz + actinMonoRadius * nuz;
                } else {
                    nbrLpx = nbrTipx - actinMonoRadius * nux;
                    nbrLpy = nbrTipy - actinMonoRadius * nuy;
                    nbrLpz = nbrTipz - actinMonoRadius * nuz;
                }
                // Owner-perspective linkPt and ptAtEnd: from the owner's view,
                // linkPt is the owner's monomer-back position and ptAtEnd is
                // the non-owner's connecting tip (matches CPU addLinkForces).
                double linkPtX, linkPtY, linkPtZ;
                double ptAtEndX, ptAtEndY, ptAtEndZ;
                if (i < e2Slot) {
                    linkPtX = selfLpx;  linkPtY = selfLpy;  linkPtZ = selfLpz;
                    ptAtEndX = nbrTipx; ptAtEndY = nbrTipy; ptAtEndZ = nbrTipz;
                } else {
                    linkPtX = nbrLpx;   linkPtY = nbrLpy;   linkPtZ = nbrLpz;
                    ptAtEndX = e2x;     ptAtEndY = e2y;     ptAtEndZ = e2z;
                }
                double dx = ptAtEndX - linkPtX;
                double dy = ptAtEndY - linkPtY;
                double dz = ptAtEndZ - linkPtZ;
                double strainDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                double luX = dx * invStrain, luY = dy * invStrain, luZ = dz * invStrain;

                // moveCoeff for self and neighbour: cosBeta is squared inside
                // (moveC = cosB²/bTGx + (1−cosB²)/bTGy + lSq·(1−cosB²)/(4 bRGy)),
                // so the sign of cosBeta is irrelevant — moveCoeff(1, x) and
                // moveCoeff(2, x) yield the same value, and dot products with
                // ±linkUVec give the same squared cosBeta. Use the raw uVec
                // dotted with the (owner-perspective) linkUVec for both.
                double cosB1 = ux * luX + uy * luY + uz * luZ;
                if (cosB1 > 1.0)  cosB1 = 1.0;
                if (cosB1 < -1.0) cosB1 = -1.0;
                double cosA1 = Math.sin(accurateAcos(cosB1));
                double cosA1_2 = cosA1 * cosA1;
                double moveC1 = cosB1 * cosB1 / rBTGx + cosA1_2 / rBTGy
                              + lSqSelf * cosA1_2 / (4.0 * rBRGy);

                double cosB2 = nux * luX + nuy * luY + nuz * luZ;
                if (cosB2 > 1.0)  cosB2 = 1.0;
                if (cosB2 < -1.0) cosB2 = -1.0;
                double cosA2 = Math.sin(accurateAcos(cosB2));
                double cosA2_2 = cosA2 * cosA2;
                double moveC2 = cosB2 * cosB2 / nBTGx + cosA2_2 / nBTGy
                              + lSqN * cosA2_2 / (4.0 * nBRGy);

                double denom = dt * (moveC1 + moveC2);
                double forceMag = (denom > 0.0) ? (fracMove * 1.0e-6 * strainDist / denom) : 0.0;

                // Sign: +forceMag·linkUVec if self is owner, −forceMag·linkUVec if not.
                double fSign = (i < e2Slot) ? 1.0 : -1.0;
                double Fx = fSign * forceMag * luX;
                double Fy = fSign * forceMag * luY;
                double Fz = fSign * forceMag * luZ;
                fx += Fx; fy += Fy; fz += Fz;
                // R = 0.5e-6 * length * fracR * uVec (lever arm from coord to end2).
                double Rscale = 0.5e-6 * len * fracR;
                double Rx = Rscale * ux, Ry = Rscale * uy, Rz = Rscale * uz;
                tx += Ry * Fz - Rz * Fy;
                ty += Rz * Fx - Rx * Fz;
                tz += Rx * Fy - Ry * Fx;

                // F4 torsion at end2 (CPU FilSegment.java:1698-1707):
                //   side=0 (ptAtEnd2 == neighbour.end1Pt) → cross(uVec, neighbour.uVec)
                //   side=1 (ptAtEnd2 == neighbour.end2Pt) → cross(uVec, neighbour.uVecR)
                // The sign convention here matters (unlike F3 which squares
                // cosBeta) — a wrong sign flips the unit torsion vector AND
                // produces angTween ≈ 180° on a straight chain, which would
                // catastrophically destabilize bench loads (a bug masked by
                // SingleFilDiag because cross(uVec, ±uVec) = 0 on a
                // perfectly straight chain).
                double nuxE, nuyE, nuzE;
                if (e2Side == 0) { nuxE =  nux; nuyE =  nuy; nuzE =  nuz; }
                else             { nuxE = -nux; nuyE = -nuy; nuzE = -nuz; }
                double tvx = uy * nuzE - uz * nuyE;
                double tvy = uz * nuxE - ux * nuzE;
                double tvz = ux * nuyE - uy * nuxE;
                double tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 1.0e-30) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;
                    double dotV = ux * nuxE + uy * nuyE + uz * nuzE;
                    if (dotV > 1.0)  dotV = 1.0;
                    if (dotV < -1.0) dotV = -1.0;
                    double angTween = accurateAcos(dotV) * RAD2DEG;
                    double torsionMag;
                    if (filTorqSpringActive > 0.5) {
                        torsionMag = fracMoveTorq * filTorqSpring * angTween;
                    } else {
                        double invBRG = 1.0 / rBRGy + 1.0 / nBRGy;
                        torsionMag = fracMoveTorq * DEG2RAD * angTween / (invBRG * dt);
                    }
                    tx += tvx * torsionMag;
                    ty += tvy * torsionMag;
                    tz += tvz * torsionMag;
                }
            }

            // --- end1 side ---
            if (e1Slot >= 0) {
                int e1Side = topoEnd1Side.get(i);
                int n3     = e1Slot * 3;
                double ncx = (double) coord.get(n3);
                double ncy = (double) coord.get(n3 + 1);
                double ncz = (double) coord.get(n3 + 2);
                double nux = (double) uVec.get(n3);
                double nuy = (double) uVec.get(n3 + 1);
                double nuz = (double) uVec.get(n3 + 2);
                double nlen = (double) soaLengthArr.get(e1Slot);
                double nHalfLen_um = 0.5 * nlen;
                double lSqN  = 1.0e-12 * nlen * nlen;
                double nBTGx = (double) bTransGam.get(n3);
                double nBTGy = (double) bTransGam.get(n3 + 1);
                double nBRGy = (double) bRotGam.get(n3 + 1);

                // F3 link force/torque, owner-perspective linkUVec (same as
                // end2 block). Owner = lower slot index, determined inline.
                // My end1 tip = coord − halfLen·uVec.
                double e1x = cx - halfLen_um * ux;
                double e1y = cy - halfLen_um * uy;
                double e1z = cz - halfLen_um * uz;
                // Self's offset linkPt (if self is owner): end1 + r·uVec_self.
                double selfLpx = e1x + actinMonoRadius * ux;
                double selfLpy = e1y + actinMonoRadius * uy;
                double selfLpz = e1z + actinMonoRadius * uz;
                // Neighbour's connecting tip (side=0 → end1, side=1 → end2).
                double nbrTipx, nbrTipy, nbrTipz;
                if (e1Side == 0) {
                    nbrTipx = ncx - nHalfLen_um * nux;
                    nbrTipy = ncy - nHalfLen_um * nuy;
                    nbrTipz = ncz - nHalfLen_um * nuz;
                } else {
                    nbrTipx = ncx + nHalfLen_um * nux;
                    nbrTipy = ncy + nHalfLen_um * nuy;
                    nbrTipz = ncz + nHalfLen_um * nuz;
                }
                // Neighbour's offset linkPt (if neighbour is owner): tip ± r·uVec_nbr,
                // offset INTO the neighbour from its connecting tip.
                //   e1Side==0 (nbr connects via end1): +r·uVec_nbr.
                //   e1Side==1 (nbr connects via end2): −r·uVec_nbr.
                double nbrLpx, nbrLpy, nbrLpz;
                if (e1Side == 0) {
                    nbrLpx = nbrTipx + actinMonoRadius * nux;
                    nbrLpy = nbrTipy + actinMonoRadius * nuy;
                    nbrLpz = nbrTipz + actinMonoRadius * nuz;
                } else {
                    nbrLpx = nbrTipx - actinMonoRadius * nux;
                    nbrLpy = nbrTipy - actinMonoRadius * nuy;
                    nbrLpz = nbrTipz - actinMonoRadius * nuz;
                }
                double linkPtX, linkPtY, linkPtZ;
                double ptAtEndX, ptAtEndY, ptAtEndZ;
                if (i < e1Slot) {
                    linkPtX = selfLpx;  linkPtY = selfLpy;  linkPtZ = selfLpz;
                    ptAtEndX = nbrTipx; ptAtEndY = nbrTipy; ptAtEndZ = nbrTipz;
                } else {
                    linkPtX = nbrLpx;   linkPtY = nbrLpy;   linkPtZ = nbrLpz;
                    ptAtEndX = e1x;     ptAtEndY = e1y;     ptAtEndZ = e1z;
                }
                double dx = ptAtEndX - linkPtX;
                double dy = ptAtEndY - linkPtY;
                double dz = ptAtEndZ - linkPtZ;
                double strainDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                double luX = dx * invStrain, luY = dy * invStrain, luZ = dz * invStrain;

                // moveCoeff for self and neighbour (cosBeta squared → sign irrelevant).
                double cosB1 = ux * luX + uy * luY + uz * luZ;
                if (cosB1 > 1.0)  cosB1 = 1.0;
                if (cosB1 < -1.0) cosB1 = -1.0;
                double cosA1 = Math.sin(accurateAcos(cosB1));
                double cosA1_2 = cosA1 * cosA1;
                double moveC1 = cosB1 * cosB1 / rBTGx + cosA1_2 / rBTGy
                              + lSqSelf * cosA1_2 / (4.0 * rBRGy);

                double cosB2 = nux * luX + nuy * luY + nuz * luZ;
                if (cosB2 > 1.0)  cosB2 = 1.0;
                if (cosB2 < -1.0) cosB2 = -1.0;
                double cosA2 = Math.sin(accurateAcos(cosB2));
                double cosA2_2 = cosA2 * cosA2;
                double moveC2 = cosB2 * cosB2 / nBTGx + cosA2_2 / nBTGy
                              + lSqN * cosA2_2 / (4.0 * nBRGy);

                double denom = dt * (moveC1 + moveC2);
                double forceMag = (denom > 0.0) ? (fracMove * 1.0e-6 * strainDist / denom) : 0.0;

                double fSign = (i < e1Slot) ? 1.0 : -1.0;
                double Fx = fSign * forceMag * luX;
                double Fy = fSign * forceMag * luY;
                double Fz = fSign * forceMag * luZ;
                fx += Fx; fy += Fy; fz += Fz;
                // R = 0.5e-6 * length * fracR * uVecR (lever arm from coord to end1, i.e. −uVec).
                double Rscale = -0.5e-6 * len * fracR;
                double Rx = Rscale * ux, Ry = Rscale * uy, Rz = Rscale * uz;
                tx += Ry * Fz - Rz * Fy;
                ty += Rz * Fx - Rx * Fz;
                tz += Rx * Fy - Ry * Fx;

                // F4 torsion at end1 (CPU FilSegment.java:1752-1761):
                //   side=0 (ptAtEnd1 == neighbour.end1Pt) → cross(uVecR, neighbour.uVec)
                //   side=1 (ptAtEnd1 == neighbour.end2Pt) → cross(uVecR, neighbour.uVecR)
                // Same correct sign convention as end2 (uVec at side=0,
                // uVecR at side=1).
                double nuxE, nuyE, nuzE;
                if (e1Side == 0) { nuxE =  nux; nuyE =  nuy; nuzE =  nuz; }
                else             { nuxE = -nux; nuyE = -nuy; nuzE = -nuz; }
                double mux = -ux, muy = -uy, muz = -uz;
                double tvx = muy * nuzE - muz * nuyE;
                double tvy = muz * nuxE - mux * nuzE;
                double tvz = mux * nuyE - muy * nuxE;
                double tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 1.0e-30) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;
                    double dotV = mux * nuxE + muy * nuyE + muz * nuzE;
                    if (dotV > 1.0)  dotV = 1.0;
                    if (dotV < -1.0) dotV = -1.0;
                    double angTween = accurateAcos(dotV) * RAD2DEG;
                    double torsionMag;
                    if (filTorqSpringActive > 0.5) {
                        torsionMag = fracMoveTorq * filTorqSpring * angTween;
                    } else {
                        double invBRG = 1.0 / rBRGy + 1.0 / nBRGy;
                        torsionMag = fracMoveTorq * DEG2RAD * angTween / (invBRG * dt);
                    }
                    tx += tvx * torsionMag;
                    ty += tvy * torsionMag;
                    tz += tvz * torsionMag;
                }
            }

            jointForceSum.set(i3,      (float) fx);
            jointForceSum.set(i3 + 1,  (float) fy);
            jointForceSum.set(i3 + 2,  (float) fz);
            jointTorqueSum.set(i3,     (float) tx);
            jointTorqueSum.set(i3 + 1, (float) ty);
            jointTorqueSum.set(i3 + 2, (float) tz);
        }
    }

    // -------------------------------------------------------------------------
    // Boundary kernel — Phase 2 F1 port (Chamber from-inside).
    //
    // One thread per move slot. Each thread reads ITS own pose (end1/end2 from
    // primary pose + soaLength), checks each endpoint against the rigid-box
    // wall (Chamber center at origin, half-extents inset by actinRadius), and
    // for any wall-penetrating endpoint computes Fcoll = fnormScale ·
    // min(fturn, ftrans) · forceUVec, Tcoll = R × Fcoll, then ADDS them to its
    // own jointForceSum / jointTorqueSum slot (read-modify-write on top of the
    // chain kernel's writes — both tasks run sequentially in the chained
    // TaskGraph on the same device buffers; boundary always runs AFTER chain).
    //
    // Formula mirrors Chamber.amICollidingOuter (Chamber.java:125-138):
    //   forceUVec_i = sign(d_i)·(half_i − R) − d_i   for i ∈ {x,y,z}
    //   zero axis i if sign(forceUVec_i) == sign(d_i) (segment still inside)
    //   delta = |forceUVec|; forceUVec /= delta if nonzero
    // and FilSegment.bugForcesFromInside (FilSegment.java:2565-2589):
    //   R = (End − coord)·1e−6  (meters)
    //   RxFuVecSqrd = |R × forceUVec|²
    //   fturn  = 1e−6 · delta · bRotGam.y  / (RxFuVecSqrd · dt)
    //   ftrans = 1e−6 · delta · bTransGam.x / dt
    //   fnorm  = 0.1 · min(fturn, ftrans)     ← 0.1 hardcoded on CPU
    //   Fcoll = fnorm · forceUVec
    //   Tcoll = R × Fcoll
    // The CPU side effects on `end1AxialF`/`end2AxialF` (used only by
    // FilSegment.setCompression(), which is commented out at FilSegment.java
    // line 500) and `end1TipC`/`end2TipC` (read by checkCapping under
    // `end2NearArpFactor`, only set in Arp-related node binding paths — dead
    // in gliding/deflection-bench workloads) are intentionally NOT replicated
    // on the device. See JOURNAL "Phase 2 F1 (box) — implementation" force-
    // coverage audit.
    //
    // Gating: per-slot boundaryActive[i] = 1 only for GPU-handled
    // FilSegments when Thing.theBox instanceof Chamber, simOutsideBug
    // inactive, and DIAG_CPU_F1 false. All other slots early-return.
    //
    // PTX-safe primitives only: ternaries for sign, ?: for min, Math.sqrt
    // (PTX-native). Sentinel value 1.0e30 stands in for the divide-by-zero
    // → +∞ path on CPU (R parallel to forceUVec): with ftrans ~ 1e−10 typical,
    // 1e30 < ftrans is always false, so fnorm reduces to fnormScale · ftrans
    // — same result as CPU's Math.min(+inf, ftrans).
    // -------------------------------------------------------------------------
    private static void boundaryBoxKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray soaLengthArr,
            IntArray   boundaryActive,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray jointForceSum,
            FloatArray jointTorqueSum,
            FloatArray boundaryTipC,
            FloatArray boundaryParams,
            IntArray   counts) {

        int N = counts.get(0);

        double dt         = (double) boundaryParams.get(0);
        double dimX       = (double) boundaryParams.get(1);
        double dimY       = (double) boundaryParams.get(2);
        double dimZ       = (double) boundaryParams.get(3);
        double R          = (double) boundaryParams.get(4);
        double fnormScale = (double) boundaryParams.get(5);
        double halfX      = 0.5 * dimX - R;
        double halfY      = 0.5 * dimY - R;
        double halfZ      = 0.5 * dimZ - R;

        for (@Parallel int i = 0; i < coord.getSize() / 3; i++) {
            if (i >= N) { return; }
            // tipC writeback (2026-06-03): sentinel-initialise BEFORE the
            // boundaryActive early-return so inactive slots have a no-op
            // value for bridgeBoundaryTipC()'s min-combine. Per-endpoint
            // blocks below overwrite for active slots.
            boundaryTipC.set(i * 2,     1.0e6f);
            boundaryTipC.set(i * 2 + 1, 1.0e6f);
            if (boundaryActive.get(i) == 0) { return; }

            int i3 = i * 3;
            double cx  = (double) coord.get(i3);
            double cy  = (double) coord.get(i3 + 1);
            double cz  = (double) coord.get(i3 + 2);
            double ux  = (double) uVec.get(i3);
            double uy  = (double) uVec.get(i3 + 1);
            double uz  = (double) uVec.get(i3 + 2);
            double len = (double) soaLengthArr.get(i);
            double halfLen_um = 0.5 * len;

            double bTGx = (double) bTransGam.get(i3);
            double bRGy = (double) bRotGam.get(i3 + 1);

            double fx = 0.0, fy = 0.0, fz = 0.0;
            double tx = 0.0, ty = 0.0, tz = 0.0;

            // ----- end1 endpoint: end = coord − halfLen · uVec -----
            {
                double dx = cx - halfLen_um * ux;
                double dy = cy - halfLen_um * uy;
                double dz = cz - halfLen_um * uz;
                // tipC writeback: per-axis clearance is (halfDim_i − R) − |d_i|
                // (positive when endpoint center sits inside the inset wall on
                // that axis; negative on the penetrating axis). Take the min
                // over axes and clamp at 0 to get the box clearance, matching
                // the convention used by the Listeria from-outside path at
                // FilSegment.java:1275/1293 (`tipC = cE.delta` for outside; we
                // pick the same "wall_distance − R" framing). 0 on contact /
                // penetration; > 0 when near a wall but not yet touching.
                {
                    double absDX = (dx < 0.0) ? -dx : dx;
                    double absDY = (dy < 0.0) ? -dy : dy;
                    double absDZ = (dz < 0.0) ? -dz : dz;
                    double cX = halfX - absDX;
                    double cY = halfY - absDY;
                    double cZ = halfZ - absDZ;
                    double mc = (cX < cY) ? cX : cY;
                    mc        = (cZ < mc) ? cZ : mc;
                    double tipC1 = (mc < 0.0) ? 0.0 : mc;
                    boundaryTipC.set(i * 2, (float) tipC1);
                }
                // sign(d_i) via ternary (PTX-safe, no Math.signum).
                double sx = (dx > 0.0) ? 1.0 : ((dx < 0.0) ? -1.0 : 0.0);
                double sy = (dy > 0.0) ? 1.0 : ((dy < 0.0) ? -1.0 : 0.0);
                double sz = (dz > 0.0) ? 1.0 : ((dz < 0.0) ? -1.0 : 0.0);
                double fux = sx * halfX - dx;
                double fuy = sy * halfY - dy;
                double fuz = sz * halfZ - dz;
                // Zero axes where the endpoint is still inside the inset wall
                // (sign(forceUVec) == sign(d)).
                double fsx = (fux > 0.0) ? 1.0 : ((fux < 0.0) ? -1.0 : 0.0);
                double fsy = (fuy > 0.0) ? 1.0 : ((fuy < 0.0) ? -1.0 : 0.0);
                double fsz = (fuz > 0.0) ? 1.0 : ((fuz < 0.0) ? -1.0 : 0.0);
                if (fsx == sx) fux = 0.0;
                if (fsy == sy) fuy = 0.0;
                if (fsz == sz) fuz = 0.0;
                double delta2 = fux * fux + fuy * fuy + fuz * fuz;
                if (delta2 > 0.0) {
                    double delta = Math.sqrt(delta2);
                    double invDelta = 1.0 / delta;
                    double luX = fux * invDelta;
                    double luY = fuy * invDelta;
                    double luZ = fuz * invDelta;
                    // R_lever = (end1 − coord) · 1e-6  (meters); end1 − coord
                    // = −halfLen · uVec (microns).
                    double Rx = -halfLen_um * ux * 1.0e-6;
                    double Ry = -halfLen_um * uy * 1.0e-6;
                    double Rz = -halfLen_um * uz * 1.0e-6;
                    double cxR = Ry * luZ - Rz * luY;
                    double cyR = Rz * luX - Rx * luZ;
                    double czR = Rx * luY - Ry * luX;
                    double RxFuVecSqrd = cxR * cxR + cyR * cyR + czR * czR;
                    double fturn = (RxFuVecSqrd > 1.0e-30)
                        ? (1.0e-6 * delta * bRGy) / (RxFuVecSqrd * dt)
                        : 1.0e30;
                    double ftrans = (1.0e-6 * delta * bTGx) / dt;
                    double fnorm  = fnormScale * ((fturn < ftrans) ? fturn : ftrans);
                    double Fx = fnorm * luX;
                    double Fy = fnorm * luY;
                    double Fz = fnorm * luZ;
                    fx += Fx; fy += Fy; fz += Fz;
                    tx += Ry * Fz - Rz * Fy;
                    ty += Rz * Fx - Rx * Fz;
                    tz += Rx * Fy - Ry * Fx;
                }
            }

            // ----- end2 endpoint: end = coord + halfLen · uVec -----
            {
                double dx = cx + halfLen_um * ux;
                double dy = cy + halfLen_um * uy;
                double dz = cz + halfLen_um * uz;
                // tipC writeback: same per-axis clearance formula as end1.
                {
                    double absDX = (dx < 0.0) ? -dx : dx;
                    double absDY = (dy < 0.0) ? -dy : dy;
                    double absDZ = (dz < 0.0) ? -dz : dz;
                    double cX = halfX - absDX;
                    double cY = halfY - absDY;
                    double cZ = halfZ - absDZ;
                    double mc = (cX < cY) ? cX : cY;
                    mc        = (cZ < mc) ? cZ : mc;
                    double tipC2 = (mc < 0.0) ? 0.0 : mc;
                    boundaryTipC.set(i * 2 + 1, (float) tipC2);
                }
                double sx = (dx > 0.0) ? 1.0 : ((dx < 0.0) ? -1.0 : 0.0);
                double sy = (dy > 0.0) ? 1.0 : ((dy < 0.0) ? -1.0 : 0.0);
                double sz = (dz > 0.0) ? 1.0 : ((dz < 0.0) ? -1.0 : 0.0);
                double fux = sx * halfX - dx;
                double fuy = sy * halfY - dy;
                double fuz = sz * halfZ - dz;
                double fsx = (fux > 0.0) ? 1.0 : ((fux < 0.0) ? -1.0 : 0.0);
                double fsy = (fuy > 0.0) ? 1.0 : ((fuy < 0.0) ? -1.0 : 0.0);
                double fsz = (fuz > 0.0) ? 1.0 : ((fuz < 0.0) ? -1.0 : 0.0);
                if (fsx == sx) fux = 0.0;
                if (fsy == sy) fuy = 0.0;
                if (fsz == sz) fuz = 0.0;
                double delta2 = fux * fux + fuy * fuy + fuz * fuz;
                if (delta2 > 0.0) {
                    double delta = Math.sqrt(delta2);
                    double invDelta = 1.0 / delta;
                    double luX = fux * invDelta;
                    double luY = fuy * invDelta;
                    double luZ = fuz * invDelta;
                    // R_lever = (end2 − coord) · 1e-6 = +halfLen · uVec · 1e-6.
                    double Rx = halfLen_um * ux * 1.0e-6;
                    double Ry = halfLen_um * uy * 1.0e-6;
                    double Rz = halfLen_um * uz * 1.0e-6;
                    double cxR = Ry * luZ - Rz * luY;
                    double cyR = Rz * luX - Rx * luZ;
                    double czR = Rx * luY - Ry * luX;
                    double RxFuVecSqrd = cxR * cxR + cyR * cyR + czR * czR;
                    double fturn = (RxFuVecSqrd > 1.0e-30)
                        ? (1.0e-6 * delta * bRGy) / (RxFuVecSqrd * dt)
                        : 1.0e30;
                    double ftrans = (1.0e-6 * delta * bTGx) / dt;
                    double fnorm  = fnormScale * ((fturn < ftrans) ? fturn : ftrans);
                    double Fx = fnorm * luX;
                    double Fy = fnorm * luY;
                    double Fz = fnorm * luZ;
                    fx += Fx; fy += Fy; fz += Fz;
                    tx += Ry * Fz - Rz * Fy;
                    ty += Rz * Fx - Rx * Fz;
                    tz += Rx * Fy - Ry * Fx;
                }
            }

            // ADD to jointForceSum / jointTorqueSum at this slot. The chain
            // kernel (which runs BEFORE this one in the chained TaskGraph) has
            // already SET the slot's value to its F3+F4 contribution (or
            // zeroed it via packRange's pre-step zero-init if chain
            // early-returned). RMW here is safe — both tasks share device
            // buffers within one .execute(); chain's writes are visible to
            // boundary because no host re-upload happens between tasks within
            // the same TaskGraph execution.
            jointForceSum.set(i3,     (float)((double) jointForceSum.get(i3)     + fx));
            jointForceSum.set(i3 + 1, (float)((double) jointForceSum.get(i3 + 1) + fy));
            jointForceSum.set(i3 + 2, (float)((double) jointForceSum.get(i3 + 2) + fz));
            jointTorqueSum.set(i3,     (float)((double) jointTorqueSum.get(i3)     + tx));
            jointTorqueSum.set(i3 + 1, (float)((double) jointTorqueSum.get(i3 + 1) + ty));
            jointTorqueSum.set(i3 + 2, (float)((double) jointTorqueSum.get(i3 + 2) + tz));
        }
    }

    // -------------------------------------------------------------------------
    // Motor-force kernels — Phase 2 F8/F9/F10 port (2026-06-03).
    //
    // F8  = MyoFilLink.addForces           — linear cross-bridge spring
    // F9  = MyoFilLink.alignUVecTorque     — motor↔seg uVec alignment torque
    // F10 = MyoFilLink.alignYVecTorque     — motor↔seg yVec alignment torque
    //
    // Each MyoFilLink is a one-motor ↔ one-seg pair. A FilSegment in the
    // gliding bed may have many motors bound at once (many-to-one), so the
    // pair force is split between two kernels to keep F3/F4's unique-ownership
    // discipline (no atomics, no visited flags):
    //
    //   motorForceKernel   — one thread per Myosin (myo joint index). Reads
    //                        its motor's pose, its bound seg's pose (via
    //                        boundSegSlot), its posOnSeg/cocked flag. Writes
    //                        +F, the motor-side R×F torque (F8), and
    //                        -tvU·tmag torque on motor (F9 + F10) to its own
    //                        motor slot only. Also writes per-Myosin
    //                        forceMag and signed forceDotFil to a readback
    //                        FloatArray for the CPU release path.
    //
    //   segMotorForceKernel — one thread per FilSegment slot. Walks its CSR
    //                        bound-motor list (segMotorOffsets +
    //                        segMotorMyo) and accumulates, for each bound
    //                        motor, the seg-side reaction: -F, the seg-side
    //                        R×(-F) torque (F8), and +tvU·tmag torque on
    //                        seg (F9 + F10). Sums into its own slot only.
    //
    // Pinned direction convention (must be byte-for-byte identical in both
    // kernels — Newton-3 invariant lives here):
    //   • motorPt   = motor.coord + 0.5·motorLen · motor.uVec
    //   • attachPt  = seg.coord  − 0.5·segLen   · seg.uVec  + posOnSeg · seg.uVec
    //                = seg.end1AsPt3D() + posOnSeg · seg.uVec
    //   • dx        = (motorPt − attachPt)    [µm]
    //   • dist      = |dx|;  forceMag = dist · myoSpring     [N]
    //   • F_unit    = (attachPt − motorPt) / dist   ← matches CPU's
    //                Pt3D.unitVec(attachPt, motorPt) = (attachPt−motorPt)/mag.
    //   • F         = forceMag · F_unit             [N, force ON the motor]
    //   • forceDotFil = Dot(F, seg.uVec)            [N, signed]
    // Motor kernel adds (+F, +R_motor×F) to motor slot, writes (forceMag,
    // forceDotFil) to writeback. Seg kernel adds (−F, +R_seg×(−F)) to seg
    // slot. Per F9: torsionUVec = unit(cross(seg.uVec, motor.uVec)),
    //   torsionMag_U = j1FracMoveTorq · (π/180) · angD / ((1/motorBRGy +
    //                  1/segBRGy) · dt), motor += −torsionUVec·torsionMag_U,
    //   seg += +torsionUVec·torsionMag_U. Per F10: torsionYVec = unit(
    //   cross(seg.yVec, motor.yVec)), torsionMag_Y same shape with bRotGam.x
    //   in the denominator and no angRelaxed offset. Motor's bRotGam.x ==
    //   bRotGam.y (set equal in MyoMotor.calculateProperties()), so lane 8
    //   of myoDrags (motor.bRotGam.y) is correct for both F9 and F10.
    //
    // Sign of forceDotFil is load-bearing: MyoFilLink.ckRelease and
    // MyoMotor.dissociateADP read forceDotFilTrack.averageVal() with
    // catch ∝ exp(−forceDotFil·xCatch/kT) and slip ∝ exp(+forceDotFil·
    // xSlip/kT) — flipping the sign inverts catch↔slip. The motor kernel's
    // forceDotFil exactly mirrors CPU's `Pt3D.Dot(F, seg.uVecAsPt3D())`
    // computed BEFORE the seg-side F.scale(−1) (i.e. the dot of force-on-motor
    // with seg.uVec, positive when motor is pushed toward seg barbed end).
    //
    // RMW into jointForceSum/jointTorqueSum is safe within the chained
    // TaskGraph: the boundary kernel uses the same pattern on FilSeg slots,
    // and the motor/seg force kernels run AFTER joints+chain+boundary and
    // BEFORE move. Motor slots got their initial SET from jointsKernel; seg
    // slots got SET from chainPairForcesKernel and an RMW from boundaryBox.
    // Slot disjointness with all earlier writes is unchanged from F3/F4:
    // motorForceKernel writes only motor slots; segMotorForceKernel writes
    // only FilSeg slots; both use separate per-slot accumulators on entry.
    //
    // Anchor for adding MyoMiniFilament later (deferred): the kernel API is
    // unchanged. boundSegSlot[mj] = -1 for non-MyosinFixed Myosins; flip
    // that line in packMotorBindingRange when the minifilament path needs
    // device support.
    // -------------------------------------------------------------------------
    private static void motorForceKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray soaLengthArr,
            IntArray   motorSlots,
            IntArray   boundSegSlot,
            FloatArray posOnSegArr,
            IntArray   cockedFlags,
            FloatArray myoDrags,
            FloatArray bRotGam,
            FloatArray jointForceSum,
            FloatArray jointTorqueSum,
            FloatArray motorWriteback,
            FloatArray motorForceParams,
            IntArray   counts) {

        int M = counts.get(3);

        double dt              = (double) motorForceParams.get(0);
        double motorLen        = (double) motorForceParams.get(1);
        double myoSpring       = (double) motorForceParams.get(2);
        double j1FracMoveTorq  = (double) motorForceParams.get(3);
        double uncockedAng     = (double) motorForceParams.get(4);
        double cockedAng       = (double) motorForceParams.get(5);

        double DEG2RAD = Math.PI / 180.0;
        double RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int mj = 0; mj < cockedFlags.getSize(); mj++) {
            // Sentinel writebacks: keep the readback well-defined for every
            // motor slot even when unbound, so bridgeMotorForceWriteback() is
            // a safe no-op for those motors.
            motorWriteback.set(mj * 2,     0.0f);
            motorWriteback.set(mj * 2 + 1, 0.0f);
            if (mj >= M) { return; }

            int segSlot = boundSegSlot.get(mj);
            if (segSlot < 0) { return; }   // unbound, CPU-fallback seg, or DIAG_CPU_MOTOR

            int motorSlot = motorSlots.get(mj);

            // Motor pose.
            int mo3 = motorSlot * 3;
            double mcx = coord.get(mo3),  mcy = coord.get(mo3 + 1), mcz = coord.get(mo3 + 2);
            double mux = uVec.get(mo3),   muy = uVec.get(mo3 + 1),  muz = uVec.get(mo3 + 2);
            double myx = yVec.get(mo3),   myy = yVec.get(mo3 + 1),  myz = yVec.get(mo3 + 2);

            // Seg pose.
            int s3 = segSlot * 3;
            double scx = coord.get(s3),  scy = coord.get(s3 + 1), scz = coord.get(s3 + 2);
            double sux = uVec.get(s3),   suy = uVec.get(s3 + 1),  suz = uVec.get(s3 + 2);
            double syx = yVec.get(s3),   syy = yVec.get(s3 + 1),  syz = yVec.get(s3 + 2);
            double segLen   = (double) soaLengthArr.get(segSlot);
            double posOnSeg = (double) posOnSegArr.get(mj);

            // Motor drags: bRotGam.y at lane 8 (motor's bRotGam.x == bRotGam.y).
            int md9 = mj * 9;
            double motorBRGy = (double) myoDrags.get(md9 + 8);
            double motorBRGx = motorBRGy;

            // Seg drags: bRotGam.x at lane 0, bRotGam.y at lane 1 of slot triplet.
            double segBRGx = (double) bRotGam.get(s3);
            double segBRGy = (double) bRotGam.get(s3 + 1);

            int cocked = cockedFlags.get(mj);

            double halfMotor = 0.5 * motorLen;
            double halfSeg   = 0.5 * segLen;

            // motorPt = motor.coord + 0.5·motorLen · motor.uVec
            double mpx = mcx + halfMotor * mux;
            double mpy = mcy + halfMotor * muy;
            double mpz = mcz + halfMotor * muz;
            // attachPt = seg.end1 + posOnSeg · seg.uVec
            //          = (seg.coord − 0.5·segLen·seg.uVec) + posOnSeg·seg.uVec
            //          = seg.coord + (posOnSeg − 0.5·segLen)·seg.uVec
            double posOff = posOnSeg - halfSeg;
            double apx = scx + posOff * sux;
            double apy = scy + posOff * suy;
            double apz = scz + posOff * suz;

            // F8 — spring force.
            double dxv = mpx - apx, dyv = mpy - apy, dzv = mpz - apz;
            double dist = Math.sqrt(dxv*dxv + dyv*dyv + dzv*dzv);
            double forceMag = dist * myoSpring;
            // F_unit = (attachPt − motorPt) / dist  (CPU's unitVec(attachPt, motorPt)).
            double invDist = (dist > 0.0) ? (1.0 / dist) : 0.0;
            double fux = -dxv * invDist;
            double fuy = -dyv * invDist;
            double fuz = -dzv * invDist;
            double Fx = forceMag * fux;
            double Fy = forceMag * fuy;
            double Fz = forceMag * fuz;
            // forceDotFil = Dot(F, seg.uVec) — SIGNED, BEFORE any seg-side
            // F-flip. Equivalent to CPU's Pt3D.Dot(F, mySeg.uVecAsPt3D())
            // computed at MyoFilLink.java:91.
            double forceDotFil = Fx * sux + Fy * suy + Fz * suz;

            // Motor-side R×F (F8 contribution to motor torque). R_motor =
            // (motorPt − motor.coord) · 1e-6 = 0.5e-6·motorLen·motor.uVec.
            double Rmx = halfMotor * mux * 1.0e-6;
            double Rmy = halfMotor * muy * 1.0e-6;
            double Rmz = halfMotor * muz * 1.0e-6;
            double Tmx = Rmy * Fz - Rmz * Fy;
            double Tmy = Rmz * Fx - Rmx * Fz;
            double Tmz = Rmx * Fy - Rmy * Fx;

            // F9 — uVec alignment torque.
            {
                double tvx = suy * muz - suz * muy;
                double tvy = suz * mux - sux * muz;
                double tvz = sux * muy - suy * mux;
                double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
                if (tvMag2 > 0.0) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;
                    double dotVecs = sux*mux + suy*muy + suz*muz;
                    if (dotVecs >  1.0) dotVecs =  1.0;
                    if (dotVecs < -1.0) dotVecs = -1.0;
                    double angTween = accurateAcos(dotVecs) * RAD2DEG;
                    double angRelaxed = (cocked == 1) ? cockedAng : uncockedAng;
                    double angD = angTween - angRelaxed;
                    double invBRG = 1.0 / motorBRGy + 1.0 / segBRGy;
                    double torsionMag = j1FracMoveTorq * DEG2RAD * angD / (invBRG * dt);
                    // Motor side gets -torsion (seg side gets +torsion via seg kernel).
                    Tmx -= tvx * torsionMag;
                    Tmy -= tvy * torsionMag;
                    Tmz -= tvz * torsionMag;
                }
            }

            // F10 — yVec alignment torque.
            {
                double tvx = syy * myz - syz * myy;
                double tvy = syz * myx - syx * myz;
                double tvz = syx * myy - syy * myx;
                double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
                if (tvMag2 > 0.0) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;
                    double dotVecs = syx*myx + syy*myy + syz*myz;
                    if (dotVecs >  1.0) dotVecs =  1.0;
                    if (dotVecs < -1.0) dotVecs = -1.0;
                    double angTween = accurateAcos(dotVecs) * RAD2DEG;
                    // No angRelaxed for F10 — target is 0°.
                    double angD = angTween;
                    double invBRG = 1.0 / motorBRGx + 1.0 / segBRGx;
                    double torsionMag = j1FracMoveTorq * DEG2RAD * angD / (invBRG * dt);
                    Tmx -= tvx * torsionMag;
                    Tmy -= tvy * torsionMag;
                    Tmz -= tvz * torsionMag;
                }
            }

            // RMW into the motor slot's jointForceSum / jointTorqueSum. The
            // motor slot was SET by jointsKernel earlier in the same execute()
            // — its rod/lever/motor contributions are already in place; we add
            // the cross-bridge contribution on top. Slot disjointness with
            // chainPairForcesKernel / boundaryBoxKernel (both FilSeg slots
            // only) is preserved.
            jointForceSum.set(mo3,     (float)((double) jointForceSum.get(mo3)     + Fx));
            jointForceSum.set(mo3 + 1, (float)((double) jointForceSum.get(mo3 + 1) + Fy));
            jointForceSum.set(mo3 + 2, (float)((double) jointForceSum.get(mo3 + 2) + Fz));
            jointTorqueSum.set(mo3,     (float)((double) jointTorqueSum.get(mo3)     + Tmx));
            jointTorqueSum.set(mo3 + 1, (float)((double) jointTorqueSum.get(mo3 + 1) + Tmy));
            jointTorqueSum.set(mo3 + 2, (float)((double) jointTorqueSum.get(mo3 + 2) + Tmz));

            // Per-Myosin readback. The CPU release path (MyoFilLink.ckRelease,
            // MyoMotor.dissociateADP) reads these on the NEXT step, after
            // bridgeMotorForceWriteback() drains them.
            motorWriteback.set(mj * 2,     (float) forceMag);
            motorWriteback.set(mj * 2 + 1, (float) forceDotFil);
        }
    }

    // Seg-side companion: one thread per FilSegment slot, walks the CSR
    // bound-motor list and accumulates the SEG SIDE of each (motor, seg)
    // pair's cross-bridge contribution into its own jointForceSum /
    // jointTorqueSum slot. Force formula and angles are RECOMPUTED bit-for-bit
    // from the same inputs via the pinned convention so the seg-side reaction
    // is exactly anti-parallel to the motor-side action — F3/F4's
    // unique-ownership pair pattern generalized 1:1 → many:1. No atomics, no
    // visited flags, no cross-slot writes.
    private static void segMotorForceKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray soaLengthArr,
            IntArray   segMotorOffsets,
            IntArray   segMotorMyo,
            IntArray   motorSlots,
            FloatArray posOnSegArr,
            IntArray   cockedFlags,
            FloatArray myoDrags,
            FloatArray bRotGam,
            FloatArray jointForceSum,
            FloatArray jointTorqueSum,
            FloatArray motorForceParams,
            IntArray   counts) {

        int N = counts.get(0);

        double dt              = (double) motorForceParams.get(0);
        double motorLen        = (double) motorForceParams.get(1);
        double myoSpring       = (double) motorForceParams.get(2);
        double j1FracMoveTorq  = (double) motorForceParams.get(3);
        double uncockedAng     = (double) motorForceParams.get(4);
        double cockedAng       = (double) motorForceParams.get(5);

        double DEG2RAD = Math.PI / 180.0;
        double RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int i = 0; i < coord.getSize() / 3; i++) {
            if (i >= N) { return; }

            int start = segMotorOffsets.get(i);
            int end   = segMotorOffsets.get(i + 1);
            if (start >= end) { return; }

            int s3 = i * 3;
            double scx = coord.get(s3),  scy = coord.get(s3 + 1), scz = coord.get(s3 + 2);
            double sux = uVec.get(s3),   suy = uVec.get(s3 + 1),  suz = uVec.get(s3 + 2);
            double syx = yVec.get(s3),   syy = yVec.get(s3 + 1),  syz = yVec.get(s3 + 2);
            double segLen = (double) soaLengthArr.get(i);
            double halfSeg = 0.5 * segLen;

            double segBRGx = (double) bRotGam.get(s3);
            double segBRGy = (double) bRotGam.get(s3 + 1);

            double halfMotor = 0.5 * motorLen;

            double fxSum = 0.0, fySum = 0.0, fzSum = 0.0;
            double txSum = 0.0, tySum = 0.0, tzSum = 0.0;

            for (int k = start; k < end; k++) {
                int mj = segMotorMyo.get(k);
                int motorSlot = motorSlots.get(mj);
                int mo3 = motorSlot * 3;
                double mcx = coord.get(mo3),  mcy = coord.get(mo3 + 1), mcz = coord.get(mo3 + 2);
                double mux = uVec.get(mo3),   muy = uVec.get(mo3 + 1),  muz = uVec.get(mo3 + 2);
                double myx = yVec.get(mo3),   myy = yVec.get(mo3 + 1),  myz = yVec.get(mo3 + 2);
                double posOnSeg = (double) posOnSegArr.get(mj);
                int cocked = cockedFlags.get(mj);

                int md9 = mj * 9;
                double motorBRGy = (double) myoDrags.get(md9 + 8);
                double motorBRGx = motorBRGy;

                // motorPt and attachPt — IDENTICAL formula to motorForceKernel.
                double mpx = mcx + halfMotor * mux;
                double mpy = mcy + halfMotor * muy;
                double mpz = mcz + halfMotor * muz;
                double posOff = posOnSeg - halfSeg;
                double apx = scx + posOff * sux;
                double apy = scy + posOff * suy;
                double apz = scz + posOff * suz;

                double dxv = mpx - apx, dyv = mpy - apy, dzv = mpz - apz;
                double dist = Math.sqrt(dxv*dxv + dyv*dyv + dzv*dzv);
                double forceMag = dist * myoSpring;
                double invDist = (dist > 0.0) ? (1.0 / dist) : 0.0;
                double fux = -dxv * invDist;
                double fuy = -dyv * invDist;
                double fuz = -dzv * invDist;
                double Fx = forceMag * fux;
                double Fy = forceMag * fuy;
                double Fz = forceMag * fuz;
                // Seg side gets -F.
                fxSum -= Fx;
                fySum -= Fy;
                fzSum -= Fz;

                // Seg-side R × (−F). R_seg = (attachPt − seg.coord) · 1e-6
                //                          = (posOnSeg − 0.5·segLen) · seg.uVec · 1e-6.
                double Rsx = posOff * sux * 1.0e-6;
                double Rsy = posOff * suy * 1.0e-6;
                double Rsz = posOff * suz * 1.0e-6;
                double negFx = -Fx, negFy = -Fy, negFz = -Fz;
                txSum += Rsy * negFz - Rsz * negFy;
                tySum += Rsz * negFx - Rsx * negFz;
                tzSum += Rsx * negFy - Rsy * negFx;

                // F9 — uVec alignment torque, seg side gets +torsion.
                {
                    double tvx = suy * muz - suz * muy;
                    double tvy = suz * mux - sux * muz;
                    double tvz = sux * muy - suy * mux;
                    double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
                    if (tvMag2 > 0.0) {
                        double invMag = 1.0 / Math.sqrt(tvMag2);
                        tvx *= invMag; tvy *= invMag; tvz *= invMag;
                        double dotVecs = sux*mux + suy*muy + suz*muz;
                        if (dotVecs >  1.0) dotVecs =  1.0;
                        if (dotVecs < -1.0) dotVecs = -1.0;
                        double angTween = accurateAcos(dotVecs) * RAD2DEG;
                        double angRelaxed = (cocked == 1) ? cockedAng : uncockedAng;
                        double angD = angTween - angRelaxed;
                        double invBRG = 1.0 / motorBRGy + 1.0 / segBRGy;
                        double torsionMag = j1FracMoveTorq * DEG2RAD * angD / (invBRG * dt);
                        txSum += tvx * torsionMag;
                        tySum += tvy * torsionMag;
                        tzSum += tvz * torsionMag;
                    }
                }

                // F10 — yVec alignment torque, seg side gets +torsion.
                {
                    double tvx = syy * myz - syz * myy;
                    double tvy = syz * myx - syx * myz;
                    double tvz = syx * myy - syy * myx;
                    double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
                    if (tvMag2 > 0.0) {
                        double invMag = 1.0 / Math.sqrt(tvMag2);
                        tvx *= invMag; tvy *= invMag; tvz *= invMag;
                        double dotVecs = syx*myx + syy*myy + syz*myz;
                        if (dotVecs >  1.0) dotVecs =  1.0;
                        if (dotVecs < -1.0) dotVecs = -1.0;
                        double angTween = accurateAcos(dotVecs) * RAD2DEG;
                        double angD = angTween;
                        double invBRG = 1.0 / motorBRGx + 1.0 / segBRGx;
                        double torsionMag = j1FracMoveTorq * DEG2RAD * angD / (invBRG * dt);
                        txSum += tvx * torsionMag;
                        tySum += tvy * torsionMag;
                        tzSum += tvz * torsionMag;
                    }
                }
            }

            // RMW into this seg's jointForceSum / jointTorqueSum slot. Chain
            // SET its F3+F4 contribution, boundary RMW'd its F1 contribution;
            // here we RMW the seg side of every bound motor's cross-bridge
            // contribution. Disjoint from motor slots written by motorForceKernel.
            jointForceSum.set(s3,     (float)((double) jointForceSum.get(s3)     + fxSum));
            jointForceSum.set(s3 + 1, (float)((double) jointForceSum.get(s3 + 1) + fySum));
            jointForceSum.set(s3 + 2, (float)((double) jointForceSum.get(s3 + 2) + fzSum));
            jointTorqueSum.set(s3,     (float)((double) jointTorqueSum.get(s3)     + txSum));
            jointTorqueSum.set(s3 + 1, (float)((double) jointTorqueSum.get(s3 + 1) + tySum));
            jointTorqueSum.set(s3 + 2, (float)((double) jointTorqueSum.get(s3 + 2) + tzSum));
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
    // SoA-pose variant of the move kernel (Phase-0 layout spike).
    //
    // All slot-keyed FloatArrays here use AXIS-MAJOR indexing within a single
    // buffer of size slotCap*3 (or slotCap*2 for brownianScales): the x-axis
    // values fill [0..stride), then y in [stride..2*stride), then z in
    // [2*stride..3*stride). The host pack writes this layout; this kernel
    // reads/writes it. Stride is recovered as coord.getSize() / 3.
    //
    // Consequence: thread m's x-read of coord is coord.get(m) (not coord.get(3m));
    // consecutive threads in a warp read consecutive floats, so reads coalesce
    // into a single L1/L2 transaction instead of stride-3 gathers. This is
    // the access pattern Phase-0 is measuring.
    //
    // The numerical body is BYTE-IDENTICAL to moveThingKernel above — only
    // the index arithmetic changes. Float-add order is the same (no operand
    // reorder), so equilibrium observables should match modulo nondeterministic
    // thread scheduling on the GPU (the same source of seed-to-seed jitter
    // the AoS kernel already exhibits — not a layout effect).
    // -------------------------------------------------------------------------
    private static void moveThingKernelSoA(
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

        int stride  = coord.getSize() / 3;          // = slotCap (x in [0,stride), y in [stride,2stride), z in [2stride,3stride))
        int stride2 = brownianScales.getSize() / 2; // = slotCap (t in [0,stride2), r in [stride2,2*stride2))

        for (@Parallel int m = 0; m < coord.getSize() / 3; m++) {
            if (m >= N) { return; }

            int ix = m;
            int iy = m + stride;
            int iz = m + 2 * stride;

            float cx = coord.get(ix);
            float cy = coord.get(iy);
            float cz = coord.get(iz);
            float ux = uVec.get(ix);
            float uy = uVec.get(iy);
            float uz = uVec.get(iz);
            float yx = yVec.get(ix);
            float yy = yVec.get(iy);
            float yz = yVec.get(iz);

            float zx = uy * yz - uz * yy;
            float zy = uz * yx - ux * yz;
            float zz = ux * yy - uy * yx;
            float zlen = 1.0f / (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx *= zlen; zy *= zlen; zz *= zlen;

            float fx = cpuForceSum.get(ix)     + jointForceSum.get(ix);
            float fy = cpuForceSum.get(iy)     + jointForceSum.get(iy);
            float fz = cpuForceSum.get(iz)     + jointForceSum.get(iz);
            float tx = cpuTorqueSum.get(ix)    + jointTorqueSum.get(ix);
            float ty = cpuTorqueSum.get(iy)    + jointTorqueSum.get(iy);
            float tz = cpuTorqueSum.get(iz)    + jointTorqueSum.get(iz);

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

            float btgX = bTransGam.get(ix);
            float btgY = bTransGam.get(iy);
            float btgZ = bTransGam.get(iz);
            float brgX = bRotGam.get(ix);
            float brgY = bRotGam.get(iy);
            float brgZ = bRotGam.get(iz);

            float tScale = brownianScales.get(m);
            float rScale = brownianScales.get(m + stride2);

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

            vx *= velMask.get(ix);
            vy *= velMask.get(iy);
            vz *= velMask.get(iz);

            coord.set(ix, cx + dt * vx);
            coord.set(iy, cy + dt * vy);
            coord.set(iz, cz + dt * vz);

            float uTransInZ = -bwy * dt;
            float uTransInY =  bwz * dt;
            float nuX = ux + yx * uTransInY + zx * uTransInZ;
            float nuY = uy + yy * uTransInY + zy * uTransInZ;
            float nuZ = uz + yz * uTransInY + zz * uTransInZ;
            float nuInv = 1.0f / (float) Math.sqrt(nuX * nuX + nuY * nuY + nuZ * nuZ);
            uVec.set(ix, nuX * nuInv);
            uVec.set(iy, nuY * nuInv);
            uVec.set(iz, nuZ * nuInv);

            float yTransInX = -uTransInY;
            float yTransInZ =  bwx * dt;
            float nyX = ux * yTransInX + yx + zx * yTransInZ;
            float nyY = uy * yTransInX + yy + zy * yTransInZ;
            float nyZ = uz * yTransInX + yz + zz * yTransInZ;
            float nyInv = 1.0f / (float) Math.sqrt(nyX * nyX + nyY * nyY + nyZ * nyZ);
            yVec.set(ix, nyX * nyInv);
            yVec.set(iy, nyY * nyInv);
            yVec.set(iz, nyZ * nyInv);
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
        anchorPts      = new FloatArray(myoCap * 3);
        anchoredFlags  = new IntArray(myoCap);
        jointSlotToMyoIdx = new int[myoCap];

        // Phase 2 F3/F4: per-FilSegment chain topology + length buffers.
        topoEnd2Slot = new IntArray(slotCap);
        topoEnd2Side = new IntArray(slotCap);
        topoEnd1Slot = new IntArray(slotCap);
        topoEnd1Side = new IntArray(slotCap);
        soaLengthArr = new FloatArray(slotCap);

        // Phase 2 F1: per-slot boundary-kernel gate + box-geometry uniforms.
        boundaryActive = new IntArray(slotCap);
        boundaryParams = new FloatArray(6);
        // tipC writeback (2026-06-03): per-segment per-endpoint clearance
        // read back from the device after plan.execute(). Two entries per
        // slot (end1, end2). Sentinel-initialised to 1e6 — the kernel writes
        // the sentinel at the top of every active thread BEFORE the
        // boundaryActive early-return, so inactive slots and the
        // DIAG_CPU_F1=true case are guaranteed safe no-ops in the min-combine.
        boundaryTipC = new FloatArray(slotCap * 2);
        for (int i = 0; i < slotCap * 2; i++) { boundaryTipC.set(i, 1.0e6f); }

        // Phase 2 F8/F9/F10 — motor cross-bridge force port.
        boundSegSlot     = new IntArray(myoCap);
        posOnSegArr      = new FloatArray(myoCap);
        segMotorOffsets  = new IntArray(slotCap + 1);
        segMotorMyo      = new IntArray(Math.max(1, myoCap));
        motorWriteback   = new FloatArray(myoCap * 2);
        motorForceParams = new FloatArray(6);

        params      = new FloatArray(2);
        jointParams = new FloatArray(13);
        chainParams = new FloatArray(7);
        counts      = new IntArray(4);

        gpuThingIndices       = new int[slotCap];
        brownianRule          = new int[slotCap];
        cpuFallback           = new Thing[Math.max(64, slotCap)];

        // Phase 2 F1 — plan-build-time shape dispatch (Survey Option A).
        // Today only Chamber (box) is wired; the Bug-shaped pill kernel
        // (F1b) becomes a sibling task here after the pill CPU revival
        // flagged in the 2026-06-03 survey. Container shape is fixed for
        // the lifetime of the plan (Env.bugShapedCrucible / makeCrucible
        // are not mid-run mutable); a future shape switch would require
        // closePlan() + allocateAndBuildPlan(). When the shape is unknown
        // or unsupported, no boundary task is wired and the CPU pair runs
        // for every segment.
        if (Thing.theBox instanceof Chamber) {
            boundaryShape = BOUNDARY_SHAPE_BOX;
        } else {
            boundaryShape = BOUNDARY_SHAPE_NONE;
        }

        // Chained TaskGraph: joints task writes per-step joint contributions
        // to a SEPARATE device-side delta buffer (jointForceSum / jointTorqueSum
        // are uploaded zero-initialised each step); move task reads
        // cpuForceSum + jointForceSum (and cpuTorqueSum + jointTorqueSum) as
        // plain reads. coord/uVec/yVec are shared between tasks (read by both,
        // move writes new pose).
        //
        // Phase 2 F1: when boundaryShape == BOUNDARY_SHAPE_BOX, the "boundary"
        // task runs AFTER "chain" and BEFORE "move", adding the per-segment
        // wall force/torque to jointForceSum/jointTorqueSum via RMW (chain
        // SETs its F3+F4 contribution, boundary reads-adds its F1
        // contribution, move reads the combined sum). Inter-task RMW within
        // a single .execute() is safe because both tasks share device-resident
        // buffers and no host re-upload happens between tasks.
        TaskGraph tg = new TaskGraph("chained")
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                              coord, uVec, yVec,
                              cpuForceSum, cpuTorqueSum,
                              jointForceSum, jointTorqueSum,
                              bTransGam, bRotGam,
                              brownianScales, velMask,
                              rodSlots, leverSlots, motorSlots,
                              myoDrags, cockedFlags,
                              anchorPts, anchoredFlags,
                              topoEnd2Slot, topoEnd2Side,
                              topoEnd1Slot, topoEnd1Side,
                              soaLengthArr,
                              boundaryActive, boundaryParams, boundaryTipC,
                              boundSegSlot, posOnSegArr,
                              segMotorOffsets, segMotorMyo,
                              motorWriteback, motorForceParams,
                              jointParams, chainParams, params, counts)
            .task("joints",
                  GPUMoveThing::jointsKernel,
                  coord, uVec,
                  jointForceSum, jointTorqueSum,
                  rodSlots, leverSlots, motorSlots,
                  myoDrags, cockedFlags,
                  anchorPts, anchoredFlags,
                  jointParams, counts)
            .task("chain",
                  GPUMoveThing::chainPairForcesKernel,
                  coord, uVec, soaLengthArr,
                  topoEnd2Slot, topoEnd2Side,
                  topoEnd1Slot, topoEnd1Side,
                  bTransGam, bRotGam,
                  jointForceSum, jointTorqueSum,
                  chainParams, counts);

        if (boundaryShape == BOUNDARY_SHAPE_BOX) {
            tg = tg.task("boundary",
                  GPUMoveThing::boundaryBoxKernel,
                  coord, uVec, soaLengthArr,
                  boundaryActive,
                  bTransGam, bRotGam,
                  jointForceSum, jointTorqueSum,
                  boundaryTipC,
                  boundaryParams, counts);
        }
        // else if (boundaryShape == BOUNDARY_SHAPE_PILL) { ... // F1b }

        // Phase 2 F8/F9/F10 — motor cross-bridge force (motor-side + seg-side).
        // Order: AFTER boundary (so all FilSeg-slot writes are visible), BEFORE
        // move (so all motor-slot writes land before integration).
        tg = tg.task("motorForce",
              GPUMoveThing::motorForceKernel,
              coord, uVec, yVec, soaLengthArr,
              motorSlots, boundSegSlot, posOnSegArr, cockedFlags,
              myoDrags, bRotGam,
              jointForceSum, jointTorqueSum,
              motorWriteback, motorForceParams, counts)
            .task("segMotorForce",
              GPUMoveThing::segMotorForceKernel,
              coord, uVec, yVec, soaLengthArr,
              segMotorOffsets, segMotorMyo,
              motorSlots, posOnSegArr, cockedFlags,
              myoDrags, bRotGam,
              jointForceSum, jointTorqueSum,
              motorForceParams, counts);

        tg = tg.task("move",
                  SOA_POSE ? GPUMoveThing::moveThingKernelSoA
                           : GPUMoveThing::moveThingKernel,
                  coord, uVec, yVec,
                  cpuForceSum, cpuTorqueSum,
                  jointForceSum, jointTorqueSum,
                  bTransGam, bRotGam,
                  brownianScales, velMask,
                  params, counts);

        // boundaryTipC is transferred to host EVERY_EXECUTION when the box
        // kernel is wired (so bridgeBoundaryTipC() sees fresh writes). When no
        // boundary task is wired (BOUNDARY_SHAPE_NONE — pill or unknown), the
        // bridge is skipped and the buffer stays at its host-side 1e6
        // sentinel.
        if (DIAG_DUMP_JOINTS_STEP >= 0 || DIAG_DUMP_CHAIN_STEP >= 0) {
            if (boundaryShape == BOUNDARY_SHAPE_BOX) {
                tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                                       coord, uVec, yVec,
                                       jointForceSum, jointTorqueSum,
                                       boundaryTipC, motorWriteback);
            } else {
                tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                                       coord, uVec, yVec,
                                       jointForceSum, jointTorqueSum,
                                       motorWriteback);
            }
        } else {
            if (boundaryShape == BOUNDARY_SHAPE_BOX) {
                tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                                       coord, uVec, yVec,
                                       boundaryTipC, motorWriteback);
            } else {
                tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                                       coord, uVec, yVec,
                                       motorWriteback);
            }
        }

        itg  = tg.snapshot();
        plan = new TornadoExecutionPlan(itg);

        WorkerGrid moveWorker = new WorkerGrid1D(slotCap);
        moveWorker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
        WorkerGrid jointWorker = new WorkerGrid1D(myoCap);
        jointWorker.setLocalWork(JOINTS_KERNEL_BLOCK_SIZE, 1, 1);
        // Chain kernel: one thread per move slot, same shape as move kernel.
        WorkerGrid chainWorker = new WorkerGrid1D(slotCap);
        chainWorker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);

        gridScheduler = new GridScheduler("chained.move", moveWorker);
        gridScheduler.addWorkerGrid("chained.joints", jointWorker);
        gridScheduler.addWorkerGrid("chained.chain",  chainWorker);

        // Phase 2 F1: boundary kernel, one thread per move slot.
        if (boundaryShape == BOUNDARY_SHAPE_BOX) {
            WorkerGrid boundaryWorker = new WorkerGrid1D(slotCap);
            boundaryWorker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
            gridScheduler.addWorkerGrid("chained.boundary", boundaryWorker);
        }

        // Phase 2 F8/F9/F10: motorForce one thread per Myosin; segMotorForce
        // one thread per move slot. Same block sizes as the joints/chain
        // kernels they mirror.
        WorkerGrid motorForceWorker = new WorkerGrid1D(myoCap);
        motorForceWorker.setLocalWork(JOINTS_KERNEL_BLOCK_SIZE, 1, 1);
        gridScheduler.addWorkerGrid("chained.motorForce", motorForceWorker);
        WorkerGrid segMotorForceWorker = new WorkerGrid1D(slotCap);
        segMotorForceWorker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
        gridScheduler.addWorkerGrid("chained.segMotorForce", segMotorForceWorker);

        if (DIAG_CPU_DELTA_ADD) {
            // jointsOnly: reads coord/uVec, writes jointForceSum/jointTorqueSum,
            // transfers them to host so CPU can add them to cpuForceSum.
            TaskGraph jtg = new TaskGraph("jointsOnly")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                                  coord, uVec,
                                  jointForceSum, jointTorqueSum,
                                  rodSlots, leverSlots, motorSlots,
                                  myoDrags, cockedFlags,
                                  anchorPts, anchoredFlags,
                                  jointParams, counts)
                .task("joints",
                      GPUMoveThing::jointsKernel,
                      coord, uVec,
                      jointForceSum, jointTorqueSum,
                      rodSlots, leverSlots, motorSlots,
                      myoDrags, cockedFlags,
                      anchorPts, anchoredFlags,
                      jointParams, counts)
                .transferToHost(DataTransferMode.EVERY_EXECUTION,
                                jointForceSum, jointTorqueSum);
            jointsOnlyItg = jtg.snapshot();
            jointsOnlyPlan = new TornadoExecutionPlan(jointsOnlyItg);

            // moveOnly: reads everything (including the CPU-zeroed jointForceSum),
            // writes coord/uVec/yVec back to host.
            TaskGraph mtg = new TaskGraph("moveOnly")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                                  coord, uVec, yVec,
                                  cpuForceSum, cpuTorqueSum,
                                  jointForceSum, jointTorqueSum,
                                  bTransGam, bRotGam,
                                  brownianScales, velMask,
                                  params, counts)
                .task("move",
                      SOA_POSE ? GPUMoveThing::moveThingKernelSoA
                               : GPUMoveThing::moveThingKernel,
                      coord, uVec, yVec,
                      cpuForceSum, cpuTorqueSum,
                      jointForceSum, jointTorqueSum,
                      bTransGam, bRotGam,
                      brownianScales, velMask,
                      params, counts)
                .transferToHost(DataTransferMode.EVERY_EXECUTION,
                                coord, uVec, yVec);
            moveOnlyItg = mtg.snapshot();
            moveOnlyPlan = new TornadoExecutionPlan(moveOnlyItg);
            if (MOVE_AB_PROFILE) {
                moveOnlyPlan = moveOnlyPlan.withProfiler(ProfilerMode.SILENT);
            }

            WorkerGrid jOnlyWorker = new WorkerGrid1D(myoCap);
            jOnlyWorker.setLocalWork(JOINTS_KERNEL_BLOCK_SIZE, 1, 1);
            WorkerGrid mOnlyWorker = new WorkerGrid1D(slotCap);
            mOnlyWorker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
            jointsOnlyScheduler = new GridScheduler("jointsOnly.joints", jOnlyWorker);
            moveOnlyScheduler = new GridScheduler("moveOnly.move", mOnlyWorker);
        }

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
        if (jointsOnlyPlan != null) {
            try { jointsOnlyPlan.close(); } catch (Exception e) { /* best effort */ }
            jointsOnlyPlan = null;
            jointsOnlyItg  = null;
        }
        if (moveOnlyPlan != null) {
            try { moveOnlyPlan.close(); } catch (Exception e) { /* best effort */ }
            moveOnlyPlan = null;
            moveOnlyItg  = null;
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

            if (SOA_POSE) {
                velMask.set(n,                1.0f);
                velMask.set(n + slotCap,      1.0f);
                velMask.set(n + 2 * slotCap,  1.0f);
            } else {
                int i3 = n * 3;
                velMask.set(i3,     1.0f);
                velMask.set(i3 + 1, 1.0f);
                velMask.set(i3 + 2, 1.0f);
            }

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

        // Phase 2 F3/F4 — build per-FilSegment chain topology index.
        // For each GPU-handled FilSegment slot, look up the chain neighbour
        // at each connected end via thingNumberToMoveSlot. If a neighbour is
        // CPU-fallback (slot = -1), DIAG_CPU_F3F4 is on, or the segment is
        // not a FilSegment, mark topo*Slot[i] = -1 → kernel returns early
        // and the CPU pair runs for that segment. gpuChainHandled mirrors
        // the device decision so FilSegment.step() can gate its own
        // addLinkForces / addTorsionSpringForces calls consistently.
        boolean cpuChain = DIAG_CPU_F3F4;
        // Phase 2 F1 — global gate for the box-boundary kernel: only when
        // DIAG_CPU_F1 is off AND simOutsideBug is inactive (Listeria
        // from-outside path stays on CPU regardless) AND boundaryShape was
        // wired to BOX at plan-build (Survey Option A — pill is F1b).
        boolean boundaryOnDevice = (!DIAG_CPU_F1)
                                && !Env.simOutsideBug.isActive()
                                && (boundaryShape == BOUNDARY_SHAPE_BOX);
        for (int s = 0; s < slotCount; s++) {
            Thing t = Thing.theThings[gpuThingIndices[s]];
            int e2Slot = -1, e2Side = 0, e1Slot = -1, e1Side = 0;
            boolean chainOnDevice = false;
            // Phase 2 F1: per-slot boundary gate. Every GPU-handled
            // FilSegment slot under the global gate gets boundaryActive=1;
            // chain-end / mid-chain / isolated segments all need the wall
            // check. Non-FilSegment slots (Myo etc.) stay 0.
            int bActive = 0;
            if (boundaryOnDevice && t instanceof FilSegment) {
                bActive = 1;
                ((FilSegment) t).gpuBoundaryHandled = true;
            } else if (t instanceof FilSegment) {
                ((FilSegment) t).gpuBoundaryHandled = false;
            }
            boundaryActive.set(s, bActive);
            if (!cpuChain && t instanceof FilSegment) {
                FilSegment f = (FilSegment) t;
                FilSegment ne2 = (f.filAtEnd2) ? f.end2Fil : null;
                FilSegment ne1 = (f.filAtEnd1) ? f.end1Fil : null;
                // Only commit the device path if every active chain neighbour
                // is itself GPU-handled. Mixed-state chains (e.g. one end
                // bound to a branched FilSegment with motherFil != null)
                // fall back to CPU for THIS segment — the CPU
                // addLinkForces() then handles both ends, preserving
                // Newton's-3rd-law symmetry with the neighbour's CPU step().
                boolean okE2 = (ne2 == null) || ne2.gpuHandled;
                boolean okE1 = (ne1 == null) || ne1.gpuHandled;
                if (okE2 && okE1) {
                    if (ne2 != null) {
                        int nIdx = ne2.myThingNumber;
                        if (nIdx >= 0 && nIdx < thingNumberToMoveSlot.length) {
                            int ns = thingNumberToMoveSlot[nIdx];
                            if (ns >= 0) {
                                e2Slot = ns;
                                e2Side = (f.ptAtEnd2 == ne2.end1Pt) ? 0 : 1;
                            }
                        }
                    }
                    if (ne1 != null) {
                        int nIdx = ne1.myThingNumber;
                        if (nIdx >= 0 && nIdx < thingNumberToMoveSlot.length) {
                            int ns = thingNumberToMoveSlot[nIdx];
                            if (ns >= 0) {
                                e1Slot = ns;
                                e1Side = (f.ptAtEnd1 == ne1.end1Pt) ? 0 : 1;
                            }
                        }
                    }
                    chainOnDevice = true;
                }
                f.gpuChainHandled = chainOnDevice;
            }
            topoEnd2Slot.set(s, e2Slot);
            topoEnd2Side.set(s, e2Side);
            topoEnd1Slot.set(s, e1Slot);
            topoEnd1Side.set(s, e1Side);
        }
        // CPU-fallback FilSegments keep gpuChainHandled = false (default);
        // any FilSegment whose isGPUHandled flipped to false this classify
        // pass also needs its chain + boundary flags cleared so the CPU
        // pair runs both forces locally.
        for (int i = 0; i < cpuFallbackCt; i++) {
            if (cpuFallback[i] instanceof FilSegment) {
                ((FilSegment) cpuFallback[i]).gpuChainHandled    = false;
                ((FilSegment) cpuFallback[i]).gpuBoundaryHandled = false;
            }
        }
    }

    public static void onStepStart() {
        ensureRunSeed();
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
    // tipC writeback bridge (2026-06-03 — Phase 2 F1 fix).
    //
    // After plan.execute() returns and boundaryTipC has been transferred back,
    // walk every classified slot and min-combine the per-endpoint device
    // clearance into FilSegment.end{1,2}TipC. The min-combine respects
    // existing CPU writes (registerATipClearance from the xLink phase's
    // ProteinNode-tip interaction, and bugForcesFromInside's tipC=0 set on
    // CPU-fallback or DIAG_CPU_F1 paths) by never overwriting a smaller value.
    //
    // Inactive slots (non-FilSegment Myo/etc., and the boundaryActive[i]=0
    // case for DIAG_CPU_F1=true) carry the kernel's 1e6 sentinel, making the
    // min-combine a no-op for those segments — their tipC is whatever the
    // CPU wrote earlier in the step.
    //
    // Caller is responsible for the boundaryShape == BOX gate and the
    // DIAG_DEVICE_BOUNDARY_TIPC toggle; this method does no gating of its
    // own beyond skipping non-FilSegment slots.
    //
    // Lesson 1 (per-force gate, not per-dispatch): end1TipC has no live
    // reader (stericHindranceEnd1 exists but every caller is commented out
    // at FilSegment.java:990-991), so the end1 min-combine is observationally
    // inert in the current codebase. We still do it for cheapness and so
    // that any future revival of the end1 polymerization gate is fed
    // correctly without a second pass through this file.
    // -------------------------------------------------------------------------
    private static void bridgeBoundaryTipC() {
        int sc = slotCount;
        if (sc == 0) return;
        for (int s = 0; s < sc; s++) {
            Thing t = Thing.theThings[gpuThingIndices[s]];
            if (!(t instanceof FilSegment)) continue;
            FilSegment fs = (FilSegment) t;
            double devE1 = (double) boundaryTipC.get(s * 2);
            double devE2 = (double) boundaryTipC.get(s * 2 + 1);
            if (devE1 < fs.end1TipC) fs.end1TipC = devE1;
            if (devE2 < fs.end2TipC) fs.end2TipC = devE2;
        }
    }

    // -------------------------------------------------------------------------
    // Motor-binding pack + CSR build (Phase 2 F8/F9/F10, 2026-06-03).
    //
    // For every Myosin in the joint list (mj in [0, myoJointCt)):
    //   - read tipLink.mySeg + tipLink.posOnSeg
    //   - boundSegSlot[mj]  = move-slot of mySeg (or -1 if unbound, the seg
    //                        is CPU-fallback, the Myosin is not MyosinFixed,
    //                        or DIAG_CPU_MOTOR is on)
    //   - posOnSegArr[mj]   = (float) tipLink.posOnSeg (only meaningful when
    //                        boundSegSlot >= 0)
    //   - simultaneously count motors per seg-slot to seed the CSR prefix sum.
    //
    // Build the CSR in a second pass over the same boundSegSlot array — this
    // is the "one binding pass" structural-duality discipline: boundSegSlot
    // and (segMotorOffsets, segMotorMyo) are two views of the same per-motor
    // binding, scattered from the same source array, so they can never
    // disagree about who is bound to whom.
    //
    // The CSR view is a TRANSITIONAL Phase-2 device upload: it carries no
    // new information beyond boundSegSlot. The Phase-3 device-side binding
    // build (when MyoMotor.checkFilSegCollision moves to a kernel) will
    // eliminate it by maintaining the CSR on device. Until then, ~25k myo
    // CPU build per step at gliding-assay scale is O(motorCt) and negligible
    // relative to the surrounding step-phase budget.
    //
    // Pre-conditions: classifyThings() has populated thingNumberToMoveSlot
    // and jointSlotToMyoIdx. Called from moveThings() AFTER the parallel
    // OP_PACK_JOINTS dispatch (which fills myoDrags / cockedFlags etc.) and
    // BEFORE plan.execute().
    // -------------------------------------------------------------------------
    private static int[] segMotorCount;   // reused across calls
    private static int[] segMotorCursor;  // reused across calls

    private static void packMotorBinding() {
        int M = myoJointCt;
        int N = slotCount;
        // boundSegSlot / posOnSegArr per-myo and counts per seg-slot.
        if (segMotorCount == null || segMotorCount.length < N + 1) {
            segMotorCount  = new int[N + 1];
            segMotorCursor = new int[N + 1];
        } else {
            for (int s = 0; s <= N; s++) { segMotorCount[s] = 0; segMotorCursor[s] = 0; }
        }

        boolean cpuMotor = DIAG_CPU_MOTOR;
        int[] toMyo = jointSlotToMyoIdx;
        Myosin[] myos = Myosin.theMyosins;

        for (int mj = 0; mj < M; mj++) {
            int segSlot = -1;
            float posOnSegF = 0f;
            if (!cpuMotor) {
                Myosin myo = myos[toMyo[mj]];
                // Scope to MyosinFixed only — MyoMiniFilament path is deferred
                // (its dimers' internal Myosins have their own tipLink, but
                // the joint coupling that drives them is CPU-only; keeping
                // them off the device kernel preserves correctness without
                // any kernel change).
                if (myo instanceof MyosinFixed) {
                    MyoMotor motor = myo.myoMotor;
                    if (motor != null) {
                        MyoFilLink link = motor.tipLink;
                        if (link != null && link.mySeg != null && !link.mySeg.removeMe) {
                            int sIdx = link.mySeg.myThingNumber;
                            if (sIdx >= 0 && sIdx < thingNumberToMoveSlot.length) {
                                int s = thingNumberToMoveSlot[sIdx];
                                if (s >= 0 && s < N) {
                                    segSlot = s;
                                    posOnSegF = (float) link.posOnSeg;
                                }
                            }
                        }
                    }
                }
            }
            boundSegSlot.set(mj, segSlot);
            posOnSegArr.set(mj, posOnSegF);
            if (segSlot >= 0) segMotorCount[segSlot + 1]++;
        }

        // Prefix sum into segMotorOffsets[0..N].
        segMotorOffsets.set(0, 0);
        for (int s = 1; s <= N; s++) {
            segMotorOffsets.set(s, segMotorOffsets.get(s - 1) + segMotorCount[s]);
        }
        // Defensive: pad remaining offsets past N (kernel only indexes
        // [0..N], but the IntArray capacity is slotCap+1 which can exceed N).
        int padLast = segMotorOffsets.get(N);
        for (int s = N + 1; s < segMotorOffsets.getSize(); s++) {
            segMotorOffsets.set(s, padLast);
        }

        // Scatter mj into segMotorMyo.
        for (int mj = 0; mj < M; mj++) {
            int s = boundSegSlot.get(mj);
            if (s < 0) continue;
            int base = segMotorOffsets.get(s);
            segMotorMyo.set(base + segMotorCursor[s], mj);
            segMotorCursor[s]++;
        }
    }

    // -------------------------------------------------------------------------
    // Motor-force writeback bridge (Phase 2 F8/F9/F10, 2026-06-03).
    //
    // Drains motorWriteback (per-Myosin forceMag, forceDotFil) into the
    // MyoFilLink fields on every bound motor, then calls
    // forceDotFilTrack.registerValue(forceDotFil) to keep the 10-sample
    // running mean fed for MyoMotor.dissociateADP (it reads
    // averageVal() as the directional gate). Runs AFTER plan.execute()
    // returns and BEFORE the next step's tipLink.step() reads these fields
    // (the next step's MyoFilLink.ckRelease consumes forceMag and
    // forceDotFil; dissociateADP reads the tracker).
    //
    // Ordering (per the implementation note): writeback drain →
    // forceDotFilTrack.registerValue → CPU release on the next step.
    //
    // Sign of forceDotFil is load-bearing: the kernel computes
    // Dot(F, seg.uVec) BEFORE the seg-side F-flip, so the value written here
    // is sign-equivalent to CPU's MyoFilLink.java:91. Verified in the cheap
    // probe (HeldBoundMotorDiag) before this method ever runs on a real
    // ensemble.
    //
    // For non-MyosinFixed Myosins and CPU-fallback bindings, boundSegSlot was
    // -1, so the kernel wrote (0, 0) to motorWriteback. We skip those in the
    // drain: the CPU pair (MyoFilLink.addForces) updates the same fields on
    // its CPU path, so we must not overwrite them with zeros.
    // -------------------------------------------------------------------------
    private static void bridgeMotorForceWriteback() {
        int M = myoJointCt;
        if (M == 0) return;
        int[] toMyo = jointSlotToMyoIdx;
        Myosin[] myos = Myosin.theMyosins;
        for (int mj = 0; mj < M; mj++) {
            int segSlot = boundSegSlot.get(mj);
            if (segSlot < 0) continue;   // CPU pair owns this motor's forces this step
            Myosin myo = myos[toMyo[mj]];
            if (myo == null) continue;
            MyoMotor motor = myo.myoMotor;
            if (motor == null) continue;
            MyoFilLink link = motor.tipLink;
            if (link == null || link.mySeg == null) continue;   // released between pack and drain
            double forceMag    = (double) motorWriteback.get(mj * 2);
            double forceDotFil = (double) motorWriteback.get(mj * 2 + 1);
            link.forceMag    = forceMag;
            link.forceDotFil = forceDotFil;
            // Keep the 10-sample running mean fed — dissociateADP reads
            // averageVal() and would otherwise see only zeros on device path.
            if (link.forceDotFilTrack != null) {
                link.forceDotFilTrack.registerValue(forceDotFil);
            }
            // Phase 3 validation log (gated, default off): record what was
            // just written so it can be matched against ckRelease's read for
            // the same (step, motorId). See DIAG_RELEASE_WB_WRITER.
            if (DIAG_RELEASE_WB_WRITER != null) {
                int mid = (motor.thingInstanceId);
                int sid = link.mySeg.thingInstanceId;
                diagReleaseWbLog(Env.counter, mid, sid, forceMag, forceDotFil);
            }
            // 2026-06-04 release-read reconciliation: for device-handled motors
            // MyoFilLink.step() defers ckRelease — invoke it here so the
            // step-N release decision reads the step-N forces just written
            // above. Pre-fix it ran in the prior step phase against the
            // moveThings(N-1) writeback (1-step stale). The CPU pair path is
            // unchanged (it still calls ckRelease in MyoFilLink.step()).
            link.ckRelease();
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
        float[] soaLenArr   = Thing.soaLength;
        float   bTransCoef = sBTransCoeff;
        float   bRotCoef   = sBRotCoeff;
        float   xLnT       = sXLinkTAttn;
        float   xLnR       = sXLinkRAttn;
        float   myoBr      = sMyoBrownian;
        boolean bFilOff    = sBrownianFilOff;
        boolean bMyoOff    = sBrownianMyoOff;

        // Axis strides for the SoA-pose layout (Phase-0 spike). When SOA_POSE
        // is off these are unused; when on they give the per-axis offset
        // into each slotCap*3 (or slotCap*2 for brownianScales) FloatArray.
        // slotCap is fixed for the lifetime of the plan, so both expressions
        // evaluate to identical constants for every slot in this call.
        int stride  = slotCap;
        int stride2 = slotCap;

        for (int slot = slotStart; slot < slotEnd; slot++) {
            int thingIdx = indices[slot];
            Thing t = theThings[thingIdx];
            int s3 = thingIdx * 3;
            int rule = rules[slot];

            // Axis indices. AoS writes contiguous triplets at slot*3; SoA
            // writes axis-major (x at slot, y at slot+stride, z at slot+2*stride).
            int aX, aY, aZ;
            int bT, bR;
            if (SOA_POSE) {
                aX = slot;
                aY = slot + stride;
                aZ = slot + 2 * stride;
                bT = slot;
                bR = slot + stride2;
            } else {
                int i3 = slot * 3;
                int i2 = slot * 2;
                aX = i3; aY = i3 + 1; aZ = i3 + 2;
                bT = i2; bR = i2 + 1;
            }

            // FilSegment coord can change between steps via biochemStep
            // poly/depoly; always re-pack to stay coherent. Myosin types
            // only mutate coord inside the move kernel, so the FloatArray
            // already matches the SoA state on steady steps. Joints task
            // reads the same coord/uVec — myosins always need a valid pose
            // (which they have from the previous kernel write) so this
            // pack-skip is safe across both tasks.
            if (packCoords || rule == RULE_FIL) {
                coord.set(aX, soaCoordArr[s3]);
                coord.set(aY, soaCoordArr[s3 + 1]);
                coord.set(aZ, soaCoordArr[s3 + 2]);
                uVec.set(aX,  soaUVecArr[s3]);
                uVec.set(aY,  soaUVecArr[s3 + 1]);
                uVec.set(aZ,  soaUVecArr[s3 + 2]);
                yVec.set(aX,  soaYVecArr[s3]);
                yVec.set(aY,  soaYVecArr[s3 + 1]);
                yVec.set(aZ,  soaYVecArr[s3 + 2]);
            }
            cpuForceSum.set(aX, soaForce[s3]);
            cpuForceSum.set(aY, soaForce[s3 + 1]);
            cpuForceSum.set(aZ, soaForce[s3 + 2]);
            cpuTorqueSum.set(aX, soaTorque[s3]);
            cpuTorqueSum.set(aY, soaTorque[s3 + 1]);
            cpuTorqueSum.set(aZ, soaTorque[s3 + 2]);
            // Zero the joint-delta buffers for this slot; the joints kernel
            // overwrites the rod/lever/motor entries on device. Other slots
            // (FilSegments) keep zero, so the move kernel's cpuForceSum +
            // jointForceSum sum is just cpuForceSum for non-Myosin slots.
            jointForceSum.set(aX, 0f);
            jointForceSum.set(aY, 0f);
            jointForceSum.set(aZ, 0f);
            jointTorqueSum.set(aX, 0f);
            jointTorqueSum.set(aY, 0f);
            jointTorqueSum.set(aZ, 0f);
            bTransGam.set(aX, (float) t.bTransGam.x);
            bTransGam.set(aY, (float) t.bTransGam.y);
            bTransGam.set(aZ, (float) t.bTransGam.z);
            bRotGam.set(aX, (float) t.bRotGam.x);
            bRotGam.set(aY, (float) t.bRotGam.y);
            bRotGam.set(aZ, (float) t.bRotGam.z);

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
            brownianScales.set(bT, tScale);
            brownianScales.set(bR, rScale);
            // Phase 2 F3/F4 — refresh per-slot length. CPU keeps
            // Thing.soaLength current via pushLengthToSoa() in
            // FilSegment.initialize(), called from biochemStep when length
            // changes. Repacking every step keeps device coherent regardless
            // of whether the length change set topologyDirty.
            soaLengthArr.set(slot, soaLenArr[thingIdx]);
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
        boolean cpuAnchor = DIAG_CPU_ANCHOR;
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
            // Anchor data (Phase 1, A7.b). DIAG_CPU_ANCHOR forces the
            // kernel-side flag to 0 so the device contribution is skipped;
            // the CPU MyosinThreads dispatch then runs the anchor pass.
            int a3 = mj * 3;
            if (!cpuAnchor && myo instanceof MyosinFixed) {
                MyosinFixed mf = (MyosinFixed) myo;
                anchorPts.set(a3,     (float) mf.myFixedPt.x);
                anchorPts.set(a3 + 1, (float) mf.myFixedPt.y);
                anchorPts.set(a3 + 2, (float) mf.myFixedPt.z);
                anchoredFlags.set(mj, 1);
            } else {
                anchorPts.set(a3,     0f);
                anchorPts.set(a3 + 1, 0f);
                anchorPts.set(a3 + 2, 0f);
                anchoredFlags.set(mj, 0);
            }
        }
    }

    private static void unpackRange(int slotStart, int slotEnd) {
        int[]   indices   = gpuThingIndices;
        float[] soaCoordArr = Thing.soaCoord;
        float[] soaUVecArr  = Thing.soaUVec;
        float[] soaYVecArr  = Thing.soaYVec;
        int stride = slotCap;
        for (int slot = slotStart; slot < slotEnd; slot++) {
            int thingIdx = indices[slot];
            int s3 = thingIdx * 3;
            int aX, aY, aZ;
            if (SOA_POSE) {
                aX = slot;
                aY = slot + stride;
                aZ = slot + 2 * stride;
            } else {
                int i3 = slot * 3;
                aX = i3; aY = i3 + 1; aZ = i3 + 2;
            }
            soaCoordArr[s3]     = coord.get(aX);
            soaCoordArr[s3 + 1] = coord.get(aY);
            soaCoordArr[s3 + 2] = coord.get(aZ);
            soaUVecArr[s3]      = uVec.get(aX);
            soaUVecArr[s3 + 1]  = uVec.get(aY);
            soaUVecArr[s3 + 2]  = uVec.get(aZ);
            soaYVecArr[s3]      = yVec.get(aX);
            soaYVecArr[s3 + 1]  = yVec.get(aY);
            soaYVecArr[s3 + 2]  = yVec.get(aZ);
        }
    }

    // -------------------------------------------------------------------------
    // Part B — DIAG_CPU_DELTA_ADD execution path. Runs joints kernel,
    // downloads delta to host, adds delta to cpuForceSum / cpuTorqueSum on
    // CPU, zeros delta, then runs move kernel reading cpuForceSum + 0.
    // -------------------------------------------------------------------------
    private static void executeSplit() {
        // Run the joints-only plan (or skip if no Myosin joints — buffers are
        // already host-zeroed from packRange, so move sees the right inputs).
        if (myoJointCt > 0) {
            jointsOnlyPlan.withGridScheduler(jointsOnlyScheduler).execute();
            // Sum joint delta into cpuForceSum/cpuTorqueSum on host; zero delta
            // so the move kernel reads cpuForceSum + 0.
            int n3 = slotCount * 3;
            for (int i = 0; i < n3; i++) {
                float jf = jointForceSum.get(i);
                if (jf != 0f) {
                    cpuForceSum.set(i, cpuForceSum.get(i) + jf);
                    jointForceSum.set(i, 0f);
                }
                float jt = jointTorqueSum.get(i);
                if (jt != 0f) {
                    cpuTorqueSum.set(i, cpuTorqueSum.get(i) + jt);
                    jointTorqueSum.set(i, 0f);
                }
            }
        }
        TornadoExecutionResult res =
            moveOnlyPlan.withGridScheduler(moveOnlyScheduler).execute();
        if (MOVE_AB_PROFILE) {
            TornadoProfilerResult r = res.getProfilerResult();
            moveAB_DeviceKernelNanos += r.getDeviceKernelTime();
            moveAB_DeviceWriteNanos  += r.getDeviceWriteTime();
            moveAB_DeviceReadNanos   += r.getDeviceReadTime();
            moveAB_TotalNanos        += r.getTotalTime();
            moveAB_BytesCopyIn       += r.getTotalBytesCopyIn();
            moveAB_BytesCopyOut      += r.getTotalBytesCopyOut();
            moveAB_SlotCountSum      += slotCount;
            moveAB_CallCount++;
        }
    }

    // -------------------------------------------------------------------------
    // Part A — Structural content check on the joint delta buffer.
    // -------------------------------------------------------------------------
    private static void dumpDeltaStructure() {
        // Build the set of slots written by the joints kernel.
        java.util.BitSet myoSlots = new java.util.BitSet(slotCount);
        for (int mj = 0; mj < myoJointCt; mj++) {
            myoSlots.set(rodSlots.get(mj));
            myoSlots.set(leverSlots.get(mj));
            myoSlots.set(motorSlots.get(mj));
        }
        int expectedMyoSlotCt = myoSlots.cardinality();

        int myoNonzeroF = 0, myoZeroF = 0;
        int myoNonzeroT = 0, myoZeroT = 0;
        int otherNonzeroF = 0, otherNonzeroT = 0;
        double sumMyoF2 = 0, sumMyoT2 = 0;
        double sumOtherF2 = 0, sumOtherT2 = 0;
        float maxMyoF = 0f, maxMyoT = 0f;
        float maxOtherF = 0f, maxOtherT = 0f;
        int firstOtherNonzero = -1;

        for (int s = 0; s < slotCount; s++) {
            int s3 = s * 3;
            float fx = jointForceSum.get(s3);
            float fy = jointForceSum.get(s3 + 1);
            float fz = jointForceSum.get(s3 + 2);
            float tx = jointTorqueSum.get(s3);
            float ty = jointTorqueSum.get(s3 + 1);
            float tz = jointTorqueSum.get(s3 + 2);
            float fmag2 = fx * fx + fy * fy + fz * fz;
            float tmag2 = tx * tx + ty * ty + tz * tz;
            float fmag  = (float) Math.sqrt(fmag2);
            float tmag  = (float) Math.sqrt(tmag2);
            if (myoSlots.get(s)) {
                sumMyoF2 += fmag2;
                sumMyoT2 += tmag2;
                if (fmag2 > 0f) myoNonzeroF++; else myoZeroF++;
                if (tmag2 > 0f) myoNonzeroT++; else myoZeroT++;
                if (fmag > maxMyoF) maxMyoF = fmag;
                if (tmag > maxMyoT) maxMyoT = tmag;
            } else {
                sumOtherF2 += fmag2;
                sumOtherT2 += tmag2;
                if (fmag2 > 0f) {
                    otherNonzeroF++;
                    if (firstOtherNonzero < 0) firstOtherNonzero = s;
                    if (fmag > maxOtherF) maxOtherF = fmag;
                }
                if (tmag2 > 0f) {
                    otherNonzeroT++;
                    if (tmag > maxOtherT) maxOtherT = tmag;
                }
            }
        }
        System.err.printf("[DIAG_DELTA_STRUCT step=%d] slotCount=%d myoJointCt=%d expectedMyoSlots=%d%n",
            stepCounter, slotCount, myoJointCt, expectedMyoSlotCt);
        System.err.printf("[DIAG_DELTA_STRUCT step=%d] myoSlots: F nonzero=%d zero=%d (sqrt(sumF2)=%.3e maxF=%.3e); T nonzero=%d zero=%d (sqrt(sumT2)=%.3e maxT=%.3e)%n",
            stepCounter, myoNonzeroF, myoZeroF, Math.sqrt(sumMyoF2), maxMyoF,
            myoNonzeroT, myoZeroT, Math.sqrt(sumMyoT2), maxMyoT);
        System.err.printf("[DIAG_DELTA_STRUCT step=%d] otherSlots: F nonzero=%d (sqrt(sumF2)=%.3e maxF=%.3e); T nonzero=%d (sqrt(sumT2)=%.3e maxT=%.3e); firstOtherNonzero=%d%n",
            stepCounter, otherNonzeroF, Math.sqrt(sumOtherF2), maxOtherF,
            otherNonzeroT, Math.sqrt(sumOtherT2), maxOtherT, firstOtherNonzero);
    }

    // -------------------------------------------------------------------------
    // Public entry point — chained joints + move.
    // -------------------------------------------------------------------------
    public static void moveThings() {
        long t0 = System.nanoTime();

        ensureRunSeed();

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

        // Phase 2 F3/F4 — chain kernel parameters. fracMove/fracR/fracMoveTorq
        // are runtime-mutable (Env.java setMutableAtRuntime). filTorqSpring
        // active state encoded as a float (1.0/0.0) so the kernel can branch
        // without an extra IntArray. actinMonoRadius is a compile-time
        // constant from Env (no isActive() guard).
        chainParams.set(0, (float) Env.deltaT.getValue());
        chainParams.set(1, (float) Env.fracMove.getValue());
        chainParams.set(2, (float) Env.fracR.getValue());
        chainParams.set(3, (float) Env.fracMoveTorq.getValue());
        chainParams.set(4, Env.filTorqSpring.isActive() ? 1.0f : 0.0f);
        chainParams.set(5, (float) Env.filTorqSpring.getValue());
        chainParams.set(6, (float) Env.actinMonoRadius);

        // Phase 2 F1 — box-boundary kernel parameters. Box dims are
        // theoretically Parameters (Env.boxXDim/Y/Z), but no parameter file
        // marks them mutableAtRuntime so re-reading every step is cheap
        // future-proofing. fnormScale = 0.1 mirrors the literal in
        // FilSegment.bugForcesFromInside (`fnorm = 0.1 * Math.min(...)`);
        // if that hardcoded scaling ever moves to a Parameter, pick it up here.
        boundaryParams.set(0, (float) Env.deltaT.getValue());
        boundaryParams.set(1, (float) Env.boxXDim.getValue());
        boundaryParams.set(2, (float) Env.boxYDim.getValue());
        boundaryParams.set(3, (float) Env.boxZDim.getValue());
        boundaryParams.set(4, (float) FilSegment.radius);
        boundaryParams.set(5, 0.1f);

        // Phase 2 F8/F9/F10 — motor cross-bridge force kernel parameters.
        // myoSpring is the hard-coded literal in MyoFilLink.java:22 (1e-9
        // N/µm); if it ever moves to a Parameter, pick it up here.
        motorForceParams.set(0, (float) Env.deltaT.getValue());
        motorForceParams.set(1, (float) Env.myoMotorLength.getValue());
        motorForceParams.set(2, (float) MyoFilLink.myoSpring);
        motorForceParams.set(3, (float) Env.myoJ1FracMoveTorq.getValue());
        motorForceParams.set(4, (float) Myosin.uncockedMotor_ActinAngle);
        motorForceParams.set(5, (float) Myosin.cockedMotor_ActinAngle);

        // Build per-Myosin binding view + CSR (one binding pass, structurally
        // dual). Sets boundSegSlot[mj]/posOnSegArr[mj] for the motor kernel
        // and segMotorOffsets[]+segMotorMyo[] for the seg kernel. Done CPU-side
        // before plan.execute(); transitional until Phase 3 binding move to
        // device. Cheap (O(motorCt)) at gliding-assay scale.
        if (myoJointCt > 0) {
            packMotorBinding();
        }

        counts.set(0, slotCount);
        counts.set(1, stepCounter);
        counts.set(2, runSeed);
        counts.set(3, myoJointCt);

        long packEnd = System.nanoTime();

        // Before plan.execute(), run a CPU-side dry-run of joint computation
        // over the SAME pose the GPU joints kernel will see — populates the
        // Myosin diag accumulators so [DIAG_CPU_JOINT ...] lines print
        // matching-pose CPU joint forces. Skipped when DIAG_CPU_JOINTS=true
        // (CPU joints already ran with side effects in MyosinThreads).
        boolean armDryRun = DIAG_DUMP_JOINTS_STEP >= 0
                         && stepCounter == DIAG_DUMP_JOINTS_STEP
                         && !DIAG_CPU_JOINTS
                         && Env.useGPU;
        if (armDryRun) {
            Myosin.DIAG_DRY_RUN = true;
            for (int mj = 0; mj < myoJointCt; mj++) {
                Myosin myo = Myosin.theMyosins[jointSlotToMyoIdx[mj]];
                if (myo != null) myo.jointConstraints();
            }
            Myosin.DIAG_DRY_RUN = false;
        }

        if (slotCount > 0) {
            if (DIAG_CPU_DELTA_ADD) {
                executeSplit();
            } else {
                plan.withGridScheduler(gridScheduler).execute();
            }
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
            dumpDeltaStructure();
        }

        // Phase 2 F3/F4 chain-kernel dump: at the specified step, walk every
        // GPU-handled FilSegment slot and report (cpuForceSum, jointForceSum,
        // jointTorqueSum). Lets jba diff arm A (device F3/F4 → contribution
        // in jointForceSum) against arm B (CPU F3/F4 → contribution lands in
        // cpuForceSum via the soaForceSum pack). Slot N maps to thingIdx via
        // gpuThingIndices[N]; ID printed for cross-reference with bench output.
        if (DIAG_DUMP_CHAIN_STEP >= 0 && stepCounter == DIAG_DUMP_CHAIN_STEP) {
            for (int s = 0; s < slotCount; s++) {
                int thingIdx = gpuThingIndices[s];
                Thing t = Thing.theThings[thingIdx];
                if (!(t instanceof FilSegment)) continue;
                FilSegment f = (FilSegment) t;
                int s3 = s * 3;
                System.err.printf(
                    "[DIAG_CHAIN step=%d slot=%d thingIdx=%d filSegId=%d gpuCH=%b "
                    + "cpuF=(%.4e,%.4e,%.4e) jntF=(%.4e,%.4e,%.4e) jntT=(%.4e,%.4e,%.4e)%n",
                    stepCounter, s, thingIdx, f.thingInstanceId, f.gpuChainHandled,
                    cpuForceSum.get(s3), cpuForceSum.get(s3 + 1), cpuForceSum.get(s3 + 2),
                    jointForceSum.get(s3), jointForceSum.get(s3 + 1), jointForceSum.get(s3 + 2),
                    jointTorqueSum.get(s3), jointTorqueSum.get(s3 + 1), jointTorqueSum.get(s3 + 2));
            }
            System.err.flush();
        }

        if (sc > 0) {
            dispatchAndWait(OP_UNPACK, sc);
        }
        for (int i = 0; i < cpuFallbackCt; i++) {
            cpuFallback[i].moveThing();
        }
        // tipC writeback bridge (2026-06-03): after plan.execute() has run
        // the box boundary kernel and its FloatArray writes have been
        // transferred back, min-combine the per-endpoint clearance into
        // FilSegment.end{1,2}TipC. Runs BEFORE BoxOfActin's biochem phase
        // reads tipC (stericHindranceEnd2 gates polymerization). The bridge
        // is gated on DIAG_DEVICE_BOUNDARY_TIPC and on the box kernel
        // actually being wired (BOUNDARY_SHAPE_BOX) so the pre-fix
        // "tips-grow-through-the-wall" behaviour is recoverable for the A/B
        // (BOA_DIAG_DEVICE_BOUNDARY_TIPC=0). DIAG_CPU_F1 is intentionally
        // NOT a gate here: in CPU mode the kernel writes 1e6 sentinels via
        // the early-return path, and min(1e6, existing) = existing — so the
        // bridge is a no-op for CPU runs and won't trample existing CPU
        // bugForcesFromInside writes (which set tipC=0 on hit, smaller than
        // any sentinel).
        if (DIAG_DEVICE_BOUNDARY_TIPC && boundaryShape == BOUNDARY_SHAPE_BOX) {
            bridgeBoundaryTipC();
        }
        // Motor-force writeback (Phase 2 F8/F9/F10, 2026-06-03). Drain
        // device-computed forceMag / forceDotFil into every device-handled
        // MyoFilLink and feed forceDotFilTrack. ckRelease/dissociateADP run
        // on the NEXT step's tipLink.step() with these values; the 1-step
        // lag is harmless at dt=1e-4s (release is a stochastic gate and
        // forces don't change measurably between consecutive ~100µs steps).
        // CPU-fallback motors (boundSegSlot < 0) keep their CPU-computed
        // values from MyoFilLink.addForces in this step — the bridge skips
        // them via the boundSegSlot < 0 guard.
        if (myoJointCt > 0) {
            bridgeMotorForceWriteback();
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

    // Phase-0 A/B profile readouts.
    public static int  getMoveAB_CallCount()         { return moveAB_CallCount;         }
    public static long getMoveAB_DeviceKernelNanos() { return moveAB_DeviceKernelNanos; }
    public static long getMoveAB_DeviceWriteNanos()  { return moveAB_DeviceWriteNanos;  }
    public static long getMoveAB_DeviceReadNanos()   { return moveAB_DeviceReadNanos;   }
    public static long getMoveAB_TotalNanos()        { return moveAB_TotalNanos;        }
    public static long getMoveAB_BytesCopyIn()       { return moveAB_BytesCopyIn;       }
    public static long getMoveAB_BytesCopyOut()      { return moveAB_BytesCopyOut;      }
    public static long getMoveAB_SlotCountSum()      { return moveAB_SlotCountSum;      }

    /**
     * Print Phase-0 A/B summary. Called from BoxOfActin's stats block when
     * MOVE_AB_PROFILE is on. Reports per-call device kernel time, effective
     * bandwidth from kernel time alone (pose+force+drag+brownian+velMask
     * read+written per slot), and the % of the RTX 5070's ~500 GB/s peak.
     */
    public static void reportMoveAB() {
        if (!MOVE_AB_PROFILE || moveAB_CallCount == 0) return;
        int    calls       = moveAB_CallCount;
        double meanSlots   = (double) moveAB_SlotCountSum / calls;
        double kernelMs    = (moveAB_DeviceKernelNanos / 1.0e6) / calls;
        double writeMs     = (moveAB_DeviceWriteNanos  / 1.0e6) / calls;
        double readMs      = (moveAB_DeviceReadNanos   / 1.0e6) / calls;
        double totalMs     = (moveAB_TotalNanos        / 1.0e6) / calls;
        // Per-slot move-kernel traffic:
        //   reads  = coord(3) uVec(3) yVec(3) cpuForce(3) cpuTorque(3)
        //            jointForce(3) jointTorque(3) bTransGam(3) bRotGam(3)
        //            brownianScales(2) velMask(3)              = 32 floats
        //   writes = coord(3) uVec(3) yVec(3)                  =  9 floats
        // Total: 41 floats = 164 bytes/slot.
        double bytesPerCall = meanSlots * 164.0;
        double kernelSec    = moveAB_DeviceKernelNanos / 1.0e9;
        double totalBytes   = bytesPerCall * calls;
        double bwGBs        = (kernelSec > 0) ? (totalBytes / kernelSec / 1.0e9) : 0;
        double peakGBs      = 500.0;          // RTX 5070 nominal peak (GDDR7)
        double pctPeak      = 100.0 * bwGBs / peakGBs;

        // Also print a "pose-only" bandwidth (the access pattern Phase-0 is
        // most interested in: 9 floats read + 9 floats written = 72 bytes/slot).
        double poseBytesPerCall = meanSlots * 72.0;
        double poseTotalBytes   = poseBytesPerCall * calls;
        double poseBwGBs        = (kernelSec > 0) ? (poseTotalBytes / kernelSec / 1.0e9) : 0;
        double posePctPeak      = 100.0 * poseBwGBs / peakGBs;

        String layout = SOA_POSE ? "SoA" : "AoS";
        System.out.println();
        System.out.println("*** Phase-0 move-kernel A/B profile (BOA_MOVE_AB_PROFILE=1) ***");
        System.out.printf("  layout=%s  calls=%d  meanSlots=%.0f%n",
                          layout, calls, meanSlots);
        System.out.printf("  device kernel: %.4f ms/call   write: %.4f ms/call   read: %.4f ms/call   total: %.4f ms/call%n",
                          kernelMs, writeMs, readMs, totalMs);
        System.out.printf("  bytes/call (read+write whole-kernel) = %.2f MB%n",
                          bytesPerCall / 1.0e6);
        System.out.printf("  effective BW (whole-kernel): %.2f GB/s (%.2f%% of %.0f GB/s peak)%n",
                          bwGBs, pctPeak, peakGBs);
        System.out.printf("  effective BW (pose-only 9R+9W): %.2f GB/s (%.2f%% of %.0f GB/s peak)%n",
                          poseBwGBs, posePctPeak, peakGBs);
        System.out.printf("  bytes copied: in=%d out=%d%n",
                          moveAB_BytesCopyIn, moveAB_BytesCopyOut);
    }

    public static void reset() {
        closePlan();
        packNanos = execNanos = unpackNanos = totalNanos = jointPackNanos = 0;
        callCount = 0;
        stepCounter = 0;
        topologyDirty = true;
        coordsDirty   = true;
        moveAB_DeviceKernelNanos = moveAB_DeviceWriteNanos = moveAB_DeviceReadNanos
            = moveAB_TotalNanos = moveAB_BytesCopyIn = moveAB_BytesCopyOut
            = moveAB_SlotCountSum = 0;
        moveAB_CallCount = 0;
        runSeedInitialized = false;
    }
}
