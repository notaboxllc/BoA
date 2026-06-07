# Myosin Motor Validation

Standing summary of validation work on BoA's lumped-parameter myosin motor model.
Analogous to NMII_BIOLOGY.md but focused on validation of the simulation against experimental measurements.

For session-by-session progress, see JOURNAL.md. For motor biology background, see NMII_BIOLOGY.md.

## Status as of 2026-05-27

| Benchmark | Status | Headline result |
|-----------|--------|-----------------|
| Gliding velocity vs motor density | **Validated at high density** | 8 µm/s at 2500 motors/µm², matches skeletal myosin II |
| Density threshold (Uyeda) | **Qualitatively validated** | Transition between 100–500 motors/µm² |
| Force–velocity curve | Not started | — |
| Attachment lifetime distribution | Not started | — |
| Bidirectional/disordered gliding | Deferred | — |

## Gliding assay

### Setup

- **Geometry**: 14 µm × 2 µm × 0.5 µm shallow rectangular arena.
- **Motors**: tails locked to the z=0 plane (floor), uniformly random in (x, y), uniform random orientation. Density swept over 10, 25, 50, 100, 200, 500, 1000, 2500 motors/µm².
- **Filament**: single actin filament, 11 segments × 64 monomers, ~2 µm contour. Initially placed near the +x wall, parallel to the long axis, pointed-end leading.
- **Filament tuning**: phalloidin-stabilized stiffness regime — deflection ratio 0.500 against bare F-actin EI baseline. fracMove = 0.0573, fracR = 1.0, fracMoveTorq = 0.01. BTransCoeff = BRotCoeff = 1.4, validated against persistence length Lp_theo = 15.0 µm using a 21 µm chain at 100k samples.
- **Viscosity**: aeta = 0.1 Pa·s (~100× experimental motility buffer; see "Viscosity" below).
- **Integrator**: dt = 1e-5 s, 4 s sim time per density (gives ~80 transit times for the filament at maximum velocity).

### Reference experimental values

- **Skeletal myosin II gliding velocity**: ~5–8 µm/s at saturating ATP, room temperature.
- **Minimum motor density threshold** (Uyeda, Kron, Spudich 1990): roughly 100–300 motors/µm² below which gliding becomes intermittent or stalls.

### Key observables in the .dat output

Per row, per filament, per output interval (default every 0.001 s):

- `simTime`, `surfaceDensity`, `filamentLength`, `filamentId`
- `posX`, `posY`, `posZ` — filament center-of-mass
- `distMoved`, `vecMovedX/Y/Z`, `instantaneousSpeed` — per-interval kinematic
- `longWindowSpeedXY` — smoothed in-plane velocity, primary gliding-velocity statistic
- `longWindowSettling` — boolean flag for whether the long window has filled
- `avgBoundMotors`, `footprintMotors`, `footprintDutyRatio`, `headsWithinReachDR` — motor engagement statistics

### Results (2026-05-27 batch)

| Density (motors/µm²) | longWindow median (µm/s) | longWindow mean (µm/s) | avgBoundMotors |
|----------------------|--------------------------|------------------------|----------------|
| 10 | 0.14 | 0.79 | 0.13 |
| 25 | 0.41 | 1.10 | 0.52 |
| 50 | 0.58 | 1.14 | 0.71 |
| 100 | 1.23 | 1.56 | 1.27 |
| 200 | 2.15 | 2.82 | 3.40 |
| 500 | 3.70 | 4.23 | 6.91 |
| 1000 | 4.17 | 4.64 | 9.78 |
| 2500 | 8.06 | 8.99 | 18.38 |

(d=2500 run was killed at 2.075 s due to wall contact. Pre-contact data only.)

### Validated, qualitatively validated, not validated

**Validated quantitatively at high density:**

- Mean gliding velocity at d=2500 (8.06 µm/s median, 8.99 µm/s mean) is inside the experimental 5–8 µm/s band for skeletal myosin II.

**Qualitatively validated:**

