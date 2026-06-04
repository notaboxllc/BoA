#!/usr/bin/env bash
# 2026-06-03 — Paired DEVICE vs CPU gliding ensemble on glidingAssay500_val.
# Both arms run -gpu; CPU arm forces the four ported forces (anchor, F3/F4,
# F1 box, device tipC writeback) onto CPU via env hooks. Same -seed across
# both arms so the only difference is where the ported forces compute.
#
# Usage:  scripts/paired_64mon_gliding.sh <seedListSpace> <outDir>
# e.g.    scripts/paired_64mon_gliding.sh "1 2" RUN_LOGS/2026-06-03_paired_smoke
set -u

ROOT="$HOME/Code/BoA"
cd "$ROOT"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PF="ParameterFiles/glidingAssay500_val"

SEEDS="${1:?seed list required}"
OUT="${2:?output dir required}"
mkdir -p "$OUT"
CSV="$OUT/results.csv"
echo "arm,seed,bindEvents,meanBoundMotors,glidingVelocity,wall_sec" > "$CSV"

log_status () { echo "[$(date +%H:%M)] $*" >> .last_run_status; }

run_one () {
    local arm="$1"   # device | cpu
    local seed="$2"
    local logf="$OUT/${arm}_seed${seed}.log"

    local extra_env=""
    if [[ "$arm" == "cpu" ]]; then
        extra_env="BOA_DIAG_CPU_ANCHOR=1 BOA_DIAG_CPU_F3F4=1 BOA_DIAG_CPU_F1=1 BOA_DIAG_DEVICE_BOUNDARY_TIPC=0"
    fi

    local t0=$(date +%s)
    env $extra_env java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx800M \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
         BoxOfActin -r -gpu -pf "$PF" -seed "$seed" > "$logf" 2>&1
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

log_status "paired ensemble start — out=$OUT  seeds=[$SEEDS]"
for seed in $SEEDS; do
    run_one device "$seed"
    run_one cpu    "$seed"
done
log_status "paired ensemble done — out=$OUT"
