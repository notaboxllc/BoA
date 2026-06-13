#!/usr/bin/env bash
# A2 CSR bit-identical oracle: device parallel chunk-scatter vs serial host
# gridAssembleKernel. PASS => bit-identical CSR (offsets + within-cell order).
set -u
cd /home/jba/Code/BoA
source RUN_LOGS/2026-06-12_dense_v4/env.sh
parity() { java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
  -Dtornado.tvm.maxbytecodesize=16384 \
  -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
  boxOfActin.GridBuildParityTest "$@" 2>/dev/null | grep -E "PARITY\] (grid|offsetMismatch|RESULT)"; }
echo "### 1x-shape (51x51x4 S=6000) chunk=64, seeds 1/2/3"; for sd in 1 2 3; do parity 51 51 4 6000 $sd 64; done
echo "### 8x-shape (143x143x4 S=48000) chunk=64, seeds 1/2"; for sd in 1 2; do parity 143 143 4 48000 $sd 64; done
echo "### 8x-shape chunk sweep seed1: 32 / 512"; parity 143 143 4 48000 1 32; parity 143 143 4 48000 1 512
echo "### regression: serial scatter (chunk=0) 8x seed1"; parity 143 143 4 48000 1 0
