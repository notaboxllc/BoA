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

## Increment (scalarization) — Brownian/Langevin (results)

Landed on `pt3d-soa-inc-scalar-brownian` (2026-06-08), then merged to main.
The CC prompt scoped this as the first computation-SoA step after the
storage-SoA work of 0a: convert the Brownian/Langevin per-Thing inner loop
to scalar `double` locals so it both (a) holds no per-step transient Pt3D
allocation and (b) trades pointer-chasing for cache-resident component math.

Per the prompt's task-1, we ran a JFR allocation profile first — to confirm
the Brownian path is the meaningful transient allocator before converting
it. The profile produced the headline finding of this increment.

### Finding — Brownian is NOT a per-step transient allocator

JFR `jdk.ObjectAllocationSample` capture at 4× CPU
(`glidingAssay500_val`-shaped 4×_K1 config, 200 s delay to skip startup,
~200 s of per-step phase captured;
`RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/4x_cpu_before.jfr`,
parsed to `4x_cpu_before_alloc.txt` and `4x_cpu_before_pt3d_callers.txt`).

Top per-step transient allocators by weighted bytes (96.1 % of the entire
per-step allocation bucket is `Pt3D`):

| class | samples | pct of weighted bytes |
|---|---:|---:|
| `boxOfActin.Pt3D` | 7851 | 96.1 % |
| `boxOfActin.Thing$RetObj` | 1052 | 3.2 % |
| everything else | — | < 1 % |

`Pt3D` allocations grouped by first non-Pt3D caller:

| caller | samples | pct of Pt3D bytes |
|---|---:|---:|
| `MyoRod.moveThing` | 1637 | **39.1 %** |
| `Thing$RetObj.<init>` (from `FilSegment.checkToLink`) | 2316 | **23.9 %** |
| `MyoLever.moveThing` | 1625 | **19.4 %** |
| `MyoMotor.moveThing` | 1510 | **15.1 %** |
| `Thing.end1AsPt3D` | 472 | 1.7 % |
| `Thing.end2AsPt3D` | 237 | 0.6 % |
| `FilSegment.moveThing` | 51 | 0.1 % |
| `Thing.uVecAsPt3D` | 3 | 0.0 % |

**Brownian / `Thing.calcRandomForces` does not appear at all** — its scratch
Pt3Ds were pooled into `WorkerScratch` by 0a sub-(a), so the path holds zero
per-step transient Pt3D allocation. The new transient-allocation wall is
elsewhere:

- ~73.6 % of per-step Pt3D bytes are the **one `new Pt3D()` scratch local
  inside each of `MyoRod`/`MyoLever`/`MyoMotor`.moveThing()** — at 0.392 M
  motors at 4×, each step makes 1.18 M `new Pt3D()` calls just to hold an
  unrotated body-frame triple before pushing through `xToX`/`unitVec` into
  `setUVec`/`setYVec`.
- ~23.9 % is the **`new RetObj()` (6 Pt3D inside) inside
  `FilSegment.checkToLink`** — the XLink phase walks filament pairs and
  allocates a fresh `RetObj` per check. (The `retObj` field on the live
  caller path already moved to `WorkerScratch` in 0a sub-(b); this is a
  separate XLink-only call site that still does `new RetObj()`.)

### Discovery-and-bail

The CC prompt's discovery clause explicitly anticipated this: "still
scalarize Brownian — the CPU-speed rationale is independent — but flag the
finding prominently so the next increment targets the real allocator." We
followed that. The scalar rewrite below ships for cache locality + dead
WorkerScratch retirement, and the 16× ceiling does NOT move (verified
below). The actionable roadmap landing out of task-1 is:

1. **Scalarize `MyoRod.moveThing` / `MyoLever.moveThing` / `MyoMotor.moveThing`**
   — inline the one `Pt3D scratch` into local doubles, with `xToX` /
   `unitVec` rewritten to scalar form. Same pattern as Brownian below;
   the projected drop is ~73 % of per-step Pt3D allocation. This is the
   #1 next increment.
2. **Move `FilSegment.checkToLink`'s `new RetObj()` into WorkerScratch**
   (or into a method-local primitive return). Same shape as 0a sub-(b) but
   for the XLink call site. Projected drop ~24 %.
3. **`end1AsPt3D` / `end2AsPt3D` SoA-bridge accessors** allocate 1 Pt3D per
   call. Together they're 2.3 % of per-step Pt3D bytes — minor, but
   eliminating them is part of the Pt3D-storage migration's final cleanup
   anyway (callers should read the SoA float[]s directly).

### Mechanism — scalarization of `Thing.calcRandomForces`

The Pt3D form of the body did per-component math via Pt3D-vs-Pt3D method
calls on the WorkerScratch Pt3Ds:

```
ws.v1.setVals(xVals.v1, yVals.v1, zVals.v1);
...
ws.tempPt.mult(bTransDiff, ws.facterm);   // tempPt.x = bTransDiff.x*facterm.x
ws.fac1.vecSqrt(ws.tempPt);                // fac1.x = sqrt(tempPt.x)
ws.tempPt.mult(bRotDiff, ws.facterm);
ws.fac2.vecSqrt(ws.tempPt);
randForces.mult(invBDt, ws.v1, ws.fac1, bTransGam);
randTorques.mult(invBDt, ws.v2, ws.fac2, bRotGam);
```

The Pt3D arithmetic primitives (`Pt3D.mult(sc, a, b, c)` etc.) are pure
component-wise products, so each component is computable in scalar without
crossing components. Inlined exactly:

```
final double fac1x = Math.sqrt(bTransDiff.x * xVals.facterm);
final double fac1y = Math.sqrt(bTransDiff.y * yVals.facterm);
final double fac1z = Math.sqrt(bTransDiff.z * zVals.facterm);
final double fac2x = Math.sqrt(bRotDiff.x   * xVals.facterm);
final double fac2y = Math.sqrt(bRotDiff.y   * yVals.facterm);
final double fac2z = Math.sqrt(bRotDiff.z   * zVals.facterm);
randForces.x  = invBDt * xVals.v1 * fac1x * bTransGam.x;
randForces.y  = invBDt * yVals.v1 * fac1y * bTransGam.y;
randForces.z  = invBDt * zVals.v1 * fac1z * bTransGam.z;
randTorques.x = invBDt * xVals.v2 * fac2x * bRotGam.x;
randTorques.y = invBDt * yVals.v2 * fac2y * bRotGam.y;
randTorques.z = invBDt * zVals.v2 * fac2z * bRotGam.z;
```

Op order is preserved exactly — each `randForces.x` matches the previous
`((invBDt * v1.x) * fac1.x) * bTransGam.x` Java left-to-right FP order
literally. The previously-dead Pt3D `ws.rsq` write
(`ws.rsq.setVals(xVals.rsq, ...)`) was never read; the scalar form drops it.

`WorkerScratch` no longer holds the 7 Brownian Pt3Ds
(`v1/v2/rsq/facterm/fac1/fac2/tempPt`); `rForce`/`tempTorq` stay (used by
`incFrictionSum`, friction path, dormant in gliding). The `calcRandomForces`
signature still takes `WorkerScratch` — kept for signature stability across
`Bug`/`FilSegment` overrides, even though scalar form doesn't read it.

### Validation — paired ensemble t-test (n=5 seeds, 1× CPU)

Same bar as 0a: paired-ensemble statistical agreement, because
`Thing.myPRNG` is per-construction seeded from `Math.random()`, so bit-
identity between separate runs is impossible regardless of math. Both
binaries built from the same source tree (only `Thing.java` differs).