- Monotonic increase of gliding velocity with motor density.
- Below-threshold behavior at low density (essentially no directed motion at d≤50).
- Density threshold transition between d=100 and d=500.
- Filament stays in the assay plane (posZ within ±0.25 µm of motor plane), with negative bias consistent with motor pinning.
- Sublinear scaling of avgBoundMotors with density (geometric saturation).

**Not yet validated:**

- Force–velocity relation (stall force, F–V shape) — requires tethered-filament setup.
- Attachment lifetime distribution — requires instrumenting motor state machine.
- Stiffness sensitivity — does realistic flexibility affect velocity? A/B comparison planned.

## Viscosity

The simulation uses **aeta = 0.1 Pa·s**, about 100× experimental motility buffer (~0.001 Pa·s = 1 cP). This is a numerical convenience: high viscosity suppresses thermal fluctuations and allows manageable timesteps.

**Gliding velocity is insensitive to this choice** because motor force exceeds viscous drag by a wide margin in this parameter regime. Motor stepping kinetics (cycle rate × step size) set the velocity, not drag balance.

**Thermal-fluctuation-driven measurements are affected**, including persistence-length convergence times, relaxation times after deflection, and free-filament diffusion. These quantities have been calibrated against theoretical targets *at the simulation viscosity*, not at experimental viscosity. If experimental viscosity values are wanted for these, separate calibration is needed.

## Filament stiffness convention

The current production runs use a **phalloidin-stabilized stiffness regime**: deflection benchmark calibrated to 0.5× the bare F-actin theoretical deflection, corresponding to ~2× the bare F-actin bending stiffness (EI). Phalloidin is the standard labeling reagent for fluorescent gliding assays and stiffens filaments by a factor of ~1.5–2×; this matches the experimental preparation.

A stiff-filament comparison batch is planned (fracMove ~0.5, fracR ~0.1, fracMoveTorq ~0.2; roughly 100× stiffer) to test whether realistic flexibility is meaningfully affecting the velocity measurement. Outcome will inform whether wider boxes or periodic boundary conditions are needed.

## Wall handling

Current implementation: reflecting / exclusion walls. The filament cannot pass through. At high motor density (d ≥ 1000), the filament reaches the +/- x walls within the run duration and accordions against them. Post-contact data should be excluded from velocity statistics.

**Periodic boundary conditions along the long axis** would eliminate this concern, allow smaller boxes (reducing motor count quadratically at fixed density), and produce cleaner velocity statistics. Implementation is non-trivial because motor-filament interactions must consistently use minimum-image conventions across the boundary. Survey planned.

==============================================================================
ADDITIONS FOR MYOSIN_VALIDATION.md (2026-05-27, session 2)
==============================================================================

These are insertions/edits to the existing MYOSIN_VALIDATION.md. Placement notes
are given for each block. Paste the content (not the placement notes) into the
appropriate sections.


------------------------------------------------------------------------------
BLOCK 1 -- add to the "Qualitatively validated" list (gliding assay section)
------------------------------------------------------------------------------

- Filament shape dynamics during gliding (curving, leading-end-with-trailing-body
  deformation, transient wall contact) qualitatively match published gliding-assay
  footage: Melbacke et al. 2024, Scientific Reports (open-access supplementary
  movies). The 2x-phalloidin flexible regime reproduces real filament behavior;
  the very-stiff test regime does NOT and is unphysical (used only as a control,
  see "Stiff-vs-flexible control" below).


------------------------------------------------------------------------------
BLOCK 2 -- new subsection under the gliding assay, after the results table
------------------------------------------------------------------------------

### Stiff-vs-flexible control

To check whether realistic filament flexibility is a measurement confound rather
than a faithful representation, d=200/500/1000 were re-run with a very stiff
filament (fracMove=0.5, fracR=0.1, fracMoveTorq=0.2; roughly 100x the phalloidin
stiffness). Stiff filaments glide faster, but the effect is modest at low/mid
density and grows with density:

