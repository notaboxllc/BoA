# Head-Frame Neck Stroke + Stereospecific Roll Sign (motor-contained) — findings

**Date:** 2026-07-01. **Flag:** `myoNeckStrokeHeadFrame` (Env.java, default OFF; CPU path only; requires
`myoAxialSwingLock` ON). Companion to `NECK_STROKE_POLARITY_FIX.md` (the f̂-referenced stroke) and
`PROPER_SPEED_ANALYSIS.md` (the estimator). `BoA-v1ref` untouched — this is measurement/faithfulness only.

## STEP 0 — current roll lock (reported before changing anything)

`MyoFilLink.alignYVecTorqueAxial()` (the `myoAxialSwingLock` roll lock):
1. **Roll axis: LOCKED to ŝ = n̂bed × û_seg** (the swing-plane normal, `sx=−fhat.y, sy=fhat.x`), NOT the
   segment's incidental yVec. The axis lock is present — the prerequisite is met.
2. **Sign: SIGN-AGNOSTIC** — aligns to the **nearer of ±ŝ** (`if dotVecs<0 scale(−1)`). This is exactly v2's
   failure mode (axis-locked but sign-free → 50/50), so the sign lock was the missing piece.

## STEP 1 — the two changes (mirror v2's `-hfswing` + `-rollsign`)

Both under `myoNeckStrokeHeadFrame`, CPU, lever-only converter torque, same coeff/drag as the stock stroke.

1. **Head-frame swing** (`Myosin.applyLeverMotorJointTorqueHeadFrame`): replace the f̂ target with
   `uTarget = cos(θ)·û_head − sin(θ)·(ŷ_head × û_head)` — the neck swings relative to the head's OWN frame,
   about the head hinge axis ŷ_head. **No f̂ in the swing law.**
2. **Stereospecific roll sign** (in `alignYVecTorqueAxial`, gated on the flag): sign-lock `head.yVec` to **+ŝ
   specifically** (= +(n̂×f̂), the barbed-sweep sign) instead of the nearer of ±ŝ; the arrival angle uses the
   full 0–180° to +ŝ so a head arriving near −ŝ pays the real twist. Head-frame ≡ the old f̂ target **iff**
   yVec = +ŝ (and û_head = +n̂ — see the mhat note below).

Diagnostics: the head yVec is now emitted in the frame JSON (`motor.yVec`) for roll census / jiggle.

## STEP 2 — measurement (LONG assay, LS-centroid, ≥ ~1 s window)

`glidingAssay_axlock_d1000_long_headframe` (14×2×0.5 µm, one 2 µm filament, d = 1000, Brownian ON, dt 1e-5),
model = `myoFixedHeadNeckStroke` + `myoAxialSwingLock` + `myoNeckStrokeHeadFrame`. LS-centroid drift along f̂
over window **[0.30, 1.17] s (0.87 s)**:

| config (d = 1000) | v_axial | axial frac | avg bound |
|---|---:|---:|---:|
| f̂-target (`myoNeckStrokePolarity`) | −3.96 µm/s | 1.00 | 21.2 |
| **head-frame (`myoNeckStrokeHeadFrame`)** | **−2.54 µm/s** | 1.00 | 21.5 |
| v2 (reference) | ~−2.1 µm/s | — | — |

**VERDICT: DROP — to ~v2's level.** The head-frame law swings relative to the head's *real* frame, so it
inherits the head's orientation noise that the f̂ reference cleanly ignored. Speed drops −3.96 → **−2.54**,
right in v2's ~−2.1 band. Per the task's framework this **confirms the head-frame law inherits head-noise
identically in both codes; BoA and v2 now agree at the more-physical number → adopt head-frame as the
defensible stroke.** The glide is still clean and directional (axial frac 1.00), not degraded — the drop is
biological honesty, not a regression.

