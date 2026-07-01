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

	// Single-myosin / single-filament BINDING demo (2026-06-30). One short filament along +X through
	// the origin, plus one fixed myosin posed so its head is already in the binding pocket — no Brownian
	// search needed. For the center-bind model the head is laid ~anti-parallel to the filament (uVec ~ -X,
	// satisfying the 30deg gate) with its centre on the axis; otherwise the head is held ~perpendicular
	// (vertical motor, head up at the filament — the baseline gate). Run with Brownian off
	// (BTransCoeff/BRotCoeff/myoBrownianAttn = 0), a small dt, and a high frame rate to watch the bind
	// (and subsequent power stroke) deterministically.
	public static void setUpSingleBindDemo () {
		// short filament centred at the origin, +X (pointed end = -X, barbed = +X), z = 0
		double filLen = 0.4;
		int monCt = (int)(filLen / Env.actinMonoRadius);
		new FilSegment(new Pt3D(0, 0, 0), new Pt3D(1, 0, 0), -1, monCt, false);

		double rodLen   = Env.myoRodLength.getValue();
		double leverLen = Env.myoLeverLength.getValue();
		double motorLen = Env.myoMotorLength.getValue();
		boolean centerBind = Env.myoCenterParallelBind.isActive() && Env.myoCenterParallelBind.getValue() != 0.0;
		if (centerBind) {
			// BENT, body-ORTHOGONAL IC: the head is laid anti-parallel ON the filament (centre at the
			// origin, uVec = -X, satisfying the 30deg center-bind gate), while the neck + rod stand
			// straight UP (+Z), perpendicular to the filament. This L-shape puts the long body orthogonal
			// to the filament so the neck (lever-motor) power stroke sweeps in the X-Z viewing plane.
			// Build the straight assembly pointing -Z from the rod-tail anchor, then re-pose only the head
			// to lie flat on the filament; the lever's motor-end already sits at (½·motorLen, 0, 0) so the
			// head's neck-end joins it. Start the head in ADP-Pi so the lever-motor joint is at its 90deg
			// pre-stroke rest matching this geometry; the ADP-Pi->ADP transition then swings it to ~160deg.
			double x0      = 0.5 * motorLen;        // head neck-end == lever-motor joint x
			double anchorZ = rodLen + leverLen;     // rod-tail anchor at the top of the upright body
			MyosinFixed myo = new MyosinFixed(new Pt3D(x0, 0, anchorZ), new Pt3D(0, 0, -1));
			myo.setMotor(new Pt3D(0, 0, 0), new Pt3D(-1, 0, 0), motorLen, MyoMotor.ADPPi);
		} else {
			// TIP-bound model (e.g. myoFixedHeadNeckStroke: head held ~90deg to the filament, neck swings
			// 0->70deg). The motor stands perpendicular with its head TIP (end2) on the filament and the
			// neck + rod standing UP, orthogonal to the filament. A small initial neck bend (in the X-Z
			// plane) breaks the collinear lever-motor degeneracy (no Brownian here) so the power-stroke
			// torque has a defined swing plane. Build rod+lever along the slightly-tilted body axis so
			// lever.end2 lands at the head's neck point (0,0,motorLen), then re-pose the head perpendicular
			// with its tip on the filament. Start in ADP so the lever-motor joint drives to its 70deg
			// post-stroke rest (the neck visibly swings up from ~10deg to 70deg).
			double NB    = Math.toRadians(10.0);                        // initial neck bend off the head axis
			// bend toward +X (the POINTED side): the rear starts pointed-ward, so WITHOUT the polarity fix
			// the stroke would sweep it toward the pointed end. This is the adversarial IC for verifying
			// myoNeckStrokePolarity — the fix must swing the rear to the barbed (+) end regardless. (This +X
			// bend also satisfies the stock rod-orientation gate, so binding needs no gate bypass.)
			Pt3D bodyU   = new Pt3D(Math.sin(NB), 0, -Math.cos(NB));    // rod/lever axis (toward tip), mostly -Z
			double rl    = rodLen + leverLen;
			Pt3D rodEnd1 = new Pt3D(-rl * bodyU.x, 0, motorLen - rl * bodyU.z);
			MyosinFixed myo = new MyosinFixed(rodEnd1, bodyU);
			myo.setMotor(new Pt3D(0, 0, 0.5 * motorLen), new Pt3D(0, 0, -1), motorLen, MyoMotor.ADP);
		}
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
