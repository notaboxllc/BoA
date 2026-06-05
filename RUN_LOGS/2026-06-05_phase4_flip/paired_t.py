"""Paired-t analysis of baseline-vs-flip observables across N seeds."""
import math, re, sys, os, glob

def parse(file):
    out = {}
    with open(file) as f:
        for line in f:
            m = re.match(r'\[STATS\] (\w+)=(-?[0-9.]+)', line)
            if m: out[m.group(1)] = float(m.group(2))
    return out

logdir = sys.argv[1] if len(sys.argv) > 1 else 'RUN_LOGS/2026-06-05_phase4_flip'
metrics = ['bindEvents', 'meanBoundMotors', 'glidingVelocity']
seeds = [1, 2, 3, 4]

base, flip = {}, {}
for s in seeds:
    b = parse(f'{logdir}/baseline_seed{s}.log')
    f = parse(f'{logdir}/flip_seed{s}.log')
    base[s] = b; flip[s] = f

print(f"{'metric':18s} {'seed':4s} {'base':>10s} {'flip':>10s} {'delta':>10s} {'delta/base%':>10s}")
print("-" * 70)
all_deltas = {m: [] for m in metrics}
for m in metrics:
    for s in seeds:
        b = base[s].get(m); f = flip[s].get(m)
        if b is None or f is None: continue
        d = f - b
        all_deltas[m].append((b, f, d))
        print(f"{m:18s} {s:<4d} {b:>10.3f} {f:>10.3f} {d:>+10.3f} {100*d/b:>+9.2f}%")
    print()

print("\n=== paired t-test (N=4) ===")
print(f"{'metric':18s} {'mean_delta':>12s} {'sd_delta':>12s} {'t':>8s} {'mean_pct':>10s}")
print("-" * 65)
for m in metrics:
    deltas = [d for _,_,d in all_deltas[m]]
    bases = [b for b,_,_ in all_deltas[m]]
    if len(deltas) < 2: continue
    n = len(deltas)
    md = sum(deltas)/n
    var = sum((x - md)**2 for x in deltas) / (n-1)
    sd = math.sqrt(var)
    se = sd / math.sqrt(n)
    t = md / se if se > 0 else float('inf')
    pct = 100 * md / (sum(bases)/n)
    print(f"{m:18s} {md:>+12.4f} {sd:>12.4f} {t:>+8.3f} {pct:>+9.2f}%")
