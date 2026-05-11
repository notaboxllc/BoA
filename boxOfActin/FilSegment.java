package boxOfActin;
/**
 * FilSegment.java
 *
 *
 */


/*
	FilSegment .... the sections that form a filament
*/


import java.awt.*;

import javax.swing.*;

import java.lang.Math.*;
import java.util.Random;

public class FilSegment extends Thing {
	
	static FilSegment [] theFilSegments = new FilSegment [1000000];	// array holding all filament segments
	static int filSegmentCt = 0;
	static int filamentRenderCt = 0;	// for rendering only
	static int filSegRenderCt = 0;		// for rendering only
	static double monosize=Env.actinMonoDiam;  
	static double halfmono=Env.actinMonoRadius;
	static double radius = Env.actinWidth;		// (nm) radius of actin filament
	static final Object filSync = new Object();	// for synchronizing filament creation
	Object filLinkOSync = new Object();  // for synchronizing filLink bookkeeping
	
	// filSegments grouped by the filament they belong to... assign each a unique filament number
	static int filCt = 0; 		// tracks number of filaments
	static int filIDCt = 0;	// ever increasing id numbers for filaments
	int filID;			// all segments in the same filament share the same id number
	int filArrayPos;		// position in the static array of filsegments
	
	// empirical fit for viscous drags
	static final double aParallel = -0.20;  // approx to constant in damping for parallel motion
	static final double aOrthog = 0.84;		// ...for orthogonal motion
	static final double aTurning = -0.662; 	// ...for rotational motion
	
	// y-positions for ordered filaments
	static final double incY = 1.5*Env.actinMonoDiam;
	static double currentPosY = 0.5*incY;
	static double currentNegY = -0.5*incY;
	static boolean positiveY = true;
	static final double [] openY = new double [1000];
	static int openYCt = 0;
	
	Pt3D end1 = new Pt3D();
	Pt3D end2 = new Pt3D();
	// Pt3Ds for plasmid force calculations
	Pt3D F = new Pt3D();
	Pt3D Fopp = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RcrossF = new Pt3D();
	Pt3D toPlasmidUVec = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	
	// for filament distribution
	static Random lengthGen = new Random((long)Math.random());
	
	// for collision detection
	double xRange,yRange,zRange;
	
	// force effect on polymerization
	static double maxPolyForce;
	
	// cross-link locations and max
	static double minFilLinkSep = 2*Env.actinMonoDiam;
	double [] linkLocs = new double [2*Env.maxXLinksOnSeg.getIntValue()];
	int [] linkedTo = new int [2*Env.maxXLinksOnSeg.getIntValue()];
	int linkedToCt = 0;
	int linkCt = 0;
	
	// ActA
	boolean actAOn = false;	// reset every time-step, used in attenuating brownian motion in moveThing()
	
	// cofilin & ADP
	int cofilinCt = 0;
	double notADPRatio = 1.0;   // set to track ratio of monomer not in ADP state
	
	// hack for locking actin filaments to "other stuff" not in the simulation
	int numViscBlobs = 0;
	
	// Arp2/3 bookkeeping
	static double arpSeparation = 6*Env.actinMonoRadius;  // arp2/3s can't be closer together than this
	static int maxArpChildren = 40;
	FilSegment [] arpChildren = new FilSegment[maxArpChildren];
	Arp23 [] arp23s = new Arp23[maxArpChildren];
	double [] arpChildLoc = new double [maxArpChildren];
	boolean [] arpActive = new boolean [maxArpChildren];
	int arpChildCt = 0;
	FilSegment motherFil;
	boolean childOfArp23 = false;  // if true then first two monomers are not actin, but ARPs
	Pt3D randForcesInX = new Pt3D();
	Pt3D randTorquesInX = new Pt3D();
	
	// info about end states and segments
	boolean end2Capped = false;
	
	boolean filAtEnd1 = false;
	boolean filAtEnd2 = false;
	FilSegment end1Fil = null;
	FilSegment end2Fil = null;
	Pt3D ptAtEnd1;
	Pt3D ptAtEnd2;
	boolean end1LinkCkd = false;
	boolean end2LinkCkd = false;
	boolean end1TorqCkd = false;
	boolean end2TorqCkd = false;
	
	int end2LinkThingNumber;		// used in reading QK state... myThingNumber of end2 filsegment if any
	int end1NodeThingNumber;		// ditto
	int end2NodeThingNumber;		// ditto
	
	Pt3D linkUVec = new Pt3D();		// a recycled Pt3D for segment link force calculations
	Pt3D linkUVecR = new Pt3D();		// reverse direction of above
	Pt3D linkPt = new Pt3D();			// link point location... recycled
	ValueTracker end1ToPlasStrain = new ValueTracker(Env.nodeetherStrainToAverage);
	ValueTracker end2ToPlasStrain = new ValueTracker(Env.nodeetherStrainToAverage);
	//ValueTracker filTorque1Track = new ValueTracker(Env.filTorqueToAverage,ValueTracker.PT3D_TYPE);
	//ValueTracker filTorque2Track = new ValueTracker(Env.filTorqueToAverage,ValueTracker.PT3D_TYPE);
	//ValueTracker filLink1Track = new ValueTracker(Env.filLinkForcesToAve,ValueTracker.PT3D_TYPE);
	//ValueTracker filLink2Track = new ValueTracker(Env.filLinkForcesToAve,ValueTracker.PT3D_TYPE);
	double end2NodeForceThisStep = 0;
	ValueTracker end2NodeForce = new ValueTracker (Thing.biochemCheckInt);
	ValueTracker compressionTrack = new ValueTracker(Env.compressionStepsToTrack);
	ValueTracker end1SegAng = new ValueTracker(Env.segDistToTrack);
	ValueTracker end2SegAng = new ValueTracker(Env.segDistToTrack);
	ValueTracker end1SegDist = new ValueTracker(Env.segDistToTrack);
	ValueTracker end2SegDist = new ValueTracker(Env.segDistToTrack);
	
	int end1DetachCounter = 0;
	int end2DetachCounter = 0;
	int minusEndDelta = 0;		// keeps track of changes to minus end position, for changing binding location based on minus-end
	int plusEndDelta = 0;		// keeps track of changes to plus-end, for changing binding location based on plus-end
	
	Pt3D monInc=new Pt3D(),monOffset=new Pt3D(),evenStart=new Pt3D(),evenStop=new Pt3D(),oddStart=new Pt3D(),oddStop=new Pt3D();
	double end1AxialF = 0;
	double end2AxialF = 0;
	double end1TipC = 1e6; // large number for initial tip clearance of end1
	double end2TipC = 1e6; // large number for initial tip clearance of end2
	boolean end2NearArpFactor = false;  // use for deciding when to branch... hacky for now
	double fturn;
	double ftrans;
	double fnorm;
	Pt3D Fcoll = new Pt3D();	// recycled pt for force calculations
	Pt3D Tcoll = new Pt3D();	// recycled pt for torque calculations
	boolean lengthChanged = false;
	boolean nodeAtEnd1 = false;
	boolean nodeAtEnd2 = false;
	boolean globalNodeAtEnd1 = false;
	boolean globalNodeAtEnd2 = false;
	ProteinNode end1Node,end2Node;
	Pt3D forminVecInx = new Pt3D(); Pt3D forminVecInX = new Pt3D();
	Pt3D end1PAttachPt = new Pt3D(); Pt3D end2PAttachPt = new Pt3D();
	Pt3D end1PAttachPtInX = new Pt3D(); Pt3D end2PAttachPtInX = new Pt3D();
	Monomer minusMon, plusMon;
	Pt3D end1MonCenter = new Pt3D();
	Pt3D end2MonCenter = new Pt3D();
	Pt3D coordMonCenter = new Pt3D();
	Pt3D curMonStart = new Pt3D();
	Pt3D curMonStop = new Pt3D();

	double length;
	double l;
	double helixAng = 2*Math.PI*myPRNG.nextDouble();	// keeps track of the helix angle of minusMon.. starts randomly
	int monomerCt = 0;

	// graphics bookkeeping (primitives only; Java3D fields removed in Phase 1)
	boolean updateCylGraphicsFlag = false;
	double renderThicken = Env.filRenderThicken.getValue();
	double coordLineLength = 5*Env.actinMonoDiam;
	Pt3D xLineEndPt = new Pt3D();
	Pt3D yLineEndPt = new Pt3D();
	Pt3D zLineEndPt = new Pt3D();
	boolean coordSysOn = false;
	boolean plusCapMarkOn = false;

	public FilSegment (Pt3D initCoord, Pt3D initUVec, int filID) {
		super(initCoord);
		//synchronized (filSync) {
			uVec.copy(initUVec);
			yVec.randomUnitVec(myPRNG);
			monomerCt = Env.actinSeed.getIntValue();
			length = (monomerCt+1)*Env.actinMonoRadius;
			addFilSegment(this);
			setFilamentID(this, filID);
			calculateProperties();
			initialize();
			makeInitialMonomers();
			theBox.takeMonomer(monomerCt);
		//}
	}

	public FilSegment (Pt3D initCoord, Pt3D initUVec, int filID, int monomerCt, boolean fromFile) {
		super(initCoord);
		//synchronized (filSync) {
			uVec.copy(initUVec);
			yVec.randomUnitVec(myPRNG);
			this.monomerCt = monomerCt;
			length = (monomerCt+1)*Env.actinMonoRadius;
			addFilSegment(this);
			setFilamentID(this, filID);
			calculateProperties();
			initialize();
			makeInitialMonomers();
			if (!fromFile) {
				theBox.takeMonomer(monomerCt);
			}
		//}
	}
	
	public FilSegment (Pt3D initCoord, Pt3D initUVec, int monomerCt, FilSegment splitFromFil) {
		super(initCoord);
		//synchronized (filSync) {
			uVec.copy(initUVec);
			yVec.randomUnitVec(myPRNG);
			this.monomerCt = monomerCt;
			length = (monomerCt+1)*Env.actinMonoRadius;
			addFilSegment(this);
			setFilamentID(this, splitFromFil.filID);
			globalNodeAtEnd1 = splitFromFil.globalNodeAtEnd1;
			globalNodeAtEnd2 = splitFromFil.globalNodeAtEnd2;
			calculateProperties();
			initialize();
			setEnd1Links(splitFromFil, true);
			if (splitFromFil.filAtEnd2) {
				if (splitFromFil.ptAtEnd2 == splitFromFil.end2Fil.end1) { // same orientation
					setEnd2Links(splitFromFil.end2Fil, true);
					splitFromFil.end2Fil.setEnd1Links(this, true);
				} else {
					setEnd2Links(splitFromFil.end2Fil, false);
					splitFromFil.end2Fil.setEnd2Links(this, false);
				}
			}
			
			if (splitFromFil.nodeAtEnd2) { 				// transfer plasmid link
				transferEnd2Plasmid(splitFromFil,this);
			}
			
			//if (arpChildCt > 0) { transferArpChildren(splitFromFil); }
		//}
	}
	
	public void setFromQKInfo (Pt3D nuCoord, Pt3D nuAng, int mons) {
		coord.copy(nuCoord);
		uVec.copy(nuAng);
		monomerCt = mons;
		initialize();
		updateCylGraphicsFlag = true;
	}
	
	public void sepaku () {
		super.sepaku();
		filLinkOSync = null;
		end1 = null;
		end2 = null;
		F = null;
		Fopp = null;
		R = null;
		RcrossF = null;
		toPlasmidUVec = null;
		torsionVec = null;
		
		linkLocs = null;
		linkedTo = null;
		
		end1Fil = null;
		end2Fil = null;
		ptAtEnd1 = null;
		ptAtEnd2 = null;
		
		linkUVec = null;
		linkUVecR = null;
		linkPt = null;
		end1ToPlasStrain = null;
		end2ToPlasStrain = null;
		end2NodeForce = null;
		compressionTrack = null;
		end1SegAng = null;
		end2SegAng = null;
		end1SegDist = null;
		end2SegDist = null;
		//filTorque1Track = null;
		//filTorque2Track = null;
		//filLink1Track = null;
		//filLink2Track = null;
		
		monInc=null;
		monOffset=null;
		evenStart=null;
		evenStop=null;
		oddStart=null;
		oddStop=null;
		
		Fcoll = null;
		Tcoll = null;
		
		end1Node = null;
		end2Node = null;
		forminVecInx = null;
		forminVecInX = null;
		end1PAttachPt = null; 
		end2PAttachPt = null;
		end1PAttachPtInX = null; 
		end2PAttachPtInX = null;
		minusMon = null;
		plusMon = null;
		end1MonCenter = null;
		end2MonCenter = null;
		coordMonCenter = null;
		curMonStart = null;
		curMonStop = null;
		xLineEndPt = null;
		yLineEndPt = null;
		zLineEndPt = null;
	}
	
	public static void setBiophysValues () {
		maxPolyForce = Env.kTOverDelta*Math.log(Env.actinConc.getValue()/Env.actinCritConc);
	}
	
	public void calculateProperties () {
		// define the constants for motion of this rod in viscous medium
		// Remember that the dimensions we've been using are in micrometers so....
		// **WARNING** below a certain number of monomers, depending on values like aParallel, etc
		// the rod approximation will give NaN... hence the IF statement below
		int minMonomerCt = 30;
		double minLength;
		if (filAtEnd1 | filAtEnd2) { 
			minLength = Env.stdSegLength.getIntValue()*halfmono; 
		} else {
			minLength = minMonomerCt*halfmono;
		}
		double asIfLength = length;	// default, use actual length
		if (asIfLength < minLength) { asIfLength = minLength; }
		
		double asIfLengthM = 1.0e-6*asIfLength; // in meters
		double radiusM = radius*1.0e-6;
		double denomLogTerm = Math.log(asIfLengthM/(2*radiusM));	//dimensionless
		bTransGam.x = (2*Math.PI*Env.aeta.getValue()*asIfLengthM)/(denomLogTerm + aParallel);
		bTransGam.y = (4*Math.PI*Env.aeta.getValue()*asIfLengthM)/(denomLogTerm + aOrthog);
		bTransGam.z = bTransGam.y;
		bRotGam.x = 4*Math.PI*Env.aeta.getValue()*radiusM*radiusM*asIfLengthM;	// drag for turning about x
		bRotGam.y = (Math.PI*Env.aeta.getValue()*Math.pow(asIfLengthM,3))/(3*(denomLogTerm + aTurning));
		bRotGam.z = bRotGam.y;
		
		//viscous blob based drag modifications... a hack to simulate filaments binding to "other stuff" not explicit in the simulation
		//of all the schemes for fixing filaments in space, slowly, this is the one currently turned on and used for paper with Susanne Rafelski
		if (Env.useViscousBlob.isActive()) {
			//System.out.print("old bTransGam.x = " + bTransGam.x + " and blobAddition with " + numViscBlobs + " makes it "  );
			bTransGam.add(bTransGam,numViscBlobs,Env.blobTransGam);
			bRotGam.add(bRotGam,numViscBlobs,Env.blobRotGam);
			//System.out.println (bTransGam.x);

		}	
		
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
		
		/*if (!bTransGam.checkPt3D()) { talkln ("bTransGam is crazy for FilSegment"); }
		if (!bRotGam.checkPt3D()) { talkln ("bRotGam is crazy for FilSegment"); }
		if (!bTransDiff.checkPt3D()) { talkln ("bTransDiff is crazy for FilSegment"); }
		if (!bRotDiff.checkPt3D()) { talkln ("bRotDiff is crazy for FilSegment"); }
		*/
	
	}
	
	public void initialize () {
		// this method assumes the unit x and y vectors have been set (though maybe not orthogonal), or are unchanged
		// determine z-unit vectors, then reset y-unit vector to ensure orthogonality with uVec
		zVec.cross(uVec, yVec);
		zVec.unitVec();	// yVec comes in as random direction, so normalize this
		yVec.cross(zVec, uVec);
		// find the transformation matrices at this time step
		transMat ();
		// define opposite to uVec direction, used frequently
		uVecR.scale(-1,uVec);
		// length may have changed due to poly/depoly/split
		length=(monomerCt+1)*Env.actinMonoRadius;
		// refind the end points of the rod to make sure they meet length criteria
		end1.add(coord, -length/2, uVec);
		end2.add(coord, length/2, uVec);
		
		// for collision detection
		xRange = Math.abs(coord.x-end2.x);
		yRange = Math.abs(coord.y-end2.y);
		zRange = Math.abs(coord.z-end2.z);
	}
	
	public void makeInitialMonomers() {
		if (Env.noMonomersSimd.isActive()) { return; }
		Monomer.polymerize(null,this,Monomer.MINUSSEED, true);
		Monomer curMon = minusMon;
		for (int i=1;i<monomerCt;i++) {
			Monomer.polymerize(curMon,this,Monomer.PLUSEND, true);
			curMon = curMon.frontMon;
		}
	}
	
	public void translate (double dist, Pt3D vec) {
		coord.inc(dist,vec);
		end1.inc(dist,vec);
		end2.inc(dist,vec);
	}
	
	public void translateCoord (double dist, Pt3D vec) {
		coord.inc(dist,vec);
	}
	
	public void step () {
		// increment counters that control how often different bits are run
		collCheckCt++;
		
		if (Env.simulationTime < 0.01) updateCylGraphicsFlag = true; // ? investigate better way here
				
		/*if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			checkBugOrBoxCollision(); 		// these should add forces and torques to forceSum and torqueSum
			if (Env.simulationTime < 0.001) { checkForminBinding(); }
			
			collCheckCt = 0;
		}*/
		checkBugOrBoxCollision(); 		// these should add forces and torques to forceSum and torqueSum
		
		addLinkForces();				// if linked to other segments
		
		addTorsionSpringForces();		// bending rigidity proxy
		
		addNodeForces();				// calculate elastic forces to keep filament ends and bound plasmids together
	
		//setCompression();				// register compressive force in filament, if any
		
	}
	
	public void biochemStep () {
		biochemCheckCt++;
		
		if (Env.useViscousBlob.isActive() && length > Env.vBlobMinMons.getIntValue()*Env.actinMonoRadius) { this.viscousBlobSim(length, Env.biochemDeltaT.getValue()); }  // uses lengthChanged flag to signal recalculation of drag etc
		
		if (!Env.noMonomersSimd.isActive() && biochemCheckCt >= biochemCheckInt) {
			hydrolizeInFilaments();		// monomer-by-monomer hydrolysis and dissociate
			checkCofilinDissolve();		// if ratio of cofilin-bound monomers exceeds spec, dissolve
			
			if (!filAtEnd1) { end1BiochemSim (); }			// catastrophy, polymerization, and depolymerization simulations for end1
			if (!filAtEnd2) { end2BiochemSim (); }			// and for end2, both use lengthChanged flag
			
			biochemCheckCt = 0;
		}
		
		if (lengthChanged) {
			calculateProperties(); 	// calculate new drag coefficients, etc if length has changed
			initialize();			// calculate transformation matrices, etc given the new coordinates
			updateCylGraphicsFlag = true;
		}
		
		
		if (monomerCt >= 2*Env.stdSegLength.getIntValue()) { 
			splitSegment(this);
			calculateProperties();	// again if split
			initialize();
			updateCylGraphicsFlag = true;
		}	
		
		//*** joining broken with branched networks right now, but who really needs it anyway
		/*if (monomerCt <= Env.stdSegLength.getIntValue()/2) {
			joinSegments();
			updateCylGraphicsFlag = true;
		}*/
		
		
	}
	
