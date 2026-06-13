import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Step-1 atomics gate. N threads atomic-add into a small IntArray (high
 * contention: every thread hits one of BINS bins). Verifies the sum and the
 * exact per-bin counts against the serial expectation. If atomicAdd is broken
 * on PTX the bins under-count (lost updates).
 */
public class AtomicProbe {

    static void histKernel(KernelContext ctx, IntArray bins, int nBins, int nThreads) {
        int tid = ctx.globalIdx;
        if (tid < nThreads) {
            ctx.atomicAdd(bins, tid % nBins, 1);
        }
    }

    public static void main(String[] args) {
        int nThreads = 1_048_576;   // 4096*256 exact
        int nBins    = 8;           // heavy contention: 125k adds per bin
        int block    = 256;

        IntArray bins = new IntArray(nBins);
        for (int i = 0; i < nBins; i++) bins.set(i, 0);

        KernelContext ctx = new KernelContext();
        WorkerGrid worker = new WorkerGrid1D(nThreads);
        worker.setGlobalWork(nThreads, 1, 1);
        worker.setLocalWork(block, 1, 1);
        GridScheduler scheduler = new GridScheduler("probe.hist", worker);

        TaskGraph tg = new TaskGraph("probe")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, bins)
                .task("hist", AtomicProbe::histKernel, ctx, bins, nBins, nThreads)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, bins);

        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot())) {
            plan.withGridScheduler(scheduler).execute();
        } catch (Exception e) {
            System.out.println("[GATE] EXCEPTION during execute: " + e);
            e.printStackTrace();
            System.out.println("[GATE] RESULT: FAIL (exception)");
            return;
        }

        long sum = 0;
        boolean perBinOk = true;
        int expectPerBin = nThreads / nBins;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nBins; i++) {
            int v = bins.get(i);
            sum += v;
            sb.append("bin[").append(i).append("]=").append(v)
              .append(v == expectPerBin ? " ok" : " MISMATCH(exp " + expectPerBin + ")").append("  ");
            if (v != expectPerBin) perBinOk = false;
        }
        System.out.println("[GATE] " + sb);
        System.out.println("[GATE] sum=" + sum + " expected=" + nThreads
                + (sum == nThreads ? " (sum OK)" : " (SUM MISMATCH — lost updates)"));
        System.out.println("[GATE] RESULT: " + ((sum == nThreads && perBinOk) ? "PASS" : "FAIL"));
    }
}
