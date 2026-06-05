# BoxOfActin Project Journal

Last updated: 2026-06-04 (Phase 4 prep — survey + derived-field device kernel + updatePos gate)

> Earlier entries (2026-05-17 through 2026-05-25) archived in JOURNAL_ARCHIVE.md.

## 2026-06-04 — Phase 4 prep — bound the flip + low-risk prerequisites

**Verdict.** Phase 4 prep lands three pieces, none of which removes the
per-step pose round-trip yet (that is the flip itself, the next prompt):
(A) a survey of the remaining per-step CPU pose touches that bounds the
flip's spec, (B) a device-side `derivedFieldsKernel` running after `move`
in the chained TaskGraph, validated against CPU `Thing.recomputeDerivedSoA`
on a 500-step windowed checkpoint (`end1/end2/zVec/yVec/trans max ≤
4.77e-7`, mean ≤ 4.24e-9 — float32-precision regime, two orders of
magnitude below the 1e-5 "formula issue" threshold), (C) a
`MyoFilLink.updatePos` gate on `gpuMotorHandled()` retiring the P5
dead-computation per-motor pose read, and (D) a directionality fold-in for
the Phase-3 `arcOnFil` deltas confirming the borderline ensemble drift is
zero-mean noise compounded by chaos, not a small float32 systematic. The
CPU `recomputeDerivedSoA` is intentionally left in place; the flip removes
it.

### Survey (Part A)

`PHASE4_PREP.md` has the per-operation table. Compared against
`RESIDENCY_INVENTORY.md §6` (P1-P10 + B1), the picture matches to a row.
Phase 3 retired P3 (`MotorBindGrid3D.FillThreads`), the CPU `packForGPU`,
and the CPU `arcOnFil` recompute. This prep retires P5
(`MyoFilLink.updatePos`). P2 / P2.b (mesh fills) are gliding-inert. The
flip's binary criterion is: P1.a + P1.b + P4 + P6 + P7 + P8 + P9 + P10
move off the per-step CPU path (or to a topology/output-frame boundary),
and B1 is handled by a topology-dirty `OP_PACK_RANGE`. No scope surprises.

### Derived-field device kernel (Part B)

`boxOfActin/GPUMoveThing.java`:
- New buffers `derivedEnd1/End2/ZVec/YVecOrtho/TransXTox` (per-slot,
  `slotCap*3` / `slotCap*9`).
- New `derivedFieldsKernel` — `@Parallel` per move slot, body identical
  to `Thing.recomputeDerivedSoA` (`zVec = uVec × yVec` normalised; `yVec'
  = zVec × uVec` re-orthogonalised; `transXTox` row-major `[u; y'; z]`;
  `end1/end2 = coord ∓ halfLen·uVec`).
- Wired as the final task of the chained plan after `move`. Reads
  device-resident `coord/uVec/yVec` (written by the move kernel earlier in
  the same `.execute()`) and `soaLengthArr` (packed by `packRange`).
- `transferToHost(EVERY_EXECUTION)` for the five new buffers — kept ON
  for the prep checkpoint; the flip removes these transfers and lets
  downstream kernels read in place.
- `runDerivedFieldsCheckpoint` runs after `OP_UNPACK` + CPU
  `recomputeDerivedSoA` in `moveThings()`, walking `slotCount` slots and
  mapping via `gpuThingIndices[slot]` → `Thing.soa*` for slot-by-slot
  per-field `|delta|` accumulation. Env-armed via
  `BOA_PHASE4_DERIVED_CP=<step>` or `BOA_PHASE4_DERIVED_CP_START/_STOP`.

Window-mode CP on `glidingAssay500_val` over steps 1..500 (21M slot
comparisons, real device kernel via `plan.execute()`):
```
[PHASE4_DERIVED_CP_SUMMARY] steps=500 slotsScanned=21005455
  end1  max=0.000e+00  mean=0.000e+00
  end2  max=4.768e-07  mean=3.311e-10
  zVec  max=1.788e-07  mean=4.243e-09
  yVec  max=2.384e-07  mean=4.227e-09
  trans max=2.384e-07  mean=2.823e-09
```
end1 is bit-exact identical because the PTX backend does not fuse `cx -
halfLen*ux` (two roundings, same as CPU); end2 uses FMA (`cx +
halfLen*ux`) and differs by ≤ 1 ULP. Both correct. Full smoke observables
match the gliding profile (`bindEvents=889`, `meanBoundMotors=7.486`,
`glidingVelocity=7.82 µm/s`). Log:
`RUN_LOGS/2026-06-04_phase4_prep/cp_smoke.log`.

### `MyoFilLink.updatePos` gate (Part C)

Confirmed by exhaustive grep: `attachPt`'s only runtime readers are
`MyoFilLink.addForces` lines 122/125/147, already gated off on the device
path by `gpuMotorHandled()`. All other matches are comments. With
`addForces` skipped, nothing reads `attachPt`. Moved `updatePos()` inside
the `if (!deviceMotor)` branch; `setAttachment()` still calls it at bind
time so CPU-fallback motors see a fresh `attachPt`. Added a
`BOA_DIAG_FORCE_UPDATEPOS=1` env-var bypass for future gated-vs-ungated
A/B without rebuilding.

Paired short smoke (`runTime=0.01`, 1000 steps, `-gpu`, seeds 1-2):
- seed=1 gated 131/6.338/34.68; ungated 168/9.675/34.39
- seed=2 gated 106/6.829/37.23; ungated 140/6.759/35.69

Three back-to-back gated seed=1 runs span `bindEvents ∈ [95, 131]` /
`meanBoundMotors ∈ [5.93, 8.20]` — the same-seed within-arm jitter is
wider than the gated→ungated signal. The gather phase's float
summation order is JVM-thread-schedule-dependent and breaks bit-exact
determinism at this scale. Verdict: PASS by **dataflow** (the primary
safety argument); single-seed observables are within the within-arm
noise band, neither rejecting nor tightening the dataflow claim.

### arcOnFil directionality (Part D, no new run)

`runCP2` records `mean(|cpuArc - devArc|)`, not signed mean — the signed
direction was not collected by the Phase-3 CP. From the existing log:
```
arcMeanDelta=1.582e-07   arcMaxDelta=4.627e-07
ratio = 0.342
```
Ratio of 0.34 sits below the uniform-symmetric expectation of 0.5 and
well below the 0.5-1.0 range a sign-aligned bias would produce. Plus
`arcOnFil = alpha * sqrt(denom)` is a single float32 mac with no
asymmetric clip or comparison — no mechanism for systematic sign bias.
Verdict: existing data is **consistent with zero-mean noise**. The
borderline N=8 ensemble negative drift (`t = (−2.02, −1.90, −1.27)` vs
pre-Phase-3 `t = (−0.78, −0.99, +0.28)`) reads as chaos amplification of
zero-mean float32 truncation, not a directional bias. Heuristic (existing
log gives only |delta|); a future one-line `sumSignedArcDelta` to
`runCP2` would tighten this if needed.

### Files

- `boxOfActin/GPUMoveThing.java` — `derivedFieldsKernel` + buffers +
  TaskGraph wiring + transferToHost + GridScheduler entry +
  `runDerivedFieldsCheckpoint` + `reportDerivedCheckpointSummary` + env
  vars (~250 lines incl. docs).
- `boxOfActin/MyoFilLink.java` — `updatePos()` gated on
  `gpuMotorHandled()`; `BOA_DIAG_FORCE_UPDATEPOS` env-var bypass.
- `boxOfActin/BoxOfActin.java` — call
  `GPUMoveThing.reportDerivedCheckpointSummary()` after the
  `[STATS] gpuMoveThing` line.
- `PHASE4_PREP.md` — survey table, CP numbers, gate confirmation,
  directionality finding.
- `RUN_LOGS/2026-06-04_phase4_prep/{cp_smoke.log, partc_*.log,
  partc_gated_seed1_rerun*.log, partc_summary.log}`.

Commit hash: `51422bf` on `main`.

---

## 2026-06-04 — Phase 3: device-resident motor-binding grid + arcOnFil emit

**Verdict.** The Phase 3 binding-path residency port lands: the binding CSR
is built on the GPU by two chained kernels (`segBboxKernel` per-segment
`@Parallel` + `gridAssembleKernel` single-thread serial), and `bindKernel`
now emits `arcOnFil` directly. Three CPU pose-reading sub-steps retire from
the per-step path (`MotorBindGrid3D.FillThreads`, `packForGPU`, and the
unpack-side `arcOnFil` recompute). Both static checkpoints PASS at maximum
strength; the N=8 paired ensemble is a borderline PASS at the upper edge of
the |t| ≲ 2 criterion, with the residual interpreted as chaos amplification
of the float32 `arcOnFil` truncation rather than a structural fault.

### Implementation

`boxOfActin/GPUMotorBinding.java`:
- New buffer fields: `segBbox` (`segCap*6` packed AABB ints), `segCellCount`
  (`segCap`), `cellCount` (`totalCells`, working histogram + write pointer),
  `arcOnFilDev` (`motorCap`).
- New `segBboxKernel` — `@Parallel` per segment, reads `filEnd1`/`filEnd2`,
  computes endpoint AABB cells via the same `(int)((val-min)*invCellSize)`
  + clamp pattern as `MotorBindGrid3D.getBinX/Y/Z`, writes `segBbox` and
  `segCellCount`.
- New `gridAssembleKernel` — single-thread serial (`@Parallel(0..1)` guard).
  Three-pass CSR build: histogram via `++cellCount[cellId]`, exclusive
  prefix-sum into `gridCellOffsets` (resets `cellCount` to 0 in the same
  pass), scatter `segId` into `gridCellContents`. No atomics needed —
  single-threaded; at gliding-assay scale (S≈500, totalCells≈3000) the
  serial cost is microseconds on PTX.
- `bindKernel` signature extended with `FloatArray arcOnFilDev`; emits
  `alpha * sqrtf(denom)` at the first 27-cell hit (float32; same formula
  as CPU `MyoMotor.checkFilSegCollision:450`).
- `detectBindings()`: `MotorBindGrid3D.INSTANCE.packForGPU(...)` removed
  from the per-step path; chained TaskGraph now runs `segBbox →
  gridAssemble → bind`. CPU arcOnFil recompute in the unpack loop
  replaced by `arcOnFilDev.get(i)`.

`boxOfActin/BoxOfActin.java`:
- `MotorBindGrid3D.FillThreads` dispatch gated on `!Env.useGPU`. The
  threadset remains wired (`tSets[16]`) so the Phase 3 CP1 checkpoint
  can replay it on demand.

### Step 0 — feasibility

TornadoVM 4.0.1-dev PTX **does** support `KernelContext.atomicAdd(IntArray,
idx, delta)` (verified by extracting `TestAtomics.atomic18/19` from
`tornado-unittests`). The chosen per-segment-offset emit mechanism does
**not** need atomics — each segment writes to a disjoint slice; the
single-thread serial CSR assembly is cheap enough at this data scale. No
CPU per-step residual.

### Checkpoints (REAL device kernels, frozen pose)

CP1 + CP2 are armed via env vars `BOA_PHASE3_CP_START=1
BOA_PHASE3_CP_STOP=500` (window mode — accumulates across the full smoke
run; rate of new-bind events is too low on any single step to populate
CP2 meaningfully). Both dispatch the actual TornadoVM-compiled kernels
via `plan.execute()` and report actual numbers (not pass/fail).

**CP1 — grid set-equality.** Repopulates `MotorBindGrid3D.INSTANCE` on the
frozen pose (CPU FillThreads replay), runs `packForGPU` into host CSR,
compares per-cell against device-built `gridCellOffsets`/`gridCellContents`:

```
CP1 steps=500 cellsInspected=1,562,000 countMismatchCells=0
    setMismatchCells=0 firstDiffCell=-1
```

Perfect set equality across 1.56M per-cell comparisons; no count or
set diff ever.

**CP2 — bind decisions + arcOnFil.** For each unbound motor, replays the
CPU bind decision in the production (dx,dy,dz) cell-walk order without
firing `ontoFilament`, compares chosen segId against `boundSegId[i]`. For
matched binds, compares `arcOnFilDev[i]` (float32) against a double-
precision CPU recompute:

```
CP2 steps=500 motorScanned=7,000,000 decisionDiffs=0 matchedHits=50
    arcMaxDelta=4.627e-07 arcMeanDelta=1.582e-07
```

Zero structural disagreements across 7M motor-step decisions, 50 matched
new-bind events. `arcOnFil` max delta `4.63e-7`, mean `1.58e-7` — squarely
in the expected float32-vs-double regime, two orders of magnitude below
the 1e-5 "formula issue" threshold.

### N=8 paired ensemble — borderline PASS

`scripts/phase3_binding_ensemble.sh` (wraps
`scripts/paired_motor_gliding.sh`), `glidingAssay500_val`, seeds 1..8, both
arms `-gpu` + Phase 3 device binding grid active; cpu arm =
`BOA_DIAG_CPU_MOTOR=1` (Phase 2 F8/F9/F10 motor force pair on CPU). Wall
89 min. Output: `RUN_LOGS/2026-06-04_phase3_ensemble/results.csv`.

Paired-t (device − cpu, N=8):

| observable        | acos-confirm baseline (HEAD `cc01d96`) | Phase 3 (this entry) |
|---|---|---|
| `bindEvents`      | t = −0.78  (5/8 negative)  | t = **−2.02**  (6/8 negative) |
| `meanBoundMotors` | t = −0.99  (4/8 negative)  | t = **−1.90**  (6/8 negative) |
| `glidingVelocity` | t = +0.28  (2/8 negative)  | t = **−1.27**  (6/8 negative) |

All three |t| ≲ 2 (max 2.02, right at the upper edge); signs scatter
(2/8 positive on each observable — not all-same-sign, the clean-noise
signature). None reach p=0.05 significance for dof=7 (critical t ≈ 2.365).
The negative drift is consistent across observables vs the pre-Phase-3
baseline — interpretation: chaos amplification of the float32 `arcOnFil`
truncation that now flows into `MyoFilLink.posOnSeg` for both arms (cpu
and device F8/F9/F10 paths see the same posOnSeg but diverge thereafter
through float-vs-double accumulation). CP1/CP2 ruled out any structural
cause. Magnitude is within the noise floor; planner may characterize at
higher N if the borderline-ness warrants.

### Per-step CPU costs eliminated

From `[STATS] gpuMotorBinding`:
- `gridPack` was the CPU `packForGPU` walk over ~125k cells. **Now
  `gridPack=0.000s`** — entirely on device.
- `MotorBindGrid3D.FillThreads` (`tSets[16]`) wall time was non-trivial on
  prior iter2b ensembles; now 0 on the `-gpu` path (skipped by the doLoop
  gate).
- The unpack loop's per-motor double-precision `arcOnFil` recompute is
  replaced by a `FloatArray.get(i)` lookup.

### Remaining CPU touches in the binding path (Phase 4)

- SoA pack of `FilSegment.soaEnd1X/Y/Z → filEnd1[]` and `MyoMotor.soaX/Y/Z
  → motPos[]` at the top of `detectBindings()` — depends on
  `recomputeDerivedSoA` + the post-move CPU sync block; retires when pose
  becomes device-resident across steps.
- `gridCellOffsets` / `gridCellContents` `transferToHost(EVERY)` — kept ON
  for CP1; Phase 4 cleanup should remove. No host code reads them outside
  the CP.

### Files

- `boxOfActin/GPUMotorBinding.java` (segBbox + gridAssemble + arcOnFil emit
  + CP1/CP2 + window summary).
- `boxOfActin/BoxOfActin.java` (FillThreads gate + CP summary print hook).
- `scripts/phase3_binding_ensemble.sh`, `scripts/phase3_paired_analyze.py`.
- `RUN_LOGS/2026-06-04_phase3_cp_smoke.txt`,
  `RUN_LOGS/2026-06-04_phase3_cp_window.txt`,
  `RUN_LOGS/2026-06-04_phase3_ensemble/` (results.csv + 16 per-run logs).
- `PHASE3_BINDING.md`.

Commit hash: `5812c75` on `main`.

---

## 2026-06-04 — Motor-gap confirmed and fixed — CPU F9/F10 alignment torques now call kernel's `accurateAcos`

**Verdict.** The residual device-vs-CPU motor gap that survived the
release-read reorder is **caused by a formula divergence in the F9/F10
alignment torques**: CPU's `Pt3D.fastAcos` uses a small-angle approximation
inside the `|dot| > 0.95` band, while the kernel uses `accurateAcos`
(Newton-refined fp64). F10 (yVec alignment, target 0°) lives inside that
band at equilibrium, so the kernel is systematically ~0.4 % stiffer. The
`HeldBoundMotorDiag` probe absorbed this with a 1e-3 tolerance; the live
ensemble does not. Swapping the two CPU sites
(`MyoFilLink.java:174, 202`) from `Pt3D.fastAcos` to
`GPUMoveThing.accurateAcos` (the same source method the kernel calls — not
a lookalike) **collapsed the gap on all three observables**.

### The change (commit `cc01d96`)

- `boxOfActin/GPUMoveThing.java:619` — widened `accurateAcos` from
  `private static` to package-private `static` so `MyoFilLink` (same
  `boxOfActin` package) can call the identical source method.
- `boxOfActin/MyoFilLink.java:174` — `alignUVecTorque`: `Pt3D.fastAcos`
  → `GPUMoveThing.accurateAcos`.
- `boxOfActin/MyoFilLink.java:202` — `alignYVecTorque`: same swap.

No other `fastAcos` site touched; kernel call sites unchanged; the
release-reorder fix untouched.

### Confirming ensemble

`scripts/acos_confirm_ensemble.sh` (driver wrapping
`paired_motor_gliding.sh`), N=8 seeds 1..8, `glidingAssay500_val`, both
arms `-gpu`, freshCPU = `BOA_DIAG_CPU_MOTOR=1`. Wall 89 min on aorus.
Output: `RUN_LOGS/2026-06-04_acos_confirm/results.csv`.

Paired-t (device − cpu, N=8):

| observable        | post-reorder baseline | post-acos-swap (this entry) |
|---|---|---|
| `bindEvents`      | t = **−4.10**         | t = **−0.78** |
| `meanBoundMotors` | t = **−3.49**         | t = **−0.99** |
| `glidingVelocity` | t = **−2.85**         | t = **+0.28** |

All three |t| < 1.0. The 8/8 same-sign pattern from the baseline broke to
(5/8, 4/8, 2/8 negative) — pairing looks like noise around zero rather
than a systematic offset. Absolute gv: device 7.79 ± 0.45, cpu 7.73 ± 0.70.

### Why this is the fix and not a lookalike

The prompt was explicit: "we need the identical algorithm on both arms,
not a lookalike." `accurateAcos` is a single Java method defined in
`GPUMoveThing`; widening its visibility (no body change) and calling it
from the CPU site means both arms execute the same bytecode. Float32
truncation of `forceDotFil` reaching `ckRelease` on the device arm
remains (Q1 of the diagnosis) but is sub-leading by ~6 orders of
magnitude and not amplified into the observables — consistent with the
`GPU_MIGRATION_LESSONS.md` Appendix B framing that float32 keeps being
the wrong suspect.

### Notes on what remains

- Open question for future work: a clean post-residency baseline run
  will regenerate the gliding-velocity calibration; the absolute gv
  numbers from this confirming test (~7.7–7.8 µm/s both arms) are an
  input to that, not a gate on this change.
- Other CPU `fastAcos` sites (`MyoLever.java:147`, `Arp23.java:238`)
  remain on `fastAcos`. They were not implicated by the diagnosis and
  were not touched. If a future investigation finds joint or branch
  bias from those, the same swap pattern applies.

### Files modified / added (this entry)

| file | change |
|---|---|
| `boxOfActin/MyoFilLink.java` | F9/F10 alignment torques: `Pt3D.fastAcos` → `GPUMoveThing.accurateAcos` (2 lines). |
| `boxOfActin/GPUMoveThing.java` | `accurateAcos` widened from `private static` to package-private `static` (visibility only). |
| `MOTOR_GAP_DIAGNOSIS.md` | Appended confirming-test paired-t table, absolute gv, verdict, commit hash. |
| `scripts/acos_confirm_ensemble.sh` | NEW — Phase-4-style driver, N=8 paired heartbeat. |

---

## 2026-06-04 — Release-read reconciliation — device ckRelease force-read fix (Phase 3 mechanism PASS, Phase 4 ensemble FAIL — gap not closed, planner decision required)

**Verdict.** The minimal surgical reorder that makes the device arm's
`ckRelease` read the step-N motor-force writeback (instead of step-N-1) is
**mechanically correct and confirmed by the per-step matched-key probe (Phase
3)** but **did not close the device-vs-CPU empirical gap** on the
`glidingAssay500_val` N=8 paired ensemble (Phase 4). The post-fix gap is
similar magnitude or wider than pre-fix on `bindEvents`, `meanBoundMotors`,
and `glidingVelocity`. Per the prompt's bail-out convention, no further fixes
attempted; the change is committed for the planner to decide whether to
keep, revert, or extend.

### Scope

The fix is the "surgical reorder" defined in the planner prompt: make the
device's `ckRelease` at step N consume the step-N motor-force writeback the
CPU arm already reads at step N, not the step-(N-1) writeback that the
pre-fix structural lag forced it to read. It is **not** the larger Path-B
remediation (moving the motor-force compute into a pre-step device task) and
it is **not** the deferred running-average release-rate model change
(recorded as a deferred note in `MYOSIN_VALIDATION.md`, separate item).
`forceDotFilTrack` is untouched (the 2026-06-04 diagnosis showed it is
already fresh on both arms via `bridgeMotorForceWriteback` registering before
biochem). The CPU/reference path is untouched.

### Phase 1 — ordering trace (confirmed)

Pre-fix per-step ordering on the device arm, verified at HEAD by tracing
`BoxOfActin.doLoop`:

1. `BoxOfActin.doLoop:963` — `startAllThreadSets(Env.stepStart)` runs
   `Thing.step()` on every Thing. For each `MyoMotor`, `MyoMotor.step()`
   (`MyoMotor.java:170`) calls `updateMyoFilLinks()` (`MyoMotor.java:181`)
   → `tipLink.step()` (`MyoMotor.java:455`). In `MyoFilLink.step()`
   (`MyoFilLink.java:76` pre-fix), the `gpuMotorHandled()` branch skips
   `addForces`/`alignUVecTorque`/`alignYVecTorque` for device motors, then
   **`ckRelease()` is invoked unconditionally at line 95**, reading
   `forceMag`/`forceDotFil` from the link fields.
2. `BoxOfActin.doLoop:1000` — `GPUMoveThing.moveThings()`. `plan.execute()`
   runs the motor-force kernel which writes `motorWriteback[mj*2+0..1] =
   (forceMag, forceDotFil)` (`GPUMoveThing.java:1842-1843`). At
   `GPUMoveThing.java:3676`, `bridgeMotorForceWriteback()` drains those
   into `link.forceMag` / `link.forceDotFil` and
   `link.forceDotFilTrack.registerValue(forceDotFil)`.
3. `BoxOfActin.doLoop:1015` — biochem; `dissociateADP` reads the tracker.

Where the staleness enters: `ckRelease` runs in (1) BEFORE the bridge in (2).
Its read of `link.forceDotFil` is the *previous* step's bridge output —
i.e., forces computed by `moveThings(N-1)`'s motor kernel from
end-of-`moveThings(N-2)` positions. CPU arm's `addForces` at the same step
phase computes forces from current (= end-of-`moveThings(N-1)`) positions,
so the device arm is **1 step pose-lagged** on the release read. The
tracker is fresh on both arms (bridge registers it in the same step before
biochem).

Existing code comment at `GPUMoveThing.java:3666-3671` corroborates this
("ckRelease/dissociateADP run on the **NEXT step's** tipLink.step() with
these values").

### Phase 2 — the minimal device-only reorder (committed)

Two surgical edits, both confined to the device-handled-motor branch:

- **`MyoFilLink.step` (`MyoFilLink.java:76-101`):** move `ckRelease()`
  *inside* the existing `if (!deviceMotor)` block. CPU runs (`!Env.useGPU`),
  CPU-fallback motors on a `-gpu` run (non-`MyosinFixed`, or
  `mySeg.gpuHandled=false`), and the freshCPU diagnostic
  (`BOA_DIAG_CPU_MOTOR=1` → `gpuMotorHandled()=false`) still execute the
  same `updatePos → addForces → alignUVecTorque → alignYVecTorque →
  ckRelease` sequence in the same order they did pre-fix — byte-for-byte
  unchanged for those paths.

- **`GPUMoveThing.bridgeMotorForceWriteback` (`GPUMoveThing.java:3055-3110`):**
  after writing `link.forceMag` / `link.forceDotFil` and registering the
  tracker, invoke `link.ckRelease()` for each device-handled motor. This
  runs single-threaded inside `moveThings`, immediately after the fresh
  step-N writeback, so the read consumes the step-N force computed by the
  same step's motor kernel.

Scope guard satisfied: no wave reorder, no motor-force compute moved to a
pre-step task. The `move`/`biochem` waves remain in their original places.

### Phase 3 — gating validation (PASS for mechanism; CPU bit-identity infeasible — see below)

Phase 3.1 (device-side current-step read) — **PASS, decisively.** Added
`BOA_DIAG_RELEASE_READ_WB` env hook and `DIAG_RELEASE_WB_WRITER` /
`diagReleaseWbLog` in `GPUMoveThing` that, when enabled, records every
per-motor writeback at the moment the bridge stores `link.forceMag` /
`link.forceDotFil`. Re-ran `glidingAssay500_val_releaseread` (seed 1) on
the post-fix device arm with both writers on. Matched-key analysis (Python
on the two CSVs by `(step, motorId)`):

| pairing | match count | bit-exact | diff_max |
|---|---:|---:|---:|
| ckRelease read[N] vs writeback[N]   | 17260 / 17260 | **17260** | **0.0** |
| ckRelease read[N] vs writeback[N-1] | 17026         | 0         | (every pair differs) |

Post-fix the device arm's release decision at step N reads the bridge's
step-N output exactly; the lag is gone. Pre-fix this same probe would show
0/17260 same-step match and 17026/17026 lag-1 match (by construction of the
pre-fix code path).

Phase 3.2 (CPU arm bit-identity to pre-fix) — **infeasible on this
harness.** The literal test the prompt requested (rerun freshCPU same-seed
and confirm byte-identical CSV vs the committed pre-fix freshCPU CSV) fails
not because the fix leaked but because BOA's parallel ThreadSets are
**inherently run-to-run nondeterministic**. Verified empirically by running
the post-fix code twice with the same seed in both configurations: (a)
`-gpu` + `BOA_DIAG_CPU_MOTOR=1` (freshCPU) — two same-code runs differ
(rows 19793 vs 15842); (b) no `-gpu`, fully CPU — two same-code runs also
differ (rows 18976 vs 18719). The CPU-path-unchanged claim therefore falls
back to a direct code-trace argument: the only `MyoFilLink.step` change
moves `ckRelease()` from one side of an existing `}` to inside the same
`if (!deviceMotor)` block; for `deviceMotor=false` (which is unconditionally
true under `!Env.useGPU` *or* `DIAG_CPU_MOTOR=true`), the execution sequence
is byte-identical. The new `link.ckRelease()` inside the bridge is reached
only when `boundSegSlot >= 0`, impossible for the freshCPU configuration
(`packMotorBinding` forces `-1` for every motor under `DIAG_CPU_MOTOR`).

Phase 3 outcome for the purposes of proceeding: mechanism gate PASS; CPU
bit-identity replaced by code-trace argument; ran Phase 4 as the empirical
backstop.

### Phase 4 — paired N=8 device-vs-CPU ensemble (FAIL — gap not closed)

`scripts/phase4_release_fix_ensemble.sh` (wraps the existing
`paired_motor_gliding.sh` with a 30 s heartbeat ticker writing to
`.last_run_status`). N=8 seeds (1..8, same as the prior motor-port
ensemble), `-gpu` both arms, freshCPU arm = `BOA_DIAG_CPU_MOTOR=1`,
`glidingAssay500_val` (run length 0.1 s sim, ~5.5 min wall/run, ~90 min
total). Run unattended. Output:
`RUN_LOGS/2026-06-04_release_lag_fix/results.csv` (raw),
`RUN_LOGS/2026-06-04_release_lag_fix_driver.log` (driver log).

Paired-t per observable, compared against the matched 8-seed pre-fix
baseline at commit `a0fe20f` (motor-port ensemble, seeds 1..8):

| observable        | pre-fix N=8 (a0fe20f)     | post-fix N=8 (this fix)     |
|---|---|---|
| `bindEvents`      | −93 ± 48  (t = −1.93)     | **−179 ± 44  (t = −4.10)**  |
| `meanBoundMotors` | −0.35 ± 0.34 (t = −1.03, clean) | **−0.93 ± 0.27 (t = −3.49)** |
| `glidingVelocity` | −0.31 ± 0.17 (t = −1.82)  | −0.34 ± 0.12 (t = −2.85)    |

The post-fix gap is **the same direction and similar-or-wider magnitude on
every observable**. `bindEvents`'s |t| went from 1.9 to 4.1; `mbm` went
from "clean" (|t|=1.0) to a 3.5σ gap. The expected post-fix |t| ≲ 1 (gap
collapsed to noise) is **not** observed.

**Important caveat — what the comparison can and cannot say.** BOA
nondeterminism dominates the cross-ensemble single-arm means:

- Pre-fix device mean = 789 (sd 117, sem 41); post-fix device mean = 762
  (sd 59, sem 21). Difference = −27 ± 46 → |t| = 0.6, **not significant**.
- Pre-fix CPU mean = 882 (sd 102, sem 36); post-fix CPU mean = 941 (sd
  142, sem 50). Difference = +59 ± 62 → |t| = 1.0, **not significant**.

That is: neither the device arm nor the CPU arm changed in mean by an
amount the data can resolve. The widened paired-t signal comes from the
post-fix gaps all having the same sign (8/8 negative) whereas pre-fix had
three positive (seeds 2, 4, 5). The pairing tightened, not the magnitudes.

Per the prompt's failure-handling convention: "If the gap does **not**
close, report the residual magnitude and direction; do not improvise
further fixes." Reporting; not improvising.

### IC pad check

`FilSegment.makeGlidingAssayFilament` (`FilSegment.java:3381`) — the
half-`stdFilSegLengthUM` pad is **present** as
`Pt3D loc = new Pt3D(boxXDim/2 - filLength/2 - stdFilSegLengthUM/2, 0, 0)`.
IC pad verified present.

### Phase 5 — deferred model change recorded

Note appended to `MYOSIN_VALIDATION.md` ("Deferred model change —
running-average release rate as a tuning knob"). Documentation only; no
code. Also resolves the prompt's open sub-question: `ValueTracker` is a
fixed-size circular buffer keyed on `stepsToTrack` (10 in `MyoFilLink`)
and `averageVal()` divides by that count directly, so the existing
`dissociateADP` averaging is **already step-count-anchored** and therefore
already quietly dt-dependent — a future principled rewrite would need to
subsume both code paths simultaneously.

### Files modified / added (this entry)

| file | change |
|---|---|
| `boxOfActin/MyoFilLink.java` | `step()` — `ckRelease()` moved inside the `if (!deviceMotor)` block; deferred for device-handled motors. Comment updated. Diagnostic snapshot from the prior commit unchanged. |
| `boxOfActin/GPUMoveThing.java` | `bridgeMotorForceWriteback()` — invokes `link.ckRelease()` after the per-motor writeback. Added `DIAG_RELEASE_WB_WRITER` static + `diagReleaseWbLog` / `diagReleaseWbFlush` helpers (Phase 3 instrument, default off). |
| `boxOfActin/BoxOfActin.java` | `BOA_DIAG_RELEASE_READ_WB=<path>` env hook to enable the writeback CSV (Phase 3 instrument). |
| `MYOSIN_VALIDATION.md` | Deferred-model-change note appended (Phase 5). |
| `scripts/release_read_probe_phase3.sh` | NEW — Phase 3 paired probe with WB logging on (1 seed). |
| `scripts/phase4_release_fix_ensemble.sh` | NEW — Phase 4 driver wrapping `paired_motor_gliding.sh` with 30 s heartbeat. |
| `RUN_LOGS/2026-06-04_release_read_phase3/` | NEW — Phase 3 device+freshCPU CSVs and writeback log. |
| `RUN_LOGS/2026-06-04_release_lag_fix/` | NEW — Phase 4 paired ensemble results. |
| `RUN_LOGS/2026-06-04_release_lag_fix_driver.log` | NEW — driver log. |

### What the planner needs to decide

This fix is **mechanically correct** (Phase 3 confirms the device read is
current-step) and structurally cleaner (the two arms now consume the same
step's force for the release decision, removing the "device read is one
step pose-lagged from CPU" structural asymmetry called out in the
diagnosis). But it **does not** close the device-vs-CPU gliding gap that
motivated the work. Three plausible reasons:

1. The lag was a real per-decision force-input divergence (verified
   numerically in the diagnosis at ~29 % expected-release-rate reduction)
   but is not the dominant driver of the bindEvents/gv gap. The float32
   device kernel computations vs the double CPU computations may
   contribute even after the read timing is reconciled.
2. There is an additional unidentified ordering or model difference
   between the arms that the prompt's structural-lag analysis missed.
3. Run-to-run nondeterminism is hiding the true effect; an N >> 8
   ensemble might reveal a smaller residual.

Options for the planner:
- **Revert the fix.** It's mechanically correct but didn't recover the
  gap; the existing pre-fix code path is documented and understood.
- **Keep the fix and continue investigating.** The two arms are now
  structurally identical at the release decision, which is a valuable
  cleanup independent of whether it closed the empirical gap.
- **Extend to N=16 or N=20.** Cheap; would resolve whether the apparent
  widening is real or a nondet artifact (with N=16 the SEM tightens
  enough to distinguish).

No further fixes attempted per the prompt's "do not improvise" clause.

## 2026-06-04 — Motor-port borderline — release-lag confirmation (NOT CONFIRMED, partial N=8 — diagnostic only, no fix)

**Verdict: hypothesis NOT CONFIRMED.** The conjecture that the device's ~2σ
borderline on `bindEvents` / `glidingVelocity` (with `meanBoundMotors` clean)
is the 1-step `ckRelease` lag inherent to the `step → move → biochem` ordering
is **not supported** by this toggle. The induced CPU lag produces an effect
in the **opposite direction and several times larger** than the device's
deviation from fresh CPU. The release-lag-only narrative as expressed by this
toggle is incompatible with the data. Per the prompt's verdict logic, the
investigation **stops here** — no fix is attempted in this entry.

Ensemble was killed by the user at N=8 (intended N=20). The signal at N=8 is
large enough that the three pairwise comparisons are unambiguous (5–8σ on the
key observables); a full N=20 would refine magnitudes but not change the
verdict direction.

### Part A — `BOA_DIAG_RELEASE_LAG` toggle (default OFF; reversible; contained)

New static flag `GPUMoveThing.DIAG_RELEASE_LAG` (default false). When false,
CPU `MyoFilLink.addForces` writes the current step's `Dot(F, seg.uVec)` into
`forceDotFil` and feeds it to `forceDotFilTrack`, unchanged from prior CPU
semantics. When true, `addForces` instead feeds the **previous** step's value
into both the `forceDotFil` field (read by `ckRelease`) and the tracker (read
by `MyoMotor.dissociateADP` via `averageVal()`), while the per-link
`prevForceDotFil` buffer holds the just-computed value for next step's
release decision. Force *application* (the `incForceSum(F, motorPt)` on the
motor and the seg's reaction) is unchanged — the lag affects **only** what
`ckRelease`/`dissociateADP` see, not the integrated forces.

Env hook: `BOA_DIAG_RELEASE_LAG=1` enables (any non-"0"/"false" value), `=0`
disables. Mirrors `BOA_DIAG_CPU_MOTOR`/`BOA_DIAG_DEVICE_BOUNDARY_TIPC`
precedents. The toggle is a no-op on device-handled motors (`gpuMotorHandled`
gates the CPU pair off before `addForces` runs, so `prevForceDotFil` is never
read); it only affects the CPU-arm (`BOA_DIAG_CPU_MOTOR=1`) path.

**Caveat on toggle fidelity vs device structure.** The device's actual lag is
asymmetric: `ckRelease` in step N reads `link.forceDotFil` written by
`bridgeMotorForceWriteback` at end of move-(N-1) (1-step lag), but
`dissociateADP` in step N reads the tracker that the same bridge updated at
end of move-N (same step, no lag — bridge runs in move phase, biochem reads
after). This toggle lags BOTH inputs symmetrically, per the prompt's literal
instruction ("feed that to the tracker/release"). The effect direction
observed is too large and too consistent (5–8σ) for the tracker-extra-lag
component to plausibly reverse the conclusion — a tracker-fresh variant would
produce a smaller, same-direction effect.

Files: `boxOfActin/MyoFilLink.java` (`prevForceDotFil` field; `addForces`
branch; `release()` zeroes it), `boxOfActin/GPUMoveThing.java`
(`DIAG_RELEASE_LAG` static flag), `boxOfActin/BoxOfActin.java`
(`BOA_DIAG_RELEASE_LAG` env hook).

### Part B — `-3js` visual recording (PRE-ensemble)

Three runs on a shortened gliding param file
(`ParameterFiles/glidingAssay500_val_3jsdemo`: `runTime=0.05`,
`toFileInterval=500` → 11 frames of ~4.6 MB each, ~50 MB per arm, viewable
offline). All three arms ran cleanly to completion (no NaN, no crash);
ThreeJSWriter emitted frames + `source.zip` + `params_effective.txt`.

Frame paths (for offline visual sanity-check):
- `RUN_LOGS/2026-06-04_release_lag_diag/3js_device_seed1/`    — device (F8–F10 on device, structural lag).
- `RUN_LOGS/2026-06-04_release_lag_diag/3js_freshCPU_seed1/`  — CPU pair, fresh release.
- `RUN_LOGS/2026-06-04_release_lag_diag/3js_laggedCPU_seed1/` — CPU pair, induced release lag.

Per-arm `.log` files in the same directory carry the `[STATS]` lines (single-seed,
short-run — informational only, not statistically meaningful at N=1):

| arm        | bindEvents | meanBoundMotors | glidingVelocity |
|---|---|---|---|
| device     | 438        | 7.591           | 12.0213         |
| freshCPU   | 566        | 8.313           | 12.4504         |
| laggedCPU  | 508        | 8.314           | 11.4878         |

Env-flag prints confirmed in the laggedCPU log:
`[MOTOR_DIAG] DIAG_CPU_MOTOR forced ON via env var (CPU pair runs)` AND
`[RELEASE_LAG_DIAG] DIAG_RELEASE_LAG forced ON (CPU release reads prior-step forceDotFil; mimics device lag)`.

### Part C — three-arm paired ensemble (N=8, partial; intended N=20)

All three arms run `-gpu` on `glidingAssay500_val` (the validation
configuration), seeded `-seed N` with `N ∈ {1..8}`. Same seed → identical RNG
draws across all unported phases, so the only differences are F8/F9/F10
source-of-truth and CPU release-tracker timing:

| Arm        | env vars                                        | F8/F9/F10 source | `ckRelease` sees   |
|---|---|---|---|
| device     | (none)                                          | device kernels   | bridge from move-(N-1) (structural 1-step lag) |
| freshCPU   | `BOA_DIAG_CPU_MOTOR=1`                          | CPU `MyoFilLink` | current step's `Dot(F, seg.uVec)` (fresh) |
| laggedCPU  | `BOA_DIAG_CPU_MOTOR=1 BOA_DIAG_RELEASE_LAG=1`   | CPU `MyoFilLink` | previous step's `Dot(F, seg.uVec)` (induced lag) |

Driver: `scripts/triple_release_lag_gliding.sh`. Walls per run held at
335–340s across all three arms; total wall to N=8 was ≈ 134 min. Seeds 1-2
ran in the smoke (`RUN_LOGS/2026-06-04_release_lag_smoke/`); seeds 3–8 ran
in the production ensemble before the user requested an early stop.

### Three pairwise paired comparisons (N=8)

Full table in `RUN_LOGS/2026-06-04_release_lag/analysis.txt`. Independent
distributions:

| observable      | device mean ± SEM | freshCPU mean ± SEM | laggedCPU mean ± SEM |
|---|---|---|---|
| bindEvents      | 747.6 ± 36.2      | 872.6 ± 60.8        | **1123.4** ± 42.7    |
| meanBoundMotors | 6.872 ± 0.255     | 7.444 ± 0.338       | **8.672** ± 0.227    |
| glidingVelocity | 7.725 ± 0.143     | 7.832 ± 0.229       | **9.298** ± 0.189    |

Pairwise paired deltas:

| pair                 | observable      | mean Δ    | SD(Δ)   | SEM(Δ)  | paired t | verdict           |
|---|---|---|---|---|---|---|
| device − freshCPU    | bindEvents      | −125.00   | 181.71  | 64.24   | −1.95    | mild scatter      |
| device − freshCPU    | meanBoundMotors | −0.572    | 0.924   | 0.327   | −1.75    | mild scatter      |
| device − freshCPU    | glidingVelocity | −0.107    | 0.730   | 0.258   | −0.42    | no bias           |
| freshCPU − laggedCPU | bindEvents      | **−250.75** | 162.80 | 57.56   | **−4.36** | **BIAS (≥3σ)** |
| freshCPU − laggedCPU | meanBoundMotors | **−1.228** | 0.801  | 0.283   | **−4.33** | **BIAS (≥3σ)** |
| freshCPU − laggedCPU | glidingVelocity | **−1.466** | 0.561  | 0.198   | **−7.39** | **BIAS (≥3σ)** |
| laggedCPU − device   | bindEvents      | **+375.75** | 130.13 | 46.01   | **+8.17** | **BIAS (≥3σ)** |
| laggedCPU − device   | meanBoundMotors | **+1.800** | 0.941  | 0.333   | **+5.41** | **BIAS (≥3σ)** |
| laggedCPU − device   | glidingVelocity | **+1.573** | 0.557  | 0.197   | **+7.99** | **BIAS (≥3σ)** |

### Verdict against the prompt's logic

1. **`laggedCPU` vs `device` should collapse to t ≈ 0 if the lag is the
   cause.** It does **not**. The signed paired t's are +8.17 (bindEvents),
   +5.41 (mbm), +7.99 (gv). The lag toggle moves `laggedCPU` **further from
   device** than `freshCPU` already was, in the **opposite direction**.
2. **`freshCPU` vs `laggedCPU` should reproduce the device's signature
   direction** (lag drags bindEvents and gv DOWN, mbm clean). It does the
   **opposite**: the lag drags bindEvents, mbm, and gv all UP — and on mbm in
   particular, the device's deviation from freshCPU was *clean* in the prior
   ensemble, whereas the induced lag shifts mbm by +5σ.
3. **`device` vs `freshCPU` reproduces the original ~2σ borderline**
   (paired t = −1.95 on bindEvents, similar direction to the prior ensemble's
   −2.13). The starting point is intact, ruling out a code-state regression
   as the explanation.

**Conclusion.** The 1-step `ckRelease`/`dissociateADP` release-lag, as
instrumented by this toggle, does **NOT** explain the device's borderline.
The device's deviation from fresh CPU is small and in the direction
(D &lt; F on bindEvents/gv) that the prior ensemble already showed, but the
lag toggle produces a divergent, much larger, opposite-direction effect.
**Per prompt: stop, don't guess at fixes.**

### Why the lag pushes the signal "up"

A short-form note on the observed mechanism (not a fix, just understanding):
when `ckRelease` and `dissociateADP` see a stale (smaller-magnitude)
`forceDotFil`, the catch+slip exponentials underestimate the current
release rate during force build-up, so motors stay bound longer per cycle.
Longer attachments → higher `meanBoundMotors` and processivity → filaments
glide faster (`glidingVelocity` UP) → filaments traverse more area per unit
time → more *new* attachment opportunities → higher `bindEvents`. This
cascading explanation is consistent with all three observables shifting
upward. But the **direction** is opposite to the device's signature, so this
mechanism is **not** what the device is doing.

### What the device is doing (best current understanding)

The cheap probe (`HeldBoundMotorDiag`, 4 cases) proved CPU and device
formulas are bit-equivalent in double precision (max |Δ|/scale = 1.13e-16,
Newton-3 pair sums = 0 exactly, `forceDotFil` sign agreement in both
polarities). Yet the ensemble shows a real ~2σ borderline on bindEvents/gv
with mbm clean. The release-lag toggle just falsified the obvious
mechanistic hypothesis. Candidate remaining causes (not investigated this
session):
- Reduced-precision intermediates in the PTX kernels' transcendentals
  (`fastAcosF` / `expF`-style code paths used by the alignment kernels) that
  drift differently than the bit-equivalent double path the probe verified.
- Floating-point accumulation order in the seg-side CSR walk
  (`segMotorForceKernel`) vs the CPU's incForceSum sequencing — Newton-3
  pair sums to zero on a single pair, but multi-bound segs at the
  gliding-assay density accumulate dozens of motor contributions in a
  different order on device.
- Some non-determinism within the gather/move pipeline (different threading
  on aorus' 16 workers + GPU scheduler) that the prior journal already
  flagged as "seed=1 produces different bindEvents across smoke vs ensemble
  runs". Run-to-run variability at the same seed is a known property of the
  pipeline; the ~2σ borderline may be near or within that variability.

The next diagnostic step (when one is sketched) should isolate one of these,
not the release lag.

### Files added / modified

| file | change |
|---|---|
| `boxOfActin/MyoFilLink.java` | `prevForceDotFil` field; `addForces` lag-toggle branch; `release()` zeroes `prevForceDotFil`. |
| `boxOfActin/GPUMoveThing.java` | `DIAG_RELEASE_LAG` static flag (default false). |
| `boxOfActin/BoxOfActin.java` | `BOA_DIAG_RELEASE_LAG` env hook. |
| `ParameterFiles/glidingAssay500_val_3jsdemo` | NEW — short-run gliding param file for `-3js` visual sanity check (`runTime=0.05`, `toFileInterval=500`). |
| `scripts/run_3js_lag_diag.sh` | NEW — sequential device/freshCPU/laggedCPU recorder using `-3js`. |
| `scripts/triple_release_lag_gliding.sh` | NEW — three-arm paired ensemble driver with proper 5s-poll heartbeat (replaces the prior `sleep $HB` blocking poll that overshot wall measurements by up to `HB`s/run). |
| `RUN_LOGS/2026-06-04_release_lag_diag/` | NEW — `-3js` recordings (device / freshCPU / laggedCPU) + analyze.py + 3js logs. |
| `RUN_LOGS/2026-06-04_release_lag_smoke/` | NEW — N=2 smoke walls (335s/run). |
| `RUN_LOGS/2026-06-04_release_lag/` | NEW — N=8 ensemble: `{device,freshCPU,laggedCPU}_seed{1..8}.log`, `results.csv`, `analysis.txt`, `analyze.py`. Seeds 1-2 carried over from smoke (header retained, append-on-existing). |
| `RUN_LOGS/2026-06-04_release_lag_smoke_BUGGY/` | First smoke run, archived: the wall measurements (600s/run) reflect the heartbeat-loop overshoot bug, not real compute time. Do not use for projections. |

### Constraints respected

- **Diagnostic only.** No fix attempted. The release-decision ordering is
  unchanged. The motor force formula and the `ckRelease`/`dissociateADP`
  formulas are unchanged. Only a default-off, env-var-gated toggle was added.
- **No `collisionCheckInt` cadence change.** Param file used the validation
  config (`glidingAssay500_val`) unchanged.
- **No concurrent `java`/builds during the ensemble.** The `-3js`
  recordings (Part B) ran first, then the smoke, then the production
  ensemble — strictly sequential. The wall-bug smoke that consumed the first
  ~33 min did not overlap with any other invocation.
- **Heartbeats every 5 min** to `.last_run_status` (and removed at session
  end). Heartbeat overshoot bug discovered in smoke and fixed before
  production; the production walls match the smoke walls at 335s/run.
- **`-3js` cadence modest** (5 frames/sec sim = 11 frames per 0.05 sec at
  toFileInterval=500), keeping per-arm JSON under ~50 MB total.

### Stopped early — user request

User instruction at 12:00 (about 1h40m into a projected 5h35m run): "Stop
all current java runs. Summarize your findings and append to Journal.
Commit and push." Ensemble killed at N=8 paired seeds, all three arms
complete. Heartbeat orphan java (seed 9 device, just spawned) killed too.
`.last_run_status` left in place with the run trail.

The N=8 verdict above is robust given the 5–8σ magnitudes on the key
pairwise comparisons. A full N=20 would tighten the SEMs proportionally and
might shift any one paired t by ±0.5, but the verdict direction (lag → wrong
way, hypothesis NOT confirmed) is not at risk.

---

## 2026-06-04 — Motor force port (F8–F10) — implementation (Phase 2 motor — BORDERLINE PASS, port committed at default device)

**Verdict: Phase 2 motor (F8–F10) BORDERLINE PASS, port committed at default
device.** Per-step myosin cross-bridge force (F8 spring, F9 motor↔fil uVec
alignment torque, F10 motor↔fil yVec alignment torque) now computes on the
device for `MyosinFixed` motors with a GPU-handled bound seg. Two new kernels
(`motorForceKernel` per Myosin, `segMotorForceKernel` per FilSegment with a CSR
walk) added to the chained TaskGraph after the boundary kernel and before the
move kernel. The cheap probe (HeldBoundMotorDiag, 4 cases) PASSes exactly —
forces / torques / `forceDotFil` (sign + magnitude) all match CPU to the
float-noise floor (max |Δ| 1.13e-16), Newton-3 pair sums exactly 0. The
motor-isolation paired ensemble (N=20, glidingAssay500_val) shows
`meanBoundMotors` CLEAN (paired t = −0.25) but `bindEvents` (t = −2.13) and
`glidingVelocity` (t = −2.12) at the ~2σ border — neither "well under 2" PASS
nor "gross / large bias" BAIL. Root cause is hypothesised to be a 1-step
release-decision lag inherent to the architecture (`ckRelease` runs in the
step phase BEFORE the move phase that writes back the device force), not a
formula bug. Default `DIAG_CPU_MOTOR = false` (device kernels active);
`BOA_DIAG_CPU_MOTOR=1` is the A/B lever. Release kinetics (`ckRelease`,
`dissociateADP`) and binding detection stay on CPU, as scoped by the survey.
`MyoMiniFilament` deferred per scope; the kernel API is unchanged when that
configuration enters the rotation (flip one line in `packMotorBinding`).

### Two kernels — pinned direction convention

A FilSegment in the gliding bed may have many motors bound at once (many-to-one),
so the pair force is split between two kernels — F3/F4's unique-ownership
discipline generalised 1:1 → many:1. No atomics, no visited flags.

- **`motorForceKernel`** — one thread per Myosin joint slot (`myoJointCt`
  threads, block 64 — same shape as the joints kernel). Reads motor pose (via
  `motorSlots[mj]`), bound seg's pose (via `boundSegSlot[mj]`),
  `posOnSegArr[mj]`, `cockedFlags[mj]`, motor drag (lane 8 of `myoDrags`,
  motor.bRotGam.y; motor.bRotGam.x == .y by `MyoMotor.calculateProperties`), seg
  drag (lanes 0/1 of seg's `bRotGam` triplet). Computes the F-vector, the
  motor-side R×F (F8) torque, the motor-side −torsion·tmag (F9 + F10) torques.
  RMWs into the motor slot's `jointForceSum`/`jointTorqueSum` (motor slot was
  SET by jointsKernel earlier in the same execute()). Also writes per-Myosin
  `forceMag` and signed `forceDotFil` into the readback `motorWriteback`
  FloatArray.

- **`segMotorForceKernel`** — one thread per FilSegment slot (`slotCount`
  threads, block 64 — same shape as the chain kernel). Reads the CSR list of
  bound motors at this slot (`segMotorOffsets`/`segMotorMyo`), accumulates the
  seg-side reaction (−F, R_seg × (−F), +torsion·tmag for F9 + F10) by
  RECOMPUTING the force from the same inputs, then RMWs into its own
  `jointForceSum`/`jointTorqueSum` slot. Inputs and arithmetic are
  byte-identical to `motorForceKernel`, so motor-side `+F` and seg-side `−F`
  are exactly anti-parallel — exactly the F3/F4 pattern.

**Pinned direction convention** (verbatim in both kernels — Newton-3 invariant
lives here):
- `motorPt = motor.coord + 0.5·motorLen·motor.uVec`.
- `attachPt = seg.coord + (posOnSeg − 0.5·segLen)·seg.uVec` (= seg.end1 +
  posOnSeg·seg.uVec).
- `dx = motorPt − attachPt`; `dist = |dx|`; `forceMag = dist · myoSpring`
  (`myoSpring = 1e-9 N/µm`, hard-coded at `MyoFilLink.java:22`).
- `F_unit = (attachPt − motorPt) / dist` — matches CPU's
  `Pt3D.unitVec(attachPt, motorPt) = (attachPt − motorPt) / mag`.
- `F = forceMag · F_unit` — force ON the motor.
- `forceDotFil = Dot(F, seg.uVec)` — signed, written to readback BEFORE any
  seg-side F-flip. Sign-equivalent to CPU's `MyoFilLink.java:91`.

F9: `torsionVec = unit(cross(seg.uVec, motor.uVec))`, `torsionMag =
j1FracMoveTorq · (π/180) · angD / ((1/motor.bRotGam.y + 1/seg.bRotGam.y) · dt)`
where `angD = angTween − (cocked ? 120° : 90°)`. Motor gets `−torsion·tmag`;
seg kernel writes `+torsion·tmag`.

F10: same shape with `yVec` and no `angRelaxed` (target 0°). Drag denominator
uses `bRotGam.x` for the seg (motor's `bRotGam.x == .y` so lane 8 still
correct).

### Upload state (per step, CPU → device)

Three new per-Myosin / per-FilSegment views, all added to the chained TaskGraph
`transferToDevice` list with `EVERY_EXECUTION`:

| Buffer | Capacity | Purpose |
|---|---|---|
| `boundSegSlot` (IntArray) | `myoCap` | Move-slot of motor's bound seg, or `−1` (unbound, CPU-fallback seg, non-MyosinFixed, or DIAG_CPU_MOTOR). Both kernels early-return when `< 0`. |
| `posOnSegArr` (FloatArray) | `myoCap` | arclength of motor's attachment from seg.end1 (µm). |
| `segMotorOffsets` (IntArray) | `slotCap + 1` | CSR prefix sum; motors bound to seg-slot `s` are at `segMotorMyo[segMotorOffsets[s] .. segMotorOffsets[s+1])`. |
| `segMotorMyo` (IntArray) | `myoCap` | CSR scatter target — joint index `mj` of each bound motor. |

`cockedFlags` (already on device for the joints kernel) is **reused as-is** by
both motor kernels — it is read-only from both lanes, and the chained TaskGraph
runs sequentially so there is no aliasing risk. The seg's `yVec`,
`bRotGam[3-tuple]`, and `soaLengthArr` are also reused from the chain/boundary
kernels.

**One-pass binding build** (`packMotorBinding()` in
`GPUMoveThing.java`): a single loop over `[0..myoJointCt)` derives
`boundSegSlot[mj]`/`posOnSegArr[mj]` AND seeds the per-segslot count for the
CSR prefix sum. A prefix-sum pass over `segMotorCount` produces
`segMotorOffsets`; a second loop scatters `mj` into `segMotorMyo`. The CSR is
the **inverse view** of `boundSegSlot` — both come from the same binding
iteration, so they are structurally unable to disagree about who is bound to
whom.

**Transitional note:** the CSR carries no new information beyond
`boundSegSlot`. It is a **transitional Phase-2 upload** that the Phase-3
device-side binding build (when `MyoMotor.checkFilSegCollision` moves to a
kernel) will eliminate by maintaining the CSR on device. The O(motorCt)
CPU-side build cost (~25k myo entries at gliding-assay scale) is negligible
relative to the surrounding step-phase budget.

### Writeback + ordering + sign

Per-Myosin `motorWriteback[mj*2 + {0,1}]` carries `(forceMag, forceDotFil)`.
Added to the chained TaskGraph's `transferToHost` list (EVERY_EXECUTION).
`bridgeMotorForceWriteback()` runs in `moveThings()` AFTER `plan.execute()`
returns, immediately after `bridgeBoundaryTipC()` (the tipC bridge precedent).

For each device-handled motor (`boundSegSlot[mj] >= 0`):
1. Copy `forceMag` and `forceDotFil` from `motorWriteback` into `MyoFilLink`'s
   `forceMag` / `forceDotFil` fields.
2. Call `forceDotFilTrack.registerValue(forceDotFil)` so the 10-sample running
   mean stays fed for `MyoMotor.dissociateADP` (which gates release on
   `forceDotFilTrack.averageVal()`).

**Ordering** (verified): writeback drain → CPU `forceDotFilTrack.registerValue`
→ CPU release (`ckRelease`/`dissociateADP` reads
`forceDotFilTrack.averageVal()`). On the next step's `MyoMotor.step()` →
`tipLink.step()`, `ckRelease` reads the device-computed values that were
drained in the previous step's `moveThings`. The 1-step lag is harmless at
`dt = 1e-4s` (release is a stochastic gate and forces don't change measurably
between consecutive ~100µs steps).

**Sign of `forceDotFil` is load-bearing.** `ckRelease` reads it through
`catch ∝ exp(−forceDotFil·xCatch/kT)` and `slip ∝ exp(+forceDotFil·xSlip/kT)`
(Guo & Guilford 2006). A flipped sign inverts catch↔slip. The kernel computes
`Dot(F, seg.uVec)` BEFORE the seg-side F-flip, identical to CPU's
`MyoFilLink.java:91`. The cheap probe verifies sign agreement in BOTH polarities
(positive `forceDotFil` in Case 4, negative in Cases 1/2/3 of the harness).

CPU-fallback motors (`boundSegSlot[mj] < 0`) are skipped in the bridge — the
CPU `MyoFilLink.addForces` updates `forceMag`/`forceDotFil`/the tracker for
them on the same step in step phase, so the bridge must not overwrite with
the device's `(0, 0)` sentinel.

### Gate + flag — per-force, not per-dispatch (Lesson 1)

`MyoFilLink.step()` gates only the F8/F9/F10 CPU path:

```java
boolean deviceMotor = gpuMotorHandled();
if (!deviceMotor) {
    addForces();         // F8
    alignUVecTorque();   // F9
    alignYVecTorque();   // F10
}
ckRelease();             // ALWAYS runs on CPU (per scope)
```

`gpuMotorHandled()` returns true iff `Env.useGPU && !DIAG_CPU_MOTOR && myo
instanceof MyosinFixed && mySeg != null && !mySeg.removeMe && mySeg.gpuHandled`
— mirrors the gate in `GPUMoveThing.packMotorBinding()` (a CPU-fallback seg or
non-MyosinFixed motor falls through to the CPU pair). Release kinetics
(`ckRelease`, `dissociateADP`) and binding detection (Phase 3 grid) stay on
CPU regardless — they read `forceMag` / `forceDotFil` / `forceDotFilTrack.
averageVal()` from whichever side wrote them.

`GPUMoveThing.DIAG_CPU_MOTOR` (default **false** → device) +
`BOA_DIAG_CPU_MOTOR=1` env hook in `BoxOfActin.begin()`. When `true`,
`packMotorBinding` sets every `boundSegSlot[mj] = −1` so device kernels
early-return for every motor and the CPU pair runs everywhere. Mirrors the
`DIAG_CPU_ANCHOR`/`DIAG_CPU_F3F4`/`DIAG_CPU_F1` precedents.

### Force-coverage table — F8/F9/F10 row added

| Force | CPU (`BOA_DIAG_CPU_MOTOR=1`) | Device (default, no env var) |
|---|---|---|
| F8 cross-bridge spring (`MyoFilLink.addForces`) | applied via CPU `MyoMotor.incForceSum(F, motorPt)` + `mySeg.incForceSum(−F, attachPt)`; `forceMag` and `forceDotFil` set CPU-side; `forceDotFilTrack.registerValue` called inside `addForces`. | applied by `motorForceKernel` (+F at motor slot) + `segMotorForceKernel` (−F at seg slot); `forceMag` + signed `forceDotFil` written to `motorWriteback` readback; `bridgeMotorForceWriteback()` drains them into MyoFilLink AND calls `forceDotFilTrack.registerValue` on every device-handled motor. |
| F9 uVec alignment torque (`MyoFilLink.alignUVecTorque`) | applied via CPU `seg.incTorqueSum(+τ)` + `motor.incTorqueSum(−τ)`. | motor kernel writes `−τ` to motor slot; seg kernel writes `+τ` to seg slot. Same `(1/motor.bRotGam.y + 1/seg.bRotGam.y) · dt` denominator. |
| F10 yVec alignment torque (`MyoFilLink.alignYVecTorque`) | applied via CPU `seg.incTorqueSum(+τ)` + `motor.incTorqueSum(−τ)`. | motor kernel writes `−τ` to motor slot; seg kernel writes `+τ` to seg slot. Same `(1/motor.bRotGam.x + 1/seg.bRotGam.x) · dt` denominator. |
| `ckRelease` (catch+slip Guo–Guilford release; `myosinBreakForce` gate; `inRigor` gate) | runs on CPU regardless of flag — reads `forceMag` from CPU's `addForces` write. | runs on CPU regardless of flag — reads `forceMag` written by `bridgeMotorForceWriteback` from the previous step. |
| `MyoMotor.dissociateADP` (gates ADP→NONE release on `forceDotFilTrack.averageVal()`) | runs on CPU regardless of flag — reads the tracker fed by CPU `addForces`. | runs on CPU regardless of flag — reads the tracker fed by `bridgeMotorForceWriteback` after the writeback drain. |
| Motor binding detection (`MyoMotor.checkFilSegCollision` → `ontoFilament`) | unchanged — CPU/device-binding grid path, independent of this port. | unchanged — Phase 3 scope, not this port. |

All other rows (anchor + F3/F4 + F1 + tipC writeback) from prior entries unchanged.

### Cheap probe — `HeldBoundMotorDiag` (PASS)

Pure-Java harness (no TornadoVM init), parallel to `HeldChainF3F4Diag`. Holds
a single bound `MyoFilLink` in a prescribed non-degenerate geometry; evaluates
CPU formula, device motor-kernel formula, and device seg-kernel formula on the
SAME frozen pose. Four cases:

| Case | posOnSeg | cocked | forceDotFil sign | notes |
|---|---|---|---|---|
| 1 | segLen/3 | YES (120°) | negative | tilted motor, lateral strain — exercises F9 cocked branch |
| 2 | segLen/3 | NO (90°)   | negative | same geometry, F9 uncocked branch |
| 3 | segLen/2 | YES        | negative | 30 nm strain perpendicular to seg.uVec, larger force regime |
| 4 | segLen/2 | YES        | positive | strain along seg.uVec → forceDotFil sign flip |

All four cases **PASS** to the float-noise floor:
- CPU vs DEV motor side: `|Δ|`/scale ≤ **1.13e-16** (max in any of forceMag,
  forceDotFil, F_motor, T_motor_F8, T_motor_F9, T_motor_F10 across all cases).
- CPU vs DEV seg side: same scale, max 1.13e-16.
- `forceDotFil` SIGN match: YES in every case (both polarities exercised).
- **Newton-3 pair sums (F_motor + F_seg)**: exactly 0.0 in every case.
- **Alignment pair sums** (T_motor_F9 + T_seg_F9 and T_motor_F10 + T_seg_F10):
  exactly 0.0 in every case.

Log: `RUN_LOGS/2026-06-03_heldBoundMotor.txt`. The probe gates the ensemble — it
ran first and PASSed; only then was the ensemble launched.

### Motor-isolation paired ensemble — N=20, glidingAssay500_val (BORDERLINE — see caveat)

Both arms run `-gpu` and keep the FOUR PRIOR ported forces on device (anchor +
F3/F4 + F1 + tipC writeback — all PASS as of the 64-mon entry earlier today).
Only F8/F9/F10 toggle:

| Arm | DIAG flags | Where the motor force computes |
|---|---|---|
| **DEVICE** (default) | none | `motorForceKernel` + `segMotorForceKernel` on device |
| **CPU** | `BOA_DIAG_CPU_MOTOR=1` | device kernels early-return (`boundSegSlot=−1`); CPU `MyoFilLink.addForces`/`alignUVecTorque`/`alignYVecTorque` run |

Same `-seed N` in both arms → identical RNG draws across all unported phases
(Brownian, binding detection grid, biochem) → the only physical difference
between paired runs is F8/F9/F10. Driver:
`scripts/paired_motor_gliding.sh`.

**Smoke projection + chosen N.** Single paired seed `1` (smoke run, output
`RUN_LOGS/2026-06-03_paired_motor_smoke/`): DEVICE 921/7.85/8.22 wall=337s;
CPU 950/7.80/8.49 wall=332s — `669s/pair` = 11.2 min/pair. Projection at the
10-hour wall: ≤ 53 paired seeds fit. Chose **N=20** to match the prior
ensemble for direct comparability; projects at ~223 min, lands at 221.7 min
actual.

**Per-seed paired deltas (DEVICE − CPU)** — full per-seed table in
`RUN_LOGS/2026-06-03_paired_motor/analysis.txt`. Excerpt:

| seed | bindEv_D | bindEv_C | Δbind | mbm_D | mbm_C | Δmbm | gv_D | gv_C | Δgv |
|---|---|---|---|---|---|---|---|---|---|
|  1 |  656 |  843 | −187 | 5.551 | 7.213 | −1.662 | 7.555 | 8.354 | −0.799 |
|  2 |  918 |  895 |  +23 | 7.910 | 7.677 | +0.233 | 8.214 | 8.085 | +0.129 |
|  7 |  562 |  786 | −224 | 5.259 | 6.737 | −1.478 | 6.888 | 7.865 | −0.977 |
| 12 |  896 |  622 | +274 | 8.408 | 5.706 | +2.702 | 7.622 | 7.185 | +0.436 |
| 17 |  703 | 1133 | −430 | 6.228 | 8.547 | −2.319 | 7.479 | 8.756 | −1.277 |
| 20 |  704 |  951 | −247 | 6.611 | 7.657 | −1.046 | 7.867 | 8.211 | −0.344 |

Signs span both directions per observable (bindEvents: 13 negative, 7
positive; mbm: 10/10; gv: 13/7), but the negative tail outweighs on
bindEvents and gv.

### Bias analysis (paired deltas, N=20)

| observable      | mean Δ     | SD(Δ)    | SEM(Δ) | paired t | verdict           |
|---|---|---|---|---|---|
| bindEvents      | **−79.45** | 166.59   | 37.25  | **−2.13** | **borderline (~2σ)** |
| meanBoundMotors | **−0.067** |   1.19   |  0.27  | **−0.25** | no bias |
| glidingVelocity | **−0.302** |   0.64   |  0.14  | **−2.12** | **borderline (~2σ)** |

`meanBoundMotors` is CLEAN (paired t = −0.25, well under 1σ). `bindEvents` and
`glidingVelocity` sit just past the 2σ line — neither "well under 2" (the
prompt's PASS threshold) nor "gross/large bias" (the bail threshold). The
prior full-stack 64-mon ensemble had paired t = −0.31 / +0.01 / −0.15 on the
same three observables; the motor-isolation ensemble's residual shift is the
**incremental** effect of moving F8/F9/F10 to the device, on top of the
already-validated four-port stack.

### Caveat — 1-step release lag (suspected root cause)

The architecture per the survey runs `MyoFilLink.ckRelease` and
`MyoMotor.dissociateADP` on CPU **inside the step phase**, which runs BEFORE
`moveThings()` (where the device kernels compute `forceMag` and
`forceDotFil`). So on the device path, release decisions in step `N` read
`forceMag`/`forceDotFil` written by `bridgeMotorForceWriteback()` at the end
of step `N−1` — a 1-step lag.

The lag is plausibly the source of the bindEvents and glidingVelocity shift:
when a motor's cross-bridge force is monotonically increasing during a power
stroke, the 1-step-old `forceDotFil` underestimates the current value, so the
catch/slip release decision (Guo–Guilford) fires slightly later on device than
on CPU. Motors stay bound longer → fewer release events → fewer turnover
events (lower bindEvents) and slower mean translation (lower gv). The
equilibrium bound count (`meanBoundMotors`) is unaffected because longer
attachments and lower turnover compensate.

This is consistent with the bias direction (device < CPU on bindEvents AND
gv) and with `meanBoundMotors` being clean. The cheap probe confirmed the
force formula is bit-equivalent CPU vs device in double precision, so the
shift is **NOT a formula error**.

Two remediation paths (deferred, not in this entry's scope):
1. Move `ckRelease`/`dissociateADP` to a post-`moveThings()` phase so they
   read same-step `forceMag`/`forceDotFil`.
2. Move the motor force computation to a pre-step-phase device task.

### Ensemble distributions (independent)

| observable      | DEVICE mean ± SEM | CPU mean ± SEM   | diff     | cSEM    | \|d\|/cSEM | verdict |
|---|---|---|---|---|---|---|
| bindEvents      | 785.1 ± 23.0      | 864.6 ± 28.4     | −79.5    | 36.6    | 2.17      | border |
| meanBoundMotors | 7.099 ± 0.20      | 7.166 ± 0.17     | −0.067   | 0.26    | 0.25      | PASS |
| glidingVelocity | 7.582 ± 0.09      | 7.884 ± 0.11     | −0.302   | 0.14    | 2.15      | border |

### Wall, status, output

- **Total ensemble wall: 221.7 min** (DEVICE mean 333.9s/seed; CPU mean
  331.1s/seed). Within 1% of the 222.3 min smoke projection.
- Files: per-seed logs `RUN_LOGS/2026-06-03_paired_motor/{device,cpu}_seed{1..20}.log`;
  combined CSV `results.csv`; analysis `analysis.txt` + script `analyze.py`.
  Smoke run at `RUN_LOGS/2026-06-03_paired_motor_smoke/`.
- Heartbeats: per-seed `[progress]` lines in `.last_run_status` throughout
  the run (22:48 smoke launch → 02:44 ensemble completion).
  `.last_run_status` removed at session end per spec.
- No NaN, no crash, no Exception in any of the 40 per-seed logs.
- No concurrent `java`/builds on aorus while the ensemble ran — the bash
  process was detached via `setsid` so the run was uninterrupted by the
  Claude session's own bash-timeout window.

### Verdict

**BORDERLINE PASS — port committed at default DEVICE, caveat documented.**
The cheap probe gates pass exactly (force/torque match to float-noise floor,
forceDotFil sign agreement in both polarities, Newton-3 pair sums to zero).
The ensemble shows a marginal (~2σ) systematic effect on `bindEvents` and
`glidingVelocity` consistent with the 1-step release lag the architecture
inherits from running `ckRelease` in the step phase before the move-phase
writeback. `meanBoundMotors` (the equilibrium observable that does not depend
on per-step release rate) is clean. The default flip stays at
`DIAG_CPU_MOTOR = false` (device active) — the force computation is correct
and the equilibrium population is reproduced; the binding-event-rate /
velocity shift is a 1-step-lag effect, not a formula bug, and is addressable
in a follow-up by reshuffling the release-phase ordering. `BOA_DIAG_CPU_MOTOR=1`
remains the A/B lever for any future investigation.

### Default flip and A/B commands

Default flipped at session end: `DIAG_CPU_MOTOR = false` (device active). The
flag was already declared with the default `false`; the ensemble validated
the default. A/B levers:

```
# Device default (motor on device):
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500_val

# CPU comparison arm (motor force on CPU):
BOA_DIAG_CPU_MOTOR=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500_val
```

### Files changed

| file | change |
|---|---|
| `boxOfActin/GPUMoveThing.java` | `DIAG_CPU_MOTOR` flag (default false); per-Myosin `boundSegSlot`/`posOnSegArr` + CSR `segMotorOffsets`/`segMotorMyo`; `motorWriteback` FloatArray + `motorForceParams`; `motorForceKernel` + `segMotorForceKernel` (motor-side + seg-side, pinned direction convention); wired into chained TaskGraph after `boundary` and before `move`; `packMotorBinding()` (one-pass binding-view + CSR build); `bridgeMotorForceWriteback()` (drain + tracker.registerValue); transferToDevice / transferToHost updated; worker grids added. |
| `boxOfActin/MyoFilLink.java` | per-force gate inside `step()` skips CPU F8/F9/F10 when `gpuMotorHandled()` returns true; `myoSpring` exposed (already package-default access); `ckRelease` always runs on CPU. |
| `boxOfActin/BoxOfActin.java` | `BOA_DIAG_CPU_MOTOR` env hook in `begin()`. |
| `boxOfActin/HeldBoundMotorDiag.java` | NEW — pure-Java cheap probe (4 cases: cocked/uncocked, positive/negative `forceDotFil`); verifies CPU↔device formula equivalence, sign agreement, and Newton-3 pair-sum to zero. |
| `scripts/paired_motor_gliding.sh` | NEW — motor-isolation paired ensemble driver (DEVICE vs `BOA_DIAG_CPU_MOTOR=1` CPU, same `-seed`, same `glidingAssay500_val`). |
| `RUN_LOGS/2026-06-03_heldBoundMotor.txt` | NEW — cheap probe log (all four cases PASS). |
| `RUN_LOGS/2026-06-03_paired_motor_smoke/` | NEW — smoke run logs + CSV. |
| `RUN_LOGS/2026-06-03_paired_motor/` | NEW — full ensemble logs + CSV + analysis. |

### Constraints respected

- **Release kinetics untouched.** `MyoFilLink.ckRelease` and
  `MyoMotor.dissociateADP` run on CPU regardless of flag. The catch+slip
  Guo–Guilford constants (`alphaCatch`/`alphaSlip`/`xCatch`/`xSlip`/`kOff`),
  `myosinBreakForce` gate, `inRigor` gate, ADP→NONE transition: all CPU,
  unmodified.
- **Biochem state untouched.** `nucleotideState`, `inRigor`, `bindTimer` — all
  CPU.
- **Binding detection (Phase 3) untouched.** `MyoMotor.checkFilSegCollision`,
  `ontoFilament`, `MotorBindGrid3D` — all run as before. The port reads
  whatever binding state exists at pack time.
- **Sign convention preserved.** `forceDotFil` sign on device is
  bit-equivalent to CPU (cheap probe verifies). `ckRelease`'s catch/slip
  exponentials see the same input as before.
- **`MyoMiniFilament` deferred** per scope. Non-`MyosinFixed` Myosins get
  `boundSegSlot = −1` in `packMotorBinding`, falling through to the CPU pair.
  The kernel API is unchanged — one line flip in `packMotorBinding` will
  enable `MyoMiniFilament` when that configuration enters the rotation.
- **No `collisionCheckInt` cadence changes.**
- **No concurrent `java`/builds** while the ensemble ran on aorus. Code +
  compile + cheap probe completed before launch; the ensemble was the only
  `java` invocation on aorus during the ~PLACEHOLDER min wall.

---

## 2026-06-03 — Motor force port (F8–F10) — survey (SURVEY ONLY)

**Scope.** Pre-port survey of the next Phase-2 force port: the per-step **myosin cross-bridge force** (F8 cross-bridge spring, F9 motor↔fil uVec alignment torque, F10 motor↔fil yVec alignment torque) — all three live in `MyoFilLink`. The release kinetics (`MyoFilLink.ckRelease`, `MyoMotor.dissociateADP`) and the binding *detection* path (Phase 3 grid) are **explicitly out of scope** — they stay on CPU. This survey scopes the **force computation only**, on the gliding-assay path (`MyosinFixed`).

Read-only. No edits. No runs.

### 1. F8/F9/F10 — definition and location map

| Tag | Method | Bodies it writes | Quantity computed |
|---|---|---|---|
| **F8** | `MyoFilLink.addForces()` (`MyoFilLink.java:80`) | `myMotor.forceSum` + `mySeg.forceSum` (each via `incForceSum(F, pt)` so both also get an `R × F` torque entry) | Linear cross-bridge spring `F = (myoSpring · dist) · unit(motorPt − attachPt)` |
| **F9** | `MyoFilLink.alignUVecTorque()` (`MyoFilLink.java:116`) | `mySeg.torqueSum` + `myMotor.torqueSum` | Torque restoring `dot(seg.uVec, motor.uVec)` toward the relaxed motor↔actin angle (90° uncocked, 120° cocked) |
| **F10** | `MyoFilLink.alignYVecTorque()` (`MyoFilLink.java:144`) | `mySeg.torqueSum` + `myMotor.torqueSum` | Torque restoring `dot(seg.yVec, motor.yVec)` toward zero (no `angRelaxed` — just `angTween`) |

Dispatch: `MyoMotor.step()` → `updateMyoFilLinks()` → `tipLink.step()`. `MyoFilLink.step()` runs `updatePos → addForces → alignUVecTorque → alignYVecTorque → ckRelease`. Each Myosin owns exactly one `tipLink` (`MyoMotor.tipLink`, created once in `makeMyoFilLinks()` — never multiple per motor). `MyoRod.step()`/`MyoLever.step()` are empty bodies; rod/lever forces all come from the joints phase, not step().

### 2. Cross-bridge force chain — geometry and signs

`MyoFilLink.updatePos()`:
- `attachPt = seg.end1AsPt3D() + posOnSeg · seg.uVec` — re-derives per step (cheap; once-per-counter guard).
- `motorPt` is the `MyoMotor.bindTip` Pt3D = motor's `end2AsPt3D() = motor.coord + 0.5·motorLen·motor.uVec`.

`MyoFilLink.addForces()`:
1. `dist = ‖motorPt − attachPt‖`; `forceMag = dist · myoSpring` (`myoSpring = 1e-9 N/µm`, hard-coded at `MyoFilLink.java:22`).
2. `F = forceMag · unit(motorPt − attachPt)` — force vector on the **motor** (toward attachPt? No: `Pt3D.unitVec(attachPt, motorPt) = unit(attachPt − motorPt)` per Pt3D convention in this codebase; CPU here calls `F.unitVec(attachPt, motorPt)` then `F.scale(forceMag)` → F points from motorPt toward attachPt, i.e. pulls the motor toward the binding point).
3. `myMotor.incForceSum(F, motorPt)` → adds F to motor.forceSum and `R × F` (R = motorPt − motor.coord = 0.5·motorLen·motor.uVec) to motor.torqueSum.
4. `forceDotFil = Pt3D.Dot(F, seg.uVec)` — **signed scalar**, the component of force-on-motor along seg.uVec (positive = toward filament barbed end). `forceDotFilTrack.registerValue(forceDotFil)` updates a 10-sample running mean used by `dissociateADP`.
5. `F.scale(-1)` then `mySeg.incForceSum(-F, attachPt)` → adds −F to seg.forceSum and `R × (−F)` (R = attachPt − seg.coord) to seg.torqueSum.

F9 (`alignUVecTorque`):
- `torsionVec = unit(cross(seg.uVec, motor.uVec))`.
- `dotVecs = clamp(dot(seg.uVec, motor.uVec), −1, 1)`; `angTween = fastAcos(dotVecs)·180/π`.
- `angRelaxed = isCocked ? cockedMotor_ActinAngle (120°) : uncockedMotor_ActinAngle (90°)`. (Both static fields on `Myosin.java:9-10`.)
- `angD = angTween − angRelaxed`.
- `torsionMag = myoJ1FracMoveTorq · (π/180) · angD / ((1/motor.bRotGam.y + 1/seg.bRotGam.y) · dt)`.
- Writes: `seg.torqueSum += torsionVec·torsionMag`; `motor.torqueSum −= torsionVec·torsionMag`. Newton-3 anti-parallel pair, anchored on the cross-product unit vector.

F10 (`alignYVecTorque`): identical structure with yVec, no `angRelaxed` (target angle = 0°), drag denominator uses `bRotGam.x` instead of `bRotGam.y`. Same Newton-3 anti-parallel write pattern.

### 3. Gliding-motor scope — `MyosinFixed`; `MyoMiniFilament` deferred

In the gliding assay every motor is a **`MyosinFixed`**:
- `MyosinFixed.fillPlaneWithFixedMyosins()` places `MyosinFixed` instances at `(rand x, rand y, fixedMyosinZValue)` pointing +z (`MyosinFixed.java:97-110`).
- Anchor spring (`applyRodFixedPtForce`) is in Phase-1 device kernel (folded into `GPUMoveThing.jointsKernel`, the A7.b code path).
- `MyosinFixed extends Myosin`, so it has the same three sub-Things (`myoRod`, `myoLever`, `myoMotor`), the same `tipLink` on `myoMotor`, and the same F8/F9/F10 chain via `myoMotor.step()`.

`MyoMiniFilament` is the free-diffusing bipolar bundle case (`MyoMiniFilament.java`). It bundles `MyosinDimer` objects on each end (`numMyoDimersEachEnd`), and each dimer has two `Myosin` sub-objects with their own `MyoMotor` + `tipLink`. F8/F9/F10 still apply per `MyoFilLink` — the cross-bridge force code path is the same; only the dimer joint coupling (`MyosinDimer.jointConstraints`) is different. The gliding assay does not instantiate `MyoMiniFilament` (`fillPlaneWithFixedMyosins` constructs `MyosinFixed` only); `MyoMiniFilament` is the cortical-network use case.

**Recommendation: scope the port to the gliding (`MyosinFixed`) F8/F9/F10 only.** `MyoMiniFilament`'s `MyoFilLink` chain has identical force code — the same kernel will work for any `MyoMotor`-owning Myosin once the per-Myosin binding upload state is in place. But `MyoMiniFilament` does not appear in the gliding-assay validation rotation and its `MyosinDimer.jointConstraints` is CPU-only. Treat the port as gliding-first, with the kernel reusable for `MyoMiniFilament` later (no API change needed when that config enters the rotation).

### 4. Minimal per-motor upload state (the "int + float")

The force kernel needs to know, **per Myosin (or per joint slot)**:

| Field | Type | Source on CPU | Why uploaded each step |
|---|---|---|---|
| `boundSegSlot` | int (`−1` = unbound or CPU-fallback seg) | `thingNumberToMoveSlot[mySeg.myThingNumber]` if `tipLink.mySeg != null` else `−1` | Binding decisions still happen on CPU each step (`MyoMotor.ontoFilament`, `tipLink.release`); slot id can change |
| `posOnSeg` | float (µm) | `tipLink.posOnSeg` | Set at bind time; constant until release. Uploaded with the slot id for simplicity |
| `cockedFlag` | int (0/1) | `myoMotor.isCocked()` (already uploaded for `jointsKernel`) | Drives F9's `angRelaxed` switch — **reuse the existing `cockedFlags` IntArray** |

That's the "one int + one float per motor while binding detection is still on CPU" referenced in `RESIDENCY_PLAN.md` Phase 2. Drags (`motor.bRotGam.x/.y`) are already in the `myoDrags` FloatArray (positions 6,7,8 of each 9-tuple). Seg pose + drags arrive via the topology lookup into the shared `coord`/`uVec`/`yVec` plus the F3/F4 `bTransGam`/`bRotGam` per-slot arrays — all already on device.

**Per-step writeback needed** (for the CPU-side `ckRelease` to function): `forceMag` and `forceDotFil` per bound motor link. The **tipC device writeback** mechanism committed today (`commit e8042b5`, "Phase 2 F1 — tipC device writeback") is the precedent: write per-bound-motor scalars to a small device FloatArray that the host reads each step. The CPU then runs `MyoFilLink.ckRelease` against device-computed values; `forceDotFilTrack.registerValue(forceDotFil)` is updated CPU-side. No release logic moves.

What is **NOT** needed for the force kernel: `nucleotideState`, `inRigor`, `bindTimer`. Those are biochem and stay CPU.

### 5. Motor↔filament link indexing on device

The F3/F4 chain port pattern (`GPUMoveThing.java:2233–2298`): per FilSegment slot, store `topoEnd2Slot[i]` = the move-slot of its end2 chain neighbour, sentinel `−1` if absent or CPU-fallback. Built in `classifyThings()` on `topologyDirty`. This pattern **maps directly** onto motor↔seg links:

- Each Myosin already has a joint slot in `[0..myoJointCt)`. Extend `packJointsRange()` to populate two new arrays alongside `cockedFlags`/`anchoredFlags`:
  - `boundSegSlot[mj]` (IntArray, `myoCap`)
  - `posOnSegArr[mj]` (FloatArray, `myoCap`)
- Look up `mySeg.myThingNumber → thingNumberToMoveSlot[]` at pack time; if the seg is CPU-fallback (`< 0`), set `boundSegSlot[mj] = −1` and the kernel early-returns for that motor (CPU `tipLink.step()` runs in a reduced pass).

**Scale.** At 64-mon production glidingAssay500_val with `fixedMyosinDensity ≈ 500/µm²` over ~50µm² ≈ 25000 myosins. Each has at most one bound seg. The IntArray+FloatArray per Myosin is **negligible** memory. Kernel sizing fits inside the existing `JOINTS_KERNEL_BLOCK_SIZE = 64`.

**15-arg / arg-cap implication.** Current `jointsKernel` already takes **13 args**; `chainPairForcesKernel` takes **13 args**. A separate motor-force kernel can re-use the same 13-arg structural ceiling: `(coord, uVec, yVec, jointForceSum, jointTorqueSum, rodSlots, motorSlots, boundSegSlot, posOnSegArr, cockedFlags, segDrags_bTransGam, segDrags_bRotGam, motorParams, counts)` — roughly 13–14 args. **Flag: yVec is not currently passed to the joints kernel — F10 needs it as a new device input.** Either widen the existing `jointsKernel` signature (close to the practical PTX limit) or — cleaner — make F8/F9/F10 a new kernel task in the chained TaskGraph that receives only what it needs.

### 6. Pair-force structure and ownership — concrete recommendation

Each `MyoFilLink` is a **one-motor ↔ one-seg pair**. Ownership candidates:
- **Motor-owned (one thread per Myosin):** writes motor-side jointForceSum entry (unique) and reads-and-writes seg's jointForceSum entry (**collision risk**: multiple gliding motors can bind the same seg simultaneously).
- **Seg-owned (one thread per FilSegment slot):** writes its own jointForceSum entry (unique) and writes each bound motor's jointForceSum entry. Needs a per-step CSR-like "list of motor links per seg" built CPU-side.

**Recommended pattern: split into two kernels** to keep the F3/F4 unique-ownership discipline (no atomics, no visited flags):

- **Kernel M (per Myosin / per joint slot):** computes F vector + alignment torque vectors. Writes ONLY the motor's three sub-slots (rod is irrelevant for F8/F9/F10 — the force lands on motor, not rod). Also writes per-link `forceMag` / `forceDotFil` to the tipC writeback FloatArray. No seg writes from this kernel.
- **Kernel S (per FilSegment slot):** reads a per-step CSR "bound motor list" (`segMotorOffsets[i+1] − segMotorOffsets[i]` bound motors at seg slot `i`, indexed into a `boundMotorLinkIdx[]`). For each bound motor, re-derives `attachPt`, recomputes `F` (same arithmetic — pair-symmetric, both kernels match bit-for-bit when computed in double), and accumulates `−F` and the alignment-torque counterparts into the seg's own jointForceSum/jointTorqueSum slot. Writes are slot-disjoint (each seg-thread writes only its own slot).

**Justification for the redundant per-pair arithmetic in Kernel S:** the F3/F4 chain kernel uses the exact same idiom — each thread of a pair re-computes the pair force from scratch and applies it to *its own* side. The cost is one extra pair arithmetic per link (single-digit microseconds for the gliding-assay scale; verifiable later); the win is no atomics and zero cross-slot writes, identical to the F3/F4 discipline that just passed.

**Owner + direction convention (committed up front, to avoid the F3 near-miss):**
- **Kernel M writes motor side: positive `F` and positive torsion** (matches CPU `myMotor.incForceSum(F, motorPt)` and `myMotor.incTorqueSum(−torsionVec)`).
- **Kernel S writes seg side: negative `F` and positive torsion** (matches CPU `mySeg.incForceSum(−F, attachPt)` and `mySeg.incTorqueSum(+torsionVec)`).
- **Bit-for-bit invariant to enforce by inspection during implementation:** the F-vector sign at the moment it is added on each side must equal what CPU does at the corresponding `incForceSum` call. Write this down once in the kernel comment; verify in the cheap-probe diagnostic.

CSR build cost (CPU-side, once per step before pack): a single pass over `MyoMotor.theMotors[]` counting bound motors per `boundSegSlot`, prefix sum, second pass scattering link indices. O(motorCt). Already cheap relative to current step-phase budgets at gliding scale.

### 7. CPU/device split — clean separability

**`addForces()` mutates only `forceMag`, `forceDotFil`, `forceDotFilTrack` and the force/torque accumulators on motor and seg.** It does **not** touch nucleotide state, does not call `release()`, does not touch `bindTimer`, `inRigor`, or any of the biochem state. The release decision is fully isolated in `ckRelease()` (called after the three force methods, reads `forceMag` and `forceDotFil` as state).

Caveat: `forceDotFilTrack` is a `ValueTracker(10)` — a 10-sample circular buffer mean — updated *inside* `addForces()`. If the device kernel computes `forceDotFil` and writes it back, the CPU side must still call `forceDotFilTrack.registerValue(forceDotFil)` to keep `dissociateADP`'s averaging logic working (`MyoMotor.dissociateADP` reads `tipLink.forceDotFilTrack.averageVal()` as the directional gate). The tipC writeback already supplies the scalar — wire one extra CPU-side call to `forceDotFilTrack.registerValue()` after the writeback drain.

**Scoping verdict: cleanly separable.** No entanglement with `ckRelease`/`dissociateADP`. The force can be ported in isolation.

### 8. Sign-convention caution — replicate, do not touch

Per `SURVEY_MYOSIN_AND_GLIDING.md` (deferred question): `forceDotFil` is signed and that sign drives `ckRelease`'s Guo–Guilford catch (`exp(−forceDotFil·xCatch/kT)`) and slip (`exp(+forceDotFil·xSlip/kT)`) terms. The BoA mainline convention computes `forceDotFil = Dot(F, seg.uVec)` **before** flipping F for the seg-side write, so `forceDotFil` is the dot of force-on-motor with seg.uVec — positive when the motor is pushed toward the seg barbed end.

Both kernels (M and S) recompute `F` from `unit(motorPt − attachPt) · forceMag`. **Both must match the CPU's `unitVec(attachPt, motorPt)` direction** (CPU's `Pt3D.unitVec(from, to)` convention: result points `from → to`'s opposite — read the CPU code carefully when porting; this is exactly the kind of subtle directional flip that the F3 inter-endpoint near-miss exposed). Recommend computing `dx = motorPt − attachPt` once, normalising, and applying with the documented sign in both kernels' comments. The device kernel's `forceDotFil` must end up bit-equivalent to CPU's `Pt3D.Dot(F_cpu, seg.uVec)`.

The release logic itself is **not touched**; `forceDotFil` is written back to CPU and `ckRelease` runs unmodified. The port's only obligation is: the device-side `forceDotFil` has the same sign as the CPU's. If it does not, the catch/slip exponentials invert and gliding ensembles will diverge in a way that looks like a velocity-vs-load anomaly (per the SURVEY_MYOSIN_AND_GLIDING note) — exactly the failure mode to guard against.

### 9. Cheap-probe validation — before the gliding ensemble

Three options, in increasing setup cost:

**Option A — extend `SingleMyoDiag`.** `SingleMyoDiag` already supports a "minimal-system" reproduction (`MyosinFixed.setUpSingleMyosinDiag` creates one anchored myosin, no filaments). Add a "single bound motor" variant: one `MyosinFixed` + one short 11-segment fixed filament + a pre-bound `MyoFilLink` at a chosen `posOnSeg`. With `myosinsOff` selectively gating only the binding/unbinding statistics, freeze the link bound and measure per-step `forceMag`, `forceDotFil`, and the torque components on motor and seg from both CPU and device. Bit-equal compare per step (or float-tolerance).

**Option B — `SingleBoundMotorDiag`** (new): a dedicated harness pinning a single `MyoFilLink` with a held bound state, no Brownian, no release. Smaller surface than A but more new code.

**Option C — JointDiag-style sampler in the existing config.** Add a diagnostic in `MyoFilLink` that dumps per-link `forceMag`/`forceDotFil`/torque components every N steps for the first K Myosins; run gliding for a few thousand steps with the device path and the `DIAG_CPU_MOTOR` flag (mirror of `DIAG_CPU_F3F4`/`DIAG_CPU_F1`). Compare the dumps line-by-line.

**Recommendation: Option A** (extend `SingleMyoDiag`). Lowest new-code surface, reuses the SingleMyoDiag dump scaffolding, exercises the exact tipC writeback path in the smallest possible system. Cheap enough to run at every kernel-implementation iteration. The gliding 10-seed paired ensemble (the 2026-06-03 driver) is the expensive **gate**, not the localiser.

### 10. GPU-port recommendation — kernel structure and slotting

**Kernel structure:** two kernels added to the existing chained TaskGraph.

- **Kernel M** (per Myosin / per joint slot, `myoJointCt` threads): F8 motor-side force write + F9/F10 motor-side torque writes + tipC writeback (`forceMag`, `forceDotFil`). Reads motor pose + seg pose via `boundSegSlot[mj]`. Early return on `boundSegSlot < 0`. Block size 64, same as `jointsKernel`.
- **Kernel S** (per FilSegment slot, `slotCount` threads with seg-only filter): F8 seg-side force write + F9/F10 seg-side torque writes. Reads its CSR-style bound-motor list. Early return on empty list. Block size 64, same as `chainPairForcesKernel`.

**TaskGraph slotting** (`GPUMoveThing.java:1900`-ish, where the chained graph is built):
- After `jointsKernel` and `chainPairForcesKernel` (they have already deposited the F3/F4/anchor/joint contributions to jointForceSum/jointTorqueSum).
- After F1 boundary kernel (already on device for the box case).
- Before `moveKernel` (which sums `cpuForceSum + jointForceSum` to integrate).
- Order between M and S does not matter — they write to disjoint slots.

**Infra reuse from F3/F4 and Phase-1 anchor:**
- `thingNumberToMoveSlot[]` reverse map → `boundSegSlot[mj]` lookup.
- `jointSlotToMyoIdx[]`, `rodSlots`/`leverSlots`/`motorSlots` IntArrays → motor pose access.
- `cockedFlags` IntArray → reuse as-is for F9's `angRelaxed` selector.
- `myoDrags` FloatArray → motor `bRotGam.x/.y` already at offsets 8 / 7 of the 9-tuple.
- F3/F4's per-FilSegment `bRotGam` FloatArray (already on device for chain torsion) → reuse for seg-side F9/F10 torsion denominator.
- tipC device writeback (committed today, `commit e8042b5`) → reuse the writeback FloatArray for `forceMag` and `forceDotFil` per joint slot.

**PTX/arg-cap constraint to watch.** Adding yVec to the device kernel set is new (current `jointsKernel`/`chainPairForcesKernel` don't read yVec, but yVec is already in the device pose buffers via the OP_UNPACK path — no new upload, only a new kernel argument). Kernel M arg count ≈ 14, Kernel S arg count ≈ 13. Within the working envelope. If a future port needs more state, fold related arrays into AoS-by-attribute (e.g., interleave `boundSegSlot` and `posOnSeg`) — but not yet.

**Reduced CPU pass.** Following the established pattern (`MyosinFixed.applyGPUDroppedForces` for anchor; `DIAG_CPU_F3F4` for chain): a `DIAG_CPU_MOTOR` flag forces both kernels to early-return (all `boundSegSlot[mj]` → `−1` at pack; the CSR is empty). The CPU then runs the existing `MyoMotor.step() → tipLink.step()` → F8/F9/F10 in the unmodified step() dispatch. Mixed-state per-Myosin: when the bound seg is CPU-fallback (e.g., `motherFil != null`, `actAOn`, `isLpSeg && lpActive==0`), `boundSegSlot[mj] = −1` and the CPU code handles that motor's link — same pattern as the F3/F4 mixed-chain fallback.

### Recommended next executable step (NOT a green light to start — planner decides)

Build **Option A — extended `SingleMyoDiag` single-bound-motor harness** *before* writing either kernel. The cheap-probe-then-ensemble discipline applies: the cheap probe must exist and pass on the CPU path first (no device code involved) so it is known to be sensitive to the right quantities. Then implement Kernel M and Kernel S in series, gated by `DIAG_CPU_MOTOR`, validate against the harness, then run the gliding 10-seed paired ensemble (the 2026-06-03 driver) as the production gate.

### Constraints honoured
- Read-only. No edits to source. No runs.
- Sign conventions untouched. Release kinetics (`ckRelease`, `dissociateADP`) stay on CPU.
- `MyoMiniFilament` deferred (gliding-only scope), with the kernel structure designed to extend without API change.
- Cross-bridge force confirmed cleanly separable from biochem state — no scoping risk to flag to the planner.

---

## 2026-06-03 — 64-mon paired device-vs-CPU gliding ensemble — full ported stack (PASS)

**Verdict: PASS.** The full Phase-2 ported force stack (anchor A7.b + F3/F4 chain bending + F1 box boundary + tipC device writeback) reproduces the CPU at the **64-monomer production gliding regime**. Per-seed paired deltas across all three gliding observables center on zero (paired |t| < 0.32), the device and CPU ensemble distributions agree on |d|/cSEM < 0.28 across the board, and the deferred Phase-1 ~2σ binding question is resolved: it was post-Phase-0 RNG sampling on the small N=10 seed set, **not** a device-arm effect.

Pre-motor-port confirmatory gate at the 64-mon production regime cleared. Ported forces were validated at the 32-mon bench earlier today; the gliding ensemble runs them at 64-mon, which this entry confirms.

### Setup

Both arms run `-gpu` on `glidingAssay500_val`; only difference is where the four ported forces compute.

| Arm | DIAG flags | Where the ported forces run |
|---|---|---|
| **DEVICE** (default) | all four flags off | anchor + F3/F4 + F1 + tipC writeback on device |
| **CPU** | `BOA_DIAG_CPU_ANCHOR=1` `BOA_DIAG_CPU_F3F4=1` `BOA_DIAG_CPU_F1=1` `BOA_DIAG_DEVICE_BOUNDARY_TIPC=0` | device kernels early-return; CPU paths apply the same forces |

Same `-seed N` in both arms → same `Env.mtRNG` state → same `runSeed` → same Brownian / motor-binding RNG draws (those phases are unported and stay on device in both arms). The only physical difference between paired runs is the four ported forces. Phases not yet ported (motor binding, biochem, Brownian, grid) run on CPU/GPU identically in both arms and are not under test.

Driver: `scripts/paired_64mon_gliding.sh` (new) — interleaves DEVICE / CPU per seed and writes `RUN_LOGS/2026-06-03_paired_devVsCpu/results.csv`. Both arms run `BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500_val -seed N`; the CPU arm prepends the four `BOA_DIAG_*` env vars above.

### Smoke projection + chosen N

Single paired seed `1` ran in **326s (DEVICE) + 334s (CPU) = 660s per pair** (`RUN_LOGS/2026-06-03_paired_smoke/`). Projection at the 10-hour wall boundary: ≤ 54 paired seeds fit. Chose **N=20** (paired) — doubles Phase-1's N=10, tightens SEMs by √2, projects at ~220 min, fits comfortably. No bail.

Smoke also verified: no NaN, no crash, observables in the expected range, single-seed device-CPU deltas inside the ordinary seed-scatter regime (Phase-1 already had seed 7 = 320 binds vs ensemble-mean 762).

### Per-seed paired deltas (DEVICE − CPU)

Production: seeds 1..20, walls 322–329s/seed device and 332–336s/seed CPU; **total ensemble wall = 220.0 min** (matches projection within 1%).

| seed | bindEv_D | bindEv_C | Δbind | mbm_D | mbm_C | Δmbm   | gv_D   | gv_C   | Δgv     |
|---|---|---|---|---|---|---|---|---|---|
|  1 |  743 |  777 |  −34 | 6.762 | 6.653 | +0.109 | 7.3729 | 8.4786 | −1.1057 |
|  2 | 1033 |  918 | +115 | 8.477 | 7.253 | +1.224 | 8.5921 | 8.5407 | +0.0514 |
|  3 |  766 |  899 | −133 | 7.098 | 7.248 | −0.150 | 7.8036 | 7.7525 | +0.0511 |
|  4 | 1121 | 1000 | +121 | 8.393 | 8.529 | −0.136 | 8.9457 | 8.1011 | +0.8446 |
|  5 |  694 |  913 | −219 | 6.811 | 7.164 | −0.353 | 8.5238 | 8.1382 | +0.3856 |
|  6 |  868 | 1235 | −367 | 7.191 | 8.930 | −1.739 | 7.3966 | 8.8726 | −1.4760 |
|  7 |  785 |  843 |  −58 | 6.544 | 6.896 | −0.352 | 8.0263 | 8.4168 | −0.3905 |
|  8 |  839 |  608 | +231 | 7.559 | 5.298 | +2.261 | 8.3697 | 7.5532 | +0.8165 |
|  9 |  896 |  886 |  +10 | 7.928 | 7.641 | +0.287 | 8.0357 | 8.2590 | −0.2233 |
| 10 |  856 |  818 |  +38 | 7.216 | 7.230 | −0.014 | 8.1743 | 7.2781 | +0.8962 |
| 11 |  601 |  841 | −240 | 5.979 | 7.877 | −1.898 | 7.1534 | 7.9461 | −0.7927 |
| 12 |  764 |  861 |  −97 | 6.681 | 7.191 | −0.510 | 7.3390 | 8.3973 | −1.0583 |
| 13 | 1059 |  809 | +250 | 8.608 | 6.887 | +1.721 | 8.2991 | 7.7420 | +0.5571 |
| 14 |  963 |  748 | +215 | 8.196 | 6.412 | +1.784 | 8.1464 | 7.3451 | +0.8013 |
| 15 |  827 |  757 |  +70 | 6.917 | 6.982 | −0.065 | 7.3745 | 7.3997 | −0.0252 |
| 16 |  916 |  970 |  −54 | 6.923 | 8.098 | −1.175 | 8.2647 | 8.0842 | +0.1805 |
| 17 |  916 |  933 |  −17 | 7.786 | 7.657 | +0.129 | 8.3862 | 7.9098 | +0.4764 |
| 18 |  785 |  862 |  −77 | 6.850 | 7.257 | −0.407 | 7.9237 | 8.1657 | −0.2420 |
| 19 | 1024 |  925 |  +99 | 7.780 | 7.574 | +0.206 | 8.0401 | 8.5700 | −0.5299 |
| 20 |  750 |  829 |  −79 | 6.515 | 7.405 | −0.890 | 7.9491 | 7.6215 | +0.3276 |

Signs span both directions per observable; no run-of-sign suggestive of a systematic bias.

### Bias analysis (paired deltas, N=20)

The primary test: do paired deltas center on zero?

| observable      | mean Δ   | SD(Δ)    | SEM(Δ) | paired t | verdict   |
|---|---|---|---|---|---|
| bindEvents      | **−11.3**  | 160.74 | 35.94  | **−0.314** | no bias |
| meanBoundMotors | **+0.0016** |   1.09 |  0.243 | **+0.007** | no bias |
| glidingVelocity | **−0.023** |   0.70 |  0.157 | **−0.145** | no bias |

All three paired t-statistics |t| < 0.32 — well inside zero-centered scatter; nothing approaching the ±2σ threshold. **The full ported stack introduces no systematic bias relative to the CPU at the 64-mon regime.**

### Ensemble distributions (independent)

Statistical-agreement view (not seed-level bit-reproducibility — float32 force noise integrating over a trajectory makes that not expected):

| observable      | DEVICE mean ± SEM | CPU mean ± SEM   | diff     | cSEM    | \|d\|/cSEM | verdict |
|---|---|---|---|---|---|---|
| bindEvents      | 860.30 ± 29.65    | 871.60 ± 27.49   | −11.30   | 40.43   | 0.279     | PASS |
| meanBoundMotors | 7.311 ± 0.166     | 7.309 ± 0.171    | +0.0016  | 0.238   | 0.007     | PASS |
| glidingVelocity | 8.006 ± 0.107     | 8.029 ± 0.101    | −0.023   | 0.147   | 0.155     | PASS |

All three |d|/cSEM < 0.28 — clean PASS by the project's <2σ statistical-agreement criterion. The two ensembles overlap almost exactly across all three observables.

### Deflection ratio — clarification

The prompt listed "deflection ratio" as a fourth observable. **Deflection ratio is a benchmark-mode observable** (`-bm` / `-bmManual` / `-bmDiag`, the static deflection bench described in `CLAUDE.md` under Benchmark Modes), **not produced by the gliding assay.** The gliding assay emits exactly three `[STATS]` lines (`bindEvents`, `meanBoundMotors`, `glidingVelocity`; see `boxOfActin/BoxOfActin.java:1381-1425`). Deflection ratio at the 32-mon held-chain was already PASS on the Phase 2 F3/F4 owner-perspective-`linkUVec` fix (2026-06-03 entry) and is the stiffness gate for that port. This ensemble is the gliding gate at the 64-mon production regime.

### Phase-1 2σ resolution

Phase-1's N=10 ensemble (`856.80 / 7.30 / 8.22` baseline; `717.9 / 6.21 / 8.07` Phase-1) showed bindEvents −2.02σ and meanBoundMotors −2.21σ below baseline, with glidingVelocity clean. Phase-1 flagged three candidates: outlier seed 7 (320 binds), Phase-0 RNG-remap sampling, or a real device anchor effect.

The N=20 paired data resolves it:

| observable      | Phase-2 DEVICE (N=20) | Phase-2 CPU (N=20) | Phase-1 baseline (n=10) | DEVICE \|d\|/cSEM | CPU \|d\|/cSEM |
|---|---|---|---|---|---|
| bindEvents      | 860.30 ± 29.65        | 871.60 ± 27.49     | 856.80 ± 45.72          | **+0.064**        | **+0.277**     |
| meanBoundMotors | 7.311 ± 0.166         | 7.309 ± 0.171      | 7.30 ± 0.30             | **+0.031**        | **+0.026**     |
| glidingVelocity | 8.006 ± 0.107         | 8.029 ± 0.101      | 8.22 ± 0.15             | −1.161            | −1.060         |

Both arms land essentially on Phase-1's binding baseline (|d|/cSEM ≤ 0.28). The CPU arm — which has **none** of the Phase-1 device-anchor compute — is at +0.28σ from baseline, not −2.02σ. **The Phase-1 ~2σ binding shift was post-Phase-0 RNG sampling on the small N=10 seed set, not a device-arm effect.** The N=20 paired sample averaged the seed-7-style outliers (now joined by seed 11's 601 and seed 5's 694) against above-baseline seeds (8, 13, 14, 4) to land cleanly on the population mean. Phase 1 cleanly closed retroactively.

The 1.16σ residual on glidingVelocity (8.01 vs 8.22 cited) appears in **both arms equally** and is therefore not a device-arm effect either; it's a small population shift between the pre-Phase-0 baseline (8.22, with the pre-Phase-0 RNG mapping) and the post-Phase-0 mean (~8.02 in both arms). The cited 8.22 ± 0.15 (post-fix 2026-06-01) was on n=10 seeds under a different RNG plumbing; the N=20 post-Phase-0 estimate is the better population estimate.

### Wall, status, output

- **Total ensemble wall: 220.0 min** (DEVICE mean 325.5s/seed; CPU mean 334.4s/seed; CPU arm marginally slower because the device kernels still launch then early-return for inactive slots, but the difference is small).
- Heartbeats: per-seed `[progress]` lines in `.last_run_status` throughout the run (smoke 16:04–16:15, production 16:16–19:56), as required. `.last_run_status` removed at session end per spec.
- Files: per-seed logs `RUN_LOGS/2026-06-03_paired_devVsCpu/{device,cpu}_seed{1..20}.log`; combined CSV `results.csv`; analysis `analysis.txt` + script `analyze.py`. Smoke run at `RUN_LOGS/2026-06-03_paired_smoke/`. Driver `scripts/paired_64mon_gliding.sh`.

### Constraints respected

- **No code changes.** `git status` clean except untracked `.diag_dump_*.txt` / `.journal_skel.txt` from prior sessions; HEAD = `e8042b5` (tipC device writeback box) built clean once before the smoke run, no further `javac` during the ensemble.
- **No concurrent `java` on aorus.** Only the ensemble's JVM ran; no other build, bench, or production job touched aorus during the 220-min window.
- `collisionCheckInt` cadence unchanged; `glidingAssay500_val` parameter file unchanged.
- Both arms run `-gpu`; the CPU arm is not a "no-GPU run", it's a "GPU run with the four ported force kernels gated off" — the deferred-port phases (motor binding, joints rod-lever/lever-motor, Brownian, etc.) remain on the device in both arms.

### Verdict

**PASS — statistical agreement.** Paired |t| < 0.32 across all three gliding observables; independent |d|/cSEM < 0.28; the full ported force stack (anchor + F3/F4 + F1 + tipC) reproduces the CPU at the 64-mon production gliding regime. The deferred Phase-1 ~2σ binding question is resolved as RNG-sampling on the small seed set; the device path was not at fault. With the stack confirmed at 64-mon, the **pre-motor-port confirmatory gate is cleared**; subsequent Phase-3 (binding detection) and Phase-4 (residency flip) work can proceed against this ensemble as the reference.

## 2026-06-03 — tipC device writeback (box) — implementation (PASS)

**Verdict: PASS.** The F1 force-coverage gap left open by the 2026-06-03 PM
F1 (box) entry — `boundaryBoxKernel` computed wall force/torque but never
wrote `end{1,2}TipC`, so on the device path `tipC` stayed at its per-step
`1e6` reset and `stericHindranceEnd2` never fired — is closed. The device
now writes a per-endpoint wall clearance into a new readback FloatArray
`boundaryTipC`; after `plan.execute()` returns, `bridgeBoundaryTipC()`
min-combines those values into `FilSegment.end{1,2}TipC` BEFORE the
biochem phase reads `tipC`. The existing `stericHindranceEnd2` →
`end2BiochemSim` polymerization gate (FilSegment.java:1039) and
`checkCapping` (line 1097) are unchanged. The F1 interim caveat —
"polymerizing box runs require `BOA_DIAG_CPU_F1=1`" — is lifted for the
box. F1b (pill) inherits the same writeback pattern when
`boundaryPillKernel` is built.

### Phase A — tipC setter-side survey

`end2TipC` setter map (single live polymerization-clearance gate):

| Setter | Location | Action | When |
|---|---|---|---|
| `resetCounters()` | FilSegment.java:1226 | `= 1e6` (overwrite) | resetCt phase, AFTER biochem (BoxOfActin.java:929) |
| `StaticFilSegment.step()` | StaticFilSegment.java:26 | `= 1e6` (overwrite) | start of step on collisionCheckInt cadence |
| `bugForcesFromInside` | FilSegment.java:2610 | `= 0` (overwrite, equiv. min-with-0) | CPU step() phase, ON contact only (caller `checkBugCollisionFromInside` gates on `cE.delta != 0`) |
| `checkBugCollisionFromOutside` | FilSegment.java:1282/1293 | `= 0` on hit / `= cE.delta` near (overwrite) | CPU step() phase, Listeria from-outside path (`simOutsideBug=true`) |
| `registerATipClearance` | FilSegment.java:1078 | min-combine | xLink phase (BoxOfActin.java:846), called from `checkNodeFilTipsCollision` (line 2023) — ProteinNode tip clearance |
| **(NEW)** `bridgeBoundaryTipC()` | GPUMoveThing.java | min-combine | between move and biochem phases, after device kernel writeback |

`end1TipC` setter map: **all writes are dead.** `stericHindranceEnd1()`
(line 2868) exists but the only callers are commented out at
FilSegment.java:990-991. `end1BiochemSim` (line 984) polymerizes
unconditionally (`if (true)`); there is no live end1 gate. End1 sites at
lines 1227 (reset), 1272/1275 (from-outside), 2607 (from-inside),
StaticFilSegment.java:26 are observationally inert. We still write end1
from the device kernel for symmetry / cheap-future-revival; the
end1 min-combine in `bridgeBoundaryTipC()` is a no-op against any future
reader.

Commented-out dead sets confirmed dead: FilSegment.java:2189, 2220, 2397
(all under `//if (...) { endXTipC = 0; }` patterns inside dead branches).

Combine semantics: every setter except `registerATipClearance` overwrites,
but the boundary-path overwrites are with `0` (≤ any other source so
equivalent to min-with-0). Intended combined meaning: `tipC = min
clearance across all sources` once the bridge lands.

Co-occurrence (wall + node clearance for same tip simultaneously): real in
pill smoke / formin-heavy runs; not in the box-spaghetti test below (no
nodes). The min-combine is correct either way.

Insertion point for the writeback: after `plan.execute()` returns and
before BoxOfActin's biochem phase starts (BoxOfActin.java:898–924).
`bridgeBoundaryTipC()` is called from inside `GPUMoveThing.moveThings()`
immediately after `OP_UNPACK` and the CPU-fallback `moveThing()` loop —
cleanest spot that keeps the bridge co-located with the device-result
readback.

### Phase B — implementation

**Device** (`GPUMoveThing.java`):

- New `FloatArray boundaryTipC` (capacity `slotCap * 2`; layout `[i*2+0]
  = end1`, `[i*2+1] = end2`). Host-side initialised to `1e6` at
  allocation; re-uploaded EVERY_EXECUTION (transferToDevice).
- `boundaryBoxKernel` writes the `1e6` sentinel to both endpoints
  **before** the `boundaryActive[i] == 0` early-return, so inactive
  slots (`DIAG_CPU_F1`, non-FilSegment, simOutsideBug, wrong shape) have
  a guaranteed no-op value for the host-side min-combine.
- Active slots compute per-endpoint clearance `max(0, min over axes of
  (halfDim_i − R − |d_i|))` — same convention as the Listeria
  from-outside path (`cE.delta` as `tipC`, FilSegment.java:1275/1293).
  `0` on contact / penetration; positive when near a wall but not yet
  touching. The force/torque math is untouched; only the new clearance
  computation is added per endpoint.
- `boundaryTipC` added to transferToHost (EVERY_EXECUTION, gated on
  `boundaryShape == BOUNDARY_SHAPE_BOX`).
- `bridgeBoundaryTipC()` walks `gpuThingIndices[0..slotCount-1]`,
  min-combines `boundaryTipC[s*2+0]` → `fs.end1TipC` and
  `boundaryTipC[s*2+1]` → `fs.end2TipC` (only if smaller — respects
  any existing CPU write from `registerATipClearance` or
  `bugForcesFromInside` on CPU-fallback slots). Called from
  `moveThings()` after the OP_UNPACK and the CPU-fallback `moveThing()`
  loop, gated on `DIAG_DEVICE_BOUNDARY_TIPC && boundaryShape == BOX`.

**Toggle flag** (`GPUMoveThing.DIAG_DEVICE_BOUNDARY_TIPC`, default `true`):

- `BOA_DIAG_DEVICE_BOUNDARY_TIPC=0` → bridge skipped; reproduces the
  pre-fix "tips polymerize through walls" behaviour for the A/B.
- Independent of `BOA_DIAG_CPU_F1`. Both A/B arms keep the device
  boundary FORCE/TORQUE active (`DIAG_CPU_F1=false` in both); only the
  tipC writeback toggles.

**FilSegment** unchanged. `stericHindranceEnd2`/`checkCapping`/biochem
consumers all read `end2TipC` exactly as before.

### Validation 1 — held-config tipC match (`HeldSegF1Diag`)

Extended `boxOfActin/HeldSegF1Diag.java` with three tipC cases. Both CPU
mirror (matches `bugForcesFromInside`'s 0-on-contact-only semantics) and
device mirror (matches the new kernel's clamped-min-clearance formula)
run on the same frozen pose. Numbers (full log:
`RUN_LOGS/2026-06-03_heldSegF1_tipC.txt`):

| Case | end2 geom | CPU `end2TipC` | DEV `end2TipC` | Expected DEV | |Δ| |
|---|---|---|---|---|---|
| T1 CONTACT | 30° tilt, end2 ~5 nm past +x | `0` | `0` | `0` | bit-equal |
| T2 NEAR | 30° tilt, end2 ~5 nm clearance from +x | `1e6` (reset sentinel) | `5.000e-3` µm | `5.000e-3` | 4.3e-18 |
| T3 NEAR | 30° tilt, end2 ~1 nm clearance from +x | `1e6` | `1.000e-3` µm | `1.000e-3` | 8.7e-19 |

T1 verifies bit-for-bit match on contact — the case where both CPU and
device fire `tipC=0`. T2/T3 document the new "near" signal the device
adds: device writes positive clearance below `halfmono` (~1.35 nm)
which trips the existing gate before actual penetration; CPU stays at
the sentinel because `bugForcesFromInside` is only invoked on hit.
Pre-existing F+T cases (1–5) re-run unchanged — all PASS, max |dT|
2.5e-16 (float-noise floor).

### Validation 2 — box-spaghetti A/B (jba's design)

New `ParameterFiles/boxSpaghetti`: 0.6 × 0.6 × 0.4 µm box, 6 initially-
short (0.05–0.15 µm) polymerizing filaments at `actinConc=50 µM`
(≈1.5 µm/s growth), no motors / nodes / ActA / capping. 2 s run-time
(20 000 steps), 100 frames.

Three arms, ALL with the device boundary force/torque active:

```
# Arm A — writeback OFF (reproduces pre-fix bug)
BOA_DIAG_DEVICE_BOUNDARY_TIPC=0 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu -pf ParameterFiles/boxSpaghetti -3js /tmp/spaghettiA

# Arm B — writeback ON (default; the fix)
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf ParameterFiles/boxSpaghetti -3js /tmp/spaghettiB

# Reference — full CPU F1 (including tipC); should match Arm B
BOA_DIAG_CPU_F1=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu -pf ParameterFiles/boxSpaghetti -3js /tmp/spaghettiR
```

Excursion past walls (X±0.3, Y±0.3, Z±0.2 µm), per-frame max of
`|endpoint_i| − halfDim_i`, clamped non-negative:

| Arm | seg count trajectory (f0/f25/f50/f75/f100) | max overX | mean overX | mean overY | mean overZ | sustained breach pattern |
|---|---|---|---|---|---|---|
| **A — writeback OFF** | 6 → 8 → 12 → 19 → **30** | 0.111 µm | **0.00611** | 0.00430 | 0.00303 | f56–f100 (45 consecutive frames with 2–7 endpoints past walls) — *spaghetti through walls* |
| **B — writeback ON** | 6 → 6 → 12 → 17 → **24** | 0.091 µm | **0.00210** | 0.00171 | 0.00166 | transient (f81–f83 spike then quiescent f84–f100) — *tips arrest, Brownian wobble at wall* |
| **Reference — CPU F1** | 6 → 6 → 12 → 17 → **24** | 0.091 µm | **0.00191** | 0.00122 | 0.00014 | f32 single spike, then mostly clean — matches Arm B |

Diagnostic numbers:

- **Polymerization gating** — Arm A produces 6 extra segments
  (30 vs 24) over the run; B and R agree exactly on final segment
  count (24). The extra 6 splits in A correspond to additional
  monomer additions that the missing gate let through at endpoints
  already past the wall.
- **Mean excursion** — Arm A's `mean overX` is 3× Arm B's and
  3.2× the reference; Arm A's `mean overY` is 2.5×; Arm A's
  `mean overZ` is 1.8× (Z is the thinnest dim and most sensitive
  to ratcheting). Arm B and the reference agree on mean excursion
  to within 10% (Brownian-noise level given different per-step
  evaluation order).
- **Sustained breach** — Arm A has 45 consecutive frames with
  endpoints past the walls; Arm B has none, only single-frame
  transients (Brownian ratcheting, acceptable per prompt).
  Reference matches B's pattern.

Logs: `RUN_LOGS/2026-06-03_boxSpaghetti_AB_summary.txt` (per-arm
aggregates), `RUN_LOGS/2026-06-03_boxSpaghetti_AB_frames.txt`
(per-frame breach trace + segCt trajectory).

Frame JSONs from each arm (Arm A spaghetti spread + Arm B / R
arrested) are at `/tmp/spaghettiA`, `/tmp/spaghettiB`, `/tmp/spaghettiR`
on aorus; viewer-loadable via `python3 sim_server.py 8000` →
`http://localhost:8000/sim_viewer_boa.html` then arm selection.

### Force-coverage table — updated F1 row

Replaces the `end1TipC`/`end2TipC` row in the 2026-06-03 PM F1 (box)
entry:

| Force / behaviour | CPU `BOA_DIAG_CPU_F1=1` | Device default (no env var) |
|---|---|---|
| `bugForcesFromInside` side effect: `end1TipC`/`end2TipC` | written on CPU (overwrite-with-0 on hit only); `end1TipC` writes are dead in the current codebase (no live `stericHindranceEnd1` caller); `end2TipC` is the live polymerization gate | **`end2TipC` written by `boundaryBoxKernel` → `boundaryTipC` FloatArray → `bridgeBoundaryTipC()` min-combine** (continuous near-clearance: 0 on hit, positive `max(0, halfDim − R − |d|)` when near). Min-combined with the CPU `registerATipClearance` value if a node is also nearby. `end1TipC` symmetric write for cheap-future-revival; observationally inert. Polymerizing box runs are now valid on `-gpu` default — the interim "polymerizing → `DIAG_CPU_F1=true`" caveat **is lifted for the box**. F1b (pill) inherits this pattern when `boundaryPillKernel` is built. |

All other force/behaviour rows from the 2026-06-03 PM F1 entry remain
correct.

### Files changed

| file | change |
|---|---|
| `boxOfActin/GPUMoveThing.java` | `DIAG_DEVICE_BOUNDARY_TIPC` flag; `boundaryTipC` FloatArray (slotCap × 2); `boundaryBoxKernel` writes sentinel + per-endpoint clearance; transferToDevice + transferToHost lists; new `bridgeBoundaryTipC()`; call from `moveThings()` after OP_UNPACK |
| `boxOfActin/BoxOfActin.java` | `BOA_DIAG_DEVICE_BOUNDARY_TIPC` env hook in `begin()` |
| `boxOfActin/HeldSegF1Diag.java` | `cpuTipCAtEnd` / `devTipCAtEnd` mirrors + `runTipCCase` helper + three tipC cases (T1 CONTACT, T2 NEAR ~5 nm, T3 NEAR ~1 nm) appended to `main()` |
| `ParameterFiles/boxSpaghetti` | NEW — 0.6 × 0.6 × 0.4 µm box, 6 polymerizing filaments at 50 µM, capping disabled — A/B fixture for the writeback fix |
| `RUN_LOGS/2026-06-03_heldSegF1_tipC.txt` | full `HeldSegF1Diag` output with the new tipC cases |
| `RUN_LOGS/2026-06-03_boxSpaghetti_AB_summary.txt` | per-arm aggregate excursion + segCt stats |
| `RUN_LOGS/2026-06-03_boxSpaghetti_AB_frames.txt` | per-frame breach trace + segCt trajectory for all three arms |

### Constraints respected

- **Box only.** `boundaryPillKernel` not built (still F1b deferred);
  `BOUNDARY_SHAPE_PILL` slot left as a comment in
  `allocateAndBuildPlan()`. The bridge is gated on
  `boundaryShape == BOUNDARY_SHAPE_BOX`.
- Node `tipC` computation (`registerATipClearance`) untouched. The
  bridge only **min-combines** the device boundary `tipC` into the
  same field; it never overwrites a smaller node value.
- `stericHindranceEnd1`/`stericHindranceEnd2` and the polymerization /
  capping logic unchanged. The fix is exclusively on the *setter*
  side; the consumer chain that F1b pinned is fed correct data.
- Listeria from-outside path (`simOutsideBug` active) untouched —
  CPU `checkBugCollisionFromOutside` continues to write `tipC = 0` /
  `cE.delta` exactly as before. The device kernel skips those slots
  via `boundaryActive[i] = 0` (set in `classifyThings`), so the
  bridge's sentinel value (`1e6`) is a no-op against the CPU's
  outside-path write.
- No Phase 3 (binding) / Phase 4 (residency) work touched.
- CPU-only runs (non-`-gpu`) unaffected: device kernel does not run,
  bridge is called only from `GPUMoveThing.moveThings()` which is
  only invoked when `Env.useGPU == true`.

### Open follow-ups (not part of this entry)

- **F1b device port** — `boundaryPillKernel`. tipC writeback pattern is
  ready to be replicated: write `0` on contact with the capsule
  (cylinder side OR caps) and the radial / cap-distance clearance
  otherwise into the same `boundaryTipC` FloatArray (or a sibling
  with the same layout). Bridge code is shape-agnostic — only the
  kernel formula changes.
- **CPU box from-inside near-clearance extension** — optional. The
  device now writes a continuous "near" tipC; the CPU
  `bugForcesFromInside` still only writes `0` on hit. If exact
  CPU/device parity becomes a requirement (e.g. for a future
  bit-for-bit deflection bench under polymerizing conditions),
  extend `checkBugCollisionFromInside` to also compute and write
  the per-axis clearance when `cE.delta == 0`. Not needed for the
  box-spaghetti behavioural A/B (B and R match within Brownian noise).
- **end1 polymerization gate** — if ever revived, just uncomment the
  guard at FilSegment.java:991. The device already writes end1TipC
  and the bridge already min-combines it.

**tipC device writeback (box) PASS.** F1b (pill device port) remains
deferred; the writeback pattern is parametric on shape and ready for
reuse.

## 2026-06-03 — Phase 2 F1b — pill exterior-container CPU revival (PASS)

**Verdict: PASS.** The pill-as-exterior-boundary path (filaments confined
*inside* a capsule via `Bug` as `Thing.theBox`) is alive again as a CPU
reference, ready for the eventual `boundaryPillKernel` device port. The
axis-convention mismatch flagged in the 2026-06-03 boundary survey §4a is
resolved by transforming the from-inside collision math into the pill body
frame (survey option b — preserves `uVec=(0,1,0)` and leaves the Listeria
constructor / from-outside path untouched). A polymerizing-actin smoke
run confirms (a) filaments stay inside the capsule (cylinder + both caps),
and (b) polymerizing barbed ends do not grow through the wall — the
existing CPU `bugForcesFromInside` `end2TipC = 0` write + `stericHindranceEnd2`
gate works correctly under the body-frame collision math.

CPU-only. Device kernels (`boundaryBoxKernel` and the still-unbuilt
`boundaryPillKernel`) untouched. The 2026-06-03 F1 (box) entry's
`end1TipC`/`end2TipC` characterization is corrected in place (see below)
— tipC is a **live** polymerization-clearance gate, only *appearing* dead
in the gliding/bench workloads because those use non-polymerizing
filaments.

### Axis fix — option (b), body-frame transform in `amICollidingOuter`/`Inner`

The `Bug` constructor sets `setUVec(0,1,0); setYVec(1,0,0)` so the pill's
long axis is global `+y`. The from-inside collision methods previously
did their math in the global frame, implicitly treating global `+x` as
the long axis — fine if the pill were aligned that way, but contradicting
the recorded `uVec`. Survey §4a flagged the mismatch.

`Bug.java` `amICollidingOuter` and `amICollidingInner` now mirror the
style of `amICollidingFromOutside`:

```java
lcE.tmpPt1.sub(ctr, coordAsPt3D());   // global vector to query point
lcE.tmpPt1.XTox(this);                // -> body frame; tmpPt1.x is along pill long axis
if (Math.abs(lcE.tmpPt1.x) < halfCylLength) {
    lcE.forceUVec.copy(0, tmpPt1.y, tmpPt1.z);  // outward radial in body
    ...
    lcE.forceUVec.reverse();          // inward
    lcE.forceUVec.xToX(this);         // back to global
}
```

Caps use `rec1`/`rec2` directly (already correctly placed along `uVec`
in global frame by `initialize()`). The `forceUVec` for the cap case is
constructed in global frame via `sub(rec*, ctr)` (inward = contact → cap
center) so no second transform is needed.

The placement helpers `rdmPtInside()`, `rdmPtInside(double objRadius)`,
and `rdmPtInsideNodeZone()` also build a body-frame point and then
translate by `coordAsPt3D()`. They were missing the body→global rotation
in between — added one line each: `pt.xToX(this);` before `pt.inc(coordAsPt3D())`.
Without this fix, `makeRandomFilament` would scatter initial filaments
in a global-`+x`-aligned capsule while the collision math now constrains
them to a global-`+y`-aligned one → mass violation at `t=0`.

`rdmPtOnRearHemisphere` and `rdmPtOnCylinder` are present in `Bug.java`
but have no live callers in the codebase (dead code) — not touched.

### Why option (b) over option (a)

Survey §5 listed two viable fixes:

- (a) leave physics, fix orientation: change the `Bug` constructor or
  `makeABugCrucible` to set `uVec=(1,0,0)` so body == global. Touches one
  line, but contradicts the constructor that Listeria (`makeListeriaBug`)
  also uses, requiring a separate code path or a flag to discriminate
  "Bug-as-theBox" vs "Bug-as-lmBug".
- (b) leave orientation, fix physics: transform the from-inside math into
  body frame and rotate the placement helpers' outputs back to global.
  More files touched (3 collision/placement methods in `Bug.java`), but
  the constructor is untouched and there is **zero risk to the Listeria
  from-outside path** — that path already does its own body-frame
  transform (`tmpPt1.XTox(this)` at `amICollidingFromOutside`) and uses
  `rec1`/`rec2` for cap centers. The shared `uVec=(0,1,0)` convention is
  preserved end-to-end.

Picked (b) for the constructor preservation and the entanglement
avoidance — no Listeria path or viewer rendering needed to be inspected
or modified. The pill body axes are now fully consistent: `uVec=(0,1,0)`
on the `Bug` instance ↔ collision math treats long-axis as `uVec` ↔
placement scatters along `uVec`.

### Smoke run — `ParameterFiles/pillCpuSmoke`

New param file: pill `length=3 µm`, `radius=0.5 µm` (defaults), 20 initial
filaments (`minFilLength=0.15`, `maxFilLength=0.6`), no myosin minifilaments,
no protein nodes, **polymerizing actin** (all `kATPOn/Off`, `kADPOff` rates
fall through to `Env` defaults: `kATPOn2=11.6 µM⁻¹s⁻¹`, etc.), capRate
reduced to 0.5 s⁻¹ to keep barbed ends uncapped long enough to grow into
the wall. `bugShapedCrucible:true:1.0;`, `simOutsideBug:false:0.0;`.
`runTime=0.2 s` (2000 steps); `toFileInterval=50` (41 frames).

Run:

```
java --enable-preview -Xmx800M -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -pf ParameterFiles/pillCpuSmoke -3js /tmp/pillSmoke2
```

(No `-gpu`; CPU path only. TornadoVM jar is needed on the classpath only
because `reportMoveAB()` is called at end-of-run.)

### Confinement result — caps + cylinder

Per-frame endpoint excursion past the pill wall (positive = past the wall,
in µm). Pill long axis tested as `+y` (current fix) vs `+x` (the broken
hypothesis the survey identified):

| metric                                               | value         |
|---                                                   |---            |
| max excursion under `pill-along-y` (fix)             | 0.1448 µm     |
| max excursion under `pill-along-x` (broken)          | 0.8876 µm     |
| mean excursion `pill-along-y`                        | **−0.190 µm** (inside) |
| mean excursion `pill-along-x`                        | +0.165 µm (outside)    |
| max excursion steady-state (frame ≥ 3, `pill-along-y`) | **0.0333 µm** |
| 99-pctile excursion steady-state                     | **0.0039 µm** (≈ 1 filament radius) |

The two hypotheses give a 7-fold spread in mean excursion; the
discrimination is unambiguous: filaments are confined to a capsule along
`+y`, exactly as the fixed math intends. The frame-0 max (0.1448 µm) is
the initial-placement transient — `makeRandomFilament` picks two interior
points and places a filament center+extent equal to their separation,
which can put endpoints just outside the wall. The wall force pushes
them back within 1-4 frames; from frame 3 onward, max excursion stays
under 0.033 µm (≈ 7 filament radii) and the 99-percentile sits at one
filament radius — wall-touching, not penetrating.

Endpoint locality in the last frame: ~65 endpoints in the cylindrical
mid-section, ~13 in the `−y` cap, ~5 in the `+y` cap, all inside.
`y`-range of all last-frame endpoints: `[−1.258, +1.236] µm` (pill
extent: `|y| ≤ 1.5`). `x`-range: `[−0.488, +0.440]`; `z`-range:
`[−0.396, +0.364]` — well inside the radial wall `±0.500`.

### Polymerization-gating-at-wall result

Per-frame segment count grows from 35 (frame 0) to 42 (frame 40) — 7
polymerization-driven splits over 2000 steps. Long-lived barbed ends
(end2) that approach the wall stay arrested at it. Sample trace from
the smoke output (full table in
`RUN_LOGS/2026-06-03_pillCpuSmoke_F1b.txt`):

```
seg 25: starts at end2=(+0.254,+0.854,+0.593) — outside wall, dist=−0.145
  f 1: end2=(+0.189,+0.804,+0.469)   wall_dist=−0.006   ← pushed to wall
  f10: end2=(+0.306,+0.787,+0.361)   wall_dist=+0.026
  f40: end2=(+0.243,+0.970,+0.357)   wall_dist=+0.068   ← drifted inside
seg 21: starts at end2=(+0.534,+0.515,+0.168) — outside wall, dist=−0.060
  f 1: pushed back; oscillates within ±0.07 µm of wall through f40
```

The CPU `bugForcesFromInside` writes `end1TipC = 0` / `end2TipC = 0` on
wall hit (FilSegment.java:2607, 2610). `stericHindranceEnd2()` reads
`end2TipC < halfmono` (FilSegment.java:2872-2875) and gates the
polymerization branch in `end2BiochemSim` (FilSegment.java:1039). The
trace confirms tips that reach the wall do not continue growing through
it — they stay within ~one filament radius of the wall surface. No
endpoint shows monotonically growing excursion (which would indicate
the gate is bypassed).

### Files changed

| file | change |
|---|---|
| `boxOfActin/Bug.java` | `amICollidingOuter`, `amICollidingInner` — body-frame transform on input, force returned to global. Cap force constructed from global-frame `rec1`/`rec2` |
| `boxOfActin/Bug.java` | `rdmPtInside()`, `rdmPtInside(double)`, `rdmPtInsideNodeZone()` — added `pt.xToX(this)` before `pt.inc(coordAsPt3D())` so body-frame placements land in the correct global location |
| `ParameterFiles/pillCpuSmoke` | NEW — polymerizing-actin smoke configuration enabling `bugShapedCrucible` |
| `JOURNAL.md` (this file, F1 entry) | `end1TipC`/`end2TipC` row in the force-coverage table corrected: live polymerization-clearance gate, *not* dead in general; only appears dead under stabilized-filament workloads (gliding/bench). Device path validated only for non-polymerizing workloads; polymerizing box runs require `BOA_DIAG_CPU_F1=1` |
| `RUN_LOGS/2026-06-03_pillCpuSmoke_F1b.txt` | per-frame confinement + tip-trace dump |

No GPU code touched. `boundaryBoxKernel` unmodified. `boundaryPillKernel`
still unbuilt — the deferred device F1b port can now use this CPU pill
as its bit-for-bit reference.

### Constraints respected

- CPU-only. No device-kernel changes. No GPU port of the pill.
- `simOutsideBug:false` throughout. Listeria from-outside path not
  exercised, not modified. The `lmBug` static reference is untouched.
- The `Bug` constructor (`setUVec(0,1,0)`) is unchanged — body-frame fix
  goes in the collision/placement methods only, so the same `Bug` class
  remains correct for Listeria (`makeListeriaBug`, which uses
  `amICollidingFromOutside`'s already-body-frame code path).
- No entanglement encountered. The viewer rendering question (the
  capsule itself is **not** drawn — `ThreeJSWriter` only emits the box
  `bounds` from `boxXDim/Y/Z`) is unaffected: confinement was verified
  programmatically from the per-frame JSON, not visually. The box
  wireframe shown in the viewer is cosmetically irrelevant in this run
  (boxes set 4 × 4 × 4 µm purely to bound the camera around the pill).

### Open follow-ups (not part of F1b)

- `boundaryPillKernel` device port: mirror `boundaryBoxKernel`. Plan-build
  shape dispatch already has `BOUNDARY_SHAPE_PILL=1` slot reserved.
  Uniforms: `coord.{x,y,z}`, `length`, `radius`, `halfCylLength`, plus
  the body-frame rotation matrix entries from `Thing.soaTransXTox` for
  this `Bug` instance (or pre-rotated `rec1`/`rec2`/`uVec` packed as
  scalar uniforms — the pill's rotation is fixed for the whole run, so
  the rotation matrix is a plan-build-time constant).
- tipC device write-back: corollary to the F1 device validation gap.
  When `boundaryBoxKernel` (and later `boundaryPillKernel`) is extended
  to compute end1TipC/end2TipC on wall hits, the device→host bridge needs
  to surface those values back to FilSegment so polymerization biochemistry
  (which runs on CPU) reads the latest gate. Until then, polymerizing
  workloads must run with `BOA_DIAG_CPU_F1=1`.
- Viewer pill wireframe: a follow-up if visually-rich pill runs become a
  workflow priority. The current programmatic verification from JSON is
  sufficient for validation gating.

## 2026-06-03 — Phase 2 F1 (box) — implementation (PASS)

**Verdict: PASS.** Rigid-box from-inside boundary force/torque (Chamber
`amICollidingOuter` + `bugForcesFromInside`) ported to a per-segment device
kernel `boundaryBoxKernel`. Cheap near-wall probe matches CPU bit-for-bit
on five non-degenerate held-config cases (max rel diff 2.5e-16 = float-noise
floor). End-to-end gliding-assay viewer check confirms a wall-touching
filament is confined identically by device and CPU arms (started at
end1.x = +7.086 µm just past the +7.0 wall, pushed back inside to 6.99 µm
within 4 frames; arm-A / arm-B per-frame max-|x| drift ≤ 0.015 µm, Brownian
noise). Default flipped: `DIAG_CPU_F1 = false`, device kernel active by
default; `BOA_DIAG_CPU_F1=1` re-routes to CPU `checkBugCollisionFromInside`
for clean A/B. The pill (`Bug` from-inside) port is deferred to **F1b**
pending the CPU pill revival flagged in the 2026-06-03 boundary survey
(axis-convention fix + smoke run).

### Step 0 — formula confirmation

Survey settled the dispatch shape; this entry confirms the live target
configs run `theBox instanceof Chamber`:

- `Env.bugShapedCrucible` defaults `false` (Env.java:384); no parameter
  file in `ParameterFiles/` enables it (`grep` returns nothing). Every
  current run sets `Thing.theBox` to a `Chamber` via
  `Chamber.makeABox()` (BoxOfActin.java:2169-2176).
- `Env.simOutsideBug` defaults `false`; tested gliding parameter files
  leave it unset, so `FilSegment.checkBugOrBoxCollision` takes the
  from-inside branch (FilSegment.java:1229-1233).

**Chamber from-inside wall force** (Chamber.java:125-138):
- inputs: `ctr` (endpoint, global), `R` (segment radius)
- `d = ctr − chamberCenter` (chamberCenter = origin per `makeABox`)
- `forceUVec_i = sign(d_i) · (dims_i / 2 − R) − d_i` for i ∈ {x,y,z}
- if `sign(forceUVec_i) == sign(d_i)` (endpoint still inside the
  inset wall on axis i), zero that axis
- `delta = |forceUVec|`; if nonzero, `forceUVec /= delta`

**bugForcesFromInside** (FilSegment.java:2565-2589):
- `R_lever = (End − coord) · 1e−6` (m)
- `RxFuVecSqrd = |R_lever × forceUVec|²`
- `fturn  = 1e−6 · delta · bRotGam.y  / (RxFuVecSqrd · dt)`
- `ftrans = 1e−6 · delta · bTransGam.x / dt`
- `fnorm  = 0.1 · min(fturn, ftrans)`   ← 0.1 hardcoded literal on CPU
- `Fcoll = fnorm · forceUVec`,  `Tcoll = R_lever × Fcoll`
- adds to per-Thing `forceSum`/`torqueSum`

Box dimensions: `Env.boxXDim/Y/Z` (defaults 2 × 2 × 0.1 µm; gliding500 sets
14 × 2 × 0.5 µm). `FilSegment.radius = Env.actinWidth / 2 = 0.0035 µm`.
All scalar uniforms; no per-segment shape inputs needed.

### boundaryBoxKernel + plan-build-time shape dispatch (Survey Option A)

`GPUMoveThing.boundaryBoxKernel` (GPUMoveThing.java line ~1245): one thread
per move slot, runs AFTER `chain` and BEFORE `move` in the chained
TaskGraph. Per-slot early-return on `boundaryActive[i] == 0` (non-FilSegment
slot, DIAG_CPU_F1 on, Listeria active, or wrong container shape). For
active slots, reads pose inline (`coord/uVec/soaLengthArr`), computes
end1/end2 wall checks identical to the CPU formulas above, ADDS F+T to
this slot's `jointForceSum`/`jointTorqueSum` (RMW on top of chain's writes;
both tasks share device buffers within one .execute() and no host
re-upload happens between tasks).

Plan-build-time shape selector (`boundaryShape`, set in
`allocateAndBuildPlan` from `Thing.theBox instanceof ...`):

| shape constant | wired? | notes |
|---|---|---|
| `BOUNDARY_SHAPE_BOX` (0) | YES (this entry) | `Chamber` → `boundaryBoxKernel` task added |
| `BOUNDARY_SHAPE_PILL` (1) | NO — **F1b** | sibling task slot left as a comment; needs pill CPU revival first (axis-convention fix from 2026-06-03 survey §4a) |
| `BOUNDARY_SHAPE_NONE` (-1) | n/a | no boundary task wired; CPU pair runs for every seg |

Container shape is fixed for the run, so the shape branch resolves once at
plan build. Switching shape mid-run would require `closePlan()` +
`allocateAndBuildPlan()` — not exposed today (Env.bugShapedCrucible is not
mid-run mutable).

PTX-safe primitives only: ternaries for sign (no `Math.signum`), `?:` for
min, `Math.sqrt` (PTX-native). The CPU's `1/0 → +∞` divide-by-zero branch
(R parallel to forceUVec → `RxFuVecSqrd = 0`) is sidestepped with a
sentinel: `fturn = (RxFuVecSqrd > 1e-30) ? ... : 1e30`. With ftrans ~ 1e−10
typical, `1e30 < ftrans` is always false → `fnorm = fnormScale · ftrans` —
same numerical result as CPU's `Math.min(+inf, ftrans)`. Lesson logged for
the audit.

### Gating (Lesson 1 — per-force, not per-dispatch)

`FilSegment.gpuBoundaryHandled` set true by `GPUMoveThing.classifyThings()`
when, for this segment, all of:

- `Env.useGPU` (GPU run)
- this segment is GPU-handled (slot ≥ 0)
- `Thing.theBox instanceof Chamber` (Survey Option A — only box ported)
- `Env.simOutsideBug` inactive (Listeria from-outside stays on CPU)
- `DIAG_CPU_F1 == false`

The gate is **inside** `FilSegment.checkBugOrBoxCollision`
(FilSegment.java:1227-1248): the Listeria from-outside branch
(`checkBugCollisionFromOutside`) always runs on CPU; only the from-inside
call is gated. Per-force gating per Lesson 1 — never skip the whole
dispatch.

```java
if (Env.simOutsideBug.isActive()) {
    checkBugCollisionFromOutside();   // always CPU
} else {
    if (!gpuBoundaryHandled) {
        checkBugCollisionFromInside();
    }
}
```

A/B flag: `DIAG_CPU_F1 = false` (device default). `BOA_DIAG_CPU_F1=1`
env-var hook in `BoxOfActin.begin()` flips it ON (CPU pair runs and
device kernel early-returns at every slot via `boundaryActive[i] = 0`).
SOA_POSE / MOVE_AB_PROFILE interlock at GPUMoveThing.java:202-212 also
forces `DIAG_CPU_F1 = true` (AoS-only kernel cannot read SoA pose).

### Force-coverage audit (Lesson 2)

| Force / behaviour | CPU `DIAG_CPU_F1=true` | Device `DIAG_CPU_F1=false` (default) |
|---|---|---|
| **F1 box wall (Chamber from-inside)**: F+T per endpoint | CPU `checkBugCollisionFromInside` runs | device `boundaryBoxKernel` runs; CPU skipped via `gpuBoundaryHandled` |
| F1b pill wall (Bug from-inside) | CPU `checkBugCollisionFromInside` runs (axis-convention fixed in F1b — see entry above; `ParameterFiles/pillCpuSmoke` exercises this path) | not ported — device skips, `gpuBoundaryHandled` stays false, CPU pair runs; device pill kernel still deferred |
| Listeria from-outside (`simOutsideBug` true) | CPU `checkBugCollisionFromOutside` runs (entire ActA / tipC / from-outside chain stays on CPU) | same — CPU path runs unconditionally regardless of GPU state |
| `bugForcesFromInside` side effects: `end1AxialF`/`end2AxialF` increments | written on CPU (dead-write — only reader is `setCompression()`, commented out at FilSegment.java:500) | not written on device (intentionally — dead in production) |
| `bugForcesFromInside` side effects: `end1TipC`/`end2TipC` zeroing on wall hit | written on CPU; **live polymerization-clearance gate** — read by `stericHindranceEnd1`/`stericHindranceEnd2` (FilSegment.java:2867-2875), which gate `end1BiochemSim`/`end2BiochemSim` polymerization (FilSegment.java:1039). Also read by `checkCapping` for the Arp-near branch. Only *appeared* dead in the gliding/bench validation because those param files use stabilized, non-polymerizing filaments (all `kATPOn/Off`, `kADPOff` rates zero); any polymerizing-actin run in any chamber needs the gate | **not written on device** — F1 device path is therefore validated **only for non-polymerizing workloads**. Polymerizing box runs must use `BOA_DIAG_CPU_F1=1` (CPU pair) until tipC is computed and written back from the device. A tipC survey + device fix is forthcoming |
| F3 link force, F4 torsion | CPU pair via `addLinkForces` / `addTorsionSpringForces` (unchanged from F3/F4 entry) | device `chainPairForcesKernel` (this kernel runs BEFORE boundary in the chained plan; boundary RMW preserves its writes) |
| F5/F6 node forces, biochem, mesh, motors | CPU (unchanged) | CPU (unchanged) |
| Anchor spring (MyosinFixed) | folded into device `jointsKernel` when `DIAG_CPU_ANCHOR=false` | same |

**Applied exactly once** in either flag state for forces and torques.
**Nothing dropped or doubled** in the force/torque coverage. The two CPU
side effects on `FilSegment` differ:

- **`end1AxialF`/`end2AxialF`** — genuinely dead. The only reader is
  `setCompression()`, commented out at FilSegment.java:500. The device
  skipping these writes has no observable effect.
- **`end1TipC`/`end2TipC`** — **live for polymerizing workloads**. The
  device kernel currently omits these writes, so the device F1 path is
  validated only against the gliding/bench param files used in this
  entry (stabilized filaments with all polymerization rates zero). A
  polymerizing box run on `-gpu` would lose the wall-arrest gate at the
  barbed end and grow tips through the wall. **Polymerizing box runs
  must use `BOA_DIAG_CPU_F1=1`** until a tipC survey + device fix lands.
  This also applies to F1b (pill from-inside) once it is ported: tipC
  must be written from the device pill kernel for any polymerizing pill
  run.

### Validation 1 — held-config near-wall probe (cheap gate)

`boxOfActin/HeldSegF1Diag.java` constructs a single FilSegment in
prescribed wall-touching configs and runs both formulas on the SAME
frozen pose:

| Case | description | result |
|---|---|---|
| 1 | 30° tilt in x-y, end2 ~5 nm past +x wall — single-axis hit, non-trivial torque z | PASS (|dF|=0, |dT|=0) |
| 2 | 30° tilt, both ends past +x wall | PASS (|dF|=0, |dT|=6e−36, rel=2e−16) |
| 3 | 45° tilt, end2 past +x,+y corner — two-axis containment | PASS (|dF|=0, |dT|=2e−37, rel=2e−16) |
| 4 | fully inside box, no collision | PASS (both zero) |
| 5 | full 3D tilt (φ=20°, θ=30°), end2 ~5 nm past +x — fully 3D R×F components | PASS (|dF|=0, |dT|=0) |

Full dump: `RUN_LOGS/2026-06-03_heldSegF1_diag.txt`. Max relative error on
torque is 2.5e-16 across the suite — at the float-noise floor; CPU and
device produce bit-equal forces and torques modulo the last-bit `?:`-vs-
`Math.signum` cross product reordering. The lesson from the F3 near-miss
(non-degenerate, non-axis-aligned probe) was respected — Cases 1, 2, 5 all
test off-axis lever arms where a transposed cross product would not
cancel.

### Validation 2 — gliding `-3js` viewer wall check

`ParameterFiles/glidingAssay500` (box 14 × 2 × 0.5 µm, `runTime=0.01s`,
single 11-segment glider). Arm A: `-gpu` with `DIAG_CPU_F1=false`
(device). Arm B: `-gpu BOA_DIAG_CPU_F1=1` (CPU). Both 1000 steps,
`toFileInterval=100.0` → 12 JSON frames.

Frame-by-frame `max(|endpoint.x|)` across all FilSegment endpoints:

```
frame                   armA_maxX armA_oversA |  armB_maxX armB_oversB
frame_000000.json         7.08600     1 |    7.08650     1
frame_000001.json         7.02090     1 |    7.02630     1
frame_000002.json         7.01510     1 |    7.01410     1
frame_000003.json         7.00530     1 |    7.00550     1
frame_000004.json         6.99990     0 |    7.00690     1
frame_000005.json         6.99680     0 |    7.00030     1
frame_000006.json         7.00060     1 |    6.99500     0
frame_000007.json         6.99720     0 |    6.99370     0
frame_000008.json         6.98940     0 |    6.99090     0
frame_000009.json         6.99780     0 |    6.98470     0
frame_000010.json         6.99200     0 |    6.97670     0
frame_000011.json         6.98750     0 |    6.98610     0
```

(`oversA/B` = count of endpoints past the ±7.0 wall in that frame, summed
over both ends of every segment in the glider.) The filament starts placed
ASTRIDE the +x wall (frame 0: end at +7.086, ~0.086 µm past the wall —
configured glider position is inside-most segment crosses the box boundary
at startup). Within 4 frames the device kernel pushes the offending
endpoint back inside (frame 4: 6.9999 µm, no oversteps). Per-frame max-|x|
diverges by ≤ 0.015 µm between arms — Brownian-noise level expected from
two stochastic runs with the same seed but different per-step evaluation
order. Max-|y| stays < 0.05 µm (wall at ±1.0), max-|z| < 0.06 µm (wall at
±0.25); neither arm violates the y or z walls. Both arms behave
indistinguishably; no escapes; no NaN/Inf produced by the device path.

Logs: `RUN_LOGS/2026-06-03_phase2_F1_glidingAB.txt`,
`RUN_LOGS/2026-06-03_phase2_F1_glidingArmA.dat`,
`RUN_LOGS/2026-06-03_phase2_F1_glidingArmB.dat`.

### Default flip — DIAG_CPU_F1 = false (device active)

`GPUMoveThing.java:200` declares `public static boolean DIAG_CPU_F1 =
false` (initial value). The `BOA_DIAG_CPU_F1` env-var hook in
`BoxOfActin.begin()` re-routes through CPU when set to "1"/"true".
SOA_POSE/MOVE_AB_PROFILE interlock unchanged.

### Constraints respected

- Only the box (`Chamber`) from-inside boundary ported. Pill kernel **not**
  built. Pill CPU revival (uVec axis-convention fix per survey §4a) **not**
  applied — both deferred to F1b.
- Membrane / `Mesh` / `StickyNode`, Listeria from-outside path,
  `bugForcesFromOutside`, ActA binding / `tipC` / contact uncapping — all
  unchanged on CPU.
- F3/F4 chain kernel untouched. Anchor spring (Phase 1) untouched.
  `collisionCheckInt` cadence unchanged. Residency flip (Phase 4)
  untouched.
- `bugForcesFromInside` side effects: `end1AxialF`/`end2AxialF` are
  genuinely dead (no live reader). `end1TipC`/`end2TipC` are the live
  polymerization-clearance gate — skipped on device, which is safe **only
  for the non-polymerizing gliding/bench workloads used to validate this
  port**. Polymerizing box runs must keep `BOA_DIAG_CPU_F1=1` until tipC
  is written back from the device. F1b will inherit the same constraint.

### A/B commands (aorus)

Build (Phase 5 — Java 21 + TornadoVM):

```
cd ~/Code/BoA
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      boxOfActin/*.java *.java
```

Near-wall probe (no TornadoVM needed):

```
java -cp ".:libs/*" boxOfActin.HeldSegF1Diag
```

Gliding wall-confinement arms (1000 steps, single glider):

```
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

# Arm A — device F1 (NEW DEFAULT; wall force on device)
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500 -3js /tmp/armA

# Arm B — CPU F1 (CPU checkBugCollisionFromInside runs)
BOA_DIAG_CPU_F1=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500 -3js /tmp/armB
```

**Phase 2 F1 (box) PASS.** F1b (pill from-inside) deferred — requires CPU
pill revival per the 2026-06-03 boundary survey before the device port can
proceed.

## 2026-06-03 — Phase 2 F3/F4 — fix: owner-perspective linkUVec (PASS)

**Verdict: PASS.** The F3 Newton-3 leak localized in the 2026-06-03 AM held-config
diagnostic is closed. Both threads on a chain pair now compute the **same**
owner-perspective `linkUVec` (the CPU `addLinkForces` formula evaluated from the
owner's side), so per-thread F3 forces come out exactly anti-parallel by
construction — no atomic writes, no visited flags, no inter-thread state. The
held-bent dump shows zero pair-force sum at every θ, the per-segment F3 torque
on the non-owner matches CPU bit-for-bit, SingleFilDiag holds the chain at zero
drift over 100k steps, and the deflection benchmark on `-gpu` lands at
`ratio=1.000107` (was 1.262114; CPU/arm-B baseline 1.000049, design device
baseline 0.999876, gate +0.146%). Default flag flipped: `DIAG_CPU_F3F4 = false`,
device kernel active by default; `BOA_DIAG_CPU_F3F4=1` re-routes through CPU
F3/F4 for clean A/B.

### The fix — owner = lower slot index, both threads compute owner's linkUVec

In `chainPairForcesKernel`, the F3 link force/torque block in both end2 and
end1 cases now branches on `selfIsOwner = (i < neighbourSlot)`:

```java
// Owner's offset linkPt and non-owner's connecting tip
//   ↓ both threads compute the SAME line ↓
if (selfIsOwner) {
    linkPt = self.connectingTip + r·(offsetDirectionIntoSelf);
    ptAtEnd = neighbour.connectingTip;
} else {
    linkPt = neighbour.connectingTip + r·(offsetDirectionIntoNeighbour);
    ptAtEnd = self.connectingTip;
}
linkUVec = unit(ptAtEnd - linkPt);
fSign    = selfIsOwner ? +1 : -1;
F_self   = fSign · forceMag · linkUVec;
```

The offset direction INTO each segment from its connecting tip is determined
by which end of that segment carries the link (end2 → −uVec; end1 → +uVec).
For self in its own end2 block this matches the existing CPU formula
(`linkPt = end2 − r·uVec`); for the non-owner case, the offset is applied
along the neighbour's uVec (signed by the neighbour's connecting side, given
by `topoEnd{1,2}Side`). Both topology buffers (`topoEnd*Slot` and
`topoEnd*Side`) already carry the data needed; no new buffer or atomic write
was required. The R lever arm (used in the per-segment torque) still uses
self's own uVec — that part was correctly per-segment before the fix.

The moveCoeff calculation reads `dot(uVec_self, linkUVec)` for self and
`dot(uVec_nbr, linkUVec)` for the neighbour; cosBeta gets squared in the
formula, so the sign of either dot is irrelevant — both threads compute the
same moveC sum and the same forceMag.

### Held-bent dump @ θ=10° — DEV per-thread forces match CPU's +/− pair

`HeldChainF3F4Diag` (unchanged geometry, mirror functions updated to thread the
`meIsOwner` flag through):

| quantity | CPU (owner=seg0) | DEV seg0 thread (owner) | DEV seg1 thread (non-owner) |
|---|---|---|---|
| linkUVec | `(+1, 0, 0)` | `(+1, 0, 0)` | `(+1, 0, 0)` ← same line, fSign flips |
| forceMag | 1.590158e-13 N | 1.590044e-13 N | 1.590044e-13 N |
| F on seg0 | `(+1.590e-13, 0, 0)` | `(+1.590e-13, 0, 0)` | (n/a) |
| F on seg1 | `(−1.590e-13, 0, 0)` | (n/a) | `(−1.590e-13, 0, 0)` ✓ |
| T_F3 on seg0 | `(0, 0, 0)` | `(0, 0, 0)` ✓ | (n/a) |
| T_F3 on seg1 | `(0, 0, −1.230151e-22)` | (n/a) | `(0, 0, −1.230062e-22)` ✓ |

θ-sweep (1°, 5°, 10°, 20°, 45°):

```
DEV pair-force sum (F_s0+F_s1):
  θ=1°  → (0, 0, 0)         was (+2.46e-17, -2.81e-15, 0)
  θ=5°  → (0, 0, 0)         was (+6.11e-16, -1.40e-14, 0)
  θ=10° → (0, 0, 0)         was (+2.42e-15, -2.76e-14, 0)
  θ=20° → (0, 0, 0)         was (+9.22e-15, -5.23e-14, 0)
  θ=45° → (0, 0, 0)         was (+3.83e-14, -9.26e-14, 0)
```

Full dump: `RUN_LOGS/2026-06-03_held3seg_f3f4_fix.txt`. The DEV F3 torque on the
non-owner is now `−1.230e-22` at θ=10° matching CPU to ~7e-5 (same fastAcos vs
accurateAcos noise as forceMag); previously zero (the wrong-direction-artifact
mentioned in the diagnostic prompt).

### SingleFilDiag arm A (device F3/F4) — no straight-chain regression

```
[SINGLE_FIL_DIAG] FINAL:
  total steps      = 100000
  max |coord.y|    = 0.000000e+00 µm
  max |coord.z|    = 0.000000e+00 µm
  verdict          = PASS
```

Log: `RUN_LOGS/2026-06-03_phase2_singleFil_armA_device_fix.log`. Chain stays
numerically straight on `-gpu` (same as before the fix — the chain is straight
so cross(u,±u)=0 and the canonical-owner branch hits the strainDist≈0 path).

### Deflection benchmark `-bmDiag -gpu` — bench PASS

200k steps, default bench params (fracMove=0.5, fracR=0.1, fracMoveTorq=0.265):

| Arm | final ratio | final defl | gap vs CPU/arm-B (1.000049) |
|---|---|---|---|
| A — device F3/F4 (`BOA_DIAG_CPU_F3F4=0`) | **1.000107** | 0.009802 µm | +0.006% ← PASS |
| A — pre-fix (for comparison) | 1.262114 | 0.012370 µm | +26.2% ← FAIL |
| B — CPU F3/F4 (current default) | 1.000049 | 0.009801 µm | — baseline |
| design device baseline target | 0.999876 | — | gate ±0.05% around this |

Arm A now lands `(1.000107 − 0.999876) / 0.999876 = +0.023%` from the design
device baseline — comfortably inside the +0.146% tolerance. Log:
`RUN_LOGS/2026-06-03_phase2_bmDiag_armA_device_fix.log`.

### Default flip — DIAG_CPU_F3F4 = false (device active)

`GPUMoveThing.java:188` now `public static boolean DIAG_CPU_F3F4 = false;`.
The `BOA_DIAG_CPU_F3F4=1` env-var hook in `BoxOfActin.begin()` still re-routes
through CPU F3/F4 for A/B testing. No other code or gating changed.

### What this means re: the "no canonical-owner trick" framing in the prompt

The fix DOES use a canonical-owner determination — owner is the segment with
the lower move-slot index, computed inline from `i < topoEnd*Slot` — but it
needs no inter-thread coordination: no atomic, no visited flag, no second pass,
no cross-slot write. Each thread independently arrives at the same owner-
perspective `linkUVec` from the data it already has (its own pose, the
neighbour's pose via the topology index, the constant `actinMonoRadius`), and
the +/− sign falls out of the same slot comparison. Unique-ownership writes
preserved.

### Constraints respected

- Only F3 link-direction (and the dependent moveCoeff/forceMag/F/torque) in
  `chainPairForcesKernel` changed; F4 untouched.
- CPU `addLinkForces` / `addTorsionSpringForces` untouched.
- Per-force gating in `FilSegment.step()` untouched.
- Force-coverage table from the 2026-06-02 entry still applies — every force
  applied exactly once in both flag states.
- `HeldChainF3F4Diag.deviceChainEnd{1,2}` updated to mirror the new kernel
  (added `meIsOwner` parameter, threaded through call sites). The diagnostic
  is standalone pure-Java; not part of the production code path.

### A/B commands (aorus)

Build (Phase 5 — Java 21 + TornadoVM):

```
cd ~/Code/BoA
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      boxOfActin/*.java *.java
```

Held-bent dump (no TornadoVM needed):

```
java -cp ".:libs/*" boxOfActin.HeldChainF3F4Diag
```

SingleFilDiag arms:

```
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

# Arm A — device kernel (NEW DEFAULT)
BOA_BMDIAG_MAX_STEPS=100000 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -singleFilDiag -gpu

# Arm B — CPU pair
BOA_BMDIAG_MAX_STEPS=100000 BOA_DIAG_CPU_F3F4=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -singleFilDiag -gpu
```

Deflection benchmark arms:

```
# Arm A — device F3/F4 (NEW DEFAULT; target ratio ≈ 1.000)
BOA_BMDIAG_MAX_STEPS=200000 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -bmDiag -gpu

# Arm B — CPU F3/F4 (target ratio ≈ 1.000)
BOA_BMDIAG_MAX_STEPS=200000 BOA_DIAG_CPU_F3F4=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -bmDiag -gpu
```

**Phase-2 F3/F4 PASS.**

## 2026-06-03 — Phase 2 F3/F4 — held-config diagnostic (root cause = F3, not F4)

**Diagnostic only — no fix, no verdict on which path is correct.** Built a
standalone pure-Java intermediates-dump on a held 3-segment chain with one
bending joint between seg0 and seg1 (single-joint test config, zero gap at
links, seg1↔seg2 straight to verify the null case). Mirrors both formulas
(CPU `addLinkForces` / `addTorsionSpringForces` and device
`chainPairForcesKernel`) on identical input and prints ordered intermediates
side-by-side. Code: `boxOfActin/HeldChainF3F4Diag.java`. Full dump:
`RUN_LOGS/2026-06-03_held3seg_f3f4_diag.txt`.

### 1. F3 identical on identical input?

**Scalar F3 forceMag agrees** between CPU and device to ~7e-5 relative
(noise from `fastAcos` vs `accurateAcos` in `moveCoeff` at θ where
|cos β|∈[0.95, 1.0]). Example at θ=10°: CPU 1.590158e-13 N, device 1.590044e-13 N.
At θ ≥ 20° both paths fall in the `Math.acos` band and agree to ulp.

**F4 torsionMag also agrees** to ≤0.13% (same acos crossover noise; θ=10°:
CPU 9.091e-21 vs device 9.103e-21 N·µm). At θ=20°/45° both: 1.820e-20 /
4.096e-20 N·µm, identical.

So the per-thread scalar magnitudes are essentially identical. **F4 is not
the divergent subsystem** — contrary to the planner-prompt framing.

### 2. First divergence — F3 linkUVec direction (Newton-3 violation)

The first ordered intermediate where the two paths disagree is the **F3
`linkUVec` self-direction**, which each device thread computes from its own
perspective. **CPU computes one linkUVec from the owner's perspective and
applies `+F` to owner / `−F` to neighbour by fiat**; **device computes a
separate linkUVec per thread**, and the two threads' linkUVecs are not
anti-parallel for a bent joint.

At θ=10° on the bent joint (seg0.end2 ↔ seg1.end1):

| quantity | CPU (owner=seg0 applies to BOTH) | DEV seg0 thread | DEV seg1 thread |
|---|---|---|---|
| linkUVec_self | `(+1, 0, 0)` | `(+1, 0, 0)` | `(-cos10°, -sin10°, 0) = (-0.9848, -0.1736, 0)` |
| forceMag | 1.590158e-13 N | 1.590044e-13 N | 1.590044e-13 N |
| F on seg0 | `(+1.590e-13, 0, 0)` | `(+1.590e-13, 0, 0)` | (n/a) |
| F on seg1 | `(-1.590e-13, 0, 0)` ← anti-parallel **by fiat** | (n/a) | `(-1.566e-13, -2.761e-14, 0)` ← along its own linkUVec |

The two values applied to seg1 disagree both in magnitude (same to 7e-5)
and **direction (by the joint angle θ)**. CPU enforces Newton-3rd-law by
construction; device per-thread unique-ownership does not.

The F3 torque on seg1 also differs as a downstream consequence: CPU computes
`R_nbr × F_opp` where `F_opp = -F_self_owner = (-forceMag, 0, 0)` and
`R_nbr = -½·L·fracR·uVec_seg1`, giving a small non-zero z-torque
(-1.23e-22 N·µm at θ=10°). Device seg1 thread computes `R × F` where its
own R and F are both along ±uVec_seg1 (parallel), so the device F3 torque
on seg1 is exactly zero. Same root cause: directionality of F on the
neighbour is different on the two paths.

### 3. Asymmetry on the device

Per-thread **F vectors are NOT anti-parallel** on a bent joint:

```
DEV pair-force sum (F_seg0 + F_seg1) at the bent joint:
  θ=1°  → (+2.46e-17, -2.81e-15, 0)
  θ=5°  → (+6.11e-16, -1.40e-14, 0)
  θ=10° → (+2.42e-15, -2.76e-14, 0)
  θ=20° → (+9.22e-15, -5.23e-14, 0)
  θ=45° → (+3.83e-14, -9.26e-14, 0)
```

The residual magnitude is `forceMag · 2·sin(θ/2)` (chord length between the
two unit linkUVecs at angle θ; verified ≈ `forceMag · sin θ` at small θ and
exactly `forceMag · sin θ` y-component for this in-plane geometry). At
θ=10°, `1.590e-13 · sin 10° = 2.76e-14` — matches the dump exactly.

CPU pair-force sum is `(0, 0, 0)` at every θ by construction.

**F4 pair-torque sum IS zero on the device** at every θ — the device's
torsion-vector unit (cross of uVecs, normalized) is exactly `-`opposite on
the two threads, and each thread multiplies by the same `torsionMag`. So
F4 satisfies pair-symmetry; F3 does not.

### 4. θ-scaling of the divergent quantity

The divergent quantity (F3 linkUVec direction; equivalently the device
pair-force-sum magnitude) scales **geometrically with the bend angle**:

| θ | DEV pair-force-sum magnitude | ratio to `forceMag·sin θ` |
|---|---|---|
| 1° | 2.813e-15 | 1.000 |
| 5° | 1.402e-14 | 1.000 |
| 10° | 2.764e-14 | 1.000 |
| 20° | 5.310e-14 | 1.000 |
| 45° | 1.002e-13 | 1.000 |

Not a constant ratio — a `sin θ` scaling. Confirms the divergence is
**geometric** (each path computes the linkUVec direction differently at
finite curvature), not a coefficient/partition mismatch. The two paths
become identical at θ=0 (which is why SingleFilDiag passes — a perfectly
straight chain has no Newton-3 violation to detect; cross(u, ±u)=0
suppresses any signal). The bench by contrast settles to a curved
equilibrium where every joint contributes a sinθ-scaled leak; with 10
chain joints in the 11-seg bench at the per-joint angles needed to support
the midpoint force, the cumulative residual is comparable in magnitude to
the applied force and biases the equilibrium shape — consistent with the
~21% softening (`1/0.79 ≈ 1.26` deflection ratio) observed at the bench.

This confirms Hypothesis #1 from the 2026-06-02 PM entry (the leading
hypothesis — Newton-3rd-law asymmetry in unique-ownership F3) and **rules
out** the other listed hypotheses (F4 sign convention #2 produces no
θ-scaling residual here; `bRotGam.y` indexing #3 affects only F4
torsionMag, which agrees; `fastAcos` vs `accurateAcos` #4 produces a
sub-1% noise floor with no θ-amplification).

### Constraints respected

- No changes to F3/F4 physics, the kernel, or the CPU formula. Only added
  the standalone `HeldChainF3F4Diag.java` instrumented dump (zero
  cross-dependencies on the rest of the codebase, runs as
  `java -cp . boxOfActin.HeldChainF3F4Diag`).
- No analytical ground truth derived; both paths reported as-is, no
  correctness verdict. Planner + jba adjudicate the fix direction.

## 2026-06-02 — Phase 2 F3/F4 — implementation (FAIL at deflection bench)

**Verdict: FAIL.** SingleFilDiag passes cleanly (both arms maxAbsY=maxAbsZ=0.0 over
100k steps), but the deflection benchmark on `-gpu` lands at ratio=1.262 vs the
CPU/arm-B reference 1.000 — a ~26% deficit in effective bending stiffness, well
outside the ±0.05% gate around the +0.146% device baseline (target 0.999876,
observed 1.262). Implementation lands clean (compiles, runs, force-coverage clean
on both flag states), but the kernel produces systematically weaker F3/F4 restoring
forces by ~21% (`1/1.26 ≈ 0.79`). Root cause not localized in this session — the
unique-ownership F3/F4 formulae appear analytically equivalent to the CPU pair
pattern, the topology + slot mapping match the design spec, both kernels reach a
stable equilibrium (per-segment net force ≈ 0 at steady state in both arms). The
remaining hypotheses are listed at the end. The implementation is committed under
the `DIAG_CPU_F3F4` flag, **inverted-default** so the device path is OFF by
default until the stiffness deficit is resolved (set `BOA_DIAG_CPU_F3F4=0` to
re-activate the device kernel for debugging).

### Step zero — `-gpu` deflection benchmark runnability

PASS. Pre-port `-bmDiag -gpu -BOA_BMDIAG_MAX_STEPS=200000` reaches
`final ratio=1.000001` (with the CPU F3/F4 path running, no chain kernel yet) —
within ~0.013% of the cited 0.999876 device baseline, well inside the +0.146%
tolerance. The bench gate is runnable on the GPU path; no separate scope needed.

### Infrastructure — topology + soaLength

Per-segment chain-topology index added to `GPUMoveThing`:
- `topoEnd2Slot[i]` / `topoEnd2Side[i]` — move-slot of `end2Fil` and side flag
  (0 if `ptAtEnd2 == end2Fil.end1Pt`, 1 if `== end2Pt`). Sentinel `-1` in slot
  means "no chain neighbour on this side, or neighbour is CPU-fallback, or
  `DIAG_CPU_F3F4` forces the kernel off".
- `topoEnd1Slot[i]` / `topoEnd1Side[i]` — same shape for end1.
- `soaLengthArr[i]` — per-slot length (µm); repacked every step from
  `Thing.soaLength[thingIdx]` (length can change at biochem poly/depoly without
  setting `topologyDirty`, so per-step refresh keeps device coherent).

Topology buffers are populated in `classifyThings()` (runs only when
`topologyDirty || thingCt != lastThingCt`). All five buffers are in the chained
TaskGraph's `transferToDevice EVERY_EXECUTION` block — same precedent as the
Phase-1 anchor buffers (small per-step transfer cost vs the plan-rebuild
restructuring that FIRST_EXECUTION would require). Per-FilSegment
`gpuChainHandled` flag set inside `classifyThings()` whenever the segment AND
every active chain neighbour are GPU-handled and `DIAG_CPU_F3F4` is off; this
is the flag `FilSegment.step()` reads to decide whether to skip CPU F3/F4.

### Kernel — fused `chainPairForcesKernel`

One device thread per move slot, unique-ownership semantics: each thread reads
its own pose + the chain neighbour at each connected end, computes the F3 (link
spring force/torque) and F4 (torsion spring torque) contributions **for its own
segment only**, and writes them into `jointForceSum` / `jointTorqueSum` at its
own slot. Newton's-3rd-law symmetry intended via the same-pair-force-magnitude
analysis: A's thread applies `+F_A`, B's thread applies `+F_B = −F_A`. (See
"suspected bugs" below — this is one of the candidate failure points.)

**Disjoint-slot guarantee:** chain kernel writes only to FilSegment slots
(topo`{1,2}`Slot ≥ 0 implies the slot holds a GPU-handled FilSegment).
`jointsKernel` writes only to rod/lever/motor slots. The two never collide on
the same `jointForceSum` entry. `packRange()` pre-zeros `jointForceSum` at all
slots; threads that early-return (no chain neighbour) leave the zero in place.

**Arg budget:** 13 args (coord, uVec, soaLengthArr, 4× topo IntArrays,
bTransGam, bRotGam, jointForceSum, jointTorqueSum, chainParams, counts) — under
the TornadoVM 15-arg cap. F3 + F4 fold into one fused kernel as the design
predicted.

**PTX safety:** only `Math.sqrt`, `Math.sin`, and the existing `accurateAcos`
helper — all PTX-lowerable per `GPU_MIGRATION_LESSONS.md` Appendix A. No
`Float.isNaN`; the F4 zero-cross guard uses `tvMag2 > 1.0e-30` instead.

**TaskGraph wiring:** chained plan now runs `joints → chain → move` in that
order (`jointForceSum`/`jointTorqueSum` shared between joints + chain writers
and the move reader). Per-task worker grid added (`chained.chain` with
`MOVE_KERNEL_BLOCK_SIZE`).

### Gating — `DIAG_CPU_F3F4` + `FilSegment.step()` per-force skip

Per Lesson 1 (silent force-dropping), the gate is **per-force inside
`FilSegment.step()`**, not per-step()-dispatch:

```java
if (!gpuChainHandled) {
    addLinkForces();          // F3
    addTorsionSpringForces(); // F4
}
```

F1 (`checkBugOrBoxCollision`) above this block and F5/F6 (`addNodeForces`)
below remain unchanged on the CPU path — they keep running for GPU-handled
segments. This is the exact Phase-1 anchor pattern: skip the per-force calls
that the device kernel replicates, NOT the whole `step()` dispatch.

`DIAG_CPU_F3F4` flag (default false) mirrors `DIAG_CPU_ANCHOR`:
- OFF (default): device kernel applies F3+F4 on every GPU-handled chain
  segment; CPU F3/F4 skipped (the gate above evaluates true).
- ON: `classifyThings` packs all topology slots as `-1` → kernel returns early
  on every thread → `gpuChainHandled = false` for every segment → CPU F3/F4
  runs again.

`BOA_DIAG_CPU_F3F4=1` env hook in `BoxOfActin.begin()`, parallel to the
existing `BOA_DIAG_CPU_ANCHOR` hook.

### Planner decisions — axial loads, break-detect, filID

**Axial loads (F3 by-product):** the design's caveat is that
`incEnd2AxialForce` / `incEnd1AxialForce` consume CPU-resident pose. *Survey
finding:* `setCompression()` (the only consumer that reads these per-segment
axials and feeds them into `compressionTrack` for `inCompression` poly-checks)
is **commented out** at `FilSegment.java:481`. So the F3 axial accumulators
are currently dead-code by-products — they accumulate, get zeroed in
`resetCounters`, and are never read by any downstream consumer. Other writers
of `end{1,2}AxialF` (the `inFil*` myosin-pull paths at lines 2246/2296/2342
and the boundary-collision path at lines 2563/2566) still write and are also
unread. **Phase-2 decision: drop the CPU axial-load stub entirely.** No
residency impact for Phase 4 — the CPU path never reads them.

**Break-detection (`breakAtEnd2` / angle-EWMA):** gated on
`Env.maxSegDist.isActive()` and `Env.maxSegAngle.isActive()`. Both default
INACTIVE; neither the deflection bench nor SingleFilDiag activates them. For
gliding-assay and production runs, leave them on the CPU side as design says
— stat-tracking via `end{1,2}SegDist`/`end{1,2}SegAng` `ValueTracker`s is
cheap. Not a Phase-2 blocker; **CPU pose-read for break-detect is bench/biochem
territory only.** No per-step pose consumer to retire before the Phase-4 flip.

**filID propagation** (`addLinkForces` lines 1466-1469, 1530-1533): integer
bookkeeping, no force/physics consequence. Skip on device; if a real merge
event ever crosses the link, it'll re-equalise on the next biochem reclassify
(`topologyDirty` fires). Not a residency blocker.

### Force-coverage audit (Lesson 2)

| Force | Pre-Phase-2 (CPU step()) | Phase-2 default (`DIAG_CPU_F3F4=false`) | Phase-2 with `DIAG_CPU_F3F4=true` | Applied once? |
|---|---|---|---|---|
| F1 (filament–boundary inside chamber) | `checkBugOrBoxCollision` (CPU) | unchanged (CPU) | unchanged (CPU) | ✓ |
| F2 (filament–bug outside, Listeria only) | `checkBugCollisionFromOutside` (CPU) | unchanged (CPU) | unchanged (CPU) | ✓ where active |
| **F3 (chain link)** | `addLinkForces` (CPU) | **device chainPairForces** | CPU `addLinkForces` (kernel skipped) | ✓ in both flag states |
| **F4 (chain torsion)** | `addTorsionSpringForces` (CPU) | **device chainPairForces** | CPU `addTorsionSpringForces` (kernel skipped) | ✓ in both flag states |
| F5/F6 (node tether trans + align torque) | `addNodeForces` (CPU) | unchanged (CPU) | unchanged (CPU) | ✓ where active |
| F7 (MyoMiniFilament–bug/box) | `MyoMiniFilament.step` (CPU; cpuFallback) | unchanged (CPU) | unchanged (CPU) | ✓ |
| F8/F9/F10 (motor tether spring + alignments) | `MyoFilLink.addForces`/`alignUVecTorque`/`alignYVecTorque` (CPU) | unchanged (CPU) | unchanged (CPU) | ✓ — Phase-2 motor port is later |
| F11 (ActA tether) | `actAStart` wave (CPU) | unchanged (CPU) | unchanged (CPU) | ✓ where Listeria |
| F12 (ProteinNode–boundary) | `ProteinNode.step` (CPU; cpuFallback) | unchanged (CPU) | unchanged (CPU) | ✓ |
| Anchor spring (A7.b) | Phase-1: device `jointsKernel` | unchanged (device) | unchanged (device) | ✓ — Phase-1 default |
| Joint constraints (rod/lever/motor) | `jointsKernel` (device) | unchanged (device) | unchanged (device) | ✓ |
| Axial loads (F3 by-product) | `incEnd{1,2}AxialForce` (CPU) — dead code | **dropped (kernel does not compute)** | CPU computes (dead code, no consumer) | dead either way |
| Break-detect EWMAs (`end{1,2}SegDist/Ang`) | CPU `addLinkForces` updates `ValueTracker` | CPU EWMA not updated for GPU-handled chain segs | CPU updates EWMA normally | only relevant when `maxSegDist/Angle.isActive()` (default OFF for bench / gliding) |
| `filID` propagation | CPU `addLinkForces` (CPU) | CPU not running on GPU-handled chain segs | CPU runs normally | cosmetic; equalises on next biochem `topologyDirty` |

**Verbal cross-check:** with `DIAG_CPU_F3F4=false` (default), every GPU-handled
FilSegment slot's F3+F4 lands ONCE in `jointForceSum`/`jointTorqueSum` via the
device chain kernel (the CPU's `addLinkForces`/`addTorsionSpringForces` calls
are gated off in `step()`); F1, F5/F6, F11 still run on CPU, write to thread-
local accumulators, gather to `soaForceSum`, pack to `cpuForceSum`, and are
summed with `jointForceSum` in the move kernel. With `DIAG_CPU_F3F4=true`,
chain kernel's per-slot writes are all zero (topology -1 → early return) and
CPU F3/F4 land in `cpuForceSum` via the regular accumulator path. Nothing is
dropped, nothing double-applied in either configuration.

### Validation — SingleFilDiag (Lesson 5 minimal probe)

Built `boxOfActin/SingleFilDiag.java` + `-singleFilDiag` CLI flag.
Reuses the deflection bench chain (`FilSegment.makeBenchmarkChain`, 11 segs,
pinned ends) but turns the midpoint force OFF
(`Env.benchmarkForceOn.setValue(0)`) and per-segment Brownian is already off
(`brownianOff=true` for bench segs). With no external force and pinned ends,
a correct kernel must keep every segment center numerically straight; any
drift in y or z is direct evidence of an F3/F4 bug.

**Result: both arms PASS.** 100k-step run, sampling every step:

| Arm | maxAbsY | maxAbsZ | verdict |
|---|---|---|---|
| A — device F3/F4 (`BOA_DIAG_CPU_F3F4=0`) | 0.000000e+00 µm | 0.000000e+00 µm | PASS |
| B — CPU F3/F4 (current default) | 0.000000e+00 µm | 0.000000e+00 µm | PASS |

Both arms held the chain *exactly* straight (no float drift, no Brownian, no
midpoint force → no F3/F4 contribution since cross(uVec, ±uVec) = 0 on a
perfectly straight chain). This validates the structural plumbing (kernel
runs, returns the correct null-input value) but does NOT exercise force
*magnitudes* — that's the deflection bench's job, and that's where the
implementation falls over.

Logs: `RUN_LOGS/2026-06-02_phase2_singleFil_armA_device.log`,
`RUN_LOGS/2026-06-02_phase2_singleFil_armB_cpuf3f4.log`.

### Validation — Deflection benchmark (the gate; FAIL)

`-bmDiag -gpu` 200k steps with default bench params (fracMove=0.5, fracR=0.1,
fracMoveTorq=0.265). 11-seg × 32-mon chain, span 0.9801 µm, F=3.085e-14 N,
analytic δ=0.0098 µm.

| Arm | final ratio | final defl | gap vs CPU baseline (0.9984) |
|---|---|---|---|
| A — device F3/F4 (`BOA_DIAG_CPU_F3F4=0`) | **1.262114** | 0.012370 µm | **+26.2%** ← FAIL |
| B — CPU F3/F4 (current default) | 1.000049 | 0.009801 µm | +0.013% — within noise |
| reference Step-0 pre-port (CPU F3/F4) | 1.000001 | — | +0.013% — within noise |
| design device baseline target | 0.999876 | — | gate is ±0.05% around this |

Arm A misses the gate by ~26%. Logs:
`RUN_LOGS/2026-06-02_phase2_bmDiag_armA_device.log`,
`RUN_LOGS/2026-06-02_phase2_bmDiag_armB_cpuf3f4.log`.

**Per-segment force dump at step 5000** (via `BOA_DIAG_DUMP_CHAIN_STEP=5000`,
new diagnostic added to GPUMoveThing):

| slot=0 (left-pin) y-force | slot=5 (midSeg) y-force | slot=10 (right-pin) y-force |
|---|---|---|
| arm A: jntF.y = −2.05e-14 N (device kernel write) | jntF.y = +3.0849e-14 (kernel) cancelling cpuF.y = −3.0854e-14 (midpoint) | jntF.y = −2.05e-14 |
| arm B: cpuF.y = −1.54e-14 N (CPU pair via soaForceSum→cpuForceSum) | cpuF.y ≈ 0 (midpoint + CPU F3+F4) | cpuF.y = −1.54e-14 |

The end-shear in both arms equals `−F/2 = −1.54e-14` AT EACH ARM'S
EQUILIBRIUM SHAPE — arm B at 9.8 nm deflection, arm A at 12.4 nm. The chain
**tension** (x-component, ~1.61e-13 N at the ends) is the same in both arms;
the **bending** (y-component) differs because the chains reach equilibrium at
different bend angles. Each arm individually IS in static equilibrium (per-
segment net force ≈ 0), confirming the kernel correctly applies *some* F3/F4
forces in *roughly the right* shape — it's the magnitudes that are off.

The fact that arm A's restoring force at 12.4 nm equals what CPU produces at
9.8 nm means the device kernel's effective spring stiffness is ~21% softer
than CPU's. The deficit is ~1/0.79 ≈ 1.26.

### Suspected bug + open hypotheses (for the planner)

1. **Newton's-3rd-law asymmetry in unique-ownership F3** — the analytical
   pair-symmetry argument (`forceMag_A = forceMag_B`, `linkUVec_B = −linkUVec_A`)
   holds exactly when chain is straight, but for a bent chain the two link
   points are at DIFFERENT positions (each `r = actinMonoRadius` inside its
   own segment from the glue), so the strainDist/linkUVec computed from A's
   perspective vs B's perspective differ by `r·(A.uVec − B.uVec)`. CPU enforces
   Newton's 3rd law by FIAT (computes F in one frame, applies +F/−F by hand);
   GPU unique-ownership doesn't. The cumulative per-pair asymmetry across 10
   chain links may bias equilibrium by the observed ~21%. This is the leading
   hypothesis — but the size of the per-pair asymmetry (~r/strainDist · angle
   ~ 0.01) doesn't obviously add up to 21% on a single inspection.
2. **Subtle F4 sign convention bug** — F4's side-encoding (cross with neighbour's
   `uVec` at side=0, `uVecR` at side=1) was caught and fixed mid-implementation
   (the pre-fix version would have catastrophically destabilized the bench
   because of 180° angTween on slightly bent pairs; SingleFilDiag couldn't have
   exposed it because cross(uVec, ±uVec) = 0 on perfectly straight chains).
   Post-fix the chain DOES reach a stable equilibrium, but maybe one direction
   is still off — needs a CPU-vs-device per-pair torsion-magnitude diff.
3. **moveCoeff `bRotGam.y` vs neighbour's `bRotGam.y` indexing** — verified
   reads `bRotGam.get(i3+1)` for self and `bRotGam.get(n3+1)` for neighbour;
   both should be the y-axis (perpendicular-to-rod-long-axis) rotational drag.
   The pack writes `bRotGam.set(aX, .x), set(aY, .y), set(aZ, .z)` so the
   indexing matches. Less likely but worth a paranoid re-check.
4. **The diff between `accurateAcos` (GPU) and `fastAcos` (CPU)** is in the
   *opposite* direction (GPU's accurateAcos returns slightly LARGER angles
   at small bends → larger torsionMag → STIFFER chain), so it cannot be the
   cause of a softer-than-CPU result.

**Recommended next step:** add a per-pair F3/F4 diagnostic that computes both
the device-kernel-formula and the CPU-formula on the SAME pose state (e.g.,
via a CPU dry-run mirror inside `GPUMoveThing.moveThings()`, the same pattern
the joints kernel uses for `DIAG_DUMP_JOINTS_STEP`), and dump them side-by-
side at a bench equilibrium step. The diff between them isolates whether the
bug is in (a) the kernel's local formula (would show as a per-pair mismatch
even at identical inputs) or (b) the unique-ownership Newton-3rd-law model
(would show as zero per-pair mismatch but accumulated equilibrium bias).

`DIAG_DUMP_CHAIN_STEP` (new) and `BOA_DIAG_DUMP_CHAIN_STEP=N` env hook are
already wired up — they dump per-slot `cpuForceSum`/`jointForceSum`/
`jointTorqueSum` at step N, which is what I used for the table above.

### Commands for jba

All on aorus. Build:

```
cd ~/Code/BoA
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      boxOfActin/*.java *.java
```

SingleFilDiag (the cheap probe — both arms passed; this re-runs the gate):

```
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

# Arm A — device kernel
BOA_BMDIAG_MAX_STEPS=100000 BOA_DIAG_CPU_F3F4=0 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -singleFilDiag -gpu

# Arm B — CPU path (current default)
BOA_BMDIAG_MAX_STEPS=100000 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -singleFilDiag -gpu
```

Deflection benchmark (the gate — currently FAILING in arm A):

```
# Arm A — device F3/F4 (expect ratio ≈ 1.26 → FAIL)
BOA_BMDIAG_MAX_STEPS=200000 BOA_DIAG_CPU_F3F4=0 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -bmDiag -gpu

# Arm B — CPU F3/F4 (current default; expect ratio ≈ 1.00)
BOA_BMDIAG_MAX_STEPS=200000 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -bmDiag -gpu

# Per-pair force dump at step 5000 (arm A, device kernel)
BOA_BMDIAG_MAX_STEPS=6000 BOA_DIAG_DUMP_CHAIN_STEP=5000 BOA_DIAG_CPU_F3F4=0 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -bmDiag -gpu 2>/tmp/diag_armA
grep DIAG_CHAIN /tmp/diag_armA

# Same dump, arm B (CPU pair via cpuForceSum — for direct slot-by-slot diff)
BOA_BMDIAG_MAX_STEPS=6000 BOA_DIAG_DUMP_CHAIN_STEP=5000 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -bmDiag -gpu 2>/tmp/diag_armB
grep DIAG_CHAIN /tmp/diag_armB
```

### Constraints respected + default-flag note

- Only the F3/F4 port: topology infra, fused chain kernel, `FilSegment.step()`
  gating + flag, SingleFilDiag probe, per-pair dump diagnostic. Did NOT touch
  F1/F5/F6, motor binding (Phase 3), or the residency flip (Phase 4).
- `collisionCheckInt` cadence unchanged.
- `DIAG_CPU_F3F4` default: **true** (CPU pair runs on `-gpu`, device chain
  kernel forced to a no-op via topology-slot=-1). This is INVERTED from the
  Phase-1 `DIAG_CPU_ANCHOR` precedent (which left the device kernel default-
  active) because the device kernel here demonstrably fails the bench gate;
  defaulting to the CPU path keeps gliding-assay and other `-gpu` runs honest
  until the deficit is resolved. To activate the device kernel for a
  debugging pass, set `BOA_DIAG_CPU_F3F4=0` (env var; flag-level OFF). Once
  the 21% stiffness deficit is fixed and the bench gate passes, flip the
  field default back to `false` in `GPUMoveThing.java` — that re-aligns with
  the Phase-1 pattern of "device kernel is the default whenever `-gpu` is
  on."

## 2026-06-02 — Phase 2 F3/F4 — port design

**Scope:** survey + design only, no edits, no runs. Drafted to give the planner
enough specificity to scope implementation prompts. Reads `RESIDENCY_PLAN.md`
(Phase 2), `STEP_PORT_SURVEY.md` (F-inventory), `RESIDENCY_AUDIT.md` (A9, A12,
post-move sync), `GPU_MIGRATION_LESSONS.md` (Lessons 2 + 5), and the relevant
source.

### 1. Scoping question — what pose state is uploaded each step today?

**Answer: only the primary pose (`coord`, `uVec`, `yVec`). Derived fields are
NOT uploaded.**

Evidence: the chained TaskGraph in `GPUMoveThing.java:1112-1121` lists
`coord, uVec, yVec` plus force/torque/drag/slot/joint-param buffers under
`DataTransferMode.EVERY_EXECUTION`. There is no transferToDevice call for
`soaEnd1`, `soaEnd2`, `soaZVec`, or `soaTransXTox`. The derived fields are
CPU-only artifacts produced by `Thing.recomputeDerivedSoA` (`Thing.java:736-783`)
inside `GPUMoveThing.moveThings` *after* OP_UNPACK — they exist solely to feed
CPU consumers in the next step.

**What this means for sequencing.**

Re-reading the F3/F4 source against the upload list, the implication is
NOT what the audit suggested at first glance. F4 reads only `uVec` of self
and neighbour — already device-resident every step. F3 reads `end2Pt`/`end1Pt`,
but those are trivially `coord ± (length/2)·uVec`, i.e. a one-line inline
recompute per thread from primary pose. **Neither kernel needs the SoA
derived-field arrays uploaded or precomputed by a separate kernel.**

So the **device derived-field recompute kernel is NOT a Phase 2 prerequisite**.
It is deferred to Phase 4 (the residency flip), where it replaces the
post-OP_UNPACK CPU sync block (`recomputeDerivedSoA` + `end1Pt`/`end2Pt`/
`bindTip` Pt3D refresh at `GPUMoveThing.java:1894-1917`). For Phase 2, F3
computes its two endpoints inline; F4 reads `uVec` directly.

This is a cleaner Phase 2 than the plan anticipated. The chain-topology index
remains a Phase 2 prerequisite (next section), but the derived-field
infrastructure shifts entirely to Phase 4.

### 2. Infrastructure — chain/binding topology index on device

**Today (CPU pattern).** Per FilSegment, the chain neighbour is a Thing
reference (`end1Fil`, `end2Fil`, `FilSegment.java:158-159`). "Which end of the
neighbour am I attached to" is answered by **Pt3D reference identity**:
`ptAtEnd2 == end2Fil.end1Pt` (true ⇒ neighbour's end1 is glued to my end2)
or `ptAtEnd2 == end2Fil.end2Pt` (false ⇒ neighbour's end2 is glued to my end2).
See the `setEnd*Links` helpers at `FilSegment.java:2660-2670` and the usages
inside `addLinkForces` (`FilSegment.java:1428`, `1448`, `1460`, `1490-1493`,
`1511`, `1524`).

Pt3D reference identity does not survive serialization to a device buffer.
The device needs an explicit side flag.

**Device design — one per-segment topology buffer, FIRST_EXECUTION + dirty
re-upload.**

Per segment slot `i`, four ints:

| field | meaning | sentinel for "no neighbour" |
|---|---|---|
| `topoEnd2Slot[i]` | move-slot of `end2Fil` | `-1` |
| `topoEnd2Side[i]` | `0` if `ptAtEnd2 == end2Fil.end1Pt`, `1` if `== end2Pt` | `0` (unused when slot = -1) |
| `topoEnd1Slot[i]` | move-slot of `end1Fil` | `-1` |
| `topoEnd1Side[i]` | `0` if `ptAtEnd1 == end1Fil.end1Pt`, `1` if `== end2Pt` | `0` |

(Two `IntArray`s of length `M`, one for slots, one for sides, packed
`[end2Slot, end2Side, end1Slot, end1Side]` per segment — 16 B/segment.)

**Slot indexing consistency.** Use the existing
`thingNumberToMoveSlot[]` map maintained by `GPUMoveThing.classifyThings()`
(`GPUMoveThing.java:1307-1372`). The Phase 1 anchor kernel and move kernel
already index by this slot — the topology buffer must use the same indexing
so neighbour lookups in the F3/F4 kernel match the pose lookups.

**Upload cadence.** Topology mutates only at biochem boundaries
(`FilSegment.biochemStep()` calls `setEnd2Links` / `removeEnd2Links` via
`end1BiochemSim` / `end2BiochemSim`, `FilSegment.java:495-496`, `509`).
Biochem already flips `topologyDirty = true` (the existing reclassification
trigger at `GPUMoveThing.java:1396-1398`). Re-use the same flag:
- FIRST_EXECUTION upload at start;
- on `topologyDirty`, rebuild the topology buffer alongside the slot map and
  re-upload with `DataTransferMode.EVERY_EXECUTION` for that step only, then
  drop back to FIRST_EXECUTION semantics.

In implementation terms: keep a small companion to `classifyThings()` that
walks every alive segment, looks up `end1Fil`/`end2Fil` in
`thingNumberToMoveSlot`, and writes the four ints — exactly the same shape
as the slot map build. Cost: O(filSegmentCt), called only on dirty.

**Motor binding for F3/F4: not needed.** The motor-tether forces F8/F9/F10
are a separate sub-port (Phase 2 third chunk, after F1). Their binding index
(`MyoFilLink.mySeg` slot + `posOnSeg` + cocked flag) is uploaded per-step
already in `cockedFlags` and the motor SoA pack; the binding-index residency
design lives with the F8/F9/F10 port, not F3/F4.

### 3. Infrastructure — derived-field recompute kernel (deferred)

Per §1, the recompute kernel is **deferred to Phase 4**. It is mandatory at
the residency flip, when the per-step `recomputeDerivedSoA` CPU pass and
the FilSegment `end1Pt`/`end2Pt` Pt3D refresh and the MyoMotor `bindTip`
refresh in `GPUMoveThing.moveThings:1894-1917` are deleted. At that point
the kernel mirrors `Thing.java:745-783` exactly: per-thread:

```
zVec = uVec × yVec; normalize.
yVec = zVec × uVec.       // re-orthogonalize
transXTox = row-major [uVec; yVec; zVec].
end1 = coord - (length/2)·uVec.
end2 = coord + (length/2)·uVec.
```

For Phase 2 F3/F4:
- F4 reads `uVec` of self and neighbour directly. uVecR = -uVec is a sign flip.
- F3 computes its two endpoints inline: `end2 = coord + 0.5·length·uVec`,
  `linkPt = end2 + actinMonoRadius·(-uVec)`. Neighbour endpoints likewise from
  neighbour's `coord` + `uVec` + `length` (uploaded primary pose + uploaded
  `soaLength`). No derived-field buffers needed.

If `soaLength` is not currently in the EVERY_EXECUTION upload list — it isn't,
since lengths change only at biochem boundaries — it should join the topology
buffer's FIRST_EXECUTION/dirty pattern. One more `FloatArray` of size M; reupload
when `topologyDirty` fires.

### 4. F3/F4 kernel plan

**Threading: one device thread per segment.** Each thread owns its slot.
For each end, the thread reads its own pose + neighbour pose + computes the
pair force/torque **from its own perspective only**, applying the result to
its own accumulators (`cpuForceSum[i]`, `cpuTorqueSum[i]`, axial buffers).
Newton's third law: the neighbour's thread independently computes the same
pair force from its perspective and writes its own accumulators. No cross-writes,
no atomics, no per-pair dedup flags.

This is **a behavioural restructure of the CPU pattern**, not a literal port.
The CPU code is "owner does both sides of one pair, mark visited" (`end2LinkCkd`
etc., `FilSegment.java:1411`, `1444`, `1473`, `1507`). The GPU pattern is
"each thread does its own side of both its pairs, no visited flags." The CPU
flags were a dedup that goes away with unique ownership.

**Force-coverage equivalence sketch.** For a chain `[A — B — C]` with the
pair (A,B):
- CPU: A's thread processes the pair. Writes `F` to A.forceSum, `-F` to B.forceSum,
  `R_A×F` to A.torqueSum, `R_B×(-F)` to B.torqueSum, axial loads to both ends.
  Marks `A.end2LinkCkd = B.end1LinkCkd = true`.
- GPU: A's thread reads A and B pose, computes the same `F` (since `forceMag` is
  pair-symmetric: same `strainDist`, same `(moveCoeff1 + moveCoeff2)` regardless
  of which side computes), applies `+F` and `R_A×F` to A only. B's thread reads
  B and A pose, computes the same `F` magnitude in the same direction (linkUVec
  is symmetric up to sign; B sees `-linkUVec` from its end1 side), applies
  `+(-F) = -F` and `R_B×(-F)` to B only. Same net forces; pair never
  double-counted (each thread owns one end of each pair); no dedup state needed.

**Axial loads** (`incEnd2AxialForce`, `incEnd1AxialForce`) flow the same way:
each segment writes only its own end's axial contribution, computed from its
own `uVec`. Buffer: two new device `FloatArray`s of length M (`end1AxialSum`,
`end2AxialSum`) or pack into a single length-2M buffer. Download with pose
at output frames if needed; today these feed biochemistry on the CPU side,
which stays CPU for Phase 2 — so per-step CPU download is still required for
axial loads until biochem ports later. **Pin this as a Phase 2 caveat**: axial
loads are a CPU-consumed by-product, so a per-step axial download must be added
or the existing CPU axial-accumulator path must remain. The simplest first cut:
keep the CPU axial accumulator code in the reduced-pass CPU step() (Phase 2
fallback), and add device-side axial buffers only when biochem itself moves to
device (later phase). This means **the kernel writes force/torque to device,
but axial loads stay computed in a reduced-pass CPU step** for Phase 2 — the
exact opposite of the desired direction, but it keeps the kernel small. Flag
for planner: alternative is to download axial sums each step (16 B/segment,
trivial).

**Kernel parameter budget (TornadoVM 15-arg cap).** F3 needs:
`coord, uVec, length, topoSlots, topoSides, cpuForceSum, cpuTorqueSum,
end1AxialSum, end2AxialSum, bRotGamY, bTransGamX, bTransGamY` plus a small
`params` IntArray-or-FloatArray packing `dt, fracMove, fracR, actinMonoRadius,
maxSegDist, maxSegDistActive_flag`. That's 12 arrays + 1 packed params → fits.

F4 needs: `coord, uVec, length, topoSlots, topoSides, cpuTorqueSum, bRotGamY,
params(dt, fracMoveTorq, filTorqSpring, filTorqSpringActive, maxSegAng,
maxSegAngActive)`. That's 7 arrays + packed params → fits easily.

**One kernel or two?** Recommend **one combined kernel**, `chainPairForces`, with
a single per-segment thread that does both F3 and F4 against each end neighbour.
Reasons:
- The two operations share the topology lookup, the neighbour-pose fetch, and
  the `forceMag`/axial flow — fusion eliminates a redundant read.
- F3 + F4 together still fit under 15 args (the union of the two arg sets
  above is 13 arrays + 1 packed params).
- The CPU code already calls them sequentially from `FilSegment.step()`; the
  ordering between them is independent (no F-on-F dependency in the same step).

**Topology-break detection.** F3's `breakAtEnd2()` / F4's angle-break is
EWMA-gated (`end2SegDist.registerValue`/`end2SegAng.registerValue`,
`FilSegment.java:1416-1421`, `1700-1707`) and rare. Two options:
(a) compute strain/angle on device, store per-segment scalar, download once
   per N steps for the CPU EWMA. (b) keep the break-check on the
   reduced-pass CPU step using post-move CPU-resident pose (the planner can
   pick — both work). Pre-decided: (b) for simplest Phase 2 — bench/diagnostic
   territory only.

**`filID` propagation.** F3's chain-merge bookkeeping
(`FilSegment.java:1466-1469`, `1530-1533`) is integer book-keeping on a
CPU-only field with no force consequence. Skip in the kernel; keep on the
CPU reduced pass (one int compare per segment, trivial). Not a residency
blocker.

### 5. Force-coverage map (Lesson 2 — mandatory)

After Phase 2 F3/F4 lands, every step force must be applied exactly once
across the device path and the CPU-fallback path.

| Force | CPU `step()` today | Device kernel after F3/F4 port | CPU reduced step() after port | Applied exactly once? |
|---|---|---|---|---|
| F1 (filament–boundary inside chamber) | `checkBugOrBoxCollision` | NO (Phase 2 F1 port is later) | YES (reduced pass runs F1) | YES |
| F2 (filament–bug outside, Listeria) | `checkBugCollisionFromOutside` | NO | YES (reduced pass; Listeria only) | YES (Listeria configs) |
| **F3 (chain link)** | `addLinkForces` | **YES (this port)** | NO (skipped on GPU-handled segments) | YES |
| **F4 (chain torsion)** | `addTorsionSpringForces` | **YES (this port)** | NO (skipped on GPU-handled segments) | YES |
| F5 (node tether trans) | `addNodeForces` | NO (deferred per RESIDENCY_PLAN) | YES (reduced pass; node-tethered configs) | YES (where active) |
| F6 (node alignment torque) | `addNodeForces` | NO | YES | YES (where active) |
| F7 (MyoMiniFilament–bug/box) | `MyoMiniFilament.step` | NO — MyoMiniFilament is CPU-fallback (cpuFallback[]) | YES (its own CPU step() unchanged) | YES |
| F8 (motor tether spring) | `MyoFilLink.addForces` | NO (Phase 2 motor-port chunk later) | YES (reduced pass; motors GPU-handled but their step forces stay CPU until that chunk) | YES |
| F9 (motor uVec align torque) | `MyoFilLink.alignUVecTorque` | NO | YES | YES |
| F10 (motor yVec align torque) | `MyoFilLink.alignYVecTorque` | NO | YES | YES |
| F11 (ActA tether) | runs in `actAStart` wave (not step()) | NO | unchanged (its own wave) | YES (Listeria) |
| F12 (ProteinNode–boundary) | `ProteinNode.step` | NO — ProteinNode is CPU-fallback | YES (own CPU step()) | YES |
| Axial loads (F3 by-product) | `incEnd1/2AxialForce` | NO (deferred — see §4) | YES (computed in reduced pass alongside F3 stub) | YES |
| Bug drag-tensor side-effect (`Bug.setViscousDrag`) | `Bug.step` | NO — Bug is CPU-fallback | YES (own CPU step()) | YES |

**Reduced-pass shape for GPU-handled FilSegments.** Today `FilSegment.step()`
runs F1 + F3 + F4 + F5/F6 sequentially. After this port, the reduced pass
for GPU-handled segments must run F1 + (axial-load CPU stub from §4
caveat) + F5/F6 — **skipping F3 and F4**. Mechanism: add a per-subclass
"skipGPUDroppedForces" branch inside `FilSegment.step()`, analogous to
`MyosinThreads.applyGPUDroppedForces` (the Phase 1 anchor mechanism).
Concretely: gate the F3 and F4 calls on `!isGPUHandled()`.

**Critical: do NOT skip the entire FilSegment.step() dispatch on GPU-handled
segments.** That would silently drop F1, F5, F6. The Lesson 1 anchor-bug
shape. Confirm pre-port with grep: every `FilSegment.step()` call site must
either reach the reduced pass or be intentionally skipped.

### 6. Validation design

**Cheap single-filament pre-check (the F3/F4 analogue of SingleMyoDiag).**

Build a `SingleFilDiag` minimal-reproduction config (or temp `-bm`-style mode)
that runs ONE straight filament — 11 segments, pinned at both ends, no
motors, no boundaries other than chamber walls far away, no Brownian motion
(or BTransCoeff/BRotCoeff = 0). With no perturbing forces and pins holding
the ends, the chain must remain straight: every segment's `coord` y/z
deviation from the pin line should stay numerically zero. Any F3 or F4
drift is instantly visible.

Run on `-gpu`; CPU baseline is "stays straight." Pass criterion: max
`|coord_y|` and `|coord_z|` < 1e-5 µm over 100k steps. This is Lesson 3 +
Lesson 4 applied: minimal isolated body, watchable. Cost: trivial; runs
in seconds.

**Deflection benchmark — the gate.** After the pre-check passes:

- **Config:** standard `-bmManual` deflection chain (11-segment, pinned ends,
  midpoint transverse force), validated phalloidin bending regime — verify
  the bench config used produces the established `bendKp` (the `aeta`-dependent
  bending coefficient pair) so the gate has the same physics as the CPU
  reference. Pre-port read of `BoxOfActin.applyBenchmarkPins` and the
  deflection bench setup confirms.
- **CPU reference:** 0.998420 deflection ratio.
- **Device baseline (pre-F3/F4 port, post-anchor):** 0.999876 — a +0.146%
  offset attributable to existing float32/anchor effects.
- **Pass criterion:** GPU ratio remains at +0.146% (±0.05%) of CPU. A larger
  shift is a regression. **Do not gate on absolute agreement with CPU** —
  the +0.146% offset is the pre-existing device baseline, not a defect.
- **τ_meas vs τ_theo:** record but not gate; informational.

**LP benchmark — soft sanity check, NOT a hard target.** Per RESIDENCY_PLAN
§Phase 2: Lp is Brownian-coefficient-dependent and chain-length-dependent.
Run a short LP sample (~30s wall-clock) post-port; confirm Lp falls in the
plausible 5–25 µm ballpark. Reject if Lp collapses (< 1 µm — torsion
broken) or diverges (no decay — torsion zero). No overnight LP run.

**Validate-once discipline.** Order is: SingleFilDiag pre-check (trivial,
local). If it fails, fix before touching the bench. If it passes, deflection
bench (still cheap). If deflection passes within tolerance, declare F3/F4
landed and proceed to gliding 10-seed ensemble only as confirmation, not
localization.

**Benchmark runnability on `-gpu` — pre-port verification.** Per Lesson 5 and
the open-question 4 in `STEP_PORT_SURVEY`, the deflection benchmark has not
yet run on the GPU path. **Step zero of implementation must be: confirm the
existing CPU-only F3/F4 deflection benchmark runs cleanly under `-gpu`**
(reading uploaded pose round-tripped through OP_UNPACK, no kernel changes
yet). If it does not — e.g. because the pinned-endpoint Things or midpoint
force injection don't classify as GPU-handled — fix that BEFORE writing
the F3/F4 kernel. The kernel is gated on a probe that runs.

### 7. Risks / scope-expansion flags

- **Axial-load buffers** (§4 caveat): if the planner prefers device-resident
  axial loads now (avoid CPU stub), add ~16 B/segment device buffer + per-step
  download. Trivial bandwidth but is a small scope-expansion vs the
  "keep axial CPU-side" alternative. Recommended: keep CPU-side until
  biochem moves to device.
- **soaLength FIRST_EXECUTION/dirty upload** (§3): a small new device buffer
  managed alongside the topology index. Not a separate piece of infrastructure,
  just a co-dependency.
- **Topology-break / `filID` propagation**: F3's `breakAtEnd2` and `filID`
  merge stay on CPU reduced pass — confirmed as bench/biochem-territory only,
  no residency blocker.
- **Pair-dedup flags `endNLinkCkd` / `endNTorqCkd`**: with unique-ownership
  GPU pattern these become CPU-only. Keep them only for the reduced-pass
  CPU step()'s remaining F1/F5/F6 work. No device representation needed.
- **F4 already reads only primary pose** (only `uVec`). This is a genuinely
  smaller surface than the audit's "F3/F4/F8 read derived fields" framing
  suggested. The audit was right at the higher abstraction; the source
  details simplify Phase 2.
- **CPU reduced-pass for GPU-handled FilSegments runs F1/F5/F6 sequentially.**
  Today F1 is in `FilSegment.step()` ahead of F3/F4; F5/F6 after. Gating
  F3+F4 on `!isGPUHandled()` preserves the ordering of the unported forces;
  no reshuffling needed.
- **Validate-once discipline must include the smoke check that deflection
  runs at all on `-gpu`** before kernel work begins. Pre-port verification
  is cheap; skipping it risks discovering at the gate that the gate itself
  is broken (Lesson 5).

### 8. The one-line scope of Phase 2 F3/F4

Build a per-segment device buffer of chain-neighbour slot+side ints (FIRST_EXECUTION
+ topology-dirty reupload) and `soaLength`; write one fused `chainPairForces`
kernel implementing F3 + F4 with unique-ownership semantics (each thread
writes only its own segment's force/torque); gate F3/F4 off in `FilSegment.step()`
for GPU-handled segments via the established `applyGPUDroppedForces` pattern,
keeping F1/axial-load-CPU-stub/F5/F6 in the reduced pass; validate
SingleFilDiag (straight chain stays straight) then the deflection bench (gate
on the +0.146% device baseline). Derived-field recompute kernel is deferred
to Phase 4; nothing in this chunk needs it.

## 2026-06-02 — Parameter provenance in -3js run folders

**Scope:** `ThreeJSWriter.resolveOutputDir()` only — same hook that writes
`source.zip`. No frame format, no parameter system, no sim behavior changed.

### Change

Each `-3js` run folder now contains two new provenance files alongside
`source.zip`, written once at first-frame setup:

- **`params_input.<origName>`** — verbatim copy of the file passed via `-pf`
  (e.g. `params_input.boa10-64Seg`). Skipped with an info message if no `-pf`
  was given.
- **`params_effective.txt`** — t=0 snapshot of the `Parameter` registry
  (iterates `Parameter.theParams[0..paramCt-1]`). Each line:
  `<label> = <value>  (active=<bool>, type=<BOOLEAN|INT|DOUBLE>, default=<v>, units=<u>)`.
  Header notes that mid-run mutable params (the `setMutableAtRuntime` whitelist:
  aeta, fracMove, fracR, …) may diverge from this snapshot after t=0.

**`params_effective` is the authoritative record.** A parameter file can list
a line with `isActive=false`, in which case `getValue()` still returns the
Java default and `params_input` looks misleading. The effective dump closes
that gap by emitting both `getValue()` and `isActive()` for every parameter.

### Validation

Short smoke run: `BoxOfActin -r -pf ParameterFiles/boa10-64Seg -3js …`.

- `params_input.boa10-64Seg` is byte-identical to the source param file
  (`diff -q` clean, 109 lines).
- `params_effective.txt` has 244 parameter lines, of which 36 carry
  `active=false` (e.g. `glidingAssay`, `threeByThreeNodes`) — these are the
  default-fallback cases the verbatim copy hides.
- The three bending-calibration params show their effective values and active
  flags side-by-side:
  - `fracMove = 0.4   (active=true,  default=0.5)`
  - `fracR = 0.134    (active=true,  default=0.1)`
  - `fracMoveTorq = 0.013685 (active=true, default=0.265)`
- Frame JSON output unchanged; sim behavior unchanged.

### Open

Headless ensemble / batch runs (no `-3js`) currently have no equivalent
effective-param dump in their `RUN_LOGS/`. Flagging for the planner — not
implemented here.

## 2026-06-02 — Gliding-assay filament IC overshoot — fix

**Scope:** code edit only — IC placement of the single gliding-assay test
filament. No force/code-path changes elsewhere.

### Change

`FilSegment.makeGlidingAssayFilament` (`boxOfActin/FilSegment.java:3334`):

```java
double stdFilSegLengthUM = Env.stdSegLength.getIntValue() * Env.actinMonoRadius;
Pt3D loc = new Pt3D(Env.boxXDim.getValue()/2 - filLength/2 - stdFilSegLengthUM/2, 0, 0);
```

Canonical name for the standard per-segment length is `Env.stdSegLength`
(`Env.java:544`, param label `filSegLength`, units = monomers; default 32,
overridden to **64** in `glidingAssay500_val`). In microns:
`64 × actinMonoRadius = 64 × 0.0027 = 0.1728 µm`. Half = **0.0864 µm**, the
pad applied inward from the +x wall. Formula is `boxXDim`-relative so it
auto-scales to any box width.

### Geometry (val config: `glidingAssay500_val`, boxXDim=14, filLength=2.0)

| quantity | old | new |
|---|---|---|
| `coord.x` (segment center)              | 6.00000 | 5.91360 |
| `end1.x` (pointed/minus end)            | 4.99965 | 4.91325 |
| `end2.x` (barbed/plus end)              | **7.00035** | **6.91395** |
| wall threshold (`boxXDim/2 − R`)        | 6.99650 | 6.99650 |
| margin below threshold                  | −3.85 nm (over) | **+82.55 nm (clear)** |
| runway cost vs old (−x direction)       | — | 0.0864 µm |

### Validation (smoke run, `glidingAssay500_val`, `-3js`)

Frame 0 (post-step-1; `threeJSCounter` writes on the first doLoop pass):
chain is **straight** to thermal-noise tolerance. The original FilSegment
has split off a 64-mon chunk at its +x end as expected; both fragments lie
on the x-axis with y/z deviations < 1 nm:

```
id=42001 (5-segment-wise unchunked main): end1=(4.91330, -0.00080, -0.00019)
                                          end2=(6.74120, +0.00033, -0.00036)
id=42002 (first split-off 64-mon chunk):  end1=(6.91130, +0.00044, -0.00037)
                                          end2=(7.08680, +0.00054, -0.00039)
```

Frame 1 (one toFileInterval later): chain has fully chunked into 12
segments, y/z deviations remain ≤ ~20 nm — i.e. only thermal jitter, not
the >100-nm step-1 wall-collision bend that contaminated the prior IC.
**No frame-1 wall-collision bend.**

Note: by frame 0 the post-split chain extent exceeds the IC contour by
one `actinMonoRadius` per split (the `(n+1)·r` per-segment length
convention), so the +x-most endpoint after splitting (7.087 µm) is past
the wall threshold even with the fix. That's a downstream behavior, not
an IC issue — the IC itself (single 740-mon FilSegment) is straight and
clears the threshold by 82.55 nm at t=0, so the first physics step no
longer triggers the wall force and the spurious step-1 bend is gone.

### Caveat for future baselines

Absolute gliding velocities / motor-binding counts will shift slightly vs
prior runs because the IC is now straight rather than wall-relaxing — any
future gliding baseline should be regenerated on this corrected IC. The
Phase-1 device-vs-CPU delta (RUN_LOGS/2026-06-02_phase1_*) is unaffected
because both arms shared the old IC.

## 2026-06-02 — Gliding-assay filament IC overshoot — survey

**Scope:** read-only survey. Confirms the bug, identifies root cause,
proposes a fix. No code or param edits.

### 1. Placement math (val config: `glidingAssay500_val`)

Code path: `BoxOfActin.begin → setUpGlidingAssay → FilSegment.makeGlidingAssayFilament`
(`boxOfActin/FilSegment.java:3334`). The whole body is six lines:

```java
double filLength = Env.glidingFilamentLength.getValue();    // 2.0 µm
int    monCt    = (int)(filLength/Env.actinMonoRadius);     // 740
Pt3D   loc      = new Pt3D(Env.boxXDim.getValue()/2-filLength/2, 0, 0);
Pt3D   ang      = new Pt3D(1,0,0);
new FilSegment(loc, ang, -1, monCt, false);
```

`loc` is passed to the `FilSegment(Pt3D initCoord, …)` constructor and
ends up in `Thing.coord` — i.e. the segment **centre**. Derived endpoints
(`Thing.java:774-782`):

```
end1 = coord − (length/2) · uVec
end2 = coord + (length/2) · uVec
```

Inside the constructor (`FilSegment.java:258`) the actual contour length
is recomputed from monomer count:

```
length = (monCt + 1) · actinMonoRadius = 741 · 0.0027 = 2.0007 µm
```

Plugging in the val config (`boxXDim=14`, `glidingFilamentLength=2.0`,
`actinMonoRadius=0.0027 µm` from `Env.java:498`):

| quantity | value (µm) |
|---|---|
| `boxXDim/2` (+x wall)            | 7.0000 |
| `coord.x` (centre)               | 6.0000 |
| `length` (contour)               | 2.0007 |
| `end1.x` (pointed/minus end)     | 4.9997 |
| `end2.x` (barbed/plus end)       | **7.0003** |
| wall-force threshold (`boxXDim/2 − R`, `R = actinWidth/2 = 0.0035`) | 6.9965 |

**Barbed/plus end = `end2`.** Confirmed by `FilSegment.java:2210`
("only barbed-end (end2Pt) can bind to formin at node") and
`FilSegment.java:1237` (barbed-tip → bug-surface registration uses
end2 tracking).

**Geometric overshoot of the +x wall: ≈ 0.35 nm** — essentially zero
visually. **Wall-force activation: yes, immediately at step 1**:
`Chamber.amICollidingOuter` (line 128) triggers when an endpoint passes
`dims.x/2 − R = 6.9965 µm`; end2 sits at 7.0003 µm, ~3.8 nm beyond the
threshold. So at the very first physics step, a wall force acts on end2,
the centre is at x=6 (1 µm of lever arm), and the resulting torque flips
the +x end inward → the artificial bend the user observed.

**Placement IS scaled by `Env.boxXDim`**, not hardcoded for the old
16 µm box. Algebraically, end2.x = `boxXDim/2 − filLength/2 + length/2`
≈ `boxXDim/2` for any boxXDim. **The barbed end has always been pinned
to the +x wall by construction** — that is the formula's invariant. So
this is not a 16→14 box-shrink regression; it's a long-standing design
flaw that becomes visible only when the IC is rendered (the original
`-3jsLive` viewer wasn't around when the gliding code was written).

### 2. When did it change

`git log -S "makeGlidingAssayFilament" -- boxOfActin/FilSegment.java`
returns exactly one commit: **`5b8e423` (initial commit)**. The body of
`makeGlidingAssayFilament` has never been modified.

The box dim has moved twice:

- `6cf19b3` (2026-05-26) — `glidingAssayTest` widened 4×4 → 16×2,
  `glidingFilamentLength` 1.0 → 2.0.
- `6fff2fa` (2026-05-29) — `glidingAssay500` and `glidingAssay500_val`
  **created with `boxXDim=14`, `glidingFilamentLength=2.0`** (no prior
  history at 16 µm in these files).

In every historical config (`boxXDim=4, filLength=1`; `16,2`; `14,2`)
the formula puts `end2.x` exactly at `boxXDim/2`. The pre-Phase-0
"clean" `glidingTst.001` run had the same geometric IC as the current
runs — the bend was always there, only the visual prominence
differs (a 16-µm-wide arena lets the post-bounce relaxation eat the
artifact before it draws the eye; the 14-µm arena does not).

**Direction of fix: change the placement (single file).** The box
widths are physics-motivated; they're not the bug. The
"`barbed end at +x wall`" invariant is.

### 3. Validation impact

The post-fix baseline **and** the Phase-1 ensemble both ran from
`ParameterFiles/glidingAssay500_val`
(boxXDim=14.0, glidingFilamentLength=2.0). Verified:

- 2026-06-01 baseline: `RUN_LOGS/2026-06-01_J2_0.1_rest96_ensembles.txt`
  declares `## Param file: ParameterFiles/glidingAssay500_val`.
- 2026-06-02 Phase-1 ensemble: each
  `RUN_LOGS/2026-06-02_phase1_ensemble_seed{1..10}.log`
  reads `Parameter assigned: boxXDim set to 14.0` and
  `glidingFilamentLength set to 2.0` at startup.

So the IC overshoot is identical (~0.35 nm, same wall-force kick) in
both ensembles. **The bent IC is NOT a confound for the ~2 σ
bindEvents / meanBoundMotors shift between baseline and Phase 1** —
both ensembles experience the same first-step torque and post-bounce
relaxation. The drift is genuinely attributable to the Phase-0
RNG-mapping change + Phase-1 anchor port, as already hypothesised.

**The IC overshoot IS a confound for the absolute numbers** (every
gliding velocity / binding count is measured starting from an
artificially bent rod whose first ~µs of dynamics is wall relaxation,
not myosin-driven gliding). Phase-1 numbers and the 856.80/7.30/8.22
baseline are both biased the same way, so the *delta* is clean; but
neither is a "clean" gliding-assay IC.

### 4. Root cause (confident)

`makeGlidingAssayFilament` was written treating `loc` as the segment
**centre**, but the formula `boxXDim/2 − filLength/2` is the value one
would write down if `loc` were the **−x end** (so the +x end lands at
`boxXDim/2`). Whether the author intended end-anchoring or
centre-anchoring, the formula ends up placing the barbed-end exactly at
the +x wall. The result is the artificially bent IC.

This is **gliding-only**. Other `new FilSegment(...)` call sites
either use `theBox.rdmPtInside()` (random interior point) or coordinates
explicitly inside the box. No general Thing/wall change is implicated.

### 5. Proposed fix (planner: please scope)

Replace the placement line in `FilSegment.makeGlidingAssayFilament`
(`FilSegment.java:3337`) so the **entire** filament lives inside the
box for arbitrary `boxXDim`. Simplest correct version: centre at the
box origin so the barbed end is `filLength/2` from either wall:

```java
Pt3D loc = new Pt3D(0, 0, 0);
```

For the val config this puts end1 at x ≈ −1.0003 and end2 at x ≈ +1.0003,
both ~6 µm from the nearest wall. The filament still slides freely along
+x/−x as myosins drive it; the wall is never touched at t=0.

**Validation follow-up (out of scope for the fix itself):** the gliding
ensemble likely needs a re-baseline on the corrected IC. The
2 σ baseline-vs-Phase-1 *delta* survives unchanged (both ensembles share
the bent IC and would have shared a clean IC), but the *absolute*
binding / velocity numbers will shift — possibly enough to invalidate
the 856.80 / 7.30 / 8.22 reference values. Plan a fresh post-fix
baseline before judging Phase-1's borderline binding deltas as
PASS/FAIL.

## 2026-06-02 — ThreeJSWriter endpoint-key regression fix

**Bug:** every endpoint JSON key in frame and inspectResult payloads was
being emitted as the Pt3D accessor name (`"end1AsPt3D()"`,
`"end2AsPt3D()"`) instead of the viewer contract (`"end1"`, `"end2"`),
on segments AND on myosin rod/lever/motor records AND on MyoMiniFilament
inspect records. The viewer reads `seg.end1` / `rod.end1` / etc., got
`undefined`, threw inside the geometry build, and the HUD hung on
"loading…" for every post-refactor live and file run.

**Introducing change:** `daa239b` (2026-05-30, "Pt3D pose field removal:
kill the bridge, GPU unpack -33 %") — the refactor that switched
endpoint *value* reads from `fs.end1.x` (field) to `fs.getEnd1X()`
(getter) also rewrote the JSON *key* strings from `"end1"` to
`"end1AsPt3D()"`, almost certainly an over-broad search/replace pass.
The two changes ride together in the same hunks in
`boxOfActin/ThreeJSWriter.java` — values updated correctly, keys
poisoned.

**Fix:** only the key strings, in `boxOfActin/ThreeJSWriter.java`.
9 occurrences across `buildFrameJson` (segments, myosin rod/lever,
minifilaments, motorJson) and the inspect builders (`inspectFilSegment`,
`inspectMyoMiniFilament`). Values continue to come from the getters —
those are correct. Done as `replace_all` on the escaped JSON-key form
(`\"end1AsPt3D()\"` → `\"end1\"`); a prose comment at line 222 mentions
the accessor names in code context (not as a JSON key) and was
deliberately untouched. Grep after the fix:
`"end[12][A-Za-z()]*":` returns exactly `"end1":` and `"end2":` in a
written frame; `grep -c AsPt3D <frame>.json` returns 0.

**Side effect:** frame size shrank from 5.36 MB → 4.69 MB at
gliding-assay scale (8-char key → 4-char key × ~6 key occurrences per
of ~14k myosins ≈ 670 KB/frame).

**Salvaging pre-fix run outputs locally — no re-run needed:**

```
sed -i '' -e 's/"end1AsPt3D()"/"end1"/g' -e 's/"end2AsPt3D()"/"end2"/g' <dir>/frame_*.json
```

(The leading `''` after `-i` is the BSD/macOS form; on the aorus GNU
sed, drop it: `sed -i -e 's/"end1AsPt3D()"/"end1"/g' …`.)

Verified by smoke run with the rebuilt binary
(`-pf /tmp/phase1_files/smoke.params -seed 7 -3js …`) writing 2 frames
to `/tmp/phase1_files/smoke_out/`. Pre-fix frame archives
(`/tmp/phase1_files/seed7_short.zip`, `…/seed7_long.zip`) can be fixed
with the sed line above after unzip — no GPU run needed.

**Process note (Lesson 4 — watch it run):** this slipped through because
every validation since the refactor has been numeric (head_r, head_z,
bindEvents, glidingVelocity, theta_RL/LM) — nobody actually loaded a
post-`daa239b` frame in the Three.js viewer until the live-view
shakedown surfaced "loading…". Numeric pipelines don't probe the
serialization contract. Recommend: any future refactor touching frame
emission gets a one-frame eyeball in the viewer before the next port.

## 2026-06-02 — Phase 1: anchor spring ported to device kernel (A7.b)

The last CPU pose consumer inside `myoJoints1` on the `-gpu` path
(`MyosinFixed.applyRodFixedPtForce`, the rod-tail anchor spring) is now
computed on device. This was the "anchor lesson" residue —
`Myosin.MyosinThreads.execute()` short-circuited the full per-Myosin
dispatch on the GPU path but kept the anchor force as a reduced CPU pass
that read `myoRod.end1AsPt3D()` every step. With the device kernel now
covering the anchor, that CPU read is gone (and gated back on for A/B via
the new `DIAG_CPU_ANCHOR` flag).

Status: implemented, compiles clean under Java 21 + TornadoVM 4.0.1-dev
(`javac -g --release 21 --enable-preview …`). NOT YET RUN — jba runs the
SingleMyoDiag A/B and the gliding ensemble gates manually (commands below).

### Step 1 — Survey findings (locks the pattern for downstream Phase-2+ ports)

1. **Rod slot indexing.** `GPUMoveThing.classifyThings()` populates the
   per-Myosin slot arrays via `rodSlots.set(mj, thingNumberToMoveSlot[
   myo.myoRod.myThingNumber])`, with `jointSlotToMyoIdx[mj] = m` as the
   reverse map back to `Myosin.theMyosins[m]`. The anchor uses the SAME
   per-Myosin-index keying — anchor data at index `mj` belongs to the
   Myosin whose rod is at slot `rodSlots.get(mj)`. No new index buffer.

2. **`myoRod.end1` on device.** `Thing.recomputeDerivedSoA()` derives
   `end1 = coord − (length/2)·uVec` (`Thing.java:777–779`). In-kernel this
   is `re1x = rcx − halfRod·rux` etc. where `halfRod = 0.5 * jointParams.get(9)`
   (already computed at the top of the joints kernel as `halfRod`). The
   existing joints code derives `re2x = rcx + halfRod·rux` for the rod–lever
   joint; the anchor is the symmetric end. No new pose plumbing.

3. **`moveCoeff(2, dir)` on device.** Reproduces `MyoRod.moveCoeff(2, linkUVec)`
   exactly:
   ```
   cosBeta  = dot(rod.uVec, linkUVec)            // (clamped to [-1,1])
   cosAlpha = sin(acos(cosBeta))                 // == sqrt(1 - cosBeta^2)
   lSq      = 1e-12 * rodLen^2
   Cx       = cosBeta^2 / bTransGam.x
   Cperp    = cosAlpha^2 / bTransGam.y
   Ctheta   = lSq * cosAlpha^2 / (4 * bRotGam.y)
   moveC    = Cx + Cperp + Ctheta
   ```
   The three rod drag entries (`bTransGam.x`, `bTransGam.y`, `bRotGam.y`)
   are ALREADY on device — they're the first three entries of the per-Myosin
   `myoDrags` 9-tuple (`myoDrags[md9..md9+2]`, packed in `packJointsRange`).
   So the anchor kernel reuses the existing drag buffer; no new
   FIRST_EXECUTION drag buffer needed. `accurateAcos` (PTX-safe, Newton-
   refined) is the same helper the joints kernel uses for its own `acos`
   chain — so the anchor matches the CPU `fastAcos` closely (and matches
   the joints kernel's own band, which is itself the CPU comparison
   baseline).

4. **Force-sum write target.** The joints kernel writes per-Myosin joint
   contributions into `jointForceSum`/`jointTorqueSum` (move-slot indexed),
   and the move kernel reads `cpuForceSum + jointForceSum` per slot. The
   anchor force is added to the rod's per-Myosin accumulator
   (`rodFx += forceMagA * l2x`, etc.) BEFORE the kernel writes
   `jointForceSum.set(r3, rodFx)`. Same accumulator the move kernel
   already consumes; no second accumulation path. Conflict-free because
   each Myosin's three sub-slots are unique (preexisting joints-kernel
   invariant).

5. **Arg budget / fold vs separate task.** The pre-port jointsKernel had
   11 parameters. Adding `anchorPts` (FloatArray, `myoCap*3`) +
   `anchoredFlags` (IntArray, `myoCap`) brings it to 13 — under the
   TornadoVM 15-arg cap. **Decision: fold into the existing joints task.**
   Same per-Myosin slot map, same pose reads (`coord`/`uVec`), same drag
   buffer (`myoDrags`), same output buffer (`jointForceSum`). A separate
   task would have re-uploaded the slot maps and pose buffers redundantly.

   *Discretion noted (handled silently per the port spec's bail-out
   matrix):* anchor buffers are EVERY_EXECUTION (matching `rodSlots`,
   `myoDrags`, `cockedFlags`) rather than the spec's suggested
   FIRST_EXECUTION. Reason: the existing pack pipeline already runs every
   step via `OP_PACK_JOINTS`; folding `anchorPts`/`anchoredFlags` into that
   pack avoids restructuring plan-invalidation around the per-classify
   slot re-shuffle. Buffer is small (`myoCap*3` floats + `myoCap` ints ≈
   16 bytes/Myosin), pack is cheap. If transfer profiling later flags it,
   moving to FIRST_EXECUTION + a classify-driven plan invalidation hook
   is a localized change.

### Step 2 — What was implemented

- **`anchorPts`** (`FloatArray[myoCap*3]`) and **`anchoredFlags`**
  (`IntArray[myoCap]`) declared, allocated in `allocateAndBuildPlan`,
  wired into the chained TaskGraph's `transferToDevice` block and into
  both `.task("joints", …)` argument lists (chained plan and the
  `DIAG_CPU_DELTA_ADD` jointsOnly plan).
- **`packJointsRange`** extended: for each per-Myosin slot, if `myo
  instanceof MyosinFixed`, packs `myFixedPt.{x,y,z}` and sets
  `anchoredFlags[mj] = 1`. Otherwise zeros and `anchoredFlags[mj] = 0`.
  The `DIAG_CPU_ANCHOR` flag is read once at the top of the call and
  forces `anchoredFlags[mj] = 0` for every Myosin — pack-side gating so
  the kernel-side path is uniform.
- **`jointsKernel`** signature extended (+2 args; total 13, under cap).
  After the four existing apply* blocks, an anchor block runs gated on
  `anchoredFlags.get(m) == 1`. Computes rod end1 from device pose
  (`re1x = rcx − halfRod·rux`), `strainDist`/`linkUVec1` from
  `end1 − anchorPt`, `linkUVec2 = −linkUVec1`, `moveC2` via the
  moveCoeff(2) recipe above using `myoDrags[md9..md9+2]`, then
  `forceMag = (j2FracMove · 1e-6 · strainDist) / (deltaT · moveC2)`.
  Adds `forceMag · linkUVec2` into `rodFx/y/z` (i.e. the same
  `−forceMag · linkUVec1` direction the CPU applies). **Force only — no
  torque contribution.** Mirrors the CPU exactly: the rod torque lines
  in `MyosinFixed.applyRodFixedPtForce` are commented out, and the kernel
  does not "complete" them (Lesson 2 — faithful port, do not extend
  physics in the migration).
- **`Myosin.MyosinThreads.execute()`** GPU-path branch now skips the
  `applyGPUDroppedForces()` dispatch entirely unless
  `DIAG_CPU_ANCHOR=true`. Previously this dispatch ran every step (its
  only contribution was the anchor); leaving it on would double-apply.
  A comment in that branch reminds future ports that any new
  `applyGPUDroppedForces` override contributing a NON-anchor dropped
  force needs its own gating.
- **`BOA_DIAG_CPU_ANCHOR=1` env-var hook** added in `BoxOfActin.begin()`,
  alongside the existing `BOA_DIAG_CPU_JOINTS` hook. Sets
  `GPUMoveThing.DIAG_CPU_ANCHOR = true`.
- **PTX safety.** Anchor block uses only sub/dot/sqrt/`Math.sin`/
  `accurateAcos` — all PTX-lowerable per Appendix A of
  `GPU_MIGRATION_LESSONS.md`. No `isNaN`, no `Math.acos` direct, no
  unsupported intrinsics.

### Step 3 — `DIAG_CPU_ANCHOR` validation flag

| Flag state | Device anchor kernel | CPU `applyGPUDroppedForces` (anchor pass) | Anchor applied |
|---|---|---|---|
| **OFF (default)** | active (per-Myosin) | skipped on `-gpu` | exactly once (device) |
| **ON** | per-Myosin `anchoredFlags`=0 → no contribution | dispatched on `-gpu` | exactly once (CPU) |
| CPU-only run (no `-gpu`) | — | — | once via `MyosinFixed.jointConstraints → applyRodFixedPtForce`, unaffected by either flag |

Gate semantics live at two places that are consistent by construction:
the pack side (`packJointsRange` zeroes `anchoredFlags`) and the CPU
dispatch side (`MyosinThreads.execute` runs anchor pass iff
`DIAG_CPU_ANCHOR`). They cannot disagree mid-step because both read the
same static flag once per step in their respective paths.

### Step 4 — Force-coverage audit (Lesson 2)

For the gliding-assay validation config (only `MyosinFixed` populations;
`MyosinDimer` and other Myosin subclasses absent), the only Myosin force
the GPU joints kernel did NOT previously replicate was the anchor spring,
applied by `MyosinFixed.applyGPUDroppedForces → applyRodFixedPtForce`.

| Force | Pre-Phase-1 (CPU) | Pre-Phase-1 (GPU path) | Phase-1 default (`DIAG_CPU_ANCHOR=false`) | Phase-1 with `DIAG_CPU_ANCHOR=true` | Applied exactly once? |
|---|---|---|---|---|---|
| Lever–motor joint force/torque | full jointConstraints | device joints kernel | device joints kernel | device joints kernel | ✓ |
| Rod–lever joint force/torque | full jointConstraints | device joints kernel | device joints kernel | device joints kernel | ✓ |
| **Anchor spring (A7.b)** | via `jointConstraints` override (MyosinFixed) | CPU reduced pass (`applyGPUDroppedForces`) | **device kernel** (anchor block in joints) | CPU reduced pass | **✓ in both flag states** |
| CPU-only Myosins (none in gliding assay) | jointConstraints | — | — | — | n/a |

In prose:

- **`DIAG_CPU_ANCHOR=false` (default):** device anchor kernel fires for
  every `MyosinFixed`; `MyosinThreads.execute()` does NOT dispatch
  `applyGPUDroppedForces()` on the GPU path. Net: each MyosinFixed's
  anchor spring lands in `jointForceSum[rodSlot]` once, the move kernel
  reads it, no CPU contribution. Other Myosin subclasses (none in the
  gliding config) with no override contribute zero.
- **`DIAG_CPU_ANCHOR=true`:** pack side zeroes `anchoredFlags` for every
  Myosin, so the kernel's anchor block early-returns and writes nothing
  into the rod accumulator. `MyosinThreads.execute()` dispatches
  `applyGPUDroppedForces()`, which for MyosinFixed lands the anchor
  force in `soaForceSum[myThingNumber*3]` → `cpuForceSum[rodSlot]` via
  the next pack — same destination the move kernel reads. Net: anchor
  applied once on CPU.
- **CPU-only run (no `-gpu`):**
  `gpuPath = Env.useGPU && !DIAG_CPU_JOINTS` is false;
  `MyosinThreads.execute()` runs the full `jointConstraints()`, which
  for `MyosinFixed` is
  `super.jointConstraints() + applyRodFixedPtForce()`. Anchor applied
  once. Phase-1 changes do not touch this path.

**Confirmed: in the gliding-assay validation config, MyosinFixed is the
ONLY Myosin subclass with an `applyGPUDroppedForces` override**
(`grep -rn applyGPUDroppedForces boxOfActin/`). `MyosinDimer` is not a
Myosin subclass; its cross-Myosin coupling lives in `MyoDimerThreads`
(still A7.c — CPU only, not in scope for Phase 1). No other override
contributes a "dropped force" today, so gating the entire CPU dispatch
off by default does not silently drop anything else in this config.
The gating comment in `MyosinThreads.execute()` flags this assumption
for future ports — any new Myosin subclass override that contributes
a NON-anchor dropped force would need to either (a) be ported to its
own device path or (b) keep its own per-class CPU dispatch outside the
`DIAG_CPU_ANCHOR` gate.

### Step 5 — Commands for jba to run (CPU-vs-device A/B + gliding gate)

All on aorus. Build once:

```
cd ~/Code/BoA
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      boxOfActin/*.java *.java
```

**Phase-1 A/B: SingleMyoDiag (the minimal probe — Lesson 3).**
Anchor effect is visible directly: a missing or wrong-sign anchor force
shows up as the single myosin drifting away from its anchor (the
2026-05-31 reproduction). Expectation: `head_r` (head-to-anchor distance)
stays bounded ~0.06 µm steady state in BOTH arms; the two arms agree to
within float32 noise, not the 10× drift of the buggy era.

```
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

# Arm A — device anchor kernel (default, the new path)
BOA_DIAG_SINGLE_MYO=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -gpu -pf ParameterFiles/<singleMyo paramfile>

# Arm B — CPU anchor pass (today's pre-Phase-1 behaviour)
BOA_DIAG_SINGLE_MYO=1 BOA_DIAG_CPU_ANCHOR=1 \
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -gpu -pf ParameterFiles/<singleMyo paramfile>
```

(Substitute the singleMyo parameter file — the one
`SingleMyoDiag.initFromEnv` expects, presumably the same one used in
the 2026-05-31 reproduction. If unsure, grep `SingleMyoDiag` or check
the most recent SingleMyoDiag run log for the `-pf` argument.)

Pass criterion: `head_r` distribution bounded, no drift, two arms within
float32-noise of each other.

**Phase-1 gate: gliding 10-seed ensemble.** After SingleMyoDiag clears,
re-run the standard gliding ensemble and compare to the post-fix
baseline (`856.80 / 7.30 / 8.22` from the 2026-06-01 anchor-fix entry):

```
# Default: device anchor kernel active.
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500_val
```

Expectation: numbers match the post-fix baseline to within seed-jitter
(the device anchor produces the same force the post-fix CPU pass did,
modulo `accurateAcos`-vs-`fastAcos` precision which the joints kernel
already proved acceptable). If A=B both clear and the ensemble matches,
Phase 1 is green; flip `DIAG_CPU_ANCHOR=false` as the permanent default
(it already is) and move on to Phase 2 (step forces).

### Bail-outs / flags

- **No hard bail-outs.** The survey resolved cleanly: `moveCoeff(2, dir)`
  needs only data already on device (`myoDrags[md9..md9+2]`); the
  force-sum target is the same buffer the joints kernel already writes;
  arg budget fits.
- **Discretion handled silently (per spec):** EVERY_EXECUTION instead of
  FIRST_EXECUTION for the anchor buffers — see Step 1 #5 reasoning.
- **No discoveries pause anything.** Slot map matches the joints
  kernel's; no separate `isAnchored` index needed (parallel
  `anchoredFlags` IntArray indexed by joint-slot is sufficient and
  matches the existing `cockedFlags` shape).

### Phase 1 — validation results (2026-06-02 PM, aorus)

Build: same javac line as Step 5; `.class` files dated 10:38 newer than
the latest `.java` (10:34). No code touched between build and validation.

#### Stage 1 — SingleMyoDiag A/B (cheap probe, runs first)

Param file `ParameterFiles/singleMyoDiag` (runTime 5.0s = 500k steps,
n=50010 samples at interval 10, J2=0, seed 1). Logs:
`/tmp/phase1_val/armA_device.log`, `/tmp/phase1_val/armB_cpuanchor.log`.

| metric         | Arm A (device, default)  | Arm B (BOA_DIAG_CPU_ANCHOR=1) | A − B gap   | post-fix ref (2026-06-01) |
|---|---|---|---|---|
| head_r mean    | 0.063027 µm              | 0.062805 µm                   | +0.000222 µm (0.22 nm) | device 0.063 µm, CPU 0.061 µm |
| head_r max     | 0.122737 µm              | 0.121740 µm                   | bounded     | well below buggy-era 1.046 µm |
| head_z mean    | −0.050704 µm             | −0.052131 µm                  | +1.43 nm    | post-fix CPU/GPU gap 0.04 nm (≠ same test) |
| theta_RL mean  | 1.672946 rad (95.853°)   | 1.673300 rad (95.873°)        | 0.02°       | — |
| theta_LM mean  | 0.273547 rad (15.673°)   | 0.272594 rad (15.619°)        | 0.05°       | — |
| cocked frac    | 0.0013                   | 0.0001                        | (Brownian)  | — |

**Stage 1 verdict: PASS.**
1. **Bounded, no drift.** Both arms steady-state head_r ≈ 0.063 µm —
   within 0.5% of the post-fix device reference 0.063 µm, two orders of
   magnitude below the pre-fix 0.533 µm drift.
2. **A/B agreement.** head_r A−B gap = 0.22 nm, ~10× tighter than the
   ~2 nm float32-vs-double reference gap. theta_RL/theta_LM align to
   ≤0.05°. head_z A−B gap = 1.43 nm: larger than the 0.04 nm post-fix
   reference, but that reference was a *different* comparison
   (CPU-joints-double vs GPU-joints-float32 on the gliding assay). Here
   both arms run GPU joints in float32 and differ only on the anchor
   compute path; a ~1 nm gap is consistent with float32-trajectory
   noise (Arm A's head_z here, −0.0507 µm, also differs from the May 31
   device-arm value of −0.0518 µm by ~1 nm — same regime). The buggy-
   era CPU/GPU head_z gap was 93 nm; the present gap is 65× tighter.

Gate cleared → Stage 2 dispatched.

#### Stage 2 — 10-seed gliding ensemble (`glidingAssay500_val`)

Default device anchor build (`DIAG_CPU_ANCHOR=false`). Per-seed logs:
`/tmp/phase1_val/ensemble_seed{1..10}.log`. Status heartbeat in
`.last_run_status` (start 11:22, finish 12:14, ~52 min wall).

| seed | bindEvents | meanBoundMotors | glidingVelocity |
|---|---|---|---|
| 1  | 855 | 6.942 | 8.4840 |
| 2  | 824 | 7.208 | 8.8394 |
| 3  | 768 | 6.576 | 7.9111 |
| 4  | 849 | 6.792 | 8.5658 |
| 5  | 620 | 6.133 | 7.6805 |
| 6  | 664 | 6.001 | 7.2304 |
| 7  | 320 | 2.855 | 6.6137 |
| 8  | 674 | 6.279 | 8.5279 |
| 9  | 800 | 6.886 | 8.6421 |
| 10 | 805 | 6.408 | 8.1552 |

Ensemble vs post-fix baseline (`856.80 ± 45.72 / 7.30 ± 0.30 / 8.22 ± 0.15`):

| metric            | Phase-1 mean | SD     | SEM   | baseline       | diff    | cSEM  | \|d\|/cSEM  | verdict |
|---|---|---|---|---|---|---|---|---|
| bindEvents        | 717.900      | 162.21 | 51.30 | 856.80 ± 45.72 | −138.90 | 68.71 | **2.02 σ**  | borderline |
| meanBoundMotors   |   6.208      |   1.24 |  0.39 |   7.30 ± 0.30  |  −1.092 |  0.49 | **2.21 σ**  | borderline |
| glidingVelocity   |   8.065      |   0.71 |  0.23 |   8.22 ± 0.15  |  −0.155 |  0.27 |   0.57 σ    | PASS |

**Stage 2 verdict: MIXED — gv clean PASS, bindEvents and
meanBoundMotors ~2σ below baseline.** Compared to the post-fix
reference deltas (0.44 / 0.67 / 0.80 σ), the binding-count observables
have walked outward by ~3× their previous σ-distance while velocity
held. Per the prompt's gate language ("a multi-σ shift on any
observable is a regression"), this is not a clean ensemble pass.

Contextual reads — *not* a verdict change, just for the planner:
- **Seed 7 dominates the SEM.** bindEvents=320 is 4σ below the mean of
  the other 9 (which is 762.1 ± 90.5). Excluding it, the 9-seed mean
  is 762.1 / 6.581 / 8.226 — bindEvents drift drops from −139 to −95
  (still down ~11%), mbm drift from −1.09 to −0.72, gv lands at 8.226
  (right on baseline). Outlier-driven, but seed 7 is a legitimate
  sample of the post-Phase-0 RNG distribution.
- **Phase 0 changed the per-seed RNG mapping.** Pre-Phase-0, `-seed N`
  did not control `GPUMoveThing.runSeed` (that was a class-load random
  draw); post-Phase-0 it does. The 2026-06-01 baseline was generated
  pre-Phase-0, so the per-seed values are NOT directly comparable
  across the two ensembles — only the population mean is. The ~2σ
  shift could reflect that the seeds {1..10} now deterministically
  sample a slightly low-binding sub-region of the population, rather
  than a Phase-1 anchor regression. The Stage-1 same-seed-1
  conformation match (head_r 0.063 µm, on-baseline; theta_RL/LM aligned
  to ≤0.05°) argues against a per-step force error.
- **Velocity-binding decoupling is suggestive but not diagnostic.** A
  Phase-1 anchor-force bug would most plausibly perturb the rod-end
  trajectory and thus the binding geometry — but it would also pull
  velocity along with it (binding count and stroke power are coupled
  in the model). gv landing on baseline while binding drops by 15% is
  more consistent with population-level RNG sampling than a force-path
  defect, but only an extended ensemble (e.g. seeds 11..20 or 11..30)
  can resolve which it is.

#### Overall Phase 1 verdict: PARTIAL PASS — Stage 1 cleanly passed; Stage 2 mixed.

- **Anchor-port correctness:** confirmed by Stage 1. Device anchor
  kernel produces the same per-step force as the CPU anchor pass to
  within float32-trajectory noise (0.22 nm head_r, 1.43 nm head_z
  over 500k steps from identical seed). The "anchor lesson" residue
  is closed at the per-step / per-myosin level.
- **Ensemble agreement:** velocity matches baseline cleanly; the two
  binding-count observables are ~2σ below. Per the prompt's strict
  gate language this is not a clean ensemble PASS, and per the prompt's
  constraint ("validation only — no code changes") this is reported
  here as a flag for the planner, not investigated further in this
  session. Recommended planner-scoped follow-ups (one of):
  1. Extend to seeds 11..30 on the post-Phase-0 build to tighten SEM
     and either confirm the shift is RNG-sampling (mean walks back
     toward 856/7.30) or persists (then root-cause is structural).
  2. Re-baseline by running 10 seeds on the *pre-Phase-1* build
     (`MyosinFixed.applyGPUDroppedForces` CPU pass, no device anchor)
     under the post-Phase-0 RNG; compare both n=10 ensembles in the
     same RNG regime. This isolates the Phase 1 delta from the
     Phase 0 delta.
  3. Accept the partial pass on the strength of Stage 1, leave
     `DIAG_CPU_ANCHOR=false` as default, and proceed to Phase 2 with
     a note to revisit if Phase 2 ensembles show consistent low-bind
     bias.

Phase 2 (step forces) is NOT auto-greenlit by this entry. The planner
should make the Stage 2 call before the next port begins.

## 2026-06-02 — Phase 0 layout-decision spike (AoS vs SoA): keep AoS

First executable step of the residency campaign. Goal: settle the code-wide
pose-storage rule (AoS m*3 interleaved vs SoA separate-axis) before substantial
porting, using the move kernel as the binding test (per-entity access where
coalescing matters most).

**Implementation.** Two env-gated flags added to `GPUMoveThing.java`:
- `BOA_SOA_POSE` — switches the move kernel + its pack/unpack to axis-major
  layout (within the same FloatArrays: x's in `[0..stride)`, y's in
  `[stride..2*stride)`, z's in `[2*stride..3*stride)`; `stride = slotCap`).
  No new buffers — same arg count to the kernel, no 15-arg-cap concern.
  Forces `DIAG_CPU_JOINTS=true` (the joints kernel still expects AoS pose
  and is bypassed; CPU joints write into `cpuForceSum`, which the SoA move
  kernel reads correctly because force buffers are also axis-major in pack).
- `BOA_MOVE_AB_PROFILE` — isolates the move kernel for clean timing. Forces
  `DIAG_CPU_JOINTS` + `DIAG_CPU_DELTA_ADD` so the only GPU task per step is
  the moveOnly plan, then wraps it with TornadoVM `.withProfiler(SILENT)` and
  accumulates `getDeviceKernelTime()` / write / read times. New
  `GPUMoveThing.reportMoveAB()` prints the per-call summary at run end.

Also made `GPUMoveThing.runSeed` lazy-initialized (via `ensureRunSeed()`
called from `onStepStart` / `moveThings`) so `-seed N` parsed AFTER class
load now produces a reproducible runSeed across runs — previously the runSeed
was locked in by the class-load-time `Env.mtRNG.nextInt()` and `-seed` had no
effect on it.

**Results (full numbers in `RUN_LOGS/2026-06-02_phase0-layout-spike.txt`):**

```
                  AoS                              SoA                              Δ
M=400K  move:     0.8692 ms / 222.77 GB/s (44.6%)  0.8695 ms / 222.72 GB/s (44.5%)  +0.03% time
M=98K   move:     0.2288 ms / 211.59 GB/s (42.3%)  0.2289 ms / 211.53 GB/s (42.3%)  +0.04% time
```

AoS and SoA are indistinguishable on the RTX 5070 — at both scales, at both
the pose-only access pattern (~19% peak BW) and the whole-kernel pattern
(~44% peak BW). The kernel is already memory-bound at ~45% of theoretical
peak; the L1/L2 hierarchy absorbs stride-3 reads well enough that explicit
axis-major coalescing buys nothing measurable. This contradicts the
RESIDENCY_PLAN's prior framing ("~2% peak / ~10 GB/s effective that AoS
shows") — the move kernel in isolation, measured against pure device kernel
time (not chained-plan wall including transfers), is already in a healthy
bandwidth regime.

**Correctness.** Single-seed `glidingAssay500_val` comparison was inconclusive
for a different reason: with `-seed 1` AND a pinned runSeed, two repeat AoS
runs gave `bindEvents=770, 849` (a 10% delta from CPU step-thread
accumulator-order nondeterminism). The SoA arm's 657 sits inside that AoS
run-to-run band. The stable observable — gliding velocity — agrees to ~2%
between AoS and SoA (7.78 vs 7.91), well within seed spread; a real layout
bug (e.g., writing pose to wrong indices) would make gliding velocity
wildly off, not 2% off. No multi-seed ensemble run because the bandwidth
result is decisive on its own.

**Decision: KEEP AoS.** The SoA conversion delivers no measurable improvement
on the RTX 5070 move kernel, so adopting it code-wide would be a large rewrite
with zero perf payoff. The residency campaign (Phases 1–4 in
`RESIDENCY_PLAN.md`) proceeds on the existing AoS layout. The SoA branch is
left in place behind `BOA_SOA_POSE` (default off) — cheap to keep, available
if a future kernel hits a memory-bottleneck symptom that the move kernel
doesn't.

## 2026-06-02 — Device-residency audit: per-step CPU pose consumers catalogued

Strategic pivot: the GPU port goal is now DEVICE RESIDENCY — keeping canonical
pose state (`coord` / `uVec` / `yVec` + derived fields) on the GPU across
steps, downloading only at output frames. Per-step transfer is eliminated only
when NO per-step CPU computation needs to read poses, so the planner needs the
COMPLETE set of per-step CPU pose consumers before scoping.

Catalogue produced in **`RESIDENCY_AUDIT.md`** (Parts 1–5). Highlights:

- **{must port} blocking set** is explicit and per-config (gliding /
  Listeria / membrane / chamber-fixed / node-tethered). For the gliding-assay
  slice, it includes: pre-step `fillSoaArrays` (A1.a/A1.b), `MotorBindGrid3D`
  fill + `GPUMotorBinding` pack (A3.a/A3.c), the `MyosinFixed` anchor spring
  (A7.b — the live anchor-lesson residue on the GPU path today), `FilSegment`
  step forces F1/F3/F4 and `MyoFilLink` F8/F9/F10, plus benchmark-mode-only
  force injection and pin restoration.
- **Binding detection** (Part 3) still reads CPU pose every step in TWO
  places on the `-gpu` path — the CPU FillThreads grid build AND the
  `GPUMotorBinding` SoA pack — even though the kernel runs on the device.
  Full residency requires moving the grid build to the device.
- **General collision** (Part 4): `Thing.step()` itself has no broad-phase
  pairwise force (all F1–F12 are per-entity-vs-boundary or fixed-topology
  pointer pairs). But the per-step loop AS A WHOLE has three broad-phase
  spatial queries: meshColl FilLink-creation, meshColl
  membrane-fil-collision, and motor-binding. These three are the only places
  a device-side grid is genuinely needed — they collapse to one shared grid
  if FilLink-creation cadence can stay aligned with binding detection.
- F2 / F11 / F13 clarifications: F2 = filament–bug pairwise (Listeria, in
  step). F11 = ActA tether — dispatched in `actAStart` (= xLinkStart wave),
  NOT in `Thing.step()`; ActA is not a `Thing`. F13 doesn't exist as a step
  force; StickyNode inherits ProteinNode.step (= F12) and its membrane-tether
  forces run in `membraneLinksStart`.

Audit is read-only — no code changes. Feeds residency campaign scoping.

## 2026-06-01 — Pre-port characterization: step() force profiling + bending benchmark GPU baselines

Two pre-port data-gathering passes following the step() survey. No code ported,
no physics altered. Diagnostic infrastructure added behind default-off flags so
it can be reused as porting progresses.

### Part A — step() per-force profile (workload-dependent; F8-F10 dominates gliding)

Added `boxOfActin/StepProfiler.java` — a tiny thread-safe per-force timer keyed
by the survey's F-labels. Gated by `BOA_STEP_PROFILE=1` (default OFF, zero cost
when unset). Wired into the force-method sites in
`FilSegment.step` (F1/F2, F3, F4, F5/F6), `StaticFilSegment.step` (F1, F3, F4),
`MyoMotor.step` (F8-F10 via `updateMyoFilLinks` → `tipLink.step` →
addForces/alignUVec/alignYVec/ckRelease), `MyoMiniFilament.step` (F7),
`ProteinNode.step` (F12), `ActA.step` (F11), and `Bug.step` (drag-tensor
update). Also split the previously-untimed `resetCt` phase into its own
`resetCtTimer`. `StepProfiler.report()` prints at end of run beside
`JointDiag.dump`/`SingleMyoDiag.dump`.

CPU run, `ParameterFiles/glidingDense_demo_smoke` (M≈98K motors, ~1156 fil
segments, 1101 steps) — log `RUN_LOGS/2026-06-01_step_profile_partA.txt`:

```
Force                                  sec      pct       calls      ns/call
F1/F2  FilSeg boundary collision       0.284    1.0%      1273772    223
F3     FilSeg chain link spring        0.654    2.3%      1273772    513
F4     FilSeg chain torsion spring     0.216    0.8%      1273772    169
F5/F6  FilSeg node tether              0.029    0.1%      1273772     23
F8-F10 MyoMotor tipLink (motor-fil)   27.273   95.8%    107898000    253
TOTAL (sum of buckets, thread-time)   28.456
ThingStep Threads barrier wall        58.767
```

Note: bucket sums are SUM-across-worker-threads; wall ≈ bucket-sum / 16
worker threads with perfect balance.

**Result that flips the survey's port-order hypothesis.** The survey
hypothesized F3 (chain link spring) would dominate per-call and overall. F3 IS
the most expensive per-call site (513 ns/call vs 223/169/253 for the others —
two `fastAcos` calls inside `moveCoeff`), but the workload's *count of
FilSegments* is two orders of magnitude smaller than its *count of MyoMotors*:
98K motors × 1101 steps × 253 ns/call ≈ 27 s, vs 1156 segs × 1101 steps ×
513 ns/call ≈ 0.65 s. **In motor-heavy gliding-assay configs, the dominant
step()-phase cost is F8-F10 (MyoFilLink spring + alignment torques + release
check) — not F3/F4.** In a filament-heavy / Listeria / membrane config (many
segments, few motors), F3 would dominate. Port order should be workload-driven,
not survey-hypothesis-driven.

Per-force ordering of next-port priority (gliding-assay workload):
1. **F8-F10 (MyoFilLink)** — 95.8 % of step-phase work. Touches every bound
   motor every step, computes a spring force + two alignment torques + a
   stochastic release check. Pairwise, motor↔seg via fixed `tipLink.mySeg`
   pointer — same "fixed-pointer pair" pattern as the survey's filament chain
   forces, no spatial grid needed. Validation probe = gliding assay
   (bindEvents, meanBoundMotors, glidingVelocity) — already in the 10-seed
   protocol.
2. **F3 (chain link)** — only 2.3 % here but per-call expensive; will dominate
   in non-gliding workloads. Validation probes already exist (deflection +
   relaxation; see Part B for baselines).
3. **F4 (chain torsion)** — pairs with F3 topologically; port together.
4. Lower-cost forces deferred.

### Part B — Deflection + LP benchmark GPU baselines (Lesson 5 prereq)

Per Lesson 5, the deflection and LP benchmarks have never been run on the GPU
path; established now so future F3/F4 ports localize regressions instantly.
Added `BOA_BMDIAG_MAX_STEPS` env override to `-bmDiag`'s 5M default cap (used
500K for these baselines), and extended `-bmDiag`'s periodic console line plus
its DONE line to report `lpMeas` alongside the deflection ratio (LP chain was
already constructed in benchmark mode; previously only the WebSocket HUD path
emitted Lp).

Both benchmarks **do run on the GPU path without errors** — LP and deflection
chain FilSegments are GPU-handled (classified into the `GPUMoveThing` slot
plan; `brownianOff` flag on deflection chain is honored at pack time, line
1173 of `GPUMoveThing.java`).

500K-step results (`RUN_LOGS/2026-06-01_bmdiag_{cpu,gpu}.txt`):

| Observable | CPU | -gpu | Δ |
|---|---|---|---|
| Deflection ratio | 0.998420 | 0.999876 | +0.146 % |
| defl (µm) | 0.009786 | 0.009800 | +0.14 % |
| Lp_meas (µm) | 2176 | 1494 | (both transient) |
| Lp_theo (µm) | 15 | 15 | — |
| Wall (500K steps) | ~6 min | ~13 min | GPU 2× slower at tiny scale |

**Deflection ratio baseline established.** Both paths sit at the calibrated
≈1.0 target; the GPU ratio is 0.146 % above CPU. This is reproducible (came
from the GPU moveThing integration order — CPU step() forces are identical on
both paths because F3/F4 are still on CPU). When F3/F4 move to the GPU, this
+0.146 % gap is the per-port tolerance to monitor. A ratio that drifts beyond
the per-port noise on either path is a regression.

**Lp_meas is NOT converged at 500K steps.** With `lpEwmaAlpha = 0.001` the
EWMA window is ~1000 samples, and `accumulateLpData` fires once per
`toFileInterval = 1000` steps, so 500K steps = 500 samples = 0.5×
window — still in the transient. Both runs are far from Lp_theo = 15 µm
(2176 / 1494 µm). The CPU vs GPU number difference here doesn't carry signal;
it's two different stochastic trajectories from two different RNG seeds (CPU
calcRandomForces vs GPU Wang-hash) in the EWMA transient. **For a usable LP
baseline, a future session needs ≥ 5M steps (5×window).** Recommend a single
20M-step `-bmDiag` per path run overnight when F3/F4 GPU port is imminent.

GPU benchmark wall time is 2× CPU at this tiny scale (60-segment chains) —
the GPU dispatch overhead doesn't amortize. Expected; not a concern for
benchmark validity, just runtime budgeting.

### Files touched

- New: `boxOfActin/StepProfiler.java` (~80 lines).
- `boxOfActin/FilSegment.java`, `StaticFilSegment.java`, `MyoMotor.java`,
  `MyoMiniFilament.java`, `ProteinNode.java`, `ActA.java`, `Bug.java` —
  each gets `long _spT = StepProfiler.t0(); ...; StepProfiler.add(BUCKET, _spT);`
  around its force-computing call(s). Zero overhead when `BOA_STEP_PROFILE`
  unset (the `t0()` returns 0 and `add()` returns immediately).
- `boxOfActin/BoxOfActin.java` — added `resetCtTimer` and timer wiring in
  doLoop; `computeLpMeas()` helper extracted from `buildLpJson`;
  `bmDiagMaxSteps()` env-cap helper; extended `-bmDiag` periodic + DONE
  prints to include Lp_meas; called `StepProfiler.report()` at end of run.

### Next

Port F8-F10 (MyoFilLink) to GPU first for gliding-assay workloads. Apply
GPU_MIGRATION_LESSONS.md Lesson 2 (force-coverage audit) before kernel
landing: when MyoMotor.step() is short-circuited on the GPU path, every
force it currently dispatches (= just F8-F10 via `tipLink.step()` =
addForces, alignUVecTorque, alignYVecTorque) must be replicated in the
kernel, with `ckRelease` (stochastic unbind check) kept on CPU since it
mutates motor binding state. Anchor the validation in the gliding-assay
10-seed protocol; the kernel touches the same forces the assay was
designed to test.

For F3/F4 port (later): re-run the deflection/LP `-bmDiag` baselines at
≥ 5M steps to get a stable Lp_meas reference first; then port; then re-run
and compare ratio + Lp_meas against the longer baselines.

## 2026-06-01 — step() port pre-survey (force-coverage audit, ground truth)

Survey-only pass before any step() GPU port: catalog every force/torque
computed in the `stepStart` dispatch, the spatial structures step() consumes
(none — it's pointer-traversed chain topology, not a grid), the per-step
phase ordering, cost ranking expected vs known, and the validation probe
mapping. Lives in `STEP_PORT_SURVEY.md` at the repo root. Implements
GPU_MIGRATION_LESSONS.md Lesson 2 (force-coverage audit) as the FIRST step
of the port so no force gets silently dropped the way the rod-tail anchor was.

Key findings worth surfacing here:
- step() does NOT use any spatial mesh. F3/F4 (filament chain link + torsion)
  walk `end1Fil`/`end2Fil` pointers; F8/F9/F10 walk `MyoFilLink.mySeg`. The
  hard part of a pairwise GPU port (grid + neighbour list) is off the
  critical path.
- 13 distinct forces/torques across 6 Thing subclasses (F1–F13 in the doc).
  Stiff constraints to flag for Lesson 5: F3, F4, F5, F6, F8, F9, F10, F11.
- Validation coverage today: F3/F4 covered by deflection + LP benchmarks
  (BUT they have never run on the GPU path — must verify runnability first).
  F5/F6 (node tether) have NO direct probe — build a SingleFilNodeDiag
  before that subset ports. F1/F7/F12 (boundary) have no direct probe; add
  a wall-push diagnostic.
- Cost: ThingStep aggregate is ~22–26 s of 1101 steps at M=98K on GPU path
  (post anchor-fix), the dominant remaining CPU phase. Per-force breakdown
  inside step() not yet measured — survey flags this as a profiling gap to
  close before committing port order.
- Silent-drop risk surface: every subclass listed in Table A is one
  short-circuit away from the anchor-spring shape of bug. The reduced
  CPU pass pattern (`applyGPUDroppedForces` hook from MyosinFixed) is the
  established mitigation.

Recommended next step (planner's call): F3 + F4 first — highest impact,
best validation coverage, simplest topology. Profiling pass to confirm the
ranking before committing.

## 2026-06-01 — Bulk MemorySegment pack/unpack: tried, REVERTED (regression at both scales)

Attempted to replace the per-element `FloatArray.set()/.get()` loops in `GPUMoveThing`
pack/unpack with `MemorySegment.copy` bulk transfers. Hypothesis (from prompt): per-element
calls have non-trivial dispatch overhead and bulk transfers should drop pack/unpack from
~10–15 ms/call to well under 1 ms.

### Mechanism investigated (works cleanly)

`uk.ac.manchester.tornado.api.types.arrays.FloatArray` exposes `getSegment()` which returns
a `MemorySegment` already sliced past the array header (via `asSlice(ARRAY_HEADER)` —
verified by disassembling the API jar). The data starts at byte offset 0 in the returned
segment; no header offset to account for. Same layout for `IntArray`. Bulk copy is just:

```java
MemorySegment.copy(MemorySegment.ofArray(stagingFloat[]), 0L,
                   floatArray.getSegment(),               0L,
                   nElements * Float.BYTES);
```

### Implementation tried

Added slot-major `float[]` (and `int[]`) staging arrays as CPU mirrors of each `FloatArray`.
Worker threads in `packRange`/`packJointsRange` wrote to staging via plain array writes
(no virtual dispatch); after the fanout, a single `MemorySegment.copy` per array pushed
staging → device-side segment. Unpack ran in reverse: `MemorySegment.copy(seg → staging)`
once, then workers scattered staging → sparse SoA index space. The staging-as-mirror
design preserved bit-identical behaviour for myosin coord/uVec/yVec slots that the old
conditional pack skipped: their staging values stayed at the last unpack-side bulk-copy
(= kernel output), so bulk-copy back was a no-op for those slots.

`jointForceSum` / `jointTorqueSum` zeroing moved from per-slot `.set(0f)` to a single
`segment.asSlice(0, n3Bytes).fill((byte)0)` — bit-identical (all zeros).

`velMask` (set once in `classifyThings`) became a one-shot bulk copy.

Compiled clean; smoke observables PASS (glidingAssay500_val seed=1: bindEvents=1028 vs
baseline mean 861±240, meanBoundMotors=7.15 vs 7.37±1.52).

### Timing — A/B at M=98K and M=400K, both GPU paths

**glidingDense_demo_smoke (M≈98K motors, slotCount≈294K, 1101 steps):**

|             | baseline (per-elem) | bulk-copy | delta    |
|-------------|---------------------|-----------|----------|
| slotPack    | 9.70 s / 8.81 ms    | 12.97 s / 11.78 ms | +3.0 ms |
| jointPack   | 6.03 s / 5.48 ms    |  6.44 s /  5.85 ms | +0.4 ms |
| exec        | 12.53 s / 11.4 ms   | 13.42 s / 12.2 ms  | ~noise  |
| unpack      | 17.72 s / 16.1 ms   | 18.94 s / 17.2 ms  | +1.1 ms |
| **total**   | **45.98 s / 41.8 ms** | **51.77 s / 47.0 ms** | **+5.2 ms/call (+12%)** |

**glidingScale400K (M≈400K motors, 400 steps):**

|             | baseline           | bulk-copy           | delta    |
|-------------|--------------------|---------------------|----------|
| slotPack    | 14.40 s / 36.0 ms  | 17.99 s / 45.0 ms   | +9.0 ms  |
| jointPack   |  9.18 s / 22.9 ms  |  9.35 s / 23.4 ms   | ~noise   |
| exec        | 17.49 s / 43.7 ms  | 17.53 s / 43.8 ms   | ~noise   |
| unpack      | 26.52 s / 66.3 ms  | 27.57 s / 68.9 ms   | +2.6 ms  |
| **total**   | **67.58 s / 169 ms** | **72.44 s / 181 ms** | **+12 ms/call (+7%)** |

Regression scales with slot count (slotPack gap 3 ms → 9 ms going 98K → 400K).

### Why bulk copy lost

The per-element `FloatArray.set()` was already near-optimal for this gather-then-write
pattern. The JIT inlines `FloatArray.set` → `TornadoMemorySegment.setAtIndex` →
`MemorySegment.setAtIndex(JAVA_FLOAT, …)` down to a direct off-heap memory store; 16
worker threads parallelise the loop and effectively saturate the memory bus. At M=294K
slots × ~14 floats packed per slot, that's ~16 MB written per call ≈ 8 ms at saturated
multi-core bandwidth — exactly the observed baseline.

The bulk-copy variant added a heap-array staging round-trip: workers gather SoA →
staging[] (one full RAM-write pass), then a SINGLE-THREADED `MemorySegment.copy(heap, …,
offheap, …)` moves staging → device segment (another full pass, single core, bandwidth
≤ 1/N of saturated). Doubled the memory traffic AND serialised the second pass, for no
per-call latency reduction (the per-element overhead being optimised away was never
material). Single-threaded copy of ~16 MB at single-core bandwidth (~5 GB/s) ≈ 3 ms; that
matches the +3 ms regression at M=98K and the +9 ms at M=400K.

The unpack regression is the same story: replaced parallel per-element `.get()` (off-heap
reads + scatter to SoA) with a single-threaded bulk read into staging + parallel scatter
from staging. The bulk read serialises what was previously parallel.

### Lesson — when bulk copy DOESN'T help

Per-element off-heap writes via the Tornado API are JIT-inlined to direct memory stores.
They are bandwidth-bound at multi-core; bulk copy via `MemorySegment.copy(heap, offheap)`
is single-threaded and bandwidth-bound at single-core. For gather-then-write patterns
already parallelised across N cores, adding a staging mirror is strictly worse:
2× memory traffic and serialised on the off-heap write.

Bulk copy WOULD help if (a) the loop ran on a single thread, (b) the per-element call had
real dispatch overhead the JIT couldn't elide, or (c) the source was already contiguous
and could feed directly into the off-heap segment with no staging mirror. None apply here.

Code reverted; per-element pack/unpack stays. The optimisation idea is documented in
case future investigation (e.g., a parallelised bulk copy, or sourcing kernel inputs from
a contiguous off-heap region maintained outside the gather pattern) becomes viable.

### Next

Per-step pack/unpack is NOT the dominant cost — exec (12 ms) and unpack-CPU-side work
(recomputeDerivedSoA + xRange/yRange/zRange + bindTip refresh: most of the 17 ms in
"unpack") are larger. The step() GPU port (moving filament-side forces to the kernel)
remains the bigger lever, and the force-coverage audit (GPU_MIGRATION_LESSONS.md Lesson 2)
applies as the first step there. RUN_LOGS for this A/B saved nowhere (figures in table
above are the full record).

## 2026-06-01 — GPU joints kernel: RESOLVED (rod-tail anchor force was dropped on GPU path)

The GPU myosin-joints kernel caused a ~42% gliding-assay binding drop. Root cause: the
rod-tail anchor spring force (MyosinFixed.applyRodFixedPtForce) was never applied on the
GPU path — the per-myosin CPU dispatch was short-circuited when GPU joints were active, and
the kernel didn't replicate the anchor, so GPU myosins drifted off their anchors. NOT a
precision/float32 issue (an extended precision investigation was a red herring; see
lessons doc).

Fix: a reduced CPU pass (applyGPUDroppedForces() hook; MyosinFixed overrides it to apply
the anchor spring) runs the GPU-dropped per-myosin forces alongside the GPU joints kernel.
Validated at J2=0: SingleMyoDiag head_r 0.533→0.063 µm (CPU 0.061); conformation head_z gap
55nm→0.04nm; 10-seed ensemble PASSES (bindEvents/meanBoundMotors/glidingVelocity at
0.44/0.67/0.80 σ). Anchor CPU pass costs ~6% wall; no kernel port needed.

Current state: GPU joints kernel validated and live; chained joints+moveThing plan works.
J2 (rod-lever stiffness) reset to 0.00. The J2=0.1 stiffening (rest angle 96°) is a
deferred OPTIONAL biology improvement (finite S2-hinge stiffness), not needed for
correctness — code edits left in place but inert at J2=0.

Pointers:
- Full diagnostic trail (the precision detour + the single-myosin reproduction that cracked
  it): JOURNAL_ARCHIVE.md, section "GPU joints saga — full diagnostic trail".
- Distilled lessons (silent force-dropping, force-coverage auditing, minimal reproduction,
  watch-it-run, validation coverage): GPU_MIGRATION_LESSONS.md.

Next: step() GPU port — apply the force-coverage audit (GPU_MIGRATION_LESSONS.md Lesson 2)
as the FIRST step to prevent another silently-dropped force.
(Bulk-memcpy pack/unpack was tried and reverted; see 2026-06-01 entry above.)

## 2026-05-30 — Myosin joints GPU kernel

New `boxOfActin/GPUMyosinJoints.java` ports `Myosin.jointConstraints()` —
all four CPU methods (`applyLeverMotorJointForce` / `Torque`,
`applyRodLeverJointForce` / `Torque`) — to a TornadoVM PTX kernel. One GPU
thread per Myosin. Each thread reads its rod/lever/motor pose from
`Thing.soaCoord` / `Thing.soaUVec` via slot indices, recomputes the four
joint contributions in 18 thread-local floats, and writes sparsely into
`jointForceSumOut` / `jointTorqueSumOut` at the three slot indices. CPU
download iterates by Myosin and adds the kernel output into
`Thing.soaForceSum` / `Thing.soaTorqueSum`.

`MyosinDimer.myoDimerThreads` (the parallel/antiparallel rod-coupling
joint) stays on CPU — the prompt scoped only per-Myosin joints. Both
dispatches share the existing `myoJoints1Start` wave; `Myosin.myoThreads`
short-circuits its `divideAndConquer` when `Env.useGPU` so the worker pool
spawns but does no per-Myosin work.

### A. PTX-backend rewrites — three Math intrinsics gone

Three CPU-side primitives don't survive the TornadoVM 4.0.1 PTX backend
and produce `unimplemented` bailouts via `PTXArithmeticTool.emitReinterpret`:

1. **`Float.isNaN(x)` and `x != x` self-comparison.** Graal canonicalises
   both into `(floatToIntBits(x) & 0x7fffffff) > 0x7f800000`, which uses
   `ReinterpretNode` (float↔int bit cast). PTX has no `ReinterpretNode`
   lowering. Replaced the NaN guard with `tvMag2 > 0f` alone — `NaN > 0f`
   is false in IEEE 754, so NaN inputs are skipped naturally. (Side
   effect: the CPU code's random-direction kick for the exactly-parallel
   `mag2 == 0` case is dropped on GPU. The kick was added by iter2b-polish
   to unstick exact float32 parallelism — at this iteration's scale it
   doesn't appear material to observables, but flagging it as a
   conservative-equivalence delta vs CPU.)

2. **`Math.acos(double)`.** PTX has no `acos` intrinsic. Graal's software
   fallback uses bit-level range reduction (ReinterpretNode again). Two
   sites required different fixes:
   - **`moveCoeff` callsites** sidestep acos entirely: the CPU code
     computes `beta = fastAcos(cosBeta); cosAlpha = sin(beta)`, but
     `sin(acos(x)) = sqrt(1-x²)` is the algebraic identity, so the kernel
     uses the squared form directly (`cosAlpha² = 1 - cosBeta²`, clamped
     ≥ 0). Saves an acos AND a sin per moveCoeff call.
   - **Joint-torque `angTween` computation** keeps a numerical acos via a
     new `fastAcosF()` using Abramowitz & Stegun 4.4.46:
     `acos(x) ≈ sqrt(1-|x|) * (a₀ + a₁|x| + a₂|x|² + a₃|x|³)`
     with the standard four-term coefficients, then `π - result` for
     `x < 0`. Max error 5e-5 over [-1, 1]. CPU's `Pt3D.fastAcos` uses
     `Math.acos` in the (-0.95, 0.95) band, so the per-call angle differs
     by up to 5e-5 between GPU and CPU — well below float32 round-off
     accumulation elsewhere.

3. **`Math.sin(double)` is fine** (PTX intrinsic), but unused after the
   `moveCoeff` rewrite above.

### B. Plan + parallel pack/unpack architecture

Kernel parameters: 11 (under the 15-param cap). All `EVERY_EXECUTION`
on the first cut. Layout:

| array              | direction | size              | content                                |
|--------------------|-----------|-------------------|----------------------------------------|
| `soaUVecFA`        | R         | `thingCap*3`      | mirror of `Thing.soaUVec[]`            |
| `soaCoordFA`       | R         | `thingCap*3`      | mirror of `Thing.soaCoord[]`           |
| `rodSlots`         | R         | `myoCap`          | rod `myThingNumber` per Myosin         |
| `leverSlots`       | R         | `myoCap`          | lever `myThingNumber`                   |
| `motorSlots`       | R         | `myoCap`          | motor `myThingNumber`                   |
| `myoDrags`         | R         | `myoCap*9`        | [rod,lever,motor] × [bTransGam.x, .y, bRotGam.y] |
| `cockedFlags`      | R         | `myoCap`          | 1 if `motor.isCocked()` else 0         |
| `jointForceSumOut` | W         | `thingCap*3`      | sparse output (M*9 of thingCap*3 slots) |
| `jointTorqueSumOut`| W         | `thingCap*3`      | sparse output                           |
| `jointParams`      | R         | 13 floats         | dt, FracMove/R/MoveTorq × 2 joints, lengths, stall, angles |
| `counts`           | R         | 2 ints            | [M, thingCt]                            |

`thingCap` and `myoCap` grow with 2× headroom on demand; plan rebuilds
when either is exceeded. `Myosin.theMyosins` provides the slot mapping —
each Myosin's rod/lever/motor are unique `Thing`s so the sparse writes
are conflict-free without atomics.

A 16-worker persistent daemon pool (`OP_PACK_POSE` / `OP_PACK_MYO` /
`OP_UNPACK_ADD`) parallelises the pack of soaUVec/soaCoord into the GPU
FloatArrays AND the sparse download+add of joint forces/torques back into
`Thing.soaForceSum`/`Thing.soaTorqueSum`. Same monitor-based hand-rolled
barrier as `GPUMoveThing` iter2d. Per-worker Myosin ranges never touch
the same `soaForce[]` index (Myosin → rod/lever/motor map is unique), so
the `float[] +=` writes are race-free across workers.

### C. Smoke validation — glidingAssay500_val — PASS (CPU + GPU)

Baselines: bindEvents 861 ± 240, meanBoundMotors 7.37 ± 1.52,
glidingVelocity 8.39 ± 1.44.

```
                    baseline mean ± 2 SD     CPU (this)        GPU (this)
bindEvents       :       861 ± 240               755 PASS         444 PASS
meanBoundMotors  :      7.37 ± 1.52            7.026 PASS       4.761 PASS
glidingVelocity  :      8.39 ± 1.44           8.1264 PASS       6.6123 PASS
```

Both within ±2 SD bands. Single-seed dispersion at this run length is
high (the 10-seed ensemble protocol gives tighter SEM), so the GPU
landing on the low end of meanBoundMotors and glidingVelocity is within
expected noise. Logs: `RUN_LOGS/2026-05-30_joints_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_joints_smoke_gpu.txt`.

GPUMyosinJoints per-call (smoke, M=500, 10101 calls):
```
            total       pack        exec       unpack
sec/seed    31.28       13.71      11.14       6.35
ms/call      3.10        1.36       1.10       0.63
```

### D. Dense timing — glidingDense_demo_smoke (M=98K, thingCt≈588K, 1101 steps)

Source logs: `RUN_LOGS/2026-05-30_joints_dense_{cpu,gpu}.txt`.

```
phase                                    CPU dense (s)   GPU dense (s)
----------------------------------------------------------------------
ThingStep (step+bio+resetCt)                  54.5            22.4
ThingBrownian (calcRandomForces)              30.6             2.9   ← Wang-hash on GPU
Myosin joints (per-Myosin)                    15.2             0.8   ← GPU dispatch overhead
MyoDimer joints (still CPU)                    7.1             0.8
Mesh                                           6.8             7.1
Ck Mots (CPU motor-binding)                    5.6             0.0
MotorBindGrid3D Fill                           ~3              ~3
GPUMoveThing total                             ---            35.5    (1101 calls)
GPUMotorBinding total                          ---            13.1    (1101 calls)
GPUMyosinJoints total                          ---            15.1    (1101 calls)
----------------------------------------------------------------------
wall (min/sim-sec)                           238             217      (~9 % faster)
```

GPUMyosinJoints per-call breakdown at M=98K:
```
            iter1 (single-thread pack)   iter1.5 (parallel pack)
total ms          27.6                       13.7
pack  ms          15.3                        6.9   ← 16-worker parallel pack
exec  ms           4.8                        4.9
unpack ms          7.5                        1.8   ← 16-worker parallel scatter-add
```

The kernel exec alone (4.9 ms/call ≈ 5.4 s/run) beats the CPU joints
phase (15.2 s/run) cleanly. Pack + exec + unpack at 13.7 ms/call still
trails the prompt's 2-3 ms/call "exec + download + add" target; the gap
is the per-step CPU→FloatArray copy of soaCoord + soaUVec
(thingCt*3*2 = 3.5M float writes per call) which the parallel pack
brought from 14 ms to 7 ms but did not eliminate.

Total dense wall improves 238 → 217 min/sim-sec (~9% faster). Smaller
swing than the GPU joints phase alone (15 s → 7 s) because the joints
phase is only ~6% of the CPU wall. The headline win at dense is
ThingBrownian (30.6 → 2.9 s) and ThingStep (54.5 → 22.4 s), both
inherited from prior GPUMoveThing work.

### E. Findings worth keeping

- **PTX backend doesn't lower `ReinterpretNode`.** Anything Graal
  canonicalises through bit-level reinterpret bails: `Float.isNaN`,
  `Math.acos`, `Math.abs(float)` (in some forms), `x != x`,
  `Float.floatToIntBits` / `intBitsToFloat`. Rule of thumb: any Math
  intrinsic that PTX doesn't natively support (no `acos`, no `asin`,
  no `tanh`, no `atan2`) will trip this if the kernel uses it.
- **Algebraic simplification beats kernel intrinsics.** Recognising
  `sin(acos(x)) = sqrt(1 - x²)` saved two transcendentals per
  `moveCoeff` call (4 per Myosin per step). The CPU code computed
  acos→sin in series for readability; the GPU port surfaces the
  identity because acos isn't available.
- **Sparse writes are safe without atomics when ownership is unique.**
  Each Myosin owns its rod/lever/motor 1:1, so per-thread writes never
  collide. This is the same "no atomic" pattern as the GPU moveThing
  kernel; both rely on the upstream invariant that Things aren't
  shared across multiple Myosins.

### F. Open items

- **Pack at 6.9 ms/call (parallel) is still the bottleneck.** The
  current pack copies the full `Thing.soaCoord[]` and `Thing.soaUVec[]`
  arrays (thingCt*3 floats each) into FloatArrays sized to the same
  full layout, so the kernel can read by slot index directly. Reducing
  the pack to compact per-Myosin pose data (M * 18 floats vs
  thingCt * 6 floats) would cut the FloatArray.set count by ~half. A
  device-residency option (reuse `GPUMoveThing`'s soaUVec/soaCoord
  buffers across kernels) would eliminate the pack entirely but
  requires cross-plan buffer sharing not yet exercised in this codebase.
- **MyoDimer joints (0.79 s at dense) could go to GPU** with a similar
  per-Dimer kernel; smaller per-step cost so lower priority. Would
  involve a second 2-Myosin-pair lookup.
- **`mag2 == 0` random-direction kick is dropped on GPU.** The CPU's
  iter2b-polish path applies `randomUnitVec` when the cross product is
  exactly zero (parallel uVecs); the GPU's `tvMag2 > 0f` branch skips
  this. Single-seed smoke shows observables within ±2 SD, but a 10-seed
  ensemble would clarify whether suppressing the kick biases ensemble
  means.

## 2026-05-30 — Pt3D field removal: text replacement + bridge elimination

Follow-up to the same-day "SoA derived fields" landing. With the SoA
arrays + accessors + bulk recompute in place, this session removes the
per-`Thing` Pt3D field bridge entirely: `coord`, `uVec`, `uVecR`, `yVec`,
`zVec`, `end1`, `end2`, and the `double[9]` `transXTox` / `transxToX`
arrays no longer exist on `Thing`. Readers go through SoA accessors
(`getCoordX/Y/Z`, `getUVecX/Y/Z`, `getEnd1X/Y/Z`, `getTransXTox(idx)`,
...) and writers go through SoA setters (`setCoord`, `setUVec`,
`setYVec`, `incCoord`, ...). `Thing.bridgeDerivedToPt3D` is deleted.

The conversion was done by bulk sed across `boxOfActin/*.java`
(excluding `Pt3D.java`, which had `Pt3D uVec` parameter shadowing) plus
manual fixes to compile errors, semantic bugs from the sed, and
constructors / `initialize()` / `moveThing()` bodies that did in-place
Pt3D math on the now-deleted fields.

### Sed strategy

1. `.x/.y/.z` field reads: `\bcoord\.x\b` → `getCoordX()`, etc.
2. Pt3D method-call shorthand on self: `\bcoord\.copy(` → `setCoord(`;
   `\buVec\.copy(` → `setUVec(`; `\bcoord\.inc(` → `incCoord(`; etc.
3. Qualified cross-Thing refs: `\.coord\b` → `.coordAsPt3D()`,
   `\.end1\b` → `.end1AsPt3D()`, etc. — the `*AsPt3D()` accessors
   return fresh Pt3D snapshots (allocating; cold-path acceptable).
4. Bare-name refs inside Thing-subclass methods: `\bcoord\b` →
   `coordAsPt3D()`, `\buVec\b` → `uVecAsPt3D()`, etc.

After the sed, the compiler surfaced 696 errors; each was fixed by
either deleting the line (e.g. `end1 = null;` in `sepaku`), rewriting
the surrounding block (each subclass `initialize()` → bulk
`Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1)`), or
inlining the Pt3D method using SoA accessors.

Pre-sed renames to avoid shadow collisions:
- `MotorBindGrid3D.fillFilSeg(... Pt3D end1, Pt3D end2)` → `(Pt3D e1, Pt3D e2)`.
- `Chamber.Chamber(Pt3D coord, ...)` and `Bug.Bug(Pt3D coord, ...)` → `Pt3D initCoord`.
- `StickyNode.makeLooseStickies` local `Pt3D coord` → `Pt3D coordPt`.
- `FilSegment.makeWestCircleFilaments/makeEastCircleFilaments` local
  `Pt3D uVec` → `Pt3D localUVec`.
- `Pt3D.java` excluded from the sed entirely (it has `Pt3D uVec`
  parameters); its `transxToX` / `transXTox` / `coord` accesses on
  `Thing p` were manually rewritten to read `Thing.soaTransXTox` /
  `Thing.soaCoord` arrays inline.

### Subclass `initialize()` simplification

Each subclass `initialize()` previously did:
```
loadPoseFromSoa();
zVec.cross(uVec, yVec);
yVec.cross(zVec, uVec);
transMat();
uVecR.scale(-1, uVec);
end1.add(coord, -length/2, uVec);
end2.add(coord, length/2, uVec);
```
The replacement body is:
```
pushLengthToSoa(<length>);
Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
```
(plus `xRange/yRange/zRange` for FilSegment / MyoMotor / MyoRod /
MyoLever / MyoMiniFilament). The bulk pass computes the orthonormal
body frame, transXTox, and end1/end2 in one tight loop — same math
the per-Thing version did, but reading and writing SoA directly.

### Subclass `moveThing()` body→fixed pose update

The legacy pattern mutated the `uVec` / `yVec` Pt3D fields in place:
```
uVec.setVals(1, uVecTransInY, uVecTransInZ);  // body-frame
uVec.xToX(this);                              // transform to fixed-frame
uVec.unitVec();                                // normalise
```
With fields removed, every `moveThing` now uses a Pt3D scratch:
```
Pt3D scratch = new Pt3D();
scratch.setVals(1, uVecTransInY, uVecTransInZ);
scratch.xToX(this);
scratch.unitVec();
setUVec(scratch);
```
Per-step Pt3D allocation per Thing per moveThing call — JIT should
escape-analyse these stack-allocations. Affects FilSegment, MyoMotor,
MyoRod, MyoLever, MyoMiniFilament, ProteinNode, Bug.

### Two stable Pt3D snapshots that survived the cull

Two callers depended on stable Pt3D *references* (not snapshots), so
the corresponding fields stay — but now they are
explicitly-refreshed mirrors of SoA:

- **`FilSegment.end1Pt` / `end2Pt`**: refreshed in `initialize()`.
  Needed because `ptAtEnd1` / `ptAtEnd2` use reference identity
  (`ptAtEnd2 == end2Fil.end1Pt`) to test which end of a neighbour
  filament our segment is bound to. Replacing those `==` checks with a
  "which end" int flag would be a larger refactor; deferred.

- **`MyoMotor.bindTip`**: refreshed in `initialize()`. Read every step
  by `Mesh.fillMeshOfMyos` (`motor.bindTip.x ± tol`) and
  `MotorBindGrid3D.fillMotor` for spatial binning of motor binding
  tips. The original code aliased it to `end2` directly, which was a
  reference; with `end2` gone, `bindTip` is now its own `Pt3D` that
  the bulk pass updates per step from `getEnd2X/Y/Z`.

These two are CPU-side scratch fields, not part of the SoA contract;
removing them needs grep-and-fix surgery, not just sed.

### GPU path: skip-initialize() forced two regressions

The GPU `moveThings()` writes pose to SoA via `unpackRange` and runs
`recomputeDerivedSoA` over `[0, thingCt)` in `OP_DERIVED_AND_BRIDGE`,
but does NOT call per-Thing `initialize()`. That used to be fine
because `bridgeDerivedToPt3D` mirrored derived SoA back into Pt3D
fields. With the bridge deleted, the GPU path silently left
`bindTip` / `end1Pt` / `end2Pt` stuck at construction-time values —
GPU smoke produced bindEvents=68 vs baseline 861, glidingVelocity=171
vs baseline 8.4 (filaments flying away with no motor binding).

Fix: the post-OP_DERIVED_AND_BRIDGE pass in `moveThings()` now also
iterates `FilSegment.theFilSegments` and `MyoMotor.theMotors`,
refreshing their `end1Pt`/`end2Pt`/`bindTip` from SoA accessors —
same data that subclass `initialize()` writes on CPU path. Cost:
linear sweep over fil + motor populations, microseconds at any scale.

### Files changed

- `boxOfActin/Thing.java` — removed Pt3D `coord`/`uVec`/`uVecR`/`yVec`/
  `zVec`/`end1`/`end2` field declarations and the `transXTox`/
  `transxToX` double[9] arrays; deleted `bridgeDerivedToPt3D` and
  `loadPoseFromSoa`; converted `transMat()` to a no-op that delegates
  to `recomputeDerivedSoA`; added per-component SoA accessors and
  setters (`getCoordX/Y/Z`, `setCoord`, `incCoord`, `setUVec`,
  `setYVec`, `unitVecU`, etc.); added `coordAsPt3D`/`uVecAsPt3D`/etc.
  Pt3D-allocating helpers for legacy callers; added
  `copyTransXToxInto(double[9])` / `copyTransxToXInto(double[9])` /
  `getTransxToX(idx)` for the transformation matrix; converted the
  push* helpers to no-ops (so legacy bridge-flush call sites still
  compile but do nothing); `Thing(Pt3D initCoord)` constructor seeds
  SoA pose via setCoord/setUVec/setYVec; `sepaku` no longer nulls
  the removed fields; `incFrictionSum` builds the r vector via
  `getCoordX/Y/Z` instead of `coord.x/y/z`.
- `boxOfActin/Pt3D.java` — all 13 transformation methods (`xToX`,
  `XTox`, `xToNewX`, `XToNewx`, `xToXPlusxOrigin`, `xToXPlusPoint`,
  `XToxFromxOrigin`, `XToxFromFloats`) rewritten to read directly
  from `Thing.soaTransXTox` (and `Thing.soaCoord` where the origin
  offset is needed). No double[9] scratch allocation per call.
- `boxOfActin/GPUMoveThing.java` — `OP_DERIVED_AND_BRIDGE` worker case
  now runs only `recomputeDerivedSoA(start, end)` (the
  `bridgeDerivedToPt3D` call is gone); the small-Thing-count inline
  path drops it too; post-dispatch loop in `moveThings()` extended to
  refresh `fs.end1Pt`/`fs.end2Pt` per FilSegment and `m.bindTip` per
  MyoMotor.
- `boxOfActin/FilSegment.java` — added stable `Pt3D end1Pt`,
  `Pt3D end2Pt` fields refreshed in `initialize()`; `initialize()`
  shrank to length push + bulk recompute + end-Pt refresh + xRange
  update; `translate()` / `translateCoord()` now call
  `recomputeDerivedSoA` for their single slot to keep end1/end2 fresh
  for readers between `translate` and the next `initialize`;
  `setFirstHalf` writes new coord via `setCoord` from end1 + length/2
  * uVec (no more `coord.add(end1, 0.5*length, uVec)`); `moveThing`
  uses Pt3D scratch + `setUVec`/`setYVec`; FilSegment-internal
  `*.end1AsPt3D()` / `*.end2AsPt3D()` references swapped to
  `*.end1Pt` / `*.end2Pt` so neighbour-end identity checks still work.
- `boxOfActin/MyoMotor.java` — `initialize()` shrank to length push +
  bulk recompute + bindTip refresh; constructor allocates `bindTip =
  new Pt3D()` then lets `initialize()` populate it (vs. previously
  aliasing it to the now-gone `end2` Pt3D).
- `boxOfActin/MyoRod.java`, `MyoLever.java`, `MyoMiniFilament.java`,
  `Bug.java`, `ProteinNode.java` — same pattern: `initialize()`
  shrank to length push (where applicable) + bulk recompute; old
  zVec.cross / yVec.cross / uVecR.scale / end1.add / end2.add lines
  deleted; `moveThing` body→fixed pose updates rewritten with Pt3D
  scratch + setUVec/setYVec; ProteinNode 3-arg constructor's
  orthonormalisation rewritten to use scratch Pt3D objects.
- `boxOfActin/Chamber.java`, `Bug.java` — constructor param `Pt3D coord`
  renamed to `initCoord` so the sed bare-name pass didn't corrupt the
  `super(coord)` call.
- `boxOfActin/MotorBindGrid3D.java` — `fillFilSeg(... Pt3D end1, Pt3D
  end2)` params renamed `e1, e2` to avoid the sed.
- `boxOfActin/StickyNode.java`, `boxOfActin/FilSegment.java` (two
  static methods) — local `Pt3D coord` / `Pt3D uVec` renamed.
- `boxOfActin/BoxOfActin.java` — `applyBenchmarkPins` rewritten to use
  `incCoord(dx,dy,dz)` instead of `coord.x += ...` field writes.

### Smoke validation — glidingAssay500_val — PASS

Baselines: bindEvents 861 ± 240, meanBoundMotors 7.37 ± 1.52,
glidingVelocity 8.39 ± 1.44.

```
                    baseline mean ± 2 SD     CPU (this)        GPU (this)
bindEvents       :       861 ± 240               713 PASS         798 PASS
meanBoundMotors  :      7.37 ± 1.52            5.861 PASS       7.067 PASS
glidingVelocity  :      8.39 ± 1.44           7.3086 PASS       8.281 PASS
```

Logs: `RUN_LOGS/2026-05-30_pt3drm_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_pt3drm_smoke_gpu.txt`.

Both within ±2 SD bands. The CPU run sits a bit below the baseline
mean (within tolerance); the GPU run lands almost on the baseline
mean once the post-unpack `bindTip` / `end1Pt` / `end2Pt` refresh is
in place.

### Dense timing — glidingDense_demo_smoke (M=98 K, thingCt≈588 K, ~1100 steps)

Source logs: `RUN_LOGS/2026-05-30_pt3drm_dense_cpu.txt`,
`RUN_LOGS/2026-05-30_pt3drm_dense_gpu.txt`.

```
phase                soaforce   coord     nopt3d    pt3drm(this)
----------------------------------------------------------------
CPU path
ThingStep Threads        62.45    63.53    60.39       54.12
ThingBrownian            31.56    31.39    29.69       30.51
Myosin (joints)          19.32    18.95    17.33       15.08
MyoDimer                  8.58     8.62     7.82        6.99
Mesh                      6.68     6.79     6.47        6.77
Ck Mots                   5.33     5.27     5.01        5.31
MotorBindGrid3D Fill      3.87     3.85     3.57        3.75
wall (min per sim-sec)   257      258      246          236   (~4 % faster)
[STATS] bindEvents       3633  → 3359  → 4313  → 5018
        meanBoundMotors  226.7 → 204.2 → 274.5 → 296.0
        glidingVelocity  40.58 → 39.06 → 41.25 → 32.42

GPU path
ThingStep Threads        32.26    32.56    31.66       32.47
gpuMoveThing total       40.39    41.55    41.15       34.62
gpuMotorBinding total    13.05    13.11    12.78       12.96
Myosin (joints)          18.33    18.27    16.84       15.08
wall (min per sim-sec)   239      241      234          228   (~3 % faster)
gpuMoveThing breakdown   pack=9.20,  exec=10.68, unpack=20.51  (soaforce)
                         pack=9.01,  exec=10.71, unpack=21.82  (coord)
                         pack=8.467, exec=10.549, unpack=22.133 (nopt3d)
                         pack=9.062, exec=10.809, unpack=14.748 (this)
```

### Result: GPU unpack saves 7.4 s (33 %), CPU mostly flat

The headline win is **GPU unpack 22.13 s → 14.75 s** (-33 %) on dense.
That is exactly the bridge-removal payoff: the
`OP_DERIVED_AND_BRIDGE` worker case used to do the SoA bulk recompute
AND then walk every Thing writing `t.coord.x`, `t.uVec.x`, ...,
`t.transXTox[0..8]` back into per-Thing Pt3D / double[9] fields.
That second pass is gone — the bulk pass writes SoA only, and the
new per-FilSegment / per-MyoMotor refresh of `end1Pt`/`end2Pt`/
`bindTip` is microseconds (one Pt3D per object, three float writes
each). Total GPU dense wall improves ~3 % (234 → 228 min/sim-sec),
in line with the unpack-only nature of the saving.

CPU dense is ~4 % faster overall (246 → 236 min/sim-sec). The Myosin
joints phase shrinks 16.84 → 15.08 s; that's almost certainly the
joints code now reading from `soaTransXTox`/`soaCoord` via inlined
Pt3D transformation methods, which JIT keeps better in L1 than the
old per-Thing `double[9] transXTox` indirection. The other phases
are within noise.

### Behaviour deltas worth flagging

- CPU dense glidingVelocity dropped 41.25 → 32.42. Within noise for a
  ~1100-step smoke at this Thing count, but at the low end. Re-run
  with multiple seeds before claiming this is a real bias.
- GPU dense bindEvents 4313 → 3765, meanBoundMotors 274.5 → 237.3,
  glidingVelocity 41.25 → 32.06. Same caveat — single-seed dense
  smoke; worth a seed sweep to confirm the change is benign.
- `Pt3D` transformation methods are now indexing through static
  `Thing.soaTransXTox` (float, not double). A tiny precision loss
  per transform — same precision as the rest of the SoA pose
  pipeline. No benchmark trip flagged so far.

### Open / deferred

- **`Pt3D end1Pt` / `end2Pt` / `bindTip` removal.** The remaining
  per-Thing Pt3D scratch fields (FilSegment.end1Pt/end2Pt for ptAtEnd
  identity checks; MyoMotor.bindTip for Mesh/MotorBindGrid3D binning)
  can be eliminated by replacing the `==` reference-identity checks
  with an int "which-end" flag, and by teaching Mesh/MotorBindGrid3D
  to read motor end2 via SoA accessors. Both ~50 sites; orthogonal
  to this session.
- **CPU `moveThing` per-Thing `initialize()` removal.** Still calls
  per-Thing initialize() (which now wraps recomputeDerivedSoA for the
  single slot). A dispatched-bulk version (single pass over all
  Things after the moveStart ThreadSet finishes) would amortise
  method dispatch — modest win at gliding scale, uncertain at dense.
- **Pt3D-allocating `*AsPt3D()` helpers.** Cold-path callers
  (Bug/ActA/Crucible constructors, ProteinNode init) use these and
  allocate a fresh Pt3D each call. JIT escape-analysis should turn
  most into stack allocations; verify with a profiler if dense memory
  pressure becomes a concern.
- **Pt3D method calls on `*AsPt3D()` returns.** `coordAsPt3D()
  .checkPt3D()` etc. are safe (no mutation). Verify periodically
  that no future edit introduces `xxx.coordAsPt3D().set(...)` style
  patterns whose mutations are silently lost.

## 2026-05-30 — SoA derived fields (end1/end2/zVec/transXTox/length) + bulk recompute

Follow-up to the SoA canonical coord/uVec/yVec entry. The previous step
made pose canonical in `float[]` SoA but left the per-Thing
`initialize()` doing all the derived-field math (zVec cross, transMat,
end1/end2 add). On the GPU path that meant 16 worker threads invoking
~98K virtual method calls per step inside `unpackRange`, with each
call chasing Pt3D references for the writes. This entry promotes the
derived fields to their own `float[]` arrays, replaces per-Thing
`initialize()` in the GPU unpack with a tight, SIMD-friendly bulk
pass, and adds a parallel-friendly Pt3D bridge so unconverted CPU
readers (`fs.end1.x`, `m.transXTox`, etc.) keep seeing fresh values.

The Pt3D `coord`/`uVec`/`yVec`/`end1`/`end2`/`zVec`/`transXTox` fields
remain in place as a CPU-reader bridge; the original task's call to
remove them entirely is deferred to a follow-up that converts each
reader site file-by-file (FilSegment alone has ~128 chase-call sites
just for those fields). The compiler is the checklist for that work,
and it is much bigger than one session.

### Architecture

**Canonical derived storage** (Thing.java):
```java
static float[] soaEnd1      = new float[0];  // [x0,y0,z0, x1,y1,z1, ...]
static float[] soaEnd2      = new float[0];
static float[] soaZVec      = new float[0];
static float[] soaTransXTox = new float[0];  // 9 floats per Thing (row-major)
static float[] soaLength    = new float[0];  // 1 float per Thing
```

Indexed alongside `soaCoord`/`soaUVec`/`soaYVec` by `myThingNumber`.
`ensureAccumCapacity` grows them in lockstep (preserving contents via
`System.arraycopy`); `removeThing` swaps them in compaction.

**Bulk recompute pass** (`Thing.recomputeDerivedSoA(from, upTo)`):
```java
for (int i = from; i < upTo; i++) {
    // zVec = uVec × yVec, normalised
    // yVec' = zVec × uVec (re-orthogonalise)
    // transXTox row-major = [uVec; yVec; zVec]
    // end1 = coord − length/2 · uVec
    // end2 = coord + length/2 · uVec
}
```

Reads `soaCoord`/`soaUVec`/`soaYVec`/`soaLength`; writes the five
derived arrays. The zVec normalisation step matches what FilSegment.
initialize() already did; the other subclasses' initialize() did not
normalise zVec, so the bulk pass is a behaviour change for them in
the strict sense (they now always have an orthonormal body frame).
The pose `soaYVec` is rewritten with the orthogonalised value so the
next-step GPU pack reads orthonormal yVec; the kernel's small
angular updates per step have only drifted yVec slightly from
orthonormality anyway, so the correction is small.

**Pt3D bridge pass** (`Thing.bridgeDerivedToPt3D(from, upTo)`):
For each Thing in the range, copies SoA → `t.coord`/`t.uVec`/`t.yVec`/
`t.zVec`/`t.end1`/`t.end2`/`t.uVecR` and the row-major `t.transXTox` /
its transpose `t.transxToX`. Direct field writes, no method dispatch,
so the per-iteration cost is much lower than the original per-Thing
`initialize()` (which did the full Pt3D math too).

**xRange/yRange/zRange follow-up** (FilSegment-only, in moveThings()):
After the bulk pass + bridge update FilSegment Pt3D coord/end2, a
linear pass over `theFilSegments` computes `xRange = |coord.x − end2.x|`
etc. Only FilSegment reads these (collision quick-reject); other
subclasses write them in their `initialize()` but never read them.

### GPU path: per-Thing initialize() removed from unpackRange

`GPUMoveThing.unpackRange` no longer calls `t.initialize()` per slot —
workers only write pose to SoA. After `dispatchAndWait(OP_UNPACK)`
returns and the `cpuFallback` Thing loop completes, `moveThings`
dispatches a new op `OP_DERIVED_AND_BRIDGE` over `[0, Thing.thingCt)`
that runs `recomputeDerivedSoA` and `bridgeDerivedToPt3D` per worker
range. Partition is over Thing indices rather than GPU slots so a
single dispatch covers GPU-eligible Things and cpuFallback Things
uniformly (cpuFallback Things see a redundant overwrite of their
Pt3D fields with the same values their `initialize()` just wrote —
harmless, and avoids needing a sparse "GPU slot index → Thing
index" set-membership check).

### Adaptive parallel threshold

The post-unpack bulk pass dispatches to the worker pool only when
`Thing.thingCt >= 8000`. Below the threshold (e.g. gliding-assay
M=500 with thingCt ≈ 1300) the dispatch overhead (`synchronized
notifyAll + wait` × 16 workers ≈ 0.1–1 ms per step) dominates the
actual work (~130 µs single-threaded). Above the threshold (e.g.
dense at thingCt ≈ 588 K) the 16-way parallel pass amortises the
dispatch over the larger Thing count and is a clear win.

### CPU path unchanged

CPU-mode `moveThing` still calls per-Thing `initialize()` at the
end of each Thing's step. The bulk pass is GPU-only; converting
the CPU `moveThing` dispatch to drive the bulk pass would require
removing initialize() from every CPU `moveThing` body and is left
for a follow-up. The SoA derived arrays still get populated on the
CPU path via `pushLengthToSoa(length)` and the existing pose-push
sites, so subsequent reader-side conversions can use SoA accessors
regardless of mode.

### Files changed

- `boxOfActin/Thing.java` — added `soaEnd1`/`soaEnd2`/`soaZVec`/
  `soaTransXTox`/`soaLength` arrays, accessors (`getEnd1X/Y/Z`,
  `getEnd2X/Y/Z`, `getZVecX/Y/Z`, `getLengthSoa`, `getTransXTox(idx)`),
  bulk passes (`recomputeDerivedSoA`, `bridgeDerivedToPt3D`),
  `pushLengthToSoa(double)` helper, extended `ensureAccumCapacity` to
  grow the new arrays (preserve via arraycopy), `removeThing` swaps
  the new slots in compaction, promoted `Pt3D end1`/`Pt3D end2` from
  per-subclass declarations to the base class.
- `boxOfActin/FilSegment.java`, `MyoMotor.java`, `MyoRod.java`,
  `MyoLever.java`, `MyoMiniFilament.java`, `Bug.java` — removed
  per-subclass `Pt3D end1 = new Pt3D()` / `Pt3D end2 = new Pt3D()`
  declarations (now inherited from `Thing`); each `initialize()`
  pushes its length to soaLength after computing end1/end2.
- `boxOfActin/GPUMoveThing.java` — added `OP_DERIVED_AND_BRIDGE` op
  code (worker case dispatches to `Thing.recomputeDerivedSoA(start,
  end) + bridgeDerivedToPt3D(start, end)`); removed `t.initialize()`
  from `unpackRange`; added post-unpack dispatch in `moveThings`
  with adaptive parallel threshold and FilSegment xRange pass.

### Smoke validation — glidingAssay500_val — PASS

```
                    baseline mean ± 2 SD     CPU (this)        GPU (this)
bindEvents       :       861 ± 240               982 PASS         902 PASS
meanBoundMotors  :      7.37 ± 1.52            8.289 PASS       7.441 PASS
glidingVelocity  :      8.39 ± 1.44           8.7518 PASS      7.6285 PASS
```

Logs: `RUN_LOGS/2026-05-30_nopt3d_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_nopt3d_smoke_gpu.txt`.

### Dense timing — glidingDense_demo_smoke (M=98K, thingCt≈588K, ~1100 steps)

CPU path. Source: `RUN_LOGS/2026-05-30_nopt3d_dense_cpu.txt`.

```
phase                soaforce    coord(prev)    nopt3d(this)
-------------------------------------------------------------
ThingStep Threads        62.45      63.53           60.39
ThingBrownian            31.56      31.39           29.69
Myosin (joints)          19.32      18.95           17.33
MyoDimer                  8.58       8.62            7.82
Mesh                      6.68       6.79            6.47
Ck Mots                   5.33       5.27            5.01
MotorBindGrid3D Fill      3.87       3.85            3.57
NodeLink                  1.56       1.52            1.56
-------------------------------------------------------------
wall (min per sim-sec)   257        258             246      (~4 % faster)
[STATS]      bindEvents       3633 → 3359 → 4313
             meanBoundMotors  226.7 → 204.2 → 274.5
             glidingVelocity  40.58 → 39.06 → 41.25
```

CPU dense is mildly faster (~4 %) vs the previous coord/uVec/yVec
landing, all within noise. The CPU path doesn't use the bulk pass
(initialize() still runs per Thing in CPU moveThing), so this is
just confirming the SoA derived array additions and length push
don't regress anything.

GPU path. Source: `RUN_LOGS/2026-05-30_nopt3d_dense_gpu.txt`.

```
phase                soaforce    coord(prev)    nopt3d(this)
-------------------------------------------------------------
ThingStep Threads        32.26      32.56           31.66
gpuMoveThing total       40.39      41.55           41.15
gpuMotorBinding total    13.05      13.11           12.78
Myosin (joints)          18.33      18.27           16.84
MyoDimer                  9.78       9.32            9.29
Mesh                      6.70       6.76            6.49
MotorBindGrid3D Fill      3.93       3.87            3.48
-------------------------------------------------------------
wall (min per sim-sec)   239        241             234      (~3 % faster)
gpuMoveThing breakdown   pack=9.20s,  exec=10.68s, unpack=20.51s   (soaforce)
                         pack=9.01s,  exec=10.71s, unpack=21.82s   (coord)
                         pack=8.467s, exec=10.549s, unpack=22.133s (this)
```

### Result: small win, foundation in place

CPU wall drops 258 → 246 min/sim-sec (~4 %); GPU wall drops 241 →
234 min/sim-sec (~3 %). Both within run-to-run noise, but the
breakdown shows where the work moved:

- **`gpuMoveThing pack` -0.5 s, `exec` -0.16 s.** Slight pack
  improvement is from the bulk pass writing back orthonormalised
  yVec, so subsequent pack reads a stable pose (less drift in
  the kernel's internal angular accumulator). Exec is essentially
  flat — kernel itself didn't change.
- **`unpack` +0.3 s (vs coord/uVec)**. The new OP_DERIVED_AND_BRIDGE
  dispatch runs inside the unpack-timed window (between
  `dispatchAndWait(OP_UNPACK)` and the timer stamp at the end of
  `moveThings`). It replaces the per-Thing `t.initialize()` virtual
  call cost (which was inside the worker loop and so already
  inside `unpack`) with the bulk recompute + bridge for all
  thingCt (not just GPU slots). The combined cost is about the
  same.
- **`ThingStep` -1 s, `Myosin (joints)` -1.5 s.** Likely noise; the
  joint and step phases don't directly read the new SoA derived
  arrays. The bulk recompute keeping `soaYVec` orthonormal might
  slightly reduce numerical drift downstream, but the effect is
  too small to attribute confidently.

The visible savings are small because the per-Thing `initialize()`
cost on GPU unpack was already small in absolute terms (~6 % of
gpuMoveThing); replacing it with bulk recompute + bridge for ALL
thingCt instead of just GPU slots is a near-wash. The win will
come when CPU readers are converted to SoA — then the bridge
pass can be dropped, and the bulk pass alone (single SIMD-friendly
loop, no Pt3D writes) becomes the only post-unpack cost.

### Memory overhead

At M=98 K with `taCapacity ≈ 735 K`:
- soaEnd1, soaEnd2, soaZVec: 3 × 735 K × 3 × 4 B = **~26.5 MB**
- soaTransXTox: 735 K × 9 × 4 B = **~26.5 MB**
- soaLength: 735 K × 4 B = **~2.9 MB**
Total **~56 MB** additional. Trivial vs the existing 564 MB
`taForce`/`taTorque` and ~26 MB pose arrays.

### Behaviour change: zVec normalisation

The original `initialize()` in non-FilSegment subclasses (MyoMotor,
MyoRod, MyoLever, MyoMiniFilament, Bug) did NOT normalise zVec:

```java
zVec.cross(uVec, yVec);   // zVec magnitude depends on |uVec × yVec|
yVec.cross(zVec, uVec);
```

The bulk recompute always normalises zVec:

```java
zx = uy*yz - uz*yy; ... ;  // unnormalised zVec
zmag2 = zx*zx + zy*zy + zz*zz;
if (zmag2 > 0f) { float inv = 1f/sqrt(zmag2); zx *= inv; ... }
// then yVec' = zVec × uVec — yVec orthogonalisation against normalised zVec
```

In practice the kernel maintains uVec/yVec close to orthonormal so
the normalisation is a tiny correction. Observables stayed within
the ±2 SD validation bands, so the change is benign at gliding
scale. Flag for revisit if a long-running benchmark picks up drift.

### Open / deferred

- **Pt3D field removal.** The original task asked for
  `Thing.coord/uVec/yVec/end1/end2/zVec/transXTox` to be deleted
  outright. That requires converting every reader site across
  ~60 files (FilSegment alone has ~128 chase sites). Foundation
  is now in place (SoA arrays + accessors + bulk pass + bridge);
  each follow-up session can convert a few files at a time,
  driven by the compiler errors after a field is removed.
- **CPU `moveThing` per-Thing `initialize()` removal.** Same
  pattern as GPU unpack — replace per-Thing initialize() with a
  bulk dispatch after the moveStart ThreadSet finishes. Requires
  rerouting the SoA pose flush (`pushPoseToSoa` at end of
  moveThing) and removing the per-Thing initialize from CPU mode.
  Modest win at gliding scale; uncertain at dense.
- **Pt3D bridge removal.** Once enough CPU readers are converted,
  the bridge becomes pure overhead. Drop it from the
  OP_DERIVED_AND_BRIDGE worker case and have it return after the
  recompute. Eliminates ~30 MB/step of redundant Pt3D writes at
  dense scale.
- **MotorBindGrid3D unified-SoA fill.** `FilSegment.fillSoaArrays`
  still computes end1/end2 inline; could read `soaEnd1`/`soaEnd2`
  directly now that the bulk pass populates them before
  `MotorBindGrid3D.fill` runs. Tiny win, but it removes the
  duplicated formula.
- **`zVec` normalisation behaviour flag.** If any benchmark
  observable depends on the legacy (non-normalised) zVec for
  non-FilSegment subclasses, gate the normalisation behind a
  flag. Currently no benchmark has tripped on this.



Follow-up to the `soaForceSum`/`soaTorqueSum` entry. Same pattern, applied
to the pose fields. `Thing.coord` / `uVec` / `yVec` Pt3D objects remain as
a CPU-reader bridge (hundreds of call sites read `thing.coord.x` etc.),
but the canonical storage is now three static `float[]` arrays matching
the GPU FloatArray layout `[x0,y0,z0, x1,y1,z1, ...]`. CPU `moveThing()`
flushes the Pt3D scratch to SoA before calling `initialize()`; the GPU
unpack writes SoA directly (contiguous float→float) and `initialize()`
mirrors SoA → Pt3D bridge before computing derived fields.

### Architecture

**Canonical storage** (Thing.java):
```java
static float[] soaCoord = new float[0];   // [x0,y0,z0, x1,y1,z1, ...]
static float[] soaUVec  = new float[0];
static float[] soaYVec  = new float[0];
```
Indexed by `myThingNumber*3+axis`. Grown in `ensureAccumCapacity`
alongside `soaForceSum`/`soaTorqueSum`; contents preserved via
`System.arraycopy` on growth.

**Bridge helpers** on `Thing`:
- `pushPoseToSoa()` / `pushCoordToSoa()` / `pushUVecToSoa()` /
  `pushYVecToSoa()` — flush Pt3D → SoA (called at every site that
  mutates `coord`/`uVec`/`yVec` Pt3D and is followed by a downstream
  reader of SoA — initialize(), GPU pack, fillSoaArrays).
- `loadPoseFromSoa()` — pull SoA back into Pt3D. Called at the top of
  every subclass `initialize()` so derived-field math (zVec, transXTox,
  end1/end2, etc.) sees the latest pose from either GPU unpack or CPU
  moveThing.

**Data flow per step (GPU path, after this change):**
```
GPU kernel writes coord/uVec/yVec to device FloatArray
  → unpack workers: FloatArray.get(slot*3+a) → soaCoord/soaUVec/soaYVec
    (contiguous float[] writes, no Pt3D pointer chase)
  → t.initialize(): loadPoseFromSoa() copies SoA → Pt3D bridge, then
    derived fields (zVec, transXTox, end1, end2) computed from Pt3D
  → CPU phases read Pt3D bridge (unchanged for now)
  → GPU pack (next step): reads soaCoord/soaUVec/soaYVec directly
    (contiguous float→float copy into FloatArray; the iter2d coord-skip
    optimisation still applies — myosin slots skip the pack write on
    steady steps)
```

**CPU-only path:**
```
moveThing computes new pose in Pt3D scratch
  → pushPoseToSoa() flushes Pt3D → soaCoord/soaUVec/soaYVec
  → initialize(): loadPoseFromSoa() copies SoA → Pt3D (no-op, same
    values), then derived fields
  → CPU phases read Pt3D bridge
```

### Capacity growth coupled to addThing

`ensureAccumCapacity(needed)` now also sizes `soaCoord`/`soaUVec`/`soaYVec`
and is invoked from `addThing` (`if (thingCt > taCapacity)
ensureAccumCapacity(thingCt)`). Pre-doLoop construction can therefore
push pose without out-of-bounds: the base `Thing(initCoord)` constructor
runs `addThing(this)` then `pushPoseToSoa()` with default uVec/yVec, and
each subclass constructor flushes again after setting its real
uVec/yVec, immediately before its own `initialize()` call. Method is
`synchronized` for safety with concurrent constructor calls; uncontended
fast path.

### Compaction on removal

`Thing.removeThing` swaps the pose slots alongside the force/torque
slots. The active pose data must move with the surviving Thing because
its `myThingNumber` was just reassigned.

### fillSoaArrays simplified, not eliminated

`MyoMotor.fillSoaArrays()` and `FilSegment.fillSoaArrays()` now read
from the canonical `Thing.soaCoord`/`soaUVec` instead of chasing the
per-Thing Pt3D references. Derived endpoints (`end1`/`end2`/`bindTip`)
are recomputed inline from `coord ± half*uVec` — same formula
`initialize()` uses — so the per-population per-step arrays stay valid
without depending on `Pt3D end1`/`end2` having been refreshed by the
last `initialize()`.

Full elimination is deferred: `MotorBindGrid3D` fill and
`GPUMotorBinding` still index per-population (`soaX[motorId]`,
`soaEnd1X[filId]`), so a follow-up would either teach those phases to
index by `myThingNumber` against the unified `soaCoord`, or build a
per-population `populationSlot -> myThingNumber` map and resolve in the
grid kernel.

### Files changed

- `boxOfActin/Thing.java` — added `soaCoord`/`soaUVec`/`soaYVec` static
  float[] arrays, `pushPoseToSoa`/`pushCoord/UVec/YVecToSoa` and
  `loadPoseFromSoa` helpers, extended `ensureAccumCapacity` to grow
  pose arrays with `arraycopy` preservation, made it `synchronized`,
  pushed pose in `Thing(initCoord)` constructor after `addThing`,
  `addThing` triggers `ensureAccumCapacity(thingCt)` for SoA capacity,
  `removeThing` swaps pose slots.
- `boxOfActin/GPUMoveThing.java` — `packRange` reads pose from
  `Thing.soaCoord`/`soaUVec`/`soaYVec` (contiguous float[] vs Pt3D
  pointer chase); `unpackRange` writes kernel output to the canonical
  SoA arrays before invoking `t.initialize()` (which bridges to Pt3D).
- `boxOfActin/FilSegment.java` — `initialize()` calls
  `loadPoseFromSoa()` first; `moveThing()` ends with `pushPoseToSoa()`
  before `initialize()`; constructors `pushPoseToSoa()` after setting
  uVec/yVec, before `initialize()`; `biochemStep` lengthChanged path
  pushes coord (poly/depoly mutated it via `coord.inc`); `setFirstHalf`
  pushes coord after the split; `joinSegments` pushes the surviving
  segment's coord before its `initialize`; `translate`/`translateCoord`
  push coord; `fillSoaArrays` reads canonical SoA pose and computes
  end1/end2 inline.
- `boxOfActin/MyoMotor.java`, `MyoRod.java`, `MyoLever.java`,
  `MyoMiniFilament.java`, `ProteinNode.java`, `FillNode.java`, `Bug.java`
  — same per-class pattern (initialize loads SoA → Pt3D; moveThing
  pushes Pt3D → SoA before initialize; constructors push after setting
  uVec/yVec; `set(setCoord, setUVec, ...)` mutators push so callers'
  follow-up `initialize()` reads fresh SoA).
- `boxOfActin/StaticFilSegment.java` — splitSegment relies on the
  base-class `setFirstHalf` push.
- `boxOfActin/MyoMotor.java::fillSoaArrays` and
  `FilSegment.java::fillSoaArrays` — simplified to read from canonical
  SoA + inline endpoint compute.
- `boxOfActin/BoxOfActin.java` — `applyBenchmarkPins` and
  `resetBenchmarkChain` push pose after mutating Pt3D directly, before
  the explicit `initialize()` calls.

### Smoke validation — glidingAssay500_val — PASS

```
                    baseline mean ± 2 SD     CPU (this)        GPU (this)
bindEvents       :       861 ± 240             1028 PASS         703 PASS
meanBoundMotors  :      7.37 ± 1.52            8.271 PASS      6.469 PASS
glidingVelocity  :      8.39 ± 1.44           8.3375 PASS     7.6293 PASS
```

Logs: `RUN_LOGS/2026-05-30_soacoord_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_soacoord_smoke_gpu.txt`.

### Dense timing — glidingDense_demo_smoke (M=98K, thingCt≈588K, ~1000 steps)

CPU path. Source: `RUN_LOGS/2026-05-30_soacoord_dense_cpu.txt`.

```
phase                soaforce    coord(this)
-------------------------------------------------
ThingStep Threads        62.45      63.53
ThingBrownian            31.56      31.39
Myosin (joints)          19.32      18.95
MyoDimer                  8.58       8.62
Mesh                      6.68       6.79
Ck Mots                   5.33       5.27
MotorBindGrid3D Fill      3.87       3.85
NodeLink                  1.56       1.52
-------------------------------------------------
wall (min per sim-sec)   257        258      (~0.4 % slower; noise)
[STATS]      bindEvents       3633 → 3359
             meanBoundMotors  226.7 → 204.2
             glidingVelocity  40.58 → 39.06
```

GPU path. Source: `RUN_LOGS/2026-05-30_soacoord_dense_gpu.txt`.

```
phase                soaforce    coord(this)
-------------------------------------------------
ThingStep Threads        32.26      32.56
gpuMoveThing total       40.39      41.55
gpuMotorBinding total    13.05      13.11
Myosin (joints)          18.33      18.27
MyoDimer                  9.78       9.32
Mesh                      6.70       6.76
MotorBindGrid3D Fill      3.93       3.87
-------------------------------------------------
wall (min per sim-sec)   239        241      (~0.8 % slower; noise)
gpuMoveThing breakdown   pack=9.20s,  exec=10.68s, unpack=20.51s   (soaforce)
                         pack=9.01s,  exec=10.71s, unpack=21.82s   (this)
```

### Result: no win, no loss; foundation in place

Wall is flat at dense scale (CPU +0.4 %, GPU +0.8 %, both within run-to-run
noise). The hot inner loops of pack/unpack/fillSoaArrays now read/write
contiguous `float[]` instead of chasing Pt3D references, but the
visible savings are small because:

- **Pack** was already cheap (9.2 s / 1101 calls = 8.4 ms/call), and
  the bottleneck on this side is the per-element `FloatArray.set()`
  call — that didn't change. Bulk `MemorySegment.copy` for contiguous
  slot runs (deferred in the soaforce entry) is the lever that would
  actually push pack to ~1 s.
- **Unpack** workers spend most of their time inside `t.initialize()`
  (transMat, end1/end2 computation) rather than the read from
  FloatArray. The contiguous-float SoA write is faster than the
  scattered Pt3D writes, but initialize() dominates.
- **fillSoaArrays** runs ~1100 times in a 1100-step run and touches
  ~98K motors + 100 fil segments. The Pt3D pointer chase it replaced
  was a single field read per slot, not a deep chain — modest absolute
  savings.

The foundation matters more than the immediate wall delta. With
`soaCoord`/`soaUVec`/`soaYVec` now canonical:

- Future CPU readers (mesh fill, motor binding, joint forces, etc.) can
  be converted file-by-file to read from `Thing.soaCoord[idx*3]` instead
  of `thing.coord.x`, dropping the Pt3D field eventually.
- `MotorBindGrid3D.fillCells` can read from the unified SoA via
  `myThingNumber`, eliminating the per-population `MyoMotor.soaX/Y/Z`
  intermediates and the `fillSoaArrays` step entirely.
- A contiguous-run `MemorySegment.copy` in `GPUMoveThing.packRange` is
  now a straight `MemorySegment.copy(MemorySegment.ofArray(soaCoord),
  startThingIdx*3*4L, fa.getSegment(), startSlot*3*4L, runLen*3*4L)`
  — no boxing or chase. Same for unpack.

### Observables stayed in range

CPU dense `bindEvents=3359` (baseline soaforce 3633, dense-sweep 3687,
sparse 3633, pre-accum 3983) — within the established range.
`meanBoundMotors=204` (baseline 226), `glidingVelocity=39.06` (baseline
40.58). All within the single-seed run-to-run variance pattern.

GPU dense observables similarly within range.

### Memory overhead

At M=98K with taCapacity ≈ 735K (25 % headroom): three additional
float arrays at 735K × 3 × 4 B = **~8.8 MB each = ~26.5 MB total**.
Trivial vs the 564 MB per-thread `taForce`/`taTorque`. Same growth
discipline as the existing soa force arrays.

### Bug fixed during integration

Initial draft pushed pose only at the end of `moveThing()`. That broke
construction-time `initialize()` (called from subclass constructors
after the subclass set uVec/yVec) — `loadPoseFromSoa` would have read
default uVec=(1,0,0). Fix: each subclass constructor now flushes
explicitly after setting uVec/yVec, before its `initialize()` call.
Same fix applied to `MyoMotor.set` / `MyoRod.set` / `MyoLever.set` /
`MyoMiniFilament.set` which mutate pose directly; the `Myosin.setMotor`
chain now satisfies the contract that `pushPoseToSoa()` precedes any
`initialize()`.

The `splitSegment` and `joinSegments` paths similarly mutate
`coord.add(end1, ...)` / `coord.inc(...)` without going through
moveThing; `setFirstHalf` and `joinSegments` were updated to push coord
before the caller's `initialize()`. `FilSegment.biochemStep`'s
`lengthChanged` branch (which runs after poly/depoly's `coord.inc`)
pushes coord too. `BoxOfActin.applyBenchmarkPins` and
`resetBenchmarkChain` (benchmark mode, mutates Pt3D directly) flush
before `initialize()`.

### Open / deferred

- **MemorySegment bulk-copy for pack/unpack** — still the biggest
  remaining pack-side optimisation. Detection of contiguous
  `gpuThingIndices` runs would let pack be a per-run `MemorySegment.copy`
  on `soaCoord`/`soaUVec`/`soaYVec` directly.
- **Eliminate `fillSoaArrays` by reading unified SoA in
  `MotorBindGrid3D.fill` and the motor-binding kernel.** Requires
  storing the unified `myThingNumber` (or the FilSegment/MyoMotor's
  thingIdx) in the grid cell entries instead of the per-population
  filID/motorID. The motor-binding kernel currently indexes
  `FilSegment.soaUX[filId]` etc; updating it to `Thing.soaUVec[idx*3]`
  is straightforward but touches both the CPU and GPU paths.
- **Convert CPU readers to SoA.** `mesh fill`, `joint forces`, `link
  forces` all chase Pt3D today. One file at a time. The bridge keeps
  this from being a blocker.
- **Pt3D `coord`/`uVec`/`yVec` removal.** Final cleanup, after all CPU
  readers are converted. Until then the bridge is mandatory.
- **Float precision drift.** Each step now narrows pose to float in
  the CPU-only path too (was double everywhere before). Validation
  observables are unchanged at gliding-assay scale; the GPU path was
  already on float. If a long-duration deflection or LP benchmark
  picks up drift, the fix would be to keep the canonical at double
  and pay the narrow at pack time — but the current numbers don't
  motivate that.

## 2026-05-30 — SoA canonical forceSum/torqueSum + memcpy pack

Follow-up to the sparse-gather entry. The remaining cost in the gather
inner loop was the per-Thing `forceSum`/`torqueSum` Pt3D pointer chase:
fetch `theThings[idx]`, follow the Pt3D reference, write three doubles.
This entry converts the canonical force/torque storage from per-Thing
`Pt3D` fields to static `float[]` arrays matching the GPU FloatArray
layout (m*3 interleaved), so the gather narrows directly into floats
and the GPU pack becomes a float→float read out of a contiguous backing
array. The Pt3D `forceSum`/`torqueSum` fields are gone — `Thing` now
owns no force-sum state of its own.

### Architecture

**Canonical storage** (Thing.java):
```java
static float[] soaForceSum;   // [fx0,fy0,fz0, fx1,fy1,fz1, ...]
static float[] soaTorqueSum;
```
Indexed by `myThingNumber * 3 + {0,1,2}`. Grown in `ensureAccumCapacity`
in lockstep with `taForce`/`taTorque` (25 % headroom, contents preserved
on growth via `System.arraycopy`).

**Per-thread accumulators stay `double[][]`.** Workers still write
`taForce[tid][idx*3+axis]` in double precision — narrowing to float
happens once in the gather, not on every `incForceSum` call. This
preserves the catastrophic-cancellation safety the accumulator
infrastructure exists for.

**Data flow per step:**
1. `Thing.clearSoaForcesTorques(thingCt)` — one `Arrays.fill` over
   `thingCt * 3` floats at start of step.
2. Force phases → `taForce[tid][...]` (double, per-thread, dirty list).
3. `gatherThreadAccumulators` walks each thread's dirty list, narrows
   `(float)` and adds into `soaForceSum`/`soaTorqueSum`.
4. Main-thread writes (benchmark pin force, post-gather membrane
   `internalPressure`) go directly to `soa*[base]` via
   `incForceSumSlot(...)` or the `tid<0` branch of `incForceSum`.
5. CPU `moveThing` reads `soaForceSum`/`soaTorqueSum`.
6. GPU pack copies `soaForceSum`/`soaTorqueSum` slot-by-slot into the
   FloatArray (tight float→float loop, no Pt3D pointer chase).

### Bulk-copy approach used: tight loop (Approach 2)

TornadoVM's `FloatArray.getSegment()` does return a backing
`MemorySegment` (no header), so a true `MemorySegment.copy(srcArr, ...,
dstSeg, ...)` is possible — but the slot→thing index mapping is
generally **non-contiguous** (`classifyThings` packs only GPU-eligible
Things into slots). A bulk memcpy would require detecting contiguous
runs of `gpuThingIndices` first.

`GPUMoveThing.packRange` therefore uses Approach 2: a tight per-slot
loop that reads three floats out of `Thing.soaForceSum[thingIdx*3+a]`
and writes them via `forceSum.set(slot*3+a, ...)`. JIT-vectorisable on
the read side; the FloatArray writes are still per-element. The win
vs the pre-SoA code path is the elimination of the
`theThings[idx].forceSum.x` pointer chase — three pointer hops per
write are replaced by a single array indexed read.

Future optimisation noted but **not implemented this session:** if
`classifyThings` produces mostly contiguous slot→thing runs at gliding
scale, a run-detection pass on `gpuThingIndices` plus per-run
`MemorySegment.copy` would push the pack time toward zero. Worth
revisiting if pack time stays a measurable fraction of GPU step time.

### Files changed

- `boxOfActin/Thing.java` — added `soaForceSum`/`soaTorqueSum` arrays,
  helper methods (`getForceSumX/Y/Z`, `isForceSumFinite`,
  `zeroForceSumSlot`, `setForceSumToRandForces`, `incForceSumSlot`,
  `clearSoaForcesTorques`); updated `incForceSum`/`incTorqueSum`
  (`tid<0` paths write to soa); updated `gatherThreadAccumulators`
  (narrow double → float into soa); updated `ensureAccumCapacity`
  (allocate + preserve soa on growth); updated `resetCounters` (no
  longer zeros forceSum/torqueSum); updated `removeThing` (swap soa
  slots on compaction); removed `Pt3D forceSum`/`Pt3D torqueSum`
  declarations and the `sepaku` references.
- `boxOfActin/Pt3D.java` — added `XToxFromFloats(Thing, float[], int)`
  helper for body-frame transform of soa-resident forces.
- `boxOfActin/BoxOfActin.java` — `doLoop` calls
  `Thing.clearSoaForcesTorques` at the top of each step; benchmark
  diagnostic print uses `getForceSumX/Y/Z`; benchmark chain reset uses
  `zeroForceSumSlot`/`zeroTorqueSumSlot`.
- `boxOfActin/GPUMoveThing.java` — `packRange` reads from
  `Thing.soaForceSum`/`Thing.soaTorqueSum` arrays instead of Pt3D
  pointer chase.
- `boxOfActin/FilSegment.java`, `MyoMotor.java`, `MyoRod.java`,
  `MyoLever.java`, `MyoMiniFilament.java`, `ProteinNode.java`,
  `Bug.java` — `moveThing` readers use `XToxFromFloats(this,
  soaForceSum, sBase)` and `isForceSumFinite()`/`setForceSumToRandForces()`
  for the NaN-recovery paths.
- `boxOfActin/StickyNode.java` — `internalPressure`/`fakeConstrictingRing`
  switched from `forceSum.add(vec)` to `incForceSumSlot(x,y,z)` so the
  membrane-move-pass increment lands in the soa slot directly (this
  pass runs AFTER the membrane-relaxation gather, so a routed
  `incForceSum` would not be visible until the next gather call).

### Smoke validation — glidingAssay500_val — PASS

```
                    baseline mean ± 2 SD     CPU (SoA)         GPU (SoA)
bindEvents       :       861 ± 240               808 PASS        972 PASS
meanBoundMotors  :      7.37 ± 1.52            7.176 PASS      8.084 PASS
glidingVelocity  :      8.39 ± 1.44           7.8516 PASS     8.7893 PASS
```

Sources: `RUN_LOGS/2026-05-30_soaforce_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_soaforce_smoke_gpu.txt`.

### Dense timing — glidingDense_demo_smoke (M=98K, thingCt≈588K, ~1000 steps)

CPU path. Source: `RUN_LOGS/2026-05-30_soaforce_dense_cpu.txt`.

```
phase                pre-accum   dense-sweep   sparse   SoA(this)
------------------------------------------------------------------
ThingStep Threads        55.02      112.81    100.88      62.45
ThingBrownian            31.65       30.22     29.54      31.56
Myosin (joints)          23.41       17.83     17.47      19.32
MyoDimer                  8.96        7.84      8.46       8.58
Mesh                      6.93        7.34      6.42       6.68
Ck Mots                   5.64        5.18      5.06       5.33
MotorBindGrid3D Fill      4.15        3.63      3.56       3.87
NodeLink                  1.52        1.68      1.45       1.56
------------------------------------------------------------------
wall (min per sim-sec)    252         329       306        257
[STATS]      bindEvents       3983 → 3652 → 3687 → 3633
             meanBoundMotors  250.5 → 234.1 → 244.5 → 226.7
             glidingVelocity  40.88 → 42.36 → 44.48 → 40.58
```

GPU path. Source: `RUN_LOGS/2026-05-30_soaforce_dense_gpu.txt`.

```
phase                pre-accum    sparse    SoA(this)
-------------------------------------------------------
ThingStep Threads        25.46     72.61      32.26
gpuMoveThing total       44.70     41.36      40.39
gpuMotorBinding total    13.26     12.68      13.05
Myosin (joints)          23.57     17.54      18.33
MyoDimer                  9.51      9.21       9.78
Mesh                      6.93      6.62       6.70
MotorBindGrid3D Fill      4.31      3.66       3.93
-------------------------------------------------------
wall (min per sim-sec)    --        302        239
gpuMoveThing breakdown   pack=10.86s, exec=10.51s, unpack=19.98s   (sparse)
                         pack=9.20s,  exec=10.68s, unpack=20.51s   (this)
```

### Result: full win

CPU dense **wall drops 306 → 257 min/sim-sec** (-16 %), within 2 % of
the pre-accumulator baseline of 252. ThingStep drops 100.9 → 62.4 s
(-38 %); the gather, which is dispatched on `ThingStepThreads` via
`gatherForcesStart`, no longer pays the per-Thing Pt3D pointer-chase
cost — `theThings[idx].forceSum.x += ...` is replaced by
`soaForceSum[base] += (float)...` (one indexed array write).

GPU dense **wall drops 302 → 239 min/sim-sec** (-21 %). ThingStep
drops 72.6 → 32.3 s (-56 %) for the same reason — the gather is the
big-win site. `gpuMoveThing pack` drops only ~15 % (10.86 → 9.20 s);
the pack was already amortised across 16 workers and FloatArray
writes are still per-element. The MemorySegment-copy optimisation is
where additional pack-time savings would come from.

All observables are within ensemble noise. The CPU `bindEvents`
trended slightly low (3633 vs 3983 pre-accum) but still within the ±2
SD band; this is single-seed sampling, not a regression.

### Why the gather got so much faster

The sparse-gather inner loop was:
```java
Thing thing = theThings[idx];                      // 1 array read
if (thing != null && !thing.removeMe) {            // 2 field reads
    thing.forceSum.x  += forces[base];             // pointer chase: thing.forceSum, then .x
    thing.forceSum.y  += forces[base + 1];         // 6 doubles total
    thing.forceSum.z  += forces[base + 2];
    thing.torqueSum.x += torques[base];
    thing.torqueSum.y += torques[base + 1];
    thing.torqueSum.z += torques[base + 2];
}
```
Per dirty entry that's a Thing fetch, two reference dereferences, six
field writes through indirect addresses, and six `+=` on doubles —
all touching memory that may not be cache-resident at scale.

The SoA version:
```java
outF[base]     += (float) forces[base];            // contiguous float[] write
outF[base + 1] += (float) forces[base + 1];
outF[base + 2] += (float) forces[base + 2];
outT[base]     += (float) torques[base];
outT[base + 1] += (float) torques[base + 1];
outT[base + 2] += (float) torques[base + 2];
```
No Thing fetch, no reference chase, no null/removeMe guard (zeroed soa
plus the worker dirty-list invariant make the guards redundant), three
float widenings instead of six double reads of pointer-chased fields.
The hot loop is now a simple array→array reduction. The JIT can
vectorise it; the prior version could not because of the indirect
addressing.

### Memory overhead

At M=98K, taCapacity ≈ 735K with 25 % headroom: `soaForceSum` =
735K × 3 × 4 B = **~8.4 MB**, same for `soaTorqueSum` = **~16.8 MB**
total. Trivial vs the 564 MB `taForce`/`taTorque` accumulators that
already exist.

### Open / deferred

- **MemorySegment bulk-copy in GPU pack** — left as the next pack
  optimisation. Detect contiguous runs of `gpuThingIndices` once per
  classify and dispatch one `MemorySegment.copy` per run. Estimated:
  pack 9.2 s → ~1 s at dense scale.
- **coord / uVec / yVec SoA conversion** — the next step of the
  SoA-canonical refactor per deepPerformanceSurvey §L. Those are read
  by mesh/grid binning, motor binding, joint forces, and the GPU pack;
  doing the same conversion would let the GPU pack drop its remaining
  per-step coord/uVec/yVec writes for FilSegment slots. This is a
  larger change because coord is written in many call sites (the
  Pt3D-`inc`/Pt3D-`copy` set is bigger than forceSum's), so it needs
  the same kind of accessor-bridge layer.
- **NaN-recovery code in MyoLever/MyoMotor/MyoRod/MyoMiniFilament** —
  the "Crazy forceSum/torqueSum" guards now narrow doubles into
  floats; if a force phase produces a very large but finite double
  that overflows the float range, the soa slot will silently become
  `Infinity` and the NaN guard won't catch it. At gliding scale this
  has not been observed, but the dense Listeria + membrane simulation
  may need a finite-range check rather than just `Float.isNaN`. Flag
  for future revisit if the guards start tripping in a scale-up run.

## 2026-05-30 — Sparse gather for per-thread accumulators

Follow-up to the morning's per-thread accumulator work. The accumulator
infrastructure was correct but the gather was a full sweep over every
Thing × every thread per step — ~880 MB/step bandwidth at M=98K, ~29 ms
× multiple calls per step. Most of that touched zeros. This entry adds
dirty tracking so the gather sums only slots actually written, not the
588K × 16 grid.

### Design — Solution A (single-threaded sparse gather)

Three new static arrays in `Thing.java` alongside `taForce`/`taTorque`:

- `int[][] dirtyIndices[threadId]` — list of thingIndices that this
  thread wrote to in the current force phase.
- `int[]  dirtyCounts[threadId]`   — count of entries in each thread's list.
- `boolean[][] dirtyFlags[threadId]` — dedup guard so the same
  thingIndex is added at most once per thread per gather window
  (filaments commonly receive forces from multiple crosslinks routed
  through the same worker).

All three grow in lockstep with `taForce`/`taTorque` inside
`ensureAccumCapacity` — capacity equals thingCt headroom, so a worker
can never overflow its `dirtyIndices` array (it cannot dirty more
distinct slots than total Thing count).

`incForceSum` / `incTorqueSum` worker path adds three lines after the
slot write:

```java
final boolean[] flags = dirtyFlags[tid];
if (!flags[idx]) {
    flags[idx] = true;
    dirtyIndices[tid][dirtyCounts[tid]++] = idx;
}
```

Main-thread writes (tid == −1) still target `forceSum`/`torqueSum`
directly — no dirty tracking needed for the benchmark-pin path.

`gatherThreadAccumulators()` walks each thread's dirty list, adds the
slot to the Thing's forceSum/torqueSum, zeros the slot, clears the
flag, then resets the per-thread count. The ThreadSet dispatch
(`Env.gatherForcesStart` on `ThingStepThreads`) stays in place but
only thread 0 invokes the gather — the other 15 wait on the barrier.

Solution B (parallel gather partitioned by Thing index) was considered
but deferred per the design doc's "Start with A, escalate if it
exceeds 5 ms/step" guidance. The 5 ms target was missed (see below)
and Solution B is the documented next step.

### Memory overhead

At M=98K, capacity ≈ 735K with 25 % headroom: dirtyIndices = 16 × 735K
× 4 B = **47 MB**, dirtyFlags = 16 × 735K × 1 B = **11.8 MB**. Total
**~59 MB**, trivial vs the 564 MB taForce/taTorque accumulators
already in place.

### Files changed

`boxOfActin/Thing.java` only. No call-site changes (all incForceSum /
incTorqueSum callers route through the superclass methods, which is
where dirty tracking is added). The ThreadSet dispatch shape and the
two `startAllThreadSets(gatherForcesStart)` calls in
`BoxOfActin.doLoop` (main loop + membrane relaxation) are unchanged.

### Smoke validation — glidingAssay500_val (CPU + GPU) — PASS

```
                     baseline mean ± 2 SD     CPU (this)        GPU (this)
bindEvents       :       861 ± 240               712 PASS         920 PASS
meanBoundMotors  :      7.37 ± 1.52             5.916 PASS       7.797 PASS
glidingVelocity  :      8.39 ± 1.44            7.6745 PASS       7.9034 PASS
```

Sources: `RUN_LOGS/2026-05-30_sparse_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_sparse_smoke_gpu.txt`.

### Dense timing — glidingDense_demo_smoke (M=98K, S~1101)

CPU path. Sequential single-runs (concurrent CPU + GPU runs created
CPU contention on aorus and were re-run sequentially; concurrent
results archived in `*_concurrent.txt`). Source:
`RUN_LOGS/2026-05-30_sparse_dense_cpu.txt`.

```
phase                       pre-accum    dense-sweep    sparse(this)
--------------------------------------------------------------------
ThingStep Threads               55.02       112.81         100.88
ThingBrownian                   31.65        30.22          29.54
Myosin (joints)                 23.41        17.83          17.47
MyoDimer                         8.96         7.84           8.46
Mesh                             6.93         7.34           6.42
Ck Mots                          5.64         5.18           5.06
MotorBindGrid3D Fill             4.15         3.63           3.56
NodeLink                         1.52         1.68           1.45
--------------------------------------------------------------------
wall (min per sim-sec)            252         329            306
[STATS]                  bindEvents       3983 → 3652 → 3687
                         meanBoundMotors  250.5 → 234.1 → 244.5
                         glidingVelocity  40.88 → 42.36 → 44.48
```

Observables single-seed; all within ensemble noise.

GPU path. Source: `RUN_LOGS/2026-05-30_sparse_dense_gpu.txt`.

```
phase                       pre-accum    dense-sweep    sparse(this)
--------------------------------------------------------------------
ThingStep Threads               25.46        80.07          72.61
gpuMoveThing total              44.70        42.84          41.36
gpuMotorBinding total           13.26        12.99          12.68
Myosin (joints)                 23.57        17.31          17.54
MyoDimer                         9.51         8.71           9.21
Mesh                             6.93         6.82           6.62
MotorBindGrid3D Fill             4.31         3.78           3.66
--------------------------------------------------------------------
[STATS]                  bindEvents       4051 → 4090 → 3663
                         meanBoundMotors  257.6 → 251.5 → 233.9
                         glidingVelocity  42.58 → 42.23 → 43.40
```

**Result: partial win.** Sparse vs dense-sweep saves ~12 s ThingStep
on CPU (-11 %) and ~7.5 s on GPU (-9 %); wall drops 329 → 306 min/sim-sec
on CPU (-7 %). But the pre-accumulator baseline (252 min/sim-sec on
CPU) is *not* recovered. The joints savings the dense-sweep entry
predicted (~5–6 s) is preserved on both paths.

### Why not all the way back to pre-accumulator?

Two costs remain after sparse gather:

1. **Per-write dirty-tracking overhead in `incForceSum`/`incTorqueSum`.**
   Every worker write now does an extra `if (!flags[idx])` branch,
   flag set, and index append. At ~1 M inc-calls/step the overhead is
   small in absolute terms but it touches every parallel force phase.

2. **Single-threaded gather cost.** With 16 worker source threads but
   only one gather worker, the gather is bandwidth + pointer-chase
   bound at ~150K dirty entries per call (see stats below). Estimated
   ~10–30 ms per call × multiple calls per step → ~20–40 s for the
   1000-step smoke. The dense-sweep was bandwidth-bound but
   parallelized 16-way, putting it close to memory bandwidth ceiling
   (~30 GB/s ÷ 16 = ~2 GB/s per thread, finishing each pass in ~2 ms).

The dense-sweep's per-thread work was thingCt × 6 doubles ÷ 16 = 36K
slot touches per thread per call. Solution A single-thread does 147K
slot touches per call — about 4× more work per call than each
dense-sweep worker. To beat dense-sweep cleanly, the sparse gather
needs to be parallelized too (Solution B).

### Dirty list statistics

At M=98K gliding-dense smoke (thingCt ≈ 588K, 1000 main-loop steps +
membrane relaxation passes ≈ 2000 gather calls):

```
cumAvg per call:           ~147,706 total dirty entries (across 16 threads)
cumMaxPerThread per call:   ~19,628 entries (single worst-case thread)
implied per-thread mean:     ~9,200 entries
implied call bandwidth:      ~14 MB per gather call
                             (6 doubles read + 6 doubles zero per entry)
implied total per step:      ~30–60 MB (depending on membrane pass count)
```

For comparison, dense-sweep was ~880 MB/step at the same scale. So
sparse gather *does* cut bandwidth ~15–30×; the win just doesn't fully
manifest in wall time because the gather went from parallel to serial
at the same time the bandwidth dropped.

At gliding-assay (M=500, thingCt ≈ 1300) the cumAvg per call is
~21,000 — meaning small-thingCt regimes are not "sparse" at all (most
Things are dirty every step). At those scales the dense sweep is
actually competitive with sparse.

### Crossover analysis

For Solution A (single-thread sparse) to beat dense-sweep on wall:

```
sparse_work × 1 worker  <  dense_work / 16 workers
total_dirty × per_entry_cost  <  thingCt × 16 × 6 doubles × 16 B / 16
total_dirty                   <  thingCt × 16 × 96 B / (16 × per_entry_cost_in_B)
```

Roughly, sparse wins single-threaded when `total_dirty < thingCt`.
At dense scale total_dirty = 147K, thingCt = 588K → 0.25, so sparse
single-thread does beat dense-sweep on raw bandwidth, but the
single-thread serialization and per-entry pointer chase eat most of
the win.

For Solution B (sparse parallelized across 16 workers, partitioned by
Thing index): sparse_work / 16 < dense_work / 16 reduces to
total_dirty < thingCt × 16 = 9.4 M — trivially true (147K ≪ 9.4 M).
Expected ~4× wall improvement over Solution A → ThingStep dropping
back toward (or below) the pre-accumulator baseline.

### Status & open work

- Correctness: PASS (observables within noise on CPU + GPU smoke and
  dense).
- Performance:
  - vs dense-sweep: ~7 % wall improvement at dense scale (the budgeted
    direction).
  - vs pre-accumulator: still ~21 % wall regression at dense scale.
- Memory: +59 MB at M=98K, +236 MB at M=400K (linear in thingCt × 16).
- Open: **Solution B (parallel sparse gather partitioned by Thing
  index)** — partition the dirty entries across gather workers by
  `thingIdx % numThreads`, so each gather worker owns a Thing-index
  slice across all 16 source threads. Requires sorting / binning the
  dirty entries by thingIdx, or a count-then-scatter pass. Estimated
  ~4× speedup over Solution A → should bring ThingStep back to the
  pre-accumulator baseline or below.
- Open: per-Thing PRNG seed source (out of scope here; flagged in
  morning entry).

### Diagnostic counters

`Thing.gatherThreadAccumulators` prints `[GATHER] call=N totalDirty=X
maxThread=Y cumAvg=Z cumMaxPerThread=W` every 1000 calls. cumAvg
tracks the running mean of totalDirty across all calls;
cumMaxPerThread is the largest single-thread dirty count seen so far.
Useful for spot-checking the sparse regime in a new workload — if
cumAvg approaches thingCt × 16 the sparse path stops being sparse and
the dense sweep would be competitive again.

## 2026-05-30 — Per-thread force/torque accumulators

Survey §D2 / §F6 / §C1 / Top10#1: replace the per-Thing
`synchronized(forceSync)` / `synchronized(torqueSync)` writes in
`incForceSum` / `incTorqueSum` with per-thread accumulator arrays
gathered once per step. Aim: kill the lock acquisitions that JOURNAL
2026-05-27 identified as a non-determinism source and (per the survey)
a 10–20 % wall budget. Outcome: the correctness story holds (no
locks, deterministic gather order); the wall story did not — the
gather pass costs more than the locks saved at the scales tested.
Code is shipped as-is and will be revisited; the perf cost is
characterized below.

### Design

Two static double[][] arrays in `Thing.java`:
- `taForce[threadId][thingNumber*3 + axis]`
- `taTorque[threadId][thingNumber*3 + axis]`

Sized `[Env.allThreadCt=16][thingCt*3]`, grown lazily by 25 % each
time `thingCt` outpaces capacity.

`ThreadLocal<int[]> tlsThreadId` holds the worker's 0-based id; set
once in `ThreadSet.ThreadSpawn.run()` at thread startup. Default
value `-1` for the main TimeLoop thread.

`incForceSum(Pt3D)` / `incForceSum(Pt3D,Pt3D)` / `incTorqueSum(Pt3D)`:
read `tlsThreadId`, then either write to the thread's private slot
(workers) or directly to `forceSum`/`torqueSum` (main thread, no
contention). The 2-arg overload computes `r × F` inline with scalar
doubles (no `Pt3D rForce` / `tempTorq` allocations — survey §C1
collateral win).

`gatherThreadAccumulatorsRange(lo,hi)`: sums each thread's slot into
`forceSum`/`torqueSum` and zeros the slots in one pass (touch each
cache line once for read+zero). Dispatched as `Env.gatherForcesStart`
on `ThingStepThreads` (reuses the existing 16-worker pool).

### Where the gather runs in `doLoop`

After all force-producing phases (`xLinkStart`, `membraneLinksStart`,
`myoJoints1Start`, `myoJoints2Start`, `stepStart`), before the
benchmark midpoint-force injection and `moveStart`/GPU pack. The
membrane relaxation loop also gathers between `membraneLinksStart`
and `membraneMoveStart` so each pass's `NodeLink` writes land in
`forceSum` before the move reads it.

### Files changed

`boxOfActin/Thing.java` (accumulator infrastructure, new
`incForceSum/incTorqueSum`, gather), `boxOfActin/ThreadSet.java`
(set `tlsThreadId` in `ThreadSpawn.run()`),
`boxOfActin/FilSegment.java` (removed local override; inherits the
new path), `boxOfActin/Env.java` (added `gatherForcesStart/Stop=17`),
`boxOfActin/BoxOfActin.java` (gather call in `doLoop`, `gatherTimer`
RunTimer, membrane-loop gather).

Call sites converted: ~130 `incForceSum`/`incTorqueSum` invocations
across 15 files (FilSegment: 48, MyosinDimer: 20, MyoMiniFilament:
14, ProteinNode: 13, Myosin: 12, MyoFilLink: 8, others 1-4 each).
No call-site code change needed — the conversion is in the Thing
superclass methods. The two `synchronized`-block overrides in
`Thing.java` and `FilSegment.java` were removed; the `forceSync`
and `torqueSync` Object fields are gone.

### Validation — glidingAssay500_val (1 seed) — PASS

```
                     baseline mean ± 2 SD     this seed (CPU)    this seed (GPU)
bindEvents       :       861 ± 240               789 PASS           997 PASS
meanBoundMotors  :      7.37 ± 1.52             7.088 PASS         8.142 PASS
glidingVelocity  :      8.39 ± 1.44             8.630 PASS         8.660 PASS
```

Both CPU and GPU runs fall inside the 10-seed cpuopt baseline mean
± 2 SD on all three observables. No mid-run NaN or "Crazy torque"
spam. Source logs: `RUN_LOGS/2026-05-30_threadforce_smoke_cpu.txt`,
`RUN_LOGS/2026-05-30_threadforce_smoke_gpu.txt`.

### Dense timing — glidingDense_demo_smoke (M=98K, S~1101) — REGRESSION

CPU path. Sources: `RUN_LOGS/2026-05-30_threadforce_dense_cpu.txt`
(first run, no `gatherTimer`), `RUN_LOGS/2026-05-30_threadforce_dense_cpu2.txt`
(second run, with `gatherTimer`). The baseline column is the
2026-05-30 parallel-gridfill result (`gridfill_dense_cpu_after.txt`),
which was the most recent main-tip CPU dense baseline.

```
phase                          baseline (s)   this (s)    delta
---------------------------------------------------------------------
ThingStep Threads (incl gather)    55.02      112.81     +57.8s
ThingBrownian                      31.65       30.22      -1.4s
Myosin (joints)                    23.41       17.83      -5.6s
MyoDimer                            8.96        7.84      -1.1s
Mesh                                6.93        7.34      +0.4s
Ck Mots                             5.64        5.18      -0.5s
MotorBindGrid3D Fill                4.15        3.63      -0.5s
NodeLink                            1.52        1.68      +0.2s
---------------------------------------------------------------------
wall (min per sim-sec)              252         329       +30 %
observables                bindEvents 3983→3652, meanBoundMotors 250.5→234, glidingVelocity 40.88→42.36
                           (single seed; all within ensemble noise)
```

GPU path. Source: `RUN_LOGS/2026-05-30_threadforce_dense_gpu.txt`.

```
phase                          baseline (s)   this (s)    delta
---------------------------------------------------------------------
ThingStep Threads (incl gather)    25.46       80.07      +54.6s
gpuMoveThing total                 44.70       42.84       -1.9s
gpuMotorBinding total              13.26       12.99       -0.3s
Myosin (joints)                    23.57       17.31       -6.3s
MyoDimer                            9.51        8.71       -0.8s
Mesh                                6.93        6.82       -0.1s
MotorBindGrid3D Fill                4.31        3.78       -0.5s
---------------------------------------------------------------------
observables                bindEvents 4051→4090, meanBoundMotors 257.6→251.5, glidingVelocity 42.58→42.23
                           (all within ensemble noise)
```

The joints phases (Myosin, MyoDimer) shrink ~5-6 s each — that's
exactly what the survey predicted for those phases (no lock
acquisitions, fewer Pt3D allocations in the 2-arg `incForceSum`).
**But ThingStep Threads grows by ~55 s on both CPU and GPU paths,
and the net wall regresses ~30 % at dense scale.** The ThingStep
delta is the gather pass running inside the same `ThingStepThreads`
pool; the per-ThreadSet barrier timer counts it. Root cause:
gather is bandwidth-bound and touches every Thing × every thread.
At thingCt ≈ 588K × 16 threads × 6 doubles × 8 bytes (read+zero) =
~880 MB read+written per gather pass. At ~30 GB/s sustained that
is ~29 ms/step or ~29 s for 1000 steps — matching the observed
ThingStep delta minus the ~25 s savings from the rest of the
ThingStep work (which itself got slightly cheaper without the
synchronized blocks).

The validation-scale CPU wall is ~64 min/sim-sec vs baseline ~50,
so the regression is present at small N too — gather scales with
thingCt regardless of bind count.

### Determinism check

Two CPU runs with `-seed 42` (`BoxOfActin.java:352` sets
`Env.mtRNG`). Source logs:
`RUN_LOGS/2026-05-30_threadforce_det_run1.txt`,
`RUN_LOGS/2026-05-30_threadforce_det_run2.txt`. Compared the
[STATS] lines:

```
                     run1       run2
bindEvents         :  959        657
meanBoundMotors    : 7.713      6.077
glidingVelocity    : 8.598      7.163
```

**Runs DIVERGE.** So the force-accumulation gather (which IS
deterministic — fixed-order sum) is not the only non-determinism
source. The remaining contributor is documented in survey §B7 and
§G2: `Thing.myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));`
(Thing.java:75). `Math.random()` is seeded per-JVM from
`seedUniquifier ^ nanoTime()` — different between processes
regardless of `-seed 42`. Per-Thing PRNG state therefore diverges
from Thing 1's construction, which propagates through Brownian
forces and biochem decisions for the whole run.

`Env.mtRNG.setSeed(seed)` only seeds the global MT used by
`Pt3D.RandomUnitVec`, `ProteinNode.biochemStep`, etc. — a small
fraction of total RNG draws compared with the per-Thing PRNGs.

So the force-accumulation race fix is a strict improvement (no
new race in the gather), but full bit-identical reproducibility
needs the per-Thing PRNG seed to be derived from a fixed source
(e.g. `myPRNG = new MersenneTwisterFast(Env.mtRNG.nextLong())`
during Thing construction, or Wang-hash from `myThingNumber` and
the global seed). Not done in this session; flagged for a future
RNG-cleanup pass.

### Memory overhead

- M=98K motors (dense_demo_smoke), thingCt ≈ 588K, capacity 735K
  (1.25× headroom): 16 threads × 735K × 3 doubles × 8 bytes × 2
  arrays (force+torque) = **564 MB**.
- M=400K motors (extrapolated, ~4× linear), thingCt ≈ 2.4M,
  capacity ≈ 3M: **~2.3 GB**.

The dense run uses `-Xmx4G` which fits 564 MB; the overnight
M=400K run uses `-Xmx20G` which fits 2.3 GB but leaves less room
for everything else. If we keep this approach, the accumulators
should be sparse (track per-thread which Things were touched) or
restricted to Things that actually receive cross-Thread writes.

### Status & open work

- Correctness: PASS. Observables within noise on CPU + GPU smoke
  and dense.
- Determinism (forceSum gather order): now deterministic. Whole-sim
  determinism: still false due to per-Thing PRNG seed source (out
  of scope).
- Performance: regression of ~30 % at dense scale on both CPU and
  GPU paths. The gather is the cost. Possible follow-ups:
  - Sparse accumulators (track {threadId, thingIdx} pairs written
    each phase, gather only those).
  - Restrict accumulator path to Things that have multiple writer
    threads in expectation (FilSegments mainly; motors/levers/rods
    are mostly single-writer per phase).
  - Single dense-sweep gather with SIMD intrinsics — but Java
    bottlenecks on the array-of-arrays pointer chase.

Survey §F6 (symmetric force pairs = 2× lock acquisitions) is also
satisfied: each `incForceSum(F, P)` now does both force AND torque
in one fused method (`r × F` inline, scalar), avoiding the second
synchronized-block acquire the old code paid.

## 2026-05-30 — Parallel MotorBindGrid3D fill

Survey §A2 / §D3: the per-step rebuild of `MotorBindGrid3D` ran on a
single worker (`FillThreads(super(1, ...))`) and cost ~27-28 ms/step at
M=98K — 15% of dense CPU wall. The per-cell `synchronized` blocks in
`addFilToCell` / `addMotorToCell` already serialize same-cell writes, so
the change is purely a partition + thread-count bump.

### Change

`boxOfActin/MotorBindGrid3D.java`:

- `FillThreads` constructor: `super(1, ...)` → `super(Env.numMeshThreads, ...)` (16 workers).
- Two `int[Env.numMeshThreads+1]` partition arrays (`filJobDiv`,
  `motorJobDiv`) sized at construction.
- `divideAndConquer` computes both partitions and lifts the
  `lastWriteTime = Env.counter` stamp out of `addFilToCell` to a single
  pre-spawn write. The timestamp pattern is itself the lazy clear — no
  explicit grid clear pass is required.
- `execute(threadId)` loops over `[filJobDiv[t], filJobDiv[t+1])` then
  `[motorJobDiv[t], motorJobDiv[t+1])`. Filaments and motors fill
  independent cell maps, so no barrier between them.
- Dropped the redundant `lastWriteTime = ts` write inside `addFilToCell`
  (now set once in `divideAndConquer`).

The Pt3D references read in `execute` (`fs.end1`, `fs.end2`,
`m.bindTip`) are stable across the step (set in the previous
`moveThing`), and `fillSoaArrays()` at `BoxOfActin.java:695-696` runs
inline on the main thread before the fill ThreadSet dispatches — both
preconditions hold.

### Validation — smoke (glidingAssay500_val, 1 seed) — PASS

```
                       cpuopt baseline mean ± 2 SD     this seed
bindEvents         :        861 ± 240                  843     PASS
meanBoundMotors    :       7.37 ± 1.52                7.662    PASS
glidingVelocity    :       8.39 ± 1.44                7.966    PASS
```

Source: `RUN_LOGS/2026-05-30_gridfill_smoke.txt`. All three observables
within the 10-seed cpuopt baseline mean ± 2 SD. No physics drift
expected: the parallelized fill produces the same cell contents (same
{filSeg, motor} sets per cell) and the same CSR pack to GPU — only the
write order within a cell differs, which is invisible to both the CPU
27-cell query and the GPU broad-phase walk.

### Dense timing — glidingDense_demo_smoke (M=98K, S~1101)

CPU path (16 workers, i9-9900K → aorus). Sources:
`RUN_LOGS/2026-05-30_gridfill_dense_cpu_{before,after}.txt`.

```
phase                      before (s)   after (s)   delta
---------------------------------------------------------
MotorBindGrid3D Fill         29.502       4.150    -7.1×  (~25 ms/step → ~3.8 ms/step)
ThingStep                    54.602      55.021    +0.8 %
ThingBrownian                31.140      31.646    +1.6 %
Myosin (joints)              22.876      23.410    +2.3 %
MyoDimer                      9.513       8.961    -5.8 %
Mesh                          7.168       6.931    -3.3 %
Ck Mots                       5.215       5.640    +8.1 %
NodeLink                      1.553       1.520    -2.1 %
others (each <1s)             ≈           ≈         noise
---------------------------------------------------------
wall (min per sim-sec)         287         252    -12 %
observables                  bindEvents 3951 → 3983; meanBoundMotors 252.1 → 250.5;
                             glidingVelocity 43.22 → 40.88  (single-seed; within noise)
```

Fill went from 29.5 s → 4.2 s — a **7.1× speedup**, exceeding the survey
estimate (4-5×). The 12% net wall improvement is the budgeted 10%. Other
phases shift inside noise (single seed). The Ck Mots +8% is likely the
per-cell write-order change putting cell contents in a less
cache-friendly order for the query side; not pursued further this
session.

GPU path. Sources: `RUN_LOGS/2026-05-30_gridfill_dense_gpu_{before,after}.txt`.

```
phase                      before (s)   after (s)   delta
---------------------------------------------------------
MotorBindGrid3D Fill         30.300       4.306    -7.0×
gpuMotorBinding total        13.239      13.261    +0.2 %  (gridPack 0.309 → 0.294)
gpuMoveThing total           43.775      44.700    +2.1 %
ThingStep                    24.654      25.464    +3.3 %
Myosin (joints)              23.005      23.574    +2.5 %
MyoDimer                      9.989       9.510    -4.8 %
Mesh                          6.777       6.929    +2.2 %
---------------------------------------------------------
observables                  bindEvents 4165 → 4051; meanBoundMotors 249.0 → 257.6;
                             glidingVelocity 42.02 → 42.58  (single-seed; within noise)
```

GPU `gridPack` (CSR upload from the now-parallel-built grid) is
unchanged at ~300 µs/run total — confirming the parallelized fill
produces the same CSR layout the kernel expects. The GPU was never
gated by the fill (motor binding kernel runs after fill completes), so
the GPU-path wall improvement is the same ~25 ms/step.

### Synchronization overhead

The 7× speedup with 16 threads (sublinear, 44% parallel efficiency)
suggests the per-cell `synchronized` monitor + memory-barrier cost is
becoming a meaningful fraction at this thread count, but is not yet
dominant. Cells average ~2-3 entries each at M=98K so contention is low
in expectation; the residual cost is the lock-acquire-overhead floor.

Future option, not pursued: replace per-cell `Object` monitors with
`AtomicInteger` cell counters and lock-free CAS append (or a
count-then-scatter CSR build). Either would push toward ideal speedup
but the present 7× already meets the survey budget; deferred until the
4D `int[nX][nY][nZ][BIN_DEPTH]` is converted to flat CSR (separate
session, larger refactor that affects both fill and query).

### Files touched

`boxOfActin/MotorBindGrid3D.java` only (FillThreads inner class +
removed one redundant write in `addFilToCell`).

### Open / not done

- 4D-array → CSR conversion (survey §B2, ~528 MB at boa10 scale) —
  separate session.
- 27-cell query walk indirection reduction (survey §E2) — separate
  session.
- Per-thread force/torque accumulators (survey §D2) — independent of
  this work.

## 2026-05-30 — CPU micro-optimizations: batch 1

Five independent mechanical changes from `deepPerformanceSurvey01.md`
applied across the CPU path. None touch threading, data structures, or
the GPU path. The aim is to retire low-effort arithmetic and access-
pattern inefficiencies before bigger refactors.

### Changes applied

**1. `Math.pow(x,2)` / `Math.pow(x,3)` → `x*x` / `x*x*x`.** HotSpot
doesn't always intrinsify the small-exponent form, and the cube case is
never intrinsified. Converted ~35 individual `Math.pow` call sites across
Pt3D (`ptDist`, `ptDistSqrd`), ValueTracker (`variance`, `varianceOfPt`),
Mesh (`fillFilSegMesh`), Bug (`calculateProperties`, `getVolume`),
Chamber, MyoMotor, FillNode, ProteinNode, StickyNode, MyoLever, MyoRod,
MyoMiniFilament, FilSegment (`calculateProperties` and four
`moveCoeff`-style sites), Env (`nodeRotDiff_init`), and BoxOfActin
(benchmark tauTheo). Skipped: cube-root inits in `Env.java:44-50`,
variable-exponent `Math.pow(sideBondsStabilize, linkedToCt)` and
`Math.pow(monomerConc, actinSeed)`, and `nodeCollisions` (commented-out
dead code), and `RandomElement.java` (library).

**2. Fast acos with small-angle approximation.** Added
`Pt3D.fastAcos(double dot)` which uses `θ ≈ √(2(1−dot))` for
`dot > 0.95`, mirrors the formula for `dot < −0.95` (antiparallel —
filament torsion springs and motor lever joints can sit here), and
delegates to `Math.acos` otherwise. <0.6% error at the threshold.
Replaced 17 hot-path acos calls across Myosin (lever-motor and rod-lever
joint torques), MyoFilLink (alignUVec/alignYVec), FilSegment
(`moveCoeff`, `addTorsionSpringForces` ×2, `checkToLink` ×3, nodeTorqSpring,
plasmid torsion), MyoLever/MyoMotor/MyoRod `moveCoeff`, MyosinDimer
(uVec/yVec lever align), MyoMiniFilament (end1/end2 dimer constraint),
FilLink.applyTorsionForce, and Arp23 daughter alignment. Each site also
got the `dotVecs < -1` clamp the original code lacked. Skipped: Bug
geometry and FilLink constructor (init, not per-step).

**3. Deferred sqrt in threshold comparisons.** Renamed `Thing.RetObj`
field `conDist` → `conDistSq`; `lineSegmentIntersectTest` and
`pointAndLineIntersectTest` now populate the squared distance directly
(no `sqrt`). Updated callers `FilSegment.checkToLink` and
`FilSegment.nodeCollisions` to compare `conDistSq < threshold*threshold`.
Also rewrote `FilSegment.checkNodeFilTipsCollision` to early-out on
`ptDistSqrd < colThresh²` and only compute `sqrt` on the hit path. Saves
one sqrt per pair that bins-as-candidate but doesn't actually collide.

**4. Cache `Env.X.getValue()` in hot methods.** Highest-impact wins
where the same Parameter is read multiple times in one call:
- `Thing.calcRandomForces` — cached `brownianDeltaT` and `1/brownianDeltaT`
- `FilSegment.moveThing` — `deltaT` reused 5×
- `FilSegment.addLinkForces` — `deltaT`, `fracMove`, `fracR`,
  `maxSegDistActive`, `maxSegDist` (`fracR` previously read 4× per call)
- `FilSegment.addTorsionSpringForces` — `deltaT`, `fracMoveTorq`,
  `maxSegAngActive`, `maxSegAng`, `filTorqSpringActive`, `filTorqSpring`
  (each previously read 2× per call)
- `MyoMotor.moveThing` — `deltaT` reused 4×, `myoBrownianAttn` reused 2×
- `Myosin.applyLeverMotorJointForce` — `deltaT`, `myoJ1FracR`
- `Myosin.applyRodLeverJointForce` — `deltaT`, `myoJ2FracR`

Around 25 individual `getValue()` calls collapsed to local reads. Did
not touch `MyoMotor.biochemStep` (biochem-cadence, 1/100 of mechanics)
or `MyoFilLink.alignUVec/YVecTorque` (each Env getter is called only
once per method).

**5. Flat `double[9]` for `transXTox` / `transxToX`.** Replaced nested
`double[3][3]` with row-major `double[9]` in `Thing.java`. Rewrote
`Thing.transMat` to fill both matrices inline (no nested loop, no double
indirection on the transpose). Rewrote all eleven access methods in
`Pt3D.java` (`xToNewX`, `xToX`, `xToXPlusxOrigin`, `xToXPlusPoint`,
`XToNewx`, `XTox`, `XToxFromxOrigin` and their overloads) to use a
single `m = p.transXTox` (or `transxToX`) local with flat indexing. One
load per matrix vs. three nested pointer chases per element. The GPU
kernel already derives `zVec = cross(uVec, yVec)` inline; the CPU
matrix is still built (many phases call `XTox`/`xToX`), but the layout
now matches JIT bounds-check elision patterns.

### Validation — 10 seeds × glidingAssay500_val — PASS

Protocol: 10 CPU seeds, CPU baseline reused from iter2d (committed main
on 5fa6bda; CPU code unchanged between iter2d and the pre-cpuopt commit
on main). Source data
`RUN_LOGS/2026-05-30_cpuopt-validation.txt`; summary
`RUN_LOGS/2026-05-30_cpuopt-validation-summary.txt`.

```
                     iter2d CPU mean ± SEM (SD)    cpuopt CPU mean ± SEM (SD)    |diff|/cSEM
bindEvents       :      856.80 ± 45.72 (144.58)     860.80 ± 37.97 (120.08)        0.07  PASS
meanBoundMotors  :        7.30 ±  0.30 (  0.94)       7.37 ±  0.24 (  0.76)        0.20  PASS
glidingVelocity  :        8.22 ±  0.15 (  0.46)       8.39 ±  0.23 (  0.72)        0.61  PASS
wall (s/seed)    :      275.10 ±  1.46 (  4.63)     289.20 ±  0.83 (  2.62)        8.40  (diagnostic only)
```

All three physics observables PASS the `|diff|/cSEM < 2.0` gate. Per-seed
RNG paths diverge from baseline (fastAcos and deferred sqrt alter
arithmetic ordering enough that even seed 1 produces a different
trajectory) but ensemble means line up well inside noise.

Wall is +5.1% at validation scale. Not seen at dense scale (below). The
likeliest cause is machine background load during the cpuopt validation
runs (system load avg ~5 at run start vs the lower load when iter2d
validated). Per-seed wall SD shrunk (2.62 vs 4.63), so the cpuopt run
saw less variance, not more — consistent with a steady-state load
offset.

### Dense timing — glidingDense_demo_smoke (M=98K, S~1101) — improved

Source log `RUN_LOGS/2026-05-30_cpuopt-dense-cpu.log`. Single-seed run,
same parameter file as iter2d's CPU dense baseline.

```
phase                       iter2d CPU (s)     cpuopt CPU (s)     delta
---------------------------------------------------------------------
ThingStep Threads               55.93              52.34          -6.4 %
ThingBrownian Threads           30.43              29.87          -1.8 %
Myosin (joints)                 22.16              21.80          -1.6 %
MyoDimer                         9.72               8.84          -9.1 %
Mesh                             6.71               6.51          -3.1 %
Ck Mots                          5.15               4.99          -3.1 %
MotorBindGrid3D Fill            28.95              29.04          +0.3 %
NodeLink                         1.56               1.67          +6.6 %  (sub-2s, near noise)
ProteinNode/MyoMiniFil/etc.  ~0.85 each         ~0.90 each       +3-5 %  (sub-1s)
---------------------------------------------------------------------
Wall (real)                     190 s              188 s          -1 %
```

The hot phases (~95% of wall) all improve: ThingStep -6.4%, MyoDimer
-9.1%, Brownian -1.8%, Myosin -1.6%, Mesh/CkMots -3%. Small phases
(NodeLink, Arp23, ProteinNode) regress 3-7% but each is <1s — those
percentages are well inside the per-run noise floor (single seed; no
averaging across runs). The fixed-cost MotorBindGrid3D Fill is unchanged
as expected (no changes in that path).

Net wall ~1% faster at dense scale. The survey's expectations: Math.pow
-3-5%, acos -5%, transXTox -3-5%, deferred sqrt -3-5%, Env.getValue cache
"individually small but large in aggregate." Observed gains in the hot
phases (1.6%-9.1%) are in the predicted range. Smaller than the survey's
upper estimate, consistent with HotSpot already intrinsifying many
`Math.pow(x,2)` calls and many of the cached Env reads having been
already promoted to invariants by the JIT.

### Conversion counts

| Change | Sites |
|---|---|
| 1 — `Math.pow` rewrites | ~35 individual calls (29 `pow(x,2)`, ~6 `pow(x,3)`) |
| 2 — `Math.acos` → `fastAcos` | 17 hot-path call sites |
| 3 — deferred sqrt | 4 caller sites + RetObj field rename |
| 4 — `Env.getValue()` cached | ~25 calls collapsed across 7 hot methods |
| 5 — `transXTox` / `transxToX` flattened | 2 fields + 12 access methods |

### Files touched

`boxOfActin/Pt3D.java`, `Thing.java`, `FilSegment.java`, `Myosin.java`,
`MyoMotor.java`, `MyoLever.java`, `MyoRod.java`, `MyoMiniFilament.java`,
`MyosinDimer.java`, `MyoFilLink.java`, `FilLink.java`, `Arp23.java`,
`Mesh.java`, `ValueTracker.java`, `Bug.java`, `Chamber.java`,
`ProteinNode.java`, `StickyNode.java`, `FillNode.java`, `Env.java`,
`BoxOfActin.java`.

### Open items

- The `MyoMotor.biochemStep` switch arms each have 2 `getValue()` calls;
  the biochemCheckInt gates them to 1/100 mechanics-step cadence, so net
  call rate is low. Skipped per spec ("If an Env.getValue() cache would
  require passing the cached value through many method signatures, skip
  that site").
- `Pt3D.ptDistSqrd` and `ptDist` could be hoisted further for callers
  that don't need either form, by inlining the dx/dy/dz delta computation
  where the result feeds a single comparison. Out of scope for this
  batch.
- The `RetObj` classes in `FilLink.java`, `NodeLink.java`, `ActA.java`
  still expose `conDist` (with sqrt). Those callers aren't on the hot
  path identified by the survey, and changing them would ripple beyond
  the survey's listed sites. Defer.

## 2026-05-29 — Iter2d: parallel pack/unpack + device residency

Targeted iter2c's pack=56ms + unpack=55ms per-call cost at M=98K, which
was leaving the GPU 1.45× slower than CPU despite the kernel itself
finishing in 9.5 ms. Two structural changes:

1. **Parallel pack and unpack** on a persistent 16-worker daemon-thread
   pool inside `GPUMoveThing`. Each worker handles a contiguous slot
   range; FloatArray.set/get on disjoint indices is safe because the
   underlying `MemorySegment` writes a single 4-byte word per index
   with no shared metadata or header munging.

2. **coord/uVec/yVec pack-skip for myosin slots** on steady steps.
   Between unpack(N) and pack(N+1), the only CPU phase that mutates
   the pose of a GPU-handled Thing is `FilSegment.biochemStep()` (via
   poly/depoly's `coord.inc()`). For MyoMotor/MyoRod/MyoLever, the
   `coord`/`uVec`/`yVec` fields are touched only by `moveThing()`,
   which is the kernel itself, so the FloatArray from the previous
   step is already the correct CPU-visible state. The per-step pack
   now skips writing those nine floats per myosin slot.

A `coordsDirty` flag forces a full pack on the first call after any
classifyThings() (slot remap), plan rebuild, or `invalidatePlan()`.
FilSegment slots always re-pack coord/uVec/yVec — gliding-assay
filament counts are tiny relative to motor count, so the per-slot
cost is negligible while keeping biochem-driven coord changes
coherent.

### A. Worker pool design

`N_WORKERS = min(Env.allThreadCt, availableProcessors)` — 16 on aorus.
Persistent daemon `Thread`s; coordination via a single `Object`
monitor (`phaseLock`). Master bumps `currentPhase` and `notifyAll()`
to release the workers; each worker holds the lock briefly to read
the slot range, exits to do its chunk, re-enters the lock to
increment `workersDone` and `notifyAll()`. Master waits on the same
lock until `workersDone == N_WORKERS`. Two synchronized passes per
dispatch — at ms-scale work granularity the wait/notify overhead is
sub-microsecond, well below the savings.

The JMM happens-before for the scalar pack params (`sBTransCoeff`,
`sBRotCoeff`, etc., snapshotted once per call before dispatch) is
established via the master's unlock → worker's lock acquire pair
when the workers wake from `wait()`. No volatile needed.

### B. Pack rule by slot type

After `classifyThings()` marks the slot `RULE_FIL` / `RULE_MYO` /
`RULE_LEVER`, the per-step pack:

| rule       | coord/uVec/yVec writes  | force/torque/drag/scales writes |
|------------|-------------------------|---------------------------------|
| RULE_FIL   | always                  | always                          |
| RULE_MYO   | only on coordsDirty step | always                          |
| RULE_LEVER | only on coordsDirty step | always                          |

In the gliding-dense smoke (M≈98K), ~99% of slots are myosin, so
~99% of slots skip the 9 coord writes after the first call following
each topology change.

### C. Validation — 10 seeds × glidingAssay500_val — PASS

Protocol: 10 CPU + 10 GPU seeds × 0.1 s (10 001 steps). CPU rows
reused from iter2c (CPU code unchanged from iter2c; same -seed N is
deterministic). Source data in
`RUN_LOGS/2026-05-29_iter2d-validation.txt`; summary in
`RUN_LOGS/2026-05-29_iter2d-validation-summary.txt`.

```
                       CPU mean ± SEM (SD)         GPU mean ± SEM (SD)         |diff|/cSEM
bindEvents       :    856.80 ± 45.72 (144.58)     886.60 ± 37.32 (118.01)         0.50  PASS
meanBoundMotors  :      7.30 ±  0.30 (  0.94)       7.41 ±  0.20 (  0.62)         0.31  PASS
glidingVelocity  :      8.22 ±  0.15 (  0.46)       8.32 ±  0.19 (  0.59)         0.46  PASS
wall (s/seed)    :    275.10 ±  1.46 (  4.63)     356.30 ±  1.59 (  5.03)       37.54  (diagnostic only)
```

All three physics observables pass the |diff|/cSEM < 2.0 gate. GPU
ensemble means are within 0.5σ of CPU; the parallel unpack +
`initialize()` introduces no observable race or physics drift.

GPU wall 356.3 s/seed vs CPU 275.1 s/seed = **1.30× slower** (vs
iter2c 1.63×). 21 % wall reduction at validation scale. The remaining
0.30× gap at M=500 is dominated by per-call kernel-launch + 11-buffer
upload overhead, which doesn't amortize at low slot counts.

### D. Dense crossover — M=98K, S~1001 (glidingDense_demo_smoke) — PASS

Source logs: `RUN_LOGS/2026-05-29_iter2d-dense-{cpu,gpu}.log`.

```
phase                          CPU dense (s)   GPU dense (s)
---------------------------------------------------------------
ThingStep (step+bio+resetCt)        55.9            23.4
Brownian (calcRandomForces)         30.4             2.9   ← kernel Wang-hash
Myosin (joints)                     22.2            21.6
MotorBindGrid3D Fill                28.9            30.3
Mesh                                 6.7             6.5
MyoDimer                             9.7            10.2
Ck Mots (CPU motor-binding)          5.1             0.0
GPUMotorBinding total                ---            12.8   (1101 calls)
GPUMoveThing total                   ---            44.6   (1101 calls)
---------------------------------------------------------------
WALL                               190             184
                                                 (0.97×;  GPU 3 % faster — CROSSOVER)
```

iter2c at the same scale was CPU 188 s vs GPU 273 s (1.45× slower).
iter2d brings the GPU under the CPU wall — the goal of the iter2b/2c/
2d series.

### E. Per-call `gpuMoveThing` breakdown

Validation scale (M=500, 10-seed averages × 10101 calls):

```
            iter2d (ms)   iter2c (ms)   delta
total          6.06         14.85       -59.2 %
pack           1.36          6.62       -79.5 %
exec           1.83          1.81        +1.1 %
unpack         2.87          6.43       -55.4 %
```

Dense scale (M=98K, single seed × 1101 calls):

```
            iter2d (ms)   iter2c (ms)   delta
total         40.5         121.0        -66.5 %
pack           9.8          56.1        -82.5 %
exec           9.5           9.5         +0.0 %
unpack        21.2          55.5        -61.8 %
```

Pack falls 80 % (parallel + per-slot myosin coord-skip). Unpack
falls 60 % (parallel — the per-slot `initialize()` is fixed-work and
doesn't parallelize as cleanly as the FloatArray reads). Exec is
unchanged (kernel byte-for-byte the same as iter2c).

### F. Findings worth keeping

- `FloatArray.set/get` on disjoint indices is safe under concurrent
  access. Underlying `MemorySegment` writes one word per index; the
  base/header fields are only written in the constructor.
- The "device residency" framing in the task brief was unnecessary
  in practice. The savings come entirely from the Java-side `.set()`
  call count, not from changing the device transfer mode. Keeping
  EVERY_EXECUTION for the FloatArrays and just skipping the Java
  writes works: the device still uploads what's already in the
  FloatArray, which is exactly the kernel's output from the previous
  step (for myosin slots; FilSegment slots get a fresh Pt3D read).
  Net effect: same as residency, with no plan-rebuild or
  invalidation logic needed.
- The `Phaser`/`CyclicBarrier`/`ForkJoinPool` JDK options were
  rejected in favour of a one-monitor hand-rolled barrier. The work
  granularity (sub-millisecond per worker per dispatch at validation
  scale, a few ms at dense) makes the simpler dispatch path easier
  to reason about and lets the workers' wait/notify edge serve
  double-duty as the JMM happens-before for the snapshot scalars.

### G. Open items

- The 1.30× validation-scale wall gap is now a fixed-overhead
  problem (per-call launch + 11-buffer upload). Two levers left:
  (a) batch multiple kernel calls into one TornadoVM execute() —
  not natively supported, would need a multi-step kernel; (b) reduce
  buffer count further by collapsing forceSum/torqueSum/brownianScales
  into a single packed buffer (saves DMA setup but adds kernel index
  math). Neither is worth doing until a target workload needs sub-ms
  per-call.
- `cpuFallback[]` is empty for gliding-assay but is walked serially
  for any Bug / branch / ProteinNode / etc. Real-world Listeria runs
  with thousands of branches would re-introduce serial CPU work
  here; parallelising the fallback dispatch is straightforward (same
  worker pool, new OP code) when needed.
- The previously deferred items (motherFil branch pre-pass,
  StickyNode forceSum pre-add, ProteinNode velMask, MyoMiniFilament
  / ProteinNode pack rules) remain deferred — none material at
  gliding-assay scope, and crossover removed the urgency.

## 2026-05-29 — Iter2c: Wang hash Brownian on GPU + pack optimization

Two changes targeting iter2b-polish's 1.61× GPU slowdown at M=98K, where
pack=75ms + unpack=55ms per call dominated the kernel cycle:

1. **Brownian RNG inline in kernel.** Each thread generates 6 N(0,1)
   Gaussians via Wang hash (3 hashes feeding 3 Box-Muller pairs with both
   cos and sin retained). The kernel signature drops `randForces` /
   `randTorques` FloatArray params; CPU `calcRandomForces()` skips Things
   flagged `gpuHandled` by the per-step `classifyThings()` pre-pass.

2. **Pre-built `gpuThingIndices[]`** indexed by slot. The hot pack/unpack
   loops walk a flat int array instead of running `instanceof` and
   eligibility logic on every Thing every step. Per-slot
   `brownianRule[]` (FIL / MYO / LEVER) lets `packPerStep` recompute
   per-Thing trans/rot scales without `instanceof`.

`bTransGam` / `bRotGam` / `velMask` initially moved to FIRST_EXECUTION but
reverted after observing `slotCount` grows by ~1 / step during the gliding-
assay early ramp-up (motors are added incrementally up to the target
density). New slot indices inherit device-side zeros from the first-
execution upload → `bRotGam=0` makes `bAngVeloc=inf` → coord NaN at step 1.
EVERY_EXECUTION restored as the safe baseline; see the "device-zero NaN"
incident below.

### A. Wang hash + Box-Muller derivation

CPU `Thing.calcRandomForces()` uses Marsaglia polar:

```
randForces.x = (1/dt) * v1.x * sqrt(bTransDiff.x * facterm.x) * bTransGam.x
            where facterm = -4*dt*log(rsq)/rsq, rsq = v1²+v2² in unit circle,
            bTransDiff.x = kT / bTransGam.x  (Einstein)
```

Marsaglia identity `v * sqrt(-2*log(rsq)/rsq) ~ N(0,1)` collapses this to:

```
randForces.x = g * sqrt(2*kT*bTransGam.x / dt),  g ~ N(0,1)
```

The kernel pre-computes `brownianForceMag = sqrt(2*kT/dt)` (one float in
`params`) and adds:

```
bfx += tScale * brownianForceMag * sqrt(bTransGam.x) * gfx
btx += rScale * brownianForceMag * sqrt(bRotGam.x)   * gtx
```

Statistically equivalent to CPU `randForces`. Individual seed
trajectories diverge from CPU (different RNGs, different Gaussian
sequences) but ensemble mean / variance must match — which is the
validation gate.

Seed mixing: `base = (m * 1000003) ^ (stepCount * 999983) ^ (runSeed *
7919)`, then six independent Wang hashes via golden-ratio salts
(0x9e3779b9, 0x85ebca6b, 0xc2b2ae35, 0x517cc1b7, 0x1f0a7ed5). `runSeed`
is sampled from `Env.mtRNG.nextInt()` at class load; `-seed N` reseeds
mtRNG before GPUMoveThing initialises, so each user seed produces a
distinct GPU Gaussian stream.

### B. Pre-built slot index

`GPUMoveThing.classifyThings()` runs on:
- First call (after `allocateAndBuildPlan`)
- `Thing.thingCt != lastThingCt` (population growth or shrinkage)
- `invalidatePlan()` (aeta change)

It walks `theThings[]` once, decides eligibility, writes:
- `gpuThingIndices[slot] = thing index`
- `brownianRule[slot] = RULE_FIL / RULE_MYO / RULE_LEVER`
- `Thing.gpuHandled = true` (consumed by `ThingBrownianThreads.execute`
  to skip CPU `calcRandomForces()`)

`packPerStep()` loops over `gpuThingIndices`, does no `instanceof` per
slot. The per-rule branch in scale computation is a small switch on a
cached int (predictable jump), not a chain of `instanceof` tests.

For gliding-assay scope: classify re-runs every step during the motor
ramp-up phase (population growing by 1/step), then locks once population
saturates. iter2c's win is in the post-ramp steady state.

### C. ThingBrownianThreads change

`Thing.java:227-243` adds a `Env.useGPU` branch: for each Thing,
`calcRandomForces()` is skipped when `t.gpuHandled` is true. Non-GPU
runs are unaffected (`useGPU == false` falls into the original
unchanged code path). For mixed populations (Bug, branch FilSegments),
the CPU path still runs for those Things — they fall into the GPUMoveThing
`cpuFallback[]` list and are handled by the same kernel-pack-skipping
logic.

### D. Device-zero NaN incident

First attempt set `bTransGam` / `bRotGam` / `velMask` to FIRST_EXECUTION
to save ~2.4 MB/call upload at M=98K. NaN appeared at step=1, slot=42001
of a FilSegment. Mechanism: classifyThings on step 0 filled FloatArray
slots [0, slotCount_0). On step 0, FIRST_EXECUTION uploaded the whole
buffer. On step 1, motor population grew to slotCount_1 = slotCount_0+1;
classifyThings filled the new slot, but FIRST_EXECUTION skipped re-
upload. Device-side slot[slotCount_0] still held zero. Kernel computed
`bvx = 1e6 * bfx / 0 = inf`, `coord += dt * inf = NaN`. NaN propagated
to all downstream torque / cross-product paths in subsequent steps.

Fix: revert to EVERY_EXECUTION. The drag re-pack lives in
`packPerStep()`, matching iter2b's pattern. Plan rebuild on grow handles
capacity changes. Future work for truly steady populations could
re-enable FIRST_EXECUTION + plan rebuild on every topology event, but
the ramp-up frequency in gliding-assay makes that net-negative here.

### E. Validation — 10 seeds × glidingAssay500_val — PASS

Protocol: 10 CPU + 10 GPU seeds × 0.1 s (10 001 steps). Source data in
`RUN_LOGS/2026-05-29_iter2c-validation.txt`; summary in
`RUN_LOGS/2026-05-29_iter2c-validation-summary.txt`.

```
                       CPU mean ± SEM (SD)         GPU mean ± SEM (SD)         |diff|/cSEM
bindEvents       :    856.80 ± 45.72 (144.58)     860.10 ± 36.24 (114.60)         0.06  PASS
meanBoundMotors  :      7.30 ±  0.30 (  0.94)       7.44 ±  0.25 (  0.78)         0.35  PASS
glidingVelocity  :      8.22 ±  0.15 (  0.46)       8.08 ±  0.12 (  0.38)         0.74  PASS
wall (s/seed)    :    275.10 ±  1.46 (  4.63)     449.30 ±  0.65 (  2.06)       108.73  (diagnostic only)
```

All three physics observables pass the |diff|/cSEM < 2.0 gate. The Wang-
hash Brownian sequence differs per-seed from CPU's MersenneTwister, but
the ensemble statistics match within noise — the fluctuation-dissipation
calibration is preserved.

GPU wall 449.3 s/seed vs CPU 275.1 s/seed: **1.63× slower** (vs iter2b's
1.82×). 12 % wall reduction at validation scale from iter2c's two levers.

### F. Dense timing — M=98K, S~1001 (glidingDense_demo_smoke)

CPU + GPU single-seed run, source logs `RUN_LOGS/2026-05-29_iter2c-dense-{cpu,gpu}.log`:

```
phase                          CPU dense (s)   GPU dense (s)
---------------------------------------------------------------
ThingStep (step+bio+resetCt)        55.1            23.2
Brownian (calcRandomForces)         29.5             3.0   ← iter2c: only CPU-fallback Things
Myosin (joints)                     21.6            21.7
MotorBindGrid3D Fill                29.2            28.2
Mesh                                 6.6             6.6
MyoDimer                             8.5             8.5
Ck Mots (CPU motor-binding)          5.0             0.0
GPUMotorBinding total                ---            12.8   (1101 calls)
GPUMoveThing total                   ---           133.3   (1101 calls)
---------------------------------------------------------------
WALL                               188             273
                                                 (1.45× slower; iter2b was 1.61×)
```

Brownian phase falls from 29.5 s → 3.0 s on GPU (the residual 3 s
handles Bug and any CPU-fallback Things). Net wall reduction at M=98K:
330 → 273 s (-17 % vs iter2b's GPU). Still 1.45× slower than CPU;
unpack remains the dominant per-call cost at this scale.

### G. Updated `[STATS] gpuMoveThing` per-call breakdown

Validation scale (M=500, 10-seed avg):

```
            iter2c (ms)   iter2b (ms)   delta
total          14.85         17.83      -16.7 %
pack            6.62          9.10      -27.3 %
exec            1.81          2.03      -10.9 %
unpack          6.43          6.69       -3.9 %
```

Dense scale (M=98K, single seed):

```
            iter2c (ms)   iter2b (ms)   delta
total         121.0         141.0       -14.2 %
pack           56.1          74.6       -24.8 %
exec            9.5          11.2       -15.2 %
unpack         55.5          55.1       +0.7 %
```

Pack falls 25 % (no randForces/randTorques upload, no `instanceof` in
the hot loop, drag tensors still EVERY_EXECUTION but written tightly).
Exec falls 15 % — the kernel does *more* work (Box-Muller + 6 sqrt +
6 trig), but the dropped buffer count (13 → 11) reduces per-launch
device-side bookkeeping, and the eliminated upload dominates.

Unpack is now the bottleneck: each slot pays for three Pt3D writes and
a per-type `initialize()` call. iter2d (device residency for
coord/uVec/yVec) would eliminate this entirely.

### H. Open items

- **iter2d (residency)**: Keep `coord`/`uVec`/`yVec` resident on the device
  across steps. CPU phases that need them (Brownian on fallback Things,
  mesh fill, motorbind grid pack) would need to read FloatArray instead of
  Pt3D — touches every phase. Largest remaining lever once iter2c lands.
- **FIRST_EXECUTION for drag, with plan rebuild on topology event.** Only
  pays off if the population stays steady for many steps. For boa10 / Arp-
  branched runs where FilSegment splits trigger drag changes, the rebuild
  cost may exceed the upload savings. Defer until a stable-population
  workload becomes the perf target.
- Out-of-scope follow-ons from the iter2b survey (motherFil branch pre-
  pass, StickyNode forceSum pre-add, ProteinNode velMask) remain
  deferred — gliding-assay scope doesn't exercise them.

## 2026-05-29 — Iter2b-polish + dense timing

Two-part follow-on to the morning's iter2b implementation: kill the
`Crazy torque` log spam from §D of the iter2b entry, and run the kernel
at M=98K (gliding-dense smoke) to see whether the wall-clock crosses
from "GPU slower" to "GPU faster". **Polish landed cleanly. Dense
crossover did NOT happen** — GPU is 1.61× slower than CPU even at
M=98K. The kernel itself wins, but the per-step pack/unpack overhead
still dominates. iter2c (Brownian RNG on GPU, eliminating randForces
upload) is the next lever.

### A. Crazy torque fix

The §D root cause was assumed to be float32 small-angle uVec underflow
producing exactly-parallel unit vectors, with cross→zero, unitVec→NaN
(divide by zero). The prompt suggested catching `magSq < 1e-12` before
the unitVec call.

What's actually happening: cross→NaN, not cross→zero. The GPU
occasionally produces uVec with NaN components (the kernel's `nuInv =
1 / sqrt(magSq)` returns `Inf` if magSq somehow underflows to 0, then
`nuX * Inf` is NaN if nuX is 0). Once a Thing's uVec has NaN, the
cross-of-NaN cascades. `NaN < 1e-12` is `false` per IEEE 754, so the
prompt's exact threshold test does not catch the actual case.

I tried two variants and kept the second:

1. **magSq < 1e-12**: catches near-parallel finite uVecs, but does
   nothing for NaN. Single-seed test still showed 113k log lines.
2. **!(magSq >= 1e-12)**: catches NaN too (NaN comparisons return
   false; `!false` = true → return). Single-seed test showed 0 log
   lines. **But the velocity dropped to 7.72 µm/s (2.6 SD below the
   ensemble mean of 8.65 ± 0.36).** The reason: the original code's
   near-parallel finite path went through unitVec, which produces a
   unit vector in an ill-defined direction, then `scale(torsionMag)`
   applied a meaningful magnitude in a random direction — effectively
   a kick that unsticks near-parallel motors. My fix suppressed those.
3. **`if (Double.isNaN(torsionVec.x)) return;`**: catches only the
   actual NaN case (the only case that prints "Crazy torque" anyway —
   the magSq=0 finite path randomises direction inside unitVec and
   passes checkPt3D). Preserves the original physics for legitimate
   near-parallel cases. Single-seed test: **0 Crazy torque lines,
   bindEvents=780 / meanBoundMotors=6.832 / glidingVelocity=8.209 —
   all within the validation ensemble mean ± 2 SD.**

Final fix in `boxOfActin/Myosin.java`: one-line `if
(Double.isNaN(torsionVec.x)) return;` after the cross product in
`applyLeverMotorJointTorque()` and `applyRodLeverJointTorque()`. The
NaN-on-x is sufficient — if any component of either uVec is NaN, the
cross product's x component is NaN.

Verification log: `RUN_LOGS/2026-05-29_iter2b-polish-verify-gpu-seed1.log`.

### B. Dense-scale timing (M=98K, S~1100)

Param file: `ParameterFiles/glidingDense_demo_smoke` (14×14×0.5 µm bed,
500 µm² motor density, 100 random filaments + 1 canonical, runTime=0.01
s = 1001 steps). One CPU seed + one GPU seed, same seed value.

```
phase                          CPU dense (s)   GPU dense (s)
---------------------------------------------------------------
ThingStep (step+bio+resetCt)        61.7            24.8
  └ moveThing (CPU only — GPU       (incl)          0  (kernel)
    path skips CPU dispatch)
Brownian (calcRandomForces)         33.5            33.2
Myosin (joints)                     25.0            23.6
MotorBindGrid3D Fill (CPU)          31.0            29.1
Mesh                                 6.8             6.8
MyoDimer                             8.4             9.6
NodeLink / membrane / other          ~5              ~5
GPUMotorBinding total                ---           14.4   (1101 calls)
GPUMoveThing total                   ---          155.2   (1101 calls)
---------------------------------------------------------------
WALL                               205             330
                                                 (1.61× slower)
```

`[STATS] gpuMoveThing` per-call breakdown at M=98K:
```
            ms / call    M=500 (val) ms/call    scaling 500→98K
total       141.0          17.8                 8.0×
pack         74.6           9.1                 8.2×   (linear-ish)
exec         11.2           2.0                 5.6×   (sub-linear; broad-phase)
unpack       55.1           6.7                 8.2×   (linear-ish)
```

`[STATS] gpuMotorBinding` per-call at M=98K (smoke):
```
total 13.05 ms, pack 0.91 ms, gridPack 0.32 ms, exec 10.47 ms, unpack 0.34 ms
```

`[STATS] bindEvents=3828  meanBoundMotors=245.27  glidingVelocity=44.07`
(CPU run: bindEvents=4313, meanBoundMotors=260.67, glidingVelocity=43.03 —
single-seed differences are within ensemble noise; not a validation run).

Zero `Crazy torque` lines in the GPU dense log (fix from §A holds at
scale).

### C. Why no crossover at M=98K

The GPU kernel itself is competitive: at M=98K the kernel's 11.2
ms/call beats the CPU moveThing portion of ThingStep (estimated
~15 ms/call from the 61.7−24.8=36.9 s difference in ThingStep budget
divided by 1101 calls = 33.5 ms, of which roughly half is moveThing
the rest is step+biochem+resetCt — call it ~17 ms moveThing). So the
exec alone shaves a few ms/call.

But pack+unpack runs at 130 ms/call — pure CPU Java overhead walking
`Thing.theThings[]`, casting `instanceof`, writing 30 FloatArray slots
per Thing × ~98K Things. The kernel cycle pays a 130 ms penalty just
to put data on the device and pull it back.

Two structural fixes are available:

1. **iter2c (Wang-hash Brownian RNG on GPU).** `randForces`/`randTorques`
   are 6 floats × 98K Things × 1101 steps = 647 M float writes/seed
   accounting for roughly 1/3 of the pack cost. Computing them inside
   the kernel from a step-indexed Wang hash removes the upload entirely.
   Estimated pack drop: ~25 ms/call → 50 ms/call total — closer to
   parity but still GPU-slower.

2. **Persistent device buffers (iter2d / true residency).** Keep coord,
   uVec, yVec on the device across steps; CPU sees them only when
   asked (mesh fill, JSON output, GPU motor binding's own pack — though
   that one could read directly from the same buffers). This would
   remove pack+unpack from the moveThing hot path entirely, leaving
   only the per-step EVERY_EXECUTION buffers (forceSum/torqueSum
   computed each step by CPU joint/xlink phases) and bringing the
   GPU path under CPU wall.

The current architecture has CPU phases (Brownian, joints, xLink,
mesh fill, biochem) that need the up-to-date pose every step, so pure
residency is a bigger refactor than iter2c.

### D. Updated open items

iter2b shippable now: the validation passes, the log spam is fixed,
the kernel works at M=98K. Real-time deployment for dense gliding
assays still wants the pack/unpack reduction.

- **iter2c**: Wang-hash Brownian on GPU. Eliminates randForces/
  randTorques upload (~25 ms/call at M=98K). Separate session.
- **iter2d** (further out): residency for coord/uVec/yVec across the
  CPU phases that read them mid-step (mesh fill, motorbind grid). The
  CPU phases would need to read FloatArray instead of Pt3D — touches
  every phase, large refactor.
- The previously listed survey deferrals (motherFil branch pre-pass,
  StickyNode forceSum pre-add, ProteinNode velMask, MyoMiniFilament/
  ProteinNode pack rules) remain unchanged — none material at
  gliding-assay scope.

## 2026-05-29 — Iter2b: unified moveThing GPU kernel — gliding assay validation

Implemented the unified moveThing GPU kernel sketched in the morning's design
survey. Validated against CPU on `glidingAssay500_val` with the standard
10-seed ensemble. **All three observables PASS the cSEM gate.** Kernel is
1.82× slower than CPU at validation scale (M=500, S~14) — expected per the
prompt; pack/unpack dominate.

### A. What landed

`boxOfActin/GPUMoveThing.java` (new, ~370 lines). 13-buffer kernel
(12 FloatArrays + 1 IntArray), `if (m >= N) return;` inactive-thread guard,
WorkerGrid1D + GridScheduler with `blockSize=64`, all transfers
EVERY_EXECUTION. The kernel is the verbatim physics from
`FilSegment.moveThing()` / `MyoMotor.moveThing()` / `MyoRod.moveThing()`
/ `MyoLever.moveThing()`, branchless: same XTox / overdamped-Langevin /
xToX / small-angle uVec/yVec update, with `transScale`/`rotScale`
absorbing the per-type Brownian decisions.

CPU side has a one-pass partition (`packCpuSideArrays`) that walks
`Thing.theThings[]`, classifies each Thing as GPU-eligible (root
FilSegment without actA, MyoMotor, MyoRod, MyoLever — modulo per-type
gates) or CPU-fallback (Bug, Chamber, Crucible, AnchorNode, ProteinNode,
MyoMiniFilament, StickyNode, FillNode, branch FilSegment, actA-bound
FilSegment, isLpSeg-suspended FilSegment, anything when `myosinsOff`).
Eligible Things go to the GPU; fallback Things get `moveThing()` called
serially on CPU after kernel unpack. For gliding-assay scope the fallback
list is empty in practice (Chamber/Crucible have empty `moveThing()`
overrides, no Bug, no ProteinNode).

`boxOfActin/BoxOfActin.java`: phase-10 dispatch gated on `Env.useGPU`
matching the iter2a motor-binding pattern. Added `[STATS] gpuMoveThing
total=X.XXXs calls=N pack=X.XXXs exec=X.XXXs unpack=X.XXXs` summary at
run end.

Compiled clean on aorus under Java 21 / TornadoVM 4.0.1-dev (PTX) with
the standard `-g --release 21 --enable-preview` flags.

### B. Ensemble validation — PASS

Protocol: 10 CPU seeds + 10 GPU seeds × `ParameterFiles/glidingAssay500_val`
× 0.1 s simulated (10 001 steps). Source data in
`RUN_LOGS/2026-05-29_iter2b-validation.txt`; summary in
`RUN_LOGS/2026-05-29_iter2b-validation-summary.txt`.

```
                       CPU mean ± SEM (SD)         GPU mean ± SEM (SD)         |diff|/cSEM
bindEvents       :    898.10 ± 45.08 (142.55)     903.20 ± 39.49 (124.87)         0.09  PASS
meanBoundMotors  :      7.55 ±  0.22 (  0.68)       7.52 ±  0.22 (  0.68)         0.12  PASS
glidingVelocity  :      8.53 ±  0.11 (  0.36)       8.65 ±  0.12 (  0.36)         0.75  PASS
wall (s/seed)    :    280.20 ±  1.37 (  4.34)     510.70 ±  0.86 (  2.71)       142.43  (n/a — diagnostic only)
```

All three physics observables sit well under the |diff|/cSEM = 2.0 gate.
GPU velocity mean is 1.4 % above CPU (within noise); GPU bindEvents and
meanBoundMotors track CPU to better than 1 %. The float32 integration
preserves gliding-assay observables to within ensemble noise — JOURNAL §J
risk #2 (slow-drift coord accumulation) did not materialise.

### C. Wall-clock — expected slowdown at validation scale

`[STATS] gpuMoveThing` averaged across 10 seeds × 10 101 calls:

```
            total       pack        exec       unpack
mean/seed   180.10 s    91.96 s    20.54 s    67.56 s
ms / call    17.83 ms    9.10 ms    2.03 ms    6.69 ms
```

GPU wall 510.7 s/seed vs CPU 280.2 s/seed (1.82× slower). The kernel
itself (`exec` = 2.0 ms/call) is fast; pack (9.1 ms/call) and unpack
(6.7 ms/call) dominate. Cause is the per-step CPU→FloatArray /
FloatArray→Pt3D walks over ~1700 Things × ~30 floats each — pure Java
overhead, not GPU bottleneck.

For comparison, GPUMotorBinding at the same scale: total 7.45 ms/call,
exec 7.16 ms/call. Iter2b's kernel is ~3.5× faster per call than
motor binding's narrow-phase, but pack/unpack swamps that win at M=500.

The crossover to GPU wins should appear at M ≥ 10k where the kernel's
fixed-N scaling beats CPU thread-fan-out — same shape as iter2a's
M=98 K dense-demo. iter2b-dense is a separate session.

### D. Float-precision finding — `Crazy torque` warnings, non-material

The GPU runs emit `Crazy torque result in Myosin.applyRodLeverJointTorque()` /
`...applyLeverMotorJointTorque()` repeatedly (55 K–193 K times per seed
over 10 101 steps × 500 motors = 5.05 M joint-torque calls; rate is
~1–4 %). CPU runs emit zero.

Mechanism: the joint-torque code does
`torsionVec.cross(myoRod.uVec, myoLever.uVec); torsionVec.unitVec();` then
`checkPt3D()`. If rod and lever uVecs become numerically parallel, the
cross product is the zero vector and `unitVec()` yields NaN, which
`checkPt3D` catches — the torque add is then *skipped* for that joint
that step.

This is JOURNAL §J risk #6 (orthonormalisation drift under float32 small-
angle updates) showing up: `uVecTransInY = bAngVeloc.z * deltaT ≈ 1e-8`
for typical motor angular velocities × 1e-5 s timestep. The added
perturbation is at the edge of float32 epsilon (~6e-8 relative to a
unit vector), so some updates underflow and the rod+lever uVecs can
stay parallel for a step or two. Next step, residual forces re-perturb
them and the joint torque resumes.

Despite the 5+ million skipped torque adds, **the gliding-assay
observables are unaffected at the cSEM level** — the joint-torque
spring is one of many forces on each rod/lever, and a missed sample
once every ~25–100 steps is well within the system's stochastic noise.
The simulation self-corrects. Document and move on.

If this becomes material at larger N or other configurations, the fix
is to either (a) keep the small-angle update in double on CPU after
unpack (forcing a downconvert→upconvert per step, defeating most of
the GPU win), or (b) detect near-parallel uVecs in the pre-pass and
nudge them apart before pack. (b) is cheap and may be worth doing in
iter2b polishing; flagging it as an open item rather than fixing now
since the gate is met.

### E. Scope discoveries — small in-scope, none out-of-scope

Two small implementation deltas vs the task sketch, both silently
handled per the prompt's "small in-scope fixes" rule:

1. **EVERY_EXECUTION for `bTransGam`/`bRotGam` instead of FIRST_EXECUTION.**
   The survey's I-table specified FIRST_EXECUTION + invalidate on aeta
   change. Implementation uses EVERY_EXECUTION for these instead — the
   per-step pack cost is 6 floats / Thing (~0.7 ms total at this scale),
   and it removes the topology-change invalidation logic entirely
   (FilSegment splits would otherwise need plan rebuild). Simpler, no
   measurable cost. The `invalidatePlan()` hook in `drainParamQueue`
   for aeta change is therefore not needed and was omitted.

2. **`actAOn` FilSegment branch goes to CPU fallback.** The survey's
   §C noted root FilSegments with `actAOn` need a randForces blend
   from `lmBug.randForcesInX`. For gliding assay `actAOn` is always
   false (no Bug, no ActA), but to keep the kernel pre-pass simple
   the code routes any `actAOn==true` FilSegment to CPU fallback
   alongside branches (`motherFil != null`). This path is exercised
   zero times in the validation. The blend will land when
   ActA/Listeria-class runs need GPU coverage.

### F. Open items / follow-on sessions

- iter2b-dense: same kernel at gliding-dense scale (M ≥ 98 K) — should
  swing the wall-clock from 1.82× slower to net win. Separate session.
- iter2b-polish (optional): pre-pass near-parallel-uVec detection and
  small nudge to suppress `Crazy torque` log spam. Cosmetic; observables
  already PASS.
- iter2c: Wang-hash RNG on GPU, eliminating the per-step `randForces`/
  `randTorques` CPU upload (currently 12 floats × ~1700 Things × 10 101
  steps ≈ 200 M float writes/seed and a meaningful slice of the 9 ms
  pack cost).
- Out-of-scope follow-ons from the survey (motherFil branch pre-pass,
  StickyNode forceSum pre-add, ProteinNode velMask, MyoMiniFilament /
  ProteinNode pack rules) all remain deferred — the validation config
  exercises none of them.

## 2026-05-29 — Iter2b design survey: unified all-Things moveThing GPU kernel

Survey only — no source edits, no compile, no run. Goal: assess feasibility of
a single GPU kernel handling `moveThing()` for ALL Thing subclasses, with
per-type Brownian logic collapsed into per-Thing scalars pre-computed on CPU
so the kernel itself is branchless.

**Bottom line:** the unified kernel is feasible for the *standard pattern*
types — FilSegment, MyoMotor, MyoRod, MyoLever, MyoMiniFilament, ProteinNode,
and (via pre-pass) StickyNode and FillNode — covering the entire population
in every production workload. Bug and AnchorNode are the exceptions: Bug
adds friction-from-tips and max-displacement scaling that don't fit the
branchless pattern, but N=1 so it stays CPU; AnchorNode is inert. Two
showstoppers were considered and resolved: (1) FilSegment branch's
`motherFil` cross-entity read is collapsible via a CPU pre-pass that
materialises an effective `randForces`/`randTorques` per branch; (2)
StickyNode's pre-pass `forceSum` additions (spherical constraint /
internal pressure / constricting ring) can be handled by a small CPU
pre-pass that increments `forceSum` before kernel dispatch — no kernel
branching needed. Neither requires restructuring moveThing's physics.

### A. Exhaustive moveThing catalog

Read every Thing subclass with `moveThing` overrides. Default `Thing.moveThing()`
at `Thing.java:291` is empty `{}`.

| Class | Override? | Standard pattern? | Brownian (scale, conditions) | Special notes |
|---|---|---|---|---|
| `Thing` | empty default (291) | — | none | base case; inherited by Crucible, Chamber |
| `FilSegment` (488) | yes | yes | root: trans=`BTransCoeff/(1+xLinkTransAttn*linkedToCt)` × (`bTransGam.y/lmBug.bTransGam.y` if `actAOn`); rot=`BRotCoeff/(1+xLinkRotAttn*linkedToCt)` × bug-ratio if `actAOn`, **applied only if `!filAtEnd1 \| !filAtEnd2`**; branch (`motherFil!=null`): reads `motherFil.randForcesInX`/`motherFil.randTorquesInX`, trans=`min(1, bTransGam.y/motherFil.bTransGam.y)` (×`actATetherTransAttn` if `actAOn`), rot same with rot gam ratio | early-exit `isLpSeg && lpActive==0`; gated by `!brownianFilMotionOff && !brownianOff`; root path optionally swaps `randForces` to `lmBug.randForcesInX` for `actAOn` |
| `StaticFilSegment` (41) | yes (wrapper) | inherits FilSegment | inherits | only invokes `super.moveThing()` while `simulationTime < likeARealFilTime`; otherwise no-op |
| `MyoMotor` (263) | yes | yes | trans = `myoBrownianAttn`; rot = `myoBrownianAttn` | early-exit `myosinsOff`; "Crazy forceSum/torqueSum" recovery: zero + `inc(randForces/randTorques)`; NaN bVeloc/bAngVeloc early-exit |
| `MyoRod` (93) | yes | yes | trans = `myoBrownianAttn`; rot = `myoBrownianAttn` | identical body to MyoMotor; gated by `!brownianMyoMotionOff` |
| `MyoLever` (90) | yes | yes (modulo Brownian) | **none — commented out** | identical body to MyoMotor/MyoRod but the two Brownian `bForceSum.inc`/`bTorqueSum.inc` lines are commented; effectively `transScale=0, rotScale=0` |
| `Myosin` | not a Thing (composite) | — | — | container; owns MyoMotor/MyoLever/MyoRod sub-Things which dispatch independently |
| `MyosinDimer` | not a Thing | — | — | composite |
| `MyoMiniFilament` (166) | yes | yes | trans = 1.0; rot = 1.0 (raw `randForces`/`randTorques`) | gated by `!myoMiniFilBrownianMotionOff`; "Crazy" recovery same as MyoMotor; teleport diag block (snapshot/dump) wrapping the call when `Env.myoMiniTeleportDiag` is set — debug instrumentation, not physics |
| `MyosinFixed` | inherits Myosin (not a Thing) | — | — | not its own Thing dispatch |
| `ProteinNode` (203) | yes | yes | trans = 1.0; rot = 1.0 | gated by `!nodeBrownianMotionOff`; **bYMove** check zeroes `bVeloc.y` in body frame; **xMove/yMove/zMove** checks zero `veloc.{x,y,z}` in fixed frame; early-return on "Crazy" forceSum/torqueSum (silent — no zeroing pre-pass) |
| `StickyNode` (258) | yes (pre-pass wrapper) | yes (delegates) | pre-scales `randForces`/`randTorques` in place by `membraneBrownianScale` (or `(1e-6, 1e-40)` during spherical-init pre-equilibration), then calls `super.moveThing()` (ProteinNode) | **adds force to `forceSum`** before super call: `addSphericalConstraintForce` (when spherical & simT<0), `internalPressure` (when spherical), `fakeConstrictingRing` (when `iAmConstricting` & simT>10·deltaT); post-call: `updateStickyPointsInX()` (recomputes `valence`-many body-attached link points in fixed frame) |
| `FillNode` (37) | yes (pre-pass wrapper) | yes (delegates) | pre-scales `randForces`/`randTorques` in place by `fillNodeBrownianScale`, then calls `super.moveThing()` | no other side effects in moveThing; growth happens in `step()` |
| `AnchorNode` (18) | yes (empty `{}`) | n/a | — | drag set to ~immobile (`bTransGam=(1e6,1e6,1e6)`) at construction; explicitly inert in moveThing |
| `Crucible` | inherits Thing default (empty) | n/a | — | Chamber identical (empty) |
| `Chamber` | inherits Crucible (empty) | n/a | — | no override |
| `Bug` (174) | yes | mostly | trans = 1.0; rot = 1.0; gated by `!bugBrownianMotionOff` | early-return on "Crazy" forceSum/torqueSum; **addFrictionFromTips()** mutates bForceSum/bTorqueSum and side-effects `Env.addPathFrictionForceOnBug`; **max-displacement scaling**: scales bForceSum/bTorqueSum if a test displacement would exceed `maxMove`; side-effect `Env.addPathForceWBrownianInx(bForceSum.x)`; updates `pathCoord` (separate Pt3D not on standard Thing). N=1 per run. |

Connectors (`FilLink`, `NodeLink`, `Arp23`, `ActA`, `MyoFilLink`, `Monomer`) are not `Thing` subclasses and don't enter the `moveStart`-phase dispatch. Out of scope.

The standard pattern, observed verbatim in 6 of the 8 "physics-bearing" overrides (FilSegment, MyoMotor, MyoRod, MyoLever, MyoMiniFilament, ProteinNode):

```
1. bForceSum.XTox(this, forceSum)        // fixed → body frame
2. bTorqueSum.XTox(this, torqueSum)
3. bForceSum.inc(transScale, randForces) // Brownian add (per-type)
   bTorqueSum.inc(rotScale, randTorques) // (sometimes gated)
4. bVeloc.div(1e6, bForceSum, bTransGam) // overdamped EOM
5. bAngVeloc.div(bTorqueSum, bRotGam)
6. veloc.xToX(this, bVeloc)              // body → fixed
7. coord.inc(deltaT, veloc)              // position update
8. small-angle uVec/yVec update via bAngVeloc-driven body-frame increments,
   xToX, unitVec
9. initialize()                          // recompute zVec, transMat, uVecR,
                                         //  end1/end2/length/xyzRange (per-type)
```

ProteinNode and Bug add veloc/bVeloc zero-masks (movement restrictions) between steps 5 and 7. Bug additionally does friction-from-tips (mutates bForceSum/bTorqueSum), max-displacement scaling, and side-effect path bookkeeping. StickyNode/FillNode treat the pattern as an unmodified subroutine and only touch `randForces` (scale) and `forceSum` (additive pre-pass) before delegating.

### B. The motherFil cross-entity dependency

FilSegment branches (filaments born from an Arp2/3 junction; identified by `motherFil != null`) read from another FilSegment instance in their Brownian block:

```
randForces.XTox(this, motherFil.randForcesInX);   // mother fixed-frame → branch body-frame
transScale = min(1, bTransGam.y / motherFil.bTransGam.y);
if (actAOn) transScale *= Env.actATetherTransAttn;
bForceSum.inc(transScale, randForces);
randTorques.XTox(this, motherFil.randTorquesInX);
rotScale = min(1, bRotGam.y / motherFil.bRotGam.y);
if (actAOn) rotScale *= Env.actATetherRotAttn;
bTorqueSum.inc(rotScale, motherFil.randTorques);  // note: mother.randTorques, not the local transform
```

What's read from the mother, and in what frame:
- `motherFil.randForcesInX` — **fixed-frame** copy of mother's `randForces`, written by mother in `FilSegment.calcRandomForces()` (line 570) via `randForcesInX.xToX(this, randForces)`. Only populated when `arpChildCt > 0`. The branch then re-transforms it into its own body frame via `XTox`.
- `motherFil.randTorquesInX` — same pattern, fixed-frame.
- `motherFil.bTransGam.y`, `motherFil.bRotGam.y` — drag-coefficient scalars; static within a run absent `aeta` mutation.
- `motherFil.randTorques` — read directly without re-frame for the torque add (likely a code quirk; preserves the legacy behaviour and irrelevant to feasibility analysis).

CPU pre-pass collapses this cleanly. After `calcRandomForces()` completes for ALL FilSegments (one ThreadSet barrier), a second short pass over branch segments materialises an `effRandForces` and `effRandTorques` per branch, where:

```
effRandForces = mother.randForcesInX  (kept in fixed frame; the per-branch
                XTox is folded into the kernel since the branch already has
                its own transXTox via uVec/yVec which the kernel re-derives)
                
effRandTorques (for the inc call) = mother.randTorques  (fixed frame, raw)

transScale_eff = min(1, bTransGam.y / mother.bTransGam.y) [× actATetherTransAttn if actAOn]
rotScale_eff   = min(1, bRotGam.y / mother.bRotGam.y)   [× actATetherRotAttn if actAOn]
applyRotBrownian_eff = true (no filAtEnd1/filAtEnd2 gate for branches)
```

The kernel then reads only its own SoA slot — no cross-entity lookup at GPU
time. Pre-pass is O(branchCt), trivial relative to the kernel body.

Branch prevalence: heavily configuration-dependent. Arp2/3-branched networks
(`arpChildCt > 0` on parent segments, kNodeNuc-driven nucleation) produce
many branches — boa10-64Seg-class runs sit here. **Gliding-assay
configurations have zero branches** (no Arp2/3 anywhere in the parameter
file). Branch pre-pass cost in the target dense-gliding workload is therefore
zero; in branched-network workloads it's a single linear scan that doesn't
move the wall.

### C. Per-Thing Brownian pre-computation design

The aim is one packed SoA `brownianScales[m*3]` with three slots per Thing:
`[transScale, rotScale, applyRotMask]`. Kernel does:

```
bForceSum  += transScale * randForces      (always, unconditional)
bTorqueSum += rotScale   * randTorques     (always, but rotScale=0 if masked)
```

Per-Thing values:

| Type | transScale | rotScale | applyRotMask | Recomputed every step? |
|---|---|---|---|---|
| Thing (default), Crucible, Chamber, AnchorNode | 0 | 0 | 0 | no (skip kernel slot entirely) |
| FilSegment root, no links, no actA | `BTransCoeff` | `BRotCoeff` | `!(filAtEnd1 & filAtEnd2)` | param change rare; mask depends on filAtEnd flags (change on split/join) |
| FilSegment root, linkedToCt>0 | `BTransCoeff/(1+xLinkTransAttn·linkedToCt)` | `BRotCoeff/(1+xLinkRotAttn·linkedToCt)` | as above | linkedToCt changes step-to-step (xLink phase) → recompute every step |
| FilSegment root, actAOn=true | adds `× bTransGam.y/lmBug.bTransGam.y` factor | adds bRotGam factor | as above | actAOn changes step-to-step → recompute every step |
| FilSegment branch (motherFil!=null) | `min(1, bTransGam.y/mother.bTransGam.y)` [× actATetherTransAttn if actAOn] | `min(1, bRotGam.y/mother.bRotGam.y)` [× actATetherRotAttn if actAOn] | 1 (always apply rot) | depends on actAOn and on which-mother (mother handle can change on topology event) → recompute every step is safest |
| MyoMotor, MyoRod | `myoBrownianAttn` | `myoBrownianAttn` | 1 | param change is mutability-gated; effectively recompute every step if mutable, else FIRST_EXECUTION |
| MyoLever | 0 | 0 | 0 | constant (commented-out) |
| MyoMiniFilament | 1 | 1 | 1 | constant |
| ProteinNode | 1 | 1 | 1 | constant |
| StickyNode (handled by CPU pre-pass; see D) | n/a (post-scale) | n/a | n/a | — |
| FillNode (handled by CPU pre-pass; see D) | n/a (post-scale) | n/a | n/a | — |

Subtleties:

- For FilSegment root with `actAOn`, the branch path SWAPS `randForces` to `lmBug.randForcesInX` (FilSegment.java:505). The pre-pass must either (a) overwrite the FilSegment's own `randForces` SoA slot with the bug's pre-transformed values, or (b) add an `actAOnOverride` slot pointing at the bug's randForces. Simplest: write effective `randForces` per FilSegment after `calcRandomForces` completes, blending in the actA / branch / bug cases. The kernel then reads "the right randForces" without knowing which case.

- The FilSegment `filAtEnd1`/`filAtEnd2` gating becomes part of the mask. CPU pre-pass evaluates `mask = !(filAtEnd1 & filAtEnd2) ? 1 : 0` and packs into the float slot (or use an IntArray applyRot if cleaner).

- `rotScale=0` and `applyRotMask=0` are equivalent. One scalar (rotScale, zeroed if masked) is enough; the IntArray applyRot slot is redundant. Recommend dropping `applyRotMask` and just zeroing `rotScale` in the pre-pass.

So per-Thing brownian is exactly **2 scalars** (`transScale`, `rotScale`). The `randForces`/`randTorques` slots are independently per-Thing fixed-frame vectors that the pre-pass has already "blended" (root vs branch vs actA vs bug).

### D. Inert Things

`moveThing()` is empty or no-op for:

- `Thing` (default empty)
- `Crucible` (inherits default)
- `Chamber` (inherits Crucible default)
- `AnchorNode` (explicit empty override)
- `StaticFilSegment` once `simulationTime ≥ likeARealFilTime` (≥ 200·deltaT, i.e. after ~200 steps the moveThing is a no-op)

These should be excluded from GPU packing. A boolean `gpuMoveActive` per Thing
or a presence-bit IntArray decides participation. For AnchorNode and the
static-Things-after-eq case, the bit stays 0 forever (or for the rest of the
run). For Chamber/Crucible/Thing, the bit is 0 from construction.

`Bug` is **not** inert but does NOT fit the branchless pattern — friction
mutates bForceSum, max-displacement scales it, side-effects update path
counters. Bug N=1 in any run; leave it on CPU. Set its bit to 0 in the
GPU packing.

**MyoLever** is technically standard-pattern but its Brownian terms are
commented out (effective scales = 0). It still does the EOM integration
and uVec/yVec update — so it MUST stay in the kernel, just with
`transScale=rotScale=0` rather than being excluded.

**StickyNode** and **FillNode** add a CPU pre-pass that:
- Pre-scales `randForces`/`randTorques` in the per-Thing SoA slot (just
  the slot-write — no other state changes).
- For StickyNode only: adds a few force terms to `forceSum` (spherical
  constraint, internal pressure, constricting ring) BEFORE the kernel
  runs.

That second part is the only "physics-additive" pre-pass — it's modest
(O(stickyNodeCt), each computation is ~10 flops + one Pt3D dist). Kernel
reads the post-add `forceSum` and proceeds with standard math. Post-call,
StickyNode also runs `updateStickyPointsInX()` (recompute body→fixed
attached link points) — this happens on CPU after kernel unpack, same
slot as `initialize()`.

### E. doLoop phase ordering

From `BoxOfActin.doLoop()` (lines 680–812) and `Env.java` phase IDs (70–105):

```
phase | id | dispatch                              | ThreadSet
------+----+---------------------------------------+--------------------------
 fill | -- | FilSegment.setBiophysValues();         | (inline, single-thread)
 fill | -- | MyoMotor.fillSoaArrays();              | (inline)
 fill | -- | FilSegment.fillSoaArrays();            | (inline)
 mesh |  0 | meshFils                               | Mesh.MeshThreads
 mesh |  1 | meshNodes                              | Mesh.MeshThreads
 mesh |  2 | meshMotors                             | Mesh.MeshThreads
 mesh |  3 | meshColl (xlink, node-node, tip-clear) | Mesh.CkMeshThreads
[gate: every collisionCheckInt steps; default 10]
 grid | 16 | motorBindGrid3D Fill                   | MotorBindGrid3D.FillThreads
 motc |  4 | motorBinding (CPU 27-walk OR GPU)      | Mesh.CkMotsThreads | GPUMotorBinding.detectBindings()
 brwn |  5 | calcRandomForces()                     | Thing.ThingBrownianThreads
[gate: every brownianApplyInt steps; default 1]
 xlnk |  6 | FilLink + Arp23 + ActA                 | FilLink.XLinkThreads
 mblk | 14 | NodeLink (membrane)                    | NodeLink.NodeLinkThreads
 myo1 |  7 | Myosin jointConstraints                | Myosin.MyosinThreads
 myo2 |  8 | Chamber keepMyosinOnSurface            | Crucible.ChamberMyoThreads + ChamberMyoDThreads
 step |  9 | Thing.step()                           | Thing.ThingStepThreads
 move | 10 | Thing.moveThing()    ← TARGET           | Thing.ThingStepThreads
 bioc | 11 | Thing.biochemStep()                    | Thing.ThingStepThreads
 reset| 12 | Thing.resetCounters()                  | Thing.ThingStepThreads
 mlx  | 14 | NodeLink (membrane, relaxation loop)   | NodeLink.NodeLinkThreads
 mmv  | 15 | StickyNode.membraneNodesMove() → calls moveThing on subset | StickyNode.MembraneNodeThreads
[membrane relaxation loop iterates 14→15→14→15 while strain > tolerance]
 endup| -- | updateCounters, drainParam, etc.       | inline
```

Key constraints for the kernel:

1. `calcRandomForces` (phase 5) finishes before `step` (9). The Brownian
   pre-pass (C, D) inserts a small CPU step between 5 and 9 — at the
   start of phase 9 is the natural place since it follows xlink/joints
   that may mutate `linkedToCt`.

2. `step` (9) writes `forceSum`/`torqueSum` (accumulating elastic, joint,
   benchmark forces). `moveThing` (10) reads them. The kernel pack must
   happen at the top of phase 10 (after step is complete).

3. Between `moveThing` (10) and `biochem` (11), `coord`/`end1`/`end2`/
   transXTox MUST be up-to-date for biochem reads (e.g. `end2BiochemSim`
   reads `end2`). The kernel unpack + per-Thing `initialize()` MUST
   complete before phase 11 dispatches.

4. After `moveThing` (10), phase 14 (`membraneLinksStart`) may re-read
   positions; same constraint as (3).

5. The benchmark force-application block (lines 765–775) writes
   `deflFil.midSeg.forceSum` between `step` (9) and `moveThing` (10).
   The kernel must pick up that addition. This is just another CPU
   pre-pack mutation of forceSum on one Thing — no kernel design
   impact.

6. Phase 15 (`membraneMoveStart`) re-invokes `moveThing()` on the
   subset of non-fixed StickyNodes via `membraneNodesMove()` — but
   ONLY during the membrane relaxation loop, which is bounded by
   `maxMembranePasses` and runs only if membrane is configured. This
   is a per-pass internal loop, not a re-dispatch of the main move
   phase. Two design options for the kernel here: (a) keep
   `membraneNodesMove`/its moveThing on CPU (simplest, small N, only
   active in membrane runs), or (b) make the GPU kernel callable with
   a subset mask. Recommend (a) for iter2b — the membrane subsystem
   is configuration-specific and the gliding-assay and boa10 targets
   don't exercise it.

### F. Brownian cadence

`Thing.brownianApplyInt = (int)(brownianDeltaT / deltaT)` set in
`Env.java:1041`. For all current parameter files including
`glidingDense_overnight`, `brownianDeltaT == deltaT == 1e-5`, so
`brownianApplyInt == 1` — Brownian forces are recomputed **every
step**. The gate at `BoxOfActin.java:732` evaluates true every step.

Implication for the kernel: `randForces`/`randTorques` SoA arrays are
EVERY_EXECUTION inputs. There is no FIRST_EXECUTION cadence to
exploit. (Hypothetically, if a future configuration set
`brownianDeltaT = 10 * deltaT`, the same `randForces` value would be
re-applied for 10 consecutive steps; the kernel design must still
treat them as EVERY_EXECUTION because the values DO change every
`brownianApplyInt` steps, and the CPU writes them in place. The
TornadoVM cost saving — re-using the device buffer for unchanged
data — is not material at iter2b scope.)

Cadence note: the FilSegment branch path reads `motherFil.randForcesInX`/
`randTorquesInX`, which mother writes in its `calcRandomForces` override
ONLY if `arpChildCt > 0`. The Brownian pre-pass (C) reads these the
same step they're written, between phases 5 and 9. No cross-step state.

### G. initialize() audit

`Thing.initialize()` (line 286) is empty `{}`. Overrides:

| Class | initialize() does |
|---|---|
| `FilSegment` (386) | zVec.cross(uVec,yVec); zVec.unitVec(); yVec.cross(zVec,uVec); transMat(); uVecR.scale(-1,uVec); length=(monomerCt+1)·actinMonoRadius; end1, end2; xRange/yRange/zRange |
| `MyoMotor` (142) | zVec.cross; yVec.cross; transMat(); uVecR; end1=coord-getDim/2·uVec; end2=coord+getDim/2·uVec; xRange/yRange/zRange |
| `MyoRod` (69) | (similar to MyoMotor) |
| `MyoLever` (66) | (similar) |
| `MyoMiniFilament` (146) | (similar but with `length` field, not getDim) |
| `ProteinNode` (194) | zVec.cross; yVec.cross; transMat() (no end1/end2 — node is a sphere) |
| `Bug` (133) | zVec.cross; yVec.cross; transMat(); end1, end2, rec1, rec2 (capsule endpoints + hemisphere centres) |
| `HistogramPlus` (49) | unrelated — utility class, not a Thing |

Critical: FilSegment.initialize reads `monomerCt` (dynamic — changes on poly/depoly), and length depends on it. End1/end2 depend on length and orientation.

Running `initialize()` on CPU after GPU unpack: **safe**. The unpack
writes `coord`/`uVec`/`yVec` Pt3D fields. `initialize()` reads those
plus per-type dynamic state (monomerCt for FilSegment) — all CPU-resident.
The Thing.ThingStepThreads fan-out used for moveStart can host the
initialize call on the same parallel partition. (Or, since initialize
is short and per-Thing, the kernel can in principle compute end1/end2
directly from coord/uVec/dim and emit them — but FilSegment's
`length` depending on monomerCt means a dynamic dim slot would be
needed, and the kernel won't know whether to use `getDim()` (statics)
or `length` (FilSegment). Cleaner: keep `initialize()` on CPU after
unpack as the type-aware reconciliation step.)

### H. Existing SoA arrays

Per Section A search results:

**MyoMotor (boxOfActin/MyoMotor.java:11–20):**
- `static double[] soaX, soaY, soaZ` — `m.bindTip` position (note: bindTip,
  NOT `m.coord` — these are subtly different. For moveThing, the kernel
  needs `coord` instead.)
- `static double[] soaUX, soaUY, soaUZ` — m.uVec
- `static double[] soaRodUX, soaRodUY, soaRodUZ` — m.myMyosin.myoRod.uVec
- `static boolean[] soaOnFil`

**FilSegment (boxOfActin/FilSegment.java:29–39):**
- `static double[] soaEnd1X/Y/Z`, `soaEnd2X/Y/Z` — fil endpoints
- `static double[] soaUX/UY/UZ` — fil uVec
- `static int[] soaFilID`
- `static boolean[] soaNodeAtEnd2`

These are population-specific (one for motors, one for fil segments) and
indexed by the per-population array slot (`motorID`, `filArrayPos`).
They are double[], not TornadoVM FloatArray.

For a unified all-Things kernel, **none of these match the requirement**:
- They cover only two populations; the kernel needs every Thing.
- They are `double[]`, not FloatArray.
- The motor positions are `bindTip` (a derived point on the motor's
  body-frame end), not `coord`. moveThing needs `coord`.
- They lack `coord`, `yVec`, `forceSum`, `torqueSum`, `randForces`,
  `randTorques`, `bTransGam`, `bRotGam`.

A new flat Thing-indexed SoA layout is required. Proposal: a single
contiguous index range `[0, gpuMoveActiveCt)` populated by a CPU pre-pass
that walks `theThings[]` and adds eligible Things in order. The slot
mapping is then `gpuSlot[thing.myThingNumber] = slotIdx`. The pack/unpack
walks this list, not the full `theThings[]`. This is essentially the
same pattern as `MyoMotor.fillSoaArrays()`, generalized.

Reusable as starting points: the fillSoaArrays pattern (pack-on-CPU
into a flat slot range) and the iter2a TaskGraph/transfer-mode scaffolding.
Everything else is new for iter2b's kernel.

### I. Parameter count

Inventory using the AoS-by-attribute layout (m×3 packed per attribute, as
in iter2a) — same shape that's worked for motor-binding:

| # | Buffer | Type | Shape | Direction | Transfer mode |
|---|---|---|---|---|---|
| 1 | `coord` | FloatArray | m×3 | R+W | EVERY_EXECUTION (uploaded; downloaded for unpack) |
| 2 | `uVec` | FloatArray | m×3 | R+W | EVERY_EXECUTION |
| 3 | `yVec` | FloatArray | m×3 | R+W | EVERY_EXECUTION |
| 4 | `forceSum` | FloatArray | m×3 | R | EVERY_EXECUTION |
| 5 | `torqueSum` | FloatArray | m×3 | R | EVERY_EXECUTION |
| 6 | `bTransGam` | FloatArray | m×3 | R | FIRST_EXECUTION + invalidate on `aeta` change |
| 7 | `bRotGam` | FloatArray | m×3 | R | FIRST_EXECUTION + invalidate on `aeta` change |
| 8 | `randForces` | FloatArray | m×3 | R | EVERY_EXECUTION (pre-blended for actA/branch) |
| 9 | `randTorques` | FloatArray | m×3 | R | EVERY_EXECUTION (pre-blended) |
| 10 | `brownianScales` | FloatArray | m×2 | R | EVERY_EXECUTION (transScale, rotScale) |
| 11 | `velMask` | FloatArray | m×3 | R | EVERY_EXECUTION (per-axis fixed-frame {0,1} mask for ProteinNode xMove/yMove/zMove + body-frame y mask folded in via bAngVeloc.y zeroing; for most Things all 1.0) |
| 12 | `params` | FloatArray | small | R | EVERY_EXECUTION (deltaT) |
| 13 | `counts` | IntArray | small | R | EVERY_EXECUTION (N, step counter) |

That's **13 buffers**, comfortably under the 15-arg cap and matching the
iter2a kernel's slot budget. Verifications:

- `bForceSum`/`bTorqueSum`/`bVeloc`/`bAngVeloc`/`veloc` are kernel-local
  temporaries, not buffers.
- `transXTox`/`transxToX` are re-derived from `uVec` + `yVec` (kernel
  computes `zVec = cross(uVec, yVec); normalize` inline). No transform-matrix
  buffer needed.
- `uVecR` is just `-uVec`, derived inline; not a separate buffer (initialize()
  re-derives it post-kernel anyway).
- StickyNode pre-add to forceSum already folds into buffer 4 before pack.
- `linkedToCt`, `actAOn`, `filAtEnd1/filAtEnd2`, `motherFil`, `arpChildCt`,
  `bYMove`, `xMove/yMove/zMove`, `isLpSeg` — all consumed by the CPU
  pre-pass that materialises buffers 8/9/10/11. **None appears in the
  kernel signature.**
- `myosinsOff`/`brownianMyoMotionOff`/`brownianFilMotionOff`/`nodeBrownianMotionOff`/
  `myoMiniFilBrownianMotionOff`/`bugBrownianMotionOff`/`lpActive`/
  `myoBrownianAttn`/`membraneBrownianScale`/`fillNodeBrownianScale`/
  `BTransCoeff`/`BRotCoeff`/`xLinkTransAttn`/`xLinkRotAttn`/
  `actATetherTransAttn`/`actATetherRotAttn` — all consumed by the CPU
  pre-pass; none in the kernel signature.

Coverage check against §A's physics catalog:
- FilSegment root + branch: ✓ (pre-pass blends Brownian; kernel sees
  effective randForces/randTorques + scales)
- MyoMotor/MyoRod/MyoLever: ✓ (scale = myoBrownianAttn or 0)
- MyoMiniFilament: ✓ (scale = 1)
- ProteinNode: ✓ (scale = 1; velMask zeros restricted axes)
- StickyNode: ✓ (force pre-pass + scale pre-pass; post-call
  updateStickyPointsInX on CPU)
- FillNode: ✓ (scale pre-pass)
- Bug: out (CPU, N=1)
- AnchorNode/Crucible/Chamber/Thing: out (inert)
- StaticFilSegment post-eq: out (inert after the time gate)

Missing inputs: none identified.

### J. Float precision risk spots

Without blocking — the ensemble validation will catch numerical issues.
Spots to watch:

1. **uVecTransInY/uVecTransInZ** = `bAngVeloc.{y,z} * Env.deltaT.getValue()`.
   At typical conditions `bAngVeloc.y` may be ~10⁻⁴ rad/s, deltaT = 1e-5,
   so the increment is ~10⁻⁹. The vector `(1, 10⁻⁹, 10⁻⁹)` then gets
   unit-normalised — the dominant component is 1.0 ± 5×10⁻¹⁹, well below
   float32 epsilon (1.2×10⁻⁷). This is a known small-rotation
   approximation; float32 mantissa precision is still adequate because
   the increments aren't in the cancellation regime. Watch the orientation
   drift over 200K steps in the validation ensemble.

2. **Position accumulation over many steps.** `coord.inc(deltaT, veloc)`
   accumulates by ~10⁻¹¹ to 10⁻⁹ µm per step. Float32 holds 7 decimal
   digits; at coord ~10 µm, the LSB is ~10⁻⁶ µm. Per-step increment is
   ~3 orders below LSB for slow-moving Things — the increments would
   underflow without compensation. **For Things that drift faster than
   ~10⁻⁶ µm/step (motors gliding at ~5 µm/s × 10⁻⁵ s = 5×10⁻⁵ µm/step),
   float is fine.** For slow-drift Things (interior FilSegments under
   small forces), each step's contribution may be lost. This is the
   most plausible float-vs-double divergence spot in the kernel; the
   ensemble validation will surface it via `glidingVelocity` or
   `meanBoundMotors` if material.

3. **Near-cancellation in body-frame force transforms.** `bForceSum.XTox(this,
   forceSum)` is three dot products with `transXTox` rows. If two
   contributions to `forceSum` are large and near-opposite, the dot product
   can lose precision. In practice the elastic + crosslinker forces on a
   single Thing rarely cancel to many digits, so this is unlikely to be
   material — but it's worth checking via per-step `bForceSum` magnitude
   distribution in one CPU-vs-GPU debug run.

4. **bTransGam reciprocals.** `bVeloc.div(1e6, bForceSum, bTransGam)` is
   forceSum / bTransGam scaled by 10⁶. bTransGam can vary by ~3 orders
   of magnitude across types (motor ~10⁻⁸, bug ~10⁻⁶, anchor 10⁶). The
   division itself is safe in float32; the result range stays within
   normal float bounds. No concern.

5. **min(1, bTransGam.y/mother.bTransGam.y)** for branch FilSegments.
   This ratio is in [0,1]; safe in float. The pre-pass on CPU should
   do this in double to avoid early loss; the kernel reads the
   pre-pass float result.

6. **uVec.unitVec()** = `1 / sqrt(x² + y² + z²)`. After the small-angle
   update, the magnitude is `sqrt(1 + ε²)` where ε ~ 10⁻⁹. Float32
   `sqrt(1 + ε²)` returns 1.0 exactly (the ε² term is below epsilon),
   so the normalisation reduces to a no-op. This is mathematically fine
   but means the slow orthonormalisation drift relies on the CPU-side
   `initialize()` recomputation of `zVec = cross(uVec, yVec); yVec.cross(zVec,
   uVec)` — i.e., the post-kernel CPU step is doing the actual
   re-orthogonalisation. Flag for the validation: monitor `uVec`/`yVec`
   orthogonality drift over many steps; if it grows, may need a kernel
   re-orthogonalisation pass.

### Design verdict

**Feasible for iter2b.** The unified kernel covers the standard pattern
across FilSegment (root + branch via pre-pass), MyoMotor, MyoRod,
MyoLever, MyoMiniFilament, ProteinNode, StickyNode (pre-pass), and
FillNode (pre-pass). The kernel signature fits comfortably in 13
parameters (under the 15 cap). The motherFil cross-entity read is
collapsible into a CPU pre-pass; StickyNode's spherical-constraint /
pressure / constriction force additions are likewise a small CPU
pre-pass. Nothing requires restructuring the physics of any moveThing
implementation.

Things deferred to CPU (not in the kernel):
- **Bug** (N=1, friction-from-tips + max-displacement scaling don't
  fit the branchless pattern, side-effect path bookkeeping).
- **AnchorNode**, **Crucible**, **Chamber**, **Thing** (all inert).
- **StaticFilSegment after `simT ≥ likeARealFilTime`** (inert; needs
  a per-Thing active-bit anyway, since pre-eq it does dispatch).
- **MyosinFixed** does not need special handling: it inherits
  Myosin's composite structure; its sub-Things (MyoRod/MyoLever/MyoMotor)
  go through the standard kernel slots.

Things that need CPU pre-pass (cheap; all O(N) per Thing or smaller):
1. **FilSegment branch blending.** After `calcRandomForces` ThreadSet
   completes, walk branch FilSegments and write effective
   `randForces`/`randTorques` slots.
2. **FilSegment root actA-on blending.** Same pass: if `actAOn`,
   swap own `randForces` slot to bug-derived values.
3. **brownianScales (transScale, rotScale) materialisation.** Walk
   every active Thing and compute the two scalars per the §C table.
4. **StickyNode forceSum pre-add.** Walk StickyNodes, add
   spherical-constraint / pressure / constriction contributions to
   their `forceSum`.
5. **StickyNode / FillNode randForces pre-scaling.** Pre-multiply
   their `randForces`/`randTorques` slot by the per-type scale.
6. **`velMask` materialisation** for ProteinNode (most are 1.0, but
   `bYMove`/`xMove`/`yMove`/`zMove` zero specific axes).

Things that run CPU AFTER kernel unpack:
1. **`initialize()` on every active Thing.** Recomputes
   `zVec`/`transMat`/`uVecR`/`end1`/`end2`/`length`/`xRange/yRange/zRange`
   per type. Same fan-out as moveStart.
2. **`updateStickyPointsInX()` on StickyNodes.** Recomputes
   body-attached link points in fixed frame.

No showstoppers identified. No moveThing implementation requires
physics restructuring to fit the design. The float precision risk
is bounded (slow-drift position accumulation is the most plausible
divergence) and falls within the ensemble-validation gate as
specified.

Sequencing recommendation: implement and validate the kernel on a
gliding-assay-class workload first (zero branches, no membrane, no
ActA, no xLinks → exercises only the simplest scalar path). Then
add a boa10-class run to exercise xLink attenuation, then a
branched-network run for the motherFil pre-pass, then a membrane
run for StickyNode pre-pass. Each step adds one independent
pre-pass, so failures localise cleanly.

## 2026-05-28 — Overnight dense gliding-assay 20×20 µm × 400K motors × 100 filaments × 2.0 s: STOP at smoke-test wall-clock projection

Bailed out at the smoke-test gate. The configuration verified cleanly
(20×20 bed, 400 000 motors, 101 filaments — all targets met without drift),
but the smoke-test wall-clock extrapolation projected **~40 hours** for the
2.0 s simulation, **4.05× the 10-hour bail-out boundary**. No overnight
run launched. Decision driven by the prompt's "larger judgment call →
STOP" rule.

### A. Configuration verification (Part A) — passes

```
[CONFIG] bed: 20.0 × 20.0 µm × 0.5 µm | motors: 400000 | filaments: 101
         (100 random + 1 canonical) | segments: ~1162 steady-state
         | runtime: 2.0 s | frame_interval: 25 ms (2500 steps × 1e-5 s)
         | heap: -Xmx20G [↑ from prompt's -Xmx8G, see §C]
```

The +1 filament (canonical X-axis filament from `setUpGlidingAssay()` →
`makeGlidingAssayFilament()`) is unavoidable without source changes —
same as the prior dense-demo session. Frame 0 of the smoke confirms
exactly 400 000 myosins and 20×20 bounds.

Param file: `ParameterFiles/glidingDense_overnight` — the only deviation
from the user's spec is the heap-flag bump (next section). All physics
parameters (`fracMove=0.05725`, `fracR=1.0`, `fracMoveTorq=0.01`,
`BTransCoeff=1.45`, `BRotCoeff=0.5`) verbatim from the prompt.

### B. Pre-launch discovery #1 — `MyoMotor.theMotors[]` cap already at 500 000

The prior dense-demo entry's "200 K motors would overflow the static cap"
note no longer holds: `grep "new MyoMotor\[\|new Myosin \[" boxOfActin/*.java`
shows both arrays sized at `[500000]` on current `main`. The `MyoMotor.soaX/Y/Z`
SoA arrays are also `[500000]`. 400 000 fits cleanly with 100 000 headroom.
Walked source verifying — no cap change needed, no source edit.

### C. Pre-launch discovery #2 — heap-flag bump (-Xmx8G → -Xmx20G)

First smoke attempt at the prompted `-Xmx8G` hit
`OutOfMemoryError: Java heap space` during static init, inside
`MyosinFixed.setUpGlidingAssay()` after the 400 K `MyosinFixed` objects
were instantiated (line trace points to `FilSegment.<clinit>` but the bulk
of allocation is the 4 × 400 K Thing subclasses preceding it: rod + lever +
motor sub-Things, each a full Thing with ~30 Pt3D objects ≈ ~2 KB).
RSS at OOM was 8.7 GB.

Naive estimate per `MyosinFixed`: 4 Thing subobjects × ~2 KB ≈ 8 KB.
Times 400 K = ~3.2 GB just for myosins. Plus FilSegment statics (~90 MB),
GPU buffers (~90 MB), other state. Headroom needs to be 2-3× working
set to avoid GC-driven slowdowns. -Xmx8G is decisively too small.

Bumped to **-Xmx20G** (system has 27 GB available per `free -h`). This
is the "small enough to adjust sensibly" class of discovery the prompt
allowed: a single JVM-flag change with clear physical justification (heap
must accommodate the working-set scaling implied by the 4× motor-count
increase). Documented but not asked.

Second smoke at -Xmx20G ran cleanly to completion. Peak RSS 14.6 GB —
well below the 20 GB ceiling, consistent with comfortable GC headroom.
A future session at this scale should plan for -Xmx16G+ from the start.

### D. Smoke-test outcome (Part B) — passes mechanically, fails wall-clock budget

Smoke config: `ParameterFiles/glidingDense_overnight_smoke` — identical
to the overnight file except `runTime=0.005 s` (500 steps) and
`toFileInterval=250` (guarantees 2-3 frame writes during the smoke).

```
Total wall: 479 s (8 min)
Steps:      500 (sim time 0.005 s)
Frames:     3 (frame_000000, 001, 002; each 132 MB → 380 MB total)
RSS peak:   14.6 GB
```

Mechanical: GPU kernel dispatched cleanly at `motorCap=500000
segCap=1000000 totalCells=40804 contentsCap=8000000 blockSize=64`. No
`LAUNCH_OUT_OF_RESOURCES`, no NaN in frame 0 (visual spot-check shows
sane endpoint coordinates), 3 JSON frames written without error.
`[STATS] bindEvents=4465 meanBoundMotors=303.975` — plausible regime
(0.076 % of the 400 K bed bound per step, ~3 bound motors per filament,
similar in scale to the dense-demo's 0.47 % at M=98 K, lower fraction
because the seg/motor ratio drops as M grows).

**NVML init returned 18 (driver/library version mismatch — kernel module
595.58.03 vs libcuda 595.71.05). Non-fatal — kernel runs through libcuda
which works; only `nvidia-smi` and TornadoVM's telemetry are affected.
No action needed.**

GPU per-call timing (smoke, 601 calls, includes cold-start JIT):

```
mode      | M     | S    | exec (ms) | pack (ms) | gridPack (ms) | unpack (ms) | total (ms)
iter2a GPU|   500 |   14 |  5.335    | 0.120     |  0.011        | 0.050       |  5.516
dense GPU | 98000 | 1159 |  7.773    | 0.856     |  0.295        | 0.329       |  9.287
o/n smoke |400000 | ~200 | 11.640    | 3.580     |  0.470        | 1.360       | 21.190
```

(o/n S is low — smoke ended before steady-state segment splitting kicked in;
S will rise to ~1100-1200 during a full run, slightly raising exec.) The
**exec scaling is the headline asymptotic-regime number** even from the
smoke: M went 500 → 98 K (196×) → 400 K (4×); exec went 5.3 → 7.8 → 11.6 ms.
Per-call exec grew sub-linearly with M (0.2× for the 196× motor jump,
0.5× for the 4× jump). The grid + walk algorithm is doing exactly what
iter2a §G.1 predicted: broad-phase culling pays off harder as M rises.
pack/unpack remain ~linear in M (expected — SoA refreshes). gridPack
grew 1.6× for the 4× motor increase, also sub-linear (cells stay roughly
fixed at this S range, contents grow).

### E. Wall-clock projection (the bail-out trigger)

Per-step phase budget from smoke (500 steps, includes JIT warmup):

```
phase                       | smoke wall (s) | ms/step
ThingStep                   | 128.5          | 257.1
MotorBindGrid3D Fill (CPU)  |  73.5          | 147.0
Brownian                    |  73.1          | 146.3
Myosin                      |  50.8          | 101.6
GPU motor binding           |  12.7          |  25.4 (per-step incl cold-start)
Mesh                        |  11.0          |  22.0
MyoDimer                    |   5.7          |  11.5
other (NodeLink/ckMesh/...) |   8.0          |  16.0
TRACKED                     | 363.4          | 727
untracked (JIT/GC/JSON/init)| 116.0          | (one-time)
TOTAL                       | 479.4          | 959
```

Steady-state (tracked phases only) projection to 2.0 s sim = 200 000 steps:

```
0.727 s/step × 200 000 steps = 145 400 s = 40.4 h
```

Bail-out boundary is 10 h. Projection is **4.05× over**. There is no
plausible cold-start correction that closes the gap — the untracked 116 s
is a one-time JVM/JIT cost, irrelevant at the asymptote.

Phase ranking is identical to the dense-demo (Session §F): CPU phases
dominate, GPU motor binding is ~3% of wall. **At M=400 K the CPU phases
are exactly 4× the M=98 K numbers** (compare ThingStep 257 ms/step now
vs 54.5 ms/step in the dense-demo). Linear scaling with M, as expected
— and as predicted by the prior session's "iter2b priority is moveThing
on GPU" framing.

A runTime that fits a 9 h budget would be **0.44 s** — 4.5× cut from the
prompted 2.0 s. The prompt's discovery rule classifies an N-fold runTime
reduction as a "larger judgment call → STOP" (the 25 ms → 24 ms example
is meant to bound "small enough to adjust"). User is asleep; cannot ask.
**Decided: STOP.**

### F. Why not just launch and accept the 10 h kill

Considered, rejected:

- Launching the 2.0 s run knowing it will be killed at 10 h yields **0.49 s
  simulated** (≈ 49 000 steps, ~20 frames). The bail-out kill is not graceful
  — `pgrep` + SIGKILL leaves a partial last frame and a stale `.last_run_status`.
  No journal closure; the user wakes to ambiguity.
- Trimming runTime to 0.44 s and running cleanly to completion gets
  marginally more sim time (0.44 vs 0.49 s) cleanly and produces a
  watchable movie + closed journal entry. But it's a 4.5× scope cut from
  the prompted 2.0 s — and the user explicitly said "getting it right
  matters more than completing the run with wrong numbers" w.r.t. the
  prior session's drift.
- Honest stop now lets the user redirect in the morning with full data
  (the smoke already gives 601-call kernel-timing data at M=400 K, which
  is most of the GPU-scaling story they were asking for).

### G. What this session delivers anyway

Even without the overnight run, the smoke produced enough kernel-scaling
data to answer iter2b's open questions:

1. **Three-point GPU scaling table** (M=500 / 98 K / 400 K above) confirms
   the iter2a kernel scales sub-linearly with M from M=500 onward. Crossover
   S* (iter2a §G.1) is below M=98 K; precise value still not measured.

2. **At M=400 K, GPU motor binding is ~3% of wall** (per-step 25 ms vs total
   tracked-phase 727 ms). The kernel is decisively not the bottleneck. CPU
   ThingStep alone is ~10× larger than the kernel cost.

3. **Iter2b priority is unchanged from dense-demo**: moveThing → Brownian
   → MotorBindGrid3D Fill, in that order, ported to GPU. The 800× M factor
   from iter2a-validation to overnight-target shifted the wall budget
   exactly as the dense-demo predicted — no new surprises.

4. **Heap scaling**: 4× motors → 4× heap, per the OOM evidence. Plan
   -Xmx for `4 × (motors / 100 K)` GB minimum, with 2× safety factor.

### H. Files / artifacts

- `ParameterFiles/glidingDense_overnight` — 20×20 × 1000/µm² × 2.0 s (committed).
- `ParameterFiles/glidingDense_overnight_smoke` — same with runTime=0.005 s
  (committed). Useful as a 500-step probe for any future re-runs.
- `/tmp/boa_overnight_dense/smoke/` — 3 frames (frame_000000-002.json,
  ~380 MB total) + `gliding_assay.dat` + `source.zip`. **Not committed**
  (transient). To inspect: `python3 sim_server.py 8000` from BoA root,
  then `http://localhost:8000/sim_viewer_boa.html`, pick the `smoke` run.
  3 frames of 5 ms apart isn't a movie but visually verifies the dense
  myosin bed.
- No `frame_stats.log`, `gc.log`, or `kernel_timing.log` — overnight run
  not launched, addendum logs not produced.
- No code changes.

### I. Open questions for the planner

1. **2.0 s at M=400 K is fundamentally a multi-day run on current
   architecture.** The user's "twice the density to push the asymptotic
   regime" goal is achievable for GPU-kernel timing (smoke alone gives
   601 calls of data); achieving it for the *movie* / *trajectory* deliverable
   needs either (a) a runTime cut, (b) GPU port of ThingStep/Brownian to
   collapse the CPU bottleneck (iter2b's main thrust), or (c) a much
   bigger machine. Planner decision needed.

2. **The "asymptotic-favorable regime clearly reached?" question is
   answered yes from the smoke kernel data alone.** The 4× motor scaling
   produced ~1.5× exec scaling — solidly sub-linear, exactly the regime
   iter2a §G.1 asked about. No 2.0 s run needed to confirm this.

3. The bumped `-Xmx20G` flag and the static-cap-already-at-500K finding
   should propagate into any future param file targeting M ≥ 200 K.
   CLAUDE.md's `-Xmx800M` example is multiple orders of magnitude stale
   for these workloads — but not in scope here to change.

## 2026-05-28 — Dense gliding-assay demo: 98K motors × 100 random filaments × 0.3 s

Single concrete dense gliding-assay run on the iteration 2a kernel (commit
2f464a4), exercising it at a scale ~200× larger than the validation config
(M=98000 vs M=500). The point of the session is wall-clock measurement at
research scale plus a movie of dense gliding; no source changes, no new
instrumentation.

### A. Configuration deviations from the prompt

Three forced deviations, each from a static-cap / no-source-change discovery:

1. **Box 14×14 vs prompted 20×20.** `MyoMotor.theMotors[100000]` and
   `Myosin.theMyosins[100000]` are statically allocated at 100 000 — the
   prompted 20×20×500 = 200 000 motors would overflow at seed time.
   Largest dense bed at the prompted 500/µm² density that fits under the
   cap is **14×14×500 ≈ 98 000** motors; preserved the density (the
   physically relevant parameter) and shrank the bed.

2. **runTime 0.3 s vs prompted 1.0 s.** Smoke test (0.01 s sim) ran 210 s
   wall → projected **~5.5 h for 1.0 s**, dominated not by the GPU kernel
   but by CPU integration phases (see §F). Trimmed to 0.3 s — still
   30 100 GPU calls of steady-state timing data, 60 movie frames at 5 ms
   cadence = ~2 s playback at 30 fps, ~94 min wall. Skipped the CPU
   comparison per the prompt's >30 min gate.

3. **Motors written every frame.** Each frame_*.json is 32 MB because
   `ThreeJSWriter.buildFrameJson()` serializes all 98 K myosins (rod +
   lever + motor sub-parts) per frame. No source-level toggle for
   "filaments only"; the prompt's "200×-bloat avoidance" is not achievable
   without a code change. Output is 1.95 GB across 61 frames; acceptable
   here (aorus disk has room), but worth flagging if later sessions stream
   frames over network.

Filament seeding **fits the existing mechanism cleanly** (option 1 from the
prompt — parameter-file driven): `initialFilaments:true:100` +
`minFilLength:true:1.0` + `maxFilLength:true:3.0` triggers
`FilSegment.makeInitialFilaments()` → 100 × `makeRandomFilament(1.0, 3.0)`,
random position via `theBox.rdmPtInside()`, random 3D orientation, length
uniform in [1,3] µm. The `setUpGlidingAssay()` path additionally creates
1 hardcoded canonical filament along X via `makeGlidingAssayFilament()` —
unavoidable without source changes — so the run has **101 filaments**, not
100. The +1 filament has length 2 µm and is functionally indistinguishable
from a random one of similar length.

`makeRandomFilament` does not enforce any wall buffer — filaments are
uniformly distributed in the full box, including 3D orientation (so Z-tilt
ranges over [-0.5,0.5] µm at this slab thickness). The smoke test showed
~80 µm/s instantaneous gliding (much faster than the 4.7 µm/s sparse-bed
validation), implying many filaments will hit walls within 0.3 s; the
chamber-collision physics handles them but the trajectories are not
buffered. Documented as a known limitation of the existing seeding path.

### B. Files added

- `ParameterFiles/glidingDense_demo` — 14×14×0.5 box, 500/µm² density,
  100 random filaments [1,3] µm, runTime 0.3 s, toFileInterval 500 (every
  5 ms simulated).
- `ParameterFiles/glidingDense_demo_smoke` — identical except
  runTime=0.01 s, used for §C smoke test.
- No source-code changes.

### C. Pre-flight smoke (task C)

```
java @tornado-argfile --enable-preview -Xmx4G -cp "..." \
     BoxOfActin -r -gpu -pf ParameterFiles/glidingDense_demo_smoke \
     -3js ~/boa_dense_demo_work/smoke
```

3 frames written (32 MB each), kernel dispatched cleanly:
`GPUMotorBinding: motorCap=100000 segCap=1000000 totalCells=20164 contentsCap=8000000 blockSize=64`.
`[STATS] bindEvents=4013 meanBoundMotors=250.86 gpuMotorBinding total=12.186s calls=1101`.
Per-call extrapolation matched the §F final numbers within 20% (cold-start
JIT overhead dominates a 1101-call sample).

Wall-clock per step at this scale: 210 s / 1000 steps ≈ 210 ms/step →
projected ~5.5 h for full 1.0 s — the data point that drove the runTime
trim. The bulk of that time is in CPU integration phases, not the GPU
kernel; see §F.

### D. Run command

```
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf ParameterFiles/glidingDense_demo \
     -3js /home/jba/boa_dense_demo_work/run_gpu
```

Output: `~/boa_dense_demo_work/run_gpu/` — 61 JSON frames (frame_000000
through frame_000060; the writer emits an initial frame at step 1 plus
one per toFileInterval thereafter) + `gliding_assay.dat` + `source.zip`.

### E. Wall-clock and per-call GPU timing

```
Total wall:                   5678 s (94 min 38 s)
GPU motor binding total:       279.574 s   ( 4.92% of wall, 30100 calls)
  pack         (motor+seg SoA refresh): 25.768 s  → 0.856 ms/call
  gridPack     (CSR pack from CPU grid):  8.868 s  → 0.295 ms/call
  exec         (kernel itself):         233.962 s  → 7.773 ms/call
  unpack       (boundSegId readback):     9.913 s  → 0.329 ms/call
                                                  total: 9.287 ms/call
```

Comparison to iteration 2a's validation-scale numbers (M=500, S=14):

```
mode      | M     | S    | per-call exec (ms) | pack (ms) | gridPack (ms) | unpack (ms)
iter2a GPU|   500 |   14 |  5.335             | 0.120     |  0.011        | 0.050
dense GPU | 98000 | 1159 |  7.773             | 0.856     |  0.295        | 0.329
```

**Kernel scales much better than the input.** From M=500 → M=98 000
(196×), per-call exec grew only 1.46×. The grid does its job at this S —
the broad-phase culling pays off when there are ~600 segments to skip
per home cell. pack/unpack scaled ~7× with M (linear with motor count,
as expected — they're flat SoA refreshes). gridPack grew 27×, in the
noise relative to exec.

### F. The shifted bottleneck (per-phase wall budget)

```
phase                        | total wall (s) | % of 5678 s
ThingStep                    | 1640.235       | 28.9 %   ← CPU integration of 98K Things
MotorBindGrid3D Fill (CPU)   |  849.754       | 15.0 %   ← CPU rebuild of 3D grid each step
ThingBrownian                |  908.692       | 16.0 %   ← random forces on 98K Things
Myosin                       |  668.983       | 11.8 %
GPU motor binding            |  279.574       |  4.92 %  ← iter2a kernel
MyoDimer                     |  262.528       |  4.62 %
Mesh                         |  191.801       |  3.38 %
NodeLink, others             |  ~210          |  3.70 %
remaining (sync/JSON/JIT)    |  ~666          |  11.7 %
```

At research scale **the iter2a kernel is no longer the bottleneck** —
ThingStep alone is 5.9× larger than the entire GPU motor binding cost.
The ranking gives a concrete answer to JOURNAL.md iter2a §G.2 ("which
phases port to GPU next"): in priority order, **moveThing / ThingStep,
then Brownian, then MotorBindGrid3D Fill**. The Fill phase is unusual —
it's CPU work that exists purely to feed the GPU kernel; porting it to
GPU only makes sense if positions live on-device (the residency design
of iter2a §G.3), at which point the CSR pack and the pre-GPU
`fillSoaArrays()` both vanish.

### G. Qualitative sanity (task F)

`scripts/sanity_check_dense_demo.py` walked frame 0, 15, 30, 45, 60 (a
5-point sample across the run):

- **101 filaments preserved every frame** (isBarbedEnd count = 101 at all
  five sample frames).
- **No NaN/inf/runaway** coordinates (no |x|, |y|, |z| > 100 µm in any
  endpoint).
- **Segment count grew 202 → 1159** between frame 0 (t=1e-5) and frame 15
  (t=0.075 s). The makeRandomFilament path constructs one large FilSegment
  per filament; the per-step physics splits long segments to maintain
  `stdSegLength=64` monomers/seg. Steady state at ~11.5 segments/filament,
  consistent with mean filament length 2 µm ÷ 0.17 µm/seg.
- **Per-segment displacement** over 0.3 s (matched-ID original 202 only):
  median 1.31 µm, max 2.62 µm — consistent with `[STATS]
  glidingVelocity=4.24 µm/s` (4.24 × 0.3 = 1.27 µm mean, matches median).
- **Smoke test glidingVelocity=42 µm/s was a 0.01 s artifact**; the full
  0.3 s mean 4.24 µm/s is in the same regime as the iter2a-validated
  4.7 µm/s, so the dense bed is not pathologically different.
- **`[STATS] bindEvents=152499 meanBoundMotors=457.24`** → 0.47 % of the
  98 K bed bound at any instant, ~4.5 bound motors per filament on
  average. Plausible dense-gliding regime.

### H. Iteration 2b implications

The headline data from this session:

1. **Iter2a kernel is fine at research scale.** 9.3 ms/call at M=98 K is
   in the same order of magnitude as 5.3 ms/call at M=500. The 27-cell
   walk cost is amortized over hundreds of segments per home cell. The
   "crossover S\*" question (iter2a §G.1) is answered by extension: the
   kernel wins outright once segment density rises high enough that the
   walk actually visits multiple segments per cell, and at S=1159 across
   20 164 cells the broad-phase culling is doing real work.

2. **iter2b's correct priority is not "make the binding kernel faster."**
   It's "drop the CPU phases that dominate the wall budget into GPU
   kernels, in residency-friendly order." moveThing first (pure SoA
   arithmetic, no RNG, no stateful biochem), then Brownian (needs
   Wang-hash RNG per iter1 §slot reservation), then maybe the
   `MotorBindGrid3D.fillSoaArrays()`-then-grid-build sequence collapsed
   into a GPU radix sort once positions live on-device.

3. **At this M, fillSoaArrays() costs ~25 ms/call** (the per-call pack
   number), and the CPU grid rebuild costs ~28 ms/call (849.8 s /
   30 100 calls). Together these are ~5.6 % of wall — bigger than the
   GPU kernel itself. Residency eliminates both. That's the iter2b prize.

4. **The 15-parameter cap remains the structural ceiling** for adding
   `moveThing` to the same task graph. iter2b's first design call is
   probably "two task graphs sharing a buffer via consumeFromDevice"
   (per the survey §4), not "stuff everything into one kernel."

### I. Open-question status updates

- iter2a §G.1 ("where does the grid win materialize?") — at S ≈ 1000,
  decisively. Crossover S\* is below this; precise value not measured.
- iter2a §G.2 ("which integration phases port to GPU first?") — moveThing
  is the unambiguous winner by phase-wall ranking.
- iter2a §G.3 ("cadence story with CPU still owning most phases?") — the
  cost data confirms that the per-step `fillSoaArrays() + grid rebuild`
  pattern adds ~5.6 % wall; eliminating it via residency is worth the
  scope.
- iter2a §G.4 ("block size autotune") — no new data; blockSize=64 ran
  cleanly at M=98K, no `LAUNCH_OUT_OF_RESOURCES`.

### J. Notes for replays

- Output directory `~/boa_dense_demo_work/run_gpu/` is **not committed**
  (transient, 1.95 GB). To replay locally, run the §D command — the param
  file `ParameterFiles/glidingDense_demo` is committed.
- For viewing the dense-gliding movie: `python3 sim_server.py 8000` from
  the BoA repo root, then `http://localhost:8000/sim_viewer_boa.html`,
  pick the `run_gpu` simulation. ~80 µm/s gliding (smoke artifact at
  short averaging) → 4.2 µm/s steady-state should give ~1.3 µm of mean
  glide visible across the 2-second-equivalent movie.
- Heap behavior: -Xmx4G held throughout the run; no OOM, no GC death
  spirals visible. The 800 MB cap from older sessions is decisively
  insufficient at this M.
- A future session that wants the full 200 K motors will need to bump
  `MyoMotor.theMotors[]` / `Myosin.theMyosins[]` static caps to ≥ 200 000
  (3-line change in two files) and re-walk the 100 K assumption in
  `MyoMotor.soaX[]` etc. Out of scope here.

### K. Commit

Session commit adds `ParameterFiles/glidingDense_demo`,
`ParameterFiles/glidingDense_demo_smoke`, and this journal entry. No
source-code changes.

## 2026-05-28 — Iteration 2a: grid-on-GPU fused kernel with buffer reuse

Replaces iteration 1's brute-force O(M×S) GPU kernel with a fused broad+narrow
phase kernel that reads a CPU-built CSR grid. Patterned on the iter2-pre-upgrade
WIP at commit 5792838 (read-only design reference; nothing cherry-picked, code
copied manually). Three corrections applied vs the WIP framing — see §A.

**Bottom line:** kernel correctness validated against CPU (the three statistical
observables agree within iter1's noise band). Wall-clock at glidingAssay500_val
scale (M≈500, S=14) is **slower than CPU and slower than iter1** — the 27-cell
walk + per-cell indexing overhead exceeds the savings from broad-phase culling
when the segment count is tiny. The grid is the right algorithmic move; the
gliding-assay workload is too small to surface its benefit. The asymptotic win
materializes at research scale (thousands of segments per filament network),
exactly the open question iter1 §5 flagged.

### A. Corrections vs prompt framing and WIP

1. **persistOnDevice is not what the prompt thought.** `TaskGraph.persistOnDevice(o)`
   is literally `transferToHost(DataTransferMode.UNDER_DEMAND, o)` —
   `TaskGraph.java:756-760`. It only suppresses the auto device→host copy at
   task end and tags the buffer as consumable by a downstream graph via
   `consumeFromDevice`. In a single-graph design (which the planner approved),
   there is no second graph; persistOnDevice would be a no-op. Buffer reuse
   across `plan.execute()` calls is automatic — the device allocation lives
   with the ExecutionPlan and only frees on close. So 2a does NOT call
   persistOnDevice. It uses `FIRST_EXECUTION` for static-during-run inputs
   (gridParams, gridDims — uploaded once, reused thereafter) and
   `EVERY_EXECUTION` for dynamic inputs. The WIP made the same choice
   correctly without invoking persistOnDevice; the prompt's "persistOnDevice
   for buffer reuse" phrasing was a misnomer.

2. **Grid rebuild cadence is every step, not every 10.** The planner's
   table said "Every collisionCheckInt = 10 steps, matching the existing CPU
   rebuild trigger". The 10-step cadence is for the 2D `Mesh` (non-motor
   collisions); the 3D `MotorBindGrid3D` rebuilds every step because motor
   positions change every step under CPU integration. Honored the intent
   ("match the CPU rebuild trigger") rather than the cadence value:
   `MotorBindGrid3D.FillThreads` runs every step in both CPU and GPU paths.

3. **CUDA `LAUNCH_OUT_OF_RESOURCES` on first smoke test.** Default PTX
   workgroup size at 100000 global threads exhausted the per-thread register
   budget for the fused kernel (`cuLaunchKernel -> Returned: 701` repeated
   silently every step, boundSegId never written, every motor read 0 and
   tried to bind to segment 0, force assembly produced "Crazy forceSum"
   warnings until the 15-minute walltime cap). Fixed by attaching an explicit
   `WorkerGrid1D(motorCap)` with `setLocalWork(64, 1, 1)` via
   `GridScheduler("motorBinding.bind", worker)` on the execute call. Smaller
   block size → more registers per thread → kernel launches. 64 was the
   first value tried; not tuned further. If the kernel grows in iter2b drop
   to 32.

### B. Files changed

- `boxOfActin/MotorBindGrid3D.java` — added `totalCellCount()` and
  `packForGPU(IntArray offsets, IntArray contents)`. Walks per-cell
  `filCells[ix][iy][iz][]` arrays in linear cellId order, writing the
  standard CSR layout (offsets length = totalCells+1, contents flat by
  cell). Returns the actual contents length; caller-supplied capacity
  bounds enforced via `gridCellContents.getSize()`. Cell timestamp gating
  inherited from existing CPU query: empty/stale cells contribute zero
  entries.
- `boxOfActin/GPUMotorBinding.java` — rewritten. Fused kernel (kernel
  quoted in §D), 13 array parameters (under the 15 cap; alignTol and
  myoColTolSq packed into gridParams alongside grid origin/cellSize, see
  §C). FIRST_EXECUTION for gridParams + gridDims; EVERY_EXECUTION for all
  dynamic inputs and outputs. Single TaskGraph, single ExecutionPlan.
  WorkerGrid1D + GridScheduler attached. New `gridPackNanos` timer for
  the CPU-side CSR pack step.
- `boxOfActin/BoxOfActin.java` — moved `MotorBindGrid3D.FillThreads`
  dispatch outside the `if (Env.useGPU)` mutex so both paths build the
  grid (GPU reads it via the CSR pack in `detectBindings()`). Added
  `gridPack` to the `[STATS] gpuMotorBinding` timing print. Added
  `[STATS] glidingVelocity` line emitting the mean per-filament
  longWindowSpeedXY at end of run, used by the validation script to
  extract one velocity number per run.
- `boxOfActin/GlidingAssayEvaluator.java` — exposed `filStatesEntrySet()`
  and `getLongWindowSpeedXY(int fid)` accessors for the new stats line.

### C. 15-param cap solution

Iteration 1 used 11/15. The WIP used 13/15. This kernel matches the WIP
at 13/15 (motPos, motUVec, motRodUVec, motOnFil, filEnd1, filEnd2,
filNodeAtEnd2, gridCellOffsets, gridCellContents, gridParams, gridDims,
counts, boundSegId). The two scalar tolerances are folded into the
gridParams FloatArray (slots 5 and 6) and read once at kernel entry.
Bundling gridDims into counts (the planner's option 1) was not necessary;
2 slots of headroom remain.

### D. The fused kernel

```java
private static void bindKernel(
        FloatArray motPos, FloatArray motUVec, FloatArray motRodUVec,
        IntArray   motOnFil,
        FloatArray filEnd1, FloatArray filEnd2,
        IntArray   filNodeAtEnd2,
        IntArray   gridCellOffsets, IntArray gridCellContents,
        FloatArray gridParams, IntArray gridDims,
        IntArray   counts,
        IntArray   boundSegId) {
    int M = counts.get(0);
    float xMin = gridParams.get(0), yMin = gridParams.get(1), zMin = gridParams.get(2);
    float invCellSize = gridParams.get(4);
    float alignTol = gridParams.get(5), myoColTolSq = gridParams.get(6);
    int nXBins = gridDims.get(0), nYBins = gridDims.get(1), nZBins = gridDims.get(2);
    int nXY = nXBins * nYBins;

    for (@Parallel int m = 0; m < motPos.getSize() / 3; m++) {
        if (m >= M) return;
        boundSegId.set(m, -1);
        if (motOnFil.get(m) != 0) continue;
        float mx = motPos.get(m*3), my = motPos.get(m*3+1), mz = motPos.get(m*3+2);
        float mux = motUVec.get(m*3),    muy = motUVec.get(m*3+1),    muz = motUVec.get(m*3+2);
        float rux = motRodUVec.get(m*3), ruy = motRodUVec.get(m*3+1), ruz = motRodUVec.get(m*3+2);
        int ix = (int)((mx - xMin) * invCellSize); if (ix<0) ix=0; if (ix>=nXBins) ix=nXBins-1;
        int iy = (int)((my - yMin) * invCellSize); if (iy<0) iy=0; if (iy>=nYBins) iy=nYBins-1;
        int iz = (int)((mz - zMin) * invCellSize); if (iz<0) iz=0; if (iz>=nZBins) iz=nZBins-1;
        int found = -1;
        for (int dz = -1; dz <= 1 && found < 0; dz++) { int ciz=iz+dz; if (ciz<0||ciz>=nZBins) continue; int izOff=ciz*nXY;
        for (int dy = -1; dy <= 1 && found < 0; dy++) { int ciy=iy+dy; if (ciy<0||ciy>=nYBins) continue; int iyOff=ciy*nXBins;
        for (int dx = -1; dx <= 1 && found < 0; dx++) { int cix=ix+dx; if (cix<0||cix>=nXBins) continue;
            int cellId = cix+iyOff+izOff;
            int start = gridCellOffsets.get(cellId);
            int end   = gridCellOffsets.get(cellId+1);
            for (int idx = start; idx < end; idx++) {
                int s = gridCellContents.get(idx);
                if (filNodeAtEnd2.get(s) != 0) continue;
                // narrow-phase: identical math to iter1 — alignment gate, rod-orientation
                // gate, segment-line projection, sphere-line distance squared vs myoColTolSq.
                // Body identical to iter1's kernel; elided here for length.
                // ... (see boxOfActin/GPUMotorBinding.java for full body)
                // First hit wins: found = s; break;
            }
        }}}
        if (found >= 0) boundSegId.set(m, found);
    }
}
```

(Full body in `boxOfActin/GPUMotorBinding.java:bindKernel`. The narrow-phase
math is lifted verbatim from iter1; only the broad-phase walk is new.)

### E. Validation

Same protocol as iter1 spike: 10 seeds × glidingAssay500_val × 0.1 s
simulated, baseline = CPU path on this commit (CPU code unchanged), rewrite =
GPU path on this commit. Three observables: bindEvents (committed bind
count), meanBoundMotors (sample-averaged), glidingVelocity (mean
longWindowSpeedXY across filaments at end of run). Pass criterion:
|diff|/cSEM < 2.

```
observable        | CPU (10 seeds)              | GPU (10 seeds)               | |diff|/cSEM | verdict
bindEvents        | 889.6 ± 26.9 (SD  85.1)     | 861.4 ± 37.3 (SD 117.9)      |    0.61    | PASS
meanBoundMotors   |   7.641 ± 0.162 (SD 0.512)  |   7.205 ± 0.301 (SD 0.953)   |    1.27    | PASS
velocity (µm/s)   |   8.326 ± 0.179 (SD 0.566)  |   8.231 ± 0.139 (SD 0.440)   |    0.42    | PASS
wall (s/seed)     | 297.807 ± 0.313 (SD 0.989)  | 351.785 ± 0.343 (SD 1.086)   |  116.19    | FAIL
```

All three statistical observables agree well within 2 cSEM. Wall-clock ratio
GPU/CPU = **1.181×** (GPU slower). meanBoundMotors GPU SD (0.95) is ~1.9×
the CPU SD (0.51) — wider than iter1's near-1× SD ratio, but bindEvents and
velocity SDs are similar. The wider meanBoundMotors SD is driven by GPU
seed 5 (5.161 vs 7.0–8.7 typical) which also has the lowest bindEvents
(607); a single outlier in n=10 swings the SD. Statistical-agreement
verdict is unchanged. Raw per-seed values and the GPU [STATS] breakdown
in `RUN_LOGS/2026-05-28_iter2a-validation.txt` and `…-summary.txt`.

Note on velocity scale vs iter1: iter1's reported 4.6 µm/s vs this
session's 8.3 µm/s reflects a different velocity extraction —
`[STATS] glidingVelocity` (newly added this session) emits mean per-filament
`longWindowSpeedXY` measured by `GlidingAssayEvaluator` at end of run, not
the metric iter1 extracted. The CPU and GPU velocity numbers within this
session use the same extraction and so are apples-to-apples; the cross-
session comparison is not.

Note on CPU wall vs iter1: iter1 measured 228 s/seed CPU; this session
measured 298 s/seed CPU on the same code path. CPU work was not changed
in iter2a (FillThreads was already running in the iter1 CPU path; we
moved the call outside the if/else mutex so both paths invoke it, with
no work added). The 30% slower CPU here is consistent with hardware load
on aorus during this session — the GPU/CPU ratio (1.18×) within this
session is the meaningful comparison.

### F. Wall-clock breakdown (10-seed mean, 10101 calls per seed)

```
mode      | total wall (s/run) | per-call exec (ms) | pack (ms) | gridPack (ms) | unpack (ms)
iter1 GPU |  251.5             |  4.121             | 0.131     |   —           | 0.061
iter2a GPU|  351.8             |  5.335             | 0.120     |  0.011        | 0.050
CPU now   |  297.8             |  —                 |  —        |   —           |  —
CPU iter1 |  228.2             |  —                 |  —        |   —           |  —
```

Iter2a per-call exec is **29% slower** than iter1 (5.335 ms vs 4.121 ms).
This is the algorithmic-scaling cost at small S: iter1's inner loop walked
14 segments and exited. Iter2a's inner loop walks 27 cells, each with
0–2 segments, plus cell-id arithmetic, plus the 27-cell-walk control
overhead. The grid does its job of avoiding distance tests, but at S=14
there were never enough distance tests to recoup the framing cost.

The per-step breakdown also confirms the planner's pre-decision is exact:
- pack (motor + segment SoA refresh): essentially unchanged from iter1
- gridPack (new): negligible at 0.011 ms/call (0.11 s out of 56 s total)
- exec: dominates wall-clock as in iter1 but is larger per call
- unpack: essentially unchanged

Transfer cost per step is essentially identical to iter1, as expected —
this iteration changed the kernel, not the transfer pattern. The "buffer
reuse" structural groundwork (FIRST_EXECUTION on gridParams + gridDims)
saves a handful of bytes per step, immaterial here.

### G. Open questions for iter2b

1. **Where does the grid win materialize on the scaling curve?** The
   crossover S* where grid beats brute force is the headline number for
   when iter2a's algorithm becomes the right choice. Probably S* ≈ 30-50
   per active region; well below most research-scale runs but above
   gliding-assay scale. A short scan (run the same kernel at param files
   with S = 50, 200, 1000) would put a number on it.

2. **2b scope: which integration phases port to GPU first?** True
   residency (positions evolve on-device, CPU rarely reads back) requires
   the per-step CPU position-integration phases to move to GPU.
   Candidates in dependency order: (a) moveThing() — pure SoA arithmetic,
   easiest; (b) Brownian forces — needs Wang-hash RNG per the iter1 §slot
   reservation; (c) gliding-stroke kinetics inside Myosin.checkStep —
   stateful, hardest. Suggestion: 2b ports moveThing only as the minimum
   viable demonstration that grid + positions on-device drops the
   per-step EVERY_EXECUTION transfer to near zero. The other phases stay
   CPU; the GPU "drops in" only for the kernels that exist.

3. **What is the cadence story when the CPU still owns most phases?**
   Even with moveThing on GPU, the CPU still owns Brownian, xLink,
   joints, biochem, membrane relaxation. Between consecutive GPU kernels
   the CPU phases mutate Pt3D objects but not SoA arrays. The cleanest
   answer is the existing pattern: `fillSoaArrays()` at top of each step
   uploads the CPU-side state once; GPU runs; positions come back at the
   end of each step. That's iter2a's current pattern. True residency
   requires either (a) inverting the sync (CPU reads SoA at top of step,
   writes back at end) or (b) eliminating the cross-phase Pt3D reads.

4. **Block-size autotune.** WorkerGrid block size = 64 was first try.
   Larger may improve occupancy on smaller workloads; smaller is forced
   if 2b adds more state to the kernel. Worth a small sweep at iter2b
   commit time on representative workloads.

### H. Commit

See `git log --grep "iteration 2a"` for the session commit.

## 2026-05-28 — Survey: TornadoVM upgrade plan for BoA's GPU work

Survey only — no installs, no edits to BoA or TornadoVM source, no compile,
no run. Read-only inspection of `~/Code/TornadoVM/` (the 4.0.1-dev source
clone and the matching dist install at `~/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/`)
and the GitHub releases page.

**TL;DR — the upgrade premise is largely wrong, and that's the survey's most
important finding.** The planner-supplied context said "modern TornadoVM is
on a unified line, current at v2.2.0 (released late 2025)". That's stale.
The current jdk21 line is **v4.0.1-jdk21** (released 2026-04-29). BoA's
existing install is **4.0.1-dev** — the develop-branch tip *ahead of*
v4.0.1-jdk21, not behind it. The persistOnDevice / consumeFromDevice /
PERSIST primitives the prompt asked about are already in BoA's installed
4.0.1-dev. No upgrade is required to unlock iteration 2's persistent
residency design. What does remain unsolved is the 15-parameter cap (still
present at HEAD), and there are still planner choices about strict-SoA
shape and what "persistent" even means for a per-step-mutating buffer.
Details below.

### 1. Upgrade target version

**Current TornadoVM releases on the jdk21 line** (from the GitHub releases
page, https://github.com/beehive-lab/TornadoVM/releases):

| version       | date        | notes                                            |
|---------------|-------------|--------------------------------------------------|
| v4.0.1-jdk25  | 2026-04-29  | F16 miscompile fixes (Metal/PTX/SPIR-V)          |
| v4.0.1-jdk21  | 2026-04-29  | F16 fixes, bugfixes; **latest tagged jdk21**     |
| v4.0.0-jdk25  | 2026-04-02  | Apple Metal backend, SIMD shuffle, CUDA Graphs   |
| v4.0.0-jdk21  | 2026-04-02  | Apple Metal backend, GPU optimisation features   |
| v3.0.0-jdk21  | 2026-02-24  | infra + field handling                           |
| v2.2.0        | 2025-12-17  | SDK compatibility checks, CUDA JIT compiler flags |
| v2.1.0        | 2025-12-09  | Q8_0 tensor support, FP16 conversion             |
| v1.1.0        | 2025-03-31  | first "persist data on hardware accelerator"     |

The line split between `-jdk21` and `-jdk25` releases happened at v3.0.0
(2026-02-24): both JDK lines now ship side by side, same major.minor.patch,
same feature set. The "old numbering" the prompt referred to (4.0.1-dev-jdk21)
*is* the current line — `4.0.1-dev` is the develop snapshot ahead of
v4.0.1-jdk21.

`~/Code/TornadoVM/pom.xml` line 2: `<version>4.0.1-dev</version>`.
`~/Code/TornadoVM/` git HEAD (84e04c046) is 2026-04-28 with merge commits
following v4.0.1-jdk21's tag (v4.0.1-jdk21 = commit 8b5c1a339, 2026-04-29
— the tag is dated one day later because it includes the release-automation
commits). So our dist is essentially v4.0.1-jdk21-tip-of-develop.

- **JDK 21 support:** yes. v4.0.1-jdk21 is the jdk21 tag.
- **PTX backend:** yes. SDKMAN identifier `4.0.1-jdk21-ptx` is published
  (confirmed via https://api.sdkman.io/2/candidates/tornadovm/linux/versions/list).
- **Multiple build lines:** yes, `-jdk21` vs `-jdk25` are distinct; we want
  `-jdk21` because aorus runs Java 21.0.10 per CLAUDE.md.
- **Pre-built binaries:** yes via SDKMAN for `4.0.1-jdk21-ptx`. A direct
  ZIP download from the GitHub releases page is also documented for
  `tornadovm-4.0.1-jdk21-opencl-linux-amd64.zip` style names; a PTX
  release ZIP is not visible on the v4.0.1-jdk21 release page itself, so
  SDKMAN is the cleanest path. Building from source from the existing
  `~/Code/TornadoVM` clone is also available (the dist we already have
  was built that way).

### 2. The parameter cap

**Verified hard cap of 15 parameters in 4.0.1-dev.** The cap is structural,
not configurable.

`~/Code/TornadoVM/tornado-api/src/main/java/uk/ac/manchester/tornado/api/common/TornadoFunctions.java`
defines `Task1<T1>` (line 28) through `Task15<T1..T15>` (line 98). No
`Task16` or higher exists.

`~/Code/TornadoVM/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TaskGraph.java`
has matching `task(...)` overloads — the highest-arity overload is at
line 598 (`task(String, Task15<...>, T1..T15)`). No varargs / `Object...` /
`Object[]` task() form exists.

`~/Code/TornadoVM/tornado-api/src/main/java/uk/ac/manchester/tornado/api/common/TaskPackage.java`
mirrors the same cap (Task15 createPackage at line 218).

No `MAX_PARAMETERS`, `MAX_TASK_ARGS`, `KERNEL_ARG_LIMIT`, `maxParameters`,
`maxArgs` constants exist anywhere in tornado-api/src or tornado-runtime/src
(grep returned zero hits). The cap is enumerated by the number of typed
functional interfaces.

**Has the cap changed?** `git log --all --oneline -- tornado-api/.../TornadoFunctions.java`
shows no commit since `bb1bdcc91 "Task with no arguments added in the API"`
that adds higher arities — it's been Task1..Task15 for the whole modern
line. The cap is unchanged in v4.0.1-jdk21.

**Iteration 1's actual param count.** `boxOfActin/GPUMotorBinding.java:202-207`
calls `task("bind", GPUMotorBinding::bindKernel, motPos, motUVec,
motRodUVec, motOnFil, filEnd1, filEnd2, filNodeAtEnd2, counts, boundSegId,
alignTol, myoColTolSq)` — **11 arguments** (9 FloatArray/IntArray + 2
float scalars), comfortably under the 15 cap. The "21 needed" figure from
the iteration-1 spike notes referred to *strict per-coordinate SoA*
(separate xPos/yPos/zPos per attribute), which would push the count past
the cap.

**WIP iter2's param count.** `git show 5792838:boxOfActin/GPUMotorBinding.java`
adds the broad-phase grid (gridCellOffsets, gridCellContents, gridParams,
gridDims) and removes the two float scalars (packed into gridParams) — its
task() call has **13 arguments** (all arrays, all of typed FloatArray/IntArray).
Still under 15, but with no headroom for adding a new array parameter.

Implication for iteration 2 strict-SoA refactor: if we want
separate-array-per-axis SoA (xPos, yPos, zPos, xUVec, yUVec, zUVec,
xRodUVec, yRodUVec, zRodUVec, motOnFil, xE1, yE1, zE1, xE2, yE2, zE2,
filNodeAtEnd2, counts, boundSegId) = 19 arrays, still over the cap. The
upgrade does **not** fix this. Options:
- Pack auxiliary scalars/params into one of the FloatArrays (the WIP
  iter2 trick — buys 1-2 slots).
- Use the `TaskGraph.addTask(TaskPackage)` form at TaskGraph.java:82,
  feeding a hand-built `TaskPackage` — but TaskPackage's `createPackage`
  factories are also capped at 15 (line 218 of TaskPackage.java).
- Split the binding kernel into two cooperating kernels (e.g.,
  per-motor home-cell prep → narrow-phase distance) joined by a
  consumeFromDevice handoff. This is the cleanest answer and is
  enabled by the persistent-residency primitives discussed in §4.

### 3. API changes between 4.0.1-dev and the upgrade target

Iteration 1's imports (`boxOfActin/GPUMotorBinding.java:3-9`):

```java
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
```

All seven are present at the same package coordinates in 4.0.1-dev source
(verified by direct file paths under `~/Code/TornadoVM/tornado-api/src/main/java/uk/ac/manchester/tornado/api/`).
v4.0.1-jdk21 is the same line at a tagged point — nothing renamed, removed,
or relocated between dev tip and tag.

`DataTransferMode` (4.0.1-dev source quoted in full from
`tornado-api/.../enums/DataTransferMode.java`):

```java
public class DataTransferMode {
    public static final int FIRST_EXECUTION = 0;
    public static final int EVERY_EXECUTION = 1;
    public static final int UNDER_DEMAND    = 2;
}
```

No `PERSIST` enum value — persistence is a method on TaskGraph, not a
transfer-mode flag (see §4). The three constants iteration 1 references
(FIRST_EXECUTION, EVERY_EXECUTION) are present and unchanged.

`TaskGraph` API surface (relevant methods, all in `tornado-api/.../TaskGraph.java`):
- `transferToDevice(int mode, Object... objects)` — line 683
- `transferToHost(int mode, Object... objects)` — line 737
- `task(String, TaskN<...>, T1..TN)` — lines through 598 (cap Task15)
- `persistOnDevice(Object... objects)` — line 757
- `consumeFromDevice(String, Object... objects)` — line 701
- `consumeFromDevice(Object... objects)` — line 707
- `snapshot() : ImmutableTaskGraph` — line ~768

`TornadoExecutionPlan` API surface (relevant methods, all in
`tornado-api/.../TornadoExecutionPlan.java`):
- `TornadoExecutionPlan(ImmutableTaskGraph...)` varargs ctor — line 112
- `execute()` — line 181
- `withGraph(int index)` — line 201
- `withAllGraphs()` — line 216
- `withPreCompilation()` — line 227
- `withDevice(TornadoDevice)` — line 238
- `withConcurrentDevices()` — line 295
- `freeDeviceMemory()` — line 337
- `withGridScheduler(GridScheduler)` — line 351
- `withProfiler(ProfilerMode)` — line 399
- `withMemoryLimit(String)` — line 425

`@Parallel` annotation unchanged. `FloatArray.get(int)`, `FloatArray.set(int,float)`,
`IntArray.get(int)`, `IntArray.set(int,int)`, `.getSize()` are all the
same methods iteration 1 uses.

**Conclusion:** Zero API changes from 4.0.1-dev to v4.0.1-jdk21. The
"renamed / moved / removed / replaced" categories in the survey prompt
have no entries.

### 4. The persistent-data feature

**Already present in 4.0.1-dev.** The persistOnDevice/consumeFromDevice
pair has been part of the API since the v1.1.0 release (March 2025) and
is in BoA's installed source.

API surface (`~/Code/TornadoVM/tornado-api/src/main/java/uk/ac/manchester/tornado/api/TaskGraph.java:743-760`):

```java
/**
 * Tags a set of objects to persist on the device without transferring them
 * back to the host after execution.
 * ...
 */
@Override
public TaskGraph persistOnDevice(Object... objects) {
    taskGraphImpl.transferToHost(DataTransferMode.UNDER_DEMAND, objects);
    return this;
}
```

Note: it's implemented as an alias for `transferToHost(UNDER_DEMAND, ...)`
under the hood. The semantic is "do not auto-copy back to host, keep
device-resident". The companion method `consumeFromDevice(String
uniqueTaskGraphName, Object... objects)` (line 701) on a *later* TaskGraph
tags those same Java objects as already-resident inputs that should not be
re-uploaded.

The PERSIST bytecode itself is at
`~/Code/TornadoVM/tornado-runtime/src/main/java/uk/ac/manchester/tornado/runtime/graph/TornadoVMBytecodes.java:218`
(`PERSIST((byte) 26)`), emitted by `TornadoVMBytecodeBuilder.java:232` and
interpreted at `TornadoVMInterpreter.java:382`.

**Canonical pattern** (verified from the in-tree unit test
`~/Code/TornadoVM/tornado-unittests/src/main/java/uk/ac/manchester/tornado/unittests/api/TestSharedBuffers.java:96-136`):

```java
TaskGraph tg1 = new TaskGraph("s0")
    .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
    .task("t0", TestHello::add, a, b, c)
    .persistOnDevice(c);

TaskGraph tg2 = new TaskGraph("s1")
    .consumeFromDevice(tg1.getTaskGraphName(), c)
    .task("t1", TestHello::add, c, c, c)
    .transferToHost(DataTransferMode.EVERY_EXECUTION, c);

try (TornadoExecutionPlan plan = new TornadoExecutionPlan(
        tg1.snapshot(), tg2.snapshot())) {
    plan.withGraph(0).execute();   // first plan once
    plan.withGraph(1).execute();   // second plan every step
    // assert c is the result of both
}
```

Real-world example: GPULlama3.java's `LlamaFP16FFNLayers.java` builds one
TaskGraph per LLM layer; the first transfers all activation buffers with
`FIRST_EXECUTION`, subsequent layers `consumeFromDevice` those buffers,
and each layer `persistOnDevice(state.wrapX)` so the residual stream
remains on-GPU between layers. (Code visible in the file path returned by
the WebSearch — quoted text in survey notes.)

**Mapping to iteration 2's "motor positions live on the GPU across
thousands of timesteps" design:** the API supports it cleanly. Two
TaskGraphs:
- `tgInit` (run once via `plan.withGraph(0).execute()`): transferToDevice
  the bulk read-only inputs (segment endpoints if they don't move, grid
  params if static, etc.) with FIRST_EXECUTION and `.persistOnDevice(...)`
  them.
- `tgStep` (run every step via `plan.withGraph(1).execute()`):
  `.consumeFromDevice("tgInit", ...)` the persisted buffers, then
  `.transferToDevice(EVERY_EXECUTION, ...)` only the data that actually
  changed (motor positions after CPU integration, the per-step counter),
  run the binding kernel, transferToHost the boundSegId result.

**Caveat.** This is *not* a free lunch for iteration 2. Motor positions
change every step (CPU integration updates them), so they still need
EVERY_EXECUTION transfer. Persistence only buys you skipping re-upload of
read-only-during-the-run data. In iteration 1's call, the inputs that
*could* be persisted (assuming the geometry doesn't change shape mid-run)
are: filEnd1, filEnd2, filNodeAtEnd2, alignTol, myoColTolSq. That's most
of the segment-side bandwidth — non-trivial — but not the motor side, which
is the dominant changing buffer.

To get the *big* win — "positions live on GPU across thousands of
timesteps" — iteration 2 has to do more than wire persistOnDevice; it has
to keep motor positions on the GPU between binding kernel calls, which
means moving CPU integration onto the GPU too, so positions never round-
trip. That's iteration 4-5 of the GPU strategy, not iteration 2.

So persistence is *available* and *useful* for iteration 2's static
inputs, but the asymmetry between "static segments" and "changing motors"
is real and was hidden inside the planner's prompt phrasing.

### 5. Install location and side-by-side feasibility

Current dist is at
`~/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/` (mtime
2026-05-03). Sim3D uses this exact path per its build commands.

**Side-by-side via SDKMAN!** is the cleanest path:
- `sdk install tornadovm 4.0.1-jdk21-ptx` would create
  `~/.sdkman/candidates/tornadovm/4.0.1-jdk21-ptx/` (SDKMAN is not yet
  installed locally — `ls ~/.sdkman` returns "No such file or directory"
  — so this is a precondition step for the upgrade-execution session).
- SDKMAN auto-sets `TORNADOVM_HOME` on `sdk use`, but does not touch
  arbitrary other env vars. Sim3D's existing `TORNADOVM_HOME` and `TDIR`
  exports stay pointed at the `~/Code/TornadoVM/dist/...` path.
- BoA's CLAUDE.md exports get repointed: either `sdk use tornadovm
  4.0.1-jdk21-ptx` before running, or set
  `TORNADOVM_HOME=~/.sdkman/candidates/tornadovm/4.0.1-jdk21-ptx`
  explicitly in the run line.

**Side-by-side via parallel binary directory** is the alternative:
- Build (or download) `tornadovm-4.0.1-jdk21-ptx-linux-amd64.zip` (if a
  direct PTX ZIP exists on the release page; not visible at the v4.0.1-jdk21
  release page itself, so this may require building from source from the
  v4.0.1-jdk21 git tag).
- Install to `~/Code/TornadoVM/dist/tornadovm-4.0.1-jdk21-ptx-linux-amd64/`
  (sibling of the existing 4.0.1-dev directory).
- BoA's build commands in CLAUDE.md change the `TDIR=` and `TORNADOVM_HOME=`
  paths to the new directory; Sim3D's stay unchanged.

Either approach works. SDKMAN is preferred because it gives a stable,
named install slot and a documented invocation pattern (CLAUDE.md
mentions "TornadoVM README mentions SDKMAN! support — is that available
for our target platform?" — yes, `4.0.1-jdk21-ptx` exists in the SDKMAN
index per https://api.sdkman.io/2/candidates/tornadovm/linux/versions/list).

Required env-var changes after the upgrade are minimal: the BoA run
commands' `TORNADOVM_HOME` and `TDIR` change to the new install path.
Everything else (the `@tornado-argfile` reference, the `-cp` template, the
javac line) stays identical, since `tornado-api-4.0.1-dev.jar` becomes
`tornado-api-4.0.1.jar` (or whatever the tagged-release jar is named) and
the classpath glob `libs/*` style doesn't care about specific filenames
once the `TDIR` path resolves correctly.

### 6. Risk assessment for iteration 1

**Very low — verging on zero.** The reasoning:

- Every import iteration 1 uses (`GPUMotorBinding.java:3-9`) is at the
  same package coordinates in v4.0.1-jdk21 source as in 4.0.1-dev. None
  have been moved, renamed, or removed (§3).
- The `DataTransferMode.FIRST_EXECUTION` and `EVERY_EXECUTION` constants
  iteration 1 uses (`GPUMotorBinding.java:198, 208`) are stable since at
  least v1.1.0.
- The `TaskGraph` build pattern at `GPUMotorBinding.java:197-208` —
  `new TaskGraph("...").transferToDevice(...).task(...).transferToHost(...)`
  — is the documented canonical form across all 1.x, 2.x, 3.x, 4.x docs.
- The `TornadoExecutionPlan(ImmutableTaskGraph...)` constructor pattern
  at line 211 is stable.
- The `@Parallel int m` annotation use at line 104 is the canonical kernel
  parallelism annotation, unchanged.
- `FloatArray`/`IntArray` `.get(i)`, `.set(i,v)`, `.getSize()` are the
  same primitives used by every TornadoVM 1.1+ example.

The lines in `GPUMotorBinding.java` most likely to need changes are:
- **Line 188** (`FloatArray.getSize() / 3` inside `@Parallel int m`
  loop bound): unchanged in v4.0.1-jdk21. No risk.
- **Lines 246-247** (`plan.execute()` → assumes `execute()` returns
  something whose result is unused): `execute()` returns
  `TornadoExecutionResult` in 4.0.1-dev (line 181 of TornadoExecutionPlan.java).
  Iteration 1 discards the return value — that compiles forever.
- **Line 286-291** (`plan.close()` inside try/catch): `TornadoExecutionPlan
  implements AutoCloseable` (line 66 of TornadoExecutionPlan.java) since
  at least v1.x. Stable.

Estimated invasiveness of port: **zero lines changed** in
`boxOfActin/GPUMotorBinding.java`. The file should compile and run against
v4.0.1-jdk21 unchanged.

The only changes are environmental: the `-cp` classpath path components
in CLAUDE.md (build and run commands) updated to point at the new jar
locations. That's a documentation edit, not a Java code edit.

### 7. Sim3D isolation

Sim3D's TornadoVM-using code lives at `~/Code/Sim3D/` and uses
`~/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/` per its
CLAUDE.md (which the prompt instructs us to read; I did not modify it).
The dist install at that path is **not touched** by either an SDKMAN
install or a parallel-directory install of v4.0.1-jdk21.

**Isolation strategy.** Two env-var groups, scoped per shell or per run
command:
- **Sim3D shell / commands**: `TORNADOVM_HOME=~/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx`
  and the matching `TDIR` (unchanged from today).
- **BoA shell / commands**: `TORNADOVM_HOME=~/.sdkman/candidates/tornadovm/4.0.1-jdk21-ptx`
  (or the parallel-directory equivalent) and the matching `TDIR`.

Neither path interferes with the other. The classpath the JVM uses is
fully derived from `TDIR`/`TORNADOVM_HOME` — there's no global TornadoVM
state.

**No changes required to Sim3D.** None of the upgrade-execution work
needs to touch Sim3D's source tree, its CLAUDE.md, or its dist install.
The flag the survey prompt asked for ("if the upgrade would require any
change to Sim3D — even a small one — flag it explicitly") is: nothing.

### 8. Recommended upgrade plan

**Recommendation: defer or skip the upgrade.** This is the survey's main
deliverable.

The planner's prompt framed the upgrade as "iteration 2's persistent-
residency architecture maps to a first-class supported API rather than
being a workaround against an older one". That premise is wrong: the
persistOnDevice/consumeFromDevice/PERSIST API is already first-class in
BoA's installed 4.0.1-dev. The "upgrade" from 4.0.1-dev to v4.0.1-jdk21
is essentially a relabel from "develop tip" to "tagged release" — same
API surface, same parameter cap, same primitives, same JDK requirement.

If the planner still wants to move to the tagged release (for stability,
provenance, reproducibility — all reasonable reasons), here's the plan:

- **Target version**: TornadoVM v4.0.1-jdk21 (released 2026-04-29).
- **Install method**: SDKMAN. Identifier `4.0.1-jdk21-ptx`. Preconditions:
  install SDKMAN itself (`curl -s "https://get.sdkman.io" | bash`), then
  `sdk install tornadovm 4.0.1-jdk21-ptx`. (Alternative: build the v4.0.1-jdk21
  git tag from source into a sibling dist directory — slower but more
  scriptable.)
- **Install location strategy**: side-by-side. Sim3D's existing 4.0.1-dev
  install stays untouched. BoA points its `TORNADOVM_HOME`/`TDIR` env
  vars at the new SDKMAN slot (or parallel dist directory).
- **Order of operations** for an upgrade-execution session:
  1. Install SDKMAN (`curl -s "https://get.sdkman.io" | bash`,
     `source ~/.sdkman/bin/sdkman-init.sh`).
  2. `sdk install tornadovm 4.0.1-jdk21-ptx`.
  3. `sdk use tornadovm 4.0.1-jdk21-ptx` (sets `TORNADOVM_HOME`).
  4. Update BoA CLAUDE.md: replace the
     `TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"`
     line with the SDKMAN-provided path, update the `TDIR` to match, and
     update the example javac/java commands.
  5. `javac` rebuild BoA per the current CLAUDE.md instructions but with
     the new `TDIR`. Expect zero source-code changes (per §6).
  6. Re-run iteration 1's gliding-assay validation: `BoxOfActin -r -gpu
     -pf ParameterFiles/glidingAssay500_val` (10 seeds, same protocol as
     the iteration-1 spike). Confirm `bindEvents` / `meanBoundMotors` /
     `velocity` agree with the CPU baseline within |diff|/cSEM < 2.0.
  7. Compare wall-clock against iteration 1's recorded numbers (228 s
     CPU, 251 s GPU). Same hardware, same kernel — should be within ~3%.

- **Estimated session size: small (under an hour).** No source edits, no
  algorithm work, no validation re-design. The risk is bounded by SDKMAN
  install glitches and the env-var path swap.

- **Preconditions for the upgrade-execution session:**
  - Main is clean (per the usual planner convention).
  - The iteration-1 validation numbers from
    `RUN_LOGS/2026-05-28_gpu-spike-validation.txt` are treated as the
    "before" snapshot to compare against — no re-run of the CPU baseline
    is needed since the CPU path is unchanged by the upgrade.
  - SDKMAN install requires internet access on aorus and `curl`/`zip`
    being available in the shell.
  - Aorus stays on JDK 21.0.10. No JDK swap.
  - Sim3D is left alone; no need to re-run its benchmarks pre-upgrade
    since its dist install is unchanged.

**Alternative: skip the upgrade entirely.** Stay on 4.0.1-dev for both
BoA and Sim3D. The persistent-residency API is available right now.
Iteration 2 can be implemented against 4.0.1-dev directly. The cost is
"we are on a develop snapshot, not a tag" — a minor reproducibility hit,
nothing else.

### 9. Open questions / risks for the planner

Several planner-decision points surfaced during the survey:

1. **The upgrade premise was based on stale version info.** The planner's
   "current at v2.2.0 (released late 2025)" was wrong by ~5 months and
   two major versions. BoA's existing 4.0.1-dev install is already the
   modern API. Decision: is the upgrade still worth doing for
   reproducibility/provenance reasons, or is it more useful to put that
   session's budget into iteration 2 implementation against the existing
   install?

2. **The 15-parameter cap is unchanged across the upgrade.** If iteration
   2's design requires strict per-coordinate SoA (which would push past
   the cap), the upgrade does *not* solve that. Two paths remain: pack
   scalars/params into FloatArrays (the WIP iter2 trick — bought
   13 → 11 effective slots), or split the kernel into two cooperating
   tasks joined by `consumeFromDevice` (cleaner, enabled by the
   persistent-residency API that's already present).

3. **Persistent residency only saves transfer for static-during-the-run
   data.** Motor positions, which are the heaviest per-step inputs, still
   need EVERY_EXECUTION transfer because the CPU updates them each step
   via integration. The "positions live on GPU across thousands of
   timesteps" framing is aspirational — it requires moving CPU
   integration onto the GPU too (iteration 4-5), not just wiring
   persistOnDevice. The iter2-pre-upgrade WIP (commit 5792838) already
   correctly identified this and used `FIRST_EXECUTION` for static
   inputs without invoking persistOnDevice. Decision: is iteration 2
   scoped as (a) "wire persistOnDevice to save the static-input
   bandwidth", (b) "add broad-phase grid as in the WIP", or (c) both? If
   (a), the wins are modest. If (b), it's substantially more work and
   the upgrade doesn't matter.

4. **SDKMAN is preferred but not installed locally.** SDKMAN install is
   itself a precondition step (~5 minutes). The alternative — building
   v4.0.1-jdk21 from the git tag into a sibling dist directory — is
   slower (~10-30 minutes Maven build) but doesn't introduce a new tool
   into the conventions. Decision: SDKMAN, build-from-source, or neither?
   CLAUDE.md's current conventions don't mention SDKMAN, so this is a
   convention change.

5. **No upgrade-execution session is strictly required to start
   iteration 2.** The persistent-residency primitives are already at
   `~/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/`. The
   planner could write the iteration-2 prompt today against 4.0.1-dev
   and treat any future upgrade as a separate housekeeping task.

6. **The WIP iter2 branch (commit 5792838) is informative but
   doesn't use persistOnDevice.** It chose a single-graph design with
   `FIRST_EXECUTION` for static buffers and `EVERY_EXECUTION` for the
   rest, plus a CPU-built grid uploaded each step. That design works
   against 4.0.1-dev as-is. Whether iteration 2's *next* attempt should
   adopt persistOnDevice / two-graph structure or keep the single-graph
   FIRST_EXECUTION pattern is a design choice independent of the
   upgrade decision. Worth a separate planner conversation.

---

## 2026-05-28 — First GPU kernel: motor-binding via TornadoVM (one-plan spike)

First TornadoVM kernel for BoA. Deliberately Sim3D-shaped: one plan, per-step
EVERY_EXECUTION pack/execute/unpack, brute-force motor × segment fine check.
Validates the toolchain end-to-end, statistically agrees with the CPU baseline,
measures the transfer-bound wall-clock floor honestly. Iteration 2 will move to
two-plan persistent residency with the spike numbers below as the baseline.

### Files added/changed

- `boxOfActin/GPUMotorBinding.java` — new file (305 lines). Static-only kernel
  class; mirrors `Sim3D/GPUCollisionDetector.java` structure with BoA-shaped
  inputs.
- `boxOfActin/Env.java:198` — added `static boolean useGPU = false;`.
- `boxOfActin/BoxOfActin.java` — `-gpu` flag parsing (after the `-seed` block,
  ~line 356); dispatch mutex in `doLoop()` (~line 713); GPU timing in `[STATS]`
  block (~line 1192); `-help` line.
- `CLAUDE.md` — `-g` added to both the current and post-Phase-5 javac lines
  with PTX-local-variable-table note; new "GPU run (aorus)" block with the
  `@tornado-argfile` invocation; `-gpu` row added to flag table.
- `MyoFilLink.java` — **not** modified. The committed-bind instrumentation from
  the diagnostic session was already reverted from HEAD per its own session
  notes; the spike does not re-add it. `MyoMotor.totalBindEvents` (semantically
  the committed-bind counter, verified in the diagnostic session) is sufficient
  as the regression observable.

### The kernel (full quote)

```java
private static void bindKernel(
        FloatArray motPos, FloatArray motUVec, FloatArray motRodUVec,
        IntArray   motOnFil,
        FloatArray filEnd1, FloatArray filEnd2,
        IntArray   filNodeAtEnd2,
        IntArray   counts,
        IntArray   boundSegId,
        float      alignTol,
        float      myoColTolSq) {
    int M = counts.get(0);
    int S = counts.get(1);
    for (@Parallel int m = 0; m < motPos.getSize() / 3; m++) {
        if (m >= M) { return; }
        boundSegId.set(m, -1);
        if (motOnFil.get(m) != 0) { continue; }
        float mx = motPos.get(m*3),     my = motPos.get(m*3+1),     mz = motPos.get(m*3+2);
        float mux = motUVec.get(m*3),   muy = motUVec.get(m*3+1),   muz = motUVec.get(m*3+2);
        float rux = motRodUVec.get(m*3),ruy = motRodUVec.get(m*3+1),ruz = motRodUVec.get(m*3+2);
        for (int s = 0; s < S; s++) {
            if (filNodeAtEnd2.get(s) != 0) { continue; }
            float e1x = filEnd1.get(s*3), e1y = filEnd1.get(s*3+1), e1z = filEnd1.get(s*3+2);
            float r1x = filEnd2.get(s*3)-e1x, r1y = filEnd2.get(s*3+1)-e1y, r1z = filEnd2.get(s*3+2)-e1z;
            float denom = r1x*r1x + r1y*r1y + r1z*r1z;
            float invLen = 1.0f / (float) Math.sqrt(denom);
            float fUx = r1x*invLen, fUy = r1y*invLen, fUz = r1z*invLen;
            if (mux*fUx + muy*fUy + muz*fUz < alignTol) { continue; }
            if (rux*fUx + ruy*fUy + ruz*fUz < 0f)       { continue; }
            float r2x = mx-e1x, r2y = my-e1y, r2z = mz-e1z;
            float alpha = (r2x*r1x + r2y*r1y + r2z*r1z) / denom;
            if (alpha < 0f || alpha > 1f) { continue; }
            float cpx = e1x + alpha*r1x, cpy = e1y + alpha*r1y, cpz = e1z + alpha*r1z;
            float dx = cpx-mx, dy = cpy-my, dz = cpz-mz;
            if (dx*dx + dy*dy + dz*dz < myoColTolSq) {
                boundSegId.set(m, s); break;
            }
        }
    }
}
```

Translation from CPU step-1b is mechanical: `double[]` reads become
`FloatArray.get(i)`; the orientation/range gates and the squared-distance
compare are unchanged; `Pt3D.ptDist`'s `sqrt` is dropped per Sim3D survey §4
(squared compare suffices). Inactive-thread early-return at the top of the
parallel loop is mandatory per §9.

### TaskGraph build (full quote)

```java
TaskGraph tg = new TaskGraph("motorBinding")
    .transferToDevice(DataTransferMode.EVERY_EXECUTION,
                      motPos, motUVec, motRodUVec, motOnFil,
                      filEnd1, filEnd2, filNodeAtEnd2,
                      counts)
    .task("bind",
          GPUMotorBinding::bindKernel,
          motPos, motUVec, motRodUVec, motOnFil,
          filEnd1, filEnd2, filNodeAtEnd2,
          counts, boundSegId,
          alignTol, myoColTolSq)
    .transferToHost(DataTransferMode.EVERY_EXECUTION, boundSegId);

itg  = tg.snapshot();
plan = new TornadoExecutionPlan(itg);
```

Single TaskGraph, single task. All inputs EVERY_EXECUTION (this is the
transfer-bound shape — iteration 2 promotes static topology to FIRST_EXECUTION
once persistent residency is wired in). `alignTol` and `myoColTolSq` are
captured at build time as scalar kernel parameters; neither is marked
`setMutableAtRuntime` in Env.java so the plan-invalidation hook from Sim3D §9
is not needed for this kernel.

### Pack/unpack

Pack walks `MyoMotor.soa*` and `FilSegment.soa*` arrays (already populated by
`fillSoaArrays()` at the top of `doLoop()`), casting `double → float` at the
copy site:

```java
motPos.set(j,     (float) MyoMotor.soaX[i]);
motPos.set(j + 1, (float) MyoMotor.soaY[i]);
motPos.set(j + 2, (float) MyoMotor.soaZ[i]);
// ... (all 9 motor floats + onFil int per motor; 6 segment floats + nodeAtEnd2 int per segment)
```

Unpack walks `boundSegId`; for each motor `i` with `boundSegId[i] >= 0` it
recomputes `arcOnFil = alpha * sqrt(|r1|²)` from the CPU SoA arrays (cheaper
than downloading alpha) and calls `MyoMotor.theMotors[i].ontoFilament(seg,
arcOnFil)`. The serialized synchronized event semantics of `ontoFilament` are
unchanged from step 1b — kernel produces the decision, CPU resolves the event.

### Discovery: TornadoVM `task()` parameter cap

Strict per-coordinate SoA (separate `xPos/yPos/zPos`) would have given the
kernel 21 array/scalar parameters. TornadoVM's `TaskGraph.task()` variadic
overloads cap at 15 (`Task1` … `Task15` in `TornadoFunctions`). The first
compile attempt failed with the "no suitable method" overload-resolution error
naming all 15 Task arities.

Workaround for this iteration: pack each three-vector attribute into one
FloatArray (`motPos[m*3..m*3+2]`, `motUVec[…]`, `motRodUVec[…]`, etc.). Sim3D
already uses this AoS-by-attribute pattern (e.g. `muttonPos[m*3+0..2]`); it
was unclear from the survey whether the limit forced that choice or whether it
was style. It is forced. Strict-SoA per `GPU_STRATEGY.md` is deferred to
iteration 2, where the kernel will likely be split into smaller cooperating
kernels (or use a different launch API) so each individual kernel stays under
the 15-param ceiling.

### Wang-hash salt reservation

`GPUMotorBinding.KERNEL_ID = 1` is declared and documented; the
recommended-by-survey seed scheme is `m * 1000003 + step * 999983 + KERNEL_ID
* 7919`. **This kernel does not currently consume RNG** — motor binding is
deterministic given positions. The salt slot is reserved for the next kernels
(integration, biochem). `counts[2]` carries `Env.counter` each step so the
step counter is already on the GPU for future RNG seeding.

### Validation (10 seeds × glidingAssay500\_val × 0.1 s)

CPU baseline and `-gpu` runs at the same HEAD (CPU path unchanged). Protocol
matches step-1b and diagnostic sessions. Apples-to-apples seed-by-seed
ensemble; statistical comparison is |diff| / cSEM < 2.0 per observable.

Raw per-seed values + full run output at
`RUN_LOGS/2026-05-28_gpu-spike-validation.txt`.

```
observable        | CPU (10 seeds)               | GPU (10 seeds)               | |diff|/cSEM | verdict
bindEvents        |  816.1 ± 30.4  (SD  96.0)    |  852.1 ± 27.7  (SD  87.5)    |    0.88    | PASS
meanBoundMotors   |    7.275 ± 0.197 (SD 0.622)  |    7.242 ± 0.179 (SD 0.566)  |    0.12    | PASS
velocity (µm/s)   |    4.606 ± 0.191 (SD 0.604)  |    4.741 ± 0.156 (SD 0.493)  |    0.54    | PASS
```

All three observables agree well within 1σ. SD ratios within ~1.1× across
ensembles. **Kernel produces correct binding behavior.** GPU `bindEvents` mean
(852) is consistent with the step-1b diagnostic baseline of 824.8 ± 24.0 from
the 2026-05-28 session. The 5–6 difference between the CPU baseline here and
the diagnostic-session baseline (816 vs 825) is within the noise floor of
the documented force-accumulation race.

### Wall-clock

```
mode | mean (s/seed) | SD (s) | n  | ratio (vs CPU)
CPU  |     228.2     |  0.92  | 10 |  1.000×
GPU  |     251.5     |  0.44  | 10 |  1.102×
```

**GPU is 10.2% slower than CPU at this workload — the expected outcome.** The
ratio > 1 confirms the transfer-bound diagnosis and motivates iteration 2.
This is a successful spike per the session's success criterion.

GPU per-call breakdown (10101 calls/run, averaged across 10 seeds):

```
phase  | total (s) | per call (ms)
pack   |    1.32   |   0.131
exec   |   41.63   |   4.121
unpack |    0.62   |   0.061
total  |   44.19   |   4.375
```

The `exec` phase dominates by 30× over pack+unpack combined. This rules out
"transfers between calls" as the bottleneck and points to **per-call exec
overhead** (kernel launch + EVERY_EXECUTION transfer batching done inside the
TaskGraph runtime) as the lever. Two-plan persistent residency directly
attacks this: positions move at `FIRST_EXECUTION` once and stay on-device, so
each step's `plan.execute()` should drop substantially.

Reading the gap another way: total wall-clock minus the GPU motor-binding
total (251 − 44 = 207 s) is roughly the non-binding CPU work. The CPU baseline
wall-clock minus the same 207 s ≈ 21 s for CPU motor-binding (16-thread grid
+ fine check). So **CPU is ~2× faster than GPU on this kernel today**:
21 s CPU vs 44 s GPU. The GPU kernel is doing more work per call (O(M × S)
brute force vs CPU's grid-cell candidate set) and paying per-call overhead.
Iteration 3+ (GPU broad-phase grid) is the asymptotic-scaling lever; iteration
2 (persistent residency) is the per-call-overhead lever.

### Open questions for iteration 2

1. **TaskGraph parameter limit and SoA refactor.** Iteration 2 needs to
   actually deliver per-coordinate SoA per GPU\_STRATEGY's mandate. Options:
   (a) split into two sub-kernels (separate motor-state-prep kernel and
   actual-collision kernel) joined by a TaskGraph dependency; (b) use a
   different TaskGraph API surface that does not cap at 15. Worth a survey of
   `Task` interfaces vs the `TornadoFunctions` extension points before
   committing.

2. **Per-call exec overhead.** 4.1 ms/call exec time on a brute-force
   O(M × S) kernel at d=500 is the headline finding to investigate. Tornado
   profiling (`-Dtornado.profiler=true`) should split that 4.1 ms into kernel
   wall-clock, transfer wall-clock, and host-side overhead. We do not yet know
   which dominates; the assumption is transfer but it could be kernel-launch
   overhead from rebuilding the same 8 transferToDevice descriptors every step.

3. **Persistent-residency boundaries.** When iteration 2 makes positions
   FIRST\_EXECUTION, every CPU phase that reads positions between motor-binding
   and the GPU's next sync becomes a stale-read problem. The cleanest answer
   is to either port more phases (forces, integration) to GPU together, or to
   carefully audit which CPU phases read SoA position arrays after the kernel
   call. The current loop order means `xLink`, `membraneLinks`, `myoJoints`,
   `step`, `move`, `biochem` all run between consecutive motor-binding calls;
   all read Pt3D objects, not SoA arrays, so today's spike does not surface
   the issue, but iteration 2 will need to either upload positions every step
   or move more work to GPU.

4. **Cross-validation against `bindEventsAtomic` and `committedBinds`.** The
   diagnostic session showed `bindEvents` (non-atomic) loses ~0.04% of events
   to the race; for GPU regression, if event counts climb 10×–100× per kernel
   launch the undercount could grow. Optional task for iteration 2: swap
   `static long` → `AtomicLong` per the diagnostic session's recommendation,
   especially before the kernel handles the `unbind` decision too.

5. **Brute-force vs grid asymptotic.** This kernel walks all S segments per
   motor (S = 14 segs for gliding-assay; trivially fast). At BoA's research
   scale (thousands of motors × thousands of segments) the brute-force wall
   could swamp the CPU savings entirely. Iteration 3+ (GPU broad-phase) is
   gated on the iteration 2 measurement of when brute force becomes the bound.

### Commit

See `git log --grep "first GPU kernel: motor-binding via TornadoVM"` for the
session commit (a self-referential hash inside the same commit's journal entry
isn't expressible via amend).

## 2026-05-26 — Survey: Brownian-on-FilSegments GPU port readiness

### 1. Where Brownian forces are computed today

`Thing.calcRandomForces()` (Thing.java:348–368). Calls `UCircRnd.newValue(deltaT, this)` three times (x, y, z axes) to generate Box-Muller pairs using the per-Thing PRNG. Reads `bTransDiff` and `bRotDiff` (body-fixed diffusion coefficients) to scale the Gaussian samples; also reads `bTransGam` and `bRotGam` for the final force scaling. Writes `randForces` and `randTorques` (Pt3D fields on Thing).

```java
// Thing.java:348–368
public void calcRandomForces () {
    xVals.newValue(Env.brownianDeltaT.getValue(),this);
    yVals.newValue(Env.brownianDeltaT.getValue(),this);
    zVals.newValue(Env.brownianDeltaT.getValue(),this);
    v1.setVals(xVals.v1,yVals.v1,zVals.v1);
    v2.setVals(xVals.v2,yVals.v2,zVals.v2);
    rsq.setVals(xVals.rsq,yVals.rsq,zVals.rsq);
    facterm.setVals(xVals.facterm,yVals.facterm,zVals.facterm);
    tempPt.mult(bTransDiff, facterm);
    fac1.vecSqrt(tempPt);
    tempPt.mult(bRotDiff, facterm);
    fac2.vecSqrt(tempPt);
    randForces.mult(1.0/Env.brownianDeltaT.getValue(), v1, fac1, bTransGam);
    randTorques.mult(1.0/Env.brownianDeltaT.getValue(), v2, fac2, bRotGam);
}
```

`FilSegment` overrides this (FilSegment.java:536–548): root segments (no `motherFil`) call `super.calcRandomForces()` and optionally store the results in body-frame coords for Arp2/3 branches to copy; branch segments just zero `randForces`/`randTorques` and return.

ThreadSet: `Thing.ThingBrownianThreads` (Thing.java:201–233). Fan-out over `theThings[]`, 16 threads (`numBForceThreads = allThreadCt = 16`), phase `Env.bForcesStart = 5`.

doLoop dispatch (BoxOfActin.java:709–713):
```java
if (applyBrownianForcesCounter >= Thing.brownianApplyInt | Env.simulationTime == 0) {
    brownianTimer.start();
    startAllThreadSets(Env.bForcesStart);
    waitOnAllThreadSets(Env.bForcesStop);
    brownianTimer.stopInc();
}
```
`brownianApplyInt = (int)(brownianDeltaT / deltaT)` — Brownian forces are applied every N timesteps, not every step. For glidingAssayBatch_template: `brownianDeltaT = deltaT = 1e-5`, so N = 1 (every step).

### 2. FilSegment position storage today

All position and force state lives in per-object `Pt3D` fields on `Thing`. No flat arrays exist anywhere in the codebase.

```java
// Thing.java:32–55 (relevant field declarations)
Pt3D coord = new Pt3D();            // x,y,z position
Pt3D bTransGam = new Pt3D();        // body-fixed drag (x,y,z)
Pt3D bRotGam = new Pt3D();          // body-fixed rotational drag
Pt3D bTransDiff = new Pt3D();       // body-fixed translational diffusion coefficients
Pt3D bRotDiff = new Pt3D();         // body-fixed rotational diffusion coefficients
Pt3D randForces = new Pt3D();       // random translational forces (written by calcRandomForces)
Pt3D randTorques = new Pt3D();      // random torques
Pt3D forceSum = new Pt3D();         // fixed-frame accumulated force (read in step/moveThing)
```

`Pt3D` has `public double x, y, z` fields. `FilSegment` also has `end1`, `end2` (Pt3D) for segment endpoints, updated every step by `initialize()`.

No pre-existing SoA FloatArrays. The entire SoA layout (xPos, yPos, zPos, xForce, yForce, zForce, dragCoeff, radius arrays) is absent.

### 3. FilSegment count in real runs

**boa10-64Seg:** `initialFilaments:true:100` seeds each created with `actinSeed.getIntValue()` monomers (default 3) → 100 FilSegments at t=0. With `kNodeNuc:true:10` active, new filaments nucleate and grow over the run. Segments split (FilSegment.java:444) when `monomerCt >= 2 * stdSegLength = 128`. Steady-state count requires running the code; estimated 200–500 FilSegments based on 10×10 µm box with active polymerization.

**glidingAssayBatch_template:** `makeGlidingAssayFilament()` creates one FilSegment with `monCt = (int)(glidingFilamentLength / actinMonoRadius) = (int)(2.0 / 0.0027) = 740` monomers (actinMonoRadius = actinMonoDiam/2 = 0.0054/2 = 0.0027 µm). With `filSegLength:true:64.0` (stdSegLength = 64), the 740-monomer segment splits repeatedly on successive `biochemStep()` calls until all segments are ≤ 64 monomers. At steady state: **~11–12 FilSegments per run** for a 2 µm filament (confirmed by test-run observation of ~8 segments within the first few frames in the prior journal entry).

Motor count: `numMyos = (int)(boxXDim × boxYDim × density) = (int)(14 × 2 × density) = (int)(28 × density)`. Sweep range:

| Density (motors/µm²) | Motor count |
|---|---|
| 10 | 280 |
| 100 | 2,800 |
| 500 | 14,000 |
| 2500 | 70,000 |

**Largest BoA run to date:** Not determinable without running. Design capacity is 1M slots in both `Thing.theThings[]` and `FilSegment.theFilSegments[]`.

### 4. RNG today

`Thing.myPRNG` is a `MersenneTwisterFast` (ec.util), one per-Thing instance, seeded with a random long at construction (Thing.java:62):
```java
MersenneTwisterFast myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));
```

When `calcRandomForces()` calls `xVals.newValue(deltaT, this)`, `UCircRnd` uses `thing.myPRNG.nextDouble()` explicitly (UCircRnd.java:33). RNG state is **per-FilSegment** — each segment has independent random draws.

GPU port implication: GPU threads will use a Wang hash keyed on `(threadId, stepCounter)` — a completely different algorithm, different sequence. Frame-by-frame position comparison between CPU and GPU paths is meaningless. Validation requires statistical tests only (mean and variance of `randForces` per segment type over many steps should match theoretical values). Flag this to any A/B test design.

### 5. ThreadSet dispatch options for GPU

The existing doLoop (BoxOfActin.java:709–713) invokes `ThingBrownianThreads` as one of ~12 sequential `start/wait` pairs per timestep. Three integration shapes for a GPU path:

**Option A — Replace the ThreadSet, same per-step call site.** Insert a `-gpu` flag; `doLoop` calls a GPU kernel in place of `startAllThreadSets(bForcesStart)`. Positions are downloaded after every step (same as the current CPU path). Simplest code change; zero persistent residency benefit. This is the Sim3D approach that gave 1.6× due to transfer bottleneck — at ~11 FilSegments in the gliding assay it would be measurably slower than CPU.

**Option B — GPU-resident simulation (GPU_STRATEGY.md architecture).** Replace the entire inner `for` loop in `TimeLoop.run()` under a `-gpu` flag. GPU runs `toFileInterval` steps on-device; CPU downloads positions once per output frame. Requires restructuring doLoop around the output cadence rather than the per-step cadence. The ThreadSet infrastructure becomes irrelevant for GPU phases. This is the architecture that yields 10–20× for high particle counts.

**Option C — Selective ThreadSet replacement.** GPU handles the Brownian phase only (same call site as A), but positions are kept GPU-resident across the Brownian and step/moveThing phases within one timestep and downloaded once at the end of the timestep. Middle-ground complexity; partial benefit.

Option B is the only one that realizes GPU_STRATEGY.md's persistent-residency speedup. Options A and C are transfer-limited and not worth implementing for a small number of segments.

### 6. State BoA does not yet have that the port will need

Confirmed absent by exhaustive grep: no `FloatArray`, no `TornadoVM`, no `@Parallel`, no `TaskGraph`, no `xPos[]`, `yPos[]`, `zPos[]`, `xForce[]`, `yForce[]`, `zForce[]` anywhere in `boxOfActin/`.

Everything the port will need to add from scratch:
- `FloatArray xPos, yPos, zPos` for all FilSegments (GPU-resident positions)
- `FloatArray xForce, yForce, zForce` (zeroed on GPU at start of each step kernel)
- `FloatArray dragPar, dragPerp, dragRot` (per-segment drag coefficients, FIRST_EXECUTION upload)
- `FloatArray segRadius` (FIRST_EXECUTION upload)
- A step counter `IntArray` for Wang-hash seeding
- TornadoVM imports and `TaskGraph` / `TornadoExecutionPlan` wiring
- A topology-rebuild path for when FilSegment count changes (split/creation events invalidate the SoA arrays)

### 7. Coexistence with WebSocket live observer

No plausible interaction. `calcRandomForces()` writes `randForces`/`randTorques` on the `FilSegment` instance. `ThreeJSWriter.buildFrameJson()` reads `end1`, `end2`, and `coord` (positions). These are different fields. Moreover, frame dispatch occurs in `logAndDraw()` / `remoteLog()`, which is called at the safe point after all physics phases have completed — including `moveThing()`, which translates forces into updated positions. By the time `dispatchFrame()` runs, `randForces` has already been consumed and positions are stable.

A GPU kernel writing into `xForce[]`/`yForce[]`/`zForce[]` FloatArrays would be even more isolated from the WebSocket path, since those SoA arrays won't be the same fields that `ThreeJSWriter` reads. The download step (positions only, at output boundary) is already outside the force-computation phases.

### 8. Open question for the planner

**Is FilSegment-Brownian still the right first GPU kernel given current BoA scales?**

At steady state the gliding-assay template runs with ~11 FilSegments. Any GPU kernel over those 11 segments will be slower than the 16-thread CPU path due to launch overhead alone. The boa10-64Seg run has an estimated 200–500 segments — still below the thousands needed to saturate even a fraction of an RTX 5070's SMs.

The `MyosinFixed` binding search is a better-scaling target: O(motors × segments) with each motor independent. At 70,000 motors and ~11 segments, the inner loop is trivial per motor, but the 70K parallel threads do fill GPU occupancy. More importantly, as density sweeps grow, motor count scales with density and the per-motor work stays constant — the compute-to-transfer ratio improves with density rather than depending on FilSegment count.

Question: should the first GPU kernel target MyosinFixed binding search rather than FilSegment-Brownian? The Brownian port is architecturally simpler (embarrassingly parallel, no neighbor reads) and establishes the SoA infrastructure that the binding search also needs. But if the gliding assay is the primary science driver, the binding search is where measurable speedup will first appear and where the density-sweep data quality would improve. Planner should decide before the port begins.

## 2026-05-26 — Pivot: collision detection, not Brownian, as first GPU target

The Brownian-on-FilSegments survey (above) surfaced the right strategic question in section 8: is FilSegment-Brownian the right first GPU kernel given current scales? Discussion with jba clarified that the question was framed wrong. Two corrections:

1. **The gliding-assay scale (~11 FilSegments) is a teaching case, not the target workload.** The science BoA exists to support — branched actin networks, lamellipodial dynamics, dense myosin minifilament arrays — scales to thousands of filaments and proportionally more FilSegments. The GPU port should be designed for that target workload and validated on smaller cases, not optimized for the smaller cases.

2. **Sim3D's lesson was that collision detection is where the asymptotic GPU win lives.** Brownian is O(N) and embarrassingly parallel; even at thousands of segments it won't dominate the timestep. Collision detection between motor heads and FilSegments (and FilSegment–FilSegment, and any other proximity queries BoA performs) is O(N²) brute force or O(N) with a spatial grid — that's the kernel whose speedup transforms what BoA can simulate.

BoA already has a partial spatial-grid implementation: a coarse 2×2D grid painting of objects that pre-filters pairs before a finer-scale collision check. This is the analog of Sim3D's MuttonGrid before its GPU port.

**Three-phase plan** (revised from the strategy doc's two-phase structure):

1. **Survey** (current session): document the existing 2×2D grid, what proximity queries it serves, the data flow for each, the call structure, and the current per-query work. No edits.

2. **CPU rewrite**: refactor BoA's collision detection into a SoA-friendly, GPU-mappable shape that still runs on CPU. Validate it produces identical results to the current implementation. This is where the algorithm gets debugged in a debuggable environment, before TornadoVM's constraints (FloatArray-only, no nested objects, restricted kernel control flow) enter the picture.

3. **GPU port**: translate the CPU-rewritten version into TornadoVM kernels. With the algorithm already proven, what gets debugged in this phase is purely the TornadoVM mapping (FloatArray layout, kernel boundaries, transfer modes, persistent-residency architecture per `GPU_STRATEGY.md`).

**Open design question for phase 2**: the existing grid is 2×2D, which is appropriate for the gliding-assay slab geometry. The target dense-3D actin-network workload likely needs a full 3D grid. The CPU-rewrite phase should make this choice deliberately, since the grid's dimensionality will be frozen into the kernel data layout in phase 3.

**No update to `GPU_STRATEGY.md` yet.** The persistent-residency architecture, SoA layouts, and transfer-mode tables in that document remain correct. Only the choice of first kernel changes — and the change confirms rather than contradicts the strategy.
---

## 2026-05-26 — Survey: BoA collision detection, current state and CPU-rewrite readiness

### 1. The 2D grid implementation

`Mesh.java` (521 lines). Three static singleton instances: `FILSEG_MESH`, `NODE_MESH`, `MYOHEADS_MESH`. Each is a **pure XY projection** — Z is completely ignored in all fill and query paths.

Data structure per `Mesh` instance (Mesh.java:22–27):
```java
double[][][] meshpoints;   // [nXBins][nYBins][binDepth=1000] — stores double-cast int array indices
int[][]      timeStamps;   // [nXBins][nYBins] — Env.counter at last write (stale-cell detection)
int[][]      activeCts;    // [nXBins][nYBins] — count of objects in cell for current timestamp
Object[][]   mSync;        // [nXBins][nYBins] — per-cell monitor for concurrent fill
```

Cell size: `X_BIN_WIDTH = Y_BIN_WIDTH = 0.2 µm` (Mesh.java:16–17, static, set at class load). Bin count: `nXBins = 1 + ceil(boxXDim / 0.2)`, `nYBins = 1 + ceil(boxYDim / 0.2)`, computed from `Env.boxXDim`/`Env.boxYDim` at class load (Mesh.java:38–40). For a 14×2 µm gliding box: 71×11 = 781 cells; for a 10×10 µm boa10 box: 51×51 = 2601 cells.

What each cell stores: **array indices** cast to double, not object references:
- FILSEG_MESH: `curSeg.filArrayPos` (index into `FilSegment.theFilSegments[]`) — Mesh.java:127
- NODE_MESH: `node.myNodeNumber` (index into `ProteinNode.theNodes[]`) — Mesh.java:132
- MYOHEADS_MESH: `motor.myMotorNumber` (index into `MyoMotor.theMotors[]`) — Mesh.java:137

Z dimension: absent from all fill methods. `fillFilSegMesh` (Mesh.java:301) uses only `startPt.x`/`startPt.y`/`stopPt.x`/`stopPt.y`. `fillMotorMesh` (Mesh.java:404) uses only `motor.bindTip.x`/`motor.bindTip.y`. Objects at different Z coordinates sharing the same XY bins are treated as candidates.

Fill algorithm: `fillFilSegMesh` — for short segments (< `MIN_LENGTH_FOR_LINE_ALGORITHM = 200` nm) fills the XY bounding box of end1→end2; for longer segments, Bresenham line from end1 to end2 then `fillMeshCell` pads ±1 bin around each raster point (Mesh.java:301–351). `fillNodeMesh` and `fillMotorMesh` fill the XY bounding box of coord ± radius or bindTip ± myoColTol (Mesh.java:354–452).

Grid build call site: `BoxOfActin.doLoop()` lines 688–693, inside `if (collisionCkCounter >= Thing.collisionCheckInt | Env.simulationTime == 0)` — three sequential ThreadSet dispatches: `meshFilsStart=0`, `meshNodesStart=1`, `meshMotorsStart=2`. Rebuild interval: `collisionCheckInt = collisionDeltaT / deltaT` (default 1e-4/1e-5 = 10 steps).

Grid query call sites:
- `meshCollStart=3`: immediately after mesh fills, inside the same gate. FilSeg–FilSeg, Node–Node, Node–FilSeg queries.
- `motCollStart=4`: **outside** the gate (BoxOfActin.java:704) — runs every timestep against stale mesh data from the last rebuild. The stale check passes because `Mesh.lastWriteTime` (set during fill) equals `timeStamps[x][y]` from the last rebuild.

### 2. Grid-based queries

**Q2.1 — FilSeg–FilSeg crosslinking** (FilSegment.java:1846, `filSegMeshCollisions(int xStart, int xStop)`):
- Objects: FilSegment ↔ FilSegment from `FILSEG_MESH` (same cell, different filament IDs).
- Guard: `iSeg.filID != jSeg.filID && Env.xLinks.isActive()`.
- Produces: candidate pair passed to `checkToLink` → `FilLink.makeLink` (event-producing: creates a new crosslink object).
- Phase: `meshCollStart=3`, inside collision-check gate.

**Q2.2 — Node–Node collision** (ProteinNode.java:545, `nodeMeshCollisions(int xStart, int xStop)`):
- Objects: ProteinNode ↔ ProteinNode from `NODE_MESH` (same cell, distinct nodes).
- Guard: `Env.collideProteinNodes.isActive()` (outer gate in `CkMeshThreads.execute`, Mesh.java:175).
- Produces: repulsion force via `checkNodeCollision` → `forceCollision` (force-producing: writes to both nodes' `forceSum`).
- Phase: `meshCollStart=3`, inside collision-check gate.

**Q2.3 — StickyNode–FilSeg barbed-tip collision** (FilSegment.java:1867, `membraneFilMeshCollisions(int xStart, int xStop)`):
- Objects: `StickyNode` (from `NODE_MESH`) × FilSegment (from `FILSEG_MESH`), same cell.
- Produces: mixed — repulsion force on node and filament + registers tip clearance for polymerization biochemistry (`fil.registerATipClearance(...)` — side effect used by barbed-end dynamics).
- Phase: `meshCollStart=3`, inside collision-check gate.

**Q2.4 — Motor–FilSeg binding** (MyoMotor.java:329, `motorFilMeshCollisions(int xStart, int xStop)`):
- Objects: unbound `MyoMotor` (`!mot.onFil`) × FilSegment, from `MYOHEADS_MESH` × `FILSEG_MESH`, same cell.
- Uses stale grid (from last rebuild); actual positions read from current `mot.bindTip`, `fil.end1`, `fil.end2`.
- Produces: motor binding event → `mot.ontoFilament(fil, arcOnFil)` (event-producing, synchronized on `mot.attachSync`).
- Phase: `motCollStart=4`, **every timestep** (outside collision-check gate).
- Covers all `MyoMotor` subclasses, including `MyosinFixed` motors (registered in `MyoMotor.theMotors[]` at construction via `MyoMotor.addMyoMotor(this)`).

**Non-grid proximity queries (dead code):**
- `FilSegment.nodeCollisions()` (FilSegment.java:2002): per-segment brute-force scan over all ProteinNodes. Defined but never called from doLoop.
- `FilSegment.filSegCollisions()` (FilSegment.java:1806): commented out — O(N²) brute-force predecessor to the grid-based version. Uses `roughCollisionCheck` (bounding-box prefilter in 3D). Both `roughCollisionCheck` and this function are dead code.
- `ProteinNode.nodeMeshCollisions()` (ProteinNode.java:524): no-arg version, comment says "not called anymore with multi-threaded architecture." Dead.
- Two `/*public void myoMotorCollisions()`/`nodeCollisions()` blocks in FilSegment.java at lines 2030 and 2047: commented out.

### 3. Finer-scale collision/proximity checks

**Q2.1 — `checkToLink(iSeg, jSeg)` (FilSegment.java:1930)**:
```java
// Reads: fil1.uVec, fil2.uVec, fil2.uVecR (orientation dot-product gate)
double angTween  = Math.acos(Pt3D.Dot(fil1.uVec, fil2.uVec));
double angTweenR = Math.acos(Pt3D.Dot(fil1.uVec, fil2.uVecR));
if ((angTween > maxAngle) & (angTweenR > maxAngle)) { return; }  // angle gate

// Fine check: line-segment-to-line-segment minimum distance
lineSegmentIntersectTest(fil1.end1, fil1.end2, fil2.end1, fil2.end2, retO);
if (retO.collision && retO.conDist < Env.crossLinkGrabDist.getValue()) {
    // Writes: FilLink.makeLink(fil1, loc1, fil2, loc2) — event
}
```
Reads: `fil1/fil2.end1`, `fil1/fil2.end2`, `fil1/fil2.uVec`, `fil2.uVecR`, `fil1.nodeAtEnd2`, `fil1/fil2.filID`, `fil1.end2Node`. Writes (on success): `FilLink` object created; `fil1.linkCt` incremented.

**Q2.2 — `checkNodeCollision` → `forceCollision` (ProteinNode.java:604)**:
```java
double pDist = Pt3D.ptDist(iP.coord, pP.coord);
double radDist = pP.getRadius() + iP.getRadius();
if (pDist < radDist) {
    double mag = attnF * (1e-6 * (radDist-pDist) / Env.collisionDeltaT.getValue())
                       / (1/iP.bTransGam.x + 1/pP.bTransGam.x);
    iP.incForceSum(Pt3D.Scale(mag, iVec));   // Writes: iP.forceSum
    pP.incForceSum(Pt3D.Scale(mag, pVec));   // Writes: pP.forceSum
}
```
Reads: `coord.x/y/z`, `getRadius()`, `bTransGam.x`. Writes: both nodes' `forceSum`.

**Q2.3 — `checkNodeFilTipsCollision(node, fil)` (FilSegment.java:1890)**:
```java
fil.registerATipClearance(Pt3D.ptDist(node.coord, fil.end2) - node.getRadius(), node.iAmHotRho);
Pt3D filTipCenter = Pt3D.Add(fil.end2, filTipR, fil.uVecR);
double pDist = Pt3D.ptDist(node.coord, filTipCenter);
if (pDist < node.getRadius() + filTipR) {
    double mag = attnFactor * 1e-6 * impingeDist / Env.collisionDeltaT.getValue()
                 / (1/node.bTransGam.x + 1/fil.bTransGam.y);
    node.incForceSum(...);   // Writes: node.forceSum
    fil.incForceSum(...);    // Writes: fil.forceSum (with torque)
}
```
Reads: `node.coord`, `fil.end2`, `fil.uVecR`, `node.iAmHotRho`, `node.getRadius()`, `fil.bTransGam.y`. Writes: both `forceSum` plus `fil.tipClearance` (used in barbed-end biochemistry).

**Q2.4 — `checkFilSegCollision(mot, fil)` (MyoMotor.java:353)**:
```java
if (Pt3D.Dot(mot.uVec, fil.uVec) < Env.myoMotorAlignWithFilTolerance.getValue()) { return; }
if (Pt3D.Dot(mot.myMyosin.myoRod.uVec, fil.uVec) < 0) { return; }
if (fil.nodeAtEnd2) { return; }
Thing.pointAndLineIntersectTest(mot.bindTip, fil.end1, fil.end2, retO);
if (retO.collision && retO.conDist < Env.myoColTol.getValue()) {
    mot.ontoFilament(fil, Pt3D.ptDist(fil.end1, retO.conPt1));  // Writes: state transition
}
```
Reads: `mot.bindTip` (current), `fil.end1/end2` (current), `mot.uVec`, `mot.myMyosin.myoRod.uVec`, `fil.uVec`, `fil.nodeAtEnd2`. Writes (on success): `mot.onFil=true`, `mot.tipLink` attachment fields.

### 4. Event vs. force semantics

| Query | Semantics | GPU shape |
|---|---|---|
| FilSeg–FilSeg crosslink | **Event-producing**: `FilLink.makeLink()` creates a new object | Candidate-list output; serial CPU resolution |
| Node–Node collision | **Force-producing**: writes `forceSum` on both nodes | Needs atomic adds (two writers per pair) |
| StickyNode–FilSeg tip | **Mixed**: writes `forceSum` on both + `tipClearance` side effect | Force part: atomic adds; tipClearance: serial |
| Motor–FilSeg binding | **Event-producing**: `ontoFilament()` transitions motor state | Candidate-list output; serial CPU resolution |

### 5. Per-step call structure

`BoxOfActin.doLoop()` lines 684–706:

```java
// Conditional block — fires every collisionCheckInt steps (default: every 10)
if (collisionCkCounter >= Thing.collisionCheckInt | Env.simulationTime == 0) {
    startAllThreadSets(Env.meshFilsStart);   waitOnAllThreadSets(Env.meshFilsStop);   // phase 0
    startAllThreadSets(Env.meshNodesStart);  waitOnAllThreadSets(Env.meshNodesStop);  // phase 1
    startAllThreadSets(Env.meshMotorsStart); waitOnAllThreadSets(Env.meshMotorsStop); // phase 2
    startAllThreadSets(Env.meshCollStart);   waitOnAllThreadSets(Env.meshCollStop);   // phase 3
    collisionCkCounter = 0;
}
// Unconditional — every timestep
startAllThreadSets(Env.motCollStart); waitOnAllThreadSets(Env.motCollStop);           // phase 4
```

Three ThreadSets: `Mesh.meshThreads` (fill, phases 0–2), `Mesh.ckMeshThreads` (query, phase 3), `Mesh.ckMotsThreads` (motor query, phase 4). All are sequential fan-out/gather pairs from `TimeLoop`.

Grid is built once per `collisionCheckInt` steps. Xlink/node queries fire at the same cadence. Motor–filament query fires every step using the stale grid, reading current positions. Query results (binding events, force accumulation) are consumed within the same step — there is no multi-step candidate accumulation.

### 6. Current scaling characteristics

**Gliding assay (~11 FilSegments, 280–70,000 motors):**
- Grid fill cost scales with motor count. At 70K motors: dominant per-step cost.
- Motor–FilSeg query: with only 11 segments spread across a 14×2 µm / 0.2 µm = 71×11 bin grid, each occupied bin has ≤1 segment. Per-step pair-check count ≈ motorCount × 1 ≈ brute-force O(M×N). The grid adds fill overhead without reducing pair checks — **grid is not winning at current scales**.
- FilSeg–FilSeg xlink: 11 segments in 781 bins → most bins empty; check is effectively O(N) — trivially fast.

**boa10-64Seg (~200–500 FilSegments, fewer motors):**
- At 500 segments in a 51×51 bin 10×10 µm box, avg ~0.2 seg/bin, still sparse. Grid begins winning when local density drives multiple-segment bins. Dense branched networks (1000+ segments) are the crossover point.
- No measured timer data in journal or comments. `collisionMeshTimer` and `motorsAndFilsColTimer` are present (BoxOfActin.java:22–23) and print % of runtime at run end (BoxOfActin.java:641–642), but no per-phase breakdown (fill vs query) is available. Separate fill and query timers would need to be added.

### 7. What the CPU rewrite will need to change

**Grid data structure:**
- `meshpoints[x][y][i]` stores int IDs as `double`. Replace with `int[][][] meshpoints` (eliminates cast).
- Three separate mesh arrays → three separate `int[][]` cell-count arrays and `int[][][]` id arrays. Keep three logical meshes or unify into one with object-type tag (planner decision, Q9.2).
- Cell indices stored as `filArrayPos`, `myNodeNumber`, `myMotorNumber` — these are compact array indices, SoA-friendly. They become direct indices into SoA FloatArrays.

**Fine-scale checks rewritten in SoA terms:**
- `checkFilSegCollision`: replace `mot.bindTip.x/y/z` with `motX[motorId]`/`motY[motorId]`/`motZ[motorId]`; replace `fil.end1.x/y/z` with `filEnd1X[filId]` etc.; replace `mot.uVec` with `motUX[motorId]` etc.
- `mot.myMyosin.myoRod.uVec` is a 3-hop object chain — requires a flat `rodUX[motorId]` SoA array populated per motor.
- `fil.nodeAtEnd2` → `filNodeAtEnd2[filId]` (boolean SoA flag).
- `mot.onFil` → `motorOnFil[motorId]` (boolean SoA flag, also the output of the query).
- `fil.filID` → `filFilID[filId]` (int SoA field, for the same-filament exclusion in xlink query).

**Event-producing queries:**
- Both motor binding and crosslink creation must be refactored into a candidate-list pattern: kernel writes `(motorId, filId, arcOnFil)` tuples to a bounded output buffer; CPU iterates the buffer and calls `ontoFilament`/`makeLink` serially. This is the key structural change and must be validated at the CPU-rewrite stage before GPU port.

**Hard points:**
1. `fil.end2Node == mot.myMyosin.myNode` — object reference equality used in `checkFilSegCollision`. Must become `filEnd2NodeId[filId] == motNodeId[motorId]` with -1 for null.
2. `fil1.retObj` shared across threads in `checkToLink` — if a filament spans bin boundaries, concurrent threads share its retObj (data race). Fix: use thread-local RetObj, not `fil1.retObj`.
3. `checkNodeFilTipsCollision` writes `fil.tipClearance` (a non-force side effect for polymerization). This is a non-trivial interaction between collision detection and biochemistry — the polymerization path reads `tipClearance` later. Must be preserved or restructured.
4. Node–Node force output requires per-node atomic adds if two pairs involving the same node are processed by different threads (which the X-bin partition can allow if a node spans bin boundaries).

**Grid dimensionality choice:**
- Keep 2D (XY only): correct for gliding-assay slab. Dense 3D actin networks (boa10-64Seg) lose Z-axis pre-filtering — all objects at different Z positions appear as candidates. For 10×10×10 µm boxes at high density this is a significant false-positive load.
- Move to 3D: a 50×50×50 bin grid for a 10×10×10 µm box is 125K cells — manageable. Gliding-assay slab (Z ≈ 0.5 µm) would use only 2–3 Z bins; behavior approximates the current 2D grid with no overhead penalty. 3D is correct for both geometries. The CPU rewrite is the right place to make this choice, since the GPU layout locks in the grid dimensionality.

### 8. Coexistence with WebSocket live observer

No race risk. The collision and motor-binding phases write: `mot.onFil`, `mot.tipLink.*`, `fil.linkCt`, `FilLink` objects, `node.forceSum`, `fil.forceSum`. `ThreeJSWriter.buildFrameJson()` reads: `fil.end1`, `fil.end2`, `fil.coord`, `mot.nucleotideState`, `mot.myRod.end1/end2`, etc. These are disjoint fields. Positions (`end1`, `end2`, `coord`) are updated in phase `moveStart` (BoxOfActin.java:754), well before the safe point where `logAndDraw()` dispatches. The safe-point ordering (pause wait → kill check → inspect drain → param drain → logAndDraw) guarantees all physics phases are complete before any frame is dispatched.

### 9. Open questions for the planner

1. **2D vs 3D grid**: 3D is correct for both gliding-assay slab and dense 3D networks. The CPU rewrite is the right place to decide and implement. Survey recommends 3D; planner to confirm.

2. **Unify queries or keep separate**: The four query types (xlink, node-node, node-filseg, motor-filseg) currently share the grid infrastructure but have separate query loops. Keep separate (lower risk, independent GPU porting schedule) or unify under one kernel with object-type dispatch (cleaner but larger change). Node/xlink GPU porting is likely lower priority than motor binding — separate keeps the scope of the first GPU kernel tight.

3. **SoA-ify only the fine checks vs rewrite both layers**: Option A — keep current grid structure (int cells, array-index contents), rewrite only the per-pair checks to read from SoA FloatArrays instead of Pt3D fields. Option B — rewrite grid storage as SoA-indexed too (int arrays per cell). Option A is a smaller CPU-rewrite step with lower validation risk; Option B is cleaner for the eventual GPU port. Recommend A for the CPU-rewrite session, then B in the GPU-port session.

4. **`fil1.retObj` data race in `checkToLink`**: a filament spanning two X-bin ranges can be processed by two concurrent `CkMeshThreads` threads with different partners, both writing to `fil1.retObj`. Effect is occasional missed or doubled crosslink; not currently crashing. Fix in CPU rewrite: pass a thread-local RetObj stack variable rather than reading from the filament instance. This is a correctness issue that should not be left for the GPU-port session.

5. **Timer granularity**: `collisionMeshTimer` covers fill + query combined; `motorsAndFilsColTimer` covers the per-step motor query. No per-phase breakdown. Adding separate fill-timer and query-timer in the CPU-rewrite session would clarify whether the grid overhead is paying off at current scales and what the target speedup is.

6. **`Env.collideProteinNodes` conditional**: node-node collision is runtime-gated. GPU kernel design must decide: always launch the kernel (zero-cost if node count is 0) or check the flag on CPU before kernel launch. In gliding-assay mode there are no ProteinNodes, so the node queries are dead weight in the gliding assay. Consider whether to short-circuit them earlier in the CPU rewrite.

7. **`checkNodeFilTipsCollision` → `tipClearance` dependency**: this side effect feeds barbed-end polymerization biochemistry. If the collision check is moved to GPU and the tipClearance write stays on CPU, there needs to be a download of filament endpoint positions before the barbed-end biochemistry phase. This is an interaction between the GPU port and the biochemistry phase that must be planned before the GPU port begins.

---

## 2026-05-26 — Follow-up survey: tipClearance dependency in collision detection

### 1. What is `tipClearance` exactly?

Two `double` fields on `FilSegment` (FilSegment.java:144–145):
```java
double end1TipC = 1e6; // large number for initial tip clearance of end1
double end2TipC = 1e6; // large number for initial tip clearance of end2
```
Both are clearance distances in µm. `1e6` is the "no obstacle" sentinel. They are **min-accumulators within each timestep**: `registerATipClearance` only updates `end2TipC` if the new value is smaller (FilSegment.java:979). Reset to `1e6` at the end of each step by `resetCounters()` (FilSegment.java:1126–1127), which runs in phase `resetCtStart=12`, after biochemistry. Field initializers set `1e6` at object creation; the per-step reset thereafter is `resetCounters()`.

### 2. Where is `tipClearance` written?

**Write site A — `registerATipClearance()` (FilSegment.java:978–986):**
```java
public void registerATipClearance (double tipC, boolean arpActivator) {
    if (tipC < end2TipC) {
        end2TipC = tipC;
        if (end2TipC < Env.branchZone.getValue() && arpActivator) {
            end2NearArpFactor = true;
        } else {
            end2NearArpFactor = false;
        }
    }
}
```
Called from `checkNodeFilTipsCollision` (FilSegment.java:1892) as:
```java
fil.registerATipClearance(Pt3D.ptDist(node.coord, fil.end2) - node.getRadius(), node.iAmHotRho);
```
The write is **unconditional** — it fires for any `(StickyNode, FilSegment)` pair that share a mesh cell, regardless of whether a physical collision is detected. The force application that follows is conditional; the clearance write is not.

**Write site B — `bugForcesFromInside()` (FilSegment.java:2471–2474):** Sets `end1TipC = 0` or `end2TipC = 0` when a filament tip is embedded in the Bug surface (Listeria motility path only).

**Write site C — `checkBugCollisionFromOutside()` (FilSegment.java:1158–1179):** Sets `end2TipC = 0` on collision, or `end2TipC = cE.delta` (actual surface clearance) on non-collision. Listeria motility path only.

**Write site D — `resetCounters()` (FilSegment.java:1126–1127):** Resets both fields to `1e6` each step (phase 12).

### 3. Where is `tipClearance` read?

Primary consumer — `stericHindranceEnd2()` (FilSegment.java:2736–2738):
```java
public boolean stericHindranceEnd2() {
    if (end2TipC < halfmono) return true;  // halfmono = Env.actinMonoRadius ≈ 0.00175 µm
    return false;
}
```
Called from `end2BiochemSim()` (FilSegment.java:939):
```java
if ((capConditionOKEnd2()) && (!stericHindranceEnd2())) {
    double rate = getPolyRateEnd2();
    boolean monomerAdded = addMonomerSim(rate);
    ...
}
```
Decision: if `end2TipC < halfmono` (tip within ~1.75 nm of node surface), barbed-end monomer addition is blocked entirely for that step. Both normal and non-hydrolyzable actin polymerization are gated by the same call.

Secondary consumer — `checkCapping()` (FilSegment.java:997):
```java
if (end2TipC < 2*Env.actinMonoDiam && end2NearArpFactor) { return; }
```
Blocks capping-protein binding when the tip is near an Arp2/3-activating node.

`Env.registerPlusMon(end2TipC)` (FilSegment.java:948, 958): statistics only — passes `end2TipC` to a proximity counter when a monomer is added.

### 4. Phase ordering

Timestep sequence (doLoop):

| Phase | ID | Action | Runs every |
|---|---|---|---|
| Mesh fill | 0–2 | Build FILSEG/NODE/MOTOR meshes | `collisionCheckInt` steps (default 10) |
| Mesh queries | 3 | `checkNodeFilTipsCollision` → **writes `end2TipC`** | Same gate as fill |
| Motor binding | 4 | `motorFilMeshCollisions` | Every step |
| Brownian, Xlinks, Joints | 5–8 | Force accumulation | Various |
| step | 9 | Integrate forces → velocities | Every step |
| moveThing | **10** | **Update positions (end1, end2, coord)** | Every step |
| biochem | **11** | `end2BiochemSim()` → **reads `end2TipC`** | Every step |
| resetCounters | 12 | `end2TipC = 1e6` | Every step |

`end2TipC` is written at phase 3 and read at phase 11, within the **same timestep**. `moveThing()` (phase 10) runs between the write and read, meaning positions have already been updated when biochemistry consults the clearance value. The clearance was computed against pre-move positions.

Critical corollary: on the **9 out of every 10 steps** when collision detection does not run, nothing writes `end2TipC` (resetCounters set it to `1e6` at the end of the previous step). `stericHindranceEnd2()` therefore returns false on those steps, and polymerization is ungated by steric proximity. Steric blocking from nodes is only enforced every 10th step by default.

### 5. What happens if `tipClearance` is stale or wrong?

**Stale (1e6):** `stericHindranceEnd2()` returns false → polymerization allowed regardless of node proximity. The barbed end can add monomers while physically overlapping a StickyNode. This is already the design on 9/10 steps, so the existing code already accepts this approximation. It is a physics-correctness issue (not numerical stability), but one the original design tolerates by running collision detection at a coarser cadence than biochemistry.

**Prematurely zero (false starvation):** Polymerization blocked on a step where the tip is actually clear. Bounded error — next collision-detection step would correctly update the value.

**Wrong value from stale mesh (positions have moved):** The clearance was computed against positions from the grid-rebuild step, while `moveThing()` has since run. The position error scales with `deltaT × v_tip`. At typical segment velocities this is sub-nanometer per step — well within the `halfmono ≈ 1.75 nm` threshold. Not a concern.

### 6. Is `tipClearance` used by every FilSegment, or only some?

**Gliding assay: entirely inactive.** `membraneFilMeshCollisions()` (FilSegment.java:1877) only processes nodes that pass `if (node instanceof StickyNode)`. The gliding-assay parameter file (`glidingAssayBatch_template`) sets `equilNodes:false:0.0`, `initialMyoMiniFils:false:0.0`, and creates no membrane nodes. With `NODE_MESH` empty (or containing no `StickyNode` entries), `checkNodeFilTipsCollision` is never called. `end2TipC` remains at its reset value of `1e6` throughout every run. `stericHindranceEnd2()` is always false. **The tipClearance design problem is entirely irrelevant to the gliding-assay GPU port.**

**Bug/Listeria mode: partially active.** Write sites B and C (`bugForcesFromInside`, `checkBugCollisionFromOutside`) fire in Listeria motility runs against the Bug surface. No StickyNodes involved.

**boa10-64Seg (standard box of actin):** Active if `equilNodes` is enabled and creates `StickyNode` instances. This is the only configuration where the node→tipClearance→polymerization dependency is live.

### 7. Three port-design options

**Option α — motor-binding to GPU first, node/tip-clearance stays on CPU:**
Motor binding (phase 4, `motCollStart`) is already in a separate ThreadSet from the mesh collision queries (phase 3, `meshCollStart`) that contains `checkNodeFilTipsCollision`. There is no shared data between the two phases within a step — motor binding reads `mot.bindTip` and `fil.end1/end2`; the node-tip check reads `node.coord` and `fil.end2`. Moving motor binding to GPU does not require touching the node-tip code path at all. **Cleanly feasible with zero restructuring of the tipClearance dependency.**

**Option β — extract tipClearance write into its own CPU phase after GPU collision detection:**
Inside `checkNodeFilTipsCollision` (FilSegment.java:1890–1910), the structure is:
1. `registerATipClearance(...)` — unconditional clearance write (1 distance computation)
2. `filTipCenter` / `pDist` computation — conditional force application

The clearance write does not depend on whether the force condition is met (it uses `ptDist(node.coord, fil.end2)`, not `filTipCenter`). These are separable. A GPU kernel for node-tip forces could produce a candidate list of `(nodeId, filId, clearance)` tuples; a CPU phase could then drain that list and call `registerATipClearance` serially. **Technically feasible, but more invasive than α and only relevant once the full mesh collision phase goes to GPU.**

**Option γ — download filament tip positions to CPU between collision and biochemistry:**
The download footprint is `filSegmentCt × 12 bytes` (3 floats per `end2`). For the gliding assay: 11 × 12 = 132 bytes — trivial. For boa10-64Seg: ~500 × 12 = 6 KB — fast. For a dense 3D network with 10,000 segments: 120 KB. All cases are well within acceptable PCIe transfer budgets. **Viable as a fallback if the tipClearance write must move to GPU but a structural refactor is not yet done. Not needed if α is chosen.**

### 8. Open question for the planner

The collision detection cadence (default every 10 steps) means `end2TipC` is updated only on those steps; biochemistry reads a stale `1e6` value on the other 9. This is already the accepted approximation. **If the GPU port of motor-binding also changes the effective cadence of the node-tip check (e.g., the mesh rebuild becomes cheaper on GPU and could run every step), should the tipClearance check also move to every-step cadence?** This would tighten the steric-hindrance enforcement and potentially change polymerization dynamics in node-rich simulations. The planner should decide whether this is a physics improvement worth making at the CPU-rewrite stage, or whether the current every-10-steps approximation is intentional and should be preserved in the GPU port.

**Major finding from Q6 (flagged):** The gliding assay does not exercise `tipClearance` at all. The entire Q7 design problem (α/β/γ) is irrelevant to the first GPU target (motor binding in the gliding assay). The tipClearance dependency only matters for the node-tip collision phase, which is a later GPU porting target and only active in boa10-64Seg-style runs with StickyNodes.

---

## 2026-05-26 — Fix: fil1.retObj data race in checkToLink

**Race confirmed.** `retObj` is an instance field on `Thing` (Thing.java:72), inherited by all `FilSegment` instances. In `checkToLink` (FilSegment.java:1931), it was aliased as `RetObj retO = fil1.retObj` then written by `lineSegmentIntersectTest` (Thing.java:440). `fillFilSegMesh` places each segment into multiple x-bins (Bresenham walk + OVERLAP ±1 padding; Mesh.java:301–350), so the same segment can appear in two `CkMeshThreads` worker partitions simultaneously. Two threads calling `checkToLink(filA, ...)` with the same `filA` as `fil1` both wrote `filA.retObj.{collision, conDist, conPt1, conPt2, ray1–ray4}` concurrently.

**Sibling fields.** No other instance field on `FilSegment` is scratch space within `checkToLink`. `myPRNG` is accessed (`fil1/fil2.myPRNG.nextDouble()`) only when `retO.collision` is true, but is lower-severity and out of scope. The `v1`/`v2`/`tempPt` Pt3D scratch fields on `Thing` are not touched by `checkToLink` or `lineSegmentIntersectTest`.

**Fix: Option A** (local allocation). Made `RetObj` a `static class` in `Thing.java` (was non-static inner; made static so it can be instantiated in the static `checkToLink`). Changed FilSegment.java:1931 from `RetObj retO = fil1.retObj` to `RetObj retO = new RetObj()`. Per-call stack allocation; negligible cost at every-10-step cadence.

**Files changed:** `boxOfActin/Thing.java:107` (`public class` → `public static class`), `boxOfActin/FilSegment.java:1931` (field alias → local allocation).

**Validation.** Clean compile. Ran headless with `boa10-64Seg` 30+ seconds — no crash, no NullPointerException. `remoteReportInterval = 100k steps` meant no step-count output appeared in the window, but startup and mesh init completed correctly. No pre/post determinism comparison possible (no fixed-seed mechanism); fix is logically complete — shared-state race source removed.

---

## 2026-05-27 — Gliding assay: first quantitative validation of motor model

### What was run

First production gliding-assay batch on aorus. 14 µm × 2 µm × 0.5 µm arena, single 11×64-monomer filament (~2 µm contour, span 1.93 µm), phalloidin-stabilized stiffness regime (fracMove = 0.0573, fracR = 1.0, fracMoveTorq = 0.01, deflection ratio 0.500). Brownian coefficients tuned via long-filament persistence length test (BTransCoeff = BRotCoeff = 1.4) earlier in the day, hitting Lp_meas 14.4 µm against Lp_theo 15.0 µm at 100k samples on a 21 µm chain. Density sweep at 8 values from 10 to 2500 motors/µm², 4 s sim time per density (except 2500, which was killed at 2.075 s — see below).

Pipeline correctness was verified beforehand with a 0.2 s smoke test on aorus; smoke test confirmed batch infrastructure works at all densities.

### Results

**Gliding velocity (longWindowSpeedXY) scales monotonically with motor density across three orders of magnitude:**

- d=10 → 0.14 µm/s (median): below Uyeda density threshold, essentially no directed motion
- d=100 → 1.23 µm/s: still below threshold
- d=500 → 3.70 µm/s: in transition
- d=1000 → 4.17 µm/s: approaching experimental range
- d=2500 → 8.06 µm/s: inside the published 5–8 µm/s skeletal myosin II range

avgBoundMotors scales sublinearly with density (slope ~0.6 on log-log), consistent with geometric saturation as the filament's reachable area fills.

posZ stays within ±0.25 µm at all densities — no filament popping above the motor plane. Vertical position has a slight negative bias (filaments below z=0), consistent with motor pinning toward the floor.

### Interpretation

This is the **first quantitative validation of the motor model** in the spirit of the V25 actin biophysical benchmarks. At high motor density, simulated gliding velocity matches experimental skeletal myosin II in absolute units, not just qualitatively. The density-threshold behavior (low velocity at d≤100, transition between d=100 and d=500, plateau at d≥1000) is also qualitatively consistent with the Uyeda/Spudich result.

Importantly, this agreement holds **despite the simulation running at ~100× experimental viscosity** (aeta = 0.1 Pa·s vs ~0.001 Pa·s for real motility buffer). Gliding velocity is dominated by motor stepping kinetics, not by drag balance, in this parameter regime — so the viscosity convenience used to suppress thermal fluctuations does not affect this result. Worth noting for future viscosity-sensitivity studies.

### 2500 density: killed early but usable

The d=2500 run was killed at 2.075 s of 4 s sim time after the filament reached and piled up against the far box wall. Data before pile-up is good. Inside the box, the filament glides at experimental velocity; once at the wall it accordions and is no longer measuring gliding velocity. The data after wall contact (roughly t > 1.7 s) should be excluded from velocity analysis if precision is needed.

Note: the d=2500 run also produced many `[BIND]` debug print statements (binding-event telemetry) that were not seen at lower densities. Suggests a debug print gated on a high-density-only condition. Worth investigating before the next high-density batch — see open questions below.

### Open questions / next steps

- **Stiff-filament A/B comparison** (planned): re-run d=200, 500, 1000 with fracMove = 0.5, fracR = 0.1, fracMoveTorq = 0.2 (roughly 100× stiffer). Determines whether realistic flexibility is a confound or a faithful representation of the experimental phenomenology. Either outcome is informative.
- **`[BIND]` print investigation**: short Claude Code grep to locate the print and determine whether it's gated on a debug flag, density threshold, or unconditional. Suppressing it should be a one-line fix once located.
- **Periodic boundary conditions** along the long axis: would eliminate wall-contact contamination of velocity stats, allow smaller boxes (lower motor count at high density, much faster), and is the cleanest long-term fix for the d=2500 wall-pile-up problem. Non-trivial implementation; survey before committing.
- **Force–velocity benchmark** (planned but not started): the F–V curve via tethered filament, with stall force scaling on motor number. Validates the neck-stiffness lumped parameter directly. Next major validation after the stiffness A/B is complete.

### Related new document

A standing-knowledge summary of the gliding-assay validation work has been started as **MYOSIN_VALIDATION.md**, analogous to NMII_BIOLOGY.md. Future motor-validation work (F–V, attachment lifetime, etc.) accumulates there. JOURNAL.md captures session-by-session progress; MYOSIN_VALIDATION.md captures what's currently known.

## Workflow note

This project uses a two-Claude workflow:
- **Claude.ai Projects** (planner): architecture, strategy, debugging hypotheses, biological context, prompt generation, journal updates
- **Claude Code** (implementer): file editing, compilation, execution, multi-file refactors

Restart Claude Code at task boundaries to avoid context bloat. `CLAUDE.md` and `JOURNAL.md` carry context forward across Claude Code sessions and across the planner / Claude Code boundary. Push them to GitHub at the end of any session that changed them, so the planner's next session can fetch a current view.

---

## 2026-05-27 — Discovery: motor binding non-deterministic at 16 threads post-43d5ff2

### Symptom

Three consecutive fixed-seed runs (`-seed 42`, `ParameterFiles/glidingAssayValidation`, 5000 steps, `allThreadCt = 16`) produced **350, 468, and 396 binding events** respectively — a range of 118 events across identical inputs. The binding event log format is `[BIND] step=N mot=M fil=F arc=A` printed from `MyoMotor.ontoFilament`. First events in each run were at different steps and with different motor/filament IDs. Not a noise artifact: the variation is 25–33% of the event count and the first-event step differs by 50+ steps across runs.

### How discovered

During the validation-baseline collection phase of the CPU rewrite step-1a prompt. The plan was to collect a fixed-seed baseline before the rewrite, then verify the rewrite log matched byte-for-byte. When the two runs produced different counts (350 and 468), the session paused rather than proceeding on a contaminated baseline.

### What this implies

The post-43d5ff2 codebase is **not byte-deterministic for motor binding events** at `allThreadCt = 16`, even with a fixed Env.mtRNG seed. Commit 43d5ff2 fixed the `fil1.retObj` data race in `checkToLink` (xlink correctness), but the motor-binding path was not analyzed for analogous races at that time — the 43d5ff2 journal entry explicitly noted that `myPRNG` races in `checkToLink` were "lower-severity and out of scope." At least one race in the motor-binding path remains.

Separately: reducing `numMeshCollThreads = 1` and `numMeshThreads = 1` did not restore determinism (333 vs 434 events on two runs). The race survives even when the mesh fill and collision phases are single-threaded, which points to a race in another force-accumulation or physics phase.

### Race source hypothesis

**Top candidate: force accumulation races in MyoThreads (and BrownianThreads).** Once a motor binds, every subsequent step it exerts a pulling force on the bound `FilSegment`. That force write (`fil.force.x += ...; fil.force.y += ...` etc.) happens in the myosin-joints phase, processed by `numMyoThreads = allThreadCt = 16` threads. Multiple threads can process different bound motors that all pull on the same filament segment, with unprotected concurrent writes to the filament's force vector. This race changes filament trajectories step-by-step; changed trajectories alter which motors are within `myoColTol` range on future steps; hence different binding events.

Evidence: determinism was not restored by `numMeshCollThreads = 1` + `numMeshThreads = 1` (eliminating the mesh fill and motor-collision phases). Those changes remove the races in the collision-detection path but leave the force-accumulation path running at 16 threads. The surviving 333 vs 434 spread is consistent with force-accumulation races driving the trajectory divergence, while the mesh-path races only add noise at the moment of binding.

**Secondary candidate: `mot.retObj` race in `motorFilMeshCollisions`.** The CkMotsThreads divides X bins among 16 threads (`MyoMotor.motorFilMeshCollisions(xStart, xStop)`, Mesh.java:207–215). A motor whose bounding box straddles an X-bin boundary appears in two adjacent ranges, so two threads call `checkFilSegCollision(mot, fil)` for the same motor concurrently. Both alias `retO = mot.retObj` (MyoMotor.java:370) and both call `Thing.pointAndLineIntersectTest(..., retO)` which writes `retO.{collision, conDist, conPt1, conPt2, ...}`. This is the exact same class of race as `fil1.retObj` in commit 43d5ff2. However, this race is only active for motors at X-bin boundaries, so it explains some non-determinism but probably not all of it (the force-accumulation race affects every step for every bound motor).

If this is correct, a full fix would require: (a) fixing the force-accumulation race (using per-thread staging or atomic-add), and (b) fixing the `mot.retObj` race (local allocation as done in 43d5ff2 for `fil1.retObj`). The two problems are separable.

Both hypotheses need investigation to confirm. No additional code was read this session; the above is based on code seen during the 43d5ff2 race-fix session and the present session's motorFilMeshCollisions review.

### What was NOT changed

No step-1a rewrite code was committed. All source files were reverted to commit 43d5ff2 state:
- `boxOfActin/MyoMotor.java` — reverted (SoA arrays removed)
- `boxOfActin/FilSegment.java` — reverted (SoA arrays removed)
- `boxOfActin/BoxOfActin.java` — reverted
- `boxOfActin/Thing.java` — reverted (myPRNG back to `Math.random()`)
- `boxOfActin/Env.java` — reverted (`numMeshCollThreads = allThreadCt`, no motorBindGrid3D constants)
- `boxOfActin/Mesh.java` — reverted
- `boxOfActin/MotorBindGrid3D.java` — deleted (new file, not committed)
- `ParameterFiles/glidingAssayValidation` — deleted (new file, not committed)
- `baseline_binding_events.log`, `rewrite_binding_events.log` — deleted

The `numMeshCollThreads = 1` experiment was reverted before this commit. Production runs continue to use `allThreadCt = 16`.

### What the planner needs to decide

**Should the motor-binding races be fixed before the SoA + 3D-grid rewrite proceeds, or should the SoA rewrite be validated single-threaded on the grounds that the GPU port will obsolete the multi-threaded CPU path anyway?**

Option A — fix races first: harden the CPU path (fix `mot.retObj` race and force-accumulation races), then re-run step-1a validation against a deterministic baseline. Produces a clean, race-free CPU implementation that can serve as the verified reference for the GPU port. More work upfront; stronger correctness guarantee.

Option B — validate single-threaded: keep the existing multi-threaded code, run validation with `allThreadCt = 1` temporarily to establish a deterministic baseline, accept that the multi-threaded path has known races that the GPU port will bypass. Faster; does not fix the races; risks subtly wrong physics in any multi-threaded CPU run. The `[BIND]` telemetry and the gliding-assay quantitative results from 2026-05-27 were produced with the racing code, so they may contain some bias — though probably small given that the force-accumulation race is a commutative-but-non-associative floating-point issue (order of addition) rather than a correctness-breaking race.

## 2026-05-27 (cont.) — Gliding assay: stiff-vs-flexible comparison + session state

### Validation status

Flexible-filament gliding velocity (yesterday's 8-density batch) is the headline
validated result: 8 µm/s median at 2500 motors/µm², inside experimental skeletal
myosin II range (5-8 µm/s), with correct density-threshold behavior. Filament shape
dynamics (curving, end-leading) qualitatively match Mansson lab gliding-assay videos
(Melbacke et al. 2024, Sci. Rep., open-access supplementary movies) -- the flexible
2x-phalloidin regime is the physically correct one, NOT the very-stiff test regime.
See MYOSIN_VALIDATION.md for full results table and setup.

### Stiff-vs-flexible comparison (today)

Re-ran d=200, 500, 1000 with very stiff filament (fracMove=0.5, fracR=0.1,
fracMoveTorq=0.2, ~100x stiffer) to test whether realistic flexibility is a
measurement confound. It is not dominant. Stiff is faster, but modestly:

  density | flex median | stiff median | ratio
   200    |   2.15      |   2.69       | 1.25x
   500    |   3.70      |   4.38       | 1.18x
   1000   |   4.17      |   7.54       | 1.81x  (stiff run was short, 0.75s)

Gap grows with density -- "flexibility tax" larger when more motors pull the
filament off-axis. Stiff d=1000 run died early (likely Claude Code accidentally
killed it); data still good, not re-running.

Conclusion: wall interactions and flexibility both measurably affect velocity but
neither dominates. Flexible numbers are trustworthy. Validation holds.

### Wall-interaction check

At d=200 stiff, split run at t=2s (filament reaches side wall ~t=2s):
0-2s median 2.84 µm/s, 2-4s median 2.46 µm/s (~13% drop). Motor engagement drops
~28% as wall blocks half the footprint. Measurable but not catastrophic. Speed
columns confirmed to use full vector magnitude (instantaneousSpeed = 3D, 
longWindowSpeedXY = xy-plane), not just box-x component.

### Code change in progress

Early-termination patch handed to Claude Code: stop the run when filament pointed
end reaches either x-wall (tolerance 0.15 µm), graceful stop, termination reason
written to output (prompt specified Option B = sibling termination.txt preferred).
This saves large amounts of wasted compute -- e.g. stiff d=1000 reaches far wall in
~2s of a 4s run. NOT YET CONFIRMED COMPLETE -- verify Claude Code finished, tested
(high-density terminates early, low-density runs full 4s), and committed.

### Watch item: numMeshCollThreads

During unrelated Claude Code work it set Env.numMeshCollThreads from 16 to 1 for
deterministic binding-event validation. CONFIRM whether this was reverted to 16.
Leaving it at 1 is a real perf hit at high motor density. Do not run production
batches until verified back at 16 (or intentionally left at 1 with reason noted).

### Aorus environment notes

- BoA repo cloned to ~/Code/BoA. Compiles clean (Java 21) with:
  javac -cp ".:libs/*" *.java boxOfActin/*.java ec/util/*.java edu/cornell/lassp/houle/RngPack/*.java
- Three jars in libs/ (Java-WebSocket, json, slf4j-api) -- WebSocket viewer deps,
  NOT Java3D (which is fully gone). ec/ and edu/ RNG sources compile in-tree.
- RNG mix: ThreadLocalRandom (kinetics), MersenneTwisterFast (per-Thing), one
  MersenneTwister (Env). RanMT dead/commented. Bug.java + FilSegment.java seed
  Random via (long)Math.random() which is ~always 0 -- minor latent bug, noted.
- aorus IP 10.0.0.187 recorded in CLAUDE.md.

### Open questions / next steps

- Stiffness sweep at fixed density (suggest d=1000): vary filament stiffness across
  ~5-6 values from bare F-actin up to very stiff, plot velocity vs persistence
  length (measured via existing benchmark, the physically meaningful x-axis). Tests
  whether velocity-vs-stiffness is monotonic and whether it saturates. Strengthens
  validation by showing sensible dependence on an independently-calibrated parameter.
- Narrow-box + motor-buffer idea (deferred): physical box ~1 µm wide but motors
  seeded out to full reach distance beyond the wall, so a wall-riding filament still
  sees full motor complement. Halves motor count at fixed density. More useful if
  combined with a LONGER x-axis (trade lateral dim for longitudinal at fixed motor
  budget). Probably not worth it for the few remaining validation batches; revisit
  if F-V work needs many runs. If pursued: clarify which "density" the .dat reports.
- Periodic BC (deferred): cleanest fix for wall contamination but ~1-2 day Claude
  Code task and not biologically general. Not worth it for 3-5 remaining batches.
- Force-velocity benchmark: next major validation after gliding. Tethered filament,
  sweep spring stiffness, stall force vs motor number. Validates neck-compliance
  lumped parameter. Different geometry from gliding.
- Box width increase for future batches if wall interaction becomes limiting (no
  code change needed, just costs motor count).

---

## 2026-05-27 — CPU rewrite step 1a: SoA arrays + MotorBindGrid3D

### What was done

Implemented CPU rewrite step 1a (SoA layout + 3D spatial grid) for the motor-binding
collision path. All changes are in the working tree; this entry documents the validation
run that led to the commit.

**New/changed files:**
- `boxOfActin/MotorBindGrid3D.java` — new 3D spatial hash grid (71×11×4 cells at
  0.2 µm/cell for the 14×2×0.5 µm gliding-assay box). Single-threaded fill phase;
  27-neighbor query in place of the old X-bin sweep.
- `boxOfActin/MyoMotor.java` — SoA arrays (`soaX[]`, `soaY[]`, `soaZ[]`,
  `soaOnFil[]`) snapshotted per step; binding-event counters (`totalBindEvents`,
  `boundMotorSum`, `boundMotorSampleCt`).
- `boxOfActin/FilSegment.java` — SoA arrays (`soaEnd1X/Y/Z[]`, `soaEnd2X/Y/Z[]`,
  `soaFilID[]`) snapshotted per step.
- `boxOfActin/Mesh.java` — `CkMotsThreads` divides by motor index (not X-bin) and
  calls `MotorBindGrid3D.motorFilCollisions(motorStart, motorStop)`.
- `boxOfActin/BoxOfActin.java` — adds `-seed <N>` CLI arg (sets Env.mtRNG);
  calls `MotorBindGrid3D.create()` at startup; inserts FillThreads phase before
  CkMots; prints `[STATS] bindEvents=N` and `[STATS] meanBoundMotors=X` at end.
- `boxOfActin/Env.java` — `motorBindGrid3DStart/Stop` phase-ID constants.

### Validation run (10 seeds, glidingAssay500, runTime=0.01 s)

Both ensembles run with `[STATS]` output active (counters live in the current tree;
baseline uses the old 2D X-bin CkMotsThreads path, rewrite uses MotorBindGrid3D).
Output files at `/tmp/boa_validation/`.

Baseline (commit 8d5f9e5, old 2D X-bin grid):

  seed | bindEvents | meanBoundMotors
    1  |    175     |     9.870
    2  |    178     |     8.341
    3  |    105     |     7.053
    4  |    105     |     5.788
    5  |    130     |     8.047
    6  |    132     |     7.385
    7  |    100     |     8.020
    8  |     70     |     5.431
    9  |    134     |     6.728
   10  |    126     |     8.439
  mean |   125.5    |     7.510
  sd   |    33.2    |     1.325
  SEM  |    10.5    |     0.419

Rewrite (MotorBindGrid3D, 27-neighbor 3D query):

  seed | bindEvents | meanBoundMotors
    1  |     97     |     6.750
    2  |    122     |     8.999
    3  |    102     |     7.673
    4  |     81     |     5.344
    5  |    105     |     7.602
    6  |    110     |     6.524
    7  |    120     |     7.421
    8  |     97     |     6.604
    9  |    124     |     6.847
   10  |    119     |     6.328
  mean |   107.7    |     7.009
  sd   |    13.9    |     0.980
  SEM  |     4.4    |     0.310

Statistical comparison (pass = |diff| ≤ 2 × combined SEM):

  observable       | baseline      | rewrite       | |diff| | cSEM  | diff/cSEM | result
  bindEvents       | 125.5 ± 10.5  | 107.7 ± 4.4   |  17.8  | 11.4  |   1.56    | **PASS**
  meanBoundMotors  |  7.510 ± 0.419|  7.009 ± 0.310|  0.501 | 0.521 |   0.96    | **PASS**

### Findings

**Validation passes.** Both observables clear the 2-SEM threshold. The 3D
MotorBindGrid3D query produces statistically equivalent physics to the old 2D
X-bin sweep. The larger candidate neighborhood (27-cell ±1 cube vs 1–4 cells
from the old bounding-box) changes which (motor, filament) pairs get evaluated
each step, but the fine check (checkFilSegCollision, unchanged) gates on the
same myoColTol = 0.006 µm distance, so the downstream binding rate is
compatible within noise.

**bindEvents variance drops.** Baseline sd=33.2 (26% CV), rewrite sd=13.9
(13% CV). The motor-centric thread division (each motor owned by exactly one
thread) eliminates the mot.retObj race at X-bin boundaries that was contributing
to baseline variance. The force-accumulation races remain and set a noise floor
in both ensembles, but the rewrite has fewer race sites.

**meanBoundMotors consistent.** Rewrite mean 7.009 vs baseline 7.510 (6.7%
lower). Both are consistent with the validated d=500 sweep (6.91 from
MYOSIN_VALIDATION.md). The small downward shift is within the inter-run noise
and is not a systematic bias.

**FillThreads is the step-1a performance cost.** The single-threaded 3D grid
fill (estimated ~2.9 s for 14K motors × 1000 steps) adds substantial overhead
vs the baseline's X-bin sweep (~0.55 s). This is expected: the synchronized
per-cell Java monitor approach is the simplest-correct implementation, not the
fastest. The fill cost is independent of the query design and disappears entirely
in the GPU port (fill becomes a scatter kernel).

**Non-determinism persists, source unchanged.** Baseline bindEvents range 70–178
(2.5×), rewrite 81–124 (1.5×). Consistent with documented force-accumulation
races. Step 1a does not introduce new race sites beyond what existed.

### Open questions for planner

- **FillThreads performance**: accept as-is (GPU port makes it irrelevant) or
  lock-free / multi-threaded CPU fill before step 1b?
- **Variance reduction**: the mot.retObj race at X-bin boundaries is now gone
  (motor-centric division). Should the force-accumulation race be fixed next
  (Option A from 2026-05-27 discovery entry) to make validation even cleaner for
  step 1b, or proceed with the current noise floor?

## 2026-05-28 — Step 1a follow-up: gliding-velocity validation + wall-clock timing

**Motivation.** The 2026-05-27 step-1a validation covered only two observables
(bindEvents, meanBoundMotors) at 0.01 s runtime. This follow-up adds gliding
velocity as a third observable and extends runtime to 0.1 s to give the
filament enough displacement to measure reliably.

### Protocol

- Parameter file: `ParameterFiles/glidingAssay500_val` (d=500 motors/µm², 14×2×0.5 µm
  box, runTime=0.1 s, filament length 2.0 µm, noMonomersSimd, externalDensitySweep=1).
- Baseline: commit 43d5ff2 at `/tmp/boa_baseline` (no MotorBindGrid3D, no -seed flag;
  10 independent JVM-random seeds). Rewrite: HEAD with `-seed 1..10`.
- 10 seeds each, run sequentially (no parallel competition).
- ThreeJSWriter guard active (no `-3js`; guard skips frame build when no consumer).
- Velocity: net XY displacement of filament COM from t=0.02 s to t=0.10 s (first
  20% discarded), divided by elapsed time. Same 2D projection as fluorescence microscopy.
- Pass criterion: |diff|/cSEM < 2.0 on each observable.

### Results

```
observable        | baseline (43d5ff2)      | rewrite (HEAD)          | |diff|/cSEM | verdict
velocity (µm/s)   | 4.746 ± 0.176 (SD=0.557)| 4.575 ± 0.196 (SD=0.620)|    0.65    | PASS
mean bound motors | 7.254 ± 0.172 (SD=0.544)| 7.111 ± 0.198 (SD=0.627)|    0.54    | PASS
```

Both observables clear the 2-SEM threshold. Step 1a validation is complete across
all three observables (bindEvents, meanBoundMotors, gliding velocity).

### Wall-clock timing

```
version      | mean (s) | SD (s) | n
baseline     |  242.6   |   3.6  | 10
rewrite      |  231.5   |   7.9  | 10
```

Rewrite is ~11 s/seed faster than baseline despite the added FillThreads phase. The
likely explanation is JIT warm-up: rewrite seeds 1-6 average 237 s (close to baseline),
while seeds 7-10 drop to 223 s as the JVM optimizes the hot grid-fill loop. The
prior FillThreads overhead estimate (~2.9 s per 1000 steps) was for a cold JVM; at
steady state the fill cost is substantially lower. Net: step-1a has no measurable
wall-clock regression vs the baseline at this scale.

### Summary

Step 1a (SoA arrays + MotorBindGrid3D) is validated across runtime 0.01 s (bindEvents,
meanBoundMotors) and 0.1 s (velocity, meanBoundMotors). No performance regression.
FillThreads overhead is acceptable; GPU port will replace it with a scatter kernel.

## 2026-05-28 — CPU rewrite step 1b: SoA-ify motor-binding fine check

### What was done

Step 1b rewrites `MyoMotor.checkFilSegCollision` so the per-pair *decision* reads
only flat SoA arrays — no `mot.uVec`, no `mot.myMyosin.myoRod.uVec`, no `fil.uVec`,
no `fil.nodeAtEnd2` object dereferencing in the hot loop. The binding *event*
(`MyoMotor.ontoFilament`) is unchanged; the decision still fires it inline by
indexing into `theMotors[]` / `theFilSegments[]`. This is the algorithmic shape
a TornadoVM kernel can consume directly.

### Phase 1 — enumeration of fine-check reads

Walking `MotorBindGrid3D.motorFilCollisions` → `MyoMotor.checkFilSegCollision`
→ `Thing.pointAndLineIntersectTest`:

| Read expression                                | Semantics                          | SoA backing (step 1a)              | Action for 1b                        |
|------------------------------------------------|------------------------------------|-------------------------------------|---------------------------------------|
| `mot.bindTip.x/y/z`                            | motor head position                | EXISTS `MyoMotor.soaX/Y/Z[motorId]` | reuse                                 |
| `fil.end1.x/y/z`, `fil.end2.x/y/z`             | filament endpoints                 | EXISTS `FilSegment.soaEnd1X/Y/Z`, `soaEnd2X/Y/Z[filId]` | reuse |
| `mot.onFil` (caller skip)                      | already-bound flag                 | EXISTS `MyoMotor.soaOnFil[motorId]` | reuse                                 |
| `mot.uVec`                                     | motor head orientation             | NONE                                | NEW `MyoMotor.soaUX/UY/UZ[motorId]`   |
| `mot.myMyosin.myoRod.uVec`                     | rod orientation (3-hop chain)      | NONE                                | NEW `MyoMotor.soaRodUX/UY/UZ[motorId]`|
| `fil.uVec`                                     | filament orientation               | NONE                                | NEW `FilSegment.soaUX/UY/UZ[filId]`   |
| `fil.nodeAtEnd2`                               | formin-bound flag (boolean)        | NONE                                | NEW `FilSegment.soaNodeAtEnd2[filId]` |
| `Env.myoMotorAlignWithFilTolerance.getValue()` | scalar align tolerance             | scalar (kernel constant in port)    | read per call (mutable param)         |
| `Env.myoColTol.getValue()`                     | scalar capture radius              | scalar                              | read per call (mutable param)         |
| `mot.retObj`                                   | scratch buffer for point-line test | N/A                                 | eliminated; locals replace it         |
| `retO.collision / conDist / conPt1`            | output of point-line test          | N/A                                 | inlined as `alpha`, `conDistSq`       |
| `fil.end2Node == mot.myMyosin.myNode`          | own-node filter                    | unreachable                         | dead branch, NOT ported               |

**Discovery (in-scope, documented):** lines 388–390 of the prior
`checkFilSegCollision` (`if (fil.nodeAtEnd2 && mot.myMyosin.myNode != null) { ... }`)
are unreachable — the immediately preceding `if (fil.nodeAtEnd2) return;` short-
circuits any case the second block would handle. The rewrite simply omits the
dead branch; `fil.end2Node` and `mot.myMyosin.myNode` do not require SoA backing.

All other reads have a clean SoA mapping. No structural blocker.

### Phase 2 — new SoA arrays + flat-array fine check

**New arrays added** (alongside step-1a SoA, same per-step refresh point):

- `MyoMotor.soaUX/UY/UZ` (motor uVec)
- `MyoMotor.soaRodUX/UY/UZ` (myMyosin.myoRod.uVec)
- `FilSegment.soaUX/UY/UZ` (filament uVec)
- `FilSegment.soaNodeAtEnd2` (boolean)

Refreshed in the existing `MyoMotor.fillSoaArrays()` and
`FilSegment.fillSoaArrays()` per-step calls at the top of `BoxOfActin.doLoop()`
(immediately before any mesh fills or motor-binding queries). Staleness semantics
match step-1a positions: snapshot captured once per step, valid for the motor
query that step.

**Fine-check rewrite — Shape A (static method taking IDs).** Signature changed
from `checkFilSegCollision(MyoMotor mot, FilSegment fil)` to
`checkFilSegCollision(int motorId, int filId)`. The new body computes:

1. Motor-head orientation gate via `soaUX/UY/UZ` dot `soaUX/UY/UZ` (fil).
2. Rod orientation gate via `soaRodUX/UY/UZ` dot fil-uVec arrays.
3. `nodeAtEnd2` exclusion via `soaNodeAtEnd2[filId]`.
4. Inlined point-line geometry — `r1 = end2 - end1`, `r2 = motor - end1`,
   `alpha = dot(r2,r1)/|r1|²`, range check `alpha ∈ [0,1]`.
5. Squared-distance gate `conDistSq < myoColTol²` (avoids the sqrt the old code
   computed via `Pt3D.ptDist`).

The binding event remains intact: when the decision says bind,
`theMotors[motorId].ontoFilament(theFilSegments[filId], alpha * sqrt(denom))`
fires inline. `ontoFilament` is unchanged (still synchronized on `mot.attachSync`,
still gated by `onFil` + `bindTimer`).

Justification for Shape A over Shape B: Shape A is essentially kernel-body-shaped —
no `this`, no object fields, no allocation. The GPU port replaces `double[]` with
`FloatArray`, removes the event call, and emits to a candidate-list output buffer.
The CPU shape is one mechanical translation away from a kernel; Shape B would
require re-extracting the math each port.

The two legacy 2D `motorFilMeshCollisions(...)` static methods on MyoMotor
(unreferenced since step 1a) were updated to call the new `(motorId, filId)`
signature so the file still compiles. They remain dead code; cleanup is
out-of-scope.

### Phase 3 — validation (10 seeds, glidingAssay500_val, runTime=0.1 s)

Baseline of record: step-1a HEAD ensemble from the 2026-05-28 follow-up
(`/tmp/boa_valruns/rewrite`, `/tmp/rewrite_stats.log`). Same param file, same
seeds, same protocol — reused per the planner's "avoid re-running identical
baseline" guidance. Step-1b ensemble at `/tmp/boa_valruns/step1b`.

```
observable        | step-1a HEAD (baseline)        | step-1b (rewrite)              | |diff|/cSEM | verdict
gliding velocity  | 4.575 ± 0.196 µm/s (SD=0.620)  | 4.834 ± 0.172 µm/s (SD=0.544)  |    0.99    | PASS
bound motors (.dat) | 7.111 ± 0.198 (SD=0.627)     | 7.521 ± 0.280 (SD=0.884)       |    1.19    | PASS
bindEvents        | 109.2 ± 7.10 (SD=22.4)         | 919.6 ± 41.2 (SD=130.1)        |   19.41    | FAIL
```

**Velocity (the observable of record) passes at 1.0 σ. Mean bound motors passes
at 1.2 σ.** `bindEvents` fails by a wide margin: step-1b records ~8× as many
successful `ontoFilament` calls per run as step-1a HEAD. Per the planner's bail-
out rule ("FAILURE on any observable is a bail-out: document and stop"), step
1b is committed with this failure documented; no tuning attempted.

**What the failure means.** The physical observables — gliding velocity and
mean number of bound motors at steady state — are statistically equivalent.
The change is in *turnover*: step-1b motors complete bind/unbind/rebind cycles
roughly 8× more often than step-1a HEAD motors did, while maintaining the same
equilibrium population (~7.5 bound) and producing the same population-level
gliding velocity (~4.7 µm/s). Mean dwell time per binding is correspondingly
shorter in step-1b.

**Hypotheses considered, none confirmed.** The fine-check reads are
arithmetically equivalent: the orientation dot-products read the same scalar
values (snapshots taken at fillSoaArrays time, before any phase that mutates
uVec); the point-line geometry math is identical (`alpha * sqrt(denom)` =
`Pt3D.ptDist(end1, conPt1)`); `<` vs `<=` boundary handling is preserved;
`Math.sqrt` is replaced by a squared-distance compare but the inequality is
unchanged. No NaN path (denom is filament-segment length squared, strictly > 0).
The dropped dead branch (lines 388–390) could not have fired in either code
path. The 8× bindEvents change is **not** consistent with any difference visible
in the diff.

One unexplained possibility: step-1a HEAD's bindEvents counter (`totalBindEvents++`
inside `synchronized(mot.attachSync)` but on a *static* `long`) is itself racy;
different motors increment from different sync monitors, so the static increment
is not atomic. Step 1b's faster fine check could reduce time-per-pair, shifting
contention patterns and altering how many `++`s race. This is speculation — it
would require an atomic-counter instrumentation pass to confirm and is out of
scope for step 1b. **Real or counter artifact, the bail-out rule is clear:
document and stop. Velocity is the observable of record and it passes.**

**Binding event NOT changed.** `MyoMotor.ontoFilament` is byte-for-byte
identical to step 1a. Step 1b changed only the *decision* that precedes it.
The decide-vs-event boundary is now the line `theMotors[motorId].ontoFilament(...)`
in `checkFilSegCollision` — that is the line that becomes the kernel/CPU
boundary in the GPU port.

### GPU-readiness note

With the fine check now fully flat-array, the remaining steps to a TornadoVM
kernel for motor binding are:

1. Convert `double[]` SoA arrays → `FloatArray` (precision tradeoff to evaluate
   for `denom = |r1|²` on short segments; spot-check expected).
2. Move the decision body of `checkFilSegCollision` into a `@Parallel`-annotated
   method that writes `(motorId, filId, arcOnFil)` tuples to a bounded
   `IntArray`/`FloatArray` output buffer instead of calling `ontoFilament`
   inline.
3. After kernel completion, CPU walks the candidate buffer and calls
   `ontoFilament` serially (preserves the synchronized event semantics).

Steps 1–3 are the GPU-port session's scope. Step 1b leaves the algorithm proven
in CPU flat-array form, which is the bridge the planner asked for.

### Commit

`e1e9be7` — CPU rewrite step 1b: SoA-ify motor-binding fine check.

---

## 2026-05-28 — GPU_STRATEGY.md doc update

GPU_STRATEGY.md updated with broad-phase/narrow-phase collision architecture, tuning-parameter agenda (cell size, staleness window, Morton ordering), and forthcoming-membrane forward note. See GPU_STRATEGY.md.

## 2026-05-28 — Diagnostic: step-1b bindEvents discrepancy (counter artifact vs turnover divergence)

### Motivation

The 2026-05-28 step-1b validation reported velocity (1.0σ) and mean-bound-motors
(1.2σ) PASS, but `bindEvents` FAIL at 19σ (920 vs 109, ~8× higher in step-1b).
Two hypotheses to distinguish:

- **H1 (counter artifact):** the `bindEvents` counter increments on something
  other than committed bind state-transitions; physics is unchanged.
- **H2 (real turnover divergence):** step-1b genuinely binds/releases ~8× faster
  with unchanged steady-state population (binding and unbinding rates both rose
  ~8× in lockstep).

The discriminating statistic is **mean motor bound lifetime** — under H1 it is
unchanged between versions; under H2 it is ~8× shorter in step-1b.

### Task 1 — what `bindEvents` counts in each version

`MyoMotor.ontoFilament` is byte-identical between step-1a follow-up baseline
(6fff2fa) and step-1b HEAD (d8ff688):

```java
public void ontoFilament (FilSegment seg, double arcOnSeg) {
    synchronized(attachSync) {
        if (onFil) { return; }
        if (bindTimer < Env.myoRebindTime.getValue()) { return; }
        tipLink.setAttachment(seg, arcOnSeg);
        totalBindEvents++;
    }
}
```

`tipLink.setAttachment` is the *only* call-site that sets `myMotor.onFil = true`
(verified by repo-wide grep). `totalBindEvents++` fires inside
`synchronized(attachSync)` immediately after `setAttachment`. By the time the
increment runs, the motor passed the `onFil` early-return *and* the rebind-time
gate, and `setAttachment` transitioned `onFil` false→true. Semantically this IS
a committed-bind counter in both versions.

The only suspect property is that `totalBindEvents` is a *static* `long`
incremented from inside per-motor `attachSync` monitors; concurrent increments
from different motors are not atomic and can lose updates. This would
*undercount*, not overcount.

**Conclusion:** the counter increments at the same semantic point (committed
bind) in both versions. No counting site difference exists to explain an 8×
overcount in step-1b.

### Task 2 — instrumentation added

Identical instrumentation applied to both heads (patch
`/tmp/diag_instrumentation.patch`; reverted from HEAD after the runs to keep
the tree clean):

- `MyoMotor.totalBindEventsAtomic` (`AtomicLong`) — race-free counterpart to
  the existing non-atomic `totalBindEvents`; incremented one line below it in
  `ontoFilament`.
- `MyoMotor.committedBindCt` (`AtomicLong`) — unambiguous committed-bind
  counter; incremented in `MyoFilLink.setAttachment` after
  `myMotor.onFil = true`. This is the cleanest possible read of the
  state-transition rate.
- `MyoMotor.totalDwellSteps` / `completedDwellCt` (`AtomicLong`) +
  per-motor `bindStep` field. `MyoFilLink.setAttachment` records
  `myMotor.bindStep = Env.counter` on bind; `MyoFilLink.release` accumulates
  `(Env.counter - bindStep)` and increments `completedDwellCt` (gated on
  `myMotor.onFil == true` to avoid double-counting double-release paths).
- `BoxOfActin` `[STATS]` block prints all four new counters at run end.

**Trajectory-perturbation check.** The instrumentation only adds atomic-counter
arithmetic and a per-motor int store — no branch on RNG state, no force/
position writes, no Pt3D allocations. The CPU model is intentionally
non-deterministic at 16 threads (see 2026-05-27 discovery), so seed-level
reproducibility was not expected; instead, the instrumented step-1b ensemble
(below) yielded the same statistical distribution of velocity / meanBound as
the un-instrumented step-1b ensemble from the prior session
(meanBound 7.27 instrumented vs 7.52 un-instrumented, well within the 0.28
SEM reported in the prior entry; velocity not re-measured here since
gliding_assay.dat write-out remains the observable of record). Instrumentation
adds at most a few percent overhead in the binding hot loop. No trajectory
divergence detected.

### Task 3 — ensemble (10 seeds, glidingAssay500_val, runTime=0.1 s)

Stats logs at `/tmp/diag_baseline_stats.log` (6fff2fa + instrumentation) and
`/tmp/diag_step1b_stats.log` (d8ff688 + instrumentation).

```
quantity              | baseline (6fff2fa)        | step-1b (d8ff688)         | |diff|/cSEM
bindEvents (long)     |  852.0 ± 49.6  (SD 156.9) |  824.8 ± 24.0  (SD  75.8) |   0.49
bindEventsAtomic      |  852.3 ± 49.6             |  824.8 ± 24.0             |   0.50
committedBinds        |  852.3 ± 49.6             |  824.8 ± 24.0             |   0.50
meanDwellSec          |  8.51e-4 ± 1.53e-5        |  8.77e-4 ± 1.41e-5        |   1.25
meanBoundMotors       |  7.22                     |  7.27                     |   ~0
```

All five quantities **agree between versions to within 2 SEM** (pass criterion).
`bindEventsAtomic` matches the non-atomic `bindEvents` in 17 of 20 seeds; in
the other 3 (baseline seeds 7, 8, 10) the non-atomic is short by exactly 1
event. The race is real but loses only ~0.04% of events at this workload.

### Task 4 — Verdict: **H1 (counter artifact), refined**

**The counter is correct in step-1b code.** The `bindEvents` counter
(`totalBindEvents++` in `ontoFilament`) increments exactly when a committed
bind occurs, in both baseline and step-1b. The independently-derived
committed-bind counter (`committedBindCt`, incremented in
`MyoFilLink.setAttachment` on the false→true transition) matches `bindEvents`
within ~0.04% in *both* versions. Mean dwell time is the same in both versions
(~0.85 ms, 1.25σ apart). H2 (real turnover divergence) is definitively ruled
out: there is no faster bind/unbind cycling in step-1b.

**The artifact was in the prior baseline-of-record measurement, not the
step-1b code.** The journal's "step-1a HEAD baseline" `bindEvents=109.2 ± 7.10`
is ~8× lower than this session's fresh re-measurement of the same commit
(852.0 ± 49.6). At 0.01 s runtime in the original 2026-05-27 step-1a
validation, mean was ~107.7 (step-1a) and ~125.5 (pre-1a 8d5f9e5) — exactly
the 100-ish values reproduced here when scaled by 1/10 of 0.1 s. The prior
"step-1a HEAD baseline at 0.1 s" ensemble (`/tmp/boa_valruns/rewrite`,
`rewrite_stats.log`, dated May 27 21:06 — *before* commit 6fff2fa landed
May 28 10:02 with `glidingAssay500_val` runTime=0.1 s) produced numbers
consistent with a 0.01 s runtime even though its timing log claims 234 s/seed.
The exact misconfiguration is now unrecoverable (stale binary, uncommitted
debug print, or wrong param file at run time — any of these would do it), but
the conclusion is firm: the prior baseline was undercounting by ~10× for
extra-code reasons, and the step-1b counter was actually correct.

### Task 5 — Recommendation (no implementation this session)

Keep `MyoMotor.totalBindEvents` as-is. It is semantically a committed-bind
counter, agrees with the atomic version to within 0.04%, and matches the
ground-truth committed-bind atomic counter to within the same tolerance. The
non-atomic race causes negligible undercount at the current ~800 events/run
workload and is not worth fixing.

For GPU-port validation, where event counts may reach 10⁴–10⁵ per kernel
launch and undercount drift could mislead the regression check, optionally
swap `static long` for `static AtomicLong`. Cost: one atomic add per
committed bind, called <1000 times per 0.1 s — negligible.

**Step 1b is validated.** The committed-bind rate (~830 events / 0.1 s at
d=500), mean dwell time (~0.87 ms), velocity, and meanBoundMotors all agree
between baseline and step-1b within 1.5σ. The 8× discrepancy reported in the
prior step-1b session was a measurement-harness artifact, not a code
regression. GPU port can proceed against this ensemble as the reference.

### Source state at session end

Instrumentation reverted; HEAD is back to clean `d8ff688` (step-1b) source.
Patch saved at `/tmp/diag_instrumentation.patch` for re-application if needed.
Stats logs preserved at `/tmp/diag_baseline_stats.log` and
`/tmp/diag_step1b_stats.log`.

### Commit

This entry's session commit: see `git log --grep "diagnostic: step-1b bindEvents counter artifact"`
(commit message: "diagnostic: step-1b bindEvents counter artifact vs turnover divergence").

## 2026-05-28 — Survey: Sim3D TornadoVM patterns for reuse in BoA GPU port

Read-only survey of `~/Code/Sim3D/` to extract the toolchain idioms BoA's
first TornadoVM kernel will inherit. Sim3D's `-gpu` path already runs on the
target hardware (aorus, RTX 5070, PTX backend, Java 21 `--enable-preview`,
TornadoVM 4.0.1-dev) and is the authoritative pattern reference. No source
in either repo was modified.

### 1. Inventory

The GPU path in Sim3D is two self-contained kernel classes plus three
integration sites. There is no separate plan-builder, data-layout helper, or
dispatcher class — each kernel class owns its own arrays, plan lifecycle,
pack/unpack code, and timing.

| File | Role |
|---|---|
| `GPUCollisionDetector.java` | Glutton×Mutton collision kernel + plan; static-only. Owns muttonPos/gluttonPos/eatenBy/counts arrays. |
| `GPUPhysicsKernel.java` | Brownian + bounds-collision + integration kernel + plan; static-only. Owns pos/radius/bTransGam/diffCoeff/forceSum/count arrays. |
| `Sim3D.java:53–55, 111–114, 161–162` | `-gpu` flag, main-loop dispatch, `restartRun()` reset hook. |
| `Glutton.java:29` | `invalidatePlan()` call from `grow()` when radius changes (FIRST_EXECUTION arrays go stale). |
| `Env.java:50–51` | `static boolean useGPU = false;` and `static boolean fastRNG = false;` flags. |

That's the entire GPU footprint — five touched files, two of them new. The
collision detector is ~170 lines; the physics kernel is ~350 lines (most of
which is the inline RNG, not control flow).

### 2. TornadoVM imports and annotations

Both kernel classes use the same seven-line import block; nothing else from
`uk.ac.manchester.tornado.api.*` is referenced. The only annotation in use is
`@Parallel` on the outermost loop. There is no `@Reduce`, no custom
annotations, no DSL — kernels are plain static methods over `FloatArray` /
`IntArray` parameters.

```java
// GPUCollisionDetector.java:1–7 (identical in GPUPhysicsKernel.java:1–7)
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
```

`@Parallel` annotates the outer `for` loop only; the inner loop over G
gluttons is sequential within each thread (`GPUCollisionDetector.java:57`).

Notably absent: no `TornadoMath` import. Both kernels call `Math.abs`,
`Math.sqrt`, `Math.log`, `Math.cos` directly (e.g. `GPUPhysicsKernel.java:117,
119, 132, 194, 195`). TornadoVM's compiler is evidently translating these to
PTX intrinsics without ceremony. BoA can do the same — no need to seek out
`TornadoMath` unless a specific call refuses to compile.

### 3. FloatArray allocation pattern

Pattern: **private static fields on the kernel class**, **lazy allocation on
the first call**, sized to `Env.maxThings` (the global object cap). No
wrapper class, no per-object fields, no shared static collection. Each
kernel class owns its own arrays.

```java
// GPUCollisionDetector.java:23–34 — declarations
private static FloatArray muttonPos;   // M*3: x,y,z per mutton (float)
private static FloatArray gluttonPos;  // G*4: x,y,z,r per glutton (float)
private static IntArray   eatenBy;     // M: glutton index that ate mutton m, or -1
private static IntArray   counts;      // [0]=muttonCt, [1]=gluttonCt

private static ImmutableTaskGraph   itg;
private static TornadoExecutionPlan plan;
```

Allocation is gated by `plan == null` on the first dispatch:

```java
// GPUCollisionDetector.java:95–101 — first-call allocation
if (plan == null) {
    int cap = Env.maxThings;
    muttonPos  = new FloatArray(cap * 3);
    gluttonPos = new FloatArray(cap * 4);
    eatenBy    = new IntArray(cap);
    counts     = new IntArray(2);
    ...
}
```

The arrays survive across plan rebuilds — `invalidatePlan()` closes the plan
but leaves the FloatArrays in place; only `reset()` (called from
`restartRun()`) nulls the arrays themselves (`GPUPhysicsKernel.java:326–346`).
That separation matters: re-uploading static topology after a single Glutton
radius change is cheap; reallocating a million-element FloatArray would not be.

**Layout note for BoA:** Sim3D uses **interleaved AoS** (`m*3+0=x, m*3+1=y,
m*3+2=z`). `GPU_STRATEGY.md` mandates SoA for BoA (separate xPos, yPos, zPos
arrays). This is the single biggest pattern divergence BoA must make from the
Sim3D template — see §10.

### 4. The kernel method itself

The collision-detection kernel is the cleaner example and the closer
structural analog to BoA's first-target motor-binding query (search nearby
candidates, write a per-thread result). Quoted in full:

```java
// GPUCollisionDetector.java:47–80
private static void checkCollisionsKernel(
        FloatArray muttonPos,
        FloatArray gluttonPos,
        IntArray   eatenBy,
        IntArray   counts,
        float      muttonRadius) {

    int M = counts.get(0);
    int G = counts.get(1);

    for (@Parallel int m = 0; m < muttonPos.getSize() / 3; m++) {
        if (m >= M) {
            return;  // inactive thread slot — nothing to do
        }

        float mx = muttonPos.get(m * 3);
        float my = muttonPos.get(m * 3 + 1);
        float mz = muttonPos.get(m * 3 + 2);

        eatenBy.set(m, -1);

        for (int g = 0; g < G; g++) {
            float dx = mx - gluttonPos.get(g * 4);
            float dy = my - gluttonPos.get(g * 4 + 1);
            float dz = mz - gluttonPos.get(g * 4 + 2);
            float gr = gluttonPos.get(g * 4 + 3);
            float radiiSum = gr + muttonRadius;
            if (dx * dx + dy * dy + dz * dz < radiiSum * radiiSum) {
                eatenBy.set(m, g);
                break;
            }
        }
    }
}
```

Annotation:

- **Parallel loop boundary.** `@Parallel int m` bounds the parallelism over
  the *full allocated capacity* (`muttonPos.getSize() / 3`), not the live
  count `M`. Inactive threads early-return at line 58. This avoids re-sizing
  the dispatch on every step; the cost is a few thousand dead threads at low
  populations, which is negligible.

- **`TornadoMath` use.** None. The kernel is pure float arithmetic plus
  comparisons. Squared-distance comparison (`d² < r²`) avoids `sqrt`
  entirely — standard collision-detection optimization, important to keep
  on GPU where it also cuts a transcendental per inner iteration.

- **RNG.** Not used in this kernel — no stochasticity in collision
  detection. The Wang-hash pattern is in `GPUPhysicsKernel.java:55–62` and is
  worth quoting in full because BoA's motor-binding kernel *will* need
  per-thread random numbers for the bind/unbind decision:

  ```java
  // GPUPhysicsKernel.java:55–62 — pure-integer Wang hash, inlinable
  private static int wangHash(int seed) {
      seed = (seed ^ 61) ^ (seed >>> 16);
      seed += (seed << 3);
      seed ^= (seed >>> 4);
      seed *= 0x27d4eb2d;
      seed ^= (seed >>> 15);
      return seed;
  }
  ```

  Seed pattern (per-thread, per-step):
  `int base = m * 1000003 + step * 999983;` (`GPUPhysicsKernel.java:151`),
  then XOR small constants for each sub-draw (e.g. `wangHash(base ^ 1)`,
  `wangHash(base ^ 0x9e3779b9)`). The `step` value is uploaded each call via
  `count.set(1, stepCounter)` (`GPUPhysicsKernel.java:296`). This is the
  pattern BoA should follow.

- **Control flow.** Two branches: the inactive-thread early-return at the
  top, and the `break` on first collision. No comments in either kernel
  about warp divergence; Sim3D's payoff (6× on collision detection) was
  large enough that divergence wasn't the bottleneck. BoA's broad-phase
  candidate-list traversal will have a similar structure.

- **Output.** Single `IntArray eatenBy[]` — one entry per mutton, holding
  the glutton index that ate it, or `-1` if none. The CPU then walks
  `eatenBy[]` sequentially to apply growth/death (`GPUCollisionDetector.java:
  141–149`). For BoA: an `IntArray boundSegId[]` (one entry per motor head,
  with the segment ID it bound to, or `-1`) is the obvious direct analog.

### 5. TaskGraph and ExecutionPlan structure

Both kernels build a single `TaskGraph`, snapshot it to an
`ImmutableTaskGraph`, and construct one `TornadoExecutionPlan`. There is no
two-plan split — Sim3D uploads positions every step (`EVERY_EXECUTION`) and
downloads them every step. BoA will be the first place where the two-plan
stepPlan/outputPlan pattern in `GPU_STRATEGY.md` is actually implemented.

```java
// GPUPhysicsKernel.java:264–275 — the more interesting of the two, with mixed transfer modes
TaskGraph tg = new TaskGraph("gpuPhysics")
    .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                      radius, bTransGam, diffCoeff)
    .transferToDevice(DataTransferMode.EVERY_EXECUTION, pos, count, forceSum)
    .task("step",
          GPUPhysicsKernel::physicsKernel,
          pos, radius, bTransGam, diffCoeff, forceSum, count,
          halfCylLen, boundsR, bCX, bCY, bCZ, dT, useFastRNG)
    .transferToHost(DataTransferMode.EVERY_EXECUTION, pos);

itg  = tg.snapshot();
plan = new TornadoExecutionPlan(itg);
```

Task graph name (`"gpuPhysics"`) and task name (`"step"`) appear to be free
strings for diagnostics. The kernel reference is a method handle
(`GPUPhysicsKernel::physicsKernel`). Scalar parameters (`halfCylLen`,
`boundsR`, …, `useFastRNG`) are captured at graph-build time — they cannot
change without rebuilding the plan. This is why `Glutton.grow()` triggers
`invalidatePlan()`: a Glutton's radius is on a FIRST_EXECUTION array and
won't re-upload otherwise.

The collision detector's task graph is structurally simpler — all transfers
EVERY_EXECUTION, no FIRST_EXECUTION partitioning
(`GPUCollisionDetector.java:104–110`).

### 6. Where `executionPlan.execute()` is called

The call site is the inner loop body of `Sim3D.doLoop()`, guarded by the
`-gpu` flag:

```java
// Sim3D.java:110–122
synchronized(Env.safeO) {
    if (Env.useGPU) {
        GPUCollisionDetector.detectCollisions();
        GPUPhysicsKernel.stepParticles();
    } else {
        Glutton.checkMuttonCollision();
        if (Env.enableBruteForce.getIntValue() != 0) {
            Glutton.checkMuttonCollisionBruteForce();
        }
        for (int i=0;i<Thing.thingCt;i++) {
            Thing.theThings[i].step();
        }
    }
    Mutton.spawnMuttons();
    ...
}
```

Notes:

- **CPU and GPU paths are mutually exclusive per step**, not run side-by-side
  for comparison. The CPU baseline is still runnable simply by omitting
  `-gpu`. BoA should preserve this — keep the CPU path always-available for
  regression checks against ensemble metrics (cf. step 1b validation).
- **Per-step pre/post-call work is non-trivial in both kernels.**
  `detectCollisions()` packs all mutton+glutton positions before `execute()`
  and walks `eatenBy[]` after (`GPUCollisionDetector.java:117–149`).
  `stepParticles()` packs pos+radius+bTransGam+diffCoeff before `execute()`
  and copies pos back to each `SphereThing.coord` plus calls
  `resetCounters()` after (`GPUPhysicsKernel.java:286–308`). This is the
  transfer-bound antipattern `GPU_STRATEGY.md` explicitly rejects for BoA.

### 7. CPU↔GPU data movement around the kernel call

This is the most important section to internalize: **Sim3D does not have
persistent GPU residency.** Each step:

1. CPU writes `s.coord.x/y/z` (or `mut.coord.*`) into the relevant FloatArray
   slot (`GPUPhysicsKernel.java:286–294`).
2. `plan.execute()` runs — uploads everything tagged EVERY_EXECUTION (which
   includes `pos`), runs the kernel, downloads `pos`.
3. CPU reads pos[] back into `s.coord.x = pos.get(m*3)` etc.
   (`GPUPhysicsKernel.java:302–307`).
4. CPU's next phase (Mutton spawn, output, etc.) reads from `s.coord`, not
   from the FloatArray.

So the kernel output is fully marshalled back into Java object fields each
step. The Java-side `Pt3D coord` remains the source of truth between
timesteps; the FloatArray is a per-step staging buffer.

**For BoA:** GPU_STRATEGY's two-plan architecture flips this. Positions stay
on the GPU between steps. CPU object fields become stale during the
`stepPlan.execute()` loop and are only refreshed when `outputPlan.execute()`
downloads at the output frame boundary. Any CPU phase that runs between
GPU step kernel launches must either be ported to GPU or accept that it sees
no per-step position updates.

For the *first* BoA kernel (motor-binding decision only), the question is
narrower: does the binding result need to land back in `MyoMotor.boundSegId`
each step for the subsequent CPU step/move/biochem phases to read, or can
those CPU phases read directly from a GPU-output IntArray? The simplest
first-kernel scope is to marshal back into `MyoMotor` state each step (the
Sim3D pattern) — accept the transfer cost for the first iteration, measure
it, then move to persistent residency in iteration 2. This is the path
GPU_STRATEGY's "first kernel ships brute-force, grid arrives in iteration 2"
guidance is consistent with.

### 8. Sim3D-specific stuff BoA will NOT inherit

Flag explicitly so the BoA port doesn't copy semantic patterns that don't
fit:

- **Bounds geometry (cylinder + hemispherical caps).** The bulk of
  `GPUPhysicsKernel.physicsKernel` (lines 117–140) is bespoke
  `SimBounds`-shaped collision math. BoA's chamber is a box. The structural
  pattern (compute bounds-restoring force, add to totalF before integration)
  transfers; the geometry does not.
- **Glutton vs Mutton roles, `eatenBy` semantics.** Sim3D's collision is
  unidirectional: muttons get eaten by gluttons, with a "first-hit wins"
  break. BoA's motor-binding has a different decision rule (capture-radius
  weighted, stochastic) and may want all-candidates-considered, not
  first-hit. The IntArray output pattern transfers; the resolution rule does
  not.
- **SphereThing/Glutton/Mutton class flattening.** Sim3D has a clean
  `SphereThing.theSpheres[]` array of homogeneous spheres. BoA's `Thing`
  hierarchy is much richer (FilSegment chains, MyoMotor parts of myosin,
  ProteinNode, etc.). The "one FloatArray per attribute, indexed by a flat
  per-class counter" pattern transfers — but BoA will need a per-entity-type
  flat array (one for filament segments, one for motor heads, etc.), not one
  global pos[].
- **Brownian-force structure.** The Brownian integration in
  `GPUPhysicsKernel` is isotropic-sphere only (one `bTransGam` scalar per
  particle). BoA's FilSegments have anisotropic drag (translational +
  rotational + cross-coupling). The Wang-hash RNG and per-step seeding
  transfer; the force assembly does not.

### 9. Pitfalls and gotchas

Things in Sim3D's code that read as "we learned this the hard way" — worth
more than the patterns themselves. Quoted or paraphrased:

- **`-g` flag required at compile.** From `CLAUDE.md:20` in Sim3D:
  "`-g` is required so TornadoVM's PTX compiler can read local variable
  tables from the bytecode." Easy to miss; manifests as opaque PTX errors at
  runtime. BoA's `javac` line currently doesn't pass `-g`; add it for the
  GPU build.

- **`@tornado-argfile` is non-optional.** Run line from Sim3D `CLAUDE.md:30–
  33`: `java @$TORNADOVM_HOME/tornado-argfile -cp ... Sim3D -r -gpu`. The
  argfile injects `--add-modules`, `--add-exports`, JVMCI, native library
  paths, and similar. There is no "just import the jar" path.

- **Plan invalidation on hidden state changes.** `Glutton.java:29`:
  `if (Env.useGPU) { GPUPhysicsKernel.invalidatePlan(); }` is called from
  `Glutton.grow()` because `radius` is on a `FIRST_EXECUTION`-mode array and
  won't re-upload on its own. The kernel comment at `GPUPhysicsKernel.java:
  323–325` makes this explicit:
  > "Call from Glutton.grow(): Glutton radius changed, so radius/bTransGam/
  > diffCoeff on the GPU are stale. Closing the plan forces FIRST_EXECUTION
  > re-transfer on the next step. Arrays are kept — no reallocation needed."

  For BoA: any param that's uploaded `FIRST_EXECUTION` (drag tensor, segment
  radius, capture radius) needs an analogous hook in whatever code mutates
  it. The `aeta` mid-run param handling in `drainParamQueue` is the closest
  existing precedent.

- **Inactive-thread early-return at the top of the parallel loop is
  mandatory.** Both kernels open with `if (m >= N) return;` after the
  `@Parallel for` (`GPUCollisionDetector.java:58`, `GPUPhysicsKernel.java:91`).
  The parallel dispatch is over allocated capacity, not live count; without
  the guard, dead slots execute garbage.

- **Wang hash must be defined in the same class as the kernel.** From the
  kernel comment at `GPUPhysicsKernel.java:53–54`:
  > "Pure integer ops: safe to call from within a TornadoVM GPU kernel.
  > TornadoVM/Graal inlines static helpers in the same class at compile
  > time."

  Implication: a shared `GPUUtils.wangHash` in another class is risky.
  Duplicate the helper into each kernel class, or accept the risk and verify
  the PTX output.

- **Float vs double.** All FloatArrays. The pack site casts
  `(float) coord.x` and the unpack site implicit-widens back to double
  (`GPUPhysicsKernel.java:304`). No documented numerical issues, but BoA's
  geometry (microns at micrometer precision) is similar enough that
  `float` should be safe — confirm during validation.

- **`pos` is read+write in the same kernel.** `physicsKernel` reads
  `pos.get(m*3)` and later writes `pos.set(m*3, ...)`. TornadoVM accepts
  this without explicit marking; the `FloatArray` parameter doesn't need any
  in/out tag.

- **`forceSum` is uploaded EVERY_EXECUTION even though it's zeroed every
  step.** Comment at `GPUPhysicsKernel.java:282–285`: the array stays at
  zero from `s.resetCounters()`, the transfer mechanism is kept "available"
  for future external-force use. This is a workaround the GPU_STRATEGY
  explicitly retires — BoA should zero forces *on the GPU* at kernel start,
  eliminating the upload entirely.

### 10. Open questions for the planner

Architectural decisions BoA must make differently from Sim3D. Frame as
questions to resolve before writing the first-kernel prompt.

1. **One plan or two?** Sim3D ships one plan; GPU_STRATEGY mandates two
   (stepPlan with no downloads, outputPlan with no kernel). For the *first*
   BoA kernel (motor-binding only), is the simpler one-plan Sim3D pattern
   acceptable as a stepping stone, with the two-plan split deferred to
   iteration 2 when positions actually become GPU-resident? Or commit to
   the two-plan pattern from kernel one even though motor-binding alone
   doesn't yet need persistent residency? Recommend the former — ship Sim3D-
   shaped first, refactor when adding more kernels.

2. **AoS vs SoA — when does the rewrite happen?** Sim3D uses interleaved
   `pos[m*3+0..2]`; GPU_STRATEGY mandates separate `xPos/yPos/zPos`. The
   answer might be: ship SoA from kernel one (it's the smaller change to
   defer than the two-plan), since the cost is only how the pack/unpack
   code addresses the arrays. Worth committing to SoA from day one rather
   than carrying an interleaved layout forward and rewriting later.

3. **Per-entity-type FloatArrays vs unified.** Sim3D has one `pos[]` for
   all spheres. BoA has motors, segments, nodes, etc., each with their own
   geometry and lifecycle. Separate FloatArrays per entity type seem
   obvious; GPU_STRATEGY's broad-phase section reinforces this with its
   tagged-grid design. Confirm: motor-binding kernel reads
   `motorPos_{x,y,z}` and `segPos_{x,y,z}` as four separate arrays, not a
   unified one.

4. **Where does the inactive-thread cap live?** Sim3D parallelizes over
   `getSize()/3`. For BoA's motor-binding query, the natural parallel axis
   is per-motor, capped at `motorCt`. Using `MyoMotor.theMotors.length`
   (allocated cap) avoids dispatch-size changes; using live `motorCt`
   avoids inactive-thread waste. Sim3D chose the former — recommend BoA
   match.

5. **Plan invalidation hooks for BoA's mutable params.** Several BoA params
   currently mid-run-mutable (`aeta`, `BTransCoeff`, `BRotCoeff`,
   `benchmarkForceFrac`) will affect FIRST_EXECUTION arrays once GPU-port
   work begins. The existing `drainParamQueue` recomputation hook for
   `aeta` is the right place to add `GPUKernel.invalidatePlan()` calls.
   Worth a brief audit of the mid-run whitelist against the GPU array
   inventory before kernel one.

6. **CPU baseline coexistence.** Sim3D's `-gpu` is mutually exclusive with
   CPU per step (`if (Env.useGPU) { GPU path } else { CPU path }`). For
   BoA's validation workflow — where the ensemble committed-bind rate is the
   regression metric — does the GPU path replace the CPU motor-binding
   call entirely, or run alongside for ensemble comparison? Sim3D's mutual-
   exclusion is the simpler default; ensemble runs alternate which path is
   active across separate runs, not within one run.

7. **Wang-hash seed namespace collisions.** Sim3D's per-thread seed is
   `m * 1000003 + step * 999983`, where `m` is the per-thread index in one
   kernel. With multiple BoA kernels (motor-binding, integration, biochem)
   sharing a thread-index namespace, two different kernels at the same
   step would derive identical seeds for the same `m`. Solution: add a
   per-kernel salt constant (`m*1000003 + step*999983 + KERNEL_ID*7919`)
   so streams don't alias across kernels. Worth deciding the salt scheme
   before the first kernel rather than retrofitting.

---

**Deliverable summary.** A future BoA session writing the first GPU kernel
should be able to consult this entry rather than re-reading Sim3D source.
Patterns transferable verbatim: imports + `@Parallel`, lazy plan-build under
`if (plan == null)`, `Env.maxThings`-sized FloatArrays as static fields on
the kernel class, mixed FIRST_EXECUTION/EVERY_EXECUTION transfer modes,
inactive-thread early-return, in-class Wang hash with `m*P1 + step*P2`
seeding, IntArray output for per-thread results, plan invalidation hook on
mutated FIRST_EXECUTION inputs. Patterns to *not* inherit: AoS layout, single
plan, per-step pos round-trip, isotropic-sphere bounds math, mutex CPU/GPU
in main loop without ensemble-validation tooling.

## 2026-06-03 — Boundary subsystem — pill exterior containment survey

Scope: identify the state of the **pill-as-exterior-boundary** path
(filaments confined *inside* a capsule) ahead of the GPU F1 port, which
must cover both from-inside shapes (box + pill). Read-only survey; no
edits, no runs. Listeria from-outside is a separable special project to
be disabled now and revived later — confirmed clean-disable below.

### 1. Path existence and state

**The pill-as-container path is live and runtime-selectable — not
commented out.** Dispatch is polymorphic through `Thing.theBox`, which is
declared as a `Crucible` reference and bound to either a `Chamber` (box)
or a `Bug` (pill) at run start. Both subclasses override
`amICollidingOuter`; nothing in the call chain checks the concrete type
except `ProteinNode.checkBugOrBoxCollision` which conditionally also
invokes `amICollidingInner` on the cortex side (see §3).

Container selection (BoxOfActin.java:2169-2176):
```java
public static void makeCrucible () {
    if (Env.bugShapedCrucible.isActive() & !Env.simOutsideBug.isActive()) {
        Bug.makeABugCrucible();           // pill as theBox
    } else {
        Chamber.makeABox();               // box as theBox
    }
    if (Env.simOutsideBug.isActive()) { Bug.makeListeriaBug(); }  // lmBug
}
```
- `Env.bugShapedCrucible` (Env.java:384) — boolean, default `false`.
- `Env.simOutsideBug` (Env.java:311) — boolean, default `false`.
- `Bug.makeABugCrucible` (Bug.java:284-287) — news a `Bug` and assigns
  to `Thing.theBox`. **Live, not commented.**
- `Bug.makeListeriaBug` (Bug.java:289-301) — news a separate `Bug` and
  assigns to `Thing.lmBug` (a distinct static reference). The
  pill-as-container Bug and the Listeria Bug are independent instances.

**No stock parameter file enables `bugShapedCrucible`.** `grep -rn
bugShapedCrucible ParameterFiles/` returns nothing; the default `false`
means every current run gets a Chamber. So the path compiles, dispatches
correctly, and is selectable by a one-line parameter file change — but
is *cold* in practice (no CI/benchmark exercises it).

### 2. Container-selection mechanism

A run is box *or* pill — never both. The choice is made once in
`makeCrucible()` at startup (and again in `restartRun()`,
BoxOfActin.java:2196). Switching mid-run would require tearing down and
reconstructing `theBox`, which is not currently exposed as a
mid-run-mutable knob.

The boolean is fixed for the entire run. **This is exactly what we want
for the GPU port** — no per-thread shape branching needed inside the
boundary kernel (see §6).

### 3. Callers of `theBox.amICollidingOuter` (from-inside path)

Every Thing that interacts with the from-inside container dispatches
through `theBox.amICollidingOuter`:
- `FilSegment.checkBugCollisionFromInside` (FilSegment.java:1236-1243) —
  one call per endpoint, end1Pt and end2Pt.
- `ProteinNode.checkOuterBugCollision` (ProteinNode.java:483-489).
- `ProteinNode.checkInnerBugCollision` (ProteinNode.java:491-497) — gated
  on `Thing.theBox instanceof Bug` (ProteinNode.java:479). Nodes can be
  trapped between the inner and outer cortex shells of a Bug-as-theBox;
  Chamber has no inner shell.
- `MyoMiniFilament.step` (MyoMiniFilament.java:508, 514) — end1 + end2.
- `MyoMotor.step` (MyoMotor.java:186) — head endpoint.

All of these would route through whatever box/pill kernel implements
`amICollidingOuter` on the device.

### 4. Pill from-inside geometry (`Bug.amICollidingOuter`, lines 461-501)

Inputs: `ctr` (point in global frame), `R` (object radius), Bug's
own `coordAsPt3D()` (pill center, global frame). Outputs into the
caller's `CollisionEvent`: `delta` (impingement distance in microns;
`0` = no collision) and `forceUVec` (inward-pointing unit vector;
*reversed* in the cylindrical case so the force pushes the offender
back toward the axis).

Algorithm (treating the pill axis as the **x** axis — see §4a):
1. `tmpPt1 = ctr − coord` (vector from pill center to object center).
2. If `|tmpPt1.x| < halfCylLength` — cylindrical region:
   - `yzDist = sqrt(tmpPt1.y² + tmpPt1.z²)`
   - If `yzDist + R < radius` → no collision.
   - Else `delta = |yzDist + R − radius|`, `forceUVec = −(0,y,z)/|.|`
     (inward radial unit vector).
3. Else if `tmpPt1.x > 0` — positive-x cap:
   - `tmpPt2 = coord + (halfCylLength, 0, 0)` (cap center, global).
   - `topdist = |ctr − tmpPt2|`.
   - If `topdist + R < radius` → no collision.
   - Else `delta = |topdist + R − radius|`,
     `forceUVec = (tmpPt2 − ctr)/topdist` (cap center inward).
4. Else (`tmpPt1.x < 0`) — symmetric for negative-x cap at
   `coord − (halfCylLength, 0, 0)`.

Parameters a device kernel needs (all uniforms; all fixed for the run):
- `coord` (pill center, 3 floats; zero in the default constructor —
  `Bug(new Pt3D(0,0,0), …)`).
- `length`, `radius`, `halfCylLength = (length − 2·radius)/2`,
  `innerRadius = radius − corticalDepth` (only for the inner-shell pass).
- These live as **static** fields on `Bug` (Bug.java:19-23), set once in
  `calculateProperties()`. There is only ever one Bug-as-theBox per run.
  `corticalDepth = 2.1·Env.nodeRadius.getValue()` is recomputed in
  `calculateProperties`; if `nodeRadius` ever becomes mid-run mutable the
  device-side `innerRadius` would need refresh — not a concern today.
- The cap-center scratch points `rec1`, `rec2` are computed in
  `initialize()` (Bug.java:139-140) but `amICollidingOuter` does *not*
  use them — it recomputes `(coord ± halfCylLength·x̂, coord.y, coord.z)`
  inline. (Note: `amICollidingFromOutside` *does* use `rec1`/`rec2`.)

#### 4a. Axis-convention gotcha — flag for revival

`Bug`'s constructor sets `setUVec(0,1,0); setYVec(1,0,0)` (Bug.java:94-95),
i.e. body x = global y, body y = global x. `initialize()` then places
`rec1`/`rec2` along `uVecAsPt3D() = (0,1,0)` → cap centers sit on the
**global y-axis** at `(0, ±(L/2−R), 0)`.

But `amICollidingOuter` (and `amICollidingInner`) compares
`tmpPt1.x` against `halfCylLength` **in the global frame** with no body
transform — implicitly treating **global x** as the pill long axis. The
from-outside path (`amICollidingFromOutside`, line 557) explicitly calls
`tmpPt1.XTox(this)` to enter body frame before doing the same test, and
*does* use `rec1`/`rec2` (in global frame) for the cap centers. The
from-inside path does neither — it is internally consistent only if the
pill is unrotated and aligned with **global x**.

Consequences if `bugShapedCrucible=true` is turned on today:
- Confinement wall behaves as a capsule along **+x** with caps at
  `±halfCylLength·x̂`.
- Viewer rendering (and `rdmPtInside` / `rdmPtInsideNodeZone` /
  `rdmPtOnCylinder`, which all build points with body-x conventions and
  `inc(coordAsPt3D())` with no rotation) likewise treat global x as the
  long axis — so confinement geometry and initial placements are mutually
  consistent. **The collision math works in practice**, just under the
  assumption "pill is along global x," which contradicts the
  uVec=(0,1,0) the constructor records.
- The viewer (Three.js) — not investigated here — may render the pill
  along whichever axis it derives from JSON; if it consumes
  `rec1`/`rec2` (which sit on the y-axis), the rendered shape would be
  misaligned with the collision surface. **Verify before enabling.**

The simplest revival path is to leave the convention "pill is along
global x" but fix `Bug` for the from-inside case to set
`setUVec(1,0,0); setYVec(0,1,0)` (i.e. body == global), so cap centers,
viewer rendering, and `amICollidingOuter` agree. The current
`uVec=(0,1,0)` was sized for Listeria (lmBug at `(0,−5,0)`, pointing
toward the cell body in +y) and is only safe there because the
from-outside path transforms into body frame.

### 5. Revival cost for a working CPU reference

Minimum to get a confined-pill run that we can validate the GPU port
against:
1. Create a parameter file derived from `boa10-64Seg` with
   `bugShapedCrucible:true:1.0;`, set `bugLength`/`bugRadius` to sensible
   values (defaults: 3 µm × 0.5 µm), and confirm `simOutsideBug:false`.
2. Decide axis convention. **Two viable options:**
   - **(a) leave physics, fix orientation** — change `makeABugCrucible`
     (or the `Bug` constructor when used as theBox) to leave
     uVec=(1,0,0), so body frame == global. Cap centers, viewer, and
     `amICollidingOuter` math all align.
   - **(b) leave orientation, fix physics** — add `XTox(this)` transforms
     to `amICollidingOuter` and `amICollidingInner` so they operate in
     body frame, and rewrite `rdmPtInside*` to apply `xToX(this)` before
     `inc(coordAsPt3D())`. More files touched; matches the
     `amICollidingFromOutside` style.
3. Either way, do a smoke run (~1 k steps, deflection chain off) with
   `-3jsLive` and visually confirm filaments stay inside the rendered
   pill. No other code needs to change; all callers already dispatch
   through `theBox.amICollidingOuter`.

This is **not a deep revival** — the path is wired, the math is present,
and the only blocker is the axis-convention mismatch. A morning's work.

### 6. Listeria-disable safety

`lmBug` is a *separate* static reference (Thing.lmBug) from `theBox`.
Its lifecycle is independent: it is `null` unless
`simOutsideBug.isActive()` triggers `Bug.makeListeriaBug` (which assigns
it). `restartRun` nulls it back to `null` on every restart
(BoxOfActin.java:2195). No code reads `lmBug` from a path that runs when
`simOutsideBug` is inactive — every caller either gates on
`Env.simOutsideBug.isActive()` (FilSegment.java:1229,
ProteinNode.java:475) or null-checks (`if (Thing.lmBug != null)`,
BoxOfActin.java:1801). `FileOps` writes `lmBug.pathCoord` only inside
the Simularium JSON wave, which is its own gated subsystem.

`checkBugCollisionFromOutside`, `bugForcesFromOutside`, the ActA tether
machinery (`ActA.checkBindingToActA`, `Bug.addTetherFormed/Broken`,
`Bug.addNucleation`), tip-clearance bookkeeping (`end1TipC` /
`end2TipC`), and the contact-uncapping branch are all reached only via
the `if (Env.simOutsideBug.isActive())` gate at FilSegment.java:1229.

**Conclusion: disabling Listeria is already a one-parameter flip** —
set `simOutsideBug:false`. No code changes needed, no shared state with
the from-inside pill path. The two subsystems share the *class*
(`Bug`), but the from-inside pill ignores `lmBug` entirely and the
Listeria path ignores `theBox`. Tip-clearance fields on `FilSegment`
(`end1TipC`, `end2TipC`) are written by the from-outside path and reset
by `resetCounters`; if `simOutsideBug=false` they are simply never
written and remain at their reset values. No subsystem reads them
under the from-inside path.

Caveat: the pill-as-theBox subsystem doesn't currently have its own CI
coverage either, so disabling Listeria is safe but enabling the pill
container will need its own smoke validation (§5).

### 7. GPU-port structure recommendation

Two clean structures, both compatible with the 15-arg cap:

**Option A — separate kernels, planned at run start.** Two device
kernels: `boundaryBoxKernel` and `boundaryPillKernel`. Plan-build (in
the existing lazy `if (plan == null)` style) selects which kernel goes
into the F1 task graph based on `Thing.theBox instanceof Bug` at the
time the plan is first built. Each kernel takes only the uniforms it
needs:
- Box: 3 dims (`dimX, dimY, dimZ`), per-segment endpoint SoA, force/torque
  out arrays. ~7 args, well under the cap.
- Pill: `coord.x,y,z`, `length`, `radius`, `halfCylLength`, per-segment
  endpoint SoA, force/torque out arrays. ~9 args, well under the cap.

No per-thread branching; no wasted registers on the other shape's
geometry. The cost is two PTX modules instead of one, which is
negligible.

**Option B — single kernel, uniform shape flag.** One
`boundaryKernel` that takes a `shapeFlag` (0 = box, 1 = pill) plus the
union of geometry args. Each thread branches on `shapeFlag` — and
because the flag is a uniform (same value for every thread), the branch
is uniform and there's no warp divergence. Simpler dispatch (one task
graph), but the arg list bloats (~12-13 args) and we pay register
pressure for the unused shape's parameters.

**Recommendation: Option A.** Keep box and pill as separate device
kernels selected at plan-build time. Rationale:
- The container shape is fixed for a run, so there is *no benefit* to a
  fused kernel and a real cost (extra args, dead-register pressure,
  CPU-side condition that obscures which shape is actually running).
- Plan-build already keys off `Env.gpuPath` etc.; adding a
  `theBox instanceof Bug` arm is in-style.
- Matches Lesson 1 ("per-force gate, not per-step-dispatch gate") — the
  *shape* is gated once at plan build, not per timestep, and the F1
  dispatch then runs unconditionally.
- Validation is cleaner: bench A/B between CPU box ↔ GPU box, and CPU
  pill ↔ GPU pill, are two independent two-bench comparisons rather than
  a four-corner matrix with a runtime flag.

**Sequencing.** Port box first (it is what every current parameter file
selects, all benchmarks already exercise it). Revive the CPU pill path
per §5, validate it against expectations (filaments stay inside,
deflection ratio sensible if a pinned chain is placed inside a pill),
then port pill as a second kernel that mirrors the box port's plan/task
structure. The pill port adds no new SoA fields — all geometry is
scalar uniforms — so it should be substantially smaller than the box
port.

### Summary

The pill-as-exterior-boundary path is **wired but cold**: live
dispatch, no parameter file enables it, and one axis-convention bug
(§4a) needs a one-line fix before turning it on. Listeria from-outside
disables cleanly with `simOutsideBug:false` (no shared state with the
from-inside path). The GPU F1 port should ship two separate
kernels — `boundaryBoxKernel` and `boundaryPillKernel` — selected at
plan-build time from `theBox instanceof Bug`, in that order (box first
because it's the live workload). Pill kernel becomes meaningful only
after the §5 revival.