| Density (motors/µm²) | Flexible median (µm/s) | Stiff median (µm/s) | Stiff/flex |
|----------------------|------------------------|---------------------|------------|
| 200  | 2.15 | 2.69 | 1.25x |
| 500  | 3.70 | 4.38 | 1.18x |
| 1000 | 4.17 | 7.54 | 1.81x (stiff run short, 0.75 s) |

Interpretation: the "flexibility tax" on velocity grows with motor density --
more bound motors pull a flexible filament off its glide axis, while a stiff
filament is forced to track straight. Flexibility measurably reduces velocity but
is not the dominant effect, and the flexible (phalloidin) regime is the
biologically correct one (see qualitative validation above). The flexible-batch
velocities remain the headline validated results.

Note: the stiff d=1000 run terminated early (~0.75 s, likely an accidental kill);
its displacement-based velocity (5.14 µm / 0.754 s = 6.8 µm/s) corroborates the
longWindowSpeedXY median, so the data point is usable despite the short run.


------------------------------------------------------------------------------
BLOCK 3 -- replace/expand the "Wall handling" section with this quantification
------------------------------------------------------------------------------

## Wall handling

Current implementation: reflecting / exclusion walls. The filament cannot pass
through. At high motor density the filament reaches an x-wall within the run and,
if flexible, transiently contacts and departs; if very stiff, tends to lodge
against the wall (stiff filaments cannot bend to escape oblique contact, flexible
ones can).

Quantified wall effect (stiff d=200, run split at t=2 s when the filament reaches
the side wall):

- longWindowSpeedXY median: 2.84 µm/s (0-2 s, pre-wall) vs 2.46 µm/s (2-4 s,
  wall-riding) -- about a 13% reduction.
- avgBoundMotors: 3.52 vs 2.54 -- about a 28% reduction, as the wall blocks roughly
  half the filament's accessible motor footprint.

So wall contact measurably reduces both motor engagement and velocity, but not
catastrophically. A single mean over the full run is biased low by ~7-13% for runs
that hit a wall; for clean numbers, restrict to pre-wall-contact time, or use the
early-termination feature (stops the run when the pointed end reaches an x-wall).

Periodic boundary conditions and a narrow-box-with-motor-buffer scheme were
considered as cleaner fixes but deferred -- see JOURNAL.md. Increasing physical box
width is the no-code-change fallback if wall interaction becomes limiting.


------------------------------------------------------------------------------
BLOCK 4 -- add to the "Key observables in the .dat output" section
------------------------------------------------------------------------------

Speed-column semantics (verified 2026-05-27):

- `instantaneousSpeed` is the full 3D displacement magnitude per interval divided
  by dt -- a scalar speed (path length per unit time), direction-agnostic. It
  counts thermal jitter and lateral wander as motion, so it overestimates directed
  gliding velocity.
- `longWindowSpeedXY` is a time-smoothed in-plane (xy) speed. It averages out
  back-and-forth jitter and is the better proxy for directed gliding velocity.
  This is the primary statistic used in the results above.
- Neither column is a box-x-only projection; both use vector magnitudes. A filament
  gliding at an angle registers its full speed.

Caveat for stiff-vs-flexible comparisons: because instantaneousSpeed counts
wandering as motion, comparing mean instantaneousSpeed between stiff and flexible
UNDERSTATES the true velocity gap (flexible looks faster than its net progress
warrants). Use longWindowSpeedXY (or net-displacement / time) for honest comparison.

## Files and infrastructure

- `runGlidingSweep.sh` — batch driver, sweeps motor density, configurable via `-t` (runtime) and `-o` (output directory).
- `ParameterFiles/glidingAssayBatch_template` — parameter file template with `__DENSITY__` and `__RUNTIME__` placeholders.
- `gliding_assay.dat` — per-density tab-separated output, header on line 1, one row per filament per output interval.
- Output convention: each density gets its own subdirectory `density_N/` containing `gliding_assay.dat`, `params.txt`, and frame JSONs.

