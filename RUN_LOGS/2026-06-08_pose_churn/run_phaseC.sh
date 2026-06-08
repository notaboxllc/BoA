#!/usr/bin/env bash
# Phase C — verify the startup-sized cap + persistent-identity Phase B fix:
#   C1: 1× and 4× regression (ms/step should match pre-fix baselines within
#       seed noise — old cap was already sufficient at those sizes, so the
#       new sizing is inert; if numbers shift it means the buffer-reuse change
#       altered something).
#   C2: 8× completes (was the SIGKILL ceiling) with flat host memory; produce
#       8× GPU ms/step via the two-step-count difference method.
#
# No BOA_POSE_CHURN_LOG (per-step printf would skew timing). Buffers and cap
# are auto-sized from config; no env-var overrides.
set -uo pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pose_churn"
SCALING="$REPO/RUN_LOGS/2026-06-08_scaling_study"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/phaseC_runner.log"
RESULTS="$OUT/phaseC_results.md"
START_TS=$(date +%s)

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."

DT="1.0E-5"
HEAP="28G"

# Per-size adaptive K1/K2 — matches scaling-study choices.
declare -A K1=( ["1x"]="500" ["4x"]="400" ["8x"]="300" )
declare -A K2=( ["1x"]="2000" ["4x"]="1600" ["8x"]="1200" )
declare -A BUDGET=( ["1x"]="900" ["4x"]="2400" ["8x"]="3000" )

ts()     { date +%H:%M:%S; }
log()    { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }
status() { echo "$*" > "$PROGRESS"; }

write_pf_runtime() {
  local src="$1" outpf="$2" runtime="$3"
  awk -v rt="$runtime" '
    /^runTime:true:/ { print "runTime:true:" rt ";"; next }
    { print }
  ' "$src" > "$outpf"
}

run_one() {
  local label="$1" k="$2"
  local src_pf="$SCALING/${label}_gpu_K1.pf"
  local runtime
  runtime=$(awk -v kk="$k" -v dt="$DT" 'BEGIN { printf "%.6f", kk*dt }')
  local tag="${label}_phaseC_K${k}"
  local pf="$OUT/${tag}.pf"
  local logf="$OUT/${tag}.log"
  local wallf="$OUT/${tag}.wall"
  local budget="${BUDGET[$label]}"
  write_pf_runtime "$src_pf" "$pf" "$runtime"
  status "[$(ts)] phaseC $label K=$k runTime=$runtime budget=${budget}s"
  log "  running $label K=$k (budget ${budget}s)..."
  cd "$REPO"
  timeout --kill-after=30s "$budget" /usr/bin/time -f "%e" -o "$wallf" \
    java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview \
    -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp "$CP" \
    BoxOfActin -r -gpu -pf "$pf" -seed 1 \
    >"$logf" 2>&1
  local rc=$?
  local wall=""
  [ -f "$wallf" ] && wall=$(tail -1 "$wallf" | tr -d '\n')
  log "    rc=$rc wall=${wall}s"
  # Summary
  grep -E "^\[POSE_CHURN\] sized|^\[STATS\] gpuMoveThing (poseDelta|trueDirty|demandSyncPose)|^\[STATS\] (bindEvents|glidingVelocity)" "$logf" | tee -a "$RUNNER_LOG" >&2
  echo "$rc|$wall"
}

if [ ! -s "$RESULTS" ]; then
  cat > "$RESULTS" <<EOF
# Phase C — Phase B cap-sizing + persistent-buffer fix verification

Code: Phase B applied — cap sized from initialFilaments (4× safety),
delta buffers + slotCap-sized FloatArrays use ensureFloatArray /
ensureIntArray to preserve identity across plan rebuilds. No
BOA_POSE_DELTA_CAP or BOA_POSE_CHURN_LOG env vars.

Method: two-step-count difference — \`ms/step = 1000·(wall_K2 − wall_K1)/(K2 − K1)\`.

Aorus, Java 21, TornadoVM 4.0.1-dev (PTX backend), RTX 5070 (12 GB),
31 GB host RAM, heap \`-Xmx28G -XX:-UseGCOverheadLimit\`.

| size | K1 | K2 | K1 wall s | K2 wall s | GPU ms/step | scaling-study baseline ms/step | Δ | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|
EOF
fi

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

declare -A BASELINE=( ["1x"]="89.71" ["4x"]="343.67" ["8x"]="(was ceiling)" )

log "Phase C starting"

for label in "$@"; do
  log "=== $label ==="
  k1="${K1[$label]}" ; k2="${K2[$label]}"
  res1=$(run_one "$label" "$k1")
  rc1="${res1%%|*}" ; w1="${res1##*|}"
  if [ "$rc1" != "0" ]; then
    log "  K1 failed (rc=$rc1) — skipping K2 for $label"
    echo "| $label | $k1 | $k2 | $w1 | — | — | ${BASELINE[$label]} | — | K1 rc=$rc1 |" >> "$RESULTS"
    continue
  fi
  res2=$(run_one "$label" "$k2")
  rc2="${res2%%|*}" ; w2="${res2##*|}"
  if [ "$rc2" != "0" ]; then
    log "  K2 failed (rc=$rc2)"
    echo "| $label | $k1 | $k2 | $w1 | $w2 | — | ${BASELINE[$label]} | — | K2 rc=$rc2 |" >> "$RESULTS"
    continue
  fi
  ms=$(awk -v a="$w1" -v b="$w2" -v k1="$k1" -v k2="$k2" \
    'BEGIN { printf "%.2f", 1000*(b-a)/(k2-k1) }')
  delta=""
  base="${BASELINE[$label]}"
  if [[ "$base" =~ ^[0-9.]+$ ]]; then
    delta=$(awk -v m="$ms" -v b="$base" 'BEGIN { printf "%+.2f", m-b }')
  else
    delta="(was ceiling)"
  fi
  echo "| $label | $k1 | $k2 | $w1 | $w2 | $ms | $base | $delta | ok |" >> "$RESULTS"
  log "  ms/step = $ms  (baseline ${base}, Δ ${delta})"
done

ELAPSED=$(( $(date +%s) - START_TS ))
log "=== Phase C done (elapsed ${ELAPSED}s) ==="
status "[$(ts)] phaseC done (elapsed ${ELAPSED}s)"
