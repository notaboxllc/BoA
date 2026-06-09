# Pt3D → SoA Migration — Design / Decision Document

**Status:** planner draft, pre-implementation. Sequenced after the cheap-wins
branch. This is a decision document, not a CC prompt; the per-increment
implementation prompts come later, once the partition measurement (below) is in.

## Why Pt3D, and why now

The `0cb2e9b` profiling pass ranked `boxOfActin.Pt3D` the #1 host-graph target:
**45.2 M instances / 1.8 GB at 4×, 92.9 M / 3.7 GB at 8×** — roughly 38 Pt3Ds
retained per Thing. Two payoffs from one refactor:

1. **Host-heap ceiling.** Pt3D is the largest single driver of the OOP object
   graph that walls both paths near ~16×. Shedding it directly pushes the wall out.
2. **GPU-path per-step cost.** Table 1 found the GPU path is slot-/joint-*pack*
   dominated (33–48% at 8×, linear in M). That pack is the *symptom* of Pt3D being
   the source of truth — every step gathers scattered Pt3D objects into FloatArrays.
   With contiguous SoA backing, the pack becomes a cache-friendly strided copy
   instead of pointer-chasing, so the same refactor that frees heap also cuts the
   dominant GPU-path cost.

These are retained-heap numbers (`jmap -histo:live` excludes garbage), so the 38/
Thing are genuinely *held in fields*, not transient math results. The lever is
converting **stored** Pt3D fields, plus de-retaining **held scratch** — not chasing
transient allocation (that is a separate GC-pressure concern, addressed only
incidentally).

## Guard-rails (what this is and isn't)

- **Endorsed:** SoA primitive-array storage as the canonical form, with thin CPU
  accessors as a convenience view. This is the user-approved "thin shell."
- **Out of scope:** v2 extensibility — no plugin loader, no component registry. The
  SoA-by-index store happens to match v2's representation, which reduces future
  migration friction, but we do it for BoA's own performance, not to retrofit v2.
- **Precision:** keep the **host canonical store in `double[]`** (matches Pt3D
  today), GPU float marshalling unchanged. This preserves CPU-path numerics, so a
  correct storage conversion should reproduce CPU results to near-bit-identity and
  needs no precision re-validation. A host→float question can be revisited later;
  it is deliberately *not* bundled here.
- **Legibility:** name accessor groups by the biology they carry (segment
  endpoints, lever orientation, motor anchor), so each stays findable by name — the
  v2 note about keeping physics legible, and good practice regardless.

## The population partition (the spine of the plan)

The ~38 Pt3D/Thing are not one thing. They split by *role*, and the two roles want
*different* treatments:

**A. Canonical state** — the model's actual degrees of freedom and derived
geometry: segment endpoints (`end1`/`end2`), node positions, orientation vectors
(`uVec`/`yVec`/`zVec`), force/torque accumulators. These are the real SoA
conversion: per-field `double[]` keyed by `Thing.myThingNumber` (or a dense
per-subsystem index), CPU accessors replacing field reads.

**B. Held scratch** — per-Thing working memory currently retained in helper
fields: `RetObj.conPt1/conPt2/ray1..4`, `UCircRnd`'s own Pt3Ds, `CollisionEvent`.
These are *not* state and should *not* be SoA'd. They should be pooled, made
thread-local, or simply not retained. CC's note that "RetObj/CollisionEvent share
the same fate once their Pt3D fields are SoA-shaped" conflates the two — for scratch
the fix is de-retention, not SoA.

**Prerequisite measurement (increment 0a, cheap):** get the actual A-vs-B count of
the 38/Thing. If scratch (B) is the bulk, de-retaining it is a large instance-count
drop for very little risk and should land first. This is a quick static/instrumented
count, the first thing CC does when implementation starts.

## Migration pattern decision

For **state (A)**: Things hold an `int` index into central SoA arrays; geometry is
read/written through thin static accessors grouped by biology. This is the only
option that actually *sheds* the instances (a handle object that wraps array+index
is still an object and doesn't reduce the count unless it's transient). More
call-site churn than a wrapper, but it's the change that delivers the 1.8 GB.

For **scratch (B)**: pool / thread-local / eliminate. No index, no SoA — just stop
retaining 38 working Pt3Ds per Thing.

**Aliasing audit (prerequisite, increment 0b):** today two Pt3D references can
alias the same object. SoA-by-index changes that semantics. Before converting any
subsystem, audit for intentional shared-Pt3D aliasing (e.g. a joint sharing an
endpoint reference with both partners) and decide per case whether to preserve the
alias (shared index) or break it (copy). A silently-broken alias is the most likely
source of a validation shift, so this gates increment 1.

Related, lower-priority: Pt3D arithmetic (`add`/`sub`/`cross`/`normalize`) that
*returns* new Pt3Ds is a transient-allocation (GC) cost, not part of the retained
1.8 GB. Convert these to index-targeted / in-place forms opportunistically as
subsystems convert, but don't treat it as the heap lever — it isn't.

## Staging

Each increment is independently validated, mergeable, and measured (before/after
`jmap -histo:live` + ms/step at 4× and 8×, plus gliding validation).

