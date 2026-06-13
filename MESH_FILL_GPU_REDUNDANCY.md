# Is the host-side Mesh fill redundant on the `-gpu` path?

**Date:** 2026-06-12 · **HEAD:** `d582bb3` (`main`, post-gridAssemble merge) · survey only,
no source edits · scope: the contractile `-gpu` step (`boa10-64Seg-dyn`-class, the cost-map
workload) plus the gliding rotation.

## Verdict — NOT redundant on `-gpu`

The host `FILSEG_MESH` fill is **still rebuilt every collision-cadence step on `-gpu`**
and is **consumed by an active GPU-path consumer**: `FilSegment.filSegMeshCollisions`
(the `meshColl` wave) walks the mesh every step and calls `checkToLink` — the
**crosslinker (FilLink) broad-phase** — whenever `Env.xLinks.isActive()`, which is **true**
for the cost-map config `boa10-64Seg-dyn` (`sideBonds:true:0.0`) and true by Java default.

The device CSR grid (`segBbox → gridAssemble → bindKernelResident`) did **not** make this
redundant: it replaced the **motor-binding** grid (`MotorBindGrid3D`), a *different grid
serving a different query*. The host `FILSEG_MESH` serves fil↔fil crosslinker broad-phase;
the device grid serves motor↔fil closest-point binding. **Coverage gap, not redundancy.**

This is a **discovery-bail**: an active `-gpu` consumer was found, so the trace stops at
reporting it (per the prompt). `checkToLink → FilLink.makeLink` is flagged as a CPU-only
spatial query with no device equivalent — a *migration candidate* (a device fil-fil
broad-phase grid), **not migrated here**.

A genuine but separate nuance (see §6): in the profiled `boa10-64Seg-dyn` runs the
broad-phase appears to form **zero links** (`XLink Threads` force wave `OFF`,
`addLinkForcesFireCt=0`), so the walk does effectively-inert work *in that config*. That is
a crosslinker-broad-phase optimization question (gate the walk when no links can form), not
a "device grid replaced the host grid" redundancy. The fill is **not code-redundant** and
gating it is **not physics-neutral in general**.

---

## 1. The host per-step spatial fills

Two distinct host spatial structures exist; only one is the subject here.

