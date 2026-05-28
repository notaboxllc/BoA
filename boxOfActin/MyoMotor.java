package boxOfActin;

public class MyoMotor extends Thing {
	static MyoMotor [] theMotors = new MyoMotor[100000];
	static int motorCt = 0;
	int myMotorNumber;

	// SoA arrays for GPU-ready motor-binding path (step 1a)
	static double[]  soaX     = new double[100000];
	static double[]  soaY     = new double[100000];
	static double[]  soaZ     = new double[100000];
	static boolean[] soaOnFil = new boolean[100000];

	static void fillSoaArrays() {
		for (int i = 0; i < motorCt; i++) {
			MyoMotor m = theMotors[i];
			soaX[i]     = m.bindTip.x;
			soaY[i]     = m.bindTip.y;
			soaZ[i]     = m.bindTip.z;
			soaOnFil[i] = m.onFil;
		}
	}

	static double radius = 0.01; // microns
	Object attachSync = new Object();	// synchronize attachment to filament

	// Binding-event statistics (validation instrumentation)
	static long totalBindEvents = 0;
	static long boundMotorSum = 0;
	static long boundMotorSampleCt = 0;

	static void sampleBoundMotors() {
		int ct = 0;
		for (int i = 0; i < motorCt; i++) { if (theMotors[i].onFil) ct++; }
		boundMotorSum += ct;
		boundMotorSampleCt++;
	}

	// binding and unbinding related
	static double bindTimer = 1e6;
	
	Myosin myMyosin;
	boolean onFil = false;
	boolean inRigor = false; // special flag... never unbinds a filament once it finds one
	
	Pt3D end1 = new Pt3D();	// the free-end	
	Pt3D end2 = new Pt3D();	// attached to head
	Pt3D bindTip;  
	
	// biochemical states
	static final byte NONE = 0;
	static final byte ATP = 1;
	static final byte ADPPi = 2;
	static final byte ADP = 3;
	byte nucleotideState = NONE;
	
	// empirical fit for viscous drags
	static final double aParallel = -0.20;  // approx to constant in damping for parallel motion
	static final double aOrthog = 0.84;		// ...for orthogonal motion
	static final double aTurning = -0.662; 	// ...for rotational motion
	
	// for collision detection
	double xRange,yRange,zRange;
	
	// myosins in node
	MyoFilLink tipLink;
	
	
	public MyoMotor(Pt3D initCoord) {
		super(initCoord);
		
		calculateProperties();
		initialize();
		addMyoMotor(this);
		
		// set binding points
		bindTip = end2;
		makeMyoFilLinks();
	}
	
	public MyoMotor(Pt3D initCoord, Pt3D initUVec) {
		super(initCoord);
		
		uVec.copy(initUVec);
		calculateProperties();
		initialize();
		addMyoMotor(this);
		
		// set binding points
		bindTip = end2;
		makeMyoFilLinks();
	}
	
	public void sepaku () {
		super.sepaku();
		myMyosin = null;
		end1 = null;
		end2 = null;
		bindTip = null;
	}
	
	public void set (Pt3D setCoord, Pt3D setUVec, double dim, byte nucState) {
		coord.copy(setCoord);
		uVec.copy(setUVec);
		Env.myoMotorLength.setValue(dim);
		nucleotideState = nucState;
	}
	
	public void calculateProperties () {
		// define the constants for motion of this rod in viscous medium
		// Remember that the dimensions we've been using are in micrometers so...
		double headLengthM = 1.0e-6*getDim(); // in meters
		double radiusM = radius*1.0e-6;
		double denomLogTerm = Math.log(headLengthM/(2*radiusM));	//dimensionless
		bTransGam.x = 6*Math.PI*Env.aeta.getValue()*radiusM;//(2*Math.PI*Env.aeta.getValue()*headLengthM)/(denomLogTerm + aParallel);
		bTransGam.y = bTransGam.x;//(4*Math.PI*Env.aeta.getValue()*headLengthM)/(denomLogTerm + aOrthog);
		bTransGam.z = bTransGam.y;
		bRotGam.x = 8*Math.PI*Env.aeta.getValue()*Math.pow(radiusM,3);//4*Math.PI*Env.aeta.getValue()*radiusM*radiusM*headLengthM;	// drag for turning about x
		bRotGam.y = bRotGam.x;//(Math.PI*Env.aeta.getValue()*Math.pow(headLengthM,3))/(3*(denomLogTerm + aTurning));
		bRotGam.z = bRotGam.y;
		
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
		
	}
	
