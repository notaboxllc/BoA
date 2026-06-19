import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
files=files[f0:f1]; base=None
print(f"{'frame':>6} {'membCdrift_nm':>13} {'filC_radius':>11} {'nfil':>5}")
for k,f in enumerate(files):
    dd=json.load(open(f)); mem=dd.get("membranes",[]); segs=dd.get("segments",[])
    if not mem: continue
    V=np.array(mem[0]["vertices"],dtype=float); Cm=V.mean(0)
    if base is None: base=Cm.copy(); sphereC=Cm.copy()
    drift=np.linalg.norm(Cm-base)*1000.0  # nm
    if segs:
        coms=np.array([[(s["end1"][j]+s["end2"][j])*0.5 for j in range(3)] for s in segs])
        filC=coms.mean(0); filR=np.linalg.norm(filC-sphereC)
    else: filR=0
    if k%max(1,len(files)//8)==0:
        print(f"{f0+k:6d} {drift:13.4f} {filR:11.4f} {len(segs):5d}")
