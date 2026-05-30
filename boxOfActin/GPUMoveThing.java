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
 * GPU-accelerated Thing.moveThing() via TornadoVM — iteration 2c.
 *
 * Iter2c changes vs iter2b:
 *   - Brownian random forces are generated inside the kernel via Wang hash
 *     + Box-Muller, eliminating the per-step randForces/randTorques upload.
 *     Each kernel thread produces 6 N(0,1) Gaussians from 6 sequential
 *     Wang hashes; the per-axis force magnitude is g * sqrt(2*kT/dt) *
 *     sqrt(bTransGam[axis]) and the torque magnitude is the analogous
 *     bRotGam variant, matching the CPU calcRandomForces() statistical
 *     distribution.
 *   - Pre-built `gpuThingIndices[]` selected once per topology event:
 *     pack/unpack walks a tight slot range instead of doing instanceof on
 *     every Thing every step. Per-slot Brownian rules (FIL vs MYO vs LEVER)
 *     are cached in a small int array so the per-step pack can compute
 *     transScale/rotScale without re-running the eligibility decision.
 *   - bTransGam and bRotGam moved to FIRST_EXECUTION transfer mode and
 *     filled at plan-build time. Invalidated (plan rebuilt) on aeta
 *     mutation via invalidatePlan().
 *   - Kernel parameter count: 11 FloatArrays + 1 IntArray = 12 buffers
 *     (was 13 in iter2b; randForces/randTorques dropped).
 *
 * Scope (gliding-assay first pass): MyoMotor, MyoRod, MyoLever, and root
 * (motherFil == null) FilSegment instances. Ineligible Things (Bug,
 * Chamber, Crucible, AnchorNode, ProteinNode, MyoMiniFilament, StickyNode,
 * FillNode, branch FilSegment, actA-bound FilSegment, isLpSeg-suspended
 * FilSegment, anything when myosinsOff) fall back to CPU moveThing().
 */
public class GPUMoveThing {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation.
     *  Pattern: (m * 1000003) ^ (stepCount * 999983) ^ (runSeed * 7919). */
    public static final int KERNEL_ID = 2;

    // Per-run seed sampled from Env.mtRNG at class load (a fresh JVM gets a
    // fresh mtRNG seed via Long.MAX_VALUE * Math.random()). Different runs
    // therefore produce different GPU Gaussian streams; same-JVM repeats are
    // reproducible from the mtRNG sequence. Multiplied into the per-thread
    // Wang-hash seed inside the kernel.
    private static final int runSeed = Env.mtRNG.nextInt();

    // Brownian-rule codes (cached per slot in classifyThings).
    private static final int RULE_FIL   = 0;  // FilSegment root: tScale = BTransCoeff / (1 + xLinkAttn*linkedToCt)
    private static final int RULE_MYO   = 1;  // MyoMotor / MyoRod: tScale = myoBrownianAttn (constant)
    private static final int RULE_LEVER = 2;  // MyoLever: tScale = 0 (CPU has Brownian commented out)

    // ----- capacity / current count -----
    private static int slotCap   = 0;
    private static int slotCount = 0;

    // ----- per-Thing SoA buffers (capacity slotCap) -----
    private static FloatArray coord;          // slotCap * 3
    private static FloatArray uVec;           // slotCap * 3
    private static FloatArray yVec;           // slotCap * 3
    private static FloatArray forceSum;       // slotCap * 3
    private static FloatArray torqueSum;      // slotCap * 3
    private static FloatArray bTransGam;      // slotCap * 3  (FIRST_EXECUTION)
    private static FloatArray bRotGam;        // slotCap * 3  (FIRST_EXECUTION)
    private static FloatArray brownianScales; // slotCap * 2 (transScale, rotScale)
    private static FloatArray velMask;        // slotCap * 3 (per-axis fixed-frame {0,1}); FIRST_EXECUTION

