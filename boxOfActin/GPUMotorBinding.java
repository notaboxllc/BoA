package boxOfActin;

import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * GPU-accelerated motor-binding decision via TornadoVM.
 *
 * First BoA TornadoVM kernel — deliberately Sim3D-shaped (one plan, EVERY_EXECUTION
 * transfer, per-step pack/execute/unpack). The transfer-bound antipattern is chosen
 * here as the smallest-blast-radius first port; iteration 2 refactors to two-plan
 * persistent residency.
 *
 * Translation of MyoMotor.checkFilSegCollision (post step 1b) into kernel form:
 * the per-pair decision reads only flat arrays — no object dereferencing in the
 * hot path. The kernel produces a *decision* (boundSegId[m] = segment ID or -1); the
 * CPU walks that buffer after execute() and fires the binding *event*
 * (MyoFilLink.setAttachment via ontoFilament), preserving the synchronized
 * event-semantics boundary established in step 1b.
 *
 * Brute-force inner loop over all live segments — no broad-phase. Grid port is
 * iteration 3+ per GPU_STRATEGY.md.
 *
 * Layout note: TornadoVM's task() variadic overloads cap at 15 parameters; a strict
 * SoA layout (separate xPos/yPos/zPos arrays per attribute) would need 21 kernel
 * arguments. This kernel uses Sim3D-style AoS-by-attribute packing — separate
 * FloatArrays per attribute (position, motor uVec, rod uVec, fil endpoints) but
 * with x/y/z interleaved within each. Strict-SoA per GPU_STRATEGY is deferred to
 * iteration 2 when the kernel surface is reshaped for persistent residency.
 */
public class GPUMotorBinding {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation.
     *  Pattern: motorIdx * 1000003 + step * 999983 + KERNEL_ID * 7919.
     *  This kernel does not currently consume RNG (motor binding is deterministic
     *  given positions), but the salt slot is reserved for future stochastic
     *  decisions that may move into this kernel. */
    public static final int KERNEL_ID = 1;

    // Capacity caps — match the step-1a SoA array sizes so capacity is consistent
    // CPU-side and GPU-side. Computed lazily on first call.
    private static int motorCap = 0;
    private static int segCap   = 0;

    // Per-motor inputs (capacity = motorCap)
    private static FloatArray motPos;     // motorCap*3: x,y,z
    private static FloatArray motUVec;    // motorCap*3: ux,uy,uz   (motor head orientation)
    private static FloatArray motRodUVec; // motorCap*3: rux,ruy,ruz (myMyosin.myoRod uVec)
    private static IntArray   motOnFil;   // 0/1; TornadoVM kernels can't take BooleanArray

    // Per-segment inputs (capacity = segCap)
    private static FloatArray filEnd1;        // segCap*3: e1x,e1y,e1z
    private static FloatArray filEnd2;        // segCap*3: e2x,e2y,e2z
    private static IntArray   filNodeAtEnd2;  // 0/1 boolean gate

    // Counts: [0]=motorCt, [1]=segmentCt, [2]=stepCounter (reserved for future RNG seeding)
    private static IntArray   counts;

    // Per-motor output: bound segment ID (or -1 if no candidate satisfied the fine check)
    private static IntArray   boundSegId;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;

    // Timing accumulators
    private static long packNanos   = 0;
    private static long execNanos   = 0;
    private static long unpackNanos = 0;
    private static long totalNanos  = 0;
    private static int  callCount   = 0;

