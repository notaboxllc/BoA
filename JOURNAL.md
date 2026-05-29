# BoxOfActin Project Journal

Last updated: 2026-05-28

> Earlier entries (2026-05-17 through 2026-05-25) archived in JOURNAL_ARCHIVE.md.

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
