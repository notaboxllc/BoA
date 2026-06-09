package boxOfActin;
/**
 * ProteinNode.java
 *
 *
 */



import java.awt.*;
import javax.swing.*;
import java.lang.Math.*;


public class MyoMiniFilament extends Thing {
	static MyoMiniFilament [] myoMiniFils = new MyoMiniFilament [10000];	// array holding all protein nodes
	static int myoMiniFilCt = 0; //
	int myMyoMiniNumber;
	static double length = 0.180;	// �m
	static double radius = 0.005; // �m
	static double headZone = 0.05; // �m  distribution of myosins about each end of filament
	private int boundFilaments = 0;			// counter for number of bound actin filaments
	
	// end1AsPt3D() (free end) / end2AsPt3D() (attached to head) live on Thing now;
	// bridgeDerivedToPt3D writes them after every GPU step.
	
	// empirical fit for viscous drags
	static final double aParallel = -0.20;  // approx to constant in damping for parallel motion
	static final double aOrthog = 0.84;		// ...for orthogonal motion
	static final double aTurning = -0.662; 	// ...for rotational motion
	
	// for multithreading
	static MyoMiniFilThreads miniFilThreads = new MyoMiniFilThreads();
	
	// re-used in force calcs
	Pt3D F = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RcrossF = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D linkUVec1 = new Pt3D(); 
	Pt3D linkUVec2 = new Pt3D();
	
	// for collision detection
	double xRange,yRange,zRange;
	
	// myosin heads
	int numMyosinHeads = 0;
	Myosin [] myosins = new Myosin [numMyosinHeads];
	Pt3D [] myoPtsInx = new Pt3D[numMyosinHeads];
	Pt3D [] myoPtsInX = new Pt3D[numMyosinHeads];
	
	// myosin dimers
	int numMyoDimersEachEnd = Env.numMyoDimersEachEndOfMiniFil.getIntValue();
	MyosinDimer [] myoDimersEnd1 = new MyosinDimer[numMyoDimersEachEnd];
	MyosinDimer [] myoDimersEnd2 = new MyosinDimer[numMyoDimersEachEnd];
	
	Pt3D [] myoDimerPtsEnd1Inx = new Pt3D[numMyoDimersEachEnd];
	Pt3D [] myoDimerPtsEnd1InX = new Pt3D[numMyoDimersEachEnd];
	
	Pt3D [] myoDimerPtsEnd2Inx = new Pt3D[numMyoDimersEachEnd];
	Pt3D [] myoDimerPtsEnd2InX = new Pt3D[numMyoDimersEachEnd];

	
	public MyoMiniFilament (Pt3D initCoord) {
		super(initCoord);
		addMiniFil(this);
		calculateProperties();
		pushPoseToSoa();
		initialize();

		makeMyosinHeads();
		makeMyosinDimers();
	}

	public MyoMiniFilament (Pt3D initCoord, Pt3D initUVec) {
		super(initCoord);
		addMiniFil(this);

		setUVec(initUVec);
		calculateProperties();
		pushPoseToSoa();
		initialize();

		makeMyosinHeads();
		makeMyosinDimers();
	}
	
	static class MyoMiniFilThreads extends ThreadSet {
		MyoMiniFilThreads () {
			super (Env.numMyoThreads, "MyoMiniFil Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.myoJoints2Start:
					if (myoMiniFilCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*myoMiniFilCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.myoJoints2Stop:
					if (myoMiniFilCt == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.myoJoints2Start:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						myoMiniFils[i].updateMyosins(); 
					}
					break;
			}
		}
	}
	
	public void set (Pt3D setCoord, Pt3D setUVec, double dim, double rad) {
		setCoord(setCoord);
		setUVec(setUVec);
		length = dim;
		radius = rad;
		pushPoseToSoa();
		initialize();
	}
	