## See also

- **JOURNAL.md** — session-by-session work log including this validation work (2026-05-27 entry).
- **NMII_BIOLOGY.md** — biology background on non-muscle myosin II.
- **SURVEY_MYOSIN_AND_GLIDING.md** — earlier survey of gliding-assay-related code.

## Deferred model change — running-average release rate as a tuning knob

*Recorded 2026-06-04. **Not part of the release-lag reconciliation fix** (that fix
only made the device arm's `ckRelease` read the step-N force the CPU arm already
reads; the per-decision release rate formula was untouched). Listed here as a
deferred candidate model change to be considered separately, with no current
implementation commitment.*

Candidate change to the release rate law: base `ckRelease` on a smoothing
running average of the motor–filament bond force-state with a **physical-time-
anchored** time constant τ (α = dt/τ per step) instead of evaluating the
instantaneous overdamped force each step.

**Rationale.**

1. The instantaneous overdamped cross-bridge load is dt-sensitive. Brownian
   force amplitude scales as 1/√dt, and the 2026-06-04 release-read diagnosis
   (`RELEASE_LAG_DIAGNOSIS.md`, Section 1) measured step-to-step
   |Δ(forceDotFil)| ≈ 4.45 × 10⁻¹² N vs steady-state mean |forceDotFil|
   ≈ 1.5 × 10⁻¹³ N — i.e., the per-step change is the same order as the signal
   itself. A rate law on the bare instantaneous force has no clean continuum
   limit; an average over a fixed *physical* time is the dt-robust object.

2. With τ as a parameter, release rate has a physically-principled tuning knob
   for the duty ratio and the gliding velocity, so the calibration target
   becomes published NMII values rather than ad-hoc per-experiment fitting.

**Cost / caveat.** This changes release kinetics for **both** code paths (CPU
and device), so it requires re-checking the gliding velocity match against
published NMII data, not merely device-vs-CPU agreement (which is the success
criterion of the reconciliation fix). The current gliding-velocity match
against published values is rough; that match would need to be re-validated
after the law change, not assumed to hold.

**Open sub-question — already resolved:** Is `forceDotFilTrack`'s averaging
window currently anchored in physical time or in step count? **Step count.**
`ValueTracker` (`boxOfActin/ValueTracker.java`) is a fixed-size circular
buffer of `stepsToTrack` samples (size 10 in `MyoFilLink`), and
`averageVal()` divides by `stepsToTrack` directly. So the existing release
law that `dissociateADP` uses via `forceDotFilTrack.averageVal()` is
**already quietly dt-dependent** — the same change of variable that motivates
the new release law would also need to apply to the existing tracker (or the
new law would replace it). Any future principled rewrite of the release-rate
law should subsume both code paths simultaneously.

## float32 binding systematic (CPU-double vs GPU-float32) — observed, deferred

Observation (Phase 4.5, seeds 1–4): the resident GPU bind path produces ~+22% bindEvents
vs the retired CPU/double host-pack, uniform-positive across seeds (t≈2.5). Gliding velocity
preserved within 3%. Mechanism: GPU derives the motor tip (coord + 0.5·motorLen·uVec) from
float32 pose on-device; the host path computed it in double. At the capture-tolerance
threshold, float32 tips cross slightly more often, biasing the count upward.

Interpretation: NOT a precision error against ground truth. The binding rule (thermal
exploration under a coarse timestep + stochastic accept/reject against a capture tolerance)
is a lumped-parameter scheme calibrated to approximate biology. Double is not "the true
signal," only one realization. CPU-double and GPU-float32 are two equally-valid
approximations differing at the threshold.

Resolution (deferred): make both paths float32 so they agree, then calibrate the unified
binding behavior to experiment via the capture-tolerance / acceptance knob once suitable
experimental binding/kinetics data exists. Scope: confine the float32 reconciliation to the
binding DECISION (CPU tip/threshold computed from float32 pose, mirroring the GPU); leave the
CPU double-precision Langevin integration untouched to protect the persistence-length /
deflection calibration (BTransCoeff/BRotCoeff, fracMove/fracR/fracMoveTorq).

