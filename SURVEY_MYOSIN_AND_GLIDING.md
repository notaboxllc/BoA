# Survey: Force-Dependent Myosin Release and Gliding Assay Configuration

**Date:** 2026-05-25  
**Packages surveyed:**
- `~/Dropbox/CodeSync/codeToSurvey/ActomyosinNet3/`
- `~/Dropbox/CodeSync/codeToSurvey/boxOfActinMT-4/`

**Summary:** ActomyosinNet3 (ANM3) contains both targets in working, activated form: a Veigel 2003 catch-bond release model and a complete gliding-assay scaffolding with a dedicated data evaluator. boxOfActinMT-4 contains a slip-bond only release model (older, simpler than BoA mainline) and no gliding-assay code whatsoever. BoA mainline already has a more sophisticated force-dependent release model (Guo–Guilford catch-slip) than either sibling, and has partial gliding-assay scaffolding in `MyosinFixed.java` that lacks the measurement infrastructure ANM3 provides.

---

## Package 1: ActomyosinNet3

### Identification

A 2D actomyosin network simulation. Units are nm, pN, seconds. Main class: `main/Sim2D.java`. Has a Swing GUI (`gui/WorldFrame`), a parameter-set-listener system (`parameters/`), and a rich initializer/iterator/evaluator infrastructure. Pure Java (no Java3D). Closest to a 2D cortical-network simulation; myosin units are flat `Myosin` objects (not the three-part rod/lever/motor hierarchy BoA uses). Filaments are continuous `Actin` objects with per-monomer `Monomer` nodes.

---

### Force-dependent release

**Files:** `main/Myosin.java`

**Key method:** `dissociateADPForceBased()` — Veigel 2003 catch-bond (catch only, no slip term).

```java
// Myosin.java — the ADP-state dissociation step
public void dissociateADPForceBased() {
    // based on curve fit from Veigel et al. 2003 for smooth-muscle myosin
    double F = forceMagTangSigned; // force in bond (pN), positive = toward barbed end
    double k =  1.380662e-2;       // Boltzmann constant in nm·pN/K
    double T = 298.15;             // temperature (K)
    double d = 2.7;                // distance parameter from Veigel 2003 fit (nm)
    double fudge = 1.0;
    double k1 = myoOnFilADP_NoneRate_local * Math.exp(-F * fudge * d / (k * T));
    if (k1 > 2000) k1 = 2000;     // cap to prevent blow-up
    if (Math.random() < k1 * Sim2D.deltaT) {
        setStateNONE();
    }
}
```

This is a **catch-bond only** model. The exponent is `-F * d / (kT)`, so:
- Positive F (force toward barbed end, assisting power stroke): reduces off-rate (catch regime).
- Negative F (load opposing stroke): increases off-rate (the only force regime that accelerates release).

Distance parameter `d = 2.7 nm` is taken from Veigel's empirical curve fit for smooth-muscle myosin. No slip term. Off-rate is capped at 2000 s⁻¹ to prevent numerical blow-up.

**How it is called.** In `biochemStep()`, the ADP state always dispatches to `dissociateADPForceBased()`:

```java
case ADP:
    if (boundState != FREE) { setBoundState(BOUND_PS); }
    dissociateADPForceBased();   // always active — force-based path is hardwired
    break;
```

A constant-rate fallback `dissociateADP()` exists but is never called in the current `biochemStep()`. The parameter `myoUseForceBasedRelease` is declared in `staticInit()` but is not referenced in the switch dispatch — it appears vestigial.

**Force variable.** `forceMagTangSigned` is computed in `doForces()`: the signed tangential spring force along the filament axis, positive if the force on the myosin is toward the plus/barbed end. This is the component of the head–filament spring force that projects along `boundMon.myFilament.uVect`.

**Comparison to BoA mainline.** BoA mainline handles force-dependent release in `MyoFilLink.ckRelease()` (not in `MyoMotor.dissociateADP()`). The mainline version uses the full Guo–Guilford (2006) catch-slip form:

