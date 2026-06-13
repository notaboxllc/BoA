# NODE_PATH_SCOPING.md — protein-node contractile assay + GPU residency

**Survey only — no code changed.** Scopes two pieces of work on the protein-node path
(jba's flagship whole-cell contractile **ring** substrate): **(a)** a node-based
contractile assay (the node analog of the minifilament `contractilityAssay`), and **(b)**
GPU residency for the protein-node path (`RULE_MINIFIL` playbook applied to nodes).

Sources: `ProteinNode.java`, `BoxOfActin.java`, `GPUMoveThing.java`, `MyosinDimer.java`,
`MyoMiniFilament.java`, `Env.java`; docs `RESIDENT_POSE_DELTA_SCATTER.md`,
`MYOSIN_VALIDATION.md`, `FORCE_OFFLOAD_RESIDENCY.md`; JOURNAL entries 2026-06-10/11/12.

---

## 1. ProteinNode state map (the way the cohesion Step-0 survey mapped the dimer dispatch)

`ProteinNode extends Thing` — a small sphere (`radius` default `0.05` µm,
`Env.nodeRadius`) that organizes a sub-population of myosins/dimers and can nucleate
formins. Full `Thing` pose machinery (coord/uVec/yVec, forceSum/torqueSum, drag/diffusion
tensors, SoA slot, stable `thingInstanceId`) **plus** its own sub-populations.

### Structure & state (`ProteinNode.java`)
- `radius = Env.nodeRadius.getValue()` (`:21`); sphere geometry, **length-0 in the SoA**
  (no rod length).
- **Myosin singlets**: `myoCt = Env.numNodeMyos` (`:55`), `Myosin[] myosins`, body-frame
  attach pts `Pt3D[] myoPtsInx`, world-frame attach pts `Pt3D[] myoPtsInX` (`:55-58`).
- **Myosin dimers**: `myoDimerCt = Env.numNodeMyoDimers` (`:61`), `MyosinDimer[] myodimers`,
  `myoDimerPtsInx`/`myoDimerPtsInX` (`:61-64`).
- Formins: `FilSegment[] forminFils` (cap 30), `forminCt` (`:50-52`).
- **Per-axis movement restriction**: `xMove/yMove/zMove` (fixed frame) + `bYMove` (body
  frame), all default `true` (`:39-44`). `makeMyosinClusterArray` sets `xMove=false;
  zMove=false` on cortex clusters (`:829`) → Y-mobile only. `fixedNode` flag (`:24`) skips
  membrane-node integration. **No analog exists in MyoMiniFilament — this is node-specific.**
- `myNodeNumber` (compaction-reassigned, like `myThingNumber`); `thingInstanceId` purely
  inherited & stable.

### Integration (`moveThing`, `:212-270`) — a REAL overdamped Langevin integrator
Unlike the minifilament body (whose CPU `moveThing` was retired during its port) and unlike
Chamber/Crucible/AnchorNode (empty stubs), a bare node **integrates**: body-frame force/
torque transform, Brownian add (`randForces`/`randTorques` unless `Env.nodeBrownianMotionOff`,
default false), `bVeloc = bForceSum/(1e6·bTransGam)`, **per-axis restriction**
(`if(!bYMove) bVeloc.y=0` `:238`; `xMove/yMove/zMove` `:249-251`), `incCoord` + small-angle
uVec/yVec rotation. `step()` (`:288-298`) runs a periodic Bug/box boundary check
(`F12_PROTEINNODE_BOUNDARY`). `biochemStep()` (`:282-286`) = stochastic death with prob
`deltaT/nodeLifetime` (default 10 s).

### Node→myosin attachment geometry (the crux for the kernel question)
Attach points are **arbitrary points on the sphere surface** — a random unit vector in the
**body frame** scaled by radius, transformed to world by a **full body→world rotation**:
```java
// makeMyosinSinglets, :328-334
myoPtsInx[i] = Pt3D.RandomUnitVec(...);          // random body-frame direction
myoPtsInx[i].scale(getRadius());                 // point on sphere (body frame)
myoPtsInX[i].xToX(this, myoPtsInx[i]);           // FULL rotation (uVec+yVec+zVec), not axial
myoPtsInX[i].inc(coordAsPt3D());                 // + node center → world attach pt
```
Refreshed each step from the (rotated) body frame in `updateMyosinPositions()` (`:362-367`)
/ `updateMyosinDimerPositions()` (`:440-445`). **Contrast the minifilament**, whose attach
offset was *purely axial* (`coord + offsetX·uVec`, y=z=0) — so the minifilament cohesion
kernel needed no yVec/zVec rebuild. **The node attach point requires the full orientation
frame.** This is the single biggest structural difference for the GPU port (see §4).

### Full CPU force coverage (every force that touches a node)
| Force | Method (`ProteinNode.java`) | Phase / ThreadSet | Reaction on node | Live in production? |
|---|---|---|---|---|
| Singlet surface tether | `keepMyosinsOnSurface` `:369` | `myoJoints2` (phase 8), `ProteinNodeThreads`, via `updateMyosins` `:398` | **pure force** (`incForceSum(F)` `:388`, no moment arm → no torque) | only if `numNodeMyos>0` |
| Dimer surface tether | `keepMyosinDimersOnSurface` `:447` | same (phase 8) | **force + torque** (`incForceSum(F, curAttPt)` `:465`, has moment arm) | only if `numNodeMyoDimers>0` |
| Node↔node soft collision / sticky link | `checkNodeCollision`/`forceCollision` `:581/619` | `Mesh` threads, gated `Env.collideProteinNodes` | force | only if `collideProteinNodes` & >1 node |
| Bug/box boundary | `checkOuter/InnerBugCollision` `:484/493` | `step()`, every `collisionCheckInt` | force | Bug/Listeria configs only |
| Formin nucleation (couples node↔FilSegment, no direct force) | `spawnNodeFilaments` `:659` | `doLoop` cleanup `:1949` | — | only if `kNodeNuc` active |
| Brownian | inside `moveThing` `:227-231` | `brownianThreads` fills, move consumes | force/torque | yes unless `nodeBrownianMotionOff` |

**The surface-tether force law** (the only forces that read resident pose):
- Singlet (`:382`): `forceMag = 0.4·(1e-6·strainDist / numNodeMyos) / (deltaT·(1/myoRod.bTransGam.y + 1/bTransGam.y))`,
  pulling `myoRod.end1` back to the surface attach point. `attnForce = 0.4` is a **hard-coded
  constant, not a Parameter**.
- Dimer (`:459`): `forceMag = 0.4·(myoDimerFracMove·1e-6·strainDist) / (deltaT·(1/curRod.bTransGam.y + 1/bTransGam.y))`,
  anchored to `myo1.myoRod.end1`. Scales by `Env.myoDimerFracMove`, **not** divided by count.
- **No conditional gate inside either method** — pure geometry once entered. Gated only at
  the population level: `updateMyosins` runs in `myoJoints2` only `if(nodeCt>0)`; the inner
  loops are empty when `numNodeMyos`/`numNodeMyoDimers`=0.

**myoJoints2 = the 4-pool serialized force wave** (phase id 8, `runForceWave` in
`BoxOfActin.java:1381`): ProteinNode + MyoMiniFilament + Chamber(Myo) + Chamber(MyoD), all
serialized because all write force sums (the `taForce` race).

### Live vs dead today
- **Gliding / contractility / deflection / LP**: `numNodeMyos`=`numNodeMyoDimers`=0,
  `nodeCt`=0 → entire node path is a no-op; ProteinNode effectively absent.
- **`singleNode_myosins`, cluster/cortex, Listeria**: populated → tethers live, integration
  on CPU even under `-gpu`.

### GPU classification — ProteinNode is ALWAYS cpuFallback (host-OOP)
`classifyThings()` (`GPUMoveThing.java:4888+`) tests `FilSegment`, `MyoMotor||MyoRod`,
`MyoLever`, `MyoMiniFilament` — ProteinNode matches none and hits the catch-all `else →
cpuFallback` (`:4949-4951`). No `brownianRuleFor` rule either (`:462-465`). A3 tracks it via
`cpuFbProteinNode` (`:902`, incremented `:4995`). StickyNode/FillNode/AnchorNode extend
ProteinNode and also fall here. `FORCE_OFFLOAD_RESIDENCY.md` (A3) confirms ProteinNode
host-OOP; its surface tethers run in the CPU `myoJoints2` wave.

---

## 2. Existing-config inventory

### `ParameterFiles/singleNode_myosins` — the only dedicated node fixture
`deltaT=1e-5`, box `1×1×0.5` µm, `runTime=1.0`, turnover off (all rates inactive),
**`initialNodes=1`**, **`numNodeMyos=8`**, **`numNodeMyoDimers=4`**, **`nodeRadius=0.1`**
(2× default, "for visibility"), `myoBrownianAttn=0.1` (myosin thermal at 1/10 to avoid
twirl — matches [[production-run-config-prefs]]), `simOutsideBug:false` (no Listeria), no
actin, no minifilaments. CPU only (header says "omit -gpu"). Exercises
`initialNodes → makeInitialProteinNodes → makeRandomProteinNode → ProteinNode` with
full-sphere myosin placement.

### Node IC builders (`ProteinNode.java`)
- **`makeInitialProteinNodes()`** `:740` — loops `initialNodes` × `makeRandomProteinNode`.
  Dispatched unconditionally in `begin()` (`BoxOfActin.java:3196`).
- **`makeRandomProteinNode()`** `:746` — one node at a random interior point (node-zone in a
  bug crucible).
- **`makeMyosinClusterArray()`** `:799` — the closest thing to a multi-node/cortex builder,
  gated by `Env.fixedMyosinClusters` (`BoxOfActin.java:3167`). Box branch = staggered grid;
  bug branch = `numHotSpotsOnCortex` nodes around the cortex circumference, inward-facing
  (hemisphere myosin placement). Cortex *cluster ring* geometrically, **but no ring-closure
  tension readout or contractile-ring physics**.
- `makeTestNodePair()` `:761` / `makeTestNode()` `:768` — not dispatched (no live caller).
- `rdmNodeInsideCurrentDistribution()` `:784` — runtime node-number maintenance
  (`equilibrateNodeNumber`), not an IC builder.
- Test fixtures in `FilSegment.java`: `twoByTwoNodes`/`threeByThreeNodes`/`nodeChain`/
  `twoNodesOneFil` (node+filament test rigs).

### Node Env parameters (`Env.java`)
`initialNodes` (0), `equilNodes` (0), `numNodeMyos` (0), `numNodeMyoDimers` (0),
`nodeRadius` (0.05), `nodeZone` (0.9), `forminsPerNode` (0), `nodeTransDiff`/`nodeRotDiff`
(Stokes, inactive), `collideProteinNodes` (false), `nodeLifetime` (10 s), `nodeTorqSpring`
(1e-18), `kNodeNuc` (10/node·s), `numHotSpotsOnCortex`/`numOffCenterHotSpotRows`/
`hotSpotRowSpacing`, `fixedMyosinClusters` (false). Plain statics: `nodeFracMove=0.5`,
`nodeBrownianMotionOff=false`. (Membrane/fill `StickyNode` params are a separate subsystem.)

### Ring formation
**No whole-cell contractile ring config exists** — zero "ring" matches in param files; none
in `.java`. No `makeRing*`/`contractileRing`/`wholeCell` builder. The cortex-cluster bug
branch of `makeMyosinClusterArray` is the only cortical-myosin scaffold; a true contractile
ring is aspirational/absent.

### How nodes get myosins (data flow)
`numNodeMyos`/`numNodeMyoDimers` → ProteinNode ctor → `makeMyosinSinglets`/`makeMyosinDimers`
→ each myosin at a **uniformly random surface point**, oriented radially, stored in body &
world frames; tethered each step by `keepMyosin(Dimer)sOnSurface`. Every node gets exactly
the param count (8+4 in `singleNode_myosins`). Hemisphere variants flip wrong-side vectors
(cortex/inward-facing nodes).

---

## 3. Gap analysis — node contractile assay

The minifilament assay separates cleanly into **anchor/readout scaffold** (filament-agnostic)
and **load source** (minifilament-specific). The node assay reuses the former and swaps the
latter.

### Reuses UNCHANGED (the entire scaffold)
- **Two anti-parallel pinned filaments** via `FilSegment.makeStraightChain` —
  `makeContractilityAssay` `BoxOfActin.java:3273-3288`. Polarity-aware, verbatim.
- **Pin registry + `applyBenchmarkPins()`** (`BoxOfActin.java:132-138`, `2202-2229`) —
  generic positional snap-back, already calls `GPUMoveThing.markPoseDirty` on `-gpu`. Reuse
  for the filament pins verbatim.
- **Tension readout `captureContractilityTension()`** (`BoxOfActin.java:3313-3325`) — reads
  `anchorSeg.getForceSum*()` and projects onto the inward `buildDir`; **indifferent to what
  applied the force**. A node-carried myosin pulling the filament registers identically.
  `addDeviceJointForce` (device chain-reaction fold) reused too.
- **Stats / JSON `contractility` block / HUD / stdout reporter**
  (`accumulateContractilityStats` `:3345`, `ThreeJSWriter.java:260-279`) — all read the
  `ContractAssay` struct, not the minifilament.
- **`applyContractilityDefaults()`** (box geom, dt=1e-5, turnover off) — generic.
- **Mode dispatch + population suppression** (`begin()` `:278-286`, IC short-circuit
  `:3121-3124`) — generic pattern.

### Needs a node analog (the load source — small, localized)
1. **The load source construction.** Replace `contract.mini = new MyoMiniFilament(center,
   axis)` (`:3293`) with protein node(s) carrying myosins bridging the two anti-parallel
   filaments. `ContractAssay.mini` is typed `MyoMiniFilament` (`:146`) → add a node field or
   a small `ContractileLoad` interface.
2. **`countBoundMotors()`.** The only minifilament-specific call in the stats path
   (`accumulateContractilityStats` `:3347` → `MyoMiniFilament.countBoundMotors`, iterates
   `myoDimersEnd1/End2` checking `onFil`). Node analog: count bound heads across the node's
   `myosins[]`/`myodimers[]` whose `myoMotor.onFil`. Cleanest: a `countBoundMotors()`
   interface implemented by both.
3. **`hasMotor`/`mini!=null` checks** (`ThreeJSWriter.java:277`, `contractNoMotor` path
   `:3292`) — re-point to the node field.
4. **Overlap-vs-span warning** (`:3296-3306`) uses `MyoMiniFilament.length` — replace with
   the node assembly's reach.

### The minimal node-contractile IC (the one real design question)
A bipolar minifilament spans **axially** and naturally bridges two anti-parallel filaments
offset in Y. A node is a **sphere with radial myosins** — its reach is `nodeRadius` + myosin
length. The minimal IC: one (or two) node(s) centered in the overlap, sized/placed so
surface myosins can **capture and power-stroke on both** anti-parallel filaments (each at
±`yOff`, default 0.05 µm apart). The motor machinery is identical (full `Myosin`/`MyoMotor`
nucleotide cycle, same binding capture `myoColTol`, same cross-bridge), so **binding + power
stroke transfer unchanged**; the only new variable is **geometric reach** — node radius vs
filament Y-offset. Mitigations if a single small node can't reach both: raise `nodeRadius`,
use longer-reach myosins, use two nodes, or narrow `yOff`. This is a tuning question, not a
machinery gap.

**Assessment: the assay is a clean reuse.** Scaffold ~95% reused; the new code is one IC
builder branch + a `countBoundMotors()` interface + 3 trivial type re-points.

---

## 4. Gap analysis — GPU residency port

The minifilament port is the playbook. A node is the same *shape* of problem — **a body
Thing carrying N attached myosins/dimers, with action forces on the attachments and a
reaction (force ± torque) gathered back onto the body** — so the `RULE_MINIFIL` arc applies
directly. But the node has **three concrete differences**, two benign and one that adds real
kernel work.

### The minifilament playbook (baseline)
1. **Phase A** — body integration → device. New `RULE_MINIFIL` in `classifyThings` (before
   same-supertype checks) + `ruleForGpuThing`; Brownian scale in `packRange`; shared
   `moveThingKernel`. Computation stays CPU.
2. **Phase B** — `dimerCohesionKernel`: a **per-element-from-packed-flags** kernel, **one
   thread per body**, each thread owning its dimers' disjoint child slots (RMW race-free) and
   **single writer of the body slot**. All state-dependence reduced to flags packed at
   pack-time (`parallel`/`bothOnFil`/`end` bits, axial offset). CPU computation gated off by
   a predicate matching the pack condition exactly (`MyosinDimer.cohesionOnDevice()`).
3. **Body reaction** — the central pitfall. A *separate per-body gather kernel would not run
   in the chained graph* (Nth-writer scheduling limit on `jointForceSum`). Resolved by
   folding the reaction into the per-body thread (single writer) **with a 1-step lag held in
   a device-resident buffer (`cohBodyReact`, FIRST_EXECUTION), SET not RMW**. The lag is a
   **stiff-stability (semi-implicit) measure** for the body↔N-children collective coupling,
   needed in float64 too — not a float32 patch.
4. Pitfalls: `unitVec` argument-order/sign, `fastAcosDev` (replicate CPU's `fastAcos`
   bit-for-bit), explicit 64-thread WorkerGrid (else 701 register overrun),
   `-Dtornado.tvm.maxbytecodesize=16384`, `reconcilePackRule` (new RULE_ type reopens the
   packRange cast/desync class), pre-existing float32 binding seam (~+22% bindEvents — don't
   misattribute).

### Does the node fit the playbook? — Yes, with differences

**Phase A (node integration → device) — directly applicable, slightly more involved.**
Add `RULE_NODE`, classify before nothing else (ProteinNode is its own type), wire Brownian
(`nodeBrownianMotionOff`/scale), reuse `moveThingKernel`. **New vs minifilament**: the node's
`moveThing` has **per-axis movement restriction** (`xMove/yMove/zMove`/`bYMove`) that the
shared kernel doesn't honor. For the **assay** this is sidestepped — pin nodes (if pinned at
all) via host `applyBenchmarkPins` after the device execute (generalize `Pin` to accept a
node, snap `coord`, `markPoseDirty`), exactly as filaments are pinned. For **production
ring** configs with axis-pinned cortex clusters, the kernel needs per-axis masks packed per
slot (small addition) — flag, but not a blocker.

