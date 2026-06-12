# Recompute residency: gating the per-step pose-audit scan (`onStepStart`)

Branch `benchmark-contractile-dense`. Machine aorus1, RTX 5070 (12 GB), Java 21,
TornadoVM 4.0.1-dev PTX. 2026-06-12. `V1_FINISH_LINE.md` item **A1**. Follows
`COPYOUT_RESIDENCY.md` Part 2, which decomposed the host "other" bucket and flagged
`recompute` / `GPUMoveThing.onStepStart` as the GPU path's superlinear elephant
(1.19 → 27.72 ms/step over 1×→8×, ~23×).

## TL;DR

- **Phase 1 diagnosis refuted the prompt's primary hypothesis.** `onStepStart` does
  **not** re-run the classification every step. Sub-bracketing it into `classify`
  (`classifyThings`) vs `poseAudit` (`buildDeltaSet`) shows:
  - **`classifyThings` runs only on steps with a genuine structural event** — 4/360
    (1×), 33/360 (8×) — and **0 % of those runs were forced only by a length change**
    (the redundancy ratio). It is *not* redundant; it scales superlinearly because each
    run is a full O(N) rebuild *and* the structural-event rate is ∝N → ∝N² amortized.
  - **`poseAudit` (`buildDeltaSet`) runs every step and IS the redundant work.** It
    does a full O(slotCount) slot-change scan on all 360 steps, but on the ~90 % of
    steps with no occupant change it provably finds nothing. This is the
    residency-redundant per-step host work the prompt anticipated, and it is the larger
    of the two at 8× (17.4 vs 11.8 ms/step).
- **Fix (poseAudit only):** gate the O(slotCount) slot-change scan on `occupantsChanged`
  ( = `topologyDirty || thingCt-changed`, the same signal that triggers `classifyThings`).
  Slot occupants change *only* via creation (count change) or `removeThing` swap-
  compaction (topology-dirty), so on a non-structural step the scan is provably empty
  and is skipped. The explicit `pendingDirty` marks (in-place biochem/pin pose
  mutations) are **always** drained, independent of the gate.
- **Result — `poseAudit` eliminated, `recompute` cut ~37 % at 8×:**

  | metric (ms/step) | full 1× | inc 1× | full 8× | inc 8× |
  |---|---|---|---|---|
  | **poseAudit** | 0.706 | **0.022** | 17.61 | **2.18** |
  | classify | 0.439 | 0.392 | 11.84 | 15.74* |
  | **recompute (total)** | 1.302 | **0.571** | 30.95 | **19.37** |
  | GPU wall | 61.11 | 60.30 | 272.29 | 261.65 |

  \*`inc_8x` happened to see more structural turnover (44 vs 33 classify runs) from
  run-to-run multithreaded-RNG variation, which inflates its `classify` and `poseAudit`;
  the fix does not touch `classify`. The clean measure of the fix is `poseAudit`:
  17.61 → 2.18 at 8×, and 2.18 ≈ (44 structural steps / 360) × 17.6 ms full-scan —
  i.e. the gated path now pays the scan **only** on structural steps, exactly as designed.

- **GPU÷CPU at 8× improves 0.804 → 0.773** (CPU 8× = 338.6 ms/step on this binary;
  GPU already won at dense post-copyout, now by a little more).

- **What the fix does NOT do:** it does not kill the superlinear *slope*. The residual
  elephant is `classify`, which in the dense fixture is **non-redundant** (every run is
  structurally justified) and ∝N². Removing its superlinearity needs **incremental
  classification** (update only the touched slots instead of a full O(N) rebuild) — the
  high cache-staleness-risk change the prompt warns against; deferred (see Follow-ons).

- **Physics-neutral, proven three ways** (below): a read-only skip-invariant verifier
  (0 misses at 1×/8×/high-turnover), the physics oracle (`crosslinkFireCt` identical,
  segs/activeLinks within the same-config run-to-run noise floor), and the algebraic
  argument that a skipped scan feeds the scatter kernel a byte-identical empty delta.

- **Nothing merged.** Committed on `benchmark-contractile-dense`. The pre-fix
  unconditional scan is retained permanently behind `BOA_FULL_RECOMPUTE=1` (the A/B
  oracle).

## Phase 1 — decomposition (no fix)

