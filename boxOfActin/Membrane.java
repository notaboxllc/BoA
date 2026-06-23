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
    public int nv;                 // vertices (MUTABLE: edge split/collapse change it; arrays are capacity-backed)
    public int nf;                 // faces   (MUTABLE)
    public int ne;                 // edges   (MUTABLE; = 3*nf/2 for a closed manifold)
    public final int maxVal;       // fixed width of the vertex-incidence array
    private final int capV, capF, capE;   // array capacities (logical counts nv/nf/ne grow within these)

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
    // Per-instance mechanics (init from Env defaults; the inner cortex shell overrides them to be stiffer).
    // The force code uses THESE fields, not Env globals, so the two shells can differ. Mutable Env params
    // (dtsKappaArea etc.) are re-read each step in computeForces for the BILAYER; the cortex keeps its overrides.
    public double kappaBend = Env.dtsKappaBend.getValue();   // bending rigidity (J)
    public double kappaArea = Env.dtsKappaArea.getValue();   // area-stretch modulus K_A (N/m)
    public double kappaVolume = Env.dtsKappaVolume.getValue(); // volume modulus K_V (J)
    public double targetRedVol = Env.dtsTargetReducedVol.getValue(); // target reduced volume vt
    public double dragScale = 1.0;                            // per-vertex Stokes-drag multiplier (cortex > 1)
    public boolean isCortex = false;                          // true = inner actin-cortex shell
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

    /** True if this shell carries per-vertex surface chemistry (Arp field / NPF hot patches / formin pool), which
     *  is allocated to the INITIAL nv (not capacity-sized). Such a shell must NOT remesh (split/collapse change nv
     *  and would overflow these arrays) -- it's the cortex / nucleating membrane, kept fixed-topology. */
    public boolean hasSurfaceChem() { return arpLocal != null || forminLocal != null || arpHot != null; }

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
        final int CAP = 4;             // capacity factor: edge split/collapse grow/shrink nv/nf/ne within these
        this.capV = CAP*nv; this.capF = CAP*nf; this.capE = CAP*ne;
        this.faceVert = new int[3*capF];
        System.arraycopy(geom.face, 0, faceVert, 0, 3*nf);

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
        this.vert = new MembraneVertex[capV];
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
        this.edgeVert = new int[2 * capE];
        this.edgeFace = new int[2 * capE];
        this.edgeWing = new int[2 * capE];
        for (int e = 0; e < ne; e++) {
            int[] rec = edges.get(e);
            edgeVert[2 * e] = rec[0]; edgeVert[2 * e + 1] = rec[1];
            edgeFace[2 * e] = rec[2]; edgeFace[2 * e + 1] = rec[3];
            edgeWing[2 * e] = rec[4]; edgeWing[2 * e + 1] = rec[5];
        }

        int mv = 0;
        for (int i = 0; i < nv; i++) mv = Math.max(mv, valence[i]);
        this.maxVal = Math.max(mv, 12);   // headroom: edge flips raise valence past the icosphere's 6 (cap VAL_MAX)
        this.vertEdge = new int[capV * maxVal];
        this.vertEdgeCt = new int[capV];
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
        // Surface chemistry (hot patches / Arp field / formin pool) is initialized later by initSurfaceChemistry(),
        // on the CORTEX in two-shell mode (where actin nucleates) or the sole shell in single-shell mode -- NOT here
        // in the ctor, because isCortex isn't set until buildIcosphereMembrane finishes constructing both shells.
        report();
    }

    /** Initialize the actin-nucleating surface chemistry (NPF hot patches, Arp2/3 field, formin pool) on THIS shell.
     *  Called by buildIcosphereMembrane on the cortex (two-shell) or the bilayer (single-shell). */
    public void initSurfaceChemistry() {
        if (Env.dtsArpOn.isActive() || Env.dtsForminOn.isActive()) {
            markHotPatches();
            if (Env.dtsArpOn.isActive())    initArp();
            if (Env.dtsForminOn.isActive()) initFormin();
        }
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
        new Membrane(nu, r, new Pt3D(0, 0, 0));                 // index 0 = outer LIPID BILAYER (fluid)
        if (Env.dtsCortexOn.getValue() != 0) {
            double rc = Env.dtsCortexRadius.getValue();
            Membrane cortex = new Membrane(nu, rc, new Pt3D(0, 0, 0));   // index 1 = inner ACTIN CORTEX (stiff)
            cortex.isCortex = true;
            cortex.kappaBend *= Env.dtsCortexKappaScale.getValue();
            cortex.kappaArea  = Env.dtsKappaArea.getValue() * Env.dtsCortexAreaScale.getValue();
            cortex.kappaVolume = Env.dtsKappaVolume.getValue() * Env.dtsCortexVolScale.getValue();
            cortex.targetRedVol = Env.dtsCortexTargetRedVol.getValue();   // <1 => cortex contracts (pull in)
            cortex.dragScale  = Env.dtsCortexDragScale.getValue();
            // calculateProperties ran with owner==null (drag scale 1) during construction; re-run now that
            // dragScale is set so the cortex vertices get their heavier (slower) Stokes drag.
            for (int i = 0; i < cortex.nv; i++) cortex.vert[i].calculateProperties();
            cortex.initSurfaceChemistry();   // actin nucleates AT THE CORTEX in two-shell mode (anchor = reaction)
            Thing.talkln(String.format(
                "[DTS] two-shell: bilayer R=%.3f + cortex R=%.3f  (cortex kappa x%.0f, K_A=%.3g, drag x%.1f)",
                r, rc, Env.dtsCortexKappaScale.getValue(), cortex.kappaArea, cortex.dragScale));
        } else {
            bilayer().initSurfaceChemistry();   // single shell: chemistry on the sole membrane (legacy)
        }
    }

    /** The outer fluid bilayer (the non-cortex shell), or the sole shell in single-shell mode. */
    public static Membrane bilayer() {
        for (Membrane m : theMembranes) if (!m.isCortex) return m;
        return theMembranes.isEmpty() ? null : theMembranes.get(0);
    }
    /** The inner actin-cortex shell, or null if single-shell. */
    public static Membrane cortex() {
        for (Membrane m : theMembranes) if (m.isCortex) return m;
        return null;
    }

    /**
     * Sliding membrane–cortex-attachment (MCA) linkers — coupling rung C (TWO_SHELL_MEMBRANE_DESIGN §4).
     * Holds each BILAYER vertex at a rest gap off the NEAREST point on the CORTEX surface: the nearest surface
     * point is re-selected every step, so the linker bears NORMAL load (maintains the gap) but exerts ~no
     * TANGENTIAL force (the bilayer flows over the cortex) — the ERM/ezrin sliding-anchor. Two-sided, force-limited
     * (dtsMcaForceMax ~ few pN). Reaction distributed to the cortex face's 3 vertices by barycentric weights.
     */
    // MCA linker geometry for the viewer: flat [bx,by,bz, cx,cy,cz] per active linker (bilayer vertex -> cortex
    // point). Filled every step by applyMcaLinkers; ThreeJSWriter emits a strided subset when dtsMcaLinkViz>0.
    public static double[] mcaLinkBuf = null;
    public static int mcaLinkCount = 0;

    public static void applyMcaLinkers() {
        Membrane bilayer = bilayer(), cortex = cortex();
        mcaLinkCount = 0;
        if (bilayer == null || cortex == null) return;
        cortex.buildFaceGrid();                       // narrow-phase grid for nearestFace (face normals are fresh from computeForces)
        double k = Env.dtsMcaStiffness.getValue();
        double fmax = Env.dtsMcaForceMax.getValue();
        double h = Env.dtsMcaRestGap.getValue();
        if (h <= 0) h = Env.dtsMembraneRadius.getValue() - Env.dtsCortexRadius.getValue();
        double[] cp = new double[7];
        int linked = 0;
        if (mcaLinkBuf == null || mcaLinkBuf.length < bilayer.nv * 6) mcaLinkBuf = new double[bilayer.nv * 6];
        for (int vi = 0; vi < bilayer.nv; vi++) {
            double px = bilayer.vx(vi), py = bilayer.vy(vi), pz = bilayer.vz(vi);
            int f = cortex.nearestFace(px, py, pz, cp);
            if (f < 0) continue;
            double dx = px-cp[0], dy = py-cp[1], dz = pz-cp[2];
            double d = Math.sqrt(dx*dx+dy*dy+dz*dz);
            if (d < 1e-9) continue;
            double ux = dx/d, uy = dy/d, uz = dz/d;   // cortex surface -> bilayer vertex (~ outward normal)
            double mag = k * (d - h) * 1.0e-6;         // two-sided: d>h pulls in, d<h pushes out (N)
            if (mag >  fmax) mag =  fmax;
            if (mag < -fmax) mag = -fmax;
            bilayer.vert[vi].incForceSumSlot(-mag*ux, -mag*uy, -mag*uz);   // bilayer pulled toward the gap
            int a = cortex.faceVert[3*f], b = cortex.faceVert[3*f+1], c = cortex.faceVert[3*f+2];
            double wa = cp[3], wb = cp[4], wc = cp[5];
            cortex.vert[a].incForceSumSlot(mag*ux*wa, mag*uy*wa, mag*uz*wa); // equal-opposite on the cortex
            cortex.vert[b].incForceSumSlot(mag*ux*wb, mag*uy*wb, mag*uz*wb);
            cortex.vert[c].incForceSumSlot(mag*ux*wc, mag*uy*wc, mag*uz*wc);
            int o = mcaLinkCount * 6;                  // record this linker (bilayer vertex -> cortex closest point)
            mcaLinkBuf[o]=px; mcaLinkBuf[o+1]=py; mcaLinkBuf[o+2]=pz;
            mcaLinkBuf[o+3]=cp[0]; mcaLinkBuf[o+4]=cp[1]; mcaLinkBuf[o+5]=cp[2];
            mcaLinkCount++;
            linked++;
        }
        if (Env.counter % 500 == 0) {
            double rb = bilayer.maxRadiusAlongX(), rcx = cortex.maxRadiusAlongX();
            Thing.talkln(String.format("[MCA] step %d linked=%d/%d  gap=%.3f  bilayerMaxR=%.3f cortexMaxR=%.3f",
                Env.counter, linked, bilayer.nv, h, rb, rcx));
        }
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

    // ===================== Bond (edge) flips — bilayer FLUIDITY (design doc §4) =====================
    // A fluid bilayer has no fixed connectivity: lipids rearrange so the surface resists bending+area but NOT
    // shear. We fluidize the (otherwise solid, fixed-connectivity) DTS mesh by flipping edges: an interior edge
    // shared by two triangles (quad a-c-b-d) is reconnected along the other diagonal (c-d). Updates the flat
    // arrays in place; vertEdge incidence is NOT maintained (currently unused by the force path -- restore for the
    // GPU gather port). Acceptance here = Delaunay/quality (robust first cut); Metropolis-on-bending is the
    // physical refinement (next stage).
    private java.util.HashMap<Long,Integer> edgeIndex;   // ekey -> edge index, maintained across flips
    int flipAccepted, flipTried;                         // diagnostics (per sweep)
    private final java.util.Random flipRng = new java.util.Random(20260620L);
    private static final int VAL_MAX = 9, VAL_MIN = 3;

    private long ekey(int u, int v) { int lo=Math.min(u,v), hi=Math.max(u,v); return (long)lo*capV + hi; }  // capV (fixed!) not nv -- nv changes on split/collapse
    private int findEdge(int u, int v) { Integer e = edgeIndex.get(ekey(u,v)); return e==null?-1:e; }

    private void buildFlipIndex() {   // edge lookup; valence is tracked by vertEdgeCt (built in the ctor)
        if (edgeIndex != null) return;
        edgeIndex = new java.util.HashMap<>(ne*2);
        for (int e=0;e<ne;e++) edgeIndex.put(ekey(edgeVert[2*e], edgeVert[2*e+1]), e);
    }
    private void removeIncidence(int v, int e) {   // swap-remove e from v's incidence row
        int base = v*maxVal, ct = vertEdgeCt[v];
        for (int k=0;k<ct;k++) if (vertEdge[base+k]==e) { vertEdge[base+k]=vertEdge[base+ct-1]; vertEdgeCt[v]=ct-1; return; }
    }
    private void setEdgeFaceWing(int pe, int oldFace, int newFace, int newWing) {
        if      (edgeFace[2*pe]  ==oldFace) { edgeFace[2*pe]  =newFace; edgeWing[2*pe]  =newWing; }
        else if (edgeFace[2*pe+1]==oldFace) { edgeFace[2*pe+1]=newFace; edgeWing[2*pe+1]=newWing; }
    }
    private void orientFaceOutward(int f) {
        int a=faceVert[3*f], b=faceVert[3*f+1], c=faceVert[3*f+2];
        double ux=vx(b)-vx(a),uy=vy(b)-vy(a),uz=vz(b)-vz(a), wx=vx(c)-vx(a),wy=vy(c)-vy(a),wz=vz(c)-vz(a);
        double nx=uy*wz-uz*wy, ny=uz*wx-ux*wz, nz=ux*wy-uy*wx;
        double cx=(vx(a)+vx(b)+vx(c))/3-center.x, cy=(vy(a)+vy(b)+vy(c))/3-center.y, cz=(vz(a)+vz(b)+vz(c))/3-center.z;
        if (nx*cx+ny*cy+nz*cz < 0) { faceVert[3*f+1]=c; faceVert[3*f+2]=b; }   // swap -> CCW outward
    }
    private double triArea(int a,int b,int c){
        double ux=vx(b)-vx(a),uy=vy(b)-vy(a),uz=vz(b)-vz(a), wx=vx(c)-vx(a),wy=vy(c)-vy(a),wz=vz(c)-vz(a);
        double nx=uy*wz-uz*wy,ny=uz*wx-ux*wz,nz=ux*wy-uy*wx; return 0.5*Math.sqrt(nx*nx+ny*ny+nz*nz);
    }
    private double edgeLen(int u,int v){ double dx=vx(u)-vx(v),dy=vy(u)-vy(v),dz=vz(u)-vz(v); return Math.sqrt(dx*dx+dy*dy+dz*dz); }
    private double angAt(int o,int p,int q){
        double ax=vx(p)-vx(o),ay=vy(p)-vy(o),az=vz(p)-vz(o), bx=vx(q)-vx(o),by=vy(q)-vy(o),bz=vz(q)-vz(o);
        double dn=(ax*bx+ay*by+az*bz)/(Math.sqrt(ax*ax+ay*ay+az*az)*Math.sqrt(bx*bx+by*by+bz*bz)+1e-30);
        return Math.acos(Math.max(-1,Math.min(1,dn)));
    }
    private double triMinAngle(int a,int b,int c){ return Math.min(angAt(a,b,c), Math.min(angAt(b,a,c), angAt(c,a,b))); }

    // ---- local bending energy (J), for the Metropolis flip acceptance (matches computeForces' convention) ----
    private final double[] nA=new double[4], nB=new double[4];   // {nx,ny,nz, area(m^2)}
    private final int[] facesTmp = new int[32];
    private void faceNormalArea(int f, double[] out) {
        int a=faceVert[3*f],b=faceVert[3*f+1],c=faceVert[3*f+2];
        double ax=vx(a)*UM_TO_M,ay=vy(a)*UM_TO_M,az=vz(a)*UM_TO_M;
        double ux=vx(b)*UM_TO_M-ax,uy=vy(b)*UM_TO_M-ay,uz=vz(b)*UM_TO_M-az;
        double wx=vx(c)*UM_TO_M-ax,wy=vy(c)*UM_TO_M-ay,wz=vz(c)*UM_TO_M-az;
        double nx=uy*wz-uz*wy,ny=uz*wx-ux*wz,nz=ux*wy-uy*wx, n=Math.sqrt(nx*nx+ny*ny+nz*nz)+1e-300;
        out[0]=nx/n; out[1]=ny/n; out[2]=nz/n; out[3]=0.5*n;
    }
    private double signedDihedral(int e) {   // same convention as computeForces (sign via f1 CCW winding)
        int p=edgeVert[2*e],q=edgeVert[2*e+1], f1=edgeFace[2*e], f2=edgeFace[2*e+1];
        faceNormalArea(f1,nA); faceNormalArea(f2,nB);
        double dot=Math.max(-1,Math.min(1, nA[0]*nB[0]+nA[1]*nB[1]+nA[2]*nB[2]));
        double ex,ey,ez;
        if (edgeDirInFaceIsPtoQ(f1,p,q)) { ex=(vx(q)-vx(p))*UM_TO_M; ey=(vy(q)-vy(p))*UM_TO_M; ez=(vz(q)-vz(p))*UM_TO_M; }
        else                              { ex=(vx(p)-vx(q))*UM_TO_M; ey=(vy(p)-vy(q))*UM_TO_M; ez=(vz(p)-vz(q))*UM_TO_M; }
        double crx=nA[1]*nB[2]-nA[2]*nB[1], cry=nA[2]*nB[0]-nA[0]*nB[2], crz=nA[0]*nB[1]-nA[1]*nB[0];
        return Math.acos(dot)*((ex*crx+ey*cry+ez*crz)>=0?1.0:-1.0);
    }
    private double vertexBendEnergy(int v, double kappa, double C0) {
        int base=v*maxVal, ct=vertEdgeCt[v];
        double cv=0;
        for (int k=0;k<ct;k++){ int e=vertEdge[base+k];
            double le=edgeLen(edgeVert[2*e],edgeVert[2*e+1])*UM_TO_M;
            cv += 0.25*le*signedDihedral(e);
        }
        int nfc=0;
        for (int k=0;k<ct;k++){ int e=vertEdge[base+k];
            for (int s=0;s<2;s++){ int f=edgeFace[2*e+s]; boolean seen=false;
                for(int j=0;j<nfc;j++) if(facesTmp[j]==f){seen=true;break;}
                if(!seen && nfc<facesTmp.length) facesTmp[nfc++]=f; } }
        double Av=0; for(int j=0;j<nfc;j++){ faceNormalArea(facesTmp[j],nA); Av+=nA[3]; } Av/=3.0;
        double dev = cv/Av - 0.5*C0;
        return 2.0*kappa*dev*dev*Av;
    }
    private double localBendEnergy(int va,int vb,int vc,int vd, double kappa, double C0) {
        int[] vs={va,vb,vc,vd}; double E=0;
        for (int i=0;i<4;i++){ boolean dup=false; for(int j=0;j<i;j++) if(vs[j]==vs[i]) dup=true;
            if(!dup) E += vertexBendEnergy(vs[i],kappa,C0); }
        return E;
    }

    /** The array surgery for flipping edge e from diagonal (a,b) to (c,d). Assumes the flip is valid.
     *  SELF-INVERSE: running it again on the same edge flips back (used to revert a rejected Metropolis move). */
    private void doFlipSurgery(int e) {
        int a=edgeVert[2*e], b=edgeVert[2*e+1], f0=edgeFace[2*e], f1=edgeFace[2*e+1], c=edgeWing[2*e], d=edgeWing[2*e+1];
        int eac=findEdge(a,c), ecb=findEdge(c,b), ebd=findEdge(b,d), eda=findEdge(d,a);
        setEdgeFaceWing(eac, f0, f0, d);
        setEdgeFaceWing(ecb, f0, f1, d);
        setEdgeFaceWing(ebd, f1, f1, c);
        setEdgeFaceWing(eda, f1, f0, c);
        faceVert[3*f0]=a; faceVert[3*f0+1]=c; faceVert[3*f0+2]=d; orientFaceOutward(f0);
        faceVert[3*f1]=b; faceVert[3*f1+1]=c; faceVert[3*f1+2]=d; orientFaceOutward(f1);
        edgeIndex.remove(ekey(a,b)); edgeIndex.put(ekey(c,d), e);
        edgeVert[2*e]=Math.min(c,d); edgeVert[2*e+1]=Math.max(c,d);
        edgeFace[2*e]=f0; edgeFace[2*e+1]=f1; edgeWing[2*e]=a; edgeWing[2*e+1]=b;
        removeIncidence(a,e); removeIncidence(b,e); addIncidence(c,e); addIncidence(d,e);
    }

    /** Attempt edge flip by Metropolis on the bending energy: accept with prob min(1, exp(-dE/kT)).
     *  Manifold + valence + geometry guards; reverts (re-flips) on rejection. */
    boolean tryFlip(int e, double kT) {
        int a=edgeVert[2*e], b=edgeVert[2*e+1], c=edgeWing[2*e], d=edgeWing[2*e+1];
        if (c==d || findEdge(c,d) != -1) return false;                          // degenerate / would duplicate
        if (vertEdgeCt[a]<=VAL_MIN || vertEdgeCt[b]<=VAL_MIN) return false;
        if (vertEdgeCt[c]>=VAL_MAX || vertEdgeCt[d]>=VAL_MAX) return false;
        if (triArea(a,c,d) < 1e-12 || triArea(b,c,d) < 1e-12) return false;     // no degenerate new triangle
        // Tether bounds on the NEW diagonal. l_max < sqrt(3)*l0 ~ 1.73*l0 is essential: it rejects the
        // regular-hex flip (whose new diagonal is ~sqrt(3)*l0), so flips fire ONLY where the geometry is
        // distorted enough that the new edge is short -- the standard DTS mesh-conditioning that keeps the
        // triangulation regular instead of melting it into slivers.
        double lcd=edgeLen(c,d); if (lcd<0.67*l0 || lcd>1.5*l0) return false;
        // QUALITY guard: don't let a flip CREATE a sliver even if the bending energy favours it (bending doesn't
        // penalize thin triangles). Reject if either new triangle's min angle is too small. This is what keeps
        // flips from degrading the mesh in strongly-deformed regions (e.g. a bleb neck).
        final double MIN_ANG = Math.toRadians(12.0);
        if (triMinAngle(a,c,d) < MIN_ANG || triMinAngle(b,c,d) < MIN_ANG) return false;
        if (findEdge(a,c)<0||findEdge(c,b)<0||findEdge(b,d)<0||findEdge(d,a)<0) return false;
        double kappa=this.kappaBend, C0=spontCurv;   // per-instance (refreshed in computeForces each step)
        double E0 = localBendEnergy(a,b,c,d,kappa,C0);
        doFlipSurgery(e);
        double E1 = localBendEnergy(a,b,c,d,kappa,C0);
        double dE = E1 - E0;
        if (dE <= 0 || flipRng.nextDouble() < Math.exp(-dE/kT)) return true;    // accept
        doFlipSurgery(e);                                                       // reject -> revert
        return false;
    }

    /** One flip sweep over all edges; returns #accepted. The acceptance "temperature" is an EFFECTIVE
     *  fluidity/stability knob (NOT the physical T): kT_flip = BOA_FLIP_KT * (Boltz*tempK), default 0.1.
     *  -> 0 = quench (accept only energy-decreasing flips: max stability, reactive fluidity, deterministic);
     *  -> 1 = full thermal (samples the Boltzmann triangulation ensemble; accepts uphill flips, less stable
     *         under strong deformation). We only want qualitative fluidity + stability, so default near-quench. */
    int flipSweep() {
        buildFlipIndex();
        double factor = 0.0; String s=System.getenv("BOA_FLIP_KT"); if(s!=null) factor=Double.parseDouble(s);  // default QUENCH (stable)
        double kT = factor * Env.Boltz * Env.tempK;   // factor=0 -> exp(-dE/0)=0 for dE>0 -> pure quench
        int acc=0; for (int e=0;e<ne;e++) { if (tryFlip(e, kT)) acc++; }
        flipTried=ne; flipAccepted=acc; return acc;
    }

    // ===================== Edge SPLIT — remesh growing protrusions (design doc §5) =====================
    // A growing protrusion (bleb, actin finger) stretches the existing triangles; flips can't fix that (they only
    // re-choose the diagonal). Split inserts a midpoint vertex on a too-long edge: 2 triangles -> 4, adding the
    // vertex, 3 edges, 2 faces (Euler-preserving). Grows nv/nf/ne within the capacity arrays. The new vertex is a
    // real MembraneVertex Thing (gets forces from next step's computeForces).

    /** Split edge e at its midpoint. Returns the new vertex index, or -1 if at capacity / invalid. */
    int edgeSplit(int e) {
        if (nv >= capV-1 || nf >= capF-2 || ne >= capE-3) return -1;             // capacity guard
        int a=edgeVert[2*e], b=edgeVert[2*e+1], f0=edgeFace[2*e], f1=edgeFace[2*e+1], c=edgeWing[2*e], d=edgeWing[2*e+1];
        if (c==d) return -1;
        int eac=findEdge(a,c), ebc=findEdge(b,c), ead=findEdge(a,d), ebd=findEdge(b,d);
        if (eac<0||ebc<0||ead<0||ebd<0) return -1;
        int m = nv;                                                              // new midpoint vertex (append)
        vert[m] = new MembraneVertex(new Pt3D(0.5*(vx(a)+vx(b)),0.5*(vy(a)+vy(b)),0.5*(vz(a)+vz(b))), vertexRadius, this, m);
        nv++;
        int fA=nf, fB=nf+1; nf+=2;                                              // new faces (m,b,c),(m,b,d)
        int eMB=ne, eMC=ne+1, eMD=ne+2; ne+=3;                                   // new edges (m,b),(m,c),(m,d); reuse e=(a,m)
        faceVert[3*f0]=a; faceVert[3*f0+1]=m; faceVert[3*f0+2]=c; orientFaceOutward(f0);  // f0 -> (a,m,c)
        faceVert[3*f1]=a; faceVert[3*f1+1]=m; faceVert[3*f1+2]=d; orientFaceOutward(f1);  // f1 -> (a,m,d)
        faceVert[3*fA]=m; faceVert[3*fA+1]=b; faceVert[3*fA+2]=c; orientFaceOutward(fA);  // fA = (m,b,c)
        faceVert[3*fB]=m; faceVert[3*fB+1]=b; faceVert[3*fB+2]=d; orientFaceOutward(fB);  // fB = (m,b,d)
        edgeVert[2*e]=Math.min(a,m); edgeVert[2*e+1]=Math.max(a,m); edgeFace[2*e]=f0; edgeFace[2*e+1]=f1; edgeWing[2*e]=c; edgeWing[2*e+1]=d;
        edgeVert[2*eMB]=Math.min(m,b); edgeVert[2*eMB+1]=Math.max(m,b); edgeFace[2*eMB]=fA; edgeFace[2*eMB+1]=fB; edgeWing[2*eMB]=c; edgeWing[2*eMB+1]=d;
        edgeVert[2*eMC]=Math.min(m,c); edgeVert[2*eMC+1]=Math.max(m,c); edgeFace[2*eMC]=f0; edgeFace[2*eMC+1]=fA; edgeWing[2*eMC]=a; edgeWing[2*eMC+1]=b;
        edgeVert[2*eMD]=Math.min(m,d); edgeVert[2*eMD+1]=Math.max(m,d); edgeFace[2*eMD]=f1; edgeFace[2*eMD+1]=fB; edgeWing[2*eMD]=a; edgeWing[2*eMD+1]=b;
        setEdgeFaceWing(eac, f0, f0, m);   // (a,c): stays in f0, apex b->m
        setEdgeFaceWing(ebc, f0, fA, m);   // (b,c): f0->fA, apex a->m
        setEdgeFaceWing(ead, f1, f1, m);   // (a,d): stays in f1, apex b->m
        setEdgeFaceWing(ebd, f1, fB, m);   // (b,d): f1->fB, apex a->m
        edgeIndex.remove(ekey(a,b));
        edgeIndex.put(ekey(a,m), e); edgeIndex.put(ekey(m,b), eMB); edgeIndex.put(ekey(m,c), eMC); edgeIndex.put(ekey(m,d), eMD);
        removeIncidence(b, e); addIncidence(b, eMB); addIncidence(c, eMC); addIncidence(d, eMD);
        addIncidence(m, e); addIncidence(m, eMB); addIncidence(m, eMC); addIncidence(m, eMD);
        if (arpLocal != null && m < arpLocal.length) arpLocal[m] = 0.5*(arpLocal[a]+arpLocal[b]);
        return m;
    }

    /** Split every edge longer than splitLen (remesh stretched/growing regions). Returns #splits. */
    int remeshSweep(double splitLen) {
        buildFlipIndex();
        if (hasSurfaceChem()) return 0;   // per-vertex chem (arp/formin/hot) not capacity-sized -> don't grow nv (would overflow them); this shell (the cortex / nucleating membrane) stays fixed-topology
        int ne0=ne, ns=0;                                           // snapshot: don't re-split this sweep's new edges
        for (int e=0;e<ne0;e++) if (edgeLen(edgeVert[2*e],edgeVert[2*e+1]) > splitLen) { if (edgeSplit(e)>=0) ns++; }
        return ns;
    }

    // ===================== Edge COLLAPSE — recover slivers / recycle vertices (design doc §5) =====================
    // The inverse of split: merge the two endpoints of a too-short edge into one vertex (placed at the midpoint),
    // removing 1 vertex, 3 edges, 2 faces (Euler-preserving). Split feeds vertices into a growing protrusion; collapse
    // recycles them from the depleting reservoir / the protrusion neck -- TOGETHER they are the discrete realization of
    // lipid FLOW (area streaming front-ward at ~constant total area), and collapse is what keeps the triangulation clean
    // (removes the compression slivers a deflating reservoir accumulates -- see RUN_LOGS bead_deflate: worst angle ->1.2
    // deg without it). Unlike split (which appends), collapse REMOVES from the middle of the flat arrays: we edit only
    // faceVert + the vert[] object array, then REDERIVE the whole edge/incidence/index structure from faces (the proven
    // ctor path) -- far less error-prone than maintaining wing-edges incrementally through a removal. The deleted vertex
    // Thing leaves theNodes immediately (removeNode) and theThings/SoA at the next cleanup (removeMe).
    //
    // GUARDED OFF whenever surface chemistry / formin is active: the per-vertex arpLocal/forminLocal pools (and actin
    // formin-anchor references) are not remapped here, so collapse currently serves the membrane-only protrusion tests.

    /** Re-derive edgeVert/edgeFace/edgeWing + vertEdge incidence + edgeIndex from the current faceVert (same
     *  derivation the ctor uses). Called after a collapse renumbers vertices/faces. Manifold-checks via deriveEdges. */
    private void rebuildTopologyFromFaces() {
        int[] valence = new int[nv];
        ArrayList<int[]> edges = deriveEdges(valence);   // throws on non-manifold (link condition should prevent)
        ne = edges.size();
        for (int e = 0; e < ne; e++) {
            int[] rec = edges.get(e);
            edgeVert[2*e]=rec[0]; edgeVert[2*e+1]=rec[1];
            edgeFace[2*e]=rec[2]; edgeFace[2*e+1]=rec[3];
            edgeWing[2*e]=rec[4]; edgeWing[2*e+1]=rec[5];
        }
        for (int v = 0; v < nv; v++) vertEdgeCt[v] = 0;
        for (int e = 0; e < ne; e++) { addIncidence(edgeVert[2*e], e); addIncidence(edgeVert[2*e+1], e); }
        edgeIndex = null; buildFlipIndex();
    }

    private double cco(int i,int u,int v,double m,int axis){
        if (i==u||i==v) return m;
        return axis==0?vx(i):axis==1?vy(i):vz(i);
    }
    /** Min interior angle (radians) of triangle given explicit coords. */
    private double triMinAngleCoords(double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz){
        double aa=ang(bx-ax,by-ay,bz-az, cx-ax,cy-ay,cz-az);
        double ab=ang(ax-bx,ay-by,az-bz, cx-bx,cy-by,cz-bz);
        double ac=ang(ax-cx,ay-cy,az-cz, bx-cx,by-cy,bz-cz);
        return Math.min(aa, Math.min(ab, ac));
    }
    private double ang(double ux,double uy,double uz,double wx,double wy,double wz){
        double d=(ux*wx+uy*wy+uz*wz)/(Math.sqrt(ux*ux+uy*uy+uz*uz)*Math.sqrt(wx*wx+wy*wy+wz*wz)+1e-30);
        return Math.acos(Math.max(-1,Math.min(1,d)));
    }

    /** Would collapsing (u,v) to (mx,my,mz) keep every RETAINED incident face non-inverted, non-degenerate, and
     *  above a min-angle floor? Evaluates each face around u or v (except the two deleted faces f0,f1) with both
     *  u and v placed at the midpoint. */
    private boolean collapseGeomOK(int u,int v,int f0,int f1,double mx,double my,double mz){
        int[] fl = new int[2*maxVal+8]; int n=0;
        for (int pass=0; pass<2; pass++){
            int vv = (pass==0)?u:v; int base=vv*maxVal, ct=vertEdgeCt[vv];
            for (int k=0;k<ct;k++){ int e=vertEdge[base+k];
                for (int s=0;s<2;s++){ int f=edgeFace[2*e+s];
                    if (f==f0||f==f1) continue;
                    boolean seen=false; for(int j=0;j<n;j++) if(fl[j]==f){seen=true;break;}
                    if(!seen){ if(n>=fl.length) return false; fl[n++]=f; }
                }
            }
        }
        final double MINA = Math.toRadians(8.0);
        for (int j=0;j<n;j++){ int f=fl[j];
            int a=faceVert[3*f], b=faceVert[3*f+1], c=faceVert[3*f+2];
            double ax=cco(a,u,v,mx,0),ay=cco(a,u,v,my,1),az=cco(a,u,v,mz,2);
            double bx=cco(b,u,v,mx,0),by=cco(b,u,v,my,1),bz=cco(b,u,v,mz,2);
            double cx=cco(c,u,v,mx,0),cy=cco(c,u,v,my,1),cz=cco(c,u,v,mz,2);
            double ux=bx-ax,uy=by-ay,uz=bz-az, wx=cx-ax,wy=cy-ay,wz=cz-az;
            double nx=uy*wz-uz*wy,ny=uz*wx-ux*wz,nz=ux*wy-uy*wx;
            if (0.5*Math.sqrt(nx*nx+ny*ny+nz*nz) < 1e-8) return false;            // near-degenerate
            double gx=(ax+bx+cx)/3-center.x, gy=(ay+by+cy)/3-center.y, gz=(az+bz+cz)/3-center.z;
            if (nx*gx+ny*gy+nz*gz <= 0) return false;                             // inverted (normal points inward)
            if (triMinAngleCoords(ax,ay,az,bx,by,bz,cx,cy,cz) < MINA) return false;
        }
        return true;
    }

    /** Swap-compact face fR out of faceVert (last live face moves into the hole). Edges are rederived afterward,
     *  so no edgeFace fix is needed here. */
    private void removeFaceCompact(int fR){
        int last=nf-1;
        if (fR!=last){ faceVert[3*fR]=faceVert[3*last]; faceVert[3*fR+1]=faceVert[3*last+1]; faceVert[3*fR+2]=faceVert[3*last+2]; }
        nf--;
    }
    /** Swap-compact vertex vR out of vert[]/faceVert (last live vertex moves into the hole), and retire the dead
     *  vertex Thing (theNodes now, theThings/SoA at cleanup). Assumes no LIVE face still references vR. */
    private void removeVertexCompact(int vR){
        MembraneVertex dead = vert[vR];
        int last=nv-1;
        if (vR!=last){
            vert[vR]=vert[last]; vert[vR].localIndex=vR;
            for (int f=0; f<nf; f++){
                if(faceVert[3*f]==last)faceVert[3*f]=vR;
                if(faceVert[3*f+1]==last)faceVert[3*f+1]=vR;
                if(faceVert[3*f+2]==last)faceVert[3*f+2]=vR;
            }
        }
        vert[last]=null;
        nv--;
        ProteinNode.removeNode(dead, dead.myNodeNumber);   // out of theNodes now; removeMe -> theThings/SoA at cleanup
    }

    /** Collapse edge e=(u,v): merge v into u at the edge midpoint. Returns true if performed. Validates the manifold
     *  LINK CONDITION (common neighbours of u,v == exactly the two wing vertices), valence bounds, and geometry (no
     *  inverted / degenerate / sliver retained face). On success the mesh is fully rederived from faces. */
    boolean edgeCollapse(int e){
        if (hasSurfaceChem()) return false;     // per-vertex chem / anchors not remapped under vertex compaction
        int u=edgeVert[2*e], v=edgeVert[2*e+1];
        int f0=edgeFace[2*e], f1=edgeFace[2*e+1], c=edgeWing[2*e], d=edgeWing[2*e+1];
        if (u==v || c==d) return false;
        int valU=vertEdgeCt[u], valV=vertEdgeCt[v];
        int mergedVal = valU + valV - 4;                               // u keeps its edges + v's, less e and the two merges
        if (mergedVal < VAL_MIN || mergedVal > Math.min(VAL_MAX, maxVal-1)) return false;
        if (vertEdgeCt[c]-1 < VAL_MIN || vertEdgeCt[d]-1 < VAL_MIN) return false;
        // LINK CONDITION: the only neighbours u and v share must be c and d (else collapse folds the surface).
        int baseV=v*maxVal, baseU=u*maxVal, common=0;
        for (int kv=0; kv<valV; kv++){ int ev=vertEdge[baseV+kv];
            int w = (edgeVert[2*ev]==v)?edgeVert[2*ev+1]:edgeVert[2*ev];
            if (w==u) continue;
            boolean nbU=false;
            for (int ku=0; ku<valU; ku++){ int eu=vertEdge[baseU+ku];
                int wu=(edgeVert[2*eu]==u)?edgeVert[2*eu+1]:edgeVert[2*eu]; if (wu==w){nbU=true;break;} }
            if (nbU){ if (w!=c && w!=d) return false; common++; }
        }
        if (common != 2) return false;
        double mx=0.5*(vx(u)+vx(v)), my=0.5*(vy(u)+vy(v)), mz=0.5*(vz(u)+vz(v));
        if (!collapseGeomOK(u,v,f0,f1,mx,my,mz)) return false;
        // ---- commit ----
        vert[u].setCoord(mx,my,mz);                                    // merged vertex sits at the midpoint
        for (int f=0; f<nf; f++){ if (f==f0||f==f1) continue;          // retarget v -> u in every retained face
            if (faceVert[3*f]==v)   faceVert[3*f]=u;
            if (faceVert[3*f+1]==v) faceVert[3*f+1]=u;
            if (faceVert[3*f+2]==v) faceVert[3*f+2]=u;
        }
        removeFaceCompact(Math.max(f0,f1));                            // descending: the mover is always a live face
        removeFaceCompact(Math.min(f0,f1));
        removeVertexCompact(v);                                        // u may have moved slot if u was the last vertex
        rebuildTopologyFromFaces();
        return true;
    }

    /** Collapse short edges (< minLen) until none remain collapsible. Returns #collapses. Restarts the scan after each
     *  success (indices renumber on rederive); terminates when a full pass collapses nothing. */
    int collapseSweep(double minLen){
        if (hasSurfaceChem()) return 0;
        buildFlipIndex();
        int done=0, guard=0;
        boolean progress=true;
        while (progress && guard++ < 2000){
            progress=false;
            for (int e=0;e<ne;e++){
                if (edgeLen(edgeVert[2*e],edgeVert[2*e+1]) < minLen && edgeCollapse(e)){ done++; progress=true; break; }
            }
        }
        return done;
    }

    /** Re-derive edges from faceVert and check manifold (every edge borders 2 faces), edge count, and Euler=2.
     *  An independent integrity check on the flip surgery. Returns true if valid. */
    boolean verifyMesh() {
        java.util.HashMap<Long,Integer> m = new java.util.HashMap<>(nf*3);
        for (int f=0; f<nf; f++) {
            int a=faceVert[3*f],b=faceVert[3*f+1],c=faceVert[3*f+2];
            m.merge(ekey(a,b),1,Integer::sum); m.merge(ekey(b,c),1,Integer::sum); m.merge(ekey(c,a),1,Integer::sum);
        }
        int bad=0; for (int v: m.values()) if (v!=2) bad++;
        int E=m.size(), euler=nv-E+nf;
        boolean ok = (bad==0 && E==ne && euler==2);
        if (!ok) Thing.talkln(String.format("[FLIP-VERIFY] FAIL edges=%d/%d nonmanifold=%d euler=%d", E, ne, bad, euler));
        return ok;
    }

    /** Drive every membrane's Stage-2 forces into its vertices' soaForceSum (pre-move hook), then any demo push. */
    public static void computeAllForces() {
        for (int m = 0; m < theMembranes.size(); m++) {
            Membrane mem = theMembranes.get(m);
            mem.computeForces();
            if (Env.dtsPushForce.getValue() != 0 && !mem.isCortex) mem.applyPushPatch();   // push the BILAYER only
            if (System.getenv("BOA_TIPVEL") != null) mem.applyTipForce();
            if (mem.arpLocal != null) mem.diffuseArp();    // surface chemistry (reaction-diffusion)
            if (mem.forminLocal != null) mem.forminStep(); // formin nucleation of mother filaments
        }
        applyMcaLinkers();   // two-shell coupling: sliding membrane-cortex linkers (no-op if single shell)
        // Bilayer fluidity: periodic edge-flip sweep (design doc §4). Env-gated (BOA_FLIP_N = steps per sweep).
        { int flipN = 0; String s=System.getenv("BOA_FLIP_N"); if(s!=null) flipN=Integer.parseInt(s);
          if (flipN>0 && Env.counter % flipN == 0) {
            for (int m=0;m<theMembranes.size();m++) {
                Membrane mem=theMembranes.get(m);
                int acc=mem.flipSweep();
                boolean ok = (Env.counter % (flipN*20)==0) ? mem.verifyMesh() : true;
                if (Env.counter % 500 == 0) Thing.talkln(String.format("[FLIP] step %d membrane#%d flips=%d/%d verify=%b",
                    Env.counter, m, acc, mem.flipTried, ok));
            }
          } }
        // Edge split remesh: subdivide stretched edges so growing protrusions get new vertices (design doc §5).
        // Env-gated BOA_REMESH_N = steps/sweep; BOA_REMESH_LEN = split threshold in units of l0 (default 1.5).
        { int rN=0; String s=System.getenv("BOA_REMESH_N"); if(s!=null) rN=Integer.parseInt(s);
          if (rN>0 && Env.counter % rN == 0) {
            double f=1.5; String s2=System.getenv("BOA_REMESH_LEN"); if(s2!=null) f=Double.parseDouble(s2);
            for (int m=0;m<theMembranes.size();m++) { Membrane mem=theMembranes.get(m);
                int ns=mem.remeshSweep(f*mem.l0);
                if (ns>0 && Env.counter % 500 == 0) Thing.talkln(String.format(
                    "[REMESH] step %d split=%d -> nv=%d nf=%d ne=%d verify=%b", Env.counter, ns, mem.nv, mem.nf, mem.ne, mem.verifyMesh()));
            }
          } }
        // Edge collapse remesh: recycle vertices from compressed/depleting regions (the inverse of split -- removes the
        // slivers a deflating reservoir / protrusion neck accumulates). BOA_COLLAPSE_N = steps/sweep; BOA_COLLAPSE_LEN =
        // collapse threshold in units of l0 (default 0.6). Pair with BOA_REMESH_* for a split+collapse [0.6,1.5]*l0 band.
        { int cN=0; String s=System.getenv("BOA_COLLAPSE_N"); if(s!=null) cN=Integer.parseInt(s);
          if (cN>0 && Env.counter % cN == 0) {
            double f=0.6; String s2=System.getenv("BOA_COLLAPSE_LEN"); if(s2!=null) f=Double.parseDouble(s2);
            for (int m=0;m<theMembranes.size();m++) { Membrane mem=theMembranes.get(m);
                int nc=mem.collapseSweep(f*mem.l0);
                if (nc>0 && Env.counter % 500 == 0) Thing.talkln(String.format(
                    "[COLLAPSE] step %d collapse=%d -> nv=%d nf=%d ne=%d verify=%b", Env.counter, nc, mem.nv, mem.nf, mem.ne, mem.verifyMesh()));
            }
          } }
        if (dtsProbe != null && !theMembranes.isEmpty()) theMembranes.get(0).applyProbeForces(dtsProbe);
        if (!bouncers.isEmpty() && !theMembranes.isEmpty()) theMembranes.get(0).applyBouncers();
        if (Env.dtsBranchOn.getValue() != 0) branchAllActin();        // Arp2/3 branching (gated by local Arp)
        if (Env.dtsActinCollide.getValue() != 0) collideAllActin();   // actin <-> membrane (containment + push)
        netForceDiag();
    }

    /** Diagnostic: sum ALL forces in the system after the actin phase. If total|F| ~ 0, momentum is conserved
     *  (any outward filament motion is force-partition + drag asymmetry, not a leak). If total|F| is large and
     *  radial, there is a genuine non-equal-and-opposite leak. Gated by BOA_FORCE_DIAG. */
    static void netForceDiag() {
        if (System.getenv("BOA_FORCE_DIAG") == null || Env.counter % 500 != 0 || theMembranes.isEmpty()) return;
        Pt3D C = theMembranes.get(0).center;
        double tfx=0,tfy=0,tfz=0, ffx=0,ffy=0,ffz=0, vfx=0,vfy=0,vfz=0, fcx=0,fcy=0,fcz=0; int nf=0;
        for (int i=0;i<Thing.thingCt;i++) {
            Thing t = Thing.theThings[i];
            if (t==null || t.removeMe) continue;
            int b = t.myThingNumber*3;
            double fx=Thing.soaForceSum[b], fy=Thing.soaForceSum[b+1], fz=Thing.soaForceSum[b+2];
            tfx+=fx; tfy+=fy; tfz+=fz;
            if (t instanceof FilSegment) { ffx+=fx; ffy+=fy; ffz+=fz; fcx+=t.getCoordX(); fcy+=t.getCoordY(); fcz+=t.getCoordZ(); nf++; }
            else if (t instanceof MembraneVertex) { vfx+=fx; vfy+=fy; vfz+=fz; }
        }
        double frad=0;
        if (nf>0){ double cx=fcx/nf-C.x, cy=fcy/nf-C.y, cz=fcz/nf-C.z; double l=Math.sqrt(cx*cx+cy*cy+cz*cz); if(l>0) frad=(ffx*cx+ffy*cy+ffz*cz)/l; }
        Thing.talkln(String.format("[NETF] step %d  total|F|=%.3e  fil|F|=%.3e(rad %+.3e)  vert|F|=%.3e  nfil=%d",
            Env.counter, Math.sqrt(tfx*tfx+tfy*tfy+tfz*tfz), Math.sqrt(ffx*ffx+ffy*ffy+ffz*ffz), frad,
            Math.sqrt(vfx*vfx+vfy*vfy+vfz*vfz), nf));
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
    /** TIP-tracking driver (clean filopodial driver, decoupled from actin/branching): the vertices within a
     *  SPATIAL radius of the current leading tip are advanced at a fixed VELOCITY along +x. As the tip advances
     *  the region tracks it, so the membrane behind flows into a tube of radius ~tipR -- unlike the fixed-angular
     *  cap push (bulb-on-stalk) or a fixed FORCE (runaway: all force lands on the lone leading vertex).
     *  Velocity control (per-vertex F = vel*gamma) avoids the runaway. Env: BOA_TIPVEL (um/s), BOA_TIPR (um). */
    void applyTipForce() {
        double vel = Double.parseDouble(System.getenv("BOA_TIPVEL"));   // um/s
        double tipR = 0.15; { String s=System.getenv("BOA_TIPR"); if(s!=null) tipR=Double.parseDouble(s); }
        double r2 = tipR*tipR;
        int L=-1; double maxx=-1e30;
        for (int v=0;v<nv;v++) if (vx(v)>maxx) { maxx=vx(v); L=v; }   // leading tip = max-x vertex
        if (L<0) return;
        double tx=vx(L),ty=vy(L),tz=vz(L);
        int n=0;
        for (int v=0;v<nv;v++){ double dx=vx(v)-tx,dy=vy(v)-ty,dz=vz(v)-tz;
            if(dx*dx+dy*dy+dz*dz<=r2){ vert[v].incForceSumSlot(vel*vert[v].bTransGam.x/1.0e6, 0, 0); n++; } }  // F=vel*gamma -> moves at ~vel
        if (Env.counter % 500 == 0)
            Thing.talkln(String.format("[DTS-TIP] step %d tipX=%.4f tipVerts=%d vel=%.1f nv=%d", Env.counter, maxx, n, vel, nv));
    }

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
            // TEST (BOA_MAX_MOTHERS): hard cap on total formin mothers, for single-filament isolation runs.
            { String mm = System.getenv("BOA_MAX_MOTHERS"); if (mm != null && forminMotherCt >= Integer.parseInt(mm)) break; }
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
            if (isCortex) {
                // TWO-SHELL (Stage 2): nucleate AT THE CORTEX. POINTED end (end1) anchored to the cortex vertex
                // (the reaction surface, one-sided tether); BARBED end (end2) points OUTWARD and grows toward the
                // bilayer, which it pushes via steric -- with NO barbed bond, so no superman ride-out (the reaction
                // is borne by the stiff cortex, the push deforms the compliant bilayer). end1 = center - halfLen*uVec,
                // so seat center = vertex + halfLen*outward to put the pointed end at the cortex vertex.
                double ox=-ix, oy=-iy, oz=-iz;   // outward unit (cortex vertex -> away from center, toward bilayer)
                Pt3D nucPt = new Pt3D(vx(v)+halfLen*ox, vy(v)+halfLen*oy, vz(v)+halfLen*oz);
                mother = FilSegment.makeForminMother(nucPt, new Pt3D(ox, oy, oz));   // barbed outward toward bilayer
                FilSegment.linkEnd1Node(mother, vert[v]);    // pointed end anchored to the cortex (reaction)
            } else if (Env.dtsForminGrowOut.isActive()) {
                // FORMIN holds the BARBED end (end2) a STANDOFF distance off the cortex; pointed end (end1)
                // trails into the cytoplasm. Seat the center so end2 (= center + halfLen*uVec, uVec=outward)
                // lands at the standoff point (vertex + standoff*inward) -- in agreement with the tether's
                // rest position, so the bond starts unstrained and never craters the vertex.
                double standoff = Env.dtsForminStandoff.getValue();
                Pt3D nucPt = new Pt3D(vx(v) + (halfLen+standoff)*ix, vy(v) + (halfLen+standoff)*iy, vz(v) + (halfLen+standoff)*iz);
                mother = FilSegment.makeForminMother(nucPt, new Pt3D(-ix, -iy, -iz));   // uVec outward -> barbed toward cortex
                FilSegment.linkEnd2Node(mother, vert[v]);        // formin holds the barbed end off the membrane
                // Capture the bond rest target FIXED in space (= the barbed end's birth position, vertex+standoff*inward).
                // The bond pulls toward THIS, not the live (bulging) vertex -> no anchor-follows-vertex drift feedback.
                mother.forminAnchorRef = new Pt3D(vx(v)+standoff*ix, vy(v)+standoff*iy, vz(v)+standoff*iz);
                mother.forminAnchorDir = new Pt3D(-ix, -iy, -iz);   // outward unit dir = the SLIDING anchor's fixed angular address
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
        // STERIC PUSH lands on the BILAYER (compliant surface actin deforms). The formin/Arp anchor bonds target
        // the CORTEX (set at nucleation, dereferenced by fs.end1Node/end2Node in collideActin), so the reaction is
        // borne by the stiff cortex -- not the pushed bilayer. In single-shell mode bilayer()==the sole shell (legacy).
        bilayer().collideActin();
    }

    // Anchor-spring magnitude (N), capped so a large offset (e.g. a freshly nucleated mother before the
    // spring relaxes) can never deliver a NaN-inducing kick. k is N/m; offset (ax,ay,az) is in microns,
    // so the linear force is k * (offset_um * 1e-6). Capped at ANCHOR_FMAX.
    private static double anchorMag(double k, double ax, double ay, double az) {
        double off = Math.sqrt(ax*ax+ay*ay+az*az);       // microns
        double f = k * off * 1.0e-6;                     // N
        double fmax = Env.dtsAnchorForceMax.getValue();  // tunable ceiling on the formin-membrane tether
        return f > fmax ? fmax : f;
    }

    void collideActin() {
        buildFaceGrid();
        double anchorTrackLambda = 1.0;   // 1=instant vertex tracking (legacy); <1 low-passes the drift feedback
        { String al = System.getenv("BOA_ANCHOR_LAMBDA"); if (al != null) anchorTrackLambda = Double.parseDouble(al); }
        double rad = FilSegment.radius;
        double ratchet = Env.dtsRatchetForce.getValue();
        double kAnchor = Env.dtsAnchorStiffness.getValue();
        double[] r1 = new double[3], r2 = new double[3];
        Pt3D force = new Pt3D(), pt = new Pt3D(), standoffTgt = new Pt3D();
        double standoff = Env.dtsForminStandoff.getValue();
        int contacts = 0;
        for (int i = 0; i < FilSegment.filSegmentCt; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            double e1x=fs.getEnd1X(), e1y=fs.getEnd1Y(), e1z=fs.getEnd1Z();
            double e2x=fs.getEnd2X(), e2y=fs.getEnd2Y(), e2z=fs.getEnd2Z();
            if (!(Double.isFinite(e1x)&&Double.isFinite(e2x)&&Double.isFinite(e1y)&&Double.isFinite(e2y)
                  &&Double.isFinite(e1z)&&Double.isFinite(e2z))) { fs.removeMe = true; continue; }  // cull NaN filaments
            if (kAnchor > 0) {
                // FORMIN holds the BARBED end (end2) a small STANDOFF distance off the cortex: a TWO-SIDED
                // spring between end2 and the standoff point (just inside the vertex). Targeting the standoff
                // -- not the vertex itself -- makes the tether agree with where the steric collision keeps the
                // filament, so there is no tether-vs-collision battle and the vertex feels ~zero net reaction
                // (the membrane stays smooth). As the barbed end elongates against the membrane the spring
                // still pulls the vertex outward (the formin/filopodial push).
                if (fs.nodeAtEnd2 && fs.end2Node != null && !fs.end2Node.removeMe) {
                    // SLIDING anchor: re-select the bonded vertex as the one now nearest the mother's FIXED angular
                    // address, so the anchor stays put in angle while the mesh flows under it (instead of surfing the
                    // tangential mesh flow). Needs the gentle PAIRS bond so the vertex hand-offs don't jump.
                    if (System.getenv("BOA_SLIDE_ANCHOR") != null && fs.forminAnchorDir != null) {
                        int sv = nearestVertexToDir(fs.forminAnchorDir.x, fs.forminAnchorDir.y, fs.forminAnchorDir.z);
                        if (sv >= 0 && vert[sv] != fs.end2Node) { fs.end2Node = vert[sv]; fs.forminAnchorRef = null; }
                    }
                    ProteinNode an = fs.end2Node;
                    double tx, ty, tz;
                    if (an instanceof MembraneVertex) {
                        // LOW-PASS anchor: the bond rest target tracks the live (bulging) vertex, but only at
                        // rate lambda per step. lambda=1 -> instant tracking (legacy; stable but the daughter
                        // push feeds back step-to-step -> the pair rides the bulge outward = the drift).
                        // lambda<1 -> the target lags fast membrane motion, damping the feedback, while still
                        // following slow legitimate protrusion (so it stays low-strain -> stable, unlike a
                        // space-fixed anchor which builds huge strain and blows up).
                        ((MembraneVertex)an).forminStandoffTarget(standoffTgt, standoff);   // live standoff target
                        double lam = anchorTrackLambda;
                        if (fs.forminAnchorRef == null) fs.forminAnchorRef = new Pt3D(standoffTgt.x,standoffTgt.y,standoffTgt.z);
                        else {
                            fs.forminAnchorRef.x += lam*(standoffTgt.x - fs.forminAnchorRef.x);
                            fs.forminAnchorRef.y += lam*(standoffTgt.y - fs.forminAnchorRef.y);
                            fs.forminAnchorRef.z += lam*(standoffTgt.z - fs.forminAnchorRef.z);
                        }
                        tx = fs.forminAnchorRef.x; ty = fs.forminAnchorRef.y; tz = fs.forminAnchorRef.z;
                    } else { tx = an.getCoordX(); ty = an.getCoordY(); tz = an.getCoordZ(); }
                    double ax = (tx-e2x), ay = (ty-e2y), az = (tz-e2z);
                    double off = Math.sqrt(ax*ax+ay*ay+az*az); if (off < 1e-12) off = 1;
                    double m;
                    // TEST (#2): PAIRS drag-weighted bond with a tunable membrane-side effective drag, instead of
                    // the fixed-stiffness spring. BOA_BOND_PAIRS=<scale> uses m=(strain/dt)/(1/gFil + 1/(scale*gVert)).
                    // A small scale makes the membrane the "light" element so the bond conforms the membrane rather
                    // than dragging the filament network outward (see drift discussion). Default (env unset) = legacy spring.
                    String pairsEnv = System.getenv("BOA_BOND_PAIRS");
                    if (pairsEnv != null) {
                        double scale = Double.parseDouble(pairsEnv);
                        double gMemEff = Math.max(1e-30, scale * an.bTransGam.x);   // effective membrane drag for the bond
                        double cdt = Env.collisionDeltaT.getValue();
                        m = (1.0e-6 * off / cdt) / (1.0/fs.bTransGam.y + 1.0/gMemEff);
                        double fmaxc = Env.dtsAnchorForceMax.getValue();
                        if (m > fmaxc) m = fmaxc;
                    } else {
                        m = anchorMag(kAnchor, ax, ay, az);   // legacy fixed-stiffness spring, N, capped
                    }
                    double fxA=m*ax/off, fyA=m*ay/off, fzA=m*az/off;
                    force.setVals(fxA,fyA,fzA); pt.setVals(e2x,e2y,e2z); fs.incForceSum(force, pt);   // barbed end -> vertex
                    an.incForceSumSlot(-fxA,-fyA,-fzA);                                                // vertex -> barbed end (push cortex out)
                    fs.dbgAnchorF = m; fs.dbgAnchorOff = off;   // for the single-filament force-state diagnostic
                }
                // pointed-end mother: tether the pointed (base) end to its anchor vertex.
                else if (fs.nodeAtEnd1 && fs.end1Node != null && !fs.end1Node.removeMe) {
                    ProteinNode an = fs.end1Node;
                    double ax = (an.getCoordX()-e1x), ay = (an.getCoordY()-e1y), az = (an.getCoordZ()-e1z);
                    double m = anchorMag(kAnchor, ax, ay, az);
                    double off = Math.sqrt(ax*ax+ay*ay+az*az); if (off < 1e-12) off = 1;
                    double fx=m*ax/off, fy=m*ay/off, fz=m*az/off;
                    force.setVals(fx,fy,fz); pt.setVals(e1x,e1y,e1z); fs.incForceSum(force, pt);
                    // TWO-SHELL (Stage 2): when the base is anchored to the CORTEX, make the tether TWO-SIDED so the
                    // filament's push-reaction loads the cortex INWARD. This is what makes the cortex the genuine
                    // reaction surface: the inward actin reaction counters the MCA's outward drag (the bilayer bulge
                    // pulling the cortex along), so the cortex holds and the anchored actin does NOT ride out. On the
                    // single shell this stays one-sided (loading the sole membrane inward would just dimple it).
                    if (an instanceof MembraneVertex && ((MembraneVertex)an).owner.isCortex)
                        an.incForceSumSlot(-fx, -fy, -fz);
                }
            }
            if (System.getenv("BOA_BRANCH_DIAG") != null && fs.forminMother && fs.arpChildCt > 0 && Env.counter % 200 == 0) {
                double rB = Math.sqrt((e2x-center.x)*(e2x-center.x)+(e2y-center.y)*(e2y-center.y)+(e2z-center.z)*(e2z-center.z));
                double rRef = (fs.forminAnchorRef!=null)? Pt3D.ptDist(fs.forminAnchorRef, center) : -1;
                Thing.talkln(String.format("[BR-TRAJ] step %d motherId=%d kids=%d  barbedR=%.4f anchorRefR=%.4f strain=%.5f",
                    Env.counter, fs.thingInstanceId, fs.arpChildCt, rB, rRef, Math.abs(rB-rRef)));
            }
            double gSeg = fs.bTransGam.y;                              // perpendicular filament drag
            if (fs.childOfArp23 && System.getenv("BOA_NO_DAUGHTER_STERIC") != null) { r1[0]=r1[1]=r1[2]=0; r2[0]=r2[1]=r2[2]=0; }
            else segmentVsMembrane(e1x,e1y,e1z, e2x,e2y,e2z, rad, gSeg, r1, r2);
            // SINGLE-FILAMENT force-state diagnostic (BOA_MOM_DIAG): log every formin mother each output frame so a
            // reported "frame interval" can be mapped to its force state (anchor force, steric reaction, tip clearance,
            // length, drag, cap/bond flags) -- to catch the occasional "force state jump".
            { int momInt = 0; { String s = System.getenv("BOA_MOM_DIAG"); if (s != null) momInt = Integer.parseInt(s); }
              if (momInt > 0 && fs.forminMother && Env.counter % momInt == 0) {
                double sMag = Math.sqrt((r1[0]+r2[0])*(r1[0]+r2[0])+(r1[1]+r2[1])*(r1[1]+r2[1])+(r1[2]+r2[2])*(r1[2]+r2[2]));
                double rB = Math.sqrt((e2x-center.x)*(e2x-center.x)+(e2y-center.y)*(e2y-center.y)+(e2z-center.z)*(e2z-center.z));
                double tR = (fs.forminAnchorRef!=null)? Pt3D.ptDist(fs.forminAnchorRef, center) : -1;
                ProteinNode vn = fs.end2Node; double vR = (vn!=null)? Math.sqrt((vn.getCoordX()-center.x)*(vn.getCoordX()-center.x)+(vn.getCoordY()-center.y)*(vn.getCoordY()-center.y)+(vn.getCoordZ()-center.z)*(vn.getCoordZ()-center.z)) : -1;
                double angD = -1;
                if (fs.forminAnchorDir != null) { double bx=(e2x-center.x),by=(e2y-center.y),bz=(e2z-center.z),bl=Math.sqrt(bx*bx+by*by+bz*bz);
                    if (bl>1e-12) angD = Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(bx*fs.forminAnchorDir.x+by*fs.forminAnchorDir.y+bz*fs.forminAnchorDir.z)/bl)))); }
                Thing.talkln(String.format("[MOM] step %d barbedR=%.4f targetR=%.4f vertR=%.4f radialOff=%+.4f anchorF=%.3e steric=%.3e len=%.4f angDrift=%.2f",
                    Env.counter, rB, tR, vR, rB-tR, fs.dbgAnchorF, sMag, fs.length, angD));
              } }
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
    private final Pt3D scratchTip = new Pt3D();

    /** Index of the vertex whose outward radial direction is closest to (dx,dy,dz) (a unit dir from center).
     *  Used by the SLIDING anchor to re-select the bonded vertex at a fixed angular address as the mesh flows. */
    public int nearestVertexToDir(double dx, double dy, double dz) {
        int best = -1; double bestDot = -2;
        for (int v = 0; v < nv; v++) {
            double ox=vx(v)-center.x, oy=vy(v)-center.y, oz=vz(v)-center.z;
            double l=Math.sqrt(ox*ox+oy*oy+oz*oz); if (l<1e-12) continue;
            double d=(ox*dx+oy*dy+oz*dz)/l;
            if (d>bestDot) { bestDot=d; best=v; }
        }
        return best;
    }

    /** Signed distance of a point to the membrane surface along the nearest face normal: &gt;0 outside,
     *  &lt;0 inside. Returns a large negative (deep inside) if no face resolves. Uses {@code scratchCp}. */
    public double signedDistanceToSurface(double x, double y, double z) {
        int f = nearestFace(x, y, z, scratchCp);
        if (f < 0) return -1e30;
        return (x-scratchCp[0])*fNx[f] + (y-scratchCp[1])*fNy[f] + (z-scratchCp[2])*fNz[f];
    }

    /** Arp2/3 branch nucleation off membrane-proximal filaments, gated by the local Arp field. A filament
     *  whose barbed tip is near the cortex in a high-Arp region branches: a daughter nucleates at the branch
     *  angle, tilted toward the membrane normal (dendritic geometry). Rate ∝ local Arp; each branch consumes
     *  Arp (negative feedback). Bounded by the Arp field + dtsMaxFilaments cap. */
    public static void branchAllActin() {
        if (theMembranes.isEmpty() || FilSegment.filSegmentCt == 0) return;
        Membrane m = cortex();            // Arp field lives on the cortex (two-shell); fall back to the sole shell
        if (m == null) m = bilayer();
        if (m == null || m.arpLocal == null) return;
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
        int cand=0, nearCortex=0, rejBorn=0;   // gate counters (pipeline-health diagnostics)
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
            // BIRTH-CLEARANCE GATE: refuse a branch whose SEED daughter would be born already loading the
            // cortex. Such daughters (held at the ~70deg geometry, partway through the surface) drive a
            // sustained steric push on the compliant membrane -> the formin-anchored pair rides the bulge
            // outward (the directional drift). Require the seed barbed tip to start at least one steric
            // contact distance INSIDE the surface, so the daughter loads the membrane only as it GROWS
            // (the Mogilner-Oster ratchet then throttles it) -- a gentle, growth-driven push, not a static shove.
            if (System.getenv("BOA_BRANCH_BIRTH_GATE") != null) {   // opt-in; over-prunes near-cortex branches (see journal)
                double tipClear = FilSegment.radius + vertexRadius + 0.005;   // born this far inside, minimum
                fs.prospectiveDaughterTipInto(bLoc, scratchTip);
                if (signedDistanceToSurface(scratchTip.x, scratchTip.y, scratchTip.z) > -tipClear) { rejBorn++; continue; }
            }
            fs.makeArpBranch(bLoc);
            // TEST (BOA_BREAK_BOND_ON_BRANCH): sever the mother's formin-membrane bond the moment it branches.
            // The mother keeps its high-drag daughter, so it shouldn't diffuse far. If it STILL shows directed
            // outward motion, the bond is not the driver.
            if (System.getenv("BOA_BREAK_BOND_ON_BRANCH") != null && fs.nodeAtEnd2 && fs.end2Node != null) {
                fs.end2Node.filamentOff(); fs.end2Node = null; fs.nodeAtEnd2 = false; fs.forminAnchorRef = null;
            }
            arpLocal[a]=Math.max(0,arpLocal[a]-consume);
            arpLocal[b]=Math.max(0,arpLocal[b]-consume);
            arpLocal[c]=Math.max(0,arpLocal[c]-consume);
            made++; branchCt++;
        }
        if (Env.counter % 1000 == 0) {
            Thing.talkln(String.format("[DTS-BRANCH] step %d  fils=%d nearCortex=%d rejBorn=%d  totalBranches=%d",
                    Env.counter, cand, nearCortex, rejBorn, branchCt));
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
        double reaction;
        if (System.getenv("BOA_PROBE_SURFACE") != null) {
            // EXPERIMENTAL continuous-surface (no vertex gaps). WIP: a FREE-DRIVEN sphere is not yet robustly
            // contained by closest-face steric -- the bead chases the fleeing contact (the drag-coupled split moves
            // the lighter membrane vertex ~80% of the gap closure, so the bulge flees rather than the bead stopping;
            // a soft spring has the mirror failure). The per-vertex steric below CUPS the bead (3D ball of contacts
            // that re-captures as it advances) and contains it at adequate force. Robust surface containment for a
            // self-driven sphere needs a signed-distance hard one-sided constraint or an ADHERED (bonded) bead --
            // see JOURNAL 2026-06-22. Kept opt-in for that follow-up.
            buildFaceGrid();
            reaction = stericNodeVsMembraneSurface(probe, reac);
        } else {
            reaction = stericNodeVsMembrane(probe, reac);            // per-vertex cup (default; robust at adequate force)
        }
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

    /**
     * CONTINUOUS-SURFACE steric: a sphere (node) vs the TRIANGULATED surface — the robust replacement for the
     * per-vertex {@link #stericNodeVsMembrane} (which has face-center gaps a small/marginally-engaged bead slips
     * through, esp. while split/collapse reshuffle the contact cap — see RUN_LOGS 2026-06-22 tether_F6e-11). Here
     * the bead is pushed back against the closest point on every nearby FACE (point-vs-triangle, no gaps), the
     * reaction distributed to that face's 3 vertices by the closest-point barycentric weights. There is no force
     * regime where the bead can slip between vertices: the faces tile the surface continuously.
     *
     * EFFICIENCY: two-level. (1) COARSE GATE — if the bead is closer to the membrane centroid than its nearest
     * surface vertex minus the bead's reach, it provably can't contact anything: return immediately (O(1), no grid
     * query). (2) NARROW PHASE — only the faces in the few grid cells the bead overlaps are tested (grid built by
     * {@link #buildFaceGrid}), ~O(1) faces per node instead of O(nf). Requires fresh face normals (computeForces)
     * and a current face grid (caller builds it). Soft penetration spring (dtsStericStiffness) + hard inward
     * recovery if the center has crossed outside (dtsStericRecover), matching segmentVsMembrane's containment.
     */
    double stericNodeVsMembraneSurface(ProteinNode node, double[] reacOut) {
        double pr = node.getRadius();
        double px = node.getCoordX(), py = node.getCoordY(), pz = node.getCoordZ();
        double contact = pr + vertexRadius;
        reacOut[0]=reacOut[1]=reacOut[2]=0;
        stericContactMaxR = 0;
        if (gHead == null) return 0;                                   // grid not built -> caller error; no-op
        // (1) coarse gate: deep inside -> no possible contact (l0 margin covers a face bulging in past its vertices)
        double dcx=px-bgCx, dcy=py-bgCy, dcz=pz-bgCz;
        double dc = Math.sqrt(dcx*dcx+dcy*dcy+dcz*dcz);
        if (dc + contact + l0 < bgRminVert) return 0;
        // (2) narrow phase: faces in the cells the bead's reach overlaps (centroid within contact+l0 of the bead)
        int reach = (int)Math.ceil((contact + l0) / gCell);
        int ix=axisIdx(px,gx0,gnx), iy=axisIdx(py,gy0,gny), iz=axisIdx(pz,gz0,gnz);
        double rx=0, ry=0, rz=0, maxContactR=0;
        double[] cp = new double[6];
        double recover = Env.dtsStericRecover.getValue();
        double cdt = Env.collisionDeltaT.getValue();
        double gb = node.bTransGam.x;                                 // bead translational drag (N.s/m)
        for (int dx=-reach; dx<=reach; dx++) for (int dy=-reach; dy<=reach; dy++) for (int dz=-reach; dz<=reach; dz++) {
            int cx=ix+dx, cy=iy+dy, cz=iz+dz;
            if (cx<0||cx>=gnx||cy<0||cy>=gny||cz<0||cz>=gnz) continue;
            for (int f=gHead[(cx*gny+cy)*gnz+cz]; f>=0; f=gNext[f]) {
                int a=faceVert[3*f], b=faceVert[3*f+1], c=faceVert[3*f+2];
                closestPtTri(px,py,pz, a,b,c, cp);
                double ddx=px-cp[0], ddy=py-cp[1], ddz=pz-cp[2];
                if (ddx*ddx+ddy*ddy+ddz*ddz >= contact*contact) continue;
                double nx=fNx[f], ny=fNy[f], nz=fNz[f];
                double signed = ddx*nx + ddy*ny + ddz*nz;             // >0 = bead center outside this face
                if (signed <= -contact) continue;
                double pen = signed + contact;                        // >0
                // DRAG-COUPLED stiff wall (same form as segmentVsMembrane, which reliably contains actin): the force
                // that would close the penetration in one collision step, shared by the bead/vertex drags. A SOFT
                // kSteric spring instead lets the low-drag vertices outrun the heavier bead -> the bulge flees, the
                // gap opens past `contact`, and the bead is lost (the punch-through this method exists to kill).
                double gv = vert[a].bTransGam.x;
                double mag = (1.0e-6 * pen / cdt) / (1.0/gb + 1.0/gv);
                if (signed > 0) mag += recover * signed * 1.0e-6;     // hard recovery once the center has crossed
                if (mag > 2.0e-10) mag = 2.0e-10;                     // bound (low-drag overshoot -> NaN otherwise)
                double wa=cp[3], wb=cp[4], wc=cp[5];
                vert[a].incForceSumSlot(mag*nx*wa, mag*ny*wa, mag*nz*wa);   // push membrane OUTWARD (+n),
                vert[b].incForceSumSlot(mag*nx*wb, mag*ny*wb, mag*nz*wb);   // distributed by closest-point
                vert[c].incForceSumSlot(mag*nx*wc, mag*ny*wc, mag*nz*wc);   // barycentric weights
                node.incForceSumSlot(-mag*nx, -mag*ny, -mag*nz);           // reaction pushes the bead INWARD
                rx -= mag*nx; ry -= mag*ny; rz -= mag*nz;
                double cr=Math.sqrt((cp[0]-center.x)*(cp[0]-center.x)+(cp[1]-center.y)*(cp[1]-center.y)+(cp[2]-center.z)*(cp[2]-center.z));
                if (cr>maxContactR) maxContactR=cr;
            }
        }
        stericContactMaxR = maxContactR;
        reacOut[0]=rx; reacOut[1]=ry; reacOut[2]=rz;
        return Math.sqrt(rx*rx+ry*ry+rz*rz);
    }

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
        // DEFAULT: collide only the two ENDPOINTS as tiny spheres (K=2 -> samples at t=0=end1 and t=1=end2, each
        // pushed through the continuous-surface face logic below). A filament is a chain of short segments, so the
        // per-segment endpoint spheres together tile the whole filament: the BARBED tip pushes the cortex, a POINTED
        // (or free) end also blocks, and there is no dense body-spearing of the membrane -- which (with the
        // flip/split/collapse remesher) removes the sharp-push-into-degenerate-triangle source of the 1/sinTheta
        // bending blow-up. BOA_ACTIN_CAPSULE restores dense whole-capsule sampling (the old containment-wall behaviour).
        int K = (System.getenv("BOA_ACTIN_CAPSULE") != null) ? Math.max(2, (int)Math.ceil(sLen / (0.4*l0)) + 1) : 2;
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
                // Two cap regimes:
                //  - PROTRUSION (sample still inside, signed<=0, pressing out): cap at the actin polymerization
                //    STALL force (dtsActinPushMax ~5 pN). A growing Arp2/3 daughter then pushes the cortex only
                //    as hard as a real filament can before stalling (the Mogilner-Oster ratchet throttles its
                //    growth as the gap closes) -- gentle, self-limiting, no stiff push that convolutes the cortex.
                //  - CONTAINMENT (sample crossed outside, signed>0): keep the stiff hard recovery, capped at
                //    2e-10, so a leak is yanked back in (safety; rarely triggered).
                double pushCap;
                if (signed > 0) {
                    mag += Env.dtsStericRecover.getValue() * signed * 1.0e-6;
                    pushCap = 2.0e-10;   // a thin low-drag filament can otherwise overshoot under the hard
                                         // recovery -> runaway -> NaN. Bound it.
                } else {
                    pushCap = Env.dtsActinPushMax.getValue();   // stall-force-limited protrusion push
                }
                if (mag > pushCap) mag = pushCap;
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
    private double bgCx, bgCy, bgCz, bgRminVert; // coarse-gate: membrane centroid + nearest-vertex radius
    private final double[] scratchCp = new double[7];

    private int axisIdx(double v, double v0, int n) { int i=(int)((v-v0)/gCell); return i<0?0:(i>=n?n-1:i); }

    /** Bin the faces (by centroid) into a uniform grid over the membrane bbox; cell ~2 mean edges so a face's
     *  neighbourhood is covered by the 3x3x3 cells around any query point. O(nf), rebuilt each collision step. */
    public void buildFaceGrid() {
        double minx=1e30,miny=1e30,minz=1e30,maxx=-1e30,maxy=-1e30,maxz=-1e30;
        double sumx=0,sumy=0,sumz=0;
        for (int v=0; v<nv; v++) {
            double x=vx(v),y=vy(v),z=vz(v);
            if(x<minx)minx=x; if(y<miny)miny=y; if(z<minz)minz=z;
            if(x>maxx)maxx=x; if(y>maxy)maxy=y; if(z>maxz)maxz=z;
            sumx+=x; sumy+=y; sumz+=z;
        }
        // COARSE-GATE (broad phase): membrane centroid + nearest surface-vertex radius. A node closer to the
        // centroid than (rMinVert - its reach) provably can't touch any face -> skip the narrow phase entirely.
        // Cheap O(nv), reused by stericNodeVsMembraneSurface; matters most for a population of interior nodes.
        bgCx=sumx/nv; bgCy=sumy/nv; bgCz=sumz/nv;
        double rmin2=1e30;
        for (int v=0; v<nv; v++) { double ax=vx(v)-bgCx, ay=vy(v)-bgCy, az=vz(v)-bgCz; double r2=ax*ax+ay*ay+az*az; if(r2<rmin2)rmin2=r2; }
        bgRminVert=Math.sqrt(rmin2);
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
        if (mPx != null) return;   // sized to CAPACITY so split/collapse growth never outruns them
        mPx = new double[capV]; mPy = new double[capV]; mPz = new double[capV];
        fNx = new double[capF]; fNy = new double[capF]; fNz = new double[capF]; fArea = new double[capF];
        eLen = new double[capE]; eTheta = new double[capE];
        vA = new double[capV]; vC = new double[capV]; vAlpha = new double[capV]; vBeta = new double[capV];
        fX = new double[capV]; fY = new double[capV]; fZ = new double[capV];
    }

    private static final double UM_TO_M = 1.0e-6;

    /**
     * Recompute all geometry (per-face normal/area, per-edge length/dihedral, per-vertex curvature
     * c_v and area A_v) and the three energy components, from the CURRENT vertex pose. Fills the
     * scratch arrays and {@link #areaTot}/{@link #volTot}. Positions are pulled in metres.
     * Returns total energy (J). Shared by {@link #totalEnergy} and {@link #computeForces}.
     */
    /** Bilayer tracks runtime-mutable Env stiffness each step; the cortex keeps its (stiffer) overrides. */
    private void refreshMechFromEnvIfBilayer() {
        if (isCortex) return;
        kappaBend   = Env.dtsKappaBend.getValue();
        kappaArea   = Env.dtsKappaArea.getValue();
        kappaVolume = Env.dtsKappaVolume.getValue();
        targetRedVol= Env.dtsTargetReducedVol.getValue();
    }

    private double computeGeometryAndEnergy() {
        ensureScratch();
        refreshMechFromEnvIfBilayer();
        double kappa = kappaBend;
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
        double KA = kappaArea;
        double KV = kappaVolume;
        double vt = targetRedVol;
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
        refreshMechFromEnvIfBilayer();
        double kappa = kappaBend;
        double KA = kappaArea;
        double KV = kappaVolume;
        double vt = targetRedVol;
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
                if (sinT < 1.0e-2) sinT = 1.0e-2;   // FLOOR the dihedral sine (~0.57deg). Without this, when actin
                                                    // folds two faces toward coplanar (dot->+-1) the exact gradient's
                                                    // 1/sinT term explodes to ~1e150 N -> the cortex flings outward
                                                    // ("superman"). Normal nu=4 dihedrals have sinT~0.04, so this
                                                    // floor never touches the FD-validated regime.
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

        // ---- Write per-vertex force into soaForceSum (N), with a backstop cap ----
        // Hard-cap the assembled per-vertex membrane force. Legitimate bending/area/volume forces are ~1e-11 N;
        // this 1e-9 ceiling is 100x above them yet bounds any residual degenerate-triangle blow-up (the sinT
        // floor above is the primary guard; this is the safety net the journal flagged -- "cap the assembled
        // per-vertex membrane force like the actin caps"). Tunable via BOA_DTS_FMAX.
        double FMAX = 1.0e-9;
        { String fx = System.getenv("BOA_DTS_FMAX"); if (fx != null) FMAX = Double.parseDouble(fx); }
        double maxF = 0;
        int capped = 0;
        for (int v = 0; v < nv; v++) {
            double fx=fX[v], fy=fY[v], fz=fZ[v];
            double fm = fx*fx+fy*fy+fz*fz;
            if (fm > FMAX*FMAX) { double s = FMAX/Math.sqrt(fm); fx*=s; fy*=s; fz*=s; fm=FMAX*FMAX; capped++; }
            vert[v].incForceSumSlot(fx, fy, fz);
            if (fm > maxF) maxF = fm;
        }
        if (capped > 0 && Env.counter % 500 == 0)
            Thing.talkln(String.format("[DTS-E] step %d  WARNING %d vertices hit the force cap (FMAX=%.1e N) -- degenerate triangles", Env.counter, capped, FMAX));
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
