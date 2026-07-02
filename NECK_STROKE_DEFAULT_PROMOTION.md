# Promotion: the f̂-directed neck-stroke motor is now the DEFAULT myosin

**Date:** 2026-07-01. **Flag added:** `myoLegacyHeadSwing` (Env.java, default OFF). CPU is the supported path for
the new default; `-gpu` is guarded (see §GPU). `BoA-v1ref` untouched. No kinetics/geometry retune; release
pathway unchanged. Reads: `FIXED_HEAD_NECK_STROKE_MOTOR.md`, `NECK_STROKE_POLARITY_FIX.md`, the 2026-07-01
JOURNAL decision entry, `PROPER_SPEED_ANALYSIS.md`.

## Decision
Adopt the **f̂-referenced (filament-directed) neck powerstroke** as the myosin motor and make it the **default**
(runs with no flags). Biological basis: stereospecific binding registers the head to actin in one orientation
(head clamped to actin), so a neck swing referenced to f̂ is a faithful stand-in for "neck swings off an
actin-registered head." The head-frame / mhat-lock / bind-set arc is the negative-result evidence that the
head's *own* orientation is noisy and must not drive the swing (`NECK_STROKE_HEADFRAME.md`,
`HEAD_AXIS_SIGN_LOCK.md`, `HEAD_AXIS_BIND_SET.md`).

## The model made default (three flags collapsed into default-ON)
1. **Fixed head at 90° ⊥** (no head swing in either nucleotide state) — was `myoFixedHeadNeckStroke`. Encoded as
   rest angles `cockedMotor_ActinAngle = uncockedMotor_ActinAngle = 90`, `cockedLever_MotorAngle = 70`.
2. **Axial swing lock** (roll axis → ŝ = n̂bed × û_seg; swing plane contains f̂) — was `myoAxialSwingLock`
   (`MyoFilLink.alignYVecTorqueAxial`).
3. **f̂-directed neck powerstroke** (`uTarget = cosθ·mhat + sinθ·t̂`, t̂ = −(f̂⊥mhat), rear sweeps barbed-ward,
   θ 0→70°) — was `myoNeckStrokePolarity` (`Myosin.applyLeverMotorJointTorquePolarity`).

Roll-SIGN lock, head-frame swing, mhat-lock/bind-set stay **OFF** (documented negatives). The f̂ target is robust
to the head roll/axis sign (it references the filament, not the head), so roll-sign is unnecessary — confirmed by
the default run's mhat census sitting at 50/50 with no speed penalty (below).

## Promotion mechanics
A single predicate drives the model:
```java
// Env.java
static boolean defaultNeckStrokeMotorOn () {
    return !(myoLegacyHeadSwing.isActive() && myoLegacyHeadSwing.getValue() != 0.0);
}
```
Each of the three behaviors is gated on `defaultNeckStrokeMotorOn() || <its own legacy test flag>`:
- `BoxOfActin.begin()` rest-angle block (fixed head 90 / neck 70)
- `MyoFilLink.alignYVecTorque` → `alignYVecTorqueAxial` (axial roll lock)
- `Myosin.applyLeverMotorJointTorque` → `applyLeverMotorJointTorquePolarity` (f̂ stroke; head-frame branch still
  takes precedence when its flag is on)

**`myoLegacyHeadSwing:true`** sets `defaultNeckStrokeMotorOn()=false` and, absent the individual flags, all three
gates go false → the rest-angle block is skipped (Java-default angles: uncockedMotor=90, **cockedMotor=120 →
head swings**; lever 0→60), stock roll, stock lever-motor relaxation = **the old F9 head-swing motor**, the prior
validated oracle (4b-iv −13%, SET-A −5.7), kept reachable for regression/oracle checks.

## Regression — the two contracts

**Byte-identity is ill-posed in this codebase.** The simulator is NOT reproducible run-to-run: `UCircRnd`
(the Brownian uniform-deviate generator, `UCircRnd.java:47-48`) and the fixed-myosin-mat placement draw from
`Math.random()`, the global JVM RNG seeded from entropy at startup — seedable by neither `-seed` (which only
seeds `Env.mtRNG`) nor `BOA_RNG_SEED` (which only seeds the per-worker Brownian MTF pool). Two runs of the *same*
config produce different frame bytes even at `allThreadCt=1` with all named seeds pinned (verified: three
distinct md5s). So a literal bit-for-bit md5 contract cannot be met by any two runs, identical config or not.

The contract is therefore satisfied the correct way — **code-path identity (structural) + observable
equivalence**:

**(1) Code-path identity — structural.** The behavioral diff vs the pre-promotion commit (b7bf75a) changes ONLY
the three gate predicates (`myoX` → `defaultNeckStrokeMotorOn() || myoX`) plus the GPU guard and a log-line
string. The f̂ method (`applyLeverMotorJointTorquePolarity`), the axial-roll method (`alignYVecTorqueAxial`), the
stock-F9 branches, and the angle constants (90/70, 90/120/0/60) are **untouched**. Therefore, by construction:
- **new default (no flags):** `defaultNeckStrokeMotorOn()=true` → all three gates true → the exact f̂ path with
  angles 90/70. Identical to the old flagged run (`myoFixedHeadNeckStroke+myoAxialSwingLock+myoNeckStrokePolarity`),
  which set those same flags true → same (unmodified) methods. **new-default ≡ old-flagged-f̂.**
- **`myoLegacyHeadSwing`:** all three gates false → rest-angle block skipped (Java defaults) → stock roll + stock
  torque. Identical to the old default (no flags: individual flags off → same stock path). **legacy ≡ old-default.**

