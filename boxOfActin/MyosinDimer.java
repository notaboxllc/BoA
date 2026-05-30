package boxOfActin;

public class MyosinDimer {
	static MyosinDimer [] theMyoDimers = new MyosinDimer[100000];
	static int myoDimerCt = 0;
	static double leverAngle = 160; // degrees
	int myMyoDimerNumber;
	Pt3D myCM = new Pt3D(); // center of mass
	Myosin myo1,myo2;
	boolean parallel = true;
	boolean removeMe = false; // flag for taking this myosin out of the simulation
	
	// for multithreading
	static MyoDimerThreads myoDimerThreads = new MyoDimerThreads();
	
	// re-used in force calcs
	Pt3D F = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RcrossF = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D linkUVec1 = new Pt3D(); 
	Pt3D linkUVec2 = new Pt3D();

	public MyosinDimer () {
		myo1 = new Myosin();
		myo2 = new Myosin();
		addMyosinDimer(this);
	}
	
	public MyosinDimer (Pt3D motorCM) {
		myo1 = new Myosin(motorCM);
		myo2 = new Myosin(motorCM);
		addMyosinDimer(this);
	}
	
	public MyosinDimer (Pt3D motorCM, Pt3D unitVec) {
		myo1 = new Myosin(motorCM,unitVec);
		myo2 = new Myosin(motorCM,unitVec);
		addMyosinDimer(this);
	}
	
	static class MyoDimerThreads extends ThreadSet {
		MyoDimerThreads () {
			super (Env.numMyoThreads, "MyoDimer Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.myoJoints1Start:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*myoDimerCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.myoJoints1Stop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.myoJoints1Start:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						theMyoDimers[i].jointConstraints(); 
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		myCM = null;
		myo1 = null;
		myo2 = null;
		F = null;
		R = null;
		RcrossF = null;
		torsionVec = null;
		linkUVec1 = null;
		linkUVec2 = null;
	}
	
	public void alignUVecLeversTorque () {
		torsionVec.cross(myo1.myoLever.uVec,myo2.myoLever.uVec);
		torsionVec.unitVec();
		
		double dotVecs = Pt3D.Dot(myo1.myoLever.uVec,myo2.myoLever.uVec);
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;
		
		double angD = angTween-leverAngle;
			
		//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
		double torsionMag = Env.myoDimerLeverFracMoveTorq.getValue()*(Math.PI/180)*angD/((1/myo1.myoLever.bRotGam.y + 1/myo2.myoLever.bRotGam.y)*Env.deltaT.getValue());
		
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			myo1.myoLever.incTorqueSum(torsionVec);
		
			torsionVec.scale(-1);
			myo2.myoLever.incTorqueSum(torsionVec);
		} else {
			System.out.println ("Crazy torque result in MyoDimer.alignUVecLeversTorque()");
		}

	}
	
	public void alignYVecLeversTorque () {
		torsionVec.cross(myo1.myoLever.yVec,myo2.myoLever.yVec);
		torsionVec.unitVec();
		
		double dotVecs = Pt3D.Dot(myo1.myoLever.yVec,myo2.myoLever.yVec);
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;
		
		double angD = angTween-180;
			
		//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
		double torsionMag = Env.myoDimerLeverFracMoveTorq.getValue()*(Math.PI/180)*angD/((1/myo1.myoLever.bRotGam.x + 1/myo2.myoLever.bRotGam.x)*Env.deltaT.getValue());
		
		if (torsionVec.checkPt3D()) {
			torsionVec.scale(torsionMag);
			myo1.myoLever.incTorqueSum(torsionVec);
		
			torsionVec.scale(-1);
			myo2.myoLever.incTorqueSum(torsionVec);
		} else {
			System.out.println ("Crazy torque result in MyoDimer.alignYVecLeversTorque()");
		}

	}
	
	public void applyRodCouplingEnd1 () {
		double strainDist,moveC1,moveC2,forceMag;
		MyoRod myoRod1 = myo1.myoRod;
		MyoRod myoRod2 = myo2.myoRod;
		
		// forces applied between myosin rods, part I
		strainDist = Pt3D.ptDist(myoRod1.end1, myoRod2.end1);
		linkUVec1.unitVec(myoRod2.end1,myoRod1.end1);
		linkUVec2.scale(-1,linkUVec1);
		moveC1 = myoRod1.moveCoeff(1,linkUVec1);
		moveC2 = myoRod2.moveCoeff(1,linkUVec2);
		forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveC1 + moveC2));

