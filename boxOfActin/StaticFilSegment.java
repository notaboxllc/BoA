package boxOfActin;

public class StaticFilSegment extends FilSegment {
	static final int staticFilSegLength = 200;
	static double likeARealFilTime; 

	public StaticFilSegment(Pt3D initCoord, Pt3D initUVec, int filID) {
		super(initCoord, initUVec, filID);
	}
	
	public StaticFilSegment (Pt3D initCoord, Pt3D initUVec, int filID, int monomerCt, boolean fromFile) {
		super(initCoord,initUVec,filID,monomerCt,fromFile);
		likeARealFilTime = 200*Env.deltaT.getValue();
	}
	
	public StaticFilSegment(Pt3D initCoord, Pt3D initUVec, int monomerCt, FilSegment splitFromFil) {
		super(initCoord,initUVec,monomerCt,splitFromFil);
	}
	
	public void step () {
		// increment counters that control how often different bits are run
		collCheckCt++;
		
		if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			end1TipC = end2TipC = 1e6;		// reset these to big numbers since we are about to check them again
			
			checkBugOrBoxCollision(); 		// these should add forces and torques to forceSum and torqueSum
			//if (Env.simulationTime < 0.001) { checkForminBinding(); }
			
			collCheckCt = 0;
		}
		
		addLinkForces();				// if linked to other segments
		
		addTorsionSpringForces();		// bending rigidity proxy
		
		//addNodeForces();				// calculate elastic forces to keep filament ends and bound plasmids together
	
	}
	
	public void moveThing () {
		if (Env.simulationTime < likeARealFilTime) {
			super.moveThing();
		}
	}
	
	public void biochemStep () {

		if (monomerCt >= 2*staticFilSegLength) {
			splitSegment();			// setFirstHalf pushes pose internally
			calculateProperties();	// again if split
			initialize();
		}

	}
	
	public void splitSegment () {
		int halfSegCt = staticFilSegLength;
		setFirstHalf(halfSegCt);
		double nextFilLength = (halfSegCt+1)*Env.actinMonoRadius;
		Pt3D nextFilCoord = Pt3D.Add(end2AsPt3D(),0.5*nextFilLength-Env.actinMonoRadius,uVecAsPt3D());
		StaticFilSegment nextFil = new StaticFilSegment (nextFilCoord,this.uVecAsPt3D(),halfSegCt,this);
		setEnd2Links(nextFil, true);
		if (!Env.noMonomersSimd.isActive()) { transferMons (monomerCt,nextFil); }
	}
	
	public static void makeStaticHoop () {
		int numMons = 1600;
		//north part of hoop
		Pt3D coordPt = new Pt3D(0,Env.bugRadius.getValue(),0);
		Pt3D unitVec = new Pt3D(0,0,1);
		Pt3D unitVecR = new Pt3D(0,0,-1);
		//new StaticFilSegment(coordPt,unitVec,-1,numMons,false);
		new StaticFilSegment(coordPt,unitVecR,-1,numMons,false);

		
		// south of hoop
		coordPt.setVals(0,-Env.bugRadius.getValue(),0);
		//new StaticFilSegment(coordPt,unitVec,-1,numMons,false);
		new StaticFilSegment(coordPt,unitVecR,-1,numMons,false);
	
	}

}
