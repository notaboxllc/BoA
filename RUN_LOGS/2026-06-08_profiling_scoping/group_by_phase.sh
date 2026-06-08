#!/usr/bin/env bash
# Group JFR top-frame samples to doLoop phases using phase_map.txt.
# Usage: group_by_phase.sh <topframes_file>
# Output: per-phase total + %, plus "unmapped" leftovers.

set -u
set -o pipefail

TOP="$1"
MAP="$(dirname "$0")/phase_map.txt"

TOTAL=$(awk '{s+=$1} END {print s+0}' "$TOP")

# Build per-line phase classification, then aggregate.
awk -v map="$MAP" -v TOTAL="$TOTAL" '
  BEGIN {
    while ((getline line < map) > 0) {
      if (line ~ /^#/ || line ~ /^[[:space:]]*$/) continue;
      n = split(line, parts, " → ");
      if (n != 2) continue;
      gsub(/[[:space:]]+$/, "", parts[1]);
      gsub(/^[[:space:]]+/, "", parts[2]);
      rules[++nr] = parts[1] "::" parts[2];
    }
  }
  {
    count = $1;
    $1 = "";
    sub(/^ /, "", $0);
    method = $0;
    matched = 0;
    for (i = 1; i <= nr; i++) {
      split(rules[i], rp, "::");
      pat = rp[1];
      phase = rp[2];
      # substring match
      if (index(method, pat) > 0) {
        phase_count[phase] += count;
        matched = 1;
        break;
      }
    }
    if (!matched) {
      unmapped[method] += count;
      unmapped_total += count;
    }
  }
  END {
    printf "TOTAL_SAMPLES=%d\n", TOTAL;
    for (p in phase_count) {
      printf "%s\t%d\t%.1f%%\n", p, phase_count[p], 100*phase_count[p]/TOTAL;
    }
    printf "unmapped\t%d\t%.1f%%\n", unmapped_total, 100*unmapped_total/TOTAL;
  }
' "$TOP" | sort -k2 -rn | (echo "PHASE	SAMPLES	PCT"; cat) | column -t -s $'\t'

echo ""
echo "--- top unmapped (review for phase_map.txt updates) ---"
awk -v map="$MAP" '
  BEGIN {
    while ((getline line < map) > 0) {
      if (line ~ /^#/ || line ~ /^[[:space:]]*$/) continue;
      n = split(line, parts, " → ");
      if (n != 2) continue;
      gsub(/[[:space:]]+$/, "", parts[1]);
      rules[++nr] = parts[1];
    }
  }
  {
    count = $1; $1 = ""; sub(/^ /, "", $0); method = $0;
    for (i = 1; i <= nr; i++) {
      if (index(method, rules[i]) > 0) next;
    }
    printf "%6d  %s\n", count, method;
  }
' "$TOP" | sort -rn | head -10
