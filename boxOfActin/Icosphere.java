package boxOfActin;

import java.util.HashMap;

/**
 * Deterministic icosphere geometry generator (Stage 1 of the DTS membrane).
 *
 * Builds a unit-radius triangulated sphere by recursively subdividing a regular
 * icosahedron: each of the 20 faces is split into 4 (edge midpoints projected to
 * the unit sphere) {@code nu} times, yielding
 *   {@code nv = 10*4^nu + 2} vertices and {@code nf = 20*4^nu} faces.
 *
 * Pure geometry only: no physics, no Things, no SoA. The {@link Membrane} object
 * consumes the {@link Geom} result, scales/translates it, derives the wing-edge
 * and vertex-incidence index arrays, and creates the vertex Things.
 *
 * Faces are emitted CCW as seen from OUTSIDE the sphere (outward normals), so the
 * signed volume V = (1/6) Sigma r_a . (r_b x r_c) is positive — the convention the
 * later area/volume force kernels rely on. {@link #build} verifies this and throws
 * if a build ever comes out inward-wound.
 *
 * Built on the host, once, at IC time (matches the reference DTS codes, which read
 * clean triangulations rather than hull-triangulating point clouds).
 */
public final class Icosphere {

    private Icosphere() {}

    /** Flat-array geometry result: unit-sphere vertices and CCW triangle faces. */
    public static final class Geom {
        /** Vertex coordinates on the unit sphere, packed [x0,y0,z0, x1,y1,z1, ...]; length 3*nv. */
        public final double[] vert;
        /** Triangle faces as CCW vertex-index triples [a0,b0,c0, a1,b1,c1, ...]; length 3*nf. */
        public final int[] face;
        public final int nv;
        public final int nf;

        Geom(double[] vert, int[] face) {
            this.vert = vert;
            this.face = face;
            this.nv = vert.length / 3;
            this.nf = face.length / 3;
        }
    }

    // Golden-ratio icosahedron: 12 vertices, 20 faces. Canonical (Kahler) winding,
    // which gives consistent OUTWARD normals once normalized to the unit sphere.
    private static final double T = (1.0 + Math.sqrt(5.0)) / 2.0;

    private static final double[][] BASE_VERT = {
        {-1,  T,  0}, { 1,  T,  0}, {-1, -T,  0}, { 1, -T,  0},
        { 0, -1,  T}, { 0,  1,  T}, { 0, -1, -T}, { 0,  1, -T},
        { T,  0, -1}, { T,  0,  1}, {-T,  0, -1}, {-T,  0,  1}
    };

