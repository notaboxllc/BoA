#!/usr/bin/env bash
# Minifil-ON turnover residency validation: GPU-resident vs full-CPU oracle.
# Config: boa10-64Seg-dyn (100 fil + 100 minifil + cofilin sever + kRdmNuc).
# Metrics: completion (no cast crash / NaN), packRuleDesync, demandSyncPose
# cadence, poseDelta overflow, planRebuild, meanBoundMotors, segCt/len_nm
# trajectory (turnover_metrics.py), minifil body wander.
set -u
cd "$(dirname "$0")/.."
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
JARGS="@$TORNADOVM_HOME/tornado-argfile -Dtornado.tvm.maxbytecodesize=16384 --enable-preview -Xmx6G"
OUT=RUN_LOGS/_minifil_turnover
TV=$OUT/tv
mkdir -p "$OUT" "$TV"
PF=${PF:-ParameterFiles/boa10-64Seg-dyn}
SEEDS=${SEEDS:-"1 2 3"}
ST(){ date +%H:%M:%S; }

run(){ # mode seed extra
  local mode=$1 seed=$2; shift 2
  local tag="${mode}_s${seed}"
  local gpu=""; [ "$mode" = gpu ] && gpu="-gpu"
  echo "[$(ST)] $tag start" >> .last_run_status
  java $JARGS -cp "$CP" BoxOfActin -r $gpu -seed $seed -pf "$PF" "$@" \
       -3js "$TV/$tag" > "$OUT/$tag.log" 2>&1
  local rc=$?
  echo "[$(ST)] $tag rc=$rc" >> .last_run_status
  # summarize
  local desync=$(grep -oE 'packRuleDesync=[0-9]+' "$OUT/$tag.log" | tail -1)
  local calls=$(grep -oE 'demandSyncPose=[0-9.]+s\(calls=[0-9]+\)' "$OUT/$tag.log" | grep -oE 'calls=[0-9]+' | tail -1)
  local over=$(grep -oE 'overflow=[0-9]+' "$OUT/$tag.log" | tail -1)
  local prb=$(grep -oE 'planRebuild=[0-9]+' "$OUT/$tag.log" | tail -1)
  local mbm=$(grep -oE 'meanBoundMotors=[0-9.]+' "$OUT/$tag.log" | tail -1)
  local fsi=$(grep -oE 'filSegInitFireCt=[0-9]+' "$OUT/$tag.log" | tail -1)
  local crazy=$(grep -ciE 'crazy|cannot be cast|ClassCastException|NaN' "$OUT/$tag.log")
  local cast=$(grep -ciE 'cannot be cast|ClassCastException' "$OUT/$tag.log")
  local mets=$(python3 scripts/turnover_metrics.py "$TV/$tag" 2>/dev/null | tail -1)
  printf '%-10s rc=%d cast=%d crazyOrNaN=%d %s %s %s %s %s %s | %s\n' \
    "$tag" "$rc" "$cast" "$crazy" "${desync:-desync=?}" "${calls:-calls=?}" \
    "${over:-overflow=?}" "${prb:-planRebuild=?}" "${mbm:-meanBoundMotors=?}" "${fsi:-filSegInit=?}" "$mets" \
    | tee -a "$OUT/SUMMARY.txt"
}

echo "=== minifil-ON turnover validation $(date) pf=$PF ===" | tee "$OUT/SUMMARY.txt"
for s in $SEEDS; do
  run gpu "$s" "$@"
  run cpu "$s" "$@"
done
echo "=== DONE $(date) ===" | tee -a "$OUT/SUMMARY.txt"
