# NMII_BIOLOGY.md

Non-muscle myosin II (NMII) kinetics and mechanics — reference document for BoA simulation tuning. Last updated: 2026-05-23.

## Purpose

This document describes the most realistic behavior the BoA lumped-parameter model could plausibly capture for non-muscle myosin II motors interacting with actin filaments. It's intended to provide biological context for parameter tuning, benchmark design, and future model refinements. Skeletal/cardiac muscle myosin behavior is intentionally excluded — the focus is exclusively on NMII (isoforms IIA, IIB, IIC).

The simulation currently tracks:
- Per-monomer hydrolysis state on actin (ATP, ADP·Pi, ADP) — though this can be averaged over filament segments rather than per-monomer when computationally desirable.
- Per-head hydrolysis state on myosin motors.
- Mechanical state of motor heads (attached/detached, lever arm angle, force on bond).

So in principle, every kinetic dependence described below can be encoded — the question is which ones are worth the complexity for a given benchmark.

## The Lymn-Taylor cycle, NMII variant

The classical actomyosin ATPase cycle (Lymn & Taylor 1971) applies to NMII but with rate constants very different from skeletal muscle myosin. The cycle:

1. **M·ATP → M·ADP·Pi** (hydrolysis on detached head). Fast and reversible. The post-hydrolysis state is "primed" — the lever arm is in its pre-powerstroke position.
2. **M·ADP·Pi + A → A·M·ADP·Pi** (weak binding). Diffusion-limited encounter with actin. k_on,weak ~ 10⁵–10⁶ M⁻¹s⁻¹ for NMII (an order of magnitude slower than skeletal — Kovács et al. 2003).
3. **A·M·ADP·Pi → A·M·ADP** (Pi release, isomerization to strong binding). This is where the powerstroke happens — lever arm rotates ~10 nm. Rate is ~0.1–1 s⁻¹ for NMIIB, ~1–5 s⁻¹ for NMIIA at zero load. **This is the rate-limiting step for NMII**, in sharp contrast to skeletal muscle where ADP release is rate-limiting.
4. **A·M·ADP → A·M** (ADP release). Slow under load; this is what gives NMII its catch-bond character (see below). Zero-load rate ~0.35 s⁻¹ for NMIIB, ~3 s⁻¹ for NMIIA.
5. **A·M + ATP → M·ATP + A** (ATP rebinding and detachment). Very fast at physiological [ATP] ~1 mM; effectively instantaneous compared to other steps.

**Duty ratio** (fraction of cycle spent strongly bound) is the key parameter distinguishing NMII isoforms from skeletal:
- Skeletal muscle myosin II: ~0.02–0.05 (low duty, fast cycling)
- NMIIA: ~0.1–0.2
- NMIIB: ~0.3–0.4 (highest duty among NMIIs — built for tension maintenance)
- NMIIC: intermediate, less well characterized

High duty ratio means NMII spends a substantial fraction of its cycle bound, which is why even small minifilaments (10–30 heads vs. ~300 for muscle thick filaments) can sustain tension.

## Binding: dependence on actin hydrolysis state

This is where the "young vs. old" filament distinction matters. F-actin at the barbed end is ATP/ADP·Pi-rich; further from the barbed end it has aged to ADP-actin.

**Direct kinetic effect on myosin:** modest. Myosin binds ATP-, ADP·Pi-, and ADP-actin with affinities differing by ~2–5×, with a slight preference for ADP-actin (the "old" state). This was shown most cleanly by De La Cruz & Ostap (2004) and reviewed in De La Cruz (2009). The effect appears mostly in k_off rather than k_on.

**Indirect effect via competition.** This is the bigger story for cellular mechanics:
- Cofilin binds ADP-actin with much higher affinity than ATP/ADP·Pi-actin and competes with myosin (and tropomyosin) for old filament regions. So in vivo, ADP-rich regions tend to be cofilin-decorated and severed before myosin can extensively act on them.
- Tropomyosin (when present) protects filaments from cofilin and modulates myosin binding in an isoform-specific way.
- In BoA, if cofilin isn't modeled, this competition is absent — meaning the modest direct preference of myosin for ADP-actin can stand in as the main hydrolysis-state effect.

