#!/usr/bin/env bash
# Perf: gridAssemble parallel vs serial. Two-step-diff ms/step (GPU-parallel,
# GPU-serial, CPU) at 1x/8x/16x + profiler sweep for per-task kernel time.
# Same weak-scaling configs / method as the 06-11 cost map.
set -u; set -o pipefail
REPO="/home/jba/Code/BoA"; WD="$REPO/RUN_LOGS/2026-06-12_gridassemble"
PROF="$WD/prof"; mkdir -p "$PROF"
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"; CP="$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$REPO"
BASE="$REPO/RUN_LOGS/2026-06-11_contractile_scaling/base_config"
STATUS="$REPO/.last_run_status"; RAW="$WD/perf_raw.tsv"
ts(){ date +%H:%M:%S; }
write_pf(){ awk -v b="$1" -v nf="$2" -v nm="$3" -v rt="$4" '
  /^boxXDim:true:/{print "boxXDim:true:" b ";";next}
  /^boxYDim:true:/{print "boxYDim:true:" b ";";next}
  /^initialFilaments:true:/{print "initialFilaments:true:" nf ";";next}
  /^initialMyoMiniFils:true:/{print "initialMyoMiniFils:true:" nm ";";next}
  /^runTime:true:/{print "runTime:true:" rt ";";next}{print}' "$BASE" > "$5"; }
DT=1.0E-4
# label:boxXY:nFil:nMini:K1:K2:nseed:budget
SIZES=("1x:10.000:100:100:500:2500:2:300" "8x:28.284:800:800:300:1300:2:400" "16x:40.000:1600:1600:200:700:1:500")

gpu_wall(){ # serialflag pf seed K budget -> wall seconds (via /usr/bin/time)
  local sg="$1" pf="$2" seed="$3" K="$4" budget="$5" wf; wf=$(mktemp)
  local rt; rt=$(awk -v k="$K" -v dt="$DT" 'BEGIN{printf "%.6f",k*dt}')
  write_pf "$BXY" "$NF" "$NM" "$rt" "$pf"
  BOA_SERIAL_GRID=$sg timeout --kill-after=20s "$budget" /usr/bin/time -f "%e" -o "$wf" \
    java "@$TH/tornado-argfile" --enable-preview -Xmx16G -XX:-UseGCOverheadLimit \
    -Dtornado.tvm.maxbytecodesize=16384 -cp "$CP" BoxOfActin -r -gpu -pf "$pf" -seed "$seed" >/dev/null 2>&1
  tail -1 "$wf"; rm -f "$wf"; }
cpu_wall(){ local pf="$1" seed="$2" K="$3" budget="$4" wf; wf=$(mktemp)
  local rt; rt=$(awk -v k="$K" -v dt="$DT" 'BEGIN{printf "%.6f",k*dt}')
  write_pf "$BXY" "$NF" "$NM" "$rt" "$pf"
  timeout --kill-after=20s "$budget" /usr/bin/time -f "%e" -o "$wf" \
    java --enable-preview -Xmx16G -XX:-UseGCOverheadLimit -cp "$CP" BoxOfActin -r -pf "$pf" -seed "$seed" >/dev/null 2>&1
  tail -1 "$wf"; rm -f "$wf"; }

echo -e "scale\tmode\tseed\tmsStep" > "$RAW"
echo "[$(ts)] PERF START" >> "$STATUS"
for spec in "${SIZES[@]}"; do
  IFS=':' read -r LAB BXY NF NM K1 K2 NSEED BUD <<<"$spec"
  echo "[$(ts)] === $LAB ===" | tee -a "$STATUS"
  for mode in gpu_par gpu_ser cpu; do
    for ((s=1;s<=NSEED;s++)); do
      pf="$WD/perf_${LAB}.pf"
      case $mode in
        gpu_par) w1=$(gpu_wall 0 "$pf" $s $K1 $BUD); w2=$(gpu_wall 0 "$pf" $s $K2 $BUD);;
        gpu_ser) w1=$(gpu_wall 1 "$pf" $s $K1 $BUD); w2=$(gpu_wall 1 "$pf" $s $K2 $BUD);;
        cpu)     w1=$(cpu_wall "$pf" $s $K1 $BUD);    w2=$(cpu_wall "$pf" $s $K2 $BUD);;
      esac
      ms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" 'BEGIN{if(a==""||b=="")print"NA";else printf"%.2f",1000*(b-a)/(k2-k1)}')
      echo -e "${LAB}\t${mode}\t${s}\t${ms}" | tee -a "$RAW"
    done
  done
done
echo "[$(ts)] two-step-diff done; profiler sweep" | tee -a "$STATUS"
# Profiler: per-task kernel time, 2 runs each, ~250 steps (runTime 0.025).
for spec in "${SIZES[@]}"; do
  IFS=':' read -r LAB BXY NF NM _ _ _ _ <<<"$spec"
  pf="$WD/prof_${LAB}.pf"; write_pf "$BXY" "$NF" "$NM" "0.025000" "$pf"
  for grid in par ser; do
    sg=0; [ "$grid" = ser ] && sg=1
    for run in 1 2; do
      jf="$PROF/${LAB}_${grid}_r${run}.json"; rm -f "$jf"
      BOA_SERIAL_GRID=$sg timeout 300 java "@$TH/tornado-argfile" --enable-preview -Xmx16G -XX:-UseGCOverheadLimit \
        -Dtornado.tvm.maxbytecodesize=16384 -Dtornado.profiler=True -Dtornado.profiler.dump.dir="$jf" \
        -cp "$CP" BoxOfActin -r -gpu -pf "$pf" -seed 1 > "$PROF/${LAB}_${grid}_r${run}.log" 2>&1
      echo "[$(ts)]   prof $LAB $grid run$run rc=$?" | tee -a "$STATUS"
    done
  done
done
echo "[$(ts)] PERF DONE" | tee -a "$STATUS"
