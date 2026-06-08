#!/usr/bin/env bash
# Profiling scoping runs — JFR self-time-by-method for CPU + GPU at 1x/4x/8x,
# plus jmap heap histogram capture for CPU at 4x/8x.
# Same scaling-study config generator (boxXY=14*sqrt(N), nFil=400*N).
# No source edits — JFR/jmap only.

set -u
set -o pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_profiling_scoping"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"
START_TS=$(date +%s)

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."

DT="1.0E-5"
HEAP="28G"

ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG" >&2; }
status() { echo "[$(ts)] $*" > "$PROGRESS"; }

cd "$REPO"

# ---- Args: tag path K jfr_delay jfr_duration [jmap_after_secs] ----
# tag examples: 1x_cpu, 1x_gpu, 4x_cpu, 4x_gpu, 8x_cpu, 8x_gpu
# path: cpu | gpu
# K: total simulation steps
# jfr_delay: secs after JVM start before JFR recording begins
# jfr_duration: secs of JFR recording (set short enough to cover steady-state, not entire run)
# jmap_after_secs: if set, take jmap -histo:live after this many secs into the run, then continue
run_one() {
  local tag="$1" path="$2" K="$3" delay="$4" duration="$5" jmap_after="${6:-}"
  local label
  label=$(echo "$tag" | cut -d_ -f1)
  local pf="$REPO/RUN_LOGS/2026-06-08_scaling_study/${label}_${path}_K1.pf"
  local out_pf="$OUT/${tag}.pf"
  local runtime
  runtime=$(awk -v k="$K" -v dt="$DT" 'BEGIN { printf "%.6f", k*dt }')
  # Override toFileInterval to a value larger than any K we use; this prevents
  # GlidingAssayEvaluator.outputInterval() from firing at step 0 (its initial
  # threeJSCounter=1e6 sentinel triggers it on the first step, and the
  # O(M*F*S) duty-ratio nested loop swamps JFR windows at 4x/8x). The per-step
  # sampleStep() still runs unchanged.
  awk -v rt="$runtime" '
    /^runTime:true:/         { print "runTime:true:" rt ";"; next }
    /^toFileInterval:true:/  { print "toFileInterval:true:100000000.0;"; next }
    { print }
  ' "$pf" > "$out_pf"
  local logf="$OUT/${tag}.log"
  local jfrf="$OUT/${tag}.jfr"
  local wallf="$OUT/${tag}.wall"
  local pidf="$OUT/${tag}.pid"

  local jfr_args="-XX:StartFlightRecording=delay=${delay}s,duration=${duration}s,filename=${jfrf},settings=profile"

  status "$tag (K=$K, delay=${delay}s, duration=${duration}s, jmap_after=${jmap_after:-none})"
  log ">>> $tag K=$K JFR delay=${delay}s duration=${duration}s jmap_after=${jmap_after:-none}"

  local cmd
  if [ "$path" = "gpu" ]; then
    cmd="java @$TORNADOVM_HOME/tornado-argfile --enable-preview $jfr_args -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp \"$CP\" BoxOfActin -r -gpu -pf \"$out_pf\" -seed 1"
  else
    cmd="java --enable-preview $jfr_args -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp \"$CP\" BoxOfActin -r -pf \"$out_pf\" -seed 1"
  fi

  /usr/bin/time -f "%e" -o "$wallf" bash -c "$cmd" >"$logf" 2>&1 &
  local pid=$!
  echo "$pid" > "$pidf"
  log "    bash pid=$pid"

  if [ -n "$jmap_after" ]; then
    # Sleep until step 2000 is past, then resolve the JVM child PID
    # (we want the java process under the bash wrapper, not the wrapper itself).
    sleep "$jmap_after"
    if kill -0 "$pid" 2>/dev/null; then
      # Find the java JVM child by walking pgrep -P repeatedly.
      local jvm_pid="$pid" tries=0
      while true; do
        local children
        children=$(pgrep -P "$jvm_pid" 2>/dev/null)
        if [ -z "$children" ]; then break; fi
        jvm_pid=$(echo "$children" | head -1)
        tries=$((tries+1))
        [ $tries -gt 8 ] && break
      done
      log "    jmap -histo:live on JVM pid=$jvm_pid (bash wrapper pid=$pid) at +${jmap_after}s..."
      jmap -histo:live "$jvm_pid" > "$OUT/${tag}_jmap_histo.txt" 2>&1 && log "    jmap captured" || log "    jmap FAILED"
    else
      log "    process already exited before jmap"
    fi
  fi

  wait "$pid"
  local rc=$?
  local wall=""
  [ -f "$wallf" ] && wall=$(tail -1 "$wallf")
  log "<<< $tag rc=$rc wall=${wall}s"
}

# Argument: run name (one of the run_one calls below) or "all"
TARGET="${1:-all}"

case "$TARGET" in
  1x_cpu) run_one 1x_cpu cpu  500 10 60  ;;
  1x_gpu) run_one 1x_gpu gpu  500 10 40  ;;
  4x_cpu) run_one 4x_cpu cpu 2200 30 120 1050 ;;
  4x_gpu) run_one 4x_gpu gpu  400 20 100 ;;
  8x_cpu) run_one 8x_cpu cpu 2200 60 240 2050 ;;
  8x_gpu) run_one 8x_gpu gpu  300 30 150 ;;
  all)
    run_one 1x_cpu cpu  500 10 60
    run_one 1x_gpu gpu  500 10 40
    run_one 4x_gpu gpu  400 20 100
    run_one 4x_cpu cpu 2200 30 120 1050
    run_one 8x_gpu gpu  300 30 150
    run_one 8x_cpu cpu 2200 60 240 2050
    ;;
  *) echo "unknown target: $TARGET" >&2; exit 1 ;;
esac

ELAPSED=$(( $(date +%s) - START_TS ))
log "all $TARGET done — elapsed ${ELAPSED}s"
status "profiling all done (elapsed ${ELAPSED}s)"
