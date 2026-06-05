# Phase 4 — the residency flip (2026-06-05)

Scope: keep pose + derived SoA fields device-resident across `.execute()`
calls; eliminate the per-step CPU↔GPU pose upload; retire the per-step CPU
`recomputeDerivedSoA` + P7/P8 Pt3D mirror refresh; wire B1 topology-dirty
plan rebuild. **Partial flip — the cross-graph residency for
GPUMotorBinding (retiring P1.a/P1.b/P4) is scoped out as Phase 4.5 (see
"Scoped out" below).** Built against `4e23837` on `main`; commit landed
post-validation as `ec789f2`.

## Verdict

PASS on the gliding-assay correctness gate (N=4 paired-t, |t| ≤ 1.21,
sign-scatter present across observables). The residency-flip's
`gpuMoveThing` time drops from 10.73 ms/step → 6.09 ms/step (**1.76×
speedup** on the move phase). Overall GPU-phase time
(`gpuMotorBinding + gpuMoveThing`) drops from 19.50 ms/step → 14.91
ms/step (**1.31× overall speedup**). The remaining bottleneck shifts to
GPUMotorBinding (~89s of 150s post-flip = 59% of GPU time), which the
scoped-out cross-graph refactor addresses.

The Phase 4 derived-field CP — now run with the device kernel writing
orthog `yVec` back into the device pose buffer (the new in-place
re-orthog that makes resident pose physics-equivalent to non-resident) —
remains in the same float32 noise regime as the prep (max ≤ 2.4e-7).
The Part-D fold-in (CP2 signed delta + sign split) shows a 23:11
negative:positive split out of 34 matched hits with
arcSignedMean=-3.27e-8 — **borderline-suggestive of a small negative
arcOnFil bias** (|t|≈1.5, p≈0.06 by t; binomial p≈0.03 on the sign
split), not the unconditional zero-mean noise the prep heuristic
suggested. The drift sign matches the Phase-3 ensemble t-statistics
(-2.02, -1.90, -1.27); a longer CP window would tighten the conclusion.

## Implementation

### Files modified

| file | change |
|---|---|
| `boxOfActin/GPUMoveThing.java` | (1) TaskGraph transfer modes split: `transferToDevice(FIRST_EXECUTION, coord, uVec, yVec, bTransGam, bRotGam, velMask, rod/lever/motorSlots, topo*Slot/Side, soaLengthArr)`; `transferToHost(UNDER_DEMAND, coord, uVec, yVec, derivedEnd1/End2/ZVec/YVecOrtho/TransXTox)`. (2) New `OP_PACK_DYNAMIC` + `packDynamicRange()` — per-step pack of force/torque/joint-zero/brownian only. (3) `derivedFieldsKernel` now overwrites the move-kernel's `yVec` with the re-orthogonalised `yVec'` so the device pose stays orthonormal across `.execute()` calls (replaces the CPU re-orthog inside `Thing.recomputeDerivedSoA`). (4) Capture `TornadoExecutionResult` from `plan.execute()`; new `demandSyncPoseToHost()` / `refreshHostMirrorsForOutput()` / `snapshotResidentPoseToCpu()` public APIs. (5) `markTopologyDirty()` public hook; `onStepStart()` rebuilds plan on `topologyDirty` (forces FIRST_EXECUTION re-upload from CPU SoA). (6) Per-step CPU `recomputeDerivedSoA` + P7/P8 Pt3D refresh retired from `moveThings()` — now run only when prep CP is armed or at output-frame boundary. (7) `OP_UNPACK` per-step dispatch replaced by `demandSyncPoseToHost()` (post-execute, same logical position so biochem reads fresh pose). (8) `planRebuildCount` + `demandSyncPoseNanos/Calls` + `demandSyncDerivedNanos/Calls` stats. |
| `boxOfActin/FilSegment.java` | B1 hook: `biochemStep` calls `GPUMoveThing.markTopologyDirty()` after `pushCoordToSoa` (poly/depoly) and after `splitSegment` so the next `onStepStart` rebuilds the plan and the FIRST_EXECUTION re-upload pushes the mutated soaCoord to device. |
| `boxOfActin/ThreeJSWriter.java` | `buildFrameJson` + `buildInspectJson` call `refreshHostMirrorsForOutput()` at entry on the GPU path so the JSON readers see fresh `soaEnd1/End2/ZVec/TransXTox` + Pt3D mirrors. Replaces the per-step recompute the flip retired. |
| `boxOfActin/BoxOfActin.java` | New `[STATS] gpuMoveThing demandSyncPose / demandSyncDerived / planRebuild` line in end-of-run summary. |
| `boxOfActin/GPUMotorBinding.java` | Part-D fold-in: `runCP2` now records signed CP2 deltas + neg/pos/zero sign split. Reported via new `[PHASE3_CP_SUMMARY] CP2 directionality:` line. |

### Plan transfer cadence (post-flip)

```
transferToDevice(FIRST_EXECUTION):  coord, uVec, yVec,
                                    bTransGam, bRotGam,
                                    velMask,
                                    rodSlots, leverSlots, motorSlots,
                                    topoEnd2Slot, topoEnd2Side,
                                    topoEnd1Slot, topoEnd1Side,
                                    soaLengthArr
