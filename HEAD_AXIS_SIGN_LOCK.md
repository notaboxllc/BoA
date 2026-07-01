# Head-Axis (mhat) Sign Lock — the last free head DOF → PIN-ABSORPTION (BAIL)

**Date:** 2026-07-01. **Flag:** `myoHeadAxisSignLock` (Env.java, default OFF; CPU path only; used on top of
`myoFixedHeadNeckStroke` + `myoAxialSwingLock` + `myoNeckStrokeHeadFrame`). Sequel to
`NECK_STROKE_HEADFRAME.md`. `BoA-v1ref` untouched — measurement/faithfulness only, no kinetics/geometry retune.

## The fork this test was meant to resolve
Head-frame stroke leaves `mhat` (the motor head long axis) free to be ±n̂, and it came out ~50/50 (stdev 0.889).
Two readings:
- **"second-sign-not-noise"** — the mhat = −n̂ half is a *second productive orientation*; pinning mhat to one
  sign should **recover** speed toward the f̂-target's −3.96 µm/s.
- **"noise"** — the 50/50 is honest head-orientation freedom; pinning it does nothing useful.

## The lock (what was added)
`MyoFilLink.alignUVecSignAxial()` (gated on `myoHeadAxisSignLock`): a compliant restoring torque on the **head
only** driving `mhat → +n̂` (bed normal, lab +Z), same coefficient/drag as the other head locks
(`k = myoJ1FracMoveTorq = 0.4`). Applied every step alongside the polar hold (mhat ⊥ f̂) and the roll lock
(yVec → +ŝ). This is the third compliant head-orientation torque.

## Result (LONG d1000 assay, LS-centroid along f̂, run killed once stats were clear at t≈0.42 s)

| config (d=1000) | v_axial | axial frac | avg bound | mhat sign | roll |
|---|---:|---:|---:|---|---|
| f̂-target (`myoNeckStrokePolarity`) | −3.96 | 1.00 | 21.2 | (n/a) | — |
| head-frame (2 locks) | −2.54 | 1.00 | 21.5 | **50/50** (stdev 0.889) | 100% +ŝ |
| **+ axis-sign lock (3 locks)** | **+0.22** | — | **13.9** | **100% one side** (stdev ~0) | 100% +ŝ |

**VERDICT: BAIL — pin-absorption, neither fork.** The lock does exactly what it says mechanically — the mhat
sign goes from 50/50 to **100% consistent** (the last free head DOF is pinned). But it is a **third** compliant
torque stacked on an already-doubly-held head, and the head crosses from "held" into "pinned": the neck stroke
can no longer reorient the head, so it is **absorbed** instead of transmitted to the filament. Two independent
signatures:
1. **avg bound 21.5 → 13.9** — rigidly-held heads hit the break-force threshold and release more; fewer stay bound.
2. **glide −2.54 → +0.22 µm/s** — the centroid stops drifting and just jitters (cx in [5.656, 5.726], no net
   drift over the whole steady window t≥0.25 s; the head-frame run was gliding cleanly by this same t).

So pinning mhat does **NOT** recover toward −3.96 (rules out "second-sign-not-noise") and does not leave the
glide intact either — it **kills** it. The correct reading: **the mhat 50/50 is genuine head-orientation
freedom the mechanism NEEDS to glide**, not a recoverable productivity split. Removing that DOF over-constrains
the head → pin-absorption, exactly the failure mode the task said to bail on ("compliant lock; bail if it needs
to be pin-like to hold"). Here it holds *too* well.

**Decision: reject `myoHeadAxisSignLock`. The head-frame 2-lock model (`myoNeckStrokeHeadFrame`, −2.54 µm/s,
mhat free 50/50) stands as the defensible motor.** The mhat 50/50 is head-orientation noise honestly inherited
(the harmless Z-reflection of `NECK_STROKE_HEADFRAME.md` §"Note on mhat"), and it must remain free.

## Implementation caveat (immaterial to the verdict)
`alignUVecSignAxial()` targets **+n̂** (nz=+1) but the census settled at **mhat·+n̂ = 0.00** (i.e. −n̂): the
head's `uVec` is anti-parallel to the intended motor axis (`uVecR` is the productive head direction), so a
+n̂ target locks the *rendered* uVec to −n̂. This does **not** change the verdict — the sign is locked to a
single side either way, and the productive test is the speed, which collapses regardless of which pole.

## Release pathway (unchanged, per v1)
Catch-slip only on the F8 tip-spring colinear load (`ckRelease`: Guo–Guilford on `forceDotFil`) + a
`myosinBreakForce` safety cap; `inRigor` never catch-slip-releases. The avg-bound drop above is this same
pathway responding to the higher head strain the pin creates — not a release-law change.

## Status
- Flag-gated, default OFF; CPU only (GPU joint kernel still polarity-blind).
- Run killed at t≈0.42 s (142 frames) — the glide/no-glide difference vs head-frame is unambiguous well before 1 s.
- `myoHeadAxisSignLock` should stay OFF; kept in the tree only as the documented negative result.

## Journal line
2026-07-01 — Head-axis (mhat) sign lock (`myoHeadAxisSignLock`, 3rd compliant head torque → mhat→+n̂):
mechanically pins the last free head DOF (mhat sign 50/50→100%, stdev 0.889→~0; roll still 100% +ŝ) but
**over-constrains the head → PIN-ABSORPTION**: avg bound 21.5→13.9, glide COLLAPSES −2.54→**+0.22 µm/s**
(LS-centroid, no net drift). Resolves the fork: pinning mhat does NOT recover toward the f̂-target −3.96
("second-sign") and does not preserve the glide — the mhat 50/50 is head-orientation freedom the mechanism
NEEDS. BAIL per the compliant-only constraint; head-frame 2-lock (−2.54) stands as final. Release =
catch-slip-on-F8 (per v1). Flag default OFF, CPU-only, BoA-v1ref untouched.
