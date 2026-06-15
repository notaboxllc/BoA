# Sub-cycling on the GPU path — design notes

Status: design + CPU prototypes (2026-06). Captures the architecture for a **safe, fast
sub-cycling capability on the GPU path**, motivated by membranes (strain propagation) and
branched filaments (constraint overshoot). Nothing here is committed to the GPU path yet;
this is the spec plus what the CPU prototypes have already established.

## Why we need this

Two independent problems, one underlying primitive:

- **Membranes (propagation / under-relaxation).** The membrane is a triangulated sheet of
  `StickyNode`s joined by `NodeLink`s. Each link only pulls its two endpoints, so in one
  explicit step a strain perturbation propagates **one neighbour-ring**. To equilibrate
  strain across a sheet of diameter D nodes takes O(D) steps. Without sub-cycling, either
  the global dt must be tiny or the membrane is permanently lagging.
- **Branched filaments (overshoot).** The Arp2/3 branch constraint, integrated once per step
  at dt=1e-5, violently overshoots and leaves the daughter mis-oriented (see the single-
  junction bench below). dt=1e-6 fixes it at 10× cost.

Membranes are the *propagation* problem (too slow); branches are the *overshoot* problem (too
fast). Both reduce to the same on-device primitive.

## The core lesson: every BoA spring is a dt-coupled position correction

Every constraint/spring force in the codebase has the same shape:

```
forceMag = (coeff * 1e-6 * strain / deltaT) / (1/gam_a + 1/gam_b)
```

- `Arp23.applyTransForce`  — `coeff = arpTransFracMove`
- `NodeLink.applyTransForce` — `coeff = membraneLinkFracMove`
- `FilSegment` chain bending (PAIRS) — `coeff = fracMove/fracR/fracMoveTorq`
- `FilLink` crosslinks — same form

This is **not a physical force**. It is a *"close `coeff` fraction of the strain per step"*
position correction (a PBD-style projection done explicitly), and its **effective stiffness
is `k = coeff / (deltaT · mobility)` — it scales as 1/dt.**

Consequences, all observed empirically:

1. **It cannot be time-refined or sub-cycled as written.** Shrinking dt makes the spring
   *stiffer*, not the integration finer. Naive sub-cycling of the branch constraint at dt/N
   went straight to chaos (junction angle 30°→110°) because each inner step was N× too stiff.
2. **It overshoots when `coeff > 1`** (the original hardcoded `2.0` for both Arp23 and
   NodeLink caused visible spin/overshoot; `1.0` is critically damped for a *point* mass).
3. **There is no dt-independent "true" solution to converge toward** until the stiffness is
   decoupled from dt.

### The fix: fixed-stiffness (dt-independent) explicit penalty springs

Pin the stiffness to a **reference dt** (or a true spring constant `k`) instead of the live
`deltaT`:

```
kDt = (fixedStiffnessDt > 0) ? fixedStiffnessDt : deltaT;   // legacy when 0
forceMag = (coeff * 1e-6 * strain / kDt) / (1/gam_a + 1/gam_b);
```

Now `k = coeff/(kDt·mobility)` is dt-independent, the relaxation time is ~`kDt`, and the
force is a genuine **explicit penalty spring** — still no implicit solve. This is the
prerequisite for *any* dt-refinement or time-sub-cycling. Prototyped as `Env.arpFixedStiffnessDt`
for branches; generalizes to a per-spring-family reference dt (membrane, PAIRS, FilLink).

> This is the "spring-constant vs deltaT" point: the PAIRS coefficients and the membrane
> `fracMove` are the same dt-coupled form, so the fixed-stiffness reformulation is the
> common prerequisite across all of them.

## Two flavours of sub-cycling

| | mechanism | resolves | who needs it | state |
|---|---|---|---|---|
| **Iterative projection** (Gauss-Seidel / Jacobi, N passes/step) | propagate strain N rings across the connected mesh within one global step | membranes; dense crosslinked nets | exists **CPU-only** (membrane relaxation loop) |
| **Time sub-cycle** (r-RESPA, N steps of dt/N, fixed k) | resolve a stiff local junction without explicit-Euler overshoot | branches | CPU prototype |

They are different but share the GPU primitive below. The membrane already runs iterative
projection on CPU — the `while (NodeLink.maxStrain > tol && mPass < maxPasses)` loop in
`BoxOfActin.doLoop()` (enforceLink → gather → membraneMove per pass; Jacobi within a pass).

## The unified GPU primitive

Both flavours are: **a bounded inner loop over fixed-stiffness springs, with device-resident
pose, accumulating into shared endpoints.**

1. **Fixed stiffness is the precondition for both.** (See above.) Projection tolerates the
   dt-coupled form better than time-sub-cycling, but pinning `k` makes everything robust to
   global-dt changes and is mandatory for time-sub-cycling to converge.
