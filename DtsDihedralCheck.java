import boxOfActin.Icosphere;
import java.util.HashMap;

/**
 * Standalone numeric check (no sim machinery): for icospheres at increasing subdivision,
 * compute the discrete dihedral bending sum  S = Sigma_{interior edges} (1 - n_a . n_b)
 * over the two unit face normals sharing each edge. For a closed surface the continuum
 * Helfrich bending energy is 8*pi*kappa (radius-independent, spontaneous curvature 0). If
 * E_bend = kappa_d * S and kappa = c * kappa_d, then S should converge to 8*pi*c.
 *
 *   c = sqrt(3)/2  -> 8*pi*c = 21.766   (Seung-Nelson triangular-lattice result)
 *
 * This tells us empirically what constant our OWN icosphere mesh produces, so we hardcode
 * the right kappa<->kappa_d coefficient. Run on the unit sphere (radius cancels for the
 * dihedral form, but we confirm radius-independence by also doing R=1.2).
 */
public class DtsDihedralCheck {

    public static void main(String[] args) {
        System.out.println("Two discrete bending forms on the icosphere, vs the continuum sphere target 8*pi:");
        System.out.println("  CRUDE   (Seung-Nelson)  E/kd = S1 = Sigma_edges (1 - n_a.n_b)        -- anisotropic, NOT Helfrich");
        System.out.println("  JULICHER(TriMem/FreeDTS) E/k  = S2 = Sigma_v 2 c_i^2 / A_i,           -- isotropic, true Helfrich");
        System.out.println("                                   c_i = (1/4) Sigma_{e in i} l_e theta_e,  A_i = (1/3) Sigma area");
        System.out.println();
        System.out.printf("%-4s %8s %8s %12s %12s %12s %12s%n",
                "nu", "nv", "nf", "S1(crude)", "S1/(8pi)", "S2(julich)", "S2/(8pi)");
        for (int nu = 1; nu <= 6; nu++) {
            Icosphere.Geom g = Icosphere.build(nu);
            double s1 = dihedralSum(g, 1.0);
            double s2 = julicherSum(g, 1.0);
            double s2big = julicherSum(g, 2.7);   // radius-independence check (Helfrich is scale-free)
            System.out.printf("%-4d %8d %8d %12.6f %12.6f %12.6f %12.6f   (R=2.7 -> S2=%.6f)%n",
                    nu, g.nv, g.nf, s1, s1 / (8 * Math.PI), s2, s2 / (8 * Math.PI), s2big);
        }
        System.out.printf("%nContinuum sphere target  8*pi = %.6f%n", 8 * Math.PI);
        System.out.println("If S2 -> 8*pi : the Julicher/TriMem form reproduces Helfrich with kappa multiplying DIRECTLY");
        System.out.println("(E_bend = kappa * S2), no sqrt(3)/2 fudge. The crude S1 does NOT (anisotropic dihedral model).");
    }

    // Sum over interior edges of (1 - n_a . n_b), n = unit triangle normal (outward, CCW).
    private static double dihedralSum(Icosphere.Geom g, double radius) {
        double[] v = new double[g.vert.length];
        for (int i = 0; i < v.length; i++) v[i] = radius * g.vert[i];

        // Precompute unit face normals.
        double[] nx = new double[g.nf], ny = new double[g.nf], nz = new double[g.nf];
        for (int f = 0; f < g.nf; f++) {
            int a = g.face[3 * f], b = g.face[3 * f + 1], c = g.face[3 * f + 2];
            double abx = v[3*b]-v[3*a], aby = v[3*b+1]-v[3*a+1], abz = v[3*b+2]-v[3*a+2];
            double acx = v[3*c]-v[3*a], acy = v[3*c+1]-v[3*a+1], acz = v[3*c+2]-v[3*a+2];
            double cx = aby*acz - abz*acy, cy = abz*acx - abx*acz, cz = abx*acy - aby*acx;
            double inv = 1.0 / Math.sqrt(cx*cx + cy*cy + cz*cz);
            nx[f] = cx*inv; ny[f] = cy*inv; nz[f] = cz*inv;
        }

        // Derive undirected edges -> the (up to) two faces sharing them.
        HashMap<Long,int[]> edge = new HashMap<>();  // key -> {face0, face1}
        for (int f = 0; f < g.nf; f++) {
            int a = g.face[3*f], b = g.face[3*f+1], c = g.face[3*f+2];
            addEdge(edge, a, b, f, g.nv);
            addEdge(edge, b, c, f, g.nv);
            addEdge(edge, c, a, f, g.nv);
        }
        double s = 0.0;
        for (int[] fc : edge.values()) {
            if (fc[1] < 0) continue;   // boundary edge (none on a closed sphere)
            int fa = fc[0], fb = fc[1];
            double dot = nx[fa]*nx[fb] + ny[fa]*ny[fb] + nz[fa]*nz[fb];
            s += 1.0 - dot;
        }
        return s;
    }

