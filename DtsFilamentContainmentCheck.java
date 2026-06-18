import boxOfActin.Membrane;
import boxOfActin.Pt3D;
import boxOfActin.Env;

/**
 * Filament-containment stress test (before wiring real actin): can the DTS membrane contain a THIN rigid rod
 * (an actin filament, radius 3.5 nm) driven hard against it, in ALL orientations — including the dangerous
 * oblique/tangential cases where the old tip-only collision would let the body leak through?
 *
 * For a sweep of directions × obliquities, a rod is placed just inside the (held-rigid) shell and driven
 * radially outward with a strong constant force; each step it is integrated as a rigid overdamped rod and
 * Membrane.segmentVsMembrane pushes it back. We track the worst "leak" = the farthest any rod sample gets
 * past the membrane surface (along the outward normal). leak <= filament radius everywhere = contained.
 *
 * The membrane is held rigid (vertices not integrated) on purpose: that is the STRICTEST containment test —
 * the rod is forced against an immovable wall, maximizing the pressure to tunnel. A deforming membrane only
 * gives way (bulges) and is easier to contain.
 */
public class DtsFilamentContainmentCheck {
    public static void main(String[] args) throws Exception {
        int nu       = args.length > 0 ? Integer.parseInt(args[0]) : 3;       // mesh (nf = 20*4^nu)
        double drive = args.length > 1 ? Double.parseDouble(args[1]) : 1.0e-10; // outward press (N)
        int steps    = args.length > 2 ? Integer.parseInt(args[2]) : 2500;

        double R = 1.2, L = 0.4;                  // membrane radius, rod length (um)
        double rad = 0.0035;                      // actin filament radius (um, 3.5 nm)
        double gSeg = 3.0e-7;                     // rod drag (N s/m) -- a ~0.4 um filament's order
        double dt = 1.0e-5;
        setVal("collisionDeltaT", dt);

        Membrane m = new Membrane(nu, R, new Pt3D(0,0,0));
        m.refreshFaceGeometry();                  // populate per-face normals (membrane held rigid)
        System.out.printf("Containment test: nu=%d (nf=%d)  rod L=%.2f r=%.4f um  drive=%.1e N  steps=%d%n",
                nu, 20*pow4(nu), L, rad, drive, steps);

        // Direction set: 6 axes + 8 cube-corners + random (seeded) -> broad coverage.
        java.util.ArrayList<double[]> dirs = new java.util.ArrayList<>();
        double c = 0.57735;
        double[][] fixed = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1},
                            {c,c,c},{c,c,-c},{c,-c,c},{c,-c,-c},{-c,c,c},{-c,c,-c},{-c,-c,c},{-c,-c,-c}};
        for (double[] d : fixed) dirs.add(d);
        java.util.Random rng = new java.util.Random(7);
        for (int i = 0; i < 10; i++) dirs.add(randUnit(rng));

        String[] oblNames = {"radial(0deg)", "oblique(45deg)", "tangential(90deg)"};
        double[] oblAng = {0.0, 45.0, 90.0};
        double worstFinalAll = -1e30, worstTransAll = -1e30;
        // "escape" = the rod ends up persistently past the surface by more than a membrane edge (~truly through)
        for (int oi = 0; oi < 3; oi++) {
            double worstFinal = -1e30, worstTrans = -1e30;
            for (double[] d : dirs) {
                double[] r = runOne(m, d, oblAng[oi], R, L, rad, gSeg, drive, dt, steps);  // {transient, final}
                worstTrans = Math.max(worstTrans, r[0]);
                worstFinal = Math.max(worstFinal, r[1]);
            }
            worstTransAll = Math.max(worstTransAll, worstTrans);
            worstFinalAll = Math.max(worstFinalAll, worstFinal);
            System.out.printf("  %-18s final leak %+.5f  transient max %+.5f um  %s%n",
                    oblNames[oi], worstFinal, worstTrans, worstFinal <= rad ? "contained" : "leaked");
        }
        System.out.printf("%nWorst FINAL leak = %+.5f um, worst TRANSIENT = %+.5f um  (filament r=%.4f)%n",
                worstFinalAll, worstTransAll, rad);
        boolean contained = worstFinalAll <= rad;            // rod ends up inside everywhere
        boolean noEscape  = worstTransAll < 0.10;            // never even transiently went a membrane-scale out
        System.out.println(contained && noEscape
            ? "PASS: filament contained in all orientations (ends inside; no escape)"
            : (contained ? "PASS(soft): ends contained everywhere, but a transient overshoot exceeded 0.10 um under this drive"
                         : "FAIL: filament ends up outside in some orientation"));
    }

    // Drive one rod; return {worstTransientLeak, finalLeak}.
    private static double[] runOne(Membrane m, double[] d, double oblDeg, double R, double L, double rad,
                                 double gSeg, double drive, double dt, int steps) {
        double[] n = norm(d);                                  // placement direction (radial)
        double[] perp = norm(anyPerp(n));
        double th = Math.toRadians(oblDeg);
        double[] axis = norm(new double[]{                     // rod long-axis = radial tilted by obliquity
                Math.cos(th)*n[0] + Math.sin(th)*perp[0],
                Math.cos(th)*n[1] + Math.sin(th)*perp[1],
                Math.cos(th)*n[2] + Math.sin(th)*perp[2]});
        double place = R - 0.5*L - 0.15;                       // center deep enough that EVERY endpoint starts inside
        double[] cen = {place*n[0], place*n[1], place*n[2]};
        double[] p1 = {cen[0]-0.5*L*axis[0], cen[1]-0.5*L*axis[1], cen[2]-0.5*L*axis[2]};
        double[] p2 = {cen[0]+0.5*L*axis[0], cen[1]+0.5*L*axis[1], cen[2]+0.5*L*axis[2]};

        double[] r1 = new double[3], r2 = new double[3];
        double worst = -1e30, last = -1e30;
        for (int s = 0; s < steps; s++) {
            double leak = m.segmentVsMembrane(p1[0],p1[1],p1[2], p2[0],p2[1],p2[2], rad, gSeg, r1, r2);
            if (leak > worst) worst = leak;
            last = leak;
            // forces on endpoints: steric reaction + half the outward radial drive each
            double f1x = r1[0] + 0.5*drive*n[0], f1y = r1[1] + 0.5*drive*n[1], f1z = r1[2] + 0.5*drive*n[2];
            double f2x = r2[0] + 0.5*drive*n[0], f2y = r2[1] + 0.5*drive*n[1], f2z = r2[2] + 0.5*drive*n[2];
            double k = dt/gSeg*1e6;                            // overdamped step, N -> um displacement
            p1[0]+=k*f1x; p1[1]+=k*f1y; p1[2]+=k*f1z;
            p2[0]+=k*f2x; p2[1]+=k*f2y; p2[2]+=k*f2z;
            // re-rigidify (keep length L, recompute center+axis from moved endpoints)
            double cx=0.5*(p1[0]+p2[0]), cy=0.5*(p1[1]+p2[1]), cz=0.5*(p1[2]+p2[2]);
            double ax=p2[0]-p1[0], ay=p2[1]-p1[1], az=p2[2]-p1[2];
            double al=Math.sqrt(ax*ax+ay*ay+az*az); ax/=al; ay/=al; az/=al;
            p1[0]=cx-0.5*L*ax; p1[1]=cy-0.5*L*ay; p1[2]=cz-0.5*L*az;
            p2[0]=cx+0.5*L*ax; p2[1]=cy+0.5*L*ay; p2[2]=cz+0.5*L*az;
        }
        return new double[]{worst, last};
    }

    private static double[] randUnit(java.util.Random rng) {
        double x,y,z,s;
        do { x=2*rng.nextDouble()-1; y=2*rng.nextDouble()-1; z=2*rng.nextDouble()-1; s=x*x+y*y+z*z; }
        while (s>1 || s<1e-4);
        double inv=1/Math.sqrt(s); return new double[]{x*inv,y*inv,z*inv};
    }
    private static double[] norm(double[] v){ double l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); return new double[]{v[0]/l,v[1]/l,v[2]/l}; }
    private static double[] anyPerp(double[] n){
        double[] a = Math.abs(n[2])<0.9 ? new double[]{0,0,1} : new double[]{1,0,0};
        return new double[]{ n[1]*a[2]-n[2]*a[1], n[2]*a[0]-n[0]*a[2], n[0]*a[1]-n[1]*a[0] };
    }
    private static int pow4(int n){ int r=1; for(int i=0;i<n;i++) r*=4; return r; }
    private static void setVal(String label, double val) throws Exception {
        java.lang.reflect.Field f = Env.class.getDeclaredField(label); f.setAccessible(true);
        Object p = f.get(null);
        p.getClass().getMethod("setValue", double.class).invoke(p, val);
    }
}
