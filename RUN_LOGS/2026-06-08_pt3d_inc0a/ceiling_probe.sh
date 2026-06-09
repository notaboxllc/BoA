#!/usr/bin/env bash
# 16× CPU ceiling probe with inc0a binary (uses /tmp/inc0a_v2 staged classes).
set -u
set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pt3d_inc0a"
CLASSES="${1:-/tmp/inc0a_v2}"  # default to inc0a_v2
LABEL="${2:-16x_cpu}"
# Use the scaling-study 16x_cpu_K1.pf — short runTime, exposes startup OOM if any
PF="$REPO/RUN_LOGS/2026-06-08_scaling_study/${LABEL}_K1.pf"
LOGF="$OUT/ceiling_${LABEL}_inc0a.log"
ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*"; }
cd "$REPO"
log "ceiling probe ${LABEL} starting (classes=$CLASSES)"
echo "[$(date +%H:%M)] ceiling probe ${LABEL} (inc0a) begin" >> "$REPO/.last_run_status"
java --enable-preview -Xmx28G -XX:-UseGCOverheadLimit \
     -cp "$REPO/libs/*:$CLASSES" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOGF" 2>&1
RC=$?
log "ceiling probe ${LABEL} rc=$RC"
echo "[$(date +%H:%M)] ceiling probe ${LABEL} (inc0a) rc=$RC" >> "$REPO/.last_run_status"
tail -20 "$LOGF"
exit $RC
