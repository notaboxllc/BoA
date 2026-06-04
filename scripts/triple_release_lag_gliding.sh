#!/usr/bin/env bash
# 2026-06-04 — Release-lag confirmation: three-arm paired gliding ensemble.
# All three arms run -gpu on glidingAssay500_val, sharing the same seed set.
# Differences only in motor force source-of-truth and CPU release tracker timing:
#   - device   : default (DIAG_CPU_MOTOR off, DIAG_RELEASE_LAG off — device kernels
#                compute F8/F9/F10; ckRelease structurally reads forceDotFil
#                from the PRIOR step's bridgeMotorForceWriteback — inherent lag).
#   - freshCPU : BOA_DIAG_CPU_MOTOR=1 (CPU MyoFilLink pair runs; CPU release
#                reads fresh same-step forceDotFil).
#   - laggedCPU: BOA_DIAG_CPU_MOTOR=1 + BOA_DIAG_RELEASE_LAG=1 (CPU pair runs;
#                CPU release reads PRIOR-step forceDotFil, mimicking device lag).
#
# Usage:  scripts/triple_release_lag_gliding.sh "<seedListSpace>" <outDir> [heartbeat_period_sec]
# e.g.    scripts/triple_release_lag_gliding.sh "1 2" RUN_LOGS/2026-06-04_release_lag_smoke

set -u
ROOT="$HOME/Code/BoA"
cd "$ROOT"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PF="ParameterFiles/glidingAssay500_val"

SEEDS="${1:?seed list required (space-separated)}"
OUT="${2:?output dir required}"
HB="${3:-300}"   # heartbeat period in seconds (default 5 min)
mkdir -p "$OUT"
CSV="$OUT/results.csv"
if [[ ! -s "$CSV" ]]; then
  echo "arm,seed,bindEvents,meanBoundMotors,glidingVelocity,wall_sec" > "$CSV"
fi

log_status () { echo "[$(date +%H:%M)] $*" >> "$ROOT/.last_run_status"; }

run_one () {
    local arm="$1"   # device | freshCPU | laggedCPU
    local seed="$2"
    local logf="$OUT/${arm}_seed${seed}.log"

    local extra_env=""
    case "$arm" in
        device   ) extra_env="" ;;
        freshCPU ) extra_env="BOA_DIAG_CPU_MOTOR=1 BOA_DIAG_RELEASE_LAG=0" ;;
        laggedCPU) extra_env="BOA_DIAG_CPU_MOTOR=1 BOA_DIAG_RELEASE_LAG=1" ;;
        *) echo "unknown arm: $arm" >&2; return 2 ;;
    esac

    local t0=$(date +%s)
    # Spawn the run in the background so we can emit periodic heartbeats while it computes.
    # Poll every 5s (cheap) and emit a heartbeat each time the elapsed time crosses a
    # multiple of HB. This avoids the prior `sleep $HB` overshoot that added up to one
    # full HB period per run to the measured wall.
    env $extra_env java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx800M \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
         BoxOfActin -r -gpu -pf "$PF" -seed "$seed" > "$logf" 2>&1 &
    local java_pid=$!
    local hb_count=0
    while kill -0 "$java_pid" 2>/dev/null; do
        sleep 5
        local elapsed=$(( $(date +%s) - t0 ))
        local next=$(( elapsed / HB ))
        if (( next > hb_count )); then
            hb_count=$next
            log_status "[heartbeat] arm=$arm seed=$seed pid=$java_pid running for ${elapsed}s"
        fi
    done
    wait "$java_pid"
    local rc=$?
    local t1=$(date +%s)
    local wall=$((t1 - t0))

    local be=$(grep -m1 "^\[STATS\] bindEvents="      "$logf" | sed -E 's/.*bindEvents=([0-9.]+).*/\1/')
    local mb=$(grep -m1 "^\[STATS\] meanBoundMotors=" "$logf" | sed -E 's/.*meanBoundMotors=([0-9.]+).*/\1/')
    local gv=$(grep -m1 "^\[STATS\] glidingVelocity=" "$logf" | sed -E 's/.*glidingVelocity=([0-9.]+).*/\1/')
    [[ -z "$be" ]] && be="NaN"
    [[ -z "$mb" ]] && mb="NaN"
    [[ -z "$gv" ]] && gv="NaN"
    printf "%s,%d,%s,%s,%s,%d\n" "$arm" "$seed" "$be" "$mb" "$gv" "$wall" >> "$CSV"
    log_status "[progress] arm=$arm seed=$seed rc=$rc bindEvents=$be meanBoundMotors=$mb glidingVelocity=$gv wall=${wall}s"
}

log_status "three-arm release-lag ensemble start — out=$OUT  seeds=[$SEEDS]  heartbeat=${HB}s"
for seed in $SEEDS; do
    run_one device    "$seed"
    run_one freshCPU  "$seed"
    run_one laggedCPU "$seed"
done
log_status "three-arm release-lag ensemble done — out=$OUT"
