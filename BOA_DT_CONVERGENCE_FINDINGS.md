# dt-convergence at coltol8/d1000: active BoA SATURATES TOO — it loses its duty cycle as dt→0, exactly like v2

**Date:** 2026-07-03. **Measurement-only** (existing params + a byte-identical-when-off census extension; no
model/release/stroke/kinetics change; `BoA-v1ref` untouched). Active BoA (`~/Code/BoA`), **CPU**, default f̂-directed
neck-stroke motor, single mat draw per dt. coltol8 (`myoColTol`=8 nm), density 1000, `CAPTURE_RADIUS_REPLICATE`
protocol (mat bed 14×2×0.5 µm, LS-centroid drift along f̂ over window [0.30,0.70] s), aeta=0.1, Brownian on.
`runTime`=0.7 s held at every dt ⇒ identical sim-time window; step count scales 70k/140k/280k. Raw:
`RUN_LOGS/2026-07-03_dtconv_boa/` (`analyze.py`, `analysis_out.txt`, `run_dt{1e5,5e6,2p5e6}.log`).

## PLAIN ANSWER — does active BoA also saturate (lose its duty cycle) as dt→0?

**YES. Unambiguously.** Refining dt 4× (1e-5 → 2.5e-6) drives BoA's **avgBound explosion 19.6 → 163.9 (8.4×,
toward saturation)**, **per-bound drift collapse 0.258 → 0.0068 (38× LOWER, monotonic)**, **windowed dwell
0.233 → 0.711 ms (3× LONGER)**, and **per-head detachment 4317 → 1402 events/s (3× LOWER)**. BoA holds **no**
finite duty cycle in the dt→0 limit — its glide-per-bound-head craters toward zero just as v2's does. BoA's
avgBound trajectory (19.6/68.6/163.9) is **nearly identical to v2's** (17.9/69.1/157.9), and BoA's drift, though
3× higher at coarse dt, **lands on v2's converged value** (0.0068 vs v2's 0.0077 at dt=2.5e-6). **The two codes
converge to the SAME near-saturated low limit.**

**⇒ FORK: BoA SATURATES TOO. The missing dt-robust detachment is UNIVERSAL (both codes). The coarse-dt gather
split (v2 0.081/0.090 vs BoA 0.248/0.258) is an OPERATING-POINT ARTIFACT — both codes are unconverged at
dt=1e-5 and land at different points on the same disease. Gather question CLOSED (no gather redesign warranted).
The real problem is the detachment pathway, for both codes.**

---

## 1. The dt-refinement series (BoA, coltol8/d1000, single draw, matched 0.7 s sim time, window [0.30,0.70])

| dt | steps | avgBound | net \|v_axial\| | **per-bound drift** | dwell_win (ms) | detach/head (/s) | break-cap frac | \|forceDotFil\| (pN) | axialFrac | wall |
|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 1e-5 (production default) | 70k | 19.61 | 5.062 | **0.2581** | 0.233 | 4317 | 0.646 | 3.183 | 0.994 | 73 min |
| 5e-6 | 140k | 68.64 | 2.192 | **0.0319** | 0.500 | 2003 | 0.589 | 2.724 | 0.994 | 129 min |
| 2.5e-6 | 280k | 163.89 | 1.108 | **0.0068** | 0.711 | 1402 | 0.670 | 2.288 | 0.993 | 213 min |

per-bound drift = |v_axial| / avgBound. axialFrac ≥0.99 everywhere and no run reached a bed edge (coverage clean),
so every velocity is a clean axial glide over the full [0.30,0.70] s window. detach/head = (Δbreak-cap + Δcatch-slip
releases over the window) / window / avgBound; dwell_win is the window-differenced episode dwell (backed out from the
cumulative census + release counters, transient removed); break-cap frac = Δbreak-cap / Δ(all releases).

**Six monotonic trends as dt→0 — every one is a saturation signature:**
- **avgBound → saturation.** 19.6 → 68.6 → 163.9; still climbing hard (increments +49, +95) toward the same
  ~220 all-reachable plateau v2 hit at dt=1.25e-6. Refining dt removes the false over-detachment ⇒ engagement
  climbs toward its true (near-saturated carpet) value.
- **per-bound drift → ~0.007.** 0.258 → 0.032 → 0.0068; craters 38×, monotone, no upturn. Low because the same
  ~1.1 µm/s net is spread over ~8× more bound heads — the tug-of-war carpet.
- **net glide → a finite limit ~1.1 µm/s.** 5.062 → 2.192 → 1.108; the coarse-dt 5.06 is a numerical over-estimate
  that collapses under refinement toward v2's converged ~1.2.
- **windowed dwell → ∞ direction.** 0.233 → 0.500 → 0.711 ms; heads stay bound ~3× longer as detachment slows.
- **per-head detachment → 0 direction.** 4317 → 2003 → 1402 events/head/s; the direct signature of detachment
  stopping. (Whole-bed detach/s *rises* 84k→230k only because avgBound rises 8×; per head it falls 3×.)
