#!/usr/bin/env bash
# 701-hunt correctness: does the now-executing dimerCohesion kernel stay neutral
# vs the CPU oracle, and does the DEVICE cohesion kernel match CPU cohesion?
# Three configs at 1x, full-ish (2500 steps), seeds 1-3, with -3js output:
#   cpu      = full CPU path (oracle; CPU applies minifilament cohesion)
#   gpudev   = -gpu, device dimerCohesion kernel runs (the fix)
#   gpucpu   = -gpu with BOA_MINIFIL_COHESION_CPU=1 (cohesion on CPU, rest on GPU)
# Compares: final-frame segCt, total actin length, meanBoundMotors, NaN scan.
# gpudev vs gpucpu isolates the cohesion KERNEL; both vs cpu is the oracle bar.
set -u; set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-11_701_hunt"
PF="$REPO/RUN_LOGS/2026-06-11_contractile_scaling/base_config"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$REPO"
HEAP="16G"
RAW="$OUT/correctness.tsv"
ts(){ date +%H:%M:%S; }
# 2500 steps @ 1e-4
SHORTPF="$OUT/corr_1x.pf"
awk '/^runTime:true:/{print "runTime:true:0.250000;";next}{print}' "$PF" > "$SHORTPF"
echo -e "config\tseed\tsegCt\ttotalLen_um\tmeanBound\tnanLines\trc" > "$RAW"

parse_frame(){ # dirprefix -> "segCt totalLen"  (newest frame across dir + dir.NNN)
  local d="$1"
  local f; f=$(ls -1t "$d"*/frame_*.json 2>/dev/null | head -1)
  [ -z "$f" ] && { echo "0 0"; return; }
  python3 - "$f" <<'PY'
import json,sys,math
d=json.load(open(sys.argv[1]))
segs=d.get("segments",[])
tot=0.0
for s in segs:
    a=s["end1"]; b=s["end2"]
    tot+=math.dist(a,b)
print(len(segs), round(tot,3))
PY
}

run(){ # config seed
  local cfg="$1" seed="$2"
  local dir="$OUT/3js_${cfg}_s${seed}"
  local logf="$OUT/corr_${cfg}_s${seed}.log"
  cd "$REPO"
  rm -f "$dir"/frame_*.json 2>/dev/null
  if [ "$cfg" = "cpu" ]; then
    timeout 600 java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
      -cp "$CP" BoxOfActin -r -pf "$SHORTPF" -seed "$seed" -3js "$dir" >"$logf" 2>&1
  elif [ "$cfg" = "gpudev" ]; then
    timeout 600 java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx${HEAP} \
      -XX:-UseGCOverheadLimit -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$CP" BoxOfActin -r -gpu -pf "$SHORTPF" -seed "$seed" -3js "$dir" >"$logf" 2>&1
  else # gpucpu
    BOA_MINIFIL_COHESION_CPU=1 timeout 600 java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx${HEAP} \
      -XX:-UseGCOverheadLimit -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$CP" BoxOfActin -r -gpu -pf "$SHORTPF" -seed "$seed" -3js "$dir" >"$logf" 2>&1
  fi
  local rc=$?
  local mb; mb=$(grep -oE 'meanBoundMotors=[0-9.]+' "$logf" | tail -1 | grep -oE '[0-9.]+')
  local nan; nan=$(grep -ic 'nan\|infinity' "$logf")
  read segct totlen < <(parse_frame "$dir")
  echo -e "$cfg\t$seed\t${segct}\t${totlen}\t${mb:-NA}\t${nan}\t$rc" >> "$RAW"
  echo "[$(ts)] $cfg s$seed: segCt=$segct len=${totlen}um meanBound=${mb:-NA} nan=$nan rc=$rc"
}

# auto-discover the actual -3js output dir (it auto-increments .NNN suffix)
parse_latest(){ ls -1d "$1"* 2>/dev/null | sort | tail -1; }

for seed in 1 2 3; do
  for cfg in cpu gpudev gpucpu; do
    run "$cfg" "$seed"
  done
done
echo "[$(ts)] correctness DONE -> $RAW"
