# Dense contractile compute benchmark (v4 — heavy-crosslink load, no percolation gate)

> **Follow-up (2026-06-12, `COPYOUT_RESIDENCY.md`):** the flat ~115 ms / ~1.08 GB
> copy-out flagged below was identified as the fixed 1.024 GB `ffCandPartner`
> fil-fil candidate buffer, declared `EVERY_EXECUTION` but consumed by the host only
> at crosslink cadence (every 100 steps). Cadence-gating it to `UNDER_DEMAND` (Part 1
> there) moved **GPU÷CPU from 3.32→1.17 at 1× and 1.12→0.82 at 8× (GPU now wins at
> dense)**, crossover ~10×→~1.3×, physics-neutral. Part 2 there decomposes the host
> "other" bucket; the GPU elephant is `recompute`/`onStepStart` (superlinear).

Branch `benchmark-contractile-dense`. Machine: aorus1, RTX 5070 (12 GB), Java 21,
TornadoVM 4.0.1-dev PTX. `main` + `crosslink-lifecycle` merged (`3d0b6f7`). 2026-06-12.

## TL;DR

This run **completed Parts A–E** (the v3 percolation gate is dropped: compute cost is
count-driven, not connectivity-driven). Headlines:

- **GPU does NOT win at dense — at any scale tested.** GPU÷CPU = **4.95 at 0.5×**, **3.32 at
  1×**, 2.24 at 2×, 1.55 at 4×, **1.12 at 8×**. The GPU step is **latency / serial-kernel
  bound** at small N — its device `exec` is ~139 ms/step at 0.5×, ~169 at 4×, ~205 at 8×
  (16× more work, +48 %), strongly sublinear — so the chained task graph's fixed per-step
  kernel-launch + serial-dependency cost dominates while CPU scales ∝ N. CPU is 1.5–5× faster
  through 4×; at 8× the gap closes to 1.12× (GPU still behind). Crossover (GPU÷CPU → 1) lands
  **just beyond 8×**. At 8× the GPU step has *also* become ~45 % host-bound (`pack` ∝ N, 61 ms),
  so even past crossover the win would be modest.
- **The exec is not kernel-bound — it's transfer-bound by a flat ~115 ms/execute device→host
  copy-out** (~1.08 GB/execute, ~9.4 GB/s, *constant across 1×/4×/8×* — a fixed-size buffer
  copied every step). Kernel compute totals only 4 ms (1×) → 33 ms (8×). The dominant *kernel*
  is **`gridScatter`** (the bind-grid counting-sort build) and it's the only **superlinear** one
  (3.1→14.7→29.9 ms, **9.6× over 8× population**) — the next `gridAssemble`-style target. The
  `bind` narrow-phase **did NOT wake up** (0.14→0.44 ms, negligible) — the density cost landed in
  the grid *build*, not the narrow phase. **The single biggest GPU win available is killing the
  flat 115 ms copy-out** (size-independent overhead, ~80 % of exec at 1×).
- **Largest every-step host phase: `pack`** (OOP→device FloatArray gather), and unlike
  `exec` it scales **∝ N** (3.8 → 31 ms/step over 0.5→4×). `meshFill`≈0 (FILSEG_MESH skipped),
  `sync` cadenced, `crosslinkForce` non-trivial but only weakly link-count-driven.
- **Memory ceiling at 8× is a fixed array cap, not VRAM/RSS:** `MyosinDimer.theMyoDimers`
  (100 000) overflows at 8 000 minifils × 16 dimers = 128 k. Raised to 300 000 on this branch
  to complete the sweep. VRAM stays ~1.6–1.8 GB through 4× (no device-memory ceiling on 12 GB).
- **Two science items remain open, reported not fixed:** crosslinks *bundle* rather than
  *bridge*; F-actin sits 8× below geometric in sim-internal units (box-volume convention).

## Part A — heavy-crosslink calibration (no percolation gate)

Calibrated on the committed `boa10-64Seg-dyn-dense` 1× fixture (1000 filaments ~1.1 µm,
1000 minifilaments, turnover ON). Goal: a stable formation/dissolution balance representing a
heavy contractile crosslink load. Formation is `P_form = 1−exp(−k_on·[xlink]·Δt_check)` per
qualifying candidate per `crosslinkCheckInt` (=100 steps); dissolution is the force-dependent
FilLink Bell off-rate every step.

