#!/usr/bin/env bash
# Dense contractile COMPUTE benchmark v5 — 4x DENSITY variant of the v4 weak-scaling sweep.
#
# Difference from v4: the assumed 1x density is dialed up 4x by quadrupling particle
# counts at every scale point while holding the v4 box schedule (boxXY = 10*sqrt(scale)).
# Areal density therefore = 40x of boa10-64Seg-dyn (v4 was 10x) — the percolation
# regime per the dense-network percolation findings. Counts: NFIL = 4000*scale,
# matching minifils. Everything else (crosslink params, runtime window, profiling)
# is identical to v4 so the two sweeps are directly comparable.
#
# NOTE (this 31 GB-RAM / 12 GB-VRAM box): the 4x and 8x points (16k / 32k filaments)
# need ~30-50 GB heap and are expected to OOM here. We attempt every scale anyway and
# let the JVM throw a clean OutOfMemoryError (XMX-bounded) rather than truncate silently;
# analyze_bcd.py reports "(no data)" for any run that did not produce a profile window.
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-13_dense_v5_4xdensity/env.sh
OUT=RUN_LOGS/2026-06-13_dense_v5_4xdensity
STATUS="$OUT/.last_run_status"
: > "$STATUS"
export XMX=26G   # max safe on a 31 GB box; bounds heap so over-large runs OOME cleanly

# Crosslink params held across the sweep (from v4 Part A calibration).
GRAB=${GRAB:-0.05}; MLD=${MLD:-0.02}; KON=${KON:-400}; CONC=${CONC:-1.0}
RUNTIME=${RUNTIME:-0.065}   # 650 steps @1e-4 -> warmup 300 + window 350
WARM=300

# scale -> boxXY (v4 schedule, UNCHANGED) and filcount (v4 * 4 = 4x density).
declare -A BXY=( [0p5]=7.0711 [1]=10.0 [2]=14.1421 [4]=20.0 [8]=28.2843 )
declare -A NFIL=( [0p5]=2000 [1]=4000 [2]=8000 [4]=16000 [8]=32000 )
SCALES=${SCALES:-"0p5 1 2 4 8"}

FIX () { echo "ParameterFiles/boa10-64Seg-dyn-dense-${1}x"; }
gen_scale () { # scale
  local s=$1
  python3 RUN_LOGS/2026-06-13_dense_v5_4xdensity/gen_fixture.py $(FIX $s) \
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

echo "[$(date +%H:%M)] === BCD v5 (4x density) SWEEP START grab=$GRAB mld=$MLD kon=$KON conc=$CONC rt=$RUNTIME ===" >> "$STATUS"
for s in $SCALES; do
  gen_scale $s
  run_one $s cpu ""
  run_one $s gpu ""
done
if [ "${DO_S2:-1}" = 1 ]; then
  run_one 1 cpu "_s2"
  run_one 1 gpu "_s2"
fi
echo "[$(date +%H:%M)] === BCD v5 SWEEP DONE ===" >> "$STATUS"
echo "BCD_DONE" >> "$STATUS"
