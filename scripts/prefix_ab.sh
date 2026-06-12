#!/usr/bin/env bash
# A/B: post-fix vs pre-fix GPU on minifil-ON turnover (seed 1, boa10-64Seg-dyn).
# Proves the reconcilePackRule guard is behavior-neutral in production (where
# packRuleDesync=0, so it is a pure pass-through). Stashes ONLY the 3 fix files
# (GPUMoveThing/Thing/BoxOfActin), leaving the user's working-tree changes intact.
set -u
cd "$(dirname "$0")/.."
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
CP="$TDIR/tornado-api-4.0.1-dev.jar:libs/*:."
JARGS="@$TORNADOVM_HOME/tornado-argfile -Dtornado.tvm.maxbytecodesize=16384 --enable-preview -Xmx6G"
OUT=RUN_LOGS/_minifil_turnover
build(){ javac -g --release 21 --enable-preview -XDignore.symbol.file -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java 2>&1 | grep -v "Note:\|warning:\|preview\|^Picked" ; }
runseed(){ java $JARGS -cp "$CP" BoxOfActin -r -gpu -seed 1 -pf ParameterFiles/boa10-64Seg-dyn -3js "$OUT/tv/$1" > "$OUT/$1.log" 2>&1; echo "$1 rc=$? mBM=$(grep -oE 'meanBoundMotors=[0-9.]+' "$OUT/$1.log"|tail -1) desync=$(grep -oE 'packRuleDesync=[0-9]+' "$OUT/$1.log"|tail -1) $(python3 scripts/turnover_metrics.py "$OUT/tv/$1" 2>/dev/null|tail -1)"; }

echo "=== PRE-FIX A/B $(date) ===" | tee "$OUT/AB_prefix.txt"
echo "[stashing fix files]"; git stash push -m prefix_ab boxOfActin/GPUMoveThing.java boxOfActin/Thing.java boxOfActin/BoxOfActin.java
build
runseed prefix_gpu_s1 | tee -a "$OUT/AB_prefix.txt"
echo "[restoring fix]"; git stash pop
build
runseed postfix_gpu_s1 | tee -a "$OUT/AB_prefix.txt"
echo "=== AB DONE $(date) ===" | tee -a "$OUT/AB_prefix.txt"