	public void moveThing () {
		// Given the forces/torques at this time point... move with explicit Euler approximation to ODE solution
		
		// Work in coordinates aligned with the rod... transform forces and torques into body-fixed axis....
		bForceSum.XTox(this, forceSum);
		bTorqueSum.XTox(this, torqueSum);
		
		// add brownian force and torque... these are zero except at every chosen time-step
		if (!Env.brownianFilMotionOff) {
			double transScale,rotScale;
			if (motherFil == null) {	
				// trans
				transScale = Env.BTransCoeff.getValue();
				if (linkedToCt > 0) { transScale = transScale/(1 + Env.xLinkTransAttn.getValue()*linkedToCt); }
				if (actAOn) { 
					transScale *= bTransGam.y/Thing.lmBug.bTransGam.y; //Env.actATetherTransAttn.getValue(); 
					randForces.XTox(this,Thing.lmBug.randForcesInX);	// use bug random forces
				}
				bForceSum.inc(transScale,randForces);
				
				// rot
				rotScale = Env.BRotCoeff.getValue();
				if (linkedToCt > 0) { rotScale = rotScale/(1+ Env.xLinkRotAttn.getValue()*linkedToCt); }
				if (actAOn) { 
					rotScale *= bRotGam.y/Thing.lmBug.bRotGam.y; //Env.actATetherRotAttn.getValue(); 
					randTorques.XTox(this,Thing.lmBug.randTorquesInX);
				}
				if (!filAtEnd1 | !filAtEnd2) {			// only apply brownian torques to end filaments.. best matches expected angular correlations
					bTorqueSum.inc(rotScale,randTorques);
				}
			} else {  // use mother filaments thermal forces if I'm an Arp2/3 branch
				try {
					// trans 
					randForces.XTox(this,motherFil.randForcesInX);			// and finally back to body-fixed for this filament
					transScale = Math.min(1,(bTransGam.y/motherFil.bTransGam.y)); // ratio of translation drag coeff, but not greater than 1
					if (actAOn) { transScale *= Env.actATetherTransAttn.getValue(); }
					bForceSum.inc(transScale,randForces); // scale brownian forces copied from mother filament
					// rot
					randTorques.XTox(this,motherFil.randTorquesInX);
					rotScale = Math.min(1,(bRotGam.y/motherFil.bRotGam.y));  // ratio or rotational drag coeff but not greater than 1
					if (actAOn) { rotScale *= Env.actATetherRotAttn.getValue(); }
					bTorqueSum.inc(rotScale,motherFil.randTorques); // scale brownian torques copied from mother filament 
				} catch (NullPointerException npe) {
					talkln("catching null pointer exception trying to ~share brownian motion in FilSegment.moveThing()");
				}
			}
		}
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec
		
		// New Positions
		// the body-fixed angular velocities can just be transformed into fixed-frame velocities, and the coord updated
		veloc.xToX(this, bVeloc);
		coord.inc(Env.deltaT.getValue(),veloc);  // just position = velocity*time
		
		//deltaBAng.inc(Env.deltaT.getValue(),bAngVeloc);
		// to apply the body-fixed angular velocities, approximate new unit vector from arc of rotations.. good for small rotations
		// for uVec
		double uVecTransInZ = -bAngVeloc.y * Env.deltaT.getValue();	// arclength out at 1 micron
		double uVecTransInY = bAngVeloc.z * Env.deltaT.getValue();
		uVec.setVals(1,uVecTransInY,uVecTransInZ);	// in body-fixed, not a unit vector yet
		uVec.xToX(this);	// make in fixed-frame, not a unit vector yet
		uVec.unitVec();		// make a unit vector
		
		// for yVec 
		double yVecTransInX = - uVecTransInY;
		double yVecTransInZ = bAngVeloc.x * Env.deltaT.getValue();	// arclength at 1 micron
		yVec.setVals(yVecTransInX, 1, yVecTransInZ);
		yVec.xToX(this);
		yVec.unitVec();
		
		initialize();		
		
	}
	
	public void calcRandomForces () {  // override Thing.calRandomForces to account for sync'd brownian motion and to avoid wasting calculation of independent values
		if (motherFil == null) {
			super.calcRandomForces();
			if (arpChildCt > 0) {	// if this filSegment has branches that will ~share brownian motion then store this once
				randForcesInX.xToX(this,randForces);
				randTorquesInX.xToX(this,randTorques);
			}
		} else {  // just set brownian forces/torques to zero if sharing mother's, or cheaper still do nothing
			randForces.zero();
			randTorques.zero();
		}
	}
	

	
	public void joinSegments () {
		// determine which case and call appropriate joining method
		if (!filAtEnd1 & !filAtEnd2) { return; }  	// no one to join to
		
		FilSegment joinTo = null;
		boolean chooseEnd1Fil = filAtEnd1;
		if (filAtEnd1 & filAtEnd2) { 	// choose end2Fil if shorter...
			if (end2Fil.monomerCt < end1Fil.monomerCt) { chooseEnd1Fil = false; }
		}
		if (chooseEnd1Fil) {
			joinTo = end1Fil;
			if (ptAtEnd1 == end1Fil.end2) { 	// normal alignment
				joinSegs21(end1Fil,this);
				
			} else {
				joinSegs11(end1Fil,this);
			}
		} else {	// must be filAtEnd2
			joinTo = end2Fil;
			if (ptAtEnd2 == end2Fil.end1) { 	// normal alignment
				joinSegs12(end2Fil,this);
			} else {
				joinSegs22(end2Fil,this);
			}
		}
		if (joinTo != null) {
			joinTo.initialize();
			joinTo.calculateProperties();
		}
	}
	
	public static void joinSegs11 (FilSegment stayFil, FilSegment byeFil) {
		// case11:  stayFil.end1 linked to byeFil end1
		//talkln ("case11: joining filseg with " + byeFil.monomerCt + " to filseg with " + stayFil.monomerCt);
		// will take what's left of byeFil and add it to stayFil
		// transfer monomers
		byeFil.switchMonomerLinkDirection();	// affects minusMon and plusMon pointers
		stayFil.minusMon.backMon = byeFil.plusMon;	
		byeFil.plusMon.frontMon = stayFil.minusMon;
		stayFil.minusMon = byeFil.minusMon;	
		stayFil.minusMon.backMon = Monomer.minusGhost;
		// increment monomerCt of stayFil, and shift CM
		stayFil.monomerCt += byeFil.monomerCt;
		stayFil.coord.inc(byeFil.monomerCt*halfmono/2,stayFil.uVecR);
		// reassign graphics, if rendering
		Monomer curMon = byeFil.plusMon;
		if (Env.monomerGraphics) {
			while (curMon != Monomer.minusGhost) {
				curMon.reassignGraphics(byeFil, stayFil);
				curMon = curMon.backMon;
			}
		}
		// get rid of byeFil, reassign links if any
		byeFil.monomerCt = 0;
		byeFil.minusMon = Monomer.minusGhost; // remove pointers to real monomers from byeFil
		byeFil.plusMon = Monomer.plusGhost;
		cleanup(byeFil, true, false);
	}
	
	public static void joinSegs12 (FilSegment stayFil, FilSegment byeFil) {
		// case12:  usual orientation in reverse, stayFil.end2 linked to byeFil end1
		//talkln ("case12: joining filseg with " + byeFil.monomerCt + " to filseg with " + stayFil.monomerCt);
		// will take what's left of byeFil and add it to stayFil
		// transfer monomers
		stayFil.minusMon.backMon = byeFil.plusMon;	// link plusmon to byeFil.minusmon
		byeFil.plusMon.frontMon = stayFil.minusMon;
		stayFil.minusMon = byeFil.minusMon;	// declare that byeFils minusMon is new minusMon for stayfil
		stayFil.minusMon.backMon = Monomer.minusGhost;
		// increment monomerCt of stayFil, and shift CM
		stayFil.monomerCt += byeFil.monomerCt;
		stayFil.coord.inc(byeFil.monomerCt*halfmono/2,stayFil.uVecR);
		// reassign graphics, if rendering
		Monomer curMon = byeFil.plusMon;
		if (Env.monomerGraphics) {
			while (curMon != Monomer.minusGhost) {
				curMon.reassignGraphics(byeFil, stayFil);
				curMon = curMon.backMon;
			}
		}
		// get rid of byeFil, reassign links if any
		byeFil.monomerCt = 0;
		byeFil.minusMon = Monomer.minusGhost; // remove pointers to real monomers from byeFil
		byeFil.plusMon = Monomer.plusGhost;
		cleanup(byeFil, true, false);
	}
	
	public static void joinSegs21 (FilSegment stayFil, FilSegment byeFil) {
		// case21:  usual orientation, stayFil.end2 linked to byeFil end1
		//talkln ("case21: joining filseg with " + byeFil.monomerCt + " to filseg with " + stayFil.monomerCt);
		// will take what's left of byeFil and add it to stayFil
		// transfer monomers
		stayFil.plusMon.frontMon = byeFil.minusMon;	// link plusmon to byeFil.minusmon
		byeFil.minusMon.backMon = stayFil.plusMon;
		stayFil.plusMon = byeFil.plusMon;	// declare that byeFils plus mon is new plusmon for stayfil
		stayFil.plusMon.frontMon = Monomer.plusGhost;
		// increment monomerCt of stayFil, and shift CM
		stayFil.monomerCt += byeFil.monomerCt;
		stayFil.coord.inc(byeFil.monomerCt*halfmono/2,stayFil.uVec);
		// reassign graphics, if rendering
		Monomer curMon = byeFil.minusMon;
		if (Env.monomerGraphics) {
			while (curMon != Monomer.plusGhost) {
				curMon.reassignGraphics(byeFil, stayFil);
				curMon = curMon.frontMon;
			}
		}
		// get rid of byeFil, reassign links if any
		byeFil.monomerCt = 0;
		byeFil.minusMon = Monomer.minusGhost; // remove pointers to real monomers from byeFil
		byeFil.plusMon = Monomer.plusGhost;
		cleanup(byeFil, true, false);
	}
	
	public static void joinSegs22 (FilSegment stayFil, FilSegment byeFil) {
		// case22:  stayFil.end2 linked to byeFil end2
		//talkln ("case22: joining filseg with " + byeFil.monomerCt + " to filseg with " + stayFil.monomerCt);
		// will take what's left of byeFil and add it to stayFil
		// transfer monomers
		byeFil.switchMonomerLinkDirection();	// plusMon and minusMon pointers swapped as well
		stayFil.plusMon.frontMon = byeFil.minusMon;	
		byeFil.minusMon.backMon = stayFil.plusMon;
		stayFil.plusMon = byeFil.plusMon;	// declare that byeFils plus mon is new plusmon for stayfil
		stayFil.plusMon.frontMon = Monomer.plusGhost;
		// increment monomerCt of stayFil, and shift CM
		stayFil.monomerCt += byeFil.monomerCt;
		stayFil.coord.inc(byeFil.monomerCt*halfmono/2,stayFil.uVec);
		// reassign graphics, if rendering
		Monomer curMon = byeFil.minusMon;
		if (Env.monomerGraphics) {
			while (curMon != Monomer.plusGhost) {
				curMon.reassignGraphics(byeFil, stayFil);
				curMon = curMon.frontMon;
			}
		}
		// get rid of byeFil, reassign links if any
		byeFil.monomerCt = 0;
		byeFil.minusMon = Monomer.minusGhost; // remove pointers to real monomers from byeFil
		byeFil.plusMon = Monomer.plusGhost;
		cleanup(byeFil, true,false);
	}
	
	public void switchMonomerLinkDirection () { 
		Monomer [] mons = new Monomer [monomerCt];
		int monCt = 0;
		Monomer curMon = minusMon;
		while (curMon != Monomer.plusGhost) {
			mons[monCt] = curMon;
			monCt++;
			curMon = curMon.frontMon;
		}
		for (int i=0;i<monCt-1;i++) {
			mons[i].backMon = mons[i+1];
			mons[i+1].frontMon = mons[i];
		}
		//reverse plusMon and minusMon pointers
		plusMon = mons[0];
		minusMon = mons[monCt-1];
	}
	
	synchronized static void splitSegment (FilSegment splitFilSeg) {
		int halfSegCt = Env.stdSegLength.getIntValue();
		splitFilSeg.setFirstHalf(halfSegCt);
		double nextFilLength = (halfSegCt+1)*Env.actinMonoRadius;
		Pt3D nextFilCoord = Pt3D.Add(splitFilSeg.end2,0.5*nextFilLength-Env.actinMonoRadius,splitFilSeg.uVec);
		FilSegment nextFil = new FilSegment (nextFilCoord,splitFilSeg.uVec,halfSegCt,splitFilSeg);
		splitFilSeg.setEnd2Links(nextFil, true);
		if (!Env.noMonomersSimd.isActive()) { splitFilSeg.transferMons (splitFilSeg.monomerCt,nextFil); }
		splitFilSeg.transferArpChildren(nextFil);
	}
	
	public void setFirstHalf (int halfSegCt) {
		monomerCt = monomerCt - halfSegCt;
		length = (monomerCt+1)*Env.actinMonoRadius;
		coord.add(end1,0.5*length,uVec);
		end2.add(end1, length, uVec);
	}
	
	public void transferArpChildren (FilSegment targetFil) {
		for (int i=0;i<arpChildCt;i++) {
			if (arpActive[i]) {
				//talk("arpChildLoc" + i + " = " + arpChildLoc[i] + " ;  while length = " + length);
				if (arpChildLoc[i] > length) {
					//talkln("... moving");
					targetFil.addExistingArp(arpChildren[i],arpChildLoc[i]-length,arp23s[i]);
					arpChildren[i] = null;
					arp23s[i] = null;
					arpActive[i] = false;
				} else {
					//talkln (" ");
				}
				
			}
		}
	}
	
	public void transferMons (int startMonLoc, FilSegment targetFil) {
		// get handle to starting mon
		Monomer startMon = minusMon;
		
		for (int i=0;i<startMonLoc;i++) {
			startMon = startMon.frontMon;
		}
		targetFil.plusMon = plusMon;						// take over this filaments current plusMon
		targetFil.plusMon.frontMon = Monomer.plusGhost;
		plusMon = startMon.backMon;
		plusMon.frontMon = Monomer.plusGhost;
		targetFil.minusMon = startMon;
		targetFil.minusMon.backMon = Monomer.minusGhost;
		
		Monomer curMon = startMon;
		if (Env.monomerGraphics) {
			while (curMon != Monomer.plusGhost) {
				curMon.reassignGraphics(this, targetFil);
				curMon = curMon.frontMon;
			}
		}
	}
	
	public static void annealSegments (FilSegment seg1, Pt3D pt1, FilSegment seg2, Pt3D pt2) {
		//talkln ("In annealSegments");
		// four cases
		if (pt1.equals(seg1.end2) & pt2.equals(seg2.end1)) {
			seg1.setEnd2Links(seg2,true);
			seg2.setEnd1Links(seg1,true);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
		
		if (pt1.equals(seg1.end1) & pt2.equals(seg2.end2)) {
			seg1.setEnd1Links(seg2,true);
			seg2.setEnd2Links(seg1,true);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
		
		if (pt1.equals(seg1.end2) & pt2.equals(seg2.end2)) {
			seg1.setEnd2Links(seg2,false);
			seg2.setEnd2Links(seg1,false);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
		
		if (pt1.equals(seg1.end1) & pt2.equals(seg2.end1)) {
			seg1.setEnd1Links(seg2,false);
			seg2.setEnd1Links(seg1,false);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
			
	}
	
	public double getPolyRateEnd1 () {
		double rate;
		rate = Env.kATPOn1.getValue(); 
		return rate;
	}
	
	public double getNonHydroPolyRateEnd1 () {
		double rate;
		rate = Env.kATPOn1NonHydro.getValue();
		return rate;
	}
	
	/*public double getPolyRateEnd2 () {
		double rate;
		if (nodeAtEnd2) { 
			rate = Env.kATPOn2WithFormin.getValue(); 
		} else { rate = Env.kATPOn2.getValue(); }
		return rate;
	}*/
	
	public double getPolyRateEnd2 () {
		double rate;
		if (nodeAtEnd2) { 
			double rateMod = 1;
			double nodeForce = end2NodeForce.averageVal();
			if (nodeForce < 0) { 
				/*
				double log10 = 2.30259; // for one order of magnitude change in poly prob at maxForce
				double log20 = 2.99573227355399;  
				double log40 = 3.68887945411394;
				double log60 = 4.0943445622221;
				double log80 = 4.38202663467388;
				double log100 = 4.60517;  // for two orders of magnitude change in poly prob at maxForce
				*/
				rateMod = Math.exp(-Env.polyLogFactor.getValue()*(-nodeForce/maxPolyForce));  // exp. decrease in poly. prob as nodeForce gets large
				//System.out.println("nodeForce = " + nodeForce + " maxForce = " + maxForce + " rateMod = " + rateMod);
			}
			rate = rateMod*Env.kATPOn2WithFormin.getValue(); 
		} else { 
			rate = Env.kATPOn2.getValue(); 
		}
		return rate;
	}
	
	public double getNonHydroPolyRateEnd2 () {
		double rate;
		if (nodeAtEnd2) { 
			rate = Env.kATPOn2NonHydroWithFormin.getValue(); 
		} else { rate = Env.kATPOn2NonHydro.getValue(); }
		return rate;
	}
	
	public double getDepolyRateEnd1 () {
		double depolySlowDown = 1;
		if (minusMon.hydrolyzable) { 
			if ((linkCt > 0) & (Env.sideBondsStabilize.isActive())) { depolySlowDown = Math.pow(Env.sideBondsStabilize.getValue(),linkedToCt); }
			if (minusMon.isADP()) { return depolySlowDown*Env.kADPOff1.getValue(); } else { return depolySlowDown*Env.kATPOff1.getValue(); }
		} else {
			return Env.kATPOff1NonHydro.getValue(); 
		}
	}
	
	public double getDepolyRateEnd2 () {
		double depolySlowDown = 1;
		if (plusMon.hydrolyzable) { 
			if ((linkCt > 0) & (Env.sideBondsStabilize.isActive())) { depolySlowDown = Math.pow(Env.sideBondsStabilize.getValue(),linkedToCt); }
			if (nodeAtEnd2) { 
				if (plusMon.isADP()) { return Env.kADPOff2WithFormin.getValue(); } else { return Env.kATPOff2WithFormin.getValue(); }
			} else {
				if (plusMon.isADP()) { return depolySlowDown*Env.kADPOff2.getValue(); } else { return depolySlowDown*Env.kATPOff2.getValue(); }
			}
		} else {
			if (nodeAtEnd2) { 
				return Env.kATPOff2NonHydroWithFormin.getValue();
			} else {
				return Env.kATPOff2NonHydro.getValue(); 
			}
		}
		
	}
	
	public void end1BiochemSim () {
		if (!removeMe) { 
			if (childOfArp23 && motherFil != null) { return; }  // no end1 biochem if arp2/3 is there and bound to mother filament
			minusEndDelta = 0;
			
			if (true) {
			//if (!stericHindranceEnd1()) { talkln ("no stericHindrance end1"); }
			//if ((!inCompression("at end1")) && (!stericHindranceEnd1()) && (capConditionOKEnd1())) {
				// normal actin pool polymerization sim
				double rate = getPolyRateEnd1();
				boolean monomerAdded = addMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end1 norm poly");
					coord.inc(-halfmono/2,uVec); 
					helixAng += (Math.PI - Env.helixAngInc);
					Monomer.polymerize(minusMon,this,Monomer.MINUSEND, true);
					minusEndDelta++;
				}
				// non-hydrolyzable actin pool polymerization sim
				rate = getNonHydroPolyRateEnd1();
				monomerAdded = addNonHydroMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end1 non-hydro poly");
					coord.inc(-halfmono/2,uVec); 
					helixAng += (Math.PI - Env.helixAngInc);
					Monomer.polymerize(minusMon,this,Monomer.MINUSEND, false);
					minusEndDelta++;
				}
			}
			
			// depolymerization...
			if (monomerCt >= Env.actinSeed.getIntValue()) {
				boolean monomerRemoved = removeMonomerSim(getDepolyRateEnd1(),minusMon);
				if (monomerRemoved) { 
					coord.inc(halfmono/2,uVec); 
					helixAng += (Math.PI + Env.helixAngInc);
					minusMon.depolymerize(this,Monomer.MINUSEND);
					minusEndDelta--;
				}
			} else {
				cleanup(this,true,true);
			}
			
			if (minusEndDelta !=0) { updateArpLocations(); }
		}
	}
	
	public void end2BiochemSim () {
		if (!removeMe) {
			plusEndDelta = 0;
			//talkln("end2Tip is " + end2TipC);
			checkForminRelease();
			checkCapping();
			checkBranching();
			
			if ((capConditionOKEnd2()) && (!stericHindranceEnd2())) {
				// normal actin polymerization
				double rate = getPolyRateEnd2();
				boolean monomerAdded = addMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end2 norm poly");
					coord.inc(halfmono/2,uVec); 
					Monomer.polymerize(plusMon,this,Monomer.PLUSEND, true);
					plusEndDelta++;
					Env.registerPlusMon(end2TipC);
			    }
				// non-hydrolyzable actin polymerization
				rate = getNonHydroPolyRateEnd2();
				monomerAdded = addNonHydroMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end2 non-hydro poly");
					coord.inc(halfmono/2,uVec); 
					Monomer.polymerize(plusMon,this,Monomer.PLUSEND, false);
					plusEndDelta++;
					Env.registerPlusMon(end2TipC);
			    }
			}
			
			// depolymerize...
			if (monomerCt >= Env.actinSeed.getIntValue()) {
				if (!end2Capped) {
					boolean monomerRemoved = removeMonomerSim(getDepolyRateEnd2(),plusMon);
					if (monomerRemoved) { 
						coord.inc(-halfmono/2,uVec); 
						plusMon.depolymerize(this,Monomer.PLUSEND);
						plusEndDelta--;
					}
				}
			} else {
				cleanup(this,true,true);
			}
		}
	}
	