	public void initialize () {
		// this method assumes the unit x and y vectors have been set (though maybe not orthogonal), or are unchanged
		// determine z-unit vectors, then reset y-unit vector to ensure orthogonality with uVec
		zVec.cross(uVec, yVec);
		yVec.cross(zVec, uVec);
		// find the transformation matrices at this time step
		transMat ();
		// define opposite to uVec direction, used frequently
		uVecR.scale(-1,uVec);
		
		// re-find the end points of the rod to make sure they meet length criteria
		end1.add(coord, -0.5*getDim(), uVec);
		end2.add(coord, 0.5*getDim(), uVec);
		
		// for collision detection
		xRange = Math.abs(coord.x-end2.x);
		yRange = Math.abs(coord.y-end2.y);
		zRange = Math.abs(coord.z-end2.z);
	}
	
	public void step () {
		if (Env.myosinsOff) { return; }
		bindTimer += Env.deltaT.getValue();
		
		collCheckCt++;
		if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			//checkOuterBugCollision();		// these should add forces and torques to forceSum and torqueSum
			collCheckCt = 0;
		}
		
		updateMyoFilLinks();
	}
	
	public void checkOuterBugCollision () {
		theBox.amICollidingOuter(cE,end1,radius);
		if (cE.delta != 0) {
			double mag = Env.nodeFracMove*1.0e-6*cE.delta*bTransGam.x/Env.collisionDeltaT.getValue();
			incForceSum(Pt3D.Scale(mag,cE.forceUVec));
		}
	}
	
	
	
