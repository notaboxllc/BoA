#!/usr/bin/env bash
# Parts B (speed) + C (host decomposition) + D (memory) for the dense v4 benchmark.
# Weak scaling across 0.5x/1x/2x/4x/8x, both CPU and GPU paths.
# Each run: BOA_STEP_PROFILE windowed (W=200, window>=300), wrapped in
# /usr/bin/time -v for peak RSS (VmHWM); GPU runs also poll nvidia-smi for peak VRAM.
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_dense_v4
STATUS=.last_run_status
export XMX=24G   # generous so Part D ceiling is VRAM/RSS, not an artificial -Xmx cap

# Crosslink params held across the sweep (from Part A calibration).
GRAB=${GRAB:-0.05}; MLD=${MLD:-0.02}; KON=${KON:-400}; CONC=${CONC:-1.0}
RUNTIME=${RUNTIME:-0.065}   # 650 steps @1e-4 -> warmup 300 + window 350
WARM=300

# scale -> boxXY filcount
declare -A BXY=( [0p5]=7.0711 [1]=10.0 [2]=14.1421 [4]=20.0 [8]=28.2843 )
declare -A NFIL=( [0p5]=500 [1]=1000 [2]=2000 [4]=4000 [8]=8000 )
SCALES=${SCALES:-"0p5 1 2 4 8"}

FIX () { echo "ParameterFiles/boa10-64Seg-dyn-dense-${1}x"; }
gen_scale () { # scale
  local s=$1
  python3 RUN_LOGS/2026-06-12_dense_v4/gen_fixture.py $(FIX $s) \
    boxXDim=${BXY[$s]} boxYDim=${BXY[$s]} initialFilaments=${NFIL[$s]} initialMyoMiniFils=${NFIL[$s]} \
    crossLinkGrabDist=$GRAB maxFilLinkDist=$MLD xLinkOnRate=$KON xLinkConc=$CONC runTime=$RUNTIME >/dev/null
}

run_one () { # scale path tag
  local s=$1 path=$2 tag=$3
  local log="$OUT/bcd_${s}_${path}${tag}.log"
  local rss="$OUT/bcd_${s}_${path}${tag}.rss"
  local vram="$OUT/bcd_${s}_${path}${tag}.vram"
  echo "[$(date +%H:%M)] BCD $s $path$tag START" >> "$STATUS"
  if [ "$path" = gpu ]; then
    # poll VRAM in background (peak used by any process; single-tenant box)
    ( peak=0; for i in $(seq 1 100000); do
        u=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits 2>/dev/null | head -1)
        [ -n "$u" ] && [ "$u" -gt "$peak" ] && peak=$u && echo "$peak" > "$vram"
        sleep 1
        [ -f "$OUT/.stop_$s$path$tag" ] && break
      done ) &
    VPID=$!
    BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM \
      /usr/bin/time -v -o "$rss" \
      java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx$XMX \
        -Dtornado.tvm.maxbytecodesize=16384 \
        -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
        BoxOfActin -r -gpu -pf $(FIX $s) > "$log" 2>&1
    local rc=$?
    touch "$OUT/.stop_$s$path$tag"; wait $VPID 2>/dev/null; rm -f "$OUT/.stop_$s$path$tag"
  else
    BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=$WARM \
      /usr/bin/time -v -o "$rss" \
      java --enable-preview -Xmx$XMX \
        -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
        BoxOfActin -r -pf $(FIX $s) > "$log" 2>&1
    local rc=$?
  fi
  local mss=$(grep "STEP_PROFILE\] window" "$log" | sed 's/.*(\([0-9.]*\) ms\/step).*/\1/')
  echo "[$(date +%H:%M)] BCD $s $path$tag rc=$rc ms/step=$mss" >> "$STATUS"
}

echo "[$(date +%H:%M)] === BCD SWEEP START grab=$GRAB mld=$MLD kon=$KON conc=$CONC rt=$RUNTIME ===" >> "$STATUS"
for s in $SCALES; do
  gen_scale $s
  run_one $s cpu ""
  run_one $s gpu ""
done
# 1x stability: a second window each path
if [ "${DO_S2:-1}" = 1 ]; then
  run_one 1 cpu "_s2"
  run_one 1 gpu "_s2"
fi
echo "[$(date +%H:%M)] === BCD SWEEP DONE ===" >> "$STATUS"
echo "BCD_DONE" >> "$STATUS"
