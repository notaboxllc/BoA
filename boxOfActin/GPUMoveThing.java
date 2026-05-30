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
 * GPU-accelerated Thing.moveThing() via TornadoVM — iteration 2b.
 *
 * Scope (gliding-assay first pass): MyoMotor, MyoRod, MyoLever, and root
 * (motherFil == null) FilSegment instances are packed into a flat Thing-
 * indexed SoA layout and integrated by a single branchless kernel. The
 * kernel itself is type-agnostic: it reads forceSum, torqueSum, the
 * per-Thing drag tensors, and per-Thing Brownian scale factors (computed
 * on CPU before pack), then runs the standard overdamped Langevin step
 * (force/drag -> velocity), the explicit-Euler position update, and the
 * small-angle uVec/yVec rotation. All type-specific decisions (which
 * Brownian scale, which xLink attenuation, end-of-filament torque mask,
 * Lp suspension, etc.) collapse to a per-Thing scalar/mask in the pack
 * pre-pass.
 *
 * Deferred to follow-on sessions (see JOURNAL iter2b survey section K):
 *   - Branch FilSegment motherFil pre-pass (Arp2/3 networks)
 *   - ActA-bound FilSegment bug-frame randForces swap
 *   - StickyNode forceSum pre-add (spherical constraint, pressure, ring)
 *   - FillNode / StickyNode randForces pre-scaling
 *   - ProteinNode velMask axis restrictions and bYMove body-frame mask
 *   - MyoMiniFilament, ProteinNode (out of gliding-assay scope)
 * Things not in the eligible list fall through to a CPU moveThing()
 * tail loop after kernel unpack — Bug, Chamber, Crucible, AnchorNode,
 * post-equilibration StaticFilSegment, any FilSegment with a motherFil
 * (Arp branch), and any of the type list above that may appear in a
 * mixed run.
 *
 * Buffer count: 12 FloatArrays + 1 IntArray = 13 kernel parameters
 * (under the 15-arg cap, matching iteration 2a's slot budget). All
 * buffers are EVERY_EXECUTION: drag tensors can change on mid-run
 * `aeta` mutation, Brownian scales depend on linkedToCt which mutates
 * step-to-step, and slot mapping can shift on FilSegment split. Re-
 * packing 6 floats / Thing each step is cheap relative to the kernel
 * body.
 */
public class GPUMoveThing {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation.
     *  Pattern: thingIdx * 1000003 + step * 999983 + KERNEL_ID * 7919.
     *  Slot reserved; this kernel does not consume RNG (Brownian forces are
     *  generated on the CPU in calcRandomForces() and uploaded). */
    public static final int KERNEL_ID = 2;

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
    private static FloatArray randForces;     // slotCap * 3
    private static FloatArray randTorques;    // slotCap * 3
    private static FloatArray brownianScales; // slotCap * 2 (transScale, rotScale)
    private static FloatArray velMask;        // slotCap * 3 (per-axis fixed-frame {0,1})

    // ----- small inputs -----
    private static FloatArray params;         // [0]=deltaT
    private static IntArray   counts;         // [0]=N

    // ----- CPU-side index of packed Things, by slot -----
    private static Thing[] gpuThings;
    // ----- CPU fallback list: Things that move on CPU each step -----
    private static Thing[] cpuFallback;
    private static int     cpuFallbackCt = 0;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;
    private static GridScheduler        gridScheduler;

    // Block size: 64 leaves headroom for register pressure (~50 live locals
    // in the unified kernel — coord/uVec/yVec/zVec/force/torque/body-frame
    // copies + Brownian + drag). If we ever see CUDA_ERROR_LAUNCH_OUT_OF_
    // RESOURCES at run time, drop to 32.
    private static final int MOVE_KERNEL_BLOCK_SIZE = 64;

    // Timing accumulators
    private static long packNanos   = 0;
    private static long execNanos   = 0;
    private static long unpackNanos = 0;
    private static long totalNanos  = 0;
    private static int  callCount   = 0;

    // -------------------------------------------------------------------------
    // GPU kernel — branchless per-Thing integration step.
    //
    // Each thread:
    //   1. Loads coord, uVec, yVec from SoA.
    //   2. Re-derives zVec = unit(cross(uVec, yVec)).
    //   3. Loads forceSum / torqueSum (fixed frame).
    //   4. Transforms to body frame: bF = [uVec; yVec; zVec] * F (rows).
    //   5. Adds Brownian: bF += transScale * randForces (body frame).
    //   6. Overdamped Langevin: bVeloc = 1e6 * bF / bTransGam,
    //      bAngVeloc = bT / bRotGam.
    //   7. Transforms bVeloc -> fixed frame veloc via transpose.
    //   8. Applies axis velMask (no-op when all 1.0 for in-scope types).
    //   9. Updates coord += dt * veloc.
    //  10. Small-angle uVec/yVec updates via bAngVeloc-driven body-frame
    //      increments, transformed back to fixed frame, normalised.
    //
    // No NaN guards — the CPU baseline's `checkPt3D()` defensive paths only
    // fire on broken physics; if the physics are sane both paths produce
    // identical results. (Validation against CPU catches divergence.)
    // -------------------------------------------------------------------------
    private static void moveThingKernel(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray forceSum,
            FloatArray torqueSum,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray randForces,
            FloatArray randTorques,
            FloatArray brownianScales,
            FloatArray velMask,
            FloatArray params,
            IntArray   counts) {

        int   N  = counts.get(0);
        float dt = params.get(0);

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

            // Brownian add — randForces / randTorques are body-frame on CPU
            // (calcRandomForces builds them via per-component bTransGam /
            // bRotGam, which are body-fixed).
            float tScale = brownianScales.get(i2);
            float rScale = brownianScales.get(i2 + 1);
            bfx += tScale * randForces.get(i3);
            bfy += tScale * randForces.get(i3 + 1);
            bfz += tScale * randForces.get(i3 + 2);
            btx += rScale * randTorques.get(i3);
            bty += rScale * randTorques.get(i3 + 1);
            btz += rScale * randTorques.get(i3 + 2);

            // Overdamped Langevin in body frame.
            float bvx = 1.0e6f * bfx / bTransGam.get(i3);
            float bvy = 1.0e6f * bfy / bTransGam.get(i3 + 1);
            float bvz = 1.0e6f * bfz / bTransGam.get(i3 + 2);
            float bwx = btx / bRotGam.get(i3);
            float bwy = bty / bRotGam.get(i3 + 1);
            float bwz = btz / bRotGam.get(i3 + 2);

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

            // Small-angle orientation update.
            // CPU pattern (FilSegment.java:548, MyoMotor.java:304, etc.):
            //   uVecTransInZ = -bAngVeloc.y * dt
            //   uVecTransInY =  bAngVeloc.z * dt
            //   uVec.setVals(1, uVecTransInY, uVecTransInZ); uVec.xToX(this); uVec.unitVec();
            // xToX in body->fixed uses transxToX, which is transpose of [u;y;z].
            // So new uVec (fixed) = u*1 + y*uTransInY + z*uTransInZ.
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
    // Lazy allocation + plan build. Capacity sized at first call to 2 *
    // current thingCt with a 1024 floor; reallocated only if subsequent
    // thingCt growth exceeds slotCap.
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
        randForces     = new FloatArray(slotCap * 3);
        randTorques    = new FloatArray(slotCap * 3);
        brownianScales = new FloatArray(slotCap * 2);
        velMask        = new FloatArray(slotCap * 3);

        params = new FloatArray(1);
        counts = new IntArray(1);

        gpuThings   = new Thing[slotCap];
        cpuFallback = new Thing[Math.max(64, slotCap)];

        TaskGraph tg = new TaskGraph("moveThing")
            .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                              coord, uVec, yVec,
                              forceSum, torqueSum,
                              bTransGam, bRotGam,
                              randForces, randTorques,
                              brownianScales, velMask,
                              params, counts)
            .task("move",
                  GPUMoveThing::moveThingKernel,
                  coord, uVec, yVec,
                  forceSum, torqueSum,
                  bTransGam, bRotGam,
                  randForces, randTorques,
                  brownianScales, velMask,
                  params, counts)
            .transferToHost(DataTransferMode.EVERY_EXECUTION,
                            coord, uVec, yVec);

        itg  = tg.snapshot();
        plan = new TornadoExecutionPlan(itg);

        WorkerGrid worker = new WorkerGrid1D(slotCap);
        worker.setLocalWork(MOVE_KERNEL_BLOCK_SIZE, 1, 1);
        gridScheduler = new GridScheduler("moveThing.move", worker);

        System.out.printf("GPUMoveThing: slotCap=%d blockSize=%d%n",
                          slotCap, MOVE_KERNEL_BLOCK_SIZE);
    }

    private static void closePlan() {
        if (plan != null) {
            try { plan.close(); } catch (Exception e) { /* best effort */ }
            plan = null;
            itg  = null;
        }
    }

    // -------------------------------------------------------------------------
    // Pre-pack partition: walk theThings, separate eligible (GPU) from
    // fallback (CPU). Computes per-Thing Brownian scales and velMask in
    // place of the type-specific branches in each moveThing() body.
    //
    // Eligibility rules (iter2b first pass, gliding-assay scope):
    //   - MyoMotor, MyoRod, MyoLever  : eligible unless myosinsOff
    //   - FilSegment (motherFil null) : eligible unless isLpSeg && !lpActive
    //   - everything else             : fallback to CPU moveThing()
    //
    // Things in the fallback list still get moveThing() called serially
    // after kernel unpack to preserve full simulation correctness for
    // mixed-population runs.
    // -------------------------------------------------------------------------
    private static void packCpuSideArrays() {
        int n = 0;
        int cn = 0;
        int tc = Thing.thingCt;

        double bTransCoeffV  = Env.BTransCoeff.getValue();
        double bRotCoeffV    = Env.BRotCoeff.getValue();
        double xLinkTAttnV   = Env.xLinkTransAttn.getValue();
        double xLinkRAttnV   = Env.xLinkRotAttn.getValue();
        double myoBrownianV  = Env.myoBrownianAttn.getValue();
        double lpActiveV     = Env.lpActive.getValue();
        boolean brownianFilOff = Env.brownianFilMotionOff;
        boolean brownianMyoOff = Env.brownianMyoMotionOff;
        boolean myosinsOff     = Env.myosinsOff;

        for (int i = 0; i < tc; i++) {
            Thing t = Thing.theThings[i];
            if (t == null || t.removeMe) continue;

            float tScale = 0f;
            float rScale = 0f;
            boolean eligible = false;

            if (t instanceof FilSegment) {
                FilSegment f = (FilSegment) t;
                // Out-of-scope FilSegment cases fall back to CPU:
                //   - branch (motherFil != null): needs motherFil pre-pass
                //   - actA-bound: needs lmBug randForces swap
                //   - isLpSeg suspended: trivially no-op on CPU too
                if (f.motherFil != null || f.actAOn) {
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                if (f.isLpSeg && lpActiveV == 0) {
                    // CPU moveThing early-returns; matching skip here keeps
                    // bookkeeping consistent (Thing stays alive, no motion).
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                if (!brownianFilOff && !f.brownianOff) {
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
                eligible = true;
            } else if (t instanceof MyoMotor || t instanceof MyoRod) {
                if (myosinsOff) {
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                if (!brownianMyoOff) {
                    tScale = (float) myoBrownianV;
                    rScale = (float) myoBrownianV;
                }
                eligible = true;
            } else if (t instanceof MyoLever) {
                if (myosinsOff) {
                    if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                    continue;
                }
                // CPU MyoLever has the Brownian inc lines commented out
                // (MyoLever.java:112-113). Effective scales = 0.
                eligible = true;
            } else {
                // Bug, Chamber, Crucible, AnchorNode, ProteinNode,
                // MyoMiniFilament, StickyNode, FillNode, post-eq
                // StaticFilSegment (its override no-ops after time gate
                // and falls through here) — all out of iter2b scope.
                if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                continue;
            }

            if (!eligible) continue;
            if (n >= slotCap) {
                // Capacity exhausted — fall back to CPU for the overflow.
                // Plan rebuild will pick up the new size on the next call.
                if (cn < cpuFallback.length) cpuFallback[cn++] = t;
                continue;
            }

            gpuThings[n] = t;
            int i3 = n * 3;
            int i2 = n * 2;

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
            bTransGam.set(i3,     (float) t.bTransGam.x);
            bTransGam.set(i3 + 1, (float) t.bTransGam.y);
            bTransGam.set(i3 + 2, (float) t.bTransGam.z);
            bRotGam.set(i3,       (float) t.bRotGam.x);
            bRotGam.set(i3 + 1,   (float) t.bRotGam.y);
            bRotGam.set(i3 + 2,   (float) t.bRotGam.z);
            randForces.set(i3,     (float) t.randForces.x);
            randForces.set(i3 + 1, (float) t.randForces.y);
            randForces.set(i3 + 2, (float) t.randForces.z);
            randTorques.set(i3,     (float) t.randTorques.x);
            randTorques.set(i3 + 1, (float) t.randTorques.y);
            randTorques.set(i3 + 2, (float) t.randTorques.z);
            brownianScales.set(i2,     tScale);
            brownianScales.set(i2 + 1, rScale);
            velMask.set(i3,     1.0f);
            velMask.set(i3 + 1, 1.0f);
            velMask.set(i3 + 2, 1.0f);

            n++;
        }

        slotCount     = n;
        cpuFallbackCt = cn;
    }

    // -------------------------------------------------------------------------
    // Unpack kernel results into Thing.coord/uVec/yVec, then run the type-
    // aware reconciliation on the CPU side: initialize() recomputes zVec,
    // transMat, uVecR, end1/end2, length, xyzRange. CPU is serial here;
    // initialize is ~10 flops per Thing and gliding-assay slotCount sits
    // around ~1700 — negligible relative to the kernel body.
    // -------------------------------------------------------------------------
    private static void unpackAndInitialize() {
        for (int m = 0; m < slotCount; m++) {
            Thing t = gpuThings[m];
            int i3 = m * 3;
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

        int tc = Thing.thingCt;
        if (plan == null) {
            int initialCap = Math.max(1024, tc * 2);
            allocateAndBuildPlan(initialCap);
        } else if (tc > slotCap) {
            // Population grew past current capacity (e.g. many FilSegment
            // splits). Rebuild with headroom.
            closePlan();
            allocateAndBuildPlan(Math.max(slotCap * 2, tc * 2));
        }

        long packStart = System.nanoTime();
        packCpuSideArrays();
        params.set(0, (float) Env.deltaT.getValue());
        counts.set(0, slotCount);
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
    }
}
