package boxOfActin;

public class MyoFilLink {
	static final int maxLinks = 500000;
	static MyoFilLink [] theMyoFilLinks = new MyoFilLink [maxLinks];
	static int myoFilLinkCt = 0;
	int myMyoFilLinkNumber;
	static int onCt = 0;
	static int offCt = 0;
	static double timeOnSum = 0;
	static double timeOffSum = 0;
	double timeOn = 0;
	double timeOff = 0;
	static int numInBoundingBoxes=0;
	static int nodeHits=0;
	
	//counting release method
	static int myoBreakForceRelease = 0;
	static int normalRelease = 0;
	
	static double stepSize = Env.actinMonoRadius;	// the step-size for myosin
	static double myoSpring = 1e-9;  // N/�m
	
	MyoMotor myMotor = null;
	FilSegment mySeg = null;
	double posOnSeg = 0;			// the arclength position of a myosin from end1AsPt3D() of its segment
	Pt3D motorPt = new Pt3D();		// point of attachment on node
	Pt3D attachPt = new Pt3D();		// the fixed coordinate system position of the myosin attachment on filament
	int lastPosUpdate = 0;
	double forceMag = 0;
	double forceDotFil = 0;   // use this value in calculating release probabilities, it's signed appropriately but only co-linear component of force
	ValueTracker forceDotFilTrack = new ValueTracker(10); // or maybe use this running average for release prob
	// 2026-06-04 release-lag diagnostic: holds prior step's just-computed
	// Dot(F, seg.uVec). Read only when GPUMoveThing.DIAG_RELEASE_LAG is on; in
	// that case addForces feeds prevForceDotFil to both forceDotFilTrack and
	// link.forceDotFil, then overwrites prevForceDotFil with this step's value
	// for next step's release decision.
	double prevForceDotFil = 0;
	
	// re-used in force calcs
	Pt3D F = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RcrossF = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D linkUVec1 = new Pt3D(); 
	Pt3D linkUVec2 = new Pt3D();
	
	
	public MyoFilLink (MyoMotor mot, Pt3D pt) {
		myMotor = mot;
		motorPt = pt;
		addMyoFilLink(this);
	}
	
	public void sepaku () {
		myMotor = null;
		mySeg = null;
		motorPt = null;
		attachPt = null;
		forceDotFilTrack = null;
		F = null;
		R = null;
		RcrossF = null;
		torsionVec = null;
		linkUVec1 = null;
		linkUVec2 = null;
	}
	
	public void setAttachment (FilSegment seg, double pos) {
		mySeg = seg;
		posOnSeg = pos;
		updatePos();
		myMotor.onFil = true;
	}
	
	// Phase 4 prep (2026-06-04) — when set via env var
	// BOA_DIAG_FORCE_UPDATEPOS=1, force updatePos() to run for every bound
	// motor every step regardless of the GPU gate. Restores the pre-gate
	// behaviour for a gated-vs-ungated A/B without rebuilding. Default off.
	private static final boolean DIAG_FORCE_UPDATEPOS;
	static {
		String s = System.getenv("BOA_DIAG_FORCE_UPDATEPOS");
		DIAG_FORCE_UPDATEPOS = (s != null && !s.isEmpty()
		        && !s.equals("0") && !s.equalsIgnoreCase("false"));
	}