```java
// BoA mainline MyoFilLink.java:ckRelease()
double guoCatchTerm = Env.alphaCatch.getValue() * Math.exp(-forceDotFil * Env.xCatch.getValue() / (Env.Boltz * Env.tempK));
double guoSlipTerm  = Env.alphaSlip.getValue()  * Math.exp( forceDotFil * Env.xSlip.getValue()  / (Env.Boltz * Env.tempK));
double guoCatchSlipProb = Env.kOff.getValue() * (guoCatchTerm + guoSlipTerm);
```

Default parameters: `alphaCatch=0.92`, `xCatch=2.5 nm`, `alphaSlip=0.08`, `xSlip=0.4 nm`, `kOff=100 /s`. This has both a catch term (dominant at low load) and a slip term (dominant at high load), producing the non-monotonic lifetime-vs-force curve that matches NMII single-molecule data. ANM3's catch-only model is simpler and references an older, 2D-specific experiment.

---

### Gliding assay configuration

**Files:**
- `main/MyosinSurface.java` — fixed-position myosin carpet object (extends `MyosinHolder`)
- `initializers/MakeMyosinSurface.java` — parameter-file initializer, reads surface dimensions and density
- `iterators/GlidingAssayEvaluator.java` — per-interval data logger: filament position, distance moved, average motor count, duty ratio
- `inputfiles/GlidingAssay.par` — example parameter file demonstrating a complete gliding assay run

**How the surface works.** `MyosinSurface` holds an array of `Myosin` objects whose `attachPt` is set once at construction and never moved. The `addForce()` / `addTorque()` methods on `MyosinSurface` are explicit no-ops — forces applied to the holder do not displace it.

Construction from `createMyosinHeads()`:

```java
// MyosinSurface.java — uniform random placement within a rectangle
public void createMyosinHeads(int n) {
    myMyosins = new Myosin[n];
    for (int i = 0; i < n; i++) {
        double rdmX = cm.x + (2 * Math.random() - 1) * xDim / 2.0;
        double rdmY = cm.y + (2 * Math.random() - 1) * yDim / 2.0;
        nuMyo = new Myosin(this);
        nuMyo.cm.set(rdmX, rdmY);
        nuMyo.attachPt.set(rdmX, rdmY);    // pinned position, never updated
        nuMyo.bindingSite.set(rdmX, rdmY);
        myMyosins[i] = nuMyo;
    }
}
```

Per-step pinning in `moveMyosins()` (called from `step()`):

```java
// MyosinSurface.java — reset free head location to fixed attach point each step
public void moveMyosins() {
    for (int i = 0; i < myMyosins.length; i++) {
        if (myMyosins[i].isFree()) {
            myMyosins[i].bindingSite.set(myMyosins[i].attachPt);
        }
    }
}
```

**Activation.** No runtime flag. Place a `MakeMyosinSurface` initializer block in the parameter file:

```
&Initializer MakeMyosinSurface
    &CenterXPosition  2000       // nm
    &CenterYPosition  2000
    &XDimension       4000
    &YDimension       250
    &MyosinDensity    0.0001     // per nm² = 100 per µm²
&endInitializer
```

**GlidingAssay.par setup** (from `inputfiles/GlidingAssay.par`): 4000×4000 nm chamber; one 1999 nm filament at the center; surface 4000×250 nm at nominal density 0.0001 /nm². `brownianOn false`. Uses `GlidingAssayEvaluator` to log: `simTime`, per-filament `DistMoved` and `PosX`, `AveMyoCt`, `AveDutyRatio`, speed in nm/s.

**Comparison to BoA mainline.** BoA mainline has `MyosinFixed.java` with `fillPlaneWithFixedMyosins()`, `setUpGlidingAssay()` (which calls `fillPlaneWithFixedMyosins()` + `FilSegment.makeGlidingAssayFilament()`), and `glidingAssayDataSetRun()` (for a density-sweep loop). Activated by setting the `glidingAssay` boolean parameter; `BoxOfActin.java` lines 1849–1850 call `MyosinFixed.setUpGlidingAssay()` on initialization and line 1470 calls `MyosinFixed.glidingAssayDataSetRun()` each loop. The BoA mainline version is 3D (motors fixed at a specified Z plane via `fixedMyosinZValue`), has parameters `fixedMyosinDensity` and `glidingFilamentLength`, and has a density-sweep outer loop with CSV output. What it lacks is a per-timestep velocity/duty-ratio evaluator equivalent to ANM3's `GlidingAssayEvaluator`. The single-filament position tracking in BoA mainline (`posData` array in `MyosinFixed`) is wired for a density-sweep batch run, not continuous output.

