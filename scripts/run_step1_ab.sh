#!/bin/bash
# Step 1 behavior-neutral A/B for dimer cross-bridge force onto device.
# Arms (same seed):
#   devmotor  : -gpu, default            -> dimer F8/F9/F10 computed ON DEVICE (new)
#   cpumotor  : -gpu, BOA_DIAG_CPU_MOTOR=1 -> dimer F8/F9/F10 on CPU from synced pose (pre-change control)
#   oracle    : no -gpu                   -> full CPU
set -u
cd /home/jba/Code/BoA
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CFG=ParameterFiles/contractilityAssay_gpu_short
OUT=RUN_LOGS/_step1_ab
mkdir -p $OUT
SEEDS="${1:-1}"
status(){ echo "[$(date +%H:%M:%S)] $*" >> .last_run_status; echo "[$(date +%H:%M:%S)] $*"; }

run_gpu(){ # $1=label $2=seed $3=extraEnv
  local lbl=$1 seed=$2 env=$3
  status "step1 $lbl seed$seed start"
  env $env java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx4G \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -gpu -seed $seed -pf $CFG > $OUT/${lbl}_s${seed}.log 2>&1
  status "step1 $lbl seed$seed rc=$?"
}
run_cpu(){ # $1=label $2=seed
  local lbl=$1 seed=$2
  status "step1 $lbl seed$seed start"
  java --enable-preview -Xmx4G \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -seed $seed -pf $CFG > $OUT/${lbl}_s${seed}.log 2>&1
  status "step1 $lbl seed$seed rc=$?"
}

for s in $SEEDS; do
  run_gpu devmotor $s ""
  run_gpu cpumotor $s "BOA_DIAG_CPU_MOTOR=1"
  run_cpu oracle   $s
done
status "step1 A/B done seeds=$SEEDS"
