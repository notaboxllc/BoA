#!/usr/bin/env bash
# Per-worker RNG paired-ensemble seed runner. Usage: run_one_ens.sh <label> <seed> <classes-dir>
set -u
set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-09_per_worker_rng/ens_${1}"
mkdir -p "$OUT/seed${2}"
SEED="$2"
CLASSES="$3"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
cd "$REPO"
java --enable-preview -Xmx8G \
     -cp "$CLASSES:$REPO:$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*" \
     BoxOfActin -r -pf "$REPO/ParameterFiles/glidingAssay500_val" -seed "$SEED" \
     > "$OUT/seed${SEED}/stdout.txt" 2>&1
echo "[$(date +%H:%M)] pwrng ens ${1} seed${SEED} rc=$?" >> "$REPO/.last_run_status"