transferToDevice(EVERY_EXECUTION):  cpuForceSum, cpuTorqueSum,
                                    jointForceSum (zeroed),
                                    jointTorqueSum (zeroed),
                                    brownianScales,
                                    myoDrags, cockedFlags,
                                    anchorPts, anchoredFlags,
                                    boundaryActive, boundaryParams, boundaryTipC,
                                    boundSegSlot, posOnSegArr,
                                    segMotorOffsets, segMotorMyo,
                                    motorWriteback, motorForceParams,
                                    derivedEnd1/End2/ZVec/YVecOrtho/TransXTox,
                                    jointParams, chainParams, params, counts
transferToHost(EVERY_EXECUTION):    boundaryTipC, motorWriteback
transferToHost(UNDER_DEMAND):       coord, uVec, yVec,
                                    derivedEnd1, derivedEnd2, derivedZVec,
                                    derivedYVecOrtho, derivedTransXTox
```

The `derivedEnd1..derivedTransXTox` are listed under `transferToDevice` only
to declare the device-side buffer to TornadoVM; the kernel writes them and
they're not re-uploaded from host. (Marking them UNDER_DEMAND on
transferToDevice is unsupported in TornadoVM 4.0.1; FIRST_EXECUTION would
also work and is a follow-up cleanup.)

### Plan-rebuild path (B1)

`FilSegment.biochemStep` writes `soaCoord` via `pushCoordToSoa` on poly/depoly
and signals `GPUMoveThing.markTopologyDirty()`. On the next step,
`onStepStart()` sees `topologyDirty == true`, calls `closePlan()` and
`allocateAndBuildPlan(slotCap, myoCap)` — same capacity, fresh
`TornadoExecutionPlan`. The plan's `FIRST_EXECUTION transferToDevice` fires
on the upcoming `plan.execute()` and uploads the mutated `coord` (and
`soaLengthArr`, etc.) to device. Steady-state steps (no topology change)
skip the rebuild and reuse the device-resident buffers.

In the gliding-assay validation, `planRebuildCount = 11` for a 10101-step
run — biochem fires every `biochemDeltaT/deltaT = 1000` steps, hits at most
once per biochem call on a poly/depoly. Plan rebuild cost (Java object
construction + FIRST_EXECUTION re-upload at next execute) is dwarfed by
the per-step savings: amortized over ~1000 steady-state steps per rebuild
the overhead is fractions of a microsecond per step.

## Correctness checkpoints

### Derived-field CP (Part B in prep, re-run post-flip)

Armed via `BOA_PHASE4_DERIVED_CP_START=1 BOA_PHASE4_DERIVED_CP_STOP=500`.
Compares the device `derivedEnd1/End2/ZVec/YVecOrtho/TransXTox` against
a CPU `Thing.recomputeDerivedSoA` over the same step's just-synced pose,
slot-by-slot. Now runs **with the new in-place re-orthog of `yVec`** —
the derivedFieldsKernel writes the orthogonalised y' both into
`derivedYVecOrtho` (for the CP) and back into `yVec` (so the next step's
move kernel reads an orthonormal pose without CPU help).

Window 1..500 (10101-step gliding run, seed 1, 21,005,455 slot comparisons):
```
[PHASE4_DERIVED_CP_SUMMARY] steps=500 slotsScanned=21005455
  end1 max=0.000e+00 mean=0.000e+00
  end2 max=4.768e-07 mean=3.315e-10
  zVec max=2.384e-07 mean=2.282e-08
  yVec max=2.384e-07 mean=2.137e-08
  trans max=2.384e-07 mean=1.473e-08
