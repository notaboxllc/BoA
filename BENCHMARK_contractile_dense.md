# Dense contractile re-baseline benchmark — GATE BAIL (2026-06-12)

Branch `benchmark-contractile-dense`. Machine: aorus1, RTX 5070 (12 GB), Java 21,
TornadoVM 4.0.1-dev PTX. Run on `main` with `crosslink-lifecycle` merged (`3d0b6f7`).

## TL;DR

The benchmark **bailed at the Part A gate**: the spec'd **10× areal density does not
percolate**, so Parts B–E (speed sweep, memory ceilings, kernel profiler) did not run —
per the prompt's gate ("Report and STOP if any fail … If the network won't percolate
within a reasonable `xLinkOnRate`, **BAIL** — config needs jba"). The GPU path itself is
clean and the instrumentation is ready; only the density/percolation target is unresolved.

What *was* established:
- **GPU minifil + turnover runs clean** (no 701/packRange/ClassCast/overflow) — the base
  fixture's "RUN ON CPU" warning is stale (fixed by RULE_MINIFIL, 2026-06-11).
- **Percolation onset is sharp between 20× and 40× areal density** — needs ~4000 filaments,
  not the spec'd 1000.
- **F-actin** is on the 12–20 µM target *geometrically* (~13.7 µM) but ~8× lower in the
  simulation's internal units (~1.7 µM) due to a box-volume convention.
