#!/usr/bin/env bash
set -e; cd "$HOME/Code/BoA"
NAME="${1:-contractility_gpu_phaseA}"; SEED="${2:-12345}"
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"; OB="$HOME/Code/threejs_output"
OUT="$OB/$NAME"; rm -rf "$OUT" "$OUT".0*
timeout 580 java @$TH/tornado-argfile --enable-preview -Xmx4G \
   -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
   BoxOfActin -r -gpu -pf ParameterFiles/contractilityAssay_gpu_short -seed "$SEED" -3js "$OUT" > "$OUT.genlog" 2>&1 || true
A=$(ls -d "$OUT"* 2>/dev/null | grep -vE '\.genlog$' | head -1)
echo "OUT=$A  frames=$(ls $A/frame_*.json 2>/dev/null|wc -l)  crazy/NaN=$(grep -ciE 'crazy|NaN|exception' "$OUT.genlog")"
echo "last log: $(tail -1 "$OUT.genlog")"
echo "=== last few [STATS] contractility lines ==="
grep -iE "contractil|tension|boundMotor" "$OUT.genlog" | tail -6
