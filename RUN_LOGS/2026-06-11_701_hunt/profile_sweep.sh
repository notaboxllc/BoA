#!/usr/bin/env bash
set -u; set -o pipefail
REPO="/home/jba/Code/BoA"; OUT="$REPO/RUN_LOGS/2026-06-11_701_hunt/prof"
mkdir -p "$OUT"
PF="$REPO/RUN_LOGS/2026-06-11_contractile_scaling/base_config"
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"; CP="$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$REPO"
ts(){ date +%H:%M:%S; }
# label:boxXY:fil:mini  (weak scaling). ~250 steps each (runTime 0.025).
SIZES=("1x:10.000:100:100" "4x:20.000:400:400" "8x:28.284:800:800" "16x:40.000:1600:1600")
for spec in "${SIZES[@]}"; do
  IFS=':' read -r lab bxy nf nm <<<"$spec"
  pf="$OUT/${lab}.pf"
  awk -v b="$bxy" -v nf="$nf" -v nm="$nm" '
    /^boxXDim:true:/{print "boxXDim:true:" b ";";next}
    /^boxYDim:true:/{print "boxYDim:true:" b ";";next}
    /^initialFilaments:true:/{print "initialFilaments:true:" nf ";";next}
    /^initialMyoMiniFils:true:/{print "initialMyoMiniFils:true:" nm ";";next}
    /^runTime:true:/{print "runTime:true:0.025000;";next}{print}' "$PF" > "$pf"
  for run in 1 2 3; do
    jf="$OUT/${lab}_r${run}.json"; rm -f "$jf"
    echo "[$(ts)] profiling $lab run$run"
    timeout 300 java "@$TH/tornado-argfile" --enable-preview -Xmx16G -XX:-UseGCOverheadLimit \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -Dtornado.profiler=True -Dtornado.profiler.dump.dir="$jf" \
      -cp "$CP" BoxOfActin -r -gpu -pf "$pf" -seed 1 > "$OUT/${lab}_r${run}.log" 2>&1
    echo "[$(ts)]   rc=$? json=$(du -h "$jf" 2>/dev/null | cut -f1)"
  done
done
echo "[$(ts)] PROFILE SWEEP DONE"
