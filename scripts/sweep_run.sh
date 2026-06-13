#!/usr/bin/env bash
set -e; cd "$HOME/Code/BoA"
NAME="$1"; CFG="$2"; SEED="${3:-1}"
TH="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TH/share/java/tornado"
OUT="/tmp/sweep/$NAME"; rm -rf "$OUT"*; mkdir -p /tmp/sweep
timeout 580 java @$TH/tornado-argfile --enable-preview -Xmx4G \
   -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
   BoxOfActin -r -gpu -pf "ParameterFiles/$CFG" -seed "$SEED" > "$OUT.log" 2>&1 || true
echo "[$NAME / $CFG] rc-clean=$(grep -c 'Finished closing' "$OUT.log")  crazy/NaN/exception=$(grep -ciE 'crazy|NaN|exception|SIGSEGV|ClassCast' "$OUT.log")"
grep -iE "ClassCast|Exception|SIGSEGV" "$OUT.log" | head -2 || true
grep -iE "velocity|glidingAssay|meanBoundMotors|\[STATS\]" "$OUT.log" | tail -3 || true
echo "last: $(tail -1 "$OUT.log")"
