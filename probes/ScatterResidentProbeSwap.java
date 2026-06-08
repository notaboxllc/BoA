/*
 * ScatterResidentProbeSwap — TornadoVM 4.0.1-dev feasibility probe.
 *
 * Question (from RESIDENT_POSE_DELTA_SCATTER.md):
 *   Can a FIRST_EXECUTION resident FloatArray be, within ONE task graph,
 *   across many .execute() calls:
 *     (a) uploaded once,
 *     (b) written by a kernel each execute,
 *     (c) ALSO written by a scatter kernel from small EVERY_EXECUTION host deltas,
 *     (d) persist across executes with no wholesale re-upload?
 *
 * Phase 4 proved (a)+(b)+(d) for coord/uVec/yVec; the new part is (c) coexisting.
 *
 * Probe shape:
 *   pose     : FloatArray[1000], FIRST_EXECUTION in + UNDER_DEMAND out.
 *   deltaVal : FloatArray[16],   EVERY_EXECUTION in.
 *   deltaIdx : IntArray[16],     EVERY_EXECUTION in.
 *   count    : IntArray[1],      EVERY_EXECUTION in.
 *
 *   Task 1 (scatter)   : for i<count: pose[deltaIdx[i]] = deltaVal[i]
 *   Task 2 (integrate) : for k<1000:  pose[k] += 1.0f
 *
 *   200 executes. Mostly count=0. On execute index 50 only:
 *     count=1, deltaIdx[0]=5, deltaVal[0]=42.0.
 *   After: UNDER_DEMAND read pose, inspect pose[10] and pose[5].
 *
 *   Then a timing pass: 1000 executes count=0 vs 1000 count=1, avg wall ns.
 */
public class ScatterResidentProbeSwap {

    static final int POSE_SIZE   = 1000;
    static final int DELTA_CAP   = 16;
    static final int EXECUTES    = 200;
    static final int SCATTER_AT  = 50;
    static final int SCATTER_IDX = 5;
    static final float SCATTER_VAL = 42.0f;
    static final int RESIDENCY_PROBE_IDX = 10;
    static final int TIMING_EXECUTES = 1000;

    private static void scatterKernel(
            uk.ac.manchester.tornado.api.types.arrays.FloatArray pose,
            uk.ac.manchester.tornado.api.types.arrays.FloatArray deltaVal,
            uk.ac.manchester.tornado.api.types.arrays.IntArray   deltaIdx,
            uk.ac.manchester.tornado.api.types.arrays.IntArray   count) {

        int N = count.get(0);
        for (@uk.ac.manchester.tornado.api.annotations.Parallel int i = 0;
                                                     i < deltaIdx.getSize(); i++) {
            if (i >= N) { return; }
            int idx = deltaIdx.get(i);
            pose.set(idx, deltaVal.get(i));
        }
    }

    private static void integrateKernel(
            uk.ac.manchester.tornado.api.types.arrays.FloatArray pose) {

        for (@uk.ac.manchester.tornado.api.annotations.Parallel int k = 0;
                                                     k < pose.getSize(); k++) {
            pose.set(k, pose.get(k) + 1.0f);
        }
    }

