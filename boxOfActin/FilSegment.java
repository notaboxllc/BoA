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

	// SoA arrays for GPU-ready collision path
	// Step 1a: filament endpoints + filament ID
	// Step 1b: filament orientation (uVecAsPt3D()) + nodeAtEnd2 gate — fine-check inputs
	static double[]  soaEnd1X      = new double[1000000];
	static double[]  soaEnd1Y      = new double[1000000];
	static double[]  soaEnd1Z      = new double[1000000];
	static double[]  soaEnd2X      = new double[1000000];
	static double[]  soaEnd2Y      = new double[1000000];
	static double[]  soaEnd2Z      = new double[1000000];
	static int[]     soaFilID      = new int[1000000];
	static double[]  soaUX         = new double[1000000];
	static double[]  soaUY         = new double[1000000];
	static double[]  soaUZ         = new double[1000000];
	static boolean[] soaNodeAtEnd2 = new boolean[1000000];

	static void fillSoaArrays() {
		// Read per-segment pose from the canonical SoA pose arrays
		// (Thing.soaCoord / soaUVec) instead of chasing the per-segment Pt3D
		// references. end1Pt / end2Pt are derived from coordAsPt3D() ± (length/2)*uVecAsPt3D() —
		// same formula that FilSegment.initialize() uses. Computing them here
		// avoids a stale-end risk if the segment length changed between
		// initialize() calls.
		final float[] soaCoordArr = Thing.soaCoord;
		final float[] soaUVecArr  = Thing.soaUVec;
		for (int i = 0; i < filSegmentCt; i++) {
			FilSegment fs = theFilSegments[i];
			final int b = fs.myThingNumber * 3;
			final double cx = soaCoordArr[b];
			final double cy = soaCoordArr[b + 1];
			final double cz = soaCoordArr[b + 2];
			final double ux = soaUVecArr[b];
			final double uy = soaUVecArr[b + 1];
			final double uz = soaUVecArr[b + 2];
			final double half = 0.5 * fs.length;
			soaEnd1X[i] = cx - half * ux;
			soaEnd1Y[i] = cy - half * uy;
			soaEnd1Z[i] = cz - half * uz;
			soaEnd2X[i] = cx + half * ux;
			soaEnd2Y[i] = cy + half * uy;
			soaEnd2Z[i] = cz + half * uz;
			soaFilID[i] = fs.filID;
			soaUX[i]    = ux;
			soaUY[i]    = uy;
			soaUZ[i]    = uz;
			soaNodeAtEnd2[i] = fs.nodeAtEnd2;
		}
	}

	static int filamentRenderCt = 0;	// for rendering only
	static int filSegRenderCt = 0;		// for rendering only
	static double monosize=Env.actinMonoDiam;  
	static double halfmono=Env.actinMonoRadius;
	static double radius = Env.actinWidth/2.0;		// (nm) radius of actin filament
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
	
	// end1Pt/end2Pt live on Thing now; bridgeDerivedToPt3D writes them after
	// every GPU step. CPU readers (collision, links, mesh, output, etc.)
	// still chase fs.getEnd1X() — that works because the bridge has populated
	// the inherited Pt3D fields.
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
	// Removed 2026-05-17 (Round 7): mechanism fully commented out; see JOURNAL.md.
	// int numViscBlobs = 0;
	
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
	boolean forminMother = false;  // implicit-formin-nucleated linear MOTHER (not an Arp2/3 branch product)
	Pt3D randForcesInX = new Pt3D();
	Pt3D randTorquesInX = new Pt3D();
	
	// info about end states and segments
	boolean end2Capped = false;
	
	boolean filAtEnd1 = false;
	boolean filAtEnd2 = false;
	FilSegment end1Fil = null;
	FilSegment end2Fil = null;
	// Live per-FilSegment Pt3D snapshots of end1/end2 positions. Refreshed by
	// initialize() each step from SoA. These are auxiliary Pt3D handles used by
	// CPU value-readers in collision/link/biochem/output paths; the SoA arrays
	// remain canonical.
	Pt3D end1Pt = new Pt3D();
	Pt3D end2Pt = new Pt3D();
	// Inc 1 (2026-06-09): A1 conversion. Neighbour endpoint identity used to be
	// encoded by the reference-identity check `ptAtEnd? == endNFil.end?Pt`, with
	// ptAtEnd? aliased to the neighbour's stable end1Pt/end2Pt Pt3D. That was
	// brittle (any mistaken-=, default-Pt3D, or accidental copy silently corrupted
	// orientation). Replaced by a stored byte: end1NbrSide / end2NbrSide.
	//   side = 0 → my endN attaches to neighbour's end1
	//   side = 1 → my endN attaches to neighbour's end2
	// Meaningful only when the corresponding filAtEnd? flag is true. Set in
	// setEnd1Links / setEnd2Links / cleanup join-event. Promoted from the
	// derive-each-step pattern already present at GPUMoveThing.java:4001/4011.
	byte end1NbrSide = 0;
	byte end2NbrSide = 0;
	boolean end1LinkCkd = false;
	boolean end2LinkCkd = false;
	boolean end1TorqCkd = false;
	boolean end2TorqCkd = false;
	// Phase 2 F3/F4 (2026-06-02): set by GPUMoveThing.classifyThings() when
	// this segment AND every active chain neighbour are GPU-handled and
	// DIAG_CPU_F3F4 is false — meaning the device chainPairForcesKernel
	// will compute this segment's F3/F4 contributions. step() gates its
	// own addLinkForces / addTorsionSpringForces calls on !gpuChainHandled
	// so the device and CPU never double-apply (Lesson 1 — per-force gate,
	// not per-dispatch). Mixed-state chains (e.g. one neighbour is a
	// CPU-fallback branched seg) keep gpuChainHandled = false; the CPU
	// pair then runs both sides locally and the device kernel returns
	// early at this slot.
	boolean gpuChainHandled = false;
	// Phase 2 F1 (2026-06-03): set by GPUMoveThing.classifyThings() when
	// this segment is GPU-handled, Thing.theBox instanceof Chamber,
	// simOutsideBug is inactive, and DIAG_CPU_F1 is false — meaning the
	// device boundaryBoxKernel will compute this segment's box from-inside
	// wall force/torque. checkBugOrBoxCollision() gates the CPU
	// checkBugCollisionFromInside call on !gpuBoundaryHandled so device and
	// CPU never double-apply (per-force gate per Lesson 1). The Listeria
	// from-outside path keeps running on CPU unconditionally — the gate
	// applies only to the from-inside branch.
	boolean gpuBoundaryHandled = false;
	
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
	double end1TipC = 1e6; // large number for initial tip clearance of end1Pt
	double end2TipC = 1e6; // large number for initial tip clearance of end2Pt
	boolean end2NearArpFactor = false;  // use for deciding when to branch... hacky for now
	StickyNode end2NearArpNode = null;  // the nearest hot-Rho node gating this barbed end (for the per-node Arp2/3 field)
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
	boolean brownianOff = false;  // per-segment Brownian suppression (AND with global Env.brownianFilMotionOff)
	boolean isLpSeg = false;      // true for segments belonging to the LP benchmark chain
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

	// CrossProbe (A2 diagnostic) per-segment prev-state — packed AABB cell range
	// and min-corner cell from the previous probed step. Sentinel = unset (new
	// segment). Travels through swap-compaction with the object.
	long probePrevAabb   = Long.MIN_VALUE;
	int  probePrevCenter = Integer.MIN_VALUE;

	double helixAng = 2*Math.PI*currentScratch().rng.nextDouble();	// keeps track of the helix angle of minusMon.. starts randomly
	int monomerCt = 0;

	// renderThicken: read by setRenderThicken() — dead call chain, defer to Phase 6
	double renderThicken = Env.filRenderThicken.getValue();

	public FilSegment (Pt3D initCoord, Pt3D initUVec, int filID) {
		super(initCoord);
		//synchronized (filSync) {
			setUVec(initUVec);
			setYVec(Pt3D.RandomUnitVec(currentScratch().rng));
			monomerCt = Env.actinSeed.getIntValue();
			length = (monomerCt+1)*Env.actinMonoRadius;
			addFilSegment(this);
			setFilamentID(this, filID);
			calculateProperties();
			pushPoseToSoa();   // bridge: subclass set uVecAsPt3D()/yVecAsPt3D(); flush before initialize() reads SoA
			initialize();
			makeInitialMonomers();
			theBox.takeMonomer(monomerCt);
		//}
	}

	public FilSegment (Pt3D initCoord, Pt3D initUVec, int filID, int monomerCt, boolean fromFile) {
		super(initCoord);
		//synchronized (filSync) {
			setUVec(initUVec);
			setYVec(Pt3D.RandomUnitVec(currentScratch().rng));
			this.monomerCt = monomerCt;
			length = (monomerCt+1)*Env.actinMonoRadius;
			addFilSegment(this);
			setFilamentID(this, filID);
			calculateProperties();
			pushPoseToSoa();   // bridge: subclass set uVecAsPt3D()/yVecAsPt3D(); flush before initialize() reads SoA
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
			setUVec(initUVec);
			setYVec(Pt3D.RandomUnitVec(currentScratch().rng));
			this.monomerCt = monomerCt;
			length = (monomerCt+1)*Env.actinMonoRadius;
			addFilSegment(this);
			setFilamentID(this, splitFromFil.filID);
			globalNodeAtEnd1 = splitFromFil.globalNodeAtEnd1;
			globalNodeAtEnd2 = splitFromFil.globalNodeAtEnd2;
			calculateProperties();
			pushPoseToSoa();
			initialize();
			setEnd1Links(splitFromFil, true);
			if (splitFromFil.filAtEnd2) {
				if (splitFromFil.end2NbrSide == 0) { // same orientation: my end2 → neighbour's end1
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
	
	public void sepaku () {
		super.sepaku();
		filLinkOSync = null;
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
	}
	
	public static void setBiophysValues () {
		maxPolyForce = Env.kTOverDelta*Math.log(Env.actinConc.getValue()/Env.actinCritConc);
	}
	
	public void calculateProperties () {
		// define the constants for motion of this rod in viscous medium
		// Remember that the dimensions we've been using are in micrometers so....
		// **WARNING** below a certain number of monomers, depending on values like aParallel, etc
		// the rod approximation will give NaN... hence the IF statement below
		// Elevated drag floor applies ONLY to Arp2/3 daughters (motherFil != null), so
		// unbranched/free filaments keep correct slender-body drag. Daughters get drag as if
		// at least filDragMinMonomers long; rotational drag (~L^3) is over-damped, stabilizing
		// the stiff branch constraint at larger dt (viscous-blob-style, scoped to branches).
		final int baseFloor = 30;  // original interior-segment floor (rod-approx NaN guard)
		int minMonomerCt = (motherFil != null) ? Math.max(baseFloor, Env.filDragMinMonomers.getIntValue()) : baseFloor;
		double minLength;
		if (filAtEnd1 | filAtEnd2) {
			minLength = Math.max(Env.stdSegLength.getIntValue(), minMonomerCt)*halfmono;  // never below the end-filament rod-approx floor
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
		bRotGam.y = (Math.PI*Env.aeta.getValue()*(asIfLengthM*asIfLengthM*asIfLengthM))/(3*(denomLogTerm + aTurning));
		bRotGam.z = bRotGam.y;
		
		// Viscous-blob drag addition — removed 2026-05-17 (Round 7); see JOURNAL.md.
		// Was a hack for Listeria motility experiments (Rafelski paper): filaments
		// accumulate sphere-drag blobs representing implicit crosslinks to unlisted
		// cellular components. Caused bRotGam to jump 560× at vBlobMinMons (default 50),
		// stopping rotation entirely and producing the "stepped chain" artifact.
		// if (Env.useViscousBlob.isActive()) {
		//     bTransGam.add(bTransGam,numViscBlobs,Env.blobTransGam);
		//     bRotGam.add(bRotGam,numViscBlobs,Env.blobRotGam);
		// }
		
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
		pushDragToSoa();

		/*if (!bTransGam.checkPt3D()) { talkln ("bTransGam is crazy for FilSegment"); }
		if (!bRotGam.checkPt3D()) { talkln ("bRotGam is crazy for FilSegment"); }
		if (!bTransDiff.checkPt3D()) { talkln ("bTransDiff is crazy for FilSegment"); }
		if (!bRotDiff.checkPt3D()) { talkln ("bRotDiff is crazy for FilSegment"); }
		*/

	}
	
	// Phase 4.5 diag (2026-06-05): count initialize() calls on the -gpu path.
	// initialize() locally fetches getEnd1X/Y/Z and writes them into end1Pt/end2Pt.
	// On steady-state GPU steps the GPU kernel updates device coord/uVec but does
	// not push refreshed end1/end2 into Thing.soaEnd1[] until
	// refreshHostMirrorsForOutput; if CPU-side initialize() runs per-step it would
	// rewrite end1Pt with the stale soaEnd1 value, propagating the staleness.
	public static long DIAG_FILSEG_INIT_CT = 0;

	public void initialize () {
		if (Env.useGPU) DIAG_FILSEG_INIT_CT++;
		// Canonical pose lives in SoA arrays; derived end1/end2/zVec/transXTox
		// are recomputed in bulk. length may have changed due to poly/depoly/split.
		length = (monomerCt+1)*Env.actinMonoRadius;
		pushLengthToSoa(length);
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
		// Refresh stable end1Pt/end2Pt Pt3D references — auxiliary handles read
		// by CPU value-readers (collision, links, biochem, output). Identity is
		// no longer encoded here; see end1NbrSide / end2NbrSide.
		end1Pt.x = getEnd1X(); end1Pt.y = getEnd1Y(); end1Pt.z = getEnd1Z();
		end2Pt.x = getEnd2X(); end2Pt.y = getEnd2Y(); end2Pt.z = getEnd2Z();
		// for collision detection
		xRange = Math.abs(getCoordX()-getEnd2X());
		yRange = Math.abs(getCoordY()-getEnd2Y());
		zRange = Math.abs(getCoordZ()-getEnd2Z());
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
		incCoord(dist, vec);
		// end1Pt/end2Pt are derived from coordAsPt3D() + length·uVecAsPt3D(); recompute now so
		// readers between translate() and the next initialize() see fresh values.
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
	}

	public void translateCoord (double dist, Pt3D vec) {
		incCoord(dist, vec);
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
	}
	
	public void step () {
		if (isLpSeg && Env.lpActive.getValue() == 0) return;
		// increment counters that control how often different bits are run
		collCheckCt++;

		/*if (collCheckCt >= collisionCheckInt | Env.simulationTime == 0) {
			checkBugOrBoxCollision(); 		// these should add forces and torques to forceSum and torqueSum
			if (Env.simulationTime < 0.001) { checkForminBinding(); }

			collCheckCt = 0;
		}*/
		long _spT;
		_spT = StepProfiler.t0();
		checkBugOrBoxCollision(); 		// these should add forces and torques to forceSum and torqueSum
		StepProfiler.add(StepProfiler.F1_2_FILSEG_BOUNDARY, _spT);

		// Phase 2 F3/F4 — when the device chainPairForcesKernel handles this
		// segment's chain link + torsion, skip the CPU pair here. F1 (above)
		// and F5/F6 (below) keep running — Lesson 1 dictates a per-force
		// gate, not a per-step()-dispatch gate, so the unported forces don't
		// silently drop. Mixed chains (gpuChainHandled=false despite GPU
		// being on for this segment) run the CPU pair as before.
		if (!gpuChainHandled) {
			_spT = StepProfiler.t0();
			addLinkForces();				// if linked to other segments
			StepProfiler.add(StepProfiler.F3_FILSEG_CHAIN_LINK, _spT);

			_spT = StepProfiler.t0();
			addTorsionSpringForces();		// bending rigidity proxy
			StepProfiler.add(StepProfiler.F4_FILSEG_CHAIN_TORQUE, _spT);
		}

		_spT = StepProfiler.t0();
		addNodeForces();				// calculate elastic forces to keep filament ends and bound plasmids together
		StepProfiler.add(StepProfiler.F5_6_FILSEG_NODE, _spT);

		addMembraneConfinement();		// keep filaments inside a closed membrane (porous node lattice)
		addCortexAlignTorque();			// lay Arp2/3-held mothers tangent to the cortex (nurse-log geometry)

		//setCompression();				// register compressive force in filament, if any

	}
	
	public void biochemStep () {
		biochemCheckCt++;
		
		// Viscous-blob stochastic update — removed 2026-05-17 (Round 7); see JOURNAL.md.
		// if (Env.useViscousBlob.isActive() && length > Env.vBlobMinMons.getIntValue()*Env.actinMonoRadius) { this.viscousBlobSim(length, Env.biochemDeltaT.getValue()); }
		
		// Part 2 (turnover residency): when GPU global biochem cadence is active, all segments
		// fire on the same step (GPUMoveThing.biochemFiresThisStep, set once per step in doLoop)
		// instead of their scattered per-instance counter — so the relative-write incCoord/split
		// concentrate onto 1-in-biochemCheckInt steps where the host pose is pulled fresh. Rate
		// preserved (still one pass per biochemCheckInt steps), only the phase is synchronized.
		boolean biochemFires = GPUMoveThing.biochemGlobalCadence
				? GPUMoveThing.biochemFiresThisStep
				: (biochemCheckCt >= biochemCheckInt);
		if (!Env.noMonomersSimd.isActive() && biochemFires) {
			hydrolizeInFilaments();		// monomer-by-monomer hydrolysis and dissociate
			checkCofilinDissolve();		// if ratio of cofilin-bound monomers exceeds spec, dissolve
			
			if (!filAtEnd1) { end1BiochemSim (); }			// catastrophy, polymerization, and depolymerization simulations for end1Pt
			if (!filAtEnd2) { end2BiochemSim (); }			// and for end2Pt, both use lengthChanged flag
			checkDebranch();								// P2: stochastic Arp2/3 debranching (releases aged daughters → array turnover)

			biochemCheckCt = 0;
		}
		
		if (lengthChanged) {
			calculateProperties(); 	// calculate new drag coefficients, etc if length has changed
			pushCoordToSoa();		// poly/depoly mutated Pt3D coordAsPt3D() via coordAsPt3D().inc; flush before initialize() reads SoA
			initialize();			// calculate transformation matrices, etc given the new coordinates
			// Step 2 (2026-06-07) — pose was mutated in-place on the same
			// Thing (incCoord shifts ±halfmono/2; length changed). Slot-change
			// detection in buildDeltaSet() will NOT catch this (same Thing,
			// same slot), so explicit markPoseDirty is required. The scatter
			// kernel will land the new coord+length in the device-resident
			// pose ahead of the next move integration. markTopologyDirty also
			// kept for the Phase 4.5 EVERY_EXECUTION path back-compat (it's
			// just a flag set; cheap).
			if (Env.useGPU) {
				GPUMoveThing.markTopologyDirty();
				GPUMoveThing.lengthDirtyCount++;   // A1: length-only — not a structural change
				GPUMoveThing.markPoseDirty(this);
			}
		}


		if (monomerCt >= 2*Env.stdSegLength.getIntValue()) {
			splitSegment(this);		// setFirstHalf pushes coordAsPt3D() internally; the new FilSegment ctor handles its own pose
			calculateProperties();	// again if split
			initialize();
			// Step 2 (2026-06-07) — splitSegment mutated this segment's coord
			// (setFirstHalf calls setCoord) AND created nextFil. Mark this
			// dirty so the scatter packs the parent's new pose; nextFil is
			// auto-detected by buildDeltaSet's slot-change scan (new Thing
			// occupant at whatever slot classifyThings assigns to it).
			if (Env.useGPU) {
				GPUMoveThing.markTopologyDirty();
				GPUMoveThing.structuralDirtyCount++;   // A1: split creates nextFil — real topology change
				GPUMoveThing.markPoseDirty(this);
			}
		}

		//*** joining broken with branched networks right now, but who really needs it anyway
		/*if (monomerCt <= Env.stdSegLength.getIntValue()/2) {
			joinSegments();
			updateCylGraphicsFlag = true;
		}*/
		
		
	}
	
	public void moveThing () {
		if (isLpSeg && Env.lpActive.getValue() == 0) return;
		// Branch-constraint sub-cycling: mothers (arpChildCt>0) and daughters (motherFil!=null)
		// are integrated inside Arp23.subcycleAll() at dt/N, so skip them in the global move
		// wave to avoid double-integration. Non-branch filaments fall through normally.
		if (Env.arpSubcycleN.getIntValue() > 1 && !Arp23.subcyclingNow && (motherFil != null || arpChildCt > 0)) return;
		// Given the forces/torques at this time point... move with explicit Euler approximation to ODE solution

		double dt = Env.deltaT.getValue();

		// Work in coordinates aligned with the rod... transform forces and torques into body-fixed axis....
		int sBase = myThingNumber * 3;
		bForceSum.XToxFromFloats(this, Thing.soaForceSum, sBase);
		bTorqueSum.XToxFromFloats(this, Thing.soaTorqueSum, sBase);

		// add brownian force and torque... these are zero except at every chosen time-step
		if (!Env.brownianFilMotionOff && !brownianOff) {
			double transScale,rotScale;
				// An Arp2/3-held filament (de-novo nucleated at a membrane node, pointed end tethered) is
				// structurally anchored, not free -- dial its thermal forcing way down so the tiny nascent
				// seed doesn't get a full free-filament kick that destabilizes its stiff pointed-end tether.
				double heldBrown = ((childOfArp23 || forminMother) && nodeAtEnd1) ? Env.arpHeldBrownianFactor.getValue() : 1.0;
				// Crowded cortical shell: damp Brownian for any filament pressed against the cortex (NOT just
				// the held mothers) — otherwise a large filament freed by debranching gets a full free-filament
				// kick and smashes into the membrane nodes. Spatial gate on the center's radius near the inner
				// steric face. Take the stronger (smaller) of the held and cortex factors.
				if (StickyNode.sphericalGeometry && Env.cortexBrownianZone.getValue() > 0) {
					double inner = Env.membraneCellRadius.getValue() - (Env.membraneNodeRadius.getValue() + Env.filTipRadiusForCollisions.getValue());
					double rC = Pt3D.ptDist(coordAsPt3D(), StickyNode.centerOfSphere);
					if (rC > inner - Env.cortexBrownianZone.getValue()) { heldBrown = Math.min(heldBrown, Env.cortexBrownianFactor.getValue()); }
				}
			if (motherFil == null) {
				// trans
				transScale = Env.BTransCoeff.getValue()*heldBrown;
				if (linkedToCt > 0) { transScale = transScale/(1 + Env.xLinkTransAttn.getValue()*linkedToCt); }
				if (actAOn) {
					transScale *= bTransGam.y/Thing.lmBug.bTransGam.y; //Env.actATetherTransAttn.getValue();
					randForces.XTox(this,Thing.lmBug.randForcesInX);	// use bug random forces
				}
				bForceSum.inc(transScale,randForces);

				// rot
				rotScale = Env.BRotCoeff.getValue()*heldBrown;
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
		// the body-fixed angular velocities can just be transformed into fixed-frame velocities, and the coordAsPt3D() updated
		veloc.xToX(this, bVeloc);
		incCoord(dt,veloc);  // just position = velocity*time

		Pt3D scratch = new Pt3D();
		double uVecTransInZ = -bAngVeloc.y * dt;
		double uVecTransInY = bAngVeloc.z * dt;
		scratch.setVals(1, uVecTransInY, uVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setUVec(scratch);

		double yVecTransInX = -uVecTransInY;
		double yVecTransInZ = bAngVeloc.x * dt;
		scratch.setVals(yVecTransInX, 1, yVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setYVec(scratch);

		initialize();
	}

	public void calcRandomForces (WorkerScratch ws) {  // override Thing.calRandomForces to account for sync'd brownian motion and to avoid wasting calculation of independent values
		if (isLpSeg && Env.lpActive.getValue() == 0) return;
		if (motherFil == null) {
			super.calcRandomForces(ws);
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
			if (end1NbrSide == 1) { 	// normal alignment: my end1 → neighbour's end2
				joinSegs21(end1Fil,this);

			} else {
				joinSegs11(end1Fil,this);
			}
		} else {	// must be filAtEnd2
			joinTo = end2Fil;
			if (end2NbrSide == 0) { 	// normal alignment: my end2 → neighbour's end1
				joinSegs12(end2Fil,this);
			} else {
				joinSegs22(end2Fil,this);
			}
		}
		if (joinTo != null) {
			joinTo.pushCoordToSoa();   // joinSegs* mutated stayFil.coordAsPt3D() via coordAsPt3D().inc; flush before initialize() reads SoA
			joinTo.initialize();
			joinTo.calculateProperties();
		}
	}
	
	public static void joinSegs11 (FilSegment stayFil, FilSegment byeFil) {
		// case11:  stayFil.end1Pt linked to byeFil end1Pt
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
		stayFil.incCoord(byeFil.monomerCt*halfmono/2,stayFil.uVecRAsPt3D());
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
		// case12:  usual orientation in reverse, stayFil.end2Pt linked to byeFil end1Pt
		//talkln ("case12: joining filseg with " + byeFil.monomerCt + " to filseg with " + stayFil.monomerCt);
		// will take what's left of byeFil and add it to stayFil
		// transfer monomers
		stayFil.minusMon.backMon = byeFil.plusMon;	// link plusmon to byeFil.minusmon
		byeFil.plusMon.frontMon = stayFil.minusMon;
		stayFil.minusMon = byeFil.minusMon;	// declare that byeFils minusMon is new minusMon for stayfil
		stayFil.minusMon.backMon = Monomer.minusGhost;
		// increment monomerCt of stayFil, and shift CM
		stayFil.monomerCt += byeFil.monomerCt;
		stayFil.incCoord(byeFil.monomerCt*halfmono/2,stayFil.uVecRAsPt3D());
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
		// case21:  usual orientation, stayFil.end2Pt linked to byeFil end1Pt
		//talkln ("case21: joining filseg with " + byeFil.monomerCt + " to filseg with " + stayFil.monomerCt);
		// will take what's left of byeFil and add it to stayFil
		// transfer monomers
		stayFil.plusMon.frontMon = byeFil.minusMon;	// link plusmon to byeFil.minusmon
		byeFil.minusMon.backMon = stayFil.plusMon;
		stayFil.plusMon = byeFil.plusMon;	// declare that byeFils plus mon is new plusmon for stayfil
		stayFil.plusMon.frontMon = Monomer.plusGhost;
		// increment monomerCt of stayFil, and shift CM
		stayFil.monomerCt += byeFil.monomerCt;
		stayFil.incCoord(byeFil.monomerCt*halfmono/2,stayFil.uVecAsPt3D());
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
		// case22:  stayFil.end2Pt linked to byeFil end2Pt
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
		stayFil.incCoord(byeFil.monomerCt*halfmono/2,stayFil.uVecAsPt3D());
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
		// setFirstHalf() shortened splitFilSeg (new coord/length) but DEFERS the derived-end
		// recompute, so splitFilSeg.end2Pt is still the STALE pre-split plus-end (~stdSegLength
		// monomers too far out). Computing the new segment's position from it placed the new
		// plus-end segment off-chain for one frame (chain spring then snapped it back — a visible
		// pop at synchronized splits). Use the first half's FRESH end2 = coord + 0.5*length*uVec.
		double fhHalf = 0.5*splitFilSeg.length;
		Pt3D fhEnd2 = new Pt3D(splitFilSeg.getCoordX()+fhHalf*splitFilSeg.getUVecX(),
		                       splitFilSeg.getCoordY()+fhHalf*splitFilSeg.getUVecY(),
		                       splitFilSeg.getCoordZ()+fhHalf*splitFilSeg.getUVecZ());
		Pt3D nextFilCoord = Pt3D.Add(fhEnd2,0.5*nextFilLength-Env.actinMonoRadius,splitFilSeg.uVecAsPt3D());
		FilSegment nextFil = new FilSegment (nextFilCoord,splitFilSeg.uVecAsPt3D(),halfSegCt,splitFilSeg);
		splitFilSeg.setEnd2Links(nextFil, true);
		if (!Env.noMonomersSimd.isActive()) { splitFilSeg.transferMons (splitFilSeg.monomerCt,nextFil); }
		splitFilSeg.transferArpChildren(nextFil);
	}
	
	public void setFirstHalf (int halfSegCt) {
		monomerCt = monomerCt - halfSegCt;
		length = (monomerCt+1)*Env.actinMonoRadius;
		// new coord = end1 + 0.5*length * uVec; new end2 = end1 + length * uVec.
		// end1 stays where it is (anchor); coord/end2 shift to maintain new length.
		setCoord(getEnd1X() + 0.5*length*getUVecX(),
		         getEnd1Y() + 0.5*length*getUVecY(),
		         getEnd1Z() + 0.5*length*getUVecZ());
		// end2 is derived; the caller's calculateProperties + initialize() will
		// run recomputeDerivedSoA to refresh soaEnd2 from the new coord+length.
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
		if (pt1.equals(seg1.end2Pt) & pt2.equals(seg2.end1Pt)) {
			seg1.setEnd2Links(seg2,true);
			seg2.setEnd1Links(seg1,true);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
		
		if (pt1.equals(seg1.end1Pt) & pt2.equals(seg2.end2Pt)) {
			seg1.setEnd1Links(seg2,true);
			seg2.setEnd2Links(seg1,true);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
		
		if (pt1.equals(seg1.end2Pt) & pt2.equals(seg2.end2Pt)) {
			seg1.setEnd2Links(seg2,false);
			seg2.setEnd2Links(seg1,false);
			if (seg1.filID < seg2.filID) { seg2.filID = seg1.filID; } else { seg1.filID = seg2.filID; }
			return;
		}
		
		if (pt1.equals(seg1.end1Pt) & pt2.equals(seg2.end1Pt)) {
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
			if (childOfArp23 && motherFil != null) { return; }  // no end1Pt biochem if arp2/3 is there and bound to mother filament
			minusEndDelta = 0;
			
			if (true) {
			//if (!stericHindranceEnd1()) { talkln ("no stericHindrance end1Pt"); }
			//if ((!inCompression("at end1Pt")) && (!stericHindranceEnd1()) && (capConditionOKEnd1())) {
				// normal actin pool polymerization sim
				double rate = getPolyRateEnd1();
				boolean monomerAdded = addMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end1Pt norm poly");
					incCoord(-halfmono/2,uVecAsPt3D()); 
					helixAng += (Math.PI - Env.helixAngInc);
					Monomer.polymerize(minusMon,this,Monomer.MINUSEND, true);
					minusEndDelta++;
				}
				// non-hydrolyzable actin pool polymerization sim
				rate = getNonHydroPolyRateEnd1();
				monomerAdded = addNonHydroMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end1Pt non-hydro poly");
					incCoord(-halfmono/2,uVecAsPt3D()); 
					helixAng += (Math.PI - Env.helixAngInc);
					Monomer.polymerize(minusMon,this,Monomer.MINUSEND, false);
					minusEndDelta++;
				}
			}
			
			// depolymerization...
			if (monomerCt >= Env.actinSeed.getIntValue()) {
				boolean monomerRemoved = removeMonomerSim(getDepolyRateEnd1(),minusMon);
				if (monomerRemoved) { 
					incCoord(halfmono/2,uVecAsPt3D()); 
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
			
			if (capConditionOKEnd2()) {
				// Soft steric attenuation: when the barbed end is sterically blocked, scale the poly
				// rate by stericPolyFactor (0 = hard stop = original behavior; 0<f<1 = Brownian-ratchet-
				// like reduced growth; 1 = no steric effect) instead of skipping polymerization entirely.
				double sterFac = Env.ratchetOn.isActive()
						? ratchetPolyFactor()
						: (stericHindranceEnd2() ? Env.stericPolyFactor.getValue() : 1.0);
				// normal actin polymerization
				double rate = getPolyRateEnd2()*sterFac;
				boolean monomerAdded = addMonomerSim(rate);
				if (Env.ratchetOn.isActive()) { RatchetDiag.recordTip(end2TipC - Env.filTipRadiusForCollisions.getValue(), halfmono, sterFac, monomerAdded); }
				if (monomerAdded) {
					//talkln ("end2Pt norm poly");
					incCoord(halfmono/2,uVecAsPt3D());
					Monomer.polymerize(plusMon,this,Monomer.PLUSEND, true);
					plusEndDelta++;
					Env.registerPlusMon(end2TipC);
			    }
				// non-hydrolyzable actin polymerization
				rate = getNonHydroPolyRateEnd2()*sterFac;
				monomerAdded = addNonHydroMonomerSim(rate);
				if (monomerAdded) { 
					//talkln ("end2Pt non-hydro poly");
					incCoord(halfmono/2,uVecAsPt3D()); 
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
						incCoord(-halfmono/2,uVecAsPt3D()); 
						plusMon.depolymerize(this,Monomer.PLUSEND);
						plusEndDelta--;
					}
				}
			} else {
				cleanup(this,true,true);
			}
		}
	}
	
	public void registerATipClearance (double tipC, ProteinNode arpNode) {
		if (tipC < end2TipC) {
			end2TipC = tipC;
			if (end2TipC < Env.branchZone.getValue() && arpNode != null && arpNode.iAmHotRho) {
				end2NearArpFactor = true;
				end2NearArpNode = (arpNode instanceof StickyNode) ? (StickyNode)arpNode : null;  // for the per-node Arp2/3 field
			} else {
				end2NearArpFactor = false;
				end2NearArpNode = null;
			}
		}
		
		/*if (end2TipC < 0 && end2Capped) { // remove cap if collision of tip, with some probability... why not?
			if (currentScratch().rng.nextDouble() < 1e-4) { end2Capped = false; }
		}  	*/
	}
			
	public void checkCapping() {
		if (filAtEnd2) { return; } 	// no capping if interior (filament continues past end2)
		if (nodeAtEnd2) { return; }  // no capping if formin at end2Pt
		// Membrane-localized capping (lamellipodium rule): barbed ends at the cortex are uncapped and
		// free to grow/push; any barbed end away from the membrane is aggressively (deterministically)
		// capped, so growth stays a thin layer tracking the membrane. Overrides stochastic capping.
		double memCapDist = Env.membraneCapDist.getValue();
		if (memCapDist > 0) {
			// On a CLOSED membrane the "near a node" test can't tell a tip approaching the cortex from
			// inside (good — keep growing) from one punching OUT through the porous gaps between nodes
			// (bad — the daughter spikes out of the cell). Add a side test: a barbed tip at/beyond the
			// cortex radius is on the wrong side, so cap it. This stops outward-pointing branch daughters
			// at the membrane instead of letting them thread through.
			if (StickyNode.sphericalGeometry) {
				double tipR = Pt3D.ptDist(end2Pt, StickyNode.centerOfSphere);
				if (tipR >= Env.membraneCellRadius.getValue()) { end2Capped = true; return; }
			}
			end2Capped = (end2TipC >= memCapDist);   // capped iff away from membrane; uncapped on contact/proximity
			return;
		}
		if (end2Capped) { return; } // already capped
		if (end2TipC < 2*Env.actinMonoDiam && end2NearArpFactor) { return; }  // steric conditions for end capping (replace with capping protein dimension!)
		if (currentScratch().rng.nextDouble() < Env.capRate.getValue()*Env.capConc.getValue()*Env.biochemDeltaT.getValue()) {
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
		// Eligible if near a hot Arp activator (original mesh trigger) OR — when branchMembraneDist>0 —
		// anywhere within that distance below the membrane plane (z=0). The latter lets the dendritic
		// network self-amplify (daughters, not just membrane-proximal mothers, keep branching).
		double memDist = Env.branchMembraneDist.getValue();
		boolean nearMembrane = memDist > 0 && getEnd2Z() > -memDist;
		if (!end2NearArpFactor && !nearMembrane) { return; }
		// Branch rate reads the LOCAL Arp2/3 pool (nearest hot node) when the per-node field is on,
		// else the global arpConc. Local depletion at a hot zone therefore slows branching THERE.
		double localArp = (Env.arpLocalField.isActive() && end2NearArpNode != null)
				? end2NearArpNode.arpLocal : Env.arpConc.getValue();
		if (currentScratch().rng.nextDouble() < Env.branchRateNearArpFactors.getValue()*localArp*Env.biochemDeltaT.getValue()) {
			// branch in the upper (barbed) region; cap the offset at 0.8*length so bLoc never goes
			// negative (a bLoc<0 branch is marked inactive immediately).
			double bLoc = length - Math.random()*Math.min(Env.branchZone.getValue(), 0.8*length);
			makeArpBranch(bLoc);
		}
	}

	// P2: stochastic Arp2/3 debranching. A mother rolls each of its active branches for dissociation,
	// at a rate scaled by the DAUGHTER's aged (ADP) fraction — GMF-like: fresh ATP/ADP-Pi branches are
	// stable, branches whose daughter has hydrolyzed to ADP release. On debranch the Arp23 is marked
	// inactive; the single-threaded Arp23.setInactiveArp23s() pass then frees the daughter
	// (daughterFil.motherFil -> null, so its drag floor lifts) and recycles the Arp23. The freed
	// daughter, no longer fed at a hot node and subject to normal depoly, shrinks away → array turnover.
	public void checkDebranch () {
		double rate = Env.arpDebranchRate.getValue();
		if (rate <= 0 || arpChildCt == 0) return;
		double dt = Env.biochemDeltaT.getValue();
		for (int i=0; i<arpChildCt; i++) {
			if (!arpActive[i]) continue;
			FilSegment d = arpChildren[i];
			Arp23 a = arp23s[i];
			if (d == null || a == null || !a.active) continue;
			double aged = d.junctionADPFraction();   // 0 = junction fresh (ATP/ADP-Pi), 1 = junction fully ADP — ages even while the barbed end grows
			if (currentScratch().rng.nextDouble() < rate*aged*dt) {
				a.active = false;       // setInactiveArp23s() (single-threaded) releases the daughter + recycles the Arp23
				arpActive[i] = false;   // stop the mother counting it immediately (and free the branch location)
			}
		}
	}

	public FilSegment makeArpBranch(double bLoc) {
		double theta = getHelixAngleAtLoc(bLoc); // assume helixAng is angle with mother filament's body-fixed y-axis
		double yPart = Math.cos(theta)*Env.sinArp23Alpha;
		double zPart = Math.sin(theta)*Env.sinArp23Alpha;
		Pt3D nucUVec = new Pt3D(Env.cosArp23Alpha,yPart,zPart);
		nucUVec.xToX(this);
		Pt3D nucLoc = Pt3D.Add(end1Pt,bLoc,uVecAsPt3D());   // branch point on the mother = daughter's end1 anchor
		// Place the daughter so its end1 (pointed end) sits AT the branch point, not its CENTER.
		// end1 = coord - 0.5*length*uVec, so coord = branchPoint + 0.5*length*uVec. Previously the
		// daughter's center was put at the branch point, leaving end1 ~0.5*length off, which the
		// Arp2/3 translational constraint then yanked in — a pop at every branch creation and at
		// startup (subtle for seed-length daughters, but the same posing bug as the split).
		double dHalf = 0.5*(Env.actinSeed.getIntValue()+1)*Env.actinMonoRadius;
		Pt3D dCenter = Pt3D.Add(nucLoc, dHalf, nucUVec);
		FilSegment dFil = FilSegment.makeArp23NucFilament(dCenter, nucUVec);
		Arp23 newArp = Arp23.newArpBranch(this, bLoc, dFil);

		// Localized Arp2/3 depletion: consume from the nearest hot node's local pool (per-node field)
		// or the global pool; recorded on the Arp23 so it is returned exactly once on dissociation.
		double cons = Env.arpConsumePerBranch.getValue();
		if (cons > 0) {
			if (Env.arpLocalField.isActive()) {
				// Activated field: consume the nearest hot node's local pool. Branches with no resolved
				// node (e.g. the IC seed mothers at startup) do NOT touch arpConc — it is the production
				// TARGET in this model, not a consumable pool.
				if (end2NearArpNode != null) {
					end2NearArpNode.arpLocal -= cons;
					newArp.arpConsumedNode = end2NearArpNode;
					newArp.arpConsumedAmt = cons;
				}
			} else {
				Env.arpConc.addToValue(-cons);   // global conserved pool
				newArp.arpConsumedAmt = cons;
			}
		}

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
	
	// viscousBlobSim removed 2026-05-17 (Round 7): Listeria-specific hack; see JOURNAL.md.
	// public void viscousBlobSim (double effectiveLength, double dT) {
	//     if (numViscBlobs < Env.maxVBlobs) {
	//         double blobAddProb = effectiveLength*Env.vBlobOnRate*dT;
	//         if (currentScratch().rng.nextDouble() < blobAddProb) { numViscBlobs++; lengthChanged = true; }
	//     }
	//     if (numViscBlobs == 0) return;
	//     double blobRemoveProb = Env.vBlobOffRate*dT;
	//     if (currentScratch().rng.nextDouble() < blobRemoveProb) { numViscBlobs--; lengthChanged = true; }
	// }
	
	public double getHelixAngleAtLoc(double loc) {
		return (helixAng + (loc/Env.actinMonoRadius)*Env.helixAngInc);// %(2*Math.PI);
	}
	
	public void resetCounters() {
		super.resetCounters();	// call the generic Thing method
		end1AxialF = 0;			// reset axial force at end1Pt to zero
		end2AxialF = 0; 		// reset axial force at end2Pt to zero
		lengthChanged = false;	// 
		end1LinkCkd = false;
		end2LinkCkd = false;
		end1TorqCkd = false;
		end2TorqCkd = false;
		end2TipC = 1e6; 		// big number
		end1TipC = 1e6;
		end2NearArpNode = null; // re-resolved each step by registerATipClearance when a tip nears a hot node
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
			// Listeria from-outside path stays on CPU (out of scope for
			// Phase 2 F1; survey confirmed lmBug is a separate static ref
			// from theBox with no shared state with the from-inside path).
			checkBugCollisionFromOutside();
		} else {
			// Phase 2 F1 — when the device boundaryBoxKernel handles this
			// segment's from-inside box wall, skip the CPU pair here.
			// Per-force gate (Lesson 1): we gate INSIDE checkBugOrBox so
			// the Listeria branch stays live on CPU even when the box is
			// ported. The side effects of bugForcesFromInside on
			// end1AxialF/end2AxialF/end1TipC/end2TipC are dead in the
			// box-from-inside gliding/bench workloads (see
			// boundaryBoxKernel header comment); device kernel does not
			// replicate them.
			if (!gpuBoundaryHandled) {
				checkBugCollisionFromInside();
			}
		}
	}
	
	// Phase 4.5 diag (2026-06-05): count how often checkBugCollisionFromInside
	// actually fires on the GPU path. Hypothesis from selective poison: the
	// `ranges` family HIT may come from end1Pt/end2Pt reads here that the gate
	// claims are inert. If this counter > 0 on a -gpu gliding run, the gate
	// is misfiring. Print at end of run (BoxOfActin.printStats area).
	public static long DIAG_BUG_INSIDE_FIRE_CT = 0;
	// Phase 4.5 diag: count addLinkForces and addTorsionSpringForces fires on
	// the GPU path. Hypothesis: gpuChainHandled may flip false for some
	// subset of segments at some times, leaking the CPU addLinkForces (which
	// reads end1Pt/end2Pt and ptAtEnd1/2 — all rangesEndpt family).
	public static long DIAG_ADDLINK_FIRE_CT = 0;
	public static long DIAG_ADDTORSION_FIRE_CT = 0;

	public void checkBugCollisionFromInside() {
		if (Env.useGPU) DIAG_BUG_INSIDE_FIRE_CT++;
		final CollisionEvent cE = currentScratch().cE;
		theBox.amICollidingOuter(cE,end1Pt,radius);
		if (cE.delta != 0) { bugForcesFromInside(cE,end1Pt); }


		theBox.amICollidingOuter(cE,end2Pt,radius);
		if (cE.delta != 0) { bugForcesFromInside(cE,end2Pt); }
	}

	public void checkBugCollisionFromOutside() {
		final CollisionEvent cE = currentScratch().cE;
		lmBug.amICollidingFromOutside(cE,end1Pt,radius);
		if (cE.isColliding()) {
			end1TipC = 0;
			bugForcesFromOutside(cE,end1Pt);
		} else {
			end1TipC = cE.delta;
		}


		lmBug.amICollidingFromOutside(cE,end2Pt,radius);
		Env.registerCloseTip(cE.delta);  // only registering barbed-ends close to the bug surface
		if (cE.isColliding()) {
			end2TipC = 0; // set tip clearance
			bugForcesFromOutside(cE,end2Pt);
			//talkln ("collision");
			//ActA.checkFilamentBinding(this,cE.tmpPt1);
			if (currentScratch().rng.nextDouble() < Env.checkActABindingProb.getValue()) {	// only check for ActA binding rarely
				ActA.checkBindingToActA(this,cE.tmpPt1);
			}
			if (currentScratch().rng.nextDouble() < Env.contactUncapsProb.getValue()) {		// uncap if contact with surface, with some probability
				end2Capped = false;
			}
		} else {
			end2TipC = cE.delta;
		}
	}
	
	public void validateEnd2Link() {
		// check state of link between my end2 and end2Fil — dissolve if problem found
		if (end2Fil == null) {
			// no handle to the linked segment, nullify my end2 link
			filAtEnd2 = false;
			return;
		}
		// if we've gotten here then check pointers of other segment
		if (end2NbrSide == 0) {		// my end2 attaches to neighbour's end1
			boolean breakLink = false;
			if (!breakLink & !end2Fil.filAtEnd1) { breakLink = true; }
			if (!breakLink & end2Fil.end1Fil == null) { breakLink = true; }
			if (!breakLink & end2Fil.end1Fil != this) { breakLink = true; }
			if (breakLink) {
				end2Fil.removeEnd1Links();
				removeEnd2Links();
			}
		} else {					// my end2 attaches to neighbour's end2
			boolean breakLink = false;
			if (!breakLink & !end2Fil.filAtEnd2) { breakLink = true; }
			if (!breakLink & end2Fil.end2Fil == null) { breakLink = true; }
			if (!breakLink & end2Fil.end2Fil != this) { breakLink = true; }
			if (breakLink) {
				end2Fil.removeEnd2Links();
				removeEnd2Links();
			}
		}
	}

	public void validateEnd1Link() {
		// check state of link between my end1 and end1Fil — dissolve if problem found
		if (end1Fil == null) {
			// no handle to the linked segment, nullify my end1 link
			filAtEnd1 = false;
			return;
		}
		// if we've gotten here then check pointers of other segment
		if (end1NbrSide == 0) {		// my end1 attaches to neighbour's end1
			boolean breakLink = false;
			if (!breakLink & !end1Fil.filAtEnd1) { breakLink = true; }
			if (!breakLink & end1Fil.end1Fil == null) { breakLink = true; }
			if (!breakLink & end1Fil.end1Fil != this) { breakLink = true; }
			if (breakLink) {
				end1Fil.removeEnd1Links();
				removeEnd1Links();
			}
		} else {					// my end1 attaches to neighbour's end2
			boolean breakLink = false;
			if (!breakLink & !end1Fil.filAtEnd2) { breakLink = true; }
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
			if (end2NbrSide == 0) {		// my end2 → neighbour's end1
				end2Fil.removeEnd1Links();
			} else {
				end2Fil.removeEnd2Links();
			}
		}
		removeEnd2Links();
	}

	public void breakAtEnd1() {
		if (filAtEnd1) {
			if (end1NbrSide == 1) {		// my end1 → neighbour's end2
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
			cosBeta = Pt3D.Dot(uVecAsPt3D(), linkUVec);
		} else {
			cosBeta = Pt3D.Dot(uVecRAsPt3D(), linkUVec);
		}
		if (cosBeta > 1.0) cosBeta = 1.0;
		if (cosBeta < -1.0) cosBeta = -1.0;
		double beta = Pt3D.fastAcos(cosBeta);
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
		// this method doesn't care which end of end2Fil my end2 is linked to (the end2NbrSide branches handle either)
		if (Env.useGPU) DIAG_ADDLINK_FIRE_CT++;

		double dt = Env.deltaT.getValue();
		double fracMove = Env.fracMove.getValue();
		double fracR = Env.fracR.getValue();
		boolean maxSegDistActive = Env.maxSegDist.isActive();
		double maxSegDist = Env.maxSegDist.getValue();

		// first double-check validity of links
		// non-existence of attached filament
		validateEnd2Link();
		validateEnd1Link();

		if (filAtEnd2 & !end2LinkCkd) {
			Pt3D nbrEnd2 = (end2NbrSide == 0) ? end2Fil.end1Pt : end2Fil.end2Pt;
			linkPt.add(end2Pt,Env.actinMonoRadius,uVecRAsPt3D());		// link point is half a monomer back from end2 tip
			double strainDist = Pt3D.ptDist(linkPt,nbrEnd2);

			// check distance if fils can break apart
			end2SegDist.registerValue(strainDist);
			if (maxSegDistActive & end2SegDist.averageVal() > maxSegDist) {
				breakAtEnd2();
				talkln ("broke because dist too large between linked segments");
				return;
			}

			linkUVec.unitVec(strainDist,nbrEnd2,linkPt);
			linkUVecR.scale(-1,linkUVec);

			double moveCoeff1 = moveCoeff(2,linkUVec);
			double moveCoeff2;
			if (end2NbrSide == 0) {
				moveCoeff2 = end2Fil.moveCoeff(1, linkUVecR);
			} else {
				moveCoeff2 = end2Fil.moveCoeff(2, linkUVecR);
			}
			double forceMag = (fracMove*1.0e-6*strainDist)/(dt*(moveCoeff1 + moveCoeff2));

			// filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink2Track.registerValue(F);
			//F.copy(filLink2Track.averagePtVal());

			incForceSum(F);
			R.scale(0.5e-6*length*fracR,uVecAsPt3D());
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end2LinkCkd = true;

			Fopp.scale(-1,F);
			end2Fil.incForceSum(Fopp);
			if (end2NbrSide == 0) {
				R.scale(0.5e-6*end2Fil.length*fracR,end2Fil.uVecRAsPt3D());
				end2Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end2Fil.length*fracR,end2Fil.uVecAsPt3D());
				end2Fil.end2LinkCkd = true;
			}
			RcrossF.cross(R,Fopp);
			end2Fil.incTorqueSum(RcrossF);

			// add these link forces to the axial loads on each segment
			incEnd2AxialForce(Pt3D.Dot(uVecAsPt3D(),F)); // axial force contribution
			if (end2NbrSide == 0) {
				end2Fil.incEnd1AxialForce(Pt3D.Dot(end2Fil.uVecRAsPt3D(),Fopp)); // axial force contribution
			} else {
				end2Fil.incEnd2AxialForce(Pt3D.Dot(end2Fil.uVecAsPt3D(),Fopp)); // axial force contribution
			}

			// propagate a change in filID.... lower filID always used
			if (filID != end2Fil.filID) {
				if (filID < end2Fil.filID) { end2Fil.filID = filID; } else { filID = end2Fil.filID; }
			}
		}

		// take care of link at end1
		if (filAtEnd1 & !end1LinkCkd) {
			Pt3D nbrEnd1 = (end1NbrSide == 0) ? end1Fil.end1Pt : end1Fil.end2Pt;
			linkPt.add(end1Pt,Env.actinMonoRadius,uVecAsPt3D());		// link point is half a monomer back from end1 tip
			double strainDist = Pt3D.ptDist(linkPt,nbrEnd1);

			// check distance if fils can break apart
			end1SegDist.registerValue(strainDist);
			if (maxSegDistActive & end1SegDist.averageVal() > maxSegDist) {
				breakAtEnd1();
				talkln ("broke because dist too large between linked segments");
				return;
			}

			linkUVec.unitVec(strainDist,nbrEnd1,linkPt);
			linkUVecR.scale(-1,linkUVec);

			double moveCoeff1 = moveCoeff(1,linkUVec);
			double moveCoeff2;
			if (end1NbrSide == 0) {
				moveCoeff2 = end1Fil.moveCoeff(1, linkUVecR);
			} else {
				moveCoeff2 = end1Fil.moveCoeff(2, linkUVecR);
			}
			double forceMag = (fracMove*1.0e-6*strainDist)/(dt*(moveCoeff1 + moveCoeff2));

			//filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink1Track.registerValue(F);
			//F.copy(filLink1Track.averagePtVal());

			incForceSum(F);
			R.scale(0.5e-6*length*fracR,uVecRAsPt3D());
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end1LinkCkd = true;

			Fopp.scale(-1,F);
			end1Fil.incForceSum(Fopp);
			if (end1NbrSide == 0) {
				R.scale(0.5e-6*end1Fil.length*fracR,end1Fil.uVecRAsPt3D());
				end1Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end1Fil.length*fracR,end1Fil.uVecAsPt3D());
				end1Fil.end2LinkCkd = true;
			}
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,Fopp);
			end1Fil.incTorqueSum(RcrossF);

			// add these link forces to the axial loads on each segment
			incEnd1AxialForce(Pt3D.Dot(uVecRAsPt3D(),F)); // axial force contribution
			if (end1NbrSide == 0) {
				end1Fil.incEnd1AxialForce(Pt3D.Dot(end1Fil.uVecRAsPt3D(),Fopp)); // axial force contribution
			} else {
				end1Fil.incEnd2AxialForce(Pt3D.Dot(end1Fil.uVecAsPt3D(),Fopp)); // axial force contribution
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
		// this method doesn't care which end of end2Fil my end2 is linked to (the end2NbrSide branches handle either)
		
		// first double-check validity of links
		// non-existence of attached filament
		validateEnd2Link();
		validateEnd1Link();
		
		if (filAtEnd2 & !end2LinkCkd) {
			Pt3D nbrEnd2 = (end2NbrSide == 0) ? end2Fil.end1Pt : end2Fil.end2Pt;
			linkPt.add(end2Pt,Env.actinMonoRadius,uVecRAsPt3D());		// link point is half a monomer back from end2 tip
			double strainDist = Pt3D.ptDist(linkPt,nbrEnd2);

			// check distance if fils can break apart
			end2SegDist.registerValue(strainDist);
			if (Env.maxSegDist.isActive() & end2SegDist.averageVal() > Env.maxSegDist.getValue()) {
				breakAtEnd2();
				talkln ("broke because dist too large between linked segments");
				return;
			}

			linkUVec.unitVec(strainDist,nbrEnd2,linkPt);
			linkUVecR.scale(-1,linkUVec);
			// define cosines of angles between filament uVecs and line between endpoints
			double cosAngTween1 = Pt3D.CrossMag(uVecAsPt3D(),linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
			double cosAngTween2;
			if (end2NbrSide == 0) {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end2Fil.uVecAsPt3D());
			} else {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end2Fil.uVecRAsPt3D());
			}
			double arm1 = 1e-6*length*cosAngTween1/2;
			double arm2 = 1e-6*end2Fil.length*cosAngTween2/2;
			double moveCoeff1 = 1/bTransGam.x + (arm1*arm1)/bRotGam.y;
			double moveCoeff2 = 1/end2Fil.bTransGam.x + (arm2*arm2)/end2Fil.bRotGam.y;
			double forceMag = (Env.fracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
		
			// filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink2Track.registerValue(F);
			//F.copy(filLink2Track.averagePtVal());
			
			incForceSum(F);
			R.scale(0.5e-6*length,uVecAsPt3D());
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end2LinkCkd = true;
			
			Fopp.scale(-1,F);
			end2Fil.incForceSum(Fopp);
			if (end2NbrSide == 0) {
				R.scale(0.5e-6*end2Fil.length,end2Fil.uVecRAsPt3D());
				end2Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end2Fil.length,end2Fil.uVecAsPt3D());
				end2Fil.end2LinkCkd = true;
			}
			RcrossF.cross(R,Fopp);
			end2Fil.incTorqueSum(RcrossF);
			
			// add these link forces to the axial loads on each segment
			incEnd2AxialForce(Pt3D.Dot(uVecAsPt3D(),F)); // axial force contribution
			if (end2NbrSide == 0) {
				end2Fil.incEnd1AxialForce(Pt3D.Dot(end2Fil.uVecRAsPt3D(),Fopp)); // axial force contribution
			} else {
				end2Fil.incEnd2AxialForce(Pt3D.Dot(end2Fil.uVecAsPt3D(),Fopp)); // axial force contribution
			}
			
			// propagate a change in filID.... lower filID always used
			if (filID != end2Fil.filID) {
				if (filID < end2Fil.filID) { end2Fil.filID = filID; } else { filID = end2Fil.filID; }
			}
		}
		
		// take care of link at end1
		if (filAtEnd1 & !end1LinkCkd) {
			Pt3D nbrEnd1 = (end1NbrSide == 0) ? end1Fil.end1Pt : end1Fil.end2Pt;
			linkPt.add(end1Pt,Env.actinMonoRadius,uVecAsPt3D());		// link point is half a monomer back from end1 tip
			double strainDist = Pt3D.ptDist(linkPt,nbrEnd1);

			// check distance if fils can break apart
			end1SegDist.registerValue(strainDist);
			if (Env.maxSegDist.isActive() & end1SegDist.averageVal() > Env.maxSegDist.getValue()) {
				breakAtEnd1();
				talkln ("broke because dist too large between linked segments");
				return;
			}

			linkUVec.unitVec(strainDist,nbrEnd1,linkPt);
			linkUVecR.scale(-1,linkUVec);
			// define cosines of angles between filament uVecs and line between endpoints
			double cosAngTween1 = Pt3D.CrossMag(uVecRAsPt3D(),linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
			double cosAngTween2;
			if (end1NbrSide == 0) {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end1Fil.uVecAsPt3D());
			} else {
				cosAngTween2 = Pt3D.CrossMag(linkUVecR,end1Fil.uVecRAsPt3D());
			}
			double arm1 = 1e-6*length*cosAngTween1/2;
			double arm2 = 1e-6*end1Fil.length*cosAngTween2/2;
			double moveCoeff1 = 1/bTransGam.x + (arm1*arm1)/bRotGam.y;
			double moveCoeff2 = 1/end1Fil.bTransGam.x + (arm2*arm2)/end1Fil.bRotGam.y;
			double forceMag = (Env.fracMove.getValue()*1.0e-6*strainDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
			
			//filter instantaneous F through averaging
			F.scale(forceMag,linkUVec);
			//filLink1Track.registerValue(F);
			//F.copy(filLink1Track.averagePtVal());
			
			incForceSum(F);
			R.scale(0.5e-6*length,uVecRAsPt3D());
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			end1LinkCkd = true;
			
			Fopp.scale(-1,F);
			end1Fil.incForceSum(Fopp);
			if (end1NbrSide == 0) {
				R.scale(0.5e-6*end1Fil.length,end1Fil.uVecRAsPt3D());
				end1Fil.end1LinkCkd = true;
			} else {
				R.scale(0.5e-6*end1Fil.length,end1Fil.uVecAsPt3D());
				end1Fil.end2LinkCkd = true;
			}
			//R.zero();	// remove if you want torque from links
			RcrossF.cross(R,Fopp);
			end1Fil.incTorqueSum(RcrossF);
			
			// add these link forces to the axial loads on each segment
			incEnd1AxialForce(Pt3D.Dot(uVecRAsPt3D(),F)); // axial force contribution
			if (end1NbrSide == 0) {
				end1Fil.incEnd1AxialForce(Pt3D.Dot(end1Fil.uVecRAsPt3D(),Fopp)); // axial force contribution
			} else {
				end1Fil.incEnd2AxialForce(Pt3D.Dot(end1Fil.uVecAsPt3D(),Fopp)); // axial force contribution
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
		// this method doesn't care which end of end2Fil my end2 is linked to (the end2NbrSide branches handle either)
		if (Env.useGPU) DIAG_ADDTORSION_FIRE_CT++;

		double dt = Env.deltaT.getValue();
		double fracMoveTorq = Env.fracMoveTorq.getValue();
		boolean maxSegAngActive = Env.maxSegAngle.isActive();
		double maxSegAng = Env.maxSegAngle.getValue();
		boolean filTorqSpringActive = Env.filTorqSpring.isActive();
		double filTorqSpring = Env.filTorqSpring.getValue();

		if (filAtEnd2 & !end2TorqCkd) {
			end2TorqCkd = true;
			double dotVecs;
			if (end2NbrSide == 0) {
				end2Fil.end1TorqCkd = true;
				torsionVec.cross(uVecAsPt3D(),end2Fil.uVecAsPt3D());
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVecAsPt3D(),end2Fil.uVecAsPt3D());
			} else {
				end2Fil.end2TorqCkd = true;
				torsionVec.cross(uVecAsPt3D(),end2Fil.uVecRAsPt3D());
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVecAsPt3D(),end2Fil.uVecRAsPt3D());
			}
			
			if (dotVecs > 1.0) dotVecs = 1.0;
			if (dotVecs < -1.0) dotVecs = -1.0;
			double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;

			// check if angle too large
			end2SegAng.registerValue(angTween);
			/*if (maxSegAngActive & end2SegAng.averageVal() > maxSegAng/4) {
				talkln ("Something happening!");
				Env.paintOn = true;
			}*/
			if (maxSegAngActive & end2SegAng.averageVal() > maxSegAng) {
				angTween = 0;
				talkln ("broke because angle too large between linked segments");
				filAtEnd2 = false;
			}

			//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
			double torsionMag;
			if (filTorqSpringActive) {
				torsionMag = fracMoveTorq*filTorqSpring*angTween;
			} else {
				//torsionMag = fracMoveTorq*(Math.PI/180)*end2SegAng.averageVal()/((1/bRotGam.y + 1/end2Fil.bRotGam.y)*dt);
				torsionMag = fracMoveTorq*(Math.PI/180)*angTween/((1/bRotGam.y + 1/end2Fil.bRotGam.y)*dt);
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
			if (end1NbrSide == 0) {
				end1Fil.end1TorqCkd = true;
				torsionVec.cross(uVecRAsPt3D(),end1Fil.uVecAsPt3D());
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVecRAsPt3D(),end1Fil.uVecAsPt3D());
			} else {
				end1Fil.end2TorqCkd = true;
				torsionVec.cross(uVecRAsPt3D(),end1Fil.uVecRAsPt3D());
				torsionVec.unitVec();
				dotVecs = Pt3D.Dot(uVecRAsPt3D(),end1Fil.uVecRAsPt3D());
			}
			
			if (dotVecs > 1.0) dotVecs = 1.0;
			if (dotVecs < -1.0) dotVecs = -1.0;
			double angTween = Pt3D.fastAcos(dotVecs)*180/Math.PI;

			// check if angle too large
			end1SegAng.registerValue(angTween);
			/*if (maxSegAngActive & end1SegAng.averageVal() > maxSegAng/4) {
				talkln ("Something happening!");
				Env.paintOn = true;
			}*/
			if (maxSegAngActive & end1SegAng.averageVal() > maxSegAng) {
				angTween = 0;
				talkln ("broke because angle too large between linked segments");
				filAtEnd1 = false;
			}

			//talkln ("DotVecs is " + dotVecs + " and angTween is " + angTween);
			double torsionMag;
			if (filTorqSpringActive) {
				torsionMag = fracMoveTorq*filTorqSpring*angTween;
			} else {
				//torsionMag = fracMoveTorq*(Math.PI/180)*end1SegAng.averageVal()/((1/bRotGam.y + 1/end1Fil.bRotGam.y)*dt);
				torsionMag = fracMoveTorq*(Math.PI/180)*angTween/((1/bRotGam.y + 1/end1Fil.bRotGam.y)*dt);
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
				ptD = Pt3D.ptDist(fil1.end2Pt, fil2.end1Pt);
				if (ptD < Env.annealDist.getValue()) { 
					//System.out.println("1st Ck passed: ptD = " + ptD);
					cosAngTween = Pt3D.Dot(fil1.uVecAsPt3D(), fil2.uVecAsPt3D());
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case1");
						FilSegment.annealSegments(fil1, fil1.end2Pt, fil2, fil2.end1Pt);
						//System.out.println ("Annealed!");
						return true;
					}
				}
			}
			
			if (!fil2.filAtEnd2 & !fil2.nodeAtEnd2) {
				ptD = Pt3D.ptDist(fil1.end2Pt,fil2.end2Pt);
				if (ptD < Env.annealDist.getValue()) { 
					cosAngTween = Pt3D.Dot(fil1.uVecAsPt3D(), fil2.uVecRAsPt3D());
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case2");
						FilSegment.annealSegments(fil1, fil1.end2Pt, fil2, fil2.end2Pt);
						//System.out.println ("Annealed!");
						return true;
					}
				}
			}
		}
		
		if (!fil1.filAtEnd1 & !fil1.nodeAtEnd1) {
			if (!fil2.filAtEnd1 & !fil2.nodeAtEnd1) {
				ptD = Pt3D.ptDist(fil1.end1Pt, fil2.end1Pt);
				if (ptD < Env.annealDist.getValue()) { 
					cosAngTween = Pt3D.Dot(fil1.uVecRAsPt3D(), fil2.uVecAsPt3D());
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case3");
						FilSegment.annealSegments(fil1, fil1.end1Pt, fil2, fil2.end1Pt);
						//System.out.println ("Annealed!");
						return true;
					}
				}
			}
			
			if (!fil2.filAtEnd2 & !fil2.nodeAtEnd2) {
				ptD = Pt3D.ptDist(fil1.end1Pt, fil2.end2Pt);
				if (ptD < Env.annealDist.getValue()) { 
					cosAngTween = Pt3D.Dot(fil1.uVecRAsPt3D(), fil2.uVecRAsPt3D());
					if (cosAngTween > Env.annealAngleCosine.getValue()) {
						//System.out.println ("case4");
						FilSegment.annealSegments(fil1, fil1.end1Pt, fil2, fil2.end2Pt);
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
	
	// Phase 4.5 diag (2026-06-05): meshAllSegs is the static-not-from-ThreadSet path
	// using end1Pt/end2Pt directly (rangesEndpt family). Nominally not on the GPU
	// gliding live path (Mesh.MeshThreads uses end1AsPt3D instead) but instrument
	// to confirm it stays at 0.
	public static long DIAG_MESHALLSEGS_FIRE_CT = 0;

	public static void meshAllSegs () {
		FilSegment curSeg;
		for (int i=0;i<filSegmentCt;i++) {
			curSeg = theFilSegments[i];
			if (Env.useGPU) DIAG_MESHALLSEGS_FIRE_CT++;
			Mesh.FILSEG_MESH.fillFilSegMesh(curSeg.filArrayPos, curSeg.end1Pt, curSeg.end2Pt);
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
							// Crosslink formation re-cadenced to crosslinkCheckInt (2026-06-12):
							// only attempt links on crosslink-check steps. The mesh walk still
							// runs every collision step (membraneFilMeshCollisions needs FILSEG_MESH),
							// but formation is a biochem-class stochastic event, not every-step.
							if ((iSeg.filID != jSeg.filID) & (Env.xLinks.isActive()) & GPUMoveThing.crosslinkFiresThisStep) { checkToLink(iSeg,jSeg); }  // don't check to link if segs belong to same filament, no xlinks, or off crosslink cadence
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
							// Crosslink formation re-cadenced to crosslinkCheckInt (2026-06-12):
							// only attempt links on crosslink-check steps. The mesh walk still
							// runs every collision step (membraneFilMeshCollisions needs FILSEG_MESH),
							// but formation is a biochem-class stochastic event, not every-step.
							if ((iSeg.filID != jSeg.filID) & (Env.xLinks.isActive()) & GPUMoveThing.crosslinkFiresThisStep) { checkToLink(iSeg,jSeg); }  // don't check to link if segs belong to same filament, no xlinks, or off crosslink cadence
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
		fil.registerATipClearance(Pt3D.ptDist(node.coordAsPt3D(), fil.end2Pt) - node.getRadius(), node);  // register tip clearance (+ nearest hot node) for poly / capping / branching

		// FACE (triangulated-surface) collision: the membrane is an impermeable sheet of triangles
		// (a node + two of its mutually-linked neighbours). Colliding the filament tip with the FACES
		// (not just the point-nodes) keeps the sheet impermeable even when stretched — a tip can no
		// longer slip through the open interior of a triangle. Replaces the point-node collision when on.
		if (Env.membraneFaceCollideOn.getValue() != 0 && node instanceof StickyNode) {
			faceCollideTipVsNodeTriangles((StickyNode)node, fil);
			// fall through: keep the point-node sphere collision too (covers triangle vertices/edges)
		}

		// collision part (point-node sphere collision)
		double attnFactor = 0.3;
		double filTipR = Env.filTipRadiusForCollisions.getValue();
		Pt3D filTipCenter = Pt3D.Add(fil.end2Pt,filTipR,fil.uVecRAsPt3D());
		double pDistSq = Pt3D.ptDistSqrd(node.coordAsPt3D(), filTipCenter);
		double colThresh = node.getRadius()+filTipR;
		if (pDistSq < colThresh*colThresh) {
			double pDist = Math.sqrt(pDistSq);
			double impingedist = colThresh - pDist;
		    Pt3D nodeVec = Pt3D.UnitVec(pDist, node.coordAsPt3D(), filTipCenter);
			Pt3D filVec = Pt3D.Reverse(nodeVec);
			double mag = (attnFactor*1.0e-6*impingedist/Env.collisionDeltaT.getValue())/(1/node.bTransGam.x+1/fil.bTransGam.y);
			node.incForceSum(Pt3D.Scale(mag,nodeVec));
			fil.incForceSum(Pt3D.Scale(mag,filVec),filTipCenter);
			// (b) Capture the POINT-NODE steric push (the dominant one) so the membrane relaxation
			// re-applies it -- without this only the near-zero face push was captured, so a net pressing
			// the cortex transmitted ~no sustained force and the shell stayed frozen. Confirmation test
			// that the vesicle blebs under a real load before the physical stall-force coupling.
			if (Env.membraneYield.getValue() > 0.5 && node instanceof StickyNode) {
				((StickyNode)node).incExtMembForce(Pt3D.Scale(mag,nodeVec));
			}

			//register collsions
			node.collision();
			fil.collision();
		}
	}

	// Collide a filament tip with the membrane TRIANGLES incident to `node` (node + two of its
	// mutually-linked neighbours). For the closest such face within the collision radius, push the
	// tip out along (tip - closestPointOnFace) and push the three face nodes back (1/3 each) — a soft
	// steric repulsion off the membrane SURFACE, so the tip cannot cross an open face. Reuses the
	// existing tip-near-node mesh pairing; each face is tested from whichever of its nodes the tip is
	// paired with (mild double-count near shared faces is harmless for a steric push).
	static void faceCollideTipVsNodeTriangles (StickyNode node, FilSegment fil) {
		double filTipR = Env.filTipRadiusForCollisions.getValue();
		double colThresh = node.getRadius() + filTipR;          // membrane "thickness" ~ node radius
		Pt3D tip = fil.end2Pt;
		Pt3D base = fil.end1AsPt3D();                            // filament minus end = cytoplasmic (inner) side reference
		Pt3D nC = node.coordAsPt3D();
		// Find the incident face the tip most violates, ONE-SIDED: the tip must stay on the inner
		// (base) side. Orient each face normal AWAY from the base (outward); if the tip is within
		// colThresh of the face plane on the inner side OR has crossed to the outer side, that's a
		// violation, and we push the tip back toward the inner side (never further through).
		StickyNode bestA=null, bestB=null; Pt3D bestN=null; double bestViol=0;
		Pt3D nrm = new Pt3D();
		for (int i=0; i<node.valence; i++) {
			if (!node.isBound[i] || node.boundTo[i]==null) continue;
			StickyNode A = node.boundTo[i];
			for (int j=i+1; j<node.valence; j++) {
				if (!node.isBound[j] || node.boundTo[j]==null) continue;
				StickyNode B = node.boundTo[j];
				if (!A.isLinkedTo(B)) continue;                 // (node, A, B) is a membrane triangle
				Pt3D Ac = A.coordAsPt3D(), Bc = B.coordAsPt3D();
				nrm.cross(Pt3D.Sub(Ac,nC), Pt3D.Sub(Bc,nC));    // face normal (arbitrary sign)
				double nl2 = Pt3D.Dot(nrm,nrm); if (nl2 < 1e-24) continue;
				Pt3D n = Pt3D.Scale(1.0/Math.sqrt(nl2), nrm);
				if (Pt3D.Dot(Pt3D.Sub(base,nC), n) > 0) { n = Pt3D.Scale(-1.0, n); }  // orient outward (away from base)
				double s = Pt3D.Dot(Pt3D.Sub(tip,nC), n);       // signed distance of tip from face plane, outward +
				if (s <= -colThresh) continue;                  // tip safely on the inner side
				Pt3D proj = Pt3D.Add(tip, -s, n);               // tip projected onto the face plane
				if (!pointInTriangle(proj, nC, Ac, Bc)) continue;  // tip isn't actually over this face
				double viol = s + colThresh;                    // >0 (grows as tip nears / crosses the face)
				if (viol > bestViol) { bestViol=viol; bestA=A; bestB=B; bestN=new Pt3D(n.x,n.y,n.z); }
			}
		}
		if (bestA == null) return;                               // no face barrier here (vertex/edge handled by node-sphere)
		if (bestViol > colThresh) bestViol = colThresh;          // bound the push: a deep-leaking tip can't give an unbounded (unstable) force
		double mag = (0.3*1.0e-6*bestViol/Env.collisionDeltaT.getValue())/(1.0/fil.bTransGam.y + 1.0/node.bTransGam.x);
		fil.incForceSum(Pt3D.Scale(-mag, bestN), tip);          // push tip toward inner side (never further out)
		Pt3D back = Pt3D.Scale(mag/3.0, bestN);                 // reaction: membrane nodes pushed outward (sheet deflects = compliant)
		node.incForceSum(back); bestA.incForceSum(back); bestB.incForceSum(back);
		if (Env.membraneYield.getValue() > 0.5) {              // capture the push so the relax pass re-applies it -> protrusion
			node.incExtMembForce(back); bestA.incExtMembForce(back); bestB.incExtMembForce(back);
		}
		node.collision(); fil.collision();
	}

	// Is point p (assumed on the triangle's plane) inside triangle (a,b,c)? Barycentric test.
	static boolean pointInTriangle (Pt3D p, Pt3D a, Pt3D b, Pt3D c) {
		Pt3D v0 = Pt3D.Sub(c,a), v1 = Pt3D.Sub(b,a), v2 = Pt3D.Sub(p,a);
		double d00=Pt3D.Dot(v0,v0), d01=Pt3D.Dot(v0,v1), d02=Pt3D.Dot(v0,v2), d11=Pt3D.Dot(v1,v1), d12=Pt3D.Dot(v1,v2);
		double den = d00*d11 - d01*d01; if (Math.abs(den) < 1e-24) return false;
		double inv = 1.0/den; double u = (d11*d02 - d01*d12)*inv, v = (d00*d12 - d01*d02)*inv;
		return u >= -1e-6 && v >= -1e-6 && u+v <= 1.0+1e-6;
	}

	
	public static boolean sameNodeBound (FilSegment iSeg, FilSegment jSeg) {
		if (!iSeg.nodeAtEnd2) { return false; }
		if (!jSeg.nodeAtEnd2) { return false; }
		if (iSeg.end2Node == jSeg.end2Node) { return true; }
		return false;
	}
	
	public static boolean roughCollisionCheck (FilSegment fil1, FilSegment fil2) {
		if (fil1.filID == fil2.filID) { return false; }
		if (Math.abs(fil1.getCoordX() - fil2.getCoordX()) > fil1.xRange+fil2.xRange) { return false; }		// quick checks
		if (Math.abs(fil1.getCoordY() - fil2.getCoordY()) > fil1.yRange+fil2.yRange) { return false; }
		if (Math.abs(fil1.getCoordZ() - fil2.getCoordZ()) > fil1.zRange+fil2.zRange) { return false; }
			
		return true;
	}
	
	public static void checkToLink (FilSegment fil1, FilSegment fil2) {
		// Per-worker reused RetObj (Pt3D SoA inc 0b sub-(b)). Lifetime is the
		// single lineSegmentIntersectTest write + immediate read below — no
		// nested xLink/collision call consumes it. lineSegmentIntersectTest
		// begins with retO.reset() and only sets conPt1/conPt2/conDistSq
		// when retO.collision becomes true; the reader gates on collision
		// before touching those fields, so stale data from a prior call
		// cannot leak in. Previously `new RetObj()` per filament-pair at
		// ~24 % of per-step Pt3D allocation in the XLink phase.
		RetObj retO = currentScratch().retObj;
		if (fil1.nodeAtEnd2 && fil2.nodeAtEnd2) {
			if (fil1.end2Node == fil2.end2Node) { return; }  // no xlinks between first segments from same node
		}
		
		double angTween,angTweenR;
		double maxAngle = Env.maxXLinkBondAngle.getValue();
		switch (Env.xLinks.getIntValue()) {
		case 0:
			angTween = Pt3D.fastAcos(Pt3D.Dot(fil1.uVecAsPt3D(), fil2.uVecAsPt3D()));
			angTweenR = Pt3D.fastAcos(Pt3D.Dot(fil1.uVecAsPt3D(), fil2.uVecRAsPt3D()));
			if ((angTween > maxAngle) & (angTweenR > maxAngle)) { return; }
			break;
		case 1:
			angTween = Pt3D.fastAcos(Pt3D.Dot(fil1.uVecAsPt3D(), fil2.uVecAsPt3D()));
			if (angTween > maxAngle) { return; }
			break;
		case -1:
			angTweenR = Pt3D.fastAcos(Pt3D.Dot(fil1.uVecAsPt3D(), fil2.uVecRAsPt3D()));
			//if (Env.buildBranchedFils.isActive()) { System.out.println("Angle between test filaments is " + angTween + " radians"); }
			if (angTweenR > maxAngle) { return; }
			break;
		}
		
		lineSegmentIntersectTest(fil1.end1Pt,fil1.end2Pt,fil2.end1Pt,fil2.end2Pt,retO);
		double xLinkGrab = Env.crossLinkGrabDist.getValue();
		if ((retO.collision) && retO.conDistSq < xLinkGrab*xLinkGrab) {
			double loc1 = Pt3D.ptDist(fil1.end1Pt,retO.conPt1) + (2*currentScratch().rng.nextDouble()-1)*minFilLinkSep;
			double loc2 = Pt3D.ptDist(fil2.end1Pt,retO.conPt2) + (2*currentScratch().rng.nextDouble()-1)*minFilLinkSep;
			if (loc1 > fil1.length) { loc1 = fil1.length; }
			if (loc1 < 0) { loc1 = 0; }
			if (loc2 > fil2.length) { loc2 = fil2.length; }
			if (loc2 < 0) { loc2 = 0; }
			
			
			if (fil1.tooCloseFilLinkLoc(loc1)) { return; }
			if (fil2.tooCloseFilLinkLoc(loc2)) { return; }

			// Probabilistic, concentration-dependent formation (2026-06-12). A
			// qualifying candidate (alignment + line-segment proximity + spacing) forms
			// a link only with probability
			//   P_form = 1 - exp(-k_on * [xlink] * dtCheck),  dtCheck = deltaT*crosslinkCheckInt,
			// the standard first-order on-event over one crosslink-check interval (it
			// linearizes to k_on*[xlink]*dtCheck for small p; the exact form stays
			// bounded in [0,1) for large rate). This makes formation a stochastic
			// biochem-class event balanced against the FilLink Bell-model dissolution,
			// giving a finite tunable steady-state link population. checkToLink is the
			// single formation site for BOTH the CPU mesh walk and the GPU candidate
			// drain, so one roll covers both paths. RNG: per-worker currentScratch().rng
			// (the drain runs on the main loop thread → main-thread scratch slot).
			double dtCheck = Env.deltaT.getValue() * Thing.crosslinkCheckInt;
			double pForm = 1.0 - Math.exp(-Env.xLinkOnRate.getValue() * Env.xLinkConc.getValue() * dtCheck);
			if (currentScratch().rng.nextDouble() >= pForm) { return; }

			FilLink.makeLink(fil1, loc1, fil2, loc2);
		}
	}
	
	public boolean ptInNodeBoundingBox (Pt3D pt, ProteinNode node) {
		// quicker checks to see if this end could be colliding with this plasmid... bounding box
		double cushion = Env.actinMonoDiam;
		double nodeRad = node.getRadius()+cushion;
		// x coordAsPt3D()
		if (pt.x < node.getCoordX() - nodeRad) { return false; }
		if (pt.x > node.getCoordX() + nodeRad) { return false; }
		// y coordAsPt3D()
		if (pt.y < node.getCoordY() - nodeRad) { return false; }
		if (pt.y > node.getCoordY() + nodeRad) { return false; }
		// z coordAsPt3D()
		if (pt.z < node.getCoordZ() - nodeRad) { return false; }
		if (pt.z > node.getCoordZ() + nodeRad) { return false; }
		
		return true;
	}
	
	public boolean nodeInFilSegBoundingBox (ProteinNode node) {
		double cushion = Env.actinMonoDiam;
		double sumRad = node.getRadius()+cushion + length/2;
		// x coordAsPt3D()
		if (Math.abs(getCoordX() - node.getCoordX()) > sumRad) { return false; }
		// y coordAsPt3D()
		if (Math.abs(getCoordY() - node.getCoordY()) > sumRad) { return false; }
		// z coordAsPt3D()
		if (Math.abs(getCoordZ() - node.getCoordZ()) > sumRad) { return false; }
		
		return true;
	}
	
	public void nodeCollisions() {
		// retObj is per-worker scratch (Pt3D SoA inc 0a sub-(b)); pointAndLine
		// writes it then we read it before any other consumer can touch it.
		final RetObj retObj = currentScratch().retObj;
		ProteinNode curNode;
		for (int i=0;i<ProteinNode.nodeCt;i++) {
			curNode = ProteinNode.theNodes[i];
			if (nodeInFilSegBoundingBox(curNode)) {
				Thing.pointAndLineIntersectTest(curNode.coordAsPt3D(), end1Pt, end2Pt, retObj);
				double nodeR = curNode.getRadius();
				if (retObj.collision && retObj.conDistSq < nodeR*nodeR) {
					double arcOnFil = Pt3D.ptDist(end1Pt, retObj.conPt1);
					//curNode.myosinOn(this,arcOnFil);
				}
			}
		}
	}
	
	public boolean myoMotorInFilSegBoundingBox (MyoMotor mot) {
		double cushion = Env.actinMonoDiam;
		double sumRad = mot.getDim()+cushion + length/2;
		// x coordAsPt3D()
		if (Math.abs(getCoordX() - mot.getCoordX()) > sumRad) { return false; }
		// y coordAsPt3D()
		if (Math.abs(getCoordY() - mot.getCoordY()) > sumRad) { return false; }
		// z coordAsPt3D()
		if (Math.abs(getCoordZ() - mot.getCoordZ()) > sumRad) { return false; }
		
		return true;
	}
	

	/*public void myoMotorCollisions() {
		MyoMotor curMotor;
		for (int i=0;i<Myosin.myoCt;i++) {
			curMotor = Myosin.theMyosins[i].myoMotor;
			if (!curMotor.onFil && myoMotorInFilSegBoundingBox(curMotor)) {
				MyoFilLink.numInBoundingBoxes++;
				Thing.pointAndLineIntersectTest(curMotor.bindTip, end1Pt, end2Pt, retObj);
				if (retObj.collision && retObj.conDist < Env.myoColTol.getValue()) {
					MyoFilLink.nodeHits++;
					double arcOnFil = Pt3D.ptDist(end1Pt, retObj.conPt1);
					curMotor.ontoFilament(this,arcOnFil);
				}
			}
		}
	}
	*/
	
	/*public void nodeCollisions() {
		// if colliding with protein node at end1Pt then push away
		for (int i=0;i<ProteinNode.nodeCt;i++){
			ProteinNode curNode = ProteinNode.theNodes[i];
			if (ptInNodeBoundingBox(end1Pt,curNode)) {
				double distToEnd1 = Pt3D.ptDist(curNode.coordAsPt3D(),end1Pt);
				double impDist = curNode.getRadius() - distToEnd1;
				//if (impDist > -halfmono) { end1TipC = 0; } 	// steric hindrance to polymerization set
				if (impDist > 0) {
					linkUVec.unitVec(distToEnd1,end1Pt,curNode.coordAsPt3D());
					linkUVecR.scale(-1,linkUVec);
					// define cosines of angles between filament uVecs and line between endpoints
					double cosAngTween1 = Pt3D.CrossMag(uVecRAsPt3D(),linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
					double moveCoeff1 = 1/bTransGam.x + Math.pow(1e-6*length*cosAngTween1/2,2)/bRotGam.y;
					double moveCoeff2 = 1/curNode.bTransGam.x;
					double forceMag = (Env.fracMove.getValue()*1.0e-6*impDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
					
					F.scale(forceMag,linkUVec);
					incForceSum(F);
					R.scale(1e-6*length/2,uVecRAsPt3D());
					RcrossF.cross(R,F);
					incTorqueSum(RcrossF);
					
					Fopp.scale(-1,F);
					curNode.incForceSum(Fopp);
					// note: no torque on node.... force through CM
					
					curNode.myosinOn(this,0);
				}
			}
		}

		// if colliding with plasmid at end2Pt then push away
		for (int i=0;i<ProteinNode.nodeCt;i++){
			ProteinNode curPlasmid = ProteinNode.theNodes[i];
			if (ptInNodeBoundingBox(end2Pt,curPlasmid)) {
				double distToEnd2 = Pt3D.ptDist(curPlasmid.coordAsPt3D(),end2Pt);
				double impDist = curPlasmid.getRadius() - distToEnd2;
				//if (impDist > -halfmono) { end2TipC = 0; } 	// steric hindrance to polymerization set
				if (impDist > 0) {
					linkUVec.unitVec(distToEnd2,end2Pt,curPlasmid.coordAsPt3D());
					linkUVecR.scale(-1,linkUVec);
					// define cosines of angles between filament uVecs and line between endpoints
					double cosAngTween1 = Pt3D.CrossMag(uVecAsPt3D(),linkUVec);  // use magnitude of cross product (which is Sin(theta)) 'cause we want Cos(90-theta)=Sin(theta)
					double moveCoeff1 = 1/bTransGam.x + Math.pow(1e-6*length*cosAngTween1/2,2)/bRotGam.y;
					double moveCoeff2 = 1/curPlasmid.bTransGam.x;
					double forceMag = (Env.fracMove.getValue()*1.0e-6*impDist)/(Env.deltaT.getValue()*(moveCoeff1 + moveCoeff2));
					
					F.scale(forceMag,linkUVec);
					incForceSum(F);
					R.scale(1e-6*length/2,uVecAsPt3D());
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
			if (ptInNodeBoundingBox(end2Pt,curNode)) { return true; }
		}
		return false;
	}
	

	public void checkForminBinding() {
		//** In latcon model assume only barbed-end (end2Pt) can bind to formin at node **
		// if colliding with node at end2Pt then nodeAtEnd2 = true; and end2Node = the colliding plasmid
		if ((!filAtEnd2) && (!nodeAtEnd2) && (end2DetachCounter ==0)) {
			for (int i=0;i<ProteinNode.nodeCt;i++){
				ProteinNode curNode = ProteinNode.theNodes[i];
				if (forminCloseAndReady(curNode)) {
					double distToNode = Pt3D.ptDist(curNode.coordAsPt3D(),end2Pt);
					if (distToNode<curNode.getRadius()) {
						nodeAtEnd2= true;
						end2Node=curNode;
						linkUVec.sub(curNode.getRadius()/distToNode,end2Pt,curNode.coordAsPt3D());	// vector to edge of plasmid in direction from coordAsPt3D() to end2Pt
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
			double strainDist = Pt3D.ptDist(end2Node.coordAsPt3D(),end2Pt);
			double forceMag = Env.fracMove.getValue()*1.0e-6*strainDist/((1/bTransGam.x + 1/end2Node.bTransGam.x)*Env.deltaT.getValue());
			toPlasmidUVec.unitVec(end2Node.coordAsPt3D(),end2Pt);
			F.scale(forceMag,toPlasmidUVec);
			incForceSum(F,end2Pt);
			double axialF = Pt3D.Dot(uVecAsPt3D(),F);  // axial force contribution
			end2NodeForceThisStep = axialF;
			end2NodeForce.registerValue(axialF);
			incEnd2AxialForce(axialF);  

			Fopp.scale(-1,F);
			end2Node.incForceSum(Fopp);
			
			// torque to keep a certain alignment with node
			if (Env.nodeTorqSpring.isActive()) {
				forminVecInX.xToX(end2Node,forminVecInx);
				double dotVecs = Pt3D.Dot(forminVecInX,uVecAsPt3D());
				//if (dotVecs < 0) { dotVecs = 0; }
				if (dotVecs > 1) { dotVecs = 1; }
				if (dotVecs < -1) { dotVecs = -1; }
				double angTween = Pt3D.fastAcos(dotVecs);
				double torqMag = Env.nodeTorqSpring.getValue()*angTween;
				//System.out.println ("angTween = " + angTween*180/Math.PI);
				R.cross(uVecAsPt3D(),forminVecInX);
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
			} else if ((Env.nodeTetherDetachRate.isActive()) & (currentScratch().rng.nextDouble() < Env.nodeTetherDetachRate.getValue()*Env.deltaT.getValue())) {
				removeTether = true;
			} 
			if (removeTether) {
				//talkln ("removing plasmid tether at end2Pt");
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
			// Anchor the Arp2/3 (pointed) end at the node's INNER steric face, not its center. A membrane
			// node's center sits on the cortex (radius R); pulling the pointed end there parks it on/through
			// the rendered membrane. Offset inward along the node's outward normal (zVec) by the same
			// standoff a barbed tip would stop at, so the pointed end sits just inside the cortex.
			Pt3D end1Tgt = end1Node.coordAsPt3D();
			if (end1Node instanceof StickyNode) {
				double inset = Env.membraneNodeRadius.getValue() + Env.filTipRadiusForCollisions.getValue()
						+ Env.motherTetherDepth.getValue();   // hold the pointed end this far OFF the surface
				// Inset along the GEOMETRIC outward radial on the sphere (not the node's body-frame zVec,
				// which rotates off-radial during the sim and can flip -> tether target ends up OUTSIDE the
				// cortex, pinning the pointed end out through the membrane). Flat sheet: zVec is stable.
				Pt3D outward = StickyNode.sphericalGeometry
						? Pt3D.UnitVec(end1Node.coordAsPt3D(), StickyNode.centerOfSphere)  // node from center = outward
						: end1Node.zVecAsPt3D();
				end1Tgt = Pt3D.Add(end1Tgt, -inset, outward);
			}
			double strainDist = Pt3D.ptDist(end1Tgt,end1Pt);
			double forceMag = Env.fracMove.getValue()*1.0e-6*strainDist/((1/bTransGam.x + 1/end1Node.bTransGam.x)*Env.deltaT.getValue());
			toPlasmidUVec.unitVec(end1Tgt,end1Pt);
			F.scale(forceMag,toPlasmidUVec);
			incForceSum(F,end1Pt);
			double axialF = Pt3D.Dot(uVecAsPt3D(),F);  // axial force contribution
			incEnd1AxialForce(axialF);  

			Fopp.scale(-1,F);
			// A membrane (StickyNode) anchor is a heavy, mesh-constrained structure — the ERM linker should
			// hold the filament without the filament dragging the node around (the node-vs-filament drag is
			// only comparable, so the un-scaled reaction yanks the node ~half the strain each step, the
			// hot-zone jitter). Scale the reaction on the membrane node down; the filament still feels the
			// full tether. (Non-membrane anchors keep the full Newton reaction.)
			if (end1Node instanceof StickyNode) { Fopp.scale(Env.membraneAnchorReactionFrac.getValue()); }
			end1Node.incForceSum(Fopp);
			// (tether reaction is NOT captured for membraneYield — only the barbed-end face-collision push
			// drives protrusion; the tether reaction can be large at high strain and destabilizes the relax)

			
			// register strainDist and check for filament detachment
			end1ToPlasStrain.registerValue(strainDist);
			boolean removeTether = false;
			if ((Env.maxNodeTetherStrainDist.isActive()) & (end1ToPlasStrain.averageVal() > Env.maxNodeTetherStrainDist.getValue())) { 	// if strain greater than max allowable
				removeTether = true; 
			} else if ((Env.nodeTetherDetachRate.isActive()) & (currentScratch().rng.nextDouble() < Env.nodeTetherDetachRate.getValue()*Env.deltaT.getValue())) {
				removeTether = true;
			} 
			if (removeTether) {
				//talkln ("removing plasmid tether at end2Pt");
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
				end2PAttachPt.sub(end2Pt,end2Node.coordAsPt3D());
				end2PAttachPt.unitVec();
				end2PAttachPt.scale(end2Node.getRadius());
				end2PAttachPt.XTox(end2Node);
			}
			
			end2PAttachPtInX.xToXPlusxOrigin(end2Node,end2PAttachPt);
			double strainDist = Pt3D.ptDist(end2PAttachPtInX,end2Pt);
			double forceMag = Env.fracMove.getValue()*1.0e-6*strainDist/((1/bTransGam.x + 1/end2Node.bTransGam.x)*Env.deltaT.getValue());
			toPlasmidUVec.unitVec(end2PAttachPtInX,end2Pt);
			F.scale(forceMag,toPlasmidUVec);
			incForceSum(F);
			R.scale(1e-6*(0.5*length),uVecAsPt3D());
			double axialF = Pt3D.Dot(uVecAsPt3D(),F);  // axial force contribution
			incEnd2AxialForce(axialF);  
			RcrossF.cross(R,F);
			incTorqueSum(RcrossF);
			
			Fopp.scale(-1,F);
			end2Node.incForceSum(Fopp);
			R.sub(1e-6,end2PAttachPtInX,end2Node.coordAsPt3D());
			RcrossF.cross(R,Fopp);
			end2Node.incTorqueSum(RcrossF);
			
			// steric hindrance from plasmid
			//double toEnd2Dist = Pt3D.ptDist(end2Plasmid.coordAsPt3D(), end2Pt);
			//if (toEnd2Dist < (end2Plasmid.getRadius()-halfmono)) { end2TipC = 0; }
			
			
			//	torsional spring between plasmid and segment
			toPlasmidUVec.unitVec(end2Pt,end2Node.coordAsPt3D());	// the unit vector orthogonal to the plasmid at the attachment point
			torsionVec.cross(uVecRAsPt3D(),toPlasmidUVec);
			torsionVec.unitVec();
			double angTween = Pt3D.fastAcos(Pt3D.Dot(uVecRAsPt3D(),toPlasmidUVec));
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
			} else if ((Env.nodeTetherDetachRate.isActive()) & (currentScratch().rng.nextDouble() < Env.nodeTetherDetachRate.getValue()*Env.deltaT.getValue())) {
				removeTether = true;
			} 
			if (removeTether) {
				//talkln ("removing plasmid tether at end2Pt");
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
		if (currentScratch().rng.nextDouble() < Env.forminRelease.getValue()*Env.biochemDeltaT.getValue()) {
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
		if (currentScratch().rng.nextDouble() < releaseProb) {
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
		R.sub(End,coordAsPt3D()); 		// define vector from center of filament out to the endpoint
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
		if (End == end1Pt) { 
			incEnd1AxialForce(Pt3D.Dot(uVecRAsPt3D(),Fcoll)); 
			end1TipC = 0;
		}
		if (End == end2Pt) { incEnd2AxialForce(Pt3D.Dot(uVecAsPt3D(),Fcoll)); 
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
		R.sub(End,coordAsPt3D()); 		// define vector from center of filament out to the endpoint
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
		if (currentScratch().rng.nextDouble()< onRate*theBox.getMonomerConc()*Env.biochemDeltaT.getValue()){
			monomerCt++;
			length+=halfmono;
			theBox.takeMonomer(1);
			lengthChanged = true;
			return true;
		}
		return false;
	}
	
	public boolean addNonHydroMonomerSim (double onRate){
		if (currentScratch().rng.nextDouble()< onRate*theBox.getNonHydroMonomerConc()*Env.biochemDeltaT.getValue()){
			monomerCt++;
			length+=halfmono;
			theBox.takeNonHydroMonomer(1);
			lengthChanged = true;
			return true;
		}
		return false;
	}
	
	public boolean removeMonomerSim (double offRate, Monomer endMon) {
		if (currentScratch().rng.nextDouble()< offRate*Env.biochemDeltaT.getValue()){
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
	
	// Closed-membrane confinement: a soft one-sided inward radial force on any filament end that has
	// poked past the cortex. The membrane node lattice is porous to filament BODIES (only barbed tips
	// collide with nodes), so free/depolymerizing filaments otherwise drift out through the gaps and
	// accumulate outside the cell. This is the membrane physically containing the cytoskeleton.
	public void addMembraneConfinement () {
		if (!StickyNode.sphericalGeometry || !Env.membraneConfine.isActive()) { return; }
		// Confine to the INNER STERIC FACE of the cortex, not the node-center sphere: a barbed tip can't
		// pass closer than (nodeRadius + filTipRadius) to a node center, and the viewer draws the membrane
		// surface there too (membraneSurfaceFit). Holding filaments at the node-center radius leaves them
		// in the (nodeR+filTipR)-thick shell OUTSIDE the rendered membrane — looking like they poke out.
		double inset = Env.membraneNodeRadius.getValue() + Env.filTipRadiusForCollisions.getValue();
		double Rc = Env.membraneCellRadius.getValue() - inset;
		confineEndInside(end1Pt, Rc);
		confineEndInside(end2Pt, Rc);
	}

	// Lay an Arp2/3-held de-novo mother TANGENT to the cortex (the 'nurse log'): a gentle restoring
	// torque rotating the filament's long axis into the local tangent plane, so it lies along the
	// membrane and 70-degree branches grow off it into the cytoplasm — rather than the mother spiking
	// radially inward. Mirrors Arp23.applyTorsionForce (align uVec to a target direction).
	public void addCortexAlignTorque () {
		if (!StickyNode.sphericalGeometry || !Env.membraneAlignTorque.isActive()) { return; }
		if (!((childOfArp23 || forminMother) && nodeAtEnd1)) { return; }  // only the tethered held mothers
		Pt3D radOut = Pt3D.UnitVec(coordAsPt3D(), StickyNode.centerOfSphere);  // outward surface normal at filament center
		Pt3D u = uVecAsPt3D();
		double uDotR = Pt3D.Dot(u, radOut);
		Pt3D tang = Pt3D.Sub(u, Pt3D.Scale(uDotR, radOut));      // tangential component of u (azimuth to keep)
		if (Pt3D.Dot(tang, tang) < 1e-9) { return; }             // u ~ radial: azimuth undefined, skip (rare)
		tang.unitVec();
		// Target = the nearest direction at membraneAlignAngle from the OUTWARD normal, same azimuth as u.
		// 90deg -> target = tang (tangent mat); <90 -> tilts the barbed end toward the membrane (protrusion).
		double a = Math.toRadians(Env.membraneAlignAngle.getValue());
		Pt3D target = Pt3D.Add(Math.cos(a), radOut, Math.sin(a), tang);
		target.unitVec();
		double dot = Pt3D.Dot(u, target); if (dot > 1) dot = 1; if (dot < -1) dot = -1;
		double ang = Pt3D.fastAcos(dot);                         // deviation from the target cone
		Pt3D axis = new Pt3D(); axis.cross(u, target);           // rotate u -> target
		if (!axis.checkPt3D() || Pt3D.Dot(axis, axis) < 1e-18) { return; }
		axis.unitVec();
		axis.scale(Env.membraneAlignTorque.getValue()*ang);
		incTorqueSum(axis);
	}

	private void confineEndInside (Pt3D pt, double Rc) {
		double r = Pt3D.ptDist(pt, StickyNode.centerOfSphere);
		if (r <= Rc) { return; }                                   // inside the cortex: no force
		Pt3D inward = Pt3D.UnitVec(StickyNode.centerOfSphere, pt); // from the poking end toward the center
		double overshoot = r - Rc;
		double mag = Env.membraneConfineFrac.getValue()*(1.0e-6*overshoot/Env.collisionDeltaT.getValue())/(1.0/bTransGam.x);
		incForceSum(Pt3D.Scale(mag, inward), pt);                  // push the end back in (torque reorients the filament inward)
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
		// normOrientation: my end1 attaches to neighbour's end2 → side = 1
		// !normOrientation: my end1 attaches to neighbour's end1 → side = 0
		end1NbrSide = (byte)(normOrientation ? 1 : 0);
	}

	public void setEnd2Links (FilSegment at2, boolean normOrientation) {
		filAtEnd2 = true;
		end2Fil = at2;
		// normOrientation: my end2 attaches to neighbour's end1 → side = 0
		// !normOrientation: my end2 attaches to neighbour's end2 → side = 1
		end2NbrSide = (byte)(normOrientation ? 0 : 1);
	}

	public void removeEnd1Links() {
		filAtEnd1 = false;
		end1Fil = null;
	}

	public void removeEnd2Links() {
		filAtEnd2 = false;
		end2Fil = null;
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
				// Original identity scheme: cleanF.ptAtEnd1 pointed at either
				// cleanF.end1Fil.end1Pt (cleanF.end1NbrSide == 0) or .end2Pt (side == 1).
				// We hand cleanF's end2 link to cleanF.end1Fil, replacing the slot
				// (end1 vs end2) on end1Fil that previously linked to cleanF. The
				// new side flag on end1Fil's new neighbour is cleanF.end2NbrSide
				// (whichever end of cleanF.end2Fil cleanF's end2 was attached to).
				if (cleanF.end1NbrSide == 1) {			// cleanF's end1 was attached to end1Fil's end2
					cleanF.end1Fil.filAtEnd2 = true;
					cleanF.end1Fil.end2Fil = cleanF.end2Fil;
					cleanF.end1Fil.end2NbrSide = cleanF.end2NbrSide;
				} else {								// cleanF's end1 was attached to end1Fil's end1
					cleanF.end1Fil.filAtEnd1 = true;
					cleanF.end1Fil.end1Fil = cleanF.end2Fil;
					cleanF.end1Fil.end1NbrSide = cleanF.end2NbrSide;
				}

				if (cleanF.end2NbrSide == 0) {			// cleanF's end2 was attached to end2Fil's end1
					cleanF.end2Fil.filAtEnd1 = true;
					cleanF.end2Fil.end1Fil = cleanF.end1Fil;
					cleanF.end2Fil.end1NbrSide = cleanF.end1NbrSide;
				} else {								// cleanF's end2 was attached to end2Fil's end2
					cleanF.end2Fil.filAtEnd2 = true;
					cleanF.end2Fil.end2Fil = cleanF.end1Fil;
					cleanF.end2Fil.end2NbrSide = cleanF.end1NbrSide;
				}

				// remove cleanF links
				cleanF.filAtEnd1 = false;
				cleanF.end1Fil = null;
				cleanF.filAtEnd2 = false;
				cleanF.end2Fil = null;
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
			cleanup(curSeg,false,true);
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
	
	// Brownian-ratchet polymerization closure (Mogilner-Oster), gating the barbed-end poly rate on the
	// resolved clearance g=end2TipC: free rate when a full monomer fits (g>=delta), else the Boltzmann
	// probability of a thermal fluctuation opening the remaining deficit (delta-g) against load f. An
	// existing gap shrinks the deficit and raises the rate; the penalty vanishes at g>=delta. g=1e6 for
	// tips with no detected obstacle => free rate (away from the membrane). See RATCHET_CLOSURE_DESIGN.
	public double ratchetPolyFactor() {
		// g is the gap from the tip to the membrane's STERIC SURFACE — where the collision actually
		// stops the tip, which is filTipRadiusForCollisions BEYOND the node surface. end2TipC is the
		// tip-to-node-surface distance, so subtract filTipR. Without this the collision standoff
		// (~filTipR ~= 18 monomers) keeps g >> delta and the ratchet never engages.
		double g = end2TipC - Env.filTipRadiusForCollisions.getValue();   // gap to membrane steric surface (um)
		double delta = halfmono;      // monomer length increment (um)
		if (g >= delta) return 1.0;   // a full monomer already fits -> unobstructed
		double deficit = delta - g;   // remaining gap a fluctuation must open (um)
		if (deficit > delta) deficit = delta;  // g<0 (tip overlapping membrane): cap the deficit at one monomer
		double f = Env.ratchetForce.getValue();        // membrane-normal load (N)
		double kT = Env.Boltz * Env.tempK;             // J
		return Math.exp(-f * (deficit * 1e-6) / kT);   // deficit um -> m; f*deficit = J
	}

	public boolean stericHindranceEnd2() {
		if (end2TipC < halfmono) return true;
		return false;
	}
	
	// incForceSum/incTorqueSum inherited from Thing — per-thread accumulator path.
	
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
		
		AnchorNode end2Anchor0 = new AnchorNode(newFil0.end2Pt);
		linkEnd2Node(newFil0,end2Anchor0);
		
		// second antiparallel filament
		Pt3D loc1 = new Pt3D(-xSpacing,-ySpacing,0);
		Pt3D ang1 = new Pt3D(-1,0,0);
		//StaticFilSegment newFil1 = new StaticFilSegment (loc1,ang1,-1,monCt,false);
		FilSegment newFil1 = new FilSegment (loc1,ang1,-1,monCt,false);
		
		AnchorNode end2Anchor1 = new AnchorNode(newFil1.end2Pt);
		linkEnd2Node(newFil1,end2Anchor1);
	}
	
	public static void makeWestCircleFilaments () {
		int minMonCt = (int)(Env.circleFilsMinLength.getValue()/Env.actinMonoRadius) - 1;
		int maxMonCt = (int)(Env.circleFilsMaxLength.getValue()/Env.actinMonoRadius) - 1;
		int numFils = Env.westCircleFils.getIntValue();
		double baseRad = 0.25;
		Pt3D basePt = new Pt3D(-Env.boxXDim.getValue()/2,0,0);
		Pt3D endPt = new Pt3D();
		Pt3D localUVec = new Pt3D();
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
			localUVec.unitVec(endPt,basePt);
			coordPt.add(endPt,length/2,localUVec);
			
			if (Env.circleFilsMixedPolarity.isActive() && Math.random() < 0.5) {
				FilSegment newFil = new FilSegment (coordPt,localUVec,-1,monCt,false);
				AnchorNode end1Anchor = new AnchorNode(newFil.end1Pt);
				linkEnd1Node(newFil,end1Anchor);
			} else {
				localUVec.reverse();
				FilSegment newFil = new FilSegment (coordPt,localUVec,-1,monCt,false);
				AnchorNode end2Anchor = new AnchorNode(newFil.end2Pt);
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
		Pt3D localUVec = new Pt3D();
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
			localUVec.unitVec(endPt,basePt);
			coordPt.add(endPt,length/2,localUVec);

			if (Env.circleFilsMixedPolarity.isActive() && Math.random() < 0.5) {
				FilSegment newFil = new FilSegment (coordPt,localUVec,-1,monCt,false);
				AnchorNode end1Anchor = new AnchorNode(newFil.end1Pt);
				linkEnd1Node(newFil,end1Anchor);
			} else {
				localUVec.reverse();
				FilSegment newFil = new FilSegment (coordPt,localUVec,-1,monCt,false);
				AnchorNode end2Anchor = new AnchorNode(newFil.end2Pt);
				linkEnd2Node(newFil,end2Anchor);
			}
			
			
		}
	}
	
	// Seed several LONG mother filaments just under the membrane sheet (z=0), barbed ends
	// pointing UP (+z) into it, each with a few DETERMINISTIC Arp2/3 branches. With de-novo
	// nucleation off, this gives a legible branched network deforming the cortex (instead of
	// the hot-Rho swarm), and the daughter drag floor keeps the branches stable. Gated by
	// (buildBranchedFils && buildMembraneSheet) in makeInitialThings; test scaffold, not production.
	public static void makeMembraneBranchedMothers() {
		int nMothers = 5;
		int momMonomers = 80;                                  // ~0.22 um mother (visible)
		double momLen = (momMonomers+1)*Env.actinMonoRadius;
		// Start the barbed tips just INSIDE the cytoplasm, below the membrane's steric contact (a tip is
		// stopped nodeRadius+filTipR below the node plane), so the seeded mothers don't begin poking
		// through the membrane. Tied to the collision geometry so it tracks the radii.
		double standoff = Env.membraneNodeRadius.getValue() + Env.filTipRadiusForCollisions.getValue();
		double barbedZ = -standoff - 0.02;                     // barbed tip a hair below the collision contact
		double cz = barbedZ - 0.5*momLen;                      // centre z so end2 (barbed, +z) sits at barbedZ
		// place mothers in a small ring near the hot-patch centre so the branched tufts are distinct
		double[][] xy = { {0.0,0.0}, {0.22,0.0}, {-0.22,0.0}, {0.0,0.22}, {0.0,-0.22} };
		Pt3D up = new Pt3D(0,0,1);                             // grow +z toward the membrane (barbed end up)
		double filLen = momMonomers*Env.actinMonoRadius;
		double[] branchFracs = {0.45, 0.65, 0.85};             // deterministic branches along the upper (near-membrane) part
		for (int m=0; m<nMothers; m++) {
			Pt3D loc = new Pt3D(xy[m][0], xy[m][1], cz);
			FilSegment mom = new FilSegment(loc, up, -1, momMonomers, false);
			for (double fr : branchFracs) {
				double bLoc = fr*filLen;
				if (mom.canAddArpHere(bLoc)) { mom.makeArpBranch(bLoc); }
			}
		}
	}

	public static void makeTestBranchedFilament() {
		if (Env.junctionTest.getValue() > 0.5) { makeSingleJunctionTest(); return; }
		Pt3D coordForBoth = new Pt3D(0,0,0);
		Pt3D fil1UVec = new Pt3D(0,0,-1);
		double testAngleBetween = 178; // in degrees
		double testAngleInRads = testAngleBetween*Math.PI/180;
		double xPart = Math.cos(testAngleInRads); 
		double yPart = Math.sin(testAngleInRads);
		Pt3D fil2UVec = new Pt3D(xPart,yPart,0); 
		int numMonomers = 128;
		FilSegment mFil = new FilSegment (coordForBoth,fil1UVec,0,numMonomers,false);

		// Deterministic branches: fixed count, evenly spaced along the mother
		// (minimal-system Arp2/3 constraint test — no stochastic/membrane trigger).
		double filLen = (double)numMonomers*Env.actinMonoRadius;
		double[] branchFracs = {0.2, 0.35, 0.5, 0.65, 0.8};
		for (double fr : branchFracs) {
			double bLoc = fr*filLen;
			if (mFil.canAddArpHere(bLoc)) {
				mFil.makeArpBranch(bLoc);
			} else {
				talkln("Skipped Arp2/3 branch (too close to another) at frac " + fr);
			}
		}
		//new FilSegment (coordForBoth,fil2UVec,1,600,false);
		
		// make test myosin minifilament
		//new MyoMiniFilament (coordForBoth,fil1UVec);
		//new MyoMiniFilament (coordForBoth,fil1UVec);
		//new MyosinDimer (coordForBoth,fil1UVec);
		
	} 
	
	// Controlled single-junction relaxation test: 1 mother + 1 daughter Arp2/3 branch,
	// daughter perturbed off its constraint by junctionPerturbDeg. With thermal off and a
	// short run, the Arp23 logs the gap/angle relaxation each step (see Arp23.enforceFilLink)
	// so overshoot/ringing can be inspected in isolation, free of growth/floppiness/RNG.
	public static void makeSingleJunctionTest() {
		Pt3D coord = new Pt3D(0,0,0);
		Pt3D mUVec = new Pt3D(0,0,-1);
		int numMonomers = 128;
		FilSegment mFil = new FilSegment(coord, mUVec, 0, numMonomers, false);
		double bLoc = 0.5*numMonomers*Env.actinMonoRadius;
		FilSegment dFil = mFil.makeArpBranch(bLoc);   // nascent daughter at relaxed orient; relaxDUVec set to relaxed
		if (dFil == null) { talkln("junctionTest: branch creation failed"); return; }
		// Perturb daughter orientation by perturbDeg about its center. Rotating about the
		// center swings BOTH ends, so this excites the angular (torsion) AND the end-gap
		// (translation) constraints at once; each is logged separately for attribution.
		double p = Env.junctionPerturbDeg.getValue()*Math.PI/180.0;
		Pt3D u = dFil.uVecAsPt3D();
		Pt3D m = mFil.uVecAsPt3D();
		double mu = Pt3D.Dot(m,u);
		Pt3D w = new Pt3D(m.x-mu*u.x, m.y-mu*u.y, m.z-mu*u.z);   // mother uVec component perp to daughter uVec
		double wn = Math.sqrt(Pt3D.Dot(w,w));
		if (wn < 1e-9) { w.x=u.y; w.y=-u.x; w.z=0; wn=Math.sqrt(Pt3D.Dot(w,w)); }  // fallback perpendicular
		w.scale(1.0/wn);
		double c=Math.cos(p), s=Math.sin(p);
		Pt3D nu = new Pt3D(c*u.x+s*w.x, c*u.y+s*w.y, c*u.z+s*w.z);
		double nn=Math.sqrt(Pt3D.Dot(nu,nu)); nu.scale(1.0/nn);
		dFil.setUVec(nu);
		dFil.initialize();
		talkln("junctionTest: built 1 mother + 1 daughter; daughter perturbed " + Env.junctionPerturbDeg.getValue() + " deg");
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
		//FilSegment mFil = new FilSegment (bug.end1Pt,fil1UVec,0,numMonomers,false);
		Pt3D firstPos = Pt3D.Sub(bug.end1AsPt3D(), bug.coordAsPt3D()); // vector from bug coord to bug end1
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

	// An implicit-formin-nucleated LINEAR mother filament: a plain actin seed (NOT Arp2/3-capped at the
	// pointed end), flagged forminMother so it gets the held-mother treatment (reduced Brownian, cortex
	// alignment) without being treated as an Arp2/3 branch product. Arp2/3 branches off it later.
	synchronized static FilSegment makeForminMother (Pt3D nucPt, Pt3D nucAng) {
		FilSegment m = new FilSegment (nucPt,nucAng,-1);
		m.forminMother = true;
		return m;
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
			linkEnd2Node(nuFil,new AnchorNode(nuFil.end2Pt));
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
	
		new MyoMiniFilament (fil1.coordAsPt3D());
		
		//new MyoMiniFilament (fil1.coordAsPt3D());
		
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
		
		Pt3D p1Loc = Pt3D.Add(fil1.end2Pt,-.5*Env.nodeRadius.getValue(),fil1.uVecAsPt3D());
		ProteinNode node1 = new ProteinNode (p1Loc,false);
		
		fil1.nodeAtEnd2=true;
		fil1.end2Node=node1;
		fil1.end2PAttachPt.zero();  // use this for formins at center of node
		fil1.forminVecInx.XTox(node1,ang);
		node1.filamentOn();
		
		Pt3D p2Loc = Pt3D.Add(fil1.end1Pt,0.5*Env.nodeRadius.getValue(),fil1.uVecRAsPt3D());
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
		
		Pt3D p1Loc = Pt3D.Add(fil1.end2Pt,-.5*Env.nodeRadius.getValue(),fil1.uVecAsPt3D());
		ProteinNode node1 = new ProteinNode (p1Loc,false);
		
		fil1.nodeAtEnd2=true;
		fil1.end2Node=node1;
		fil1.end2PAttachPt.zero();  // use this for formins at center of node
		fil1.forminVecInx.XTox(node1,ang);
		node1.filamentOn();
		
		Pt3D p2Loc = Pt3D.Add(fil1.end1Pt,0.5*Env.nodeRadius.getValue(),fil1.uVecRAsPt3D());
		ProteinNode node2 = new ProteinNode (p2Loc,false);
		// additional filaments
		FilSegment fil2 = new FilSegment (loc2,angR,-1,monCt,false);
		
		fil2.nodeAtEnd2=true;
		fil2.end2Node=node2;
		fil2.end2PAttachPt.zero();  // use this for formins at center of node
		fil2.forminVecInx.XTox(node2,angR);
		node2.filamentOn();
	
		
		
		//Pt3D p2Loc = Pt3D.Add(fil2.end2Pt,Env.nodeRadius.getValue(),fil2.uVecAsPt3D());
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
			tempPt.add(fil1.end1Pt,i*nodeSpacing,fil1.uVecAsPt3D());
			new ProteinNode (tempPt);
		}
		
		Pt3D p1Loc = Pt3D.Add(fil1.end2Pt,1*Env.nodeRadius.getValue(),fil1.uVecAsPt3D());
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
		double stdFilSegLengthUM = Env.stdSegLength.getIntValue() * Env.actinMonoRadius;
		// Pad covers the per-split chain extension. splitSegment() places nextFil
		// at original_end2 + (0.5*nextFilLength - actinMonoRadius) along uVec, so the
		// post-step-1 chain end extends ~1 * stdFilSegLengthUM beyond the IC end2.
		// 0.5* was the centroid shift (used historically); 1.5* keeps the post-step-1
		// chain end comfortably inside the +x wall (~80 nm clear).
		Pt3D loc = new Pt3D(Env.boxXDim.getValue()/2 - filLength/2 - 1.5*stdFilSegLengthUM, 0, 0);
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
				curMonStart.add(end1Pt);
				curMon.updatePosition(curMonStart);
			} else {
				ptFromHelixPos(curMonStart,pos,oppAng);
				curMonStart.xToX(this);
				curMonStart.add(end1Pt);
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
				curMonStart.add(end1Pt);
				if (twoPoints) {
					ptFromHelixPos(curMonStop,pos+Env.actinMonoDiam,helixAng);
					curMonStop.xToX(this);
					curMonStop.add(end1Pt);
				}
			} else {
				ptFromHelixPos(curMonStart,pos,oppAng);
				curMonStart.xToX(this);
				curMonStart.add(end1Pt);
				if (twoPoints) {
					ptFromHelixPos(curMonStop,pos+Env.actinMonoDiam,oppAng);
					curMonStop.xToX(this);
					curMonStop.add(end1Pt);
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
		}
	}

	private void makeNewCyl () {}
	 
	private void updateCylGraphics () {}
	
	private void makeCoordinateSysGraphics () {}
	
	public static void initializeAllAppearances () {}
	public void makeGraphics () {}
	public void updateGraphics () {}
	public void addCoordSysGraphics () {}
	public void removeCoordSysGraphics () {}
	
	public double getEffADPLength() {	// write method to figure length from end1Pt that is a certain high percentage ADP
		return 0;
	}
	
	public double getEffADPPiLength() {  // write method to figure length from end1Pt that is a certain high percentage ADP-Pi
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
	

		// Fraction of monomers NOT in the ADP (aged) state, walking the minus->plus
		// chain. Drives the viewer's age-coloring (red = old/ADP -> yellow = young/ATP):
		// 1.0 = all ATP/ADPPi (newly polymerized), 0.0 = fully hydrolyzed to ADP. Returns
		// 1.0 when monomers aren't individually tracked (noMonomersSimd) or the chain is
		// empty/degenerate. Walked only at output cadence (cheap), mirroring hydrolizeInFilaments.
		public double notADPFraction () {
			if (Env.noMonomersSimd.isActive() || minusMon == null || monomerCt <= 0) return 1.0;
			int n = 0, notAdp = 0;
			Monomer curMon = minusMon;
			while (curMon != Monomer.plusGhost && curMon != null && n < monomerCt + 2) {
				if (curMon.nucleotideState != Monomer.ADPstate) notAdp++;
				n++;
				curMon = curMon.frontMon;
			}
			return n > 0 ? ((double) notAdp) / n : 1.0;
		}

		// ADP fraction of the monomers AT THE POINTED END (the Arp2/3 junction region). Unlike the
		// whole-filament notADPFraction(), this ages even while the barbed end keeps adding fresh ATP
		// monomers — so a continuously-elongating daughter still ages at its junction. Drives P2
		// debranching (GMF-like: ADP at the junction destabilizes the branch). 0 = junction all fresh
		// (ATP/ADP-Pi), 1 = junction fully ADP. Returns 0 when monomers aren't individually tracked.
		public double junctionADPFraction () {
			if (Env.noMonomersSimd.isActive() || minusMon == null || monomerCt <= 0) return 0.0;
			int span = Math.min(monomerCt, 8);   // the oldest ~8 monomers nearest the branch junction
			int n = 0, adp = 0;
			Monomer curMon = minusMon;
			while (curMon != Monomer.plusGhost && curMon != null && n < span) {
				if (curMon.nucleotideState == Monomer.ADPstate) adp++;
				n++;
				curMon = curMon.frontMon;
			}
			return n > 0 ? ((double) adp) / n : 0.0;
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
          getCoordX(),  // position X
          getCoordY(),  // position Y
          getCoordZ(),  // position Z  
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
		
		Pt3D capEndPt = Pt3D.Add(end2Pt, Env.radOfCap,uVecAsPt3D());
		String capXStr = String.format("%.2f",Env.simJSonsScale*getEnd2X());
		String capYStr = String.format("%.2f",Env.simJSonsScale*getEnd2Y());
		String capZStr = String.format("%.2f",Env.simJSonsScale*getEnd2Z());
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
          getCoordX(),  // position X
          getCoordY(),  // position Y
          getCoordZ(),  // position Z  
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
		
		Pt3D capEndPt = Pt3D.Add(end2Pt, Env.radOfCap,uVecAsPt3D());
		String capXStr = String.format("%.2f",Env.simJSonsScale*getEnd2X());
		String capYStr = String.format("%.2f",Env.simJSonsScale*getEnd2Y());
		String capZStr = String.format("%.2f",Env.simJSonsScale*getEnd2Z());
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
          getCoordX(),  // position X
          getCoordY(),  // position Y
          getCoordZ(),  // position Z  
          angle.x,  // rotation X --can be zero for fiber
          angle.y,  // rotation Y --can be zero for fiber
          angle.z,  // rotation Z --can be zero for fiber
          Env.gActinDiameter,   // radius
          6.0,   // number of subpoint values following this number
          getEnd1X(),
          getEnd1Y(),
          getEnd1Z(),
          getEnd1X() + ADPLength*getUVecX(),
          getEnd1Y() + ADPLength*getUVecY(),
          getEnd1Z() + ADPLength*getUVecZ(),
          * and likewise for the other two segments
		*/
		if (!coordAsPt3D().checkPt3D()) { return "";}	// sanity check... if something wrong with actin position then skip serialization
		// points in space of different biochem sections
		/*Pt3D adpPt = Pt3D.Add(end1Pt,getEffADPLength(),uVecAsPt3D());			
		Pt3D adpPiPt = Pt3D.Add(adpPt,getEffADPPiLength(),uVecAsPt3D());
		// coordAsPt3D()
		String coordXStr = String.format("%.2f",Env.simJSonsScale*getCoordX());
		String coordYStr = String.format("%.2f",Env.simJSonsScale*getCoordY());
		String coordZStr = String.format("%.2f",Env.simJSonsScale*getCoordZ());
		// adp endpoint
		String adpPtXStr = String.format("%.2f",Env.simJSonsScale*adpPt.x);
		String adpPtYStr = String.format("%.2f",Env.simJSonsScale*adpPt.y);
		String adpPtZStr = String.format("%.2f",Env.simJSonsScale*adpPt.z);
		// adp-Pi endpoint
		String adpPiPtXStr = String.format("%.2f",Env.simJSonsScale*adpPiPt.x);
		String adpPiPtYStr = String.format("%.2f",Env.simJSonsScale*adpPiPt.y);
		String adpPiPtZStr = String.format("%.2f",Env.simJSonsScale*adpPiPt.z); */
		// end1Pt
		String end1XStr = String.format("%.2f",Env.simJSonsScale*getEnd1X());
		String end1YStr = String.format("%.2f",Env.simJSonsScale*getEnd1Y());
		String end1ZStr = String.format("%.2f",Env.simJSonsScale*getEnd1Z());
		// end2Pt
		String end2XStr = String.format("%.2f",Env.simJSonsScale*getEnd2X());
		String end2YStr = String.format("%.2f",Env.simJSonsScale*getEnd2Y());
		String end2ZStr = String.format("%.2f",Env.simJSonsScale*getEnd2Z());
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

	// F1 benchmark: create n segments in a straight chain along +X axis, linked end-to-end.
	// Caller must set Env.noMonomersSimd active before calling (suppresses Monomer creation
	// in constructors and biochemistry each step). Returns the segment array so BoxOfActin
	// can store first/mid/last references without creating a circular class dependency.
	public static FilSegment[] makeBenchmarkChain(int n) {
		int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
		double segLen = (monCt + 1) * halfmono; // µm
		double totalLen = n * segLen;
		Pt3D xAxis = new Pt3D(1, 0, 0);
		FilSegment[] segs = new FilSegment[n];
		for (int i = 0; i < n; i++) {
			double cx = -totalLen / 2.0 + (i + 0.5) * segLen;
			segs[i] = new FilSegment(new Pt3D(cx, 0, 0), xAxis, 0, monCt, true);
		}
		for (int i = 0; i < n - 1; i++) {
			segs[i].setEnd2Links(segs[i + 1], true);
			segs[i + 1].setEnd1Links(segs[i], true);
		}
		return segs;
	}

	// Contractility assay: build an end-to-end linked straight chain of n rigid segments.
	//   outerPt  — the pinned outer endpoint (sits at the box wall); chain extends inward from it
	//   buildDir — unit vector pointing from outerPt toward the box centre (segments laid along it)
	//   uVec     — filament polarity (segments' long-axis unit vector; end2 = plus/barbed end)
	// uVec is independent of buildDir so a single geometry can be run at either polarity (the
	// reversed-polarity control). Consecutive segments are linked end2<->end1 with the link
	// direction chosen from sign(buildDir·uVec) so the chain is physically continuous either way.
	// Caller is responsible for Env.noMonomersSimd being active (rigid rods). brownOff suppresses
	// per-segment Brownian forcing for a clean axial-tension readout.
	public static FilSegment[] makeStraightChain(int n, Pt3D outerPt, Pt3D buildDir, Pt3D uVec, boolean brownOff) {
		int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
		double segLen = (monCt + 1) * halfmono; // µm
		FilSegment[] segs = new FilSegment[n];
		int fid = -1;
		for (int i = 0; i < n; i++) {
			double s = (i + 0.5) * segLen; // arclength of segment centre from outerPt along buildDir
			Pt3D c = new Pt3D(outerPt.x + s * buildDir.x, outerPt.y + s * buildDir.y, outerPt.z + s * buildDir.z);
			segs[i] = new FilSegment(c, uVec, fid, monCt, true);
			if (i == 0) fid = segs[0].filID; // first segment auto-assigns a unique filID; reuse for the rest
			if (brownOff) segs[i].brownianOff = true;
		}
		boolean buildAlongU = (buildDir.x * uVec.x + buildDir.y * uVec.y + buildDir.z * uVec.z) > 0;
		for (int i = 0; i < n - 1; i++) {
			if (buildAlongU) { // segs[i+1] is on segs[i]'s +uVec (end2) side
				segs[i].setEnd2Links(segs[i + 1], true);
				segs[i + 1].setEnd1Links(segs[i], true);
			} else {           // segs[i+1] is on segs[i]'s -uVec (end1) side
				segs[i].setEnd1Links(segs[i + 1], true);
				segs[i + 1].setEnd2Links(segs[i], true);
			}
		}
		return segs;
	}

	// LP benchmark: create n segments in a straight chain along +X axis at the given Y/Z offset.
	// All segments are marked isLpSeg = true. Caller sets Env.noMonomersSimd active.
	public static FilSegment[] makeLpChain(int n, double yOff, double zOff) {
		int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
		double segLen = (monCt + 1) * halfmono; // µm
		double totalLen = n * segLen;
		Pt3D xAxis = new Pt3D(1, 0, 0);
		FilSegment[] segs = new FilSegment[n];
		for (int i = 0; i < n; i++) {
			double cx = -totalLen / 2.0 + (i + 0.5) * segLen;
			segs[i] = new FilSegment(new Pt3D(cx, yOff, zOff), xAxis, 0, monCt, true);
			segs[i].isLpSeg = true;
		}
		for (int i = 0; i < n - 1; i++) {
			segs[i].setEnd2Links(segs[i + 1], true);
			segs[i + 1].setEnd1Links(segs[i], true);
		}
		return segs;
	}

}


