#!/usr/bin/env bash
# Iter2b 10-seed ensemble validation: CPU vs GPU on glidingAssay500_val.
# Writes per-seed results to RUN_LOGS/2026-05-29_iter2b-validation.txt and
# updates .last_run_status as it progresses.

set -u

ROOT="$HOME/Code/BoA"
cd "$ROOT"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PF="ParameterFiles/glidingAssay500_val"

OUT="RUN_LOGS/2026-05-29_iter2b-validation.txt"
echo "mode seed bindEvents meanBoundMotors glidingVelocity wall_sec" > "$OUT"

run_one () {
    local mode="$1"   # cpu or gpu
    local seed="$2"
    local label="${mode}_seed${seed}"
    local logf="RUN_LOGS/2026-05-29_iter2b_${label}.log"

    local t0=$(date +%s)
    if [[ "$mode" == "gpu" ]]; then
        java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx800M \
             -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
             BoxOfActin -r -gpu -pf "$PF" -seed "$seed" > "$logf" 2>&1
    else
        java --enable-preview -Xmx800M \
             -cp "libs/*:." \
             BoxOfActin -r -pf "$PF" -seed "$seed" > "$logf" 2>&1
    fi
    local t1=$(date +%s)
    local wall=$((t1 - t0))

    local be=$(grep -m1 "^\[STATS\] bindEvents="     "$logf" | sed -E 's/.*bindEvents=([0-9.]+).*/\1/')
    local mb=$(grep -m1 "^\[STATS\] meanBoundMotors=" "$logf" | sed -E 's/.*meanBoundMotors=([0-9.]+).*/\1/')
    local gv=$(grep -m1 "^\[STATS\] glidingVelocity=" "$logf" | sed -E 's/.*glidingVelocity=([0-9.]+).*/\1/')
    [[ -z "$be" ]] && be="NaN"
    [[ -z "$mb" ]] && mb="NaN"
    [[ -z "$gv" ]] && gv="NaN"
    printf "%s %2d %s %s %s %d\n" "$mode" "$seed" "$be" "$mb" "$gv" "$wall" >> "$OUT"
    echo "[$label] bindEvents=$be meanBoundMotors=$mb glidingVelocity=$gv wall=${wall}s" >> .last_run_status
}

date > .last_run_status
echo "iter2b ensemble starting" >> .last_run_status

for seed in 1 2 3 4 5 6 7 8 9 10; do
    run_one cpu "$seed"
done
echo "CPU ensemble done" >> .last_run_status

for seed in 1 2 3 4 5 6 7 8 9 10; do
    run_one gpu "$seed"
done
echo "GPU ensemble done" >> .last_run_status

# Aggregate GPU [STATS] gpuMoveThing lines.
{
    echo ""
    echo "GPU per-seed moveThing timing:"
    for seed in 1 2 3 4 5 6 7 8 9 10; do
        grep -m1 "^\[STATS\] gpuMoveThing" "RUN_LOGS/2026-05-29_iter2b_gpu_seed${seed}.log" | sed "s/^/seed ${seed}  /"
    done
    echo ""
    echo "GPU per-seed motorBinding timing:"
    for seed in 1 2 3 4 5 6 7 8 9 10; do
        grep -m1 "^\[STATS\] gpuMotorBinding" "RUN_LOGS/2026-05-29_iter2b_gpu_seed${seed}.log" | sed "s/^/seed ${seed}  /"
    done
} >> "$OUT"

echo "iter2b ensemble fully complete" >> .last_run_status
