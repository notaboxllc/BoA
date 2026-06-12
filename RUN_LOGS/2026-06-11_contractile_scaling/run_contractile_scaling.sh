#!/usr/bin/env bash
# Contractile-network weak-scaling sweep — GPU-resident vs full-CPU, ms/step.
# Weak scaling: hold myosin:actin ratio AND actin density constant; grow box
# AREA (= filament & minifilament count) by N. boxXY = 10*sqrt(N), Z held 0.5
# (quasi-2D slab preserved → volumetric density constant; matches the 06-08
# gliding methodology). filaments = 100*N, minifilaments = 100*N (1:1 held).
# Per-seed two-step-count difference removes warmup; >=NSEED seeds per point.
#
# NOTE on growth: boa10-64Seg-dyn net-grows segCt ~2.8x over 5000 steps. The
# two-step-diff window therefore measures the MEAN ms/step over [K1,K2]; CPU and
# GPU use identical K1,K2,seed so the GPU/CPU ratio is on statistically-equal
# system size (segCt/length neutral per campaign closer). meanBoundMotors differs
# (documented GPU-minifil-binding seam) — does not affect per-step *cost*.

set -u
set -o pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-11_contractile_scaling"
BASE_PF="$OUT/base_config"
RESULTS="$OUT/results.md"
RAW="$OUT/raw_rows.tsv"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"
START_TS=$(date +%s)
START_HUMAN=$(date +%H:%M:%S)

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
HEAP="16G"
DT="1.0E-4"
SEEDS=(1 2 3)

# Size axis: "label:N:boxXY:nFil:nMini:K1:K2:seeds"
# boxXY = 10*sqrt(N); nFil = nMini = 100*N. seeds = how many of SEEDS to use.
SIZES=(
  "1x:1.0:10.000:100:100:500:2500:3"
  "2x:2.0:14.142:200:200:500:2500:3"
  "4x:4.0:20.000:400:400:400:2000:3"
  "8x:8.0:28.284:800:800:300:1300:3"
  "16x:16.0:40.000:1600:1600:200:700:1"
)

ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }
status() { echo "$*" > "$PROGRESS"; }

write_pf() {
  local boxXY="$1" nFil="$2" nMini="$3" runtime="$4" outPf="$5"
  awk -v bxy="$boxXY" -v nf="$nFil" -v nm="$nMini" -v rt="$runtime" '
    /^boxXDim:true:/          { print "boxXDim:true:" bxy ";"; next }
    /^boxYDim:true:/          { print "boxYDim:true:" bxy ";"; next }
    /^initialFilaments:true:/ { print "initialFilaments:true:" nf ";"; next }
    /^initialMyoMiniFils:true:/ { print "initialMyoMiniFils:true:" nm ";"; next }
    /^runTime:true:/          { print "runTime:true:" rt ";"; next }
    { print }
  ' "$BASE_PF" > "$outPf"
}

# run_one: label path(cpu|gpu) seed k boxXY nFil nMini K budget -> echoes "rc|wall"
run_one() {
  local label="$1" path="$2" seed="$3" k="$4" boxXY="$5" nFil="$6" nMini="$7" K="$8" budget="$9"
  local runtime
  runtime=$(awk -v k="$K" -v dt="$DT" 'BEGIN { printf "%.6f", k*dt }')
  local tag="${label}_${path}_s${seed}_K${k}"
  local pf="$OUT/${tag}.pf"
  local logf="$OUT/${tag}.log"
  local wallf="$OUT/${tag}.wall"
  write_pf "$boxXY" "$nFil" "$nMini" "$runtime" "$pf"
  cd "$REPO"
  status "[$(ts)] $label $path s${seed} K${k} (box=${boxXY} fil=${nFil} mini=${nMini} K=${K})"
  log "  $path s${seed} K${k} (box=${boxXY} fil=${nFil} mini=${nMini} K=${K} rt=${runtime} budget=${budget}s)"
  local rc
  if [ "$path" = "gpu" ]; then
    timeout --kill-after=30s "${budget}" /usr/bin/time -f "%e" -o "$wallf" \
      java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview \
      -Xmx${HEAP} -XX:-UseGCOverheadLimit -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$CP" BoxOfActin -r -gpu -pf "$pf" -seed "$seed" \
      >"$logf" 2>&1
    rc=$?
  else
    timeout --kill-after=30s "${budget}" /usr/bin/time -f "%e" -o "$wallf" \
      java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
      -cp "$CP" BoxOfActin -r -pf "$pf" -seed "$seed" \
      >"$logf" 2>&1
    rc=$?
  fi
  local wall=""
  [ -f "$wallf" ] && wall=$(tail -1 "$wallf" | tr -d '\n')
  log "    rc=$rc wall=${wall}s"
  echo "$rc|$wall"
}

