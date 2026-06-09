#!/usr/bin/env bash
# Aggregate JFR jdk.ObjectAllocationSample events by class. Outputs top N.
set -u
JFR="$1"
N="${2:-25}"
OUT="${3:-/dev/stdout}"

JFR_TOOL="$(command -v jfr)"
[ -z "$JFR_TOOL" ] && JFR_TOOL=/usr/bin/jfr

# JDK 21 default 'profile' settings emit jdk.ObjectAllocationSample (sampled,
# low-overhead; one event per ~512KB of allocation per thread). The "weight"
# field is the bytes-since-last-sample for that thread/class — i.e. the sampled
# bytes attributable to the class. Aggregating weight gives the per-class
# sampled allocation rate; aggregating count gives sample density.
TMP=$(mktemp)
"$JFR_TOOL" print --events 'jdk.ObjectAllocationSample' --json "$JFR" > "$TMP"
python3 - "$TMP" "$N" "$OUT" <<'PY'
import json, sys, collections
tmp_path, n_str, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
N = int(n_str)
with open(tmp_path) as f:
    data = json.load(f)
events = data.get("recording", {}).get("events", []) or data.get("events", [])
bytes_per = collections.Counter()
count_per = collections.Counter()
for e in events:
    v = e.get("values", e)
    cls = v.get("objectClass", {})
    name = cls.get("name") if isinstance(cls, dict) else str(cls)
    if not name:
        name = "<unknown>"
    w = int(v.get("weight", 0))
    bytes_per[name] += w
    count_per[name] += 1
total_bytes = sum(bytes_per.values())
total_samples = sum(count_per.values())
out = open(out_path, "w") if out_path != "/dev/stdout" else sys.stdout
print(f"# ObjectAllocationSample events: {total_samples}  total weighted bytes: {total_bytes:,}", file=out)
print(file=out)
print(f"{'class':70} {'samples':>10} {'pct_smp':>7} {'weighted_bytes':>16} {'pct_bytes':>9}", file=out)
print("-" * 116, file=out)
for name, b in bytes_per.most_common(N):
    pct_b = 100.0 * b / total_bytes if total_bytes else 0.0
    pct_s = 100.0 * count_per[name] / total_samples if total_samples else 0.0
    print(f"{name[:70]:70} {count_per[name]:>10d} {pct_s:>6.1f}% {b:>16,d} {pct_b:>8.1f}%", file=out)
if out is not sys.stdout:
    out.close()
PY
rm -f "$TMP"