**Phase B (surface tethers → device) — applicable; one extra cost.**
Both `keepMyosinsOnSurface` and `keepMyosinDimersOnSurface` are **per-element from packed
constants** — same pattern as cohesion. Inputs: the body-frame attach offset
(`myoPtsInx[i]`, a **constant 3-vector** per myosin), the resident node pose, the resident
`myoRod.end1` (rods are already GPU-handled in these configs), and the drags. The force laws
are pure geometry with **no runtime branch** (`attnForce=0.4` constant; singlet `/numNodeMyos`;
dimer `·myoDimerFracMove`). **Map one thread per node**, looping both `myosins[]` and
`myodimers[]`, RMW-ing each myosin's unique rod slot, and writing the node slot once.
- **THE difference vs minifilament**: the attach point needs a **full body→world rotation**
  (`xToX`, reconstruct rotation from resident uVec + yVec, zVec = cross) — the minifilament
  got away with an axial `coord + offsetX·uVec`. This is more kernel arithmetic and one extra
  resident buffer read (yVec), but it is **fully expressible** (the per-myosin body offset is
  a packed constant; the rotation is the same `transxToX` math already used elsewhere). **Not
  a blocker — just more work.**
- Pack a per-node CSR (`bodyMyoStart/Count`, `bodyDimStart/Count`) + flat offset records.

