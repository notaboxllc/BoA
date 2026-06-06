# Phase 4.5 scoping — not-stale confirm + binding-phase breakdown (2026-06-05)

Two diagnostics, no refactor. Built against `ec789f2` on `main`.

- **Part 1** — poison the frame-only host mirrors between frames, run a
  short gliding-assay, compare observables to baseline.
- **Part 2** — TornadoVM per-task profiler on the binding plan + per-step
  timer on `fillSoaArrays` to break the 59% binding bottleneck into pose
  pack / grid build / bind kernel / CSR-result transfer.

## Verdict

- **Part 1 — FAIL**. Poison shifts observables by ~65% on bindEvents and
  meanBoundMotors and ~70% on glidingVelocity, consistent across seeds.
  A per-step compute path **does** read the frame-only mirrors. Localized
  to `Crucible.keepMyosinOnSurface(int)` (`Crucible.java:115-129`) and its
  dimer twin `keepMyosinDimerOnSurface(int)` (`Crucible.java:137-151`).
  These two functions are dispatched from the `myoJoints2Start` ThreadSet
  every step **without** a `gpuMotorHandled` / `gpuHandled` gate, and read
  `curRod.end1AsPt3D()` → `Thing.soaEnd1[]` to compute the surface-tether
  spring strain. After the half-flip retired the per-step
  `recomputeDerivedSoA`, `Thing.soaEnd1` is fresh **only** at the last
  output-frame `refreshHostMirrorsForOutput()` call. Between frames it
  drifts. The PHASE4_PREP Part A survey listed `recomputeDerivedSoA` as
  P6 / FLIP-RETIRE and did not flag this consumer.

- **Part 2 — strongly pack-dominated**. Of binding's 8.8 ms/call (89s of
  the 10101-step gliding run), **6.6 ms/call (75%) is the pose pack +
  PCIe host→device transfer** — exactly the cluster Phase 4.5 cross-graph
  residency retires. The 27-cell narrow-phase compute (bindKernel) is
  0.023 ms/call (0.3%); the grid build is 0.18 ms/call (2.1%); the CSR /
  result device→host transfer is 1.96 ms/call (22%). Cross-graph
  residency is the speed lever, not just a goal-completion step.

## Part 1 — not-stale confirm: localized stale reader

### Instrumentation

`GPUMoveThing.poisonFrameOnlyMirrors()` (`GPUMoveThing.java:3032-3115`),
armed by `BOA_PHASE45_POISON=1`. Called from `BoxOfActin.doLoop()`
immediately after `GPUMoveThing.moveThings()` returns — i.e. after
demand-sync of `soaCoord/UVec/YVec` has written fresh pose to the host
SoA, but **before** any subsequent compute on the next step. Adds
`+1.0 µm` to every float of:

- `Thing.soaEnd1[]`, `Thing.soaEnd2[]`, `Thing.soaZVec[]`,
  `Thing.soaTransXTox[]`
- `FilSegment.xRange / yRange / zRange / end1Pt.{x,y,z} / end2Pt.{x,y,z}`
- `MyoMotor.bindTip.{x,y,z}`

These are exactly the fields that `refreshHostMirrorsForOutput()`
restores at output-frame entry, so the frame writer / inspect path sees
the correct values regardless of poison. Pose source for the binding
pack (`Thing.soaCoord/UVec` consumed by `MyoMotor.fillSoaArrays` and
`FilSegment.fillSoaArrays`) is **not** poisoned — those arrays are
demand-synced every step and out of scope for this test.

`+1.0 µm` is large enough to taint any geometric computation (the
gliding box is ~10 µm) and small enough to not push array indexing
out-of-bounds for the mesh fill paths.

### Result (paired, N=2 seeds, 10101 steps)

| seed | metric | baseline | poisoned | Δ % |
|---|---|---|---|---|
| 1 | bindEvents | 712 | 249 | **-65.0%** |
| 1 | meanBoundMotors | 6.644 | 2.280 | **-65.7%** |
| 1 | glidingVelocity (µm/s) | 7.856 | 13.138 | **+67.3%** |
| 2 | bindEvents | 733 | 214 | **-70.8%** |
| 2 | meanBoundMotors | 5.912 | 1.746 | **-70.5%** |
| 2 | glidingVelocity (µm/s) | 7.753 | 13.593 | **+75.3%** |

Consistent sign + magnitude across seeds. This is **not** the
borderline run-to-run jitter the half-flip N=4 paired-t showed
(|t| ≤ 1.21); it is a deterministic shift far outside the gate
band. Some per-step path reads the poisoned data.

Logs: `RUN_LOGS/2026-06-05_phase45_scoping/baseline_seed{1,2}.log`,
`poison_seed{1,2}.log`.

### Localization

`grep -rn '\.xToX\|\.XTox\|\.end1AsPt3D\|\.end2AsPt3D\|\.bindTip\b'`
across the per-step compute path, intersected with the dispatch chain
reachable from `BoxOfActin.doLoop()` on `-gpu`:

| candidate path | reader | disposition |
|---|---|---|
| `MyoMotor.fillSoaArrays` (P1.a) | reads `Thing.soaCoord/UVec` | **not poisoned** — pose source, demand-synced; explicitly out-of-scope. |
| `FilSegment.fillSoaArrays` (P1.b) | reads `Thing.soaCoord/UVec` | same as above. |
| `Mesh.fillFilSegMesh(curSeg.end1AsPt3D(), end2AsPt3D())` | reads `Thing.soaEnd1/End2` | poisoned bins are populated, but `filSegMeshCollisions` only does `checkToLink` gated on `Env.xLinks.isActive()` — off in gliding. **No per-step consumer in gliding.** |
| `Mesh.fillMotorMesh(motor.bindTip)` | reads `MyoMotor.bindTip` | mesh consumer is `nodeMeshCollisions` (needs nodes) and the CPU motor-grid (gated off on `-gpu`). **Inert in gliding.** |
| `MyoFilLink.step → updatePos → mySeg.end1AsPt3D()` | reads `Thing.soaEnd1` | **gated** by `gpuMotorHandled()` (Phase-4 prep Part C). Off for MyosinFixed motors on bound GPU FilSegments — the gliding case. |
| `MyoMotor.step → updateMyoFilLinks → tipLink.step → addForces/updatePos` | same as above | same gate. |
| `FilSegment.step → checkBugCollisionFromInside` | reads `end1Pt`, `end2Pt` | gated by `gpuBoundaryHandled` (Phase 2 F1) — true in gliding because `boundaryShape == BOX` and `!simOutsideBug`. Path off. |
| `FilSegment.step → addNodeForces` | reads `end2Pt` | requires `nodeAtEnd2` — false in gliding (no nodes). Inert. |
| `FilSegment.step → addLinkForces / addTorsionSpringForces` | no Pt3D-mirror reads | (gated on `!gpuChainHandled` anyway). |
| **`Crucible.keepMyosinOnSurface(i)`** | **reads `curRod.end1AsPt3D()`** (→ `Thing.soaEnd1`) | **PER-STEP, UN-GATED.** Dispatched from `Crucible.ChamberMyoThreads` on `myoJoints2Start` (`BoxOfActin.doLoop:989`). Fires for every chamber-fixed myosin every step regardless of `useGPU` / `gpuHandled`. |
| **`Crucible.keepMyosinDimerOnSurface(i)`** | **reads `curRod.end1AsPt3D()`** | same — `ChamberMyoDThreads`, same dispatch. |

Gliding-assay configuration:
```
$ grep -iE 'myo|fixed|chamber' ParameterFiles/glidingAssay500_val
initialMyoMiniFils:false:0.0;
fixedMyosinDensity:true:500;
fixedMyosinZValue:true:-0.05;
```