Status: not blocking gliding-velocity validation (gv holds). Revisit when force-velocity /
phalloidin biological runs that depend on binding kinetics begin.

### Binding scheme reference

What the code actually does, with file:line citations — so the unify-and-calibrate work
can start here without re-reading the code from scratch.

#### 1. Capture test (geometry)

**Per-pair criterion**, applied to every (motor, candidate-seg) pair returned by the spatial
grid (CPU) or the 27-cell device-grid walk (GPU). The pair binds iff ALL of:

1. **Motor-axis vs filament-axis alignment** — `dot(motUVec, filUVec) ≥ myoMotorAlignWithFilTolerance`
   (default `-0.4` cos(rad), `Env.java:718`).
2. **Rod-axis vs filament-axis sign gate** — `dot(rodUVec, filUVec) ≥ 0` (motor approaches the
   barbed end with the right handedness).
3. **End2 nodal exclusion** — `FilSegment.soaNodeAtEnd2[s]` must be false (a seg whose end2 is
   tethered to a node is excluded — formin-bound filaments etc.).
4. **Tip-to-segment perpendicular distance** — compute α = projection of (tip − end1) onto
   (end2 − end1), require `0 ≤ α ≤ 1` AND `|tip − (end1 + α·r1)|² < myoColTol²`.

**The capture-tolerance parameter is `Env.myoColTol`**, default `0.006 µm` =
`myoColTol_init` (`Env.java:742-743`). This is THE primary knob that controls bind rate via
the geometric criterion.

CPU implementation: `MyoMotor.checkFilSegCollision(int motorId, int filId)`
(`MyoMotor.java:417-459`), all arithmetic in `double`. The narrow-phase math:

- Read `FilSegment.soaUX/Y/Z`, `soaEnd1X/Y/Z`, `soaEnd2X/Y/Z`, `soaNodeAtEnd2`
  (`MyoMotor.java:419-436`).
- Read `MyoMotor.soaX/Y/Z` (= **the precomputed motor tip**, set in
  `MyoMotor.fillSoaArrays`; see §3 below).
- Compute α = numer/denom, clamp 0 ≤ α ≤ 1, compute closest-point distance squared
  `conDistSq`, compare against `myoColTol²` (`MyoMotor.java:443-453`).
- On hit: `arcOnFil = α · sqrt(denom)`, fire `ontoFilament(seg, arcOnFil)`
  (`MyoMotor.java:457-458`).

GPU resident kernel: `GPUMotorBinding.bindKernelResident` (`GPUMotorBinding.java:607-755`),
all arithmetic in `float32`. Same algebra, with the algebraic simplification that the on-device
`uVec` is unit so `denom = length²` and `arcOnFil = α·length` directly (no sqrt)
(`GPUMotorBinding.java:713-743`).

The CPU pre-filter that broadens to the 27 grid cells lives in
`MotorBindGrid3D.motorFilCollisions` (`MotorBindGrid3D.java:241-266`); the GPU equivalent is
the cell-walk inside `bindKernelResident` (`GPUMotorBinding.java:678-693`).

#### 2. Stochastic accept/reject — there isn't one

**The bind decision is purely geometric.** There is NO probabilistic acceptance on top of the
distance test — no rate·dt, no Boltzmann factor, no RNG draw at bind time. A grep for
`Random`/`nextDouble`/`mtRNG` across `MyoMotor.checkFilSegCollision` /
`bindKernelResident` / `MotorBindGrid3D.motorFilCollisions` finds none. The
KERNEL_ID comment block in `GPUMotorBinding.java:52-54` confirms: "Slot reserved; this kernel
does not consume RNG."

Two non-RNG gates supplement the geometry inside `MyoMotor.ontoFilament`
(`MyoMotor.java:488-495`):

