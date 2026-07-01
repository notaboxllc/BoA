# Fixed-Head / Neck-Stroke Myosin Motor (flag-gated test model)

**Status:** experimental, **flag-gated, NOT promoted to default** (as of 2026-06-30). CPU path only; the GPU
binding/force kernels are not updated for any of these flags. See the 2026-06-30 `JOURNAL.md` entry for the terse arc.

## 1. What this model is

A myosin gliding-assay motor in which:

- **The motor head does NOT rotate during the power stroke.** It is held at a fixed **90°** to the bound filament
  (polar angle), in *both* nucleotide states — it acts as a rigid, roughly-vertical **strut** between the filament
  and the anchored tail.
- **The power stroke is entirely in the neck (lever).** The lever–motor joint swings through **~70°** on the
  ADP-Pi → ADP transition (0° pre-stroke → 70° post-stroke). The neck's rear (rod-side) end sweeps toward the
  filament's **barbed (+) end**, driving the filament toward its **pointed end** (pointed-leading glide, as in a
  real gliding assay).
- **The swing is locked into the axial plane.** The head's azimuthal roll is pinned to `shat = normalize(nhat × fhat)`
  so the neck swings in the plane containing the filament axis and the bed normal — not transversely.

This contrasts with (a) the stock motor (head swings 90°→120° on the stroke) and (b) the failed "flat center-bind"
detour (head laid 180° *along* the filament — see §6).

## 2. Geometry / angles

| Quantity | Pre-stroke (ADP-Pi) | Post-stroke (ADP / NONE / ATP) | Field |
|---|---|---|---|
| Head–actin (polar) angle | 90° | **90° (unchanged — no head swing)** | `Myosin.uncockedMotor_ActinAngle` / `cockedMotor_ActinAngle` |
| Neck (lever–motor) angle | 0° | **70°** | `Myosin.uncockedLever_MotorAngle` / `cockedLever_MotorAngle` |
| Head azimuth (roll) target | `shat = normalize(nhat × fhat)` | same | `MyoFilLink.alignYVecTorqueAxial` |

- `fhat` = bound segment uVec (pointed→barbed). `nhat` = bed normal = lab **+Z** (the `fixedMyosinZValue` plane).
- The stock model instead uses head–actin 90°→**120°** and lever–motor 0°→60°, with the head roll pinned to the
  **segment's yVec** (the bug — the swing plane then follows the filament's incidental roll).

## 3. Flags (all in `Env.java`, default OFF)

The **gliding model** = `myoFixedHeadNeckStroke` + `myoAxialSwingLock`. The rest are detour / demo scaffolding.

| Flag | Effect | Where |
|---|---|---|
| **`myoFixedHeadNeckStroke`** | `begin()` sets `cockedMotor_ActinAngle = uncockedMotor_ActinAngle = 90` (head fixed, no swing) and `cockedLever_MotorAngle = 70` (neck 0→70° stroke; `uncockedLever_MotorAngle` stays 0). | `BoxOfActin.begin()` |
| **`myoAxialSwingLock`** | (a) `MyoFilLink.alignYVecTorque` → `alignYVecTorqueAxial`: aligns head yVec to `shat = normalize(nhat×fhat)` with a **compliant** torque `k_az·(headYVec × shat)`, `k_az = myoJ1FracMoveTorq (=0.4)`, head-only reaction → swing plane axial. (b) **Bypasses the rod-orientation gate** (`rodDotFil ≥ 0`) in `MyoMotor.checkFilSegCollision` so the barbed-sweep pose (rod pointing toward the pointed end) can bind. | `MyoFilLink.java`, `MyoMotor.java` |
| `singleBindDemo` | Single-myosin + single-filament IC posed for an immediate Brownian-free bind (`MyosinFixed.setUpSingleBindDemo`). For the tip-bound branch the neck is pre-bent toward −X so the rear starts on the barbed side. | `MyosinFixed.java` |
| `myoParallelBindOffset` | Detour: tip-bind + slide the attach point one motor-length toward the pointed end, 180° head. (Run 3 — self-released.) | — |
| `myoCenterParallelBind` | Detour: strain-free CENTER bind, 30° anti-parallel gate, 180° flat head. (Runs 4–5.) | — |
| `myoNeckPerpStroke` | Detour: perpendicular neck rest + 70° stroke (90°→160°) on top of center-bind. (Runs 6–7.) | — |
| `myoCenterBindStandoff` | Detour: nonzero cross-bridge spring rest length (head held N µm off the filament). | — |

**Important caveat:** the barbed-ward sweep direction currently relies on (i) the demo IC neck-bend (single-motor
only) and (ii) the rod-gate bypass being bundled into `myoAxialSwingLock`. The bind-pose ↔ sweep-direction coupling
is **not yet a clean, polarity-derived model rule** — promoting this model should first replace the gate bypass +
IC-bend hack with a bind pose whose barbed-ward sweep falls out of filament polarity directly.

