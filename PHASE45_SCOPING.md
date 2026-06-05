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