		F.scale(forceMag,linkUVec1);
		myoRod1.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod1.uVecR);
		RcrossF.cross(R,F);
		myoRod1.incTorqueSum(RcrossF);
		
		F.scale(-1,F);
		myoRod2.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod2.uVecR);
		RcrossF.cross(R,F);
		myoRod2.incTorqueSum(RcrossF);
	}
	
	public void applyRodCouplingEnd2 () {
		double strainDist,moveC1,moveC2,forceMag;
		MyoRod myoRod1 = myo1.myoRod;
		MyoRod myoRod2 = myo2.myoRod;
		
		// forces applied between myosin rods, part II
		strainDist = Pt3D.ptDist(myoRod1.end2, myoRod2.end2);
		linkUVec1.unitVec(myoRod2.end2,myoRod1.end2);
		linkUVec2.scale(-1,linkUVec1);
		moveC1 = myoRod1.moveCoeff(2,linkUVec1);
		moveC2 = myoRod2.moveCoeff(2,linkUVec2);
		forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveC1 + moveC2));

		F.scale(forceMag,linkUVec1);
		myoRod1.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod1.uVec);
		RcrossF.cross(R,F);
		myoRod1.incTorqueSum(RcrossF);
		
		F.scale(-1,F);
		myoRod2.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod2.uVec);
		RcrossF.cross(R,F);
		myoRod2.incTorqueSum(RcrossF);
	}
	
	public void applyRodCouplingEnd1End2 () {
		double strainDist,moveC1,moveC2,forceMag;
		MyoRod myoRod1 = myo1.myoRod;
		MyoRod myoRod2 = myo2.myoRod;
		
		// forces applied between myosin rods, part I
		strainDist = Pt3D.ptDist(myoRod1.end1, myoRod2.end2);
		linkUVec1.unitVec(myoRod2.end2,myoRod1.end1);
		linkUVec2.scale(-1,linkUVec1);
		moveC1 = myoRod1.moveCoeff(1,linkUVec1);
		moveC2 = myoRod2.moveCoeff(2,linkUVec2);
		forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveC1 + moveC2));

		F.scale(forceMag,linkUVec1);
		myoRod1.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod1.uVecR);
		RcrossF.cross(R,F);
		myoRod1.incTorqueSum(RcrossF);
		
		F.scale(-1,F);
		myoRod2.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod2.uVec);
		RcrossF.cross(R,F);
		myoRod2.incTorqueSum(RcrossF);
	}
	
	public void applyRodCouplingEnd2End1 () {
		double strainDist,moveC1,moveC2,forceMag;
		MyoRod myoRod1 = myo1.myoRod;
		MyoRod myoRod2 = myo2.myoRod;
		
		// forces applied between myosin rods, part I
		strainDist = Pt3D.ptDist(myoRod1.end2, myoRod2.end1);
		linkUVec1.unitVec(myoRod2.end1,myoRod1.end2);
		linkUVec2.scale(-1,linkUVec1);
		moveC1 = myoRod1.moveCoeff(2,linkUVec1);
		moveC2 = myoRod2.moveCoeff(1,linkUVec2);
		forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveC1 + moveC2));

		F.scale(forceMag,linkUVec1);
		myoRod1.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod1.uVec);
		RcrossF.cross(R,F);
		myoRod1.incTorqueSum(RcrossF);
		
		F.scale(-1,F);
		myoRod2.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue(),myoRod2.uVecR);
		RcrossF.cross(R,F);
		myoRod2.incTorqueSum(RcrossF);
	}

	public void enforceParallel() {
		if (!myo1.myoMotor.onFil | !myo2.myoMotor.onFil) {
			alignUVecLeversTorque();
		}
		//alignYVecLeversTorque();
		
		applyRodCouplingEnd1();
		applyRodCouplingEnd2();
	}
	
	public void enforceAntiParallel() {
		applyRodCouplingEnd1End2();
		applyRodCouplingEnd2End1();
	}
	
	public static void allJointConstraints () {
		for (int i=0;i<myoDimerCt;i++) {
			theMyoDimers[i].jointConstraints();
		}
	}
	
	public void jointConstraints() {
		if (myo1.removeMe || myo2.removeMe) { // check if dimer is falling apart/disappearing
			myo1.removeMe = true;
			myo2.removeMe = true;
			removeMe = true; 
		} else {
			if (parallel) {
				enforceParallel();
			} else {
				enforceAntiParallel();
			}
		}
	}
	
	public void setCM () {
		myCM.add(myo1.myoRod.end2,myo2.myoRod.end2);
		myCM.scale(0.5);
	}
	
	public double getPositionOnFil() {
		FilSegment end1Fil = FilSegment.theFilSegments[0];
		FilSegment end2Fil = FilSegment.theFilSegments[0];

		for (int i=0;i<FilSegment.filSegmentCt;i++) {
			if (!FilSegment.theFilSegments[i].filAtEnd1) { end1Fil = FilSegment.theFilSegments[i]; }
			if (!FilSegment.theFilSegments[i].filAtEnd2) { end2Fil = FilSegment.theFilSegments[i]; }
		}
		Pt3D bigUVec = Pt3D.UnitVec(end1Fil.end1,end2Fil.end2);
		setCM();
		double toCMDist = Pt3D.ptDist(end1Fil.end1,myCM);
		Pt3D toCMUVec = Pt3D.UnitVec(toCMDist,end1Fil.end1,myCM);
		double cosAng = Pt3D.Dot(bigUVec, toCMUVec);
		return toCMDist*cosAng;
	}
	
	public void setOwnerNode (ProteinNode node) {
		myo1.setOwnerNode(node);
		myo2.setOwnerNode(node);
	}
	
	public static void makeTestDimer () {
		new MyosinDimer(new Pt3D(-0.4,Env.myoMotorLength.getValue(),0));
	}
	
	public static void addMyosinDimer (MyosinDimer nuMyoD) {
		theMyoDimers[myoDimerCt] = nuMyoD;
		nuMyoD.myMyoDimerNumber = myoDimerCt;
		myoDimerCt++;
	}
	
	public static synchronized void cleanupMyoDimers () {
		MyosinDimer curMD;
		for (int i=0;i<myoDimerCt;i++) {
			if (theMyoDimers[i] == null) { break; } // reached end of theMyosinDimers array I guess
			curMD = theMyoDimers[i];
			if (curMD.removeMe) { 
				theMyoDimers[i] = theMyoDimers[myoDimerCt-1];
				theMyoDimers[i].myMyoDimerNumber = i;
				theMyoDimers[myoDimerCt-1] = null;
				curMD.myo1.removeMe = true;
				curMD.myo2.removeMe = true;
				curMD.sepaku();
				myoDimerCt--;
			}
		}
	}
	
	
	public static void removeAll () {
		MyosinDimer curMD;
		for (int i=0;i<myoDimerCt;i++) {
			curMD = theMyoDimers[i];
			curMD.sepaku();
		}
		
		myoDimerCt = 0;
	}
}
