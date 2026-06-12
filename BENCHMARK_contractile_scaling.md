# GPU-vs-CPU dense benchmarks — gliding point + ratio-locked contractile weak-scaling

**Date:** 2026-06-11 · **Host:** aorus1 (RTX 5070, 12 GB VRAM; 31 GB host RAM) ·
Java 21, TornadoVM 4.0.1-dev PTX backend · branch base: `main` @ `bd91c34`
(campaign closer — full contractile network runs GPU-resident).

Purpose: the campaign's thesis-validating number — **does pose residency convert
to throughput on the real contractile workload, and does GPU÷CPU improve with
scale?** — plus the recorded **v2 baseline** (the curve v2 must reproduce for
correctness and beat for speed).

---

## TL;DR (verdict)

1. **Residency eliminated the transfer wall — confirmed.** On the contractile
   workload the residual host↔device transfer (`demandSyncPose`) is **fired only at
   output cadence** (7–25 calls over hundreds–thousands of steps) and costs
   **< 1 ms/step at every scale** (0.05 ms/step at 1×, 0.77 ms/step at 16×). The GPU
   path is **kernel-bound**, not transfer-bound — exactly what the residency thesis
   predicted. The v2 lever is kernel efficiency, not PCIe.

2. **But residency does NOT make the contractile workload GPU-faster — GPU is slower
   than CPU at every tested scale** (opposite of dense gliding). At 1× GPU is **9.7×
   slower**; the workload is sparse (hundreds of Things vs the gliding bed's ~1 M
   motors) and the per-step kernels are heavy (binding-grid + joints + cohesion +
   move) and **launch-throttled**: one `cuLaunchKernel → 701 (LAUNCH_OUT_OF_RESOURCES)`
   fires on **every** move-execute (benign — oracle-neutral, no NaN — but it is fixed
   per-launch overhead).

3. **GPU÷CPU improves strongly and monotonically with scale** — the campaign's
   weak-scaling thesis holds: **9.67× → 6.17× → 2.17× → 1.72× → 1.55×** over
   1×→16× (a **6.2× narrowing**). The fixed per-launch overhead (the 701, dispatch)
   amortizes as work grows. No crossover below 1× within 16×, and the closing rate
   flattens (1.72→1.55 from 8×→16×), so a crossover would need a much larger system.

4. **Ceiling is soft (GPU kernel cost), not memory.** At 16× VRAM is **782 MiB /
   12 GB** and host heap is comfortable; both CPU and GPU complete at all scales. The
   contractile network is far smaller in object count than the gliding bed, so neither
   the host-heap wall nor the 8×-CPU-intractability that bounded the gliding study
   appears here. The 701-at-scale is benign and does not cap N or distort the ratio.

**Verdict:** residency did its job (transfer ≈ 0, kernel-bound), and GPU÷CPU on the
real contractile network improves 6.2× across 1×→16× — but the workload is still
**GPU-negative (1.5–9.7× slower)** at these scales because it is sparse and the
contractile kernels are launch-throttled. **This curve is the quantitative v2
baseline**: v2 must reproduce these numbers for correctness and beat the GPU column
(target the 701/launch overhead and kernel efficiency).

---

## Part B1 — Stoichiometry mapping (the biology, made explicit)

Read from `boa10-64Seg` / `boa10-64Seg-dyn` and measured from the realized initial
frame (`frame_000000.json`, seed 1):

| quantity | value | source |
|---|---|---|
| monomers / FilSegment (`filSegLength`) | 64 | param file |
| axial rise / monomer (`actinMonoRadius`) | 0.0027 µm | `Env.java:518-519` (`actinMonoDiam=0.0054`) → **370.4 subunits/µm** (matches literature 370) |
| filament length draw (`min/maxFilLength`) | 0.2–1.5 µm | param file (rejection-sampled in `makeRandomFilament`) |
| realized filaments (1×) | 100 | `initialFilaments` |
| realized segments (1×, step 0) | 197 | measured |
| **total actin length (1×, step 0)** | **101.2 µm** | measured (Σ endpoint distances) |
| mean filament length | 1.01 µm | 101.2 / 100 |
| minifilament size (`numMyoDimersEachEndOfMiniFil`=8) | 8 dimers/end × 2 = **16 dimers** | `Env.java:361` |
| ⇒ molecules / minifilament | 16 (1 dimer ≈ 1 myosin molecule) | — |
| ⇒ heads / minifilament | 32 (2 heads/molecule) | — |
| minifilaments (1×) | 100 | `initialMyoMiniFils` |

