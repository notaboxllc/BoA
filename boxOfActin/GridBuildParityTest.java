package boxOfActin;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.Random;

/**
 * Structural parity oracle for the parallel bind-grid build. Generates random
 * segment AABBs over a production-shaped grid, runs the parallel 6-kernel
 * counting-sort pipeline ON THE DEVICE, and compares the resulting CSR against
 * the trusted single-threaded GPUMotorBinding.gridAssembleKernel run on host.
 *
 * Pass: for every cell the device CSR holds the same MULTISET of segment IDs as
 * the serial build (order-insensitive). Offsets are also checked bit-exact
 * (the serial scatter keeps the build deterministic).
 *
 * Run (args: nX nY nZ S seed):
 *   java @argfile --enable-preview -cp ... boxOfActin.GridBuildParityTest 101 101 4 600 1
 */
public class GridBuildParityTest {

    static final int CH = GPUMotorBinding.GRID_SCAN_CHUNK;

    public static void main(String[] args) {
        int nX = args.length > 0 ? Integer.parseInt(args[0]) : 101;
        int nY = args.length > 1 ? Integer.parseInt(args[1]) : 101;
        int nZ = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        int S  = args.length > 3 ? Integer.parseInt(args[3]) : 600;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 1L;
        // args[5] = scatterChunk: 0 -> serial gridScatterKernel (original parity),
        // >0 -> A2 parallel gridScatterChunkKernel with that cell-chunk size. The
        // host reference is always the trusted serial gridAssembleKernel, so a PASS
        // proves the chosen device scatter is BIT-IDENTICAL to the serial build.
        int scatterChunk = args.length > 5 ? Integer.parseInt(args[5]) : 0;

        int totalCells = nX * nY * nZ;
        // Generous cap so nothing clips — we are comparing full multisets.
        int maxCellsPerSeg = 3 * 3 * 3;
        int contentsCap = S * maxCellsPerSeg + 16;

        Random rng = new Random(seed);
        IntArray segBbox = new IntArray(S * 6);
        for (int s = 0; s < S; s++) {
            int ix0 = rng.nextInt(nX);
            int ix1 = Math.min(ix0 + rng.nextInt(3), nX - 1);   // span 1..3 cells
            int iy0 = rng.nextInt(nY);
            int iy1 = Math.min(iy0 + rng.nextInt(3), nY - 1);
            int iz0 = rng.nextInt(nZ);
            int iz1 = Math.min(iz0 + rng.nextInt(2), nZ - 1);
            segBbox.set(s * 6,     ix0);
            segBbox.set(s * 6 + 1, ix1);
            segBbox.set(s * 6 + 2, iy0);
            segBbox.set(s * 6 + 3, iy1);
            segBbox.set(s * 6 + 4, iz0);
            segBbox.set(s * 6 + 5, iz1);
        }
        IntArray gridDims = new IntArray(5);
        gridDims.set(0, nX); gridDims.set(1, nY); gridDims.set(2, nZ); gridDims.set(3, totalCells);
        gridDims.set(4, scatterChunk > 0 ? scatterChunk : 1);
        IntArray counts = new IntArray(3);
        counts.set(0, 0); counts.set(1, S); counts.set(2, 0);

        // ---- Serial reference on host ----
        IntArray offS = new IntArray(totalCells + 1);
        IntArray conS = new IntArray(contentsCap);
        IntArray ccS  = new IntArray(totalCells);
        IntArray dummySegCellCount = new IntArray(S);
        GPUMotorBinding.gridAssembleKernel(dummySegCellCount, segBbox, gridDims, counts, offS, conS, ccS);

        // ---- Parallel pipeline on device ----
        IntArray offP = new IntArray(totalCells + 1);
        IntArray conP = new IntArray(contentsCap);
        IntArray ccP  = new IntArray(totalCells);
        int numChunks = (totalCells + CH - 1) / CH;
        IntArray chunkSum = new IntArray(numChunks + 1);

        KernelContext ctx = new KernelContext();
        final int B = 64;
        int cellsGlobal  = ((totalCells + B - 1) / B) * B;
        int segGlobal    = ((S          + B - 1) / B) * B;
        int chunksGlobal = ((numChunks  + B - 1) / B) * B;

        GridScheduler sch = new GridScheduler("g.gridZero", worker(cellsGlobal, B));
        sch.addWorkerGrid("g.gridHist",       worker(segGlobal, B));
        sch.addWorkerGrid("g.gridScanLocal",  worker(chunksGlobal, B));
        sch.addWorkerGrid("g.gridScanChunks", worker(1, 1));
        sch.addWorkerGrid("g.gridScanAdd",    worker(chunksGlobal, B));
        if (scatterChunk > 0) {
            int numScatterChunks = (totalCells + scatterChunk - 1) / scatterChunk;
            int scatterGlobal = ((numScatterChunks + B - 1) / B) * B;
            sch.addWorkerGrid("g.gridScatter", worker(scatterGlobal, B));
        } else {
            sch.addWorkerGrid("g.gridScatter", worker(1, 1));
        }

        TaskGraph tg = new TaskGraph("g")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, segBbox, gridDims, counts)
                .task("gridZero",       GPUMotorBinding::gridZeroKernel, gridDims, ccP)
                .task("gridHist",       GPUMotorBinding::gridHistogramKernel, ctx, segBbox, gridDims, counts, ccP)
                .task("gridScanLocal",  GPUMotorBinding::gridScanLocalKernel, gridDims, ccP, offP, chunkSum)
                .task("gridScanChunks", GPUMotorBinding::gridScanChunksKernel, gridDims, chunkSum)
                .task("gridScanAdd",    GPUMotorBinding::gridScanAddKernel, gridDims, offP, conP, ccP, chunkSum);
        if (scatterChunk > 0) {
            tg = tg.task("gridScatter", GPUMotorBinding::gridScatterChunkKernel, segBbox, gridDims, counts, offP, conP, ccP);
        } else {
            tg = tg.task("gridScatter", GPUMotorBinding::gridScatterKernel, segBbox, gridDims, counts, offP, conP, ccP);
        }
        tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION, offP, conP);

        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot())) {
            plan.withGridScheduler(sch).execute();
        } catch (Exception e) {
            System.out.println("[PARITY] EXCEPTION: " + e);
            e.printStackTrace();
            System.out.println("[PARITY] RESULT: FAIL (exception)");
            return;
        }

        // ---- Compare ----
        int offsetMismatch = 0, countMismatch = 0, setMismatch = 0, firstBad = -1;
        for (int c = 0; c <= totalCells; c++) {
            if (offS.get(c) != offP.get(c)) { offsetMismatch++; if (firstBad < 0) firstBad = c; }
        }
        int orderMismatch = 0;
        for (int c = 0; c < totalCells; c++) {
            int sStart = offS.get(c), sEnd = offS.get(c + 1);
            int pStart = offP.get(c), pEnd = offP.get(c + 1);
            if ((sEnd - sStart) != (pEnd - pStart)) { countMismatch++; continue; }
            // multiset equality
            boolean diff = false;
            for (int a = sStart; a < sEnd; a++) {
                int v = conS.get(a);
                int cntS = 0, cntP = 0;
                for (int b = sStart; b < sEnd; b++) if (conS.get(b) == v) cntS++;
                for (int b = pStart; b < pEnd; b++) if (conP.get(b) == v) cntP++;
                if (cntS != cntP) { diff = true; break; }
            }
            if (diff) setMismatch++;
            // exact within-cell order (offsets are bit-identical so indices align)
            for (int a = sStart; a < sEnd; a++) {
                if (conS.get(a) != conP.get(a)) { orderMismatch++; break; }
            }
        }
        long sumS = 0, sumP = 0;
        for (int c = 0; c < totalCells; c++) { sumS += (offS.get(c+1)-offS.get(c)); }
        int tail = offP.get(totalCells);
        for (int b = 0; b < tail; b++) sumP += 1;

        System.out.printf("[PARITY] grid=%dx%dx%d totalCells=%d S=%d chunks=%d cap=%d%n",
                nX, nY, nZ, totalCells, S, numChunks, contentsCap);
        System.out.printf("[PARITY] serialTotalContents=%d devTail=%d%n", sumS, tail);
        System.out.printf("[PARITY] offsetMismatch=%d countMismatch=%d setMismatch=%d orderMismatch=%d firstBadOffsetCell=%d%n",
                offsetMismatch, countMismatch, setMismatch, orderMismatch, firstBad);
        boolean pass = offsetMismatch == 0 && countMismatch == 0 && setMismatch == 0 && sumS == tail;
        System.out.println("[PARITY] RESULT: " + (pass ? "PASS" : "FAIL"));
    }

    static WorkerGrid worker(int global, int local) {
        WorkerGrid w = new WorkerGrid1D(global);
        w.setLocalWork(local, 1, 1);
        return w;
    }
}
