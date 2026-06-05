# Motor-gap diagnosis — device-vs-CPU divergence in the F8/F9/F10 motor path

Date: 2026-06-04. Survey only — read-only, no edits, no runs, no compiles.
Sibling-instance convention: this file lives at the repo root; the planner
will fold it into JOURNAL.md.

## Premise (carried over)

Both ensemble arms run `-gpu`: GPU float32 filament dynamics, GPU float32
Brownian (inline in `moveThingKernel` via `wangHash`), GPU motor-binding
detection (`GPUMotorBinding.bindKernel`, float32). The only path that
changes between arms is the F8/F9/F10 cross-bridge force computation,
toggled by `GPUMoveThing.DIAG_CPU_MOTOR` (`BOA_DIAG_CPU_MOTOR=1` for the
CPU arm). The release decision (`MyoFilLink.ckRelease`) and ADP→NONE gate
(`MyoMotor.dissociateADP`) run on CPU on both arms; what they *read*
differs by arm. The post-fix N=8 ensemble shows the device arm
systematically weaker than the CPU arm: `bindEvents` ≈ −20 %,
`meanBoundMotors` ≈ −15 %, `glidingVelocity` ≈ −5 %, same sign on every
seed (8/8 negative).

A small correction to the prompt's framing: both arms use
**GPU** motor-binding detection (`GPUMotorBinding.detectBindings()` is
gated on `Env.useGPU`, not on `DIAG_CPU_MOTOR`). So binding detection is
not a differentiator; it's the same float32 GPU kernel in both arms.
This strengthens the prompt's premise — the F8/F9/F10 force path really
is the only motor-side variable.

## Q1 — Precision of the value `ckRelease` actually reads

### Device arm path (`DIAG_CPU_MOTOR=false`)

```
motorForceKernel             (boxOfActin/GPUMoveThing.java:1799,1873)
   compute forceDotFil       — double inside the kernel (lane 0 is
                                Math.sqrt/×/+ in fp64; PTX backend
                                emits f64 instructions)
   motorWriteback.set(2*mj+1, (float) forceDotFil)   ←── *float32 truncation*
                                FloatArray is float32 storage
                                (uk.ac.manchester.tornado.api.types.arrays.FloatArray)
bridgeMotorForceWriteback    (boxOfActin/GPUMoveThing.java:3100-3103)
   double forceDotFil = (double) motorWriteback.get(2*mj+1)
                              ←── float32 → double promotion (no info gained)
   link.forceDotFil = forceDotFil
                              ←── link.forceDotFil is `double`
                                (MyoFilLink.java:31), but the value
                                carries float32 precision
ckRelease                    (boxOfActin/MyoFilLink.java:280-281)
   exp(±forceDotFil · x{Catch,Slip} / (kB·T))
                              ←── reads link.forceDotFil — float32-precision
                                  double
```

### CPU arm path (`BOA_DIAG_CPU_MOTOR=1`)

```
MyoFilLink.addForces         (boxOfActin/MyoFilLink.java:120-149)
   double dist  = Pt3D.ptDist(motorPt, attachPt)
   double forceMag = dist * myoSpring          — all double
   F.unitVec(attachPt, motorPt)
   F.scale(forceMag)                            — F is Pt3D (double fields)
   double thisStepDot = Pt3D.Dot(F, mySeg.uVecAsPt3D())
   forceDotFil = thisStepDot                    — direct double store
                              ←── link.forceDotFil is `double`
                                (MyoFilLink.java:31), value is double-precision
ckRelease                    (boxOfActin/MyoFilLink.java:280-281)
   reads link.forceDotFil — double precision
```

### Verdict for Q1

**Asymmetry exists**: the device arm's `forceDotFil` reaches `ckRelease`
as a float32 value held in a double field; the CPU arm's `forceDotFil`
reaches `ckRelease` as a true double. The truncation point on the device
arm is exactly the `motorWriteback.set(mj*2+1, (float) forceDotFil)`
write at `GPUMoveThing.java:1874`.