**Resulting ratio (initial state):**

- **minifilaments per µm of actin = 100 / 101.2 = 0.99 ≈ 1.0** — hits the biological
  target (≈1 minifilament per µm of actin filament) essentially exactly.
- **effective myosin:actin molar ratio R = 1600 molecules / (101.2 µm × 370) = 1600 / 37 459 = 0.043.**
- **heads per µm of actin = 3200 / 101.2 = 31.6.**

The literature band for actively contractile reconstituted networks is R ≈ 0.05–0.08,
~40–60 heads/µm, non-muscle minifilaments ~15–30 molecules. BoA's `boa10-64Seg-dyn`
lands at R = 0.043, 31.6 heads/µm, 16 molecules/minifilament — **minifilament size is
squarely in-range; R and heads/µm sit just below the band** (consistent with the
low-end 16-molecule minifilament). The existing 100:100 (1:1 minifil:filament) count
already meets the ≈1/µm target, so **it is kept unchanged** for the weak-scaling sweep.

_Note on turnover:_ the ratio above is the **initial** (reproducible) stoichiometry.
With turnover active the actin pool net-grows (segCt 197→~545 over 5000 steps @
actinConc=20); minifilament count is fixed, so minifil/µm drifts downward during a
run. The weak-scaling invariant (Part B2) is enforced at initialization and holds in
the density/statistical sense across the trajectory (same fractional growth at every N).

---

## Part A — Dense gliding point (extends the 2026-06-08 curve)

Existing curve (`RUN_LOGS/2026-06-08_scaling_study/results.md`): pure gliding, no
biochem, density held, box area & motor count scaled by N. GPU÷CPU ≈ 0.65–0.68 from
1× through 8× (GPU 32–35 % faster); 16× OOMs at startup on both paths (host-heap wall).

**Attempted denser point: 12×** (boxXY = 14·√12 = 48.50 µm, 4 800 filaments,
~1.18 M motors — the point between the clean 8× and the 16× startup-OOM). This
exposed **two GPU gliding ceilings that sit below 12×**, both newly characterized:

1. **TaskGraph bytecode wall.** Without `-Dtornado.tvm.maxbytecodesize=16384`, 12×
   throws `java.nio.BufferOverflowException` inside `plan.execute()`
   (`GPUMoveThing.moveThings:5932`) on the first move-execute — the chained graph's
   bytecode exceeds TornadoVM's 4096-byte default at this slot count. 8× did not need
   the flag; 12× does. (Same flag the contractile/minifil path already requires.)
   With the flag, 12× compiles and the first ~40 steps run clean (rc=0).

2. **Host-RAM OOM-kill (the binding ceiling).** With the flag, a sustained 12× GPU run
   is **SIGKILL-ed by the Linux OOM-killer** (`rc=137` at 31.8 s): `-Xmx28G` heap +
   TornadoVM off-heap FloatArrays (slotCap ≈ 7.07 M) exceed the 31 GB host. This is a
   *kill*, not a clean JVM OOM, so it leaves no `OutOfMemoryError` — it caps achievable
   GPU scale at **< 12×** on this host. (VRAM was never the limit: only 2.6 GB / 12 GB
   used at the kill — the wall is host RAM, consistent with the 2026-06-08 finding that
   16× OOMs at startup.)

**Result:** the densest **clean** GPU gliding point on this host remains the existing
**8× (GPU÷CPU = 0.65×)**; the 06-08 curve (0.65–0.68× from 1× to 8×) is unchanged. The
denser-point contribution is this **ceiling refinement**: the gliding GPU practical
ceiling is host-RAM-bound and sits between 8× (clean) and 12× (OOM-killed), with a
TaskGraph-bytecode sub-ceiling at ~12× that the `maxbytecodesize` flag clears. A clean
10× timing point was queued but not run (stopped per operator; the 8× curve + ceiling
characterization are sufficient).