- **catch-slip load (|forceDotFil|) drops.** 3.18 → 2.72 → 2.29 pN; the per-head load falls 28% as the F8
  overshoot is removed — **confirming the coarse-dt load is inflated by numerical overshoot** (the v2 mechanism).

## 2. The overshoot mechanism in BoA — confirmed on load, with one nuance on the break-cap

v2's mechanism: at coarse dt the F8 cross-bridge **overshoots**, spiking the load and firing catch-slip / the 12 pN
break-cap far more than the physical load would; as dt→0 the overshoot vanishes, the load sits at its true (low)
value, and detachment nearly stops. **BoA confirms the load half directly:** per-head load drops 3.18 → 2.29 pN and
per-head detachment craters 4317 → 1402 /s as dt→0 — the overshoot is being removed and detachment is slowing, the
saturation trend.

**Nuance (a real BoA/v2 detail, not a refutation):** BoA's **break-cap fraction does NOT vanish** — it stays
~0.59–0.67 at *every* dt (the 12 pN forceMag cap remains BoA's dominant detachment channel throughout, not just at
coarse dt). So refining dt in BoA slows *both* channels proportionally rather than shifting detachment from cap to
catch-slip. The transient (t<0.02 s) *looked* like the clean v2 picture (dt=2.5e-6 fired zero break-caps while
settling), but in the steady near-saturated carpet the heads are stretched enough (ext ~4.5 nm, forceMag near the
12 pN cap) that ~65% of the now-rarer detachments still fire via the cap. Either way the conclusion is identical:
**detachment slows toward zero and the duty cycle collapses — there is no dt-robust operating point.**

## 3. Side-by-side: BoA vs v2 dt-behavior (the two codes' dt-refined trajectories)

| dt | **BoA** avgBound | **v2** avgBound | **BoA** drift | **v2** drift | **BoA** net \|v_ax\| | **v2** net \|v_ax\| |
|--:|--:|--:|--:|--:|--:|--:|
| 1e-5 | 19.61 | 17.88 | **0.2581** | **0.0900** | 5.062 | 1.609 |
| 5e-6 | 68.64 | 69.10 | 0.0319 | 0.0193 | 2.192 | 1.332 |
| 2.5e-6 | 163.89 | 157.93 | **0.0068** | **0.0077** | 1.108 | 1.213 |
| 1.25e-6 | — (not run) | 216.27 | — | 0.0056 | — | 1.201 |

(v2 numbers from `~/Code/SoftBox/DT_CONVERGENCE_SEGGATHER_FINDINGS.md`, GPU device-resident, seed 0.)

Read straight off the table:
- **avgBound: the two codes track each other almost point-for-point** (19.6≈17.9, 68.6≈69.1, 163.9≈157.9). Same
  engagement explosion, same trajectory, same approach to the ~220 all-reachable plateau.
- **drift: both crater monotonically; they CONVERGE.** BoA starts 3× higher at coarse dt (0.258 vs 0.090) but by
  dt=2.5e-6 the two are within scatter (0.0068 vs 0.0077). The coarse-dt "split" **closes under refinement** — it
  is not a converged scheme difference.
- **net glide: both fall to ~1.1–1.2 µm/s.** BoA's coarse 5.06 (vs v2's 1.61) is the one genuinely large coarse-dt
  discrepancy between the codes — and it **washes out** under refinement (BoA 1.108 vs v2 1.213 at dt=2.5e-6, <10%).

**So the entire coarse-dt BoA↔v2 gather split is a dt/operating-point artifact.** At dt=1e-5 the two codes sit at
different points (BoA glides 3× faster with slightly more bound heads); refine dt and both collapse onto the same
near-saturated low-drift limit. Nothing about the split survives convergence.

## 4. FORK VERDICT — BoA SATURATES TOO ⇒ universal detachment problem, gather CLOSED

**→ Active BoA loses its duty cycle as dt→0 exactly like v2:** avgBound explodes (8.4×), per-bound drift craters
(38×), dwell lengthens (3×), per-head detachment craters (3×). It does **not** hold a finite avgBound~20 / drift~0.25
as dt→0 — the coarse-dt (0.258) point is unconverged, and refinement drives it to v2's saturated limit (~0.007).
Therefore:

