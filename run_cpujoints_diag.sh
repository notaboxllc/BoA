#!/usr/bin/env bash
# DIAG_CPU_JOINTS 10-seed ensemble runner.
# Output: per-seed log under RUN_LOGS/2026-05-31_cpujoints_diag_seed{1..10}.log
# Progress: /home/jba/Code/BoA/.last_run_status

set -u
cd /home/jba/Code/BoA

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

LOGDIR=RUN_LOGS
mkdir -p "$LOGDIR"
STATUS=.last_run_status
SUMMARY="$LOGDIR/2026-05-31_cpujoints_diag_summary.txt"

> "$SUMMARY"
echo "[$(date +%H:%M)] ensemble start (DIAG_CPU_JOINTS GPU)" > "$STATUS"

for seed in 1 2 3 4 5 6 7 8 9 10; do
    LOG="$LOGDIR/2026-05-31_cpujoints_diag_seed${seed}.log"
    echo "[$(date +%H:%M)] seed $seed start" >> "$STATUS"
    java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
         BoxOfActin -r -gpu -pf ParameterFiles/glidingAssay500_val -seed "$seed" \
         > "$LOG" 2>&1
    rc=$?
    BE=$(grep '\[STATS\] bindEvents=' "$LOG" | tail -1 | sed 's/.*bindEvents=//')
    MB=$(grep '\[STATS\] meanBoundMotors=' "$LOG" | tail -1 | sed 's/.*meanBoundMotors=//')
    GV=$(grep '\[STATS\] glidingVelocity=' "$LOG" | tail -1 | sed 's/.*glidingVelocity=//')
    echo "seed=$seed rc=$rc bindEvents=$BE meanBoundMotors=$MB glidingVelocity=$GV" | tee -a "$SUMMARY"
    echo "[$(date +%H:%M)] seed $seed done (be=$BE mbm=$MB gv=$GV)" >> "$STATUS"
done

echo "[$(date +%H:%M)] ensemble complete" >> "$STATUS"
echo "DONE" >> "$STATUS"
