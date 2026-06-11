#!/usr/bin/env python3
# Turnover fingerprint from -3js frames: segment-count trajectory + length distribution.
import json, glob, math, os, sys, statistics as st
def lens_counts(d):
    fs = sorted(glob.glob(os.path.join(d, "frame_*.json")))
    counts=[]; finalLens=[]
    for f in fs:
        j=json.load(open(f)); s=j.get("segments",[]); counts.append(len(s))
    if fs:
        s=json.load(open(fs[-1])).get("segments",[])
        for seg in s:
            e1,e2=seg["end1"],seg["end2"]
            L=math.sqrt(sum((e2[i]-e1[i])**2 for i in range(3)))*1000.0  # nm
            finalLens.append(L)
    return counts, finalLens
for d in sys.argv[1:]:
    c,L=lens_counts(d); lbl=os.path.basename(d)
    if not c: print(f"{lbl:34s} NO FRAMES"); continue
    nanL=sum(1 for x in L if math.isnan(x) or math.isinf(x))
    Lc=[x for x in L if not (math.isnan(x) or math.isinf(x))]
    print(f"{lbl:34s} frames={len(c):3d} segCt[first={c[0]} last={c[-1]} peak={max(c)} mean={st.mean(c):.0f}] "
          f"len_nm[mean={st.mean(Lc):.1f} sd={st.pstdev(Lc):.1f} n={len(Lc)}] nanLen={nanL}")
