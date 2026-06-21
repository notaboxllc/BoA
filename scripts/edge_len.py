import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
# l0 from frame 0
d0=json.load(open(files[0])); V0=np.array(d0["membranes"][0]["vertices"]); F0=np.array(d0["membranes"][0]["faces"]).reshape(-1,3)
def edgelens(V,F):
    e=set()
    for a,b,c in F:
        for u,v in ((a,b),(b,c),(c,a)): e.add((min(u,v),max(u,v)))
    e=np.array(list(e)); return np.linalg.norm(V[e[:,0]]-V[e[:,1]],axis=1)
l0=np.median(edgelens(V0,F0))
print(f"l0(median frame0)={l0:.4f}; thresholds: 1.15*l0={1.15*l0:.4f} 1.5*l0={1.5*l0:.4f}")
print(f"{'frame':>6} {'maxEdge/l0':>11} {'p99/l0':>8} {'#edges>1.5l0':>13} {'#edges>1.15l0':>14}")
for k,f in enumerate(files[f0:f1]):
    if k%max(1,(f1-f0)//6): continue
    dd=json.load(open(f)); V=np.array(dd["membranes"][0]["vertices"]); F=np.array(dd["membranes"][0]["faces"]).reshape(-1,3)
    el=edgelens(V,F)
    print(f"{f0+k:6d} {el.max()/l0:11.2f} {np.percentile(el,99)/l0:8.2f} {int((el>1.5*l0).sum()):13d} {int((el>1.15*l0).sum()):14d}")
