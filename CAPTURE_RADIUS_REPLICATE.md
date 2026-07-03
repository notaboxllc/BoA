# Capture-radius sweep, replicated WITH error bars: BoA agrees with its own single-run, NOT with v2

**Date:** 2026-07-02. **Question:** BoA's earlier single-run capture-radius side-result
(`CAPTURE_RADIUS_SENSITIVITY.md`) and v2's 3-seed GPU result (`~/Code/SoftBox/PHASE2_CAPTURE_RADIUS_FINDINGS.md`)
**disagree on the sign** of the radius→speed trend. v2 blamed the Gauss–Seidel(CPU)/Jacobi(GPU) one-step-stale
force residual; the simpler hypothesis was that BoA's earlier single-run trend was **mat-draw noise**. This task
replicates BoA's sweep with **3 fresh mat draws per radius** to decide which. **Verdict: BoA's trend is REAL and
replicates — net RISES with radius, per-bound drift stays ~flat. It is NOT mat noise; there genuinely is a
CPU/GPU sign difference.**

## Method
Standard d1000 gliding assay, one 2 µm filament, full fixed-myosin mat (box 14×2×0.5 µm), dt 1e-5, Brownian on,
**CPU**, default f̂-directed neck-stroke motor (no flags). `myoColTol` = **4 / 6 / 8 nm** (the extremes + default,
where v2 and BoA-earlier diverge most). **3 mat draws per radius** (BoA mat is `Math.random`-nondeterministic ⇒
3 launches = 3 distinct mats). runTime 0.7 s; **gliding velocity = LS-centroid drift along f̂ over the uniform
steady window [0.30, 0.70] s** (`PROPER_SPEED_ANALYSIS.md`). Coverage-violated runs (filament reaching a bed
edge) would be excluded from the velocity mean — **none violated** (all filaments drift −x from x≈4.7 to ~2,
staying well inside; axialFrac 0.994–0.999). Param files `ParameterFiles/glidingAssay_d1000_colTol{4,6,8}nm`.
`BoA-v1ref` untouched; no model/release/stroke change. Raw analysis: `RUN_LOGS/2026-07-02_colrep/` (`finalize.py`,
`finalize_out.txt`, per-run logs, `run_batch.sh`).

## STEP 1 — the 3-draw radius sweep (mean ± population SD over 3 mats)

| `myoColTol` | net \|v_axial\| (µm/s) | avgBound | per-bound drift | axialFrac | draws |
|--:|--:|--:|--:|--:|--:|
| 4 nm | **2.91 ± 0.21** | 11.22 ± 0.21 | **0.259 ± 0.014** | 0.999 | 3/3 |
| 6 nm (default) | **2.83 ± 0.23** | 16.92 ± 0.44 | 0.167 ± 0.012 | 0.997 | 3/3 |
| 8 nm | **4.89 ± 0.51** | 19.76 ± 0.16 | **0.248 ± 0.028** | 0.994 | 3/3 |

(per-bound drift = |v_axial| / avgBound, µm/s per bound head. Sign of v_axial is negative = pointed-leading =
correct.) Per-draw net |v_axial|: **4 nm** 3.06 / 3.05 / 2.62; **6 nm** 2.51 / 3.01 / 2.96; **8 nm** 5.60 / 4.43 / 4.66.

**Read:**
1. **Net glide RISES over the range: 4→8 nm = 2.91 → 4.89 µm/s (+68%).** The endpoints are cleanly separated —
   the *slowest* 8 nm draw (4.43) exceeds *every* 4 nm and 6 nm draw. This is **not** mat noise. (The 4→6 nm step
   is flat within spread — 2.91 vs 2.83 — so the trend is "flat then up," not perfectly monotonic; the 8 nm jump
   is the robust, unambiguous feature.)
2. **avgBound rises monotonically and tightly: 11.2 → 16.9 → 19.8 (+76%).** A larger radius lets more heads reach
   binding range — the clean, low-variance mechanism (both codes agree here).
3. **Per-bound drift is ~FLAT across the extremes: 0.259 → 0.248 (−4%), spreads overlapping.** It dips at 6 nm
   (0.167) but the 4 nm and 8 nm endpoints are statistically equal. **There is NO drift collapse.** Net rises
   because avgBound rises at ~constant per-head efficiency — exactly BoA's earlier "binding-count" picture.

## STEP 2 — the refutation of v2's trend (this is the whole point)

At the shared 6 nm anchor all three studies agree (~2.8–3.1 µm/s, drift ~0.17–0.21). At the **extremes they
diverge in sign**:

| radius | BoA-CPU net (this, 3-draw) | BoA drift | v2-GPU net (3-seed) | v2 drift |
|--:|--:|--:|--:|--:|
| 4 nm | **2.91 ± 0.21** | **0.259** | **3.83 ± 0.03** | **0.380** |
| 6 nm | 2.83 ± 0.23 | 0.167 | 3.07 ± 0.12 | 0.206 |
| 8 nm | **4.89 ± 0.51** | **0.248** | **1.51 ± 0.15** | **0.081** |

- **BoA CPU 4→8 nm: net +68%, drift flat (0.259→0.248, −4%).**
- **v2 GPU 4→8 nm: net −61%, drift COLLAPSE (0.380→0.081, −79%).**

