package boxOfActin;
/**
 * ProteinNode.java
 *
 *
 */



import java.awt.*;
import javax.swing.*;
import java.lang.Math.*;


public class ProteinNode extends Thing {  
	static ProteinNode [] theNodes = new ProteinNode [100000];	// array holding all protein nodes
	static int nodeCt = 0;			//
	private int boundFilaments = 0;			// counter for number of bound actin filaments
	private Object syncForminFils = new Object();  // for sync of adding formin filaments
	int myNodeNumber;
	double radius = Env.nodeRadius.getValue();
	boolean iAmArpActivator = false;
	boolean iAmHotRho = false;
	boolean fixedNode = false; // use to fix a node in space

	
	// for multithreading
	static ProteinNodeThreads nodeThreads = new ProteinNodeThreads();
	
	// re-used in force calcs
	Pt3D F = new Pt3D();
	Pt3D R = new Pt3D();  
	Pt3D RcrossF = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D linkUVec1 = new Pt3D(); 
	Pt3D linkUVec2 = new Pt3D();
	
	// movement restrictions  
	boolean xMove = true;
	boolean yMove = true; 
	boolean zMove = true;
	boolean bXMove = true;
	boolean bYMove = true;
	boolean bZMove = true;
	
	// constant force (for testing)
	Pt3D constantF = new Pt3D();
	
	// formins in node
	static int maxFormins = 30;
	FilSegment [] forminFils = new FilSegment [maxFormins];
	int forminCt = 0;
	
	// myosin heads
	int myoCt = Env.numNodeMyos.getIntValue();
	Myosin [] myosins = new Myosin [myoCt];
	Pt3D [] myoPtsInx = new Pt3D[myoCt];
	Pt3D [] myoPtsInX = new Pt3D[myoCt];
	
	// myosin dimers
	int myoDimerCt = Env.numNodeMyoDimers.getIntValue();
	MyosinDimer [] myodimers = new MyosinDimer[myoDimerCt];
	Pt3D [] myoDimerPtsInx = new Pt3D[myoDimerCt];
	Pt3D [] myoDimerPtsInX = new Pt3D[myoDimerCt];
	
	ValueTracker bugCollisionMagOuter = new ValueTracker(Env.bugNodeCollisionsToSum);
	ValueTracker bugCollisionMagInner = new ValueTracker(Env.bugNodeCollisionsToSum);
	ValueTracker bugCollidingTrack = new ValueTracker(Env.bugNodeCollisionsToSum);
	Color nodeColor = Color.green;	// color for drawing


	
	public ProteinNode (Pt3D initCoord, boolean fromQKFile) {
		super(initCoord);
		addNode(this);
		calculateProperties();
		pushPoseToSoa();
		initialize();
		makeMyosinSinglets();
		makeMyosinDimers();
	}

	public ProteinNode (Pt3D initCoord, Pt3D initUVec, Pt3D initZVec, boolean fromQKFile) {
		super(initCoord);
		// initUVec and initZVec may not be truly orthogonal; orthonormalise in
		// Pt3D scratch then push uVec/yVec to SoA. zVec is derived from uVec×yVec
		// by recomputeDerivedSoA in initialize().
		Pt3D yScratch = new Pt3D();
		yScratch.cross(initZVec, initUVec);
		yScratch.unitVec();
		Pt3D uScratch = new Pt3D();
		uScratch.cross(yScratch, initZVec);
		uScratch.unitVec();
		setUVec(uScratch);
		setYVec(yScratch);
		addNode(this);
		calculateProperties();
		initialize();
		makeMyosinSinglets();
		makeMyosinDimers();
	}

	public ProteinNode (Pt3D initCoord, Pt3D hemisphereNormal) {  // this constructor specifies a hemisphere for myosins
		super(initCoord);
		addNode(this);
		calculateProperties();
		pushPoseToSoa();
		initialize();
		makeMyosinSinglets(hemisphereNormal);
		makeMyosinDimers(hemisphereNormal);
	}

	public ProteinNode (Pt3D initCoord, double radius) {  // this constructor specifies a radius
		super(initCoord);
		addNode(this);
		this.radius = radius;
		calculateProperties();
		pushPoseToSoa();
		initialize();

		makeMyosinSinglets();
		makeMyosinDimers();

	}
	
	static class ProteinNodeThreads extends ThreadSet {
		ProteinNodeThreads () {
			super (Env.numMyoThreads, "Protein Node Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.myoJoints2Start:
					if (nodeCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*nodeCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.myoJoints2Stop:
					if (nodeCt == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.myoJoints2Start:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						theNodes[i].updateMyosins(); 
					}
					break;
			}
		}
	}
	