---

## Package 2: boxOfActinMT-4

### Identification

An older version of BoxOfActin with Java3D graphics (pre-Phase 4 stripping). Same 3D motor hierarchy as BoA mainline (`MyoMotor`, `MyoFilLink`, `MyoLever`, `MyoRod`, `Myosin`, `MyosinDimer`, `MyoMiniFilament`). Notable differences from BoA mainline: Java3D render path is live (not stubs), QK save/resume code is present in `FileOps.java`, RNG calls use `.raw()` instead of `.nextDouble()`. No `MyosinFixed`, no `ThreeJSWriter`, no `LiveFrameServer`.

---

### Force-dependent release

**Files:** `boxOfActin/MyoFilLink.java`, `boxOfActin/MyoMotor.java`

**Mechanism:** Two complementary checks. First, a directional threshold in `MyoMotor.dissociateADP()`:

```java
// MyoMotor.java — directional gate on ADP-state biochemical release
public void dissociateADP() {
    if (tipLink.forceDotFilTrack.averageVal() < 0) { return; } // skip if force on filament is toward minus-end
    if (myPRNG.raw() < Env.myoOnFilADP_None.getValue() * Env.deltaT.getValue()) {
        setStateNONE();
    }
}
```

Note: in boxOfActinMT-4, `forceDotFil` is computed **after** the sign flip in `addForces()`, so it is the force on the **filament** (not the motor). `< 0` means force on filament is toward minus-end (i.e., the motor is actively pulling in the power-stroke direction). This prevents state-based release while the motor is doing work. BoA mainline computes `forceDotFil` before the sign flip (force on motor) and uses `> 0` for the same physical condition — the two conditions are equivalent despite the sign and direction difference.

Second, a **slip-bond only** model in `MyoFilLink.ckRelease()` (the active version):

```java
// MyoFilLink.java:ckRelease() — slip-bond, force-magnitude exponential
public void ckRelease() {
    if (forceMag > Env.myosinStallForce.getValue()) {
        release();
        return;
    }
    double releaseModX = 1.0;
    if (myMotor.notATP()) { releaseModX *= Env.notATPMyoReleaseMod.getValue(); }
    double releaseProb = Env.myoFBRBase.getValue()
                         * Math.exp(forceMag * 1e12 * releaseModX * Env.myoFBRExp.getValue())
                         * Env.deltaT.getValue();
    if (myMotor.myPRNG.raw() < releaseProb) { release(); }
}
```

`forceMag` is unsigned (bond spring extension magnitude, not signed along filament axis). This is a **slip bond only** — release probability increases monotonically with force, with no catch term. Parameters `myoFBRBase` (base rate) and `myoFBRExp` (exponential coefficient) control the curve shape. The stall-force ceiling is in `myosinStallForce`. A commented-out constant-rate alternative is also present.

**Comparison to BoA mainline.** BoA mainline replaced this slip-only model with the Guo–Guilford catch-slip form. The commented-out code in BoA mainline `MyoFilLink.java` (lines 222–238) is exactly the boxOfActinMT-4 slip-only equation. This package is thus an earlier development state, not a new approach to port.

---

### Gliding assay configuration

**Package boxOfActinMT-4 does not contain a gliding assay.** No `MyosinFixed`, no `glidingAssay` parameter, no `fillPlane` methods, no surface-binding initializer. A `StaticFilSegment.java` file exists but is not wired into the simulation (commented-out references at `BoxOfActin.java:480` and `:510` for an unrelated static-filament concept). All myosin objects are free-diffusing `MyoMiniFilament` bipolar assemblies.

---

## Comparison summary

