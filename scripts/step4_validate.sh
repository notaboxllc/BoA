#!/usr/bin/env bash
# Step 4 validation: N=4 ensemble on glidingAssay500_val + dense smoke.
# Compares Step 4 (per-step demand-sync gated off in noMonomersSimd) against
# Step 3 baselines (from RUN_LOGS/2026-06-07_step3_single_graph/).
set -u
ROOT="$HOME/Code/BoA"
cd "$ROOT"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT="RUN_LOGS/2026-06-07_step4_demand_sync_reduce"
mkdir -p "$OUT"

run_one() {
    local pf="$1"; local seed="$2"; local tag="$3"
    local logf="$OUT/${tag}_seed${seed}.log"
    echo "[$(date +%H:%M)] Step4 run ${tag} seed=${seed} pf=${pf}" >> .last_run_status
    local t0=$(date +%s)
    java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx4G \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
         BoxOfActin -r -gpu -pf "$pf" -seed "$seed" > "$logf" 2>&1
    local rc=$?
    local t1=$(date +%s)
    echo "  -> rc=$rc wall=$((t1-t0))s" >> .last_run_status
    return $rc
}

# N=4 ensemble on glidingAssay500_val
for s in 1 2 3 4; do
    run_one ParameterFiles/glidingAssay500_val "$s" smoke
done

# Dense smoke
run_one ParameterFiles/glidingDense_demo_smoke 1 dense

echo "[$(date +%H:%M)] Step4 validation done" >> .last_run_status
