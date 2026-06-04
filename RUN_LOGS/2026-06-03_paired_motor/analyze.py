#!/usr/bin/env python3
# Paired analysis of the motor-isolation device-vs-CPU gliding ensemble
# (Phase 2 F8/F9/F10). Mirrors the analyzer of
# 2026-06-03_paired_devVsCpu/analyze.py but compares ONLY the motor port:
# both arms run -gpu with the four PRIOR ported forces (anchor + F3/F4 + F1 +
# tipC writeback) on device; the cpu arm sets BOA_DIAG_CPU_MOTOR=1 to route
# F8/F9/F10 through the CPU MyoFilLink pair.
import csv, math, statistics, sys, pathlib

p = pathlib.Path(__file__).parent / "results.csv"
rows = list(csv.DictReader(p.open()))
device = {int(r["seed"]): r for r in rows if r["arm"] == "device"}
cpu    = {int(r["seed"]): r for r in rows if r["arm"] == "cpu"}
seeds  = sorted(set(device) & set(cpu))
N = len(seeds)
print(f"=== Motor-isolation paired device-vs-CPU gliding ensemble (N={N}) ===\n")

OBS = [("bindEvents",      "%d",   int),
       ("meanBoundMotors", "%.3f", float),
       ("glidingVelocity", "%.4f", float)]

def stats(vals):
    m = statistics.fmean(vals)
    s = statistics.stdev(vals) if len(vals) > 1 else 0.0
    sem = s / math.sqrt(len(vals)) if len(vals) > 0 else 0.0
    return m, s, sem

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

# Independent-comparison verdict
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

# Wall-time summary
walls_d = [int(device[s]["wall_sec"]) for s in seeds]
walls_c = [int(cpu[s]["wall_sec"])    for s in seeds]
mwd, _, _ = stats(walls_d)
mwc, _, _ = stats(walls_c)
print(f"Wall (s): device mean={mwd:.1f}  cpu mean={mwc:.1f}  total ensemble wall ≈ {(sum(walls_d)+sum(walls_c))/60:.1f} min")
