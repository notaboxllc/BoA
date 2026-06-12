#!/usr/bin/env bash
# A1 Phase 2 validation:
#   (1) skip-invariant verifier (BOA_DELTASET_VERIFY) on dense-1x/8x + turnoverstress
#   (2) A/B oracle: incremental vs BOA_FULL_RECOMPUTE at 1x/8x (segs/links/fireCt/overflow)
#   (3) re-measure recompute ms/step at 1x/8x (incremental vs full)
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_recompute_a1
export XMX=24G
WARM=300
SEED=12345
GPU () { # extra-env... -- passed as leading VAR=VAL ; rest are -pf args handled by caller
  :
}
gpu_run() { # $1=log ; rest = extra java/env handled inline
  echo placeholder
}

run() { # label  envprefix  paramfile
  local label=$1 envp=$2 pf=$3
  local log="$OUT/${label}.log"
  echo "[$(date +%H:%M)] $label START"
  env $envp BOA_RNG_SEED=$SEED \
    java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx$XMX \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      BoxOfActin -r -gpu -pf $pf > "$log" 2>&1
  echo "[$(date +%H:%M)] $label DONE -> $log"
}

PF1=ParameterFiles/boa10-64Seg-dyn-dense-1x
PF8=ParameterFiles/boa10-64Seg-dyn-dense-8x
PFS=ParameterFiles/boa10-64Seg-dyn-dense-turnoverstress

# (1) skip-invariant verify (turnover-stress oracle)
run verify_1x        "BOA_DELTASET_VERIFY=1"               $PF1
run verify_8x        "BOA_DELTASET_VERIFY=1"               $PF8
run verify_stress    "BOA_DELTASET_VERIFY=1"               $PFS

# (2)+(3) A/B oracle + re-measure: incremental vs full, with profiler window
run inc_1x   "BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM"                       $PF1
run full_1x  "BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM BOA_FULL_RECOMPUTE=1"  $PF1
run inc_8x   "BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM"                       $PF8
run full_8x  "BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM BOA_FULL_RECOMPUTE=1"  $PF8

echo "[$(date +%H:%M)] === A1 VALIDATE DONE ==="
