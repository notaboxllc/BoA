package boxOfActin;

import java.io.File;
import java.text.DecimalFormat;

//import FileOps.SourceFileFilter;

public class BoxOfActin {
	
	static TimeLoop timeLoop;

	// output formats
	static DecimalFormat timeFormat = new DecimalFormat ("00.00");
	static DecimalFormat deflectionFormat = new DecimalFormat ("0.0000");
	static DecimalFormat expFormat = new DecimalFormat ("0.000E0");
	
	// multithreading
	static ThreadSet [] tSets;
	static final int numberOfWaves = 1;
	
	// timers for finding slow spots
	static RunTimer collisionMeshTimer = new RunTimer("CollisionMesh");
	static RunTimer motorsAndFilsColTimer = new RunTimer("MotorsAndFilsCollisions");
	static RunTimer brownianTimer = new RunTimer("BrownianMotion");
	static RunTimer xLinkTimer = new RunTimer("CrossLinksArpsActAs");
	static RunTimer stepTimer = new RunTimer("Step");
	static RunTimer moveTimer = new RunTimer("Move");
	static RunTimer biochemTimer = new RunTimer("Biochem");
	static RunTimer cleanupTimer1 = new RunTimer("Cleanups1");
	static RunTimer cleanupTimer2 = new RunTimer("Cleanups2");
	static RunTimer cleanupTimer3 = new RunTimer("Cleanups3");
	static RunTimer cleanupTimer4 = new RunTimer("Cleanups4");


	static RunTimer [] runTimers = {collisionMeshTimer,motorsAndFilsColTimer,brownianTimer,xLinkTimer,stepTimer,moveTimer,biochemTimer,cleanupTimer1};

	
	// counters for doLoop()
	static boolean paintedThisStep;
	static double lastReportTime = System.currentTimeMillis();
	static int drawCounter = 0;
	static int toFileCounter = 0;
	static int remoteOutCounter = 0;
	static int collisionCkCounter = 0;
	static int ckElasticityCounter = 0;
	static int ckPersistenceCounter = 0;
	static int applyBrownianForcesCounter = 0;
	static int drawingsMadeCounter = 0;
	static int jSonCt = (int)1e6;	// large number so it'll write at time zero
	static int jSonPlotCt = (int)1e6;	// ditto
	static int jSon2Ct = 0;  // start counting at zero so file writing starts at specified time vi Env.simJSon2StartCounter
	static int threeJSCounter = (int)1e6;	// large number so first frame writes at time zero
	
	// report time in logAndDraw
	static double lastLogAndDrawTime = System.currentTimeMillis();
	static double curLogAndDrawTime = 0;
	static double lastRunDetsTime = 0;

	// Deflection benchmark state (pinned ends, applied force, static-deflection measurement)
	static class DeflFil {
		FilSegment firstSeg, lastSeg, midSeg;
		FilSegment[] segs;
		Pt3D anchor1 = new Pt3D(), anchor2 = new Pt3D();
		Pt3D transForce = new Pt3D();
		Pt3D[] initCoords;
		double analyticDefl, chainSpanMicrons;
		double tauTheo = Double.NaN, tauMeas = Double.NaN;
		boolean tauMeasFrozen = false;
		long releaseStep = -1;
		double releaseDefl = Double.NaN;
		double releaseTime = Double.NaN;  // Env.simulationTime when release was detected (output-frame resolution)
		boolean prevForceOn = true;
	}
	static final DeflFil deflFil = new DeflFil();

	// LP benchmark state (free BCs, Brownian forces, tangent-correlation measurement)
	static class LpFil {
		FilSegment[] segs;
		int nSegs;
		double segLen, contourLength;  // µm
		double[] cMean;                // EWMA of C(s); index 0..nSegs-1; k=0 always 1.0
		boolean cMeanInitialized = false;
		int sampleCount;
	}
	static LpFil lpFil = null;  // null until makeInitialThings() creates it

	// Gliding assay evaluator (null when glidingAssay parameter is not active)
	static GlidingAssayEvaluator glidingEvaluator = null;

	// Shared snapshot struct returned by computeBenchmarkSnapshot()
	static class BenchmarkSnapshot {
		final double observed, expected, ratio;
		BenchmarkSnapshot(double obs, double exp, double r) { observed=obs; expected=exp; ratio=r; }
	}

	// Relaxation-time constants
	static final double RELAX_INV_E = 1.0 / Math.E;

	// F1 benchmark per-eval counters
	static int    benchStepCount   = 0;
	static int    benchMonCt       = 32;   // stored for LP chain and HUD

	// (settle-window formula constants removed — new crossing-event controller needs no settle wait)

	// Automated deflection tuning controller (null when not running)
	static DeflectionTuner deflTuner = null;
	static DeflectionTunerV15 deflTunerV15 = null;  // armed when -bmTunerV15 flag is present
	static DeflectionTunerV16 deflTunerV16 = null;  // armed when -bmTunerV16 flag is present
	static DeflectionTunerV17 deflTunerV17 = null;  // armed when -bmTunerV17 flag is present
	static DeflectionTunerV18 deflTunerV18 = null;  // armed when -bmTunerV18 flag is present
	static DeflectionTunerV19 deflTunerV19 = null;  // armed when -bmTunerV19 flag is present
	static DeflectionTunerV20 deflTunerV20 = null;  // armed when -bmTunerV20 flag is present
	static DeflectionTunerV21 deflTunerV21 = null;  // armed when -bmTunerV21 flag is present
	static DeflectionTunerV22 deflTunerV22 = null;  // armed when -bmTunerV22 flag is present
	static DeflectionTunerV23 deflTunerV23 = null;  // armed when -bmTunerV23 flag is present
	static DeflectionTunerV24 deflTunerV24 = null;  // armed when -bmTunerV24 flag is present
	static DeflectionTunerV25 deflTunerV25 = null;  // armed when -bmTunerV25 flag is present
	static int autoTuneStepCounter = 0;

	public BoxOfActin (String[] args) {
		
	}
	
	// Entry point: the default-package BoxOfActin.java at the project root has the main() method;
	// it parses no arguments itself and immediately calls this begin(args). Run with: java -Xmx800M BoxOfActin
	public static void begin (String[] args) {
		parseArgs(args);
		if (Env.benchmarkFilament) {
			Env.remote = true;
			Env.paused = false; // benchmark runs headless; no WebSocket client to send resume
			Env.simOutsideBug.setActive(false); // suppress Listeria bug + ActA creation
		}
		if (Env.benchmarkDiag) {
			Env.runTime.setValue(600); // 6M steps at deltaT=1e-4; diag exits at 5M via System.exit
		}
		if (Env.benchmarkManual) {
			Env.runTime.setValue(600); // 600s sim time — user drives termination via Kill button
		}
		if (Env.threeJSLivePort > 0) {
			LiveFrameServer.startServer(Env.threeJSLivePort);
		}
		System.err.println("[TELEPORT_DIAG] enabled=" + Env.myoMiniTeleportDiag
    + " threshold=" + Env.myoMiniTeleportThreshold);
		
		if (Env.paramFile != null) { FileOps.loadParamConfig(Env.paramFile, false); }
		if (Env.logFiles) { FileOps.remoteParamConfigSave(); }

		// Benchmark mode: zero all population parameters after param file (overrides whatever it set).
		// makeInitialThings() already returns early in benchmark mode, so makeInitialFilaments /
		// makeInitialMyoMiniFils / makeInitialProteinNodes are never reached.
		// Chamber() constructor and doLoop equilibration are not guarded — suppress them here.
		if (Env.benchmarkFilament) {
			Env.numChamberFixedMyos.setValue(0);       // Chamber::makeMyosinHeads
			Env.numChamberFixedMyoDimers.setValue(0);  // Chamber::makeMyosinDimers
			Env.initialMyoMiniFils.setValue(0);        // equilibrateMyoMiniNumber
			Env.equilNodes.setValue(0);                // equilibrateNodeNumber
			Env.kRdmNuc.setActive(false);              // spawnRdmFilaments
			Env.kNodeNuc.setActive(false);             // spawnNodeFilaments
		}

		// reset dependent parameters, etc
		Env.setTimeStepCounts();
		Env.setDependencies();
		FileOps.recalcJSonValues();
		
		// make Things, etc
		makeCrucible();
		makeInitialThings();
		if (Env.glidingAssay.isActive()) {
			GlidingAssayEvaluator.create();
			glidingEvaluator = GlidingAssayEvaluator.getInstance();
		}
		Mesh.createMeshes(); 	// for 2D grid collision detection
		MotorBindGrid3D.create();  // 3D grid for motor-binding path (step 1a)

		// multithreading
		loadAllThreadSets();
		
		// make the time loop instance
		timeLoop = new TimeLoop();
		timeLoop.start();
	}
	
