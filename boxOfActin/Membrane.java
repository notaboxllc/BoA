package boxOfActin;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A DTS (dynamically-triangulated-surface) membrane: a fluid lipid bilayer modelled as
 * a closed triangulated sphere. Stage 1 = geometry + data structure + render only; the
 * bending / area / volume / linker forces are later stages (see MEMBRANE_DTS_DESIGN.md).
 *
 * <h3>GPU/ECS-ready flat SoA — NOT a half-edge (hard requirement)</h3>
 * The canonical, device-portable topology is flat {@code int[]} index arrays, mirroring
 * BoA's existing GPU residency model (per-element kernels + per-vertex Jacobi gather +
 * host-patched POSE_DELTA scatter for topology deltas). There is no pointer-based
 * half-edge anywhere in this object.
 *
 * <ul>
 *   <li>{@link #faceVert} — {@code 3*nf} CCW vertex indices (outward normals).</li>
 *   <li><b>wing-edges</b> (flat analog of a half-edge): {@link #edgeVert} {@code 2*ne}
 *       endpoints, {@link #edgeFace} {@code 2*ne} the two flanking faces, {@link #edgeWing}
 *       {@code 2*ne} the two opposite/apex vertices — everything dihedral bending and
 *       edge-flips need, with zero pointer chasing.</li>
 *   <li>per-vertex incidence for the gather: fixed-width {@link #vertEdge} {@code nv*maxVal}
 *       + {@link #vertEdgeCt} {@code nv}.</li>
 * </ul>
 *
 * Vertex pose is NOT duplicated here: it lives in the existing Thing SoA, accessed through
 * the {@link MembraneVertex} handles in {@link #vert} (which read live pose via the move
 * kernels). Multi-instance: a sim may hold several Membranes (see {@link #theMembranes}).
 */
public final class Membrane {

    /** All membranes in the sim. No global-membrane assumption: this is a registry. */
    public static final ArrayList<Membrane> theMembranes = new ArrayList<>();
    private static int nextMembraneId = 0;

    // ---- identity / build params ----
    public final int membraneId;
    public final int nu;          // icosphere subdivision level
    public final double radius;    // initial sphere radius (microns)
    public final Pt3D center;      // initial center (microns)

    // ---- counts ----
    public final int nv;           // vertices
    public final int nf;           // faces
    public final int ne;           // edges  (= 3*nf/2 for a closed manifold)
    public final int maxVal;       // fixed width of the vertex-incidence array

    // ---- flat SoA topology (device-portable; kernel-shaped) ----
    public final int[] faceVert;   // 3*nf : CCW vertex indices per face
    public final int[] edgeVert;   // 2*ne : the two endpoint vertices (lo, hi)
    public final int[] edgeFace;   // 2*ne : the two faces flanking the edge
    public final int[] edgeWing;   // 2*ne : the two apex (opposite) vertices, one per flanking face
    public final int[] vertEdge;   // nv*maxVal : incident edge indices (fixed width)
    public final int[] vertEdgeCt; // nv : valence (count of valid entries per row)

    // ---- vertices as Things (host handles; SoA pose is canonical) ----
    public final MembraneVertex[] vert;   // nv

    // ---- physical params (stored now; consumed by later force stages) ----
    public double vertexRadius;    // microns; ~half the edge spacing (for drag + sterics)
    public double l0;              // mean initial edge length (microns)
    public double area0;           // initial total surface area (um^2)
    public double vol0;            // initial enclosed volume (um^3)
    public double kappaBend = Env.dtsKappaBend.getValue();   // bending rigidity (J)
    public double spontCurv = 0.0;                            // spontaneous curvature C̄ (0 = symmetric bilayer)

    // ---- Stage-2 force scratch (allocated once; all SI: positions in metres) ----
    // Geometry/force kernels (§2): per-face normal+area, per-edge length+dihedral, per-vertex
    // gather of curvature c_v + area A_v, then per-edge / per-face force scatter into fX/fY/fZ.
    private double[] mPx, mPy, mPz;          // vertex positions, metres
    private double[] fNx, fNy, fNz, fArea;   // per-face unit normal + area (m^2)
    private double[] eLen, eTheta;           // per-edge length (m) + signed dihedral angle
    private double[] vA, vC, vAlpha, vBeta;  // per-vertex area, curvature, dE/dc, dE/dA
    private double[] fX, fY, fZ;             // per-vertex force accumulator (N)
    private double areaTot, volTot;          // current total area (m^2) / signed volume (m^3)
    private double energyBend, energyArea, energyVol;   // last-computed energy components (J)

    // ---- Surface chemistry: activated Arp2/3 field, diffused over the wing-edge graph (Stage 3) ----
    public double[] arpLocal;                // nv: activated Arp2/3 concentration per vertex (uM); null = off
    public boolean[] arpHot;                 // nv: NPF (hot-Rho) activator-patch flag
    private double[] arpNext, arpLap;        // Jacobi scratch (per-vertex)
    public double[] forminLocal;             // nv: depletable formin pool at hot vertices; null = off

    /**
     * Build a closed icosphere membrane and register it. Creates the vertex Things,
     * derives the wing-edge and vertex-incidence arrays from the faces, and records the
     * reference area/volume. No forces are attached (Stage 1).
     */
    public Membrane(int nu, double radius, Pt3D center) {
        this.membraneId = nextMembraneId++;
        this.nu = nu;
        this.radius = radius;
        this.center = new Pt3D(center);

        Icosphere.Geom geom = Icosphere.build(nu);
        this.nv = geom.nv;
        this.nf = geom.nf;
        this.ne = 3 * nf / 2;          // Euler, closed orientable manifold (verified below)
        this.faceVert = geom.face.clone();

        // Scaled/placed vertex positions: center + radius * unit-sphere direction.
        double[] px = new double[nv], py = new double[nv], pz = new double[nv];
        for (int i = 0; i < nv; i++) {
            px[i] = center.x + radius * geom.vert[3 * i];
            py[i] = center.y + radius * geom.vert[3 * i + 1];
            pz[i] = center.z + radius * geom.vert[3 * i + 2];
        }

        // Mean edge length (over face-corner pairs; each edge counted twice — fine for a mean).
        double sumLen = 0.0;
        int pairs = 0;
        for (int f = 0; f < nf; f++) {
            int a = faceVert[3 * f], b = faceVert[3 * f + 1], c = faceVert[3 * f + 2];
            sumLen += dist(px, py, pz, a, b) + dist(px, py, pz, b, c) + dist(px, py, pz, c, a);
            pairs += 3;
        }
        this.l0 = sumLen / pairs;
        this.vertexRadius = (Env.dtsVertexRadiusFrac.getValue() > 0 ? Env.dtsVertexRadiusFrac.getValue() : 0.5) * l0;

        // Create the vertex Things (registered into theThings/theNodes/SoA by the ctor chain).
        this.vert = new MembraneVertex[nv];
        for (int i = 0; i < nv; i++) {
            vert[i] = new MembraneVertex(new Pt3D(px[i], py[i], pz[i]), vertexRadius, this, i);
        }

        // Derive wing-edges + vertex incidence from the faces.
        int[] valence = new int[nv];
        ArrayList<int[]> edges = deriveEdges(valence);   // each: {lo, hi, face0, face1, wing0, wing1}
        if (edges.size() != ne) {
            throw new IllegalStateException("membrane edge count " + edges.size() + " != expected " + ne
                    + " (non-manifold or open mesh?)");
        }
        this.edgeVert = new int[2 * ne];
        this.edgeFace = new int[2 * ne];
        this.edgeWing = new int[2 * ne];
        for (int e = 0; e < ne; e++) {
            int[] rec = edges.get(e);
            edgeVert[2 * e] = rec[0]; edgeVert[2 * e + 1] = rec[1];
            edgeFace[2 * e] = rec[2]; edgeFace[2 * e + 1] = rec[3];
            edgeWing[2 * e] = rec[4]; edgeWing[2 * e + 1] = rec[5];
        }

        int mv = 0;
        for (int i = 0; i < nv; i++) mv = Math.max(mv, valence[i]);
        this.maxVal = mv;
        this.vertEdge = new int[nv * maxVal];
        this.vertEdgeCt = new int[nv];
        for (int e = 0; e < ne; e++) {
            addIncidence(edgeVert[2 * e], e);
            addIncidence(edgeVert[2 * e + 1], e);
        }

        // Reference area / volume from the initial (= current) geometry.
        this.area0 = totalArea();
        this.vol0 = enclosedVolume();

        // Sanity: Euler characteristic of a sphere is 2.
        int euler = nv - ne + nf;
        if (euler != 2) {
            throw new IllegalStateException("membrane Euler V-E+F = " + euler + " (expected 2 for a sphere)");
        }

        theMembranes.add(this);
        if (Env.dtsArpOn.isActive() || Env.dtsForminOn.isActive()) {
            markHotPatches();
            if (Env.dtsArpOn.isActive())    initArp();
            if (Env.dtsForminOn.isActive()) initFormin();
        }
        report();
    }

    // --- vertex incidence helper ---
    private void addIncidence(int v, int e) {
        int base = v * maxVal;
        vertEdge[base + vertEdgeCt[v]] = e;
        vertEdgeCt[v]++;
    }

    /**
     * Derive the wing-edge records from {@link #faceVert}. For each undirected edge {u,v}
     * shared by exactly two faces, records the two flanking faces and the two apex (opposite)
     * vertices. Also fills the per-vertex valence count. Returns one int[6] per edge:
     * {lo, hi, face0, face1, wing0, wing1}.
     */
    private ArrayList<int[]> deriveEdges(int[] valence) {
        HashMap<Long, int[]> map = new HashMap<>();   // edge key -> mutable record
        ArrayList<int[]> order = new ArrayList<>();    // stable insertion order
        for (int f = 0; f < nf; f++) {
            int a = faceVert[3 * f], b = faceVert[3 * f + 1], c = faceVert[3 * f + 2];
            registerCorner(map, order, a, b, c, f);   // edge (a,b), apex c
            registerCorner(map, order, b, c, a, f);   // edge (b,c), apex a
            registerCorner(map, order, c, a, b, f);   // edge (c,a), apex b
        }
        // Validate manifoldness and tally valence.
        for (int[] rec : order) {
            if (rec[6] != 2) {
                throw new IllegalStateException("membrane edge (" + rec[0] + "," + rec[1] + ") borders "
                        + rec[6] + " faces (expected 2)");
            }
            valence[rec[0]]++;
            valence[rec[1]]++;
        }
        return order;
    }

    // record layout: {lo, hi, face0, face1, wing0, wing1, count}
    private void registerCorner(HashMap<Long, int[]> map, ArrayList<int[]> order,
                                int p, int q, int apex, int face) {
        int lo = Math.min(p, q), hi = Math.max(p, q);
        long key = (long) lo * nv + hi;
        int[] rec = map.get(key);
        if (rec == null) {
            rec = new int[]{lo, hi, face, -1, apex, -1, 1};
            map.put(key, rec);
            order.add(rec);
        } else {
            if (rec[6] == 1) {          // second face
                rec[3] = face;
                rec[5] = apex;
            }
            rec[6]++;
        }
    }

    // --- geometry readouts (read LIVE pose from vertex Things) ---

    /** Current x of vertex i (live SoA pose). */
    public double vx(int i) { return vert[i].getCoordX(); }
    public double vy(int i) { return vert[i].getCoordY(); }
    public double vz(int i) { return vert[i].getCoordZ(); }

    /** Total surface area = sum of triangle areas, using current vertex positions (um^2). */
    public double totalArea() {
        double area = 0.0;
        for (int f = 0; f < nf; f++) {
            int a = faceVert[3 * f], b = faceVert[3 * f + 1], c = faceVert[3 * f + 2];
            double abx = vx(b) - vx(a), aby = vy(b) - vy(a), abz = vz(b) - vz(a);
            double acx = vx(c) - vx(a), acy = vy(c) - vy(a), acz = vz(c) - vz(a);
            double cx = aby * acz - abz * acy;
            double cy = abz * acx - abx * acz;
            double cz = abx * acy - aby * acx;
            area += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return area;
    }

    /** Signed enclosed volume V = (1/6) sum r_a.(r_b x r_c) over CCW faces (um^3). */
    public double enclosedVolume() {
        double v6 = 0.0;
        for (int f = 0; f < nf; f++) {
            int a = faceVert[3 * f], b = faceVert[3 * f + 1], c = faceVert[3 * f + 2];
            double bx = vx(b), by = vy(b), bz = vz(b);
            double cx = vx(c), cy = vy(c), cz = vz(c);
            double crx = by * cz - bz * cy;
            double cry = bz * cx - bx * cz;
            double crz = bx * cy - by * cx;
            v6 += vx(a) * crx + vy(a) * cry + vz(a) * crz;
        }
        return v6 / 6.0;
    }

    private static double dist(double[] x, double[] y, double[] z, int i, int j) {
        double dx = x[i] - x[j], dy = y[i] - y[j], dz = z[i] - z[j];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void report() {
        Thing.talkln(String.format(
            "[DTS] membrane #%d built: nu=%d  nv=%d  nf=%d  ne=%d  maxVal=%d  R=%.3f um  l0=%.4f um  rVert=%.4f um  A0=%.4f um^2  V0=%.4f um^3",
            membraneId, nu, nv, nf, ne, maxVal, radius, l0, vertexRadius, area0, vol0));
        // Continuum cross-checks: sphere area 4 pi R^2, volume (4/3) pi R^3.
        double aSphere = 4 * Math.PI * radius * radius;
        double vSphere = (4.0 / 3.0) * Math.PI * radius * radius * radius;
        Thing.talkln(String.format(
            "[DTS]   continuum sphere: A=%.4f (mesh %.1f%%)  V=%.4f (mesh %.1f%%)",
            aSphere, 100 * area0 / aSphere, vSphere, 100 * vol0 / vSphere));
    }

    /**
     * IC factory: build a DTS membrane from the {@code dtsMembrane*} parameters. Called from
     * {@link BoxOfActin#begin} when {@code buildDtsMembrane} is active.
     */
    public static void buildIcosphereMembrane() {
        int nu = Env.dtsMembraneSubdiv.getIntValue();
        double r = Env.dtsMembraneRadius.getValue();
        new Membrane(nu, r, new Pt3D(0, 0, 0));
    }

    // ================================================================================
    // Stage 2 — bending (Julicher edge form) + area + volume forces.
    //   E_bend = kappa * Sum_v 2 (c_v/A_v - C0/2)^2 A_v,  c_v = (1/4) Sum_{e in v} l_e theta_e,
    //            A_v = (1/3) Sum_{f in v} area(f)            [validated -> 8*pi*kappa on a sphere]
    //   E_area = (K_A/2)(A_tot - A0)^2 / A0
    //   E_vol  = (K_V/2)(V/V0 - vt)^2
    // Forces are -dE/dr (analytic, TriMem gradient cribbed from the paper), assembled as
    // per-edge / per-face scatter into a per-vertex accumulator, then added to soaForceSum.
    // All geometry is done in METRES so forces come out in Newtons with no unit fudge factor.
    // ================================================================================

    /** Optional constant-force probe pushed into the membrane (demo of membrane mechanics; a simple
     *  stand-in for Stage-3 actin steric push). A plain ProteinNode, NOT a MembraneVertex. */
    public static ProteinNode dtsProbe = null;

    /** Drive every membrane's Stage-2 forces into its vertices' soaForceSum (pre-move hook), then any demo push. */
    public static void computeAllForces() {
        for (int m = 0; m < theMembranes.size(); m++) {
            Membrane mem = theMembranes.get(m);
            mem.computeForces();
            if (Env.dtsPushForce.getValue() != 0) mem.applyPushPatch();
            if (mem.arpLocal != null) mem.diffuseArp();    // surface chemistry (reaction-diffusion)
            if (mem.forminLocal != null) mem.forminStep(); // formin nucleation of mother filaments
        }
        if (dtsProbe != null && !theMembranes.isEmpty()) theMembranes.get(0).applyProbeForces(dtsProbe);
        if (!bouncers.isEmpty() && !theMembranes.isEmpty()) theMembranes.get(0).applyBouncers();
        if (Env.dtsBranchOn.getValue() != 0) branchAllActin();        // Arp2/3 branching (gated by local Arp)
        if (Env.dtsActinCollide.getValue() != 0) collideAllActin();   // actin <-> membrane (containment + push)
    }

    // ---- Bouncers: free nodes that shoot around inside the membrane, ricochet off it (pushing transient
    //      bulges), and randomly change direction -- a live demo of membrane response + relaxation. ----
    static final class Bouncer { ProteinNode node; double dx, dy, dz; }   // node + current drive direction
    public static final java.util.ArrayList<Bouncer> bouncers = new java.util.ArrayList<>();

    public static void createDtsBouncers() {
        int n = Env.dtsBouncerCount.getIntValue();
        if (n <= 0 || theMembranes.isEmpty()) return;
        Membrane m = theMembranes.get(0);
        double minR = Env.dtsBouncerMinR.getValue(), maxR = Env.dtsBouncerMaxR.getValue();
        double place = 0.55 * m.radius;     // start well inside the shell
        for (int i = 0; i < n; i++) {
            double rr = minR + (maxR - minR) * Env.mtRNG.nextDouble();
            // random position inside a sphere of radius `place` about the membrane center
            double qx, qy, qz, s;
            do { qx = 2*Env.mtRNG.nextDouble()-1; qy = 2*Env.mtRNG.nextDouble()-1; qz = 2*Env.mtRNG.nextDouble()-1;
                 s = qx*qx+qy*qy+qz*qz; } while (s > 1 || s < 1e-4);
            Bouncer b = new Bouncer();
            b.node = new ProteinNode(new Pt3D(m.center.x + place*qx, m.center.y + place*qy, m.center.z + place*qz), rr);
            double[] d = randUnit();
            b.dx = d[0]; b.dy = d[1]; b.dz = d[2];
            bouncers.add(b);
        }
        Thing.talkln("[DTS-BOUNCE] created " + n + " bouncers, r in [" + minR + "," + maxR + "] um, drive="
                + Env.dtsBouncerForce.getValue() + " N");
    }

    /** Per-step: drive each bouncer along its direction; ricochet off the membrane on contact; otherwise
     *  randomly re-aim with probability dtsBouncerTurnProb. The steric push bulges the membrane transiently. */
    void applyBouncers() {
        double F = Env.dtsBouncerForce.getValue();
        double turnP = Env.dtsBouncerTurnProb.getValue();
        double[] reac = new double[3];
        double rmaxBase = radius;     // membrane nominal radius
        for (int i = 0; i < bouncers.size(); i++) {
            Bouncer b = bouncers.get(i);
            double rmag = stericNodeVsMembrane(b.node, reac);   // soft spring: pushes a bulge where it touches
            // Radial backstop: a hard wall just inside the shell so a bouncer can never punch through; on
            // hitting it, reflect the drive direction inward (a ricochet). The steric above already bulged
            // the membrane on the way in; the bounce sends it off and the bulge relaxes behind it.
            double dx = b.node.getCoordX()-center.x, dy = b.node.getCoordY()-center.y, dz = b.node.getCoordZ()-center.z;
            double cr = Math.sqrt(dx*dx + dy*dy + dz*dz);
            // Backstop tracks the LOCAL membrane surface (stericContactMaxR, set by the steric call above):
            // a bouncer may reach and bulge the membrane, but its surface can never get past the local wall
            // (which moves out as it pushes). Falls back to the nominal radius when not in contact.
            double wall = (stericContactMaxR > 0) ? stericContactMaxR : rmaxBase;
            double rmax = wall - b.node.getRadius();
            if (cr > rmax && cr > 1e-9) {
                double nx = dx/cr, ny = dy/cr, nz = dz/cr;      // outward radial unit
                double over = (cr - rmax) * 1.0e-6;             // m
                double mag = 0.2 * over;                        // stiff inward backstop spring (N)
                b.node.incForceSumSlot(-mag*nx, -mag*ny, -mag*nz);
                double dot = b.dx*nx + b.dy*ny + b.dz*nz;
                if (dot > 0) {                                  // heading outward -> reflect inward (bounce)
                    b.dx -= 2*dot*nx; b.dy -= 2*dot*ny; b.dz -= 2*dot*nz;
                    double s = 1.0/Math.sqrt(b.dx*b.dx + b.dy*b.dy + b.dz*b.dz);
                    b.dx*=s; b.dy*=s; b.dz*=s;
                }
            } else if (Env.mtRNG.nextDouble() < turnP) {
                double[] d = randUnit(); b.dx=d[0]; b.dy=d[1]; b.dz=d[2];   // random re-aim in open space
            }
            b.node.incForceSumSlot(F*b.dx, F*b.dy, F*b.dz);
            if (Env.counter % 1000 == 0) bounceReacDbg += rmag;
        }
        if (Env.counter % 1000 == 0) {
            Thing.talkln(String.format("[DTS-BOUNCE] step %d  sumReaction=%.3e N (drive=%.2e x %d)  bulge rStd=%.4f",
                    Env.counter, bounceReacDbg, F, bouncers.size(), radialStd()));
            bounceReacDbg = 0;
        }
    }
    private double bounceReacDbg = 0;
    private double radialStd() {
        double m = 0; for (int v=0; v<nv; v++) m += Math.sqrt(vx(v)*vx(v)+vy(v)*vy(v)+vz(v)*vz(v)); m/=nv;
        double s = 0; for (int v=0; v<nv; v++){ double r=Math.sqrt(vx(v)*vx(v)+vy(v)*vy(v)+vz(v)*vz(v)); s+=(r-m)*(r-m);}
        return Math.sqrt(s/nv);
    }

    private static double[] randUnit() {
        double x, y, z, s;
        do { x = 2*Env.mtRNG.nextDouble()-1; y = 2*Env.mtRNG.nextDouble()-1; z = 2*Env.mtRNG.nextDouble()-1;
             s = x*x+y*y+z*z; } while (s > 1 || s < 1e-4);
        double inv = 1.0/Math.sqrt(s);
        return new double[]{x*inv, y*inv, z*inv};
    }

    /**
     * Push-patch protrusion demo: spread a constant outward (+x) force over the cap of vertices within
     * dtsPushCapDeg of the +x pole. A localized drive (clean stand-in for actin pushing) — the bulge grows
     * and STALLS when the membrane's bending + area + volume reaction balances the push. Added on top of the
     * membrane forces (computeForces already wrote them).
     */
    void applyPushPatch() {
        double total = Env.dtsPushForce.getValue();
        double cosCap = Math.cos(Math.toRadians(Env.dtsPushCapDeg.getValue()));
        // collect the cap (vertices whose direction from the membrane center is within the cap of +x).
        int cap = 0;
        for (int v = 0; v < nv; v++) {
            double rx = vx(v) - center.x, ry = vy(v) - center.y, rz = vz(v) - center.z;
            double r = Math.sqrt(rx*rx + ry*ry + rz*rz);
            if (r > 0 && rx/r >= cosCap) cap++;
        }
        if (cap == 0) return;
        double per = total / cap;                       // spread equally over the cap
        double bulge = 0;
        for (int v = 0; v < nv; v++) {
            double rx = vx(v) - center.x, ry = vy(v) - center.y, rz = vz(v) - center.z;
            double r = Math.sqrt(rx*rx + ry*ry + rz*rz);
            if (r > 0 && rx/r >= cosCap) {
                vert[v].incForceSumSlot(per, 0, 0);     // constant +x push
                if (vx(v) > bulge) bulge = vx(v);
            }
        }
        if (Env.counter % 500 == 0) {
            Thing.talkln(String.format("[DTS-PUSH] step %d  capVerts=%d  forcePerVert=%.3e N  poleX=%.4f  bulgeR=%.4f  (R0=%.3f)",
                    Env.counter, cap, per, bulge, maxRadiusAlongX(), radius));
        }
    }

    // ================================================================================
    // Surface chemistry — activated Arp2/3 reaction-diffusion on the membrane.
    //   c_i += alpha*Sum_{edges e at i}(c_j - c_i)  +  [hot_i ? ke*target : 0]  -  ke*c_i
    // Kernel-shaped (per-edge gather into a per-vertex Laplacian, then per-vertex update) — the same
    // gather pattern as the bending forces, so it ports to the GPU as "register the kernels".
    // ================================================================================

    /** Mark NPF (hot-Rho) activator patches on cube-corner directions (off the coordinate singularities,
     *  like the legacy StickyNode.markHotPatches). Shared by the Arp field and formin nucleation. */
    private void markHotPatches() {
        if (arpHot != null) return;
        arpHot = new boolean[nv];
        int nP = Math.max(0, Math.min(8, Env.dtsArpHotPatches.getIntValue()));
        double cosCap = Math.cos(Math.toRadians(Env.dtsArpHotPatchDeg.getValue()));
        double c = 0.5773502692;
        double[][] dirs = {{c,c,c},{c,c,-c},{c,-c,c},{c,-c,-c},{-c,c,c},{-c,c,-c},{-c,-c,c},{-c,-c,-c}};
        int marked = 0;
        for (int v = 0; v < nv; v++) {
            double rx = vx(v)-center.x, ry = vy(v)-center.y, rz = vz(v)-center.z;
            double r = Math.sqrt(rx*rx+ry*ry+rz*rz);
            if (r < 1e-9) continue;
            rx/=r; ry/=r; rz/=r;
            for (int p = 0; p < nP; p++) {
                if (rx*dirs[p][0] + ry*dirs[p][1] + rz*dirs[p][2] >= cosCap) { arpHot[v] = true; marked++; break; }
            }
        }
        Thing.talkln("[DTS-HOT] " + nP + " NPF patches, " + marked + " hot vertices of " + nv);
    }

    private void initArp() {
        arpLocal = new double[nv]; arpNext = new double[nv]; arpLap = new double[nv];
        double target = Env.dtsArpTarget.getValue();
        for (int v = 0; v < nv; v++) if (arpHot[v]) arpLocal[v] = target;
    }

    private void initFormin() {
        forminLocal = new double[nv];
        double pool = Env.dtsForminPool.getValue();
        for (int v = 0; v < nv; v++) if (arpHot[v]) forminLocal[v] = pool;
    }

    /** One reaction-diffusion step of the activated-Arp2/3 field (Jacobi, kernel-shaped). */
    public void diffuseArp() {
        if (arpLocal == null) return;
        double alpha = Env.dtsArpDiffusion.getValue();   // per-step graph diffusion
        double ke = Env.dtsArpDecay.getValue();          // per-step decay / production rate
        double target = Env.dtsArpTarget.getValue();

        // per-EDGE gather -> per-vertex Laplacian  (Sum_neighbors (c_j - c_i))
        java.util.Arrays.fill(arpLap, 0.0);
        for (int e = 0; e < ne; e++) {
            int p = edgeVert[2*e], q = edgeVert[2*e+1];
            double diff = arpLocal[q] - arpLocal[p];
            arpLap[p] += diff; arpLap[q] -= diff;
        }
        // per-VERTEX update. Hot (NPF) vertices are a clamped source (held at target); elsewhere the field
        // diffuses and decays -> a halo of length ~sqrt(alpha/ke) edges around each patch.
        for (int v = 0; v < nv; v++) {
            if (arpHot[v]) { arpNext[v] = target; continue; }
            double c = arpLocal[v] + alpha*arpLap[v] - ke*arpLocal[v];
            arpNext[v] = (c < 0) ? 0 : c;
        }
        double[] tmp = arpLocal; arpLocal = arpNext; arpNext = tmp;

        if (Env.counter % 500 == 0) {
            double mx = 0, sum = 0; int nz = 0;
            for (int v = 0; v < nv; v++) { mx = Math.max(mx, arpLocal[v]); sum += arpLocal[v]; if (arpLocal[v] > 0.01*target) nz++; }
            Thing.talkln(String.format("[DTS-ARP] step %d  maxConc=%.4f  meanConc=%.4f  spread=%d/%d verts >1%%",
                    Env.counter, mx, sum/nv, nz, nv));
        }
    }

    private int forminMotherCt = 0;   // running count, for the readout

    /**
     * Formin nucleation step: at each NPF (hot) vertex, recover the formin pool, then with probability
     * proportional to the remaining pool seed a LINEAR mother filament just inside the cortex, anchored to
     * the vertex by an ERM-like end1 tether (FilSegment.linkEnd1Node). Each mother spends a formin quantum
     * (depletion caps mothers per zone). Ported from the legacy StickyNode.deNovoNucleate onto DTS vertices.
     * Runs every step; the per-step probability uses deltaT so the rate is cadence-correct.
     */
    public void forminStep() {
        if (forminLocal == null) return;
        double pool = Env.dtsForminPool.getValue();
        double rate = Env.dtsForminNucRate.getValue();
        double recover = Env.dtsForminRecover.getValue();
        double consume = Env.dtsForminConsume.getValue();
        double dt = Env.deltaT.getValue();
        double tipR = Env.filTipRadiusForCollisions.getValue();
        double seedDepth = vertexRadius + (tipR > 0 ? tipR : 0.01) + 0.02;   // seat just inside the cortex
        for (int v = 0; v < nv; v++) {
            if (!arpHot[v]) continue;
            if (recover > 0 && forminLocal[v] < pool) forminLocal[v] = Math.min(pool, forminLocal[v] + recover*dt);
            double avail = pool > 0 ? forminLocal[v] : 0.0;
            if (avail <= 0) continue;
            double prob = rate * (avail/pool) * dt;
            if (Thing.currentScratch().rng.nextDouble() >= prob) continue;
            // inward = geometric radial (vertex -> center); grow the mother radially inward (barbed into the
            // cytoplasm), pointed end held at the inner steric face by the vertex tether.
            double ix = center.x - vx(v), iy = center.y - vy(v), iz = center.z - vz(v);
            double il = Math.sqrt(ix*ix+iy*iy+iz*iz);
            if (il < 1e-9) continue;
            ix/=il; iy/=il; iz/=il;                              // inward radial (vertex -> center)
            double halfLen = 0.5*(Env.actinSeed.getIntValue()+1)*Env.actinMonoRadius;   // seed half-length (um)
            FilSegment mother;
            if (Env.dtsForminGrowOut.isActive()) {
                // FORMIN holds the BARBED end (end2) at the cortex; pointed end (end1) trails into the
                // cytoplasm. Seat the center so end2 (= center + halfLen*uVec, uVec=outward) lands AT the
                // vertex (zero initial anchor offset); link the BARBED end to the vertex (formin-membrane bond).
                Pt3D nucPt = new Pt3D(vx(v) + halfLen*ix, vy(v) + halfLen*iy, vz(v) + halfLen*iz);
                mother = FilSegment.makeForminMother(nucPt, new Pt3D(-ix, -iy, -iz));   // uVec outward -> barbed toward cortex
                FilSegment.linkEnd2Node(mother, vert[v]);        // formin holds the barbed end at the membrane
            } else {
                // de-novo mother: pointed end held at the cortex, grows inward (the legacy geometry).
                Pt3D nucPt = new Pt3D(vx(v) + seedDepth*ix, vy(v) + seedDepth*iy, vz(v) + seedDepth*iz);
                mother = FilSegment.makeForminMother(nucPt, new Pt3D(ix, iy, iz));
                FilSegment.linkEnd1Node(mother, vert[v]);        // ERM-like anchor (pointed end)
            }
            forminLocal[v] -= consume;
            if (forminLocal[v] < 0) forminLocal[v] = 0;
            forminMotherCt++;
        }
        if (Env.counter % 500 == 0) {
            double remain = 0; int active = 0;
            for (int v = 0; v < nv; v++) if (arpHot[v]) { remain += forminLocal[v]; if (forminLocal[v] > 0) active++; }
            Thing.talkln(String.format("[DTS-FORMIN] step %d  mothers nucleated=%d  pool remaining=%.1f over %d hot verts",
                    Env.counter, forminMotherCt, remain, active));
        }
    }

    /** Collide all actin FilSegments against the (first) membrane each step: containment + push (the reaction
     *  bulges the membrane), plus the polymerization ratchet on barbed tips. Grid-accelerated. */
    public static void collideAllActin() {
        if (theMembranes.isEmpty() || FilSegment.filSegmentCt == 0) return;
        theMembranes.get(0).collideActin();
    }

    // Anchor-spring magnitude (N), capped so a large offset (e.g. a freshly nucleated mother before the
    // spring relaxes) can never deliver a NaN-inducing kick. k is N/m; offset (ax,ay,az) is in microns,
    // so the linear force is k * (offset_um * 1e-6). Capped at ANCHOR_FMAX.
    private static final double ANCHOR_FMAX = 1.0e-10;   // ~0.1 pN ceiling on the formin-membrane tether
    private static double anchorMag(double k, double ax, double ay, double az) {
        double off = Math.sqrt(ax*ax+ay*ay+az*az);       // microns
        double f = k * off * 1.0e-6;                     // N
        return f > ANCHOR_FMAX ? ANCHOR_FMAX : f;
    }

    void collideActin() {
        buildFaceGrid();
        double rad = FilSegment.radius;
        double ratchet = Env.dtsRatchetForce.getValue();
        double kAnchor = Env.dtsAnchorStiffness.getValue();
        double[] r1 = new double[3], r2 = new double[3];
        Pt3D force = new Pt3D(), pt = new Pt3D();
        int contacts = 0;
        for (int i = 0; i < FilSegment.filSegmentCt; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            double e1x=fs.getEnd1X(), e1y=fs.getEnd1Y(), e1z=fs.getEnd1Z();
            double e2x=fs.getEnd2X(), e2y=fs.getEnd2Y(), e2z=fs.getEnd2Z();
            if (!(Double.isFinite(e1x)&&Double.isFinite(e2x)&&Double.isFinite(e1y)&&Double.isFinite(e2y)
                  &&Double.isFinite(e1z)&&Double.isFinite(e2z))) { fs.removeMe = true; continue; }  // cull NaN filaments
            if (kAnchor > 0) {
                // FORMIN holds the BARBED end (end2) at the cortex: a TWO-SIDED spring between end2 and its
                // vertex. As the barbed end elongates and advances against the membrane, the spring pulls the
                // vertex OUTWARD (the formin/filopodial push); the vertex (cortex) pulls the barbed end back,
                // keeping it at the membrane. This is the formin-membrane attachment doing work as it grows.
                if (fs.nodeAtEnd2 && fs.end2Node != null && !fs.end2Node.removeMe) {
                    ProteinNode an = fs.end2Node;
                    double ax = (an.getCoordX()-e2x), ay = (an.getCoordY()-e2y), az = (an.getCoordZ()-e2z);
                    double m = anchorMag(kAnchor, ax, ay, az);   // N, capped
                    double off = Math.sqrt(ax*ax+ay*ay+az*az); if (off < 1e-12) off = 1;
                    double fxA=m*ax/off, fyA=m*ay/off, fzA=m*az/off;
                    force.setVals(fxA,fyA,fzA); pt.setVals(e2x,e2y,e2z); fs.incForceSum(force, pt);   // barbed end -> vertex
                    an.incForceSumSlot(-fxA,-fyA,-fzA);                                                // vertex -> barbed end (push cortex out)
                }
                // de-novo (pointed-end) mother: one-sided tether (don't load the cortex inward).
                else if (fs.nodeAtEnd1 && fs.end1Node != null && !fs.end1Node.removeMe) {
                    ProteinNode an = fs.end1Node;
                    double ax = (an.getCoordX()-e1x), ay = (an.getCoordY()-e1y), az = (an.getCoordZ()-e1z);
                    double m = anchorMag(kAnchor, ax, ay, az);
                    double off = Math.sqrt(ax*ax+ay*ay+az*az); if (off < 1e-12) off = 1;
                    force.setVals(m*ax/off, m*ay/off, m*az/off); pt.setVals(e1x,e1y,e1z); fs.incForceSum(force, pt);
                }
            }
            double gSeg = fs.bTransGam.y;                              // perpendicular filament drag
            segmentVsMembrane(e1x,e1y,e1z, e2x,e2y,e2z, rad, gSeg, r1, r2);
            boolean hit = false;
            if (r1[0]!=0||r1[1]!=0||r1[2]!=0) { force.setVals(r1[0],r1[1],r1[2]); pt.setVals(e1x,e1y,e1z); fs.incForceSum(force,pt); hit=true; }
            if (r2[0]!=0||r2[1]!=0||r2[2]!=0) { force.setVals(r2[0],r2[1],r2[2]); pt.setVals(e2x,e2y,e2z); fs.incForceSum(force,pt); hit=true; }
            if (hit) contacts++;
            // MOGILNER-OSTER coupling: write the barbed tip's clearance to the membrane into end2TipC, which
            // FilSegment.ratchetPolyFactor reads (g = end2TipC - filTipR; growth rate *= exp(-f*(delta-g)/kT)
            // when g < delta). So a barbed tip pressing the cortex polymerizes slowly; when the membrane
            // bulges away (room opens) it grows -- and the steric collision above turns that growth into the
            // membrane push. (Replaces the old faceCollideTipVsNodeTriangles registerATipClearance.)
            int ft = nearestFace(e2x,e2y,e2z, scratchCp);
            if (ft >= 0) {
                double signed = (e2x-scratchCp[0])*fNx[ft] + (e2y-scratchCp[1])*fNy[ft] + (e2z-scratchCp[2])*fNz[ft];
                double clearance = -signed;                 // distance from the barbed tip to the membrane surface (um)
                if (clearance < 0) clearance = 0;
                if (clearance < fs.end2TipC) fs.end2TipC = clearance;
            }
            // Optional constant ratchet push (legacy approximation; default off — the Mogilner-Oster growth
            // above + the steric collision are the physical mechanism).
            if (ratchet > 0 && ft >= 0) {
                double signed = (e2x-scratchCp[0])*fNx[ft] + (e2y-scratchCp[1])*fNy[ft] + (e2z-scratchCp[2])*fNz[ft];
                double band = rad + vertexRadius + 0.03;
                if (signed > -band && signed < band) {
                    force.setVals(ratchet*fNx[ft], ratchet*fNy[ft], ratchet*fNz[ft]);
                    pt.setVals(e2x,e2y,e2z); fs.incForceSum(force, pt);
                }
            }
        }
        if (Env.counter % 500 == 0 && contacts > 0) {
            Thing.talkln(String.format("[DTS-ACTIN] step %d  filaments contacting membrane = %d / %d",
                    Env.counter, contacts, FilSegment.filSegmentCt));
        }
    }

    private int branchCt = 0;

    /** Arp2/3 branch nucleation off membrane-proximal filaments, gated by the local Arp field. A filament
     *  whose barbed tip is near the cortex in a high-Arp region branches: a daughter nucleates at the branch
     *  angle, tilted toward the membrane normal (dendritic geometry). Rate ∝ local Arp; each branch consumes
     *  Arp (negative feedback). Bounded by the Arp field + dtsMaxFilaments cap. */
    public static void branchAllActin() {
        if (theMembranes.isEmpty() || FilSegment.filSegmentCt == 0) return;
        Membrane m = theMembranes.get(0);
        if (m.arpLocal == null) return;
        m.branchStep();
    }

    void branchStep() {
        buildFaceGrid();
        double rate = Env.dtsBranchRate.getValue();
        double consume = Env.dtsArpConsumePerBranch.getValue();
        double ang = Math.toRadians(Env.dtsBranchAngle.getValue());
        double ca = Math.cos(ang), sa = Math.sin(ang);
        double dt = Env.deltaT.getValue();
        double band = vertexRadius + 0.06;
        int cap = Env.dtsMaxFilaments.getIntValue();
        int n0 = FilSegment.filSegmentCt;   // snapshot: a daughter can't branch the same step it's born
        int made = 0;
        int cand=0, nearCortex=0;   // gate counters (pipeline-health diagnostics)
        for (int i = 0; i < n0; i++) {
            if (FilSegment.filSegmentCt >= cap) break;
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            cand++;
            double e2x=fs.getEnd2X(), e2y=fs.getEnd2Y(), e2z=fs.getEnd2Z();   // barbed tip
            int f = nearestFace(e2x,e2y,e2z, scratchCp);
            if (f < 0) continue;
            double signed = (e2x-scratchCp[0])*fNx[f] + (e2y-scratchCp[1])*fNy[f] + (e2z-scratchCp[2])*fNz[f];
            if (signed < -band || signed > band) continue;                   // tip not near the cortex
            nearCortex++;
            int a=faceVert[3*f], b=faceVert[3*f+1], c=faceVert[3*f+2];
            double arp = (arpLocal[a]+arpLocal[b]+arpLocal[c]) / 3.0;
            if (arp <= 0.02) continue;
            if (Thing.currentScratch().rng.nextDouble() >= rate*arp*dt) continue;
            // Real Arp2/3 branch: a daughter nucleates off this mother at a point in its barbed-end zone,
            // CONNECTED via an Arp23 junction (makeArpBranch handles the ~70deg structural angle + the helix
            // tilt + the mother-daughter linkage). The membrane-facing daughters then grow + ratchet + push.
            double bLoc = fs.length - Thing.currentScratch().rng.nextDouble() * Math.min(0.05, 0.6*fs.length);
            if (bLoc < 0.1*fs.length) bLoc = 0.1*fs.length;
            fs.makeArpBranch(bLoc);
            arpLocal[a]=Math.max(0,arpLocal[a]-consume);
            arpLocal[b]=Math.max(0,arpLocal[b]-consume);
            arpLocal[c]=Math.max(0,arpLocal[c]-consume);
            made++; branchCt++;
        }
        if (Env.counter % 1000 == 0) {
            Thing.talkln(String.format("[DTS-BRANCH] step %d  fils=%d nearCortex=%d  totalBranches=%d",
                    Env.counter, cand, nearCortex, branchCt));
        }
    }

    /** Create the constant-force probe inside the (first) membrane, if dtsProbeForce > 0. */
    public static void createDtsProbe() {
        if (Env.dtsProbeForce.getValue() == 0) return;
        double x0 = Env.dtsProbeStartX.getValue();
        dtsProbe = new ProteinNode(new Pt3D(x0, 0, 0), Env.dtsProbeRadius.getValue());
        Thing.talkln(String.format("[DTS-PROBE] created at x=%.3f r=%.3f driveF=%.3e N",
                x0, Env.dtsProbeRadius.getValue(), Env.dtsProbeForce.getValue()));
    }

    /**
     * Constant outward (+x) drive on the probe + drag-coupled steric repulsion between the probe sphere and
     * every membrane vertex it overlaps (push the vertex outward along probe→vertex, equal-and-opposite
     * reaction on the probe). The membrane's bending/area/volume forces resist the bulge; the probe stalls
     * when the integrated reaction balances the drive. Added AFTER computeForces (which owns the vertex
     * force), so the steric push accumulates on top of the membrane forces.
     */
    void applyProbeForces(ProteinNode probe) {
        probe.incForceSumSlot(Env.dtsProbeForce.getValue(), 0, 0);   // constant +x drive
        double[] reac = new double[3];
        double reaction = stericNodeVsMembrane(probe, reac);
        if (Env.counter % 500 == 0) {
            Thing.talkln(String.format("[DTS-PROBE] step %d  x=%.4f  reaction=%.3e N  drive=%.3e N  bulgeR=%.4f",
                    Env.counter, probe.getCoordX(), reaction, Env.dtsProbeForce.getValue(), maxRadiusAlongX()));
        }
    }

    /**
     * Point(sphere)-vs-TRIANGLE steric between a node and this membrane (one-sided, continuous surface —
     * the node can't slip through gaps between vertices the way it does with per-vertex spheres). Pushes the
     * node back inward and the contacted surface outward (reaction distributed to the face vertices by the
     * closest-point barycentric weights). {@code fN*} are the per-face outward normals from the last
     * computeGeometryAndEnergy. Writes the net reaction vector on the node into {@code reacOut} (points
     * inward) and returns its magnitude (0 = no contact).
     */
    double stericNodeVsMembrane(ProteinNode node, double[] reacOut) {
        double pr = node.getRadius();
        double px = node.getCoordX(), py = node.getCoordY(), pz = node.getCoordZ();
        double kSteric = Env.dtsStericStiffness.getValue();    // N/m per contacting vertex (soft spring)
        double contact = pr + vertexRadius;
        double rx = 0, ry = 0, rz = 0;
        double maxContactR = 0;     // radius-from-center of the farthest-out vertex this node is touching
        // Per-VERTEX soft spring: push every membrane vertex the node overlaps outward (away from the node),
        // equal-and-opposite reaction on the node. Pushing the vertices directly (rather than holding the
        // node at a triangle wall) is what actually bulges the surface; the summed reaction contains the
        // node. The node DWELLS in contact (sustained push -> visible bulge), then a re-aim sends it off and
        // the bulge relaxes.
        for (int v = 0; v < nv; v++) {
            double dx = vx(v) - px, dy = vy(v) - py, dz = vz(v) - pz;
            double d2 = dx*dx + dy*dy + dz*dz;
            if (d2 >= contact*contact || d2 < 1e-20) continue;
            double d = Math.sqrt(d2);
            double pen = contact - d;
            double ux = dx/d, uy = dy/d, uz = dz/d;             // node -> vertex (push vertex this way)
            double mag = kSteric * pen * 1.0e-6;                // soft penetration spring (pen um -> m), N
            vert[v].incForceSumSlot(mag*ux, mag*uy, mag*uz);    // push vertex outward (bulge)
            node.incForceSumSlot(-mag*ux, -mag*uy, -mag*uz);    // reaction on node (contain)
            rx -= mag*ux; ry -= mag*uy; rz -= mag*uz;
            double cvx = vx(v)-center.x, cvy = vy(v)-center.y, cvz = vz(v)-center.z;
            double vr = Math.sqrt(cvx*cvx + cvy*cvy + cvz*cvz);
            if (vr > maxContactR) maxContactR = vr;
        }
        stericContactMaxR = maxContactR;
        reacOut[0] = rx; reacOut[1] = ry; reacOut[2] = rz;
        return Math.sqrt(rx*rx + ry*ry + rz*rz);
    }
    private double stericContactMaxR = 0;   // local membrane surface radius the last node touched (0 = no contact)

    // Closest point on triangle (a,b,c) to point p, in microns. Writes {cx,cy,cz, wa,wb,wc} into out
    // (the point and its barycentric weights). Ericson, "Real-Time Collision Detection".
    private void closestPtTri(double px, double py, double pz, int a, int b, int c, double[] out) {
        double ax=vx(a),ay=vy(a),az=vz(a), bx=vx(b),by=vy(b),bz=vz(b), cx=vx(c),cy=vy(c),cz=vz(c);
        double abx=bx-ax, aby=by-ay, abz=bz-az;
        double acx=cx-ax, acy=cy-ay, acz=cz-az;
        double apx=px-ax, apy=py-ay, apz=pz-az;
        double d1=abx*apx+aby*apy+abz*apz, d2=acx*apx+acy*apy+acz*apz;
        double wa,wb,wc;
        if (d1<=0 && d2<=0) { wa=1; wb=0; wc=0; }
        else {
            double bpx=px-bx,bpy=py-by,bpz=pz-bz;
            double d3=abx*bpx+aby*bpy+abz*bpz, d4=acx*bpx+acy*bpy+acz*bpz;
            double cpx=px-cx,cpy=py-cy,cpz=pz-cz;
            double d5=abx*cpx+aby*cpy+abz*cpz, d6=acx*cpx+acy*cpy+acz*cpz;
            double vc=d1*d4-d3*d2, vb=d5*d2-d1*d6, va=d3*d6-d5*d4;
            if (d3>=0 && d4<=d3) { wa=0; wb=1; wc=0; }
            else if (d6>=0 && d5<=d6) { wa=0; wb=0; wc=1; }
            else if (vc<=0 && d1>=0 && d3<=0) { double t=d1/(d1-d3); wa=1-t; wb=t; wc=0; }
            else if (vb<=0 && d2>=0 && d6<=0) { double t=d2/(d2-d6); wa=1-t; wb=0; wc=t; }
            else if (va<=0 && (d4-d3)>=0 && (d5-d6)>=0) { double t=(d4-d3)/((d4-d3)+(d5-d6)); wa=0; wb=1-t; wc=t; }
            else { double den=1.0/(va+vb+vc); wb=vb*den; wc=vc*den; wa=1-wb-wc; }
        }
        out[0]=wa*ax+wb*bx+wc*cx; out[1]=wa*ay+wb*by+wc*cy; out[2]=wa*az+wb*bz+wc*cz;
        out[3]=wa; out[4]=wb; out[5]=wc;
    }

    /**
     * Steric collision of a thin SEGMENT (capsule: endpoints p1,p2, radius {@code rad}) against the closed
     * membrane — the actin-filament containment / push primitive (reused by real FilSegments in Stage 3).
     *
     * The OLD membrane collided only the barbed TIP against triangles, so a filament BODY could leak through
     * in oblique/tangential orientations. Here we sample the whole segment and do one-sided point-vs-triangle
     * at each sample over the CONTINUOUS triangulated surface — the triangle faces tile the surface with no
     * gaps (unlike vertex-spheres, which leave face-center gaps a thin rod slips through). Each sample within
     * the collision envelope of its nearest face is pushed back inward (the membrane is pushed outward,
     * reaction distributed to the face's 3 vertices); the reaction on the segment is split to p1/p2 by the
     * sample's parameter and returned in {@code reac1}/{@code reac2} (N). Drag-coupled magnitude (stiff — a
     * containing wall), the same form the old tip-vs-triangle used.
     *
     * Returns the MAX signed distance of any sample past its nearest triangle (along the outward normal):
     * &lt;= 0 means every sample is inside (contained); &gt; rad means a sample has clearly leaked out.
     */
    public double segmentVsMembrane(double p1x, double p1y, double p1z, double p2x, double p2y, double p2z,
                             double rad, double gSeg, double[] reac1, double[] reac2) {
        double ex = p2x-p1x, ey = p2y-p1y, ez = p2z-p1z;
        double sLen = Math.sqrt(ex*ex + ey*ey + ez*ez);
        int K = Math.max(2, (int)Math.ceil(sLen / (0.4*l0)) + 1);   // sample finer than the triangle size
        double cdt = Env.collisionDeltaT.getValue();
        double contact = rad + vertexRadius;
        double maxLeak = -1e30;
        reac1[0]=reac1[1]=reac1[2]=0; reac2[0]=reac2[1]=reac2[2]=0;
        double[] cp = new double[7];   // {cx,cy,cz, wa,wb,wc, faceIndex}
        for (int k = 0; k < K; k++) {
            double t = (K <= 1) ? 0.0 : (double)k/(K-1);
            double sx = p1x + t*ex, sy = p1y + t*ey, sz = p1z + t*ez;
            // nearest triangle to this sample (continuous surface — no gaps). Grid-accelerated when built.
            int bestF = nearestFace(sx, sy, sz, cp);
            double bcx=cp[0],bcy=cp[1],bcz=cp[2],bwa=cp[3],bwb=cp[4],bwc=cp[5];
            if (bestF < 0) continue;
            double nx = fNx[bestF], ny = fNy[bestF], nz = fNz[bestF];
            double signed = (sx-bcx)*nx + (sy-bcy)*ny + (sz-bcz)*nz;   // >0 = sample outside the surface
            if (signed > maxLeak) maxLeak = signed;
            if (signed > -contact) {                                   // within the collision envelope (or crossed)
                double pen = signed + contact;                         // >0
                int a = faceVert[3*bestF], b = faceVert[3*bestF+1], c = faceVert[3*bestF+2];
                double gv = vert[a].bTransGam.x;
                double mag = (1.0e-6 * pen / cdt) / (1.0/gSeg + 1.0/gv);   // drag-coupled engagement (stiff), N
                // HARD RECOVERY: any sample that has crossed to the OUTSIDE (signed>0) gets a stiff inward
                // spring on top, so it is yanked back in within a step or two -- guarantees containment even
                // under a strong outward drive (the soft term alone equilibrates just past the surface).
                if (signed > 0) mag += Env.dtsStericRecover.getValue() * signed * 1.0e-6;
                if (mag > 2.0e-10) mag = 2.0e-10;   // cap: a thin low-drag filament can otherwise overshoot
                                                    // under the hard recovery -> runaway -> NaN. Bound it.
                double fx = -mag*nx, fy = -mag*ny, fz = -mag*nz;       // push sample INWARD (-n̂)
                reac1[0]+=(1-t)*fx; reac1[1]+=(1-t)*fy; reac1[2]+=(1-t)*fz;
                reac2[0]+=t*fx;     reac2[1]+=t*fy;     reac2[2]+=t*fz;
                vert[a].incForceSumSlot(mag*nx*bwa, mag*ny*bwa, mag*nz*bwa);   // push membrane OUTWARD (+n̂),
                vert[b].incForceSumSlot(mag*nx*bwb, mag*ny*bwb, mag*nz*bwb);   // distributed by closest-point
                vert[c].incForceSumSlot(mag*nx*bwc, mag*ny*bwc, mag*nz*bwc);   // barycentric weights
            }
        }
        return maxLeak;
    }

    // ---- Spatial face grid (accelerates actin-vs-membrane collision; rebuilt each step) ----
    private int[] gHead, gNext;                  // uniform-grid bucket heads + per-face next (linked list)
    private double gCell, gx0, gy0, gz0;
    private int gnx, gny, gnz;
    private final double[] scratchCp = new double[7];

    private int axisIdx(double v, double v0, int n) { int i=(int)((v-v0)/gCell); return i<0?0:(i>=n?n-1:i); }

    /** Bin the faces (by centroid) into a uniform grid over the membrane bbox; cell ~2 mean edges so a face's
     *  neighbourhood is covered by the 3x3x3 cells around any query point. O(nf), rebuilt each collision step. */
    public void buildFaceGrid() {
        double minx=1e30,miny=1e30,minz=1e30,maxx=-1e30,maxy=-1e30,maxz=-1e30;
        for (int v=0; v<nv; v++) {
            double x=vx(v),y=vy(v),z=vz(v);
            if(x<minx)minx=x; if(y<miny)miny=y; if(z<minz)minz=z;
            if(x>maxx)maxx=x; if(y>maxy)maxy=y; if(z>maxz)maxz=z;
        }
        gCell = 2.0*l0;
        gx0=minx-gCell; gy0=miny-gCell; gz0=minz-gCell;
        gnx=(int)((maxx-minx)/gCell)+3; gny=(int)((maxy-miny)/gCell)+3; gnz=(int)((maxz-minz)/gCell)+3;
        int nc=gnx*gny*gnz;
        if (gHead==null || gHead.length<nc) gHead=new int[nc];
        java.util.Arrays.fill(gHead, 0, nc, -1);
        if (gNext==null || gNext.length<nf) gNext=new int[nf];
        for (int f=0; f<nf; f++) {
            int a=faceVert[3*f],b=faceVert[3*f+1],c=faceVert[3*f+2];
            double cx=(vx(a)+vx(b)+vx(c))/3.0, cy=(vy(a)+vy(b)+vy(c))/3.0, cz=(vz(a)+vz(b)+vz(c))/3.0;
            int cell=(axisIdx(cx,gx0,gnx)*gny+axisIdx(cy,gy0,gny))*gnz+axisIdx(cz,gz0,gnz);
            gNext[f]=gHead[cell]; gHead[cell]=f;
        }
    }

    /** Closest face to (sx,sy,sz): fills out[0..5]={cx,cy,cz,wa,wb,wc}, returns the face index (-1 if none).
     *  Uses the grid (3x3x3 cells) when built, else scans all faces (the brute-force path the test uses). */
    private int nearestFace(double sx, double sy, double sz, double[] out) {
        int bestF=-1; double bestD2=1e30;
        if (gHead != null) {
            int ix=axisIdx(sx,gx0,gnx), iy=axisIdx(sy,gy0,gny), iz=axisIdx(sz,gz0,gnz);
            for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
                int cx=ix+dx, cy=iy+dy, cz=iz+dz;
                if (cx<0||cx>=gnx||cy<0||cy>=gny||cz<0||cz>=gnz) continue;
                for (int f=gHead[(cx*gny+cy)*gnz+cz]; f>=0; f=gNext[f]) {
                    closestPtTri(sx,sy,sz, faceVert[3*f],faceVert[3*f+1],faceVert[3*f+2], scratchCp);
                    double ddx=sx-scratchCp[0], ddy=sy-scratchCp[1], ddz=sz-scratchCp[2];
                    double d2=ddx*ddx+ddy*ddy+ddz*ddz;
                    if (d2<bestD2){ bestD2=d2; bestF=f; System.arraycopy(scratchCp,0,out,0,6); }
                }
            }
        } else {
            for (int f=0; f<nf; f++) {
                closestPtTri(sx,sy,sz, faceVert[3*f],faceVert[3*f+1],faceVert[3*f+2], scratchCp);
                double ddx=sx-scratchCp[0], ddy=sy-scratchCp[1], ddz=sz-scratchCp[2];
                double d2=ddx*ddx+ddy*ddy+ddz*ddz;
                if (d2<bestD2){ bestD2=d2; bestF=f; System.arraycopy(scratchCp,0,out,0,6); }
            }
        }
        return bestF;
    }

    /** Refresh the per-face geometry (normals/areas) the steric reads — call before segmentVsMembrane when
     *  the membrane is held rigid (computeForces is otherwise the one to populate the per-face arrays). */
    public void refreshFaceGeometry() { computeGeometryAndEnergy(); }

    // Max vertex radius in the +x hemisphere (a crude bulge-extent readout).
    private double maxRadiusAlongX() {
        double m = 0;
        for (int v = 0; v < nv; v++) {
            if (vx(v) > 0) {
                double r = Math.sqrt(vx(v)*vx(v) + vy(v)*vy(v) + vz(v)*vz(v));
                if (r > m) m = r;
            }
        }
        return m;
    }

    private void ensureScratch() {
        if (mPx != null) return;
        mPx = new double[nv]; mPy = new double[nv]; mPz = new double[nv];
        fNx = new double[nf]; fNy = new double[nf]; fNz = new double[nf]; fArea = new double[nf];
        eLen = new double[ne]; eTheta = new double[ne];
        vA = new double[nv]; vC = new double[nv]; vAlpha = new double[nv]; vBeta = new double[nv];
        fX = new double[nv]; fY = new double[nv]; fZ = new double[nv];
    }

    private static final double UM_TO_M = 1.0e-6;

    /**
     * Recompute all geometry (per-face normal/area, per-edge length/dihedral, per-vertex curvature
     * c_v and area A_v) and the three energy components, from the CURRENT vertex pose. Fills the
     * scratch arrays and {@link #areaTot}/{@link #volTot}. Positions are pulled in metres.
     * Returns total energy (J). Shared by {@link #totalEnergy} and {@link #computeForces}.
     */
    private double computeGeometryAndEnergy() {
        ensureScratch();
        double kappa = Env.dtsKappaBend.getValue();
        double C0 = spontCurv;

        for (int i = 0; i < nv; i++) {
            mPx[i] = vx(i) * UM_TO_M; mPy[i] = vy(i) * UM_TO_M; mPz[i] = vz(i) * UM_TO_M;
        }

        // Per-face: unit normal, area; accumulate total area + signed 6V.
        areaTot = 0.0;
        double v6 = 0.0;
        for (int f = 0; f < nf; f++) {
            int a = faceVert[3*f], b = faceVert[3*f+1], c = faceVert[3*f+2];
            double abx=mPx[b]-mPx[a], aby=mPy[b]-mPy[a], abz=mPz[b]-mPz[a];
            double acx=mPx[c]-mPx[a], acy=mPy[c]-mPy[a], acz=mPz[c]-mPz[a];
            double cx=aby*acz-abz*acy, cy=abz*acx-abx*acz, cz=abx*acy-aby*acx;
            double mag = Math.sqrt(cx*cx+cy*cy+cz*cz);
            fNx[f]=cx/mag; fNy[f]=cy/mag; fNz[f]=cz/mag; fArea[f]=0.5*mag;
            areaTot += fArea[f];
            v6 += mPx[a]*(mPy[b]*mPz[c]-mPz[b]*mPy[c])
                + mPy[a]*(mPz[b]*mPx[c]-mPx[b]*mPz[c])
                + mPz[a]*(mPx[b]*mPy[c]-mPy[b]*mPx[c]);
        }
        volTot = v6 / 6.0;

        // Per-vertex barycentric area A_v.
        java.util.Arrays.fill(vA, 0.0);
        for (int f = 0; f < nf; f++) {
            double t = fArea[f] / 3.0;
            vA[faceVert[3*f]] += t; vA[faceVert[3*f+1]] += t; vA[faceVert[3*f+2]] += t;
        }

        // Per-edge length + signed dihedral; scatter (1/4) l theta into the two endpoints' c_v.
        java.util.Arrays.fill(vC, 0.0);
        for (int e = 0; e < ne; e++) {
            int p = edgeVert[2*e], q = edgeVert[2*e+1];
            int f1 = edgeFace[2*e], f2 = edgeFace[2*e+1];
            double le = Math.sqrt((mPx[q]-mPx[p])*(mPx[q]-mPx[p])
                                + (mPy[q]-mPy[p])*(mPy[q]-mPy[p])
                                + (mPz[q]-mPz[p])*(mPz[q]-mPz[p]));
            double dot = fNx[f1]*fNx[f2]+fNy[f1]*fNy[f2]+fNz[f1]*fNz[f2];
            dot = Math.max(-1.0, Math.min(1.0, dot));
            // Signed dihedral: theta = s*acos(dot), s = sign( ehat . (n1 x n2) ). For a CONSISTENT
            // convex-positive sign across the closed mesh, ehat MUST be the edge direction as traversed
            // in face f1's CCW (outward) winding -- otherwise the arbitrary edgeVert lo/hi order flips
            // the sign per edge, and c_v = (1/4) Sum l*theta cancels to garbage. (This is the difference
            // between the validated 8*pi*kappa and a 16x-too-small energy.)
            double ex, ey, ez;
            if (edgeDirInFaceIsPtoQ(f1, p, q)) { ex=mPx[q]-mPx[p]; ey=mPy[q]-mPy[p]; ez=mPz[q]-mPz[p]; }
            else                               { ex=mPx[p]-mPx[q]; ey=mPy[p]-mPy[q]; ez=mPz[p]-mPz[q]; }
            double crx=fNy[f1]*fNz[f2]-fNz[f1]*fNy[f2];
            double cry=fNz[f1]*fNx[f2]-fNx[f1]*fNz[f2];
            double crz=fNx[f1]*fNy[f2]-fNy[f1]*fNx[f2];
            double sdot = ex*crx+ey*cry+ez*crz;
            double theta = Math.acos(dot) * (sdot >= 0 ? 1.0 : -1.0);
            eLen[e] = le; eTheta[e] = theta;
            double t = 0.25 * le * theta;
            vC[p] += t; vC[q] += t;
        }

        // Per-vertex energy weights:  alpha = dE/dc,  beta = dE/dA  for E = kappa Sum 2(c/A - C0/2)^2 A.
        // Expand: e_v = 2 kappa (c/A - C0/2)^2 A = 2 kappa (c - C0 A/2)^2 / A.
        //   de/dc = 4 kappa (c/A - C0/2)
        //   de/dA = -2 kappa ( (c/A)^2 - (C0/2)^2 )
        energyBend = 0.0;
        for (int v = 0; v < nv; v++) {
            double Av = vA[v], H = vC[v] / Av;          // H = c/A = mean curvature
            double dev = H - 0.5 * C0;
            energyBend += 2.0 * kappa * dev * dev * Av;
            vAlpha[v] = 4.0 * kappa * dev;
            vBeta[v]  = -2.0 * kappa * (H * H - 0.25 * C0 * C0);
        }

        // Area + volume constraint energies.
        double KA = Env.dtsKappaArea.getValue();
        double KV = Env.dtsKappaVolume.getValue();
        double vt = Env.dtsTargetReducedVol.getValue();
        double A0 = area0 * 1.0e-12;     // um^2 -> m^2
        double V0 = vol0  * 1.0e-18;     // um^3 -> m^3
        energyArea = (KA > 0) ? 0.5 * KA * (areaTot - A0) * (areaTot - A0) / A0 : 0.0;
        energyVol  = (KV > 0) ? 0.5 * KV * (volTot / V0 - vt) * (volTot / V0 - vt) : 0.0;

        return energyBend + energyArea + energyVol;
    }

    /** Total membrane energy (J) at the current pose — bending + area + volume. (Also used by the
     *  finite-difference force check.) */
    public double totalEnergy() { return computeGeometryAndEnergy(); }

    /** Compute Stage-2 forces and add them to each vertex's soaForceSum (Newtons). */
    public void computeForces() {
        double kappa = Env.dtsKappaBend.getValue();
        double KA = Env.dtsKappaArea.getValue();
        double KV = Env.dtsKappaVolume.getValue();
        double vt = Env.dtsTargetReducedVol.getValue();
        if (kappa <= 0 && KA <= 0 && KV <= 0) return;

        // computeForces OWNS the DTS vertex force: zero each vertex's soaForceSum, then write only the
        // membrane forces below. The generic node pipeline (chamber-wall collision, etc.) otherwise leaves
        // a large spurious force on these vertices that swamps the physical membrane forces and destabilizes
        // the surface. Brownian undulations and actin/probe coupling are added HERE explicitly in later stages,
        // not via the generic pipeline. (See JOURNAL 2026-06-17 Stage-2 debugging.)
        for (int v = 0; v < nv; v++) vert[v].zeroForceSumSlot();
        computeGeometryAndEnergy();
        java.util.Arrays.fill(fX, 0.0); java.util.Arrays.fill(fY, 0.0); java.util.Arrays.fill(fZ, 0.0);

        // ---- Bending c-term: per-edge scatter of -1/4 (alpha_p+alpha_q)(theta dl/dr + l dtheta/dr) ----
        if (kappa > 0) {
            for (int e = 0; e < ne; e++) {
                int p = edgeVert[2*e], q = edgeVert[2*e+1];
                int f1 = edgeFace[2*e], f2 = edgeFace[2*e+1];
                int w1 = edgeWing[2*e], w2 = edgeWing[2*e+1];
                double le = eLen[e], theta = eTheta[e];
                double coeff = 0.25 * (vAlpha[p] + vAlpha[q]);
                if (coeff == 0) continue;

                // dl/dr: l = |q-p|. dl/dq = ehat, dl/dp = -ehat.
                double ex=(mPx[q]-mPx[p])/le, ey=(mPy[q]-mPy[p])/le, ez=(mPz[q]-mPz[p])/le;
                // force term from l: -coeff*theta*dl/dr
                double cl = -coeff * theta;
                fX[q]+=cl*ex;       fY[q]+=cl*ey;       fZ[q]+=cl*ez;
                fX[p]+=cl*(-ex);    fY[p]+=cl*(-ey);    fZ[p]+=cl*(-ez);

                // dtheta/dr (EXACT): theta = s*acos(dot), dot = n1.n2, s = sign(theta).
                //   d(theta)/dx = s*(-1/sin)*d(dot)/dx.
                //   d(dot)/dx_i = Sum_{faces f containing i} (1/(2A_f))( r_f x w_i^f ),
                //     r_f = n_other - dot*n_f,  w_i^f = p_next - p_prev in f's CCW order
                //     (from d(face normal N)/dx_i = [w_i]_x skew). Provably d(n1.n2)/dx.
                double dot = fNx[f1]*fNx[f2]+fNy[f1]*fNy[f2]+fNz[f1]*fNz[f2];
                dot = Math.max(-1.0, Math.min(1.0, dot));
                double sinT = Math.sqrt(Math.max(1e-300, 1.0 - dot*dot));
                double thetaFac = (theta >= 0 ? 1.0 : -1.0) * (-1.0 / sinT);   // s*(-1/sin)
                double fscale = -coeff * le * thetaFac;       // force = -coeff*l * d(theta)/dx
                double r1x=fNx[f2]-dot*fNx[f1], r1y=fNy[f2]-dot*fNy[f1], r1z=fNz[f2]-dot*fNz[f1];
                double r2x=fNx[f1]-dot*fNx[f2], r2y=fNy[f1]-dot*fNy[f2], r2z=fNz[f1]-dot*fNz[f2];
                double i1=1.0/(2.0*fArea[f1]), i2=1.0/(2.0*fArea[f2]);
                addDotGrad(f1, p,  r1x,r1y,r1z, i1, fscale);   // p,q are in BOTH faces
                addDotGrad(f1, q,  r1x,r1y,r1z, i1, fscale);
                addDotGrad(f1, w1, r1x,r1y,r1z, i1, fscale);   // apex of face1
                addDotGrad(f2, p,  r2x,r2y,r2z, i2, fscale);
                addDotGrad(f2, q,  r2x,r2y,r2z, i2, fscale);
                addDotGrad(f2, w2, r2x,r2y,r2z, i2, fscale);   // apex of face2
            }
        }

        // ---- Per-face scatter: bending A-term + area constraint + volume constraint ----
        double A0 = area0 * 1.0e-12, V0 = vol0 * 1.0e-18;
        double areaCon = (KA > 0) ? KA * (areaTot - A0) / A0 : 0.0;        // dE_area/dA_tot coefficient
        double volCon  = (KV > 0) ? KV * (volTot / V0 - vt) / V0 : 0.0;    // dE_vol /dV     coefficient
        for (int f = 0; f < nf; f++) {
            int a = faceVert[3*f], b = faceVert[3*f+1], c = faceVert[3*f+2];
            // area-gradient scalar multiplying d(area_f)/dr_i: -(bending A-term + area constraint).
            double bendW = (kappa > 0) ? (vBeta[a]+vBeta[b]+vBeta[c]) / 3.0 : 0.0;
            double areaScalar = -(bendW + areaCon);
            double nx=fNx[f], ny=fNy[f], nz=fNz[f];
            // d(area)/dr_a = 1/2 n x (c-b); for b: 1/2 n x (a-c); for c: 1/2 n x (b-a).
            addAreaGrad(a, areaScalar, nx,ny,nz, mPx[c]-mPx[b], mPy[c]-mPy[b], mPz[c]-mPz[b]);
            addAreaGrad(b, areaScalar, nx,ny,nz, mPx[a]-mPx[c], mPy[a]-mPy[c], mPz[a]-mPz[c]);
            addAreaGrad(c, areaScalar, nx,ny,nz, mPx[b]-mPx[a], mPy[b]-mPy[a], mPz[b]-mPz[a]);
            // volume: F_i += -volCon * dV/dr_i, dV/dr_a = 1/6 (p_b x p_c), cyclic.
            if (volCon != 0) {
                addVolGrad(a, -volCon, b, c);
                addVolGrad(b, -volCon, c, a);
                addVolGrad(c, -volCon, a, b);
            }
        }

        // ---- Write per-vertex force into soaForceSum (N) ----
        double maxF = 0;
        for (int v = 0; v < nv; v++) {
            vert[v].incForceSumSlot(fX[v], fY[v], fZ[v]);
            double fm = fX[v]*fX[v]+fY[v]*fY[v]+fZ[v]*fZ[v];
            if (fm > maxF) maxF = fm;
        }
        if (Env.counter % 500 == 0) {
            Thing.talkln(String.format(
                "[DTS-E] step %d  E bend=%.3e area=%.3e vol=%.3e tot=%.3e J   A=%.4f/%.4f  V=%.4f/%.4f um  |F|max=%.2e N",
                Env.counter, energyBend, energyArea, energyVol, energyBend+energyArea+energyVol,
                areaTot*1e12, area0, volTot*1e18, vol0, Math.sqrt(maxF)));
        }
    }

    // F_i += scalar * (1/2) n x edgeOpp
    private void addAreaGrad(int i, double scalar, double nx, double ny, double nz,
                             double ex, double ey, double ez) {
        double gx = 0.5*(ny*ez - nz*ey);
        double gy = 0.5*(nz*ex - nx*ez);
        double gz = 0.5*(nx*ey - ny*ex);
        fX[i]+=scalar*gx; fY[i]+=scalar*gy; fZ[i]+=scalar*gz;
    }

    // F_i += scalar * (1/6)(p_j x p_k)
    private void addVolGrad(int i, double scalar, int j, int k) {
        double gx = (mPy[j]*mPz[k] - mPz[j]*mPy[k]) / 6.0;
        double gy = (mPz[j]*mPx[k] - mPx[j]*mPz[k]) / 6.0;
        double gz = (mPx[j]*mPy[k] - mPy[j]*mPx[k]) / 6.0;
        fX[i]+=scalar*gx; fY[i]+=scalar*gy; fZ[i]+=scalar*gz;
    }

    // True iff the directed edge p->q appears in face f's CCW vertex order (vs q->p).
    private boolean edgeDirInFaceIsPtoQ(int f, int p, int q) {
        int a=faceVert[3*f], b=faceVert[3*f+1], c=faceVert[3*f+2];
        return (a==p&&b==q) || (b==p&&c==q) || (c==p&&a==q);
    }

    // Accumulate the dihedral d(dot)/dx contribution of vertex i within face f, scaled, into the force.
    //   contribution = invTwoA * ( r x w_i ),  w_i = p_next - p_prev in f's CCW order.
    private void addDotGrad(int f, int i, double rx, double ry, double rz, double invTwoA, double scale) {
        int a=faceVert[3*f], b=faceVert[3*f+1], c=faceVert[3*f+2];
        double wx, wy, wz;
        if (i==a)      { wx=mPx[c]-mPx[b]; wy=mPy[c]-mPy[b]; wz=mPz[c]-mPz[b]; }
        else if (i==b) { wx=mPx[a]-mPx[c]; wy=mPy[a]-mPy[c]; wz=mPz[a]-mPz[c]; }
        else           { wx=mPx[b]-mPx[a]; wy=mPy[b]-mPy[a]; wz=mPz[b]-mPz[a]; }
        double gx=invTwoA*(ry*wz-rz*wy);   // g = r x w
        double gy=invTwoA*(rz*wx-rx*wz);
        double gz=invTwoA*(rx*wy-ry*wx);
        fX[i]+=scale*gx; fY[i]+=scale*gy; fZ[i]+=scale*gz;
    }

    public double lastEnergyBend() { return energyBend; }
    public double lastEnergyArea() { return energyArea; }
    public double lastEnergyVol()  { return energyVol; }
    public double currentAreaUm2() { return areaTot * 1.0e12; }
    public double currentVolUm3()  { return volTot * 1.0e18; }
}
