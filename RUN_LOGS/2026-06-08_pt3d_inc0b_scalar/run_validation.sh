#!/usr/bin/env bash
# Run a single 1× CPU validation seed against the provided classes dir.
set -u
LABEL="$1"  # main_baseline OR scalar
SEED="$2"
CLASSES="$3"
REPO="/home/jba/Code/BoA"
OUT="$REPO/RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/ens_${LABEL}/seed${SEED}"
mkdir -p "$OUT"
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
cd "$REPO"
java --enable-preview -Xmx6G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:$REPO/libs/*:$CLASSES" \
     BoxOfActin -r -pf "$REPO/ParameterFiles/glidingAssay500_val" -seed "$SEED" \
     > "$OUT/stdout.txt" 2>&1
RC=$?
echo "[$(date +%H:%M)] ens ${LABEL} seed${SEED} rc=$RC" >> "$REPO/.last_run_status"
exit $RC
