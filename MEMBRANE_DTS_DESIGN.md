# Membrane v2 — Dynamically-Triangulated-Surface (DTS) design note

**Status:** design / pre-build. Survives context clears — this is the agreed math + parameter map before
any code. Nothing here is implemented yet. Last updated 2026-06-17.

## 0. Purpose & decision record

We are replacing the ad-hoc spring/disc membrane with a **dynamically-triangulated surface (DTS)** — the
standard coarse-grained biophysics membrane model. Decisions already made with jba:

- **The DTS surface = the lipid bilayer** (fluid, bending + area + volume). It is *always* a bilayer; we do
  not build "stiff membrane" modes into it.
- **Stiffness/role comes from the actin**, which BoA already simulates explicitly: the cortex is the bilayer
  **bonded to actin via implicit accessory-protein linkers** (ERM/spectrin abstracted to kinetic tethers).
  Stiff cortex = dense/slow linkers + dense actin; floppy lamellipodium = sparse linkers + branched-actin
  push; mitochondrion/organelle = bilayer alone, no actin.
- **Blebbing is then native**, not manufactured: a bleb is the bilayer detaching from the cortex where
  linkers are sparse or rupture and pressure balloons the freed membrane out.
- **Multi-instance:** a sim may hold several independent `Membrane` objects (cell surface, organelles).
  Architecture must not assume one global membrane.
- **Initial mesh: icosphere** (subdivided icosahedron), matching the reference codes (they read clean
  triangulations; nobody hull-triangulates point clouds).
