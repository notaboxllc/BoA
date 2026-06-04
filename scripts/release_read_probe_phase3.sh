#!/usr/bin/env bash
# Phase 3 gating validation of the release-read reconciliation fix.
# Runs:
#   (a) device arm with BOA_DIAG_RELEASE_READ + BOA_DIAG_RELEASE_READ_WB
#       enabled so the post-fix per-step pairing can be matched on
#       (step, motorId).
#   (b) freshCPU arm with BOA_DIAG_RELEASE_READ only; the resulting CSV
#       should be byte-identical to the pre-fix freshCPU seed (CPU path
#       was not touched).
# Pre-fix freshCPU seed CSVs live in RUN_LOGS/2026-06-04_release_read_diag/.

set -uo pipefail

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PARAMS="ParameterFiles/glidingAssay500_val_releaseread"
OUTDIR="RUN_LOGS/2026-06-04_release_read_phase3"
SEED=${SEED:-1}

cd "$(dirname "$0")/.."
mkdir -p "$OUTDIR"

run_device () {
  local seed="$1"
  local csv="$OUTDIR/device_seed${seed}.csv"
  local wb="$OUTDIR/device_seed${seed}.wb.csv"
  local log="$OUTDIR/device_seed${seed}.log"
  echo "[$(date +%H:%M)] start device seed=$seed (post-fix; wb log on)"
  BOA_DIAG_RELEASE_READ="$csv" BOA_DIAG_RELEASE_READ_WB="$wb" \
      java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
           -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
           BoxOfActin -r -gpu -pf "$PARAMS" -seed "$seed" \
      > "$log" 2>&1
  local rc=$?
  local rrows=0 wrows=0
  [ -f "$csv" ] && rrows=$(wc -l < "$csv")
  [ -f "$wb" ]  && wrows=$(wc -l < "$wb")
  echo "[$(date +%H:%M)] done device seed=$seed rc=$rc read_rows=$rrows wb_rows=$wrows"
}

run_freshcpu () {
  local seed="$1"
  local csv="$OUTDIR/freshCPU_seed${seed}.csv"
  local log="$OUTDIR/freshCPU_seed${seed}.log"
  echo "[$(date +%H:%M)] start freshCPU seed=$seed (post-fix bit-stability check)"
  BOA_DIAG_RELEASE_READ="$csv" BOA_DIAG_CPU_MOTOR=1 \
      java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
           -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
           BoxOfActin -r -gpu -pf "$PARAMS" -seed "$seed" \
      > "$log" 2>&1
  local rc=$?
  local rows=0
  [ -f "$csv" ] && rows=$(wc -l < "$csv")
  echo "[$(date +%H:%M)] done freshCPU seed=$seed rc=$rc rows=$rows"
}

run_device   "$SEED"
run_freshcpu "$SEED"
echo "[$(date +%H:%M)] all done"
