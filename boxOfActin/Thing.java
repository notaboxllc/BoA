package boxOfActin;
/*
	// Thing.... the superclass for all moving objects in this demonstration
*/
import java.awt.*;
import java.text.DecimalFormat;

import edu.cornell.lassp.houle.RngPack.RanMT;
import ec.util.MersenneTwisterFast;

import java.util.Date;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class Thing extends Object {
	
	static Thing [] theThings = new Thing [32000000];	// array of all things
	static int thingCt = 0;								// how many things
	// Fault-injection diagnostic (2026-06-11): when set, removeThing() does NOT
	// signal topologyDirty on a swap-compaction. This deliberately reintroduces
	// the historical GPU slot-map staleness that produced the packRange
	// MyoMiniFilament->FilSegment ClassCastException, so the pack-dispatch
	// desync guard (GPUMoveThing.reconcilePackRule) can be exercised. OFF in
	// all production runs.
	static final boolean DISABLE_TOPODIRTY_ON_SWAP =
		"1".equals(System.getenv("BOA_DISABLE_TOPODIRTY_ON_SWAP"));
	static Crucible theBox;								// only one bug/box
	static Bug lmBug;				// only one listeria
	// for efficiency
	static final Pt3D xUnitVector = new Pt3D(1,0,0);	// unit vector along body-fixed x-axis
	static final Pt3D yUnitVector = new Pt3D(0,1,0);	// unit vector along body-fixed y-axis
	static final Pt3D zUnitVector = new Pt3D(0,0,1);	// unit vector along body-fixed z-axis
	static final Pt3D zeroVec = new Pt3D();	// just a zero Pt3D
	private static int nextThingInstanceId = 0;
	private static final ConcurrentHashMap<Integer, Thing> instanceRegistry = new ConcurrentHashMap<>();
	public final int thingInstanceId;     // stable identity; set once at construction, never reassigned (unlike myThingNumber)
	final int createdAtStep;              // Env.counter at construction, for age reporting
	int myThingNumber;					// identifies where in "theThings" this Thing is; reassigned on swap-cleanup
	boolean removeMe = false;			// if true this Thing will be eliminated
	boolean gpuHandled = false;			// iter2c: set by GPUMoveThing.classifyThings(); when true and Env.useGPU,
										// CPU calcRandomForces() skips this Thing (kernel generates Brownian inline)
	// Canonical pose/orientation lives in static SoA arrays soaCoord/soaUVec/
	// soaYVec (and soaEnd1/soaEnd2/soaZVec/soaTransXTox derived). The Pt3D
	// coord/uVec/yVec/zVec/end1/end2/uVecR fields and the per-Thing transXTox/
	// transxToX double[9] arrays were removed in the SoA derived-field-removal
	// landing (2026-05-30). Readers use the per-component accessors
	// (getCoordX/Y/Z, getUVecX/Y/Z, getEnd1X/Y/Z, getTransXTox(idx), etc.);
	// writers use setCoord/setUVec/setYVec or the SoA arrays directly.
	static Pt3D maxPos = Env.worldDimension;	// maximum x position this Thing can occupy
	Pt3D veloc = new Pt3D();			// the fixed frame translational velocity values Xdot, Ydot, Zdot
	Pt3D angVeloc = new Pt3D();		// the angular velocities psidot, thetadot, phidot
	Pt3D bVeloc = new Pt3D(); 		// the body-fixed frame velocities xdot, ydot, zdot
	Pt3D bAngVeloc = new Pt3D();	// the body-fixed frame angular velocities Wx, Wy, Wz
	Pt3D deltaBAng = new Pt3D();	// rotation of body-fixed axes in moveThing ()
	Pt3D bTransGam = new Pt3D(); 	// body-fixed viscous translational resistances in (x,y,z}.
	Pt3D bRotGam = new Pt3D();		// body-fixed viscous rotational coefficients (Wx, Wy, Wz)
	Pt3D bTransDiff = new Pt3D();		// body-fixed translational diffusion coefficients, from bTransGam through Einstein's relation
	Pt3D bRotDiff = new Pt3D();		// body-fixed rotational diffusion coefficients
	Pt3D randForces = new Pt3D();		// random translational forces (Fx,Fy,Fz)
	Pt3D randTorques = new Pt3D();	// random rotational torques (Tx,Ty,Tz)
	// Canonical force/torque storage is the static soaForceSum/soaTorqueSum
	// float[] arrays below, indexed by myThingNumber*3+{0,1,2}. The Pt3D fields
	// were removed when the SoA conversion landed; readers go through the
	// static helpers (getForceSumX/Y/Z, zeroForceSumSlot, etc.) or read the
	// soaForceSum/soaTorqueSum arrays directly.
	Pt3D bForceSum = new Pt3D();		// body-fixed force sum
	Pt3D bTorqueSum = new Pt3D();		// body-fixed torque sum
	Pt3D bFricForceSum = new Pt3D();	// friction forces are implemented, and stay, in the body-fixed frame
	Pt3D bFricTorqueSum = new Pt3D();

	// Per-thread force/torque accumulators. Worker threads write to a private slot
	// indexed by [threadId][thingNumber*3 + axis]; gatherThreadAccumulators() sums
	// them (narrowed to float) into soaForceSum/soaTorqueSum once per step before
	// moveThing. tlsThreadId is set in ThreadSpawn.run() at thread startup; the
	// main thread leaves it at -1 and writes directly to soaForceSum/soaTorqueSum
	// (no contention between phases).
	// Must be initialised BEFORE the ThreadSet pools below, since ThreadSpawn.run()
	// reads tlsThreadId on its first scheduling.
	static final ThreadLocal<int[]> tlsThreadId = ThreadLocal.withInitial(() -> new int[]{-1});
	static final int accumThreadCt = Env.allThreadCt;
	static double[][] taForce  = new double[accumThreadCt][0];
	static double[][] taTorque = new double[accumThreadCt][0];
	// Canonical SoA force/torque storage. Layout: [fx0,fy0,fz0, fx1,fy1,fz1, ...]
	// indexed by myThingNumber*3+axis. Matches the GPU FloatArray layout in
	// GPUMoveThing so the per-step pack can read this array contiguously. The
	// gather pass narrows the per-thread double accumulators into these floats.
	static float[] soaForceSum  = new float[0];
	static float[] soaTorqueSum = new float[0];
	// Canonical SoA pose storage (coord/uVec/yVec). Same layout as soaForceSum;
	// matches the GPU coord/uVec/yVec FloatArrays so the per-step pack/unpack
	// can read and write tightly. The Pt3D coord/uVec/yVec fields are kept
	// as a CPU-reader bridge — initialize() copies SoA → Pt3D each call.
	// Writers (moveThing, constructors, translate, biochem coord.inc, ...)
	// flush Pt3D → SoA via pushPoseToSoa()/pushCoord/pushUVec/pushYVec before
	// initialize() runs.
	static float[] soaCoord = new float[0];
	static float[] soaUVec  = new float[0];
	static float[] soaYVec  = new float[0];
	// SoA derived-field storage. Populated by recomputeDerivedSoA() from the
	// canonical coord/uVec/yVec arrays + soaLength; mirrored back into Pt3D
	// end1/end2/zVec and transXTox/transxToX by bridgeDerivedToPt3D() so
	// CPU readers keep working. soaLength is one float per Thing (slot
	// index = myThingNumber); the per-Thing `length` value lives in the
	// FilSegment.length field or the static `length` in MyoMiniFilament/Bug
	// and is pushed via pushLengthToSoa() at construction (or whenever the
	// length changes for a FilSegment — poly/depoly/split).
	static float[] soaEnd1      = new float[0];   // [x0,y0,z0, x1,y1,z1, ...]
	static float[] soaEnd2      = new float[0];
	static float[] soaZVec      = new float[0];
	static float[] soaTransXTox = new float[0];   // 9 floats per Thing: row-major 3×3 fixed→body
	static float[] soaLength    = new float[0];   // 1 float per Thing
	// Inc2 (2026-06-09) — body-fixed drag tensor SoA. Dual-written by
	// calculateProperties() alongside the bTransGam/bRotGam Pt3D fields the
	// ~30 CPU readers consume; GPUMoveThing.packRange()/packJointsRange() read
	// from these arrays contiguously instead of pointer-chasing through Pt3D.
	// Layout: 3 floats per Thing, indexed by myThingNumber*3+{0,1,2}.
	static float[] soaBTransGam = new float[0];
	static float[] soaBRotGam   = new float[0];
	// Sparse gather bookkeeping: each worker thread records the indices it actually
	// wrote to (deduped via dirtyFlags). gatherThreadAccumulators() walks only those
	// entries instead of sweeping every Thing × every thread.
	static int[][]     dirtyIndices = new int[accumThreadCt][0];
	static boolean[][] dirtyFlags   = new boolean[accumThreadCt][0];
	static int[]       dirtyCounts  = new int[accumThreadCt];
	static int taCapacity = 0;
	// Diagnostic counters: cumulative sparse-gather stats, printed every 1000 calls.
	static long gatherTotalEntriesAllTime = 0;
	static int  gatherMaxEntriesAllTime   = 0;
	static int  gatherCallCount           = 0;

	// multithreading
	static ThingStepThreads stepThreads = new ThingStepThreads();
	static ThingBrownianThreads brownianThreads = new ThingBrownianThreads();
	// Per-Thing MersenneTwisterFast myPRNG used to live here — at 16× this was
	// 1.568 M MT instances × ~2.5 KB state = ~12 GB retained (#1 line item in
	// inc1's heap-dump verdict, 43 % of the 27.74 GB OOM heap). Pt3D SoA
	// migration "per-worker RNG consolidation" moves the PRNG into
	// WorkerScratch — one MTF per worker (+ one for the main thread) seeded
	// via SplitMix64 from a master for independent, well-mixed streams.
	// CollisionEvent scratch used to live here (3 Pt3D × Thing.thingCt — 3.53 M
	// at 4×). Pt3D SoA migration increment 0a sub-item (c) moves it into
	// WorkerScratch; collision-check methods cache currentScratch().cE locally.
	
	// averaging of forces for stability
	//ValueTracker bForceTrack = new ValueTracker(Env.forcesToTrack,ValueTracker.PT3D_TYPE);
	//ValueTracker bTorqueTrack = new ValueTracker(Env.forcesToTrack,ValueTracker.PT3D_TYPE);
	
	//	 for collision tests — RetObj scratch (6 Pt3D × Thing.thingCt — 7.07 M at
	// 4×) used to be a per-Thing field. Pt3D SoA migration increment 0a
	// sub-item (b) moves it to WorkerScratch; callers use currentScratch().retObj.
	boolean collidedWithBugThisStep = false;
	int collisionCt = 0; 	// keep track of number of collisions at each time-step
	double lastCollisionTime = 0; // stores sim. time of last collision
	
	//	different time-steps for sim pieces
	static int collisionCheckInt, biochemCheckInt,brownianApplyInt;
	// crosslinkCheckInt (2026-06-12): crosslink FORMATION cadence (broad-phase +
	// checkToLink + makeLink fire every crosslinkCheckInt steps, not every
	// collision step). Default = biochemCheckInt; derived in Env.setTimeStepCounts
	// from Env.crosslinkDeltaT. Formation is a biochem-class stochastic event, so it
	// rides the biochem-cadence pose pull on the -gpu residency path.
	static int crosslinkCheckInt;
	int collCheckCt, biochemCheckCt;
	
	// Per-Thing UCircRnd xVals/yVals/zVals used to live here — three pure-
	// scratch objects (v1,v2,rsq,facterm) retained per Thing, ~14.21 M
	// instances / 455 MB at 16×. The per-worker RNG consolidation moves them
	// into WorkerScratch alongside the rng (write-before-read inside
	// calcRandomForces, no carryover, so per-worker is sufficient).
	// Per-Thing Brownian/torque/friction scratch (v1,v2,rsq,facterm,fac1,fac2,tempPt,
	// rForce,tempTorq) used to be Pt3D fields here — 9 Pt3D × Thing.thingCt of
	// retained scratch with no semantics surface. Pt3D SoA migration increment 0a
	// moves them to per-worker-thread WorkerScratch instances looked up by the
	// integer in tlsThreadId. Brownian path (the hot one) passes WorkerScratch
	// down as a parameter (one array load per worker, not per Thing) — see
	// ThingBrownianThreads.execute and calcRandomForces(WorkerScratch).
	
	static DecimalFormat expFormat = new DecimalFormat ("0.000E0");
	
	public Thing (Pt3D initCoord) {
		this.thingInstanceId = nextThingInstanceId++;
		this.createdAtStep   = Env.counter;
		instanceRegistry.put(this.thingInstanceId, this);
		addThing(this);
		// Seed canonical SoA pose with initial coord and default uVec/yVec
		// (1,0,0)/(0,1,0). Subclass constructors that overwrite uVec/yVec call
		// setUVec/setYVec or write soaUVec/soaYVec directly.
		setCoord(initCoord.x, initCoord.y, initCoord.z);
		setUVec(1, 0, 0);
		setYVec(0, 1, 0);
	}
	
	// Per-worker scratch — owns the working memory that used to be retained as
	// per-Thing Pt3D fields. One instance per worker thread (indexed by the
	// integer in tlsThreadId); the last slot (accumThreadCt) is the main thread
	// (tlsThreadId == -1). Lifetime is the JVM; allocations are O(workers), not
	// O(Things). Sub-item (a) of Pt3D SoA increment 0a holds the 9 Brownian /
	// torque / friction Pt3Ds; (b) adds RetObj, (c) adds CollisionEvent. The
	// per-worker RNG consolidation increment adds `rng` and the three UCircRnd
	// scratch instances (used to be 1 + 3 per Thing).
	public static final class WorkerScratch {
		// Brownian RNG scratch (v1/v2/rsq/facterm/fac1/fac2/tempPt Pt3Ds) was
		// removed when calcRandomForces was scalarized — the per-component math
		// runs in stack-local doubles now.
		// Torque/friction scratch — written/read inside incFrictionSum only.
		final Pt3D rForce   = new Pt3D();
		final Pt3D tempTorq = new Pt3D();
		// Line/point-intersect return-by-mutation. Reused across collision /
		// xLink checks on the same worker; lifetime is the single test call.
		final RetObj retObj = new RetObj();
		// Bug/Chamber collision return-by-mutation. Same lifetime/ownership
		// pattern as retObj — written by amICollidingOuter/Inner/FromOutside,
		// read by the immediate next caller block.
		final CollisionEvent cE = new CollisionEvent();
		// Per-worker move scratch — reused by MyoRod/MyoLever/MyoMotor.moveThing
		// to hold the unrotated body-frame {1,uy,uz} / {yx,1,yz} triple before
		// xToX/unitVec/setUVec/setYVec. Each use begins with a full setVals(...)
		// call (write-before-read), so no carryover. Pt3D SoA migration
		// increment 0b sub-(a) — these used to be `new Pt3D()` per call,
		// 1.18 M/step at 4× config.
		final Pt3D moveScratch = new Pt3D();
		// Per-worker PRNG. Replaces the per-Thing `myPRNG` MersenneTwisterFast
		// (~12 GB retained at 16× — see PT3D_SOA_MIGRATION.md "per-worker RNG
		// consolidation"). Independent streams across workers; each MTF is
		// seeded via SplitMix64-derived seeds from a master in the static
		// initializer below, preserving MT statistical quality.
		final MersenneTwisterFast rng;
		// Brownian uniform-deviate scratch (v1,v2,rsq,facterm) for the three
		// translational/rotational axes. Used to be three UCircRnd fields per
		// Thing (~14.21 M / 455 MB at 16×). Pure scratch — written by
		// UCircRnd.newValue, read immediately by calcRandomForces, no
		// carryover across calls.
		final UCircRnd xVals;
		final UCircRnd yVals;
		final UCircRnd zVals;
		WorkerScratch (long seed) {
			rng   = new MersenneTwisterFast(seed);
			xVals = new UCircRnd(Env.deltaT.getValue());
			yVals = new UCircRnd(Env.deltaT.getValue());
			zVals = new UCircRnd(Env.deltaT.getValue());
		}
	}

	// Master seed for the per-worker RNG pool. Configurable via the
	// `BOA_RNG_SEED` env var (parsed as a long); falls back to a high-entropy
	// mix of System.nanoTime() and System.currentTimeMillis() if unset. With a
	// fixed BOA_RNG_SEED *and* a fixed thread count *and* a deterministic
	// work-split, the per-worker streams (and therefore the simulation as a
	// whole, modulo any other entropy sources like Env.mtRNG) become
	// reproducible — pre-consolidation runs were not reproducible (per-Thing
	// MTF was seeded from `Math.random()` per Thing).
	private static long resolveMasterSeed () {
		String s = System.getenv("BOA_RNG_SEED");
		if (s != null && !s.isEmpty()) {
			try { return Long.parseLong(s); } catch (NumberFormatException e) {
				System.err.println("[BOA_RNG_SEED] could not parse '" + s + "' as long; falling back to entropy");
			}
		}
		long nano = System.nanoTime();
		long ms   = System.currentTimeMillis();
		return nano ^ (ms * 0x9E3779B97F4A7C15L);
	}
	static final long RNG_MASTER_SEED = resolveMasterSeed();
	// SplitMix64 — the standard "seed-the-MTs" splitter (also what JDK's
	// SplittableRandom uses). Stateless mix; given a counter, returns a
	// well-distributed 64-bit output. Used to derive accumThreadCt+1
	// independent seeds from RNG_MASTER_SEED in a way that avoids the close-
	// seed correlations possible when seeding MT with adjacent integers.
	private static long splitMix64 (long z) {
		z = (z + 0x9E3779B97F4A7C15L);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static final WorkerScratch[] workerScratch;
	static {
		workerScratch = new WorkerScratch[accumThreadCt + 1];
		// Derive (accumThreadCt + 1) independent seeds via SplitMix64. Slot
		// accumThreadCt is the main-thread slot.
		long counter = RNG_MASTER_SEED;
		for (int i = 0; i < workerScratch.length; i++) {
			counter = splitMix64(counter);
			workerScratch[i] = new WorkerScratch(counter);
		}
		System.out.println("[RNG] per-worker RNG pool: " + workerScratch.length
			+ " MTFs, masterSeed=" + RNG_MASTER_SEED
			+ " (BOA_RNG_SEED " + (System.getenv("BOA_RNG_SEED") != null ? "set" : "unset") + ")");
	}
	// Main thread (tlsThreadId == -1) uses the last pool slot. Worker threads
	// use their threadId directly. Callers in the Brownian hot path should
	// resolve once per worker and pass the WorkerScratch down — see
	// ThingBrownianThreads.execute and brownianMotionForAll. Cooler paths
	// (incFrictionSum, collision tests in (b)/(c)) can call currentScratch().
	public static WorkerScratch currentScratch() {
		final int tid = tlsThreadId.get()[0];
		return workerScratch[tid < 0 ? accumThreadCt : tid];
	}

	public static class RetObj {
		// this is the object passed by from line-line and line-point intersect tests
		Pt3D conPt1, conPt2, ray1, ray2, ray3, ray4;
		double conDistSq = 0;  // squared contact distance — callers compare against threshold²
		double alpha, beta;	 // the coefficients of ray1 and ray2, respectively, to define contact pts from end1s
		boolean collision = false;
		
		public RetObj () {
			conPt1 = new Pt3D();
			conPt2 = new Pt3D();
			ray1 = new Pt3D();
			ray2 = new Pt3D();
			ray3 = new Pt3D();
			ray4 = new Pt3D();
		}
		
		public void reset () {
			collision = false;
		}
	}
	
	static class ThingStepThreads extends ThreadSet {
		ThingStepThreads () {
			super (Env.numThingStepThreads, "ThingStep Threads");
		}

		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.stepStart:
				case Env.moveStart:
				case Env.biochemStart:
				case Env.resetCtStart:
				case Env.gatherForcesStart:
					if (thingCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.stepStop:
				case Env.moveStop:
				case Env.biochemStop:
				case Env.resetCtStop:
				case Env.gatherForcesStop:
					if (thingCt == 0) return;
					gather(); break;
			}
		}

		public void execute (int threadId) {
			switch (jobId) {
				case Env.stepStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						try { if (!theThings[i].removeMe) { theThings[i].step(); } } catch (NullPointerException npe) { System.out.println("npe in Thing.step");}
					}
					break;
				case Env.moveStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (!theThings[i].removeMe) { theThings[i].moveThing(); }
					}
					break;
				case Env.biochemStart:
					//Thread cThread = Thread.currentThread();
					//System.out.println (cThread.getName() + " is working on Things " + String.valueOf(jobDiv[threadId]) + " to " + String.valueOf(jobDiv[threadId+1]));
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (!theThings[i].removeMe) { theThings[i].biochemStep(); }
					}
					break;
				case Env.resetCtStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (!theThings[i].removeMe) { theThings[i].resetCounters(); }
					}
					break;
				case Env.gatherForcesStart:
					// Sparse gather: cheap enough to run single-threaded (Solution A
					// from the design doc). Other workers no-op; the start/wait
					// barrier still timestamps the phase via gatherTimer.
					if (threadId == 0) { gatherThreadAccumulators(); }
					break;
			}
		}
	}
	
	static class ThingBrownianThreads extends ThreadSet {
		ThingBrownianThreads () {
			super (Env.numBForceThreads, "ThingBrownian Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.bForcesStart:
					if (thingCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.bForcesStop:
					if (thingCt == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.bForcesStart:
					// Resolve worker scratch once per dispatch; pass down so
					// calcRandomForces stays out of ThreadLocal.get() in the
					// per-Thing inner loop (~40% of CPU per-step).
					final WorkerScratch ws = workerScratch[threadId];
					if (Env.useGPU) {
						// iter2c: GPU kernel generates Brownian inline via Wang hash.
						// Skip CPU calcRandomForces for Things flagged by
						// GPUMoveThing.classifyThings() — only CPU-fallback Things
						// (Bug, branch FilSegments, etc.) still need it.
						for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
							Thing t = theThings[i];
							if (!t.removeMe && !t.gpuHandled) { t.calcRandomForces(ws); }
						}
					} else {
						for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
							if (!theThings[i].removeMe) { theThings[i].calcRandomForces(ws); }
						}
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		// pose/orientation/end fields live in static SoA arrays; nothing to null here.
		veloc = null;
		angVeloc = null;
		bVeloc = null;
		bAngVeloc = null;
		deltaBAng = null;
		bTransGam = null;
		bRotGam = null;
		bTransDiff = null;
		bRotDiff = null;
		randForces = null;
		randTorques = null;
		bForceSum = null;
		bTorqueSum = null;
		bFricForceSum = null;
		bFricTorqueSum = null;
		// myPRNG is no longer a per-Thing field (moved to WorkerScratch in the
		// per-worker RNG consolidation increment).
		// cE is no longer a per-Thing field (moved to WorkerScratch).

		//bForceTrack = null;
		//bTorqueTrack = null;

		// retObj is no longer a per-Thing field (moved to WorkerScratch).

		// xVals/yVals/zVals UCircRnd scratch is no longer a per-Thing field
		// (moved to WorkerScratch in the per-worker RNG consolidation).
		// v1/v2/rsq/facterm/fac1/fac2/tempPt and rForce/tempTorq are no longer
		// per-Thing fields (moved to per-thread WorkerScratch in increment 0a).
	}
	
	public void initialize(){}
	public void calculateProperties() {}
	public void step () {
		// put the code here to move this object each time-step
	}
	public void moveThing() {}
	public void biochemStep() {}
	
	public void drawYourself (Graphics g, double scale, double [] offset) {
		// put the code here to draw the object on "g"
	}
	
	public void incForceSum (Pt3D forceToAdd) {
		final int tid = tlsThreadId.get()[0];
		final int idx = myThingNumber;
		final int base = idx * 3;
		if (tid < 0) {
			soaForceSum[base]     += (float) forceToAdd.x;
			soaForceSum[base + 1] += (float) forceToAdd.y;
			soaForceSum[base + 2] += (float) forceToAdd.z;
		} else {
			final double[] f = taForce[tid];
			f[base]     += forceToAdd.x;
			f[base + 1] += forceToAdd.y;
			f[base + 2] += forceToAdd.z;
			final boolean[] flags = dirtyFlags[tid];
			if (!flags[idx]) {
				flags[idx] = true;
				dirtyIndices[tid][dirtyCounts[tid]++] = idx;
			}
		}
	}

	public void incForceSum (Pt3D forceToAdd, Pt3D forcePoint) {
		// r = (forcePoint - coord) * 1e-6 (µm → m), torque = r × force, fused write.
		final double rx = (forcePoint.x - getCoordX()) * 1e-6;
		final double ry = (forcePoint.y - getCoordY()) * 1e-6;
		final double rz = (forcePoint.z - getCoordZ()) * 1e-6;
		final double fx = forceToAdd.x, fy = forceToAdd.y, fz = forceToAdd.z;
		final double tx = ry*fz - rz*fy;
		final double ty = rz*fx - rx*fz;
		final double tz = rx*fy - ry*fx;
		final int tid = tlsThreadId.get()[0];
		final int idx = myThingNumber;
		final int base = idx * 3;
		if (tid < 0) {
			soaForceSum[base]      += (float) fx;
			soaForceSum[base + 1]  += (float) fy;
			soaForceSum[base + 2]  += (float) fz;
			soaTorqueSum[base]     += (float) tx;
			soaTorqueSum[base + 1] += (float) ty;
			soaTorqueSum[base + 2] += (float) tz;
		} else {
			final double[] f = taForce[tid];
			final double[] q = taTorque[tid];
			f[base]     += fx; f[base + 1] += fy; f[base + 2] += fz;
			q[base]     += tx; q[base + 1] += ty; q[base + 2] += tz;
			final boolean[] flags = dirtyFlags[tid];
			if (!flags[idx]) {
				flags[idx] = true;
				dirtyIndices[tid][dirtyCounts[tid]++] = idx;
			}
		}
	}

	public void incTorqueSum (Pt3D torqueToAdd) {
		final int tid = tlsThreadId.get()[0];
		final int idx = myThingNumber;
		final int base = idx * 3;
		if (tid < 0) {
			soaTorqueSum[base]     += (float) torqueToAdd.x;
			soaTorqueSum[base + 1] += (float) torqueToAdd.y;
			soaTorqueSum[base + 2] += (float) torqueToAdd.z;
		} else {
			final double[] q = taTorque[tid];
			q[base]     += torqueToAdd.x;
			q[base + 1] += torqueToAdd.y;
			q[base + 2] += torqueToAdd.z;
			final boolean[] flags = dirtyFlags[tid];
			if (!flags[idx]) {
				flags[idx] = true;
				dirtyIndices[tid][dirtyCounts[tid]++] = idx;
			}
		}
	}

	// ---- SoA force/torque accessors ----------------------------------------
	// Canonical storage is soaForceSum/soaTorqueSum. These helpers keep the
	// existing call sites (NaN checks, "Crazy forceSum" recovery, benchmark
	// prints, membrane internalPressure increments) terse.

	public float getForceSumX()  { return soaForceSum[myThingNumber * 3];     }
	public float getForceSumY()  { return soaForceSum[myThingNumber * 3 + 1]; }
	public float getForceSumZ()  { return soaForceSum[myThingNumber * 3 + 2]; }
	public float getTorqueSumX() { return soaTorqueSum[myThingNumber * 3];     }
	public float getTorqueSumY() { return soaTorqueSum[myThingNumber * 3 + 1]; }
	public float getTorqueSumZ() { return soaTorqueSum[myThingNumber * 3 + 2]; }

	public boolean isForceSumFinite() {
		final int b = myThingNumber * 3;
		return !(Float.isNaN(soaForceSum[b]) | Float.isNaN(soaForceSum[b+1]) | Float.isNaN(soaForceSum[b+2]));
	}

	public boolean isTorqueSumFinite() {
		final int b = myThingNumber * 3;
		return !(Float.isNaN(soaTorqueSum[b]) | Float.isNaN(soaTorqueSum[b+1]) | Float.isNaN(soaTorqueSum[b+2]));
	}

	public void zeroForceSumSlot() {
		final int b = myThingNumber * 3;
		soaForceSum[b] = 0f; soaForceSum[b+1] = 0f; soaForceSum[b+2] = 0f;
	}

	public void zeroTorqueSumSlot() {
		final int b = myThingNumber * 3;
		soaTorqueSum[b] = 0f; soaTorqueSum[b+1] = 0f; soaTorqueSum[b+2] = 0f;
	}

	// Replaces "forceSum.zero(); forceSum.inc(randForces);" pattern used by
	// Myo*/MyoMiniFilament/MyoMotor "Crazy forceSum" recovery paths.
	public void setForceSumToRandForces() {
		final int b = myThingNumber * 3;
		soaForceSum[b]     = (float) randForces.x;
		soaForceSum[b + 1] = (float) randForces.y;
		soaForceSum[b + 2] = (float) randForces.z;
	}

	public void setTorqueSumToRandTorques() {
		final int b = myThingNumber * 3;
		soaTorqueSum[b]     = (float) randTorques.x;
		soaTorqueSum[b + 1] = (float) randTorques.y;
		soaTorqueSum[b + 2] = (float) randTorques.z;
	}

	// Direct slot increment used by post-gather writers (StickyNode.internalPressure
	// etc.) — must NOT go through taForce because gather has already run for this
	// pass and the next gather wouldn't pick up the contribution until too late.
	public void incForceSumSlot(double fx, double fy, double fz) {
		final int b = myThingNumber * 3;
		soaForceSum[b]     += (float) fx;
		soaForceSum[b + 1] += (float) fy;
		soaForceSum[b + 2] += (float) fz;
	}

	// Bulk zero called at the start of each step before any force-producing
	// phase. One memset over the active slot range — microseconds at any scale.
	public static void clearSoaForcesTorques(int upTo) {
		final int n = upTo * 3;
		if (n <= 0) return;
		java.util.Arrays.fill(soaForceSum,  0, n, 0f);
		java.util.Arrays.fill(soaTorqueSum, 0, n, 0f);
	}

	// Lazy-grow the per-thread accumulators and the canonical SoA arrays.
	// Called at the top of doLoop() AND from addThing() so initial
	// construction (which runs before doLoop) has capacity to receive
	// pushPoseToSoa() calls. dirtyIndices/dirtyFlags grow with
	// taForce/taTorque so workers can never overflow (no thread can dirty
	// more distinct slots than the Thing count). soaForceSum/soaTorqueSum
	// and soaCoord/soaUVec/soaYVec grow in lockstep so myThingNumber*3
	// indexing is always valid.
	public static synchronized void ensureAccumCapacity (int needed) {
		if (needed <= taCapacity) return;
		int newCap = Math.max(needed + (needed >> 2) + 64, 1024);  // 25% headroom
		for (int t = 0; t < accumThreadCt; t++) {
			taForce[t]      = new double[newCap * 3];
			taTorque[t]     = new double[newCap * 3];
			dirtyIndices[t] = new int[newCap];
			dirtyFlags[t]   = new boolean[newCap];
		}
		// Reallocate the canonical SoA arrays; preserve current contents so
		// growth mid-step doesn't drop accumulated force from earlier phases
		// or pose data already pushed by constructors.
		float[] newForce  = new float[newCap * 3];
		float[] newTorque = new float[newCap * 3];
		float[] newCoord  = new float[newCap * 3];
		float[] newUVec   = new float[newCap * 3];
		float[] newYVec   = new float[newCap * 3];
		float[] newEnd1   = new float[newCap * 3];
		float[] newEnd2   = new float[newCap * 3];
		float[] newZVec   = new float[newCap * 3];
		float[] newTransXTox = new float[newCap * 9];
		float[] newLength = new float[newCap];
		float[] newBTransGam = new float[newCap * 3];
		float[] newBRotGam   = new float[newCap * 3];
		if (soaForceSum.length > 0) {
			System.arraycopy(soaForceSum,  0, newForce,  0, soaForceSum.length);
			System.arraycopy(soaTorqueSum, 0, newTorque, 0, soaTorqueSum.length);
		}
		if (soaCoord.length > 0) {
			System.arraycopy(soaCoord, 0, newCoord, 0, soaCoord.length);
			System.arraycopy(soaUVec,  0, newUVec,  0, soaUVec.length);
			System.arraycopy(soaYVec,  0, newYVec,  0, soaYVec.length);
		}
		if (soaEnd1.length > 0) {
			System.arraycopy(soaEnd1, 0, newEnd1, 0, soaEnd1.length);
			System.arraycopy(soaEnd2, 0, newEnd2, 0, soaEnd2.length);
			System.arraycopy(soaZVec, 0, newZVec, 0, soaZVec.length);
		}
		if (soaTransXTox.length > 0) {
			System.arraycopy(soaTransXTox, 0, newTransXTox, 0, soaTransXTox.length);
		}
		if (soaLength.length > 0) {
			System.arraycopy(soaLength, 0, newLength, 0, soaLength.length);
		}
		if (soaBTransGam.length > 0) {
			System.arraycopy(soaBTransGam, 0, newBTransGam, 0, soaBTransGam.length);
			System.arraycopy(soaBRotGam,   0, newBRotGam,   0, soaBRotGam.length);
		}
		soaForceSum  = newForce;
		soaTorqueSum = newTorque;
		soaCoord     = newCoord;
		soaUVec      = newUVec;
		soaYVec      = newYVec;
		soaEnd1      = newEnd1;
		soaEnd2      = newEnd2;
		soaZVec      = newZVec;
		soaTransXTox = newTransXTox;
		soaLength    = newLength;
		soaBTransGam = newBTransGam;
		soaBRotGam   = newBRotGam;
		taCapacity = newCap;
	}

	// ---- SoA pose bridge (no-ops after Pt3D field removal) --------------
	// Canonical pose lives in soaCoord/soaUVec/soaYVec; writers go through
	// setCoord/setUVec/setYVec or the SoA arrays directly. The push helpers
	// are retained as no-ops so legacy call sites at write boundaries (which
	// used to flush Pt3D → SoA after mutating the Pt3D) don't need surgery.
	public void pushCoordToSoa() {}
	public void pushUVecToSoa()  {}
	public void pushYVecToSoa()  {}
	public void pushPoseToSoa()  {}

	// Inc2 — flush bTransGam/bRotGam Pt3D values into the SoA drag arrays.
	// Called at the end of every calculateProperties() override that writes
	// the drag tensors. Cheap (six float casts + six array stores); bypassed
	// entirely if the Pt3D fields were nulled (cleanup tombstones).
	public final void pushDragToSoa() {
		if (bTransGam == null || bRotGam == null) return;
		final int b = myThingNumber * 3;
		soaBTransGam[b]     = (float) bTransGam.x;
		soaBTransGam[b + 1] = (float) bTransGam.y;
		soaBTransGam[b + 2] = (float) bTransGam.z;
		soaBRotGam[b]       = (float) bRotGam.x;
		soaBRotGam[b + 1]   = (float) bRotGam.y;
		soaBRotGam[b + 2]   = (float) bRotGam.z;
	}

	// SoA pose accessors and mutators. After the Pt3D pose field removal,
	// readers go through getCoordX/Y/Z, getUVecX/Y/Z, getYVecX/Y/Z; writers
	// go through setCoord/setUVec/setYVec (or the per-component setters /
	// inc helpers below).
	// --- Per-step host-pose read audit (BOA_POSE_AUDIT) -----------------------
	// Counts reads of the host pose SoA (soaCoord/UVec/YVec) and attributes each
	// to its first non-Thing/non-Pt3D caller, but ONLY while poseAuditWindow is
	// set (BoxOfActin arms it for a few mid-run, non-output steps). This is the
	// exhaustive enumeration of per-step host-pose CONSUMERS — anything that
	// reads fresh pose between the demand-sync and the device execute is what
	// blocks retiring demandSyncPoseToHost. Off by default; a boolean check on
	// the hot path otherwise.
	public static final boolean POSE_AUDIT = System.getenv("BOA_POSE_AUDIT") != null;
	public static boolean poseAuditWindow = false;
	public static long poseAuditReads = 0;
	public static final java.util.HashMap<String,Long> poseAuditCallers = new java.util.HashMap<>();
	static void poseAuditHit() {
		poseAuditReads++;
		StackTraceElement[] st = new Throwable().getStackTrace();
		String caller = "?";
		for (int i = 2; i < st.length; i++) {
			String cn = st[i].getClassName();
			if (cn.endsWith(".Thing") || cn.endsWith(".Pt3D")) continue;
			caller = cn.substring(cn.lastIndexOf('.') + 1) + "." + st[i].getMethodName();
			break;
		}
		poseAuditCallers.merge(caller, 1L, Long::sum);
	}

	public float getCoordX() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaCoord[myThingNumber * 3];     }
	public float getCoordY() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaCoord[myThingNumber * 3 + 1]; }
	public float getCoordZ() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaCoord[myThingNumber * 3 + 2]; }
	public float getUVecX()  { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaUVec [myThingNumber * 3];     }
	public float getUVecY()  { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaUVec [myThingNumber * 3 + 1]; }
	public float getUVecZ()  { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaUVec [myThingNumber * 3 + 2]; }
	public float getYVecX()  { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaYVec [myThingNumber * 3];     }
	public float getYVecY()  { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaYVec [myThingNumber * 3 + 1]; }
	public float getYVecZ()  { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaYVec [myThingNumber * 3 + 2]; }
	public float getUVecRX() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return -soaUVec[myThingNumber * 3];     }
	public float getUVecRY() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return -soaUVec[myThingNumber * 3 + 1]; }
	public float getUVecRZ() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return -soaUVec[myThingNumber * 3 + 2]; }

	public void setCoordX(double v) { soaCoord[myThingNumber * 3]     = (float) v; }
	public void setCoordY(double v) { soaCoord[myThingNumber * 3 + 1] = (float) v; }
	public void setCoordZ(double v) { soaCoord[myThingNumber * 3 + 2] = (float) v; }
	public void setUVecX (double v) { soaUVec [myThingNumber * 3]     = (float) v; }
	public void setUVecY (double v) { soaUVec [myThingNumber * 3 + 1] = (float) v; }
	public void setUVecZ (double v) { soaUVec [myThingNumber * 3 + 2] = (float) v; }
	public void setYVecX (double v) { soaYVec [myThingNumber * 3]     = (float) v; }
	public void setYVecY (double v) { soaYVec [myThingNumber * 3 + 1] = (float) v; }
	public void setYVecZ (double v) { soaYVec [myThingNumber * 3 + 2] = (float) v; }

	public void setCoord(double x, double y, double z) {
		final int b = myThingNumber * 3;
		soaCoord[b] = (float) x; soaCoord[b+1] = (float) y; soaCoord[b+2] = (float) z;
	}
	public void setUVec(double x, double y, double z) {
		final int b = myThingNumber * 3;
		soaUVec[b] = (float) x; soaUVec[b+1] = (float) y; soaUVec[b+2] = (float) z;
	}
	public void setYVec(double x, double y, double z) {
		final int b = myThingNumber * 3;
		soaYVec[b] = (float) x; soaYVec[b+1] = (float) y; soaYVec[b+2] = (float) z;
	}
	public void setCoord(Pt3D p) { setCoord(p.x, p.y, p.z); }
	public void setUVec (Pt3D p) { setUVec(p.x, p.y, p.z); }
	public void setYVec (Pt3D p) { setYVec(p.x, p.y, p.z); }

	public void incCoord(Pt3D delta) {
		final int b = myThingNumber * 3;
		soaCoord[b]   += (float) delta.x;
		soaCoord[b+1] += (float) delta.y;
		soaCoord[b+2] += (float) delta.z;
	}
	public void incCoord(double scale, Pt3D delta) {
		final int b = myThingNumber * 3;
		soaCoord[b]   += (float) (scale * delta.x);
		soaCoord[b+1] += (float) (scale * delta.y);
		soaCoord[b+2] += (float) (scale * delta.z);
	}
	public void incCoord(double dx, double dy, double dz) {
		final int b = myThingNumber * 3;
		soaCoord[b]   += (float) dx;
		soaCoord[b+1] += (float) dy;
		soaCoord[b+2] += (float) dz;
	}

	// Normalise uVec in place (read SoA, scale, write back).
	public void unitVecU() {
		final int b = myThingNumber * 3;
		double ux = soaUVec[b], uy = soaUVec[b+1], uz = soaUVec[b+2];
		double mag2 = ux*ux + uy*uy + uz*uz;
		if (mag2 > 0) {
			double inv = 1.0 / Math.sqrt(mag2);
			soaUVec[b]   = (float) (ux * inv);
			soaUVec[b+1] = (float) (uy * inv);
			soaUVec[b+2] = (float) (uz * inv);
		}
	}

	// Read pose into Pt3D scratch (callers that still need a Pt3D for legacy APIs).
	public void getCoord(Pt3D out) {
		if (POSE_AUDIT && poseAuditWindow) poseAuditHit();
		final int b = myThingNumber * 3;
		out.x = soaCoord[b]; out.y = soaCoord[b+1]; out.z = soaCoord[b+2];
	}
	public void getUVec(Pt3D out) {
		if (POSE_AUDIT && poseAuditWindow) poseAuditHit();
		final int b = myThingNumber * 3;
		out.x = soaUVec[b]; out.y = soaUVec[b+1]; out.z = soaUVec[b+2];
	}
	public void getYVec(Pt3D out) {
		if (POSE_AUDIT && poseAuditWindow) poseAuditHit();
		final int b = myThingNumber * 3;
		out.x = soaYVec[b]; out.y = soaYVec[b+1]; out.z = soaYVec[b+2];
	}
	public void getZVec(Pt3D out) {
		final int b = myThingNumber * 3;
		out.x = soaZVec[b]; out.y = soaZVec[b+1]; out.z = soaZVec[b+2];
	}
	public void getEnd1(Pt3D out) {
		final int b = myThingNumber * 3;
		out.x = soaEnd1[b]; out.y = soaEnd1[b+1]; out.z = soaEnd1[b+2];
	}
	public void getEnd2(Pt3D out) {
		final int b = myThingNumber * 3;
		out.x = soaEnd2[b]; out.y = soaEnd2[b+1]; out.z = soaEnd2[b+2];
	}

	// Pt3D-allocating accessors for legacy callers that expect a Pt3D
	// (e.g., `Pt3D.Add(myBug.coordAsPt3D(), scale, dispV)`). Allocations are fine in
	// cold paths (Bug construction, ActA tether init, Crucible link strain);
	// hot paths should use the float accessors directly.
	public Pt3D coordAsPt3D() { return new Pt3D(getCoordX(), getCoordY(), getCoordZ()); }
	public Pt3D uVecAsPt3D()  { return new Pt3D(getUVecX(),  getUVecY(),  getUVecZ());  }
	public Pt3D yVecAsPt3D()  { return new Pt3D(getYVecX(),  getYVecY(),  getYVecZ());  }
	public Pt3D zVecAsPt3D()  { return new Pt3D(getZVecX(),  getZVecY(),  getZVecZ());  }
	public Pt3D end1AsPt3D()  { return new Pt3D(getEnd1X(),  getEnd1Y(),  getEnd1Z());  }
	public Pt3D end2AsPt3D()  { return new Pt3D(getEnd2X(),  getEnd2Y(),  getEnd2Z());  }
	public Pt3D uVecRAsPt3D() { return new Pt3D(-getUVecX(), -getUVecY(), -getUVecZ()); }

	// Fresh end1/end2 derived on-the-fly from the canonical coord+uVec+length
	// pose instead of the soaEnd1/End2 derived-field mirror. On the GPU path
	// soaEnd1/End2 are recomputed only at output cadence (and then restored to
	// their pre-output values by the -3js readonly guard), so per-step they are
	// FROZEN; meanwhile soaCoord/soaUVec are demand-synced fresh every step in
	// biochem configs (!noMonomersSimd). The CPU minifilament cohesion
	// (MyoMiniFilament.keepMyosinsOnSurface / constrainEnd*Dimers, MyosinDimer.
	// applyRodCoupling*) couples GPU-resident myosin rods and MUST read fresh
	// rod ends — reading the frozen soaEnd1/End2 makes the springs pull toward
	// stale, velocity-correlated geometry, producing a coherent spurious spin
	// (Path B Phase 2a). Deriving from coord/uVec adds no transfer (already
	// synced) and no per-step derived-SoA recompute. On the CPU path coord/uVec
	// are also fresh and soaEnd1 == coord - half*uVec, so the result is
	// bit-equivalent to end1AsPt3D() — no CPU behavioral change.
	public Pt3D freshEnd1AsPt3D() {
		final float half = 0.5f * getLengthSoa();
		return new Pt3D(getCoordX() - half*getUVecX(),
		                getCoordY() - half*getUVecY(),
		                getCoordZ() - half*getUVecZ());
	}
	public Pt3D freshEnd2AsPt3D() {
		final float half = 0.5f * getLengthSoa();
		return new Pt3D(getCoordX() + half*getUVecX(),
		                getCoordY() + half*getUVecY(),
		                getCoordZ() + half*getUVecZ());
	}

	// Push the per-Thing length into the SoA length array. Called from
	// subclass constructors after the natural length is known and any time
	// the length changes (FilSegment poly/depoly/split). Other Things with
	// length 0 (ProteinNode/StickyNode) leave the slot at 0; their end1/end2
	// will equal coord, which is harmless since those readers never look.
	public void pushLengthToSoa(double len) {
		soaLength[myThingNumber] = (float) len;
	}

	// ---- SoA derived-field accessors ----------------------------------------

	public float getEnd1X() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaEnd1[myThingNumber * 3];     }
	public float getEnd1Y() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaEnd1[myThingNumber * 3 + 1]; }
	public float getEnd1Z() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaEnd1[myThingNumber * 3 + 2]; }
	public float getEnd2X() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaEnd2[myThingNumber * 3];     }
	public float getEnd2Y() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaEnd2[myThingNumber * 3 + 1]; }
	public float getEnd2Z() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaEnd2[myThingNumber * 3 + 2]; }
	public float getZVecX() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaZVec[myThingNumber * 3];     }
	public float getZVecY() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaZVec[myThingNumber * 3 + 1]; }
	public float getZVecZ() { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaZVec[myThingNumber * 3 + 2]; }
	public float getLengthSoa() { return soaLength[myThingNumber]; }
	// transXTox row-major; idx in [0,9). transxToX is the transpose
	// (orthonormal matrix), so transxToX(i)==transXTox(swap(i)).
	public float getTransXTox(int idx) { if (POSE_AUDIT && poseAuditWindow) poseAuditHit(); return soaTransXTox[myThingNumber * 9 + idx]; }
	// Transpose accessor: row-major (i,j) for transxToX is (j,i) for transXTox.
	public float getTransxToX(int idx) {
		if (POSE_AUDIT && poseAuditWindow) poseAuditHit();
		// idx = row*3+col → transpose idx = col*3+row.
		final int row = idx / 3, col = idx - row * 3;
		return soaTransXTox[myThingNumber * 9 + col * 3 + row];
	}
	// Copy 9 floats of the body→fixed matrix into a caller-supplied double[9]
	// scratch (Pt3D.XTox-style readers that load m once and reuse 9× per call).
	public void copyTransXToxInto(double[] out) {
		if (POSE_AUDIT && poseAuditWindow) poseAuditHit();
		final int b9 = myThingNumber * 9;
		out[0] = soaTransXTox[b9];   out[1] = soaTransXTox[b9+1]; out[2] = soaTransXTox[b9+2];
		out[3] = soaTransXTox[b9+3]; out[4] = soaTransXTox[b9+4]; out[5] = soaTransXTox[b9+5];
		out[6] = soaTransXTox[b9+6]; out[7] = soaTransXTox[b9+7]; out[8] = soaTransXTox[b9+8];
	}
	public void copyTransxToXInto(double[] out) {
		if (POSE_AUDIT && poseAuditWindow) poseAuditHit();
		final int b9 = myThingNumber * 9;
		// transpose of the row-major 3×3 stored in soaTransXTox.
		out[0] = soaTransXTox[b9];   out[1] = soaTransXTox[b9+3]; out[2] = soaTransXTox[b9+6];
		out[3] = soaTransXTox[b9+1]; out[4] = soaTransXTox[b9+4]; out[5] = soaTransXTox[b9+7];
		out[6] = soaTransXTox[b9+2]; out[7] = soaTransXTox[b9+5]; out[8] = soaTransXTox[b9+8];
	}

	// Bulk pass: recompute derived SoA arrays (end1, end2, zVec, transXTox)
	// from the canonical coord/uVec/yVec/length arrays. Tight loop, SIMD-
	// friendly, no Thing object touches. Called once after the GPU unpack
	// (and after any code that mutates pose in bulk) instead of per-Thing
	// initialize(). yVec is re-orthogonalised against the new zVec to keep
	// the body frame orthonormal (mirrors the per-Thing zVec.cross / yVec.cross
	// dance in subclass initialize() bodies).
	public static void recomputeDerivedSoA(int from, int upTo) {
		// Bulk reader of soaCoord/soaUVec/soaYVec (bypasses the getters) — count it
		// in the pose audit so refreshMiniFilBodyDerived / output recompute are
		// attributed (one hit per Thing recomputed).
		if (POSE_AUDIT && poseAuditWindow) for (int i = from; i < upTo; i++) poseAuditHit();
		final float[] coordArr = soaCoord;
		final float[] uVecArr  = soaUVec;
		final float[] yVecArr  = soaYVec;
		final float[] zVecArr  = soaZVec;
		final float[] end1Arr  = soaEnd1;
		final float[] end2Arr  = soaEnd2;
		final float[] mArr     = soaTransXTox;
		final float[] lenArr   = soaLength;
		for (int i = from; i < upTo; i++) {
			final int b3 = i * 3;
			final int b9 = i * 9;
			float ux = uVecArr[b3], uy = uVecArr[b3+1], uz = uVecArr[b3+2];
			float yx = yVecArr[b3], yy = yVecArr[b3+1], yz = yVecArr[b3+2];
			// zVec = uVec × yVec (right-handed body frame).
			float zx = uy*yz - uz*yy;
			float zy = uz*yx - ux*yz;
			float zz = ux*yy - uy*yx;
			// FilSegment uniquely normalises zVec before re-orthogonalising
			// yVec (initial yVec is random direction in some constructors).
			// Other subclasses skip the normalise step; we always normalise
			// here so the matrix stays orthonormal even when yVec drifted
			// from orthogonality during integration. Cost: 3 muls + 1 sqrt.
			float zmag2 = zx*zx + zy*zy + zz*zz;
			if (zmag2 > 0f) {
				float inv = (float) (1.0 / Math.sqrt(zmag2));
				zx *= inv; zy *= inv; zz *= inv;
			}
			zVecArr[b3] = zx; zVecArr[b3+1] = zy; zVecArr[b3+2] = zz;
			// yVec' = zVec × uVec (restores orthogonality).
			yx = zy*uz - zz*uy;
			yy = zz*ux - zx*uz;
			yz = zx*uy - zy*ux;
			yVecArr[b3] = yx; yVecArr[b3+1] = yy; yVecArr[b3+2] = yz;
			// transXTox row-major = [uVec; yVec; zVec].
			mArr[b9]   = ux; mArr[b9+1] = uy; mArr[b9+2] = uz;
			mArr[b9+3] = yx; mArr[b9+4] = yy; mArr[b9+5] = yz;
			mArr[b9+6] = zx; mArr[b9+7] = zy; mArr[b9+8] = zz;
			// end1/end2 = coord ∓ length/2 · uVec.
			float cx = coordArr[b3], cy = coordArr[b3+1], cz = coordArr[b3+2];
			float halfLen = lenArr[i] * 0.5f;
			end1Arr[b3]   = cx - halfLen * ux;
			end1Arr[b3+1] = cy - halfLen * uy;
			end1Arr[b3+2] = cz - halfLen * uz;
			end2Arr[b3]   = cx + halfLen * ux;
			end2Arr[b3+1] = cy + halfLen * uy;
			end2Arr[b3+2] = cz + halfLen * uz;
		}
	}

	public static void recomputeDerivedSoA(int upTo) {
		recomputeDerivedSoA(0, upTo);
	}

	// Sparse gather: walk each worker thread's dirty list, narrow the double
	// per-thread contributions to float and add into soaForceSum/soaTorqueSum,
	// then zero the touched slots and clear the dirty flag. Cost is O(sum of
	// dirty counts), not O(thingCt × threadCt) — typically a few thousand
	// entries vs the hundreds of millions of slots the full sweep used to touch.
	//
	// Called single-threaded from the main loop; no contention because we
	// process one source thread at a time and writes target distinct slots.
	public static void gatherThreadAccumulators () {
		final int tCt = accumThreadCt;
		final float[] outF = soaForceSum;
		final float[] outT = soaTorqueSum;
		int total = 0, maxT = 0;
		for (int t = 0; t < tCt; t++) {
			final int count = dirtyCounts[t];
			if (count > maxT) maxT = count;
			total += count;
			if (count == 0) continue;
			final int[] indices    = dirtyIndices[t];
			final double[] forces  = taForce[t];
			final double[] torques = taTorque[t];
			final boolean[] flags  = dirtyFlags[t];
			for (int d = 0; d < count; d++) {
				final int idx = indices[d];
				final int base = idx * 3;
				Thing thing = theThings[idx];
				if (thing != null && !thing.removeMe) {
					outF[base]     += (float) forces[base];
					outF[base + 1] += (float) forces[base + 1];
					outF[base + 2] += (float) forces[base + 2];
					outT[base]     += (float) torques[base];
					outT[base + 1] += (float) torques[base + 1];
					outT[base + 2] += (float) torques[base + 2];
				}
				forces[base] = 0; forces[base + 1] = 0; forces[base + 2] = 0;
				torques[base] = 0; torques[base + 1] = 0; torques[base + 2] = 0;
				flags[idx] = false;
			}
			dirtyCounts[t] = 0;
		}
		gatherCallCount++;
		gatherTotalEntriesAllTime += total;
		if (maxT > gatherMaxEntriesAllTime) gatherMaxEntriesAllTime = maxT;
		if (gatherCallCount % 1000 == 0) {
			System.out.printf("[GATHER] call=%d totalDirty=%d maxThread=%d cumAvg=%.1f cumMaxPerThread=%d%n",
				gatherCallCount, total, maxT,
				(double)gatherTotalEntriesAllTime / gatherCallCount,
				gatherMaxEntriesAllTime);
		}
	}
	
	
	public synchronized void incFrictionSum (Pt3D forceVec, Pt3D forcePt) {  // send in as body-fixed frame force, fixed-frame point!!!
		// friction force and torque are stored and used in movePlayer as body-fixed frame forces!!!
		// rForce/tempTorq used to be per-Thing Pt3D fields (pure scratch); they
		// now live in WorkerScratch — one per worker, not one per Thing. Friction
		// is dormant in gliding so the TLS lookup here is on the cold path.
		final WorkerScratch ws = currentScratch();
		bFricForceSum.inc(forceVec);
		ws.rForce.x = forcePt.x - getCoordX();
		ws.rForce.y = forcePt.y - getCoordY();
		ws.rForce.z = forcePt.z - getCoordZ();
		ws.rForce.scale(1e-6);	// units (from µm to m)
		ws.rForce.XTox(this);
		ws.tempTorq.cross(ws.rForce, forceVec);
		incFricTorqueSum(ws.tempTorq);
	}
	
	public void incFricTorqueSum (Pt3D torque) {
		bFricTorqueSum.inc(torque);
	}
	
	public void collision() {
		if (lastCollisionTime == Env.simulationTime) {
			collisionCt++;
		} else {
			lastCollisionTime = Env.simulationTime;
			collisionCt = 1;
		}
	}
	
	public boolean didCollide() {
		return (lastCollisionTime == Env.simulationTime);
	}
	
	public void divide() {}
	
	public void calcRandomForces (WorkerScratch ws) {
		// Uniform deviates → Gaussian random forces/torques with variance 2Dt.
		// Computation-SoA: the per-component math is independent across x/y/z, so
		// the prior object-vector form (setVals → mult → vecSqrt → mult into
		// randForces) is rewritten as scalar locals. Op order is preserved exactly
		// (each component goes diff×facterm → sqrt → (invBDt*v*sqrt*gam) with
		// left-to-right Java FP), so the result is bit-identical to the Pt3D form.
		// xVals/yVals/zVals and the PRNG used to be per-Thing; now both live in
		// WorkerScratch (one set per worker) — see per-worker RNG consolidation.
		final double bDt = Env.deltaT.getValue();   // brownianDeltaT removed — always == deltaT
		final double invBDt = 1.0 / bDt;
		final UCircRnd xVals = ws.xVals;
		final UCircRnd yVals = ws.yVals;
		final UCircRnd zVals = ws.zVals;
		xVals.newValue(bDt, ws.rng);
		yVals.newValue(bDt, ws.rng);
		zVals.newValue(bDt, ws.rng);
		// tempPt.mult(bTransDiff, facterm); fac1.vecSqrt(tempPt) — per component:
		final double fac1x = Math.sqrt(bTransDiff.x * xVals.facterm);
		final double fac1y = Math.sqrt(bTransDiff.y * yVals.facterm);
		final double fac1z = Math.sqrt(bTransDiff.z * zVals.facterm);
		// tempPt.mult(bRotDiff, facterm); fac2.vecSqrt(tempPt):
		final double fac2x = Math.sqrt(bRotDiff.x * xVals.facterm);
		final double fac2y = Math.sqrt(bRotDiff.y * yVals.facterm);
		final double fac2z = Math.sqrt(bRotDiff.z * zVals.facterm);
		// randForces.mult(invBDt, v1, fac1, bTransGam) — Pt3D body multiplies
		// left-to-right: ((sc * v1.x) * fac1.x) * bTransGam.x. Match exactly.
		randForces.x = invBDt * xVals.v1 * fac1x * bTransGam.x;
		randForces.y = invBDt * yVals.v1 * fac1y * bTransGam.y;
		randForces.z = invBDt * zVals.v1 * fac1z * bTransGam.z;
		randTorques.x = invBDt * xVals.v2 * fac2x * bRotGam.x;
		randTorques.y = invBDt * yVals.v2 * fac2y * bRotGam.y;
		randTorques.z = invBDt * zVals.v2 * fac2z * bRotGam.z;
	}

	public static void brownianMotionForAll () {
		//talkln ("brownian apply @ " + Env.simulationTime + " seconds");
		final WorkerScratch ws = currentScratch();
		for (int i=0;i<thingCt;i++) {
			theThings[i].calcRandomForces(ws);
		}
	}
	
	// transMat()/transXTox/transxToX double[9] arrays were removed; the row-major
	// 3×3 lives in static soaTransXTox indexed by myThingNumber*9+i. Callers
	// invoke transMat() as a no-op so legacy initialize() bodies keep compiling;
	// recomputeDerivedSoA(myThingNumber, myThingNumber+1) is the SoA way to
	// refresh the matrix from the current uVec/yVec.
	public void transMat () {
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
	}
	
	public void resetCounters() {
		// SoA force/torque slots are zeroed in bulk at the start of each step
		// via Thing.clearSoaForcesTorques(thingCt); no per-Thing zero needed.
		bFricForceSum.zero();
		bFricTorqueSum.zero();
		//randForces.zero();		// these must be set to zero now that brownian forces aren't applied every time-step
		//randTorques.zero();		//
		collisionCt =  0;
	}
	public double getRdmDelta (){
		return currentScratch().rng.nextDouble()*2-1;
	}
	
	public static synchronized void addThing (Thing newThing) {
		theThings[thingCt] = newThing;
		theThings[thingCt].myThingNumber = thingCt;
		thingCt++;
		// Ensure the canonical SoA arrays can hold this slot. Constructors
		// run BEFORE doLoop's per-step ensureAccumCapacity, so push to SoA
		// from a constructor would otherwise hit a not-yet-allocated array.
		// Growth is amortised (25 % headroom inside ensureAccumCapacity).
		if (thingCt > taCapacity) {
			ensureAccumCapacity(thingCt);
		}
	}

	public static void removeThing (Thing byeThing) {
		int swapId = byeThing.myThingNumber;
		int lastId = thingCt - 1;
		theThings[swapId] = theThings[lastId];
		theThings[swapId].myThingNumber = swapId;
		// Compact the canonical SoA force/torque slots AND pose slots:
		// copy the last slot's data into the dead slot. Forces are zeroed
		// each step so the force swap is cosmetic, but pose data MUST move
		// with the surviving Thing — its myThingNumber just changed.
		if (swapId != lastId && soaForceSum.length >= (lastId + 1) * 3) {
			int dst = swapId * 3, src = lastId * 3;
			soaForceSum[dst]     = soaForceSum[src];
			soaForceSum[dst + 1] = soaForceSum[src + 1];
			soaForceSum[dst + 2] = soaForceSum[src + 2];
			soaTorqueSum[dst]     = soaTorqueSum[src];
			soaTorqueSum[dst + 1] = soaTorqueSum[src + 1];
			soaTorqueSum[dst + 2] = soaTorqueSum[src + 2];
			soaCoord[dst]     = soaCoord[src];
			soaCoord[dst + 1] = soaCoord[src + 1];
			soaCoord[dst + 2] = soaCoord[src + 2];
			soaUVec[dst]      = soaUVec[src];
			soaUVec[dst + 1]  = soaUVec[src + 1];
			soaUVec[dst + 2]  = soaUVec[src + 2];
			soaYVec[dst]      = soaYVec[src];
			soaYVec[dst + 1]  = soaYVec[src + 1];
			soaYVec[dst + 2]  = soaYVec[src + 2];
			// Derived SoA: end1/end2/zVec/length/transXTox. These are
			// recomputed each step from coord/uVec/yVec/length so the
			// swap is mostly cosmetic, but moving them with the surviving
			// Thing keeps stale-read paths from seeing inconsistent state.
			soaEnd1[dst]     = soaEnd1[src];
			soaEnd1[dst + 1] = soaEnd1[src + 1];
			soaEnd1[dst + 2] = soaEnd1[src + 2];
			soaEnd2[dst]     = soaEnd2[src];
			soaEnd2[dst + 1] = soaEnd2[src + 1];
			soaEnd2[dst + 2] = soaEnd2[src + 2];
			soaZVec[dst]     = soaZVec[src];
			soaZVec[dst + 1] = soaZVec[src + 1];
			soaZVec[dst + 2] = soaZVec[src + 2];
			soaLength[swapId] = soaLength[lastId];
			int dst9 = swapId * 9, src9 = lastId * 9;
			soaTransXTox[dst9]   = soaTransXTox[src9];
			soaTransXTox[dst9+1] = soaTransXTox[src9+1];
			soaTransXTox[dst9+2] = soaTransXTox[src9+2];
			soaTransXTox[dst9+3] = soaTransXTox[src9+3];
			soaTransXTox[dst9+4] = soaTransXTox[src9+4];
			soaTransXTox[dst9+5] = soaTransXTox[src9+5];
			soaTransXTox[dst9+6] = soaTransXTox[src9+6];
			soaTransXTox[dst9+7] = soaTransXTox[src9+7];
			soaTransXTox[dst9+8] = soaTransXTox[src9+8];
			// Inc2 — drag tensors follow the surviving Thing's myThingNumber.
			soaBTransGam[dst]     = soaBTransGam[src];
			soaBTransGam[dst + 1] = soaBTransGam[src + 1];
			soaBTransGam[dst + 2] = soaBTransGam[src + 2];
			soaBRotGam[dst]       = soaBRotGam[src];
			soaBRotGam[dst + 1]   = soaBRotGam[src + 1];
			soaBRotGam[dst + 2]   = soaBRotGam[src + 2];
		}
		// GPU slot-map staleness fix: the swap above changed which Thing
		// occupies theThings[swapId] (the surviving last Thing moved in, its
		// myThingNumber rewritten). The GPU rule/slot maps built by
		// GPUMoveThing.classifyThings() — gpuThingIndices[slot],
		// brownianRule[slot], thingNumberToMoveSlot[], and the Myosin joint
		// slot lists — are cached and only rebuilt on topologyDirty ||
		// thingCt != lastThingCt. The count proxy is unreliable: when an add
		// and a remove balance in one step (kRdmNuc nucleation / new-FilSegment
		// creation alongside a cofilin dissolve), thingCt is unchanged, the
		// rebuild is skipped, and packRange casts the swapped-in Thing (often a
		// CPU-fallback MyoMiniFilament) under the dead slot's stale RULE_FIL →
		// ClassCastException. Signal topology-dirty here, exactly as poly/split
		// already do (FilSegment.biochemStep). This forces a single in-place
		// classifyThings() refresh at the next onStepStart (NOT a plan rebuild
		// — topologyDirty never triggers allocateAndBuildPlan), idempotent
		// across all removals in a step. Only meaningful when a swap actually
		// moved a Thing (swapId != lastId).
		if (Env.useGPU && swapId != lastId && !DISABLE_TOPODIRTY_ON_SWAP) {
			GPUMoveThing.markTopologyDirty();
			GPUMoveThing.structuralDirtyCount++;   // A1: swap-compaction moved a Thing — real topology change
		}
		instanceRegistry.remove(byeThing.thingInstanceId);
		byeThing.sepaku();
		thingCt--;
	}

	public static Thing findByInstanceId(int id) {
		return instanceRegistry.get(id);
	}
	
	public static void removeDeadThings () {
		for (int i=0;i<thingCt;i++) {
			if (theThings[i] == null) { break; }		// this means we've gotten to the end of our shortening list of things
			if (theThings[i].removeMe) {
				removeThing(theThings[i]);
			}
		}
	}
	
