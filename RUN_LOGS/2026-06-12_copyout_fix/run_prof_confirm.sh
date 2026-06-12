#!/usr/bin/env bash
# Part 1 confirmation: 1x GPU profiler run with the copy-out fix, to show
# copyOutBytes dropped from the v4 baseline 1083 MB.
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
OUT=RUN_LOGS/2026-06-12_copyout_fix/prof
mkdir -p "$OUT"
pf="ParameterFiles/_copyoutfix_prof_1x"
python3 RUN_LOGS/2026-06-12_dense_v4/gen_fixture.py "$pf" \
  boxXDim=10.0 boxYDim=10.0 initialFilaments=1000 initialMyoMiniFils=1000 \
  crossLinkGrabDist=0.05 maxFilLinkDist=0.02 xLinkOnRate=400 xLinkConc=1.0 runTime=0.03 >/dev/null
jf="$OUT/1x_r1.json"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx24G \
  -Dtornado.tvm.maxbytecodesize=16384 \
  -Dtornado.profiler=True -Dtornado.profiler.dump.dir="$jf" \
  -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
  BoxOfActin -r -gpu -pf "$pf" > "$OUT/1x_r1.log" 2>&1
echo "rc=$? json=$(du -h "$jf" 2>/dev/null | cut -f1)"