	public void registerATipClearance (double tipC,boolean arpActivator) {
		if (tipC < end2TipC) { 
			end2TipC = tipC; 
			if (end2TipC < Env.branchZone.getValue() && arpActivator) { 
				end2NearArpFactor = true; 
			} else { 
				end2NearArpFactor = false; 
			}
		}
		
		/*if (end2TipC < 0 && end2Capped) { // remove cap if collision of tip, with some probability... why not?
			if (myPRNG.nextDouble() < 1e-4) { end2Capped = false; }
		}  	*/
	}
			
	public void checkCapping() {
		if (filAtEnd2) { return; } 	// no capping if interior
		if (end2Capped) { return; } // already capped
		if (nodeAtEnd2) { return; }  // no capping if formin at end2
		if (end2TipC < 2*Env.actinMonoDiam && end2NearArpFactor) { return; }  // steric conditions for end capping (replace with capping protein dimension!)
		if (myPRNG.nextDouble() < Env.capRate.getValue()*Env.capConc.getValue()*Env.biochemDeltaT.getValue()) {
			end2Capped = true;
		}
	}
	
	public void checkForminRelease() {
		if (end2Node == null) { nodeAtEnd2 = false; }  // fail-safe if end2Node disappears from sim
		if (nodeAtEnd2 && !forminCanHold()) { 
			nodeAtEnd2 = false;
			end2Node.filamentOff();
			end2Node = null;
		}
	}
	
	public void releasedByFormin() {
		nodeAtEnd2 = false;
		end2Node = null;
	}
	
	public void checkBranching() {
		if (!end2NearArpFactor) { return; }
		if (myPRNG.nextDouble() < Env.branchRateNearArpFactors.getValue()*Env.arpConc.getValue()*Env.biochemDeltaT.getValue()) {
			double bLoc = length - Math.random()*Env.branchZone.getValue();
			makeArpBranch(bLoc);
		}
	}
	  
	public FilSegment makeArpBranch(double bLoc) {
		double theta = getHelixAngleAtLoc(bLoc); // assume helixAng is angle with mother filament's body-fixed y-axis
		double yPart = Math.cos(theta)*Env.sinArp23Alpha;
		double zPart = Math.sin(theta)*Env.sinArp23Alpha;
		Pt3D nucUVec = new Pt3D(Env.cosArp23Alpha,yPart,zPart);
		nucUVec.xToX(this);
		Pt3D nucLoc = Pt3D.Add(end1,bLoc,uVec);
		FilSegment dFil = FilSegment.makeArp23NucFilament(nucLoc, nucUVec);
		Arp23 newArp = Arp23.newArpBranch(this, bLoc, dFil);
		
		// load into arrays
		arpChildren[arpChildCt] = dFil;
		arp23s[arpChildCt] = newArp;
		arpChildLoc[arpChildCt] = bLoc;
		arpActive[arpChildCt] = true;
		arpChildCt++;
		
		return dFil;
	}
	
	public void updateArpLocations () {
		for (int i=0;i<arpChildCt;i++) {
			if (arpActive[i]) {
				arpChildLoc[i] += minusEndDelta*Env.actinMonoRadius; 
				arp23s[i].updateBranchLoc(arpChildLoc[i]);
			}
		}
	}
	
	public synchronized void addExistingArp (FilSegment child, double loc, Arp23 arp) {
		arpChildren[arpChildCt] = child;
		arpChildLoc[arpChildCt] = loc;
		arp23s[arpChildCt] = arp;
		arp.reSet(this, loc, child);	// reset Arp23 with new mother, loc, etc
		arpActive[arpChildCt] = true;
		arpChildCt++;
	}
	
	public synchronized void removeArp23 (Arp23 rmArp) {
		// find index
		int indexOfChild = -1;
		for (int i=0;i<arpChildCt;i++) {
			if (arp23s[i] == rmArp) { 
				indexOfChild = i;
				//break;
			}
		}
		if (indexOfChild != -1) {
			rmArp.unSet();
			arp23s[indexOfChild] = null;
			arpChildren[indexOfChild] = null;
			arpActive[indexOfChild] = false;
		} else {
			talkln ("Daughter filament not found on selected Mother in FilSegment.removeArpChild!");
		}
	}
	
	public void removeArp23 (int i) {
		try {
			arp23s[i].unSet();
			arp23s[i] = null;
			arpChildren[i] = null;
			arpActive[i] = false;
		} catch (NullPointerException npe) { 
			talkln ("null pointer exception in removeArp23(int i)");
		}
	}
	
	public boolean canAddArpHere (double loc) {
		for (int i=0; i< arpChildCt; i++) {
			if (arpActive[i]) {
				if (Math.abs(arpChildLoc[i]-loc) <  arpSeparation) { return false; }
			}
		}
		return true;
	}
	
	public void viscousBlobSim (double effectiveLength, double dT) {
		// simulate addition of blobs
		if (numViscBlobs < Env.maxVBlobs) {
			double blobAddProb = effectiveLength*Env.vBlobOnRate*dT;
			if (myPRNG.nextDouble() < blobAddProb) { numViscBlobs++; lengthChanged = true;}  // use lengthChanged to trigger recalc. of drag
		}
		
		// simulate detaching blobs
		if (numViscBlobs == 0) return;  // don't waste time below if no more visc blobs
		double blobRemoveProb = Env.vBlobOffRate*dT;
		if (myPRNG.nextDouble() < blobRemoveProb) { 
			numViscBlobs--; 
			lengthChanged = true;
		}
	}
	
	public double getHelixAngleAtLoc(double loc) {
		return (helixAng + (loc/Env.actinMonoRadius)*Env.helixAngInc);// %(2*Math.PI);
	}
	
	public void resetCounters() {
		super.resetCounters();	// call the generic Thing method
		end1AxialF = 0;			// reset axial force at end1 to zero
		end2AxialF = 0; 		// reset axial force at end2 to zero
		lengthChanged = false;	// 
		end1LinkCkd = false;
		end2LinkCkd = false;
		end1TorqCkd = false;
		end2TorqCkd = false;
		end2TipC = 1e6; 		// big number
		end1TipC = 1e6; 
	}
	
	public static void zeroAllLinkCts () {
		for (int i=0;i<filSegmentCt;i++) {
			theFilSegments[i].linkCt = 0;
			theFilSegments[i].linkedToCt = 0;
		}
	}

	public void checkBugOrBoxCollision() {
		//if (Env.bugOff.isActive()) { return; }
		if (Env.simOutsideBug.isActive()) { 
			checkBugCollisionFromOutside();
		} else {
			checkBugCollisionFromInside();
		}
	}
	
	public void checkBugCollisionFromInside() {
		theBox.amICollidingOuter(cE,end1,radius);
		if (cE.delta != 0) { bugForcesFromInside(cE,end1); }
		
		
		theBox.amICollidingOuter(cE,end2,radius);
		if (cE.delta != 0) { bugForcesFromInside(cE,end2); }
	}
	
	public void checkBugCollisionFromOutside() {
		lmBug.amICollidingFromOutside(cE,end1,radius);
		if (cE.isColliding()) { 
			end1TipC = 0;
			bugForcesFromOutside(cE,end1); 
		} else {
			end1TipC = cE.delta;
		}
		
		
		lmBug.amICollidingFromOutside(cE,end2,radius);
		Env.registerCloseTip(cE.delta);  // only registering barbed-ends close to the bug surface
		if (cE.isColliding()) { 
			end2TipC = 0; // set tip clearance
			bugForcesFromOutside(cE,end2); 
			//talkln ("collision");
			//ActA.checkFilamentBinding(this,cE.tmpPt1);
			if (myPRNG.nextDouble() < Env.checkActABindingProb.getValue()) {	// only check for ActA binding rarely
				ActA.checkBindingToActA(this,cE.tmpPt1);
			}
			if (myPRNG.nextDouble() < Env.contactUncapsProb.getValue()) {		// uncap if contact with surface, with some probability
				end2Capped = false;
			}
		} else {
			end2TipC = cE.delta;
		}
	}
	
	public void validateEnd2Link() {
		// check state of link between end2  and end2Fil.. dissolve if problem found
		if (end2Fil == null | ptAtEnd2 == null) { 
			// no handle to the linked segment, or don't know which end linked on other seg... can only nullify this segments objects
			ptAtEnd2 = null;
			filAtEnd2 = false;
			return;
		}
		// if we've gotten here then check pointers of other segment
		if (ptAtEnd2 == end2Fil.end1) {
			boolean breakLink = false;
			if (!breakLink & !end2Fil.filAtEnd1) { breakLink = true; }
			if (!breakLink & end2Fil.ptAtEnd1 == null) { breakLink = true; }
			if (!breakLink & end2Fil.end1Fil == null) { breakLink = true; }
			if (!breakLink & end2Fil.end1Fil != this) { breakLink = true; }
			if (breakLink) { 
				end2Fil.removeEnd1Links();
				removeEnd2Links();
			}
		} else {
			boolean breakLink = false;
			if (!breakLink & !end2Fil.filAtEnd2) { breakLink = true; }
			if (!breakLink & end2Fil.ptAtEnd2 == null) { breakLink = true; }
			if (!breakLink & end2Fil.end2Fil == null) { breakLink = true; }
			if (!breakLink & end2Fil.end2Fil != this) { breakLink = true; }
			if (breakLink) { 
				end2Fil.removeEnd2Links();
				removeEnd2Links();
			}
		}
	}
	
	public void validateEnd1Link() {
		// check state of link between end1  and end1Fil.. dissolve if problem found
		if (end1Fil == null | ptAtEnd1 == null) { 
			// no handle to the linked segment, or don't know which end linked on other seg... can only nullify this segments objects
			ptAtEnd1 = null;
			filAtEnd1 = false;
			return;
		}
		// if we've gotten here then check pointers of other segment
		if (ptAtEnd1 == end1Fil.end1) {
			boolean breakLink = false;
			if (!breakLink & !end1Fil.filAtEnd1) { breakLink = true; }
			if (!breakLink & end1Fil.ptAtEnd1 == null) { breakLink = true; }
			if (!breakLink & end1Fil.end1Fil == null) { breakLink = true; }
			if (!breakLink & end1Fil.end1Fil != this) { breakLink = true; }
			if (breakLink) { 
				end1Fil.removeEnd1Links();
				removeEnd1Links();
			}
		} else {
			boolean breakLink = false;
			if (!breakLink & !end1Fil.filAtEnd2) { breakLink = true; }
			if (!breakLink & end1Fil.ptAtEnd2 == null) { breakLink = true; }
			if (!breakLink & end1Fil.end2Fil == null) { breakLink = true; }
			if (!breakLink & end1Fil.end2Fil != this) { breakLink = true; }
			if (breakLink) { 
				end1Fil.removeEnd2Links();
				removeEnd1Links();
			}
		}
	}
	
	public void breakAtEnd2() {
		if (filAtEnd2) {
			if (ptAtEnd2 == end2Fil.end1) {
				end2Fil.removeEnd1Links();
			} else {
				end2Fil.removeEnd2Links();
			}
		}
		removeEnd2Links();
	}
	
	public void breakAtEnd1() {
		if (filAtEnd1) {
			if (ptAtEnd1 == end1Fil.end2) {
				end1Fil.removeEnd2Links();
			} else {
				end1Fil.removeEnd1Links();
			}
		}
		removeEnd1Links();
	}
	
	public double moveCoeff (int end, Pt3D linkUVec) {
		double cosBeta;
		if (end == 2) {
			cosBeta = Pt3D.Dot(uVec, linkUVec);
		} else {
			cosBeta = Pt3D.Dot(uVecR, linkUVec);
		}
		double beta = Math.acos(cosBeta);
		double cosAlpha = Math.sin(beta);
		double lSqrd = 1e-12*length*length;
		double Cx = cosBeta*cosBeta/bTransGam.x;
		double Cperp = cosAlpha*cosAlpha/bTransGam.y;
		double Ctheta = lSqrd*cosAlpha*cosAlpha/(4*bRotGam.y);
		double moveC = Cx + Cperp + Ctheta;
		if (Double.isNaN(moveC)) {
			talkln ("MoveC is NaN");
			talkln ("	cosBeta = " + cosBeta);
			talkln ("	cosAlpha = " + cosAlpha);
			talkln ("	lSqrd = " + lSqrd);
			talkln ("	Cx = " + Cx);
			talkln ("	Cperp = " + Cperp);
			talkln ("	Ctheta = " + Ctheta);
		}
		return moveC;
	}
	
	public void registerFilLink (double loc, FilSegment linker) {
		synchronized (filLinkOSync) {
			// FilLinks register every time-step with FilSegments.. linkCt set to zero in resetCounters()
			if (linkCt > linkLocs.length-1) { linkLocs = new double[2*linkLocs.length]; }
			linkLocs[linkCt] = loc;
			linkCt++;
			if (!alreadyLinkedTo(linker) ) { 
				if (linkedToCt < linkedTo.length-1) {
					linkedTo[linkedToCt] = linker.filID;
					linkedToCt++; 
				}
			}
		}
		
	}
	
	public int getLinkCt () {
		return linkCt;
	}
	
	public boolean alreadyLinkedTo (FilSegment linker) {
		for (int i=0;i<linkedToCt;i++) {
			if (linkedTo[i] == linker.filID) { return true; }
		}
		return false;
	}
		
