# Membrane subsystem — revive, verify & scope (2026-06-13)

**Bottom line:** the membrane is a **working** BoA subsystem. Revived and run on the
post-SoA / post-RNG-consolidation / post-`taForce` tree, it **still behaves correctly** —
no bit-rot regression. The sheet holds, the NodeLink spring wave and the membrane
relaxation loop run, node integration is sane, and `membraneFilMeshCollisions` engages
filaments and produces a physically correct response. The only failure seen was a
self-inflicted over-driven blowup (a 500×-default nucleation stress test), which is a
load/integrator-stability artifact, not a code defect. Evidence:
`RUN_LOGS/2026-06-13_membrane_revive/revive_results.txt`.

For the **whole-cell ring** use case the membrane gives more than a flat sheet (closed
surfaces, turgor, a constriction force *hook* all exist in test form), but the pieces a
constricting ring needs — **wired spring topology on a closed surface**, **membrane↔ring
force coupling**, and **ring-driven (not hardcoded) constriction** — are absent or
disabled. This is a **build** gap on a sound foundation, not a repair job.

---

## 1. Revive: how to run it today

The membrane has **no parameter-file launch path**. It is gated by a single hardcoded
flag. To run it you must:

1. `boxOfActin/Env.java`: set `nodeLinkTesting = true` (default `false`, line ~267).
   Read once in `BoxOfActin.makeInitialThings()` (~line 3200) → calls
   `StickyNode.makeSheetHexPackedNodes()`. **That is sufficient** — see the auto-link
   note below.
2. *(optional)* `StickyNode.makeSheetHexPackedNodes()` has a commented-out *manual*
   NodeLink loop (~lines 712–731). **Uncommenting it is NOT required**: with
   `collideProteinNodes` on (default true) the sheet **self-assembles its links by
   proximity collision** (§3, "Auto-linking"). Verified: the hex sheet wired **2,581
   NodeLinks with the manual loop left commented.** Uncomment it only if you want the
   exact deterministic hex bond pattern instead of the collision-discovered one.
   (`makeSheetSquarePackedNodes()` does keep an active manual loop, but does not pin its
   edges.)
3. Run on **CPU** (the GPU path deliberately routes membrane configs back to CPU — see §6):
   ```
   java --enable-preview -Xmx2G -cp "<tornado-api>:libs/*:." \
        BoxOfActin -r -pf ParameterFiles/membraneRevive -3js <outdir>
   ```
   `ParameterFiles/membraneRevive`, `membraneReviveFils`, `membraneReviveFilsMod` were
   added by this work and capture the three runs below. They set `simOutsideBug:false`
   (no Listeria — otherwise `makeCrucible()` builds a bug), suppress
   initialFilaments/minifils/nodes, and size a 4×4×2 µm box around the ~2 µm sheet. **They
   only take effect with the two code edits above.**

> The three param files are kept as run artifacts; **all code edits were reverted** — the
> tracked tree is pristine (`git diff` empty). The flag flip + link uncomment are
> *enablement*, not bug fixes, so they were not left in.

**Latent (pre-existing, not migration) bug noted, not fixed:** in the hex link loop the
`j+1` neighbour is guarded by `(j!=nodeCols-1)` where it should be `(j!=nodeRows-1)` —
harmless only while `nodeCols==nodeRows`. Keep sheet dims square, or fix the guard, before
using non-square sheets.

## 2. Verify: it still works (no regression)

Three CPU runs (full data in the run log):

| Run | Config | Result |
|---|---|---|
| **1** | sheet only, 500 steps | Exit 0, **no NaN**. Pinned x/y edges *identical* across all frames; interior z-ripple bounded to ±0.076 µm. **Sheet holds.** NodeLink wave 6.9 s, membraneMove wave 2.2 s, gather stable at 1357 dirty/step. |
| **2** | sheet + nucRate=50/s (500× default) → 37.8k filaments | Exit 0. `membraneFilMeshCollisions` **engaged: 152.8M pair-checks, 353k collision forces.** Sheet bulges *monotonically* in +z (correct response) and stays clean through ~30k segs; then a few massively-overloaded nodes overshoot the explicit integrator → NaN propagates to ~109 linked neighbours. **Over-driven blowup, not bit-rot** (segments never NaN; run 1 proves the core is sound). |
| **3** | sheet + nucRate=5/s → 3.3k filaments | Exit 0, **badNodes=0 every frame**, 45k collision forces, membrane bulges smoothly 0→0.37 µm and **holds**. Confirms the fil-membrane path is healthy at sane loads. |