2. **Shared-endpoint accumulation** — the one real GPU design choice. A node has many links;
   a mother has many daughters → concurrent writes to the same pose.
   - **Jacobi (preferred, deterministic):** each spring writes its own force slot; a per-
     endpoint gather kernel sums them. No atomics, no race, reproducible. Matches the
     existing per-thread `taForce` + gather pattern on CPU.
   - **Atomic:** `KernelContext.atomicAdd` into per-endpoint force buffers. Works on PTX
     (void form, no fetch-add). Less code, non-deterministic ordering.
   Default to Jacobi for "safe".
3. **Bounded inner loop** `for (p = 0; p < N; p++)` inside one kernel → PTX-friendly (N is a
   launch-time constant). Pose stays resident across passes. Slots into the unified
   TaskGraph as one phase between `scatter` and the global `move`.
   - Caveat: `WorkerGrid` drops the remainder partial block when `global % local != 0` —
     size launch dims accordingly (known TornadoVM PTX behaviour).
4. **Work partition:** the membrane is one connected component → one big Jacobi relaxation;
   branches are many small independent clusters → embarrassingly parallel. Same kernel,
   different partition (membrane: all links; branches: per-cluster).
5. **Exclude sub-cycled bodies from the global move** so they are not double-integrated
   (CPU prototype uses a `subcyclingNow` flag + an early-return in `FilSegment.moveThing`).

### Safe and fast

- **Safe:** fixed pass count `N` is deterministic with no host sync. Fixed stiffness prevents
  the dt-coupling blow-up. A pass cap bounds the work.
- **Fast:** pose resident, Jacobi gather (or atomics), bounded loop, one TaskGraph phase.
- **Adaptive early-out** (stop when `maxStrain < tol`) is the optimization: needs a device-
  side reduction + flag, or a persistent kernel. Start with fixed-N; add early-out later.

## CPU prototype findings (branches) — what's already validated

Single-junction bench (`Env.junctionTest`: 1 mother + 1 daughter, daughter perturbed
`junctionPerturbDeg`, thermal off, Arp23 logs `JCT step gap angle` per step):

- Baseline dt=1e-5: junction settles **mis-oriented at ~37°** (perturbation was 30°), gap
  excursion to ~0.13 µm. dt=1e-6: relaxes cleanly to **~0°**. → real dt-dependent overshoot.
- `arpTransFracMove` (0.5/1.0/1.5) and a rotational-drag-aware force rescale (`arpRotDragAware`,
  since reverted) **do nothing** — the overshoot is independent of force magnitude, because
  the gap closes by a fixed *fraction* regardless of absolute force. Lesson: rescaling the
  force is the wrong lever.
- Naive time-sub-cycle: **chaos** (the 1/dt stiffness). With **fixed stiffness**
  (`arpFixedStiffnessDt`): sub-cycling **converges**, junction 37° → **~6°** by N≈5,
  approaching the fixed-k dt=1e-6 reference (~0.5°). Residual ~6° (operator-split / sequential
  mother-daughter update) is a refinement, not a blocker.

## Parameters (CPU prototype)

- `Env.arpFixedStiffnessDt` (default 0 = legacy) — reference dt pinning the Arp2/3 trans
  stiffness. `>0` → fixed-stiffness explicit penalty spring. **Prerequisite for sub-cycling.**
- `Env.arpSubcycleN` (default 1 = off) — r-RESPA inner sub-steps for the branch constraint.
- `Env.junctionTest`, `Env.junctionPerturbDeg` — single-junction relaxation bench.
- (membrane) `Env.membraneFixedStiffnessDt`, `Env.membraneRelaxGpuShaped` — see prototype.

## Validation methodology

- **Junction bench** — deterministic, seconds per run, isolates one constraint from
  growth/floppiness/RNG. The right place to test any constraint-integration change first.
- **Cumulative rigid rotation (Kabsch)** — for the full network, match segments by nearest
  midpoint between frames, best-fit rotation, accumulate. Captures coherent spin that per-
  segment metrics miss. (The naive per-segment-reorientation median is blind to slow rigid
  rotation — that mistake cost a wrong "stable in isolation" conclusion early on.)
- **Membrane** — compare deformation / `maxStrain` decay / node trajectories of the GPU-
  shaped relaxation vs the current relaxation loop on the same seed. Target: *behaves
  somewhat like* the current method (membrane modelling is in development; the existing
  method is not an oracle).

## Open questions / next steps

1. **Membrane GPU-shaped CPU prototype** (in progress) — Jacobi accumulation, fixed
   stiffness, bounded passes, self-contained (kernel-shaped). Validate vs current loop.
2. **Branch residual** — close the ~6° gap (operator-split: apply soft forces once at dt;
   investigate sequential vs simultaneous cluster update).
3. **Generalize fixed-stiffness** across PAIRS / FilLink so the whole spring system is
   dt-independent and sub-cyclable.
4. **Kernel** — port the Jacobi relaxation to a TornadoVM kernel with a bounded pass-loop;
   decide Jacobi-gather vs atomicAdd; wire into the unified TaskGraph; size WorkerGrid for the
   remainder-block drop.
5. **Adaptive early-out** — device-side `maxStrain` reduction to stop passes near equilibrium.