    // ----- small inputs -----
    private static FloatArray params;         // [0]=deltaT, [1]=brownianForceMag = sqrt(2*kT/dt)
    private static IntArray   counts;         // [0]=N, [1]=stepCount, [2]=runSeed

    // ----- CPU-side index of packed Things, by slot -----
    private static int[]   gpuThingIndices;   // slot -> Thing.theThings[] index
    private static int[]   brownianRule;      // slot -> RULE_FIL / RULE_MYO / RULE_LEVER
    private static Thing[] cpuFallback;
    private static int     cpuFallbackCt = 0;
    private static int     lastThingCt   = -1;
    private static boolean topologyDirty = true;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;
    private static GridScheduler        gridScheduler;

    // Step counter incremented per moveThings() call; seeded into the Wang
    // hash so successive steps produce independent Gaussian streams.
    private static int stepCounter = 0;

    // Block size: 64 leaves headroom for register pressure. The Wang-hash
    // Box-Muller path adds ~12 live floats (6 Gaussians + 6 uniforms) on
    // top of iter2b's footprint. If we see CUDA_ERROR_LAUNCH_OUT_OF_
    // RESOURCES at run time, drop to 32.
    private static final int MOVE_KERNEL_BLOCK_SIZE = 64;

    // Timing accumulators
    private static long packNanos   = 0;
    private static long execNanos   = 0;
    private static long unpackNanos = 0;
    private static long totalNanos  = 0;
    private static int  callCount   = 0;

    // -------------------------------------------------------------------------
    // Wang hash — 32-bit integer mixer used as the per-thread RNG seed.
    // Same-class private static so TornadoVM's PTX compiler inlines it into
    // the kernel.
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
    // GPU kernel — branchless per-Thing integration step with inline
    // Wang-hash Brownian RNG.
    //
    // Each thread:
    //   1. Loads coord, uVec, yVec from SoA.
    //   2. Re-derives zVec = unit(cross(uVec, yVec)).
    //   3. Loads forceSum / torqueSum (fixed frame).
    //   4. Transforms to body frame: bF = [uVec; yVec; zVec] * F (rows).
    //   5. Generates 6 N(0,1) Gaussians via Wang hash + Box-Muller.
    //   6. Adds Brownian: bF += tScale * brownianForceMag * sqrt(bTransGam) * g.
    //   7. Overdamped Langevin: bVeloc = 1e6 * bF / bTransGam,
    //      bAngVeloc = bT / bRotGam.
    //   8. Transforms bVeloc -> fixed frame veloc via transpose.
    //   9. Applies axis velMask (no-op when all 1.0 for in-scope types).
    //  10. Updates coord += dt * veloc.
    //  11. Small-angle uVec/yVec updates via bAngVeloc-driven body-frame
    //      increments, transformed back to fixed frame, normalised.
    // -------------------------------------------------------------------------
    private static void moveThingKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray forceSum,
            FloatArray torqueSum,
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
        float brownianForceMag = params.get(1);  // sqrt(2 * kT / dt)

