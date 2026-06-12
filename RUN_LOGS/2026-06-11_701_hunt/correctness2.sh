#!/usr/bin/env bash
set -u; set -o pipefail
REPO="/home/jba/Code/BoA"; OUT="$REPO/RUN_LOGS/2026-06-11_701_hunt"
PF="$REPO/RUN_LOGS/2026-06-11_contractile_scaling/base_config"
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"; CP="$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$REPO"
ts(){ date +%H:%M:%S; }
SP="$OUT/corr2_1x.pf"
awk '/^runTime:true:/{print "runTime:true:0.250000;";next}
     /^toFileInterval:true:/{print "toFileInterval:true:500.0;";next}{print}' "$PF" > "$SP"
RAW="$OUT/correctness2.tsv"; echo -e "config\tseed\tsegCt\ttotalLen_um\tmeanBound\tnan\trc" > "$RAW"
pf(){ python3 - "$1" <<'PY'
import json,sys,math,glob,os
fs=sorted(glob.glob(sys.argv[1]+"*/frame_*.json"), key=os.path.getmtime)
if not fs: print("0 0"); sys.exit()
d=json.load(open(fs[-1])); segs=d.get("segments",[])
tot=sum(math.dist(s["end1"],s["end2"]) for s in segs)
print(len(segs), round(tot,3))
PY
}
run(){ local cfg="$1" seed="$2"; local dir="$OUT/f_${cfg}_s${seed}"; local lg="$OUT/c2_${cfg}_s${seed}.log"
  cd "$REPO"
  if [ "$cfg" = cpu ]; then
    timeout 600 java --enable-preview -Xmx16G -XX:-UseGCOverheadLimit -cp "$CP" BoxOfActin -r -pf "$SP" -seed "$seed" -3js "$dir" >"$lg" 2>&1
  else
    timeout 600 java "@$TH/tornado-argfile" --enable-preview -Xmx16G -XX:-UseGCOverheadLimit -Dtornado.tvm.maxbytecodesize=16384 -cp "$CP" BoxOfActin -r -gpu -pf "$SP" -seed "$seed" -3js "$dir" >"$lg" 2>&1
  fi
  local rc=$?; local mb; mb=$(grep -oE 'meanBoundMotors=[0-9.]+' "$lg"|tail -1|grep -oE '[0-9.]+'); local nan; nan=$(grep -ic 'nan\|infinity' "$lg")
  read sc tl < <(pf "$dir"); echo -e "$cfg\t$seed\t$sc\t$tl\t${mb:-NA}\t$nan\t$rc" >> "$RAW"
  echo "[$(ts)] $cfg s$seed segCt=$sc len=${tl}um mb=${mb:-NA} nan=$nan rc=$rc"
}
for seed in 1 2 3; do for cfg in cpu gpudev; do run "$cfg" "$seed"; done; done
echo "[$(ts)] DONE2 -> $RAW"
