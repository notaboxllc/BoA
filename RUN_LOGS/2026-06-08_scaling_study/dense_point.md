# Part A — denser gliding point (extends 2026-06-08 scaling curve)

Same base_config, same method (two-step-diff, single seed=1, dt=1e-5, output off).
boxXY = 14*sqrt(N), fil = 400*N, motors ~ 500*boxXY^2.

| size | N | boxXY | motors | fil | K1 | K2 | CPU ms/step | GPU ms/step | GPU/CPU | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
