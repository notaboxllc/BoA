# BoA v1 — Finish Line (pre-v2 checklist)

**Purpose.** Define the bounded set of work that takes BoA v1 as far as it usefully
goes — fast *and* trustworthy — so it can be **tagged as the biology-production
release** and left to run assays while the v2 SoA/ECS port proceeds on its own
branch. This is the explicit definition of "done with v1."

## Guiding principle

- **Do the contained levers; stop at the architectural wall.** The copy-out fix
  (`COPYOUT_RESIDENCY.md`) landed us right at the v1/v2 seam. The remaining v1 speed
  work is a *finite, enumerable* list. Past it, the costs are OOP-graph structural
  (`pack`, host allocation ceiling) — that's v2's job, not v1's. Grinding past the
  wall is gold-plating a codebase we're about to replace.
- **Ready = speed AND calibration AND long-run stability.** A fast sim that's
  miscalibrated or leaks over a long run just produces wrong answers faster. The
  benchmarks stressed *per-step* cost over short windows; biology runs are *long*, so
  stability gets its own section.
- **The biology runs become v2's regression suite.** Assays on frozen-v1 generate the
  ground-truth (velocities, contractile rates, force curves) that v2 must reproduce.
  Sequencing them first serves the port, it isn't a detour from it.

---

## Part A — Speed (bounded list, ranked by scaling shape)

*Rank by how a term scales, not its current size: superlinear terms dominate at
research scale and are the real ceiling.*

- [ ] **A1 · `recompute` incremental classification** — *P1, superlinear, highest value.*
  `GPUMoveThing.onStepStart` (Thing reclassification + pose delta-audit) re-runs in
  full every step because biochem turnover keeps topology dirty: 1.2 → 27.7 ms/step
  over 1×→8× (**~23×**, worse-than-∝N). Cache/incrementally update classification when
  the topology delta is small; only re-audit changed slots.
  *Done when:* recompute slope drops toward ∝N and 8× GPU÷CPU improves, physics-neutral
  (oracle: segs, activeLinks, crosslinkFireCt, NaN, overflow). *Prompt: needs drafting.*

- [ ] **A2 · `gridScatter` restructure** — *P2, superlinear.* The remaining superlinear
  bind-grid kernel: ~3.1 → 29.9 ms over 1×→8× (v4 Part E). Diagnose the superlinear
  factor first (cheap probe), then parallelize/restructure as we did for `gridAssemble`.
  *Done when:* gridScatter slope is ∝N or better, CSR bit-identical to the serial
  oracle, physics-neutral. *Prompt: needs scoping after a diagnostic probe.*

- [ ] **A3 · Force-offload of the un-offloaded CPU work** — *P3, ∝N, large absolute.*
  On the `-gpu` path, `step` (13.5) + `brownian` (5.5) + `jointsCpu` (11.8) ≈ **31
  ms/step at 8×** still runs on CPU — now larger than the retired copy-out. `jointsCpu`
  is the MyosinDimer / MyoMiniFilament / ProteinNode / Chamber joint pools.
  *Done when:* these phases dispatch on device, physics-neutral. *Note:* sequence after
  A1/A2 — it's a constant-factor win, not a scaling-shape change. May share scope with
  the v2 design; if porting them cleanly requires SoA, **stop and defer to v2**.

- [ ] **A4 · Live-subrange fil-fil transfer (the (c) follow-on)** — *P4, optional polish.*
  Shrinks each fire-step `ffCandPartner` pull from 1.024 GB to `filSegmentCt×256`
  (~6–50 MB) via a partial-range `transferToHost`. The cadence gate already amortizes
  this to <2 ms/step, so **only do it if it falls out cheaply.** Otherwise skip.

**Stop criterion for Part A:** once A1–A3 land, the dominant residual is `pack` + the
host object-graph allocation ceiling. That is the v2 boundary — **do not re-architect
v1 to chase it.**

---

## Part B — Long-run stability (biology runs are long, not short benchmarks)

- [ ] **B1 · `POSE_DELTA_CAP` overflow / host-memory leak** — *P1 for long runs.*
  Overflow forces per-step plan rebuilds that leak host memory at 8× — tolerable in a
  300-step benchmark, fatal in a long assay. Measure true dirty-slot demand →
  startup-size the cap → defuse the fallback to a non-leaky full-pose upload.
  *Done when:* a long soak at the assay scale shows flat host RSS. *Prompt: drafted earlier.*