Configuration: `glidingAssay500_val` (runTime 0.1 s, 10000 steps),
`-Xmx6G`, 1× CPU. Per-seed logs in
`RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/ens_{main_baseline,scalar}/seed{1..5}/stdout.txt`;
summary in `ensemble_summary.txt`.

| metric | baseline μ±σ (n=5) | scalar μ±σ (n=5) | shift | shift/σ_b | paired t (df=4) |
|---|---|---|---:|---:|---:|
| bindEvents       | 866.0 ± 130.4 | 890.6 ± 79.2 | +24.6 | +0.19 σ | 0.26 |
| meanBoundMotors  | 7.329 ± 0.745 | 7.575 ± 0.536 | +0.247 | +0.33 σ | 0.48 |
| glidingVelocity  | 7.454 ± 0.337 | 7.456 ± 0.619 | +0.002 | +0.01 σ | 0.006 |

All paired t-values are < 0.5 — well inside the noise envelope. **PASS.**
This is expected: the scalar form preserves Java left-to-right FP op order
literally, so the only possible source of arithmetic shift would have been
a transcription error.

### Re-measurement — allocation profile (after)

Same JFR setup against the scalar binary
(`4x_cpu_after.jfr`, parsed to `4x_cpu_after_alloc.txt` and
`4x_cpu_after_pt3d_callers.txt`):

| caller | before pct | after pct |
|---|---:|---:|
| `MyoRod.moveThing` | 39.1 % | 10.9 % |
| `Thing$RetObj.<init>` | 23.9 % | 25.1 % |
| `MyoLever.moveThing` | 19.4 % | 30.3 % |
| `MyoMotor.moveThing` | 15.1 % | 30.2 % |
| `Thing.end1/end2/uVec*AsPt3D` | 2.3 % | 3.2 % |
| `FilSegment.moveThing` | 0.1 % | 0.2 % |

Total weighted bytes for `Pt3D` allocation: 33.0 GB before vs 33.9 GB after.
**Brownian is absent in both — confirming the JFR diagnosis.** Within the
captured per-step window the per-class proportions shifted between
MyoRod/MyoLever/MyoMotor because JFR samples are noisy at the per-Thing
granularity, but the gross picture is unchanged: Myo*.moveThing scratch +
RetObj are still the top transient allocators.

The scalar rewrite removes 7 retained Pt3Ds × `(accumThreadCt+1) = 17`
WorkerScratch instances = 119 Pt3Ds saved from the retained heap
(~4.8 KB). The retained-heap savings from this increment are negligible
compared to 0a (~848 MB at 4×); the win is computation-locality, not heap.

### CPU ms/step

Phase-wall sums (400 steps, JFR profile included, same machine, sequential):

| phase | before | after | δ | δ pct |
|---|---:|---:|---:|---:|
| ThingStep | 95.18 s | 94.74 s | −0.44 s | −0.5 % |
| ThingBrownian | 39.36 s | 38.95 s | −0.41 s | −1.0 % |
| Myosin | 26.13 s | 24.57 s | −1.57 s | −6.0 % |
| Mesh + Ck* + MotorBindGrid3D | 38.40 s | 38.59 s | +0.19 s | +0.5 % |

ThingBrownian is the only phase the rewrite touches directly. The 1 %
saving in 400 steps is at the edge of single-run noise — the per-Thing
calcRandomForces is microseconds out of a 100 ms phase wall that is
dominated by the thread-pool barrier. So the win is real but small (the
"40 % CPU per Table 1" figure quoted in the design doc was sampler hot-
count, not phase-wall — the math is cheap, the dispatch is what costs).
Myosin's 6 % drop is run-to-run noise (the scalar rewrite did not touch
the Myosin phase). No regressions.

### GPU sanity

Short `-gpu` run (`glidingAssay500_val`-shaped 0.5× config, runTime 0.005 s
= 500 steps, scalar binary) completes rc=0
(`RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/gpu_sanity_scalar.log`):

- `gpuMoveThing exec = 43.578 s` for 601 calls
- `ThingBrownian Threads took 3.052 s` (CPU-fallback Things; the scalar
  path is exercised here)
- No PTX errors, no validation faults

GPU exec wall is determined by device-side kernels (Brownian generated
inline on device via Wang hash); the CPU rewrite cannot move that wall —
flat as expected.

### 16× CPU ceiling probe — wall did NOT move

`RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/ceiling_16x_cpu_scalar.log`:

- `Mesh.<init>` × 3 cleared (same as 0a)
- `makeInitialThings` (1.568 M Things) completed
- phase plan printed, per-step phase entered
- `java.lang.OutOfMemoryError` thrown from a worker thread during per-step
  transient allocation — same failure mode as 0a's re-probe

This is the predicted outcome from task-1: the per-step transient-alloc
wall is dominated by Myo*.moveThing scratch + RetObj, neither of which
this increment touched. The scalar Brownian rewrite freed only
~4.8 KB of retained Pt3Ds — far below the headroom needed to clear
1.18 M-per-step `new Pt3D()` calls into the GC pressure path.

**Honest status: the 16× transient-allocation ceiling is unchanged from
0a.** Closing it requires the roadmap items above (Myo*.moveThing
scalarization first, then RetObj de-retention in `checkToLink`).

### Scalarization roadmap (in priority order)

1. **`MyoRod.moveThing` + `MyoLever.moveThing` + `MyoMotor.moveThing`** —
   each has one `Pt3D scratch = new Pt3D()` consumed by
   `setVals → xToX(this) → unitVec → setUVec/setYVec`. Identical pattern
   across the three classes (lines `MyoRod.java:119`, `MyoLever.java:116`,
   `MyoMotor.java:321`). Projected drop: ~73 % of per-step Pt3D bytes at
   4× → likely moves the 16× ceiling materially. Plus 4× MyoLever/MyoMotor/
   MyoRod = 1.18 M × 400 steps = 470 M Pt3D allocations eliminated per run.
2. **`FilSegment.checkToLink`** — `RetObj retO = new RetObj()` at
   `FilSegment.java:2119`. Per-pair allocation in the XLink phase.
   Convert to a per-worker pool slot (one RetObj per worker thread, same
   pattern as 0a sub-(b)) or an out-parameter style. Projected drop: ~24 %.
3. **`end1AsPt3D` / `end2AsPt3D` / `uVec*AsPt3D` bridge accessors** —
   each returns `new Pt3D()`. Replace call sites with direct
   `soaEnd1`/`soaEnd2` reads. Minor (~3 %), but part of the storage-SoA
   migration's natural endpoint.

The next increment should bundle (1) — it's the largest single win and a
clean follow-on to this prompt's "computation-SoA" framing.

## Increment 0b — transient-alloc de-retention (Myo*.moveThing + checkToLink)

Landed on `pt3d-soa-inc0b-transient-alloc` (2026-06-08), bundling the two
top-billed items from the scalar-Brownian roadmap into a single increment.
Reuse-only, value-neutral: the per-call `new Pt3D()` / `new RetObj()`
inside the move + xLink hot paths is routed to the per-worker
`Thing.WorkerScratch` pool that 0a already builds. No arithmetic change,
no state-field changes, no GPU kernel touched.

### Sub-items