**Key correction over v3:** the steady-state metric must be **active** links
(`PercolationProbe.activeLinks`, which checks `FilLink.active`), **not** `FilLink.filLinkCt`
(the array population, which includes broken-but-uncompacted links). The two diverge sharply at
large reach: a link forms wherever two segments approach within `crossLinkGrabDist`, but if the
contact exceeds `maxFilLinkDist` (the spring break distance) it is born over-stretched and the
Bell off-rate kills it within a step — it lingers in the array (inflating `filLinkCt`) while
contributing **no force**. So `filLinkCt` reaches 1000–2000 at large grab while *active* links
collapse.

### Active-link sweep (CPU, 1× density, 1200-step runs; activeLinks = end-of-run probe)

| grab (µm) | maxFilLinkDist | active links | array pop (settled) | note |
|---|---|---|---|---|
| 0.05 | 0.02 (default) | **~500** (446–518) | ~440–535 | active-link peak |
| 0.08 | 0.02 | 493 | 607 | |
| 0.15 | 0.02 | 204 | 788 | churn rising |
| 0.25 | 0.02 | 111 | 1081 | array≫active (all churn) |
| 0.03 | 0.03 | 407 | 402 | grab=break matched |
| 0.05 | 0.05 | 397 | 420 | |
| 0.08 | 0.08 | 481 | 607 | |
| 0.12 | 0.12 | 330 | 715 | |
| 0.18 | 0.18 | 135 | 809 | |
| 0.25 | 0.25 | 99 | 1115 | |

**The ~1000–2000 *active*-link target is geometrically unreachable at 10× density.** Active
links peak ~480–520 then *fall* as reach grows (longer links are more strained → break faster).
A 1.1 µm filament sweeping radius 0.08 µm has ~0.4 neighbours at 20 fil/µm³, which matches the
~480 active links measured — a hard density+strain-dissolution wall, the same wall the v3
percolation finding hit, now measured as active-link count. This is **consistent with the v3
density wall** and is *not* a gate failure (percolation dropped); we calibrate to the active-link
maximum.

**Locked config (held across the whole sweep):** `crossLinkGrabDist=0.05`,
`maxFilLinkDist=0.02` (default), `xLinkOnRate=400`, `xLinkConc=1.0` → ~500 active links at 1×
(~0.5 links/fil), a clean formation/dissolution balance (`finalActive ≈ settled`).

### Realized load across the sweep (window [300,660), BCD runs) — scales ∝ N ✅

| scale | filaments | active links (CPU) | active (GPU) | per-fil (CPU) |
|---|---|---|---|---|
| 0.5× | 500 | 188 | 142 | 0.38 |
| 1× | 1000 | 373 | 232 | 0.37 |
| 2× | 2000 | 745 | 456 | 0.37 |
| 4× | 4000 | 1458 | 894 | 0.36 |
| 8× | 8000 | 3134 | 1599 | 0.39 |

Active-link load scales **∝ N** on both paths (weak scaling holds density constant). The **GPU
path forms ~60 % of the CPU's links** (1× 232 vs 373) — the known float32-trajectory /
single-thread-RNG / 1-step-lag candidate-drain seam, not a broad-phase miss (the device
candidate set was proved complete in the crosslink-lifecycle validation). Reported, not a bug.

### F-actin — both conventions (for the record, not a gate)

F-actin is held constant across the weak-scaling sweep at **~1.72 µM sim-internal** /
**~13.7 µM geometric**. `Chamber.makeABox` normalises concentration by `boxVolume = 8·dimX·dimY·dimZ`
(400 µm³ at 1×) while filaments occupy the 50 µm³ region — an 8× factor. The biochem rates draw
from the sim-internal value; the geometric value is on the 12–20 µM target. **Reported, not
blocked** ([[boxvolume-8x-concentration-convention]]).

### Connectivity (for the record, not a gate)

Largest connected component stays ≤1 % of filaments, spanFrac ~0.15, `percolates=false` at all
sweep scales — expected (crosslinks bundle adjacent pairs rather than bridge,
[[dense-network-percolation-density-wall]]). Not a gate in v4.

### GPU-path cleanliness (Part A gate, the only retained gate)

1× GPU run at the locked config: `overflowSegs=0`, `planRebuild=1` (initial only),
`filsegMeshFillSkipped` = every step, `demandSyncPoseCalls` at biochem cadence (3 over the
window), `poseDelta overflow=0`, NaN=0, no 701/packRange/ClassCast. **Clean at every sweep
scale through 8×.**

