#!/usr/bin/env bash
# A1 Phase 1 diagnosis: GPU recompute sub-bracket (classify / poseAudit / capacity)
# + redundancy ratio, at 1x and 8x dense weak-scaling. Profiling only, no fix.
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_recompute_a1
export XMX=24G
WARM=300
FIX () { echo "ParameterFiles/boa10-64Seg-dyn-dense-${1}x"; }

run_gpu () { # scale
  local s=$1
  local log="$OUT/p1_${s}_gpu.log"
  echo "[$(date +%H:%M)] A1-P1 ${s}x gpu START"
  BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM \
    java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx$XMX \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      BoxOfActin -r -gpu -pf $(FIX $s) > "$log" 2>&1
  echo "[$(date +%H:%M)] A1-P1 ${s}x gpu DONE -> $log"
}

for s in 1 8; do run_gpu $s; done
echo "[$(date +%H:%M)] === A1-P1 DONE ==="
