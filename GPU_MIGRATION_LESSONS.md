# GPU Migration Lessons

Hard-won lessons from porting BoA's force computation to the GPU (TornadoVM PTX backend).
Read this before porting any force-computing phase to the GPU, and before debugging any
CPU-vs-GPU divergence. It replaces the earlier GPU_NUMERICAL_PRECISION.md, whose central
"stiff constraints need double precision" thesis was wrong — see the postmortem in
Appendix B.

## The saga, in one paragraph

A GPU myosin-joints kernel caused a ~42% drop in gliding-assay binding events. We spent
seven diagnostics chasing float32 precision (joint arithmetic, normalization, parameters,
thresholds, signed torques) — all came back clean. The actual cause: the rod-tail anchor
spring force was never applied on the GPU path, because the CPU per-myosin dispatch was
short-circuited when GPU joints were active, and the kernel didn't replicate the anchor.
GPU myosins drifted away from their anchors instead of staying put. A single-myosin
diagnostic (no binding) found it in one run: the isolated GPU myosin drifted 10× its
contour length while the CPU one tumbled in place. It was a missing force, not a precision
effect.

---

## Lesson 1 — Silent force-dropping is THE core migration hazard

When you move a force computation to the GPU and short-circuit the CPU path, you must
verify the GPU replicates **every** force that path computed — not most of them. BoA's
`MyosinThreads.divideAndConquer` short-circuited the *entire* per-myosin CPU dispatch when
`(useGPU && !DIAG_CPU_JOINTS)`, but the GPU kernel only replicated the *joint* forces, not
the anchor force. The anchor was silently dropped. Nothing errored; the simulation ran;
the numbers were just wrong.

This is the single most dangerous pattern in incremental GPU migration: "the GPU handles
that now" is true for *part* of a dispatch and false for the rest, and the gap is silent.

## Lesson 2 — Force-coverage audit (do this for every kernel port)

Before and after porting any force to the GPU, produce an explicit table:

| Force | Computed on CPU? | Replicated on GPU? | Applied exactly once? |
|---|---|---|---|

Every force in the system must be applied on **exactly one** path — never zero (silently
dropped), never two (double-applied). For each force the GPU does NOT replicate, the CPU
path must still run it (a reduced dispatch that computes the non-replicated forces and
skips the replicated ones). This audit is mandatory for the step() port, which moves many
forces — the opportunity for silent drops multiplies.

## Lesson 3 — Build the minimal reproduction FIRST, not after seven diagnostics

When a bug won't localize, strip the system to the smallest version that still exhibits
it. A single isolated myosin — no binding, no filaments, no other bodies — made a missing
force unmissable: it drifted away. Seven per-step forensic diagnostics on the full
500-myosin assay couldn't find what one minimal run made obvious. The minimal system
removes confounds (binding feedback, multi-body coupling, ensemble noise) and is
computationally trivial, so you can run it long and characterize it cleanly. Reach for it
early, before deep forensics on the full system.

## Lesson 4 — Watch it run; visual observation is a first-line tool

Numerical observables compress the system to scalars and throw away the geometry. A myosin
drifting ten body-lengths from its anchor is instantly obvious in the Three.js viewer but
shows up in `bindEvents` only as a smaller number, stripped of the spatial story. When a
GPU/CPU divergence appears, the cheapest first move is to render a few frames of both paths
— ideally of the *simplest* case — and watch, before any per-step numerical audit. Gross
failures (drift, blow-up, things not where they belong) are visible in seconds. Headless
remote runs bias you toward number-staring; resist it, and look at the actual behavior.
(Minimal case + visual observation is the strongest combination: watching the full assay
might hide a drift among 500 moving motors; watching one myosin would not.)

## Lesson 5 — Match the validation probe to the physics being changed

The gliding assay caught the missing anchor force only *indirectly and by luck* — binding
happens to depend on head position, which the drift disturbed. No standard observable
directly tested "is the anchor force applied." A kernel that changes a physical subsystem
must be validated by an observable sensitive to that subsystem.

Known coverage gaps as of this writing:
- The gliding assay does not directly exercise filament bending. When filament bending
  moves to the GPU (step() port), the **deflection** and **relaxation-time** benchmarks
  are the probes that test it — and they have never been run on the GPU path. Run them.