	public static double defaultTransDiff () {
		double defaultTransGam = 6*Math.PI*Env.aeta.getValue()*(1.0e-6*Env.nodeRadius.getValue());
		return Env.Boltz*Env.tempK/defaultTransGam;
	}
	
	public static double defaultRotDiff () {
		double nrM = 1.0e-6*Env.nodeRadius.getValue();
		double defaultRotGam = 8*Math.PI*Env.aeta.getValue()*(nrM*nrM*nrM);
		return Env.Boltz*Env.tempK/defaultRotGam;
	}
	
	public void calculateProperties () {
		if (Env.nodeTransDiff.isActive()) {
			bTransDiff.x = Env.nodeTransDiff.getValue();
			bTransDiff.y = bTransDiff.x;
			bTransDiff.z = bTransDiff.x;
			bTransGam.div(Env.Boltz*Env.tempK,bTransDiff);		// Einstein's relation gamma=kT/D
		} else {
			double radiusM = getRadius()*1.0e-6;
			bTransGam.x = 6*Math.PI*Env.aeta.getValue()*radiusM;
			bTransGam.y = bTransGam.x;
			bTransGam.z = bTransGam.x;
			bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
			//System.out.println ("nodeTransDiff = " + bTransDiff.x);
		}
		
		if (Env.nodeRotDiff.isActive()) {
			bRotDiff.x = Env.nodeRotDiff.getValue();
			bRotDiff.y = bRotDiff.x;
			bRotDiff.z = bRotDiff.x;
			bRotGam.div(Env.Boltz*Env.tempK,bRotDiff);		// Einstein's relation gamma=kT/D
		} else {
			double radiusM = getRadius()*1.0e-6;
			bRotGam.x = 8*Math.PI*Env.aeta.getValue()*(radiusM*radiusM*radiusM);	// drag for turning about x
			bRotGam.y = bRotGam.x;
			bRotGam.z = bRotGam.x;
			bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);		// Einstein's relation gamma=kT/D
		}
		
		talkln ("Node trans/rot diff =" + String.valueOf(expFormat.format(bTransDiff.x)) + " / " + String.valueOf(expFormat.format(bRotDiff.x)));
	}
	
	public void initialize () {
		// ProteinNode has no length, but recomputeDerivedSoA still computes
		// transXTox and zVec from uVec/yVec — needed by downstream phases.
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
	}
	
	public void moveThing () {
		// Given the forces/torques at this time point... move with explicit Euler approximation to ODE solution
		
		// first check that forceSum and torqueSum aren't wacky... exit method if they are
		if (!isForceSumFinite()) { return; }
		if (!isTorqueSumFinite()) { return; }

		// add constant force for testing
		//if (Env.simulationTime < 0.6) { forceSum.add(constantF);}

		// Work in coordinates aligned with the node... transform forces and torques into body-fixed axis....
		int sBase = myThingNumber * 3;
		bForceSum.XToxFromFloats(this, Thing.soaForceSum, sBase);
		bTorqueSum.XToxFromFloats(this, Thing.soaTorqueSum, sBase);
		
		if (!Env.nodeBrownianMotionOff) {
			// add random forces, given in body-fixed frame
			bForceSum.inc(randForces);
			bTorqueSum.inc(randTorques);
		}
		
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec
		
		// **** movement restrictions in body-fixed frame ****
		if (!bYMove) { bVeloc.y = 0; }
		
		// ** before progressing .... check that bVeloc and bAngVeloc are not NaN... exit if wacky
		if (!bVeloc.checkPt3D()) { 
			talkln ("** problem with bVeloc for filament " + String.valueOf(this)); return; }
		if (!bAngVeloc.checkPt3D()) { talkln ("** problem with bAngVeloc for filament " + String.valueOf(this)); return; }
		
		// ....then transform back into fixed coordinate frame
		veloc.xToX(this, bVeloc);
		
		// **** movement restrictions in fixed frame ****
		if (!xMove) { veloc.x = 0; } 
		if (!yMove) { veloc.y = 0; }
		if (!zMove) { veloc.z = 0; }
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
	}
	
	public void membraneNodesMove () {
		if (this instanceof StickyNode && !fixedNode) {  // only do this if a non-fixed membrane node
			//if (didCollide()) { bZMove = false; } // restrict body-fixed y-direction motion for nodes that have just collided, i.e. these membrane nodes readjust in plane of membrane only
			moveThing();
			bZMove = true;  // reset or big trouble!
			zeroForceSumSlot();   // reset forces only, not collisionCt
			zeroTorqueSumSlot();
		}
	}
	
	public void biochemStep () {
		if (Env.mtRNG.nextDouble() < Env.deltaT.getValue()/Env.nodeLifetime.getValue()) {
			removeMe = true;
		}
	}
	