	public void addLinkForces () {
		// version1.2 of soft lagrange multipliers; constraining forces translational and rotational.
		// this filament takes care of both end links, if not already visited, then marks as visited on this and linked fils
		// this method doesn't care if ptAtEnd2 is end2Fil.end1 or end2Fil.end2
		
		// first double-check validity of links
		// non-existence of attached filament
		validateEnd2Link();
		validateEnd1Link();
		
		if (filAtEnd2 & !end2LinkCkd) {
			linkPt.add(end2,Env.actinMonoRadius,uVecR);		// link point is half a monomer back from end2 tip
			double strainDist = Pt3D.ptDist(linkPt,ptAtEnd2);
			
			// check distance if fils can break apart
			end2SegDist.registerValue(strainDist);
			if (Env.maxSegDist.isActive() & end2SegDist.averageVal() > Env.maxSegDist.getValue()) {
				breakAtEnd2();
				talkln ("broke because dist too large between linked segments");
				return;
			}
			
			linkUVec.unitVec(strainDist,ptAtEnd2,linkPt);
			linkUVecR.scale(-1,linkUVec);
			
			double moveCoeff1 = moveCoeff(2,linkUVec);
			double moveCoeff2;
			if (ptAtEnd2 == end2Fil.end1) {
				moveCoeff2 = end2Fil.moveCoeff(1, linkUVecR);
			} else {
				moveCoeff2 = end2Fil.moveCoeff(2, linkUVecR);
			}
			double forceMag = (Env.fracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
			
			// filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink2Track.registerValue(F);
			//F.copy(filLink2Track.averagePtVal());
			
			incForceSum(F);
			R.scale(0.5e-6*length*Env.fracR.getValue(),uVec);
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end2LinkCkd = true;
			
			Fopp.scale(-1,F);
			end2Fil.incForceSum(Fopp);
			if (ptAtEnd2 == end2Fil.end1) {
				R.scale(0.5e-6*end2Fil.length*Env.fracR.getValue(),end2Fil.uVecR);
				end2Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end2Fil.length*Env.fracR.getValue(),end2Fil.uVec);
				end2Fil.end2LinkCkd = true;
			}
			RcrossF.cross(R,Fopp);
			end2Fil.incTorqueSum(RcrossF);
			
			// add these link forces to the axial loads on each segment
			incEnd2AxialForce(Pt3D.Dot(uVec,F)); // axial force contribution
			if (ptAtEnd2 == end2Fil.end1) {
				end2Fil.incEnd1AxialForce(Pt3D.Dot(end2Fil.uVecR,Fopp)); // axial force contribution
			} else {
				end2Fil.incEnd2AxialForce(Pt3D.Dot(end2Fil.uVec,Fopp)); // axial force contribution
			}
			
			// propagate a change in filID.... lower filID always used
			if (filID != end2Fil.filID) {
				if (filID < end2Fil.filID) { end2Fil.filID = filID; } else { filID = end2Fil.filID; }
			}
		}
		
		// take care of link at end1
		if (filAtEnd1 & !end1LinkCkd) {
			linkPt.add(end1,Env.actinMonoRadius,uVec);		// link point is half a monomer back from end2 tip
			double strainDist = Pt3D.ptDist(linkPt,ptAtEnd1);
			
			// check distance if fils can break apart
			end1SegDist.registerValue(strainDist);
			if (Env.maxSegDist.isActive() & end1SegDist.averageVal() > Env.maxSegDist.getValue()) {
				breakAtEnd1();
				talkln ("broke because dist too large between linked segments");
				return;
			}
			
			linkUVec.unitVec(strainDist,ptAtEnd1,linkPt);
			linkUVecR.scale(-1,linkUVec);
			
			double moveCoeff1 = moveCoeff(1,linkUVec);
			double moveCoeff2;
			if (ptAtEnd1 == end1Fil.end1) {
				moveCoeff2 = end1Fil.moveCoeff(1, linkUVecR);
			} else {
				moveCoeff2 = end1Fil.moveCoeff(2, linkUVecR);
			}
			double forceMag = (Env.fracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
			
			//filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink1Track.registerValue(F);
			//F.copy(filLink1Track.averagePtVal());
		
			incForceSum(F);
			R.scale(0.5e-6*length*Env.fracR.getValue(),uVecR);
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end1LinkCkd = true;
			
			Fopp.scale(-1,F);
			end1Fil.incForceSum(Fopp);
			if (ptAtEnd1 == end1Fil.end1) {
				R.scale(0.5e-6*end1Fil.length*Env.fracR.getValue(),end1Fil.uVecR);
				end1Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end1Fil.length*Env.fracR.getValue(),end1Fil.uVec);
				end1Fil.end2LinkCkd = true;
			}
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,Fopp);
			end1Fil.incTorqueSum(RcrossF);
			
			// add these link forces to the axial loads on each segment
			incEnd1AxialForce(Pt3D.Dot(uVecR,F)); // axial force contribution
			if (ptAtEnd1 == end1Fil.end1) {
				end1Fil.incEnd1AxialForce(Pt3D.Dot(end1Fil.uVecR,Fopp)); // axial force contribution
			} else {
				end1Fil.incEnd2AxialForce(Pt3D.Dot(end1Fil.uVec,Fopp)); // axial force contribution
			}
			
			// propagate a change in filID.... lower filID always used
			if (filID != end1Fil.filID) {
				if (filID < end1Fil.filID) { end1Fil.filID = filID; } else { filID = end1Fil.filID; }
			}
		}
	}
	
	public void addLinkForcesOld () {
		// version1.2 of soft lagrange multipliers; constraining forces translational and rotational.
		// this filament takes care of both end links, if not already visited, then marks as visited on this and linked fils
		// this method doesn't care if ptAtEnd2 is end2Fil.end1 or end2Fil.end2
		
		// first double-check validity of links
		// non-existence of attached filament
		validateEnd2Link();
		validateEnd1Link();
		
		if (filAtEnd2 & !end2LinkCkd) {
			linkPt.add(end2,Env.actinMonoRadius,uVecR);		// link point is half a monomer back from end2 tip
			double strainDist = Pt3D.ptDist(linkPt,ptAtEnd2);
			
			// check distance if fils can break apart
			end2SegDist.registerValue(strainDist);
			if (Env.maxSegDist.isActive() & end2SegDist.averageVal() > Env.maxSegDist.getValue()) {
				breakAtEnd2();
				talkln ("broke because dist too large between linked segments");
				return;
			}
				
			linkUVec.unitVec(strainDist,ptAtEnd2,linkPt);
			linkUVecR.scale(-1,linkUVec);
			// define cosines of angles between filament uVecs and line between endpoints
			double cosAngTween1 = Pt3D.CrossMag(uVec,linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
			double cosAngTween2;
			if (ptAtEnd2 == end2Fil.end1) {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end2Fil.uVec);
			} else {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end2Fil.uVecR);
			}
			double moveCoeff1 = 1/bTransGam.x + Math.pow(1e-6*length*cosAngTween1/2,2)/bRotGam.y;
			double moveCoeff2 = 1/end2Fil.bTransGam.x + Math.pow(1e-6*end2Fil.length*cosAngTween2/2,2)/end2Fil.bRotGam.y;
			double forceMag = (Env.fracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
		
			// filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink2Track.registerValue(F);
			//F.copy(filLink2Track.averagePtVal());
			
			incForceSum(F);
			R.scale(0.5e-6*length,uVec);
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end2LinkCkd = true;
			
			Fopp.scale(-1,F);
			end2Fil.incForceSum(Fopp);
			if (ptAtEnd2 == end2Fil.end1) {
				R.scale(0.5e-6*end2Fil.length,end2Fil.uVecR);
				end2Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end2Fil.length,end2Fil.uVec);
				end2Fil.end2LinkCkd = true;
			}
			RcrossF.cross(R,Fopp);
			end2Fil.incTorqueSum(RcrossF);
			
			// add these link forces to the axial loads on each segment
			incEnd2AxialForce(Pt3D.Dot(uVec,F)); // axial force contribution
			if (ptAtEnd2 == end2Fil.end1) {
				end2Fil.incEnd1AxialForce(Pt3D.Dot(end2Fil.uVecR,Fopp)); // axial force contribution
			} else {
				end2Fil.incEnd2AxialForce(Pt3D.Dot(end2Fil.uVec,Fopp)); // axial force contribution
			}
			
			// propagate a change in filID.... lower filID always used
			if (filID != end2Fil.filID) {
				if (filID < end2Fil.filID) { end2Fil.filID = filID; } else { filID = end2Fil.filID; }
			}
		}
		
		// take care of link at end1
		if (filAtEnd1 & !end1LinkCkd) {
			linkPt.add(end1,Env.actinMonoRadius,uVec);		// link point is half a monomer back from end2 tip
			double strainDist = Pt3D.ptDist(linkPt,ptAtEnd1);
			
			// check distance if fils can break apart
			end1SegDist.registerValue(strainDist);
			if (Env.maxSegDist.isActive() & end1SegDist.averageVal() > Env.maxSegDist.getValue()) {
				breakAtEnd1();
				talkln ("broke because dist too large between linked segments");
				return;
			}
			
			linkUVec.unitVec(strainDist,ptAtEnd1,linkPt);
			linkUVecR.scale(-1,linkUVec);
			// define cosines of angles between filament uVecs and line between endpoints
			double cosAngTween1 = Pt3D.CrossMag(uVecR,linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
			double cosAngTween2;
			if (ptAtEnd1 == end1Fil.end1) {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end1Fil.uVec);
			} else {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end1Fil.uVecR);
			}
			double moveCoeff1 = 1/bTransGam.x + Math.pow(1e-6*length*cosAngTween1/2,2)/bRotGam.y;
			double moveCoeff2 = 1/end1Fil.bTransGam.x + Math.pow(1e-6*end1Fil.length*cosAngTween2/2,2)/end1Fil.bRotGam.y;
			double forceMag = (Env.fracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
			
			//filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink1Track.registerValue(F);
			//F.copy(filLink1Track.averagePtVal());
			
			incForceSum(F);
			R.scale(0.5e-6*length,uVecR);
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end1LinkCkd = true;
			
			Fopp.scale(-1,F);
			end1Fil.incForceSum(Fopp);
			if (ptAtEnd1 == end1Fil.end1) {
				R.scale(0.5e-6*end1Fil.length,end1Fil.uVecR);
				end1Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end1Fil.length,end1Fil.uVec);
				end1Fil.end2LinkCkd = true;
			}
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,Fopp);
			end1Fil.incTorqueSum(RcrossF);
			
			// add these link forces to the axial loads on each segment
			incEnd1AxialForce(Pt3D.Dot(uVecR,F)); // axial force contribution
			if (ptAtEnd1 == end1Fil.end1) {
				end1Fil.incEnd1AxialForce(Pt3D.Dot(end1Fil.uVecR,Fopp)); // axial force contribution
			} else {
				end1Fil.incEnd2AxialForce(Pt3D.Dot(end1Fil.uVec,Fopp)); // axial force contribution
			}
			
			// propagate a change in filID.... lower filID always used
			if (filID != end1Fil.filID) {
				if (filID < end1Fil.filID) { end1Fil.filID = filID; } else { filID = end1Fil.filID; }
			}
		}
	}
	

	
	public void addTorsionSpringForces () {
		// rotational spring which works to straighten out the filament
		// this filament takes care of spring at both ends, if not visited, then marks as visited for involved fils
		// this method doesn't care if ptAtEnd2 is end2Fil.end1 or end2Fil.end2
		
		if (filAtEnd2 & !end2TorqCkd) {
			end2TorqCkd = true;
			double dotVecs;
			if (ptAtEnd2 == end2Fil.end1) {
				end2Fil.end1TorqCkd = true;
				torsionVec.cross(uVec,end2Fil.uVec);
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVec,end2Fil.uVec);
			} else {
				end2Fil.end2TorqCkd = true;
				torsionVec.cross(uVec,end2Fil.uVecR);
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVec,end2Fil.uVecR);
			}
			
			if (dotVecs > 1.0) { dotVecs = 1.0; }
			double angTween = Math.acos(dotVecs)*180/Math.PI;
			
			// check if angle too large
			end2SegAng.registerValue(angTween);
			/*if (Env.maxSegAngle.isActive() & end2SegAng.averageVal() > Env.maxSegAngle.getValue()/4) { 
				talkln ("Something happening!");
				Env.paintOn = true;
			}*/
			if (Env.maxSegAngle.isActive() & end2SegAng.averageVal() > Env.maxSegAngle.getValue()) { 
				angTween = 0;
				talkln ("broke because angle too large between linked segments");
				filAtEnd2 = false; ptAtEnd2 = null; 
			}
			
			//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
			double torsionMag;
			if (Env.filTorqSpring.isActive()) {
				torsionMag = Env.fracMoveTorq.getValue()*Env.filTorqSpring.getValue()*angTween;
			} else { 
				//torsionMag = Env.fracMoveTorq.getValue()*(Math.PI/180)*end2SegAng.averageVal()/((1/bRotGam.y + 1/end2Fil.bRotGam.y)*Env.deltaT.getValue());
				torsionMag = Env.fracMoveTorq.getValue()*(Math.PI/180)*angTween/((1/bRotGam.y + 1/end2Fil.bRotGam.y)*Env.deltaT.getValue());
			}
		
			if (torsionVec.checkPt3D()) {
				torsionVec.scale(torsionMag);
				//filTorque2Track.registerValue(torsionVec);
				//torsionVec.copy(filTorque2Track.averagePtVal());
				incTorqueSum(torsionVec);
			
				torsionVec.scale(-1);
				end2Fil.incTorqueSum(torsionVec);
			} else {
				talkln ("Crazy torque result in FilSegment.addTorsionSpringForce() part 2");
			}
		}
		
		
		if (filAtEnd1 & !end1TorqCkd) {
			end1TorqCkd = true;
			double dotVecs;
			if (ptAtEnd1 == end1Fil.end1) {
				end1Fil.end1TorqCkd = true;
				torsionVec.cross(uVecR,end1Fil.uVec);
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVecR,end1Fil.uVec);
			} else {
				end1Fil.end2TorqCkd = true;
				torsionVec.cross(uVecR,end1Fil.uVecR);
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVecR,end1Fil.uVecR);
			}
			
			if (dotVecs > 1.0) { dotVecs = 1.0; }
			double angTween = Math.acos(dotVecs)*180/Math.PI;
			
			// check if angle too large
			end1SegAng.registerValue(angTween);
			/*if (Env.maxSegAngle.isActive() & end1SegAng.averageVal() > Env.maxSegAngle.getValue()/4) { 
				talkln ("Something happening!");
				Env.paintOn = true;
			}*/
			if (Env.maxSegAngle.isActive() & end1SegAng.averageVal() > Env.maxSegAngle.getValue()) { 
				angTween = 0;
				talkln ("broke because angle too large between linked segments");
				filAtEnd1 = false; ptAtEnd1 = null; 
			}
			
			//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
			double torsionMag;
			if (Env.filTorqSpring.isActive()) {
				torsionMag = Env.fracMoveTorq.getValue()*Env.filTorqSpring.getValue()*angTween;
			} else { 
				//torsionMag = Env.fracMoveTorq.getValue()*(Math.PI/180)*end1SegAng.averageVal()/((1/bRotGam.y + 1/end1Fil.bRotGam.y)*Env.deltaT.getValue());
				torsionMag = Env.fracMoveTorq.getValue()*(Math.PI/180)*angTween/((1/bRotGam.y + 1/end1Fil.bRotGam.y)*Env.deltaT.getValue());
			}
			if (torsionVec.checkPt3D()) {
				torsionVec.scale(torsionMag);
				//filTorque1Track.registerValue(torsionVec);
				//torsionVec.copy(filTorque1Track.averagePtVal());
				incTorqueSum(torsionVec);
				
				torsionVec.scale(-1);
				end1Fil.incTorqueSum(torsionVec);
			} else {
				talkln ("Crazy torque result in FilSegment.addTorsionSpringForce() part 1");
			}
		}
	}
	
	public static boolean checkForAnnealing (FilSegment fil1, FilSegment fil2) {
		double ptD, cosAngTween;
		if (fil1.filAtEnd1 & fil1.filAtEnd2) { return false; } // fil1 is interior, can't anneal
		if (fil2.filAtEnd1 & fil2.filAtEnd2) { return false; } // fil2 is interior, can't anneal
		
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
						return true;
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
						return true;
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
						return true;
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
						return true;
					}
				}
			}
		}
		return false;
	}
	
	public boolean tooCloseFilLinkLoc (double locToCheck) { // too close if locToCheck near existing FilLink with SAME FilSegment... a different FilSegment is ok to bind here
		if (linkCt >= Env.maxXLinksOnSeg.getValue()) return true;		// fails if max links achieved
		double curD;
		double minSep = Env.minSepBetweenXLinks.getValue();
		for (int i=0;i<linkCt;i++) {
			curD = Math.abs(locToCheck - linkLocs[i]);
			if (curD < minSep) { return true; }	// fails if one of the existing links is too close to this new location
		}
		return false;
	}
	
	public boolean tooManyLinksToThisFil (FilSegment filToCk) {
		for (int i=0;i<linkCt;i++) {
			
			
		}
		return false;
	}
	
	public static void meshAllSegs () {
		FilSegment curSeg;
		for (int i=0;i<filSegmentCt;i++) {
			curSeg = theFilSegments[i];
			Mesh.FILSEG_MESH.fillFilSegMesh(curSeg.filArrayPos, curSeg.end1, curSeg.end2);
		}
	}
	
	/*public static void filSegCollisions () {
		FilSegment iSeg,jSeg;
		for (int i=0;i<filSegmentCt;i++) {
			iSeg = theFilSegments[i];
			if (iSeg.linkCt < Env.maxLinksOnSeg.getValue()) {
				for (int j=i+1;j<filSegmentCt;j++) {
					jSeg = theFilSegments[j];
					if (jSeg.linkCt < Env.maxLinksOnSeg.getValue() && !sameNodeBound(iSeg,jSeg)) {
						if (roughCollisionCheck(iSeg,jSeg)) {
							boolean annealed = false;
							if (Env.filamentsAnneal.isActive()) { annealed = checkForAnnealing(iSeg,jSeg); }
							if (!annealed & Env.sideBonds.isActive()) { checkToLink(iSeg,jSeg); }
						}
					}
				}
			}
		}
	}*/
	
	public static void filSegMeshCollisions(){
		FilSegment iSeg,jSeg;
		for(int x=0;x<Mesh.nXBins;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				
				if(Mesh.FILSEG_MESH.timeStamps[x][y]==Env.counter){
				
					for(int i=0;i<Mesh.FILSEG_MESH.activeCts[x][y];i++){
						int iSegID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][i];
						iSeg=FilSegment.theFilSegments[iSegID];
						for (int j=i;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
							int jSegID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
							jSeg=FilSegment.theFilSegments[jSegID];
							if ((iSeg.filID != jSeg.filID) & (Env.xLinks.isActive())) { checkToLink(iSeg,jSeg); }  // don't check to link if segs belong to same filament or no xlinks at all
						}
					}
				}
			}
		}
	}
	
	public static void filSegMeshCollisions(int xStart, int xStop){
		FilSegment iSeg,jSeg;
		for(int x=xStart;x<xStop;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				
				if(Mesh.FILSEG_MESH.timeStamps[x][y]==Env.counter){
				
					for(int i=0;i<Mesh.FILSEG_MESH.activeCts[x][y];i++){
						int iSegID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][i];
						iSeg=FilSegment.theFilSegments[iSegID];
						for (int j=i;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
							int jSegID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
							jSeg=FilSegment.theFilSegments[jSegID];
							if ((iSeg.filID != jSeg.filID) & (Env.xLinks.isActive())) { checkToLink(iSeg,jSeg); }  // don't check to link if segs belong to same filament or no xlinks at all
						}
					}
				}
			}
		}
	}
	
	public static void membraneFilMeshCollisions(int xStart, int xStop){
		ProteinNode node;
		FilSegment fil;
		for(int x=xStart;x<xStop;x++){
			for(int y=0;y<Mesh.nYBins;y++) {
				if (Mesh.NODE_MESH.timeStamps[x][y]==Mesh.lastWriteTime && Mesh.FILSEG_MESH.timeStamps[x][y]==Mesh.lastWriteTime) {
				
					for(int i=0;i<Mesh.NODE_MESH.activeCts[x][y];i++){
						int nodeID=(int)Mesh.NODE_MESH.meshpoints[x][y][i];
						node=ProteinNode.theNodes[nodeID];
						if (node instanceof StickyNode) {  // this is membrane node specific
							for (int j=0;j<Mesh.FILSEG_MESH.activeCts[x][y];j++) {
								int filID=(int)Mesh.FILSEG_MESH.meshpoints[x][y][j];
								fil=theFilSegments[filID];
								checkNodeFilTipsCollision(node,fil);
							}
						}
					}
				}
			}
		}
	}
	
	public static void checkNodeFilTipsCollision (ProteinNode node, FilSegment fil) {
		// store tip clearance part
		fil.registerATipClearance(Pt3D.ptDist(node.coord, fil.end2) - node.getRadius(),node.iAmHotRho);  // register tip clearance for polymerization / capping
		
		// collision part
		double attnFactor = 0.3;
		double filTipR = Env.filTipRadiusForCollisions.getValue();
		Pt3D filTipCenter = Pt3D.Add(fil.end2,filTipR,fil.uVecR);
		double pDist = Pt3D.ptDist(node.coord, filTipCenter);
		if (pDist< node.getRadius()+filTipR) {
			double impingedist = (node.getRadius()+filTipR) - pDist;
		    Pt3D nodeVec = Pt3D.UnitVec(pDist, node.coord, filTipCenter);
			Pt3D filVec = Pt3D.Reverse(nodeVec);
			double mag = (attnFactor*1.0e-6*impingedist/Env.collisionDeltaT.getValue())/(1/node.bTransGam.x+1/fil.bTransGam.y);
			node.incForceSum(Pt3D.Scale(mag,nodeVec));
			fil.incForceSum(Pt3D.Scale(mag,filVec),filTipCenter);
			
			//register collsions
			node.collision();
			fil.collision();
		}
	}

	
	public static boolean sameNodeBound (FilSegment iSeg, FilSegment jSeg) {
		if (!iSeg.nodeAtEnd2) { return false; }
		if (!jSeg.nodeAtEnd2) { return false; }
		if (iSeg.end2Node == jSeg.end2Node) { return true; }
		return false;
	}
	
	public static boolean roughCollisionCheck (FilSegment fil1, FilSegment fil2) {
		if (fil1.filID == fil2.filID) { return false; }
		if (Math.abs(fil1.coord.x - fil2.coord.x) > fil1.xRange+fil2.xRange) { return false; }		// quick checks
		if (Math.abs(fil1.coord.y - fil2.coord.y) > fil1.yRange+fil2.yRange) { return false; }
		if (Math.abs(fil1.coord.z - fil2.coord.z) > fil1.zRange+fil2.zRange) { return false; }
			
		return true;
	}
	
	public static void checkToLink (FilSegment fil1, FilSegment fil2) {
		RetObj retO = fil1.retObj;
		if (fil1.nodeAtEnd2 && fil2.nodeAtEnd2) {
			if (fil1.end2Node == fil2.end2Node) { return; }  // no xlinks between first segments from same node
		}
		
		double angTween,angTweenR;
		double maxAngle = Env.maxXLinkBondAngle.getValue();
		switch (Env.xLinks.getIntValue()) { 
		case 0:
			angTween = Math.acos(Pt3D.Dot(fil1.uVec, fil2.uVec));
			angTweenR = Math.acos(Pt3D.Dot(fil1.uVec, fil2.uVecR));
			if ((angTween > maxAngle) & (angTweenR > maxAngle)) { return; }
			break;
		case 1:
			angTween = Math.acos(Pt3D.Dot(fil1.uVec, fil2.uVec));
			if (angTween > maxAngle) { return; }
			break;
		case -1:
			angTweenR = Math.acos(Pt3D.Dot(fil1.uVec, fil2.uVecR));
			//if (Env.xLinkTesting) { System.out.println("Angle between test filaments is " + angTween + " radians"); }
			if (angTweenR > maxAngle) { return; }
			break;
		}
		
		lineSegmentIntersectTest(fil1.end1,fil1.end2,fil2.end1,fil2.end2,retO);
		if ((retO.collision) && retO.conDist < Env.crossLinkGrabDist.getValue()) {
			double loc1 = Pt3D.ptDist(fil1.end1,retO.conPt1) + (2*fil1.myPRNG.nextDouble()-1)*minFilLinkSep;
			double loc2 = Pt3D.ptDist(fil2.end1,retO.conPt2) + (2*fil2.myPRNG.nextDouble()-1)*minFilLinkSep;
			if (loc1 > fil1.length) { loc1 = fil1.length; }
			if (loc1 < 0) { loc1 = 0; }
			if (loc2 > fil2.length) { loc2 = fil2.length; }
			if (loc2 < 0) { loc2 = 0; }
			
			
			if (fil1.tooCloseFilLinkLoc(loc1)) { return; }
			if (fil2.tooCloseFilLinkLoc(loc2)) { return; }
			
			FilLink.makeLink(fil1, loc1, fil2, loc2);
		}
	}
	
	public boolean ptInNodeBoundingBox (Pt3D pt, ProteinNode node) {
		// quicker checks to see if this end could be colliding with this plasmid... bounding box
		double cushion = Env.actinMonoDiam;
		double nodeRad = node.getRadius()+cushion;
		// x coord
		if (pt.x < node.coord.x - nodeRad) { return false; }
		if (pt.x > node.coord.x + nodeRad) { return false; }
		// y coord
		if (pt.y < node.coord.y - nodeRad) { return false; }
		if (pt.y > node.coord.y + nodeRad) { return false; }
		// z coord
		if (pt.z < node.coord.z - nodeRad) { return false; }
		if (pt.z > node.coord.z + nodeRad) { return false; }
		
		return true;
	}
	
	public boolean nodeInFilSegBoundingBox (ProteinNode node) {
		double cushion = Env.actinMonoDiam;
		double sumRad = node.getRadius()+cushion + length/2;
		// x coord
		if (Math.abs(coord.x - node.coord.x) > sumRad) { return false; }
		// y coord
		if (Math.abs(coord.y - node.coord.y) > sumRad) { return false; }
		// z coord
		if (Math.abs(coord.z - node.coord.z) > sumRad) { return false; }
		
		return true;
	}
	
	public void nodeCollisions() {
		ProteinNode curNode;
		for (int i=0;i<ProteinNode.nodeCt;i++) {
			curNode = ProteinNode.theNodes[i];
			if (nodeInFilSegBoundingBox(curNode)) {
				Thing.pointAndLineIntersectTest(curNode.coord, end1, end2, retObj);
				if (retObj.collision && retObj.conDist < curNode.getRadius()) {
					double arcOnFil = Pt3D.ptDist(end1, retObj.conPt1);
					//curNode.myosinOn(this,arcOnFil);
				}
			}
		}
	}
	
	public boolean myoMotorInFilSegBoundingBox (MyoMotor mot) {
		double cushion = Env.actinMonoDiam;
		double sumRad = mot.getDim()+cushion + length/2;
		// x coord
		if (Math.abs(coord.x - mot.coord.x) > sumRad) { return false; }
		// y coord
		if (Math.abs(coord.y - mot.coord.y) > sumRad) { return false; }
		// z coord
		if (Math.abs(coord.z - mot.coord.z) > sumRad) { return false; }
		
		return true;
	}
	

	/*public void myoMotorCollisions() {
		MyoMotor curMotor;
		for (int i=0;i<Myosin.myoCt;i++) {
			curMotor = Myosin.theMyosins[i].myoMotor;
			if (!curMotor.onFil && myoMotorInFilSegBoundingBox(curMotor)) {
				MyoFilLink.numInBoundingBoxes++;
				Thing.pointAndLineIntersectTest(curMotor.bindTip, end1, end2, retObj);
				if (retObj.collision && retObj.conDist < Env.myoColTol.getValue()) {
					MyoFilLink.nodeHits++;
					double arcOnFil = Pt3D.ptDist(end1, retObj.conPt1);
					curMotor.ontoFilament(this,arcOnFil);
				}
			}
		}
	}
	*/
	
	/*public void nodeCollisions() {
		// if colliding with protein node at end1 then push away
		for (int i=0;i<ProteinNode.nodeCt;i++){
			ProteinNode curNode = ProteinNode.theNodes[i];
			if (ptInNodeBoundingBox(end1,curNode)) {
				double distToEnd1 = Pt3D.ptDist(curNode.coord,end1);
				double impDist = curNode.getRadius() - distToEnd1;
				//if (impDist > -halfmono) { end1TipC = 0; } 	// steric hindrance to polymerization set
				if (impDist > 0) {
					linkUVec.unitVec(distToEnd1,end1,curNode.coord);
					linkUVecR.scale(-1,linkUVec);
					// define cosines of angles between filament uVecs and line between endpoints
					double cosAngTween1 = Pt3D.CrossMag(uVecR,linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
					double moveCoeff1 = 1/bTransGam.x + Math.pow(1e-6*length*cosAngTween1/2,2)/bRotGam.y;
					double moveCoeff2 = 1/curNode.bTransGam.x;
					double forceMag = (Env.fracMove.getValue()*1.0e-6*impDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
					
					F.scale(forceMag,linkUVec);
					incForceSum(F);
					R.scale(1e-6*length/2,uVecR);
					RcrossF.cross(R,F);
					incTorqueSum(RcrossF);
					
					Fopp.scale(-1,F);
					curNode.incForceSum(Fopp);
					// note: no torque on node.... force through CM
					
					curNode.myosinOn(this,0);
				}
			}
		}

		// if colliding with plasmid at end2 then push away
		for (int i=0;i<ProteinNode.nodeCt;i++){
			ProteinNode curPlasmid = ProteinNode.theNodes[i];
			if (ptInNodeBoundingBox(end2,curPlasmid)) {
				double distToEnd2 = Pt3D.ptDist(curPlasmid.coord,end2);
				double impDist = curPlasmid.getRadius() - distToEnd2;
				//if (impDist > -halfmono) { end2TipC = 0; } 	// steric hindrance to polymerization set
				if (impDist > 0) {
					linkUVec.unitVec(distToEnd2,end2,curPlasmid.coord);
					linkUVecR.scale(-1,linkUVec);
					// define cosines of angles between filament uVecs and line between endpoints
					double cosAngTween1 = Pt3D.CrossMag(uVec,linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
					double moveCoeff1 = 1/bTransGam.x + Math.pow(1e-6*length*cosAngTween1/2,2)/bRotGam.y;
					double moveCoeff2 = 1/curPlasmid.bTransGam.x;
					double forceMag = (Env.fracMove.getValue()*1.0e-6*impDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
					
					F.scale(forceMag,linkUVec);
					incForceSum(F);
					R.scale(1e-6*length/2,uVec);
					RcrossF.cross(R,F);
					incTorqueSum(RcrossF);
					
					Fopp.scale(-1,F);
					curPlasmid.incForceSum(Fopp);
					// note: no torque on plasmid.... force through CM
				}
			}
		}
	}*/
		
	public boolean forminCloseAndReady (ProteinNode curNode) {
		if (curNode.canNucleateFilament()) {
			if (ptInNodeBoundingBox(end2,curNode)) { return true; }
		}
		return false;
	}
	

	public void checkForminBinding() {
		//** In latcon model assume only barbed-end (end2) can bind to formin at node **
		// if colliding with node at end2 then nodeAtEnd2 = true; and end2Node = the colliding plasmid
		if ((!filAtEnd2) && (!nodeAtEnd2) && (end2DetachCounter ==0)) {
			for (int i=0;i<ProteinNode.nodeCt;i++){
				ProteinNode curNode = ProteinNode.theNodes[i];
				if (forminCloseAndReady(curNode)) {
					double distToNode = Pt3D.ptDist(curNode.coord,end2);
					if (distToNode<curNode.getRadius()) {
						nodeAtEnd2= true;
						end2Node=curNode;
						linkUVec.sub(curNode.getRadius()/distToNode,end2,curNode.coord);	// vector to edge of plasmid in direction from coord to end2
						end2PAttachPt.XTox(curNode,linkUVec);
						end2PAttachPt.zero(); // *** REMOVE if don't want binding in center of node
						curNode.filamentOn();
						setGlobalEnd2Node(true);
					}
				}
			}
		}
	}
	
	public void addNodeForces() { // if attach point at center of node
		// fail-safe trust no one check on nodes!
		if (end2Node == null || end2Node.removeMe) { releasedByFormin(); }
		if (end1Node == null || end1Node.removeMe) { nodeAtEnd1 = false; }
		
		if (nodeAtEnd2) {
			end2Node.registerWithNode(this);
			double strainDist = Pt3D.ptDist(end2Node.coord,end2);
			double forceMag = Env.fracMove.getValue()*1.0e-6*strainDist/((1/bTransGam.x + 1/end2Node.bTransGam.x)*Env.deltaT.getValue());
			toPlasmidUVec.unitVec(end2Node.coord,end2);
			F.scale(forceMag,toPlasmidUVec);
			incForceSum(F,end2);
			double axialF = Pt3D.Dot(uVec,F);  // axial force contribution
			end2NodeForceThisStep = axialF;
			end2NodeForce.registerValue(axialF);
			incEnd2AxialForce(axialF);  

			Fopp.scale(-1,F);
			end2Node.incForceSum(Fopp);
			
			// torque to keep a certain alignment with node
			if (Env.nodeTorqSpring.isActive()) {
				forminVecInX.xToX(end2Node,forminVecInx);
				double dotVecs = Pt3D.Dot(forminVecInX,uVec);
				//if (dotVecs < 0) { dotVecs = 0; }
				if (dotVecs > 1) { dotVecs = 1; }
				double angTween = Math.acos(dotVecs);
				double torqMag = Env.nodeTorqSpring.getValue()*angTween;
				//System.out.println ("angTween = " + angTween*180/Math.PI);
				R.cross(uVec,forminVecInX);
				R.scale(torqMag);
				incTorqueSum(R);
				R.reverse();
				end2Node.incTorqueSum(R);
			}
			
			// register strainDist and check for filament detachment
			end2ToPlasStrain.registerValue(strainDist);
			boolean removeTether = false;
			if ((Env.maxNodeTetherStrainDist.isActive()) & (end2ToPlasStrain.averageVal() > Env.maxNodeTetherStrainDist.getValue())) { 	// if strain greater than max allowable
				removeTether = true; 
			} else if ((Env.nodeTetherDetachRate.isActive()) & (myPRNG.nextDouble() < Env.nodeTetherDetachRate.getValue()*Env.deltaT.getValue())) {
				removeTether = true;
			} 
			if (removeTether) {
				//talkln ("removing plasmid tether at end2");
				nodeAtEnd2= false;
				end2Node.filamentOff();
				end2Node=null;
				end2PAttachPt.zero();
				end2DetachCounter = 10000;
				setGlobalEnd2Node(false);
			}
			
		}
		
		if (nodeAtEnd1) {
			end1Node.registerWithNode(this);
			double strainDist = Pt3D.ptDist(end1Node.coord,end1);
			double forceMag = Env.fracMove.getValue()*1.0e-6*strainDist/((1/bTransGam.x + 1/end1Node.bTransGam.x)*Env.deltaT.getValue());
			toPlasmidUVec.unitVec(end1Node.coord,end1);
			F.scale(forceMag,toPlasmidUVec);
			incForceSum(F,end1);
			double axialF = Pt3D.Dot(uVec,F);  // axial force contribution
			incEnd1AxialForce(axialF);  

			Fopp.scale(-1,F);
			end1Node.incForceSum(Fopp);
	
			
			// register strainDist and check for filament detachment
			end1ToPlasStrain.registerValue(strainDist);
			boolean removeTether = false;
			if ((Env.maxNodeTetherStrainDist.isActive()) & (end1ToPlasStrain.averageVal() > Env.maxNodeTetherStrainDist.getValue())) { 	// if strain greater than max allowable
				removeTether = true; 
			} else if ((Env.nodeTetherDetachRate.isActive()) & (myPRNG.nextDouble() < Env.nodeTetherDetachRate.getValue()*Env.deltaT.getValue())) {
				removeTether = true;
			} 
			if (removeTether) {
				//talkln ("removing plasmid tether at end2");
				nodeAtEnd1= false;
				end1Node.filamentOff();
				end1Node=null;
				end1PAttachPt.zero();
				end1DetachCounter = 10000;
				setGlobalEnd1Node(false);
			}
			
		}
	}
	
	/*public void addNodeForces() { // if attach point not at center of node
		
		if (nodeAtEnd2) {
			if (Env.forminMoves.isActive()) {
				// reassign attachpt...
				end2PAttachPt.sub(end2,end2Node.coord);
				end2PAttachPt.unitVec();
				end2PAttachPt.scale(end2Node.getRadius());
				end2PAttachPt.XTox(end2Node);
			}
			
			end2PAttachPtInX.xToXPlusxOrigin(end2Node,end2PAttachPt);
			double strainDist = Pt3D.ptDist(end2PAttachPtInX,end2);
			double forceMag = Env.fracMove.getValue()*1.0e-6*strainDist/((1/bTransGam.x + 1/end2Node.bTransGam.x)*Env.deltaT.getValue());
			toPlasmidUVec.unitVec(end2PAttachPtInX,end2);
			F.scale(forceMag,toPlasmidUVec);
			incForceSum(F);
			R.scale(1e-6*(0.5*length),uVec);
			double axialF = Pt3D.Dot(uVec,F);  // axial force contribution
			incEnd2AxialForce(axialF);  
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			
			Fopp.scale(-1,F);
			end2Node.incForceSum(Fopp);
			R.sub(1e-6,end2PAttachPtInX,end2Node.coord);
			RcrossF.cross(R,Fopp);
			end2Node.incTorqueSum(RcrossF);
			
			// steric hindrance from plasmid
			//double toEnd2Dist = Pt3D.ptDist(end2Plasmid.coord, end2);
			//if (toEnd2Dist < (end2Plasmid.getRadius()-halfmono)) { end2TipC = 0; }
			
			
			//	torsional spring between plasmid and segment
			toPlasmidUVec.unitVec(end2,end2Node.coord);	// the unit vector orthogonal to the plasmid at the attachment point
			torsionVec.cross(uVecR,toPlasmidUVec);
			torsionVec.unitVec();
			double angTween = Math.acos(Pt3D.Dot(uVecR,toPlasmidUVec));
			double torsionMag;
			if (Env.nodeTorqSpring.isActive()) {
				torsionMag = Env.nodeTorqSpring.getValue()*angTween;
			} else {
				torsionMag = -Env.fracMoveTorq.getValue()*angTween*bRotGam.y/Env.deltaT.getValue();
			}
			torsionVec.scale(torsionMag);
			incTorqueSum(torsionVec);	
			torsionVec.scale(-1);
			end2Node.incTorqueSum(torsionVec);
			
			// register strainDist and check for filament detachment
			end2ToPlasStrain.registerValue(strainDist);
			boolean removeTether = false;
			if ((Env.maxNodeTetherStrainDist.isActive()) & (end2ToPlasStrain.averageVal() > Env.maxNodeTetherStrainDist.getValue())) { 	// if strain greater than max allowable
				removeTether = true; 
			} else if ((Env.nodeTetherDetachRate.isActive()) & (myPRNG.nextDouble() < Env.nodeTetherDetachRate.getValue()*Env.deltaT.getValue())) {
				removeTether = true;
			} 
			if (removeTether) {
				//talkln ("removing plasmid tether at end2");
				nodeAtEnd2= false;
				end2Node.filamentOff();
				end2Node=null;
				end2PAttachPt.zero();
				end2DetachCounter = 10000;
				setGlobalEnd2Node(false);
			}
			
		}
	}*/
	
	public void setGlobalEnd1Node (boolean onState) {
		if (true) { return; }
		FilSegment curSeg = this;
		while (curSeg.filAtEnd2) {
			curSeg.globalNodeAtEnd1 = onState;
			curSeg = curSeg.end2Fil;
		}
		curSeg.globalNodeAtEnd1 = onState; // get last one
	}
	
	public void setGlobalEnd2Node (boolean onState) {
		if (true) { return; }
		FilSegment curSeg = this;
		while (curSeg.filAtEnd1) {
			curSeg.globalNodeAtEnd2 = onState;
			curSeg = curSeg.end1Fil;
		}
		curSeg.globalNodeAtEnd2 = onState; // get end filSegment too
	}
	
	public boolean capConditionOKEnd1 () {	
		if (true) { return true; }
		int numMonsToCk = 0;
		if (nodeAtEnd1) {
			if (!Env.capNumEnd1WithNode.isActive()) { return true; } // plasmid bound with no cap condition
			numMonsToCk = Env.capNumEnd1WithNode.getIntValue();
		} else {
			if (!Env.capNumEnd1.isActive()) { return true; } // free with no cap condition
			numMonsToCk = Env.capNumEnd1.getIntValue();
		}
		if (monomerCt < numMonsToCk) { numMonsToCk = monomerCt; }
		Monomer curMon = minusMon;
		for (int i=0;i<numMonsToCk;i++) {
			if (!curMon.isATP()) { return false; }
			curMon = curMon.frontMon;
		}
		return true;
	}
	
	public boolean capConditionOKEnd2 () {  // conditions to allow polymerization
		if (end2Capped) { return false; }
		int numMonsToCk = 0;
		if (nodeAtEnd2) {
			if (!Env.capNumEnd2WithNode.isActive()) { return true; } // plasmid bound with no cap condition
			numMonsToCk = Env.capNumEnd2WithNode.getIntValue();
		} else {
			if (!Env.capNumEnd2.isActive()) { return true; } // free with no cap condition
			numMonsToCk = Env.capNumEnd2.getIntValue();
		}
		if (monomerCt < numMonsToCk) { numMonsToCk = monomerCt; }
		Monomer curMon = plusMon;
		for (int i=0;i<numMonsToCk;i++) {
			if (!curMon.isATP()) { return false; }
			curMon = curMon.backMon;
		}
		return true;
	}
	
	/*public boolean forminCanHold () {
		// this version is random formin release
		if (myPRNG.nextDouble() < Env.forminRelease.getValue()*Env.biochemDeltaT.getValue()) {
			//System.out.println ("forminCanHold says release formin");
			return false;
		}
		return true;
	}*/
	
	public boolean forminCanHold () {
		double releaseProb = Env.forminRelease.getValue()*Env.biochemDeltaT.getValue();
		double nodeForce = end2NodeForce.averageVal();
		if (nodeForce < 0) { 
			double log10 = 2.30259; // for one order of magnitude change in release prob at refForce
			double refForce = 2e-12;  // 2 pN
			//System.out.print("nodeForce = " + nodeForce + " ; releaseProbBase = " + releaseProb);
			releaseProb *= Math.exp(-log10*(-nodeForce/refForce));  // exp. decrease in releaseProb at nodeForce gets large in compression
			//System.out.println (" ; releasePrb = " + releaseProb);
		}
		if (myPRNG.nextDouble() < releaseProb) {
			return false;
		}
		return true;
	}
	
	/*public boolean forminCanHold () {
		// this version is for hydrolysis dependent formin release
		int numMonsToCk = 2;
		if (monomerCt < numMonsToCk) { numMonsToCk = monomerCt; }
		Monomer curMon = plusMon;
		for (int i=0;i<numMonsToCk;i++) {
			if (curMon.isATP()) { return true; }
			curMon = curMon.backMon;
		}
		return false;
	}*/
	
