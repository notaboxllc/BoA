# Tip-flexibility diagnostic — can a lumped-segment filament support an emergent Brownian ratchet?

*2026-06-15. Companion to `RATCHET_CLOSURE_DESIGN.{html,pdf}` (the polymerization closure spec) and the
`MEMBRANE_DENDRITIC_REVIEW` lamellipodium work.*

## Question

The elastic Brownian ratchet (Mogilner–Oster) generates protrusion force from the **thermal transverse
fluctuation of the filament tip** opening a δ ≈ 2.7 nm gap against the membrane. BoA models filaments as
chains of rigid `FilSegment` rods, each spanning `stdSegLength` monomers. Does the tip have the right
compliance for a ratchet to *emerge*, or must the ratchet be supplied as an analytical closure?

## Method

A single **cantilever** filament — one end pinned (`pinRegistry`), free tip, **no membrane/wall** — built
by reusing the deflection-benchmark chain (`-singleFilDiag` + `BOA_TIPFLEX=1`; one-pin + Brownian-on patch
in `BoxOfActin.makeInitialThings`, accumulators in `SingleFilDiag`). Two measurements of the free-tip
transverse motion:

1. **Fluctuation mode** (Brownian on, accumulate `<y²>`): noisy — the soft cantilever's correlation time
   (~0.13 s) left only ~28 independent samples in a 3.6 s run; y/z anisotropy up to 5× confirmed
   non-convergence. Abandoned for quantitative use.
2. **Static-compliance mode** (`BOA_TIPFLEX_FORCE=F` pN, Brownian off): apply a known transverse tip force,
   measure steady deflection δ, then `k_eff = F/δ` and, by equipartition, `σ_tip = √(kT/k_eff)`.
   Deterministic, converges in a few relaxation times. **This is the reported data.**

Sweep at **fixed contour length** 0.69 µm (N×(MON+1)=256 monomers), varying discretization. Force 0.001 pN
kept all deflections in the linear regime (δ ≤ ~30 nm ≪ 690 nm; the N=2 point at 112 nm is mildly nonlinear,
so its softness is if anything understated). Scripts + logs: `RUN_LOGS/2026-06-15_tipflex/`.

## Results (static compliance)

| N segs | mon/seg | tip deflection | k_eff (N/m) | σ_tip (1D) | vs. ideal Lp=15 µm |
|---|---|---|---|---|---|
| 32 | 7   | 3.9 nm  | 2.57e-7 | 127 nm | 2.2× softer |
| 16 | 15  | 8.6 nm  | 1.16e-7 | 188 nm | 4.8× softer |
| 8  | 31  | 20.5 nm | 4.87e-8 | 291 nm | 11.5× softer |
| 4  | 63  | 30.7 nm | 3.26e-8 | 355 nm | 17× softer |
| 2  | 127 | 112 nm  | 8.96e-9 | 678 nm | 63× softer |

Continuum reference (WLC cantilever, Lp=15 µm, L=0.69 µm): `k_eff = 3·kT·Lp/L³ ≈ 5.6e-7 N/m`, `σ_tip ≈ 86 nm`.

## Findings

1. **Tip compliance is strongly, monotonically segment-length-dependent.** Coarser segments make the tip
   *softer*, not stiffer — k_eff falls ~29× from N=32 to N=2. Mechanism: bending compliance is lumped at
   discrete hinges, so a long rigid segment pivoting on one soft joint swings its tip a large distance
   (long lever arm). Rigid segment *bodies* don't help; the hinge + lever arm dominate.
2. **In the typical operating range (24–64 mon/seg)** the modeled tip is **~11–17× softer** (σ_tip ≈
   290–355 nm vs ideal ≈ 86 nm, i.e. ~3–4× floppier), worsening toward 64.
3. **Head-on incidence is geometrically dead** regardless of all the above: the membrane-normal tip
   excursion is `σ_normal(θ) = sinθ · σ_tip`, → 0 at normal incidence (θ from the membrane normal).

## The honest caveat (important)

The sweep **did not recalibrate the bending stiffness per segment length** — `fracR`/`fracMoveTorq` were held
at defaults while only the monomers-per-segment changed. In this model the chain's *effective* persistence
length depends on segment length (the bending springs are "close-a-fraction-per-step" corrections whose
effective stiffness runs through the per-segment drag). So a **large part of the 11–63× is the chain simply
not sitting at Lp = 15 µm at these segment lengths — miscalibration, not a fundamental inability to resolve
the tip.** The raw factors therefore **overstate** the error for a run whose bending *is* tuned to Lp ≈ 15 µm
at its chosen segment length. A clean in-range number requires recalibrating (deflection tuner) at 24/32/48/64
mon/seg and re-measuring — that isolates the residual hinge/rigid-terminal-segment effect at correct Lp.
**Not yet done.**

## Implication for the ratchet (the decision)

Independent of the exact factor, the tip compliance the ratchet would ride is a function of segment length
**and** bending calibration. A ratchet that *reads it from the dynamics* (emergent) inherits that
config-dependence; a **closure anchored to physical constants (Lp, δ, the load f, and the resolved geometric
gap g = `end2TipC`)** does not. So: **supply the ratchet as a closure, do not let its magnitude emerge.** The
closure form, limits, and where it slots into `end2BiochemSim` are in `RATCHET_CLOSURE_DESIGN.{html,pdf}`.

## Reproduce

```
# static compliance at one segment length (deterministic):
BOA_TIPFLEX=1 BOA_TIPFLEX_FORCE=0.001 BOA_TIPFLEX_N=8 BOA_TIPFLEX_MON=31 \
  BOA_TIPFLEX_WARMUP=80000 BOA_BMDIAG_MAX_STEPS=150000 \
  java --enable-preview -cp "<tornado-api>:libs/*:." BoxOfActin -singleFilDiag -pf ParameterFiles/tipflex
# full sweeps: RUN_LOGS/2026-06-15_tipflex/sweep_static.sh (static), sweep.sh (fluctuation)
```

Diagnostic scaffolding (`SingleFilDiag` TIPFLEX/STATIC, the one-pin + Brownian/force gates in
`makeInitialThings`, `tipFlexForce`) is default-off and harmless to other modes.