	protected static void parseArgs( String[] args ) {
		for ( int i = 0; i < args.length; i++ ) {
			if (args[i].equals("-help")) {
				talkln (" Command line options for BOA:");
				talkln (" -help: print this message");
				talkln (" -r  : run the code 'remotely', without any graphics output");
				talkln (" -pf -paramfile -paramFile (file) : load parameter values from specified file");
				talkln (" -o -out (directory) : create specified directory... all log, histogram, and log files will be saved there");
				talkln (" -outMade (directory) : same as -out except main directory already made... all log, histogram, and log files will be saved there");
				talkln (" -lf -logfile -logFile (directory) : create specified directory and save all output files to there");
				talkln (" -biochem : run the simulation without any collisions, forces, or brownian motion");
				talkln (" -3js (directory) : write Three.js per-frame JSON files to the specified directory (auto-increments .001 suffix if exists)");
				talkln (" -3jsLive (port)  : start WebSocket server on specified port for live frame streaming to sim_viewer_boa.html?live=<port>");
				talkln (" -oc : ordered filaments (in a biochem only run) are centered");
				System.exit(0);
			}
			
			if ( args[i].equals( "-r" )) {
				Env.remote = true;
				Env.paused = false;
			}
			
			if ((args[i].equals("-paramfile")) | (args[i].equals("-pf")) | (args[i].equals("-paramFile"))) {
				File paramFile = new File(args[i+1]);
				if (paramFile.exists()) {
					Env.paramFile = paramFile;
				} else {
					talkln ("Specified param file can't be found... check path, etc");
					System.exit(0);
				}
			}
			
			if (args[i].equals("-oc")) {
				Env.orderedCentered = true;
			}
			
			if ((args[i].equals("-o")) | (args[i].equals("-out")) | (args[i].equals("-outfile"))) {
				File outFolder = new File(args[i+1]);
				File altOutFolder = new File (args[i+1]);
				int j=1;
				while (altOutFolder.isDirectory()) {
					System.out.println (altOutFolder.getName() + " exists.... keeping file name BUT changing directory name");
					altOutFolder = new File(args[i+1] + "." + String.valueOf(j));
					j++;
				}
				Env.outFileName = outFolder.getName();
				altOutFolder.mkdir();
				// directory for the log files
				File logSubFolder = new File(altOutFolder.getAbsolutePath() + File.separator + Env.outFileName + "-LOG");
				Env.logFolderPath = logSubFolder.getAbsolutePath();
				logSubFolder.mkdir();
				Env.logFiles = true;
				// directory for the source files
				File srcSubFolder = new File (altOutFolder.getAbsolutePath() + File.separator + Env.outFileName + "-SRC");
				Env.srcFilePath = srcSubFolder.getAbsolutePath();
				srcSubFolder.mkdir();

			}
			
			if (args[i].equals("-outMade")) {
				File outFolder = new File(args[i+1]);
				Env.outFileName = outFolder.getName();
	
				// directory for the log files
				File logSubFolder = new File(outFolder.getAbsolutePath() + File.separator + Env.outFileName + "-LOG");
				Env.logFolderPath = logSubFolder.getAbsolutePath();
				logSubFolder.mkdir();
				Env.logFiles = true;

			}
			
			if ((args[i].equals("-logfile")) | (args[i].equals("-lf")) | (args[i].equals("-logFile"))) {
				File outFolder = new File(args[i+1]);
				File altOutFolder = new File (args[i+1]);
				int j=1;
				while (altOutFolder.isDirectory()) {
					System.out.println (altOutFolder.getName() + " exists.... keeping file name BUT changing directory name");
					altOutFolder = new File(args[i+1] + "." + String.valueOf(j));
					j++;
				}
				altOutFolder.mkdir();
				Env.outFileName = outFolder.getName();
				Env.logFolderPath = altOutFolder.getAbsolutePath();
				// make a directory for the source files
				File srcdir = new File (altOutFolder.getAbsolutePath() + File.separator + Env.outFileName + ".SRC");
				//srcdir.mkdir();
				Env.srcFilePath = srcdir.getAbsolutePath();
				//*******
				Env.logFiles = true;
			}
			
			if (args[i].equals("-3js")) {
				Env.threeJSOutputDir = args[i + 1];
			}

			if (args[i].equals("-3jsLive")) {
				try {
					Env.threeJSLivePort = Integer.parseInt(args[i + 1]);
				} catch (NumberFormatException e) {
					talkln("Invalid port for -3jsLive: " + args[i + 1]);
					System.exit(1);
				}
			}

			if (args[i].equals("-bm") || args[i].equals("-benchmark")) {
				Env.benchmarkFilament = true;
			}
			if (args[i].equals("-bmMonomer") && i + 1 < args.length) {
				Env.benchmarkMonomerCt = Integer.parseInt(args[++i]);
			}
			if (args[i].equals("-bmDiag")) {
				Env.benchmarkDiag = true;
				Env.benchmarkFilament = true; // reuses chain/box setup
			}
			if (args[i].equals("-bmManual")) {
				Env.benchmarkFilament = true;
				Env.benchmarkManual = true;
			}
			if (args[i].equals("-bmTunerV15")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV15 = true;
			}
			if (args[i].equals("-bmTunerV16")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV16 = true;
			}
			if (args[i].equals("-bmTunerV17")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV17 = true;
			}
			if (args[i].equals("-bmTunerV18")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV18 = true;
			}
			if (args[i].equals("-bmTunerV19")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV19 = true;
			}
			if (args[i].equals("-bmTunerV20")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV20 = true;
			}
			if (args[i].equals("-bmTunerV21")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV21 = true;
			}
			if (args[i].equals("-bmTunerV22")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV22 = true;
			}
			if (args[i].equals("-bmTunerV23")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV23 = true;
			}
			if (args[i].equals("-bmTunerV24")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV24 = true;
			}
			if (args[i].equals("-bmTunerV25")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV25 = true;
			}
			if (args[i].equals("-bmNoiseProbe")) {
				Env.benchmarkFilament = true;
				Env.benchmarkTunerV15 = true;
				Env.benchmarkNoiseProbe = true;
			}
			if (args[i].equals("-seed") && i + 1 < args.length) {
				long seed = Long.parseLong(args[++i]);
				Env.mtRNG.setSeed(seed);
			}
		}
		// Mutual exclusivity: v25 > v24 > v23 > v22 > v21 > v20 > v19 > v18 > v17 > v16 > v15 > v14. Warn and clear lower-priority flags.
		if (Env.benchmarkTunerV25) {
			if (Env.benchmarkTunerV24) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV24 both set — using v25, clearing v24");
				Env.benchmarkTunerV24 = false;
			}
			if (Env.benchmarkTunerV23) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV23 both set — using v25, clearing v23");
				Env.benchmarkTunerV23 = false;
			}
			if (Env.benchmarkTunerV22) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV22 both set — using v25, clearing v22");
				Env.benchmarkTunerV22 = false;
			}
			if (Env.benchmarkTunerV21) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV21 both set — using v25, clearing v21");
				Env.benchmarkTunerV21 = false;
			}
			if (Env.benchmarkTunerV20) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV20 both set — using v25, clearing v20");
				Env.benchmarkTunerV20 = false;
			}
			if (Env.benchmarkTunerV19) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV19 both set — using v25, clearing v19");
				Env.benchmarkTunerV19 = false;
			}
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV18 both set — using v25, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV17 both set — using v25, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV16 both set — using v25, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV25 and -bmTunerV15 both set — using v25, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV24) {
			if (Env.benchmarkTunerV23) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV23 both set — using v24, clearing v23");
				Env.benchmarkTunerV23 = false;
			}
			if (Env.benchmarkTunerV22) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV22 both set — using v24, clearing v22");
				Env.benchmarkTunerV22 = false;
			}
			if (Env.benchmarkTunerV21) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV21 both set — using v24, clearing v21");
				Env.benchmarkTunerV21 = false;
			}
			if (Env.benchmarkTunerV20) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV20 both set — using v24, clearing v20");
				Env.benchmarkTunerV20 = false;
			}
			if (Env.benchmarkTunerV19) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV19 both set — using v24, clearing v19");
				Env.benchmarkTunerV19 = false;
			}
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV18 both set — using v24, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV17 both set — using v24, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV16 both set — using v24, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV24 and -bmTunerV15 both set — using v24, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV23) {
			if (Env.benchmarkTunerV22) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV22 both set — using v23, clearing v22");
				Env.benchmarkTunerV22 = false;
			}
			if (Env.benchmarkTunerV21) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV21 both set — using v23, clearing v21");
				Env.benchmarkTunerV21 = false;
			}
			if (Env.benchmarkTunerV20) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV20 both set — using v23, clearing v20");
				Env.benchmarkTunerV20 = false;
			}
			if (Env.benchmarkTunerV19) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV19 both set — using v23, clearing v19");
				Env.benchmarkTunerV19 = false;
			}
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV18 both set — using v23, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV17 both set — using v23, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV16 both set — using v23, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV23 and -bmTunerV15 both set — using v23, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV22) {
			if (Env.benchmarkTunerV21) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV21 both set — using v22, clearing v21");
				Env.benchmarkTunerV21 = false;
			}
			if (Env.benchmarkTunerV20) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV20 both set — using v22, clearing v20");
				Env.benchmarkTunerV20 = false;
			}
			if (Env.benchmarkTunerV19) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV19 both set — using v22, clearing v19");
				Env.benchmarkTunerV19 = false;
			}
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV18 both set — using v22, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV17 both set — using v22, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV16 both set — using v22, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV22 and -bmTunerV15 both set — using v22, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV21) {
			if (Env.benchmarkTunerV20) {
				System.err.println("[WARN] -bmTunerV21 and -bmTunerV20 both set — using v21, clearing v20");
				Env.benchmarkTunerV20 = false;
			}
			if (Env.benchmarkTunerV19) {
				System.err.println("[WARN] -bmTunerV21 and -bmTunerV19 both set — using v21, clearing v19");
				Env.benchmarkTunerV19 = false;
			}
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV21 and -bmTunerV18 both set — using v21, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV21 and -bmTunerV17 both set — using v21, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV21 and -bmTunerV16 both set — using v21, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV21 and -bmTunerV15 both set — using v21, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV20) {
			if (Env.benchmarkTunerV19) {
				System.err.println("[WARN] -bmTunerV20 and -bmTunerV19 both set — using v20, clearing v19");
				Env.benchmarkTunerV19 = false;
			}
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV20 and -bmTunerV18 both set — using v20, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV20 and -bmTunerV17 both set — using v20, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV20 and -bmTunerV16 both set — using v20, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV20 and -bmTunerV15 both set — using v20, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV19) {
			if (Env.benchmarkTunerV18) {
				System.err.println("[WARN] -bmTunerV19 and -bmTunerV18 both set — using v19, clearing v18");
				Env.benchmarkTunerV18 = false;
			}
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV19 and -bmTunerV17 both set — using v19, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV19 and -bmTunerV16 both set — using v19, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV19 and -bmTunerV15 both set — using v19, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV18) {
			if (Env.benchmarkTunerV17) {
				System.err.println("[WARN] -bmTunerV18 and -bmTunerV17 both set — using v18, clearing v17");
				Env.benchmarkTunerV17 = false;
			}
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV18 and -bmTunerV16 both set — using v18, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV18 and -bmTunerV15 both set — using v18, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV17) {
			if (Env.benchmarkTunerV16) {
				System.err.println("[WARN] -bmTunerV17 and -bmTunerV16 both set — using v17, clearing v16");
				Env.benchmarkTunerV16 = false;
			}
			if (Env.benchmarkTunerV15) {
				System.err.println("[WARN] -bmTunerV17 and -bmTunerV15 both set — using v17, clearing v15");
				Env.benchmarkTunerV15 = false;
			}
		}
		if (Env.benchmarkTunerV16 && Env.benchmarkTunerV15) {
			System.err.println("[WARN] -bmTunerV16 and -bmTunerV15 both set — using v16, clearing v15");
			Env.benchmarkTunerV15 = false;
		}
	}
	
	static class TimeLoop extends Thread {
	
		public void run() {
			doLoop();

			FileOps.closeJSons();
			LiveFrameServer.stopServer();
			//System.exit(0);
		}
	}
	
	private static void loadAllThreadSets () {
		tSets = new ThreadSet [17];
		tSets[0] = Thing.stepThreads;
		tSets[1] = Thing.brownianThreads;
		tSets[2] = Myosin.myoThreads;
		tSets[3] = MyosinDimer.myoDimerThreads;
		tSets[4] = ProteinNode.nodeThreads;
		tSets[5] = MyoMiniFilament.miniFilThreads;
		tSets[6] = Chamber.chamberMyoThreads;
		tSets[7] = Chamber.chamberMyoDThreads;
		tSets[8] = Mesh.meshThreads;
		tSets[9] = Mesh.ckMeshThreads;
		tSets[10] = Mesh.ckMotsThreads;
		tSets[11] = FilLink.xLinkThreads;
		tSets[12] = Arp23.arp23Threads;
		tSets[13] = NodeLink.nodeLinkThreads;
		tSets[14] = StickyNode.membraneNodeThreads;
		tSets[15] = ActA.actAThreads;
		tSets[16] = MotorBindGrid3D.FillThreads.fillThreads;


	}
	
	private static void reportAllThreadSetTimes () {
		for (int i=0; i< tSets.length; i++) {
			tSets[i].barrier.timer.reportTime();
		}
	}
	
	private static void reportRunTimers () {
		double runTimeSum = 0;
		double collMeshSum = collisionMeshTimer.getTimeSum();
		System.out.println();
		System.out.println("*** Real time for different simulations steps... ****");
		for (int i=0; i< runTimers.length; i++) {
			runTimers[i].reportTime();
			runTimeSum += runTimers[i].getTimeSum();
			runTimers[i].resetTime();
		}
		double collMeshPercentage = 100*(collMeshSum/runTimeSum);
		System.out.println("Percentage of time in Collision Mesh:" + String.format("%3f", collMeshPercentage));
		System.out.println();
	}
	
	private static void resetAllThreadSetTimes () {
		for (int i=0; i< tSets.length; i++) {
			tSets[i].barrier.timer.resetTime();
		}
	}
	
	private static void startAllThreadSets (int waveNum) {
		for (int i=0; i < tSets.length; i++) {
			tSets[i].divideAndConquer(waveNum);
			//System.out.println(tSets[i].commandName + " calling wave # " + waveNum);
		}
	}
	
	private static void waitOnAllThreadSets(int waveNum) {
		for (int i=0; i < tSets.length; i++) {
			tSets[i].regroup(waveNum);
		}
	}
	
	public static void doLoop() {
		// timers
		double startTime;
		double collisionTime = 0;
		double myosinTime = 0;
		
		// C3: label lets break escape from inside synchronized when kill is detected
		outer:
		while (Env.simulationTime <= (Env.runTime.getValue()+Env.runBump) && !Env.terminating) {
			synchronized(Env.safeO) {
				// C3: pre-step pause wait — blocks between timesteps, releases the lock
				// so the WebSocket thread can set Env.paused/terminating and notifyAll()
				while (Env.paused && !Env.terminating) {
					try { Env.safeO.wait(50); } catch (InterruptedException e) { break; }
				}
				if (Env.terminating) break outer;

				// set biophysical values needed for this next time step
				FilSegment.setBiophysValues();
				// SoA sync: snapshot motor and filament positions for 3D grid (step 1a)
				MyoMotor.fillSoaArrays();
				FilSegment.fillSoaArrays();
				 // Meshed Collisions
				if (collisionCkCounter >= Thing.collisionCheckInt | Env.simulationTime == 0) {
					 collisionMeshTimer.start();

					 startAllThreadSets(Env.meshFilsStart);
					 waitOnAllThreadSets(Env.meshFilsStop);
					 startAllThreadSets(Env.meshNodesStart);
					 waitOnAllThreadSets(Env.meshNodesStop);
					 startAllThreadSets(Env.meshMotorsStart);
					 waitOnAllThreadSets(Env.meshMotorsStop);

					 startAllThreadSets(Env.meshCollStart);
					 waitOnAllThreadSets(Env.meshCollStop);

					 collisionCkCounter = 0;
					 collisionMeshTimer.stopInc();
				}

				// Motor domain - filament collisions... check every time-step
				motorsAndFilsColTimer.start();
				startAllThreadSets(Env.motorBindGrid3DStart);
				waitOnAllThreadSets(Env.motorBindGrid3DStop);
				startAllThreadSets(Env.motCollStart);
				waitOnAllThreadSets(Env.motCollStop);
				motorsAndFilsColTimer.stopInc();
				MyoMotor.sampleBoundMotors();

				// Brownian Motion
				if (applyBrownianForcesCounter >= Thing.brownianApplyInt | Env.simulationTime == 0) {
					brownianTimer.start();
					startAllThreadSets(Env.bForcesStart);
					waitOnAllThreadSets(Env.bForcesStop);
					brownianTimer.stopInc();
				}

				// Crosslinkers and Arp2/3 branches and ActAs
				xLinkTimer.start();
				FilSegment.zeroAllLinkCts();
				startAllThreadSets(Env.xLinkStart);
				waitOnAllThreadSets(Env.xLinkStop);
				xLinkTimer.stopInc();

				// Membrane links
				startAllThreadSets(Env.membraneLinksStart);
				waitOnAllThreadSets(Env.membraneLinksStop);

				// actual myosin joints
				startAllThreadSets(Env.myoJoints1Start);
				waitOnAllThreadSets(Env.myoJoints1Stop);

				// connections to other things
				startAllThreadSets(Env.myoJoints2Start);
				waitOnAllThreadSets(Env.myoJoints2Stop);

				// Thing.step() calls
				stepTimer.start();
				startAllThreadSets(Env.stepStart);
				waitOnAllThreadSets(Env.stepStop);
				stepTimer.stopInc();

				// F1 benchmark: apply transverse force to midpoint segment before integration
				if (Env.benchmarkFilament && deflFil.midSeg != null && Env.benchmarkForceOn.getValue() != 0) {
					deflFil.midSeg.incForceSum(deflFil.transForce);
				}
				// Round 3 diagnostic: trace force application path
				if (Env.benchmarkFilament && deflFil.midSeg != null && benchStepCount < 10) {
					System.err.printf("[BENCH:STEP] step=%d forceSum=(%.4e,%.4e,%.4e) coord=(%.4f,%.4f,%.4f) veloc.y=%.4e%n",
						benchStepCount, deflFil.midSeg.forceSum.x, deflFil.midSeg.forceSum.y, deflFil.midSeg.forceSum.z,
						deflFil.midSeg.coord.x, deflFil.midSeg.coord.y, deflFil.midSeg.coord.z,
						deflFil.midSeg.veloc.y);
				}

				moveTimer.start();
				startAllThreadSets(Env.moveStart);
				waitOnAllThreadSets(Env.moveStop);
				moveTimer.stopInc();

				// F1 benchmark: restore pinned endpoints after integration
				if (Env.benchmarkFilament) { applyBenchmarkPins(); }
				// Round 3 diagnostic: midpoint coord after integration + pin correction
				if (Env.benchmarkFilament && deflFil.midSeg != null && benchStepCount < 10) {
					System.err.printf("[BENCH:POST] step=%d coord.y=%.6e veloc.y=%.4e%n",
						benchStepCount, deflFil.midSeg.coord.y, deflFil.midSeg.veloc.y);
				}
				biochemTimer.start();
				startAllThreadSets(Env.biochemStart);
				waitOnAllThreadSets(Env.biochemStop);
				biochemTimer.stopInc();

				startAllThreadSets(Env.resetCtStart);
				waitOnAllThreadSets(Env.resetCtStop);

				// Membrane relaxation loop... special passes to allow forces to propogate/move nodes, especially laterally at collisions
				int mPass = 0;
				NodeLink.maxStrain = 10;
				while (NodeLink.maxStrain > Env.membraneMaxLinkStrain.getValue() && mPass < Env.maxMembranePasses.getIntValue()) {
					NodeLink.maxStrain = 0;	// zero before each pass... values set in NodeLink.enforceLink()

					startAllThreadSets(Env.membraneLinksStart);
					waitOnAllThreadSets(Env.membraneLinksStop);
					//System.out.println("max membrane strain = " + NodeLink.maxStrain);

					startAllThreadSets(Env.membraneMoveStart);
					waitOnAllThreadSets(Env.membraneMoveStop);

					mPass++;
				}

				updateCounters();

				// C3: safe-point — pause check (with inspect drain while waiting),
				// kill check, then final inspect drain. Order: pause > kill > inspect.
				while (Env.paused && !Env.terminating) {
					drainInspectQueue();   // inspect still works on the frozen view
					try { Env.safeO.wait(50); } catch (InterruptedException e) { break; }
				}
				if (Env.terminating) break outer;
				drainInspectQueue();
				drainParamQueue();  // C4: apply pending parameter changes, dispatch acks

				// -bmDiag: fixed-parameter equilibrium diagnostic — no search, just report ratio every 5000 steps
				if (Env.benchmarkDiag) {
					benchStepCount++;
					if (benchStepCount % 5000 == 0) {
						double ratio = computeDeflectionRatio();
						double defl = deflFil.analyticDefl * ratio;
						System.out.printf("[BMDIAG] step=%8d  simT=%8.2fs  ratio=%.6f  defl=%.6fµm%n",
							benchStepCount, Env.simulationTime, ratio, defl);
						System.out.flush();
					}
					if (benchStepCount >= 5_000_000) {
						System.out.printf("[BMDIAG] DONE: %d steps  simT=%.1fs  final ratio=%.6f%n",
							benchStepCount, Env.simulationTime, computeDeflectionRatio());
						System.exit(0);
					}
				}

				// benchStepCount suppresses early-step chain diagnostics after first 10 steps.
				if (Env.benchmarkFilament && !Env.benchmarkDiag) { benchStepCount++; }

				// Automated deflection tuning: feed active controller at output-frame cadence.
				// Uses a dedicated step counter so it fires in headless mode (no output frames).
				boolean eitherTunerActive = (deflTuner != null || deflTunerV15 != null || deflTunerV16 != null || deflTunerV17 != null || deflTunerV18 != null || deflTunerV19 != null || deflTunerV20 != null || deflTunerV21 != null || deflTunerV22 != null || deflTunerV23 != null || deflTunerV24 != null || deflTunerV25 != null)
					&& Env.benchmarkFilament && deflFil.midSeg != null
					&& Env.benchmarkForceOn.getValue() != 0;
				if (eitherTunerActive) {
					autoTuneStepCounter++;
					if (autoTuneStepCounter >= Env.toFileInterval.getIntValue()) {
						autoTuneStepCounter = 0;
						BenchmarkSnapshot snap = computeBenchmarkSnapshot();
						if (snap != null) {
							if (deflTunerV25 != null) {
								// v25 path
								DeflectionTunerV25.ParamTriple update = deflTunerV25.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV25.isDone()) {
									boolean converged = deflTunerV25.getPhase() == DeflectionTunerV25.Phase.CONVERGED;
									System.out.println(deflTunerV25.resultSummary());
									System.out.flush();
									deflTunerV25 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV24 != null) {
								// v24 path
								DeflectionTunerV24.ParamTriple update = deflTunerV24.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV24.isDone()) {
									boolean converged = deflTunerV24.getPhase() == DeflectionTunerV24.Phase.CONVERGED;
									System.out.println(deflTunerV24.resultSummary());
									System.out.flush();
									deflTunerV24 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV23 != null) {
								// v23 path
								DeflectionTunerV23.ParamTriple update = deflTunerV23.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV23.isDone()) {
									boolean converged = deflTunerV23.getPhase() == DeflectionTunerV23.Phase.CONVERGED;
									System.out.println(deflTunerV23.resultSummary());
									System.out.flush();
									deflTunerV23 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV22 != null) {
								// v22 path
								DeflectionTunerV22.ParamTriple update = deflTunerV22.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV22.isDone()) {
									boolean converged = deflTunerV22.getPhase() == DeflectionTunerV22.Phase.CONVERGED;
									System.out.println(deflTunerV22.resultSummary());
									System.out.flush();
									deflTunerV22 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV21 != null) {
								// v21 path
								DeflectionTunerV21.ParamTriple update = deflTunerV21.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV21.isDone()) {
									boolean converged = deflTunerV21.getPhase() == DeflectionTunerV21.Phase.CONVERGED;
									System.out.println(deflTunerV21.resultSummary());
									System.out.flush();
									deflTunerV21 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV20 != null) {
								// v20 path
								DeflectionTunerV20.ParamTriple update = deflTunerV20.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV20.isDone()) {
									boolean converged = deflTunerV20.getPhase() == DeflectionTunerV20.Phase.CONVERGED;
									System.out.println(deflTunerV20.resultSummary());
									System.out.flush();
									deflTunerV20 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV19 != null) {
								// v19 path
								DeflectionTunerV19.ParamTriple update = deflTunerV19.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV19.isDone()) {
									boolean converged = deflTunerV19.getPhase() == DeflectionTunerV19.Phase.CONVERGED;
									System.out.println(deflTunerV19.resultSummary());
									System.out.flush();
									deflTunerV19 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV18 != null) {
								// v18 path
								DeflectionTunerV18.ParamTriple update = deflTunerV18.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV18.isDone()) {
									boolean converged = deflTunerV18.getPhase() == DeflectionTunerV18.Phase.CONVERGED;
									System.out.println(deflTunerV18.resultSummary());
									System.out.flush();
									deflTunerV18 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV17 != null) {
								// v17 path
								DeflectionTunerV17.ParamTriple update = deflTunerV17.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV17.isDone()) {
									boolean converged = deflTunerV17.getPhase() == DeflectionTunerV17.Phase.CONVERGED;
									System.out.println(deflTunerV17.resultSummary());
									System.out.flush();
									deflTunerV17 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV16 != null) {
								// v16 path
								DeflectionTunerV16.ParamTriple update = deflTunerV16.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV16.isDone()) {
									boolean converged = deflTunerV16.getPhase() == DeflectionTunerV16.Phase.CONVERGED;
									System.out.println(deflTunerV16.resultSummary());
									System.out.flush();
									deflTunerV16 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else if (deflTunerV15 != null) {
								// v15 path
								DeflectionTunerV15.ParamTriple update = deflTunerV15.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTunerV15.isDone()) {
									boolean converged = deflTunerV15.getPhase() == DeflectionTunerV15.Phase.CONVERGED;
									System.out.println(deflTunerV15.resultSummary());
									System.out.flush();
									deflTunerV15 = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							} else {
								// v14 path (unchanged)
								DeflectionTuner.ParamTriple update = deflTuner.feed(snap.observed);
								if (update != null) {
									double oldFm  = Env.fracMove.getValue();
									double oldFr  = Env.fracR.getValue();
									double oldFmt = Env.fracMoveTorq.getValue();
									Env.fracMove.setValue(update.fracMove);
									Env.fracR.setValue(update.fracR);
									Env.fracMoveTorq.setValue(update.fracMoveTorq);
									if (LiveFrameServer.isRunning()) {
										if (update.fracMove     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  update.fracMove);
										if (update.fracR        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  update.fracR);
										if (update.fracMoveTorq != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, update.fracMoveTorq);
									}
								}
								if (deflTuner.isDone()) {
									boolean converged = deflTuner.getPhase() == DeflectionTuner.Phase.CONVERGED;
									System.out.println(deflTuner.resultSummary());
									System.out.flush();
									deflTuner = null;
									if (!Env.benchmarkManual) System.exit(converged ? 0 : 1);
								}
							}
						}
					}
				}

				// Per-step 1/e crossing detection: step-resolution τ_meas (Option A).
				// Runs every step during relaxation; at most one computeBenchmarkSnapshot() call per step.
				if (Env.benchmarkFilament && !deflFil.tauMeasFrozen
						&& deflFil.releaseStep >= 0 && Env.benchmarkForceOn.getValue() == 0
						&& !Double.isNaN(deflFil.releaseDefl) && deflFil.releaseDefl > 0) {
					BenchmarkSnapshot stepSnap = computeBenchmarkSnapshot();
					if (stepSnap != null && stepSnap.observed / deflFil.releaseDefl <= RELAX_INV_E) {
						deflFil.tauMeas = !Double.isNaN(deflFil.releaseTime)
							? Env.simulationTime - deflFil.releaseTime
							: (benchStepCount - deflFil.releaseStep) * Env.deltaT.getValue();
						deflFil.tauMeasFrozen = true;
					}
				}

				// output to screen and/or files
				if (!Env.remote) { logAndDraw(); } else { remoteLog(); }

				//**** Clean Up ****
				cleanupTimer1.start();
				// large-scale removal of stuff at prescribed rates
				ProteinNode.cleanUpNodes();
				MyoMiniFilament.cleanUpMyoMinis();
				//if (Env.randomNodesOn && (Math.random() < 0.01)) { MyoMiniFilament.removeRandomMiniFil();	}

				// remove Things AFTER the graphical update... can safely detach graphics objects of dead things this way
				// the order is important here... conglomerates of smaller things first, since removal can cascade that direction
				ProteinNode.cleanUpNodes();
				MyoMiniFilament.cleanUpMyoMinis();
				MyosinDimer.cleanupMyoDimers();  //
				Myosin.cleanupMyos(); 		// ditto above
				MyoMotor.cleanupMyoMotors();  // compact motor array
				Thing.removeDeadThings();		// get rid of dead things
				FilLink.setInactiveFilLinks();	// register inactive links
				NodeLink.setInactiveNodeLinks();
				Arp23.setInactiveArp23s();
				cleanupTimer1.stopInc();

				ActA.cleanUpActAs();

				if (Env.simulationTime > 0 && StickyNode.sphericalGeometry) { FillNode.addFillNodeToCell(); }   // fill cell as appropriate


				// create new Things
				if (Env.kRdmNuc.isActive()) { FilSegment.spawnRdmFilaments(); }
				if (Env.kNodeNuc.isActive()) { ProteinNode.spawnNodeFilaments(); }

				ProteinNode.equilibrateNodeNumber();
				MyoMiniFilament.equilibrateMyoMiniNumber();
			}
		}
		System.out.println("collisionTime = " + collisionTime);
		System.out.println("myosinTime = " + myosinTime);
		System.out.printf("[STATS] bindEvents=%d%n", MyoMotor.totalBindEvents);
		if (MyoMotor.boundMotorSampleCt > 0) {
			System.out.printf("[STATS] meanBoundMotors=%.3f%n", (double)MyoMotor.boundMotorSum / MyoMotor.boundMotorSampleCt);
		}
		reportAllThreadSetTimes();
		
	}
	
	// F1 benchmark: translate terminal segments so their pinned endpoints return to anchors.
	// Called after moveThing() each step. Post-moveThing() position correction handles both
	// centroid translation and rotation (rotation pivots about centroid, not pin, so the
	// endpoint drifts; the correction below restores it exactly regardless of the source).
	private static void applyBenchmarkPins() {
		if (deflFil.firstSeg == null || deflFil.lastSeg == null) return;
		deflFil.firstSeg.coord.x += deflFil.anchor1.x - deflFil.firstSeg.end1.x;
		deflFil.firstSeg.coord.y += deflFil.anchor1.y - deflFil.firstSeg.end1.y;
		deflFil.firstSeg.coord.z += deflFil.anchor1.z - deflFil.firstSeg.end1.z;
		deflFil.firstSeg.initialize();
		deflFil.lastSeg.coord.x += deflFil.anchor2.x - deflFil.lastSeg.end2.x;
		deflFil.lastSeg.coord.y += deflFil.anchor2.y - deflFil.lastSeg.end2.y;
		deflFil.lastSeg.coord.z += deflFil.anchor2.z - deflFil.lastSeg.end2.z;
		deflFil.lastSeg.initialize();
	}

	// F1 benchmark: compute midpoint perpendicular deflection as a full snapshot.
	private static BenchmarkSnapshot computeBenchmarkSnapshot() {
		if (deflFil.midSeg == null) return null;
		double ax = deflFil.anchor2.x - deflFil.anchor1.x;
		double ay = deflFil.anchor2.y - deflFil.anchor1.y;
		double az = deflFil.anchor2.z - deflFil.anchor1.z;
		double aLen = Math.sqrt(ax*ax + ay*ay + az*az);
		ax /= aLen; ay /= aLen; az /= aLen;
		double px = deflFil.midSeg.coord.x - deflFil.anchor1.x;
		double py = deflFil.midSeg.coord.y - deflFil.anchor1.y;
		double pz = deflFil.midSeg.coord.z - deflFil.anchor1.z;
		double proj = px*ax + py*ay + pz*az;
		double perpX = px - proj*ax, perpY = py - proj*ay, perpZ = pz - proj*az;
		double obs = Math.sqrt(perpX*perpX + perpY*perpY + perpZ*perpZ);
		double exp = deflFil.analyticDefl;
		double ratio = exp > 0 ? obs / exp : Double.NaN;
		return new BenchmarkSnapshot(obs, exp, ratio);
	}

	// Thin wrapper — callers that only need the ratio.
	private static double computeDeflectionRatio() {
		BenchmarkSnapshot s = computeBenchmarkSnapshot();
		return s != null ? s.ratio : Double.NaN;
	}

	// F1 benchmark: print midpoint perpendicular deflection vs analytic FL³/(48EI).
	private static void reportBenchmarkDeflection(String label) {
		if (deflFil.midSeg == null) return;
		BenchmarkSnapshot snap = computeBenchmarkSnapshot();
		if (snap == null) return;
		System.out.println("[BENCH:" + label + "] step=" + benchStepCount
			+ "  meas=" + deflectionFormat.format(snap.observed) + " µm"
			+ "  analytic=" + deflectionFormat.format(snap.expected) + " µm"
			+ "  ratio=" + String.format("%.4f", snap.ratio));
	}

	// Build the deflection benchmark WebSocket topic payload JSON.
	// Handles force-toggle state transitions and 1/e relaxation-time detection.
	private static String buildBenchmarkJson() {
		BenchmarkSnapshot snap = computeBenchmarkSnapshot();
		if (snap == null) return null;
		boolean forceOn = Env.benchmarkForceOn.getValue() != 0;
		if (forceOn != deflFil.prevForceOn) {
			if (!forceOn) {
				deflFil.releaseStep = benchStepCount;
				deflFil.releaseDefl = snap.observed;
				deflFil.releaseTime = Env.simulationTime;  // output-frame resolution; crossing check uses step resolution
				deflFil.tauMeas = Double.NaN;
				deflFil.tauMeasFrozen = false;
			} else {
				deflFil.releaseStep = -1;
				deflFil.releaseDefl = Double.NaN;
				deflFil.releaseTime = Double.NaN;
				deflFil.tauMeas = Double.NaN;
				deflFil.tauMeasFrozen = false;
			}
			deflFil.prevForceOn = forceOn;
		}
		// 1/e crossing detection moved to per-step block in doLoop() for step-resolution τ_meas.
		StringBuilder sb = new StringBuilder(256);
		sb.append(String.format(
			"{\"chainSegments\":%d,\"monomersPerSegment\":%d,\"chainSpanMicrons\":%.4f,\"viscosity\":%.4f",
			Env.benchmarkNSegs, benchMonCt, deflFil.chainSpanMicrons, Env.aeta.getValue()));
		// observedDeflection and expectedDeflection are in µm; viewer multiplies by 1000 for nm
		// and shows toFixed(3) — needs 0.001 nm = 1e-6 µm precision, so emit full double.
		sb.append(String.format(
			",\"observedDeflection\":%s,\"expectedDeflection\":%s,\"ratio\":%.3f,\"forceOn\":%b,\"stepCount\":%d",
			snap.observed, snap.expected, snap.ratio, forceOn, (long) benchStepCount));
		if (!Double.isNaN(deflFil.tauTheo)) {
			sb.append(",\"tauTheo\":").append(deflFil.tauTheo);
		}
		if (!forceOn && deflFil.releaseStep >= 0) {
			if (deflFil.tauMeasFrozen) {
				sb.append(",\"tauMeas\":").append(deflFil.tauMeas).append(",\"tauMeasFrozen\":true");
			} else {
				double elapsed = !Double.isNaN(deflFil.releaseTime)
					? Env.simulationTime - deflFil.releaseTime
					: (long)(benchStepCount - deflFil.releaseStep) * Env.deltaT.getValue();
				sb.append(",\"tauMeas\":").append(elapsed).append(",\"tauMeasFrozen\":false");
			}
		}
		sb.append("}");
		return sb.toString();
	}

	// LP benchmark: accumulate EWMA of tangent-tangent correlation C(s) for this output frame.
	// Called once per output frame inside synchronized(Env.safeO). Reads segment uVec — read-only, safe.
	private static void accumulateLpData() {
		if (lpFil == null) return;
		if (Env.lpActive.getValue() == 0) return;
		int nLp = lpFil.nSegs;
		FilSegment[] lpSegs = lpFil.segs;
		double alpha = Env.lpEwmaAlpha.getValue();
		// Compute per-frame mean C(s) for each separation k=1..nLp-1
		for (int k = 1; k < nLp; k++) {
			int pairs = 0;
			double sum = 0.0;
			for (int i = 0; i + k < nLp; i++) {
				sum += Pt3D.Dot(lpSegs[i].uVec, lpSegs[i + k].uVec);
				pairs++;
			}
			double cNew = (pairs > 0) ? sum / pairs : 1.0;
			if (!lpFil.cMeanInitialized) {
				lpFil.cMean[k] = cNew;
			} else {
				lpFil.cMean[k] = alpha * cNew + (1.0 - alpha) * lpFil.cMean[k];
			}
		}
		if (!lpFil.cMeanInitialized) lpFil.cMeanInitialized = true;
		lpFil.sampleCount++;
	}

	// LP benchmark: build the lpBenchmark WebSocket topic payload JSON.
	// Weighted log-linear regression: weight_k = C_k² (proportional to 1/var(log C_k) for
	// small fluctuations). High-C (small-s) points get strong weight; noisy large-s tails
	// are down-weighted, making Lp_meas stable even when L ≲ Lp.
	private static String buildLpJson() {
		if (lpFil == null || !lpFil.cMeanInitialized) return null;
		int nLp = lpFil.nSegs;
		double segLen = lpFil.segLen;
		// Weighted regression: Σw, Σw·s, Σw·logC, Σw·s², Σw·s·logC
		double sumW = 0, sumWS = 0, sumWLogC = 0, sumWS2 = 0, sumWSlogC = 0;
		int nFit = 0;
		for (int k = 1; k < nLp; k++) {
			double ck = lpFil.cMean[k];
			if (ck > 0.01) {
				double sk = k * segLen;
				double logC = Math.log(ck);
				double w = ck * ck;  // weight ∝ 1/var(log C) ≈ C²
				sumW += w; sumWS += w * sk; sumWLogC += w * logC;
				sumWS2 += w * sk * sk; sumWSlogC += w * sk * logC;
				nFit++;
			}
		}
		double lpMeas = Double.NaN;
		if (nFit >= 2 && sumW > 1e-30) {
			double denom = sumWS2 - sumWS * sumWS / sumW;
			if (Math.abs(denom) > 1e-30) {
				double b = (sumWSlogC - sumWS * sumWLogC / sumW) / denom;
				if (b < 0) lpMeas = -1.0 / b;
			}
		}
		// Build JSON
		StringBuilder sb = new StringBuilder(nLp * 10 + 160);
		sb.append(String.format(
			"{\"nSegs\":%d,\"segLen\":%.5g,\"contourLength\":%.4f,\"monomersPerSegment\":%d,\"EI\":%.4e,\"lpTheo\":%.4f,\"samples\":%d",
			nLp, segLen, lpFil.contourLength, benchMonCt, Env.EI, Env.persistenceLength, lpFil.sampleCount));
		if (!Double.isNaN(lpMeas)) {
			sb.append(String.format(",\"lpMeas\":%.4f", lpMeas));
		}
		sb.append(",\"cc\":[1.0");
		for (int k = 1; k < nLp; k++) {
			sb.append(String.format(",%.5g", lpFil.cMean[k]));
		}
		sb.append("]}");
		return sb.toString();
	}

	private static void resetBenchmarkChain() {
		if (deflFil.segs == null || deflFil.initCoords == null) return;
		for (int i = 0; i < deflFil.segs.length; i++) {
			FilSegment s = deflFil.segs[i];
			s.coord.setVals(deflFil.initCoords[i].x, deflFil.initCoords[i].y, deflFil.initCoords[i].z);
			s.uVec.setVals(1, 0, 0);
			s.yVec.setVals(0, 1, 0);
			s.initialize();
			s.forceSum.zero();
			s.torqueSum.zero();
		}
	}

	// C2/C3: drain pending inspect requests at the safe-point — after all physics phases
	// complete and before cleanup removes Things, inside synchronized(Env.safeO).
	// C3 pause/kill waits use this same point; both pause wait loops call this directly.
	private static void drainInspectQueue() {
		if (!LiveFrameServer.isRunning()) return;
		Integer id;
		while ((id = Env.inspectQueue.poll()) != null) {
			String json = ThreeJSWriter.buildInspectJson(id);
			LiveFrameServer.dispatchInspectResult(json);
		}
	}

	// C4: drain pending parameter changes at the safe-point — after inspect drain,
	// before logAndDraw. Each entry was validated on the WebSocket thread; apply,
	// then dispatch the success ack to all clients.
	private static void drainParamQueue() {
		if (!LiveFrameServer.isRunning()) return;
		Env.PendingParamChange change;
		while ((change = Env.paramQueue.poll()) != null) {
			double oldValue = change.param.getValue();
			change.param.setValue(change.newValue);
			// Special counter reset: when toFileInterval changes, advance the counter
			// to newInterval-1 so the next step's logAndDraw fires immediately.
			if ("toFileInterval".equals(change.param.label)) {
				threeJSCounter = (int) change.newValue - 1;
			}
			// Drag tensor refresh: when aeta changes, recompute bTransGam/bRotGam for all
			// FilSegments. Safe at the safe point — all worker threads are idle. Also
			// refresh tauTheo for the benchmark HUD if a benchmark chain is active.
			// Note: nodeTransDiff/RotDiff (static finals set at class load) are NOT
			// updated here — protein-node drag would need a fuller recalculation on aeta change.
			if ("aeta".equals(change.param.label)) {
				for (int i = 0; i < FilSegment.filSegmentCt; i++) {
					FilSegment.theFilSegments[i].calculateProperties();
				}
				if (Env.benchmarkFilament && deflFil.midSeg != null && deflFil.segs != null) {
					double spanM = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2) * 1e-6;
					double zetaPerp = deflFil.midSeg.bTransGam.y;
					deflFil.tauTheo = deflFil.segs.length * zetaPerp * Math.pow(spanM, 3)
						/ (Env.EI * Math.pow(Math.PI, 4));
				}
			}
			// Force-frac refresh: recompute transverse force and analytic deflection immediately.
			if ("benchmarkForceFrac".equals(change.param.label) && Env.benchmarkFilament && deflFil.firstSeg != null) {
				double spanM = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2) * 1e-6;
				if (spanM > 1e-15) {
					double newForceN = 48.0 * Env.EI * change.newValue / (spanM * spanM);
					deflFil.transForce.setVals(0, -newForceN, 0);
					deflFil.analyticDefl = change.newValue * spanM * 1e6;
				}
			}
			// lpActive 0→1 transition: reset accumulator so stale frozen-state data is discarded.
			if ("lpActive".equals(change.param.label) && oldValue == 0.0 && change.newValue == 1.0 && lpFil != null) {
				java.util.Arrays.fill(lpFil.cMean, 1.0);
				lpFil.cMeanInitialized = false;
				lpFil.sampleCount = 0;
			}
			LiveFrameServer.dispatchParamAck(change.param.label, oldValue, change.newValue);
		}
	}

	public static void updateCounters() {
		//update counters and flags
		paintedThisStep = false;
		Env.counter++;
		Env.simulationTime += Env.deltaT.getValue();
		remoteOutCounter++;
		collisionCkCounter++;
		ckElasticityCounter++;
		ckPersistenceCounter++;
		applyBrownianForcesCounter++;
		threeJSCounter++;
	}
	
	public static void logAndDraw() {
		drawCounter++;
		toFileCounter++;
		jSonCt++;
		jSonPlotCt++;
		jSon2Ct++;
		
		if ((Env.paintOn) & (drawCounter >= Env.drawInterval.getIntValue() | Env.simulationTime == 0)) {
			paintedThisStep = true;
			drawCounter = 0;
			StickyNode.stickyBoundStats();
		}

		if ((jSonCt >= Env.simJSonFreq) && (Env.writeSimJSons)) {
			FileOps.writeSimJSonsFrame();
			jSonCt = 0;
		}
		
		if ((jSonPlotCt >= Env.simJSonPlotFreq) && (Env.writeSimJSons)) {
			FileOps.saveJSonPlotData();
			Env.resetEventCounters();
			jSonPlotCt = 0;
		}
		
		if (jSon2Ct == Env.simJSon2StartCounting) { jSon2Ct = 0; Env.resetEventCounters2(); }  // for valid counting at first data point
		if ((jSon2Ct >= Env.simJSon2Freq) & (Env.simulationTime >= Env.simJSon2Start) & (Env.simulationTime <= Env.simJSon2Stop+Env.runBump) & (Env.writeSimJSons2)) {
			FileOps.saveJSonPlotData2();
			FileOps.writeSimJSonsFrame2();
			Env.resetEventCounters2();
			jSon2Ct = 0;
		}

		if (Env.glidingAssay.isActive() && glidingEvaluator != null) {
			glidingEvaluator.sampleStep();
		}

		if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning() || Env.glidingAssay.isActive()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
			if (Env.benchmarkFilament && LiveFrameServer.isRunning()) {
				String bmJson = buildBenchmarkJson();
				if (bmJson != null) LiveFrameServer.dispatchBenchmark(bmJson);
				accumulateLpData();
				String lpJson = buildLpJson();
				if (lpJson != null) LiveFrameServer.dispatchLpBenchmark(lpJson);
			}
			if (Env.glidingAssay.isActive() && glidingEvaluator != null) {
				String gaJson = glidingEvaluator.outputInterval();
				if (gaJson != null && LiveFrameServer.isRunning()) {
					LiveFrameServer.dispatchGlidingAssay(gaJson);
				}
			}
			threeJSCounter = 0;
		}

		if (remoteOutCounter >= Env.remoteReportInterval.getValue()) {
			if (Env.logFiles) { FileOps.writeToOutFile(); }

			if (Env.glidingAssay.isActive()) {
				MyosinFixed.glidingAssayDataSetRun ();
				//FileOps.writeToGAssayFile();
			}
			remoteOutCounter = 0;
		}
		
		if (Env.simulationTime - lastRunDetsTime >= 1) {
			reportRunTimeDets();
			lastRunDetsTime = Env.simulationTime;
		}
		
		if (Env.simulationTime >= Env.runTime.getValue()-1) Env.showActAs.setActive(true);
		
		
		
		
	}
	
	public static void reportRunTimeDets () {
		curLogAndDrawTime = System.currentTimeMillis();
		double timeDelta = curLogAndDrawTime-lastLogAndDrawTime;
		lastLogAndDrawTime = curLogAndDrawTime;
		double timePerStep = timeDelta/(Env.remoteReportInterval.getValue()*1000);	// factor of 1000 to get millis to seconds
		double timePerSimulatedSec = (1/Env.deltaT.getValue())*timePerStep;	// in seconds
		double timeTillRunDone = (Env.runTime.getValue()-Env.simulationTime)*timePerSimulatedSec; // in seconds
		System.out.println("Sim. time=" + String.valueOf(timeFormat.format(Env.simulationTime)) + "....~" + String.valueOf(timeFormat.format(timePerSimulatedSec/60)) + " minutes computation to simulate 1 sec, run finished in ~" + String.valueOf(timeFormat.format(timeTillRunDone/3600)) + " hours");
		//reportRunTimers();
	}
	
	public static void reportPlayerDets () {
		double curTime = System.currentTimeMillis();
		double timeTween = curTime - lastReportTime;
		lastReportTime = curTime;
		if (Env.logFiles) { 
			FileOps.writeToOutFile(); 
			FileOps.writeToFilLengthFile();
		}
		talk ("Time: " + Env.simulationTime);
		talk (" , timeTween: " + timeTween);
		talk (" , Actin Concentration: " + Env.actinConc.getValue());
		talk (" , # of Filament: " + FilSegment.getNumberOfFilaments());
		talk (" , # of Filament Segments: "  + FilSegment.filSegmentCt);
		talk (" , # of Mons: " + FilSegment.monomerSum());
		talkln (" , total length: " + FilSegment.lengthSum());
	}
	
	public static void reportActATetherStats () {
		if (Thing.lmBug != null) { 
			Thing.lmBug.reportColAndTetherPathForces(); 
			Thing.lmBug.reportTethersFormedAndBroken();
		}
	}
	
	public static void remoteLog() {
		if (remoteOutCounter >= Env.remoteReportInterval.getValue()) {
			//reportPlayerDets();
			reportRunTimeDets();
			remoteOutCounter = 0;
		}

		if (Env.glidingAssay.isActive() && glidingEvaluator != null) {
			glidingEvaluator.sampleStep();
		}

		if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning() || Env.glidingAssay.isActive()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
			if (Env.benchmarkFilament && LiveFrameServer.isRunning()) {
				String bmJson = buildBenchmarkJson();
				if (bmJson != null) LiveFrameServer.dispatchBenchmark(bmJson);
				accumulateLpData();
				String lpJson = buildLpJson();
				if (lpJson != null) LiveFrameServer.dispatchLpBenchmark(lpJson);
			}
			if (Env.glidingAssay.isActive() && glidingEvaluator != null) {
				String gaJson = glidingEvaluator.outputInterval();
				if (gaJson != null && LiveFrameServer.isRunning()) {
					LiveFrameServer.dispatchGlidingAssay(gaJson);
				}
			}
			threeJSCounter = 0;
		}
	}

	public static void makeInitialThings() {
		if (Env.benchmarkFilament) {
			Env.noMonomersSimd.setActive(true);

			// --- Deflection benchmark chain ---
			FilSegment[] segs = FilSegment.makeBenchmarkChain(Env.benchmarkNSegs);
			int n = segs.length;
			deflFil.firstSeg = segs[0];
			deflFil.lastSeg  = segs[n - 1];
			deflFil.midSeg   = segs[n / 2];
			deflFil.anchor1.setVals(segs[0].end1.x, segs[0].end1.y, segs[0].end1.z);
			deflFil.anchor2.setVals(segs[n-1].end2.x, segs[n-1].end2.y, segs[n-1].end2.z);
			double spanM = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2) * 1e-6;
			double forceN = 48.0 * Env.EI * Env.benchmarkForceFrac.getValue() / (spanM * spanM);
			deflFil.transForce.setVals(0, -forceN, 0); // negative Y: downward in default camera view
			deflFil.analyticDefl = Env.benchmarkForceFrac.getValue() * spanM * 1e6; // µm
			deflFil.segs = segs;
			deflFil.initCoords = new Pt3D[n];
			for (int i = 0; i < n; i++) {
				deflFil.initCoords[i] = new Pt3D(segs[i].coord.x, segs[i].coord.y, segs[i].coord.z);
			}
			// Suppress Brownian forces on deflection chain (per-segment, replacing removed global flag)
			for (FilSegment s : segs) s.brownianOff = true;

			benchMonCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
			deflFil.chainSpanMicrons = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2);

			System.out.printf("[BENCH] %d-seg × %d-mon/seg chain, span=%.4f µm, F=%.3e N, analytic δ=%.4f µm%n",
				n, benchMonCt, spanM * 1e6, forceN, deflFil.analyticDefl);
			System.err.printf("[BENCH:FORCE] deflFil.transForce=(%.4e, %.4e, %.4e) N  EI=%.4e  frac=%.4f  spanM=%.4e%n",
				deflFil.transForce.x, deflFil.transForce.y, deflFil.transForce.z,
				Env.EI, Env.benchmarkForceFrac.getValue(), spanM);

			benchStepCount = 0;
			deflFil.prevForceOn = true;
			deflFil.releaseStep = -1;
			deflFil.releaseDefl = Double.NaN;

			// τ_theo = N × ζ_perp_seg × L³ / (EI × π⁴)  (first bending mode, pinned-pinned)
			double zetaPerp = deflFil.midSeg.bTransGam.y;
			deflFil.tauTheo = n * zetaPerp * Math.pow(spanM, 3) / (Env.EI * Math.pow(Math.PI, 4));
			deflFil.tauMeas = Double.NaN;
			deflFil.tauMeasFrozen = false;
			System.out.printf("[BENCH] τ_theo=%.3f s  ζ_perp_seg=%.3e N·s/m%n", deflFil.tauTheo, zetaPerp);

			// Round 3 diagnostic: print each segment's center coord, length, and anchor flag
			for (int i = 0; i < n; i++) {
				boolean isAnchor = (segs[i] == deflFil.firstSeg || segs[i] == deflFil.lastSeg);
				System.err.printf("[BENCH:CHAIN] i=%d coord=(%.4f,%.4f,%.4f) length=%.4f isAnchor=%b%n",
					i, segs[i].coord.x, segs[i].coord.y, segs[i].coord.z, segs[i].length, isAnchor);
			}

			// --- LP benchmark chain (free BCs, Brownian forces) ---
			int monCtLp = benchMonCt;
			double segLenLp = (monCtLp + 1) * FilSegment.halfmono; // µm
			int nLp = (int) Math.round(Env.testLpFilLength / segLenLp);
			double lpYOff = -1.5, lpZOff = -0.5;
			FilSegment[] lpSegs = FilSegment.makeLpChain(nLp, lpYOff, lpZOff);
			lpFil = new LpFil();
			lpFil.segs = lpSegs;
			lpFil.nSegs = nLp;
			lpFil.segLen = segLenLp;
			lpFil.contourLength = nLp * segLenLp;
			lpFil.cMean = new double[nLp];
			java.util.Arrays.fill(lpFil.cMean, 1.0); // placeholder; real data accumulates from first frame
			lpFil.cMeanInitialized = false;
			lpFil.sampleCount = 0;
			System.out.printf("[LP] %d-seg × %d-mon/seg LP chain, contour=%.4f µm, offset=(0, %.1f, %.1f) µm%n",
				nLp, monCtLp, lpFil.contourLength, lpYOff, lpZOff);

			// Box sizing: use the larger of deflection span and LP contour length
			double maxSpan = Math.max(deflFil.chainSpanMicrons, lpFil.contourLength);
			double benchBoxDim = Math.max(maxSpan * 3.0, Env.boxXDim.getValue());
			if (Thing.theBox instanceof Chamber) {
				Chamber.dimX = benchBoxDim;
				Chamber.dimY = Math.max(benchBoxDim, Env.boxYDim.getValue());
				Chamber.dims.x = Chamber.dimX;
				Chamber.dims.y = Chamber.dimY;
				System.out.println("[BENCH] box auto-sized to "
					+ String.format("%.2f", Chamber.dimX) + " × "
					+ String.format("%.2f", Chamber.dimY) + " × "
					+ String.format("%.3f", Chamber.dimZ) + " µm");
			}

			if (Env.benchmarkManual) {
				// Manual tuning mode — user tunes via viewer; no auto search.
				System.out.printf("[BENCH:MANUAL] chain ready: span=%.4f µm  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f%n",
					deflFil.chainSpanMicrons, Env.fracMove.getValue(), Env.fracR.getValue(), Env.fracMoveTorq.getValue());
				return;
			}

			if (Env.benchmarkDiag) {
				System.out.printf("[BMDIAG] fixed-param diagnostic: fracR=%.4f  fracMoveTorq=%.4f  fracMove=%.4f  monomerCt=%d  span=%.4f µm%n",
					Env.fracR.getValue(), Env.fracMoveTorq.getValue(), Env.fracMove.getValue(), benchMonCt, deflFil.chainSpanMicrons);
				System.out.printf("[BMDIAG] analytic δ=%.6f µm  reporting every 5000 steps  cap=5,000,000 steps%n",
					deflFil.analyticDefl);
				return;
			}

			// Automated deflection tuning (-bm / -bmTunerVNN): arm the controller.
			if (Env.benchmarkTunerV25) {
				// V25 pre-pass starts at softest corner (fr=FR_HI=1.5, fmt=FMT_LO=0.01).
				double oldFr  = Env.fracR.getValue();
				double oldFmt = Env.fracMoveTorq.getValue();
				Env.fracR.setValue(DeflectionTunerV25.FR_HI);
				Env.fracMoveTorq.setValue(DeflectionTunerV25.FMT_LO);
				if (LiveFrameServer.isRunning()) {
					if (Env.fracR.getValue()        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  Env.fracR.getValue());
					if (Env.fracMoveTorq.getValue() != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, Env.fracMoveTorq.getValue());
				}
				deflTunerV25 = new DeflectionTunerV25();
				deflTunerV25.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV24) {
				// V24 starts from the stiffest corner (fr=FR_LO=0.1, fmt=FMT_HI=0.5), not the soft-start.
				double oldFr  = Env.fracR.getValue();
				double oldFmt = Env.fracMoveTorq.getValue();
				Env.fracR.setValue(DeflectionTunerV24.FR_LO);
				Env.fracMoveTorq.setValue(DeflectionTunerV24.FMT_HI);
				if (LiveFrameServer.isRunning()) {
					if (Env.fracR.getValue()        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  Env.fracR.getValue());
					if (Env.fracMoveTorq.getValue() != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, Env.fracMoveTorq.getValue());
				}
				deflTunerV24 = new DeflectionTunerV24();
				deflTunerV24.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV23) {
				// V23 starts from the stiffest corner (fr=FR_LO=0.1, fmt=FMT_HI=0.5), not the soft-start.
				double oldFr  = Env.fracR.getValue();
				double oldFmt = Env.fracMoveTorq.getValue();
				Env.fracR.setValue(DeflectionTunerV23.FR_LO);
				Env.fracMoveTorq.setValue(DeflectionTunerV23.FMT_HI);
				if (LiveFrameServer.isRunning()) {
					if (Env.fracR.getValue()        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  Env.fracR.getValue());
					if (Env.fracMoveTorq.getValue() != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, Env.fracMoveTorq.getValue());
				}
				deflTunerV23 = new DeflectionTunerV23();
				deflTunerV23.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else {
			// Soft-start: override to softest legal configuration so the first crossing is
			// guaranteed and convergence trajectory is independent of parameter-file state.
			{
				double oldFm  = Env.fracMove.getValue();
				double oldFr  = Env.fracR.getValue();
				double oldFmt = Env.fracMoveTorq.getValue();
				Env.fracMove.setValue(DeflectionTuner.FRAC_MOVE_MAX);
				Env.fracR.setValue(DeflectionTuner.FRAC_R_MAX);
				Env.fracMoveTorq.setValue(DeflectionTuner.FRAC_MT_MIN);
				if (LiveFrameServer.isRunning()) {
					if (Env.fracMove.getValue()     != oldFm)  LiveFrameServer.dispatchParamAck("fracMove",     oldFm,  Env.fracMove.getValue());
					if (Env.fracR.getValue()        != oldFr)  LiveFrameServer.dispatchParamAck("fracR",        oldFr,  Env.fracR.getValue());
					if (Env.fracMoveTorq.getValue() != oldFmt) LiveFrameServer.dispatchParamAck("fracMoveTorq", oldFmt, Env.fracMoveTorq.getValue());
				}
			}
			if (Env.benchmarkTunerV22) {
				deflTunerV22 = new DeflectionTunerV22();
				deflTunerV22.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV21) {
				deflTunerV21 = new DeflectionTunerV21();
				deflTunerV21.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV20) {
				deflTunerV20 = new DeflectionTunerV20();
				deflTunerV20.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV19) {
				deflTunerV19 = new DeflectionTunerV19();
				deflTunerV19.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV18) {
				deflTunerV18 = new DeflectionTunerV18();
				deflTunerV18.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV17) {
				deflTunerV17 = new DeflectionTunerV17();
				deflTunerV17.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV16) {
				deflTunerV16 = new DeflectionTunerV16();
				deflTunerV16.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else if (Env.benchmarkTunerV15) {
				deflTunerV15 = new DeflectionTunerV15();
				if (Env.benchmarkNoiseProbe) deflTunerV15.enableNoiseProbe();
				deflTunerV15.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			} else {
				deflTuner = new DeflectionTuner();
				deflTuner.start(
					Env.fracMove.getValue(),
					Env.fracR.getValue(),
					Env.fracMoveTorq.getValue(),
					deflFil.analyticDefl,
					deflFil.tauTheo
				);
			}
			} // ends the else from if (Env.benchmarkTunerV23)
			autoTuneStepCounter = 0;
			System.out.printf("[AUTOTUNE] armed: tuner=%s  fracMove=%.4f  fracR=%.4f  fracMoveTorq=%.4f  target=%.6f µm%n",
				Env.benchmarkTunerV25 ? "v25" : Env.benchmarkTunerV24 ? "v24" : Env.benchmarkTunerV23 ? "v23" : Env.benchmarkTunerV22 ? "v22" : Env.benchmarkTunerV21 ? "v21" : Env.benchmarkTunerV20 ? "v20" : Env.benchmarkTunerV19 ? "v19" : Env.benchmarkTunerV18 ? "v18" : Env.benchmarkTunerV17 ? "v17" : Env.benchmarkTunerV16 ? "v16" : Env.benchmarkTunerV15 ? "v15" : "v14",
				Env.fracMove.getValue(), Env.fracR.getValue(), Env.fracMoveTorq.getValue(), deflFil.analyticDefl);
			return;
		}
		if (Env.twoNodesOneFil) {
				FilSegment.twoNodesOneFilTst();
				Env.equilNodes.setValue(ProteinNode.nodeCt);
				return;
			}
			
			if (Env.actinAndMyoMinis) {
				FilSegment.filWMyoMinisTst();
				//FilSegment.makeStaticFilament();
				return;
			}
			
			if (Env.twoByTwoNodes) {
				FilSegment.twoByTwoNodesTst();
				Env.equilNodes.setValue(ProteinNode.nodeCt);  
				return;
			}
			
			if (Env.threeByThreeNodes.isActive()) {
				//StaticFilSegment.makeStaticHoop();
				FilSegment.threeByThreeNodesTst();
				Env.equilNodes.setValue(ProteinNode.nodeCt); 
				return;
			}
			
			if (Env.nodeChain) { 
				FilSegment.nodeChainTst();
				Env.equilNodes.setValue(ProteinNode.nodeCt); 
				return;
			}
			
			if (Env.xLinkTesting) {
				FilSegment.makeTestBranchedFilament();
				//FilSegment.makeXLinkFromNodePair();
			}
			
			if (Env.nodeLinkTesting) {
				StickyNode.makeSheetHexPackedNodes();
				//FilSegment.makeXLinkFromNodePair();
			}
			
			if (Env.fixedMyosinClusters.isActive()) {
				ProteinNode.makeMyosinClusterArray();
			}
			
			if (Env.glidingAssay.isActive()) {
				MyosinFixed.setUpGlidingAssay();
			}
			
			FilSegment.makeInitialFilaments();
			MyoMiniFilament.makeInitialMyoMiniFils();
			ProteinNode.makeInitialProteinNodes();
			
			//FilSegment.makeWestCircleFilaments();
			//FilSegment.makeEastCircleFilaments();
			
			//FilSegment.makeStaticFilament();
			//Myosin.makeTstMyosin();
			//MyosinDimer.makeTestDimer();
			//ProteinNode.makeTestNode();
			//MyoMiniFilament.makeTestMiniFil();
			
			Env.equilNodes.setValue(ProteinNode.nodeCt);  // after all nodes created, set value for number to maintain
	}

	public static void makeCrucible () {
		if (Env.bugShapedCrucible.isActive() & !Env.simOutsideBug.isActive()) {
			Bug.makeABugCrucible();
		} else {
			Chamber.makeABox();
		}
		if (Env.simOutsideBug.isActive()) { Bug.makeListeriaBug(); }
	}
	
	public static void restartRun (boolean newParamsLoaded) {
		synchronized(Env.safeO) {
			Monomer.removeAll();	// removes monomers from rendered files
			FilSegment.removeAll();
			ProteinNode.removeAll();
			FillNode.removeAll();
			MyoMiniFilament.removeAll();
			MyoFilLink.removeAll();
			FilLink.removeAll();
			ActA.removeAll();
			Arp23.removeAll();
			NodeLink.removeAll();
			Myosin.removeAll();
			MyosinDimer.removeAll();
			Thing.removeDeadThings();
			Thing.thingCt = 0;	// no Things left
			Thing.theBox = null;
			Thing.lmBug = null;
			makeCrucible();
			Env.simulationTime = Env.simStartTime;
					
			Env.setDependencies();
			FileOps.recalcJSonValues();
			makeInitialThings();
			
		}
	}
	
	public static void setRunning () {
		Env.paused = false;
	}

	public static void setPaused () {
		Env.paused = true;
	}

	private static void talkln (String info) {
		System.out.println(info);
	}
	
	private static void talk (String info) {
		System.out.print(info);
	}
}
