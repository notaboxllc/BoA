# Gliding Filament Assay — Density 1000 motors/µm² (v1 code)

**Date:** 2026-06-30
**Code:** v1 (CPU path; build compiled against TornadoVM, run without `-gpu`)
**Param file:** `ParameterFiles/glidingAssay1000_val`
**Output frames:** `~/Code/threejs_output/gliding1000/` (103 frames), data file `gliding_assay.dat`

## Setup

A single 2 µm gliding filament over a fixed myosin bed at **1000 motors/µm²**.

- Bed: 14 × 2 × 0.5 µm → 14×2 = 28 µm² surface → **28,000 fixed motors**
- One canonical X-axis gliding filament (`initialFilaments:false` — the lone hardcoded filament from `setUpGlidingAssay`)
- 0.1 s simulated, 100 output frames

## Important parameters

| Parameter | Value | Note |
|---|---|---|
| `fixedMyosinDensity` | 1000 | motors/µm² (the density knob) |
| `glidingAssay` | true | enables the assay |
| `externalDensitySweep` | true | density-sweep mode |
| `fixedMyosinZValue` | -0.05 | motor bed Z plane (µm) |
| `glidingFilamentLength` | 2.0 | µm |
| `filSegLength` | 64.0 | monomers/segment |
| `boxXDim / boxYDim / boxZDim` | 14.0 / 2.0 / 0.5 | µm (28 µm² bed) |
| `deltaT` | 1.0e-5 | s |
| `biochemDeltaT` | 0.01 | s |
| `collisionDeltaT` | 1.0e-5 | s |
| `runTime` | 0.1 | s simulated |
| `toFileInterval` | 100 | steps/frame |
| `aeta` | 0.1 | Pa·s (medium viscosity) |
| `fracMove / fracR / fracMoveTorq` | 0.5 / 0.1 / 0.2 | integration tuning |
| `noMonomersSimd` | true | gliding production mode |
| `kRdmNuc / kNodeNuc / initialMyoMiniFils / equilNodes` | off | nucleation/population suppressed |

**Run command** (TornadoVM on classpath, CPU path, 12 GB heap — the 8M-element static motor array OOMs at the CLAUDE.md `-Xmx800M`):

```
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx12G \
     -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -pf ParameterFiles/glidingAssay1000_val -3js ~/Code/threejs_output/gliding1000
```

## Result

Steady state taken as simTime > 0.02 s (82 samples), from `gliding_assay.dat`.

| Metric (steady state, t > 0.02 s) | Value |
|---|---|
| Glide speed (long-window) | ~14.0 µm/s |
| **Avg myosins bound** | **~11.5** |
| Motors within reach of the filament (footprint) | ~398 |
| Footprint duty ratio (bound / reachable) | ~0.029 |

**Interpretation:** ~11.5 motors are bound to the gliding filament at any instant. Of the ~398 motors physically under/near the 2 µm filament, only ~2.9% are engaged at a time — consistent with the low instantaneous duty ratio of a fast-cycling myosin population. The filament settles to a steady glide of ~14 µm/s (the long-window estimate starts high during the initial transient while the ring buffer fills).

**Caveat:** this is a single canonical filament gliding over the dense bed (`initialFilaments:false`). If "average number bound" should be averaged over a population, seed filaments via `initialFilaments` and rerun.

---

# Run 2 — TEST: fixed motor head, 70° neck power stroke (v1 code)

**Date:** 2026-06-30
**Code change (flag-gated test):** `Env.myoFixedHeadNeckStroke` (boolean, default OFF). When ON, the power stroke (ADP-Pi → ADP transition) no longer rotates the motor head — it is held **fixed at 90° relative to the filament** — and the entire stroke is taken up by the **neck/lever, which swings through 70° relative to its unbound (straight, 0°) rest state**, in the same torsion direction the head previously rotated.

**Implementation:** the flag overrides the `Myosin` static rest-angle fields once at startup in `BoxOfActin.begin()` (before any myosin or GPU plan is built), so both CPU and GPU paths pick it up via the existing consumers:
- `cockedMotor_ActinAngle`: 120° → **90°** (== `uncockedMotor_ActinAngle`; head no longer rotates on the stroke) — read by `MyoFilLink.alignUVecTorque()`.
- `cockedLever_MotorAngle`: 60° → **70°** (neck swings to 70° from the 0° unbound rest) — read by `Myosin.applyLeverMotorJointTorque()`.

