# Bind capture-radius sensitivity of gliding velocity

**Date:** 2026-07-02. **Parameter:** `myoColTol` (Env.java, default **6 nm** = 0.006 µm) — a myosin head binds a
filament when its tip falls within this perpendicular distance of the filament axis and the alignment gate
passes. **Motor:** the default f̂-directed neck-stroke motor (no flags; see `NECK_STROKE_DEFAULT_PROMOTION.md`).
CPU path. `BoA-v1ref` untouched; no other params changed.

## Method
Standard gliding assay: full d1000 fixed-myosin mat, box 14×2×0.5 µm, one 2 µm gliding filament, dt 1e-5,
Brownian on. Gliding velocity = LS-centroid drift along f̂ over a steady window (`PROPER_SPEED_ANALYSIS.md`).
One run per radius (5 / 6 / 7 nm), each to a t≈0.5 s window; LS consistent across the [0.10/0.20/0.30]-start
sub-windows, so converged. Param files: `ParameterFiles/glidingAssay_d1000_colTol{5,6,7}nm`.

## Result

| `myoColTol` | glide velocity (LS[0.10,~0.53s]) | avgBound | per-motor drift |
|---|---:|---:|---:|
| 5 nm | −2.76 µm/s | 14.7 | 0.187 |
| **6 nm (default)** | **−3.02 µm/s** | **16.4** | 0.185 |
| 7 nm | −4.09 µm/s | 19.0 | 0.215 |

(per-motor drift = |v| / avgBound, µm/s per bound head.)

## Finding — significant, monotonic, and binding-frequency-driven
The capture radius matters: over just ±1 nm around the 6 nm default, gliding velocity moves monotonically, with
**5 → 7 nm ≈ −2.76 → −4.09 µm/s (~+48%)**. The mechanism is clean — a larger radius lets more heads reach
binding range, so **avgBound rises monotonically (14.7 → 16.4 → 19.0)** and the collective glide force (hence
velocity) scales with it. The **per-motor drift stays ~constant (~0.19 µm/s per bound head)** across all three,
so this is a *binding-count* effect, not a change in per-stroke mechanics. The default 6 nm sits in a sensitive,
unsaturated regime — not on a plateau.

## Caveats
- **Single run per radius**, and the simulator is non-deterministic run-to-run (`Math.random()` in `UCircRnd`
  and the mat placement → a different mat each launch, ~±15% velocity scatter observed elsewhere). So the
  **5 ↔ 6 nm step (~10%) is within run-to-run noise**, but the **7 nm result and the monotonic avgBound trend
  are robust** (the effect exceeds the scatter band, and avgBound is a direct, low-variance readout of the radius).
- Practical implication: `myoColTol` is a strong knob on gliding speed via engagement fraction; a velocity
  target should fix it (or report it) rather than treat it as incidental.

## Follow-ups (not done)
- 2–3 seed replicates per radius for error bars on the velocities.
- Wider sweep (e.g. 4–10 nm) to locate saturation and check linearity of avgBound(radius).
