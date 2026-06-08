#!/usr/bin/env bash
# Scaling study — CPU vs GPU steady-state ms/step at increasing system size.
# Holds density constant; scales box area (= motor count) and filament count
# together by N. Per-size adaptive K1/K2. Writes incrementally so a crash
# never loses completed rows.

set -u
set -o pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_scaling_study"
BASE_PF="$OUT/base_config"
RESULTS="$OUT/results.md"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"
START_TS=$(date +%s)
START_HUMAN=$(date +%H:%M:%S)

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."

DT="1.0E-5"
HEAP="28G"

# CPU wall budget per single run (K1 or K2). If a CPU K1 or K2 exceeds
# this, we declare CPU impractical at that size and skip remaining CPU rows.
CPU_WALL_BUDGET_SECS=2400        # 40 min per single run

# Size axis. Format: "label:N:boxXY:nFil:K1:K2"
# boxXY = 14 * sqrt(N) (round to 3 decimals); nFil = 400 * N
# K1/K2 shrink for larger sizes to keep each run bounded.
SIZES=(
  "16x:16.0:56.000:6400:200:700"
  "32x:32.0:79.196:12800:100:400"
)

ts() { date +%H:%M:%S; }
# log writes to terminal (stderr) and to RUNNER_LOG. Using stderr keeps log
# noise out of $(...) capture of inner stdout (run_one returns rc|wall on stdout).
log() { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }
status() { echo "$*" > "$PROGRESS"; }

write_pf_variant() {
  local boxXY="$1" nFil="$2" runtime="$3" outPf="$4"
  awk -v bxy="$boxXY" -v nf="$nFil" -v rt="$runtime" '
    /^boxXDim:true:/        { print "boxXDim:true:" bxy ";"; next }
    /^boxYDim:true:/        { print "boxYDim:true:" bxy ";"; next }
    /^initialFilaments:true:/ { print "initialFilaments:true:" nf ";"; next }
    /^runTime:true:/        { print "runTime:true:" rt ";"; next }
    { print }
  ' "$BASE_PF" > "$outPf"
}

cpu_impractical=false
gpu_oom=false

run_one() {
  # args: label path(cpu|gpu) k boxXY nFil K_steps wall_budget_sec
  local label="$1" path="$2" k="$3" boxXY="$4" nFil="$5" K="$6" budget="$7"
  local runtime
  runtime=$(awk -v k="$K" -v dt="$DT" 'BEGIN { printf "%.6f", k*dt }')
  local tag="${label}_${path}_K${k}"
  local pf="$OUT/${tag}.pf"
  local logf="$OUT/${tag}.log"
  local wallf="$OUT/${tag}.wall"
  write_pf_variant "$boxXY" "$nFil" "$runtime" "$pf"
  cd "$REPO"
  status "[$(ts)] $label $path K${k} (boxXY=${boxXY} fil=${nFil} K=${K}, budget=${budget}s)"
  log "  running $path K${k} (boxXY=${boxXY} fil=${nFil} K=${K} runtime=${runtime}, budget=${budget}s)..."
  local rc
  if [ "$path" = "gpu" ]; then
    timeout --kill-after=30s "${budget}" /usr/bin/time -f "%e" -o "$wallf" \
      java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview \
      -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp "$CP" \
      BoxOfActin -r -gpu -pf "$pf" -seed 1 \
      >"$logf" 2>&1
    rc=$?
  else
    timeout --kill-after=30s "${budget}" /usr/bin/time -f "%e" -o "$wallf" \
      java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp "$CP" \
      BoxOfActin -r -pf "$pf" -seed 1 \
      >"$logf" 2>&1
    rc=$?
  fi
  local wall=""
  [ -f "$wallf" ] && wall=$(tail -1 "$wallf" | tr -d '\n')
  log "    rc=$rc wall=${wall}s"
  echo "$rc|$wall"
}

# Initialize results file if not present or empty
if [ ! -s "$RESULTS" ]; then
  cat > "$RESULTS" <<'EOF'
# Scaling study — CPU + GPU steady-state ms/step vs simulation size

Current `main` HEAD only. Pure gliding (no biochem). Aorus, Java 21,
TornadoVM 4.0.1-dev (PTX backend), RTX 5070 (12 GB), 31 GB RAM, heap
`-Xmx24G -XX:-UseGCOverheadLimit`.