## 4. Key parameter values (stock defaults unless noted)

| Parameter | Value | Note |
|---|---|---|
| `myoRodLength` | 0.080 µm | tail |
| `myoLeverLength` | 0.008 µm | neck / lever arm (the stroke element) |
| `myoMotorLength` | 0.020 µm | head |
| `myoSpring` | 1.0e-9 N/µm | cross-bridge spring = **1 pN/nm** |
| `myosinBreakForce` | 12 pN | force-release threshold |
| `myoColTol` | 0.006 µm | bind capture radius (perpendicular) |
| `myoJ1FracMoveTorq` | 0.4 | compliant orientation-torque coeff; **= k_az** for the axial lock |
| `myoMotorAlignWithFilTolerance` | −0.4 | head-align bind gate (cos) |
| `myoOnFilATP_ADPPi` | 100 s⁻¹ | recharge (rate-limits re-cocking) |
| `myoOnFilADPPi_ADP` | 1e4 s⁻¹ | the power-stroke transition (fast) |
| `fixedMyosinZValue` | −0.05 µm | motor-bed plane (sets `nhat`) |

## 5. Results

### Single-motor verify (Brownian OFF)
`ParameterFiles/singleBindDemo_tipHead90_axlock` (+ IC neck-bend + rod-gate bypass):
- Binds deterministically at step 1; head held ~90° throughout; neck swings **10° → 70°**.
- Swing **axial fraction: 0.13 (lock off) → 1.00 (lock on)**; transverse component → 0.
- Rear (lever rod-side end) sweeps **toward the barbed end** (Δx = +0.0026; was −0.0027 before the direction fix).

### Dense-mat gliding assay (Brownian ON)
`ParameterFiles/glidingDenseMat_axlock` — 6×6×0.5 µm bed, 1000 motors/µm² (**36,000 motors**), 12 seeded filaments
(1.5–3 µm) + canonical, dt 1e-5, 0.15 s, CPU:
- **All 13 filaments glide pointed-leading (correct).**
- Net speed **5.6–10.5 µm/s (mean ~8)**, measured as ‖centroid(t_end) − centroid(0)‖ / t.
- **Directional**: net displacement ≈ path length, ~99% along each filament's own axis (no meander).
- Compatible with **fast skeletal muscle myosin II** gliding velocities (~5–10 µm/s).

### Why the axial lock matters (single-fil A/B, density 1000, Brownian on)
Long-window tracked speed ~8.8 µm/s with or without the lock, but the **net** glide was only ~1.4 µm/s **off** (the
filament wandered, path ≈ 3.5× net). The lock makes the motion **directional**, so net ≈ path ≈ the true glide.

## 6. The failed detour (for the record)
The "flat center-bind" family laid the head 180° *along* the filament (strain-free center tether). It **reeled the
filament down into the motor bed**: with the head flat there is no vertical strut, so the anchored rods pull the
bound filament toward the z=−0.05 plane (filament Z sank 0 → −0.067 µm, through the bed). Gliding was weak and
barbed-ward (wrong). Adding a 10 nm standoff (`myoCenterBindStandoff`) partly arrested the sink and flipped the net
to pointed-leading but stayed weak (~0.01 µm net). Conclusion: the head must remain a **load-bearing ~90° strut** —
which is what the fixed-head model here does.

## 7. Reproduce
```
# build (aorus, Java 21 + TornadoVM on classpath — CPU path)
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java

# dense-mat gliding run (needs the TornadoVM argfile at runtime + large heap)
TORNADOVM_HOME="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx"
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx12G -Dtornado.tvm.maxbytecodesize=16384 \
     -cp "$TORNADOVM_HOME/share/java/tornado/tornado-api-4.0.1-dev.jar:libs/*:." \
     BoxOfActin -r -pf ParameterFiles/glidingDenseMat_axlock -3js ~/Code/threejs_output/glidingDenseMat_axlock
```

## 8. Open items before promotion
- Replace the rod-gate bypass + demo IC-bend with a bind pose that derives the **barbed-ward sweep from polarity**
  directly (clean model rule).
- Update the **GPU** binding/force kernels (all of the above is CPU-only).
- Longer runs (multi-second) + steady-window centroid-track fits for a rigorous velocity number.
- Decide whether to fold `myoAxialSwingLock`'s two effects (roll retarget vs rod-gate) into separate flags.

---

# 9. Mathematical specification (self-contained — for a clean-room v2)