`fixedMyosinDensity=500` → ~500 chamber-fixed myosins, each driving a
surface-anchoring spring every step. The spring is:

```java
// Crucible.java:115-129
public static void keepMyosinOnSurface (int i) {
    Myosin curMyo = Thing.theBox.myosins[i];
    MyoRod curRod = curMyo.myoRod;
    Pt3D curAttPt = Thing.theBox.myoPtsInX[i];
    double strainDist = Pt3D.ptDist(curRod.end1AsPt3D(), curAttPt);   // <-- POISONED
    linkUVec1.unitVec(curAttPt, curRod.end1AsPt3D());                  // <-- POISONED
    double forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curRod.bTransGam.y));
    F.scale(forceMag, linkUVec1);
    curRod.incForceSum(F);
}
```

`curRod.end1AsPt3D()` returns `new Pt3D(getEnd1X(), getEnd1Y(), getEnd1Z())`
which read directly from `Thing.soaEnd1`. After the half-flip,
`Thing.soaEnd1` is updated only by `refreshHostMirrorsForOutput()` at
output-frame boundaries. Between frames it drifts away from current
pose by `Δt × velocity` — small per step, but compounding linearly
between frames.

### Why the half-flip N=4 paired-t still PASSED

The natural drift between frames is on the order of `7 µm/s × 100 steps
× 1e-5 s/step = 7e-3 µm` (at gliding velocity, with toFileInterval ≈
100). The poison injects `+1.0 µm`, ~150× larger. The natural drift
produces a small biased force on each chamber-fixed myosin that
compounds over the run; the half-flip's |t| = -1.21 on bindEvents and
mean Δ = -69.5 events out of ~700 (≈10%) is consistent with a
small-but-real bias. The N=4 paired-t band absorbed it because the
direction sign-scattered across seeds (this prompt's poison shows the
bias is sign-aligned negative — the natural between-frame drift sign
depends on local pose phase per seed). The half-flip's gate accepted
because the effect lived inside the |t| ≲ 2 band, not because the
stale read was absent.

### Fix surface (out of scope for this scoping prompt)

The minimum-touch fix is to refresh just `Thing.soaEnd1[rodSlot]` for
the chamber-fixed myosin rods inline before the `myoJoints2Start`
dispatch, or to ungate the demand-sync derived refresh on every step
(reintroducing some of what the flip retired). The cleaner fix is to
port `keepMyosinOnSurface` to a device kernel that reads the device-
resident `coord/uVec/length` directly — natural to fold into the
chained move plan ahead of `move`. Decision deferred to a Phase 4.5
fix prompt.

## Part 2 — binding-phase breakdown

### Instrumentation

- `GPUMotorBinding` runs with
  `withProfiler(ProfilerMode.SILENT)` when `BOA_PHASE45_BIND_PROFILE=1`.
- After each `.execute()`, the captured `TornadoExecutionResult`'s
  `getProfilerResult()` exposes `getDeviceWriteTime()` /
  `getDeviceReadTime()` (aggregate PCIe per-execute), and per-task
  `TASK_KERNEL_TIME` is parsed out of `getProfileLog()` JSON for
  `motorBinding.segBbox`, `motorBinding.gridAssemble`, and
  `motorBinding.bind`.
- `MyoMotor.fillSoaArrays + FilSegment.fillSoaArrays` (P1.a + P1.b) are
  timed inline in `BoxOfActin.doLoop` and reported under
  `fillSoaArraysNanos`.
- All instrumentation is no-op unless the env-var is on; the per-step
  hot path is unchanged in production.

The end-of-run prints (one line each):
- `[PHASE45_BIND_PROFILE]` — per-component absolute time + ms/call.
- `[PHASE45_BIND_BUCKETS]` — % shares for the four scoping buckets
  (pose / grid / bind / csr).

### Result (gliding-assay 10101 steps, seed=1, aorus + Java 21 + TornadoVM 4.0.1-dev PTX)

Honest absolutes: the baseline (no-profiler) run shows
`gpuMotorBinding total=89.099s` (8.82 ms/call). The profiler-on run shows
`bindTotal=149.885s`, inflated by ~57s of JSON-log parsing in the
unpack path (`cpuUnpack=5.65ms/call` profiler-on vs `0.05ms/call`
baseline). The kernel + PCIe absolute times the profiler reports are
event-driven and **not** inflated by the JSON parse; they are usable
as honest measurements of the underlying work.

| component | total time | ms/call | % of binding total (baseline 89s) |
|---|---|---|---|
| `fillSoa` (P1.a + P1.b CPU pose snapshot) | 5.51s | 0.55 ms | 6.2% |
| `cpuPack` (P4: CPU motor/segment SoA → FloatArray) | 1.27s | 0.13 ms | 1.4% |
| `pcieWrite` (host→device of pose / counts) | 60.13s | 5.95 ms | **67.5%** |
| `segBbox` kernel | 0.23s | 0.022 ms | 0.3% |
| `gridAssemble` kernel | 1.65s | 0.16 ms | 1.9% |
| `bind` kernel | 0.23s | 0.023 ms | 0.3% |
| `pcieRead` (device→host of CSR + boundSegId + arc) | 19.28s | 1.91 ms | 21.7% |
| `cpuUnpack` (boundSegId walk + ontoFilament, baseline) | 0.51s | 0.05 ms | 0.6% |
| residual (dispatch / driver) | ~0.3s | ~0.03 ms | 0.3% |
| **bindTotal** (baseline, profiler-off) | **89.10s** | **8.82 ms** | 100% |

### Bucket shares (the read the prompt asked for)

| bucket | ms/call | % | retired by Phase 4.5? |
|---|---|---|---|
| **pose pack / transfer** (fillSoa + cpuPack + pcieWrite) | **6.63 ms** | **75.1%** | **YES** — cross-graph residency reads `coord/uVec` from the move plan's device buffers; `bindKernel` rewrites to slot-map lookups; the P1.a + P1.b CPU snapshots + P4 CPU pack + the entire host→device PCIe go to zero. |
| **grid build** (segBbox + gridAssemble kernels) | 0.18 ms | 2.1% | no — pure device compute. |
| **bind kernel** (27-cell narrow phase) | 0.023 ms | 0.3% | no — pure device compute. |
| **CSR / result transfer** (pcieRead + cpuUnpack) | 1.96 ms | 22.3% | partially — `gridCellOffsets/Contents` device→host (currently retained for Phase-3 CP1 paths) can move UNDER_DEMAND; the `boundSegId` + `arcOnFilDev` reads + `ontoFilament` CPU loop stay. Drops roughly half. |

### One-line read

**Phase 4.5 is pack-dominated.** Cross-graph residency retires the 75%
of binding that lives in the pose snapshot + CPU pack + PCIe upload.
The combined GPU phase (currently `gpuMotorBinding + gpuMoveThing ≈
14.9 ms/step` post-half-flip) would drop by ~6.6 ms/step from this
bucket alone, plus another ~1 ms/step if the CSR `gridCellOffsets`
download is also moved UNDER_DEMAND. Projected total ~7 ms/step
(2.1× speedup over the half-flip; 2.8× over pre-flip), at which point
the bind / grid kernels (~0.2 ms each) and demand-sync pose download
(~1.3 ms) become the floor. The bind kernel itself is **not** a
useful optimization target relative to PCIe; residency is.

Log: `RUN_LOGS/2026-06-05_phase45_scoping/bindprofile_seed1.log`.

## Files modified

