package boxOfActin;

/**
 * A single vertex of a DTS (dynamically-triangulated-surface) {@link Membrane}.
 *
 * Deliberately a LIGHTWEIGHT node: it extends {@link ProteinNode} purely to reuse the
 * existing SoA pose, overdamped Langevin mover ({@code moveThing}), Brownian forcing,
 * and steric-collision machinery — but it carries no surface myosins, and it is
 * STRUCTURAL (no stochastic turnover). It is intentionally NOT a {@link StickyNode}:
 * the DTS membrane is a new subsystem and does not use the legacy sticky-point /
 * NodeLink spring apparatus, nor the StickyNode {@code membraneNodeDragScale} drag
 * reduction (the 1e-10 force-scale trap — see MEMBRANE_DTS_DESIGN.md sec 0).
 *
 * The vertex's canonical position is its SoA pose slot (reused by the move/force
 * kernels); the owning {@link Membrane} references vertices by object handle, reading
 * live pose through {@code getCoordX/Y/Z}. Stage 1 attaches no membrane forces, so a
 * fresh membrane is just a static (optionally Brownian-jittering) icosphere.
 */
public final class MembraneVertex extends ProteinNode {

    /** The membrane this vertex belongs to (multi-instance: a sim may hold several). */
    public final Membrane owner;
    /** This vertex's index within {@code owner}'s flat topology arrays. */
    public final int localIndex;

    public MembraneVertex(Pt3D initCoord, double radius, Membrane owner, int localIndex) {
        super(initCoord, radius, false);   // no myosins
        this.owner = owner;
        this.localIndex = localIndex;
    }

    /**
     * PHYSICAL Stokes drag (gamma = 6 pi eta r), scale = 1.0. Deliberately bypasses both
     * the StickyNode {@code membraneNodeDragScale} reduction and the nodeTransDiff/nodeRotDiff
     * overrides: DTS forces are physical N-scale forces and must see physical drag, or they
     * are silently swallowed (the trap that decoupled the old membrane). See
     * MEMBRANE_DTS_DESIGN.md sec 0 & 7.
     *
     * Called from the ProteinNode constructor via virtual dispatch (radius is already set).
     */
    @Override
    public void calculateProperties() {
        double radiusM = getRadius() * 1.0e-6;          // microns -> metres
        double eta = Env.aeta.getValue();               // BoA effective viscosity (Pa.s)
        bTransGam.x = 6 * Math.PI * eta * radiusM;
        bTransGam.y = bTransGam.x;
        bTransGam.z = bTransGam.x;
        bTransDiff.div(Env.Boltz * Env.tempK, bTransGam);   // Einstein D = kT/gamma
        bRotGam.x = 8 * Math.PI * eta * (radiusM * radiusM * radiusM);
        bRotGam.y = bRotGam.x;
        bRotGam.z = bRotGam.x;
        bRotDiff.div(Env.Boltz * Env.tempK, bRotGam);
        pushDragToSoa();
    }

    /** Structural: DTS vertices never undergo stochastic node turnover. Topology changes
     *  (split / collapse / flip) are explicit, later-stage operations. */
    @Override
    public void biochemStep() {
        // intentionally empty
    }

    /** Skip the chamber/box wall collision the generic ProteinNode.step() applies. A DTS cell
     *  membrane IS the boundary; letting its vertices collide with the simulation chamber injects
     *  a large spurious wall force (∝ bTransGam/collisionDeltaT) that swamps the physical membrane
     *  forces and destabilizes the surface. (Actin/probe coupling is added explicitly in Stage 3.) */
    @Override
    public void step() {
        // intentionally empty — no box/bug collision for membrane vertices
    }
}
