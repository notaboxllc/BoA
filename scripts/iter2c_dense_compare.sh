#!/usr/bin/env bash
# Iter2c dense timing comparison: one CPU seed + one GPU seed on
# glidingDense_demo_smoke (M=98K motors, 14x14x0.5 µm bed, 100 random fils,
# 1001 steps). Reports the per-phase breakdown so iter2c gains over iter2b's
# 1.61x slower can be quantified.

set -u

ROOT="$HOME/Code/BoA"
cd "$ROOT"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PF="ParameterFiles/glidingDense_demo_smoke"

CPU_LOG="RUN_LOGS/2026-05-29_iter2c-dense-cpu.log"
GPU_LOG="RUN_LOGS/2026-05-29_iter2c-dense-gpu.log"

echo "Iter2c dense smoke (M=98K, S~1001): CPU + GPU"
date

# CPU run
echo "--- CPU run ---"
t0=$(date +%s)
java --enable-preview -Xmx4G \
     -cp "libs/*:." \
     BoxOfActin -r -pf "$PF" -seed 1 > "$CPU_LOG" 2>&1
t1=$(date +%s)
echo "CPU wall: $((t1-t0)) s"
grep -E "^\[STATS\]" "$CPU_LOG"

# GPU run
echo "--- GPU run ---"
t0=$(date +%s)
java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx4G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -gpu -pf "$PF" -seed 1 > "$GPU_LOG" 2>&1
t1=$(date +%s)
echo "GPU wall: $((t1-t0)) s"
grep -E "^\[STATS\]" "$GPU_LOG"

echo "Dense comparison done"
date
