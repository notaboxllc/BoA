# Copy-out residency: retiring the flat ~1 GB device→host copy-out

Branch `benchmark-contractile-dense`. Machine aorus1, RTX 5070 (12 GB), Java 21,
TornadoVM 4.0.1-dev PTX. 2026-06-12. Follows the dense v4 benchmark
(`BENCHMARK_contractile_dense.md`), which measured the GPU step as transfer-bound:
a flat ~115 ms/execute, ~1.08 GB device→host copy-out riding **every** execute,
constant across 1×/4×/8× → a fixed-capacity buffer.

## TL;DR

- **The ~1 GB copy-out is `ffCandPartner`** — the fil-fil broad-phase candidate
  partner buffer, `IntArray(segCap × FILFIL_MAX_CAND)` =
  **1 000 000 × 256 × 4 B = 1.024 GB**, allocated at a FIXED capacity
  (`segCap = FilSegment.soaEnd1X.length = 1 000 000`, the SoA array cap, **not** ∝N).
  That fixed size is exactly why the v4 profiler saw the copy-out flat at ~1.08 GB
  across all scales while the box and population grew. (The remaining ~60 MB of the
  1083 MB is the small EVERY_EXECUTION bind/boundary buffers.)
- **Verdict: (b)+(c).** The host consumer `GPUMotorBinding.drainFilFilCandidates()`
  reads it (a) only every `crosslinkCheckInt` (= 100) steps — the crosslink
  FORMATION cadence — not every step, and (b) only the live sub-range
  `[0, filSegmentCt × 256)`, a tiny fraction of the 256 M-int capacity. The buffer
  was declared `EVERY_EXECUTION`, so it was copied out **100× more often than the
  host consumes it**.
- **Fix (primary = cadence-gate):** demote `ffCandPartner` + `ffCandPerSegCount`
  to `UNDER_DEMAND` and pull them with an explicit
  `lastExecResult.transferToHost(...)` on crosslink-fire steps only — the same
  residency pattern the pose buffers already use. The kernel still rewrites them
  every execute; only the host transfer is gated.
- **Result — GPU now WINS at dense (8×) and the crossover moved from ~10× to ~1.3×:**

  | scale | CPU ms/step | GPU ms/step (v4) | GPU ms/step (fix) | GPU÷CPU (v4) | **GPU÷CPU (fix)** |
  |---|---|---|---|---|---|
  | 1× | 52.6 | 170.5 | **61.5** | 3.32 | **1.17** |
  | 8× | 335.6 | 372.5 | **274.8** | 1.12 | **0.82** |

  GPU 1× exec fell 142 → 34 ms/step (the ~108 ms copy-out retirement, matching the
  ~115 ms profiler floor). GPU÷CPU crosses 1.0 between 1× and 2× now (≈1.3×),
  vs "just beyond 8×" in v4.
- **Physics-neutral.** The change only alters *when the host reads* an
  already-device-resident buffer; the bytes drained on a fire step are identical to
  what `EVERY_EXECUTION` delivered. Oracle (GPU before → after):

  | scale | fActin segs (v4 → fix) | activeLinks (v4 → fix) | crosslinkFireCt | overflowSegs | NaN |
  |---|---|---|---|---|---|
  | 1× | 5955 → 5961 (+0.10 %) | 232 → 219 | 6 → 6 | 0 | 0 |
  | 8× | 47 722 → 47 510 (−0.44 %) | 1599 → 1657 | 6 → 6 | 0 | 0 |

  The deltas are within the GPU path's own run-to-run nondeterminism (multithreaded
  RNG): v4's two 1× GPU runs themselves differed by activeLinks 232 vs 214 (±8 %)
  and segs 5955 vs 5944 (±0.18 %). `crosslinkFireCt` is identical (6), confirming
  the fire-step gating preserves the formation cadence exactly.

## The buffer (survey detail)

`GPUMotorBinding.java`:
- `FILFIL_MAX_CAND = 256` (line 127), `segCap = FilSegment.soaEnd1X.length` (line 1554).
- `candPartner = new IntArray(segCap * FILFIL_MAX_CAND)` (line 1597) = 256 M ints = **1.024 GB**.
- `FilSegment.soaEnd1X = new double[1000000]` (line 29) — the fixed SoA cap that makes `segCap` size-independent.