    private static final int[][] BASE_FACE = {
        {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
        {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
        {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
        {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
    };

    /**
     * Build a unit-radius icosphere with {@code nu} levels of 4:1 subdivision.
     *
     * @param nu subdivision level (>= 0). nu=0 → 12 v / 20 f; nu=4 → 2562 v / 5120 f;
     *           nu=5 → 10242 v / 20480 f.
     */
    public static Geom build(int nu) {
        if (nu < 0) throw new IllegalArgumentException("icosphere subdivision nu must be >= 0, got " + nu);

        final int nv = 10 * pow4(nu) + 2;
        final int nf = 20 * pow4(nu);
        double[] vert = new double[3 * nv];

        // Seed the 12 icosahedron vertices (normalized to the unit sphere).
        int vCount = 0;
        for (double[] p : BASE_VERT) {
            placeNormalized(vert, vCount++, p[0], p[1], p[2]);
        }

        // Current face set begins as the 20 base faces (flat, [a,b,c] triples).
        int[] cur = new int[3 * 20];
        for (int f = 0; f < 20; f++) {
            cur[3 * f]     = BASE_FACE[f][0];
            cur[3 * f + 1] = BASE_FACE[f][1];
            cur[3 * f + 2] = BASE_FACE[f][2];
        }
        int curNf = 20;

        // One subdivision pass per level. midCache shares a midpoint vertex between the
        // two faces that flank an edge (keyed by the undirected edge), keeping the mesh
        // watertight. It is rebuilt each level (an edge only exists within one level).
        HashMap<Long, Integer> midCache = new HashMap<>();
        for (int level = 0; level < nu; level++) {
            int nextNf = curNf * 4;
            int[] next = new int[3 * nextNf];
            int nextCount = 0;
            midCache.clear();

            for (int f = 0; f < curNf; f++) {
                int a = cur[3 * f], b = cur[3 * f + 1], c = cur[3 * f + 2];
                int ab = midpoint(a, b, vert, midCache, nv, vCount);
                if (ab == vCount) vCount++;     // a fresh midpoint was placed
                int bc = midpoint(b, c, vert, midCache, nv, vCount);
                if (bc == vCount) vCount++;
                int ca = midpoint(c, a, vert, midCache, nv, vCount);
                if (ca == vCount) vCount++;
                // Replace each parent face with 4, preserving CCW winding.
                nextCount = emit(next, nextCount, a, ab, ca);
                nextCount = emit(next, nextCount, b, bc, ab);
                nextCount = emit(next, nextCount, c, ca, bc);
                nextCount = emit(next, nextCount, ab, bc, ca);
            }
            cur = next;
            curNf = nextNf;
        }

        if (vCount != nv) {
            throw new IllegalStateException("icosphere vertex count mismatch: built " + vCount + " expected " + nv);
        }
        if (curNf != nf) {
            throw new IllegalStateException("icosphere face count mismatch: built " + curNf + " expected " + nf);
        }

        Geom g = new Geom(vert, cur);
        verifyOutward(g);
        return g;
    }

    // Append face (a,b,c) into dst at offset 3*count; return new count.
    private static int emit(int[] dst, int count, int a, int b, int c) {
        dst[3 * count]     = a;
        dst[3 * count + 1] = b;
        dst[3 * count + 2] = c;
        return count + 1;
    }

    // Return the midpoint vertex index of edge (i,j). On first request the midpoint is
    // created at slot `freeSlot` (and returned == freeSlot, signalling the caller to
    // advance its counter); on a repeat request the cached index is returned.
    private static int midpoint(int i, int j, double[] vert, HashMap<Long, Integer> midCache, int nv, int freeSlot) {
        int lo = Math.min(i, j), hi = Math.max(i, j);
        long key = (long) lo * nv + hi;
        Integer existing = midCache.get(key);
        if (existing != null) return existing;

        placeNormalized(vert, freeSlot,
                vert[3 * i]     + vert[3 * j],
                vert[3 * i + 1] + vert[3 * j + 1],
                vert[3 * i + 2] + vert[3 * j + 2]);   // sum, not /2 — normalize cancels the 2
        midCache.put(key, freeSlot);
        return freeSlot;
    }

    // Write the unit-normalized direction (x,y,z) into vertex slot `slot`.
    private static void placeNormalized(double[] vert, int slot, double x, double y, double z) {
        double inv = 1.0 / Math.sqrt(x * x + y * y + z * z);
        vert[3 * slot]     = x * inv;
        vert[3 * slot + 1] = y * inv;
        vert[3 * slot + 2] = z * inv;
    }

    private static int pow4(int n) {
        int r = 1;
        for (int k = 0; k < n; k++) r *= 4;
        return r;
    }

    // Verify outward winding via positive signed volume.
    private static void verifyOutward(Geom g) {
        double v6 = 0.0;
        for (int f = 0; f < g.nf; f++) {
            int a = g.face[3 * f], b = g.face[3 * f + 1], c = g.face[3 * f + 2];
            double bx = g.vert[3 * b], by = g.vert[3 * b + 1], bz = g.vert[3 * b + 2];
            double cx = g.vert[3 * c], cy = g.vert[3 * c + 1], cz = g.vert[3 * c + 2];
            double cxx = by * cz - bz * cy;     // (b x c)
            double cxy = bz * cx - bx * cz;
            double cxz = bx * cy - by * cx;
            v6 += g.vert[3 * a] * cxx + g.vert[3 * a + 1] * cxy + g.vert[3 * a + 2] * cxz;
        }
        if (v6 <= 0) {
            throw new IllegalStateException("icosphere faces are inward-wound (signed 6V=" + v6 + "); fix BASE_FACE winding");
        }
    }
}
