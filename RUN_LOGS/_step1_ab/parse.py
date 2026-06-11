#!/usr/bin/env python3
import sys, re, glob, os, statistics as st
# Parse [STATS] contractility lines; plateau = step>5000 window.
pat = re.compile(r"\[STATS\] contractility step=(\d+) t=([\d.]+) tensionA=([-\d.]+) tensionB=([-\d.]+) pN boundMotors=(\d+)")
def parse(path):
    A=[];B=[];BM=[]
    last=None
    for ln in open(path):
        m=pat.search(ln)
        if not m: continue
        step=int(m.group(1)); ta=float(m.group(3)); tb=float(m.group(4)); bm=int(m.group(5))
        last=(step,ta,tb,bm)
        if step>5000:
            A.append(ta);B.append(tb);BM.append(bm)
    if not A: return None
    return dict(nA=len(A),tA=st.mean(A),tB=st.mean(B),bm=st.mean(BM),
                tAsd=st.pstdev(A) if len(A)>1 else 0, last=last)
def mbm(path):
    for ln in open(path):
        m=re.search(r"meanBoundMotors=([\d.]+)",ln)
        if m: return float(m.group(1))
    return None
def be(path):
    for ln in open(path):
        m=re.search(r"bindEvents=(\d+)",ln)
        if m: return int(m.group(1))
    return None

d=sys.argv[1]
for f in sorted(glob.glob(os.path.join(d,"*.log"))):
    lbl=os.path.basename(f)[:-4]
    r=parse(f)
    if r is None:
        print(f"{lbl:18s} (no contractility STATS)"); continue
    print(f"{lbl:18s} plateau(step>5k n={r['nA']:3d}): tensionA={r['tA']:.3f}±{r['tAsd']:.2f} "
          f"tensionB={r['tB']:.3f} boundMotors={r['bm']:.2f}  meanBoundMotors={mbm(f)}  bindEvents={be(f)}  "
          f"last={r['last']}")