## Part B — speed sweep (weak scaling, 0.5×–8×) — headline

`boxXY = 10·√N`, depth 0.5 µm, populations ∝ N, crosslink params from A held. In-process
windowed timing: warmup **W=300**, window **M=350** (`BOA_STEP_PROFILE`), `ms/step =
window_wall/M`. 1 seed; profiler OFF. 1× stability: CPU 51.31/51.33, GPU 170.49/173.89 ms/step
(half-spread <1 %).

| scale | filaments+minifils | **CPU ms/step** | **GPU ms/step** | **GPU÷CPU** |
|---|---|---|---|---|
| 0.5× | 500 | 31.83 | 157.62 | **4.95** |
| 1× | 1000 | 51.31 | 170.49 | **3.32** |
| 2× | 2000 | 88.20 | 197.82 | 2.24 |
| 4× | 4000 | 165.15 | 255.96 | 1.55 |
| 8× | 8000 | 332.86 | 372.52 | **1.12** |

**Is GPU winning at dense? No — not even at 8×.** CPU is 1.5–5× faster through 4× and still
1.12× faster at 8×. CPU scales ~linearly in N (32→51→88→165→333, ≈∝N above 1×); GPU scales
weakly (158→170→198→256→373) because its device `exec` is strongly sublinear (latency-bound,
Part C/E). GPU÷CPU shrinks monotonically with scale; the curves cross **just beyond 8×**, so the
GPU path only pays off for populations larger than this production-density sweep — and even then
the host `pack` phase (∝ N, 45 % of the 8× GPU step) caps the achievable speedup.

## Part C — host-phase decomposition (confirmatory)

`BOA_STEP_PROFILE=1`, window [300,660). **GPU path** (ms/step):

| scale | exec | pack | crosslinkForce | meshFill | step | gather | brownian | biochem | sync | host(tot−exec) | other |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.5× | 139.0 | 3.78 | 0.77 | 0.00 | 1.44 | 0.99 | 1.47 | 1.62 | 0.56 | 18.6 | 11.9 |
| 1× | 142.2 | 8.00 | 0.85 | 0.00 | 2.36 | 1.19 | 1.69 | 2.08 | 0.59 | 28.3 | 16.7 |
| 2× | 150.8 | 15.93 | 0.93 | 0.00 | 4.00 | 1.41 | 1.91 | 2.98 | 0.02 | 47.0 | 27.1 |
| 4× | 168.6 | 30.96 | 1.07 | 0.00 | 6.98 | 1.43 | 3.08 | 4.70 | 0.04 | 87.4 | 50.5 |
| 8× | 205.4 | 61.37 | 1.50 | 0.01 | 13.14 | 1.56 | 5.20 | 8.18 | 0.08 | 167.1 | 95.6 |

- **`pack` is the largest every-step *host* phase** and scales **∝ N** (3.8→31 ms over 0.5→4×) —
  the OOP→device FloatArray gather. As `exec` is flat, `pack`+`other` are what grow the host
  bucket; they will overtake the host budget at large N. **Next host target: `pack`.**
- **`meshFill` ≈ 0** on `-gpu` (FILSEG_MESH skipped) ✅. **`sync`** small and cadenced ✅.
- **`crosslinkForce` is non-trivial (0.77–1.07 ms/step)** but only weakly link-count-driven —
  at the v3 gate (28 links) it was 0.77; here (232–894 active links) 0.85–1.07. The phase cost
  is dominated by the per-step FilLink array scan + Bell dissolution bookkeeping, not the
  active-link force sum. Reported at load as requested.
- **`other`** (motor-fil collision sample, resetCt, cleanups, recompute, logAndDraw cadence)
  scales ∝ N and is large (50 ms at 4×).

**CPU path** (ms/step) — everything is per-element CPU work, all phases scale ∝ N:

| scale | cpuIntegrate | step | gather | brownian | crosslinkForce | meshFill | biochem | other |
|---|---|---|---|---|---|---|---|---|
| 0.5× | 3.89 | 3.20 | 2.03 | 2.38 | 0.71 | 2.63 | 1.36 | 15.6 |
| 1× | 6.99 | — | 3.55 | — | — | — | 1.87 | 25.6 |
| 2× | 13.45 | 10.54 | 6.68 | 6.97 | 0.84 | 3.23 | 2.82 | 43.7 |
| 4× | 26.18 | 20.41 | 12.84 | 13.05 | 1.15 | 4.21 | 4.72 | 82.6 |
| 8× | 53.51 | 41.20 | 25.40 | 26.24 | 1.73 | 6.18 | 8.53 | 170.1 |