- **Port the physics into BoA's overdamped-Langevin MD loop**; do not drive an external code. We crib the
  validated *energy/force expressions* (math isn't licensable), using the codes as reference.

**Prerequisite already landed:** `membraneNodeDragScale = 1.0` (physical drag). The DTS forces below are
**physical N-scale forces**; the old 1e-10 drag put the membrane at a ~1e-20 N scale and would silently
swallow them. Physical drag is mandatory for this model. (See JOURNAL 2026-06-17.)

## 1. Reference codes (crib targets)

| code | lang | method | crib for |
|---|---|---|---|
| **FreeDTS** (Pezeshkian, Nat. Commun. 2024) | C++, self-contained | Monte Carlo | energy terms (verified below), constraints (tension/volume/pressure/confinement/external force), inclusions, architecture |
| **TriMem** (Hummer group, JCP 2022) | C++/Python, OpenMesh | hybrid MC (MD moves + MC flips) | **analytic energy gradients (forces)** for the MD path; half-edge data structure |
| **trisurf-ng** (Penić/Fošnarič) | C | Monte Carlo | simplest reference for the bond-flip (fluidity) move |

We are MD (overdamped Langevin), so TriMem's HMC structure (MD vertex moves + interspersed MC bond flips)
is the closest paradigm. FreeDTS/trisurf are pure MC and only ever evaluate *energy*; we additionally need
the **gradient** (force) — see §3.

## 2. Geometry & data structure

- **Icosphere:** start from the 12-vertex / 20-face icosahedron; subdivide each triangle into 4 (edge
  midpoints projected to the sphere) ν times → `10·4^ν + 2` vertices, `20·4^ν` faces.
  - ν=4 → 2562 verts / 5120 faces (≈ current 3267-node sphere; ~50–80 nm patches on a 1.2 µm cell).
  - ν=5 → 10242 verts. Pick ν by target patch size vs cost.
- **Topology:** a **half-edge** structure (TriMem uses OpenMesh's). Gives O(1) neighbor iteration and clean
  edge-flip / edge-split / edge-collapse. A lightweight Java half-edge (`Vertex`, `HalfEdge`, `Face`) is the
  data-structure investment of Stage 1. (A faces[]+incidence-lists alternative works for fixed topology but
  is painful for flips/splits — go half-edge.)
- **`Membrane` object** owns its own `Vertex[]/HalfEdge[]/Face[]` and parameters (κ, K_A, A₀, K_V, V₀, …).
  A static `Membrane[] theMembranes` allows several per sim. **Vertices are lightweight `Thing`s**
  (StickyNode-derived or a new `MembraneVertex`) so they ride the existing SoA pose, overdamped mover,
  Brownian forcing, and collision machinery — the `Membrane` object only adds the topology + the
  bending/area/volume force computation on top.

## 3. Energy terms (the math we crib)

Conventions below: `r_i` vertex position (vector), `l_ij = |r_i − r_j|`, faces CCW-outward.

### 3a. Bending (Helfrich) — FreeDTS Eq. 2 (verified from the paper)

```
E_bend = Σ_v [ (κ/2)(2H_v − C̄)²  −  κ_G·K_v ] · A_v
```
- `κ` bending rigidity; `κ_G` Gaussian modulus (constant for fixed topology → can drop while topology is
  fixed, by Gauss–Bonnet); `C̄` spontaneous curvature (0 for a symmetric bilayer by default).
- `2H_v = c1,v + c2,v` (twice mean curvature at vertex v); `K_v = c1,v·c2,v` (Gaussian curvature).
- `A_v` vertex (dual) area. Units in the papers: energy in k_BT, lengths in `l_dts`, curvature in `l_dts⁻¹`.

The per-vertex principal curvatures `c1,c2` and `A_v` come from the **vertex shape operator**: build the
3×3 curvature tensor at v by summing edge contributions (edge curvature × outer product of the edge
direction), area-weighted, then diagonalize (the surviving two non-zero eigenvalues are c1,c2). This is the
Gompper–Kroll / Ramakrishnan–Ipsen discretization FreeDTS & trisurf use. **VERIFY the exact edge weights and
A_v definition against FreeDTS source before coding** (`Vertex`/`Triangle` curvature routines).

**For the MD (force) path, start with the simpler dihedral form** — its gradient is analytic and cheap:
```
E_bend ≈ κ_d · Σ_{shared edges αβ} (1 − n̂_α · n̂_β)
```
- `n̂_α, n̂_β` unit normals of the two triangles sharing the edge.
- Continuum mapping (regular triangulation): `κ ≈ (√3/2)·κ_d`  — **VERIFY coefficient** (Seung–Nelson /
  Gompper–Kroll; the √3/2 is the standard result for a triangular lattice but pin it down).
- Sphere check: discrete `E_bend` should reproduce the continuum `8πκ` for a sphere.
Upgrade to the FreeDTS per-vertex form (more accurate, captures C̄ and curvature-tensor anisotropy) once the
pipeline works; crib TriMem's analytic gradient for that form.

### 3b. Area / tension — FreeDTS Eq. 3 + frame tension

Global harmonic area constraint (vesicle / area-elasticity):
```
E_area = (K_A/2) (A_tot − A₀)²            A_tot = Σ_faces area
```
or constant tension (lamellipodium / open patch): `E_τ = τ·A_tot` (FreeDTS uses `−τ·A_p` on the *projected*
area for periodic frames; for a closed cell use total area). `K_A` = area-compressibility modulus.
Vertex force: `F_i = −K_A (A_tot−A₀) · ∇_i A_tot`, with `∇_i A_face = ½ (n̂_face × e_opposite)` summed over
v's incident faces (standard triangle-area gradient).

### 3c. Volume / pressure — FreeDTS Eq. 4

```
E_vol = −ΔP·V + (K_V/2)(V/V₀ − v_t)²       V = (1/6) Σ_faces r_a·(r_b × r_c)   (signed, outward CCW)
```
- `ΔP` fixed pressure difference (turgor); `(K_V/2)(…)²` volume constraint to target `v_t·V₀`.
- Vertex force from the constraint: `F_i = −K_V(V/V₀−v_t)(1/V₀) · ∇_i V`,
  `∇_i V = (1/6) Σ_{faces f∋i} (r_b × r_c)` (the other two CCW vertices of f). (This is the clean,
  triangulated version of the per-node radial hack in the current vesicle code.)
- Osmotic variant (FreeDTS Eq. 5, van 't Hoff) available if we want concentration-driven pressure later.

### 3d. In-plane / fluidity

A **fluid bilayer has ~zero static shear modulus** — so we do **not** use rest-length harmonic edge springs
(those bear shear → a *solid* sheet; that's what the current Tier-1 `NodeLink` mesh is). Instead:
- area-elasticity comes from §3b (resists area change, no shear);
- edges carry only a **tether bound** for mesh conditioning / non-self-intersection: `l_min < l_ij < l_max`
  (typical `l_max ≈ √3·l₀`, `l_min ≈ l₀` range) — a hard/soft wall, *not* a rest-length spring;
- **shear is relaxed by bond flips** (§4) → genuine fluidity.

**Staging caveat:** without bond flips (Stage 1–3, fixed topology) the membrane is effectively a soft
*solid* shell (the conditioning tethers bear some shear). That's fine for first bleb-by-detachment tests;
true fluidity (and area transport into a protrusion) needs flips. Keep conditioning tethers soft so the
fixed-topology shell is as low-shear as possible.

## 4. Fluidity — bond flips (Alexander moves)

The shared edge of two adjacent triangles (i,j,k) & (j,i,m) is flipped to (k,m): cut edge ij, create edge
km, retriangulate the two faces. Accept by **Metropolis** on ΔE_bend (+ tether-bound validity, + no-fold /
min-angle guard). Run as an occasional MC sweep every `N_flip` MD steps (TriMem's HMC pattern). This is what
gives the membrane lateral fluidity (zero shear) without changing vertex count. FreeDTS calls these
*Alexander moves*; trisurf-ng is the cleanest code reference for the move + guards.

## 5. Topology / growth (later)

Native to DTS once half-edge exists:
- **edge split** — insert a vertex at an edge midpoint, 2 faces → 4 (adds area; the Tier-2 link-graph version
  I wrote ports directly onto faces and gets cleaner);
- **edge collapse** — remove an edge/vertex (remodel/shrink);
- **edge flip** already in §4 (quality + fluidity).
Growth = split under sustained area strain / linker-mediated insertion. This is the long-pole, research-y
stage; defer until bending/area/volume/linker behave.

## 6. Actin coupling (BoA-specific — not in the reference codes)

The reference codes have only *inclusions* (proteins embedded in the membrane). Our case — a membrane pushed
and tethered by **external explicit actin** — is ours to build. Two interactions, both with precursors:

- **Implicit linkers (cortex bond, blebbing knob).** Kinetic harmonic tethers between a membrane vertex and
  a nearby filament — bind to filament **sides** (cortex-like), not just ends (generalize the existing
  `nodeAtEnd1` ERM tether / `membraneAnchorReactionFrac`). Parameters: stiffness `k_link`, rest length
  `l_link` (~20–40 nm), `k_on`/`k_off` (force-dependent slip for rupture), areal **density** = the
  cortex-stiffness / blebbing control. Dense+slow → clamped stiff cortex; sparse/ruptured → local detachment
  → bleb.
- **Steric push (protrusion).** Actin barbed tip vs membrane **triangle** (point-vs-triangle, one-sided,
  push along face normal) — BoA's `faceCollideTipVsNodeTriangles`, now robust on a clean triangulation. The
  face normal also gives the correct local direction for any normal-directed force.

## 7. Units mapping to BoA

BoA: lengths **µm**, time **s** (`deltaT≈1e-5`), forces **N** (SI), drag `bTransGam` **N·s/m**, overdamped
move `Δr[µm] = F[N]/(1e6·γ[N·s/m])·Δt[s]`. The reference codes are in reduced units (kT, `l_dts`); convert:

- **Energy:** `k_BT = 1.381e-23·T`. At T=300 K, `k_BT ≈ 4.14e-21 J`.
- **Bending κ:** lipid bilayer **20–25 k_BT ≈ 0.8–1.0e-19 J**. Dimensionless (curvature·length cancels) —
  use the J value directly in `E_bend`.
- **Force:** `F = −∂E/∂r` with `r` in **metres** (positions are µm → ×1e-6 before differentiating), output in
  **N** for `incForceSum`. (Same 1e-6 bookkeeping already sprinkled through the spring code.)
- **Area modulus K_A:** lipid **≈ 0.2–0.3 N/m** (near-inextensible). NOTE: this is *stiff* — at `dt=1e-5`
  and physical drag it may force a smaller `dt` or membrane sub-stepping; may need a softened effective K_A
  for stability. Flag for calibration.
- **Volume/pressure:** turgor `ΔP ~ 10²–10³ Pa`; `K_V` large for near-incompressible cytoplasm.
- **Drag:** `γ = membraneNodeDragScale·6π·η·r_v`, `η = aeta = 0.1 Pa·s` (note: 100× water — BoA's effective
  viscosity), `r_v ≈ ½ vertex spacing`, **`membraneNodeDragScale = 1.0`** (the fix). 
- **Spontaneous curvature C̄:** 0 default (symmetric bilayer).

## 8. Integration into BoA's loop

- Each step, in the single-threaded pre-move region (where `computeVesiclePressure`/`driveMembraneProbe`
  hook in now): `for each Membrane m: m.computeForces()` → accumulate **bending + area + volume + linker +
  face-collision** forces into the vertices' `soaForceSum` (physical N). Then the **existing overdamped move
  phase** integrates them (no separate relaxation loop — the DTS forces are real forces, so
  `NodeLink.subcycleRelaxAll` is *retired* for DTS membranes; big simplification).
- **Bond flips:** an MC sweep every `N_flip` steps on each `Membrane`'s topology (§4).
- Brownian: vertices keep the standard per-Thing thermal forcing (FDT with the physical drag).
- Stability: bending+area can be stiff; if explicit overdamped Euler is marginal, sub-cycle the membrane
  forces (dt/N) or cap per-step vertex displacement (the `membraneNodeMaxDispFrac` clamp already exists).

## 9. Parameter table (starting values, literature)

| symbol | meaning | start value | source/notes |
|---|---|---|---|
| κ | bending rigidity | 20–25 k_BT (~1e-19 J) | lipid bilayer; cortex stiffness comes from actin, not κ |
| κ_G | Gaussian modulus | drop (fixed topology) | restore if topology changes |
| C̄ | spontaneous curvature | 0 | symmetric bilayer |
| K_A | area modulus | 0.2 N/m (may soften) | lipid; watch dt stability |
| A₀ | target area | from icosphere IC | |
| ΔP / K_V | turgor / volume modulus | ~100 Pa / large | cytoplasm near-incompressible |
| l₀, l_max | edge length, tether cap | from IC, `l_max≈√3 l₀` | conditioning only (no rest-length spring) |
| ν | icosphere subdivision | 4 (2562 v) | patch size vs cost |
| N_flip | steps per bond-flip sweep | TBD | fluidity rate |
| k_link, l_link | linker stiffness / length | ~pN/nm, 20–40 nm | ERM-like |
| k_on,k_off | linker kinetics | tune | k_off slip = rupture/bleb |
| linker density | cortex coupling | tune | **the cortex-stiffness / blebbing knob** |
| membraneNodeDragScale | vertex drag scale | **1.0** | the fix; required |

## 10. Staged build plan

1. **Geometry + data structure** (~2 d): icosphere builder, half-edge `Membrane` object (multi-instance),
   vertices as Things. Render the faces + viewer surface toggle (the rendering payoff; correct tipping
   falls out of vertex normals).
2. **Bending + area + volume forces** (~1–2 d): dihedral bending first (analytic force), area & volume
   constraints. Validate: sphere holds at `8πκ`; a vesicle relaxes to known reduced-volume shapes
   (oblate/stomatocyte) — a *standard DTS validation* we inherit from the papers.
3. **Actin linkers + face collision** (~1–2 d): kinetic side-binding linkers + tip-vs-triangle push.
   Demonstrate **bleb-by-detachment** (drop linker density locally → bilayer balloons under pressure).
4. **Fluidity (bond flips)** + **growth (split/collapse)** (~several d, research-y): defer until 1–3 behave.

## 11. Open questions / VERIFY-before-coding

- [ ] Exact per-vertex curvature/`A_v` discretization & weights in FreeDTS source (for the §3a upgrade).
- [ ] The `κ = (√3/2)κ_d` dihedral↔continuum coefficient (Seung–Nelson / Gompper–Kroll).
- [ ] Crib TriMem's analytic gradient for the per-vertex Helfrich form (when upgrading from dihedral).
- [ ] K_A stiffness vs `dt=1e-5` stability — softened effective modulus or membrane sub-stepping?
- [ ] Fixed-topology-first (solid-ish) vs flips-from-start — recommend fixed first.
- [ ] Linker: confirm side-binding model + kinetic (slip) parameters.
- [ ] Licenses of FreeDTS/TriMem/trisurf before copying any code verbatim (we port methods from papers; code
      is reference only).

## 12. References

- FreeDTS — Pezeshkian, *Mesoscale simulation of biomembranes with FreeDTS*, Nat. Commun. 15, 548 (2024).
  https://github.com/weria-pezeshkian/FreeDTS · open access: https://pmc.ncbi.nlm.nih.gov/articles/PMC10792169/
- TriMem — Siggel, Kehl, Reuter, Köfinger, Hummer, *TriMem: A parallelized hybrid MC software …*, J. Chem.
  Phys. 157, 174801 (2022). https://trimem.readthedocs.io/
- trisurf-ng — Penić, Fošnarič et al. (cluster-trisurf fork: https://github.com/yoavrv/cluster-trisurf)
- Gompper & Kroll, *Triangulated-surface models of fluctuating membranes*, in Nelson/Piran/Weinberg,
  "Statistical Mechanics of Membranes and Surfaces" — the canonical methodology + bending discretization.
- Seung & Nelson, Phys. Rev. A 38, 1005 (1988) — discrete bending ↔ continuum κ.
- Helfrich, Z. Naturforsch. C 28, 693 (1973) — the curvature elastic energy.