`BOA_STEP_PROFILE` windowed [300,660), GPU, dense weak-scaling. New nanoTime
sub-brackets inside `onStepStart`: `classify` (the `classifyThings` call), `poseAudit`
(the `buildDeltaSet` call), `capacity` (the cap/alloc check). Plus dirty-reason tallies
at the `markTopologyDirty` call sites (length-change vs structural) and a per-step
structural-vs-length-only decision for each `classifyThings` run.

| sub-bracket | 1× | 8× | 8×/1× | mechanism |
|---|---|---|---|---|
| **poseAudit** (`buildDeltaSet`) | 0.818 | **17.43** | 21× | runs **every** step; O(slotCount) pointer-chase scan; ~90 % of steps find nothing |
| **classify** (`classifyThings`) | 0.201 | **11.78** | 58× | runs only on structural steps (4→33); full O(N) rebuild × ∝N event-rate = ∝N² |
| capacity | 0.0003 | 0.0003 | flat | cap check; alloc only at plan build |
| classifyRuns | 4/360 | 33/360 | — | **structural=all, lengthOnly=0 → 0 % redundant** |

### The two costs are distinct and need different fixes

- **`poseAudit` is genuinely redundant per-step work.** `buildDeltaSet` walks every live
  slot comparing `theThings[gpuThingIndices[s]].thingInstanceId` to last step's, to
  detect occupant changes for the `scatterPose` kernel. But occupants change only on
  creation / `removeThing` swap — i.e. structural steps. On the ~327/360 non-structural
  steps the scan finds `trueSlotChangeDirty == 0` and produces an empty delta. The
  entire O(slotCount) walk is wasted. Its 21× slope (super-∝N) is the pointer-chase over
  an 8×-larger heap progressively missing cache. **This is the fix target.**

- **`classify` is NOT redundant.** It already runs only when topology changed (the
  count proxy or `topologyDirty`), and in the dense fixture 100 % of those runs coincide
  with a real structural event (split / remove-swap / nucleation). Its ∝N² is the
  product of a full O(N) rebuild and an ∝N structural-event frequency — not a stuck
  flag. Caching/gating cannot help when every run is necessary; only an incremental
  (O(Δ)) rebuild would, and that is the high-risk path.

### Redundancy ratio depends on biochem cadence

The dense fixture fires biochem every 100 steps (`biochemDeltaT=0.01`, `dt=1e-4`), so
length-change marks are rare and never the sole trigger for a `classify` run
(`lengthOnly = 0`). A **high-turnover** stress fixture (biochem every 5 steps,
`cofilinRate=1.2`, `kRdmNuc=0.01`) tells a different story:

```
[STATS] A1 classify runs=109 (structural=52 lengthOnly=57)   ← 52 % redundant
```

So the prompt's "classify re-runs redundantly under turnover" hypothesis **is** true at
fast biochem cadence — but the dense benchmark's slow cadence hides it entirely, and the
benchmark's `classify` cost is all structural. Gating `classify` on the structural-only
signal would help fast-cadence configs (a follow-on); it gives the dense benchmark zero
benefit.

## Phase 2 — the fix

`GPUMoveThing.buildDeltaSet(boolean occupantsChanged)`:

- `occupantsChanged` is captured in `onStepStart` as `topologyDirty || (thingCt != lastThingCt)`
  **before** `classifyThings` clears `topologyDirty` — the exact condition under which
  occupants can change.
- When `occupantsChanged` (or `BOA_FULL_RECOMPUTE`): run the full slot-change scan
  (unchanged pre-fix behavior), then drain `pendingDirty`.
- When **not** `occupantsChanged`: skip the scan. `prevSlotInstanceId` is already valid
  (occupants unchanged), so next step's diff baseline is preserved. Still drain
  `pendingDirty` — `markPoseDirty` can fire without `markTopologyDirty` (the
  contractility pin snap-back in `BoxOfActin.applyBenchmarkPins`), so in-place pose
  mutations are always packed. No `slotAlreadyDeltaed` fill is needed on skip steps
  (each unique live Thing maps to its own slot; no scan-vs-pending dedup required).

`length-only` marks (`FilSegment` poly/depoly) also set `topologyDirty`, so they
conservatively keep `occupantsChanged` true on biochem steps — a harmless extra (empty)
scan on the few cadence steps; correctness is never at stake from over-running the scan.

Code: `GPUMoveThing.java` (`onStepStart`, `buildDeltaSet`), behavior-neutral diagnostic
counters, `BOA_FULL_RECOMPUTE` + `BOA_DELTASET_VERIFY` flags; `FilSegment.java` /
`Thing.java` dirty-reason tallies; `BoxOfActin.java` profiler sub-bracket report + STATS.