Transcribed from the code. Everything is **overdamped** (no inertia). One integration step is `Δt = deltaT`.
Each rigid body (head=MyoMotor, lever, rod, filament segment) carries a position `x` (µm) and a body frame given by
orthonormal `û` (long axis), `ŷ`, and implied `ẑ = û × ŷ`. Vectors marked `_body` are in that frame; forces/torques
are summed in the body frame, integrated, then the frame is advanced. `end1 = x − ½L·û`, `end2 = x + ½L·û` (L = length).

Constants: `kB = 1.380662e-23 J/K`, `T = 298.15 K`, `η = aeta` (Pa·s, default 0.1).

## 9.1 State & the power-stroke trigger
Nucleotide state `S ∈ {NONE, ATP, ADP-Pi, ADP}`. Define **`cocked ≡ (S ≠ ADP-Pi)`** (note the inverted name: the
"cocked"/post-stroke geometry applies in ADP, NONE, ATP; the pre-stroke geometry only in ADP-Pi).
The **power stroke is the ADP-Pi → ADP transition** — it flips `cocked` false→true, so the neck rest angle jumps
0°→70° and the neck relaxes to the new rest (§9.4b). The head polar angle is 90° in **both** states (no head swing).

## 9.2 Drag and overdamped integration
Rotational and translational drag (isotropic; head modeled as a sphere of radius `r = 0.01 µm`, `r_m = r·1e-6`):

    γ_trans = 6π η r_m           [N·s/m]           (bTransGam, all axes equal)
    γ_rot   = 8π η r_m³          [N·m·s]           (bRotGam,  all axes equal)

Given body-frame force sum `F` (N) and torque sum `τ` (N·m):

    v_body = 1e6 · F / γ_trans   [µm/s]            (the 1e6 is m→µm; positions are in µm)
    ω_body = τ / γ_rot           [rad/s]

Position: `x += Δt · (v_body rotated to lab frame)`.
Orientation — first-order rotation of the body axes by `ω·Δt` (ω = (ωx,ωy,ωz) in body frame), then renormalize:

    û_new = normalize( (1,  ωz·Δt, −ωy·Δt) )   (i.e. x̂ + (ω × x̂)Δt)
    ŷ_new = normalize( (−ωz·Δt, 1,  ωx·Δt) )   (i.e. ŷ + (ω × ŷ)Δt)

(both then transformed body→lab). No Brownian term in the demos (Brownian force, when on, adds
`myoBrownianAttn · randForce` to `F` with `randForce` ~ N(0, 2 γ kB T / Δt)).

## 9.3 Generic joint restoring torque (the master law)
All three orientation constraints are the **same** compliant, per-step-fractional restoring torque between two unit
axes `â` (reference) and `b̂` (the body being driven), toward a rest angle `θ0` (deg):

    n̂  = normalize(â × b̂)                         (rotation axis; if ‖â×b̂‖≈0 skip — degenerate)
    θ  = acos(clamp(â · b̂, −1, 1)) · 180/π         (deg)
    |τ| = k · (π/180)·(θ − θ0) / ( (1/γ_b + 1/γ_a) · Δt )        [N·m]
    (optional cap |τ| ≤ τ_max = myosinStallForce · 0.5 · L_head · 1e-18)
    apply +|τ| n̂ to body a, −|τ| n̂ to body b   (equal/opposite)   [OR head-only, see §9.4c]

`k` is a **coefficient (`myoJ1FracMoveTorq = 0.4`), not a stiffness**: the `1/((1/γa+1/γb)·Δt)` factor makes the
resulting `ω·Δt` a fixed *fraction* of the angle error per step (≈ `k`·error, split across the two bodies) — i.e. the
angle relaxes ~40%/step toward `θ0`, independent of the drag magnitude. `γa,γb` use the drag component about the
relevant body axis: **`.y`** for uVec-type (long-axis) constraints, **`.x`** for yVec-type (roll) constraints.

## 9.4 The three orientation constraints (instances of §9.3)
Let `û_M, ŷ_M` = head axes; `û_L` = lever axis; `û_seg, ŷ_seg` = bound-segment axes; `n̂bed = (0,0,1)` (bed normal).

**(a) Head polar hold** — `MyoFilLink.alignUVecTorque`. `â=û_seg, b̂=û_M, θ0 = 90°` (fixed-head model; stock uses 90°
if ADP-Pi else 120°), drag axis `.y`, two-body reaction. Holds the head at 90° to the filament.

**(b) Neck power stroke** — `Myosin.applyLeverMotorJointTorque`. `â=û_L, b̂=û_M`, `θ0 = 0°` if `S==ADP-Pi` else `70°`,
drag axis `.y`, two-body reaction, **with the τ_max cap**. This is the swing.

