# A3 — un-offloaded CPU force/integrate work: diagnosis + residency classification

Branch `benchmark-contractile-dense`. Machine aorus1, RTX 5070 (12 GB), Java 21,
TornadoVM 4.0.1-dev PTX. 2026-06-12. `V1_FINISH_LINE.md` item **A3**, following the
copy-out fix + A1 (`onStepStart` poseAudit gate) + A2 (`gridScatter` parallelize).

Diagnostic fixture: `boa10-64Seg-dyn-dense-{1,8}x` (the dense contractile weak-scaling
sibling — 1000·N filaments + 1000·N minifilaments, heavy crosslink, active turnover,
no formins, no Listeria/bug). Window `[300,660)`, 360 steps. Data:
`RUN_LOGS/2026-06-12_a3_diag/`.

## TL;DR — clean BAIL on the device-port axis

**A3 asked: port the already-device-resident subset of the residual CPU force/integrate
work (`step` 13.5, `jointsCpu` 11.8, `brownian` 5.5 ms/step at 8×, per
`COPYOUT_RESIDENCY.md` Part 2) to a device kernel reading resident state.**

The diagnosis refutes the premise: **there is no un-offloaded resident-state *force*
left to port.** Every force that reads device-resident state is *already* a device
kernel (chain F3/F4, boundary F1, motor F8/F9/F10, Brownian, and the dimer/body↔rod
cohesion). The ~30.5 ms/step that still burns on CPU at 8× is **not force computation**
— it is:

1. **∝N CPU dispatch/iteration over GPU-handled Things doing gated no-ops** (`step`,
   `brownian`, the `myoJoints1` dimer wave), and
2. **host pose-derived bookkeeping** (`MyoMiniFilament.updateMyosins` in `myoJoints2`)
   that recomputes world-frame myosin/dimer positions — a *dead-host-work* candidate on
   `-gpu`, not a force.

