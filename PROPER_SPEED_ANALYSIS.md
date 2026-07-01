# Measuring Gliding-Assay Speed Properly — Method & Reference

**Date:** 2026-06-30/07-01. **Scope:** how to extract a defensible gliding velocity from a BoA gliding-assay run,
why the naive metrics disagree, and the resolved numbers for the axial-lock motor. Written so future analysis uses
**one** estimator. Measurement-only (no code changes); applies to any motor model.

---

## 1. The problem this resolves

Early sweep reports quoted **two** speeds that disagreed by 3–5×:
- "**net glide**" = (endpoint − start) of a tracked point over a window → 0.4–3.7 µm/s.
- "**track speed**" = `longWindowSpeedXY` from `gliding_assay.dat` (a per-window smoothed speed) → 5–10 µm/s.

And a **single-vs-mat** gap: the 6×6 dense-mat run reported ~8 µm/s at d=1000 while the 14×2 single-filament run
reported ~2.4 at the same density. Both turned out to be **measurement artifacts**, not physics.

---

## 2. The correct estimator (use this one, always)

**Gliding velocity = least-squares slope of the filament CENTROID vs time, over a steady window, projected onto the
filament's own long axis.**

Per run (or per filament in a multi-filament run):
1. **Centroid** each output frame: `c(t) = mean of all segment endpoints of that filament`
   (mean of `end1,end2` over the filament's segments; combine multi-segment filaments — do **not** treat each
   segment as a separate filament unless it genuinely is one).
2. **Steady window** `[t0, t_end]`: pick `t0` by **inspecting** `c(t)` for where the slope plateaus (past the
   startup transient). Do **not** hard-code 0.03 s. Verify convergence by refitting at a few `t0` — the slope
   should be stable.
3. **Least-squares linear fit** of each of `cx,cy,cz` vs `t` over `[t0,t_end]` → velocity vector `v = dc/dt`.
4. **Axial speed** `v_axial = v · f̂`, where `f̂` = the filament's pointed→barbed unit axis (from first segment
   `end1` to last segment `end2`). **Sign: negative = pointed-leading = correct** for a gliding assay.
5. Report `v_axial` (signed) as THE speed. Also report `|v|` and **axial fraction = |v_axial|/|v|** from the same
   fit; at high density this is ≈1.0 (drift is straight along the axis).

