#!/usr/bin/env python3
# Paired analysis of the 64-mon device-vs-CPU gliding ensemble.
import csv, math, statistics, sys, pathlib

p = pathlib.Path(__file__).parent / "results.csv"
rows = list(csv.DictReader(p.open()))
device = {int(r["seed"]): r for r in rows if r["arm"] == "device"}
cpu    = {int(r["seed"]): r for r in rows if r["arm"] == "cpu"}
seeds  = sorted(set(device) & set(cpu))
N = len(seeds)
assert N == 20, f"expected 20 paired seeds, got {N}"

OBS = [("bindEvents",      "%d",   int),
       ("meanBoundMotors", "%.3f", float),
       ("glidingVelocity", "%.4f", float)]

def stats(vals):
    m = statistics.fmean(vals)
    s = statistics.stdev(vals) if len(vals) > 1 else 0.0
    sem = s / math.sqrt(len(vals))
    return m, s, sem

print(f"=== 64-mon paired device-vs-CPU gliding ensemble (N={N}) ===\n")

# Per-seed deltas table
print("=== Per-seed paired deltas (device − cpu) ===")
hdr = f"{'seed':>5} | {'bindEv_D':>9} {'bindEv_C':>9} {'Δbind':>8} | {'mbm_D':>7} {'mbm_C':>7} {'Δmbm':>7} | {'gv_D':>7} {'gv_C':>7} {'Δgv':>8}"
print(hdr); print("-" * len(hdr))
deltas = {n: [] for n,_,_ in OBS}
for s in seeds:
    d = device[s]; c = cpu[s]
    dbe = int(d["bindEvents"])   - int(c["bindEvents"])
    dmb = float(d["meanBoundMotors"]) - float(c["meanBoundMotors"])
    dgv = float(d["glidingVelocity"]) - float(c["glidingVelocity"])
    deltas["bindEvents"].append(dbe)
    deltas["meanBoundMotors"].append(dmb)
    deltas["glidingVelocity"].append(dgv)
    print(f"{s:>5} | {int(d['bindEvents']):>9d} {int(c['bindEvents']):>9d} {dbe:>+8d} | "
          f"{float(d['meanBoundMotors']):>7.3f} {float(c['meanBoundMotors']):>7.3f} {dmb:>+7.3f} | "
          f"{float(d['glidingVelocity']):>7.4f} {float(c['glidingVelocity']):>7.4f} {dgv:>+8.4f}")
print()

# Paired-delta bias analysis
print("=== Paired-delta bias (device − cpu, per-seed) ===")
print(f"{'observable':>20} | {'mean Δ':>10} {'SD(Δ)':>10} {'SEM(Δ)':>9} {'paired t':>10} {'verdict':>22}")
print("-" * 90)
for name, _, _ in OBS:
    vs = deltas[name]
    mD, sD, sEM = stats(vs)
    t = mD / sEM if sEM > 0 else float("inf")
    if abs(t) < 1.0:
        v = "no bias"
    elif abs(t) < 2.0:
        v = "mild scatter"
    elif abs(t) < 3.0:
        v = "borderline (~2σ)"
    else:
        v = "BIAS (≥3σ)"
    print(f"{name:>20} | {mD:>+10.4f} {sD:>10.4f} {sEM:>9.4f} {t:>+10.3f} {v:>22}")
print()

# Ensemble distributions
print("=== Ensemble distributions (independent) ===")
print(f"{'observable':>20} | {'arm':>6} | {'mean':>10} {'SD':>10} {'SEM':>10}")
print("-" * 70)
arm_stats = {}
for name, _, _ in OBS:
    for arm, table in (("device", device), ("cpu", cpu)):
        vs = [float(table[s][name]) for s in seeds]
        m, s, sem = stats(vs)
        arm_stats[(name, arm)] = (m, s, sem)
        print(f"{name:>20} | {arm:>6} | {m:>10.4f} {s:>10.4f} {sem:>10.4f}")
print()

# Independent-comparison verdict (parallel to Phase-1 table style)
print("=== Independent device-vs-cpu comparison (|mean_d - mean_c| / cSEM) ===")
print(f"{'observable':>20} | {'mean_dev':>10} {'mean_cpu':>10} {'diff':>10} {'cSEM':>10} {'|d|/cSEM':>10} {'verdict':>12}")
print("-" * 95)
for name, _, _ in OBS:
    md, sd, semd = arm_stats[(name, "device")]
    mc, sc, semc = arm_stats[(name, "cpu")]
    diff = md - mc
    csem = math.sqrt(semd**2 + semc**2)
    z = diff / csem if csem > 0 else float("inf")
    v = "PASS" if abs(z) < 2.0 else ("border" if abs(z) < 3.0 else "FAIL")
    print(f"{name:>20} | {md:>10.4f} {mc:>10.4f} {diff:>+10.4f} {csem:>10.4f} {z:>+10.3f} {v:>12}")
print()

# Compare to Phase-1 baseline (CPU-arm post-fix 10-seed ensemble values cited 856.80/7.30/8.22)
PHASE1 = {"bindEvents": (856.80, 45.72), "meanBoundMotors": (7.30, 0.30), "glidingVelocity": (8.22, 0.15)}
print("=== Phase-2 device arm vs Phase-1 post-fix CPU baseline (cited 856.80 ± 45.72 / 7.30 ± 0.30 / 8.22 ± 0.15) ===")
print(f"{'observable':>20} | {'devN20 mean':>12} {'devN20 SEM':>11} {'P1 mean':>10} {'P1 SEM(n=10)':>13} {'|d|/cSEM':>10}")
print("-" * 95)
for name, _, _ in OBS:
    md, sd, semd = arm_stats[(name, "device")]
    p1m, p1sem = PHASE1[name]
    csem = math.sqrt(semd**2 + p1sem**2)
    z = (md - p1m) / csem if csem > 0 else float("inf")
    print(f"{name:>20} | {md:>12.4f} {semd:>11.4f} {p1m:>10.4f} {p1sem:>13.4f} {z:>+10.3f}")
print()

# CPU-arm vs Phase-1 same RNG, same baseline frame (this is the Phase-1 2σ resolution question)
print("=== Phase-2 CPU arm vs Phase-1 post-fix CPU baseline (closes the Phase-1 2σ question) ===")
print(f"{'observable':>20} | {'cpuN20 mean':>12} {'cpuN20 SEM':>11} {'P1 mean':>10} {'P1 SEM(n=10)':>13} {'|d|/cSEM':>10}")
print("-" * 95)
for name, _, _ in OBS:
    mc, sc, semc = arm_stats[(name, "cpu")]
    p1m, p1sem = PHASE1[name]
    csem = math.sqrt(semc**2 + p1sem**2)
    z = (mc - p1m) / csem if csem > 0 else float("inf")
    print(f"{name:>20} | {mc:>12.4f} {semc:>11.4f} {p1m:>10.4f} {p1sem:>13.4f} {z:>+10.3f}")
print()

# Wall-time summary
walls_d = [int(device[s]["wall_sec"]) for s in seeds]
walls_c = [int(cpu[s]["wall_sec"])    for s in seeds]
mwd, _, _ = stats(walls_d)
mwc, _, _ = stats(walls_c)
print(f"Wall (s): device mean={mwd:.1f}  cpu mean={mwc:.1f}  total ensemble wall ≈ {(sum(walls_d)+sum(walls_c))/60:.1f} min")
