package boxOfActin;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;

// RatchetDiag (2026-06-15): measures the Brownian-ratchet closure "in action" by binning barbed-end
// polymerization by the gap g=end2TipC to the membrane (in units of the monomer increment delta). For
// each gap bin it counts poly-ELIGIBLE tip samples (denominator) and actual poly EVENTS (numerator);
// rate = events/samples. Normalizing to the free bin (g>=2*delta, where the ratchet factor is 1) gives
// the empirical attenuation vs gap — the force-velocity signature. SUCCESS = the boundary bins (g<delta)
// show rate suppressed by ~exp(-f*(delta-g)/kT) relative to free, i.e. the simulation's actual events
// reproduce the closure law. Accumulated lock-free across the multithreaded biochem phase.
public class RatchetDiag {

    static final int NB = 8;
    static final AtomicLongArray samples = new AtomicLongArray(NB);
    static final AtomicLongArray events  = new AtomicLongArray(NB);
    static final DoubleAdder[]   factorSum = new DoubleAdder[NB];   // Σ applied ratchet factor per bin
    static { for (int i = 0; i < NB; i++) factorSum[i] = new DoubleAdder(); }

    static final String[] LABELS = {
        "g<0 (overlap)", "0.0-0.2d", "0.2-0.4d", "0.4-0.6d",
        "0.6-0.8d", "0.8-1.0d", "1-2d", "g>=2d (free)"
    };

    static int bin(double gOverDelta) {
        if (gOverDelta < 0.0) return 0;
        if (gOverDelta < 0.2) return 1;
        if (gOverDelta < 0.4) return 2;
        if (gOverDelta < 0.6) return 3;
        if (gOverDelta < 0.8) return 4;
        if (gOverDelta < 1.0) return 5;
        if (gOverDelta < 2.0) return 6;
        return 7;
    }

    // Called once per poly-eligible barbed end per biochem fire: g = end2TipC (um), delta = monomer
    // increment (um), factor = the ratchet multiplier applied this fire, added = whether a monomer was
    // actually added. meanFactor per bin is the closure's output directly (saturation-independent);
    // the event rate is the realized effect.
    public static void recordTip(double g, double delta, double factor, boolean added) {
        int b = bin(g / delta);
        samples.incrementAndGet(b);
        factorSum[b].add(factor);
        if (added) events.incrementAndGet(b);
    }

    public static void report() {
        long sFree = samples.get(7);
        long eFree = events.get(7);
        double rFree = sFree > 0 ? (double) eFree / sFree : 0.0;
        long totS = 0, totE = 0;
        for (int b = 0; b < NB; b++) { totS += samples.get(b); totE += events.get(b); }
        if (totS == 0) return;
        System.out.printf("[RATCHET] poly vs gap g to membrane (delta=monomer, f=%.2e N). meanFactor=closure output; relRate=realized:%n",
            Env.ratchetForce.getValue());
        for (int b = 0; b < NB; b++) {
            long s = samples.get(b), e = events.get(b);
            double rate = s > 0 ? (double) e / s : 0.0;
            double rel  = rFree > 0 ? rate / rFree : 0.0;
            double mf   = s > 0 ? factorSum[b].sum() / s : 0.0;
            System.out.printf("  %-14s samples=%-9d events=%-8d meanFactor=%.3f  relRate=%.3f%n",
                LABELS[b], s, e, mf, rel);
        }
        System.out.printf("  TOTAL eligible tip-fires=%d  poly events=%d  (boundary g<delta share=%.1f%%)%n",
            totS, totE, 100.0 * (totS - sFree - samples.get(6)) / totS);
    }
}