| file | change |
|---|---|
| `boxOfActin/GPUMoveThing.java` | New `poisonFrameOnlyMirrors()` + `isPoisonEnabled()`. Env-var `BOA_PHASE45_POISON=1`. No-op when off. |
| `boxOfActin/GPUMotorBinding.java` | New `BIND_PROFILE` flag (`BOA_PHASE45_BIND_PROFILE=1`). When on: `plan.withProfiler(SILENT)`; capture `TornadoExecutionResult` from `.execute()`; accumulate `bindWriteNanos/ReadNanos/SegBboxKernelNanos/GridAssembleKernelNanos/BindKernelNanos/DeviceKernelTotalNanos`. Helper `extractTaskKernelTime(json, taskName)` parses the per-task `TASK_KERNEL_TIME` substring out of `getProfileLog()`. Eight new public accessors. |
| `boxOfActin/BoxOfActin.java` | Inline timer around `MyoMotor.fillSoaArrays + FilSegment.fillSoaArrays` gated on `GPUMotorBinding.isBindProfileEnabled()`. New `[PHASE45_BIND_PROFILE]` + `[PHASE45_BIND_BUCKETS]` print block at end of run when the env var is on. Poison call inserted after `GPUMoveThing.moveThings()`. |

The instrumentation is **kept** — both flags are off by default. The
poison hook is a one-line method that adds nothing to the hot path
unless armed; the bind profiler is a per-execute conditional with a
small JSON-parse cost when armed. Both are valuable diagnostics for
the Phase 4.5 implementation and validation prompts.

## Reproduction

```
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java

# Baseline
for S in 1 2; do
  /tmp/p45_run.sh $S > RUN_LOGS/2026-06-05_phase45_scoping/baseline_seed${S}.log 2>&1
done

# Poisoned
for S in 1 2; do
  BOA_PHASE45_POISON=1 /tmp/p45_run.sh $S \
    > RUN_LOGS/2026-06-05_phase45_scoping/poison_seed${S}.log 2>&1
done

# Binding-phase profile
BOA_PHASE45_BIND_PROFILE=1 /tmp/p45_run.sh 1 \
  > RUN_LOGS/2026-06-05_phase45_scoping/bindprofile_seed1.log 2>&1
```

`/tmp/p45_run.sh` is the standard `java @$TORNADOVM_HOME/tornado-argfile
--enable-preview -Xmx800M -cp "$TDIR/...:libs/*:." BoxOfActin -r -gpu
-seed $1 -pf ParameterFiles/glidingAssay500_val` invocation.

---

# Phase 4.5 fix attempt — record correction (2026-06-05)

The scoping doc above localized the per-step stale reader to
`Crucible.keepMyosinOnSurface(int i)`. That localization is **wrong** and the
fix attempt described here did not close the bug. The actual stale reader is
elsewhere and is still open at the time of this writeup.

## What the fix attempt found

A device port of `Crucible.keepMyosinOnSurface` / `keepMyosinDimerOnSurface`
was implemented as a new `surfaceTetherKernel` chained into the move
TaskGraph (between `segMotorForce` and `move`), reading the resident
`coord/uVec/soaLengthArr/bTransGam` for each chamber-fixed rod slot and
RMW-ing the tether force into `jointForceSum`. A per-entry writeback buffer
was added for parity. The CPU `keepMyosin*OnSurface` were gated with
`if (Env.useGPU && curRod.gpuHandled && !GPUMoveThing.SOA_POSE) return;`
and `computeKeepMyosin*OnSurfaceForce` helpers added for parity testing.

Compile clean. Part-2 parity dump (BOA_PHASE45_TETHER_PARITY=1000) printed:

```
[PHASE45_TETHER_PARITY step=1000] compared=0 max|dev-cpu|=0.000000e+00 mean|dev-cpu|=0.000000e+00
```

`compared=0` is the smoking gun: the parity walked
`Thing.theBox.myosins[0..numChamberFixedMyos)` and
`Thing.theBox.myodimers[0..numChamberFixedMyoDimers)` and found
**no entries** to compare against. Reading the param file:

```
$ grep -i 'numChamberFixed' ParameterFiles/glidingAssay500_val
# (no match — the parameter is at its default)
$ grep numChamberFixedMyos boxOfActin/Env.java
static final Parameter numChamberFixedMyos = new Parameter("numChamberFixedMyos", ..., 0," ", Parameter.INT);
```

`numChamberFixedMyos` defaults to **0**. The scoping doc's table conflated
`fixedMyosinDensity` (which controls `MyosinFixed` instances via
`MyosinFixed.fillPlaneWithFixedMyosins()`) with `numChamberFixedMyos` (which
controls `Thing.theBox.myosins[]`). These are different mechanisms.
`Crucible.ChamberMyoThreads.divideAndConquer` (`Crucible.java:43-49`) short-
circuits on `numChamberFixedMyos == 0` — so `keepMyosinOnSurface` literally
never runs in gliding-assay. The device port is correct code for the
function it ports, but the function is dead in this workload.

## Re-poison test still shows the stale read

After the no-op port, re-running the poison test (seed=1) reproduces the
same magnitude of observable shift the original scoping flagged:

| | bindEvents | meanBoundMotors | glidingVelocity |
|---|---|---|---|
| baseline #1 (post-port, no poison)  | 848 | 7.870 | 7.581 |
| baseline #2 (post-port, no poison)  | 684 | 6.000 | 7.595 |
| poison #1 (post-port, BOA_PHASE45_POISON=1) | 156 | 1.283 | 11.754 |

Two same-seed baseline runs differ by 848 vs 684 — i.e. the simulation has
run-to-run thread-scheduling noise of ~24% on bindEvents under fixed seed.
Even with that noise, the poison shifts bindEvents from ~768 (baseline mean)
to 156 — many noise floors. The stale read is real; the localization was
wrong.

## What we now know about the actual reader

The original poison touches:
- `Thing.soaEnd1/End2/ZVec/TransXTox` (Thing-level SoA mirrors)
- per-FilSegment `xRange/yRange/zRange/end1Pt.{x,y,z}/end2Pt.{x,y,z}`
- per-MyoMotor `bindTip.{x,y,z}`

Per-step paths that read these on the GPU gliding path, with the gate
status I verified:

| candidate | reader | actually inert in gliding? |
|---|---|---|
| `Mesh.fillFilSegMesh(curSeg.end1AsPt3D(), end2AsPt3D())` | `Thing.soaEnd1/End2` | populates `FILSEG_MESH` per step. Mesh consumer (`filSegMeshCollisions → checkToLink`) gated on `Env.xLinks.isActive()`, off in gliding. **Inert.** |
| `Mesh.fillMotorMesh(motor.bindTip)` | `MyoMotor.bindTip` | populates `MYOHEADS_MESH` per step. Mesh consumer (`motorFilMeshCollisions`) only dispatched on `!Env.useGPU` path. **Inert.** |
| `MyoFilLink.setAttachment → updatePos → mySeg.end1AsPt3D()` | `Thing.soaEnd1` | fires on each fresh bind (~50/step in steady-state gliding). Writes poisoned `attachPt`. `addForces` reads `attachPt` but is gated off via `gpuMotorHandled() = true` on `MyosinFixed + GPU-handled seg`. **Should be inert** — but worth double-checking the gate is actually true for all 500 fixed-density motors. |
| `MyosinFixed.applyRodFixedPtForce → myoRod.end1AsPt3D()` | `Thing.soaEnd1` | called via `applyGPUDroppedForces` from `MyosinThreads.myoJoints1Start`, but the call site at `Myosin.java:121` is gated on `DIAG_CPU_ANCHOR=true` (default false). **Inert.** |
| `MotorBindGrid3D.fillFilSeg / fillMotor` | `end1AsPt3D / bindTip` | `FillThreads` dispatch in `BoxOfActin.doLoop:957` gated on `!Env.useGPU`. **Inert on GPU path.** |