**What this exercised, all confirmed functional under the migrated infrastructure:**
NodeLink spring force wave (phase 14), the iterative membrane relaxation loop (≤30
sub-passes/step), `StickyNode.membraneNodesMove` (phase 15), `ProteinNode` free-body
integration, `gatherThreadAccumulators` over membrane forces, `incForceSum`/
`incForceSumSlot` (the post-gather direct-write path used inside the membrane move pass),
and `membraneFilMeshCollisions`.

**Why no bit-rot, mechanistically.** The SoA/RNG audit flagged three theoretical risks;
the runs clear all three: (a) membrane nodes inherit the consolidated per-worker RNG via
the base `calcRandomForces(WorkerScratch)` — they don't override it, so Brownian forcing
worked; (b) `StickyNode/ProteinNode.calculateProperties()` already call `pushDragToSoa()`,
so drag tensors are SoA-consistent; (c) the post-gather `incForceSumSlot()` writes in
`internalPressure`/`fakeConstrictingRing` land correctly because membraneMove runs after
`gatherThreadAccumulators`. The collision routine reads SoA-aware accessors
(`coordAsPt3D`, `end2Pt`) throughout.

## 3. What the membrane models

A **particle–spring network**, not a continuous mesh:

- **Nodes** = `StickyNode` (extends `ProteinNode` extends `Thing`): a sphere (default
  r=0.05 µm) carrying up to 9 orientable **sticky points** whose geometry is set by
  `valence` (dimer / linear / planar-3/4/5/hex / 9-ring / 3D-sphere-6). Integrated as an
  overdamped free body with a deliberately tiny drag (`membraneNodeDragScale=1e-10` →
  very mobile) and down-scaled Brownian forcing (`membraneBrownianScale=1e-10`).
- **Links** = `NodeLink`: a viscous **zero-rest-length contractile spring** between two
  bound sticky points (`applyTransForce`: force ∝ full separation, always pulling the two
  points together). With `maxStickies=9` a node can bind that many neighbours.
- **Auto-linking (self-assembly).** Links do **not** have to be wired by hand. In the
  collision phase, when `collideProteinNodes` is active (default true),
  `ProteinNode.nodeMeshCollisions` → `checkNodeCollision` examines every node pair sharing
  a mesh cell; for two non-fully-bound `StickyNode`s whose centres are within the sum of
  their radii it calls `StickyNode.ckToLink`, which finds the closest pair of *free* sticky
  points within one radius and makes a `NodeLink`. So if you place nodes in contact, they
  **discover their neighbours and wire themselves together** — and keep doing so as they
  move (a node links up to its `valence` before `fullyBound()` stops it). `linkMembraneTime`
  (default 0) gates an optional pre-link settling window: while `simulationTime <
  linkMembraneTime` sticky nodes only soft-collide (no linking), letting them jostle into
  position first; after it they begin binding. The manual loops in the *sheet* factories are
  just an explicit alternative to this; the *closed-surface* factories rely on it entirely.
- **Relaxation:** each step iterates membraneLinks→gather→membraneMove up to
  `maxMembranePasses` (30) times until `NodeLink.maxStrain < membraneMaxLinkStrain` (0.1×
  radius). This is an *iterative constraint solver*, not a single force eval.
- **Forces present:** link springs; Brownian; `membraneFilMeshCollisions` (steric
  node↔filament-tip push, `attnFactor=0.3`); and — only in `sphericalGeometry` mode —
  `addSphericalConstraintForce` (radial restoring to `membraneCellRadius`, i.e. holds a
  closed shape) and `internalPressure` (outward turgor, `outwardCellForce=1e-22 N`).
- **Geometry factories (`StickyNode`):** flat sheets (`makeSheetHexPackedNodes`,
  `makeSheetSquarePackedNodes`), and **closed/near-closed surfaces**
  (`makeSphereOfNodes`, `makeCylinderOfNodes`, `makeSphereLikeCylinderOfNodes{,2}`,
  `makeIcosahedronOfNodes`). The sphere-like-cylinder is a capsule cell shape with fixed
  polar caps.
- **Biochem hook:** `StickyNode` nodes flagged `iAmHotRho` nucleate Arp2/3 filaments at the
  surface (the "Rho-hot" patch in the sheet factory) — this is how filaments appear at the
  membrane in runs 2–3. There is also a dormant hot-spot origination/spread model
  (probabilities currently 0).
- **Rendering:** `ThreeJSWriter` emits nodes as spheres in a `"nodes"` array; **NodeLinks
  are not rendered** — membrane connectivity is invisible in the viewer. (Verification here
  used per-frame node coordinates, not the viewer.)

## 4. Gap analysis for the whole-cell ring

A cytokinetic ring constricting a cell needs: a **deformable closed cortex**, nodes that
the ring **anchors to / pulls on**, and a **constriction response** driven by the
actomyosin ring. Status:

