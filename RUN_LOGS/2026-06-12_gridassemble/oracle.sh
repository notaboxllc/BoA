#!/usr/bin/env bash
# Downstream physics oracle: serial-grid vs parallel-grid, boa10-64Seg-dyn 1x,
# 3 seeds, 2500 steps. Compares final-frame segCt + total actin length +
# meanBoundMotors, scans for NaN, checks rc. Writes .last_run_status progress.
set -u
REPO="/home/jba/Code/BoA"; WD="$REPO/RUN_LOGS/2026-06-12_gridassemble"
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"; CP="$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$REPO"
STATUS="$REPO/.last_run_status"
PF="$WD/1x_oracle.pf"
awk '/^runTime:true:/{print "runTime:true:0.250000;";next}{print}' "$REPO/RUN_LOGS/2026-06-11_contractile_scaling/base_config" > "$PF"  # 2500 steps

parse_frame() { # $1 = 3js dir → "segCt totalLenUm"
  python3 - "$1" <<'PY'
import sys,glob,json,os,math
d=sys.argv[1]
fs=sorted(glob.glob(os.path.join(d,"frame_*.json")))
if not fs: print("0 0.0"); sys.exit()
j=json.load(open(fs[-1]))
segs=j.get("segments",[])
tot=0.0
for s in segs:
    e1=s.get("end1"); e2=s.get("end2")
    if e1 and e2:
        tot+=math.dist(e1,e2)
print(f"{len(segs)} {tot:.2f}")
PY
}

echo "[$(date +%H:%M)] gridAssemble oracle START" > "$STATUS"
for mode in serial parallel; do
  if [ "$mode" = serial ]; then export BOA_SERIAL_GRID=1; else unset BOA_SERIAL_GRID; fi
  for seed in 1 2 3; do
    js="$WD/3js_${mode}_s${seed}"; rm -rf "$js"* 2>/dev/null
    log="$WD/oracle_${mode}_s${seed}.log"
    echo "[$(date +%H:%M)] $mode seed$seed running" | tee -a "$STATUS"
    timeout 900 java "@$TH/tornado-argfile" --enable-preview -Xmx16G -XX:-UseGCOverheadLimit \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$CP" BoxOfActin -r -gpu -pf "$PF" -seed "$seed" -3js "$js" > "$log" 2>&1
    rc=$?
    mb=$(grep -oE "meanBoundMotors=[0-9.]+" "$log" | tail -1 | cut -d= -f2)
    be=$(grep -oE "bindEvents=[0-9]+" "$log" | tail -1 | cut -d= -f2)
    nan=$(grep -ciE "nan|crazy|Exception|701|BufferOverflow" "$log")
    # 3js dir auto-increments; find the actual one
    realjs=$(ls -d "${js}"* 2>/dev/null | head -1)
    sl=$(parse_frame "$realjs")
    echo "[$(date +%H:%M)] RESULT $mode s$seed rc=$rc segCt_len=[$sl] meanBoundMotors=$mb bindEvents=$be errCount=$nan" | tee -a "$STATUS"
  done
done
echo "[$(date +%H:%M)] gridAssemble oracle DONE" | tee -a "$STATUS"
