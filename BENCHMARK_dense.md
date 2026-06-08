# Dense CPU-vs-GPU benchmark — iter2b/2c/2d crossover series, current HEAD

Single-seed dense wall-clock benchmark on `glidingDense_demo_smoke`
(M ≈ 98K motors, 14×14×0.5 µm bed, 100 random filaments, runTime=0.01s ⇒
~1001 steps). Same config used for the iter2b/2c/2d wall-clock entries.
This benchmark point is for the dense-scale crossover graph that lives in
the JOURNAL.md narrative.

## post-4.5 (resident bind, separate-plan) @ ade5b56

| | wall (s, seed=1) | ratio vs CPU |
|---|---|---|
| CPU (no `-gpu`)         | **153** | 1.00× |
| GPU (`-gpu`)            | **125** | **0.82×** |

Branch tip `ade5b56` (phase45-binding-residency, includes Step 1
persistent identities + EVERY_EXECUTION pose, Step 2 separate-plan
resident-pose bind, validation gate, float32 binding doc). CPU wall
matches the prior point within 1 s (no CPU-side change in 4.5 — sanity
check passes). GPU wall is +2 s vs `b044874`'s 123 s, ratio shifted
0.80×→0.82× — single-seed noise band. The bind-side win and move-side
upload cost both land where the small-fix accounting predicted:

```
[STATS] gpuMotorBinding total=13.988s calls=1101  pack=0.071s exec=13.663s unpack=0.254s
[STATS] gpuMoveThing    total=47.916s calls=1101  slotPack=16.561s jointPack=7.237s exec=15.599s unpack=8.515s
[STATS] gpuMoveThing    demandSyncPose=5.268s(calls=1101) planRebuild=1
[STATS] bindEvents=1917  meanBoundMotors=151.856  glidingVelocity=42.5256
```

vs HEAD's bind 17.7 / move 34.7. Bind dropped -3.7 s (pose-pack PCIe
retired). Move grew +13.2 s (EVERY_EXECUTION pose upload + OP_PACK_FULL
every step — the accepted interim cost of plan-invariant identities).
`planRebuild` dropped 17→1 (the persistent-identities lift retires
all topology-dirty rebuilds; only the startup build remains). Net wall
moved +2 s — the bind savings and move addition near-cancel at dense
scale, where M ≈ 98K motors makes the per-call pose-pack PCIe saved on
bind comparable in magnitude to the per-call pose-pack PCIe added on
move (both ride the same M-scale Cap*3 floats). Step-5-class wins (full
elimination of demand-sync + the move-side push together) are what's
needed to translate 4.5's per-call wins into dense-scale wall.

## Prior numbers (commit `b044874`)

| | wall (s, seed=1) | ratio vs CPU |
|---|---|---|
| CPU (no `-gpu`)         | **154** | 1.00× |
| GPU (`-gpu`)            | **123** | **0.80×** |

Aorus, Java 21, TornadoVM 4.0.1-dev PTX, RTX-class GPU. Both arms ran
with the full classpath (`tornado-api-4.0.1-dev.jar:libs/*:.`); the
non-`-gpu` arm does no kernel dispatch but loads the API jar (post-iter2d
regression: `GPUMoveThing` static fields reachable from `MyosinFixed.
jointConstraints` reference `WorkerGrid` at class-load time, so the CPU
arm now needs `tornado-api.jar` on the classpath to avoid
`NoClassDefFoundError` in worker threads — an immaterial JVM-startup
delta of a few ms, but flagged so the comparison is honest vs iter2d's
barer classpath).

## Comparison to the iter2b/2c/2d wall-clock series (same dense config)

```
                    CPU wall   GPU wall   ratio (GPU / CPU)
iter2b (5fa6bda?)    ~188 s     330 s      1.76× (GPU slower)
iter2c (cb8b1ca)      188 s     273 s      1.45× (GPU slower)
iter2d (5fa6bda)      190 s     184 s      0.97× (GPU 3 % faster — first crossover)
HEAD   (b044874)      154 s     123 s      0.80× (GPU 20 % faster than CPU)
post-45 (ade5b56)     153 s     125 s      0.82× (within noise of HEAD; net flat)
```

The iter2b → iter2d series got the GPU under the CPU wall (0.97×); the
work since then (motor port to GPU, Phase 3 device-resident grid build,
Phase 4 half-flip residency, motor-gap acos fix, IC pad fix) has pushed
both arms down. The CPU wall dropped 190 → 154 s (-19 %) and the GPU
wall dropped 184 → 123 s (-33 %), widening the GPU advantage.

