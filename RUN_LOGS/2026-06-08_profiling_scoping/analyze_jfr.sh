#!/usr/bin/env bash
# Extract top-frame self-time-by-method from a JFR file and group by doLoop phase.
# Reads only jdk.ExecutionSample events.

set -u
set -o pipefail

JFR="$1"
TAG=$(basename "$JFR" .jfr)
OUT="$(dirname "$JFR")/${TAG}_topframes.txt"

# Top-of-stack frame (the method actually running when sampled).
jfr print --events jdk.ExecutionSample --stack-depth 1 "$JFR" 2>/dev/null | \
  awk '
    /stackTrace = \[/ { in_stack=1; first=1; next }
    in_stack && first && /^    [a-zA-Z]/ {
      gsub(/^[ \t]+/, ""); gsub(/ line: [0-9]+$/, "")
      print
      first=0; in_stack=0
    }
    /^\]/ { in_stack=0 }
  ' | sort | uniq -c | sort -rn > "$OUT"

TOTAL=$(awk '{s+=$1} END {print s+0}' "$OUT")
echo "TOTAL_SAMPLES=$TOTAL" > "${OUT}.total"

echo "$TAG: $TOTAL samples → ${OUT}"
head -25 "$OUT"
