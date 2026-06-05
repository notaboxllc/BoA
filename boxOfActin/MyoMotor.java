package boxOfActin;

public class MyoMotor extends Thing {
	static MyoMotor [] theMotors = new MyoMotor[500000];
	static int motorCt = 0;
	int myMotorNumber;

	// SoA arrays for GPU-ready motor-binding path
	// Step 1a: motor head position + bound-state flag
	// Step 1b: motor head orientation (uVecAsPt3D()) + rod orientation (myMyosin.myoRod.uVecAsPt3D()) — fine-check inputs
	static double[]  soaX     = new double[500000];
	static double[]  soaY     = new double[500000];
	static double[]  soaZ     = new double[500000];
	static boolean[] soaOnFil = new boolean[500000];
	static double[]  soaUX    = new double[500000];
	static double[]  soaUY    = new double[500000];
	static double[]  soaUZ    = new double[500000];
	static double[]  soaRodUX = new double[500000];
	static double[]  soaRodUY = new double[500000];
	static double[]  soaRodUZ = new double[500000];

	static void fillSoaArrays() {
		// Read per-motor pose from the canonical SoA pose arrays
		// (Thing.soaCoord / soaUVec) instead of chasing the per-motor Pt3D
		// references. bindTip is the motor's end2AsPt3D() — the kernel/grid wants the
		// motor head tip, not the centre, so we reconstruct end2AsPt3D() from coordAsPt3D()
		// and uVecAsPt3D() the same way MyoMotor.initialize() does:
		//     end2AsPt3D() = coordAsPt3D() + 0.5 * motorLength * uVecAsPt3D()
		// Cheaper than re-running initialize(); the value is needed only here.
		final float[] soaCoordArr = Thing.soaCoord;
		final float[] soaUVecArr  = Thing.soaUVec;
		final double  halfLen     = 0.5 * Env.myoMotorLength.getValue();
		for (int i = 0; i < motorCt; i++) {
			MyoMotor m = theMotors[i];
			final int b = m.myThingNumber * 3;
			final double cx = soaCoordArr[b];
			final double cy = soaCoordArr[b + 1];
			final double cz = soaCoordArr[b + 2];
			final double ux = soaUVecArr[b];
			final double uy = soaUVecArr[b + 1];
			final double uz = soaUVecArr[b + 2];
			soaX[i]     = cx + halfLen * ux;
			soaY[i]     = cy + halfLen * uy;
			soaZ[i]     = cz + halfLen * uz;
			soaOnFil[i] = m.onFil;
			soaUX[i]    = ux;
			soaUY[i]    = uy;
			soaUZ[i]    = uz;
			// MyoRod pose: read from the rod's canonical SoA slot.
			final int rb = m.myMyosin.myoRod.myThingNumber * 3;
			soaRodUX[i] = soaUVecArr[rb];
			soaRodUY[i] = soaUVecArr[rb + 1];
			soaRodUZ[i] = soaUVecArr[rb + 2];
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
	
	// end1AsPt3D() (free end) / end2AsPt3D() (attached to head) live on Thing now;
	// bridgeDerivedToPt3D writes them after every GPU step.
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
		bindTip = new Pt3D();
		initialize();   // refreshes bindTip from SoA end2
		addMyoMotor(this);
		makeMyoFilLinks();
	}

	public MyoMotor(Pt3D initCoord, Pt3D initUVec) {
		super(initCoord);

		setUVec(initUVec);
		calculateProperties();
		bindTip = new Pt3D();
		initialize();   // refreshes bindTip from SoA end2
		addMyoMotor(this);
		makeMyoFilLinks();
	}
	
	public void sepaku () {
		super.sepaku();
		myMyosin = null;
		bindTip = null;
	}
	
	public void set (Pt3D setCoord, Pt3D setUVec, double dim, byte nucState) {
		setCoord(setCoord);
		setUVec(setUVec);
		Env.myoMotorLength.setValue(dim);
		nucleotideState = nucState;
		// SoA bridge: caller often follows with initialize(); make sure SoA is fresh.
		pushPoseToSoa();
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
		bRotGam.x = 8*Math.PI*Env.aeta.getValue()*(radiusM*radiusM*radiusM);//4*Math.PI*Env.aeta.getValue()*radiusM*radiusM*headLengthM;	// drag for turning about x
		bRotGam.y = bRotGam.x;//(Math.PI*Env.aeta.getValue()*Math.pow(headLengthM,3))/(3*(denomLogTerm + aTurning));
		bRotGam.z = bRotGam.y;
		
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
		
	}
	
	// Phase 4.5 diag (2026-06-05): count MyoMotor.initialize() calls on -gpu.
	// On the GPU path the device kernel updates coord/uVec; CPU initialize() should
	// not run per-step for handled motors. If this counter > 0 in gliding, CPU
	// moveThing is still firing for some subset.
	public static long DIAG_MOTOR_INIT_CT = 0;

	public void initialize () {
		if (Env.useGPU) DIAG_MOTOR_INIT_CT++;
		pushLengthToSoa(getDim());
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
		// Refresh bindTip Pt3D snapshot — Mesh/MotorBindGrid3D read its x/y/z each
		// step to spatially bin the motor's binding tip (= end2).
		if (bindTip != null) {
			bindTip.x = getEnd2X(); bindTip.y = getEnd2Y(); bindTip.z = getEnd2Z();
		}
		// for collision detection
		xRange = Math.abs(getCoordX()-getEnd2X());
		yRange = Math.abs(getCoordY()-getEnd2Y());
		zRange = Math.abs(getCoordZ()-getEnd2Z());
	}
	
	public void step () {
		if (Env.myosinsOff) { return; }
		bindTimer += Env.deltaT.getValue();

		collCheckCt++;
		if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			//checkOuterBugCollision();		// these should add forces and torques to forceSum and torqueSum
			collCheckCt = 0;
		}

		long _spT = StepProfiler.t0();
		updateMyoFilLinks();
		StepProfiler.add(StepProfiler.F8_10_MYOMOTOR_TIPLINK, _spT);
	}
	
