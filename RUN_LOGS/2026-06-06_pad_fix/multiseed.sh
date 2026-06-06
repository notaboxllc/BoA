#!/bin/bash
# Multi-seed mini-comparison for resident vs refreshed (post-fix).
# Seeds 2, 3, 4 (we already have seed=1 in run1_resident/run2_refreshed).
set -u

cd /home/jba/Code/BoA

LOG_DIR=/home/jba/Code/BoA/RUN_LOGS/2026-06-06_pad_fix
PARAMFILE=/home/jba/Code/BoA/ParameterFiles/glidingAssay500_val

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

ts() { date +'%Y-%m-%d %H:%M:%S'; }

run_gpu() {
    local seed="$1"; shift
    local name="$1"; shift
    local logfile="$LOG_DIR/seed${seed}_${name}.log"
    local envprefix=""
    for kv in "$@"; do envprefix="$envprefix $kv"; done

    echo "[$(ts)] starting seed=$seed $name (env=$envprefix)" >> "$LOG_DIR/multiseed.log"

    local cmd="$envprefix java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
        -cp \"$TDIR/tornado-api-4.0.1-dev.jar:libs/*:.\" \
        BoxOfActin -r -gpu -seed $seed -pf $PARAMFILE"

    eval "$cmd" > "$logfile" 2>&1
    local rc=$?
    local be=$(grep -E '^\[STATS\] bindEvents='     "$logfile" | tail -1 | sed 's/.*=//')
    local mbm=$(grep -E '^\[STATS\] meanBoundMotors=' "$logfile" | tail -1 | sed 's/.*=//')
    local gv=$(grep -E '^\[STATS\] glidingVelocity='  "$logfile" | tail -1 | sed 's/.*=//')
    echo "[$(ts)] seed=$seed $name done rc=$rc be=$be mbm=$mbm gv=$gv" >> "$LOG_DIR/multiseed.log"
}

echo "==============================================================" >> "$LOG_DIR/multiseed.log"
echo "[$(ts)] multiseed runner starting (pid=$$)" >> "$LOG_DIR/multiseed.log"

for s in 2 3 4; do
    run_gpu $s resident
    run_gpu $s refreshed "BOA_PHASE45_POISON=1" "BOA_PHASE45_POISON_MODE=fixed" "BOA_PHASE45_POISON_OFFSET=0"
done

echo "[$(ts)] multiseed runner finished" >> "$LOG_DIR/multiseed.log"
