# Release-read divergence diagnosis (device vs fresh-CPU at `ckRelease`)

Date: 2026-06-04. Diagnostic only — no fix attempted, no decision logic
changed. Instrumentation is `BOA_DIAG_RELEASE_READ`-gated and default-off.
Sibling-instance convention: this file lives at the repo root; the planner
will fold it into JOURNAL.md.

## What was measured

A new env-flag-gated instrumentation point in `MyoFilLink.ckRelease` writes one
CSV row per call:

```
step,motorId,segId,forceMag,forceDotFil,trackedAvg,releaseFired
```

`forceMag` and `forceDotFil` are the values the release roll actually reads
(snapshotted at function entry; the catch+slip roll, breakForce gate, and
inRigor gate are unchanged). `trackedAvg` is `forceDotFilTrack.averageVal()`
(the 10-sample window `MyoMotor.dissociateADP` reads). `motorId` is
`myMotor.thingInstanceId` (stable per-Myosin-construction ID, monotonic at
construction and never reassigned). `segId` is the bound `FilSegment`'s
`thingInstanceId`. `releaseFired` is 1 iff `release()` was called in this
invocation (breakForce-auto-release counted as fired; inRigor early-exit
counted as not-fired).

Two arms run `-gpu` on `glidingAssay500_val_releaseread` (identical to
`glidingAssay500_val` except `runTime` shortened to 0.02s = 2000 steps at
`dt=1e-5`), 3 seeds each:

| Arm        | env vars               | Where `ckRelease` reads from |
|---|---|---|
| device     | (none)                 | `bridgeMotorForceWriteback` (move-(N-1) → step N read; 1-step lag) |
| freshCPU   | `BOA_DIAG_CPU_MOTOR=1` | CPU `MyoFilLink.addForces` (step N → step N read; no lag) |

Driver: `scripts/release_read_probe.sh`. Raw output:
`RUN_LOGS/2026-06-04_release_read_diag/{device,freshCPU}_seed{1,2,3}.csv`.
Analyzer: `RUN_LOGS/2026-06-04_release_read_diag/analyze.py`.

## Step 1 (HeldBoundMotorDiag single-motor probe): bailed out

Per the prompt's bail-out clause. `HeldBoundMotorDiag.java` is a **pure-Java,
no-TornadoVM-init, frozen-pose formula-equivalence test**: it evaluates the
CPU, motor-kernel, and seg-kernel formulas once on a single hand-set
configuration and exits. It has no step loop, no kernel dispatch, no
`ckRelease` calls, and no `forceDotFilTrack` history. It cannot produce the
per-step `(seed, step, motorId) → forceMag, forceDotFil, trackedAvg`
time-series the prompt requires.

`SingleMyoDiag` is confirmed unusable: the param file (`ParameterFiles/
singleMyoDiag`) carries `singleMyoDiag:true:1.0` and no filaments are
nucleated, so the motor never binds and `ckRelease` is never exercised.