	public void calculateProperties () {
		// define the constants for motion of this rod in viscous medium
		// Remember that the dimensions we've been using are in micrometers so...
		double tailLengthM = 1.0e-6*length; // in meters
		double radiusM = radius*1.0e-6;
		double denomLogTerm = Math.log(tailLengthM/(2*radiusM));	//dimensionless
		bTransGam.x = (2*Math.PI*Env.aeta.getValue()*tailLengthM)/(denomLogTerm + aParallel);
		bTransGam.y = (4*Math.PI*Env.aeta.getValue()*tailLengthM)/(denomLogTerm + aOrthog);
		bTransGam.z = bTransGam.y;
		bRotGam.x = 4*Math.PI*Env.aeta.getValue()*radiusM*radiusM*tailLengthM;	// drag for turning about x
		bRotGam.y = (Math.PI*Env.aeta.getValue()*(tailLengthM*tailLengthM*tailLengthM))/(3*(denomLogTerm + aTurning));
		bRotGam.z = bRotGam.y;
		
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
		pushDragToSoa();
	}

	public void initialize () {
		pushLengthToSoa(length);
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
		xRange = Math.abs(getCoordX()-getEnd2X());
		yRange = Math.abs(getCoordY()-getEnd2Y());
		zRange = Math.abs(getCoordZ()-getEnd2Z());
	}
	
