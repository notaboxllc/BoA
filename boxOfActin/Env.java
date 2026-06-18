 package boxOfActin;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.util.Date;

import edu.cornell.lassp.houle.RngPack.RanMT;

import ec.util.*;
 
public class Env {
	// **** psuedo-random number generator (PRNG) ****
	static final MersenneTwister mtRNG = new MersenneTwister(
			(long) (Long.MAX_VALUE * Math.random()));
	// **** Useful Strings ***
	static final String kOnUnits = " /microMolar-s";
	static final String kOffUnits = " /s";
	static final String concUnits = " microMolar";
	static final String distUnits = " microns";
	static final String degUnits = " degrees";

	// **** Physical Constants ****
	static final double Boltz = 1.380662e-23; // Boltzman's constant in J/K
	static final double tempK = 298.15; // 25 deg Celsius in Kelvin
	static final double AvogadroNum = 6.02214199e23; // Avogadro's number..
														// #/Mole
	
	// **** MASS OF PROTEINS ****  (kiloDaltons) kD
	static final double massOfActin = 42;
	static final double massOfAlphaActinin = 190;
	static final double massOfProfilin = 125;
	static final double massOfCap = 68;
	static final double massOfADF = 15;
	static final double massOfARP = 220;
	static final double massOfActA = 70;
	
	// **** EFFECTIVE RADIUS OF PROTEINS *** (�m)
	// Use the following rules of thumb from Howard:
	// 		~1.2 nm^3 for each kD of protein (1.2e-9 �m^3)
	//		~7 amino acids per nm^3
	// and assume globular so that volume = (4/3)*PI*r^3
	static final double reffCoeff = 3*(1.2e-9)/(4*Math.PI);
	static final double radOfActin = Math.pow(reffCoeff*massOfActin, 1.0/3.0);
	static final double radOfAlphaActinin = Math.pow(reffCoeff*massOfAlphaActinin, 1.0/3.0);
	static final double radOfProfilin = Math.pow(reffCoeff*massOfAlphaActinin, 1.0/3.0);
	static final double radOfCap = Math.pow(reffCoeff*massOfCap, 1.0/3.0);
	static final double radOfADF = Math.pow(reffCoeff*massOfADF, 1.0/3.0);
	static final double radOfARP = Math.pow(reffCoeff*massOfARP, 1.0/3.0);
	static final double radOfActA = Math.pow(reffCoeff*massOfActA, 1.0/3.0);

	// *** The Virtual World Dimensions ****
	static final Pt3D worldDimension = new Pt3D(1, 1, 1);
	static final Pt3D farfarAway = new Pt3D(1000, 1000, 1000);

	// **** Threads *****
	static final int allThreadCt = 16; // use this to change thread count in one fell swoop
	static final int numMeshThreads = allThreadCt;
	static final int numMeshCollThreads = allThreadCt; // must this be 1 or else?
	static final int numBForceThreads = allThreadCt;
	static final int numMembraneThreads = allThreadCt;
	static final int numMembraneLinkThreads = allThreadCt;
	static final int numXLinkThreads = allThreadCt/2;
	static final int numArp23Threads = allThreadCt/2;
	static final int numMyoThreads = allThreadCt;
	static final int numThingStepThreads = allThreadCt;
	static final int numCleanupThreads = allThreadCt;
	static final int numActAThreads = allThreadCt;

	static final int meshFilsStart = 0;
	static final int meshFilsStop = 0;
	static final int meshNodesStart = 1;
	static final int meshNodesStop = 1;
	static final int meshMotorsStart = 2;
	static final int meshMotorsStop = 2;
	static final int meshCollStart = 3;
	static final int meshCollStop = 3;
	static final int motCollStart = 4;
	static final int motCollStop = 4;
	static final int bForcesStart = 5;
	static final int bForcesStop = 5;
	static final int xLinkStart = 6;
	static final int xLinkStop = 6;
	static final int actAStart = 6;
	static final int actAStop = 6;
	static final int myoJoints1Start = 7;
	static final int myoJoints1Stop = 7;
	static final int myoJoints2Start = 8;
	static final int myoJoints2Stop = 8;
	static final int stepStart = 9;
	static final int stepStop = 9;
	static final int moveStart = 10;
	static final int moveStop = 10;
	static final int biochemStart = 11;
	static final int biochemStop = 11;
	static final int resetCtStart = 12;
	static final int resetCtStop = 12;
	static final int cleanupStart = 13;
	static final int cleanupStop = 13;
	static final int membraneLinksStart = 14;
	static final int membraneLinksStop = 14;
	static final int membraneMoveStart = 15;
	static final int membraneMoveStop = 15;
	static final int motorBindGrid3DStart = 16;
	static final int motorBindGrid3DStop  = 16;
	static final int gatherForcesStart = 17;
	static final int gatherForcesStop  = 17;

	// **** Times and Counters
	static private final double deltaT_init = 1e-4; // seconds
	static private final double biochemDeltaT_init = 1e-3; // seconds
	static private final double collisionDeltaT_init = 1e-4; // seconds
	static double simStartTime = 0.0;	// if we need some pre-zero time for sim. to initialize
	static double runBump = 0.001; // a chunk of time to make sure round-off doesn't screw us out of the last JSon write... that format is picky with commas
	static double linkMembraneTime = 0.0;
	static double simulationTime = simStartTime; // keeps track of current time
	static private double runTime_init = 120.0; // final time to run to
	static int counter = 0; // keeps track of current integration step number

	static final Parameter deltaT = new Parameter("deltaT", " Time Step",deltaT_init, "seconds");
	static final Parameter biochemDeltaT = new Parameter("biochemDeltaT"," Time Step For Biochemical Events", biochemDeltaT_init, "seconds");
	static final Parameter collisionDeltaT = new Parameter("collisionDeltaT"," Time Step For Collison Detection", collisionDeltaT_init,"seconds");
	// brownianDeltaT removed (2026-06-11): it was always meant to equal deltaT. Configs
	// that set it to 1e-5 (< deltaT=1e-4) made the CPU Brownian force magnitude sqrt(10)
	// too strong (calcRandomForces scaled by 1/brownianDeltaT while integration uses
	// deltaT — an FDT-breaking mismatch; the GPU kernel already used deltaT). Brownian
	// now always uses deltaT. Stale "brownianDeltaT:..." lines in old param files are
	// harmless (loader logs them as one "misunderstanding" and ignores them).
	static final Parameter runTime = new Parameter("runTime", " Run Time",runTime_init, "seconds");

	// **** "Alberts Force", or Pairwise Agent Interaction with Rational
	// Superposition (PAIRS) related ****
	static double nodeFracMove = 0.5;
	static private double fracMove_init = 0.5; // fraction of the calculated AlbertsForce distance to move in a time-step... < 1 for  stability
	static private double fracR_init = 0.1; // as above, but for torque from link force .. bigger numbers are stiffer / faster response times
	static private double fracMoveTorq_init = 0.265; // as above, but for torsion springs only.. bigger numbers are stiffer faster response times

	static final Parameter fracMove = new Parameter("fracMove",
			" Coeff. for PAIRS movement", fracMove_init, "").setMutableAtRuntime();
	static final Parameter fracR = new Parameter("fracR",
			" Coeff. torque arm for PAIRS movement", fracR_init, "").setMutableAtRuntime();
	static final Parameter fracMoveTorq = new Parameter("fracMoveTorq",
			" Coeff. for PAIRS torque movement", fracMoveTorq_init, "").setMutableAtRuntime();

	static final Parameter myoJ1FracMove = new Parameter("myoJ1FracMove",
			" PAIRS Coeff: myosin lever-motor joint", 0.4, "");
	static final Parameter myoJ1FracR = new Parameter("myoJ1FracR",
			" PAIRS Torque Arm Coeff: myosin lever-motor joint", 0.4, "");
	static final Parameter myoJ1FracMoveTorq = new Parameter(
			"myoJ1FracMoveTorq",
			" PAIRS Torque Coeff: myosin lever-motor joint", 0.4, "");

	static final Parameter myoJ2FracMove = new Parameter("myoJ2FracMove",
			" PAIRS Coeff: myosin rod-lever joint", 0.4, "");
	static final Parameter myoJ2FracR = new Parameter("myoJ2FracR",
			" PAIRS Torque Arm Coeff: myosin rod-lever joint", 0.4, "");
	static final Parameter myoJ2FracMoveTorq = new Parameter(
			"myoJ2FracMoveTorq", " PAIRS Torque Coeff: myosin rod-lever joint",
			0.00, "");

	static final Parameter myoDimerLeverFracMoveTorq = new Parameter(
			"myoDimerLeverFracMoveTorq",
			" PAIRS Torque Coeff: myosin dimer lever-lever joint", 0.4, "");

	static final Parameter myoDimerFracMove = new Parameter("myoDimerFracMove",
			" PAIRS Coeff: myosin dimer rod-rod", 0.2, "");
	static final Parameter myoMiniFilFracMove = new Parameter(
			"myoMiniFilFracMove", " PAIRS Coeff: myosin rod - minifil body",
			0.07, "");
	static final Parameter myoNodeFracMove = new Parameter("myoNodeFracMove",
			" PAIRS Coeff: myosin rod - node body", 0.1, "");

	static final Parameter myoMiniFilAlign = new Parameter("myoMiniFilAlign",
			" PAIRS Coeff: myosin dimer alignment in minifils", 0.01, "");

	static final Parameter myoBrownianAttn = new Parameter("myoBrownianAttn",
			" Attenuation of Brownian forces on Myo parts", 1.0, "");

	// Scale on the minifilament BODY's own Brownian forcing, as a fraction of the
	// free-body value (computed from the body's drag tensor in calcRandomForces).
	// Default 0.1 = 1/10 of a free body of the same size/shape, so the big body
	// does not tumble and fling its attached myosins around. 0.0 = fully off (also
	// reachable via myoMiniFilBrownianMotionOff / BOA_MINIFIL_BROWNIAN_OFF).
	static final Parameter myoMiniFilBrownianScale = new Parameter("myoMiniFilBrownianScale",
			" Scale on minifilament BODY Brownian (fraction of free-body; 0=off)", 0.1, "").setMutableAtRuntime();

	// **** Value Tracking *****
	static final int forcesToTrack = 4;
	static final int nodeetherStrainToAverage = 10;
	static final int filTorqueToAverage = 4;
	static final int filLinkForcesToAve = 5;
	static final int filLinkStrainToAve = 10;
	static final int segSegTorqueToAverage = 4;
	static final int bugNodeCollisionsToSum = 10;
	static final int compressionStepsToTrack = 100;
	static final int segDistToTrack = 10;
	static final int actATetherStrainToAve = 3; // was working with 8?
	static final int actATetherForcesToAve = 3;

	// **** Files and Paths ****
	static String outFileName = null;
	static String logFolderPath = null;
	static String srcFilePath = null;
	static File paramFile = null;
	static String parentFolderPath;


	// **** For Synchronization of Methods ****
	static Object safeO = new Object(); // handle for operations that cannot be performed at the same time

	// **** GPU Acceleration ****
	static boolean useGPU = false; // -gpu flag: route motor-binding through GPUMotorBinding kernel

	// **** Flags and Counters for Graphics ****
	static boolean remote = false; // flag for no graphics instantiation
	static boolean monomerGraphics = false; // one dependent flag for simplicity, set at start of sim.
	static boolean logFiles = false; // write info to files
	static boolean paintOn = true;
	static boolean fullSetToFile = true;
	static boolean toFile = false;
	static volatile boolean paused = true;
	static volatile boolean terminating = false;   // C3: set by kill action; absorbing state
	static boolean viewRotation = false;
	static boolean orderedCentered = false;

	static private final int drawInterval_init = 100;
	static private final int toFileInterval_init = 1000;
	static private final double jpegQuality_init = 1.0;
	static private final int remoteReportInterval_init = (int) 1e4; // time-steps
	static final boolean timeStampJPEGs = true; // write time and other info on
												// JPEG output

	static final Parameter drawInterval = new Parameter("drawInterval"," To Screen Interval", drawInterval_init, "time steps", Parameter.INT);
	// C4: toFileInterval is the only confirmed mid-run mutable parameter.
	// It is a pure counter threshold (threeJSCounter >= toFileInterval) with no cached derivatives.
	static final Parameter toFileInterval = new Parameter("toFileInterval"," Image to File Interval", toFileInterval_init, "time steps", Parameter.INT).setMutableAtRuntime();
	static final Parameter jpegQuality = new Parameter("jpegQuality"," JPEG Quality", jpegQuality_init, " (1.0 is best)");
	static final Parameter remoteReportInterval = new Parameter("remoteWriteInterval", " Remote Reporting Interval",remoteReportInterval_init, "time steps", Parameter.INT);
	static final Parameter rotationPerWrite = new Parameter("rotationPerWrite", " Rotation Per Image Write",0, "degrees", Parameter.DOUBLE);

	
	static String toFilePath;
	static String toFileName;

	// Rendering from QK files
	static boolean filRenderOff = false;
	static boolean myoRenderOff = false;
	static int curRenderNum = 0;

	// **** Flags and Constants for Testing ****
	static boolean brownianFilMotionOff = false;
	static boolean brownianMyoMotionOff = false;
	static boolean nodeBrownianMotionOff = false;
	static boolean bugBrownianMotionOff = false;
	static boolean myoMiniFilBrownianMotionOff = false;
	static boolean myoMiniTeleportDiag = false;       // TELEPORT_DIAG toggle; set true to log large single-step minifilament displacements
	static double  myoMiniTeleportThreshold = 0.1;    // TELEPORT_DIAG µm; displacement per step that triggers a [TELEPORT] dump
	static boolean twoNodesOneFil = false;
	static boolean actinAndMyoMinis = false;
	static boolean twoByTwoNodes = false;
	static final Parameter threeByThreeNodes = new Parameter("threeByThreeNodes", " 3x3 Nodes Test", 0, "", Parameter.BOOLEAN,false);
	static boolean nodeChain = false;
	static final double tstNodeOffset = 0.2;
	static boolean compressionCritOff = true;
	static boolean octopusMode = false;
	// Initial-condition selectors (promoted 2026-06-15 from the compile-time test flags xLinkTesting/
	// nodeLinkTesting to runtime params — set in a param file, no recompile, no cross-run contamination).
	// buildMembraneSheet -> hex StickyNode sheet + hot-Rho NPF patch (makeSheetHexPackedNodes).
	// buildBranchedFils  -> Arp2/3 branched filaments: WITH a sheet = membrane-branched mothers
	//                       (lamellipodium, makeMembraneBranchedMothers); ALONE = free-space branched
	//                       filament / junction-relaxation diagnostic (makeTestBranchedFilament).
	// Both default OFF, so production/gliding/contractility runs are unaffected.
	static final Parameter buildMembraneSheet = new Parameter("buildMembraneSheet", " Build membrane StickyNode sheet IC", 0, "", Parameter.BOOLEAN, false);
	static final Parameter buildBranchedFils  = new Parameter("buildBranchedFils", " Build Arp2/3 branched-filament IC", 0, "", Parameter.BOOLEAN, false);
	// Closed spherical membrane IC (makeSphereOfNodes) with a few hot-Rho (NPF) patches, for testing the
	// activated-Arp2/3 field on a curved/closed surface. Both default OFF.
	static final Parameter buildMembraneSphere = new Parameter("buildMembraneSphere", " Build spherical membrane IC", 0, "", Parameter.BOOLEAN, false);
	static final Parameter sphereHotPatches = new Parameter("sphereHotPatches", " Number of hot Rac1/Cdc42 (NPF) patches on the sphere", 3, "", Parameter.INT);
	static final Parameter sphereHotPatchDeg = new Parameter("sphereHotPatchDeg", " Angular radius of each hot Rac1 patch", 20.0, "degrees");
	static boolean myosinsOff = false;
	static boolean randomNodesOn = false;
	static final Parameter fixedMyosinClusters = new Parameter("fixedMyosinClusters", " Fixed Myosin Cluster Test", 0, "", Parameter.BOOLEAN, false);
	static final Parameter glidingAssay = new Parameter("glidingAssay"," Filament Gliding Assay", 0, "", Parameter.BOOLEAN, false);
	static final Parameter externalDensitySweep = new Parameter("externalDensitySweep", " External density sweep (disables glidingAssayDataSetRun)", 0, "", Parameter.BOOLEAN, false);
	static final Parameter nodeGrowthPerStep = new Parameter("nodeGrowthPerStep"," Node Growth (testing)", 0.001, "microns", Parameter.DOUBLE);

	// Minimal contractility assay — two anti-parallel stiff filaments pinned at their plus ends
	// to the outer box walls, one minifilament in the central overlap; measures the isometric
	// contractile tension at each anchor. See BoxOfActin.makeContractilityAssay(). CPU-only.
	static final Parameter contractilityAssay = new Parameter("contractilityAssay"," Minimal contractility assay", 0, "", Parameter.BOOLEAN, false);
	static final Parameter contractNoMotor = new Parameter("contractNoMotor"," Contractility control: omit the minifilament (no motor)", 0, "", Parameter.BOOLEAN, false);
	static final Parameter contractReversePolarity = new Parameter("contractReversePolarity"," Contractility control: flip filament polarity (plus ends inward -> extension)", 0, "", Parameter.BOOLEAN, false);
	static final Parameter contractFilNSegs = new Parameter("contractFilNSegs"," Contractility: segments per filament", 13, " ", Parameter.INT);
	static final Parameter contractFilYOffset = new Parameter("contractFilYOffset"," Contractility: filament +/- Y offset from minifilament axis", 0.05, "microns", Parameter.DOUBLE);

