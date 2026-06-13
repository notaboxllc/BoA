# NODE_CONTRACTILE_ASSAY.md — CPU node-based contractility assay (Node path Stage 1)

**Stage 1 of `NODE_PATH_SCOPING.md`. CPU-only oracle for the later GPU port. Built off
`benchmark-contractile-dense`. Not merged — hold for jba.**

The node analog of the minifilament `contractilityAssay`: the same two anti-parallel pinned
filaments and the same pin / tension-readout / stats / JSON / HUD scaffold, with the load
source swapped from a bipolar minifilament to **protein node(s) carrying surface myosins**.
Both the science deliverable and the bit-checkable* CPU reference for Stage 2 (GPU residency).

\* see "Determinism caveat" — the threaded CPU path is not bit-reproducible, so the oracle is
recorded as time-averaged / behavioral metrics, exactly as the minifilament assay's was.

---

## What was built

| Piece | Location |
|---|---|
| `makeNodeContractilityAssay()` IC builder | `BoxOfActin.java` (next to `makeContractilityAssay`) |
| `applyNodeContractilityDefaults()` (stand-alone launch defaults) | `BoxOfActin.java` |
| `ProteinNode.countBoundMotors()` (stats trait) | `ProteinNode.java` |
| `contractBoundMotors()` / `contractHasMotor()` (load-source-agnostic readout) | `BoxOfActin.java` |
| `ContractAssay.nodes` field (the node load source) | `BoxOfActin.java` |
| CLI flag `-contractilityNode` (alias `-bmContractileNode`) | `parseArgs` |
| Config params + dispatch | `Env.java`, `makeInitialThings`, `begin()` |
| Param fixtures | `ParameterFiles/nodeContractilityAssay{,_noMotor,_reversed}` |

**Reused UNCHANGED** (per the scoping survey): the two-filament setup + `Pin` registry,
`captureContractilityTension()` (reads the anchor `forceSum`, projects onto the inward
`buildDir` — indifferent to what pulled), `accumulateContractilityStats` / the
`contractility` JSON block / `[STATS]` reporter / HUD, and `applyContractilityDefaults()`. The
node assay routes through the shared plumbing by also activating `Env.contractilityAssay`;
`makeInitialThings` dispatches the NODE builder first so the minifilament builder is never hit.

### New config knobs (`Env.java`)
| param | default | meaning |
|---|---|---|
| `nodeContractilityAssay` | false | mode flag (the node analog of `contractilityAssay`) |
| `contractNodeRadius` | 0.10 (CLI default 0.06) | carrier-node sphere radius (µm) |
| `contractNodeCount` | 1 | number of carrier nodes (1 bridging sphere; 2 allowed, exploratory) |
| `contractNodeYOffset` | 0.05 | ±Y placement of the carrier nodes (2-node config only) |

Reused controls: `contractNoMotor`, `contractReversePolarity`, `contractFilNSegs`,
`contractFilYOffset`. Reused load knobs: `numNodeMyos`, `numNodeMyoDimers`, `nodeLifetime`,
`myoBrownianAttn`.

---

## Run

```
# stand-alone (applies node-assay defaults, then -pf overrides):
java -Xmx4G --enable-preview -cp "<tornado-api>:libs/*:." BoxOfActin -contractilityNode -seed 1
# self-contained param fixture (CPU, no flag needed):
java -Xmx4G --enable-preview -cp "<tornado-api>:libs/*:." BoxOfActin -r -seed 1 -pf ParameterFiles/nodeContractilityAssay
```
**Heap:** `-Xmx4G`, not `800M` — `MyoMotor` allocates ~640 MB of static SoA arrays; at 800M
the node assay OOMs in `MyoMotor.<clinit>`. **Classpath:** the TornadoVM `tornado-api` jar is
required even for CPU runs (`parseArgs` references `WorkerGrid`). CPU only — **no `-gpu`** this stage.

---

## The geometry finding (the one open design question)

**A single sphere of radial myosins DOES bridge both filaments — but only with myosin thermal
search enabled.** This is the key deliverable; the scoping doc flagged "a sphere of radial
myosins is a clumsier bridge than a rod" and that is exactly what the data show.

The mechanism is sound: surface myosins on the +Y side capture filament A (at +yOff), -Y-side
ones capture filament B (at -yOff); each head power-strokes toward its filament's plus end
(outward, at the pinned ±X walls), the node's ±X reactions cancel (it stays centred), and both
filament anchors are pulled inward = contraction. Tension reads identically at the pins.

**Why the naive single sphere fails (`boundMotors=0`).** Unlike the minifilament, whose end
dimers are *pre-positioned ON* the two filaments, the node's myosins are placed at **random
radial surface points**. A head only binds if it lands within the capture tolerance
(`myoColTol = 0.006 µm`) of a filament line — a tiny solid angle (≈4 patches, fraction ≈1e-3 of
the sphere). With ~30 heads and the thermal dialed down, essentially **none** land on target
and they never move, so zero bind.

