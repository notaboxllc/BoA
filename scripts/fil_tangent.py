import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
# track the barbed end (end2) of the single filament; report its angular drift from frame 0
base=None; C=None
out=[]
for k,f in enumerate(files):
    dd=json.load(open(f)); segs=dd.get("segments",[]); mem=dd.get("membranes",[])
    if not segs or not mem: continue
    V=np.array(mem[0]["vertices"],dtype=float); C=V.mean(0)
    # the formin mother is the longest / first seg; take seg with max length
    e2=np.array(segs[0]["end2"]); 
    dirv=e2-C; dirv=dirv/np.linalg.norm(dirv)
    if base is None: base=dirv
    ang=np.degrees(np.arccos(np.clip(np.dot(dirv,base),-1,1)))
    out.append((k,ang,np.linalg.norm(e2-C)))
for k,a,r in out[::max(1,len(out)//12)]:
    print(f"frame {k:4d}  angularDrift={a:6.2f} deg  barbedR={r:.4f}")
print(f"FINAL angular drift = {out[-1][1]:.2f} deg")
