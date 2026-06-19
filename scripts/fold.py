import json,sys,glob,os
import numpy as np
from collections import defaultdict
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
d0=json.load(open(files[0])); faces0=np.array(d0["membranes"][0]["faces"],dtype=int).reshape(-1,3)
e2f=defaultdict(list)
for fi,(a,b,c) in enumerate(faces0):
    for u,v in ((a,b),(b,c),(c,a)): e2f[(min(int(u),int(v)),max(int(u),int(v)))].append(fi)
ev=[]; ef1=[]; ef2=[]
for e,fs in e2f.items():
    if len(fs)==2: ev.append(e); ef1.append(fs[0]); ef2.append(fs[1])
ef1=np.array(ef1); ef2=np.array(ef2)
print(f"{'frame':>6} {'maxFoldDeg':>11} {'nFold>60':>9} {'nFold>90':>9} {'foldEdge':>16}")
for k,f in enumerate(files[f0:f1],start=f0):
    if k%max(1,(f1-f0)//16): continue
    dd=json.load(open(f)); V=np.array(dd["membranes"][0]["vertices"],dtype=float)
    faces=np.array(dd["membranes"][0]["faces"],dtype=int).reshape(-1,3)
    p=V[faces]; n=np.cross(p[:,1]-p[:,0],p[:,2]-p[:,0]); n=n/np.maximum(np.linalg.norm(n,axis=1,keepdims=True),1e-30)
    ang=np.degrees(np.arccos(np.clip(np.einsum('ij,ij->i',n[ef1],n[ef2]),-1,1)))
    i=int(np.argmax(ang))
    print(f"{k:6d} {ang.max():11.1f} {int((ang>60).sum()):9d} {int((ang>90).sum()):9d} {str(ev[i]):>16}")
