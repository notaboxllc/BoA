#!/usr/bin/env bash
# release_read_probe.sh — short-window paired (device vs freshCPU) runs of
# glidingAssay500_val_releaseread with BOA_DIAG_RELEASE_READ logging on.
# Outputs per-arm per-seed CSV under RUN_LOGS/2026-06-04_release_read_diag/.

set -uo pipefail

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
PARAMS="ParameterFiles/glidingAssay500_val_releaseread"
OUTDIR="RUN_LOGS/2026-06-04_release_read_diag"
SEEDS=${SEEDS:-"1 2 3"}

cd "$(dirname "$0")/.."
mkdir -p "$OUTDIR"

run_one () {
  local arm="$1" seed="$2" extra_env="$3"
  local csv="$OUTDIR/${arm}_seed${seed}.csv"
  local log="$OUTDIR/${arm}_seed${seed}.log"
  echo "[$(date +%H:%M)] start arm=$arm seed=$seed -> $csv"
  env $extra_env BOA_DIAG_RELEASE_READ="$csv" \
      java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx800M \
           -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
           BoxOfActin -r -gpu -pf "$PARAMS" -seed "$seed" \
      > "$log" 2>&1
  local rc=$?
  local rows=0
  if [ -f "$csv" ]; then rows=$(wc -l < "$csv"); fi
  echo "[$(date +%H:%M)] done arm=$arm seed=$seed rc=$rc rows=$rows"
}

for s in $SEEDS; do
  run_one device   "$s" ""
  run_one freshCPU "$s" "BOA_DIAG_CPU_MOTOR=1"
done
echo "[$(date +%H:%M)] all done"
