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
	
	static Thing [] theThings = new Thing [2000000];	// array of all things
	static int thingCt = 0;								// how many things
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
	Pt3D coord = new Pt3D();			// the x,y,and z position of the Thing
	static Pt3D maxPos = Env.worldDimension;	// maximum x position this Thing can occupy
	// 3x3 transformation matrices, flat row-major: [row*3 + col].
	// transXTox: fixed→body-fixed. transxToX: body-fixed→fixed (transpose).
	double [] transXTox = new double [9];
	double [] transxToX = new double [9];
	Pt3D uVec = new Pt3D(1,0,0);		// the unit vector that describes the orientation of the player
	Pt3D uVecR = new Pt3D(-1,0,0);		// opposite direction of uVec
	Pt3D yVec = new Pt3D(0,1,0);		// the first transvers vector for this body... in y direction
	Pt3D zVec = new Pt3D(0,0,1);		// the second tranverse vector.. in z direction
	// Body-axis endpoints (coord ± length/2 · uVec). Promoted from per-subclass
	// declarations so bridgeDerivedToPt3D can write them uniformly. Point-like
	// Things (ProteinNode/StickyNode/FillNode/Crucible/AnchorNode) have length=0
	// and leave end1==end2==coord, which is harmless — those subclasses don't
	// read end1/end2.
	Pt3D end1 = new Pt3D();
	Pt3D end2 = new Pt3D();
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
	//RanMT myPRNG = new RanMT((long)(Long.MAX_VALUE*Math.random()));
	MersenneTwisterFast myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));
	CollisionEvent cE = new CollisionEvent();		// try to reuse when possible
	
	// averaging of forces for stability
	//ValueTracker bForceTrack = new ValueTracker(Env.forcesToTrack,ValueTracker.PT3D_TYPE);
	//ValueTracker bTorqueTrack = new ValueTracker(Env.forcesToTrack,ValueTracker.PT3D_TYPE);
	
	//	 for collision tests
	RetObj retObj = new RetObj();
	boolean collidedWithBugThisStep = false;
	int collisionCt = 0; 	// keep track of number of collisions at each time-step
	double lastCollisionTime = 0; // stores sim. time of last collision
	
	//	different time-steps for sim pieces
	static int collisionCheckInt, biochemCheckInt,brownianApplyInt;
	int collCheckCt, biochemCheckCt;
	
	// some Pt3Ds used in calculating random forces
	UCircRnd xVals = new UCircRnd(Env.deltaT.getValue());
	UCircRnd yVals = new UCircRnd(Env.deltaT.getValue());
	UCircRnd zVals = new UCircRnd(Env.deltaT.getValue());
	Pt3D v1 = new Pt3D();
	Pt3D v2 = new Pt3D();
	Pt3D rsq = new Pt3D();
	Pt3D facterm =new Pt3D();
	Pt3D fac1 = new Pt3D();
	Pt3D fac2 = new Pt3D();
	Pt3D tempPt = new Pt3D();
	
	// reused in torque calculations
	Pt3D rForce = new Pt3D();
	Pt3D tempTorq = new Pt3D();
	
	static DecimalFormat expFormat = new DecimalFormat ("0.000E0");
	
	public Thing (Pt3D initCoord) {
		this.thingInstanceId = nextThingInstanceId++;
		this.createdAtStep   = Env.counter;
		instanceRegistry.put(this.thingInstanceId, this);
		this.coord.copy(initCoord);
		addThing(this);
		// Seed the canonical SoA pose with the initial coord and the default
		// uVec/yVec (1,0,0)/(0,1,0). Subclass constructors that overwrite
		// uVec/yVec must call pushPoseToSoa() before their initialize() call.
		pushPoseToSoa();
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
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.bForcesStop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.bForcesStart:
					if (Env.useGPU) {
						// iter2c: GPU kernel generates Brownian inline via Wang hash.
						// Skip CPU calcRandomForces for Things flagged by
						// GPUMoveThing.classifyThings() — only CPU-fallback Things
						// (Bug, branch FilSegments, etc.) still need it.
						for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
							Thing t = theThings[i];
							if (!t.removeMe && !t.gpuHandled) { t.calcRandomForces(); }
						}
					} else {
						for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
							if (!theThings[i].removeMe) { theThings[i].calcRandomForces(); }
						}
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		coord = null;
		transXTox = null;
		transxToX = null;
		uVec = null;
		uVecR = null;
		yVec = null;
		zVec = null;
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
		myPRNG = null;
		cE = null;
		
		//bForceTrack = null;
		//bTorqueTrack = null;
		
		retObj = null;
				
		xVals = null;
		yVals = null;
		zVals = null;
		v1 = null;
		v2 = null;
		rsq = null;
		facterm = null;
		fac1 = null;
		fac2 = null;
		tempPt = null;
		
		rForce = null;
		tempTorq = null;
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
		final double rx = (forcePoint.x - coord.x) * 1e-6;
		final double ry = (forcePoint.y - coord.y) * 1e-6;
		final double rz = (forcePoint.z - coord.z) * 1e-6;
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
		taCapacity = newCap;
	}

	// ---- SoA pose bridge -------------------------------------------------
	// Canonical pose lives in soaCoord/soaUVec/soaYVec. Pt3D coord/uVec/yVec
	// are a CPU-reader bridge maintained in sync via loadPoseFromSoa() inside
	// initialize(). Writers (moveThing end, constructors, translate, biochem
	// coord.inc, splitSegment/join, benchmark pin reset) call pushPoseToSoa()
	// (or the per-component helpers) BEFORE initialize() so initialize sees
	// the new pose. The bridge is read-only for downstream CPU phases until
	// the SoA-canonical refactor converts them one by one.

	public void pushCoordToSoa() {
		final int b = myThingNumber * 3;
		soaCoord[b]   = (float) coord.x;
		soaCoord[b+1] = (float) coord.y;
		soaCoord[b+2] = (float) coord.z;
	}

	public void pushUVecToSoa() {
		final int b = myThingNumber * 3;
		soaUVec[b]   = (float) uVec.x;
		soaUVec[b+1] = (float) uVec.y;
		soaUVec[b+2] = (float) uVec.z;
	}

	public void pushYVecToSoa() {
		final int b = myThingNumber * 3;
		soaYVec[b]   = (float) yVec.x;
		soaYVec[b+1] = (float) yVec.y;
		soaYVec[b+2] = (float) yVec.z;
	}

	public void pushPoseToSoa() {
		final int b = myThingNumber * 3;
		soaCoord[b]   = (float) coord.x;
		soaCoord[b+1] = (float) coord.y;
		soaCoord[b+2] = (float) coord.z;
		soaUVec[b]    = (float) uVec.x;
		soaUVec[b+1]  = (float) uVec.y;
		soaUVec[b+2]  = (float) uVec.z;
		soaYVec[b]    = (float) yVec.x;
		soaYVec[b+1]  = (float) yVec.y;
		soaYVec[b+2]  = (float) yVec.z;
	}

	// Read canonical SoA pose back into Pt3D bridge fields. Called from
	// initialize() so downstream Pt3D readers see the SoA values that the
	// GPU kernel wrote (via unpack) or that CPU moveThing flushed.
	public void loadPoseFromSoa() {
		final int b = myThingNumber * 3;
		coord.x = soaCoord[b];   coord.y = soaCoord[b+1]; coord.z = soaCoord[b+2];
		uVec.x  = soaUVec[b];    uVec.y  = soaUVec[b+1];  uVec.z  = soaUVec[b+2];
		yVec.x  = soaYVec[b];    yVec.y  = soaYVec[b+1];  yVec.z  = soaYVec[b+2];
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

	public float getEnd1X() { return soaEnd1[myThingNumber * 3];     }
	public float getEnd1Y() { return soaEnd1[myThingNumber * 3 + 1]; }
	public float getEnd1Z() { return soaEnd1[myThingNumber * 3 + 2]; }
	public float getEnd2X() { return soaEnd2[myThingNumber * 3];     }
	public float getEnd2Y() { return soaEnd2[myThingNumber * 3 + 1]; }
	public float getEnd2Z() { return soaEnd2[myThingNumber * 3 + 2]; }
	public float getZVecX() { return soaZVec[myThingNumber * 3];     }
	public float getZVecY() { return soaZVec[myThingNumber * 3 + 1]; }
	public float getZVecZ() { return soaZVec[myThingNumber * 3 + 2]; }
	public float getLengthSoa() { return soaLength[myThingNumber]; }
	// transXTox row-major; idx in [0,9). transxToX is the transpose
	// (orthonormal matrix), reachable via getTransXToxT(idx) or by swapping
	// (row,col) → (col,row).
	public float getTransXTox(int idx) { return soaTransXTox[myThingNumber * 9 + idx]; }

	// Bulk pass: recompute derived SoA arrays (end1, end2, zVec, transXTox)
	// from the canonical coord/uVec/yVec/length arrays. Tight loop, SIMD-
	// friendly, no Thing object touches. Called once after the GPU unpack
	// (and after any code that mutates pose in bulk) instead of per-Thing
	// initialize(). yVec is re-orthogonalised against the new zVec to keep
	// the body frame orthonormal (mirrors the per-Thing zVec.cross / yVec.cross
	// dance in subclass initialize() bodies).
	public static void recomputeDerivedSoA(int from, int upTo) {
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

	// Bridge: pull derived SoA fields back into per-Thing Pt3D / transXTox
	// state so unconverted CPU readers (which still chase Pt3D.end1.x etc.)
	// see the values the bulk recompute just wrote. Per-Thing loop, but the
	// body is direct field writes — no method dispatch, no Pt3D math.
	// Called after recomputeDerivedSoA in the GPU unpack path. For CPU
	// moveThing, the existing per-Thing initialize() handles bridge sync
	// since the bulk pass and bridge run together in lock-step.
	public static void bridgeDerivedToPt3D(int from, int upTo) {
		final float[] coordArr = soaCoord;
		final float[] uVecArr  = soaUVec;
		final float[] yVecArr  = soaYVec;
		final float[] zVecArr  = soaZVec;
		final float[] end1Arr  = soaEnd1;
		final float[] end2Arr  = soaEnd2;
		final float[] mArr     = soaTransXTox;
		final Thing[] arr      = theThings;
		for (int i = from; i < upTo; i++) {
			Thing t = arr[i];
			if (t == null || t.removeMe) continue;
			final int b3 = i * 3;
			final int b9 = i * 9;
			t.coord.x = coordArr[b3]; t.coord.y = coordArr[b3+1]; t.coord.z = coordArr[b3+2];
			t.uVec.x  = uVecArr[b3];  t.uVec.y  = uVecArr[b3+1];  t.uVec.z  = uVecArr[b3+2];
			t.yVec.x  = yVecArr[b3];  t.yVec.y  = yVecArr[b3+1];  t.yVec.z  = yVecArr[b3+2];
			t.zVec.x  = zVecArr[b3];  t.zVec.y  = zVecArr[b3+1];  t.zVec.z  = zVecArr[b3+2];
			t.end1.x  = end1Arr[b3];  t.end1.y  = end1Arr[b3+1];  t.end1.z  = end1Arr[b3+2];
			t.end2.x  = end2Arr[b3];  t.end2.y  = end2Arr[b3+1];  t.end2.z  = end2Arr[b3+2];
			t.uVecR.x = -t.uVec.x;    t.uVecR.y = -t.uVec.y;      t.uVecR.z = -t.uVec.z;
			final double[] m = t.transXTox;
			final double[] mt = t.transxToX;
			float m0 = mArr[b9],   m1 = mArr[b9+1], m2 = mArr[b9+2];
			float m3 = mArr[b9+3], m4 = mArr[b9+4], m5 = mArr[b9+5];
			float m6 = mArr[b9+6], m7 = mArr[b9+7], m8 = mArr[b9+8];
			m[0] = m0; m[1] = m1; m[2] = m2;
			m[3] = m3; m[4] = m4; m[5] = m5;
			m[6] = m6; m[7] = m7; m[8] = m8;
			mt[0] = m0; mt[1] = m3; mt[2] = m6;
			mt[3] = m1; mt[4] = m4; mt[5] = m7;
			mt[6] = m2; mt[7] = m5; mt[8] = m8;
		}
	}

	public static void bridgeDerivedToPt3D(int upTo) {
		bridgeDerivedToPt3D(0, upTo);
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
		bFricForceSum.inc(forceVec);
		rForce.sub(forcePt,coord);
		rForce.scale(1e-6);	// units (from µm to m)
		rForce.XTox(this);
		tempTorq.cross(rForce, forceVec);
		incFricTorqueSum(tempTorq);
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
	
	public void calcRandomForces () {
		// this method takes uniform deviates and finds random numbers with a
		// Gaussian distribution of mean=0, variance=2Dt, as applies for diffusive
		// motion of a particle with diffusivity D.
		double bDt = Env.brownianDeltaT.getValue();
		double invBDt = 1.0 / bDt;
		// get fresh random value pairs in unit circle {v1,v2,rsq,facterm}
		xVals.newValue(bDt,this);
		yVals.newValue(bDt,this);
		zVals.newValue(bDt,this);
		// rearrange values into v1, v2, rsq, and facterm Pt3Ds
		v1.setVals(xVals.v1,yVals.v1,zVals.v1);
		v2.setVals(xVals.v2,yVals.v2,zVals.v2);
		rsq.setVals(xVals.rsq,yVals.rsq,zVals.rsq);
		facterm.setVals(xVals.facterm,yVals.facterm,zVals.facterm);
		// this part actually depends on the objects diffusion and drag coefficients
		tempPt.mult(bTransDiff, facterm);
		fac1.vecSqrt(tempPt);
		tempPt.mult(bRotDiff, facterm);
		fac2.vecSqrt(tempPt);
		randForces.mult(invBDt, v1, fac1, bTransGam);
		randTorques.mult(invBDt, v2, fac2, bRotGam);
	}
	
	public static void brownianMotionForAll () {
		//talkln ("brownian apply @ " + Env.simulationTime + " seconds");
		for (int i=0;i<thingCt;i++) {
			theThings[i].calcRandomForces();
		}
	}
	
	public void transMat () {
		double ux = uVec.x, uy = uVec.y, uz = uVec.z;
		double yx = yVec.x, yy = yVec.y, yz = yVec.z;
		double zx = zVec.x, zy = zVec.y, zz = zVec.z;
		transXTox[0] = ux; transXTox[1] = uy; transXTox[2] = uz;
		transXTox[3] = yx; transXTox[4] = yy; transXTox[5] = yz;
		transXTox[6] = zx; transXTox[7] = zy; transXTox[8] = zz;
		// inverse transformation is the transpose (orthogonal direction-cosine matrix)
		transxToX[0] = ux; transxToX[1] = yx; transxToX[2] = zx;
		transxToX[3] = uy; transxToX[4] = yy; transxToX[5] = zy;
		transxToX[6] = uz; transxToX[7] = yz; transxToX[8] = zz;
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
		return myPRNG.nextDouble()*2-1;
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