	public void moveThing () {
		// TELEPORT_DIAG — snapshot pre-step state so we can measure displacement after integration
		Pt3D coordBefore = null, end1Before = null, end2Before = null;
		Pt3D forceSumSnap = null, torqueSumSnap = null;
		Pt3D randForcesSnap = null, randTorquesSnap = null;
		Pt3D bTransGamSnap = null, bRotGamSnap = null;
		if (Env.myoMiniTeleportDiag) {
			coordBefore    = new Pt3D(getCoordX(),      getCoordY(),      getCoordZ());
			end1Before     = new Pt3D(getEnd1X(),       getEnd1Y(),       getEnd1Z());
			end2Before     = new Pt3D(getEnd2X(),       getEnd2Y(),       getEnd2Z());
			forceSumSnap   = new Pt3D(getForceSumX(),  getForceSumY(),  getForceSumZ());
			torqueSumSnap  = new Pt3D(getTorqueSumX(), getTorqueSumY(), getTorqueSumZ());
			randForcesSnap = new Pt3D(randForces.x, randForces.y, randForces.z);
			randTorquesSnap= new Pt3D(randTorques.x,randTorques.y,randTorques.z);
			bTransGamSnap  = new Pt3D(bTransGam.x,  bTransGam.y,  bTransGam.z);
			bRotGamSnap    = new Pt3D(bRotGam.x,    bRotGam.y,    bRotGam.z);
		}

		// Given the forces/torques at this time point... move with explicit Euler approximation to ODE solution

		// first check that forceSum and torqueSum aren't wacky... exit method if they are
		if (!isForceSumFinite()) {
			talkln ("Crazy forceSum in " + this);
			setForceSumToRandForces();
		}
		if (!isTorqueSumFinite()) {
			talkln ("Crazy torqueSum in " + this);
			setTorqueSumToRandTorques();
		}

		// Work in coordinates aligned with the minifilament... transform forces and torques into body-fixed axis....
		int sBase = myThingNumber * 3;
		bForceSum.XToxFromFloats(this, Thing.soaForceSum, sBase);
		bTorqueSum.XToxFromFloats(this, Thing.soaTorqueSum, sBase);
		if (!Env.myoMiniFilBrownianMotionOff) {
			// add random forces, given in body-fixed frame
			bForceSum.inc(randForces);
			bTorqueSum.inc(randTorques);
		}
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec

		// ** before progressing .... check that bVeloc and bAngVeloc are not NaN... exit if wacky
		if (!bVeloc.checkPt3D()) { talkln ("** problem with bVeloc for " + this); return; }
		if (!bAngVeloc.checkPt3D()) { talkln ("** problem with bAngVeloc for " + this); return; }

		// New Positions
		// ....then transform back into fixed coordinate frame
		veloc.xToX(this, bVeloc);
		incCoord(Env.deltaT.getValue(), veloc);

		Pt3D scratch = new Pt3D();
		double uVecTransInZ = -bAngVeloc.y * Env.deltaT.getValue();
		double uVecTransInY = bAngVeloc.z * Env.deltaT.getValue();
		scratch.setVals(1, uVecTransInY, uVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setUVec(scratch);

		double yVecTransInX = -uVecTransInY;
		double yVecTransInZ = bAngVeloc.x * Env.deltaT.getValue();
		scratch.setVals(yVecTransInX, 1, yVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setYVec(scratch);

		initialize();

		// TELEPORT_DIAG — check displacement; emit full state dump if threshold exceeded
		if (Env.myoMiniTeleportDiag) {
			double disp = Pt3D.ptDist(coordBefore, coordAsPt3D());
			if (disp > Env.myoMiniTeleportThreshold) {
				dumpTeleportDiag(coordBefore, end1Before, end2Before,
						forceSumSnap, torqueSumSnap,
						randForcesSnap, randTorquesSnap,
						bTransGamSnap, bRotGamSnap, disp);
			}
		}
	}

	// TELEPORT_DIAG — emit one [TELEPORT] block to stderr; called only when disp > Env.myoMiniTeleportThreshold
	private void dumpTeleportDiag(
			Pt3D coordBefore, Pt3D end1Before, Pt3D end2Before,
			Pt3D forceSumSnap, Pt3D torqueSumSnap,
			Pt3D randForcesSnap, Pt3D randTorquesSnap,
			Pt3D bTransGamSnap, Pt3D bRotGamSnap,
			double disp) {
		String p = "[TELEPORT] ";
		System.err.println(p + String.format(
				"t=%.4f step=%d minifil#%d disp=%.3eµm",
				Env.simulationTime, Env.counter, myMyoMiniNumber, disp));
		System.err.println(p + String.format(
				"  coordAsPt3D():      (%.3f, %.3f, %.3f) -> (%.3f, %.3f, %.3f)",
				coordBefore.x, coordBefore.y, coordBefore.z,
				getCoordX(), getCoordY(), getCoordZ()));
		System.err.println(p + String.format(
				"  end1AsPt3D():       (%.3f, %.3f, %.3f) -> (%.3f, %.3f, %.3f)",
				end1Before.x, end1Before.y, end1Before.z,
				getEnd1X(), getEnd1Y(), getEnd1Z()));
		System.err.println(p + String.format(
				"  end2AsPt3D():       (%.3f, %.3f, %.3f) -> (%.3f, %.3f, %.3f)",
				end2Before.x, end2Before.y, end2Before.z,
				getEnd2X(), getEnd2Y(), getEnd2Z()));
		System.err.println(p + String.format(
				"  forceSum   = (%.3e, %.3e, %.3e)",
				forceSumSnap.x, forceSumSnap.y, forceSumSnap.z));
		System.err.println(p + String.format(
				"  torqueSum  = (%.3e, %.3e, %.3e)",
				torqueSumSnap.x, torqueSumSnap.y, torqueSumSnap.z));
		System.err.println(p + String.format(
				"  bForceSum  = (%.3e, %.3e, %.3e)",
				bForceSum.x, bForceSum.y, bForceSum.z));
		System.err.println(p + String.format(
				"  bTorqueSum = (%.3e, %.3e, %.3e)",
				bTorqueSum.x, bTorqueSum.y, bTorqueSum.z));
		System.err.println(p + String.format(
				"  randForces = (%.3e, %.3e, %.3e)",
				randForcesSnap.x, randForcesSnap.y, randForcesSnap.z));
		System.err.println(p + String.format(
				"  randTorques= (%.3e, %.3e, %.3e)",
				randTorquesSnap.x, randTorquesSnap.y, randTorquesSnap.z));
		System.err.println(p + String.format(
				"  bVeloc     = (%.3e, %.3e, %.3e)",
				bVeloc.x, bVeloc.y, bVeloc.z));
		System.err.println(p + String.format(
				"  bAngVeloc  = (%.3e, %.3e, %.3e)",
				bAngVeloc.x, bAngVeloc.y, bAngVeloc.z));
		System.err.println(p + String.format(
				"  bTransGam  = (%.3e, %.3e, %.3e)",
				bTransGamSnap.x, bTransGamSnap.y, bTransGamSnap.z));
		System.err.println(p + String.format(
				"  bRotGam    = (%.3e, %.3e, %.3e)",
				bRotGamSnap.x, bRotGamSnap.y, bRotGamSnap.z));
		System.err.println(p + String.format(
				"  collisionCt=%d lastCollTime=%.4f",
				collisionCt, lastCollisionTime));
	}
	
	public void biochemStep () {
		if (Env.mtRNG.nextDouble() < Env.deltaT.getValue()/Env.myoMiniLifetime.getValue()) {
			removeMe = true;
		}
	}
	
	public void step () {
		//increment counters that control how often different sims occur
		collCheckCt++;
		if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			long _spT = StepProfiler.t0();
			checkOuterBugCollision();		// these should add forces and torques to forceSum and torqueSum
			StepProfiler.add(StepProfiler.F7_MYOMINI_BOUNDARY, _spT);
			collCheckCt = 0;
		}

	}
	
	public void resetCounters() {
		super.resetCounters();
	}
	
	public void makeMyosinHeads () {
		for (int i=0;i<numMyosinHeads;i++) {
			myoPtsInx[i] = new Pt3D();
			double ang = currentScratch().rng.nextDouble()*Math.PI;
			myoPtsInx[i].y = getRadius()*Math.cos(ang);
			myoPtsInx[i].z = getRadius()*Math.sin(ang);
			myoPtsInx[i].x = (2*currentScratch().rng.nextDouble()-1)*(length/2);
			
			myoPtsInX[i] = new Pt3D();
			myoPtsInX[i].xToX(this,myoPtsInx[i]);
			myoPtsInX[i].inc(coordAsPt3D());
			myosins[i] = new Myosin(myoPtsInX[i]);
			//myosins[i].rodInvisible = true;
		}
	}
	
	public void updateMyosinPositions () {
		for (int i=0;i<numMyosinHeads;i++) {
			myoPtsInX[i].xToX(this,myoPtsInx[i]);
			myoPtsInX[i].inc(coordAsPt3D());
		}
	}
	
	public void keepMyosinsOnSurface () {
		Pt3D curAttPt;
		Myosin curMyo;
		for (int i=0;i<numMyosinHeads;i++) {
			curMyo = myosins[i];
			curAttPt = myoPtsInX[i];
			double strainDist = Pt3D.ptDist(curMyo.myoRod.end1AsPt3D(), curAttPt);
			linkUVec1.unitVec(curAttPt,curMyo.myoRod.end1AsPt3D());
			linkUVec2.scale(-1,linkUVec1);
			double forceMag = (Env.myoMiniFilFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curMyo.myoRod.bTransGam.y + 1/bTransGam.y));

			F.scale(forceMag,linkUVec1);
			curMyo.myoRod.incForceSum(F);
			
			F.scale(-1,F);
			incForceSum(F);
		}
	}
	
