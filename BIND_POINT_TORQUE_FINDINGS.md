# Bind point along the head: is the neck-swing's J1 torque parasitic, or is it the propulsion?

**Date:** 2026-07-02. **Flag:** `myoBindPoint` (Env.java, default OFF ⇒ tip; CPU path only). Default f̂-directed
neck-stroke motor (`NECK_STROKE_DEFAULT_PROMOTION.md`). `BoA-v1ref` untouched; no release/stroke/kinetics change —
**only the bind point moves.** Reads: `FIXED_HEAD_NECK_STROKE_MOTOR.md`, `PROPER_SPEED_ANALYSIS.md`,
`CAPTURE_RADIUS_SENSITIVITY.md`, `HEAD_AXIS_BIND_SET.md`, `PHASE2_SPEED_LEVERS_FINDINGS.md` (in `~/Code/SoftBox`).

## Hypothesis under test (jba)
The power stroke is a pure torque on the neck ⇒ a constraint force at the neck–head junction **J1** (= head end1,
the converter side). J1 sits ~a head-length (20 nm) from the actin contact (the F8-anchored head **tip**), so that
force resolves into a **torque about the binding point**, transmitted through the head's orientation locks to the
filament as transverse/rotational disturbance. **Prediction:** this parasitic torque saps directed speed, so
shrinking the J1→contact moment arm (tip→mid→rear) should **raise** v_axial, **raise** axial fraction, and **lower**
transverse RMS.

## The probe — move the bind point along the head
`myoBindPoint` p ∈ [0,1] slides BOTH the bind-decision projection point AND the cross-bridge spring anchor (kept
**identical**, so d≈0 at bind ⇒ no positional yank) a fraction p along the head from tip to J1. One helper,
`Env.myoBindHeadOffset() = (0.5 − p)·myoMotorLength`, is the single source of truth, threaded through the three CPU
sites: bind decision (`MyoMotor.checkFilSegCollision`), spatial-bin `bindTip` (`MyoMotor.initialize`), and the
spring anchor (`MyoFilLink.addForces`). The setAttachment reorient-about-bound-point path uses it too.

| p | bind point | J1→contact moment arm | topology |
|---|---|---|---|
| **0.0 (default)** | tip = coord + ½L·û (= end2) | full = L (~20 nm) | 3-body head+neck lever — **byte-identical to the prior default** |
| **0.5** | mid = coord | half = ½L (~10 nm) | same 3-body — **CLEAN isolate: only the arm changes** |
| **1.0** | rear = J1 = coord − ½L·û (= end1) | zero | collapses head+neck to ~2-body at the contact |

**No-yank / stationary-bound verified.** Because the decision point and the spring anchor are the *same* point,
which the bind test requires to be within `myoColTol` (6 nm) of the filament axis, the spring length at bind is the
same ≤6 nm perpendicular gap at every bind point — no new strain. Empirically, bound **lifetimes hold** at every
bind point (tip 34.9, mid 42.8, rear 24.3 steps in a 5000-step probe); a bind-time positional yank would collapse
lifetime to ~1 step and crater avgBound (as the naïve center-rotate bind-set did, 21→14 in `HEAD_AXIS_BIND_SET.md`).
Binds persist ⇒ moving the anchor injects no yank.

## Method
Standard gliding assay: full d1000 fixed-myosin mat, box 14×2×0.5 µm, one 2 µm gliding filament, dt 1e-5, Brownian
on, CPU. Gliding velocity = LS-centroid drift projected on f̂ over a settled window (`PROPER_SPEED_ANALYSIS.md`; the
`.dat` posX/Y/Z track equals the frame centroid exactly — verified). **3 fresh launches per bind point** (the BoA
mat is `Math.random`-nondeterministic ⇒ 3 distinct mats). First wave settled to t=0.70 s (window [0.21,0.70],
LS converged across [0.07/0.14/0.21,0.70] starts); later draws corroborate. `transRMS` = centroid wander ⊥ f̂ about
the drift line; `fhatAngRMS` = RMS wander of the filament axis (rotational disturbance).

Param files: `ParameterFiles/glidingAssay_d1000_bindpt_{tip,mid,rear}` (p = 0.0/0.5/1.0). Runners: `run_bindpt.sh`;
analysis: `fast_bindpt.py` (.dat + sparse frames), `analyze_bindpt.py` (per-run convergence).

## Result — the glide does not recover toward the rear; it MONOTONICALLY WEAKENS, REVERSES SIGN, and its
## rotational disturbance RISES

