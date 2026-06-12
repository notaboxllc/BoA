# GPU cost map — the contractile step, decomposed (per-kernel × scale, bandwidth headroom)

**Date:** 2026-06-11 · **Host:** aorus1 (RTX 5070, 12 GB GDDR7, 192-bit → ~672 GB/s
peak; PCIe Gen4 x16 ~25 GB/s) · Java 21, TornadoVM 4.0.1-dev PTX · branch
`fix-701-cohesion-launch` (701 fix + cohesion correctness landed) · workload
`boa10-64Seg-dyn` (full contractile network + active turnover), GPU-resident.

**Gate satisfied:** the now-running `dimerCohesion` kernel is oracle-neutral vs full-CPU
(segCt 540≈540, length 105.8≈106.0 µm, NaN=0; see the 701-hunt journal entry). Profiling
the correct kernel.

---

## TL;DR — the headline number, and a benchmark correction

**The contractile GPU step is NOT kernel-bound and NOT memory-bandwidth-bound. It is
(1) per-execute host↔device transfer-call latency and (2) a single-threaded grid-assembly
kernel — both recoverable, neither a memory wall.** The mechanics kernels (joints, move,
chain, cohesion, motor force) are a negligible <1 ms combined even at 16×.

This **corrects `BENCHMARK_contractile_scaling.md` B4**, which reported "exec 62–73%,
kernel-bound, transfer ≈ 0." That conclusion was an instrumentation artifact: BoA's
`[STATS] exec=` timer wraps the whole `plan.execute()`, which **includes the
EVERY_EXECUTION host↔device buffer transfers**. The "transfer ≈ 0" claim measured only
the *pose pull* (`demandSyncPose`, device→host, output-cadence) and missed ~75 per-step
buffer transfers. The real split at 1× is **5% kernel / 85% transfer / ~10% dispatch.**

**Recoverable headroom (the v2-for-speed input):** ~85–95% of the per-step time is in two
fixable inefficiencies, not in the mechanics physics:
- **`gridAssemble` (single-threaded bind-grid build):** 0.8 ms (1×) → **34.9 ms (16×) =
  50% of the entire step.** Embarrassingly parallel (counting sort) but runs on **one GPU
  thread**. Parallelizing it removes essentially all of it.
- **Per-execute transfers (~75 separate `cuMemcpy` calls):** COPY_IN 15.7→23.6 ms +
  COPY_OUT ~7 ms flat = **~23–31 ms, mostly FIXED (per-call latency, not bandwidth).**
  Coalescing the many small buffers + making the static topology arrays resident
  (FIRST_EXECUTION) removes most of it.

A v2 that does both could plausibly take the 1× step from **26.8 ms → ~3–5 ms**
(GPU÷CPU 9.6× → ~1×) and the 16× step from **69.7 ms → ~5–8 ms** (GPU÷CPU 1.56× →
**GPU faster than CPU**). **Verdict: contractile GPU loss is RECOVERABLE — but via
grid-build parallelization + transfer coalescing, NOT mechanics-kernel redesign and NOT
PCIe bandwidth.**

---

## Method

- **Per-kernel + per-component attribution:** TornadoVM profiler
  (`-Dtornado.profiler=True -Dtornado.profiler.dump.dir=<file.json>`), parsing
  `TASK_KERNEL_TIME` (per task, device GPU timer), `TOTAL_KERNEL_TIME`, `COPY_IN_TIME`,
  `COPY_OUT_TIME`, `TOTAL_TASK_GRAPH_TIME`. Warm-up (first 15 executes) dropped;
  steady-state mean over ~235 executes; 3 runs per scale, seed 1.
- **Absolute step time:** taken from the **instrumentation-OFF** two-step-diff sweep
  (`raw_after.tsv`). The profiler is near-transparent here — `TOTAL_TASK_GRAPH_TIME`
  (26.8/35.9/47.0/69.7 ms) matches the instrumentation-off `exec` (26.3/45.9/68.5 ms at
  1×/8×/16×) within noise — so the profiler fractions are trustworthy as absolutes too.
- **Block/grid dims:** `-Dtornado.threadInfo=true`.
- **ncu / DRAM-throughput / occupancy counters: UNAVAILABLE.** This host has only CUDA
  11.5 + Nsight Compute 2021.3.1, which predate Blackwell (sm_120); `nvcc` can't target
  sm_90+ and `ncu` cannot attach to the 5070. Bandwidth utilization and hardware
  occupancy are therefore characterized **analytically + structurally** (block/grid dims,
  bytes moved, achieved transfer rate), clearly labeled, not counter-measured.
- Weak-scaling configs identical to the benchmark (ratio + density held, box area ∝ N).
  Viewer/output OFF.

