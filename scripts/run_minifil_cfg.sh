#!/usr/bin/env bash
set -e; cd "$HOME/Code/BoA"
NAME="$1"; CFG="$2"; MODE="$3"; SEED="$4"; shift 4
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"; OB="$HOME/Code/threejs_output"
OUT="$OB/$NAME"; rm -rf "$OUT" "$OUT".0*
G="-gpu"; [ "$MODE" = "cpu" ] && G=""
env "$@" timeout 580 java @$TH/tornado-argfile --enable-preview -Xmx2G \
   -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
   BoxOfActin -r $G -pf "ParameterFiles/$CFG" -seed "$SEED" -3js "$OUT" > "$OUT.genlog" 2>&1 || true
A=$(ls -d "$OUT"* 2>/dev/null | grep -vE '\.genlog$' | head -1)
echo "OUT=$A frames=$(ls $A/frame_*.json 2>/dev/null|wc -l) crazy/NaN=$(grep -ciE 'crazy|NaN|exception' "$OUT.genlog") last=$(tail -1 "$OUT.genlog")"
