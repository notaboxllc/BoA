package boxOfActin;

/**
 * Arp23.java
 *
 *
 */



import java.awt.*;

import javax.swing.*;

import java.lang.Math.*;

import boxOfActin.FilLink.RetObj;
import boxOfActin.FilLink.XLinkThreads;
import ec.util.MersenneTwisterFast;

import edu.cornell.lassp.houle.RngPack.RanMT;

public class Arp23 {
	static final int maxArp23s = 10000;
	static Arp23 [] theArp23s = new Arp23[maxArp23s];
	static Arp23 [] theArp23s_inactive = new Arp23[maxArp23s];
	static int arp23Ct = 0;
	static int arp23Ct_inactive = 0;
	static int filLinkRenderCt = 0;
	static double restLength = Env.actinMonoRadius/2;	// nm  tolerance in location between filament endpoints
	FilSegment motherFil,daughterFil; 
	double momLoc;	// location on mother filament of arp2/3
	Pt3D momPt = new Pt3D();
	Pt3D relaxDUVec = new Pt3D();	// what daughter uVecAsPt3D() should be, in mother's body-fixed frame... set at branch formation
	Pt3D curDUVec = new Pt3D();		// what daughter uVecAsPt3D() should be in fixed-frame... calculated each time step
	Pt3D curDTipLoc = new Pt3D(); // where tip of daughter filament should be
	int arp23Num;
	double branchAngOffMotherYAxis;
	boolean active = false;
	double endDisplacement;
	ValueTracker forceMag = new ValueTracker(Env.filLinkForcesToAve);
	ValueTracker torqueMag = new ValueTracker(Env.filLinkForcesToAve);
	double simTimeFormed;
	Pt3D displacementVec = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D forceVec = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RCrossF = new Pt3D();
	boolean removeMe = false;
	static int arpJSonIDCounter = 0; // used for making unique Simularium JSon Ids only
	
	// multithreading
	static Arp23Threads arp23Threads = new Arp23Threads();
	
	boolean farAway = false;
	static Pt3D farPt = new Pt3D();
	static boolean showArpLink = false;
	
