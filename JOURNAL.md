# BoxOfActin Project Journal

## 2026-06-17 — Membrane v2 DTS, STAGE 3a: activated-Arp2/3 reaction-diffusion field on the membrane

First piece of actin coupling: a per-vertex **activated-Arp2/3 concentration** that's produced at NPF "hot"
patches and **diffuses across the membrane over the wing-edge graph** (the substrate Stage-3b branched
nucleation will read). Ported from the legacy StickyNode `arpLocal` mechanism onto the DTS vertices.

Kept **kernel-shaped** (GPU-portable, per the CPU-now decision): per-EDGE gather of `(c_j−c_i)` into a
per-vertex Laplacian, then per-vertex update — the same gather pattern as the bending forces.
`c_i += α·Σ_{e∋i}(c_j−c_i) − k_e·c_i`; NPF hot patches are a **clamped source** (held at target — the soft
`k_e·target` source was too weak vs. diffusion and flattened the field). Hot patches marked on cube-corner
directions (like the old `markHotPatches`). Steady-state halo length ≈ `sqrt(α/k_e)` edges; reaches steady
state in ~`1/k_e` steps. New Env params `dtsArpOn/Target/Diffusion/Decay/HotPatches/HotPatchDeg`
(diffusion/decay/target runtime-mutable). Diffusion runs each step in `computeAllForces`.

Render: `ThreeJSWriter` emits per-vertex `arp` in the `membranes` JSON; viewer colors the DTS surface by a
blue→cyan→green→yellow→red heat-map (**DTS Arp heatmap** toggle, opaque). Config `ParameterFiles/dtsMembraneArp`
(5 patches, 12°, α=0.1, k_e=0.015). Validated: clean diffusion gradient (histogram 817 faded → smooth halo →
135 clamped sources at 1.0), screenshot shows bright NPF patches with halos fading to blue. **Next (Stage 3b):**
formin nucleation at hot patches → mother filaments linked to the membrane; then spatial-accelerated
`segmentVsMembrane` + branch gating reading this Arp field.

## 2026-06-17 — Membrane v2 DTS, STAGE 3 prep: thin-filament containment (segment-vs-triangle) — PASS

Before wiring real actin, de-risked the collision: can the DTS membrane contain a THIN rigid rod (actin
filament, r=3.5 nm) driven against it in ALL orientations? The OLD membrane collided only the barbed TIP
vs triangles (the body could leak in oblique/tangential cases). New `Membrane.segmentVsMembrane(p1,p2,rad,…)`
**samples the whole segment** and does one-sided point-vs-triangle at each sample over the CONTINUOUS surface
(the triangle faces tile with no gaps — unlike vertex-spheres, which leave face-center gaps a thin rod slips
through). Stiff drag-coupled engagement + a **hard-recovery** spring (`dtsStericRecover`) that yanks back any
sample that crosses to the outside. Reusable by real FilSegments in Stage 3.

`DtsFilamentContainmentCheck.java` (committed) sweeps 24 directions × {radial, 45° oblique, 90° tangential},
driving each rod hard against a **held-rigid** membrane (the strictest test) and measuring the worst leak
(any sample past the surface). **Result: PASS in all orientations** at realistic (5e-12 N ≈ few-pN stall),
strong (5e-11, 10×), and extreme (2e-10, ~40×) drive, ν=3 and ν=4: tip held ~0.04–0.09 µm *inside*, no
escape. (The hard cases — oblique/tangential, where tip-only would leak — are exactly the ones the
segment-vs-triangle handles.) Actin coupling (Arp diffusion + formin nucleation) is the next step; the
explorer's mapping of the old per-node mechanisms (`arpLocal` Jacobi diffusion over the link graph,
depletable `forminLocal` pool, hot-Rho patches) ports onto DTS vertices + the wing-edge graph.

## 2026-06-17 — Membrane v2 DTS, STAGE 2: bending + area + volume forces — vesicle holds, FD-validated (default-off)

Implemented the Stage-2 forces on `Membrane` as kernel-shaped passes (per-face normal/area + signed-volume,
per-edge length/dihedral → per-vertex curvature/area gather, then per-edge/per-face force scatter into a
per-vertex accumulator; all geometry in **metres** so forces come out in N with no fudge factor):
- **Bending** (Jülicher/TriMem): `E = κ·Σ_v 2(c_v/A_v − C̄/2)²·A_v`, force assembled from the edge-length,
  face-area, and **exact dihedral-angle gradients**.
- **Area**: `E = (K_A/2)(A−A₀)²/A₀`, force `−K_A(A−A₀)/A₀·∇A`, `∇A_face = ½ n̂×e_opp`.
- **Volume**: `E = (K_V/2)(V/V₀−v_t)²`, force `−K_V(V/V₀−v_t)/V₀·∇V`, `∇_iV = ⅙(r_j×r_k)`.
New Env params `dtsKappaArea`/`dtsKappaVolume`/`dtsTargetReducedVol`/`dtsMaxDispFrac`/`dtsBrownianOff`; the
pre-move loop hook is `Membrane.computeAllForces()` (single-threaded, before the move phase).

**Validation: `DtsForceCheck.java` (committed) finite-difference-checks every analytic force vs −∂E/∂r → 2e-4
relative (PASS).** The vt=1 vesicle **holds a near-perfect sphere**: E_bend pinned at 8πκ (2.517e-18 J), A/V
held exactly, and **Fmax decays 8.75e-14 → 1.0e-15 N** (true equilibrium; the icosphere defect residual
smooths out). vt=0.9 deflates to the target volume and reaches a stable non-spherical shape. Deep deflation
(vt≤0.6) drives severe buckling that needs self-avoidance + mesh conditioning (§3d tethers / §4 flips) +
vt-annealing — explicitly later stages.

**Four bugs found and fixed during bring-up** (each via the FD oracle + energy monitoring):
1. **Hinge gradient.** The cotangent-weighted dihedral-gradient heuristic was structurally wrong (no
   sign/assignment combo passed FD). Replaced with the **exact normal-derivative form** `∂θ/∂x =
   s·(−1/sinθ)·∂(n̂₁·n̂₂)/∂x`, `∂(n̂₁·n̂₂)/∂x_i = Σ_f (1/2A_f)(r_f×w_i)` from `∂N/∂x=[w]_×` skew matrices. FD → 2e-4.
2. **Dihedral sign.** `θ`'s sign must use ê oriented by **face f1's CCW winding**, else the arbitrary
   edgeVert lo/hi order randomizes the sign and `c_v=¼Σ l·θ` cancels to garbage (live E read 16× too small).
3. **Force ownership.** The generic node pipeline (chamber-wall collision etc.) left a ~2.7e-10 N spurious
   force on the vertices that swamped the membrane forces and inflated the surface — even though manual
   gradient descent with the *same* force was perfectly stable. Fix: **`computeForces` zeroes each vertex's
   `soaForceSum` then writes only the membrane forces** (it owns the DTS vertex force; Brownian/actin coupling
   will be added there explicitly). Also `MembraneVertex.step()` overridden empty (no chamber collision).
4. **Stiffness.** Stiff K_A/K_V + large transients explode under explicit Euler at dt=1e-5; added the
   `dtsMaxDispFrac` per-step displacement clamp (the §11 open item; DTS analog of `membraneNodeMaxDispFrac`).

Config `ParameterFiles/dtsMembrane2` (needs `-Xmx4G`). All default-off; production paths untouched.

**Protrusion demo (`ParameterFiles/dtsMembranePush`).** A constant outward (+x) force spread over a polar
cap of vertices (`dtsPushForce`/`dtsPushCapDeg`) pushes a **smooth, coherent pear-shaped bleb** that grows and
**stalls** when the bending+area+volume reaction balances the push (poleX 1.2 → ~2.2 µm at K_A=0.2, K_V=1e-13,
push 1.5e-10 N). The bending rigidity keeps it kink-free; the near-inextensible area constraint stalls it
(soft K_A pulls an unbounded tube instead — the membrane-tether regime). A clean visual of the DTS mechanical
character. Also added a constant-force **probe** node (`dtsProbeForce`) with point-vs-triangle steric
(Ericson closest-point) — the surface is continuous (no punch-through), but the free-flying-probe *contact
dynamics* (sustained push vs bounce) still need work; the push-patch is the reliable demo for now. All knobs
(`dtsKappaBend/Area/Volume`, `dtsTargetReducedVol`, `dtsMaxDispFrac`, `dtsPushForce/CapDeg`, `dtsProbeForce`)
are `setMutableAtRuntime()` — compliance is tunable live in the viewer Params panel.

**Bouncers demo (`ParameterFiles/dtsMembraneBouncers`).** `dtsBouncerCount` free nodes of random sizes
(`dtsBouncerMinR/MaxR`) shoot around inside the shell (`dtsBouncerForce` drive + `dtsBouncerTurnProb` random
re-aim), ricochet off it, and dent transient bulges — a live demo of membrane **response + relaxation**.
Steric is a **vertex-based soft spring** (`dtsStericStiffness`): pushing the vertices directly is what bulges
the surface — the earlier triangle-wall / drag-coupled steric just held the node at the boundary at ~0
penetration and never bulged (probe `dtsProbeForce` parks for the same reason; the probe needs sustained
contact work — deferred). Containment uses a **backstop that tracks the LOCAL (bulging) membrane surface**
(`stericContactMaxR`) so a bouncer can reach+bulge the wall but never push its surface through — fixes small
bouncers escaping. Verified: rStd~0.03 bulging, worst poke-through 0.001 µm, 0 escapes. **Viewer cleanup:**
removed the dead legacy **Membrane flatten** / **Surface fit** sliders (they only drove the old StickyNode
disc membrane; the DTS surface is a real mesh — opacity + DTS surface/wireframe toggles are what apply).

## 2026-06-17 — Membrane v2 DTS: §11 VERIFY resolved — the bending energy is the Jülicher edge form, not the crude dihedral

Before coding Stage-2 energies, resolved the §11 VERIFY items (3 source/literature research agents + a numeric
test on our own icosphere; `RUN_LOGS/2026-06-17_dts_bending_coefficient.txt`). **Outcome changed the plan:**
skip the crude `Σ(1−n̂·n̂)` dihedral form the doc tentatively proposed — it is **anisotropic** (sphere ≠
cylinder, no clean continuum κ; literature splits √3/2 vs 1/√3, and our icosphere measures ~0.30·8π). Use the
**Jülicher / Itzykson edge-weighted form** that FreeDTS (energy) and TriMem (gradient) both use:
`E_bend = κ·Σ_v 2c_v²/A_v`, `c_v = ¼Σ_{e∋v} l_e θ_e`, `A_v = ⅓Σ area` (barycentric), `θ_e = acos(n̂_f1·n̂_f2)`.

**Numeric proof** (`DtsDihedralCheck.java`, committed as the Stage-2 regression test): on our actual icosphere
`Σ_v 2c_v²/A_v → 8π` (continuum 8πκ/κ) — **0.05% at ν=4**, 0.003% at ν=6, textbook h² convergence, exactly
radius-independent. **κ multiplies directly — no √3/2 fudge.** The crude form stays stuck at ~0.30·8π.

**Source findings.** FreeDTS: shape-operator per-vertex curvature, `A_v` barycentric `(1/3)Σ`, **MC-only / no
forces**. TriMem: the gradient source — same Jülicher curvature (NOT the Meyer cotangent-Laplacian; that's
Mem3DG), analytic force from 3 primitive gradients (`∂l/∂r`, `∂A_face/∂r=½n̂×e_opp`, `∂θ/∂r` hinge), transcribed
into §3a. **Licenses:** FreeDTS GPL-2.0, TriMem + trisurf GPL-3.0 — port the *math* from papers (not
copyrightable), no verbatim/line-by-line code copy. Design doc §3a rewritten, §11 checkboxes closed.

## 2026-06-17 — Membrane v2 DTS, STAGE 1: icosphere + flat-SoA Membrane object + viewer surface render (all default-off)

First stage of the DTS rebuild (plan in `MEMBRANE_DTS_DESIGN.md` §10). Geometry + data structure +
render only — **no bending/area/volume forces yet** (those are Stage 2, gated on the §11 VERIFY items).

**Three new classes.** (1) `Icosphere.java` — deterministic icosahedron → ν-subdivision → unit-sphere
projection; shared-edge midpoint dedup keeps it watertight; emits CCW-outward faces and self-checks via
positive signed volume. (2) `Membrane.java` — the GPU/ECS-ready **flat-SoA** object (NOT half-edge):
`faceVert[3nf]`, wing-edges `edgeVert/edgeFace/edgeWing[2ne]`, fixed-width vertex incidence
`vertEdge[nv·maxVal]`+`vertEdgeCt[nv]`, all derived from the faces; multi-instance via a static
`theMembranes` registry; readouts (`totalArea`/`enclosedVolume`) read **live** vertex pose. (3)
`MembraneVertex.java` — a lightweight vertex Thing extending `ProteinNode` (reuses SoA pose / overdamped
mover / collisions) but **not** `StickyNode`: no sticky-points/NodeLink, structural (no turnover), and
**PHYSICAL drag** — `calculateProperties` computes γ=6πηr directly, deliberately bypassing the StickyNode
`membraneNodeDragScale` (the 1e-10 force-scale trap) and the nodeTransDiff overrides. Added a no-myosin
`ProteinNode(Pt3D,double,boolean)` ctor for it.

**Wiring.** Env params `buildDtsMembrane` (IC flag, default-off), `dtsMembraneSubdiv` (ν, def 4),
`dtsMembraneRadius` (def 1.0 µm), `dtsVertexRadiusFrac` (def 0.5), `dtsKappaBend` (stored for Stage 2).
`begin()` calls `Membrane.buildIcosphereMembrane()` when active. `ThreeJSWriter` emits a new `membranes`
array (flat `vertices` + flat triangle-index `faces`) and **skips** MembraneVertex from `nodes`. Viewer
(`sim_viewer_boa.html`) builds an indexed `BufferGeometry` (position + `computeVertexNormals`) into a solid
translucent `Mesh` + a shared-geometry wireframe `Mesh`; toggles **DTS surface** / **DTS wireframe**;
opacity slider reused.

**Validated** (`ParameterFiles/dtsMembrane1`, ν=4): nv=2562 / nf=5120 / ne=7680, maxVal=6, **Euler V−E+F=2**,
mesh area/volume **99.9% / 99.8%** of the continuum sphere. Full short run completes; frame JSON has the
`membranes` array (indices in range) and empty `nodes`. Headless-Chrome screenshot of the real viewer shows
a smooth shaded translucent sphere in the box — the Stage-1 render payoff. **Note:** ν=4 needs `-Xmx4G`
(the 26³×binDepth-1000 collision mesh OOMs the default 800M; unrelated to DTS — the static membrane doesn't
need collisions, a later trim). **Next:** Stage 2 forces, but first resolve §11 VERIFY (FreeDTS curvature
discretization, the √3/2 dihedral↔κ coefficient).

## 2026-06-17 — Constant-force PROBE isolates the real bug: membrane node drag scale (membraneNodeDragScale)

To decouple membrane mechanics from the messy actin, added a clean isolation tool: a single plain protein
node (no myosins/formins/actin), created inside the sphere and driven outward with a CONSTANT force
(`membraneProbeForce`/`Radius`/`StartRadius`; `StickyNode.createMembraneProbe`/`driveMembraneProbe`, steric
collision with membrane nodes captured into extMembF; `[PROBE]` readout). Config `ParameterFiles/lamSphereProbe`.

**The probe found the bug that blocked everything.** First runs: the probe sailed straight through the
membrane. Not a missing collision — the instrumentation showed real overlaps (0.15 um) but `maxPush ~1e-19 N`,
8 orders below the probe's drive. Root cause: **`membraneNodeDragScale` defaults to 1e-10**, so a membrane
node's drag is ~9e-18 and the drag-weighted collision force `(overlap/dt)/(1/g_probe+1/g_node)` is capped at
the membrane's force scale (~1e-20 N). The whole membrane lives at ~1e-20 N while the probe/actin push at
~1e-11 N — a 9-order mismatch, so ANY normal-force body (probe OR filament) overpowers the collision and
punches through while the membrane feels nothing. This is almost certainly why the dendritic net never moved
the cortex either; it was never the vesicle stiffness, the ratchet, or the geometry — it was the drag scale.

**Fix: physical drag (`membraneNodeDragScale=1.0`).** The spring/relaxation is a drag-coupled position
projection, so its dynamics are ~drag-independent (verified: resting sphere holds, relaxation stable); but the
collision now couples at the physical ~1e-11 N scale. Result (run `probe_drag1`, F=2e-11 N): the probe pushes
a smooth, growing bleb (0.4 um, 80 nodes), SLOWS toward a stall as the reaction (-1e-10 N) overtakes the
drive, the rest of the sphere holds at ~1.18 and dimples slightly inward (volume borrowed for the bleb), and
**jitter DECREASES over time (0.006->0.003) — the membrane smooths and settles**. Finally decent shell
behaviour: a known force makes a coherent bleb and stalls. Set in `lamSphereProbe`; Env DEFAULT left at 1e-10
(don't silently change untested sheet/sphere configs) — flip per-config for now.

**Two follow-on nuances.** (1) At physical drag the VESICLE pressure (a true physical force ~1e-14 N) becomes
negligible vs the drag-coupled link projections, so the shell is effectively held by rest-length link tension
alone (worked fine in the probe run). The volume constraint needs re-expressing (drag-coupled, or modulus
scaled ~1e10) to stay relevant. (2) The old off-center-link jitter that drove us to center-attach was the
SAME tiny-bRotGam drag artifact — so off-center (physically-tipping) attachment may now be viable. Also added
the `(b)` point-node steric push capture into extMembF (FilSegment) — correct but insufficient alone (the
steric push is ~0 at equilibrium); kept.

## 2026-06-17 — Vesicle membrane (volume-pressure shell) + the real blocker: actin→membrane force coupling (all default-off)

**Replan.** Comparing the two reference runs settled why the sphere never behaved: `lam_npf_backed` (the
"happy medium" sheet) protrudes 0.46 µm by out-of-plane BENDING with edge pins — a coherent elastic
membrane anchored at a boundary; `lam_sphere_protrusion3` is held by a per-node radial pin
(`addSphericalConstraintForce`) = a Winkler foundation that leashes every node to fixed R independently, so
it's either rigid (frozen at r=1.200) or, when softened, floppy (nodes shoved aside). No happy medium,
and "more nodes" can't help because they're uncoupled. The closed-surface analog of the sheet's pinned rim
is the **enclosed volume**.

**Vesicle model (`membraneVesicle`, default-off).** Replace the per-node radial pin with a volume-conserving
pressure `P = P0 + K·(V0−V)/V0` over the surface, balanced by the Tier-1 rest-length link tension. v1 used a
clique triangulation for V and **collapsed** — the auto-linked lattice (avg degree ~4.6) isn't a clean closed
triangulation, so untriangulated nodes got zero outward force and caved in while others flew out. Fix:
**triangulation-free** — each node's outward normal is the geometric radial (robust, never flips), local area
≈ (√3/2)·spacing²; `V = ⅓Σ r·A`; force = `P·A·n̂`. V0 comes out correct (7.04 µm³) and pressure covers EVERY
node. Result: a **stable coherent shell** — holds a clean sphere (rStd ~2e-4), conserves volume (V/V0≈1.00),
no collapse, and filaments can no longer shove individual nodes through (the excavation/mess is gone). New
params `membraneVolumeModulus`, `membraneTurgorP0`; `[VESICLE]` readout. Applied via per-node `presF` in
`StickyNode.moveThing` (replacing pin+turgor), recomputed each relax pass and before the move phase; runs
through the `membraneYield` sub-cycle so push+tension+pressure integrate together.

**The real blocker (diagnosed, NOT yet fixed): the actin transmits ~no force to the membrane.** Drove a
0.5 s run (`lamSphereVesicle`, run `vesicle_long`): the dendritic net grew to **459 segments** (≈protrusion3
scale) and the shell stayed **completely unmoved** (rStd 2e-4) while **97 tips leaked past the node shell**.
The captured push `maxExtF ≈ 4e-19 N` — ~8 orders below the ~1e-11 N needed. Three causes, all on the
coupling side: (1) the steric barrier equilibrates tips at the standoff with ~0 force (force ∝ penetration ≈ 0;
the ratchet stops poly at contact); (2) `membraneYield` captures only the FACE push (FilSegment.java:2312),
the ~0 equilibrium term, NOT the dominant point-node push (line 2258), and the relaxation zeroes the real
per-step collision force; (3) the thin push (~1 fil/node) just threads through the porous lattice. The old
runs only ever looked "pushed" because filaments shoved individual nodes aside — which the coherent vesicle
correctly forbids. **Fix (next): a sustained polymerization force** (Mogilner–Oster stall force, `ratchetForce`)
per contacting barbed tip along the membrane normal, applied to the nearest node and persisted through the
relaxation — distinct from, and now unblocked by, the vesicle structure. Config `ParameterFiles/lamSphereVesicle`.

## 2026-06-16 — Tier-2 membrane area growth: node insertion / edge-split (all default-off)

When a bulge grows, a fixed node count thins coverage → the cortex tears open and the net excavates through
(measured: 10 bulge nodes, links 4.2× rest, 0.34 µm holes). **Tier-2 (`membraneAreaGrow`):** when a membrane
link over-stretches, EDGE-SPLIT it — insert a StickyNode at the midpoint, wire it to the two endpoints + the
two shared triangle-apex neighbours (a 2→4 split), rest length = mesh nominal. New nodes auto-register
(`Thing` ctor → SoA + `theNodes`); `StickyNode.freeSlot()` bumps valence (up to `maxStickies`) for the apex
nodes; requires `membraneLinkCenterAttach` (links act center-to-center, so inserted nodes need no rigid
sticky-point geometry; `getCurrentLinkLoc` returns the center in that mode).

**Two failure modes found and fixed.** (a) Strain-relative trigger (split at 1.6× rest) + the apex-link
geometry (M–C, M–D born at ~0.87× the split length, just over threshold) → a **refinement CASCADE** that
floods to the 12000-node cap (a cauliflower bloom; jba caught it). A young-link cooldown
(`membraneInsertCooldown`) damped it but didn't converge — on a soft mesh the force-balanced stretch sits
permanently above any strain threshold, so insertion pins at the per-step cap. (b) Fixed with a
**coverage-based trigger** (`membraneInsertGapUm`, absolute hole size ~0.14 µm) + metering
(`membraneInsertEveryNSteps`, `membraneInsertPerStep`, biggest holes first): a stretched-but-covered bulge is
left alone, so it CONVERGES → a gentle trickle (+391 nodes over a run vs +8700 capped). Verified
`ParameterFiles/lamSphereProtrusionT2`. NOTE: superseded as the primary approach by the vesicle replan above
(area growth is a later concern once the shell is driven); kept default-off.

## 2026-06-16 — Tier-1 membrane stabilizers: elastic mesh + center-attach + averaged Jacobi + step clamp (all default-off)

Fresh look at the `membraneYield` node instability. Root cause found: `NodeLink.applyTransForce` used
`curStretchDist = linkLength` — a **zero-rest-length contractile spring** (force ∝ full length). On a
pinned flat sheet that's a stable trampoline, but on the closed sphere (no boundary pins, only the radial
pin) it gives the mesh **no in-plane ground state** — nothing sets node spacing inside the relaxation loop
(steric repulsion runs in the main collision phase, not in `subcycleRelaxAll`), so the springs contract
the lattice and it shears/clumps under perturbation = "nodes are a mess." Three contributing factors:
un-normalized Jacobi over-relaxation (each node integrates the SUM of ~6 incident link corrections,
`membraneLinkFracMove` default 2.0), links applied at the **off-center sticky point** (torque → the
lightly rotationally-damped node spins, sticky points whip → jitter), and no per-step displacement clamp.

