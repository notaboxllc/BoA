# Membrane v2 — DTS implementation notes (as-built)

**Status:** Stages 1–2 complete and committed (2026-06-17). All default-off; production paths untouched.
This is the *as-built* record — what was actually implemented, how it was tested, the bugs found, and (most
importantly) **where every formula and code pattern was cribbed from**. The pre-build plan / decision record
is in `MEMBRANE_DTS_DESIGN.md`; the narrative history is in `JOURNAL.md` (entries dated 2026-06-17).

> **Provenance policy.** Mathematical methods and equations are not copyrightable; we ported them from the
> papers and re-derived the gradients ourselves, validating by finite differences. We did **not** copy or
> line-by-line translate source code from the reference packages (all are GPL — see §10). Where an algorithm
> is standard (closest-point-on-triangle, triangle-normal derivative) it is cited to a textbook.

---

## 1. What was built

| Stage | Deliverable | Files |
|---|---|---|
| 1 | Icosphere geometry + flat-SoA `Membrane` object + viewer surface render | `boxOfActin/Icosphere.java`, `boxOfActin/Membrane.java`, `boxOfActin/MembraneVertex.java`, `sim_viewer_boa.html`, `boxOfActin/ThreeJSWriter.java` |
| 2 | Bending (Jülicher) + area + volume forces, FD-validated | `boxOfActin/Membrane.java` (`computeForces` and helpers) |
| demos | constant-force probe, push-patch protrusion, bouncers | `boxOfActin/Membrane.java`, `ParameterFiles/dtsMembrane*` |
| tests | sphere-energy check (→8πκ), analytic-force FD check | `DtsDihedralCheck.java`, `DtsForceCheck.java` |

The DTS membrane is a closed, triangulated **fluid lipid bilayer**: Helfrich bending + area + volume, with
lightweight vertex `Thing`s reusing BoA's existing SoA pose / overdamped-Langevin mover / collision machinery.
It is independent of the legacy spring/vesicle (StickyNode) membrane.

---

## 2. Build & run

Requires Java 21 + TornadoVM on the classpath (CPU run; see `CLAUDE.md` build section). Needs `-Xmx4G`
(the default 800M OOMs on the spatial collision grid — unrelated to DTS).

```
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java

# Stage 1 (geometry only):            java -Xmx4G ... BoxOfActin -r -pf ParameterFiles/dtsMembrane1 -3js OUT
# Stage 2 vesicle (holds / deflates): java -Xmx4G ... BoxOfActin -r -pf ParameterFiles/dtsMembrane2 -3js OUT
# Demos:                              -pf ParameterFiles/dtsMembranePush | dtsMembraneBouncers | dtsMembraneProbe
# Regression tests (standalone mains):
java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." DtsDihedralCheck      # energy -> 8*pi
java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." DtsForceCheck 3 1e-19 0.2 1e-13  # force = -dE/dr
```

View: `python3 sim_server.py 8000 ~/Code`, then `http://localhost:8000/BoA/sim_viewer_boa.html`
(turn on **DTS surface** / **DTS wireframe**; **opacity** slider applies).

---

## 3. Geometry & data structure (Stage 1)

### 3.1 Icosphere — `Icosphere.java`

Golden-ratio icosahedron (12 v / 20 f) subdivided 4:1 `ν` times, midpoints projected to the unit sphere
→ `10·4^ν + 2` vertices, `20·4^ν` faces. A `HashMap` midpoint cache shares the inserted vertex between the
two faces flanking an edge (watertight). Faces are CCW-outward; the build self-checks via positive signed
volume.