**But the magnitude is too small to explain the gap on its own.**
Float32 relative precision ~1.2e-7 × steady-state `|forceDotFil|` ~5e-12 N
= noise ~6e-19 N on each read. Plug into the Guo–Guilford exponent:
`forceDotFil · xCatch / (kB·T) ≈ 5e-12 · 2.5e-9 / 4e-21 ≈ 3`; noise in
the exponent ≈ 6e-19 · 2.5e-9 / 4e-21 ≈ 4e-7. `exp` of that is a
relative change of ~4e-7 per call. Even integrated over the ~50 k
release rolls in a 5-min ensemble per seed, this cannot produce a 20 %
shift in `bindEvents`. (Note: this matches `GPU_MIGRATION_LESSONS.md`
Appendix B — float32 precision keeps being the wrong suspect.)

There is one structural correctness issue worth flagging while we are
here: a small fraction of `forceDotFilTrack`'s 10-sample window on the
device arm is also float32-precision (each `bridgeMotorForceWriteback`
call promotes a float32 to double before `registerValue`). The CPU arm
fills the tracker with true doubles. `dissociateADP` reads
`averageVal()` and gates ADP→NONE on its sign. Same noise argument: the
sign flip is dominated by physical fluctuations (~3.5e-12 N sd per
sample), not by ~6e-19 N float32 noise.

## Q2 — Force-formula divergence (the real finding)

The F8 cross-bridge spring formulas match operation-for-operation
between `MyoFilLink.addForces` (CPU, `MyoFilLink.java:120-149`) and
`motorForceKernel` (`GPUMoveThing.java:1784-1799`) and
`segMotorForceKernel` (`GPUMoveThing.java:1960-1983`): same pinned
direction (`motorPt = motor.coord + 0.5·motorLen·motor.uVec`,
`attachPt = seg.coord + (posOnSeg − 0.5·segLen)·seg.uVec`), same
`F_unit = (attachPt − motorPt)/dist`, same `forceDotFil =
Dot(F, seg.uVec)` BEFORE the seg-side F-flip (sign-equivalent). The
torque arm is `R = (forcePt − coord) · 1e-6` on both sides, R×F via the
standard formula. Newton-3 pair-sum is exactly zero by construction
(seg side computes the same intermediates and writes the anti-parallel
contribution). No fast-math (`rsqrt`, reciprocal approximation,
denormal-flush) is enabled on either path — the kernel uses
`Math.sqrt`/`1.0 / Math.sqrt(...)` which PTX lowers to native f64
intrinsics. So F8 itself: bit-equivalent in double at the formula level.

**The divergence lives in F9 and F10: `accurateAcos` vs `fastAcos`.**

- CPU `alignUVecTorque` / `alignYVecTorque`
  (`MyoFilLink.java:174, 202`) call `Pt3D.fastAcos(dotVecs)`
  (`Pt3D.java:37-48`). For `|dot| > 0.95`, `fastAcos` returns the
  small-angle approximation `sqrt(2·(1 − |dot|))`, then sign-flips
  via `π − sqrt(...)` for the negative branch. For `|dot| ≤ 0.95`
  it falls back to `Math.acos`.
- Kernel F9/F10 (`GPUMoveThing.java:1822, 1846`) call
  `accurateAcos(dotVecs)` (`GPUMoveThing.java:619-649`). This seeds
  with the same small-angle approximation in the outer band and an
  Abramowitz & Stegun 4.4.46 polynomial inside, then runs **two
  Newton iterations** `y ← y + (cos(y) − x)/sin(y)`. Each iteration
  roughly squares the error, so the result lands at ~machine
  precision in fp64 (close to `Math.acos`).

Where this matters:
- **F10 (yVec alignment, target angle 0°, dot near +1)**: at steady
  state the motor's yVec sits very close to the seg's yVec, so
  `dot(seg.yVec, motor.yVec)` lives mostly inside the `|dot| > 0.95`
  fastAcos band. CPU's `angTween = sqrt(2(1−dot)) · 180/π`
  underestimates the true angle; kernel's `accurateAcos` returns the
  fp64-correct angle (larger by ~0.4 % at the band edge, dropping to
  ~0.01 % at dot=0.999). Torque magnitude scales linearly with
  `angD = angTween` (no `angRelaxed` subtract for F10), so:
  **kernel's F10 torque is systematically slightly larger than CPU's
  F10 torque whenever the yVec alignment is loose.**
- **F9 (uVec alignment, target 90° uncocked / 120° cocked, dot near 0
  or −0.5)**: well outside the `|dot| > 0.95` band, so `fastAcos`
  falls through to `Math.acos` and both arms get the same value. No
  systematic divergence in F9 at typical poses.

