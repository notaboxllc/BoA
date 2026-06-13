# BoA v1 — Finish Line (pre-v2 checklist) — *rev. 2026-06-13*

**Purpose.** Define the bounded work that takes BoA v1 as far as it usefully goes — fast,
trustworthy, and carrying the capabilities the assays need — so it can be **tagged as the
biology-production release** and left to run science while the v2 SoA/ECS port proceeds on
its own branch.

**Status at this revision:** the **speed sweep is complete** (v1 speed wall reached), the
**protein-node minimal assay + GPU residency are complete**, and the **consolidation to main
is done** (the `benchmark-contractile-dense` block is merged). The **membrane subsystem was
revived and CPU-verified** (2026-06-13) and is now a tracked finish-line subsystem (Part M).
What remains: the **membrane StickyNode GPU port** (Part M — the new pre-v2 gate), the
**biology-readiness gates** (Parts B/C), the **full-ring couplings** for whole-cell work,
then **freeze**.

## Guiding principle (unchanged)
Do the contained levers; stop at the architectural wall. Ready = speed **and** calibration
**and** long-run stability **and** the capabilities the assays need. The biology runs on
frozen-v1 become v2's regression suite.

---

## Part A — Speed — DONE (v1 speed wall reached)
All three superlinear/large levers landed their contained v1 win or bailed; everything left
is v2-structural. GPU/CPU **0.699 at 8x** (dense contractile), **all forces device-resident**.

- [x] **A1 . `recompute`/`onStepStart`** — `poseAudit` scan gated on `occupantsChanged`
  (-37% recompute @8x), proven byte-identical via the skip-invariant verifier. The residual
  `classify` ~N^2 is non-redundant -> **incremental classification deferred to v2**.
- [x] **A2 . `gridScatter`** — serial scatter parallelized via per-cell-chunk ownership
  (atomic-free, CSR bit-identical), 3.45x @8x, slope 9.5->5.8x. The incremental-grid
  slope-kill needs a different data structure -> **v2**.
- [x] **A3 . force-offload** — clean **bail**: diagnosis proved **every resident-state force
  is already a device kernel** (all CPU fire-counts = 0). The ~30 ms residual is OOP-graph
  dispatch shell + dead bookkeeping, not force -> **CPU-shell elimination is a v2 lever**.
- [x] **A4 . live-subrange fil-fil** — **skipped** (sub-2 ms amortized polish; not worth a
  session at the wall).

---

## Part N — Protein-node path — minimal assay + GPU residency DONE
The biology-readiness capability for the **whole-cell contractile-ring** work (nodes, not
minifilaments — the experimentally-observed ring architecture). NB: these are `ProteinNode`
myosin-cluster nodes — **distinct from the membrane `StickyNode`s in Part M.**

- [x] **N1 . CPU node contractile assay** (Stage 1) — single-sphere bridge; **thermal search
  is essential and load-bearing** (= the real search-capture: heads diffuse to find
  filaments). Oracle: avgBound ~5-7, avgTension ~2.0 pN, controls clean.
- [x] **N2 . GPU residency port** (Stage 2, `RULE_NODE`) — search reproduces on device
  (avgBound 7.08 vs 6.64; `boundMotors` 0->6-10), tension band matched, controls clean,
  force-exactly-once (`cpuNodeTetherApplyCt=0`), no regression to the minifilament GPU path.
  `xToX` surface rotation + 1-step-lagged reaction, CPU path behind `BOA_NODE_GPU=0`.
- [ ] **N3 . full-ring couplings** (for whole-cell ring, beyond the minimal assay) —
  **deferred**: node birth/death (`nodeLifetime`), formin nucleation (`kNodeNuc`),
  body-frame `bYMove`, membrane node subclasses. Needed for the flagship ring sim; scope
  later (some may be v2).
- [ ] **N4 . myosin turnover / lability** (biology enhancement, optional) — the
  experimentally-supported lability is **exchange with the cytosolic pool** (on/off
  turnover), **not** free surface diffusion; plus **node-on-membrane diffusion** (~20 nm^2/s)
  for ring condensation. A `nodeLifetime`-style per-myosin exchange rate is the grounded
  version. Pairs with N3 toward the full ring.

---

## Part M — Membrane subsystem — REVIVED + CPU-verified; GPU port is the pre-v2 gate
Revived and scoped 2026-06-13 (`MEMBRANE_SCOPING.md`). A **particle–spring network**
(`StickyNode`/`ProteinNode` bodies + zero-rest-length contractile `NodeLink` springs, solved
by an iterative relaxation loop), necessary to both the whole-cell cortex/ring work in v1 and
as a v2 deformable-boundary library entry. **The StickyNode-onto-device port (M2) is the
stated pre-v2 gate** ("v2 migration does not begin until the membrane runs on GPU").

- [x] **M0 . Revive + no-regression verify** — three CPU runs on the post-SoA / post-RNG /
  post-`taForce` tree: the hex sheet holds, the NodeLink spring wave (phase 14) + iterative
  relaxation loop (≤30 sub-passes) + `StickyNode.membraneNodesMove` (phase 15) + free-body
  integration + `gatherThreadAccumulators` + `membraneFilMeshCollisions` all functional. The
  only failure was a self-inflicted 500×-nucleation over-drive (integrator stability, not
  bit-rot). All code edits reverted; tree pristine. (`RUN_LOGS/2026-06-13_membrane_revive/`.)