| Need | Present? | Detail |
|---|---|---|
| Closed surface geometry | **Yes** | Sphere / capsule / cylinder factories place nodes on a closed surface. They don't *manually* wire links because they rely on **auto-linking** (§3): nodes in contact self-assemble NodeLinks by proximity, and the radial `addSphericalConstraintForce` holds the closed shape. The open item is *tuning* — packing the surface (`membraneCellPackingFactor`) so neighbours fall within binding range — not writing link code. **Worth a confirming run** (none of the closed-surface factories were exercised here; only the flat sheet was). |
| Deformable / elastic shell | **Sheet verified; shell very likely** | Elastic behavior is *directly verified* on the flat sheet. A closed shell uses the same NodeLink springs + auto-linking, so it should behave the same once packed to link — but that was not run, so treat it as high-confidence-untested. |
| Turgor / volume | **Force hook only** | `internalPressure` (outward) + `addSphericalConstraintForce` (radius restoring) exist; no true enclosed-volume conservation. |
| Node ↔ ring-filament coupling | **No (collision only)** | `membraneFilMeshCollisions` is *steric push* between filament tips and nodes — filaments bounce off the membrane, they are not **bound** to it. Filaments can be anchored at a node only when that node *nucleated* them (`end2Node`/`nodeAtEnd2`, minus-end formin anchor). There is **no tether between membrane nodes and an independent contractile ring**, so a ring cannot pull the membrane inward. |
| Constriction response | **Disabled hook** | `iAmConstricting` + `fakeConstrictingRing()` apply a **hardcoded** inward force (magnitude 1e-20 N — token/off), and the `constrictingRing = nodeCols/2` designation is commented out. A constriction *primitive* exists, but it is a manual fake force, **not driven by ring tension.** |

**Net:** the membrane supports a **deformable, anchored, link-elastic flat sheet that
filaments push against**, plus the building blocks of a closed cortex — closed-surface node
placement, **self-assembling links (auto-linking)**, a turgor hook, and a fake-constriction
hook. A **linked closed cortex is reachable** (auto-linking + the sphere/capsule factories
should produce one once packed to link), but it has **not been run**, and the two pieces a
constricting ring genuinely needs are still missing: **membrane↔ring mechanical coupling**
(today it's steric collision only, no tether) and **ring-driven constriction** (today it's a
hardcoded 1e-20 N fake force). These are independent build tasks, none requiring new core
physics infrastructure.

## 5. Recommended next step

**Use-as-is for sheet-scale tests; a scoped build (not a regression fix) for the ring.**
The subsystem is healthy — no repair needed. The natural next prompt is a **closed-cortex +
ring-coupling build**, in three stackable increments:

1. **Run a closed surface and confirm it self-wires + holds** (start from
   `makeSphereLikeCylinderOfNodes` — it already has fixed polar caps and a `constrictingRing`
   marker). No link code to write: rely on auto-linking, tuning `membraneCellPackingFactor`
   so neighbours fall within binding range, and verify the shell self-assembles and holds
   under turgor like the flat sheet does. *Smallest, highest-value first step.*
2. **Couple a ring to the cortex:** either tether ring filaments to the nearest membrane
   nodes (a node↔filament `NodeLink` analog — new connector), or transmit ring contractile
   force to `iAmConstricting` nodes, replacing the 1e-20 fake with the measured ring tension.
3. **Stability:** runs 2–3 show the explicit integrator tolerates sane loads but not a 37k
   over-driven thicket. A constricting ring concentrates force on few nodes — add a
   per-step displacement clamp (or substep) on membrane nodes before the ring drives them
   hard, to avoid the run-2 overshoot mode.

Also worth a small separate ticket: **render NodeLinks** in `ThreeJSWriter` so membrane/ring
work is visually debuggable (currently links are invisible).

## 6. GPU note (high-level — for a later decision, not scoped here)

CPU-only today: the chained move plan computes `filFilBroadphaseActive` with `!anyStickyNode`
(`GPUMoveThing` ~line 3898), and there is no device kernel for the NodeLink wave or the
membrane relaxation loop, so any sticky-node config runs the host path.

It *would* ride the **`RULE_NODE` residency playbook just landed for the protein-node path**
(2026-06-12, `NODE_GPU_PORT_RESIDENCY.md`): `StickyNode` **is** a `ProteinNode`, so its body
already integrates on the shared `moveThingKernel`, and the pairwise `NodeLink` springs map
to a one-thread-per-node device kernel reading resident pose exactly like `nodeTetherKernel`.
**The one genuine wrinkle is the iterative relaxation loop** (≤30 membraneLinks→gather→move
sub-passes/step): unlike the single-eval node tethers, it would need either an on-device
fixed-iteration sub-loop or a single-pass stiffness reformulation. Not a blocker, but the
part to think about before porting. Defer until the closed-cortex + ring build (§5) defines
the real workload.