**Four default-off knobs added** (Env): `membraneLinkRestFrac` (>0 ⇒ elastic spring, rest = frac×length
captured at link creation; force ∝ length−rest, resists stretch AND compression), `membraneLinkCenterAttach`
(apply link force at node center, no torque), `membraneRelaxAvgValence` (divide each node's incident link
force by its `boundCt` → averaged/under-relaxed Jacobi, can't overshoot), `membraneNodeMaxDispFrac` (cap a
StickyNode's per-substep translation to frac×nodeRadius — explosion safety net). `ParameterFiles/
lamSphereProtrusionT1` = `lamSphereProtrusion` + all four on (restFrac 1.0, center, avg, clamp 0.25).

**A/B on the sphere (123 frames each, `RUN_LOGS/2026-06-16_tier1/`, viewer dirs `t1_baseline`/`t1_tier1`;
metric = per-node frame-to-frame displacement (jitter) + link-length CoV (clumping), a far better instability
proxy than the radius/err summaries that misled earlier).** Tier-1 wins where it was breaking: early window
(t<0.05, before heavy load) **jitter RMS 0.00011 vs 0.00135 (~12× quieter)**, link CoV flat 0.088 vs baseline
rising 0.10; baseline's `lkMean` contracts (0.079→0.077) and `lkMin` collapses 0.064→0.014 (nodes piling up),
Tier-1 holds 0.079 / ~0.05. Whole-run jitter RMS 0.00046 vs 0.00101 (~2.2×). Neither NaN'd. Tier-1 also makes
a **cleaner, larger** protrusion (maxBulgeR 1.316 vs 1.293; rMean stays pinned 1.2009 — rest of sphere doesn't
move). **Honest limit:** late-time under heavy actin load (170 segs, 77 branches) the **bulge region itself**
still distorts hard — links stretch to 4× rest (lkMax 0.32) and some compress to ~0 (lkMin 0.0013), CoV 0.19.
That's the Tier-2 problem: a *large* bleb needs added membrane AREA; a fixed-node mesh must absorb the bulge
as extreme local strain. So Tier-1 fixes the global "mess" and quiets the nodes, but large blebs still need
area growth / node insertion. All changes default-off; production paths untouched.

## 2026-06-16 — Membrane protrusion attempt (`membraneYield`): mechanism works, but NODES NOT STABLE (jba) — WIP

First crack at letting the closed sphere protrude under actin load. Recap of why it's hard: `NodeLink.
subcycleRelaxAll` relaxes the membrane links to rest length every step (inextensible) and **discards the
actin push** — a flat sheet protrudes by BENDING (no stretch, relaxation allows it) but a closed-sphere
bulge needs STRETCHING, which the relaxation forbids.

**`membraneYield` mechanism.** Capture the actin->membrane force (barbed-tip `faceCollideTipVsNodeTriangles`
push; `membraneFaceCollideOn` required) into a per-node accumulator (`StickyNode.extMembF*`,
`incExtMembForce`/`resetExtMembForce`) and RE-APPLY it inside the relaxation so the mesh settles at a
force-balanced bulge instead of rest length. `bestViol` capped to `colThresh` so a deep-leaking tip can't
give an unbounded push. Tether reaction is NOT captured (too large at high strain → unstable).

**Two relaxation variants tried.** (a) Re-apply the push every full-`deltaT` Jacobi pass → bulges large
(70 nm → 1700 nm) but the stiff mesh OVERSHOOTS each pass → nodes shoot around / fly to inf. (b) Sub-cycled
integration `membraneYieldSubN` sub-steps of `deltaT/N` (Arp2/3 pattern) → metrics looked much better:
smooth, localized, gradually-developing bulge (≈100 nm over ~38 hot nodes at t=0.12, rest of sphere ~1.18–
1.20, `err:0`, no inf). `sphereConstraintFrac` parameterizes the radial pin; a compliant (no-yield, high
`membraneMaxLinkStrain`) control was smooth but FLAT (single main-move push too weak to pay the stretch
cost).

**jba's conclusion: the nodes are still NOT stable in the viewer** — the sub-cycling improved the summary
metrics but the membrane node motion is not acceptable. So `membraneYield` is **WIP / not a solution yet**.
Default-off (`membraneYield`=0), so nothing else is affected; `ParameterFiles/lamSphereProtrusion` holds the
experiment (membraneYield on, face-collide on, confine off, soft pin, angle 45 + ratchet).

**Next time.** (1) Stabilize the relaxation under load — likely Gauss-Seidel instead of Jacobi, and/or treat
the membrane links as genuinely compliant springs rather than a rest-length constraint. (2) The real fix is
**#2 membrane growth** (add area / grow rest lengths / insert nodes at the protrusion) so the membrane gains
area instead of stretching — sidesteps the stretch-cost + stiffness fight entirely; jba wants this. (3) A
**yielding confinement** that rides the local node radius (the fixed-radius `membraneConfine` is off here, so
filaments leak through the porous mesh). See memory `membrane-protrusion-inextensible-relax`.

## 2026-06-16 — Formin mothers + Arp2/3 branches: literature-faithful cortical network; protrusion blocked by inextensible membrane

Reworked the spherical cortical model to match the literature (a focused agent sweep, citations in the
session): **Arp2/3 cannot nucleate de novo** (WASP/WAVE only branches off a mother); the mother/first
filaments are made by **formins (mDia)** at the same GTPase zone; branched nets are **Rac1/Cdc42**, not
RhoA; the membrane tether is **ERM-like**, not Arp2/3; lamellipodial barbed ends face the membrane (±35°).

**Implicit-formin mothers (`forminConc`/`forminConsumePerMother`/`forminRecover`).** De-novo Arp2/3
nucleation replaced: each hot Rac1 node has a depletable formin pool that seeds **linear** mothers
(`makeForminMother`, no Arp2/3 pointed cap, `forminMother` flag), hard-capped at `forminConc/consume` per
node. Arp2/3 now branches only off these. Hot-Rho → **Hot Rac1/Cdc42** relabel (params, log, viewer).

**Nurse-log cortex.** `membraneAlignTorque` lays held mothers at `membraneAlignAngle` from the surface
normal (90°=tangent mat); `cortexBrownianZone`/`Factor` damp the crowded cortical shell; debranch rate
dropped (4→~0.15) keeps the mat. `membraneAnchorReactionFrac` makes the membrane node a HEAVY anchor (the
tether reaction was jittering the comparatively-light nodes — that was the "node instability"). Hot patches
moved to **cube-corner directions**, off the Deserno poles + φ=0 seam (fixes the pole-vs-equator asymmetry,
where a sparser patch auto-capped mothers into stubs). Per-segment `"branch"` flag emitted for analysis.

**Findings (runs in `RUN_LOGS/2026-06-15_lamellipodium_p1p3/`).** Tangent cortex is BOUNDED — branches
track mothers (br:mo peaks ~0.7 then declines), they don't take off, because NPF-surface-gated branching
can't self-amplify (inward daughters leave the zone). Tilting barbed ends toward the membrane
(`membraneAlignAngle`<90 + shallow `motherTetherDepth` + `ratchetOn` + `membraneCapDist`≥`branchZone`)
DOES build a membrane-incident dendritic network — but it **gets pushed back, not protrusive**. Root cause:
`NodeLink.subcycleRelaxAll` zeroes each node's force then relaxes links to rest length every step →
**inextensible mesh** that discards the actin push. The flat sheet protrudes by out-of-plane BENDING
(no stretch); a closed-sphere bulge needs STRETCHING, which the relaxation forbids. `sphereConstraintFrac`
parameterizes the (sphere-only) radial pin. NEXT: make the relaxation respect the actin load (preserve +
re-apply external node forces across Jacobi passes) + a yielding confinement that rides the local node.
Protrusive-mode knobs are default-off (`membraneAlignAngle`=90, `motherTetherDepth`=0, `sphereConstraintFrac`
=0.4) so the tangent cortex is unaffected. `ParameterFiles/lamSphereProtrusion` records the experiment.

## 2026-06-16 — Spherical membrane: held, contained cortical-actin network (`buildMembraneSphere`)

Smallish sphere (R=1.2, 3267 Deserno nodes) with shrunk hot-Rho patches + de-novo nucleation + branching →
a bounded cortical net held under the membrane. `ParameterFiles/lamSphereBranch`, run `lam_sphere_confine2`.
Long debug chain (all caught by jba via screenshots):

**De-novo nucleation now does what Arp2/3 does.** Caps the pointed (minus) end, tethers it to the
activating node (reuse of the existing `linkEnd1Node` end1 spring), grows the barbed end INWARD. Direction
uses the **geometric** radial (sphere-center→node), NOT the node's body-frame `zVec`: membrane nodes rotate,
so `zVec` drifts off-radial and ~10/3267 even flip → those nucleated/tethered *outward* (the first "escapes").
Nucleation is also field-gated + field-consuming (same governor as branching).

**Held-filament Brownian (`arpHeldBrownianFactor`=0.02).** A nascent tethered seed has tiny drag; a full
free-filament Brownian kick against its stiff tether at dt=1e-5 went unstable. Scale the Brownian FORCE
(not drag) 50× down for `childOfArp23 && nodeAtEnd1` filaments. jba's diagnosis.

**Two Arp23 sub-cycle NPEs were the real "blowups."** `subcycleAll` (a) called `applyForces()` on a
just-deactivated Arp23 and (b) added removed (nulled-`bForceSum`) segments to `subcycleSegs` → NPE on a
worker thread → corrupted state → the 1e25 positions. Guarded both (match the single-shot `enforceFilLink`
path). After this the net runs the full time with no numerical instability — growth is bounded only by the
(still permanent) tether, a deferred turnover item.

**Membrane confinement (`membraneConfine`/`membraneConfineFrac`).** The node lattice is **porous to filament
bodies** (only barbed tips collide with nodes; `membraneFaceCollideOn` is still tip-only) — free/debranched
filaments drift out through the gaps and pile up outside, capped. Added a one-sided inward radial force on
any filament end past the cortex. **Confine to the INNER STERIC FACE** `membraneCellRadius−(nodeR+filTipR)`
≈1.10, NOT the node-center sphere (1.2): the viewer draws the membrane there (`membraneSurfaceFit`), so a
first version that confined to 1.2 held filaments in the 0.10-thick shell *outside* the drawn surface — they
looked like escapes. Tether inset + seed depth use the same face. Result: max endpoint radius EVER = 1.101
across 123 frames, all cortical actin under the membrane.

## 2026-06-16 — Membrane rendering: sim emits per-node normals; t=0 frame; IC backed off the membrane

Three fixes after the collision-surface disc offset exposed real geometry issues (jba caught all via
screenshots).

**Sim emits per-node membrane normals (`ThreeJSWriter` `"n"` = node `zVec`).** The viewer was inferring
each disc's normal from node positions (PCA + centroid orientation) to do the inward offset — fragile:
on any curved/bulged surface the sign is ambiguous (which way is "out"?), giving a crater at frame 0 and
inconsistent discs on the bulged dome, and it would never work for a sphere. The node already carries its
own orientation (`zVec`), set correctly at construction and evolving smoothly — verified it stays consistent
AND tilts with the surface as the sheet bulges (central node `[0,0,1]`→`[-0.003,0.026,0.9997]`, pinned edge
stays `[0,0,1]`). Viewer now offsets the disc inward along the emitted normal with ONE global sign decision
(keyed to the filament/cytoplasm centroid); position-inference kept only as a fallback for older frames.
Robust for any geometry.

**t=0 frame.** `doLoop` now writes a frame BEFORE the first integration step, so frame 0 is the pristine IC
(flat sheet, z=0) instead of the post-first-step state (which had already bulged 0.026 µm).

**IC backed off the membrane.** `makeMembraneBranchedMothers` seeded barbed tips at z=−0.04 — above the
steric contact (a tip is stopped `membraneNodeRadius+filTipRadiusForCollisions`=0.10 µm below the node
plane), so they started poking through. Now `barbedZ = −(nodeR+filTipR)−0.02` ≈ −0.12, just inside the
cytoplasm; tied to the collision radii so it tracks them. Verified frame 0: barbed-end z ≤ −0.12.

## 2026-06-16 — Arp2/3 depletion as a branching governor: global pool → activated-Arp2/3 field (NPF source, bulk sink)

4× branching (`arpConc` 20→80) over-branches into an "insanely bushy" net after ~0.2 s — autocatalytic
branching with no negative feedback. Built the feedback in two stages.

**1. Global consumable pool (`arpConsumePerBranch`, default off).** Each branch consumes `arpConc`, returned
on dissociation (conserved); standing branches cap at ~`arpConc/consume`. Works as a knob, BUT the
*physical* global pool can't be the limiter: at 80 µM the box holds ~millions of complexes.

**2. Per-node ACTIVATED-Arp2/3 field (`arpLocalField`) — the physically-grounded model.** The realization
(prompted by "how do the knobs fit real Arp2/3 diffusion?"): free cytoplasmic Arp2/3 (~5 µm²/s →
`arpDiffusion = D/(1.5·spacing²) ≈ 700`) refills a depleted node in ~0.25 ms — ~1000× faster than branching
empties it — so **free Arp2/3 can't deplete locally at all.** The real depletable resource is the
**membrane-bound, NPF-activated** pool (D ~0.01–0.1 µm²/s, ~50–500× slower → `arpDiffusion` ~1–14, where I
had it). So the field is an *activated*-Arp2/3 model, **NPF source / bulk sink**: hot-Rho (NPF) nodes are the
only source (produce toward `arpConc`); it diffuses laterally as a slow membrane species (`arpDiffusion`);
it is lost everywhere to the inactive bulk = sink at 0 (`arpBulkExchange`, lifetime ~1/k, decay length
√(D/k)); branching consumes the nearest node's pool with **no return** (incorporated complex rejoins the
inactive pool) → branching is **activation-rate-limited**, the biologically correct governor.

Implementation: `StickyNode.arpLocal` + `diffuseArpField()` (one explicit Jacobi step over the NodeLink
graph, single-threaded, before biochem); `FilSegment.end2NearArpNode` (nearest hot node, set in
`registerATipClearance`); `checkBranching`/`makeArpBranch` read+consume the local pool; `Arp23` records the
node and skips the return for field-consumed Arp2/3. `[ARPFIELD] hotMean/hotMin` readout on the `[LAM]` line.
**Verified** (`ParameterFiles/lamRatchet4xField`, run `threejs_output/lam_ratchet_npf`): the activated field
develops a real depletion gradient — hotMin 22 < hotMean 36 < target 80 µM — the most-branched nodes dig the
deepest hole, so growth is pushed laterally rather than piling up. Plenty of dials (`arpConc` target,
`arpDiffusion`, `arpBulkExchange`, `arpConsumePerBranch`) to tune toward a morphological match later.
All default-off.

**Also (viewer):** membrane-disc inward offset (from the collision-surface fix) used a degenerate per-node
PCA normal for under-linked nodes (the outer ring before auto-linking), placing them wrong at startup.
Fixed with a global-sheet-normal fallback (planar membranes) — outer ring sits correctly from frame 0.

## 2026-06-15 — Brownian-ratchet polymerization closure: implemented, demonstrated, and a buffer/scale-mismatch fix

Implemented the Mogilner–Oster polymerization closure (`RATCHET_CLOSURE_DESIGN.{html,pdf}`) as a default-off
replacement for `stericPolyFactor`. New params `ratchetOn` + `ratchetForce` (membrane-normal load f);
`FilSegment.ratchetPolyFactor()` gates the barbed-end poly rate: **free when a full monomer fits the gap
(g≥δ), else exp(−f·(δ−g)/kT)** — the deficit (δ−g) form, so an existing gap raises the rate. `RatchetDiag`
bins every poly-eligible tip-fire by gap g (units of δ), recording the applied factor and the realized
event rate; reported at frame cadence on the `-r` path.

**The bug the demo exposed (jba's catch from the viewer: "collisions look far from the membrane").** The
ratchet read `g = end2TipC` and never engaged — orthogonal tips polymerized freely. Root cause is a
**scale mismatch**: the steric collision lives at the 50 nm node/tip-radius scale
(`membraneNodeRadius=filTipRadiusForCollisions=0.05 µm`, collision standoff `nodeRadius+filTipR=0.10 µm`
from node centre), but the ratchet gates at the 2.7 nm monomer scale. `end2TipC` (tip-to-node-surface) is
held at ≈`filTipR` ≈ **18 δ** by the collision buffer, so `g≥δ` was always true → factor 1.0. The buffer is
intentional (prevents poke-through) and untouched; the fix makes the ratchet read the gap to the **steric
surface**: `g = end2TipC − filTipR` (→ 0 at collision contact). One line, plus the same correction in the
`RatchetDiag` binning.

**Validated (config `ParameterFiles/lamRatchet` = P1–P3 v4 + ratchetOn, f=2 pN, kATPOn2 lowered to 50 to
de-saturate the base rate; run `threejs_output/lam_ratchet_fix`).** After the fix the boundary bins
populate (0% → 14.8% of fires) and the applied **meanFactor reproduces exp(−f(δ−g)/kT) to ~1% across every
gap bin** — 0.269 at full deficit (= exp(−1.31)), rising smoothly to 1.0 at g≥δ. Realized rate is suppressed
in the contact bins (relRate 0.24–0.86 vs 1.0 free; noisier at ~15–40 events/bin). So the force–velocity law
operates per-tip: leading-edge filaments slow under load, those with clearance grow free.

**Open (sub-grid gap resolution):** the membrane is modeled at the 50 nm node scale, so the gap is resolved
only to ~that softness — the sub-monomer `(δ−g)` part is coarse; the meaningful behavior is the load-gated
`exp(−fδ/kT)` switching on at contact (consistent with a sub-grid closure). Also `ratchetForce` is a
representative constant load; reading the per-tip membrane reaction is the rigorous upgrade. Both noted in
the param/`Env` descriptions.

## 2026-06-15 — Tip-flexibility diagnostic: lumped segments can't host an emergent Brownian ratchet (use a closure)

Characterized whether the rigid-`FilSegment` filament has the right *tip compliance* for a Mogilner–Oster
elastic ratchet to emerge. Built a cantilever (one end pinned, free tip, no wall) by reusing the deflection
bench (`-singleFilDiag` + new `BOA_TIPFLEX=1`); measured free-tip transverse motion two ways. **Fluctuation
mode** (Brownian on, accumulate `<y²>`) was too noisy — the soft cantilever's ~0.13 s correlation time gave
~28 independent samples (5× y/z anisotropy). **Static-compliance mode** (`BOA_TIPFLEX_FORCE`, Brownian off:
apply tip force, `k_eff=F/δ`, `σ_tip=√(kT/k_eff)`) is deterministic and clean — the reported data.

Sweep at fixed contour 0.69 µm, varying segments: **tip compliance is strongly, monotonically
segment-length-dependent** — coarser = *softer* (k_eff falls ~29× from 32→2 segs; bending is lumped at hinges,
so a long rigid segment on one soft joint swings its tip far). In the **typical 24–64 mon/seg range** the tip
came out ~11–17× softer than an ideal Lp=15 µm filament (σ_tip ≈ 290–355 nm vs ≈ 86 nm). Plus head-on
incidence is geometrically dead (`σ_normal = sinθ·σ_tip`). **Caveat (don't over-read):** the sweep did NOT
recalibrate bending per segment length, and effective Lp is segment-length-dependent here, so much of that
factor is miscalibration, not a fundamental tip-resolution limit — the raw numbers overstate the in-range
error; the clean test is recalibrate-then-measure at 24/32/48/64 (not yet done). **Decision either way:**
the tip compliance a ratchet would ride is config-dependent, so **anchor the ratchet to physical constants
(a closure), don't let its magnitude emerge.** Full method, table, caveats, repro: `TIP_FLEXIBILITY_DIAGNOSTIC.md`;
closure spec: `RATCHET_CLOSURE_DESIGN.{html,pdf}`. Diagnostic scaffolding (`SingleFilDiag` TIPFLEX/STATIC,
one-pin/Brownian/force gates, `tipFlexForce`) is default-off. Logs: `RUN_LOGS/2026-06-15_tipflex/`.

## 2026-06-15 — Dendritic lamellipodium P1–P3: NPF-surface branching + debranching + bulk capping (exploratory)

Implemented the P1–P3 recommendations from the membrane-dendritic literature review (see
`MEMBRANE_DENDRITIC_REVIEW.pdf/.html`) and tuned them to a **bounded, treadmilling lamellipodium**.

**Code (all default-off / additive):**
- **P1 — branch at the real surface, not a z-plane.** `ProteinNode.permanentHotRho` marks a stable NPF
  (Rac/Cdc42/PIP2) activator patch; `StickyNode.biochemStep` keeps it hot for the whole run (no
  `rhoHotLifetime` expiry) and `makeSheetHexPackedNodes` flags the central patch permanent. Branch
  eligibility was already wired to hot-node proximity (`registerATipClearance(..., node.iAmHotRho)` →
  `end2NearArpFactor`); P1 just makes the patch persistent and turns OFF the `branchMembraneDist`
  z-plane so branching is gated purely on NPF proximity.
- **P2 — Arp2/3 debranching** (`Env.arpDebranchRate`, `FilSegment.checkDebranch`): per-branch
  stochastic dissociation, freed daughter depolymerizes (`Arp23.active=false` → `setInactiveArp23s`
  releases it). **Gate must be junction-ADP** (`FilSegment.junctionADPFraction`, oldest ~8 monomers at
  the pointed end), NOT the whole-filament `notADPFraction` — a growing daughter keeps adding fresh ATP
  so its whole-filament average never ages and never debranches.
- **P3 — bulk stochastic capping** (`capConc`, `membraneCapDist=0`): off-surface tips cap; tips at the
  hot patch are capping-exempt (protected front). Already how `mem_lam10` worked; P3 just keeps it.
- Diagnostics/viewer: per-frame `[LAM] segs=… activeArps=…` (in `remoteLog` AND `logAndDraw` — `-r` runs
  use `remoteLog`). `ThreeJSWriter` emits `"hotRho":true`; viewer renders hot-Rho nodes **bright pink**
  (per-instance `instanceColor`), default **30 fps**, and **auto-fits the camera to the box** on load.

**The key finding (v1→v4 sweep, `RUN_LOGS/2026-06-15_lamellipodium_p1p3/`, runs in
`threejs_output/lam_p1p3_v{2,3,4}`):**
- **De-novo nucleation is a fog generator.** v1 used `nucRateNearArpFactors=5` → the ~49 hot nodes each
  spawn independent stubs → a haze, not a branched tree. `mem_lam10` had it at **0**; growth must come
  from seeded mothers branching. Fixed.
- **The `branchMembraneDist` z-plane causes autocatalytic runaway.** It makes every barbed end within
  0.3 µm of z=0 branch-eligible = a *volume* source. v2/v3 grew super-linearly (segs 25→8525) with
  **activeArps in lockstep with segs** (ratio ~0.65) — branching ∝ network size. Raising `capConc`
  10× (v3) did NOT tame it (a volumetric source can't be capped away). `mem_lam10` only looked OK
  because it stopped at 0.15 s, before the runaway.
- **NPF-surface gating (`branchMembraneDist=0`, v4) is bounded.** Branching ∝ membrane *surface*
  (the patch), a fixed source → debranching balances it: **`activeArps` plateaus at ~50** (flat
  t=0.22→0.49), segs settle ~213, membrane protrudes 0.79 µm, only ~6 tips left pointing away from the
  membrane. A thin, forward-oriented, treadmilling dendritic array — the lamellipodium. This vindicates
  P1's core claim: real-surface NPF gating > fixed z-plane.

**Config:** `ParameterFiles/lamellipodiumP1P3` (= v4: `nucRate=0`, `arpConc=20`, `branchRate=0.7`,
`branchMembraneDist=0`, `capConc=3.0`, `arpDebranchRate=2.0`, `kHydrolysis/kDissociation=3` to age
junctions within the run, full `mem_lam10` stability stack). CPU; needs `xLinkTesting=nodeLinkTesting=true`.

**IC-by-param refactor (fixes the long-standing wart).** The compile-time `xLinkTesting`/`nodeLinkTesting`
flags were promoted to runtime BOOLEAN params **`buildMembraneSheet`** (hex StickyNode sheet + hot-Rho
patch) and **`buildBranchedFils`** (Arp2/3 branched fils; +sheet = membrane-branched mothers, alone =
free-space junction test). Both default OFF, so the IC is selected per-run from the param file — no
recompile, no cross-run contamination. Verified: selectors on → 900 nodes/49 hot-Rho; off → 0 nodes.
`ParameterFiles/lamellipodiumP1P3` sets both true.

**Open / caveats:** (a) — resolved (IC-by-param refactor above). (b) Hydrolysis accelerated 10–30× for
debranch observability; at physiological rates turnover is a ~seconds process. (c) No membrane
tension/area limit — the protrusion is bounded here only because turnover bounds the *network*; a
stronger push would still balloon to the box ceiling (`MEMBRANE_MODEL_NOTES` §3). (d) `branchMembraneDist`
z-plane still references fixed z=0, not the bulging surface — now moot at 0, but the param remains.

## 2026-06-15 — Lamellipodium tuning: orthogonal knobs + three throttles (exploratory)

Working recipe for a sparse dendritic network pushing the membrane (membrane IC, both sub-cycles
on), plus three non-obvious throttles found while tuning. Membrane modeling is still in development;
this is current knowledge, not settled physics.

- **Branch density = `arpConc`** (linear: branch prob/fire = `branchRateNearArpFactors·arpConc·
  biochemDeltaT`). **GOTCHA: `arpConc` defaults to 0, so runtime branching is silently dead** no
  matter the branch rate — the membrane configs never set it (`isoBranch` used 10). `branchMembraneDist`
  (z-rule, ~0.3) makes near-membrane barbed ends branch-eligible (the dendritic self-amplification).
  `nucRateNearArpFactors` + `kNodeNuc` are the de-novo "hot-Rho" nucleation; set both to 0 and growth
  comes only from the IC mothers branching, not a nucleated brush (that brush was what the old
  "dendritic" membrane runs actually were).
- **Elongation speed = `biochemDeltaT`, NOT `kATPOn2`/`actinConc`.** `addMonomerSim` adds ≤1 monomer
  per biochem fire, so growth velocity ≈ 1 monomer / `biochemDeltaT` regardless of rate/concentration
  (the add-prob is already saturated at the 15 µM default). Lowering `biochemDeltaT` (0.01→0.001) gave
  ~10× faster elongation while branch *rate per second* stayed fixed (branch prob ∝ `biochemDeltaT`,
  cancels). So `arpConc` and `biochemDeltaT` tune branch-spacing and filament length nearly orthogonally.
- **Cortex confinement = `membraneCapDist`.** Caps any barbed end whose clearance to the nearest
  membrane node ≥ this; `end2TipC` defaults to 1e6 for tips the mesh doesn't detect near a node, so
  off-cortex tips are always capped → no growth. 0 = free growth (light stochastic capping via
  `capConc`); >0 = thin membrane-hugging layer. It throttles elongation by design.

**Build wart:** the membrane IC (`makeMembraneBranchedMothers`) vs the free-space branch IC
(`makeTestBranchedFilament`) is selected by the hardcoded `xLinkTesting`/`nodeLinkTesting` flags in
`Env.java` (committed default `false`), so switching scenarios needs an edit + recompile — and flipping
them for one run silently changes the IC for any *other* run launched on that binary (it contaminated a
branch batch this session, caught only because the branch runs showed a membrane). Selecting the IC by
a parameter instead of a compile-time flag is a wanted refactor.

## 2026-06-15 — Constraint sub-cycling (branches + membrane) and two segment-posing bugs

**Branch spin, re-diagnosed and fixed.** The Arp2/3-branched-network spin at dt=1e-5 is a
dt-dependent *explicit-overshoot artifact*, not Brownian tumbling and not branch-count
accumulation (cumulative rigid rotation via Kabsch confirmed it; the earlier per-segment-median
metric was blind to coherent rigid rotation). Root cause: **every spring in BoA is a dt-coupled
`coeff·strain/dt` position correction**, so its effective stiffness `k = coeff/(dt·mobility)`
scales as 1/dt — a "close a fraction per step" projection, not a force. Two fixes were *falsified*
on a new deterministic single-junction bench (`Env.junctionTest`): a rotational-drag-aware force
rescale (`arpRotDragAware`, since reverted) and lowering `arpTransFracMove` — both no-ops, because
the gap closes by a fixed fraction regardless of force magnitude. The fix that works: reformulate
as a **fixed-stiffness explicit penalty spring** (`Env.arpFixedStiffnessDt`, k uses a reference dt,
not live dt) and **sub-cycle it** (r-RESPA, `Env.arpSubcycleN` inner steps of dt/N on only the
branch constraint, soft forces frozen; `Arp23.subcycleAll`). Junction misalignment 36°→~6°;
isolated network cumulative rotation 75°→23° mean across 5 seeds (every seed lower, variance halved);
confirmed by eye (mother filament Brownian-tumbles only, no coherent spin).

**Membrane relaxation, GPU-shaped.** `NodeLink.subcycleRelaxAll` (`Env.membraneRelaxGpuShaped`,
`Env.membraneFixedStiffnessDt`) — self-contained Jacobi iterative projection (zero node force → sum
all link forces at fixed pose → integrate all nodes → repeat to cap/strain-tolerance), the shape a
per-mesh GPU kernel takes. Indistinguishable from the legacy ThreadSet relaxation loop by eye and in
bulge curves, and *more robust* (legacy NaN'd a membrane node at ~12k-segment load; Jacobi stayed
finite). NodeLink uses the same `fracMove/dt` form, so the fixed-stiffness lesson transfers.

**Two segment-posing bugs (unconditional fixes).** Same root cause — a newly created segment was
positioned by its CENTER where a constraint anchors an END, so it was born off-constraint and the
spring yanked it in (a pop at every creation; visible at startup, every branch, every split).
(1) `splitSegment` placed the new plus-end segment from the STALE pre-split `end2Pt` (setFirstHalf
defers the derived-end recompute) → ~½·stdSegLength off → big pop, synchronized across in-phase
mothers every stdSegLength(=32) frames. Now uses the first half's fresh end2 = coord+½·length·uVec.
(2) `makeArpBranch` put the daughter's center at the branch point → end1 ~½·length off. Now places
center at branchPoint+½·length·uVec so end1 sits on the junction. Verified: clean startup, no split pop.

All sub-cycle/fixed-stiffness/Jacobi params **default off** (legacy behavior). Design + GPU port plan
(two sub-cycle flavors, fixed-stiffness prerequisite across all spring families, Jacobi-vs-atomic
shared-endpoint accumulation, TaskGraph integration) in `SUBCYCLING_GPU.md`. Viewer: barbed-end "+"
markers thinner/smaller and off by default. CPU prototypes; GPU kernel port is the next step.

## 2026-06-14 — Membrane nodes no longer randomly turn over

Diagnosed apparent "nodes separating from the sheet" in the compliant-membrane runs: it was NOT
instability — no NaN, NodeLink lengths never stretched (median 0.08 µm, max <0.15, none >0.2), the
farthest node stayed at the pinned edges. The membrane node *count* was dropping (400→386 over 0.3 s):
`StickyNode.biochemStep` calls `super.biochemStep()`, and `ProteinNode.biochemStep` deletes any node
at rate `deltaT/nodeLifetime` (default 10 s). So the structural cortex inherited myosin-cluster node
turnover and was eroding (~3%/0.3 s, time-proportional → disintegration over seconds), leaving
permanent holes (no replacement). Fix: `ProteinNode.biochemStep` now early-returns for `StickyNode`
(membrane nodes are structural). Verified: node count holds constant at 400 across a run. Directed
node flow in/out remains a future deliberate mechanism, distinct from this random turnover.

## 2026-06-14 — Membrane-localized dendritic branching: exploratory lamellipodium kit (all default-off)

**Status: EXPLORATORY, subject to change.** How filaments interact with the membrane, and how the
membrane itself works, are both still in flux. Everything here is gated behind new parameters that
**default to off / legacy behavior**, plus the `xLinkTesting`/`nodeLinkTesting` build flags (left
`false`), so production paths are untouched. Committed so the work isn't lost and the hacks are
documented — not as settled physics. The working demo recipe (CPU, `dt=1e-5`): a hex sticky-node
sheet, a few seeded mother filaments under it (`makeMembraneBranchedMothers`, gated by
`xLinkTesting && nodeLinkTesting`), Arp2/3 branching, growing into and deforming the cortex. Runs in
`threejs_output/membrane_*`.

**The knobs (new Env params, all live-tunable, all default-off):**
- `branchMembraneDist` — branch-eligible when the barbed end is within this distance below the
  membrane plane, *in addition* to the original near-hot-node trigger → the dendritic network
  self-amplifies (daughters branch, not just membrane-proximal mothers).
- `stericPolyFactor` — soft steric attenuation: a barbed end blocked by the membrane keeps
  polymerizing at this fraction of its rate instead of hard-stopping (0=legacy hard stop).
- `membraneCapDist` — lamellipodial capping: barbed ends are uncapped within this distance of the
  cortex and aggressively capped beyond it → growth localizes to a thin layer tracking the membrane.
- `membraneLinkFracMove` — membrane in-plane stiffness (NodeLink correction fraction; was hardcoded
  2.0). Lower = more compliant/floppy sheet. `0.1` gave ~2× the bulge of the stiff sheet.
- `membraneFaceCollideOn` — collide filament tips with the membrane's triangular FACES (point-vs-
  triangle, one-sided, reaction split over the 3 nodes) instead of only the point-nodes, so a
  stretched sheet stays impermeable. Triangles enumerated per-node from `boundTo[]` 3-cliques.
- (`filDragMinMonomers` — the daughter drag floor from the entry below.)

**Hacks — the good:**
- *Membrane-localized capping* (`membraneCapDist`): genuinely the lamellipodium mechanism (cap away
  from the cortex, uncap on contact). Keeps growth a thin membrane-hugging mat — physically honest.
- *Face/triangle collision*: the correct primitive (the membrane is a triangulated surface; edges
  alone are measure-zero and miss open faces — jba caught that). Closest-point-on-triangle subsumes
  face/edge/vertex. Decouples compliance from coverage.
- *Daughter drag floor*: brush-drag, FDT-preserved (see entry below).

**Hacks — the bad / coarse / caveats (READ BEFORE TRUSTING):**
- *`branchMembraneDist` and `membraneCapDist` reference a FIXED z=0 membrane plane*, not the actual
  (bulging) membrane surface. Fine for a flat sheet near z=0; wrong once the cortex curves/closes.
  Should be re-keyed to a local membrane height / the bulge front.
- *`stericPolyFactor` is ad-hoc*, not a derived force–velocity / Brownian-ratchet law. Worse:
  polymerization advances the barbed-end coordinate directly (`incCoord`), so with eased steric the
  tip can poly *through* the membrane faster than the (one-sided) face collision pushes it back —
  poly-driven penetration, distinct from gap poke-through. The one-sided push mitigates but does not
  fully solve it; tunneling is possible at high poly rate / large `dt`. Keep `stericPolyFactor` modest.
- *Face collision is static point-vs-triangle* (no swept/CCD test), and tested per the existing tip-
  near-node mesh pairing, so a face can be double-counted near shared vertices (harmless for a steric
  push) and a fast tip could tunnel a large face in one step.
- *The membrane node-sphere barrier is actually decent on its own*: nodes (r=0.05) overlap at the
  default spacing (~0.07), so legacy collision blocks tips until the sheet stretches enough to open
  >0.1 µm gaps — which the seeded-mother push didn't reach. So face collision's payoff is unproven in
  this regime (0% poke-through with OR without it); it matters in the heavily-stretched / dense-swarm
  regime. It's in as correct, available robustness, not a demonstrated necessity here.
- *`makeMembraneBranchedMothers` hardcodes 5 seed mothers* under the sheet — pure test scaffold.
- *Membrane integrator over-drives at high filament load*: a dense, fast-growing network on a small/
  stiff sheet NaN'd the membrane nodes (~5–6k segments; the "run-2" overshoot mode). Mitigations:
  larger/more-compliant sheet (load spreads), fewer active pushers (capping helps). Real fix deferred:
  a per-step displacement clamp / substep on membrane nodes.
- *The sticky-point membrane has no area growth / remodeling*: the rigid valence-6 sticky-point model
  makes node insertion (edge-split) painful. The right structure for compliant + remodeling +
  impermeable is a dynamically-triangulated surface (topological neighbor lists, edge-split/collapse,
  face collision, bending energy) — a deferred refactor, not done here.
- `Parameter` array cap bumped 256→512 (the new params overflowed it).

Recommended lamellipodium settings (CPU, dt=1e-5): `filDragMinMonomers≈200`, `arpTransFracMove 1.5`,
`branchMembraneDist 0.3`, `stericPolyFactor 0.3`, `membraneCapDist 0.1`, `membraneLinkFracMove 0.1–0.5`,
`membraneFaceCollideOn 1`, `kATPOn2 60`, plus the seeded mothers (`xLinkTesting && nodeLinkTesting`).

## 2026-06-14 — Arp2/3 branch spin fixed via daughter-scoped drag floor

Branched filaments spun / overshot at `dt=1e-5` (explicit-Euler stiffness of the Arp2/3
junction); only `dt=1e-6` was stable (10× cost). Root cause: the translational
(`applyTransForce`) and torsional (`applyTorsionForce`) constraints are solved sequentially as
explicit forces and fight each other → sustained rotation (a coupled-constraint pathology;
deep-research confirmed the rigorous fix is a *simultaneous* implicit/Lagrange solve — Cytosim/
aLENS — deferred as Path B).

**What worked (the fix): a daughter-scoped drag floor.** New `Env.filDragMinMonomers` (INT,
mutable, **default 30 = production unchanged**). In `FilSegment.calculateProperties` the effective
rod length floor is raised **only for Arp2/3 daughters** (`motherFil != null`); `Arp23.set` now
calls `daughterFil.calculateProperties()` so it applies immediately. Because `bRotGam ∝ L³`,
raising the floor over-damps daughter *rotation* (the spin mode) far more than translation — the
viscous-blob mechanism, scoped to branches. At `filDragMinMonomers=200`, `dt=1e-5`: spin drops
~48→0.6 °/2ms (low thermal) and 32→2.5 (full thermal), branches locked at ~70°, beating the
`dt=1e-6` floor (14) at 1/10 the cost. Unbranched/free filaments keep correct slender-body drag.
FDT preserved (`D=kT/γ` from the floored γ) → equilibrium statistics unchanged, only short-daughter
kinetics slowed. Physically defensible: a brushy branch genuinely has much higher (rotation-heavy)
drag than an isolated rod (hydrodynamic screening + steric/entanglement coupling). Magnitude is a
free parameter (calibrate vs an assay later); a future refinement could scale it with local branch
density.

**With the drag floor, `arpTransFracMove` (the translational correction fraction, default 1.0,
committed earlier replacing the hardcoded 2.0) becomes nearly moot** — daughters are over-damped
enough that 0.5/1.0/1.5/**2.0** all run stable (spin ~0.6–1.0, branches held) and grow cleanly
(test tree 0.41→1.07 µm). So the original `fracMove=2.0` tightness is recoverable. Recommended for
branched/Listeria runs: `filDragMinMonomers≈200` + `arpTransFracMove` 1.0–2.0 + `dt=1e-5`.

**Negative results (reverted — don't re-try):** point-mobility normalization of the translational
constraint (didn't reduce spin, loosened branches); critically-damping the torsion (over-stiff,
destroyed branches); applying the inherited Brownian "at the branch point" (the existing rigid-
co-motion inheritance — daughter gets parent's force *and* torque, drag-scaled — is better and
already holds branches at 70°). Diagnostic runs in `threejs_output/branch_*`.

## 2026-06-13 — Dense contractile benchmark v5: 4× density (40× areal) — GPU now wins

Re-ran the v4 dense weak-scaling sweep with the assumed 1× density dialed up 4× ("add particles":
counts quadrupled to 2k/4k/8k/16k/32k fils + matching minifils, v4 box schedule held → 40× areal
density). Harness/data: `RUN_LOGS/2026-06-13_dense_v5_4xdensity/` (reuses v4's run_bcd/gen_fixture/
analyze). Enablement: `MyoMiniFilament` cap 10k→40k and `MyosinDimer` cap 300k→600k (32k minifils ×
16 dimers = 512k at 8×) — uncommitted, like the v4 dimer-cap precedent. All 5 scales clean both paths
(no NaN/overflow); 8× (32k fils, 190k segs) fit in `-Xmx26G` (GPU RSS 25.5 GB, VRAM 3.8 GB).

**Headline — the GPU/CPU verdict flips vs v4.** At 10× density (v4) GPU *lost* everywhere
(GPU/CPU 1.1–5.0). At 40× density GPU *wins* at every scale:

| scale | fils | CPU ms/step | GPU ms/step | GPU/CPU |
|---|---|---|---|---|
| 0.5× | 2 000 | 117.2 | 86.4 | 0.74 |
| 1× | 4 000 | 215.0 | 134.5 | 0.63 |
| 2× | 8 000 | 434.3 | 246.4 | 0.57 |
| 4× | 16 000 | 865.6 | 494.3 | 0.57 |
| 8× | 32 000 | 1777.2 | 1030.2 | 0.58 |

GPU advantage saturates at ~1.75× (GPU/CPU≈0.57) for 2×–8×. Denser network → more force/integrate
compute/step → GPU amortizes its fixed exec+sync overhead. CPU is dominated by cpuIntegrate+step+
gather+brownian (all ~linear in particle count); GPU host time is exec+pack-bound. Percolation: at
40× links/fil≈1.4 (CPU) but only 1× crosses the spanning threshold — dense bundling, still mostly
non-spanning in 650 steps (consistent with the percolation-density-wall finding).

## 2026-06-13 — Membrane subsystem revived + CPU-verified; V1_FINISH_LINE updated

**Membrane is a working subsystem, no bit-rot on the post-SoA/post-RNG/post-`taForce` tree.**
A particle–spring network (`StickyNode`/`ProteinNode` bodies + zero-rest-length contractile
`NodeLink` springs, solved by a ≤30-pass iterative relaxation loop). Three CPU runs
(`RUN_LOGS/2026-06-13_membrane_revive/`): (1) sheet-only 500 steps — holds, no NaN, pinned
edges identical across frames, interior z-ripple ±0.076 µm; (2) sheet + nucRate 50/s → 37.8k
fils — `membraneFilMeshCollisions` engages (152.8M pair-checks, 353k collision forces), sheet
bulges +z monotonically then a few massively-overloaded nodes overshoot the explicit
integrator → NaN (over-driven load artifact, **not** bit-rot; segments never NaN); (3) sheet +
nucRate 5/s → 3.3k fils — badNodes=0 every frame, bulges 0→0.37 µm and holds. No-regression
mechanism confirmed: membrane nodes inherit the consolidated per-worker RNG via base
`calcRandomForces`; `calculateProperties()` already calls `pushDragToSoa()`; post-gather
`incForceSumSlot()` lands because membraneMove runs after `gatherThreadAccumulators`. All code
edits reverted — tree pristine; the `nodeLinkTesting=true` flip (+ optional manual NodeLink
loop; with `collideProteinNodes` on the sheet self-wires 2,581 links by proximity) is
enablement, not a fix. Param files `membraneRevive{,Fils,FilsMod}` kept as artifacts. Full
scope in `MEMBRANE_SCOPING.md`.

**Whole-cell ring gap (build, not repair):** closed-surface factories + auto-linking + turgor
hook + fake-constriction hook all exist; missing = membrane↔ring *tether* (today steric
collision only) and *ring-driven* constriction (today a hardcoded 1e-20 N `fakeConstrictingRing`).

**`V1_FINISH_LINE.md` rev 2026-06-13.** Added **Part M (membrane)**: M0 revive+verify (done);
M1 closed-cortex confirming run; **M2 StickyNode GPU residency port = the stated pre-v2 gate**
(StickyNode body rides the shared `moveThingKernel` via the `RULE_NODE` template, but the
≤30-pass relaxation loop needs an on-device fixed-iteration sub-loop or single-pass stiffness
reformulation — not covered by N2's single-eval `nodeTetherKernel`; **StickyNode device work
unstarted**, distinct from the done `ProteinNode` port N2); M3 ring coupling; M4 stability
clamp; M5 render NodeLinks. Marked **Consolidation DONE** (`benchmark-contractile-dense`
merged to main). Latent hex-link guard bug noted (`j!=nodeCols-1` should be `nodeRows-1`;
harmless while square).

## 2026-06-13 — Viewer: render the membrane (translucent flattened nodes + NodeLink connectivity)

Viewer + emitter only (`ThreeJSWriter.java` + `sim_viewer_boa.html`; no sim-core/physics changes).
Closes the MEMBRANE_SCOPING.md §5 "render NodeLinks" follow-on. **Emitter:** membrane nodes
(`StickyNode`) now carry `"membrane":true` in the existing `"nodes"` array; a per-frame
`"membraneLinks"` array emits one `[node1Id,node2Id]` pair per active `NodeLink` (IDs resolve
against `nodes`, so links are drawn *and* used to infer normals — emitted every frame since
auto-linking mutates topology). **Viewer:** membrane nodes render as a separate translucent,
flattened (oblate) `InstancedMesh`, short axis along a **derived** surface normal — PCA
smallest-eigenvector of linked-neighbour displacements (sign-fixed outward from the membrane
centroid), 2-neighbour cross-product, radial-from-centroid fallback for under-linked nodes; correct
for both the flat sheet and a closed cortex. `NodeLink`s draw as one toggleable `THREE.LineSegments`.
Display-panel knobs: Membrane links (on/off), opacity, flatten. **Validated:** `membraneRevive` (CPU,
`-Xmx2G`, `nodeLinkTesting=true` enablement only — reverted) → 900 membrane nodes, 2581 links, all
IDs resolve; viewer passes `node --check`. Tracked changes limited to the two render files.

## 2026-06-13 — Viewer: active run-folder browsing (no more server restarts)

Tooling-only (`sim_server.py` + `sim_viewer_boa.html`; no sim-core/JSON/render changes). Decoupled
the server from its launch dir. Server now serves from a **configurable root** (CLI arg `argv[2]`,
`SIM_ROOT` env, or default = launch dir/cwd — run from `~/Code` → root `~/Code`, viewer at the
familiar `/sim_viewer_boa.html`) and exposes `GET /api/runs` — a
recursive scan (depth ≤ 4, skips `.git`/`node_modules`/`removeMe`/etc.) for run folders (dir with
`frame_000000.json`), returning `{path, name, frameCount, modified}` newest-first. `/api/simulations`
kept as alias. Viewer's `Recent ▾` picker is populated from `/api/runs` (path + frames + mtime), a
new **↻ Refresh** button rescans without a restart, and `frameUrl` is now root-absolute so frames
resolve regardless of where the viewer HTML is served (`/BoA/sim_viewer_boa.html` under root `~/Code`).
`?dir=<path>` loads a specific run. **Validated:** one server from `~/Code` discovered runs in both
`BoA/` and `threejs_output/` (279 total, newest-first, frames serve 200 from each); a run created
mid-session appeared on rescan without restart; live mode (`?live=`) untouched (separate code path).

## 2026-06-12 — Node-path Stage 2: GPU residency port for the protein-node path (branch `benchmark-contractile-dense`)

`NODE_PATH_SCOPING.md` Stage 2. Full writeup `NODE_GPU_PORT_RESIDENCY.md`; data
`RUN_LOGS/2026-06-12_node_gpu_port/`. **Validated against the Stage-1 CPU oracle — NOT merged, hold for jba.**

Ported the protein-node path to GPU residency (`RULE_NODE`) following the `RULE_MINIFIL`
playbook. The node BODY integrates on device via the shared `moveThingKernel` (per-axis
`xMove/yMove/zMove` honored through `velMask`; raw free-body Brownian `tScale=rScale=1.0`
matching CPU `ProteinNode.moveThing`). The surface tethers + node-dimer internal cohesion run
as ONE device kernel `nodeTetherKernel` (one thread per node) reading the resident pose:
singlet tether (`keepMyosinsOnSurface`), dimer tether (`keepMyosinDimersOnSurface`), and
node-dimer `enforceParallel` (rod↔rod End1/End2 + lever align — byte-identical to
`dimerCohesionKernel`'s blocks; node dimers have `ownerMiniFil==null` so the minifil cohesion
kernel never touched them — a 2nd stale-pose gap closed in the same kernel since the thread
holds both rods). Adopted the single-writer + **1-step-lagged device-resident reaction buffer**
(`nodeBodyReact`, FIRST_EXECUTION, SET) from the start — the minifil stiff-stability lesson.
The surface attach point needs the **full `xToX` body→world rotation**
(`coord + o.x·uVec + o.y·yVec + o.z·zVec`, kernel rebuilds `zVec=uVec×yVec`) — the one
genuinely new cost vs the minifil's axial offset. CPU node path retained behind `BOA_NODE_GPU=0`
(A/B oracle); plain-`ProteinNode`-only eligibility (StickyNode/FillNode/AnchorNode + `fixedNode`
+ `kNodeNuc` nodes stay host-OOP).

**THE hazard, confirmed then cleared.** Node myosins start at random radial surface points and
must thermally diffuse to find the filaments (Stage-1 finding). Running the assay on `-gpu`
*before* the port (node=cpuFallback, CPU tethers reading STALE device pose on the
`noMonomersSimd` residency path) **silently failed: boundMotors=0, tension→0.16 pN** — exactly
the thermal-search failure the scoping doc flagged. After the port the device kernel computes
the tether from FRESH resident pose and the search reproduces: **boundMotors 6–10/step**.

**Validation (vs the CPU oracle band: avgBound 5–7, tension ~2 pN, controls clean).** GPU main
20k: **avgBound 7.08, ewmaTension 1.73, peak 2.01, A/B +1.76/+1.65** (CPU same-fixture 20k:
6.64 / 1.94 / 2.02 / +2.24/+1.66 — clean behavioral match within the documented threaded
run-to-run variance). noMotor control → boundMotors=0, tension ~0.001 (✓); reversed control →
strongly negative (GPU A/B −0.55/−0.67 vs CPU −0.55/−0.47, avgBound 5.92 vs 6.24 — extension ✓). **Force-exactly-once:**
`cpuNodeTetherApplyCt=0` on GPU (vs 20100 on CPU), `ProteinNode=0` in the cpuFallback histogram
(node device-classified), node-dimer cohesion gated to device via `nodeCohesionOnDevice()`.
No 701 / NaN / overflow on any run; tolerated the documented float32 binding seam (behavioral
compare). **Deferred (production-ring):** body-frame `bYMove`, node birth/death (`nodeLifetime`),
formin nucleation (`kNodeNuc`) — all off in the assay.

## 2026-06-12 — Node-path Stage 1: CPU node-based contractility assay (branch `benchmark-contractile-dense`)

`NODE_PATH_SCOPING.md` Stage 1. Full writeup `NODE_CONTRACTILE_ASSAY.md`; data
`RUN_LOGS/2026-06-12_node_contractility/`. **CPU oracle for the later GPU port — not merged, hold for jba.**

Node analog of the minifilament `contractilityAssay`: same two anti-parallel pinned filaments +
the same pin/tension/stats/JSON/HUD scaffold (reused unchanged — the readout projects the anchor
`forceSum` onto the inward `buildDir`, indifferent to the load source), with the bipolar
minifilament swapped for **protein node(s) carrying surface myosins**. New code is small and
localized: `makeNodeContractilityAssay()`, `applyNodeContractilityDefaults()`,
`ProteinNode.countBoundMotors()`, a load-source-agnostic `contractBoundMotors()`/`contractHasMotor()`,
a `ContractAssay.nodes` field, the `-contractilityNode` flag (+ `nodeContractilityAssay`,
`contractNodeRadius/Count/YOffset` params), and three `ParameterFiles/nodeContractilityAssay{,_noMotor,_reversed}`
fixtures. Routes through the shared plumbing by also activating `contractilityAssay`; the node
builder dispatches first so the minifilament path is never hit.

**The geometry finding (the open design question): a single radial-myosin sphere DOES bridge —
but ONLY with myosin thermal search ON.** Unlike the minifilament (end dimers pre-positioned ON
the filaments), node myosins start at random radial surface points; a head binds only if it
lands within `myoColTol` (0.006 µm) of a filament (≈1e-3 solid-angle fraction). With thermal
dialed down (`myoBrownianAttn=0.1`, borrowed from `singleNode_myosins`) the heads freeze →
`boundMotors=0` regardless of head count or capture width (sweeps S1/S3). Restoring full thermal
(`myoBrownianAttn=1.0`) lets heads diffuse and find the filaments → binding. Chosen default: 1
sphere r=0.06 µm, 60 singlets + 30 dimers, full thermal, default yOff=0.05. (A 2-node variant
also works but binds asymmetrically; single sphere preferred.)

**Validation (success pattern, all ✓):** contractile signal — tension 0→positive, both anchors
positive/inward, `boundMotors>0`; no-motor control ≈0 (avgTension 0.02–0.04 pN, 0 heads);
reversed-polarity control **negative** (extension). **Oracle band:** avgBound ≈ 5–7, avgTension
≈ 2.0 pN, peak ≈ 3–4.8 pN. **Determinism caveat:** the threaded CPU path is NOT bit-reproducible
(same seed → final A/B 1.73/2.72 vs 3.87/4.56; multithread sum order + non-master-seeded scratch
RNGs, pre-existing). Time-averaged metrics are the stable oracle, not instantaneous tension —
so Stage 2 compares behaviorally, as the minifilament GPU port did. **Gotchas:** needs `-Xmx4G`
(MyoMotor statics ~640 MB OOM at 800M); needs `tornado-api` jar on the classpath even on CPU.

## 2026-06-12 — A3: un-offloaded CPU force work is already offloaded → clean BAIL (branch `benchmark-contractile-dense`)

`V1_FINISH_LINE.md` item **A3**. Full writeup `FORCE_OFFLOAD_RESIDENCY.md`; data
`RUN_LOGS/2026-06-12_a3_diag/`. **Diagnostic + bail — no port, no physics change, not merged.**

**Premise refuted.** A3 targeted the residual `step` (13.5) / `jointsCpu` (11.8) /
`brownian` (5.5 ms/step @8×) CPU force/integrate work to port the already-device-resident
subset. The decomposition (new A3 sub-brackets + `[A3]` stats line, dense `boa10-64Seg-dyn-dense-{1,8}x`,
window [300,660)) shows there is **no un-offloaded resident-state *force* to port** —
every force reading resident state is *already* a device kernel. Proof, both scales:
`addLinkForcesFireCt = addTorsionFireCt = checkBugInsideFireCt = updatePosFromStepFireCt =
anchorFireCt = 0` (F1/F3/F4/F8-10 never fire on CPU); dimer cohesion + body↔rod constrain
`cpu(work)=0` (joints 100 % device); `cpuFallback = 1` (just the Chamber) out of 823 684
Things at 8×. The ~30.5 ms/step residual is **(1) ∝N CPU dispatch/iteration over
GPU-handled Things doing gated no-ops** (`step`; `brownian` iterates 823k, computes for 1;
`myoJoints1` dispatches over ~100k+ dimers whose cohesion is device-gated) **+ (2) host
pose-derived bookkeeping** (`MyoMiniFilament.updateMyosins` recomputing world-frame
myosin/dimer positions — a dead-host-work candidate; `refreshHostMirrorsForOutput` does
not recompute them and the device cohesion kernel reads only the body-frame constant
offset). Not force computation.

**Stronger bail than the prompt scoped.** The PORTABLE category (resident force → device
kernel) is empty (already kernels); the anticipated bail (needs SoA residency) doesn't
apply (the Things ARE resident — there's just no force left on them). **The v1 force-offload
is complete; the wall is reached.** Per the bail boundary, committed nothing but the
reusable physics-neutral instrumentation (`StepProfiler.ENABLED`-gated): jointsCpu
membraneLinks/myoJoints1/myoJoints2 sub-brackets, cpuFallback type histogram, dimer/body↔rod
cohesion device-vs-cpu counters.

**v2 lever documented (different optimization class — shell elimination, all ∝N
constant-factor, not slope):** (1) `brownian` → dispatch over the existing `cpuFallback`
list, not all 823k Things (~5.3 ms; provably force-identical; keep ThreadSet fan-out, only
change the partition — do NOT main-thread `incForceSum`, that's the `taForce` race); (2)
gate `updateMyosins` off on `-gpu` after a pose-consumer liveness audit (~5.8 ms; caveat:
`keepMyosinsOnSurface` may apply a real boundary force — add a fire-counter; if nonzero it's
the one genuine resident-force port candidate, mirror of the device F1 box kernel); (3)
per-force-gated `step` dispatch skip (~13.5 ms; Lesson-1 silent-drop risk on un-gated F5/F6);
(4) skip the `myoJoints1` dimer wave when all dimers `cohesionOnDevice` (~6.0 ms). GPU÷CPU
8× unchanged at 0.699 (A3 is a no-op port); retiring all of (1)–(4) in v2 would move it to
≈0.61.

## 2026-06-12 — A2: parallelized the serial `gridScatter` bind-grid kernel (branch `benchmark-contractile-dense`)

`V1_FINISH_LINE.md` item **A2**. Full writeup `GRIDSCATTER_RESIDENCY.md`; data
`RUN_LOGS/2026-06-12_gridscatter_a2/`. Not merged.

**Target.** `gridScatter` was the last serial device kernel and the only superlinear
one (v4 Part E: 3.13→14.70→29.91 ms over 1×/4×/8×, ~90 % of kernel time at 8×) — the
counting-sort scatter the `gridAssemble` parallelization left on a single thread
(`WorkerGrid1D(1)`) because the void-only PTX `atomicAdd` has no fetch-add for a
parallel write cursor.

**Phase 1 (diagnose-first).** Superlinear driver = **single-thread memory latency,
not work**: ~200 ns/scattered-write (a lone GPU thread can't hide global-memory
latency) over a cache footprint growing ∝ box area. The key probe — **cell-crossing
rate** (new host `CrossProbe`, CPU run, AABB→cell replica of `segBboxKernelResident`)
— shows the rebuild is ~97 % redundant: only **3.1 % of segments change their AABB
cell-set per step** (3.14 % @1×, 3.17 % @8× — scale-invariant; center-cell 1.6 %; avg
2.82 cells/seg; 0.3 % of steps have 0 crossers). Low crossing ⇒ the prompt's
incremental signal — **but incremental is NOT expressible bit-identically here**:
compact-CSR offsets shift globally on any cell-count change (a single crosser
invalidates ~half the contents array → no win), and the only locally-updatable layout
(fixed-cap-per-cell bins) reintroduces the exact fetch-add insertion race that forced
serial in the first place. So the expressible driver is the serial one → **parallelize**.

**Phase 2 (fix).** `gridScatterChunkKernel` — per-cell-chunk-owned parallel scatter:
the linear cell-ID space is partitioned into fixed-size chunks, one thread per chunk
owning a disjoint range `[lo,hi)`. Each thread walks all segments **in index order**,
writing a seg's ID into every AABB cell in its range via `cellCount[cellId]` as a
**private** cursor — cells in `[lo,hi)` are touched by exactly one thread → race-free
**without atomics**. In-order traversal ⇒ within-cell order matches serial ⇒ CSR is
**bit-identical**. Linear-cell-span early-out skips far segments. Default chunk 64
(`BOA_SCATTER_CHUNK`); serial scatter retained as A/B oracle behind
`BOA_SERIAL_SCATTER=1`. Mirrors the `gridAssemble` atomic-free per-owner-partition
playbook; +0 tasks, no new buffers (chunk size carried in `gridDims[4]`).

**Validation.** (1) **CSR bit-identical** (`GridBuildParityTest`, device chunk-scatter
vs serial host oracle): `offset/count/set/orderMismatch=0` at 1× (51×51×4, S=6000,
seeds 1/2/3) and 8× (143×143×4, S=48000, seeds 1/2), chunks 32/64/512. (2)
**Physics-neutral** (GPU serial vs parallel, same fixture): segs +0.10 %/+0.14 %,
activeLinks 267→271 / 2169→2224 (within the documented run-to-run spread),
`crosslinkFireCt`=6 identical, overflow=0, NaN=0. (3) **Re-measure**: gridScatter
**8× 26.66→7.73 ms (3.45×)**, 1× 2.80→1.34 (2.08×); slope 9.5×→5.8× (reduced, not
killed — residual is the chunk scan's ∝N² with early-out). End-to-end **8× ms/step
251.3→226.8 (−24.6); GPU÷CPU 8× 0.777→0.701** (CPU 8× = 323.6) — GPU wins wider at
dense. 1× GPU÷CPU 1.218→1.195 (scatter is a small fraction there; GPU still loses 1×).
8× chunk sweep flat near 32–64 (par32 7.60 best, par256 10.59). Nothing merged.

## 2026-06-12 — A1: gated the redundant per-step pose-audit scan in `onStepStart` (branch `benchmark-contractile-dense`)

Follow-up to the copy-out fix (`V1_FINISH_LINE.md` A1). Full writeup `RECOMPUTE_RESIDENCY.md`;
data `RUN_LOGS/2026-06-12_recompute_a1/`. Not merged.

**Phase 1 refuted the hypothesis.** Sub-bracketing `onStepStart` into `classify`
(`classifyThings`) vs `poseAudit` (`buildDeltaSet`): `classifyThings` does **not** run every
step — only 4/360 (1×), 33/360 (8×), and **0 % length-only** (all structurally justified). It's
superlinear because each run is a full O(N) rebuild AND the structural-event rate is ∝N → ∝N².
The real redundancy is **`poseAudit`**, which runs every step (O(slotCount) pointer-chase scan)
but finds nothing on ~90 % of steps — the residency-redundant work, and the larger half at 8×
(17.4 vs 11.8 ms/step). The "classify redundant under turnover" idea IS real, but only at fast
biochem cadence: a high-turnover stress fixture shows classify 52 % length-only; the dense
benchmark's 100-step biochem cadence hides it (0 %).

**Fix (poseAudit only).** Gate `buildDeltaSet`'s slot-change scan on `occupantsChanged`
(=`topologyDirty || thingCt-changed`, the same signal that triggers classify — occupants change
only via creation or `removeThing` swap). Skip the scan on non-structural steps (provably empty);
always drain `pendingDirty` (in-place biochem/pin marks fire without topology-dirty). Old
unconditional scan retained behind `BOA_FULL_RECOMPUTE=1` (A/B oracle).

**Result.** poseAudit 1× 0.71→0.02, 8× 17.61→2.18 ms/step (the gate now pays the scan only on
structural steps: 2.18 ≈ 44/360 × 17.6). recompute total 8× 30.95→19.37 (−37 %). GPU wall 8×
272.3→261.7; **GPU÷CPU 8× 0.804→0.773** (CPU 8× = 338.6). Slope reduced but **not killed** —
residual is the non-redundant ∝N² `classify`; killing it needs incremental classification
(high cache-staleness risk, deferred).

**Physics-neutral, proven 3 ways.** (1) Skip-invariant verifier (`BOA_DELTASET_VERIFY=1`,
read-only re-scan on skipped steps): **0 misses at 1×, 8×, and high-turnover stress** — direct
step-for-step proof of bit-identical device pose. (2) A/B oracle: `crosslinkFireCt`=6 identical;
segs/activeLinks within the same-config run-to-run noise floor (inc activeLinks 179–249 vs full
184–220, overlapping); overflow=0, NaN=0. (3) algebraic: a skipped scan feeds the scatter kernel
a byte-identical empty delta. Follow-ons: incremental classify; gate classify on structural-only
(helps fast-cadence configs); bind-map clears are fixed-1M-cap (cheap live-range win).

## 2026-06-12 — Retired the flat ~1 GB copy-out: GPU now WINS at dense (branch `benchmark-contractile-dense`)

Follow-up to v4. Full writeup `COPYOUT_RESIDENCY.md`; data `RUN_LOGS/2026-06-12_copyout_fix/`. Not merged.

**Part 1 — the copy-out was `ffCandPartner`.** The v4 "flat ~115 ms / ~1.08 GB device→host
copy-out, constant across scale" is the fil-fil broad-phase candidate buffer:
`IntArray(segCap × FILFIL_MAX_CAND)` = `1 000 000 × 256 × 4 B` = **1.024 GB**, allocated at a
FIXED capacity (`segCap = FilSegment.soaEnd1X.length = 1e6`, the SoA cap — not ∝N), which is
exactly why it was flat. It was declared `EVERY_EXECUTION` in the chained graph, but the host
consumer `drainFilFilCandidates()` reads it (b) only every `crosslinkCheckInt` (=100) steps and
(c) only the live sub-range `[0, filSegmentCt×256)`. **Verdict (b)+(c)** → cadence-gated to
`UNDER_DEMAND` + `demandSyncFilFilCandidates()` on fire steps (same residency pattern as pose).

**Result: GPU÷CPU 3.32→1.17 at 1×, 1.12→0.82 at 8× — GPU now wins at dense.** Crossover moved
from "just beyond 8×" to ~1.3×. GPU 1× exec 142→34 ms/step. Profiler-confirmed: copy-out
1083→64.8 MB/execute (−1018 MB = the buffer exactly), 115.5→7.2 ms; copy-in + kernels untouched.
**Physics-neutral:** segs +0.10 %/−0.44 %, activeLinks within the GPU path's own run-to-run
spread (v4's two 1× runs differed ±8 % links / ±0.18 % segs), `crosslinkFireCt`=6 unchanged,
NaN=0, overflow=0. (c) live-subrange transfer left as a cheap follow-on (fire-step cost already
amortized to <2 ms/step). Next device cost down: the superlinear `gridScatter` kernel.

**Part 2 — host "other" bucket decomposed** (new `BOA_STEP_PROFILE` nanoTime brackets at existing
boundaries; residual now <2 ms/step, ~100 % attributed). The v4 GPU "other" (16.7→95.6) breaks
down to: **`recompute` 1.2→27.7 ms — the elephant, superlinear 23×** (`setBiophys` + force-zero
memset + `GPUMoveThing.onStepStart` reclassification/delta-audit, which re-runs every step under
turnover); then resetCt 21, cleanup 14, and the CPU force phases still on the GPU path
(step 13.5, jointsCpu 11.8, brownian 5.5 ≈ 31 ms of un-offloaded CPU work — the next residency
frontier). CPU "other" co-elephants: jointsCpu 56.8 + motorFilCol 52.5. Found+fixed two
accounting bugs in the decomposition (pack is inside the move-wrap → double-counted in
moveDrains; step/gather/brownian run on `-gpu` but were excluded from labeled). Attribution only,
no host optimization, pack untouched.

## 2026-06-12 — Dense contractile compute benchmark v4 COMPLETE (Parts A–E; GPU does not win at dense) (branch `benchmark-contractile-dense`)

**Percolation dropped as a gate** (compute cost is count-driven, not connectivity-driven). Ran
the full 0.5×–8× weak-scaling sweep both paths. Full writeup `BENCHMARK_contractile_dense.md`;
data `RUN_LOGS/2026-06-12_dense_v4/` (`bcd_summary.txt`, `prof_summary.txt`).

**Is GPU winning at dense? No — at any scale.** GPU÷CPU = 4.95 (0.5×) / 3.32 (1×) / 2.24 (2×) /
1.55 (4×) / **1.12 (8×)**. CPU ms/step 31.8→51.3→88.2→165→333 (≈∝N); GPU 158→170→198→256→373
(strongly sublinear). Crossover lands just beyond 8×; GPU only pays off for populations larger
than this production-density sweep. 1× stability <1 %.

**Dominant exec cost is a FLAT ~115 ms/execute device→host copy-out, not kernels** (Part E
profiler). copyOut = 115.5/115.5/115.0 ms and ~1.08 GB at 1×/4×/8× — *constant* while box and
population grow, i.e. a fixed-capacity buffer (or blocking sync on one) copied every step;
~9.4 GB/s. Total kernel time only 4.3→33 ms. The flat copy-out is 80 % of exec at 1×, 56 % at 8×
— the single biggest GPU optimization target (the resident-pose pull is already deferred to
biochem cadence; this is a *different* fixed buffer riding every execute). Follow-on: enumerate
the chained graph's `transferToHost` decls and find the ~1 GB fixed-cap one.

**Dominant kernel: `gridScatter`** (bind-grid counting-sort build), the only **superlinear**
kernel (3.1→14.7→29.9 ms, 9.6× over 8× population; 90 % of kernel time at 8×) — next
gridAssemble-style target, but a quarter of the copy-out. **The `bind` narrow-phase did NOT wake
up** (0.14→0.44 ms; `filFilCandidate` flat 1.09×): the v3 quadratic-bind-with-density prediction
is refuted for a *constant-density* weak-scaling sweep (per-cell occupancy held constant); the
density cost landed in the grid build, not the narrow phase.

**Every-step host ranking (GPU):** `pack` is the largest (OOP→device gather) and scales ∝N
(3.8→61 ms over 0.5→8×), then `other` (∝N), `step`, `brownian`, `crosslinkForce`. `meshFill`≈0
(FILSEG_MESH skipped) ✅, `sync` cadenced ✅. At 8× host = 167 ms = 45 % of the GPU step, so even
past crossover the `pack` floor caps the win. `crosslinkForce` non-trivial (0.77–1.5 ms) but only
weakly link-count-driven (per-step FilLink-scan/Bell overhead dominates the force sum).

**Part A heavy-crosslink calibration.** Locked grab=0.05, maxFilLinkDist=0.02, xLinkOnRate=400,
xLinkConc=1.0 → ~500 **active** links at 1× (~0.5/fil), scales ∝N (active CPU 188→3134, GPU
142→1599 over 0.5→8×; GPU forms ~60 % of CPU's — the float32/RNG/1-step-lag seam). **Key
correction:** the steady-state metric must be `PercolationProbe.activeLinks` (checks
`FilLink.active`), NOT `filLinkCt` (array pop incl. broken-uncompacted). They diverge at large
reach: links born over `maxFilLinkDist` die in a step (Bell) but linger in the array. **~1000–2000
*active* links is geometrically unreachable at 10× density** — active peaks ~480–520 then *falls*
at longer reach (strained long links break faster; ~0.4 neighbours/fil at 0.08 µm matches). Same
density wall as v3, measured as active-link count. F-actin ~1.7 µM sim / ~13.7 µM geometric (8×
box-volume convention) — reported, not blocked.

**Memory ceiling = fixed Java array cap, not VRAM/RSS.** `MyosinDimer.theMyoDimers` (100 000)
overflows at 8× (8000 minifils × 16 dimers = 128 k; 4× = 64 k fits) → AIOOBE in
`makeInitialMyoMiniFils`. **Raised to 300 000 on this branch** to complete the sweep (the only
code change beyond instrumentation). VRAM only 2.0 GB at 8× (devBufEst 123→350 MB + ~1.5 GB
context) — RTX 5070's 12 GB far from full; device path would scale ~40×+ on VRAM. Bind order:
**array caps (~8×) → host RAM (~16×, RSS 12 GB at 8×) → VRAM (~40×+)**. GPU clean at every scale
(overflow=0, planRebuild=1, sync cadenced, NaN=0).

**Instrumentation added (branch):** `[STATS] mem` (used/max heap, slotCap, myoCap, devBufEst);
reused `PercolationProbe`/`BOA_STEP_PROFILE`. Harness + analyzers in `RUN_LOGS/2026-06-12_dense_v4/`.
Sibling fixtures `boa10-64Seg-dyn-dense-{0p5,1,2,4,8}x`. **No change to main's defaults.**

**Open science (reported, not fixed):** crosslinks bundle rather than bridge
([[dense-network-percolation-density-wall]]); F-actin 8× box-volume convention
([[boxvolume-8x-concentration-convention]]).

## 2026-06-12 — Dense contractile re-baseline benchmark: GATE BAIL (10× density won't percolate) (branch `benchmark-contractile-dense`)

**Outcome: bailed at the Part A gate** per the prompt — the spec'd 10× areal density does
**not percolate**, so Parts B–E (speed sweep, memory ceilings, kernel profiler) did not run.
Full writeup in `BENCHMARK_contractile_dense.md`; calibration numbers in
`RUN_LOGS/2026-06-12_dense_rebaseline/`.

**What was established.**
- **GPU minifil + turnover is clean now.** A 211-step GPU run of `boa10-64Seg-dyn` (minifils +
  active turnover) completed rc=0, no 701/packRange/ClassCast/overflow. The base fixture's
  "RUN ON CPU — GPU crashes on minifils + FilSegment removal" warning is **stale** (fixed by
  RULE_MINIFIL, 2026-06-11). filFilBroadphase active, FILSEG_MESH skipped every step, sync at
  biochem cadence — as designed.
- **Percolation is a density wall, not a rate/reach problem.** Even with formation saturated
  (P_form≈1, every step) and crosslinker reach widened to 0.40 µm (37× default), the largest
  connected component stays ≤12 of 1000 filaments. Mechanism: with `maxLinksOnSeg=10` crosslinks
  **bundle adjacent filament pairs** (components of size ~2) instead of bridging. Onset is sharp
  with density: 10×→largestComp 12, 20×→20, **40× (4000 fils)→2171 (54 %), spanFrac 0.99,
  percolates=TRUE**.
- **F-actin units caveat.** `Chamber.makeABox` normalises concentration by `boxVolume =
  8·dimX·dimY·dimZ` (400 µm³) while filaments occupy 50 µm³, so sim-internal F-actin is 8× below
  geometric. At 10× density: geometric ~13.7 µM (on target), sim-internal ~1.7 µM.
- **Host-phase decomposition captured** (gate run, GPU, window [200,460)): exec dominates
  145 ms/step (84 %); host = 28 ms/step; largest every-step **host** phase is **`pack` 8.1 ms/step**
  (OOP→device gather), then step 2.4 / brownian 1.6 / gather 1.2 / crosslinkForce 0.77;
  `meshFill` 0.008 (skipped ✅); `sync` 0.81 amortized / 104.7 per-fire, 2 calls = biochem cadence ✅.
  The dense config is strongly device-bound (a kernel-scaling question, not run).

**Instrumentation added (branch, reusable):** `PercolationProbe.java` (union-find largest
component + spanning + links/fil → `[STATS] percolation`); windowed `BOA_STEP_PROFILE` host-phase
report (`reportStepPhaseProfile`/`maybeTakeProfileBaseline`, `BOA_PROFILE_WARMUP`, new
`crosslinkFormTimer`); `[STATS] fActin` readout. Fixture `boa10-64Seg-dyn-dense` (annotated
non-percolating at 10×). **No change to main's defaults.**

**Open / for jba:** decide target density (~40× to percolate, or relax the requirement, or change
the bundle-vs-bridge model) and the F-actin convention (geometric vs sim-internal). Then Parts
B–E run unchanged; confirm a 40× config stays GPU-clean (watch `POSE_DELTA_CAP`/overflow).

## 2026-06-12 — Crosslink lifecycle: cadenced stochastic formation + force-dependent dissolution (branch `crosslink-lifecycle`, NOT merged)

**Mission / design record.** Re-cadence crosslink FORMATION from every-collision-step
to a biochem-class cadence, make it a concentration-dependent stochastic event, and
pair it with the (pre-existing) force-dependent dissolution so the link population is a
finite, tunable formation/dissolution steady state. This removes the every-step host
pose pull the device fil–fil broad-phase forced. Branch off `gpu-filfil-broadphase`
(`b517bca`). **One generic crosslinker type, parameterized — no type-plugin system.**
Reuses the existing `checkToLink` alignment criterion and the `FilLink`/`makeLink`
force+topology machinery unchanged; only *when* and *with what probability* formation
fires changed.

**The three cadences (this is the spec):**
- **FORMATION — `crosslinkCheckInt` (default = `biochemCheckInt`).** Device
  `filFilCandidateKernel` (or the CPU `FILSEG_MESH` walk) → `checkToLink` alignment +
  line-segment-proximity + spacing test → a concentration-dependent dice roll →
  `makeLink`. Fires every `crosslinkCheckInt` steps. Valid because at dt=1e-4 a segment
  drifts ≪ grab(10.8 nm)/step, so a qualifying pair persists for thousands of steps; a
  100-step check never misses a real opportunity.
- **FORCE — every step.** Existing `FilLink` spring force into `forceSum`. Unchanged.
- **DISSOLUTION — every step, force-dependent.** `FilLink.ckLinkBreak` (PRE-EXISTING)
  Bell off-rate riding the strain just computed in the force pass.

**Rate forms + every tunable constant and its chosen default:**
- `P_form = 1 − exp(−k_on · [xlink] · Δt_check)`, `Δt_check = deltaT · crosslinkCheckInt`
  (linearizes to `k_on·[xlink]·Δt_check`; exact form stays bounded in [0,1)). New params,
  both `mutableAtRuntime` + `setDescription`: **`xLinkOnRate` (k_on) = 10.0 /(µM·s)**,
  **`xLinkConc` ([xlink]) = 1.0 µM**. At the fixture's deltaT=1e-4, crosslinkCheckInt=100
  → Δt_check=0.01 s → default P_form = 1−exp(−0.1) = **0.095** per qualifying candidate
  per check.
- `k_off = linkOffConst + linkOffCoeff·exp(aveStrain·linkOffExp)`, `P_break = k_off·deltaT`
  per step. aveStrain is the EWMA normalized stretch (force ∝ strain for the linear link
  spring), so this is the force-dependent Bell off-rate (k0 = linkOffConst, F_c scale ~
  1/linkOffExp). Constants were hardcoded-as-non-mutable; **surfaced as `mutableAtRuntime`
  + `setDescription`**, defaults unchanged: **`linkOffConst` (C/k0) = 1 /s**,
  **`linkOffCoeff` (α) = 1 /s**, **`linkOffExp` (β) = 2**. (Phase C was therefore
  report-and-surface, not new code — `ckLinkBreak` already implemented the Bell model and
  fires every step from `applyTransForce`; the §6 "evaluated once at setup" worry in
  `MESH_FILL_GPU_REDUNDANCY.md` only bit the zero-link config — `recomputeActiveThreadSets`
  runs every step, so the XLink force/dissolution wave activates as soon as links form.)
- **Cadence:** `crosslinkDeltaT` param (default INACTIVE → `crosslinkCheckInt =
  biochemCheckInt`; set `crosslinkDeltaT:true:<s>` to decouple). Launch-time only
  (mirrors `biochemDeltaT`).

**Phase A — re-cadence (device + host) + pull-gone.** A per-step `crosslinkFiresThisStep`
flag is set ONCE in `doLoop` (before both the CPU mesh walk and the GPU move plan),
increment-then-check matching `advanceBiochemCadence`'s phase so that at the default
(`crosslinkCheckInt==biochemCheckInt`) formation fires on the SAME steps as biochem.
Three formation sites all read it: (1) CPU `filSegMeshCollisions` gates `checkToLink`;
(2) device `filFilCandidateKernel` reads it via `counts[3]` (packed every step) and each
thread early-returns off-cadence (zeroes its count) — the kernel still dispatches in the
single chained graph but does no work; (3) `drainFilFilCandidates` is skipped off-cadence.
Because the fil-fil drain now fires on a biochem-fire step, it RIDES the move-phase
biochem-cadence pose pull: `refreshHostPoseForFilFil` skips its transfer when
`lastPoseSyncCounter==Env.counter` (nothing mutates pose between the move pull and the
drain). **Result (boa-xlink-dense-nomotor, -gpu, 2011 steps): `demandSyncPoseCalls=20`
(was 2031 every-step), `pull/step=0.010` — exactly the biochem cadence, perfectly aligned
with `biochemFireCt=20` and `crosslinkFireCt=20`. The every-step pull is GONE.**
NaN/701/crash=0.

**Validation #2 — candidate completeness (the carried-forward 43%/56% question).** New
frozen-pose harness `GPUMoveThing.filFilCandidateParityCheck()` (env `BOA_FILFIL_PARITY=
<step>`, fires on a crosslink-fire step before the drain): compares the device
`filFilCandidateKernel` output against a host BRUTE-FORCE within-reach reference computed
with the kernel's own bounding-sphere criterion (`centerDistSq ≤ (halfI+halfJ+grab)²`,
i<j, different filament). This is the authoritative dedup test — the kernel header proves
any within-reach pair has overlapping AABBs in i's one-cell-expanded scan region (grab ≪
CELL, per-axis |Δ| ≤ centerDist ≤ reach), so the grid walk must visit a shared cell and
the min-corner dedup emits it exactly once; therefore device SHOULD equal brute-force.
**Result (step 999, S=200): `devicePairs=7919 == bruteForceWithinReach=7919,
missing(dropped)=0, extra=0, setMismatch=0, overflow=0`.** The min-corner dedup drops
NOTHING — the device candidate set is exactly the within-reach set. **Verdict: the dedup
is COMPLETE (no fix needed); the earlier ~56%-of-host link deficit is NOT a broad-phase
miss — it is the float32-trajectory / single-thread-RNG / 1-step-lag seam, now confirmed
by direct frozen-pose set comparison rather than inferred.**

**Validation #3 — tunable steady-state (CPU sweeps, settled = back-half time-mean of the
active link count; the instantaneous final count is a post-formation snapshot that masks
dissolution, so a per-step mean `linkCtMeanSettled` was added).**

| sweep | values | settled link count |
|---|---|---|
| k_on (formation), offC=30/coeff=10 | 5 / 20 / 80 | **5.0 / 14.0 / 25.4** (↑) |
| dissolution, k_on=40 | offC 5 / 30 / 120 | **53.5 / 17.0 / 14.2** (↓) |
| plateau, k_on=40 offC=30 | 2000 vs 4000 steps | **19.0 ≈ 20.0** (steady) |

The count scales up with formation, down with dissolution, and plateaus — a finite,
tunable formation/dissolution balance (not unbounded, not zero). Note: with the DEFAULT
slow dissolution (linkOffConst=1 → link lifetime ~1e4 steps) the regime is formation-
accumulating over short runs; a tight plateaued steady state needs faster turnover
(swept above).

**Validation #4 — CPU vs GPU (statistical, 3 seeds, k_on=40 offC=30, settled mean).**
CPU: **19.0 / 19.7 / 14.0** (mean 17.6). GPU: **9.2 / 10.8 / 16.0** (mean 12.0). The
per-seed ranges OVERLAP (GPU's 16.0 inside CPU's 14.0–19.7 spread; CPU's 14.0 inside GPU's
9.2–16.0 spread), with GPU biased modestly lower on average. **Verdict: the inherited
GPU-path seam, NOT a rate-implementation bug.** Evidence: (a) the same fixture showed a
same-direction ~56% gap with the OLD *deterministic* formation (179 vs 323, the
`gpu-filfil-broadphase` entry) — this work did not introduce the gap; (b) candidate
completeness is exact (setMismatch=0) → not a broad-phase miss; (c) the rate code
(`P_form`/`k_off` in `checkToLink`/`ckLinkBreak`) is shared byte-identical across paths —
only the float32 candidate geometry, the single-main-thread vs per-worker RNG stream, and
the 1-step formation lag differ, all pre-existing seam sources. At these modest counts
(~10–20 links) RNG variance is large, which is why the seeds overlap rather than showing a
clean ratio. Definitive isolation would need a frozen-pose formation parity (identical
candidate set + identical RNG seed fed to both paths' `checkToLink`) — the deferred
frozen-pose harness, beyond this scope.

**Validation #5 — physics sane.** NaN/701/crash = 0 across all ~30 CPU+GPU runs; every
run RC=0, forces bounded (stable to completion). Lifecycle active every step: links form
(stochastic), the XLink force wave runs (`applyTransForce`), and links break
(`inactive`>0). The crosslinked fixture mechanically couples filaments (link forces
applied every step) rather than behaving as an unlinked gas.

**Files / harness.** `Thing.crosslinkCheckInt`; `Env.crosslinkDeltaT/xLinkOnRate/xLinkConc`
+ surfaced `linkOff*`; `BoxOfActin.doLoop` cadence flag + drain gate + `linkCtMean*`
stats; `FilSegment.checkToLink` P_form roll + `filSegMeshCollisions` gate; `GPUMotorBinding`
`counts[3]` gate + kernel early-return; `GPUMoveThing` flag/counter, `lastPoseSyncCounter`
ride, `filFilCandidateParityCheck`. Logs + sweep pf's in
`RUN_LOGS/2026-06-12_crosslink_lifecycle/`. Branch `crosslink-lifecycle`, **NOT merged.**

## 2026-06-12 — Fil–fil crosslink broad-phase on device + MYOHEADS_MESH removal (branch `gpu-filfil-broadphase`, NOT merged)

**Mission.** Move the fil–fil crosslinker broad-phase off the host so `-gpu`
contractile/crosslinked runs can drop the host `FILSEG_MESH` fill (real
contractile networks are heavily crosslinked). v2-carryover: a clean, named,
free-standing device kernel over the resident SoA FilSegment grid; `makeLink`
and all link topology stay on host. Branch off `main` HEAD `d582bb3`.

**Phase 0a — dead `MYOHEADS_MESH` removed (physics-neutral by construction).**
`MyoMotor.motorFilMeshCollisions` / `meshAllMotors` had **zero call sites** — the
`MYOHEADS_MESH` 2D motor-fil mesh was write-only on every path (superseded by
`MotorBindGrid3D` / the device bind kernel). Removed the `meshMotorsStart` fill
dispatch (`BoxOfActin.doLoop`), the `Mesh` field/init/`fillMotorMesh`/divide-
conquer+regroup+execute cases, and the two dead `MyoMotor` methods.
`checkFilSegCollision` (used by `MotorBindGrid3D` + `GPUMotorBinding`) kept. No
reader existed → no behavior change; CPU and GPU runs unaffected.

**Phase 0b — `crossLinkGrabDist` vs `CELL_SIZE`: NO BAIL.** `crossLinkGrabDist =
2·actinMonoDiam ≈ 0.0108 µm` ≪ `CELL_SIZE = Mesh.SIZE = 0.2 µm` (~18×). The
resident grid's neighbour-cell reach (one cell, 0.2 µm) far exceeds the grab
distance → the 27-cell / AABB-expanded walk reaches it; no separate coarser grid
needed. Also found: the param label `sideBonds` **is** `Env.xLinks` (mode 0 =
both parallel/antiparallel); so `boa10-64Seg-dyn`'s `sideBonds:true:0.0` already
has the broad-phase active — it forms zero links only because the 10×10 box is
too sparse (fils never within 0.0108 µm).

**Phase 1 — device fil–fil proximity broad-phase (`GPUMotorBinding.filFil-
CandidateKernel`).** One thread per FilSegment i over the resident CSR. Walks i's
AABB cells (from the resident `segBbox`) expanded by one cell, and for each
neighbour j>i in a different filament within the bounding-sphere reach
(`centerDist ≤ halfLen_i+halfLen_j+grab`, the tightest safe necessary condition)
emits (i,j). **No atomics** (the PTX `KernelContext.atomicAdd` returns void — no
fetch-add cursor, the same constraint that kept gridScatter serial): output is
**per-segment owned slices** `candPartner[i*MAXC .. ]` + `candPerSegCount[i]`,
race-free and fully parallel. **Exact dedup, no miss:** a pair co-occupying
several scanned cells is emitted only from the min-corner cell of (i's expanded
region ∩ j's AABB) — j is binned by AABB into every cell of its box, so that
min-corner cell is guaranteed to hold j and be scanned by i. **Correctness
foundation (stated in code):** the resident grid bins by AABB ⊇ the host
`FILSEG_MESH` Bresenham line-raster cells, and grab ≪ cell, so the device
candidate set is a **superset of the host's qualifying (link-forming) pairs** —
no qualifying pair is missed; the host `checkToLink` rejects the geometric
extras. WorkerGrid block 64 (the 701 lesson), global padded to a block multiple
+ self-guarded (the gridAssemble WorkerGrid-remainder lesson). Capped at
`FILFIL_MAX_CAND=256` with per-segment overflow reporting (no global buffer to
overflow). New resident `segFilId` (packed in `packForSingleGraph`).

**Wiring + host boundary.** The kernel is a task in the single-graph chained
TaskGraph, declared **after the `move` task** so it reads the post-integration
resident pose — the same pose the host `checkToLink` sees after the drain
refresh (declared pre-move first; that one-step offset cost ~0 here, 175→179, so
post-move is the principled placement, not a fix). `GPUMotorBinding.drainFilFil-
Candidates()` (after `drainBoundResults`, 1-step lag like bind) walks the slices
and calls the **unchanged** host `checkToLink → makeLink`. `checkToLink` reads
host `end1Pt/end2Pt`, which are **stale between syncs on the residency path** →
the drain first calls `GPUMoveThing.refreshHostPoseForFilFil()` (demandSyncPose +
recomputeDerivedSoA + endpoint/`xRange` refresh) so the host fine check matches
the device candidate geometry. This is the **residual per-collision-step
host-pose cost of the Phase-1 design** (host `checkToLink`); Phase 2 (device-side
geometry test) removes it. Required relaxing the `M==0` early-returns in
`detectBindings`/`packForSingleGraph` to `S==0` — the broad-phase must not depend
on motor count (with 0 motors `counts`/`segFilId` weren't packed → grid built
nothing → `candPairs=0`; fixed). The drain runs on the main loop thread, so
`currentScratch()` resolves to the main-thread RNG/RetObj slot (safe).

**Consumer-activity gate.** `GPUMoveThing.filFilBroadphaseActive = useGPU &&
SINGLE_GRAPH && BOA_FILFIL_GPU≠0 && Env.xLinks.isActive() && no StickyNodes`
(decided once at plan build by scanning `ProteinNode.theNodes`). When active, the
host `FILSEG_MESH` fill **and** the `meshColl` fil-fil walk are skipped (the
device path replaces fil-fil; membrane `membraneFilMeshCollisions` is inert with
no StickyNodes). Membrane configs (StickyNodes) and CPU runs keep the host
`FILSEG_MESH` path untouched. `filFilFillSkipCt` confirms the skip.

**Validation — links-forming fixture (the oracle).** `boa10-64Seg-dyn` forms
zero links (sparse) — useless. Built `ParameterFiles/boa-xlink-dense[-nomotor]`:
0.7×0.7×0.3 µm box, 200 short filaments, `sideBonds` mode 0,
`maxXLinkBondAngle` widened to 0.6 rad, turnover off (static segCt → clean
parity). The no-motor variant isolates the broad-phase from the motor-binding
seam. CPU host-mesh forms a healthy link count; committed as the fixture.

**Link parity (no-motor, 2000 steps, 3 seeds — counts both paths).**
CPU host-`FILSEG_MESH`: **312 / 311 / 345** (seeds 1/22/33, ~323, tight ±5%).
GPU device-broadphase: **179 / 185 / 177** (~180, tight ±2%). NaN/701/crash = 0
on all runs; `filsegMeshFillSkipped=2011` (every step); `candPairs≈15.6M`
(~7773/step), `candMaxPerSeg≈133–142`, overflow 0. The device candidate set is a
**verified superset** (candidates ≫ any host co-cell count) and the grid does
not truncate (`contentsCap` ample, dense box), so the gap is **not a broad-phase
miss**. It is a **systematic GPU-path seam (~56% of host links, reproducible on
both paths)** — same identical segment population (no turnover), only positions
differ — from the float32 trajectory + single-thread-RNG stream + 1-step
formation lag, analogous to the documented GPU-minifil-binding seam. float32
precision is NOT the cause (grab 10.8 nm ≫ float32 ε at µm scale). **Definitive
isolation would need a frozen-pose fil-fil parity harness (like the bind CP1/CP2)
— deferred.** With-motors fixture additionally diverges via the pre-existing
binding seam (meanBoundMotors 15 vs 61 in this dense box), orthogonal to this
work, so the no-motor fixture is the clean parity vehicle.

**FILSEG_MESH retention confirmed.** CPU runs: `filFilBroadphase active=false`,
host fill runs, 311–345 links. `BOA_FILFIL_GPU=0` on `-gpu`: gate flips off,
`filsegMeshFillSkipped=0` (host fill runs on the GPU path too) — and it forms
only **69 links** (the host `meshColl` walk reads stale residency endpoints,
no refresh), vs the device path's ~180, which is exactly why the device
broad-phase + `refreshHostPoseForFilFil` is the correct GPU path. Membrane
(StickyNode) retention verified by inspection of the gate predicate
(`nodeLinkTesting` is a hardcoded flag, not param-settable, so no membrane
physics run); the `!anyStickyNode` term keeps the host path for membrane configs.

**Cadence measurement (realized ratios, biochem-active links-forming run, before
the broad-phase drain forced any extra pull).** `demandSyncPose` **20 calls /
2011 steps = 0.010/step** and `biochemFireCt` **20 / 2011 = 0.010/step** — both
fire at **biochem cadence (every `biochemCheckInt`=100 steps) + output frames,
NOT per-step**. Settles the open question (Step-4 gate confirmed; the cost map's
"output-cadence" hint reconciled — it is biochem-cadence+output). **The Phase-1
fil-fil drain adds a per-collision-step pose refresh on top** (demandSyncPose →
2031/2011 ≈ 1.01/step when broadphase active) — the residual transfer cost,
the Phase-2 lever (device-side `checkToLink`) removes it.

**Phase 2 — warranted, deferred (named, not built).** The measured residual host
cost per step on the active path = the per-collision-step `refreshHostPoseForFilFil`
(full pose pull + `recomputeDerivedSoA`) PLUS the host `checkToLink` over ~7773
candidates/step. Moving the angle+line-segment geometry test onto the device
(emit only qualifying pairs, host does only `makeLink` + loc1/loc2) removes both
the per-step pull and the bulk host fine-check. The candidate transfer
(`candPartner` = segCap·256 ints) also motivates Phase 2's tighter output.

**Verdict.** MYOHEADS_MESH fill gone; a named device fil-fil proximity kernel
over the resident grid feeds the unchanged host `checkToLink/makeLink`; host
`FILSEG_MESH` fill skipped on `-gpu` non-membrane crosslink runs (retained for
CPU + membrane); device forms links abundantly with a verified-superset
candidate set; link count is a systematic ~56%-of-host GPU seam (trajectory/RNG,
not a miss); pull/biochem fire at biochem cadence (drain adds a per-step refresh).
Branch `gpu-filfil-broadphase`, **NOT merged** — jba reviews. Logs +
fixtures in `RUN_LOGS/2026-06-12_filfil_broadphase/`.

## 2026-06-12 — Parallelize gridAssemble — counting-sort bind-grid build

**Mission.** The cost map (`PROFILE_gpu_cost_map.md`) named `gridAssemble` — the
spatial bind-grid build — as the single superlinear kernel in the chained move/bind
graph: a single-threaded counting sort (`@Parallel gid<1`, block [1,1,1]) running
**0.8 ms (1×) → 34.9 ms (16×) ≈ 50% of the whole step**. Parallelize it on the
single-graph (production) path, keep the serial kernel as the oracle, re-measure.
Branch `parallelize-gridassemble` off `fix-701-cohesion-launch`. **Not merged.**

**Layout found — compact CSR (not fixed-capacity-per-cell).** `gridCellOffsets`
(totalCells+1) + flat `gridCellContents` (cap `min(totalCells·BIN_DEPTH, segCap·8)`),
`cellCount` (totalCells) scratch. Linear `cellId = ix + iy·nXBins + iz·nXBins·nYBins`.
`gridAssemble` reads the per-segment AABB cell-ranges in `segBbox[s·6..s·6+5]`
(produced by `segBboxKernelResident`, one thread/segment) — each segment scatters
into *every* cell of its AABB (a triple loop), so it is a counting sort with variable
fan-out, not one-item-per-cell. Downstream consumer `bindKernelResident` walks the
27-cell neighbourhood reading `[offsets[c], offsets[c+1])`; **order-insensitive**
(first qualifying candidate wins, but candidates are a set). `CELL_SIZE = 0.2 µm` and
FilSegments are ~0.17 µm (64 mon × 0.0027 µm) **< one cell**, so each AABB spans only
a few cells → **the O(totalCells) zero + prefix-sum dominate, not the per-segment
histogram/scatter.** `totalCells`: **40 804 (1×, 101×101×4) → 161 604 (16×, 201×201×4)**,
∝ box area; that O(totalCells) serial scan on one thread is the superlinear cost.

**Atomics gate — PASS (with a launch-config lesson).** No GPU atomics existed anywhere
in BoA (the design had used unique-ownership patterns). `KernelContext.atomicAdd(IntArray,
idx, v)` is the supported PTX atomic — TornadoVM's own `testAtomic18_parallel_api_IntegerArray`
guards only SPIRV, **not PTX** (the PTX-skipped atomic tests are all the `AtomicInteger`
node-replacement API, not the KernelContext one). A standalone probe (1,048,576 atomic
adds into 8 heavily-contended bins) gave **every bin exactly 131 072, sum exact, zero
lost updates** — atomicAdd is correct on PTX under contention. The probe also surfaced a
load-bearing rule: **a WorkerGrid drops the remainder partial block when global%local ≠ 0**
(1,000,000 threads, block 256 → only 999 936 ran). So every new parallel task pads its
global work UP to a multiple of the block and self-guards in-kernel.
`atomicAdd` returns **void** — no value-returning fetch-add — which decides the scatter.

**Parallel form (six device-barrier-separated tasks replace one).** In
`GPUMotorBinding`: `gridZero` (∥ cells, =0) → `gridHist` (∥ segments, KernelContext
`atomicAdd` per AABB cell) → `gridScanLocal` (∥ over 512-cell chunks: local exclusive
prefix + per-chunk total) → `gridScanChunks` (1 thread: exclusive scan of the chunk
totals, two-level scan) → `gridScanAdd` (∥ chunks: add chunk base, reset `cellCount` to
the scatter cursor, write `offsets[totalCells]`) → `gridScatter` (**1 thread, serial**).
The scatter stays serial because this PTX KernelContext exposes only a *void* atomicAdd
— no fetch-add for a parallel per-cell write cursor; the prompt's atomic-fetch-add
scatter is not expressible here. Every task gets an explicitly-registered `WorkerGrid`
at block 64 (the 701 lesson — never default a parallel block; the two 1-thread kernels
get block [1,1,1]). Behind `BOA_SERIAL_GRID=1` (`GPUMoveThing.PARALLEL_GRID`, default
ON) the single serial `gridAssembleKernel` is retained as the oracle. The +5 tasks
stayed under the existing `-Dtornado.tvm.maxbytecodesize=16384` (no bump needed).

**Correctness — two oracles, both neutral.** **(1) Structural (`GridBuildParityTest`,
device parallel build vs serial `gridAssembleKernel` on host, identical random AABB
input, grids 1×/8×/16×, multiple seeds): `offsetMismatch=0, countMismatch=0,
setMismatch=0, orderMismatch=0`** and device content-count == serial total — the CSR is
**bit-identical**, not merely multiset-equal. Because the histogram counts are
order-independent (atomic sum is commutative), the prefix-sum is deterministic, and the
scatter is serial (walks segments 0..S in the same nested cell order as the serial
kernel), within-cell order is preserved too. **(2) Downstream physics (`boa10-64Seg-dyn`
1×, 3 seeds, 2500 steps, serial-grid vs parallel-grid GPU):** `meanBoundMotors` mean
**1.630 (serial) ≈ 1.624 (parallel)**, `bindEvents` 197.7 ≈ 201.3 — neutral within the
1.17–2.29 seed spread; **NaN/701/crash = 0, rc=0 on all 6 runs.** Per-seed serial/parallel
differences are pre-existing GPU run-to-run nondeterminism (documented in the 701 hunt),
not from the grid build — which is bit-deterministic.

**Determinism note.** The prompt anticipated within-cell order becoming nondeterministic
(atomic scatter). It did **not**, because the void-only atomicAdd forced a serial scatter
— so the build is fully deterministic and bit-identical to the serial oracle. No
per-cell sort-by-ID is needed. If a future fetch-add enables a parallel scatter, order
would become nondeterministic (physics-neutral) and a per-cell sort would restore
bit-exactness at a cost — flagged, not implemented.

**Perf (profiler `TASK_KERNEL_TIME` + two-step-diff ms/step, same weak-scaling configs
as the cost map; serial today reproduced the cost-map gridAssemble curve).**

| scale | gridAssemble serial | parallel grid total | speedup | (serial gridScatter residual) |
|---:|---:|---:|---:|---:|
| 1×  | 0.80 ms  | 0.36 ms | 2.2× | 0.25 ms |
| 8×  | 15.63 ms | 2.60 ms | 6.0× | 2.44 ms |
| 16× | 31.25 ms | 5.14 ms | **6.1×** | 4.96 ms |

The O(totalCells) zero + prefix-sum collapsed from ~26 ms to **~0.17 ms** at 16×
(`gridScanLocal` 56 µs + `gridScanAdd` 61 µs + `gridScanChunks` 42 µs + `gridZero` 7 µs;
`gridHist` 16 µs). The **single serial `gridScatter` (4.96 ms) is now the whole grid-build
cost** and the only remaining serial term — it cannot be parallelized without a fetch-add.

| scale | CPU ms/step | GPU serial | GPU parallel | **GPU÷CPU par** | GPU÷CPU ser |
|---:|---:|---:|---:|---:|---:|
| 1×  | 3.50  | 35.25  | 35.28 | **10.08×** | 10.07× |
| 8×  | 39.45 | 69.33  | 59.24 | **1.50×**  | 1.76×  |
| 16× | 68.66 | 107.32 | 79.72 | **1.16×**  | 1.56×  |

GPU÷CPU is **unchanged at 1×** (grid build was 0.8 ms of a 35 ms step — negligible) and
**improves at scale: 16× 1.56×→1.16×, 8× 1.76×→1.50×.** It did **not** cross below 1 at
16×: the parallel grid build cut the *GPU exec* (profiler wall 65→40 ms) by the predicted
~25 ms, but the two-step-diff ms/step also carries ~40 ms/step of **unchanged CPU-side
host work** (mesh fill, slotPack, biochem, output) plus the ~30 ms transfer wall, both of
which now dominate. So the cost map's "below 1" projection — which was a GPU-exec-only view
— needs the **second** recoverable lever (transfer coalescing / CPU-side host-work
reduction), out of scope here. **The grid build is no longer the dominant GPU cost.**

**Verdict.** Target hit: `gridAssemble` **31→5 ms at 16× (6.1×)**, both oracles neutral,
serial retained behind `BOA_SERIAL_GRID=1`, nothing merged. GPU÷CPU improved at scale but
crossing parity needs the transfer/host-work lever next. Tooling + logs in
`RUN_LOGS/2026-06-12_gridassemble/` (`oracle.sh`, `perf.sh`, `analyze.py`,
`GridBuildParityTest` method). Branch `parallelize-gridassemble`, pushed, **not merged**.

## 2026-06-12 — Contractile-network memory headroom at 16× (soft ceiling; VRAM recorded, host RSS not captured)

Captures a memory finding from the 2026-06-11 contractile study
(`BENCHMARK_contractile_scaling.md`): the host-RAM ceiling work — all done on the
**dense gliding assay** — does not transfer to the contractile network. The two ceilings
are different workloads, not the same wall at a different scale.

The documented 16× host-heap OOM (`Mesh.<init>`/`Thing.<init>`, `-Xmx28G`) is the gliding
assay's motor-dense graph: **1,568,000 motors at 16×**. The contractile network at 16× is
a far smaller graph — **1,600 filaments + 1,600 minifilaments = 51,200 heads**, roughly
**1/30th** the myosin-head count. It ran to a full 3-run steady-state profile at 16×, where
gliding-16× cannot even initialize.

**Recorded numbers (16× contractile):**
- **GPU VRAM: 782 MiB / 12 GB** — ~6% utilized. VRAM is nowhere near the constraint; 32×
  and 64× are VRAM-reachable. This is why the contractile ceiling is "soft."
- **slotCap** (TornadoVM off-heap FloatArray sizing): **19,602 (1×) → 313,602 (16×)**,
  linear in N.
- **Host heap:** ran under `-Xmx16G`, characterized as "comfortable" — **qualitative only;
  no RSS / used-heap figure was captured.** Only VRAM got a number.

**Not pinned (deliberately, per jba):** resident host RAM (RSS / used heap) for contractile
at 16× is the figure that actually bounds reach on the 31 GB host, and it was not measured.
If contractile sims are planned in the 32×–64× range, CC should record peak RSS (from run
logs or a re-run) to pin the real ceiling rather than infer it. Hypothesis (unmeasured) for
the next limiter as N grows: the **Mesh bin grid** (∝ box area, worst-case `BIN_DEPTH`
provisioning), not the Thing graph — the sparse-bin lever already noted in
`PT3D_SOA_MIGRATION.md`.

Last updated: 2026-06-12 (**Parallelize `gridAssemble` — counting-sort bind-grid build.** The cost map's #1 recoverable wall, the single-threaded `gridAssemble` bind-grid build (`@Parallel gid<1`, 0.8→34.9 ms over 1×→16× ≈ 50% of the step), is now a six-task parallel counting sort on the single-graph production path (behind `BOA_SERIAL_GRID=1`, default parallel). **Layout = compact CSR**; CELL_SIZE 0.2 µm > FilSegment 0.17 µm so each AABB spans few cells → **the O(totalCells) zero+prefix-sum dominate** (totalCells 40 804→161 604 ∝ box area), not the per-segment histogram/scatter. **Atomics gate PASS:** `KernelContext.atomicAdd(IntArray)` is PTX-supported (TornadoVM's own atomic18 test skips only SPIRV); a probe of 1 048 576 contended adds was exact/lossless — but it returns **void** (no fetch-add) and exposed that a **WorkerGrid drops the remainder partial block when global%local≠0** (so every new task pads global up to a block multiple + self-guards). **Form:** `gridZero` (∥cells) → `gridHist` (∥segs, atomicAdd) → `gridScanLocal` (∥512-cell chunks) → `gridScanChunks` (1 thread, two-level scan) → `gridScanAdd` (∥chunks) → **`gridScatter` (serial — void atomicAdd has no fetch-add for a parallel cursor)**; all blocks explicitly registered (the 701 lesson), +5 tasks fit under maxbytecodesize=16384. **Correctness — both oracles neutral:** structural `GridBuildParityTest` (device parallel vs host serial, 1×/8×/16×) = **bit-identical CSR** (`offset/count/set/orderMismatch=0`, not just multiset — serial scatter preserves within-cell order); downstream physics (`boa10-64Seg-dyn` 1×, 3 seeds) `meanBoundMotors` 1.630≈1.624, `bindEvents` 197.7≈201.3, **NaN/701=0 on 6/6**. **Determinism PRESERVED** (serial scatter), the prompt's atomic-scatter nondeterminism caveat does not apply. **Perf:** `gridAssemble` **31.25→5.14 ms at 16× (6.1×)**, 15.63→2.60 (8×, 6.0×), 0.80→0.36 (1×); the O(totalCells) zero+scan collapsed ~26 ms→~0.17 ms, leaving the **serial `gridScatter` (4.96 ms) as the sole grid-build cost** (un-parallelizable without fetch-add). **GPU÷CPU:** 1× 10.07→10.08× (grid negligible there), 8× 1.76→1.50×, **16× 1.56→1.16×** — improved at scale but NOT below 1: the GPU *exec* dropped ~25 ms as predicted, but the two-step-diff ms/step still carries ~40 ms/step unchanged CPU-side host work (mesh/slotPack/biochem/output) + the ~30 ms transfer wall, which now dominate → crossing parity needs the cost map's **second** lever (transfer coalescing), out of scope. Grid build is no longer the dominant GPU cost. Branch `parallelize-gridassemble`, pushed, **NOT merged** — jba's call. Tooling `RUN_LOGS/2026-06-12_gridassemble/`. Full writeup at top.)

Prior update: 2026-06-11 (**GPU cost map — the contractile step is transfer- + serial-kernel-bound, NOT kernel/bandwidth-bound; the loss is RECOVERABLE (corrects the benchmark's "kernel-bound" call).** Per-kernel × scale profile (TornadoVM profiler for attribution, instrumentation-off `exec` for absolutes; **ncu UNAVAILABLE — host has CUDA 11.5 / Nsight 2021.3.1, both predate Blackwell sm_120**, so bandwidth/occupancy are analytical+structural, labeled). Gated on the 701-hunt cohesion-correctness pass (oracle-neutral). **Component split per step (1×/4×/8×/16×):** wall 26.8/35.9/47.0/69.7 ms = **kernel 5%/23%/37%/52% + COPY_IN 59%/49%/42%/34% + COPY_OUT 26%/20%/15%/11%**. **This corrects `BENCHMARK_contractile_scaling.md` B4 ("exec 62–73%, kernel-bound, transfer ≈ 0")** — BoA's `[STATS] exec=` timer wraps the whole `plan.execute()`, which **includes the EVERY_EXECUTION host↔device transfers**; the benchmark's "transfer≈0" measured only the *pose pull* (`demandSyncPose`) and missed **~75 per-step buffer transfers**. Real 1× split = **5% kernel / 85% transfer / ~10% dispatch.** **Two recoverable walls, neither mechanics nor bandwidth:** **(1) `gridAssemble` — a SINGLE-THREADED kernel** (`@Parallel gid<1`, block [1,1,1]) doing the entire spatial bind-grid build (zero cells→count→prefix-sum→scatter) serially on one thread: **0.8 ms (1×) → 34.9 ms (16×) = 50% of the whole step, superlinear** (97% of all kernel time at 16×). Embarrassingly parallel (counting sort) — a standard atomic-count + parallel-prefix + scatter rewrite removes ~all of it. **(2) ~75 per-execute `cuMemcpy` transfers** — COPY_IN 15.7→23.6 ms + COPY_OUT ~7 ms FLAT = ~23–31 ms, **mostly FIXED (per-call latency, not bandwidth: 141 MB/15.7 ms = 9 GB/s = ~36% of PCIe peak, ~1.3% of the 672 GB/s device BW)**; many are static topology arrays (rodSlots, anchorPts, segMotorOffsets, cohSlots…) that ride EVERY_EXECUTION but change only on classifyThings → coalesce + FIRST_EXECUTION them. **The actual mechanics kernels** (joints/move/chain/cohesion/motorForce/segMotorForce/boundary) are **<1 ms combined even at 16× (<1.5% of the step)** — fast, ~linear, **NOT memory-bound → refutes both halves of the binding-efficient/mechanics-slow hypothesis** (binding is "slow" only via the one serial kernel; mechanics barely registers). `dimerCohesion` (the kernel the 701 fix made run) is flat ~0.4–0.5 ms (mostly early-out) — making it run cost nothing. **No kernel is near the memory wall** — grids of 100–1600 threads = a few blocks across 48 SMs, work-starved/latency-bound, not bandwidth-bound. **Recoverable headroom ≈ 95% of the step** (gridAssemble parallelization + transfer coalescing/residency); a v2 doing both plausibly takes the step to **a few ms at every scale → GPU÷CPU parity at 1× and GPU-FASTER at 8×–16×** (CPU 42–70 ms there). **Sharpens the 701-hunt "structural" reading: same direction (not launch, not mechanics, not bandwidth) but the loss is RECOVERABLE** — via grid-build parallelism + transfer overhead, the *opposite* of the "mechanics-bandwidth" hypothesis. **No code change** (profiler-flag-gated instrumentation, stock TornadoVM); deliverable `PROFILE_gpu_cost_map.md`, tooling `RUN_LOGS/2026-06-11_701_hunt/profile_sweep.sh` + `agg_prof.py`. Full writeup in `PROFILE_gpu_cost_map.md`.)

Prior update: 2026-06-11 (**The 701 hunt — move/bind launch-config fix + contractile re-measure (Outcome 3: 701 benign AND cheap; the fix is a CORRECTNESS win, not a speed win).** Hunted the `cuLaunchKernel → 701 (LAUNCH_OUT_OF_RESOURCES)` that fires on every move-execute on `boa10-64Seg-dyn`. **Root cause (proven by `-Dtornado.threadInfo=true`):** of the ~12 tasks in the chained move/bind/cohesion TaskGraph, every parallel one is pinned to a 64-thread block (segBbox 128, gridAssemble 1) **except `dimerCohesion`, which had NO registered `WorkerGrid`** → TornadoVM defaulted it to an **800-thread block** (`Blocks dimensions [800,1,1]`, the lone outlier). 800 threads × (≥82 regs/thread for the fused cohesion kernel) > the 5070's **65,536-register SM file** → the launch is rejected with 701, every execute. NOT a register-pressure-in-the-kernel problem and NOT the move kernel — a single missing launch-config line on the cohesion task. **The 701 was not pure overhead — it was silently DROPPING physics:** `MyosinDimer.cohesionOnDevice()` makes the CPU **skip** minifilament cohesion (rod↔rod, lever torque, body↔rod) on the `-gpu` path and defer to the device kernel — so a rejected launch meant **minifilament cohesion was absent on the entire GPU path** (the kernel, added 2026-06-11, had likely never executed). **Fix (one structural line):** register `WorkerGrid("chained.dimerCohesion", myoCap)` at block 64 (matching its joints/motorForce siblings). Block 800→64, **701 count 60/50-steps → 0**, clean launch every step. **Correctness (firm, vs full-CPU oracle, `boa10-64Seg-dyn` 1×, 3 seeds, 2500 steps):** the now-running kernel is **oracle-neutral** — segCt CPU 540 ≈ GPU 540, total actin length 105.8 ≈ 106.0 µm (within seed spread), `meanBoundMotors` overlaps (the documented binding seam), **NaN=0, rc=0 on every run** incl. a clean 5000-step run. **Re-measured curve (two-step-diff, same weak-scaling configs, output OFF, 3/3/1 seeds):** GPU÷CPU **9.60× (1×) / 1.64× (8×) / 1.56× (16×)** — statistically identical to the pre-fix **9.67 / 1.72 / 1.55**; `exec`/step actually ticked *up* slightly (25.4→26.3 ms at 1×) because the cohesion kernel now does real work where before it was a rejected no-op. **Verdict = Outcome 3:** clearing the 701 leaves GPU÷CPU unchanged — a rejected launch is ~free and the now-running kernel adds ~the same cost back, so the 701 was never throttling throughput. The contractile GPU-slower-than-CPU loss is **structural** (sparse workload — ~306 blocks across 48 SMs at 1× — latency/dispatch-bound, not launch- or occupancy-throttled; at block 64 the mechanics kernels can't overrun the register file, so block-size tuning can't recover it). The real win is correctness: minifilament cohesion is now actually applied on the GPU path. **Recommend MERGE** (one-line launch-config fix, correctness-positive, perf-neutral; behind the existing single-graph default). Branch `fix-701-cohesion-launch`. `RUN_LOGS/2026-06-11_701_hunt/`. Full writeup below.)

Prior update: 2026-06-11 (**GPU-vs-CPU dense benchmarks — gliding point + ratio-locked contractile weak-scaling (campaign thesis + v2 baseline).** Measurement only (no physics/kernel change), recording the campaign's thesis-validating number on the REAL contractile workload and the quantitative v2 baseline. **Stoichiometry (B1):** `boa10-64Seg-dyn` 1× = 100 filaments → 197 segments → **101.2 µm actin** (measured from the initial frame; 64 mon/seg, 0.0027 µm/mon = 370 subunits/µm), mean filament ~1.0 µm; minifilament = 8 dimers/end ×2 = **16 molecules = 32 heads**; 100 minifils → **0.99 minifil/µm** (hits the ~1/µm target exactly), **R = 0.043 molar, 31.6 heads/µm** — minifil size in the non-muscle 15–30 band, R/heads just below the contractile literature band. Kept the 100:100 (1:1) count. **Contractile weak-scaling (B2/B3):** held myosin:actin ratio + actin density constant, grew box AREA by N (boxXY = 10√N, Z = 0.5 held → density constant, slab preserved; matches 06-08 areal method over isotropic N^(1/3)); fil = mini = 100·N. GPU-resident vs full-CPU, same seed, ≥3 seeds, two-step-diff steady-state ms/step, output off. **GPU÷CPU = 9.67× (1×) → 6.17× (2×) → 2.17× (4×) → 1.72× (8×) → 1.55× (16×)** — GPU SLOWER at every scale (opposite of gliding) but the gap narrows **6.2× monotonically** with N (the weak-scaling thesis: fixed per-launch overhead amortizes). CPU 3.6→68.1 ms/step, GPU 34.8→105.5. **Breakdown (B4):** kernel-bound at every scale — `exec` 62–73% of GPU step; **transfer (`demandSyncPose`) < 1 ms/step (output-cadence only, 7–25 calls)** = residency eliminated the transfer wall, confirmed; host SoA pack the secondary cost (0.4→9.5 ms/step). **Ceiling (B5):** SOFT — VRAM 782 MiB/12 GB at 16×, CPU tractable at 16× (68 ms/step), both paths complete; the benign 701-per-execute (`LAUNCH_OUT_OF_RESOURCES`, slotCap 19602→313602, oracle-neutral) is fixed per-launch overhead that amortizes (the #1 v2 kernel-launch lever), not a cap. **Gliding dense point (A):** attempting 12× exposed two GPU gliding ceilings below 12× — (1) TaskGraph bytecode wall (`BufferOverflowException` at `plan.execute`; needs `-Dtornado.tvm.maxbytecodesize=16384`, which 8× didn't), (2) host-RAM OOM-kill (`rc=137`, heap 28G + off-heap > 31G; VRAM only 2.6G) — so the densest CLEAN GPU gliding point stays 8× (0.65×); the 06-08 curve is unchanged. **Verdict: residency converts transfer→0 (kernel-bound), and GPU÷CPU on the contractile network improves 6.2× across 1×→16×, but the workload is still GPU-negative (1.5–9.7× slower) because it is sparse + launch-throttled — this curve is the v2 baseline v2 must reproduce and beat.** `BENCHMARK_contractile_scaling.md`; tooling `RUN_LOGS/2026-06-11_contractile_scaling/` (+ `2026-06-08_scaling_study/run_dense_point.sh`). **Recommend MERGE (benchmark tooling + docs; no source change).** Full writeup below.)

Prior update: 2026-06-11 (**Minifil-ON turnover on device — packRange cast fixed; GPU-resident contractile network with turnover (CAMPAIGN CLOSER).** The terminal gate of the mainline GPU residency campaign is closed: the full contractile network — 100 actin filaments + 100 minifilaments + active turnover — runs GPU-resident to completion, behavior-neutral vs the CPU oracle. **Crash mechanism:** `packRange`/`packDynamicRange` cast `(FilSegment) t` in the `RULE_FIL` branch; the historical `ClassCastException` is a `rules[]`/`theThings[]` desync — a slot cached `RULE_FIL` whose live occupant is a `MyoMiniFilament`. The ONLY path that puts a different-typed Thing in a slot's index without an append is `removeThing`'s swap-compaction, which the **2026-06-09 fix already covers** (`markTopologyDirty` on swap → `classifyThings` refresh before the next pack); Phase A `1d7dcc3` added the `RULE_MINIFIL` pack branches. **So on HEAD the cast does NOT reproduce** (`boa10-64Seg-dyn` seeds 1&2 ran 5000 steps clean pre-change) — the journal's "residual blocker" note was STALE (carried from pre-fix; boa10-64Seg-dyn was never re-tested on the merged HEAD, Part-2 used the `-nomini` variant). **Reproduced the mechanism on demand** via fault injection `BOA_DISABLE_TOPODIRTY_ON_SWAP=1` (default OFF): 538 desyncs over 5000 steps, rc=0 (all caught). **Fix:** `reconcilePackRule` dispatches on the LIVE occupant type, not the cached rule — the `FilSegment` cast runs only when the live type IS FilSegment, so a stale rule can never throw; on mismatch it counts (`[STATS] packRuleDesync=N`), logs, and `markTopologyDirty` to self-heal. Closes the crash class structurally, independent of the topologyDirty signal; in production `packRuleDesync=0` (provable pass-through → behavior-neutral). **Force-coverage exactly-once preserved** (only selects per-slot Brownian + makes the cast safe; no force/kernel change). **Validation (3 seeds + 10k long, GPU-resident vs full-CPU oracle):** 10/10 runs `cast=0 NaN=0 packRuleDesync=0 overflow=0 planRebuild=1`; segCt GPU 545 ≈ CPU 557 (neutral), len ~197±42 nm identical; **residency cadence held with minifils — `demandSyncPose calls=151 @5k / 301 @10k` (biochem+frame, not per-step)**; minifils hold span ~180 nm (cohesion on device works). `meanBoundMotors` GPU<CPU = the **pre-existing GPU-minifil-binding seam** (2026-06-09), NOT this fix (noisy both paths). **Flagged separate residual:** at `slotCap=19602` one INERT kernel emits a benign per-step `cuLaunchKernel 701` (out-of-resources) — results oracle-neutral, no NaN; newly exposed at scale, a kernel block-size tuning item (the v2 scale lever), independent of this fix. `RUN_LOGS/2026-06-11_minifil_turnover_packrange_fix.txt`. **Recommend MERGE — campaign closed.** Full writeup below.)

Prior update: 2026-06-11 (**Float32 verdict (cohesion body-reaction) + residency under ACTIVE TURNOVER.** Two parts. **Part 1 — float32 or "round up the usual suspects"?** Added a diagnostic synchronous (no-lag, SET) body-reaction path (`BOA_COH_SYNC`, off by default) and swept (N, myoMiniFilFracMove, dt) on three schemes: float32-sync (device), float64-sync (CPU oracle), float32-lag (production). **VERDICT: the production-fix rationale is a MISATTRIBUTION (RMW confound) with a narrow genuine float32 effect on the side.** At the PRODUCTION operating point (fm=0.07, N=8) synchronous float32 SET is STABLE (wander 22.7° ≈ oracle 20.4°) — the journal's "190°→NaN synchronous divergence" was the cross-step RMW confound (body slot += across steps), exactly the flagged confound; the lag is NOT load-bearing there. The stability threshold tracks the **fm·N stiff-coupling product** (a stiff explicit-integration instability): float32-sync diverges at product ~1.5, float64-sync at ~1.9, and **float32-LAG is stable to product 32 (fm=4.0, never broke)** — so the lag extends the margin >20×, a SCHEME (staggered) effect that float64 needs too, NOT precision. A genuine but narrow ~20% float32 margin-narrowing DOES exist (sync float32 diverges at product 1.5 vs float64 1.9, confirmed at fm=0.20 N=8: float32-sync DIVERGED, float64-sync STABLE), but only at 2.5–4× production stiffness, seed-marginal, and the device also differs in Brownian RNG so 20% is an upper bound on pure precision. **V2: keep a deliberate lag (or semi-implicit/softer fracMove) for the stiff cohesion term as a stiff-stability measure; f64 on that term is unnecessary and would not remove the lag's need at high stiffness.** Diagnostic branch `float32-stability-diag` (commit `BOA_COH_SYNC` + sweep); `RUN_LOGS/2026-06-11_float32_cohesion_stability_sweep.txt`. **Part 2 — residency under active turnover (LANDED, branch `turnover-residency`).** Retired the per-step pose pull on the biochem-ON turnover path by **phase-aligning biochem to a GLOBAL cadence** (`GPUMoveThing.biochemGlobalCadence`/`advanceBiochemCadence`, default-on for `-gpu`): all FilSegment poly/depoly/split/sever mutations now fire on the same 1-in-`biochemCheckInt` step (rate preserved, phase synchronized) instead of scattered per-instance phases — concentrating the relative-write `incCoord`/`setFirstHalf` (which need a fresh host coord baseline) onto biochem steps, where the pose is pulled. Pull now fires at **biochem-cadence + output-cadence only**. On `boa10-64Seg-dyn-nomini` (full turnover: poly/depoly, cofilin sever, kRdmNuc nucleate, split): **demandSyncPose 2011→61 at 2000 steps (=20 biochem + 41 frames), 5011→151 at 5000 steps (=50 biochem + 101 frames)** — exactly Nsteps/K + frames, not Nsteps. **Validation** (3 seeds, GPU-resident vs CPU-global vs CPU-per-instance oracle): statistically NEUTRAL — final segCt means 547/546/571 (within ~13% seed spread), length 195±40 nm all configs; **10/10 runs NaN-free**, planRebuild=1, poseDelta overflow=0 even at production length. **Force-coverage exactly-once trivially preserved** — the change touches NO force/kernel code (only cadence-gating + pull scheduling). Topology marks already cover split/sever/removeThing; nucleation creates absolute-coord segments handled by the slot-scan. `RUN_LOGS/2026-06-11_turnover_residency_validation.txt`. **Residual blocker:** minifil-ON turnover configs (`boa10-64Seg-dyn`) still hit the pre-existing `packRange` MyoMiniFilament→FilSegment cast crash (orthogonal to this work) — that is the last gate before GPU-resident contractile-NETWORK turnover. **Recommend MERGE Part 2; HOLD Part 1 (diagnostic; cherry-pick `BOA_COH_SYNC` as residency tooling if wanted).** Full writeup below.)

Prior update: 2026-06-12 (**Residency flip LANDED — minifilament body reaction on device + per-step pose pull retired (contractility path).** Branch `minifil-cohesion-device`. **Obstacle 1 — body reaction on device (host bridge retired):** `dimerCohesionKernel` restructured to **one thread per BODY** (single-writer body slot, race-free) — kills the host bridge (`cohBodyForce/Torque` scratch + readback + `packCohesion` gather). **The real obstacle was float32 stability, not the second kernel:** synchronous body reaction diverges (body wander ~190° vs oracle ~21°) because the body couples to all N dimers and the same-step stiff feedback is unstable in float32 — **the 1-step lag is load-bearing, refuting the "mere over-damping" premise.** Resolution: 1-step lag in a **device-resident buffer** (`cohBodyReact`, FIRST_EXECUTION; body slot SET not RMW). Also fixed `accurateAcos`→`fastAcosDev` (CPU uses raw `Pt3D.fastAcos`). Behavior-neutral vs oracle: span 180nm, **jitter now matches oracle** (1.7 vs 1.5nm; bridge over-damped to 0.4), wander tracks oracle within seed noise, tension/boundMotors neutral, no NaN. Commit `53facdf`. **Obstacle 2 — exhaustive consumer audit + pull RETIRED:** instrumented audit (`BOA_POSE_AUDIT`: counter + caller capture on ALL host-pose accessors incl. the bulk `recomputeDerivedSoA` + `Pt3D` transforms the prior reasoning-only audits missed). Complete map on `contractilityAssay_gpu` = 2 roots: `refreshMiniFilBodyDerived` (DEAD — `updateMyosinDimerPositions` doesn't run per-step, proven by `xToX` never firing; **gated off**) + the anchor pin (harness, **tolerates stale pose** — targets a fixed anchor, quasi-static). **Pull retired** (`retirePosePull()`, default-on for `contractilityAssay`): **`demandSyncPose` 10203→102 calls, 9.14s→0.06s**, anchors held <0.5nm, 3-seed tension/boundMotors neutral, no NaN. Non-contractility minifil keeps the pull (no regression). `RUN_LOGS/2026-06-12_pose_consumer_audit.txt`. **Recommend MERGE** — body-gather landed clean, pull retirement validated. Full writeup below.)

Prior update: 2026-06-11 (**Minifilament cohesion onto device + per-step pose pull retired — biochem-path residency flip.** Branch `minifil-cohesion-device` off main (`a2956e9`; has Step-1 dimer port `97bf945`, `84e188c`, Phase A `RULE_MINIFIL`). **Step 0 — force-coverage survey** (`RUN_LOGS/2026-06-11_cohesion_device_step0_survey.txt`): mapped the complete cohesion dispatch — `parallel` is ALWAYS true (antiparallel/`alignYVecLeversTorque`/`keepMyosinsOnSurface` are dead), `alignUVecLeversTorque` gates on `!(both heads onFil)`, the body attach offset is purely axial (y=z=0) so `attachPt = body.coord + offsetX·body.uVec` (no frame rebuild). Expressible as a per-(dimer) kernel with packed flags → no bail. **Step 1 (committed `ed957a3`) — cohesion on device, behavior-neutral:** `dimerCohesionKernel` (one thread per parallel GPU-handled dimer) reproduces `applyRodCouplingEnd1/End2` + conditional `alignUVecLeversTorque` + `constrainEnd{1,2}Dimers` from the resident pose; CPU cohesion gated off in lockstep via `MyosinDimer.cohesionOnDevice()` (verified `cpuCohesionApplyCt=0` — no double-apply). **Two bugs found+fixed in validation: (1) linkUVec SIGN FLIP** — `Pt3D.unitVec(a,b)=(a−b)`, so the kernel's `(p1−p2)` made the springs repulsive → runaway NaN by frame ~9; negated → span locks 180 nm. **(2) the body-reaction gather wouldn't run as a 2nd device kernel** — a per-body gather task never executed in the chained graph (writes to `jointForceSum[bodySlot]` never landed; verified by an unconditional top-of-kernel sentinel), though per-dimer −F/−τ match CPU exactly. Worked around with a **host bridge**: the dimer kernel writes per-dimer −F/−τ to `cohBodyForce/cohBodyTorque` (read back EVERY_EXECUTION), `packCohesion` sums them into the body slot for the next move (1-step lag). Needs `-Dtornado.tvm.maxbytecodesize=16384`. Neutral A/B (devcoh vs `BOA_MINIFIL_COHESION_CPU=1` vs full-CPU oracle): `boa10-singleMiniFil` span 180.0 nm, body wander 16–18° ≈ oracle 18–21°, no NaN; `contractilityAssay_gpu_short` mean tension 1.58 vs oracle 1.85 (within ~1.8× run-to-run noise), boundMotors 5.67 vs 6.08. Body *translational* jitter is mildly over-damped by the bridge lag (sub-nm); the rotational/held DOF + tension are neutral. **Step 2 (audit → BAIL, no flip):** cohesion is NOT the last per-step host-pose consumer (refuting the prior session's Step-3 audit, which predated this port). Remaining on `contractilityAssay_gpu`: (1) `updateMyosinDimerPositions`+`refreshMiniFilBodyDerived` — read host body pose to recompute attach points now DEAD (device computes them internally), a quick gateable follow-on; (2) the contractility anchor-pin (host end1/2, assay-harness); (3) the biochem flag-gate; plus the new host bridge's per-step force readback. So the pull (`demandSyncPose calls=10203`) is NOT retired. `RUN_LOGS/2026-06-11_cohesion_device_step{1,2_audit_BAIL}.txt`. Full writeup below.)

Prior update: 2026-06-11 (**Dimer-motor cross-bridge force onto device + ckRelease lag fix — biochem-path residency step 2.** Branch `dimer-motor-force-device` off main (base has `84e188c` brownianDeltaT-removed + Phase A). **Step 0 — θ_LM closed:** re-measured θ_LM=angle(lever.uVec,motor.uVec) on `boa10-singleMiniFil` (deltaT=1e-4 minifil config) on the brownianDeltaT-fixed base — CPU steady **24.8°** converges to GPU **18.2°** (was CPU ~67–82° / GPU ~16–19° on the √10-buggy base; θ_RL matches 90.3 vs 89.3). The wide CPU θ_LM was the over-excited buggy-Brownian value (√10 over-forcing shifts the soft anharmonic lever-motor joint's *mean*); it is another face of the removed √10 bug → **closed, proceed**. **Step 1 (committed `97bf945`) — dimer cross-bridge force onto device:** extended the device motor-force kernel coverage from MyosinFixed-only to *all* GPU-handled bound motors via a two-gate flip — `GPUMoveThing.packMotorBinding` drops the `instanceof MyosinFixed` gate (any bound motor with a GPU-handled seg gets a real `boundSegSlot`/`posOnSeg`/CSR reaction entry) and `MyoFilLink.gpuMotorHandled` drops the short-circuit (CPU `addForces`/`alignUVec`/`alignYVec`/`ckRelease` no-op in lockstep). NO new kernel — the shared `motorForceKernel`+`segMotorForceKernel` are pure-geometry (motor pose via `motorSlots`, seg pose, `posOnSeg`, cocked, drags), the motor sub-Things were already in the joint list. So F8/F9/F10 apply **exactly once** on device. Behavior-neutral A/B (`contractilityAssay_gpu_short`, dimer motors, 3 seeds): the device path matches the **CPU oracle** (the ground truth; the `BOA_DIAG_CPU_MOTOR=1` control is a float32-pose+CPU-force hybrid that over-churns) within run-to-run noise on *every* metric — boundMotors 5.41 vs 4.53, meanBoundMotors 4.83 vs 4.07, tension 1.84/1.67 vs 1.45/1.34 pN, bindEvents 947 vs 876; instantaneous boundMotors == the control (5.41 vs 5.54) → no drop/double. No NaN/crazy. **Step 2 (no-op, NO commit) — ckRelease reorder ALREADY landed:** the "reorder ckRelease after moveThings" RELEASE_LAG_DIAGNOSIS.md recommends is already on main (`844ecc9`, 2026-06-04; ckRelease deferred to `bridgeMotorForceWriteback`, bit-exact step-N read verified, `git blame` confirms both placements, ancestor of HEAD). It applies automatically to the Step-1 dimer motors (uniform bridge path), and the ensemble confirms the device release rate already tracks the oracle (bindEvents 947 ≈ 876 — a residual −29% lag would put it systematically below) → nothing to implement; hard-bail per the discovery convention. **Step 3 (audit, no change):** after Step 1, **cohesion** (`MyoMiniFilament.constrainEnd*Dimers` + `MyosinDimer.applyRodCoupling*`/`alignUVecLeversTorque`, reads fresh host rod/lever/body pose) is the **sole remaining per-step production-physics host-pose consumer** — Step 1 retired the *bind-application* blocker the Phase A writeup named as blocking Phase C (the motor cross-bridge `MyoFilLink.addForces`/`updatePos`). The contractility anchor-pin is assay-harness-only; biochem is gated-on but inactive (zeroed turnover). `demandSyncPose calls=10203` unchanged (one structural per-step pull gated on the `noMonomersSimd:false` flag — Step 1 removes a *consumer*, not the call). **Deliverable: cohesion→device (the deferred Phase B) is the last port before the residency flip can land on this path, and now GAINS the residency payoff it lacked when first deferred.** Did NOT flip the pull. Logs `RUN_LOGS/2026-06-11_dimer_motor_device_step{0,1,2_BAIL,3_audit}*.txt`. Full writeup below.)

Prior update: 2026-06-11 (**Minifilament Brownian: CPU/GPU parity (`brownianDeltaT` removed) + body thermal scale; spin-seam premise refuted.** Investigating the "GPU minifilament spin" (Path B Phase 2a), the spin-coherence probe does **not** reproduce a real seam on current main: at the 300-step run length its statistical noise floor (~1/√62 ≈ 0.13) swamps the signal (64 correlated myosins ≈ 1 effective DOF). 5-seed ensembles GPU 0.207 / CPU 0.140 / body-off 0.158 all overlap; at 603 frames GPU (0.028–0.036) == CPU (0.021–0.044) at the floor, and in the same first-63-frame window CPU (0.249) is *more* "spun" than GPU (0.104). **No reproducible spin seam → the body+cohesion device port (Phases A/B/C) chases an artifact; Phase 0 bail** (and Phase C would anyway be blocked by bind-application, an in-scope per-step pose consumer). The REAL CPU-vs-GPU difference jba saw in frames ("CPU myosins knocked around, GPU milder") is a **√10 Brownian bug**: GPU kernel scales by `sqrt(2kT/deltaT)` (deltaT=1e-4) while CPU `calcRandomForces` scales by `1/brownianDeltaT` (1e-5) with integration at deltaT — FDT-breaking; CPU diffuses ~10× too fast (rod-center step CPU 17.4 vs GPU 5.5 nm = 3.16×). Per jba (`brownianDeltaT` was always meant to equal `deltaT`; residual): **removed `brownianDeltaT` entirely — Brownian always uses `deltaT`** (`Env.java`, `Thing.java`; `brownianApplyInt`=1). No-op where they already matched (gliding & contractility_gpu both 1e-5/1e-5; Lp configs ran 1e-5/1e-5 too); corrects only the deltaT=1e-4 minifil configs → CPU rod step 17.4→**5.46 nm = GPU 5.51**. Also added **`Env.myoMiniFilBrownianScale`** (default **0.1**, runtime-mutable; `BOA_MINIFIL_BROWNIAN_OFF` still hard-off): minifilament BODY thermal dialed to 1/10 of the (corrected) free-body value — body-axis wander 522°→21° (CPU≈GPU). Branch `minifil-body-cohesion-device`. `RUN_LOGS/2026-06-11_minifil_brownian_parity.txt`; viewer `~/Code/threejs_output/minifil_*`. **Then, per jba, did the body→device port (residency, not spin): Phase A landed** — `MyoMiniFilament` body is now device-integrated (`RULE_MINIFIL`), on the same float32 integrator as its rods, so the cohesion action/reaction integrate in matched precision (removes the two-integrator split — the architecturally-correct state). Validated vs CPU: span locked 180.0 nm (300- & 3000-step), no NaN, jitter/wander/COM match. **Phase B (cohesion→device kernels) DEFERRED** and **Phase C (retire per-step pose pull) BLOCKED by bind-application** (`MyoFilLink.addForces`, out of scope) — both documented for the planner. Full writeup below.)

Prior update: 2026-06-11 (**GPU-vs-CPU contractility tension difference — resolved as a measurement artifact, NOT a physics gap.** The prior entry's "~1.8× overshoot = float32/θ_LM seam" was wrong. Measured per-motor cross-bridge force GENERATION (env `BOA_TENSION_DIAG` in `MyoFilLink.addForces` — runs CPU-side on both paths since dimer motors are CPU-handled) plus the anchor force decomposition. Findings: **(1)** the CPU "oracle" ~1.8 pN was UNDER-CONVERGED — CPU tension only plateaus by ~step 15k; true CPU plateau (50k run) = **2.06 ± 0.10 pN**, not 1.8. **(2)** GPU binds motors ~600–1000 steps EARLIER, so its ramp leads; in a 10k window CPU hasn't converged → the "mean over steps>5000" diverges. **(3)** Run-to-run noise is ~1.8× by itself (GPU plateau realizations 1.78 / 2.08 / 2.18 / 3.27 pN — the 3.27 used in the prior entry was an outlier; median ~2.1). **(4)** Small ~6–9% systematic GPU per-motor force excess (meanForceMag 5.65 vs 5.35 pN; bound heads sit ~6% farther from attach points) — a float32 / integration-timing residual, within noise. **NOT** θ_LM/cocking: head-vs-filament alignment `meanMotSegDot` is identical (−0.35 both paths). Readout is clean — no double-count (CPU tension entirely `soaForceSum`, GPU entirely the gathered `jointForceSum`). **Net: true GPU and CPU steady-state tensions agree (~2.0–2.1 pN) within run-to-run noise; neither path is ground truth.** Diagnostic-only (branch `gpu-tension-diag`), not committed. `RUN_LOGS/2026-06-11_gpu_cpu_tension_difference.txt`.)

Prior update: 2026-06-10 (**GPU contractility works end-to-end — three fixes, merged to main.** The contractility assay ran correctly on CPU but produced boundMotors=0 / tension=0 / drifting anchors on `-gpu`. Three independent GPU-residency seams, each found by measuring against the CPU oracle (same seed, only `-gpu` differs). **(1) Fresh-tip cross-bridge force** (`MyoFilLink.addForces`/`updatePos`): the device DOES bind dimer (non-`MyosinFixed`) motors — no `MyosinFixed` gate on the bind decision/drain (refuting the prior "device never binds dimers" conclusion) — but the CPU cross-bridge force read the **stale `bindTip` host mirror** (refreshed only at output cadence) so the spring distance ran 40–80 nm vs a true 3–25 nm → every bind force-broke the next step (`relBreak==ontoFired`, `relNorm=0`), heads froze at ADPPi. Fix: derive the head tip fresh from the demand-synced coord+uVec (== `freshEnd2AsPt3D`) for distance, direction, AND torque arm; `updatePos` uses `freshEnd1AsPt3D`. CPU bit-identical. → boundMotors 5.4 ≈ CPU 5.0. **(2) Tension readout gather** (`GPUMoveThing` + `captureContractilityTension`): the anchor reaction is the device chain force in `jointForceSum`, never gathered to host, so the readout always saw ~0. Fix: transfer `jointForceSum` to host EVERY_EXECUTION when the assay is active + `readDeviceJointForce(thingNumber)`; add it to the anchor force before projecting (1-step lag). **(3) Anchor pin → device** (`applyBenchmarkPins`): the hard-pin `incCoord` is an in-place same-Thing/same-slot host mutation, invisible to `buildDeltaSet`'s slot-change scan, so the pin never reached the device-resident pose → plus-ends drifted inward 0.35 µm, the assay stopped being isometric, tension read low. Fix: `markPoseDirty(seg)` after the snap-back (mirrors the biochem poly/depoly pattern), GPU-gated. → anchor held <0.4 nm, plateau tension 0.185 → 3.27 pN. **Net validation** (`contractilityAssay_gpu_short`, same seed): boundMotors GPU 5.40 / CPU 5.03; plateau tension GPU ~3.3 pN vs CPU ~1.8 pN — recovered into the CPU regime, both anchors positive/contractile, anchors held. Residual ~1.8× overshoot [**later REFUTED, 2026-06-11**: it is a measurement artifact — CPU was under-converged (true plateau 2.06 pN) + run-to-run noise — NOT a θ_LM seam; true steady-state tensions agree. See the top entry]. Merged `gpu-fresh-tip-on-assay` → main (brings the contractility-assay harness `17adff9` along). `RUN_LOGS/2026-06-10_gpu_dimer_bind_rootcause.txt` + `_freshtip_fix.txt`; viewer `~/Code/threejs_output/contractility_gpu_pinfix`. Full writeup below.)

Prior update: 2026-06-10 (**Minimal contractility assay (anti-parallel filaments, isometric tension).** Branch `contractility-assay`. Two anti-parallel phalloidin-stiff filaments (13 segs each, ~2.28 µm) pinned at their PLUS ends to the outer walls of a long narrow box (4×0.3×0.2 µm); one bipolar minifilament in the central 0.76 µm overlap, turnover off. **Reused** the placed-`FilSegment` ctor (new `makeStraightChain` helper), `MyoMiniFilament(coord,uVec)`, `Chamber`, and the `applyBenchmarkPins` hard-pin pattern. **Added 4 small pieces**: `makeContractilityAssay()` IC builder; a generalized pin registry (`Pin{seg,whichEnd,anchor}`) that replaced the hardwired `deflFil` pin — **deflection benchmark still passes, ratio 0.998**; per-step tension readout = `Dot(forceSum, inwardDir)` on each pinned anchor segment, emitted to `[STATS]` + a frame `contractility` block; `contractilityAssay`/`contractNoMotor`/`contractReversePolarity` config flags. Polarity confirmed **end2 = plus/barbed** (`MyoFilLink` `forceDotFil` comment; frame `isBarbedEnd`), so plus ends point outward and the minifilament pulls both anchors inward. **Result**: tension rises 0→~2 pN as motors engage (boundMotors→8), both anchors positive (inward/contractile) and ~equal. **Controls**: no-motor → ~0.001 pN (signal is myosin-generated); reversed polarity → **negative** (extension), sign tracks the geometry. CPU-only; run needs `-Xmx4G` (MyoMotor static SoA ~640 MB) + the tornado-api jar on the classpath even on CPU. Config `ParameterFiles/contractilityAssay` (+`_noMotor`/`_reversed`); logs `RUN_LOGS/2026-06-10_contractility-*.txt`; viewer `RUN_LOGS/3js_contractility_v2`. **Not merged — hold for jba.** Full writeup below.)

Prior update: 2026-06-10 (**Cross-pool `taForce` race fix — serialize multi-pool force phases.** Confirmed root cause of the CPU dimer/minifilament dissolution: `incForceSum` accumulates into a per-thread row `taForce[tid]` where `tid` is each worker's **local** index within its own ThreadSet, and `taForce` has only `allThreadCt`(=16) rows — the design assumes one pool writes forces at a time. But waves releasing 2+ force-writing pools concurrently (`spawn()` is non-blocking) make worker-0 of each pool race on `taForce[0]`/`dirtyCounts[0]`/`dirtyIndices[0]` → dropped `+=` updates and corrupted dirty-bookkeeping → restoring forces silently lost → progressive, non-diffusive dissolution. **Not** cross-step accumulation (forces *are* cleared each step). SoA-inc migration regression. Ladder that exposed it: single myosin (1 pool) holds → dimer (Myosin+MyosinDimer) one myosin dissolves → minifilament (+MyoMiniFilament) falls apart → gliding (1 pool) works. **Methodological note: single-pool tests are blind to this bug class** — the earlier "forces applied, no regression" verdict came from a single-myosin test. **Fix** (BoxOfActin.java only): `runForceWave()` serializes the *pools* of a wave against each other (within-pool 16-worker parallelism preserved); applied to the 3 audited multi-pool force waves. **Default behavior**; `BOA_CONCURRENT_FORCES=1` restores the racy legacy dispatch for A/B. **Completeness audit** (every `startAllThreadSets` site): serialized **xLink(6: FilLink+Arp23+ActA — ActA aliases onto xLinkStart=6), myoJoints1(7: Myosin+MyosinDimer), myoJoints2(8: ProteinNode+MyoMiniFilament+ChamberMyo+ChamberMyoD)**. Corrections to the brief: **membrane is NOT a race** (NodeLink wave 14 vs StickyNode wave 15 — different waves, one pool each); myoJoints2 is a **4-pool** collision. **Validation** (all pass): dimer worst joint gap 0.0021µm fix vs 0.172µm racy (82×); minifilament holds low + normal thermal (span stable, gaps ≤0.06µm); gliding velocity unaffected (cross-mode median Δ0.04µm/s ≪ run-to-run noise Δ0.92µm/s — sim is nondeterministic, gliding is single-pool so serialization is a no-op there); step-time 7s vs 7s on 100 minifilaments (3200 myosins) with no -3js — no regression, worst joint gap 0.078µm (holds). `RUN_LOGS/2026-06-10_taforce_race_fix.txt`. **Follow-on (same branch)**: ladder extended — minifilaments hold against a static actin field (`boa10-miniFil-staticActin`, 0.074µm), and protein nodes carry myosins (singlets + dimers all glued to the sphere, joints ~0.4nm — `singleNode_myosins`); added node-sphere rendering (`ThreeJSWriter` `nodes` array + grey transparent spheres in the viewer, commit `924a9d9`). **Not merged — hold for jba.** Full writeup below.)

Prior update: 2026-06-09 (GPU packRange slot-map staleness fix — the pre-existing `ClassCastException MyoMiniFilament→FilSegment` at `GPUMoveThing.packRange` that blocked the minifilament+turnover workload on GPU. Root cause: `Thing.removeThing()` swap-compacts `theThings[]` but never signalled the GPU layer, so the four cached slot-indexed maps `classifyThings()` owns (`gpuThingIndices`/`brownianRule`/`thingNumberToMoveSlot`/joint slot lists) go stale when an add+remove balance in one step keeps `thingCt` unchanged (the `thingCt != lastThingCt` rebuild proxy is a count check, not identity). Everything else GPU-resident is re-packed each step and self-corrects — the maps are the only stale cached state. **Fix**: `removeThing()` sets `topologyDirty` on a real swap (`Env.useGPU && swapId != lastId`), mirroring poly/split — a single idempotent boolean → one batched `classifyThings` (slot-map refresh, **never** a plan rebuild) per step. **No per-removal rebuild-cost regression** (nomini run `planRebuild=1`). Validated: baseProbe + dyn run clean to completion on GPU (was instant crash), CPU control 743/0.380 ≈ ref 679/0.338 (fix CPU-neutral), nomini rc=0, gliding 7.3683 (in band). Separately flagged: GPU minifil binding is systematically lower than CPU (~270/0.054 vs ~710/0.36, stable across seeds) — the pre-existing CPU-fallback-`MyoMiniFilament` seam, not this fix. Full writeup below.)
Prior update: 2026-06-10 (Path B Phase 2a — fresh-geometry cohesion (kill the spin). The GPU minifilament "strange directed motion" is a coherent spurious **spin**: the CPU cohesion forces (`MyoMiniFilament.constrainEnd*Dimers` C, `MyosinDimer.applyRodCoupling*` D) pulled rod tips toward attach points using **frozen** `soaEnd1/End2` (derived fields refreshed only at output cadence on GPU) while the rods moved on-device every step. **Fix**: derive rod ends on-the-fly from the **fresh, demand-synced** `coord/uVec` (`Thing.freshEnd1/2AsPt3D()` = `coord ∓ ½·length·uVec`) — same formula as `recomputeDerivedSoA`, no new transfer, no per-step derived-SoA recompute, CPU-identical (`freshEnd==end1AsPt3D` on CPU). **Result: spin substantially reduced but NOT eliminated** — matched-seed cloud coherence 0.66→0.27 (s7) / 0.66→0.46 (s11), vs CPU's diffusive 0.03–0.07. The residual is a **rotational integrator-split / float32 seam** (C applies +F/+τ to the float32 device-integrated rod, −F/−τ to the float64 CPU-integrated minifil body — they don't cancel in rotation), **NOT** stale geometry (geometry now as fresh as CPU; the avoided recompute would compute the *same* ends) and **NOT** COM drift (GPU bundle-COM net <13 nm, *tighter* than CPU's 32–45 nm). **θ_LM divergence did NOT resolve** (GPU ~32° vs CPU ~67°, unchanged pre→post) — refuting the Phase-1 hypothesis that it was cohesion-stale-geometry sensitive; it's a separate internal lever-motor/float32 seam. Both residual seams **flagged for jba, not fixed** (out of Phase 2a scope). **Follow-on**: new `BOA_MINIFIL_BROWNIAN_OFF=1` gate (suppresses only the rigid body's own thermal forcing) shows the residual spin is **body-thermal-EXCITED** — body-off drops spin coherence to CPU levels (0.27/0.46→0.026/0.079) and stops the body flopping (axis wander ~12–40× less); the body tumbles on the CPU integrator while the GPU-resident rods chase it (two-integrator seam). **Not merged** — jba views frames first. `RUN_LOGS/2026-06-10_phaseB2a_fresh_cohesion_spin.txt`; frames `~/Code/threejs_output/phaseB2a/{pre,post}_gpu_s{7,11}` + `post_cpu_s{7,11}`. Full writeup below.)

Prior update: 2026-06-10 (Path B Phase 1 investigation — internal myosin joints on-device: **the premise is refuted, no code change made.** The scoped task assumed the device joints kernel is MyosinFixed-only, so non-MyosinFixed (minifilament) myosins' internal rod→lever→motor joints are applied by nobody. In fact there is **no MyosinFixed gate on the internal joints**: `classifyThings` GPU-handles all MyoMotor/MyoRod/MyoLever, the joint-list build admits every all-GPU-handled myosin, and `jointsKernel` computes all four internal apply* for every myosin in the list (only the anchor spring + motor cross-bridge are MyosinFixed-gated). Confirmed empirically: on minifilament myosins **θ_RL (rod-lever) matches CPU ~90°** and internal gaps stay tight/bounded — the joints ARE computed and integrated on-device. **Step 2 would be a no-op.** Residual finding: **θ_LM (lever-motor) diverges** (GPU ~16° vs CPU ~68°) even on a compact single minifilament (not a bundle-density artifact); formula/params/clamp/cocked-flags are all faithful, so it is a downstream sensitivity — Phase 2a now shows it is **not** the cohesion stale geometry. Frames `~/Code/threejs_output/boa_phaseB1_{gpu,cpu}_{singleMiniFil,dyn}`. Full writeup below.)

Prior update: 2026-06-10 (`-3js` output-sync read-only fix — the GPU minifilament "blow-apart" is the output path corrupting host physics state, not dropped cohesion. `refreshHostMirrorsForOutput()` recomputes the host derived arrays (`soaYVec`/`soaEnd1/2`/`soaTransXTox` + `end1Pt/end2Pt`/`bindTip` Pt3D mirrors); the GPU-resident minifilament dimers' CPU coupling (`alignUVecLeversTorque`/`constrainEnd*Dimers`/`applyRodCoupling`) reads those same arrays next step, and the stale→fresh jump under the stiff alignment torque cascades to NaN. **Fix**: snapshot the physics-owned host arrays before the output recompute (`beginOutputSnapshot`), restore them bit-identically after the frame (`endOutputRender`, at the safe point); device pose read, never written. Minifilaments stay GPU-resident — NOT the cpuFallback hybrid. Paired same-seed `boa10-64Seg-dyn-short` GPU runs: with-`-3js` now matches without (crazy=0, no NaN, frames hold, meanBoundMotors≈0.054 = documented stable occupancy) where before with-`-3js` → ~1.16M crazy → NaN by frame 5. Frames staged `RUN_LOGS/3js_readonly_test/frames/`. **Not merged** — jba views frames first. Full writeup below.)

> Earlier entries (2026-05-17 through 2026-05-25) archived in JOURNAL_ARCHIVE.md.

## 2026-06-11 — The 701 hunt: move/bind launch-config fix + contractile re-measure

**Mission.** The `cuLaunchKernel → 701 (LAUNCH_OUT_OF_RESOURCES)` that fires on every
move-execute on the `boa10-64Seg-dyn` family (flagged benign in the campaign closer +
benchmark) sits inside the dominant exec cost. Find why it launches out of resources,
fix the first launch, re-measure the contractile curve, and decide whether clearing it
moves GPU÷CPU toward parity (→ v2-for-speed on) or not (→ mechanics-bound).

**Root cause — a single missing `WorkerGrid`, not the move kernel, not kernel register
pressure.** `-Dtornado.threadInfo=true` dumps the per-task block/grid dims. In the
chained move/bind/cohesion TaskGraph (~12 tasks) every parallel task is pinned to a
64-thread block (`segBbox` 128, `gridAssemble` 1 since its `@Parallel` is `gid<1`) —
**except `chained.dimerCohesion`, which printed `Blocks dimensions [800,1,1]`**, the
lone outlier. The grid scheduler in `installResidentPlan` registers a `WorkerGrid` for
`move`, `joints`, `chain`, `scatterPose`, `boundary`, `motorForce`, `segMotorForce`,
`derived`, `bind`, `segBbox` — but the `dimerCohesion` task (added 2026-06-11) was
never given one, so TornadoVM fell back to its default block size (800). The cohesion
kernel is large (rod↔rod springs + lever torque + body↔rod reaction gather); at
**800 threads × ≥82 regs/thread** it overruns the 5070's **65,536-register SM file**
(65536/800 = 81.9 → the kernel uses ≥82 regs/thread), so the driver rejects the launch
with 701. The starting hypothesis (an oversized *move* block / in-kernel register
pressure) was wrong: every other kernel was already at block 64 (where even the 255-reg
hardware max fits: 64×255 = 16 320 ≪ 65 536). The bug was a launch-config omission on
exactly one task.

**The 701 was not pure overhead — it was silently dropping physics.** A 701 means the
kernel did **not** execute. And `MyosinDimer.cohesionOnDevice()` (true when `-gpu` and
not `BOA_MINIFIL_COHESION_CPU`) makes the CPU **skip** minifilament cohesion —
`MyosinDimer.applyRodCoupling*`/`alignUVecLeversTorque` and
`MyoMiniFilament.constrainEnd{1,2}Dimers` all `continue`/short-circuit and defer to the
device kernel. So the rejected launch meant **minifilament cohesion was absent on the
entire GPU contractile path** — the device kernel had likely never run successfully
(the 800-block default is independent of body count, so it 701s on any config with
cohesion active). The campaign-closer's "oracle-neutral" check held because segCt and
total length are insensitive to cohesion over a run; the missing cohesion did not show
up in those metrics.

**Fix (one structural line, `GPUMoveThing.installResidentPlan`).** Register a
`WorkerGrid1D(myoCap)` for `chained.dimerCohesion` with `setLocalWork(64)` — the same
block and range as the `joints`/`motorForce` kernels it mirrors (its `@Parallel` range
is `cohBodyDimStart.getSize() = myoCap`). Block 800→64 (`Blocks [64,1,1]`, 100 blocks ×
64 = 6400 = myoCap). **701 count: 60 over 50 steps → 0**, clean launch every step, every
scale.

**Correctness (firm — vs the full-CPU oracle, same seed).** The fix makes a
never-executed kernel run for the first time, so this is the load-bearing check.
`boa10-64Seg-dyn` 1×, 3 seeds, 2500 steps, `-3js` final-frame segment count + total
actin length (Σ endpoint distances), `meanBoundMotors` from `[STATS]`, NaN scan:

| seed | CPU segCt | GPU segCt | CPU len µm | GPU len µm | CPU mBound | GPU mBound | NaN |
|---|---|---|---|---|---|---|---|
| 1 | 515 | 534 | 101.7 | 105.1 | 1.47 | 1.15 | 0 |
| 2 | 560 | 548 | 109.4 | 107.8 | 2.15 | 1.97 | 0 |
| 3 | 544 | 538 | 106.3 | 105.0 | 2.44 | 3.39 | 0 |
| mean | 540 | 540 | 105.8 | 106.0 | 2.02 | 2.17 | 0 |

segCt and total length are **statistically neutral** (means identical, within seed
spread); `meanBoundMotors` overlaps (the documented GPU-minifil-binding seam, noisy both
paths). A clean 5000-step GPU run: **NaN=0, rc=0**, `planRebuild=1`, `overflow=0`,
`meanBoundMotors=2.458`. The now-running cohesion kernel produces correct, stable
physics — no blow-up, span held. (Side note: a `BOA_MINIFIL_COHESION_CPU=1` A/B — CPU
cohesion + GPU pose — collapsed `meanBoundMotors` to ~0.08, a pre-existing mixed-mode
diagnostic artifact unrelated to this fix; the production path is device cohesion, which
is the path validated above.)

**Re-measured contractile curve (two-step-diff, same weak-scaling configs as
`BENCHMARK_contractile_scaling.md`, warm-up excluded, output OFF, 3/3/1 seeds).**

| N | CPU ms/step | GPU ms/step | **GPU÷CPU (after)** | GPU÷CPU (before) | exec/step after | exec before | 701 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1× | 3.67 | 35.2 | **9.60×** | 9.67× | 26.3 ms | 25.4 ms | 0 |
| 8× | 42.8 | 70.1 | **1.64×** | 1.72× | 45.9 ms | 44.8 ms | 0 |
| 16× | 70.4 | 110.0 | **1.56×** | 1.55× | 68.5 ms | 64.9 ms | 0 |

The ratios are statistically **identical** to the pre-fix curve, and `exec`/step ticked
slightly *up* at every scale (the cohesion kernel now does real work where before it was
a rejected no-op). The CPU column reproduced the documented numbers (3.6 / ~42 / 70),
confirming negligible machine drift. The pre-fix 701 contrast was captured directly: the
unmodified build emits 60 × 701 over 50 steps; the fixed build emits 0.

**Verdict — Outcome 3 (of the three framed): the fallback was cheap; the contractile GPU
loss is structural.** A rejected `cuLaunchKernel` is ~free, and running the real cohesion
kernel costs about the same, so clearing the 701 nets to zero on GPU÷CPU — it was never
throttling throughput. The contractile network is sparse (hundreds of real Things;
slotCap 19 602 at 1× → only ~306 64-thread blocks across the 5070's 48 SMs), so the step
is **launch/dispatch/latency-bound**, not launch-config-throttled and not occupancy-
limited (at block 64 the mechanics kernels cannot overrun the register file, so there is
no block-size lever left to pull). The honest gating result for v2: GPU-for-contractile
is not made viable by launch tuning — the wall is the mechanics kernels' efficiency on a
sparse bed, which a follow-on per-kernel bandwidth profile quantifies. The concrete win
here is **correctness**: minifilament cohesion is now actually applied on the GPU path
(it was silently absent), at zero measured speed cost.

**Files / repro.** Fix in `boxOfActin/GPUMoveThing.java` (`installResidentPlan`, the
`cohesionWorker` block). Tooling + raw data in `RUN_LOGS/2026-06-11_701_hunt/`
(`timing.sh`, `correctness.sh`/`correctness2.sh`, `raw_after.tsv`, `correctness2.tsv`).
**Recommend MERGE** — one-line launch-config fix, correctness-positive, perf-neutral,
behind the existing single-graph default. Branch `fix-701-cohesion-launch`.

## 2026-06-11 — GPU-vs-CPU dense benchmarks — gliding point + ratio-locked contractile weak-scaling (campaign thesis + v2 baseline)

Measurement only — no physics/kernel change. Produces the campaign's thesis-validating
number on the real contractile workload and records the v2 baseline. Full doc:
`BENCHMARK_contractile_scaling.md`. Tooling committed under
`RUN_LOGS/2026-06-11_contractile_scaling/` (base config + generator/runner +
`results.md` + `raw_rows.tsv`) and `RUN_LOGS/2026-06-08_scaling_study/run_dense_point.sh`.

### B1 — Stoichiometry mapping
Measured from the realized initial frame of `boa10-64Seg-dyn` (seed 1): 100 filaments →
197 segments → **101.2 µm total actin** (Σ endpoint distances; `actinMonoRadius=0.0027 µm`
⇒ 370 subunits/µm, matches literature), mean filament ~1.0 µm. Minifilament =
`numMyoDimersEachEndOfMiniFil`=8 → 8×2 = **16 dimers = 16 molecules = 32 heads** (in the
non-muscle 15–30 band). 100 minifilaments ⇒ **0.99 minifil/µm of actin** (≈1/µm target hit
exactly), **R = 1600/37459 = 0.043 molar**, **31.6 heads/µm** (R and heads/µm just below the
0.05–0.08 / 40–60 contractile band, consistent with the 16-molecule minifilament). The
100:100 (1:1) count already meets the target → kept unchanged. The ratio is set at init;
turnover net-grows actin ~2.8×/run with minifil count fixed, so minifil/µm drifts down
during a run (the weak-scaling invariant holds in the density/statistical sense across N).

### B2 — Weak-scaling design
Held myosin:actin ratio AND actin density constant, grew box ∝ N: **boxXY = 10√N, Z = 0.5
held** (volume ∝ N, areal+volumetric density constant, quasi-2D slab preserved), fil =
mini = 100·N. Chose areal √N over isotropic N^(1/3) because the workload is a 0.5 µm slab —
N^(1/3) would thicken it 0.5→1.26 µm at 16× (a z-confinement physics change) — and √N
matches the established 06-08 gliding method. Sizes 1×/2×/4×/8×/16× = 100…1600 fil,
3200…51200 heads, box 50…800 µm³.

### B3 — GPU-vs-CPU timing (the curve)
Full workload (minifils + turnover), GPU-resident vs full-CPU, same seed, two-step-diff
steady-state ms/step, output off, ≥3 seeds (16× single-seed ceiling probe).

| N | fil | mini | heads | CPU ms/step | GPU ms/step | **GPU÷CPU** |
|---:|---:|---:|---:|---:|---:|:--:|
| 1× | 100 | 100 | 3 200 | 3.6 ± 0.1 | 34.8 ± 0.3 | **9.67×** |
| 2× | 200 | 200 | 6 400 | 6.6 ± 0.4 | 40.7 ± 0.8 | **6.17×** |
| 4× | 400 | 400 | 12 800 | 23.5 ± 0.3 | 51.0 ± 0.2 | **2.17×** |
| 8× | 800 | 800 | 25 600 | 40.2 ± 0.6 | 69.2 ± 0.2 | **1.72×** |
| 16× | 1 600 | 1 600 | 51 200 | 68.1 | 105.5 | **1.55×** |

GPU is slower at every scale (opposite of the gliding bed), but GPU÷CPU narrows **6.2×
monotonically** with N — the weak-scaling thesis (fixed per-launch overhead amortizes).
Inter-seed spread tiny (GPU ±0.2–0.8). The 701 fires deterministically once per execute.
Absolute ms/step carries a turnover-growth/window caveat across N; the **per-row ratio is
clean** (CPU/GPU share seed+window; segCt/length statistically neutral per the campaign
closer; `meanBoundMotors` GPU<CPU is the documented binding seam, not a cost difference).

### B4 — GPU breakdown (kernel vs transfer vs host)
Per-step from `[STATS] gpuMoveThing`: exec (kernel, incl. 701 round-trip) = **62–73%** of
GPU step at every scale (25.4 ms/step @1× → 64.9 @16×). **Transfer (`demandSyncPose`) <1
ms/step always** (output-cadence only: 25 calls @1× → 7 @16×) — residency eliminated the
transfer wall, **confirmed kernel-bound**. Host SoA pack (`slotPack`) is the secondary cost
and the only piece growing materially with N (0.4→9.5 ms/step) — a candidate v2 cleanup,
secondary to the kernel. **v2 lever = kernel efficiency + the 701 launch throttle, not PCIe.**

### B5 — Practical ceiling (soft)
Neither VRAM (782 MiB/12 GB at 16×) nor host heap (`-Xmx16G`) caps the contractile workload
— object count is tiny vs the gliding bed; 32×/64× reachable on memory. CPU stays tractable
(68 ms/step at 16×) — it does NOT go intractable before GPU (contrast gliding's 8× CPU heap
wall). The 701-at-scale is benign (oracle-neutral, no NaN; slotCap 19602→313602 linear), a
fixed per-launch overhead that amortizes (≡ why GPU÷CPU improves), not a cap — the #1 v2
kernel-launch tuning lever. The contractile ceiling is just GPU kernel cost.

### A — Gliding dense point (ceiling refinement; no new timing dot)
Attempting a 12× point on the 06-08 curve exposed two GPU gliding ceilings below 12×:
(1) **TaskGraph bytecode wall** — `BufferOverflowException` in `plan.execute`
(`GPUMoveThing.moveThings:5932`) without `-Dtornado.tvm.maxbytecodesize=16384` (8× didn't
need it; the larger 12× graph does — same flag the contractile path requires); with the
flag the first ~40 steps run clean. (2) **Host-RAM OOM-kill** — sustained 12× is SIGKILL-ed
(`rc=137`, 31.8 s): `-Xmx28G` heap + TornadoVM off-heap (slotCap≈7.07 M) > 31 GB host
(VRAM only 2.6 G — host RAM, not VRAM). So the densest **clean** GPU gliding point stays the
existing **8× (GPU÷CPU 0.65×)**; the 06-08 0.65–0.68× curve is unchanged. A 10× timing point
was queued but not run (stopped per operator — 8× + ceiling are sufficient).

### Verdict
Residency converts transfer→0 (kernel-bound on both workloads), and GPU÷CPU on the real
contractile network improves **6.2× across 1×→16×** — but the contractile workload is still
**GPU-negative (1.5–9.7× slower)** because it is sparse and the kernels are launch-throttled,
the mirror image of the dense, kernel-light, memory-bound gliding bed where GPU wins (0.65×).
**This curve is the quantitative v2 baseline** (reproduce for correctness, beat the GPU column
— target the 701/launch overhead + kernel efficiency). **Recommend MERGE** — benchmark tooling
+ docs only, no source change. Next conversation: v2 scoping.

## 2026-06-11 — Minifil-ON turnover on device — packRange cast fixed; GPU-resident contractile network with turnover (campaign closer)

Branch `minifil-turnover-packrange-fix` (off main `47fa1c8` = Part-2 turnover residency).
Files: `boxOfActin/GPUMoveThing.java`, `Thing.java`, `BoxOfActin.java`. Driver
`scripts/minifil_turnover_val.sh`; A/B `scripts/prefix_ab.sh`; log
`RUN_LOGS/2026-06-11_minifil_turnover_packrange_fix.txt`; frames
`RUN_LOGS/_minifil_turnover/tv/`. **The terminal gate of the GPU residency campaign is closed:
the full contractile network — 100 actin filaments + 100 minifilaments + active turnover —
runs GPU-resident to completion, behavior-neutral vs the CPU oracle.**

### The crash and the exact desync mechanism
`GPUMoveThing.packRange`/`packDynamicRange` read the cached `brownianRule[slot]` and the live
occupant `theThings[gpuThingIndices[slot]]`, and the `RULE_FIL` branch casts `(FilSegment) t`.
The historical crash: a slot cached `RULE_FIL` whose live occupant is a `MyoMiniFilament`
→ `ClassCastException`. Root cause is a **`rules[]` / `theThings[]` desync** — the cached slot
maps (built by `classifyThings`) describe an older `theThings[]` layout than the pack reads.
The ONLY mutation that moves a different-typed Thing into an existing slot's index without an
append is `removeThing`'s swap-compaction; the 2026-06-09 fix (`Thing.removeThing` → `markTopologyDirty`
on `swapId != lastId`) already forces a `classifyThings` refresh before the next pack, and Phase A
(`1d7dcc3`) added the `RULE_MINIFIL` pack branches. **On HEAD both are present, so the cast does
not reproduce** — `boa10-64Seg-dyn` seeds 1 & 2 ran 5000 steps clean before any change. (The
prompt's line numbers ~4758/~4859 predate Phase A; the journal's "residual blocker" note was carried
forward from the pre-fix state and was stale — boa10-64Seg-dyn was never re-tested on the merged HEAD,
Part-2 deliberately used the `-nomini` variant to sidestep it.)

### Reproduction (proving the mechanism on demand)
Added a fault-injection flag `BOA_DISABLE_TOPODIRTY_ON_SWAP=1` (`Thing.java`, default OFF) that
skips the swap `markTopologyDirty` — deliberately reintroducing the staleness. On
`boa10-64Seg-dyn` seed 1 (guard ON): **538 slot/rule desyncs over 5000 steps**
(`[PACK_DESYNC] step=395 slot=9606 cachedRule=3 liveType=FilSegment`), `packRuleDesync=538`,
**rc=0, no crash** — every desync caught and self-healed. The desync is bidirectional; the
historical `ClassCastException` is the `(cachedRule=RULE_FIL, live=MyoMiniFilament)` subset.

### The fix — type-consistent pack dispatch
`reconcilePackRule(slot, t, cachedRule)`: dispatch on the **live occupant type** (`ruleForGpuThing`),
not the cached rule. When live ≠ cached: count it (`packRuleDesyncCount` → `[STATS] packRuleDesync=N`),
log the first occurrence, and `markTopologyDirty()` to rebuild slot maps + joint lists next step.
Both pack functions route their per-slot rule through it. Because the `FilSegment` cast now runs
**only when the live type is FilSegment**, a stale cached rule can NEVER throw — the crash class is
closed structurally, independent of the topologyDirty signal. **In production (signal ON) the guard
never fires (`packRuleDesync=0` across all 10 validation runs); under fault injection it absorbs all
538 events.** Chose this over "guarantee refresh ordering" / "segregate minifil slots" because it is
local, cheap (one `instanceof` chain per slot), and makes the pack robust to ANY future desync source,
not just removeThing — a true belt-and-suspenders over the 2026-06-09 primary fix.

**Force-coverage exactly-once: preserved.** `reconcilePackRule` only selects the per-slot Brownian
scale and makes the cast safe; it touches no force-sum / joint-apply / kernel code. Each slot's
force/pose still packs from `soaForce[thingIdx]` via the same `gpuThingIndices[slot]` (which co-varies
with `brownianRule[slot]` — both written as a pair in `classifyThings`, so the index points at the live
occupant whose force we pack; only the rule *label* could lag, and that is exactly what we reconcile).
**Minifil Brownian corrected:** a `RULE_MINIFIL` slot gets `sMiniFilBrownian` (= `myoMiniFilBrownianScale`,
Phase A); reconciliation makes that robust to a stale rule too. Minifil bodies hold span ~180 nm
throughout the 10k run (frame0 180.0 → frame200 181.4 nm) → cohesion + body Brownian on device work.

### Validation — campaign integration test (`boa10-64Seg-dyn`, 3 seeds + 10k long, GPU-resident vs full-CPU oracle)
**10/10 GPU runs: `cast=0`, `NaN=0`, `packRuleDesync=0`, `poseDelta overflow=0`, `planRebuild=1`.**
- **segCt** (final): GPU 542/564/530 (mean 545) vs CPU 568/535/567 (mean 557) — overlapping both
  directions, neutral. **len_nm**: GPU ~197±42 vs CPU ~196±43 — identical.
- **Residency cadence held with minifils present**: `demandSyncPose calls=151 @5k (=50 biochem + 101
  frames)`, `=301 @10k (=100 biochem + 201 frames)` — exactly the Part-2 pull-retirement cadence,
  NOT per-step. poseDelta `max=194 ≪ cap 8192`, overflow=0. Long run segCt 197→578, len 196.3±43.9.
- **meanBoundMotors**: GPU 1.86/0.85/1.74 vs CPU 6.93/2.26/2.00 — noisy on BOTH paths (CPU itself
  spans 2.0–6.9; seed3 GPU 1.74 ≈ CPU 2.00); GPU trends lower = the **pre-existing GPU-minifil-binding
  seam** (flagged 2026-06-09, "systematically lower, stable across seeds, NOT this fix"). My change is
  provably neutral here: `packRuleDesync=0` makes `reconcilePackRule` a pure pass-through, so the pack
  computes byte-identically to pre-fix. **Pre-fix A/B confirms (seed 1):** pre-fix GPU
  meanBoundMotors 1.728 / segCt 541 ≈ post-fix 1.864 / 542 (within run-to-run noise) — the fix
  changes nothing, and pre-fix GPU is already far below CPU (the seam predates this work).

### Residual (separate, flagged — NOT this fix)
On `boa10-64Seg-dyn` (`slotCap=19602`) one **inert** kernel in the chained graph emits a per-step
`cuLaunchKernel -> Returned: 701` (LAUNCH_OUT_OF_RESOURCES) — 5011 @5k, 10011 @10k. **Benign**:
results are oracle-neutral, minifils hold span, no NaN, rc=0; the move/cohesion/bind/biochem pipeline
demonstrably works. It is newly EXPOSED now that minifil-ON turnover reaches the GPU at this scale (the
cast crash previously masked it). Small configs show 701=0. This is a kernel block-size/register-tuning
item at scale (connects to the "scale is the v2 lever" note), independent of the cast fix. **Diagnostic
hooks left as residency tooling (default OFF): `BOA_DISABLE_TOPODIRTY_ON_SWAP`, `BOA_PACK_RAW_CAST`.**
**Recommend MERGE** — the campaign closer; the 701-at-scale is the next item, and it is a scale lever.

## 2026-06-11 — Float32 verdict (cohesion body-reaction) + residency under active turnover

Two independent goals. Part 1 settles WHY the cohesion 1-step lag is needed (for the v2 record);
Part 2 extends the residency flip to biochem turnover.

### Part 1 — Is the cohesion body-reaction instability a genuine float32 effect? (diagnostic)

Branch `float32-stability-diag`. Added `BOA_COH_SYNC=1` (off by default): applies the device body
reaction SYNCHRONOUSLY (this step's `accB*`, **SET** not RMW) instead of the production 1-step
device-resident lag — a clean float32-synchronous-SET datapoint with no RMW confound, directly
comparable to the CPU float64-synchronous-SET oracle. Swept (N=`numMyoDimersEachEndOfMiniFil`,
fm=`myoMiniFilFracMove`, dt) on `boa10-singleMiniFil`; metric = body-axis wander / span / NaN
(`RUN_LOGS/_cohesion/analyze_minifil.py`). Full log + table:
`RUN_LOGS/2026-06-11_float32_cohesion_stability_sweep.txt`.

**Confound ruled out first.** The journal's "synchronous diverges 190°→NaN" does NOT reproduce with a
clean SET: at the production point (fm=0.07, N=8) float32-synchronous-SET is STABLE (wander 22.7° ≈
oracle 20.4°, span 180.0). The 190° came from the **RMW confound** (body slot accumulated across
steps), exactly as the prompt flagged. So at production the lag is NOT load-bearing — synchronous
float32 works.

**Threshold tracks the fm·N stiff-coupling product** (= a stiff explicit-integration instability,
not precision):
| scheme | divergence threshold (fm·N) |
|---|---|
| float32-sync | ~1.5 (N8 fm≈0.19 ; N16 fm≈0.09) |
| float64-sync (CPU oracle) | ~1.9 (N8 fm≈0.23 ; N16 fm≈0.12) |
| float32-**lag** (production) | **>32** (stable to fm=4.0, never broke) |

The **lag extends the stiff-stability margin >20×** and is stable far past where BOTH synchronous
schemes (float32 AND float64) diverge → its stabilization is a **scheme (staggered/semi-implicit-like)
effect that float64 needs too**, NOT a float32 compensation.

**Genuine but narrow float32 margin-narrowing.** float32-sync destabilizes at product ~1.5 vs
float64 ~1.9 (~20% smaller margin), confirmed at fm=0.20 N=8 (float32-sync DIVERGED wander 10222°
[rotational; span held], float64-sync STABLE 21.4°; seed-marginal — s2 held, s1/s3 diverged). Caveat:
the device also differs in Brownian RNG / integration, so ~20% is an UPPER BOUND on the pure-precision
contribution. This sits at 2.5–4× production stiffness, nowhere near the operating point.

**Verdict:** the production-fix *rationale* ("float32 tips synchronous over at fm=0.07, lag
load-bearing for precision reasons") is a **MISATTRIBUTION** — production synchronous is float32-stable
(the divergence the fix responded to was the RMW confound), and the lag's real power is a stiff-scheme
effect. A genuine float32 stiff-stability effect *exists* but is narrow and irrelevant at production.
**V2 implication:** keep a deliberate lag (or semi-implicit / softer fracMove) for the stiff cohesion
constraint as a STIFF-STABILITY measure; f64 on that one term is unnecessary and would not remove the
lag's need at high stiffness. Production keeps the lag unchanged. `BOA_COH_SYNC` left as residency
diagnostic tooling — HOLD (cherry-pick to main if wanted).

### Part 2 — Residency under active biochem turnover (LANDED)

Branch `turnover-residency` (off main). **Problem:** for biochem-ACTIVE (`noMonomersSimd:false`)
turnover configs the per-step pose pull could not be retired, because the per-instance biochem counter
(`FilSegment.biochemCheckCt`) wraps on scattered steps (segments created at split/nucleate get random
phases) → some segment's relative-write `incCoord`/split runs on essentially every step, and each needs
a fresh host coord baseline (a stale baseline clobbers the device-resident pose on the next scatter).

**Fix — phase-align biochem to a GLOBAL cadence.** `GPUMoveThing.biochemGlobalCadence` (default-on for
`-gpu`; `BOA_BIOCHEM_GLOBAL_CADENCE=1/0` forces on/off) + `advanceBiochemCadence()` (called once/step in
`doLoop` before the move phase) sets `biochemFiresThisStep` every `Thing.biochemCheckInt` steps.
`FilSegment.biochemStep` gates its mutation on that flag instead of the per-instance counter, so ALL
segments mutate on the SAME step — concentrating the relative writes onto 1-in-K steps. The per-step
pose pull is then gated `(!biochemGlobalCadence || biochemFiresThisStep)` → pull fires at
**biochem-cadence + output-cadence only** (output frames refresh independently via
`refreshHostMirrorsForOutput`). Rate is preserved (still one biochem pass per K steps per segment) —
only the PHASE is synchronized, so the stochastic turnover is statistically unchanged.

**Cadence (boa10-64Seg-dyn-nomini, full turnover: poly/depoly + cofilin sever + kRdmNuc nucleate +
split):** demandSyncPose **2011→61 at 2000 steps (=20 biochem + 41 frames)**, **5011→151 at 5000 steps
(=50 biochem + 101 frames)** — exactly Nsteps/biochemCheckInt + frames, NOT Nsteps. ~33× fewer pulls
(1.96s→0.02s at 2000 steps).

**Validation (3 seeds, 2000 steps): GPU-resident vs CPU-global vs CPU-per-instance oracle.**
Statistically NEUTRAL — final segCt means **547 / 546 / 571** (within the ~13% seed-to-seed spread;
ranges overlap), final length **195±40 nm** all three. **10/10 runs (incl. the 5000-step production
length) NaN-free**, planRebuild=1 (no per-step rebuilds — topology marks → `classifyThings` only),
poseDelta overflow=0 (max=196 ≪ cap 8192, even with all mutations phase-concentrated).
`RUN_LOGS/2026-06-11_turnover_residency_validation.txt`.

**Force-coverage exactly-once: trivially preserved** — the change touches NO force/kernel/force-sum
code (verified `git diff`: only cadence-gating + pull scheduling). Biochem mutations are unchanged
(same `incCoord`/`markPoseDirty`), only phase-shifted.

**Topology coverage:** split (`markPoseDirty`+`markTopologyDirty`), cofilin sever / depoly-to-zero
(`cleanup()`→`removeThing` swap, sets `topologyDirty`) all fire INSIDE `biochemStep` = biochem-fire
steps, so host pose is fresh when they read it. Nucleation (`kRdmNuc`, every step) creates
absolute-coord segments (`theBox.rdmPtInside()`) with no dependence on existing host pose — handled by
`buildDeltaSet`'s slot-change scan regardless of pull cadence.

**Residual blocker (next step):** minifil-ON turnover configs (`boa10-64Seg-dyn`,
`boa10-miniFil-turnover`) still hit the PRE-EXISTING `GPUMoveThing.packRange`
MyoMiniFilament→FilSegment cast crash when a swap-compaction lands a minifil in a slot the move-pack
casts as FilSegment — orthogonal to this work, but it is the last gate before GPU-resident contractile-
NETWORK turnover (minifilaments + actin turnover together). **Recommend MERGE Part 2.**

## 2026-06-12 — Residency flip: device body-gather + complete consumer retirement — FLIP LANDED

Branch `minifil-cohesion-device`. Commits: `53facdf` (body reaction on device) + the obstacle-2
commit (audit + pull retirement). Logs: `RUN_LOGS/_cohesion/{retire_*,retireAB_*}.log`,
`RUN_LOGS/2026-06-12_pose_consumer_audit.txt`. Driver `scripts/run_cohesion_ab.sh`.
**Both success states reached — the flip landed on the contractility/minifilament path.**

### Obstacle 1 — body reaction on device (host bridge retired)
`dimerCohesionKernel` restructured to **one thread per minifilament BODY**: each body thread loops
its dimers, RMW-ing each dimer's UNIQUE rod1/rod2/lever1/lever2 slots (race-free — a Myosin belongs
to exactly one dimer→one body) and writing the body's jointForce/Torque slot **exactly once**
(single writer). This sidesteps the failed "second per-body gather kernel" entirely and retires the
host bridge (per-dimer `cohBodyForce/Torque` scratch + EVERY_EXECUTION host readback + `packCohesion`
host gather). `cohBodyForce/Torque` deleted; per-body CSR `cohBodyDimStart/cohBodyDimCount` added.

**The real obstacle was NOT the second kernel — it is float32 stability.** Applying the body
reaction SYNCHRONOUSLY (same execute) is unstable: the body couples to all N dimers at once, so its
same-step response feeds back into every dimer force and the explicit stiff loop diverges (body
wander ~190° vs oracle ~21°, growing 0.3→3.3°/frame; NaN once the resident lag buffer was added
naively). This is **independent of the acos fix** (below) and refutes the brief's premise that the
host-bridge lag was mere over-damping: **the 1-step lag is load-bearing for stability.** The full-CPU
oracle is stable synchronously only because float64 sits just inside the stability margin; float32
tips it. Resolution: carry the body reaction with a **1-step lag in a device-resident buffer**
(`cohBodyReact`, FIRST_EXECUTION) — the kernel applies the previous step's value and stores the
current one, all on device, NO host round-trip. The body slot is **SET (overwrite)**, not RMW: the
cohesion kernel is its sole writer and the slot is not reliably host-zeroed, so += accumulated the
reaction across steps (verified: body force grew as react[N-1]+react[N-2]+… → divergence).

**Convention fix:** the kernel used Newton-refined `accurateAcos` while the CPU cohesion
(`moveCoeff`, `applyRodCoupling*`, `constrainEnd*`, lever torque) uses the raw `Pt3D.fastAcos`. Added
`fastAcosDev` (bit-equivalent to fastAcos — raw `sqrt(2(1±x))` near ±1, accurateAcos in mid-range)
and used it throughout the cohesion kernel. This fixed the step-1 torque match (1.371e-22 vs CPU
1.370e-22, was 1.378e-22) but did NOT fix the instability — confirming the two are separate.

**Force exactly-once:** body SET by the sole body thread; rod/lever RMW on slots unique per dimer;
CPU cohesion gated off (`cohesionOnDevice`, `cpuCohesionApplyCt=0` style). The 1-step lag applies
each computed reaction exactly once (deferred one step) — the `taForce`/anchor exactly-once class is
preserved.

**Validation** (`boa10-singleMiniFil` 3 seeds + `contractilityAssay_gpu_short`, devcoh / `BOA_MINIFIL_COHESION_CPU`
control / full-CPU oracle): span 180.0 nm, no NaN/crazy. Body **translational jitter now MATCHES the
oracle** (~1.7 nm vs 1.5; the host bridge over-damped it to 0.4 — the improvement the brief expected).
Body wander tracks the oracle within the large seed-to-seed envelope (seed3: devcoh 132° / oracle 80°
— both high, a thermal realization; seeds 1-2: 20-22° ≈ oracle 18-19°). Contractility tension ~1.5 pN
and boundMotors ~5 neutral vs oracle ~1.8 / ~5 (within the documented ~1.8× noise). Needs
`-Dtornado.tvm.maxbytecodesize=16384`.

### Obstacle 2 — complete consumer enumeration + per-step pull RETIRED
**Exhaustive instrumented audit** (`BOA_POSE_AUDIT`): a counter + first-non-Thing/Pt3D-caller capture
on EVERY host-pose accessor — the `getCoordX/UVecX/YVecX` getters, the `getEnd*/ZVec/TransXTox`
derived getters, the bulk `recomputeDerivedSoA` (reads soaCoord directly, bypassing getters — the
gap the prior reasoning-only audits missed), and the `Pt3D` body-frame transforms (`xToX/XTox`).
Armed for 3 mid-run non-output steps, dumped in `updateCounters` (excludes output frames). This is
the exhaustive enumeration the two prior audits lacked.

**Complete per-step host-pose consumer map on `contractilityAssay_gpu`** — exactly TWO roots:
1. `GPUMoveThing.refreshMiniFilBodyDerived` (1 read/step) — Phase-A recompute of the body's host
   derived fields to feed the CPU cohesion's `xToX`. With cohesion on device that CPU path is gated
   off; the `xToX` instrumentation **never fired**, proving `updateMyosinDimerPositions` does not run
   per-step → the body derived has NO per-step consumer. **DEAD. Gated off** (kept only under the
   `DIAG_COHESION_CPU` A/B control). Output writers refresh independently via
   `refreshHostMirrorsForOutput`.
2. The **anchor pin** (`applyBenchmarkPins`, 6 reads + `FilSegment.initialize` re-init, 26 reads) —
   the contractility harness snapping the 2 pinned plus-ends back to their fixed anchors. **Assay-
   harness only** (absent in production minifilament configs).

After gating (1), the audit shows the anchor pin as the SOLE remaining consumer (32 reads, all
`applyBenchmarkPins`+`initialize`).

**The pull was RETIRED** (`retirePosePull()`, on by default for `contractilityAssay`; `BOA_RETIRE_POSE_PULL`
env for other biochem-inactive configs). The anchor pin **tolerates stale pose**: it snaps each
segment toward a FIXED anchor point and the anchors are quasi-static, so a stale read still lands the
end on target. Biochem turnover is zeroed in the assay, so the poly/depoly `incCoord` baseline that
keeps the pull on for biochem-ACTIVE noMonomersSimd:false configs (boxSpaghetti) does not apply.

**Result — `demandSyncPose calls 10203 → 102`** (output cadence only), **9.14 s → 0.06 s** (~0.9 ms/step
saved over the 10k-step run). Behavior-neutral: anchors held **<0.5 nm** (vs ~1.0 nm with the pull on
— tighter, no drift), tension/boundMotors within run-to-run noise vs the full pull and the oracle, no
NaN. 3-seed A/B (`contractilityAssay_gpu_short`): tension pullon 1.64/1.85/1.83 vs pulloff
1.93/1.88/1.79 pN (means 1.74 vs 1.79, within noise); boundMotors 5.3-5.6 vs 5.5-6.8. Default-on
confirmation (no env, `retirePosePull()` via `contractilityAssay`): demandSyncPose calls=102,
tension 1.78/1.82, no NaN. Non-contractility minifil unaffected: `boa10-singleMiniFil` keeps the
pull (calls=374), span 180.0, wander 22.7≈oracle 22.1, jitter 1.70≈1.51, no NaN (the
refreshMiniFilBodyDerived gate is neutral there too).

**Net: the residency flip landed on the contractility/minifilament path** — body reaction fully on
device (host bridge gone), per-step pose pull retired, behavior-neutral, with the measured wall-clock
payoff. The `BOA_POSE_AUDIT` instrumentation (zero-overhead, final-boolean gated) is left in as
residency tooling. `BOA_MINIFIL_COHESION_CPU=1` left as the A/B control.

**Recommend MERGE** — the device body-gather landed clean (single-writer per-body, no host bridge),
not a fallback to the bridge; the pull retirement is validated behavior-neutral with a measured win.

## 2026-06-11 — Minifilament cohesion onto device + per-step pose pull (audit) — biochem-path residency flip

Branch `minifil-cohesion-device` (off main `a2956e9`). Commit: `ed957a3` (Step 1). Logs:
`RUN_LOGS/2026-06-11_cohesion_device_step0_survey.txt` (force-coverage table),
`_step1.txt`, `_step2_audit_BAIL.txt`. Driver `scripts/run_cohesion_ab.sh`,
analyzer `RUN_LOGS/_cohesion/analyze_minifil.py`. **For the planner.**

### Step 0 — force-coverage survey (no code change)
The cohesion set is three pools across two waves: `MyoMiniFilament.constrainEnd{1,2}Dimers`
(myoJoints2, body↔rod on myo1.rod), `MyosinDimer.applyRodCoupling*` (myoJoints1, rod↔rod), and
`alignUVecLeversTorque` (myoJoints1, lever↔lever, gated on `!(both heads onFil)`). Key findings:
`parallel` is **always true** (antiparallel, `alignYVecLeversTorque`, `keepMyosinsOnSurface` are
DEAD); the body attach offset has **y=z=0** so `attachPt = body.coord + offsetX·body.uVec` (no
yVec/zVec rebuild). All state dependence packs as flags (parallel, bothOnFil, end1/end2) read at
pack time, not pose → expressible as a per-(dimer) kernel, **no bail**. Full force-coverage table
(every force/torque, source, target slot, sign) in the survey log.

### Step 1 — cohesion onto device (committed `ed957a3`), behavior-neutral
`dimerCohesionKernel` (one thread per packed parallel GPU-handled dimer) reads resident pose
(fresh ends = coord ∓ ½·len·uVec), drags from the resident `bTransGam/bRotGam`, RMW the **unique**
rod1/rod2/lever1/lever2 slots (race-free). `MyoRod.moveCoeff` replicated as `moveCoeffDev`.
`packCohesion` packs only parallel + fully-GPU-handled dimers; `MyosinDimer.cohesionOnDevice()`
gates the CPU cohesion off for exactly that set (`cpuCohesionApplyCt=0` confirms exactly-once).
`BOA_MINIFIL_COHESION_CPU=1` is the A/B control. **GPU runs need `-Dtornado.tvm.maxbytecodesize=16384`**
(the cohesion tasks push the chained graph past the 4096 default → BufferOverflow at compile).

**Two bugs found during validation:**
1. **linkUVec sign flip.** `Pt3D.unitVec(pt1,pt2) = (pt1−pt2)/|.|`, so CPU's `unitVec(rod2End,rod1End)`
   points rod1→rod2 (attractive). The kernel computed `(p1−p2)/dist` = the OPPOSITE → repulsive
   springs → runaway, NaN by frame ~9. Negated `u` in all three force blocks → span locks 180 nm.
2. **Body-reaction gather won't run as a 2nd device kernel.** A per-body gather (sum the 16 dimers'
   −F/−τ into the body slot, mirroring `segMotorForceKernel`) never executed in the chained graph —
   even an unconditional top-of-kernel sentinel write to `jointForceSum[0]` never landed (bs=0, N and
   the CSR all valid host-side; not a double-apply; per-dimer −F/−τ match CPU exactly). Root cause not
   isolated (likely a chained-graph writer/scheduling limit on `jointForceSum`'s Nth writer). **Worked
   around with a host bridge**: the dimer kernel writes per-dimer −F/−τ to `cohBodyForce/cohBodyTorque`
   (transferred to host EVERY_EXECUTION), and `packCohesion` sums them into the body's
   jointForce/Torque slot for the NEXT execute (1-step lagged; negligible for the quasi-static body).

**Neutral A/B** (devcoh / cpucoh=`BOA_MINIFIL_COHESION_CPU=1` / oracle=full CPU):
- `boa10-singleMiniFil`, seeds 1–3: span 180.0±0.03 nm all arms; body wander devcoh 16–18° ≈ oracle
  18–21° ≈ cpucoh 20–22°; no NaN/crazy. Body **translational** jitter devcoh 0.42 nm vs CPU ~1.5 nm —
  mildly over-damped by the bridge's 1-step lag (sub-nm; the rotational/held DOF matches).
- `contractilityAssay_gpu_short`, seeds 1–2: mean tension devcoh 1.58 vs oracle 1.85 vs cpucoh 1.75
  (within the documented ~1.8× run-to-run noise); boundMotors 5.67 vs 6.08 vs 5.35. No NaN.
Behavior-neutral on the science observables (span, rotational wander, tension, boundMotors).

### Step 2 — retire per-step pose pull: feasibility audit → BAIL (no flip)
`demandSyncPoseToHost` still fires 1/step (`calls=10203`, gated on `noMonomersSimd:false`). After
Step 1 the cohesion FORCE no longer reads host pose, but cohesion is **NOT** the last per-step
host-pose consumer (refuting the prior session's Step-3 audit, which predated this port):
1. `updateMyosinDimerPositions()` (myoJoints2) reads the body's host pose via `xToX` to recompute the
   world attach points — now **dead** (the device kernel computes attach internally; the gated-off CPU
   `constrainEnd*` consumed them). + `refreshMiniFilBodyDerived` (Phase A) feeds its `transXTox`. Both
   are a quick gateable follow-on (skip when `cohesionOnDevice`).
2. The contractility **anchor pin** (`applyBenchmarkPins`) reads host `getEnd1/2` each step — assay
   harness, but a genuine per-step host-pose read on this config.
3. Biochem flag-gate (zeroed turnover here, but the pull is keyed on the flag).
Plus the Step-1 host bridge's per-step `cohBodyForce/Torque` readback (a force, not pose — doesn't
block the flip, but is not residency-clean). **Did not flip.** To retire the pull: gate (1) off, move
the anchor-pin read off-host or flip on a pin-free production minifil config, and the biochem
event-cadence sync + a working device-side body gather remain the open threads.

## 2026-06-11 — Dimer-motor cross-bridge force onto device + ckRelease lag fix — biochem-path residency step 2

Branch `dimer-motor-force-device` (off main; base `0c115af`, has `84e188c` brownianDeltaT-removed +
Phase A). Commit: `97bf945` (Step 1). Logs:
`RUN_LOGS/2026-06-11_dimer_motor_device_step{0_thetaLM,1,2_BAIL,3_audit}*.txt`.
Driver `scripts/run_step1_ab.sh`. **For the planner.**

### The port (why it's bounded)
On the dimer-motor path (contractility, minifilaments — any non-`MyosinFixed` motor) the device
already had the cross-bridge kernel (`motorForceKernel` motor-slot + `segMotorForceKernel` seg-slot
reaction, F8 spring / F9 uVec torque / F10 yVec torque from the **resident** pose). It was gated to
MyosinFixed-only by a single pack line (`boundSegSlot[mj]=-1` for non-MyosinFixed) + the matching
`MyoFilLink.gpuMotorHandled` short-circuit. The CPU `MyoFilLink.addForces`/`updatePos` computed
F8/F9/F10 CPU-side from the demand-synced host pose for dimer motors, forcing a per-step pose
consumer. The port is the two-gate flip — **not a new kernel** (the kernel always read pure
geometry).

### Step 0 — θ_LM re-measure (CLOSED)
`boa10-singleMiniFil`, 300 steps / 63 frames, θ_LM=angle(lever.uVec,motor.uVec) mean over the
minifilament's ~32 myosins, steady = last-20-frame mean.

| | θ_LM steady | θ_RL steady |
|---|---|---|
| GPU | 18.2° | 90.3° |
| CPU | 24.8° | 89.3° |

Prior (Phase B1, on the √10-buggy base): GPU ~16–19°, **CPU ~68–82°**. CPU θ_LM collapsed 67–82 →
24.8, converging toward GPU 18.2. The √10 Brownian over-forcing shifted the *mean* of the soft
anharmonic lever-motor joint (angle clamp + cocked/uncocked flips), not just its variance — exactly
a mean divergence. θ_RL (stiff joint) matched on both bases. Residual 6.6° is a small
float32/integration-timing mean offset (consistent with the documented `meanMotSegDot`-identical
finding). **No surviving CPU/GPU internal-joint seam → proceed.**

### Step 1 — dimer cross-bridge force onto device (committed `97bf945`)
Two-gate flip, force exactly-once:
- `GPUMoveThing.packMotorBinding()`: dropped `if (myo instanceof MyosinFixed)` → `if (myo != null)`.
  Any bound motor whose seg has a valid GPU move-slot now gets `boundSegSlot`/`posOnSeg` + the CSR
  seg-reaction entry.
- `MyoFilLink.gpuMotorHandled()`: dropped the `!(myo instanceof MyosinFixed)) return false`
  short-circuit (kept the null/removeMe/`gpuHandled` guards, which mirror the pack gate). CPU pair
  no-ops for these motors.
- Force-coverage: the shared `motorForceKernel`(+F,+τ motor slot) and `segMotorForceKernel`(−F,
  reaction seg slot) cover every admitted motor; the CPU `addForces`/`alignUVec`/`alignYVec` are
  skipped. ckRelease for them defers to the bridge (Step 2, already present). **Applied exactly once,
  never dropped/doubled.** `BOA_DIAG_CPU_MOTOR=1` remains the A/B control.

Behavior-neutral A/B — `contractilityAssay_gpu_short` (dimer minifilament motors, **0** MyosinFixed),
3 seeds. devmotor = device (new); cpumotor = `BOA_DIAG_CPU_MOTOR=1` (CPU cross-bridge on GPU pose);
oracle = full CPU (ground truth):

| arm | boundMotors | meanBoundMotors | tensionA | tensionB | bindEvents |
|---|---|---|---|---|---|
| devmotor | 5.41±0.08 | 4.83±0.46 | 1.84±0.12 | 1.67±0.20 | 947±122 |
| cpumotor | 5.54±0.07 | 5.31±0.38 | 2.38±0.44 | 2.43±0.30 | 1765±416 |
| oracle | 4.53±1.25 | 4.07±1.79 | 1.45±0.48 | 1.34±0.93 | 876±412 |

The device path matches the **CPU oracle** within run-to-run noise on every metric, including
bindEvents (947 vs 876). Instantaneous boundMotors == the control (5.41 vs 5.54) confirms
force-exactly-once. The single-seed "devmotor bindEvents < cpumotor" concern dissolved with the
ensemble: cpumotor (1765±416) is the high-variance outlier (its float32-pose+float64-CPU-force
hybrid over-churns binding — no production path uses it); devmotor tracks the oracle. Tension ramps
physically 0 → ~1.9 pN, both anchors contractile. No NaN/crazy on any of the 9 runs.

### Step 2 — ckRelease-after-moveThings reorder: ALREADY LANDED (no-op, NO commit, bail)
The reorder RELEASE_LAG_DIAGNOSIS.md recommends is already on main: `844ecc9` ("reconcile device
ckRelease force-read with CPU path", 2026-06-04) deferred `ckRelease()` from `MyoFilLink.step()` into
`bridgeMotorForceWriteback()` (end of `moveThings(N)`, after the EVERY_EXECUTION `motorWriteback`
transfer), so the device step-N release reads the step-N writeback. `844ecc9` is an ancestor of HEAD;
`git blame` traces both the bridge `ckRelease` (`GPUMoveThing.java:4723`) and the `step()` placement
(`MyoFilLink.java:141`) to it; no later commit reverted it. Its own Phase-3 check: 17260/17260
bit-exact step-N match, 0/17026 vs step-(N-1) — the lag is gone. It applies **automatically** to the
Step-1 dimer motors (the bridge iterates every `boundSegSlot>=0` motor uniformly), and the Step-1
ensemble confirms the device release rate already tracks the oracle (bindEvents 947 ≈ 876 — a
residual −29% lag would put it systematically below). RELEASE_LAG_DIAGNOSIS.md's device-arm
"move-(N-1) → 1-step lag" row is **stale** w.r.t. the current code (the doc was written against the
pre-844ecc9 `ckRelease`-in-`step()` state, the same day). Nothing to implement → hard-bail per the
discovery convention; no commit.

### Step 3 — remaining per-step pose-consumer audit (no change)
`demandSyncPoseToHost` is gated on `!noMonomersSimd.isActive()` (on for contractility). `[STATS]
demandSyncPose calls=10203` IDENTICAL pre/post Step 1 — it is ONE structural per-step pull keyed on
the flag, not per-consumer; Step 1 removes a *consumer*, not a call. Per-step host-pose consumers on
`contractilityAssay_gpu` after Step 1:
- **(1) Cohesion** — the deferred Phase B. `MyoMiniFilament.constrainEnd1/2Dimers` (myoJoints2; body
  ↔rod spring + alignment, reads body `xToX`/`transXTox` + rod `freshEnd1/2AsPt3D`) and
  `MyosinDimer.jointConstraints`→`applyRodCoupling*`/`alignUVecLeversTorque` (myoJoints1; the two
  rods/levers). `refreshMiniFilBodyDerived()` (Phase A) feeds cohesion's `xToX` and rides with it.
  **The production physical consumer.**
- (2) Anchor pin `applyBenchmarkPins` — reads host `getEnd1/2` each step; contractility-ASSAY
  harness only (absent in production minifilament configs).
- (3) Biochem `incCoord`/`splitSegment` — gated on `!noMonomersSimd` so the pull stays on, but ALL
  turnover rates are zeroed here → never fires (a real biochem-active config WOULD make it a
  consumer; GPU_STRATEGY lever #2).
- **Removed by Step 1:** the motor cross-bridge `MyoFilLink.addForces`/`updatePos` — the
  bind-application blocker the Phase A writeup named as blocking Phase C.

**What blocks the flip now:** cohesion is the SOLE remaining per-step production-physics host-pose
consumer. So **cohesion→device (Phase B) is the last port before the per-step pull can be retired on
the minifilament/contractility path — and it now GAINS the residency payoff** it lacked when first
deferred (Phase A deferred B because its only benefit, residency, was blocked at Phase C by
bind-application; Step 1 removed that block). Did NOT flip the pull (audit only). `BOA_DIAG_CPU_MOTOR=1`
left as the A/B control.

## 2026-06-11 — Minifilament Brownian parity + body→device port (Phase A); spin premise refuted

Branch `minifil-body-cohesion-device` (off main). Commits: `84e188c` (remove brownianDeltaT),
`71db5b0` (body Brownian scale), `1d7dcc3` (Phase A body→device). Data:
`RUN_LOGS/2026-06-11_minifil_brownian_parity.txt`. Viewer: `~/Code/threejs_output/minifil_*`.
**Not merged — for the planner.**

### The task and the pivot
The session began as the scoped "MyoMiniFilament body + cohesion onto device — kill the spin"
port (Phases 0/A/B/C). **Phase 0 refuted the premise**: the Path-B Phase-2a "GPU minifilament
spin" does not reproduce as a real seam. The `spin_metric.py` cloud coherence over a 300-step run
has only ~62 transitions and the 64 myosins of one minifilament are ~1 effective DOF (all chase
the body), so the metric's statistical noise floor is ~1/√62 ≈ 0.13 — comparable to the signal.
- 5-seed ensembles: GPU 0.207 / CPU 0.140 / body-off 0.158 — all overlap (per-run 0.003–0.382).
- 10× longer (3000 steps, 603 frames, floor ~0.04): GPU 0.028–0.036 == CPU 0.021–0.044, random-
  sign net rotations (diffusive). A real ballistic spin would hold coherence as N grows; this
  decays as 1/√N. In the *same* first-63-frame window CPU (0.249) is *more* "spun" than GPU (0.104).
- Phase-2a's own body-axis numbers already showed CPU 536° ≈ GPU 555° (the body wanders the same
  on both — it was CPU-integrated on both). No GPU-specific rotational excess exists.
So the spin-collapse rationale for the port is void. The port still has *residency* value, which is
why jba directed proceeding with A/B/C anyway.

### The real CPU-vs-GPU difference jba saw in frames: a √10 Brownian bug (FIXED)
With the body thermal equalized, residual myosin rod-center jitter was CPU 17.4 vs GPU 5.5 nm/frame
= 3.16× = √10 = √(deltaT/brownianDeltaT). Root cause: the GPU move kernel scales Brownian by
`sqrt(2kT/deltaT)` (`GPUMoveThing.java:5227`, deltaT=1e-4) while CPU `calcRandomForces` scales by
`1/brownianDeltaT` (`Thing.java:1051`, brownianDeltaT=1e-5) with integration at deltaT — an
FDT-breaking mismatch; the CPU diffused ~10× too fast in variance. GPU was correct; CPU over-forced.
Per jba (brownianDeltaT was always meant to equal deltaT; residual), **removed `brownianDeltaT`
entirely** — Brownian always uses `deltaT`, `brownianApplyInt`=1 (it was every-step already since
`(int)(1e-5/1e-4)=0` and `counter>=0`). No-op where they already matched (gliding & contractility_gpu
1e-5/1e-5; Lp configs ran 1e-5/1e-5); corrects only the deltaT=1e-4 minifil configs. Validated:
CPU rod step 17.4→**5.46 nm = GPU 5.51**. Stale `brownianDeltaT:` lines in old param files are
non-fatal (logged "misunderstanding") — left in place per jba.

### Minifilament BODY Brownian scale (jba directive)
`Env.myoMiniFilBrownianScale` (default **0.1**, runtime-mutable; `myoMiniFilBrownianMotionOff` /
`BOA_MINIFIL_BROWNIAN_OFF` still hard-off). Applied in `MyoMiniFilament.moveThing()` as
`bForceSum.inc(scale, randForces)`. Body is `cpuFallback`→applies on both paths. Body-axis wander
522° (full) → 21° (1/10 of corrected free-body), CPU≈GPU.

### Phase A — MyoMiniFilament body device-integrated (`RULE_MINIFIL`) — LANDED (`1d7dcc3`)
The body was the one `cpuFallback` Thing in a minifilament whose rods were GPU-handled (the
float64-CPU vs float32-GPU two-integrator split Phase-2a hypothesized as the spin cause). Moved it
onto the device move/derived kernel path:
- `RULE_MINIFIL` classification in `classifyThings` (was `else→cpuFallback`).
- Body Brownian on device: `packRange`/`packDynamicRange` set tScale=rScale=`myoMiniFilBrownianScale`
  (0 if off) — same free-body drag formula as the rods, so device body Brownian == the CPU formula
  it replaces.
- `refreshMiniFilBodyDerived()`: after the per-step `demandSyncPoseToHost`, recompute the body's
  host `transXTox`/`end1`/`end2` from the synced pose (the CPU cohesion's `xToX` attachment
  placement reads cached `soaTransXTox[body]`; the body's CPU `moveThing`/`initialize` used to
  refresh it). Idempotent re-orthog from the device's orthonormal pose; few bodies.
The body's CPU `step()` (F7 boundary) is inert here (no Bug); its CPU `moveThing` no longer runs.
Cohesion stays on CPU (it reads fresh host pose, demand-synced). The cohesion reaction (−F/−τ,
gathered into `soaForceSum[body]`) now integrates on the SAME float32 device integrator as the
rods' +F/+τ. **Validated** (boa10-singleMiniFil, seeds 1–2, vs CPU): no NaN/crazy; span locked
180.0 nm over 300- and 3000-step runs; jitter GPU 5.50 ≈ CPU 5.46 nm; body wander GPU 22.2 ≈
CPU 20.7°; COM drift GPU 4.1 vs CPU 7.3 nm (s1), comparable paths.

### Phase B (cohesion→device) — DEFERRED (jba's call); design + rationale for the planner
**Decision:** defer. With the spin rationale refuted, Phase A already delivered the only real
correctness goal (matched-precision integration of the cohesion action/reaction). Moving the
cohesion *computation* to the device buys nothing physical (the stored force is float32 either
way) and its only benefit — residency — is blocked at Phase C regardless (below). It is the
high-risk force-coverage class (the recurring taForce/anchor hazard) with zero wall-clock payoff
pre-flip. Not worth the risk now; the planner should re-prioritize.

**Design if/when done.** Per-step pose consumers on this config (the things that would need to move):
(1) `MyoMiniFilament.constrainEnd1/2Dimers` (myoJoints2) — body↔rod spring + alignment torque,
reads body pose (attachment pts via `xToX`) + rod pose; (2) `MyosinDimer.jointConstraints`
(myoJoints1) — `alignUVecLeversTorque` + `applyRodCoupling{End1,End2,End1End2,End2End1}`, reads
the two rods/levers (not the body). A device cohesion kernel would insert into the chained
TaskGraph BEFORE `move` (after joints/chain), RMW into `jointForceSum`/`jointTorqueSum` on the rod
and body slots. Per (dimer,end) it needs: rod move-slot, body move-slot, the FIXED body-frame
attachment offset (`myoDimerPtsEnd{1,2}Inx`, 3 floats), an end flag (end1 uses −uVec / uVecR),
and drags. **Note:** the normal (non-diag) graph's post-move `derived` task is only
`yVecOrthoKernel` — device `transXTox` is NOT resident — so the kernel must rebuild the body frame
(uVec, yVec′, zVec) from `uVec`/`yVec` per body to transform the offset. Validate against CPU via
a new `BOA_MINIFIL_COHESION_CPU=1` A/B (stability, span, jitter, wander, meanBoundMotors —
force-coverage exactly-once).

### Phase C (retire per-step `demandSyncPoseToHost`) — BLOCKED (reported)
Blocked by **bind-application**: `MyoFilLink.addForces`/`updatePos` read fresh host motor+filament
pose every step (dimer motors are CPU-handled — JOURNAL 2026-06-10). This remains even if all of
Phase B is done, so Phase B cannot enable the residency flip this session; binding must move first
("next session", out of scope). Documented so the planner can sequence binding before re-attempting
B/C.

## 2026-06-10 — GPU contractility: dimer binding + tension readout + anchor pin (merged)

Branch `gpu-fresh-tip-on-assay` (off the assay commit `17adff9`) → **merged to main**. Three files:
`MyoFilLink.java` (+27/−5), `GPUMoveThing.java` (+34), `BoxOfActin.java` (+29). No diagnostic code.
Full detail + run logs: `RUN_LOGS/2026-06-10_gpu_dimer_bind_rootcause.txt`,
`RUN_LOGS/2026-06-10_gpu_dimer_bind_freshtip_fix.txt`.

Three independent GPU-residency seams kept the assay from working on `-gpu`, each isolated by
measuring against the CPU oracle (identical seed/config, only `-gpu` differs):

1. **Binding never held (fresh-tip cross-bridge force).** The device bind decision
   (`GPUMotorBinding.bindKernelResident` / `drainBoundResults`) has **no `MyosinFixed` gate** — it
   offers and binds dimer (plain-`Myosin`) motors fine (devHits/ontoFired prove it), refuting the
   prior session's "device never binds dimers (c)" conclusion. The real failure: dimer motors are
   `gpuMotorHandled()==false`, so their cross-bridge force is CPU-computed in `MyoFilLink.addForces`,
   which read `motorPt` = the **stale `bindTip` host mirror** (only refreshed in `initialize()`,
   gated off per-step for GPU-handled motors). Measured: stale spring distance 40–80 nm vs a fresh
   3–25 nm → forceMag 22–82 pN >> the 12 pN break threshold → **every** bind force-released the next
   step (`relBreak==ontoFired` exactly, `relNorm=0`); motors piled at ADPPi (off-fil ADPPi→ADP rate
   is 0, so the cycle stalls without a held bind — also why the viewer's "red ADP head" was only a
   brief early-transient). Fix: derive the head tip fresh from the demand-synced coord+uVec
   (`= freshEnd2AsPt3D`) and use it for the distance, force direction, and torque application point;
   `updatePos` uses `freshEnd1AsPt3D` for the filament attach point. CPU bit-identical
   (`freshEnd==end` when coord/uVec are fresh). → GPU boundMotors mean 5.40 vs CPU 5.03.

2. **Tension always read 0 (anchor-force gather).** The readout projects the host `soaForceSum` at
   the pinned anchor, but the anchor's reaction is the device chain force (F3/F4) in `jointForceSum`,
   never gathered to host. Fix: add `jointForceSum` to the chained graph's EVERY_EXECUTION
   transferToHost when `contractilityAssay` is active, plus `GPUMoveThing.readDeviceJointForce(thingNumber)`;
   `captureContractilityTension` adds the device joint force to the anchor force before projecting.
   1-step lagged (read before the next `moveThings`), negligible for the quasi-static plateau.

3. **Plus-ends drifted / tension low (anchor pin not propagated to device).** `applyBenchmarkPins`
   snaps the anchor back with an in-place `incCoord` on the same Thing/slot — invisible to
   `buildDeltaSet`'s slot-change scan, exactly the case the biochem path documents and handles with
   an explicit `markPoseDirty`. The pin lacked that call, so the correction hit host `soaCoord` and
   was discarded; the device kept integrating the anchor unpinned. Measured drift: plus-end x
   1.8999 → 1.547 µm (0.35 µm). Without a held anchor the contraction translates the filaments
   inward instead of building isometric strain → low reaction. Fix: `markPoseDirty(p.seg)` after the
   snap-back, GPU-gated. → anchor held to <0.4 nm; plateau tension 0.185 → **3.27 pN**.

**Net:** GPU contractility now matches CPU on binding (5.40 vs 5.03) and develops contractile
tension in the CPU regime (both anchors positive, anchors held). [**Update 2026-06-11**: the
apparent ~3.3-vs-1.8 pN gap was REFUTED as a real difference — it was the CPU run under-converged in
the 10k window (true CPU plateau 2.06 pN, reached ~step 15k) + GPU's earlier binding onset + ~1.8×
run-to-run noise; per-motor force generation matches and head alignment is identical, so it is NOT a
θ_LM seam. True GPU/CPU steady-state tensions agree ~2.0–2.1 pN. See the top entry and
`RUN_LOGS/2026-06-11_gpu_cpu_tension_difference.txt`.] Viewer:
`~/Code/threejs_output/contractility_gpu_pinfix` (plus-ends stay pinned).

## 2026-06-10 — Minimal contractility assay (anti-parallel filaments, isometric tension)

Branch `contractility-assay` (off `main`). Validation logs:
`RUN_LOGS/2026-06-10_contractility-run.txt` (main), `-noMotor.txt`, `-reversed.txt`,
`-bench-regression.txt`. Viewer dir `RUN_LOGS/3js_contractility_v2` (101 frames).
**Not merged — hold for jba.**

### What it is

A sarcomere-like isometric tension probe. Long narrow box (4×0.3×0.2 µm). Two
anti-parallel, Y-offset (±0.05 µm), overlapping stiff filaments along X; one bipolar
minifilament centered in the 0.76 µm overlap. Each filament's plus (barbed) end is
hard-pinned to the outer wall (filament A's at +X, B's at −X); minus ends overlap in
the center. Myosin walks plus-ward → pulls both anchors inward = contraction. The
measurement is the axial reaction force at each pinned anchor.

### Polarity (the #1 confirmation)

`end2 = coord + ½L·uVec` is the **plus/barbed end** — confirmed three ways:
`MyoFilLink.addForces` (`forceDotFil = Dot(F,uVec) > 0` moves a motor toward the plus
end, +uVec), the frame writer's `isBarbedEnd` (emitted when `end2Fil == null`), and the
gliding-assay convention. So filament uVec points toward its plus end; normal polarity
sets both plus ends outward. Verified empirically by the **sign**: normal → positive
(inward) at both anchors; reversed `uVec` → negative (extension).

### Build = reuse + 4 additions

**Reused**: `new FilSegment(coord,uVec,filID,monCt,fromFile)` (wrapped in a new
`FilSegment.makeStraightChain(n, outerPt, buildDir, uVec, brownOff)` that lays a linked
chain from a placed outer point, link direction chosen from `sign(buildDir·uVec)` so it
works at either polarity); `new MyoMiniFilament(coord,uVec)`; `Chamber.makeABox`; the
`applyBenchmarkPins` hard-pin (post-`moveThing` `incCoord` snap-back).

**Added** (all CPU; GPU path untouched):
1. `BoxOfActin.makeContractilityAssay()` — builds the two chains + minifilament, forces
   `noMonomersSimd` (rigid rods), `brownianOff` on the filaments for a clean readout,
   logs overlap geometry and warns if overlap < minifilament span (the bail check).
2. **Generalized pin registry** — `Pin{seg,whichEnd,anchor}` + `pinRegistry`;
   `applyBenchmarkPins` iterates it; the deflection benchmark now registers its two pins
   (firstSeg.end1, lastSeg.end2) into the same registry; doLoop gate changed from
   `if (benchmarkFilament)` to `if (!pinRegistry.isEmpty())`. **Regression: -bmDiag ratio
   0.998, intact.**
3. **Tension readout** — after the force gather, before the pin snaps the endpoint back,
   read the anchor segment's `soaForceSum` and project onto the inward `buildDir`
   (positive = contractile). Anchors are inset 0.10 µm from the walls so the box-boundary
   force never contaminates the readout. Emitted to `[STATS] contractility` lines and a
   frame `contractility` block (tensions + anchor points). `MyoMiniFilament.countBoundMotors()`
   added for the `boundMotors` column.
4. Config flags `contractilityAssay` / `contractNoMotor` / `contractReversePolarity`
   (Env). Bumped `Parameter.theParams` 240→256 (the 5 new params overflowed it).

### Result + controls (50k steps, dt=1e-5, CPU)

| run | tensionA / tensionB (pN) | boundMotors |
|---|---|---|
| **main** | rises 0 → ~2.0 / ~2.0 (plateau) | 0 → 8 |
| no-motor control | ~0.0013 / ~0.0013 | 0 |
| reversed polarity | **−0.4 / −0.5** (extension) | 1–3 |

Tension rises from a ~0.28 pN settling transient to a ~2 pN contractile plateau as the
minifilament engages; both anchors positive (inward) and roughly equal. No-motor → ~0
confirms the signal is myosin-generated. Reversed polarity flips the sign to extension,
confirming the geometry. Stiffness: phalloidin regime `fracMove=0.0573, fracR=1.0,
fracMoveTorq=0.01`.

### Run notes / open

- **Run command** needs `-Xmx4G` (MyoMotor's static SoA arrays are ~640 MB, allocated on
  first motor construction) and the tornado-api jar on the classpath even for CPU:
  `java --enable-preview -Xmx4G -cp ".:libs/*:$TDIR/tornado-api-4.0.1-dev.jar" BoxOfActin
  -r -pf ParameterFiles/contractilityAssay -3js <dir>`. (`Myosin.jointConstraints`
  link-references `WorkerGrid`.)
- **GPU comparison (same seed 12345, CPU vs `-gpu`)**: CPU minifilament stays intact (span
  0.18 µm throughout), tension plateaus ~2 pN. `-gpu` **blows the minifilament apart within
  ~20 ms** (end1 → 75 µm by t=0.02 s → ~1e17 µm) and tension reads a **flat 0.0000** the whole
  run (boundMotors=0). Two compounding GPU-only failures: (1) MyoMiniFilament is a CPU-fallback
  object whose internal myosin joints don't hold against the GPU-resident neighbour pose (the
  pre-existing "single-minifilament cohesion BLOWS APART on GPU" seam); (2) the readout projects
  host `soaForceSum`, which isn't synced per-step while `noMonomersSimd` is active, so it would
  read ~0 even without the explosion. Confirms **CPU-only**; added a `[CONTRACT][WARN]` startup
  guard under `-gpu`. Frames: `~/Code/threejs_output/contractility_{cpu,gpu}`.
- **GPU follow-up after merging the cohesion stack (`gpu-phaseB2a-fresh-cohesion` → main `2f489bb`).**
  The minifilament blow-apart is **fixed**: re-running the assay on `-gpu` with the merged
  output-readonly + fresh-geometry fixes, the minifilament **holds** — span locked at 0.1800 µm,
  zero NaN, clean exit (102 frames, `~/Code/threejs_output/contractility_gpu_fixed_short`), where
  pre-fix it exploded to ~1e17 µm by t=0.02 s. **Two changes were needed for the assay to benefit:**
  (1) stop forcing `noMonomersSimd:true` in `makeContractilityAssay()` — under `noMonomersSimd` the
  per-step `demandSyncPoseToHost` is gated off, so the fix's fresh-pose reads stay stale and it
  blows up anyway; the GPU config (`ParameterFiles/contractilityAssay_gpu`) uses
  `noMonomersSimd:false` (turnover still off via zeroed rates → filaments stay static); (2) the
  `[CONTRACT][WARN]` guard now fires only on the bad combo `useGPU && noMonomersSimd`. **Remaining
  GPU seam (separate, not this assay):** motor **binding** is still CPU-only — GPU `boundMotors=0`
  vs CPU 4–9, so no contractile tension develops on GPU (`tension=0`). The device binding kernel is
  scoped to gliding `MyosinFixed`; minifilament (dimer) motor binding is unported (the pre-existing
  GPU-minifilament-binding-fidelity seam, JOURNAL 2026-06-09). So GPU now gives a **stable, viewable
  minifilament** but the contractile **measurement still requires CPU**. Also: GPU is ~33 steps/s
  for this tiny system (kernel-launch-bound) — far slower than CPU; the full-length GPU run hit the
  600 s wall-timeout (its `libcuda` SIGSEGV was the kill-teardown, not a physics fault — the short
  run exits cleanly). NOTE: GPU pose-sync gating means the host `forceSum` tension readout also
  would not work on `-gpu` regardless of binding.
- Reversed-polarity control sustains fewer bound motors than normal (less favorable
  binding geometry); the **sign** is the decisive observable there, not the magnitude.
- Tension is read as the full `forceSum` axial projection, not the `end{1,2}AxialF`
  accumulators (those also pick up boundary forces on CPU; the wall-inset + the clean
  `forceSum` projection sidesteps that). Dedicated state in `BoxOfActin.ContractAssay`,
  not overloaded onto `FilSegment` fields.

## 2026-06-10 — Cross-pool taForce race fix (serialize multi-pool force phases)

Branch `cpu-taforce-race-fix` (off `main`). Full data + reproduce lines:
`RUN_LOGS/2026-06-10_taforce_race_fix.txt`. **Not merged — hold for jba's visual
confirmation of the dimer + minifilament.**

### Root cause (confirmed via same-seed serialize A/B)

`incForceSum` accumulates into a per-thread row `taForce[tid]` (plus
`dirtyCounts[tid]`/`dirtyIndices[tid]`), where `tid` is each worker's **local**
index within its own ThreadSet (`ThreadSpawn(i)` sets `tlsThreadId=i` once at
startup), and `taForce` has only `allThreadCt`(=16) rows — the design assumes one
pool writes forces at a time. But `startAllThreadSets` releases every pool for a
wave via the **non-blocking** `spawn()`, so a wave with 2+ force-writing pools runs
them concurrently → worker-0 of each pool both mutate `taForce[0]`/`dirtyCounts[0]`/
`dirtyIndices[0]` → lost `+=` updates and corrupted dirty bookkeeping → some
restoring forces never reach `soaForceSum`. The lost fraction makes displacement
ratchet out instead of being corrected → **progressive, directional, non-diffusive
dissolution** (the head/neck stretch jba observed). Forces *are* cleared every step
(`clearSoaForcesTorques`), so it is **not** cross-step accumulation. SoA-inc
migration regression (pre-migration `incForceSum` wrote the shared sum under
synchronization; the per-thread accumulator that replaced it assumed unique tids).

The config ladder, and the **methodological lesson**:

| config | force pools in joint phase | result |
|---|---|---|
| single myosin | 1 (Myosin) | holds ✅ |
| dimer | 2 (Myosin + MyosinDimer) | one myosin dissolves |
| minifilament | 3+ (+ MyoMiniFilament …) | falls apart |
| gliding | 1 | works ✅ |

**Single-pool tests are blind to this bug class.** My earlier "forces are applied,
no regression" verdict (the `cpu-constraint-force-application-verify` branch) was
measured on a *single myosin* — exactly the config that can't expose a cross-pool
race. The plumbing trace was correct; the concurrency was the gap.

### Completeness audit (every dispatch site; force-writing pools per wave value)

Wave-constant aliasing found: `actAStart == xLinkStart == 6`.

| wave (value) | force-writing pools | #pools | action |
|---|---|---|---|
| xLink/actA (6) | FilLink + Arp23 + ActA | 3 | **serialize** |
| myoJoints1 (7) | Myosin + MyosinDimer | 2 | **serialize** |
| myoJoints2 (8) | ProteinNode + MyoMiniFilament + ChamberMyo + ChamberMyoD | 4 | **serialize** |
| membraneLinks (14) | NodeLink | 1 | none |
| membraneMove (15) | StickyNode | 1 | none |
| meshColl (3) / motColl (4) | ckMesh / ckMots | 1 each | none |
| bForces (5), mesh-binning, bind (16), step/move/gather/biochem/resetCt | single pool | 1/0 | none |

**Corrections to the brief's tentative list:** (1) membrane is **not** a race —
`NodeLink` (wave 14) and `StickyNode` (wave 15) are on *different* waves, one pool
each; (2) `myoJoints2` is a **4-pool** collision (the two Crucible chamber-myo pools
join ProteinNode + MyoMiniFilament), not 3; (3) `ActA` has its own wave constant
that **aliases** onto `xLinkStart`, so the xLink dispatch does release all three.
Single-pool waves are race-free (a pool's own workers have distinct tids 0..15);
collision force writes (`FilSegment.incForceSum` from ckMesh/ckMots) are single-pool
per wave → unaffected.

### The fix (BoxOfActin.java only)

`runForceWave(start,stop)` — default path serializes the wave's pools via
`startAndWaitEachThreadSet` (`divideAndConquer`+`regroup` per active pool, in order),
so two pools never write `taForce` at once. **Within-pool parallelism is kept** —
each pool still fans out its 16 workers; only the cross-pool overlap is removed.
The xLink / myoJoints1 / myoJoints2 dispatch pairs now call `runForceWave`.
`BOA_CONCURRENT_FORCES=1` restores the legacy concurrent dispatch (A/B + rollback).
On the per-machine cost argument (16 logical threads): 2–4 concurrent force pools =
32–64 workers oversubscribing 16 HW threads is contention, not parallelism, so
sequential 16-worker passes do the same work with better cache/scheduling — the
"lost overlap" is mostly illusory, confirmed by the step-time check. Also added
`BOA_SINGLE_DIMER=1` (builds a single free `MyosinDimer`) as the 2-pool fixture.
No physics/param/strength/GPU changes.

### Validation (all CPU; viewer dirs under `~/Code/threejs_output`)

1. **Dimer** (seed 1, 1/10 thermal): worst joint gap over run **0.0021 µm fix
   (serialized, default) vs 0.1724 µm racy** — 82×. `cpu_dimer_FIX_default` vs
   `cpu_dimer_FIX_concurrent`. Fix holds; escape hatch reproduces the bug.
2. **Minifilament low thermal** (`myoBrownianAttn=0.1`): worst gap 0.011 µm, span
   0.254→0.245 µm (stable). `cpu_minifil_FIX_lowthermal`.
3. **Minifilament normal thermal**: worst gap 0.061 µm, span 0.267→0.248 µm
   (coheres). `cpu_minifil_FIX_normal`.
4. **Gliding** (full val, 10k steps): steady-half filament XY speed — default median
   7.94, default#2 median 8.86 (run-to-run noise floor), concurrent median 7.90.
   Cross-mode Δ = 0.04 µm/s vs same-mode run-to-run Δ = 0.92 µm/s → velocity/
   throughput **unaffected**. Sim is inherently nondeterministic (multi-thread FP
   gather order: default-vs-default frames differ), and gliding is single-pool in
   all force waves so serialization is a literal no-op there. `glide_full_*`.
5. **Step-time / no-regression**: 100 minifilaments (3200 myosins), 2000 steps, no
   -3js — **7s vs 7s** (fix vs concurrent). Holding: worst joint gap 0.078 µm over
   403 frames (bounded, no dissolution). `mf100_FIX_default`.

### Open / notes

- Residual minifilament jitter under normal thermal (~26–78 nm) is the genuine
  cohesion-strength-vs-thermal balance (jba's tuning call), separate from this race.
- The earlier `cpu-constraint-force-application-verify` branch (FE force-vs-effect
  instrumentation, free-myosin gate) stays as investigation history; this branch is
  the clean fix off main.

### Follow-on — structure ladder extended (static actin, protein nodes) + node rendering

Same branch. Walked the post-fix "does it hold" ladder onto the remaining structures
and added node visualization.

- **Minifilaments + static actin field** (`boa10-miniFil-staticActin`: 50 minifilaments
  / 1600 myosins + 100 static actin filaments 0.5–2 µm, `noMonomersSimd:true` + all
  turnover off; dt=1e-4, ~9.6 min, 301 frames). Actin field genuinely static (total
  ~136 µm conserved; segment count re-meshes to the 0.185 µm filSeg size and stabilizes
  at ~748). Minifilaments **hold** against the actin field: worst myosin joint gap
  0.074 µm. `minifil_staticActin_FIX`.
- **Protein nodes carry myosins** (`singleNode_myosins`: one spherical node, `nodeRadius`
  0.1 µm, `numNodeMyos`/`numNodeMyoDimers`; dt=1e-5, `myoBrownianAttn=0.1`). 8 singlets +
  4 dimers = 16 myosins all stay glued to the sphere (mean tail radius = node radius 0.1,
  spread flat ~0.197 µm = sphere diameter), internal joints tight to ~0.4 nm, cluster
  coherent over 101 frames. Confirms node-attached **singlets and dimers** work —
  `keepMyosinsOnSurface`/`keepMyosinDimersOnSurface` are purely mechanical (force-only),
  and `ProteinNode` is exactly one of the 4 pools in the now-serialized myoJoints2
  (MyosinDimer in myoJoints1). `singleNode_myo_dimers`.
- **Node sphere rendering** (commit `924a9d9`): `ThreeJSWriter.buildFrameJson` now emits a
  `nodes` array (`{id, center, r}`); the viewer draws them as grey, ~35%-opacity instanced
  spheres (depthWrite off, HUD node count). Before this the node was invisible (only the
  surface myosins showed).
- **Thermal caveat**: the node-example config dialed down the myosin *parts*
  (`myoBrownianAttn=0.1`) but **not the node body** — `nodeBrownianMotionOff` is binary
  (no "small but nonzero" scale), so the node body diffused at full thermal (~50 nm
  center drift). Same body-thermal-scale gap flagged for the minifilament body
  ([[production-run-config-prefs]] — a `*BrownianAttn`-style body scale knob is the fix,
  not yet added). Attachment held regardless.

## 2026-06-10 — Path B Phase 2a — fresh-geometry cohesion (kill the spin)

Branch `gpu-phaseB2a-fresh-cohesion` (off `gpu-phaseB1-internal-joints`, which carries
the `-3js` readonly + packRange fixes Phase 1 needs but that aren't yet on main). Full
data: `RUN_LOGS/2026-06-10_phaseB2a_fresh_cohesion_spin.txt`.

### The fix (in-scope, correct)

Forces **C** (`MyoMiniFilament.constrainEnd1/2Dimers`) and **D** (`MyosinDimer.applyRod-
Coupling*`) read `myoRod.end1/end2AsPt3D()` == `soaEnd1/End2`. On the GPU path those
derived fields are refreshed only at output cadence (and the `-3js` readonly fix restores
them to pre-output values) → **frozen per-step**, while `soaCoord/soaUVec` are demand-
synced fresh every step (`demandSyncPoseToHost`, gated `!noMonomersSimd`). Replaced with
ends derived on-the-fly from fresh pose: `Thing.freshEnd1/2AsPt3D()` = `coord ∓ ½·length·
uVec` — the same formula `recomputeDerivedSoA`/`fillSoaArrays` use. **No new transfer, no
per-step derived-SoA recompute** (the residency retreat we avoided). On CPU `coord/uVec`
are fresh and `soaEnd1 == coord−half·uVec`, so `freshEnd == end1AsPt3D` bit-for-bit — zero
CPU behavioral change. (Force **B** `keepMyosinsOnSurface` also switched, but inactive here:
`numMyosinHeads==0`; the 32 myosins are dimer-organised → only C and D apply. **E**
`alignUVecLeversTorque` already read fresh uVec.)

Confirmed before coding: loop order is myoJoints1/2 (cohesion) → gatherForces → GPU
pack+integrate, so the rod's +F **is** packed to device before integration — both halves
of each action/reaction pair are applied (no mistimed-pack bug).

### Result — spin reduced, NOT eliminated

Spin metric (`scripts/spin_metric.py`): signed rotation of each myosin's rod center about
the minifilament axis, frame-to-frame; cloud coherence = |Σ mean Δθ|/Σ|mean Δθ| (1 =
ballistic spin, 0 = diffusive). 63 frames (~300 steps), seeds 7 & 11. Pre-fix built from
parent in-tree via `git stash` for matched before/after.

| config | net rotation | cloud coherence |
|---|---|---|
| PRE-FIX GPU s7 | +148.8° | **0.662** |
| POST-FIX GPU s7 | +90.7° | **0.273** |
| CPU s7 | +7.5° | 0.031 |
| PRE-FIX GPU s11 | +95.5° | **0.670** |
| POST-FIX GPU s11 | −61.8° | **0.455** |
| CPU s11 | −20.5° | 0.069 |

(On-disk survey frame `_s7` scores 0.675 with this metric ≈ survey's reported 0.65 →
calibrated.) The fresh-geometry fix removes ~40–60% of the per-frame rotational bias but
leaves a residual coherent rotation (~1.5°/frame) well above CPU's diffusive floor.

### Why the residual is a separate seam (flagged, not fixed)

- **Not stale geometry.** Post-fix GPU geometry at the cohesion phase is now as fresh as
  CPU (both read end-of-previous-step pose). The per-step derived-SoA recompute would
  produce the *same* `coord±½·length·uVec` ends `freshEnd` already computes → it cannot
  reduce the residual. So no hard-bail: the recompute is not the answer.
- **Not COM drift.** Bundle-COM net <13 nm on GPU (s7 5.6, s11 12.7), *tighter* than CPU
  (44.8 / 31.8 nm). The C-force action/reaction split (+F device rod, −F CPU minifil body)
  does **not** produce significant translational drift here.
- **It is rotational integrator-split / float32.** The +force/+torque on the float32
  device-integrated rod and the −force/−torque on the float64 CPU-integrated minifil body
  don't cancel exactly in rotation → small systematic tangential bias → slow coherent spin.
  A rotational generalisation of the two-integrator seam (survey clue 2). **Flagged for jba.**

### θ_LM check — Phase-1 hypothesis refuted

θ_RL (rod-lever) matches CPU (~85° vs 88–90°). θ_LM (lever-motor) does **not**: GPU ~32°
vs CPU ~67°, and the fix did **not** move it (pre 32.6° → post 31.9°). So θ_LM divergence
is **not** stale-end sensitive — refuting the Phase-1 conjecture that the cohesion's
per-step-stale geometry perturbs the soft motor. It's an independent internal lever-motor /
float32 seam (force A on-device + motor-head state). **Flagged, separate from Phase 2a.**

### No regression / open

- Gliding has no minifilaments (`noMonomersSimd:true`, `initialMyoMiniFils:false`) → the
  cohesion functions never run → provably unaffected. [gliding band check appended to RUN_LOG]
- Internal joints (Phase 1) hold; packRange + `-3js` readonly fixes hold (carried on parent).
- **Open / for jba**: (1) residual rotational integrator-split spin (0.27–0.46 vs CPU
  0.03–0.07); (2) θ_LM internal seam; (3) cohesion *strength* — CPU disperses even with zero
  actin (jba's tuning/model call, untouched here).
- **Not merged — hold for jba's visual confirmation** of the frames.

### Follow-on — body thermal noise off: the residual spin is body-thermal-EXCITED

jba's read of the frames: "GPU minifilament doesn't look bad except the body flopping
around." The minifilament body is its own rigid `Thing` (cpuFallback on the GPU path) and
receives its OWN Brownian forces/torques in `MyoMiniFilament.moveThing()` unless
`Env.myoMiniFilBrownianMotionOff`. Added env-var gate **`BOA_MINIFIL_BROWNIAN_OFF=1`** (in
`begin()`) that suppresses ONLY the body's thermal forcing — rods/levers/motors keep their
GPU-kernel Brownian. GPU singleMiniFil, seeds 7/11:

| metric | GPU body-ON | GPU body-**OFF** | CPU (body-on) |
|---|---|---|---|
| spin cloud coherence s7 | 0.273 | **0.026** | 0.031 |
| spin cloud coherence s11 | 0.455 | **0.079** | 0.069 |
| body-axis total turn s7 | 555° | **46°** | 536° |
| body-axis total turn s11 | 567° | **14°** | 543° |
| body-COM path s7 | 631 nm | **109 nm** | 690 nm |

Turning off the body's thermal noise (a) stops the body flopping (axis wander ~12–40×
less) AND (b) collapses the residual rod spin to CPU levels. So **the residual coherent
spin is body-thermal-EXCITED, not a constant float bias**: the body tumbles on the CPU
integrator (float64), the GPU-resident rods chase its moving attach points on the device
integrator (float32), and the chase carries a small directional bias that accumulates into
coherent rotation *only when there are large body excursions to chase*. Refines the residual
diagnosis from "rotational integrator-split/float32 seam" to "**body-thermal-excited
integrator-split coupling**" — same two-integrator seam, now with a clean off-switch.
This is a DIAGNOSTIC suppression (a real body should feel thermal forces), not a physics
fix. Frames `~/Code/threejs_output/phaseB2a/post_gpu_nobody_s{7,11}`.

## 2026-06-10 — Path B Phase 1 — internal myosin joints on-device (premise refuted; no code change)

Branch `gpu-phaseB1-internal-joints` (off the `-3js` readonly fix). Scoped task:
make the device joints kernel apply the internal rod→lever→motor joints for
non-`MyosinFixed` (minifilament) myosins, on the premise that they are applied by
**neither** the device kernel (assumed `MyosinFixed`-only) nor the CPU
(short-circuited on the GPU path). **Investigation refuted the premise — the
internal joints are already on-device. No code change made.**

### Step 1 — the scope gate: there isn't one (for internal joints)

- `classifyThings` (`GPUMoveThing.java:3985-3996`): **all** `MyoMotor`/`MyoRod`/
  `MyoLever` are GPU-handled regardless of owner subclass (only gate is
  `Env.myosinsOff`).
- Joint-list build (`4032-4061`): admits **every** Myosin whose 3 sub-parts are
  GPU-handled — **no `instanceof MyosinFixed` check**.
- `jointsKernel` (`854-1190`): computes all four internal apply* methods
  (`applyLeverMotorJoint{Force,Torque}`, `applyRodLeverJoint{Force,Torque}`) for
  every `m ∈ [0, myoJointCt)`. Only the **anchor spring** (`anchoredFlags` gate)
  and the **motor cross-bridge force** (`packMotorBinding:4534`) are
  `MyosinFixed`-only. The all-minifilament dyn run shows `jointPack=0.117s` over
  510 calls ⇒ `myoJointCt>0` ⇒ the 3200 minifilament myosins are in the list and
  processed. My earlier "kernel is MyosinFixed-only" diagnosis conflated the WIP
  comment's *validation* scope with a *code* gate; there is no code gate.

**Step 2 (extend kernel to non-MyosinFixed) is a no-op** — already done.

### Step 3 — validation (GPU vs CPU, seed 11, `-3js` frames)

θ_RL = ∠(rod.uVec, lever.uVec); θ_LM = ∠(lever.uVec, motor.uVec). Data:
`RUN_LOGS/2026-06-10_phaseB1_internal_joints_investigation.txt`.

| config | θ_RL GPU→ / CPU→ | θ_LM GPU→ / CPU→ |
|---|---|---|
| 100MiniFil-noactin (zero binding) | 84.7° / 86.8° ✓ | 19° / 82° ✗ |
| singleMiniFil (compact both, span~0.5) | 89.9° / 98.7° ✓ | 16° / 68° ✗ |

- **θ_RL matches CPU (~90°)** and internal gaps stay tight/bounded (~0.009 µm) —
  the internal joints ARE computed and integrated on-device for non-`MyosinFixed`.
  If they weren't, θ_RL would not match and the free rod/lever/motor would
  disperse under Brownian. They don't.
- **θ_LM (lever-motor) diverges** (GPU ~16° vs CPU ~68°) **even on a compact
  single minifilament** (matched bundle span ~0.5 µm) — so it is **not** a
  bundle-density / dispersal artifact. Nucleotide states match (both ~95% ADPPi).
  `isCocked()==!isADPPi()` ⇒ rest angle 0° at ADPPi; GPU relaxes toward 0° (16°),
  CPU sits ~68°. The lever-motor torque **formula, `jointParams[0-12]`, `maxMag`
  clamp (`motorLen==getDim==0.020`), and `cockedFlags` (from `isCocked`) are all
  faithful to CPU**. So θ_LM is not a missing/mis-scoped internal joint — it is a
  **downstream sensitivity**: most likely the Phase-2 CPU cohesion forces
  (`MyoMiniFilament.constrainEnd*Dimers`, `MyosinDimer.alignUVecLeversTorque`),
  which read host-side derived geometry **not refreshed per-step on the GPU path**,
  perturbing the soft motor head differently than CPU. θ_RL (stiff, constant 96°
  rest) is robust to this; θ_LM (soft) is not.

### Outcome — pause-and-document

No kernel change: the scoped Step 2 is already in place, and the θ_RL match proves
the internal-joint kernel is faithful for non-`MyosinFixed`. The residual θ_LM
divergence is a separate correctness item whose root cause needs the deterministic
single-myosin instrument (`SingleMyoDiag` dumping lever/motor torque vs CPU on a
frozen pose) and most plausibly belongs with **Phase 2** (cohesion / per-step host
derived geometry). **Recommendation: skip the (redundant) Phase-1 kernel change and
proceed to Phase 2; revisit θ_LM once the cohesion forces read fresh geometry.**

Frames for visual confirmation (GPU vs CPU side-by-side): `~/Code/threejs_output/
boa_phaseB1_{gpu,cpu}_singleMiniFil` (63 ea), `…_{gpu,cpu}_dyn` (21 ea). On the
single minifilament, GPU myosins stay intact rod→lever→motor units in a compact
bundle (span 0.36→0.46) — the bundle does not disintegrate at this scale.

## 2026-06-10 — `-3js` output-sync read-only fix (GPU minifilament blow-apart)

Branch `gpu-3js-output-readonly` (off main). Makes the `-3js` / Simularium /
inspect output path **read-only with respect to simulation state**, fixing the
GPU minifilament "blow-apart" without retreating to the CPU-fallback hybrid.
Minifilament myosins stay **GPU-resident**. **Not merged** — frames staged for
jba to view first.

### The write that was found

`GPUMoveThing.refreshHostMirrorsForOutput()` (called by every frame/inspect
writer) does two things: `demandSyncPoseToHost()` (device→host pose, read-only
on the device) **and** `Thing.recomputeDerivedSoA(0, tc)`, which **overwrites
the host derived arrays** `soaYVec` (re-orthonormalised), `soaZVec`, `soaEnd1`,
`soaEnd2`, `soaTransXTox`, plus the `FilSegment.end1Pt/end2Pt/xRange…` and
`MyoMotor.bindTip` Pt3D mirrors. Those same host arrays are read **the next
step** by the CPU minifilament coupling that runs unconditionally on the GPU
path — `MyosinDimer.alignUVecLeversTorque`, `applyRodCoupling*` (via
`end1/end2AsPt3D` → `soaEnd1/2`), and `MyoMiniFilament.constrainEnd*Dimers`. The
host recompute (float32, host orthonormalisation) differs slightly from the
device-integrated frame the no-output path leaves resident; without output those
derived mirrors are simply **stale-but-consistent** (P6/P7/P8 retired per-step),
and the run is stable. With output they jump stale→fresh at each frame, and the
**stiff alignment torque** (`/deltaT`) amplifies the discontinuity into the
NaN cascade tallied at `alignUVecLeversTorque` + `constrainEnd*Dimers`
(JOURNAL 2026-06-09 investigation). The corruption is **host-only** — the
delta-scatter to device is driven by an explicit `pendingDirty` set (biochem
poly/split), which dimer rods/levers never join, so the device pose is never
touched by output.

### The host-only change

`refreshHostMirrorsForOutput()` now snapshots the physics-owned host arrays +
Pt3D mirrors **before** the recompute (`beginOutputSnapshot`, idempotent within
an output episode), the writers read the fresh values, and
`GPUMoveThing.endOutputRender()` — called once at the safe point in
`doLoop()` right after `logAndDraw()/remoteLog()` — **restores them
bit-identically**. The device's resident pose is read, never written. Gated on
the same `!noMonomersSimd` predicate as the per-step pose pull: gliding has no
CPU minifilament coupling reading these arrays and already emits frames stably,
so it keeps its current path untouched (zero gliding risk by construction).

### Equivalence result (`boa10-64Seg-dyn-short`, GPU, 500 steps)

Paired same-seed runs (`-seed N`, with vs without `-3js`). **Turning on output
no longer changes the outcome:**

| seed | no-`-3js` (bindEvents / meanBound) | with-`-3js` | crazy / NaN |
|---|---|---|---|
| 11 | 26 / 0.051 | 28 / 0.055 | 0 / 0 |
| 22 | 18 / 0.035 | 17 / 0.033 | 0 / 0 |
| 33 | 18 / 0.035 | 29 / 0.057 | 0 / 0 |

Was: with-`-3js` → ~1.16M crazy → NaN by frame 5. Now crazy=0, no NaN, frames
hold; with≈without within the run-to-run thread non-determinism (binding is
non-deterministic at 16 threads — see the unapplied `discovery` stash). Frame 20
(t=0.0501): 100 minifilaments / 550 segments / 3200 myosins, all intact,
endpoints bounded in the 10×10×0.5 box, no NaN. Frames staged at
`RUN_LOGS/3js_readonly_test/frames/` for viewing.

**Binding reconciles with the documented stable value.** `meanBoundMotors ≈
0.051–0.057` matches the documented GPU-stable **occupancy 0.054** (2026-06-09
entry) — binding is healthy, NOT the `0.000` the cpuFallback hybrid regressed
to. Cumulative `bindEvents` (~20–30/seed) is lower than the ~270 figure cited
there only because **`boa10-64Seg-dyn-short` is a 500-step config**
(`runTime:0.05`), whereas the ~270 was on `boa10-64Seg-dyn` (`runTime:0.5`,
5000 steps). `bindEvents` is cumulative and scales ~linearly with step count:
~26 over 500 steps ↔ ~260 over 5000 steps ≈ the documented ~270. The
per-sample occupancy (0.054) is length-invariant and matches directly. So the
prompt's "~279, not 0" target is met.

### No-regression

- no-`-3js` runs unchanged: the new code is inert when no frame is written
  (no writer → no `refreshHostMirrorsForOutput` → snapshot never armed →
  `endOutputRender` is a no-op).
- packRange crash-fix holds: `dyn-short` GPU runs complete rc=0 (no
  `ClassCastException`).
- gliding val (`glidingAssay500_val`, GPU): glidingVelocity=GLIDEVAL (band
  7.369–8.076).

Fixtures (`ParameterFiles/boa10-*MiniFil*`, `boa10-64Seg-dyn-short`,
investigation run log) cherry-picked from `gpu-minifil-cohesion-fix`. The
cpuFallback approach from that branch (minifilament myosins → CPU, which
regressed binding to 0) is **not** applied here.

## 2026-06-09 — GPU packRange slot-map staleness fix

Fixed the pre-existing GPU crash (`ClassCastException: MyoMiniFilament →
FilSegment` at `GPUMoveThing.packRange`) that blocked the dynamic
contractile-network workload (minifilaments + active turnover) on the
GPU — confirmed pre-migration at `b15ff84` in the prior entry, not a
SoA/RNG/inc2 regression. The workload now runs to completion on GPU.

### Root-cause mechanism

`classifyThings()` (GPUMoveThing.java:3832) builds **four cached,
slot-indexed maps** in one atomic pass: `gpuThingIndices[slot]` →
`theThings[]` index, `brownianRule[slot]` → RULE_FIL/MYO/LEVER (the cast
driver), `thingNumberToMoveSlot[myThingNumber]` → slot, and the Myosin
joint slot lists (`rodSlots`/`leverSlots`/`motorSlots`). These are
**cached** and only rebuilt when `topologyDirty || thingCt != lastThingCt`
(GPUMoveThing.java:4112).

`Thing.removeThing()` (Thing.java:1092) swap-compacts
`theThings[swapId] = theThings[lastId]` — **changing which Thing occupies
a slot** — but never signalled the GPU layer (no `markTopologyDirty()`).
The `thingCt != lastThingCt` guard is a *count* proxy, not an *identity*
check. A pure removal drops the count and does rebuild (safe), but this
workload runs heavy biochem: when an **add and a remove balance in one
step** (kRdmNuc nucleation / new-FilSegment creation alongside a cofilin
dissolve), `thingCt == lastThingCt`, and if no poly/split fired
`markTopologyDirty` that step, `classifyThings` is **skipped**. The
swapped-in last Thing — often a CPU-fallback `MyoMiniFilament` — then sits
at a slot whose `brownianRule == RULE_FIL`, and `packRange` casts it to
`FilSegment` → crash. Non-deterministic: depends on the add/remove balance
× Thing ordering × seed (the only `theThings[]` identity-changing write in
the codebase is this swap; the `addThing` append never restructures
existing slots).

### Which slot-indexed state was stale — rule map, or more?

**The whole classifyThings map-set, but it is one cohesive cached
structure.** The four maps are rebuilt together by `classifyThings`. The
rule map crashes *loudly* (the cast); the index/thingNumber/joint maps go
silently stale the same way (an even-type swap corrupts the moved Thing's
*old* slot's pose). Everything else GPU-resident — pose
(`coord/uVec/yVec/soaLengthArr`), drag tensors (`soaBTransGam` etc.,
already swap-compacted in `removeThing`), joint forces — is **re-packed
each step** by `packRange` from the live (already-compacted) Thing array,
and `buildDeltaSet`'s slot-change scan scatters fresh pose on identity
change. **So the packed/resident buffers self-correct once the maps are
right; the classifyThings maps are the only cached slot-keyed state that
goes stale.** A single signal therefore covers all of it.

### The fix + cost rationale

`removeThing()` now sets `topologyDirty` (via
`GPUMoveThing.markTopologyDirty()`) when a swap actually moves a Thing
(`Env.useGPU && swapId != lastId`) — exactly mirroring how poly/split
already signal it (FilSegment.java:562/578). Chose the **dirty-flag**
over a localized O(1) slot-patch because the latter isn't actually O(1):
it would require compacting the GPU *slot* array and joint maps across 4
cases (removed/moved × GPU-handled/not), error-prone, for a path that
already rebuilds most steps.

**Cost:** `topologyDirty` triggers **only `classifyThings()`** — an
in-place O(thingCt) slot-map refresh — **never a plan rebuild**
(`closePlan`+`allocateAndBuildPlan`+FIRST_EXECUTION re-upload is gated
solely on `plan==null` or capacity-grow, GPUMoveThing.java:4085–4099).
It is a single idempotent boolean → **one** batched `classifyThings` at
the next `onStepStart` regardless of how many segments dissolved that
step. In this biochem-active workload poly/split already set
`topologyDirty` most steps, so `classifyThings` already runs most steps —
the fix only *adds* a rebuild on the rare balanced-count steps that were
buggily skipping it. **No per-removal rebuild-cost regression** — the
removal-heavy nomini run below logs `planRebuild=1` (init only). This
respects the POSE_DELTA_CAP lesson (the thing that work fought was the
*plan* rebuild, which this never touches).

### Validation (all on aorus, Java 21 + TornadoVM PTX, `-Xmx8G`)

| Run | Result |
|---|---|
| **baseProbe GPU** (crash-repro, `slotCap=19602`) | rc=0, 0 NaN/Inf — ran past the `call=1000`/`packRange:4703` crash point to clean shutdown. **Crash gone.** |
| **dyn GPU** seed1 (the config that crashed) | rc=0, 0 NaN/Inf, `bindEvents=279`, `meanBoundMotors=0.056`, `slotChange max=6112` (removal churn caught by the dirty-set scan) |
| **dyn GPU** seed2 | rc=0, `261 / 0.052` — **stable across seeds** |
| **dyn CPU control** (fix present, `Env.useGPU=false`) | `743 / 0.380` ≈ prior committed ref `679 / 0.338` (RNG variance) — **fix is CPU-neutral, reference reproduces** |
| **dyn-nomini GPU** (no-regression removal path) | rc=0, 0 NaN/Inf, `slotCount=554` (≈ prior 559), `poseDelta overflow=0`, **`planRebuild=1`** |
| **gliding val GPU** (physics-neutrality) | rc=0, `glidingVelocity=7.3683` — within the validated baseline band (7.369–8.076) |

**Note — GPU minifilament binding fidelity (separate, pre-existing
issue, NOT this fix).** GPU dyn binds systematically lower than CPU
(~270/0.054 vs ~710/0.36, stable across seeds). The CPU-control match
(743/0.380 ≈ ref) localizes the gap entirely to the **GPU path**, and it
traces to the known architectural seam that `MyoMiniFilament` is
**CPU-fallback** (`cpuFallback[]`; `MyosinDimer.jointConstraints` is
CPU-only, never ported — the binding kernel was scoped to gliding
`MyosinFixed`, JOURNAL refs ~4158/6003). On `-gpu`, minifilament motors
integrate on CPU while the filaments they bind are GPU-resident — a
lower-fidelity seam, orthogonal to slot-map staleness. There was no prior
GPU minifil completion to regress *from* (it always crashed); this is the
first measurement. Minifilaments **do** engage (nonzero binds, seg
growth, zero NaN/Inf). Flagged as a follow-on (port `MyoMiniFilament`
joints/binding to GPU), not gated here.

**Visual frame analysis sharpens this (added after a `-3js` GPU run,
`boa_dyn_minifil_gpu_fixed`, 101 frames).** The reduction is not a uniform
lower binding *rate* — it is **front-loaded and decays to near-zero**.
Counting `onFil:true` motors per frame: the GPU run has a bound motor in
only **2 of 101 frames** (frames 0 and 1 — the first ~50 steps), then
**zero bound motors for the entire rest of the run**; the CPU reference
(`boa_dyn_cpu_minifil`) has one in **16 of 101 frames**, sporadic
throughout. Both are sparse binders (these minifilament motors are
inherently transient), but the GPU path is ~8× sparser *and* qualitatively
different — binding **dies out** once the filaments start moving on the
device rather than sustaining. This is the fingerprint of the host-mirror
seam: the CPU-fallback minifilament motors run collision/binding detection
against the **host mirror** of filament pose, which is fresh at t≈0 (just
built) so motors bind, but goes stale relative to the device-resident
authoritative pose once integration proceeds — so binding detection misses
thereafter. It also explains the wide `bindEvents` spread across GPU runs
(279 → 261 → 59): the total is dominated by the brief early window and is
therefore sensitive to seed-dependent initial geometry. **Filament
turnover, by contrast, is faithful on GPU** — segments 197 → 563 (≈ CPU
198 → 566). **Concrete next step for the follow-on:** the real fix is the
filament pose the CPU-fallback minifilament motors read during collision
detection (host mirror) — either feed them device-synced pose at
collision cadence, or port minifilament binding/collision on-device. Not a
slot-map issue.

### Files

- `boxOfActin/Thing.java` — `removeThing()` signals `markTopologyDirty()`
  on a real swap when `Env.useGPU`.
- Fixtures merged onto the fix branch: `boa10-64Seg-dyn-probe` (3000-step
  minifil+turnover probe), `boa10-64Seg-baseProbe` (crash-repro),
  `boa10-64Seg-dyn-cpuprobe` (CPU reference).
- Run logs: `RUN_LOGS/2026-06-09_gpu_packrange_slotmap_fix.txt`.
- Visual frames: `~/Code/threejs_output/boa_dyn_minifil_gpu_fixed/` (101
  frames, GPU, the now-working config — pair with CPU
  `boa_dyn_cpu_minifil` for the binding-seam comparison above).

## 2026-06-09 — Dynamic-biochem regression run (minifilaments + active actin)

First post-migration exercise of the biochem-dynamics path. The whole
Pt3D→SoA migration (inc0a/0b/scalar, inc1, per-worker RNG, inc2) was
validated **only against the gliding assay, which runs actin biochem
OFF** (fixed-composition filaments). This run flips biochem ON to
exercise the segment length-changes (poly/depoly/split) that inc2's
drag-tensor contiguous-pack now rides on. Headline: **the migration's
dynamic path runs clean; the inc2 drag-pack survives active
length-changes; the one real bug found is a PRE-EXISTING GPU crash
(confirmed pre-migration), not a migration regression.**

### Survey findings (the config's biochem is misleadingly labeled)

- **`noMonomers` is a DEAD parameter label.** There is no `Parameter`
  named `noMonomers` — only `noMonomersSimd` / `noMonomersRendered`
  (Env.java:579–580). `FileOps.loadParamLine` matches on exact
  `label.equals`, so `boa10-64Seg`'s `noMonomers:true:0.0` matches
  nothing ("No match found... you best check that out!") and is
  ignored. `noMonomersSimd` therefore stays at its Java default =
  **inactive**, and **inactive means monomers ARE simulated** (biochem
  gates are `if (!Env.noMonomersSimd.isActive())` at
  FilSegment.java:539, 853, 472, 3712). To enable monomer simulation:
  `noMonomersSimd:false:0.0` (the simulate value; `isActive()` is the
  gate, the 0/1 value is irrelevant). `boxSpaghetti`'s comment
  ("Monomers ARE simulated and tracked" next to `noMonomers:true:0.0`)
  already encodes this — the author knew the label is dead.
  `myosSteppingSwitch` and `myosinStepRate` are **also dead labels** —
  motor stepping is governed by the declared `myo*` params at their Env
  defaults (same as the gliding configs, which don't list them).
- **Biochem-ON reference config:** `boxSpaghetti` (named in CLAUDE.md).
  It activates `actinConc` and simply **omits** the poly-rate lines so
  they fall to their nonzero Env inits.
- **Env init defaults restored** (boa10-64Seg zeroed them): end1
  kATPOn1/Off1/ADPOff1 = 1.3 / 0.8 / 2.7; end2 kATPOn2/Off2/ADPOff2 =
  11.6 / 1.4 / 7.2; actinConc_init = 15 µM; kHydro_init = 0.3 (file
  overrides to 1.0); kRdmNuc_init = 0.0.
- **Severing is cofilin-driven; there is NO `kSevering`.** A segment
  dissolves in `checkCofilinDissolve()` when its cofilin-bound monomer
  ratio exceeds `cofilinRatio` (FilSegment.java:3683). `boxSpaghetti`
  confirms: `cofilinRate:0.0 // Disable cofilin severing`.
