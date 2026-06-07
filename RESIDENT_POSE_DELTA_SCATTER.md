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
