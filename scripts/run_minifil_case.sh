#!/usr/bin/env bash
# Run boa10-singleMiniFil and report stability + jitter/body-wander vs a reference.
# Usage: run_minifil_case.sh <outname> <gpu|cpu> <seed> [env=VAL ...]
set -e
cd "$HOME/Code/BoA"
NAME="$1"; MODE="$2"; SEED="$3"; shift 3
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
OUTBASE="$HOME/Code/threejs_output"
OUT="$OUTBASE/$NAME"; rm -rf "$OUT" "$OUT".0*
GPUFLAG="-gpu"; [ "$MODE" = "cpu" ] && GPUFLAG=""
env "$@" timeout 400 java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
   -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
   BoxOfActin -r $GPUFLAG -pf ParameterFiles/boa10-singleMiniFil -seed "$SEED" -3js "$OUT" > "$OUT.genlog" 2>&1 || true
A=$(ls -d "$OUT"* 2>/dev/null | grep -vE '\.genlog$' | head -1)
echo "OUT=$A  frames=$(ls $A/frame_*.json 2>/dev/null | wc -l)"
echo "crazy/NaN/exception lines: $(grep -ciE 'crazy|exception|NaN|blow' "$OUT.genlog")"
grep -iE "crazy|exception|NaN" "$OUT.genlog" | head -3 || true
echo "last log line: $(tail -1 "$OUT.genlog")"
echo "$A"
