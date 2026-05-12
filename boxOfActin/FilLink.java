package boxOfActin;
/**
 * FilLink.java
 *
 *
 */


/*
	FilLink .... the links between filaments
*/

import java.awt.*;
import javax.swing.*;
import java.lang.Math.*;
import java.util.concurrent.ThreadLocalRandom;

import ec.util.MersenneTwisterFast;
import edu.cornell.lassp.houle.RngPack.RanMT;

public class FilLink {
	static final int maxFilLinks = 100000;
	static FilLink [] filLinks = new FilLink[maxFilLinks];
	static FilLink [] filLinks_inactive = new FilLink[maxFilLinks];
	static int filLinkCt = 0;
	static int filLinkCt_inactive = 0;
	static int filLinkRenderCt = 0;
	static double restLength = 0.0125;	// nm
	FilSegment fil1,fil2; 
	double loc1,loc2;
	Pt3D pt1 = new Pt3D();
	Pt3D pt2 = new Pt3D();
	boolean active = false;
	double linkLength;
	ValueTracker forceMag = new ValueTracker(Env.filLinkForcesToAve);
	ValueTracker torqueMag = new ValueTracker(Env.filLinkForcesToAve);
	ValueTracker strainTrack = new ValueTracker(Env.filLinkStrainToAve);
	double simTimeFormed;
	Pt3D linkVec = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D forceVec = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RCrossF = new Pt3D();
	int filLinkNum;
	boolean removeMe = false;
	boolean orientSame = true;
	
	// multithreading
	static XLinkThreads xLinkThreads = new XLinkThreads();
	//MersenneTwisterFast myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));
	
	boolean farAway = false;
	static Pt3D farPt = new Pt3D();
	
