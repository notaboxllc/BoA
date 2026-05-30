#!/usr/bin/env python3
"""Iter2b ensemble analysis: compare CPU vs GPU 10-seed runs on
glidingAssay500_val using the cSEM test.

Input: RUN_LOGS/2026-05-29_iter2b-validation.txt with lines
    mode seed bindEvents meanBoundMotors glidingVelocity wall_sec
"""
import math
import sys
from pathlib import Path

src = Path("RUN_LOGS/2026-05-29_iter2b-validation.txt")
if len(sys.argv) > 1:
    src = Path(sys.argv[1])

rows = {"cpu": [], "gpu": []}
for line in src.read_text().splitlines():
    parts = line.split()
    if len(parts) < 6:
        continue
    mode = parts[0]
    if mode not in rows:
        continue
    try:
        seed = int(parts[1])
        be   = float(parts[2])
        mb   = float(parts[3])
        gv   = float(parts[4])
        wall = float(parts[5])
    except ValueError:
        continue
    rows[mode].append((seed, be, mb, gv, wall))

def stats(vals):
    n = len(vals)
    if n == 0:
        return float("nan"), float("nan"), float("nan"), 0
    m = sum(vals) / n
    if n < 2:
        return m, 0.0, 0.0, n
    sd = math.sqrt(sum((v - m) ** 2 for v in vals) / (n - 1))
    sem = sd / math.sqrt(n)
    return m, sem, sd, n

def report(name, idx):
    c_vals = [r[idx] for r in rows["cpu"]]
    g_vals = [r[idx] for r in rows["gpu"]]
    cm, cse, csd, cn = stats(c_vals)
    gm, gse, gsd, gn = stats(g_vals)
    csem = math.sqrt(cse * cse + gse * gse) if cn > 0 and gn > 0 else float("nan")
    diff = abs(gm - cm)
    z    = diff / csem if csem > 0 else float("inf")
    verdict = "PASS" if z < 2.0 else "FAIL"
    print(f"{name:<18} | CPU {cm:8.3f} ± {cse:6.3f} (SD {csd:6.3f}) | "
          f"GPU {gm:8.3f} ± {gse:6.3f} (SD {gsd:6.3f}) | "
          f"|diff|/cSEM = {z:5.2f} | {verdict}")
    return verdict

print(f"n_cpu={len(rows['cpu'])} n_gpu={len(rows['gpu'])}")
print("-" * 110)
v1 = report("bindEvents",      1)
v2 = report("meanBoundMotors", 2)
v3 = report("velocity (µm/s)", 3)
v4 = report("wall (s/seed)",   4)
print("-" * 110)

# Walls
c_walls = [r[4] for r in rows["cpu"]]
g_walls = [r[4] for r in rows["gpu"]]
if c_walls and g_walls:
    ratio = (sum(g_walls)/len(g_walls)) / (sum(c_walls)/len(c_walls))
    print(f"\nWall-clock ratio (GPU / CPU): {ratio:.3f}x (GPU slower if > 1)")

# Per-seed raw table
print("\n---")
print("Per-seed raw values:")
print("MODE seed bindEvents meanBoundMotors velocity_um_per_s wall_sec")
for mode in ("cpu", "gpu"):
    for r in rows[mode]:
        print(f"{mode}  {r[0]:<3d} {int(r[1]):>4d} {r[2]:6.3f} {r[3]:7.4f} {r[4]:8.3f}")

verdicts = [v1, v2, v3]
print()
print("OBSERVABLE VERDICT:",
      "ALL PASS" if all(v == "PASS" for v in verdicts) else "AT LEAST ONE FAILED",
      f" ({', '.join(verdicts)})")