- [ ] **B2 · Long-run soak test** — *P2.* Run the actual assay configs (gliding,
  contractile) for full biological duration on both CPU and GPU paths; watch for host
  RSS growth, VRAM creep, NaN-over-time, and determinism drift. This is the gate that
  short per-step benchmarks never exercised.
  *Done when:* a full-length run completes clean on the production path.

---

## Part C — Biology calibration & correctness (trustworthy instrument)

*These gate whether v1 produces the* right *answers — independent of speed. Several are
cheap checks that gate everything downstream; do those early.*

- [ ] **C1 · GPU minifilament cohesion bug** — *P1, gates contractile/minifil assays on GPU.*
  Dimer Myosins are GPU-classified and device-integrated but receive **zero anchor
  force** (the GPU anchor kernel recognizes only `MyosinFixed`) → bundles blow apart.
  Cheap CPU-fallback: exclude dimer Myosins from GPU classification. The moving-body-
  anchor device port is **deferred to v2**.
  *Done when:* a no-actin single-minifilament repro stays cohesive on the GPU path.
  *Prompt: drafted earlier.*

- [ ] **C2 · Gliding-assay IC fix verification** — *P1, cheap.* Confirm the barbed/+x-end
  inward pad (`end2.x` 7.00035 → 6.91395, ~½ a std filSegment length) didn't revert
  during the motor-port sessions — a frame-0 viewer image showed a filament end at/through
  the +x wall. Check the pad term at `FilSegment.makeGlidingAssayFilament`; re-apply if lost.
  *Done when:* frame-0 viewer shows all filament ends inside the box.

- [ ] **C3 · F-actin units convention** — *P1, gates any µM claim.* `Chamber.makeABox`
  uses `boxVolume = 8·dimX·dimY·dimZ` (~400 µm³) vs the occupied ~50 µm³, so reported µM
  is 8× off geometric (geometric ~13.7 µM vs sim-internal ~1.7 µM). Reconcile and pick a
  single convention before any concentration target is meaningful.
  *Done when:* one documented convention; reported µM matches it.

- [ ] **C4 · Gliding baseline + phalloidin regime** — *P2, for biological gliding.*
  Keep current defaults (fracMove 0.5 / fracR 0.1 / fracMoveTorq 0.2) **for GPU-port
  validation**. For biology, switch to the phalloidin-stabilized regime (fracMove
  0.05725, fracR 1.0, fracMoveTorq 0.1, 64-mon segments, ~2× stiffer), regenerate the
  gliding baseline, and re-validate the published-velocity match.
  *Done when:* phalloidin-regime gliding velocity matches the published target.

- [ ] **C5 · Contractile density / bundling-vs-bridging decision** — *P2, science decision.*
  At runnable densities crosslinks bundle adjacent pairs (components ~2) rather than
  percolate; percolation needs ~40× density (≈4000 filaments) or a model change. Decide,
  before designing the minimal contractile assay, whether the target regime is bundling
  or percolation — and if percolation, how to reach it.
  *Done when:* the contractile assay's intended regime + density are specified.

---

## Part D — Freeze criteria (when to tag `biology-production-v1`)

- [ ] All Part A items landed or explicitly deferred-to-v2, merged to `main`.
- [ ] Part B long-run soak passes on the production path.
- [ ] Part C correctness items (C1–C3 at minimum) closed; C4/C5 closed for whichever
  assay runs first.
- [ ] `JOURNAL.md` / `CLAUDE.md` updated with the frozen state and the validation numbers.
- [ ] **Tag the release** (e.g. `git tag v1-biology-production`) and branch the pre-v2
  snapshot. From here, assays run on the tag; v2 develops on its own branch.

---

## What this unlocks

- **Gliding assay** — motor velocity validation/tuning (phalloidin regime).
- **Minimal contractile assay** — minifilament contractile behavior.
- **Laser-tweezers-type in silico** — single-molecule / small-ensemble force readouts.

Each produces a **regression target** the v2 port must reproduce — so the science done
on frozen-v1 is also the acceptance test for v2.

---

*Sequence is flexible. A reasonable order: the cheap correctness checks first (C2, C3),
then the superlinear speed wins (A1, A2), the GPU minifilament fix (C1) before any
contractile run, stability (B1/B2) before long assays, and A3/A4 as final polish — but
reprioritize per whichever assay you want to run first.*