	// ── Node-based contractility assay (the node analog of the minifilament assay above) ──
	// Same two anti-parallel pinned filaments and the same pin/tension/stats readout, but the
	// load source is protein node(s) carrying surface myosins (numNodeMyos / numNodeMyoDimers)
	// instead of a bipolar minifilament. See BoxOfActin.makeNodeContractilityAssay(). Reuses the
	// contractNoMotor / contractReversePolarity / contractFilNSegs / contractFilYOffset controls.
	static final Parameter nodeContractilityAssay = new Parameter("nodeContractilityAssay"," Node-based contractility assay (myosins on protein nodes)", 0, "", Parameter.BOOLEAN, false);
	static final Parameter contractNodeRadius = new Parameter("contractNodeRadius"," Node contractility: carrier-node sphere radius", 0.1, "microns", Parameter.DOUBLE);
	static final Parameter contractNodeCount = new Parameter("contractNodeCount"," Node contractility: number of carrier nodes (1 bridging sphere default, 2 allowed)", 1, " ", Parameter.INT);
	static final Parameter contractNodeYOffset = new Parameter("contractNodeYOffset"," Node contractility: +/- Y placement of carrier nodes (2-node config only)", 0.05, "microns", Parameter.DOUBLE);

	// F1 static-deflection benchmark — gated by Env.benchmarkFilament (set by -bm flag)
	static boolean benchmarkFilament = false;
	static int benchmarkNSegs = 11;       // odd → midpoint segment is exactly at midspan
	static final Parameter benchmarkForceFrac = new Parameter("benchmarkForceFrac",
			" Benchmark force fraction of span", 0.01, "").setMutableAtRuntime();
	static int benchmarkSettleSteps = 5000;  // steps before first measurement
	static int benchmarkMonomerCt = 0;       // 0 = use stdSegLength; nonzero overrides for -bm runs (-bmMonomer flag)
	static boolean benchmarkDiag = false;    // -bmDiag: fixed-param equilibrium diagnostic, no search
	static boolean benchmarkManual = false;  // -bmManual: no search loop; user tunes live from viewer
	static boolean benchmarkTunerV15 = false; // -bmTunerV15: use DeflectionTunerV15 instead of v14
	static boolean benchmarkTunerV16 = false; // -bmTunerV16: use DeflectionTunerV16 (bracket-and-overshoot)
	static boolean benchmarkTunerV17 = false; // -bmTunerV17: use DeflectionTunerV17 (single-parameter overshoot)
	static boolean benchmarkTunerV18 = false; // -bmTunerV18: use DeflectionTunerV18 (empirical-sensitivity overshoot)
	static boolean benchmarkTunerV19 = false; // -bmTunerV19: use DeflectionTunerV19 (fast-path for far-from-target)
	static boolean benchmarkTunerV20 = false; // -bmTunerV20: use DeflectionTunerV20 (sensitivity tracking + velocity-trend gating)
	static boolean benchmarkTunerV21 = false; // -bmTunerV21: use DeflectionTunerV21 (bounded sens + single-param near-target)
	static boolean benchmarkTunerV22 = false; // -bmTunerV22: use DeflectionTunerV22 (Broyden's method, clean-slate 2D root finding)
	static boolean benchmarkTunerV23 = false; // -bmTunerV23: use DeflectionTunerV23 (corrected bounds, stiffest start, fracMove outer loop)
	static boolean benchmarkTunerV24 = false; // -bmTunerV24: use DeflectionTunerV24 (noise-aware Jacobian gate, physics-aware conv tolerance)
	static boolean benchmarkTunerV25 = false; // -bmTunerV25: use DeflectionTunerV25 (coarse fracMove pre-pass + V24 convergence)
	static boolean benchmarkNoiseProbe = false; // -bmNoiseProbe: post-convergence tail capture for V_NOISE/A_NOISE calibration

	// Increment 4: force toggle — mutable at runtime so the Params panel and HUD button both work.
	// Not shown in the Params panel UI (filtered client-side); the HUD Force button is the only control.
	static final Parameter benchmarkForceOn = new Parameter("benchmarkForceOn",
			" Benchmark: apply midpoint force", 1.0, "", Parameter.BOOLEAN).setMutableAtRuntime();

	// LP benchmark: EWMA smoothing factor for the tangent-tangent correlation accumulator.
	// α ∈ (0,1]; effective window ≈ 1/α output frames. Mutable at runtime.
	static final Parameter lpEwmaAlpha = new Parameter("lpEwmaAlpha",
			" LP benchmark EWMA alpha", 0.001, "").setMutableAtRuntime();

	// LP benchmark: active flag. When 0, LP segments are skipped in step/move/Brownian and
	// accumulation halts; when transitioning 0→1, accumulator resets for a clean re-start.
	static final Parameter lpActive = new Parameter("lpActive",
			" LP benchmark active", 1.0, "", Parameter.BOOLEAN).setMutableAtRuntime();
	
	// LP benchmark filament length
	static final double testLpFilLength = 48; // (microns) length of the test filament used for observing effective Lp in simulation
	
	// **** Graphics Sizes Etc ****
	static private final int frameWidth_init = 800;
	static private final int frameHeight_init = 800;

	static final Parameter frameWidth = new Parameter("frameWidth"," Frame Width", frameWidth_init, "Pixels", Parameter.INT);
	static final Parameter frameHeight = new Parameter("frameHeight"," Frame Height", frameHeight_init, "Pixels", Parameter.INT);

	static final Parameter showCrucible = new Parameter("showCrucible", " Show Box/Bug Arena", 0, " ",Parameter.BOOLEAN, false);  // turns off painting 
	static final Parameter simOutsideBug = new Parameter("simOutsideBug", " Sim. Outside Bug", 1, " ",Parameter.BOOLEAN, false);


	static final int nodeTessalation = 24;
	static final int actinEndSphereTessalation = 10;
	static final int monomerTessalation = 8;

	static boolean helixSpheres = true;
	static boolean polarityArrows = false;
	static boolean filCoordSysOn = false;
	static boolean bugCoordSysOn = false;
	static boolean bugWired = true;
	static boolean bugWiredCoarse = true;
	static boolean infoIn2D = true;
	static boolean infoIn3D = false;
	static final Parameter showXLinks = new Parameter("showXLinks"," Render Crosslinks", 0, " ", Parameter.BOOLEAN,false);
	static final Parameter showActAs = new Parameter("showActAs"," Render ActAs", 0, " ", Parameter.BOOLEAN,false);


	static final Parameter transScale = new Parameter("transScale"," Scale for 3D rendering", 2, " ", Parameter.DOUBLE);
	static final Parameter filRenderThicken = new Parameter("filRenderThicken"," Scale radius of filament cylinders by:", 1, "x");
	static final Parameter lockView = new Parameter("lockView"," Lock View During Sim.", 0, " ", Parameter.BOOLEAN,false);

	static final int maxLayout = 35;

	// **** Initial Thing Creation ****
	static private final int initialFilaments_init = 0;
	static private final int initialNodes_init = 0;
	static private final int initialMyoMiniFils_init = 0;
	static private final int numMyoDimersEachEndOfMiniFil_init = 8;

	static final Parameter initialFilaments = new Parameter("initialFilaments"," Initial Actin Filaments", initialFilaments_init, " ",Parameter.INT);
	static final Parameter initialNodes = new Parameter("initialNodes"," Initial Protein Nodes", initialNodes_init, " ", Parameter.INT);
	static final Parameter equilNodes = new Parameter("equilNodes"," Equil. # of Protein Nodes", 0, " ", Parameter.INT);
	static final Parameter initialMyoMiniFils = new Parameter("initialMyoMiniFils", " Initial Myosin Minifilaments",initialMyoMiniFils_init, " ", Parameter.INT);
	static final Parameter numMyoDimersEachEndOfMiniFil = new Parameter("numMyoDimersEachEndOfMiniFil"," Number of Myosin Dimers At Each End of Minifilaments",numMyoDimersEachEndOfMiniFil_init, " ", Parameter.INT);
	static final Parameter numChamberFixedMyos = new Parameter("numChamberFixedMyos", " Number of Myosins Fixed to Chamber", 0," ", Parameter.INT);
	static final Parameter numChamberFixedMyoDimers = new Parameter("numChamberFixedMyoDimers"," Number of Myosin Dimers Fixed to Chamber", 0, " ", Parameter.INT);
	static final Parameter numHotSpotsOnCortex = new Parameter("numHotSpotsOnCortex"," Number of Hot Spots On Cortex", 0, " ", Parameter.INT);
	static final Parameter numOffCenterHotSpotRows = new Parameter("numOffCenterHotSpotRows"," Number of Off-Center Hot Spot Rows On Cortex", 0, " ", Parameter.INT);
	static final Parameter hotSpotRowSpacing = new Parameter("hotSpotRowSpacing"," Spacing of Hot Spot Rows", 1.0, "microns", Parameter.DOUBLE);
	
	static final Parameter westCircleFils = new Parameter("westCircleFils"," Initial West Circle Filaments", 0, "", Parameter.INT);
	static final Parameter eastCircleFils = new Parameter("eastCircleFils"," Initial East Circle Filaments", 0, "", Parameter.INT);
	static final Parameter circleFilsMinLength = new Parameter("circleFilsMinLength", " Circle Filaments Min. Length", .1,distUnits, Parameter.DOUBLE);
	static final Parameter circleFilsMaxLength = new Parameter("circleFilsMaxLength", " Circle Filaments Max. Length", .2,distUnits, Parameter.DOUBLE);
	static final Parameter circleFilsMixedPolarity = new Parameter("circleFilsMixedPolarity", " Circle Filaments Mixed Polarity", 0,"", Parameter.BOOLEAN, false);

	static final Parameter numNodeMyos = new Parameter("numNodeMyos"," Number of Myosins on each Protein Node", 0, " ", Parameter.INT);
	static final Parameter numNodeMyoDimers = new Parameter("numNodeMyoDimers"," Number of Myosin Dimers on each Protein Node", 0, " ",Parameter.INT);	
	
	static final Parameter minFilLength = new Parameter("minFilLength"," Min. Filament Length", 0.2, distUnits);
	static final Parameter maxFilLength = new Parameter("maxFilLength"," Max. Filament Length", 1.5, distUnits);

	static final Parameter stdDevActinDist = new Parameter("stdDevActinDist", " Std Dev Actin Distribution", 0.5, " ");

	// **** The Box and Bug ****
	static private final double boxXDim_init = 2; // (microns)
	static private final double boxYDim_init = 2; // (microns)
	static private final double boxZDim_init = 0.1; // (microns)
	static private final double bugLength_init = 3;// 10.0; // (microns) length from tip to tip
	static private final double bugRadius_init = 0.5;// 1.64; // (microns) radius of cylinder and hemispherical caps
	static private final double aeta_init = 0.1; // viscosity in bug.... Pa-s (1e-3 is water)
	static private final double actinConc_init = 15; // �M concentration of actin monomers
	static private final double actinConcNonHydro_init = 15; // �M concentration of non-hydrolyzable actin monomers
	static private final double capConc_init = 2.0; // �M concentration of capping protein

	static final Parameter boxXDim = new Parameter("boxXDim"," Box X-dimension", boxXDim_init, distUnits);
	static final Parameter boxYDim = new Parameter("boxYDim"," Box Y-dimension", boxYDim_init, distUnits);
	static final Parameter boxZDim = new Parameter("boxZDim"," Box Z-dimension", boxZDim_init, distUnits);
	
	static final Parameter boxSpawnFraction = new Parameter("boxSpawnFraction"," Fraction of Box Used for Spawning Things", 1," ");

	static final Parameter bugShapedCrucible = new Parameter("bugShapedCrucible", " Make A Bug-Shaped Crucible",0, "", Parameter.BOOLEAN, false);
	static final Parameter bugLength = new Parameter("bugLength"," Bug Length", bugLength_init, distUnits);
	static final Parameter bugRadius = new Parameter("bugRadius"," Bug Radius", bugRadius_init, distUnits);
	static final Parameter bugFrictionCoeff = new Parameter("bugFrictionCoeff"," Friction coeff. for bug surface", 0.2,"",Parameter.DOUBLE);

	static final double actinCritConc = 0.12; // µM critical concentration (Pollard JCB 1986)
	static final Parameter actinConc = new Parameter("actinConc"," Actin Concentration", actinConc_init, concUnits,Parameter.DOUBLE, Parameter.CHANGE_VALUE);
	static final Parameter actinConcNonHydro = new Parameter("actinConcNonHydro", " Non-hydrolyzable Actin Concentration",actinConcNonHydro_init, concUnits, Parameter.DOUBLE,Parameter.CHANGE_VALUE);
	static final Parameter actinConcX = new Parameter("actinConcX"," Actin Concentration XFactor", 1.0, " ", Parameter.DOUBLE);
	static final Parameter aeta = new Parameter("aeta", " Viscosity in Bug",aeta_init, "Pa-s (1e-3 is water)").setMutableAtRuntime();
	static final Parameter capConc = new Parameter("capConc"," Cap Concentration", capConc_init, concUnits, Parameter.DOUBLE);

	// **** The Protein Nodes ****
	static final Parameter nodeZone = new Parameter("nodeZone"," Node Dist. Zone", 0.9, distUnits);

	static private final double nodeRadius_init = 0.05; // microns
	static final Parameter nodeRadius = new Parameter("nodeRadius"," Node Radius", nodeRadius_init, distUnits);

	static private final int forminsPerNode_init = 0; // # of filaments a node can nucleate
	static final Parameter forminsPerNode = new Parameter("forminsPerNode"," Formins per node", forminsPerNode_init, "", Parameter.INT);

	static private final double nodeTransDiff_init = Boltz*tempK / (6*Math.PI*aeta.getValue()*(1.0e-6*nodeRadius.getValue()));
	static final Parameter nodeTransDiff = new Parameter("nodeTransDiff"," Protein Node Translation Diffusivity", nodeTransDiff_init,"m^2/s", Parameter.DOUBLE, false);

	static private final double nodeRotDiff_init = Boltz*tempK / (8*Math.PI*aeta.getValue()*(1.0e-6*nodeRadius.getValue())*(1.0e-6*nodeRadius.getValue())*(1.0e-6*nodeRadius.getValue()));
	static final Parameter nodeRotDiff = new Parameter("nodeRotDiff"," Protein Node Rotational Diffusivity", nodeRotDiff_init, "/s",Parameter.DOUBLE, false);
	
	static final Parameter showProteinNode = new Parameter("showProteinNode"," Draw Protein Node?", 0, "  ",Parameter.BOOLEAN, true);
	static final Parameter collideProteinNodes = new Parameter("collideProteinNodes"," Do Protein Nodes Collide?", 0, "  ",Parameter.BOOLEAN, true);

	//**** LISTERIA PARAMETERS ****
	static final double listeriaLength = 1.7;	// 1.7 for most, or 2.26 for bipolar, 1.51 for cartoon distributions
	static final double listeriaCylDiam = 0.7;		// diameter of Listeria cylinder, �m.
	static final double listeriaCylLength = listeriaLength - listeriaCylDiam; 	// length of cylindrical part of listeria
	static final double listeriaCapRad = listeriaCylDiam/2;		// radius of spherical Listeria cap, �m.
	static final double capSurfaceArea = 2.0*Math.PI*(listeriaCapRad*listeriaCapRad); // surface area of one spherical cap (ie (4pi()r^2)/2)
	static final double cylSurfaceArea = 2.0*Math.PI*(listeriaCylDiam/2.0)*listeriaCylLength; // surface area of cylinder
	static final Parameter linearDragSlope = new Parameter("linearDragSlope"," Linear multiplier of close tips for bug drag scale", 1, "dragScale=baseDragScale + this*closeTips",Parameter.DOUBLE);  // 
	static final Parameter bugBaseDragScale = new Parameter("bugBaseDragScale"," Base Drag Scale for bug", 10, "",Parameter.DOUBLE);  // 
	static final Parameter quadraticDragSlope = new Parameter("quadraticDragSlope"," Quadratic factor of close tips for bug drag scale", 1, "dragScale=baseDragScale + this*(closetips)^2",Parameter.DOUBLE);  // 
	static final Parameter maxBugDragScale = new Parameter("maxBugDragScale"," Max. Drag Scale for bug", 10, "",Parameter.DOUBLE);
	static final double viscShellDist = 0.05; // microns
	static final Parameter useAveBugDragScale = new Parameter("useAveBugDragScale"," Use average (as opposed to instantaneous) bug drag", 0, "",Parameter.BOOLEAN, true);

	
	//*** ACTA Behavior Params ***
	static final Parameter actASpringK = new Parameter("actASpringK"," ActA-Filament Spring Constant", 1e-6, "N/m",Parameter.DOUBLE);  //  if using a simple spring here
	static final Parameter actAMaxTetherStrain = new Parameter("actAMaxTetherStrain"," ActA-Filament Tether Max. Strain", 1.05, "",Parameter.DOUBLE);  //  in RocketBugs current this is 1.05
	static final Parameter actATetherFracMove = new Parameter("actATetherFracMove"," Scaling of Tether Force From One-step Resolution", 1.0, "",Parameter.DOUBLE);
	static final Parameter actABranchProb = new Parameter("actABranchProb"," ActA Branching Probability", 0.2, "/s",Parameter.DOUBLE);  // (/s) in RocketBugs this is the Arp2/3 binding probability for ActA, value is 0.177 there
	static final Parameter actANucProb = new Parameter("actANucProb"," ActA Nucleation Probability", 0.0013, "/µM-s",Parameter.DOUBLE);  // (/s) in RocketBugs current runs this appears to have the value of 0.0013
	static final Parameter actADetachProb = new Parameter("actADetachProb"," ActA-Filament Baseline Detach Probability", 0.8, "/s",Parameter.DOUBLE);  // (/s) in RocketBugs current runs this has the value of 0.8
	static final Parameter actAUncapDistance = new Parameter("actAUncapDistance"," ActA will uncap filament if bound this close to plus-end", 0.02, "µm",Parameter.DOUBLE); 
	static final Parameter closeActATolerance = new Parameter("closeActATolerance"," Adjust ActA Binding Zone Distance for Colliding Filaments", 0.01, "µm",Parameter.DOUBLE);  // (microns) distance in each coordinate direction allowable for binding
	static final Parameter actATetherTransAttn = new Parameter("actATetherTransAttn"," Attn. of Translational Brownian Forces if ActA Bound", 0.02, "",Parameter.DOUBLE);  // in RocketBugs this isn't a constant but a function
	static final Parameter actATetherRotAttn = new Parameter("actATetherRotAttn"," Attn. of Rotational Brownian Forces if ActA Bound", 0.02, "",Parameter.DOUBLE);  // in RocketBugs this isn't a constant but a function
	static final Parameter checkActABindingProb = new Parameter("checkActABindingProb"," With collision, prob. of checking for binding to nearby ActA", 0.1, "",Parameter.DOUBLE);  
	static final Parameter contactUncapsProb = new Parameter("contactUncapsProb"," With collision, prob. of uncapping barbed-end", 0.1, "",Parameter.DOUBLE);  


