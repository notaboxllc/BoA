import json,re,sys,glob,os
from collections import defaultdict

WD=os.path.dirname(os.path.abspath(__file__))
GRID_TASKS={"gridZero","gridHist","gridScanLocal","gridScanChunks","gridScanAdd","gridScatter","gridAssemble"}

def load(path):
    txt=open(path).read(); txt=re.sub(r',(\s*[}\]])', r'\1', txt)
    dec=json.JSONDecoder(); i=0; n=len(txt); objs=[]
    while i<n:
        while i<n and txt[i] in ' \t\r\n': i+=1
        if i>=n: break
        try: o,j=dec.raw_decode(txt,i)
        except Exception: break
        objs.append(o); i=j
    return objs

def per_task(path, warm=15):
    objs=load(path)
    ke=[o["chained"] for o in objs if "chained" in o]
    ke=ke[warm:]
    if not ke: return None
    per=defaultdict(float); cnt=0
    wall=0.0
    for g in ke:
        for k,v in g.items():
            if k.startswith("chained.") and isinstance(v,dict) and "TASK_KERNEL_TIME" in v:
                per[k.replace('chained.','')]+=int(v["TASK_KERNEL_TIME"])/1e6
        if "TOTAL_TASK_GRAPH_TIME" in g and not isinstance(g["TOTAL_TASK_GRAPH_TIME"],dict):
            wall+=int(g["TOTAL_TASK_GRAPH_TIME"])/1e6
        cnt+=1
    per={k:v/cnt for k,v in per.items()}
    return per, wall/cnt, cnt

def agg(lab, grid):
    paths=sorted(glob.glob(f"{WD}/prof/{lab}_{grid}_r*.json"))
    runs=[per_task(p) for p in paths]; runs=[r for r in runs if r]
    if not runs: return None
    allk=set(); [allk.update(r[0]) for r in runs]
    perm={k:sum(r[0].get(k,0) for r in runs)/len(runs) for k in allk}
    wall=sum(r[1] for r in runs)/len(runs)
    return perm, wall, len(runs)

print("=== Per-task kernel time (TASK_KERNEL_TIME, ms/execute, profiler) ===")
for lab in ["1x","8x","16x"]:
    print(f"\n## {lab}")
    for grid in ["ser","par"]:
        r=agg(lab,grid)
        if not r: print(f"  {grid}: (no data)"); continue
        perm,wall,nr=r
        gridsum=sum(v for k,v in perm.items() if k in GRID_TASKS)
        print(f"  [{grid}] n={nr} wall={wall:.2f}ms  GRID-BUILD total={gridsum:.3f}ms")
        for k in sorted(perm,key=lambda x:-perm[x]):
            tag=" <grid>" if k in GRID_TASKS else ""
            print(f"       {k:16s} {perm[k]*1000:8.1f} us{tag}")

print("\n=== ms/step (two-step-diff) + GPU/CPU ===")
raw=f"{WD}/perf_raw.tsv"
if os.path.exists(raw):
    data=defaultdict(lambda: defaultdict(list))
    for ln in open(raw).read().splitlines()[1:]:
        p=ln.split("\t")
        if len(p)<4 or p[3]=="NA": continue
        data[p[0]][p[1]].append(float(p[3]))
    for lab in ["1x","8x","16x"]:
        d=data.get(lab,{})
        def mean(m):
            v=d.get(m,[]); return sum(v)/len(v) if v else None
        par,ser,cpu=mean("gpu_par"),mean("gpu_ser"),mean("cpu")
        def r(x): return f"{x:.2f}" if x is not None else "NA"
        line=f"  {lab}: CPU={r(cpu)}  GPU_serial={r(ser)}  GPU_parallel={r(par)}"
        if cpu and par: line+=f"   GPU/CPU(par)={par/cpu:.2f}x"
        if cpu and ser: line+=f"  GPU/CPU(ser)={ser/cpu:.2f}x"
        print(line)