	public void checkOuterBugCollision () {
		theBox.amICollidingOuter(cE,end1AsPt3D(),radius);
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

		double dt = Env.deltaT.getValue();

		// first check that forceSum and torqueSum aren't wacky... exit method if they are
		if (!isForceSumFinite()) {
			talkln ("Crazy forceSum in " + this);
			setForceSumToRandForces();
		}
		if (!isTorqueSumFinite()) {
			talkln ("Crazy torqueSum in " + this);
			setTorqueSumToRandTorques();
		}

		// Work in coordinates aligned with the rod... transform forces and torques into body-fixed axis....
		int sBase = myThingNumber * 3;
		bForceSum.XToxFromFloats(this, Thing.soaForceSum, sBase);
		bTorqueSum.XToxFromFloats(this, Thing.soaTorqueSum, sBase);

		// add brownian force and torque... these are zero except at every chosen time-step
		if (!Env.brownianMyoMotionOff) {
			double myoBrownianAttn = Env.myoBrownianAttn.getValue();
			bForceSum.inc(myoBrownianAttn,randForces); //trans
			bTorqueSum.inc(myoBrownianAttn,randTorques); //rot
		}
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);	// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec

		// ** before progressing .... check that bVeloc and bAngVeloc are not NaN... exit if wacky
		if (!bVeloc.checkPt3D()) { talkln ("** problem with bVeloc for " + this); return; }
		if (!bAngVeloc.checkPt3D()) { talkln ("** problem with bAngVeloc for " + this); return; }

		// New Positions
		// the body-fixed angular velocities can just be transformed into fixed-frame velocities, and the coordAsPt3D() updated
		veloc.xToX(this, bVeloc);
		incCoord(dt,veloc);  // just position = velocity*time

		Pt3D scratch = new Pt3D();
		double uVecTransInZ = -bAngVeloc.y * dt;
		double uVecTransInY = bAngVeloc.z * dt;
		scratch.setVals(1, uVecTransInY, uVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setUVec(scratch);

		double yVecTransInX = -uVecTransInY;
		double yVecTransInZ = bAngVeloc.x * dt;
		scratch.setVals(yVecTransInX, 1, yVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setYVec(scratch);

		initialize();
	}
	
	public double getDim () {
		return Env.myoMotorLength.getValue();
	}
	
	public double moveCoeff (int end, Pt3D linkUVec) {
		double cosBeta;
		if (end == 2) {
			cosBeta = Pt3D.Dot(uVecAsPt3D(), linkUVec);
		} else {
			cosBeta = Pt3D.Dot(uVecRAsPt3D(), linkUVec);
		}
		if (cosBeta > 1.0) cosBeta = 1.0;
		if (cosBeta < -1.0) cosBeta = -1.0;
		double beta = Pt3D.fastAcos(cosBeta);
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
		for(int x=0;x<Mesh.nXBins;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				if (Mesh.MYOHEADS_MESH.timeStamps[x][y]==Env.counter && Mesh.FILSEG_MESH.timeStamps[x][y]==Env.counter) {
					for(int i=0;i<Mesh.MYOHEADS_MESH.activeCts[x][y];i++){
						int motorID=(int)Mesh.MYOHEADS_MESH.meshpoints[x][y][i];
						if (!soaOnFil[motorID]) {
							for (int j=0;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
								int filID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
								checkFilSegCollision(motorID, filID);
							}
						}
					}
				}
			}
		}
	}

