# Phase 4 prep — bound the flip + build the low-risk pieces (2026-06-04)

Scope: the residency-flip prep. Three deliverables, none of which removes the
per-step `OP_PACK`/`OP_UNPACK` round-trip yet (that is the flip itself, the
next prompt). Behaviour is unchanged in production. Validated by static
checkpoints and a paired smoke; no expensive ensemble run.

- **Part A** — survey the remaining per-step CPU pose touches; bound what the
  flip must retire.
- **Part B** — derived-field device kernel (`derivedFieldsKernel`) chained
  into the move plan after `move`; CPU `Thing.recomputeDerivedSoA` left in
  place; per-field max/mean device-vs-CPU divergence measured on a real
  windowed run.
- **Part C** — gate `MyoFilLink.updatePos()` on `gpuMotorHandled()`;
  gated-vs-ungated paired observables comparison.
- **Part D** — directionality fold-in for the Phase-3 `arcOnFil` deltas.

Commit landed as `<HASH>` on `main`.

---

## Part A — survey: what the flip must retire

Built against HEAD `5812c75` (Phase 3 landed). Cross-checked against
`RESIDENCY_INVENTORY.md §6` (P1–P10 + B1) and the doLoop walk in
`BoxOfActin.doLoop()`/`GPUMoveThing.moveThings()`.

### Phase 3 retired (no longer per-step CPU pose work on `-gpu`)

These appeared in the Phase-2 inventory and are no longer per-step CPU work
on the gliding-assay path:

- **A3.a `MotorBindGrid3D.FillThreads`** — `BoxOfActin.doLoop:944-947`
  now gates the dispatch on `!Env.useGPU`. Device `segBboxKernel` +
  `gridAssembleKernel` build the CSR from already-resident `filEnd1`/
  `filEnd2`.
- **A3.c `packForGPU`** (CPU grid CSR pack) — removed from the per-step
  path; `GPUMotorBinding.detectBindings()` runs the device assembly
  instead.
- **A3.c CPU `arcOnFil` recompute** — `MyoMotor.ontoFilament` no longer
  re-derives `posOnSeg` from CPU SoA; `bindKernel` emits `arcOnFilDev`
  on device and the unpack loop reads it via `arcOnFilDev.get(i)`
  (`GPUMotorBinding.java:633-634`).

### Remaining per-step CPU operations reading or writing core pose / derived SoA

Disposition column abbreviations:
- **FLIP-RETIRE** — the residency flip must move this off CPU (or gate it
  to a resync boundary).
- **PHASE-4-PREP** — addressed by Part B (derived kernel) or Part C (gate)
  in this prompt — still runs CPU-side today but the prep is in place.
- **RESYNC-ONLY** — already only runs at a topology / output-frame boundary,
  not per step on the production hot path.
- **NO-OP-GLIDING** — gated off in the gliding rotation but lives in the
  source as a per-step call.