Startup log confirms: `[TEST] myoFixedHeadNeckStroke ON: cockedMotor_ActinAngle=90.0 cockedLever_MotorAngle=70.0`.

**Param file:** `ParameterFiles/glidingAssay1000_val_neckstroke` — identical to Run 1 plus `myoFixedHeadNeckStroke:true:1.0`.
**Output frames:** `~/Code/threejs_output/gliding1000_neckstroke/` (103 frames).
All other parameters, box, density, timesteps, run length, and the CPU run command are **exactly the same as Run 1** (only the test flag added).

## Result

Steady state taken as simTime > 0.02 s (82 samples), from `gliding_assay.dat`.

| Metric (steady state, t > 0.02 s) | Run 1 (baseline) | Run 2 (fixed head / 70° neck) |
|---|---|---|
| Glide speed (long-window) | ~14.0 µm/s | **~8.5 µm/s** |
| Avg myosins bound | ~11.5 | **~15.4** |
| Motors within reach (footprint) | ~398 | ~399 |
| Footprint duty ratio (bound / reachable) | ~0.029 | ~0.039 |

**Interpretation:** removing the head swing and routing the full stroke through a 70° neck rotation **slows the glide** (~14 → ~8.5 µm/s) while **raising the average bound count** (~11.5 → ~15.4) and duty ratio (~0.029 → ~0.039). Heads stay attached longer / more are simultaneously engaged, but produce less net translational velocity per cycle under this geometry — consistent with a less efficient stroke that trades speed for occupancy. Footprint (geometrically reachable motors, ~399) is unchanged, as expected since the bed and filament are identical.

---

# Run 3 — TEST: parallel head bind, pointed-end offset (v1 code)

**Date:** 2026-06-30
**Code change (flag-gated test, independent of Run 2):** `Env.myoParallelBindOffset` (boolean, default OFF). When ON, at the bind event:
1. **The found binding point is slid exactly one motor length (0.02 µm) toward the POINTED end** of the filament. `posOnSeg` is the arclength from `end1` (the pointed/minus end; `end2` is barbed), so the shift subtracts `myoMotorLength`, clamped ≥ 0 to stay on-segment. Implemented in `MyoMotor.ontoFilament(seg, arcOnSeg)` — the single bind funnel for both CPU and GPU paths.
2. **The head's rest angle relative to the filament is forced to 180°** (head lies parallel/collinear with the actin axis) in both nucleotide states — `Myosin.uncockedMotor_ActinAngle = cockedMotor_ActinAngle = 180`, set once in `BoxOfActin.begin()` and read by `MyoFilLink.alignUVecTorque()`.

Goal: lock the head in **lying flat** so the head-neck joint lands ~at the originally found point — binding without the artificial barbed-ward head swing that exaggerates gliding ("as if the motor slides backward one motor length to lock into the site").

Startup log confirms: `[TEST] myoParallelBindOffset ON: motor-actin rest angle forced to 180 (head parallel to filament); bind point offset -1 motorLength (0.02 um) toward pointed end`.

