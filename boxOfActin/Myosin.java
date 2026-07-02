package boxOfActin;

public class Myosin {
	static Myosin [] theMyosins = new Myosin [8000000];
	static int myoCt = 0;
	int myMyoNumber = 0;
	// Inc2 (2026-06-09) — anchor SoA for the MyosinFixed rod-tail anchor coord.
	// Populated by MyosinFixed constructor at sim start; swap-compacted in
	// cleanupMyos so the per-myo slot follows myMyoNumber. Layout: 3 floats per
	// Myosin slot, flag is 1 for anchored (MyosinFixed) and 0 otherwise.
	// GPUMoveThing.packJointsRange reads these arrays contiguously to fill the
	// anchorPts / anchoredFlags FloatArrays — replaces the per-step
	// `instanceof MyosinFixed` cast + 3 Pt3D-gather reads.
	static float[] soaMyFixedPt = new float[theMyosins.length * 3];
	static byte[]  soaMyAnchored = new byte[theMyosins.length];
	static double uncockedLever_MotorAngle = 0; // degrees
	static double cockedLever_MotorAngle = 60; // degrees
	static double uncockedMotor_ActinAngle = 90; // degrees
	static double cockedMotor_ActinAngle = 120; // degrees
	MyoMotor myoMotor;
	MyoLever myoLever;
	MyoRod myoRod;
	ProteinNode myNode = null;
	boolean removeMe = false; // flag for taking this myosin out of the simulation

	
	// for multithreading
	static MyosinThreads myoThreads = new MyosinThreads();
	
	// re-used in force calcs
	Pt3D F = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RcrossF = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D linkUVec1 = new Pt3D(); 
	Pt3D linkUVec2 = new Pt3D();


	public Myosin () {
		myoMotor = new MyoMotor(new Pt3D());
		myoLever = new MyoLever(new Pt3D());
		myoRod = new MyoRod(new Pt3D());
		setLinks();
		addMyosin (this);
	}
	
	public Myosin (Pt3D motorCM) {
		Pt3D layDir = new Pt3D(-1,0,0);
		Pt3D leverCM = new Pt3D();
		Pt3D rodCM = new Pt3D();
		leverCM.add(motorCM,Env.myoMotorLength.getValue()/2+Env.myoLeverLength.getValue()/2,layDir);
		rodCM.add(leverCM,Env.myoLeverLength.getValue()/2 + Env.myoRodLength.getValue()/2,layDir);
		
		myoMotor = new MyoMotor(motorCM);
		myoLever = new MyoLever(leverCM);
		myoRod = new MyoRod(rodCM);
		setLinks();
		addMyosin (this);
	}
	
	public Myosin (Pt3D rodEnd1,Pt3D unitVec) {
		Pt3D rodCM = new Pt3D();
		Pt3D leverCM = new Pt3D();
		Pt3D motorCM = new Pt3D();
		rodCM.add(rodEnd1,Env.myoRodLength.getValue()/2,unitVec);
		leverCM.add(rodCM,Env.myoRodLength.getValue()/2+Env.myoLeverLength.getValue()/2,unitVec);
		motorCM.add(leverCM,Env.myoLeverLength.getValue()/2 + Env.myoMotorLength.getValue()/2,unitVec);
		
		myoMotor = new MyoMotor(motorCM,unitVec);
		myoLever = new MyoLever(leverCM,unitVec);
		myoRod = new MyoRod(rodCM,unitVec);
		setLinks();
		addMyosin (this);
	}
	
	static class MyosinThreads extends ThreadSet {
		MyosinThreads () {
			super (Env.numMyoThreads, "Myosin Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.myoJoints1Start:
					if (myoCt == 0) return;
					// Always distribute work across threads. On the CPU path,
					// execute() calls the full Myosin.jointConstraints() (four
					// inter-segment joint apply* methods, plus MyosinFixed's
					// applyRodFixedPtForce anchor spring via its override). On
					// the GPU path, execute() calls applyGPUDroppedForces()
					// which runs ONLY the per-Myosin forces the GPU jointsKernel
					// does not replicate — currently just MyosinFixed's
					// rod-tail anchor spring. The four inter-segment joints
					// are computed on device and would double-apply if also
					// dispatched here. See JOURNAL 2026-06-01 "Fix missing
					// rod-tail anchor force on GPU path".
					// MyosinDimer.myoDimerThreads keeps its own full CPU
					// dispatch (cross-Myosin coupling — not in jointsKernel).
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*myoCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.myoJoints1Stop:
					if (myoCt == 0) return;
					gather(); break;
			}
		}