	public void makeMyosinDimers () {
		
		for (int i=0;i<numMyoDimersEachEnd;i++) {
			myoDimerPtsEnd1Inx[i] = new Pt3D();
			double ang = currentScratch().rng.nextDouble()*Math.PI;
			myoDimerPtsEnd1Inx[i].y = 0;//getRadius()*Math.cos(ang);
			myoDimerPtsEnd1Inx[i].z = 0;//getRadius()*Math.sin(ang);
			myoDimerPtsEnd1Inx[i].x = -(length/2 - currentScratch().rng.nextDouble()*headZone);
			
			myoDimerPtsEnd1InX[i] = new Pt3D();
			myoDimerPtsEnd1InX[i].xToX(this,myoDimerPtsEnd1Inx[i]);
			myoDimerPtsEnd1InX[i].inc(coordAsPt3D());
			myoDimersEnd1[i] = new MyosinDimer(myoDimerPtsEnd1InX[i],uVecRAsPt3D());
			//myosins[i].rodInvisible = true;
		}
		
		for (int i=0;i<numMyoDimersEachEnd;i++) {
			myoDimerPtsEnd2Inx[i] = new Pt3D();
			double ang = currentScratch().rng.nextDouble()*Math.PI;
			myoDimerPtsEnd2Inx[i].y = 0;//getRadius()*Math.cos(ang);
			myoDimerPtsEnd2Inx[i].z = 0;//getRadius()*Math.sin(ang);
			myoDimerPtsEnd2Inx[i].x = length/2 - currentScratch().rng.nextDouble()*headZone;
			
			myoDimerPtsEnd2InX[i] = new Pt3D();
			myoDimerPtsEnd2InX[i].xToX(this,myoDimerPtsEnd2Inx[i]);
			myoDimerPtsEnd2InX[i].inc(coordAsPt3D());
			myoDimersEnd2[i] = new MyosinDimer(myoDimerPtsEnd2InX[i],uVecAsPt3D());
			//myosins[i].rodInvisible = true;
		}
	}
	
