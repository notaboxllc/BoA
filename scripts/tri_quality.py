import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
def minangles(V,F):
    p=V[F]; out=[]
    for tri in (p,):
        for perm in ((0,1,2),(1,2,0),(2,0,1)):
            a=tri[:,perm[0]]; b=tri[:,perm[1]]; c=tri[:,perm[2]]
            u=b-a; w=c-a
            cosA=np.einsum('ij,ij->i',u,w)/(np.linalg.norm(u,axis=1)*np.linalg.norm(w,axis=1)+1e-30)
            out.append(np.degrees(np.arccos(np.clip(cosA,-1,1))))
    return np.minimum.reduce(out)  # min angle per triangle
print(f"{'frame':>6} {'minAngle(worst)':>15} {'p5 minAngle':>12} {'#tris<20deg':>11}")
for k,f in enumerate(files[f0:f1]):
    if k%max(1,(f1-f0)//8): continue
    dd=json.load(open(f)); mem=dd.get("membranes",[])
    if not mem: continue
    V=np.array(mem[0]["vertices"],dtype=float); F=np.array(mem[0]["faces"],dtype=int).reshape(-1,3)
    ma=minangles(V,F)
    print(f"{f0+k:6d} {ma.min():15.2f} {np.percentile(ma,5):12.2f} {int((ma<20).sum()):11d}")