	public static void motorFilMeshCollisions(int xStart, int xStop){
		for(int x=xStart;x<xStop;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				if (Mesh.MYOHEADS_MESH.timeStamps[x][y]==Mesh.lastWriteTime && Mesh.FILSEG_MESH.timeStamps[x][y]==Mesh.lastWriteTime) {
					for(int i=0;i<Mesh.MYOHEADS_MESH.activeCts[x][y];i++){
						int motorID=(int)Mesh.MYOHEADS_MESH.meshpoints[x][y][i];
						if (!soaOnFil[motorID]) {
							for (int j=0;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
								int filID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
								checkFilSegCollision(motorID, filID);
							}
						}
					}
				}
			}
		}
	}


	/**
	 * Step 1b: flat-array motor-binding fine check.
	 *
	 * Per-pair decision reads only static SoA arrays indexed by motor ID and FilSegment ID —
	 * no object dereferencing in the hot path. Translates directly to a TornadoVM kernel
	 * (replace double[] with FloatArray, drop the event call).
	 *
	 * The binding *event* (ontoFilament) is unchanged: when the decision says bind, the
	 * event fires inline by indexing into theMotors[] / theFilSegments[]. The decide-vs-event
	 * boundary is the line that becomes the kernel/CPU boundary in the GPU port.
	 */
	public static void checkFilSegCollision (int motorId, int filId) {
		// Motor-head orientation gate: dot(motUVec, filUVec) >= align tolerance
		final double fUx = FilSegment.soaUX[filId];
		final double fUy = FilSegment.soaUY[filId];
		final double fUz = FilSegment.soaUZ[filId];
		final double motDotFil = soaUX[motorId]*fUx + soaUY[motorId]*fUy + soaUZ[motorId]*fUz;
		if (motDotFil < Env.myoMotorAlignWithFilTolerance.getValue()) { return; }
		// Rod orientation gate: dot(rodUVec, filUVec) >= 0
		final double rodDotFil = soaRodUX[motorId]*fUx + soaRodUY[motorId]*fUy + soaRodUZ[motorId]*fUz;
		if (rodDotFil < 0) { return; }
		// Formin-bound filament excluded (dead `&& myNode` branch from prior code not ported — unreachable)
		if (FilSegment.soaNodeAtEnd2[filId]) { return; }

		// Point-line geometry — perpendicular drop from motor bindTip onto fil end1AsPt3D()→end2AsPt3D() segment
		final double e1x = FilSegment.soaEnd1X[filId];
		final double e1y = FilSegment.soaEnd1Y[filId];
		final double e1z = FilSegment.soaEnd1Z[filId];
		final double r1x = FilSegment.soaEnd2X[filId] - e1x;
		final double r1y = FilSegment.soaEnd2Y[filId] - e1y;
		final double r1z = FilSegment.soaEnd2Z[filId] - e1z;
		final double mx  = soaX[motorId];
		final double my  = soaY[motorId];
		final double mz  = soaZ[motorId];
		final double r2x = mx - e1x;
		final double r2y = my - e1y;
		final double r2z = mz - e1z;
		final double numer = r2x*r1x + r2y*r1y + r2z*r1z;
		final double denom = r1x*r1x + r1y*r1y + r1z*r1z;
		final double alpha = numer/denom;
		if (alpha < 0 || alpha > 1) { return; }
		final double cpx = e1x + alpha*r1x;
		final double cpy = e1y + alpha*r1y;
		final double cpz = e1z + alpha*r1z;
		final double dx = cpx - mx, dy = cpy - my, dz = cpz - mz;
		final double conDistSq = dx*dx + dy*dy + dz*dz;
		final double myoColTol = Env.myoColTol.getValue();
		if (conDistSq >= myoColTol*myoColTol) { return; }

		// Decision: bind. Fire the event (state-changing, synchronized inside ontoFilament).
		// arcOnFil = |conPt - end1AsPt3D()| = alpha * |end2AsPt3D() - end1AsPt3D()| = alpha * sqrt(denom)
		final double arcOnFil = alpha * Math.sqrt(denom);
		theMotors[motorId].ontoFilament(FilSegment.theFilSegments[filId], arcOnFil);
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
