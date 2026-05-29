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
 * GPU-accelerated motor-binding decision via TornadoVM — iteration 2a.
 *
 * Iteration 1 (commit 8628946) shipped a brute-force single-plan spike: GPU
 * ran 1.10× slower than CPU because the inner loop was O(M*S) while CPU was
 * already grid-accelerated. Iteration 2a ports the broad-phase spatial grid
 * to GPU. CPU still builds MotorBindGrid3D each step (the existing CPU
 * FillThreads, unchanged); a new packForGPU() walks the per-cell arrays into
 * a flat CSR layout (gridCellOffsets / gridCellContents); the GPU kernel
 * reads the resident grid, walks the 27-cell neighbourhood per motor, and
 * runs the narrow-phase orientation + sphere-line distance gate against only
 * the segment IDs the grid emits. The brute-force inner loop is gone.
 *
 * Buffer reuse vs true residency. The FloatArray/IntArray device buffers
 * survive across plan.execute() calls automatically (TornadoVM owns them
 * inside the ExecutionPlan and only frees on close). Static-during-run
 * inputs (gridParams, gridDims) ride FIRST_EXECUTION — uploaded once and
 * reused. Dynamic inputs (motor SoA, segment SoA, grid CSR, counts) ride
 * EVERY_EXECUTION because their CPU contents change every step (motors and
 * segments move under CPU integration; the grid rebuilds each step from the
 * FillThreads pass to match the motor displacement). The boundSegId output
 * comes back EVERY_EXECUTION. We do NOT call persistOnDevice — it is
 * literally an alias for transferToHost(UNDER_DEMAND, ...) (see
 * TaskGraph.java:756-760) and is intended for the multi-graph
 * consumeFromDevice handoff pattern; in our single-graph design it would be
 * a no-op. This is one of the prompt's misconceptions corrected during the
 * iter2a session (see JOURNAL).
 *
 * True residency — where positions evolve on the GPU and the CPU rarely
 * reads them back — requires porting position integration (moveThing,
 * Brownian, gliding-stroke) to GPU. That is iteration 2b's scope, not 2a's.
 *
 * 15-param cap (Task1..Task15 in TornadoFunctions): this kernel uses 13
 * array parameters and 0 scalar parameters; alignTol and myoColTolSq are
 * packed into gridParams alongside the grid origin and cellSize.
 */
public class GPUMotorBinding {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation.
     *  Pattern: motorIdx * 1000003 + step * 999983 + KERNEL_ID * 7919.
     *  Slot reserved; this kernel does not consume RNG. */
    public static final int KERNEL_ID = 1;

    // Capacity caps and grid dims — set on first call from CPU singletons.
    private static int motorCap = 0;
    private static int segCap   = 0;
    private static int totalCells = 0;
    private static int contentsCap = 0;

    // Per-motor inputs (capacity = motorCap)
    private static FloatArray motPos;     // motorCap*3: x,y,z
    private static FloatArray motUVec;    // motorCap*3
    private static FloatArray motRodUVec; // motorCap*3
    private static IntArray   motOnFil;   // motorCap

    // Per-segment inputs (capacity = segCap)
    private static FloatArray filEnd1;        // segCap*3
    private static FloatArray filEnd2;        // segCap*3
    private static IntArray   filNodeAtEnd2;  // segCap

    // Grid CSR (broad-phase)
    private static IntArray   gridCellOffsets;  // totalCells+1
    private static IntArray   gridCellContents; // capped at contentsCap

    // Static grid params — FIRST_EXECUTION upload, packed FloatArray:
    //   [0]=xMin, [1]=yMin, [2]=zMin, [3]=cellSize, [4]=invCellSize,
    //   [5]=alignTol, [6]=myoColTolSq
    private static FloatArray gridParams;
    // Static grid dims — FIRST_EXECUTION: [nXBins, nYBins, nZBins, totalCells]
    private static IntArray   gridDims;

