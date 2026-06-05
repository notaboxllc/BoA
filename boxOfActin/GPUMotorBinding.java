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

    // Phase 3 — device-resident grid build scratch.
    // segBbox: per-segment AABB in cell indices, packed as [ix0,ix1,iy0,iy1,iz0,iz1].
    // segCellCount: per-segment AABB cell count (= (ix1-ix0+1)*(iy1-iy0+1)*(iz1-iz0+1)).
    // cellCount: per-cell working buffer; used by gridAssembleKernel for the
    //   histogram pass and reused as a per-cell write-pointer during scatter.
    private static IntArray   segBbox;          // segCap*6
    private static IntArray   segCellCount;     // segCap
    private static IntArray   cellCount;        // totalCells

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
    // Phase 3 — per-motor arcOnFil emitted from bindKernel (= alpha * sqrt(denom)
    // in float32). When boundSegId[m] == -1 the value is meaningless.
    private static FloatArray arcOnFilDev;

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
    // Phase 3 — segBbox kernel block size. Register-light (six float reads,
    // six int writes, a handful of comparisons) so 128 fits comfortably.
    private static final int SEGBBOX_KERNEL_BLOCK_SIZE = 128;

    // Timing accumulators
    private static long packNanos       = 0;
    private static long gridPackNanos   = 0;
    private static long execNanos       = 0;
    private static long unpackNanos     = 0;
    private static long totalNanos      = 0;
    private static int  callCount       = 0;

    // -------------------------------------------------------------------------
    // Phase 3 — device-resident grid build, step 1: per-segment AABB.
    //
    // One thread per segment. Reads the segment's endpoints (filEnd1, filEnd2 —
    // already device-resident as the bindKernel's inputs) and computes the
    // endpoint AABB cells (ix0..ix1, iy0..iy1, iz0..iz1). Writes the bbox to
    // segBbox[s*6..s*6+5] and the cell count = (ix1-ix0+1)*(iy1-iy0+1)*(iz1-iz0+1)
    // to segCellCount[s]. Replicates MotorBindGrid3D.fillFilSeg's bbox shape
    // and getBinX/Y/Z's clamp pattern exactly.
    // -------------------------------------------------------------------------
    private static void segBboxKernel(
            FloatArray filEnd1,
            FloatArray filEnd2,
            FloatArray gridParams,
            IntArray   gridDims,
            IntArray   counts,
            IntArray   segCellCount,
            IntArray   segBbox) {

        int S = counts.get(1);

        float xMin        = gridParams.get(0);
        float yMin        = gridParams.get(1);
        float zMin        = gridParams.get(2);
        float invCellSize = gridParams.get(4);

        int nXBins = gridDims.get(0);
        int nYBins = gridDims.get(1);
        int nZBins = gridDims.get(2);

        for (@Parallel int s = 0; s < filEnd1.getSize() / 3; s++) {
            if (s >= S) {
                segCellCount.set(s, 0);
                continue;
            }
            float e1x = filEnd1.get(s * 3);
            float e1y = filEnd1.get(s * 3 + 1);
            float e1z = filEnd1.get(s * 3 + 2);
            float e2x = filEnd2.get(s * 3);
            float e2y = filEnd2.get(s * 3 + 1);
            float e2z = filEnd2.get(s * 3 + 2);

            float xLo = e1x < e2x ? e1x : e2x;
            float xHi = e1x > e2x ? e1x : e2x;
            float yLo = e1y < e2y ? e1y : e2y;
            float yHi = e1y > e2y ? e1y : e2y;
            float zLo = e1z < e2z ? e1z : e2z;
            float zHi = e1z > e2z ? e1z : e2z;

            int ix0 = (int) ((xLo - xMin) * invCellSize);
            int ix1 = (int) ((xHi - xMin) * invCellSize);
            int iy0 = (int) ((yLo - yMin) * invCellSize);
            int iy1 = (int) ((yHi - yMin) * invCellSize);
            int iz0 = (int) ((zLo - zMin) * invCellSize);
            int iz1 = (int) ((zHi - zMin) * invCellSize);

            if (ix0 < 0)        ix0 = 0;
            if (ix0 >= nXBins)  ix0 = nXBins - 1;
            if (ix1 < 0)        ix1 = 0;
            if (ix1 >= nXBins)  ix1 = nXBins - 1;
            if (iy0 < 0)        iy0 = 0;
            if (iy0 >= nYBins)  iy0 = nYBins - 1;
            if (iy1 < 0)        iy1 = 0;
            if (iy1 >= nYBins)  iy1 = nYBins - 1;
            if (iz0 < 0)        iz0 = 0;
            if (iz0 >= nZBins)  iz0 = nZBins - 1;
            if (iz1 < 0)        iz1 = 0;
            if (iz1 >= nZBins)  iz1 = nZBins - 1;

            segBbox.set(s * 6,     ix0);
            segBbox.set(s * 6 + 1, ix1);
            segBbox.set(s * 6 + 2, iy0);
            segBbox.set(s * 6 + 3, iy1);
            segBbox.set(s * 6 + 4, iz0);
            segBbox.set(s * 6 + 5, iz1);

            int cc = (ix1 - ix0 + 1) * (iy1 - iy0 + 1) * (iz1 - iz0 + 1);
            segCellCount.set(s, cc);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 3 — device-resident grid build, step 2: CSR assembly.
    //
    // Single-threaded serial kernel (@Parallel range of 1) reading the per-
    // segment AABBs from segBbox/segCellCount and assembling the CSR layout in
    // gridCellOffsets / gridCellContents. Reproduces MotorBindGrid3D.packForGPU's
    // contract exactly:
    //   - gridCellOffsets[N] = start of cell N's contents in gridCellContents
    //     (length totalCells+1; offsets[totalCells] = total content length, capped)
    //   - gridCellContents = flat array of seg IDs; per-cell set equal to the
    //     CPU-built layout (within-cell ordering need not match)
    //   - linear cellId = ix + iy*nXBins + iz*nXBins*nYBins
    //
    // Three passes:
    //   1. Zero cellCount[totalCells].
    //   2. Histogram: for each seg, walk its AABB, ++cellCount[cellId].
    //   3. Exclusive prefix-sum cellCount → gridCellOffsets; reset cellCount to
    //      zero (reused as per-cell write pointer).
    //   4. Scatter: for each seg, walk its AABB, write segId at
    //      gridCellContents[offsets[cellId] + cellFill[cellId]++].
    //
    // At gliding-assay scale S≈500 and totalCells≈100k–200k, so the dominant
    // single-thread cost is the prefix-sum scan over totalCells (~100µs on PTX).
    // Acceptable for Phase 3; Phase 4 may parallelise the scan via @Reduce/
    // KernelContext if it becomes a bottleneck.
    //
    // contentsCap is recovered at runtime via gridCellContents.getSize().
    // -------------------------------------------------------------------------
    private static void gridAssembleKernel(
            IntArray segCellCount,
            IntArray segBbox,
            IntArray gridDims,
            IntArray counts,
            IntArray gridCellOffsets,
            IntArray gridCellContents,
            IntArray cellCount) {

        for (@Parallel int gid = 0; gid < 1; gid++) {
            int S          = counts.get(1);
            int nXBins     = gridDims.get(0);
            int nYBins     = gridDims.get(1);
            int totalCells = gridDims.get(3);
            int nXY        = nXBins * nYBins;
            int contentsCap = gridCellContents.getSize();

            // 1. Zero cellCount
            for (int c = 0; c < totalCells; c++) {
                cellCount.set(c, 0);
            }

            // 2. Histogram
            for (int s = 0; s < S; s++) {
                int ix0 = segBbox.get(s * 6);
                int ix1 = segBbox.get(s * 6 + 1);
                int iy0 = segBbox.get(s * 6 + 2);
                int iy1 = segBbox.get(s * 6 + 3);
                int iz0 = segBbox.get(s * 6 + 4);
                int iz1 = segBbox.get(s * 6 + 5);
                for (int iz = iz0; iz <= iz1; iz++) {
                    int izOff = iz * nXY;
                    for (int iy = iy0; iy <= iy1; iy++) {
                        int iyOff = iy * nXBins;
                        for (int ix = ix0; ix <= ix1; ix++) {
                            int cellId = ix + iyOff + izOff;
                            cellCount.set(cellId, cellCount.get(cellId) + 1);
                        }
                    }
                }
            }

            // 3. Exclusive prefix-sum → gridCellOffsets; reset cellCount to 0
            int acc = 0;
            for (int c = 0; c < totalCells; c++) {
                gridCellOffsets.set(c, acc);
                acc += cellCount.get(c);
                cellCount.set(c, 0);
            }
            int finalOff = acc < contentsCap ? acc : contentsCap;
            gridCellOffsets.set(totalCells, finalOff);

            // 4. Scatter
            for (int s = 0; s < S; s++) {
                int ix0 = segBbox.get(s * 6);
                int ix1 = segBbox.get(s * 6 + 1);
                int iy0 = segBbox.get(s * 6 + 2);
                int iy1 = segBbox.get(s * 6 + 3);
                int iz0 = segBbox.get(s * 6 + 4);
                int iz1 = segBbox.get(s * 6 + 5);
                for (int iz = iz0; iz <= iz1; iz++) {
                    int izOff = iz * nXY;
                    for (int iy = iy0; iy <= iy1; iy++) {
                        int iyOff = iy * nXBins;
                        for (int ix = ix0; ix <= ix1; ix++) {
                            int cellId = ix + iyOff + izOff;
                            int writePos = gridCellOffsets.get(cellId) + cellCount.get(cellId);
                            if (writePos < contentsCap) {
                                gridCellContents.set(writePos, s);
                            }
                            cellCount.set(cellId, cellCount.get(cellId) + 1);
                        }
                    }
                }
            }
        }
    }

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
            IntArray   boundSegId,
            FloatArray arcOnFilDev) {

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
            arcOnFilDev.set(m, 0.0f);
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
                                // Phase 3: emit arcOnFil = alpha * sqrt(denom).
                                // Matches the CPU formula in MyoMotor.checkFilSegCollision:
                                //   arcOnFil = alpha * Math.sqrt(denom)
                                // Float32 here; CPU path is double — sub-1e-7 divergence
                                // is the expected float-vs-double surface (see CP2).
                                arcOnFilDev.set(m, alpha * (float) Math.sqrt(denom));
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

            // Phase 3 — device-resident grid build scratch (allocated once;
            // refilled each step inside the chained TaskGraph; never touched
            // by the host on the per-step path).
            segBbox      = new IntArray(segCap * 6);
            segCellCount = new IntArray(segCap);
            cellCount    = new IntArray(totalCells);

            arcOnFilDev  = new FloatArray(motorCap);

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

            // Phase 3 TaskGraph: device-resident grid build chained ahead of
            // bindKernel. gridCellOffsets / gridCellContents / segBbox /
            // segCellCount / cellCount / arcOnFilDev are NOT in transferToDevice
            // (they are populated entirely on device by segBbox + gridAssemble
            // kernels; arcOnFilDev is written by bindKernel). gridCellOffsets and
            // gridCellContents ARE in transferToHost so that the Phase-3 CP1
            // checkpoint can read them back for set-equality comparison against
            // the CPU CSR; the per-step download cost is ~500 KB (mostly empty
            // cells) and will be removed at the Phase 4 flip.
            TaskGraph tg = new TaskGraph("motorBinding")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, gridParams, gridDims)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                                  motPos, motUVec, motRodUVec, motOnFil,
                                  filEnd1, filEnd2, filNodeAtEnd2,
                                  counts)
                .task("segBbox",
                      GPUMotorBinding::segBboxKernel,
                      filEnd1, filEnd2,
                      gridParams, gridDims, counts,
                      segCellCount, segBbox)
                .task("gridAssemble",
                      GPUMotorBinding::gridAssembleKernel,
                      segCellCount, segBbox,
                      gridDims, counts,
                      gridCellOffsets, gridCellContents, cellCount)
                .task("bind",
                      GPUMotorBinding::bindKernel,
                      motPos, motUVec, motRodUVec, motOnFil,
                      filEnd1, filEnd2, filNodeAtEnd2,
                      gridCellOffsets, gridCellContents,
                      gridParams, gridDims,
                      counts, boundSegId, arcOnFilDev)
                .transferToHost(DataTransferMode.EVERY_EXECUTION,
                                boundSegId, arcOnFilDev,
                                gridCellOffsets, gridCellContents);

            itg  = tg.snapshot();
            plan = new TornadoExecutionPlan(itg);

            // Per-task WorkerGrids — bind keeps its small block to fit register
            // pressure; segBbox uses a larger block (register-light). The
            // gridAssemble task is single-threaded (forced via @Parallel(0..1));
            // omitting it from the scheduler lets TornadoVM apply default
            // 1-thread launch from the @Parallel range.
            WorkerGrid bindWorker = new WorkerGrid1D(motorCap);
            bindWorker.setLocalWork(BIND_KERNEL_BLOCK_SIZE, 1, 1);
            WorkerGrid segBboxWorker = new WorkerGrid1D(segCap);
            segBboxWorker.setLocalWork(SEGBBOX_KERNEL_BLOCK_SIZE, 1, 1);
            gridScheduler = new GridScheduler("motorBinding.bind",    bindWorker);
            gridScheduler.addWorkerGrid("motorBinding.segBbox", segBboxWorker);

            System.out.printf("GPUMotorBinding: motorCap=%d segCap=%d totalCells=%d contentsCap=%d bindBlk=%d segBboxBlk=%d%n",
                              motorCap, segCap, totalCells, contentsCap,
                              BIND_KERNEL_BLOCK_SIZE, SEGBBOX_KERNEL_BLOCK_SIZE);
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

        // ---------- Grid build now runs on device ----------
        // Phase 3: the segBbox + gridAssemble kernels (chained ahead of bind
        // inside the same TaskGraph) populate gridCellOffsets / gridCellContents
        // directly on the device, reading the same filEnd1/filEnd2 buffers the
        // bind kernel uses. The CPU MotorBindGrid3D.FillThreads + packForGPU
        // pair are no longer on the per-step path (FillThreads is still wired
        // in BoxOfActin's tSets but should be skipped by the caller on the
        // GPU device-grid path — see BoxOfActin.doLoop gate). For Phase 3 the
        // CSR is still transferred back to the host for the CP1 checkpoint
        // path; Phase 4 will drop that.
        long gridStart = System.nanoTime();
        long gridEnd   = gridStart;

        // ---------- Execute ----------
        plan.withGridScheduler(gridScheduler).execute();
        long execEnd = System.nanoTime();

        // ---------- Unpack ----------
        // Walk boundSegId serially on the CPU and fire ontoFilament for each hit.
        // arcOnFil is now emitted by bindKernel into arcOnFilDev (float32) —
        // no CPU recompute. The synchronized event semantics (attachSync,
        // bindTimer gate) live inside ontoFilament — unchanged from CPU step 1b.
        for (int i = 0; i < M; i++) {
            int segIdx = boundSegId.get(i);
            if (segIdx < 0) continue;
            if (segIdx >= S) continue;  // defensive guard
            FilSegment seg = FilSegment.theFilSegments[segIdx];
            if (seg == null) continue;
            double arcOnFil = arcOnFilDev.get(i);
            MyoMotor.theMotors[i].ontoFilament(seg, arcOnFil);
        }
        long unpackEnd = System.nanoTime();

        packNanos     += packEnd   - packStart;
        gridPackNanos += gridEnd   - packEnd;     // 0 — grid build is on device
        execNanos     += execEnd   - gridEnd;
        unpackNanos   += unpackEnd - execEnd;
        totalNanos    += unpackEnd - t0;
        callCount++;

        // Phase 3 — CP1 / CP2 diagnostic. Runs AFTER plan.execute() so
        // gridCellOffsets/gridCellContents and boundSegId/arcOnFilDev hold the
        // device's output on the SAME pose CPU is about to read for
        // comparison. Side-effects (ontoFilament) above already fired — the
        // diagnostic captures decisions, not state. Single-step (BOA_PHASE3_CP)
        // or accumulated across a window (BOA_PHASE3_CP_START / _STOP) — the
        // window mode aggregates arcOnFil deltas across hundreds of steps to
        // catch enough new-bind events for a meaningful sample (gliding-rotation
        // bind rate is only ~0.04 per step).
        if ((CP_STEP >= 0 && Env.counter == CP_STEP) ||
            (CP_START >= 0 && Env.counter >= CP_START && Env.counter <= CP_STOP)) {
            runCheckpoints(M, S);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 3 checkpoints — frozen-pose comparison of device vs CPU grid build
    // (CP1) and bind decisions + arcOnFil (CP2). Armed via env var:
    //   BOA_PHASE3_CP=<stepNumber>
    // Fires once on that step. Reports actual numbers (not pass/fail) per the
    // Phase 3 protocol — divergences are evidence, not verdicts.
    // -------------------------------------------------------------------------

    private static final int CP_STEP  = parseEnvInt("BOA_PHASE3_CP",       -1);
    private static final int CP_START = parseEnvInt("BOA_PHASE3_CP_START", -1);
    private static final int CP_STOP  = parseEnvInt("BOA_PHASE3_CP_STOP",  Integer.MAX_VALUE);

    // Window-mode aggregators for CP1 + CP2 — accumulated across CP_START..CP_STOP
    // and reported at the end of run via reportCheckpointSummary().
    static int    cp1StepsRan         = 0;
    static int    cp1CellsTotal       = 0;   // total cells inspected across window
    static int    cp1CountMismatches  = 0;   // total cells with count diff across window
    static int    cp1SetMismatches    = 0;   // total cells with set diff across window
    static int    cp1FirstDiffCellEver = -1;
    static int    cp2StepsRan         = 0;
    static int    cp2MotorScanned     = 0;
    static int    cp2DecisionDiffs    = 0;
    static int    cp2MatchedHits      = 0;
    static double cp2ArcDeltaMax      = 0.0;
    static double cp2ArcDeltaSum      = 0.0;
    // Phase 4 flip — Part D directionality fold-in (2026-06-05). The Phase-3
    // CP recorded only |cpu - dev|; the signed mean is what disambiguates
    // zero-mean float32 noise from a directional bias. Track signed deltas
    // and the negative/positive split alongside the absolute statistics.
    static double cp2SignedArcDeltaSum = 0.0;
    static int    cp2PositiveArcDelta  = 0;
    static int    cp2NegativeArcDelta  = 0;
    static int    cp2ZeroArcDelta      = 0;

    private static int parseEnvInt(String name, int defVal) {
        String s = System.getenv(name);
        if (s == null) return defVal;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defVal; }
    }

    private static void runCheckpoints(int M, int S) {
        if (CP_STEP >= 0) {
            // single-step verbose path (legacy)
            System.err.printf("[PHASE3_CP step=%d] running CP1 + CP2%n", Env.counter);
            runCP1(S, true);
            runCP2(M, S, true);
            System.err.println("[PHASE3_CP] done");
        } else {
            // window mode — accumulate, suppress per-step printing
            runCP1(S, false);
            runCP2(M, S, false);
        }
    }

    /** Print the accumulated window-mode CP1 + CP2 summary. Called from
     *  BoxOfActin's end-of-run printout. */
    public static void reportCheckpointSummary() {
        if (cp1StepsRan == 0 && cp2StepsRan == 0) return;
        double meanArcDelta = cp2MatchedHits > 0 ? cp2ArcDeltaSum / cp2MatchedHits : 0.0;
        System.err.printf("[PHASE3_CP_SUMMARY] CP1 steps=%d cellsInspected=%d countMismatchCells=%d setMismatchCells=%d firstDiffCell=%d%n",
                          cp1StepsRan, cp1CellsTotal, cp1CountMismatches, cp1SetMismatches, cp1FirstDiffCellEver);
        System.err.printf("[PHASE3_CP_SUMMARY] CP2 steps=%d motorScanned=%d decisionDiffs=%d matchedHits=%d arcMaxDelta=%.3e arcMeanDelta=%.3e%n",
                          cp2StepsRan, cp2MotorScanned, cp2DecisionDiffs, cp2MatchedHits,
                          cp2ArcDeltaMax, meanArcDelta);
        // Phase 4 — Part D fold-in: signed mean + sign split. Disambiguates
        // zero-mean float32 noise (signed mean ~ 0, balanced +/- counts) from
        // a directional bias (signed mean nonzero, lopsided count).
        double meanSignedArcDelta = cp2MatchedHits > 0
            ? cp2SignedArcDeltaSum / cp2MatchedHits : 0.0;
        System.err.printf("[PHASE3_CP_SUMMARY] CP2 directionality: arcSignedMean=%+.3e neg=%d pos=%d zero=%d (signedSum=%+.3e)%n",
                          meanSignedArcDelta,
                          cp2NegativeArcDelta, cp2PositiveArcDelta, cp2ZeroArcDelta,
                          cp2SignedArcDeltaSum);
    }

    private static void runCP1(int S, boolean verbose) {
        // Repopulate the CPU MotorBindGrid3D on the same frozen pose the
        // device kernel just consumed. The GPU doLoop gate skipped the CPU
        // FillThreads this step, so the per-cell arrays + timestamps are
        // stale; stamp lastWriteTime = Env.counter and replay fillFilSeg
        // serially.
        MotorBindGrid3D grid = MotorBindGrid3D.INSTANCE;
        MotorBindGrid3D.lastWriteTime = Env.counter;
        for (int i = 0; i < S; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null) continue;
            grid.fillFilSeg(fs.filArrayPos, fs.end1AsPt3D(), fs.end2AsPt3D());
        }

        int totalCellsLocal  = grid.totalCellCount();
        int contentsCapLocal = gridCellContents.getSize();
        IntArray cpuOffsets  = new IntArray(totalCellsLocal + 1);
        IntArray cpuContents = new IntArray(contentsCapLocal);
        int cpuUsed = grid.packForGPU(cpuOffsets, cpuContents);
        int devUsedTail = gridCellOffsets.get(totalCellsLocal);

        int cellsCountMismatch = 0;
        int cellsSetMismatch   = 0;
        int firstCountDiff     = -1;
        int firstSetDiff       = -1;
        int reported           = 0;
        for (int c = 0; c < totalCellsLocal; c++) {
            int cpuStart = cpuOffsets.get(c);
            int cpuEnd   = cpuOffsets.get(c + 1);
            int devStart = gridCellOffsets.get(c);
            int devEnd   = gridCellOffsets.get(c + 1);
            int cpuCount = cpuEnd - cpuStart;
            int devCount = devEnd - devStart;
            if (cpuCount != devCount) {
                if (firstCountDiff < 0) firstCountDiff = c;
                cellsCountMismatch++;
                if (reported < 8) {
                    System.err.printf("[PHASE3_CP1] cell %d count differs: cpu=%d dev=%d%n",
                                      c, cpuCount, devCount);
                    reported++;
                }
                continue;
            }
            // Cell-set equality (small per-cell counts; O(n²) ok).
            boolean setDiff = false;
            for (int a = cpuStart; a < cpuEnd; a++) {
                int v = cpuContents.get(a);
                boolean found = false;
                for (int b = devStart; b < devEnd; b++) {
                    if (gridCellContents.get(b) == v) { found = true; break; }
                }
                if (!found) { setDiff = true; break; }
            }
            if (setDiff) {
                if (firstSetDiff < 0) firstSetDiff = c;
                cellsSetMismatch++;
                if (reported < 8) {
                    StringBuilder sbC = new StringBuilder(), sbD = new StringBuilder();
                    for (int a = cpuStart; a < cpuEnd; a++) sbC.append(cpuContents.get(a)).append(',');
                    for (int b = devStart; b < devEnd; b++) sbD.append(gridCellContents.get(b)).append(',');
                    System.err.printf("[PHASE3_CP1] cell %d set differs: cpu=[%s] dev=[%s]%n",
                                      c, sbC, sbD);
                    reported++;
                }
            }
        }
        cp1StepsRan++;
        cp1CellsTotal      += totalCellsLocal;
        cp1CountMismatches += cellsCountMismatch;
        cp1SetMismatches   += cellsSetMismatch;
        if (cp1FirstDiffCellEver < 0 && (firstCountDiff >= 0 || firstSetDiff >= 0)) {
            cp1FirstDiffCellEver = firstCountDiff >= 0 ? firstCountDiff : firstSetDiff;
        }
        if (verbose) {
            System.err.printf("[PHASE3_CP1] totalCells=%d cpuUsed=%d devUsedTail=%d countMismatchCells=%d setMismatchCells=%d firstCountDiff=%d firstSetDiff=%d%n",
                              totalCellsLocal, cpuUsed, devUsedTail,
                              cellsCountMismatch, cellsSetMismatch,
                              firstCountDiff, firstSetDiff);
        }
    }

    private static void runCP2(int M, int S, boolean verbose) {
        // For each motor, replay the CPU bind decision logic (without firing
        // ontoFilament — the device already fired its decision above). This
        // mirrors MotorBindGrid3D.motorFilCollisions + MyoMotor.checkFilSegCollision,
        // but in (dx,dy,dz) cell-walk order (matching the CPU production path).
        // Compares (segId, arcOnFil) against device boundSegId / arcOnFilDev.
        // Disagreements on segId are expected as float-near-tie effects; the
        // diagnostic reports counts plus per-disagreement distSq for both
        // candidates so the near-tie hypothesis can be verified.
        MotorBindGrid3D grid = MotorBindGrid3D.INSTANCE;
        int decisionDiffs = 0;
        int matchedHits   = 0;
        double maxArcDelta = 0.0;
        double sumArcDelta = 0.0;
        int reported = 0;

        double alignTol  = Env.myoMotorAlignWithFilTolerance.getValue();
        double myoColTol = Env.myoColTol.getValue();
        double myoColTolSq = myoColTol * myoColTol;

        for (int i = 0; i < M; i++) {
            if (MyoMotor.soaOnFil[i]) continue;
            int devSegId = boundSegId.get(i);
            double devArc = arcOnFilDev.get(i);

            // CPU walk: same order as MotorBindGrid3D.motorFilCollisions.
            double mx = MyoMotor.soaX[i],  my = MyoMotor.soaY[i],  mz = MyoMotor.soaZ[i];
            double mux= MyoMotor.soaUX[i], muy= MyoMotor.soaUY[i], muz= MyoMotor.soaUZ[i];
            double rux= MyoMotor.soaRodUX[i], ruy= MyoMotor.soaRodUY[i], ruz= MyoMotor.soaRodUZ[i];
            int cx = grid.getBinX(mx);
            int cy = grid.getBinY(my);
            int cz = grid.getBinZ(mz);
            int cpuSegId = -1;
            double cpuArc = 0.0;
            outer:
            for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx;
                if (nx < 0 || nx >= grid.nXBins) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = cy + dy;
                    if (ny < 0 || ny >= grid.nYBins) continue;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = cz + dz;
                        if (nz < 0 || nz >= grid.nZBins) continue;
                        if (grid.filTimeStamps[nx][ny][nz] != MotorBindGrid3D.lastWriteTime) continue;
                        int ct = grid.filActiveCts[nx][ny][nz];
                        for (int k = 0; k < ct; k++) {
                            int segIdx = grid.filCells[nx][ny][nz][k];
                            if (segIdx < 0 || segIdx >= S) continue;
                            if (FilSegment.soaNodeAtEnd2[segIdx]) continue;
                            double fUx = FilSegment.soaUX[segIdx];
                            double fUy = FilSegment.soaUY[segIdx];
                            double fUz = FilSegment.soaUZ[segIdx];
                            double motDotFil = mux*fUx + muy*fUy + muz*fUz;
                            if (motDotFil < alignTol) continue;
                            double rodDotFil = rux*fUx + ruy*fUy + ruz*fUz;
                            if (rodDotFil < 0) continue;
                            double e1x = FilSegment.soaEnd1X[segIdx];
                            double e1y = FilSegment.soaEnd1Y[segIdx];
                            double e1z = FilSegment.soaEnd1Z[segIdx];
                            double r1x = FilSegment.soaEnd2X[segIdx] - e1x;
                            double r1y = FilSegment.soaEnd2Y[segIdx] - e1y;
                            double r1z = FilSegment.soaEnd2Z[segIdx] - e1z;
                            double r2x = mx - e1x, r2y = my - e1y, r2z = mz - e1z;
                            double numer = r2x*r1x + r2y*r1y + r2z*r1z;
                            double denom = r1x*r1x + r1y*r1y + r1z*r1z;
                            double alpha = numer/denom;
                            if (alpha < 0 || alpha > 1) continue;
                            double cpx = e1x + alpha*r1x;
                            double cpy = e1y + alpha*r1y;
                            double cpz = e1z + alpha*r1z;
                            double ddx = cpx - mx, ddy = cpy - my, ddz = cpz - mz;
                            double conDistSq = ddx*ddx + ddy*ddy + ddz*ddz;
                            if (conDistSq >= myoColTolSq) continue;
                            cpuSegId = segIdx;
                            cpuArc = alpha * Math.sqrt(denom);
                            break outer;
                        }
                    }
                }
            }

            if (cpuSegId == devSegId) {
                if (cpuSegId >= 0) {
                    double signedDelta = cpuArc - devArc;
                    double delta = Math.abs(signedDelta);
                    if (delta > maxArcDelta) maxArcDelta = delta;
                    sumArcDelta += delta;
                    // Phase 4 — Part D fold-in: signed delta + sign split.
                    cp2SignedArcDeltaSum += signedDelta;
                    if (signedDelta > 0)      cp2PositiveArcDelta++;
                    else if (signedDelta < 0) cp2NegativeArcDelta++;
                    else                       cp2ZeroArcDelta++;
                    matchedHits++;
                }
            } else {
                decisionDiffs++;
                if (reported < 12) {
                    // Probe near-tie: report conDistSq for whichever pair we
                    // have. cpuSegId and devSegId may each be -1.
                    double cpuDistSq = (cpuSegId >= 0) ? distSqMotorToSeg(i, cpuSegId) : -1;
                    double devDistSq = (devSegId >= 0) ? distSqMotorToSeg(i, devSegId) : -1;
                    System.err.printf("[PHASE3_CP2] motor %d: cpu=%d (distSq=%.3e) dev=%d (distSq=%.3e)%n",
                                      i, cpuSegId, cpuDistSq, devSegId, devDistSq);
                    reported++;
                }
            }
        }
        cp2StepsRan++;
        cp2MotorScanned  += M;
        cp2DecisionDiffs += decisionDiffs;
        cp2MatchedHits   += matchedHits;
        if (maxArcDelta > cp2ArcDeltaMax) cp2ArcDeltaMax = maxArcDelta;
        cp2ArcDeltaSum   += sumArcDelta;
        if (verbose) {
            double meanArcDelta = matchedHits > 0 ? sumArcDelta / matchedHits : 0.0;
            System.err.printf("[PHASE3_CP2] motors=%d decisionDiffs=%d matchedHits=%d arcMaxDelta=%.3e arcMeanDelta=%.3e (float-vs-double ~1e-7 expected)%n",
                              M, decisionDiffs, matchedHits, maxArcDelta, meanArcDelta);
        }
    }

    /** Compute conDistSq from motor i's bindTip to seg's segment line.
     *  Used by CP2 to report near-tie evidence. */
    private static double distSqMotorToSeg(int i, int segIdx) {
        double mx = MyoMotor.soaX[i], my = MyoMotor.soaY[i], mz = MyoMotor.soaZ[i];
        double e1x = FilSegment.soaEnd1X[segIdx], e1y = FilSegment.soaEnd1Y[segIdx], e1z = FilSegment.soaEnd1Z[segIdx];
        double r1x = FilSegment.soaEnd2X[segIdx] - e1x;
        double r1y = FilSegment.soaEnd2Y[segIdx] - e1y;
        double r1z = FilSegment.soaEnd2Z[segIdx] - e1z;
        double r2x = mx - e1x, r2y = my - e1y, r2z = mz - e1z;
        double denom = r1x*r1x + r1y*r1y + r1z*r1z;
        double alpha = (r2x*r1x + r2y*r1y + r2z*r1z) / denom;
        if (alpha < 0) alpha = 0; else if (alpha > 1) alpha = 1;
        double cpx = e1x + alpha*r1x, cpy = e1y + alpha*r1y, cpz = e1z + alpha*r1z;
        double ddx = cpx - mx, ddy = cpy - my, ddz = cpz - mz;
        return ddx*ddx + ddy*ddy + ddz*ddz;
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
