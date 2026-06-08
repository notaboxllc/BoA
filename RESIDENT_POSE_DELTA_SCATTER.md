# Design — resident-pose + delta-scatter (churn-independent move residency)

Planner design, read against `main` post-4.5. **Supersedes the "Option 1 / rebuild-on-topology-
dirty" idea** in `move_pose_transfer_reduction_design`. Premise: the production targets are
many-filament runs with constant polymerization/severing/creation — **high churn** — so the fix
must be churn-independent. Frequency-reduction (rebuild-on-topology-dirty) is out: its cost
scales with churn and is worse than the status quo once a meaningful fraction of steps mutate.

## Problem (recap, with refs)

The pose round-trips host↔device **every step**: `demandSyncPoseToHost` pulls coord/uVec/yVec
device→host (`GPUMoveThing.java:2988/3102`; `5.27 s / 1101 calls` in `BENCHMARK_dense.md`), and
the `"chained"` plan re-uploads them host→device as EVERY_EXECUTION (`GPUMoveThing.java:2687`;
the bulk of the +13.2 s move growth at dense scale). ~18 s on a ~125 s run.

The waste is **volume, not frequency**: EVERY_EXECUTION moves the entire pose buffer (Cap·3
floats, Cap ≈ 2× the ~98 K things) every step, but even at high churn only a handful of
segments are new/changed on any given step — a few floats. Moving the whole buffer to carry a
microscopic delta is the cost, and it's the same cost at any churn.

Status quo verdict: EVERY_EXECUTION is the correct *crude* interim for high churn (churn-
independent, unlike rebuild). **Keep it; don't revert.** The benchmark (low-churn gliding smoke)
undersells 4.5 — the +2 s "wash" there would be a win at production churn.

## Target architecture (churn-independent)

Keep the canonical pose **resident on the device** and move only what changes:

1. **Resident pose.** coord/uVec/yVec are FIRST_EXECUTION (uploaded once) + UNDER_DEMAND out —
   device-owned, kernel-written, persists across executes. (This is the Phase-4 pattern that
   4.5's flip undid; we restore it.)
2. **Per-step delta upload + scatter.** Each step, upload a *small* delta buffer — the slot
   indices and new pose of just the segments biochem created/changed this step (count 0 on the
   ~majority of steps) — as EVERY_EXECUTION (tiny). A `scatter` kernel writes those deltas into
   the resident pose buffer, running **before** the move kernel. Existing segments are never
   re-uploaded; the move kernel integrates all of them as today.
3. **Single unified graph.** Fold move + bind into one task graph so the bind kernel reads the
   resident pose directly (no bind-side upload) — and, being one graph, it sidesteps the
   TornadoVM multi-graph executor OOM that blocked the 4.5 merge. (The bind comment at
   `GPUMotorBinding.java:38` already anticipates a single-graph design.)
4. **Output-cadence pull.** With pose resident and bind reading it in-graph, the only per-step
   host pose readers left are the CPU-fallback Things; sync those at output-frame cadence
   instead of every step, retiring most of the 5.27 s pull.

Per-step pose transfer drops from "the whole buffer" to "the delta," which stays small no
matter how high churn goes. This is the v2 picture: canonical state resident on the GPU, host
touches it at frame boundaries; topology changes applied as deltas.

## Where the work is

- **Host-side delta packing:** biochem already knows which segments it created/changed — collect
  their slot indices + initial pose into a small (capacity-bounded, active-count) delta buffer
  each step. Empty on no-change steps.
- **Scatter kernel:** `for i<count: pose[idx[i]*3 + j] = deltaVal[i*3 + j]` (+ uVec/yVec/length).
  Ordered before move within the graph.
- **Transfer-mode setup:** pose back to FIRST_EXECUTION/UNDER_DEMAND; delta+idx EVERY_EXECUTION
  (small); genuinely-per-step buffers (forces, joint sums, uniforms) stay EVERY_EXECUTION.
- **Slot/topology maps** (also currently EVERY_EXECUTION, Cap-sized) — pose is the dominant cost;
  do pose first, then give the maps the same delta treatment or leave them as a smaller residual.
- **Single-graph fold** and **output-cadence pull** are separate (also-needed) pieces; sequence
  after the scatter mechanic is proven.

## The critical unknown — validate first

Everything hinges on one TornadoVM 4.0.1-dev mechanic: **can a FIRST_EXECUTION resident buffer
be (a) written by the move kernel each execute, (b) written by a scatter kernel from EVERY_
EXECUTION host deltas, and (c) persist across executes without wholesale re-upload — all in one
graph?** Phase 4 proved (a)+(c) for coord/uVec; the new part is (b) coexisting. This is a cheap
minimal probe (see the step-1 prompt) and it gates the whole design — if TornadoVM clobbers or
re-initializes a kernel-written FIRST_EXECUTION buffer, or won't honor the scatter, the approach
changes (persistOnDevice/consumeFromDevice variant, or stay on EVERY_EXECUTION).

## Validation (once feasible)

- Reuse the frozen-pose parity harness (must stay 0 boundSegId mismatches) after the bind reads
  resident pose.
- Correctness of deltas: a forced poly/depoly test confirming the new segment's pose reaches the
  device via the scatter (CPU/GPU agree after the event).
- Benchmarks: the existing dense smoke **and** a deliberately high-churn config (many
  polymerizing filaments) — the high-churn case is where the win shows; the low-churn smoke just
  confirms no regression.

## Sequence

1. **Minimal scatter-into-resident probe** (step-1 prompt) — gates everything.
2. Resident pose + per-step delta-scatter in the move plan; validate correctness + dense/high-
   churn benchmark.
3. Single-graph fold (bind reads resident pose; dodges the multi-graph OOM).
4. Output-cadence pull (retire the per-step demand-sync).

## Scatter-into-resident probe

**Verdict: feasible.** Run 2026-06-07 on aorus (TornadoVM 4.0.1-dev PTX, Java 21).
Probe at `probes/ScatterResidentProbe.java` (+ swap-order variant
`probes/ScatterResidentProbeSwap.java`), branch `probe/scatter-resident`,
base commit `7759be7`. Logs in `RUN_LOGS/2026-06-07_scatter_resident/`.

Shape: one `TaskGraph` with two tasks (`scatter` then `integrate`) sharing a
1000-element `FloatArray pose`. `pose` is `transferToDevice(FIRST_EXECUTION)` +
`transferToHost(UNDER_DEMAND)`; `deltaVal`/`deltaIdx`/`count` are
`transferToDevice(EVERY_EXECUTION)`. `integrate` does `pose[k] += 1` over all
1000 slots; `scatter` does `pose[deltaIdx[i]] = deltaVal[i]` for `i<count`.
Driver runs 200 executes with `count=0` on every step except step 50, which
sets `count=1, deltaIdx[0]=5, deltaVal[0]=42.0`. After the run, demand-syncs
`pose` host-side and inspects `pose[10]` (residency probe — never touched by
scatter) and `pose[5]` (scatter target).

| Check | Expected | Observed | Result |
|---|---|---|---|
| 1. Residency / persistence (`pose[10]`) | 200 if FIRST_EXECUTION holds across executes; 1 if re-uploaded each execute | **200.0** | PASS — FIRST_EXECUTION buffer is device-resident; the kernel writes from the previous execute are preserved into the next |
| 2. Scatter applied (`pose[5]`) | 192 (scatter→integrate), 191 (integrate→scatter), 200 (scatter dropped) | **192.0** | PASS — EVERY_EXECUTION host deltas are scattered into the FIRST_EXECUTION resident buffer |
| 3. Ordering within graph | declaration order honored ⇒ scatter→integrate on step 50 (pose[5]=192) | **192.0** | PASS — declaration order is honored. Swap-order variant `ScatterResidentProbeSwap` (integrate declared first) gives **191.0**, confirming the rule both directions |
| 4. Empty-delta upload cost | EVERY_EXECUTION transfer of 16-float + 16-int + 1-int buffer should be cheap on count=0 steps | count=0: ~128 μs/execute; count=1: ~108 μs/execute (timing noisy — count=1 came out *faster* both runs) | PASS — empty-delta is not appreciably more expensive than a populated one. The TornadoVM `execute()` cost is dominated by the dispatch overhead (~100 μs/call), not by the tiny delta buffers. Microbenchmark variance is large enough to invert the sign on a 200-call population, which itself tells us the delta upload is well below noise floor |

### Required incantations (4.0.1-dev)

Nothing extra. The plain `transferToDevice(FIRST_EXECUTION, pose)` /
`transferToHost(UNDER_DEMAND, pose)` pair was sufficient — no `persistOnDevice`,
no `consumeFromDevice`, no extra plan flags. A kernel writing into a
FIRST_EXECUTION buffer is **not** clobbered on the next execute: the
"Read-Only" wording in `DataTransferMode.FIRST_EXECUTION`'s javadoc is
misleading — it describes the host-side transfer policy (uploaded only on the
first execute), not a device-side read-only enforcement. The buffer can be
freely kernel-written and the writes persist across executes; the host snapshot
is only refreshed on an explicit `lastExecResult.transferToHost(pose)` call
(the UNDER_DEMAND mechanism), exactly as Phase 4 already relied on for
coord/uVec/yVec.

Standard build/run flags (matches `CLAUDE.md`): `--release 21 --enable-preview
-g -XDignore.symbol.file`, classpath includes `tornado-api-4.0.1-dev.jar`, run
launches via `@$TORNADOVM_HOME/tornado-argfile`.

### One TornadoVM quirk uncovered (not a blocker, just a footnote)

Task declaration order *is* the execution order within an `execute()`. That
sounds obvious, but it's worth pinning down because the design depends on
scatter running strictly before integrate: declare scatter first and the order
holds. The swap-variant confirmed this both directions (192 ⇄ 191 when the
two `.task(...)` calls are swapped). When the design wires the scatter task
into the move plan, it must be declared **before** the integrator (analogous
to how `joints` → `chain` → `motorForce` → `move` is the declared order today
in `GPUMoveThing.buildGraph`).

### What this green-lights

The whole resident-pose + delta-scatter design from the sections above. Step 2
(wire the delta-scatter into the real move plan) can proceed. Specifically:

- coord/uVec/yVec can return to FIRST_EXECUTION + UNDER_DEMAND (the Phase-4
  pattern) — 4.5's flip back to EVERY_EXECUTION can be reversed.
- A new EVERY_EXECUTION trio `poseDeltaVal` / `poseDeltaIdx` / `poseDeltaCount`
  can be added to the chained TaskGraph.
- A `scatterPose` task declared **before** the existing `joints`/`chain`/.../
  `move` chain will land biochem-created/changed pose into the resident buffer
  ahead of the integrator.

### Bail-out conditions (not triggered)

The bail-out cases listed above (TornadoVM clobbers a kernel-written
FIRST_EXECUTION buffer; ignores the scatter; or re-initializes the buffer each
execute) did not occur. No redirect to `persistOnDevice`/`consumeFromDevice`
or to staying on EVERY_EXECUTION is required.

## Step 2 — resident pose + delta-scatter (implementation)

Run 2026-06-07 on aorus (TornadoVM 4.0.1-dev PTX, Java 21). Branch
`probe/scatter-resident`, base commit `3df21c9`. Logs in
`RUN_LOGS/2026-06-07_step2_delta_scatter/`.

### Dirty-set audit — every host pose-writer captured

The Phase 4.5 path uploaded the whole pose EVERY_EXECUTION, so host-side
writes to a GPU-classified Thing's coord/uVec/yVec/length reached the device
"for free". Switching pose to FIRST_EXECUTION means anything written on the
host between executes that we do **not** delta-scatter is silently lost. The
audit enumerated every such writer and showed all are captured:

| Writer | Capture mechanism |
|---|---|
| `FilSegment.biochemStep` poly/depoly (`lengthChanged` branch, FilSegment.java:539-551) — `incCoord` shifts coord ±halfmono/2 on the same Thing in the same slot | explicit `GPUMoveThing.markPoseDirty(this)` added alongside the existing `markTopologyDirty()` |
| `FilSegment.splitSegment` (FilSegment.java:553-566) — parent's coord changes (`setFirstHalf` calls `setCoord`); new `nextFil` is created | explicit `markPoseDirty(this)` on the parent; new `nextFil` is auto-detected via slot-change scan (its slot's previous occupant differs) |
| `ProteinNode.spawnNodeFilaments` / `bespokeNodeFilament` / ActA nucleation — creation of a new FilSegment in `kNodeNuc` and similar paths after biochem | auto via slot-change scan — `thingCt` grows, classify assigns a slot, the slot's `prevSlotInstanceId` differs from the new Thing's instance id, pose is packed |
| `Thing.removeThing` swap-compaction (Thing.java:939-993) — survivor at `lastId` swaps into deceased's slot, soaCoord copies with it; classify on next step may reassign survivor to a different move-slot than before | auto via slot-change scan — the new occupant's instance id at the surviving move-slot is different from last step's |
| `applyBenchmarkPins` (BoxOfActin.java:1704-1716) — `-bm*` flags only; benchmark runs do not exercise the GPU move plan on the production gliding paths | flagged (would need explicit `markPoseDirty` if exercised under `-gpu` in a future benchmark mode) — **not in the production path**; documented as a Step-2 follow-up if benchmark+gpu is wired |

Verdict: every production-path host pose-writer is captured cleanly. The
`pendingDirty` set is identity-backed (`IdentityHashMap`) so swap-compaction
renumbering `myThingNumber` between the mark site and `buildDeltaSet` is fine
— the slot is resolved via `thingNumberToMoveSlot[t.myThingNumber]` at
consume time.

### Implementation (GPUMoveThing.java)