        for (@Parallel int m = 0; m < coord.getSize() / 3; m++) {
            if (m >= N) { return; }                  // inactive thread slot

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

            // zVec = unit(cross(uVec, yVec))
            float zx = uy * yz - uz * yy;
            float zy = uz * yx - ux * yz;
            float zz = ux * yy - uy * yx;
            float zlen = 1.0f / (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx *= zlen; zy *= zlen; zz *= zlen;

            float fx = forceSum.get(i3);
            float fy = forceSum.get(i3 + 1);
            float fz = forceSum.get(i3 + 2);
            float tx = torqueSum.get(i3);
            float ty = torqueSum.get(i3 + 1);
            float tz = torqueSum.get(i3 + 2);

            // Fixed -> body: bF = transXTox * F where transXTox rows are
            // {uVec, yVec, zVec}. Matches Pt3D.XTox + Thing.transMat().
            float bfx = ux * fx + uy * fy + uz * fz;
            float bfy = yx * fx + yy * fy + yz * fz;
            float bfz = zx * fx + zy * fy + zz * fz;
            float btx = ux * tx + uy * ty + uz * tz;
            float bty = yx * tx + yy * ty + yz * tz;
            float btz = zx * tx + zy * ty + zz * tz;

            // ----- Brownian via Wang hash + Box-Muller --------------------
            // 6 hashes from a single base seed, mixed with KERNEL_ID-flavoured
            // golden-ratio constants. 3 Box-Muller pairs, taking both cos and
            // sin (so each pair yields 2 independent N(0,1) variates). Pair
            // 1 -> {g_fx, g_tx}, pair 2 -> {g_fy, g_ty}, pair 3 -> {g_fz, g_tz}.
            int base = (m * 1000003) ^ (stepCount * 999983) ^ (runSeed * 7919);
            int h1 = wangHash(base);
            int h2 = wangHash(base ^ 0x9e3779b9);
            int h3 = wangHash(base ^ 0x85ebca6b);
            int h4 = wangHash(base ^ 0xc2b2ae35);
            int h5 = wangHash(base ^ 0x517cc1b7);
            int h6 = wangHash(base ^ 0x1f0a7ed5);

            // Map to (0, 1] uniform; clamp u1/u3/u5 away from zero to avoid
            // log(0) -> -inf in Box-Muller.
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

            float r3 = (float) Math.sqrt(-2.0f * (float) Math.log(u5));
            float theta3 = 2.0f * 3.14159265f * u6;
            float gfz = r3 * (float) Math.cos(theta3);
            float gtz = r3 * (float) Math.sin(theta3);

            // CPU calcRandomForces() statistical equivalent:
            //   randForces.x = g * sqrt(2*kT/dt) * sqrt(bTransGam.x)
            //   randTorques.x = g * sqrt(2*kT/dt) * sqrt(bRotGam.x)
            // (Derived from Marsaglia polar + Einstein's bTransDiff=kT/bTransGam.)
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

            // Overdamped Langevin in body frame.
            float bvx = 1.0e6f * bfx / btgX;
            float bvy = 1.0e6f * bfy / btgY;
            float bvz = 1.0e6f * bfz / btgZ;
            float bwx = btx / brgX;
            float bwy = bty / brgY;
            float bwz = btz / brgZ;

            // Body -> fixed: veloc = transxToX * bVeloc where transxToX
            // is the transpose of [uVec; yVec; zVec] (rows -> columns).
            float vx = ux * bvx + yx * bvy + zx * bvz;
            float vy = uy * bvx + yy * bvy + zy * bvz;
            float vz = uz * bvx + yz * bvy + zz * bvz;

            // Axis velocity mask — fixed-frame zeroing (ProteinNode xMove /
            // yMove / zMove). All 1.0 for in-scope gliding-assay types.
            vx *= velMask.get(i3);
            vy *= velMask.get(i3 + 1);
            vz *= velMask.get(i3 + 2);

            coord.set(i3,     cx + dt * vx);
            coord.set(i3 + 1, cy + dt * vy);
            coord.set(i3 + 2, cz + dt * vz);

            // Small-angle orientation update — see FilSegment.java:548 etc.
            float uTransInZ = -bwy * dt;
            float uTransInY =  bwz * dt;
            float nuX = ux + yx * uTransInY + zx * uTransInZ;
            float nuY = uy + yy * uTransInY + zy * uTransInZ;
            float nuZ = uz + yz * uTransInY + zz * uTransInZ;
            float nuInv = 1.0f / (float) Math.sqrt(nuX * nuX + nuY * nuY + nuZ * nuZ);
            uVec.set(i3,     nuX * nuInv);
            uVec.set(i3 + 1, nuY * nuInv);
            uVec.set(i3 + 2, nuZ * nuInv);

            // yVec (body-frame increment) = (-uTransInY, 1, bAngVeloc.x*dt).
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
    // Lazy allocation + plan build.
    //
    // bTransGam, bRotGam, velMask are FIRST_EXECUTION — uploaded on first
    // execute() after plan build and never re-uploaded. classifyThings()
    // fills them with current per-slot values; aeta mutation hooks must
    // call invalidatePlan() to force a rebuild and re-upload.
    // -------------------------------------------------------------------------
    private static void allocateAndBuildPlan(int newCap) {
        slotCap = newCap;

        coord          = new FloatArray(slotCap * 3);
        uVec           = new FloatArray(slotCap * 3);
        yVec           = new FloatArray(slotCap * 3);
        forceSum       = new FloatArray(slotCap * 3);
        torqueSum      = new FloatArray(slotCap * 3);
        bTransGam      = new FloatArray(slotCap * 3);
        bRotGam        = new FloatArray(slotCap * 3);
        brownianScales = new FloatArray(slotCap * 2);
        velMask        = new FloatArray(slotCap * 3);

        params = new FloatArray(2);
        counts = new IntArray(3);

        gpuThingIndices = new int[slotCap];
        brownianRule    = new int[slotCap];
        cpuFallback     = new Thing[Math.max(64, slotCap)];

        // TaskGraph: everything EVERY_EXECUTION. coord/uVec/yVec must be
        // downloaded each step (kernel writes the updated pose).
        //
        // Drag tensors (bTransGam/bRotGam) and velMask were promoted to
        // FIRST_EXECUTION in an early iter2c attempt to save ~2.4 MB/call
        // upload at M=98K, but the gliding-assay population grows by ~1
        // Thing/step during the early-run ramp-up. New slots above the
        // first-call slotCount inherit device-side zeros for FIRST_EXECUTION
        // buffers, and zero bRotGam makes bAngVeloc=inf -> coord=NaN. Plan
        // rebuild every time slotCount grows would amortise back into upload
        // cost. Keeping EVERY_EXECUTION here is the safe baseline; revisit
        // if a steady-population workload (no growth after init) ever
        // becomes the perf target.
        TaskGraph tg = new TaskGraph("moveThing")
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                              coord, uVec, yVec,
                              forceSum, torqueSum,
                              bTransGam, bRotGam,
                              brownianScales, velMask,
                              params, counts)
            .task("move",
                  GPUMoveThing::moveThingKernel,
                  coord, uVec, yVec,
                  forceSum, torqueSum,
                  bTransGam, bRotGam,
                  brownianScales, velMask,
                  params, counts)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                            coord, uVec, yVec);

        itg  = tg.snapshot();
        plan = new TornadoExecutionPlan(itg);

        WorkerGrid worker = new WorkerGrid1D(slotCap);
        worker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
        gridScheduler = new GridScheduler("moveThing.move", worker);

        // Force classify on next call so the new buffers get filled.
        topologyDirty = true;

        System.out.printf("GPUMoveThing: slotCap=%d blockSize=%d runSeed=%d%n",
                          slotCap, MOVE_KERNEL_BLOCK_SIZE, runSeed);
    }