**Modeling recommendation for BoA:** A uniform k_on across hydrolysis states is defensible for short simulations (<< filament turnover time). For longer runs or anything modeling cortex remodeling, encode at most a 2–5× preference for ADP-actin in k_off (not k_on, per the experimental evidence). The actual mechanism is thought to involve conformational changes in the actin subunit upon Pi release that subtly reshape the myosin binding surface (Otterbein et al. 2001 structural work, Galkin et al. 2010 cryo-EM).

## Binding: dependence on myosin head hydrolysis state

This is the more important kinetic dependence and the simulation already tracks it.

- **M·ATP**: extremely low actin affinity. Should not bind in the model.
- **M·ADP·Pi**: weak binding. This is the encounter state. In the model, this is when k_on,weak should fire.
- **A·M·ADP·Pi → A·M·ADP**: the isomerization to strong binding. Modeled as a single rate constant k_iso ~ 1–5 s⁻¹ for NMIIA, ~0.1–1 s⁻¹ for NMIIB. This is the step that commits the head to the powerstroke.
- **A·M·ADP**: strongly bound, force-bearing. Lifetime is load-dependent (catch bond).
- **A·M (no nucleotide)**: rigor state, extremely strong binding. Brief at physiological [ATP] but mechanically important.

A reasonable lumping for BoA: collapse states 1–2 into "detached, primed" with rate-to-bind k_on. Collapse strong-binding states (A·M·ADP and A·M) into "attached" with a load-dependent detachment rate k_off(F).

## Force dependence of unbinding: the catch-bond regime

This is the part where the modeling literature diverged significantly in the 2010s and is most directly relevant to BoA benchmarks.

**The naive slip-bond model** (Bell 1978): k_off(F) = k_off(0) · exp(F · x_β / kT), where x_β ~ 1–3 Å is a distance-to-transition-state. Force exponentially accelerates unbinding. This is what most coarse cytoskeletal models use (e.g., Kim, Hwang, Kamm 2009; Stam et al. 2017).

**The experimentally measured behavior for NMII is non-monotonic** — a catch bond at low loads transitioning to a slip bond at high loads:
- Below ~2 pN, increasing load *increases* attached lifetime. NMIIB shows this most strongly (lifetime can extend 10×).
- Around 2–4 pN, lifetime peaks.
- Above ~4–5 pN, lifetime decreases with load (slip regime).

**Mechanism:** Load on the lever arm slows ADP release from the strongly-bound A·M·ADP state by stabilizing the post-powerstroke conformation. Since ADP release is required before ATP can rebind and detach the head, slowing ADP release prolongs attachment. This is the molecular basis of the catch bond. At high loads, however, the force on the actomyosin interface itself begins to dominate, and the bond ruptures mechanically (slip regime).

Key experimental references:
- **Veigel et al. (2003)**, *Nat Cell Biol* 5:980 — first single-molecule demonstration of load-dependent ADP release in smooth/non-muscle myosin.
- **Kovács et al. (2007)**, *PNAS* 104:9994 — NMIIA vs. NMIIB kinetic comparison, established NMIIB as the tension-bearing isoform.
- **Norstrom et al. (2010)**, *J Biol Chem* 285:26326 — NMIIA, IIB, IIC head-to-head ATPase and load comparison.
- **Stam et al. (2015)**, *PNAS* 112:E4061 — single-molecule force-velocity for NMIIB filaments, directly relevant to ensemble simulation tuning.
- **Melli et al. (2018)**, *eLife* 7:e32871 — direct measurement of NMII catch-bond characteristics with optical traps.

**Modeling recommendation for BoA:** Two options.

1. **Slip-bond only** (simpler): k_off(F) = k_0 · exp(F · x_β / kT). Tune k_0 and x_β to match measured unloaded lifetime and stall force. Adequate for many contractility benchmarks.