		public void execute (int threadId) {
			switch (jobId) {
				case Env.myoJoints1Start:
					boolean gpuPath = Env.useGPU && !GPUMoveThing.DIAG_CPU_JOINTS;
					if (gpuPath) {
						// Phase 1 (2026-06-02): the MyosinFixed rod-tail anchor
						// spring is now applied on device by the joints kernel,
						// so applyGPUDroppedForces() — whose only contribution
						// is that anchor — must NOT also run here or it would
						// double-apply. DIAG_CPU_ANCHOR=true flips this back:
						// the device kernel zeros its anchor write (anchoredFlags
						// is forced 0 in packJointsRange) and the CPU pass runs.
						// Any future override that adds a NON-anchor dropped
						// force will need its own gating — see force-coverage
						// audit in JOURNAL "Phase 1 — anchor spring".
						if (GPUMoveThing.DIAG_CPU_ANCHOR) {
							for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
								theMyosins[i].applyGPUDroppedForces();
							}
						}
					} else {
						for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
							theMyosins[i].jointConstraints();
						}
					}
					break;
			}
		}
	}
	
	public void sepaku() {
		myoMotor = null;
		myoLever = null;
		F = null;
		R = null;
		RcrossF = null;
		torsionVec = null;
		linkUVec1 = null;
		linkUVec2 = null;
	}
	
	public void setMotor (Pt3D setCoord, Pt3D setUVec, double setDim, byte setState) {
		myoMotor.set(setCoord, setUVec, setDim, setState);
		myoMotor.initialize();
	}
	
	public void setLever (Pt3D setCoord, Pt3D setUVec, double setDim) {
		myoLever.set(setCoord, setUVec, setDim);
		myoLever.initialize();
	}
	
	public void setRod (Pt3D setCoord, Pt3D setUVec, double setDim, boolean invis) {
		myoRod.set(setCoord, setUVec, setDim, invis);
		myoRod.initialize();
	}
	
	public void setLinks () {
		myoMotor.myMyosin = this;
		myoLever.myMyosin = this;
		myoRod.myMyosin = this;
	}
	
	public void setRodInvisible (boolean invis) {
		myoRod.rodInvisible = invis;
	}
	
	public void setInRigor() {
		myoMotor.inRigor = true;
	}
	
	public void applyLeverMotorJointForce () {
		double dt = Env.deltaT.getValue();
		double myoJ1FracR = Env.myoJ1FracR.getValue();
		double strainDist = Pt3D.ptDist(myoLever.end2AsPt3D(), myoMotor.end1AsPt3D());
		linkUVec1.unitVec(myoLever.end2AsPt3D(),myoMotor.end1AsPt3D());
		linkUVec2.scale(-1,linkUVec1);
		double moveCoeffHead = myoMotor.moveCoeff(1,linkUVec1);
		double moveCoeffTail = myoLever.moveCoeff(2,linkUVec2);
		double forceMag = (Env.myoJ1FracMove.getValue()*1.0e-6*strainDist)/(dt*(moveCoeffHead + moveCoeffTail));
		//double forceMag = (1.0e-6*strainDist*1e-8);

		// forces and torques applied to myosin motor domain
		F.scale(forceMag,linkUVec1);
		if (!DIAG_DRY_RUN) myoMotor.incForceSum(F);
		diagAddMotorF(F.x, F.y, F.z);
		R.scale(0.5e-6*Env.myoMotorLength.getValue()*myoJ1FracR,myoMotor.uVecRAsPt3D());
		RcrossF.cross(R,F);
		if (!DIAG_DRY_RUN) myoMotor.incTorqueSum(RcrossF);
		diagAddMotorT(RcrossF.x, RcrossF.y, RcrossF.z);

		// forces and torques applied to myosin lever arm
		F.scale(-1,F);
		if (!DIAG_DRY_RUN) myoLever.incForceSum(F);
		diagAddLeverF(F.x, F.y, F.z);
		R.scale(0.5e-6*Env.myoLeverLength.getValue()*myoJ1FracR,myoLever.uVecAsPt3D());
		RcrossF.cross(R,F);
		if (!DIAG_DRY_RUN) myoLever.incTorqueSum(RcrossF);
		diagAddLeverT(RcrossF.x, RcrossF.y, RcrossF.z);

	}

	public void applyLeverMotorJointTorque () {
		// TEST FLAG myoNeckStrokeHeadFrame: HEAD-FRAME stroke (no filament reference; see method). Takes
		// precedence over the polarity law when both are on.
		if (Env.myoNeckStrokeHeadFrame.isActive() && Env.myoNeckStrokeHeadFrame.getValue() != 0.0
		    && myoMotor.onFil) {
			applyLeverMotorJointTorqueHeadFrame();
			return;
		}
		// PROMOTION (2026-07-01): the fhat-directed neck stroke is part 3 of 3 of the DEFAULT motor. When the
		// head is bound, drive the neck stroke toward a polarity-derived target so the rear endpoint ALWAYS
		// sweeps toward the barbed end (see below). Active by default; the test flag myoNeckStrokePolarity
		// still forces it (redundant). myoLegacyHeadSwing:true falls through to the stock lever-motor
		// relaxation below (old F9). The head-frame branch above still takes precedence when its flag is on.
		if ((Env.defaultNeckStrokeMotorOn()
		     || (Env.myoNeckStrokePolarity.isActive() && Env.myoNeckStrokePolarity.getValue() != 0.0))
		    && myoMotor.onFil && myoMotor.tipLink != null && myoMotor.tipLink.mySeg != null) {
			applyLeverMotorJointTorquePolarity();
			return;
		}
		torsionVec.cross(myoLever.uVecAsPt3D(),myoMotor.uVecAsPt3D());
		// Float32 GPU uVecAsPt3D() updates occasionally produce NaN components (orientation
		// drift past representable precision). The original code caught NaN at the
		// bottom via checkPt3D and printed "Crazy torque" once per occurrence —
		// at gliding-assay scale that is ~100k log lines per seed. Early-return on
		// NaN cross-product matches the original skip-torque behaviour without the
		// log spam. The legitimate near-parallel finite case is left untouched:
		// unitVec normalises an ill-conditioned direction, the relaxed-angle
		// restoring torque is applied (random direction acts as an unsticking kick).
		// See JOURNAL 2026-05-29 iter2b §D and iter2b-polish entry.
		if (Double.isNaN(torsionVec.x)) return;
		torsionVec.unitVec();
		
		double dotVecs = Pt3D.Dot(myoLever.uVecAsPt3D(),myoMotor.uVecAsPt3D());
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;

		double angRelaxed = uncockedLever_MotorAngle;
		if (myoMotor.isCocked()) { angRelaxed = cockedLever_MotorAngle; }
		double angD = angTween-angRelaxed;
			
		//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*angD/((1/myoMotor.bRotGam.y + 1/myoLever.bRotGam.y)*Env.deltaT.getValue());
		double maxMag = Env.myosinStallForce.getValue()*0.5*myoMotor.getDim()*1e-18; //**** check this for units!!!  missing conversion of force and lever arm to proper units? 1e-18 is 1e-12 (pN to N) * 1e-6 (microns to meters)
		torsionMag = Math.min(torsionMag,maxMag);
		
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			if (!DIAG_DRY_RUN) myoLever.incTorqueSum(torsionVec);
			diagAddLeverT(torsionVec.x, torsionVec.y, torsionVec.z);

			torsionVec.scale(-1);
			if (!DIAG_DRY_RUN) myoMotor.incTorqueSum(torsionVec);
			diagAddMotorT(torsionVec.x, torsionVec.y, torsionVec.z);
		} else {
			System.out.println ("Crazy torque result in Myosin.applyLeverMotorJointTorque()");
		}

	}

	// TEST FLAG myoNeckStrokePolarity (2026-07-01) — well-defined neck-stroke DIRECTION.
	// The stock torque above relaxes only the scalar lever-motor angle about the (lever x motor) axis, so
	// the swing azimuth — and thus whether the rear endpoint moves barbed- or pointed-ward — is
	// under-determined (set by incidental initial azimuth). Here we instead align the lever to a DEFINITE
	// target direction built from the bound filament polarity:
	//   fhat = bound seg uVec (pointed->barbed);  mhat = head uVec;  theta = rest lever-motor angle
	//   that  = -normalize( fhat - (fhat.mhat) mhat )     (the -fhat direction, projected perp to mhat)
	//   uTarget = cos(theta)*mhat + sin(theta)*that
	// The rear (-uTarget) then has rear.fhat = +sin(theta)*|f_perp| > 0 for a head held ~perpendicular to
	// the filament, i.e. the rear ALWAYS sweeps toward the barbed (+) end, regardless of starting azimuth.
	// Compliant alignment torque (same coeff/drag as the stock stroke), applied to the lever only (the
	// target references the external filament, not an internal body pair).
	public void applyLeverMotorJointTorquePolarity () {
		Pt3D fhat = myoMotor.tipLink.mySeg.uVecAsPt3D();
		Pt3D mhat = myoMotor.uVecAsPt3D();
		double fm = Pt3D.Dot(fhat, mhat);
		double tx = -(fhat.x - fm*mhat.x), ty = -(fhat.y - fm*mhat.y), tz = -(fhat.z - fm*mhat.z);
		double tm = Math.sqrt(tx*tx + ty*ty + tz*tz);
		if (tm < 1e-9) return;                          // head ~parallel to filament -> axial plane undefined
		tx /= tm; ty /= tm; tz /= tm;
		double angRelaxed = uncockedLever_MotorAngle;
		if (myoMotor.isCocked()) { angRelaxed = cockedLever_MotorAngle; }
		double c = Math.cos(Math.toRadians(angRelaxed)), s = Math.sin(Math.toRadians(angRelaxed));
		double ux = c*mhat.x + s*tx, uy = c*mhat.y + s*ty, uz = c*mhat.z + s*tz;
		double um = Math.sqrt(ux*ux + uy*uy + uz*uz); ux /= um; uy /= um; uz /= um;   // uTarget (unit)
		Pt3D lev = myoLever.uVecAsPt3D();
		// torque = lever x uTarget rotates the lever toward uTarget (shortest path)
		torsionVec.setVals(lev.y*uz - lev.z*uy, lev.z*ux - lev.x*uz, lev.x*uy - lev.y*ux);
		if (Double.isNaN(torsionVec.x)) return;
		double dotLU = lev.x*ux + lev.y*uy + lev.z*uz;
		if (dotLU > 1.0) dotLU = 1.0; if (dotLU < -1.0) dotLU = -1.0;
		double ang = Pt3D.fastAcos(dotLU)*180/Math.PI;
		torsionVec.unitVec();
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*ang
		                    /((1/myoMotor.bRotGam.y + 1/myoLever.bRotGam.y)*Env.deltaT.getValue());
		double maxMag = Env.myosinStallForce.getValue()*0.5*myoMotor.getDim()*1e-18;
		torsionMag = Math.min(torsionMag, maxMag);
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			if (!DIAG_DRY_RUN) myoLever.incTorqueSum(torsionVec);   // lever only
		}
	}

	// TEST FLAG myoNeckStrokeHeadFrame (2026-07-01) — HEAD-FRAME neck stroke (motor-contained).
	// The neck swings relative to the HEAD's own frame, about the head hinge axis yHead:
	//   uTarget = cos(theta)*uHead - sin(theta)*(yHead x uHead)
	// There is NO filament axis in this law — actin polarity enters ONLY through the head's bound pose
	// (its uVec, from the 90deg polar hold, and its yVec, sign-locked to +shat by alignYVecTorqueAxial when
	// this flag is on). This equals the myoNeckStrokePolarity fhat-target iff yHead = +shat, but it inherits
	// the head's real thermal orientation noise (which the fhat reference cleanly ignores). Compliant
	// alignment torque, same coeff/drag as the stock stroke, applied to the lever only.
	public void applyLeverMotorJointTorqueHeadFrame () {
		Pt3D mhat  = myoMotor.uVecAsPt3D();
		Pt3D yhead = myoMotor.yVecAsPt3D();
		// hinge axis h = yHead x uHead (the head's third frame axis); the neck rotates about yHead in the
		// plane spanned by uHead and h.
		double hx = yhead.y*mhat.z - yhead.z*mhat.y;
		double hy = yhead.z*mhat.x - yhead.x*mhat.z;
		double hz = yhead.x*mhat.y - yhead.y*mhat.x;
		double angRelaxed = uncockedLever_MotorAngle;
		if (myoMotor.isCocked()) { angRelaxed = cockedLever_MotorAngle; }
		double c = Math.cos(Math.toRadians(angRelaxed)), s = Math.sin(Math.toRadians(angRelaxed));
		double ux = c*mhat.x - s*hx, uy = c*mhat.y - s*hy, uz = c*mhat.z - s*hz;   // uTarget
		double um = Math.sqrt(ux*ux + uy*uy + uz*uz);
		if (um < 1e-12) return;
		ux /= um; uy /= um; uz /= um;
		Pt3D lev = myoLever.uVecAsPt3D();
		torsionVec.setVals(lev.y*uz - lev.z*uy, lev.z*ux - lev.x*uz, lev.x*uy - lev.y*ux);   // lever x uTarget
		if (Double.isNaN(torsionVec.x)) return;
		double dotLU = lev.x*ux + lev.y*uy + lev.z*uz;
		if (dotLU > 1.0) dotLU = 1.0; if (dotLU < -1.0) dotLU = -1.0;
		double ang = Pt3D.fastAcos(dotLU)*180/Math.PI;
		torsionVec.unitVec();
		double torsionMag = Env.myoJ1FracMoveTorq.getValue()*(Math.PI/180)*ang
		                    /((1/myoMotor.bRotGam.y + 1/myoLever.bRotGam.y)*Env.deltaT.getValue());
		double maxMag = Env.myosinStallForce.getValue()*0.5*myoMotor.getDim()*1e-18;
		torsionMag = Math.min(torsionMag, maxMag);
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			if (!DIAG_DRY_RUN) myoLever.incTorqueSum(torsionVec);   // lever only
		}
	}

	public void applyRodLeverJointForce () {
		double dt = Env.deltaT.getValue();
		double myoJ2FracR = Env.myoJ2FracR.getValue();
		double strainDist = Pt3D.ptDist(myoRod.end2AsPt3D(), myoLever.end1AsPt3D());
		linkUVec1.unitVec(myoRod.end2AsPt3D(),myoLever.end1AsPt3D());
		linkUVec2.scale(-1,linkUVec1);
		double moveC1 = myoLever.moveCoeff(1,linkUVec1);
		double moveC2 = myoRod.moveCoeff(2,linkUVec2);
		double forceMag = (Env.myoJ2FracMove.getValue()*1.0e-6*strainDist)/(dt*(moveC1 + moveC2));
		//double forceMag = (1.0e-6*strainDist*1e-8);

		// forces and torques applied to myosin lever arm
		F.scale(forceMag,linkUVec1);
		if (!DIAG_DRY_RUN) myoLever.incForceSum(F);
		diagAddLeverF(F.x, F.y, F.z);
		R.scale(0.5e-6*Env.myoLeverLength.getValue()*myoJ2FracR,myoLever.uVecRAsPt3D());
		RcrossF.cross(R,F);
		if (!DIAG_DRY_RUN) myoLever.incTorqueSum(RcrossF);
		diagAddLeverT(RcrossF.x, RcrossF.y, RcrossF.z);

		// forces and torques applied to myosin rod
		F.scale(-1,F);
		if (!DIAG_DRY_RUN) myoRod.incForceSum(F);
		diagAddRodF(F.x, F.y, F.z);
		R.scale(0.5e-6*Env.myoRodLength.getValue()*myoJ2FracR,myoRod.uVecAsPt3D());
		RcrossF.cross(R,F);
		if (!DIAG_DRY_RUN) myoRod.incTorqueSum(RcrossF);
		diagAddRodT(RcrossF.x, RcrossF.y, RcrossF.z);

	}
	
	public void applyRodLeverJointTorque () {
		torsionVec.cross(myoRod.uVecAsPt3D(),myoLever.uVecAsPt3D());
		// See applyLeverMotorJointTorque — same float32 NaN guard, suppresses log spam.
		if (Double.isNaN(torsionVec.x)) return;
		torsionVec.unitVec();
		
		double dotVecs = Pt3D.Dot(myoRod.uVecAsPt3D(),myoLever.uVecAsPt3D());
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;

		double angRelaxed = 96.0;   // degrees; assembled myosin's natural rod-lever equilibrium (CPU value 1.676 rad)
		double angD = angTween-angRelaxed;

		//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
		double torsionMag = Env.myoJ2FracMoveTorq.getValue()*(Math.PI/180)*angD/((1/myoLever.bRotGam.y + 1/myoRod.bRotGam.y)*Env.deltaT.getValue());
		
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			if (!DIAG_DRY_RUN) myoRod.incTorqueSum(torsionVec);
			diagAddRodT(torsionVec.x, torsionVec.y, torsionVec.z);

			torsionVec.scale(-1);
			if (!DIAG_DRY_RUN) myoLever.incTorqueSum(torsionVec);
			diagAddLeverT(torsionVec.x, torsionVec.y, torsionVec.z);
		} else {
			System.out.println ("Crazy torque result in Myosin.applyRodLeverJointTorque()");
		}

	}
	
	public static void allJointContraints() {
		for (int i=0;i<myoCt;i++) {
			theMyosins[i].jointConstraints();
		}
	}

	// GPU-path reduced pass. The GPU jointsKernel replicates the four
	// inter-segment apply* methods inside jointConstraints() but NOT any
	// per-subclass extras (e.g. MyosinFixed's rod-tail anchor spring).
	// MyosinThreads.execute() calls this instead of jointConstraints()
	// when (useGPU && !DIAG_CPU_JOINTS) so the dropped forces still apply
	// on the CPU side and land in soaForceSum (which the move kernel reads
	// as cpuForceSum + jointForceSum, with the joints kernel writing to a
	// separate delta buffer). Base implementation is a no-op for plain
	// Myosin; MyosinFixed overrides to apply applyRodFixedPtForce().
	public void applyGPUDroppedForces() {}

	// DIAG_DUMP: per-Myosin accumulators populated during the apply* methods
	// when the diagnostic step matches. Read out at the end of jointConstraints.
	// These mirror the per-Myosin totals the GPU jointsKernel writes to
	// jointForceSum/jointTorqueSum.
	//
	// DIAG_DRY_RUN (2026-05-31, delta-buffer transport diag): when true, the
	// apply* methods compute joint forces and populate the diag accumulators
	// but SKIP the incForceSum/incTorqueSum side effects on Motor/Lever/Rod.
	// Used by GPUMoveThing.moveThings() at the dump step to compare CPU joints
	// (same pose as GPU) against the GPU delta buffer without corrupting Thing
	// force/torque state. Default off — no production impact.
	public static boolean DIAG_DRY_RUN = false;
	private double diagMotorFx, diagMotorFy, diagMotorFz;
	private double diagLeverFx, diagLeverFy, diagLeverFz;
	private double diagRodFx, diagRodFy, diagRodFz;
	private double diagMotorTx, diagMotorTy, diagMotorTz;
	private double diagLeverTx, diagLeverTy, diagLeverTz;
	private double diagRodTx, diagRodTy, diagRodTz;

	private void diagAddMotorF(double x, double y, double z) { diagMotorFx += x; diagMotorFy += y; diagMotorFz += z; }
	private void diagAddLeverF(double x, double y, double z) { diagLeverFx += x; diagLeverFy += y; diagLeverFz += z; }
	private void diagAddRodF  (double x, double y, double z) { diagRodFx   += x; diagRodFy   += y; diagRodFz   += z; }
	private void diagAddMotorT(double x, double y, double z) { diagMotorTx += x; diagMotorTy += y; diagMotorTz += z; }
	private void diagAddLeverT(double x, double y, double z) { diagLeverTx += x; diagLeverTy += y; diagLeverTz += z; }
	private void diagAddRodT  (double x, double y, double z) { diagRodTx   += x; diagRodTy   += y; diagRodTz   += z; }

	public void jointConstraints() {
		// Always reset diag accumulators so they don't grow unboundedly across
		// steps. Cost is 18 double writes per Myosin per step.
		diagMotorFx = diagMotorFy = diagMotorFz = 0;
		diagLeverFx = diagLeverFy = diagLeverFz = 0;
		diagRodFx   = diagRodFy   = diagRodFz   = 0;
		diagMotorTx = diagMotorTy = diagMotorTz = 0;
		diagLeverTx = diagLeverTy = diagLeverTz = 0;
		diagRodTx   = diagRodTy   = diagRodTz   = 0;

		applyLeverMotorJointForce();
		applyLeverMotorJointTorque();

		applyRodLeverJointForce();
		applyRodLeverJointTorque();

		boolean diagOn = GPUMoveThing.DIAG_DUMP_JOINTS_STEP >= 0
		              && GPUMoveThing.getStepCounter() == GPUMoveThing.DIAG_DUMP_JOINTS_STEP
		              && myMyoNumber < GPUMoveThing.DIAG_DUMP_MYO_LIMIT;
		if (diagOn) {
			System.err.printf("[DIAG_CPU_JOINT step=%d myoIdx=%d] rodF=(%.6e,%.6e,%.6e) leverF=(%.6e,%.6e,%.6e) motorF=(%.6e,%.6e,%.6e)%n",
				GPUMoveThing.getStepCounter(), myMyoNumber,
				diagRodFx, diagRodFy, diagRodFz,
				diagLeverFx, diagLeverFy, diagLeverFz,
				diagMotorFx, diagMotorFy, diagMotorFz);
			System.err.printf("[DIAG_CPU_JOINT step=%d myoIdx=%d] rodT=(%.6e,%.6e,%.6e) leverT=(%.6e,%.6e,%.6e) motorT=(%.6e,%.6e,%.6e)%n",
				GPUMoveThing.getStepCounter(), myMyoNumber,
				diagRodTx, diagRodTy, diagRodTz,
				diagLeverTx, diagLeverTy, diagLeverTz,
				diagMotorTx, diagMotorTy, diagMotorTz);
		}
	}
	
	public void setOwnerNode (ProteinNode node) {
		myNode = node;
	}
	
	public static void makeTstMyosin () {
		new Myosin();
	}
	
	public static void addMyosin (Myosin nuMyo) {
		theMyosins[myoCt] = nuMyo;
		nuMyo.myMyoNumber = myoCt;
		myoCt++;
	}
	
	public static synchronized void cleanupMyos () {
		Myosin curM;
		for (int i=0;i<myoCt;i++) {
			if (theMyosins[i] == null) { break; } // reached end of theMyosins array I guess
			curM = theMyosins[i];
			if (curM.removeMe) {
				theMyosins[i] = theMyosins[myoCt-1];
				theMyosins[i].myMyoNumber = i;
				theMyosins[myoCt-1] = null;
				// Inc2 — drag the anchor SoA along with the surviving Myosin.
				// myMyoNumber was just reassigned to i; the SoA slot must
				// follow so packJointsRange's myMyoNumber lookup stays valid.
				int dst = i * 3, src = (myoCt - 1) * 3;
				soaMyFixedPt[dst]     = soaMyFixedPt[src];
				soaMyFixedPt[dst + 1] = soaMyFixedPt[src + 1];
				soaMyFixedPt[dst + 2] = soaMyFixedPt[src + 2];
				soaMyAnchored[i]      = soaMyAnchored[myoCt - 1];
				soaMyAnchored[myoCt - 1] = 0;
				//System.out.println ("Removed " + String.valueOf(curM) + " from Myosin Array.  Its motor is " + String.valueOf(curM.myoMotor));
				curM.remove();
				curM.sepaku();
				myoCt--;
			}
		}
	}
	
	public static void markRandomMyosFoRemove() {
		Myosin curM;
		int rmMe = (int)(Math.random()*myoCt);
		theMyosins[rmMe].removeMe = true;
		System.out.println("Removing random myosin #" + rmMe);
	}
	
	public void remove() {
		myoMotor.remove();
		myoLever.remove();
		myoRod.remove();
	}

	public static void removeAll () {
		Myosin curM;
		for (int i=0;i<myoCt;i++) {
			curM = theMyosins[i];
			curM.remove();
			curM.sepaku();
		}
		// Inc2 — reset the anchor flags for the slots we just blew away so a
		// fresh population (after restartRun) starts from zero.
		java.util.Arrays.fill(soaMyAnchored, 0, myoCt, (byte) 0);
		MyoMotor.motorCt = 0;
		myoCt = 0;
	}
}
