package boxOfActin;

public class Myosin {
	static Myosin [] theMyosins = new Myosin [500000];
	static int myoCt = 0;
	int myMyoNumber = 0;
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
						for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
							theMyosins[i].applyGPUDroppedForces();
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
		MyoMotor.motorCt = 0;
		myoCt = 0;
	}
}