//	 **** For collision detection ****
	public static void lineSegmentIntersectTest (Pt3D pt1A, Pt3D pt1B, Pt3D pt2A, Pt3D pt2B, RetObj retO) {
		// this method implements an adaption of the "Faster Line Segment Intersection" technique of 
		// Franklin Antonio presented in "Graphics Gems III", ed David Kirk, IBM 1992 and "Intersection of 
		// Two Lines in Three-space" by Ronald Goldman in "Graphics Gems", 1990.
		// the points received define two line segments:  pt1A-pt1B and pt2A-pt2B
		// the object, RetObj, returned hold two Pt3D and one double
		double smallNum = 1e-20;
		retO.reset();
		
		retO.ray1.sub(pt1B,pt1A);
		retO.ray2.sub(pt2B,pt2A);
		retO.ray3.sub(pt2A,pt1A);
		retO.ray4.cross (retO.ray1,retO.ray2);
		if ((retO.ray4.x < smallNum) & (retO.ray4.y < smallNum) & (retO.ray4.z < smallNum)) { 	// change this criterion to < some small #
			return;	 			// then stop 'cause the segments are parallel
		} else {
			double denom = Pt3D.Dot (retO.ray4,retO.ray4);
			double alpha = Pt3D.Dot(retO.ray4, Pt3D.Cross(retO.ray3,retO.ray2))/denom;
			if ((alpha >= 0) & (alpha <= 1)) {
				double beta = Pt3D.Dot(retO.ray4, Pt3D.Cross(retO.ray3,retO.ray1))/denom;
				if ((beta >= 0) & (beta <= 1)) {
					// if we've gotten this far we only need to check that the lines aren't skew... i.e. is there
					// one distinct intersection point or do we have the two points of closest approach?
					retO.collision = true;
					retO.alpha = alpha;
					retO.beta = beta;
					retO.conPt1.add(pt1A, alpha, retO.ray1);
					retO.conPt2.add(pt2A, beta, retO.ray2);
					retO.conDistSq = Pt3D.ptDistSqrd(retO.conPt1,retO.conPt2);
				}
			}
		}		
	}
	
	public static void pointAndLineIntersectTest (Pt3D point, Pt3D ptA, Pt3D ptB, RetObj retO) {
		// Point and Line Segment Intersection test... 
		// see derivation of the following formulae in work book... uses dot product as zero to enforce
		// the perpendicularity and parameterization of line segment to check if the perpendicular drop
		// from sphere to line is on the line segment.
		// A line segment is {x1,y1,z1,x2,y2,z2}
		// A point is {x,y,z}
		retO.reset();
		
		retO.ray1.sub(ptB,ptA);
		retO.ray2.sub(point,ptA);
		double numer = Pt3D.Dot(retO.ray2,retO.ray1);
		double denom = Pt3D.vecMagSqrd(retO.ray1);
		double alpha = numer/denom;
		if ((alpha <= 1) & (alpha >= 0)) {	// the perpendicular projection is on the line segment...  then check distance
			retO.collision = true;
			retO.conPt1.add(ptA, alpha,retO.ray1);		// define perpendicular point
			retO.conDistSq = Pt3D.ptDistSqrd(retO.conPt1,point);
		}
	}

	public static void talk (String info) {
		System.out.print(info);
	}
	
	public static void talkln (String info) {
		System.out.println(info);
	}
	
	public String getJSonString () {
		return "";
	}
}