	public void step () {
		if (!isFree()) {
			// Phase 2 F8/F9/F10 (2026-06-03): when -gpu is on and
			// DIAG_CPU_MOTOR is off AND the GPU pack picked this motor up
			// (MyosinFixed with a GPU-handled bound seg), the device kernels
			// compute F8/F9/F10 and write forceMag/forceDotFil back via
			// bridgeMotorForceWriteback(). In that case the CPU pair below
			// would double-apply, so skip it. The handoff decision lives in
			// GPUMoveThing.packMotorBinding(): motor mj has boundSegSlot >= 0
			// iff the device path handles it this step.
			// 2026-06-04 release-read reconciliation: ckRelease is also
			// deferred for device-handled motors — it now runs from
			// bridgeMotorForceWriteback() immediately after the fresh
			// forceMag/forceDotFil are written, so step-N ckRelease consumes
			// step-N forces (matching the CPU arm). The CPU path below keeps
			// ckRelease here because addForces has just written fresh forces.
			boolean deviceMotor = gpuMotorHandled();
			// Phase 4 prep (2026-06-04): attachPt is read only by addForces
			// (which is gated off below on the device path). The pre-existing
			// per-bound-motor updatePos() call on the device path was dead CPU
			// work — a small per-motor pose read every step that produced an
			// unused field. Gate it on the device path. setAttachment() still
			// calls updatePos() directly at bind time, so attachPt is correct
			// for the (CPU-fallback or DIAG_CPU_MOTOR) path that does read it.
			// DIAG_FORCE_UPDATEPOS env var restores the pre-gate behaviour
			// (always call) for a gated-vs-ungated A/B.
			if (DIAG_FORCE_UPDATEPOS) updatePos();
			if (!deviceMotor) {
				if (!DIAG_FORCE_UPDATEPOS) updatePos();
				addForces();
				alignUVecTorque();
				alignYVecTorque();
				ckRelease();
			}
		}
	}

	// Returns true if the device motor-force kernels are computing F8/F9/F10
	// for this MyoFilLink THIS step. Mirrors the gating in
	// GPUMoveThing.packMotorBinding(): scope is MyosinFixed-only; the seg
	// must be a GPU-handled FilSegment; DIAG_CPU_MOTOR forces the entire path
	// off. Cheap to recompute every step (it is the same fields the pack
	// already read).
	private boolean gpuMotorHandled () {
		if (!Env.useGPU) return false;
		if (GPUMoveThing.DIAG_CPU_MOTOR) return false;
		if (myMotor == null) return false;
		Myosin myo = myMotor.myMyosin;
		if (!(myo instanceof MyosinFixed)) return false;
		if (mySeg == null) return false;
		if (mySeg.removeMe) return false;
		return mySeg.gpuHandled;
	}
	
	public void addForces () {
		if (isFree()) return;
		double dist = Pt3D.ptDist(motorPt, attachPt);
		if (dist < 0) { dist = 0; }
		forceMag = dist*myoSpring;
		F.unitVec(attachPt,motorPt);
		F.scale(forceMag);
		myMotor.incForceSum(F,motorPt);

		// calculate component of force toward barbed-end, signed magnitude needed for catch/slip Guo&Guilford (2006) release probability
		// Here, forceDotFil is positive for a force that will move myosin to plus-end of filament)
		double thisStepDot = Pt3D.Dot(F,mySeg.uVecAsPt3D());
		if (GPUMoveThing.DIAG_RELEASE_LAG) {
			// Lag mode: ckRelease in this step sees prior step's forceDotFil;
			// tracker gets prior step's value too. Mimics the device path's
			// structural lag (ckRelease reads what bridgeMotorForceWriteback
			// wrote in the PREVIOUS step's moveThings).
			forceDotFilTrack.registerValue(prevForceDotFil);
			forceDotFil = prevForceDotFil;
			prevForceDotFil = thisStepDot;
		} else {
			forceDotFilTrack.registerValue(thisStepDot);
			forceDotFil = thisStepDot;
			prevForceDotFil = thisStepDot;   // keep buffer in sync so a mid-run flip is benign
		}

		F.scale(-1);
		mySeg.incForceSum(F,attachPt);

	}
	
	/*public void addForces () {
		double strainDist = Pt3D.ptDist(motorPt,attachPt);
		if (strainDist < 0) { strainDist = 0; }
		linkUVec1.unitVec(attachPt,motorPt);
		double moveCoeffMotor = myMotor.moveCoeff(2,linkUVec1);
		double forceMag = (0.01*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeffMotor + 1/mySeg.bTransGam.y));

		// forces and torques applied to myosin motor domain
		F.scale(forceMag,linkUVec1);
		myMotor.incForceSum(F,motorPt);
		
		// forces and torques applied to filament seg
		F.scale(-1,F);
		mySeg.incForceSum(F,attachPt);
	}*/
	
