#!/usr/bin/env python3
"""
Minifilament spin metric (Path B Phase 2a validation).

Reproduces the cohesion-survey "rotation-about-bundle-center" metric. For each
myosin, measure the signed angular displacement of its rod center about the
minifilament (bundle) axis, frame to frame, and accumulate:

  net rotation  = sum over frames of the per-frame mean Δθ across myosins  (deg)
  coherence     = |sum Δθ| / sum |Δθ|   (1 = ballistic/coherent spin, 0 = diffusive)

The signed per-myosin per-transition rotation about the bundle axis â is
  Δθ = atan2( (p_f × p_{f+1})·â , p_f · p_{f+1} )
where p = component of (rod_center − bundle_center) perpendicular to â. This is
basis-free and directly measures rotation about the bundle center, matching the
survey definition. Output: per-myosin mean net rotation + mean coherence, and the
aggregate (mean-of-cloud) net rotation + coherence.

Usage: spin_metric.py <frame_dir> [maxframes]
"""
import json, sys, glob, os, math

def v_sub(a, b): return [a[0]-b[0], a[1]-b[1], a[2]-b[2]]
def v_add(a, b): return [a[0]+b[0], a[1]+b[1], a[2]+b[2]]
def v_scale(a, s): return [a[0]*s, a[1]*s, a[2]*s]
def v_dot(a, b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def v_cross(a, b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
def v_norm(a): return math.sqrt(v_dot(a, a))
def v_unit(a):
    n = v_norm(a)
    return [a[0]/n, a[1]/n, a[2]/n] if n > 0 else [0.0, 0.0, 0.0]
def mid(e1, e2): return [(e1[0]+e2[0])*0.5, (e1[1]+e2[1])*0.5, (e1[2]+e2[2])*0.5]

def perp(w, ahat):
    d = v_dot(w, ahat)
    return v_sub(w, v_scale(ahat, d))

def load(frame_dir, maxframes=None):
    files = sorted(glob.glob(os.path.join(frame_dir, "frame_*.json")))
    if maxframes: files = files[:maxframes]
    frames = []
    for f in files:
        d = json.load(open(f))
        if not d.get("minifilaments"): continue
        mf = d["minifilaments"][0]
        C = mid(mf["end1"], mf["end2"])
        ahat = v_unit(v_sub(mf["end2"], mf["end1"]))
        myos = {}
        for m in d.get("myosins", []):
            rc = mid(m["rod"]["end1"], m["rod"]["end2"])
            myos[m["id"]] = rc
        frames.append((C, ahat, myos))
    return frames

def analyze(frames):
    if len(frames) < 2: return None
    # per-myosin accumulators
    per_net = {}      # signed sum of Δθ
    per_abs = {}      # sum of |Δθ|
    cloud_dtheta = [] # per-transition mean Δθ across myosins
    for k in range(len(frames)-1):
        C0, a0, m0 = frames[k]
        C1, a1, m1 = frames[k+1]
        ahat = v_unit(v_add(a0, a1))  # average axis over the transition
        dthetas = []
        for mid_id in m0:
            if mid_id not in m1: continue
            p0 = perp(v_sub(m0[mid_id], C0), ahat)
            p1 = perp(v_sub(m1[mid_id], C1), ahat)
            if v_norm(p0) < 1e-9 or v_norm(p1) < 1e-9: continue
            cr = v_cross(p0, p1)
            s = v_dot(cr, ahat)
            c = v_dot(p0, p1)
            dth = math.degrees(math.atan2(s, c))
            dthetas.append(dth)
            per_net[mid_id] = per_net.get(mid_id, 0.0) + dth
            per_abs[mid_id] = per_abs.get(mid_id, 0.0) + abs(dth)
        if dthetas:
            cloud_dtheta.append(sum(dthetas)/len(dthetas))
    # per-myosin coherence
    coh_list = [abs(per_net[i])/per_abs[i] for i in per_net if per_abs[i] > 0]
    net_list = [per_net[i] for i in per_net]
    mean_net = sum(net_list)/len(net_list) if net_list else 0.0
    mean_coh = sum(coh_list)/len(coh_list) if coh_list else 0.0
    # cloud-aggregate
    cloud_net = sum(cloud_dtheta)
    cloud_abs = sum(abs(x) for x in cloud_dtheta)
    cloud_coh = abs(cloud_net)/cloud_abs if cloud_abs > 0 else 0.0
    return dict(n_frames=len(frames), n_myo=len(per_net),
                per_myo_mean_net_deg=mean_net, per_myo_mean_coherence=mean_coh,
                cloud_net_deg=cloud_net, cloud_coherence=cloud_coh)

if __name__ == "__main__":
    d = sys.argv[1]
    mx = int(sys.argv[2]) if len(sys.argv) > 2 else None
    fr = load(d, mx)
    r = analyze(fr)
    if r is None:
        print(f"{d}: <2 usable frames"); sys.exit(0)
    print(f"{d}")
    print(f"  frames={r['n_frames']}  myosins={r['n_myo']}")
    print(f"  per-myosin: mean net rotation = {r['per_myo_mean_net_deg']:+.2f} deg   mean coherence = {r['per_myo_mean_coherence']:.3f}")
    print(f"  cloud:      net rotation      = {r['cloud_net_deg']:+.2f} deg   coherence      = {r['cloud_coherence']:.3f}")
