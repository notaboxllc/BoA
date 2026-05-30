package boxOfActin;

public class MyoRod extends Thing {
	static double radius = 0.003; // microns
	Myosin myMyosin;
	
	Pt3D end1 = new Pt3D();	// the free-end	
	Pt3D end2 = new Pt3D();	// attached to head
	
	// empirical fit for viscous drags
	static final double aParallel = -0.20;  // approx to constant in damping for parallel motion
	static final double aOrthog = 0.84;		// ...for orthogonal motion
	static final double aTurning = -0.662; 	// ...for rotational motion
	
	// for collision detection
	double xRange,yRange,zRange;

	boolean rodInvisible = false;

	public MyoRod(Pt3D initCoord) {
		super(initCoord);

		calculateProperties();
		pushPoseToSoa();
		initialize();

	}

	public MyoRod(Pt3D initCoord, Pt3D initUVec) {
		super(initCoord);

		uVec.copy(initUVec);
		calculateProperties();
		pushPoseToSoa();
		initialize();

	}
	
	public void sepaku () {
		super.sepaku();
		myMyosin = null;
		end1 = null;
		end2 = null;
	}
	
	public void set (Pt3D setCoord, Pt3D setUVec, double dim, boolean invis) {
		coord.copy(setCoord);
		uVec.copy(setUVec);
		Env.myoRodLength.setValue(dim);
		rodInvisible = invis;
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
		
	}
	
	public void initialize () {
		// SoA bridge: pull canonical pose into Pt3D fields.
		loadPoseFromSoa();
		// this method assumes the unit x and y vectors have been set (though maybe not orthogonal), or are unchanged
		// determine z-unit vectors, then reset y-unit vector to ensure orthogonality with uVec
		zVec.cross(uVec, yVec);
		yVec.cross(zVec, uVec);
		// find the transformation matrices at this time step
		transMat ();
		// define opposite to uVec direction, used frequently
		uVecR.scale(-1,uVec);

		// re-find the end points of the rod to make sure they meet length criteria
		end1.add(coord, -getDim()/2, uVec);
		end2.add(coord, getDim()/2, uVec);

		// for collision detection
		xRange = Math.abs(coord.x-end2.x);
		yRange = Math.abs(coord.y-end2.y);
		zRange = Math.abs(coord.z-end2.z);
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
			bForceSum.inc(Env.myoBrownianAttn.getValue(),randForces); //trans
			bTorqueSum.inc(Env.myoBrownianAttn.getValue(),randTorques); //rot
		}
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
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

		pushPoseToSoa();   // canonical SoA flush
		initialize();

	}
	
	public double getDim() {
		return Env.myoRodLength.getValue();
	}
	
	public double moveCoeff (int end, Pt3D linkUVec) {
		double cosBeta;
		if (end == 2) {
			cosBeta = Pt3D.Dot(uVec, linkUVec);
		} else {
			cosBeta = Pt3D.Dot(uVecR, linkUVec);
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