- **The gather question is CLOSED.** The coarse-dt drift split (v2 0.081/0.090 vs BoA 0.248/0.258 at dt=1e-5) is an
  **operating-point artifact** of two unconverged codes at different dt/engagement points, not a converged scheme
  difference. Both codes converge to the same degenerate near-saturated carpet. **No fresh-force (Gauss–Seidel)
  seg-gather redesign is warranted** — it would be chasing an artifact. (This closes the branch v2's own dt-series
  already pointed at: `DT_CONVERGENCE_SEGGATHER_FINDINGS.md` §5 read #1.)
- **The real problem is the detachment pathway — universal to both codes.** Catch-slip-on-F8 (± a 12 pN break-cap)
  has **no dt-robust duty cycle**: as the integration converges, the false over-detachment that propped up the
  duty cycle vanishes, nothing detaches, and the model freezes into a fully-bound carpet with drift→0. Both codes
  need a **dt-independent detachment** — a nucleotide/ATP-binding-limited, catch-slip-modulated release whose rate
  does not depend on a numerical force overshoot — before either has a physical, converged gliding operating point.
  This is the standing `substep-feasibility` / dt-faithful-ceiling story, now confirmed to bind **both** codes.

## 5. Staging / cost / caveats

- **Staging as specified:** dt=1e-5 + dt=2.5e-6 probe launched first (2 runs) to confirm direction + wall time; the
  transient already showed the overshoot signature within seconds, and dt=5e-6 was then filled to complete the
  series. All three ran concurrently on aorus1 (16 cores, CPU); 73 / 129 / 213 min wall.
- **Single-draw is decisive on direction.** The effect is a **38× monotone** drift collapse with an 8× avgBound
  explosion — the same scale as v2's 16×/12×. BoA's coarse-dt point reproduces the `CAPTURE_RADIUS_REPLICATE`
  3-draw coltol8 mean (avgBound 19.6 vs 19.76±0.16; drift 0.258 vs 0.248±0.028) within seed scatter, so the coarse
  anchor is representative and the monotone trajectory dwarfs mat-draw noise. A multi-draw grid would refine the
  exact converged numbers but cannot flip a 38× monotone verdict.
- **The dt→0 limit is a near-SATURATED over-bound state** (as in v2): per-bound drift is only an apples-to-apples
  scheme comparison at *matched* avgBound. At BoA's dt→0 avgBound (~164) vs BoA's coarse (~20) the drifts are not
  directly comparable; the clean same-engagement comparison remains the matched-engagement one on record. The fork
  answer here ("does BoA saturate / lose its duty cycle") is unambiguous regardless.
- **Parity untouched.** Timestep params are set via the param file (launch-time, not mid-run); the census extension
  (`bfRel`/`nRel` added to the `[STRETCHCENSUS]` line) fires only under `BOA_STRETCH_CENSUS` ⇒ a no-flag run is
  byte-identical (pure bookkeeping — reads cumulative release counters already maintained each step, touches no
  force or RNG). No release/stroke/kinetics/model change. `BoA-v1ref` byte-clean.

## Runs (for the record)
```
BOA_STRETCH_CENSUS=1 BoxOfActin -r -pf ParameterFiles/glidingAssay_d1000_colTol8nm         (dt=1e-5,  70k steps)
BOA_STRETCH_CENSUS=1 BoxOfActin -r -pf ParameterFiles/glidingAssay_d1000_colTol8nm_dt5e6   (dt=5e-6, 140k steps)
BOA_STRETCH_CENSUS=1 BoxOfActin -r -pf ParameterFiles/glidingAssay_d1000_colTol8nm_dt2p5e6 (dt=2.5e-6,280k steps)
# CPU, aorus1. Analysis: RUN_LOGS/2026-07-03_dtconv_boa/analyze.py
```

## JOURNAL line
```
## 2026-07-03 — dt-convergence at coltol8/d1000 on ACTIVE BoA (mirror of v2's series): BoA SATURATES TOO. Refining dt 1e-5→2.5e-6, avgBound EXPLODES 19.6→163.9 (8.4×, tracking v2's 17.9→157.9 almost point-for-point), per-bound drift CRATERS 0.258→0.0068 (38×), windowed dwell LENGTHENS 0.23→0.71 ms (3×), per-head detachment CRATERS 4317→1402/s (3×), catch-slip load DROPS 3.18→2.29 pN (overshoot removed). BoA's coarse drift is 3× v2's (0.258 vs 0.090) but LANDS on v2's converged value (0.0068 vs 0.0077 @dt=2.5e-6) — the two codes CONVERGE to the same near-saturated low limit; net glide both → ~1.1–1.2 µm/s (BoA's coarse 5.06 washes out). ⇒ FORK: the missing dt-robust detachment is UNIVERSAL (both codes; catch-slip±12pN-cap has no dt-robust duty cycle). The coarse-dt gather split (v2 0.081 vs BoA 0.248 @avgB≈19) is an OPERATING-POINT ARTIFACT of two unconverged codes — GATHER QUESTION CLOSED, no gather redesign. Real problem = the detachment pathway (needs a dt-independent, nucleotide/ATP-limited, catch-slip-modulated release), for both codes. Nuance: BoA's 12pN break-cap stays the dominant channel (~65%) at all dt (doesn't shift to catch-slip), but detachment still slows toward zero. Measurement-only (param-file dt + byte-identical BOA_STRETCH_CENSUS bfRel/nRel counters); no model/release/stroke change; BoA-v1ref byte-clean; single draw (38× monotone, coarse point matches CAPTURE_RADIUS 3-draw mean). Report: BOA_DT_CONVERGENCE_FINDINGS.md.
```
</content>
</invoke>
