#!/usr/bin/env bash
# jmap-only runner — launches a CPU sim, waits past step ~2000, captures
# jmap -histo:live, then kills the run. No JFR. Used for retained-heap
# histogram capture only.
#
# Usage: run_jmap.sh <label>   (label = 4x | 8x)

set -u
set -o pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_profiling_scoping"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"

LABEL="$1"
TAG="${LABEL}_cpu_jmap"
PF="$OUT/${TAG}.pf"
LOGF="$OUT/${TAG}.log"
HISTOF="$OUT/${LABEL}_cpu_jmap_histo.txt"

case "$LABEL" in
  4x) K=2100; JMAP_AFTER=1050 ;;  # ~step 2000 at 505 ms/step + warmup
  8x) K=2100; JMAP_AFTER=2050 ;;  # ~step 2000 at 1014 ms/step + warmup
  *) echo "unknown label: $LABEL" >&2; exit 1 ;;
esac

DT="1.0E-5"
HEAP="28G"
SRC_PF="$REPO/RUN_LOGS/2026-06-08_scaling_study/${LABEL}_cpu_K1.pf"
RUNTIME=$(awk -v k="$K" -v dt="$DT" 'BEGIN { printf "%.6f", k*dt }')
awk -v rt="$RUNTIME" '
  /^runTime:true:/         { print "runTime:true:" rt ";"; next }
  /^toFileInterval:true:/  { print "toFileInterval:true:100000000.0;"; next }
  { print }
' "$SRC_PF" > "$PF"

ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }

cd "$REPO"

log "jmap run starting: $LABEL (K=$K, jmap at +${JMAP_AFTER}s)"
echo "[$(ts)] $LABEL jmap run K=$K jmap@+${JMAP_AFTER}s" > "$PROGRESS"

# Launch java directly (no time/bash wrapper) so $! is the JVM PID.
java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
     -cp ".:libs/*" \
     BoxOfActin -r -pf "$PF" -seed 1 > "$LOGF" 2>&1 &
JVM_PID=$!
log "  JVM pid=$JVM_PID"

# Wait until past step 2000 by wall-clock estimate, then jmap.
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

# Kill the JVM to free resources; we only needed the histogram.
log "  killing JVM pid=$JVM_PID"
kill "$JVM_PID" 2>/dev/null
sleep 3
kill -9 "$JVM_PID" 2>/dev/null
wait "$JVM_PID" 2>/dev/null
log "$LABEL jmap done"
echo "[$(ts)] $LABEL jmap done" > "$PROGRESS"