    // Counts: [0]=motorCt, [1]=segmentCt, [2]=stepCounter (reserved for RNG)
    private static IntArray   counts;

    // Per-motor output
    private static IntArray   boundSegId;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;
    private static GridScheduler        gridScheduler;

    // Workgroup size for the bind kernel. PTX default (256-1024) launches the
    // kernel with too many registers per block; CUDA_ERROR_LAUNCH_OUT_OF_RESOURCES
    // (701) fires silently and boundSegId is never written. Empirically 64
    // threads/block leaves enough register headroom for the fused kernel's
    // ~60 live locals (motor pose floats + cell-walk indices + narrow-phase
    // float scratch). If the kernel grows further, drop to 32.
    private static final int BIND_KERNEL_BLOCK_SIZE = 64;

    // Timing accumulators
    private static long packNanos       = 0;
    private static long gridPackNanos   = 0;
    private static long execNanos       = 0;
    private static long unpackNanos     = 0;
    private static long totalNanos      = 0;
    private static int  callCount       = 0;

    // -------------------------------------------------------------------------
    // GPU kernel — fused broad+narrow phase
    //
    // Each motor thread:
    //   1. Loads its position + orientation from resident SoA arrays.
    //   2. Computes its cell (ix, iy, iz) from gridParams origin+cellSize.
    //   3. Walks the 27-cell neighbourhood (dx,dy,dz in -1..+1).
    //   4. For each segment ID in those cells, runs the narrow-phase
    //      orientation gate + sphere-line distance test (identical to
    //      iteration 1's brute-force inner body, just over a candidate set
    //      instead of all segments).
    //   5. First hit wins — break and return the segment ID via boundSegId.
    //
    // gridCellOffsets is the standard CSR offsets array of length
    // totalCells+1: cell N's contents span [offsets[N], offsets[N+1]).
    // -------------------------------------------------------------------------
    private static void bindKernel(
            FloatArray motPos,
            FloatArray motUVec,
            FloatArray motRodUVec,
            IntArray   motOnFil,
            FloatArray filEnd1,
            FloatArray filEnd2,
            IntArray   filNodeAtEnd2,
            IntArray   gridCellOffsets,
            IntArray   gridCellContents,
            FloatArray gridParams,
            IntArray   gridDims,
            IntArray   counts,
            IntArray   boundSegId) {

        int M = counts.get(0);

        float xMin        = gridParams.get(0);
        float yMin        = gridParams.get(1);
        float zMin        = gridParams.get(2);
        float invCellSize = gridParams.get(4);
        float alignTol    = gridParams.get(5);
        float myoColTolSq = gridParams.get(6);

        int nXBins = gridDims.get(0);
        int nYBins = gridDims.get(1);
        int nZBins = gridDims.get(2);
        int nXY    = nXBins * nYBins;

        for (@Parallel int m = 0; m < motPos.getSize() / 3; m++) {
            if (m >= M) { return; }                  // inactive thread slot
            boundSegId.set(m, -1);
            if (motOnFil.get(m) != 0) { continue; }  // already bound

            float mx  = motPos.get(m * 3);
            float my  = motPos.get(m * 3 + 1);
            float mz  = motPos.get(m * 3 + 2);
            float mux = motUVec.get(m * 3);
            float muy = motUVec.get(m * 3 + 1);
            float muz = motUVec.get(m * 3 + 2);
            float rux = motRodUVec.get(m * 3);
            float ruy = motRodUVec.get(m * 3 + 1);
            float ruz = motRodUVec.get(m * 3 + 2);

            // Motor's home cell — clamp to [0, nBins-1].
            int ix = (int) ((mx - xMin) * invCellSize);
            int iy = (int) ((my - yMin) * invCellSize);
            int iz = (int) ((mz - zMin) * invCellSize);
            if (ix < 0)        ix = 0;
            if (ix >= nXBins)  ix = nXBins - 1;
            if (iy < 0)        iy = 0;
            if (iy >= nYBins)  iy = nYBins - 1;
            if (iz < 0)        iz = 0;
            if (iz >= nZBins)  iz = nZBins - 1;

            // 27-cell neighbourhood walk.
            int found = -1;
            for (int dz = -1; dz <= 1 && found < 0; dz++) {
                int ciz = iz + dz;
                if (ciz < 0 || ciz >= nZBins) continue;
                int izOff = ciz * nXY;
                for (int dy = -1; dy <= 1 && found < 0; dy++) {
                    int ciy = iy + dy;
                    if (ciy < 0 || ciy >= nYBins) continue;
                    int iyOff = ciy * nXBins;
                    for (int dx = -1; dx <= 1 && found < 0; dx++) {
                        int cix = ix + dx;
                        if (cix < 0 || cix >= nXBins) continue;

                        int cellId = cix + iyOff + izOff;
                        int start  = gridCellOffsets.get(cellId);
                        int end    = gridCellOffsets.get(cellId + 1);

                        for (int idx = start; idx < end; idx++) {
                            int s = gridCellContents.get(idx);
                            if (filNodeAtEnd2.get(s) != 0) { continue; }

                            float e1x = filEnd1.get(s * 3);
                            float e1y = filEnd1.get(s * 3 + 1);
                            float e1z = filEnd1.get(s * 3 + 2);
                            float r1x = filEnd2.get(s * 3)     - e1x;
                            float r1y = filEnd2.get(s * 3 + 1) - e1y;
                            float r1z = filEnd2.get(s * 3 + 2) - e1z;

                            float denom  = r1x * r1x + r1y * r1y + r1z * r1z;
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
                            float ddx = cpx - mx, ddy = cpy - my, ddz = cpz - mz;
                            float conDistSq = ddx * ddx + ddy * ddy + ddz * ddz;
                            if (conDistSq < myoColTolSq) {
                                found = s;
                                break;
                            }
                        }
                    }
                }
            }
            if (found >= 0) { boundSegId.set(m, found); }
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

        // First call: size arrays to the CPU SoA caps + grid dims, build the plan.
        if (plan == null) {
            MotorBindGrid3D grid = MotorBindGrid3D.INSTANCE;
            motorCap    = MyoMotor.soaX.length;
            segCap      = FilSegment.soaEnd1X.length;
            totalCells  = grid.totalCellCount();
            // Worst-case contents capacity: every segment painted into every cell
            // it overlaps, capped by BIN_DEPTH per cell. The totalCells * BIN_DEPTH
            // worst case is far larger than reality; cap at a sane multiplier of
            // segCap*8 (each segment lives in ~8 cells at this geometry).
            contentsCap = Math.min(totalCells * MotorBindGrid3D.BIN_DEPTH, segCap * 8);

            motPos     = new FloatArray(motorCap * 3);
            motUVec    = new FloatArray(motorCap * 3);
            motRodUVec = new FloatArray(motorCap * 3);
            motOnFil   = new IntArray(motorCap);

            filEnd1       = new FloatArray(segCap * 3);
            filEnd2       = new FloatArray(segCap * 3);
            filNodeAtEnd2 = new IntArray(segCap);

            gridCellOffsets  = new IntArray(totalCells + 1);
            gridCellContents = new IntArray(contentsCap);

            gridParams = new FloatArray(7);
            gridParams.set(0, grid.xMin);
            gridParams.set(1, grid.yMin);
            gridParams.set(2, grid.zMin);
            gridParams.set(3, MotorBindGrid3D.CELL_SIZE);
            gridParams.set(4, 1.0f / MotorBindGrid3D.CELL_SIZE);
            gridParams.set(5, (float) Env.myoMotorAlignWithFilTolerance.getValue());
            float myoColTol = (float) Env.myoColTol.getValue();
            gridParams.set(6, myoColTol * myoColTol);

            gridDims = new IntArray(4);
            gridDims.set(0, grid.nXBins);
            gridDims.set(1, grid.nYBins);
            gridDims.set(2, grid.nZBins);
            gridDims.set(3, totalCells);

            counts     = new IntArray(3);
            boundSegId = new IntArray(motorCap);

            TaskGraph tg = new TaskGraph("motorBinding")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, gridParams, gridDims)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                                  motPos, motUVec, motRodUVec, motOnFil,
                                  filEnd1, filEnd2, filNodeAtEnd2,
                                  gridCellOffsets, gridCellContents,
                                  counts)
                .task("bind",
                      GPUMotorBinding::bindKernel,
                      motPos, motUVec, motRodUVec, motOnFil,
                      filEnd1, filEnd2, filNodeAtEnd2,
                      gridCellOffsets, gridCellContents,
                      gridParams, gridDims,
                      counts, boundSegId)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, boundSegId);

