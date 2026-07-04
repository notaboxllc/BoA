#!/usr/bin/env python3
# BoA dt-convergence analysis at coltol8/d1000.
# Per (dt) run, from ~/Code/threejs_output/dtconv_<tag> + census log:
#   avgBound, net |v_axial|, per-bound drift (LS-centroid, window [T0,0.70]),
#   dwell_ms (final cumulative), detachment rate (events/s, windowed),
#   catch-slip vs break-cap fraction (windowed), mean |forceDotFil| (windowed).
import json, glob, os, math, re, sys, statistics as st

OUTROOT = os.path.expanduser("~/Code/threejs_output")
LOGDIR  = "RUN_LOGS/2026-07-03_dtconv_boa"
T0, T1 = 0.30, 0.70

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

def velocity(run_dir):
    rec=series(run_dir)
    if not rec: return None
    tend=rec[-1][0]
    w=[r for r in rec if T0<=r[0]<=T1]
    if len(w)<5: return None
    ts=[r[0] for r in w]
    v=[ls_slope(ts,[r[k] for r in w]) for k in (1,2,3)]
    fhat=w[len(w)//2][4]
    va=sum(v[i]*fhat[i] for i in range(3)); vm=math.sqrt(sum(x*x for x in v))
    afr=abs(va)/(vm+1e-12)
    maxX=max(r[6] for r in rec);minX=min(r[5] for r in rec)
    maxY=max(r[8] for r in rec);minY=min(r[7] for r in rec)
    viol=(maxX>7-0.15)or(minX<-7+0.15)or(maxY>1-0.15)or(minY<-1+0.15)
    return dict(va=va,vm=vm,afr=afr,viol=viol,tend=tend,nframe=len(rec))

def avg_bound(run_dir):
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
        if T0<=t<=T1:vals.append(b)
    return sum(vals)/len(vals) if vals else None

CEN=re.compile(r"STRETCHCENSUS\] step=(\d+) t=([\d.]+) n=(\d+) ext_nm=([\-\d.]+) "
               r"absFdF_pN=([\-\d.]+) sgnFdF_pN=([\-\d.]+) dwell_ms=([\-\d.]+) "
               r"bfRel=(\d+) nRel=(\d+)")
def census(tag):
    lp=os.path.join(LOGDIR,f"run_{tag}.log")
    if not os.path.exists(lp): return None
    rows=[]
    for line in open(lp,errors="ignore"):
        m=CEN.search(line)
        if not m: continue
        rows.append(dict(step=int(m.group(1)),t=float(m.group(2)),n=int(m.group(3)),
            ext=float(m.group(4)),absfdf=float(m.group(5)),sgnfdf=float(m.group(6)),
            dwell=float(m.group(7)),bf=int(m.group(8)),nr=int(m.group(9))))
    if not rows: return None
    win=[r for r in rows if T0<=r["t"]<=T1]
    if len(win)<3: win=rows[len(rows)//2:]   # fallback
    mean=lambda k:sum(r[k] for r in win)/len(win)
    r0,r1=win[0],win[-1]
    dt_win=r1["t"]-r0["t"]
    dbf=r1["bf"]-r0["bf"]; dnr=r1["nr"]-r0["nr"]; dtot=dbf+dnr
    detach_rate = dtot/dt_win if dt_win>0 else float('nan')          # events/s (whole bed)
    breakcap_frac = dbf/dtot if dtot>0 else float('nan')
    per_head_detach = detach_rate/mean("n") if mean("n")>0 else float('nan')
    # windowed dwell: episodes E~=bf+nr (validateSeg releases negligible in gliding);
    # cumulative totSteps_i = dwell_i*E_i/(dt*1000) so windowed_ms = (d1*E1 - d0*E0)/(E1-E0).
    E0,E1=r0["bf"]+r0["nr"], r1["bf"]+r1["nr"]
    win_dwell = ((r1["dwell"]*E1 - r0["dwell"]*E0)/(E1-E0)) if (E1-E0)>0 else float('nan')
    return dict(n=mean("n"),ext=mean("ext"),absfdf=mean("absfdf"),sgnfdf=mean("sgnfdf"),
        dwell_final=rows[-1]["dwell"], win_dwell=win_dwell,
        detach_rate=detach_rate, per_head_detach=per_head_detach,
        breakcap_frac=breakcap_frac, dbf=dbf, dnr=dnr, dt_win=dt_win, tend=rows[-1]["t"])

DTS=[("dt1e5",1e-5),("dt5e6",5e-6),("dt2p5e6",2.5e-6),("dt1p25e6",1.25e-6)]
print(f"# BoA dt-convergence, coltol8/d1000, window [{T0},{T1}] s\n")
hdr=("dt","avgBound","net|v_ax|","drift","axialFr","dwell_win","detach/s","detach/head/s",
     "breakcapFrac","|FdF|pN","tend","viol")
print(" | ".join(f"{h:>12}" for h in hdr))
for tag,dtv in DTS:
    d=os.path.join(OUTROOT,f"dtconv_{tag}")
    if not os.path.isdir(d): continue
    vel=velocity(d); ab=avg_bound(d); c=census(tag)
    if vel is None:
        print(f"{dtv:>12.2e} | (no frames yet, tend<{T1})"); continue
    drift=abs(vel['va'])/ab if ab else float('nan')
    row=[f"{dtv:.2e}", f"{ab:.2f}" if ab else "NA",
         f"{abs(vel['va']):.3f}", f"{drift:.4f}", f"{vel['afr']:.3f}",
         f"{c['win_dwell']:.4f}" if c else "NA",
         f"{c['detach_rate']:.1f}" if c else "NA",
         f"{c['per_head_detach']:.3f}" if c else "NA",
         f"{c['breakcap_frac']:.3f}" if c else "NA",
         f"{c['absfdf']:.4f}" if c else "NA",
         f"{vel['tend']:.3f}", "Y" if vel['viol'] else "n"]
    print(" | ".join(f"{x:>12}" for x in row))
    if c:
        print(f"             (census: dbf={c['dbf']} dnr={c['dnr']} over {c['dt_win']:.3f}s; "
              f"ext_nm={c['ext']:.3f} sgnFdF={c['sgnfdf']:.4f})")