    public static void main(String[] args) {
        // ----- buffers -----
        uk.ac.manchester.tornado.api.types.arrays.FloatArray pose =
            new uk.ac.manchester.tornado.api.types.arrays.FloatArray(POSE_SIZE);
        uk.ac.manchester.tornado.api.types.arrays.FloatArray deltaVal =
            new uk.ac.manchester.tornado.api.types.arrays.FloatArray(DELTA_CAP);
        uk.ac.manchester.tornado.api.types.arrays.IntArray   deltaIdx =
            new uk.ac.manchester.tornado.api.types.arrays.IntArray(DELTA_CAP);
        uk.ac.manchester.tornado.api.types.arrays.IntArray   count =
            new uk.ac.manchester.tornado.api.types.arrays.IntArray(1);

        for (int i = 0; i < POSE_SIZE; i++)  pose.set(i, 0.0f);
        for (int i = 0; i < DELTA_CAP; i++) { deltaVal.set(i, 0.0f); deltaIdx.set(i, 0); }
        count.set(0, 0);

        // ----- task graph -----
        uk.ac.manchester.tornado.api.TaskGraph tg =
            new uk.ac.manchester.tornado.api.TaskGraph("probe")
                .transferToDevice(
                    uk.ac.manchester.tornado.api.enums.DataTransferMode.FIRST_EXECUTION,
                    pose)
                .transferToDevice(
                    uk.ac.manchester.tornado.api.enums.DataTransferMode.EVERY_EXECUTION,
                    deltaVal, deltaIdx, count)
                // VARIANT 2: declaration order swapped — integrate declared FIRST,
                // scatter declared SECOND. If declaration order drives execution
                // order, pose[5] will now be 193 (scatter→integrate ordering
                // achieved by reversing what we just observed).
                .task("integrate",
                      ScatterResidentProbeSwap::integrateKernel,
                      pose)
                .task("scatter",
                      ScatterResidentProbeSwap::scatterKernel,
                      pose, deltaVal, deltaIdx, count)
                .transferToHost(
                    uk.ac.manchester.tornado.api.enums.DataTransferMode.UNDER_DEMAND,
                    pose);

        uk.ac.manchester.tornado.api.ImmutableTaskGraph itg = tg.snapshot();
        uk.ac.manchester.tornado.api.TornadoExecutionPlan plan =
            new uk.ac.manchester.tornado.api.TornadoExecutionPlan(itg);

        // ----- Phase A: verdict run (200 executes, scatter at index 50) -----
        uk.ac.manchester.tornado.api.TornadoExecutionResult lastExec = null;
        for (int s = 0; s < EXECUTES; s++) {
            if (s == SCATTER_AT) {
                count.set(0, 1);
                deltaIdx.set(0, SCATTER_IDX);
                deltaVal.set(0, SCATTER_VAL);
            } else {
                count.set(0, 0);
            }
            lastExec = plan.execute();
        }
        // demand-sync pose host-side
        lastExec.transferToHost(pose);

        float pose10 = pose.get(RESIDENCY_PROBE_IDX);
        float pose5  = pose.get(SCATTER_IDX);

        // Expected values, two orderings:
        //   pose[10]: untouched by scatter; integrate adds 1 each execute.
        //             Expected if residency holds: 200.0f.
        //             If FIRST_EXECUTION re-uploads (reset to 0 each execute): 1.0f.
        //   pose[5]: if scatter runs BEFORE integrate on step 50:
        //              pre-50: 50.0 (incremented 50 times by integrate)
        //              step 50: scatter sets to 42; integrate adds 1 → 43
        //              post-50 (steps 51..199): +150 increments → 193.0f.
        //            if scatter runs AFTER integrate on step 50:
        //              pre-50 + integrate: 51.0
        //              scatter: 42.0
        //              post-50: +150 → 192.0f.
        //            (no scatter applied / silently dropped: 200.0f.)
        float expectedResident          = 200.0f;
        float expectedReset             =   1.0f;
        float expectedScatterBeforeIntg = 193.0f;
        float expectedScatterAfterIntg  = 192.0f;

        boolean residencyOk = Math.abs(pose10 - expectedResident) < 1e-3f;
        boolean residencyReset = Math.abs(pose10 - expectedReset) < 1e-3f;
        boolean scatterBeforeIntg = Math.abs(pose5 - expectedScatterBeforeIntg) < 1e-3f;
        boolean scatterAfterIntg  = Math.abs(pose5 - expectedScatterAfterIntg)  < 1e-3f;
        boolean scatterIgnored    = Math.abs(pose5 - expectedResident) < 1e-3f;

        System.out.println("===== verdict =====");
        System.out.printf("pose[%d] = %.4f%n", RESIDENCY_PROBE_IDX, pose10);
        System.out.printf("pose[%d]  = %.4f%n", SCATTER_IDX, pose5);
        System.out.println();
        System.out.println("Check 1 — residency / persistence:");
        System.out.printf("  expected %.1f if FIRST_EXECUTION holds (kernel writes persist) %n", expectedResident);
        System.out.printf("  expected %.1f if re-uploaded each execute (residency broken)%n", expectedReset);
        System.out.printf("  result: %s%n",
            residencyOk    ? "PASS — FIRST_EXECUTION resident, kernel writes persist" :
            residencyReset ? "FAIL — buffer re-uploaded each execute" :
                             "UNEXPECTED — neither candidate matched");
        System.out.println();
        System.out.println("Check 2 — scatter applied into resident buffer:");
        System.out.printf("  expected %.1f (scatter BEFORE integrate on step 50)%n", expectedScatterBeforeIntg);
        System.out.printf("  expected %.1f (scatter AFTER integrate on step 50)%n", expectedScatterAfterIntg);
        System.out.printf("  expected %.1f (scatter NOT applied)%n", expectedResident);
        System.out.printf("  result: %s%n",
            scatterBeforeIntg ? "PASS — scatter honored; ordering = scatter→integrate" :
            scatterAfterIntg  ? "PASS — scatter honored; ordering = integrate→scatter" :
            scatterIgnored    ? "FAIL — scatter NOT applied into resident buffer" :
                                "UNEXPECTED — no candidate matched");
        System.out.println();
        System.out.println("Check 3 — ordering within graph:");
        if (scatterBeforeIntg) {
            System.out.println("  scatter task runs BEFORE integrate task (declaration order honored).");
        } else if (scatterAfterIntg) {
            System.out.println("  scatter task runs AFTER integrate task (declaration order NOT honored).");
        } else {
            System.out.println("  could not determine ordering — scatter not applied.");
        }
        System.out.println();

        // ----- Phase B: per-execute timing (count=0 vs count=1) -----
        // Warm-up to let JIT settle (the pose buffer state is irrelevant for timing).
        for (int s = 0; s < 50; s++) {
            count.set(0, 0);
            plan.execute();
        }
        // count=0 timing
        long t0 = System.nanoTime();
        for (int s = 0; s < TIMING_EXECUTES; s++) {
            count.set(0, 0);
            plan.execute();
        }
        long t1 = System.nanoTime();
        // count=1 timing (single-element delta packed each execute)
        for (int s = 0; s < TIMING_EXECUTES; s++) {
            count.set(0, 1);
            deltaIdx.set(0, s % POSE_SIZE);
            deltaVal.set(0, (float) s);
            plan.execute();
        }
        long t2 = System.nanoTime();

        double avgZeroUs = (t1 - t0) / (double) TIMING_EXECUTES / 1000.0;
        double avgOneUs  = (t2 - t1) / (double) TIMING_EXECUTES / 1000.0;
        System.out.println("===== timing =====");
        System.out.printf("avg per-execute (count=0) : %.3f us%n", avgZeroUs);
        System.out.printf("avg per-execute (count=1) : %.3f us%n", avgOneUs);
        System.out.printf("delta cost (count=1 − 0)  : %.3f us%n", avgOneUs - avgZeroUs);

        try { plan.close(); } catch (Exception e) { /* best effort */ }
    }
}