- **New monomers add as ATP** (`Monomer.polymerize` → `new Monomer` →
  `setStateATP()`) and age to ADP via `kHydrolysis`. Confirmed.

### Parameter changes (`ParameterFiles/boa10-64Seg-dyn`)

From `boa10-64Seg`: `noMonomersSimd:false:0.0` (correctly-labeled);
`actinConc:true:20.0` (was `false:30.0`); poly/depoly rates restored to
Env inits (were 0.0); cofilin severing kept on (0.4 / 3.0 / 1.1);
`kRdmNuc:true:0.001` (was off); `kHydrolysis` aging stays on; minifils
kept (100). `runTime:0.5` (5000 steps), `toFileInterval:50` (~100
frames). Sibling `boa10-64Seg-dyn-nomini` = same but minifils removed
(GPU-runnable, see below).

### The one real bug — PRE-EXISTING GPU crash (NOT a migration regression)

On the **GPU path** (`-gpu`), `boa10-64Seg`-shaped configs that contain
**MyoMiniFilaments AND remove FilSegments** (cofilin dissolve / depoly)
crash with `ClassCastException: MyoMiniFilament cannot be cast to
FilSegment` at `GPUMoveThing.packRange` (worker thread dies → main
hangs). Mechanism: a `removeThing` swap-compaction of `Thing.theThings[]`
stales the GPU rule/slot map — `packRange` reads
`theThings[gpuThingIndices[slot]]` while `brownianRule[slot]==RULE_FIL`
and casts the swapped-in MyoMiniFilament to FilSegment.
`classifyThings` only rebuilds the rule map on `topologyDirty ||
thingCt != lastThingCt`; `buildDeltaSet` fixes swapped-slot *pose* but
not the *rule* map. The gliding validation never hit this (gliding uses
MyosinFixed, no MyoMiniFilaments, no FilSegment removal).

