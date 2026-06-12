#!/usr/bin/env bash
# Part E — TornadoVM kernel profiler at 1x/4x/8x (GPU path), locked crosslink config.
# Dumps per-execute profiler JSON (-Dtornado.profiler.dump.dir=<file>); analyze.py
# aggregates per-task TASK_KERNEL_TIME + COPY_IN/COPY_OUT, warmup-skipping executes.
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_dense_v4/prof
mkdir -p "$OUT"
STATUS=.last_run_status

GRAB=0.05; MLD=0.02; KON=400; CONC=1.0
RUNTIME=0.03   # 300 steps; analyzer skips first 15 executes as warmup
declare -A BXY=( [1]=10.0 [4]=20.0 [8]=28.2843 )
declare -A NFIL=( [1]=1000 [4]=4000 [8]=8000 )

echo "[$(date +%H:%M)] === PROF SWEEP START ===" >> "$STATUS"
for s in 1 4 8; do
  pf="ParameterFiles/_v4prof_${s}x"   # separate from the committable siblings (short profiler runTime)
  python3 RUN_LOGS/2026-06-12_dense_v4/gen_fixture.py "$pf" \
    boxXDim=${BXY[$s]} boxYDim=${BXY[$s]} initialFilaments=${NFIL[$s]} initialMyoMiniFils=${NFIL[$s]} \
    crossLinkGrabDist=$GRAB maxFilLinkDist=$MLD xLinkOnRate=$KON xLinkConc=$CONC runTime=$RUNTIME >/dev/null
  for run in 1 2; do
    jf="$OUT/${s}x_r${run}.json"; rm -f "$jf"
    echo "[$(date +%H:%M)] prof ${s}x run$run" >> "$STATUS"
    java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx24G \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -Dtornado.profiler=True -Dtornado.profiler.dump.dir="$jf" \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
      BoxOfActin -r -gpu -pf "$pf" > "$OUT/${s}x_r${run}.log" 2>&1
    echo "[$(date +%H:%M)]   ${s}x r$run rc=$? json=$(du -h "$jf" 2>/dev/null | cut -f1)" >> "$STATUS"
  done
done
echo "[$(date +%H:%M)] === PROF SWEEP DONE ===" >> "$STATUS"
echo "PROF_DONE" >> "$STATUS"
