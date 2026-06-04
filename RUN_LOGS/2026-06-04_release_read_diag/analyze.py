#!/usr/bin/env python3
# Analyze release-read divergence between device and freshCPU arms, and
# compute the single-arm lag-counterfactual flip count on freshCPU.

import csv, collections, math, statistics, sys, os

BASE = os.path.dirname(os.path.abspath(__file__))

# Guo & Guilford constants — from Env defaults. Read directly from JOURNAL +
# code: alphaCatch, alphaSlip, kOff, xCatch, xSlip, deltaT, tempK, Boltz, and
# myosinBreakForce. The single-arm counterfactual only needs these to
# recompute p(forceDotFil).
# These are from grep of Env.java:
# Hardcoded from Env.java defaults (verified by grep of *_init in Env.java)
# and the runtime override of deltaT to 1e-5 in glidingAssay500_val_releaseread.
ALPHA_CATCH = 0.92         # alphaCatch_init
ALPHA_SLIP  = 0.08         # alphaSlip_init
K_OFF       = 100.0        # kOff_init
X_CATCH     = 2.5e-9       # xCatch_init  (m)
X_SLIP      = 0.4e-9       # xSlip_init   (m)
TEMP_K      = 298.15       # Env.tempK
BOLTZ       = 1.380662e-23 # Env.Boltz
DELTA_T     = 1.0e-5       # overridden by param file
print(f"Constants used: alphaCatch={ALPHA_CATCH} alphaSlip={ALPHA_SLIP} kOff={K_OFF}"
      f" xCatch={X_CATCH} xSlip={X_SLIP} tempK={TEMP_K} Boltz={BOLTZ} dt={DELTA_T}")

def guo_prob(forceDotFil):
    """Per-call release probability (matches MyoFilLink.ckRelease.guoCatchSlipProb*dt)."""
    catch = ALPHA_CATCH * math.exp(-forceDotFil * X_CATCH / (BOLTZ * TEMP_K))
    slip  = ALPHA_SLIP  * math.exp( forceDotFil * X_SLIP  / (BOLTZ * TEMP_K))
    return K_OFF * (catch + slip) * DELTA_T

def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append((
                int(r['step']),
                int(r['motorId']),
                int(r['segId']),
                float(r['forceMag']),
                float(r['forceDotFil']),
                float(r['trackedAvg']),
                int(r['releaseFired']),
            ))
    return rows

def by_motor(rows):
    out = collections.defaultdict(dict)
    for step, mid, sid, fM, fD, fA, fired in rows:
        out[mid][step] = (sid, fM, fD, fA, fired)
    return out

SEEDS = ["1", "2", "3"]
print()
print("====== PER-SEED, PER-ARM SUMMARY ======")
totals = {}
for arm in ("device","freshCPU"):
    totals[arm] = {"rows":0, "motors":set(), "fired":0}
    for s in SEEDS:
        rows = load(os.path.join(BASE, f"{arm}_seed{s}.csv"))
        mids = set(r[1] for r in rows)
        fired = sum(r[6] for r in rows)
        totals[arm]["rows"] += len(rows)
        totals[arm]["motors"] |= mids
        totals[arm]["fired"] += fired
        print(f"  {arm} seed={s}: rows={len(rows):6d}  motors={len(mids):4d}  fired={fired}")
print(f"  {arm} totals: rows={totals[arm]['rows']}  motors={len(totals[arm]['motors'])}  fired={totals[arm]['fired']}")

