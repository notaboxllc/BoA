package boxOfActin;

import java.util.concurrent.atomic.LongAdder;

// step() per-force profiler.
//
// Gated by env var BOA_STEP_PROFILE=1 (default OFF). When enabled, each
// instrumented force-method site records elapsed nanoseconds into a per-
// force bucket. Workers all increment the same LongAdder concurrently;
// contention is low because there is one adder per bucket, and LongAdder
// shards internally.
//
// Reported once at end of run via StepProfiler.report().
public final class StepProfiler {
    public static final boolean ENABLED;
    static {
        String v = System.getenv("BOA_STEP_PROFILE");
        ENABLED = (v != null && !v.isEmpty()
                && !v.equals("0") && !v.equalsIgnoreCase("false"));
    }

    // Bucket IDs — match the survey F-labels.
    public static final int F1_2_FILSEG_BOUNDARY      = 0;
    public static final int F3_FILSEG_CHAIN_LINK      = 1;
    public static final int F4_FILSEG_CHAIN_TORQUE    = 2;
    public static final int F5_6_FILSEG_NODE          = 3;
    public static final int F7_MYOMINI_BOUNDARY       = 4;
    public static final int F8_10_MYOMOTOR_TIPLINK    = 5;
    public static final int F11_ACTA                  = 6;
    public static final int F12_PROTEINNODE_BOUNDARY  = 7;
    public static final int FX_BUG_DRAG_TENSOR        = 8;
    public static final int NBUCKETS = 9;

    static final String[] LABELS = {
        "F1/F2  FilSeg boundary collision   ",
        "F3     FilSeg chain link spring    ",
        "F4     FilSeg chain torsion spring ",
        "F5/F6  FilSeg node tether          ",
        "F7     MyoMini boundary collision  ",
        "F8-F10 MyoMotor tipLink (motor-fil)",
        "F11    ActA tether                 ",
        "F12    ProteinNode boundary        ",
        "       Bug drag-tensor update      ",
    };

    private static final LongAdder[] nanos = new LongAdder[NBUCKETS];
    private static final LongAdder[] calls = new LongAdder[NBUCKETS];
    static {
        for (int i = 0; i < NBUCKETS; i++) {
            nanos[i] = new LongAdder();
            calls[i] = new LongAdder();
        }
    }

    // Caller pattern: long t = StepProfiler.t0(); work(); StepProfiler.add(BUCKET, t);
    public static long t0() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void add(int bucket, long t0) {
        if (!ENABLED) return;
        nanos[bucket].add(System.nanoTime() - t0);
        calls[bucket].increment();
    }

    public static void report() {
        if (!ENABLED) return;
        long total = 0;
        for (int i = 0; i < NBUCKETS; i++) total += nanos[i].sum();
        System.out.println();
        System.out.println("*** step() per-force profile (BOA_STEP_PROFILE=1) ***");
        System.out.printf("  %-36s %8s %6s %14s %12s%n",
                "Force", "sec", "pct", "calls", "ns/call");
        for (int i = 0; i < NBUCKETS; i++) {
            long n = nanos[i].sum();
            long c = calls[i].sum();
            if (c == 0) continue;
            double sec = n / 1.0e9;
            double pct = total > 0 ? 100.0 * n / total : 0.0;
            double nspc = (double) n / c;
            System.out.printf("  %-36s %8.3f %5.1f%% %14d %12.0f%n",
                    LABELS[i], sec, pct, c, nspc);
        }
        System.out.printf("  %-36s %8.3f%n", "TOTAL (sum of buckets)", total / 1.0e9);
        System.out.println("  Note: bucket sums are wall × thread-count (each worker accumulates its own time).");
    }
}