	public Arp23 (FilSegment momFil, double momLoc, FilSegment daughterFil) {
		set(momFil,momLoc,daughterFil);
		addArp23(this);
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
	
	static class Arp23Threads extends ThreadSet {
		Arp23Threads () {
			super (Env.numArp23Threads, "Arp23 Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.xLinkStart: // for now, arp2/3 calculations are concurrent with crosslinkers
					if (arp23Ct == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*arp23Ct/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.xLinkStop:
					if (arp23Ct == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.xLinkStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (theArp23s[i] == null) { break; }		// protects from null pointer exception when we have no FilLinks
						if (theArp23s[i].active) { theArp23s[i].enforceFilLink(); }
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		motherFil = null;
		daughterFil = null;
		momPt = null;
		forceMag = null;
		torqueMag = null;
		displacementVec = null;
		torsionVec = null;
	}
	
	synchronized static Arp23 newArpBranch (FilSegment mFil, double mLoc, FilSegment dFil) {	
		Arp23 arp;
		if (arp23Ct_inactive > 0) {
			arp = theArp23s_inactive[arp23Ct_inactive-1];
			arp23Ct_inactive--;
			arp.set(mFil,mLoc,dFil);
		} else { 
			arp = new Arp23(mFil,mLoc,dFil);
		}
		Env.registerArp(mFil);
		return arp;
	}
	
	public void set (FilSegment mFil, double bLoc, FilSegment dFil) {
		motherFil = mFil;
		daughterFil = dFil;
		daughterFil.motherFil = motherFil;  // need this if want to connect brownian motion
		momLoc = bLoc;
		
		updatePts();
		simTimeFormed = Env.simulationTime;
		
		// store orientation of branch relative to body-fixed mother coordinate system.. fixed for life of FilLink
		relaxDUVec.XTox(motherFil,daughterFil.uVecAsPt3D());

		active = true;
	}
	
	public void reSet (FilSegment mFil, double bLoc, FilSegment dFil) {
		motherFil = mFil;
		daughterFil = dFil;
		daughterFil.motherFil = motherFil;  // need this if want to connect brownian motion
		momLoc = bLoc;
		active = true;
	}
	
	public void unSet () {
		motherFil = null;
		daughterFil.motherFil = null; 
		daughterFil = null;
		active = false;
	}
	
	public void updateMother (FilSegment mom) {
		motherFil = mom;
		daughterFil.motherFil = motherFil;
	}
	
	public void updateBranchLoc (double newLoc) {
		momLoc = newLoc;
		if (momLoc < 0) { active = false; }
	}
	
	
	public void updatePts () {
		momPt.add(motherFil.end1AsPt3D(),momLoc,motherFil.uVecAsPt3D());
		endDisplacement = Pt3D.ptDist(momPt,daughterFil.end1AsPt3D());
		displacementVec.sub(daughterFil.end1AsPt3D(),momPt);
		
		curDUVec.xToX(motherFil, relaxDUVec);
		curDTipLoc.add(momPt,daughterFil.length,curDUVec);
	}
	
	public void updateArp23Links () {
		// conditions for Arp23 link dissolution, either mother or daughter filsegment is leaving sim.
		if ((motherFil == null) | (motherFil.removeMe)) { active = false; return; }
		if ((daughterFil == null) | (daughterFil.removeMe)) { active = false; return; }
		//if ((momLoc < 0) | (momLoc > motherFil.length)) { active = false; return; } // if depoly past branch
		
		updatePts();
	}
	
	public void applyForces() {
		applyTransForce();
		applyTorsionForce(); 
	}
	
	public void applyTransForce () {
		// strains
		//double curStretchDist = endDisplacement-restLength;
		//if (curStretchDist < 0) { curStretchDist = 0; }
		
		//forces and accompanying torques
		double fracMove = 2.0;
		double curForceMag= (fracMove*1.0e-6*endDisplacement/Env.deltaT.getValue())/(1/motherFil.bTransGam.y+1/daughterFil.bTransGam.x);
		//forceMag.registerValue(curForceMag);
		forceVec.scale(curForceMag,displacementVec);
		motherFil.incForceSum(forceVec,momPt);
			
		forceVec.reverse();
		daughterFil.incForceSum(forceVec,daughterFil.end1AsPt3D());
		//System.out.println("force on daughter = " + curForceMag);
	}
	
	public void applyTorsionForce () {
		// rotational spring which works to align attached filament segments
		// need to know whether trying to align in same, or opposite, orientations
	
		double dotVecs;
		double angTween;
		torsionVec.cross(curDUVec,daughterFil.uVecAsPt3D());
		torsionVec.unitVec();
		dotVecs = Pt3D.Dot(curDUVec,daughterFil.uVecAsPt3D());
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		if (dotVecs < -1.0) { dotVecs = -1.0; }
		angTween = Pt3D.fastAcos(dotVecs);
	
		
		double curTorqueMag = Env.arpTorqSpring.getValue()*angTween;
		
		if (torsionVec.checkPt3D()) {
			torqueMag.registerValue(curTorqueMag);
			torsionVec.scale(torqueMag.averageVal());
			motherFil.incTorqueSum(torsionVec);
			
			torsionVec.scale(-1);
			daughterFil.incTorqueSum(torsionVec);
		} else {
			System.out.println ("Crazy torque in Arp23.applyTorsionForce()");
		}

	}
	
	public void enforceFilLink () {
		updateArp23Links();
		if (active) { 
			applyForces();
		}
	}
	
	synchronized static void addArp23 (Arp23 addMe) {
		theArp23s[arp23Ct] = addMe;
		theArp23s[arp23Ct].arp23Num = arp23Ct;
		arp23Ct++;
	}
	
	synchronized static void addInactive (Arp23 addMe) {
		theArp23s_inactive[arp23Ct_inactive] = addMe;
		arp23Ct_inactive++;
	}
	
	synchronized static void setInactiveArp23s () {
		arp23Ct_inactive = 0;
		for (int i=0;i<arp23Ct;i++) {
			try {
				if (!theArp23s[i].active) {
					theArp23s[i].cleanUpPointers();
					addInactive(theArp23s[i]);
				}	
			}
			catch (NullPointerException npe)
			{ System.out.println("null pointer exception in Arp23.setInactiveArp23s!");}
		}
	}		
	
	public void cleanUpPointers() {
		try {
			motherFil.removeArp23(this);
		} catch (NullPointerException npe) {}
		
		try {
			daughterFil.motherFil = null;
		} catch (NullPointerException npe) {}
		
		daughterFil = null;
		motherFil = null;
	}
	
	public static void removeAll () {
		for (int i=0;i<arp23Ct;i++) {
			theArp23s[i].active = false;
		}
		setInactiveArp23s();
		arp23Ct = 0;
		arp23Ct_inactive = 0; // try.. why are links working every other restart only
	}
		
	
	public static int getNumberActiveArps () {
		int activeArpCt = 0;
		for (int i=0;i<arp23Ct;i++) {
			if (theArp23s[i].active) {
				activeArpCt++;
			}	
		
		}
		return activeArpCt;
	}
	
	public String getJSonString () {
		/* Format for Arp2/3 JSON Serialization for Simularium
          1000.0,// visualization type : default sphere
          50000+arpCounter,   // agent instance ID
          5,   	 // agent type ID --ARP2/3
          getCoordX(),  // position X
          getCoordY(),  // position Y
          getCoordZ(),  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.radOfArp,   // radius
          0.0,   // number of subpoint values following this number
		*/
		// id number
		if (!active) return ""; // do nothing if not an active Arp23
		int arpBaseID = 50000;
		int id = arpBaseID+arpJSonIDCounter;
		FileOps.addJSonID(id);
		arpJSonIDCounter++;
		
		try {
			Pt3D arpEnd1Pt = Pt3D.Add(motherFil.end1AsPt3D(), momLoc, motherFil.uVecAsPt3D());
			Pt3D arpEnd2Pt = Pt3D.Add(arpEnd1Pt, Env.radOfCap,daughterFil.uVecAsPt3D());
			String arpEnd1XStr = String.format("%.2f",Env.simJSonsScale*arpEnd1Pt.x);
			String arpEnd1YStr = String.format("%.2f",Env.simJSonsScale*arpEnd1Pt.y);
			String arpEnd1ZStr = String.format("%.2f",Env.simJSonsScale*arpEnd1Pt.z);
			String arpEnd2XStr = String.format("%.2f",Env.simJSonsScale*arpEnd2Pt.x);
			String arpEnd2YStr = String.format("%.2f",Env.simJSonsScale*arpEnd2Pt.y);
			String arpEnd2ZStr = String.format("%.2f",Env.simJSonsScale*arpEnd2Pt.z);
			String arpDStr = String.format("%.2f",Env.simJSonsScale*2*Env.radOfARP);
			// Assemble serialization...
			String arpString = "1000,"+id+",5,";
			arpString += arpEnd1XStr + "," + arpEnd1YStr + "," + arpEnd1ZStr + ",";
			arpString += "0.0,0.0,0.0,";
			arpString += arpDStr + ",";
			arpString += "0.0,";
			//arpString += arpEnd1XStr+","+arpEnd1YStr+","+arpEnd1ZStr+",";
			//arpString += arpEnd2XStr+","+arpEnd2YStr+","+arpEnd2ZStr+",";
			return arpString;
		} catch (NullPointerException npe) {
			return "";
		}
	}
	
}