	public void step () {
		//increment counters that control how often different sims occur
		collCheckCt++;
		if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			long _spT = StepProfiler.t0();
			checkBugOrBoxCollision();
			StepProfiler.add(StepProfiler.F12_PROTEINNODE_BOUNDARY, _spT);
			collCheckCt = 0;
		}

	}
	
	public void resetCounters() {
		super.resetCounters();
		forminCt = 0;
	}
	
	public static void zeroAllForceSums () {
		for (int i=0; i<nodeCt; i++) {
			theNodes[i].zeroForceSumSlot();
			theNodes[i].bTorqueSum.zero();
		}
	}
	
	public void registerWithNode (FilSegment forminSeg) {
		synchronized (syncForminFils) {
			forminFils[forminCt] = forminSeg;
			forminCt++;
		}
	}
	
	public FilSegment getOtherForminFil (FilSegment oneFil) {
		for (int i=0;i<forminCt;i++) {
			if (forminFils[i]!=oneFil) { return forminFils[i]; }
		}
		return null;
	}
	
	public void makeMyosinSinglets () {
		for (int i=0;i<myoCt;i++) {
			myoPtsInx[i] = Pt3D.RandomUnitVec(myPRNG);
			Pt3D myoUVec = new Pt3D(myoPtsInx[i]);
			myoPtsInx[i].scale(getRadius());
			
			myoPtsInX[i] = new Pt3D();
			myoPtsInX[i].xToX(this,myoPtsInx[i]);
			myoPtsInX[i].inc(coordAsPt3D());
			myosins[i] = new Myosin(myoPtsInX[i],myoUVec);
			myosins[i].setOwnerNode(this);
			//if (Env.showProteinNode.isActive()) { myosins[i].setRodInvisible(true); }
		}
	}
	
	public void makeMyosinSinglets (Pt3D hemisphereNormal) {
		for (int i=0;i<myoCt;i++) {
			myoPtsInx[i] = Pt3D.RandomUnitVec(myPRNG);
			// check if randomly created uVecAsPt3D() points to opposite hemisphere, if so change direction
			if (Pt3D.Dot(hemisphereNormal, myoPtsInx[i]) < 0) {
				myoPtsInx[i].reverse();
			}
			
			Pt3D myoUVec = new Pt3D(myoPtsInx[i]);
			myoPtsInx[i].scale(getRadius());
			
			myoPtsInX[i] = new Pt3D();
			myoPtsInX[i].xToX(this,myoPtsInx[i]);
			myoPtsInX[i].inc(coordAsPt3D());
			myosins[i] = new Myosin(myoPtsInX[i],myoUVec);
			myosins[i].setOwnerNode(this);
			//if (Env.showProteinNode.isActive()) { myosins[i].setRodInvisible(true); }
		}
	}
	
	
	public void updateMyosinPositions () {
		for (int i=0;i<myoCt;i++) {
			myoPtsInX[i].xToX(this,myoPtsInx[i]);
			myoPtsInX[i].inc(coordAsPt3D());
		}
	}
	
	public void keepMyosinsOnSurface () {
		double attnForce = 0.4;
		Pt3D curAttPt;
		Myosin curMyo;
		Pt3D myoAttPt;
		for (int i=0;i<myoCt;i++) {
			curMyo = myosins[i];
			curAttPt = myoPtsInX[i];
			myoAttPt = curMyo.myoRod.end1AsPt3D();  // rod end1AsPt3D() or end2AsPt3D()?  Hmmm...
			double strainDist = Pt3D.ptDist(myoAttPt, curAttPt);
			linkUVec1.unitVec(curAttPt,myoAttPt);
			linkUVec2.scale(-1,linkUVec1);
			//double forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curMyo.myoRod.bTransGam.y + 1/bTransGam.y));
			double forceMag = attnForce*(1.0e-6*strainDist/Env.numNodeMyos.getValue())/(Env.deltaT.getValue()*(1/curMyo.myoRod.bTransGam.y + 1/bTransGam.y));

			F.scale(forceMag,linkUVec1);
			curMyo.myoRod.incForceSum(F);
			
			F.scale(-1,F);
			incForceSum(F);
		}
	}
	
	public static void updateAllMyosins () {
		for (int i=0;i<nodeCt;i++) {
			theNodes[i].updateMyosins();
		}
	}
	
	public void updateMyosins () {
		updateMyosinPositions();
		keepMyosinsOnSurface();
		
		updateMyosinDimerPositions();
		keepMyosinDimersOnSurface();
	}
	
	public void makeMyosinDimers () {
		for (int i=0;i<myoDimerCt;i++) {
			myoDimerPtsInx[i] = Pt3D.RandomUnitVec(myPRNG);
			Pt3D myoUVec = new Pt3D(myoDimerPtsInx[i]);
			myoDimerPtsInx[i].scale(getRadius());
			
			myoDimerPtsInX[i] = new Pt3D();
			myoDimerPtsInX[i].xToX(this,myoDimerPtsInx[i]);
			myoDimerPtsInX[i].inc(coordAsPt3D());
			myodimers[i] = new MyosinDimer(myoDimerPtsInX[i],myoUVec);
			myodimers[i].setOwnerNode(this);
			//myosins[i].rodInvisible = true;
		}
	}
	
	public void makeMyosinDimers (Pt3D hemisphereNormal) {
		for (int i=0;i<myoDimerCt;i++) {
			myoDimerPtsInx[i] = Pt3D.RandomUnitVec(myPRNG);
			// check if randomly created uVecAsPt3D() points to opposite hemisphere, if so change direction
			if (Pt3D.Dot(hemisphereNormal, myoDimerPtsInx[i]) < 0) {
				myoDimerPtsInx[i].reverse();
			}
			Pt3D myoUVec = new Pt3D(myoDimerPtsInx[i]);
			myoDimerPtsInx[i].scale(getRadius());
			
			myoDimerPtsInX[i] = new Pt3D();
			myoDimerPtsInX[i].xToX(this,myoDimerPtsInx[i]);
			myoDimerPtsInX[i].inc(coordAsPt3D());
			myodimers[i] = new MyosinDimer(myoDimerPtsInX[i],myoUVec);
			myodimers[i].setOwnerNode(this);
			//myosins[i].rodInvisible = true;
		}
	}
	
	public void updateMyosinDimerPositions () {
		for (int i=0;i<myoDimerCt;i++) {
			myoDimerPtsInX[i].xToX(this,myoDimerPtsInx[i]);
			myoDimerPtsInX[i].inc(coordAsPt3D());
		}
	}
	
	public void keepMyosinDimersOnSurface () {
		double attnForce = 0.4;
		Pt3D curAttPt;
		MyosinDimer curMyoD;
		MyoRod curRod;
		for (int i=0;i<myoDimerCt;i++) {
			curMyoD = myodimers[i];
			curRod = curMyoD.myo1.myoRod;
			curAttPt = myoDimerPtsInX[i];
			double strainDist = Pt3D.ptDist(curRod.end1AsPt3D(), curAttPt);
			linkUVec1.unitVec(curAttPt,curRod.end1AsPt3D());
			linkUVec2.scale(-1,linkUVec1);
			double forceMag = attnForce*(Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curRod.bTransGam.y + 1/bTransGam.y));

			F.scale(forceMag,linkUVec1);
			curMyoD.myo1.myoRod.incForceSum(F,curRod.end1AsPt3D());
			
			F.scale(-1,F);
			incForceSum(F,curAttPt);
		}
	}
	
	public void setMyosinsToRigor () { 
		for (int i=0;i<myoCt;i++) {
			myosins[i].setInRigor();
		}
	}
	
	public void checkBugOrBoxCollision() {
		if (Env.simOutsideBug.isActive()) { 
			checkBugCollisionFromOutside();
		} else {
			checkOuterBugCollision();						
			if (Thing.theBox instanceof Bug) { checkInnerBugCollision(); }	// nodes can be constrained to cortex by trapping between inner and outer bug shell
		}
	}
	
	public void checkOuterBugCollision () {
		theBox.amICollidingOuter(cE,coordAsPt3D(),getRadius());
		if (cE.delta != 0) {
			double mag = Env.nodeFracMove*1.0e-6*cE.delta*bTransGam.x/Env.collisionDeltaT.getValue();
			incForceSum(Pt3D.Scale(mag,cE.forceUVec));
		}
	}
	
	public void checkInnerBugCollision () {
		theBox.amICollidingInner(cE,coordAsPt3D(),getRadius());
		if (cE.delta != 0) {
			double mag = Env.nodeFracMove*1.0e-6*cE.delta*bTransGam.x/Env.collisionDeltaT.getValue();
			incForceSum(Pt3D.Scale(mag,cE.forceUVec));
		}
	}
	
	public void checkBugCollisionFromOutside() {
		lmBug.amICollidingFromOutside(cE,coordAsPt3D(),radius);
		if (cE.delta != 0) {
			double mag = Env.nodeFracMove*1.0e-6*cE.delta*bTransGam.x/Env.collisionDeltaT.getValue();
			incForceSum(Pt3D.Scale(mag,cE.forceUVec));
		}
		
		
	}
	
	public static void checkNodeCollision () {   // this method not called anymore with multi-threaded architecture
		for (int i=0; i<nodeCt; i++) {
			ProteinNode iP = theNodes[i];
			for (int p=i+1;p<nodeCt;p++) {
				ProteinNode pP = theNodes[p];
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
	
	public static void meshAllNodes () {  // this method not called anymore with multi-threaded architecture
		ProteinNode curNode;
		for (int i=0;i<nodeCt;i++) {
			curNode = theNodes[i];
			Mesh.NODE_MESH.fillNodeMesh(curNode);
		}
	}
	
	public static void nodeMeshCollisions(){  // this method not called anymore with multi-threaded architecture
		ProteinNode iNode,jNode;
		for(int x=0;x<Mesh.nXBins;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				
				if(Mesh.NODE_MESH.timeStamps[x][y]==Env.counter){
				
					for(int i=0;i<Mesh.NODE_MESH.activeCts[x][y];i++){
						int iNodeID=(int)Mesh.NODE_MESH.meshpoints[x][y][i];
						iNode=theNodes[iNodeID];
						for (int j=i;j<Mesh.NODE_MESH.activeCts[x][y];j++) {
							int jNodeID=(int)Mesh.NODE_MESH.meshpoints[x][y][j];
							jNode=theNodes[jNodeID];
							checkNodeCollision(iNode,jNode);
						}
					}
				}
			}
		}
	}
	
	public static void nodeMeshCollisions(int xStart, int xStop){
		ProteinNode iNode,jNode;
		for(int x=xStart;x<xStop;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				
				if(Mesh.NODE_MESH.timeStamps[x][y]==Env.counter){
				
					for(int i=0;i<Mesh.NODE_MESH.activeCts[x][y];i++){
						int iNodeID=(int)Mesh.NODE_MESH.meshpoints[x][y][i];
						iNode=theNodes[iNodeID];
						for (int j=i;j<Mesh.NODE_MESH.activeCts[x][y];j++) {
							int jNodeID=(int)Mesh.NODE_MESH.meshpoints[x][y][j];
							jNode=theNodes[jNodeID];
							if (iNode != jNode) { checkNodeCollision(iNode,jNode); } // check node collisions if not the same node
						}
					}
				}
			}
		}
	}
	
	public static void checkNodeCollision (ProteinNode iP, ProteinNode pP) {
		// define the softness of collisions between things
		double stickyAttn = 0.02;
		double fillAttn = 0.2;
		double fillStickyAttn = 0.4;
		double otherAttn = 0.4;
		// a bit awkward for now, but here's where we determine whom collides with whom
		if (iP instanceof StickyNode && pP instanceof StickyNode) { // no force collision, but check for linking
			//if (true) { return; }  // remove for self-linking
			if (Env.simulationTime < Env.linkMembraneTime) {
				forceCollision(iP,pP,stickyAttn);
				return;
			}  
			if (!iP.fullyBound() && !pP.fullyBound()) { //(Env.simulationTime < 0) {
				double pDist = Pt3D.ptDist(iP.coordAsPt3D(), pP.coordAsPt3D());
				double radDist = pP.getRadius()+iP.getRadius();
				if (pDist < 1.0*radDist) { StickyNode.ckToLink((StickyNode)iP,(StickyNode)pP); }
				return;
			} 
			return;
		} 
		
		if (iP instanceof FillNode) {	// fillNodes only collide with each other or membrane nodes
			if (pP instanceof FillNode) { forceCollision(iP,pP,fillAttn); }
			if (pP instanceof StickyNode) { oneSidedForceCollision(iP,pP,fillStickyAttn); }
			return;
		}
		
		if (pP instanceof FillNode) {	// fillNodes only collide with each other or membrane nodes
			if (iP instanceof FillNode) { forceCollision(iP,pP,fillAttn); }
			if (iP instanceof StickyNode) { oneSidedForceCollision(pP,iP,fillStickyAttn); }
			return;
		}
		
		// every other possibility, if we've made it this far, should have a forceCollision
		forceCollision(iP,pP,otherAttn);
	}
	
	public static void forceCollision (ProteinNode iP, ProteinNode pP, double attnF) {
		double pDist = Pt3D.ptDist(iP.coordAsPt3D(), pP.coordAsPt3D());
		double radDist = pP.getRadius()+iP.getRadius();
		if (pDist < radDist) {
			double impingedist = radDist - pDist;
		    Pt3D iVec = Pt3D.UnitVec(pDist, iP.coordAsPt3D(), pP.coordAsPt3D());
			Pt3D pVec = Pt3D.Reverse(iVec);
			double mag = attnF*(1.0e-6*impingedist/Env.collisionDeltaT.getValue())/(1/iP.bTransGam.x+1/pP.bTransGam.x);
			iP.incForceSum(Pt3D.Scale(mag,iVec));
			pP.incForceSum(Pt3D.Scale(mag,pVec));
			
			//register collsions
			iP.collision();
			pP.collision();
		}
	}
	
	public static void oneSidedForceCollision (ProteinNode iP, ProteinNode pP, double attnF) {
		// First object gets collision force applied... second gets away clean!  Only for ghost aspects of sim., like fill nodes tracking volume/concentrations
		// force mag calculation considers second object to be immobile... all motion from iP
		double pDist = Pt3D.ptDist(iP.coordAsPt3D(), pP.coordAsPt3D());
		double radDist = pP.getRadius()+iP.getRadius();
		if (pDist < radDist) {
			double impingedist = radDist - pDist;
		    Pt3D iVec = Pt3D.UnitVec(pDist, iP.coordAsPt3D(), pP.coordAsPt3D());
			//Pt3D pVec = Pt3D.Reverse(iVec);  // not used in one-sided collision
			double mag = attnF*(1.0e-6*impingedist/Env.collisionDeltaT.getValue())/(1/iP.bTransGam.x);
			iP.incForceSum(Pt3D.Scale(mag,iVec));
			//pP.incForceSum(Pt3D.Scale(mag,pVec)); not used in one-sided collision
			
			//register collsions  Don't register these non-newtonian interactions (i.e. no equal and opposite forces)
			//iP.collision();
			//pP.collision();
		}
	}
	
	public double getRadius () {
		return radius;
	}
	
	public static void spawnNodeFilaments () {
		ProteinNode curP;
		for (int i=0;i<ProteinNode.nodeCt;i++) {
			curP = ProteinNode.theNodes[i];
			if (curP.canNucleateFilament()) {
				if (Env.mtRNG.nextDouble() < Env.kNodeNuc.getValue()*Env.deltaT.getValue()) {
					Pt3D nucVec = curP.getNucleationVec();
					//Pt3D newAttachPt = Pt3D.Scale(curP.getRadius(),nucVec);
					//newAttachPt.inc(curP.coordAsPt3D());
					nucVec.scale(-1);	// reverse direction if end2AsPt3D() is node attached
					FilSegment newFil = new FilSegment (curP.coordAsPt3D(),nucVec,-1);
					newFil.translate(newFil.length/2,nucVec);
					newFil.nodeAtEnd2=true;
					newFil.end2Node=curP;
					newFil.end2PAttachPt.XToxFromxOrigin(curP,newFil.end2AsPt3D());
					newFil.end2PAttachPt.zero();  // use this for formins at center of node
					newFil.forminVecInx.XTox(curP,nucVec);
					curP.filamentOn();
				}
			}
		}
		
	}
	
	public void bespokeNodeFilament (int numMons, Pt3D nucVec) { 
		Pt3D nucVecR = Pt3D.Reverse(nucVec); // reverse uVecAsPt3D() direction if end2AsPt3D() is node attached
		FilSegment bespokeFil = new FilSegment (coordAsPt3D(),nucVecR,-1,numMons,false);
		bespokeFil.translate(bespokeFil.length/2,nucVec);
		bespokeFil.nodeAtEnd2=true;
		bespokeFil.end2Node=this;
		bespokeFil.end2PAttachPt.XToxFromxOrigin(this,bespokeFil.end2AsPt3D());
		bespokeFil.end2PAttachPt.zero();  // use this for formins at center of node
		bespokeFil.forminVecInx.XTox(this,nucVecR);
		filamentOn();
	}
	
	public boolean canNucleateFilament () {
		//if (this != theNodes[0]) { return false; }
		if (Env.octopusMode && this!=theNodes[0]) { return false; } ///Octopus mode.... only first node sends out filaments
		if (boundFilaments < Env.forminsPerNode.getValue()) { return true; }
		return false;
	}
	
	public Pt3D getNucleationVec () {
		FilSegment nucTo = null;
		Pt3D nucVec = Pt3D.RandomUnitVec(myPRNG);
		
		if (Env.twoForminsOpposite) {
			if (forminCt >= 1) {
				nucTo = forminFils[0];
				nucVec.copy(nucTo.uVecAsPt3D());
			}
		}
		
		return Pt3D.RandomUnitVec(myPRNG);
	}
	
	public boolean fullyBound() {
		return false;
	}
	
	public void filamentOn () {
		boundFilaments ++;
	}
	
	public void filamentOff () {
		boundFilaments --;
		if (boundFilaments < 0) { boundFilaments = 0; }
	}
	
	public int numberFilsBound () {
		return boundFilaments;
	}
	
	private void updateSphGraphics () {}
	
	public void makeGraphics () {}

	public void updateGraphics () {}
	
	
	public static void makeInitialProteinNodes() {
		for (int i=0;i<Env.initialNodes.getValue();i++) {
			makeRandomProteinNode();
		}
	}  
	
	public static void makeRandomProteinNode () {
		Pt3D randomLoc = Thing.theBox.rdmPtInside();
		if (Env.bugShapedCrucible.isActive()) {
			Bug theBug = (Bug)Thing.theBox;
			randomLoc = theBug.rdmPtInsideNodeZone();
		}
		new ProteinNode (randomLoc,false);
	}
	
	public static void equilibrateNodeNumber() {
		if (nodeCt < Env.equilNodes.getIntValue()) {
			rdmNodeInsideCurrentDistribution();
		}
	}
	
	public static void makeTestNodePair () {
		Pt3D loc1 = new Pt3D (Env.tstNodeOffset,0,0);
		Pt3D loc2 = new Pt3D (-Env.tstNodeOffset,0,0);
		new ProteinNode(loc1,false);
		new ProteinNode(loc2,false);
	}
	
	public static void makeTestNode () {
		new ProteinNode(new Pt3D(-0.0,0,0),false);
		//new ProteinNode(new Pt3D(-0.2,0,0),false);

	}
	
	public static Pt3D getMinMaxZOfNodeDistro () {
		Pt3D minMax = new Pt3D(1e6,1e-6,0);  	// store min in x, max in y.  z could be some other feature of distro in the future
		for (int i=0;i<nodeCt;i++) {
			double curZ = theNodes[i].getCoordZ();
			if (curZ < minMax.x) { minMax.x = curZ; }
			if (curZ > minMax.y) { minMax.y = curZ; }
		}
		return minMax;
	}
	
	public static void rdmNodeInsideCurrentDistribution () {
		double bRad = Env.bugRadius.getValue()-Env.nodeRadius.getValue();
		Pt3D pLoc = new Pt3D();
		Pt3D inwardNormal = new Pt3D();
		double curBRad;
		Pt3D minMaxZOfNodes = ProteinNode.getMinMaxZOfNodeDistro();
		pLoc.z = minMaxZOfNodes.x + Env.mtRNG.nextDouble()*(minMaxZOfNodes.y - minMaxZOfNodes.x);  // randomly choose z in range of current z positions of nodes
		curBRad = Math.sqrt(bRad*bRad - pLoc.z*pLoc.z);
		double rdmAng = Env.mtRNG.nextDouble()*2*Math.PI;
		pLoc.x = curBRad*Math.cos(rdmAng);
		pLoc.y = curBRad*Math.sin(rdmAng);
		inwardNormal.scale(-1,pLoc);
		new ProteinNode(pLoc,inwardNormal);
	}
	
	public static void makeMyosinClusterArray () { 
		ProteinNode node;
		if (!Env.bugShapedCrucible.isActive()) {
			// make a bunch of protein nodes in linear array, set number of myosins, adjust drag as desired
			int numClusters = Env.numHotSpotsOnCortex.getIntValue();
			double boxCushion = 6; // microns... cushion on each end of cluster
			double xStart = -1*Env.boxXDim.getValue()/2 + boxCushion;
			double xInc = (Env.boxXDim.getValue()-2*boxCushion)/(numClusters-1);
			double xStartEven = xStart;
			double xStartOdd = xStart - xInc/2;
			double xStartCur;
			int numClustersCur;
			Pt3D loc = new Pt3D(xStart,0,0);
			int offCenterRows = Env.numOffCenterHotSpotRows.getIntValue();
			double hotSpotLateralSpacing = Env.hotSpotRowSpacing.getValue(); // microns
			for (int i=-offCenterRows; i<=offCenterRows; i++) {
				if (i % 2 == 0) { 
					xStartCur = xStartEven;
					numClustersCur = numClusters;
				} else { 
					xStartCur = xStartOdd; 
					numClustersCur = numClusters+1;
				}
				loc.y = i*Env.hotSpotRowSpacing.getValue();
				for (int j=0;j<numClusters;j++) {
					loc.x=xStartCur+j*xInc;
					node = new ProteinNode(loc,false);
					//talkln ("Node trans diff = " + node.bTransDiff.x + "  Node rot diff = " + node.bRotDiff.x);
					//if ((i==0) || (i==numClusters-1)) { node.setMyosinsToRigor(); } //first and last cluster hold filaments, no walking
					//if ((j==0) | (j==numClustersCur-1)) { node.xMove = false; node.zMove = false; }
					if (true) { node.xMove = false; node.zMove = false; }
				}
			}
			talkln("*Protein Cluster spacing is " + xInc + " microns across box*");
			
			/*// make more rows
			xStart = -1*Env.boxXDim.getValue()/2 + boxCushion - xInc/2;
			loc.y = 1.73;
			for (int i=0;i<numClusters+1;i++) {
				loc.x=xStart+i*xInc;
				node = new ProteinNode(loc,false);
				if ((i==0) | (i==numClusters)) { node.xMove = false; node.zMove = false; }
			}
			loc.y = -1.73;
			for (int i=0;i<numClusters+1;i++) {
				loc.x=xStart+i*xInc;
				node = new ProteinNode(loc,false);
				if ((i==0) | (i==numClusters)) { node.xMove = false; node.zMove = false; }
			}*/
		} else {
			int numClusters = Env.numHotSpotsOnCortex.getIntValue();
			double bRad = Env.bugRadius.getValue()-Env.nodeRadius.getValue();
			double bugCirc = 2*Math.PI*bRad;
			double clusterSpacing = bugCirc/(double)numClusters;
			talkln("*Protein Cluster spacing is " + clusterSpacing + " microns on cortex*");
			double clusterAngSep = 2*Math.PI/(double)numClusters;
			Pt3D pLoc = new Pt3D();
			Pt3D inwardNormal = new Pt3D();
			int offCenterRows = Env.numOffCenterHotSpotRows.getIntValue();
			double hotSpotLateralSpacing = Env.hotSpotRowSpacing.getValue(); // microns
			double curBRad;
			double angleOffset = 0;
			for (int i=-offCenterRows; i<=offCenterRows; i++ ) {
				pLoc.z = i*hotSpotLateralSpacing;
				curBRad = Math.sqrt(bRad*bRad - (i*hotSpotLateralSpacing)*(i*hotSpotLateralSpacing));
				angleOffset += clusterAngSep/2;
				for (int j=0;j<numClusters;j++) {
					pLoc.x = curBRad*Math.cos(j*clusterAngSep+angleOffset);
					pLoc.y = curBRad*Math.sin(j*clusterAngSep+angleOffset);
					inwardNormal.scale(-1,pLoc);
					node = new ProteinNode(pLoc,inwardNormal);
				}
			}
		}
	}
	
	public void removeAllMyosins() {
		for (int i=0;i<myoCt;i++) {
			myoPtsInx[i] = null;
			myoPtsInX[i] = null;
			myosins[i].removeMe = true;
			myosins[i] = null; 
		}
		myoCt = 0;
	}
	
	public void removeAllMyoDimers() {
		for (int i=0;i<myoDimerCt;i++) {
			myoDimerPtsInx[i] = null;
			myoDimerPtsInX[i] = null;
			myodimers[i].removeMe = true;
			myodimers[i] = null; 
		}
		myoDimerCt = 0;
	}
	
	public static synchronized void releaseAllMyos() {
		for (int i=0;i<nodeCt;i++) {
			theNodes[i].removeAllMyosins();
			theNodes[i].removeAllMyoDimers();
		}
	}
	
	public static synchronized void removeRandomNode() {
		if (nodeCt == 0) { return; }
		int rmMeIndex = (int)(Math.random()*nodeCt);
		theNodes[rmMeIndex].removeAllMyosins();
		theNodes[rmMeIndex].removeAllMyoDimers();
		removeNode(theNodes[rmMeIndex],rmMeIndex);
	}
	
	public static synchronized void cleanUpNodes() {
		for (int i=0;i<nodeCt;i++) { 
			if (theNodes[i] == null) { break; } // we've reached the end of the array through deletions
			if (theNodes[i].removeMe) {
				theNodes[i].removeAllMyosins();
				theNodes[i].removeAllMyoDimers();
				theNodes[i] = theNodes[nodeCt-1];
				theNodes[i].myNodeNumber = i;
				theNodes[nodeCt-1] = null;  // set pointer to null for garbage collection
				nodeCt --;
			}
		}
	}
	
	
	public static synchronized void addNode (ProteinNode newNode) {
		theNodes[nodeCt] = newNode;
		theNodes[nodeCt].myNodeNumber = nodeCt;
		nodeCt ++;
	}
	
	public static synchronized void removeNode (ProteinNode rmMe, int nodeIndex) {
		theNodes[nodeIndex] = theNodes[nodeCt-1];
		theNodes[nodeIndex].myNodeNumber = nodeIndex;
		theNodes[nodeCt-1] = null;  // set pointer to null for garbage collection
		nodeCt --;
		rmMe.removeMe = true;
	}
	
	public static void removeAll () {
		for (int i=0;i<nodeCt;i++) {
			theNodes[i].removeMe = true;
			theNodes[i] = null;
		}
		nodeCt = 0;
	}
}