### Why the other two are wrong
- **`longWindowSpeedXY` / "track speed"** is a per-window *path* speed: it sums frame-to-frame displacement, so it
  counts the high-frequency **bind/release jitter** riding on top of the drift. Path length is ~3–5× the net drift,
  so this **over-reads by 3–5×**. It is *not* a translation velocity. (An experimentalist's camera integrates over
  this jitter and never sees it — another reason it's non-physical to quote.)
- **endpoint − start (single net)** is noise- and transient-sensitive: one jumpy start frame or a short window
  biases it badly. The mat's spurious ~8 µm/s came from this over the whole run *including* the startup transient.

### The subtle point that caused the confusion
Axial fraction ≈ 1.0 does **not** imply "net" and "track" converge. Axial fraction measures whether the *drift
direction* is axial — it says nothing about path straightness. A filament can drift cleanly along its axis
(axial-frac 1.0) while **jittering back and forth along that same axis**, so path ≫ net. The LS drift is the real
velocity; the path/track speed is the jitter.

---

## 3. Results — axial-lock motor (`myoFixedHeadNeckStroke` + `myoAxialSwingLock`, flag-gated)

Standard single-filament assay (`glidingAssay500_val` geometry: 14×2×0.5 µm, one 2 µm filament, Brownian on,
dt 1e-5). Density = motors/µm².

| Run | **v_axial** | \|v\| | axial frac | avg bound | t0 | window |
|---|---:|---:|---:|---:|---:|---:|
| single-fil 14×2, **d1000, 1.5 s** | **−2.09 µm/s** | 2.09 | 1.00 | 21.2 | 0.30 s | 1.20 s |
| single-fil 14×2, **d500, 1.5 s** | **−0.76 µm/s** | 0.77 | 0.99 | 17.4 | 0.30 s | 1.20 s |
| single-fil 14×2, d1000, 0.15 s (matched) | −2.26 | 2.32 | 0.97 | 21.7 | 0.02 s | 0.13 s |
| dense mat 6×6, d1000, 0.15 s (per-fil, n≈150) | −1.63 ± 0.81 | — | 0.83 | ~22 | 0.02 s | 0.13 s |

Long-run fits are window-independent (d500 slope −0.76 to −0.81 across t0; d1000 centroid trajectory monotonic
5.74→~3.6 over 1.5 s) → converged.

### Both discrepancies, resolved
1. **Two-speed disagreement = jitter.** Under the LS estimator, `v_axial ≈ |v|` (axial-frac →1.0) → **one**
   velocity (−2.09 at d1000). The 5–10 µm/s "track speed" was path-with-jitter (~3–5× the drift), not a velocity.
2. **Single-vs-mat 3–4× gap = metric mismatch.** With the *same* LS-centroid estimator on the *same* window:
   single −2.26, mat −1.63 µm/s → within ~1.4×, **no gap** (mat if anything slightly slower, plausibly from
   shorter/randomly-oriented filaments; spread ±0.81). The **narrow-2 µm-Y wall-drag hypothesis is not supported** —
   the narrow-box filament is the faster one. No single-vs-many effect large enough to matter.

### The one defensible number
**≈ 2.1 µm/s, pointed-leading, at 1000 motors/µm²** — LS-centroid drift over a ≥1 s steady window, axial fraction
1.00. In body lengths: **≈ 1.6 filament lengths over 1.5 s ≈ 1 body-length/s** (filament = 2 µm). d500 → ≈ 0.76 µm/s.

This ~2 µm/s is in the low end of the fast-skeletal-myosin-II gliding range; it is a consequence of the model's
stroke geometry/kinetics, not tuned to a specific myosin (see `FIXED_HEAD_NECK_STROKE_MOTOR.md`).

---

## 4. Reproduce the estimator

Data source: the per-frame `frame_*.json` in the `-3js` output dir (use these, not `gliding_assay.dat`, so you
control the centroid + window). Reference Python (no numpy needed):

```python
import json, glob, os, math
def ls_slope(ts, xs):                      # least-squares slope of xs vs ts
    n=len(ts); mt=sum(ts)/n; mx=sum(xs)/n
    return sum((ts[i]-mt)*(xs[i]-mx) for i in range(n)) / sum((t-mt)**2 for t in ts)

def centroid_series(run_dir):
    rec=[]
    for fp in sorted(glob.glob(os.path.join(run_dir,"frame_*.json"))):
        f=json.load(open(fp)); segs=sorted(f["segments"], key=lambda s:s["id"])
        pts=[p for s in segs for p in (s["end1"], s["end2"])]
        c=[sum(p[i] for p in pts)/len(pts) for i in range(3)]
        a,b=segs[0]["end1"], segs[-1]["end2"]; v=[b[i]-a[i] for i in range(3)]
        m=math.sqrt(sum(x*x for x in v)) or 1.0
        rec.append((f["t"], c[0], c[1], c[2], [x/m for x in v]))   # t, cx,cy,cz, fhat
    return rec

def v_axial(rec, t0):                       # pick t0 by inspecting the trajectory first
    w=[r for r in rec if r[0]>=t0]; ts=[r[0] for r in w]
    v=[ls_slope(ts,[r[k] for r in w]) for k in (1,2,3)]
    fhat=w[len(w)//2][4]
    va=sum(v[i]*fhat[i] for i in range(3)); vm=math.sqrt(sum(x*x for x in v))
    return va, vm, abs(va)/(vm+1e-12)       # signed axial speed, |v|, axial fraction
```
For a **multi-filament** run, group segments by filament first (one segment per filament in the seeded-mat runs,
keyed by segment `id`; the frame JSON currently carries **no** `filamentId`, so combine by whatever identifies a
filament in your config) and fit each separately; report mean ± spread.

Run dirs used here: `~/Code/threejs_output/axlock_d500_long`, `…/axlock_d1000_long` (1.5 s),
`…/axlock_sweep_d1000`, `…/glidingDenseMat_axlock` (0.15 s).

---

## 5. Rules of thumb for future speed measurements
- **Always** use the LS-centroid-along-axis drift. Ignore `longWindowSpeedXY` for velocity (it's jitter-inflated).
- **Run long enough** that the steady window is ≥ ~1 s and much larger than the transient; pick `t0` by inspection.
- **Report the axial fraction** — if it's well below 1, the filament is wandering and a single axial speed is
  suspect (low density / detachment regime).
- **Compare like with like** — never contrast a whole-run-endpoint number against a steady-window number.
- Body-lengths (net axial displacement / filament length) is a good sanity check and the most experiment-legible
  unit.
