package boxOfActin;

public class MyoFilLink {
	static final int maxLinks = 8000000;
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

	// ---- Read-only stretch census (2026-07-02, capture-radius replicate task) ----
	// Gated behind BOA_STRETCH_CENSUS; default-off => byte-identical (no force/RNG touched,
	// pure bookkeeping). Reports, over the currently-bound cross-bridge population at output
	// cadence: anchor-spring extension (nm) = forceMag/myoSpring, |forceDotFil| & signed
	// forceDotFil per head (pN), and mean bound-episode dwell (ms) from completed bind->release
	// episodes. Mirrors v2's -stretchcensus (PHASE2_CAPTURE_RADIUS_FINDINGS STEP 3).
	static final boolean STRETCH_CENSUS;
	static {
		String s = System.getenv("BOA_STRETCH_CENSUS");
		STRETCH_CENSUS = (s != null && !s.isEmpty() && !s.equals("0") && !s.equalsIgnoreCase("false"));
	}
	int bindStep = -1;                 // Env.counter at the current bind; -1 when free
	static long censusDwellSteps = 0;  // sum of completed-episode lifetimes (in steps)
	static long censusDwellEpisodes = 0;

	// (Removed dead `stepSize` field — it had no consumers; the working stroke is
	// emergent from the cross-bridge spring + rebind geometry, not an explicit step.)
	// Cross-bridge stiffness is now Env.myoSpring (runtime-mutable); read via getValue().
	
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
	// GPU residency fix (2026-06-10): the cross-bridge force for a CPU-handled
	// (non-MyosinFixed) bound motor must use the motor head tip derived fresh
	// from the demand-synced coord+uVec, not the stale bindTip host mirror.
	// Reused per-call to avoid allocation in the per-bound-motor hot path.
	Pt3D freshMotorTip = new Pt3D();
	
	
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
		freshMotorTip = null;
	}
	
	// Phase 4.5 diag (2026-06-05): split-counter for updatePos() calls. Each
	// updatePos() reads mySeg.end1AsPt3D() => Thing.soaEnd1[] (frame-stale).
	// FROM_BIND fires on each fresh GPUMotorBinding.detectBindings()-driven
	// bind via ontoFilament => setAttachment. FROM_STEP fires on the gated
	// CPU step() path or under BOA_DIAG_FORCE_UPDATEPOS.
	public static long DIAG_UPDATEPOS_FROM_BIND_CT = 0;
	public static long DIAG_UPDATEPOS_FROM_STEP_CT = 0;
	public static long DIAG_VALIDATESEG_FIRE_CT    = 0;

	public void setAttachment (FilSegment seg, double pos) {
		mySeg = seg;
		posOnSeg = pos;
		if (STRETCH_CENSUS) bindStep = Env.counter;   // census: start of a bound episode
		if (Env.useGPU) DIAG_UPDATEPOS_FROM_BIND_CT++;
		updatePos();
		myMotor.onFil = true;
		// TEST FLAG myoHeadAxisBindSet (2026-07-01) — stereospecific bind pose (initialization, NOT a torque).
		// Set the head's full orientation ONCE at the bind instant so mhat is on the productive pole (+nhat,
		// STEP-0) and yVec = +shat, consistent with the perp hold + roll lock that then maintain it. The stroke
		// law reads myMotor.uVec/yVec directly, so writing the SoA here is sufficient; coord is untouched (no
		// positional yank — with center-bind the tip == coord, orientation-independent).
		if (Env.myoHeadAxisBindSet.isActive() && Env.myoHeadAxisBindSet.getValue() != 0.0) {
			Pt3D fhat = mySeg.uVecAsPt3D();
			// shat = nhat x fhat with nhat = (0,0,1) -> (-fy, fx, 0); mhat = +(nhat perp fhat), normalized.
			double sx = -fhat.y, sy = fhat.x, sz = 0.0;
			double sm = Math.sqrt(sx*sx + sy*sy + sz*sz);
			double ndf = fhat.z;                                   // nhat . fhat
			double mx = 0.0 - ndf*fhat.x, my = 0.0 - ndf*fhat.y, mz = 1.0 - ndf*fhat.z;
			double mm = Math.sqrt(mx*mx + my*my + mz*mz);
			if (sm > 1e-9 && mm > 1e-9) {                          // else filament ~parallel to bed normal: skip
				mx /= mm; my /= mm; mz /= mm;                      // mhat = +nhat (perp fhat), unit
				// Reorient the head ABOUT ITS BOUND TIP (the actin-binding site = cross-bridge spring anchor),
				// NOT its center: hold the tip fixed and swing the body around it, so the bind pose-set does
				// NOT jump the spring length (which with tip-bind would spike the catch-slip/break load and
				// shed the bind). tip = coord + halfMot*uVec (== freshMotorTip in addForces); after setting
				// uVec = mhat, restore coord = tip - halfMot*mhat so the tip is unchanged. centerBind -> halfMot
				// = 0 -> coord unchanged (graceful).
				// halfMot = the bind-point offset (Env.myoBindHeadOffset): reorient about the ACTUAL bound
				// point (tip/mid/rear per myoBindPoint), not always the tip, so no positional yank there either.
				final double halfMot = Env.myoBindHeadOffset();
				double tipX = myMotor.getCoordX() + halfMot*myMotor.getUVecX();
				double tipY = myMotor.getCoordY() + halfMot*myMotor.getUVecY();
				double tipZ = myMotor.getCoordZ() + halfMot*myMotor.getUVecZ();
				myMotor.setUVec(mx, my, mz);                       // mhat = +nhat
				myMotor.setYVec(sx/sm, sy/sm, sz/sm);              // yhead = +shat
				myMotor.setCoord(tipX - halfMot*mx, tipY - halfMot*my, tipZ - halfMot*mz);  // tip preserved
			}
		}
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
			if (DIAG_FORCE_UPDATEPOS) {
				if (Env.useGPU) DIAG_UPDATEPOS_FROM_STEP_CT++;
				updatePos();
			}
			if (!deviceMotor) {
				if (!DIAG_FORCE_UPDATEPOS) {
					if (Env.useGPU) DIAG_UPDATEPOS_FROM_STEP_CT++;
					updatePos();
				}
				addForces();
				alignUVecTorque();
				alignYVecTorque();
				if (Env.myoHeadAxisSignLock.isActive() && Env.myoHeadAxisSignLock.getValue() != 0.0) {
					alignUVecSignAxial();
				}
				ckRelease();
			}
		}
	}

	// Returns true if the device motor-force kernels are computing F8/F9/F10
	// for this MyoFilLink THIS step. Mirrors the gating in
	// GPUMoveThing.packMotorBinding(): any bound motor (dimer / minifilament
	// or MyosinFixed) whose seg is a GPU-handled FilSegment; DIAG_CPU_MOTOR
	// forces the entire path off. Cheap to recompute every step (it is the
	// same fields the pack already read).
	private boolean gpuMotorHandled () {
		if (!Env.useGPU) return false;
		if (GPUMoveThing.DIAG_CPU_MOTOR) return false;
		if (myMotor == null) return false;
		// 2026-06-11: admit non-MyosinFixed (dimer / minifilament) motors whose
		// bound seg is GPU-handled. The device kernels now compute their
		// cross-bridge F8/F9/F10 (packMotorBinding gives them a real
		// boundSegSlot when mySeg has a valid move slot), so the CPU pair must
		// no-op here exactly as for MyosinFixed. The MyosinFixed-only
		// short-circuit is removed; the null / removeMe / gpuHandled guards
		// below mirror the pack gate so the two paths agree on the device set
		// (force applied exactly once — never dropped, never doubled).
		if (mySeg == null) return false;
		if (mySeg.removeMe) return false;
		return mySeg.gpuHandled;
	}
	
	public void addForces () {
		if (isFree()) return;
		// GPU residency: the motor head is integrated on-device every step, but the
		// bindTip host mirror (== motorPt) is only refreshed at output cadence, so on
		// the GPU path it lags the true head by tens of nm. Reading it here inflated
		// the cross-bridge spring distance past the break-force threshold, so every
		// GPU dimer bind force-released on the next step (binds never held). Derive
		// the head tip fresh from the demand-synced coord+uVec (== freshEnd2AsPt3D),
		// and use it for the spring distance, direction, AND the torque application
		// point. On the CPU path coord/uVec are also fresh and this equals bindTip,
		// so CPU behaviour is unchanged.
		// TEST FLAG (2026-06-30) — myoCenterParallelBind: tether the head CENTER (coord) rather than the
		// tip (end2). attachPt is the filament point closest to the center, so at bind the spring length is
		// just the perpendicular gap (strain-free). Otherwise use the tip as before.
		final boolean centerBind = Env.myoCenterParallelBind.isActive() && Env.myoCenterParallelBind.getValue() != 0.0;
		// TEST FLAG (2026-07-02) — myoBindPoint: anchor the cross-bridge spring at the same bind point the
		// bind decision used (= coord + off*uVec, off from Env.myoBindHeadOffset()). Default (tip): off = +1/2*L
		// => freshMotorTip == end2, byte-identical. centerBind: off 0 => center. Keeping this identical to the
		// decision point means d~=0 at bind (no positional yank on a freshly-bound, non-stroking head).
		final double halfMot = Env.myoBindHeadOffset();
		freshMotorTip.setVals(myMotor.getCoordX() + halfMot*myMotor.getUVecX(),
		                      myMotor.getCoordY() + halfMot*myMotor.getUVecY(),
		                      myMotor.getCoordZ() + halfMot*myMotor.getUVecZ());
		double dist = Pt3D.ptDist(freshMotorTip, attachPt);
		if (dist < 0) { dist = 0; }
		// TEST FLAG myoCenterBindStandoff: nonzero spring REST LENGTH holds the center-bound head this far off
		// the filament binding point. forceMag<0 when dist<standoff pushes the head back out to the standoff.
		final double standoff = centerBind ? Env.myoCenterBindStandoff.getValue() : 0.0;
		forceMag = (dist - standoff)*Env.myoSpring.getValue();
		F.unitVec(attachPt,freshMotorTip);
		F.scale(forceMag);
		myMotor.incForceSum(F,freshMotorTip);

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
		// PROMOTION (2026-07-01): axial roll lock is part 2 of 3 of the DEFAULT fhat motor. Retarget the head's
		// roll reference from the segment's incidental yVec to shat = normalize(nhat x fhat) so the neck swing
		// plane is axial (see alignYVecTorqueAxial). Active by default; the test flag myoAxialSwingLock still
		// forces it (redundant). myoLegacyHeadSwing:true falls through to the stock yVec roll below (old F9).
		if (Env.defaultNeckStrokeMotorOn()
		    || (Env.myoAxialSwingLock.isActive() && Env.myoAxialSwingLock.getValue() != 0.0)) {
			alignYVecTorqueAxial();
			return;
		}
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

	// TEST FLAG myoAxialSwingLock (2026-06-30) — axial swing-plane lock.
	// Instead of aligning the head roll to the segment's incidental yVec, align it to the desired
	// swing-plane normal shat = normalize(nhat x fhat), where fhat = bound segment uVec (toward +/barbed
	// end) and nhat = bed normal (lab +Z). With shat = (0,0,1) x (fx,fy,fz) = (-fy, fx, 0), the head's yVec
	// is driven into +/-shat by a COMPLIANT restoring torque tau = k_az*(headYVec x shat) about that axis,
	// so the J1 neck swing sweeps in the axial (fhat-nhat) plane toward the + end rather than transverse.
	// Head-only reaction (shat is a lab/bed reference, not the segment's roll); k_az = myoJ1FracMoveTorq
	// (same compliant coeff as the perp/orientation torque — NOT a stiff pin). The 90deg polar hold in
	// alignUVecTorque is left unchanged.
	// TEST FLAG myoHeadAxisSignLock (2026-07-01) — HEAD-AXIS SIGN LOCK, the last free head DOF.
	// The 90deg polar hold (alignUVecTorque) pins only the head-actin ANGLE (mhat perp fhat); the roll lock
	// (alignYVecTorqueAxial) pins yVec to +shat. Together they force mhat = +/-nhat, but the SIGN is free
	// (~50/50). The head-frame swing law flips its axial tilt when mhat flips, so ~half the heads swing with
	// a reversed axial component -> partial cancellation -> the observed speed drop. This lock breaks the
	// degeneracy by driving the head uVec (mhat) to a definite +nhat (bed normal, +Z) with a COMPLIANT,
	// head-only torque (same coeff family as the roll/perp locks; NOT a stiff pin). Completes the
	// stereospecific head pose: with all three axes+signs fixed, head-frame swing == filament-referenced.
	public void alignUVecSignAxial () {
		Pt3D mhat = myMotor.uVecAsPt3D();
		double nx = 0.0, ny = 0.0, nz = 1.0;                 // +nhat = bed normal (lab +Z)
		// torque = mhat x nhat rotates mhat toward +nhat
		torsionVec.setVals(mhat.y*nz - mhat.z*ny, mhat.z*nx - mhat.x*nz, mhat.x*ny - mhat.y*nx);
		double dotVecs = mhat.x*nx + mhat.y*ny + mhat.z*nz;  // mhat.nhat
		if (dotVecs >  1.0) dotVecs =  1.0;
		if (dotVecs < -1.0) dotVecs = -1.0;
		double angTween = GPUMoveThing.accurateAcos(dotVecs)*180/Math.PI;   // full 0..180 to +nhat
		torsionVec.unitVec();                                // handles the near-antiparallel degeneracy w/ a kick
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*angTween
		                    /((1/myMotor.bRotGam.y + 1/mySeg.bRotGam.y)*Env.deltaT.getValue());
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			myMotor.incTorqueSum(torsionVec);                // head only — nhat is a lab/bed reference
		} else {
			System.out.println ("Crazy torque result in MyoFilLink.alignUVecSignAxial()");
		}
	}

	public void alignYVecTorqueAxial () {
		Pt3D fhat = mySeg.uVecAsPt3D();
		// shat = nhat x fhat with nhat = (0,0,1): the swing-plane normal. Aligning the head yVec to +/-shat
		// forces the neck swing into the axial (fhat-nhat) plane. NOTE: the SIGN of shat does not set the
		// sweep DIRECTION (barbed vs pointed) -- that is set by the lever's azimuth, not the head roll -- so
		// we just align to the nearer of +/-shat (avoids a 180deg roll fight).
		double sx = -fhat.y, sy = fhat.x, sz = 0.0;
		double sm = Math.sqrt(sx*sx + sy*sy + sz*sz);
		if (sm < 1e-9) return;                        // filament ~parallel to bed normal -> shat undefined
		sx /= sm; sy /= sm; sz /= sm;
		Pt3D yh = myMotor.yVecAsPt3D();
		torsionVec.setVals(yh.y*sz - yh.z*sy, yh.z*sx - yh.x*sz, yh.x*sy - yh.y*sx);
		double dotVecs = yh.x*sx + yh.y*sy + yh.z*sz;
		if (dotVecs >  1.0) dotVecs =  1.0;
		if (dotVecs < -1.0) dotVecs = -1.0;
		// STEREOSPECIFIC ROLL SIGN (myoNeckStrokeHeadFrame): lock to +shat SPECIFICALLY (the barbed-sweep
		// sign) instead of the nearer of +/-shat, completing the head frame so the head-frame stroke law is
		// equivalent to the fhat target. torsionVec (= yh x shat) already rotates yh toward +shat, and
		// angTween = acos(yh.shat) is the full 0..180deg angle to +shat (a head arriving near -shat pays the
		// real ~180deg twist). Otherwise (axial-lock alone) align to the nearer of +/-shat.
		boolean signFixed = Env.myoNeckStrokeHeadFrame.isActive() && Env.myoNeckStrokeHeadFrame.getValue() != 0.0;
		if (!signFixed && dotVecs < 0) { torsionVec.scale(-1); dotVecs = -dotVecs; }
		double angTween = GPUMoveThing.accurateAcos(dotVecs)*180/Math.PI;
		torsionVec.unitVec();
		// same magnitude form / coefficient as the existing roll torque, applied to the head only
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*angTween
		                    /((1/myMotor.bRotGam.x + 1/mySeg.bRotGam.x)*Env.deltaT.getValue());
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			myMotor.incTorqueSum(torsionVec);        // head only — no segment reaction
		} else {
			System.out.println ("Crazy torque result in MyoFilLink.alignYVecTorqueAxial()");
		}
	}
	
	public void updatePos () {
		if (lastPosUpdate != Env.counter) {
			//motorPt.copy(myMotor.bindTip);
			// freshEnd1AsPt3D (coord-½·len·uVec from the demand-synced pose) instead
			// of end1AsPt3D (stale soaEnd1 mirror) so the filament attach point tracks
			// current geometry on the GPU path. CPU-bit-identical (freshEnd1==end1).
			attachPt.add(mySeg.freshEnd1AsPt3D(),posOnSeg,mySeg.uVecAsPt3D());
			lastPosUpdate = Env.counter;
		}
	}
	
	
	public boolean isFree () {
		if (mySeg == null) { return true; }
		validateSeg ();
		return (mySeg == null);
	}
	
	public void validateSeg() {
		if (Env.useGPU) DIAG_VALIDATESEG_FIRE_CT++;
		if ((mySeg.end1AsPt3D() == null) | (mySeg.uVecAsPt3D() == null) | mySeg.removeMe) {
			release();
			return;
		}
	}
	public void release () {
		if (STRETCH_CENSUS && bindStep >= 0) {        // census: complete a bound episode
			censusDwellSteps += (Env.counter - bindStep);
			censusDwellEpisodes++;
			bindStep = -1;
		}
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

		boolean fired = (Thing.currentScratch().rng.nextDouble() < guoCatchSlipProb*Env.deltaT.getValue());
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
	
	// Read-only census over the currently-bound cross-bridge population. Iterates by direct
	// mySeg-null test (NOT isFree(), which can trigger validateSeg->release side effects), reads
	// per-link forceMag/forceDotFil already computed this step, and prints population means.
	// Dwell is the running mean of completed bind->release episode lifetimes. No force/RNG touched.
	public static void stretchCensus (int step, double simTime) {
		if (!STRETCH_CENSUS) return;
		int n = 0;
		double extSum = 0, absFdfSum = 0, sgnFdfSum = 0;
		final double spring = Env.myoSpring.getValue();
		for (int i = 0; i < myoFilLinkCt; i++) {
			MyoFilLink l = theMyoFilLinks[i];
			if (l == null || l.mySeg == null) continue;
			n++;
			extSum    += l.forceMag / spring;      // µm
			absFdfSum += Math.abs(l.forceDotFil);  // N
			sgnFdfSum += l.forceDotFil;            // N
		}
		double dwellMs = censusDwellEpisodes > 0
		        ? (double) censusDwellSteps / censusDwellEpisodes * Env.deltaT.getValue() * 1000.0
		        : 0.0;
		if (n > 0) {
			System.out.printf(
			    "[STRETCHCENSUS] step=%d t=%.4f n=%d ext_nm=%.4f absFdF_pN=%.4f sgnFdF_pN=%.4f dwell_ms=%.4f%n",
			    step, simTime, n, extSum / n * 1000.0, absFdfSum / n * 1e12, sgnFdfSum / n * 1e12, dwellMs);
		}
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