The `HeldBoundMotorDiag` cheap probe carries a 1e-3 relative tolerance
specifically to absorb this divergence (the comment at
`HeldBoundMotorDiag.java:432-436` is explicit: "accurateAcos vs
fastAcos can differ by ~5e-5 ABS in radians; angD then differs by
~6e-3 deg; torsionMag scales by ~1e7 at gliding params, so ABS
divergence of ~5e1 N·µm is expected on alignment torques. We use a
RELATIVE tolerance of 1e-3 to absorb this acos divergence"). The
JOURNAL summary that the probe agreed "to ~1.13e-16" referred to the
F8 spring path; F9/F10 were known to differ at the 1e-3 level and
that was deliberately accepted at probe time. **The live ensemble
does not have a 1e-3 tolerance budget** — that divergence accumulates
in trajectories.

This is also recorded as known behavior in
`GPU_MIGRATION_LESSONS.md` Appendix A ("acos replacement (Newton
refinement)") and Appendix B ("the stiffest joint (lever-motor)
computed in float32 was *fine* — its only discrepancy was that the
CPU's fastAcos approximation made the CPU slightly *less* accurate
than the GPU"). The earlier conclusion was correct in the joints
context — there the angles were far from the fastAcos band and the
discrepancy was inert. F10's distribution sits inside the band, so
it reappears here.

### Other Q2 sub-questions

No sign error: `forceDotFil` is computed BEFORE the seg-side F-flip
on both paths, so its sign matches CPU in both polarities (probe
Case 4 exercises positive `forceDotFil` and PASSes sign-check).

No order-of-operations divergence in the F8 spring beyond what
floating-point associativity already costs. The kernel and CPU compute
`dist = sqrt(dx² + dy² + dz²)`, `forceMag = dist · myoSpring`,
`F = forceMag · (attachPt − motorPt) / dist` in the same sequence.

No fast-math approximation in the kernel that CPU lacks: PTX `f64`
sqrt is correctly rounded; the kernel uses `1.0 / Math.sqrt(...)`
rather than an `rsqrt` intrinsic.

One small **input-precision** difference exists but is innocuous: the
kernel reads `motorBRGy` / `motorBRGx` / `segBRGx` / `segBRGy` from
`FloatArray`s (`myoDrags`, `bRotGam`), so the drag values entering the
torsion denominator are float32-precision. CPU reads
`myMotor.bRotGam.y` and `mySeg.bRotGam.y` directly as `Pt3D` doubles.
Drag values are per-Thing constants set in
`calculateProperties()` and not derivatives of state, so the float32
representation is a fixed offset (≤1 ULP), not a per-step noise
source. Not the cause.

## Q3 — Precision of the force applied to dynamics

Both arms apply float32 force to the move kernel. Tracing each:

**CPU arm (F8/F9/F10 on CPU)**:
1. `MyoFilLink.step` runs in a `ThreadSet` worker (tid ≥ 0).
2. `addForces` → `myMotor.incForceSum(F, motorPt)` →
   `Thing.taForce[tid][base + axis] += F.axis` — **double accumulator**
   per worker (`Thing.java:73, 365-374`).
3. End of step phase: `Thing.gatherThreadAccumulators` walks dirty
   lists and **narrows to float32** on aggregation:
   `outF[base] += (float) forces[base]` (`Thing.java:798-839`,
   `soaForceSum` is `float[]`, `Thing.java:79`).
4. `GPUMoveThing.packGpuPose` copies `Thing.soaForceSum[s3]` (float)
   into `cpuForceSum` (FloatArray, float32) — lossless float-to-float
   (`GPUMoveThing.java:3272-3274`).
5. `moveThingKernel` / `moveThingKernelSoA` reads
   `cpuForceSum.get(i3) + jointForceSum.get(i3)` as **float32**
   (`GPUMoveThing.java:2092-2094`, `:2261-2263`) and integrates in
   float32.

**Device arm (F8/F9/F10 on device)**:
1. `motorForceKernel` writes the motor side: `jointForceSum.set(mo3,
   (float)((double) jointForceSum.get(mo3) + Fx))` — RMW that reads
   float32, promotes to double, adds the kernel-computed `Fx`
   (double), and truncates back to float32 on store
   (`GPUMoveThing.java:1863-1865`).
2. `segMotorForceKernel` walks the CSR list of bound motors for its
   FilSegment slot, accumulates the seg-side contribution in **double
   locals** (`fxSum`, etc., `GPUMoveThing.java:1934-2029`), then
   single-RMW into `jointForceSum.set(s3, (float)((double)
   jointForceSum.get(s3) + fxSum))` (`GPUMoveThing.java:2035-2037`).
3. `moveThingKernel` reads `cpuForceSum + jointForceSum` as float32 —
   same line as the CPU-arm step 5.

**Verdict for Q3**: ~symmetric. Both arms land the cross-bridge force
in a float32 accumulator slot (`cpuForceSum` on CPU arm,
`jointForceSum` on device arm) and the move kernel integrates the
sum in float32. The arithmetic precision sequences differ in
inconsequential ways (CPU sums per-thread doubles before float
narrowing; device sums in-kernel doubles before float narrowing),
but the post-narrowing float32 representation a moveKernel reads is
~1 ULP either way. **Dynamics feedback is not the asymmetry.**

## Q4 — Where the arms first diverge, and the verdict

Synthesising Q1–Q3, the device arm and CPU arm consume different
values at exactly two well-defined places. Listed in expected
magnitude order:

| # | Where they differ | Magnitude of divergence | Feeds |
|---|---|---|---|
| **1** | F9/F10 angle: kernel `accurateAcos`, CPU `Pt3D.fastAcos` | ~5e-5 rad in the `\|dot\| > 0.95` band, ~0 outside. F10 lives in the band; F9 does not. ~0.4 % systematic difference in F10 torsionMag at typical poses. | (ii) filament dynamics via per-step F10 torque on motor + seg |
| **2** | `forceDotFil` read by `ckRelease`: kernel result is float32-truncated at the writeback, CPU result is true double | ~6e-19 N noise per call on a ~5e-12 N quantity. Translates to ~4e-7 relative change in catch/slip exponent. | (i) release decision |

The drag-input precision difference (kernel reads f32 drags, CPU reads
f64) is a constant offset per Thing — third order and ignored.

**Leading hypothesis: (b) formula asymmetry — `fastAcos` vs
`accurateAcos` in the F10 (and edge cases of F9) alignment torque is
the residual cause of the device-vs-CPU motor gap.** The reasoning:

- (a) float32 reaching `ckRelease` on the device arm only is real but
  too small by ~6 orders of magnitude to produce the observed 20 %
  `bindEvents` shift (`GPU_MIGRATION_LESSONS.md` Appendix B's prior
  conclusion holds — float32 precision is not the suspect here).
- (c) dynamics-feedback asymmetry is essentially absent — both arms
  put the cross-bridge force into a float32 accumulator and integrate
  in float32, with structurally similar rounding paths.
- (b) is the only divergence that (i) is systematic and same-sign
  across seeds (kernel always returns the larger angle for F10), (ii)
  bites at typical equilibrium yVec alignment (`|dot| > 0.95`),
  (iii) feeds the filament dynamics (F10 torque enters motor and seg
  via `incTorqueSum`), and (iv) was explicitly absorbed by the
  `HeldBoundMotorDiag` probe's 1e-3 tolerance window — so the cheap
  probe never flagged it.

The direction of the gap (device weaker on bindEvents / mbm / gv) is
qualitatively consistent with a stiffer F10 alignment on device: the
motor yVec tracks the seg yVec more tightly, which slightly changes
the cross-bridge attachment geometry through to the next step's F8
and forceDotFil, and through there into bind/release rates. The
magnitudes — whether 0.4 % stiffer F10 actually maps to 20 %
bindEvents reduction — cannot be settled from code reading alone and
needs the confirming test below.

There is a secondary candidate worth naming for completeness:
**float32-trajectory chaos.** Because both arms run float32
integration of the same dynamical system, any tiny perturbation (even
1 ULP from the `forceDotFil` writeback or the drag truncation)
amplifies over time. The arms are run with the same `-seed`, but the
divergence at step 1 (motor-force formula differs by 1e-3 relative on
F10) is amplified by float32 chaos over 10⁴ steps. This would produce
a same-sign-per-seed gap that does not shrink with N. The
F10-fastAcos hypothesis subsumes this — fastAcos is the seed of the
divergence — but if the test below does not close the gap, chaos
amplification of even bit-noise becomes the next candidate.

## Decisive confirming test

**Replace CPU's `Pt3D.fastAcos(dotVecs)` with `Math.acos(dotVecs)` in
`MyoFilLink.alignUVecTorque` (line 174) and `alignYVecTorque` (line
202) only**, then rerun the N=8 paired ensemble at
`glidingAssay500_val`.

Rationale: `Math.acos` is fp64-correct; the kernel's `accurateAcos`
is Newton-refined to ~fp64 precision. After this edit both arms
compute F9/F10 alignment torques from the same fp64-correct angle (up
to the input precision asymmetry already discussed in the drag/pose
notes — those are sub-leading). If the gap collapses to noise on
`bindEvents`, `meanBoundMotors`, and `glidingVelocity`, hypothesis (b)
is confirmed and the fix is to either keep the change (CPU absorbs
the cost of `Math.acos`) or replace `accurateAcos` with the kernel
equivalent of `fastAcos` (device absorbs the small-angle
approximation). The clean, low-risk choice is the CPU-side edit
because it makes CPU match the more accurate of the two paths.

If the gap **does not** close, the residual is not the acos
divergence and the next investigation should target trajectory-chaos
amplification of bit-level noise (i.e., even formula-equivalent
double precision would diverge under float32 integration) — at which
point the question shifts from "make CPU and device compute the same
thing" to "are 8 seeds enough to resolve the noise floor of two
float32 trajectories sharing a seed?", and the answer may be that
the post-fix paired gap is the noise floor and not a real signal.
That would argue for the planner's N=16/N=20 option from the prior
entry, not another mechanical fix.

Bounds for the confirming test:
- One-line CPU edit (two call sites). No kernel change. No new files.
- Same `glidingAssay500_val`, same `-gpu` for both arms, same N=8 seeds.
- Wall ≈ 90 min on aorus (same harness as the prior ensemble).
- Compare paired-t against the prior post-fix baseline at
  `RUN_LOGS/2026-06-04_release_lag_fix/results.csv`.

## Files inspected (no edits)

| file | what was checked |
|---|---|
| `RELEASE_LAG_DIAGNOSIS.md` | prior diagnosis of the release-read divergence; rejected pure-lag thesis |
| `JOURNAL.md` (top entries) | Phase 3/4 outcomes; cheap-probe scope; force-coverage table |
| `GPU_MIGRATION_LESSONS.md` | Appendices A/B — Newton-acos rationale, prior float32 framing as red herring |
| `boxOfActin/MyoFilLink.java` | `step`, `addForces`, `alignUVecTorque`, `alignYVecTorque`, `ckRelease`; field types of `forceMag`/`forceDotFil` |
| `boxOfActin/Pt3D.java` | `fastAcos` definition (small-angle approximation with Math.acos fallback) |
| `boxOfActin/GPUMoveThing.java` | `accurateAcos`, `motorForceKernel`, `segMotorForceKernel`, `motorWriteback`, `bridgeMotorForceWriteback`, move kernels; FloatArray usage |
| `boxOfActin/GPUMotorBinding.java` | confirmed device arm uses GPU binding detection (no CPU/GPU split on `DIAG_CPU_MOTOR`) |
| `boxOfActin/HeldBoundMotorDiag.java` | confirmed probe's RELATIVE_TOL = 1e-3 absorbs the acos divergence by design |
| `boxOfActin/MyoMotor.java` | `dissociateADP` reads `forceDotFilTrack.averageVal()` |
| `boxOfActin/ValueTracker.java` | tracker stores `double[]`; on device arm those doubles carry float32-precision values |
| `boxOfActin/Thing.java` | `taForce` (double accumulator), `gatherThreadAccumulators` (narrows to float), `soaForceSum` (float[]), drag accessors |
| `boxOfActin/BoxOfActin.java` | doLoop ordering — step phase before move; `GPUMoveThing.moveThings()` runs in move phase; `GPUMotorBinding.detectBindings()` gated on `Env.useGPU` only |

## Constraints respected

- Read-only survey. No edits, no compiles, no runs.
- `glidingAssay500_val` is the regime of record.
- No new files added beyond this report.
- All file/line references are at the current HEAD as inspected.
