#!/usr/bin/env bash
# Part A — one denser gliding point extending the 2026-06-08 scaling curve.
# Same base_config family, same two-step-diff method, single seed (matches the
# original rows). Default 12x: boxXY = 14*sqrt(12), fil = 400*12 = 4800 — the
# point between the last clean 8x row and the 16x host-heap startup OOM.
# Falls back to 10x if 12x OOMs at startup.

set -u; set -o pipefail
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_scaling_study"
BASE_PF="$OUT/base_config"
DENSE="$OUT/dense_point.md"
PROGRESS="$REPO/.last_run_status"
LOG="$OUT/dense_runner.log"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
DT="1.0E-5"; HEAP="26G"
ts(){ date +%H:%M:%S; }
log(){ echo "[$(ts)] $*" | tee -a "$LOG" >&2; }

# Candidate sizes: "label:N:boxXY:nFil:K1:K2"
# 12x established to exceed the host-RAM ceiling (heap+offheap > 31GB → OOM-kill,
# even with the bytecode flag); the densest GPU gliding point that fits is ~10x.
# K1/K2 tightened to bound the (slow) CPU control at this scale.
CANDS=( "10x:10.0:44.272:4000:200:700" )

write_pf(){ awk -v bxy="$1" -v nf="$2" -v rt="$3" '
  /^boxXDim:true:/{print "boxXDim:true:" bxy ";";next}
  /^boxYDim:true:/{print "boxYDim:true:" bxy ";";next}
  /^initialFilaments:true:/{print "initialFilaments:true:" nf ";";next}
  /^runTime:true:/{print "runTime:true:" rt ";";next}
  {print}' "$BASE_PF" > "$4"; }

run_one(){ # label path k boxXY nFil K budget -> rc|wall
  local label="$1" path="$2" k="$3" boxXY="$4" nFil="$5" K="$6" budget="$7"
  local rt; rt=$(awk -v k="$K" -v dt="$DT" 'BEGIN{printf "%.6f",k*dt}')
  local tag="${label}_${path}_K${k}"; local pf="$OUT/${tag}.pf"; local lf="$OUT/${tag}.log"; local wf="$OUT/${tag}.wall"
  write_pf "$boxXY" "$nFil" "$rt" "$pf"; cd "$REPO"
  echo "[$(ts)] dense $label $path K${k} (boxXY=$boxXY fil=$nFil K=$K)" > "$PROGRESS"
  log "  $path K${k} boxXY=$boxXY fil=$nFil K=$K rt=$rt budget=${budget}s"
  local rc
  if [ "$path" = gpu ]; then
    timeout --kill-after=30s "$budget" /usr/bin/time -f "%e" -o "$wf" \
      java "@$TORNADOVM_HOME/tornado-argfile" --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
      -Dtornado.tvm.maxbytecodesize=16384 \
      -cp "$CP" BoxOfActin -r -gpu -pf "$pf" -seed 1 >"$lf" 2>&1; rc=$?
  else
    timeout --kill-after=30s "$budget" /usr/bin/time -f "%e" -o "$wf" \
      java --enable-preview -Xmx${HEAP} -XX:-UseGCOverheadLimit \
      -cp "$CP" BoxOfActin -r -pf "$pf" -seed 1 >"$lf" 2>&1; rc=$?
  fi
  local wall=""; [ -f "$wf" ] && wall=$(tail -1 "$wf" | tr -d '\n')
  log "    rc=$rc wall=${wall}s"; echo "$rc|$wall"
}

[ -s "$DENSE" ] || cat > "$DENSE" <<'EOF'
# Part A — denser gliding point (extends 2026-06-08 scaling curve)

Same base_config, same method (two-step-diff, single seed=1, dt=1e-5, output off).
boxXY = 14*sqrt(N), fil = 400*N, motors ~ 500*boxXY^2.

| size | N | boxXY | motors | fil | K1 | K2 | CPU ms/step | GPU ms/step | GPU/CPU | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
EOF

for entry in "${CANDS[@]}"; do
  IFS=':' read -r label N boxXY nFil K1 K2 <<<"$entry"
  motors=$(awk -v b="$boxXY" 'BEGIN{printf "%d",500.0*b*b}')
  log "=== Dense point $label N=$N boxXY=$boxXY motors=$motors fil=$nFil ==="
  # GPU startup-OOM probe via K1 first
  r=$(run_one "$label" gpu 1 "$boxXY" "$nFil" "$K1" 3600); grc1="${r%%|*}"; gw1="${r##*|}"
  if grep -qiE "OutOfMemoryError|out of memory" "$OUT/${label}_gpu_K1.log" 2>/dev/null; then
    log "  $label GPU OOM at startup — trying next smaller candidate"
    echo "| $label | $N | $boxXY | $motors | $nFil | $K1 | $K2 | — | — | — | GPU startup OOM (host-heap wall) |" >> "$DENSE"
    continue
  fi
  if grep -qiE "BufferOverflowException|Exception in thread" "$OUT/${label}_gpu_K1.log" 2>/dev/null; then
    log "  $label GPU crashed (TaskGraph/buffer overflow at execute) — trying next smaller candidate"
    echo "| $label | $N | $boxXY | $motors | $nFil | $K1 | $K2 | — | — | — | GPU crash: BufferOverflow at plan.execute (TaskGraph bytecode ceiling) |" >> "$DENSE"
    continue
  fi
  r=$(run_one "$label" gpu 2 "$boxXY" "$nFil" "$K2" 6000); grc2="${r%%|*}"; gw2="${r##*|}"
  r=$(run_one "$label" cpu 1 "$boxXY" "$nFil" "$K1" 4000); crc1="${r%%|*}"; cw1="${r##*|}"
  r=$(run_one "$label" cpu 2 "$boxXY" "$nFil" "$K2" 6000); crc2="${r%%|*}"; cw2="${r##*|}"
  gms="—"; cms="—"; ratio="—"; notes="ok"
  [ "$grc1" = 0 ] && [ "$grc2" = 0 ] && gms=$(awk -v a="$gw1" -v b="$gw2" -v k1="$K1" -v k2="$K2" 'BEGIN{printf "%.2f",1000*(b-a)/(k2-k1)}')
  [ "$crc1" = 0 ] && [ "$crc2" = 0 ] && cms=$(awk -v a="$cw1" -v b="$cw2" -v k1="$K1" -v k2="$K2" 'BEGIN{printf "%.2f",1000*(b-a)/(k2-k1)}')
  [ "$gms" != "—" ] && [ "$cms" != "—" ] && ratio=$(awk -v g="$gms" -v c="$cms" 'BEGIN{printf "%.2fx",g/c}')
  e701=$(grep -c "Returned: 701" "$OUT/${label}_gpu_K2.log" 2>/dev/null)
  echo "| $label | $N | $boxXY | $motors | $nFil | $K1 | $K2 | $cms | $gms | $ratio | 701=${e701} |" >> "$DENSE"
  log "=== $label done: CPU $cms GPU $gms ratio $ratio ==="
  break   # one denser point is enough
done
log "Dense point complete."
echo "[$(ts)] dense gliding point complete" > "$PROGRESS"
