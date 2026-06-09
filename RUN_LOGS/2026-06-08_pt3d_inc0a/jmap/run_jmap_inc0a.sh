#!/usr/bin/env bash
# 4× CPU jmap probe for inc 0a binary (uses /tmp/inc0a_run staged classes).
set -u
set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pt3d_inc0a/jmap"
LABEL="$1"
CLASSES="$2"   # e.g. /tmp/inc0a_run (= staged inc0a classes) or $REPO (= main classes)
TAG="${LABEL}_cpu_jmap_inc0a_$(basename "$CLASSES")"
SRC_PF="$REPO/RUN_LOGS/2026-06-08_profiling_scoping/${LABEL}_cpu_jmap.pf"
PF="$OUT/${TAG}.pf"
LOGF="$OUT/${TAG}.log"
HISTOF="$OUT/${TAG}_histo.txt"
case "$LABEL" in
  4x) JMAP_AFTER=1050 ;;
  8x) JMAP_AFTER=2050 ;;
  *) echo "unknown label: $LABEL" >&2; exit 1 ;;
esac
cp "$SRC_PF" "$PF"
ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*"; }
cd "$REPO"
log "jmap run ${TAG} starting"
java --enable-preview -Xmx28G -XX:-UseGCOverheadLimit \
     -cp "$REPO/libs/*:$CLASSES" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOGF" 2>&1 &
JVM_PID=$!
log "  JVM pid=$JVM_PID"
sleep "$JMAP_AFTER"
if ! kill -0 "$JVM_PID" 2>/dev/null; then
    log "  JVM exited before jmap window"
    exit 1
fi
log "  capturing jmap -histo:live on pid=$JVM_PID..."
jmap -histo:live "$JVM_PID" > "$HISTOF" 2>&1 || log "  jmap failed"
log "  killing JVM pid=$JVM_PID"
kill "$JVM_PID" 2>/dev/null
sleep 3
kill -9 "$JVM_PID" 2>/dev/null
wait "$JVM_PID" 2>/dev/null
log "${TAG} jmap done"