**(c) Axial roll lock** (`myoAxialSwingLock`) — `MyoFilLink.alignYVecTorqueAxial`. Replaces the stock roll torque
(which used `â=ŷ_seg`). Target = swing-plane normal:

    ŝ = normalize(n̂bed × û_seg) = normalize(−û_seg.y, û_seg.x, 0)      (skip if ‖·‖<1e-9)

then §9.3 with `â=ŝ, b̂=ŷ_M, θ0=0°`, drag axis `.x`, **head-only** (no segment reaction; align to nearer of ±ŝ). The
sign of ŝ sets the swing *plane*, not the sweep *direction* (that's the bind-pose issue in §3/§8).

## 9.5 Cross-bridge spring (`MyoFilLink.addForces`, per step, per bound head)
Head bind point `p_h = x_M + ½L_head·û_M` (the tip; **center-bind** flag uses `p_h = x_M`).
Attach point (fixed on the filament at bind) `p_a = end1_seg + s·û_seg`, `s = posOnSeg` (arclength from §9.8).

    d   = |p_a − p_h|
    F_mag = myoSpring · d              [N]      (myoSpring = 1e-9 N/µm = 1 pN/nm)
    F   = F_mag · (p_a − p_h)/d                 applied to the head at p_h;  −F applied to the segment at p_a
    F∥  = F · û_seg                             (colinear component; + = toward barbed/+ end) → drives release

(A nonzero rest length `myoCenterBindStandoff = L0` changes `F_mag = myoSpring·(d − L0)`.)

## 9.6 Release: break + Guo–Guilford catch-slip (`MyoFilLink.ckRelease`, per step)
    if F_mag > myosinBreakForce·1e-12 [N]:  release   (break-force; myosinBreakForce = 12 pN)
    else:
      P = kOff · ( αcatch·exp(−F∥·xcatch/(kB T)) + αslip·exp(+F∥·xslip/(kB T)) )     [s⁻¹]
      release if  U(0,1) < P·Δt
    (kOff=100, αcatch=0.92, αslip=0.08, xcatch=2.5e-9 m, xslip=0.4e-9 m; `inRigor` heads never catch-slip-release)

## 9.7 Biochem cycle (`MyoMotor.biochemStep`, called every `round(biochemDeltaT/deltaT)` steps)
Cycle `NONE → ATP → ADP-Pi → ADP → NONE`. Each call, the active transition fires if `U(0,1) < rate·Δt`
(**note: `Δt=deltaT`, not biochemDeltaT — the per-call probability uses the integration step, so with sparse
biochem calls the effective rate is under-counted by ~`biochemCheckInt`; a known quirk, transcribe as-is**):

| transition | rate (on-filament) | value |
|---|---|---|
| NONE → ATP | `atpOnMyo` | 2e4 s⁻¹ |
| ATP → ADP-Pi | `myoOnFilATP_ADPPi` | 100 s⁻¹ |
| **ADP-Pi → ADP (power stroke)** | `myoOnFilADPPi_ADP` | 1e4 s⁻¹ |
| ADP → NONE | `myoOnFilADP_None` | 1e3 s⁻¹, **gated**: only if `⟨F∥⟩ ≤ 0` (running avg, 10-sample) |

## 9.8 Bind decision (`MyoMotor.checkFilSegCollision`, per (head i, segment j) candidate)
Let `p` = the head bind point (tip `x_M+½L_head·û_M`, or `x_M` under center-bind), `e1,e2` = segment ends,
`r1 = e2−e1`.

    Gate A (head align):  û_M · û_seg  ≥  myoMotorAlignWithFilTolerance (= −0.4)
                          [center-bind flag instead requires û_M·û_seg < −cos30° = −0.866]
    Gate B (rod):         û_rod · û_seg ≥ 0        [BYPASSED under myoAxialSwingLock]
    Gate C (formin):      segment not barbed-end-anchored
    α = ((p − e1)·r1)/(r1·r1);   require 0 ≤ α ≤ 1
    c = e1 + α r1;               require |c − p|² < myoColTol²   (myoColTol = 0.006 µm)
    if all pass → bind, set posOnSeg s = α·|r1|

## 9.9 Numeric constants (defaults)
L_rod=0.080, L_lever=0.008, L_head=0.020 µm; r_head=0.010 µm; myoSpring=1e-9 N/µm; myosinBreakForce=12 pN;
myosinStallForce=6 pN; myoColTol=0.006 µm; myoMotorAlignWithFilTolerance=−0.4; myoJ1FracMoveTorq(=k=k_az)=0.4;
fixedMyosinZValue=−0.05 µm (sets `n̂bed`); η=aeta=0.1 Pa·s; kB=1.380662e-23; T=298.15 K.
Model angles: head-polar 90° (both states); neck 0°(ADP-Pi)/70°(else). deltaT=1e-5 s (gliding), 1e-6 s (single-motor demo).
