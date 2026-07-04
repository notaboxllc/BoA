#!/bin/bash
# BoA dt-convergence at coltol8/d1000. CPU, census ON. Single mat draw per (dt).
cd /home/jba/Code/BoA
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
LOGDIR=RUN_LOGS/2026-07-03_dtconv_boa
run() {
  local tag=$1 pf=$2
  local out=~/Code/threejs_output/dtconv_${tag}
  local log=$LOGDIR/run_${tag}.log
  echo "[$(date +%H:%M)] launch $tag pf=$pf -> $log"
  BOA_STRETCH_CENSUS=1 java -Xmx4G --enable-preview -cp "$CP" \
    BoxOfActin -r -pf "$pf" -3js "$out" > "$log" 2>&1
  echo "[$(date +%H:%M)] DONE $tag exit=$?" 
}
run "$1" "$2"