- A clean **host-phase decomposition** was captured on the gate run: exec dominates
  (145 ms/step, 84 %); the largest every-step **host** phase is **`pack` (8.1 ms/step)`**;
  `meshFill`≈0 (FILSEG_MESH skipped) and `sync` is biochem-cadenced — both confirmed.

## Part A — dense fixture + GPU-path gate

Fixture `ParameterFiles/boa10-64Seg-dyn-dense`: 10× areal density of `boa10-64Seg-dyn`
in the 10×10×0.5 µm box — `initialFilaments 100→1000`, `initialMyoMiniFils 100→1000`,
seeded near steady state (`minFilLength 0.9`, `maxFilLength 1.3` → ~1.1 µm mean), turnover
ON, `toFileInterval` parked high. Crosslink knobs surfaced: `sideBonds=0` (xLinks mode 0,
both alignments), `maxXLinkBondAngle 0.6 rad` (~34°), `xLinkOnRate/xLinkConc`, default
`linkOff*`.

### Gate result (GPU, `xLinkOnRate=40`, `W=200`)

| gate criterion | result | verdict |
|---|---|---|
| F-actin ~12–20 µM (≥7.5) holds | 1.72 µM sim-units / ~13.7 µM geometric | ⚠️ units-dependent (see below) |
| crosslink network percolates | largestComp = 3/1001 fils, spanFrac 0.14 | ❌ **FAIL** |
| GPU clean (no 701/packRange/overflow, meshFill skipped, sync cadenced) | overflow=0, planRebuild=1, meshFillSkipped=460/460, syncCalls=4 (biochem cadence) | ✅ PASS |
| NaN=0, network behaves as a connected contracting network | NaN=0; behaves as isolated bundles, not a network | ❌ (follows from no percolation) |

### Why it can't percolate at 10× — link-ceiling probe

Firing formation **every step at P_form≈1** (`xLinkOnRate=1000`, `xLinkConc=10`) to find the
maximum achievable link population, sweeping crosslinker reach `crossLinkGrabDist`:

| grab (µm) | mean active links | largest component | spanFrac | percolates |
|---|---|---|---|---|
| 0.0108 (default) | 403 | 4 | 0.15 | ❌ |
| 0.05 | 1826 | 12 | 0.23 | ❌ |
| 0.15 | 4215 | 7 | 0.19 | ❌ |
| 0.40 (37× default) | 7237 | 5 | 0.14 | ❌ |

**Mechanism:** with `maxLinksOnSeg=10`, crosslinks pile up between already-adjacent filament
*pairs* (bundling → components of size ~2) instead of bridging new filaments. More links and
longer reach **bundle harder**; they do not connect the box. At 10× areal density the mean
filament spacing (~0.6 µm) leaves too few *distinct* neighbours per filament for a giant
component to form.

### Density is the lever

Same saturated formation, `grab=0.05`:

| areal density | filaments | largest component | spanFrac | percolates | F-actin (sim-units) |
|---|---|---|---|---|---|
| 10× (spec) | 1000 | 12 (1.2 %) | 0.15 | ❌ | 1.7 µM |
| 20× | 2000 | 20 (1.0 %) | 0.39 | ❌ | 3.4 µM |
| **40×** | **4000** | **2171 (54.3 %)** | **0.99** | ✅ | 6.8 µM |

Percolation turns on sharply between 20× and 40×.

### F-actin units caveat

`Chamber.makeABox` sets `boxVolume = 8·dimX·dimY·dimZ` (= 400 µm³ for the 10×10×0.5 box)
while filaments occupy the 50 µm³ region `[-0.5·boxXDim, +0.5·boxXDim]…`. So
`microMolarChangePerMonomer` (and the reported `fActinUM`, and `actinConc`) is normalised by
the 400 µm³ reference — **8× larger than the occupied volume**. Geometric F-actin at 10×
density is ~13.7 µM (on the prompt's target); the simulation-internal value is ~1.7 µM. The
biochem rates draw from `actinConc` in the *internal* units, so this is an internal-consistency
convention, not a correctness bug — but it must be reconciled before "F-actin µM" is a target.

## Host-phase decomposition (captured on the gate run)

Even though the gate failed, the windowed `BOA_STEP_PROFILE` decomposition was captured and is
informative for the "next host target" question. Window `[200,460)`, 260 steps, GPU path:

| group | phase | ms/step | per-fire | notes |
|---|---|---|---|---|
| total | wall | **173.47** | | |
| | exec (task graph) | **145.26** | | 84 % of wall — **device-bound** |
| | host (= total − exec) | **28.20** | | |
| every-step host | **pack** | **8.09** | | ← largest every-step host phase (gather OOP → device FloatArrays) |
| every-step host | step | 2.42 | | |
| every-step host | brownian | 1.65 | | |
| every-step host | gatherForces | 1.18 | | |
| every-step host | crosslinkForce | 0.77 | | FilLink force + Bell dissolution |
| every-step host | meshFill | **0.008** | | ≈0 — FILSEG_MESH skipped on -gpu ✅ |
| cadence | biochem | 2.13 | 277 ms | 2 fires/window |
| cadence | **sync** | 0.81 | 104.7 ms | 2 calls = biochem cadence, **not every step** ✅ |
| cadence | crosslinkFormation | 0.05 | 6 ms | host drain of device candidates |
| | other (unlabeled) | 16.36 | | motor-fil collision sample, resetCt, cleanups, recompute |

**Next every-step host target: `pack` (8.1 ms/step)** — the OOP→device FloatArray gather. The
run is otherwise device-bound (exec 84 %), which is itself notable: at this density the chained
task graph is ~145 ms/step on a 5070 (a separate kernel-scaling question for Part E, not run).

These numbers characterise *compute cost vs density* on the non-percolating gate config; they are
**not** a validated contractile-network benchmark. Re-run on the jba-approved density before
quoting as the dense baseline.

## Recommendation for jba (to unblock Parts B–E)

1. **Density / percolation:** raise `initialFilaments` to ~4000 (40× areal) for a percolating
   network, **or** relax the percolation requirement, **or** change the model so crosslinks
   bridge rather than bundle (lower `maxLinksOnSeg`, larger `crossLinkGrabDist`).
2. **F-actin target:** decide whether 12–20 µM means geometric (occupied 50 µm³) or sim-internal
   (400 µm³ reference). At 40× density: geometric ~27 µM, sim-internal ~6.8 µM.
3. **Ready to go once density is set:** `PercolationProbe`, windowed `BOA_STEP_PROFILE`, the
   `fActin` readout, and the per-scale harness are committed; the GPU path is clean with
   minifils+turnover. Confirm a 40× config stays GPU-clean (watch `POSE_DELTA_CAP`/overflow at
   higher churn) and Parts B–E run unchanged.

## Instrumentation added (this branch)

- `PercolationProbe.java` — filament-graph union-find, largest component, spanning check,
  links-per-filament. Emits `[STATS] percolation …` at end of run.
- `BoxOfActin.reportStepPhaseProfile()` + `maybeTakeProfileBaseline()` — windowed host-phase
  decomposition gated by `BOA_STEP_PROFILE`, warmup via `BOA_PROFILE_WARMUP`. Splits every-step
  vs biochem-cadence phases, reports `total`/`host`/per-fire. New `crosslinkFormTimer` brackets
  the device-candidate host drain.
- `[STATS] fActin …` — Σ monomers × `microMolarChangePerMonomer` (polymerized actin µM) +
  free hydrolyzable pool.

### How to run (once density is resolved)

```
# GPU, windowed profile, warmup 200:
BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=200 \
  java @<tornado-argfile> --enable-preview -Xmx8G -Dtornado.tvm.maxbytecodesize=16384 \
  -cp "<tornado-api>:libs/*:." BoxOfActin -r -gpu -pf ParameterFiles/boa10-64Seg-dyn-dense
# CPU: drop -gpu and the argfile/-D flags (keep tornado-api on cp for class loading).
```
