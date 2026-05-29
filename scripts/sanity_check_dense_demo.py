#!/usr/bin/env python3
"""Smell-test the dense-demo JSON output. Not validation — just a sanity pass:
  1. frame count, each parses, no NaN/inf coords
  2. filament count stable across frames (101 expected)
  3. filament COM moves between frames (gliding happening)
"""
import json, glob, math, sys

d = sys.argv[1] if len(sys.argv) > 1 else "/home/jba/boa_dense_demo_work/run_gpu"
files = sorted(glob.glob(f"{d}/frame_*.json"))
print(f"frames found: {len(files)}")

def has_bad(xs):
    return any(math.isnan(x) or math.isinf(x) or abs(x) > 100 for x in xs)

# Walk filaments by isBarbedEnd-tagged segments per frame
def per_frame_stats(path):
    with open(path) as f:
        d = json.load(f)
    bad = 0
    for s in d["segments"]:
        if has_bad(s["end1"]) or has_bad(s["end2"]):
            bad += 1
    barbed = sum(1 for s in d["segments"] if s.get("isBarbedEnd"))
    return {
        "t": d["t"],
        "nSegments": len(d["segments"]),
        "nMyosins": len(d["myosins"]),
        "nFilamentsByBarbed": barbed,
        "bad": bad,
    }

# First, last, and 3 middle
sample = []
if files:
    sample.append(files[0])
    if len(files) > 4:
        sample += [files[len(files)//4], files[len(files)//2], files[3*len(files)//4]]
    sample.append(files[-1])

for fp in sample:
    print(fp.split("/")[-1], per_frame_stats(fp))

# COM drift check between first and last frame
if len(files) >= 2:
    with open(files[0]) as f0, open(files[-1]) as fl:
        d0, dl = json.load(f0), json.load(fl)
    # Group segments by filament: trace via isBarbedEnd as start anchor.
    # Simpler: just compute global COM-of-segments per frame.
    def gcom(d):
        xs = [s["end1"][0] for s in d["segments"]] + [s["end2"][0] for s in d["segments"]]
        ys = [s["end1"][1] for s in d["segments"]] + [s["end2"][1] for s in d["segments"]]
        zs = [s["end1"][2] for s in d["segments"]] + [s["end2"][2] for s in d["segments"]]
        n = len(xs)
        return (sum(xs)/n, sum(ys)/n, sum(zs)/n)
    g0 = gcom(d0); gl = gcom(dl)
    print(f"global segment COM frame0  = ({g0[0]:.4f}, {g0[1]:.4f}, {g0[2]:.4f}) µm")
    print(f"global segment COM frameLast = ({gl[0]:.4f}, {gl[1]:.4f}, {gl[2]:.4f}) µm")
    print(f"COM displacement |Δ|     = {math.sqrt(sum((a-b)**2 for a,b in zip(g0,gl))):.4f} µm")
    print(f"dt between frame0 and last = {dl['t']-d0['t']:.5f} s")

    # Per-segment displacement distribution by matching IDs.
    s0 = {s["id"]: s for s in d0["segments"]}
    moved = []
    for s in dl["segments"]:
        prev = s0.get(s["id"])
        if not prev: continue
        dx = (s["end1"][0]+s["end2"][0])/2 - (prev["end1"][0]+prev["end2"][0])/2
        dy = (s["end1"][1]+s["end2"][1])/2 - (prev["end1"][1]+prev["end2"][1])/2
        dz = (s["end1"][2]+s["end2"][2])/2 - (prev["end1"][2]+prev["end2"][2])/2
        moved.append(math.sqrt(dx*dx+dy*dy+dz*dz))
    if moved:
        moved.sort()
        n = len(moved)
        print(f"matched segments across endpoints: {n}")
        print(f"per-seg displacement µm: min={moved[0]:.4f}  median={moved[n//2]:.4f}  max={moved[-1]:.4f}")
