#!/usr/bin/env python3
"""
Branched-actin-network spin metric (Kabsch, frame-to-frame).

For each consecutive frame pair, take the actin segments present in BOTH frames
(matched by stable `id`), use their midpoints as a point cloud, and compute the
optimal rigid rotation (Kabsch) of the cloud about its centroid. Accumulate:

  net rotation = | sum of signed per-transition rotation vectors |   (deg)
  total path   = sum of |per-transition rotation angle|             (deg)
  coherence    = net rotation / total path   (1 = ballistic coherent spin, 0 = diffusive tumble)

A coherent junction spin shows large net rotation AND coherence near 1.
Brownian tumbling shows total path > 0 but coherence near 0.

Usage: branch_spin.py <frame_dir> [first_frame] [last_frame]
"""
import json, sys, glob, os
import numpy as np

def load_mids(path):
    d = json.load(open(path))
    out = {}
    for s in d.get("segments", []):
        e1, e2 = s["end1"], s["end2"]
        out[s["id"]] = ((e1[0]+e2[0])*0.5, (e1[1]+e2[1])*0.5, (e1[2]+e2[2])*0.5)
    return out

def kabsch_R(P, Q):
    # rotation mapping P->Q (both already centered), proper rotation
    H = P.T @ Q
    U, S, Vt = np.linalg.svd(H)
    d = np.sign(np.linalg.det(Vt.T @ U.T))
    D = np.diag([1.0, 1.0, d])
    return Vt.T @ D @ U.T

def rotvec(R):
    # axis-angle vector (radians) from a rotation matrix
    ang = np.arccos(np.clip((np.trace(R) - 1.0) / 2.0, -1.0, 1.0))
    if ang < 1e-9:
        return np.zeros(3), 0.0
    ax = np.array([R[2,1]-R[1,2], R[0,2]-R[2,0], R[1,0]-R[0,1]])
    n = np.linalg.norm(ax)
    if n < 1e-12:
        return np.zeros(3), ang
    return ax / n * ang, ang

def main():
    d = sys.argv[1]
    files = sorted(glob.glob(os.path.join(d, "frame_*.json")))
    f0 = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    f1 = int(sys.argv[3]) if len(sys.argv) > 3 else len(files)
    files = files[f0:f1]
    if len(files) < 2:
        print("need >=2 frames"); return
    net = np.zeros(3); total = 0.0; transitions = 0; mincommon = 1e9; maxn = 0
    prev = load_mids(files[0])
    for f in files[1:]:
        cur = load_mids(f)
        common = sorted(set(prev) & set(cur))
        maxn = max(maxn, len(cur))
        if len(common) >= 4:
            P = np.array([prev[i] for i in common])
            Q = np.array([cur[i]  for i in common])
            P -= P.mean(0); Q -= Q.mean(0)
            rv, ang = rotvec(kabsch_R(P, Q))
            net += rv; total += ang; transitions += 1
            mincommon = min(mincommon, len(common))
        prev = cur
    netdeg = np.degrees(np.linalg.norm(net))
    totdeg = np.degrees(total)
    coh = netdeg / totdeg if totdeg > 0 else 0.0
    print(f"{os.path.basename(d.rstrip('/')):28s} frames[{f0}:{f1}] trans={transitions:3d} "
          f"maxSeg={maxn:4d} minCommon={int(mincommon) if mincommon<1e9 else 0:4d}  "
          f"netRot={netdeg:7.1f} deg  pathRot={totdeg:7.1f} deg  coherence={coh:.3f}")

if __name__ == "__main__":
    main()
