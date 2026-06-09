#!/usr/bin/env bash
# 16× CPU ceiling re-probe with the per-worker-RNG binary. Same setup as
# inc1 / inc0b probes — -Xmx28G on the aorus 31 GB box.
set -u
set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-09_per_worker_rng"
CLASSES="${1:-/tmp/pwrng_classes}"
LABEL="${2:-16x_cpu}"
PF="$REPO/RUN_LOGS/2026-06-08_scaling_study/${LABEL}_K1.pf"
LOGF="$OUT/ceiling_${LABEL}_pwrng.log"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*"; }
cd "$REPO"
log "ceiling probe ${LABEL} (pwrng) starting"
echo "[$(date +%H:%M)] ceiling probe ${LABEL} (pwrng) begin" >> "$REPO/.last_run_status"
java --enable-preview -Xmx28G -XX:-UseGCOverheadLimit \
     -cp "$CLASSES:$REPO:$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOGF" 2>&1
RC=$?
log "ceiling probe ${LABEL} (pwrng) rc=$RC"
echo "[$(date +%H:%M)] ceiling probe ${LABEL} (pwrng) rc=$RC" >> "$REPO/.last_run_status"
tail -30 "$LOGF"
exit $RC