**Does the node hit the same body-reaction-gather problem? — YES, and it's solvable the same
way.** Both tethers push reaction onto the node (singlets: pure force, `:388`; dimers: force
**+ torque**, `:465`). Summing N reactions into the node slot is exactly the gather the
minifilament's "second kernel" couldn't do. **Apply the proven fix**: single-writer-per-node
(the per-node thread writes the node slot once) + **1-step-lagged device-resident reaction
buffer, SET not RMW**. The stiff-stability argument carries over (node ↔ N tethers is the
same collective coupling). **This is the highest-value transfer from the minifilament port —
adopt the lag from the start rather than rediscovering the instability.**

**Structural blockers: none.** Everything the node tether reads is resident or a packed
constant; every force reduces to branch-free geometry; the reaction gather has a known
solution. The node port is **strictly within the playbook**, plus full-frame attach math and
(for production) per-axis pin masks.

### What the node faces that the minifilament did NOT
1. **Full orientation frame in the tether kernel** (sphere surface, not axial offset) — extra
   arithmetic + yVec read. *Moderate.*
2. **Per-axis movement restriction** (`xMove/yMove/zMove`/`bYMove`) — sidestepped in the
   assay (host pin); needs packed masks for axis-pinned production clusters. *Low.*
3. **Node birth/death + formin nucleation** (`nodeLifetime`, `kNodeNuc`) — topology events
   coupling node↔FilSegment creation. **Off in the assay** (turnover zeroed). In production
   rings these are real couplings handled by the delta-set scatter (like FilSegment
   create/remove) — *defer to a production phase, flag now.*