| bind point (arm) | **v_axial** (µm/s) | avgBound | **per-bound drift** | **axial frac** | **transRMS** (nm) | **fhatAngRMS** (deg) |
|---|---:|---:|---:|---:|---:|---:|
| **tip** (full, p=0, default) | **−2.96 ± 0.31** | 16.3 ± 0.7 | **−0.181 ± 0.013** | 1.000 ± 0.000 | 24.4 ± 1.2 | 5.9 ± 0.8 |
| **mid** (half, p=0.5) | **−0.79 ± 0.17** | 29.4 ± 0.3 | **−0.027 ± 0.006** | 0.988 ± 0.004 | 19.7 ± 2.5 | 6.6 ± 0.5 |
| **rear = J1** (zero, p=1) | **+0.87 ± 0.08** | 9.1 ± 3.3 | **+0.103 ± 0.024** | 0.909 ± 0.049 | 28.3 ± 1.9 | **16.9 ± 1.2** |

(All runs settled to t=0.70 s, LS window [0.21,0.70]; mean ± population SD; **n=3** at every bind point. Signs:
**negative = pointed-leading = correct glide**; **positive = barbed-leading = REVERSED**.)

Settled per-run anchors (0.70 s, converged): tip −2.94 / −3.36 / −2.60; mid −0.78 / −0.59 / −1.00;
rear **+0.97 / +0.77 / +0.85**. Note rear avgBound is mat-sensitive (13.6 / 6.1 / 7.6 — the reach confound below),
so per-bound drift, not avgBound, is the invariant readout (rear drift +0.071 / +0.127 / +0.112, all positive).

## Verdict — the parasitic-torque hypothesis is REFUTED. The J1→contact arm is the PROPULSION lever, not a drag.

Every directional prediction of the hypothesis is **inverted**:

| hypothesis predicted (tip→rear) | observed (tip→rear) |
|---|---|
| v_axial **rises** (shed parasitic torque) | v_axial **falls monotonically −2.96 → −0.79 → +0.87 and reverses sign** |
| axial fraction **rises** toward 1 | axial fraction **falls** 1.000 → 0.988 → 0.909 |
| transverse RMS **falls** | transverse RMS **flat/rises** (24 → 20 → 30 nm, no fall) |
| (rotational disturbance smaller at zero arm) | rotational wander **rises ~3×** 5.9° → 16.9° at zero arm |

So the moment arm between J1 and the actin contact is **not a parasitic torque dragging a hidden forward glide** —
it **is the entire productive lever that generates the glide.** The head, held as a rigid ~90° strut, is rotated
by the neck stroke about a center near the head's tip-ward third; the filament is propelled by whichever end it is
tethered to. Tether at the **tip** (arm = L) → full pointed-leading propulsion (−2.96). Tether at the **mid**
(arm = ½L, same 3-body topology — the clean isolate) → propulsion collapses to −0.79 (the crossover sits toward the
rear, so half the arm loses ~73% of the speed, far more than a linear 50% — the head's stroke-rotation center is
tip-ward). Tether at **J1** (arm = 0) → propulsion is gone and the residual **reverses** to +0.87, with the
head/neck now flailing at the contact (fhatAngRMS 17°). This is clean rigid-body-lever physics, per-bound-pure (the
per-bound-drift bands **−0.181 / −0.027 / +0.103** do not overlap, so the
result survives the mat-count and mat-draw scatter that `CAPTURE_RADIUS_SENSITIVITY` / `PROPER_SPEED_ANALYSIS`
flagged).

**Where it lands on the task's fork:** it is the **geometry** branch, but far stronger and cleaner than "the rear
gain is geometry" — there is *no gain anywhere*; the midpoint moves a lot (not "barely"), and the effect is a
monotonic **destruction + reversal** of transport. The hypothesis's premise — that a forward glide exists
independently and the J1 torque merely taxes it transversely — is false: kill the arm and the forward glide is
gone. This directly corroborates `PHASE2_SPEED_LEVERS_FINDINGS.md` ("the swing's effective lever is HEAD_LEN; the
J1/neck converter swing contributes ≈0 to the tip") and explains the flat neck-angle sweep there: net transport is
set by the **head-length lever arm**, not by the neck-swing amplitude. The tip-bind (arm = L) is simultaneously the
**fastest AND the lowest-rotational-disturbance** configuration — there is no parasitic-torque tradeoff to exploit,
so this is not a lever toward the skeletal 5–8 µm/s.

## Confounds (named)
- **Rear-bind changes geometry, not only the arm.** Binding at J1 removes the head's perpendicular stand-off (the
  ~90° strut) and, because J1 sits ~20 nm off the filament for a strut, it is harder to bring within `myoColTol`
  (avgBound 9.1 vs tip 16.3, and mat-sensitive: 13.6 / 6.1 / 7.6 across draws). So the rear result is torque-arm **and**
  changed pose/reach — it is the confounded extreme, reported as such. **The midpoint is the clean isolate**
  (identical 3-body topology, head still a strut, only the arm halved) and it already shows the effect
  unambiguously (−2.96 → −0.79, a **73% loss** with *no* topology change), so the verdict does not rest on the
  rear run.
