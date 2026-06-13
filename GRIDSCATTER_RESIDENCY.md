# gridScatter residency: parallelizing the serial bind-grid scatter

Branch `benchmark-contractile-dense`. Machine aorus1, RTX 5070 (12 GB), Java 21,
TornadoVM 4.0.1-dev PTX. 2026-06-12. `V1_FINISH_LINE.md` item **A2**. Follows the
`gridAssemble` parallelization (which left `gridScatter` serial) and the copy-out /
recompute residency fixes (`COPYOUT_RESIDENCY.md`, `RECOMPUTE_RESIDENCY.md`).

## TL;DR

- **`gridScatter` was the last serial device kernel and the only superlinear one**:
  the counting-sort scatter that fills the bind-grid CSR. The `gridAssemble`
  parallelization split the grid build into six tasks but left the scatter on a
  **single thread** (`WorkerGrid1D(1)`, `@Parallel gid<1`) because the PTX
  `KernelContext.atomicAdd` returns **void** — no fetch-add for a parallel per-cell
  write cursor. Profiler (v4 Part E, dense weak-scaling): **3.13 → 14.70 → 29.91
  ms/execute over 1×/4×/8×** (9.6× over an 8× population — super-∝N), ~90 % of all
  device kernel time at 8×.

- **Phase 1 diagnosis (diagnose-first, per the A1 lesson).** The superlinear driver
  is **single-thread memory latency, not work volume**: ~29.9 ms / (S≈48 k segs ×
  2.82 cells) ≈ **200 ns per scattered write** — a single GPU thread has zero
  latency hiding, so every random-access `cellCount`/`contents` read+write pays full
  global-memory latency, and the cache footprint grows with the box (cellCount ∝ box
  area). The key probe — **cell-crossing rate** — shows the rebuild is mostly
  redundant: only **3.1 % of segments change their AABB cell-set per step**
  (3.14 % @1×, 3.17 % @8× — scale-invariant; center-cell 1.6 %; avg 2.82 AABB
  cells/seg; only 0.3 % of steps have zero crossers).

- **Why not incremental (the rate says low-crossing → incremental).** It is **not
  expressible bit-identically on the compact-CSR layout**: the prefix-sum offsets
  shift globally whenever any cell's count changes, so a single crosser invalidates
  ~half the contents array — O(N) rework, no win. The only layout that admits a
  local incremental update (fixed-capacity-per-cell bins) reintroduces the exact
  fetch-add insertion race that forced the scatter serial in the first place, and
  breaks within-cell order. The redundancy is real but the data structure blocks
  harvesting it without abandoning the bit-identical guarantee. So the **actionable,
  expressible driver is the serial one**: parallelize.

- **Fix — per-cell-chunk-owned parallel scatter (atomic-free, bit-identical).** The
  linear cell-ID space is partitioned into fixed-size chunks; one thread owns each
  chunk's contiguous range `[lo,hi)`. A chunk-thread walks all segments in index
  order and writes each segment's ID into every AABB cell that lands in its range,
  using `cellCount[cellId]` as a **private** write cursor — cells in `[lo,hi)` are
  touched by exactly one thread, so the cursor is race-free **without atomics**.
  Because the owning thread visits segments in increasing `s` order, each cell's
  contents land in the **same order as the serial scatter** → the CSR is
  **bit-identical** (offsets + within-cell order), not merely multiset-equal. A
  linear-cell-span early-out skips segments whose AABB cannot touch the chunk. This
  mirrors the `gridAssemble` "barrier-segmented, atomic-free, per-owner partition"
  playbook. Default chunk size 64 (occupancy vs early-out tradeoff). Serial scatter
  retained as the A/B oracle behind `BOA_SERIAL_SCATTER=1`.

- **CSR bit-identical — proven.** `GridBuildParityTest` (device chunk-scatter vs the
  trusted serial `gridAssembleKernel` host reference): `offsetMismatch=0,
  countMismatch=0, setMismatch=0, orderMismatch=0` at 1× (51×51×4, S=6000, seeds
  1/2/3) and 8× (143×143×4, S=48000, seeds 1/2) grid shapes and chunk sizes 32/64/512.