The chained TaskGraph in `GPUMoveThing.java` declared (pre-fix, ~line 3837):
```java
if (filFilBroadphaseActive) {
    tg = tg.transferToHost(DataTransferMode.EVERY_EXECUTION,
                           ffCandPartner, ffCandPerSegCount);   // 1.024 GB / execute
}
```
Host consumer `drainFilFilCandidates()` (`GPUMotorBinding.java:1517`) loops only
`i < FilSegment.filSegmentCt` and reads `candPartner[i*256 + k]` — the live
sub-range. Its caller (`BoxOfActin.java:1411`) is gated on
`GPUMoveThing.crosslinkFiresThisStep`, i.e. every `crosslinkCheckInt` (100) steps.

### Why not also do (c) — live-subrange transfer?

The cadence gate (b) already amortizes the 1.024 GB pull from every step to 6
fire-steps over a 360-step window (~1.8 ms/step amortized — see `moveDrains`
below). A live-subrange transfer would further shrink each fire-step pull to
`filSegmentCt×256` ints (~6 MB at 1×, ~50 MB at 8×, vs 1.024 GB), but since the
fire-step cost is already amortized to <2 ms/step it is not the bottleneck. Left as
a cheap follow-on (would need TornadoVM partial-range `transferToHost`); the primary
win is the cadence gate.

### What this exposed next (reported, not chased)

With the copy-out retired, the GPU step is no longer transfer-bound. The next
device cost down is the **`gridScatter`** kernel (the bind-grid counting-sort build,
the only superlinear kernel — 3.1→14.7→29.9 ms over 1×→8× in v4 Part E). On the
host side, see Part 2 below.

## Code changes

- `GPUMoveThing.java` (chained graph build): `ffCandPartner`/`ffCandPerSegCount`
  → `UNDER_DEMAND`; new `demandSyncFilFilCandidates()` helper mirroring the pose
  demand-sync.
- `BoxOfActin.java` (fire-step block, ~1404): call `demandSyncFilFilCandidates()`
  before the parity harness + `drainFilFilCandidates()`.

## Reproduce

```
bash RUN_LOGS/2026-06-12_copyout_fix/run_validate.sh   # GPU+CPU, 1x and 8x, ms/step + oracle
```

---

## Part 1 — direct profiler confirmation (1× GPU, `-Dtornado.profiler=True`)

| metric (ms or MB / execute) | v4 baseline | with fix |
|---|---|---|
| graph wall | 144.1 | **35.1** |
| copy-OUT time | 115.5 | **7.2** |
| copy-OUT bytes | 1083.1 MB | **64.8 MB** |
| copy-IN time / bytes | 21.0 / 182.0 | 20.8 / 180.2 (unchanged) |
| top kernel `gridScatter` | 3.13 | 2.78 (unchanged) |

The −1018 MB copy-out drop is exactly the 1.024 GB `ffCandPartner` buffer. The 64.8 MB
residual = the small EVERY_EXECUTION bind/boundary buffers (~45 MB) + the now-amortized
1 GB fil-fil pull on the 6 fire-steps (6 × 1024 MB / 302 executes ≈ 20 MB/execute).
Copy-in and kernel times are untouched — the fix is purely the retired copy-out.

---

# Part 2 — the host "other" bucket, decomposed

`BOA_STEP_PROFILE` windowed, [300,660), with new nanoTime brackets added at existing
loop boundaries for the components the v4 report lumped into "other". The
decomposition now accounts for ~100 % of wall (`otherResidual` < 2 ms/step on both
paths at both scales). No extra passes; attribution only — no host phase was changed.

### What the new brackets cover (previously unlabeled)

- **jointsCpu** — the CPU joint-force waves (membrane-links pass + `myoJoints1` +
  `myoJoints2`) between xLink and step. On `-gpu` the per-Myosin *internal* joints
  run as a device kernel, but the MyosinDimer / ProteinNode / **MyoMiniFilament** /
  Chamber joint pools still dispatch on CPU here.
- **recompute** — per-step setup: `recomputeActiveThreadSets` + `setBiophysValues` +
  `ensureAccumCapacity` + `clearSoaForcesTorques` (∝N memset) + `GPUMoveThing.onStepStart`
  (GPU Thing reclassification + delta-set audit — GPU-only).
