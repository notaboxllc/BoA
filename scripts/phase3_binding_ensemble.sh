#!/usr/bin/env bash
# Phase 3 ensemble gate: device-grid binding now active in both arms.
# Wraps scripts/paired_motor_gliding.sh — both arms run -gpu, both build
# the binding CSR on device (new Phase 3 path), the cpu arm additionally
# sets BOA_DIAG_CPU_MOTOR=1 to swap the F8/F9/F10 motor force pair to CPU.
# Observables: bindEvents, meanBoundMotors, glidingVelocity. Success = all
# |t| ≲ 1–2 AND per-seed paired differences scatter in sign.

set -u
ROOT="$HOME/Code/BoA"
cd "$ROOT"

OUT="RUN_LOGS/2026-06-04_phase3_ensemble"
SEEDS="1 2 3 4 5 6 7 8"
mkdir -p "$OUT"

STATUS_FILE="$ROOT/.last_run_status"
T0=$(date +%s)

ticker () {
    while true; do
        local now=$(date +%s)
        local elapsed=$((now - T0))
        local last_run=$(ls -t "$OUT"/*_seed*.log 2>/dev/null | head -1)
        local last_basename=""
        if [[ -n "$last_run" ]]; then
            last_basename=$(basename "$last_run")
        fi
        local done_ct=$(ls "$OUT"/*_seed*.log 2>/dev/null | wc -l)
        {
            echo "phase3 binding ensemble (paired device-grid vs device-grid+cpu-motor, N=8, glidingAssay500_val)"
            echo "elapsed=${elapsed}s  done_logs=${done_ct}/16  latest=${last_basename:-<none>}"
            echo "out=$OUT  pid=$$"
        } > "$STATUS_FILE"
        sleep 30
    done
}

ticker &
TICKER_PID=$!
trap "kill $TICKER_PID 2>/dev/null" EXIT

echo "[$(date +%H:%M)] phase3 ensemble start out=$OUT seeds=[$SEEDS]"
bash scripts/paired_motor_gliding.sh "$SEEDS" "$OUT"
rc=$?
echo "[$(date +%H:%M)] phase3 ensemble done rc=$rc"

{
    echo "phase3 binding ensemble DONE rc=$rc"
    echo "elapsed=$(( $(date +%s) - T0 ))s  results=$OUT/results.csv"
    echo "ran $(ls "$OUT"/*_seed*.log 2>/dev/null | wc -l)/16 logs"
} > "$STATUS_FILE"

exit $rc
