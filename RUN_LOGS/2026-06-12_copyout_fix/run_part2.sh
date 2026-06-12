#!/usr/bin/env bash
# Part 2: "other"-bucket decomposition at 1x and 8x, GPU + CPU.
# Uses the new BOA_STEP_PROFILE sub-phase brackets (motorFilCol/recompute/
# resetCt/moveDrains/output/cleanup/cleanupTail + otherResidual).
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_copyout_fix
export XMX=24G
WARM=300
FIX () { echo "ParameterFiles/boa10-64Seg-dyn-dense-${1}x"; }

run_one () { # scale path
  local s=$1 path=$2
  local log="$OUT/p2_${s}_${path}.log"
  echo "[$(date +%H:%M)] PART2 $s $path START"
  if [ "$path" = gpu ]; then
    BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM \
      java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx$XMX \
        -Dtornado.tvm.maxbytecodesize=16384 \
        -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
        BoxOfActin -r -gpu -pf $(FIX $s) > "$log" 2>&1
  else
    BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM \
      java --enable-preview -Xmx$XMX \
        -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
        BoxOfActin -r -pf $(FIX $s) > "$log" 2>&1
  fi
  echo "[$(date +%H:%M)] PART2 $s $path DONE"
}

for s in 1 8; do
  run_one $s gpu
  run_one $s cpu
done
echo "[$(date +%H:%M)] === PART2 DONE ==="