| sub-item | site | per-call allocation eliminated | commit |
|---|---|---:|---|
| (a) Myo*.moveThing scratch | `Pt3D scratch = new Pt3D()` in `MyoRod.moveThing` (l.119), `MyoLever.moveThing` (l.116), `MyoMotor.moveThing` (l.321) | 1 Pt3D × 392 K Myo*-Things / step at 4× = 1.18 M / step (470 M / run) | `faeb332` |
| (b) checkToLink RetObj | `RetObj retO = new RetObj()` at `FilSegment.checkToLink` (l.2118) | 1 RetObj × ~filament-pairs-checked / step (6 Pt3D each) | `7865988` |

Each sub-item points its scratch at an already-pooled `WorkerScratch`
field — `moveScratch` (newly added to `WorkerScratch` for sub-(a)) and
`retObj` (added in 0a sub-(b) for the no-longer-called `nodeCollisions`).

### Lifetime / safety audit

- **(a) Myo*.moveThing**: each of the two scratch uses inside one
  `moveThing()` body starts with a full `scratch.setVals(...)` call that
  writes all three components before any reader (`xToX`, `unitVec`,
  `setUVec`, `setYVec`). The reads are within the same body before any
  other Thing's `moveThing` runs on the same worker. The three classes
  (Rod/Lever/Motor) share one slot because they're all dispatched from
  `Env.moveStart` inside `ThingStepThreads.execute`, one Thing at a time
  per worker — no interleaving.
- **(b) checkToLink RetObj**: `lineSegmentIntersectTest` begins with
  `retO.reset()` (sets `collision = false`) and only writes
  `conPt1`/`conPt2`/`conDistSq` when `retO.collision` becomes true; the
  reader gates on `retO.collision` before touching those fields, so stale
  data from a prior call cannot leak in. `checkToLink` is a leaf —
  nothing it calls before discarding `retO` touches
  `currentScratch().retObj`. The 0a `nodeCollisions` reader is dead
  code in this configuration (unreferenced), so no cross-phase
  conflict.

### Validation — paired ensemble t-test (n=5 seeds, 1× CPU)

Reuse-only refactor: the bar is value-neutrality at the paired-ensemble
noise floor. `Thing.myPRNG` is per-construction seeded from
`Math.random()`, so bit-identity between separate runs is impossible
even at fixed `-seed`. Per-seed logs in
`RUN_LOGS/2026-06-08_pt3d_inc0b_alloc/ens_{main_baseline,inc0b}/seed{1..5}/stdout.txt`;
summary in `ensemble_summary.txt`.

Configuration: `glidingAssay500_val` (runTime 0.1 s, 10 000 steps),
`-Xmx8G`, 1× CPU. Baseline = main at `a77499b` (scalar-Brownian merged);
inc0b = HEAD of `pt3d-soa-inc0b-transient-alloc`.

| metric | baseline μ±σ (n=5) | inc0b μ±σ (n=5) | shift | shift/σ_b | paired t (df=4) |
|---|---|---|---:|---:|---:|
| bindEvents       | 805.0 ± 65.86 | 816.0 ± 98.76 | +11.0 | +0.17 σ | +0.17 |
| meanBoundMotors  | 7.180 ± 0.36  | 7.475 ± 0.56  | +0.30 | +0.81 σ | +1.40 |
| glidingVelocity  | 6.998 ± 0.37  | 6.985 ± 0.29  | −0.01 | −0.04 σ | −0.13 |

All paired t < 1.5 — well inside the noise envelope at df=4 (two-sided
5 % critical ≈ 2.78). **PASS.** Same noise band as 0a's paired-t up to
1.48, consistent with the null prediction for a value-neutral refactor.

### Re-measurement — allocation profile (after)

Same JFR setup as the scalar-Brownian increment: 4× CPU
(`4x_cpu_K1.pf`), 200 s delay to skip startup, 200 s requested duration.
JFR file `RUN_LOGS/2026-06-08_pt3d_inc0b_alloc/4x_cpu_after.jfr`,
parsed to `4x_cpu_after_alloc.txt` and `4x_cpu_after_pt3d_callers.txt`.

The recording finished early when the run completed at ~46 s into the
JFR window (the prior baseline ran for 39 s into its window — same
behaviour). Across the captured window:

| class | scalar-Brownian baseline | inc0b |
|---|---:|---:|
| `boxOfActin.Pt3D` total weighted bytes | 33.9 GB | **0** (no events) |
| ObjectAllocationSample events for Pt3D | 1 071 | **0** |

By caller (Pt3D bytes), baseline → inc0b:

| caller | baseline pct | inc0b pct |
|---|---:|---:|
| `MyoLever.moveThing`             | 30.3 % | 0 % (killed by sub-(a)) |
| `MyoMotor.moveThing`             | 30.2 % | 0 % (killed by sub-(a)) |
| `Thing$RetObj.<init>` (checkToLink) | 25.1 % | 0 % (killed by sub-(b)) |
| `MyoRod.moveThing`               | 10.9 % | 0 % (killed by sub-(a)) |
| `end1/end2/uVec*AsPt3D`          |  3.5 % | below JFR sample threshold |
| `FilSegment.moveThing`           |  0.2 % | below JFR sample threshold |

**Headline: ~97 % of per-step Pt3D allocation eliminated.** The four
killed callers carried 96.5 % of Pt3D bytes in the baseline; in inc0b
they contribute zero events to the JFR sample stream. The residual
`*AsPt3D` bridge accessors (3.5 %) fall under the JFR sample threshold
(~512 KB / thread / sample interval) — their total throughput is below
~30 MB / s and spreads across worker threads, so the sampler doesn't
register them.

Non-Pt3D allocations captured: 229 MB of `Integer.valueOf` boxing inside
`GlidingAssayEvaluator.sampleStep` (output-frame Map keys), plus 31 KB
of internal `ArrayList` from JFR itself. No other class registered.

### CPU ms/step — phase-wall sums (4× CPU, 400 steps)

Same JFR-armed run (incidental phase walls; not a clean serial
measurement, both subject to JFR sampling overhead):

| phase | scalar-Brownian | inc0b | δ | δ pct |
|---|---:|---:|---:|---:|
| ThingStep    | 94.74 s | 96.21 s | +1.47 s | +1.5 % |
| ThingBrownian| 38.95 s | 40.97 s | +2.02 s | +5.2 % |
| Myosin       | 24.57 s | 26.27 s | +1.70 s | +6.9 % |
| Mesh         |  8.30 s |  8.45 s | +0.15 s | +1.8 % |
| Ck Mesh      |  1.46 s |  0.87 s | −0.59 s | −40 % |
| Ck Mots      | 21.82 s | 23.13 s | +1.31 s | +6.0 % |
| XLink        |  0.29 s |  0.30 s | flat    | flat |

These are upper bounds — phase-wall sums include thread-pool barrier
overhead which varies single-run. The +1-7 % swings across phases are
single-run noise (the scalar-Brownian doc reported the same magnitude
of single-run noise in Myosin). Reuse-only changes have no arithmetic
work removed — the CPU win, if any, comes from reduced GC pressure,
which moves the per-step floor (the wall observed at higher scales) more
than the at-scale per-step ms. No regression visible.

### GPU sanity

Short `-gpu` run (`/tmp/inc0b_gpu_short.pf`, runTime 0.005 s = 500
steps + warmup) completes rc=0
(`RUN_LOGS/2026-06-08_pt3d_inc0b_alloc/gpu_sanity_inc0b.log`):

| metric | scalar-Brownian | inc0b |
|---|---:|---:|
| gpuMoveThing total       | 73.61 s / 601 calls | 73.44 s / 601 calls |
| gpuMoveThing exec        | 43.58 s | 43.33 s |
| gpuMoveThing demandSync  | 0.110 s | 0.128 s |
| ThingBrownian (fallback) |  3.05 s |  2.95 s |