- **axial fraction was already ~1.0 at tip** (0.999). Per the task's note, a change there is not "reduced transverse
  wander" — indeed transRMS is flat. The finding is sharper: moving the bind point toward J1 does **not** reduce
  rotational work at the contact, it **increases** it (fhatAngRMS 5.9° → 16.9°) while destroying propulsion. The tip
  geometry minimizes both — no hidden rotational tax to recover.
- **transRMS window-length artifact:** the mixed-settle aggregate under-reads transRMS for the shorter later-draw
  windows; the equal-0.70 s settled subset (quoted above) is the apples-to-apples comparison and shows the same
  flat trend.

## Faithfulness
The myosin head's actin-binding interface (lower-50 kDa / actin cleft) is a real structural site, **offset from the
converter/lever junction by ~the motor-domain dimension** — i.e. the contact is near the tip, far from J1. So
**tip-bind is the physically defensible geometry**, and it is the default. **Mid and rear are mechanism probes, not
adoptable poses:** mid (half arm) is a clean torque-arm isolate with no structural claim; rear (contact coincident
with the converter) is structurally indefensible — the actin site is not at J1. The result is, if anything, a
faithfulness **asset**: it shows the model's correct pointed-leading directionality *depends on the actin contact
being offset toward the head tip*, which is anatomically correct. This is diagnosis, **not a promotion** — the
default stays tip (`myoBindPoint` OFF).

## Status
- Flag-gated, default OFF (= tip, code-path-identical to the prior default: with the flag inactive
  `myoBindHeadOffset()` returns +½L exactly as before; tip-via-flag reproduces the default meanBound 16.4 ≈ 16.6).
- CPU only (GPU binding/force kernels unchanged for this flag). `BoA-v1ref` untouched. No stroke/release/kinetics
  change.

## Reproduce
```
# build (aorus, Java 21 + TornadoVM classpath, CPU path)
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java
# 3 bind points x 3 mat draws, d1000, 0.7 s each:
./run_bindpt.sh 0.7 4
python3 fast_bindpt.py /tmp/bindpt_runs 0.30
```

## Journal line
2026-07-02 — Bind-point-along-head moment-arm probe (`myoBindPoint` p∈[0,1]: tip 0 / mid 0.5 / rear=J1 1; default
OFF=tip, CPU, one `Env.myoBindHeadOffset()` helper into checkFilSegCollision + bindTip + addForces; decision point ≡
spring anchor ⇒ d≈0 at bind, no yank — bound lifetimes hold 35/43/24 steps). Tests jba's hypothesis that the neck
stroke's J1 constraint force is a *parasitic* torque about the contact that saps a forward glide (predicting speed
RISES tip→rear). **REFUTED, and refuted informatively:** v_axial **falls monotonically and reverses** −2.96±0.31 →
−0.79±0.17 → +0.87±0.08 µm/s (per-bound drift −0.181/−0.027/+0.103, non-overlapping bands, n3 each); axial fraction
FALLS 1.00→0.99→0.91; transRMS flat/rising 24→20→28 nm; rotational wander RISES ~3× (fhatAng 5.9°→16.9°). The
J1→contact moment arm is the **productive propulsion lever, not a parasitic drag** — kill the arm (rear) and the
glide is gone and slightly reverses; the midpoint (clean half-arm isolate, same topology) already loses 73%. Confirms
`PHASE2` (effective lever = HEAD_LEN; neck-angle sweep flat) and explains it. Faithful geometry = tip (actin site
offset from the converter) = the default; mid/rear are probes (rear structurally indefensible + removes the strut
stand-off — confound named, midpoint weighted). Diagnosis, not a promotion. `BoA-v1ref` untouched.
