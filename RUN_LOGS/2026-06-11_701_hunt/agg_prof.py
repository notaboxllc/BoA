import json,re,sys,glob,os
from collections import defaultdict
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
def parse_run(path, warm=15):
    objs=load(path)
    ke=[o["chained"] for o in objs if "chained" in o and any(k.startswith("chained.") and isinstance(o["chained"][k],dict) for k in o["chained"])]
    ke=ke[warm:]
    if not ke: return None
    def A(key):
        v=[int(g[key]) for g in ke if key in g and not isinstance(g[key],dict)]
        return sum(v)/len(v)/1e6 if v else 0.0  # ms
    per=defaultdict(float); cnt=0
    for g in ke:
        for k,v in g.items():
            if k.startswith("chained.") and isinstance(v,dict) and "TASK_KERNEL_TIME" in v:
                per[k.replace('chained.','')]+=int(v["TASK_KERNEL_TIME"])/1e6
        cnt+=1
    per={k:v/cnt for k,v in per.items()}
    return dict(wall=A("TOTAL_TASK_GRAPH_TIME"), kernel=A("TOTAL_KERNEL_TIME"),
               copyin=A("COPY_IN_TIME"), copyout=A("COPY_OUT_TIME"),
               copyin_mb=sum(int(g["TOTAL_COPY_IN_SIZE_BYTES"]) for g in ke if "TOTAL_COPY_IN_SIZE_BYTES" in g)/len(ke)/1e6,
               n=len(ke), per=per)
def mean_runs(paths):
    rs=[parse_run(p) for p in paths]; rs=[r for r in rs if r]
    if not rs: return None
    keys=["wall","kernel","copyin","copyout","copyin_mb"]
    m={k:sum(r[k] for r in rs)/len(rs) for k in keys}
    sp={k:(max(r[k] for r in rs)-min(r[k] for r in rs))/2 for k in keys}
    allk=set(); [allk.update(r["per"]) for r in rs]
    perm={k:sum(r["per"].get(k,0) for r in rs)/len(rs) for k in allk}
    return m,sp,perm,len(rs)
if __name__=="__main__":
    base=sys.argv[1]
    for lab in ["1x","4x","8x","16x"]:
        paths=sorted(glob.glob(f"{base}/{lab}_r*.json"))
        if not paths: continue
        res=mean_runs(paths)
        if not res: continue
        m,sp,perm,nr=res
        print(f"\n=== {lab} (n={nr} runs) ===")
        print(f"  wall   {m['wall']:7.2f} ±{sp['wall']:.2f} ms   kernel {m['kernel']:6.3f} ms ({100*m['kernel']/m['wall']:.1f}%)")
        print(f"  COPY_IN {m['copyin']:6.2f} ±{sp['copyin']:.2f} ms ({100*m['copyin']/m['wall']:.1f}%)  {m['copyin_mb']:.1f} MB")
        print(f"  COPY_OUT{m['copyout']:6.2f} ±{sp['copyout']:.2f} ms ({100*m['copyout']/m['wall']:.1f}%)")
        ksum=sum(perm.values())
        for k in sorted(perm,key=lambda x:-perm[x]):
            print(f"      {k:15s} {perm[k]*1000:8.1f} us ({100*perm[k]/ksum:4.1f}% of kernel)")
