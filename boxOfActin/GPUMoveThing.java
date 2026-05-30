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
 * GPU-accelerated Thing.moveThing() via TornadoVM — iteration 2d.
 *
 * Iter2d changes vs iter2c:
 *   - Parallel pack and unpack on a persistent N_WORKERS daemon-thread pool
 *     (default N = Math.min(16, availableProcessors)). Each worker handles a
 *     contiguous slot range; FloatArray.set/get on disjoint indices is safe
 *     because the underlying MemorySegment writes a single 4-byte word per
 *     index with no shared metadata.
 *   - coord/uVec/yVec are not re-packed for myosin slots on steps that don't
 *     follow a classify (topology) event. Between unpack(N) and pack(N+1),
 *     CPU phases never write to a GPU-handled MyoMotor/MyoRod/MyoLever's
 *     coord/uVec/yVec — those fields are touched only by moveThing(), which
 *     is the kernel itself. The previous step's FloatArray contents are
 *     already the correct CPU-visible state. FilSegment slots still re-pack
 *     coord/uVec/yVec every step because biochemStep poly/depoly can call
 *     coord.inc(), and the gliding-assay FilSegment count is small enough
 *     that the per-slot Pt3D-read cost is negligible.
 *   - On the first call after classifyThings() (or after plan rebuild), the
 *     pack runs in "full" mode and writes coord/uVec/yVec for every slot.
 *     Subsequent steady-state calls run in "resident" mode.
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
    private static FloatArray bTransGam;      // slotCap * 3
    private static FloatArray bRotGam;        // slotCap * 3
    private static FloatArray brownianScales; // slotCap * 2 (transScale, rotScale)
    private static FloatArray velMask;        // slotCap * 3 (per-axis fixed-frame {0,1})

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
    /** Set true whenever classifyThings remaps slot->thing or new capacity is
     *  allocated; the next packPerStep must write coord/uVec/yVec for every
     *  slot. Cleared at the end of packPerStep. */
    private static boolean coordsDirty   = true;

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

    // ---------------- Worker pool (iter2d) -------------------------------
    // Persistent daemon threads share the simulation's existing Env.allThreadCt
    // budget but stay parked between dispatches. Each dispatch (pack or
    // unpack) divides slotCount into contiguous chunks; workers and the
    // calling thread coordinate via two synchronized rendezvous points
    // (start signal + completion count).
    private static final int N_WORKERS = Math.max(1,
            Math.min(Env.allThreadCt, Runtime.getRuntime().availableProcessors()));
    private static final int OP_PACK_FULL          = 0;
    private static final int OP_PACK_RESIDENT      = 1;
    private static final int OP_UNPACK             = 2;
    // Bulk SoA derived recompute + Pt3D bridge over a contiguous Thing-index
    // range. Replaces the per-Thing t.initialize() call that used to run
    // inside unpackRange — the bulk pass amortises method dispatch and runs
    // a SIMD-friendly tight loop over the canonical SoA arrays. The bridge
    // step keeps unconverted CPU readers (Pt3D.end1.x etc.) seeing the
    // freshly-computed derived state.
    private static final int OP_DERIVED_AND_BRIDGE = 3;
    // Threshold below which the post-unpack bulk recompute + Pt3D bridge
    // runs inline on the main thread rather than dispatching to the worker
    // pool. Picked so gliding-assay-scale runs (Thing.thingCt ≈ 1300) avoid
    // ~10 ms / step dispatch overhead while dense runs (≈ 588 K) still see
    // the 16-way parallel speedup.
    private static final int DERIVED_BRIDGE_PARALLEL_THRESHOLD = 8000;
    private static Thread[] workers;
    private static final Object phaseLock = new Object();
    private static int     currentPhase   = 0;   // bumped by master per dispatch
    private static int     workersDone    = 0;
    private static int     workOp         = 0;
    private static int     workSlotCount  = 0;
    private static int     workChunkSize  = 0;
    // Pre-fetched scalar constants for the pack path (re-read each dispatch).
    private static float   sBTransCoeff;
    private static float   sBRotCoeff;
    private static float   sXLinkTAttn;
    private static float   sXLinkRAttn;
    private static float   sMyoBrownian;
    private static boolean sBrownianFilOff;
    private static boolean sBrownianMyoOff;

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
    // Wang-hash Brownian RNG. (Unchanged from iter2c.)
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

            // Fixed -> body
            float bfx = ux * fx + uy * fy + uz * fz;
            float bfy = yx * fx + yy * fy + yz * fz;
            float bfz = zx * fx + zy * fy + zz * fz;
            float btx = ux * tx + uy * ty + uz * tz;
            float bty = yx * tx + yy * ty + yz * tz;
            float btz = zx * tx + zy * ty + zz * tz;

            // ----- Brownian via Wang hash + Box-Muller --------------------
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

            float r3 = (float) Math.sqrt(-2.0f * (float) Math.log(u5));
            float theta3 = 2.0f * 3.14159265f * u6;
            float gfz = r3 * (float) Math.cos(theta3);
            float gtz = r3 * (float) Math.sin(theta3);

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

            // Body -> fixed.
            float vx = ux * bvx + yx * bvy + zx * bvz;
            float vy = uy * bvx + yy * bvy + zy * bvz;
            float vz = uz * bvx + yz * bvy + zz * bvz;

            vx *= velMask.get(i3);
            vy *= velMask.get(i3 + 1);
            vz *= velMask.get(i3 + 2);

            coord.set(i3,     cx + dt * vx);
            coord.set(i3 + 1, cy + dt * vy);
            coord.set(i3 + 2, cz + dt * vz);

            // Small-angle orientation update.
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
    // Lazy allocation + plan build.
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

        // Everything EVERY_EXECUTION; the per-call upload of coord/uVec/yVec
        // is required because the kernel updates them in place and the
        // device-side state must match the FloatArray after each Java-side
        // change. coord pack-write skipping (iter2d) avoids the FloatArray
        // re-write on the Java side; the upload itself remains a single
        // bulk DMA and is not the bottleneck.
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
        coordsDirty   = true;

        System.out.printf("GPUMoveThing: slotCap=%d blockSize=%d runSeed=%d nWorkers=%d%n",
                          slotCap, MOVE_KERNEL_BLOCK_SIZE, runSeed, N_WORKERS);
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
        coordsDirty   = true;
    }

    // -------------------------------------------------------------------------
    // Classify Things into GPU vs CPU fallback. (Unchanged from iter2c except
    // for marking coordsDirty.)
    // -------------------------------------------------------------------------
    private static void classifyThings() {
        int n  = 0;
        int cn = 0;
        int tc = Thing.thingCt;

        double lpActiveV   = Env.lpActive.getValue();
        boolean myosinsOff = Env.myosinsOff;

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

            int i3 = n * 3;
            // velMask is all 1.0 for the in-scope types — fill it once at
            // classify time (drag tensors are repacked every step by the
            // per-step pack).
            velMask.set(i3,     1.0f);
            velMask.set(i3 + 1, 1.0f);
            velMask.set(i3 + 2, 1.0f);

            n++;
        }

        slotCount     = n;
        cpuFallbackCt = cn;
        lastThingCt   = tc;
        topologyDirty = false;
        // Slot->Thing mapping may have shifted; the next pack must rewrite
        // coord/uVec/yVec for every slot.
        coordsDirty   = true;
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
                        Thing.bridgeDerivedToPt3D(start, end);
                        break;
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
    // Pack a contiguous slot range. Called from each worker. The packCoords
    // parameter selects between full pack (writes coord/uVec/yVec for every
    // slot) and resident pack (writes coord/uVec/yVec only for FilSegment
    // slots, which can be perturbed by biochemStep poly/depoly between
    // steps; myosin slots inherit the kernel's previous-step output via the
    // unchanged FloatArray).
    // -------------------------------------------------------------------------
    private static void packRange(int slotStart, int slotEnd, boolean packCoords) {
        Thing[] theThings  = Thing.theThings;
        int[]   indices    = gpuThingIndices;
        int[]   rules      = brownianRule;
        float[] soaForce   = Thing.soaForceSum;
        float[] soaTorque  = Thing.soaTorqueSum;
        // Canonical SoA pose: coord/uVec/yVec are already float arrays matching
        // the GPU FloatArray layout. The pack is a float→float copy from a
        // contiguous backing array; no Pt3D pointer chase, no narrowing.
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
            // only mutate coord inside moveThing (the kernel), so the
            // FloatArray already matches the SoA state on steady steps.
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
            // Forces/torques are already float in the canonical
            // soaForceSum/soaTorqueSum arrays.
            forceSum.set(i3,     soaForce[s3]);
            forceSum.set(i3 + 1, soaForce[s3 + 1]);
            forceSum.set(i3 + 2, soaForce[s3 + 2]);
            torqueSum.set(i3,     soaTorque[s3]);
            torqueSum.set(i3 + 1, soaTorque[s3 + 1]);
            torqueSum.set(i3 + 2, soaTorque[s3 + 2]);
            // Drag tensors are still in Pt3D (read-mostly, change only on
            // calculateProperties — no SoA storage yet).
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

    private static void unpackRange(int slotStart, int slotEnd) {
        int[]   indices   = gpuThingIndices;
        float[] soaCoordArr = Thing.soaCoord;
        float[] soaUVecArr  = Thing.soaUVec;
        float[] soaYVecArr  = Thing.soaYVec;
        for (int slot = slotStart; slot < slotEnd; slot++) {
            int thingIdx = indices[slot];
            int i3 = slot * 3;
            int s3 = thingIdx * 3;
            // Write kernel output into the canonical SoA pose arrays
            // (contiguous float[] writes, no Pt3D pointer chase). Derived
            // fields (zVec/transXTox/end1/end2) and the Pt3D bridge run
            // in a separate parallel pass (OP_DERIVED_AND_BRIDGE) after
            // every worker has finished pose unpacks AND the cpuFallback
            // moveThing loop has pushed its pose to SoA, so the bulk
            // recompute sees a globally-consistent SoA pose snapshot.
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
    // Public entry point.
    // -------------------------------------------------------------------------
    public static void moveThings() {
        long t0 = System.nanoTime();

        if (plan == null || topologyDirty || Thing.thingCt != lastThingCt) {
            onStepStart();
        }

        // Snapshot scalar params once per call so the worker pack can read
        // primitive fields without touching the Env Parameter machinery
        // (which involves a HashMap.get).
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
        params.set(0, (float) Env.deltaT.getValue());
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

        if (sc > 0) {
            dispatchAndWait(OP_UNPACK, sc);
        }
        for (int i = 0; i < cpuFallbackCt; i++) {
            cpuFallback[i].moveThing();
        }
        // SoA derived recompute + Pt3D bridge: now that every Thing has a
        // fresh SoA pose (GPU workers wrote it for kernel slots; cpuFallback
        // moveThing pushed it via pushPoseToSoa), the bulk pass can read the
        // canonical pose, compute zVec/transXTox/end1/end2 into SoA derived
        // arrays, and copy the result back into the Pt3D bridge fields that
        // unconverted CPU readers chase. Parallelised across the same worker
        // pool that just ran OP_UNPACK; partition is over Thing indices
        // [0, thingCt) rather than GPU slots. cpuFallback Things see a
        // redundant overwrite of their Pt3D with the same values they
        // computed inside initialize() — harmless, and avoids needing a
        // sparse "GPU slot index → Thing index" set membership check.
        int tc = Thing.thingCt;
        if (tc > 0) {
            // Skip the worker dispatch for small thingCt — at gliding-assay
            // scale (~1300 Things) the per-step dispatch overhead (~0.1–1 ms
            // for synchronized notifyAll + wait across 16 workers) dwarfs the
            // actual bulk-pass work (~130 µs single-threaded). Threshold
            // chosen well below the dense-scale Thing count (~588 K) so the
            // dense path keeps the parallel speedup.
            if (tc < DERIVED_BRIDGE_PARALLEL_THRESHOLD) {
                Thing.recomputeDerivedSoA(0, tc);
                Thing.bridgeDerivedToPt3D(0, tc);
            } else {
                dispatchAndWait(OP_DERIVED_AND_BRIDGE, tc);
            }
            // FilSegment xRange/yRange/zRange — only FilSegments read these
            // (collision quick-reject in mightFilsCollide). Other subclasses
            // write them in initialize() but never read them, so skipping the
            // update for them is safe. Cheap pass — one iter per FilSegment.
            int filCt = FilSegment.filSegmentCt;
            FilSegment[] fils = FilSegment.theFilSegments;
            for (int i = 0; i < filCt; i++) {
                FilSegment fs = fils[i];
                if (fs == null || fs.removeMe) continue;
                fs.xRange = Math.abs(fs.coord.x - fs.end2.x);
                fs.yRange = Math.abs(fs.coord.y - fs.end2.y);
                fs.zRange = Math.abs(fs.coord.z - fs.end2.z);
            }
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
    public static int getNumWorkers()    { return N_WORKERS;     }

    /** Reset the plan (arrays survive); mirrors GPUMotorBinding.reset(). */
    public static void reset() {
        closePlan();
        packNanos = execNanos = unpackNanos = totalNanos = 0;
        callCount = 0;
        stepCounter = 0;
        topologyDirty = true;
        coordsDirty   = true;
    }
}