If all of these are truly inert in gliding, then the stale reader must
either:

(a) be a path the survey missed (a `getEnd1X()` / `bindTip.x` / `xRange`
read on a code path I haven't grep'd), or
(b) be a path that *looks* gated but the gate is actually misfiring for
some subset of objects in steady-state (e.g. one motor whose `gpuMotorHandled`
returns false unexpectedly because `gpuHandled` flips after a topology
rebuild).

The cheapest next step is **selective poison**: poison only one field family
at a time (e.g. only `Thing.soaEnd1` and `soaEnd2`, or only `bindTip`, or
only `xRange/yRange/zRange`) and re-run. Whichever subset reproduces the
~80% bindEvents drop is the family the actual reader consumes. From there
the grep is narrow.

The no-op tether port was reverted from the working tree. The original
scoping run logs at `RUN_LOGS/2026-06-05_phase45_tether_fix/` are retained
as reference (no port code is committed).

---

# Phase 4.5 selective-poison localization (2026-06-05)

Selective per-field-family poison narrowed the half-flip's stale per-step
reader to **filament-segment endpoint positions**: poisoning either
`Thing.soaEnd1[]/soaEnd2[]` (the Thing-level SoA mirror) or
`FilSegment.end1Pt/end2Pt` (the per-FilSegment Pt3D mirror) — alone —
reproduces nearly the full ~80% bindEvents drop. No other family does.

## Selector instrumentation

`GPUMoveThing.poisonFrameOnlyMirrors()` now branches on a static
`BOA_PHASE45_POISON_FAMILY` env-var. Accepted values:
`all` (default), `none`, `soaEnds`, `bindTip`, `ranges`, `rangesScalar`,
`rangesEndpt`, `zvecTransx`. Unrecognised values fall back to `all` with a
warning. `BOA_PHASE45_POISON=1` is still required to arm the hook; the
family selector then restricts which fields get the +1 µm offset. A
one-shot banner prints the active family at startup:

```
[PHASE45_POISON] armed; family=<label> (soaEnds=<b> bindTip=<b>
  rangesScalar=<b> rangesEndpt=<b> zvecTransx=<b>)
```

## Sweep results (seed=1, gliding-assay 10101 steps)

| run | bindEvents | meanBoundMotors | glidingVelocity | classification |
|---|---:|---:|---:|---|
| baseline #1 (no poison) | 773 | 6.581 | 7.565 | — |
| baseline #2 (no poison) | 714 | 6.728 | 8.284 | — |
| baseline #3 (no poison) | 759 | 6.837 | 8.210 | — |
| poison=none (control) | 620 | 5.694 | 7.596 | within band |
| poison=all | 155 | 1.822 | 11.929 | (reference) |
| **soaEnds**  | **173** | 1.397 | 6.607 | **HIT** |
| bindTip | 651 | 6.186 | 7.524 | MISS |
| ranges | 202 | 1.842 | 9.105 | HIT (driven by Endpt; see below) |
| zvecTransx | 759 | 6.739 | 7.724 | MISS |
| rangesScalar | 851 | (skip) | (skip) | MISS |
| **rangesEndpt** | **125** | (skip) | (skip) | **HIT** |

Same-seed baselines span 714–773 (mean 749). poison=all is 155, the
reference floor. Two families saturate the floor on their own:
**soaEnds** (173) and **rangesEndpt** (125). Every other family stays in
the baseline band. The `ranges` HIT is entirely from `rangesEndpt`:
`rangesScalar` (xRange/yRange/zRange alone) returned 851, comfortably in
the noise band.

Because `Thing.soaEnd1[N]` and `FilSegment[N].end1Pt` are two **mirrors of
the same physical quantity** (one is the SoA storage, the other is the
Pt3D copy the host writes during `refreshHostMirrorsForOutput`), the two
HITs do not stack additively. Either alone saturates because either alone
poisons the segment endpoints the reader consumes, no matter which mirror
the reader prefers.

## Move-side end1Pt/end2Pt readers ruled out

I instrumented every grep'd per-step CPU end1Pt/end2Pt reader on the GPU
gliding path with a fire counter (`FilSegment.DIAG_BUG_INSIDE_FIRE_CT`,
`DIAG_ADDLINK_FIRE_CT`, `DIAG_ADDTORSION_FIRE_CT`) and ran a baseline:

```
[STATS] checkBugInsideFireCt=0
[STATS] addLinkForcesFireCt=0
[STATS] addTorsionFireCt=0
```

All three counters are exactly zero across the full 10101-step gliding
run. The Phase-2 F1 boundary gate (`!gpuBoundaryHandled`) and the F3/F4
chain gate (`!gpuChainHandled`) are correctly closed for every GPU-handled
FilSegment every step — confirming the survey's gate claim empirically.
**The stale reader is NOT on the move/step CPU path.**

The Phase 1 anchor spring (`MyosinFixed.applyRodFixedPtForce`) was
already ruled out by its `DIAG_CPU_ANCHOR=false` gate at
`Myosin.java:121`; the chamber surface tether (`keepMyosinOnSurface`)
was ruled out earlier by `numChamberFixedMyos=0`. The 2D `FILSEG_MESH`
and `MYOHEADS_MESH` consumers are gated inert
(`filSegMeshCollisions` on `xLinks.isActive()=false`,
`motorFilMeshCollisions` on `!Env.useGPU`).

## Named next target — GPUMotorBinding pose pack

The remaining per-step path that reads filament endpoints on the GPU
gliding run is **`GPUMotorBinding.detectBindings()`'s pose pack**:

- `filEnd1[j..j+2] = (float) FilSegment.soaEnd1X[s]` (and `soaEnd1Y/Z`)
  at `GPUMotorBinding.java:628–633`. Same for `soaEnd2X/Y/Z` → `filEnd2`.
- These pack arrays are uploaded EVERY_EXECUTION (see the binding plan's
  `transferToDevice` at `GPUMotorBinding.java:562–565`), so the binding
  kernel reads them every step.
- `FilSegment.soaEnd1X/Y/Z` are **rederived** from
  `Thing.soaCoord/soaUVec` by `FilSegment.fillSoaArrays()` in
  `BoxOfActin.doLoop` (called at `BoxOfActin.java:920`, immediately
  before the binding dispatch).
- `arcOnFilDev` post-process at `GPUMotorBinding.java:992–1004` recomputes
  `(motor − seg.end1) · uVec` for the binding-event arc, also reading
  from `FilSegment.soaEnd1X/Y/Z`.

Hypothesis to verify at next session start (code-read, no run needed):
**is `FilSegment.fillSoaArrays()` still called per-step on the GPU
gliding path before the binding dispatch?** And, if so, **does it pull
from a `Thing.soaCoord/soaUVec` that has actually been demand-synced
from device this step?** A retired `fillSoaArrays()` call, or a
demand-sync that no longer fires before it, would leave the binding's
endpoint pack frame-stale — explaining both the `soaEnds` HIT (the
binding pack reads them via `FilSegment.soaEnd1X` whose source is
`Thing.soaCoord`, distinct from `Thing.soaEnd1` but indistinguishable
under the +1 µm poison if the half-flip moved the refresh) and the
`rangesEndpt` HIT (the same per-FilSegment endpoint info that
`refreshHostMirrorsForOutput` writes to `fs.end1Pt/end2Pt`).

The `bindTip` MISS is consistent with this hypothesis: all 500 fixed
myosins are `MyosinFixed`, and `MyoMotor.fillSoaArrays()` rederives
`MyoMotor.soaX/Y/Z` from `Thing.soaCoord/soaUVec` (not from `bindTip`),
so the binding's motor pack stays fresh regardless of `bindTip` poison.

