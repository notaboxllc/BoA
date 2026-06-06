#!/bin/bash
# Re-baseline after IC pad fix (1.5x stdFilSegLength).
# 3 GPU runs (resident/refreshed/poison) + 1 CPU sanity run, no frame dump.
set -u

cd /home/jba/Code/BoA

LOG_DIR=/home/jba/Code/BoA/RUN_LOGS/2026-06-06_pad_fix
PARAMFILE=/home/jba/Code/BoA/ParameterFiles/glidingAssay500_val

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"

ts() { date +'%Y-%m-%d %H:%M:%S'; }

run_gpu() {
    local name="$1"; shift
    local logfile="$LOG_DIR/${name}.log"
    local envprefix=""
    for kv in "$@"; do envprefix="$envprefix $kv"; done

    echo "[$(ts)] starting GPU $name (env=$envprefix)" >> "$LOG_DIR/runner.log"

    local cmd="$envprefix java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
        -cp \"$TDIR/tornado-api-4.0.1-dev.jar:libs/*:.\" \
        BoxOfActin -r -gpu -seed 1 -pf $PARAMFILE"

    eval "$cmd" > "$logfile" 2>&1
    local rc=$?
    local be=$(grep -E '^\[STATS\] bindEvents='     "$logfile" | tail -1 | sed 's/.*=//')
    local mbm=$(grep -E '^\[STATS\] meanBoundMotors=' "$logfile" | tail -1 | sed 's/.*=//')
    local gv=$(grep -E '^\[STATS\] glidingVelocity='  "$logfile" | tail -1 | sed 's/.*=//')
    echo "[$(ts)] $name done rc=$rc be=$be mbm=$mbm gv=$gv" >> "$LOG_DIR/runner.log"
}

run_cpu() {
    local name="$1"
    local logfile="$LOG_DIR/${name}.log"
    echo "[$(ts)] starting CPU $name" >> "$LOG_DIR/runner.log"
    java --enable-preview -Xmx800M -cp "libs/*:." \
        BoxOfActin -r -seed 1 -pf $PARAMFILE > "$logfile" 2>&1
    local rc=$?
    local be=$(grep -E '^\[STATS\] bindEvents='     "$logfile" | tail -1 | sed 's/.*=//')
    local mbm=$(grep -E '^\[STATS\] meanBoundMotors=' "$logfile" | tail -1 | sed 's/.*=//')
    local gv=$(grep -E '^\[STATS\] glidingVelocity='  "$logfile" | tail -1 | sed 's/.*=//')
    echo "[$(ts)] $name done rc=$rc be=$be mbm=$mbm gv=$gv" >> "$LOG_DIR/runner.log"
}

mkdir -p "$LOG_DIR"
echo "==============================================================" >> "$LOG_DIR/runner.log"
echo "[$(ts)] rebaseline runner starting (pid=$$)" >> "$LOG_DIR/runner.log"

run_gpu run1_resident
run_gpu run2_refreshed "BOA_PHASE45_POISON=1" "BOA_PHASE45_POISON_MODE=fixed" "BOA_PHASE45_POISON_OFFSET=0"
run_gpu run3_poison    "BOA_PHASE45_POISON=1" "BOA_PHASE45_POISON_MODE=accum" "BOA_PHASE45_POISON_OFFSET=1.0"
run_cpu run4_cpu_sanity

echo "[$(ts)] rebaseline runner finished" >> "$LOG_DIR/runner.log"