/*	public void criteriaEnd1Catastrophy () {
		double nucleotiderepartition = 0;
		Monomer curMon = minusMon;
		if (Env.criteriaLength1.getIntValue()<monomerCt){
			for (int i=0;i<Env.criteriaLength1.getIntValue();i++) {
				if (curMon.isADP()){
					nucleotiderepartition++;	
				}
				curMon=curMon.frontMon;	
			}
			if ((nucleotiderepartition/Env.criteriaLength1.getValue())>Env.criteriaRatio1.getValue()){ 
				end1Catastrophy = true;
				setEnd1CatastrophyAppearance();
			}
		} else {
			for (int i=0;i<monomerCt;i++) {
				if (curMon.isADP()){
					nucleotiderepartition++;	
				}
				curMon=curMon.frontMon;	
			}
			if ((nucleotiderepartition/monomerCt)>Env.criteriaRatio1.getValue()){ 
				end1Catastrophy = true;
				setEnd1CatastrophyAppearance();
			}
		}
	}
	
	public void criteriaEnd2Catastrophy() {
		double nucleotiderepartition = 0;
		Monomer curMon= plusMon;
		if (Env.criteriaLength2.getValue()<monomerCt){
			for (int i = 0; i<Env.criteriaLength2.getValue(); i++) {
				if (curMon.isADP()){
					nucleotiderepartition++;	
					}
				curMon=curMon.backMon;	
			}
			if ((nucleotiderepartition/Env.criteriaLength2.getValue())>Env.criteriaRatio2.getValue()){ 
				end2Catastrophy = true;
				setEnd2CatastrophyAppearance();
			}
		} else {
			for (int i = 0; i<monomerCt; i++) {
				if (curMon.isADP()){
					nucleotiderepartition++;	
					}
				curMon=curMon.backMon;	
			}
			if ((nucleotiderepartition/monomerCt)>Env.criteriaRatio2.getValue()){ 
				end2Catastrophy = true;
				setEnd2CatastrophyAppearance();
			}
		}
	}
	*/
	
	public void bugForcesFromInside (CollisionEvent X,Pt3D End) {
		// don't recall why I'm doing this collision in some fancier way than just sending force and forcept to objects.. should try both ways
		R.sub(End,coord); 		// define vector from center of filament out to the endpoint
		R.scale(1e-6);				// make units meters
		double RxFuVecSqrd = Pt3D.CrossMagSqrd(R,X.forceUVec);
		fturn = (1e-6*X.delta*bRotGam.y/(RxFuVecSqrd*Env.deltaT.getValue()));
		ftrans= (1e-6*X.delta*bTransGam.x/Env.deltaT.getValue());
		//fnorm=Env.fracMove.getValue()*Math.min(fturn,ftrans);
		fnorm=0.1*Math.min(fturn,ftrans);
		Fcoll.scale(fnorm,X.forceUVec);
		Tcoll.cross(R,Fcoll);
		//if (!Fcoll.checkPt3D()) { System.out.println("Fcoll in addBugForces"); }
		incForceSum(Fcoll);
		incTorqueSum(Tcoll);
		
		// axial force contribution
		if (End == end1) { 
			incEnd1AxialForce(Pt3D.Dot(uVecR,Fcoll)); 
			end1TipC = 0;
		}
		if (End == end2) { incEnd2AxialForce(Pt3D.Dot(uVec,Fcoll)); 
			end2TipC = 0;

		}
	}
	
	public void bugForcesFromOutside (CollisionEvent cE, Pt3D endPt) {
		double impD = Math.abs(cE.delta)*1e-6; // impingement distance in meters
		double attnFactor = 1.0;
		double mag = (attnFactor*impD/Env.deltaT.getValue())/(1/Thing.lmBug.bTransGam.x+1/bTransGam.x);
		cE.forceUVec.scale(mag); // now the actual force vector
		incForceSum(cE.forceUVec,endPt);
		cE.forceUVec.reverse();
		lmBug.incForceSum(cE.forceUVec,endPt);
		lmBug.addPathColForceOnBug(cE.forceUVec);
		if (cE.type == CollisionEvent.CYLINDER) {
			lmBug.addNormalForce(cE.forceUVec,endPt);
		}
	}
	
	public void old_bugForcesFromOutside (CollisionEvent X,Pt3D End) {
		// don't recall why I'm doing this collision in some fancier way than just sending force and forcept to objects.. should try both ways
		R.sub(End,coord); 		// define vector from center of filament out to the endpoint
		R.scale(1e-6);				// make units meters
		double RxFuVecSqrd = Pt3D.CrossMagSqrd(R,X.forceUVec);
		fturn = (1e-6*X.delta*bRotGam.y/(RxFuVecSqrd*Env.deltaT.getValue()));
		ftrans= (1e-6*X.delta*bTransGam.x/Env.deltaT.getValue());
		//fnorm=Env.fracMove.getValue()*Math.min(fturn,ftrans);
		fnorm=0.1*Math.min(fturn,ftrans);
		Fcoll.scale(fnorm,X.forceUVec);
		Tcoll.cross(R,Fcoll);
		//if (!Fcoll.checkPt3D()) { System.out.println("Fcoll in addBugForces"); }
		incForceSum(Fcoll);
		incTorqueSum(Tcoll);
		Fcoll.scale(-1);
		lmBug.incForceSum(Fcoll,End);  // force on listeria
		lmBug.addPathColForceOnBug(Fcoll);
	
	}
	
	public boolean addMonomerSim (double onRate){
		if (myPRNG.nextDouble()< onRate*theBox.getMonomerConc()*Env.biochemDeltaT.getValue()){
			monomerCt++;
			length+=halfmono;
			theBox.takeMonomer(1);
			lengthChanged = true;
			return true;
		}
		return false;
	}
	
	public boolean addNonHydroMonomerSim (double onRate){
		if (myPRNG.nextDouble()< onRate*theBox.getNonHydroMonomerConc()*Env.biochemDeltaT.getValue()){
			monomerCt++;
			length+=halfmono;
			theBox.takeNonHydroMonomer(1);
			lengthChanged = true;
			return true;
		}
		return false;
	}
	
	public boolean removeMonomerSim (double offRate, Monomer endMon) {
		if (myPRNG.nextDouble()< offRate*Env.biochemDeltaT.getValue()){
			monomerCt--;
			length+= -halfmono;
			if (endMon.hydrolyzable) {
				theBox.putMonomer(1);
			} else {
				theBox.putNonHydroMonomer(1);
			}
			lengthChanged = true;
			return true;
		}
		return false;
	}
	
	public static void linkEnd1Node (FilSegment fil, ProteinNode node) {
		fil.nodeAtEnd1 = true;
		fil.end1Node = node;
		node.filamentOn();
	}
	
	public static void linkEnd2Node (FilSegment fil, ProteinNode node) {
		fil.nodeAtEnd2 = true;
		fil.end2Node = node;
		node.filamentOn();
	}
	
	public static void link (FilSegment at1, FilSegment at2) {
		at1.setEnd2Links(at2,true);
		at2.setEnd1Links(at1, true);
	}
	
	public void setEnd1Links (FilSegment at1, boolean normOrientation) {
		filAtEnd1 = true;
		end1Fil = at1;
		if (normOrientation) { ptAtEnd1 = at1.end2; } else { ptAtEnd1 = at1.end1; }
	}
	
	public void setEnd2Links (FilSegment at2, boolean normOrientation) {
		filAtEnd2 = true;
		end2Fil = at2;
		if (normOrientation) { ptAtEnd2 = at2.end1; } else { ptAtEnd2 = at2.end2; }
	}
	
	public void removeEnd1Links() {
		filAtEnd1 = false;
		end1Fil = null;
		ptAtEnd1 = null;
	}
	
	public void removeEnd2Links() {
		filAtEnd2 = false;
		end2Fil = null;
		ptAtEnd2 = null;
	}
	
	public static void transferEnd1Plasmid (FilSegment givesP, FilSegment getsP) {
		getsP.nodeAtEnd1 = true;
		getsP.end1Node = givesP.end1Node;
		getsP.end1PAttachPt.copy(givesP.end1PAttachPt);
		givesP.nodeAtEnd1=false;
		givesP.end1Node=null;
	}
	
	public static void transferEnd2Plasmid (FilSegment givesP, FilSegment getsP) {
		getsP.nodeAtEnd2 = true;
		getsP.end2Node = givesP.end2Node;
		getsP.end2PAttachPt.copy(givesP.end2PAttachPt);
		getsP.forminVecInx.copy(givesP.forminVecInx);
		givesP.nodeAtEnd2=false;
		givesP.end2Node=null;
	}
		
	public static void cleanup (FilSegment cleanF, boolean swapOut, boolean isACut) {
			theBox.putMonomer(cleanF.monomerCt);
			if (!cleanF.filAtEnd1 & !cleanF.filAtEnd2) { filCt--; }		// only decrement filament counter if unattached to other FilSegments
		
			if (cleanF.nodeAtEnd1) {
				if ((cleanF.filAtEnd2) & (!isACut)) {
					transferEnd1Plasmid(cleanF,cleanF.end2Fil);
				} else {
					cleanF.end1Node.filamentOff(); 
				}
			}
			if (cleanF.nodeAtEnd2) { 
				if ((cleanF.filAtEnd1) & (!isACut)) {
					transferEnd2Plasmid(cleanF,cleanF.end1Fil);
				} else {
					cleanF.end2Node.filamentOff(); 
				}
			}
			
			for (int i=0;i<cleanF.arpChildCt;i++) { // detach any remaining Arp2/3 branches
				if (cleanF.arpActive[i]) {
					cleanF.removeArp23(i); 
				}
			}	
			
			// majorly messy code for a joining event... swapping links around for four possible arrangements
			if (!isACut && cleanF.filAtEnd1 && cleanF.filAtEnd2) {		// joining event.. 
				//talkln ("joining event.. setting links");
				if (cleanF.ptAtEnd1 == cleanF.end1Fil.end2) {
					cleanF.end1Fil.filAtEnd2 = true;
					cleanF.end1Fil.end2Fil = cleanF.end2Fil;
					cleanF.end1Fil.ptAtEnd2 = cleanF.ptAtEnd2;
				} else {
					cleanF.end1Fil.filAtEnd1 = true;
					cleanF.end1Fil.end1Fil = cleanF.end2Fil;
					cleanF.end1Fil.ptAtEnd1 = cleanF.ptAtEnd2;
				}
				
				if (cleanF.ptAtEnd2 == cleanF.end2Fil.end1) {
					cleanF.end2Fil.filAtEnd1 = true;
					cleanF.end2Fil.end1Fil = cleanF.end1Fil;
					cleanF.end2Fil.ptAtEnd1 = cleanF.ptAtEnd1;
				} else {
					cleanF.end2Fil.filAtEnd2 = true;
					cleanF.end2Fil.end2Fil = cleanF.end1Fil;
					cleanF.end2Fil.ptAtEnd2 = cleanF.ptAtEnd1;
				}
					
				// remove cleanF links
				cleanF.filAtEnd1 = false;
				cleanF.end1Fil = null;
				cleanF.ptAtEnd1 = null;
				cleanF.filAtEnd2 = false;
				cleanF.end2Fil = null;
				cleanF.ptAtEnd2 = null;
			}
			
			cleanF.breakAtEnd1();
			cleanF.breakAtEnd2();
			
			if (cleanF.monomerCt > 0) {
				Monomer curMon = cleanF.minusMon;
				Monomer tempMon;
				while (curMon != Monomer.plusGhost) {
					tempMon = curMon.frontMon;
					curMon.depolymerize(cleanF,Monomer.MINUSEND);
					curMon = tempMon;
				}
			}
			
			if (swapOut) { removeFilSegment(cleanF); }
	}
	
	public static void removeAll () {
		FilSegment curSeg;
		for (int i=0;i<filSegmentCt;i++) {
			curSeg = theFilSegments[i];
			try {
				curSeg.detachGraphics();
				cleanup(curSeg,false,true);
			} catch (NullPointerException npe) { }
		}
		for (int i=0;i<filSegmentCt;i++) {
			theFilSegments[i].removeMe = true;
			theFilSegments[i] = null;
		}
		filSegmentCt = 0;	// reset array counter
		filCt = 0;	// reset unique id counter
	}
	
	public boolean end2NodeForceOK () {
		//if (end2NodeForceThisStep < -Env.maxPolyForce.getValue()*1e-12) { 
		if (end2NodeForce.averageVal() < -Env.maxPolyForce.getValue()*1e-12) { 
			return false;
		} else {
			return true;
		}
	}
	
	public boolean inCompression(String endString) {
		if (Env.compressionCritOff) { return false; }
		
		// to match the experimental observation of growth rates same with or without plasmid, ignore this poly. crit unless colliding with bug
		if (nodeAtEnd1) { if (!end1Node.collidedWithBugThisStep) { return false; } }
		if (nodeAtEnd2) { if (!end2Node.collidedWithBugThisStep) { return false; } }
		
		if (compressionTrack.runningAverageVal() < Env.polyCompressionCutoff.getValue()) { 
			//talkln ("Yes Compression" + endString);
			return true;
		}
		//talkln ("No Compression" + endString);
		return false;
	}
	
	public void setCompression() {
		if (end1AxialF < 0 & end2AxialF < 0) {
			if (end1AxialF > end2AxialF) { 
				compressionTrack.registerValue(end1AxialF); 
			} else {
				compressionTrack.registerValue(end2AxialF); 
			}
		} else {
			compressionTrack.registerValue(0);
		}
	}
	
	public boolean stericHindranceEnd1() {
		if (end1TipC < halfmono) return true;
		return false;
	}
	
	public boolean stericHindranceEnd2() {
		if (end2TipC < halfmono) return true;
		return false;
	}
	
	public void incForceSum (Pt3D forceToAdd) {
		synchronized (forceSync) {
			forceSum.inc(forceToAdd);
		}
	}
	
	public void incForceSum (Pt3D forceToAdd, Pt3D forcePoint) {
		incForceSum(forceToAdd);
		rForce.sub(forcePoint,coord);
		rForce.scale(1e-6);	// units (from �m to m)
		tempTorq.cross(rForce, forceToAdd);
		incTorqueSum(tempTorq);
	}
	
	public void incTorqueSum (Pt3D torqueToAdd) {
		synchronized (torqueSync) {
			torqueSum.inc(torqueToAdd);
		}
	}
	
	public void incEnd1AxialForce (double incF) {
		end1AxialF += incF;
	}
	
	public void incEnd2AxialForce (double incF) {
		end2AxialF += incF;
	}
	
	public void isArp23Bound (boolean arpOn) {
		try {
			childOfArp23 = arpOn;
			minusMon.isArp = arpOn;
			minusMon.frontMon.isArp = arpOn;
			minusMon.frontMon.frontMon.isArp = arpOn;
		} catch (NullPointerException npe)  {talkln ("exception in isArp23Bound");}
	}
	
	public static void makeStaticFilament () {
		int monCt = 500;
		double xSpacing = 0.1;
		double ySpacing = 0.1;
		Pt3D loc0 = new Pt3D(xSpacing,ySpacing,0);
		Pt3D ang0 = new Pt3D(1,0,0);
		//StaticFilSegment newFil0 = new StaticFilSegment (loc0,ang0,-1,monCt,false);
		FilSegment newFil0 = new FilSegment (loc0,ang0,-1,monCt,false);
		
		AnchorNode end2Anchor0 = new AnchorNode(newFil0.end2);
		linkEnd2Node(newFil0,end2Anchor0);
		
		// second antiparallel filament
		Pt3D loc1 = new Pt3D(-xSpacing,-ySpacing,0);
		Pt3D ang1 = new Pt3D(-1,0,0);
		//StaticFilSegment newFil1 = new StaticFilSegment (loc1,ang1,-1,monCt,false);
		FilSegment newFil1 = new FilSegment (loc1,ang1,-1,monCt,false);
		
		AnchorNode end2Anchor1 = new AnchorNode(newFil1.end2);
		linkEnd2Node(newFil1,end2Anchor1);
	}
	
	public static void makeWestCircleFilaments () {
		int minMonCt = (int)(Env.circleFilsMinLength.getValue()/Env.actinMonoRadius) - 1;
		int maxMonCt = (int)(Env.circleFilsMaxLength.getValue()/Env.actinMonoRadius) - 1;
		int numFils = Env.westCircleFils.getIntValue();
		double baseRad = 0.25;
		Pt3D basePt = new Pt3D(-Env.boxXDim.getValue()/2,0,0);
		Pt3D endPt = new Pt3D();
		Pt3D uVec = new Pt3D();
		Pt3D coordPt = new Pt3D();
		for (int i=0;i<numFils;i++) {
			int monCt = (int)(Math.random()*(maxMonCt-minMonCt) + minMonCt);
			double length = (monCt+1)*Env.actinMonoRadius;
			double rdmAng = (Math.PI/6)*(2*Math.random()-1);
			double rdmRad = Math.random()*baseRad;
			double xAdd = rdmRad*Math.cos(rdmAng);
			double yAdd = rdmRad*Math.sin(rdmAng);
			endPt.x = basePt.x + xAdd;
			endPt.y = basePt.y + yAdd;
			uVec.unitVec(endPt,basePt);
			coordPt.add(endPt,length/2,uVec);
			
			if (Env.circleFilsMixedPolarity.isActive() && Math.random() < 0.5) {
				FilSegment newFil = new FilSegment (coordPt,uVec,-1,monCt,false);
				AnchorNode end1Anchor = new AnchorNode(newFil.end1);
				linkEnd1Node(newFil,end1Anchor);
			} else {
				uVec.reverse();
				FilSegment newFil = new FilSegment (coordPt,uVec,-1,monCt,false);
				AnchorNode end2Anchor = new AnchorNode(newFil.end2);
				linkEnd2Node(newFil,end2Anchor);
			}
		}
	}
	
	public static void makeEastCircleFilaments () {
		int minMonCt = (int)(Env.circleFilsMinLength.getValue()/Env.actinMonoRadius) - 1;
		int maxMonCt = (int)(Env.circleFilsMaxLength.getValue()/Env.actinMonoRadius) - 1;
		int numFils = Env.eastCircleFils.getIntValue();
		double baseRad = 0.25;
		Pt3D basePt = new Pt3D(Env.boxXDim.getValue()/2,0,0);
		Pt3D endPt = new Pt3D();
		Pt3D uVec = new Pt3D();
		Pt3D coordPt = new Pt3D();
		for (int i=0;i<numFils;i++) {
			int monCt = (int)(Math.random()*(maxMonCt-minMonCt) + minMonCt);
			double length = monCt*Env.actinMonoRadius;
			double rdmAng = (Math.PI/6)*(2*Math.random()-1);
			double rdmRad = Math.random()*baseRad;
			double xAdd = rdmRad*Math.cos(rdmAng);
			double yAdd = rdmRad*Math.sin(rdmAng);
			endPt.x = basePt.x - xAdd;
			endPt.y = basePt.y + yAdd;
			uVec.unitVec(endPt,basePt);
			coordPt.add(endPt,length/2,uVec);

			if (Env.circleFilsMixedPolarity.isActive() && Math.random() < 0.5) {
				FilSegment newFil = new FilSegment (coordPt,uVec,-1,monCt,false);
				AnchorNode end1Anchor = new AnchorNode(newFil.end1);
				linkEnd1Node(newFil,end1Anchor);
			} else {
				uVec.reverse();
				FilSegment newFil = new FilSegment (coordPt,uVec,-1,monCt,false);
				AnchorNode end2Anchor = new AnchorNode(newFil.end2);
				linkEnd2Node(newFil,end2Anchor);
			}
			
			
		}
	}
	
	public static void makeTestBranchedFilament() {
		Pt3D coordForBoth = new Pt3D(0,0,0);
		Pt3D fil1UVec = new Pt3D(0,0,-1);
		double testAngleBetween = 178; // in degrees
		double testAngleInRads = testAngleBetween*Math.PI/180;
		double xPart = Math.cos(testAngleInRads); 
		double yPart = Math.sin(testAngleInRads);
		Pt3D fil2UVec = new Pt3D(xPart,yPart,0); 
		int numMonomers = 128;
		FilSegment mFil = new FilSegment (coordForBoth,fil1UVec,0,numMonomers,false);

		double bLoc;
		for (int i=0;i<10;i++) {
			bLoc = Math.random()*(double)numMonomers*Env.actinMonoRadius;
			//bLoc = Math.random()*(double)(numMonomers/4)*Env.actinMonoRadius;
			if (mFil.canAddArpHere(bLoc)) {
				mFil.makeArpBranch(bLoc);
				//Arp23.makeBranch(mFil,bLoc);
			} else {
				talkln("Didn't make an Arp2/3 too close to another!");
			}
		} 
		//new FilSegment (coordForBoth,fil2UVec,1,600,false);
		
		// make test myosin minifilament
		//new MyoMiniFilament (coordForBoth,fil1UVec);
		//new MyoMiniFilament (coordForBoth,fil1UVec);
		//new MyosinDimer (coordForBoth,fil1UVec);
		
	} 
	
	public static void makeTestBranchedFilament(Pt3D loc) {
		Pt3D fil1UVec = new Pt3D(1,0,0);
		int numMonomers = 128;
		FilSegment mFil = new FilSegment (loc,fil1UVec,0,numMonomers,false);

		double bLoc;
		for (int i=0;i<10;i++) {
			bLoc = Math.random()*(double)numMonomers*Env.actinMonoRadius;
			//bLoc = Math.random()*(double)(numMonomers/4)*Env.actinMonoRadius;
			if (mFil.canAddArpHere(bLoc)) {
				mFil.makeArpBranch(bLoc);
				//Arp23.makeBranch(mFil,bLoc);
			} else {
				talkln("Didn't make an Arp2/3 too close to another!");
			}
		} 
	} 
	
	public static void firstActATest(Pt3D loc, Bug bug) {
		Pt3D fil1UVec = new Pt3D(1,0,0);
		int numMonomers = 135;
		FilSegment mFil = new FilSegment (loc,fil1UVec,0,numMonomers,false);
		//FilSegment mFil = new FilSegment (bug.end1,fil1UVec,0,numMonomers,false);
		Pt3D firstPos = Pt3D.Sub(bug.end1, bug.coord); // vector from bug.coord to bug.end1
		firstPos.XTox(bug);
		ActA firstActA = new ActA(firstPos, bug);
		//firstActA.setFil(mFil, numMonomers*Env.actinMonoRadius);
	
	} 
	
	public static void makeXLinkFromNodePair() { 
		// make test ProteinNodes
		double nodeOffset = 1.0; //microns
		ProteinNode nodeR = new ProteinNode(new Pt3D(nodeOffset,0,0),false);
		ProteinNode nodeL = new ProteinNode(new Pt3D(-nodeOffset,0,0),false);
		
		// have ProteinNodes make bespoke filaments for testing
		int mons = 600;
		Pt3D filLUVec = new Pt3D(1,0,0);
		Pt3D filRUVec = new Pt3D(-1,0,0);
		
		nodeL.bespokeNodeFilament(mons, filLUVec);
		nodeR.bespokeNodeFilament(mons, filRUVec);
	}
	
	public static void makeInitialFilaments () {
		if (!Env.remote) { 
			initializeAllAppearances();
			Monomer.initializeAllAppearances();
		}
		for (int i=0;i<Env.initialFilaments.getValue();i++) { 
			makeRandomFilament(Env.minFilLength.getValue(),Env.maxFilLength.getValue()); 
		}
	}
	
	public static void resetOrderedY () {
		openYCt = 0;
		positiveY = true;
		currentPosY = 0.5*incY;
		currentNegY = -0.5*incY;
	}
	
	public static double getOrderedY () {
		if (openYCt > 0) {
			return getOpenY();
		} else {
			double yVal = currentPosY;
			if (positiveY) {
				currentPosY += incY;
			} else {
				yVal = currentNegY;  
				currentNegY += -incY;
			}
			positiveY = !positiveY;
			return yVal;
		}
	}
	
	public static double getOpenY () {
		openYCt--;
		return openY[openYCt];
	}
	
	public static void putOpenY (double yVal) {
		openY[openYCt] = yVal;
		openYCt++;
	}
	
	synchronized static FilSegment makeArp23NucFilament (Pt3D nucPt, Pt3D nucAng) {
		//double nucLength = Env.actinSeed.getIntValue()*Env.actinMonoRadius;
		FilSegment newBranch = new FilSegment (nucPt,nucAng,-1);
		newBranch.isArp23Bound(true);;
		return newBranch;
	}

	public static void makeOrderedFilament () {
		double yVal = getOrderedY();
		Pt3D orderedLoc = new Pt3D(0,yVal,0);
		Pt3D orderedAng = new Pt3D(1,0,0);
		new FilSegment (orderedLoc,orderedAng,-1,Env.actinSeed.getIntValue(),false);
	}
	
	synchronized static void makeRandomFilament () {
		double nucLength = (Env.actinSeed.getIntValue()+1)*Env.actinMonoRadius;
		Pt3D randomLoc = theBox.rdmPtInside();
		Pt3D randomUVec = Pt3D.RandomUnitVec(Env.mtRNG);
		new FilSegment (randomLoc,randomUVec,-1);
	}
	synchronized static void makeRandomFilament (double minL, double maxL) {
		double filL = 0;
		Pt3D rdmLoc1= new Pt3D();
		Pt3D rdmLoc2= new Pt3D();
		while (filL < minL | filL > maxL) {
			rdmLoc1 = theBox.rdmPtInside();
			rdmLoc2 = theBox.rdmPtInside();
			filL = Pt3D.ptDist(rdmLoc1, rdmLoc2);
		}
		Pt3D randomOrient = Pt3D.UnitVec(rdmLoc2,rdmLoc1);
		rdmLoc2.add(rdmLoc1,filL/2,randomOrient);
		int monCt = (int)(filL/Env.actinMonoRadius)-1;
		FilSegment nuFil = new FilSegment (rdmLoc2,randomOrient,-1,monCt,false);
		
		if (false) { //(Math.random() < 0.2) {
			linkEnd2Node(nuFil,new AnchorNode(nuFil.end2));
		}
	}
	
	public static void makeTestFilament () {
		double filLength = 2*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(0,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		FilSegment newSeg = new FilSegment (loc,ang,-1,monCt,false);
	}
	
	public static void makeTestFilament (Pt3D loc) {
		double filLength = 2*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D ang = new Pt3D(1,0,0);
		FilSegment newSeg = new FilSegment (loc,ang,-1,monCt,false);
	}
	
	public static void makeWebOfActin (Pt3D startPt, double spread) {
		int numFils = 100;
		double filLength = 12*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D ang;
		Pt3D loc;
		Pt3D spreadDir;
		for (int i=0;i<numFils;i++) {
			ang = Pt3D.RandomUnitVec(Env.mtRNG);
			spreadDir = Pt3D.RandomUnitVec(Env.mtRNG);
			loc = Pt3D.Add(startPt, 2.5*spread,spreadDir);
			new FilSegment (loc,ang,-1,monCt,false);
		}
	}
	
	public static void makeLinkTstFils() {
		double filLength = 2*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(0,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		new FilSegment (loc,ang,-1,monCt,false);
		
		// make test myosin minifilament
		new MyoMiniFilament (loc,ang);
				
		filLength = 2*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		monCt = (int)(filLength/Env.actinMonoRadius);
		loc = new Pt3D(0,0,0);
		ang = new Pt3D(1,2,0);
		ang.unitVec();
		new FilSegment (loc,ang,-1,monCt,false);
		
		
	}
	
	public static void annealTst() {
		double filLength = 2*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(-5*Env.actinMonoDiam,0,0);
		Pt3D ang = new Pt3D(-1,0,0);
		FilSegment fil1 = new FilSegment (loc,ang,-1,monCt,false);
		
		monCt = (int)(filLength/Env.actinMonoRadius);
		loc = new Pt3D(filLength-10*Env.actinMonoDiam,0,0);
		ang = new Pt3D(1,0,0);
		ang.unitVec();
		FilSegment fil2 = new FilSegment (loc,ang,-1,monCt,false);
	}
	
	public static void crossedFilsTst() {
		double filLength = 2*(Env.tstNodeOffset-Env.nodeRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(-5*Env.actinMonoDiam,0,0);
		Pt3D ang = new Pt3D(1,-.9,0);
		FilSegment fil1 = new FilSegment (loc,ang,-1,monCt,false);
		
		monCt = (int)(filLength/Env.actinMonoRadius);
		loc = new Pt3D(0,0,0);
		ang = new Pt3D(-1,-.9,0);
		ang.unitVec();
		FilSegment fil2 = new FilSegment (loc,ang,-1,monCt,false);
	}
	
	public static void filWMyoMinisTst() {
		double filLength = 1.2;
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(0,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		FilSegment fil1 = new FilSegment (loc,ang,-1,monCt,false);
		
		monCt = (int)(filLength/Env.actinMonoRadius);
		loc = new Pt3D(0,0,0);
		ang = new Pt3D(-1,0,0);
		ang.unitVec();
		FilSegment fil2 = new FilSegment (loc,ang,-1,monCt,false);
	
		new MyoMiniFilament (fil1.coord);
		
		//new MyoMiniFilament (fil1.coord);
		
	}
	
	public static void twoNodesOneFilTst() {
		double filLength = 3; // micron
		//double filLength = 2*(Env.tstPlasmidOffset-Env.plasmidRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		double offset = 0.01;
		Pt3D loc = new Pt3D(offset,0,0);
		Pt3D loc2 = new Pt3D(-offset,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		Pt3D angR = new Pt3D(-1,.01,0);
		FilSegment fil1 = new FilSegment (loc,ang,-1,monCt,false);
		
		Pt3D p1Loc = Pt3D.Add(fil1.end2,-.5*Env.nodeRadius.getValue(),fil1.uVec);
		ProteinNode node1 = new ProteinNode (p1Loc,false);
		
		fil1.nodeAtEnd2=true;
		fil1.end2Node=node1;
		fil1.end2PAttachPt.zero();  // use this for formins at center of node
		fil1.forminVecInx.XTox(node1,ang);
		node1.filamentOn();
		
		Pt3D p2Loc = Pt3D.Add(fil1.end1,0.5*Env.nodeRadius.getValue(),fil1.uVecR);
		ProteinNode node2 = new ProteinNode (p2Loc,false);
		
		
	}
	
	public static void twoNodesTwoFilsTst() {
		double filLength = .3; // micron
		//double filLength = 2*(Env.tstPlasmidOffset-Env.plasmidRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		double offset = 0.01;
		Pt3D loc = new Pt3D(offset,0,0);
		Pt3D loc2 = new Pt3D(-offset,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		Pt3D angR = new Pt3D(-1,.01,0);
		FilSegment fil1 = new FilSegment (loc,ang,-1,monCt,false);
		
		Pt3D p1Loc = Pt3D.Add(fil1.end2,-.5*Env.nodeRadius.getValue(),fil1.uVec);
		ProteinNode node1 = new ProteinNode (p1Loc,false);
		
		fil1.nodeAtEnd2=true;
		fil1.end2Node=node1;
		fil1.end2PAttachPt.zero();  // use this for formins at center of node
		fil1.forminVecInx.XTox(node1,ang);
		node1.filamentOn();
		
		Pt3D p2Loc = Pt3D.Add(fil1.end1,0.5*Env.nodeRadius.getValue(),fil1.uVecR);
		ProteinNode node2 = new ProteinNode (p2Loc,false);
		// additional filaments
		FilSegment fil2 = new FilSegment (loc2,angR,-1,monCt,false);
		
		fil2.nodeAtEnd2=true;
		fil2.end2Node=node2;
		fil2.end2PAttachPt.zero();  // use this for formins at center of node
		fil2.forminVecInx.XTox(node2,angR);
		node2.filamentOn();
	
		
		
		//Pt3D p2Loc = Pt3D.Add(fil2.end2,Env.nodeRadius.getValue(),fil2.uVec);
		//new ProteinNode (p2Loc);
		
	}
	
	/*public static void nodeChainTst() {
		int numNodes = 10;
		double filLength = 0.2*Env.bugLength.getValue() - 4*Env.nodeRadius.getValue();
		double nodeSpacing = (filLength+Env.nodeRadius.getValue())/(numNodes-1);
		//double filLength = 2*(Env.tstPlasmidOffset-Env.plasmidRadius.getValue());
		int monCt = (int)(filLength/Env.actinMonoRadius);
		double offset = 0.01;
		Pt3D loc = new Pt3D(offset,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		FilSegment fil1 = new FilSegment (loc,ang,-1,monCt,false);
		
		Pt3D tempPt = new Pt3D();
		for (int i=0;i<numNodes-1;i++) {
			tempPt.add(fil1.end1,i*nodeSpacing,fil1.uVec);
			new ProteinNode (tempPt);
		}
		
		Pt3D p1Loc = Pt3D.Add(fil1.end2,1*Env.nodeRadius.getValue(),fil1.uVec);
		new ProteinNode (p1Loc);
		
	
	}*/

	public static void nodeChainTst() {
		int numNodes = 10;
		double zloc = Env.bugRadius.getValue();
		double filLength = Math.PI/2;//1.72;
		double nodeSpacing = (filLength+Env.nodeRadius.getValue())/(numNodes-1);
		Pt3D loc = new Pt3D(0,-filLength/2,zloc);
		Pt3D ang = new Pt3D(0,1,0);
		
		Pt3D tempPt = new Pt3D();
		for (int i=0;i<numNodes;i++) {
			tempPt.add(loc,i*nodeSpacing,ang);
			new ProteinNode (tempPt,false);
		}
	
	}
	
	public static void twoByTwoNodesTst() {
		double zloc = Env.bugRadius.getValue();
		double spacing = 0.1;
		new ProteinNode (new Pt3D(spacing,spacing,zloc),false);
		new ProteinNode (new Pt3D(-spacing,spacing,zloc),false);
		new ProteinNode (new Pt3D(spacing,-spacing,zloc),false);
		new ProteinNode (new Pt3D(-spacing,-spacing,zloc),false);
		
	}
	
	public static void threeByThreeNodesTst() {
		double zloc = 0;// Env.bugRadius.getValue();
		double spacing = 0.4;
		new ProteinNode (new Pt3D(-spacing,spacing,zloc),false);
		new ProteinNode (new Pt3D(0,spacing,zloc),false);
		new ProteinNode (new Pt3D(spacing,spacing,zloc),false);
		
		new ProteinNode (new Pt3D(-spacing,0,zloc),false);
		new ProteinNode (new Pt3D(0,0,zloc),false);
		new ProteinNode (new Pt3D(spacing,0,zloc),false);
		
		new ProteinNode (new Pt3D(-spacing,-spacing,zloc),false);
		new ProteinNode (new Pt3D(0,-spacing,zloc),false);
		new ProteinNode (new Pt3D(spacing,-spacing,zloc),false);
		
		new ProteinNode (new Pt3D(-spacing,-2*spacing,zloc),false);
		new ProteinNode (new Pt3D(0,-2*spacing,zloc),false);
		new ProteinNode (new Pt3D(spacing,-2*spacing,zloc),false);
		
	}
	
	public static void makeGlidingAssayFilament () {
		double filLength = Env.glidingFilamentLength.getValue();
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(Env.boxXDim.getValue()/2-filLength/2,0,0);
		Pt3D ang = new Pt3D(1,0,0);
		FilSegment newSeg = new FilSegment (loc,ang,-1,monCt,false);
	}
	
	


	synchronized static void setFilamentID (FilSegment newFilSeg, int filID) {
		if (filID == -1) {
			newFilSeg.filID = filIDCt;
			filCt++;
			filIDCt++;
		} else {
			newFilSeg.filID = filID;
		}
	}
	
	synchronized static void addFilSegment (FilSegment newFilament) {
		theFilSegments[filSegmentCt] = newFilament;
		theFilSegments[filSegmentCt].filArrayPos = filSegmentCt;
		filSegmentCt ++;
	}
	public static void removeFilSegment ( FilSegment byeFilament) {
		synchronized (filSync) {
			int swapId = byeFilament.filArrayPos;
			theFilSegments[swapId] = theFilSegments[filSegmentCt-1];
			theFilSegments[swapId].filArrayPos = swapId;
			filSegmentCt --;
			byeFilament.removeMe = true;
		}
	
	}
	public static void spawnRdmFilaments () {
		if (Env.mtRNG.nextDouble() < Env.kRdmNuc.getValue()*Math.pow(theBox.getMonomerConc(),Env.actinSeed.getIntValue())*Env.deltaT.getValue()) {
			makeRandomFilament();
		}
	}
	
	
	public void ptFromHelixPos (Pt3D pt, double pos, double initAng) {
		double curAng = initAng + pos*Env.helixPitch;
		pt.x = pos;
		pt.y = Env.helixMonOffset*Math.cos(curAng);
		pt.z = Env.helixMonOffset*Math.sin(curAng);
	}
	
	public void resetGraphics () {
		if (Env.monomerGraphics) {
			Monomer curMon = minusMon;
			while (curMon != Monomer.plusGhost) {
				curMon.resetGraphics(this);
				curMon = curMon.frontMon;
			}
		}
		updateCylGraphicsFlag = true;
		updateGraphics();
			
	}
	
	public static void resetGraphicsAll () {
		for (int i=0;i<filSegmentCt;i++) {
			theFilSegments[i].resetGraphics();
		}
	}
	
	public int countLinkedMonomers () {
		int monCt = 0;
		Monomer curMon = minusMon;
		while (curMon != Monomer.plusGhost) {
			monCt++;
			curMon = curMon.frontMon;
		}
		return monCt;
	}
	
	public static int getNumberOfFilaments () {
		int filamentCt = 0;
		for (int i=0;i<filSegmentCt;i++) {
			// only count pointed-ends
			if (!theFilSegments[i].filAtEnd1) { filamentCt++; }
		}
		return filamentCt;
	}
	
	public static double lengthSum () {
		double lengthSum = 0;
		for (int i=0;i<filSegmentCt;i++) {
			lengthSum += theFilSegments[i].length;
		}
		return lengthSum;
	}
	
	public static int linkedMonomerSum () {
		int monSum = 0;
		for (int i=0;i<filSegmentCt;i++) {
			monSum += theFilSegments[i].countLinkedMonomers();
		}
		return monSum;
	}
	
	public static int monomerSum () {
		int monSum = 0;
		for (int i=0;i<filSegmentCt;i++) {
			monSum += theFilSegments[i].monomerCt;
		}
		return monSum;
	}
	
	public void updateMonomerPositions () {
		boolean even = true;
		double oppAng = helixAng + Math.PI;
		double pos = Env.actinMonoRadius;
		Monomer curMon = minusMon;
		while (curMon != Monomer.plusGhost) {
			if (curMon==null) { talkln ("skipping updateMonomerNoGraphics for " + String.valueOf(this) + " 'cause a monomer is null"); return; }
			if (curMon.removeMe) { talkln ("skipping updateMonomerNoGraphics for " + String.valueOf(this) + " 'cause a monomer is to be removed"); return; }
			if (even) {
				ptFromHelixPos(curMonStart,pos,helixAng);
				curMonStart.xToX(this);
				curMonStart.add(end1);
				curMon.updatePosition(curMonStart);
			} else {
				ptFromHelixPos(curMonStart,pos,oppAng);
				curMonStart.xToX(this);
				curMonStart.add(end1);
				curMon.updatePosition(curMonStart);
			}
			even = !even;
			pos += Env.actinMonoRadius;
			curMon = curMon.frontMon;
		}
	}
	
	public void updateMonomerGraphics () {
		boolean twoPoints = !Env.helixSpheres;
		boolean even = true;
		double oppAng = helixAng + Math.PI;
		double pos = 0;
		boolean endCap = false;
		if (!twoPoints) { pos += Env.actinMonoRadius; }		// if indicating sphere centers
		Monomer curMon = minusMon;
		while (curMon != Monomer.plusGhost) {
			if (curMon==null) { talkln ("skipping updateMonomerGraphics for " + String.valueOf(this) + " 'cause a monomer is null"); return; }
			if (curMon.removeMe) { talkln ("skipping updateMonomerGraphics for " + String.valueOf(this) + " 'cause a monomer is to be removed"); return; }
			if (even) {
				ptFromHelixPos(curMonStart,pos,helixAng);
				curMonStart.xToX(this);
				curMonStart.add(end1);
				if (twoPoints) {
					ptFromHelixPos(curMonStop,pos+Env.actinMonoDiam,helixAng);
					curMonStop.xToX(this);
					curMonStop.add(end1);
				}
			} else {
				ptFromHelixPos(curMonStart,pos,oppAng);
				curMonStart.xToX(this);
				curMonStart.add(end1);
				if (twoPoints) {
					ptFromHelixPos(curMonStop,pos+Env.actinMonoDiam,oppAng);
					curMonStop.xToX(this);
					curMonStop.add(end1);
				}
			}
			// other conditions which affect graphics representation
			if (end2Capped & (curMon==plusMon)) { endCap = true; }
			curMon.updateGraphics(this,curMonStart,curMonStop,endCap);
			
			
			even = !even;
			pos += Env.actinMonoRadius;
			curMon = curMon.frontMon;
			
			//reset other condition flags
			endCap = false;
		}
	}
	
	public void setRenderThicken () {
		if (renderThicken != Env.filRenderThicken.getValue()) {
			renderThicken = Env.filRenderThicken.getValue();
			updateCylGraphicsFlag = true;
		}
	}

	private void makeNewCyl () {}
	 
	private void updateCylGraphics () {}
	
	private void makeCoordinateSysGraphics () {}
	
	public static void initializeAllAppearances () {}
	public void makeGraphics () {}
	public void updateGraphics () {}
	public void detachGraphics () {}
	public void addCoordSysGraphics () {}
	public void removeCoordSysGraphics () {}
	
	public double getEffADPLength() {	// write method to figure length from end1 that is a certain high percentage ADP
		return 0;
	}
	
	public double getEffADPPiLength() {  // write method to figure length from end1 that is a certain high percentage ADP-Pi
		return 0;
	}
	
	public void hydrolizeInFilaments () {
		Monomer curMon = minusMon;
		cofilinCt = 0;
		while (curMon!=Monomer.plusGhost) {
			curMon.checkHydrolysisCofilinTropo(this);	
			if (curMon.cofilinOn) { cofilinCt++; }
			if (curMon.frontMon == null) { 
				talkln ("null curMon.frontMon in FilSegment.hydrolizeInFilaments()... skipping that FilSegment with monomerCt of " + monomerCt);
				return; // just leave method if there's an errant filament 
			} 
			curMon=curMon.frontMon;	
		}
	}
	
	public void checkCofilinDissolve () {
		double curCofilinRatio = ((double)cofilinCt)/((double)monomerCt);
		if (curCofilinRatio > Env.cofilinRatio.getValue()) {
			cleanup(this,true,true);
			removeMe = true;
		}
	}
	
	/*public void setCofilinAppearance () {
		double noCofRatio = ((double)(monomerCt-cofilinCt))/((double)monomerCt);
		if (noCofRatio>0.9) { setAppearance(FilSegment.ninetyApp); return;} 
		if (0.9>=noCofRatio && noCofRatio>0.8) { setAppearance(FilSegment.eightyApp); return;} 
		if (0.8>=noCofRatio && noCofRatio>0.7) { setAppearance(FilSegment.seventyApp); return;} 
		if (0.7>=noCofRatio && noCofRatio>0.6) { setAppearance(cylApp = FilSegment.sixtyApp); return;} 
		if (0.6>=noCofRatio && noCofRatio>0.5) { setAppearance(FilSegment.fiftyApp); return;} 
		if (0.5>=noCofRatio && noCofRatio>0.4) { setAppearance(FilSegment.fortyApp); return;} 
		if (0.4>=noCofRatio && noCofRatio>0.3) { setAppearance(FilSegment.thirtyApp); return;} 
		if (0.3>=noCofRatio && noCofRatio>0.2) { setAppearance(FilSegment.twentyApp); return;} 
		if (0.2>=noCofRatio && noCofRatio>0.1) { setAppearance(FilSegment.tenApp); return;} 
		if (noCofRatio >= 0) { setAppearance(FilSegment.zeroApp); } 

		
	}*/
	
	public void setCofilinAppearance () {}

	public void setADPAppearance () {}
	
	public static void updateAllMonomerPositions () {
		if (Env.noMonomersSimd.isActive()) { return; }
		for (int i=0;i<FilSegment.filSegmentCt;i++) {
			FilSegment.theFilSegments[i].updateMonomerPositions();
		}
	}
	
	//**** statistics
	public static Stat filLengthStatistics () {
		double curLength;
		double filLengthSum = 0;
		double filLengthSqrdSum = 0;
		int filNum = 0;
		for (int i=0;i<filSegmentCt;i++) {
			curLength = theFilSegments[i].length;
			filLengthSum += curLength;
			filLengthSqrdSum += curLength*curLength;
			filNum++;
		}
		double lengthMean = filLengthSum/filNum;
		double lengthStdDev = Math.sqrt((filLengthSqrdSum - (filLengthSum*filLengthSum/filNum))/(filNum-1));
		Stat filStat = new Stat (lengthMean,lengthStdDev);
		return filStat;
	}
	
	public static void reportAllFilaments () {
		for (int i=0;i<filSegmentCt;i++) {
			
		}
	}
	
	public String getSphereCapJSonString () {
		/* Format for capping protein JSON Serialization for Simularium
          1000.0,// visualization type : default
          10000+filID,   // agent instance ID
          4,   	 // agent type ID --CAP
          coord.x,  // position X
          coord.y,  // position Y
          coord.z,  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.radOfCap,   // radius
          0.0,   // number of subpoint values following this number
		*/
		// id number
		int capBaseID = 30000;
		int id = capBaseID+filArrayPos;
		FileOps.addJSonID(id);
		
		Pt3D capEndPt = Pt3D.Add(end2, Env.radOfCap,uVec);
		String capXStr = String.format("%.2f",Env.simJSonsScale*end2.x);
		String capYStr = String.format("%.2f",Env.simJSonsScale*end2.y);
		String capZStr = String.format("%.2f",Env.simJSonsScale*end2.z);
		String capEndXStr = String.format("%.2f",Env.simJSonsScale*capEndPt.x);
		String capEndYStr = String.format("%.2f",Env.simJSonsScale*capEndPt.y);
		String capEndZStr = String.format("%.2f",Env.simJSonsScale*capEndPt.z);
		String capDStr = String.format("%.2f",Env.simJSonsScale*2*Env.radOfCap);
		// Assemble serialization...
		String capString = "1000,"+id+",4,";
		capString += capXStr + "," + capYStr + "," + capZStr + ",";
		capString += "0.0,0.0,0.0,";
		capString += capDStr + ",";
		capString += "0.0,";
		
		return capString;
	}
	
	public String getFiberCapJSonString () {
		/* Format for capping protein JSON Serialization for Simularium
          1001.0,// visualization type : fiber
          capBaseID+filID,   // agent instance ID
          4,   	 // agent type ID --CAP
          coord.x,  // position X
          coord.y,  // position Y
          coord.z,  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.radOfCap,   // radius
          0.0,   // number of subpoint values following this number
		*/
		// id number
		int capBaseID = 30000;
		int id = capBaseID+filArrayPos;
		if (filArrayPos > 10000) { System.out.println("filArrayPos > 10000!!! Problem with JSon Id system");}
		FileOps.addJSonID(id);
		
		Pt3D capEndPt = Pt3D.Add(end2, Env.radOfCap,uVec);
		String capXStr = String.format("%.2f",Env.simJSonsScale*end2.x);
		String capYStr = String.format("%.2f",Env.simJSonsScale*end2.y);
		String capZStr = String.format("%.2f",Env.simJSonsScale*end2.z);
		String capEndXStr = String.format("%.2f",Env.simJSonsScale*capEndPt.x);
		String capEndYStr = String.format("%.2f",Env.simJSonsScale*capEndPt.y);
		String capEndZStr = String.format("%.2f",Env.simJSonsScale*capEndPt.z);
		String capDStr = String.format("%.2f",Env.simJSonsScale*2*Env.radOfCap);
		// Assemble serialization...
		String capString = "1001,"+id+",4,";
		capString += capXStr + "," + capYStr + "," + capZStr + ",";
		capString += "0.0,0.0,0.0,";
		capString += capDStr + ",";
		capString += "6.0,";
		capString += capXStr+","+capYStr+","+capZStr+",";
		capString += capEndXStr+","+capEndYStr+","+capEndZStr+",";
		
		return capString;
	}
	
	public String getJSonString () {
		/* Format for actin filament JSON Serialization for Simularium
		 * option of three distinct two-point fibers, one for each biochem state (ADP, ADP-Pi, ATP), or just one line per filament
          1001.0,// visualization type : fiber
          filID,   // agent instance ID
          1,   	 // agent type ID --ADP
          coord.x,  // position X
          coord.y,  // position Y
          coord.z,  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.gActinDiameter,   // radius
          6.0,   // number of subpoint values following this number
          end1.x,
          end1.y,
          end1.z,
          end1.x + ADPLength*uVec.x,
          end1.y + ADPLength*uVec.y,
          end1.z + ADPLength*uVec.z,
          * and likewise for the other two segments
		*/
		if (!coord.checkPt3D()) { return "";}	// sanity check... if something wrong with actin position then skip serialization
		// points in space of different biochem sections
		/*Pt3D adpPt = Pt3D.Add(end1,getEffADPLength(),uVec);			
		Pt3D adpPiPt = Pt3D.Add(adpPt,getEffADPPiLength(),uVec);
		// coord
		String coordXStr = String.format("%.2f",Env.simJSonsScale*coord.x);
		String coordYStr = String.format("%.2f",Env.simJSonsScale*coord.y);
		String coordZStr = String.format("%.2f",Env.simJSonsScale*coord.z);
		// adp endpoint
		String adpPtXStr = String.format("%.2f",Env.simJSonsScale*adpPt.x);
		String adpPtYStr = String.format("%.2f",Env.simJSonsScale*adpPt.y);
		String adpPtZStr = String.format("%.2f",Env.simJSonsScale*adpPt.z);
		// adp-Pi endpoint
		String adpPiPtXStr = String.format("%.2f",Env.simJSonsScale*adpPiPt.x);
		String adpPiPtYStr = String.format("%.2f",Env.simJSonsScale*adpPiPt.y);
		String adpPiPtZStr = String.format("%.2f",Env.simJSonsScale*adpPiPt.z); */
		// end1
		String end1XStr = String.format("%.2f",Env.simJSonsScale*end1.x);
		String end1YStr = String.format("%.2f",Env.simJSonsScale*end1.y);
		String end1ZStr = String.format("%.2f",Env.simJSonsScale*end1.z);
		// end2
		String end2XStr = String.format("%.2f",Env.simJSonsScale*end2.x);
		String end2YStr = String.format("%.2f",Env.simJSonsScale*end2.y);
		String end2ZStr = String.format("%.2f",Env.simJSonsScale*end2.z);
		// size
		String actinDStr = String.format("%.2f",Env.simJSonsScale*Env.radOfActin);
		// id number
		int id = filArrayPos;
		FileOps.addJSonID(id);
		// assemble the serialization...
		String filString;
		if (notADPRatio > 0.66) {
			// make it all ATP color
			filString = "1001,"+id+",3,";
			filString += "0.0,0.0,0.0,";
			filString += "0.0,0.0,0.0,";
			filString += actinDStr + ",";
			filString += "6.0,";
			filString += end1XStr+","+end1YStr+","+end1ZStr+",";
			filString += end2XStr+","+end2YStr+","+end2ZStr+",";
		} else if (notADPRatio > 0.33) {
			// make it all ADP-Pi color
			filString = "1001,"+id+",2,";
			filString += "0.0,0.0,0.0,";
			filString += "0.0,0.0,0.0,";
			filString += actinDStr + ",";
			filString += "6.0,";
			filString += end1XStr+","+end1YStr+","+end1ZStr+",";
			filString += end2XStr+","+end2YStr+","+end2ZStr+",";	
		} else {
			// make it all ADP color
			filString = "1001,"+id+",1,";
			filString += "0.0,0.0,0.0,";
			filString += "0.0,0.0,0.0,";
			filString += actinDStr + ",";
			filString += "6.0,";
			filString += end1XStr+","+end1YStr+","+end1ZStr+",";
			filString += end2XStr+","+end2YStr+","+end2ZStr+",";
		}
		if (end2Capped) { filString += getSphereCapJSonString(); }	// add info on plus-end cap if applicable
		return filString;
	}
	
}