	public void updateMyosinDimerPositions () {
		for (int i=0;i<numMyoDimersEachEnd;i++) {
			myoDimerPtsEnd1InX[i].xToX(this,myoDimerPtsEnd1Inx[i]);
			myoDimerPtsEnd1InX[i].inc(coordAsPt3D());
			
			myoDimerPtsEnd2InX[i].xToX(this,myoDimerPtsEnd2Inx[i]);
			myoDimerPtsEnd2InX[i].inc(coordAsPt3D());
		}
	}
	
	public void constrainEnd1Dimers () {
		Pt3D curAttPt;
		MyosinDimer curMyoD;
		MyoRod curMyoRod;
		for (int i=0;i<numMyoDimersEachEnd;i++) {
			// force to keep two points together
			curMyoD = myoDimersEnd1[i];
			curMyoRod = curMyoD.myo1.myoRod;
			curAttPt = myoDimerPtsEnd1InX[i];
			double strainDist = Pt3D.ptDist(curMyoRod.end1AsPt3D(), curAttPt);
			linkUVec1.unitVec(curAttPt,curMyoRod.end1AsPt3D());
			linkUVec2.scale(-1,linkUVec1);
			double forceMag = (Env.myoMiniFilFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curMyoRod.bTransGam.y + 1/bTransGam.y));

			F.scale(forceMag,linkUVec1);
			curMyoRod.incForceSum(F);
			
			F.scale(-1,F);
			incForceSum(F);
			
			// torque to maintain alignment (more or less)
			torsionVec.cross(curMyoRod.uVecAsPt3D(),uVecRAsPt3D());
			torsionVec.unitVec();
			
			double dotVecs = Pt3D.Dot(curMyoRod.uVecAsPt3D(),uVecRAsPt3D());
			if (dotVecs > 1.0) { dotVecs = 1.0; }
			if (dotVecs < -1.0) { dotVecs = -1.0; }
			double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;
			
			//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
			double torsionMag = Env.myoMiniFilAlign.getValue()*(Math.PI/180)*angTween/((1/curMyoRod.bRotGam.y + 1/bRotGam.y)*Env.deltaT.getValue());
			
			if (torsionVec.checkPt3D()) {
				torsionVec.scale(torsionMag);
				curMyoRod.incTorqueSum(torsionVec);
			
				torsionVec.scale(-1);
				incTorqueSum(torsionVec);
			} else {
				System.out.println ("Crazy torque result in MyoMiniFilament.constrainEnd1Dimers()");
			}
		}
	}
	
