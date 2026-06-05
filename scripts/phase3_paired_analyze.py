#!/usr/bin/env python3
"""Paired-t analysis for the Phase 3 N=8 ensemble.

Reads RUN_LOGS/2026-06-04_phase3_ensemble/results.csv with columns
    arm,seed,bindEvents,meanBoundMotors,glidingVelocity,wall_sec
where arm is `device` (device F8/F9/F10 + device binding grid) or `cpu`
(CPU F8/F9/F10 + device binding grid). The Phase 3 device binding grid
is active in BOTH arms — this ensemble validates that the new binding
grid does not introduce population-level divergence under either motor
force regime.

Per the Phase 3 protocol: success = all |t| <= 1-2 AND per-seed paired
differences scatter in sign (not all-same-sign — that would be a clean-
noise signature failure).
"""
import csv
import math
import sys
from pathlib import Path
from collections import defaultdict

src = Path("RUN_LOGS/2026-06-04_phase3_ensemble/results.csv")
if len(sys.argv) > 1:
    src = Path(sys.argv[1])
if not src.exists():
    print(f"results.csv not found: {src}", file=sys.stderr)
    sys.exit(1)

rows = defaultdict(dict)   # rows[seed] = {arm: dict}
with src.open() as fh:
    reader = csv.DictReader(fh)
    for r in reader:
        try:
            seed = int(r["seed"])
            arm  = r["arm"]
        except (KeyError, ValueError):
            continue
        rows[seed][arm] = {
            "bindEvents":       float(r.get("bindEvents") or "nan"),
            "meanBoundMotors":  float(r.get("meanBoundMotors") or "nan"),
            "glidingVelocity":  float(r.get("glidingVelocity") or "nan"),
            "wall_sec":         float(r.get("wall_sec") or "nan"),
        }

# Keep only seeds with both arms.
paired_seeds = sorted(s for s, d in rows.items() if "device" in d and "cpu" in d)
print(f"Paired seeds: {paired_seeds}  (N={len(paired_seeds)})")
print()

def paired_t(label, key):
    diffs = []
    for s in paired_seeds:
        d = rows[s]["device"][key] - rows[s]["cpu"][key]
        diffs.append(d)
    n = len(diffs)
    if n < 2 or any(math.isnan(d) for d in diffs):
        print(f"{label}: insufficient data")
        return
    m = sum(diffs) / n
    sd = math.sqrt(sum((x - m) ** 2 for x in diffs) / (n - 1))
    sem = sd / math.sqrt(n)
    t = m / sem if sem > 0 else float("inf")
    signs = "".join("+" if d > 0 else ("-" if d < 0 else "0") for d in diffs)
    print(f"{label:<18} mean diff={m:+9.4f}  sd={sd:7.4f}  sem={sem:7.4f}  t={t:+6.2f}  signs={signs}")
    print("                   per-seed diffs:  " +
          "  ".join(f"s{paired_seeds[i]}={diffs[i]:+8.4f}" for i in range(n)))

print("PAIRED-T (device arm - cpu arm) — both arms run -gpu + Phase 3 device binding grid")
print("                    " + "-" * 92)
paired_t("bindEvents",      "bindEvents")
paired_t("meanBoundMotors", "meanBoundMotors")
paired_t("glidingVelocity", "glidingVelocity")
print()
print("Per-seed raw observables:")
print(f"  {'seed':>4} {'be_dev':>8} {'be_cpu':>8} {'mb_dev':>8} {'mb_cpu':>8} {'gv_dev':>8} {'gv_cpu':>8}")
for s in paired_seeds:
    d = rows[s]["device"]; c = rows[s]["cpu"]
    print(f"  {s:>4} {d['bindEvents']:>8.0f} {c['bindEvents']:>8.0f} "
          f"{d['meanBoundMotors']:>8.3f} {c['meanBoundMotors']:>8.3f} "
          f"{d['glidingVelocity']:>8.3f} {c['glidingVelocity']:>8.3f}")