	public void alignUVecTorque () {
		torsionVec.cross(mySeg.uVecAsPt3D(),myMotor.uVecAsPt3D());
		torsionVec.unitVec();

		double dotVecs = Pt3D.Dot(mySeg.uVecAsPt3D(),myMotor.uVecAsPt3D());
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		double angTween = GPUMoveThing.accurateAcos(dotVecs)*180/Math.PI;

		double angRelaxed = Myosin.uncockedMotor_ActinAngle;
		if (myMotor.isCocked()) { angRelaxed = Myosin.cockedMotor_ActinAngle; }
		double angD = angTween-angRelaxed;

		//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*angD/((1/myMotor.bRotGam.y + 1/mySeg.bRotGam.y)*Env.deltaT.getValue());
		
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			mySeg.incTorqueSum(torsionVec);
		
			torsionVec.scale(-1);
			myMotor.incTorqueSum(torsionVec);
		} else {
			System.out.println ("Crazy torque result in MyoFilLink.alignUVecTorque()");
		}

	}
	
	public void alignYVecTorque () {
		torsionVec.cross(mySeg.yVecAsPt3D(),myMotor.yVecAsPt3D());
		torsionVec.unitVec();
		
		double dotVecs = Pt3D.Dot(mySeg.yVecAsPt3D(),myMotor.yVecAsPt3D());
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		double angTween = GPUMoveThing.accurateAcos(dotVecs)*180/Math.PI;
			
		//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*angTween/((1/myMotor.bRotGam.x + 1/mySeg.bRotGam.x)*Env.deltaT.getValue());
		
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			mySeg.incTorqueSum(torsionVec);
		
			torsionVec.scale(-1);
			myMotor.incTorqueSum(torsionVec);
		} else {
			System.out.println ("Crazy torque result in MyoFilLink.alignYVecTorque()");
		}

	}
	
	public void updatePos () {
		if (lastPosUpdate != Env.counter) {
			//motorPt.copy(myMotor.bindTip);
			attachPt.add(mySeg.end1AsPt3D(),posOnSeg,mySeg.uVecAsPt3D());
			lastPosUpdate = Env.counter;
		}
	}
	
	
	public boolean isFree () {
		if (mySeg == null) { return true; }
		validateSeg ();
		return (mySeg == null);
	}
	
	public void validateSeg() {
		if ((mySeg.end1AsPt3D() == null) | (mySeg.uVecAsPt3D() == null) | mySeg.removeMe) {
			release();
			return;
		}
	}
	public void release () {
		mySeg = null;
		posOnSeg = 0;
		forceMag = 0;
		forceDotFil = 0;
		prevForceDotFil = 0;
		forceDotFilTrack.zero();
		myMotor.onFil = false;
		myMotor.bindTimer = 0;
	}
	
	public void ckRelease () {
		// Diagnostic snapshot (2026-06-04 — release-read divergence probe).
		// Captures the values the release roll about to read. Decision logic
		// below is unchanged.
		boolean diag = (GPUMoveThing.DIAG_RELEASE_READ_WRITER != null);
		double snapForceMag = 0, snapForceDot = 0, snapAvg = 0;
		int snapMotorId = -1, snapSegId = -1;
		if (diag) {
			snapForceMag = forceMag;
			snapForceDot = forceDotFil;
			snapAvg = (forceDotFilTrack != null) ? forceDotFilTrack.averageVal() : 0.0;
			snapMotorId = (myMotor != null) ? myMotor.thingInstanceId : -1;
			snapSegId = (mySeg != null) ? mySeg.thingInstanceId : -1;
		}
		int snapStep = diag ? Env.counter : 0;

		if (forceMag > Env.myosinBreakForce.getValue()*1e-12) { // combat stiffness and force insanity
			release();
			myoBreakForceRelease++;
			//System.out.println("**released myoFilLink because break force exceeded!");
			if (diag) GPUMoveThing.diagReleaseReadLog(snapStep, snapMotorId, snapSegId, snapForceMag, snapForceDot, snapAvg, 1);
			return;
		}

		if (myMotor.inRigor) {
			if (diag) GPUMoveThing.diagReleaseReadLog(snapStep, snapMotorId, snapSegId, snapForceMag, snapForceDot, snapAvg, 0);
			return; // don't release a filament normally if this flag is set, only if large force as above
		}

		double guoCatchTerm = Env.alphaCatch.getValue()*Math.exp(-forceDotFil*Env.xCatch.getValue()/(Env.Boltz*Env.tempK));
		double guoSlipTerm = Env.alphaSlip.getValue()*Math.exp(forceDotFil*Env.xSlip.getValue()/(Env.Boltz*Env.tempK));
		double guoCatchSlipProb =  Env.kOff.getValue()*(guoCatchTerm + guoSlipTerm);


		//System.out.println("Motor state is " + myMotor.getState() + " forceDotFil = " + forceDotFil + " ; guoCatchSlipProb = " + guoCatchSlipProb);
		//System.out.println("Motor state is " + myMotor.getState() + " ForceMag = " + forceMag+ " ; forceDotFil = " + forceDotFil + " ; ReleaseProb = " + releaseProb + " ; guoCatchSlipProb = " + guoCatchTerm + " ; " +  guoSlipTerm + " ; " + guoCatchSlipProb);

		boolean fired = (myMotor.myPRNG.nextDouble() < guoCatchSlipProb*Env.deltaT.getValue());
		if (fired) {
			release();
			normalRelease++;
		}
		if (diag) GPUMoveThing.diagReleaseReadLog(snapStep, snapMotorId, snapSegId, snapForceMag, snapForceDot, snapAvg, fired ? 1 : 0);
	}
	
	/*public void ckRelease () {
		if (forceMag > Env.myosinStallForce.getValue()*1e-12) {
			release();
			stallForceRelease++;
			//System.out.println("**Released myoFilLink because stall force exceeded!");
			return;
		}
		double releaseModX = 1.0;
		if (myMotor.notATP()) { releaseModX *= Env.notATPMyoReleaseMod.getValue(); } 
		double releaseProb = Env.myoFBRBase.getValue()*Math.exp(forceMag*1e12*releaseModX*Env.myoFBRExp.getValue())*Env.deltaT.getValue();
		//System.out.println("ForceMag = " + forceMag+ " ; ReleaseProb = " + releaseProb);
		if (myMotor.myPRNG.nextDouble() < releaseProb) { 
			//System.out.println("released myoFilLink normally");
			normalRelease++;
			release(); 
		}
	}*/
	
	/*public void ckRelease () {
		// biochem only... ie no force dependence
		double releaseRate;
		if (myMotor.isATP()) { releaseRate = 20000.0; } else { releaseRate = 0; }
		double releaseProb = releaseRate*Env.deltaT.getValue();
		if (myMotor.myPRNG.raw() < releaseProb) { release(); }
	}*/
	
	public static void resetReleaseCounters () {
		myoBreakForceRelease = 0;
		normalRelease = 0;
	}
	
	public static synchronized void addMyoFilLink (MyoFilLink newLink) {
		theMyoFilLinks[myoFilLinkCt] = newLink;
		theMyoFilLinks[myoFilLinkCt].myMyoFilLinkNumber = myoFilLinkCt;
		myoFilLinkCt ++;
	}
	
	public static synchronized void removeMyoFilLink (MyoFilLink rmMe) {
		theMyoFilLinks[rmMe.myMyoFilLinkNumber] = theMyoFilLinks[myoFilLinkCt-1];
		theMyoFilLinks[rmMe.myMyoFilLinkNumber].myMyoFilLinkNumber = rmMe.myMyoFilLinkNumber;
		theMyoFilLinks[myoFilLinkCt-1] = null;  // set pointer to null for garbage collection
		myoFilLinkCt --;
		rmMe.sepaku();
		rmMe = null;
	}
	
	public static void removeAll () {
		for (int i=0;i<myoFilLinkCt;i++) {
			if (theMyoFilLinks[i] != null) { 
				theMyoFilLinks[i].sepaku();
			}
		}
		myoFilLinkCt= 0;
	}
	
}