	public void constrainEnd2Dimers () {
		Pt3D curAttPt;
		MyosinDimer curMyoD;
		MyoRod curMyoRod;
		for (int i=0;i<numMyoDimersEachEnd;i++) {
			// force to keep two points together
			curMyoD = myoDimersEnd2[i];
			curMyoRod = curMyoD.myo1.myoRod;
			curAttPt = myoDimerPtsEnd2InX[i];
			double strainDist = Pt3D.ptDist(curMyoRod.end1AsPt3D(), curAttPt);
			linkUVec1.unitVec(curAttPt,curMyoRod.end1AsPt3D());
			linkUVec2.scale(-1,linkUVec1);
			double forceMag = (Env.myoMiniFilFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curMyoRod.bTransGam.y + 1/bTransGam.y));

			F.scale(forceMag,linkUVec1);
			curMyoRod.incForceSum(F);
			
			F.scale(-1,F);
			incForceSum(F);
			
			// torque to maintain alignment (more or less)
			torsionVec.cross(curMyoRod.uVecAsPt3D(),uVecAsPt3D());
			torsionVec.unitVec();
			
			double dotVecs = Pt3D.Dot(curMyoRod.uVecAsPt3D(),uVecAsPt3D());
			if (dotVecs > 1.0) { dotVecs = 1.0; }
			if (dotVecs < -1.0) { dotVecs = -1.0; }
			double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;
			
			//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
			double torsionMag = Env.myoMiniFilAlign.getValue()*(Math.PI/180)*angTween/((1/curMyoRod.bRotGam.y + 1/bRotGam.y)*Env.deltaT.getValue());
			
			if (torsionVec.checkPt3D()) {
				torsionVec.scale(torsionMag);
				curMyoRod.incTorqueSum(torsionVec);
			
				torsionVec.scale(-1);
				incTorqueSum(torsionVec);
			} else {
				System.out.println ("Crazy torque result in MyoMiniFilament.constrainEnd2Dimers()");
			}
		}
	}
	
	public static void updateAllMyosins () {
		for (int i=0;i<myoMiniFilCt;i++) {
			myoMiniFils[i].updateMyosins();
		}
	}
	
	public void updateMyosins() {
		updateMyosinPositions();
		keepMyosinsOnSurface();
		
		updateMyosinDimerPositions();
		constrainEnd1Dimers();
		constrainEnd2Dimers();
	}
	
	public void checkOuterBugCollision () {
		final CollisionEvent cE = currentScratch().cE;
		double mag;
		theBox.amICollidingOuter(cE,end1AsPt3D(),getRadius());
		if (cE.delta != 0) {
			mag = Env.nodeFracMove*1.0e-6*cE.delta*bTransGam.x/Env.collisionDeltaT.getValue();
			incForceSum(Pt3D.Scale(mag,cE.forceUVec),end1AsPt3D());
		}

		theBox.amICollidingOuter(cE,end2AsPt3D(),getRadius());
		if (cE.delta != 0) {
			mag = Env.nodeFracMove*1.0e-6*cE.delta*bTransGam.x/Env.collisionDeltaT.getValue();
			incForceSum(Pt3D.Scale(mag,cE.forceUVec),end2AsPt3D());
		}
	}
	
	public static void checkMyoMiniFilCollisions () {
		for (int i=0; i<myoMiniFilCt; i++) {
			MyoMiniFilament iP = myoMiniFils[i];
			for (int p=i+1;p<myoMiniFilCt;p++) {
				MyoMiniFilament pP = myoMiniFils[p];
				double pDist = Pt3D.ptDist(iP.coordAsPt3D(), pP.coordAsPt3D());
				if (pDist<pP.getRadius()+iP.getRadius()) {
					double impingedist = pP.getRadius()+iP.getRadius() - pDist;
				    Pt3D iVec = Pt3D.UnitVec(pDist, iP.coordAsPt3D(), pP.coordAsPt3D());
					Pt3D pVec = Pt3D.Reverse(iVec);
					double mag = (1.0e-6*impingedist/Env.collisionDeltaT.getValue())/(1/iP.bTransGam.x+1/pP.bTransGam.x);
					iP.incForceSum(Pt3D.Scale(mag,iVec));
					pP.incForceSum(Pt3D.Scale(mag,pVec));
				}
			}
		}
	}
	
	public double getRadius() {
		return radius;
	}
	
	public double getLength () {
		return length;
	}
	
	public static void makeInitialMyoMiniFils() {
		for (int i=0;i<Env.initialMyoMiniFils.getValue();i++) {
			makeRandomMyoMiniFil();
		}
	}
	
	public static void makeRandomMyoMiniFil () {
		Pt3D randomLoc = Thing.theBox.rdmPtInside();
		Pt3D randomUVec = Pt3D.RandomUnitVec(Env.mtRNG);
		new MyoMiniFilament(randomLoc,randomUVec);
	}
	
	public static void equilibrateMyoMiniNumber() {
		if (myoMiniFilCt < Env.initialMyoMiniFils.getIntValue()) {
			makeRandomMyoMiniFil();
		}
	}
	
	public static void makeTestMiniFil () {
		Pt3D unitVec = new Pt3D(1,1,0);
		unitVec.unitVec();
		
		new MyoMiniFilament(new Pt3D(0,0,0),unitVec);
	}
	
	public static void addMiniFil (MyoMiniFilament newMiniFil) {
		myoMiniFils[myoMiniFilCt] = newMiniFil;
		myoMiniFils[myoMiniFilCt].myMyoMiniNumber = myoMiniFilCt;
		myoMiniFilCt ++;
	}
	
	public static synchronized void removeRandomMiniFil() {
		if (myoMiniFilCt == 0) { return; }
		int rmMeIndex = (int)(Math.random()*myoMiniFilCt);
		myoMiniFils[rmMeIndex].removeAllMyosins();
		myoMiniFils[rmMeIndex].removeAllMyoDimers();
		removeMyoMiniFil(myoMiniFils[rmMeIndex],rmMeIndex);
	}
	
	public static synchronized void cleanUpMyoMinis() {
		for (int i=0;i<myoMiniFilCt;i++) { 
			if (myoMiniFils[i] == null) { break; } // we've reached the end of the array through deletions
			if (myoMiniFils[i].removeMe) {
				myoMiniFils[i].removeAllMyosins();
				myoMiniFils[i].removeAllMyoDimers();
				myoMiniFils[i] = myoMiniFils[myoMiniFilCt-1];
				myoMiniFils[i].myMyoMiniNumber = i;
				myoMiniFils[myoMiniFilCt-1] = null;  // set pointer to null for garbage collection
				myoMiniFilCt --;
			}
		}
	}
	
	public static synchronized void removeMyoMiniFil (MyoMiniFilament rmMe, int nodeIndex) {
		myoMiniFils[nodeIndex] = myoMiniFils[myoMiniFilCt-1];
		myoMiniFils[nodeIndex].myMyoMiniNumber = nodeIndex;
		myoMiniFils[myoMiniFilCt-1] = null;  // set pointer to null for garbage collection
		myoMiniFilCt --;
		rmMe.removeMe = true;
	}
	
	public void removeAllMyosins() {
		for (int i=0;i<numMyosinHeads;i++) {
			myoPtsInx[i] = null;
			myoPtsInX[i] = null;
			myosins[i].removeMe = true;
			myosins[i] = null; 
		}
		numMyosinHeads = 0;
	}
	
	public void removeAllMyoDimers() {
		for (int i=0;i<numMyoDimersEachEnd;i++) {
			myoDimerPtsEnd1Inx[i] = null;
			myoDimerPtsEnd1InX[i] = null;
			myoDimersEnd1[i].removeMe = true;
			myoDimersEnd1[i] = null; 
			
			myoDimerPtsEnd2Inx[i] = null;
			myoDimerPtsEnd2InX[i] = null;
			myoDimersEnd2[i].removeMe = true;
			myoDimersEnd2[i] = null; 
		}
		numMyoDimersEachEnd = 0;
	}
	
	public static void removeAll () {
		for (int i=0;i<myoMiniFilCt;i++) {
			myoMiniFils[i].removeMe = true;
			myoMiniFils[i] = null;
		}
		myoMiniFilCt = 0;
	}
}
