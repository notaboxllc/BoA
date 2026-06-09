#!/usr/bin/env bash
# jmap-only runner — launches a CPU sim at the per-worker-RNG branch, waits
# past step ~2000, captures jmap -histo:live, then kills the run.
#
# Usage: run_jmap.sh <label> <classes-dir>   (label = 4x | 16x)

set -u
set -o pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-09_per_worker_rng"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"

LABEL="$1"
CLASSES="$2"
TAG="${LABEL}_cpu_jmap"
PF="$OUT/${TAG}.pf"
LOGF="$OUT/${TAG}.log"
HISTOF="$OUT/${LABEL}_cpu_jmap_histo.txt"

case "$LABEL" in
  4x)  K=2100; JMAP_AFTER=1050; HEAP="28G" ;;
  16x) K=2100; JMAP_AFTER=2100; HEAP="28G" ;;
  *) echo "unknown label: $LABEL" >&2; exit 1 ;;
esac

DT="1.0E-5"
SRC_PF="$REPO/RUN_LOGS/2026-06-08_scaling_study/${LABEL}_cpu_K1.pf"
RUNTIME=$(awk -v k="$K" -v dt="$DT" 'BEGIN { printf "%.6f", k*dt }')
awk -v rt="$RUNTIME" '
  /^runTime:true:/         { print "runTime:true:" rt ";"; next }
  /^toFileInterval:true:/  { print "toFileInterval:true:100000000.0;"; next }
  { print }
' "$SRC_PF" > "$PF"

TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"

ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }

cd "$REPO"

log "jmap run starting: $LABEL (K=$K, jmap at +${JMAP_AFTER}s, classes=$CLASSES)"
echo "[$(ts)] pwrng $LABEL jmap run K=$K jmap@+${JMAP_AFTER}s" >> "$PROGRESS"

java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
     -cp "$CLASSES:$REPO:$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOGF" 2>&1 &
JVM_PID=$!
log "  JVM pid=$JVM_PID"

sleep "$JMAP_AFTER"
if ! kill -0 "$JVM_PID" 2>/dev/null; then
  log "  JVM exited before jmap window; histogram skipped"
  exit 1
fi
log "  capturing jmap -histo:live on pid=$JVM_PID..."
if jmap -histo:live "$JVM_PID" > "$HISTOF" 2>&1; then
  log "  jmap captured to $HISTOF"
else
  log "  jmap FAILED — see $HISTOF"
fi

log "  killing JVM pid=$JVM_PID"
kill "$JVM_PID" 2>/dev/null
sleep 3
kill -9 "$JVM_PID" 2>/dev/null
wait "$JVM_PID" 2>/dev/null
log "$LABEL jmap done"
echo "[$(ts)] pwrng $LABEL jmap done" >> "$PROGRESS"