	//*** ACTA Distributions
	static final double actADensity = 6.4e17;	// (#/m^2) assuming 1e6 ActA on 0.5�m diameter latex bead (from J. Theriot)
	static final double equivCylArea = 0.65;			// length of cylinder that would be covered by ActA if it were distributed with same density as on hemispherical cap
	static final double capActAArea = 2.0*Math.PI*(listeriaCapRad*listeriaCapRad);		// area of ActA distribution on cap
	static final double cylActAArea = 2.0*Math.PI*(listeriaCylDiam/2.0)*equivCylArea;	// area of ActA distribution on cylinder
	static final boolean actAFromHist = true; //use the uncommented histogram below for ActA distribution
	static final Parameter totalActACt = new Parameter("totalActACt"," Number of ActAs on Bug", 10000, "", Parameter.INT);
	static final Parameter ultrapolarActA = new Parameter("ultrapolarActA"," Ultrapolar Distribution of ActA on Bug", 0, " ", Parameter.BOOLEAN, false);

	//31710	*** normal	... length is 1.7, numActAs is 10K
	static final double [] nmActAMeasure = new double [] {0.038,0.064,0.084,0.093,0.091,0.090,0.086,0.080,0.080,0.074,0.064,0.057,0.044,0.032,0.018,0.006,0.000};
	static final double [] nmActAProb = new double [] {0.854,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,0.726,0.403,0.142,0.000};
	//31904	*** ultapolar... length is 1.7, change numActAs to 7890
	static final double [] upActAMeasure = new double [] {0.074,0.109,0.119,0.108,0.092,0.077,0.067,0.061,0.058,0.053,0.050,0.044,0.037,0.029,0.017,0.007,0.000};
	static final double [] upActAProb = new double [] {1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,0.946,0.888,0.787,0.660,0.519,0.302,0.127,0.000};
	static final double ActA_ArpGrabDist = .5*radOfARP;		// how far away can ActA_Arp2/3 complex "grab" a filament  
	static final double equilActAAdjust = 1;//1/1.367;	//ultrapolarActA fudge factor to adjust ActA amounts in "Listeria"
	// *** Symmetric bead
	static final boolean playingWithSymBead = false;
	static final double [] beadActAMeasure = new double [] {0.026836158,0.04519774,0.059322034,0.063559322,0.064971751,0.066384181,0.06779661,0.070621469,0.070621469,0.070621469,0.06779661,0.066384181,0.064971751,0.063559322,0.059322034,0.04519774,0.026836158};
	static final double [] beadActAProb = new double [] {0.854,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1.000,1,1,1,0.854};

	// **** DTS membrane (v2 — dynamically-triangulated surface; see MEMBRANE_DTS_DESIGN.md) ****
	// New subsystem, independent of the legacy spring/vesicle membrane below. Stage 1 = geometry + render.
	static final Parameter buildDtsMembrane = new Parameter("buildDtsMembrane"," Build DTS (dynamically-triangulated) membrane IC", 0, "", Parameter.BOOLEAN, false)
		.setDescription("When on, builds a v2 dynamically-triangulated-surface membrane: an icosphere of lightweight vertex Things with flat-SoA topology (faces + wing-edges + fixed-width vertex incidence), device/ECS-portable. Stage 1 builds geometry + render only -- no bending/area/volume forces yet. Independent of the legacy spring/vesicle membrane (buildMembraneSphere/Sheet). Vertices use PHYSICAL drag (no membraneNodeDragScale).");
	static final Parameter dtsMembraneSubdiv = new Parameter("dtsMembraneSubdiv"," DTS membrane icosphere subdivision (nu)", 4, "", Parameter.INT)
		.setDescription("Icosphere subdivision level: nv = 10*4^nu + 2 vertices, nf = 20*4^nu faces. nu=2 -> 162 v, nu=3 -> 642 v, nu=4 -> 2562 v (~50-80 nm patches on a ~1.2 um cell), nu=5 -> 10242 v. Cost grows 4x per level.");
	static final Parameter dtsMembraneRadius = new Parameter("dtsMembraneRadius"," DTS membrane radius", 1.0, "microns")
		.setDescription("Radius of the initial DTS icosphere membrane, in microns.");
	static final Parameter dtsVertexRadiusFrac = new Parameter("dtsVertexRadiusFrac"," DTS vertex radius (fraction of edge length)", 0.5, "")
		.setDescription("Per-vertex steric/drag radius as a fraction of the mean initial edge length l0. 0.5 (~half the vertex spacing) is the design default; sets Stokes drag gamma = 6*pi*eta*r_v and the steric size of each vertex.");
	static final Parameter dtsKappaBend = new Parameter("dtsKappaBend"," DTS bending rigidity kappa", 1.0e-19, "Joules")
		.setMutableAtRuntime()
		.setDescription("Helfrich bending rigidity (Julicher/TriMem edge form: E = kappa * Sum_v 2 c_v^2/A_v). Lipid bilayer ~20-25 kBT ~ 0.8-1.0e-19 J. 0 disables bending. Cortex stiffness comes from actin, not kappa.");
	static final Parameter dtsKappaArea = new Parameter("dtsKappaArea"," DTS area-stretch modulus K_A", 0.0, "N/m")
		.setMutableAtRuntime()
		.setDescription("Area-compressibility modulus in the harmonic area constraint E_area = (K_A/2)(A-A0)^2/A0. Lipid bilayer ~0.2 N/m (near-inextensible -- may need softening for dt=1e-5 stability). 0 = off (free area). A0 is the icosphere IC area.");
	static final Parameter dtsKappaVolume = new Parameter("dtsKappaVolume"," DTS volume-constraint modulus K_V", 0.0, "Joules")
		.setMutableAtRuntime()
		.setDescription("Volume-constraint modulus in E_vol = (K_V/2)(V/V0 - vt)^2 (vesicle/turgor). Larger = stiffer (near-incompressible cytoplasm). 0 = off (free volume). V0 is the icosphere IC volume.");
	static final Parameter dtsTargetReducedVol = new Parameter("dtsTargetReducedVol"," DTS target reduced volume vt", 1.0, "")
		.setMutableAtRuntime()
		.setDescription("Target for the volume constraint, as a fraction of the IC volume V0 (vt=1 holds the IC sphere; vt<1 deflates -> oblate/stomatocyte shapes, the standard DTS reduced-volume validation).");
	static final Parameter dtsPushForce = new Parameter("dtsPushForce"," DTS membrane push-patch total force (+x)", 0.0, "Newtons")
		.setMutableAtRuntime()
		.setDescription("Total constant outward (+x) force spread over a cap of membrane vertices near the +x pole -- a localized protrusion drive (a clean stand-in for actin pushing a bulge). The bulge grows and STALLS when the bending+area+volume reaction balances the push: the signature membrane mechanical response. 0 = off. ~1e-10 to 1e-9 N. Soften dtsKappaArea/dtsKappaVolume for a bigger bulge.");
	static final Parameter dtsPushCapDeg = new Parameter("dtsPushCapDeg"," DTS push-patch cap half-angle", 25.0, "degrees")
		.setMutableAtRuntime()
		.setDescription("Half-angle of the +x polar cap that the push force is spread over. Smaller = sharper, more localized protrusion; larger = a broad dome.");
	static final Parameter dtsProbeForce = new Parameter("dtsProbeForce"," DTS membrane probe constant drive force (+x)", 0.0, "Newtons")
		.setMutableAtRuntime()
		.setDescription("Constant outward (+x) force on a single probe node placed inside the DTS membrane (no actin). It pushes a bulge into the bilayer and STALLS when the bending+area+volume reaction balances the drive -- a clean visual demonstration of the membrane's mechanics. 0 = no probe. ~2e-11 to 1e-10 N.");
	static final Parameter dtsProbeRadius = new Parameter("dtsProbeRadius"," DTS membrane probe radius", 0.25, "microns")
		.setDescription("Radius of the constant-force probe sphere (steric contact with membrane vertices = probeRadius + vertexRadius).");
	static final Parameter dtsProbeStartX = new Parameter("dtsProbeStartX"," DTS membrane probe start x", 0.0, "microns")
		.setDescription("Initial x of the probe (0 = center). It is driven +x from here into the membrane wall.");
	static final Parameter dtsBouncerCount = new Parameter("dtsBouncerCount"," DTS bouncer node count", 0, "", Parameter.INT)
		.setDescription("Number of free nodes that shoot around INSIDE the membrane, ricocheting off it (pushing transient bulges) and randomly changing direction. A live demo of membrane response + relaxation. 0 = none.");
	static final Parameter dtsBouncerForce = new Parameter("dtsBouncerForce"," DTS bouncer drive force", 2.0e-10, "Newtons")
		.setMutableAtRuntime()
		.setDescription("Drive-force magnitude on each bouncer: sets its speed (v=F/gamma) and how hard it bulges the membrane on impact. Raise for faster, harder hits.");
	static final Parameter dtsBouncerTurnProb = new Parameter("dtsBouncerTurnProb"," DTS bouncer random-turn probability/step", 0.004, "")
		.setMutableAtRuntime()
		.setDescription("Per-step probability a bouncer re-aims in a new random direction (in addition to bouncing off the membrane). Higher = more erratic, jittery motion; lower = long straight runs between wall hits.");
	static final Parameter dtsBouncerMinR = new Parameter("dtsBouncerMinR"," DTS bouncer min radius", 0.12, "microns")
		.setDescription("Smallest bouncer radius (each bouncer gets a random radius in [min,max] -- nodes of different sizes).");
	static final Parameter dtsBouncerMaxR = new Parameter("dtsBouncerMaxR"," DTS bouncer max radius", 0.35, "microns")
		.setDescription("Largest bouncer radius. Bigger bouncers contact more triangles and push broader bulges.");
	static final Parameter dtsStericRecover = new Parameter("dtsStericRecover"," DTS filament steric hard-recovery stiffness", 0.2, "N/m")
		.setMutableAtRuntime()
		.setDescription("Stiff inward spring applied to any actin/filament sample that has crossed to the OUTSIDE of the membrane (one-sided), yanking it back in. Guarantees containment of thin filaments under strong outward drive, on top of the soft drag-coupled engagement. Only fires on a crossed sample.");
	static final Parameter dtsStericStiffness = new Parameter("dtsStericStiffness"," DTS probe/bouncer steric spring stiffness", 8.0e-4, "N/m per face")
		.setMutableAtRuntime()
		.setDescription("Stiffness of the soft penetration spring between a probe/bouncer node and each contacting membrane face. Soft enough that the node DWELLS in contact and transmits a sustained push (so the membrane bulges) rather than being ejected in one step. Too stiff (> ~gamma/dt summed over contacts) -> bouncy rigid wall, no bulge; too soft -> the node sinks through. ~8e-4 works for vertexRadius~0.05 um at dt=1e-5.");
	static final Parameter dtsMaxDispFrac = new Parameter("dtsMaxDispFrac"," DTS vertex max per-step move (frac of vertex radius)", 0.0, "")
		.setMutableAtRuntime()
		.setDescription("0 = off. >0 caps a DTS membrane vertex's per-step translation to this * vertexRadius. Safety net for stiff area/volume constraints and large transients (e.g. a reduced-volume target mismatch) under explicit Euler -- the vertex moves slowly instead of taking one huge step and exploding. ~0.1-0.25 is reasonable.");
	// **** DTS membrane surface chemistry — activated Arp2/3 field (diffuses over the wing-edge graph) ****
	static final Parameter dtsArpOn = new Parameter("dtsArpOn"," DTS membrane activated-Arp2/3 field", 0, "", Parameter.BOOLEAN, false)
		.setDescription("When on, a per-vertex activated-Arp2/3 concentration is produced at NPF 'hot' patches and DIFFUSES across the membrane over the wing-edge graph (reaction-diffusion), reaching a halo around each patch. The substrate for membrane-localized branched nucleation (later). Render the heat-map with the viewer 'DTS Arp heatmap' toggle.");
	static final Parameter dtsArpTarget = new Parameter("dtsArpTarget"," DTS Arp2/3 production target at hot patches", 1.0, "uM")
		.setMutableAtRuntime()
		.setDescription("Concentration the NPF (hot-Rho) patches drive the local activated-Arp2/3 field toward. Sets the heat-map peak.");
	static final Parameter dtsArpDiffusion = new Parameter("dtsArpDiffusion"," DTS Arp2/3 graph diffusion (per step, per edge)", 0.1, "")
		.setMutableAtRuntime()
		.setDescription("Per-step graph-Laplacian diffusion coefficient over the wing-edges: c_i += alpha*Sum_neighbors(c_j-c_i). Stability needs alpha*valence < ~1 (valence ~6), so keep <= ~0.15. Larger = the field spreads farther/faster from the patches.");
	static final Parameter dtsArpDecay = new Parameter("dtsArpDecay"," DTS Arp2/3 decay / bulk-exchange (per step)", 0.002, "")
		.setMutableAtRuntime()
		.setDescription("Per-step loss rate (deactivation / escape to bulk) applied everywhere; the same rate drives production at hot patches. Steady-state halo length ~ sqrt(diffusion/decay) edges. Smaller decay = broader halo, slower to settle.");
	static final Parameter dtsArpHotPatches = new Parameter("dtsArpHotPatches"," DTS Arp2/3 NPF hot-patch count", 6, "", Parameter.INT)
		.setDescription("Number of NPF (hot-Rho) activator patches, placed on cube-corner directions (off coordinate singularities). 0..8.");
	static final Parameter dtsArpHotPatchDeg = new Parameter("dtsArpHotPatchDeg"," DTS Arp2/3 hot-patch cap half-angle", 20.0, "degrees")
		.setDescription("Angular radius of each NPF patch on the sphere. Larger = broader source zones.");

	// **** DTS membrane formin nucleation — depletable per-vertex pool seeds mother filaments at hot patches ****
	static final Parameter dtsForminOn = new Parameter("dtsForminOn"," DTS membrane formin nucleation", 0, "", Parameter.BOOLEAN, false)
		.setDescription("When on, NPF (hot-Rho) membrane vertices carry a depletable formin pool and nucleate LINEAR mother filaments just inside the cortex, anchored to the vertex (ERM-like end1 tether). Turns real actin on. Arp2/3 then branches off these mothers (later). Needs the hot patches (shared with the Arp field).");
	static final Parameter dtsForminPool = new Parameter("dtsForminPool"," DTS formin pool per hot vertex", 1.0, "")
		.setDescription("Formin quanta available at each NPF vertex. Each mother spends dtsForminConsume, so a vertex makes at most ~pool/consume mothers (the hard cap, unless dtsForminRecover>0).");
	static final Parameter dtsForminConsume = new Parameter("dtsForminConsume"," DTS formin spent per mother", 1.0, "")
		.setMutableAtRuntime()
		.setDescription("Formin quanta consumed from a vertex's pool per nucleated mother filament. Caps mothers per zone at ~pool/consume.");
	static final Parameter dtsForminNucRate = new Parameter("dtsForminNucRate"," DTS formin nucleation rate", 8.0, "1/s")
		.setMutableAtRuntime()
		.setDescription("Mother-nucleation rate at a full-pool hot vertex (scaled by pool fraction). Per-step prob = rate*(forminLocal/pool)*deltaT. Higher = a denser actin brush sprouts from the NPF patches.");
	static final Parameter dtsBranchOn = new Parameter("dtsBranchOn"," DTS Arp2/3 branch nucleation off membrane-proximal filaments", 0, "", Parameter.BOOLEAN, false)
		.setMutableAtRuntime()
		.setDescription("When on, a filament whose barbed tip is near the cortex in a high-Arp region branches (Arp2/3): a daughter nucleates at the Arp angle, tilted toward the membrane (the dendritic protrusive geometry). Branch rate is proportional to the LOCAL activated-Arp field, and each branch consumes Arp (negative feedback) -> a real branched network localized to the NPF zones.");
	static final Parameter dtsBranchRate = new Parameter("dtsBranchRate"," DTS branch rate per unit Arp", 60.0, "1/(uM s)")
		.setMutableAtRuntime()
		.setDescription("Branch-nucleation rate per unit local Arp concentration: per-step prob = rate*arpLocal*deltaT. Higher = a denser dendritic brush.");
	static final Parameter dtsArpConsumePerBranch = new Parameter("dtsArpConsumePerBranch"," DTS Arp consumed per branch", 0.05, "uM")
		.setMutableAtRuntime()
		.setDescription("Activated Arp removed from the local membrane vertices per branch -- the negative feedback that bounds branching near each NPF source (the source replenishes via diffusion).");
	static final Parameter dtsBranchAngle = new Parameter("dtsBranchAngle"," DTS Arp2/3 branch angle", 70.0, "degrees")
		.setDescription("Daughter-vs-mother branch angle (~70 deg for Arp2/3), tilted toward the membrane normal so the dendritic network grows into the cortex.");
	static final Parameter dtsMaxFilaments = new Parameter("dtsMaxFilaments"," DTS max filament count (branch cap)", 3000, "", Parameter.INT)
		.setMutableAtRuntime()
		.setDescription("Hard cap on total FilSegments; branching stops above it (runaway backstop).");
	static final Parameter dtsActinCollide = new Parameter("dtsActinCollide"," DTS actin-vs-membrane collision in the loop", 0, "", Parameter.BOOLEAN, false)
		.setMutableAtRuntime()
		.setDescription("When on, every actin FilSegment is collided against the membrane each step (segment-vs-triangle, grid-accelerated): the cortex CONTAINS the actin and the reaction BULGES the membrane where actin pushes. The basis for actin-driven protrusion.");
	static final Parameter dtsRatchetForce = new Parameter("dtsRatchetForce"," DTS polymerization-ratchet force on barbed tips", 0.0, "Newtons")
		.setMutableAtRuntime()
		.setDescription("Outward (along the local membrane normal) Mogilner-Oster ratchet force on actin BARBED tips pressing the membrane from inside -- so a network growing against the cortex protrudes it. The bulge grows until the membrane's bending+area+volume reaction balances the push. 0 = off. ~1e-11 N.");
	static final Parameter dtsAnchorStiffness = new Parameter("dtsAnchorStiffness"," DTS membrane-actin anchor (ERM linker) stiffness", 0.02, "N/m")
		.setMutableAtRuntime()
		.setDescription("Spring stiffness of the ERM-like tether holding a formin mother's pointed end (end1) to its membrane vertex. Enforces the linkEnd1Node anchor so mothers stay pinned to the cortex and push straight out (clean localized protrusions) instead of tumbling. 0 = no anchor (free filaments).");
	static final Parameter dtsForminGrowOut = new Parameter("dtsForminGrowOut"," DTS formin mothers grow outward (toward membrane)", 0, "", Parameter.BOOLEAN, false)
		.setDescription("When on, formin mothers point their barbed end OUTWARD (toward the cortex) so their tips press the membrane (with the ratchet -> protrusion). Default off = grow inward (the physical de-novo mother; Arp branches then face the membrane).");
	static final Parameter dtsForminRecover = new Parameter("dtsForminRecover"," DTS formin pool recovery rate", 0.0, "1/s")
		.setMutableAtRuntime()
		.setDescription("Re-recruitment rate of the formin pool at hot vertices (toward dtsForminPool). 0 = bounded (each zone makes a fixed number of mothers); >0 = sustained turnover.");

