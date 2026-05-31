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
	}
	
	public void jointConstraints () {
		super.jointConstraints();
		applyRodFixedPtForce();
	}
	
	public void applyRodFixedPtForce () {
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