- More generally: as the GPU port expands, the validation suite must expand in lockstep,
  one probe per subsystem. The single-myosin characterization (SingleMyoDiag) is now a
  standing probe for isolated myosin dynamics; build analogous minimal probes for each new
  subsystem.

## Lesson 6 — Heed dimensional sanity checks; they redirect early

When the *magnitude* of an effect doesn't fit a proposed mechanism, abandon that class of
hypothesis — don't keep probing variants of it. float32 gives ~1e-7 rad angle error; the
observed conformational bias was 0.07 rad — six orders of magnitude too large for any
float32 rounding story. That mismatch was visible early and should have killed the entire
precision line of investigation immediately. Instead we kept proposing
precision-adjacent diagnostics (arithmetic precision, then storage precision, then
parameters, then thresholds) for sessions. When the numbers say "this isn't precision,"
the next question is "is something *absent or structurally different*?" — not "let me check
another precision-flavored variant."

## Lesson 7 — A partial fix that improves the number can mask the cause

Stiffening the soft rod-lever joint (J2 = 0 → 0.1) reduced the binding drop from −42% to
−24% and pinned the joint angle — a real, measurable improvement that looked like progress.
But it was masking a symptom: a more rigid assembly drifts less far when its anchor is
missing, so the number improved without the root cause (the missing force) being touched.
A fix that partially improves an observable is not evidence you've found the cause — it can
be evidence you've found a knob that *compensates* for the cause. Be suspicious of partial
improvements; insist on a mechanism that explains the *full* effect.

## Lesson 8 — Tier your suspects, but periodically question the whole framing

The cascade of isolating diagnostics (CPU-emulation tests, config swaps, content checks)
was sound methodology — each correctly eliminated a variable. The failure was staying
inside one framing (precision) far too long. The signal to step back: when *fixes* within
a framing keep not working (double arithmetic didn't help, normalization was clean,
stiffening only half-helped), the framing itself is probably wrong. Build a fresh minimal
reproduction rather than refining the Nth diagnostic within the doubted framing.

---

## Appendix A — PTX backend technical reference (still valid)

These facts about the TornadoVM 4.0.1 PTX backend are correct and useful for future kernel
work — primarily for *matching CPU formulas* to remove confounds, not because stiff
constraints need double:

- **Cannot lower to PTX** (canonicalize through ReinterpretNode): `Math.acos`, `Math.asin`,
  `Float.isNaN(x)`, `x != x`.
- **Lower fine to native PTX instructions**: `sin`, `cos`, `sqrt` (float and double).
- **NaN guard replacement**: use `!(magnitude > threshold)` instead of `Float.isNaN` —
  `NaN > anything` is false, so this catches NaN and near-zero together. Mind the threshold
  *units*: a cutoff compared against a magnitude is unit-dependent (an absolute torque
  threshold in SI Newtons means something different than in pN).
- **acos replacement (Newton refinement)**: seed with a branched polynomial, then refine
  `y ← y + (cos(y) − x)/sin(y)`; each iteration roughly squares the error (5e-5 → 2.5e-9 →
  ~machine precision after two steps). Useful when you need a kernel angle to match the
  CPU's `Math.acos` closely enough to remove it as a variable.

## Appendix B — Postmortem: why the precision framing was wrong

The replaced doc (GPU_NUMERICAL_PRECISION.md) claimed "stiff restoring forces must be
computed in double on the GPU." This was never validated and was ultimately irrelevant:

- The bug was a missing force (the anchor), not a precision effect.
- Double-precision joint arithmetic was tested and did *not* move the equilibrium.
- The stiffest joint (lever-motor) computed in float32 was *fine* — its only discrepancy
  was that the CPU's fastAcos approximation made the CPU slightly *less* accurate than the
  GPU.
- The dimensional analysis (Lesson 6) showed float32 could not produce the observed
  effect, which should have ruled out the entire thesis early.

Float32 vs double was a red herring throughout. Precision-matching the kernel formulas to
the CPU (Appendix A) is still worth doing to *remove confounds* during debugging, but
"stiff constraints need double" is not a real requirement and should not guide design.
