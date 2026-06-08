# Retrospective campaign sweep — CPU + GPU steady-state s/step

Fixed config: `/home/jba/Code/BoA/RUN_LOGS/2026-06-07_retro_sweep/retro_sweep_config` (400 random filaments, 14×14×0.5 µm bed @ 500/µm² ≈ 98K
motors). Byte-identical at every commit; only the `runTime` line is varied
between K1 = 500 steps and K2 = 2000 steps (paired runs, dt = 1.0E-5).
Steady-state s/step = (wall_K2 − wall_K1) / (2000 − 500).

Heap: `-Xmx20G -XX:-UseGCOverheadLimit` on both paths (the pre-campaign
commits' `-gpu` startup needs > 4G for class init).

CPU command (no `-gpu`):

```
java --enable-preview -Xmx20G -XX:-UseGCOverheadLimit \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -pf <pf> -seed 1
```

GPU command:

```
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx20G \
     -XX:-UseGCOverheadLimit -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf <pf> -seed 1
```

Aorus, Java 21, TornadoVM 4.0.1-dev (PTX backend), RTX 5070 (12 GB), 31 GB RAM.

| commit | hash | CPU K1 s | CPU K2 s | CPU ss ms/step | GPU K1 s | GPU K2 s | GPU ss ms/step | GPU÷CPU | notes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| pre-campaign | `6b5599f` | 115.41 | 371.75 | 170.89 | 117.57 | 381.96 | 176.26 | 1.03x | ok |
| iter2b | `bb7dc99` | 113.02 | 372.38 | 172.91 | 185.81 | 615.43 | 286.41 | 1.66x | ok |
| iter2c | `bb7829e` | 114.54 | 376.85 | 174.87 | 162.34 | 537.04 | 249.80 | 1.43x | ok |
| iter2d | `8027662` | 114.47 | 372.90 | 172.29 | 109.91 | 351.37 | 160.97 | 0.93x | ok |
| pre-4.5 | `b044874` | 89.58 | 284.88 | 130.20 | 75.03 | 216.05 | 94.01 | 0.72x | ok |
| post-4.5 | `7759be7` | 88.82 | 287.55 | 132.49 | 73.22 | 217.85 | 96.42 | 0.73x | ok |
| post-residency | `498bb7c` | 89.55 | 284.31 | 129.84 | 69.64 | 202.94 | 88.87 | 0.68x | ok |