**Param file:** `ParameterFiles/glidingAssay1000_val_parallelbind` — identical to Run 1 plus `myoParallelBindOffset:true:1.0` (Run 2's flag is NOT set; the two tests are independent).
**Output frames:** `~/Code/threejs_output/gliding1000_parallelbind/` (103 frames).
All other parameters, box, density, timesteps, run length, and the CPU run command are **exactly the same as Run 1**.

## Result

Steady state taken as simTime > 0.02 s (82 samples), from `gliding_assay.dat`.

| Metric (steady state, t > 0.02 s) | Run 1 (baseline) | Run 3 (parallel bind, pointed offset) |
|---|---|---|
| Glide speed (long-window) | ~14.0 µm/s | **~2.9 µm/s** |
| Avg myosins bound | ~11.5 | **~0.36** |
| Motors within reach (footprint) | ~398 | ~335 |
| Footprint duty ratio (bound / reachable) | ~0.029 | ~0.0011 |

**Interpretation:** the change **nearly abolishes persistent binding** (avg bound ~11.5 → ~0.36; duty ratio ~0.029 → ~0.001) and gliding nearly stops (~14 → ~2.9 µm/s, essentially residual/Brownian drift with almost no engaged motors). The likely mechanism: the binding-capture gate (`checkFilSegCollision`) only fires when the head is already positively aligned with the filament (`dot(motorUVec, filUVec) ≥ myoMotorAlignWithFilTolerance`), but the new 180° rest angle drives the head **anti-parallel** to the filament. So heads that momentarily capture are immediately torqued away from the gate-satisfying orientation (and the pointed-end-offset attach point adds strain), and they release rather than holding. Net: the geometry that removes the barbed-ward swing also removes the alignment that keeps heads engaged, so the assay loses its active gliding.

**Caveat / note for follow-up:** "180° = parallel" here means *collinear with the filament axis* (anti-parallel sense, head pointing toward the pointed end). If the intent is for heads to **stay bound** while lying flat, the bind-capture alignment gate and/or the catch-slip release would need to be made consistent with a 180° rest angle (e.g. accept |dot| alignment, or gate on the post-offset geometry) — otherwise the flat-bound state is mechanically self-releasing under the current gate. As with Runs 1–2, this is the single canonical filament (`initialFilaments:false`).

---

# Run 4 — TEST: strain-free center bind, 30° anti-parallel gate (v1 code)

**Date:** 2026-06-30
**Code change (flag-gated test, fixes Run 3's self-release):** `Env.myoCenterParallelBind` (boolean, default OFF). Three coupled changes at the bind event (CPU path):
1. **Bind side → head CENTER (not tip).** The cross-bridge spring tethers the head center (`coord`) to the attach point (`MyoFilLink.addForces`, `halfMot=0`), and the grid bins by the center (`MyoMotor.initialize` sets `bindTip = coord`). The attach point is already the filament point closest to the center (`checkFilSegCollision` projects the center), so at bind the spring length is just the perpendicular gap (≤ `myoColTol` ≈ 6 nm → ≤ ~6 pN, well under the 12 pN break force).
2. **Bind-capture gate → `angle(filUVec, motor uVecR) < 30°`.** `uVecR = −motorUVec`, so this is `dot(filUVec, motorUVec) < −cos30° = −0.866`: the head must already lie ~anti-parallel to the filament. The old positive-alignment tolerance and the rod-orientation gate are bypassed under the flag.
3. **Head rest angle → 180°** (`begin()`), matching the gate, so a freshly-bound head sits at its torque-free equilibrium — strain-free and stable.

**Orientation (verified, not backwards).** Code convention: `end2 = plus/barbed end`, `end1 = pointed end` (`FilSegment.java:4442, 1346`), so `filUVec` points pointed→barbed; the motor tip is `end2 = coord + ½L·motorUVec` (neck-joint is `end1`). The gate `motDotFil < −0.866` makes `motorUVec` anti-parallel to `filUVec`, so the tip (`+motorUVec`) points toward the **pointed** end and the neck-joint toward the barbed end — the biologically-correct binding pose. Empirical check on frame 100 (11 bound motors): mean `dot(motorUVec, filUVec) = −0.92`; **11/11 motors tip-toward-pointed, 0 toward barbed.**

**Param file:** `ParameterFiles/glidingAssay1000_val_centerbind` — Run 1 plus `myoCenterParallelBind:true:1.0` (Runs 2–3 flags NOT set; all four tests are independent).
**Output frames:** `~/Code/threejs_output/gliding1000_centerbind/` (103 frames). All other parameters identical to Run 1.

## Result

Steady state taken as simTime > 0.02 s (82 samples), from `gliding_assay.dat`.

| Metric (steady state, t > 0.02 s) | Run 1 (baseline) | Run 3 (tip + offset, 180°) | Run 4 (center bind, 30° gate) |
|---|---|---|---|
| Glide speed (long-window) | ~14.0 µm/s | ~2.9 µm/s | **~2.7 µm/s** |
| Avg myosins bound | ~11.5 | ~0.36 | **~14.7** |
| Motors within reach (footprint) | ~398 | ~335 | ~351 |
| Footprint duty ratio | ~0.029 | ~0.0011 | **~0.042** |

**Interpretation:** binding now **holds** — avg bound climbs from ~0 at t=0 to ~15 and stays there (duty ratio ~0.042, the highest of any run), in contrast to Run 3's collapse to ~0.36. Tethering the head center to its own closest filament point keeps the spring length under the break force, and the 180° rest angle matches the bind gate, so bound heads sit strain-free and don't self-release.

**⚠️ GLIDING DIRECTION IS REVERSED (defect).** Baseline (Run 1) glides in **−X = pointed-end leading** (`posX` 5.79 → 5.24), the correct biological direction. Run 4 glides in **+X = barbed-end leading** (`posX` 5.78 → 6.16, monotonic). With the head locked flat/anti-parallel there is no power stroke, so the ~2.7 µm/s net motion is a *spurious rectification* from the asymmetric flat-bind geometry — and it comes out in the wrong (barbed-ward) direction. So the "high stable occupancy" is real, but the assay is not producing correct directional motility: it should glide pointed-leading (or, with no stroke, not at all), not barbed-leading. This couples to the binding pose: the verified tip-toward-pointed orientation yields barbed-ward glide. **Root cause found (see Run 5).** As with Runs 1–3, this is the single canonical filament (`initialFilaments:false`).

---

# Run 5 — center bind, search/attach/tether all on the CENTER (bugfix of Run 4)

**Date:** 2026-06-30
**Bug in Run 4:** the binding *search* was still using the free **tip**, not the center. `MyoMotor.soaX/soaY/soaZ` (read by `checkFilSegCollision` for the perpendicular drop) are packed as `coord + ½·motorLength·uVec` = **end2 (tip)**. So `arcOnFil` / `attachPt` was the **tip's** projection onto the filament, while `MyoFilLink.addForces` (under the flag) tethered the **center**. That left a constant ~½-motorLength (~10 nm) offset between the tethered point and the attach point — a built-in strain and a directional bias, which drove the spurious barbed-ward (+X) glide.

**Fix:** in `checkFilSegCollision`, when `myoCenterParallelBind` is on, drop the perpendicular from the **center** (recovered as `tip − ½·motorLength·uVec`). Now search → attach point → tether are all the head center and consistent — truly strain-free, no offset.

**Param/run:** same `ParameterFiles/glidingAssay1000_val_centerbind`, frames `~/Code/threejs_output/gliding1000_centerbind_v2/` (103 frames), all else identical to Run 1.

## Result

| Metric (steady state, t > 0.02 s) | Run 1 (baseline) | Run 4 (tip-search bug) | Run 5 (center search, fixed) |
|---|---|---|---|
| Net dX over 0.1 s | −0.55 µm (pointed-leading, correct) | **+0.38 µm (barbed, reversed)** | **+0.057 µm (≈stationary)** |
| Mean vecMovedX | −0.006 | +0.004 | **+0.0006** |
| Avg myosins bound | ~11.5 | ~14.7 | **~28.6** |
| Footprint duty ratio | ~0.029 | ~0.042 | **~0.071** |

**Interpretation:** making the binding search consistent with the center tether removes ~85% of the spurious drift (net dX +0.38 → +0.057 µm). The filament is now **essentially stationary** — the intended outcome of a flat, strain-free bind with no power stroke (no artificial gliding in either direction). Occupancy rises further to ~28.6 bound (duty ~0.071), confirming the bind is now genuinely strain-free. A small residual +X bias remains (~0.06 µm over 0.1 s), attributable to the remaining geometric asymmetries (lever pull on the neck-side end, `alignYVec` torque, catch-slip release sign); it can be chased down if a perfectly zero net drift is required. Note: the `gliding_assay.dat` "long-window speed" (~6.7 µm/s) is a displacement *magnitude* and is dominated by Brownian back-and-forth here — net translation is the dX above, not that figure.

---

# Runs 6 & 7 — perpendicular neck rest + 70° power stroke (on top of center bind)

**Date:** 2026-06-30
**Hypothesis (user):** Run 5 doesn't glide because, with the head pinned parallel to the filament (180°), the neck–motor joint rests collinear with the head (≈0°) so the neck also lies along the filament — its power-stroke swing is transverse and does no axial work. Fix: rotate the neck–motor rest by +90° so the neck's pre-stroke rest is **perpendicular** to the filament, and the 70° stroke sweeps along the axis (reproducing the geometry the head had when held at 90°).

**Code change (flag, composes on top of `myoCenterParallelBind`):** `Env.myoNeckPerpStroke`. In `begin()`: `uncockedLever_MotorAngle = 90°` (neck ⊥ filament, pre-stroke / ADP-Pi rest); `cockedLever_MotorAngle = 90 ± 70°` (post-stroke / ADP). Run 6 used **160°** (increasing sense); Run 7 used **20°** (decreasing sense, flipped).
**Param file:** `ParameterFiles/glidingAssay1000_val_centerbind_neckperp` (`myoCenterParallelBind` + `myoNeckPerpStroke`). Frames: `~/Code/threejs_output/gliding1000_centerbind_neckperp/` (Run 6) and `…_neckperp_flip/` (Run 7).

## Result — net glide direction is the headline

| Run | Neck stroke | Net dX / 0.1 s | Glide direction | Avg bound | Duty |
|---|---|---|---|---|---|
| Baseline (Run 1) | — | **−0.55 µm** | pointed-leading (**correct**) | ~11.5 | ~0.029 |
| Run 5 (center bind, no neck stroke) | none (neck ∥ fil) | +0.057 µm | barbed (≈stationary) | ~28.6 | ~0.071 |
| Run 6 (neck ⊥, 90°→160°) | increasing | **+0.100 µm** | barbed (wrong) | ~26.9 | ~0.067 |
| Run 7 (neck ⊥, 90°→20°) | decreasing (flipped) | **+0.040 µm** | barbed (wrong) | ~28.2 | ~0.075 |

**Interpretation:** the perpendicular-neck stroke **does** couple to axial motion — relative to Run 5 (+0.057), the 90→160 sense *adds* barbed-ward drift (+0.100) and the flipped 90→20 sense *reduces* it (+0.040). So the user's geometric reasoning is borne out: a neck rest ⊥ to the filament makes the stroke do axial work. **However, neither stroke sense reverses the sign** — all three center-bind variants drift slightly barbed-ward (+X), the opposite of baseline's pointed-leading glide, and all are far weaker than baseline (|net dX| ≤ 0.1 µm vs 0.55 µm). 

The sign of the net drift is therefore **not** set by the neck-stroke direction; it is set by a persistent barbed-ward bias that is present even with no neck stroke (Run 5). Likely origin: with the head rigidly pinned at 180° by the (strong) `alignUVecTorque`, the neck-stroke torque is largely absorbed by the head pin rather than translating the head center along the filament, and the residual axial rectification comes from the head bind/release asymmetry (catch-slip sign on `forceDotFil`) plus the `alignYVec` torque — not from the lever stroke. **Open:** reproducing correct pointed-leading gliding from a flat-bound head likely needs the direction-setting mechanism (bind-site selection vs. release kinetics, or relaxing the head pin so the stroke can drive the center axially), not just the neck-stroke geometry/sense.

---

# Run 8 — perp-neck 90°→160° with relaxed PAIRS lever-motor torque (J1 = 0.01)

**Date:** 2026-06-30
**Change:** same config as Run 6 (center bind + perp neck, stroke 90°→160°) but `myoJ1FracMoveTorq` (PAIRS torque coeff for the myosin lever-motor joint) lowered **0.4 → 0.01** via param file. Note this one coefficient drives *both* the neck-motor stroke torque (`applyLeverMotorJointTorque`) *and* the head→filament alignment pin (`MyoFilLink.alignUVecTorque`/`alignYVecTorque`), so it relaxes both. Test of the hypothesis that an over-stiff head pin absorbs the stroke.
**Param file:** `ParameterFiles/glidingAssay1000_val_centerbind_neckperp_relaxJ1`. Frames: `~/Code/threejs_output/gliding1000_neckperp_relaxJ1/`.

## Result

| Metric (steady state, t > 0.02 s) | Run 6 (J1 = 0.4) | Run 8 (J1 = 0.01) |
|---|---|---|
| Net dX / 0.1 s | +0.100 µm (barbed) | **+0.076 µm (barbed)** |
| Avg myosins bound | ~26.9 | **~5.85** |
| Footprint duty ratio | ~0.067 | **~0.014** |

**Interpretation:** relaxing the PAIRS lever-motor torque **does not flip the glide direction** — still barbed-leading. It collapses occupancy ~4.6× (26.9 → 5.9 bound, duty 0.067 → 0.014): with the head-alignment pin weak the heads wobble off the 180° anti-parallel pose, so fewer stay bound; net drift magnitude is barely changed (+0.100 → +0.076 µm). 

**Conclusion of the sweep (Runs 5–8):** the barbed-ward bias is robust to *both* neck-stroke sense (90→160 vs 90→20) *and* head-pin stiffness (J1 = 0.4 vs 0.01). The glide direction is therefore not set by the lever stroke or the head pin — it is set upstream, by the bind-site selection geometry (which heads the 30° anti-parallel gate admits, and where their center projects onto the filament) and/or the catch-slip release sign on `forceDotFil`. That is the place to look next for correct pointed-leading motility.
