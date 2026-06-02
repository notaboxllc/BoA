# Residency Campaign Plan

Planner-level sequencing for the device-residency campaign. Built on `RESIDENCY_AUDIT.md`
(the per-consumer catalog), `STEP_PORT_SURVEY.md` (the step() force inventory), and
`GPU_MIGRATION_LESSONS.md` (the discipline). The audit is the map; this is the route.

## Goal and milestone

Residency = the canonical pose state (`coord`/`uVec`/`yVec` + derived fields) lives on the
GPU across steps and is downloaded only at output frames. The per-step CPU↔GPU pose
transfer is what we are eliminating so the GPU can run the computation without the memcpy
tax.

**The milestone is binary and back-loaded.** Per the audit, the per-step download (A12.b
OP_UNPACK) cannot move to output-frame-only until *every* per-step CPU pose consumer is
gone. So:
- Early ports will NOT move wall-clock. They remove consumers one at a time and build the
  device-resident infrastructure. This is expected — set expectations accordingly.
- The payoff (transfer → 0, the GPU finally shining on compute) lands at the *flip*, when
  the last consumer leaves and A12.b becomes output-frame-only.
- Therefore we order by **tractability and dependency, not by current-assay cost.** (The
  clarified goal: we are not optimizing the gliding assay's bottleneck; we are achieving
  residency.)

## How ports work incrementally (the mechanism)

Today (STATE 0) poses are canonical on the CPU and round-trip the device every step
(upload before kernels, OP_UNPACK download after move). Because poses are *uploaded* every
step, a device kernel can already read them. So each consumer can be moved to a device
kernel one at a time while still in STATE 0:

1. Implement the consumer as a device kernel reading device pose.
2. Remove the CPU version (force-coverage audit: applied exactly once, never dropped/doubled).
3. Validate against the consumer's probe.

The **flip to STATE 1** happens after the last consumer moves: remove the per-step
OP_UNPACK (download only at output frames) AND the per-step pose upload (FIRST_EXECUTION
only; pose persists on device), and recompute derived fields with a device kernel. That
step is where the transfer cost disappears.

---

## Phase sequence (gliding-assay slice)

### Phase 0 — Layout decision (the spike) — settle AoS vs SoA before substantial porting
Determine the code-wide pose-storage rule: keep AoS m*3 interleaving (`[x0,y0,z0,x1,…]`,
current) or switch to SoA separate-axis arrays (`[x0,x1,…][y0,y1,…][z0,z1,…]`). Made late,
this forces rewriting every kernel; made first, everything downstream is written once in the
right layout.
- **Vehicle:** the move kernel (pure per-entity integration). Per-entity access is where the
  layouts differ most — AoS reads are stride-3 (uncoalesced, ~3× transactions); SoA reads
  are contiguous (coalesced). Gather/scatter (grid, neighbor reads) is scattered in both
  layouts, so it is layout-insensitive and does not drive the decision. The move kernel is
  the binding test.
- **Method:** convert the move kernel's pose arrays (coord/uVec/yVec) to SoA separate-axis
  FloatArrays, rewrite its indexing, and A/B against AoS at 400K scale (where coalescing
  bites; 98K for comparison). Measure effective bandwidth and per-call wall.
- **Decision:** if SoA materially pulls the move kernel toward memory-bound (away from the
  ~2% peak / ~10 GB/s effective that AoS shows), adopt SoA code-wide and KEEP the converted
  kernel as the foundation (not throwaway). If SoA does not materially help, keep AoS — it's
  simpler and avoids a code-wide rewrite.
- **Correctness:** pure layout change — results must match AoS modulo float-add order
  (smoke seed, observables unchanged).
- **Note:** start with the move kernel; only extend the A/B to the chained joints kernel if
  the move-kernel result is marginal/inconclusive.
- This is the v2-branch-gate signal: the layout chosen here is v2's representation.

### Phase 1 — Anchor spring (A7.b) — establishes the pattern
Move `MyosinFixed.applyRodFixedPtForce` to a device kernel (per-myosin anchor spring; reads
device rod pose; needs the per-myosin fixed anchor point + spring constant as a
FIRST_EXECUTION device buffer).
- **Why first:** single isolated force, the known anchor-lesson residue (the last anchor
  CPU pose-read on `-gpu` today), and we have a fresh CPU+GPU validation reference from the
  fix. Lowest possible risk; proves the "CPU pose consumer → device kernel + remove CPU
  version + validate" loop end-to-end.
- **Force-coverage (Lesson 2):** anchor now applied on the device, not CPU; confirm exactly
  once.
- **Gate:** SingleMyoDiag (GPU head_r stays bounded ~0.06 µm) → gliding 10-seed ensemble
  (matches the post-fix baseline 856.80/7.30/8.22).

### Phase 2 — Step forces (A9) — the compute bulk; pointer-traversed, no grid
Requires building two pieces of residency infrastructure that everything downstream reuses:
the **chain/binding topology index** on device (per-segment `end1Fil`/`end2Fil` slots,
per-motor `boundSegId`) and the **derived-field recompute as a device kernel**
(`end1`/`end2`/`zVec`/`transXTox`), since F3/F4/F8 read derived fields.

Sub-order by validation coverage (per `STEP_PORT_SURVEY`), not by cost:
- **F3 (chain link) + F4 (chain torsion) first.** Best-covered forces.
  - **Gate:** the deflection benchmark — the trustworthy stiffness probe (CPU 0.998420 vs
    device baseline 0.999876; tolerance = that +0.146% offset; a larger shift is a
    regression). LP is NOT a hard target: measured Lp is Brownian-coefficient-dependent and
    somewhat filament-length-dependent, so treat it as a soft ballpark sanity check for the
    relevant length range, not a convergence target. No overnight LP run.