2. **Catch-slip** (more realistic): Use a two-pathway model (Pereverzev et al. 2005 form): k_off(F) = k_catch · exp(-F · x_c / kT) + k_slip · exp(F · x_s / kT). Four parameters instead of two, but reproduces the non-monotonic lifetime curve. Recommended if benchmarking against single-molecule lifetime-vs-force data or studying tension homeostasis.

## Isoform-specific parameter values

Approximate values from the literature, suitable as starting points for tuning. NMIIA and NMIIB are the most-studied; NMIIC is less well-characterized.

| Parameter | NMIIA | NMIIB | NMIIC | Source |
|---|---|---|---|---|
| Duty ratio | ~0.1–0.2 | ~0.3–0.4 | ~0.2 | Kovács 2003, 2007; Heissler 2011 |
| ADP release rate, unloaded (s⁻¹) | ~3 | ~0.35 | ~0.7 | Wang 2003, Kovács 2003 |
| Actin-activated ATPase Vmax (s⁻¹) | ~0.5–1 | ~0.1–0.2 | ~0.2 | Kovács 2007 |
| Step size (nm) | ~5–8 | ~5–8 | ~5–8 | Norstrom 2010 |
| Stall force per head (pN) | ~1–2 | ~1–2 | ~1–2 | Stam 2015 |
| Catch-bond peak lifetime load (pN) | ~2 | ~2–3 | n.d. | Melli 2018 |

The two-fold-ish stall force per head, combined with NMII minifilament size of 10–30 heads per side, gives ensemble stall forces in the 10–60 pN range — consistent with measurements on isolated minifilaments (Hu et al. 2017, *Nat Cell Biol*) and order-of-magnitude consistent with what cortical foci can produce.

## NMII minifilament architecture

NMII molecules dimerize via their coiled-coil tails into ~300 nm bipolar minifilaments containing roughly 14–30 myosin dimers (28–60 heads), with heads projecting from both ends and a bare zone in the middle. This is much smaller than skeletal thick filaments (~1.6 μm, ~300 dimers). Minifilament assembly is regulated by myosin light chain kinase (MLCK) phosphorylation of the regulatory light chain — unphosphorylated NMII tends to fold into a 10S inactive monomer.

For BoA modeling purposes:
- The relevant ensemble unit is the minifilament, not the individual dimer.
- Heads on opposite ends pull antiparallel actin filaments toward the bare zone — this is the contractile geometry.
- 10–30 heads per "side" means that even with high duty ratio (~0.3 for NMIIB), only ~3–10 heads on each side are bound at any given moment. Fluctuations matter.

Key refs:
- **Billington et al. (2013)**, *J Biol Chem* 288:33398 — NMII minifilament structure.
- **Niederman & Pollard (1975)**, *J Cell Biol* 67:72 — classical NMII filament assembly.
- **Vicente-Manzanares et al. (2009)**, *Nat Rev Mol Cell Biol* 10:778 — review of NMII isoforms and cellular roles.

## Implications for BoA benchmarks

Plausible empirical targets the simulation could be tuned against:

1. **Single-head unloaded duty ratio and ATPase rate.** Tune k_iso (weak-to-strong transition) and k_off,0 (unloaded detachment) so that the fraction of time bound and the cycle frequency match isoform-specific values. This is a two-parameter fit to two numbers.

2. **Single-head force-velocity curve.** Tune the load dependence of k_off — either Bell parameter x_β (slip model) or catch-slip parameters — to match Stam 2015 or Melli 2018 single-molecule data. Step size is largely structural and fixed.

3. **Minifilament stall force.** With ~10–30 heads per side and per-head stall ~1–2 pN, ensemble stall should be 10–60 pN. This is a check on whether per-head parameters scale up correctly to ensemble behavior — if not, the duty ratio under load is likely off.

4. **Ensemble velocity at zero load.** Coupled motors don't simply average their unloaded velocities; the bound fraction matters. Tuning here checks that the load-redistribution physics among heads in a minifilament is reasonable.