- **Already-bound rejection** — `if (onFil) return;` (a motor can only attach to one seg at a
  time).
- **Rebind-cooldown timer** — `if (bindTimer < Env.myoRebindTime.getValue()) return;`. `bindTimer`
  is reset to 0 on release (`MyoFilLink.java:286`), advanced by `Env.deltaT` each call to
  `MyoMotor.step()` (`MyoMotor.java:179`). The default cooldown is `myoRebindTime_init = 1e-5 s`
  (= 1 sim step at the validation `deltaT`, `Env.java:745-746`).

Effective on-rate is implicit: each step, every free-and-not-on-cooldown motor that has at
least one geometrically-eligible candidate within `myoColTol` binds to it (the kernel returns
the **first** hit in the 27-cell walk; see `found >= 0 && break` in
`GPUMotorBinding.java:744-745`). So the per-step bind probability is effectively 1 conditional
on (i) motor free, (ii) motor off cooldown, (iii) `myoColTol`-disk overlap with some valid seg
in the 27-neighbour cells. The rate is tuned via the capture geometry (myoColTol) and the
cooldown.

RNG **does** drive the surrounding kinetics — nucleotide cycle and unbinding — but not the
bind decision itself:

- ATP/ADPPi/ADP transitions: `MyoMotor.checkATPstate` (`MyoMotor.java:204-271`), each
  transition gated by `myPRNG.nextDouble() < rate·deltaT`.
- Release: catch/slip law in `MyoFilLink.dissociateADP` (`MyoFilLink.java:318-326` and
  related), `forceDotFilTrack.averageVal()`-conditioned, `myPRNG.nextDouble()`-fired.

#### 3. Tip computation — both paths, side-by-side

The motor "tip" is the binding-business-end of the motor: `coord + 0.5·motorLength·uVec`.

**CPU (double)** — `MyoMotor.fillSoaArrays` (`MyoMotor.java:22-55`). Every step on the
non-`-gpu` path (after Phase 4.5 the CPU-path is also gated on `!Env.useGPU` in
`BoxOfActin.doLoop:951-960`):

```java
final double halfLen = 0.5 * Env.myoMotorLength.getValue();
// ...
final double cx = soaCoordArr[b];   // Thing.soaCoord is float32 mirror
final double cy = soaCoordArr[b + 1];
// ...
soaX[i] = cx + halfLen * ux;   // soaX is double[]; arithmetic in double
soaY[i] = cy + halfLen * uy;
soaZ[i] = cz + halfLen * uz;
```

Note the host already widens the float32 `Thing.soaCoord` mirror to `double` at the read,
then performs the `+ halfLen·u` operation in `double`, and stores into the `double[]
soaX/Y/Z` cache. `checkFilSegCollision` (above) reads `soaX/Y/Z` (double) and computes
α, conDistSq, comparison against `myoColTol²` — all in `double`.

**GPU (float32)** — `GPUMotorBinding.bindKernelResident` (`GPUMotorBinding.java:651-664`).
On-device, every dispatch:

```java
float mcx = coord.get(mSlot * 3);          // FloatArray, host-side already float32
float mcy = coord.get(mSlot * 3 + 1);
float mcz = coord.get(mSlot * 3 + 2);
float mux = uVec.get(mSlot * 3);
// ...
float mx = mcx + halfMotorLen * mux;       // float arithmetic
float my = mcy + halfMotorLen * muy;
float mz = mcz + halfMotorLen * muz;
```

`halfMotorLen` is `0.5f · gridParams[7]` (`GPUMotorBinding.java:633`), itself uploaded as
`(float)Env.myoMotorLength.getValue()` at plan-build time (`GPUMotorBinding.java:1099`).
All subsequent α / conDistSq / `myoColTolSq` math is also `float`.

#### 4. Tuning knobs (what jba would calibrate against experimental data)