- **Perf (profiler `TASK_KERNEL_TIME`, warmup-skip 15, dense weak-scaling).**
  `gridScatter` **1× 2.795 → 1.344 ms (2.08×)**, **8× 26.664 → 7.731 ms (3.45×)** at
  the default chunk 64; graph wall **8× 93.7 → 74.2 ms** (−19.5 ms ≈ the kernel
  saving). The superlinear **slope is cut from 9.5× to 5.8×** over 1×→8× — reduced
  (the single-thread latency wall is gone; many chunk-threads now hide it) but not
  killed (the residual is the chunk scan's ∝N² total work, kept small by the
  early-out + parallelism). 8× chunk sweep: par32 **7.60** (best) / par64 7.73 /
  par128 8.24 / par256 10.59 ms — flat near 32–64; default 64 is within 2 % of best.

## Phase 1 — diagnosis (no fix)

### Characterization: serial, dominant, superlinear

Confirmed from code and the v4 profiler:

| | 1× | 4× | 8× | 8×/1× |
|---|---|---|---|---|
| `gridScatter` ms/execute (serial) | 3.13 | 14.70 | 29.91 | **9.6×** |
| next kernel (joints) | 0.16 | 0.55 | 1.09 | — |
| all grid scan/hist/zero kernels | <0.07 each (parallel, cheap) | | | |

The histogram + two-level prefix-sum (`gridZero/gridHist/gridScanLocal/
gridScanChunks/gridScanAdd`) are already parallel and ~0.2 ms total at 8×. The
**scatter is the entire residual grid-build cost** and the only superlinear kernel.

### The superlinear driver — single-thread latency, not work

The scatter's *work* is O(S × cellsPerSeg) ≈ ∝N. The measured 9.6× over an 8×
population, and the ~200 ns/write implied rate, are the signature of a **single GPU
thread paying full, unhidden memory latency** on every scattered (random-access)
read+write, with a cache footprint (cellCount over totalCells ∝ box area) that grows
with scale. Many threads would hide that latency.

### The key probe — cell-crossing rate (host `CrossProbe`, CPU run)

Cell-crossing is a physics property (set by dt, forces, CELL_SIZE), independent of
the device path, so a host probe on a CPU run — where the pose is fresh every step —
characterises the GPU scatter's workload. The AABB→cell math is a byte-for-byte
replica of `segBboxKernelResident`.

| | avg live segs | AABB-cell-set changed/step | center-cell changed/step | avg AABB cells/seg | steps w/ 0 crossers |
|---|---|---|---|---|---|
| dense 1× | 5960 | **3.14 %** | 1.55 % | 2.80 | 0.3 % |
| dense 8× | 47541 | **3.17 %** | 1.59 % | 2.82 | 0.3 % |

The rate is **scale-invariant ~3.1 %** — the full grid rebuild is ~97 % redundant
each step. But only 0.3 % of steps have *zero* crossers, so a whole-step rebuild-skip
(the poseAudit-gate pattern) is useless at scale; any incremental win must be
**per-crosser**.

### Classification

Low crossing ⇒ the prompt's incremental signal — **but incremental is not
expressible bit-identically here** (compact-CSR offset shift; fixed-cap-bin insertion
race — see TL;DR). The expressible, bit-identical driver is the **serial** one. Fix
class: **(b)/(c) — genuine-serial → parallelize by per-owner partition.** The crossing
measurement was still decisive: it proved the ideal fix is incremental and forced the
finding that the layout blocks it, landing the parallelize path with eyes open
(rather than assuming "serial → parallelize" blind).

## Phase 2 — the fix

`GPUMotorBinding.gridScatterChunkKernel` (parallel over cell-chunks) replaces the
serial `gridScatterKernel` on the single-graph production path when
`GPUMoveThing.PARALLEL_SCATTER` (default; `BOA_SERIAL_SCATTER=1` forces the serial
oracle). Chunk size = `GPUMoveThing.SCATTER_CHUNK` (`BOA_SCATTER_CHUNK`, default 64),
carried into the kernel via `gridDims[4]`; the `WorkerGrid` global is the
chunk count padded to a block multiple (block 64, the 701 lesson), the `@Parallel
ch<numChunks` bound self-guarding the padding threads (the gridAssemble
WorkerGrid-remainder lesson). No new buffers; no atomics; +0 tasks (same task slot).

Correctness rests on disjoint cell ownership: cells in `[lo,hi)` are written by
exactly one chunk-thread, so `cellCount[cellId]` (reset to 0 by `gridScanAdd`) is a
private cursor — no race. In-order segment traversal preserves within-cell order.

## Validation

### 1. CSR bit-identical (`GridBuildParityTest`)

Device chunk-scatter vs the trusted serial `gridAssembleKernel` host reference,
random stress AABBs (span 1–3 cells/axis ⊇ the real 2.82-cell avg):

