import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
print(f"{'frame':>6} {'membRmean':>9} {'membRstd':>9} {'filRmax':>8} {'nfil':>5}")
for k,f in enumerate(files[f0:f1],start=f0):
    if k% max(1,(f1-f0)//10): continue
    dd=json.load(open(f)); mem=dd.get("membranes",[]); segs=dd.get("segments",[])
    if not mem: continue
    V=np.array(mem[0]["vertices"],dtype=float); C=V.mean(0)
    r=np.linalg.norm(V-C,axis=1)
    if segs:
        coms=np.array([[(s["end1"][j]+s["end2"][j])*0.5 for j in range(3)] for s in segs])
        fr=np.linalg.norm(coms-C,axis=1); frmax=fr.max(); nf=len(segs)
    else: frmax=0; nf=0
    print(f"{k:6d} {r.mean():9.4f} {r.std():9.5f} {frmax:8.4f} {nf:5d}")
