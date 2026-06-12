#!/usr/bin/env python3
"""Build Part B/C/D tables from bcd_*.log + .rss + .vram."""
import re, glob, os
WD = os.path.dirname(os.path.abspath(__file__))
SCALES = ["0p5", "1", "2", "4", "8"]

def fmt(x, n=2):
    return f"{x:.{n}f}" if isinstance(x,(int,float)) else "NA"

def gv(txt, pat, grp=1, cast=float):
    m = re.search(pat, txt, re.M)
    return cast(m.group(grp)) if m else None

def parse_log(path):
    if not os.path.exists(path): return None
    t = open(path).read()
    d = {}
    d["msstep"] = gv(t, r"STEP_PROFILE\] window.*?\(([\d.]+) ms/step\)")
    for ph in ["exec", "pack", "crosslinkForce", "meshFill"]:
        d[ph] = gv(t, rf"^\s+{ph}\s+([\d.]+)", 1)
    d["cpuIntegrate"] = gv(t, r"cpuIntegrate\s+([\d.]+)")
    d["step"] = gv(t, r"^\s+step\s+([\d.]+)")
    d["gather"] = gv(t, r"gatherForces\s+([\d.]+)")
    d["brownian"] = gv(t, r"^\s+brownian\s+([\d.]+)")
    d["biochem"] = gv(t, r"biochem\s+([\d.]+) \|")
    d["sync"] = gv(t, r"\n\s+sync\s+([\d.]+) \|")
    d["host"] = gv(t, r"host\(=tot-exec\)\s+([\d.]+)")
    d["other"] = gv(t, r"other\(unlabeled\)\s+([\d.]+)")
    d["active"] = gv(t, r"activeLinks=(\d+)", 1, int)
    d["linkSettled"] = gv(t, r"linkCtMeanSettled=([\d.]+)")
    d["slotCap"] = gv(t, r"slotCap=(\d+)", 1, int)
    d["myoCap"] = gv(t, r"myoCap=(\d+)", 1, int)
    d["usedHeap"] = gv(t, r"usedHeapMB=([\d.]+)")
    d["devBufEst"] = gv(t, r"devBufEstMB=([\d.]+)")
    d["overflow"] = gv(t, r"overflowSegs=(\d+)", 1, int)
    d["planRebuild"] = gv(t, r"planRebuild=(\d+)", 1, int)
    d["fActin"] = gv(t, r"fActinUM=([\d.]+)")
    d["segs"] = gv(t, r"fActin segs=(\d+)", 1, int)
    d["nanflag"] = ("NaN" in t and "NaN=0" not in t)
    return d

def rss_mb(path):
    if not os.path.exists(path): return None
    m = re.search(r"Maximum resident set size \(kbytes\): (\d+)", open(path).read())
    return int(m.group(1))/1024 if m else None

def vram_mb(path):
    if not os.path.exists(path): return None
    try: return float(open(path).read().strip())
    except: return None

rows = {}
for s in SCALES:
    for p in ["cpu", "gpu"]:
        rows[(s, p)] = {
            "d": parse_log(f"{WD}/bcd_{s}_{p}.log"),
            "rss": rss_mb(f"{WD}/bcd_{s}_{p}.rss"),
            "vram": vram_mb(f"{WD}/bcd_{s}_{p}.vram"),
        }

print("=== Part B: speed (ms/step) + GPU/CPU ===")
print(f"{'scale':>6} {'CPU':>10} {'GPU':>10} {'GPU/CPU':>8}  {'activeLinks(cpu/gpu)':>20}")
for s in SCALES:
    c = rows[(s,"cpu")]["d"]; g = rows[(s,"gpu")]["d"]
    cm = c["msstep"] if c else None; gm = g["msstep"] if g else None
    r = (gm/cm) if (cm and gm) else None
    al = f"{c['active'] if c else '-'}/{g['active'] if g else '-'}"
    print(f"{s:>6} {fmt(cm):>10} {fmt(gm):>10} {fmt(r,2):>8}  {al:>20}")

print("\n=== Part C: host-phase ms/step (GPU path) ===")
print(f"{'scale':>6} {'exec':>8} {'pack':>7} {'xlinkF':>7} {'mesh':>6} {'step':>6} {'gather':>6} {'brown':>6} {'biochem':>7} {'sync':>6} {'host':>7} {'other':>7}")
for s in SCALES:
    g = rows[(s,"gpu")]["d"]
    if not g: print(f"{s:>6}  (no data)"); continue
    print(f"{s:>6} {fmt(g['exec']):>8} {fmt(g['pack']):>7} {fmt(g['crosslinkForce']):>7} {fmt(g['meshFill']):>6} {fmt(g['step']):>6} {fmt(g['gather']):>6} {fmt(g['brownian']):>6} {fmt(g['biochem']):>7} {fmt(g['sync']):>6} {fmt(g['host']):>7} {fmt(g['other']):>7}")

print("\n=== Part C: host-phase ms/step (CPU path) ===")
for s in SCALES:
    c = rows[(s,"cpu")]["d"]
    if not c: print(f"{s:>6}  (no data)"); continue
    print(f"{s:>6} cpuIntegrate={fmt(c['cpuIntegrate'])} step={fmt(c['step'])} gather={fmt(c['gather'])} brown={fmt(c['brownian'])} xlinkF={fmt(c['crosslinkForce'])} mesh={fmt(c['meshFill'])} biochem={fmt(c['biochem'])} other={fmt(c['other'])}")

print("\n=== Part D: memory ===")
print(f"{'scale':>6} {'path':>4} {'RSS_MB':>8} {'usedHeapMB':>10} {'VRAM_MB':>8} {'slotCap':>8} {'myoCap':>8} {'devBufEstMB':>11} {'overflow':>8} {'planReb':>7}")
for s in SCALES:
    for p in ["cpu","gpu"]:
        rr = rows[(s,p)]; d = rr["d"]
        if not d: print(f"{s:>6} {p:>4}  (no data)"); continue
        print(f"{s:>6} {p:>4} {fmt(rr['rss'],0):>8} {fmt(d['usedHeap'],0):>10} {fmt(rr['vram'],0):>8} {str(d['slotCap']):>8} {str(d['myoCap']):>8} {fmt(d['devBufEst'],1):>11} {str(d['overflow']):>8} {str(d['planRebuild']):>7}")

print("\n=== sanity: rc/clean per run ===")
for s in SCALES:
    for p in ["cpu","gpu"]:
        d = rows[(s,p)]["d"]
        if not d: continue
        print(f"  {s} {p}: active={d['active']} linkSettled={fmt(d['linkSettled'])} fActin={fmt(d['fActin'])} segs={d['segs']} overflow={d['overflow']} nan?={d['nanflag']}")