**Opposite sign, confirmed with error bars on both sides.** BoA's single-run "+48% / drift-flat" was NOT a lucky
mat — the 3-draw means reproduce it. The parallel-residual explanation is therefore **necessary, not
unnecessary**: something really does flip the radius→speed response between the sequential-CPU and Jacobi-GPU
paths. The codes agree at 6 nm and diverge as co-bound density grows toward 8 nm — consistent with a co-bound
interaction that only the parallel path carries.

## STEP 3 — the stretch census (4 vs 8 nm): BoA's bound-population GEOMETRY matches v2's — the divergence is NOT here

Read-only bound-population census (`BOA_STRETCH_CENSUS=1`, default-off byte-identical; reads per-link
`forceMag`/`forceDotFil`/dwell already computed each step — no force/RNG touched), steady window t≥0.30:

| metric (bound-pop mean) | 4 nm | 6 nm | 8 nm | Δ(4→8) | v2 Δ(4→8) |
|--|--:|--:|--:|--:|--:|
| anchor-spring extension (nm) | 5.13 | 5.28 | 5.53 | **+8%** (more stretched) | +13% |
| \|forceDotFil\| per head (pN) | 2.90 | 3.01 | 3.18 | **+10%** (higher per-head axial force) | +15% |
| **signed** forceDotFil (pN) | 0.238 | 0.177 | 0.136 | small, → ~0 (**axial cancellation**) | ~0 |
| bound dwell (ms) | 0.640 | 0.393 | 0.244 | **−62%** (detaches ~2.6× faster) | −49% |

(4 nm & 8 nm census = mean over 2 draws; 6 nm over 3. p1 probes predate the flag.)

**BoA reproduces v2's STEP-3 geometry qualitatively on every axis:** at wider radius the bound heads are more
stretched, carry higher per-head |axial force|, have **near-zero signed force** (≈0.14–0.24 pN vs a ~3 pN
magnitude — the co-bound forces largely cancel, the tug-of-war quantified), and **dwell roughly halves**. These
are the *same* signatures v2 used to explain its drift collapse.

**The paradox — and the real finding.** In v2 these signatures coincide with a **drift collapse**; in BoA the
*identical* geometry shift coincides with **flat drift** (net rises). So the stretch geometry is **not** what
differs between the codes — both show it. What differs is how a shorter-dwelling, more-canceled bound population
maps to **net transport**: on BoA-CPU the faster cycling (dwell −62% ⇒ more power strokes per head per second)
**compensates**, holding per-head productivity ~constant, so more bound heads ⇒ more glide. On v2-GPU each added
co-bound head instead **resists** the others more (per-head efficiency collapses). This is precisely the
signature of the **Gauss–Seidel(CPU) vs Jacobi(GPU) co-bound residual**: sequential CPU heads see each other's
fresh within-step updates and resist less; parallel GPU heads see one-step-stale neighbor positions and resist
harder, and adding heads (wider radius) amplifies only the parallel penalty.

## The fork — verdict

> **BoA per-bound drift stays ~FLAT / net RISES (reproduces the wide-radius speed-up) ⇒ the earlier BoA trend is
> REAL and replicated.** Not single-run mat noise. There genuinely IS a CPU/GPU sign difference in the
> radius→speed response. The stale-force residual (Jacobi vs Gauss–Seidel) is the leading explanation and is a
> significant GPU-fidelity finding worth a deeper look.

The census sharpens it: the divergence is **not** in the bound-population stretch geometry (identical in both
codes) but in the **co-bound load-sharing** — how per-head force resolves into net transport as engagement rises.
BoA-CPU converts extra bound heads into extra glide at flat efficiency; v2-GPU's parallel co-bound resistance
craters efficiency as heads pile on. The two paths are cross-consistent at low engagement (6 nm) and split at
high engagement (8 nm), exactly where a stale-neighbor co-bound term would bite hardest.

**Overlay for future comparison (per-bound drift vs avgBound):**
```
 avgBound   BoA-CPU drift (this)      v2-GPU drift
  ~11.2      0.259 (r4)               0.380 (r4)
  ~16.9      0.167 (r6)               0.206 (r6)
  ~19.8      0.248 (r8)               0.081 (r8)
```
BoA's drift-vs-avgBound is roughly flat/U-shaped (0.26→0.17→0.25); v2's descends steeply and monotonically
(0.38→0.21→0.08). Same low-engagement region, opposite high-engagement behavior.

## Runs (for the record)
```
# per radius, 3 mat draws (p1 = probe, no census; p2/p3 census on):
BOA_STRETCH_CENSUS=1 java -Xmx4G --enable-preview -cp "<tornado-api>:libs/*:." \
    BoxOfActin -r -pf ParameterFiles/glidingAssay_d1000_colTol{4,6,8}nm -3js <out>
# CPU, ~40–65 min wall each (contended, pool of 3 on 16 cores). Analysis: RUN_LOGS/2026-07-02_colrep/finalize.py
```
**Validation.** *Census is default byte-identical:* it fires only under `BOA_STRETCH_CENSUS`; a no-flag run is
unchanged (pure bookkeeping — reads per-link fields already computed each step, touches no force or RNG). The
census hook and dwell episode-tracking live behind that gate in `MyoFilLink` (`stretchCensus`, `bindStep`
accumulation) + a gated call at output cadence in `BoxOfActin.doLoop`. Measurement-only otherwise (`myoColTol`
is a param). `BoA-v1ref` untouched; no release/stroke/kinetics change. All 9 runs coverage-clean (axialFrac
0.994–0.999); velocity from the uniform [0.30,0.70] LS window, converged (window-independent across full runs).
