#!/usr/bin/env bash
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_a3_diag
export XMX=24G
WARM=300
FIX () { echo "ParameterFiles/boa10-64Seg-dyn-dense-${1}x"; }
run_gpu () { local s=$1; local log="$OUT/diag_${s}_gpu.log"
  echo "[$(date +%H:%M)] A3DIAG $s gpu START"
  BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM \
    java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx$XMX \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      BoxOfActin -r -gpu -pf $(FIX $s) > "$log" 2>&1
  echo "[$(date +%H:%M)] A3DIAG $s gpu DONE rc=$?"
}
run_gpu 1
run_gpu 8
echo "[$(date +%H:%M)] === A3DIAG DONE ==="
