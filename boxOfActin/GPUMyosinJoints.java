package boxOfActin;

/**
 * Per-Myosin jointConstraints() GPU kernel.
 *
 * As of the 2026-05-30 chaining iteration this class is a no-op: the joints
 * kernel was merged into {@link GPUMoveThing}'s chained TaskGraph (joints
 * task followed by move task within a single .execute()), so dispatching
 * standalone is no longer needed. The class is kept as a stub because
 * BoxOfActin.doLoop() still references {@code computeJoints()} and the
 * end-of-run stats block reads {@code getCallCount()}; both are kept inert
 * here so the stats block stays silent.
 */
public class GPUMyosinJoints {

    public static final int KERNEL_ID = 3;

    public static void computeJoints() {
        // No-op: joints kernel is now task 1 of GPUMoveThing's chained graph.
    }

    public static void invalidatePlan() {
        // No-op: chained plan invalidation is handled by GPUMoveThing.invalidatePlan().
    }

    public static void reset() {
        // No-op.
    }

    public static long getTotalNanos()  { return 0; }
    public static long getPackNanos()   { return 0; }
    public static long getExecNanos()   { return 0; }
    public static long getUnpackNanos() { return 0; }
    public static int  getCallCount()   { return 0; }
    public static int  getMyoCap()      { return 0; }
    public static int  getThingCap()    { return 0; }
}
