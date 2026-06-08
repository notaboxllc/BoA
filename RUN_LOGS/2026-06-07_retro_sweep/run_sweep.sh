#!/usr/bin/env bash
# Retrospective CPU-vs-GPU speed sweep across the residency campaign.
# Read-only across every commit: no source edits, no commits in detached state.
# Writes incrementally so a crash never loses completed rows.

set -u
set -o pipefail

REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-07_retro_sweep"
BASE_PF="$OUT/retro_sweep_config"
RESULTS="$OUT/results.md"
PROGRESS="$REPO/.last_run_status"
RUNNER_LOG="$OUT/runner.log"

TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."

K1=500
K2=2000
DT="1.0E-5"
RUNTIME_K1="0.005"
RUNTIME_K2="0.020"
HEAP="20G"

# label, hash
COMMITS=(
  "iter2c:bb7829e"
  "iter2d:8027662"
  "pre-4.5:b044874"
  "post-4.5:7759be7"
  "post-residency:498bb7c"
)

ts() { date +%H:%M:%S; }
log() { echo "[$(ts)] $*" | tee -a "$RUNNER_LOG"; }
status() { echo "$*" > "$PROGRESS"; }

cleanup_exit() {
  log "Cleanup: returning repo to main"
  cd "$REPO"
  git checkout main >/dev/null 2>&1 || log "WARN: failed to checkout main on exit"
  status "[$(ts)] sweep cleanup done"
}
trap cleanup_exit EXIT

write_pf_variant() {
  local k_runtime="$1"
  local out_pf="$2"
  awk -v rt="$k_runtime" '
    /^runTime:true:/ { print "runTime:true:" rt ";"; next }
    { print }
  ' "$BASE_PF" > "$out_pf"
}