**Hard-bail check → cleared.** The crash is in `packRange` (the inc2
surface), so I tested the pre-migration commit `b15ff84` (last state
before any Pt3D-SoA source change; cast site at line 4694, old Pt3D
gather): **it crashes identically** (rc=124 hang, `cannot be cast`,
`packRange`). The bug predates the entire migration → it is a
pre-existing GPU + MyoMiniFilament + FilSegment-removal limitation, not
traceable to inc2/RNG/Pt3D. The unmodified `boa10-64Seg` on current
main also crashes the same way. Non-deterministic (depends on
remove-timing × Thing ordering × seed) — the first dyn GPU probe
happened to complete, but it is the same latent bug. **Fix is a
separate task** (mark `topologyDirty` on `removeThing`, or rebuild the
rule map in `buildDeltaSet`'s slot-change scan).

### Migration-surface tests (run around the pre-existing bug)

**(A) GPU drag-pack under length-changes — `boa10-64Seg-dyn-nomini`,
5000 steps, `-gpu`.** Minifils removed to isolate inc2's pack from the
cast bug. **rc=0, clean.** `filSegInitFireCt=3574` (heavy segment
creation), `poseDelta avg=0.68 max=190 sum=3461 overflow=0` (length-change
deltas captured/scattered, never overflowed POSE_DELTA_CAP=8192),
`planRebuild=1` (init only), segments **195 → 559**, no NaN/Inf, no
pack/index error. **The inc2 drag-tensor contiguous-pack survives active
poly/depoly/sever/nucleate length-changes.**

**(B) Full path with minifilaments — `boa10-64Seg-dyn`, 5000 steps,
CPU** (CPU has no `packRange`, sidesteps the pre-existing bug). **rc=0,
clean.** `bindEvents=679`, `meanBoundMotors=0.338` — **minifilaments
genuinely bind and step**. Segments **198 → 566** — matches the GPU
nomini run's 195→559, a clean CPU/GPU qualitative cross-check of the
turnover. No NaN/Inf.

### Pass conditions

| condition | result |
|---|---|
| Completes, no crash/NaN/Inf | ✅ both migration-surface runs rc=0, zero NaN/Inf |
| Drag-pack survives length-changes | ✅ GPU: 3574 seg-inits, poseDelta overflow=0, no pack error |
| No filaments ejected | ✅ x,y in ±5 box. ⚠️ CPU minifil run: ~5 % of endpoints mildly exceed the thin ±0.25 µm z-slab (max ~0.96 µm), slowly growing — minifils pulling fils against quasi-2D walls + finite CPU boundary stiffness; GPU nomini (on-device boundary kernel) stays in-box. Bounded, no blow-up; orthogonal to the migration. |
| Population steady | ⚠️ net-GROWS ~2.9×/5000 steps (poly > sever+depoly at actinConc=20). Bounded, no blow-up. Steady-state would need lower actinConc / higher cofilinRate — flagged, not gated (per discovery-and-bail "don't thrash on tuning"). |
| Minifilaments engage | ✅ CPU bindEvents=679, meanBoundMotors=0.338 |
| Recorded frames for scrubbing | ✅ see below |

### Recorded JSON frames for jba

- **`~/Code/threejs_output/boa_dyn_cpu_minifil/`** — 101 frames, CPU,
  full config (100 minifils + biochem). **Primary artifact** — shows
  minifilaments pulling + filaments polymerizing/turning over.
- **`~/Code/threejs_output/boa_dyn_nomini_gpu/`** — 101 frames, GPU,
  no minifils — the drag-pack-under-length-change run.
- Serve with `python3 sim_server.py 8000` from `~/Code/threejs_output`,
  open `sim_viewer_boa.html`.

Run logs in `RUN_LOGS/2026-06-09_dynbio_*` (`nomini_gpu`,
`cpu_minifil`, `baseProbe_gpu` = the pre-existing crash trace,
`probe_gpu_filtered` = the first dyn GPU probe minus its 9.4 M benign
"Crazy" lines). Note: the "Crazy torque result in MyoDimer" /
"Crazy forceSum in MyoMiniFilament" warnings (millions on the GPU minifil
probe) are mostly the benign parallel-lever degeneracy (`cross()≈0` →
skip) — structural to bundled minifilaments, not a NaN.

**Queued fast-follows (not part of this probe):** (1) fix the
pre-existing GPU packRange slot-map staleness so minifilament configs
run on GPU; (2) profile this workload (CPU/GPU per-step, pack fraction)
under active biochem; (3) tune actinConc/cofilin for a steadier
population if a true steady-state run is wanted.

---

Older entries — the 2026-06-09 Pt3D SoA migration work and everything before it — have been moved to `JOURNAL_ARCHIVE.md`.