| Feature | ANM3 | boxOfActinMT-4 | BoA mainline |
|---|---|---|---|
| **Force-dependent release** | YES — catch-bond only (Veigel 2003); `Myosin.dissociateADPForceBased()`; 2D; signed tangential force | YES — slip-bond only (magnitude exponential); `MyoFilLink.ckRelease()`; 3D; precursor to BoA mainline | YES — full catch-slip (Guo-Guilford 2006); `MyoFilLink.ckRelease()`; 3D; most sophisticated |
| **Kinetic form** | k(F) = k₀·exp(−F·d/kT), d=2.7 nm, one term | k(F) = k_base·exp(|F|·β), magnitude only | k(F) = kOff·(α_c·exp(−F·x_c/kT) + α_s·exp(F·x_s/kT)), two terms |
| **Biological reference** | Veigel et al. 2003 (smooth muscle, 2D context) | None cited | Guo & Guilford 2006 (catch-slip, non-muscle context) |
| **Gliding assay** | YES — `MyosinSurface` carpet; `MakeMyosinSurface` initializer; `GlidingAssayEvaluator`; example .par file | NO | PARTIAL — `MyosinFixed.fillPlaneWithFixedMyosins()`; Z-plane at configurable depth; density-sweep loop; no per-step velocity evaluator |
| **Gliding measurement** | Per-step per-filament velocity, duty ratio, motor count (`GlidingAssayEvaluator`) | n/a | Batch-mode position snapshots for density-sweep (no continuous velocity log) |
| **Dimension** | 2D | 3D | 3D |
| **Maturity (release kinetics)** | Functional but 2D-specific; simpler than BoA mainline | Superseded by BoA mainline | Production-grade; most complete |
| **Maturity (gliding assay)** | Complete and runnable (example .par provided) | Absent | Infrastructure present; measurement output incomplete |

**Key observation on force-dependent release:** BoA mainline already implements the more biologically complete model (Guo–Guilford catch-slip) compared to either sibling. The ANM3 catch-only model and the boxOfActinMT-4 slip-only model are both earlier, simpler formulations. boxOfActinMT-4's slip-only `ckRelease()` is literally the commented-out precursor code in BoA mainline's `MyoFilLink.java`.

**Key observation on gliding assay:** ANM3 has the only complete, runnable gliding assay — it has the filament velocity evaluator that BoA mainline's `MyosinFixed` lacks. BoA mainline's `MyosinFixed` has the density-sweep and data-accumulation structure but no continuous per-step velocity output. ANM3's `GlidingAssayEvaluator` is the portable piece.

---

## Recommendations

**Force-dependent release:** Do not port from either sibling. BoA mainline already has the more complete Guo–Guilford catch-slip model in `MyoFilLink.ckRelease()`. What is worth doing is tuning the existing parameters (`alphaCatch`, `xCatch`, `alphaSlip`, `xSlip`, `kOff`) against NMIIA/NMIIB single-molecule data (Stam 2015, Melli 2018) as part of the motor validation battery — the mechanism is already in place.

**Gliding assay measurement output:** Port from ANM3. The missing piece in BoA mainline is a per-step velocity evaluator: per-filament `distMoved` since last output, filament position, average bound motor count, and ensemble duty ratio. ANM3's `GlidingAssayEvaluator` (about 120 lines) implements exactly this and writes a clean columnar data file. The 3D BoA mainline analog would attach to `MyosinFixed.glidingAssayDataSetRun()` or run independently as a new output callback in the `doLoop()` safe-point region. The `MyosinSurface` pinning mechanism (fixed `attachPt`, no-op `addForce()`) translates directly to `MyosinFixed`'s existing `applyRodFixedPtForce()` approach — no new motor pinning infrastructure is needed.

**Note on a possible BoA mainline quirk:** While surveying, the sign convention of `forceDotFil` in `MyoFilLink.addForces()` deserves scrutiny. The Guo–Guilford formula is evaluated at every timestep regardless of nucleotide state (not just in the ADP state), while `dissociateADP()` in `MyoMotor` applies an additional directional gate only in the ADP state. Whether these two pathways cooperate or conflict in the intended catch-bond regime is not clear from the code alone — worth verifying before the gliding assay benchmark is instrumented, since incorrect catch-slip behavior would show up as anomalous velocity-vs-load curves.