4. **Two attachment populations** (singlets + dimers) vs the minifilament's one (dimers) —
   handle both in the one per-node thread (still single writer). *Low.*

### What the node does NOT face that the minifilament did
- ProteinNode is its own type → no `packRange` MyoMiniFilament→FilSegment cast confound at
  the *node* level (still run `reconcilePackRule` for the new RULE_NODE).
- The node's `moveThing` is already a standard free-body integrator — no bespoke body-drag
  formula needed beyond the existing Stokes-sphere `calculateProperties`.

---

## 5. Recommended staging, effort, risk

**Order: node contractile assay (CPU-first) → GPU port validated against that CPU oracle.**
This mirrors the minifilament arc (CPU cohesion was the oracle for `dimerCohesionKernel`) and
gives the device port a bit-checkable reference.

### Stage 1 — Node contractile assay, CPU (the oracle)
Reuse the `makeContractilityAssay` scaffold; swap the minifilament for node(s) carrying
myosins; add `countBoundMotors()` interface; tune node radius/placement so myosins reach &
power-stroke on both anti-parallel filaments.
- **Effort: low–moderate.** ~95% scaffold reuse; new = one IC branch + interface + 3
  re-points.
- **Risk: geometric feasibility** (can node myosins capture both fils). *Mitigatable* via
  nodeRadius / two nodes / narrower `yOff`. Validate: nonzero `boundHeads`, positive
  symmetric tension, inward anchor motion.