    private static void closePlan() {
        if (plan != null) {
            try { plan.close(); } catch (Exception e) { /* best effort */ }
            plan = null;
            itg  = null;
        }
    }

    /** Invalidate the current plan — used when drag coefficients change
     *  (e.g. aeta mutation triggers calculateProperties on all FilSegments).
     *  Next moveThings() call rebuilds the plan and re-uploads bTransGam /
     *  bRotGam. */
    public static void invalidatePlan() {
        closePlan();
        topologyDirty = true;
    }

    // -------------------------------------------------------------------------
    // Classify Things into GPU vs CPU fallback. Called when topology changes
    // (Thing count differs from last classify, plan rebuild, or explicit
    // invalidation). Walks theThings[] once with instanceof; produces:
    //   - gpuThingIndices[]: stable per-slot mapping for the next step run
    //   - brownianRule[]:    per-slot RULE_FIL/MYO/LEVER for tight pack
    //   - cpuFallback[]:     Things that need CPU moveThing() each step
    //   - bTransGam/bRotGam/velMask FloatArrays filled (FIRST_EXECUTION)
    //   - Thing.gpuHandled flag set/cleared (consumed by ThingBrownianThreads
    //     to skip CPU randForces generation for GPU Things)
    //
    // Eligibility rules (same as iter2b):
    //   - MyoMotor, MyoRod, MyoLever  : eligible unless myosinsOff
    //   - FilSegment (motherFil null) : eligible unless actAOn or
    //                                   (isLpSeg && !lpActive)
    //   - everything else             : fallback to CPU moveThing()
    // -------------------------------------------------------------------------
    private static void classifyThings() {
        int n  = 0;
        int cn = 0;
        int tc = Thing.thingCt;

        double lpActiveV   = Env.lpActive.getValue();
        boolean myosinsOff = Env.myosinsOff;

        // Reset gpuHandled for every Thing (the gpuHandled bit gates CPU
        // calcRandomForces skipping; reclassification must clear stale bits).
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
                // Out-of-scope FilSegment cases fall back to CPU:
                //   - branch (motherFil != null): needs motherFil pre-pass
                //   - actA-bound: needs lmBug randForces swap
                //   - isLpSeg suspended: trivially no-op on CPU too
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
                // Bug, Chamber, Crucible, AnchorNode, ProteinNode,
                // MyoMiniFilament, StickyNode, FillNode, etc.
                if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                continue;
            }