	static final Parameter dtsBrownianOff = new Parameter("dtsBrownianOff"," DTS deterministic (no vertex Brownian)", 0, "", Parameter.BOOLEAN, false)
		.setDescription("When active, suppresses per-vertex thermal forcing (sets nodeBrownianMotionOff) for a clean deterministic relaxation -- used to validate that the IC sphere is a force-balanced equilibrium. Default off = physical undulations on.");

	// **** Membrane (legacy v1 -- spring/vesicle; all default-off) ****
	static final Parameter membraneCellRadius = new Parameter("membraneCellRadius"," Radius of membrane created cell", 1.0, "microns");
	static final Parameter membraneCellPackingFactor = new Parameter("membraneCellPackingFactor"," Adjust to pack cells as desired", 1.0, "");
	static final Parameter membraneNodeRadius = new Parameter("membraneNodeRadius"," Radius of connected membrane nodes", 0.05, "microns");
	static final Parameter membraneXNodeCt = new Parameter("membraneXNodeCt"," Number of x-direction nodes", 30, "", Parameter.INT);
	static final Parameter membraneYNodeCt = new Parameter("membraneYNodeCt"," Number of y-direction nodes", 30, "", Parameter.INT);
	static final Parameter maxMembranePasses = new Parameter("maxMembranePasses"," Sub-passes each time-step to relax membrane", 30, "", Parameter.INT);
	static final Parameter membraneMaxLinkStrain = new Parameter("membraneMaxLinkStrain"," Membrane Link Max Strain", 0.1, "");
	static final Parameter membraneNodeDragScale = new Parameter("membraneNodeDragScale"," Scale Membrane Node Drag Factor", 1e-10, "");
	static final Parameter membraneTstBallRadius = new Parameter("membraneTstBallRadius"," Membrane Test Ball Radius", 0.1, "microns");
	static final Parameter membraneTstBallForce = new Parameter("membraneTstBallForce"," Membrane Test Ball Force", -1e-11, "Newtons");
	static final Parameter membraneBrownianScale = new Parameter("membraneBrownianScale"," Membrane Brownian Scale Factor", 1e-10, "");
	static final Parameter filTipRadiusForCollisions = new Parameter("filTipRadiusForCollision"," Sphere Radius at tip of filaments for collisions with membrane", 0.05, distUnits);
	static final Parameter membraneTransparency = new Parameter("membraneTransparency"," Membrane Transparency ", 0.2,"");
	static final Parameter outwardCellForce = new Parameter("outwardCellForce"," Cell Pressure Force", 1e-22, "Newtons");

	// *** Rho Related ***
	static final Parameter rhoHotLifetime = new Parameter("rhoHotLifetime"," Lifetime of a Rho Hot Node", 1.0, "seconds");

	// *** Turnover of elements ****
	static final Parameter nodeLifetime = new Parameter("nodeLifetime"," Lifetime of Protein Node", 10.0, "seconds");
	static final Parameter myoMiniLifetime = new Parameter("myoMiniLifetime"," Lifetime of Myosin Minifilament", 10.0, "seconds");

	
	// **** Cell Fill Nodes ****
	static final Parameter fillNodeRadius = new Parameter("fillNodeRadius"," Radius of fill nodes", 0.1, "microns");
	static final Parameter fillNodeCt = new Parameter("fillNodeCt"," Number of fill nodes", 30, "", Parameter.INT);
	static final Parameter fillNodeDragScale = new Parameter("fillNodeDragScale"," Scale Fill Node Drag Factor", 1, "");
	static final Parameter fillNodeBrownianScale = new Parameter("fillNodeBrownianScale"," Fill Node Brownian Scale Factor", 1, "");

	// **** Actin Filaments Geometry ****
	static final double actinMonoDiam = 0.0054; // (microns)
	static final double actinMonoRadius = actinMonoDiam / 2.0; // (microns)
	static final double actinWidth = 0.007; // (�m) thickness of actin filament
	static final double helixWaveLength = 0.036; // (�m) length for one turn of
													// helical filament
	static final double monsPerHelixTurn = helixWaveLength / actinMonoRadius;
	static final double helixAngInc = Math.PI / monsPerHelixTurn;
	static final double helixPitch = Math.PI / helixWaveLength;
	static final double helixMonOffset = (actinWidth - actinMonoDiam) / 2;
	static final double kTOverDelta = Boltz * tempK / (actinMonoRadius * 1e-6); // used in force modulated poly.

	static private final int actinSeed_init = 3;
	static final Parameter actinSeed = new Parameter("actinSeed"," Monomers to seed filament", actinSeed_init, "", Parameter.INT);
	
	// **** Arp2/3 Related *****
	static final double arp23Radius = 2*actinMonoDiam; // microns
	static final double arp23AlphaAngle = 70*Math.PI/180;	// radians
	static final double cosArp23Alpha = Math.cos(arp23AlphaAngle);
	static final double sinArp23Alpha = Math.sin(arp23AlphaAngle);
	static private final double arpTorqSpring_init = 1e-18; // N/radian, elastic torque spring for Arp2/3 branches
	static final Parameter arpTorqSpring = new Parameter("arpTorqSpring", " Torque Spring To Constrain Mother/Daughter Arp2/3 Relationship",arpTorqSpring_init, " N/rad", Parameter.DOUBLE, true);
	static private final double arpTransFracMove_init = 1.0; // fraction of mother/daughter branch-point gap closed per step
	static final Parameter arpTransFracMove = new Parameter("arpTransFracMove"," Arp2/3 Branch Translational Correction Fraction", arpTransFracMove_init, "").setMutableAtRuntime().setDescription("Fraction of the Arp2/3 mother-daughter branch-point gap closed per timestep by the translational constraint (Arp23.applyTransForce). 1.0 = close the gap exactly (critically damped); >1 over-corrects and overshoots (the original 2.0 caused visible spin/overshoot); <1 under-corrects, giving looser branches whose daughters can drift off the branch point. Lower if the branched network spins/overshoots; raise for tighter coupling. Live-tunable.");

	// Controlled single-junction relaxation test (diagnostic IC). When on, the buildBranchedFils
	// initial-condition builds ONE mother + ONE daughter Arp2/3 branch, perturbs the daughter
	// off its constraint by junctionPerturbDeg, and the Arp23 logs "JCT <step> <gap_um> <angle_rad>"
	// every step so the constraint relaxation can be inspected for overshoot/ringing in isolation
	// (no growth, no other filaments). Use with thermal off and a short run. Default off.
	static final Parameter junctionTest = new Parameter("junctionTest"," Single Arp2/3 Junction Relaxation Test", 0.0, "", Parameter.BOOLEAN, false);
	static final Parameter junctionPerturbDeg = new Parameter("junctionPerturbDeg"," Junction Test Daughter Perturbation", 30.0, " deg", Parameter.DOUBLE, false);

	// Arp2/3 branch-constraint sub-cycling (r-RESPA / multiple-time-stepping). N = number of
	// inner sub-steps of dt/N taken on ONLY the stiff branch constraint each global step, with
	// the soft forces (chain/boundary/node/joints/Brownian) frozen. 1 = off (legacy single
	// integration). >1 reproduces a fine-dt relaxation of the stiff junction without paying
	// dt/N on the whole sim, removing the explicit-Euler overshoot that misorients branches at
	// large dt. CPU prototype (Arp23.subcycleAll); structured to map onto a per-cluster GPU
	// kernel. Live-tunable.
	static final Parameter arpSubcycleN = new Parameter("arpSubcycleN"," Arp2/3 Branch Constraint Sub-cycle Count", 1.0, "", Parameter.INT, false).setMutableAtRuntime().setDescription("Number of inner sub-steps (each dt/N) the Arp2/3 branch constraint is integrated per global timestep, with soft forces frozen (multiple-time-stepping / r-RESPA). 1 = off (single explicit integration, can overshoot and misorient branches at large dt). Raising it (e.g. 10) relaxes the stiff junction at a fine effective dt while the rest of the sim stays at the global dt -- fixes branch spin/overshoot without the 10x cost of globally shrinking dt. Requires arpFixedStiffnessDt>0 (otherwise the constraint stiffness scales as 1/dt and cannot be refined). CPU prototype; GPU port is per-cluster. Live-tunable.");

	// Reference timestep for the Arp2/3 translational constraint stiffness. 0 = legacy (stiffness
	// uses the live deltaT, k ~ 1/dt -- a per-step position correction that cannot be dt-refined
	// or sub-cycled). >0 pins the stiffness to k = fracMove/(arpFixedStiffnessDt*mobility), a
	// dt-INDEPENDENT explicit penalty spring whose relaxation time is ~arpFixedStiffnessDt; this
	// is the prerequisite for arpSubcycleN to work. Set it to roughly the global dt at which the
	// branch was tight (e.g. 1e-5). Live-tunable.
	static final Parameter arpFixedStiffnessDt = new Parameter("arpFixedStiffnessDt"," Arp2/3 Constraint Fixed-Stiffness Reference dt", 0.0, " s", Parameter.DOUBLE, false).setMutableAtRuntime().setDescription("Reference timestep pinning the Arp2/3 translational branch stiffness k=fracMove/(this*mobility). 0 = legacy (k uses live deltaT, scales as 1/dt -- a position correction, not dt-refinable). >0 makes k a fixed, dt-independent explicit penalty spring (relaxation time ~ this value), which is required before arpSubcycleN sub-cycling is meaningful. Typically set to the dt at which the branch was acceptably tight (e.g. 1e-5). Live-tunable.");

