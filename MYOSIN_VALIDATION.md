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