- **Deliverable**: `-contractilityNode` (or `contractilityNodeAssay:true`) + a param file
  analogous to `singleNode_myosins` + the contractility fixtures.

### Stage 2 — GPU residency, two sub-phases (validate each against Stage-1 CPU oracle)
- **2A — node integration → device (`RULE_NODE`).** Classify + Brownian + shared
  `moveThingKernel`; pin nodes (if any) via generalized host `applyBenchmarkPins`. *Effort
  low–moderate; risk low* (well-trodden RULE_MINIFIL mirror). Validate pose/jitter/COM vs CPU.
- **2B — surface tethers → device (per-node kernel + lagged reaction).** Port
  `keepMyosin(Dimer)sOnSurface` as a one-thread-per-node, packed-constant kernel with the
  **full-frame attach transform** and the **1-step-lagged device-resident node-reaction
  buffer (SET)**. Gate CPU tethers off with a pack-matching predicate; A/B control.
  *Effort moderate; risk moderate.* Risk items, all with known mitigations from the
  minifilament port: full-frame `xToX` replication, `unitVec` sign, `fastAcosDev`, 64-thread
  WorkerGrid, bytecode size, single-writer reaction + lag. Validate tension/boundHeads
  behavior-neutral vs Stage-1 oracle (tolerate the documented float32 binding seam).