## Validation

### 1. Skip-invariant verifier (the turnover-stress oracle) — the crux

`BOA_DELTASET_VERIFY=1` re-runs the slot-change scan **read-only** on every *skipped*
step and asserts it would have found zero changes. A non-zero count = an occupant
changed without `occupantsChanged` being set = a missed invalidation. It never fired:

| run | failSteps | missedSlotChanges |
|---|---|---|
| dense 1× | **0** | **0** |
| dense 8× | **0** | **0** |
| high-turnover stress (biochem ×20, classify 52 % length-only) | **0** | **0** |

This directly proves — step-for-step, including under max churn — that the incremental
path feeds the `scatterPose` kernel byte-identical input to `BOA_FULL_RECOMPUTE`.

### 2. Physics-neutral A/B oracle (same seed, inc vs full)

| run | segs | activeLinks | crosslinkFireCt | overflowSegs |
|---|---|---|---|---|
| inc 1× | 5998 | 233 | 6 | 0 |
| full 1× | 5936 | 201 | 6 | 0 |
| inc 8× | 47669 | 1499 | 6 | 0 |
| full 8× | 47733 | 1719 | 6 | 0 |

`crosslinkFireCt` identical (6) at both scales; `overflowSegs=0`; no NaN. segs/activeLinks
differ — but **within the same-config run-to-run noise floor** (multithreaded RNG), so
the divergence is pre-existing nondeterminism, not the fix:

| repeats @1× | segs range | activeLinks range |
|---|---|---|
| incremental (×3) | 5951–5998 | 179–249 |
| full (×3) | 5911–5985 | 184–220 |

The inc and full bands fully overlap (inc's activeLinks spread is even *wider*), and the
inc-vs-full gap is smaller than the within-config spread. Together with the verifier
(which proves the device pose is bit-identical), the fix is conclusively physics-neutral.

### 3. Re-measure (slope)

`poseAudit` retired (1× 0.71→0.02, 8× 17.61→2.18). `recompute` total 1× 1.30→0.57
(−56 %), 8× 30.95→19.37 (−37 %). The superlinear *slope* is reduced in magnitude (the
∝N pose-scan term is gone) but **not killed**: the residual is the non-redundant ∝N²
`classify`. GPU wall 8× 272.3 → 261.7; **GPU÷CPU 8× 0.804 → 0.773** (CPU 8× = 338.6).

## Follow-ons (reported, not done — err toward correctness)

1. **Incremental classification** (kills the dense residual). `classifyThings` rebuilds
   all slot maps / rules / chain topology / joint lists from scratch on every structural
   event; updating only the touched slots would turn its per-call cost O(N)→O(Δ) and the
   amortized ∝N²→∝N. This is the high cache-staleness-risk change the prompt flags —
   the chain-topology and slot-compaction invariants make a partial update error-prone.
   Deferred; would need a classification-equality verifier analogous to the
   `BOA_DELTASET_VERIFY` skip-invariant oracle before shipping.
2. **Gate `classify` on the structural-only signal** (helps fast-biochem-cadence
   configs, 52 % redundant there; zero benefit to the dense benchmark). Safe by the same
   argument as the pose-audit gate (a length change cannot alter classification), but
   requires tagging every internal `topologyDirty` site (plan build / reset /
   invalidatePlan) as structural and a classification-equality verifier.
3. **Bind-side map clears are fixed-cap.** In `classifyThings`, `filMoveSlot` /
   `motMoveSlot` are sized to the fixed SoA cap (`FilSegment.soaEnd1X.length` = 1 000 000,
   the same fixed-cap pattern as the retired `ffCandPartner`); each `classify` call
   clears the full 1 M-element map though only `filSegmentCt` entries are live. A minor
   fixed per-call cost (not the superlinear driver); clearing only the live high-water
   range is a cheap safe win.

## Reproduce

```
bash RUN_LOGS/2026-06-12_recompute_a1/run_phase1.sh     # Phase 1 decomposition (1x,8x GPU)
bash RUN_LOGS/2026-06-12_recompute_a1/run_validate.sh   # verifier + A/B oracle + re-measure
```

Diagnostic flags (left in permanently): `BOA_FULL_RECOMPUTE=1` (unconditional scan, A/B
oracle), `BOA_DELTASET_VERIFY=1` (read-only skip-invariant proof). High-turnover fixture:
`ParameterFiles/boa10-64Seg-dyn-dense-turnoverstress`.