| Structure | Built by | Bins | Cell geometry |
|---|---|---|---|
| **`Mesh` 2D bin grid** (`FILSEG_MESH`, `NODE_MESH`, `MYOHEADS_MESH`) | `Mesh.MeshThreads` (`Mesh.java:135-155`) in waves `meshFilsStart`/`meshNodesStart`/`meshMotorsStart` | `FILSEG_MESH`: FilSegment segments (Bresenham line-raster of each seg's end1→end2 into 2D cells); `NODE_MESH`: ProteinNodes; `MYOHEADS_MESH`: MyoMotor `bindTip` | 2D (X,Y) bins, `BIN_DEPTH`-capped per cell |
| **`MotorBindGrid3D`** (`BIN_DEPTH=1000`) | `MotorBindGrid3D.FillThreads` (`Mesh.java:196-232` dispatch; build in `MotorBindGrid3D.java`) in wave `motorBindGrid3DStart` | FilSegment endpoints + MyoMotor `bindTip`, true 3D cubic grid, `CELL_SIZE=0.2 µm` | 3D cubes |

The `Mesh` fills are dispatched in `BoxOfActin.doLoop()` at **lines 1189-1197**, gated only by
the collision cadence (`collisionCkCounter >= Thing.collisionCheckInt`, default every step) —
**not** by `Env.useGPU`. `meshThreads` is `tSetActive[8]=true` (always), with per-entity
internal count-gating (`filSegmentCt`/`nodeCt`/`motorCt == 0 → early return`).

## 2. `-gpu` gating status

| Fill | Runs on `-gpu`? | Evidence |
|---|---|---|
| `MotorBindGrid3D.FillThreads` | **NO — already gated off** | `BoxOfActin.java:1213` `if (!Env.useGPU) { startAllThreadSets(motorBindGrid3DStart); … }`. Phase 3 (2026-06-04) moved the bind-grid build to device (`segBbox → gridAssemble`). The CPU build dispatches **only on the CPU path** (still wired ahead of the CPU 27-neighbour query `motCollStart`, itself in the `else` branch at 1242). |
| `Mesh.FILSEG_MESH` fill (`meshFilsStart`) | **YES — every step, ungated** | `BoxOfActin.java:1189-1190`, inside the cadence block only. A `DIAG_MESH_FILL_FILSEG_CT++` counter at `Mesh.java:140` is incremented *specifically when `Env.useGPU`*, confirming the fill is known to run on `-gpu`. |
| `Mesh.NODE_MESH` fill (`meshNodesStart`) | YES if `nodeCt>0` | `BoxOfActin.java:1191-1192`; internally `nodeCt==0 → return`. |
| `Mesh.MYOHEADS_MESH` fill (`meshMotorsStart`) | YES if `motorCt>0` | `BoxOfActin.java:1193-1194`; contractile has minifilament heads → filled every step. |
| `meshColl` wave (`CkMeshThreads`, consumes the mesh) | **YES — `tSetActive[9]=true` unconditionally** | `BoxOfActin.java:1196-1197` + `BoxOfActin.java:1022` (hard `true`, with comment "filament-filament collisions"). Runs `filSegMeshCollisions` + `membraneFilMeshCollisions` every step on `-gpu`. |

## 3. Consumers of the host `Mesh` — ACTIVE / DEAD on `-gpu`

| Consumer | Reads | Dispatched on `-gpu`? | Active query? | Verdict on `-gpu` |
|---|---|---|---|---|
| **`FilSegment.filSegMeshCollisions`** (`FilSegment.java:2048`) → `checkToLink` → `FilLink.makeLink` | `FILSEG_MESH` | **YES** (meshColl wave, `tSetActive[9]` hard-true) | gated `Env.xLinks.isActive()` (`FilSegment.java:2061`) — **TRUE** for `boa10-64Seg-dyn` (`sideBonds:true:0.0`) and true by default | **ACTIVE** — crosslinker broad-phase walks the mesh every step (see §6 caveat: forms 0 links in the profiled config, but the walk/read executes) |
| `FilSegment.membraneFilMeshCollisions` (`FilSegment.java:2069`) | `NODE_MESH` + `FILSEG_MESH` | YES (meshColl wave) | only acts on `StickyNode`; needs `nodeCt>0` membrane nodes | **DEAD** in contractile/gliding (no StickyNodes) |
| `ProteinNode.nodeMeshCollisions` (`Mesh.java:189`) | `NODE_MESH` | conditionally | gated `Env.collideProteinNodes.isActive()` (default off) | **DEAD** |
| `MyoMotor.motorFilMeshCollisions` + `MyoMotor.meshAllMotors` (`MyoMotor.java:364,372,390`) | `MYOHEADS_MESH` + `FILSEG_MESH` | **NO** | **zero call sites** anywhere in the tree (only declarations) | **DEAD on ALL paths** — `MYOHEADS_MESH` is a write-only structure (legacy 2D motor-fil collision, replaced by `MotorBindGrid3D` per `MotorBindGrid3D.java:6`) |

Note also: the device binding path (`GPUMotorBinding.detectBindings`) does **not** read the
host `Mesh` per-step. Its `MotorBindGrid3D` references are config constants
(`CELL_SIZE`/`BIN_DEPTH`/dims at `GPUMoveThing.java:1345-1370`) and the diagnostic CP1
parity replay (`GPUMotorBinding.java:1648-1772`), not the per-step host grid.

## 4. Device-vs-host coverage comparison

| | Host `FILSEG_MESH` | Device CSR grid (`gridCellOffsets`/`gridCellContents`) |
|---|---|---|
| Entities binned | FilSegment segments (+ separately: nodes, motor heads in sibling meshes) | **FilSegments only** (`segBbox` AABBs from device-resident `coord/uVec/length`) |
| Query served | **fil↔fil crosslinker broad-phase** (`checkToLink`: angle test + line-segment intersect → `FilLink.makeLink`) | **motor↔fil closest-point binding** (`bindKernelResident`: 27-cell neighbourhood, line closest-point → `boundSegId`) |
| Consumer | `filSegMeshCollisions` (CPU, every step) | `bindKernelResident` (device) |

**The device grid covers neither the same entity set nor the same query.** It replaced
`MotorBindGrid3D` (motor binding), which is the structure that *was* correctly gated off on
`-gpu`. The host `FILSEG_MESH` serves the crosslinker broad-phase — a query the device grid
has never implemented. **The host fill is therefore not made redundant by the device
gridAssemble.**

## 5. Genuine adjacent redundancies found (reported, not the verdict target)

1. **`MYOHEADS_MESH` fill is dead on every path.** `meshMotorsStart` fills it every step
   (motorCt>0 in contractile), but its only consumers (`MyoMotor.motorFilMeshCollisions`,
   `meshAllMotors`) have **zero call sites**. Pure write-only waste, removable
   *unconditionally* (independent of `-gpu`). Small win; flagged for a future cleanup.
2. **`MotorBindGrid3D` host build** — already gated off on `-gpu` (Phase 3). No action.
3. **`NODE_MESH` fill / `membraneFilMeshCollisions`** — inert in contractile & gliding (no
   StickyNodes); count-gated to a no-op.

## 6. Caveat — the active consumer does effectively-inert work in the profiled config

In the profiled `boa10-64Seg-dyn` runs (`RUN_LOGS/2026-06-11_701_hunt/`, `sideBonds:true:0.0`),
the startup phase-plan snapshot prints:

```
[20:06]   ON   Ck Mesh Threads
[20:06]   OFF  XLink Threads
[20:06] [phase-plan] 7 active, 10 inert (will skip dispatch)
…and (a K1 run):  addLinkForcesFireCt=0
```

`XLink Threads` (the `FilLink` *force* enforcement wave) is `OFF` because
`tSetActive[11] = FilLink.filLinkCt > 0` is evaluated once at setup (`BoxOfActin.java:1033`)
when `filLinkCt==0`; `addLinkForcesFireCt=0` confirms **no link forces ever fired**. So the
broad-phase (`Ck Mesh Threads`, ON) walks `FILSEG_MESH` and calls `checkToLink` every step
but apparently forms **zero links** in this geometry.

Interpretation: the fill+walk is *wasted work for this config's observed behavior*, but it
is **not code-redundant on `-gpu`** in the sense the question asked — the consumer executes
and reads the mesh, and the device grid cannot substitute for it. Gating it is **not
physics-neutral in general** (`checkToLink` forms links whenever `xLinks.isActive()` and the
geometry qualifies). Whether to gate the crosslinker broad-phase when no links can form is a
separate physics-semantics optimization, **out of scope for this redundancy survey**.

`Ck Mesh Threads` is a non-trivial cost in these logs (cumulative 2–6 s of wall over a run;
the per-step mesh-fill + walk slice), so the wasted-work question is worth a dedicated look
— but as a crosslinker-broad-phase gating decision, not as a host-grid-redundancy gate.

## 7. What a confirming test would need (were the verdict "redundant")

The verdict is **not redundant**, so no blanket `Env.gpu` skip is safe. For completeness, a
safe gate would be **consumer-activity-based, not `-gpu`-based**: skip the `FILSEG_MESH` fill
+ `meshColl` wave only when *all* its consumers are provably inert — i.e.
`!Env.xLinks.isActive() && !Env.collideProteinNodes.isActive() && (no StickyNodes)`. That
predicate would help **gliding** (where xLinks may be inert) and would help CPU too — but it
does **not** apply to the contractile cost-map config, where `xLinks.isActive()` is true.
Any such change must keep a regression guard that a crosslinked config (`sideBonds` active +
filaments within `crossLinkGrabDist`) still fills the mesh and forms links.

## Constraints honored

Survey only; no source edits (no throwaway timer added — the verdict is "not redundant", so
there is no gate to size). Discovery-bail respected: the active consumer
(`filSegMeshCollisions → checkToLink`) is reported and flagged as a migration candidate, not
migrated or modified. All line references are against HEAD `d582bb3`.
