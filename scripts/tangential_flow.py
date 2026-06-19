import json,sys,glob,os
import numpy as np
d=sys.argv[1]; files=sorted(glob.glob(os.path.join(d,"frame_*.json")))
f0=int(sys.argv[2]) if len(sys.argv)>2 else 0; f1=int(sys.argv[3]) if len(sys.argv)>3 else len(files)
files=files[f0:f1]
prev=None; cum=None
print(f"{'frame':>6} {'meanTangStep(nm)':>16} {'maxTangStep(nm)':>15} {'meanCumTang(nm)':>15}")
v0=None
for k,f in enumerate(files):
    dd=json.load(open(f)); V=np.array(dd["membranes"][0]["vertices"],dtype=float); C=V.mean(0)
    r=np.linalg.norm(V-C,axis=1,keepdims=True); rhat=(V-C)/np.maximum(r,1e-30)
    if prev is not None:
        dv=V-prev
        # tangential component = dv minus its radial projection
        radial=np.einsum('ij,ij->i',dv,rhat)[:,None]*rhat
        tang=dv-radial
        tmag=np.linalg.norm(tang,axis=1)*1000.0  # nm
        if v0 is None: v0=prev.copy()
        # cumulative tangential vs start
        dv0=V-v0; rad0=np.einsum('ij,ij->i',dv0,rhat)[:,None]*rhat; ct=np.linalg.norm(dv0-rad0,axis=1)*1000.0
        if k%max(1,len(files)//12)==0:
            print(f"{f0+k:6d} {tmag.mean():16.3f} {tmag.max():15.3f} {ct.mean():15.1f}")
    prev=V
