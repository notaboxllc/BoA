import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
rows=[]
for k,f in enumerate(files[f0:f1], start=f0):
    dd=json.load(open(f)); mem=dd.get("membranes",[]); segs=dd.get("segments",[])
    if not mem or not segs: continue
    V=np.array(mem[0]["vertices"],dtype=float); 
    if V.ndim==1: V=V.reshape(-1,3)
    C=V.mean(0); rm=np.linalg.norm(V-C,axis=1).max()
    P=np.array([s["end1"] for s in segs]+[s["end2"] for s in segs],dtype=float)
    ra=np.linalg.norm(P-C,axis=1)
    rows.append((k, ra.max(), rm, int((ra>rm+0.04).sum())))
arr=np.array(rows)
i=int(np.argmax(arr[:,1]-arr[:,2]))
k,ra,rm,nout=rows[i]
print(f"{os.path.basename(d.rstrip('/')):24s} worstFrame={k:3d}  maxActinR={ra:.3f}  maxMembR={rm:.3f}  "
      f"actin-memb={ra-rm:+.3f}  endpointsOutside(>memb+0.04)={nout}")