print()
print("====== MATCHED-KEY (device vs freshCPU at same motorId+step) ======")
all_inter_records = 0
all_motors_common = set()
seg_match_total = 0
seg_match_count = 0
xarm_devN_cpuN_abs   = []
xarm_devN_cpuNm1_abs = []
xarm_devN_cpuN       = []
xarm_devN_cpuNm1     = []
cpu_stepchange_abs   = []
for s in SEEDS:
    dev = by_motor(load(os.path.join(BASE, f"device_seed{s}.csv")))
    cpu = by_motor(load(os.path.join(BASE, f"freshCPU_seed{s}.csv")))
    common = set(dev) & set(cpu)
    all_motors_common |= common
    inter = 0
    for mid in common:
        sd = set(dev[mid]); sc = set(cpu[mid])
        for st in sorted(sd & sc):
            inter += 1
            d = dev[mid][st]; c = cpu[mid][st]
            if d[0] == c[0]:
                seg_match_total += 1
                # only include force comparisons when bound to SAME seg
                xarm_devN_cpuN.append(d[2] - c[2])
                xarm_devN_cpuN_abs.append(abs(d[2] - c[2]))
            seg_match_count += 1
            if (st-1) in cpu[mid]:
                c_prev = cpu[mid][st-1]
                if d[0] == c[0] == c_prev[0]:
                    xarm_devN_cpuNm1.append(d[2] - c_prev[2])
                    xarm_devN_cpuNm1_abs.append(abs(d[2] - c_prev[2]))
                if c_prev[0] == c[0]:
                    cpu_stepchange_abs.append(abs(c[2] - c_prev[2]))
    all_inter_records += inter
    print(f"  seed={s}: common motors {len(common):4d}  matched (mid,step) records {inter}")
print(f"  ALL seeds: common motors {len(all_motors_common)}  matched records {all_inter_records}")
print(f"  segId agreement on matched (mid,step): {seg_match_total}/{seg_match_count}")
if xarm_devN_cpuN_abs:
    print(f"  device[N]   - cpu[N]   (matched, same seg): n={len(xarm_devN_cpuN_abs)} "
          f"mean(signed)={statistics.mean(xarm_devN_cpuN):.3e} "
          f"<|.|>={statistics.mean(xarm_devN_cpuN_abs):.3e}")
if xarm_devN_cpuNm1_abs:
    print(f"  device[N]   - cpu[N-1] (matched, same seg): n={len(xarm_devN_cpuNm1_abs)} "
          f"mean(signed)={statistics.mean(xarm_devN_cpuNm1):.3e} "
          f"<|.|>={statistics.mean(xarm_devN_cpuNm1_abs):.3e}")
if cpu_stepchange_abs:
    print(f"  cpu[N]      - cpu[N-1] (same seg, step-to-step):   n={len(cpu_stepchange_abs)} "
          f"<|.|>={statistics.mean(cpu_stepchange_abs):.3e}")

print()
print("====== AGGREGATE FORCEDOTFIL DISTRIBUTION (per arm, all bound-motor records) ======")
arm_fD = {"device":[], "freshCPU":[]}
arm_fM = {"device":[], "freshCPU":[]}
arm_avg= {"device":[], "freshCPU":[]}
for arm in ("device","freshCPU"):
    for s in SEEDS:
        for row in load(os.path.join(BASE, f"{arm}_seed{s}.csv")):
            _,_,_,fM,fD,fA,_ = row
            arm_fD[arm].append(fD)
            arm_fM[arm].append(fM)
            arm_avg[arm].append(fA)
for arm in ("device","freshCPU"):
    fD = arm_fD[arm]; fM = arm_fM[arm]; fA = arm_avg[arm]
    print(f"  {arm}: n={len(fD)}  forceDotFil mean={statistics.mean(fD):.3e} sd={statistics.stdev(fD):.3e}  "
          f"forceMag mean={statistics.mean(fM):.3e} sd={statistics.stdev(fM):.3e}  "
          f"trackedAvg mean={statistics.mean(fA):.3e} sd={statistics.stdev(fA):.3e}")
# t-stat for arm mean difference on forceDotFil
import math as _m
m1=statistics.mean(arm_fD["device"]); s1=statistics.stdev(arm_fD["device"]); n1=len(arm_fD["device"])
m2=statistics.mean(arm_fD["freshCPU"]); s2=statistics.stdev(arm_fD["freshCPU"]); n2=len(arm_fD["freshCPU"])
sem = _m.sqrt(s1*s1/n1 + s2*s2/n2)
print(f"  diff device - freshCPU = {m1-m2:.3e}   z = {(m1-m2)/sem:.2f}")

