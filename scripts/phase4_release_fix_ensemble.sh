#!/usr/bin/env bash
# Phase 4 of the device ckRelease force-read reconciliation fix.
# N=8 paired device-vs-CPU gliding ensemble on glidingAssay500_val. Wraps
# scripts/paired_motor_gliding.sh with a heartbeat ticker that overwrites
# .last_run_status every 30 s with a 3-line status snapshot.
#
# Same seeds as the prior motor-port ensemble (1..8). Both arms run -gpu;
# the cpu arm is the freshCPU diagnostic (BOA_DIAG_CPU_MOTOR=1).
# Observables: bindEvents, meanBoundMotors, glidingVelocity.

set -u
ROOT="$HOME/Code/BoA"
cd "$ROOT"

OUT="RUN_LOGS/2026-06-04_release_lag_fix"
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
            echo "phase4 release-fix ensemble (paired device vs freshCPU, N=8, glidingAssay500_val)"
            echo "elapsed=${elapsed}s  done_logs=${done_ct}/16  latest=${last_basename:-<none>}"
            echo "out=$OUT  pid=$$"
        } > "$STATUS_FILE"
        sleep 30
    done
}

ticker &
TICKER_PID=$!
trap "kill $TICKER_PID 2>/dev/null" EXIT

echo "[$(date +%H:%M)] phase4 ensemble start out=$OUT seeds=[$SEEDS]"
bash scripts/paired_motor_gliding.sh "$SEEDS" "$OUT"
rc=$?
echo "[$(date +%H:%M)] phase4 ensemble done rc=$rc"

# Final summary into .last_run_status
{
    echo "phase4 release-fix ensemble DONE rc=$rc"
    echo "elapsed=$(( $(date +%s) - T0 ))s  results=$OUT/results.csv"
    echo "ran $(ls "$OUT"/*_seed*.log 2>/dev/null | wc -l)/16 logs"
} > "$STATUS_FILE"

exit $rc
