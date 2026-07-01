# Head-Axis (mhat) Sign SET AT BIND — initialization, not a torque → FORK 3 (sign doesn't set the speed)

**Date:** 2026-07-01. **Flag:** `myoHeadAxisBindSet` (Env.java, default OFF; CPU path). On top of the
head-frame model (`myoFixedHeadNeckStroke` + `myoAxialSwingLock` + `myoNeckStrokeHeadFrame`). Corrects the
mis-diagnosis in `HEAD_AXIS_SIGN_LOCK.md`. `BoA-v1ref` untouched — measurement/faithfulness only, no
kinetics/geometry retune. Release pathway unchanged (catch-slip-on-F8, per v1 — see below).

## Why the prior result (`HEAD_AXIS_SIGN_LOCK.md`) was a mis-diagnosis
That attempt added a **third compliant torque driving mhat→+n̂ every step** — a steady-state spring FIGHTING the
head's motion. It pin-absorbed (avgBound 21→14, glide −2.54→+0.22) — the same failure as every prior
over-constraint, and it did NOT resolve the fork. Stereospecific binding is **not** a persistent torque: a
specific actin interface sets the head's full orientation **at the bind instant, once**, and thereafter the head
is held by the locks it ALREADY has (⊥ hold + roll lock). This test does that: **set mhat's sign at the bind
event, add NO new steady-state torque.**

## STEP 0 — which mhat pole is productive (resolved analytically)
Match the head-frame swing law to the productive f̂-target law:
- head-frame: `uTarget = cosθ·mhat − sinθ·(ŷ_head × mhat)`
- f̂-target (`myoNeckStrokePolarity`, −3.96): `uTarget = cosθ·mhat + sinθ·t̂`, `t̂ = −(f̂ ⊥ mhat)`

Identical iff `ŷ_head × mhat = −t̂ = (f̂ ⊥ mhat)`. In the canonical frame (f̂ = +X; roll lock gives
ŷ_head = +ŝ = n̂×f̂ = +Y):
- **mhat = +Z (+n̂):** h = ŷ×mhat = +X, t̂ = −X → h = −t̂ ✓ **head-frame ≡ productive f̂-target**
- mhat = −Z (−n̂): h = −X → h = +t̂ ✗ (reversed axial sweep)

**Productive pole = mhat = +n̂ (+Z, bed normal).** The stroke law reads `myoMotor.uVec` **directly** (not
`uVecR`); the frame JSON's `mhat = normalize(end2−end1) = +uVec`, so the productive pole reads as
`mhat·n̂ > 0` in the census.

## STEP 1 — set the sign at bind (`MyoFilLink.setAttachment`, flag-gated)
At the bind event only: initialize `myMotor.uVec = +(n̂ ⊥ f̂)` and `yVec = +ŝ` (consistent with the ⊥ hold +
roll lock that then maintain it). **No new per-step torque.** Per-motor pure.

**Critical detail — reorient about the BOUND TIP, not the head center.** These runs are *tip*-bind (the actin
site is the motor tip = the cross-bridge spring anchor). A naïve pose-set that rotates about the head *center*
moves the tip by ~½ motor length → spikes the spring load → catch-slip/break sheds the bind. First (naïve)
run confirmed this confound: **avgBound 21→14, glide −0.96** — a bind-time positional yank, NOT the sign. The
fix holds the tip fixed and swings the body around it (`coord = tip − halfMot·mhat`); centerBind → halfMot=0 →
coord unchanged (graceful).

## STEP 2 — measurement (LONG d1000 assay, LS-centroid along f̂; killed at t=0.38s once settled)

| config (d = 1000) | v_axial | axial/avgBound | mhat census |
|---|---:|---|---|
| f̂-target (`myoNeckStrokePolarity`) | −3.96 | avgBound 21.2 | (n/a — reads f̂) |
| head-frame, sign FREE (`myoNeckStrokeHeadFrame`) | −2.54 | avgBound 21.5 | mhat 50/50 (stdev 0.889) |
| **+ bind-set (naïve, center-rotate)** | −0.96 | **avgBound 14** ⚠ | +n̂ locked — but YANK confound |
| **+ bind-set (tip-preserving, clean)** | **−1.9** | **avgBound 21** ✓ | **+n̂, frac>0 0.97, NO decay** |

Clean run, steady window: **LS[0.10,0.38] = −1.95, LS[0.20,0.38] = −1.74, LS[0.30,0.38] = −1.90 µm/s**
(avgBound ~21). mhat census **frac>0 = 0.96–0.98 in every time bin** (t=0.05→0.38, mean +0.83); roll +ŝ 0.99.