build_at_commit() {
  local hash="$1"
  cd "$REPO"
  find . -maxdepth 2 -name "*.class" -delete 2>/dev/null
  javac -g --release 21 --enable-preview -XDignore.symbol.file -encoding ISO-8859-1 \
    -cp "$CP" boxOfActin/*.java *.java >"$OUT/build_${hash}.log" 2>&1
  return $?
}

run_one() {
  # args: hash label path(cpu|gpu) k(1|2) runtime_val
  local hash="$1" label="$2" path="$3" k="$4" runtime="$5"
  local pf="$OUT/${hash}_K${k}.pf"
  local logf="$OUT/${hash}_${path}_K${k}.log"
  local wallf="$OUT/${hash}_${path}_K${k}.wall"
  write_pf_variant "$runtime" "$pf"
  cd "$REPO"
  if [ "$path" = "gpu" ]; then
    /usr/bin/time -f "%e" -o "$wallf" \
      java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview \
      -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp "$CP" \
      BoxOfActin -r -gpu -pf "$pf" -seed 1 \
      >"$logf" 2>&1
  else
    /usr/bin/time -f "%e" -o "$wallf" \
      java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit -cp "$CP" \
      BoxOfActin -r -pf "$pf" -seed 1 \
      >"$logf" 2>&1
  fi
  local rc=$?
  echo "$rc"
}

# Initialize results file if not present
if [ ! -f "$RESULTS" ]; then
  cat > "$RESULTS" <<EOF
# Retrospective campaign sweep — CPU + GPU steady-state s/step

Fixed config: \`$BASE_PF\` (400 random filaments, 14×14×0.5 µm bed @ 500/µm² ≈ 98K
motors). Byte-identical at every commit; only the \`runTime\` line is varied
between K1 = $K1 steps and K2 = $K2 steps (paired runs, dt = $DT).
Steady-state s/step = (wall_K2 − wall_K1) / ($K2 − $K1).

Heap: \`-Xmx$HEAP -XX:-UseGCOverheadLimit\` on both paths (the pre-campaign
commits' \`-gpu\` startup needs > 4G for class init).

CPU command (no \`-gpu\`):

\`\`\`
java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \\
     -cp "\$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \\
     BoxOfActin -r -pf <pf> -seed 1
\`\`\`

GPU command:

\`\`\`
java @\$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx${HEAP} \\
     -XX:-UseGCOverheadLimit -cp "\$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \\
     BoxOfActin -r -gpu -pf <pf> -seed 1
\`\`\`

Aorus, Java 21, TornadoVM 4.0.1-dev (PTX backend), RTX 5070 (12 GB), 31 GB RAM.

| commit | hash | CPU K1 s | CPU K2 s | CPU ss ms/step | GPU K1 s | GPU K2 s | GPU ss ms/step | GPU÷CPU | notes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
EOF
fi

N=${#COMMITS[@]}
START_TS=$(date +%s)
log "Sweep starting: $N commits, K1=$K1 K2=$K2 heap=$HEAP"
status "[$(ts)] sweep starting: $N commits"

for ((i=0;i<N;i++)); do
  entry="${COMMITS[$i]}"
  label="${entry%%:*}"
  hash="${entry##*:}"
  IDX=$((i+1))

  log "=== Commit $IDX/$N: $label ($hash) ==="
  status "[$(ts)] commit $IDX/$N $label ($hash) — checkout+build"

  cd "$REPO"
  # drop any modifications to tracked files (e.g. .class files rebuilt over
  # tracked baselines at iter2a/2b where build artifacts were committed)
  git checkout -f HEAD -- . >/dev/null 2>&1 || true
  git checkout -f "$hash" >/dev/null 2>&1
  if [ $? -ne 0 ]; then
    log "  checkout failed; skipping"
    echo "| $label | \`$hash\` | — | — | — | — | — | — | — | checkout failed |" >> "$RESULTS"
    continue
  fi

  log "  building..."
  build_at_commit "$hash"
  if [ $? -ne 0 ]; then
    log "  build failed; skipping"
    echo "| $label | \`$hash\` | — | — | — | — | — | — | — | build failed |" >> "$RESULTS"
    continue
  fi

  cpu_K1_wall="" cpu_K2_wall="" gpu_K1_wall="" gpu_K2_wall=""
  cpu_ss_ms="" gpu_ss_ms="" ratio="" notes=""

  for path in cpu gpu; do
    for k_pair in "1:$RUNTIME_K1" "2:$RUNTIME_K2"; do
      kid="${k_pair%%:*}"; rt="${k_pair##*:}"
      status "[$(ts)] commit $IDX/$N $label — $path K$kid"
      log "  running $path K$kid (runTime=$rt)..."
      rc=$(run_one "$hash" "$label" "$path" "$kid" "$rt")
      wall_file="$OUT/${hash}_${path}_K${kid}.wall"
      wall=""
      if [ -f "$wall_file" ]; then
        wall=$(tail -1 "$wall_file" | tr -d '\n')
      fi
      log "    rc=$rc wall=${wall}s"
      if [ "$rc" != "0" ]; then
        # check for OOM
        if grep -q "OutOfMemoryError" "$OUT/${hash}_${path}_K${kid}.log" 2>/dev/null; then
          if [ "$path" = "gpu" ]; then
            notes="${notes} GPU OOM at scale (rc=$rc K=$kid);"
          else
            notes="${notes} CPU OOM (rc=$rc K=$kid);"
          fi
        else
          notes="${notes} $path K$kid rc=$rc;"
        fi
        wall=""
      fi
      # store in variable
      var="${path}_K${kid}_wall"
      eval "${var}=\"$wall\""
    done

    w1="${path}_K1_wall"; w2="${path}_K2_wall"
    eval "v1=\${$w1}; v2=\${$w2}"
    if [ -n "$v1" ] && [ -n "$v2" ]; then
      ss=$(awk -v a="$v1" -v b="$v2" -v k1="$K1" -v k2="$K2" \
        'BEGIN { printf "%.2f", 1000*(b-a)/(k2-k1) }')
      if [ "$path" = "cpu" ]; then cpu_ss_ms="$ss"; else gpu_ss_ms="$ss"; fi
    fi
  done

  if [ -n "$cpu_ss_ms" ] && [ -n "$gpu_ss_ms" ]; then
    ratio=$(awk -v a="$gpu_ss_ms" -v b="$cpu_ss_ms" 'BEGIN { printf "%.2fx", a/b }')
  fi

  # display cells (use — for missing)
  d() { if [ -z "$1" ]; then echo "—"; else echo "$1"; fi; }
  notes_disp="${notes#" "}"
  [ -z "$notes_disp" ] && notes_disp="ok"
  echo "| $label | \`$hash\` | $(d $cpu_K1_wall) | $(d $cpu_K2_wall) | $(d $cpu_ss_ms) | $(d $gpu_K1_wall) | $(d $gpu_K2_wall) | $(d $gpu_ss_ms) | $(d $ratio) | $notes_disp |" >> "$RESULTS"

  ELAPSED=$(( $(date +%s) - START_TS ))
  REMAINING=$(( (ELAPSED * (N - IDX)) / IDX ))
  log "  done. elapsed=${ELAPSED}s eta=${REMAINING}s remaining"
done

log "=== Sweep complete ==="
status "[$(ts)] sweep complete: $N commits"