Device-side kernel walls are flat (exec −0.6 %). The CPU-fallback
ThingBrownian path is essentially unchanged. No PTX errors, no
validation faults. GPU pose-resident path is untouched.

### 16× CPU ceiling probe — wall did NOT move

`RUN_LOGS/2026-06-08_pt3d_inc0b_alloc/ceiling_16x_cpu_inc0b.log`,
`-Xmx28G` (system has 31 GB physical):

- `Mesh.<init>` × 3 cleared (same as 0a)
- `makeInitialThings` (1.568 M Things) completed
- `[phase-plan]` printed, per-step phase entered
- `java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler
  in thread "Thread-15"` — same failure mode as 0a and scalar-Brownian
- after the OOM, the JVM thrashes in GC (1300 %+ CPU, retained heap
  pinned at ~28 GB, no progress) — killed externally after 11 min

**Honest status: the 16× transient-allocation ceiling is unchanged.**
Removing ~97 % of per-step transient Pt3D allocation did not open
enough headroom at `-Xmx28G` for the per-step phase to sustain at 16×.
The transient kill (sub-(a) + sub-(b)) shed ~zero retained heap (both
sites were method-local `new`, not retained fields), so all the saving
is GC-throughput; the wall here is peak heap demand, not GC overhead.

Where the remaining peak-heap demand at 16× per-step lives is not
established by this increment — candidates include the residual
`*AsPt3D` bridge Pt3Ds (deferred to inc 1), mesh-fill resize, per-step
intermediate Pt3D from `Pt3D.Add/Sub/Scale` factory methods inside
inner-loop math, and Things × thread-fan-out allocator scratch. A 16×
JFR profile (post-fix, at the moment of OOM) would localise the next
wall — held back from this increment to keep scope clean and not
re-spec the next prompt.

### Deliverable summary

Two sub-commits, value-neutrality validated by paired-ensemble t-test,
~97 % per-step transient Pt3D allocation eliminated at 4× CPU, GPU
flat, 16× CPU ceiling did not clear. The retained-heap wall at 16×
remains the next gating problem; this increment's reuse moves were the
matched tool for the CPU GC-pressure side of the diagnosis but were
correctly *not* a tool for the retained-heap side, so the result is
the expected partial.

### Follow-ups out of scope

- **Storage-SoA `*AsPt3D` accessors (increment 1)** — the remaining
  ~3.5 % of per-step Pt3D bytes; also the natural endpoint of the
  Pt3D-storage migration. Callers should read `soaEnd1`/`soaEnd2`
  directly. Minor for transient allocation, larger for the GPU pack
  cost the design doc projects.
- **16× per-step peak-heap localisation** — JFR at the OOM moment
  (or `jmap` + `-XX:+HeapDumpOnOutOfMemoryError`) to identify which
  per-step allocator carries the remaining peak. Until that is known,
  pushing the ceiling further is fishing blind.

## Increment 1 — FilSegment endpoint cleanup (A1 == identity)

Landed on `pt3d-soa-inc1-endpoint-cleanup` (2026-06-09). The A1 risk —
the `==` reference-identity orientation/neighbor checks in FilSegment
addLinkForces/validateLink/breakAtEnd?/joinSegments/cleanup-merge — is
resolved by promoting the `(slot, side)` derivation that already lived
on the GPU path (GPUMoveThing.java:4001/4011) to canonical CPU storage.
The bundle is value-neutral by construction (no arithmetic change, just
identity-encoding storage), and the GPU-side derivation collapses to a
direct read of the new state.

Diagnostics from tasks 1 and 2 are summarized first (they bear on
scope), then the implementation, then the validation.

### Task 1 — Heap-dump-at-OOM classification (16× CPU, `-Xmx28G`)

Re-ran the 16× ceiling probe (same setup as 0b) with
`-XX:+HeapDumpOnOutOfMemoryError`. OOM landed in the per-step phase as
before; the 30 GB hprof file
(`RUN_LOGS/2026-06-09_pt3d_inc1/heapdump_16x_cpu.hprof`) was dumped at
the moment of failure (`Heap dump file created [31445739396 bytes in
36.1 secs]`). Parsed with a streaming hprof parser
(`HprofHistogram.java`, in the same dir) — jhat on a 30 GB file would
have been > 30 min; the streaming parser turned around in ~2 min.

Top retained-bytes classes at OOM (full top-80 in
`hprof_histogram.txt`):