/*	public void checkCocking() {
		if (!onFil) { return; }
		if (myPRNG.nextDouble() < 100*Env.deltaT.getValue()) { 
			myMyosin.cocked = ! myMyosin.cocked; 
			if (!myMyosin.cocked) {
				releaseAllMyoFilLinks();
			}
		}
	}*/
	
	public void setStateNONE () { nucleotideState = NONE; }

	public void setStateATP () { nucleotideState = ATP; }

	public void setStateADPPi () { nucleotideState = ADPPi; }

	public void setStateADP () { nucleotideState = ADP; }
	
	public void biochemStep (){
		if (Env.myosinsOff) { return; }
		switch (nucleotideState) {
		case NONE:
			atpOnMyo();
			break;
		case ATP:
			hydrolize();
			break;
		case ADPPi:
			dissociatePi();
			break;
		case ADP:
			dissociateADP();
			break;
		}
			
	}
	
	/*public void biochemStateSim () {
		if (myPRNG.nextDouble() < 100*Env.deltaT.getValue()) {
			graphicsUpdate = true;
			if (notATP()) { setStateATP(); return; }
			if (notADP()) { setStateADP(); return; }
		}
	}*/
	
	public void atpOnMyo () {
		if (myPRNG.nextDouble() < Env.atpOnMyo.getValue()*Env.deltaT.getValue()) {
			setStateATP();
        }
	}
	
	public void hydrolize (){
		if (onFil) {
			if (myPRNG.nextDouble() < Env.myoOnFilATP_ADPPi.getValue()*Env.deltaT.getValue()) { setStateADPPi(); }
		} else {
			if (myPRNG.nextDouble() < Env.myoOffFilATP_ADPPi.getValue()*Env.deltaT.getValue()) { setStateADPPi(); }
		}
	}
	
	public void dissociatePi() {
		if (onFil) {
			if (myPRNG.nextDouble() < Env.myoOnFilADPPi_ADP.getValue()*Env.deltaT.getValue()) { setStateADP(); }
		} else {
			if (myPRNG.nextDouble() < Env.myoOffFilADPPi_ADP.getValue()*Env.deltaT.getValue()) { setStateADP(); }
		}
	}	  
	
	public void dissociateADP() {
		if (tipLink.forceDotFilTrack.averageVal() > 0) { return; }
		if (myPRNG.nextDouble() < Env.myoOnFilADP_None.getValue()*Env.deltaT.getValue()) {
		     setStateNONE();
		}
	}	
	
	public boolean isCocked() {
		if (!isADPPi()) { return true; } else { return false; }
	}
	
	public void moveThing () {
		if (Env.myosinsOff) { return; }
		// Given the forces/torques at this time point... move with explicit Euler approximation to ODE solution
		
		// first check that forceSum and torqueSum aren't wacky... exit method if they are
		if (!forceSum.checkPt3D()) { 
			talkln ("Crazy forceSum in " + this); 
			forceSum.zero();
			forceSum.inc(randForces);
		}
		if (!torqueSum.checkPt3D()) { 
			talkln ("Crazy torqueSum in " + this); 
			torqueSum.zero();
			torqueSum.inc(randTorques);
		}
		
		// Work in coordinates aligned with the rod... transform forces and torques into body-fixed axis....
		bForceSum.XTox(this, forceSum);
		bTorqueSum.XTox(this, torqueSum);

		// add brownian force and torque... these are zero except at every chosen time-step
		if (!Env.brownianMyoMotionOff) {
			bForceSum.inc(Env.myoBrownianAttn.getValue(),randForces); //trans
			bTorqueSum.inc(Env.myoBrownianAttn.getValue(),randTorques); //rot
		}
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);	// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec
			
		// ** before progressing .... check that bVeloc and bAngVeloc are not NaN... exit if wacky
		if (!bVeloc.checkPt3D()) { talkln ("** problem with bVeloc for " + this); return; }
		if (!bAngVeloc.checkPt3D()) { talkln ("** problem with bAngVeloc for " + this); return; }
		
		// New Positions
		// the body-fixed angular velocities can just be transformed into fixed-frame velocities, and the coord updated
		veloc.xToX(this, bVeloc);
		coord.inc(Env.deltaT.getValue(),veloc);  // just position = velocity*time
		
		//deltaBAng.inc(Env.deltaT.getValue(),bAngVeloc);
		// to apply the body-fixed angular velocities, approximate new unit vector from arc of rotations.. good for small rotations
		// for uVec
		double uVecTransInZ = -bAngVeloc.y * Env.deltaT.getValue();	// arclength out at 1 micron
		double uVecTransInY = bAngVeloc.z * Env.deltaT.getValue();
		uVec.setVals(1,uVecTransInY,uVecTransInZ);	// in body-fixed, not a unit vector yet
		uVec.xToX(this);	// make in fixed-frame, not a unit vector yet
		uVec.unitVec();		// make a unit vector
		
		// for yVec 
		double yVecTransInX = - uVecTransInY;
		double yVecTransInZ = bAngVeloc.x * Env.deltaT.getValue();	// arclength at 1 micron
		yVec.setVals(yVecTransInX, 1, yVecTransInZ);
		yVec.xToX(this);
		yVec.unitVec();
		
		initialize();
		
	}
	
	public double getDim () {
		return Env.myoMotorLength.getValue();
	}
	
	public double moveCoeff (int end, Pt3D linkUVec) {
		double cosBeta;
		if (end == 2) {
			cosBeta = Pt3D.Dot(uVec, linkUVec);
		} else {
			cosBeta = Pt3D.Dot(uVecR, linkUVec);
		}
		double beta = Math.acos(cosBeta);
		double cosAlpha = Math.sin(beta);
		double lSqrd = 1e-12*getDim()*getDim();
		double Cx = cosBeta*cosBeta/bTransGam.x;
		double Cperp = cosAlpha*cosAlpha/bTransGam.y;
		double Ctheta = lSqrd*cosAlpha*cosAlpha/(4*bRotGam.y);
		double moveC = Cx + Cperp + Ctheta;
		return moveC;
	}
	
	public static void meshAllMotors () {
		MyoMotor curMotor;
		for (int i=0;i<motorCt;i++) {
			curMotor = theMotors[i];
			Mesh.MYOHEADS_MESH.fillMotorMesh(curMotor);
		}
	}
	
	public static void motorFilMeshCollisions(){
		MyoMotor mot;
		FilSegment fil;
		for(int x=0;x<Mesh.nXBins;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				if (Mesh.MYOHEADS_MESH.timeStamps[x][y]==Env.counter && Mesh.FILSEG_MESH.timeStamps[x][y]==Env.counter) {
				
					for(int i=0;i<Mesh.MYOHEADS_MESH.activeCts[x][y];i++){
						int motorID=(int)Mesh.MYOHEADS_MESH.meshpoints[x][y][i];
						mot=theMotors[motorID];
						if (!mot.onFil) {  // don't even check for collisions if already attached
							for (int j=0;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
								int filID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
								fil=FilSegment.theFilSegments[filID];
								checkFilSegCollision(mot,fil);
							}
						}
					}
				}
			}
		}
	}
	
	public static void motorFilMeshCollisions(int xStart, int xStop){
		MyoMotor mot;
		FilSegment fil;
		for(int x=xStart;x<xStop;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				if (Mesh.MYOHEADS_MESH.timeStamps[x][y]==Mesh.lastWriteTime && Mesh.FILSEG_MESH.timeStamps[x][y]==Mesh.lastWriteTime) { // this time-step?
				
					for(int i=0;i<Mesh.MYOHEADS_MESH.activeCts[x][y];i++){
						int motorID=(int)Mesh.MYOHEADS_MESH.meshpoints[x][y][i];
						mot=theMotors[motorID];
						if (!mot.onFil) {  // don't even check for collisions if already attached
							for (int j=0;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
								int filID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
								fil=FilSegment.theFilSegments[filID];
								checkFilSegCollision(mot,fil);
							}
						}
					}
				}
			}
		}
	}
	
	
	public static void checkFilSegCollision (MyoMotor mot, FilSegment fil) {
		RetObj retO = mot.retObj;
		if (retO==null | mot.bindTip==null | fil.end1==null | fil.end2==null) { return; }
		if (Pt3D.Dot(mot.uVec,fil.uVec) < Env.myoMotorAlignWithFilTolerance.getValue()) { return; }  // orientation of motor constraint on binding to filament
		if (Pt3D.Dot(mot.myMyosin.myoRod.uVec,fil.uVec) < 0) { return; } // orientation of motor rod constraint on binding to filament
		if (fil.nodeAtEnd2) { return; }  // *** this line disallows binding to any filSeg that is formin bound
		if (fil.nodeAtEnd2 && mot.myMyosin.myNode != null) {
			if (fil.end2Node == mot.myMyosin.myNode) { return; }  // don't walk on filaments from own node
		}
		Thing.pointAndLineIntersectTest(mot.bindTip, fil.end1, fil.end2, retO);
		if (retO.collision && retO.conDist < Env.myoColTol.getValue()) {
			double arcOnFil = Pt3D.ptDist(fil.end1, retO.conPt1);
			mot.ontoFilament(fil,arcOnFil);
		}
	}
	
	public void updateMyoFilLinks () {
		tipLink.step();
	}
	
	public void releaseAllMyoFilLinks () {
		tipLink.release();
	}
	
	public int getState () {
		return nucleotideState;
	}
	
	public boolean isNONE() { return (nucleotideState == NONE); }
	
	public boolean isATP() { return (nucleotideState == ATP); }

	public boolean isADPPi () { return (nucleotideState == ADPPi); }
	
	public boolean isADP () { return (nucleotideState == ADP); }

	public boolean notATP() { return (nucleotideState != ATP); }
	
	public boolean notADPPi () { return (nucleotideState != ADPPi); }
	
	public boolean notADP () { return (nucleotideState != ADP); }
	
	
	public void ontoFilament (FilSegment seg, double arcOnSeg) {
		synchronized(attachSync) {
			if (onFil) { return; }
			if (bindTimer < Env.myoRebindTime.getValue()) { return; }  // don't bind if too soon after unbinding
			tipLink.setAttachment(seg, arcOnSeg);
			totalBindEvents++;
		}
	}
	
	public static void addMyoMotor (MyoMotor nuMotor) {
		theMotors[motorCt] = nuMotor;
		theMotors[motorCt].myMotorNumber = motorCt;
		motorCt++;
	}

	public void makeMyoFilLinks () {
		tipLink = new MyoFilLink(this,bindTip);
	}
		
	public static synchronized void cleanupMyoMotors () {
		MyoMotor curM;
		for (int i=0;i<motorCt;i++) {
			if (theMotors[i] == null) { break; } // reached end of theMotors array I guess
			curM = theMotors[i];
			if (curM.removeMe) { 
				MyoFilLink.removeMyoFilLink(theMotors[i].tipLink); // cleanup the MyoFilLink array, removing this one
				theMotors[i] = theMotors[motorCt-1];
				theMotors[i].myMotorNumber = i;
				//System.out.println ("Removed " + String.valueOf(curM) + " from Motor Array");
				theMotors[motorCt-1] = null;
				motorCt--;
			}
		}
	}
	
	public void remove() {
		removeMe = true;
		sepaku();
	}


}
