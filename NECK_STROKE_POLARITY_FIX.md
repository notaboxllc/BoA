# Well-Defined Neck-Stroke Direction (polarity fix) — result

**Date:** 2026-07-01. **Flag:** `myoNeckStrokePolarity` (Env.java, default OFF; CPU path only). Companion to
`FIXED_HEAD_NECK_STROKE_MOTOR.md` (the motor) and `PROPER_SPEED_ANALYSIS.md` (the speed estimator).

## The bug it fixes

The stock neck power-stroke torque (`Myosin.applyLeverMotorJointTorque`) relaxes only the **scalar** lever-motor
angle about the `lever × motor` axis — it never references the filament. So the swing **azimuth** (and hence
whether the neck's rear endpoint sweeps toward the barbed or pointed end) is **under-determined**: it's set by the
lever's incidental starting azimuth, not by filament polarity. Surfaced when porting to v2 (SoftBox); confirmed in
BoA by code inspection (nothing constrains the lever azimuth — see the 2026-06-30/07-01 journal).

## The fix

When ON (and the head is bound), the stroke aligns the lever to a **definite, polarity-derived target**
(`Myosin.applyLeverMotorJointTorquePolarity`):

```
fhat = bound-segment uVec (pointed->barbed);  mhat = head uVec;  theta = rest lever-motor angle (0 or 70 deg)
that    = -normalize( fhat - (fhat.mhat) mhat )            # the -fhat direction, projected perp to the head axis
uTarget = cos(theta)*mhat + sin(theta)*that
torque  = k * (lever.uVec x uTarget)                       # compliant alignment, lever only, k = myoJ1FracMoveTorq
```

Because `uTarget` tilts toward `-fhat`, the rear (`-uLever`) always gains a `+fhat` component:
`rear.fhat = +sin(theta)*|f_perp| > 0` for a head held ~perpendicular to the filament. **The rear therefore
ALWAYS sweeps toward the barbed (+) end, regardless of the lever's starting azimuth.**

## Verification 1 — single motor, Brownian OFF, adversarial IC (34 cycles)

`ParameterFiles/singleBind_neckPolarity_cycles`: one fixed myosin, Brownian off, neck **pre-bent toward the
pointed side** (adversarial), biochem cycling, kept bound.
- Frame 0 rear on the **pointed** side (adversarial start); after binding + first stroke the rear is on the
  **barbed** side and stays there.
- **34 recock→powerstroke cycles** over 0.2 s.
- Of **962** bound frames with the neck stroked (>40°): rear on the **barbed side in 962, pointed in 0** →
  **ALWAYS barbed** (worst-case rear offset +0.0049). Head held ~90° throughout.

## Verification 2 — full gliding assay (stroke fidelity + speed)

Standard single-filament assay (14×2×0.5 µm, one 2 µm filament, d = 1000 motors/µm², Brownian ON, dt 1e-5,
**1.5 s**), model = `myoFixedHeadNeckStroke` + `myoAxialSwingLock` + `myoNeckStrokePolarity`.
Run dir: `~/Code/threejs_output/axlock_d1000_long_polarity/` (500 frames, `-3js`).

**Stroke fidelity** (sampled bound + stroked motors, whole run): **1047 / 1051 (99.6%) sweep the rear toward the
barbed end**; 4 (0.4%) caught mid-swing. The fix holds across the whole mat, not just the single-motor test.

**Speed** (LS-centroid drift along the filament axis, PROPER_SPEED_ANALYSIS method; centroid monotonic
5.74 → 0.24 over 1.5 s):

| config (d = 1000, 1.5 s) | v_axial | axial frac | window |
|---|---:|---:|---:|
| **with polarity fix** | **−3.93 to −4.06 µm/s** (pointed-leading) | 0.99–1.00 | [0.3–1.5], [0.5–1.5] s |
| axial-lock only (no fix) | −2.09 µm/s | 1.00 | [0.3–1.5] s |

**≈ 3.0 filament body-lengths over 1.5 s (~2 BL/s).**

## Headline

**The direction fix nearly DOUBLED the glide speed (−2.09 → −3.96 µm/s).** Mechanistic reading: before the fix each
motor's neck swept in an incidental azimuth (some barbed, some pointed, some transverse), so strokes partially
canceled; with 99.6% now sweeping coherently barbed, they add. So the fix is not just cosmetically correct — it
materially improves motility, and the assay confirms the direction is well-defined at scale.

## Caveats
- **CPU path only** — the fix lives in `Myosin.applyLeverMotorJointTorquePolarity`; the GPU joint kernel still uses
  the polarity-blind scalar-angle relaxation, so a `-gpu` run would silently reproduce the original ill-defined
  swing. Porting the fix (plus the roll retarget and the rod-gate change) into the device kernels is the open item
  before GPU or promotion.
- **Flag-gated test**, default OFF; not promoted to the default motor.
- The 4/1051 "pointed" frames are heads caught mid-swing, not failures.

## Reproduce
```
# single-motor adversarial cycle test
BoxOfActin -r -pf ParameterFiles/singleBind_neckPolarity_cycles -3js ~/Code/threejs_output/singleBind_neckPolarity_cycles
# 1.5 s gliding assay with the fix
BoxOfActin -r -pf ParameterFiles/glidingAssay_axlock_d1000_long_polarity -3js ~/Code/threejs_output/axlock_d1000_long_polarity
# (both CPU; launch via the TornadoVM argfile + -Xmx8G as in FIXED_HEAD_NECK_STROKE_MOTOR.md)
```