- **resetCt**, **cleanup** (existing ms RunTimers, now surfaced + folded into labeled),
  **cleanupTail** (ActA + spawn + equilibrate), **membrane** (relaxation block, no-op
  fan-out here), **output** (logAndDraw/remoteLog + endOutputRender), **safepoint**
  (updateCounters + drains + diagnostics), **moveDrains** (GPU move-wrap minus
  exec/pack/form = bind-result drain + amortized fil-fil demand-sync), **gc** (JVM
  collection time over the window, via the management API).
- **step / gatherForces / brownian** were displayed but EXCLUDED from `labeled` on the
  GPU path (the formula assumed they were ~0 on device) — they are **not** zero (~20
  ms/step at 8× GPU); now folded into labeled. This was the bulk of the GPU residual.

### Decomposition (ms/step), 1× → 8×, dense weak-scaling

**GPU path** (the previously-"other" bucket was 16.7 → 95.6 in v4):

| sub-phase | 1× | 8× | 8×/1× | note |
|---|---|---|---|---|
| **recompute** | 1.19 | **27.72** | **23.3×** | **the elephant — superlinear** (setBiophys + force-zero + onStepStart/delta-audit) |
| resetCt | 2.96 | 21.38 | 7.2× | ∝N |
| cleanup | 1.54 | 13.90 | 9.0× | ∝N array compaction |
| step | 2.34 | 13.53 | 5.8× | CPU `Thing.step()` still dispatches on `-gpu` |
| jointsCpu | 3.61 | 11.84 | 3.3× | MyosinDimer/MiniFil/ProteinNode joints on CPU |
| brownian | 1.55 | 5.51 | 3.6× | CPU Brownian force phase on `-gpu` |
| moveDrains | 0.75 | 1.48 | — | bind drain + amortized fil-fil pull |
| gatherForces | 1.14 | 1.46 | — | |
| membrane | 1.28 | 1.45 | flat | no-op threadset fan-out |
| motorFilCol | 0.06 | 0.78 | — | on device |
| output / cleanupTail / safepoint / gc | ~0 | ~0 | — | negligible |
| otherResidual | 0.14 | 1.87 | — | fan-out latency + sampleBoundMotors |

**CPU path** (previously-"other" bucket was 25.6 → 170 in v4):

| sub-phase | 1× | 8× | note |
|---|---|---|---|
| **jointsCpu** | 9.61 | **56.77** | co-elephant — minifilament joints |
| **motorFilCol** | 8.21 | **52.48** | co-elephant — motor-bind grid + motor-fil collisions (all CPU) |
| step | 5.67 | 41.47 | |
| brownian | 4.17 | 26.07 | |
| gatherForces | 3.63 | 25.43 | |
| recompute | 2.22 | 23.17 | |
| resetCt | 2.95 | 20.20 | |
| cleanup | 1.62 | 14.74 | |
| membrane | 1.23 | 1.44 | flat |
| otherResidual | 0.11 | 2.04 | |

### The elephant

- **GPU path: `recompute` (27.7 ms/step at 8×) is the largest previously-"other"
  sub-phase, and the only superlinear one** (23× over an 8× population, vs ideal 8×;
  fActin segs scale ~8×, so recompute is genuinely worse-than-∝N). At 1× it is
  *cheaper* than the CPU recompute (1.19 vs 2.22), but at 8× it is *more expensive*
  (27.7 vs 23.2) — the divergence is `GPUMoveThing.onStepStart` (the GPU-only Thing
  reclassification + pose delta-set audit), which re-runs every step because biochem
  turnover keeps the topology dirty. **That is the future host-optimization target on
  the GPU path.** Caching/incremental classification when the topology delta is small
  would cut it.
- **CPU path:** `jointsCpu` (56.8) and `motorFilCol` (52.5) co-lead — both ∝N, both
  candidates the GPU path already largely offloads (which is why GPU now wins at 8×).
- **Reported, not chased** (per the prompt — Part 2 is attribution only): the GPU path
  still runs `step` (13.5) + `brownian` (5.5) + `jointsCpu` (11.8) ≈ 31 ms/step of CPU
  force/integrate work at 8× that is *not* on device — the next residency frontier
  after the copy-out, larger now than the retired copy-out's amortized cost.

## Reproduce (Part 2)

```
bash RUN_LOGS/2026-06-12_copyout_fix/run_part2.sh    # GPU+CPU, 1x and 8x, full decomposition
```