            itg  = tg.snapshot();
            plan = new TornadoExecutionPlan(itg);

            // Bind a WorkerGrid with an explicit small workgroup so the PTX
            // launch has enough registers per thread. Global work = motorCap
            // (matches the @Parallel range of motPos.getSize()/3).
            WorkerGrid worker = new WorkerGrid1D(motorCap);
            worker.setLocalWork(BIND_KERNEL_BLOCK_SIZE, 1, 1);
            gridScheduler = new GridScheduler("motorBinding.bind", worker);

            System.out.printf("GPUMotorBinding: motorCap=%d segCap=%d totalCells=%d contentsCap=%d blockSize=%d%n",
                              motorCap, segCap, totalCells, contentsCap, BIND_KERNEL_BLOCK_SIZE);
        }

        // ---------- Pack motor + segment SoA (CPU double → FloatArray float) ----------
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

        // ---------- Pack grid CSR ----------
        // CPU FillThreads has already run for this step (BoxOfActin.doLoop()).
        // Walk the per-cell arrays into the flat CSR layout. Same cadence as
        // the CPU grid rebuild (every step) — the motor positions change every
        // step under integration, so a stale grid would mis-bin motors.
        long gridStart = System.nanoTime();
        int used = MotorBindGrid3D.INSTANCE.packForGPU(gridCellOffsets, gridCellContents);
        if (used > contentsCap) {
            System.err.println("[GPU] gridCellContents overflow: " + used + " > " + contentsCap);
        }
        long gridEnd = System.nanoTime();

        // ---------- Execute ----------
        plan.withGridScheduler(gridScheduler).execute();
        long execEnd = System.nanoTime();

        // ---------- Unpack ----------
        // Walk boundSegId serially on the CPU and fire ontoFilament for each hit.
        // The synchronized event semantics (attachSync, bindTimer gate) live
        // inside ontoFilament — unchanged from CPU step 1b.
        for (int i = 0; i < M; i++) {
            int segIdx = boundSegId.get(i);
            if (segIdx < 0) continue;
            if (segIdx >= S) continue;  // defensive guard
            FilSegment seg = FilSegment.theFilSegments[segIdx];
            if (seg == null) continue;
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

        packNanos     += packEnd   - packStart;
        gridPackNanos += gridEnd   - packEnd;
        execNanos     += execEnd   - gridEnd;
        unpackNanos   += unpackEnd - execEnd;
        totalNanos    += unpackEnd - t0;
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
        packNanos = gridPackNanos = execNanos = unpackNanos = totalNanos = 0;
        callCount = 0;
    }

    /** Diagnostic timing accessors — read by BoxOfActin at run end. */
    public static long getTotalNanos()    { return totalNanos;    }
    public static long getPackNanos()     { return packNanos;     }
    public static long getGridPackNanos() { return gridPackNanos; }
    public static long getExecNanos()     { return execNanos;     }
    public static long getUnpackNanos()   { return unpackNanos;   }
    public static int  getCallCount()     { return callCount;     }
}
