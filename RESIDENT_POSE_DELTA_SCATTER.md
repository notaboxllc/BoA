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