	// Membrane relaxation, GPU-shaped (Jacobi iterative projection). The membrane strain
	// propagates one neighbour-ring per pass, so it is relaxed by N projection passes/step. The
	// legacy loop in doLoop fans out ThreadSets in a host while-loop; this self-contained variant
	// (NodeLink.subcycleRelaxAll) does the same Jacobi passes (zero node force -> sum all link
	// forces at current pose -> integrate all nodes -> repeat) in one routine, the exact shape a
	// per-mesh GPU kernel with a bounded internal pass-loop would take. Same pass cap / maxStrain
	// early-out as the legacy loop. Default off (legacy ThreadSet loop). See SUBCYCLING_GPU.md.
	static final Parameter membraneRelaxGpuShaped = new Parameter("membraneRelaxGpuShaped"," Membrane Relaxation GPU-Shaped (Jacobi)", 0.0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("0 = legacy membrane relaxation loop (ThreadSet fan-out per pass). 1 = self-contained Jacobi relaxation (NodeLink.subcycleRelaxAll): zero node forces, sum all NodeLink forces at the current pose, integrate all membrane nodes, repeat up to maxMembranePasses or until maxStrain<membraneMaxLinkStrain -- the structure a per-mesh GPU kernel would use. Behaviour should track the legacy loop. Live-tunable.");
	// Reference dt pinning the membrane NodeLink stiffness (same role as arpFixedStiffnessDt).
	// 0 = legacy (k = membraneLinkFracMove/(deltaT*mobility), scales as 1/dt). >0 = fixed,
	// dt-independent in-plane membrane stiffness; required for dt-refinement, and makes the
	// projection passes robust to global-dt changes. Set ~ the dt at which the sheet was tuned.
	static final Parameter membraneFixedStiffnessDt = new Parameter("membraneFixedStiffnessDt"," Membrane Link Fixed-Stiffness Reference dt", 0.0, " s", Parameter.DOUBLE, false).setMutableAtRuntime().setDescription("Reference timestep pinning the membrane NodeLink stiffness k=membraneLinkFracMove/(this*mobility). 0 = legacy (k uses live deltaT, scales as 1/dt). >0 = fixed, dt-independent explicit in-plane spring (relaxation ~ this value). Same lesson as arpFixedStiffnessDt; required before membrane sub-cycling/dt-refinement is meaningful. Live-tunable.");

	// **** Viscous Blobs — removed 2026-05-17 (Round 7); see JOURNAL.md. ****
	// Listeria-specific hack: filaments accumulate sphere-drag blobs to simulate implicit
	// crosslinking to unlisted cellular components. bRotGam jumped 560× at vBlobMinMons=50,
	// stopping segment rotation entirely. Not appropriate for general-purpose actin code.
	// static final Parameter useViscousBlob = new Parameter("useViscousBlob", ...);
	// static final Parameter nVBlobPerBug = new Parameter("nVBlobPerBug", ...);
	// static final Parameter vBlobMinMons = new Parameter("vBlobMinMons", ...);
	// static final double lengthForOneBlobPerSecond = 0.25*Env.actinMonoRadius;
	// static final double vBlobOnRate = 1/lengthForOneBlobPerSecond;
	// static final double vBlobOffRate = 0.5;
	// static final int maxVBlobs = 200;
	// static final double blobGamScaleFactor = 1;
	// static final double blobRotGamScaleFactor = 1;
	// static final double bTransGamViscBlob = 6*Math.PI*Env.aeta.getValue()*1.0e-6*0.5*blobGamScaleFactor;
	// static final double bRotGamViscBlob = 8*Math.PI*Env.aeta.getValue()*Math.pow(1.0e-6*0.5,3)*blobRotGamScaleFactor;
	// static final double N = 1.0/Env.nVBlobPerBug.getIntValue();
	// static Pt3D blobTransGam = new Pt3D(N*bTransGamViscBlob, N*bTransGamViscBlob, N*bTransGamViscBlob);
	// static Pt3D blobRotGam = new Pt3D(N*bRotGamViscBlob, N*bRotGamViscBlob, N*bRotGamViscBlob);
		

	// **** Actin Mechanics ****
	static final Parameter actinOnCortex = new Parameter("actinOnCortex"," Actin constrained to cortex", 0, " ", Parameter.BOOLEAN, true);
	static final double persistenceLength = 15; // in microns, use meters in defining EI below
	static final double EI = Boltz * tempK * (persistenceLength * 1e-6); // EI = kTLp from worm-like chain theory															
	static private final int stdSegLength_init = 32; // number of monomers per segment
	static final Parameter stdSegLength = new Parameter("filSegLength"," Std. segment length", stdSegLength_init, " monomers",Parameter.INT);
	static private final int filDragMinMonomers_init = 30; // minimum effective length (monomers) used for FilSegment drag
	static final Parameter filDragMinMonomers = new Parameter("filDragMinMonomers"," Min Effective Monomers For Filament Drag", filDragMinMonomers_init, " monomers", Parameter.INT).setMutableAtRuntime().setDescription("Floor on the effective rod length used when computing a FilSegment's drag (FilSegment.calculateProperties): short/nascent filaments get drag as if at least this many monomers long. Because rotational drag scales as length^3 (vs length for translation), raising this strongly over-damps the rotation of short filaments (e.g. nascent Arp2/3 daughters) -> stabilizes the stiff branch constraint at larger dt, viscous-blob style. Coarse approximation of a short filament being embedded/locked in the surrounding network. Raising it slows the real dynamics of short filaments; default 30 (~0.08um). Takes effect when a filament's drag is next recomputed (construction / length change).");
	static private final double filTorqSpring_init = 1e-20; // N/radian If using elastic springs for torque to keep filaments aligned
															
	static final Parameter filTorqSpring = new Parameter("filTorqSpring"," Filament Torsional Spring", filTorqSpring_init, " N/radian",Parameter.DOUBLE, false);

	static private double BTransCoeff_init = 1; // range from zero (no brownian motion) to infinity
	static final Parameter BTransCoeff = new Parameter("BTransCoeff"," Brownian Translational Coefficient", BTransCoeff_init, "").setMutableAtRuntime();

	static private double BRotCoeff_init = 0.5; // range from zero (no brownian motion) to infinity
	static final Parameter BRotCoeff = new Parameter("BRotCoeff"," Brownian Rotational Coefficient", BRotCoeff_init, "").setMutableAtRuntime();

	static private double arpHeldBrownianFactor_init = 0.02; // 1/50: an Arp2/3-held filament is not free
	static final Parameter arpHeldBrownianFactor = new Parameter("arpHeldBrownianFactor"," Arp2/3-Held Brownian Factor", arpHeldBrownianFactor_init, "").setMutableAtRuntime().setDescription("Brownian-force multiplier for a filament whose pointed end is Arp2/3-capped and held (de-novo nucleated at a membrane node, or a branch tethered there). Such a filament is structurally anchored, not freely diffusing, so its thermal forcing is scaled down by this factor (default 0.02 = 1/50). Without it, a tiny nascent seed gets a full free-filament Brownian kick that, against its stiff pointed-end tether at small dt, drives the integrator unstable. 1.0 = treat as free.");

	static private double membraneAnchorReactionFrac_init = 1.0; // 1 = full Newton reaction; <1 = heavy-anchor membrane
	static final Parameter membraneYieldSubN = new Parameter("membraneYieldSubN"," Membrane Yield Sub-Steps", 20, "", Parameter.INT).setMutableAtRuntime().setDescription("Number of sub-steps (deltaT/N each) used to integrate the membrane under the sustained actin push when membraneYield is on (the Arp2/3 sub-cycle pattern). The full-deltaT Jacobi re-application overshoots a stiff mesh and makes nodes shoot around; sub-stepping converges smoothly to the bulge. Higher = smoother/slower; ~20 default. Needs membraneFixedStiffnessDt>0.");

	static final Parameter membraneYield = new Parameter("membraneYield"," Membrane Yields To Actin (Protrusion)", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("If on (needs membraneRelaxGpuShaped), the membrane link relaxation RE-APPLIES the captured actin->membrane force (barbed-tip face-collision pushes + mother tether reactions) on every Jacobi pass instead of zeroing it. The mesh then settles at a force-balanced BULGE (link tension + radial pin balancing the sustained actin push) rather than snapping back to rest length, so a dendritic network can protrude the membrane. Off = inextensible rest-length relaxation (no protrusion). Needs membraneFaceCollideOn for the push.");

	static private double sphereConstraintFrac_init = 0.4; // per-step fraction of the radial-displacement restoring (was hardcoded)
	static final Parameter sphereConstraintFrac = new Parameter("sphereConstraintFrac"," Sphere Radial Constraint Stiffness", sphereConstraintFrac_init, "").setMutableAtRuntime().setDescription("Stiffness of the per-node radial restoring force that holds the closed membrane at membraneCellRadius (fraction of the radial displacement corrected per step; default 0.4, the legacy hardcoded value). The flat-sheet membrane has NO such pin and protrudes freely under actin load. Lower this so a node pushed out by the dendritic network can BULGE locally (a protrusion) instead of snapping back to R; too low and turgor/Brownian let the whole sphere lose shape. ~0.05-0.1 to allow protrusions.");

	static final Parameter membraneAnchorReactionFrac = new Parameter("membraneAnchorReactionFrac"," Membrane Anchor Reaction Fraction", membraneAnchorReactionFrac_init, "").setMutableAtRuntime().setDescription("Scales the reaction force a filament tether (ERM-like end1 linker) exerts back on its membrane node. A membrane node's drag is only comparable to a filament's, so the full reaction lets a tethered filament drag the node ~half the strain per step — the hot-zone jitter. <1 treats the membrane as a heavy, mesh-constrained anchor (the filament still feels the full tether). Default 1.0 (full reaction). ~0.2 calms the cortex.");

	// ---- Membrane PROBE: drive a plain protein node into the membrane with a constant force (isolation test, no actin) ----
	static final Parameter membraneProbeForce = new Parameter("membraneProbeForce"," Membrane Probe Constant Drive Force", 0.0, " N").setMutableAtRuntime().setDescription("0 = off. >0 creates ONE plain protein node (no myosins/formins/actin) inside the sphere and drives it outward (+x) with this constant force, colliding it sterically with the membrane nodes. Decouples membrane mechanics from the actin: a clean, known load to measure how the membrane deforms and at what bulge it stalls the probe. ~1e-11 N is a strong push (cf. link force ~1e-11). Pair with membraneVesicle + membraneYield so the relaxation re-applies the probe push.");
	static final Parameter membraneProbeRadius = new Parameter("membraneProbeRadius"," Membrane Probe Radius", 0.15, " um").setMutableAtRuntime().setDescription("Radius of the constant-force membrane probe (steric sphere). Larger = pushes a wider patch of membrane nodes. ~0.15 um default.");
	static final Parameter membraneProbeStartRadius = new Parameter("membraneProbeStartRadius"," Membrane Probe Start Radius", 0.0, " um").setMutableAtRuntime().setDescription("Initial radial position of the probe along +x: (this,0,0). 0 = sphere center (probe travels the full radius before contact). Launch-time only.");

	// ---- VESICLE model: hold the closed membrane like a pressurized elastic shell (replaces the per-node radial pin) ----
	static final Parameter membraneVesicle = new Parameter("membraneVesicle"," Membrane Vesicle (Volume-Pressure Shell)", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("0 = legacy per-node radial pin (addSphericalConstraintForce: every node independently leashed to membraneCellRadius -- a Winkler foundation that doesn't couple neighbours, so the cortex is either rigid or floppy, never a coherent compliant shell). 1 = hold global shape by a VOLUME-conserving internal pressure (P = membraneTurgorP0 + membraneVolumeModulus*(V0-V)/V0) applied over the triangulated surface, balanced by the Tier-1 rest-length link tension. The sphere floats as a vesicle: a local actin push makes a real bleb (neighbour-coupled, bounded by tension + volume), the physical analog of the pinned sheet's edge-anchored bending. REQUIRES membraneLinkRestFrac>0 (tension) and membraneLinkCenterAttach=1. Disable the radial pin -- this replaces it.");
	static final Parameter membraneVolumeModulus = new Parameter("membraneVolumeModulus"," Membrane Volume (Bulk) Modulus", 5000.0, " Pa").setMutableAtRuntime().setDescription("Bulk modulus K of the enclosed-volume constraint (vesicle mode): pressure P = membraneTurgorP0 + K*(V0-V)/V0, where V0 is the initial enclosed volume. Large K = near-incompressible cytoplasm (V held ~V0, so a local bleb must borrow volume from a slight dimple elsewhere -- real bleb behaviour). Too small = the shell breathes/inflates; too large = stiff. Calibrate against the link tension (watch the [VESICLE] V/V0 readout). ~5000 Pa starting guess.");
	static final Parameter membraneTurgorP0 = new Parameter("membraneTurgorP0"," Membrane Resting Turgor Pressure", 0.0, " Pa").setMutableAtRuntime().setDescription("Baseline outward turgor (vesicle mode): the P0 in P = P0 + K*(V0-V)/V0. 0 = the resting sphere is held purely by the volume constraint against link tension (equilibrium at V0). >0 pre-inflates the shell (puts the cortex under resting tension), which stiffens it against protrusion. Usually leave 0 and tune membraneVolumeModulus.");

	// ---- Tier-1 membrane stability (all default-off -> legacy zero-rest contractile mesh) ----
	static final Parameter membraneLinkRestFrac = new Parameter("membraneLinkRestFrac"," Membrane Link Rest-Length Fraction", 0.0, "").setMutableAtRuntime().setDescription("0 = LEGACY zero-rest-length contractile NodeLink spring (force ~ full length; the mesh has no in-plane ground state and shears/clumps on a closed sphere under load). >0 turns each link into a genuine ELASTIC spring with rest length = this * (link's length when created): force ~ (length - rest), so the lattice resists both stretch AND compression -> stable spacing, shear/area stiffness, no tangential clumping. 1.0 = pure elastic (no prestress); <1 (e.g. 0.9) keeps a mild contractile cortical prestress. Captured per-link at creation; live changes only affect links made afterwards.");
	static final Parameter membraneLinkCenterAttach = new Parameter("membraneLinkCenterAttach"," Membrane Link Force At Node Center", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("0 = legacy: NodeLink force is applied at the off-center sticky point, which torques the (lightly rotationally-damped) membrane node -> the sticky points whip around and the link geometry jitters. 1 = apply the link force at the node CENTER (no torque) so membrane nodes behave as translational point masses in the mesh. Removes the spin/jitter mode; recommended for structural membranes.");
	static final Parameter membraneRelaxAvgValence = new Parameter("membraneRelaxAvgValence"," Membrane Relax: Average Over Valence", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("0 = legacy Jacobi: each node integrates the SUM of its incident link corrections (un-normalized -> with valence ~6 and membraneLinkFracMove>1 this over-relaxes and nodes shoot around). 1 = divide each node's incident link force by its active-link count (boundCt) as the force is applied (averaged/under-relaxed Jacobi) -> unconditionally stable for membraneLinkFracMove<=1, converges without overshoot. Applies wherever NodeLink.applyTransForce runs (relaxation passes and single-shot enforcement); composes with the membraneYield external push, which is added separately and not scaled.");
	static final Parameter membraneNodeMaxDispFrac = new Parameter("membraneNodeMaxDispFrac"," Membrane Node Max Step (frac of node radius)", 0.0, "").setMutableAtRuntime().setDescription("0 = off. >0 caps a membrane node's per-(sub)step translation to this * membraneNodeRadius. A cheap safety net: a single overloaded node (deep actin leak, transient stiff-link force) can no longer take one huge explicit-Euler step and fly to infinity -- it moves slowly instead, which is always recoverable. ~0.25 is a reasonable cap. Applies to StickyNode integration only.");

	// ---- Tier-2 membrane AREA GROWTH via node insertion (edge-split). Default-off. Needs membraneLinkCenterAttach. ----
	static final Parameter membraneAreaGrow = new Parameter("membraneAreaGrow"," Membrane Area Growth (Node Insertion)", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("0 = fixed node count (a growing bulge spreads the same nodes apart -> coverage thins -> the cortex tears open and the dendritic net excavates through). 1 = when a membrane link over-stretches (length > membraneInsertStrain * rest) it is EDGE-SPLIT: a new StickyNode is inserted at the midpoint and wired to the two endpoints + the two shared triangle-apex neighbours, with rest length = the mesh's nominal rest. The dome gains nodes as it grows and stays CLOSED over the protrusion. REQUIRES membraneLinkCenterAttach=1 (links act center-to-center; inserted nodes need no rigid sticky-point geometry).");
	static final Parameter membraneInsertGapUm = new Parameter("membraneInsertGapUm"," Membrane Insert Hole-Size Threshold", 0.14, " um").setMutableAtRuntime().setDescription("COVERAGE-based split trigger (Tier-2): a membrane link is edge-split once its ABSOLUTE length exceeds this -- i.e. once the gap is a real HOLE the dendritic net could poke through. Set just above the steric coverage scale (nodeR+filTipR ~ 0.10um) and above the resting mesh's max link (~0.10um) so a covered-but-stretched bulge is left alone. CRUCIAL: this CONVERGES (a stretched region stops being split once its gaps are < this), unlike a strain-relative trigger which chases an ever-stretched equilibrium and floods nodes. 0 = fall back to the strain-relative membraneInsertStrain trigger (legacy, tends to over-insert). ~0.14um default.");
	static final Parameter membraneInsertStrain = new Parameter("membraneInsertStrain"," Membrane Insert Strain Threshold (fallback)", 1.6, "").setMutableAtRuntime().setDescription("Fallback strain-relative split trigger used ONLY when membraneInsertGapUm=0: a link splits once length > this * rest. Tends to over-insert on a soft mesh (the force-balanced stretch sits well above threshold, so every bulge link is a permanent candidate -> floods to the node cap). Prefer the absolute membraneInsertGapUm trigger. Only active with membraneAreaGrow=1.");
	static final Parameter membraneInsertPerStep = new Parameter("membraneInsertPerStep"," Membrane Max Inserts Per Tick", 2, "", Parameter.INT).setMutableAtRuntime().setDescription("Cap on edge-splits per insertion TICK (a tick happens every membraneInsertEveryNSteps steps). The longest 'holes' are split first (biggest gaps fixed first), none sharing a node in one tick. With the metering this sets the trickle rate (~perTick / everyN nodes per step). ~2 default.");
	static final Parameter membraneInsertEveryNSteps = new Parameter("membraneInsertEveryNSteps"," Membrane Insert Interval (steps)", 20, "", Parameter.INT).setMutableAtRuntime().setDescription("Node insertion is attempted only every this-many steps (a metered TRICKLE, not a per-step flood). Combined with membraneInsertPerStep, the max growth rate is ~perStep/everyN nodes per step; actual rate is coverage-limited (it stops when no gap exceeds membraneInsertGapUm). Raise for a slower trickle. ~20 default.");
	static final Parameter membraneMaxNodes = new Parameter("membraneMaxNodes"," Membrane Max Node Count", 12000, "", Parameter.INT).setMutableAtRuntime().setDescription("Hard safety cap on total membrane (StickyNode) count; node insertion stops once reached. Prevents an unbounded refinement loop from exhausting memory. ~12000 default (a 3267-node sphere has headroom to grow a large covered bleb).");
	static final Parameter membraneInsertCooldown = new Parameter("membraneInsertCooldown"," Membrane Insert Cooldown (steps)", 300, "", Parameter.INT).setMutableAtRuntime().setDescription("A link younger than this many steps is NOT split. Breaks the refinement CASCADE: edge-splitting a stretched triangle creates two apex links (M-C, M-D) at ~0.87x the split length -- often just over the strain threshold -- which would split again next step, blowing the node count up to the cap (a cauliflower bloom). The cooldown lets a freshly-created link relax toward nominal before it can be split again, so insertion tracks the actual bulge-growth rate instead of cascading. ~300 default; raise if growth still looks explosive.");

	static final Parameter membraneConfine = new Parameter("membraneConfine"," Membrane Confines Cytoskeleton", 0.0, "").setMutableAtRuntime().setDescription("If !=0 (and the membrane is a closed sphere), any filament end that pokes past the cortex radius gets a soft inward radial force pushing it back inside — the membrane physically containing the cytoskeleton. This stops free/depolymerizing filaments from leaking out through the gaps between membrane nodes (the membrane node lattice is porous to filament bodies; only barbed tips collide with nodes). One-sided: no force on filaments already inside.");

	static private double membraneConfineFrac_init = 0.4; // overdamped 'close this fraction of the overshoot per step'
	static final Parameter membraneConfineFrac = new Parameter("membraneConfineFrac"," Membrane Confinement Fraction", membraneConfineFrac_init, "").setMutableAtRuntime().setDescription("Stiffness of the membrane confinement push (fraction of the radial overshoot corrected per step; 0.4 default, <1 for stability). Higher snaps an escaped filament back in faster.");

	static final Parameter membraneAlignTorque = new Parameter("membraneAlignTorque"," Cortex Alignment Torque", 0.0, " N/rad").setMutableAtRuntime().setDescription("If >0 (closed sphere), a gentle restoring torque (N/rad, like arpTorqSpring~1e-18) orients each held mother filament's long axis to membraneAlignAngle from the surface normal, and switches de-novo nucleation to that orientation. 0 = mother grows radially inward.");
	static private double membraneAlignAngle_init = 90.0; // deg of uVec (barbed dir) from the OUTWARD normal: 90=tangent
	static final Parameter membraneAlignAngle = new Parameter("membraneAlignAngle"," Mother Angle From Surface Normal", membraneAlignAngle_init, " degrees").setMutableAtRuntime().setDescription("Target angle (deg) between a held mother's barbed direction and the OUTWARD surface normal. 90 = tangent to the cortex (flat 'nurse-log' mat; branches grow inward and stop -> bounded cortex). <90 tilts the barbed end back TOWARD the membrane (with motherTetherDepth holding the pointed end off the surface) -> branches stay in the NPF zone and orientation-selective capping builds a self-amplifying ~35-deg dendritic array (protrusion). ~35-50 is the lamellipodial regime. Needs membraneAlignTorque>0.");
	static private double motherTetherDepth_init = 0.0; // microns the pointed end is held BELOW the inner cortex face
	static final Parameter motherTetherDepth = new Parameter("motherTetherDepth"," Mother Pointed-End Anchor Depth", motherTetherDepth_init, " microns").setMutableAtRuntime().setDescription("Depth (microns) below the inner cortex face at which the formin/ERM complex holds a mother's pointed end. 0 = at the cortex (tangent mat). >0 holds it off the surface so a barbed-toward-membrane mother (membraneAlignAngle<90) has room to angle up to the cortex — the geometry that makes a dendritic protrusion. ~0.1-0.2 um pairs with membraneAlignAngle~40.");

	static private double cortexBrownianZone_init = 0.0; // microns below the inner cortex face; 0 = off
	static final Parameter cortexBrownianZone = new Parameter("cortexBrownianZone"," Cortex Brownian Damping Zone", cortexBrownianZone_init, " microns").setMutableAtRuntime().setDescription("If >0 (closed sphere), any filament whose center lies within this distance of the inner cortex face has its Brownian forcing scaled by cortexBrownianFactor. The cortex is a dense, low-mobility shell; without this, large filaments freed by debranching get a full free-filament thermal kick and thrash against the membrane nodes. 0 = no cortex-proximity damping.");

	static private double cortexBrownianFactor_init = 0.1; // 10x-down thermal in the cortical shell
	static final Parameter cortexBrownianFactor = new Parameter("cortexBrownianFactor"," Cortex Brownian Factor", cortexBrownianFactor_init, "").setMutableAtRuntime().setDescription("Brownian-force multiplier applied to filaments within cortexBrownianZone of the cortex (default 0.1 = 10x down). Lower = calmer cortical layer.");

	// Implicit FORMIN mother-filament nucleation. Per the literature, Arp2/3 (WASP/WAVE) cannot nucleate
	// de novo — it only branches off a pre-existing mother. The first/mother cortical filaments are made by
	// formins (mDia), recruited to the same GTPase (Rac1/Cdc42) hot zones. We model formin implicitly as a
	// per-hot-node depletable pool: each mother consumes a quantum, so a zone makes at most ~forminConc/
	// forminConsumePerMother mothers (a hard cap unless forminRecover>0). Arp2/3 then branches off them.
	static private double forminConc_init = 6.0; // per-hot-node formin pool -> ~6 mothers/zone at consume=1
	static final Parameter forminConc = new Parameter("forminConc"," Formin Pool Per Hot Node", forminConc_init, "").setMutableAtRuntime().setDescription("Per-hot-node implicit formin pool that seeds linear MOTHER filaments at a Rac1/Cdc42 zone. Each de-novo mother consumes forminConsumePerMother, so standing mothers per zone cap at ~forminConc/forminConsumePerMother (hard cap unless forminRecover>0). This is the knob that controls how dense the cortical mat gets at a hot spot. Replaces the (biologically wrong) de-novo Arp2/3 nucleation.");
	static private double forminConsumePerMother_init = 1.0;
	static final Parameter forminConsumePerMother = new Parameter("forminConsumePerMother"," Formin Consumed Per Mother", forminConsumePerMother_init, "").setMutableAtRuntime().setDescription("Formin pool spent per mother-filament nucleation (default 1). With forminConc this sets the per-zone mother cap.");
	static private double forminRecover_init = 0.0; // /s; 0 = hard cap (no formin turnover)
	static final Parameter forminRecover = new Parameter("forminRecover"," Formin Pool Recovery Rate", forminRecover_init, " /s").setMutableAtRuntime().setDescription("Rate (/s) at which a hot node's formin pool recovers toward forminConc (formin re-recruitment). 0 = hard cap on mothers; >0 lets the zone slowly make new mothers over time (turnover).");

	static private final double maxSegAngle_init = 22.5; // degrees
	static final Parameter maxSegAngle = new Parameter("maxSegAngle"," Max. Angle Between Segments", maxSegAngle_init, " degrees",Parameter.DOUBLE, false);

	static private final double maxSegDist_init = 2 * Env.actinMonoDiam;
	static final Parameter maxSegDist = new Parameter("maxSegDist"," Max. Dist. Between Segment EndPts", maxSegDist_init, distUnits,Parameter.DOUBLE, false);

	static final Parameter maxPolyForce = new Parameter("maxPolyForce"," Max. Force To Poly Against", 1, "pN", Parameter.DOUBLE, true);
	static final Parameter polyLogFactor = new Parameter("polyLogFactor"," Log Factor decrease in Poly", Math.log(100), "",Parameter.DOUBLE, true);

	// **** Attachment to node
	static private final double nodeTorqSpring_init = 1e-18; // N/radian If using elastic spring for filament alignment constraint												
	static final Parameter nodeTorqSpring = new Parameter("nodeTorqSpring"," Node-Filament Torsional Spring", nodeTorqSpring_init, "N/radian",Parameter.DOUBLE);

	static private final double maxNodeTetherStrainDist_init = 2 * actinMonoDiam;
	static final Parameter maxNodeTetherStrainDist = new Parameter("maxNodeTetherStrainDist", " Max. Strain Distance in Node Tether",maxNodeTetherStrainDist_init, "microns", Parameter.DOUBLE, false);

	static private final double nodeTetherDetachRate_init = 0.001;
	static final Parameter nodeTetherDetachRate = new Parameter("nodeTetherDetachRate", " Node Tether Detachment Rate",nodeTetherDetachRate_init, "/s", Parameter.DOUBLE, false);

	static final Parameter forminMoves = new Parameter("forminMoves"," Mobile Attachment Point of formin to node", 0, " ",Parameter.BOOLEAN, false);
	static final Parameter forminRelease = new Parameter("forminRelease"," Random formin release rate", 1, kOffUnits);

	// ***** Controlling monomer sim and monomer rendering (memory $$)! ****
	// Two options, very different: if we don't simulate monomers then we just have static rods as initialized. If monomers not rendered all the biochem still happens, just using faster/cheaper graphical representation
	static final Parameter noMonomersSimd = new Parameter("noMonomersSimd"," No monomers simulated", 0, "", Parameter.BOOLEAN, false);
	static final Parameter noMonomersRendered = new Parameter("noMonomersRendered"," No monomers rendered", 0, "", Parameter.BOOLEAN, false);

	// ***** Actin Annealing ****
	static final Parameter filamentsAnneal = new Parameter("filamentsAnneal"," Do filaments end-to-end anneal?", 0, "", Parameter.BOOLEAN, true);

	static private final double annealDist_init = 10 * actinMonoDiam;
	static final Parameter annealDist = new Parameter("annealDist"," Tip distance for annealing", annealDist_init, distUnits);

	static private final double annealAngleCosine_init = 0.9;
	static final Parameter annealAngleCosine = new Parameter("annealAngleCosine", " Cosine of max. angle for annealing",annealAngleCosine_init, " unitless");

	// **** Inter actin filament Mechanics ****
	static final Parameter maxXLinksOnSeg = new Parameter("maxLinksOnSeg"," Max. Cross-Links per Segment", 10, "");
	static final Parameter minSepBetweenXLinks = new Parameter("minSepBetweenXLinks"," Min. Separation Between Crosslinks", 5*actinMonoDiam, "µm");
	static final Parameter xLinks = new Parameter("sideBonds"," Cross-linkers? (1 for parallel, -1 antiparallel, 0 both)", 0, "",Parameter.INT, true);
	static final Parameter xLinksRelax = new Parameter("sideBondsRelax"," Do side bonds relax, allowing translation of bound fils?", 0, "",Parameter.BOOLEAN, false);
	static final Parameter sideBondsStabilize = new Parameter("sideBondsStabilize"," Do side bonds stabilize depolymerization like nodes", 1.0, "",Parameter.DOUBLE, false);
	static final Parameter bundleStableFactor = new Parameter("bundleStableFactor"," Bundled filament resist cofilin binding (x each bound fil)", 2.0,"", Parameter.DOUBLE, false);

	static private final double filLinkSpring_init = 1e-9; // N/radian elastic springs link filSegs from different filaments
	static final Parameter filLinkSpring = new Parameter("filLinkSpring"," Link Spring Between Filaments", filLinkSpring_init, " N/m", Parameter.DOUBLE, true);

	static private final double filLinkTorqSpring_init = 1e-19; // N/radian, elastic torque spring links filSegs from different filaments														
	static final Parameter filLinkTorqSpring = new Parameter("filLinkTorqSpring", " Torque Spring Between Filaments",filLinkTorqSpring_init, " N/rad", Parameter.DOUBLE, true);
	
	static private final double maxXLinkBondAngle_init = Math.PI/12; // radians, max angle between filSegs for which crosslinking is allowed														
	static final Parameter maxXLinkBondAngle = new Parameter("maxXLinkBondAngle", " Max. Allowed Angle Between Filaments for XLink Binding",maxXLinkBondAngle_init, " rad", Parameter.DOUBLE, true);

	static private double xLinkTransAttn_init = 1; // range from zero (no attn) to infinity
	static final Parameter xLinkTransAttn = new Parameter("xLinkTransAttn"," Trans. Attn. Factor per link for Cross-linked Segments",xLinkTransAttn_init, "");

	static private double xLinkRotAttn_init = 1; // range from zero (no attn) to infinity											
	static final Parameter xLinkRotAttn = new Parameter("xLinkRotAttn"," Rot. Attn. Factor per link for Cross-linked Segments",xLinkRotAttn_init, "");

	static private final double crossLinkGrabDist_init = 2 * Env.actinMonoDiam;
	static final Parameter crossLinkGrabDist = new Parameter("crossLinkGrabDist", " Max. Distance to establish cross-link",crossLinkGrabDist_init, distUnits, Parameter.DOUBLE, true);

	// Crosslink lifecycle (2026-06-12) — three cadences:
	//   FORMATION  (crosslinkCheckInt): proximity broad-phase + checkToLink alignment
	//              test + a concentration-dependent dice roll → makeLink.
	//   FORCE      (every step): FilLink spring force into forceSum (unchanged).
	//   DISSOLUTION(every step): FilLink.ckLinkBreak Bell-model off-rate dice roll
	//              riding the just-computed strain (linkOffConst/Coeff/Exp below).
	// Formation cadence: crosslinkCheckInt = crosslinkDeltaT/deltaT (default =
	// biochemDeltaT → biochem cadence). Launch-time only (mirrors biochemDeltaT;
	// not runtime-mutable so no crosslinkCheckInt-recompute hook is needed).
	// Inactive by default → crosslinkCheckInt falls back to biochemCheckInt (the
	// actual derived value, honoring a param-file biochemDeltaT override). Set
	// crosslinkDeltaT:true:<s> in a param file to decouple the formation cadence.
	static final Parameter crosslinkDeltaT = new Parameter("crosslinkDeltaT"," Time Step For Crosslink Formation Check", biochemDeltaT_init, "seconds", Parameter.DOUBLE, false);

	// Concentration-dependent formation: per qualifying candidate per check,
	//   P_form = 1 - exp(-xLinkOnRate * xLinkConc * dtCheck),  dtCheck = deltaT*crosslinkCheckInt.
	// (linearizes to xLinkOnRate*xLinkConc*dtCheck for small p). With dissolution
	// this gives a finite, tunable steady-state link population. Defaults chosen
	// (see JOURNAL 2026-06-12) to give a healthy steady state in the dense xlink
	// fixture; raising either scales formation, raising it past saturation just
	// fills every qualifying site.
	static final Parameter xLinkOnRate = new Parameter("xLinkOnRate"," Crosslinker formation on-rate (k_on)", 10.0, " /(uM s)", Parameter.DOUBLE, true).setMutableAtRuntime().setDescription("Crosslinker formation on-rate k_on. Per qualifying candidate per crosslink-check, P_form = 1 - exp(-k_on*[xlink]*dtCheck). Higher k_on -> faster formation -> larger steady-state link count (until qualifying sites saturate). Pairs with xLinkConc and the dissolution knobs linkOffConst/Coeff/Exp to set the steady state.");
	static final Parameter xLinkConc = new Parameter("xLinkConc"," Crosslinker concentration ([xlink])", 1.0, " uM", Parameter.DOUBLE, true).setMutableAtRuntime().setDescription("Free crosslinker concentration [xlink]. Multiplies k_on in the formation probability P_form = 1 - exp(-k_on*[xlink]*dtCheck). Higher concentration -> more formation -> larger steady-state link count. Set to 0 to suspend new formation (existing links still dissolve).");

	// Bell-model force/strain-dependent dissolution (PRE-EXISTING; surfaced as
	// tunable 2026-06-12). FilLink.ckLinkBreak fires every step:
	//   k_off = linkOffConst + linkOffCoeff*exp(aveStrain*linkOffExp);  P_break = k_off*deltaT.
	// aveStrain is the EWMA normalized stretch (force ∝ strain for the linear
	// link spring), so this is the force-dependent off-rate (k0=linkOffConst+coeff,
	// F_c sets the strain scale via 1/linkOffExp). Higher coeff/exp -> faster
	// force-driven turnover -> lower steady-state count under load.
	static final Parameter linkOffConst = new Parameter("linkOffConst"," FilLink Dissolution Constant - (C)", 1, "/s", Parameter.DOUBLE, true).setMutableAtRuntime().setDescription("Force-independent baseline off-rate (k0) for crosslink dissolution. P_break per step = (linkOffConst + linkOffCoeff*exp(aveStrain*linkOffExp))*deltaT. Higher -> faster spontaneous turnover -> lower steady-state link count.");
	static final Parameter linkOffCoeff = new Parameter("linkOffCoeff"," FilLink Dissolution Coeff. - (alpha)", 1, "/s", Parameter.DOUBLE, true).setMutableAtRuntime().setDescription("Force-dependent prefactor (alpha) of the Bell off-rate. Off-rate = linkOffConst + linkOffCoeff*exp(aveStrain*linkOffExp). Higher -> strained links rupture sooner.");
	static final Parameter linkOffExp = new Parameter("linkOffExp"," FilLink Dissolution Exp. - (beta)", 2, "", Parameter.DOUBLE, true).setMutableAtRuntime().setDescription("Strain sensitivity (beta) of the Bell off-rate exponential exp(aveStrain*beta). Effective rupture-force scale F_c ~ 1/beta: higher beta -> sharper, earlier force-driven rupture.");

	// **** Biochemical Rates ****
	// ** End1
	static boolean endsAreSame = false;

	static private final double kATPOn1_init = 1.3; // �M^-1 s^-1
	static private final double kATPOff1_init = 0.8; // s^-1
	// static private final double kADPOn1_init = 0.16; // �M^-1 s^-1
	static private final double kADPOff1_init = 2.7; // s^-1

	static final Parameter kATPOn1 = new Parameter("kATPOn1"," ATP Actin On Rate", kATPOn1_init, kOnUnits);
	static final Parameter kATPOff1 = new Parameter("kATPOff1"," ATP Actin Off Rate", kATPOff1_init, kOffUnits);
	// static final Parameter kADPOn1 = new Parameter (" ADP Actin On Rate",
	// kADPOn1_init, kOnUnits);
	static final Parameter kADPOff1 = new Parameter("kADPOff1"," ADP Actin Off Rate", kADPOff1_init, kOffUnits);

	// ** For Non-hydrolyzable Actin
	static private final double kATPOn1NonHydro_init = 0.0; // �M^-1 s^-1
	static private final double kATPOff1NonHydro_init = 0.0; // s^-1

	static final Parameter kATPOn1NonHydro = new Parameter("kATPOn1NonHydro"," ATP Actin On Rate - Non-hydrolyzable Actin",kATPOn1NonHydro_init, kOnUnits);
	static final Parameter kATPOff1NonHydro = new Parameter("kATPOff1NonHydro"," ATP Actin Off Rate - Non-hydrolyzable Actin",kATPOff1NonHydro_init, kOffUnits);

	// ** End2
	static private final double kATPOn2_init = 11.6; // �M^-1 s^-1
	static private final double kATPOff2_init = 1.4; // s^-1
	// static private final double kADPOn2_init = 3.8; // �M^-1 s^-1
	static private final double kADPOff2_init = 7.2; // s^-1

	static final Parameter kATPOn2 = new Parameter("kATPOn2"," ATP Actin On Rate", kATPOn2_init, kOnUnits);
	static final Parameter kATPOff2 = new Parameter("kATPOff2"," ATP Actin Off Rate", kATPOff2_init, kOffUnits);
	// static final Parameter kADPOn2 = new Parameter (" ADP Actin On Rate",
	// kADPOn2_init, kOnUnits);
	static final Parameter kADPOff2 = new Parameter("kADPOff2"," ADP Actin Off Rate", kADPOff2_init, kOffUnits);

	// ** If End Attached to Formin
	static private final double kATPOn2WithFormin_init = 11.6; // �M^-1 s^-1
	static private final double kATPOff2WithFormin_init = 1.4; // s^-1
	// static private final double kADPOn2WithFormin_init = 0.0; // �M^-1 s^-1
	static private final double kADPOff2WithFormin_init = 7.2; // s^-1

	static final Parameter kATPOn2WithFormin = new Parameter("kATPOn2WithFormin", " ATP Actin On Rate If Attached To Formin",kATPOn2WithFormin_init, kOnUnits);
	static final Parameter kATPOff2WithFormin = new Parameter("kATPOff2WithFormin", " ATP Actin Off Rate If Attached To Formin",kATPOff2WithFormin_init, kOffUnits);
	// static final Parameter kADPOn2WithFormin = new Parameter
	// ("kADPOn2WithFormin"," ADP Actin On Rate If Attached To Formin",
	// kADPOn2WithFormin_init, kOnUnits);
	static final Parameter kADPOff2WithFormin = new Parameter("kADPOff2WithFormin", " ADP Actin Off Rate If Attached To Formin",kADPOff2WithFormin_init, kOffUnits);

	// ** For Non-hydrolyzable Actin
	static private final double kATPOn2NonHydro_init = 0.0; // �M^-1 s^-1
	static private final double kATPOff2NonHydro_init = 0.0; // s^-1

	static final Parameter kATPOn2NonHydro = new Parameter("kATPOn2NonHydro"," ATP Actin On Rate - Non-hydrolyzable Actin",kATPOn2NonHydro_init, kOnUnits);
	static final Parameter kATPOff2NonHydro = new Parameter("kATPOff2NonHydro"," ATP Actin Off Rate - Non-hydrolyzable Actin",kATPOff2NonHydro_init, kOffUnits);

	// ** For Non-hydrolyzable Actin when attached to Formin
	static private final double kATPOn2NonHydroWithFormin_init = 0.0; // µM^-1	s^-1															
	static private final double kATPOff2NonHydroWithFormin_init = 0.0; // s^-1
	static final Parameter kATPOn2NonHydroWithFormin = new Parameter("kATPOn2NonHydroWithFormin"," ATP Actin On Rate - No-hydro/node attached",kATPOn2NonHydroWithFormin_init, kOnUnits);
	static final Parameter kATPOff2NonHydroWithFormin = new Parameter("kATPOff2NonHydroWithFormin"," ATP Actin Off Rate - No-hydro/node attached",kATPOff2NonHydroWithFormin_init, kOffUnits);

	// ** Capping by Cap protein
	static private final double capRate_init = 3.0; // µM^-1 s^-1
	static final Parameter capRate = new Parameter("capRate"," Cap rate by cap protein", capRate_init, kOnUnits);

	// ** Cofilin Binding and Dissolution
	static private final double cofilinRate_init = 0.1; // µM^-1 s^-1
	static final Parameter cofilinRate = new Parameter("cofilinRate"," Cofilin Binding Rate", cofilinRate_init, kOnUnits);

	static private final double cofilinConc_init = 3.0; // µM
	static final Parameter cofilinConc = new Parameter("cofilinConc"," Cofilin Concentration", cofilinConc_init, concUnits);

	static final Parameter cofilinRatio = new Parameter("cofilinRatio"," Cofilin Dissolve Ratio", 1.0, " ");
	
	
	// ** Tropomyosin Binding/Unbinding
	static private final double tropoOnRate_init = 1.0;  // µM^-1 s^-1
	static final Parameter tropoOnRate = new Parameter("tropmyosinOnRate", " Tropomyosin Binding Rate", tropoOnRate_init, kOnUnits);
	
	static private final double tropoOffRate_init = 0.0;  // µM^-1 s^-1
	static final Parameter tropoOffRate = new Parameter("tropmyosinOffRate", " Tropomyosin Unbinding Rate", tropoOffRate_init, kOnUnits);
	
	static private final double tropoConc_init = 1.0; // µM
	static final Parameter tropoConc = new Parameter ("tropomyosinConc", " Tropomyosin Concentration", tropoConc_init, concUnits);
	
	
	
	// **** Arp Related / Actin Branching ****
	static final Parameter branchRateNearArpFactors = new Parameter("branchRateNearArpFactors"," Arp2/3 Branch Rate Near Arp Factors", 0.1, "/second");
	static final Parameter arpConc = new Parameter ("arpConc", " Arp2/3 Concentration", 0, concUnits);
	static final Parameter branchZone = new Parameter("branchZone"," Arp2/3 Branch Zone Near Arp Factors", 0.05, distUnits);
	static final Parameter nucRateNearArpFactors = new Parameter("nucRateNearArpFactors"," Arp2/3 Nuc Rate Near Arp Factors", 0.1, "/second");
	// P2: stochastic Arp2/3 debranching. Per-branch dissociation rate, scaled by the daughter's aged
	// (ADP) fraction so newly-polymerized ATP/ADP-Pi branches are stable and old ones release (GMF-like).
	// On debranch the daughter becomes a free filament and depolymerizes. 0 = disabled (no turnover).
	static final Parameter arpDebranchRate = new Parameter("arpDebranchRate"," Arp2/3 Debranch Rate", 0.0, "/second").setMutableAtRuntime().setDescription("P2 debranching: stochastic dissociation rate of an Arp2/3 branch, scaled by the daughter filament's aged (ADP) fraction so new ATP/ADP-Pi branches are stable and old ones release (GMF-like turnover). On debranch the daughter is freed and depolymerizes. 0 = disabled (network only accretes, never treadmills). Live-tunable.");
	// Localized Arp2/3 depletion (negative feedback against autocatalytic over-branching). Each branch
	// consumes this much arpConc; it is returned when the branch dissociates (conserved pool). For a
	// single hot-Rho zone the global arpConc acts as the LOCAL pool, so standing branches are capped at
	// ~arpConc/this. Tunable because the physical box-wide pool (~millions of complexes at 80 uM) is far
	// too large to limit anything — what matters is the small, fast-depleting local hot-zone pool.
	static final Parameter arpConsumePerBranch = new Parameter("arpConsumePerBranch"," Arp2/3 consumed per branch", 0.0, concUnits).setMutableAtRuntime().setDescription("Localized Arp2/3 depletion: each branch consumes this much arpConc (uM), returned when it dissociates. Caps standing branches at ~arpConc/this, the negative feedback that stops a dendritic network running away into an over-branched bush. 0 = disabled. Live-tunable.");
	// ACTIVATED Arp2/3 field (NPF-source / bulk-sink model). When arpLocalField is on, each membrane node
	// carries arpLocal (uM activated Arp2/3). The hot-Rho (NPF) nodes are the ONLY source (production
	// toward arpConc); it diffuses laterally as a SLOW membrane-bound species (arpDiffusion ~ membrane-
	// protein scale, NOT the fast free complex); it is LOST everywhere at arpBulkExchange (escape/deactiv-
	// ation to the inactive bulk = sink at 0); and branching consumes it with no return. Branching is thus
	// activation-rate-limited. Free cytoplasmic Arp2/3 (~5 um^2/s -> arpDiffusion ~700) refills too fast to
	// deplete, which is exactly why the depletable resource is the slow membrane-bound ACTIVE pool.
	static final Parameter arpLocalField = new Parameter("arpLocalField"," Activated-Arp2/3 field (NPF source, bulk sink)", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("When on, branching is governed by a per-membrane-node ACTIVATED Arp2/3 field: hot-Rho/NPF nodes produce it toward arpConc, it diffuses laterally (slow, membrane-bound) and is lost to the bulk (sink at 0), and branching consumes the nearest node's pool with no return -> activation-rate-limited. Default off (global pool). Live-tunable.");
	static final Parameter arpDiffusion = new Parameter("arpDiffusion"," Activated-Arp2/3 lateral diffusion", 10.0, "/second").setMutableAtRuntime().setDescription("Effective lateral diffusion of the activated-Arp2/3 field over the NodeLink graph (graph-Laplacian; = D_phys/(1.5*spacing^2), so ~140*D_phys). Membrane-bound active pool: D_phys ~0.01-0.1 um^2/s -> ~1-14. Free complex (~5 um^2/s -> ~700) refills too fast to deplete. Decay length ~ sqrt(arpDiffusion/arpBulkExchange). Live-tunable.");
	static final Parameter arpBulkExchange = new Parameter("arpBulkExchange"," Activated-Arp2/3 loss/deactivation rate", 2.0, "/second").setMutableAtRuntime().setDescription("Rate the activated-Arp2/3 field is lost everywhere to the inactive bulk (sink at 0) -- deactivation + escape of activated complex to the deep cytoplasm. Sets the activated-pool lifetime (~1/this) and the decay length sqrt(arpDiffusion/this). Live-tunable.");
	// Extended branching: branch-eligible when the barbed end is within this distance BELOW the
	// membrane plane (z=0), in addition to the original near-hot-node trigger. >0 lets the dendritic
	// network self-amplify (daughters branch too) into a thick lamellipodium-like layer. 0 = disabled.
	static final Parameter branchMembraneDist = new Parameter("branchMembraneDist"," Arp2/3 Branch Zone Below Membrane (z)", 0.0, distUnits).setMutableAtRuntime().setDescription("If >0, a filament is Arp2/3 branch-eligible whenever its barbed end is within this distance below the membrane plane (z=0), ADDED to the original near-hot-node trigger. Extends branching away from the membrane surface so the network self-amplifies into a thick dendritic layer instead of only branching right at the cortex. 0 = disabled (original behavior). Live-tunable.");
	// Soft steric attenuation of polymerization: when a barbed end is sterically blocked (within a
	// half-monomer of a node/obstacle) its poly rate is *multiplied* by this instead of hard-stopped.
	static final Parameter stericPolyFactor = new Parameter("stericPolyFactor"," Steric Poly Attenuation Factor", 0.0, "").setMutableAtRuntime().setDescription("Polymerization-rate multiplier applied at a sterically blocked barbed end (within ~a half-monomer of a membrane node/obstacle). 0.0 = original hard stop (no growth into the obstacle); 0<f<1 = growth continues at reduced (Brownian-ratchet-like) rate; 1.0 = no steric effect on growth. Raise to let filaments keep polymerizing as they push the membrane. Live-tunable. IGNORED when ratchetOn is active (the ratchet closure replaces it).");
	// Brownian-ratchet polymerization closure (Mogilner-Oster). Replaces the flat stericPolyFactor with a
	// gap-deficit gate: a barbed end polymerizes freely if it has a full monomer of clearance (g>=delta),
	// else at exp(-f*(delta-g)/kT) where g=end2TipC and f=ratchetForce (the membrane-normal load). See
	// RATCHET_CLOSURE_DESIGN.{html,pdf}. ratchetForce is currently a representative per-tip load (a
	// constant); reading the actual per-tip membrane reaction is the documented upgrade.
	static final Parameter ratchetOn = new Parameter("ratchetOn"," Brownian-ratchet poly gating", 0, "", Parameter.BOOLEAN, false).setMutableAtRuntime().setDescription("When on, polymerization at the barbed end uses the Mogilner-Oster ratchet closure instead of stericPolyFactor: free rate when a full monomer fits in the gap to the membrane (g=end2TipC >= delta), else multiplied by exp(-ratchetForce*(delta-g)/kT). An existing gap raises the rate; load slows it. Live-tunable.");
	static final Parameter ratchetForce = new Parameter("ratchetForce"," Ratchet membrane-normal load", 1e-12, "N").setMutableAtRuntime().setDescription("The membrane-normal load f on a barbed end in the ratchet closure exp(-f*(delta-g)/kT). ~kT/delta = 1.5 pN sets a characteristic stall scale; larger f = stronger force-velocity slowdown as the tip presses the membrane. Default 1e-12 N (1 pN). Currently a representative constant load; the rigorous version reads the per-tip membrane reaction force. Live-tunable.");
	// Membrane-localized capping (lamellipodium rule): if >0, a barbed end is UNCAPPED whenever its
	// tip clearance to a membrane node is below this distance (at/colliding with the membrane), and
	// AGGRESSIVELY CAPPED whenever it is farther — so only barbed ends at the cortex keep growing,
	// keeping the dendritic network a thin layer tracking the membrane. 0 = disabled (stochastic
	// capping protein, original behavior).
	static final Parameter membraneCapDist = new Parameter("membraneCapDist"," Membrane Uncapping Distance", 0.0, distUnits).setMutableAtRuntime().setDescription("Lamellipodial capping rule. If >0: a filament barbed end is uncapped (free to grow/branch) when its clearance to the nearest membrane node is below this distance (touching/near the cortex), and is capped otherwise. This localizes growth to the membrane — filaments that fall behind the advancing cortex get capped and stop, those reaching it get uncapped. 0 = disabled (original stochastic capProtein capping). Live-tunable.");
	// Membrane FACE collision: collide filament tips with the membrane's triangular FACES (node + two
	// mutually-linked neighbours) instead of just the point-nodes, so a stretched sheet stays
	// impermeable (tips can't slip through open triangle interiors). 0 = legacy point-node collision.
	static final Parameter membraneFaceCollideOn = new Parameter("membraneFaceCollideOn"," Membrane Face (Triangle) Collision On", 0.0, "").setMutableAtRuntime().setDescription("If !=0, filament tips collide with the membrane's triangular faces (point-vs-triangle, soft steric push distributed to the 3 face nodes) rather than only the point-nodes. This makes the sheet an impermeable surface even when stretched, decoupling compliance (soft NodeLink springs) from coverage. 0 = legacy point-node sphere collision. Live-tunable.");
	// Membrane in-plane stiffness: the NodeLink (zero-rest-length contractile spring) correction
	// fraction. Lower = softer/more compliant sheet (stretches more under load); was hardcoded 2.0.
	static final Parameter membraneLinkFracMove = new Parameter("membraneLinkFracMove"," Membrane Link Stiffness (Correction Fraction)", 2.0, "").setMutableAtRuntime().setDescription("Per-step correction fraction of the NodeLink membrane springs (NodeLink.applyTransForce). 2.0 = original stiff sheet; lower (e.g. 0.5) makes the membrane more compliant/stretchy so it billows under filament load. Pair with membraneFaceCollideOn so the stretched sheet stays impermeable. Live-tunable.");

	// ** Myosin
	static final Parameter myoRodLength = new Parameter("myoRodLength"," Myosin rod length", 0.080, distUnits);
	static final Parameter myoLeverLength = new Parameter("myoLeverLength"," Myosin lever length", 0.008, distUnits);
	static final Parameter myoMotorLength = new Parameter("myoMotorLength"," Myosin motor length", 0.020, distUnits);
	
	// Bind-orientation gate (cos). Runtime-mutable: CPU bind reads getValue() each
	// step; on -gpu it is baked into gridParams at FIRST_EXECUTION, so a mid-run
	// change takes effect on restart (the contractility tuning apparatus runs CPU,
	// where it is fully live).
	static final Parameter myoMotorAlignWithFilTolerance = new Parameter("myoMotorAlignWithFilTolerance"," Myosin motor alignment with filament tolerance for binding", -0.4, "cos(radians)").setMutableAtRuntime().setDescription(
		"Bind orientation gate: the cosine of the angle between the head axis and the filament axis. A head binds only if their dot product is at least this value. Default -0.4 (allows up to ~114 degrees of misalignment, fairly permissive). Toward +1: stricter co-alignment, fewer binds; toward -1: almost any orientation binds. On -gpu, applies on restart.");

	// Cross-bridge spring stiffness (force per unit head–attachment strain). Was a
	// hardcoded constant in MyoFilLink (1e-9 N/µm); promoted to a tunable so the
	// ensemble tension scale can be dialed. Read fresh CPU-side and packed into the
	// device motorForceParams EVERY_EXECUTION -> live on both paths.
	static private final double myoSpring_init = 1.0e-9; // N/µm
	static final Parameter myoSpring = new Parameter("myoSpring"," Myosin cross-bridge spring stiffness", myoSpring_init, "N/µm").setMutableAtRuntime().setDescription(
		"Cross-bridge spring stiffness. A bound head acts as a Hookean spring between its tip and its actin attachment point: force = strain * myoSpring. Default 1e-9 N/µm. Higher: more force per nm of strain, but the head reaches the break-force threshold at smaller strain and snaps off sooner, so raising this without also raising break force can REDUCE net tension.");

	static private final double myosinStallForce_init = 6.0; // pN
	static final Parameter myosinStallForce = new Parameter("myosinStallForce"," Single-head Myosin Stall Force", myosinStallForce_init, "pN").setMutableAtRuntime().setDescription(
		"Caps the per-head lever-to-motor joint torque on the GPU path (max torque scales as stall force * motor length). Default 6 pN. Limits how forcefully the cocked lever can swing the head into alignment. Note: not used by the CPU release path; it is primarily a GPU joint clamp.");

	static private final double myosinBreakForce_init = 12.0; // pN			// use this to prevent stiffness?
	static final Parameter myosinBreakForce = new Parameter("myosinBreakForce"," Single-head Myosin Break Force", myosinBreakForce_init, "pN").setMutableAtRuntime().setDescription(
		"Hard detachment threshold. If the cross-bridge force exceeds this value, the head releases immediately, a stiffness safety valve independent of the catch-slip roll. Default 12 pN. Lower: heads break off under modest load (caps peak tension); higher: heads hold larger forces.");

	// values for Guo&Guilford catch/slip probability calculations (see Stam et al 2015)
	// All read fresh in MyoFilLink.ckRelease() each step (release runs CPU-side on
	// both paths), so they are cleanly runtime-mutable.
	static private final double alphaCatch_init = 0.92;
	static final Parameter alphaCatch = new Parameter("alphaCatch"," Alpha_Catch for force-based myosin release", alphaCatch_init, " ").setMutableAtRuntime().setDescription(
		"Relative weight of the catch (load-stabilized) pathway in the catch-slip release sum. Default 0.92 (catch-dominated). Higher: detachment more load-resistant, so heads live longer under stabilizing load. See kOff for the full release formula.");

	static private final double alphaSlip_init = 0.08;
	static final Parameter alphaSlip = new Parameter("alphaSlip"," Alpha_Slip for force-based myosin release", alphaSlip_init, " ").setMutableAtRuntime().setDescription(
		"Relative weight of the slip (load-accelerated) pathway in the catch-slip release sum. Default 0.08. Higher: heads more readily pulled off by load. See kOff for the full release formula.");

	static private final double xCatch_init = 2.5e-9;
	static final Parameter xCatch = new Parameter("xCatch"," X_Catch for force-based myosin release", xCatch_init, "m").setMutableAtRuntime().setDescription(
		"Force-sensitivity distance of the catch (load-stabilized) term, which scales as exp(-Fpar*xCatch/kT) and shrinks as the along-filament force grows. Default 2.5e-9 m. Larger: stronger load-stabilization, longer attachment in the stabilizing-force regime. (Guo and Guilford 2006; Stam et al 2015.)");

	static private final double xSlip_init = 0.4e-9;
	static final Parameter xSlip = new Parameter("xSlip"," X_Slip for force-based myosin release", xSlip_init, "m").setMutableAtRuntime().setDescription(
		"Force-sensitivity distance of the slip (load-accelerated) term, which scales as exp(+Fpar*xSlip/kT) and grows with the along-filament force. Default 0.4e-9 m. Larger: load pulls heads off faster. With xCatch and kOff this sets the biphasic, load-dependent attachment lifetime.");

	static private final double kOff_init = 100;
	static final Parameter kOff = new Parameter("kOff"," kOff for force-based myosin release", kOff_init, "  ").setMutableAtRuntime().setDescription(
		"Base detachment rate for the load-dependent Guo-Guilford catch-slip release. Per-step release probability = kOff*dt*[alphaCatch*exp(-Fpar*xCatch/kT) + alphaSlip*exp(+Fpar*xSlip/kT)], where Fpar is the along-filament force component. Default 100 per second. Higher: heads detach faster overall, shortening attachment lifetime and lowering duty ratio, processivity, and sustained tension.");

	static private final double myoColTol_init = 0.006; //µm
	// Bind capture radius. Runtime-mutable (CPU live each step; -gpu baked at
	// FIRST_EXECUTION -> takes effect on restart).
	static final Parameter myoColTol = new Parameter("myoColTol"," Myosin motor collision tolerance", myoColTol_init, distUnits).setMutableAtRuntime().setDescription(
		"Bind capture radius. A myosin head binds a filament when its tip falls within this perpendicular distance of the filament axis (and the alignment gate passes). Default 0.006 µm (6 nm). Larger: heads capture from farther away, so binding is faster and more frequent; smaller: near-contact required. On -gpu this is baked at first execution, so a change applies on restart.");

	static private final double myoRebindTime_init = 1e-5; // s
	static final Parameter myoRebindTime = new Parameter("myoRebindTime"," Myosin motor rebind time", myoRebindTime_init, "s");

	// State-change rates (shaded path from Howard 2001, Table 14-2)
	static private final double atpOnMyo_init = 2e4; // s^-1
	static final Parameter atpOnMyo = new Parameter("atpOnMyo"," Myosin ATP On rate: on/off-filament", atpOnMyo_init, " ");

	static private final double myoOnFilATP_ADPPi_init = 100.0; // s^-1
	static final Parameter myoOnFilATP_ADPPi = new Parameter("myoOnFilATP_ADPPi", " Myosin ATP-ADPPi rate: on-filament",myoOnFilATP_ADPPi_init, " ");

	static private final double myoOnFilADPPi_ADP_init = 1e4; // s^-1
	static final Parameter myoOnFilADPPi_ADP = new Parameter("myoOnFilADPPi_ADP", " Myosin ADPPi-ADP rate: on-filament",myoOnFilADPPi_ADP_init, " ");

	static private final double myoOnFilADP_None_init = 1e3; // s^-1
	static final Parameter myoOnFilADP_None = new Parameter("myoOnFilADP_None"," Myosin ADP Off rate: on-filament", myoOnFilADP_None_init, " ");

	static private final double myoOffFilATP_ADPPi_init = 100.0; // s^-1
	static final Parameter myoOffFilATP_ADPPi = new Parameter("myoOffFilATP_ADPPi", " Myosin ATP-ADPPi rate: off-filament",myoOffFilATP_ADPPi_init, " ");

	static private final double myoOffFilADPPi_ADP_init = 0;// 0.1; // s^-1
	static final Parameter myoOffFilADPPi_ADP = new Parameter("myoOffFilADPPi_ADP", " Myosin ADPPi-ADP rate: off-filament",myoOffFilADPPi_ADP_init, " ");

	static private final double myoOffFilADP_None_init = 1e3; // s^-1
	static final Parameter myoOffFilADP_None = new Parameter("myoOffFilADP_None", " Myosin ADP Off rate: off-filament",myoOffFilADP_None_init, " ");

	// ** Parameters for Gliding Assay
	static private final double fixedMyosinDensity_init = 400; // myosins per sq. micron fixed to surface
	static final Parameter fixedMyosinDensity = new Parameter("fixedMyosinDensity", " Fixed Myosin Density on Surface",fixedMyosinDensity_init, "/µm^2");

	static private final double fixedMyosinZValue_init = -0.05; // position of fixed myosin tail end1AsPt3D()
	static final Parameter fixedMyosinZValue = new Parameter("fixedMyosinZValue", " Fixed Myosin Z-value of Rod End1",fixedMyosinZValue_init, "µm");

	static private final double glidingFilamentLength_init = 1.0; // length of test filament
	static final Parameter glidingFilamentLength = new Parameter("glidingFilamentLength"," Length of Actin Filament In Gliding Assay",glidingFilamentLength_init, "µm");

	static private final double glidingFilamentForce_init = 1; // force resisting gliding filament motion
	static final Parameter glidingFilamentForce = new Parameter("glidingFilamentForce", " Force Working Against Gliding Motion",glidingFilamentForce_init, "pN");

	// ** Single-myosin diagnostic mode (2026-05-31 pivot from per-step joint forensics).
	// When active, makeInitialThings() creates exactly one MyosinFixed at the box centre,
	// anchored at z=fixedMyosinZValue, pointing (0,0,1), with no filaments. Brownian + joints
	// run normally. SingleMyoDiag accumulates the conformational ensemble.
	static final Parameter singleMyoDiag = new Parameter("singleMyoDiag", " Single-myosin thermal characterization mode", 0, "", Parameter.BOOLEAN, false);

	// ** Polymerization Criteria
	static private final int capNumEnd1_init = 3;
	static final Parameter capNumEnd1 = new Parameter("capNumEnd1"," Cap at End1", capNumEnd1_init, "atp monomers", Parameter.INT,false);

	static private final int capNumEnd2_init = 3;
	static final Parameter capNumEnd2 = new Parameter("capNumEnd2"," Cap at End2", capNumEnd2_init, "atp monomers", Parameter.INT,false);

	static private final int capNumEnd1WithNode_init = 3;
	static final Parameter capNumEnd1WithNode = new Parameter("capNumEnd1WithNode", " Cap at End1 with node",capNumEnd1WithNode_init, "atp monomers", Parameter.INT, false);

	static private final int capNumEnd2WithNode_init = 3;
	static final Parameter capNumEnd2WithNode = new Parameter("capNumEnd2WithNode", " Cap at End2 with node",capNumEnd2WithNode_init, "atp monomers", Parameter.INT, false);

	static final Parameter polyCompressionCutoff = new Parameter("polyCompressionCutoff", " Compressive force that stops poly.", 0,"N (<= 0)");

	// ** Nucleation
	static private final double kRdmNuc_init = 0.0; // s^-1
	static final Parameter kRdmNuc = new Parameter("kRdmNuc"," Random Actin Nucleation Rate", kRdmNuc_init, "/µM^seed s",Parameter.DOUBLE, false);

	static private final double kNodeNuc_init = 10.0;
	static final Parameter kNodeNuc = new Parameter("kNodeNuc"," On Node Actin Nucleation By Formin", kNodeNuc_init, "/node-s",Parameter.DOUBLE, true);
	static final boolean nucVectorAlignedMyosins = false;
	static final boolean twoForminsOpposite = false;

	// ** Hydrolysis
	static private final double kHydro_init = 0.3;  
	static private final double kDissoc_init = 1;

	static final Parameter kHydrolysis = new Parameter("kHydrolysis"," Monomer Hydrolysis Rate", kHydro_init, kOffUnits);
	static final Parameter kDissociation = new Parameter("kDissociation"," Monomer Dissociation Rate", kDissoc_init, kOffUnits);

	// **** Show Stats Parameters ****
	static final Parameter showTime = new Parameter("showTime", " Show Time",0, "", Parameter.BOOLEAN);
	static final Parameter showConc = new Parameter("showConc"," Show Actin Conc", 0, "", Parameter.BOOLEAN);
	static final Parameter showNonHydroConc = new Parameter("showNonHydroConc"," Show Non-Hydro Actin Conc", 0, "", Parameter.BOOLEAN, false);
	static final Parameter showFilCt = new Parameter("showFilCt"," Show Filament Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showFilLinkCt = new Parameter("showFilLinkCt"," Show FilSegment Link Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showFilSegCt = new Parameter("showFilSegCt"," Show FilSegment Count", 0, "", Parameter.BOOLEAN, false);
	static final Parameter showMonCt = new Parameter("showMonCt"," Show Monomer Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showMyoCt = new Parameter("showMyoCt"," Show Myosin Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showArp23Ct = new Parameter("showArp23Ct"," Show Arp2/3 Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showProteinNodeCt = new Parameter("showProteinNodeCt"," Show Protein Node Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showMyoMiniCt = new Parameter("showMyoMiniCt"," Show Myosin Minifilament Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showActACt = new Parameter("showActACt"," Show ActA Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showActABoundCt = new Parameter("showActABoundCt"," Show ActABound Count", 0, "", Parameter.BOOLEAN);
	static final Parameter showBugDragScale = new Parameter("showBugDragScale"," Show Bug Drag Scale", 0, "", Parameter.BOOLEAN);

	
	// **** Filament Length Histogram Parameters ****
	static private final double rStart_init = 0; // (micron) start of range for tracking filament lengths
	static private final double rStop_init = 1; // (micron) end of range for tracking filament lengths
	static private final int binCt_init = 100; // number of bins in histogram
	static private final int histWidth_init = 200;
	static private final int histHeight_init = 200;
	static private final int histX_init = 0;
	static private final int histY_init = 0;
	static private final int fixedHistScale_init = 100; // default value using fixed vertical scale in filament length histogram

	static final Parameter fixedHistScale = new Parameter("fixedHistScale"," Fixed Vertical Scale", fixedHistScale_init, " filaments",Parameter.DOUBLE, false);
	static final Parameter showHistBox = new Parameter("showHistBox"," Show Box", 0, "", Parameter.BOOLEAN);
	static final Parameter showYHistTics = new Parameter("showYHistTics"," Show Verical Scale", 0, "", Parameter.BOOLEAN);
	static final Parameter flatTops = new Parameter("flatTops"," Flat Tops on Bins", 0, "", Parameter.BOOLEAN);


	// **** Simularium/JSon *****
	static final boolean lmAsFiberForJSon = true; // render LM as fiber
	static final int simJSonSavesPerSec = 10;	// number of jSon states for Simularium saved per second
	static final int simJSonPlotSavesPerSec = 1; // number of plot data points per second
	static final int simJSon2SavesPerSec = 10000;	// number of jSon states for Simularium saved per second for second finer scale file
	static int simJSonFreq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSonSavesPerSec))); 	// number of integration steps between Simularium jSon saves
	static int simJSonPlotFreq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSonPlotSavesPerSec))); 	// number of integration steps between Simularium jSon saves
	static int simJSon2Freq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSon2SavesPerSec))); 	// number of integration steps between Simularium jSon saves for second file
	static double simJSon2Start = 100.0; // start time for jSon2 writes, in sec
	static double simJSon2Stop	= 100.2;	// stop time for jSon2 writes
	static int simJSon2StartCounting = (int)(simJSon2Start/deltaT.getValue() - simJSon2Freq);  // integration step at which to reset counters for valid first counting
	static boolean writeSimJSons = false;		// flag for writing Simularium style output or not
	static boolean writeSimJSons2 = false;		// flag for writing second JSon Simularium style output or not
	static String threeJSOutputDir = null;		// directory for Three.js per-frame JSON output; null = disabled
	static int    threeJSLivePort  = -1;		// WebSocket port for live frame streaming; -1 = disabled
	static final java.util.concurrent.ConcurrentLinkedQueue<Integer> inspectQueue =
		new java.util.concurrent.ConcurrentLinkedQueue<>();  // C2: pending click-to-inspect IDs from viewer

	/** C4: a validated, ready-to-apply parameter change queued by LiveFrameServer. */
	static class PendingParamChange {
		final Parameter param;
		final double newValue;
		PendingParamChange(Parameter param, double newValue) {
			this.param = param;
			this.newValue = newValue;
		}
	}
	/** C4: pending mid-run parameter changes from WebSocket setParam actions. */
	static final java.util.concurrent.ConcurrentLinkedQueue<PendingParamChange> paramQueue =
		new java.util.concurrent.ConcurrentLinkedQueue<>();
	static String jSonFileName = "coarse";
	static String jSon2FileName = "fine";
	static double simJSonsScale = 20.0;			// scale all output numbers for better Simularium rendering
	static final double iCallThatClose = 2*actinMonoRadius;
	
	// **** EVENT VARIABLES ***
	// for coarse JSon file
	static int newTetherCt = 0;				// number of new tethers formed
	static int cutTetherCt = 0;				// number of tethers cut
	static int newArpCt = 0;				// number of new Arp2/3s
	static double avePathForceInx = 0;		// average path force on listeria
	static double avePathForceWBrownianInx = 0;		// average path force on listeria including the thermal forces
	static double aveColForceInx = 0;		// used with tipsOnLisSphere to figure relation between barbed-ends and propulsive force
	static double aveFricForceInx = 0;		// used with closeTipCt to compare fric. relations with PDE model
	static double aveNormForce = 0;			// sums normal forces over a reporting interval
	static double aveLinkForceInx = 0;		// sums link forces over a reporting interval
	static int aveLinkCt = 0;				// sum active ActA links
	static int polyPrettyClose = 0;			// actin polymerization close to the bacterial surface
	static int tipsPrettyClose = 0;			// actin tips (polymerizing or not)
	static int arpsPrettyClose = 0;			// arp2/3s 
	
	// for fine JSon file (jSon2)
	static int newTetherCt2 = 0;			// number of new tethers formed
	static int cutTetherCt2 = 0;			// number of tethers cut
	static int newArpCt2 = 0;				// number of new Arp2/3s
	static double avePathForceInx2 = 0;		// no thermal forces
	static double avePathForceWBrownianInx2 = 0;		// average path force on listeria including the thermal forces
	static double aveColForceInx2 = 0;		// used with tipsOnLisSphere to figure relation between barbed-ends and propulsive force
	static double aveFricForceInx2 = 0;		// used with closeTipCt to compare fric. relations with PDE model
	static double aveNormForce2 = 0;		// sums normal forces over a reporting interval
	static double aveLinkForceInx2 = 0;		// sums link forces over a reporting interval
	static int aveLinkCt2 = 0;				// sum active ActA links
	static int polyPrettyClose2 = 0;		// actin polymerization close to the bacterial surface
	static int tipsPrettyClose2 = 0;		// actin tips (polymerizing or not)
	static int arpsPrettyClose2 = 0;		// arp2/3s 
	
	
	// **** 3D Quality ****
	static int graphicsQuality = 0; // specify graphics quality
	static final int LOW_QUALITY = 0;
	static final int MID_QUALITY = 1;
	static final int HIGH_QUALITY = 2;

	static final int lowSphereTessalation = 10; // number of polygons to define
												// a sphere
	static final int midSphereTessalation = 18;
	static final int highSphereTessalation = 24;

	static final int lowCylXTessalation = 10; // x and y resolution for
												// cylinders
	static final int lowCylYTessalation = 10;
	static final int midCylXTessalation = 18;
	static final int midCylYTessalation = 18;
	static final int highCylXTessalation = 24;
	static final int highCylYTessalation = 24;

	// filament appearance
	static boolean filamentLines = true;
	static boolean filamentCyls = false;

	// monomer appearance
	static boolean eachMonomerASphere = false;
	static boolean eachMonomerADot = false;

	// **** GUI AND COLOR RELATED ****
	static final Color universeColor = Color.BLACK;
	static final Color cellShadeColor = Color.BLACK;
	static final Font controlFont = new Font(null, Font.PLAIN, 10);
	static final Font headFont = new Font(null, Font.BOLD, 10);
	static final Font text2DFont = new Font(null, Font.PLAIN, 18);
	static final Font elasticityFont = new Font(null, Font.BOLD, 12);
	static final Color controlForeColor = new Color(.8f, .8f, .8f);
	static final Color controlFlashColor = Color.WHITE;

	public static void resetEventCounters () {
		avePathForceInx = 0;			// no thermal forces
		avePathForceWBrownianInx = 0;	// with thermal forces
		aveColForceInx = 0;				// average collision force in path direction
		aveFricForceInx = 0;			// ave. fric force
		aveNormForce = 0;
		aveLinkForceInx = 0;
		aveLinkCt = 0;
		newArpCt = 0;					// number of new Arp2/3s
		newTetherCt = 0;				// number of new tethers formed
		cutTetherCt = 0;				// number of tethers cut
		polyPrettyClose = 0;
		tipsPrettyClose = 0;
		arpsPrettyClose = 0;
	}
		
	public static void resetEventCounters2 () {	
		avePathForceInx2 = 0;			// no thermal forces
		avePathForceWBrownianInx2 = 0;	// with thermal forces
		aveColForceInx2 = 0;			// average collision force in path direction
		aveFricForceInx2 = 0;			// ave. fric force
		aveNormForce2 = 0;
		aveLinkForceInx2 = 0;
		aveLinkCt2 = 0;
		newArpCt2 = 0;					// number of new Arp2/3s
		newTetherCt2 = 0;				// number of new tethers formed
		cutTetherCt2 = 0;				// number of tethers cut
		polyPrettyClose2 = 0;
		tipsPrettyClose2 = 0;
		arpsPrettyClose2 = 0;
	}
	
	public static void registerArp(FilSegment rod) {
		if (rod.end2TipC < Env.iCallThatClose) { Env.arpsPrettyClose ++; Env.arpsPrettyClose2++;} // for Simularium graphing
		Env.newArpCt ++;
		Env.newArpCt2 ++;
	}
	
	public static void registerPlusMon(double tipC) {
		if (tipC < Env.iCallThatClose) { Env.polyPrettyClose ++; Env.polyPrettyClose2++; } 
	}
	
	public static void registerCloseTip(double tipC) {
		if (tipC < Env.iCallThatClose) { Env.tipsPrettyClose++; Env.tipsPrettyClose2++; } 
	}
	
	public static void registerNewTether() {
		Env.newTetherCt ++;
		Env.newTetherCt2 ++;
	}
	
	public static void registerCutTether() {
		Env.cutTetherCt ++;
		Env.cutTetherCt2 ++;
	}
	
	public static void addPathForceInx (double val) {
		Env.avePathForceInx+=val;
		Env.avePathForceInx2+=val;
	}
	
	public static void addPathForceWBrownianInx (double val) {
		Env.avePathForceWBrownianInx+=val;
		Env.avePathForceWBrownianInx2+=val;
	}
	
	public static void addColForceInx (double val) {
		Env.aveColForceInx+=val;
		Env.aveColForceInx2+=val;
	}
	
	public static void addFricForceInx (double val) {
		Env.aveFricForceInx+=val;
		Env.aveFricForceInx2+=val;
	}
	
	public static void addNormForce (double val) {
		Env.aveNormForce+=val;
		Env.aveNormForce2+=val;
	}
	
	public static void addLinkForce (double val) {
		Env.aveLinkForceInx+=val;
		Env.aveLinkCt++;	// increment active link counter
		
		Env.aveLinkForceInx2+=val;
		Env.aveLinkCt2++;	// increment active link counter
	}
	
	public static void setTimeStepCounts() {
		Thing.biochemCheckInt = (int) (Env.biochemDeltaT.getValue() / Env.deltaT.getValue());
		Thing.collisionCheckInt = (int) (Env.collisionDeltaT.getValue() / Env.deltaT.getValue());
		Thing.crosslinkCheckInt = Env.crosslinkDeltaT.isActive()
			? Math.max(1, (int) (Env.crosslinkDeltaT.getValue() / Env.deltaT.getValue()))
			: Thing.biochemCheckInt;   // default: formation rides the biochem cadence
		Thing.brownianApplyInt = 1;   // Brownian applied every step (brownianDeltaT == deltaT)
		
		simJSonFreq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSonSavesPerSec))); 	// number of integration steps between Simularium jSon saves
		simJSon2Freq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSon2SavesPerSec))); 	// number of integration steps between Simularium jSon saves for second file
		simJSon2StartCounting = (int)(simJSon2Start/deltaT.getValue() - simJSon2Freq);  // integration step at which to reset counters for valid first counting
	}
	
	public static void setDependencies() {
		if (noMonomersSimd.isActive()) { noMonomersRendered.setActive(true); }
		if (!noMonomersRendered.isActive() & !remote) {  // set single flag for logical simplicity
			monomerGraphics = true;
		} else {
			monomerGraphics = false;
		}
		
		// Viscous-blob drag tensor rebuild — removed 2026-05-17 (Round 7); see JOURNAL.md.
		// double N = 1.0/Env.nVBlobPerBug.getIntValue();
		// blobTransGam = new Pt3D(N*bTransGamViscBlob, N*bTransGamViscBlob, N*bTransGamViscBlob);
		// blobRotGam = new Pt3D(N*bRotGamViscBlob, N*bRotGamViscBlob, N*bRotGamViscBlob);
		
		simJSonFreq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSonSavesPerSec))); 	// number of integration steps between Simularium jSon saves
		simJSon2Freq = (int)(Math.ceil((1.0/deltaT.getValue())*(1.0/simJSon2SavesPerSec))); 	// number of integration steps between Simularium jSon saves for second file
		simJSon2StartCounting = (int)(simJSon2Start/deltaT.getValue() - simJSon2Freq);  // integration step at which to reset counters for valid first counting
	}

}