- **0a — scratch de-retention.** Pool/thread-local RetObj/UCircRnd/CollisionEvent
  Pt3D scratch. Cheap, low-risk, expected large instance-count drop. Gate: count the
  A/B split first so we know the payoff before touching anything.
- **0b — aliasing audit.** Static survey; no code change. Output: per-subsystem
  alias map and a preserve/break decision for each. Gates increment 1.
- **1 — FilSegment state → SoA.** The highest-value first vertical slice: filaments
  are the dominant Thing count (400·N), `end1/end2`+orientation are the bulk
  geometry, and they're exactly what the GPU pack reads. Proves the index+accessor
  pattern, hits the biggest contributor, and directly shrinks the GPU pack. Validate
  CPU near-bit-identical (double→double) and GPU vs current baseline.
- **2 — myosin state → SoA.** MyoMotor / MyoLever / MyoRod / anchor positions, same
  pattern. This is where the joint-pack cost (the other half of Table 1's GPU
  dominance) gets attacked.
- **3 — remaining Thing base-class Pt3D fields.** Mop-up; the per-Thing helper
  constellation (UCircRnd, RetObj, CollisionEvent, MersenneTwisterFast,
  ValueTracker) largely follows once its Pt3D fields are gone or pooled.

## Validation standard

This is a storage refactor, not a physics change. On the CPU path, double→double
conversion done correctly should reproduce results to near-bit-identity — so the bar
is *tighter* than the usual statistical-agreement standard: an unexpected shift
signals a conversion bug (most likely a broken alias from 0b), and is a hard bail,
not an accepted delta. GPU float marshalling is unchanged, so prior accepted GPU
deltas (e.g. the +22% float32 binding shift) are unaffected; validate each increment
against the current GPU baseline.

## A separate axis, noted not scheduled

Table 1 also showed the CPU path is Brownian-RNG-dominated (40%+), with
`MersenneTwisterFast` appearing in the per-Thing helper constellation too. A pooled/
batched RNG would help both the CPU hot loop and the helper-constellation heap. It's
orthogonal to Pt3D and folds naturally into increment 3, but it's a distinct lever —
flagged here so it isn't lost, not slotted into the Pt3D critical path.

## Open inputs before increment 1

1. The A-vs-B (state vs scratch) count of the ~38 Pt3D/Thing — increment 0a.
2. The per-subsystem aliasing map and preserve/break decisions — increment 0b.

Both are cheap and are the first CC tasks when we open implementation. Everything
downstream (the index scheme, the accessor grouping, whether 0a alone gets most of
the win) keys off them.

## Increment 0 — partition + aliasing audit (results)

Survey-only pass (2026-06-08). Static analysis of every `Thing` subclass and the
retained helpers (`RetObj`, `CollisionEvent`, `UCircRnd`, `MersenneTwisterFast`,
`ValueTracker`, `MyoFilLink`) cross-referenced against
`RUN_LOGS/2026-06-08_profiling_scoping/{4x,8x}_cpu_jmap_histo.txt`. No source
edits, no new runs.

### Histogram anchors

Thing population in gliding production (per `RUN_LOGS/2026-06-08_scaling_study/{4x,8x}_cpu_K1.pf`:
`filSegLength=64`, single-seg-per-fil filaments, 500 motors/µm²):

| class | 4× | 8× | notes |
|---|---:|---:|---|
| **boxOfActin.Pt3D** | **45.20 M (1.81 GB)** | **92.94 M (3.72 GB)** | the bucket we are sizing |
| Thing$RetObj | 1.178 M | 2.390 M | 1 per Thing — Thing-count proxy |
| CollisionEvent | 1.178 M | 2.390 M | 1 per Thing |
| MersenneTwisterFast | 1.178 M | 2.390 M | 1 per Thing |
| UCircRnd | 3.533 M | 7.170 M | 3 per Thing |
| MyoMotor / MyoLever / MyoRod | 0.392 M each | 0.784 M each | each is a Thing |
| MyosinFixed (extends Myosin, **not** a Thing) | 0.392 M | 0.784 M | container holding 1 motor + 1 lever + 1 rod |
| MyoFilLink (not a Thing) | 0.392 M | 0.784 M | 1 per motor |
| FilSegment | 1.601 K | 37.9 K | gliding is single-seg-per-fil at 4×; 8× histogram captured later in run |
| ValueTracker | 0.406 M | 1.089 M | scattered (≈ 8 per FilSegment + a few per myosin-side object) |
| Pt3D / Thing | 38.4 | 38.9 | matches the design doc's "~38" |

The 1.178 M Thing total = FilSegment + MyoMotor + MyoLever + MyoRod = 1.6 K + 3 × 0.392 M.
`Myosin`/`MyosinFixed` are **not** `Thing` subclasses (no `extends Thing`); they
hold Pt3D scratch fields but no Thing infrastructure (no RetObj, no
CollisionEvent, no UCircRnd, no Brownian scratch). Same for `MyoFilLink`.

### Partition — per-Thing base class

The base `Thing` carries **24 Pt3D fields** directly, plus a `RetObj` (6 Pt3D),
a `CollisionEvent` (3 Pt3D), 3 `UCircRnd` (0 Pt3D each — just doubles), and a
`MersenneTwisterFast` (0 Pt3D). Total **33 Pt3D per Thing** in the base
constellation.

