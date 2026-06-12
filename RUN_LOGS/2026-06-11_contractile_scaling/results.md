# Contractile-network weak-scaling sweep — GPU-resident vs full-CPU ms/step

Workload: boa10-64Seg-dyn (100 actin filaments + 100 minifilaments + ACTIVE
turnover) per 1x. Weak scaling: boxXY = 10*sqrt(N), Z = 0.5 held; filaments &
minifilaments = 100*N (myosin:actin ratio + actin density held constant).
dt = 1e-4. Aorus, Java 21, TornadoVM 4.0.1-dev PTX, RTX 5070, heap -Xmx16G.
GPU adds -Dtornado.tvm.maxbytecodesize=16384.

Per-seed steady-state ms/step = 1000*(wall_K2 - wall_K1)/(K2 - K1). Reported:
mean +/- half-spread over seeds. Output OFF during timing.

| size | N | boxXY | fil | mini | heads | K1 | K2 | seeds | CPU ms/step | GPU ms/step | GPU/CPU | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1x | 1.0 | 10.000 | 100 | 100 | 3200 | 500 | 2500 | 3 | 3.6 +/- 0.1 | 34.8 +/- 0.3 | 9.67x | slotCap=19602 701/K2run=2511 |
| 2x | 2.0 | 14.142 | 200 | 200 | 6400 | 500 | 2500 | 3 | 6.6 +/- 0.4 | 40.7 +/- 0.8 | 6.17x | slotCap=39202 701/K2run=2511 |
| 4x | 4.0 | 20.000 | 400 | 400 | 12800 | 400 | 2000 | 3 | 23.5 +/- 0.3 | 51.0 +/- 0.2 | 2.17x | slotCap=78402 701/K2run=2011 |
| 8x | 8.0 | 28.284 | 800 | 800 | 25600 | 300 | 1300 | 3 | 40.2 +/- 0.6 | 69.2 +/- 0.2 | 1.72x | slotCap=156802 701/K2run=1310 |
| 16x | 16.0 | 40.000 | 1600 | 1600 | 51200 | 200 | 700 | 1 | 68.1 +/- 0.0 | 105.5 +/- 0.0 | 1.55x | slotCap=313602 701/K2run=710 |
