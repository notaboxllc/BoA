#!/usr/bin/env python3
# Measure gliding v_axial (LS-centroid along f-hat, PROPER_SPEED_ANALYSIS.md) + avgBound (gliding_assay.dat)
# for a single run dir. Reports v_axial at several t0 to convergence-check, plus coverage (bed-edge) flag.
import json, glob, os, math, sys

def ls_slope(ts, xs):
    n=len(ts); mt=sum(ts)/n; mx=sum(xs)/n
    den=sum((t-mt)**2 for t in ts)
    return sum((ts[i]-mt)*(xs[i]-mx) for i in range(n))/den if den else 0.0

def centroid_series(run_dir):
    rec=[]
    for fp in sorted(glob.glob(os.path.join(run_dir,"frame_*.json"))):
        f=json.load(open(fp)); segs=sorted(f["segments"], key=lambda s:s["id"])
        if not segs: continue
        pts=[p for s in segs for p in (s["end1"], s["end2"])]
        c=[sum(p[i] for p in pts)/len(pts) for i in range(3)]
        a,b=segs[0]["end1"], segs[-1]["end2"]; v=[b[i]-a[i] for i in range(3)]
        m=math.sqrt(sum(x*x for x in v)) or 1.0
        # bounding box extremes of ALL endpoints (for coverage / bed-edge check)
        xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
        rec.append((f.get("t",f.get("simTime",0.0)), c[0],c[1],c[2], [x/m for x in v],
                    min(xs),max(xs),min(ys),max(ys)))
    return rec

def v_axial(rec, t0):
    w=[r for r in rec if r[0]>=t0]
    if len(w)<3: return None
    ts=[r[0] for r in w]
    v=[ls_slope(ts,[r[k] for r in w]) for k in (1,2,3)]
    fhat=w[len(w)//2][4]
    va=sum(v[i]*fhat[i] for i in range(3)); vm=math.sqrt(sum(x*x for x in v))
    return va, vm, abs(va)/(vm+1e-12)

def avg_bound(run_dir, t0, t1):
    dat=os.path.join(run_dir,"gliding_assay.dat")
    if not os.path.exists(dat): return None
    hdr=None; vals=[]
    for line in open(dat):
        p=line.rstrip("\n").split("\t")
        if hdr is None:
            hdr=p;
            it=hdr.index("simTime"); ib=hdr.index("avgBoundMotors"); continue
        try:
            t=float(p[it]); b=float(p[ib])
        except (ValueError,IndexError): continue
        if t0<=t<=t1: vals.append(b)
    return sum(vals)/len(vals) if vals else None

if __name__=="__main__":
    run_dir=sys.argv[1]
    t0s=[float(x) for x in (sys.argv[2].split(",") if len(sys.argv)>2 else ["0.10","0.20","0.30"])]
    rec=centroid_series(run_dir)
    if not rec:
        print(f"{run_dir}: NO FRAMES"); sys.exit(1)
    tend=rec[-1][0]
    # coverage: box is 14x2 um centered at origin -> x in [-7,7], y in [-1,1].
    # bed edge violation if filament endpoints approach walls (margin 0.15 um).
    maxX=max(r[6] for r in rec); minX=min(r[5] for r in rec)
    maxY=max(r[8] for r in rec); minY=min(r[7] for r in rec)
    viol = (maxX>7-0.15) or (minX<-7+0.15) or (maxY>1-0.15) or (minY<-1+0.15)
    print(f"# {os.path.basename(run_dir)}  frames={len(rec)}  t=[{rec[0][0]:.3f},{tend:.3f}]")
    print(f"#   x-range=[{minX:.3f},{maxX:.3f}] y-range=[{minY:.3f},{maxY:.3f}]  coverageVIOLATED={viol}")
    for t0 in t0s:
        r=v_axial(rec,t0)
        if r is None: print(f"#   t0={t0}: too few frames"); continue
        va,vm,af=r
        ab=avg_bound(run_dir,t0,tend)
        drift=abs(va)/ab if ab else float('nan')
        print(f"  t0={t0:.2f}: v_axial={va:+.3f}  |v|={vm:.3f}  axialFrac={af:.3f}  avgBound={ab:.2f}  perBoundDrift={drift:.3f}")