Scaling: hold motor density at 500/µm² and filament density at ~2/µm²;
scale **box area** (motors) and **filament count** together by N.
`boxXY = 14·√N`, `initialFilaments = 400·N`, motor count ≈ 500·boxXY²
≈ 98000·N. Base ratio ≈ 245 motors/filament preserved.

Method: two-step-count difference — `ms/step = 1000·(wall_K2 − wall_K1)/(K2 − K1)`.
K1 just past warmup, K2 gives a stable window; both shrink at larger sizes
to keep each single run bounded.

Static cap raises (committed with this study, current code):
- `Myosin.theMyosins[]`   500K → 8M
- `MyoMotor.theMotors[]`  500K → 8M
- `Thing.theThings[]`     2M   → 32M

| size | N | boxXY (µm) | motors | fil | K1 | K2 | CPU K1 s | CPU K2 s | CPU ms/step | GPU K1 s | GPU K2 s | GPU ms/step | GPU÷CPU | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
EOF
fi

NSIZE=${#SIZES[@]}
log "Scaling sweep starting: $NSIZE sizes"
status "[$(ts)] scaling sweep starting: $NSIZE sizes"

# Sidecar: emit [progress] line every 5 min to RUNNER_LOG (status file is
# overwritten per-run so we don't append there).
(
  while true; do
    sleep 300
    if [ -f "$PROGRESS" ]; then
      LINE=$(tail -1 "$PROGRESS" 2>/dev/null)
      ELAPSED=$(( $(date +%s) - START_TS ))
      echo "[progress $(ts)] elapsed=${ELAPSED}s — $LINE" >> "$RUNNER_LOG"
    fi
  done
) &
PROGRESS_PID=$!
trap 'kill $PROGRESS_PID 2>/dev/null' EXIT

for ((i=0;i<NSIZE;i++)); do
  entry="${SIZES[$i]}"
  IFS=':' read -r label N boxXY nFil K1 K2 <<<"$entry"
  IDX=$((i+1))
  # estimated motors = floor(500 * boxXY * boxXY)
  motors=$(awk -v b="$boxXY" 'BEGIN { printf "%d", 500.0*b*b }')

  ELAPSED=$(( $(date +%s) - START_TS ))
  log "=== Size $IDX/$NSIZE: $label  N=$N boxXY=$boxXY motors=$motors fil=$nFil K1=$K1 K2=$K2 (elapsed ${ELAPSED}s) ==="
  status "[$(ts)] size $IDX/$NSIZE $label boxXY=$boxXY motors=$motors fil=$nFil"

  cpu_K1_wall="" cpu_K2_wall="" gpu_K1_wall="" gpu_K2_wall=""
  cpu_ss_ms="" gpu_ss_ms="" ratio="" notes=""

  # -------- CPU path --------
  if [ "$cpu_impractical" = "true" ]; then
    notes="${notes} CPU skipped (impractical beyond previous size);"
  else
    # CPU K1
    result=$(run_one "$label" cpu 1 "$boxXY" "$nFil" "$K1" "$CPU_WALL_BUDGET_SECS")
    rc1="${result%%|*}"; w1="${result##*|}"
    if [ "$rc1" = "124" ] || [ "$rc1" = "137" ]; then
      notes="${notes} CPU K1 timeout >${CPU_WALL_BUDGET_SECS}s (impractical);"
      cpu_impractical=true
    elif [ "$rc1" = "0" ]; then
      cpu_K1_wall="$w1"
      # CPU K2 — give 4x the K1 wall as budget, but not less than CPU_WALL_BUDGET_SECS
      k2_budget=$(awk -v w="$w1" -v k1="$K1" -v k2="$K2" -v floor="$CPU_WALL_BUDGET_SECS" \
        'BEGIN { est = 1.6 * w * k2 / k1; if (est < floor) est = floor; if (est > 7200) est = 7200; printf "%d", est }')
      result=$(run_one "$label" cpu 2 "$boxXY" "$nFil" "$K2" "$k2_budget")
      rc2="${result%%|*}"; w2="${result##*|}"
      if [ "$rc2" = "124" ] || [ "$rc2" = "137" ]; then
        notes="${notes} CPU K2 timeout >${k2_budget}s (impractical);"
        cpu_impractical=true
      elif [ "$rc2" = "0" ]; then
        cpu_K2_wall="$w2"
        cpu_ss_ms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" \
          'BEGIN { printf "%.2f", 1000*(b-a)/(k2-k1) }')
      else
        # check OOM
        if grep -q "OutOfMemoryError" "$OUT/${label}_cpu_K2.log" 2>/dev/null; then
          notes="${notes} CPU K2 OOM (rc=$rc2);"
          cpu_impractical=true
        else
          notes="${notes} CPU K2 rc=$rc2;"
        fi
      fi
    else
      if grep -q "OutOfMemoryError" "$OUT/${label}_cpu_K1.log" 2>/dev/null; then
        notes="${notes} CPU K1 OOM (rc=$rc1);"
        cpu_impractical=true
      else
        notes="${notes} CPU K1 rc=$rc1;"
      fi
    fi
  fi

  # -------- GPU path --------
  if [ "$gpu_oom" = "true" ]; then
    notes="${notes} GPU skipped (OOM at previous size);"
  else
    GPU_BUDGET=3000
    result=$(run_one "$label" gpu 1 "$boxXY" "$nFil" "$K1" "$GPU_BUDGET")
    rc1="${result%%|*}"; w1="${result##*|}"
    if [ "$rc1" = "0" ]; then
      gpu_K1_wall="$w1"
      k2_budget=$(awk -v w="$w1" -v k1="$K1" -v k2="$K2" \
        'BEGIN { est = 1.6 * w * k2 / k1; if (est < 600) est = 600; if (est > 6000) est = 6000; printf "%d", est }')
      result=$(run_one "$label" gpu 2 "$boxXY" "$nFil" "$K2" "$k2_budget")
      rc2="${result%%|*}"; w2="${result##*|}"
      if [ "$rc2" = "0" ]; then
        gpu_K2_wall="$w2"
        gpu_ss_ms=$(awk -v a="$w1" -v b="$w2" -v k1="$K1" -v k2="$K2" \
          'BEGIN { printf "%.2f", 1000*(b-a)/(k2-k1) }')
      elif grep -q -i "out of memory\|OutOfMemoryError\|CL_OUT_OF\|cuOOM\|CUDA_ERROR_OUT_OF_MEMORY" "$OUT/${label}_gpu_K2.log" 2>/dev/null; then
        notes="${notes} GPU K2 OOM (VRAM ceiling);"
        gpu_oom=true
      else
        notes="${notes} GPU K2 rc=$rc2;"
      fi
    elif grep -q -i "out of memory\|OutOfMemoryError\|CL_OUT_OF\|cuOOM\|CUDA_ERROR_OUT_OF_MEMORY" "$OUT/${label}_gpu_K1.log" 2>/dev/null; then
      notes="${notes} GPU K1 OOM (VRAM ceiling);"
      gpu_oom=true
    else
      notes="${notes} GPU K1 rc=$rc1;"
    fi
  fi

  if [ -n "$cpu_ss_ms" ] && [ -n "$gpu_ss_ms" ]; then
    ratio=$(awk -v a="$gpu_ss_ms" -v b="$cpu_ss_ms" 'BEGIN { printf "%.2fx", a/b }')
  fi

  notes_disp="${notes#" "}"
  [ -z "$notes_disp" ] && notes_disp="ok"

  d() { if [ -z "$1" ]; then echo "—"; else echo "$1"; fi; }
  echo "| $label | $N | $boxXY | $motors | $nFil | $K1 | $K2 | $(d "$cpu_K1_wall") | $(d "$cpu_K2_wall") | $(d "$cpu_ss_ms") | $(d "$gpu_K1_wall") | $(d "$gpu_K2_wall") | $(d "$gpu_ss_ms") | $(d "$ratio") | $notes_disp |" >> "$RESULTS"

  ELAPSED=$(( $(date +%s) - START_TS ))
  if [ "$IDX" -gt 0 ]; then
    REMAINING=$(( (ELAPSED * (NSIZE - IDX)) / IDX ))
    log "  row done. elapsed=${ELAPSED}s eta=${REMAINING}s remaining"
  fi

  # Early exit if both paths failed
  if [ "$cpu_impractical" = "true" ] && [ "$gpu_oom" = "true" ]; then
    log "Both CPU and GPU exhausted — stopping sweep"
    status "[$(ts)] sweep complete (both ceilings hit)"
    break
  fi
done

ELAPSED=$(( $(date +%s) - START_TS ))
log "=== Scaling sweep complete (elapsed ${ELAPSED}s) ==="
status "[$(ts)] scaling sweep complete (started $START_HUMAN, elapsed ${ELAPSED}s)"