5. **Tension homeostasis / sustained force.** NMIIB-rich ensembles should maintain force for seconds-to-minutes at near-stall loads with very low velocity. This is a test of the catch-bond regime — a pure slip bond will fail this benchmark because all heads will detach exponentially fast.

## Likely tuning parameters in BoA

Based on the file structure (Myosin.java, MyoMotor.java, MyoFilLink.java, MyoMiniFilament.java) the natural tuning hooks would be:

- **In MyoMotor or Myosin:** rate constants for state transitions (k_on, k_iso, k_off,0), the load-dependence parameter(s) for k_off, duty ratio targets.
- **In MyoFilLink:** the force on the bond, used as input to k_off(F). Whether this is the bond-axial force, the lever-arm tangential force, or some other geometric projection is a modeling choice with measurable consequences — the Veigel/Sellers data is most naturally interpreted in terms of force along the lever arm.
- **In MyoMiniFilament:** the number of heads per side, the geometry of head projection, and how load is shared among bound heads. Load-sharing assumptions strongly affect ensemble behavior and are worth examining critically — naive equal sharing is rarely correct.
- **In Monomer (actin):** any hydrolysis-state dependence of myosin binding/unbinding rates. Currently this is likely either uniform or simply unused; encoding the modest ADP-actin preference would be straightforward.

## Open modeling questions

A few things are not settled in the literature and worth flagging:

- **Whether the catch bond is purely lever-arm-load-mediated or involves an additional structural component at the actomyosin interface.** Some recent cryo-EM and MD work (Robert-Paganin 2020 reviews) suggests the latter. Doesn't change the phenomenology but affects which force component should drive k_off.
- **Whether NMII heads in a minifilament behave independently or show cooperative effects.** Some evidence for negative cooperativity (binding of one head slows binding of neighbors) but it's contested. Independent kinetics is the standard assumption and probably fine for BoA.
- **The role of MLCK phosphorylation in the model.** Currently presumably treated as "always phosphorylated/active." For most benchmarks this is fine. If modeling assembly/disassembly dynamics, this becomes important.

## Reference list (compact)

- Bell, G.I. (1978) *Science* 200:618 — original slip-bond model.
- Billington et al. (2013) *J Biol Chem* 288:33398 — NMII minifilament structure.
- De La Cruz & Ostap (2004, 2009) *Curr Opin Cell Biol* 16:61; 21:61 — kinetic framework reviews.
- Galkin et al. (2010) *Curr Biol* 20:R556 — actin structural states.
- Heissler & Manstein (2011) *FEBS J* 278:4974 — NMII isoform comparison.
- Hu et al. (2017) *Nat Cell Biol* 19:1389 — minifilament force generation.
- Kim, Hwang, Kamm (2009) *Biophys J* 96:1816 — example slip-bond modeling.
- Kovács et al. (2003) *J Biol Chem* 278:38132; (2007) *PNAS* 104:9994 — NMII kinetics.
- Lymn & Taylor (1971) *Biochemistry* 10:4617 — the cycle.
- Melli et al. (2018) *eLife* 7:e32871 — direct catch-bond measurement.
- Niederman & Pollard (1975) *J Cell Biol* 67:72 — minifilament assembly.
- Norstrom et al. (2010) *J Biol Chem* 285:26326 — NMII isoform comparison.
- Otterbein et al. (2001) *Science* 293:708 — F-actin structural states.
- Pereverzev et al. (2005) *Biophys J* 89:1446 — catch-slip mathematical form.
- Robert-Paganin et al. (2020) *Chem Rev* 120:5, 5 — myosin mechanochemistry review.
- Stam et al. (2015) *PNAS* 112:E4061; (2017) — NMII single-molecule and ensemble.
- Veigel et al. (2003) *Nat Cell Biol* 5:980 — load-dependent ADP release.
- Vicente-Manzanares et al. (2009) *Nat Rev Mol Cell Biol* 10:778 — review.
- Wang et al. (2003) *J Biol Chem* 278:27439 — NMII kinetic constants.