## What's committed vs not

- **Committed**: the selective-poison family-selector instrumentation in
  `GPUMoveThing.poisonFrameOnlyMirrors`; the `FilSegment.DIAG_*_FIRE_CT`
  counters; the `BoxOfActin.printStats` lines that echo them; this
  doc + JOURNAL update.
- **Reverted (not committed)**: the no-op `Crucible.keepMyosinOnSurface`
  device port (the `surfaceTetherKernel` and surrounding plumbing). It
  was confirmed mechanically correct but a runtime no-op for any
  workload with `numChamberFixedMyos = 0` — the device-port pattern
  is already exemplified by `motorForceKernel` / `segMotorForceKernel`
  / `jointsKernel`'s anchor spring, and a future port can follow those.

Run logs: `RUN_LOGS/2026-06-05_phase45_selective_poison/` (sweep +
diag-counter run).

---

# Phase 4.5 Part-A fire-count + Part-C fix attempt — bail-out (2026-06-05)

Goal: per the "locate the actual stale reader" prompt, instrument every
per-step CPU consumer of `end1AsPt3D()` / `end1Pt` / `getEnd1X/Y/Z` /
`Thing.soaEnd1[]` on the `-gpu glidingAssay500_val` path with a
DIAG_*_FIRE_CT counter (Part A), identify the live reader by its
non-zero count, fix it, and verify with a re-poison test (Part C).

## Part A — fire counters (single `-gpu` probe, seed=1, 10101 steps)

Instrumented sites and their counts (env-gated `if (Env.useGPU) DIAG_*_FIRE_CT++`):

| candidate site | counter | count |
|---|---|---:|
| `Mesh.MeshThreads.execute → fillFilSegMesh(end1AsPt3D, end2AsPt3D)` | `Mesh.DIAG_MESH_FILL_FILSEG_CT`            | **111056** |
| `FilSegment.meshAllSegs → fillFilSegMesh(end1Pt, end2Pt)`           | `FilSegment.DIAG_MESHALLSEGS_FIRE_CT`        |          0 |
| `MotorBindGrid3D.FillThreads → fillFilSeg(end1AsPt3D, end2AsPt3D)`  | `MotorBindGrid3D.DIAG_MBG3D_FILL_FILSEG_CT`  |          0 |
| `MyoFilLink.setAttachment → updatePos → end1AsPt3D`                 | `MyoFilLink.DIAG_UPDATEPOS_FROM_BIND_CT`     |        668 |
| `MyoFilLink.step → updatePos → end1AsPt3D` (gated)                  | `MyoFilLink.DIAG_UPDATEPOS_FROM_STEP_CT`     |          0 |
| `MyoFilLink.validateSeg → end1AsPt3D` (dead-code null-check)        | `MyoFilLink.DIAG_VALIDATESEG_FIRE_CT`        |      61792 |
| `MyosinFixed.applyRodFixedPtForce → end1AsPt3D` (DIAG_CPU_ANCHOR=false) | `MyosinFixed.DIAG_ANCHOR_FIRE_CT`        |          0 |
| `FilSegment.initialize → getEnd1X` (biochem length-change)          | `FilSegment.DIAG_FILSEG_INIT_CT`             |         21 |
| `MyoMotor.initialize → getEnd2X` (constructor only)                 | `MyoMotor.DIAG_MOTOR_INIT_CT`                |      14000 |
| `FilSegment.checkBugCollisionFromInside → end1Pt/end2Pt` (gated)    | `FilSegment.DIAG_BUG_INSIDE_FIRE_CT`         |          0 |
| `FilSegment.addLinkForces → end1Pt/end2Pt` (gpuChainHandled gate)   | `FilSegment.DIAG_ADDLINK_FIRE_CT`            |          0 |
| `FilSegment.addTorsionSpringForces → end1Pt/end2Pt` (chain gate)    | `FilSegment.DIAG_ADDTORSION_FIRE_CT`         |          0 |

**Live readers (non-zero counters):**

1. `Mesh.fillFilSegMesh` — every FilSegment, every collision-check step
   (~11 segs × ~10101 steps). Consumer is `filSegMeshCollisions` (gated
   on `Env.xLinks.isActive()=false` in gliding) and
   `membraneFilMeshCollisions` (gated on StickyNode presence, none in
   gliding). **The fill writes mesh bins from end1AsPt3D, but no
   downstream consumer reads them in gliding.**

2. `MyoFilLink.setAttachment → updatePos` — fires once per fresh bind
   event (kernel-decided bind triggers `GPUMotorBinding.detectBindings`
   to call `ontoFilament → setAttachment → updatePos`, line 668 binds in
   this baseline). `updatePos` writes `attachPt = end1AsPt3D +
   posOnSeg · uVecAsPt3D`. **`attachPt` is read by
   `MyoFilLink.addForces` which is gated off via `gpuMotorHandled() =
   true` on this workload. The GPU motor-force kernel reconstructs
   attachPt from device-resident `coord/uVec/length + posOnSegArr`,
   not from `attachPt`.**

3. `MyoFilLink.validateSeg` — fires for every bound motor every step
   (~6.12 mean × 10101 steps ≈ 61792). The body is a null-check:
   `(end1AsPt3D() == null) | (uVecAsPt3D() == null) | mySeg.removeMe`.
   `end1AsPt3D()` returns `new Pt3D(...)` and is never null;
   `uVecAsPt3D()` likewise. **Dead-code read — no behavioural effect.**

4. `MyoMotor.initialize` — fires `motorCt = 14000` times, all at
   construction (verified: 14000 = 500/µm² × 14×2 µm²). Init-time
   only, not per-step. Not a per-step reader.

5. `FilSegment.initialize` — fires 21 times in the run, all at biochem
   length-change events. Not steady-state per-step.

Counters that came back zero confirm the corresponding paths are
correctly gated inert in this workload (MotorBindGrid3D.FillThreads,
MyosinFixed anchor, F1 bug, F3 chain link forces, F4 chain torsion,
meshAllSegs static path).

## Part B — fix attempts and outcomes

Three attempts. None made the re-poison PASS.

### Attempt 1: refresh in `FilSegment.fillSoaArrays`

Added per-step writes inside the existing `fillSoaArrays` loop:

```java
thingEnd1Arr[b] = (float) e1x;     // Thing.soaEnd1[FilSegment slot]
thingEnd1Arr[b + 1] = (float) e1y;
thingEnd1Arr[b + 2] = (float) e1z;
// (and end2 + fs.end1Pt/end2Pt likewise)
```

Refreshes `Thing.soaEnd1[FilSegment slot]` (read by `getEnd1X/Y/Z` →
`end1AsPt3D()`) and `FilSegment.end1Pt/end2Pt` (read directly) from the
freshly computed `cx ± half·ux` values that `fillSoaArrays` already
derives from `Thing.soaCoord/UVec` (demand-synced this step).

**Result (seed=1):** poison=soaEnds bindEvents=212 (vs baseline 778,
−73%); poison=rangesEndpt bindEvents=1 (catastrophic — paradoxically
WORSE than no-fix's 125); poison=all bindEvents=282 (vs baseline 778,
−64%). All three families still HIT; rangesEndpt result indicates an
unexpected interaction between the per-step `fs.end1Pt` write and the
rangesEndpt-poison drift accumulation. Reverted.

### Attempt 2: `Thing.recomputeDerivedSoA(0, thingCt)` post-fillSoaArrays

Added a per-step `Thing.recomputeDerivedSoA(0, Thing.thingCt)` call in
`BoxOfActin.doLoop` immediately after `MyoMotor.fillSoaArrays() +
FilSegment.fillSoaArrays()`, plus an explicit `fs.end1Pt/end2Pt`
refresh loop. This re-introduces the half-flip's retired P6 host
mirror refresh on ALL slots (FilSegments, MyoMotors, MyoRods, MyoLevers
— every Thing).