# Extract a numeric stat from a log: extract_stat <logfile> <regex-with-1-capture>
extract_stat() {
  grep -oE "$2" "$1" 2>/dev/null | tail -1 | grep -oE '[0-9.]+' | tail -1
}

if [ ! -s "$RESULTS" ]; then
  cat > "$RESULTS" <<'EOF'
# Contractile-network weak-scaling sweep — GPU-resident vs full-CPU ms/step

Workload: boa10-64Seg-dyn (100 actin filaments + 100 minifilaments + ACTIVE
turnover) per 1x. Weak scaling: boxXY = 10*sqrt(N), Z = 0.5 held; filaments &
minifilaments = 100*N (myosin:actin ratio + actin density held constant).
dt = 1e-4. Aorus, Java 21, TornadoVM 4.0.1-dev PTX, RTX 5070, heap -Xmx16G.
GPU adds -Dtornado.tvm.maxbytecodesize=16384.

Per-seed steady-state ms/step = 1000*(wall_K2 - wall_K1)/(K2 - K1). Reported:
mean +/- half-spread over seeds. Output OFF during timing.

| size | N | boxXY | fil | mini | heads | K1 | K2 | seeds | CPU ms/step | GPU ms/step | GPU/CPU | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
EOF
fi

[ -s "$RAW" ] || echo -e "label\tN\tpath\tseed\tK1\tK2\twallK1\twallK2\tms_step\texec_s\tslotPack_s\tjointPack_s\tunpack_s\tdemandSync_s\tdemandSyncCalls\tslotCap\tslotCount\tposeDeltaAvg\terr701\tmeanBound\tcalls" > "$RAW"

