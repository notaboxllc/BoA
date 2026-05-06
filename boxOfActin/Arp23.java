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

import com.sun.j3d.utils.geometry.Primitive;
import com.sun.j3d.utils.geometry.Sphere;
import com.sun.j3d.utils.universe.*;

import edu.cornell.lassp.houle.RngPack.RanMT;

import javax.media.j3d.*;
import javax.vecmath.*;

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
	Pt3D relaxDUVec = new Pt3D();	// what daughter uVec should be, in mother's body-fixed frame... set at branch formation
	Pt3D curDUVec = new Pt3D();		// what daughter uVec should be in fixed-frame... calculated each time step
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
	
	// for Java3D
	boolean farAway = false;
	//static Pt3D farPt = new Pt3D(1e6,1e6,1e6);
	static Pt3D farPt = new Pt3D();
	BranchGroup G = new BranchGroup();
	TransformGroup g3d = new TransformGroup();
	Transform3D t3d = new Transform3D();
	Appearance a = new Appearance();
	Vector3d coordVec3d = new Vector3d();
	LineArray linkLine;
	Shape3D linkShape;
	boolean graphicsMade = false;
	static boolean showArpLink = false;
	
	public Arp23 (Pt3D pt1, Pt3D pt2) {  // instantiate from QK file
		this.momPt.copy(pt1);
		addArp23(this);
	}
	
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
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*arp23Ct/numThreads;	// divide the job amongst threads
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
		G = null;
		linkLine = null;   
		linkShape = null;
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
		relaxDUVec.XTox(motherFil,daughterFil.uVec);

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
		momPt.add(motherFil.end1,momLoc,motherFil.uVec);
		endDisplacement = Pt3D.ptDist(momPt,daughterFil.end1);
		displacementVec.sub(daughterFil.end1,momPt);
		
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
		daughterFil.incForceSum(forceVec,daughterFil.end1);
		//System.out.println("force on daughter = " + curForceMag);
	}
	
	public void applyTorsionForce () {
		// rotational spring which works to align attached filament segments
		// need to know whether trying to align in same, or opposite, orientations
	
		double dotVecs;
		double angTween;
		torsionVec.cross(curDUVec,daughterFil.uVec);
		torsionVec.unitVec();
		dotVecs = Pt3D.Dot(curDUVec,daughterFil.uVec);
		if (dotVecs > 1.0) { dotVecs = 1.0; }
		angTween = Math.acos(dotVecs);
	
		
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
					theArp23s[i].removeGraphics();
					addInactive(theArp23s[i]);
				}	
			}
			catch (NullPointerException npe)
			{ System.out.println("null pointer exception in Arp23.setInactiveArp23s!");}
		}
	}		
	
	public void removeGraphics () {
		G.detach();
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
		
	public void setPtsFromQKFile (Pt3D newPt1, Pt3D newPt2) {
		if (newPt1 != null & newPt2 != null) {
			momPt.copy(newPt1);
		} else {
			momPt.copy(Env.farfarAway);
		}
	}  
	
	public void setGraphicsCapabilities () {
		g3d.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
		g3d.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
		g3d.setCapability(TransformGroup.ALLOW_CHILDREN_EXTEND);
		g3d.setCapability(TransformGroup.ALLOW_CHILDREN_WRITE);
		g3d.setCapability(TransformGroup.ALLOW_CHILDREN_READ);
		
		G.setCapability(BranchGroup.ALLOW_DETACH);
		G.setCapability(TransformGroup.ALLOW_CHILDREN_EXTEND);
		G.setCapability(TransformGroup.ALLOW_CHILDREN_WRITE);
		G.setCapability(TransformGroup.ALLOW_CHILDREN_READ);
		
		a.setCapability(Appearance.ALLOW_LINE_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_COLORING_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_POLYGON_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_WRITE);
		a.setCapability(Appearance.ALLOW_MATERIAL_WRITE);
	}
	
	public void makeGraphics () {
		setGraphicsCapabilities();
		// for testing... dot showing relaxed location of daughterfil tip
		Color3f dotColor = new Color3f(Color.WHITE);
		Material m = new Material (dotColor,dotColor,dotColor,dotColor,128f);
		a.setMaterial(m);
		Sphere dotSphere = new Sphere(0.004f,Sphere.GENERATE_NORMALS, Env.nodeTessalation, a);
		dotSphere.setCapability(Sphere.ALLOW_BOUNDS_READ);
		dotSphere.setCapability(Sphere.ALLOW_BOUNDS_WRITE);
		// capabilities for graphics objects
		G.setCapability(BranchGroup.ALLOW_DETACH);
		
		Color3f linkColor = new Color3f(1.0f,1.0f,1.0f);
		ColoringAttributes cA = new ColoringAttributes(linkColor, ColoringAttributes.FASTEST);
		Appearance linkApp = new Appearance();
		linkApp.setColoringAttributes(cA);
		
		// line array
		linkLine = new LineArray(2,LineArray.COORDINATES);
		linkLine.setCapability(LineArray.ALLOW_COORDINATE_WRITE);
		try {
			linkLine.setCoordinate(0,momPt);
			linkLine.setCoordinate(1,daughterFil.end1);
		} catch (NullPointerException npe) { System.out.println ("null pointer exception in Arp23.makeGraphics");}
		linkShape = new Shape3D();
		linkShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		linkShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		linkShape.setGeometry(linkLine);
		linkShape.setAppearance(linkApp);
		
		if (showArpLink) { 
			G.addChild(linkShape);
			g3d.addChild(dotSphere);
			g3d.setTransform(t3d);
			G.addChild(g3d);
		}
	
		graphicsMade = true;
	}
	
	public void updateGraphics () {
		if (!showArpLink) { return; }	
		curDTipLoc.copyToVector3d(coordVec3d);
		t3d.setTranslation(coordVec3d);
		g3d.setTransform(t3d);	
		if (active) {
			linkLine.setCoordinate(0,momPt);
			linkLine.setCoordinate(1,daughterFil.end1);
			linkShape.setGeometry(linkLine);
			farAway = false;
		} else {
			if (farAway) { return; }
			linkLine.setCoordinate(0,farPt);
			linkLine.setCoordinate(1,farPt);
			linkShape.setGeometry(linkLine);
			farAway = true;
		}
	}
	
	public Node getGraphicsNode () {
		if (!graphicsMade) { makeGraphics();}
		updateGraphics();
		return G;
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
          coord.x,  // position X
          coord.y,  // position Y
          coord.z,  // position Z  
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
			Pt3D arpEnd1Pt = Pt3D.Add(motherFil.end1, momLoc, motherFil.uVec);
			Pt3D arpEnd2Pt = Pt3D.Add(arpEnd1Pt, Env.radOfCap,daughterFil.uVec);
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


