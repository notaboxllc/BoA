# BoxOfActin Performance Survey — 2026-05-29

## Context calibration

JOURNAL puts dense-scale CPU wall (M=98K motors, S~1100 segments) at **~205 s** for 1101 steps, and **~727 ms/step** at M=400K. Phase-wall ranking at M=98K, CPU:

```
ThingStep        28.9%  (~57 ms/step)   <- moveThing + step + biochem
Brownian         16.0%  (~32 ms/step)
MotorBindGrid3D  15.0%  (~28 ms/step CPU rebuild + walk)
Myosin joints    11.8%  (~22 ms/step)
Mesh fill         3.4%
MyoDimer          4.6%
NodeLink/etc     ~3.7%
```

That four-way split is the budget every optimization is competing against. Numbers below are sized accordingly.

---

## A. Hot loop analysis

### A1. Per-step `fillSoaArrays()` is single-threaded
`MyoMotor.fillSoaArrays()` (MyoMotor.java:22-37) and `FilSegment.fillSoaArrays()` (FilSegment.java:41-52) run inline on the TimeLoop thread at the top of every `doLoop()` iteration (BoxOfActin.java:695-696), even when `Env.useGPU == false`. At M=98K motors × 10 float writes each that's ~1M Pt3D dereferences/step on one core; JOURNAL §F estimates 25 ms/call at M=98K — and this runs **before** the GPU branch even decides anything.
- Impact: **medium** (CPU runs); **high** for dense + GPU because it's the floor that GPU pack adds on top of.
- Fix: parallelize via existing `ThingStepThreads`, or skip when `!Env.useGPU` AND no other consumer (Mesh.fillMotorMesh / GPUMotorBinding/grid fill all consume these — so only effectively dead on a pure CPU run with no `-gpu`; even then they're used by the CkMotsThreads via `MotorBindGrid3D.motorFilCollisions` which reads `MyoMotor.soaOnFil[]`, `soaX/Y/Z`). Cheapest immediate win: split into a tiny ThreadSet, or fuse into the existing meshMotors phase (which is already parallel).

### A2. `MotorBindGrid3D` fill is single-threaded by construction
MotorBindGrid3D.java:267-303: `FillThreads` is declared `super(1, ...)` — one worker. JOURNAL identifies this as ~28 ms/step at M=98K and ~73 ms/step at M=400K — pure single-thread CPU. Sync of multiple threads against per-cell monitors is the obvious extension; the JOURNAL labels it "accept as-is (GPU port makes it irrelevant)" but it dominates 15% of dense wall in the CPU-only case.
- Impact: **high** at dense scale.
- Fix: parallel fill via X-stripe partition (motors split by motorId range, segments split by filArrayPos range), per-cell `synchronized` already supports concurrent insertion. Or replace per-cell sync with `AtomicInteger` cell counters and lockfree append. Or scatter via count-then-scan (a poor man's GPU prefix-sum on CPU); but the simpler stripe path should reclaim 5-7× of the 28 ms.

### A3. The membrane relaxation loop is unbounded per step
BoxOfActin.java:814-827: `while (NodeLink.maxStrain > Env.membraneMaxLinkStrain.getValue() && mPass < Env.maxMembranePasses.getIntValue())`. Each pass dispatches NodeLink + StickyNode moveThing ThreadSets — two full barriers per pass. Not exercised in gliding-assay scope, but for any membrane configuration this is a per-step inner loop that can iterate many times. Each pass has the full ThreadSet `wait`/`notifyAll` overhead from Barrier (Barrier.java:36-52, with three synchronized blocks per pass).
- Impact: **low** (current config); **high** (future membrane work).
- Fix: cache `Env.membraneMaxLinkStrain.getValue()` once before the loop; and consider a Newton/relaxation hybrid so the number of passes is bounded.

### A4. `applyBenchmarkPins()` calls `initialize()` per terminal segment per step
BoxOfActin.java:1248-1258: small fixed-N call, not a problem at scale, but each `initialize()` does zVec.cross / unitVec / yVec.cross / transMat / 9 array writes — ~30 flops + 3 sqrts. Trivial.

### A5. Tuner v15-v25 ladder in `doLoop()` is a 12-deep if/else
BoxOfActin.java:870-1148, every step in benchmark mode evaluates 12 `!= null` checks even though only one is set. Cheap (branch-predicted), but a sign that these should be unified behind an interface. Real concern is the structural debt, not the perf cost.

### A6. ThreadSet barrier overhead per phase
Barrier.java:36-52 uses three synchronized blocks per thread per phase. `doLoop()` fires ~16 phases × ~16 threads = ~250 wait/notify cycles per simulation step. At 5-10 µs per cycle on contended monitors that's 1-2 ms/step in pure synchronization overhead.
- Impact: **medium** in steady state, but the overhead is essentially fixed independent of Thing count, so it dominates at the small end (gliding-assay validation 500 motors), and is negligible at M=400K.
- Fix: a `Phaser` or `CountDownLatch` is leaner. Or fuse adjacent phases that don't have inter-dependencies (mesh fills 0/1/2 could be a single barrier with three concurrent partitions, since they touch independent meshes).

---

## B. Data structure audit

### B1. `Mesh.meshpoints` is `double[nX][nY][1000]` of IDs cast through `double`
Mesh.java:22, addToMesh:281. Stores `(int)` IDs as `double`, then reads them back via `(int)` cast in `motorFilMeshCollisions` (MyoMotor.java:355, FilSegment.java:1862, etc.). Six issues:
1. Wrong primitive — should be `int[][][]`. Halves memory.
2. `BIN_DEPTH=1000` is wildly oversized; gliding-assay has <5 entries per cell typical (only the long canonical filament hits more).
3. 3D nested array = 3 pointer indirections + bounds checks per access; cache-unfriendly.
4. Three separate `Mesh` instances (FILSEG, NODE, MYOHEADS) — for the gliding assay only two are used; for boa10 all three.
5. mSync is `Object[nX][nY]` — for a 51×51 mesh that's 2,601 Object allocations.
6. **2D only** — Z is ignored. False-positive candidates in 3D networks. Already replaced by MotorBindGrid3D for the motor query but the older `Mesh` still serves xLink and node-node queries.
- Impact: **medium** at gliding scale; **high** for boa10/3D-network.
- Fix: flat-layout CSR: `int[] cellOffsets` + `int[] cellContents`, rebuilt each step. Rebuild on CPU via count-then-scan; this is exactly the layout MotorBindGrid3D.packForGPU produces — generalize that layout for the Mesh queries too.

### B2. `MotorBindGrid3D` is `int[nX][nY][nZ][BIN_DEPTH=1000]`
MotorBindGrid3D.java:25,61. For typical scales:
- gliding-assay (14×2×0.5): 71×11×4×1000 = 3.1M ints = **12.5 MB**.
- overnight (20×20×0.5): 101×101×4×1000 = 40.8M ints = **163 MB**.
- boa10 (10×10×10): 51×51×51×1000 = 132M ints = **528 MB**.

The 528 MB is a real memory bomb for boa10-scale runs. Per-cell sync arrays are similar: `Object[nX][nY][nZ]` — for boa10 that's 132K Objects (negligible) but for cube-cells the 4D pointer indirection kills cache locality during fill.

- Impact: **high** (memory + cache).
- Fix: same CSR pack. The `packForGPU()` method already does this conversion to upload — replace the canonical CPU storage with the packed form, eliminate the 4D array entirely. The 27-cell walk inside `motorFilCollisions` then reads contiguous int slices.

### B3. `Thing.theThings[2000000]` (16 MB) and friends
Thing.java:17. Allocated even for runs with 100 Things. FilSegment.theFilSegments[1000000] (8 MB), MyoMotor.theMotors[500000] (4 MB), Myosin.theMyosins[500000] (4 MB), MyoFilLink[500000] (4 MB), NodeLink[1000000] (8 MB). Add the per-class double[] SoA arrays at 8 MB each (10+ arrays) — ~80 MB just for SoA scratch. Total static array footprint ~120 MB; only ~1-10% is ever used in gliding-assay.
- Impact: **low** perf, **medium** memory.
- Fix: grow-on-demand `Arrays.copyOf` doubling, set initial cap to `Env.maxThings` parameter file value. Currently fine because heap is generous.

### B4. `transXTox`/`transxToX` as `double[3][3]`
Thing.java:36-37. Nested array = 2 pointer indirections per read, no SIMD. Every `xToX(Thing,Pt3D)` and `XTox(Thing,Pt3D)` call reads 9 doubles through 3 pointer chases. Pt3D.java:407-509 does this thousands of times per step (every force/joint/Brownian transform).
- Impact: **medium** (every step, every Thing, multiple times).
- Fix: flat `double[9]` field or nine scalar fields. JOURNAL §iter2b survey mentions the kernel re-derives `zVec = cross(uVec, yVec); normalize` inline rather than uploading transMat — the same idea applies on CPU: store uVec/yVec only, derive zVec, then use the 6 floats directly in body↔fixed transforms.

### B5. Linked-list filament topology
FilSegment.end1Fil / end2Fil / motherFil / arpChildren[40]. Linked list per filament; works fine for the topology operations themselves but means `validateEnd1Link/2Link` (FilSegment.java:1212-1274) chases 4-5 pointers per check, called twice per FilSegment per step. At 1200 segs in dense-gliding = ~12K pointer chases/step.
- Impact: **low**.
- Fix: cache `filAtEnd1/2`-derived booleans for short-circuit; current code already does this. Probably fine.

### B6. `linkLocs` and `linkedTo` per-FilSegment dynamic resize
FilSegment.java:1329: `if (linkCt > linkLocs.length-1) { linkLocs = new double[2*linkLocs.length]; }`. Inside a `synchronized` block. Rare event but allocates within the xLink hot phase.
- Impact: **low**.

### B7. Per-Thing PRNG state
Thing.java:64: `MersenneTwisterFast myPRNG`. MT state is ~2.5 KB (mt[624] int + state). At 400K Things: **~1 GB** of RNG state. JOURNAL doesn't flag this directly but the heap pressure cited in the dense run (-Xmx20G) is consistent.
- Impact: **high** (memory), **medium** (initialization cost; each call is fast).
- Fix: ThreadLocal MT instances, or per-Thing `long` Xoshiro state (8 bytes vs 2500 bytes), or Wang-hash seeded by `myThingNumber * counter`. Since FilLink already uses `ThreadLocalRandom.current().nextDouble()` for `ckLinkBreak`, the precedent is there.

### B8. Pt3D box on each `new Pt3D(...)` site
Pt3D has 24 static factory methods returning fresh objects (Pt3D.java:43, 45, 54, 75, 82, 94, 103, 159, 165, 182, 197, 207, 233, 237, 252+...). Many are called inside `step()` and `moveThing()` hot paths.
- Impact: **medium** (GC churn).
- Detail: see §C below.

### B9. ArrayList in `Parameter.getAllMutable()`
Parameter.java:39-46. Returns fresh ArrayList. Called by LiveFrameServer on `queryParams` — not per-step. Fine.

### B10. `instanceRegistry` ConcurrentHashMap (Thing.java:27)
Lookup is rare (only on `findByInstanceId` for click-to-inspect). Insert/remove on construction/cleanup is amortized; not a hot path. OK.

---

## C. Memory allocation patterns (GC pressure)

### C1. `Pt3D.Add / Sub / Cross / Scale / UnitVec / Reverse` allocate per call
Hot-path call sites I found:

| File:line | Call | Per step? |
|---|---|---|
| FilSegment.java:1926 | `Pt3D.Add(fil.end2, filTipR, fil.uVecR)` | Yes (membraneFilMeshCollisions) |
| FilSegment.java:1930 | `Pt3D.UnitVec(pDist, node.coord, filTipCenter)` | Yes |
| FilSegment.java:1931 | `Pt3D.Reverse(nodeVec)` | Yes |
| FilSegment.java:1933-1934 | `Pt3D.Scale(mag, nodeVec)` ×2 | Yes |
| MyoMotor.java:179 | `Pt3D.Scale(mag, cE.forceUVec)` | Yes (collisionCheckInt-gated) |
| FilSegment.java:744 | `Pt3D.Add(splitFilSeg.end2, ...)` | On split (rare) |
| FilSegment.java:1960 | `RetObj retO = new RetObj()` | Yes for each xLink candidate pair! Allocates 6 Pt3D objects |
| ProteinNode.checkBugCollisionFromOutside | `Pt3D.Scale(mag, cE.forceUVec)` | Yes |

`checkNodeFilTipsCollision` (FilSegment.java:1919-1940) allocates **5 Pt3D objects** per called pair: filTipCenter (Add), nodeVec (UnitVec), filVec (Reverse), and two Pt3D.Scale results. With membrane configs and active StickyNodes this is N_node × N_fil_candidates × 5 allocs/step.

`checkToLink` allocates a fresh `RetObj` with 6 Pt3D fields per (filA,filB) candidate pair — JOURNAL records this was added as the 2026-05-26 race-fix for the shared `fil1.retObj` race. The correctness fix was correct but the allocation cost is real at high filament density. For 1000 segs with average ~3 candidates per same-cell pass × every 10 steps × 0.1s sim = 30k checkToLink calls per run × 6 Pt3D = 180k Pt3D allocations/run. Each Pt3D = ~32 bytes ⇒ ~6 MB allocated, all garbage. At dense scale or longer runs this becomes meaningful.
- Impact: **medium** for gliding; **high** for boa10 with active xLink/membrane.
- Fix: thread-local RetObj pool keyed by `Thread.currentThread().getId()` — pre-allocated once per worker thread. Same idea for Pt3D scratch.

### C2. Pt3D `ptDist`/`ptDistSqrd` use `Math.pow(x,2)` rather than `x*x`
Pt3D.java:23-31. `Math.pow` is not always inlined to `x*x` by HotSpot, especially with arbitrary exponent argument. Same issue in `ValueTracker.variance` (line 123) and `Pt3D.varianceOfPt` (lines 137-139). 44 grep hits for `Math.pow` across the codebase, most in hot paths (`calculateProperties` does `Math.pow(asIfLengthM, 3)`).
- Impact: **low-medium** (modern JIT often handles `Math.pow(x,2)` via intrinsic, but `Math.pow(x,3)` is real call).
- Fix: replace `Math.pow(x,2)` → `x*x` and `Math.pow(x,3)` → `x*x*x` everywhere in hot paths. Trivial change, measurable benefit on critical sections.

### C3. String formatting in `getJSonString()` (filaments, myosins, etc.)
FilSegment.java:3567-3727: every filament builds 9+ `String.format` strings per output frame. ThreeJSWriter.java:62-128 uses StringBuilder which is good but still calls `String.format` per entity (each format allocates a temporary FormatString state). At M=98K motors, the per-frame format calls produce ~3 MB of garbage. JOURNAL records 32 MB JSON frames at M=98K, 132 MB at M=400K. **Output is on the simulation thread** (BoxOfActin.java:1168 → `logAndDraw`).
- Impact: **medium** during output frames; **low** between.
- Fix: hand-roll `sb.append(double)` (slower per-call but no garbage) or use a thread-local `DecimalFormat` with `format(double, sb, fp)`. Better still: emit a binary format and let the viewer parse it — but that's a bigger refactor. Async output thread would also help (see §I).

### C4. `Pt3D []` arrays in StickyNode/ProteinNode for sticky-link points
ProteinNode.java:64: `myoDimerPtsInX[]` populated at constructor. Each entry is a `new Pt3D()`. Allocated at init, not per-step. Fine.

### C5. `MyosinDimer` allocations during `bespokeNodeFilament`
ProteinNode.java:316,320,343: `new Pt3D()` and `new Myosin(...)` at init. Fine.

### C6. Per-step `incForceSum(Pt3D)` doesn't allocate
Hot-path force adds use member field `Fcoll`, `F`, `Fopp`, `RcrossF`, etc. — pre-allocated scratch. Good. But `Thing.incForceSum(Pt3D forceToAdd, Pt3D forcePoint)` (Thing.java:317) is `synchronized` — see §D2.

### C7. Iterator allocation in `theMyosins[].myo.iterator()` paths
No `for (X x : collection)` iteration over collection objects in the hot loops I read — they use indexed `for (int i=0;i<count;i++)`. Good.

### C8. `String.format` and `System.err.println` in Crazy-torque guards
Myosin.java:201, 254; MyoFilLink.java:138, 161; FilSegment.java:1685, 1738. Disabled by `Double.isNaN` guard in the GPU path (JOURNAL iter2b-polish), but CPU path still has the `else { System.out.println("Crazy torque ...") }` branch — if it ever fires, **System.out.println on the simulation thread**, holding `PrintStream` lock; cascading effect at large N. Probably never fires under steady CPU runs but it's a landmine.
- Impact: **catastrophic if triggered**.
- Fix: gate behind a static counter (`if (crazyCount++ < 100)`), or convert to a fast atomic counter and log a summary at end.

### C9. `Monomer` linked-list nodes per polymerize event
Monomer.java:97-115. Each polymerization event allocates one Monomer object (~80 bytes + linked-list pointers). At dense gliding, monomer add rate is ~per-filament-per-100-steps; for 100 filaments × N=1100 steps × 0.01 add/step = ~1100 Monomer allocs/run. Negligible. (But this is why the gliding-assay configs use `noMonomersSimd:true` — exactly to skip this.)

---

## D. Thread utilization

### D1. Fixed equal-size chunks; no work stealing
Every ThreadSet does `jobDiv[i] = i*N/numThreads` (Thing.java:140, Mesh.java:94/99/104, etc.). For homogeneous work this is fine; for `ThingStepThreads` it is not — a chunk containing many FilSegments (which have ~100-line `step()` doing xLink validate + node forces + torsion + bug check) runs far longer than a chunk containing MyoLevers (which have empty `step()`). At gliding-assay scale where the population is mostly motors (homogeneous), this is fine; at boa10-scale with Arp branches it becomes a load-imbalance problem.
- Impact: **medium** (depending on workload).
- Fix: Java's `ForkJoinPool` with `RecursiveAction` would handle imbalance automatically; or stripe by `i % numThreads` so each thread gets every Nth Thing (a finer-grained interleave that balances heterogeneity at the cost of cache locality).

### D2. `synchronized` per-Thing on every force/torque add
Thing.java:311-329, FilSegment.java:2770-2788. Every `incForceSum` takes a per-Thing `forceSync` monitor lock; same for `torqueSync`. In the xLink phase, multiple threads can write forces to the same FilSegment (a filament linked to N others). At M=98K with active xLinks, the synchronized lock chain is held across multiple field writes (`forceSum.inc`, `rForce.sub`, `rForce.scale`, `tempTorq.cross`, then `synchronized(torqueSync)` again).

Each lock acquire is ~20-100 ns uncontested, much more contested. With 16 threads, contention is real. JOURNAL 2026-05-27 already identified force-accumulation races as the source of non-determinism — the existing locks don't even guarantee consistency.
- Impact: **high** (contention).
- Fix: per-thread force/torque accumulators, gathered at end of phase. Or LongAdder/DoubleAccumulator (Java's striped accumulator for high-contention).

### D3. Per-cell `synchronized` on every Mesh write
Mesh.addToMesh:275, MotorBindGrid3D.addFilToCell/addMotorToCell:114,131. For ~98K motors × ~8 cells = ~800K lock acquire/release per step in the fill phase. The locks are uncontested most of the time (since cells are spread spatially), but each acquire still has memory-barrier cost.
- Impact: **medium**.
- Fix: lock-free append via `AtomicIntegerFieldUpdater` on cell count; or per-thread cell buffers gathered serially after fill (the gather is single-threaded but small).

### D4. `Barrier.threadDone` triple-wait
Barrier.java:36-52: each thread enters `threadDone()`, increments `numThreadsIn`, waits, increments `numThreadsOut`, waits again. Three `notifyAll`+`wait` cycles per phase per thread. 16 phases × 16 threads × 3 = 768 synchronization events per step. At ~5 µs each that's ~4 ms/step purely in barriers — about 1% of CPU wall at M=98K, more like 5% at gliding-assay scale.
- Impact: **medium** (dominant overhead at small N).
- Fix: `Phaser`, `CyclicBarrier`, or LongAdder-based done-counter with `Thread.onSpinWait`.

### D5. Serial phases that could be concurrent
`doLoop()` runs `xLinkStart` (phase 6) which dispatches FilLink+Arp23+ActA serially within one ThreadSet's `divideAndConquer`. These three are entity-independent (FilLink reads forceSum of FilSegments; Arp23 reads forceSum of FilSegments; ActA same). They write to forceSum/torqueSum on the same Things — so they can't truly parallelize without a force-accumulation fix (§D2), but if that fix lands they're a free 3× concurrency win.

The mesh fills (meshFils, meshNodes, meshMotors) write to separate mesh instances — already independent. Currently three sequential barriers; could be one barrier covering three concurrent partitions. Saves ~2 barriers/step.

### D6. WebSocket sender threads are well-isolated
LiveFrameServer.java provides bounded-queue per-client senders with `offer` non-blocking. Simulation thread never blocks. Good.

### D7. `Env.simulationTime` and `Env.counter` not `volatile`
Env.java:115,117. Read across threads (mesh staleness check, biochem timestamps). Should be volatile. May cause inconsistent staleness, though `Env.safeO` synchronization at top of doLoop covers it.

---

## E. Spatial algorithms

### E1. `Mesh` is 2D (XY only); `MotorBindGrid3D` is 3D
The old `Mesh` system serves xLink, node-node, node-fil-tip, and (legacy) motor-fil. Z is ignored — segments at z=0.4 and z=-0.4 sharing the same XY cell are evaluated as candidates. In gliding-assay (slab geometry) this is acceptable; in boa10/branched-network with 10×10×10 µm box it's a 50% false-positive rate.

`MotorBindGrid3D` is 3D and well-built, but only the motor-fil query uses it. The other queries still walk 2D bins.
- Impact: **medium** for boa10; high for any 3D network.
- Fix: generalize MotorBindGrid3D to serve all four query types via tagged contents. JOURNAL's broad-phase/narrow-phase plan (GPU_STRATEGY.md §"Collision Architecture") explicitly anticipates this; the CPU implementation can ship before GPU.

### E2. 27-cell walk is naive
MotorBindGrid3D.motorFilCollisions:237-261 walks 27 cells per motor with three nested loops and per-iteration timestamp + range checks. For motors near a chamber wall, the wall-clip means fewer cells but the inner check still happens; for typical motors all 27 cells are valid. Each cell read does `filTimeStamps[nx][ny][nz]` (3 indirections), then `filActiveCts[nx][ny][nz]` (3 more), then `filCells[nx][ny][nz][j]` (4). That's 10 indirections per cell × 27 cells × 98K motors = ~26M indirections per call — and pack-fill before each call walks 100K motors writing 8 ints each.
- Impact: **high** at dense scale.
- Fix: precompute `cellOffsetTable[27]` (flat ints) for each motor's home cell and translate the inner loops to a flat CSR walk (which the GPU pack already does for the GPU side; do it on CPU too). Or, simpler: Morton/Z-order the cell IDs so a 3×3×3 neighborhood is contiguous in memory.

### E3. xLink mesh check has a self-pair bug
FilSegment.filSegMeshCollisions:1864: `for (int j=i; ...)`. Includes j=i case. `checkToLink` then checks `iSeg.filID != jSeg.filID`, which excludes same-filament but would otherwise process the same segment against itself. Wasted call but harmless.

### E4. Mesh.OVERLAP is `false`
Mesh.java:20: `OVERLAP=false`. The OVERLAP code paths (Mesh.java:327-337, 385-395, 435-445) clamp endpoint bins ±1, which adds spatial padding. With OVERLAP=false, segments straddling a cell boundary can miss candidates. JOURNAL doesn't mention chasing this; possibly intentional, but worth re-examining now that segments are getting longer in dense-gliding.

### E5. `checkBugOrBoxCollision` called every step for every Thing
FilSegment.checkBugOrBoxCollision (FilSegment.java:1166-1173) runs in `step()` every step. With 100K motors and any-shape chamber (`Crucible.amICollidingOuter` does fairly cheap distance math but is not O(1) for complex bug shapes), this is ~98K calls/step. Probably fast per-call but worth measuring.

---

## F. Force computation (`step()`)

### F1. Filament link forces use `Pt3D.ptDist` which calls `Math.sqrt`
FilSegment.addLinkForces:1365 does `Pt3D.ptDist(linkPt, ptAtEnd2)`. The result is used to compute strain and to normalize `linkUVec.unitVec(strainDist,ptAtEnd2,linkPt)` (which divides by mag, so it would benefit from `strainDist` already computed). The unitVec method has both signatures (with and without mag) and the code uses the with-mag form correctly — so the sqrt isn't redundant.

But `ptDist` itself uses `Math.pow(x,2)+Math.pow(y,2)+Math.pow(z,2)` then `Math.sqrt`. The pow-twos are needless. **44 instances of `Math.pow` across the codebase**, mostly in hot paths.

### F2. `Math.acos` in `addTorsionSpringForces`
FilSegment.java:1653, 1707. `Math.acos(dotVecs)` for each fil-fil torsion spring, every step it's checked (gated by `filAtEnd2 & !end2TorqCkd`). For dense networks at 1000 segments × ~2 torsion checks/seg = 2000 acos/step. acos ≈ 30 ns intrinsic. Total ~60 µs/step. Minor.

Same in `MyoFilLink.alignUVecTorque` (acos in 122, 149) and `Myosin.applyLeverMotorJointTorque` (acos in 183, 239). At dense scale (98K motors, ~99% unbound, but joint-torque runs for every motor regardless of bind state via `applyLeverMotorJointTorque`/`applyRodLeverJointTorque`): 98K × 4 acos/step ≈ 12 ms/step. **This is a real chunk of the Myosin joints phase (~22 ms/step at M=98K).**
- Impact: **medium-high** for dense motor populations.
- Fix: convert to small-angle approximation when `dotVecs > 0.99` (where most steady-state joints sit). Or use a Padé approximation. Saves ~15-25 ns per call × 400K calls/step = ~10 ms/step at dense scale.

### F3. `Math.exp` in `ckRelease`
MyoFilLink.java:207-209. Two `Math.exp` per bound motor per step (Guo&Guilford catch/slip). exp ≈ 20 ns intrinsic. For ~7 bound motors at validation, trivial; for ~250 bound motors at dense scale ≈ 10 µs/step. Fine.

### F4. `Math.exp` in `forminCanHold` and `getPolyRateEnd2`
FilSegment.java:866, 2404. Inside per-step biochem on bound-formin filaments. Rare. Fine.

### F5. Distance-vs-distance² inconsistency
- `checkFilSegCollision` (MyoMotor.java:432-434): correctly uses `conDistSq < myoColTol²`. Good.
- `Thing.pointAndLineIntersectTest` (Thing.java:487-506): computes `retO.conDist = Pt3D.ptDist(...)` (sqrt) even though callers only check it against a threshold. Used for old motor binding path (now mostly gone) and FilLink.checkForAnnealing.
- `Thing.lineSegmentIntersectTest` (Thing.java:481): same — sets `conDist = ptDist(conPt1, conPt2)`.
- `checkToLink` (FilSegment.java:1985): uses `retO.conDist < Env.crossLinkGrabDist.getValue()` — could be a squared compare.
- `checkNodeFilTipsCollision` (FilSegment.java:1927): same, `Pt3D.ptDist` then compares to `radius+filTipR`.
- Impact: **low individually**, adds up.
- Fix: introduce a `conDistSq` field on RetObj, defer sqrt to callers that actually need the distance (most don't).

### F6. `forceSum` += force is symmetric for many force types
xLinks (FilLink.applyTransForce:204-214) and node-tip collisions (FilSegment.checkNodeFilTipsCollision:1933-1934) compute force-on-fil1 then `-1 * F → forceVec` and add to fil2. Symmetric but each side is a separate `incForceSum` call (and thus a separate `synchronized(forceSync)` acquisition). If `forceSync` per-Thing contention matters (§D2), these are ~2× the lock acquisitions of necessary.
- Fix: per-thread accumulation eliminates the lock entirely; symmetry then doesn't matter.

### F7. `Pt3D.unitVec()` near-zero-magnitude path is non-deterministic
Pt3D.java:117-124, 139-146, 150-157: if mag==0, calls `randomUnitVec(Env.mtRNG)`. Cross-thread reads of `Env.mtRNG` (a single MersenneTwister, not Fast) without synchronization → race. JOURNAL doesn't flag this but it's a latent determinism source. The 2026-05-27 entry noted that "force-accumulation races" cause non-determinism; the unitVec random fallback is another contribution.

---

## G. Brownian motion

### G1. Marsaglia polar method rejection loop in `UCircRnd.newValue`
UCircRnd.java:30-37. ~21.5% rejection rate per pair, two-uniform draw. **For each Thing, `calcRandomForces` calls `xVals.newValue + yVals.newValue + zVals.newValue` = 3 UCircRnd draws** = ~7.65 average nextDouble() calls per Thing per step.

At M=98K, that's ~750K MersenneTwisterFast.nextDouble() calls per Brownian phase. MTF's nextDouble is ~5 ns intrinsic → ~3.75 ms/step pure RNG = 16% of the Brownian phase time (~32 ms/step).
- Impact: **medium**.
- Fix: Replace Marsaglia polar with Box-Muller (no rejection, single sin+cos+log+sqrt per pair) — net wins because no rejection but more transcendentals. Or use Ziggurat (no rejection, table lookup, table can be hot). The GPU kernel already uses Box-Muller with Wang hash — porting that algorithm to CPU is a quick win.

### G2. Per-Thing PRNG lock contention is zero
Each Thing has its own `myPRNG` (MTF, not shared). No contention. Good.

But: **`Env.mtRNG` (a single MersenneTwister)** is read from many places: `Pt3D.RandomUnitVec(Env.mtRNG)`, `UCircRnd.newValue()` (the no-thing version, dead?), `ProteinNode.biochemStep()` (line 273: `Env.mtRNG.nextDouble()`), `FilSegment.spawnRdmFilaments`. The biochemStep call runs on `ThingStepThreads` — multiple threads read `Env.mtRNG.nextDouble()` concurrently. MersenneTwister is **not** thread-safe (unlike MTF). Likely produces wrong-but-statistically-OK numbers (because MT's internal state is just int arrays, racing degrades but doesn't crash) — but it's a data race.
- Impact: **low** perf, **latent correctness**.
- Fix: ThreadLocalRandom or explicit synchronized wrapper around Env.mtRNG.

### G3. `bTransDiff / bRotDiff` recomputed every call
Thing.calcRandomForces:375-378 mults `bTransDiff * facterm`, but `bTransDiff` is set in `calculateProperties()` (called once on Thing init and on length-change). The multiplication itself is fine; not the issue. The issue is that fac1 and fac2 are recomputed every step from facterm × bTransDiff, where bTransDiff is constant for most Things. Could be precomputed as `sqrt(bTransDiff)` once and multiplied by the (cheaper) `sqrt(facterm)` factor — same arithmetic but saves redundant work in the hot loop. Marginal.

### G4. `1.0/Env.brownianDeltaT.getValue()` in inner loop
Thing.calcRandomForces:379-380. `Env.brownianDeltaT.getValue()` is a virtual call + field read; `1.0/x` is a divide. Called once per Thing per step in the slow path. Trivial. Cache outside loop.

### G5. Wang-hash kernel on GPU
GPUMoveThing has it already. JOURNAL iter2c confirms statistical equivalence. Done.

---

## H. Biochemistry and state machines

### H1. `biochemCheckInt` controls biochemistry cadence
Thing.biochemCheckInt = `biochemDeltaT / deltaT` ≈ 100 (with default 1e-3/1e-5). So biochem runs every 100 steps — much less frequent than mechanics. Good.

### H2. `Monomer.checkHydrolysisCofilinTropo` walks full filament linked list
FilSegment.hydrolizeInFilaments:3494-3506 calls `curMon.checkHydrolysisCofilinTropo(this)` for every monomer in the filament. At 2 µm filament × 740 monomers per filament × biochem step every 100 simulation steps × 100 filaments = ~74K monomer checks/biochem step. Each check does `myPRNG.nextDouble()` and up to 2-3 transitions.
- Impact: **medium** if dense filament networks have many monomers (e.g., 64 monomers/seg × 100 segs × 100 fils = 640K monomers, ~64K hydrolysis checks per biochem-fired step). At biochem cadence 1/100 mechanics, ~6.4K monomer checks/mechanics-step amortized.
- Fix: vectorize: track per-filament ATP/ADP-Pi/ADP fractions, simulate as a 3-bin Markov chain at the fraction level. Loses per-monomer identity but most of the time you only need aggregate. This is a big algorithmic change though.

### H3. `MyoMotor.biochemStep` switch on nucleotideState
MyoMotor.java:203-220. Per-motor switch with one PRNG draw and one transition. At M=98K and biochem cadence 1/100 ≈ ~98K calls every 100 steps = ~1K/step amortized. Trivial.

### H4. `splitSegment` and `joinSegments` are O(L) in monomer count
FilSegment.java:740, FilSegment.transferMons:776-797. Walks the monomer linked list to find the split point. For a 740-monomer filament, ~740 pointer chases. Rare event (one split per filament per N seconds). Negligible.

### H5. Tropomyosin/cofilin binding chains
Monomer.tropoBinding/Unbinding walks neighbor monomers. Bounded depth (cooperative ~5 monomers each side). Fine.

---

## I. I/O and output

### I1. Frame output runs on the simulation thread
BoxOfActin.java:1168: `if (!Env.remote) { logAndDraw(); } else { remoteLog(); }` — both call ThreeJSWriter.writeFrame on the TimeLoop thread, which builds a 32-132 MB JSON string and writes to disk. At dense scale this is a multi-second block.

JOURNAL says: "ThreeJSWriter guard active (no -3js; guard skips frame build when no consumer)" — yes, the no-consumer path is fast. But when consumers exist (file write or WebSocket), the build and write happens on the sim thread.
- Impact: **high** during output frames (which are every `toFileInterval` steps, default 1000).
- Fix: producer-consumer pattern: build JSON on a dedicated daemon thread, given a snapshot of the relevant state. Snapshot cost is ~one full Pt3D-copy of every renderable Thing. WebSocket dispatch already uses per-client bounded queue; the file write should join that thread.

### I2. `getJSonString()` per Thing builds many `String.format` strings
See §C3. Each filament/myosin/dimer/Arp emits 5-10 format calls. Two `getJSonString` paths exist: ThreeJSWriter (faster, raw StringBuilder concat) and FileOps `writeSimJSonsFrame` (uses each Thing's getJSonString, which builds locally then concatenates). The Simularium path is the slower one; ThreeJSWriter is the modern one. If both are active per run, formats add up.

### I3. `source.zip` written per output run
ThreeJSWriter.archiveSource:32-53. Walks `.` for *.java files, zips them. Fine — runs once at first frame.

### I4. Memory growth from frame file writes
File writes use try-with-resources `PrintWriter(new BufferedWriter(new FileWriter(path)))` — closes cleanly. No memory leak.

### I5. Parameter file parsing
FileOps.loadParamConfig parses a few hundred lines on startup. Not a hot path.

### I6. `LiveFrameServer.dispatchFrame` uses bounded ArrayBlockingQueue(4)
LiveFrameServer architecture described in CLAUDE.md: simulation thread offers; per-client daemon sender drains. Non-blocking. Good.

---

## J. Myosin assembly and joint mechanics

### J1. Myosin object hierarchy is over-allocated
Each `Myosin` instantiation creates a MyoMotor + MyoLever + MyoRod + MyoFilLink. **Four Thing subobjects per motor** — each has the full Thing field set (~30 Pt3D fields, ~2-3 KB). At M=400K motors that's 1.6M Thing allocations, ~3.2 GB heap. Matches the JOURNAL 2026-05-28 OOM finding (had to bump -Xmx8G → -Xmx20G).

The MyoMotor and MyoRod fields are largely identical (same biochem state path). The MyoLever has commented-out Brownian (so it's essentially a passive joint). Each could fit in 8-16 bytes if you only stored coord + uVec + yVec + a few flags.
- Impact: **memory-critical** at dense scale.
- Fix: SoA layout for myosins. The fields are already partially packed in `MyoMotor.soaX/Y/Z/UX/...`; collapse the entire myosin into a single struct-of-arrays indexed by myosin number, with no per-myosin Thing objects. (This is the iter2e+ work the JOURNAL alludes to: device residency for coord/uVec/yVec across phases.)

### J2. Joint torques traverse hierarchy redundantly
Myosin.applyLeverMotorJointTorque (line 167) reads `myoMotor.uVec` and `myoLever.uVec`; applyRodLeverJointTorque (line 231) reads `myoRod.uVec` and `myoLever.uVec`. Lever uVec read twice per joint pass. Could batch by caching.

JOURNAL §iter2b §J also flags **uVec orthonormalisation drift**: each step's small-angle update creates `(1, ε_y, ε_z)` which is normalized; the `cross(uVec, yVec)` then `unitVec()` cleans up. Numerically fine in double; float drift on GPU was a session issue. CPU side is OK.

### J3. `MyoFilLink.alignUVecTorque` and `alignYVecTorque` both run every step for bound motors
MyoFilLink.java:116-163. For each bound motor: cross, unitVec, acos, scale, two `incTorqueSum` (each `synchronized(torqueSync)` on the filament). At 250 bound motors × 2 alignments/step = 500 `incTorqueSum` calls/step on a small set of filaments (in gliding-assay there's one filament; in dense-gliding ~101). High contention on the filament's torqueSync lock.
- Impact: **medium** at dense scale.
- Fix: per-thread torque accumulation (§D2) eliminates the contention.

### J4. JOURNAL §F at M=98K records Myosin (joints) at 21.6 s (10.4% of wall)
That's the budget the joint optimizations are competing for. Two acos per joint × 4 joints (one per myo dimer-element) per myosin × 98K myosins = ~800K acos/step. Replacing acos by small-angle approx in the dominant regime (rod ≈ aligned with lever) saves real wall-clock.

---

## K. Filament topology operations

### K1. Linked-list filament traversal is O(L)
`countLinkedMonomers` (FilSegment.java:3356-3364), `hydrolizeInFilaments` (3494-3506), `updateMonomerPositions` (3399-3422) walk the per-filament monomer linked list. With `noMonomersSimd` active (gliding-assay), the monomers aren't created so these are no-ops. With monomers active, each walk is O(L). Stats/reporting only; not per-step.

### K2. `setGlobalEnd1Node` / `setGlobalEnd2Node` walks chain
FilSegment.java:2330-2348. Both methods start with `if (true) { return; }` — dead code. Should be removed.

### K3. `validateEnd1Link` / `validateEnd2Link` runs every step
FilSegment.addLinkForces calls both at start. Each reads 5-6 fields on `end1Fil`/`end2Fil` for sanity. ~12 reads per FilSegment per step. At 1100 segs × 12 = 13K field reads/step. Trivial individually but pointless if links don't change every step — they only change on biochem-cadence (1/100 mechanics steps). Could be gated.

### K4. `removeArp23` linear search
FilSegment.removeArp23:1092-1109 scans `arp23s[arpChildCt]` linearly. arpChildCt ≤ 40. Trivial.

### K5. `tooCloseFilLinkLoc` linear search
FilSegment.java:1808-1817. Linear over `linkCt` (typically <maxXLinksOnSeg=20). Fine.

### K6. `alreadyLinkedTo` linear search
FilSegment.java:1346-1351. Linear over `linkedToCt`. Same scale. Fine.

### K7. FilLink active-list compaction
`setInactiveFilLinks` walks all FilLinks every step (BoxOfActin.java:1185). At dense scale this is ~all active FilLinks. With xLinks disabled (gliding-assay), filLinkCt=0 so it's free. With dense xLinks: ~one cleanup pass per step. Fine.

### K8. Split is O(L) but rare; join is similar
Already noted in §H4.

---

## L. Candidate SoA conversions (ranked by impact)

Rank by `frequency × Things-touched`:

### Per-step, per-Thing (hottest)
1. **`coord`** (x, y, z) — read every step for mesh/grid binning, motor binding, joint forces, output. Written every step in `moveThing`.
2. **`uVec`** (x, y, z) — read for joint orientations, force projections, body-frame transforms. Written every step.
3. **`yVec`** (x, y, z) — similar to uVec; written every step.
4. **`forceSum`** (x, y, z) — read in moveThing, written by every force-applying phase. Concurrent writes are §D2.
5. **`torqueSum`** (x, y, z) — same.
6. **`bTransGam` / `bRotGam`** — read in moveThing, only mutated on `calculateProperties` (length change or aeta change). Mostly stable.
7. **`bForceSum` / `bTorqueSum`** — scratch; computed inside moveThing. Could be locals, not fields.
8. **`randForces` / `randTorques`** — written in calcRandomForces, read in moveThing. Lifetime is one step.
9. **`bTransDiff` / `bRotDiff`** — set in calculateProperties, read in calcRandomForces. Stable.
10. **`transXTox` / `transxToX`** — recomputed in initialize (every step) from uVec/yVec. Read by every `XTox` / `xToX` call.

### Per-step subset
- **`linkedToCt`** (FilSegment) — modulates Brownian scale; updated in xLink phase.
- **`actAOn`** (FilSegment) — reset every step.
- **`onFil` / `bindTimer`** (MyoMotor) — state machine inputs.
- **`nucleotideState`** (MyoMotor) — biochem-cadence updates.
- **`linkCt` / `end1AxialF` / `end2AxialF`** — set during xLink/link force phases.

### Rare changes
- **`filAtEnd1` / `filAtEnd2`** — only on splits/joins/breaks.
- **`monomerCt`, `length`** — biochem cadence.
- **`removeMe`** — once per Thing lifecycle.
- **`motherFil`, `arpChildren[]`** — Arp2/3 events.

### SoA refactor priority order

For the GPU residency story to land (per GPU_STRATEGY.md), the per-step, per-Thing fields (1-8 above) need SoA backing. Iter2b/iter2c already created `MyoMotor.soaX/Y/Z/UX/UY/UZ/RodUX/RodUY/RodUZ/OnFil` and `FilSegment.soaEnd1X/Y/Z/End2X/Y/Z/UX/UY/UZ/FilID/NodeAtEnd2` — these are populated from Pt3D fields once per step and used by the grid fill + GPU pack.

**The cleanest refactor** is to invert the source of truth: SoA arrays become primary, Pt3D fields become accessors that read from the SoA via myThingNumber. That eliminates the per-step fillSoaArrays pass and the GPU pack walk, lands the GPU residency story, and lets CPU code keep working (with a small per-access cost that the JIT will optimize). The risk is that any CPU code holding a `Pt3D coord` reference and writing `coord.x = ...` directly bypasses the SoA — every such site needs to become `setX(...)`. JOURNAL §iter2d open item flags this as "touches every phase, large refactor". It's the right move.

---

## Top 10 opportunities (ranked by estimated impact)

1. **Per-thread force/torque accumulators (eliminate `synchronized` on every incForceSum)** — every Thing has `forceSync`/`torqueSync` locks held during the xLink, membrane, Brownian, joint, and node phases. With 16 threads writing concurrently to shared FilSegments (xLinks → both ends; nodes → forceSum), contention is real and produces documented non-determinism. Replace with per-thread `Pt3D[] threadForceSums[][numThreads][thingCt]` accumulators gathered at end of phase. Removes a critical bottleneck and a correctness issue at once. **Estimated: -10-20% wall at dense scale, plus determinism.**

2. **Parallelize and shrink the MotorBindGrid3D fill** — currently single-threaded at 28 ms/step at M=98K and ~73 ms/step at M=400K. Convert 4D `int[nX][nY][nZ][1000]` to flat CSR (offsets + contents), rebuild via stripe-parallel count-then-scan. Also benefits memory (528 MB → ~10 MB at boa10 scale). **Estimated: -10% wall + -200 MB heap at dense scale.**

3. **Replace `Math.acos` in joint torques with small-angle approximation** — at M=98K, the Myosin joints phase (4 acos/motor) costs ~22 ms/step. Most joints sit near equilibrium where acos is well-approximated by `sqrt(2*(1-dotVecs))` or a Padé form. **Estimated: -10 ms/step at M=98K = ~5% wall.**

4. **Hoist `fillSoaArrays()` into a parallel phase** — currently single-threaded (BoxOfActin.java:695-696), runs even on pure CPU. Use existing `ThingStepThreads`. Or remove entirely by inverting SoA source-of-truth (per §L). **Estimated: -20-25 ms/step at M=98K = ~10% wall.**

5. **Replace `Pt3D.ptDist` / `Pt3D.ptDistSqrd` `Math.pow(x,2)` with `x*x`** — 44 instances of `Math.pow` in hot paths. Even with JIT intrinsics, `x*x` is 2-5× faster. Also: defer `sqrt` everywhere a squared compare suffices (`checkToLink`, `checkNodeFilTipsCollision`, `lineSegmentIntersectTest`). **Estimated: -3-5% wall.**

6. **Move frame output to a background thread with snapshot** — `writeFrame` builds 32-132 MB JSON synchronously on the simulation thread every `toFileInterval` steps. With async output, the sim thread only pays a snapshot copy cost. Important for live WebSocket monitoring during long runs. **Estimated: at output frames, multi-second blocks become ~10 ms snapshot copies; aggregate impact depends on output cadence.**

7. **Inline RetObj instead of allocating per `checkToLink` call** — 6 Pt3D allocations per candidate pair × ~30K calls/run produces ~180K Pt3D allocs. Use thread-local pre-allocated RetObj. Same fix for `checkNodeFilTipsCollision` (5 Pt3D allocs per pair). **Estimated: -2-5% GC time at xLink-active scale.**

8. **Replace Marsaglia polar (rejection loop) with Box-Muller or Ziggurat in `UCircRnd`** — 21.5% rejection rate × 3 calls/Thing/step = wasted work. Cleaner Box-Muller: 2 nextDouble + 1 sqrt + 1 log + 1 sincos per pair, no branching. The GPU kernel already uses Box-Muller. **Estimated: -3-5% Brownian phase = ~1% wall.**

9. **Generalize MotorBindGrid3D as a single 3D grid for all four queries (motor-fil, fil-fil, node-node, node-fil-tip), retire 2D Mesh** — Mesh's Z-ignored 2D layout produces false-positive candidates in 3D networks. Tagged contents (entity-type + ID per slot) let one grid serve all queries. Saves ~3 separate fill phases, halves memory, fixes 3D false-positive rate. **Estimated: -5-10% wall at boa10/membrane scale (low impact at gliding-scale).**

10. **Replace nested `double[3][3]` transXTox/transxToX with flat `double[9]`** — every step, every Thing, multiple times: body↔fixed transforms chase 3 pointers through 2 nested arrays. Flat layout enables JIT bounds-check elision and SIMD. Combined with deriving zVec inline from uVec×yVec (already done in the GPU kernel), the `transMat()` call can disappear. **Estimated: -3-5% on the body-frame-heavy phases (Brownian, moveThing).**

### Honorable mention (medium-low individually, large in aggregate)
- Cache `Env.X.getValue()` results at the top of `step()`/`moveThing()`/`addLinkForces()` rather than re-reading per-iteration. 180 grep hits in FilSegment alone.
- Pre-compute `1.0/Env.deltaT.getValue()` and `1.0/Env.brownianDeltaT.getValue()` once per step.
- Convert per-Thing `MersenneTwisterFast` (2.5 KB × N) to per-thread MTF or Wang-hash. At M=400K, this is ~1 GB of state.
- Cache `Env.terminating` reads in tight loops.
- Drop dead-code methods (`setGlobalEnd1Node`/`setGlobalEnd2Node` with `if (true) return;`, the commented-out filSegCollisions/nodeCollisions in FilSegment).
- Remove `Math.pow(x, 3)` in `calculateProperties` → `x*x*x`.

---

## What I did NOT analyze in depth

- DeflectionTuner v15-v25 (separate code paths, not in main hot loop except in benchmark mode).
- Bug/Listeria geometry (Bug.java is 776 lines, complex, but N=1 per run so out of hot loop).
- ActA (gliding-assay = inactive).
- `MyosinDimer.updateMyosins`, `ProteinNode.updateMyosins` — read but not deep-profiled. Both contribute to MyoDimer phase (~4.6% of wall).
- File parsing (one-time).
- Simularium JSON path (FileOps.writeSimJSonsFrame) — duplicate of ThreeJSWriter for Simularium viewer; if both are enabled per run, doubled output cost.

JOURNAL_ARCHIVE.md was not read (the recent JOURNAL.md covers the relevant performance history; the archive is older context). If specific historical details about prior Brownian-on-CPU vs other architecture choices matter, that's where to look.