NSIZE=${#SIZES[@]}
log "Contractile scaling sweep starting: $NSIZE sizes, seeds=${SEEDS[*]}"
status "[$(ts)] contractile sweep starting: $NSIZE sizes"

(
  while true; do
    sleep 300
    [ -f "$PROGRESS" ] && echo "[progress $(ts)] elapsed=$(( $(date +%s) - START_TS ))s — $(tail -1 "$PROGRESS")" >> "$RUNNER_LOG"
  done
) &
trap 'kill $! 2>/dev/null' EXIT

for ((i=0;i<NSIZE;i++)); do
  IFS=':' read -r label N boxXY nFil nMini K1 K2 nseed <<<"${SIZES[$i]}"
  heads=$(awk -v m="$nMini" 'BEGIN{printf "%d", m*32}')
  IDX=$((i+1))
  log "=== Size $IDX/$NSIZE: $label N=$N box=${boxXY} fil=$nFil mini=$nMini heads=$heads K1=$K1 K2=$K2 seeds=$nseed (elapsed $(( $(date +%s)-START_TS ))s) ==="

  declare -a cpu_ms=() gpu_ms=()
  for ((s=0;s<nseed;s++)); do
    seed="${SEEDS[$s]}"
    # ---- CPU ----
    r=$(run_one "$label" cpu "$seed" 1 "$boxXY" "$nFil" "$nMini" "$K1" 2400); rc1="${r%%|*}"; w1="${r##*|}"
    r=$(run_one "$label" cpu "$seed" 2 "$boxXY" "$nFil" "$nMini" "$K2" 3600); rc2="${r%%|*}"; w2="${r##*|}"
    cms=""
    if [ "$rc1" = "0" ] && [ "$rc2" = "0" ]; then
      cms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" 'BEGIN{printf "%.2f",1000*(b-a)/(k2-k1)}')
      cpu_ms+=("$cms")
    fi
    mb=$(extract_stat "$OUT/${label}_cpu_s${seed}_K2.log" 'meanBoundMotors=[0-9.]+')
    echo -e "$label\t$N\tcpu\t$seed\t$K1\t$K2\t$w1\t$w2\t${cms:-NA}\tNA\tNA\tNA\tNA\tNA\tNA\tNA\tNA\tNA\tNA\t${mb:-NA}\tNA" >> "$RAW"

    # ---- GPU ----
    r=$(run_one "$label" gpu "$seed" 1 "$boxXY" "$nFil" "$nMini" "$K1" 3600); rc1="${r%%|*}"; w1="${r##*|}"
    r=$(run_one "$label" gpu "$seed" 2 "$boxXY" "$nFil" "$nMini" "$K2" 5400); rc2="${r%%|*}"; w2="${r##*|}"
    gms=""
    if [ "$rc1" = "0" ] && [ "$rc2" = "0" ]; then
      gms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" 'BEGIN{printf "%.2f",1000*(b-a)/(k2-k1)}')
      gpu_ms+=("$gms")
    fi
    glog="$OUT/${label}_gpu_s${seed}_K2.log"
    exec_s=$(extract_stat "$glog" 'exec=[0-9.]+s'); slotPack=$(extract_stat "$glog" 'slotPack=[0-9.]+s')
    jointPack=$(extract_stat "$glog" 'jointPack=[0-9.]+s'); unpack=$(extract_stat "$glog" 'unpack=[0-9.]+s')
    dsync=$(extract_stat "$glog" 'demandSyncPose=[0-9.]+s'); dcalls=$(grep -oE 'demandSyncPose=[0-9.]+s\(calls=[0-9]+' "$glog" 2>/dev/null | tail -1 | grep -oE 'calls=[0-9]+' | grep -oE '[0-9]+')
    scap=$(extract_stat "$glog" 'slotCap=[0-9]+'); scount=$(extract_stat "$glog" 'slotCount=[0-9]+')
    pda=$(extract_stat "$glog" 'poseDelta avg=[0-9.]+'); gcalls=$(grep -oE 'gpuMoveThing total=[0-9.]+s calls=[0-9]+' "$glog" 2>/dev/null | tail -1 | grep -oE 'calls=[0-9]+' | grep -oE '[0-9]+')
    e701=$(grep -c "Returned: 701" "$glog" 2>/dev/null); mb=$(extract_stat "$glog" 'meanBoundMotors=[0-9.]+')
    echo -e "$label\t$N\tgpu\t$seed\t$K1\t$K2\t$w1\t$w2\t${gms:-NA}\t${exec_s:-NA}\t${slotPack:-NA}\t${jointPack:-NA}\t${unpack:-NA}\t${dsync:-NA}\t${dcalls:-NA}\t${scap:-NA}\t${scount:-NA}\t${pda:-NA}\t${e701:-NA}\t${mb:-NA}\t${gcalls:-NA}" >> "$RAW"
  done

  # aggregate
  cmean=$(printf '%s\n' "${cpu_ms[@]}" | awk 'NF{s+=$1;n++} END{if(n)printf "%.1f",s/n; else printf "NA"}')
  chalf=$(printf '%s\n' "${cpu_ms[@]}" | awk 'NF{if(min==""||$1<min)min=$1; if($1>max)max=$1; n++} END{if(n)printf "%.1f",(max-min)/2; else printf "NA"}')
  gmean=$(printf '%s\n' "${gpu_ms[@]}" | awk 'NF{s+=$1;n++} END{if(n)printf "%.1f",s/n; else printf "NA"}')
  ghalf=$(printf '%s\n' "${gpu_ms[@]}" | awk 'NF{if(min==""||$1<min)min=$1; if($1>max)max=$1; n++} END{if(n)printf "%.1f",(max-min)/2; else printf "NA"}')
  ratio="NA"
  if [ "$cmean" != "NA" ] && [ "$gmean" != "NA" ]; then
    ratio=$(awk -v g="$gmean" -v c="$cmean" 'BEGIN{printf "%.2f",g/c}')
  fi
  scap=$(extract_stat "$OUT/${label}_gpu_s1_K2.log" 'slotCap=[0-9]+')
  e701=$(grep -c "Returned: 701" "$OUT/${label}_gpu_s1_K2.log" 2>/dev/null)
  echo "| $label | $N | $boxXY | $nFil | $nMini | $heads | $K1 | $K2 | $nseed | ${cmean} +/- ${chalf} | ${gmean} +/- ${ghalf} | ${ratio}x | slotCap=${scap:-?} 701/K2run=${e701:-?} |" >> "$RESULTS"
  log "  $label done: CPU ${cmean} GPU ${gmean} ratio ${ratio}x"
done

log "=== Contractile scaling sweep complete (elapsed $(( $(date +%s)-START_TS ))s) ==="
status "[$(ts)] contractile sweep complete (started $START_HUMAN, elapsed $(( $(date +%s)-START_TS ))s)"