- [ ] **M1 . Closed-cortex confirming run** — exercise a closed surface (start from
  `makeSphereLikeCylinderOfNodes` — fixed polar caps + a `constrictingRing` marker), confirm
  it **self-wires by auto-linking** and **holds under turgor** like the flat sheet does. No
  link code to write; the open item is tuning `membraneCellPackingFactor` so neighbours fall
  within binding range. *Smallest, highest-value first step; prerequisite workload for M2.*
- [ ] **M2 . StickyNode GPU residency port** — **THE pre-v2 gate.** StickyNode body rides the
  shared `moveThingKernel` (it *is* a `ProteinNode`, so the body half follows the `RULE_NODE`
  template from N2); the pairwise `NodeLink` springs map to a one-thread-per-node device
  kernel reading resident pose. **The genuine wrinkle** (not covered by N2's single-eval
  `nodeTetherKernel`): the ≤30-pass iterative relaxation loop needs either an on-device
  fixed-iteration sub-loop or a single-pass stiffness reformulation. **Net-new — no StickyNode
  device work has started.** Today the chained move plan routes any sticky-node config back to
  CPU (`filFilBroadphaseActive = !anyStickyNode`).
- [ ] **M3 . Membrane↔ring coupling** (for whole-cell ring; parallels N3) — today only
  *steric* (`membraneFilMeshCollisions` pushes filament tips off nodes; no tether). Either
  tether ring filaments to the nearest membrane nodes (a node↔filament `NodeLink` analog — new
  connector) or transmit ring contractile force to `iAmConstricting` nodes, replacing the
  hardcoded 1e-20 N `fakeConstrictingRing()` fake with measured ring tension. **May ride v1 or
  v2** like N4.
- [ ] **M4 . Membrane-node stability clamp** — runs show the explicit integrator tolerates
  sane loads but not a 37k over-driven thicket; a constricting ring concentrates force on few
  nodes. Add a per-step displacement clamp (or substep) before the ring drives them hard.
- [ ] **M5 . (small) Render NodeLinks in `ThreeJSWriter`** — connectivity is currently
  invisible in the viewer; needed to make membrane/ring work visually debuggable.

> **Latent (pre-existing, non-migration) bug, noted not fixed:** the hex link loop guards the
> `j+1` neighbour with `(j!=nodeCols-1)` where it should be `(j!=nodeRows-1)` — harmless only
> while `nodeCols==nodeRows`. Keep sheets square or fix the guard before non-square sheets.

---

## Part B — Long-run stability (biology runs are long) — PENDING
- [ ] **B1 . `POSE_DELTA_CAP` overflow / host leak** — fatal in a long assay, invisible in a
  300-step benchmark. Measure dirty-slot demand -> startup-size the cap -> non-leaky fallback.
- [ ] **B2 . long-run soak** — run the actual assay configs full-length on CPU+GPU; watch
  host RSS, VRAM creep, NaN-over-time, determinism drift.

---

## Part C — Calibration (trustworthy instrument) — PENDING
- [x] **C1 . GPU minifilament cohesion** — **already solved** (the 701-hunt `WorkerGrid`
  fix); `keepMyosinsOnSurface` reclassified from "dead" to **load-bearing for nodes** and now
  device-ported in N2.
- [ ] **C2 . gliding-assay IC** — confirm the +x-end inward pad didn't revert. The
  consolidation gliding regression was the intended check; **confirm it actually verified the
  pad** now that the merge has happened.
- [ ] **C3 . F-actin units convention** — reconcile the 8x `boxVolume` before any uM target.
- [ ] **C4 . gliding baseline + phalloidin regime** — switch for biological gliding; keep
  current defaults for port validation.
- [ ] **C5 . contractile density / regime** — bundling vs percolation; specify before the
  assay design (applies to the node ring too).

---

## Consolidation — DONE
- [x] Speed sweep + node assay + node GPU port — the `benchmark-contractile-dense` block —
  **merged to main.** (Cross-config gliding regression was the merge gate and also the
  intended C2 check — see C2.) Branch-state cleanliness + journal cleanup to confirm against
  the freeze criteria in Part D.

---

## v2-deferred lever set (recorded)
Incremental `classify`; incremental grid; CPU-shell elimination; full-ring node couplings
(N3); membrane↔ring coupling (M3) and membrane stability (M4) may ride v1 or v2; the
data-model redesign. The biology lability enhancements (N4) can ride v1 or v2. The membrane
closed-cortex becomes a v2 deformable-boundary library entry regardless — but its **v1 GPU
port (M2) is the gate**, not the v2 entry.

---

## Part D — Freeze criteria (tag `biology-production-v1`)
- [x] Part A landed (or deferred-to-v2); node minimal assay + GPU residency landed.
- [x] Consolidated to main (Consolidation section). Confirm branch state clean before tag.
- [ ] Membrane StickyNode GPU residency ported + verified (M2) — the stated pre-v2 gate (or
  explicitly scoped to v2 if jba defers).
- [ ] Part B soak passes on the production path.
- [ ] Part C closed (C2/C3 minimum; C4/C5 for whichever assay runs first).
- [ ] `JOURNAL.md`/`CLAUDE.md` updated with the frozen state + validation numbers.
- [ ] **Tag** the release; branch the pre-v2 snapshot. Assays run on the tag; v2 on its own.

---

## What this unlocks
Gliding assay (motor velocity), minimal contractile assay (minifilament), **node contractile
assay (CPU+GPU, done)**, laser-tweezers force readouts, the deformable **membrane cortex
(CPU-verified; GPU port pending)** — and the path to **whole-cell ring formation** (needs N3
+ the membrane↔ring coupling M3). Each is a regression target v2 must reproduce.