## Part D — memory ceilings

`-Xmx24G` (so the ceiling is real, not an artificial heap cap). Peak host RSS = `VmHWM`
(`/usr/bin/time -v`); JVM used heap + slotCap/devBufEst from `[STATS] mem`; device VRAM = peak
`nvidia-smi memory.used` (NVML; single-tenant box).

| scale | path | RSS (MB) | used heap (MB) | VRAM (MB) | slotCap | myoCap | devBufEst (MB) | overflow | planReb |
|---|---|---|---|---|---|---|---|---|---|
| 0.5× | cpu | 3116 | 2812 | — | — | — | — | 0 | — |
| 0.5× | gpu | 4239 | 1640 | 1636 | 98 002 | 32 000 | 123.1 | 0 | 1 |
| 1× | cpu | 2495 | 2483 | — | — | — | — | 0 | — |
| 1× | gpu | 4866 | 1864 | 1679 | 196 002 | 64 000 | 138.2 | 0 | 1 |
| 2× | cpu | 3585 | 2480 | — | — | — | — | 0 | — |
| 2× | gpu | 5390 | 2514 | 1733 | 392 002 | 128 000 | 168.4 | 0 | 1 |
| 4× | cpu | 7831 | 7227 | — | — | — | — | 0 | — |
| 4× | gpu | 7145 | 3499 | 1843 | 784 002 | 256 000 | 228.9 | 0 | 1 |
| 8× | cpu | 12 371 | 13 288 | — | — | — | — | 0 | — |
| 8× | gpu | 10 472 | 5376 | 2043 | 1 568 002 | 512 000 | 349.8 | 0 | 1 |

**Ceiling datum — which bound first:** a **fixed Java array cap**, not VRAM or RSS.
`MyosinDimer.theMyoDimers` is allocated at 100 000 entries; weak scaling needs 8 000 minifils ×
16 dimers = **128 000**, so the stock build hits `ArrayIndexOutOfBoundsException` at 8× during
`makeInitialMyoMiniFils` (4× = 64 000 fits). Raised to 300 000 on this branch to complete the
sweep. **No device-memory ceiling** is reached: VRAM is only **2.0 GB at 8×** (devBufEst
123→350 MB + ~1.5 GB TornadoVM/CUDA context), so the RTX 5070's 12 GB is far from exhausted —
the device path would scale to perhaps ~40–50× before VRAM bites; the binding limits well before
that are the fixed Java array caps (raise `theMyoDimers`; watch `theFilSegments`=1M /
`myoMiniFils`=10k for longer-than-benchmark runs that net-grow). **Host RSS is the next ceiling
after the array caps:** 12.4 GB (CPU) / 10.5 GB (GPU) at 8×, within the 31 GB box but ~half of it
— so ~16× would approach a host-RAM wall (heap used 13.3 GB CPU at 8×). Summary: **array caps
bind first (~8×), host RAM next (~16×), VRAM last (~40×+).**

## Part E — kernel profiler (the device-bound story)

`-Dtornado.profiler=True` at 1×, 4×, 8× (GPU). Per-task `TASK_KERNEL_TIME` (ms/execute),
warmup-skipping 15 executes; 2 runs averaged. (Profiler adds ~overhead vs the BCD window; use it
for the *split and slope*, not absolute ms/step.)

### Graph wall = copy-out (flat) + copy-in (∝N) + kernels (∝N), NOT kernel-bound

| scale | graph wall | **copy-out** | copy-in | total kernel | copy-out bytes | copy-in bytes |
|---|---|---|---|---|---|---|
| 1× | 144.1 | **115.5** | 21.0 | 4.27 | 1083 MB | 182 MB |
| 4× | 170.3 | **115.5** | 34.8 | 16.78 | 1089 MB | 312 MB |
| 8× | 204.0 | **115.0** | 52.4 | 33.34 | 1098 MB | 486 MB |

