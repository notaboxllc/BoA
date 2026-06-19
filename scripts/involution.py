import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
print(f"{'frame':>6} {'rMean':>7} {'rMin':>7} {'rMax':>7} {'nInward(<0.85rMean)':>20} {'minFaceCos(fold)':>16}")
for k,f in enumerate(files[f0:f1],start=f0):
    if k%max(1,(f1-f0)//14): continue
    dd=json.load(open(f)); mem=dd.get("membranes",[])
    if not mem: continue
    V=np.array(mem[0]["vertices"],dtype=float); C=V.mean(0)
    r=np.linalg.norm(V-C,axis=1); rm=r.mean()
    nin=int((r<0.85*rm).sum())
    # fold detection: adjacent face normals pointing opposite (dihedral near 180) -> involution
    faces=np.array(mem[0]["faces"],dtype=int).reshape(-1,3)
    p=V[faces]
    n=np.cross(p[:,1]-p[:,0], p[:,2]-p[:,0]); ln=np.linalg.norm(n,axis=1,keepdims=True); n=n/np.maximum(ln,1e-30)
    # outward orientation check: normal . (centroid-C)
    fc=p.mean(1); outdot=np.einsum('ij,ij->i', n, fc-C)
    nflip=int((outdot<0).sum())  # faces whose normal points inward = folded/inverted
    print(f"{k:6d} {rm:7.4f} {r.min():7.4f} {r.max():7.4f} {nin:20d} {('inverted faces=%d'%nflip):>16}")