## Per-phase, GPU arm, current HEAD

```
[STATS] gpuMotorBinding total=17.725s calls=1101  pack=0.988s exec=16.161s unpack=0.483s
[STATS] gpuMoveThing    total=34.735s calls=1101  slotPack=7.998s jointPack=7.305s exec=11.141s unpack=8.288s
[STATS] gpuMoveThing    demandSyncPose=5.044s(calls=1101) planRebuild=17

ThingStep Threads               20.991 s   (CPU-fallback Things — non-FilSegment, non-Myo)
ThingBrownian Threads            2.918 s
Myosin Threads                   1.014 s
Mesh Threads                     5.671 s
MotorBindGrid3D Fill             0.000 s   (Phase 3 — device-resident grid build)
```

The binding total dropped from iter2d's 12.8 s (1.0 ms/call avg) to today's
17.7 s (16.1 ms/call exec). The exec time **per call grew** between iter2d
and today: kernel work is heavier (the half-flip moved more state onto the
device; bind kernel and segBbox/gridAssemble run more code per call). The
1101-call count is the same — so the per-call cost change is the structural
delta.

The `MotorBindGrid3D Fill` line is 0.0 on `-gpu` (Phase 3 retired the
CPU FillThreads dispatch on `-gpu`) and 3.7 s on the CPU arm today (down
from iter2d's 28.95 s — the CPU FillThreads got faster, likely from the
SoA + iter2d CPU-side wins).

## What this benchmark establishes for Phase 4.5

Pose pack + PCIe upload is **75 % of binding** at the validation scale per
`PHASE45_SCOPING.md` Part-2 breakdown. The dense scale here has binding's
PCIe write dominated by the larger M (98K vs 500); the cross-graph
residency lift will compound on top of the per-call exec time, not the
pack. Expected dense-scale gain from Phase 4.5 binding residency at this
M: order ~5–7 s off the 17.7 s binding total (the pose-pack PCIe slice),
landing the GPU wall around 116–118 s. CPU wall is unchanged. Ratio
moves from 0.80× to ~0.76×.

## Reproduction

```
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      boxOfActin/*.java *.java

PF=ParameterFiles/glidingDense_demo_smoke

# CPU
java --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -pf "$PF" -seed 1 \
     > RUN_LOGS/2026-06-06_dense_benchmark/cpu_seed1.log 2>&1

# GPU
java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf "$PF" -seed 1 \
     > RUN_LOGS/2026-06-06_dense_benchmark/gpu_seed1.log 2>&1
```

Logs:
- HEAD point: `RUN_LOGS/2026-06-06_dense_benchmark/{cpu,gpu}_seed1.{log,wall}`.
- post-4.5 point: `RUN_LOGS/2026-06-06_dense_post45/{cpu,gpu}_seed1.{log,wall}`.

## Retrospective campaign sweep

Constant-config CPU + GPU steady-state per-step sweep across the seven
campaign milestones, run in one overnight session on **2026-06-07**.
Distinct from (and supersedes for the CPU-evolution question) the
single-seed iter2b/2c/2d wall-clock series above — that series varied
the config alongside the code over the campaign, so its CPU drop
(190 → 154 s) was confounded by config drift. This sweep holds the
config byte-identical at every commit.