---

## Part 1 — component decomposition across scale

Per-step (per-execute) wall, mean ± half-spread over 3 runs:

| N | wall (ms) | kernel | COPY_IN | COPY_OUT | kernel % | COPY_IN MB |
|---:|---:|---:|---:|---:|---:|---:|
| 1×  | 26.8 ±0.1 | 1.43  | 15.7 ±0.1 | 7.03 ±0.03 | 5.3%  | 141 |
| 4×  | 35.9 ±0.0 | 8.33  | 17.7 ±0.0 | 7.18 ±0.04 | 23.2% | 154 |
| 8×  | 47.0 ±0.7 | 17.3  | 19.7 ±0.1 | 7.25 ±0.04 | 36.9% | 172 |
| 16× | 69.7 ±0.1 | 36.0  | 23.6 ±0.1 | 7.41 ±0.04 | 51.6% | 207 |

**Decomposition tags:**

| component | 1×→16× | slope | tag | recoverable? |
|---|---|---|---|---|
| **COPY_OUT** | 7.03 → 7.41 ms | **flat** | **FIXED** | yes — per-call latency; coalesce |
| **COPY_IN** | 15.7 → 23.6 ms | +50% over 16× work | **mostly FIXED** + small marginal | yes — ~75 small per-call transfers dominate; coalesce + residency |
| **kernel** | 1.43 → 36.0 ms (×25) | **superlinear** | **MARGINAL→SUPERLINEAR** | yes — ~all of it is `gridAssemble` (see Part 2) |

The crossover is visible: at 1× the step is **85% transfer**; by 16× the single-threaded
`gridAssemble` has grown to overtake it (52% kernel). COPY_IN/OUT being nearly flat while
the work grows 16× is the signature of **per-call latency** (a fixed count of `cuMemcpy`
launches), not bandwidth: 141 MB / 15.7 ms = **9 GB/s effective** — only ~36% of PCIe
Gen4 peak and ~1.3% of the 5070's 672 GB/s device BW — i.e. nowhere near any bandwidth
wall; it is launch-latency-limited across ~75 separate transfers.

---

## Part 2 — per-kernel map (the kernel slice, dissected)

Device `TASK_KERNEL_TIME` per kernel, steady-state mean (µs/execute):

| kernel | family | block | 1× | 4× | 8× | 16× | slope tag | recoverable? |
|---|---|---:|---:|---:|---:|---:|---|---|
| **gridAssemble** | binding | **1** | 809 | 7629 | 16492 | **34861** | **SUPERLINEAR** | **YES — serial; parallelize** |
| dimerCohesion | mechanics | 64 | 411 | 414 | 442 | 479 | **FIXED** | n/a (already cheap; mostly early-out) |
| joints | mechanics | 64 | 30 | 63 | 133 | 241 | marginal (~N) | already cheap |
| bind | binding | 64 | 80 | 79 | 83 | 96 | fixed | already cheap |
| chain | mechanics | 64 | 26 | 33 | 47 | 79 | marginal | already cheap |
| move | mechanics | 64 | 13 | 32 | 63 | 125 | marginal (~N) | already cheap |
| segMotorForce | mechanics | 64 | 12 | 21 | 26 | 31 | marginal | already cheap |
| motorForce | mechanics | 64 | 11 | 13 | 14 | 15 | fixed | already cheap |
| scatterPose | infra | 64 | 12 | 13 | 13 | 13 | fixed | already cheap |
| segBbox | binding | 128 | 11 | 11 | 12 | 12 | fixed | already cheap |
| boundary | mechanics | 64 | 11 | 12 | 15 | 21 | marginal | already cheap |
| derived | infra | 64 | 5 | 7 | 8 | 11 | marginal | already cheap |

**`gridAssemble` is the whole story of the kernel slice:** 56% of kernel time at 1×,
**97% at 16×.** It is `for (@Parallel int gid = 0; gid < 1; gid++)` — a **single GPU
thread** doing the entire spatial-bind-grid build serially: zero `totalCells` → count
segments per cell → prefix-sum → scatter into `gridCellContents`. Both `totalCells`
(box volume ∝ N) and segment count (∝ N) grow with N, so a single thread's serial scan
grows ~linearly with cache degradation on the N×-larger grid → the observed superlinear
0.8→34.9 ms. Occupancy is literally **1 thread on 1 of 48 SMs** — the GPU is idle for the
single most expensive kernel in the step. This is a counting sort, the canonical
parallel primitive; a standard atomic-count + parallel-prefix-sum + parallel-scatter
rewrite removes essentially all 34.9 ms at 16×.