> **Cribbed:** the 12 golden-ratio vertices `(±1,±t,0)…` and the canonical 20-face index list are the
> well-known **"icosphere" construction popularised by Andreas Kähler** ("Creating an icosphere mesh in
> code", 2009, blog.andreaskahler.com) — the same vertex/face tables used across countless mesh libraries.
> Reference codes (FreeDTS/TriMem) read clean triangulations rather than hull-triangulating point clouds, so
> a deterministic icosphere matches their input convention.

### 3.2 Flat SoA topology — `Membrane.java`

GPU/ECS-portable flat `int[]` arrays (a **hard requirement** — see `MEMBRANE_DTS_DESIGN.md §2`), *not* a
pointer half-edge:

- `faceVert[3·nf]` — CCW vertex indices per face.
- **wing-edges**: `edgeVert[2·ne]` (lo,hi), `edgeFace[2·ne]` (the two flanking faces), `edgeWing[2·ne]`
  (the two apex/opposite vertices) — everything dihedral bending and edge-flips need, no pointer chasing.
- per-vertex incidence: fixed-width `vertEdge[nv·maxVal]` + `vertEdgeCt[nv]`.

Edges and incidence are *derived from the faces* (`deriveEdges`): each undirected edge is required to border
exactly two faces (manifold check), and `nv − ne + nf == 2` is asserted (Euler characteristic of a sphere).
Vertex pose is **not** duplicated here — it lives in the existing `Thing` SoA, accessed through the
`MembraneVertex` handles in `vert[]`.

> The wing-edge layout and the "flat SoA, host-patch topology via POSE_DELTA scatter" model are BoA-specific
> design decisions (jba) to map onto BoA's existing GPU residency machinery; the *concept* of carrying the two
> flanking faces + two apexes per edge mirrors the half-edge information FreeDTS/TriMem keep, flattened.

### 3.3 Vertices as Things — `MembraneVertex.java`

`extends ProteinNode` (so it reuses the SoA pose, overdamped `moveThing`, Brownian, collisions) but:
- carries **no surface myosins** (new no-myosin `ProteinNode(Pt3D,double,boolean)` ctor);
- is **structural** — `biochemStep()` overridden empty (no stochastic turnover);
- uses **physical Stokes drag** `γ = 6πηr` — `calculateProperties()` overridden to bypass *both* the
  StickyNode `membraneNodeDragScale` (the 1e-10 force-scale trap) *and* the nodeTransDiff overrides;
- `step()` overridden empty — skips the generic chamber-wall collision (a cell membrane *is* the boundary;
  the wall force otherwise swamps the physical membrane forces — see §8 bug #3).

### 3.4 Render

`ThreeJSWriter.buildFrameJson` emits a `membranes` array `[{id, vertices:[[x,y,z]…], faces:[a,b,c,…]}]`
(flat triangle-index list), excluding `MembraneVertex` from the `nodes` array. The viewer
(`updateDtsMembranes`) uploads it into an indexed `THREE.BufferGeometry` (position + index +
`computeVertexNormals`) drawn as a translucent `Mesh` plus a shared-geometry wireframe `Mesh`. Single
`writeFrame` feeds both live and file paths.

**Validation (ν=4):** 2562 v / 5120 f / 7680 e, Euler = 2, maxVal = 6; mesh area/volume = 99.9% / 99.8% of
the continuum sphere `4πR²` / `(4/3)πR³`.

---

## 4. Energies & forces (Stage 2)

All geometry is done **in metres** (positions ×1e-6 on read) so forces come out in Newtons with no unit fudge
factor. `computeForces()` is structured as the `MEMBRANE_DTS_DESIGN.md §2` kernels even in this CPU
prototype: per-face (normal/area/signed-volume) → per-edge (length/dihedral) → per-vertex gather (curvature
`c_v`, area `A_v`) → per-edge/per-face force **scatter** into a per-vertex accumulator.

### 4.1 Bending — the Jülicher / Itzykson edge form

```
E_bend = κ · Σ_v  2 · (c_v/A_v − C̄/2)² · A_v          (C̄ = 0 default ⇒ κ·Σ_v 2 c_v²/A_v)
   c_v = (1/4) Σ_{edges e∋v} l_e · θ_e                  l_e = edge length, θ_e = signed dihedral angle
   A_v = (1/3) Σ_{faces f∋v} area(f)                    barycentric vertex (dual) area
```

**κ multiplies directly — no √3/2 or geometry-dependent coefficient.** This form discretizes
`(κ/2)∫(2H)²dA` *isotropically*; on a closed sphere it reproduces the continuum **8πκ**.

> **Cribbed from, and cross-checked against, two independent codes (read from source, math ported):**
> - **FreeDTS** — Pezeshkian et al., *Mesoscale simulation of biomembranes with FreeDTS*, Nat. Commun. **15**,
>   548 (2024); github.com/weria-pezeshkian/FreeDTS, files `version_1/dts_src/{Curvature,links,triangle,
>   Energy}.cpp`. FreeDTS evaluates the same Helfrich energy via a per-vertex **shape operator** (build a 3×3
>   curvature tensor from edge contributions `SV += (N̂_v·n̂_e)·h_e·(Ŝ_e⊗Ŝ_e)`, `h_e = ±l_e√(½(1−n̂_{f1}·n̂_{f2}))`,
>   diagonalize, divide eigenvalues by `A_v`); `A_v = (1/3)Σ incident-triangle areas` (**barycentric**, not
>   Voronoi). FreeDTS is Monte-Carlo / **energy-only** (no analytic force).
> - **TriMem** — Siggel, Kehl, Reuter, Köfinger, Hummer, *TriMem: A parallelized hybrid MC software …*,
>   J. Chem. Phys. **157**, 174801 (2022); github.com/bio-phys/trimem, files `src/mesh_properties.cpp`
>   (`vertex_properties`, `vertex_properties_grad`), `src/{mesh_util.h,kernel.h,energy.cpp}`. TriMem uses the
>   **same** edge/dihedral curvature `H_i = (1/(4 A_i)) Σ_e l_e θ_e` with **barycentric** `A_i` and energy
>   `κ Σ_i 2 c_i²/A_i`, and provides the **analytic gradient** we cribbed (§4.4). NB: TriMem does **not** use
>   the Meyer cotangent-Laplacian operator (that is Mem3DG, arXiv:2111.04460).
> - The discretization lineage: **Itzykson** (edge/dihedral discrete curvature); **Jülicher** (the
>   `M₁ = H_i A_i = ¼Σ l_e θ_e` barycentric scheme); **Gompper & Kroll**, *Triangulated-surface models of
>   fluctuating membranes*, in Nelson/Piran/Weinberg, "Statistical Mechanics of Membranes and Surfaces"
>   (2nd ed., World Scientific, 2004), and *J. Phys. I France* **6**, 1305 (1996); **Ramakrishnan–Ipsen**
>   (the shape-operator FreeDTS cites as ref. [81]). The Helfrich functional itself: **Helfrich**, *Elastic
>   properties of lipid bilayers*, Z. Naturforsch. C **28**, 693 (1973).
>
> **Why NOT the crude dihedral `κ_d·Σ(1−n̂_α·n̂_β)`** (Seung–Nelson): it is **anisotropic** — sphere ≠
> cylinder, so there is no single continuum κ. The literature itself splits on the sphere mapping
> (`κ = (√3/2)κ_d` cylinder vs `κ = κ_d/√3` sphere), and our once-per-edge icosphere measures yet another
> value (~0.30·8π). We rejected it. Refs documenting this: **Seung & Nelson**, *Defects in flexible membranes
> with crystalline order*, Phys. Rev. A **38**, 1005 (1988); **Schmidt & Fraternali**, "Universal formulae for
> the elastic constants…", arXiv:1102.1383 (JMPS 2012); **Fedosov, Caswell & Karniadakis**, arXiv:0905.0042
> (2010), Eqs. 6 & 17; "Comparison of Bending-Energy Discretization Methods…", arXiv:2503.00302. (See the run
> log `RUN_LOGS/2026-06-17_dts_bending_coefficient.txt` for the numeric comparison on our own mesh.)

### 4.2 Area — harmonic constraint

```
E_area = (K_A/2)(A_tot − A₀)² / A₀          F_i = −K_A(A_tot − A₀)/A₀ · ∇_i A_tot
   ∇_i A_face = ½ (n̂_face × e_opposite)     (e_opposite = the face edge opposite vertex i, oriented)
```

> The triangle-area gradient `½ n̂ × e_opp` is the standard result (e.g. **TriMem** `face_area_grad` in
> `mesh_util.h`; any mesh-processing text). `A₀` is the icosphere IC area. The `/A₀` normalization makes
> `E_area` dimensionally `J` with `K_A` in N/m (lipid ≈ 0.2 N/m). FreeDTS uses the same harmonic form (its
> Eq. 3). The per-face scatter sums `½ n̂ × e_opp` over the three vertices.

### 4.3 Volume — harmonic constraint

```
E_vol = (K_V/2)(V/V₀ − v_t)²               V = (1/6) Σ_faces r_a·(r_b × r_c)   (signed, CCW-outward)
   F_i = −K_V(V/V₀ − v_t)/V₀ · ∇_i V        ∇_i V = (1/6) (r_j × r_k)  (the other two CCW verts of each face)
```

> The signed-volume formula and its per-vertex gradient `⅙(r_j×r_k)` are the standard divergence-theorem
> tetrahedron sum — **FreeDTS Eq. 4** uses the identical `V = (1/6)Σ r_a·(r_b×r_c)`. `K_V` in J; for a
> near-incompressible interior K_V must be large enough that the volume force is comparable to the area force
> (we found ~1e-13 J at our scale). `v_t < 1` deflates toward oblate/stomatocyte shapes — the standard DTS
> reduced-volume validation inherited from the papers.

### 4.4 Force assembly — the analytic gradient

Per-vertex weights from the bending energy:
`α_v ≡ ∂E/∂c_v = 4κ(c_v/A_v − C̄/2)`,  `β_v ≡ ∂E/∂A_v = −2κ((c_v/A_v)² − (C̄/2)²)`.
Then `F_i = −Σ_v [α_v ∂c_v/∂r_i + β_v ∂A_v/∂r_i]`, reorganized into scatters:

- **bending c-term** (per-edge): coefficient `¼(α_p+α_q)` × `(θ_e ∂l_e/∂r + l_e ∂θ_e/∂r)`, scattered to the
  edge's 4 vertices {p, q, w1, w2}.
- **bending A-term + area + volume** (per-face): area-gradient scalar `−(⅓(β_a+β_b+β_c) + K_A(A−A₀)/A₀)` ×
  `∂area/∂r`, plus the volume scatter.

> **Cribbed from TriMem's analytic gradient** (`vertex_properties_grad` in `mesh_properties.cpp`), re-derived
> and FD-validated. Three elementary primitive gradients are needed (all standard, all re-derived):
> - `∂l_e/∂r = ±ê` (unit edge vector);
> - `∂A_face/∂r = ½ n̂ × e_opp` (TriMem `face_area_grad`);
> - `∂θ_e/∂r` — the **dihedral / hinge-angle gradient** (the only nontrivial one).
>
> **The dihedral-angle gradient** we use the **exact normal-derivative form**, derived directly rather than
> via cotangent weights (a cotangent-weight heuristic we first tried did not pass FD — see §8 bug #1):
> `θ = s·acos(n̂₁·n̂₂)`, `∂θ/∂x = s·(−1/sinθ)·∂(n̂₁·n̂₂)/∂x`, with
> `∂(n̂₁·n̂₂)/∂x_i = Σ_{f∋i} (1/(2A_f))( r_f × w_i^f )`, `r_f = n̂_other − (n̂₁·n̂₂)n̂_f`,
> `w_i^f = p_next − p_prev` in face f's CCW order — obtained from the triangle unit-normal derivative
> `∂N/∂x_i = [w_i]_×` (a skew-symmetric matrix), standard vector calculus. The *hinge-gradient lineage* this
> belongs to: **Bridson et al.** (cloth, "Simulation of clothing with folds and wrinkles", 2003);
> **Grinspun et al.**, "Discrete Shells", SCA 2003; **Tamstorf & Grinspun**, "Discrete bending forces and
> their Jacobians", Graphical Models **75** (2013); review in **Guckenberger & Gekle**, J. Phys. Condens.
> Matter **29**, 203001 (2017). The **sign of θ** must be made consistent across the closed mesh by orienting
> ê via face f1's CCW winding (§8 bug #2).

### 4.5 Units & loop integration

- Positions µm → metres (×1e-6) on read; lengths/areas/volumes/gradients in SI → forces in N directly.
- Hook: `Membrane.computeAllForces()` in `BoxOfActin.doLoop()` in the single-threaded pre-move region (right
  after `computeVesiclePressure`/`driveMembraneProbe`), so the existing overdamped move phase integrates the
  forces. No separate relaxation loop (`NodeLink.subcycleRelaxAll` is *retired* for DTS).
- **`computeForces` OWNS the vertex force**: it zeroes each vertex's `soaForceSum` then writes only the
  membrane (+ demo) forces (§8 bug #3). Brownian/actin coupling will be added there explicitly in later stages.
- Stiff K_A/K_V transients are bounded by the `dtsMaxDispFrac` per-step displacement clamp (the DTS analog of
  the existing `membraneNodeMaxDispFrac` StickyNode clamp).

---

## 5. Demos

| Config | What it shows | Mechanism |
|---|---|---|
| `dtsMembrane2` | vesicle **holds** the sphere (vt=1); **deflates** to a stable oblate (vt=0.9) | bending+area+volume only |
| `dtsMembranePush` | constant cap force → smooth **pear-shaped bleb** that grows and **stalls** | `dtsPushForce` spread over a `dtsPushCapDeg` polar cap of vertices |
| `dtsMembraneBouncers` | sized nodes ricochet inside, dent **transient bulges** (response + relaxation) | per-vertex soft-spring steric + local-surface backstop |
| `dtsMembraneProbe` | constant-force probe (WIP — parks at the surface) | point-vs-triangle steric (contact dynamics need work) |

**Steric contact (probe/bouncers).** Two pieces:
- *push that bulges*: a per-vertex **soft penetration spring** `mag = k·pen` (`dtsStericStiffness`) — pushing
  the vertices directly is what bulges the surface; a stiff drag-coupled or triangle-wall steric just parks
  the node at the boundary at ~0 penetration and never bulges (§8 bug #4).
- *containment*: a radial backstop that tracks the **local (bulging) membrane surface** (`stericContactMaxR`),
  so a node can reach and bulge the wall but never push its surface through (fixes nodes escaping). On hitting
  it, the drive direction reflects inward (a ricochet).

> The drag-coupled collision form `mag = (overlap/Δt)/(1/γ_a + 1/γ_b)` (used in the first probe attempt) is the
> **existing BoA node-collision pattern** (`ProteinNode.checkNodeCollision`). The point-vs-triangle
> closest-point routine (`closestPtTri`, retained for the Stage-3 actin tip-vs-triangle push) is **Ericson,
> *Real-Time Collision Detection*, Morgan Kaufmann (2004), §5.1.5** (the standard barycentric algorithm).

---

## 6. Testing methodology

Two committed standalone regression tests (default-package mains) plus in-run diagnostics.

1. **`DtsDihedralCheck.java` — energy correctness.** Pure geometry on `Icosphere.build(ν)`; computes the
   Jülicher sum `Σ_v 2c_v²/A_v` (and, for contrast, the crude `Σ(1−n̂·n̂)`). **Result: → 8π** (continuum
   8πκ/κ): 25.145 at ν=4 (**0.05%**), 25.1335 at ν=6 (0.003%); textbook h² convergence; exactly
   radius-independent. The crude form sticks at ~0.30·8π (anisotropic — rejected). This is the constant we
   rely on, so the test pins it.

2. **`DtsForceCheck.java` — force correctness (the key oracle).** Builds a small membrane, perturbs it, and
   checks **every analytic force component against central finite differences of the energy**:
   `F_i ?= −(E(r_i+h) − E(r_i−h))/2h`. **Result: matches to ~2e-4** (relative; the floor is float SoA-coord
   granularity in the FD step — the error drops to ~1.7e-4 as h is tuned, confirming the analytic force is
   exact). Also includes a manual gradient-descent loop (bypassing the sim mover) that confirmed
   energy decreases monotonically — which is how bug #3 was localized to the mover/pipeline, not the force.
   Run: `DtsForceCheck <ν> <κ> <K_A> <K_V>` (each term can be isolated by zeroing the others).

3. **Dynamic validation.** vt=1 vesicle holds (E_bend pinned at 8πκ, A/V held, **|F|max decays to ~1e-15 N** —
   true equilibrium, the icosphere defect residual smoothing out); vt=0.9 deflates to the target volume and a
   stable non-spherical shape. In-run `[DTS-E]` prints energy/area/volume/|F|max at frame cadence.

**Numeric record:** `RUN_LOGS/2026-06-17_dts_bending_coefficient.txt`.

---

## 7. Parameter reference (`Env.java`; grep `dts`)

| Parameter | Meaning | Default | Runtime-mutable |
|---|---|---|---|
| `buildDtsMembrane` | build the DTS membrane IC | off | no (IC flag) |
| `dtsMembraneSubdiv` | icosphere ν (`nv=10·4^ν+2`) | 4 | no (IC) |
| `dtsMembraneRadius` | initial radius (µm) | 1.0 | no (IC) |
| `dtsVertexRadiusFrac` | vertex radius = frac × mean edge length | 0.5 | no (IC) |
| `dtsKappaBend` | bending rigidity κ (J); ~20–25 k_BT | 1e-19 | **yes** |
| `dtsKappaArea` | area-stretch modulus K_A (N/m); lipid ≈ 0.2 | 0 | **yes** |
| `dtsKappaVolume` | volume modulus K_V (J); large ⇒ incompressible | 0 | **yes** |
| `dtsTargetReducedVol` | target reduced volume v_t (<1 deflates) | 1.0 | **yes** |
| `dtsMaxDispFrac` | per-step vertex displacement clamp (× vertex r) | 0 | **yes** |
| `dtsBrownianOff` | deterministic (suppress vertex Brownian) | off | no (IC) |
| `dtsPushForce` / `dtsPushCapDeg` | push-patch protrusion drive / cap half-angle | 0 / 25° | **yes** |
| `dtsProbeForce` / `dtsProbeRadius` / `dtsProbeStartX` | constant-force probe | 0 / 0.25 / 0 | force only |
| `dtsBouncerCount` / `…Force` / `…TurnProb` / `…MinR` / `…MaxR` | bouncers | 0 / 2e-10 / .004 / .12 / .35 | force/turn only |
| `dtsStericStiffness` | probe/bouncer contact spring (N/m per vertex) | 8e-4 | **yes** |

The elastic moduli (`dtsKappaArea/Volume/Bend`) are the **compliance knobs** — lower = more compliant /
bigger bulges — and are tunable live in the viewer Params panel via `setParam`.

---

## 8. Bugs found & fixed during bring-up (each caught by the FD oracle + energy monitoring)

1. **Hinge gradient wrong.** A cotangent-weighted dihedral-gradient heuristic failed FD in every sign/weight
   combination. Replaced with the **exact normal-derivative form** (§4.4) via `∂N/∂x = [w]_×` skew matrices.
   FD then → 2e-4.
2. **Dihedral sign inconsistent.** `θ`'s sign used `sign(ê·(n̂₁×n̂₂))` with ê from the arbitrary `edgeVert`
   lo/hi order → random per-edge signs → `c_v = ¼Σ l θ` cancelled to garbage (live energy read **16× too
   small**). Fix: orient ê by **face f1's CCW winding** → consistent convex-positive sign.
3. **Force ownership / pipeline pollution.** The generic node pipeline (chamber-wall collision, etc.) left a
   ~2.7e-10 N spurious force on the vertices that swamped the membrane forces and inflated the surface — even
   though manual gradient descent with the *same* force was perfectly stable. Fix: **`computeForces` zeroes
   each vertex then writes only the membrane forces**; `MembraneVertex.step()` overridden empty (no chamber
   collision).
4. **Stiffness / contact.** (a) Stiff K_A/K_V + large transients explode under explicit Euler → `dtsMaxDispFrac`
   clamp. (b) A drag-coupled / triangle-wall steric resolves penetration in one step → ejects the contacting
   node before it can transmit a push → no bulge. Fix: **soft per-vertex penetration spring** (node dwells,
   pushes a sustained bulge) + **local-surface backstop** for containment.

---

## 9. What is NOT done / next (Stage 3+)

- **Actin coupling** (the real Stage 3): kinetic side-binding linker tethers (cortex / blebbing knob) +
  barbed-tip-vs-triangle steric push (the `closestPtTri` routine is already in place), added explicitly inside
  `computeForces`.
- **Probe contact dynamics**: the free-flying probe parks at the surface without a sustained push — needs the
  same sustained-contact treatment the bouncers' vertex-spring uses. Deferred (doubles as Stage-3 groundwork).
- **Brownian undulations**: vertices currently run deterministic (`dtsBrownianOff`); physical thermal forcing
  should be added in `computeForces` (FDT with the physical drag) for fluctuation-spectrum validation.
- **Fluidity (bond flips) + growth (split/collapse)**: needed for deep reduced-volume shapes (vt ≤ 0.6, which
  buckle and need self-avoidance + mesh conditioning) and true lateral fluidity. The flat-SoA topology is
  designed for host-patch-then-scatter at MC/flip cadence (`MEMBRANE_DTS_DESIGN.md §4–5`).
- **GPU port**: `computeForces` is written kernel-shaped (per-edge/per-face/per-vertex over the flat arrays)
  so the port is "register these into the chained TaskGraph," not a rewrite.

---

## 10. References & licenses

**Reference codes** (math ported from the papers / source read for the discretization; **no code copied** —
all are strong copyleft):

| Code | Cite | Used for | License |
|---|---|---|---|
| FreeDTS | Pezeshkian et al., Nat. Commun. **15**, 548 (2024); github.com/weria-pezeshkian/FreeDTS; PMC10792169 | bending energy + barycentric A_v + area/volume constraints; MC-only (no force) | **GPL-2.0** |
| TriMem | Siggel et al., J. Chem. Phys. **157**, 174801 (2022); github.com/bio-phys/trimem; trimem.readthedocs.io | **analytic bending gradient** (force path); same Jülicher curvature | **GPL-3.0** |
| trisurf-ng / cluster-trisurf | Penić, Fošnarič et al.; github.com/yoavrv/cluster-trisurf | bond-flip move reference (Stage 4) | **GPL-3.0** |

**Papers / texts (formulas):**
- Helfrich, *Elastic properties of lipid bilayers*, Z. Naturforsch. C **28**, 693 (1973) — the curvature energy.
- Gompper & Kroll, *Triangulated-surface models of fluctuating membranes*, in "Statistical Mechanics of
  Membranes and Surfaces" (2nd ed., World Scientific, 2004); and J. Phys. I France **6**, 1305 (1996) — DTS
  methodology + bending discretization + shape-dependence caveat.
- Seung & Nelson, Phys. Rev. A **38**, 1005 (1988); Schmidt & Fraternali, arXiv:1102.1383 (2012); Fedosov,
  Caswell & Karniadakis, arXiv:0905.0042 (2010); "Comparison of Bending-Energy Discretization Methods…",
  arXiv:2503.00302 — the crude-dihedral `√3/2` coefficient and its geometry dependence (why we *don't* use it).
- Meyer, Desbrun, Schröder, Barr, *Discrete Differential-Geometry Operators for Triangulated 2-Manifolds*
  (2003) — the cotangent operator (noted as the *alternative* discretization, used by Mem3DG, **not** here).
- Bridson et al. (SIGGRAPH 2003); Grinspun et al., *Discrete Shells* (SCA 2003); Tamstorf & Grinspun,
  *Discrete bending forces and their Jacobians*, Graphical Models **75** (2013); Guckenberger & Gekle review,
  J. Phys. Condens. Matter **29**, 203001 (2017) — hinge-angle-gradient lineage.
- Ericson, *Real-Time Collision Detection*, Morgan Kaufmann (2004), §5.1.5 — closest-point-on-triangle.
- Kähler, "Creating an icosphere mesh in code" (2009) — the icosphere vertex/face tables.
- HOOMD-blue `md.mesh.bending.BendingRigidity` docs — a production reference implementation of the dihedral form.

**Provenance:** all gradients were **re-derived and FD-validated** (`DtsForceCheck.java`); we ported the
mathematical methods from the papers and did not transcribe GPL source. If BoA is ever distributed, this
clean-room provenance keeps it free of the reference codes' copyleft.

**BoA-internal cross-references:** `MEMBRANE_DTS_DESIGN.md` (pre-build plan / decisions), `JOURNAL.md`
(2026-06-17 entries), `RUN_LOGS/2026-06-17_dts_bending_coefficient.txt`, `CLAUDE.md` (build/run).