    // Julicher / TriMem / FreeDTS form:  E_bend = kappa * Sigma_v 2 c_i^2 / A_i,
    //   c_i = (1/4) Sigma_{edges e at i} l_e * theta_e   (theta_e = dihedral angle between adjacent faces),
    //   A_i = (1/3) Sigma_{faces f at i} area(f)         (barycentric vertex area).
    // Returns Sigma_v 2 c_i^2 / A_i  (= E_bend / kappa). For a sphere this should -> 8*pi.
    private static double julicherSum(Icosphere.Geom g, double radius) {
        double[] v = new double[g.vert.length];
        for (int i = 0; i < v.length; i++) v[i] = radius * g.vert[i];

        // Per-face unit normal + area.
        double[] nx = new double[g.nf], ny = new double[g.nf], nz = new double[g.nf], ar = new double[g.nf];
        for (int f = 0; f < g.nf; f++) {
            int a = g.face[3*f], b = g.face[3*f+1], c = g.face[3*f+2];
            double abx=v[3*b]-v[3*a], aby=v[3*b+1]-v[3*a+1], abz=v[3*b+2]-v[3*a+2];
            double acx=v[3*c]-v[3*a], acy=v[3*c+1]-v[3*a+1], acz=v[3*c+2]-v[3*a+2];
            double cx=aby*acz-abz*acy, cy=abz*acx-abx*acz, cz=abx*acy-aby*acx;
            double mag=Math.sqrt(cx*cx+cy*cy+cz*cz);
            nx[f]=cx/mag; ny[f]=cy/mag; nz[f]=cz/mag; ar[f]=0.5*mag;
        }
        // Per-vertex barycentric area A_i = (1/3) Sigma incident face area.
        double[] area = new double[g.nv];
        for (int f = 0; f < g.nf; f++) {
            area[g.face[3*f]]   += ar[f] / 3.0;
            area[g.face[3*f+1]] += ar[f] / 3.0;
            area[g.face[3*f+2]] += ar[f] / 3.0;
        }
        // Per-vertex curvature c_i: each incident edge contributes (1/4) l_e theta_e.
        double[] cv = new double[g.nv];
        HashMap<Long,int[]> edge = new HashMap<>();
        for (int f = 0; f < g.nf; f++) {
            int a=g.face[3*f], b=g.face[3*f+1], c=g.face[3*f+2];
            addEdge(edge,a,b,f,g.nv); addEdge(edge,b,c,f,g.nv); addEdge(edge,c,a,f,g.nv);
        }
        for (java.util.Map.Entry<Long,int[]> en : edge.entrySet()) {
            int[] fc = en.getValue();
            if (fc[1] < 0) continue;
            long key = en.getKey();
            int lo = (int)(key / g.nv), hi = (int)(key % g.nv);
            double lex=v[3*hi]-v[3*lo], ley=v[3*hi+1]-v[3*lo+1], lez=v[3*hi+2]-v[3*lo+2];
            double le = Math.sqrt(lex*lex+ley*ley+lez*lez);          // edge length
            int fa=fc[0], fb=fc[1];
            double dot = nx[fa]*nx[fb]+ny[fa]*ny[fb]+nz[fa]*nz[fb];
            dot = Math.max(-1, Math.min(1, dot));
            double theta = Math.acos(dot);                            // dihedral angle (>=0; sphere convex)
            double contrib = 0.25 * le * theta;
            cv[lo] += contrib; cv[hi] += contrib;                     // both endpoints
        }
        double s2 = 0.0;
        for (int i = 0; i < g.nv; i++) if (area[i] > 0) s2 += 2.0 * cv[i] * cv[i] / area[i];
        return s2;
    }

    private static void addEdge(HashMap<Long,int[]> edge, int p, int q, int f, int nv) {
        int lo = Math.min(p,q), hi = Math.max(p,q);
        long key = (long) lo * nv + hi;
        int[] fc = edge.get(key);
        if (fc == null) edge.put(key, new int[]{f, -1});
        else fc[1] = f;
    }
}
