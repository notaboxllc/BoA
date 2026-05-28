package boxOfActin;

import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * GPU-accelerated motor-binding decision via TornadoVM — iteration 2.
 *
 * Iteration 1 (commit 8628946) shipped a brute-force single-plan spike that
 * showed the GPU 1.10× slower than CPU because the inner loop was O(M*S)
 * while CPU was already grid-accelerated. Iteration 2 ports the broad-phase
 * spatial grid to GPU: the CPU still builds MotorBindGrid3D each step, packs
 * it into a flat CSR layout (gridCellOffsets / gridCellContents), and uploads
 * once per step alongside the motor and segment SoA arrays. The kernel reads
 * the resident grid: each motor thread maps its position to a cell, walks the
 * 27-cell neighbourhood (own cell + 26 neighbours, NOT 26), and runs the
 * narrow-phase distance+orientation gate from iteration 1 against only the
 * segment IDs the grid emits.
 *
 * Brute-force is GONE. Iteration 1 served its purpose.
 *
 * Persistent residency (4.0.1-dev): single-graph plan. FloatArray/IntArray
 * device buffers survive across plan.execute() calls — they are not freed
 * between executions. Static arrays (gridParams, gridDims) use FIRST_EXECUTION
 * upload, so they only travel once. Dynamic arrays (motor SoA, seg SoA, grid
 * CSR, counts) use EVERY_EXECUTION upload because their CPU contents change
 * every step (motors and segments move under CPU integration; grid rebuilds
 * each step from the FillThreads pass). The output IntArray (boundSegId) uses
 * EVERY_EXECUTION transferToHost.
 *
 * The multi-task plan with persistOnDevice/consumeFromDevice exists in
 * 4.0.1-dev and would let us split the upload across graphs; we don't use it
 * here because every dynamic input changes every step, so it would only save
 * device-buffer re-binding overhead while doubling the number of execute()
 * calls. Single-graph wins on simplicity at this iteration's data shape.
 *
 * 15-param cap: TornadoVM Task1..Task15 — kernel uses 13 array parameters
 * (motPos/motUVec/motRodUVec, motOnFil, filEnd1/filEnd2, filNodeAtEnd2,
 * gridCellOffsets, gridCellContents, gridParams, gridDims, counts, boundSegId)
 * and 0 scalars; alignTol and myoColTolSq are packed into gridParams.
 */
public class GPUMotorBinding {

    /** Per-kernel Wang-hash salt for cross-kernel seed namespace isolation.
     *  Pattern: motorIdx * 1000003 + step * 999983 + KERNEL_ID * 7919.
     *  Slot reserved; this kernel still does not consume RNG. */
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
    private static IntArray   gridCellContents; // contentsCap (sum of per-cell active counts, capped)

    // Static grid params — FIRST_EXECUTION upload, packed FloatArray:
    //   [0]=xMin, [1]=yMin, [2]=zMin, [3]=cellSize, [4]=invCellSize,
    //   [5]=alignTol, [6]=myoColTolSq
    private static FloatArray gridParams;
    // Static grid dims — FIRST_EXECUTION: [nXBins, nYBins, nZBins, totalCells]
    private static IntArray   gridDims;

    // Counts: [0]=motorCt, [1]=segmentCt, [2]=stepCounter (reserved for future RNG seeding)
    private static IntArray   counts;

    // Per-motor output
    private static IntArray   boundSegId;

    private static ImmutableTaskGraph   itg;
    private static TornadoExecutionPlan plan;

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
    //      orientation gate + sphere-line distance test from iteration 1.
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
            // it overlaps, capped by BIN_DEPTH per cell. Use the same per-cell cap
            // as MotorBindGrid3D.BIN_DEPTH and budget conservatively. For the
            // gliding-assay box at 0.2 µm cells, totalCells * BIN_DEPTH is far
            // larger than reality; cap at a sane multiplier of segCap*8.
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

            System.out.printf("GPUMotorBinding: motorCap=%d segCap=%d totalCells=%d contentsCap=%d%n",
                              motorCap, segCap, totalCells, contentsCap);
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
        // Walk the per-cell arrays into the flat CSR layout.
        long gridStart = System.nanoTime();
        int used = MotorBindGrid3D.INSTANCE.packForGPU(gridCellOffsets, gridCellContents);
        if (used > contentsCap) {
            // Should never happen — packForGPU truncates writes implicitly via
            // gridCellContents.set(); the offsets array gets out-of-sync though.
            System.err.println("[GPU] gridCellContents overflow: " + used + " > " + contentsCap);
        }
        long gridEnd = System.nanoTime();

        // ---------- Execute ----------
        plan.execute();
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
        gridPackNanos += gridEnd   - gridStart;
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