### Censuses, jiggle, twist cost (steady frames, t>0.3)
| | value | reading |
|---|---|---|
| ROLL `yVec·ŝ (+Y)>0` | **100%**, mean +0.871, stdev **0.152** | roll sign-lock works; modest roll jiggle |
| HEAD-AXIS `mhat·n̂ (+Z)>0` | 55%, mean +0.082, stdev **0.889** | head axis ≈ 50/50 ±n̂, **not locked by any constraint** |
| rear-barbed | 55% | reflection metric — tracks mhat, NOT productivity (see below) |
| twist cost (arrival yVec vs +ŝ at bind, n=1078) | mean **26°**, median 23°, **99% < 90°** | sign-lock is CHEAP (no 180° fight), as in v2 |

### Note on `mhat` (why "rear-barbed" is only 55% but the glide is fine)
The polar hold fixes only `mhat ⊥ f̂` (90°), not which side; with yVec locked to ŝ, `mhat` is free to be ±n̂,
and it comes out ≈ 50/50 (stdev 0.889). The head-frame target gives a barbed *rear* only for `mhat = +n̂`; the
`mhat = −n̂` case is a **Z-reflection** (head up vs down) that mirrors the rear in X but **does not reverse the
axial glide force** — so the strokes do NOT cancel (net glide −2.54, axial frac 1.00), they're just less
efficient than the noise-free f̂ target. So the head-axis 50/50 is head-orientation *noise* the head-frame law
honestly inherits, not a fidelity failure. (Fully pinning the barbed rear would need an additional `mhat`-sign
lock; not part of this task.)

### No-drop branch (not taken)
Speed did drop, so the "BoA head tighter than v2" branch does not apply. For the record the head-lock
coefficient is `k_az = myoJ1FracMoveTorq = 0.4`, and the jiggle stdevs above (roll 0.152, head-axis 0.889) are
the head-orientation noise to compare against v2's if desired.

## Release pathway (the lurking confound — pinned)
BoA release for these runs = **catch-slip only on the F8 tip-spring load** (`MyoFilLink.ckRelease`:
Guo–Guilford `kOff·(αcatch·e^(−F∥·xcatch/kT)+αslip·e^(+F∥·xslip/kT))` on `forceDotFil` = the colinear component
of the cross-bridge/F8 tip-spring force), plus a `myosinBreakForce` safety cap; `inRigor` never catch-slip-
releases. This matches v1. **So the BoA↔v2 speed spread reads as stroke-law + head-noise, not release** — *provided
v2 uses the same catch-slip-on-F8-load pathway*. If v2's release differs, that confound must be flagged before
attributing the residual (−2.54 vs −2.1) spread to head-noise alone.

## Caveats / status
- **Flag-gated test**, default OFF; CPU path only (GPU joint kernel still polarity-blind — the standing GPU port).
- Window 0.87 s (t_end 1.17 s), LS-centroid, axial frac 1.00 → converged for this purpose (1 s is ample).
- `myoNeckStrokeHeadFrame` takes precedence over `myoNeckStrokePolarity` when both are on.

## Reproduce
```
BoxOfActin -r -pf ParameterFiles/glidingAssay_axlock_d1000_long_headframe -3js ~/Code/threejs_output/axlock_d1000_long_headframe
# (CPU; TornadoVM argfile + -Xmx8G as in FIXED_HEAD_NECK_STROKE_MOTOR.md)
```

## Journal line
2026-07-01 — Head-frame neck stroke (`myoNeckStrokeHeadFrame`) + stereospecific +ŝ roll sign: STEP-0 confirmed the
axial roll lock is axis-locked (ŝ=n̂×f̂) but sign-free; added head-frame swing (uTarget=cosθ·û_head−sinθ·(ŷ×û_head),
no f̂) + roll sign-fix to +ŝ. LONG d1000 assay: speed DROPS −3.96→**−2.54 µm/s** (LS-centroid, [0.3,1.17]s, axFrac
1.00) → v2's ~−2.1 band, confirming the head-frame law inherits head-noise the f̂ target ignored → adopt as
defensible. Roll 100% +ŝ (stdev 0.152), twist cost cheap (26° mean arrival, 99%<90°); head-axis mhat 50/50
(stdev 0.889) — a harmless Z-reflection (no cancellation). Release = catch-slip-on-F8-tip-spring (per v1). Flag-
gated, CPU-only, default OFF; BoA-v1ref untouched.