**Copy-out is the dominant exec cost and it is FLAT** (~115 ms, ~1.08 GB/execute, *constant
across scale* → ~9.4 GB/s on a fixed-size buffer transferred device→host every step). It is
**80 % of exec at 1×** and still **56 % at 8×** — pure size-independent overhead. Copy-in and
kernel time grow with N; the flat copy-out is the floor that makes `exec` nearly scale-free and
is why GPU loses to CPU. **This is the single biggest optimization target**: the ~1.08 GB
copy-out is independent of `slotCount` (flat across 1×/4×/8× while box and population grow), so it
is a **fixed-capacity buffer set**, not the resident pose — either a genuine ~1 GB
`EVERY_EXECUTION transferToHost`, or a blocking host-sync that waits on one each step. Identifying
and retiring/deferring it (the residency model already defers the *pose* pull to biochem cadence;
this is a *different* fixed buffer that still rides every execute) would more than halve the GPU
step at 1× and is what would let GPU win at dense. Follow-on: enumerate the chained graph's
`transferToHost` declarations and find the ~1 GB fixed-cap one.

### Per-kernel TASK_KERNEL_TIME (ms/execute) + slope

| kernel | 1× | 4× | 8× | 8×/1× | note |
|---|---|---|---|---|---|
| **gridScatter** | 3.13 | 14.70 | 29.91 | **9.57** | bind-grid counting-sort build — **superlinear**, 90 % of kernel time at 8× |
| joints | 0.16 | 0.55 | 1.09 | 6.75 | ∝N (per-Myosin) |
| dimerCohesion | 0.47 | 0.54 | 0.73 | 1.54 | sublinear |
| move | 0.08 | 0.30 | 0.60 | 7.47 | ∝N |
| **bind** | 0.14 | 0.25 | 0.44 | 3.11 | narrow-phase — negligible, did NOT wake up |
| chain | 0.06 | 0.12 | 0.20 | 3.20 | |
| derived | 0.01 | 0.02 | 0.04 | 4.86 | |
| boundary | 0.02 | 0.04 | 0.05 | 3.17 | |
| filFilCandidate | 0.020 | 0.021 | 0.021 | **1.09** | fil-fil broad-phase — flat |
| scatterPose | 0.013 | 0.013 | 0.013 | 1.05 | flat (delta-scatter) |
| segBbox / gridZero / gridHist / gridScan* / segMotorForce / motorForce | <0.07 each | | | ~flat–1.6× | |

- **Dominant kernel: `gridScatter`** (the parallel counting-sort scatter that builds the bind
  grid), and it is the **only superlinear kernel** (9.57× over 8× population vs the ideal 8×).
  At 8× it is 30 ms = 90 % of all kernel time. It is the next `gridAssemble`-style target — but
  note even at 8× it is a quarter of the flat copy-out, so copy-out is the higher-value target.
- **The `bind` narrow-phase did NOT wake up.** It is 0.14 ms at 1× and 0.44 ms at 8× (3.1×,
  sub-N), and `filFilCandidate` (the fil-fil broad-phase) is dead flat (1.09×). The v3 prediction
  that bind would grow ~quadratically with density is **refuted for this sweep**: weak scaling
  holds per-cell occupancy constant, so the narrow phase scales at most ∝N and stays <0.5 ms;
  whatever candidate-pair growth the 10× density caused landed in the grid *build* (`gridScatter`),
  not the narrow phase. (A true density sweep — fixed box, rising count — would be needed to test
  the quadratic-bind hypothesis directly; this constant-density sweep cannot.)
- Superlinear watch-list: only `gridScatter` exceeds the ideal 8× weak-scaling slope. Everything
  else is ≤8× (∝N per-element kernels) or flat (fixed-work kernels).

## Reproduce

```
# Parts B/C/D sweep (both paths, RSS+VRAM capture):
bash RUN_LOGS/2026-06-12_dense_v4/run_bcd.sh
python3 RUN_LOGS/2026-06-12_dense_v4/analyze_bcd.py
# Part E kernel profiler:
bash RUN_LOGS/2026-06-12_dense_v4/run_prof.sh
python3 RUN_LOGS/2026-06-12_dense_v4/analyze_prof.py
```

Sibling fixtures `boa10-64Seg-dyn-dense-{0p5,1,2,4,8}x` (weak scaling, crosslink params from A).
Instrumentation: `[STATS] mem` (heap/slotCap/devBufEst), `PercolationProbe` activeLinks,
`BOA_STEP_PROFILE` windowed host decomposition. No change to main's defaults; the `theMyoDimers`
cap bump lives on this branch.