    // -------------------------------------------------------------------------
    // GPU kernel
    // -------------------------------------------------------------------------
    //
    // Parallelism is over motor capacity (motPos.getSize() / 3). Threads beyond
    // the live motor count and threads whose motor is already bound return
    // immediately. Inner loop over all live segments is brute force (no broad-
    // phase) — this is a measurement spike, not the final port.
    //
    // First-hit-wins on the squared-distance test, matching the CPU step-1b
    // shape (which calls ontoFilament inline on the first qualifying segment).
    private static void bindKernel(
            FloatArray motPos,
            FloatArray motUVec,
            FloatArray motRodUVec,
            IntArray   motOnFil,
            FloatArray filEnd1,
            FloatArray filEnd2,
            IntArray   filNodeAtEnd2,
            IntArray   counts,
            IntArray   boundSegId,
            float      alignTol,
            float      myoColTolSq) {

        int M = counts.get(0);
        int S = counts.get(1);

        for (@Parallel int m = 0; m < motPos.getSize() / 3; m++) {
            if (m >= M) { return; }                  // inactive thread slot
            boundSegId.set(m, -1);
            if (motOnFil.get(m) != 0) { continue; }  // already bound — skip

            float mx  = motPos.get(m * 3);
            float my  = motPos.get(m * 3 + 1);
            float mz  = motPos.get(m * 3 + 2);
            float mux = motUVec.get(m * 3);
            float muy = motUVec.get(m * 3 + 1);
            float muz = motUVec.get(m * 3 + 2);
            float rux = motRodUVec.get(m * 3);
            float ruy = motRodUVec.get(m * 3 + 1);
            float ruz = motRodUVec.get(m * 3 + 2);

            for (int s = 0; s < S; s++) {
                if (filNodeAtEnd2.get(s) != 0) { continue; }

                float e1x = filEnd1.get(s * 3);
                float e1y = filEnd1.get(s * 3 + 1);
                float e1z = filEnd1.get(s * 3 + 2);
                float r1x = filEnd2.get(s * 3)     - e1x;
                float r1y = filEnd2.get(s * 3 + 1) - e1y;
                float r1z = filEnd2.get(s * 3 + 2) - e1z;

                // Filament uVec is r1 normalised. Compute denom = |r1|^2 and the
                // unit-vector components via inverse sqrt.
                float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                float invLen = 1.0f / (float) Math.sqrt(denom);
                float fUx = r1x * invLen;
                float fUy = r1y * invLen;
                float fUz = r1z * invLen;

                float motDotFil = mux * fUx + muy * fUy + muz * fUz;
                if (motDotFil < alignTol) { continue; }
                float rodDotFil = rux * fUx + ruy * fUy + ruz * fUz;
                if (rodDotFil < 0f) { continue; }

                float r2x = mx - e1x;
                float r2y = my - e1y;
                float r2z = mz - e1z;
                float numer = r2x * r1x + r2y * r1y + r2z * r1z;
                float alpha = numer / denom;
                if (alpha < 0f || alpha > 1f) { continue; }

                float cpx = e1x + alpha * r1x;
                float cpy = e1y + alpha * r1y;
                float cpz = e1z + alpha * r1z;
                float dx = cpx - mx, dy = cpy - my, dz = cpz - mz;
                float conDistSq = dx * dx + dy * dy + dz * dz;
                if (conDistSq < myoColTolSq) {
                    boundSegId.set(m, s);
                    break;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public entry point: pack → execute → unpack
    // -------------------------------------------------------------------------

    public static void detectBindings() {
        int M = MyoMotor.motorCt;
        int S = FilSegment.filSegmentCt;
        if (M == 0 || S == 0) return;

        long t0 = System.nanoTime();

        // First call: size arrays to the CPU SoA caps, build the plan.
        // Scalars (alignTol, myoColTolSq) are captured at TaskGraph build time —
        // they're not mutable mid-run per Env.java (no setMutableAtRuntime() hook),
        // so the plan is safe to cache for the lifetime of the run.
        if (plan == null) {
            motorCap = MyoMotor.soaX.length;        // 100000
            segCap   = FilSegment.soaEnd1X.length;  // 1000000

            motPos     = new FloatArray(motorCap * 3);
            motUVec    = new FloatArray(motorCap * 3);
            motRodUVec = new FloatArray(motorCap * 3);
            motOnFil   = new IntArray(motorCap);

            filEnd1       = new FloatArray(segCap * 3);
            filEnd2       = new FloatArray(segCap * 3);
            filNodeAtEnd2 = new IntArray(segCap);

            counts     = new IntArray(3);
            boundSegId = new IntArray(motorCap);

            float alignTol    = (float) Env.myoMotorAlignWithFilTolerance.getValue();
            float myoColTol   = (float) Env.myoColTol.getValue();
            float myoColTolSq = myoColTol * myoColTol;

            TaskGraph tg = new TaskGraph("motorBinding")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                                  motPos, motUVec, motRodUVec, motOnFil,
                                  filEnd1, filEnd2, filNodeAtEnd2,
                                  counts)
                .task("bind",
                      GPUMotorBinding::bindKernel,
                      motPos, motUVec, motRodUVec, motOnFil,
                      filEnd1, filEnd2, filNodeAtEnd2,
                      counts, boundSegId,
                      alignTol, myoColTolSq)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, boundSegId);

            itg  = tg.snapshot();
            plan = new TornadoExecutionPlan(itg);
        }

        // ---------- Pack ----------
        // CPU SoA arrays (double) → GPU FloatArrays (float). Cast at the pack site.
        long packStart = System.nanoTime();
        for (int i = 0; i < M; i++) {
            int j = i * 3;
            motPos.set(j,     (float) MyoMotor.soaX[i]);
            motPos.set(j + 1, (float) MyoMotor.soaY[i]);
            motPos.set(j + 2, (float) MyoMotor.soaZ[i]);
            motUVec.set(j,     (float) MyoMotor.soaUX[i]);
            motUVec.set(j + 1, (float) MyoMotor.soaUY[i]);
            motUVec.set(j + 2, (float) MyoMotor.soaUZ[i]);
            motRodUVec.set(j,     (float) MyoMotor.soaRodUX[i]);
            motRodUVec.set(j + 1, (float) MyoMotor.soaRodUY[i]);
            motRodUVec.set(j + 2, (float) MyoMotor.soaRodUZ[i]);
            motOnFil.set(i, MyoMotor.soaOnFil[i] ? 1 : 0);
        }
        for (int s = 0; s < S; s++) {
            int j = s * 3;
            filEnd1.set(j,     (float) FilSegment.soaEnd1X[s]);
            filEnd1.set(j + 1, (float) FilSegment.soaEnd1Y[s]);
            filEnd1.set(j + 2, (float) FilSegment.soaEnd1Z[s]);
            filEnd2.set(j,     (float) FilSegment.soaEnd2X[s]);
            filEnd2.set(j + 1, (float) FilSegment.soaEnd2Y[s]);
            filEnd2.set(j + 2, (float) FilSegment.soaEnd2Z[s]);
            filNodeAtEnd2.set(s, FilSegment.soaNodeAtEnd2[s] ? 1 : 0);
        }
        counts.set(0, M);
        counts.set(1, S);
        counts.set(2, Env.counter);
        long packEnd = System.nanoTime();

        // ---------- Execute ----------
        plan.execute();
        long execEnd = System.nanoTime();

        // ---------- Unpack ----------
        // Walk boundSegId serially on the CPU and fire ontoFilament for each hit.
        // The synchronized event semantics (attachSync, bindTimer gate) live inside
        // ontoFilament — unchanged from CPU step 1b.
        for (int i = 0; i < M; i++) {
            int segIdx = boundSegId.get(i);
            if (segIdx < 0) continue;
            if (segIdx >= S) continue;  // defensive guard against stale data
            FilSegment seg = FilSegment.theFilSegments[segIdx];
            if (seg == null) continue;
            // arcOnFil reconstruction: alpha * |r1|. Recompute from CPU SoA arrays
            // (cheaper than downloading alpha from the GPU; the geometry is small).
            double e1x = FilSegment.soaEnd1X[segIdx];
            double e1y = FilSegment.soaEnd1Y[segIdx];
            double e1z = FilSegment.soaEnd1Z[segIdx];
            double r1x = FilSegment.soaEnd2X[segIdx] - e1x;
            double r1y = FilSegment.soaEnd2Y[segIdx] - e1y;
            double r1z = FilSegment.soaEnd2Z[segIdx] - e1z;
            double r2x = MyoMotor.soaX[i] - e1x;
            double r2y = MyoMotor.soaY[i] - e1y;
            double r2z = MyoMotor.soaZ[i] - e1z;
            double denom = r1x * r1x + r1y * r1y + r1z * r1z;
            double alpha = (r2x * r1x + r2y * r1y + r2z * r1z) / denom;
            double arcOnFil = alpha * Math.sqrt(denom);
            MyoMotor.theMotors[i].ontoFilament(seg, arcOnFil);
        }
        long unpackEnd = System.nanoTime();

        packNanos   += packEnd   - packStart;
        execNanos   += execEnd   - packEnd;
        unpackNanos += unpackEnd - execEnd;
        totalNanos  += unpackEnd - t0;
        callCount++;
    }

    /** Reset the plan; arrays survive across resets, only the plan is rebuilt.
     *  Mirrors Sim3D's reset() pattern for restart workflows. */
    public static void reset() {
        if (plan != null) {
            try { plan.close(); } catch (Exception e) { /* best effort */ }
            plan = null;
            itg  = null;
        }
        packNanos = execNanos = unpackNanos = totalNanos = 0;
        callCount = 0;
    }

    /** Diagnostic timing accessors — read by BoxOfActin at run end. */
    public static long getTotalNanos()  { return totalNanos;  }
    public static long getPackNanos()   { return packNanos;   }
    public static long getExecNanos()   { return execNanos;   }
    public static long getUnpackNanos() { return unpackNanos; }
    public static int  getCallCount()   { return callCount;   }
}