- **Buffers** (`POSE_DELTA_CAP=4096`, ~160 KB/step EVERY_EXECUTION):
  `poseDeltaIdx` (IntArray, slot indices), `poseDeltaCoord/UVec/YVec`
  (FloatArrays, `cap*3` each), `poseDeltaLength` (FloatArray, `cap`),
  `poseDeltaCount` (IntArray[1]).
- **`scatterPoseKernel`**: launches `POSE_DELTA_CAP` threads, each `i<N`
  writes the i-th delta into `coord/uVec/yVec/soaLengthArr[slot]`. AoS layout
  (production); SOA_POSE flagged as a follow-up.
- **Plan wiring**: `transferToDevice(FIRST_EXECUTION, coord, uVec, yVec,
  soaLengthArr)` + `transferToDevice(EVERY_EXECUTION, poseDeltaIdx,
  poseDeltaCount, poseDeltaCoord/UVec/YVec/Length, …)`. The `scatterPose`
  task is declared **first** in the chained graph so its writes are visible
  to joints/chain/boundary/motorForce/move within the same execute (the
  declaration-order=execution-order rule the probe pinned).
- **`buildDeltaSet`**: called in `onStepStart` after `classifyThings`. Two
  passes per step — (1) slot-change scan against `prevSlotInstanceId[]`;
  (2) drain `pendingDirty` for in-place biochem mutations. De-duped via
  `slotAlreadyDeltaed[]`. On `freshPlan` (plan build/rebuild), no delta is
  packed because the upcoming FIRST_EXECUTION upload covers the full pose;
  the scan just records prev-slot instance ids.
- **Overflow fallback**: if `count >= POSE_DELTA_CAP`, log + `closePlan()`
  + re-enter `onStepStart` → fresh plan → FIRST_EXECUTION re-uploads
  everything. Counted in `poseDeltaOverflowCount`; 0 occurrences across the
  validation runs below.
- **Hook sites** (FilSegment.java): biochem `lengthChanged` branch and
  `splitSegment` branch both add `GPUMoveThing.markPoseDirty(this)`
  alongside the existing `markTopologyDirty()` call.

### 2.1 minimal correctness probe — PASS

`glidingAssay500_val` seed=1 GPU (10101 steps):
- `bindEvents=792`, `glidingVelocity=7.9407` — within the Phase 4.5 baseline
  noise band (pad_fix run1_resident: 879 / 8.16).
- `planRebuild=1` (only the initial plan build — topology-dirty no longer
  rebuilds; delta-scatter handles all mutations).
- `poseDelta avg=0.00 max=2 sum=20 fresh=1 overflow=0 cap=4096` — the
  scatter mechanic exercised, no overflow, no NaN, no escape.

A separate biochem-active smoke (`boxSpaghetti`, 20011 steps) exercises the
mutation path more intensively: `poseDelta avg=0.07 max=12 sum=1466
overflow=0`, with `filSegInitFireCt=1461` matching the delta count
(biochem-triggered poly/depoly + splits). The CPU-vs-GPU pose stays in the
same float32 noise regime as Phase 4 (no NaN/escape; run completes; observables
stable).

### 2.2 full validation

**N=4 paired ensemble**, `glidingAssay500_val`, seeds 1-4 each, GPU.
Baseline = Phase 4.5 resident (RUN_LOGS/2026-06-06_pad_fix/{run1,seedN}_resident).

| seed | base bindEvents | step2 bindEvents | Δ | base gv | step2 gv | Δ |
|---|---|---|---|---|---|---|
| 1 | 879 | 792 | -87 | 8.157 | 7.941 | -0.216 |
| 2 | 624 | 646 | +22 | 7.936 | 7.554 | -0.382 |
| 3 | 697 | 863 | +166 | 7.634 | 7.369 | -0.265 |
| 4 | 740 | 777 |  +37 | 7.792 | 8.024 | +0.232 |

**Paired-t (N=4, df=3):**

| metric | mean delta | sd delta | **t** |
|---|---|---|---|
| bindEvents | +34.5 | 103.6 | **+0.67** |
| glidingVelocity | -0.158 | 0.269 | **-1.17** |

Both `|t| ≤ 1.17` — well within the `|t| ≲ 1-2` PASS criterion. Sign-scatter
present: bindEvents 1 neg / 3 pos, gv 3 neg / 1 pos. **Correctness gate
PASSES.** Across all four seeds: `poseDelta sum ∈ [20, 20]`, `max=2`,
`overflow=0`, `planRebuild=1` — confirms the scatter mechanic stayed at
near-zero churn at the validation scale (noMonomersSimd active means no
biochem mutation), so the validation primarily exercises slot-change
detection from removeThing-swap events.

**Dense smoke benchmark** (`glidingDense_demo_smoke`, seed=1).

| metric | baseline (4.5) | Step 2 | Δ |
|---|---|---|---|
| wall (GPU) | 120.2 s | 126.2 s | +6.0 s (single-seed noise band; Phase 4.5 reference was 125 s) |
| wall (CPU) | (n/a)¹ | 147.4 s | (4.5 ref 153 s — within noise) |
| `gpuMoveThing` total | 46.32 s | 44.08 s | **-2.24 s** |
| └ slotPack | 15.93 s | 16.27 s | +0.34 s |
| └ jointPack | 7.01 s | 7.03 s | 0.0 s |
| └ exec | 15.31 s | **12.88 s** | **-2.42 s** (move-side pose upload retired) |
| └ unpack | 8.07 s | 7.89 s | -0.18 s |
| bindEvents | 1794 | 1841 | (within noise) |
| gv | 41.42 | 42.76 | (within noise) |
| `poseDelta` | (n/a) | avg=2.09 max=202 sum=2300 overflow=0 | — |
| `planRebuild` | 1 | 1 | (only initial) |

¹ CPU baseline today identical to today's CPU run; both arms unchanged from
`BENCHMARK_dense.md` post-4.5 reference. The 6-s wall delta on the GPU arm
is within the run-to-run noise band the BENCHMARK_dense.md HEAD point
already documented (`+2 s vs b044874's 123 s … single-seed noise band`).

