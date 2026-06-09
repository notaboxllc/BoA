package boxOfActin;

public class MyosinFixed extends Myosin {

	Pt3D myFixedPt = new Pt3D();
	
	// bunch of declarations for storing multiple run reset data for gliding assay
	static int numPosPts = 600;
	static int numRunCols = 11;
	static double [][] posData = new double[numPosPts][numRunCols];
	static String [] posHeaders = new String[numRunCols];
	static int curRun = 1;	// track current 
	static int curTimePt = 1; // current reporting time location in array
	static boolean headerWritten = false;
	static double myoDensityIncrement = 100; // myos per square micron
	static double finalTimeForEachDataPoint = 2.0; // seconds
	
	public MyosinFixed (Pt3D rodEnd1,Pt3D unitVec) {
		super(rodEnd1,unitVec);
		myFixedPt.copy(rodEnd1);
		// Inc2 — populate the per-myo anchor SoA. addMyosin() ran inside
		// super(...) and stamped myMyoNumber, so the slot index is final here.
		int b = myMyoNumber * 3;
		soaMyFixedPt[b]     = (float) myFixedPt.x;
		soaMyFixedPt[b + 1] = (float) myFixedPt.y;
		soaMyFixedPt[b + 2] = (float) myFixedPt.z;
		soaMyAnchored[myMyoNumber] = 1;
	}
	
	public void jointConstraints () {
		super.jointConstraints();
		applyRodFixedPtForce();
	}

	// GPU-path reduced pass — see Myosin.applyGPUDroppedForces().
	// Applies ONLY the rod-tail anchor spring; the four inter-segment joint
	// forces/torques are computed by GPUMoveThing.jointsKernel on device and
	// would double-apply if also run here.
	public void applyGPUDroppedForces () {
		applyRodFixedPtForce();
	}
	
	// Phase 4.5 diag (2026-06-05): count applyRodFixedPtForce fires on the
	// GPU path. Should be 0 in default config (gated off via DIAG_CPU_ANCHOR=false
	// in jointConstraints/applyGPUDroppedForces). Nonzero => the anchor gate is
	// leaking and this is the stale end1AsPt3D() reader.
	public static long DIAG_ANCHOR_FIRE_CT = 0;

	public void applyRodFixedPtForce () {
		if (Env.useGPU) DIAG_ANCHOR_FIRE_CT++;
		double strainDist = Pt3D.ptDist(myoRod.end1AsPt3D(), myFixedPt);
		linkUVec1.unitVec(myoRod.end1AsPt3D(),myFixedPt);
		linkUVec2.scale(-1,linkUVec1);
		double moveC1 = 0; // fixed point is not going to move at all
		double moveC2 = myoRod.moveCoeff(2,linkUVec2);
		double forceMag = (Env.myoJ2FracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveC1 + moveC2));

		// forces and torques applied to fixed pt (leaving code here in case a myo cluster becomes a thing that moves)
		F.scale(forceMag,linkUVec1);
		//myoLever.incForceSum(F);
		//R.scale(0.5e-6*Env.myoLeverLength.getValue()*Env.myoJ2FracR.getValue(),myoLever.uVecRAsPt3D());
		//RcrossF.cross(R,F);
		//myoLever.incTorqueSum(RcrossF);
		
		// forces and torques applied to myosin rod
		F.scale(-1,F);
		myoRod.incForceSum(F);
		R.scale(0.5e-6*Env.myoRodLength.getValue()*Env.myoJ2FracR.getValue(),myoRod.uVecAsPt3D());
		RcrossF.cross(R,F);
		//myoRod.incTorqueSum(RcrossF);
		
	}
	
	public static void makeLineOfFixedMyoClusters () {
		
		
	}
	
	public static void makeFixedMyosinCluster () {  //unused at moment... using ProteinNodes to organize clusters of myosins
		int myosInCluster = 1000;
		Pt3D clusterCenter = new Pt3D();
		Pt3D rdmSpreadVec = new Pt3D();
		Pt3D myoFixedPt = new Pt3D();
		Pt3D myoDirection = new Pt3D(0,0,1);
		double clusterSpread = 1; // nm
		for (int i=0;i<myosInCluster;i++) {
			rdmSpreadVec.randomUnitVec(Env.mtRNG);
			rdmSpreadVec.z = -0.05;
			myoFixedPt.add(clusterCenter,clusterSpread,rdmSpreadVec);
			new MyosinFixed(myoFixedPt,myoDirection);
		}
	}
	
	public static void setUpGlidingAssay() {
		fillPlaneWithFixedMyosins();
		FilSegment.makeGlidingAssayFilament();
	}

	// 2026-05-31 pivot: minimal-system reproduction. Creates exactly one
	// MyosinFixed anchored as in the gliding assay (rod tail at z = fixedMyosinZValue,
	// initial pose pointing +z), with NO filaments. Used by the singleMyoDiag
	// parameter mode to characterize an isolated myosin's thermal conformational
	// ensemble for CPU vs GPU comparison.
	public static void setUpSingleMyosinDiag () {
		double zVal = Env.fixedMyosinZValue.getValue();
		Pt3D myoLoc = new Pt3D(0, 0, zVal);
		Pt3D myoDirection = new Pt3D(0, 0, 1);
		new MyosinFixed(myoLoc, myoDirection);
	}
	
	public static void fillPlaneWithFixedMyosins () {
		double myoDensity = Env.fixedMyosinDensity.getValue(); // number per sq. micron
		double zVal = Env.fixedMyosinZValue.getValue();  // set plane for fixed myosin tail
		double xSize = Env.boxXDim.getValue();
		double ySize = Env.boxYDim.getValue();
		int numMyos = (int)(xSize*ySize*myoDensity);
		Pt3D myoLoc = new Pt3D(0,0,zVal);
		Pt3D myoDirection = new Pt3D(0,0,1);
		for (int i=0;i<numMyos;i++) {
			myoLoc.x = xSize*Math.random()-xSize/2;
			myoLoc.y = ySize*Math.random()-ySize/2;
			new MyosinFixed(myoLoc,myoDirection);
		}
	}
	
	public static void glidingAssayDataSetRun () {
		if (Env.externalDensitySweep.isActive()) return;
		storeGlidingAssayPos();
		if (Env.simulationTime >= finalTimeForEachDataPoint) {
			if (curRun == numRunCols-1) { 
				FileOps.writeGlidingAssayDataSet();
				System.exit(0);
			} else {
				MyosinFixed.curRun++;
				double newMyoDensity = Env.fixedMyosinDensity.getValue()+myoDensityIncrement;
				Env.fixedMyosinDensity.setValue(newMyoDensity);
				resetForNextGlidingAssayRun();
				BoxOfActin.restartRun(false);
			}
		}
	}
	
	public static void storeGlidingAssayPos () {
		if (!headerWritten) {
			if (curRun==1) { posHeaders[0] = "time" + FileOps.sepString; }
			posHeaders[curRun] = "Myo Density " + Env.fixedMyosinDensity.getStringValue() + FileOps.sepString;
			headerWritten = true;
		}
		if (curRun==1) { posData[curTimePt][0] = Env.simulationTime; } // write times only for first iteration
		posData[curTimePt][curRun] = FilSegment.theFilSegments[0].getCoordX();
		curTimePt++;

	}
	
	public static void resetForNextGlidingAssayRun () {
		headerWritten = false;
		curTimePt = 1;
	}
}
