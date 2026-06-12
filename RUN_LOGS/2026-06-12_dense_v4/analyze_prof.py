#!/usr/bin/env python3
"""Aggregate Part E TornadoVM profiler JSON: per-task TASK_KERNEL_TIME (ms/execute),
graph-level COPY_IN/COPY_OUT time + bytes, ranked, with 1x->4x->8x slope."""
import json, re, glob, os
from collections import defaultdict

WD = os.path.dirname(os.path.abspath(__file__))
PROF = f"{WD}/prof"
WARM = 15  # skip first N executes (compile / cache warmup)

def load(path):
    txt = open(path).read(); txt = re.sub(r',(\s*[}\]])', r'\1', txt)
    dec = json.JSONDecoder(); i = 0; n = len(txt); objs = []
    while i < n:
        while i < n and txt[i] in ' \t\r\n': i += 1
        if i >= n: break
        try: o, j = dec.raw_decode(txt, i)
        except Exception: break
        objs.append(o); i = j
    return objs

def per_run(path):
    objs = load(path)
    graphs = [o["chained"] for o in objs if "chained" in o][WARM:]
    if not graphs: return None
    ktime = defaultdict(float); cnt = 0
    copin = copout = wall = totk = cinB = coutB = allocB = 0.0
    for g in graphs:
        for k, v in g.items():
            if k.startswith("chained.") and isinstance(v, dict) and "TASK_KERNEL_TIME" in v:
                ktime[k[len("chained."):]] += int(v["TASK_KERNEL_TIME"]) / 1e6
        copin  += int(g.get("COPY_IN_TIME", 0)) / 1e6
        copout += int(g.get("COPY_OUT_TIME", 0)) / 1e6
        wall   += int(g.get("TOTAL_TASK_GRAPH_TIME", 0)) / 1e6
        totk   += int(g.get("TOTAL_KERNEL_TIME", 0)) / 1e6
        cinB   += int(g.get("TOTAL_COPY_IN_SIZE_BYTES", 0))
        coutB  += int(g.get("TOTAL_COPY_OUT_SIZE_BYTES", 0))
        allocB += int(g.get("ALLOCATION_BYTES", 0))
        cnt += 1
    f = 1.0 / cnt
    return ({k: v * f for k, v in ktime.items()},
            copin*f, copout*f, wall*f, totk*f, cinB*f, coutB*f, allocB*f, cnt)

def agg(scale):
    runs = [per_run(p) for p in sorted(glob.glob(f"{PROF}/{scale}x_r*.json"))]
    runs = [r for r in runs if r]
    if not runs: return None
    allk = set(); [allk.update(r[0]) for r in runs]
    m = len(runs)
    kt = {k: sum(r[0].get(k, 0) for r in runs)/m for k in allk}
    agv = lambda i: sum(r[i] for r in runs)/m
    return kt, agv(1), agv(2), agv(3), agv(4), agv(5), agv(6), agv(7), runs[0][8], m

data = {}
for s in ["1", "4", "8"]:
    r = agg(s)
    if r: data[s] = r

print("=== Part E: per-task TASK_KERNEL_TIME (ms/execute, profiler, warmup-skip %d) ===" % WARM)
for s in ["1", "4", "8"]:
    if s not in data: print(f"\n## {s}x: (no data)"); continue
    kt, cin, cout, wall, totk, cinB, coutB, allocB, n, m = data[s]
    print(f"\n## {s}x  (runs={m}, executes/run~{n})")
    print(f"   graph wall={wall:.2f}ms  totalKernel={totk:.2f}ms  copyIn={cin:.3f}ms copyOut={cout:.3f}ms")
    print(f"   copyInBytes={cinB/1e6:.2f}MB copyOutBytes={coutB/1e6:.2f}MB allocBytes={allocB/1e6:.1f}MB")
    for k in sorted(kt, key=lambda x: -kt[x]):
        print(f"     {k:18s} {kt[k]:8.3f} ms")

# slope table: kernel time at 1x/4x/8x and ratio 8x/1x (linear weak-scaling => ~8)
print("\n=== kernel slope (ms/execute) 1x -> 4x -> 8x, ratio 8x/1x ===")
allk = set()
for s in data: allk.update(data[s][0])
print(f"   {'kernel':18s} {'1x':>9s} {'4x':>9s} {'8x':>9s} {'8x/1x':>7s} {'4x/1x':>7s}")
def kv(s, k): return data[s][0].get(k, 0) if s in data else 0
for k in sorted(allk, key=lambda x: -kv("8", x)):
    v1, v4, v8 = kv("1", k), kv("4", k), kv("8", k)
    r8 = v8/v1 if v1 > 1e-9 else float('nan')
    r4 = v4/v1 if v1 > 1e-9 else float('nan')
    print(f"   {k:18s} {v1:9.3f} {v4:9.3f} {v8:9.3f} {r8:7.2f} {r4:7.2f}")