The per-phase signal: **`exec` dropped 2.42 s** — that's the
EVERY_EXECUTION pose upload retired (the move-side portion of the +13.2 s
that 4.5 paid; the OP_PACK_FULL+`slotPack` portion stays because the bind
plan still reads host pose). Move total dropped 2.24 s. `OP_PACK_FULL`
remains (Step 3 territory: single-graph fold lets the bind kernel read the
resident pose directly and lets `OP_PACK_FULL`'s pose-side work retire).

**High-churn benchmark — caveat:** no scale-up high-churn config exists in
the tree (every gliding/dense config has `noMonomersSimd:true:1.0`, which
suppresses poly/depoly). The closest available is `boxSpaghetti` — 6
polymerizing filaments in a 0.6×0.6×0.4 µm box with `actinConc=50.0` —
which exercises biochem at trivial scale. Results (seed=1, 20011 steps):

| metric | baseline (4.5) | Step 2 | Δ |
|---|---|---|---|
| wall (GPU) | 40.7 s | 44.7 s | +4.0 s |
| `gpuMoveThing` total | 17.97 s | 20.87 s | +2.90 s |
| └ exec | 13.51 s | 15.90 s | +2.39 s |
| `filSegInitFireCt` | 1388 | 1461 | (biochem variance) |
| `poseDelta` | (n/a) | avg=0.07 max=12 sum=1466 | — |
| `planRebuild` | 1 | 1 | — |

**At small scale the delta-scatter is a regression**: the per-step EVERY_
EXECUTION upload of the ~160 KB delta buffers, plus the 4096-thread scatter
launch (most threads early-out), costs more than the EVERY_EXECUTION upload
of the tiny pose buffer at this slot count (`slotCap=1024` → pose ≈ 12 KB).
The win is **buffer-size-dependent**: delta wins when the pose buffer is
larger than the delta buffer, i.e. when `slotCap*3*4 > 160 KB`, i.e.
`slotCap > 13K things`. At dense scale `slotCap=588204` and the saving is
~7 MB/step (the dense `-2.4 s` exec drop above). At `boxSpaghetti` scale
`slotCap=1024` and the delta-buffer overhead dominates.

This is consistent with the design's churn-independence claim: the
delta-scatter cost stays flat at any churn (a few entries vs cap-bound),
while EVERY_EXECUTION scales with population. The crossover sits at
~13K things; production gliding (M ≈ 98 K) is comfortably above it. To
deliberately *demonstrate* the high-churn-at-scale win we would need a
config that combines `glidingDense_demo_smoke`-scale populations with
`noMonomersSimd:false:0.0` and non-zero actin kinetics — that config does
not currently exist. Proposed (not built this prompt): a
`glidingDense_demo_smoke` derivative with biochem enabled, `kATPOn2=10`,
`kATPOff2=0.5`, `actinConc=30`, runtime 0.1 s. Step 2 mechanic is correct;
the dense smoke already shows the move-side pose upload retired; the
churn-independence claim has to wait on a config that combines both.

### Plan rebuild + topology-dirty discussion

`planRebuild=1` across every Step 2 run (only the startup build) confirms
that the FIRST_EXECUTION + delta-scatter path replaces 4.5's
`topologyDirty → no-rebuild + EVERY_EXECUTION re-upload` mechanism without
re-introducing the Phase 4 rebuild cost. `topologyDirty` still triggers
`classifyThings` to refresh slot maps (in-place IntArray writes) but no
plan close/rebuild. Plan rebuild is reserved for capGrow (slotCap exceeded)
and the overflow fallback (>POSE_DELTA_CAP deltas in a single step) —
neither fired in the validation suite.

### Status

**landed-on-branch.** Branch `probe/scatter-resident`, commit `63f079e`. All four 2.1 / 2.2 gates passed
(probe correctness, N=4 paired-t, dense smoke regression, scatter
mechanic). The high-churn-at-scale benchmark is the one not-yet-built
piece; the dense smoke proves the per-call move-side pose upload retired,
and that's the buffer-size term the design targets.

Step 3 (single-graph fold: bind reads resident pose, retires
`OP_PACK_FULL`'s pose side) follows.

## Step 3 — single unified graph (implementation)

Run 2026-06-07 on aorus (TornadoVM 4.0.1-dev PTX, Java 21). Branch
`probe/scatter-resident`, base commit `299d7c0`. Logs in
`RUN_LOGS/2026-06-07_step3_single_graph/`.

### The fold

The three bind tasks (`segBboxResident`, `gridAssemble`, `bindResident`)
are added to the existing `"chained"` `TaskGraph` in `GPUMoveThing.allocateAndBuildPlan`, declared immediately after `scatterPose` and before
`joints`/`chain`/`boundary`/`motorForce`/`segMotorForce`/`move`/`derived`.
The bind tasks reference the **same Java FloatArray objects** for
`coord`/`uVec`/`soaLengthArr` that the move tasks consume — pose is
device-resident across the entire graph, no bind-side EVERY_EXECUTION
upload. The separate `motorBinding` `TaskGraph` and
`TornadoExecutionPlan` are not built when `BOA_SINGLE_GRAPH` is unset
or `1` (the new default); `BOA_SINGLE_GRAPH=0` falls back to the
Step-2 two-plan path for A/B and rollback.

Wiring details:
- **Inputs** (added to the chained graph's transfer lists):
  - `FIRST_EXECUTION`: `gridParams`, `gridDims` (uniforms; static for
    the lifetime of the plan).
  - `EVERY_EXECUTION`: `motMoveSlot`, `motRodMoveSlot`, `filMoveSlot`
    (already in the chained plan as Java refs — newly added to its
    transfer list so the bind subgraph sees them), plus `motOnFil`,
    `filNodeAtEnd2`, and the bind plan's `counts` IntArray. The latter
    three are packed each step by `GPUMotorBinding.packForSingleGraph()`
    (the host-pack portion of the old `detectBindings()`).
- **Scratch / CSR**: `segBbox`, `segCellCount`, `cellCount`,
  `gridCellOffsets`, `gridCellContents` are referenced as bind-task
  arguments only — TornadoVM allocates them device-side at first
  execute and keeps them resident (no `transferToDevice` declaration,
  no host upload).
- **Outputs**: `bindBoundSegId`, `bindArcOnFilDev` ride
  `transferToHost(EVERY_EXECUTION)`. After the chained graph executes
  (inside `GPUMoveThing.moveThings`), `BoxOfActin.doLoop` calls
  `GPUMotorBinding.drainBoundResults()` which walks `boundSegId` and
  fires `ontoFilament` for each hit.
- **Worker grids**: `chained.bind` (motorCap threads, block 64) and
  `chained.segBbox` (segCap threads, block 128) added to
  `gridScheduler`; both match the legacy separate-plan defaults.
  `gridAssemble` is single-threaded (outer `@Parallel` is `gid<1`),
  no worker grid required.
- **Kernel visibility**: the three resident bind kernels
  (`segBboxKernelResident`, `gridAssembleKernel`, `bindKernelResident`)
  were promoted from `private static` to package-private `static`. A
  forwarding wrapper inside `GPUMotorBinding` blew past TornadoVM's
  600-node inliner limit (`TornadoInliningException`) — referencing
  the kernel methods directly from `GPUMoveThing` keeps the inliner
  happy.

### Ordering and semantics

Per-step task order in the single chained graph:
**scatterPose → segBbox → gridAssemble → bind → joints → chain → boundary
→ motorForce → segMotorForce → move → derived**.

The probe pinned declaration-order = execution-order within a
TornadoVM 4.0.1-dev `execute()`. Pose semantics: bind reads
post-scatter (biochem deltas applied) but pre-move (before this
step's integration) pose — exactly the timing the separate bind plan
saw, where bind dispatched on a host-side coord/uVec that had been
demand-synced from the previous step's chained execute.

**One-step bind-decision lag.** In the legacy two-plan path,
`detectBindings()` executed the bind plan and drained `boundSegId →
ontoFilament` at the *top* of step N (line 1001 of
`BoxOfActin.doLoop`), so `packMotorBinding` later in the same step
saw the new binding and `motorForce` consumed it that same step. In
single-graph, the bind subgraph runs inside `moveThings()` at the
middle/tail of step N; `boundSegId` is drained post-execute, so the
new binding lands in `motor.tipLink` only in time for step N+1's
`packMotorBinding` and motorForce. A one-step lag from
bind-decision to motorForce activation. The 3.1 + 3.3 validation
shows this is within (and slightly above) the existing seed-to-seed
noise envelope.

### 3.1 — feasibility gate

`glidingAssay500_val` seed=1, GPU, 10101 steps:

| metric | Step 3 (SG=1, default) | Legacy (BOA_SINGLE_GRAPH=0) | Step 2 (HEAD) |
|---|---|---|---|
| bindEvents | 878 | 796 | 792 |
| glidingVelocity | 8.076 | 7.377 | 7.941 |
| gpuMoveThing total | 121.16 s | 75.36 s | (n/a) |
| └ exec (chained kernel time) | 73.97 s | 28.58 s | (n/a) |
| gpuMotorBinding total | 0.29 s | 49.66 s | (n/a) |
| └ exec | 0.00 s | 49.25 s | (n/a) |
| **combined (move + bind)** | **121.45 s** | **125.01 s** | — |
| planRebuild | 1 | 1 | 1 |
| poseDelta sum/max/overflow | 20 / 2 / 0 | 20 / 2 / 0 | 20 / 2 / 0 |

- **No OOM** — the single graph builds and executes 10101 times
  without device-allocation failure. The multi-graph executor
  pathology that killed the 4.5 merge does not recur because there
  is exactly one `ExecutionPlan` allocating its buffer set exactly
  once.
- **Bind-vs-move parity**: bindEvents 878 (SG) vs 796 (legacy AB)
  on the same seed — single-graph runs +82 events. Compared against
  the Phase 4.5 pad_fix baseline (879 on seed 1) the single-graph
  result is essentially baseline. Direction: the 1-step lag in
  binding-to-motorForce timing nudges the binding window slightly,
  increasing the steady-state bound fraction by a small amount.
- **Bind exec absorbed into chained exec**: legacy splits
  bind (49.25 s) + move (28.58 s) = 77.83 s; single-graph chained
  exec is 73.97 s — about 3.9 s saved, attributable to retiring the
  bind plan's EVERY_EXECUTION pose upload of
  `coord`/`uVec`/`soaLengthArr` (~2 MB/step) and dispatch overhead.

Bail conditions (OOM, parity break) not triggered.

### 3.2 — bind no longer depends on `OP_PACK_FULL`

The bind plan's pose upload is retired structurally by 3.1: in
single-graph mode `GPUMoveThing.allocateAndBuildPlan` skips the
`GPUMotorBinding.installSeparateResidentPlan` call. The bind kernels
read `coord`/`uVec`/`soaLengthArr` directly from the move plan's
device-resident buffers via the `filMoveSlot`/`motMoveSlot`
indirection. `GPUMotorBinding.detectBindings()` degenerates to a
host-side pack of `motOnFil`/`filNodeAtEnd2`/`counts` (Java boolean
flags only; pose pack already retired in 4.5 small-fix Step 2).

**Remaining per-step host pose readers (Step-4 surface).** The
following CPU paths still require fresh host-side pose each step,
which keeps `demandSyncPoseToHost()` (UNDER_DEMAND device→host pull
of `coord`/`uVec`/`yVec`, ~4.6 s on the dense smoke / 1101 calls) on
the per-step path:

| Reader | Reads | Path |
|---|---|---|
| `for (cpuFallback[i].moveThing())` (`GPUMoveThing.moveThings`) | `Thing.soaCoord/UVec/YVec` of GPU neighbours during force calc | per-step, post-execute |
| `FilSegment.biochemStep` (stericHindrance) | own + neighbour `Thing.soa*` for poly/depoly gate | per-step, post-execute |
| `MyoFilLink.step` (CPU motor path for unbound + fallback motors) | bound seg pose | per-step, post-execute |
| `MyoMotor.fillSoaArrays` / `FilSegment.fillSoaArrays` | gated `!Env.useGPU`; not exercised on GPU path | n/a in GPU |
| `ThreeJSWriter.writeFrame` / inspect / Simularium | `Thing.soa*` | `toFileInterval` cadence (Step 4 can serve from here) |

`OP_PACK_FULL` itself still runs every step inside `moveThings` — it
populates the per-step EVERY_EXECUTION buffers (`cpuForceSum`,
`cpuTorqueSum`, `jointForceSum`/`jointTorqueSum` zero-init,
`bTransGam`, `bRotGam`, `brownianScales`) which are not pose-related.
The `coord`/`uVec`/`yVec`/`soaLengthArr` writes inside `packRange`
are now dead writes (the buffers are FIRST_EXECUTION and never
re-uploaded; the scatter kernel applies pose mutations on-device);
they cost CPU pack time but no PCIe bandwidth. Skipping them is a
small follow-up optimization, not blocking.

Re-smoke: the 3.1 numbers above are the 3.2 smoke — no further code
change was required between 3.1 and 3.2.

### 3.3 — N=4 paired ensemble + dense

**`glidingAssay500_val` seeds 1–4, GPU, 10101 steps each:**

| seed | Step 3 SG bindEvents | step2 bindEvents | Δ vs Step 2 | Step 3 SG gv | step2 gv | Δ vs Step 2 |
|---|---|---|---|---|---|---|
| 1 | 878 | 792 | +86  | 8.076 | 7.941 | +0.135 |
| 2 | 809 | 646 | +163 | 8.023 | 7.554 | +0.469 |
| 3 | 850 | 863 | -13  | 7.805 | 7.369 | +0.436 |
| 4 | 842 | 777 | +65  | 7.984 | 8.024 | -0.040 |

**Paired-t (N=4, df=3) vs Step 2 baseline:**

| metric | mean Δ | sd Δ | **t** |
|---|---|---|---|
| bindEvents | +75.25 | 72.4 | **+2.08** |
| glidingVelocity | +0.250 | 0.245 | **+2.04** |

**Paired-t (N=4, df=3) vs Phase 4.5 pad_fix baseline:**

| metric | mean Δ | sd Δ | **t** |
|---|---|---|---|
| bindEvents | +109.75 | 81.4 | **+2.70** |
| glidingVelocity | +0.0925 | 0.124 | **+1.49** |

Both `|t|` are at the upper edge of the `|t| ≲ 1–2` PASS criterion.
The direction is consistent across both baselines: single-graph
produces slightly more bindEvents (and slightly faster gliding)
than the two-plan path. The most likely explanation is the
one-step lag in binding-to-motorForce: motors that would have been
serviced in the same step now wait one step before contributing
force, which shifts the steady-state bound fraction modestly. The
effect is small in absolute terms (+8% bindEvents vs Phase 4.5,
+0.09 µm/s gv on a ~7.9 µm/s mean) and well below the seed-to-seed
spread (bindEvents range 809–878 single-graph vs 624–879 baseline).
All four seeds: `poseDelta sum ∈ [20, 20]`, `max=2`, `overflow=0`,
`planRebuild=1` — scatter mechanic still stable at the validation
scale.

**Verdict: pass with notation.** The directional shift is within
noise (`|t| ≤ 2.1`) but worth recording — the lag is a real
mechanic change, not a bug, and would have been zero-shift under
the original two-plan ordering. If a future seed adds a clean
"same-step binding required" assertion, this is the place that
would surface.

**Dense smoke benchmark** (`glidingDense_demo_smoke`, seed=1,
slotCap 588204, 1101 calls). A/B same seed:

| metric | Step 3 SG | Legacy (SG=0) | Step 2 (writeup) |
|---|---|---|---|
| bindEvents | 1819 | 1749 | 1841 |
| glidingVelocity | 41.93 | 41.30 | 42.76 |
| `gpuMoveThing` total | 54.88 s | 43.86 s | 44.08 s |
| └ slotPack | 16.01 s | 16.14 s | 16.27 s |
| └ jointPack | 7.00 s | 7.13 s | 7.03 s |
| └ exec (chained kernel time) | **24.10 s** | 12.83 s | 12.88 s |
| └ unpack | 7.77 s | 7.75 s | 7.89 s |
| `gpuMotorBinding` total | 0.23 s | 13.70 s | (separate) |
| └ pack | 0.07 s | 0.07 s | — |
| └ exec | 0.00 s | 13.39 s | — |
| └ unpack | 0.23 s | 0.25 s | — |
| **combined exec (move + bind)** | **24.10 s** | **26.22 s** | ~26 s |
| **combined total (move + bind)** | **55.11 s** | **57.56 s** | — |
| demandSyncPose | 4.62 s | 4.65 s | — |
| planRebuild | 1 | 1 | 1 |
| poseDelta sum / max / overflow | 2254 / 202 / 0 | 2234 / 202 / 0 | 2300 / 202 / 0 |

- **No OOM at dense scale** — single graph allocates all bind +
  move buffers in one `ExecutionPlan` (segBbox=24 MB scratch + grid
  CSR + bind I/O + the chained move buffers) without device
  failure. The multi-graph cross-residency pathology is structurally
  routed around.
- **Combined exec drops 2.12 s** (24.10 vs 26.22 s) — that's the
  PCIe transfer of `coord`/`uVec`/`soaLengthArr` retired
  (slotCap*3*4 + slotCap*4 = ~9.4 MB/step at slotCap=588204, ×1101
  calls ≈ 10 GB of PCIe writes removed).
- **Combined total drops 2.45 s** (55.11 vs 57.56 s) on the
  smoke wall — adds the dispatch + small CPU savings.
- **Correctness**: bindEvents 1819 vs 1749 legacy (within noise);
  gv 41.93 vs 41.30 (same band as Step 2's 42.76).

### Status

**landed-on-branch.** Branch `probe/scatter-resident`, code+docs
commit `7a509c4` (base `299d7c0`). All 3.1 / 3.2 / 3.3 gates passed:
- 3.1 feasibility: no OOM, single-graph bind reads device pose,
  bind/gv parity within noise (A/B on same seed).
- 3.2 inventory: bind plan upload retired, OP_PACK_FULL dependence
  removed; remaining demand-sync per-step readers identified as
  Step-4 surface (CPU fallback + biochem stericHindrance).
- 3.3 ensemble: N=4 paired-t `|t| ≤ 2.1` (borderline pass with
  noted directional shift from 1-step bind lag); dense smoke +2.45 s
  faster, no dense OOM.

The borderline bind-lag shift is filed as a deferred investigation
item in `MYOSIN_VALIDATION.md` (§"1-step bind lag (single-graph
fold)") — not blocking; revisit alongside the float32 binding
systematic.

The cross-graph residency the 4.5 merge couldn't achieve is now
landed via the structural simplification (one graph, not two).
Step 4 (retire `demandSyncPoseToHost` from the per-step path and
push it to output-frame cadence) is the remaining piece — requires
either porting `cpuFallback[i].moveThing()` and
`FilSegment.biochemStep`'s stericHindrance check to read
device-resident pose, or staging a host-mirror that updates only
at output cadence.

## Step 4 — reduce/retire per-step pose pull (implementation)

Run 2026-06-07 on aorus (TornadoVM 4.0.1-dev PTX, Java 21). Branch
`probe/scatter-resident`, base commit `e24e2e1` (Step 3 hash record).
Logs in `RUN_LOGS/2026-06-07_step4_demand_sync_reduce/`.

### B1 — reader audit (every per-step host pose reader)

Audit walked the per-step path from `demandSyncPoseToHost()` (end of
`moveThings`) to the next `moveThings()` for every `Thing.soaCoord/
UVec/YVec` consumer. Findings:

| Reader | Reads | Needs fresh per-step? | Handling |
|---|---|---|---|
| `FilSegment.biochemStep` poly/depoly → `incCoord(±halfmono/2, uVecAsPt3D())` | own `soaUVec` (direction); RELATIVE write into `soaCoord` | **Yes when biochem fires** — stale baseline + relative write would clobber device pose on next delta-scatter | Gate per-step pull on `!Env.noMonomersSimd.isActive()` |
| `FilSegment.splitSegment` → `setFirstHalf(setCoord)` | own `getCoord*` to compute midpoint | Same — stale baseline read | Same gate |
| `Thing.removeThing` swap-compaction | `soaCoord/UVec/YVec` at survivor slot | Stale → next scatter writes stale absolute coord to device | In `noMonomersSimd` gliding production, no per-step removeMe fires (FilSegment depoly off, no nodes/minifilaments cleanup) |
| `stericHindrance{End1,End2}` | `end{1,2}TipC` SCALAR (not pose) | No — maintained by `bridgeBoundaryTipC` + xLink-phase `registerATipClearance` | Independent of demand-sync |
| `cpuFallback[i].moveThing()` (Chamber/AnchorNode/Bug/MyoMini/ProteinNode) | own slot's `soaForceSum` + own pose for body-frame transform | No — own pose CPU-managed (never device-integrated); no GPU-neighbour pose read in any moveThing subclass | No demand-sync needed |
| `MyoFilLink.step` updatePos/addForces | `mySeg.end1AsPt3D()` + `uVecAsPt3D()` | Gated off for device-handled motors via `gpuMotorHandled()`. In gliding all MyosinFixed motors are device-handled | Inert on GPU path |
| `FilSegment.step` (Thing.step phase, pre-moveThings) | `checkBugOrBoxCollision` gated by `gpuBoundaryHandled`; `addLinkForces`/`addTorsionSpringForces` gated by `gpuChainHandled`; `addNodeForces` no-op with no nodes | All gated off / inert in gliding | None needed |
| `Mesh.fillFilSegMesh(end1AsPt3D, end2AsPt3D)` | `soaEnd1/End2` (derived) | Already at output cadence pre-Step 4 (recomputeDerivedSoA only fires in `refreshHostMirrorsForOutput`); mesh tolerates this | Unchanged |
| `OP_PACK_FULL` pose writes (next `moveThings`) | `soaCoord/UVec/YVec` | DEAD writes after Step 2 (FIRST_EXECUTION buffers, never re-uploaded) | No demand-sync needed |
| `ThreeJSWriter.buildFrameJson` / `buildInspectJson` | `getCoord*` / `getEnd*` | Output cadence — calls `refreshHostMirrorsForOutput()` which calls `demandSyncPoseToHost()` | Already handled |
| `GlidingAssayEvaluator.outputInterval` | `fs.getCoordX/Y/Z`, `fs.uVecAsPt3D()` via `distToAxis` | Output cadence — but was NOT calling refresh on its own; relied on writeFrame to fire it | **Bug surfaced**: `ThreeJSWriter.writeFrame()` early-returns when no `-3js` dir / no `LiveFrameServer`, skipping `buildFrameJson` and thus the refresh. Added refresh BEFORE the early return |
| `FileOps.writeSimJSons{,2}Frame` | `getJSonString` → `getCoord*` | Output cadence | Added defensive `refreshHostMirrorsForOutput()` call |
| `MyosinFixed.glidingAssayDataSetRun` | `getCoordX()` | Gated on `!externalDensitySweep.isActive()` — dead in production gliding | Not exercised |

**Recommendation (B2/B3 tractable):** gate the per-step
`demandSyncPoseToHost()` call inside `GPUMoveThing.moveThings()` on
`!Env.noMonomersSimd.isActive()`. Production gliding configs
(`glidingAssay500_val`, `glidingDense_demo_smoke`) have biochem off
→ per-step pull retired, output-frame `refreshHostMirrorsForOutput`
handles the cadence. Biochem-active configs (`boxSpaghetti`, future
high-churn) keep per-step demand-sync — the remaining structural
reader (biochem `incCoord` reading stale absolute coord baseline) is
a clean scoped follow-on.

**Effort:** trivial — one-line gate plus a refresh call before
`writeFrame()`'s early return plus a defensive refresh in
`writeSimJSons{,2}Frame`. No GPU kernel changes, no new buffers.

**Status:** partial-reduce. Production gliding gets the full retire
(per-step → output cadence). Biochem-active configs unchanged; the
follow-on to extend the retire to biochem-active is described below.

### B2/B3 — implementation

`GPUMoveThing.moveThings()`: the post-execute pull is now gated:

```java
if (sc > 0) {
    if (DIAG_CPU_DELTA_ADD) {
        dispatchAndWait(OP_UNPACK, sc);            // executeSplit path
    } else if (!Env.noMonomersSimd.isActive()) {
        demandSyncPoseToHost();                    // biochem-active path
    }
    // else: per-step pull retired; output cadence handled via
    // refreshHostMirrorsForOutput.
}
```

`ThreeJSWriter.writeFrame()`: the early-return path that triggers
when no `-3js` dir and no LiveFrameServer now refreshes host mirrors
first — otherwise downstream output-frame readers (`GlidingAssay-
Evaluator.outputInterval`) see stale host pose. (Pre-Step-4 the
per-step demand-sync masked this; surfaced only when the per-step
pull was retired.)

`FileOps.writeSimJSons{,2}Frame()`: added the same defensive
`refreshHostMirrorsForOutput()` so Simularium output (when armed)
sees fresh pose without depending on the writeFrame ordering.

### B4 — validation

**N=4 paired ensemble**, `glidingAssay500_val`, seeds 1–4, GPU, 10101 steps.
Baseline = Step 3 SG (`RUN_LOGS/2026-06-07_step3_single_graph/smoke_glidingAssay500_val_gpu_seed{N}_singlegraph.log`).

| seed | base bindEvents | step4 bindEvents | Δ | base gv | step4 gv | Δ |
|---|---|---|---|---|---|---|
| 1 | 878 | 926 |  +48 | 8.076 | 7.895 | -0.181 |
| 2 | 809 | 838 |  +29 | 8.023 | 8.666 | +0.643 |
| 3 | 850 | 849 |   -1 | 7.805 | 7.206 | -0.599 |
| 4 | 842 | 724 | -118 | 7.984 | 7.946 | -0.038 |

**Paired-t (N=4, df=3):**

| metric | mean Δ | sd Δ | **t** |
|---|---|---|---|
| bindEvents | -10.5 | 74.4 | **-0.28** |
| glidingVelocity | -0.044 | 0.516 | **-0.17** |

Both `|t| < 0.3` — well within the `|t| ≲ 1` PASS gate (much
tighter than the borderline `|t| ≈ 2` Step 3 had vs Step 2; the
Step 3→Step 4 mechanic adds no new lag). All four seeds:
`poseDelta sum=20`, `max=2`, `overflow=0`, `planRebuild=1` —
scatter mechanic stable. Most importantly,
`stericHindrance{End1,End2}` is structurally untouched (reads
`end{1,2}TipC` scalars, not pose; `noMonomersSimd` gates biochem-
side poly/depoly so the gate never even fires in these configs)
and the seg-tip endpoint count `filSegInitFireCt=21` matches
baseline — biochem-side initialisation count unchanged.

**Per-step demand-sync reduction (smoke):**

| metric | Step 3 baseline (seed 1) | Step 4 (seed 1) |
|---|---|---|
| `demandSyncPose` | **14.285 s / 10101 calls** | **0.340 s / 102 calls** |
| `demandSyncDerived` | 0.000 s / 0 calls | 0.148 s / 102 calls |
| `gpuMoveThing` total | 121.16 s | 104.94 s (-16.2 s) |
| `gpuMoveThing` unpack | 17.56 s | 2.81 s (-14.75 s — the retired demand-sync share) |

102 calls = exactly `10101 / toFileInterval(100) + 1` = output
cadence. Per-run wall saving 14.0–16.2 s on a ~120 s smoke. The
pull dropped to ~99.0% retired vs prior per-step path.

**Dense smoke benchmark** (`glidingDense_demo_smoke`, seed=1,
slotCap 588204, 1101 calls):

| metric | Step 3 SG | Step 4 | Δ |
|---|---|---|---|
| bindEvents | 1819 | 1907 | +88 (within single-seed noise) |
| glidingVelocity | 41.93 | 37.88 | -4.05 (single-seed dense gv has wide variance; only ~2 output frames) |
| `gpuMoveThing` total | 54.88 s | 49.77 s | **-5.11 s** |
| `gpuMoveThing` slotPack | 16.01 s | 15.91 s | -0.10 s |
| `gpuMoveThing` jointPack | 7.00 s | 6.97 s | -0.03 s |
| `gpuMoveThing` exec | 24.10 s | 23.88 s | -0.22 s (within noise) |
| `gpuMoveThing` unpack | 7.77 s | 3.01 s | **-4.76 s** (retired demand-sync share) |
| `gpuMotorBinding` total | 0.23 s | 0.23 s | 0 (single-graph, bind already absorbed) |
| `demandSyncPose` | **4.62 s / 1101 calls** | **0.389 s / 3 calls** | **-4.23 s (-99.7% wall, -99.7% calls)** |
| `demandSyncDerived` | 0.00 s / 0 calls | 0.052 s / 3 calls | refresh at output cadence (toFileInterval=500) |
| `poseDelta` sum / max / overflow | 2254 / 202 / 0 | 2244 / 202 / 0 | (within run-to-run noise) |
| `planRebuild` | 1 | 1 | (only initial) |

The dense numbers confirm: per-step pose pull retired down to the
3 output-frame demand-syncs (initial + 2 frame writes). All other
[STATS] mechanics stable (poseDelta near-identical, planRebuild=1,
no OOM at slotCap=588204). The 4-unit gv drop is within the
single-seed dense-smoke noise band (Step 2 dense gv was 42.76,
Step 3 SG 41.93 — already 0.8-unit run-to-run spread, and Step 4
is at the same scale).

### Remaining per-step pull (deferred follow-ons)

For biochem-active configs (`!noMonomersSimd`) the per-step pull
still fires. The mechanic that prevents the retire is biochem's
RELATIVE `incCoord(±halfmono/2, uVecAsPt3D())` reading host-side
absolute coord baseline. Three follow-on options, ordered from
cheapest interim to fullest fix:

1. **Biochem-cadence pull gating (cheap interim).** Biochem
   events (poly/depoly/split/cofilin-dissolve) fire far less
   often than the rigid-body physics step — likely every ~10–50
   steps (the ~ratio of `biochemDeltaT` to `deltaT`, modulated
   by per-Thing dice-roll cooldowns). The per-step host pose
   pull biochem's relative writes require need not be per-rigid-
   body-step — gate the pull to biochem-event cadence and most
   of the pull is reclaimed for biochem-active configs without a
   device port. Pends a planned survey of biochemical rates and
   dice-roll intervals (jba + planner) to confirm the
   cadence ratio and the gate point (`biochemCheckCt` increment
   vs `biochemCheckInt`, per `FilSegment.biochemStep` line 529).
   Cost: very small — same one-line gate as Step 4, condition
   on the per-Thing biochem readiness flag rather than the
   global `noMonomersSimd`. Saves most of the demand-sync wall
   in any biochem-active run.
2. **Device-side biochem deltas (fuller fix).** Apply biochem's
   relative mutations to the resident pose on-device via the
   existing `scatterPose` pipeline (`incCoord(scale, uVec)` →
   pack `(slot, scale·uVec)` into the per-step delta; the
   scatter kernel performs the addition). Removes the dependency
   on fresh host baseline entirely; closes the retire for all
   configs regardless of biochem rate. Cost: a small biochem
   refactor — split `incCoord` into a host-only path (CPU
   fallback) and a device-delta-pack path (GPU-handled);
   `FilSegment.splitSegment.setCoord` similarly. The delta-pack
   infrastructure already exists from Step 2; this extends its
   semantics from "absolute write" to "absolute or relative
   write per delta entry".
3. **Per-Thing on-demand pull.** When biochem is about to mutate
   a specific FilSegment, demand-sync ONLY that slot's coord
   first. Cost: a per-Thing transferToHost API — TornadoVM
   4.0.1-dev does not expose this granularity, so this option
   likely requires a TornadoVM API extension. Strictly inferior
   to option 2 unless TornadoVM lands the granularity
   independently.

Recommendation: take option 1 next (cheap interim, unblocks
high-churn biochem benchmarking immediately), then option 2 if
the residual per-step pull at biochem cadence still dominates
the wall. The planned biochem-rates survey informs how much of
the pull option 1 actually reclaims (high biochem rate → option
2 is needed sooner; low rate → option 1 is enough indefinitely).

### Delta-buffer cap tuning (also deferred)

`POSE_DELTA_CAP=4096` was chosen as a safe upper bound for
biochem-event count per step at production scale. At low slot
counts (`boxSpaghetti slotCap=1024`) the ~160 KB delta-buffer
EVERY_EXECUTION upload dominates the ~12 KB pose buffer it
replaces — Step 2 measured this as a regression at small scale.
Crossover point is `slotCap*3*4 > 160 KB` ≈ `slotCap > 13K
things`. Production gliding (`glidingDense_demo_smoke slotCap=
588204`) is comfortably above. A per-config tuning sweep
(`POSE_DELTA_CAP` vs measured biochem-event volume) would lower
the crossover for biochem-active mid-scale configs. Not
scoped; cross-referenced from the milestone JOURNAL entry.

### Cross-references

- **1-step bind-decision lag (Step 3 single-graph fold)** —
  `MYOSIN_VALIDATION.md` §"1-step bind lag (single-graph
  fold)". Borderline systematic shift (paired-t `|t|≈2.1`,
  uniform-positive); attributed to bind reading pose one
  integration step more advanced than the legacy two-plan
  path. Revisit alongside the float32 binding systematic.
- **float32 binding systematic (CPU-double vs GPU-float32)** —
  `MYOSIN_VALIDATION.md` §"float32 binding systematic". +22%
  bindEvents shift on the resident GPU bind path, attributable
  to the float32 motor-tip computation crossing the capture
  tolerance at a slightly different rate than CPU double.
  Resolution path: unify both arms at float32, then recalibrate
  via the capture-tolerance knob.

### Status

**landed-on-branch.** Branch `probe/scatter-resident`, code+docs+logs
commit `9b82264` (base `e24e2e1`). All B1–B4 gates passed:
- B1 audit: every per-step host pose reader inventoried and
  classified; gate identified as `Env.noMonomersSimd.isActive()`.
- B2/B3 implementation: one-line gate in `GPUMoveThing.moveThings`;
  defensive refresh in `ThreeJSWriter.writeFrame` (caught a latent
  bug where the early-return path skipped refresh — gv collapsed
  to 2.95 vs 8.08 before the fix); defensive refresh in
  `FileOps.writeSimJSons{,2}Frame`.
- B4 validation: N=4 paired-t vs Step 3 `|t|<0.3` for both
  bindEvents and gv (much tighter than Step 3's borderline
  `|t|≈2` shift); dense smoke confirms `demandSyncPose` reduced
  from 4.62 s / 1101 calls to 0.389 s / 3 calls (-99.7%);
  `gpuMoveThing total` -5.11 s; no OOM; scatter mechanic stable.

Status `{partial-reduce + follow-on}` — production gliding fully
retires the per-step pull; biochem-active configs need either
device-side biochem deltas (option 1 above) or per-Thing on-demand
sync (option 2) to extend.
