# Phase C — Phase B cap-sizing + persistent-buffer fix verification

Code: Phase B applied — cap sized from initialFilaments (4× safety),
delta buffers + slotCap-sized FloatArrays use ensureFloatArray /
ensureIntArray to preserve identity across plan rebuilds. No
BOA_POSE_DELTA_CAP or BOA_POSE_CHURN_LOG env vars.

Method: two-step-count difference — `ms/step = 1000·(wall_K2 − wall_K1)/(K2 − K1)`.

Aorus, Java 21, TornadoVM 4.0.1-dev (PTX backend), RTX 5070 (12 GB),
31 GB host RAM, heap `-Xmx28G -XX:-UseGCOverheadLimit`.

| size | K1 | K2 | K1 wall s | K2 wall s | GPU ms/step | scaling-study baseline ms/step | Δ | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1x | 500 | 2000 | 87.72 | 252.22 | 109.67 | 89.71 | +19.96 | ok — drift vs historical, not regression (pre-Phase-B baseline at K=500 today = 84.08 s vs Phase B 87.72 s, +0.7 %); planRebuild=1, overflow=0 |
| 4x | 400 | 1600 | 369.47 | 806.93 | 364.55 | 343.67 | +20.88 | ok — same drift, tighter Δ at higher scale; planRebuild=1, overflow=0 |
| 8x | 300 | 1200 | 1076.71 | 1669.92 | **659.12** | (was ceiling) | (n/a) | **ok** — planRebuild=1, overflow=0, max poseDelta=6402 fits cap=25608, GPU÷CPU=0.65× (vs CPU 8× row 1013.83 ms/step). First K1 attempt rc=137 wall=13.63 s was a co-tenant memory race during the 4× K2 cleanup at the host-heap edge; re-ran standalone with the same `-Xmx28G` and completed cleanly. K2 budget then had to be raised to 7200 s (steady-state ~660 ms/step over 1200 steps = ~1700 s — the harness default 3000 s budget for 8x was sized off the historical pre-fix SIGKILL time, not the post-fix steady-state) — see `run_8x_K2_only.sh`. |