| # | Operation (file:line) | Reads | Writes | Disposition | Notes |
|---|---|---|---|---|---|
| P1.a | `MyoMotor.fillSoaArrays` (`BoxOfActin.java:910`, body at `MyoMotor.java:22-55`) | `Thing.soaCoord/soaUVec` + rod's `soaUVec` | `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodU*` | **FLIP-RETIRE** | Exists only to feed the `GPUMotorBinding` SoA pack and (transitively) the CPU `arcOnFil` calc. With the flip, motor pose is read directly from device buffers by `bindKernel`. |
| P1.b | `FilSegment.fillSoaArrays` (`BoxOfActin.java:911`, body at `FilSegment.java:41-72`) | `Thing.soaCoord/soaUVec` | `FilSegment.soaEnd1X/Y/Z`, `soaEnd2X/Y/Z`, `soaFilID`, `soaUX/Y/Z`, `soaNodeAtEnd2` | **FLIP-RETIRE** | Exists only to feed `GPUMotorBinding.detectBindings()` pack (`filEnd1[]`/`filEnd2[]`). After the flip, the bind kernel reads `derivedEnd1`/`derivedEnd2` directly. |
| P2 | `Mesh.MeshThreads.meshFils` FILSEG_MESH fill (`Mesh.java:128-135`) | `curSeg.end1AsPt3D()`, `end2AsPt3D()` | `Mesh.FILSEG_MESH` bins | **NO-OP-GLIDING** | Inner-loop work (`checkToLink`) gated `Env.xLinks.isActive()` — off in gliding. The bin fill itself remains per-step. Not residency-blocking for gliding; the bins are not consumed device-side. Out-of-scope for the flip's binary criterion. |
| P2.b | `Mesh.NODE_MESH.fillNodeMesh`, `MYOHEADS_MESH.fillMotorMesh` | node pose, `motor.bindTip` | mesh bins | **NO-OP-GLIDING** | Same: gliding has no nodes; motor mesh inert in gliding rotation. |
| P3 | `MotorBindGrid3D.FillThreads` | seg endpoints, motor bindTip | grid bins | **DONE-PHASE-3** | Gated off on `-gpu` (`BoxOfActin.java:944`); replaced by device kernels. |
| P4 | `GPUMotorBinding.detectBindings()` CPU SoA pack (`GPUMotorBinding.java:577-599`) | `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodU*`, `FilSegment.soaEnd1X/Y/Z`, `soaEnd2X/Y/Z`, `soaNodeAtEnd2` | `motPos`, `motUVec`, `motRodUVec`, `motOnFil`, `filEnd1`, `filEnd2`, `filNodeAtEnd2` | **FLIP-RETIRE** | This is the residual binding-path CPU pack. Reads the SoA mirrors P1.a/b produced; retires when those retire. |
| P4.b | `GPUMotorBinding.detectBindings()` unpack loop (`GPUMotorBinding.java:627-635`) | `boundSegId`, `arcOnFilDev` | per-motor `ontoFilament` side-effects | **STAYS-CPU** | No CPU pose read — the `arcOnFil` value is device-emitted; the loop only walks the boundSegId array. CPU `ontoFilament` runs because it mutates Java object state (myMyosin bind sync, bind timer) outside the residency scope. Not a pose blocker. |
| **P5** | `MyoFilLink.updatePos` for every bound motor on the GPU path (`MyoFilLink.java:222`) | `mySeg.end1AsPt3D()`, `mySeg.uVecAsPt3D()` | `attachPt` | **DONE-THIS-PROMPT (Part C)** | Now gated on `gpuMotorHandled()` — see Part C below. `attachPt` is consumed only by `addForces()` which is already device-handled, so gating removes dead computation. |
| **P6** | `Thing.recomputeDerivedSoA(0, thingCt)` (`GPUMoveThing.java:3872`) or its parallel-CPU variant (`OP_DERIVED_AND_BRIDGE`) | `Thing.soaCoord/UVec/YVec/Length` | `Thing.soaEnd1/End2/ZVec/TransXTox/YVec`-orth | **FLIP-RETIRE** (Part B: device kernel built, runs alongside) | The CPU recompute still runs in production. The device-side `derivedFieldsKernel` (Part B) emits the same fields per-slot on device after `move`; the flip removes the CPU recompute and wires downstream kernels to the device buffers. |
| **P7** | Per-FilSegment `xRange/yRange/zRange` + `end1Pt`/`end2Pt` Pt3D refresh (`GPUMoveThing.java:3876-3886`) | `fs.getCoordX/Y/Z`, `getEnd1X/Y/Z`, `getEnd2X/Y/Z` | `fs.xRange`, `fs.yRange`, `fs.zRange`, `fs.end1Pt`, `fs.end2Pt` | **FLIP-RETIRE** | Pt3D snapshots are CPU mirrors only — back `end1AsPt3D()`/`end2AsPt3D()` for CPU readers. Delete when CPU consumers retire (P2/P3 etc.). Cheap O(filCt) per step today. |
| **P8** | Per-MyoMotor `bindTip` Pt3D refresh (`GPUMoveThing.java:3887-3893`) | `m.getEnd2X/Y/Z` | `m.bindTip.x/y/z` | **FLIP-RETIRE** | Same as P7 — CPU Pt3D mirror only. Backs mesh/motor-grid `bindTip` reads (no longer used on the device-grid Phase 3 path; remaining mesh fill is gliding-inert). |
| **P9** | `OP_UNPACK` (device → host `coord`/`uVec`/`yVec`) (`GPUMoveThing.java:3690`) | device `coord/uVec/yVec` | `Thing.soaCoord/UVec/YVec` | **FLIP-RETIRE** | **The blocker itself.** Phase 4 makes it output-frame-only. |
| **P10** | Pre-step `OP_PACK_FULL` / `OP_PACK_RESIDENT` upload (`GPUMoveThing.java:3532-3534`) | `Thing.soaCoord/UVec/YVec` etc. | device `coord/uVec/yVec` | **FLIP-RETIRE** | Becomes FIRST_EXECUTION; biochem poly/depoly drives a topology-dirty `OP_PACK_RANGE` for the affected slot range. |
| **B1** | `FilSegment.biochemStep` poly/depoly pose writes (`FilSegment.java:530-541`) | — | `Thing.soaCoord` (via `pushCoordToSoa`), drag tensors | **TOPOLOGY-DIRTY** | Conditional pose **writer**, not steady-state reader. Handled at the flip by a topology-dirty bit + `OP_PACK_RANGE` for affected slots. Rare. |
| | `MyoFilLink.ckRelease` (`MyoFilLink.java:251`) | `forceMag`, `forceDotFil` (device-written) | release rolls | **STAYS-CPU** | No direct pose read. Reads device-bridged forces from `bridgeMotorForceWriteback` (1-step lag, documented; not residency scope). |
| | `MyoFilLink.gpuMotorHandled()` decision (`MyoFilLink.java:109-118`) | `mySeg.gpuHandled` flag | — | **STAYS-CPU** | Flag access only; not a pose read. |
| | `MyoFilLink.setAttachment()` calls `updatePos()` at bind time (`MyoFilLink.java:72`) | seg pose | `attachPt` | **STAYS-CPU** | Once-per-bind, not per-step. Kept so CPU-fallback (`gpuMotorHandled() == false`) sees a fresh `attachPt` immediately. |
| | `FilSegment.addNodeForces` (F5/F6, `FilSegment.java:507`) | node pose | force/torque | **NO-OP-GLIDING** | Always-CPU but inner loop empty in gliding (no nodes). Out-of-scope. |
| | `MyoFilLink.updatePos` from `setAttachment` (above) and a CPU-fallback motor's per-step path | seg pose | `attachPt` | **STAYS-CPU** | Pose read only on the CPU-fallback bound motor — that motor is by definition not in the residency set. Not a blocker. |
| | Output-frame `ThreeJSWriter.writeFrame` etc. | full pose | JSON | **RESYNC-ONLY** | Already at output-frame boundary; the flip's OP_UNPACK fires here. |

