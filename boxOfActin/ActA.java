package boxOfActin;

/**
 * ActA.java
 *
 *
 */


/*
	ActA .... live on Listeria, nucleates and tethers actin
*/

import java.awt.*;
import javax.swing.*;
import java.lang.Math.*;
import java.util.concurrent.ThreadLocalRandom;

import com.sun.j3d.utils.geometry.Cylinder;
import com.sun.j3d.utils.geometry.Sphere;
import com.sun.j3d.utils.universe.*;

import ec.util.MersenneTwisterFast;
import edu.cornell.lassp.houle.RngPack.RanMT;

import javax.media.j3d.*;
import javax.vecmath.*;

public class ActA {
	static final int maxActAs = 20000;
	static final int maxRmActAs = 200;
	static ActA [] theActAs = new ActA [maxActAs];
	static int actACt = 0;
	static ActA [] rmActAs = new ActA [maxRmActAs]; // list of tempActAs to remove
	static int rmActACt = 0;
	int myActAID;
	Bug myBug;				// the listeria on which this ActA lives
	boolean tempActA = false;	// distinguish between permanent ActAs and transients created on filament contact
	boolean removeMe = false;
	static double restLength = 0.011;	// nm  (in RocketBugs it is 2*gActinDiameter = 10.8)
	FilSegment boundFil;
	double boundLoc;				// distance bound from filament's minus-end
	boolean bound = false;
	Pt3D ptOnBug = new Pt3D();		// location on bug in bug's local coordinate system
	Pt3D ptOnBugInX = new Pt3D();	// location on bug in fixed coordinate system
	Pt3D ptOnFilInX = new Pt3D(); 	// location of binding point on filament
	boolean ptsUpdated = false;
	double linkLength;
	ValueTracker forceMag = new ValueTracker(Env.actATetherForcesToAve);
	ValueTracker strainTrack = new ValueTracker(Env.actATetherStrainToAve);
	double tetherTime;
	Pt3D linkVec = new Pt3D();
	Pt3D forceVec = new Pt3D();
	Pt3D R = new Pt3D();
	
	static double aShortFilLength = 15*Env.actinMonoRadius;	// used for keeping small filaments bound longer
	
	// multithreading
	static ActAThreads actAThreads = new ActAThreads();
	//MersenneTwisterFast myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));
	
	// for Java3D
	Point3d bugPt = new Point3d();
	Point3d filPt = new Point3d();
	BranchGroup G = new BranchGroup();
	LineArray linkLine;
	Shape3D linkShape;
	boolean graphicsMade = false;
	
	public ActA (Pt3D ptOnBug, Bug bug) {  
		myBug = bug;
		this.ptOnBug.copy(ptOnBug);
		addActA(this);
		updatePtOnBugInX();
		tetherTime = Env.simulationTime;
	}
	
	public class RetObj {
		// this is the object passed by from line-line and line-point intersect tests
		Pt3D conPt1, conPt2, ray1, ray2, ray3, ray4;
		double conDist = 0;
		double alpha, beta;	 // the coefficients of ray1 and ray2, respectively, to define contact pts from end1s
		boolean collision = false;
		
		public RetObj () {
			conPt1 = new Pt3D();
			conPt2 = new Pt3D();
			ray1 = new Pt3D();
			ray2 = new Pt3D();
			ray3 = new Pt3D();
			ray4 = new Pt3D();
		}
		
		public void reset () {
			collision = false;
		}
	}
	
