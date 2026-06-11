#!/usr/bin/env python3
# theta_LM = angle(lever.uVec, motor.uVec); theta_RL = angle(rod.uVec, lever.uVec)
# Per-frame mean over all myosins; report early (first 5 frames) and steady (last 20) windows.
import json, glob, math, sys, os

def uvec(part):
    e1, e2 = part["end1"], part["end2"]
    d = [e2[i]-e1[i] for i in range(3)]
    n = math.sqrt(sum(c*c for c in d))
    return [c/n for c in d] if n > 0 else None

def ang(a, b):
    if a is None or b is None: return None
    d = max(-1.0, min(1.0, sum(a[i]*b[i] for i in range(3))))
    return math.degrees(math.acos(d))

def frame_mean(path):
    j = json.load(open(path))
    lm, rl = [], []
    for m in j.get("myosins", []):
        lu, mu, ru = uvec(m["lever"]), uvec(m["motor"]), uvec(m["rod"])
        a = ang(lu, mu); b = ang(ru, lu)
        if a is not None: lm.append(a)
        if b is not None: rl.append(b)
    n = len(lm)
    return (sum(lm)/n if n else None, sum(rl)/len(rl) if rl else None, n)

def run(d):
    fs = sorted(glob.glob(os.path.join(d, "frame_*.json")))
    rows = [frame_mean(f) for f in fs]
    return fs, rows

for label, d in [("GPU", sys.argv[1]), ("CPU", sys.argv[2])]:
    fs, rows = run(d)
    lm = [r[0] for r in rows if r[0] is not None]
    rl = [r[1] for r in rows if r[1] is not None]
    nmy = rows[len(rows)//2][2] if rows else 0
    def wmean(v, a, b):
        s = v[a:b]; return sum(s)/len(s) if s else float('nan')
    early_lm = wmean(lm, 0, 5); steady_lm = wmean(lm, -20, len(lm))
    early_rl = wmean(rl, 0, 5); steady_rl = wmean(rl, -20, len(rl))
    print(f"{label}: nframes={len(fs)} nMyosins/frame~{nmy}")
    print(f"  theta_LM: early(0-5)={early_lm:.1f}  steady(last20)={steady_lm:.1f}  "
          f"min={min(lm):.1f} max={max(lm):.1f}")
    print(f"  theta_RL: early(0-5)={early_rl:.1f}  steady(last20)={steady_rl:.1f}")
