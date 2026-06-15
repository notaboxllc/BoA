# Membrane model — design notes (2026-06-13)

*Notes comparing BoA's particle–spring membrane to the modeling literature, the upgrade menu, and
the dynamic-area / membrane-flow mechanism. Intended to inform the v1 ring build and the v2
membrane plugin. References (author–year) at the end; all claims paraphrased.*

---

## 1. Where BoA's membrane sits in the modeling landscape

**BoA's model:** a coarse-grained **particle–spring network** — `StickyNode`s connected by
`NodeLink`s — built generatively by **proximity auto-linking**, with **zero-rest-length contractile
springs**, optional turgor (`internalPressure`) and a spherical-constraint force. Geometry from
closed-surface factories (sphere / capsule / icosahedron) or flat sheets.

**The landscape splits into two camps** (the Cortes & Maddox 2018 review draws this line explicitly:
active-gel continuum theory vs agent-based modeling):

- **Continuum active-gel / active-surface** (Jülicher, Salbreux, Turlier, Zumdieck): the cortex as a
  thin active viscoelastic surface with myosin-driven tension. Faithful to cortical mechanics and
  flows. But it is a *field* — it does not host discrete molecular agents.
- **Phase-field active-shell** (Li & Lowengrub; recent active-shell work): an implicit interface with
  an active surface force proportional to actomyosin concentration; handles the furrow and the
  topology change of fission; runs on GPUs via finite differences. Again a field / proxy force — no
  discrete filaments.
- **Immersed boundary:** membrane as IB points coupled to a fluid solver.
- **Continuum FEM / Helfrich:** the membrane as an elastic surface with a bending modulus; needs
  remeshing under the large furrow deformation.
- **Triangulated spring-network / red-blood-cell models** (Discher/Boey; Fedosov–Karniadakis): a
  discrete particle–spring mesh with shear, bending, area, and volume terms. **This is BoA's family.**

**Verdict.** BoA's particle–spring network is the **right family** for an agent-based, GPU-resident,
molecular-resolution simulation. It is the *only* class that natively couples to discrete filaments,
motors, and nodes (they are all particles; coupling is just more springs/tethers) and that rides the
`RULE_NODE` GPU residency playbook already proven for protein nodes. The continuum / phase-field /
active-gel approaches model the cortex-as-surface better *in isolation*, but cannot host the
molecular agents — the wrong tool for this simulation. BoA is in the same family as the
gold-standard RBC models; it simply lacks three of their terms (next section).

---

## 2. Upgrade menu (from the RBC spring-network literature)

The Fedosov–Karniadakis RBC models represent the membrane as a triangular mesh with shear in-plane
energy, bending energy, and area + volume conservation, with macroscopic moduli imposed via
semi-analytic theory and matched to optical-tweezers experiments. They add three things BoA lacks —
adopt as the science requires, not wholesale:

1. **Explicit bending.** A pairwise spring network resists stretching but **not bending** — it folds
   like cloth, not a shell. RBC models add a bending energy as a function of the **dihedral angle
   between neighbouring faces** (Gompper formulation). This needs a real *triangulation* (defined
   faces sharing edges) → the **subdivided-icosahedron** factory, not proximity auto-linking, when
   bending matters. BoA's `addSphericalConstraintForce` is a global shape-holding hack, not a local
   bending modulus.

2. **Area + volume constraints.** RBC models enforce both local and global area and **enclosed-volume**
   conservation. BoA has `internalPressure` + a radial constraint but **no true volume conservation**.
   A constricting cell conserves volume until the septum forms — this matters for the ring.

3. **Calibration + a stress-free reference.** Fedosov maps spring parameters → macroscopic moduli
   analytically, and uses a **stress-free** construction (each spring's rest length set to its actual
   triangulated edge length) — because a non-developable surface *cannot* be triangulated with
   equal-length springs without producing local stress artifacts.
   - **BoA distinction worth being deliberate about:** `NodeLink` is a **zero-rest-length contractile
     spring** — always pulling, with no intrinsic rest shape. That is a *tensed cortex*, not an
     *elastic shell*; BoA holds shape via the constraint force + packing, not via spring rest lengths.
     Choosing **tensed** (zero rest length) vs **elastic** (non-zero, stress-free rest lengths) is a
     real modeling decision, not a detail.

---

## 3. The missing capability — dynamic area / membrane flow

BoA's largest gap for a lipid-bilayer surface is the ability to **supply area / flow with a
time-constant**. The biology gives a clean, directly implementable picture — and it both validates
and sharpens the "insert nodes into strained membrane" instinct.

### 3a. Two-phase area supply: folds, then insertion
- **Phase 1:** unfolding of membrane folds / blebs / reservoir at ~constant tension, expanding area
  by >20% (Gauthier & Sheetz, PNAS 2011).
- A **tension spike** when the reservoir is exhausted **triggers exocytosis** → **Phase 2:** insertion
  of new membrane material. The causal link is direct: artificially raising tension halts protrusion
  and sets off an exocytotic burst.
- The reservoir is folds, blebs, microvilli, caveolae; Surface Area Regulation buffers tension, and
  tether reservoirs can respond very fast (<0.1 s), suggesting purely physical (unsignaled) regulation.
- **→ BoA's two proposed mechanisms (folds, insertion) are the real consecutive phases, switched by a
  local tension threshold** — not alternatives.

