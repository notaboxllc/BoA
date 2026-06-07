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
	static RunTimer gatherTimer = new RunTimer("GatherForces");
	static RunTimer moveTimer = new RunTimer("Move");
	static RunTimer biochemTimer = new RunTimer("Biochem");
	static RunTimer resetCtTimer = new RunTimer("ResetCounters");
	static RunTimer cleanupTimer1 = new RunTimer("Cleanups1");
	static RunTimer cleanupTimer2 = new RunTimer("Cleanups2");
	static RunTimer cleanupTimer3 = new RunTimer("Cleanups3");
	static RunTimer cleanupTimer4 = new RunTimer("Cleanups4");


	static RunTimer [] runTimers = {collisionMeshTimer,motorsAndFilsColTimer,brownianTimer,xLinkTimer,stepTimer,gatherTimer,moveTimer,biochemTimer,resetCtTimer,cleanupTimer1};

	
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

	// Phase 4.5 scoping — when BOA_PHASE45_BIND_PROFILE=1, time the per-step
	// MyoMotor.fillSoaArrays + FilSegment.fillSoaArrays calls (P1.a + P1.b).
	// These feed the binding pose pack and are the Phase 4.5 retirement target
	// alongside the in-graph P4 pack inside GPUMotorBinding.detectBindings.
	static long fillSoaArraysNanos = 0;
	static int  fillSoaArraysCalls = 0;

	// Phase 4.5 — frozen-pose kernel-parity check. Triggered by
	// BOA_PHASE45_PARITY=<start>[:<stop>]; at every step in [start, stop],
	// immediately after the host-pack detectBindings() dispatch has fired and
	// ontoFilament has applied bindings, the resident-pose bind plan is
	// dispatched on the same frozen pose and boundSegId/arcOnFilDev are
	// compared element-wise. The pose is "frozen" between the two dispatches
	// because nothing between detectBindings and parityCheck mutates
	// Thing.soaCoord/soaUVec or the slot maps. After the last step in the
	// window, BoxOfActin.doLoop prints the aggregate summary and
	// System.exit(0)s to keep the trajectory from drifting past the window.
	// A single step is allowed via BOA_PHASE45_PARITY=<step> (start==stop).
	static final int PHASE45_PARITY_START = parsePhase45ParityRange()[0];
	static final int PHASE45_PARITY_STOP  = parsePhase45ParityRange()[1];
	private static int[] parsePhase45ParityRange() {
		String s = System.getenv("BOA_PHASE45_PARITY");
		if (s == null || s.isEmpty()) return new int[]{-1, -1};
		try {
			int colon = s.indexOf(':');
			if (colon < 0) {
				int v = Integer.parseInt(s.trim());
				return new int[]{v, v};
			}
			int a = Integer.parseInt(s.substring(0, colon).trim());
			int b = Integer.parseInt(s.substring(colon + 1).trim());
			return new int[]{a, b};
		} catch (NumberFormatException e) {
			return new int[]{-1, -1};
		}
	}

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

		// 2026-05-31 conformation diagnostic: env-var hooks
		// BOA_DIAG_JOINT_STATS=1     → enable conformation sampling (off by default)
		// BOA_DIAG_CPU_JOINTS=1      → force CPU-double joint computation (DOUBLE config)
		// BOA_DIAG_SINGLE_MYO=1      → enable single-myosin thermal characterization
		JointDiag.initFromEnv();
		SingleMyoDiag.initFromEnv();
		SingleFilDiag.initFromEnv();
		String cpuJointsEnv = System.getenv("BOA_DIAG_CPU_JOINTS");
		if (cpuJointsEnv != null && !cpuJointsEnv.isEmpty()
		    && !cpuJointsEnv.equals("0") && !cpuJointsEnv.equalsIgnoreCase("false")) {
			GPUMoveThing.DIAG_CPU_JOINTS = true;
			System.err.println("[JOINT_DIAG] DIAG_CPU_JOINTS forced ON via env var");
		}
		// BOA_DIAG_CPU_ANCHOR=1 → skip the device anchor-spring kernel
		// contribution and run MyosinFixed.applyGPUDroppedForces on CPU
		// (the pre-Phase-1 behaviour). Default off — device kernel applies
		// the anchor. See JOURNAL "Phase 1 — anchor spring ported to device".
		String cpuAnchorEnv = System.getenv("BOA_DIAG_CPU_ANCHOR");
		if (cpuAnchorEnv != null && !cpuAnchorEnv.isEmpty()
		    && !cpuAnchorEnv.equals("0") && !cpuAnchorEnv.equalsIgnoreCase("false")) {
			GPUMoveThing.DIAG_CPU_ANCHOR = true;
			System.err.println("[ANCHOR_DIAG] DIAG_CPU_ANCHOR forced ON via env var");
		}
		// BOA_DIAG_CPU_F3F4 — toggle the Phase-2 F3/F4 source-of-truth.
		//   default (unset)             → device chainPairForces kernel runs
		//                                 (Newton-3-safe owner-perspective
		//                                 linkUVec; bench lands on CPU value).
		//   "1" / "true"                → CPU addLinkForces /
		//                                 addTorsionSpringForces runs and the
		//                                 device kernel is gated off.
		//   "0" / "false"               → device kernel runs (same as default).
		// See JOURNAL "Phase 2 F3/F4 — fix".
		String cpuF3F4Env = System.getenv("BOA_DIAG_CPU_F3F4");
		if (cpuF3F4Env != null && !cpuF3F4Env.isEmpty()) {
			if (cpuF3F4Env.equals("0") || cpuF3F4Env.equalsIgnoreCase("false")) {
				GPUMoveThing.DIAG_CPU_F3F4 = false;
				System.err.println("[F3F4_DIAG] DIAG_CPU_F3F4 forced OFF via env var (device kernel ACTIVE)");
			} else {
				GPUMoveThing.DIAG_CPU_F3F4 = true;
				System.err.println("[F3F4_DIAG] DIAG_CPU_F3F4 forced ON via env var (CPU pair runs)");
			}
		}
		// BOA_DIAG_CPU_F1 — toggle the Phase-2 F1 box-boundary source-of-truth.
		//   default (unset)             → device boundaryBoxKernel runs and
		//                                 FilSegment.checkBugOrBoxCollision
		//                                 skips checkBugCollisionFromInside.
		//   "1" / "true"                → CPU checkBugCollisionFromInside
		//                                 runs and the device kernel is
		//                                 gated off (boundaryActive[i]=0
		//                                 for every slot — kernel still
		//                                 launched but every thread
		//                                 early-returns).
		//   "0" / "false"               → device kernel runs (same as default).
		// The Listeria from-outside branch (simOutsideBug active) stays on
		// CPU either way — only the from-inside box wall is gated here.
		// See JOURNAL "Phase 2 F1 (box) — implementation".
		String cpuF1Env = System.getenv("BOA_DIAG_CPU_F1");
		if (cpuF1Env != null && !cpuF1Env.isEmpty()) {
			if (cpuF1Env.equals("0") || cpuF1Env.equalsIgnoreCase("false")) {
				GPUMoveThing.DIAG_CPU_F1 = false;
				System.err.println("[F1_DIAG] DIAG_CPU_F1 forced OFF via env var (device kernel ACTIVE)");
			} else {
				GPUMoveThing.DIAG_CPU_F1 = true;
				System.err.println("[F1_DIAG] DIAG_CPU_F1 forced ON via env var (CPU pair runs)");
			}
		}
		// BOA_DIAG_DEVICE_BOUNDARY_TIPC — toggle the Phase-2 F1 tipC writeback
		// (independent of BOA_DIAG_CPU_F1: the boundary FORCE/TORQUE arm is
		// unaffected; only the tipC clearance write-back into FilSegment is
		// gated).
		//   default (unset)             → bridgeBoundaryTipC() runs (writeback ON).
		//                                 Polymerizing tips arrest at the wall.
		//   "0" / "false"               → bridge skipped; pre-fix behaviour
		//                                 (tips polymerize through the wall on
		//                                 device runs). For the A/B that
		//                                 reproduces the bug.
		//   "1" / "true"                → bridge runs (same as default).
		// See JOURNAL "tipC device writeback (box) — implementation".
		String tipcEnv = System.getenv("BOA_DIAG_DEVICE_BOUNDARY_TIPC");
		if (tipcEnv != null && !tipcEnv.isEmpty()) {
			if (tipcEnv.equals("0") || tipcEnv.equalsIgnoreCase("false")) {
				GPUMoveThing.DIAG_DEVICE_BOUNDARY_TIPC = false;
				System.err.println("[F1_DIAG] DIAG_DEVICE_BOUNDARY_TIPC forced OFF (tipC writeback DISABLED)");
			} else {
				GPUMoveThing.DIAG_DEVICE_BOUNDARY_TIPC = true;
				System.err.println("[F1_DIAG] DIAG_DEVICE_BOUNDARY_TIPC forced ON (tipC writeback ENABLED)");
			}
		}
		// BOA_DIAG_CPU_MOTOR — toggle the Phase-2 F8/F9/F10 motor cross-bridge
		// force source-of-truth.
		//   default (unset)             → device motorForce+segMotorForce kernels run
		//                                 and the CPU MyoFilLink addForces /
		//                                 alignUVecTorque / alignYVecTorque
		//                                 pair is gated off in MyoFilLink.step()
		//                                 for device-handled bound motors.
		//   "1" / "true"                → CPU pair runs and the device kernels
		//                                 still launch but every motor
		//                                 early-returns via boundSegSlot=-1.
		//   "0" / "false"               → device kernels run (same as default).
		// release kinetics (ckRelease / dissociateADP) and binding detection
		// (Phase 3 grid) stay on CPU either way; this flag only toggles the
		// per-step force computation (F8/F9/F10).
		// See JOURNAL "Motor force port (F8-F10) — implementation".
		String cpuMotorEnv = System.getenv("BOA_DIAG_CPU_MOTOR");
		if (cpuMotorEnv != null && !cpuMotorEnv.isEmpty()) {
			if (cpuMotorEnv.equals("0") || cpuMotorEnv.equalsIgnoreCase("false")) {
				GPUMoveThing.DIAG_CPU_MOTOR = false;
				System.err.println("[MOTOR_DIAG] DIAG_CPU_MOTOR forced OFF via env var (device kernels ACTIVE)");
			} else {
				GPUMoveThing.DIAG_CPU_MOTOR = true;
				System.err.println("[MOTOR_DIAG] DIAG_CPU_MOTOR forced ON via env var (CPU pair runs)");
			}
		}
		// BOA_DIAG_RELEASE_LAG — induce a 1-step lag in the CPU release path so
		// ckRelease and forceDotFilTrack see last step's forceDotFil rather than
		// this step's. Default off (CPU release reads fresh same-step value).
		// "1" / "true" turns the lag ON; "0" / "false" forces it OFF. The flag
		// is a no-op on the device-handled motor path (gpuMotorHandled gates
		// the CPU pair off, so addForces never runs and prevForceDotFil is
		// never read). See JOURNAL "Motor-port borderline — release-lag
		// confirmation".
		String releaseLagEnv = System.getenv("BOA_DIAG_RELEASE_LAG");
		if (releaseLagEnv != null && !releaseLagEnv.isEmpty()) {
			if (releaseLagEnv.equals("0") || releaseLagEnv.equalsIgnoreCase("false")) {
				GPUMoveThing.DIAG_RELEASE_LAG = false;
				System.err.println("[RELEASE_LAG_DIAG] DIAG_RELEASE_LAG forced OFF (CPU release reads same-step forceDotFil)");
			} else {
				GPUMoveThing.DIAG_RELEASE_LAG = true;
				System.err.println("[RELEASE_LAG_DIAG] DIAG_RELEASE_LAG forced ON (CPU release reads prior-step forceDotFil; mimics device lag)");
			}
		}
		// BOA_DIAG_RELEASE_READ — when set to a file path, MyoFilLink.ckRelease
		// emits one CSV record per call to that file:
		//   step,motorId,segId,forceMag,forceDotFil,trackedAvg,releaseFired
		// Decision logic is unchanged (the same forceMag/forceDotFil the roll
		// already reads are captured at function exit; the catch+slip roll,
		// breakForce gate, and inRigor gate are unmodified). Used to measure
		// the actual per-step read divergence between device and fresh-CPU arms
		// at the moment ckRelease consumes the force values.
		String releaseReadEnv = System.getenv("BOA_DIAG_RELEASE_READ");
		if (releaseReadEnv != null && !releaseReadEnv.isEmpty()) {
			try {
				java.io.File f = new java.io.File(releaseReadEnv);
				java.io.File parent = f.getParentFile();
				if (parent != null) parent.mkdirs();
				java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(f, false)));
				pw.println("step,motorId,segId,forceMag,forceDotFil,trackedAvg,releaseFired");
				GPUMoveThing.DIAG_RELEASE_READ_WRITER = pw;
				System.err.println("[RELEASE_READ_DIAG] logging to " + releaseReadEnv);
				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
					try { GPUMoveThing.diagReleaseReadFlush(); pw.close(); } catch (Throwable ignored) {}
				}));
			} catch (java.io.IOException ioe) {
				System.err.println("[RELEASE_READ_DIAG] failed to open " + releaseReadEnv + ": " + ioe);
			}
		}
		// Phase 3 validation companion (2026-06-04). When BOA_DIAG_RELEASE_READ_WB
		// is set to a file path, GPUMoveThing.bridgeMotorForceWriteback writes one
		// record per per-motor bridge call:
		//   step,motorId,segId,wbForceMag,wbForceDotFil
		// Matching on (step, motorId) against BOA_DIAG_RELEASE_READ's CSV lets us
		// confirm post-fix that ckRelease's read at step N == the bridge writeback
		// at step N (i.e., current-step), not step N-1.
		String releaseWbEnv = System.getenv("BOA_DIAG_RELEASE_READ_WB");
		if (releaseWbEnv != null && !releaseWbEnv.isEmpty()) {
			try {
				java.io.File f = new java.io.File(releaseWbEnv);
				java.io.File parent = f.getParentFile();
				if (parent != null) parent.mkdirs();
				java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(f, false)));
				pw.println("step,motorId,segId,wbForceMag,wbForceDotFil");
				GPUMoveThing.DIAG_RELEASE_WB_WRITER = pw;
				System.err.println("[RELEASE_READ_WB_DIAG] logging writebacks to " + releaseWbEnv);
				Runtime.getRuntime().addShutdownHook(new Thread(() -> {
					try { GPUMoveThing.diagReleaseWbFlush(); pw.close(); } catch (Throwable ignored) {}
				}));
			} catch (java.io.IOException ioe) {
				System.err.println("[RELEASE_READ_WB_DIAG] failed to open " + releaseWbEnv + ": " + ioe);
			}
		}

		String dumpChainEnv = System.getenv("BOA_DIAG_DUMP_CHAIN_STEP");
		if (dumpChainEnv != null && !dumpChainEnv.isEmpty()) {
			try {
				GPUMoveThing.DIAG_DUMP_CHAIN_STEP = Integer.parseInt(dumpChainEnv.trim());
				System.err.println("[F3F4_DIAG] dump chain forces at step "
				    + GPUMoveThing.DIAG_DUMP_CHAIN_STEP);
			} catch (NumberFormatException ignored) {}
		}

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
		// 2026-05-31 joint param + signed-torque diagnostic — Part 1 startup dump
		// (no-op when BOA_DIAG_PARAMS unset). Must run AFTER makeInitialThings so
		// drag tensors are populated on the first Myosin.
		JointParamDiag.initFromEnv();
		JointParamDiag.dumpParams();
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
				talkln (" -gpu : route motor-binding decision through GPUMotorBinding (TornadoVM); CPU motor-binding ThreadSets skipped");
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
			if (args[i].equals("-singleFilDiag")) {
				// Phase 2 F3/F4 SingleFilDiag probe: pinned-ends bench chain
				// with NO midpoint force, no Brownian (segments default
				// brownianOff=true in bench mode); reports max coord.y/z
				// drift each output interval and at run end. Pass = chain
				// stays numerically straight on -gpu.
				Env.benchmarkFilament = true;
				Env.benchmarkDiag = true;          // reuse the headless diag step loop
				Env.benchmarkForceOn.setValue(0);  // suppress the midpoint force
				SingleFilDiag.ENABLED = true;
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
			if (args[i].equals("-gpu")) {
				Env.useGPU = true;
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
				// Per-thread force/torque accumulators need at least thingCt slots.
				// Grown lazily with 25% headroom; reallocates only when thingCt outpaces capacity.
				Thing.ensureAccumCapacity(Thing.thingCt);
				// Zero the canonical SoA force/torque slots for the active Things.
				// This replaces the per-Thing forceSum.zero()/torqueSum.zero() that
				// used to live in resetCounters — one memset over thingCt*3 floats.
				Thing.clearSoaForcesTorques(Thing.thingCt);
				// SoA sync: snapshot motor and filament positions for 3D grid (step 1a)
				long _fillSoaT0 = (Env.useGPU && GPUMotorBinding.isBindProfileEnabled())
				                  ? System.nanoTime() : 0L;
				Phase45Trace.snapshot("2_preFillSoa");
				MyoMotor.fillSoaArrays();
				FilSegment.fillSoaArrays();
				Phase45Trace.snapshot("3_postFillSoa");
				if (Env.useGPU && GPUMotorBinding.isBindProfileEnabled()) {
					fillSoaArraysNanos += System.nanoTime() - _fillSoaT0;
					fillSoaArraysCalls++;
				}
				// iter2c: classify Things for the GPU moveThing kernel before the
				// Brownian phase, so calcRandomForces() can skip GPU-handled Things.
				// No-op on topology-stable steps after the first call.
				if (Env.useGPU) { GPUMoveThing.onStepStart(); }
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
				// Phase 3 (2026-06-04): grid build moved to device. The CPU
				// FillThreads is now skipped on the GPU path — the device
				// segBbox + gridAssemble kernels (chained inside GPUMotorBinding's
				// TaskGraph) build the CSR from already-resident endpoints.
				// CPU FillThreads is still callable for the Phase 3 CP1
				// checkpoint, which dispatches it explicitly on a frozen pose.
				// CPU 27-neighbour query (motCollStart/Stop) only runs on the
				// CPU path; FillThreads is still wired ahead of it there.
				if (!Env.useGPU) {
					startAllThreadSets(Env.motorBindGrid3DStart);
					waitOnAllThreadSets(Env.motorBindGrid3DStop);
				}
				if (Env.useGPU) {
					Phase45Trace.snapshot("4_preBindingDispatch");
					GPUMotorBinding.detectBindings();
					// Phase 4.5 — frozen-pose kernel-parity check. Fires
					// once at BOA_PHASE45_PARITY=<step>; resident dispatch
					// reads the move plan's coord/uVec/soaLengthArr via the
					// slot maps populated by GPUMoveThing.classifyThings().
					// The demand-sync below ensures the host-side pose
					// FloatArrays carry the same values fillSoaArrays just
					// read (otherwise the resident plan's EVERY_EXECUTION
					// upload would carry stale host data while the host-pack
					// plan saw fresh CPU pose).
					if (PHASE45_PARITY_START >= 0
					    && Env.counter >= PHASE45_PARITY_START
					    && Env.counter <= PHASE45_PARITY_STOP) {
						GPUMoveThing.demandSyncPoseToHostForParity();
						GPUMotorBinding.parityCheck();
						if (Env.counter == PHASE45_PARITY_STOP) {
							GPUMotorBinding.reportParitySummary();
							System.out.printf("[PARITY] freezing run at step %d%n",
							                  Env.counter);
							System.exit(0);
						}
					}
				} else {
					startAllThreadSets(Env.motCollStart);
					waitOnAllThreadSets(Env.motCollStop);
				}
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

				// actual myosin joints. On the GPU path, the per-Myosin
				// jointConstraints() kernel is the first task of the chained
				// TaskGraph in GPUMoveThing.moveThings() — it ADDS joint
				// forces/torques directly to the shared device-side forceSum/
				// torqueSum that the move kernel then reads. So nothing
				// dispatches here on the GPU path; the CPU Myosin.myoThreads
				// short-circuits when useGPU is set, and MyosinDimer
				// (cross-Myosin coupling) keeps its CPU dispatch in the
				// myoJoints1 wave.
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

				// Sum each thread's force/torque slots (double) into the canonical SoA
				// arrays (float) and zero the per-thread slots. Must run after every
				// phase that calls incForceSum (xLink, membrane, joints1/2, step) and
				// before moveThing/GPU pack.
				gatherTimer.start();
				startAllThreadSets(Env.gatherForcesStart);
				waitOnAllThreadSets(Env.gatherForcesStop);
				gatherTimer.stopInc();

				// F1 benchmark: apply transverse force to midpoint segment before integration
				if (Env.benchmarkFilament && deflFil.midSeg != null && Env.benchmarkForceOn.getValue() != 0) {
					deflFil.midSeg.incForceSum(deflFil.transForce);
				}
				// Round 3 diagnostic: trace force application path
				if (Env.benchmarkFilament && deflFil.midSeg != null && benchStepCount < 10) {
					System.err.printf("[BENCH:STEP] step=%d forceSum=(%.4e,%.4e,%.4e) coordAsPt3D()=(%.4f,%.4f,%.4f) veloc.y=%.4e%n",
						benchStepCount,
						deflFil.midSeg.getForceSumX(), deflFil.midSeg.getForceSumY(), deflFil.midSeg.getForceSumZ(),
						deflFil.midSeg.getCoordX(), deflFil.midSeg.getCoordY(), deflFil.midSeg.getCoordZ(),
						deflFil.midSeg.veloc.y);
				}

				moveTimer.start();
				if (Env.useGPU) {
					// Iteration 2b: unified Thing.moveThing() kernel. The GPU path
					// packs eligible Things (MyoMotor/MyoRod/MyoLever/root FilSegment
					// in this first pass), runs the branchless integration kernel,
					// unpacks coordAsPt3D()/uVecAsPt3D()/yVecAsPt3D(), and runs initialize() on the affected
					// Things. Ineligible Things (Bug, ProteinNode, MyoMiniFilament,
					// branches, ActA-bound segments, etc.) fall back to CPU
					// moveThing() inside GPUMoveThing.moveThings(). Crucible/Chamber/
					// AnchorNode have empty moveThing overrides and the fallback
					// dispatch is a no-op for them.
					GPUMoveThing.moveThings();
					// Phase 4.5 scoping — poison the frame-only host mirrors
					// (Thing.soaEnd1/End2/ZVec/TransXTox + per-FilSegment
					// xRange/end1Pt/end2Pt + per-MyoMotor bindTip) so any
					// per-step reader between frames sees a sentinel offset.
					// No-op unless BOA_PHASE45_POISON=1. refresh restores
					// before any output-frame dispatch.
					GPUMoveThing.poisonFrameOnlyMirrors();
					Phase45Trace.snapshot("1_postPoison");
				} else {
					startAllThreadSets(Env.moveStart);
					waitOnAllThreadSets(Env.moveStop);
				}
				moveTimer.stopInc();

				// F1 benchmark: restore pinned endpoints after integration
				if (Env.benchmarkFilament) { applyBenchmarkPins(); }
				// Round 3 diagnostic: midpoint coordAsPt3D() after integration + pin correction
				if (Env.benchmarkFilament && deflFil.midSeg != null && benchStepCount < 10) {
					System.err.printf("[BENCH:POST] step=%d getCoordY()=%.6e veloc.y=%.4e%n",
						benchStepCount, deflFil.midSeg.getCoordY(), deflFil.midSeg.veloc.y);
				}
				biochemTimer.start();
				startAllThreadSets(Env.biochemStart);
				waitOnAllThreadSets(Env.biochemStop);
				biochemTimer.stopInc();

				resetCtTimer.start();
				startAllThreadSets(Env.resetCtStart);
				waitOnAllThreadSets(Env.resetCtStop);
				resetCtTimer.stopInc();

				// Membrane relaxation loop... special passes to allow forces to propogate/move nodes, especially laterally at collisions
				int mPass = 0;
				NodeLink.maxStrain = 10;
				while (NodeLink.maxStrain > Env.membraneMaxLinkStrain.getValue() && mPass < Env.maxMembranePasses.getIntValue()) {
					NodeLink.maxStrain = 0;	// zero before each pass... values set in NodeLink.enforceLink()

					startAllThreadSets(Env.membraneLinksStart);
					waitOnAllThreadSets(Env.membraneLinksStop);
					//System.out.println("max membrane strain = " + NodeLink.maxStrain);

					// Gather thread-local forces from membraneLinks before membraneMove reads forceSum.
					startAllThreadSets(Env.gatherForcesStart);
					waitOnAllThreadSets(Env.gatherForcesStop);

					startAllThreadSets(Env.membraneMoveStart);
					waitOnAllThreadSets(Env.membraneMoveStop);

					mPass++;
				}

				updateCounters();

				// Phase 4.5 Part-1 — periodic device-memory tick. No-op unless
				// BOA_PHASE45_MEM_TRACE=1. Logs at MEM_TRACE_STEP_INTERVAL cadence.
				if (Env.useGPU && GPUMoveThing.MEM_TRACE) {
					GPUMoveThing.memTraceTick();
				}

				// 2026-05-31 conformation diagnostic — no-op when JointDiag.ENABLED=false.
				JointDiag.sample();
				// 2026-05-31 joint param + signed-torque diagnostic — Part 3 late-step dump
				// (no-op when BOA_DIAG_PARAMS unset).
				JointParamDiag.sample();
				// 2026-05-31 single-myosin thermal characterization — no-op when
				// BOA_DIAG_SINGLE_MYO unset.
				SingleMyoDiag.sample();
				// 2026-06-02 Phase 2 F3/F4 — single-filament chain straightness
				// probe — no-op when -singleFilDiag / BOA_DIAG_SINGLE_FIL unset.
				SingleFilDiag.sample();

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
				// BOA_BMDIAG_MAX_STEPS overrides the default 5M step cap (useful for shorter
				// characterization runs during pre-port baselines).
				if (Env.benchmarkDiag) {
					benchStepCount++;
					// LP characterization: accumulate tangent-correlation EWMA every output-interval steps
					// (matches the production cadence in the frame-write block above). Headless: no
					// LiveFrameServer needed; just builds lpFil.cMean for computeLpMeas().
					if (lpFil != null && benchStepCount % Env.toFileInterval.getIntValue() == 0) {
						accumulateLpData();
					}
					if (benchStepCount % 5000 == 0) {
						double ratio = computeDeflectionRatio();
						double defl = deflFil.analyticDefl * ratio;
						double lpMeas = computeLpMeas();
						System.out.printf("[BMDIAG] step=%8d  simT=%8.2fs  ratio=%.6f  defl=%.6fµm  lpMeas=%.4fµm  lpSamples=%d%n",
							benchStepCount, Env.simulationTime, ratio, defl,
							lpMeas, lpFil == null ? 0 : lpFil.sampleCount);
						System.out.flush();
					}
					if (benchStepCount >= bmDiagMaxSteps()) {
						double lpMeas = computeLpMeas();
						System.out.printf("[BMDIAG] DONE: %d steps  simT=%.1fs  final ratio=%.6f  final lpMeas=%.4fµm  lpTheo=%.4fµm  lpSamples=%d%n",
							benchStepCount, Env.simulationTime, computeDeflectionRatio(),
							lpMeas, Env.persistenceLength, lpFil == null ? 0 : lpFil.sampleCount);
						SingleFilDiag.reportFinal();
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
		// 2026-05-31 conformation diagnostic: no-op when disabled.
		JointDiag.dump();
		SingleMyoDiag.dump();
		// step() per-force profile — no-op when BOA_STEP_PROFILE unset.
		StepProfiler.report();
		System.out.printf("[STATS] bindEvents=%d%n", MyoMotor.totalBindEvents);
		System.out.printf("[STATS] checkBugInsideFireCt=%d%n", FilSegment.DIAG_BUG_INSIDE_FIRE_CT);
		System.out.printf("[STATS] addLinkForcesFireCt=%d%n", FilSegment.DIAG_ADDLINK_FIRE_CT);
		System.out.printf("[STATS] addTorsionFireCt=%d%n", FilSegment.DIAG_ADDTORSION_FIRE_CT);
		// Phase 4.5 stale-reader probe (2026-06-05): one counter per candidate
		// per-step end1AsPt3D()/getEnd1*/end1Pt reader. The nonzero counter on a
		// -gpu glidingAssay500_val run identifies the leaking path.
		System.out.printf("[STATS] meshFillFilSegFireCt=%d%n",     Mesh.DIAG_MESH_FILL_FILSEG_CT);
		System.out.printf("[STATS] meshAllSegsFireCt=%d%n",        FilSegment.DIAG_MESHALLSEGS_FIRE_CT);
		System.out.printf("[STATS] mbg3dFillFilSegFireCt=%d%n",    MotorBindGrid3D.DIAG_MBG3D_FILL_FILSEG_CT);
		System.out.printf("[STATS] updatePosFromBindFireCt=%d%n",  MyoFilLink.DIAG_UPDATEPOS_FROM_BIND_CT);
		System.out.printf("[STATS] updatePosFromStepFireCt=%d%n",  MyoFilLink.DIAG_UPDATEPOS_FROM_STEP_CT);
		System.out.printf("[STATS] validateSegFireCt=%d%n",        MyoFilLink.DIAG_VALIDATESEG_FIRE_CT);
		System.out.printf("[STATS] anchorFireCt=%d%n",             MyosinFixed.DIAG_ANCHOR_FIRE_CT);
		System.out.printf("[STATS] filSegInitFireCt=%d%n",         FilSegment.DIAG_FILSEG_INIT_CT);
		System.out.printf("[STATS] motorInitFireCt=%d%n",          MyoMotor.DIAG_MOTOR_INIT_CT);
		if (MyoMotor.boundMotorSampleCt > 0) {
			System.out.printf("[STATS] meanBoundMotors=%.3f%n", (double)MyoMotor.boundMotorSum / MyoMotor.boundMotorSampleCt);
		}
		if (Env.useGPU && GPUMotorBinding.getCallCount() > 0) {
			int    calls = GPUMotorBinding.getCallCount();
			double tot   = GPUMotorBinding.getTotalNanos()    / 1.0e9;
			double pk    = GPUMotorBinding.getPackNanos()     / 1.0e9;
			double gp    = GPUMotorBinding.getGridPackNanos() / 1.0e9;
			double ex    = GPUMotorBinding.getExecNanos()     / 1.0e9;
			double un    = GPUMotorBinding.getUnpackNanos()   / 1.0e9;
			System.out.printf("[STATS] gpuMotorBinding total=%.3fs calls=%d pack=%.3fs gridPack=%.3fs exec=%.3fs unpack=%.3fs%n",
				tot, calls, pk, gp, ex, un);
			GPUMotorBinding.reportCheckpointSummary();
			// Phase 4.5 scoping — per-task TornadoVM profile breakdown of the
			// .execute() block, alongside the per-step fillSoaArrays time
			// (P1.a + P1.b CPU pose snapshot that feeds the binding pack).
			if (GPUMotorBinding.isBindProfileEnabled()
			    && GPUMotorBinding.getBindProfileSamples() > 0) {
				int samples = GPUMotorBinding.getBindProfileSamples();
				double fillSoa = fillSoaArraysNanos / 1.0e9;
				double pcieIn  = GPUMotorBinding.getBindWriteNanos()              / 1.0e9;
				double pcieOut = GPUMotorBinding.getBindReadNanos()               / 1.0e9;
				double kSeg    = GPUMotorBinding.getBindSegBboxKernelNanos()      / 1.0e9;
				double kGrid   = GPUMotorBinding.getBindGridAssembleKernelNanos() / 1.0e9;
				double kBind   = GPUMotorBinding.getBindBindKernelNanos()         / 1.0e9;
				double kAll    = GPUMotorBinding.getBindDeviceKernelTotalNanos()  / 1.0e9;
				double bindTot = GPUMotorBinding.getTotalNanos()                  / 1.0e9;
				double msScale = 1000.0 / Math.max(1, samples);
				System.out.printf("[PHASE45_BIND_PROFILE] samples=%d bindTotal=%.3fs  "
				                + "fillSoa(P1.ab)=%.3fs/%.3fms  cpuPack(P4)=%.3fs/%.3fms  "
				                + "pcieWrite=%.3fs/%.3fms  segBboxK=%.3fs/%.3fms  "
				                + "gridAssembleK=%.3fs/%.3fms  bindK=%.3fs/%.3fms  "
				                + "kernelsAll=%.3fs/%.3fms  pcieRead=%.3fs/%.3fms  "
				                + "cpuUnpack=%.3fs/%.3fms%n",
				    samples, bindTot,
				    fillSoa, fillSoa * msScale,
				    pk,      pk      * msScale,
				    pcieIn,  pcieIn  * msScale,
				    kSeg,    kSeg    * msScale,
				    kGrid,   kGrid   * msScale,
				    kBind,   kBind   * msScale,
				    kAll,    kAll    * msScale,
				    pcieOut, pcieOut * msScale,
				    un,      un      * msScale);
				// Percent-of-bindTotal split of the four buckets the scoping
				// prompt asked for. fillSoa+cpuPack+pcieWrite = "pose pack /
				// transfer" (Phase 4.5 retires); segBbox+gridAssemble = "grid
				// build"; bind = "bind kernel"; pcieRead+cpuUnpack = "CSR /
				// result transfer". The unaccounted remainder is JVM/profile
				// overhead + plan dispatch.
				double pose  = fillSoa + pk + pcieIn;
				double grid  = kSeg + kGrid;
				double bind  = kBind;
				double csr   = pcieOut + un;
				if (bindTot > 0) {
					System.out.printf("[PHASE45_BIND_BUCKETS] pose=%.3fs(%.1f%%)  "
					                + "grid=%.3fs(%.1f%%)  bind=%.3fs(%.1f%%)  "
					                + "csr=%.3fs(%.1f%%)  acct=%.3fs(%.1f%%)%n",
					    pose, 100.0 * pose / bindTot,
					    grid, 100.0 * grid / bindTot,
					    bind, 100.0 * bind / bindTot,
					    csr,  100.0 * csr  / bindTot,
					    (pose + grid + bind + csr),
					    100.0 * (pose + grid + bind + csr) / bindTot);
				}
			}
		}
		if (Env.useGPU && GPUMoveThing.getCallCount() > 0) {
			int    calls = GPUMoveThing.getCallCount();
			double tot   = GPUMoveThing.getTotalNanos()      / 1.0e9;
			double pk    = GPUMoveThing.getPackNanos()       / 1.0e9;
			double jpk   = GPUMoveThing.getJointPackNanos()  / 1.0e9;
			double ex    = GPUMoveThing.getExecNanos()       / 1.0e9;
			double un    = GPUMoveThing.getUnpackNanos()     / 1.0e9;
			// pack includes jointPack — print slotPack as (pack - jointPack) for a clean breakdown.
			System.out.printf("[STATS] gpuMoveThing total=%.3fs calls=%d slotPack=%.3fs jointPack=%.3fs exec=%.3fs unpack=%.3fs%n",
				tot, calls, pk - jpk, jpk, ex, un);
			// Phase 4 flip — residency boundary stats. demandSyncPose is the
			// per-step device→host copy of coord/uVec/yVec (replaces OP_UNPACK).
			// demandSyncDerived is the output-frame refresh path (cold/cheap).
			// planRebuild counts topology-dirty rebuild events.
			double dspN = GPUMoveThing.getDemandSyncPoseNanos()    / 1.0e9;
			int    dspC = GPUMoveThing.getDemandSyncPoseCalls();
			double dsdN = GPUMoveThing.getDemandSyncDerivedNanos() / 1.0e9;
			int    dsdC = GPUMoveThing.getDemandSyncDerivedCalls();
			int    prc  = GPUMoveThing.getPlanRebuildCount();
			System.out.printf("[STATS] gpuMoveThing demandSyncPose=%.3fs(calls=%d) demandSyncDerived=%.3fs(calls=%d) planRebuild=%d%n",
				dspN, dspC, dsdN, dsdC, prc);
			GPUMoveThing.reportDerivedCheckpointSummary();
		}
		GPUMoveThing.reportMoveAB();
		if (Env.useGPU && GPUMyosinJoints.getCallCount() > 0) {
			int    calls = GPUMyosinJoints.getCallCount();
			double tot   = GPUMyosinJoints.getTotalNanos()  / 1.0e9;
			double pk    = GPUMyosinJoints.getPackNanos()   / 1.0e9;
			double ex    = GPUMyosinJoints.getExecNanos()   / 1.0e9;
			double un    = GPUMyosinJoints.getUnpackNanos() / 1.0e9;
			System.out.printf("[STATS] gpuMyosinJoints total=%.3fs calls=%d pack=%.3fs exec=%.3fs unpack=%.3fs%n",
				tot, calls, pk, ex, un);
		}
		// [STATS] glidingVelocity: mean per-filament longWindowSpeedXY at end of run.
		// Used by ensemble validation scripts to extract a single per-run velocity.
		if (GlidingAssayEvaluator.getInstance() != null) {
			double vSum = 0; int vCount = 0;
			for (java.util.Map.Entry<Integer, ?> e : GlidingAssayEvaluator.getInstance().filStatesEntrySet()) {
				double v = GlidingAssayEvaluator.getInstance().getLongWindowSpeedXY((Integer) e.getKey());
				vSum += v; vCount++;
			}
			if (vCount > 0) {
				System.out.printf("[STATS] glidingVelocity=%.4f%n", vSum / vCount);
			}
		}
		reportAllThreadSetTimes();
		
	}
	
	// F1 benchmark: translate terminal segments so their pinned endpoints return to anchors.
	// Called after moveThing() each step. Post-moveThing() position correction handles both
	// centroid translation and rotation (rotation pivots about centroid, not pin, so the
	// endpoint drifts; the correction below restores it exactly regardless of the source).
	private static void applyBenchmarkPins() {
		if (deflFil.firstSeg == null || deflFil.lastSeg == null) return;
		deflFil.firstSeg.incCoord(
			deflFil.anchor1.x - deflFil.firstSeg.getEnd1X(),
			deflFil.anchor1.y - deflFil.firstSeg.getEnd1Y(),
			deflFil.anchor1.z - deflFil.firstSeg.getEnd1Z());
		deflFil.firstSeg.initialize();
		deflFil.lastSeg.incCoord(
			deflFil.anchor2.x - deflFil.lastSeg.getEnd2X(),
			deflFil.anchor2.y - deflFil.lastSeg.getEnd2Y(),
			deflFil.anchor2.z - deflFil.lastSeg.getEnd2Z());
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
		double px = deflFil.midSeg.getCoordX() - deflFil.anchor1.x;
		double py = deflFil.midSeg.getCoordY() - deflFil.anchor1.y;
		double pz = deflFil.midSeg.getCoordZ() - deflFil.anchor1.z;
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
	// Called once per output frame inside synchronized(Env.safeO). Reads segment uVecAsPt3D() — read-only, safe.
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
				sum += Pt3D.Dot(lpSegs[i].uVecAsPt3D(), lpSegs[i + k].uVecAsPt3D());
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
	// BOA_BMDIAG_MAX_STEPS overrides the 5M step cap of -bmDiag for pre-port baseline runs.
	// Cached as a static so we don't parseInt on every step.
	private static int CACHED_BMDIAG_MAX_STEPS = -1;
	private static int bmDiagMaxSteps() {
		if (CACHED_BMDIAG_MAX_STEPS != -1) return CACHED_BMDIAG_MAX_STEPS;
		String v = System.getenv("BOA_BMDIAG_MAX_STEPS");
		int n = 5_000_000;
		if (v != null && !v.isEmpty()) {
			try { n = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) {}
		}
		CACHED_BMDIAG_MAX_STEPS = n;
		return n;
	}

	// Pre-port characterization helper: returns Lp_meas in µm (or NaN if not yet measurable).
	// Same weighted log-linear regression as buildLpJson's lpMeas computation, but headless
	// (no JSON, no LiveFrameServer). Used by -bmDiag periodic print to log Lp_meas alongside
	// the deflection ratio.
	private static double computeLpMeas() {
		if (lpFil == null || !lpFil.cMeanInitialized) return Double.NaN;
		int nLp = lpFil.nSegs;
		double segLen = lpFil.segLen;
		double sumW = 0, sumWS = 0, sumWLogC = 0, sumWS2 = 0, sumWSlogC = 0;
		int nFit = 0;
		for (int k = 1; k < nLp; k++) {
			double ck = lpFil.cMean[k];
			if (ck > 0.01) {
				double sk = k * segLen;
				double logC = Math.log(ck);
				double w = ck * ck;
				sumW += w; sumWS += w * sk; sumWLogC += w * logC;
				sumWS2 += w * sk * sk; sumWSlogC += w * sk * logC;
				nFit++;
			}
		}
		if (nFit < 2 || sumW <= 1e-30) return Double.NaN;
		double denom = sumWS2 - sumWS * sumWS / sumW;
		if (Math.abs(denom) <= 1e-30) return Double.NaN;
		double b = (sumWSlogC - sumWS * sumWLogC / sumW) / denom;
		if (b >= 0) return Double.NaN;
		return -1.0 / b;
	}

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
			s.setCoord(deflFil.initCoords[i].x, deflFil.initCoords[i].y, deflFil.initCoords[i].z);
			s.setUVec(1, 0, 0);
			s.setYVec(0, 1, 0);
			s.pushPoseToSoa();           // SoA bridge: caller mutated Pt3D directly; flush before initialize() reads SoA
			s.initialize();
			s.zeroForceSumSlot();
			s.zeroTorqueSumSlot();
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
				// iter2c: bTransGam/bRotGam buffers are FIRST_EXECUTION on the GPU plan.
				// Drag refresh invalidates them, forcing a plan rebuild + re-upload.
				if (Env.useGPU) { GPUMoveThing.invalidatePlan(); }
				if (Env.benchmarkFilament && deflFil.midSeg != null && deflFil.segs != null) {
					double spanM = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2) * 1e-6;
					double zetaPerp = deflFil.midSeg.bTransGam.y;
					deflFil.tauTheo = deflFil.segs.length * zetaPerp * (spanM*spanM*spanM)
						/ (Env.EI * (Math.PI*Math.PI*Math.PI*Math.PI));
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
			deflFil.anchor1.setVals(segs[0].getEnd1X(), segs[0].getEnd1Y(), segs[0].getEnd1Z());
			deflFil.anchor2.setVals(segs[n-1].getEnd2X(), segs[n-1].getEnd2Y(), segs[n-1].getEnd2Z());
			double spanM = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2) * 1e-6;
			double forceN = 48.0 * Env.EI * Env.benchmarkForceFrac.getValue() / (spanM * spanM);
			deflFil.transForce.setVals(0, -forceN, 0); // negative Y: downward in default camera view
			deflFil.analyticDefl = Env.benchmarkForceFrac.getValue() * spanM * 1e6; // µm
			deflFil.segs = segs;
			deflFil.initCoords = new Pt3D[n];
			for (int i = 0; i < n; i++) {
				deflFil.initCoords[i] = new Pt3D(segs[i].getCoordX(), segs[i].getCoordY(), segs[i].getCoordZ());
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
			deflFil.tauTheo = n * zetaPerp * (spanM*spanM*spanM) / (Env.EI * (Math.PI*Math.PI*Math.PI*Math.PI));
			deflFil.tauMeas = Double.NaN;
			deflFil.tauMeasFrozen = false;
			System.out.printf("[BENCH] τ_theo=%.3f s  ζ_perp_seg=%.3e N·s/m%n", deflFil.tauTheo, zetaPerp);

			// Round 3 diagnostic: print each segment's center coordAsPt3D(), length, and anchor flag
			for (int i = 0; i < n; i++) {
				boolean isAnchor = (segs[i] == deflFil.firstSeg || segs[i] == deflFil.lastSeg);
				System.err.printf("[BENCH:CHAIN] i=%d coordAsPt3D()=(%.4f,%.4f,%.4f) length=%.4f isAnchor=%b%n",
					i, segs[i].getCoordX(), segs[i].getCoordY(), segs[i].getCoordZ(), segs[i].length, isAnchor);
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

			// 2026-05-31: single-myosin thermal characterization mode (no filaments,
			// no other populations). Mutually exclusive with the gliding assay setup.
			if (Env.singleMyoDiag.isActive()) {
				MyosinFixed.setUpSingleMyosinDiag();
				return;
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
