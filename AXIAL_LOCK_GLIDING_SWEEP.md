# Axial-Lock Motor — Gliding Velocity vs Fixed-Myosin Density

**Date:** 2026-06-30. **Model:** fixed head-90° / 70° neck stroke + axial swing lock
(`myoFixedHeadNeckStroke` + `myoAxialSwingLock`; flag-gated test, CPU path — see `FIXED_HEAD_NECK_STROKE_MOTOR.md`).

## Assay

Our standard single-filament gliding assay (`ParameterFiles/glidingAssay500_val`, with the axial-lock flags added —
base file `ParameterFiles/glidingAssay_axlock_base`):
- **Box** 14 × 2 × 0.5 µm (long, narrow), fixed myosins seeded uniformly in the z = −0.05 plane.
- **One** 2 µm gliding filament along +X (barbed = +X; `initialFilaments:false`).
- Brownian ON, dt = 1e-5 s, **runTime 0.15 s**, `externalDensitySweep` (each density = one run).
- Density swept 50 → 1500 motors/µm² (one run per density; `axlock_sweep_d<N>`).

## Method

Per run, from `gliding_assay.dat` over the **steady window t > 0.03 s** (drops the startup transient):
- **Net glide** = signed net displacement of the tracked point along the filament axis (X) / window time.
  Negative X = toward the pointed end = **correct** (pointed-leading) glide.
- **Axial fraction** = |ΔX| / ‖(ΔX,ΔY,ΔZ)‖ (1.0 = straight track; low = wandering).
- **Track speed** = mean `longWindowSpeedXY` (smoothed per-window speed; counts bind-release jitter).
- **Avg bound** = mean engaged heads.

## Results

| Density (µm⁻²) | Motors | Avg bound | **Net glide** | Direction | Axial frac | Track speed |
|---:|---:|---:|---:|:--:|---:|---:|
| 50 | 1,400 | 5.4 | 0.44 µm/s | pointed* | 0.44 | 5.5 µm/s |
| 100 | 2,800 | 7.9 | 0.24 µm/s | barbed* | 0.60 | 5.1 µm/s |
| 250 | 7,000 | 14.8 | 0.56 µm/s | pointed | 0.85 | 5.3 µm/s |
| 500 | 14,000 | 17.7 | 0.90 µm/s | pointed | 0.87 | 6.2 µm/s |
| 750 | 21,000 | 20.1 | 1.70 µm/s | pointed | 0.98 | 7.0 µm/s |
| 1000 | 28,000 | 21.7 | 2.35 µm/s | pointed | 1.00 | 7.9 µm/s |
| 1500 | 42,000 | 23.2 | 3.74 µm/s | pointed | 0.99 | 8.9 µm/s |

\* Low-density points (50, 100) are erratic — too few engaged heads to hold the filament on track (see below).

## Interpretation

- **Directional, correct-polarity gliding above a density threshold.** For density ≥ 250/µm² the filament glides
  **pointed-leading** (correct) with axial fraction climbing 0.85 → 1.00 — i.e. it tracks essentially straight down
  its own axis. The axial swing lock is doing its job across the whole usable density range.
- **Low-density regime is erratic** (50–100/µm²): only ~5–8 heads engaged, axial fraction 0.44–0.60, and d=100 even
  nets slightly barbed-ward — noise, not motion. This mirrors real assays, where below a motor-density threshold
  filaments detach and move erratically / slowly.
- **Two velocities, two behaviors:**
  - **Track speed** (per-window, ~5–9 µm/s) rises only gently with density — closer to the classic experimental
    picture where gliding velocity is roughly density-*independent* above threshold and set by the motor's cycle.
  - **Net glide** (0.4 → 3.7 µm/s) rises steeply because the *directionality* improves with density: at low density
    the filament wanders (net ≪ track); by 1000–1500 it is ~99% axial so net → the directed speed. It has **not
    plateaued** by 1500 in this box.
- **Avg bound saturates** (~5 → ~23) as the 2 µm footprint fills.

## Caveats

- **Short runs (0.15 s)** — includes some transient even after the t>0.03 cut; a multi-second run + steady-window
  centroid-track fit would tighten the absolute numbers.
