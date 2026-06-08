# Scaling study — CPU + GPU steady-state ms/step vs simulation size

Current `main` HEAD only (`bcc1c7c` retro-sweep + scaling-study cap raises).
Pure gliding (no biochem). Aorus, Java 21, TornadoVM 4.0.1-dev (PTX backend),
RTX 5070 (12 GB VRAM), 31 GB host RAM. Heap `-Xmx24G -XX:-UseGCOverheadLimit`
for sizes ≤ 8×; one 16× attempt with `-Xmx28G` (still OOMs at startup).

Scaling: hold motor density at 500/µm² and filament density at ~2/µm²;
scale **box area** (motors) and **filament count** together by N.
`boxXY = 14·√N`, `initialFilaments = 400·N`, motor count ≈ 500·boxXY²
≈ 98000·N. Base ratio ≈ 245 motors/filament preserved.

Method: two-step-count difference — `ms/step = 1000·(wall_K2 − wall_K1)/(K2 − K1)`.
K1 just past warmup, K2 gives a stable window; both shrink at larger sizes
to keep each single run bounded. dt = 1e-5 s throughout.

Static cap raises (committed with this study, current code):
- `Myosin.theMyosins[]`     500K → 8M
- `MyoMotor.theMotors[]`    500K → 8M
- `Thing.theThings[]`       2M   → 32M
- `MyoFilLink.maxLinks`     500K → 8M
- `MyoMotor.soa*[]` (10 SoA pose/orient arrays)  500K → 8M

| size | N | boxXY (µm) | motors | fil | K1 | K2 | CPU K1 s | CPU K2 s | CPU ms/step | GPU K1 s | GPU K2 s | GPU ms/step | GPU÷CPU | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 0.25× | 0.25 | 7.000  | 24500    | 100   | 500 | 2000 | 26.84   | 87.71   | 40.58   | 25.43  | 76.83  | 34.27  | 0.84× | ok |
| 0.5×  | 0.5  | 9.899  | 48995    | 200   | 500 | 2000 | 46.77   | 154.05  | 71.52   | 39.63  | 119.78 | 53.43  | 0.75× | ok |
| 1×    | 1.0  | 14.000 | 98000    | 400   | 500 | 2000 | 90.51   | 288.55  | 132.03  | 68.56  | 203.12 | 89.71  | 0.68× | ok — matches retro sweep row (129.84 / 88.87) within seed-noise |
| 2×    | 2.0  | 19.799 | 196000   | 800   | 500 | 2000 | 196.44  | 578.78  | 254.89  | 153.86 | 406.96 | 168.73 | 0.66× | ok |
| 4×    | 4.0  | 28.000 | 392000   | 1600  | 400 | 1600 | 411.58  | 1017.63 | 505.04  | 362.50 | 774.90 | 343.67 | 0.68× | ok |
| 8×    | 8.0  | 39.598 | 784000   | 3200  | 300 | 1200 | 1048.18 | 1960.63 | 1013.83 | —      | —      | —      | —     | **GPU ceiling** — kernel runs but `poseDelta overflow → plan rebuild` fires every step (binding churn at this M exceeds POSE_DELTA_CAP=4096), pinning host memory until SIGKILL (rc=137 at 759 s) |
| 16×   | 16.0 | 56.000 | 1568000  | 6400  | 200 | 700  | —       | —       | —       | —      | —      | —      | —     | **CPU heap ceiling** — `java.lang.OutOfMemoryError: Java heap space` at `Mesh.<init>` (CPU) / `Thing.<init>` (GPU) during startup. Persists at `-Xmx28G` (host RAM is 31 GB so further heap is impractical without swap) |
| 32×   | 32.0 | 79.196 | 3136003  | 12800 | 100 | 400  | —       | —       | —       | —      | —      | —      | —     | not attempted — 16× ceiling already hit |

## Ceilings (the headline results)

- **GPU practical ceiling = 4×** (M=392 000 motors, F=1600 filaments). The
  last clean GPU row at 343.67 ms/step. At 8× the kernel itself executes
  but the per-step pose-delta dirty count blows past `POSE_DELTA_CAP=4096`
  (segment slot-renumbering from `theFilSegments` swap-compaction across
  3200 filaments creates more dirty entries per step than the cap allows),
  triggering a full plan rebuild every step → host memory grows
  monotonically → SIGKILL. **VRAM itself is not exhausted** — `nvidia-smi`
  shows 216 MB used / 12 227 MB total when the run dies; the kill is on
  the host side. This is a structural ceiling of the current engine, not
  the hardware. Lifting it would require growing `POSE_DELTA_CAP` (and
  the scatter kernel's per-step thread budget) so the steady-state dirty
  set fits, or batching the slot-renumber deltas across steps.

- **CPU practical ceiling = 8×** (M=784 000 motors, F=3200 filaments) at
  **1013.83 ms/step** (≈ 1 s/step — already on the edge of impractical for
  a multi-second biological window). At 16× the JVM fails to even
  initialize at the largest heap the 31 GB host can safely commit: the
  `Mesh.<init>` allocation for a 281×281×4-cell bin grid (binDepth=1000)
  + Thing/Myosin object graph blows past `-Xmx28G`.

## Notes

- 1× row (132.03 / 89.71 ms/step) reproduces the 2026-06-07 retro sweep's
  post-residency row (129.84 / 88.87) within the seed-1 noise band — this
  validates that the new measurement harness is consistent with the
  established one (same method, same config, same code).
- The GPU÷CPU ratio is steady around 0.66–0.68 from 1× through 4× (GPU
  ~33 % faster than CPU at any sustainable scale). Below 1× the ratio
  loosens (0.75× at 0.5×, 0.84× at 0.25×) as GPU dispatch overhead
  amortizes less well. The GPU lead is widest in absolute ms/step
  at 4× (CPU 505 vs GPU 344 — 161 ms/step gap).
- CPU per-step scaling 1× → 8× is 132 → 1014 ms/step over 8× the work
  → factor 7.68× → very close to linear (slight sub-linear due to fixed
  per-step overhead amortizing). GPU 1× → 4× is 90 → 344 ms/step over
  4× the work → factor 3.83× — also near-linear at this range.
- Per-size param-file variants are saved alongside this file as
  `<size>_<cpu|gpu>_K<1|2>.pf`; per-run logs and `time -f %e` wall files
  share the same basename.

## Reproduction

```
bash RUN_LOGS/2026-06-08_scaling_study/run_scaling.sh
```

The harness checks/creates the results table header, writes each row
immediately after both K1 and K2 land (crash-safe), logs progress to
`runner.log` and overwrites `.last_run_status` per run. To resume only
a subset of sizes, edit the `SIZES=( … )` array in `run_scaling.sh`;
the existing rows in `results.md` are preserved.