	static class ActAThreads extends ThreadSet {
		ActAThreads () {
			super (Env.numActAThreads, "ActA Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.actAStart:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*actACt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.actAStop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.actAStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						theActAs[i].step(); 
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		boundFil = null;
		ptOnBug = null;
		ptOnBugInX = null;
		
		forceMag = null;
		strainTrack = null;
		linkVec = null;
		forceVec = null;
		R = null;
		G = null;
		linkLine = null;
		linkShape = null;
	}
	
	public void step () {
		//System.out.print("in ActA.step()...");
		// add arp, nucleate, enforce tether
		if (bound && boundFil.removeMe) { releaseFil(); }
		
		nucleateFilament();
		makeSideBranch();
		if (bound) {    
			moveFilLoc();  // might become unbound, release if boundLoc moves off boundFil
			updatePts();   
			updateLink();
			applyTetherForce();
			checkTetherDetachRocketBugsStyle();
			//checkTetherDetach();
			checkUncap();
			ptsUpdated = true;
		} else { 
			ptsUpdated = false; 
		} 
		
		if (bound) {  // after all these possible actions above...
			boundFil.actAOn = true; 	// flag filament so it knows it's tethered 
		} else {	
			releaseFil();  // several paths to unbinding above
		}
	}
	
	public void nucleateFilament() {
		if (!bound) {
			if (ThreadLocalRandom.current().nextDouble() < Env.actANucProb.getValue()*Env.actinConc.getValue()*Env.deltaT.getValue()) {
				Pt3D normalOut = Pt3D.Sub(ptOnBugInX, myBug.coord);
				normalOut.unitVec();
				Pt3D loc = Pt3D.Add(ptOnBugInX,3*Env.actinMonoRadius,normalOut);
				normalOut.scale(-1);
				//FilSegment nuBound = new FilSegment(loc,normalOut,-1);
				FilSegment nuBound = new FilSegment(loc,Pt3D.RandomUnitVec(Env.mtRNG),-1);
				setFil(nuBound,3*Env.actinMonoRadius);
				Thing.lmBug.addNucleation();
			}
		}
	}
	
	public void makeSideBranch () {
		if (bound) {
			if (ThreadLocalRandom.current().nextDouble() < (Env.actABranchProb.getValue()*Env.deltaT.getValue())) { //0.001) { 
				if (boundFil.canAddArpHere(boundLoc)) {
					FilSegment nuBranch = boundFil.makeArpBranch(boundLoc);
					//setFil(nuBranch,nuBranch.length);	// transfer tether to daughter filament?
					unBindTether();
				}
			}
		}
	}
	
	public void checkUncap() {
		if (bound) {
			if ((boundFil.length - boundLoc) < Env.actAUncapDistance.getValue()) { boundFil.end2Capped = false; } // uncap if ActA bound near end
		}
	}
	
	public void moveFilLoc () {
		// update boundLoc if minus end of boundFil has de/polymerized
		boundLoc += boundFil.minusEndDelta*Env.actinMonoRadius;
		// conditions for link dissolution
		if ((boundLoc < 0)) { // | (boundLoc > boundFil.length)) { 
			unBindTether();
		} 
	}
	
	public void updatePts () {
		ptOnBugInX.xToXPlusPoint(myBug ,ptOnBug, myBug.coord);  // stored loc on bug transformed to fixed-frame point in space
		try {
			ptOnFilInX.add(boundFil.end1,boundLoc,boundFil.uVec);  // binding pt on filament to fixed-frame point in space
		} catch (NullPointerException npe) {
			System.out.println ("NPE in updatePts: bound = " + bound + ", boundFil = " + boundFil + ", monomers = " + boundFil.monomerCt);
		}
	}
	
	public void updateLink () {
		linkLength = Pt3D.ptDist(ptOnBugInX,ptOnFilInX);
		linkVec.sub(ptOnBugInX,ptOnFilInX);
		linkVec.unitVec();
	}
	
	public void updatePtOnBugInX () {
		ptOnBugInX.xToXPlusPoint(myBug ,ptOnBug, myBug.coord);  // stored loc on bug transformed to fixed-frame point in space
	}

	public void setFil(FilSegment fil, double loc) {
		bound = true;
		boundFil = fil;
		boundFil.actAOn = true;
		boundLoc = loc;
		tetherTime = Env.simulationTime;
		forceMag.zero();
		strainTrack.zero();
		Thing.lmBug.addTetherFormed();
	}
	
	public void unBindTether() {
		bound = false;
		Thing.lmBug.addTetherBroken(getAge());
	}
	
	public void releaseFil() {
		try { boundFil.actAOn = false; } catch (NullPointerException npe) {}
		boundFil = null;
		bound = false;
		//if (tempActA) { addRmActA(this); }
	}
	
	static void checkBindingToActA(FilSegment fil, Pt3D pt) {
		// pt comes in as the vector from bug.coord to collision point in bug's coordinate system
		if (fil.actAOn) { return; } // hack, only one ActA per filament with this here
		ActA bindTo = findCloseActA(pt);
		if (bindTo != null) { // found an available ActA close enough!
			bindTo.setFil(fil, fil.length);
			//System.out.println("found a surface ActA to bind!");
		} else {
			//System.out.println("no nearby ActA");
		}
	}
	
	static synchronized ActA findCloseActA (Pt3D pt) {
		ActA curActA;
		double closeActATolerance = Env.closeActATolerance.getValue();
		//System.out.println("pt.x = " + pt.x + " : actA1.ptOnBug.x " + theActAs[0].ptOnBug.x);
		for (int i=0;i<actACt;i++) {
			curActA = theActAs[i];
			if (!curActA.bound) {
				if (Math.abs(pt.x - curActA.ptOnBug.x) < closeActATolerance) {
					if (Math.abs(pt.y - curActA.ptOnBug.y) < closeActATolerance) {
						if (Math.abs(pt.z - curActA.ptOnBug.z) < closeActATolerance) {
							return curActA;
						}
					}
				}
			}
		}
		return null;
	}
	
	public boolean checkTetherDetach() {
		double aveStrain = strainTrack.averageVal();
		//System.out.println("aveStrain = " + aveStrain);
		if (aveStrain > Env.actAMaxTetherStrain.getValue()) { 
			//System.out.println("aveStrain = " + aveStrain + " and we broke that tether!");
			return true;
		}
		return false;
		/*
		double releaseProb = (Env.linkOffConst.getValue() + Env.linkOffCoeff.getValue()*Math.exp(aveStrain*Env.linkOffExp.getValue()))*Env.deltaT.getValue();
		if (ThreadLocalRandom.current().nextDouble() < releaseProb) {
			return true;
		}
		return false; */
	}
	
	public double getAge() {
		return (Env.simulationTime - tetherTime);  // time this tether has been active, in seconds
	}
	
	public void checkTetherDetachRocketBugsStyle() {
		if (ThreadLocalRandom.current().nextDouble() < Env.actADetachProb.getValue()*Env.deltaT.getValue()) {
			unBindTether();
			//System.out.println("broke tether 'cause of nominal detach prob");
			return;
		}
		
		double strainAdjust = 1;
		if ((boundFil.length < aShortFilLength) && (getAge() < 1)) { strainAdjust = 10; }  // keep young/short filaments around a while
	
		double aveStrain = strainTrack.averageVal();
		//System.out.println("aveStrain = " + aveStrain);
		if (aveStrain > strainAdjust*Env.actAMaxTetherStrain.getValue()) { 
			//System.out.println("aveStrain = " + aveStrain + " and we broke that tether!");
			unBindTether();
		}
	}
	
	
	public void applyTetherForce () {
		if (!bound) { return; }
		// strains
		double curStretchDist = linkLength-restLength;
		double curForceMag;
		if (curStretchDist < 0) { curStretchDist = 0; }
		strainTrack.registerValue(curStretchDist/restLength);
		//if (true) return;		// remove... turning off tether force
		try {
			//forces and accompanying torques
			if (Env.actASpringK.isActive()) {
				//System.out.println("using simple spring");
				curForceMag = (1.0e-6*curStretchDist*Env.actASpringK.getValue());
			} else {
				curForceMag= (Env.actATetherFracMove.getValue()*1.0e-6*curStretchDist/Env.deltaT.getValue())/(1/boundFil.bTransGam.y+1/myBug.bTransGam.x);
			}
			forceMag.registerValue(curForceMag);
			forceVec.scale(curForceMag,linkVec);
			boundFil.incForceSum(forceVec,ptOnFilInX);
			
			forceVec.reverse();
			myBug.incForceSum(forceVec,ptOnBugInX);
			myBug.addPathTetherForceOnBug(forceVec);
			//System.out.println("ActA tether forceMag = " + curForceMag);
		} catch (NullPointerException npe) {
			System.out.println("NPE in ActA.applyTetherForce with bound = " + bound + ", boundFil = " + boundFil);
		}
	}
	
	
	synchronized static void addActA (ActA addMe) {
		theActAs[actACt] = addMe;
		addMe.myActAID = actACt;
		actACt++;
	}
	
	synchronized static void addRmActA (ActA addMe) {
		rmActAs[rmActACt] = addMe;
		rmActACt++;
	}
	
	public static void cleanUpActAs () {
		for (int i=0;i<rmActACt;i++) {
			removeActA(rmActAs[i]);
		}
		rmActACt = 0;
	}
	
	
	synchronized static void removeActA (ActA rmMe) { 
		if (rmMe.graphicsMade) { rmMe.G.detach(); }
		int swapId = rmMe.myActAID;				// here we swap from end of list to keep a compact array of FilLinks
		theActAs[swapId] = theActAs[actACt-1];
		theActAs[swapId].myActAID = swapId;
		rmMe.sepaku();
		actACt--;
	}
	
	public static void removeAll () {
		for (int i=0;i<actACt;i++) {
			if (theActAs[i].graphicsMade) { theActAs[i].G.detach(); }
			theActAs[i].sepaku();
		}
		actACt = 0;
	}
	
	public static int countBoundActAs () {
		int boundCt = 0;
		for (int i=0;i<actACt;i++) {
			if (theActAs[i].bound) { boundCt++; }
		}
		return boundCt;
	}
	
	public static void pointAndLineIntersectTest (Pt3D point, Pt3D ptA, Pt3D ptB, RetObj retO) {
		// Point and Line Segment Intersection test... 
		// see derivation of the following formulae in work book... uses dot product as zero to enforce
		// the perpendicularity and parameterization of line segment to check if the perpendicular drop
		// from sphere to line is on the line segment.
		// A line segment is {x1,y1,z1,x2,y2,z2}
		// A point is {x,y,z}
		retO.reset();
		
		retO.ray1.sub(ptB,ptA);
		retO.ray2.sub(point,ptA);
		double numer = Pt3D.Dot(retO.ray2,retO.ray1);
		double denom = Pt3D.vecMagSqrd(retO.ray1);
		double alpha = numer/denom;
		if ((alpha <= 1) & (alpha >= 0)) {	// the perpendicular projection is on the line segment...  then check distance
			retO.collision = true;
			retO.conPt1.add(ptA, alpha,retO.ray1);		// define perpendicular point
			retO.conDist = Pt3D.ptDist (retO.conPt1,point);
		} else {
			retO.alpha = alpha;
		}	
	}
	
	public void makeGraphics () {
		// capabilities for graphics objects
		G.setCapability(BranchGroup.ALLOW_DETACH);
		
		Color3f actAColor = new Color3f(0.0f,1.0f,0.0f);
		ColoringAttributes cA = new ColoringAttributes(actAColor, ColoringAttributes.FASTEST);
		Appearance actAApp = new Appearance();
		actAApp.setColoringAttributes(cA);
		//LineAttributes la = new LineAttributes();
		//la.setLineWidth(3);
		//actAApp.setLineAttributes(la);
		
		// line array
		linkLine = new LineArray(2,LineArray.COORDINATES);
		linkLine.setCapability(LineArray.ALLOW_COORDINATE_WRITE);
		linkLine.setCoordinate(0,new Point3d(ptOnBugInX.x,ptOnBugInX.y,ptOnBugInX.z));
		linkLine.setCoordinate(1,new Point3d(ptOnFilInX.x,ptOnFilInX.y,ptOnFilInX.z));
		linkShape = new Shape3D();
		linkShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		linkShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		linkShape.setGeometry(linkLine);
		linkShape.setAppearance(actAApp);
		
		G.addChild(linkShape);
		graphicsMade = true;
	}
	
	public void updateGraphics () {
		if (!ptsUpdated) { updatePtOnBugInX(); }
		bugPt.set(ptOnBugInX.x,ptOnBugInX.y,ptOnBugInX.z);
		if (!bound) {
			ptOnFilInX.randomUnitVec(Env.mtRNG);
			ptOnFilInX.scale(restLength);
			ptOnFilInX.add(ptOnBugInX);
		}
		filPt.set(ptOnFilInX.x,ptOnFilInX.y,ptOnFilInX.z);
		linkLine.setCoordinate(0,bugPt);
		linkLine.setCoordinate(1,filPt);
		linkShape.setGeometry(linkLine);
		
	}
	
	public Node getGraphicsNode () {
		if (!graphicsMade) { makeGraphics();}
		updateGraphics();
		return G;
	}
	
	public String getJSonString () {
		/* Format for ActA JSON Serialization for Simularium
          1001.0,// visualization type : fiber
          60000+idnum,   // agent instance ID
          6,   	 // agent type ID --ActA
          coord.x,  // position X
          coord.y,  // position Y
          coord.z,  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.radOfActA,   // radius
          0.0,   // number of subpoint values following this number
		*/
		int actABaseID = 60000;
		int id = actABaseID+myActAID;
		FileOps.addJSonID(id);
		if (bound) { 	// only saving actively linked ActAs
			try {
				if (!boundFil.coord.checkPt3D()) { return ""; } // make sure rod isn't in crazy state
				String lmPtXStr = String.format("%.2f",Env.simJSonsScale*ptOnBugInX.x);
				String lmPtYStr = String.format("%.2f",Env.simJSonsScale*ptOnBugInX.y);
				String lmPtZStr = String.format("%.2f",Env.simJSonsScale*ptOnBugInX.z);
				String actinPtXStr = String.format("%.2f",Env.simJSonsScale*ptOnFilInX.x);
				String actinPtYStr = String.format("%.2f",Env.simJSonsScale*ptOnFilInX.y);
				String actinPtZStr = String.format("%.2f",Env.simJSonsScale*ptOnFilInX.z);
				String actADStr = String.format("%.2f",Env.simJSonsScale*2*Env.radOfActA);
				// Assemble serialization...
				String actAString = "1001,"+id+",6,";
				actAString += "0.0,0.0,0.0,";//lmPtXStr + "," + lmPtYStr + "," + lmPtZStr + ",";
				actAString += "0.0,0.0,0.0,";
				actAString += actADStr + ",";
				actAString += "6.0,";
				actAString += lmPtXStr+","+lmPtYStr+","+lmPtZStr+",";
				actAString += actinPtXStr+","+actinPtYStr+","+actinPtZStr+",";
				return actAString;
			} catch (NullPointerException npe) { return ""; }
				
		} else { return "";}
	}
	
	
}


