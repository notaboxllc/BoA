#!/bin/bash
# Step 1 behavior-neutral A/B for minifilament cohesion onto device.
#   devcoh  : -gpu default                  -> cohesion ON DEVICE (new)
#   cpucoh  : -gpu BOA_MINIFIL_COHESION_CPU=1 -> cohesion on CPU from synced pose (control)
#   oracle  : no -gpu                        -> full CPU (ground truth)
set -u
cd /home/jba/Code/BoA
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
BYTECODE="-Dtornado.tvm.maxbytecodesize=16384"   # cohesion adds graph buffers/tasks past the 4096 default
CFG="${1:-ParameterFiles/boa10-singleMiniFil}"
TAG="${2:-singleMiniFil}"
OUT=RUN_LOGS/_cohesion
VIEW=/home/jba/Code/threejs_output/coh_${TAG}
SEEDS="${3:-1}"
status(){ echo "[$(date +%H:%M:%S)] $*" >> .last_run_status; echo "[$(date +%H:%M:%S)] $*"; }

run_gpu(){ local lbl=$1 seed=$2 env=$3
  status "coh $TAG $lbl seed$seed start"
  env $env java @$TORNADOVM_HOME/tornado-argfile $BYTECODE --enable-preview -Xmx4G \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -gpu -seed $seed -pf $CFG -3js ${VIEW}_${lbl}_s${seed} \
    > $OUT/${TAG}_${lbl}_s${seed}.log 2>&1
  status "coh $TAG $lbl seed$seed rc=$?"
}
run_cpu(){ local lbl=$1 seed=$2
  status "coh $TAG $lbl seed$seed start"
  java --enable-preview -Xmx4G -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -seed $seed -pf $CFG -3js ${VIEW}_${lbl}_s${seed} \
    > $OUT/${TAG}_${lbl}_s${seed}.log 2>&1
  status "coh $TAG $lbl seed$seed rc=$?"
}
for s in $SEEDS; do
  run_gpu devcoh $s ""
  run_gpu cpucoh $s "BOA_MINIFIL_COHESION_CPU=1"
  run_cpu oracle $s
done
status "coh $TAG A/B done seeds=$SEEDS"
