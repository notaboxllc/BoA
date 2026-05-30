#!/usr/bin/env bash
# Iter2d 10-seed ensemble validation: CPU vs GPU on glidingAssay500_val.
# Same protocol as iter2c. CPU code path is unchanged from iter2c, so we
# reuse the 10 CPU rows from RUN_LOGS/2026-05-29_iter2c-validation.txt
# (CPU is deterministic given -seed N) and only re-run GPU.

set -u

ROOT="$HOME/Code/BoA"
cd "$ROOT"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PF="ParameterFiles/glidingAssay500_val"
ITER2C_SRC="RUN_LOGS/2026-05-29_iter2c-validation.txt"

OUT="RUN_LOGS/2026-05-29_iter2d-validation.txt"
echo "mode seed bindEvents meanBoundMotors glidingVelocity wall_sec" > "$OUT"

# Copy the 10 CPU rows from iter2c (CPU code unchanged, deterministic per seed).
grep "^cpu " "$ITER2C_SRC" >> "$OUT"

run_gpu_one () {
    local seed="$1"
    local label="gpu_seed${seed}"
    local logf="RUN_LOGS/2026-05-29_iter2d_${label}.log"

    local t0=$(date +%s)
    java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx800M \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
         BoxOfActin -r -gpu -pf "$PF" -seed "$seed" > "$logf" 2>&1
    local t1=$(date +%s)
    local wall=$((t1 - t0))

    local be=$(grep -m1 "^\[STATS\] bindEvents="     "$logf" | sed -E 's/.*bindEvents=([0-9.]+).*/\1/')
    local mb=$(grep -m1 "^\[STATS\] meanBoundMotors=" "$logf" | sed -E 's/.*meanBoundMotors=([0-9.]+).*/\1/')
    local gv=$(grep -m1 "^\[STATS\] glidingVelocity=" "$logf" | sed -E 's/.*glidingVelocity=([0-9.]+).*/\1/')
    [[ -z "$be" ]] && be="NaN"
    [[ -z "$mb" ]] && mb="NaN"
    [[ -z "$gv" ]] && gv="NaN"
    printf "gpu %2d %s %s %s %d\n" "$seed" "$be" "$mb" "$gv" "$wall" >> "$OUT"
    echo "[$label] bindEvents=$be meanBoundMotors=$mb glidingVelocity=$gv wall=${wall}s" >> .last_run_status
}

date > .last_run_status
echo "iter2d ensemble starting (GPU only; CPU rows reused from iter2c)" >> .last_run_status

for seed in 1 2 3 4 5 6 7 8 9 10; do
    run_gpu_one "$seed"
done
echo "GPU ensemble done" >> .last_run_status

# Aggregate GPU [STATS] gpuMoveThing lines.
{
    echo ""
    echo "GPU per-seed moveThing timing:"
    for seed in 1 2 3 4 5 6 7 8 9 10; do
        grep -m1 "^\[STATS\] gpuMoveThing" "RUN_LOGS/2026-05-29_iter2d_gpu_seed${seed}.log" | sed "s/^/seed ${seed}  /"
    done
    echo ""
    echo "GPU per-seed motorBinding timing:"
    for seed in 1 2 3 4 5 6 7 8 9 10; do
        grep -m1 "^\[STATS\] gpuMotorBinding" "RUN_LOGS/2026-05-29_iter2d_gpu_seed${seed}.log" | sed "s/^/seed ${seed}  /"
    done
} >> "$OUT"

echo "iter2d ensemble fully complete" >> .last_run_status
