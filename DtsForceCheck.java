import boxOfActin.Membrane;
import boxOfActin.MembraneVertex;
import boxOfActin.Pt3D;
import boxOfActin.Env;

/**
 * Finite-difference validation of the Stage-2 analytic membrane forces against the energy.
 * The energy (E_bend Julicher + E_area + E_vol) is independently trusted (E_bend -> 8*pi*kappa
 * sphere check passed). This test confirms the ANALYTIC FORCE = -dE/dr by central differences:
 *   F_analytic[i] ?= -(E(r_i+h) - E(r_i-h)) / (2h)   for every vertex, every axis.
 * A small random perturbation of the sphere makes all three force terms nonzero and checkable.
 */
public class DtsForceCheck {
    public static void main(String[] args) throws Exception {
        // Term moduli (args 2,3,4 override -> isolate one term at a time for debugging).
        double kap = args.length > 1 ? Double.parseDouble(args[1]) : 1.0e-19;   // J
        double ka  = args.length > 2 ? Double.parseDouble(args[2]) : 0.2;       // N/m
        double kv  = args.length > 3 ? Double.parseDouble(args[3]) : 5.0e-19;   // J
        setVal("dtsKappaBend",   kap);
        setVal("dtsKappaArea",   ka);
        setVal("dtsKappaVolume", kv);
        setVal("dtsTargetReducedVol", 1.0);
        System.out.printf("terms: kappa=%.2e  K_A=%.2e  K_V=%.2e%n", kap, ka, kv);

        int nu = Integer.parseInt(args.length > 0 ? args[0] : "2");   // small mesh by default
        Membrane m = new Membrane(nu, 1.0, new Pt3D(0, 0, 0));
        MembraneVertex[] v = m.vert;
        int nv = v.length;

        // Deterministic small perturbation (so all terms are excited; no Math.random()).
        java.util.Random rng = new java.util.Random(12345);
        double amp = 0.02 * mEdge(m);   // ~2% of an edge length, in microns
        for (int i = 0; i < nv; i++) {
            v[i].setCoord(m.vx(i) + amp*(rng.nextDouble()-0.5),
                          m.vy(i) + amp*(rng.nextDouble()-0.5),
                          m.vz(i) + amp*(rng.nextDouble()-0.5));
        }

        // Analytic forces: zero the slots, compute, read back (N).
        for (int i = 0; i < nv; i++) v[i].zeroForceSumSlot();
        m.computeForces();
        double[] afx = new double[nv], afy = new double[nv], afz = new double[nv];
        for (int i = 0; i < nv; i++) {
            afx[i] = v[i].getForceSumX(); afy[i] = v[i].getForceSumY(); afz[i] = v[i].getForceSumZ();
        }

        // Central-difference forces.  positions in microns; F[N] = -dE/dr[m] = -dE/dr[um] * 1e6.
        // NB float SoA coords (setCoord) limit the usable step: too small and h is lost to float
        // granularity (~6e-8 um near r~1um), too large and O(h^2) truncation grows. ~1e-3 um is the sweet spot.
        double h = args.length > 4 ? Double.parseDouble(args[4]) : 2.0e-4;     // microns (FD sweet spot)
        double maxAbsErr = 0, maxRelErr = 0, maxF = 0;
        int worst = -1;
        for (int i = 0; i < nv; i++) {
            double[] fd = new double[3];
            fd[0] = central(m, v[i], 0, h);
            fd[1] = central(m, v[i], 1, h);
            fd[2] = central(m, v[i], 2, h);
            double[] an = { afx[i], afy[i], afz[i] };
            for (int k = 0; k < 3; k++) {
                double err = Math.abs(an[k] - fd[k]);
                double scale = Math.max(Math.abs(an[k]), Math.abs(fd[k]));
                maxF = Math.max(maxF, scale);
                if (err > maxAbsErr) { maxAbsErr = err; worst = i; }
                if (scale > 1e-16) maxRelErr = Math.max(maxRelErr, err/scale);
            }
        }
        System.out.printf("nu=%d  nv=%d   typical |F| ~ %.3e N%n", nu, nv, maxF);
        System.out.printf("max |F_analytic - F_fd|      = %.3e N   (worst vertex %d)%n", maxAbsErr, worst);
        System.out.printf("max relative error           = %.3e%n", maxRelErr);
        System.out.printf("max abs err / typical |F|    = %.3e%n", maxAbsErr / maxF);
        System.out.println(maxAbsErr/maxF < 1e-3 ? "PASS: analytic force matches -dE/dr" :
                                                   "FAIL: gradient mismatch (check sign/formula)");

        // ---- Manual overdamped gradient descent (bypasses the sim's moveThing) ----
        // x_i += (F_i/gamma)*dt_desc.  If E decreases monotonically, force+energy are self-consistent
        // and any energy RISE in the live sim is a mover/loop issue, not the force.
        System.out.println("\n-- manual gradient descent (bending+area+vol), gamma=6 pi eta r_v --");
        double eta = 0.1, rv = 0.5 * mEdge(m) * 1e-6;     // metres
        double gamma = 6 * Math.PI * eta * rv;            // N s/m
        double dtd = 1e-6;                                 // s
        // reset to the clean (unperturbed) sphere
        Membrane m2 = new Membrane(nu, 1.0, new Pt3D(0, 0, 0));
        MembraneVertex[] w = m2.vert; int n2 = w.length;
        for (int it = 0; it <= 200; it++) {
            for (int i = 0; i < n2; i++) w[i].zeroForceSumSlot();
            m2.computeForces();
            if (it % 40 == 0) System.out.printf("  iter %3d  E=%.6e J  (A=%.4f V=%.4f)%n",
                    it, m2.totalEnergy(), m2.currentAreaUm2(), m2.currentVolUm3());
            for (int i = 0; i < n2; i++) {
                double fx=w[i].getForceSumX(), fy=w[i].getForceSumY(), fz=w[i].getForceSumZ();
                w[i].setCoord(w[i].getCoordX()+fx/gamma*dtd*1e6,
                              w[i].getCoordY()+fy/gamma*dtd*1e6,
                              w[i].getCoordZ()+fz/gamma*dtd*1e6);   // metres->microns: *1e6
            }
        }

        // ---- Same descent but via the sim's ProteinNode.moveThing(), matching sim R=1.2, dt=1e-5 ----
        System.out.println("\n-- descent via sim moveThing() (R=1.2, Env.deltaT=1e-5, like the sim) --");
        setEnvDt(1e-5);
        Membrane m3 = new Membrane(nu, 1.2, new Pt3D(0, 0, 0));
        MembraneVertex[] u = m3.vert; int n3 = u.length;
        for (int it = 0; it <= 2000; it++) {
            for (int i = 0; i < n3; i++) u[i].zeroForceSumSlot();
            m3.computeForces();
            if (it % 250 == 0) System.out.printf("  iter %4d  E=%.6e J  (A=%.4f V=%.4f)%n",
                    it, m3.totalEnergy(), m3.currentAreaUm2(), m3.currentVolUm3());
            for (int i = 0; i < n3; i++) u[i].moveThing();
        }
    }

