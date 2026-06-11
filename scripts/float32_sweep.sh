#!/usr/bin/env bash
# Part-1 float32 stability sweep for the minifilament cohesion BODY reaction.
# Maps the stiff-stability vs float32 question by varying ONLY (N, fracMove, dt)
# and the application scheme/precision:
#   mode=lag   : -gpu default                 -> float32, 1-step device lag (production)
#   mode=sync  : -gpu BOA_COH_SYNC=1          -> float32, SYNCHRONOUS SET (no lag)
#   mode=cpu   : no -gpu                       -> float64, synchronous SET (oracle)
# Metric: NaN/crazy from log (divergence) + analyze_minifil.py (span / body wander / jitter).
# Usage: float32_sweep.sh <tag> <mode> <N> <fracMove> <dt> [seed] [runTime]
set -u
cd "$HOME/Code/BoA"
TAG="$1"; MODE="$2"; N="$3"; FM="$4"; DT="$5"; SEED="${6:-1}"; RT="${7:-0.03}"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
BYTECODE="-Dtornado.tvm.maxbytecodesize=16384"
OUT=RUN_LOGS/_f32
mkdir -p "$OUT"
VIEW=/home/jba/Code/threejs_output/f32_${TAG}
LBL="${TAG}_${MODE}_N${N}_fm${FM}_dt${DT}_s${SEED}"

# Build override config: base singleMiniFil + appended sweep overrides (last-wins loader).
CFG="$OUT/cfg_${LBL}.txt"
cp ParameterFiles/boa10-singleMiniFil "$CFG"
{
  echo "numMyoDimersEachEndOfMiniFil:true:${N};"
  echo "myoMiniFilFracMove:true:${FM};"
  echo "deltaT:true:${DT};"
  echo "runTime:true:${RT};"
} >> "$CFG"

ENV=""
GPUFLAG="-gpu"
case "$MODE" in
  lag)  ENV="" ;;
  sync) ENV="BOA_COH_SYNC=1" ;;
  cpu)  GPUFLAG="" ;;
  *) echo "bad mode $MODE"; exit 1 ;;
esac

LOG="$OUT/${LBL}.log"
rm -rf "${VIEW}_${LBL}" "${VIEW}_${LBL}".0*
env $ENV timeout 180 java @$TORNADOVM_HOME/tornado-argfile $BYTECODE --enable-preview -Xmx2G \
   -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
   BoxOfActin -r $GPUFLAG -seed "$SEED" -pf "$CFG" -3js "${VIEW}_${LBL}" > "$LOG" 2>&1 || true

A=$(ls -d "${VIEW}_${LBL}"* 2>/dev/null | grep -vE '\.genlog$' | head -1)
CRAZY=$(grep -ciE 'crazy|exception|NaN|blow' "$LOG")
FR=$(ls "$A"/frame_*.json 2>/dev/null | wc -l)
METRIC=$(python3 RUN_LOGS/_cohesion/analyze_minifil.py "$A" 2>/dev/null | sed "s|.*frame_||" )
printf "%-44s crazy=%-4s frames=%-4s %s\n" "$LBL" "$CRAZY" "$FR" "$METRIC" | tee -a "$OUT/SWEEP_RESULTS.txt"
