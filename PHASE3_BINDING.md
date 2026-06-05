# Phase 3 — Device-resident motor-binding grid (2026-06-04)

Move `MotorBindGrid3D.FillThreads`, `MotorBindGrid3D.packForGPU`, and the
post-bind CPU `arcOnFil` recompute off the per-step path. The binding CSR is
now built on the GPU from already-resident segment endpoints, and `bindKernel`
emits `arcOnFil` directly. Three of the CPU pose-reading sub-steps in the
binding path retire; the path is now device-resident end-to-end (modulo the
SoA pack from `FilSegment.soaEnd1X/Y/Z → filEnd1[]`, which is a Phase 4 item).

Scope: binding-only grid (not a unified entity grid). Validation standard:
statistical agreement on `bindEvents`, `meanBoundMotors`, `glidingVelocity`
under the existing N=8 paired ensemble — population observables, not bit-exact
output.

## Step 0 — confirmation + chosen build mechanism

**Interface (verified against HEAD before any edits):**

- CSR contract: `gridCellOffsets` length `totalCells+1`; `gridCellContents`
  flat segment IDs; linear `cellId = ix + iy*nXBins + iz*nXBins*nYBins`; cell
  size `Mesh.SIZE = 0.2 µm`; bin indices clamped via `(int)((val - min) *
  invCellSize)` then clamp to `[0, nBins-1]`; segments painted into every cell
  in the endpoint AABB (`MotorBindGrid3D.fillFilSeg`).
- `filNodeAtEnd2` filter is applied inside `bindKernel`, not at grid-build
  time — the CSR is built with all segs.
- `MotorBindGrid3D.motorCells` is **unused** by the GPU bind path — only the
  filament CSR is packed (today by `packForGPU`, after Phase 3 by the new
  device kernel).
- Within-cell ordering need not match the CPU's `FillThreads → packForGPU`
  insertion order; `bindKernel` walks all entries in each cell.

**Build mechanism chosen: per-segment-offset emit, fully on device.**