- **F1 (boundary).**
  - **Gate:** visual check in the viewer — wall-confinement failure (segments escaping the
    box) is gross and obvious (Lesson 4: watch it run). No numerical diagnostic worth
    building for a force whose failure mode is this visible.
- **F8/F9/F10 (motor tether/alignment) last.** Binding index (`boundSegId`/`posOnSeg` per
  motor) uploads each step while binding detection is still on CPU (small; one int + one
  float per motor).
  - **Gate:** gliding 10-seed ensemble.
- **Force-coverage after each:** every step force applied exactly once across the device
  path and the CPU-fallback path. CPU-fallback Things (MyoMiniFilament F7, ProteinNode F12,
  Bug) keep their CPU `step()` — they are not in the GPU-handled set and are not blockers.

### Phase 3 — Binding detection (A3) — the one genuinely hard piece
Move the `MotorBindGrid3D` grid build to the device (per-cell atomic counters, or per-cell
histogram + prefix-sum to assign write slots) and have the bind kernel read device pose
directly — eliminating both CPU pose reads (the FillThreads grid build and the
`GPUMotorBinding` SoA pack). Retires A1 fillSoaArrays (it existed only to feed the pack).
- Build **one unified 3D grid** (0.2 µm cubes) designed to also serve the FilLink
  broad-phase later (one grid, two consumers — the audit's recommendation).
- **This is the highest-uncertainty port and the v2-gate signal:** if the device grid build
  forces a data-layout change (true SoA vs the m*3 AoS), that is the architectural signal
  we have been watching for. See the de-risk option below.
- **Gate:** gliding 10-seed ensemble — bindEvents / meanBoundMotors are the sensitive
  observables (binding rate depends directly on the grid being correct).

### Phase 4 — The residency flip (A12.b) — THE MILESTONE
- Remove the per-step OP_UNPACK; download poses only at output frames.
- Delete the post-move CPU sync block (`recomputeDerivedSoA` + the FilSegment `end1Pt`/
  `end2Pt` and MyoMotor `bindTip` Pt3D refresh) — derived fields now recomputed on device.
- Remove the per-step pose upload (FIRST_EXECUTION only; pose persists device-side).
- `biochemStep` pose writes (poly/depoly): a topology-dirty bit re-uploads the affected
  slot range on the rare event. Cheap.
- **Gate:** full gliding ensemble + deflection + LP + **timing**. This is where the transfer
  tax goes to zero and the per-step wall-clock finally drops — the payoff you are after.

---

## Deferred (config-specific — port when that config enters the validation rotation)
- **A5.a FilLink + A2.d meshColl broad-phase** (crosslinkers): uses the Phase-3 device grid
  + a FilLink force kernel (same pointer-traversed shape as F8). Build a crosslinker-turnover
  probe first.
- **A9.F5/F6 node tether** (formin/plasmid): PREREQUISITE — build SingleFilNodeDiag (Lesson 5
  gap; no probe today). Probe before port.
- **A7.c MyosinDimer, A8.a/A8.b Crucible surface tethers** (chamber-fixed): pointer-traversed
  pair forces; same kernel pattern as F8/anchor.
- **A5.b Arp23, A5.c ActA, A9.F2** (Listeria): Arp23 pointer-traversed; ActA runs in the
  actAStart wave; F2 is filament↔bug pairwise.
- **A6 NodeLink / membrane relaxation:** stays on CPU permanently (StickyNode pose is
  CPU-resident) — never a blocker.

---

## Open design decisions — recommendations

1. **One grid vs two:** one unified device 3D grid serving both binding detection and the
   FilLink broad-phase. Less code, one build per step. Design it in Phase 3 even though
   FilLink is deferred.
2. **`collisionCheckInt` cadence:** KEEP at every-step throughout the residency campaign.
   Decimating the broad-phase is a physics-affecting optimization (FilLink turnover) that
   needs its own observable validation — do NOT fold it into the residency plumbing.
   Separate optimization, later.
3. **biochem pose writes:** topology-dirty bit + affected-slot re-upload on poly/depoly
   events. Rare, so occasional pushes are fine; no need for continuous write-back.

## On the layout decision (Phase 0) and the device grid (Phase 3)
These are two separate questions and the plan treats them separately. Phase 0 settles the
*storage layout* (AoS vs SoA) via the per-entity move kernel — the cheapest decisive test.
Phase 3's difficulty is the *grid-build algorithm* (atomic per-cell counters or histogram +
prefix-sum), which is largely independent of the layout choice and is done in whichever
layout Phase 0 selects. Conflating them (my earlier "device-grid spike" framing) would have
tested the layout question with the hardest-possible vehicle; the move kernel is the right
one.

## Discipline (every phase, per GPU_MIGRATION_LESSONS.md)
- **Force-coverage audit (Lesson 2):** after each port, confirm every force is applied
  exactly once across the device + CPU-fallback split — never dropped, never doubled. This
  is the anchor-bug class; it is the recurring hazard of this whole campaign.
- **Probe before port (Lesson 5):** if a force lacks a sensitive probe (F1 boundary, F5/F6
  node tether), build the diagnostic FIRST. Two probes are known-missing.
- **Watch it run (Lesson 4):** render a few frames after each port; gross geometry failures
  are visible instantly and hide in scalar observables.
- **Validate once (methodology):** a cheap minimal pre-check (SingleMyoDiag / single-filament
  / smoke) gates the expensive ensemble; the ensemble runs once to confirm, not to localize.

## The one-line state of the campaign
Audit complete; layout decision pending. Next executable step: **Phase 0 — the
layout-decision spike (move kernel AoS vs SoA at 400K scale)** — to set the code-wide storage
rule before substantial porting. Then Phase 1 (anchor spring → device kernel).
