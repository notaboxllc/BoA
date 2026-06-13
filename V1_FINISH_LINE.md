# BoA v1 — Finish Line (pre-v2 checklist) — *rev. 2026-06-12*

**Purpose.** Define the bounded work that takes BoA v1 as far as it usefully goes — fast,
trustworthy, and carrying the capabilities the assays need — so it can be **tagged as the
biology-production release** and left to run science while the v2 SoA/ECS port proceeds on
its own branch.

**Status at this revision:** the **speed sweep is complete** (v1 speed wall reached) and the
**protein-node minimal assay + GPU residency are complete**. What remains: **consolidate to
main**, the **biology-readiness gates** (Parts B/C), the **full-ring couplings** for
whole-cell work, then **freeze**.

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
minifilaments — the experimentally-observed ring architecture).

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
- [ ] **C2 . gliding-assay IC** — confirm the +x-end inward pad didn't revert (folds into the
  consolidation gliding run).
- [ ] **C3 . F-actin units convention** — reconcile the 8x `boxVolume` before any uM target.
- [ ] **C4 . gliding baseline + phalloidin regime** — switch for biological gliding; keep
  current defaults for port validation.
- [ ] **C5 . contractile density / regime** — bundling vs percolation; specify before the
  assay design (applies to the node ring too).

---

## Consolidation — PRESSING
Speed sweep + node assay + node GPU port are **all on `benchmark-contractile-dense`,
unmerged** — a large block of validated work. Cross-config gliding regression (the merge
gate, also covers C2) -> merge to main -> journal cleanup. Confirm the actual branch/merge
state first (some things may already be merged). Do this before the freeze.

---

## v2-deferred lever set (recorded)
Incremental `classify`; incremental grid; CPU-shell elimination; full-ring node couplings
(N3); the data-model redesign. The biology lability enhancements (N4) can ride v1 or v2.

---

## Part D — Freeze criteria (tag `biology-production-v1`)
- [x] Part A landed (or deferred-to-v2); node minimal assay + GPU residency landed.
- [ ] Consolidated to main; branch state clean.
- [ ] Part B soak passes on the production path.
- [ ] Part C closed (C2/C3 minimum; C4/C5 for whichever assay runs first).
- [ ] `JOURNAL.md`/`CLAUDE.md` updated with the frozen state + validation numbers.
- [ ] **Tag** the release; branch the pre-v2 snapshot. Assays run on the tag; v2 on its own.

---

## What this unlocks
Gliding assay (motor velocity), minimal contractile assay (minifilament), **node contractile
assay (CPU+GPU, done)**, laser-tweezers force readouts — and the path to **whole-cell ring
formation** (needs N3). Each is a regression target v2 must reproduce.