- **Single canonical filament in the narrow box.** The earlier **dense-mat** run (6×6 box, 12 filaments, density
  1000) gave a higher net glide (~8 µm/s) than this narrow-box single filament at the same density (~2.4 µm/s). The
  gap is unexplained — candidates: box/wall geometry (the 2 µm-wide Y dimension), single vs. many filaments, or the
  canonical-filament placement. **Open item** — worth isolating before quoting an absolute velocity.
- **Flag-gated test model**, CPU path only; the barbed-ward sweep still leans on the rod-gate bypass (bundled into
  `myoAxialSwingLock`), not a clean polarity-derived rule. Not promoted to default.
- Track speed is inflated by sub-frame bind-release jitter an experimentalist wouldn't resolve; net glide is the
  experiment-comparable (centroid-displacement) measure — see the metric discussion in the 2026-06-30 journal.

---

# Definitive velocity — single LS-centroid estimator (resolves the two-speed / single-vs-mat discrepancies)

**Estimator (the only one to quote):** track the **filament centroid** each frame; least-squares fit `c(t)` vs `t`
over a hand-picked steady window; **v_axial = (dc/dt)·f̂** (f̂ = filament pointed→barbed axis). Negative = correct
(pointed-leading). This is the experiment-comparable centroid velocity — NOT the per-window "track speed" (which
counts sub-frame bind/release jitter) and NOT a single endpoint-minus-start net.

| Run (14×2 single-fil unless noted) | **v_axial** | \|v\| | axial frac | avg bound | t0 (s) | window (s) |
|---|---:|---:|---:|---:|---:|---:|
| **d1000, 1.5 s** | **−2.09 µm/s** | 2.09 | 1.00 | 21.2 | 0.30 | 1.20 |
| **d500, 1.5 s** | **−0.76 µm/s** | 0.77 | 0.99 | 17.4 | 0.30 | 1.20 |
| d1000, 0.15 s (matched) | −2.26 | 2.32 | 0.97 | 21.7 | 0.02 | 0.13 |
| dense mat 6×6, d1000, 0.15 s (per-fil, n≈150) | −1.63 ± 0.81 | — | 0.83 | ~22 | 0.02 | 0.13 |

Long-run fits are window-independent (d500: −0.76 to −0.81 across t0; d1000 trajectory monotonic) → converged.

**Q1 — do "net" and "track" converge?** No, and they shouldn't. Under the LS estimator v_axial ≈ \|v\| (axial frac
→1.0), giving ONE velocity (−2.09 at d1000). The old "track speed" (5.8–10.7 µm/s) is the **path length incl.
bind/release jitter** (~3–5× the drift) and was never a translation velocity — axial-frac 1.0 means the *drift* is
axial, but the filament still jitters back-and-forth *along* that axis, so path ≫ net. The **LS drift is the number;
track speed was the jitter artifact.**

**Q2 — is the single-vs-mat gap real?** No — **metric artifact.** The 3–4× gap came from mixing estimators: the mat
was measured endpoint-minus-start over the whole run (transient-inflated → ~8), the single-fil as steady-window net
(~2.4). Same LS estimator → single −2.26, mat −1.63 µm/s: within ~1.4×, no gap (mat if anything slightly *slower*).
The narrow-2 µm-Y wall-drag hypothesis is **not** supported (the narrow-box filament is the faster one).

**The one number to defend to an experimentalist:** **≈ 2.1 µm/s, pointed-leading, at 1000 motors/µm²**
(≈ 1.6 filament body-lengths over the 1.5 s run, i.e. ~1 body-length/s). d500 gives ≈ 0.76 µm/s. Both are LS-centroid
drifts over a ≥1 s steady window with axial fraction ≈ 1.0.

## Reproduce
```
# per density d in {50,100,250,500,750,1000,1500}:
sed "s/fixedMyosinDensity:true:500;/fixedMyosinDensity:true:$d;/" ParameterFiles/glidingAssay_axlock_base > ParameterFiles/glidingAssay_axlock_d$d
# then run (CPU, TornadoVM argfile) -pf ParameterFiles/glidingAssay_axlock_d$d -3js ~/Code/threejs_output/axlock_sweep_d$d
```
Output run dirs: `~/Code/threejs_output/axlock_sweep_d{50,100,250,500,750,1000,1500}/`.
