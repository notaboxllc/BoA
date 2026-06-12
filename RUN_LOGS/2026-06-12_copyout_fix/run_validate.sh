#!/usr/bin/env bash
# Copy-out residency fix validation: GPU + CPU at 1x and 8x.
# ms/step (BOA_STEP_PROFILE window) + oracle (activeLinks, fActin segs, NaN).
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_copyout_fix
export XMX=24G
WARM=300
FIX () { echo "ParameterFiles/boa10-64Seg-dyn-dense-${1}x"; }

run_one () { # scale path
  local s=$1 path=$2
  local log="$OUT/v_${s}_${path}.log"
  echo "[$(date +%H:%M)] VALIDATE $s $path START"
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
  local mss=$(grep "STEP_PROFILE\] window" "$log" | sed 's/.*(\([0-9.]*\) ms\/step).*/\1/')
  local act=$(grep -oE "activeLinks=[0-9]+" "$log" | tail -1)
  local seg=$(grep -oE "fActin segs=[0-9]+" "$log" | tail -1)
  echo "[$(date +%H:%M)] VALIDATE $s $path DONE ms/step=$mss $act $seg"
}

for s in 1 8; do
  run_one $s cpu
  run_one $s gpu
done
echo "[$(date +%H:%M)] === VALIDATE DONE ==="
