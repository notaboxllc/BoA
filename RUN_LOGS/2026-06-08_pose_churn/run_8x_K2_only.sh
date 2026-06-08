#!/usr/bin/env bash
# 8× K2-only re-run for Phase C — K1 wall (1076.71s) already in
# 8x_phaseC_K300.wall. Use a 6000s budget so K=1200 steps fit
# (~4200s expected at the observed 3.5 s/step at 8×).
set -uo pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pose_churn"
SCALING="$REPO/RUN_LOGS/2026-06-08_scaling_study"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
PF="$OUT/8x_phaseC_K1200.pf"
LOG="$OUT/8x_phaseC_K1200.log"
WALL="$OUT/8x_phaseC_K1200.wall"

awk '/^runTime:true:/ { print "runTime:true:0.012000;"; next } { print }' \
    "$SCALING/8x_gpu_K1.pf" > "$PF"

cd "$REPO"
echo "[$(date +%H:%M:%S)] running 8x K=1200 (budget 7200s)..."
timeout --kill-after=30s 7200 /usr/bin/time -f "%e" -o "$WALL" \
    java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview \
    -Xmx28G -XX:-UseGCOverheadLimit -cp "$CP" \
    BoxOfActin -r -gpu -pf "$PF" -seed 1 > "$LOG" 2>&1
rc=$?
echo "[$(date +%H:%M:%S)] rc=$rc wall=$(cat "$WALL" 2>/dev/null)"
echo "Memory after:"; free -h | head -2