print()
print("====== SINGLE-ARM CPU COUNTERFACTUAL (lag direction test) ======")
# For each (motorId, step) on freshCPU arm where (step-1) is also in that motor's
# track AND the motor wasn't released at N-1 (so it's still bound at N), compute:
#   p_now  = Guo(forceDotFil[N])
#   p_prev = Guo(forceDotFil[N-1])
# Then count, over the whole window:
#   "release_dropped": p_now > p_prev (lag predicts fewer releases)
#   "release_added":   p_now < p_prev (lag predicts more releases)
#   net_p_diff: sum(p_prev - p_now)  (expected counterfactual change in fired events under lag)
n_pairs = 0
sum_p_now  = 0.0
sum_p_prev = 0.0
sum_p_diff = 0.0   # p_prev - p_now: positive => lag would produce MORE releases
n_p_now_gt_prev = 0   # lag would DROP a release (lag = fewer)
n_p_now_lt_prev = 0
n_actually_fired = 0
# also track p_diff for actual-fired-at-N events specifically:
fired_drop = 0   # would NOT have fired under lag (lag misses it)
fired_keep = 0   # would have fired anyway under lag
nonfired_to_fired = 0  # would HAVE fired under lag (lag adds release)
for s in SEEDS:
    cpu = by_motor(load(os.path.join(BASE, f"freshCPU_seed{s}.csv")))
    for mid, byst in cpu.items():
        steps = sorted(byst)
        for st in steps:
            if (st-1) not in byst: continue
            _, fM, fD, _, fired = byst[st]
            _, _, fD_prev, _, fired_prev = byst[st-1]
            # exclude breakForce auto-releases — skip if forceMag > break (very rare)
            # Just use the catch/slip term; if it fires from breakForce it's not affected by lag.
            p_now  = guo_prob(fD)
            p_prev = guo_prob(fD_prev)
            n_pairs += 1
            sum_p_now  += p_now
            sum_p_prev += p_prev
            sum_p_diff += (p_prev - p_now)
            if p_now > p_prev: n_p_now_gt_prev += 1
            elif p_now < p_prev: n_p_now_lt_prev += 1
            if fired:
                n_actually_fired += 1
                # actual roll U was < p_now. Lagged: would fire iff U < p_prev.
                # If p_prev >= p_now, would fire (lag keeps it). If p_prev < p_now, might miss.
                if p_prev >= p_now: fired_keep += 1
                else: fired_drop += 1   # underestimate of drops (only certain if p_prev < U < p_now)
            else:
                # actual U was >= p_now. Lagged: would fire iff U < p_prev.
                # Only certain to fire under lag if p_prev > U, but we don't know U.
                # We can say: if p_prev > p_now, lagged COULD fire if U is in (p_now, p_prev).
                pass

print(f"  CPU-only n_pairs (mid,step) with bound at N and N-1: {n_pairs}")
print(f"  sum p_now    = {sum_p_now:.4f}      (expected releases at N)")
print(f"  sum p_prev   = {sum_p_prev:.4f}      (expected releases if lag)")
print(f"  sum p_prev - p_now = {sum_p_diff:.4e}   (net counterfactual fired change under lag)")
print(f"  # (mid,step) where p[N-1] < p[N]: {n_p_now_gt_prev}   (lag would DROP a release: fewer releases)")
print(f"  # (mid,step) where p[N-1] > p[N]: {n_p_now_lt_prev}   (lag would ADD a release: more releases)")
print(f"  actual fired_at_N records: {n_actually_fired}")
print(f"    of which would still fire under lag (p_prev >= p_now): {fired_keep}")
print(f"    of which might be lost under lag    (p_prev <  p_now): {fired_drop}")

# Distribution of per-step Δ(forceDotFil) for the same-motor, same-seg case
deltas = []
for s in SEEDS:
    cpu = by_motor(load(os.path.join(BASE, f"freshCPU_seed{s}.csv")))
    for mid, byst in cpu.items():
        steps = sorted(byst)
        for st in steps:
            if (st-1) not in byst: continue
            seg_now, _, fD, _, _ = byst[st]
            seg_prev, _, fD_prev, _, _ = byst[st-1]
            if seg_now == seg_prev:
                deltas.append(fD - fD_prev)
print()
print(f"  CPU forceDotFil per-step change (same seg): n={len(deltas)} "
      f"mean={statistics.mean(deltas):.3e} <|.|>={statistics.mean(abs(d) for d in deltas):.3e} "
      f"sd={statistics.stdev(deltas):.3e}")
