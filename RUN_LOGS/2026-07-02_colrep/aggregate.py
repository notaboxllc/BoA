#!/usr/bin/env python3
# Aggregate all colrep_* runs: LS-centroid v_axial (t0=0.20 & 0.30), avgBound, per-bound drift,
# axialFrac, coverage flag; plus stretch-census means (ext_nm/|fdF|/signed fdF/dwell) parsed from
# the run log over the steady window. Run repeatedly as draws complete.
import json, glob, os, math, re, sys

OUTROOT=os.path.expanduser("~/Code/threejs_output")
LOGDIR="RUN_LOGS/2026-07-02_colrep"

def ls_slope(ts, xs):
    n=len(ts); mt=sum(ts)/n; mx=sum(xs)/n
    den=sum((t-mt)**2 for t in ts)
    return sum((ts[i]-mt)*(xs[i]-mx) for i in range(n))/den if den else 0.0

def centroid_series(run_dir):
    rec=[]
    for fp in sorted(glob.glob(os.path.join(run_dir,"frame_*.json"))):
        try: f=json.load(open(fp))
        except Exception: continue
        segs=sorted(f.get("segments",[]), key=lambda s:s["id"])
        if not segs: continue
        pts=[p for s in segs for p in (s["end1"], s["end2"])]
        c=[sum(p[i] for p in pts)/len(pts) for i in range(3)]
        a,b=segs[0]["end1"], segs[-1]["end2"]; v=[b[i]-a[i] for i in range(3)]
        m=math.sqrt(sum(x*x for x in v)) or 1.0
        xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
        rec.append((f.get("t",0.0), c[0],c[1],c[2],[x/m for x in v],min(xs),max(xs),min(ys),max(ys)))
    return rec

def v_axial(rec,t0):
    w=[r for r in rec if r[0]>=t0]
    if len(w)<3: return None
    ts=[r[0] for r in w]
    v=[ls_slope(ts,[r[k] for r in w]) for k in (1,2,3)]
    fhat=w[len(w)//2][4]
    va=sum(v[i]*fhat[i] for i in range(3)); vm=math.sqrt(sum(x*x for x in v))
    return va,vm,abs(va)/(vm+1e-12)

def avg_bound(run_dir,t0,t1):
    dat=os.path.join(run_dir,"gliding_assay.dat")
    if not os.path.exists(dat): return None
    hdr=None; it=ib=None; vals=[]
    for line in open(dat):
        p=line.rstrip("\n").split("\t")
        if hdr is None:
            hdr=p
            try: it=hdr.index("simTime"); ib=hdr.index("avgBoundMotors")
            except ValueError: return None
            continue
        try: t=float(p[it]); b=float(p[ib])
        except (ValueError,IndexError): continue
        if t0<=t<=t1: vals.append(b)
    return sum(vals)/len(vals) if vals else None

CEN=re.compile(r"STRETCHCENSUS\] step=(\d+) t=([\d.]+) n=(\d+) ext_nm=([\-\d.]+) absFdF_pN=([\-\d.]+) sgnFdF_pN=([\-\d.]+) dwell_ms=([\-\d.]+)")
def census(logpath,t0):
    if not os.path.exists(logpath): return None
    ext=[];af=[];sf=[];dw=[];ns=[]
    for line in open(logpath,errors="ignore"):
        m=CEN.search(line)
        if not m: continue
        t=float(m.group(2))
        if t<t0: continue
        ns.append(int(m.group(3))); ext.append(float(m.group(4)))
        af.append(float(m.group(5))); sf.append(float(m.group(6))); dw.append(float(m.group(7)))
    if not ext: return None
    mean=lambda a: sum(a)/len(a)
    return dict(n=mean(ns),ext=mean(ext),absfdf=mean(af),sgnfdf=mean(sf),dwell=dw[-1],nsamp=len(ext))

def main():
    t0=0.20
    print(f"{'run':16} {'frames':>6} {'tend':>5} {'cov!':>5} {'vax@.20':>8} {'vax@.30':>8} {'|v|':>6} {'aFrac':>6} {'avgB':>6} {'drift':>6}")
    for nm in (4,6,8):
        for p in (1,2,3):
            d=os.path.join(OUTROOT,f"colrep_{nm}nm_p{p}")
            if not os.path.isdir(d): continue
            rec=centroid_series(d)
            if not rec:
                print(f"colrep_{nm}nm_p{p:<4}   (no frames)"); continue
            tend=rec[-1][0]
            maxX=max(r[6] for r in rec);minX=min(r[5] for r in rec)
            maxY=max(r[8] for r in rec);minY=min(r[7] for r in rec)
            viol=(maxX>7-0.15)or(minX<-7+0.15)or(maxY>1-0.15)or(minY<-1+0.15)
            r20=v_axial(rec,0.20); r30=v_axial(rec,0.30)
            ab=avg_bound(d,0.20,tend)
            va20=r20[0] if r20 else float('nan'); va30=r30[0] if r30 else float('nan')
            vm=r20[1] if r20 else float('nan'); afr=r20[2] if r20 else float('nan')
            drift=abs(va20)/ab if ab else float('nan')
            print(f"colrep_{nm}nm_p{p:<4} {len(rec):6d} {tend:5.2f} {str(viol):>5} {va20:8.3f} {va30:8.3f} {vm:6.3f} {afr:6.3f} {ab if ab else float('nan'):6.2f} {drift:6.3f}")
    print("\n# ---- STRETCH CENSUS (steady window t>=0.20, from run logs; batch runs only) ----")
    print(f"{'run':16} {'nsamp':>5} {'n_bound':>7} {'ext_nm':>7} {'absFdF_pN':>9} {'sgnFdF_pN':>9} {'dwell_ms':>8}")
    for nm in (4,6,8):
        for p in (1,2,3):
            lp=os.path.join(LOGDIR,f"run_{nm}nm_p{p}.log")
            c=census(lp,0.20)
            if not c: continue
            print(f"run_{nm}nm_p{p:<5} {c['nsamp']:5d} {c['n']:7.1f} {c['ext']:7.3f} {c['absfdf']:9.4f} {c['sgnfdf']:9.4f} {c['dwell']:8.4f}")

if __name__=="__main__": main()
