#!/usr/bin/env bash
# 16× CPU ceiling probe with the inc0b transient-alloc binary.
set -u
set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pt3d_inc0b_alloc"
CLASSES="${1:-/tmp/inc0b_classes_0b}"
LABEL="${2:-16x_cpu}"
PF="$REPO/RUN_LOGS/2026-06-08_scaling_study/${LABEL}_K1.pf"
LOGF="$OUT/ceiling_${LABEL}_inc0b.log"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*"; }
cd "$REPO"
log "ceiling probe ${LABEL} (inc0b) starting (classes=$CLASSES)"
echo "[$(date +%H:%M)] ceiling probe ${LABEL} (inc0b) begin" >> "$REPO/.last_run_status"
java --enable-preview -Xmx28G -XX:-UseGCOverheadLimit \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$CLASSES" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOGF" 2>&1
RC=$?
log "ceiling probe ${LABEL} (inc0b) rc=$RC"
echo "[$(date +%H:%M)] ceiling probe ${LABEL} (inc0b) rc=$RC" >> "$REPO/.last_run_status"
tail -20 "$LOGF"
exit $RC
