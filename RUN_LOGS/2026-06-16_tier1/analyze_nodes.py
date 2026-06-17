#!/usr/bin/env python3
# Membrane node stability analysis: clumping (link-length spread) and jitter (frame-to-frame node motion).
import json, glob, sys, math

def stats(xs):
    n=len(xs)
    if n==0: return (0,0,0,0)
    m=sum(xs)/n
    sd=math.sqrt(sum((x-m)**2 for x in xs)/n)
    return (m,sd,min(xs),max(xs))

def analyze(d):
    rows=[]
    for run in d:
        frames=sorted(glob.glob(f'threejs_output/{run}/frame_*.json'))
        prev=None
        print(f'\n=== {run}  ({len(frames)} frames) ===')
        print(f'{"t":>8} {"rMean":>7} {"rStd":>6} {"rMin":>6} {"rMax":>6} {"lkMean":>7} {"lkCoV":>6} {"lkMin":>6} {"lkMax":>6} {"jitRMS":>8} {"jitMax":>8}')
        for fp in frames[::max(1,len(frames)//40)]:
            fr=json.load(open(fp))
            nodes={n['id']:n['center'] for n in fr.get('nodes',[]) if n.get('membrane')}
            t=fr.get('t',0)
            # radial
            R=[math.dist(c,(0,0,0)) for c in nodes.values()]
            rM,rS,rmin,rmax=stats(R)
            # link lengths
            lk=[]
            for a,b in fr.get('membraneLinks',[]):
                if a in nodes and b in nodes: lk.append(math.dist(nodes[a],nodes[b]))
            lM,lS,lmin,lmax=stats(lk)
            cov=(lS/lM) if lM else 0
            # jitter vs prev frame (same ids)
            jit=[]
            if prev is not None:
                for i,c in nodes.items():
                    if i in prev: jit.append(math.dist(c,prev[i]))
            jM,jS,jmin,jmax=stats(jit)
            print(f'{t:8.4f} {rM:7.4f} {rS:6.4f} {rmin:6.3f} {rmax:6.3f} {lM:7.4f} {cov:6.3f} {lmin:6.4f} {lmax:6.3f} {jM:8.5f} {jmax:8.5f}')
            prev=nodes

analyze(sys.argv[1:] if len(sys.argv)>1 else ['t1_baseline','t1_tier1'])
