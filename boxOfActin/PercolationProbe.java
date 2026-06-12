package boxOfActin;

import java.util.HashMap;

// Filament-network percolation / spanning probe (benchmark-contractile-dense, 2026-06-12).
//
// Builds the filament-level graph from the ACTIVE FilLink population (each link
// joins fil1.filID and fil2.filID), runs union-find over filaments, and reports:
//   - number of distinct filaments and active links
//   - links-per-filament
//   - the largest connected component (by filament count and its size fraction)
//   - whether that largest component SPANS the box: for every segment whose
//     filament belongs to the largest component, the X/Y/Z coordinate extent is
//     accumulated; the per-axis span fraction = extent / boxDim. A network is
//     called percolating (contractility-competent) when the largest component
//     spans a lateral box dimension (max(xFrac,yFrac) >= SPAN_THRESHOLD).
//
// Invoked once at end of run from BoxOfActin.doLoop() (prints a [STATS] line);
// also callable mid-run. Read-only; allocates per call (end-of-run cadence).
public final class PercolationProbe {

    public static final double SPAN_THRESHOLD = 0.8;

    // Compact union-find over a dense index space.
    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }
    private static void union(int[] parent, int[] rank, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra == rb) return;
        if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank[ra] == rank[rb]) rank[ra]++;
    }

    public static final class Result {
        public int filaments, activeLinks, largestCompFils;
        public double linksPerFil, largestCompFrac;
        public double xFrac, yFrac, zFrac, spanFrac;
        public boolean percolates;
    }

    public static Result compute() {
        Result r = new Result();

        // 1. Collect distinct filament IDs present in the live segment array,
        //    mapping each to a compact 0..nFil-1 index.
        HashMap<Integer,Integer> idToIdx = new HashMap<>();
        int n = FilSegment.filSegmentCt;
        for (int i = 0; i < n; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            idToIdx.putIfAbsent(fs.filID, idToIdx.size());
        }
        int nFil = idToIdx.size();
        r.filaments = nFil;
        if (nFil == 0) return r;

        int[] parent = new int[nFil];
        int[] rank   = new int[nFil];
        for (int i = 0; i < nFil; i++) parent[i] = i;

        // 2. Union filaments joined by an ACTIVE crosslink.
        int active = 0;
        for (int i = 0; i < FilLink.filLinkCt; i++) {
            FilLink l = FilLink.filLinks[i];
            if (l == null || !l.active) continue;
            if (l.fil1 == null || l.fil2 == null) continue;
            Integer a = idToIdx.get(l.fil1.filID);
            Integer b = idToIdx.get(l.fil2.filID);
            if (a == null || b == null) continue;   // segment gone this step
            active++;
            if (!a.equals(b)) union(parent, rank, a, b);
        }
        r.activeLinks = active;
        r.linksPerFil = (double) active / nFil;

        // 3. Largest component by filament count.
        int[] compSize = new int[nFil];
        int bestRoot = -1, bestSize = 0;
        for (int i = 0; i < nFil; i++) {
            int root = find(parent, i);
            if (++compSize[root] > bestSize) { bestSize = compSize[root]; bestRoot = root; }
        }
        r.largestCompFils = bestSize;
        r.largestCompFrac = (double) bestSize / nFil;

        // 4. Coordinate extent of the largest component (over all its segments).
        double minX =  Double.MAX_VALUE, minY =  Double.MAX_VALUE, minZ =  Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            Integer idx = idToIdx.get(fs.filID);
            if (idx == null || find(parent, idx) != bestRoot) continue;
            double x = fs.getCoordX(), y = fs.getCoordY(), z = fs.getCoordZ();
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        double bx = Env.boxXDim.getValue(), by = Env.boxYDim.getValue(), bz = Env.boxZDim.getValue();
        r.xFrac = bx > 0 ? (maxX - minX) / bx : 0;
        r.yFrac = by > 0 ? (maxY - minY) / by : 0;
        r.zFrac = bz > 0 ? (maxZ - minZ) / bz : 0;
        r.spanFrac = Math.max(r.xFrac, r.yFrac);
        r.percolates = r.spanFrac >= SPAN_THRESHOLD;
        return r;
    }

    public static void report() {
        Result r = compute();
        System.out.printf("[STATS] percolation filaments=%d activeLinks=%d linksPerFil=%.3f "
                + "largestComp=%d (%.1f%% of fils) spanX=%.2f spanY=%.2f spanZ=%.2f "
                + "spanFrac=%.2f percolates=%b%n",
                r.filaments, r.activeLinks, r.linksPerFil,
                r.largestCompFils, 100.0 * r.largestCompFrac,
                r.xFrac, r.yFrac, r.zFrac, r.spanFrac, r.percolates);
    }

    private PercolationProbe() {}
}
