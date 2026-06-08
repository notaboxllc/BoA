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
| 8×    | 8.0  | 39.598 | 784000   | 3200  | 300 | 1200 | 1048.18 | 1960.63 | 1013.83 | 1076.71¹ | 1669.92¹ | 659.12¹ | 0.65×¹ | ¹ post-`POSE_DELTA_CAP` fix (commit `a812c0c`, 2026-06-08). Original row at the scaling-study commit (`0cbfa20`) was **GPU ceiling** — pose-delta dirty count overran POSE_DELTA_CAP=4096 → leaky plan rebuild → host SIGKILL at 759 s. Fix sized cap from `initialFilaments` at startup + persistent buffer identities across rebuild → 8× now runs flat with `planRebuild=1`, `overflow=0`. Walls from `RUN_LOGS/2026-06-08_pose_churn/8x_phaseC_K{300,1200}.wall` (same K1=300 / K2=1200 schedule). |
| 16×   | 16.0 | 56.000 | 1568000  | 6400  | 200 | 700  | —       | —       | —       | —      | —      | —      | —     | **CPU heap ceiling** — `java.lang.OutOfMemoryError: Java heap space` at `Mesh.<init>` (CPU) / `Thing.<init>` (GPU) during startup. Persists at `-Xmx28G` (host RAM is 31 GB so further heap is impractical without swap) |
| 32×   | 32.0 | 79.196 | 3136003  | 12800 | 100 | 400  | —       | —       | —       | —      | —      | —      | —     | not attempted — 16× ceiling already hit |

## Ceilings (the headline results)

- **GPU practical ceiling = 16× host-heap wall** (post-fix). Originally
  the engine-side ceiling sat at **4×** (last clean GPU row at this
  commit `0cbfa20` was 343.67 ms/step): at 8× the per-step pose-delta
  dirty count blew past `POSE_DELTA_CAP=4096` and triggered a full plan
  rebuild every step → host memory grew monotonically → SIGKILL at ~759 s.
  **VRAM was never exhausted** — `nvidia-smi` showed 216 MB / 12 227 MB at
  the kill. The 2026-06-08 follow-on (commit `a812c0c`) sized the cap
  from `initialFilaments` at startup and made `allocateAndBuildPlan` reuse
  buffer identities across rebuild, retiring both the overflow and the
  orphan-identity leak. With the engine ceiling gone, 8× GPU now runs
  cleanly at 659.12 ms/step (table footnote 1) and the next ceiling is
  the same 16× host-heap wall that already caps the CPU path.

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
- The GPU÷CPU ratio is steady around 0.65–0.68 from 1× through 8× (GPU
  ~32–35 % faster than CPU at any sustainable scale). Below 1× the ratio
  loosens (0.75× at 0.5×, 0.84× at 0.25×) as GPU dispatch overhead
  amortizes less well. The absolute GPU lead grows monotonically with
  size: 42 ms at 1×, 86 ms at 2×, 161 ms at 4×, **355 ms at 8×**.
- CPU per-step scaling 1× → 8× is 132 → 1014 ms/step over 8× the work
  → factor 7.68× → very close to linear (slight sub-linear due to fixed
  per-step overhead amortizing). GPU 1× → 8× is 90 → 659 ms/step over
  8× the work → factor 7.35× — also near-linear (8× row post-fix; see
  table footnote 1).
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
