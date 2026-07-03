#!/bin/bash
# Pooled launcher for the capture-radius replicate sweep.
# Usage: run_batch.sh <maxConcurrent> <spec> [<spec> ...]
#   spec = "<nm>:<draw>"  e.g. 6:1  8:2
# Each run: default f-hat motor, full d1000 mat, dt 1e-5, Brownian on, CPU, census ON.
cd /home/jba/Code/BoA
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
MAXC=$1; shift
LOGDIR=RUN_LOGS/2026-07-02_colrep

launch() {
  local nm=$1 draw=$2
  local pf=ParameterFiles/glidingAssay_d1000_colTol${nm}nm
  local out=~/Code/threejs_output/colrep_${nm}nm_p${draw}
  local log=$LOGDIR/run_${nm}nm_p${draw}.log
  echo "[$(date +%H:%M)] launch ${nm}nm draw${draw} -> $log"
  BOA_STRETCH_CENSUS=1 java -Xmx4G --enable-preview -cp "$CP" \
    BoxOfActin -r -pf "$pf" -3js "$out" > "$log" 2>&1
  echo "[$(date +%H:%M)] DONE ${nm}nm draw${draw} exit=$?"
}

for spec in "$@"; do
  nm=${spec%%:*}; draw=${spec##*:}
  # throttle: wait while >= MAXC java sims running
  while [ "$(pgrep -fc '^java .*BoxOfActin -r -pf')" -ge "$MAXC" ]; do sleep 15; done
  launch "$nm" "$draw" &
  sleep 8   # stagger starts
done
wait
echo "[$(date +%H:%M)] BATCH COMPLETE"