    private static void setEnvDt(double dt) throws Exception { setVal("deltaT", dt); }

    // -(E(+h) - E(-h)) / (2h) along axis for this vertex, in Newtons.
    private static double central(Membrane m, MembraneVertex vt, int axis, double h) {
        double x = vt.getCoordX(), y = vt.getCoordY(), z = vt.getCoordZ();
        double ep, em;
        if (axis == 0) { vt.setCoord(x+h,y,z); ep=m.totalEnergy(); vt.setCoord(x-h,y,z); em=m.totalEnergy(); }
        else if (axis == 1) { vt.setCoord(x,y+h,z); ep=m.totalEnergy(); vt.setCoord(x,y-h,z); em=m.totalEnergy(); }
        else { vt.setCoord(x,y,z+h); ep=m.totalEnergy(); vt.setCoord(x,y,z-h); em=m.totalEnergy(); }
        vt.setCoord(x,y,z);                       // restore
        return -(ep - em) / (2.0 * h) * 1.0e6;    // J/um -> N
    }

    private static double mEdge(Membrane m) {
        // mean edge length in microns (from the first few edges).
        double s = 0; int n = Math.min(50, m.ne);
        for (int e = 0; e < n; e++) {
            int p = m.edgeVert[2*e], q = m.edgeVert[2*e+1];
            double dx=m.vx(p)-m.vx(q), dy=m.vy(p)-m.vy(q), dz=m.vz(p)-m.vz(q);
            s += Math.sqrt(dx*dx+dy*dy+dz*dz);
        }
        return s / n;
    }

    private static void setVal(String label, double val) throws Exception {
        java.lang.reflect.Field f = Env.class.getDeclaredField(label);
        f.setAccessible(true);
        Object p = f.get(null);
        java.lang.reflect.Method sv = p.getClass().getMethod("setValue", double.class);
        sv.invoke(p, val);
    }
}
