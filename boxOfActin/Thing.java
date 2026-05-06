package boxOfActin;
/*
	// Thing.... the superclass for all moving objects in this demonstration
*/
import java.awt.*;
import java.text.DecimalFormat;

import javax.media.j3d.*;
import javax.vecmath.*;

import edu.cornell.lassp.houle.RngPack.RanMT;
import ec.util.MersenneTwisterFast;

import java.util.Date;
import java.util.Random;

public class Thing extends Object {
	
	static Thing [] theThings = new Thing [1000000];	// array of all things
	static int thingCt = 0;								// how many things
	static Crucible theBox;								// only one bug/box
	static Bug lmBug;				// only one listeria
	// for efficiency
	static final Pt3D xUnitVector = new Pt3D(1,0,0);	// unit vector along body-fixed x-axis
	static final Pt3D yUnitVector = new Pt3D(0,1,0);	// unit vector along body-fixed y-axis
	static final Pt3D zUnitVector = new Pt3D(0,0,1);	// unit vector along body-fixed z-axis
	static final Pt3D zeroVec = new Pt3D();	// just a zero Pt3D
	int myThingNumber;					// identifies where in "theThings" this Thing is
	boolean removeMe = false;			// if true this Thing will be eliminated
	Pt3D coord = new Pt3D();			// the x,y,and z position of the Thing
	static Pt3D maxPos = Env.worldDimension;	// maximum x position this Thing can occupy
	double [][] transXTox = new double [3][3];	// transformation matrix from fixed to body-fixed frame
	double [][] transxToX = new double [3][3];	// the inverse transformation... body-fixed to fixed
	Matrix3d mxToX = new Matrix3d(); 
	Matrix3d mXTox = new Matrix3d();
	Pt3D uVec = new Pt3D(1,0,0);		// the unit vector that describes the orientation of the player
	Pt3D uVecR = new Pt3D(-1,0,0);		// opposite direction of uVec
	Pt3D yVec = new Pt3D(0,1,0);		// the first transvers vector for this body... in y direction
	Pt3D zVec = new Pt3D(0,0,1);		// the second tranverse vector.. in z direction
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
	Pt3D forceSum = new Pt3D(); 		// fixed-frame force sums... FX, FY, FZ
	Pt3D torqueSum = new Pt3D();		// Euler axes torque sums
	Pt3D bForceSum = new Pt3D();		// body-fixed force sum
	Pt3D bTorqueSum = new Pt3D();		// body-fixed torque sum
	Pt3D bFricForceSum = new Pt3D();	// friction forces are implemented, and stay, in the body-fixed frame
	Pt3D bFricTorqueSum = new Pt3D();
	
