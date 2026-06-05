#!/usr/bin/env bash
# Confirming test for MOTOR_GAP_DIAGNOSIS: does swapping CPU's fastAcos
# (MyoFilLink:174,202) to the kernel's accurateAcos collapse the device-vs-CPU
# motor gap? N=8 paired device-vs-freshCPU ensemble on glidingAssay500_val,
# seeds 1..8, both arms -gpu, freshCPU = BOA_DIAG_CPU_MOTOR=1.
#
# Wraps scripts/paired_motor_gliding.sh and writes a 30 s heartbeat to
# .last_run_status.

set -u
ROOT="$HOME/Code/BoA"
cd "$ROOT"

OUT="RUN_LOGS/2026-06-04_acos_confirm"
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
            echo "acos-confirm ensemble (paired device vs freshCPU, N=8, glidingAssay500_val)"
            echo "elapsed=${elapsed}s  done_logs=${done_ct}/16  latest=${last_basename:-<none>}"
            echo "out=$OUT  pid=$$"
        } > "$STATUS_FILE"
        sleep 30
    done
}

ticker &
TICKER_PID=$!
trap "kill $TICKER_PID 2>/dev/null" EXIT

echo "[$(date +%H:%M)] acos-confirm ensemble start out=$OUT seeds=[$SEEDS]"
bash scripts/paired_motor_gliding.sh "$SEEDS" "$OUT"
rc=$?
echo "[$(date +%H:%M)] acos-confirm ensemble done rc=$rc"

{
    echo "acos-confirm ensemble DONE rc=$rc"
    echo "elapsed=$(( $(date +%s) - T0 ))s  results=$OUT/results.csv"
    echo "ran $(ls "$OUT"/*_seed*.log 2>/dev/null | wc -l)/16 logs"
} > "$STATUS_FILE"

exit $rc
