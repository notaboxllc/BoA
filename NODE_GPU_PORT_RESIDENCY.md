# NODE_GPU_PORT_RESIDENCY.md — protein-node path GPU residency (Node-path Stage 2)

**Stage 2 of `NODE_PATH_SCOPING.md`. Ports the protein-node path to GPU residency
(`RULE_NODE`), validated behaviorally against the Stage-1 CPU oracle
(`NODE_CONTRACTILE_ASSAY.md`). Built on `benchmark-contractile-dense`. NOT merged — hold for jba.**

Applies the `RULE_MINIFIL` playbook to the protein node: the node body integrates on
device (`RULE_NODE`, shared `moveThingKernel`), and its surface tethers + node-dimer
internal cohesion run as one device kernel (`nodeTetherKernel`) reading the resident pose,
with the single-writer + 1-step-lagged device-resident reaction buffer adopted **from the
start** (the minifilament's hardest-won lesson). The CPU node path is retained behind
`BOA_NODE_GPU=0` as the permanent A/B oracle.

---

## The hazard, confirmed first (THE validation)

`NODE_CONTRACTILE_ASSAY.md`'s key finding: node surface myosins start at random radial
points and must **thermally diffuse around their surface tether to find the filaments** —
without myosin thermal search, binding silently fails (`boundMotors=0`). The scoping doc
flagged this as the #1 device-port hazard.

**Confirmed empirically.** Running the (CPU-validated) node assay on `-gpu` *before* this
port — node as `cpuFallback`, tethers on CPU — silently fails:

| run | boundMotors | tension (pN) |
|---|---|---|
| GPU **before** port (node = cpuFallback) | **0** | decays to ~0.16 |

Mechanism: the node body + its myosins' rods are device-resident, but on the
`noMonomersSimd` residency path the per-step host-pose pull is gated off, so the CPU tethers
(`keepMyosinsOnSurface`, `myoJoints2`) and node-dimer `enforceParallel` (`myoJoints1`) read
**stale host pose** → wrong restoring force → heads drift, never bind. This is exactly the
silent thermal-search failure, and exactly why the fixture is annotated "RUN ON CPU".

**After the port** the device kernel computes the tether from FRESH resident pose and the
search reproduces (see Validation).

---

## What was built

| Piece | Location |
|---|---|
| `RULE_NODE` classification + `nodeDeviceEligible()` predicate | `GPUMoveThing.classifyThings` / `ruleForGpuThing` |
| Node body integration (shared `moveThingKernel`) + per-axis `velMask` + Brownian scale | `GPUMoveThing.classifyThings` / `packRange` |
| `nodeTetherKernel` — singlet tether + dimer tether + node-dimer rod↔rod + lever align | `GPUMoveThing.java` |
| `nodeBodyReact` — device-resident 1-step-lagged per-node reaction buffer (SET) | `GPUMoveThing.java` |
| `packNodeTethers()` — per-node CSR over flat singlet/dimer records | `GPUMoveThing.java` |
| TaskGraph `nodeTether` task (after `dimerCohesion`, before `move`) + 64-thread WorkerGrid | `GPUMoveThing.allocateAndBuildPlan` |
| CPU gates (force-exactly-once): `ProteinNode.tethersOnDevice`, `MyosinDimer.nodeCohesionOnDevice` | `ProteinNode.java`, `MyosinDimer.java` |
| `cpuNodeTetherApplyCt` force-exactly-once counter | `ProteinNode.keepMyosinsOnSurface`, `BoxOfActin` summary |

### Stage 2A — node body integration on device (`RULE_NODE`)
- New `RULE_NODE = 4`. A plain `ProteinNode` (NOT a StickyNode/FillNode/AnchorNode subclass)
  that is not `fixedNode` and is in a run with no formin nucleation (`kNodeNuc` inactive),
  under the single chained graph, is classified device-resident; everything else stays
  `cpuFallback` exactly as before. Gate: `BOA_NODE_GPU != 0` (default on).
- The node body uses the **shared `moveThingKernel`** — no new integrator. Its per-axis
  fixed-frame restriction (`xMove/yMove/zMove`) is honored via `velMask` (the move kernel
  applies it in the fixed frame, exactly where `ProteinNode.moveThing` applies the gates).
  Body-frame `bYMove` is NOT expressible via `velMask` (deferred; absent in the assay).
- Brownian scale `tScale=rScale=1.0` (raw free-body, matching the CPU
  `ProteinNode.moveThing` `bForceSum.inc(randForces)` with no extra coefficient), hard-off
  via `Env.nodeBrownianMotionOff`. The node's myosins are RULE_MYO/RULE_LEVER and already
  get full device Brownian (`myoBrownianAttn`) — this is what powers the thermal search.

### Stage 2B — surface tethers + node-dimer cohesion on device (`nodeTetherKernel`)
**ONE thread per node.** Each thread loops its singlet records and dimer records:
- **Singlet tether** (`keepMyosinsOnSurface`): rod `+= F`, node `-= F` (pure force, no
  torque), `forceMag = 0.4·(1e-6·strain/numNodeMyos)/(dt·(1/rodBTy + 1/nodeBTy))`.
- **Dimer tether** (`keepMyosinDimersOnSurface`): rod1 `+= F` at `end1` (force + torque),
  node `-= F` at the surface attach point (force + torque),
  `forceMag = 0.4·(myoDimerFracMove·1e-6·strain)/(dt·(1/rod1BTy + 1/nodeBTy))`.
- **Node-dimer internal cohesion** (`enforceParallel`): `applyRodCouplingEnd1/End2` +
  conditional `alignUVecLeversTorque` — **byte-identical** to `dimerCohesionKernel`'s blocks
  (same `MyosinDimer` methods, same `fracMD`/`fracLT`/`leverAngle`). Node dimers have
  `ownerMiniFil == null` so the minifilament cohesion kernel never touches them; folding
  their rod↔rod coupling into the per-node thread closes the second stale-pose gap in one
  kernel (the thread already holds both rods).

**The `xToX` full-frame transform — the one genuinely new cost vs the minifilament.** The
surface attach point is a body-frame offset on the sphere (`myoPtsInx[i]`, a packed
constant), rotated to world by the full orientation frame:
`attach = node.coord + o.x·uVec + o.y·yVec + o.z·zVec` (the kernel reconstructs
`zVec = uVec × yVec` from the two resident frame vectors). The minifilament got away with a
purely axial `coord + offsetX·uVec`; the node needs `yVec`/`zVec` too. Fully expressible —
just more arithmetic and one extra resident-buffer read (`yVec`).

**Single-writer + 1-step-lagged reaction (adopted from the start).** Each rod/lever slot
belongs to exactly one node → RMW into `jointForceSum/Torque` is race-free across nodes. The
node **body** slot is single-writer (only its own thread) and carries the body reaction
(`-F`/`-τ` summed over the node's tethers) with a 1-step lag held in the device-resident
`nodeBodyReact` buffer (FIRST_EXECUTION, SET not RMW) — the identical stiff-stability measure
the minifilament cohesion proved necessary (the body couples to N tethers at once;
synchronous application is float32-unstable). No host bridge.

**Force-exactly-once.** The CPU node tethers (`ProteinNode.updateMyosins` →
`keepMyosin*OnSurface`) and node-dimer `enforceParallel` (`MyosinDimer.jointConstraints`) are
gated off in lockstep via `ProteinNode.tethersOnDevice()` /
`MyosinDimer.nodeCohesionOnDevice()` — the exact predicates `packNodeTethers` packs on. The
`cpuNodeTetherApplyCt` counter reads **0** on the device path (vs `20100` on CPU).

---

## Run

```
# build (aorus, Java 21 + TornadoVM PTX)
./build.sh
# GPU node assay (device residency on by default; BOA_NODE_GPU=0 for the CPU A/B oracle)
./run_gpu.sh -r -gpu -seed 1 -pf ParameterFiles/nodeContractilityAssay
# CPU oracle
./run_cpu.sh -r -seed 1 -pf ParameterFiles/nodeContractilityAssay
```

---

## Validation (behavioral vs the Stage-1 CPU oracle; tolerating the float32 binding seam)

Data: `RUN_LOGS/2026-06-12_node_gpu_port/`. The threaded CPU path is not bit-reproducible
(documented), so the oracle is the time-averaged band, not instantaneous tension.

### 1. Search-capture works on device — THE check
`boundMotors > 0` on device, `avgBound` in the oracle band — the device Brownian +
`nodeTetherKernel` reproduce the diffuse→find→bind search. (Before the port: `boundMotors=0`.)

### 2. Contractile signal + 3. controls + 4. A/B + 5. force-exactly-once

| run (seed 1) | avgBound | ewmaTension (pN) | peak (pN) | final A / B (pN) | cpuNodeTetherApplyCt |
|---|---|---|---|---|---|
| CPU main, 20k (oracle)        | 6.64 | 1.94 | 2.02 | +2.24 / +1.66 | 20100 |
| **GPU main, 20k**             | **7.08** | **1.73** | **2.01** | **+1.76 / +1.65** | **0** |
| CPU `_noMotor`, 15k           | 0.00 | 0.001 | 0.44 | +0.001 / +0.001 | 0 (no node myosins) |
| **GPU `_noMotor`, 15k**       | 0.00 | 0.001 | 0.28 | +0.001 / +0.001 | 0 |
| CPU `_reversed`, 15k          | 6.24 | 0.51 (\|·\|) | 0.96 | **−0.55 / −0.47** | — |
| **GPU `_reversed`, 15k**      | 5.92 | 0.61 (\|·\|) | 0.96 | **−0.55 / −0.67** | 0 |

All three GPU runs finished rc=0 with **no 701 / NaN / overflow** (an apparent "701" in a log
grep was a false positive inside a tension value `1.7019`). noMotor reproduces the ≈0 control
(boundMotors=0); reversed reproduces the strongly-negative extension control, both matching
CPU within variance.

GPU main vs CPU oracle at 20k: avgBound 7.08 vs 6.64, peak 2.01 vs 2.02, tension symmetric
and positive — a clean behavioral match within the documented threaded run-to-run variance
(the CPU path itself gives final A/B 1.73/2.72 vs 3.87/4.56 across same-seed repeats). The
GPU `boundMotors` runs 6–10/step throughout (vs **0** before the port) — the thermal search
reproduces on device.

**Oracle band (Stage 1):** avgBound ≈ 5–7, avgTension ≈ 2.0 pN, peak ≈ 3–4.8 pN, both
anchors positive; no-motor ≈ 0; reversed strongly negative.

### 6. Force-exactly-once at classification
`[A3] cpuFallback histogram` reports `ProteinNode=0` on the GPU node path (the node is
device-classified, out of the catch-all). No 701 / NaN / overflow on any run.

---

## Boundaries honored / deferred
- CPU node path retained behind `BOA_NODE_GPU=0` (permanent A/B oracle).
- 1-step-lagged device-resident reaction adopted from the start (not rediscovered).
- Production-ring couplings **deferred** (NODE_PATH_SCOPING §4): body-frame `bYMove`
  restriction (only `xMove/yMove/zMove` via `velMask`), node birth/death (`nodeLifetime`),
  formin nucleation (`kNodeNuc` — eligibility gate keeps such nodes on CPU). Membrane
  StickyNode/FillNode/AnchorNode subclasses stay host-OOP.
- float32 binding seam tolerated (compared behaviorally, as the minifilament port did).
- Nothing merged.