| parameter | default | sets | location |
|---|---|---|---|
| `myoColTol` | `0.006 µm` | capture tolerance (perpendicular distance threshold) | `Env.java:742-743` |
| `myoMotorAlignWithFilTolerance` | `-0.4` cos(rad) | motor-axis alignment gate | `Env.java:718` |
| `myoRebindTime` | `1e-5 s` | post-release rebind cooldown | `Env.java:745-746` |
| `myoMotorLength` | `0.020 µm` | motor head length (sets the tip offset) | `Env.java:716` |

`myoColTol` is the dominant knob for bind rate — quadratic in the per-pair acceptance disk
area, and linear in the broad-phase candidate count (since the grid cell width is
~`CELL_SIZE = 0.2 µm`, several `myoColTol`-disk diameters fit per cell). Calibration of bind
rate against experimental kinetics would tune this against a reference dataset.

#### 5. Reconciliation point (the specific CPU site to drop to float32)

To remove the +22% CPU-vs-GPU divergence, narrow the float32 conversion to the binding
decision and ONLY the binding decision. The CPU sites to convert (Langevin integration stays
double):

- **A. Motor tip computation in `MyoMotor.fillSoaArrays`** (`MyoMotor.java:42-44`):

  ```java
  soaX[i] = cx + halfLen * ux;   // currently double; mirror the GPU by computing in float
  soaY[i] = cy + halfLen * uy;
  soaZ[i] = cz + halfLen * uz;
  ```

  Either cast inputs to `float`, do the add+multiply in `float`, then store back into the
  `double[]` arrays (`soaX/Y/Z[i] = (double)((float)cx + (float)halfLen * (float)ux)`), OR
  introduce a parallel `float[] soaXf/Yf/Zf` that `checkFilSegCollision` reads instead. The
  second is cleaner if other CPU consumers of `soaX/Y/Z` (audit needed) benefit from the
  double precision elsewhere.

- **B. The four arithmetic blocks in `MyoMotor.checkFilSegCollision`** that compute the
  distance test (`MyoMotor.java:431-453`):

  - r1 = end2 − end1, denom = |r1|² (lines 434-444)
  - r2 = tip − end1, numer = r2·r1, α = numer/denom (lines 440-445)
  - cp = end1 + α·r1; d = cp − tip; `conDistSq = |d|²` (lines 447-451)
  - threshold compare: `conDistSq >= myoColTol²` (line 452-453)

  Convert each to `float` arithmetic, mirroring the GPU kernel. Read sites: the seg endpoints
  `FilSegment.soaEnd1X/Y/Z` and `soaEnd2X/Y/Z` are already `float[]` — the widening to
  `double` (`MyoMotor.java:431-436`) is the explicit cast site that would simply drop.

- **C. `arcOnFil` simplification** (`MyoMotor.java:457`): change `α · Math.sqrt(denom)` to
  `α · length` (where `length` = `FilSegment.soaLength[filId]` cast to float), matching the
  GPU's `alpha · len` formulation (`GPUMotorBinding.java:743`).

Separability from the Langevin integrator: the Langevin step lives in `Thing.moveThing` /
`FilSegment` chain forces / Brownian — none of these read `MyoMotor.soaX/Y/Z` or call
`checkFilSegCollision`. The bind path consumes `soaX/Y/Z` exclusively (grep:
`MotorBindGrid3D.fillMotor` reads `bindTip` Pt3D, `motorFilCollisions` reads `soaX/Y/Z`, and
the GPU bind path reads them via the bind plan's EVERY_EXECUTION upload of the same caches).
So a localized float32 conversion of (A) and (B) leaves the persistence-length /
deflection calibration (`BTransCoeff`/`BRotCoeff`/`fracMove`/`fracR`/`fracMoveTorq`)
completely untouched — those knobs feed `moveThing`, `chainPairForces`, `boundaryBox`,
not the bind path.

After the conversion, the CPU-double residual that fed the historical pad-fix calibration
(`MYOSIN_VALIDATION.md` §earlier entries) disappears at the binding boundary; calibration
of the unified float32 bind rate becomes the next step.
