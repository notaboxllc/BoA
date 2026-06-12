#!/usr/bin/env bash
# 701-hunt re-measure: contractile weak-scaling ms/step, two-step-diff.
# Same methodology as 2026-06-11_contractile_scaling/run_contractile_scaling.sh
# but focused on 1x/8x/16x and parameterized by which binary (classpath) is used,
# so we can run GPU-after (fix) and GPU-before (stashed) into separate columns.
#
# Usage: timing.sh <tag> <classpath-dir> <do_cpu:0|1>
#   tag           = label written into output filenames (e.g. after / before)
#   classpath-dir = dir holding compiled .class files (the "." element); use the
#                   repo root for the live build, or a snapshot dir for before.
#   do_cpu        = 1 to also run the CPU column (CPU is binary-independent;
#                   only run it once).
set -u
set -o pipefail
TAG="${1:?tag}"; CLS="${2:?classpath dir}"; DO_CPU="${3:-0}"
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-11_701_hunt"
BASE_PF="$REPO/RUN_LOGS/2026-06-11_contractile_scaling/base_config"
RAW="$OUT/raw_${TAG}.tsv"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$CLS"
HEAP="16G"; DT="1.0E-4"; SEEDS=(1 2 3)

# label:boxXY:nFil:nMini:K1:K2:nseed
SIZES=(
  "1x:10.000:100:100:500:2500:3"
  "8x:28.284:800:800:300:1300:3"
  "16x:40.000:1600:1600:200:700:1"
)
ts(){ date +%H:%M:%S; }
echo -e "label\tpath\tseed\tK1\tK2\twallK1\twallK2\tms_step\texec_s\tslotPack_s\tdemandSync_s\tdemandSyncCalls\tslotCap\tslotCount\terr701\tmeanBound\tgcalls" > "$RAW"

write_pf(){ awk -v bxy="$1" -v nf="$2" -v nm="$3" -v rt="$4" '
  /^boxXDim:true:/{print "boxXDim:true:" bxy ";";next}
  /^boxYDim:true:/{print "boxYDim:true:" bxy ";";next}
  /^initialFilaments:true:/{print "initialFilaments:true:" nf ";";next}
  /^initialMyoMiniFils:true:/{print "initialMyoMiniFils:true:" nm ";";next}
  /^runTime:true:/{print "runTime:true:" rt ";";next}
  {print}' "$BASE_PF" > "$5"; }

run_one(){ # label path seed k boxXY nFil nMini K budget -> "rc|wall"
  local label="$1" path="$2" seed="$3" k="$4" boxXY="$5" nFil="$6" nMini="$7" K="$8" budget="$9"
  local rt; rt=$(awk -v k="$K" -v dt="$DT" 'BEGIN{printf "%.6f",k*dt}')
  local tag="${label}_${TAG}_${path}_s${seed}_K${k}"
  local pf="$OUT/${tag}.pf" logf="$OUT/${tag}.log" wallf="$OUT/${tag}.wall"
  write_pf "$boxXY" "$nFil" "$nMini" "$rt" "$pf"
  cd "$REPO"
  local rc
  if [ "$path" = "gpu" ]; then
    timeout --kill-after=30s "${budget}" /usr/bin/time -f "%e" -o "$wallf" \
      java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx${HEAP} \
      -XX:-UseGCOverheadLimit -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$CP" BoxOfActin -r -gpu -pf "$pf" -seed "$seed" >"$logf" 2>&1
    rc=$?
  else
    timeout --kill-after=30s "${budget}" /usr/bin/time -f "%e" -o "$wallf" \
      java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
      -cp "$CP" BoxOfActin -r -pf "$pf" -seed "$seed" >"$logf" 2>&1
    rc=$?
  fi
  local wall=""; [ -f "$wallf" ] && wall=$(tail -1 "$wallf" | tr -d '\n')
  echo "$rc|$wall"
}
xs(){ grep -oE "$2" "$1" 2>/dev/null | tail -1 | grep -oE '[0-9.]+' | tail -1; }

for spec in "${SIZES[@]}"; do
  IFS=':' read -r label boxXY nFil nMini K1 K2 nseed <<<"$spec"
  echo "[$(ts)] === $label ($TAG) box=$boxXY fil=$nFil seeds=$nseed ==="
  for ((s=0;s<nseed;s++)); do
    seed="${SEEDS[$s]}"
    if [ "$DO_CPU" = "1" ]; then
      r=$(run_one "$label" cpu "$seed" 1 "$boxXY" "$nFil" "$nMini" "$K1" 2400); c1="${r%%|*}"; w1="${r##*|}"
      r=$(run_one "$label" cpu "$seed" 2 "$boxXY" "$nFil" "$nMini" "$K2" 3600); c2="${r%%|*}"; w2="${r##*|}"
      cms=""; [ "$c1" = 0 ] && [ "$c2" = 0 ] && cms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" 'BEGIN{printf "%.2f",1000*(b-a)/(k2-k1)}')
      mb=$(xs "$OUT/${label}_${TAG}_cpu_s${seed}_K2.log" 'meanBoundMotors=[0-9.]+')
      echo -e "$label\tcpu\t$seed\t$K1\t$K2\t$w1\t$w2\t${cms:-NA}\tNA\tNA\tNA\tNA\tNA\tNA\tNA\t${mb:-NA}\tNA" >> "$RAW"
      echo "[$(ts)]   cpu s$seed ms/step=${cms:-NA}"
    fi
    r=$(run_one "$label" gpu "$seed" 1 "$boxXY" "$nFil" "$nMini" "$K1" 3600); g1="${r%%|*}"; w1="${r##*|}"
    r=$(run_one "$label" gpu "$seed" 2 "$boxXY" "$nFil" "$nMini" "$K2" 5400); g2="${r%%|*}"; w2="${r##*|}"
    gms=""; [ "$g1" = 0 ] && [ "$g2" = 0 ] && gms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" 'BEGIN{printf "%.2f",1000*(b-a)/(k2-k1)}')
    glog="$OUT/${label}_${TAG}_gpu_s${seed}_K2.log"
    ex=$(xs "$glog" 'exec=[0-9.]+s'); sp=$(xs "$glog" 'slotPack=[0-9.]+s')
    ds=$(xs "$glog" 'demandSyncPose=[0-9.]+s'); dc=$(grep -oE 'demandSyncPose=[0-9.]+s\(calls=[0-9]+' "$glog" 2>/dev/null|tail -1|grep -oE 'calls=[0-9]+'|grep -oE '[0-9]+')
    sc=$(xs "$glog" 'slotCap=[0-9]+'); sct=$(xs "$glog" 'slotCount=[0-9]+')
    e7=$(grep -c "Returned: 701" "$glog" 2>/dev/null); mb=$(xs "$glog" 'meanBoundMotors=[0-9.]+')
    gc=$(grep -oE 'gpuMoveThing total=[0-9.]+s calls=[0-9]+' "$glog" 2>/dev/null|tail -1|grep -oE 'calls=[0-9]+'|grep -oE '[0-9]+')
    echo -e "$label\tgpu\t$seed\t$K1\t$K2\t$w1\t$w2\t${gms:-NA}\t${ex:-NA}\t${sp:-NA}\t${ds:-NA}\t${dc:-NA}\t${sc:-NA}\t${sct:-NA}\t${e7:-NA}\t${mb:-NA}\t${gc:-NA}" >> "$RAW"
    echo "[$(ts)]   gpu s$seed ms/step=${gms:-NA} exec=${ex:-NA}s 701=${e7:-NA}"
  done
done
echo "[$(ts)] DONE $TAG -> $RAW"