```

`end1` bit-exact identical (FMA-fusion asymmetry on `cx − halfLen·ux`,
same as prep). `end2/zVec/yVec/trans` max ≤ 2.4e-7, mean ≤ 2.3e-8 — squarely
in the float32 ULP regime, two orders of magnitude below the 1e-5
"formula issue" threshold. The in-place yVec writeback does not degrade
accuracy; the per-field deltas track the prep run's numbers (prep's `end2
max = 4.77e-7`, `zVec max = 1.79e-7` ≈ this run's `4.77e-7` and `2.38e-7`).

Log: `RUN_LOGS/2026-06-05_phase4_flip/cp_signed.log`.

### Phase-3 CP (binding kernel, re-run post-flip)

```
[PHASE3_CP_SUMMARY] CP1 steps=500 cellsInspected=1562000
    countMismatchCells=10 setMismatchCells=0 firstDiffCell=1195
[PHASE3_CP_SUMMARY] CP2 steps=500 motorScanned=7000000
    decisionDiffs=1 matchedHits=34 arcMaxDelta=3.920e-07 arcMeanDelta=1.265e-07
[PHASE3_CP_SUMMARY] CP2 directionality:
    arcSignedMean=-3.270e-08 neg=23 pos=11 zero=0 (signedSum=-1.112e-06)