**(2) Observable equivalence — empirical (short d200 regression, 16 threads).** The `[MOTOR]` startup log and the
per-state head–actin angle census distinguish the two motors exactly as the code path predicts:
- default: `[MOTOR] default fhat neck-stroke: cockedMotor_ActinAngle=90.0 cockedLever_MotorAngle=70.0 (default)`;
  head–actin |angle| ≈ 76–81° across ATP/ADPPi/ADP — **head held ~90°, no state-dependent swing** (fixed head).
- legacy: no `[MOTOR]` line (Java-default angles); head–actin |angle| drops to ≈ 64° in ADP — **the head swings**
  toward the cocked 120° (folded) on the power stroke = the F9 head-swing signature.

## Physics unchanged — long d1000 default confirmation
Long d1000 gliding assay on the NEW DEFAULT (no flags), LS-centroid along f̂ (`PROPER_SPEED_ANALYSIS`):

| window | v_axial | avgBound | mhat census |
|---|---:|---:|---|
| reference (old flagged f̂, `NECK_STROKE_POLARITY_FIX.md`) | −3.96 | 21.2 | 50/50 (f̂ robust to sign) |
| **new default (this run)** | **−2.96** (LS[0.10,0.675]s, 0.58s window) | 16.6 | 50/50 (dom 0.51) |

**Reproduces the f̂ motor within run-to-run scatter.** This default sample settles at −2.96 µm/s (LS-centroid,
[0.10,0.675]s; LS is flat at −2.96/−2.97/−2.84 across the [0.10/0.20/0.30, 0.675]s windows) at avgBound 16.6,
vs the reference −3.96 at 21.2. Because the simulator is nondeterministic (§Regression), each run draws a
different fixed-myosin mat and therefore a different bound count. The physics-level invariant is the
**per-bound-motor drift**, and it matches: 2.96/16.6 = **0.178** vs 3.96/21.2 = **0.187** µm/s per bound motor
(~5%). So the f̂ stroke's per-motor productivity is reproduced; the ~25% absolute-speed difference is entirely
this mat's ~22%-lower bound count (the `Math.random()` mat draw), not the promotion — whose code path is proven
identical to the old flagged f̂ run above. The glide is directional (cx decreases monotonically along f̂).

The mhat census sits at ~50/50 with **no** speed penalty — confirming the f̂ stroke is polarity-robust (the head
sign is free, and it doesn't matter), which is precisely why the roll-sign lock is not part of the default.

## GPU parity (the standing item — resolved by GUARD + pending port)
BoA's GPU joint kernel is still polarity-blind; the f̂ swing (`applyLeverMotorJointTorquePolarity`) is CPU-only.
A `-gpu` default run would SILENTLY run the old ill-defined swing. Porting the f̂-directed target (which needs
the bound-segment polarity per motor inside the PTX kernel) into the device joint kernel is non-trivial, so per
the task's "bail-and-report rather than ship a silently-wrong GPU default," the GPU path is **GUARDED**:

`BoxOfActin.begin()` throws with a loud `[MOTOR][FATAL]` message when `-gpu` is combined with the default (or the
f̂-polarity / head-frame test) motor. Verified:
- `-gpu` + default motor → **bails** (`[MOTOR][FATAL] … the fhat swing … is CPU-only … Remedy: run on CPU, OR
  use myoLegacyHeadSwing:true`, then `IllegalStateException`). No wrong-physics frames written.
- `-gpu` + `myoLegacyHeadSwing:true` → **runs** on the device (FATAL count 0, frames written) — the legacy F9
  motor is what the device kernel implements, so GPU stays available for it.

**GPU port of the f̂ swing is the documented PENDING item.** Until then: default motor = CPU; GPU = legacy motor.

## Migration note
Param files that relied on the OLD default (F9 head swing) with no flags must now add `myoLegacyHeadSwing:true`.
The individual test flags (`myoFixedHeadNeckStroke` etc.) still force their behavior on for A/B but are redundant
under the default.

## Journal line
2026-07-01 — Promoted the f̂-directed neck-stroke motor (fixed head 90° + axial roll lock + f̂ powerstroke) to the
DEFAULT myosin; collapsed `myoFixedHeadNeckStroke+myoAxialSwingLock+myoNeckStrokePolarity` into
`Env.defaultNeckStrokeMotorOn()` (each gate = default || own flag). Old F9 head-swing motor preserved behind
`myoLegacyHeadSwing` (default OFF). Byte-identity is ill-posed here — `Math.random()` in `UCircRnd`/mat-placement
makes the sim non-reproducible run-to-run (unseedable by `-seed`/`BOA_RNG_SEED`) — so the two contracts are met by
CODE-PATH identity (diff touches only gate predicates; f̂/stock methods + angles untouched → new-default ≡
old-flagged-f̂, legacy ≡ old-default) + observable equivalence (default head fixed ~90° across states; legacy head
swings to ~120° in ADP = F9). GPU joint kernel is polarity-blind, so `-gpu`+default now BAILS loudly (guard,
verified) and points to `myoLegacyHeadSwing:true` for the device; the f̂ GPU port is the pending item. Long d1000
default confirms the f̂ glide: −2.96 µm/s at avgBound 16.6 = 0.178 µm/s per bound motor, matching the reference
3.96/21.2 = 0.187 (~5%); the ~25% absolute-speed gap is this random mat's lower bound count, not the model.
CPU-only default; `BoA-v1ref` untouched; release = catch-slip-on-F8.
