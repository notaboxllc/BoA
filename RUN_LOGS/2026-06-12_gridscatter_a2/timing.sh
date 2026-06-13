#!/usr/bin/env bash
# A2 ms/step timing (BOA_STEP_PROFILE wall) — GPU parallel scatter vs GPU serial
# scatter vs CPU, at 1x/8x. No tornado profiler (clean wall). Window=[300,650).
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
export TPROF=""   # env.sh gpu_run references $TPROF (optional profiler flag)
D=RUN_LOGS/2026-06-12_gridscatter_a2
export BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=300

row() { grep -E "STEP_PROFILE\] window" "$1" | tail -1; }

for s in 1 8; do
  pf="ParameterFiles/boa10-64Seg-dyn-dense-${s}x"
  # GPU parallel scatter (default, chunk 64)
  XMX=24G BOA_SCATTER_CHUNK=64 gpu_run -pf "$pf" > "$D/t_${s}x_gpu_par.log" 2>&1
  echo "${s}x GPU-par : $(row $D/t_${s}x_gpu_par.log)"
  # GPU serial scatter (oracle)
  XMX=24G BOA_SERIAL_SCATTER=1 gpu_run -pf "$pf" > "$D/t_${s}x_gpu_ser.log" 2>&1
  echo "${s}x GPU-ser : $(row $D/t_${s}x_gpu_ser.log)"
  # CPU
  XMX=24G cpu_run -pf "$pf" > "$D/t_${s}x_cpu.log" 2>&1
  echo "${s}x CPU     : $(row $D/t_${s}x_cpu.log)"
done
echo "TIMING DONE"
