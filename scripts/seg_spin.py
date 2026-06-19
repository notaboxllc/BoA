import json, sys, glob, os, math
import numpy as np
def axes(path):
    d=json.load(open(path)); out={}
    for s in d.get("segments",[]):
        a=np.array(s["axisX"]) if "axisX" in s else None
        if a is None or np.linalg.norm(a)<1e-9:
            e1,e2=np.array(s["end1"]),np.array(s["end2"]); a=e2-e1
        n=np.linalg.norm(a)
        if n>0: out[s["id"]]=a/n
    return out
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
files=files[f0:f1]
vals=[]; prev=axes(files[0])
for f in files[1:]:
    cur=axes(f)
    for i in set(prev)&set(cur):
        c=abs(float(np.clip(np.dot(prev[i],cur[i]),-1,1)))  # undirected axis
        vals.append(math.degrees(math.acos(c)))
    prev=cur
v=np.array(vals)
print(f"{os.path.basename(d.rstrip('/')):26s} frames[{f0}:{f1}] segPairs={len(v):5d}  "
      f"meanDeg/frame={v.mean():6.2f}  p50={np.percentile(v,50):6.2f}  p90={np.percentile(v,90):6.2f}  "
      f"p99={np.percentile(v,99):6.2f}  max={v.max():6.2f}")