| grid | S | seeds | chunk | offset/count/set/orderMismatch |
|---|---|---|---|---|
| 51×51×4 (1×) | 6000 | 1,2,3 | 64 | **0 / 0 / 0 / 0** |
| 143×143×4 (8×) | 48000 | 1,2 | 64 | **0 / 0 / 0 / 0** |
| 143×143×4 (8×) | 48000 | 1 | 32, 512 | **0 / 0 / 0 / 0** |

Bit-identical CSR (offsets AND within-cell order), every case. Serial regression
(chunk=0) also PASS.

### 2. Physics-neutral (GPU parallel vs serial scatter, same fixture)

Same dense fixture, GPU serial-scatter vs GPU parallel-scatter (chunk 64):

| scale | fActin segs (ser → par) | activeLinks (ser → par) | bindEvents | crosslinkFireCt | overflowSegs | NaN |
|---|---|---|---|---|---|---|
| 1× | 5960 → 5966 (+0.10 %) | 267 → 271 | 1717 → 1831 | 6 → 6 | 0 | 0 |
| 8× | 47597 → 47665 (+0.14 %) | 2169 → 2224 (+2.5 %) | 3906 → 4292 | 6 → 6 | 0 | 0 |

The segs/links deltas are within the GPU path's documented run-to-run
nondeterminism (multithreaded RNG — the recompute A1 study measured an activeLinks
spread of 179–249 @1× and 1499–1719 @8× between *same-config* runs). `crosslinkFireCt`
is identical (6) — formation cadence preserved — and `overflowSegs`/NaN = 0. With the
CSR proven bit-identical (validation 1), the device bind decisions are identical; the
residual divergence is pre-existing nondeterminism, not the scatter.

### 3. Re-measure

**`gridScatter` ms/execute** (profiler, the kernel itself):

| scale | serial | parallel (chunk 64) | speedup |
|---|---|---|---|
| 1× | 2.795 | 1.344 | 2.08× |
| 8× | 26.664 | 7.731 | **3.45×** |
| slope 8×/1× | 9.54× | 5.75× | superlinearity reduced ~40 % |

**End-to-end ms/step** (`BOA_STEP_PROFILE` wall, window [300,660), no profiler
overhead; same binary, GPU serial-scatter vs GPU parallel-scatter vs CPU):

| scale | CPU | GPU serial | GPU parallel | **GPU÷CPU (par)** | GPU÷CPU (ser) |
|---|---|---|---|---|---|
| 1× | 49.63 | 60.46 | 59.30 | 1.195 | 1.218 |
| 8× | 323.63 | 251.34 | **226.78** | **0.701** | 0.777 |

The parallel scatter saves **24.6 ms/step at 8×** (251.3 → 226.8) and pushes
**GPU÷CPU at 8× from 0.777 to 0.701** — GPU already won at dense post-copyout; it now
wins by a wider margin. At 1× the scatter is a small fraction of the step so the
win is marginal (1.218 → 1.195) and GPU still loses there (the fixed per-step host
costs dominate the small box, as documented in `COPYOUT_RESIDENCY.md`).

### What the fix does NOT do

It does not kill the superlinear slope (9.5×→5.8×, not →1). The residual is the chunk
scan's ∝N² total work (each chunk-thread range-checks all S segments). The early-out
caps the constant — but at much larger scales a work-optimal transpose (a fetch-add
parallel scatter with per-cell sort to restore order, or a segment-bucketed scatter)
would be needed. Not warranted at the ≤8× production-density sweep; flagged.

## Reproduce

```
# Phase 1 cell-crossing rate (CPU)
BOA_CROSS_PROBE=1 BOA_PROFILE_WARMUP=300 cpu_run -pf ParameterFiles/boa10-64Seg-dyn-dense-{1,8}x
# CSR bit-identical
java ... boxOfActin.GridBuildParityTest <nX> <nY> <nZ> <S> <seed> <scatterChunk>
# Perf + physics
bash RUN_LOGS/2026-06-12_gridscatter_a2/prof.sh      # gridScatter ms/execute, serial vs par, chunk sweep
bash RUN_LOGS/2026-06-12_gridscatter_a2/timing.sh    # GPU-par / GPU-ser / CPU ms/step at 1×/8×
```

Flags (permanent): `BOA_SERIAL_SCATTER=1` (serial scatter A/B oracle),
`BOA_SCATTER_CHUNK=<n>` (cell-chunk size, default 64), `BOA_CROSS_PROBE=1`
(Phase-1 cell-crossing probe). Nothing merged.
