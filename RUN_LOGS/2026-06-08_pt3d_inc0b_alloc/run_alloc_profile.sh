#!/usr/bin/env bash
# Run a CPU run with JFR allocation profiling. Skips startup via JFR delay.
set -u
set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pt3d_inc0b_alloc"
PF="${1:-$REPO/RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/4x_cpu_K1.pf}"
LABEL="${2:-4x_cpu_after}"
DELAY="${3:-200s}"
DURATION="${4:-200s}"
CLASSES="${5:-/tmp/inc0b_classes_0b}"
JFR_FILE="$OUT/${LABEL}.jfr"
LOG="$OUT/${LABEL}.log"

ts() { date +%H:%M:%S; }
echo "[$(date +%H:%M)] alloc profile ${LABEL} begin (delay=$DELAY dur=$DURATION pf=$PF classes=$CLASSES)" >> "$REPO/.last_run_status"
cd "$REPO"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
java --enable-preview -Xmx20G \
     -XX:StartFlightRecording=delay=${DELAY},duration=${DURATION},settings=profile,filename=${JFR_FILE} \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$CLASSES" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOG" 2>&1
RC=$?
echo "[$(date +%H:%M)] alloc profile ${LABEL} rc=$RC jfr=$JFR_FILE" >> "$REPO/.last_run_status"
echo "rc=$RC"