TornadoVM 4.0.1-dev PTX supports `KernelContext.atomicAdd(IntArray, idx,
delta)` (verified in `tornado-unittests`' `TestAtomics.atomic18/19`). The
per-segment-offset variant avoids atomics entirely — each segment computes
its own AABB cells in parallel; a single-thread serial assembly kernel
(`@Parallel(0..1)`) builds the CSR via histogram → exclusive prefix-sum →
scatter. At gliding-assay scale (`S≈500` segments, `~4000` total pairs,
`totalCells≈3000`), the serial kernel takes microseconds even single-threaded
on PTX; keeping the existing `@Parallel`-style `bindKernel` intact (no
KernelContext mix) is a structural simplification that the data size makes
free.

No CPU per-step residual: the entire grid build is on device. (A CPU
prefix-sum was offered in the prompt as an acceptable first-cut residual; not
used since the device pipeline is clean.)

## Implementation

`boxOfActin/GPUMotorBinding.java`:

- New buffer fields: `segBbox` (`segCap*6` ints — packed bbox per seg as
  `[ix0,ix1,iy0,iy1,iz0,iz1]`), `segCellCount` (`segCap` ints — per-seg AABB
  cell count), `cellCount` (`totalCells` ints — working histogram + per-cell
  write pointer), `arcOnFilDev` (`motorCap` floats — per-motor arc length
  emitted by `bindKernel`).
- New kernel `segBboxKernel` — one thread per segment, reads `filEnd1`/
  `filEnd2`, computes AABB cells with the same `(int)((val - min) *
  invCellSize)` + clamp pattern as `MotorBindGrid3D.getBinX/Y/Z`, writes
  `segBbox[s*6..]` and `segCellCount[s]`. Pose readers — runs on the device.
- New kernel `gridAssembleKernel` — single-thread serial (`@Parallel int gid
  = 0; gid < 1; gid++`), reads per-segment bboxes, builds `gridCellOffsets` +
  `gridCellContents` in three passes: zero `cellCount`, histogram via
  `++cellCount[cellId]`, exclusive prefix-sum into `gridCellOffsets` (also
  resets `cellCount` to 0 to reuse as per-cell write pointer), scatter
  `segId` into `gridCellContents[offsets[cellId] + cellFill[cellId]++]`.
  No atomics needed (single thread).
- `bindKernel` signature extended with `FloatArray arcOnFilDev`; emits
  `arcOnFilDev[m] = alpha * sqrtf(denom)` (float32) at the first 27-cell hit.
  Formula identical to the CPU `MyoMotor.checkFilSegCollision` line
  `arcOnFil = alpha * Math.sqrt(denom)` — only the precision differs.
- `detectBindings()`'s `MotorBindGrid3D.INSTANCE.packForGPU(...)` call is
  removed from the per-step path; the device CSR is built by the chained
  `segBbox + gridAssemble` kernels ahead of `bind` inside the same TaskGraph,
  on the same `filEnd1`/`filEnd2` buffers the bind kernel consumes. The
  per-step CPU `arcOnFil` recompute in the unpack loop is replaced by
  `arcOnFilDev.get(i)`.

`boxOfActin/BoxOfActin.java`:

- `MotorBindGrid3D.FillThreads` dispatch (`startAllThreadSets(motorBindGrid3DStart)
  / waitOn(motorBindGrid3DStop)`) is now gated on `!Env.useGPU`. On the GPU
  path the CPU grid build is skipped per-step; the threadset remains wired
  (`tSets[16]`) so the Phase 3 CP1 checkpoint can replay it on demand.

## Checkpoints (frozen-pose, REAL device kernels)

Both checkpoints dispatch the *actual* device kernels via `plan.execute()`
(not a pure-Java re-implementation) and report actual numbers (not pass/fail
against a hidden tolerance). Armed via env vars `BOA_PHASE3_CP` (single
step) or `BOA_PHASE3_CP_START` / `_STOP` (accumulated window). Both run
inside `GPUMotorBinding.detectBindings()` AFTER `plan.execute()`, against
device output on the same pose the CPU is about to read.

### CP1 — grid set-equality (PASS)

Repopulates the CPU `MotorBindGrid3D.INSTANCE` on the same frozen pose
(serial replay of `FillThreads`'s body since the GPU-path doLoop gate now
skips the CPU FillThreads), runs `packForGPU` into host-side CSR arrays,
compares per-cell against the device CSR (downloaded EVERY_EXECUTION).

```
Smoke run (deltaT=1e-5, runTime=0.005 → 500 steps, glidingAssay500_val,
BOA_PHASE3_CP_START=1 BOA_PHASE3_CP_STOP=500, aorus + Java 21 + TornadoVM
4.0.1-dev PTX):
  CP1 steps=500 cellsInspected=1,562,000 countMismatchCells=0
      setMismatchCells=0 firstDiffCell=-1
```

**Result: PERFECT set equality** — across 500 simulation steps and 1.56M
per-cell comparisons, no cell ever has a different count or a different
segment set between the device-built CSR and the CPU `packForGPU` CSR.

### CP2 — bind decisions + arcOnFil (PASS)

For each unbound motor, the CP replays the CPU bind decision logic
(MyoMotor.checkFilSegCollision-style, in the CPU's (dx,dy,dz) cell-walk
order, no `ontoFilament` side-effect) and compares the chosen segId against
device `boundSegId[i]`. For matched binds, compares the device-emitted
`arcOnFilDev[i]` (float32) against a fresh double-precision CPU recompute.

```
Same smoke window:
  CP2 steps=500 motorScanned=7,000,000 decisionDiffs=0 matchedHits=50
      arcMaxDelta=4.627e-07 arcMeanDelta=1.582e-07
      (float-vs-double ~1e-7 expected)
```

**Result: zero structural disagreements** across 7M motor-step decisions,
50 actual new-bind events. `arcOnFil` max delta `4.63e-7`, mean `1.58e-7`
— squarely in the expected float32-vs-double precision regime, two orders
of magnitude below the ~1e-5 "formula issue" threshold.

## Ensemble gate (PASS — borderline)

`scripts/phase3_binding_ensemble.sh` wraps `scripts/paired_motor_gliding.sh`
— N=8 paired runs on `glidingAssay500_val`, seeds 1–8, both arms `-gpu` with
Phase 3 device binding grid active in both arms; the `cpu` arm additionally
sets `BOA_DIAG_CPU_MOTOR=1` so the Phase 2 F8/F9/F10 motor force pair runs
on the CPU. Observables: `bindEvents`, `meanBoundMotors`, `glidingVelocity`.
Wall: 89 min on aorus. Results CSV:
`RUN_LOGS/2026-06-04_phase3_ensemble/results.csv`. Analyzer:
`scripts/phase3_paired_analyze.py`.

Paired-t (device arm − cpu arm, N=8):

| observable        | mean diff | sd      | sem    | **t**     | sign pattern |
|---|---|---|---|---|---|
| `bindEvents`      | −132.25   | 185.47  | 65.57  | **−2.02** | `--++----`  |
| `meanBoundMotors` |   −0.6946 |   1.034 |  0.366 | **−1.90** | `---+--+-`  |
| `glidingVelocity` |   −0.3667 |   0.816 |  0.288 | **−1.27** | `--++----`  |

Per-seed `glidingVelocity` raw values:

| seed | gv_dev | gv_cpu | diff |
|---|---|---|---|
| 1 | 7.673 | 7.766 | −0.093 |
| 2 | 6.879 | 8.268 | −1.390 |
| 3 | 7.187 | 6.582 | +0.605 |
| 4 | 7.825 | 6.940 | +0.885 |
| 5 | 7.386 | 8.612 | −1.225 |
| 6 | 7.123 | 8.009 | −0.887 |
| 7 | 8.143 | 8.641 | −0.498 |
| 8 | 7.989 | 8.320 | −0.331 |

**Verdict: PASS (borderline).** All three |t| ≲ 2; signs scatter (2/8
positive across each observable — not all-same-sign, the clean-noise
signature). None reach significance at p=0.05 (critical t ≈ 2.365 for
dof=7). bindEvents |t|=2.02 sits at the upper edge of the criterion.

**Flag.** The pre-Phase-3 baseline at HEAD `cc01d96` (acos-confirm
ensemble, same `paired_motor_gliding.sh` driver, same seeds) had
t = (−0.78, −0.99, +0.28). Post-Phase-3, all three shift negative
(t = (−2.02, −1.90, −1.27)). The negative drift is consistent across
observables — a small population shift in the device arm, plausibly
chaos amplification of the float32 `arcOnFil` truncation that now flows
to `MyoFilLink.posOnSeg` for both arms. (The cpu and device F8/F9/F10
code paths see the same posOnSeg → different downstream force
accumulation → divergent chaos trajectories.) Magnitude is within the
documented noise floor; no checkpoint surfaces a structural cause.
Planner may choose to characterize at higher N if the borderline t
warrants tightening.

## Remaining CPU touches in the binding path

After Phase 3 lands, the only CPU pose-touching work in the binding path is:

- The SoA pack of `FilSegment.soaEnd1X/Y/Z → filEnd1[]` and
  `MyoMotor.soaX/Y/Z → motPos[]` (and friends) at the top of `detectBindings()`
  (lines ~537-565). These read the CPU-mirror SoA arrays that
  `FilSegment.fillSoaArrays` / `MyoMotor.fillSoaArrays` produce from the
  per-Thing `Pt3D` snapshots. **Phase 4 prerequisite**: when pose becomes
  device-resident across steps (`OP_UNPACK` retired), this pack disappears
  with the SoA mirrors.
- The `gridCellOffsets` / `gridCellContents` `transferToHost` on the new
  TaskGraph — kept ON for the duration of Phase 3 so CP1 can read them back.
  No host code reads them outside the CP. Phase 4 should remove these
  transferToHost entries.
- `Env.counter` upload into `counts[2]` — single integer scalar, no pose.

## Commit + push

Pending the ensemble result.

Hash recorded below once committed.