**Gliding vs contractile, side by side** (the two workloads bracket the GPU's behavior):

| workload | per-step parallel work | kernels | GPU÷CPU @ scale | bound by |
|---|---|---|---|---|
| dense gliding (06-08) | ~98 K motors/× (≈1 M at 12×) | light (move + bind) | **0.65×** (GPU faster) | host RAM (8× clean ceiling) |
| contractile network (this) | ~hundreds Things/× | heavy (bind-grid + joints + cohesion + move), 701-throttled | **1.55–9.67×** (GPU slower) | GPU kernel cost (soft) |

Same engine, opposite verdicts: GPU wins on the dense, kernel-light, memory-bound
gliding bed and loses on the sparse, kernel-heavy, launch-throttled contractile network
— and on both, residency has already removed transfer from the critical path.

---

## Part B — Ratio-locked contractile weak-scaling

### B2 — Weak-scaling design

Hold myosin:actin ratio AND actin density constant; grow box ∝ N:
- `boxXY = 10·√N`, `boxZ = 0.5` held → box volume ∝ N, areal + volumetric density constant.
- `filaments = 100·N`, `minifilaments = 100·N` (1:1 ratio held).
- Per-filament length, all biochem/turnover params fixed.

**Choice of areal (√N) growth over isotropic N^(1/3):** the workload is a 0.5 µm
quasi-2D slab. Holding Z and scaling XY by √N (a) keeps volumetric *and* areal density
constant, (b) preserves the slab geometry so z-confinement physics is invariant across
scales (isotropic N^(1/3) would thicken 0.5→1.26 µm at 16×, itself a physics change),
and (c) matches the established 2026-06-08 gliding methodology for direct comparability.
"Filaments per volume constant" holds exactly.

Absolute system sizes:

| N | boxXY (µm) | box vol (µm³) | filaments | minifilaments | myosin heads |
|---:|---:|---:|---:|---:|---:|
| 1 | 10.0 | 50 | 100 | 100 | 3 200 |
| 2 | 14.14 | 100 | 200 | 200 | 6 400 |
| 4 | 20.0 | 200 | 400 | 400 | 12 800 |
| 8 | 28.28 | 400 | 800 | 800 | 25 600 |
| 16 | 40.0 | 800 | 1 600 | 1 600 | 51 200 |

(Counts are initial; turnover grows actin ~2.8× during a 5000-step run at every N.)

### B3 — GPU-vs-CPU timing curve

Full workload (minifilaments + active turnover), GPU-resident vs full-CPU, same seed.
Steady-state ms/step via two-step-diff; mean ± half-spread over seeds (output OFF).

| N | boxXY | fil | mini | heads | seeds | CPU ms/step | GPU ms/step | **GPU÷CPU** |
|---:|---:|---:|---:|---:|:--:|---:|---:|:--:|
| 1× | 10.0 | 100 | 100 | 3 200 | 3 | 3.6 ± 0.1 | 34.8 ± 0.3 | **9.67×** |
| 2× | 14.14 | 200 | 200 | 6 400 | 3 | 6.6 ± 0.4 | 40.7 ± 0.8 | **6.17×** |
| 4× | 20.0 | 400 | 400 | 12 800 | 3 | 23.5 ± 0.3 | 51.0 ± 0.2 | **2.17×** |
| 8× | 28.28 | 800 | 800 | 25 600 | 3 | 40.2 ± 0.6 | 69.2 ± 0.2 | **1.72×** |
| 16× | 40.0 | 1 600 | 1 600 | 51 200 | 1 | 68.1 | 105.5 | **1.55×** |

Inter-seed spread is tiny (GPU ±0.2–0.8 ms/step). The 701 fires deterministically once
per move-execute (identical count across seeds). GPU is slower at every scale, but the
ratio narrows monotonically — GPU per-step grows 34.8→105.5 (×3.0) while CPU grows
3.6→68.1 (×18.9) across the 16× span.

_Caveat (absolute ms/step):_ `boa10-64Seg-dyn` net-grows segCt ~2.8× over a run, so the
two-step-diff reports the **mean** ms/step over [K1,K2]; CPU and GPU at each N share
seed + window, so the **ratio per row is clean** (segCt/length statistically neutral
between paths per the campaign closer). The absolute CPU column is window-sensitive
across N (different K windows sample different growth phases) — read the **ratio** as
the robust quantity, not the cross-N CPU slope.

### B4 — GPU time breakdown (kernel vs transfer vs host)

Per-step decomposition from end-of-run `[STATS] gpuMoveThing` (averaged over seeds):

| N | GPU ms/step | exec (kernel) | host pack (slot+joint+unpack) | transfer (demandSync) | demandSync calls |
|---:|---:|---:|---:|---:|:--:|
| 1× | 34.8 | 25.4 (73%) | 0.67 (2%) | 0.05 (<1%) | 25 |
| 2× | 40.7 | 28.1 (69%) | 1.31 (3%) | 0.05 (<1%) | 25 |
| 4× | 51.0 | 33.8 (66%) | 2.65 (5%) | 0.09 (<1%) | 20 |
| 8× | 69.2 | 44.8 (65%) | 5.97 (9%) | 0.14 (<1%) | 13 |
| 16× | 105.5 | 64.9 (62%) | 13.4 (13%) | 0.77 (<1%) | 7 |

(The exec column carries the kernel-launch/701 overhead. The remainder of GPU ms/step
beyond exec+pack+transfer is JVM/host per-step orchestration and the two-step-diff
residual.)

**Confirmed kernel-bound at every scale.** exec (the device kernels, incl. the 701
launch-failure round-trip) is 62–73% of GPU per-step. **Transfer is negligible** —
`demandSyncPose` runs at output cadence only (residency working as designed), never
> 0.8 ms/step. Host-side SoA packing (`slotPack`) is the secondary cost and the only
component growing materially with N (0.4→9.5 ms/step) — a candidate v2 cleanup, but
secondary to the kernel. **The v2 lever is kernel efficiency + the 701 launch
throttle, not PCIe transfer.**

### B5 — Practical ceiling

- **Memory is NOT the ceiling.** At 16× (1 600 fil + 1 600 mini, 51 200 heads) GPU VRAM
  use is **782 MiB / 12 GB** and host heap (`-Xmx16G`) is comfortable. The contractile
  network's object count is tiny vs the dense gliding bed, so the host-heap wall that
  capped the gliding study at 16× does not appear here — 32×/64× are reachable on memory.
- **CPU does NOT become intractable before GPU** (contrast the gliding study, where CPU
  hit an 8× heap ceiling): CPU at 16× is 68 ms/step, fully tractable. Both paths complete
  at every tested scale, so the GPU÷CPU comparison is valid across the whole range.
- **701-at-scale is benign and does not cap N.** One `Returned: 701`
  (LAUNCH_OUT_OF_RESOURCES) fires per move-execute on `boa10-64Seg-dyn`-family configs
  (`slotCap` grows linearly 19 602→313 602 across 1×→16×). Per the campaign closer it is
  oracle-neutral (segCt/length match CPU, no NaN). It is a **fixed per-launch overhead**
  baked into exec — it does not distort the ratio trend (it amortizes with N, which is
  *why* GPU÷CPU improves) and does not corrupt results. It is the **#1 v2 kernel-launch
  tuning lever** (register pressure / block size on the move + bind kernels).
- **The contractile ceiling is therefore soft** — GPU kernel cost, not a hard wall. The
  sweep stops at 16× because the trend is established and larger N only costs wall-time.

---

## Reproduction

- Gliding dense point: `bash RUN_LOGS/2026-06-08_scaling_study/run_dense_point.sh`
- Contractile sweep: `bash RUN_LOGS/2026-06-11_contractile_scaling/run_contractile_scaling.sh`
  (base config + per-size `.pf` variants + `results.md` + `raw_rows.tsv` written alongside)