**The fix that bridges: thermal search.** Restoring full myosin thermal (`myoBrownianAttn=1.0`,
the global default) lets the heads **diffuse around their surface tether and find the
filaments**. This is the single essential lever:

| sweep config (10–20k steps, seed 1) | thermal | heads | boundMotors | tension A / B (pN) |
|---|---|---|---|---|
| S1 many heads | 0.1 | 60+30 | **0** | 0.001 / 0.001 |
| S3 narrow yOff + wide capture | 0.1 | 40+20 | **0** | 0.001 / 0.001 |
| S2 thermal restored | 1.0 | 40+20 | 3 | 0.08 / 1.37 |
| S4 combo (thermal 0.5) | 0.5 | 80+40 | 6 | 2.21 / 4.13 |
| **win_thermal1** | **1.0** | **60+30** | **8** | **3.78 / 3.67** |

Head count and a modestly enlarged node (`contractNodeRadius=0.06`, vs filament reach
`r + rod + lever ≈ 0.19 µm`) help secondarily; **thermal search is the enabler**. The chosen
default — **1 sphere, radius 0.06 µm, 60 singlets + 30 dimers, `myoBrownianAttn=1.0`, default
yOff=0.05** (matching the minifilament assay) — produces a clean symmetric contractile bridge.
A 2-node variant also works but binds asymmetrically; the single bridging sphere is preferred.

---

## Validation (success pattern)

1. **Contractile signal** — tension rises 0 → positive as motors engage, `boundMotors > 0`,
   **both anchors positive (inward)**, roughly symmetric. ✓
2. **No-motor control** (`contractNoMotor`) — `boundMotors=0`, tension ≈ 0 (avgTension 0.02–0.04
   pN; the ~2.5 pN at step 1 is just the IC settling transient). Confirms the signal is
   myosin-generated. ✓
3. **Reversed-polarity control** (`contractReversePolarity`) — both anchors **negative**
   (extension). Sign tracks the filament geometry. ✓

---

## Recorded oracle (for the Stage-2 GPU port)

Data: `RUN_LOGS/2026-06-12_node_contractility/`. Per-step `[STATS] contractility …` traces;
compact curves in `curve_oracle_*.txt` (cols: step time tensionA tensionB bound avgTension
ewmaTension peakTension).

**Stand-alone default geometry, `-contractilityNode`, 20k steps (node body Brownian OFF):**

| run | avgBound | avgTension (pN) | peakTension | final A / B (pN) |
|---|---|---|---|---|
| seed 1 | 5.45 | 1.97 | 4.61 | +1.73 / +2.72 |
| seed 1 (repeat) | 5.33 | 2.23 | 4.76 | +3.87 / +4.56 |
| seed 2 | 5.77 | 2.23 | 4.32 | +3.51 / +3.57 |
| no-motor | 0.00 | 0.039 | — | +0.0007 / +0.0005 |
| reversed | 5.37 | 2.12 (\|·\|) | 4.60 | **−4.20 / −5.00** |

**Self-contained fixture `nodeContractilityAssay`, 50k steps (node body Brownian ON):**

| fixture | avgBound | avgTension (pN) | peakTension | final A / B (pN) |
|---|---|---|---|---|
| nodeContractilityAssay | 7.19 | 2.00 | 3.01 | +2.22 / +2.11 |
| _noMotor | 0.00 | 0.016 | 0.44 | +0.0013 / +0.0012 |
| _reversed | 4.53 | 0.48 (\|·\|) | 0.90 | **−0.61 / −0.36** |

**Oracle band (the stable signal):** avgBound ≈ **5–7 heads**, avgTension ≈ **2.0 pN**,
peakTension ≈ **3–4.8 pN**, both anchors positive and roughly symmetric. No-motor ≈ **0**
(0.02–0.04 pN, 0 heads); reversed strongly negative. Stage 2 should reproduce these bands
(tolerating the documented float32 binding seam), not bit-exact instantaneous values.

### Determinism caveat
The threaded CPU path is **not** bit-reproducible: same `-seed 1`, two runs gave final
A/B = 1.73/2.72 vs 3.87/4.56. Cause = multithreaded force-sum accumulation order + per-thread
scratch RNGs not derived from the master seed (a pre-existing codebase property, also true of
the minifilament assay). The **time-averaged** metrics (avgBound, avgTension) are robustly
stable run-to-run and across seeds — they are the oracle, not the instantaneous tension.

---

## Boundaries honored
CPU only (no GPU port — that's Stage 2). Scaffold reused, not reinvented. Geometry exploratory,
single-sphere default with knobs open. Production-ring couplings (per-axis pinning, node
birth/death, formin nucleation) off/deferred (`nodeLifetime=1e9`, `kNodeNuc` off). Nothing merged.
