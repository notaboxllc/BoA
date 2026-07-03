#!/usr/bin/env python3
# Final analysis: FULL runs only (tend>=0.68), uniform LS window [T0,0.70].
# Per radius: net v_axial mean +/- spread (population SD) over draws, avgBound, per-bound drift,
# axialFrac. Coverage-violated runs excluded from the velocity mean (kept for avgBound). Plus the
# stretch census (4/6/8 nm) mean over draws from the run logs.
import json, glob, os, math, re, statistics as st

OUTROOT=os.path.expanduser("~/Code/threejs_output")
LOGDIR="RUN_LOGS/2026-07-02_colrep"
T0=0.30   # uniform steady-window start; runs go to 0.70

def ls_slope(ts, xs):
    n=len(ts); mt=sum(ts)/n; mx=sum(xs)/n
    den=sum((t-mt)**2 for t in ts)
    return sum((ts[i]-mt)*(xs[i]-mx) for i in range(n))/den if den else 0.0

def series(run_dir):
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
        rec.append((f.get("t",0.0),c[0],c[1],c[2],[x/m for x in v],min(xs),max(xs),min(ys),max(ys)))
    return rec

def measure(run_dir):
    rec=series(run_dir)
    if not rec or rec[-1][0]<0.68: return None
    w=[r for r in rec if r[0]>=T0]
    if len(w)<5: return None
    ts=[r[0] for r in w]
    v=[ls_slope(ts,[r[k] for r in w]) for k in (1,2,3)]
    fhat=w[len(w)//2][4]
    va=sum(v[i]*fhat[i] for i in range(3)); vm=math.sqrt(sum(x*x for x in v))
    afr=abs(va)/(vm+1e-12)
    maxX=max(r[6] for r in rec);minX=min(r[5] for r in rec)
    maxY=max(r[8] for r in rec);minY=min(r[7] for r in rec)
    viol=(maxX>7-0.15)or(minX<-7+0.15)or(maxY>1-0.15)or(minY<-1+0.15)
    # avgBound over the same window from the dat
    ab=avg_bound(run_dir,T0,rec[-1][0])
    return dict(va=va,vm=vm,afr=afr,viol=viol,ab=ab,tend=rec[-1][0])

def avg_bound(run_dir,t0,t1):
    dat=os.path.join(run_dir,"gliding_assay.dat")
    if not os.path.exists(dat): return None
    hdr=None;it=ib=None;vals=[]
    for line in open(dat):
        p=line.rstrip("\n").split("\t")
        if hdr is None:
            hdr=p
            try:it=hdr.index("simTime");ib=hdr.index("avgBoundMotors")
            except ValueError:return None
            continue
        try:t=float(p[it]);b=float(p[ib])
        except(ValueError,IndexError):continue
        if t0<=t<=t1:vals.append(b)
    return sum(vals)/len(vals) if vals else None

CEN=re.compile(r"STRETCHCENSUS\] step=(\d+) t=([\d.]+) n=(\d+) ext_nm=([\-\d.]+) absFdF_pN=([\-\d.]+) sgnFdF_pN=([\-\d.]+) dwell_ms=([\-\d.]+)")
def census(nm,p,t0=T0):
    lp=os.path.join(LOGDIR,f"run_{nm}nm_p{p}.log")
    if not os.path.exists(lp):
        # p1 probes ran without census; skip
        return None
    ext=[];af=[];sf=[];dw=[];ns=[]
    for line in open(lp,errors="ignore"):
        m=CEN.search(line)
        if not m: continue
        if float(m.group(2))<t0: continue
        ns.append(int(m.group(3)));ext.append(float(m.group(4)))
        af.append(float(m.group(5)));sf.append(float(m.group(6)));dw.append(float(m.group(7)))
    if len(ext)<5: return None
    mean=lambda a:sum(a)/len(a)
    return dict(n=mean(ns),ext=mean(ext),absfdf=mean(af),sgnfdf=mean(sf),dwell=dw[-1])

def spread(a):
    return st.pstdev(a) if len(a)>1 else 0.0

print(f"# Capture-radius replicate — FINAL (full runs, LS window [{T0},0.70])\n")
print(f"{'radius':>6} | {'net v_axial (mean±sd)':>22} | {'avgBound':>16} | {'per-bound drift':>18} | {'axialFrac':>10} | draws(clean/total)")
rad_summary={}
for nm in (4,6,8):
    per=[]
    for p in (1,2,3):
        d=os.path.join(OUTROOT,f"colrep_{nm}nm_p{p}")
        if not os.path.isdir(d): continue
        r=measure(d)
        if r: per.append((p,r))
    if not per:
        print(f"{nm:>5}nm | (no full runs yet)"); continue
    clean=[r for (p,r) in per if not r['viol'] and r['ab']]
    allr =[r for (p,r) in per]
    vas=[abs(r['va']) for r in clean]
    abs_=[r['ab'] for r in allr if r['ab']]
    drifts=[abs(r['va'])/r['ab'] for r in clean if r['ab']]
    afrs=[r['afr'] for r in clean]
    vmean=st.mean(vas) if vas else float('nan'); vsd=spread(vas)
    abmean=st.mean(abs_) if abs_ else float('nan'); absd=spread(abs_)
    dmean=st.mean(drifts) if drifts else float('nan'); dsd=spread(drifts)
    afmean=st.mean(afrs) if afrs else float('nan')
    rad_summary[nm]=dict(v=vmean,vsd=vsd,ab=abmean,absd=absd,d=dmean,dsd=dsd,af=afmean,
                         ndraw=len(allr),nclean=len(clean),
                         vals=[(p,round(r['va'],3),round(r['ab'],2) if r['ab'] else None,r['viol']) for (p,r) in per])
    print(f"{nm:>5}nm | {vmean:8.3f} ± {vsd:5.3f} (n={len(clean)}) | {abmean:6.2f} ± {absd:4.2f} | {dmean:6.3f} ± {dsd:5.3f} | {afmean:8.3f} | {len(clean)}/{len(allr)}")

print("\n# Per-draw detail (draw, v_axial, avgBound, coverageViol):")
for nm in (4,6,8):
    if nm in rad_summary:
        print(f"  {nm}nm: {rad_summary[nm]['vals']}")

print(f"\n# ---- STRETCH CENSUS (per-radius mean over draws, window t>=[{T0}]) ----")
print(f"{'radius':>6} | {'n_bound':>7} | {'ext_nm':>7} | {'absFdF_pN':>9} | {'sgnFdF_pN':>9} | {'dwell_ms':>8}")
for nm in (4,6,8):
    cs=[c for c in (census(nm,p) for p in (1,2,3)) if c]
    if not cs:
        print(f"{nm:>5}nm | (no census)"); continue
    m=lambda k: st.mean([c[k] for c in cs])
    print(f"{nm:>5}nm | {m('n'):7.1f} | {m('ext'):7.3f} | {m('absfdf'):9.4f} | {m('sgnfdf'):9.4f} | {m('dwell'):8.4f}   (n={len(cs)})")
