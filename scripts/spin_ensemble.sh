#!/usr/bin/env bash
# Ensemble spin probe: run boa10-singleMiniFil over several seeds for one condition,
# print per-run cloud coherence + |net| and the ensemble mean.
# Usage: spin_ensemble.sh <label> <mode gpu|cpu> <env e.g. BOA_MINIFIL_BROWNIAN_OFF=1 or -> <seed...>
set -e
cd "$HOME/Code/BoA"
LABEL="$1"; MODE="$2"; ENVV="$3"; shift 3
SEEDS="$@"
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
TDIR="$TORNADOVM_HOME/share/java/tornado"
GPUFLAG="-gpu"; [ "$MODE" = "cpu" ] && GPUFLAG=""
[ "$ENVV" = "-" ] && ENVV=""
mkdir -p /tmp/spinens
sum=0; n=0
for s in $SEEDS; do
  OUT="/tmp/spinens/${LABEL}_s${s}"; rm -rf "$OUT"*
  env $ENVV timeout 400 java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r $GPUFLAG -pf ParameterFiles/boa10-singleMiniFil -seed "$s" -3js "$OUT" > "${OUT}.log" 2>&1 || true
  ACTUAL=$(ls -d "${OUT}"* 2>/dev/null | grep -v '\.log$' | head -1)
  line=$(python3 scripts/spin_metric.py "$ACTUAL" 2>/dev/null | grep cloud)
  coh=$(echo "$line" | sed -E 's/.*coherence *= *([0-9.]+).*/\1/')
  net=$(echo "$line" | sed -E 's/.*net rotation *= *([+-][0-9.]+).*/\1/')
  echo "  ${LABEL} s$s: coherence=$coh  net=$net"
  if [ -n "$coh" ]; then sum=$(echo "$sum + $coh" | bc -l); n=$((n+1)); fi
done
[ "$n" -gt 0 ] && echo "==> ${LABEL} ensemble mean coherence = $(echo "scale=3; $sum/$n" | bc -l)  (n=$n)"