## VERDICT — FORK 3: the 50/50 sign does NOT set the speed; −2.5 is honest
- **avgBound stays ~21** (not 14) ⇒ this is a true initialization, NOT a re-introduced pin. (The prior torque
  collapse WAS a pin; this proves it.)
- **mhat STAYS set** (frac>0 0.97 across the whole run, no drift toward 0.5) ⇒ the bind-time sign is **retained**
  for the full ~0.6 ms bound lifetime by the existing ⊥ hold + roll lock. **Fork 2 ("decays back") is ruled out.**
- **Speed does NOT recover toward −3.96** — it stays in the head-frame band (~−1.9 to −2.5). **Fork 1
  ("second free sign") is ruled out.**

⇒ **The mhat 50/50 is genuinely irrelevant to the glide speed.** −2.5 (head-frame) is the honest number.

**Why STEP-0's equivalence doesn't buy back −3.96:** head-frame ≡ f̂-target requires a *perfect* head frame
(mhat exactly +n̂). But even with the sign pinned, **mhat·n̂ mean is only +0.83 (~34° of continuous thermal
jiggle)**. The head-frame law amplifies that instantaneous orientation noise into the swing direction; the
f̂-target reads the filament axis directly (noise-free). Fixing the **discrete** sign degeneracy leaves the
**continuous** jiggle untouched — and it is the jiggle, not the sign, that caps the speed at ~−2.5. Removing the
jiggle would require a stiffer head hold, which pin-absorbs (`HEAD_AXIS_SIGN_LOCK.md`). So −2.5 is a structural
property of an honestly-noisy head, not a fixable artifact.

## BoA ↔ v2
Both codes sit in the head-frame band (BoA −2.54 free / −1.9 sign-set; v2 ~−2.1). The bind-set does not move BoA
out of that band → consistent with v2. If v2 runs the same bind-time sign-set, it should likewise retain the
sign (no decay) without recovering toward its f̂-target — the head-noise cap is code-independent. (v2 is a
separate codebase; this is the BoA half for their cross-check.)

## Release pathway (unchanged)
Catch-slip only on the F8 tip-spring colinear load (`ckRelease`: Guo–Guilford on `forceDotFil`) + a
`myosinBreakForce` safety cap; `inRigor` never catch-slip-releases. The bind-set touches only the head's bind
pose (uVec/yVec/coord), never the release code. The avgBound recovery 14→21 is the same pathway responding to
the removed bind-yank, not a release-law change.

## Status
- Flag-gated, default OFF; CPU only (GPU joint kernel still polarity-blind; GPU parity of the bind pose-set via
  the scatterPose delta path is a deferred follow-on).
- Run killed at t=0.38 s (133 frames) — speed clearly settled ~−1.9, no trend toward −3.96; census flat.
- `myoHeadAxisBindSet` kept OFF as the documented clean negative result. It supersedes `myoHeadAxisSignLock`
  (which should stay OFF — its collapse was pin-absorption, not the fork).

## Reproduce
```
BoxOfActin -r -pf ParameterFiles/glidingAssay_axlock_d1000_long_hf_bindset -3js ~/Code/threejs_output/axlock_d1000_hf_bindset_tip
# (CPU; TornadoVM argfile + -Xmx12G as in FIXED_HEAD_NECK_STROKE_MOTOR.md)
```

## Journal line
2026-07-01 — Head-axis (mhat) sign SET AT BIND (`myoHeadAxisBindSet`, initialization not a torque; corrects the
`HEAD_AXIS_SIGN_LOCK` mis-diagnosis). STEP-0 (analytic): productive pole = mhat=+n̂ (stroke law uses uVec
directly). STEP-1: at setAttachment set uVec=+n̂/yVec=+ŝ, reorienting ABOUT THE BOUND TIP (naïve center-rotate
yanked the spring → avgBound 21→14, glide −0.96 confound; tip-preserving fix restores it). STEP-2 (clean d1000):
avgBound ~21, mhat frac>0 0.97 with NO decay (sign retained full bound life), glide LS **−1.9 µm/s** — stays in
the head-frame band, does NOT recover toward the f̂-target −3.96. **FORK 3:** the mhat 50/50 is irrelevant to
speed; −2.5 is honest. The residual gap to −3.96 is CONTINUOUS head jiggle (mhat·n̂ mean +0.83 ≈ 34°), which a
discrete sign-set can't remove and a stiffer hold pin-absorbs. Release = catch-slip-on-F8 (per v1, unchanged).
Flag default OFF, CPU-only, BoA-v1ref untouched. Head-frame 2-lock (−2.54) stands as final.