So the prompt's PORTABLE category (`state already resident; force/integrate can become a
device kernel`) is **empty** here — the resident-state forces are device kernels
already. And the prompt's anticipated bail (`requires making the type resident (SoA)`)
**does not apply** either: the GPU-handled Things ARE resident; there is simply no force
left on them to move. This is a **stronger** bail than the one the prompt scoped:
**the v1 force-offload is complete; the force-offload wall is reached.** What remains is
a *different optimization class* — CPU-shell elimination + dead-bookkeeping gating —
documented as v2 below.

**Per the prompt boundary ("commit nothing" on bail), no port and no physics change was
made.** Only reusable, physics-neutral diagnostic instrumentation (gated behind
`BOA_STEP_PROFILE`, like the existing `PercolationProbe`/`StepProfiler` probes) is
committed, as the evidence for the finding. Nothing merged.

## Phase 1 — decomposition by Thing-type + residency classification

### The evidence (GPU, dense `-gpu`, window `[300,660)`, 360 steps)

| phase | 1× ms/step | 8× ms/step | 8×/1× | what it actually executes on `-gpu` |
|---|---|---|---|---|
| **step** | 2.28 | **13.46** | 5.9× | `Thing.step()` dispatched over **all** Things (102 946 → 823 684). For GPU-handled Things every force is gated to device; the CPU body is a near-no-op. |
| **brownian** | 1.48 | **5.28** | 3.6× | `bForces` wave iterates **all** Things but computes `calcRandomForces` only for `!gpuHandled` = **cpuFallback = 1 (the Chamber)**. 99.999 % is iterate-and-skip. |
| **jointsCpu** | 3.6* | **11.75** | — | `membraneLinks` 0.002 + **myoJoints1 5.97** + **myoJoints2 5.78** (8× split, new A3 sub-brackets). |

\*1× jointsCpu from the prior Part-2 table (3.61); the A3 run's sub-bracket split is the
8× column.

### Force-fire counters — proof the forces are already on device (both scales)

```
[STATS] addLinkForcesFireCt=0        (F3 chain link  — CPU never fires)
[STATS] addTorsionFireCt=0           (F4 chain torsion — CPU never fires)
[STATS] checkBugInsideFireCt=0       (F1 box boundary — CPU never fires)
[STATS] updatePosFromStepFireCt=0    (F8/F9/F10 motor tipLink — CPU never fires)
[STATS] anchorFireCt=0               (Myosin anchor spring — CPU never fires)
[A3] dimer cohesion dispatch:  device(noop)=37 031 294  cpu(work)=0
[A3] body↔rod constrain:       device(noop)=47 490 289  cpu(work)=0
[A3] cpuFallback histogram: thingCt=823 684  cpuFb total=1 { FilSeg=0 MyoMini=0 ProteinNode=0 Chamber=1 other=0 }
```

`cpu(work)=0` and every `*FireCt=0` is the whole story: **no chain, boundary, motor,
Brownian, or cohesion force is computed on the CPU on the `-gpu` path.** The CPU phases
still *iterate* the Things but find nothing to do for them.

### Classification — each (phase × Thing-type)

| phase | Thing-type | residual CPU work | residency | class |
|---|---|---|---|---|
| step | FilSegment (GPU-handled) | `checkBugOrBoxCollision` (inner gated, `gpuBoundaryHandled`) + `addLinkForces`/`addTorsion` (gated, `gpuChainHandled`) + `addNodeForces` (F5/F6 — **un-gated** but no-op: no formins/plasmids in fixture). Net: ∝N virtual-dispatch + branches. | resident | **not a force** — already offloaded; residual is dispatch shell |
| step | MyoMotor (GPU-handled) | `updateMyoFilLinks` → `tipLink.step()` (gated off via `gpuMotorHandled()`). Net: ∝N dispatch. | resident | **not a force** — already offloaded |
| step | MyoMiniFilament (GPU-handled) | F7 boundary `checkOuterBugCollision` — every `collisionCheckInt` steps, no-op (no bug). | resident | **not a force** — inert |
| brownian | all GPU-handled | skipped (`!gpuHandled`); device Wang-hash kernel generates Brownian. | resident | **not a force** — already offloaded; residual is the ∝N iterate-and-skip loop |
| brownian | Chamber (cpuFallback) | real `calcRandomForces` — **1 object**, flat. | host-OOP | DEFER → v2 (needs residency) — but trivially small (1 object) |
| myoJoints1 | MyosinDimer (cohesion) | `jointConstraints` → `cohesionOnDevice()` true → empty branch. Net: ∝N dispatch over ~100k+ dimers + the gate check. | resident (ported) | **not a force** — already offloaded (device `dimerCohesionKernel`); residual is dispatch shell |
| myoJoints2 | MyoMiniFilament (`updateMyosins`) | `updateMyosinPositions` + `updateMyosinDimerPositions` + `keepMyosinsOnSurface` + `constrainEnd1/2Dimers` (the constrain part 100 % device-gated, `cpu=0`). Net: ∝N **host pose-derived position recompute** (not a force) + a possible boundary force in `keepMyosinsOnSurface`. | body resident, **head/dimer world-pose host-OOP** | **mostly dead host bookkeeping** → v2 (liveness audit); `keepMyosinsOnSurface` is the one possible-force sub-item |
| myoJoints2 | ProteinNode / ChamberMyo / ChamberMyoD tethers | none — **population 0** in this fixture (cpuFb ProteinNode=0; only the Chamber). | host-OOP | n/a here; DEFER → v2 if a config populates them |

**No row is (a) PORTABLE.** Every resident-state force is already a device kernel; the
only host-OOP force (Chamber Brownian) is a single object. The residual is dispatch
shell + dead bookkeeping.

## Phase 2 — BAIL (nothing to port), with the v2 lever documented

There is no resident-state force to move to a device kernel, so the port bails. The
recoverable ~30.5 ms/step at 8× is real but belongs to a different technique. For a
future session, the v2 lever is **CPU-shell elimination / dead-work gating**, ordered by
payoff × safety:

1. **`brownian` → dispatch over `cpuFallback` only (~5.3 ms/step @8×).**
   The `bForces` wave computes for exactly `{!removeMe && !gpuHandled}` = the
   `cpuFallback` list (already built by `classifyThings`). Iterating that list instead
   of all 823k `theThings` retires the ∝N skip-loop and is **provably force-identical**
   (same compute set, same worker-thread `taForce` routing — keep the ThreadSet fan-out,
   only change the partition from `thingCt` to `cpuFallbackCt`; do **not** route a
   main-thread `incForceSum` write — that reintroduces the `taForce` multi-pool race).
   Force-exactly-once check: a set-equality verifier (`cpuFallbackCt` vs the count of
   `!gpuHandled` non-removed Things) must read identical, and a `cpuBrownianApplyCt`
   must be unchanged vs the all-Things path. Residency requirement: confirm
   `cpuFallback` is complete at `bForces` time — it is, because Thing creation marks
   `topologyDirty` → `classify` fires at the next `onStepStart` (top of step) before
   `bForces`, so the list is current; new Things only appear in later cleanup/spawn
   phases.

2. **`myoJoints2` `updateMyosins` → gate off on `-gpu` after a liveness audit
   (~5.8 ms/step @8×).** `refreshHostMirrorsForOutput()` recomputes derived end1/end2 and
   `bindTip` at output cadence but does **not** recompute the world-frame myosin head
   positions (`myoPtsInX`) or dimer attach points (`myoDimerPtsEnd1InX`) that
   `updateMyosins` writes; the device cohesion kernel reads only the **body-frame
   constant offset** (`myoDimerPtsEnd1Inx`, lowercase) + device body/rod/lever poses.
   So the per-step world-pose recompute is a **dead-host-work** candidate. Residency
   requirement: an exhaustive pose-consumer audit (the same kind referenced at
   `GPUMoveThing.java:762`) proving no host reader between output frames consumes
   `myoPtsInX`/`myoDimerPts*InX` on `-gpu`; if a reader exists, fold its refresh into the
   output path. **Caveat:** `keepMyosinsOnSurface` (inside `updateMyosins`) can
   `incForceSum` a boundary spring if a head pokes through the chamber surface — add a
   fire-counter first; if it is ever nonzero this is a genuine resident-state boundary
   force (mirror of the already-device F1 box kernel) and is the **one real
   port candidate hiding in A3**, not dead work.

3. **`step` → skip dispatch for GPU-handled Things via a per-force gate
   (~13.5 ms/step @8×, highest payoff, highest risk).** The blanket-skip is unsafe:
   `addNodeForces` (F5/F6) is un-gated and would silently drop if a node-tethered
   segment ever appears. Requires a per-force gate (the established `gpuChainHandled` /
   `gpuBoundaryHandled` pattern) so the dispatch is skipped only for Things whose every
   `step()` force is device-handled AND that have no live node tether — or a reduced
   dispatch list of node-bearing/boundary-relevant segments. ∝N dispatch overhead is the
   reward; Lesson-1 silent-drop is the hazard.

4. **`myoJoints1` → skip the `MyosinDimer` wave when all dimers are `cohesionOnDevice`
   (~6.0 ms/step @8×).** A guard at the wave level (`if all dimers device → skip
   dispatch`) retires the ∝N gate-check loop. Force-exactly-once: the existing
   `DIAG_COHESION_CPU_CT` must stay 0 with the guard on.

All four are **constant-factor (∝N) wins, not scaling-shape wins** — consistent with the
prompt's framing of A3 as the largest *recoverable* host cost but not a slope changer.
The slope drivers remain `recompute`'s `classify` (∝N² structural events, A1) and
`resetCt`/`cleanup` (∝N array compaction).

## Context — where this sits

GPU 8× total = 233.6 ms/step (this branch HEAD: copy-out + A1 + A2). CPU 8× = 333.95.
**GPU÷CPU 8× = 0.699** (GPU wins at dense, unchanged — A3 is a no-op port). The residual
CPU force/integrate phases are 30.5 / 233.6 = **13 % of the GPU step**; retiring all of
it (the v2 shell-elimination above) would move GPU÷CPU 8× from 0.699 to ≈ 0.61.
1× total: GPU 58.7 vs CPU 52.2 → GPU÷CPU 1.13 (GPU still loses 1×; the residual is a
smaller share there).

## Validation

This is a diagnostic + bail; the only code is physics-neutral instrumentation. There is
no port to A/B. The **force-exactly-once** property is *confirmed already true* by the
fire-counters: `addLinkForcesFireCt = addTorsionFireCt = checkBugInsideFireCt =
updatePosFromStepFireCt = anchorFireCt = 0` and dimer/body↔rod `cpu(work)=0` at both 1×
and 8× — every force fires exactly once, on the device, with the CPU path applying
nothing. The diagnostic instrumentation does not touch any force path (it adds
`StepProfiler.ENABLED`-gated counters and nanoTime brackets only), so it cannot perturb
physics; with `BOA_STEP_PROFILE` unset the only added cost is a boolean check per gated
site.

## Code (instrumentation only — committed, physics-neutral, NOT merged)

- `BoxOfActin.java` — split the `jointsCpu` bracket into `membraneLinks` / `myoJoints1` /
  `myoJoints2` sub-brackets (`pcMembraneLinksNs`/`pcJoints1Ns`/`pcJoints2Ns`), reported
  under the `'other' decomposition`; `[A3]` stats line (cpuFallback histogram + cohesion
  dispatch counts).
- `GPUMoveThing.java` — `cpuFallback` type-histogram snapshot in `classifyThings`
  (gated, scans the already-built list).
- `MyosinDimer.java` — `DIAG_COHESION_DEVICE_CT` / `DIAG_COHESION_CPU_CT`.
- `MyoMiniFilament.java` — `DIAG_BODYROD_DEVICE_CT` / `DIAG_BODYROD_CPU_CT` /
  `DIAG_UPDATEMYOSINS_CT`.

All increments guarded by `if (StepProfiler.ENABLED)`.

## Reproduce

```
bash RUN_LOGS/2026-06-12_a3_diag/run_diag.sh   # GPU 1x + 8x, BOA_STEP_PROFILE=1
# read: [A3] histogram, *FireCt, the STEP_PROFILE 'other' decomposition sub-brackets
```