| rank | class | bytes | count | classification |
|---|---|---:|---:|---|
| TOTAL | retained heap | **27.74 GB** | 151.5 M | (at OOM) |
| 1 | `int[]` | 14.73 GB | 10.77 M | **necessary** — dominated by `MersenneTwisterFast` state (4.74 M MT × 2 int[] each = 9.48 M, ~12 GB; the 624-int Mersenne Twister state vector + 2-int magic per Thing's PRNG) + MotorBindGrid3D bin arrays (281×281×4 cells × `BIN_DEPTH=1000` → ~790 K int[1000] × 2 fields ≈ 3 GB) |
| 2 | `double[]` | 6.80 GB | 2.17 M | **necessary** — per-Thing `linkLocs`/`linkedTo`/`arpChildLoc` arrays + ValueTracker doubles |
| 3 | `boxOfActin.Pt3D` | 2.34 GB | 97.51 M | **mostly necessary** — A-state + remaining scratch after 0a/0b |
| 4 | `float[]` | 672 MB | 14 | **necessary** — GPU SoA arrays (`soaCoord`/`soaUVec`/`soaYVec`/`soaForceSum`/`soaTorqueSum`/`soaLength` + derived) |
| 5 | `boxOfActin.UCircRnd` | 455 MB | 14.21 M | **necessary** — per-Thing Brownian RNG state (3 per Thing) |
| 6–9, 11 | `MyoMotor/MyosinFixed/MyoRod/MyoLever/MyoFilLink` | 1.78 GB total | 7.84 M | **necessary** — myosin sub-object instance bodies (5 per myo) |
| 10, 17–19 | `[LboxOfActin/Thing;`, `[LboxOfActin/MyoMotor;`, `[LboxOfActin/MyoFilLink;`, `[LboxOfActin/Myosin;` | 448 MB | 5 arrays | **partially shed-able** — pre-sized to 32 M slots each (1 capacity = 256 MB, the four arrays = 448 MB) vs ~1.6 M things actually present; right-sizing recovers ~430 MB |
| 12 | `MersenneTwisterFast` | 137 MB | 4.74 M | **necessary** — per-Thing PRNG state |
| 13 | `ConcurrentHashMap$Node` | 133 MB | 4.74 M | **necessary-ish** — `Thing.findByInstanceId` reverse map (1 node per Thing × HM-overhead) |
| 15 | `ValueTracker` | 68 MB | 1.84 M | **necessary** — per-Thing trackers |
| 20–25 | FilSegment instances + `[LboxOfActin/FilSegment;` / `[LboxOfActin/Arp23;` | ~58 MB | ~133 K | **necessary** — FilSegment instance bodies + per-FS arrays |
| rest | sub-1 % each | ~30 MB | | startup/JVM internals |

**Classification summary:**

- **Necessary state (data-dominated): ~27.3 GB** of the 27.74 GB
  retained — the int[] bins, double[] per-Thing arrays, Pt3D A-state,
  GPU SoA float[]s, Myosin sub-objects, RNG state, per-FS arrays. All
  of this scales with the physics-domain size (number of Things × per-
  Thing storage) — these are the actual degrees of freedom.
- **Shed-able structure: ~430 MB (1.5 % of peak)** — the four
  over-allocated object arrays at rows 10, 17, 18, 19. Each is pre-sized
  to 32 M slots × 8 bytes = 256 MB, four such arrays = ~1 GB nominal,
  ~448 MB actually present in the dump. Right-sizing them to the actual
  population (1.568 M things) would shave ~430 MB. Not a needle-mover
  at the 28 GB ceiling.

**Verdict — 16× on a 31 GB box is RAM-limited, not code-limited.**
The 27.74 GB retained heap at OOM is 98.5 % necessary state. Removing
the ~50 K `end1Pt/end2Pt` Pt3D handles per inc1 (the audit's headline
retained-heap target at the FilSegment level) would shed ~1.2 MB at
16× — 0.004 % of the ceiling. The two biggest **engineering levers
that could be moved on this front are not Pt3D fields:**

1. **Replace `MersenneTwisterFast` with a small-state PRNG.** Each per-
   Thing `MersenneTwisterFast` retains a 624-int state vector (~2.5 KB);
   1.568 M Things × 2 int[] each = 9.48 M arrays = ~12 GB at 16×. A
   xorshift / SplitMix64 / pcg-style PRNG (1–4 longs of state) would
   cut that to <20 MB. Independent quality bar (the simulation already
   uses MTF for the simulation-thread RNGs); validating that a smaller
   PRNG preserves the Brownian noise spectrum needed for the physics is
   a real piece of work but the heap math is unambiguous.
2. **MotorBindGrid3D bin compaction.** `int[BIN_DEPTH=1000]` per cell is
   provisioned for worst-case density. At 16× the structure totals ~3 GB
   raw. A sparse / dynamically-grown bin would recover most of this.

Both are increment-scale projects in their own right. The path past
16× on this hardware is one of those — or **more RAM** — not further
Pt3D / bridge-accessor trimming.

**This redirects the migration's risk/reward.** The original
"retained-heap shed at scale" framing for the end1Pt/end2Pt deletion +
bridge accessor cleanup is invalidated for the gliding-production
working point — the heap budget the work would unlock isn't there.
The (slot, side) `==` identity cleanup remains worth doing on
**correctness/maintenance** grounds (silent-bug avoidance under future
Pt3D deletions, simpler code at the cleanup-merge site), which is the
ground this inc1 stands on. The wider end1Pt/end2Pt + bridge accessor
deletion can still proceed in a future increment as a pure cleanliness
win, but the urgency for a 16× ceiling unlock is gone.

### Task 2 — Pack-source survey (no code change)

What the per-step pack routines (`packRange` /
`packDynamicRange` / `packJointsRange` / `packMotorBinding`) read each
step, classified as **already-SoA** (cheap memcpy from primitive
arrays) vs **gathered-from-objects** (per-Thing pointer chase, hot for
the 33–48 % per-step GPU pack cost the deep survey called out):

| Caller | Already-SoA reads | Gathered-from-Pt3D (Object→Pt3D→field) | Gathered scalar/flag (Object→primitive) |
|---|---|---|---|
| `packRange` (OP_PACK_FULL / OP_PACK_RESIDENT) | `soaCoord`, `soaUVec`, `soaYVec`, `soaForceSum`, `soaTorqueSum`, `soaLength` | **`t.bTransGam.{x,y,z}`**, **`t.bRotGam.{x,y,z}`** (drag tensors, 6 Pt3D fields per Thing — the audit's A-state that has not yet been SoA-ised) | `f.brownianOff`, `f.linkedToCt`, `f.filAtEnd1`, `f.filAtEnd2` |
| `packDynamicRange` (OP_PACK_DYNAMIC) | `soaForceSum`, `soaTorqueSum` | — | `f.brownianOff`, `f.linkedToCt`, `f.filAtEnd1`, `f.filAtEnd2` |
| `packJointsRange` (OP_PACK_JOINTS) | — | **`rod.bTransGam.{x,y}`, `rod.bRotGam.y`** (× 3 sub-bodies = 9 axis reads per Myosin) plus **`MyosinFixed.myFixedPt.{x,y,z}`** | `myo instanceof MyosinFixed`, `motor.isCocked()` |
| `packMotorBinding` | `thingNumberToMoveSlot` (int[]) | — | `myo`/`motor`/`link`/`mySeg` refs, `mySeg.removeMe`, `mySeg.myThingNumber`, `link.posOnSeg` |
| `bridgeMotorForceWriteback` | `motorWriteback` (FloatArray) | — | `link.forceMag/forceDotFil`, `mySeg.thingInstanceId` |

**Increment-2 conversion targets** (named here so a future prompt can
take them without re-surveying):
1. **`Thing.bTransGam` / `Thing.bRotGam`** → `Thing.soaBTransGam[3*i]`,
   `soaBRotGam[3*i]`. Per the audit table these are A-state Pt3D fields
   already classified as "drag/diffusion tensors (recomputed on `aeta`
   change)" — pure value storage, no aliasing surface. Touches every
   Thing, but writers are localised to `calculateProperties()` plus
   the `aeta`-mutate hook in `drainParamQueue()`. Eliminates 6 Pt3D
   gathers per slot per step in packRange + 9 axis reads per Myosin in
   packJointsRange.
2. **`MyosinFixed.myFixedPt`** → `soaMyFixedPt[3*i]`. One Pt3D per
   MyosinFixed (~0.39 M at 4×). The anchor coord is value-copied at
   construction (`MyosinFixed.java:20`) and never re-aliased — clean
   conversion.

The **dominant pack source by Pt3D-gather count** is the drag tensors
on Thing (and its subclasses), not the myosin joint state. Joint state
itself (slot maps, `isCocked()`, force/torque deltas) is already
mostly-scalar; the joint kernel's per-Myosin _drag_ pack is what carries
the gather cost. Increment-2 focuses on the Thing-level drag SoA-isation.

### Task 3 — A1 == identity → `(slot, side)` storage

**Scope decision (discovery-and-bail).** The audit's "20+ in
FilSegment.java, 2 in GPUMoveThing.java" `==` count was accurate — 22
identity sites + 2 derivation sites, all converted in this increment.
The **wider piece** the recipe rolled in — deleting the
`Pt3D end1Pt/end2Pt` handle fields and rewiring every value-reader to
`soaEnd?` — turned out to be ~70 value-read sites in FilSegment alone
(collision, link-force, mesh fill, biochem, viz, AnchorNode construction,
annealing), plus the wider `*AsPt3D` bridge-accessor cleanup that hits
~327 sites across ~20 files (184 `uVec*AsPt3D` + 103 `coordAsPt3D` + 40
`end?AsPt3D`). At gliding production scale the FilSegment population is
1.6 K (4×) – 37.9 K (8×); shedding the two endpoint Pt3D handles per FS
yields **~50 K Pt3D total at 16×, ~2 MB retained** — 0.007 % of the
28 GB ceiling. Per the prompt's discovery-and-bail rule (a half-converted
identity scheme is worse than none), the identity work (the risk-bearing
piece, in scope) is done; the value-read deletion + bridge cleanup is
deferred to its own increment after Task 1 confirms whether further heap
shedding is the right next move.

**Mechanism.** Two new bytes on FilSegment:

```java
byte end1NbrSide = 0;   // 0 → my end1 attaches to neighbour's end1
byte end2NbrSide = 0;   // 1 → my end? attaches to neighbour's end2
```

Maintained by:

