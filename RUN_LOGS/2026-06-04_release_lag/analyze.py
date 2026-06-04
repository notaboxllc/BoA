#!/usr/bin/env python3
# Three-arm paired analysis for the release-lag confirmation diagnostic
# (2026-06-04). Reads results.csv produced by triple_release_lag_gliding.sh.
# Three arms keyed by name: device, freshCPU, laggedCPU. For each ordered
# pair, computes per-seed deltas + paired t for bindEvents, meanBoundMotors,
# glidingVelocity. Applies the verdict logic from the diagnostic prompt:
#   - hypothesis CONFIRMED if (laggedCPU vs device) collapses to t ~ 0 AND
#     (freshCPU vs laggedCPU) reproduces the signature (down on bindEvents
#     and gv, clean on mbm).
#   - PARTIAL if laggedCPU vs device still shows residual divergence — the
#     release lag is only part of it.
import csv, math, statistics, sys, pathlib

p = pathlib.Path(__file__).parent / "results.csv"
rows = list(csv.DictReader(p.open()))
arms = {"device": {}, "freshCPU": {}, "laggedCPU": {}}
for r in rows:
    a = r["arm"]
    if a in arms:
        arms[a][int(r["seed"])] = r
seeds = sorted(set(arms["device"]) & set(arms["freshCPU"]) & set(arms["laggedCPU"]))
N = len(seeds)
print(f"=== Three-arm release-lag confirmation ensemble (N={N}) ===\n")

OBS = ["bindEvents", "meanBoundMotors", "glidingVelocity"]

def get(arm, seed, name):
    v = arms[arm][seed][name]
    if name == "bindEvents":
        return int(v) if v not in (None, "", "NaN") else float("nan")
    return float(v) if v not in (None, "", "NaN") else float("nan")

def stats(vals):
    vs = [v for v in vals if not (isinstance(v, float) and math.isnan(v))]
    if not vs:
        return float("nan"), float("nan"), float("nan")
    m = statistics.fmean(vs)
    s = statistics.stdev(vs) if len(vs) > 1 else 0.0
    sem = s / math.sqrt(len(vs)) if len(vs) > 0 else 0.0
    return m, s, sem

def verdict(t):
    a = abs(t)
    if a < 1.0: return "no bias"
    if a < 2.0: return "mild scatter"
    if a < 3.0: return "borderline (~2σ)"
    return "BIAS (≥3σ)"

# Ensemble distributions
print("=== Ensemble distributions (independent) ===")
print(f"{'observable':>18} | {'arm':>10} | {'mean':>10} {'SD':>10} {'SEM':>10}")
print("-" * 70)
arm_stats = {}
for name in OBS:
    for arm in ("device", "freshCPU", "laggedCPU"):
        vs = [get(arm, s, name) for s in seeds]
        m, s, sem = stats(vs)
        arm_stats[(name, arm)] = (m, s, sem)
        print(f"{name:>18} | {arm:>10} | {m:>10.4f} {s:>10.4f} {sem:>10.4f}")
print()

# Per-pair paired deltas + paired t
PAIRS = [
    ("device",    "freshCPU",  "device − freshCPU"),
    ("freshCPU",  "laggedCPU", "freshCPU − laggedCPU"),
    ("laggedCPU", "device",    "laggedCPU − device"),
]
print("=== Pairwise paired deltas (per-seed) ===")
print(f"{'pair':>22} | {'observable':>18} | {'mean Δ':>10} {'SD(Δ)':>10} {'SEM(Δ)':>9} {'paired t':>10} {'verdict':>18}")
print("-" * 100)
PAIR_TS = {}
for (A, B, label) in PAIRS:
    PAIR_TS[label] = {}
    for name in OBS:
        deltas = []
        for s in seeds:
            a = get(A, s, name); b = get(B, s, name)
            if any(isinstance(x, float) and math.isnan(x) for x in (a, b)): continue
            deltas.append(a - b)
        if not deltas:
            print(f"{label:>22} | {name:>18} | {'NaN':>+10}")
            continue
        mD, sD, sEM = stats(deltas)
        t = mD / sEM if sEM > 0 else float("inf") if mD != 0 else 0.0
        PAIR_TS[label][name] = (mD, t)
        print(f"{label:>22} | {name:>18} | {mD:>+10.4f} {sD:>10.4f} {sEM:>9.4f} {t:>+10.3f} {verdict(t):>18}")
print()

# Apply the verdict logic from the diagnostic prompt
def t_or(p, o): return PAIR_TS.get(p, {}).get(o, (float("nan"), float("nan")))[1]

t_lag_dev_bind = t_or("laggedCPU − device",    "bindEvents")
t_lag_dev_gv   = t_or("laggedCPU − device",    "glidingVelocity")
t_lag_dev_mbm  = t_or("laggedCPU − device",    "meanBoundMotors")
t_fresh_lag_bind = t_or("freshCPU − laggedCPU", "bindEvents")
t_fresh_lag_gv   = t_or("freshCPU − laggedCPU", "glidingVelocity")
t_fresh_lag_mbm  = t_or("freshCPU − laggedCPU", "meanBoundMotors")

def clean(t):  return abs(t) < 1.0
def signif(t): return abs(t) >= 2.0

print("=== Verdict logic ===")
collapse_ok = (clean(t_lag_dev_bind) or abs(t_lag_dev_bind) < 1.5) and \
              (clean(t_lag_dev_gv)   or abs(t_lag_dev_gv) < 1.5)
sig_reproduced = (t_fresh_lag_bind > 1.5) and (t_fresh_lag_gv > 1.5) and clean(t_fresh_lag_mbm)
# the sign convention: fresh − lagged should be POSITIVE on bindEvents/gv if the
# lag drags those down (i.e. lagged is lower than fresh → fresh − lagged > 0)
print(f"  laggedCPU vs device:  t(bindEv)={t_lag_dev_bind:+.2f}, t(gv)={t_lag_dev_gv:+.2f}, t(mbm)={t_lag_dev_mbm:+.2f}  → collapsed? {collapse_ok}")
print(f"  freshCPU vs laggedCPU: t(bindEv)={t_fresh_lag_bind:+.2f}, t(gv)={t_fresh_lag_gv:+.2f}, t(mbm)={t_fresh_lag_mbm:+.2f}  → signature reproduced? {sig_reproduced}")
if collapse_ok and sig_reproduced:
    print("  VERDICT: hypothesis CONFIRMED — release-lag explains the device borderline.")
elif collapse_ok and not sig_reproduced:
    print("  VERDICT: partial — collapse seen but fresh-vs-lagged signature did not match (re-examine).")
elif not collapse_ok and sig_reproduced:
    print("  VERDICT: PARTIAL — lag reproduces the signature on CPU, but laggedCPU vs device still shows residual: another effect remains.")
else:
    print("  VERDICT: NOT CONFIRMED — neither collapse nor signature reproduction. Hypothesis fails.")
print()

# Wall summary
walls = {arm: [int(arms[arm][s]["wall_sec"]) for s in seeds] for arm in arms}
totals = sum(sum(v) for v in walls.values()) / 60
mw = {arm: statistics.fmean(walls[arm]) for arm in arms}
print(f"Wall (s): device mean={mw['device']:.1f}  freshCPU mean={mw['freshCPU']:.1f}  laggedCPU mean={mw['laggedCPU']:.1f}  total ensemble wall ≈ {totals:.1f} min")