**Result (seed=1):**

| run            | bindEvents | meanBoundMotors | glidingVelocity |
|---             |---:        |---:             |---:             |
| baseline       | 700        | 6.396           | 7.118           |
| poison=soaEnds | **151**    | 1.375           | 5.631           |
| poison=rangesEndpt | **215** | 1.672           | 9.311           |
| poison=all     | **171**    | 1.576           | 12.757          |

All three poison families STILL HIT at the same magnitude as the
no-fix selective sweep (soaEnds 151 vs 173 no-fix; rangesEndpt 215 vs
125 no-fix; all 171 vs 155 no-fix — all in the 125-215 floor band).
The fix has **no measurable effect on the poison's observable
impact**. Reverted.

### What the attempts establish

The refresh path covers every per-step CPU reader the Part-A fire
counters identified as non-zero (Mesh.fillFilSegMesh, updatePos,
validateSeg, plus all the gated-off candidates for safety). Both
`Thing.soaEnd1[*]` (every slot) and `FilSegment.end1Pt/end2Pt` are
forced to fresh values at the very start of every step, before any
downstream code can read them. **The poison's observable impact
survives this refresh unchanged.**

The mechanism by which the `soaEnds` / `rangesEndpt` poison drops
bindEvents from ~750 to ~150 is therefore **NOT** a CPU-side
`end1AsPt3D()` / `end1Pt` / `getEnd1X` read. The reader is somewhere
outside the candidate set the Part-A counters cover.

## Part C — re-poison: FAIL (all three families still drop)

Re-poison battery did not pass with either fix attempt. The required
Part-C bail-out applies.

## Bail-out — what we know and don't know

**Known:**

