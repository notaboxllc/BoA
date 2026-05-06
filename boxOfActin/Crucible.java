package boxOfActin;

import javax.media.j3d.BranchGroup;


public class Crucible extends Thing {
	static double boxVolume;	// entire volume contained within the rod-shaped bug
	static double microMolarChangePerMonomer;	// how much to change concentration when one monomer is used or returned
	
	
	// for multithreading
	static ChamberMyoThreads chamberMyoThreads = new ChamberMyoThreads();
	static ChamberMyoDThreads chamberMyoDThreads = new ChamberMyoDThreads();
	
	// myosin heads
	Myosin [] myosins = new Myosin [Env.numChamberFixedMyos.getIntValue()];
	Pt3D [] myoPtsInX = new Pt3D[Env.numChamberFixedMyos.getIntValue()];
	
	// myosin dimers
	MyosinDimer [] myodimers = new MyosinDimer[Env.numChamberFixedMyoDimers.getIntValue()];
	Pt3D [] myoDimerPtsInX = new Pt3D[Env.numChamberFixedMyoDimers.getIntValue()];
	
	// re-used in force calcs
	static Pt3D F = new Pt3D();
	static Pt3D R = new Pt3D();
	static Pt3D RcrossF = new Pt3D();
	static Pt3D torsionVec = new Pt3D();
	static Pt3D linkUVec1 = new Pt3D(); 
	static Pt3D linkUVec2 = new Pt3D();
	
	// for java3d
	BranchGroup infoG;
	static boolean appearanceChanged = false;

	
	public Crucible(Pt3D initCoord) {
		super(initCoord);
	}
	
	static class ChamberMyoThreads extends ThreadSet {
		ChamberMyoThreads () {
			super (Env.numMyoThreads, "Chamber Myo Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.myoJoints2Start:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*Env.numChamberFixedMyos.getIntValue()/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.myoJoints2Stop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.myoJoints2Start:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						keepMyosinOnSurface(i); 
					}
					break;
			}
		}
	}
	
	static class ChamberMyoDThreads extends ThreadSet {
		ChamberMyoDThreads () {
			super (Env.numMyoThreads, "Chamber MyoDimer Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.myoJoints2Start:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*Env.numChamberFixedMyoDimers.getIntValue()/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.myoJoints2Stop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.myoJoints2Start:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						keepMyosinDimerOnSurface(i); 
					}
					break;
			}
		}
	}
	
	public static void keepMyosinsOnSurface () {
		for (int i=0;i<Env.numChamberFixedMyos.getIntValue();i++) {
			keepMyosinOnSurface(i);
		}
	}
	
	public static void keepMyosinOnSurface (int i) {
		Myosin curMyo = Thing.theBox.myosins[i];
		MyoRod curRod = curMyo.myoRod;
		Pt3D curAttPt = Thing.theBox.myoPtsInX[i];
		double strainDist = Pt3D.ptDist(curRod.end1, curAttPt);
		linkUVec1.unitVec(curAttPt,curRod.end1);
		//linkUVec2.scale(-1,linkUVec1);
		double forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curRod.bTransGam.y));

		F.scale(forceMag,linkUVec1);
		curRod.incForceSum(F);
		
		//F.scale(-1,F);
		//incForceSum(F);
	}
	
	public static void keepMyosinDimersOnSurface () {
		for (int i=0;i<Env.numChamberFixedMyoDimers.getIntValue();i++) {
			keepMyosinDimerOnSurface(i);
		}
	}
	
	public static void keepMyosinDimerOnSurface (int i) {
		MyosinDimer curMyoD = Thing.theBox.myodimers[i];
		MyoRod curRod = curMyoD.myo1.myoRod;
		Pt3D curAttPt = Thing.theBox.myoDimerPtsInX[i];
		double strainDist = Pt3D.ptDist(curRod.end1, curAttPt);
		linkUVec1.unitVec(curAttPt,curRod.end1);
		//linkUVec2.scale(-1,linkUVec1);
		double forceMag = (Env.myoDimerFracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(1/curRod.bTransGam.y));

		F.scale(forceMag,linkUVec1);
		curRod.incForceSum(F);
		
		//F.scale(-1,F);
		//incForceSum(F);
	}
	
	
	public static void updateAllMyosins () {
		keepMyosinsOnSurface();
		keepMyosinDimersOnSurface();
	}
	
	public void makeMyosinHeads () {
		// override
	}
	
	public void makeMyosinDimers () {
		// override
	}
	
	public double getMonomerConc () {
		return Env.actinConc.getValue();
	}
	
	public double getNonHydroMonomerConc () {
		return Env.actinConcNonHydro.getValue();
	}
	
	public void takeMonomer (int numMon) {
		Env.actinConc.addToValue(-numMon*microMolarChangePerMonomer);
	}
	
	public void takeNonHydroMonomer (int numMon) {
		Env.actinConcNonHydro.addToValue(-numMon*microMolarChangePerMonomer);
	}
	
	public void putMonomer (int numMon) {
		Env.actinConc.addToValue(numMon*microMolarChangePerMonomer);
	}
	
	public void putNonHydroMonomer (int numMon) {
		Env.actinConcNonHydro.addToValue(numMon*microMolarChangePerMonomer);
	}
	
	public void amICollidingOuter (CollisionEvent CE, Pt3D ctr, double R) {
		// override to do something
	}
	
	public void amICollidingInner (CollisionEvent CE, Pt3D ctr, double R) {
		// override to do something
	}
	
	public Pt3D rdmPtInside () {
		// override to do something relevant
		return new Pt3D();
	}
	
	public Pt3D rdmPtInside (double objRadius) {
		// override to do something relevant
		return new Pt3D();
	}
	
	public Pt3D rdmPtInsideSpawnFraction () {
		// override to do something relevant
		return new Pt3D();
	}
	
	public double getVolume() {
		return 0;
	}
	
	public static void appearanceChange() {
		appearanceChanged = true;
		if (Env.paused) { theBox.updateGraphics();}
	}
	
	public void updateTextInfoState() {
		infoG.detach();
		if (Env.infoIn3D) {
			g3d.addChild(infoG);
		}
	}

}