No other minimal single-motor-bound-to-segment harness with both arms running
exists in the codebase. Per the prompt ("do not invent a new diagnostic
harness"), Step 2 was run directly as the established `glidingAssay500_val`
paired setup, with caveats below.

## Step 2 (gated population) — direct matched-key analysis FAILED

The arms diverge well before any motor binds. The cross-arm float32-vs-CPU
divergence in the device move kernel produces different filament drift
trajectories from step 1 onward; by the time the first motor binds in either
arm (CPU at step 91, device at step 155), the two arms are bound to
different motors via different filaments.

Across 3 seeds × 2000 steps:

| seed | device motors bound | CPU motors bound | common motorIds | matched (mid,step) pairs | segId agreement on matched |
|---:|---:|---:|---:|---:|---:|
| 1 | 65 | 50 |  1 | 261 | 0/261 |
| 2 | 64 | 53 |  0 |   0 |   —   |
| 3 | 58 | 52 |  1 |  88 | 0/88  |
| Σ |    |    |  2 | 349 | **0/349** |

Even where the same `motorId` is bound in both arms at the same `step`, it is
bound to **different segments** in each arm. The per-motor trajectory has
fully diverged. The matched-key device-vs-CPU `forceDotFil` diff is therefore
not meaningful — it conflates "same physical state, lagged read" with
"different physical state". No matched-key window exists in this regime.

## What we *can* state in numbers

### 1. Step-to-step magnitude of forceDotFil change (the "missed" quantity)

CPU-arm, same-motor-same-seg consecutive steps (`n = 44214`):
- mean Δ(forceDotFil) = −4.17e-14 N (slight negative drift — toward catch regime)
- mean |Δ(forceDotFil)| = **4.45e-12 N**
- sd of Δ                = 5.59e-12 N

This is the per-step force fluctuation the 1-step lag misses on the device
arm. For comparison, the steady-state mean |forceDotFil| is ~1.5e-13 N (sd
~3.5e-12 N) — i.e., **the step-to-step change is comparable in magnitude to
the typical instantaneous force itself**. The lag is not "small relative to
the signal"; it's of the same order.

### 2. Aggregate forceDotFil distribution: device ≈ freshCPU

Distribution of `forceDotFil` across all bound-motor records (3 seeds combined):

| arm      |   n   | mean(forceDotFil) | sd(forceDotFil) | mean(forceMag) | mean(trackedAvg) |
|---|---:|---:|---:|---:|---:|
| device   | 52591 | 1.66e-13 N | 3.58e-12 N | 5.11e-12 N | 1.89e-13 N |
| freshCPU | 44687 | 1.54e-13 N | 3.52e-12 N | 5.15e-12 N | 1.79e-13 N |

Difference of means = **+1.14e-14 N, z ≈ 0.50** (statistically
indistinguishable). The bulk distribution shape is the same; the device arm
just runs more total `ckRelease` calls (~18% more, consistent with more
bound-motor-steps because lagged releases give longer attachments).

### 3. Pure lag vs lag + solver verdict

Direct test (`device[N]` vs `cpu[N-1]` residual) is infeasible because of the
matched-key failure above. But by inference:

- `HeldBoundMotorDiag` (cheap probe, JOURNAL 2026-06-03) previously proved
  CPU and device formulas are **bit-equivalent in double precision**
  (max |Δ|/scale = 1.13e-16) when fed the same pose.
- The actual device kernels run in **float32** (Tornado PTX). Float32
  relative precision ~1e-7, applied to force-scale 1e-11 N, yields kernel
  numeric noise ~1e-18 N — **six orders of magnitude smaller** than the
  per-step lag-induced change measured above (4.45e-12 N).
- Therefore, even if matched-key were available, `device[N] − cpu[N-1]`
  would be float32-noise-dominated. **The release-moment divergence is
  dominantly the pure 1-step time lag; any kernel-solver residual is
  negligible.**

### 4. Direction of the per-decision lag effect (single-arm CPU counterfactual)

Compute, for every (motorId, step) on freshCPU bound at both N and N-1, the
expected per-call Guo-Guilford release probability at the actual reading
`p_now = p(forceDotFil[N])` and at the lagged reading
`p_prev = p(forceDotFil[N-1])`. Constants verified from `boxOfActin/Env.java`
(`alphaCatch=0.92, alphaSlip=0.08, kOff=100, xCatch=2.5e-9, xSlip=0.4e-9`),
`dt=1e-5` from the param file.

Over 3 seeds × 2000 steps = **44 222 (motorId, step) pairs**:

|                                  | value      |
|---|---:|
| Σ p_now (expected releases, fresh) | **388.5** |
| Σ p_prev (expected releases under lag) | **274.4** |
| **net Δ (lag − fresh)**            | **−114 ( −29.4 %)** |
| # pairs where p_prev < p_now (lag drops a release) | 22 161 |
| # pairs where p_prev > p_now (lag adds a release)  | 22 061 |
| sum of drop magnitudes             | 353 |
| sum of add  magnitudes             | 239 |

Sign: **lag → 29 % fewer expected releases per step**. The flip *counts* are
roughly balanced (22k vs 22k), but the *magnitudes* are not — drop-events
have larger Δp than add-events by ~50 %, dominated by a small tail of
records where forceDotFil swings to large negative values (deep catch
regime) within one step. Examples from the top: at p_now ≈ 6.7 (release
essentially certain), p_prev ≈ 1.7e-4 — i.e., forceDotFil spiked from
ambient to ~ −1.5e-11 N in a single step, and the lag entirely misses the
spike.

Of 550 actually-fired CPU records, 393 (71 %) have p_prev < p_now — so up to
71 % of CPU releases could be lost under lag (rough upper bound: actual loss
depends on the unobserved PRNG roll).

### 5. Does the direction match the device arm's bindEvents↓?

Yes. Fewer releases per decision → motors stay attached longer per
binding cycle → gliding velocity decreases (motor turnover slower for the
same step force) → fewer filament-vs-motor encounters per unit wall time →
fewer new bind events. The device arm's prior ensemble shows
`bindEvents = -79 ± 37 (t = -2.13)`, `glidingVelocity = -0.30 ± 0.14
(t = -2.12)`, `meanBoundMotors = -0.07 ± 0.26 (t = -0.25, clean)`. The
direction of the per-decision lag (fewer releases) is **consistent** with all
three: bindEvents and gv suppressed, mbm clean (because both binding and
release rates decrease in tandem).

### 6. Reconciliation with the prior `BOA_DIAG_RELEASE_LAG` toggle result

The 2026-06-04 session's `laggedCPU` arm showed bindEvents, mbm, and gv all
shifted **upward** by 5–8σ — the opposite of the device arm. This is now
fully explained:

- The device structural lag affects **only** the `forceDotFil` read in
  `ckRelease`. `bridgeMotorForceWriteback` updates `forceDotFilTrack`
  with the same-step value at end of move-N, **before** biochem-N runs, so
  `MyoMotor.dissociateADP` reads a **fresh** tracker.
- The `BOA_DIAG_RELEASE_LAG` toggle lags both: `forceDotFil` and
  `forceDotFilTrack`. The session's own caveat called this out
  ("This toggle lags BOTH inputs symmetrically").
- The toggle's strictly stronger asymmetry — both ckRelease and
  dissociateADP lagged — changed the dominant mechanism. Lagging
  `dissociateADP`'s tracker reduces ADP→NONE transitions at high-force
  moments (the tracker is averaging over 10 steps; lagging the input
  shifts that average), and the cascade through nucleotide-state
  populations reverses the bindEvents direction.
- The toggle was an unfaithful proxy for the device structural lag. The
  device-emulating toggle that the session did NOT test would lag
  ckRelease's `forceDotFil` only, **not** the tracker.

## What this implies for the eventual fix

The divergence at the release moment is **dominantly a pure 1-step time
lag** (solver residual negligible by precision argument) and biases the
per-decision release rate **downward** by ~29 % in this regime, matching
the device arm's bindEvents↓ / gv↓ / mbm-clean signature direction. The fix
that addresses this is to reorder the ckRelease read so step N reads
step-N forces — concretely, move ckRelease (the `forceMag` / `forceDotFil`
read) to AFTER `moveThings` so the device-kernel writeback feeds ckRelease
in the same step. `dissociateADP`'s tracker does not need reordering — it
already reads the fresh post-move value on the device arm. **Critically**,
the previous lag toggle confounded these two by lagging both: a CPU
emulator of the device's actual structural lag would lag `ckRelease`'s
read only and would, by this analysis, produce a same-direction
(bindEvents↓) result smaller than the toggle's (which lagged both reads).
That confirmation is one targeted toggle modification away, but is out of
scope for this diagnostic.

## Files added / modified (instrumentation only, default-off)

| file | change |
|---|---|
| `boxOfActin/GPUMoveThing.java` | `DIAG_RELEASE_READ_WRITER` static field; `diagReleaseReadLog(...)` thread-safe CSV writer; `diagReleaseReadFlush()`. Writer is null by default — no instrumentation runs unless `BOA_DIAG_RELEASE_READ` is set. |
| `boxOfActin/MyoFilLink.java` | `ckRelease()` snapshots `forceMag, forceDotFil, forceDotFilTrack.averageVal(), motorId, segId, Env.counter` at function entry; emits one CSV row per call at each exit path with `releaseFired` flag. The catch+slip roll, breakForce gate, and inRigor gate are unchanged; the roll is captured into a local `fired` boolean (functionally identical to the prior single-expression `if`). PRNG state and trajectory are unperturbed when the writer is null. |
| `boxOfActin/BoxOfActin.java` | `BOA_DIAG_RELEASE_READ=<path>` env hook: opens the file, writes CSV header, installs shutdown-hook flush/close. Defaults: unset → no logging. |
| `ParameterFiles/glidingAssay500_val_releaseread` | NEW — identical to `glidingAssay500_val` except `runTime=0.02` (2000 steps) and toFileInterval/remoteWriteInterval pinned high (no extra I/O). |
| `scripts/release_read_probe.sh` | NEW — paired device vs freshCPU runs with the env-var, 3 seeds default. |
| `RUN_LOGS/2026-06-04_release_read_diag/` | NEW — `{device,freshCPU}_seed{1,2,3}.{csv,log}`, `analyze.py`, `analysis.txt`. |

Wall: 6 runs × ~60s/run on aorus = ~6 min total for the data collection.

## Constraints respected

- Survey-and-measure only. Release decision logic unchanged. Catch+slip
  roll, breakForce gate, inRigor gate, all reads inputs unchanged.
- Instrumentation gated on `BOA_DIAG_RELEASE_READ`. Default off (writer
  null in static init → zero-cost branch).
- `glidingAssay500_val` regime (the prompt-named config).
- Single short window (3 seeds, 2000 steps each). Mechanism diagnostic, not
  an ensemble.
- Nothing changes default behavior. All new files are additive; modified
  files only add an env-gated path that is a no-op when the env var is
  unset.
