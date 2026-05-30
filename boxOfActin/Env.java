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

	// **** Times and Counters
	static private final double deltaT_init = 1e-4; // seconds
	static private final double biochemDeltaT_init = 1e-3; // seconds
	static private final double collisionDeltaT_init = 1e-4; // seconds
	static private final double brownianDeltaT_init = deltaT_init;
	static double simStartTime = 0.0;	// if we need some pre-zero time for sim. to initialize
	static double runBump = 0.001; // a chunk of time to make sure round-off doesn't screw us out of the last JSon write... that format is picky with commas
	static double linkMembraneTime = 0.0;
	static double simulationTime = simStartTime; // keeps track of current time
	static private double runTime_init = 120.0; // final time to run to
	static int counter = 0; // keeps track of current integration step number

	static final Parameter deltaT = new Parameter("deltaT", " Time Step",deltaT_init, "seconds");
	static final Parameter biochemDeltaT = new Parameter("biochemDeltaT"," Time Step For Biochemical Events", biochemDeltaT_init, "seconds");
	static final Parameter collisionDeltaT = new Parameter("collisionDeltaT"," Time Step For Collison Detection", collisionDeltaT_init,"seconds");
	static final Parameter brownianDeltaT = new Parameter("brownianDeltaT"," Time Step at which to apply Brownian forces",brownianDeltaT_init, "seconds");
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
	static boolean xLinkTesting = false;
	static boolean nodeLinkTesting = false;
	static boolean myosinsOff = false;
	static boolean randomNodesOn = false;
	static final Parameter fixedMyosinClusters = new Parameter("fixedMyosinClusters", " Fixed Myosin Cluster Test", 0, "", Parameter.BOOLEAN, false);
	static final Parameter glidingAssay = new Parameter("glidingAssay"," Filament Gliding Assay", 0, "", Parameter.BOOLEAN, false);
	static final Parameter externalDensitySweep = new Parameter("externalDensitySweep", " External density sweep (disables glidingAssayDataSetRun)", 0, "", Parameter.BOOLEAN, false);
	static final Parameter nodeGrowthPerStep = new Parameter("nodeGrowthPerStep"," Node Growth (testing)", 0.001, "microns", Parameter.DOUBLE);

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

	static private final double nodeRotDiff_init = Boltz*tempK / (8*Math.PI*aeta.getValue()*Math.pow(1.0e-6*nodeRadius.getValue(),3));
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

	// **** Membrane ****
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
	static private final double filTorqSpring_init = 1e-20; // N/radian If using elastic springs for torque to keep filaments aligned
															
	static final Parameter filTorqSpring = new Parameter("filTorqSpring"," Filament Torsional Spring", filTorqSpring_init, " N/radian",Parameter.DOUBLE, false);

	static private double BTransCoeff_init = 1; // range from zero (no brownian motion) to infinity
	static final Parameter BTransCoeff = new Parameter("BTransCoeff"," Brownian Translational Coefficient", BTransCoeff_init, "").setMutableAtRuntime();

	static private double BRotCoeff_init = 0.5; // range from zero (no brownian motion) to infinity
	static final Parameter BRotCoeff = new Parameter("BRotCoeff"," Brownian Rotational Coefficient", BRotCoeff_init, "").setMutableAtRuntime();

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

	static final Parameter linkOffConst = new Parameter("linkOffConst"," FilLink Dissolution Constant - (C)", 1, "/s");
	static final Parameter linkOffCoeff = new Parameter("linkOffCoeff"," FilLink Dissolution Coeff. - (alpha)", 1, "/s");
	static final Parameter linkOffExp = new Parameter("linkOffExp"," FilLink Dissolution Exp. - (beta)", 2, "");

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

	// ** Myosin
	static final Parameter myoRodLength = new Parameter("myoRodLength"," Myosin rod length", 0.080, distUnits);
	static final Parameter myoLeverLength = new Parameter("myoLeverLength"," Myosin lever length", 0.008, distUnits);
	static final Parameter myoMotorLength = new Parameter("myoMotorLength"," Myosin motor length", 0.020, distUnits);
	
	static final Parameter myoMotorAlignWithFilTolerance = new Parameter("myoMotorAlignWithFilTolerance"," Myosin motor alignment with filament tolerance for binding", -0.4, "cos(radians)");

	static private final double myosinStallForce_init = 6.0; // pN
	static final Parameter myosinStallForce = new Parameter("myosinStallForce"," Single-head Myosin Stall Force", myosinStallForce_init, "pN");

	static private final double myosinBreakForce_init = 12.0; // pN			// use this to prevent stiffness?
	static final Parameter myosinBreakForce = new Parameter("myosinBreakForce"," Single-head Myosin Break Force", myosinBreakForce_init, "pN");
	
	// values for Guo&Guilford catch/slip probability calculations (see Stam et al 2015)
	static private final double alphaCatch_init = 0.92; 			
	static final Parameter alphaCatch = new Parameter("alphaCatch"," Alpha_Catch for force-based myosin release", alphaCatch_init, " ");
		
	static private final double alphaSlip_init = 0.08; 			
	static final Parameter alphaSlip = new Parameter("alphaSlip"," Alpha_Slip for force-based myosin release", alphaSlip_init, " ");
	
	static private final double xCatch_init = 2.5e-9; 			
	static final Parameter xCatch = new Parameter("xCatch"," X_Catch for force-based myosin release", xCatch_init, "m");
	
	static private final double xSlip_init = 0.4e-9; 			
	static final Parameter xSlip = new Parameter("xSlip"," X_Slip for force-based myosin release", xSlip_init, "m");
	
	static private final double kOff_init = 100; 			
	static final Parameter kOff = new Parameter("kOff"," kOff for force-based myosin release", kOff_init, "  ");

	static private final double myoColTol_init = 0.006; //µm
	static final Parameter myoColTol = new Parameter("myoColTol"," Myosin motor collision tolerance", myoColTol_init, distUnits);

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

	static private final double fixedMyosinZValue_init = -0.05; // position of fixed myosin tail end1
	static final Parameter fixedMyosinZValue = new Parameter("fixedMyosinZValue", " Fixed Myosin Z-value of Rod End1",fixedMyosinZValue_init, "µm");

	static private final double glidingFilamentLength_init = 1.0; // length of test filament
	static final Parameter glidingFilamentLength = new Parameter("glidingFilamentLength"," Length of Actin Filament In Gliding Assay",glidingFilamentLength_init, "µm");

	static private final double glidingFilamentForce_init = 1; // force resisting gliding filament motion
	static final Parameter glidingFilamentForce = new Parameter("glidingFilamentForce", " Force Working Against Gliding Motion",glidingFilamentForce_init, "pN");

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
		Thing.brownianApplyInt = (int) (Env.brownianDeltaT.getValue() / Env.deltaT.getValue());
		
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