	// multithreading
	static ThingStepThreads stepThreads = new ThingStepThreads();
	static ThingBrownianThreads brownianThreads = new ThingBrownianThreads();
	//RanMT myPRNG = new RanMT((long)(Long.MAX_VALUE*Math.random()));
	MersenneTwisterFast myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));
	CollisionEvent cE = new CollisionEvent();		// try to reuse when possible
	Object forceSync = new Object();  // for synchronizing addition of forces
	Object torqueSync = new Object(); // ditto for torques
	
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
	
	// for graphics
	boolean inGroup = false;
	boolean graphicsMade = false;
	BranchGroup G = new BranchGroup();
	TransformGroup g3d = new TransformGroup();
	Transform3D t3d = new Transform3D();
	Appearance a = new Appearance();
	Material m;
	
	static DecimalFormat expFormat = new DecimalFormat ("0.000E0");
	
	public Thing (Pt3D initCoord) {
		this.coord.copy(initCoord);
		addThing(this);
	}
	
	public class RetObj {
		// this is the object passed by from line-line and line-point intersect tests
		Pt3D conPt1, conPt2, ray1, ray2, ray3, ray4;
		double conDist = 0;
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
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
				case Env.moveStart:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
				case Env.biochemStart:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
				case Env.resetCtStart:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*thingCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.stepStop:
					gather(); break;
				case Env.moveStop:
					gather(); break;
				case Env.biochemStop:
					gather(); break;
				case Env.resetCtStop:
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
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (!theThings[i].removeMe) { theThings[i].calcRandomForces(); }
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		coord = null;
		transXTox = null;
		transxToX = null;
		mxToX = null;
		mXTox = null;
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
		forceSum = null;
		torqueSum = null;
		bForceSum = null;
		bTorqueSum = null;
		bFricForceSum = null;
		bFricTorqueSum = null;
		myPRNG = null;
		cE = null;
		forceSync = null;
		torqueSync = null;
		
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
	
		G = null;
		g3d = null;
		t3d = null;
		a = null;
		m = null;
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
		synchronized (forceSync) {
			forceSum.inc(forceToAdd);
		}
	}
	
	public synchronized void incForceSum (Pt3D forceToAdd, Pt3D forcePoint) {
		incForceSum(forceToAdd);
		rForce.sub(forcePoint,coord);
		rForce.scale(1e-6);	// units (from �m to m)
		tempTorq.cross(rForce, forceToAdd);
		incTorqueSum(tempTorq);
	}
	
	public void incTorqueSum (Pt3D torqueToAdd) {
		synchronized (torqueSync) {
			torqueSum.inc(torqueToAdd);
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
		// get fresh random value pairs in unit circle {v1,v2,rsq,facterm}
		xVals.newValue(Env.brownianDeltaT.getValue(),this);
		yVals.newValue(Env.brownianDeltaT.getValue(),this);
		zVals.newValue(Env.brownianDeltaT.getValue(),this);
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
		randForces.mult(1.0/Env.brownianDeltaT.getValue(), v1, fac1, bTransGam);
		randTorques.mult(1.0/Env.brownianDeltaT.getValue(), v2, fac2, bRotGam);
	}
	
	public static void brownianMotionForAll () {
		//talkln ("brownian apply @ " + Env.simulationTime + " seconds");
		for (int i=0;i<thingCt;i++) {
			theThings[i].calcRandomForces();
		}
	}
	
	public void transMat () {
		transXTox [0][0] = uVec.x;
		transXTox [0][1] = uVec.y;
		transXTox [0][2] = uVec.z;
		transXTox [1][0] = yVec.x;
		transXTox [1][1] = yVec.y;
		transXTox [1][2] = yVec.z;
		transXTox [2][0] = zVec.x;
		transXTox [2][1] = zVec.y;
		transXTox [2][2] = zVec.z;
		// the inverse transformation is found as the transpose of this matrix
		// since the direction cosine matrix is orthogonal.
		double curVal;
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				curVal = transXTox [j][i];
				transxToX [i][j] = curVal;
				// set Matrix3d versions
				mXTox.setElement(j,i,curVal);
				mxToX.setElement(i,j,curVal);
			}
		}
	}
	
	public void resetCounters() {
		forceSum.zero();
		torqueSum.zero();
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
	}
	
	public static void removeThing (Thing byeThing) {
		int swapId = byeThing.myThingNumber;
		theThings[swapId] = theThings[thingCt-1];
		theThings[swapId].myThingNumber = swapId;
		byeThing.sepaku();
		thingCt--;
	}
	
	public static void removeDeadThings () {
		for (int i=0;i<thingCt;i++) {
			if (theThings[i] == null) { break; }		// this means we've gotten to the end of our shortening list of things
			if (theThings[i].removeMe) {
				try {
					if (theThings[i].graphicsMade) { theThings[i].G.detach(); }
				} catch (NullPointerException npe) { } //talkln ("Null Pointer Exception trying to remove graphics object G in Thing.removeDeadThings"); }
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
					retO.conDist = Pt3D.ptDist (retO.conPt1,retO.conPt2);
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
			retO.conDist = Pt3D.ptDist (retO.conPt1,point);
		}
	}

	public void setGraphicsCapabilities () {
		g3d.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
		g3d.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
		g3d.setCapability(TransformGroup.ALLOW_CHILDREN_EXTEND);
		g3d.setCapability(TransformGroup.ALLOW_CHILDREN_WRITE);
		g3d.setCapability(TransformGroup.ALLOW_CHILDREN_READ);
		
		G.setCapability(BranchGroup.ALLOW_DETACH);
		G.setCapability(TransformGroup.ALLOW_CHILDREN_EXTEND);
		G.setCapability(TransformGroup.ALLOW_CHILDREN_WRITE);
		G.setCapability(TransformGroup.ALLOW_CHILDREN_READ);
		
		a.setCapability(Appearance.ALLOW_LINE_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_COLORING_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_POLYGON_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_MATERIAL_WRITE);
		
	}
	
	public void makeGraphics () {}
	public void updateGraphics () {}

	public Node getGraphicsNode () {
		if (!graphicsMade) { makeGraphics();}
		updateGraphics();
		return G;
	}
	
	public void detachGraphics () {
		G.detach();
		graphicsMade = false;
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