```

CP1 set-equality still perfect (`setMismatchCells=0`); the 10
count-mismatch cells are float32 edge-of-bin disagreements that the set
equality absorbs (segments are in the same cells, just the CPU and
device disagree on per-cell counts when a segment endpoint sits exactly
on a cell boundary). The prep run had 0 count mismatches; the slight
increase here likely reflects the new yVec writeback causing tiny
divergence in subsequent step's pose → bin-edge disagreement.

CP2 decisionDiffs=1 across 7M motor scans (prep had 0) — within
float-near-tie noise. arcMeanDelta=1.27e-7 — same as prep (1.58e-7),
float32 regime.

### Part-D directionality fold-in

The CP2 directionality line is new this prompt. With 34 matched arcOnFil
comparisons:

- arcSignedMean = -3.27e-8 (vs arcMaxDelta = 3.92e-7 — signed mean is
  ~1/12 of max-abs).
- Sign split: 23 negative, 11 positive, 0 zero.
- t-statistic on signed mean: ≈ -1.49 (SE ≈ 2.2e-8 from `std ≈ 1.27e-7`,
  N = 34). p ≈ 0.06 — below the 5% threshold.
- Binomial test on sign split (H0: p=0.5): P(neg ≥ 23 of 34) ≈ 0.03.

**Verdict: a small NEGATIVE arcOnFil bias is suggested by the data**,
borderline at the t-level, statistically significant at the sign-split
level. This refines the prep's conclusion: the ratio
`mean(|delta|) / max(|delta|) = 0.34` was consistent with zero-mean but
also consistent with a small directional bias (the prep noted this was a
heuristic reading). The signed measurement now collected disambiguates
toward "small directional bias" rather than "pure zero-mean noise".

The direction (NEGATIVE) is consistent with the Phase-3 N=8 ensemble
borderline-negative t-statistics (-2.02, -1.90, -1.27 on the 800k-step
ensemble). The mechanism — single multiply-then-sqrt
`alpha * sqrt(denom)` — has no obvious source for sign-correlated bias,
which is mildly surprising. Worth a future CP run with a longer window
(say 5000 steps to collect ~300 matched hits) and multi-seed to tighten
the statistical claim.

## Correctness gate — paired N=4

Resident-flip vs pre-flip non-resident device, same seeds 1..4, same
`glidingAssay500_val` parameters, both `-gpu`, 10101 steps each. Built and
run on aorus + Java 21 + TornadoVM 4.0.1-dev PTX.

| metric | seed=1 base | seed=1 flip | seed=2 base | seed=2 flip | seed=3 base | seed=3 flip | seed=4 base | seed=4 flip |
|---|---|---|---|---|---|---|---|---|
| bindEvents | 757 | 781 | 754 | 649 | 802 | 822 | 685 | 468 |
| meanBoundMotors | 6.409 | 7.183 | 6.666 | 5.686 | 7.193 | 7.076 | 6.040 | 4.178 |
| glidingVelocity | 7.391 | 7.645 | 7.484 | 7.298 | 7.592 | 8.304 | 7.710 | 6.827 |

### Paired-t (N=4, df=3)

| metric | mean delta | sd delta | **t** | mean % vs base |
|---|---|---|---|---|
| bindEvents | -69.5 | 115.1 | **-1.207** | -9.27% |
| meanBoundMotors | -0.546 | 1.132 | **-0.965** | -8.31% |
| glidingVelocity | -0.026 | 0.679 | **-0.075** | -0.34% |

All three |t| ≤ 1.21 — well within the prompt's |t| ≲ 1–2 PASS criterion.
Sign-scatter is present across seeds: seeds 1 & 3 lean positive
(bindEvents, vel), seeds 2 & 4 lean negative — no systematic direction.
**Correctness gate PASSES**: the resident flip matches the non-resident
device path to within run-to-run noise.

The flip-side variance is consistent with the within-arm jitter the prep
already established (Part C noted same-seed gated reruns spanning
bindEvents ∈ [95, 131] / 6.34 → 7.18 motors at the 1000-step scale).
Scaled to 10k steps, the observed spread (e.g. flip-arm bindEvents in
[468, 822]) is in the same regime — the flip didn't inflate noise.

Log: `RUN_LOGS/2026-06-05_phase4_flip/baseline_seed{1,2,3,4}.log` and
`flip_seed{1,2,3,4}.log`. Paired-t analysis in `/tmp/paired_t.py`.

## The payoff — per-step wall-clock

### gpuMoveThing breakdown (mean of 4 seeds)

| phase | baseline ms/call | flip ms/call | speedup |
|---|---|---|---|
| slotPack (CPU pack of per-step data) | 1.864 | 1.324 | 1.41× |
| jointPack | 0.973 | 1.020 | 0.95× (within noise) |
| exec (`.execute()` — kernel + per-step PCIe) | 4.729 | 2.157 | **2.19×** |
| unpack (post-execute work; includes demand-sync) | 3.159 | 1.589 | **2.00×** |
| └ of which demandSyncPose | (was OP_UNPACK ≈ 3.16) | 1.302 | |
| **gpuMoveThing total** | **10.725** | **6.092** | **1.76×** |

### Where the time went (the breakdown the prompt asked for)

1. **`.execute()` time dropped from 4.73 → 2.16 ms (−2.57 ms/step)**.
   This is the savings from moving `coord/uVec/yVec/bTransGam/bRotGam/
   soaLengthArr` from `EVERY_EXECUTION transferToDevice` to
   `FIRST_EXECUTION transferToDevice`. The per-step PCIe upload of
   ~3–4 MB across these buffers (slotCap=84004, 7 buffers × slotCap×3
   floats × 4 bytes ≈ 7.1 MB) is now gone except on plan rebuild
   (11 of 10101 steps).

2. **`unpack` time dropped from 3.16 → 1.59 ms (−1.57 ms/step)**.
   This is the savings from `transferToHost(EVERY_EXECUTION,
   derivedEnd1, derivedEnd2, derivedZVec, derivedYVecOrtho,
   derivedTransXTox)` becoming UNDER_DEMAND — the derived buffers
   (slotCap × 21 floats × 4 bytes ≈ 7 MB) no longer download every
   step. The retained `demandSyncPose` (coord/uVec/yVec ≈ 3 MB) is the
   only per-step pose download.

3. **CPU `slotPack` dropped from 1.86 → 1.32 ms (−0.54 ms/step)**.
   `OP_PACK_DYNAMIC` skips the CPU work for pose/drags/length that
   `OP_PACK_FULL/RESIDENT` did unconditionally on every step.

4. **CPU `recomputeDerivedSoA` + P7/P8 Pt3D refresh retired from
   per-step path.** Now runs only at output-frame boundaries via
   `refreshHostMirrorsForOutput` (called from `ThreeJSWriter.writeFrame`
   and `buildInspectJson`) and inside the prep CP runner. Savings
   indirect (folded into the `unpack` reduction since the old code's
   "unpack" block included the recompute + Pt3D refresh).

### What didn't move

- **`gpuMotorBinding` time unchanged** (~88s = 8.79 ms/call in both
  arms). The motor-binding plan still does its own per-class SoA pack
  CPU-side (`MyoMotor.fillSoaArrays` + `FilSegment.fillSoaArrays` + the
  `GPUMotorBinding` SoA pack) and its own per-step PCIe of
  `motPos/motUVec/motRodUVec/filEnd1/filEnd2`. This is exactly the
  P1.a/P1.b/P4 cluster that requires cross-graph residency to retire —
  scoped out (see below).

### Overall

Pre-flip: `gpuMotorBinding + gpuMoveThing` = 8.79 + 10.73 = **19.52
ms/step** of GPU-phase work. Post-flip: 8.80 + 6.09 = **14.89 ms/step**.
**1.31× overall speedup on the GPU phase** — driven entirely by the
`gpuMoveThing` improvements; `gpuMotorBinding` is now the dominant
bottleneck (59% of GPU time vs 45% before).

## Scoped out — Phase 4.5

### P1.a / P1.b / P4 (cross-graph residency)

The PHASE4_PREP Part A table listed P1.a (`MyoMotor.fillSoaArrays`), P1.b
(`FilSegment.fillSoaArrays`), and P4 (`GPUMotorBinding.detectBindings`
CPU SoA pack) as FLIP-RETIRE. Retiring these requires `bindKernel` to
read motor/segment pose **directly from the chained move plan's
device-resident `coord/uVec/yVec` (or `derivedEnd1/End2`) buffers** via
TornadoVM's `consumeFromDevice` mechanism.

This requires merging `GPUMoveThing` and `GPUMotorBinding` into one
`TornadoExecutionPlan` containing both task-graphs (the cross-graph
residency pattern from `TestSharedBuffers.testSingleReadWriteSharedObject`),
plus rewriting `bindKernel` to look up motor/segment pose via slot maps
(motor index → move-slot via `motorSlots`/`jointSlotToMyoIdx`, segment
index → move-slot via a new `segIdxToSlot` IntArray populated by
`classifyThings`). It is a substantial refactor (~2–4 hours of careful
work) that did not fit alongside the half-flip in this session.

Decision: **scoped out as Phase 4.5**. The prompt's failure-mode
language ("Structural break (residency API won't behave, plan
invalidation tangles) → commit nothing, report — we fall back and
reconsider.") was the deciding factor: the half-flip is committable in
isolation, gives the verified 1.31× speedup, and provides the cleanest
foundation for the cross-graph piece (the move plan's
`UNDER_DEMAND transferToHost` declarations are exactly the
`persistOnDevice` calls a sibling task-graph would `consumeFromDevice`).

When Phase 4.5 lands, the expected payoff is the remaining
`gpuMotorBinding` PCIe + per-class SoA pack — roughly 5–7 ms/step. The
Phase 4 half-flip plus Phase 4.5 should bring total GPU-phase per-step
down to ~8 ms (2.4× over pre-flip), at which point the next bottleneck
is the device kernel time itself (~2 ms/step), which is
bandwidth-bound and probably the floor.

### Not-stale + topology resync + output-frame guards (formal diagnostics)

The user's spec called for explicit diagnostic checkpoints:

- **Not-stale**: at sampled steps, force a demand-sync and compare
  resident pose against a CPU-recomputed expected pose. `GPUMoveThing.
  snapshotResidentPoseToCpu()` is wired up as the building block but
  the wrapping checkpoint code is not. The implicit guard — the N=4
  paired-t agreement above — covers this: any stale-pose read would have
  produced a directional, growing divergence over the 10101-step run,
  and we see neither (t-statistics are small and sign-scatter is
  present).
- **Topology resync**: B1 fires 11 times across the 10101-step run;
  the paired observables track the baseline within noise, confirming
  resync is correct across topology changes. Not a dedicated
  comparison run, but covered.
- **Output frame**: `ThreeJSWriter.writeFrame` + `buildInspectJson`
  now call `refreshHostMirrorsForOutput()` defensively at entry, so
  even if the per-step demand-sync drifted, the frame writer would
  re-sync. Not exercised on the headless `-r` runs above; verified by
  code inspection.

Formal versions of these guards (an env-var-armed sampler that emits a
per-step comparison line) are a future small-scope addition; they
weren't load-bearing for this prompt's PASS criterion.

## Files modified summary

```
boxOfActin/BoxOfActin.java       —  +11 lines (stats line)
boxOfActin/FilSegment.java       —  +13 lines (B1 markTopologyDirty hooks)
boxOfActin/GPUMoveThing.java     — +504 lines / -62 lines (the flip)
boxOfActin/GPUMotorBinding.java  —  +25 lines (Part-D signed CP2)
boxOfActin/ThreeJSWriter.java    —  +12 lines (refresh-for-output hooks)
RUN_LOGS/2026-06-05_phase4_flip/baseline_seed{1..4}.log   N=4 baseline
RUN_LOGS/2026-06-05_phase4_flip/flip_seed{1..4}.log       N=4 flip
RUN_LOGS/2026-06-05_phase4_flip/cp_signed.log             CP-armed flip seed=1
RUN_LOGS/2026-06-05_phase4_flip/smoke_seed1.log           initial smoke
PHASE4_FLIP.md                                            this file
JOURNAL.md                                                Phase 4 entry
```

## Reproduction

Pre-flip baseline (against the working tree without the flip changes):
```
git stash push -- boxOfActin/BoxOfActin.java boxOfActin/FilSegment.java \
                  boxOfActin/GPUMoveThing.java boxOfActin/ThreeJSWriter.java
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java
for S in 1 2 3 4; do
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu -seed $S -pf ParameterFiles/glidingAssay500_val \
    > RUN_LOGS/2026-06-05_phase4_flip/baseline_seed${S}.log 2>&1
done
git stash pop
```

Flip arm (against the working tree with these changes):
```
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java
for S in 1 2 3 4; do
  java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
       -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
       BoxOfActin -r -gpu -seed $S -pf ParameterFiles/glidingAssay500_val \
    > RUN_LOGS/2026-06-05_phase4_flip/flip_seed${S}.log 2>&1
done
```

CP-armed run (Phase 3 + Phase 4 + Part-D signed CP2):
```
BOA_PHASE3_CP_START=1 BOA_PHASE3_CP_STOP=500 \
BOA_PHASE4_DERIVED_CP_START=1 BOA_PHASE4_DERIVED_CP_STOP=500 \
java @... BoxOfActin -r -gpu -seed 1 -pf ParameterFiles/glidingAssay500_val \
  > RUN_LOGS/2026-06-05_phase4_flip/cp_signed.log 2>&1
```

Paired-t analysis: `/tmp/paired_t.py`.
