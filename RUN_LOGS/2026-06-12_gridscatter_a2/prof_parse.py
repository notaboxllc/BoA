#!/usr/bin/env python3
# Parse a TornadoVM profiler JSON dump (chained graph), warmup-skip 15 executes,
# print mean ms/execute for gridScatter, total kernel, graph wall, copyOut.
import json, re, sys
from collections import defaultdict
WARM = 15
def load(path):
    txt = open(path).read(); txt = re.sub(r',(\s*[}\]])', r'\1', txt)
    dec = json.JSONDecoder(); i = 0; n = len(txt); objs = []
    while i < n:
        while i < n and txt[i] in ' \t\r\n': i += 1
        if i >= n: break
        try: o, j = dec.raw_decode(txt, i)
        except Exception: break
        objs.append(o); i = j
    return objs
objs = load(sys.argv[1])
graphs = [o["chained"] for o in objs if "chained" in o][WARM:]
if not graphs:
    print("NO_GRAPHS"); sys.exit(0)
kt = defaultdict(float); wall = copout = totk = 0.0
for g in graphs:
    for k, v in g.items():
        if k.startswith("chained.") and isinstance(v, dict) and "TASK_KERNEL_TIME" in v:
            kt[k[len("chained."):]] += int(v["TASK_KERNEL_TIME"]) / 1e6
    wall   += int(g.get("TOTAL_TASK_GRAPH_TIME", 0)) / 1e6
    copout += int(g.get("COPY_OUT_TIME", 0)) / 1e6
    totk   += int(g.get("TOTAL_KERNEL_TIME", 0)) / 1e6
n = len(graphs); f = 1.0/n
gs = kt.get("gridScatter", 0)*f
print("executes=%d gridScatter=%.3f totalKernel=%.3f graphWall=%.3f copyOut=%.3f" % (
      n, gs, totk*f, wall*f, copout*f))