**Everything else is fast and well-behaved.** The mechanics kernels (joints, move, chain,
cohesion, motorForce, segMotorForce, boundary) sum to **~0.5 ms at 1× and ~1.0 ms at
16×** — under 1.5% of the step at every scale. `dimerCohesion` (the kernel the 701 fix
made run) is **flat ~0.4–0.5 ms** — most of its 6400 threads early-out (only the handful
of GPU-handled minifilament bodies do work), so making it run cost nothing measurable.

---

## Part 2b — binding-vs-mechanics split (the hypothesis, tested)

The prompt's hypothesis: binding/grid kernels are GPU-efficient (proximity-search,
compute-bound); mechanics kernels are memory-bound and slow. **Both halves are refuted —
but the binding half fails for a code reason, not a bandwidth reason:**

| family | kernels | exec at 1× | exec at 16× | verdict |
|---|---|---:|---:|---|
| **binding/grid** | gridAssemble + bind + segBbox | 900 µs | 34 969 µs | **dominated by the serial gridAssemble** (a defect, not a regime); bind+segBbox themselves are tiny+flat |
| **mechanics** | joints, move, chain, cohesion, motorForce, segMotorForce, boundary | 514 µs | 991 µs | **fast, ~linear, NOT memory-bound — refutes "mechanics is the wall"** |

The mechanics kernels are not the wall; they barely register. The binding family is
"slow" only because of one un-parallelized kernel. No kernel is near the memory-bandwidth
wall — the workload is too sparse (grids of 100–1600 threads = a few blocks across 48
SMs) for any kernel to saturate bandwidth; they are **work-starved / latency-bound**, not
bandwidth-bound. The "memory wall" framing does not apply to this workload at these
scales.

---

## Part 3 — recoverable headroom & the v2 verdict

Of the 26.8 ms (1×) / 69.7 ms (16×) step:

| lever | 1× cost | 16× cost | nature | recovery |
|---|---:|---:|---|---|
| **gridAssemble serial build** | 0.8 ms | **34.9 ms** | single-thread kernel | parallelize (counting sort) → ~0.1–0.5 ms |
| **per-execute transfers** (COPY_IN+OUT) | **22.7 ms** | 31.0 ms | ~75 per-call HtoD/DtoH latency | coalesce buffers + FIRST_EXECUTION the static topology arrays (rodSlots, anchorPts, segMotorOffsets, cohSlots, … ride EVERY_EXECUTION but change only on classifyThings) → a few ms |
| dispatch/interp residual | ~1.6 ms | ~1.7 ms | TornadoVM bytecode/JNI per execute | partly via fewer tasks |
| **real mechanics compute** | 0.5 ms | 1.0 ms | the actual physics | already optimal; leave it |

**Recoverable share ≈ 95% at 1×, ≈ 95% at 16×.** The realistic v2-for-speed outcome on
contractile is a step of a **few ms at every scale**, which would flip GPU÷CPU from
9.6×/1.56× (1×/16×) to roughly **parity at 1× and GPU-faster at 8×–16×** (CPU is
42–70 ms there; a 5 ms GPU step wins outright). This is a **stronger** v2-for-speed case
than the 701-hunt's "structural / mechanics-bound" reading — but the levers are
**grid-build parallelization and transfer coalescing/residency**, the *opposite* of the
prompt's "mechanics-kernel bandwidth" hypothesis. There is **no memory-bandwidth wall** to
work against; the headroom is in serial-kernel parallelism and per-call transfer overhead.

### Reconciliation with the 701-hunt verdict
The 701 entry concluded "Outcome 3 — clearing the 701 left GPU÷CPU unchanged; the loss is
structural (latency/dispatch-bound), not launch-throttled." This profile **confirms the
direction** (not kernel-launch, not mechanics, not bandwidth) and **sharpens it**: the
"latency/dispatch" cost is concretely (a) ~75 un-coalesced per-execute transfers and
(b) one un-parallelized grid kernel — both **recoverable**, so "structural" understated
the headroom. The 701 fix remains correctness-positive and perf-neutral; the speed levers
live one layer deeper.

---

## Reproduction
- Profiling sweep: `bash RUN_LOGS/2026-06-11_701_hunt/profile_sweep.sh` (writes
  `prof/<scale>_r<run>.json`); aggregate with the parser in
  `RUN_LOGS/2026-06-11_701_hunt/agg_prof.py`.
- Absolute step time (instrumentation off): `raw_after.tsv` from `timing.sh`.
- Instrumentation is profiler-flag-gated (`-Dtornado.profiler=True`) — **no code change**;
  nothing to merge or keep on a branch. The per-kernel profiler is a stock TornadoVM
  facility.