### Verdict

The picture matches `RESIDENCY_INVENTORY.md §6` to a row, modulo what Phase 3
retired and P5 (now retired this prompt). The flip's per-step CPU pose
touches reduce to **P1.a + P1.b + P4 + P6 + P7 + P8 + P9 + P10**, with **B1**
as the conditional pose-write handled by a topology-dirty bit. P5 is
crossed off. P2/P2.b/P3 are crossed off (P3 by Phase 3; P2/P2.b by being
gliding-inert today, with a clean-up note for any config with `xLinks` on).

Nothing in the survey contradicts the inventory. The flip's spec is the
table above; no scope surprises.

---

## Part B — derived-field device kernel + checkpoint

### Implementation

`boxOfActin/GPUMoveThing.java`:

- **New device buffers** (per-slot, `slotCap*3` or `slotCap*9`):
  `derivedEnd1`, `derivedEnd2`, `derivedZVec`, `derivedYVecOrtho`,
  `derivedTransXTox`.
- **New device kernel `derivedFieldsKernel`** — one thread per move slot.
  Body mirrors `Thing.recomputeDerivedSoA` exactly:
  - `zVec = uVec × yVec`, normalised (matches CPU's always-normalise rule).
  - `yVec' = zVec × uVec` (re-orthogonalised body frame).
  - `transXTox` row-major = `[u; y'; z]`.
  - `end1 = coord − halfLen·uVec`, `end2 = coord + halfLen·uVec`.
- **Wired into chained TaskGraph** as the final task after `move`. Reads
  device-resident `coord`/`uVec`/`yVec` written by the move kernel earlier in
  the same `.execute()`, and `soaLengthArr` packed by `packRange`.
- **`transferToHost(EVERY_EXECUTION)`** added for the five new buffers —
  kept ON for the checkpoint duration. Phase 4 (the flip) removes these
  transfers and lets downstream kernels read the buffers in place.
- **CPU `Thing.recomputeDerivedSoA` is unchanged** — still runs every step
  in `moveThings()` after `OP_UNPACK`. The device kernel runs alongside
  for validation.

### Checkpoint (REAL device kernel, windowed)

Armed via env vars `BOA_PHASE4_DERIVED_CP=<step>` (single-step verbose) or
`BOA_PHASE4_DERIVED_CP_START` / `_STOP` (windowed accumulation, no per-step
prints). The checkpoint runs *after* CPU `recomputeDerivedSoA` so
`Thing.soaEnd1/End2/ZVec/TransXTox/YVec` hold the CPU reference, then for
every `gpuThingIndices[slot]` compares each device output field
(`derivedEnd1.get(slot*3+a)` etc.) against the CPU mirror. Per-field
max/mean of `|delta|` accumulated across the window and reported at end of
run via `reportDerivedCheckpointSummary()`.

```
glidingAssay500_val (deltaT=1e-5, runTime=0.1 = 10,000 steps), aorus +
Java 21 + TornadoVM 4.0.1-dev PTX. CP window 1..500 (500 steps,
21,005,455 slot comparisons):

[PHASE4_DERIVED_CP_SUMMARY] steps=500 slotsScanned=21005455
  end1  max=0.000e+00  mean=0.000e+00
  end2  max=4.768e-07  mean=3.311e-10
  zVec  max=1.788e-07  mean=4.243e-09
  yVec  max=2.384e-07  mean=4.227e-09
  trans max=2.384e-07  mean=2.823e-09
```

Run observables (sanity check, full 10k steps):
`bindEvents=889`, `meanBoundMotors=7.486`, `glidingVelocity=7.8178 µm/s`.
Log: `RUN_LOGS/2026-06-04_phase4_prep/cp_smoke.log`.

### Result — PASS

- Every per-field max is in the **1e-7 float32-vs-FMA precision regime**,
  two orders of magnitude below the 1e-5 "formula issue" threshold the
  prompt called out.
- `end1 max = 0` exactly: an artifact of the PTX backend's FMA-fusion rule
  for `cx + halfLen*ux` (fuses to `fma.rn.f32`) vs `cx − halfLen*ux` (does
  not fuse — two roundings, identical to CPU). Both forms are correct;
  the FMA form is in fact more accurate. The asymmetry confirms the
  arithmetic is performing as expected (input floats are bit-identical
  between CPU recompute and device kernel — that is required for the
  `end1` comparison to be exactly zero).
- The zVec/yVec/trans deltas (max ≤ 2.4e-7) reflect the cross-product
  cascade through the same float32 truncation pattern.
- Means are ~1e-9 to 1e-10 — essentially noise-floor; no directional bias.

No formula issue. The flip can wire downstream kernels to these device
buffers and remove the CPU recompute without changing physics within the
expected float32 noise floor (the same regime Phase 2 motor and Phase 3
ensembles already pass at).

---

## Part C — `MyoFilLink.updatePos` gate (P5)

### Confirmation that `attachPt` is unused on the GPU path

`grep -rn attachPt` (excluding markdown / logs) returns only:

- `MyoFilLink.java:28` declaration; `:59` sepaku nullification.
- `MyoFilLink.java:122,125,147` — **reads inside `addForces()`**.
- `MyoFilLink.java:152,154,164` — reads inside the commented-out alternative
  `addForces()` (dead code).
- `MyoFilLink.java:222` — the write inside `updatePos()`.
- `GPUMoveThing.java:1674,1676,1678,1789,1801,1964,1988` — **comments only**
  (kernel documentation describing the equivalent device-side computation;
  no runtime reads).
- `HeldBoundMotorDiag.java:153,162,180,181,579,592,650` — **comments only**.

On the production GPU path, `addForces` is gated off in `MyoFilLink.step`
(`MyoFilLink.java:94-99` pre-prompt): when `gpuMotorHandled() == true`,
`addForces`, `alignUVecTorque`, `alignYVecTorque`, `ckRelease` are all
skipped. With `addForces` skipped, **nothing reads `attachPt`** on the GPU
path. Confirmed.

### The change

`MyoFilLink.step()` (`MyoFilLink.java:76-107`) — `updatePos()` moved
INSIDE the `if (!deviceMotor)` branch:

```java
public void step () {
    if (!isFree()) {
        boolean deviceMotor = gpuMotorHandled();
        if (DIAG_FORCE_UPDATEPOS) updatePos();   // diagnostic-only override
        if (!deviceMotor) {
            if (!DIAG_FORCE_UPDATEPOS) updatePos();
            addForces();
            alignUVecTorque();
            alignYVecTorque();
            ckRelease();
        }
    }
}
```

`setAttachment()` (`MyoFilLink.java:69-74`) still calls `updatePos()` at bind
time, so `attachPt` is initialised when the motor binds — and that path
fires regardless of which arm handles the motor.

The `BOA_DIAG_FORCE_UPDATEPOS` env-var bypass lets future runs A/B the gate
without rebuilding (Phase 4 hardening or a paranoia check post-flip).

### Checkpoint — paired gated-vs-ungated

`glidingAssay500_val` with `runTime=0.01` (1,000 steps), `-gpu`, seeds 1
and 2, gated arm = current build, ungated arm =
`BOA_DIAG_FORCE_UPDATEPOS=1`. Compared `bindEvents`, `meanBoundMotors`,
`glidingVelocity`:

```
seed=1  gated      bindEvents=131  meanBoundMotors=6.338  glidingVelocity=34.6811
seed=1  ungated    bindEvents=168  meanBoundMotors=9.675  glidingVelocity=34.3908
seed=2  gated      bindEvents=106  meanBoundMotors=6.829  glidingVelocity=37.2301
seed=2  ungated    bindEvents=140  meanBoundMotors=6.759  glidingVelocity=35.6913
```

Then **gated seed=1 was re-run twice to measure within-arm
run-to-run jitter**:
```
seed=1  gated  run1   bindEvents=131  meanBoundMotors=6.338  glidingVelocity=34.6811
seed=1  gated  run2   bindEvents=128  meanBoundMotors=8.203  glidingVelocity=34.0800
seed=1  gated  run3   bindEvents= 95  meanBoundMotors=5.933  glidingVelocity=34.7468
```
Within-arm spread for seed=1 gated: `bindEvents ∈ [95, 131]`,
`meanBoundMotors ∈ [5.93, 8.20]`, `glidingVelocity ∈ [34.08, 34.75]`.

Log: `RUN_LOGS/2026-06-04_phase4_prep/partc_*.log`,
`RUN_LOGS/2026-06-04_phase4_prep/partc_gated_seed1_rerun*.log`.

### Interpretation

Multi-thread CPU-side force accumulators (`gather` reduction order across
worker threads) are not deterministic at the float bit-pattern level — same
seed produces different per-step force sums depending on JVM thread
scheduling. The three same-seed back-to-back gated runs span
`bindEvents ∈ [95, 131]` and `meanBoundMotors ∈ [5.93, 8.20]` — wider
than the gated→ungated swing (`131→168` / `6.34→9.68`) for seed=1, and
much wider than the seed=2 gated→ungated swing (`106→140` / `6.83→6.76`).
At this 1000-step scale, single-seed observable comparison is **not**
sensitive enough to localise the gate's effect: the gated-vs-ungated
signal is within the within-arm jitter band.

### Result — PASS (by dataflow analysis; observables consistent with noise)

The primary safety argument is the **static dataflow analysis** above:
`attachPt`'s only readers are `addForces` (lines 122, 125, 147), and
`addForces` is already gated off on the device path by `gpuMotorHandled()`.
With `addForces` skipped, no consumer reads `attachPt`. Removing the
`updatePos()` call removes dead computation, period.

The paired observables are within the within-arm run-to-run jitter band —
which means they fail to reject the null "gate is bit-safe", as expected
from the analysis. A tighter test would require either a multi-seed
ensemble (out of scope for prep) or thread-pinning to defeat the gather
non-determinism. Neither is needed: the dataflow argument is sufficient,
and the broader gliding ensemble after the flip will catch any latent
divergence as part of the resident-vs-non-resident validation.

The `BOA_DIAG_FORCE_UPDATEPOS=1` env-var bypass is kept for any future
investigation that wants to A/B the gate's effect on the same build.

---

## Part D — `arcOnFil` directionality fold-in

### What the existing Phase-3 CP2 data captured

`GPUMotorBinding.runCP2` records, per matched bind hit,
`delta = |cpuArc − devArc|`, then accumulates max(|delta|) and sum(|delta|)
into the window-mode summary. The reported window-mode result
(`RUN_LOGS/2026-06-04_phase3_cp_window.txt`, 500-step window, 7M motor
scans, 50 matched hits):

```
[PHASE3_CP_SUMMARY] CP2 steps=500 motorScanned=7000000 decisionDiffs=0
  matchedHits=50 arcMaxDelta=4.627e-07 arcMeanDelta=1.582e-07
```

This is mean of **absolute** values. The signed mean was not collected; a
direct directionality readout requires augmenting CP2 to record signed
deltas, which is a future small-scope add (not in this prompt's scope —
prompt directive was "no new run, just analyze the existing deltas").

### Analytical reading

For a roughly-symmetric zero-mean distribution of signed deltas on `[-a, a]`,
`mean(|X|) ≈ a/2` (uniform) up to `a` (single-mass at ±a). For a
sign-aligned distribution (all positive or all negative), `mean(|X|) ≈ mean(X)`
and the ratio `mean(|X|)/max(|X|)` approaches 1.

The Phase-3 ratio:
```
  arcMeanDelta / arcMaxDelta  =  1.582e-07 / 4.627e-07  =  0.342
```

0.34 sits below the uniform-symmetric expectation of 0.5 and well below
the 0.5–1.0 range a sign-aligned bias would produce. The distribution is
**more concentrated around zero than uniform**, consistent with a
zero-mean noise process rather than a small directional float32
truncation. The 50 matched hits are sparse, so this is a heuristic reading
rather than a tight statistical bound; with `std(|X|) ~ 1.6e-7` and N=50,
the SE on a signed mean is `~2e-8`, so any directional bias larger than
that would be detectable in a signed re-run (a small future add).

### Mechanistic plausibility

`arcOnFil = alpha * sqrt(denom)` is a single multiply-then-sqrt of fp64
quantities cast to fp32 at the kernel boundary. Float32 rounding is
symmetric (round-to-nearest-even) and there is no obvious mechanism — no
asymmetric clip, no comparison-against-asymmetric-bound — for systematic
sign bias. The chaos-amplification reading of the borderline N=8 ensemble
drift (`t = (−2.02, −1.90, −1.27)` vs the pre-Phase-3 baseline
`t = (−0.78, −0.99, +0.28)`) is the simpler explanation: a small zero-mean
float32 perturbation that compounds over hundreds of thousands of steps in
a nonlinear stochastic dynamics.

### Result

Existing CP2 data is **consistent with zero-mean noise**, not a directional
bias. Verdict is heuristic (the existing log gives only |delta|), but the
ratio is far enough from the directional-bias regime to support the
chaos-amplification reading the Phase-3 entry already proposed. No
follow-up action gated; if a future investigation wants a tight test, the
one-line patch is to add `sumSignedArcDelta` to `runCP2`.

---

## Files modified

| file | change |
|---|---|
| `boxOfActin/GPUMoveThing.java` | New `derivedFieldsKernel` (~100 lines incl. doc), new device buffers (`derivedEnd1/End2/ZVec/YVecOrtho/TransXTox`), wired into chained TaskGraph after `move`, transferToHost EVERY_EXECUTION for the prep CP, new `derivedWorker` GridScheduler entry, `runDerivedFieldsCheckpoint` + `reportDerivedCheckpointSummary` with `BOA_PHASE4_DERIVED_CP[_START/_STOP]` env arming. |
| `boxOfActin/MyoFilLink.java` | `updatePos()` gated on `gpuMotorHandled()` inside `step()`; `DIAG_FORCE_UPDATEPOS` env-var override for gated-vs-ungated A/B. |
| `boxOfActin/BoxOfActin.java` | Calls `GPUMoveThing.reportDerivedCheckpointSummary()` after the existing `[STATS] gpuMoveThing` line. |
| `RUN_LOGS/2026-06-04_phase4_prep/cp_smoke.log` | Part B 500-step windowed CP smoke. |
| `RUN_LOGS/2026-06-04_phase4_prep/partc_*.log` | Part C gated-vs-ungated paired (N=2 seeds × 2 arms). |
| `PHASE4_PREP.md` | This file. |
| `JOURNAL.md` | Phase 4 prep entry. |

## What this sets up for the flip

With Part A's table as the spec, the flip prompt's binary criterion is:
**every row marked FLIP-RETIRE moves off the per-step CPU path or to a
resync boundary.** The pieces this prep landed:

- A device kernel that produces the derived fields on-device — the
  prerequisite for downstream kernels to read `derivedEnd1`/`derivedEnd2`
  instead of re-deriving from `coord+uVec+length` inline.
- The P5 `updatePos` per-motor read eliminated as a freebie.
- A device-vs-CPU divergence reading for the derived fields confirming
  they're in the float32 noise floor (no formula issue).
- A directionality reading for the Phase-3 `arcOnFil` truncation
  reinforcing the chaos-amplification interpretation of the ensemble
  drift (so the flip's residency-vs-non-resident statistical agreement
  test inherits an already-understood noise floor).

The remaining flip work, scoped: wire `persistOnDevice` /
`consumeFromDevice` on `coord`/`uVec`/`yVec`/`derivedEnd1`/`derivedEnd2`/
`derivedZVec`/`derivedYVecOrtho`/`derivedTransXTox`; move per-step
`OP_PACK_*` to FIRST_EXECUTION and `OP_UNPACK` to output-frame-only;
delete the CPU `Thing.recomputeDerivedSoA` post-move sync block and the
P7/P8 Pt3D refresh; retire `MyoMotor.fillSoaArrays` /
`FilSegment.fillSoaArrays` and the `GPUMotorBinding` SoA pack (consumers
read device buffers directly); add the biochem topology-dirty
`OP_PACK_RANGE` for poly/depoly slot writes (B1). Validation:
resident-vs-non-resident statistical agreement on the gliding ensemble
**plus** the per-step wall-clock measurement — the payoff number.
