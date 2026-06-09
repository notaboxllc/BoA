package boxOfActin;

public class MyoLever extends Thing {
	static double radius = 0.002; // microns
	Myosin myMyosin;
	
	// end1AsPt3D() (free end) / end2AsPt3D() (attached to head) live on Thing now;
	// bridgeDerivedToPt3D writes them after every GPU step.
	
	// empirical fit for viscous drags
	static final double aParallel = -0.20;  // approx to constant in damping for parallel motion
	static final double aOrthog = 0.84;		// ...for orthogonal motion
	static final double aTurning = -0.662; 	// ...for rotational motion
	
	// for collision detection
	double xRange,yRange,zRange;

	public MyoLever(Pt3D initCoord) {
		super(initCoord);

		calculateProperties();
		pushPoseToSoa();
		initialize();

	}

	public MyoLever(Pt3D initCoord,Pt3D initUVec) {
		super(initCoord);

		setUVec(initUVec);
		calculateProperties();
		pushPoseToSoa();
		initialize();

	}
	
	public void sepaku () {
		super.sepaku();
		myMyosin = null;
	}
	
	public void set (Pt3D setCoord, Pt3D setUVec, double dim) {
		setCoord(setCoord);
		setUVec(setUVec);
		Env.myoLeverLength.setValue(dim);
		pushPoseToSoa();
	}
	
	public void calculateProperties () {
		// define the constants for motion of this rod in viscous medium
		// Remember that the dimensions we've been using are in micrometers so...
		double tailLengthM = 1.0e-6*getDim(); // in meters
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
		pushLengthToSoa(getDim());
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
		xRange = Math.abs(getCoordX()-getEnd2X());
		yRange = Math.abs(getCoordY()-getEnd2Y());
		zRange = Math.abs(getCoordZ()-getEnd2Z());
	}
	
	public void step () {
		
	}
	
	public void moveThing () {
		if (Env.myosinsOff) { return; }
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

		// Work in coordinates aligned with the rod... transform forces and torques into body-fixed axis....
		int sBase = myThingNumber * 3;
		bForceSum.XToxFromFloats(this, Thing.soaForceSum, sBase);
		bTorqueSum.XToxFromFloats(this, Thing.soaTorqueSum, sBase);

		// add brownian force and torque... these are zero except at every chosen time-step
		if (!Env.brownianMyoMotionOff) {
			//bForceSum.inc(Env.myoBrownianAttn.getValue(),randForces); //trans
			//bTorqueSum.inc(Env.myoBrownianAttn.getLastValue(),randTorques); //rot
		}
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec
		
		// ** before progressing .... check that bVeloc and bAngVeloc are not NaN... exit if wacky
		if (!bVeloc.checkPt3D()) { talkln ("** problem with bVeloc for " + this); return; }
		if (!bAngVeloc.checkPt3D()) { talkln ("** problem with bAngVeloc for " + this); return; }
		
		// New Positions
		// the body-fixed angular velocities can just be transformed into fixed-frame velocities, and the coordAsPt3D() updated
		veloc.xToX(this, bVeloc);
		incCoord(Env.deltaT.getValue(),veloc);  // just position = velocity*time

		// Per-worker reused scratch (Pt3D SoA inc 0b sub-(a)) — each setVals
		// below is a full write, so no carryover from prior moveThing call.
		Pt3D scratch = currentScratch().moveScratch;
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
	
	public double getDim () {
		return Env.myoLeverLength.getValue();
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

	public void remove() {
		removeMe = true;
		sepaku();
	}

}