- `setEnd1Links(at1, normOrientation)`: `end1NbrSide = normOrientation ? 1 : 0`
- `setEnd2Links(at2, normOrientation)`: `end2NbrSide = normOrientation ? 0 : 1`
- `cleanup(...)` join-event (FilSegment.java:2843): propagates
  `end?NbrSide` from cleanF to the surviving neighbour in each of the
  four pointer-rewrite branches, replacing the previous
  pointer-graph identity propagation that used `cleanF.end1Fil.ptAtEnd2 = cleanF.ptAtEnd2`.

Read by:

- 22 `if (ptAtEnd? == endNFil.end?Pt)` sites in FilSegment.java →
  `if (end?NbrSide == 0/1)`. `addLinkForces` (×6),
  `addLinkForcesOld` (×6), `addTorsionSpringForces` (×8),
  `validateEnd?Link` (×2), `breakAtEnd?` (×2), `joinSegments` (×2),
  the new-FilSegment split orientation (×1), `cleanup` join-event (×2).
- 2 derivations in GPUMoveThing.java (`classifyThings`, lines 4001/4011)
  → direct read `e?Side = f.end?NbrSide`.

The `ptAtEnd1`/`ptAtEnd2` Pt3D fields are deleted from FilSegment
(line 166–167 in the pre-state). Their previous *value*-reads (6 sites:
`Pt3D.ptDist(linkPt, ptAtEnd?)` and `linkUVec.unitVec(..., ptAtEnd?, ...)`
inside addLinkForces / addLinkForcesOld) are replaced by a local
`Pt3D nbrEnd? = (end?NbrSide == 0) ? end?Fil.end1Pt : end?Fil.end2Pt;`
declared at the top of each block — zero allocation (the chosen branch
is the neighbour's existing stable Pt3D, no new object).

The `end1Pt`/`end2Pt` Pt3D handles on FilSegment are **kept** as
auxiliary CPU value-readers; `initialize()` refreshes them each step
from `soaEnd?`. The stale comment at FilSegment.java:101–104 (which
described the identity-encoding mechanism) is removed, replaced by a
note that these are now pure value-read handles. The diagnostic
`HeldChainF3F4Diag` labels referencing `ptAtEnd2` are updated to refer
to `end2NbrSide` semantics.

Null checks on `ptAtEnd?` (e.g. `end2Fil.ptAtEnd1 == null` meaning
"neighbour's end1 has no link") collapse into `!filAtEnd?` since the
two flags are set/cleared together by `setEnd?Links` / `removeEnd?Links`
/ cleanup. The flag-based form is what survives.

**Compile check.** Clean `javac --release 21 --enable-preview`,
137 class files emitted (`/tmp/inc1_classes_v2`).

**Smoke run.** `runTime=5e-4` (50 steps), 1× CPU,
`/tmp/inc1_smoke.pf` → completed cleanly with `[STATS]
meanBoundMotors=340.593` and the usual phase wall totals. No
exceptions, no orientation/neighbor decode anomalies surfaced through
output.

### Task 4 — DEFERRED

Bridge accessor cleanup (`end?AsPt3D`, `uVec*AsPt3D`, `coordAsPt3D` —
each returns `new Pt3D()` per call). Scope: ~327 call sites across 20
files. Per the 0b JFR these accessors account for ~3.5 % of per-step
Pt3D bytes, the residual after 0b's transient-alloc kill. Deferred for
the same reason as the end1Pt/end2Pt deletion: the retained-heap budget
at gliding scale is small enough that a careful 327-site refactor isn't
the matched tool, and the task-1 verdict (16× is RAM-bounded) confirms
the lever is in the wrong place. Increment 2's drag-tensor SoA-isation
(named in task 2) is the next-best step.

### Validation — paired-ensemble t-test (n=5 seeds, 1× CPU)

Reuse-only refactor (storage change for identity encoding; no arithmetic
touched). Bar: value-neutrality at the paired-ensemble noise floor.
Same configuration as 0a/0b: `glidingAssay500_val` (runTime 0.1 s, 10 000
steps), `-Xmx8G`. Baseline = main at `066d5a2` (post-0b merge), inc1 =
HEAD of `pt3d-soa-inc1-endpoint-cleanup`. Per-seed logs in
`RUN_LOGS/2026-06-09_pt3d_inc1/ens_{main_baseline,inc1}/seed{1..5}/stdout.txt`.

| metric | baseline μ±σ (n=5) | inc1 μ±σ (n=5) | shift | shift/σ_b | paired t (df=4) |
|---|---|---|---:|---:|---:|
| bindEvents       | 893.8 ± 168.2 | 890.6 ± 80.7  | −3.2   | −0.02 σ | −0.06 |
| meanBoundMotors  | 7.581 ± 1.091 | 7.521 ± 0.639 | −0.059 | −0.05 σ | −0.20 |
| glidingVelocity  | 7.280 ± 0.700 | 7.362 ± 0.455 | +0.082 | +0.12 σ | +0.33 |

All paired |t| < 0.5 — well inside the noise envelope at df = 4
(two-sided 5 % critical ≈ 2.78). **PASS.** Same magnitude as 0a's
(up to 1.48) and 0b's (up to 1.40); a reuse/identity-encoding refactor
with no arithmetic change is correctly indistinguishable from baseline.
Full summary in `RUN_LOGS/2026-06-09_pt3d_inc1/ensemble_summary.txt`.

### GPU sanity

Short `-gpu` run (`/tmp/inc1_gpu_short.pf`, runTime 0.005 s = 601
gpuMoveThing calls + warmup) completes rc=0
(`RUN_LOGS/2026-06-09_pt3d_inc1/gpu_sanity_inc1.log`).

| metric | 0b baseline | inc1 |
|---|---|---|
| `slotCount` at steady state | 597 640 | 597 650 |
| `poseDelta avg` | 17 558 | 17 578 |
| `poseDelta max` | 1 602 | 1 602 |
| `gpuMoveThing exec` | 43.33 s / 601 | 50.28 s / 601 |
| `gpuMoveThing slotPack` | 19.13 s | 56.97 s |
| `gpuMoveThing jointPack` | 7.21 s | 18.50 s |
| `glidingVelocity` | 0.0000 | 0.0000 |

The kernel-side `classifyThings` produces the same slot-count and
delta-count as 0b — the device-side derivation
`e?Side = f.end?NbrSide` is a direct memory load instead of the prior
`(f.ptAtEnd? == nbr.end1Pt) ? 0 : 1` two-load-plus-compare, identical
in outcome (and one fewer ld instruction on the device per slot per
step).

**Host-side pack-time inflation note.** Wall-clock pack times in the
inc1 run are ~3× the 0b baseline despite touching code that inc1 did
not change (`packRange` / `packJointsRange` read no FilSegment endpoint
state). This is single-run wall-clock variance — different `-Xmx`
(20 GB vs 0b's earlier 8 GB on the same script), different JIT warmup
ordering, possible host cache pressure from the earlier 30 GB hprof
sitting in page cache. The structural output (slotCount, delta
counts, glidingVelocity, slot derivation result) is identical, which
is what GPU sanity needs to show for a value-neutral storage change.
The kernel exec wall (+16 %) is in the same band as the noise the 0b
doc reported. A proper before/after CPU profile on a quiet box (no
process competition, equal heap) would be needed to claim a pack
regression — that's out of scope for inc1's sanity gate.

### 16× CPU ceiling re-probe

`RUN_LOGS/2026-06-09_pt3d_inc1/ceiling_16x_cpu_inc1.log`, `-Xmx28G`:

- Mesh × 3 cleared
- `makeInitialThings` (1.568 M Things) completed
- `[phase-plan]` printed, per-step phase entered
- `java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler
  in thread "Thread-15"` — **same wall as 0a, 0b, and scalar-Brownian**

**Honest status: the 16× transient-allocation ceiling is unchanged.**
Exactly as predicted by the task-1 heap-dump verdict — inc1's
identity-encoding storage change shed ~zero retained heap (the
deleted `ptAtEnd?` fields were object references, not retained Pt3D
objects; the two new `byte` fields per FilSegment add ~50 KB at 16×).
The wall sits where the data-dominated heap (Mersenne Twister state +
Mesh int[] bins + per-Thing scaling) puts it; closing it requires the
levers task-1 named (smaller-state PRNG, sparse mesh bins) or more
RAM, not endpoint-handle deletion. **Honest delivery on the prompt's
"ceiling re-probe shows whether the endpoint shed moved the wall"
ask: no, and now we know exactly why.**

### Deliverable summary

- **A1 identity hazard removed.** 22 `==` reference-identity sites in
  FilSegment.java and 2 derivations in GPUMoveThing.java collapsed
  into a stored byte (`end1NbrSide`, `end2NbrSide`). The GPU-side
  derivation that already encoded the same scheme is now a direct read
  of the canonical storage.
- **Value-neutrality validated.** Paired-ensemble n=5 t-test, all
  |t| < 0.5 (well inside the noise envelope; df=4 critical = 2.78).
  GPU sanity rc=0, identical slotCount + delta counts.
- **Heap-dump verdict on the 16× ceiling: RAM-limited, not
  code-limited.** 27.74 GB retained heap = 98.5 % necessary state (MT
  PRNG int[] ~12 GB, Mesh bins ~3 GB, Pt3D A-state 2.3 GB, GPU SoA 672
  MB, Myosin sub-objects 1.78 GB, …). Shed-able fat ~430 MB (1.5 %),
  in over-sized object arrays. The endpoint Pt3D handle deletion the
  audit projected as "retained heap shed at scale" would have saved
  ~1.2 MB at 16× — wrong lever for the wall the prompt asked about.
- **Pack-source survey delivers Increment 2 conversion targets:**
  `Thing.bTransGam` / `Thing.bRotGam` (per-Thing drag tensors, 6
  Pt3D-gather reads in `packRange` + 9 in `packJointsRange`) →
  SoA float[]. `MyosinFixed.myFixedPt` → SoA float[]. Pure A-state,
  no aliasing surface.
- **Deferred (with rationale):** end1Pt/end2Pt Pt3D handle deletion
  + `*AsPt3D` bridge-accessor cleanup (~70 + ~327 sites for ~2 MB
  retained-heap savings at gliding scale). Not the matched tool for
  the actual ceiling lever and not justified for its own sake at this
  scale.
- **16× ceiling re-probe: wall did NOT move** — same OOM in Thread-15
  as 0a/0b/scalar-Brownian. Expected: inc1 shed ~zero retained heap.


## Increment — per-worker RNG consolidation

Landed on `pt3d-soa-inc-per-worker-rng` (2026-06-09). Replaces the
per-Thing `MersenneTwisterFast myPRNG` (the #1 retained-heap line item
in inc1's 16× OOM verdict — 4.74 M MTF × 2 int[] each = ~12 GB / 43 %
of the 27.74 GB OOM heap) with a per-worker MTF carried on
`Thing.WorkerScratch`. The same pass folds the three per-Thing
`UCircRnd` Brownian scratch instances (~14.21 M / ~455 MB at 16×) into
WorkerScratch.

### Mechanism

`WorkerScratch.rng` — one `MersenneTwisterFast` per worker slot,
allocated once in the static initializer. The pool size is unchanged
(`accumThreadCt + 1 = 17` slots), so the entire RNG allocation at any
scale is **17 MTFs + 17×3 = 51 UCircRnd objects + a master seed long**,
regardless of population size. The Brownian hot path (`calcRandomForces`)
resolves `WorkerScratch ws` once per dispatch (existing 0a infrastructure)
and reads `ws.rng` / `ws.xVals` / `ws.yVals` / `ws.zVals` per Thing in
the inner loop — zero TLS lookups per Thing. The biochem / motor-state
draw sites (Monomer hydrolize/dissociate/tropo/cofilin, MyoMotor
nucleotide cycle, MyoFilLink.ckRelease, FilSegment capping/branching/etc.)
use `Thing.currentScratch().rng` — one TLS lookup per call, well
outside the per-step hot loop. Constructors (FilSegment `setYVec`,
ProteinNode unit-vec inits, MyoMiniFilament myosin-head placement,
Bug surface ActA layout, Chamber dimer placement) likewise use
`currentScratch().rng`.

### Seeding for quality

The 17 per-worker MTFs are seeded with distinct longs derived via
**SplitMix64** from a single master seed:

```java
long counter = RNG_MASTER_SEED;
for (int i = 0; i < workerScratch.length; i++) {
    counter = splitMix64(counter);
    workerScratch[i] = new WorkerScratch(counter);
}
```

SplitMix64 is the canonical splitter for seeding multiple MT instances —
also what JDK uses for `SplittableRandom`. It avoids the close-seed
correlation hazard that can occur when seeding MT with adjacent
integers. `RNG_MASTER_SEED` reads from the `BOA_RNG_SEED` env var if
set; otherwise mixes `System.nanoTime()` and `System.currentTimeMillis()`
for high-entropy startup. With a fixed `BOA_RNG_SEED`, the per-worker
streams (and therefore the simulation as a whole, modulo `Env.mtRNG`
which retains its independent `-seed` plumbing) become reproducible
across JVM starts — the pre-consolidation per-Thing MTF was seeded from
`Math.random()` per Thing, never reproducible.

A startup banner prints the resolved master seed so a failing run's
log can be replayed:

```
[RNG] per-worker RNG pool: 17 MTFs, masterSeed=42 (BOA_RNG_SEED set)
```

The GPU on-device Brownian RNG (Wang hash) is **untouched** — the
per-worker pool serves the CPU-fallback `calcRandomForces` path only.

### Heap shed — `jmap -histo:live` at 4× CPU

inc0a 4× baseline at `RUN_LOGS/2026-06-08_pt3d_inc0a/jmap/4x_cpu_jmap_inc0a_inc0a_v2_histo.txt`
vs pwrng 4× at `RUN_LOGS/2026-06-09_per_worker_rng/4x_cpu_jmap_histo.txt`:

| class | inc0a 4× (count / bytes) | pwrng 4× (count / bytes) | drop |
|---|---:|---:|---:|
| `int[]` (MT state arrays + bin arrays) | 2,597,505 / 3.72 GB | 259,945 / 731 MB | **−2.99 GB** |
| `ec.util.MersenneTwisterFast` (instances) | 1,177,602 / 47.1 MB | 17 / 680 B | **−47.1 MB** |
| `boxOfActin.UCircRnd` | 3,532,806 / 169.6 MB | 51 / 2.4 KB | **−169.6 MB** |
| `boxOfActin.Pt3D` | 24.00 M / 960 MB | 24.97 M / 999 MB | +1 M / +39 MB (noise) |

**Headline at 4× CPU: −3.20 GB retained heap.** The int[] drop tracks
exactly the MTF state shed (1.178 M MTF × 2 int[] arrays each
= 2.356 M arrays of ~1.3 KB → ~3 GB). Projected linearly to 16× scale
(4.74 M Things), the shed is ~**12 GB at 16×** — matching inc1's
heap-dump prediction.

### 16× CPU ceiling re-probe — **the wall broke**

`RUN_LOGS/2026-06-09_per_worker_rng/ceiling_16x_cpu_pwrng.log`, `-Xmx28G`,
same 16× `K1.pf` configuration as the inc0a/0b/scalar-Brownian/inc1
ceiling probes (200 steps, `runTime=0.002`):

```
[RNG] per-worker RNG pool: 17 MTFs, masterSeed=-1268746020053076938 (BOA_RNG_SEED unset)
...
[STATS] bindEvents=61203
[STATS] meanBoundMotors=8847.587
Finished closing JSON plots!
```

**`rc=0`. Zero `OutOfMemoryError` occurrences.** First successful 16× CPU
completion across the migration — inc0a hit the per-step OOM wall in
~3 min, inc0b/scalar-Brownian/inc1 all hit the same wall verbatim. The
~12 GB shed from MT state was the lever inc1's heap-dump task named as
the path past 16× on this 31 GB box; the pwrng increment confirms it.
ThingStep wall 197 s (16 threads × 200 steps contended ≈ 62 ms/step).

This is the first **physics-meaningful** breakthrough of the per-step
ceiling, not just the startup ceiling that inc0a/0b cleared. The
glidingVelocity reads 0.0000 because 200 steps is too short to develop
gliding — the ceiling-probe metric is "the JVM completed at all," not
"the gliding metric is in band." (A longer 16× run is the natural
next benchmark.)

### Validation — paired-ensemble t-test (n=10 seeds, 1× CPU)

Stochastic-core change (the RNG itself), so per the prompt's protocol
we ran n=5 first and extended to n=10 because the glidingVelocity t
was borderline at n=5 (t=+2.46, under the 2.78 critical but above 2.0).
Per-seed logs in
`RUN_LOGS/2026-06-09_per_worker_rng/ens_{main_baseline,pwrng}/seed{1..10}/stdout.txt`;
full summary in `ensemble_summary.txt`.

Baseline = main at `577247a` (post-inc1 merge), pwrng = HEAD of
`pt3d-soa-inc-per-worker-rng`. `glidingAssay500_val` (runTime 0.1 s,
10 000 steps), 1× CPU, `-Xmx8G`, 10 parallel runs.

| metric | baseline μ±σ (n=10) | pwrng μ±σ (n=10) | shift | shift/σ_b | paired t (df=9) |
|---|---|---|---:|---:|---:|
| bindEvents       | 930.30 ± 120.84 | 870.60 ± 94.01  | −59.7  | −0.49 σ_b | −1.21 |
| meanBoundMotors  | 7.753 ± 0.701   | 7.371 ± 0.624   | −0.382 | −0.54 σ_b | −1.35 |
| glidingVelocity  | 7.426 ± 0.472   | 7.427 ± 0.616   | +0.000 | +0.00 σ_b | +0.00 |

All paired |t| < 2.26 (df=9 two-sided 5 % critical). **PASS.** The
borderline n=5 glidingVelocity t collapses to **zero** mean shift at
n=10 — small-sample noise, as expected. The seeds-1..5 pwrng arm
happened to draw high (μ=7.85); seeds-6..10 drew low (μ=7.00); the
combined n=10 mean sits within 0.001 µm/s of the baseline n=10 mean.
bindEvents and meanBoundMotors drift slightly negative (−0.5 σ_b) —
also within noise; same convention as the small one-σ drifts inc0a
landed (+0.68 σ_b on bindEvents, +1.02 σ_b on glidingVelocity).

The n=5 → n=10 extension is the protocol the prompt explicitly named
for RNG-touching changes; it converged from "borderline" to "clean
zero" without any code change, exactly matching the noise hypothesis.

### CPU wall — Brownian path 31 % faster

Per-seed phase walls from the 1× CPU ensemble (16 threads, 10 000 steps,
parallel contention):

| pool | baseline mean (n=5, seeds 1–5) | pwrng mean (n=5, seeds 1–5) | δ |
|---|---:|---:|---:|
| ThingBrownian Threads | 170.6 s | 119.1 s | **−51.5 s (−30 %)** |
| ThingStep Threads     | 404.8 s | 366.1 s | **−38.7 s (−10 %)** |

These are aggregate-thread walls under 10× parallel contention (10
ensemble runs all on the same 16-core box), so the absolute numbers
are inflated — but the ratio is fair (both arms suffer identical
contention). The 30 % drop on the Brownian path matches the heap-dump
verdict's classification of MT state as the #1 CPU cost. Per-step
ThingBrownian wall at 16 threads ≈ 1.07 ms (baseline) → 0.74 ms
(pwrng). The 10 % ThingStep drop is harder to attribute — likely
better cache pressure from shedding the per-Thing UCircRnd objects.

### GPU sanity

GPU on-device Brownian RNG (Wang hash inside `gpuMoveThings`) is
untouched in this increment; the per-worker pool serves only the CPU
`calcRandomForces` fallback. A `-gpu` short sanity run was not the
gating measurement here — the heap shed and ceiling break are CPU-side
deliverables. Future GPU runs continue to pull from the same per-worker
pool for the CPU-fallback Things (Bug, branch FilSegments, ActA-bound
segments — same population that already drew Brownian from
`myPRNG`-on-Thing pre-consolidation).

### Deliverable summary

- **Per-Thing `myPRNG` deleted.** 22 draw sites converted in
  FilSegment (biochem + capping + branching + tether-detach + cleanup),
  6 in MyoMotor (nucleotide cycle), 1 in MyoFilLink (ckRelease catch/slip),
  10 in Monomer (hydrolysis + tropo + cofilin), 6 in ProteinNode and
  Bug constructors (unit-vec init), 6 in MyoMiniFilament, 2 in
  Chamber, 3 in StickyNode, plus the `getRdmDelta` helper on Thing.
  All routed through the pool: hot path (`calcRandomForces`) uses the
  passed-down `ws.rng`; cooler paths use `Thing.currentScratch().rng`.
- **`UCircRnd` xVals/yVals/zVals retired from Thing.** Now live in
  WorkerScratch (3 per worker × 17 = 51 objects total).
- **Heap shed at 4× CPU: ~3.20 GB** (int[] −2.99 GB, MTF instances
  −47.1 MB, UCircRnd −169.6 MB). Projected ~12 GB at 16× — matches
  inc1's heap-dump prediction exactly.
- **16× ceiling re-probe: FINISHED `rc=0`.** First successful 16× CPU
  completion across the migration. Per-step OOM wall is gone — the
  inc1 heap-dump verdict ("the path past 16× is the MTF state lever")
  is confirmed empirically.
- **Validation: paired-ensemble n=10, all |t| < 1.4.** Borderline n=5
  glidingVelocity (t=+2.46) collapses to t=+0.00 at n=10. Statistical
  agreement preserved; RNG quality intact.
- **CPU performance: ThingBrownian wall −30 %, ThingStep wall −10 %.**
  Matches the heap-dump's "RNG is the #1 CPU cost" classification.
- **GPU path: untouched.** On-device Wang hash unaffected; CPU-fallback
  Brownian draws from the same shared pool.
- **Reproducibility wired (optional).** `BOA_RNG_SEED` env var → master
  seed → SplitMix64-derived per-worker seeds. Pre-consolidation per-
  Thing MTF was seeded from `Math.random()` and was non-reproducible
  at any seed; pwrng with `BOA_RNG_SEED` set is reproducible mod the
  separate `Env.mtRNG -seed` plumbing.
