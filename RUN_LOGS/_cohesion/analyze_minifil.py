#!/usr/bin/env python3
# Minifilament span / body-axis wander / COM jitter from -3js frames.
import json, glob, math, sys, os
def load(d):
    fs = sorted(glob.glob(os.path.join(d, "frame_*.json")))
    spans=[]; axes=[]; coms=[]
    for f in fs:
        j=json.load(open(f))
        mfs=j.get("minifilaments",[])
        if not mfs: continue
        m=mfs[0]
        e1=m["end1"]; e2=m["end2"]
        d3=[e2[i]-e1[i] for i in range(3)]
        L=math.sqrt(sum(c*c for c in d3))
        spans.append(L*1000.0)  # nm
        axes.append([c/L for c in d3] if L>0 else [1,0,0])
        coms.append([(e1[i]+e2[i])/2 for i in range(3)])
    # body-axis cumulative wander (deg)
    wander=0.0
    for k in range(1,len(axes)):
        d=max(-1,min(1,sum(axes[k][i]*axes[k-1][i] for i in range(3))))
        wander+=math.degrees(math.acos(d))
    # per-frame COM jitter (nm) mean
    jit=[]
    for k in range(1,len(coms)):
        jit.append(1000.0*math.sqrt(sum((coms[k][i]-coms[k-1][i])**2 for i in range(3))))
    comDrift=1000.0*math.sqrt(sum((coms[-1][i]-coms[0][i])**2 for i in range(3))) if coms else 0
    import statistics as st
    return dict(n=len(spans),
                spanMean=st.mean(spans) if spans else 0,
                spanSd=st.pstdev(spans) if len(spans)>1 else 0,
                spanMin=min(spans) if spans else 0, spanMax=max(spans) if spans else 0,
                wander=wander, jitMean=st.mean(jit) if jit else 0, comDrift=comDrift)
for d in sys.argv[1:]:
    r=load(d)
    lbl=os.path.basename(d)
    print(f"{lbl:32s} n={r['n']:3d} span={r['spanMean']:.1f}±{r['spanSd']:.2f}nm "
          f"[{r['spanMin']:.1f},{r['spanMax']:.1f}] wander={r['wander']:.1f}° "
          f"jitter={r['jitMean']:.2f}nm comDrift={r['comDrift']:.1f}nm")