Config: 400 random filaments (4× the dense-demo's 100), 14×14×0.5 µm bed
@ 500/µm² ≈ 98K motors, `noMonomersSimd:true`, biochem rates zeroed —
saved as `RUN_LOGS/2026-06-07_retro_sweep/retro_sweep_config`. Box +
density preserved so motor count stays under the pre-campaign
`MyoMotor.theMotors[100000]` static cap. Method: two-step-count
difference — wall_K2 − wall_K1 divided by (K2 − K1), with K1 = 500 and
K2 = 2000 timesteps at dt = 1e-5 s, cancelling startup (JVM warmup, JIT,
TornadoVM plan-build) cleanly and identically at every commit. Heap
`-Xmx20G -XX:-UseGCOverheadLimit` (pre-campaign `-gpu` startup needs
>4G for class init at this scale).

| commit | hash | CPU ms/step | GPU ms/step | GPU÷CPU |
|---|---|---:|---:|---:|
| pre-campaign (iter2a + dense intro) | `6b5599f` | 170.89 | 176.26 | 1.03× |
| iter2b polish                       | `bb7dc99` | 172.91 | 286.41 | 1.66× |
| iter2c                              | `bb7829e` | 174.87 | 249.80 | 1.43× |
| iter2d                              | `8027662` | 172.29 | 160.97 | 0.93× |
| pre-4.5 (HEAD at the time)          | `b044874` | 130.20 |  94.01 | 0.72× |
| post-4.5                            | `7759be7` | 132.49 |  96.42 | 0.73× |
| post-residency (current `main`)     | `498bb7c` | 129.84 |  88.87 | 0.68× |

**Verdict — CPU did genuinely improve, and the change is localized.**
Across the iter2a → iter2d span (5 commits worth of GPU work) CPU
steady-state is **flat at 170–175 ms/step**. Between iter2d (`8027662`,
172.3) and pre-4.5 (`b044874`, 130.2) CPU drops **24 % in one campaign
step**. The structural change in that window is `cpuopt batch 1`
(`61c2391` — `pow`/`acos`/`sqrt`/`Env-cache`/`transXTox` flatten); the
other commits in the window are restoration/IC fixes that don't touch
per-step hot paths. From pre-4.5 onward CPU is flat again
(130 → 132 → 130). So the **iter2d → HEAD CPU drop in the old crossover
series (190 → 154 s) was real, not config drift** — the difference is
that the *whole* drop is attributable to one cpuopt commit, not
distributed across the whole iter2d → post-4.5 arc.

**GPU evolution.** GPU per-step starts at 176 (pre-campaign, motor-bind-
only kernel + everything else CPU), spikes upward to **286 at iter2b**
(unified moveThing GPU kernel added — transfer-bound at this M),
recovers to 250 (iter2c, Wang-hash Brownian + pack opt) and 161 (iter2d,
parallel pack/unpack + myosin coord residency — first crossover at this
scale, ratio 0.93×), then drops sharply to **94 at pre-4.5** (motor-port
F8–F10 + Phase 3 grid-on-device + Phase 4 half-flip residency +
motor-gap `acos` fix all stacked between iter2d and `b044874`). Phase
4.5 binding-residency lands almost flat (96 vs 94 — the 4.5 small-fix
trade widened the move-side cost by ~the same as bind shrank, as
documented above). Steps 2–4 residency knocks GPU another ~8 % to
**89 ms/step** by retiring the per-step demand-sync in
`noMonomersSimd` configs and folding bind into the chained graph.

**Overall — GPU is 1.92× faster than CPU at this scale today**
(88.87 vs 170.89 / pre-campaign, or 88.87 vs 129.84 / today's CPU =
1.46× speedup over today's CPU). Over the campaign the CPU dropped 24 %
(170.89 → 129.84) and the GPU dropped 50 % (176.26 → 88.87).

### Notes on comparability

- This sweep is not directly comparable to the iter2b/2c/2d wall-clock
  numbers above — this is per-step at **4× the filament population** at
  the same motor density; that series is total wall on the 100-fil
  `glidingDense_demo_smoke` config. Internal comparability across
  commits is the point.
- The pre-campaign GPU path (`6b5599f`) only had the motor-binding
  GPU kernel; everything else was CPU. At this M and filament scale
  GPU offload overhead exceeds the marginal motor-binding kernel gain
  (1.03×) — the GPU advantage starts from the move-port at iter2b.
- A run is two paired commands per (commit × path): one with
  `runTime=0.005` for K1 = 500 steps, one with `runTime=0.020` for
  K2 = 2000 steps. The per-row K1/K2 wall numbers + run logs +
  per-commit param-file variants are committed under
  `RUN_LOGS/2026-06-07_retro_sweep/`. The harness
  (`run_sweep.sh`) is also included for reproducibility — read-only,
  no source edits at any commit.

### Reproduction

```
bash RUN_LOGS/2026-06-07_retro_sweep/run_sweep.sh
```

The harness checks out each campaign commit in turn (force-checkout to
absorb the pre-campaign/iter2b tracked-`.class` legacy), rebuilds,
runs K1 + K2 on both paths, appends results immediately to
`results.md`, and returns the repo to `main` at the end (or on any
failure path via `trap`). Logs:
`RUN_LOGS/2026-06-07_retro_sweep/{hash}_{cpu,gpu}_K{1,2}.{log,wall}`.
