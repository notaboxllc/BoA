#!/usr/bin/env bash
# Run the single-minifilament spin probe (Phase 2a metric) on GPU or CPU.
# Usage: run_spin_probe.sh <label> <seed> [gpu|cpu] [extra env passed through]
set -e
cd "$HOME/Code/BoA"
LABEL="$1"; SEED="$2"; MODE="${3:-gpu}"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUT="/tmp/spinprobe/${LABEL}_s${SEED}"
rm -rf "$OUT"*; mkdir -p /tmp/spinprobe
GPUFLAG="-gpu"; [ "$MODE" = "cpu" ] && GPUFLAG=""
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r $GPUFLAG -pf ParameterFiles/boa10-singleMiniFil -seed "$SEED" -3js "$OUT" \
     > "/tmp/spinprobe/${LABEL}_s${SEED}.log" 2>&1
# -3js auto-increments .001 if exists; find the actual dir (exclude the .log)
ACTUAL=$(ls -d "${OUT}"* 2>/dev/null | grep -v '\.log$' | head -1)
echo "FRAMES: $ACTUAL"
python3 scripts/spin_metric.py "$ACTUAL"