            if (n >= slotCap) {
                // Capacity exhausted — push to fallback. The plan rebuild on
                // the next call (triggered by thingCt > slotCap) will widen
                // the index.
                if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                continue;
            }

            gpuThingIndices[n] = i;
            brownianRule[n]    = rule;
            t.gpuHandled       = true;

            int i3 = n * 3;
            bTransGam.set(i3,     (float) t.bTransGam.x);
            bTransGam.set(i3 + 1, (float) t.bTransGam.y);
            bTransGam.set(i3 + 2, (float) t.bTransGam.z);
            bRotGam.set(i3,       (float) t.bRotGam.x);
            bRotGam.set(i3 + 1,   (float) t.bRotGam.y);
            bRotGam.set(i3 + 2,   (float) t.bRotGam.z);
            // velMask is all 1.0 for the in-scope types — set unconditionally.
            velMask.set(i3,     1.0f);
            velMask.set(i3 + 1, 1.0f);
            velMask.set(i3 + 2, 1.0f);

            n++;
        }

        slotCount     = n;
        cpuFallbackCt = cn;
        lastThingCt   = tc;
        topologyDirty = false;
    }

    /** Public entry — called by BoxOfActin at the top of each step before
     *  the Brownian phase so that ThingBrownianThreads sees up-to-date
     *  gpuHandled flags. No-op if no topology change since last classify. */
    public static void onStepStart() {
        if (plan == null) {
            int initialCap = Math.max(1024, Thing.thingCt * 2);
            allocateAndBuildPlan(initialCap);
        } else if (Thing.thingCt > slotCap) {
            closePlan();
            allocateAndBuildPlan(Math.max(slotCap * 2, Thing.thingCt * 2));
        }
        if (topologyDirty || Thing.thingCt != lastThingCt) {
            classifyThings();
        }
    }

    // -------------------------------------------------------------------------
    // Per-step pack: tight loop over gpuThingIndices. Writes coord, uVec,
    // yVec, forceSum, torqueSum, and recomputes brownianScales from the
    // cached rule + current linkedToCt / filAtEnd flags. Does NOT touch
    // bTransGam/bRotGam/velMask (filled at classify time, FIRST_EXECUTION
    // semantics).
    // -------------------------------------------------------------------------
    private static void packPerStep() {
        // Scales fetched once, broadcast across all FilSegments.
        double bTransCoeffV  = Env.BTransCoeff.getValue();
        double bRotCoeffV    = Env.BRotCoeff.getValue();
        double xLinkTAttnV   = Env.xLinkTransAttn.getValue();
        double xLinkRAttnV   = Env.xLinkRotAttn.getValue();
        double myoBrownianV  = Env.myoBrownianAttn.getValue();
        boolean brownianFilOff = Env.brownianFilMotionOff;
        boolean brownianMyoOff = Env.brownianMyoMotionOff;

        Thing[] theThings = Thing.theThings;
        int[] indices = gpuThingIndices;
        int[] rules   = brownianRule;
        int sc = slotCount;

        for (int slot = 0; slot < sc; slot++) {
            Thing t = theThings[indices[slot]];
            int i3 = slot * 3;
            int i2 = slot * 2;

            // Sequential field reads — Pt3D fields are public doubles.
            coord.set(i3,     (float) t.coord.x);
            coord.set(i3 + 1, (float) t.coord.y);
            coord.set(i3 + 2, (float) t.coord.z);
            uVec.set(i3,      (float) t.uVec.x);
            uVec.set(i3 + 1,  (float) t.uVec.y);
            uVec.set(i3 + 2,  (float) t.uVec.z);
            yVec.set(i3,      (float) t.yVec.x);
            yVec.set(i3 + 1,  (float) t.yVec.y);
            yVec.set(i3 + 2,  (float) t.yVec.z);
            forceSum.set(i3,     (float) t.forceSum.x);
            forceSum.set(i3 + 1, (float) t.forceSum.y);
            forceSum.set(i3 + 2, (float) t.forceSum.z);
            torqueSum.set(i3,     (float) t.torqueSum.x);
            torqueSum.set(i3 + 1, (float) t.torqueSum.y);
            torqueSum.set(i3 + 2, (float) t.torqueSum.z);
            // Drag tensors re-packed every step: a FilSegment's bTransGam
            // depends on its length and would otherwise go stale between
            // classifyThings calls if calculateProperties() were invoked on
            // a length change. Gliding-assay configs don't exercise this
            // path but it costs only six FloatArray writes per slot and
            // keeps the kernel correct for general workloads.
            bTransGam.set(i3,     (float) t.bTransGam.x);
            bTransGam.set(i3 + 1, (float) t.bTransGam.y);
            bTransGam.set(i3 + 2, (float) t.bTransGam.z);
            bRotGam.set(i3,       (float) t.bRotGam.x);
            bRotGam.set(i3 + 1,   (float) t.bRotGam.y);
            bRotGam.set(i3 + 2,   (float) t.bRotGam.z);

            float tScale, rScale;
            int rule = rules[slot];
            if (rule == RULE_FIL) {
                FilSegment f = (FilSegment) t;
                if (brownianFilOff || f.brownianOff) {
                    tScale = 0f; rScale = 0f;
                } else {
                    double ts = bTransCoeffV;
                    double rs = bRotCoeffV;
                    if (f.linkedToCt > 0) {
                        ts = ts / (1.0 + xLinkTAttnV * f.linkedToCt);
                        rs = rs / (1.0 + xLinkRAttnV * f.linkedToCt);
                    }
                    tScale = (float) ts;
                    // Rotational Brownian only applies when at least one end
                    // is free (CPU FilSegment.java:516).
                    rScale = (f.filAtEnd1 && f.filAtEnd2) ? 0f : (float) rs;
                }
            } else if (rule == RULE_MYO) {
                if (brownianMyoOff) {
                    tScale = 0f; rScale = 0f;
                } else {
                    tScale = (float) myoBrownianV;
                    rScale = (float) myoBrownianV;
                }
            } else {  // RULE_LEVER
                tScale = 0f;
                rScale = 0f;
            }
            brownianScales.set(i2,     tScale);
            brownianScales.set(i2 + 1, rScale);
        }
    }

    // -------------------------------------------------------------------------
    // Per-step unpack: write coord/uVec/yVec back to Thing fields and call
    // initialize() to refresh derived geometry (zVec, transMat, uVecR,
    // end1/end2, length, xyzRange).
    // -------------------------------------------------------------------------
    private static void unpackAndInitialize() {
        Thing[] theThings = Thing.theThings;
        int[] indices = gpuThingIndices;
        int sc = slotCount;
        for (int slot = 0; slot < sc; slot++) {
            Thing t = theThings[indices[slot]];
            int i3 = slot * 3;
            t.coord.x = coord.get(i3);
            t.coord.y = coord.get(i3 + 1);
            t.coord.z = coord.get(i3 + 2);
            t.uVec.x  = uVec.get(i3);
            t.uVec.y  = uVec.get(i3 + 1);
            t.uVec.z  = uVec.get(i3 + 2);
            t.yVec.x  = yVec.get(i3);
            t.yVec.y  = yVec.get(i3 + 1);
            t.yVec.z  = yVec.get(i3 + 2);
            t.initialize();
        }
    }

    // -------------------------------------------------------------------------
    // Public entry point.
    // -------------------------------------------------------------------------
    public static void moveThings() {
        long t0 = System.nanoTime();

        // Defensive: if no classify yet (caller forgot onStepStart) or plan
        // got invalidated, do it now.
        if (plan == null || topologyDirty || Thing.thingCt != lastThingCt) {
            onStepStart();
        }

        long packStart = System.nanoTime();
        packPerStep();
        params.set(0, (float) Env.deltaT.getValue());
        // brownianForceMag = sqrt(2 * kT / dt). kT in J; bTransGam already
        // carries the SI units the simulation uses. The full Langevin chain
        // pre-multiplies tScale (BTransCoeff / xLinkAttn) and post-multiplies
        // sqrt(bTransGam.axis) per-axis inside the kernel.
        double kT = Env.Boltz * Env.tempK;
        double dt = Env.deltaT.getValue();
        params.set(1, (float) Math.sqrt(2.0 * kT / dt));
        counts.set(0, slotCount);
        counts.set(1, stepCounter);
        counts.set(2, runSeed);
        long packEnd = System.nanoTime();

        if (slotCount > 0) {
            plan.withGridScheduler(gridScheduler).execute();
        }
        long execEnd = System.nanoTime();

        unpackAndInitialize();
        for (int i = 0; i < cpuFallbackCt; i++) {
            cpuFallback[i].moveThing();
        }
        long unpackEnd = System.nanoTime();

        packNanos   += packEnd   - packStart;
        execNanos   += execEnd   - packEnd;
        unpackNanos += unpackEnd - execEnd;
        totalNanos  += unpackEnd - t0;
        callCount++;
        stepCounter++;
    }

    /** Diagnostic timing accessors — read by BoxOfActin at run end. */
    public static long getTotalNanos()  { return totalNanos;  }
    public static long getPackNanos()   { return packNanos;   }
    public static long getExecNanos()   { return execNanos;   }
    public static long getUnpackNanos() { return unpackNanos; }
    public static int  getCallCount()   { return callCount;   }

    /** Diagnostic counters. */
    public static int getSlotCount()     { return slotCount;     }
    public static int getCpuFallbackCt() { return cpuFallbackCt; }
    public static int getSlotCap()       { return slotCap;       }

    /** Reset the plan (arrays survive); mirrors GPUMotorBinding.reset(). */
    public static void reset() {
        closePlan();
        packNanos = execNanos = unpackNanos = totalNanos = 0;
        callCount = 0;
        stepCounter = 0;
        topologyDirty = true;
    }
}
