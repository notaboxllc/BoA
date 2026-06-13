#!/usr/bin/env bash
# A2 gridScatter profiler: serial vs parallel-chunk scatter at 1x/8x, + chunk sweep at 8x.
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
D=RUN_LOGS/2026-06-12_gridscatter_a2
OUT=$D/prof; mkdir -p "$OUT"

run() {  # scale label  env...
  local scale="$1" label="$2"; shift 2
  local pf="ParameterFiles/boa10-64Seg-dyn-dense-${scale}x"
  local jf="$OUT/${scale}x_${label}.json"; rm -f "$jf"
  echo "[$(date +%H:%M)] prof ${scale}x ${label} ($*)"
  env "$@" java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx24G \
    -Dtornado.tvm.maxbytecodesize=16384 \
    -Dtornado.profiler=True -Dtornado.profiler.dump.dir="$jf" \
    -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
    BoxOfActin -r -gpu -pf "$pf" > "$OUT/${scale}x_${label}.log" 2>&1
  echo -n "   ${scale}x ${label}: rc=$? "; python3 $D/prof_parse.py "$jf"
}

for s in 1 8; do
  run $s serial   BOA_SERIAL_SCATTER=1
  run $s par64    BOA_SCATTER_CHUNK=64
done
# chunk sweep at 8x
for ch in 32 128 256; do
  run 8 par$ch   BOA_SCATTER_CHUNK=$ch
done
echo "[$(date +%H:%M)] PROF DONE"