	public FilLink (FilSegment fil1, double loc1, FilSegment fil2, double loc2) {
		set(fil1,loc1,fil2,loc2);
		addFilLink(this);
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
	
	static class XLinkThreads extends ThreadSet {
		XLinkThreads () {
			super (Env.numXLinkThreads, "XLink Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.xLinkStart:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*filLinkCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.xLinkStop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.xLinkStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (filLinks[i] == null) { break; }		// protects from null pointer exception when we have no FilLinks
						if (filLinks[i].active) { filLinks[i].enforceFilLink(); }
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		fil1 = null;
		fil2 = null;
		pt1 = null;
		pt2 = null;
		forceMag = null;
		torqueMag = null;
		strainTrack = null;
		linkVec = null;
		torsionVec = null;
	}
	
	synchronized static void makeLink (FilSegment fil1, double loc1, FilSegment fil2, double loc2) {
		if (filLinkCt_inactive > 0) {
			FilLink lnk = filLinks_inactive[filLinkCt_inactive-1];
			filLinkCt_inactive--;
			lnk.set(fil1, loc1, fil2, loc2);
		} else {
			new FilLink(fil1,loc1,fil2,loc2);
		}
	}
	
	public void set (FilSegment fil1, double loc1, FilSegment fil2, double loc2) {
		this.fil1 = fil1;
		this.fil2 = fil2;
		this.loc1 = loc1;
		this.loc2 = loc2;
		updatePts();
		simTimeFormed = Env.simulationTime;
		
		// store orientation.. fixed for life of FilLink
		double vecDot = Pt3D.Dot(fil1.uVec,fil2.uVec);
		double angTween = Math.acos(vecDot);
		//System.out.println ("initial ang tween is " + angTween);
		if (angTween > Math.PI/2) { orientSame = false; }
		active = true;
	}
	
	public void updatePts () {
		pt1.add(fil1.end1,loc1,fil1.uVec);
		pt2.add(fil2.end1,loc2,fil2.uVec);
		linkLength = Pt3D.ptDist(pt1, pt2);
		linkVec.sub(pt2,pt1);
	}
	
	public void updateFilLink () {
		// conditions for link dissolution
		if ((fil1 == null) | (fil1.removeMe)) { active = false; return; }
		if ((fil2 == null) | (fil2.removeMe)) { active = false; return; }
		
		// angle has grown too large
		//if (angleTooLarge()) { active = false; return; }
		
		// update loc1 and loc2 if minus end of filsegments has de/polymerized
		loc1 += fil1.minusEndDelta*Env.actinMonoRadius;
		loc2 += fil2.minusEndDelta*Env.actinMonoRadius;
		// more conditions for link dissolution
		if ((loc1 < 0) | (loc1 > fil1.length)) { active = false; return; }
		if ((loc2 < 0) | (loc2 > fil2.length)) { active = false; return; }
		
		updatePts();
	}
	
	public boolean ckLinkBreak() {
		double aveStrain = strainTrack.averageVal();
		
		double releaseProb = (Env.linkOffConst.getValue() + Env.linkOffCoeff.getValue()*Math.exp(aveStrain*Env.linkOffExp.getValue()))*Env.deltaT.getValue();
		if (ThreadLocalRandom.current().nextDouble() < releaseProb) {
			active = false;
			return true;
		}
		return false;
	}
	
	public void applyForces() {
		applyTransForce();
		if (Env.filLinkTorqSpring.isActive()) { applyTorsionForce(); }
	}
	
	public void applyTransForce () {
		// strains
		double curStretchDist = linkLength-restLength;
		if (curStretchDist < 0) { curStretchDist = 0; }
		strainTrack.registerValue(curStretchDist/restLength);
		if (ckLinkBreak()) return;
		
		//forces and accompanying torques
		double maxLinks = Math.max(fil1.getLinkCt(), fil2.getLinkCt());
		maxLinks = Math.max(maxLinks,1);
		double fracMove = 0.4/maxLinks;
		//double curForceMag = cenD*Env.filLinkSpring.getValue();
		double curForceMag= (fracMove*1.0e-6*curStretchDist/Env.deltaT.getValue())/(1/fil1.bTransGam.x+1/fil2.bTransGam.x);
		//forceMag.registerValue(curForceMag);
		forceVec.scale(curForceMag,linkVec);
		fil1.incForceSum(forceVec,pt1);
		
		forceVec.reverse();
		fil2.incForceSum(forceVec,pt2);
	}
	
	public void applyTorsionForce () {
		// rotational spring which works to align attached filament segments
		// need to know whether trying to align in same, or opposite, orientations
	
		double dotVecs;
		double angTween;
		if (orientSame) {
			torsionVec.cross(fil1.uVec,fil2.uVec);
			torsionVec.unitVec();
			dotVecs = Pt3D.Dot(fil1.uVec,fil2.uVec);
			if (dotVecs > 1.0) { dotVecs = 1.0; }
			angTween = Math.acos(dotVecs);
		} else {
			torsionVec.cross(fil1.uVec,fil2.uVecR);
			torsionVec.unitVec();
			dotVecs = Pt3D.Dot(fil1.uVec,fil2.uVec);
			if (dotVecs > 1.0) { dotVecs = 1.0; }
			angTween = Math.abs(Math.acos(dotVecs)-Math.PI);
		}
		
		
		//System.out.println ("OrientSame is " + orientSame + "  DotVecs is " + dotVecs + " and angTween is " + angTween);
		double curTorqueMag = Env.filLinkTorqSpring.getValue()*angTween;
		
		if (torsionVec.checkPt3D()) {
			torqueMag.registerValue(curTorqueMag);
			torsionVec.scale(torqueMag.averageVal());
			fil1.incTorqueSum(torsionVec);
			
			torsionVec.scale(-1);
			fil2.incTorqueSum(torsionVec);
		} else {
			System.out.println ("Crazy torque in FilLink.applyTorsionForce()");
		}

	}
	
	public void registerWithFilSegments() {
		fil1.registerFilLink(loc1,fil2);
		fil2.registerFilLink(loc2,fil1);
	}
		
	
	public boolean annealed() {
		if (fil1.filID == fil2.filID) { return true; } else { return false; }
	}
	
	/*public void checkForAnnealing () {
		if (Env.simulationTime - simTimeFormed > 1) { 
			FilSegment.annealSegments(fil1, fil1.end2, fil2, fil2.end1);
		}
	}*/

	
	public void checkForAnnealing () {
		double ptD, cosAngTween;
		if (fil1.filAtEnd1 & fil1.filAtEnd2) { return; } // fil1 is interior, can't anneal
		if (fil2.filAtEnd1 & fil2.filAtEnd2) { return; } // fil2 is interior, can't anneal
		
		// check a mess (4) of different possibilities
		if (!fil1.filAtEnd2 & !fil1.nodeAtEnd2) {
			if (!fil2.filAtEnd1 & !fil2.nodeAtEnd1) {
				ptD = Pt3D.ptDist(fil1.end2, fil2.end1);
				if (ptD < Env.annealDist.getValue()) { 
					//System.out.println("1st Ck passed: ptD = " + ptD);
					cosAngTween = Pt3D.Dot(fil1.uVec, fil2.uVec);
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case1");
						FilSegment.annealSegments(fil1, fil1.end2, fil2, fil2.end1);
						//System.out.println ("Annealed!");
						this.active = false;
						return;
					}
				}
			}
			
			if (!fil2.filAtEnd2 & !fil2.nodeAtEnd2) {
				ptD = Pt3D.ptDist(fil1.end2,fil2.end2);
				if (ptD < Env.annealDist.getValue()) { 
					cosAngTween = Pt3D.Dot(fil1.uVec, fil2.uVecR);
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case2");
						FilSegment.annealSegments(fil1, fil1.end2, fil2, fil2.end2);
						//System.out.println ("Annealed!");
						this.active = false;
						return;
					}
				}
			}
		}
		
		if (!fil1.filAtEnd1 & !fil1.nodeAtEnd1) {
			if (!fil2.filAtEnd1 & !fil2.nodeAtEnd1) {
				ptD = Pt3D.ptDist(fil1.end1, fil2.end1);
				if (ptD < Env.annealDist.getValue()) { 
					cosAngTween = Pt3D.Dot(fil1.uVecR, fil2.uVec);
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case3");
						FilSegment.annealSegments(fil1, fil1.end1, fil2, fil2.end1);
						//System.out.println ("Annealed!");
						this.active = false;
						return;
					}
				}
			}
			
			if (!fil2.filAtEnd2 & !fil2.nodeAtEnd2) {
				ptD = Pt3D.ptDist(fil1.end1, fil2.end2);
				if (ptD < Env.annealDist.getValue()) { 
					cosAngTween = Pt3D.Dot(fil1.uVecR, fil2.uVecR);
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case4");
						FilSegment.annealSegments(fil1, fil1.end1, fil2, fil2.end2);
						//System.out.println ("Annealed!");
						this.active = false;
						return;
					}
				}
			}
		}
	}
	
	
	
	public static void enforceAllFilLinks () {
		// first set the linkCt to zero for each FilSegment
		FilSegment.zeroAllLinkCts();
		FilLink curFL;
		for (int i=0;i<filLinkCt;i++) {
			filLinks[i].enforceFilLink();
		}
	}
	
	public void enforceFilLink () {
		if (!annealed()) {
			updateFilLink();
			if (active) { 
				//if (Env.filamentsAnneal.isActive()) { curFL.checkForAnnealing(); }
				registerWithFilSegments();
				applyForces();
			}
		} else {
			active = false;
		}
	}
	
	synchronized static void addFilLink (FilLink addMe) {
		filLinks[filLinkCt] = addMe;
		addMe.filLinkNum = filLinkCt;
		filLinkCt++;
	}
	
	synchronized static void addInactive (FilLink addMe) {
		filLinks_inactive[filLinkCt_inactive] = addMe;
		filLinkCt_inactive++;
	}
	
	synchronized static void removeFilLink (FilLink rmMe) {
		int swapId = rmMe.filLinkNum;				// here we swap from end of list to keep a compact array of FilLinks
		filLinks[swapId] = filLinks[filLinkCt-1];
		filLinks[swapId].filLinkNum = swapId;
		rmMe.sepaku();
		filLinkCt--;
	}
	
	synchronized static void removeDeadFilLinks () {
		for (int i=0;i<filLinkCt;i++) {
			if (filLinks[i] == null) { break; }		// this means we've gotten to the end of our shortening list of things
			if (filLinks[i].removeMe) {
				removeFilLink(filLinks[i]);
			}
		}
	}
	
	synchronized static void setInactiveFilLinks () {
		filLinkCt_inactive = 0;
		for (int i=0;i<filLinkCt;i++) {
			try {
				if (!filLinks[i].active) {
				addInactive(filLinks[i]);
				}	
			}
			catch (NullPointerException npe)
			{}
		}
	}		
	
	public static void removeAll () {
		for (int i=0;i<filLinkCt;i++) {
			filLinks[i].active = false;
		}
		setInactiveFilLinks();
		filLinkCt = 0;
		filLinkCt_inactive = 0; // let's start with clean slate... been some linker weirdness after restart
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
	
	
	public String getJSonString () {
		/* Format for ActA JSON Serialization for Simularium
          1001.0,// visualization type : fiber
          70000+idnum,   // agent instance ID
          7,   	 // agent type ID --XLink
          coord.x,  // position X
          coord.y,  // position Y
          coord.z,  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.radOfActA,   // radius
          0.0,   // number of subpoint values following this number
		*/
		int actABaseID = 70000;
		int id = actABaseID+filLinkNum;
		FileOps.addJSonID(id);
		if (active) { 	// only render active crosslinks
			try {
				if (!pt1.checkPt3D() | !pt2.checkPt3D()) { return ""; } // make sure crosslink points are good, otherwise skip
				String pt1XStr = String.format("%.2f",Env.simJSonsScale*pt1.x);
				String pt1YStr = String.format("%.2f",Env.simJSonsScale*pt1.y);
				String pt1ZStr = String.format("%.2f",Env.simJSonsScale*pt1.z);
				String pt2XStr = String.format("%.2f",Env.simJSonsScale*pt2.x);
				String pt2YStr = String.format("%.2f",Env.simJSonsScale*pt2.y);
				String pt2ZStr = String.format("%.2f",Env.simJSonsScale*pt2.z);
				String xLinkDStr = String.format("%.2f",Env.simJSonsScale*2*Env.radOfActin);
				// Assemble serialization...
				String xLinkString = "1001,"+id+",7,";
				xLinkString += "0.0,0.0,0.0,";
				xLinkString += "0.0,0.0,0.0,";
				xLinkString += xLinkDStr + ",";
				xLinkString += "6.0,";
				xLinkString += pt1XStr+","+pt1YStr+","+pt1ZStr+",";
				xLinkString += pt2XStr+","+pt2YStr+","+pt2ZStr+",";
				return xLinkString;
			} catch (NullPointerException npe) { return ""; }
				
		} else { return "";}
	}
	
	
}