| field group (boxOfActin/Thing.java) | role | A/B | count |
|---|---|---|---:|
| `veloc, angVeloc, bVeloc, bAngVeloc` (l.42–45) | translational + angular velocity, fixed/body frames | **A** | 4 |
| `bTransGam, bRotGam, bTransDiff, bRotDiff` (l.47–50) | drag/diffusion tensors (recomputed on `aeta` change) | **A** | 4 |
| `randForces, randTorques` (l.51–52) | Brownian per-step force/torque accumulators | **A** | 2 |
| `bForceSum, bTorqueSum, bFricForceSum, bFricTorqueSum` (l.58–61) | force/torque/friction accumulators (zeroed and refilled each step — the integrator's input) | **A** | 4 |
| `deltaBAng` (l.46) | per-step rotation delta inside `moveThing` | **A** (borderline) | 1 |
| `v1, v2, rsq, facterm, fac1, fac2, tempPt` (l.141–147) | Brownian RNG scratch (locals retained as fields) | **B** | 7 |
| `rForce, tempTorq` (l.150–151) | torque-calc scratch | **B** | 2 |
| `RetObj{conPt1, conPt2, ray1, ray2, ray3, ray4}` (l.168–187) | line-line / point-line intersect return values | **B** | 6 |
| `CollisionEvent{forceUVec, tmpPt1, tmpPt2}` (CollisionEvent.java l.10–13) | collision-test return values | **B** | 3 |
| **subtotal per Thing** | | **15 A + 18 B** | **33** |

Static `xUnitVector`/`yUnitVector`/`zUnitVector`/`zeroVec` (l.22–25) and
`maxPos` (l.41) are class-level singletons, ~5 Pt3D total — not in the count.

The Pt3D coord/uVec/yVec/zVec/end1/end2/transXTox per-Thing fields **were
already removed** in the 2026-05-30 SoA derived-field-removal landing
(Thing.java:34–40). They live in `soaCoord`/`soaUVec`/`soaYVec`/derived
`soaEnd1`/`soaEnd2`/`soaZVec`/`soaTransXTox` `float[]` arrays. Likewise the
Pt3D `bForceSum`/`bTorqueSum` fields shown in the table above are actually
storage-removed — only readers/accessors remain (Thing.java:53–57). Treat the
field declarations as the documentation of intent; the SoA storage carries
the values. This means the **state half of the per-Thing 33-count is partly
already SoA**; the work remaining is mostly the velocity/drag/diff/friction
buckets and the scratch.

### Partition — subclass-specific Pt3D fields

| owner | inst (4×) | Pt3D fields | A | B |
|---|---:|---|---:|---:|
| **FilSegment** subclass | 1.601 K | `end1Pt`, `end2Pt` (stable per-step-refreshed handles, see Aliasing A1); `F, Fopp, R, RcrossF, toPlasmidUVec, torsionVec` (plasmid-force scratch); `randForcesInX, randTorquesInX` (Brownian-frame scratch); `linkUVec, linkUVecR, linkPt` (chain-link scratch); `monInc, monOffset, evenStart, evenStop, oddStart, oddStop, curMonStart, curMonStop, end1MonCenter, end2MonCenter, coordMonCenter` (biochem mon-walk / viz scratch); `Fcoll, Tcoll` (collision-force scratch); `forminVecInx, forminVecInX, end1PAttachPt, end2PAttachPt, end1PAttachPtInX, end2PAttachPtInX` (formin/node attach state) | ~6 A (`end1Pt`, `end2Pt`, 4 attach-pt fields) | ~26 B + 8 from per-FS ValueTracker = ~34 B |
| **MyoMotor** subclass | 0.392 M | `bindTip` (Aliasing A2 — stable handle refreshed per step from soaEnd2; shared by reference with the motor's tipLink.motorPt) | 1 (derived bridge) | 0 |
| **MyoLever** subclass | 0.392 M | none | 0 | 0 |
| **MyoRod** subclass | 0.392 M | none | 0 | 0 |
| **Myosin** (NOT a Thing; 1 per myo) | 0.392 M | `F, R, RcrossF, torsionVec, linkUVec1, linkUVec2` (joint-force scratch) | 0 | 6 B |
| **MyosinFixed** (extends Myosin) | 0.392 M | `myFixedPt` (the immutable rod-tail anchor coord — pure state) | **1 A** | 0 |
| **MyoFilLink** (NOT a Thing) | 0.392 M | `motorPt` (Aliasing A2), `attachPt` (per-step `attachPt.add(seg.end1, posOnSeg, seg.uVec)` — derived state); `F, R, RcrossF, torsionVec, linkUVec1, linkUVec2` (force scratch) | 2 (derived) | 6 B |
| **ValueTracker** | 0.406 M | `avePt` (running-mean scratch for the avg-pt readers) | 0 | 1 B |
| **Crucible** statics | 1 / run | `F, R, RcrossF, torsionVec, linkUVec1, linkUVec2` (l.21–26) — see Cross-thread hazards | 0 | 6 B (negligible count, but see warning) |
| MyoMiniFilament / ProteinNode / StickyNode / Bug / ActA / Arp23 / FilLink / NodeLink | 0 in gliding | similar bouquets (~6–10 Pt3D, mostly scratch) | trace | trace |

(Note re: the per-FS field declarations: comments at FilSegment.java:101–104
say `end1Pt`/`end2Pt` "live on Thing now; bridgeDerivedToPt3D writes them
after every GPU step" — but the *declarations at l.164–165 are also present
locally*, and `initialize()` at l.451–454 refreshes them from
`getEnd1X/Y/Z`/`getEnd2X/Y/Z`. Both the base-Thing accessor path and the
local FS Pt3D handle are live. The local FS handle is what the alias check
A1 below pins on — the comment is slightly out of sync with the code.)

### Pt3D heap accounting at 4×

| source | per-inst Pt3D | inst | total | A | B |
|---|---:|---:|---:|---:|---:|
| Thing base fields (15 A + 9 B) | 24 | 1.178 M | 28.3 M | 17.7 M | 10.6 M |
| Thing.retObj (B) | 6 | 1.178 M | 7.07 M | 0 | 7.07 M |
| Thing.cE (B) | 3 | 1.178 M | 3.53 M | 0 | 3.53 M |
| Myosin scratch (B) | 6 | 0.392 M | 2.35 M | 0 | 2.35 M |
| MyosinFixed.myFixedPt (A) | 1 | 0.392 M | 0.39 M | 0.39 M | 0 |
| MyoMotor.bindTip (A-derived bridge) | 1 | 0.392 M | 0.39 M | 0.39 M | 0 |
| MyoFilLink (2 A-derived + 6 B) | 8 | 0.392 M | 3.14 M | 0.78 M | 2.35 M |
| ValueTracker.avePt (B) | 1 | 0.406 M | 0.41 M | 0 | 0.41 M |
| FilSegment subclass (~6 A + ~34 B) | ~40 | 1.6 K | 0.06 M | 0.01 M | 0.05 M |
| neg pops (MMF, etc.) | — | ≤ 0.01 M | ≤ 0.1 M | trace | trace |
| **total (≈ 45.2 M observed)** | | | **45.6 M** | **19.3 M (42 %)** | **26.3 M (58 %)** |

**Headline:** at 4×, **B (held scratch) ≈ 58 % of the 45.2 M Pt3D bucket
— ~26 M instances, ~1.05 GB retained**. At 8× the ratio holds (population
roughly doubles, Pt3D bucket doubles): **B ≈ 54 M instances, ~2.16 GB.**

Almost all of B is concentrated in three places:

1. **Thing-base Brownian-RNG scratch** (`v1, v2, rsq, facterm, fac1, fac2,
   tempPt` + `rForce, tempTorq`) — 9 Pt3D × 1.178 M = **10.6 M Pt3D** at 4×.
2. **RetObj** — 6 Pt3D × 1.178 M = **7.07 M Pt3D** at 4×.
3. **CollisionEvent** — 3 Pt3D × 1.178 M = **3.53 M Pt3D** at 4×.

Together: 21.2 M Pt3D (80 % of B, 47 % of the whole Pt3D bucket) is
**retained working memory that has no semantics surface** — its lifetime is
the method call, the field is just storing it across calls to avoid `new`.

A (state) is ~42 % — ~19 M Pt3D, ~0.76 GB. The big A buckets are
Thing-base velocs/drag/accumulators (14 × 1.178 M = 16.5 M) and the small
subclass state (myFixedPt, attachPt, motorPt/bindTip, MyoFilLink.motorPt,
FilSegment's stable end1Pt/end2Pt + attach pts).

### Aliasing audit

**Domain fact holds for independent bodies.** Confirmed by tracing every
inter-Thing Pt3D field assignment: linked Things do not share endpoint
Pt3Ds *as bodies*. Arp23 uses FilSegment refs (motherFil/daughterFil), not
Pt3D refs. FilLink/NodeLink have their own per-link Pt3D fields. Stickynode/
ProteinNode hold their own Pt3D state. MyosinFixed's `myFixedPt` is
**value-copied** at construction (`myFixedPt.copy(rodEnd1)`,
MyosinFixed.java:20), not aliased. The Thing(Pt3D) constructor uses
`setCoord(initCoord.x, initCoord.y, initCoord.z)` — pure value, no reference
retention.

**Two preserve-cases exist** — both are the same pattern: a stable
per-Thing Pt3D handle, refreshed each step from SoA, shared with a neighbor
for live-position read. One uses `==` reference identity to encode orientation;
the other is read-only and simpler.

#### A1 — `FilSegment.ptAtEnd1` / `ptAtEnd2` ⇒ neighbor's `end1Pt` / `end2Pt`  *(preserve)*

The mechanism:

- Every FilSegment owns stable `end1Pt`/`end2Pt` Pt3D objects
  (FilSegment.java:164–165). They are **not canonical** — `initialize()`
  refreshes them each step from `soaEnd1`/`soaEnd2` (FilSegment.java:451–454).
- Chain link is encoded by `ptAtEnd1 = neighbor.end1Pt` (or `.end2Pt`) at
  FilSegment.java:2759–2765. `ptAtEnd1` is a **direct alias** — a pointer
  to another Thing's Pt3D heap object.
- Orientation is decoded by `==` reference identity:
  `if (ptAtEnd2 == end2Fil.end1Pt) { ... }`. Twenty-plus occurrences
  (FilSegment.java:687, 695, 1350, 1382, 1407, 1418, 1516, 1536, 1548,
  1578, 1599, 1612, 1652, 1676, 1688, 1718, 1743, 1756, 1787, 1841;
  GPUMoveThing.java:4001, 4011; HeldChainF3F4Diag.java).
- The fil-merge cleanup at FilSegment.java:2825–2848 *rewires* neighbor's
  `ptAtEnd1`/`ptAtEnd2` to my `ptAtEnd1`/`ptAtEnd2` — a pointer-graph rewrite
  inside cleanup.

**Risk if naively converted.** The SoA migration's natural endpoint —
delete `end1Pt`/`end2Pt` because `soaEnd1`/`soaEnd2` carry the values —
silently breaks every `==` orientation check (now comparing against a
default-constructed Pt3D, all false). Equally, replacing with `new Pt3D()`
returns each call breaks identity. This is exactly the validation-shift
risk the design doc flagged.

**Preserve recipe (for increment 1).** Replace `ptAtEnd1`/`ptAtEnd2` with
`(int slot, byte side)` pairs: `end1NbrSlot = neighbor.myThingNumber`,
`end1NbrSide = 0 if my end1 glues to neighbor.end1Pt, 1 if to .end2Pt`.
Then `(ptAtEnd2 == end2Fil.end1Pt)` becomes `(end2NbrSide == 0)`. **The
side flag is already derived this way on the GPU path** (GPUMoveThing.java:4001:
`e2Side = (f.ptAtEnd2 == ne2.end1Pt) ? 0 : 1;`) — promoting it to the
storage of record is a strict cleanup, not a semantics change. Once the
side ints carry orientation, the stable `end1Pt`/`end2Pt` Pt3D handles can
be deleted; readers go through `soaEnd1`/`soaEnd2` per-slot. Fil-merge
cleanup becomes integer copying.

#### A2 — `MyoMotor.bindTip` ⇔ `MyoFilLink.motorPt` are the same Pt3D object  *(preserve, simpler)*

At MyoMotor.java:504, `tipLink = new MyoFilLink(this, bindTip)` passes
`bindTip` by reference. The MyoFilLink constructor (MyoFilLink.java:49–53)
assigns `motorPt = pt` — both fields now point to the same heap object.
MyoMotor.initialize() refreshes bindTip from `soaEnd2` each step
(MyoMotor.java:168–170). MyoFilLink reads `motorPt` to drive the spring
force (MyoFilLink.java:159, 162, 164, 191, 197).

Unlike A1 this is **not** a `==` identity check — it is purely a live-position
bridge. The same pattern as FilSegment.end1Pt/end2Pt but between a motor
and its tipLink.

**Preserve recipe (for increment 2).** Delete both `bindTip` and
`MyoFilLink.motorPt`; MyoFilLink already holds `myMotor` — reads tip
position via `myMotor.getEnd2X/Y/Z()` → `soaEnd2`. Mesh fill
(`Mesh.fillMotor` / `MotorBindGrid3D.fillMotor`) currently consumes
`bindTip` and would switch to the SoA read in the same step. No `==` checks
— strictly a position-read pattern, safe to convert.

#### Cross-thread scratch hazards (noted, not on the migration's critical path)

- **Crucible** holds 6 *static* Pt3D scratch (`F, R, RcrossF, torsionVec,
  linkUVec1, linkUVec2`, Crucible.java:21–26). Used inside
  `keepMyosinOnSurface(int i)` (l.115–129) and `keepMyosinDimerOnSurface(int i)`
  (l.137–151), which are invoked from the `Chamber Myo Threads` /
  `Chamber MyoDimer Threads` worker pools (Crucible.java:100–105 — the
  `execute(threadId)` dispatch). **Latent thread-safety race** if these
  subsystems were active. They're OFF in gliding production per the
  2026-06-08 inert-phase plan, so dormant. Out of scope for 0a/0b — flag as
  separate fixup (per-thread or stack-local). The 6 Pt3D × 1 instance is
  negligible to the bucket either way.
- **RetObj / CollisionEvent / UCircRnd / MersenneTwisterFast** are per-Thing
  fields — only touched by the worker thread currently processing that Thing
  in a given phase. No cross-thread sharing. Pool/de-retain however we like.

#### External retention

- No Pt3D references handed to Java3D (Java3D classpath gone post-Phase 4).
- `ThreeJSWriter` uses a single retained `prevPos` for its own dump
  (per-frame state); no per-Thing Pt3D retention escapes.
- `GlidingAssayEvaluator.outputInterval` reads `((MyosinFixed) m.myMyosin).myFixedPt`
  for spatial bin lookup (4 sites at l.330, 352, 363, 418); dereferenced
  inline, not retained.
- `Mesh.fillFilSegMesh(idx, Pt3D startPt, Pt3D stopPt)` and
  `MotorBindGrid3D.fillFilSeg / fillMotor` take Pt3D *parameters* — used
  inside the call only.
- **No external-retention preserve-cases.**

### Recommendation

1. **B is the bulk — scratch de-retention IS a meaningful first win.**
   Headline: ~58 % of the 45.2 M Pt3D bucket at 4× (~1.05 GB, ~2.16 GB at 8×)
   is held scratch in retained fields. Three places carry 80 % of B:

   - **Thing-base Brownian-RNG scratch** — `v1, v2, rsq, facterm, fac1,
     fac2, tempPt, rForce, tempTorq` (9 Pt3D × 1.178 M = 10.6 M at 4×).
     Locals retained as fields. Convert `calcRandomForces` to use stack-local
     primitives (a handful of `double` triples instead of Pt3D objects).
     Mechanical, low-risk; double-precision math unchanged so CPU bit-identity
     is the bar. As a bonus the CPU path's #1 hot method
     (40 %+ of samples per Table 1) gets per-step allocation pressure relief.

   - **Thing$RetObj** (6 × 1.178 M = 7.07 M at 4×). Intersect-test return
     value. Pool per worker thread (a handful of these per thread, not 1.18 M)
     or replace with `double[]` out-params / primitive returns. Same value
     semantics either way.

   - **CollisionEvent** (3 × 1.178 M = 3.53 M at 4×). Same shape as RetObj —
     per-Thing scratch, only touched by the local worker — same fix.

   Plus Myosin-family scratch (`F, R, RcrossF, torsionVec, linkUVec1,
   linkUVec2` on Myosin and on MyoFilLink — 6 × 0.392 M each = 2.35 M each
   = 4.7 M total) follows the same pattern.

   Expected drop in instance count from 0a alone: **~25 M Pt3D at 4×, ~50 M
   at 8×** — the Pt3D bucket roughly halves before any state conversion.
   No semantics change, no aliasing surface — validates against the CPU
   bit-identity bar the design doc set.

2. **For FilSegment state → SoA (increment 1), the risk list is exactly one
   case:** A1 (`ptAtEnd1`/`ptAtEnd2` reference-identity orientation
   encoding). The safe sequence is: introduce `(int slot, byte side)`
   storage; populate it everywhere `ptAtEnd1`/`ptAtEnd2` is currently set
   (FilSegment.java:2759–2765, 2825–2848); rewrite every `==` check to
   compare the side int (the GPU-side derivation at GPUMoveThing.java:4001
   already does this — promote that derivation to storage). Only then can
   `end1Pt`/`end2Pt` be deleted. Everything else in FilSegment's geometry
   (end1Fil/end2Fil are `FilSegment` refs, end1Node/end2Node are
   `ProteinNode` refs, the SoA `coord/uVec/yVec/end1/end2/zVec` are already
   the canonical store) is already SoA-native or converts trivially.

3. **Increment 2 (Myosin) adds case A2** (`MyoMotor.bindTip` ⇔
   `MyoFilLink.motorPt`). Read-only — no `==` — so the conversion is just a
   slot-lookup substitution. No subtlety beyond A1.

4. **No index-pattern misfits found.** No Pt3D field resists the index +
   accessor pattern. The static Crucible scratch is a thread-safety fixup
   orthogonal to the migration (and dormant in gliding). `ValueTracker.avePt`
   is per-tracker scratch and follows the increment-3 mop-up pattern.

5. **The design doc's sequencing holds.** 0a (de-retain scratch) lands
   first — larger of the two wins, zero semantic surface, no preserve list
   needed. 0b's preserve map is now in hand (A1 + A2). Increment 1 is
   unblocked. The plan's "B is the bulk" hypothesis is confirmed
   quantitatively (58 / 42 split at 4×).

## Increment 0a — scratch de-retention (results)

Three independently committed sub-items on
`pt3d-soa-inc0a-scratch-deretention`, then merged to main. Targets the
three places that hold 80 % of B per the increment 0 audit: Thing-base
Brownian/torque scratch, `Thing$RetObj`, and `CollisionEvent`. Myosin /
MyoFilLink joint scratch + Crucible statics deferred (see "Deferred"
section below).

### Mechanism

Per-Thing Pt3D scratch is replaced by a per-worker-thread
`Thing.WorkerScratch` pool indexed by `tlsThreadId`. `WorkerScratch`
holds the 9 Brownian/torque Pt3Ds (`v1, v2, rsq, facterm, fac1, fac2,
tempPt, rForce, tempTorq`), one `RetObj` (6 Pt3D), and one
`CollisionEvent` (3 Pt3D) — 18 Pt3D × (`Env.allThreadCt` + 1) = 18 × 17
= **306 Pt3D total scratch** (vs. **21.2 M** before at 4×; **42.4 M** at
8×). The pool size is `accumThreadCt + 1`; the last slot (`tid == -1`)
is the main thread.

The Brownian hot path (~40 % CPU per-step) avoids
`ThreadLocal.get()` in the per-Thing inner loop: the worker resolves
its `WorkerScratch` once at `ThingBrownianThreads.execute` and passes
it as a parameter through `calcRandomForces(WorkerScratch ws)` (Bug
and FilSegment overrides forward through `super`). `brownianMotionForAll`
resolves `currentScratch()` once before its loop. Friction and the two
collision paths (RetObj's `FilSegment.nodeCollisions`, the four
`check*BugCollision*` methods that use `cE`) are not the hot loop and
take one `currentScratch()` lookup per outer call.

### Sub-items

| sub-item | targets | per-Thing Pt3D dropped | 4× drop | commit |
|---|---|---:|---:|---|
| (a) Brownian / torque scratch | `v1 v2 rsq facterm fac1 fac2 tempPt rForce tempTorq` on Thing | 9 | 10.6 M / ~420 MB | `f73befa` |
| (b) RetObj | `Thing.retObj` | 6 | 7.07 M / ~283 MB | `8f25139` |
| (c) CollisionEvent | `Thing.cE` | 3 | 3.53 M / ~141 MB | `9c2c56d` |
| | | **18** | **21.2 M / ~0.85 GB** | |

Each sub-item touches a contiguous slice (Brownian → RetObj →
CollisionEvent), compiles cleanly on top of the previous, and runs a
1× CPU gliding smoke. Sub-item (a) modifies `Thing.calcRandomForces`,
`Thing.incFrictionSum`, `Thing.brownianMotionForAll`, `Bug.calcRandomForces`,
and `FilSegment.calcRandomForces` (signature added; supers forward).
Sub-item (b) only modifies `FilSegment.nodeCollisions` (the lone live
`retObj` reader; `FilSegment.checkToLink`'s local `new RetObj()` is
left alone — never retained). Sub-item (c) modifies the four
`check*BugCollision*` methods across MyoMotor, MyoMiniFilament,
ProteinNode, FilSegment.

### Lifetime / safety check (audit before pooling)

- RetObj is written by `lineSegmentIntersectTest` /
  `pointAndLineIntersectTest` (both static, both write to their RetObj
  parameter only) then read by the immediately following lines of the
  same caller. No call site holds a RetObj reference across a later
  intersect call on the same thread.
- CollisionEvent is written by `Bug.amICollidingFromOutside` /
  `Chamber.amICollidingOuter` etc. (each writes only its lcE parameter)
  then read by the immediately following lines of the same caller.
  `FilSegment.checkBugCollisionFromOutside` re-uses the same `cE` for
  end1 and end2 sequentially; the end1 result is consumed before the
  end2 call writes it.
- Brownian scratch is fully write-before-read inside a single
  `calcRandomForces` body.

### Validation — paired ensemble at the union point

Baseline at fixed seed is NOT bit-reproducible: `Thing.myPRNG` is
`MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()))` — seeded
from `Math.random()`, not from `Env.mtRNG`. Two baseline runs at
`-seed 1` differ by ~25 % in bindEvents (see
`RUN_LOGS/2026-06-08_pt3d_inc0a/baseline_runs/r{1,2}/stdout.txt`). So
the bar reverts to paired-ensemble statistical agreement, not
bit-identity.

n=5 CPU ensemble, `glidingAssay500_val`, seeds 1..5, baseline = main
at `b15ff84` (post-cheap-wins) vs `pt3d-soa-inc0a-scratch-deretention`
HEAD (sub-items (a)+(b)+(c) cumulative). Per-seed logs in
`RUN_LOGS/2026-06-08_pt3d_inc0a/ens_{baseline,inc0a}/seed{1..5}/stdout.txt`;
summary in `RUN_LOGS/2026-06-08_pt3d_inc0a/ensemble_summary.txt`.

| metric | baseline mean ± std (n=5) | inc0a mean ± std (n=5) | shift | shift / σ_b | 2-sample t |
|---|---|---|---:|---:|---:|
| bindEvents       | 874.40 ± 136.56 | 967.80 ± 37.08 | +93.40 | +0.68 σ_b | 1.48 |
| meanBoundMotors  | 7.5024 ± 0.7243 | 7.9966 ± 0.3803 | +0.494 | +0.68 σ_b | 1.34 |
| glidingVelocity  | 7.2282 ± 0.5321 | 7.7717 ± 0.2578 | +0.544 | +1.02 σ_b | 2.05 |

Apparent t=2.05 on glidingVelocity is within the noise envelope at n=5
of this non-deterministic benchmark. Baseline seed=1 is a low-end
outlier (bindEvents=666 vs the other four in [808, 990]); with seed=1
removed, glidingVelocity t drops to 1.65 — same order, no signal. The
code change is mathematically null (WorkerScratch holds the same Pt3D
objects, written-before-read inside a single
`calcRandomForces`/`incFrictionSum`/collision-check call body — no RNG
draw order, no accumulation order, no state crossing call boundaries).
**PASS.**

GPU validation: the pose path is unchanged; the only methods modified
are CPU-side (`calcRandomForces` is gated by `!gpuHandled` on the GPU
path so GPU Things never hit it; RetObj/CollisionEvent live in CPU
collision paths that are no-op in gliding). No GPU run performed in
this pass — orthogonal to the scratch de-retention.

### Heap measurement — `jmap -histo:live` at 4× CPU

Inc0a histo at `RUN_LOGS/2026-06-08_pt3d_inc0a/jmap/4x_cpu_jmap_inc0a_inc0a_v2_histo.txt`;
baseline at `RUN_LOGS/2026-06-08_profiling_scoping/4x_cpu_jmap_histo.txt`.

| class | inst (baseline) | bytes (baseline) | inst (inc0a) | bytes (inc0a) | inst drop | byte drop |
|---|---:|---:|---:|---:|---:|---:|
| `boxOfActin.Pt3D` | 45,199,818 | 1,807,992,720 | 24,002,312 | 960,092,480 | **21,197,506** | **847,900,240** |
| `Thing$RetObj` | 1,177,602 | 75,366,528 | 0 | 0 | 1,177,602 | 75,366,528 |
| `CollisionEvent` | 1,177,602 | 47,104,080 | 17 | 680 | 1,177,585 | 47,103,400 |
| `Thing$WorkerScratch` | — | — | 17 | 952 | — | — |

**Headline drop at 4× CPU: 21.20 M Pt3D / ~848 MB.** Matches the
audit's per-Thing 18-Pt3D projection (1.178 M Things × 18 = 21.20 M).
Including the RetObj + CollisionEvent container objects: **~970 MB
saved at 4×** (proportionally ~1.94 GB at 8×).

### Wall-time (no-regression check)

Per-step phase-wall sums extracted from each `seed{1..5}/stdout.txt`
(sum of `ThingStep Threads took`, `ThingBrownian Threads took`,
`Myosin Threads took`, `Mesh Threads took`, `Ck Mesh Threads took`,
`Ck Mots Threads took`, `MotorBindGrid3D Fill took`, divided by 10000
steps). Both ensembles ran in parallel, so these are upper bounds on
per-step wall under contention; the comparison is fair (both subject
to the same contention).

| config | baseline mean (n=5) | inc0a mean (n=5) | δ | δ / σ_combined |
|---|---:|---:|---:|---:|
| 1× CPU phase-wall sum | 23.08 ± 1.39 ms/step | 22.42 ± 2.09 ms/step | −0.67 ms | −0.59 σ |

No regression. Slight improvement is within the noise of contended
runs; clean serial measurement deferred. GPU walls unchanged in this
pass (the scratch fields removed are CPU-only).

### Ceiling probe

Baseline 16× CPU at `-Xmx28G` OOMs in `Mesh.<init>` at line 74
(`new double[nXBins][nYBins][binDepth]`) — see
`RUN_LOGS/2026-06-08_scaling_study/16x_cpu_K1.log`. That allocation
is ~1.9 GB for three meshes; the failure was post-print
("Eulerian Mesh stats: nXBins=281..."), so the first or second
`new Mesh()` triggered the OOM.

The inc0a 16× CPU probe at `-Xmx28G`
(`RUN_LOGS/2026-06-08_pt3d_inc0a/ceiling_16x_cpu_inc0a*.log`,
`ceiling_summary.txt`):

- `new Mesh()` × 3 completed successfully (**past the prior CPU wall**);
- `makeInitialThings()` completed (the 1.568 M-Thing 16× population
  was constructed, including all per-Thing fields except the
  WorkerScratch-relocated 18 Pt3D);
- the `[phase-plan]` print at the top of `doLoop()` was reached;
- per-step phase started.

So the prior 16× CPU heap wall at `Mesh.<init>` is gone — the savings
from shedding ~3.4 GB of Pt3D scratch at 16× (extrapolated from 4×
~848 MB) freed enough late-init headroom for Mesh + Thing construction
to coexist at `-Xmx28G`.

A re-probe with the TornadoVM API jar on the classpath (so Myosin
worker threads don't trip the `WorkerGrid` `NoClassDefFoundError`)
confirms: startup completes (Mesh, Thing construction, phase plan),
the simulation enters its per-step phase, and the new wall is
`java.lang.OutOfMemoryError` thrown by one of the worker threads
during the per-step phase at `-Xmx28G`. The OOM occurs once the
already-large 16× per-Thing heap meets per-step transient allocation
(mesh fill, intermediate Pt3Ds inside Pt3D.Scale / Add, etc.) plus
the GC headroom needed to recover them.

**Headline: 16× CPU now starts past `Mesh.<init>` and reaches the
per-step phase. The new wall is per-step transient allocation, not
startup; the next lever is transient Pt3D allocation (the `Pt3D.Add /
Sub / Scale / UnitVec` factory methods in inner loops), which is
GC-pressure, not retained-heap — flagged in the design doc but
deliberately out of scope for increment 0a.** Untangling it pushes the
ceiling further out.

### Deferred (fast-follows)

- **Myosin / MyoFilLink joint scratch** (`F, R, RcrossF, torsionVec,
  linkUVec1, linkUVec2` × 0.392 M Myosin + 0.392 M MyoFilLink at 4× =
  ~4.7 M Pt3D / ~190 MB). The mechanism is identical (per-worker
  scratch) but the dispatch differs: F/R/RcrossF are read across
  multiple method bodies (Myosin.jointConstraints,
  MyoFilLink.updateLink, MyoFilLink.checkRelease etc.), each called
  from different sub-phases of MyosinThreads — folding into
  WorkerScratch is a separate, larger refactor.
- **Crucible statics** (`Crucible.F`, `Crucible.linkUVec1` — the other
  4 are dead code). Latent multi-thread race in
  `Chamber Myo Threads.execute → keepMyosinOnSurface(i)`, dormant in
  gliding (numChamberFixedMyos = 0). Trivial fix (method-local Pt3Ds or
  add to WorkerScratch), held back to keep increment 0a's scope clean.

These are flagged here rather than slotted into a later increment — they
are still scratch-de-retention, not the state→SoA work of increments 1+.