### 3b. Flow is slow and LOCAL — the time-constant
- Shi & Cohen, *Cell Membranes Resist Flow* (Cell 2018): tension propagates **diffusively** with
  Dσ ≈ 0.024 µm²/s (HeLa) — so low that imbalances are **essentially static** over relevant
  timescales; local perturbations stay local for **tens of minutes**; local tension → local exocytosis
  / channel activation (no long-range signaling).
- **Cause:** cytoskeleton-bound transmembrane proteins act as **immobile obstacles** → the membrane
  behaves rheologically **like a gel, not a fluid**. Obstacle density and arrangement set propagation
  (fast in cortex-detached blebs; faster in axons' aligned obstacle arrays).
- **→ BoA's `StickyNode`s — the membrane–cortex attachments anchoring filaments — *are* the obstacles.**
  The slow, local flow emerges from node density. **Design rule: do NOT globally equalize tension each
  step. Supply area locally where strained; let gradients persist.**

### 3c. Tension ↔ protrusion coupling (filopodia / lamellipodia)
- Forming and maintaining protrusions requires a force **opposing** membrane tension, and tension
  **tunes the actin**: higher tension → longer, more oriented filaments and faster, organized
  lamellipodia; lower tension → shorter filaments and multiple disorganized lamellipodia.
- **→ Bidirectional coupling:** a pushing bundle strains the membrane; the membrane's tension regulates
  the bundle. A tension-bearing, insertable network delivers this; steric collision alone cannot.

### 3d. Modeling translation — "insert nodes into strained membrane"
- **Local tension signal** is already present: `NodeLink` strain (per-node net link tension). An
  over-strained patch is a high-tension region.
- **Below threshold:** draw from a **finite local reservoir** at ~constant tension (let links extend
  within an area budget) — Phase 1.
- **Above threshold** (the tension spike): **insert a `StickyNode` + auto-linked links** — the
  exocytosis analog — **rate-limited**: an insertion probability/step or relaxation time *is* the
  time-constant. Tune it toward the gel regime (slow), not the fluid regime.
- **New links stress-free:** rest lengths set to the local relaxed geometry, or insertion injects the
  non-developable-triangulation stress artifact (§2.3). New material enters at the local tension.
- **Reverse:** remove nodes/links in slack, low-tension regions (endocytosis / re-folding). Insert
  where taut, retract where slack — true area *regulation*, not monotonic growth.
- **Filopodia/lamellipodia** = strained tips/edges where a pushing bundle outruns local area → insert
  at the tip and let the patch's tension push back on the bundle.

---

## 4. Implications for v1 and v2

- **v1 (BoA ring build).** Immediate needs (per `MEMBRANE_SCOPING.md` §4–5) are a **closed cortex** and
  **ring↔cortex coupling** on the existing static network. Bending / area-volume / dynamic-area are the
  *next* layers — add dynamic area once a static cortex can be constricted. The zero-rest-length
  contractile spring is fine for a tensed cortex; revisit if an elastic shell is wanted.
- **v2 (membrane plugin).** A node-position SoA + a link list is the natural component-array form
  (GPU-resident, `RULE_NODE` playbook). Bending adds a **face list**; insertion/removal are **topology
  operations** on the arrays. The membrane is a clean v2 plugin — no core-engine changes.
- **GPU.** Spring forces ride the residency playbook. The iterative relaxation loop (≤30 sub-passes/step)
  needs an **on-device fixed-iteration sub-loop or a single-pass stiffness reformulation** (per
  `MEMBRANE_SCOPING.md` §6). Dynamic insertion/removal is a **topology-change event** — handle at frame
  boundaries, like other topology dirtying.

---

## References (paraphrased)

- **Fedosov, Caswell & Karniadakis (2010).** A multiscale red blood cell model with accurate mechanics,
  rheology, and dynamics. *Biophysical Journal* 98(10):2215–25. — Triangular mesh: shear + bending +
  area + volume; semi-analytic modulus mapping.
- **Fedosov, Caswell & Karniadakis (2010).** Systematic coarse-graining of spectrin-level RBC models.
  *Comput. Methods Appl. Mech. Engrg.* — Stress-free construction; corrected modulus theory.
- **Gompper & Kroll.** Dynamically triangulated membranes; dihedral-angle bending energy.
- **Li & Lowengrub et al.** Modeling cytokinesis driven by the actomyosin contractile ring — phase-field
  active surface, GPU finite-difference.
- **Jülicher, Salbreux, Turlier; Zumdieck et al. (2005).** Active-gel / active-surface cortex; continuum
  ring formation. **Cortes & Maddox (2018).** Unite to divide (review) — active gel vs agent-based.
- **Gauthier, Masters & Sheetz (2011, PNAS).** Temporary increase in membrane tension coordinates
  exocytosis and contraction during spreading — two-phase area supply; reservoir unfolding then
  exocytosis; tension-spike trigger.
- **Membrane reservoir at the cell surface** (review) — folds/blebs/microvilli/caveolae as the reservoir;
  Surface Area Regulation.
- **Shi, Graber, Baumgart, Stone & Cohen (2018, Cell).** Cell Membranes Resist Flow — Dσ ≈ 0.024 µm²/s;
  obstacle/gel model; local-not-global tension. **Shi & Cohen** (axons) — obstacle-array dependence of
  propagation.
- **PNAS (2011) commentary / nematode-sperm work** — membrane tension regulates filament length and
  lamellipodial organization.