### Flagged blockers / watch-items
- **No structural blocker** to either stage. The node tether is expressible as a per-element
  kernel; the reaction gather has the proven single-writer + lag fix.
- **Biggest single risk is Stage-1 geometry**, not the GPU port — a sphere-of-radial-myosins
  is a less natural bridge than a bipolar rod. De-risk it first (Stage 1) before any kernel.
- **Adopt the 1-step lag from the start** in 2B — the minifilament proved it's a
  stiff-stability necessity, not a float32 patch; rediscovering it costs a debugging cycle.
- **Per-axis pinning + node birth/death + formin nucleation** are production-ring concerns
  deferred out of the assay (turnover off, host pins). Flag for a later production-residency
  phase; they are couplings the minifilament never had.

---

## TL;DR
The protein-node path today is CPU-only (host-OOP cpuFallback), exercised by one fixture
(`singleNode_myosins`), with no contractile-assay or ring builder. **The node contractile
assay is a clean reuse** of the minifilament assay scaffold (pins, tension readout, stats,
JSON, defaults all transfer unchanged) — the only new code is the node load-source IC and a
`countBoundMotors()` interface, with geometric reach the one design question. **The GPU port
fits the `RULE_MINIFIL` playbook** (Phase-A body integration + per-element tether kernel +
single-writer 1-step-lagged reaction), with one genuinely new cost — the surface attach point
needs a **full orientation-frame transform** where the minifilament got away with an axial
offset — and production-only deferrals (per-axis pins, node birth/death, formin nucleation).
**No structural blockers. Recommended first stage: the CPU node contractile assay**, as both
the science deliverable and the oracle for the GPU port.
