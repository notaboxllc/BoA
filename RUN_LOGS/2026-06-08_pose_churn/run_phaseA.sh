#!/usr/bin/env bash
# Phase A — measure TRUE (pre-clamp) per-step pose-delta dirty demand at
# four sizes (1×, 2×, 4×, 8×). For each size, run a short GPU sim with a
# generous BOA_POSE_DELTA_CAP override so the run survives without per-step
# plan rebuilds, and capture the [POSE_CHURN] log lines + the end-of-run
# [STATS] trueDirty summary.
set -uo pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pose_churn"
SCALING="$REPO/RUN_LOGS/2026-06-08_scaling_study"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"
START_TS=$(date +%s)

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."

# Cap large enough that no overflow fires at any size in this sweep.
# 5M > slotCap at 8× (~4.7M), so dirty count physically can't exceed it.
export BOA_POSE_DELTA_CAP=5000000
# Periodic per-step log cadence (every 20 steps).
export BOA_POSE_CHURN_LOG=10

# Short runtimes per size: enough steps to capture steady-state churn.
# At dt=1e-5, runTime=0.001 = 100 steps.
declare -A RUNTIME=( ["1x"]="0.001000" ["2x"]="0.001000" ["4x"]="0.001000" ["8x"]="0.000500" )
# Per-size budget (seconds) — 8× takes longer per step.
declare -A BUDGET=(  ["1x"]="600"      ["2x"]="600"      ["4x"]="900"      ["8x"]="1800" )

ts()     { date +%H:%M:%S; }
log()    { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }
status() { echo "$*" > "$PROGRESS"; }

run_one() {
  local label="$1"
  local src_pf="$SCALING/${label}_gpu_K1.pf"
  local pf="$OUT/${label}_churn.pf"
  local logf="$OUT/${label}_churn.log"
  local wallf="$OUT/${label}_churn.wall"
  local rt="${RUNTIME[$label]}"
  local budget="${BUDGET[$label]}"

  awk -v rt="$rt" '
    /^runTime:true:/ { print "runTime:true:" rt ";"; next }
    { print }
  ' "$src_pf" > "$pf"

  status "[$(ts)] phaseA $label cap=$BOA_POSE_DELTA_CAP runTime=$rt budget=${budget}s"
  log "=== $label cap=$BOA_POSE_DELTA_CAP runTime=$rt budget=${budget}s ==="
  cd "$REPO"
  # Heap matches scaling-study (28G for 8×); smaller sizes need less but it's harmless.
  timeout --kill-after=30s "$budget" /usr/bin/time -f "%e" -o "$wallf" \
    java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview \
    -Xmx28G -XX:-UseGCOverheadLimit -cp "$CP" \
    BoxOfActin -r -gpu -pf "$pf" -seed 1 \
    >"$logf" 2>&1
  local rc=$?
  local wall=""
  [ -f "$wallf" ] && wall=$(tail -1 "$wallf" | tr -d '\n')
  log "  rc=$rc wall=${wall}s"
  # Quick summary
  grep -E "^\[POSE_CHURN\]" "$logf" | tail -5 | tee -a "$RUNNER_LOG" >&2
  grep -E "^\[STATS\] gpuMoveThing (trueDirty|poseDelta)" "$logf" | tee -a "$RUNNER_LOG" >&2
}

log "Phase A starting"
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

for label in "$@"; do
  run_one "$label"
done

ELAPSED=$(( $(date +%s) - START_TS ))
log "=== Phase A done (elapsed ${ELAPSED}s) ==="
status "[$(ts)] phaseA done (elapsed ${ELAPSED}s)"