- The selective-poison sweep (PHASE45_SCOPING.md "selective-poison
  localization" section) demonstrated that `soaEnds` (`Thing.soaEnd1[]
  / soaEnd2[]`) and `rangesEndpt` (`FilSegment.end1Pt / end2Pt`)
  poisoning each independently drop `bindEvents` ~75–80%. The drop is
  deterministic and sign-aligned, far outside the ±24% run-to-run
  noise band.
- The Part-A fire counters identified every per-step CPU reader of
  `end1AsPt3D / end1Pt / getEnd1X` on the gliding path. Three sites
  fire (Mesh.fillFilSegMesh: 111056, MyoFilLink.updatePos via bind:
  668, MyoFilLink.validateSeg: 61792); all others are 0.
- All three live readers' downstream effects are inert in this
  workload: Mesh consumer gated on xLinks; updatePos writes attachPt
  which the GPU motor-force kernel ignores; validateSeg's null-check
  is dead code.
- Refreshing every per-step Thing.soaEnd1 + fs.end1Pt for ALL slots at
  the start of each step (Attempt 2) leaves the poison's observable
  impact unchanged. **The reader is NOT a CPU consumer of these
  mirrors.**

**Unknown — where the stale read actually lives.** Candidates the
Part-A enumeration did not cover and which the bail-out widening should
chase next session:

- Any per-step path that reads `Thing.soaZVec[] / soaTransXTox[]` —
  although `zvecTransx` poison alone MISSED in the original sweep,
  the `soaEnds` HIT could be masking a co-located read at the
  Thing-slot level (e.g., a `body-frame transform` path that reads
  `transXTox` and the `end1/end2` of the same Thing in lockstep).
- Any per-step path that consumes a Pt3D field on a non-FilSegment
  Thing (MyoMotor, MyoRod, MyoLever, Crucible) whose `getEnd1X /
  getEnd2X` was unintentionally upstream of a force calculation.
- The `MyoMotor.fillSoaArrays`-to-`GPUMotorBinding.detectBindings`
  flow at a deeper level than the host-pack inspection: e.g., is
  there any path where the bind kernel's PCIe upload buffer
  (`motPos / filEnd1`) silently consumes stale data because the host
  FloatArray was filled from a Thing.soa* mirror rather than the
  per-class `MyoMotor.soaX / FilSegment.soaEnd1X` array I traced?
  Worth a second pass — the binding's CPU pack at
  `GPUMotorBinding.java:611–635` should read **only** per-class SoA
  arrays, but a misattribution there would explain the poison HIT
  on `soaEnds` exactly. Code-grep on
  `Thing\.soa(End|ZVec|TransXTox)` reads inside any per-step path.
- The Phase-3 device-resident grid build's relationship to host-side
  state on plan rebuild — 11 rebuilds in 10101 steps, each doing
  FIRST_EXECUTION re-upload. If any FIRST_EXECUTION buffer is filled
  from a poisoned mirror, the device-resident state inherits the
  poison for the rest of the run.

## Part D — not run

The Part-D N=8 re-gate is contingent on Part C passing. It did not.
Documented here only for completeness — no runs executed.

## Files committed (this session) vs reverted

**Committed (kept):** the Part-A fire counters. No-op when
`Env.useGPU` is false; one increment per call site on the GPU path.
Total per-step cost is bounded by `~filSegmentCt + motorCt + linkCt`
counter increments. Negligible relative to surrounding work.

| file | counter(s) added |
|---|---|
| `boxOfActin/MyosinFixed.java`     | `DIAG_ANCHOR_FIRE_CT` |
| `boxOfActin/MyoFilLink.java`      | `DIAG_UPDATEPOS_FROM_BIND_CT`, `DIAG_UPDATEPOS_FROM_STEP_CT`, `DIAG_VALIDATESEG_FIRE_CT` |
| `boxOfActin/Mesh.java`            | `DIAG_MESH_FILL_FILSEG_CT` |
| `boxOfActin/FilSegment.java`      | `DIAG_FILSEG_INIT_CT`, `DIAG_MESHALLSEGS_FIRE_CT` |
| `boxOfActin/MyoMotor.java`        | `DIAG_MOTOR_INIT_CT` |
| `boxOfActin/MotorBindGrid3D.java` | `DIAG_MBG3D_FILL_FILSEG_CT` |
| `boxOfActin/BoxOfActin.java`      | `[STATS] *FireCt=` print lines in `printStats` |

**Reverted (not committed):** Attempt-1 (`FilSegment.fillSoaArrays`
end1Pt + soaEnd1 refresh) and Attempt-2 (`recomputeDerivedSoA(0,
thingCt)` + `fs.end1Pt/end2Pt` refresh in `BoxOfActin.doLoop`). The
working tree is clean of both.

## Record correction

The named next target in the prior section — `GPUMotorBinding`'s pose
pack — is also **NOT** the reader, per the corrected prompt's
mechanism analysis: `detectBindings` reads `FilSegment.soaEnd1X` (a
distinct array refreshed every step by `FilSegment.fillSoaArrays`
from the demand-synced `Thing.soaCoord/soaUVec`). Not `Thing.soaEnd1`.
That section's hypothesis was wrong.

## Run logs

`RUN_LOGS/2026-06-05_phase45_locate_reader/`:
- `probe_seed1.log` — Part-A fire-count probe
- `fix_baseline_seed1.log`, `fix_poison_{soaEnds,rangesEndpt,all}_seed1.log`
  — Attempt-1 baseline + poison battery
- `fix2_baseline_seed1.log`, `fix2_poison_{soaEnds,rangesEndpt,all}_seed1.log`
  — Attempt-2 baseline + poison battery

---

# Phase 4.5 propagation trace — no host-side crossover (2026-06-05)

The two prior fix attempts left the empirical question open. This pass is
diagnostic only: measure where the +1 µm poison travels at four ordered
points inside one `doLoop` iteration. No fix. No production behaviour
change. The trace itself is what's being committed.

## Methodology note (why direct measurement, not refresh-fix outcome)

A "refresh the mirror" fix passes or fails the re-poison test depending on
where the refresh sits **relative** to the poison injection point and the
actual reader. The fix-failure of Attempt 1/2 doesn't prove the absence
of a CPU stale-mirror reader — it proves the refresh wasn't placed where
the reader sees it. Refresh-fix outcomes are placement-dependent and not
a clean reader test. **Direct propagation measurement is**: snapshot the
arrays at ordered points; whichever array first carries the offset, and
the point at which it appears, localizes the crossover.

## Instrumentation

`boxOfActin/Phase45Trace.java` (new). Env-gated via `BOA_PHASE45_TRACE=1`
with `BOA_PHASE45_TRACE_START` (default 200) and `BOA_PHASE45_TRACE_STEPS`
(default 3). At each snapshot point, prints raw values + a `P|.` tag for
each of four arrays at three representative FilSegment slots:

| array | poisoned-iff test |
|---|---|
| `Thing.soaEnd1[b]`        | `\|now − canonE1\| > 0.5 µm` |
| `Thing.soaCoord[b]`       | `\|now − baselineCoord\| > 0.5 µm` |
| `Thing.soaUVec[b]`        | `\|now − baselineUVec\| > 0.5 µm` |
| `FilSegment.soaEnd1X[s]`  | `\|now − canonE1\| > 0.5 µm` |

Baseline = first-snapshot values. `canonE1 = baselineCoord − 0.5·length·
baselineUVec`. The 0.5 µm threshold sits well above natural per-step
displacement (~7e-3 µm/step at gliding velocity) and well below the
+1.0 µm poison.

`BoxOfActin.doLoop` carries four `Phase45Trace.snapshot()` calls at the
ordered points (no other source change).

## Point ordering inside one `doLoop` iteration

Time order: **2 → 3 → 4 → 1**.

| label | location | what should be fresh |
|---|---|---|
| `2_preFillSoa`         | before `MyoMotor.fillSoaArrays / FilSegment.fillSoaArrays` | `soaCoord/UVec` just-synced last step's tail; `soaEnd1X` stale from prior step |
| `3_postFillSoa`        | after `FilSegment.fillSoaArrays`                            | `soaEnd1X` rederived from `soaCoord/UVec` |
| `4_preBindingDispatch` | before `GPUMotorBinding.detectBindings()`                    | binding pose pack about to read |
| `1_postPoison`         | after `GPUMoveThing.moveThings() + poisonFrameOnlyMirrors()` | `soaCoord/UVec` just demand-synced; `soaEnd1` just incremented +1 |

## The 4×4 table (seed=1, `soaEnds` poison, slot 0, steps 200/201/202)

Each cell is the maximum-axis deviation in µm from the comparison reference
(see methodology). `P` = poisoned (`> 0.5 µm`); `.` = clean.

| step | point                | `Thing.soaEnd1` | `Thing.soaCoord` | `Thing.soaUVec` | `FilSegment.soaEnd1X` |
|---|---|---|---|---|---|
| 200 | 2 preFillSoa          | P (d=199.75) | . (0.0000) | . (0.0000) | . (0.0029) |
| 200 | 3 postFillSoa         | P (199.75)   | . (0.0000) | . (0.0000) | . (0.0000) |
| 200 | 4 preBindingDispatch  | P (199.75)   | . (0.0000) | . (0.0000) | . (0.0000) |
| 200 | 1 postPoison          | P (200.75)   | . (0.0032) | . (0.0069) | . (0.0000) |
| 201 | 2 preFillSoa          | P (200.75)   | . (0.0032) | . (0.0069) | . (0.0000) |
| 201 | 3 postFillSoa         | P (200.75)   | . (0.0032) | . (0.0069) | . (0.0026) |
| 201 | 4 preBindingDispatch  | P (200.75)   | . (0.0032) | . (0.0069) | . (0.0026) |
| 201 | 1 postPoison          | P (201.75)   | . (0.0054) | . (0.0008) | . (0.0026) |
| 202 | 2 preFillSoa          | P (201.75)   | . (0.0054) | . (0.0008) | . (0.0026) |
| 202 | 3 postFillSoa         | P (201.75)   | . (0.0054) | . (0.0008) | . (0.0055) |
| 202 | 4 preBindingDispatch  | P (201.75)   | . (0.0054) | . (0.0008) | . (0.0055) |
| 202 | 1 postPoison          | P (202.75)   | . (0.0043) | . (0.0052) | . (0.0055) |

Reading: `Thing.soaEnd1` carries hundreds of µm of accumulated poison
(+1 µm/step since step 0; by step 200 it sits at ~+200 µm), and the
expected +1 µm bump appears between point 4 of step N and point 1 of
step N (the poison call). Every other array stays in the natural-motion
noise band (`< 0.014` µm anywhere in the table) at every point.

Slots 1 and 2 in the raw log show the same pattern.

## The crossover site: there isn't one (case (c))

Per the prompt's case decomposition, this falls in case (c): **none of
`soaCoord`/`soaUVec`/`soaEnd1X` poisoned, yet bindEvents still drops**.

Specifically:

- The poison stays trapped in `Thing.soaEnd1[]` (and the other
  per-Thing derived arrays `soaEnd2/ZVec/TransXTox` by extension —
  same code path).
- No CPU write-back reconstructs `Thing.soaCoord/UVec` from
  `Thing.soaEnd1` between frames on the GPU gliding path.
  `FilSegment.fillSoaArrays` recomputes `soaEnd1X` from clean
  `Thing.soaCoord/UVec` every step (point 3), so the binding kernel's
  pose pack reads CLEAN endpoints (`FilSegment.soaEnd1X` d ≤ 0.0055 µm
  across all snapshots).
- The `setFirstHalf` write-back at `FilSegment.java:843-846`
  (`setCoord(getEnd1X() + 0.5·length·getUVecX(), …)`) does NOT fire
  per-step in gliding — it's reachable only via `splitSegment` →
  biochem length-change events, which were measured at 21 fires total
  over the entire 10101-step run by Part-A's `DIAG_FILSEG_INIT_CT`
  (and `splitSegment` is a stricter subset of that).
- `Thing.recomputeDerivedSoA` is only called per-output-frame via
  `refreshHostMirrorsForOutput` — and in this `-r` run with no `-3js
  /-3jsLive`, `ThreeJSWriter.writeFrame()` early-returns at the no-
  consumer guard (`Env.threeJSOutputDir == null && !LiveFrameServer.
  isRunning()`), so even the per-100-step refresh doesn't fire. That
  explains why the table shows `Thing.soaEnd1` accumulating ~+200 µm
  by step 200 instead of being reset to canonical at step 100.

Therefore the observable shift (`bindEvents -80%`) does NOT propagate
through `Thing.soaCoord/UVec/length` into the binding kernel's pose
pack. It must propagate through a **non-geometric** consumer of one of
the poisoned host mirrors (`Thing.soaEnd1[]`, `Thing.soaEnd2[]`, or a
derived field).

## What the grep for non-geometric consumers found (and didn't)

Per the prompt's hint ("trace every per-step read of `attachPt`"),
walked the candidate set:

| candidate per-step reader of poisoned host state | disposition in `-gpu glidingAssay500_val` |
|---|---|
| `MyoFilLink.addForces` → reads `attachPt` (which `setAttachment→updatePos` writes from poisoned `mySeg.end1AsPt3D()`) | gated off via `gpuMotorHandled() == true` for every bound motor; `DIAG_UPDATEPOS_FROM_STEP_CT = 0` in the Part-A probe confirms `step()`'s CPU pair never fires. |
| `MyoFilLink.validateSeg → end1AsPt3D() == null` | result discarded (allocation never null); no behaviour. |
| `Mesh.fillFilSegMesh` populated bins → `motorFilMeshCollisions` / `filSegMeshCollisions` / `membraneFilMeshCollisions` | `motorFilMeshCollisions` only dispatched on `!Env.useGPU`; `filSegMeshCollisions` gated on `xLinks.isActive()=false`; no membrane in gliding. |
| `Myosin.applyLeverMotorJointForce` / `applyRodLeverJointForce` (read `end1AsPt3D / end2AsPt3D`) | `myoJoints1` thread only dispatches `jointConstraints()` on the `!gpuPath` branch (`Myosin.java:126-129`); confirmed inert on GPU path. |
| `MyosinFixed.applyRodFixedPtForce` (reads `myoRod.end1AsPt3D()`) | dispatched via `applyGPUDroppedForces` only when `DIAG_CPU_ANCHOR=true` (default false). |
| `MyosinDimer.divideAndConquer` joint forces (read `myoRod*.end1AsPt3D/end2AsPt3D`) | no MyosinDimer instances in gliding (`initialMyoMiniFils=false`). |
| `ProteinNode.applyMyoForce` (reads `curRod.end1AsPt3D()`) | no ProteinNode instances in gliding. |
| `Crucible.keepMyosinOnSurface / keepMyosinDimerOnSurface` (reads `curRod.end1AsPt3D()`) | `numChamberFixedMyos=0` in gliding (already established 2026-06-05). |
| `MyoMotor.checkOuterBugCollision` (reads `end1AsPt3D()`) | call site at `MyoMotor.step():183` is commented out. |
| `GPUMotorBinding.runCP1`'s `grid.fillFilSeg(…end1AsPt3D…)` | Phase 3 CP1 path, only dispatched when `BOA_DIAG_PHASE3_CP1=1` or similar. |
| `bridgeMotorForceWriteback` | reads device-resident `motorWriteback` FloatArray; no host poisoned input. |

**Every candidate I found by static grep is gated off, inert in this
workload, or consumes only device-resident state in gliding.** No live
host-side per-step consumer of `Thing.soaEnd1[]` is identifiable by
inspection of the source.

This is not a contradiction — `bindEvents` empirically drops ~80% under
`soaEnds` poison (prior selective-poison runs, `RUN_LOGS/
2026-06-05_phase45_selective_poison/`). Some path my grep missed reads
the poisoned host state per step. The miss is in the **survey**, not in
the trace.

## What this rules out vs leaves open

**Ruled out** (by this trace, with the 4×4 table as the smoking gun):

- A per-step CPU write-back from `Thing.soaEnd1` (or `end1Pt`) into
  `Thing.soaCoord/UVec/length` (e.g., a `setCoord(getEnd1X()…)` call
  on the live gliding path). If such a path existed it would show
  `Thing.soaCoord` poisoned at point 2 or point 3 of subsequent steps;
  the table shows `Thing.soaCoord` d ≤ 0.0057 µm everywhere.
- A direct corruption of `FilSegment.soaEnd1X` by anything between
  `fillSoaArrays` and binding dispatch (points 3 → 4). The table
  shows zero change in `soaEnd1X` between points 3 and 4 across
  three steps.
- The Phase 4.5 device-residency lever as a fix-for-this-bug-only
  rationale being a guess — **it removes the host pose-pack path
  entirely**, which makes ANY remaining host-side per-step
  `Thing.soaEnd1` reader irrelevant to the binding decision. The
  trace strengthens this: the leak is not via pose data at all, it's
  via a non-geometric consumer the host pose-pack flow doesn't even
  touch.

**Still open** (for the planner / jba, not this session's CC):

- Which non-geometric reader fires per step and feeds the +1 µm
  poison into the binding decision. Candidates the static grep
  cannot eliminate cheaply: any host-side path that reads a per-
  Thing `Pt3D` field on a non-FilSegment slot (e.g., a force
  recipient computed from a poisoned MyoRod / MyoLever / MyoMotor
  endpoint); or a TornadoVM plan FIRST_EXECUTION buffer fill that
  consumes a poisoned host array on plan rebuild (11 rebuilds in
  10101 steps, per Part-2 binding profile) and leaves the device
  state poisoned until the next rebuild.

The cheapest next probe is dynamic, not static: instrument the
binding kernel inputs (the `motPos / motDir / filEnd1 / filEnd2`
host FloatArrays just before `transferToDevice` fires) with a
poison-vs-baseline diff. If those FloatArrays show >0.5 µm
deviation under `BOA_PHASE45_POISON=1`, the leak is in the binding
pack itself (which my static grep claims reads only
`MyoMotor.soaX/Y/Z` and `FilSegment.soaEnd1X/Y/Z` — both clean per
this trace). If they don't, the leak is via forces on the move
plan: check the `forceSum` host FloatArray (or its device
EVERY_EXECUTION upload) for poison.

## Decision input for the planner

The 4×4 table closes a key question for the **Phase 4.5 fork** in the
prompt's "after this" section:

> **(A)** interim fix — cut or feed-fresh the write-back / redirect the
> reader → clean re-gate; or
> **(B)** go straight to **Phase 4.5** (binding reads the resident
> device pose, eliminating the host pose-pack path the poison travels).

Option (A) is misnamed — there's no write-back to cut. The leak is via
a non-geometric consumer of `Thing.soaEnd1` (case (c)). A localized
"refresh the mirror" fix would have to identify and patch the actual
reader (which the survey did not produce). The fix attempts to date
have all been refresh attempts; they've failed because the leak isn't
a geometric crossover.

Option (B) makes the host `Thing.soaEnd1` mirror irrelevant to the
binding decision by definition. Whether the still-unidentified non-
geometric reader survives Phase 4.5 depends on what it does — if it
applies a host-side force on motors, it'll still leak; if it's just a
diagnostic write, it'll become inert. The trace doesn't decide that
question; only finding the reader does.

## Files modified

| file | change |
|---|---|
| `boxOfActin/Phase45Trace.java` | New. Env-gated trace helper with one `snapshot(String)` entrypoint. No-op when `BOA_PHASE45_TRACE` unset. |
| `boxOfActin/BoxOfActin.java`   | Four `Phase45Trace.snapshot()` calls at points 2/3/4/1 in `doLoop`. No-op when trace disabled. |

Both kept committed. Trace adds 4 method calls per step; when disabled,
each call returns at the first env-var check (one branch + one boolean
field read).

## Reproduction

```
TDIR="$HOME/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx/share/java/tornado"
javac -g --release 21 --enable-preview -XDignore.symbol.file \
      -cp "$TDIR/tornado-api-4.0.1-dev.jar:libs/*:." boxOfActin/*.java *.java

BOA_PHASE45_POISON=1 BOA_PHASE45_POISON_FAMILY=soaEnds \
BOA_PHASE45_TRACE=1 BOA_PHASE45_TRACE_START=200 BOA_PHASE45_TRACE_STEPS=3 \
/tmp/p45_run.sh 1 \
  > RUN_LOGS/2026-06-05_phase45_trace/trace_soaEnds_seed1.log 2>&1
```

The run can be `kill`'d once `grep -c PHASE45_TRACE` shows 12 lines —
the trace finishes early in the run.

## Run logs

`RUN_LOGS/2026-06-05_phase45_trace/`:
- `trace_soaEnds_seed1.log` — 12-line trace + run preamble. Process
  killed after the 12 trace lines were captured; observables not
  rerun because the prior selective-poison sweep already established
  the `soaEnds` family reproduces the ~80% `bindEvents` drop.
