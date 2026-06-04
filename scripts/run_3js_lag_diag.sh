#!/usr/bin/env bash
# 2026-06-04 — Record -3js JSON for the release-lag diagnostic visual check.
# Runs three arms one after another (no concurrent java), seed 1, on the
# short glidingAssay500_val_3jsdemo param file. The output dirs are
# auto-suffixed by BoxOfActin (.001).
set -u
ROOT="$HOME/Code/BoA"
cd "$ROOT"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PF="ParameterFiles/glidingAssay500_val_3jsdemo"
OUT="RUN_LOGS/2026-06-04_release_lag_diag"
mkdir -p "$OUT"

log_status () { echo "[$(date +%H:%M)] $*" >> .last_run_status; }

run_one () {
    local arm="$1"
    local extra_env="$2"
    local jsdir="$OUT/3js_${arm}_seed1"
    local logf="$OUT/3js_${arm}_seed1.log"
    log_status "[3js] start arm=$arm  dir=$jsdir"
    local t0=$(date +%s)
    env $extra_env java @"$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx800M \
         -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
         BoxOfActin -r -gpu -pf "$PF" -seed 1 -3js "$jsdir" > "$logf" 2>&1
    local rc=$?
    local t1=$(date +%s)
    local wall=$((t1 - t0))
    log_status "[3js] done  arm=$arm  rc=$rc  wall=${wall}s"
}

log_status "release-lag diag 3js triple-run starting"
run_one device       ""                       # F8/F9/F10 on device, no lag toggle (no-op on device path)
run_one freshCPU     "BOA_DIAG_CPU_MOTOR=1"   # CPU pair runs, fresh release
run_one laggedCPU    "BOA_DIAG_CPU_MOTOR=1 BOA_DIAG_RELEASE_LAG=1"
log_status "release-lag diag 3js triple-run done"
