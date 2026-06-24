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
	// Part C (benchmark-contractile-dense): host-side crosslink FORMATION drain
	// (device filFilCandidate kernel result -> host checkToLink/makeLink). The
	// kernel itself rides exec; this captures the host drain cost (cadence phase).
	static RunTimer crosslinkFormTimer = new RunTimer("CrosslinkFormation");

	// Part C windowed host-phase profile. Gated by StepProfiler.ENABLED
	// (BOA_STEP_PROFILE). Window = [BOA_PROFILE_WARMUP, end); per-phase ms/step =
	// (final - baseline-at-warmup) / windowSteps. Wall is bracketed exactly to the
	// loop via profWallLastNs (updated every step in updateCounters).
	static long    profWarmupSteps   = -2;   // -2 = unresolved; resolved lazily from env
	static boolean profBaselineTaken = false;
	static long    profWallBaselineNs = 0, profWallLastNs = 0, profWindowStartStep = 0;
	static long    pbExecN, pbPackN, pbJointPackN, pbSyncPoseN, pbSyncDerivedN;
	static long    pbMeshMs, pbXlinkMs, pbBiochemMs, pbMoveMs, pbFormMs, pbStepMs, pbGatherMs, pbBrownMs;
	static long    pbBiochemFire, pbXlinkFire, pbSyncCalls;
	// Part-2 "other"-bucket attribution (2026-06-12). nanoTime accumulators for
	// per-step host regions that had no named timer; baselines snapshot at the
	// window start so the report subtracts the warmup. The motor-collision /
	// resetCt / cleanup1 phases already have ms RunTimers — surfaced via their
	// own pb* ms baselines below.
	static long    pcRecomputeNs, pcOutputNs, pcCleanupTailNs, pcMembraneNs, pcSafepointNs, pcJointsNs;
	// A3 jointsCpu sub-attribution (membraneLinks / myoJoints1 / myoJoints2).
	static long    pcMembraneLinksNs, pcJoints1Ns, pcJoints2Ns;
	static long    pbMembraneLinksNs, pbJoints1Ns, pbJoints2Ns;
	static long    pbRecomputeNs, pbOutputNs, pbCleanupTailNs, pbMembraneNs, pbSafepointNs, pbJointsNs;
	static long    pbMotorColMs, pbResetMs, pbCleanup1Ms;
	static long    pbGcMs;   // total JVM GC collection time (ms) at window start
	// A1 — onStepStart sub-bracket baselines (GPU recompute decomposition).
	static long    pbClassifyN, pbClassifyCalls, pbDeltaSetN, pbCapacityN,
	               pbClassifyStructSteps, pbClassifyLenSteps;

	// Sum of collection time across all GC collectors (ms). Used to attribute the
	// share of the "other" residual that is GC pauses (not a phase bracket).
	static long totalGcCollectionMs() {
		long t = 0;
		for (java.lang.management.GarbageCollectorMXBean gc
		     : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
			long ct = gc.getCollectionTime();
			if (ct > 0) t += ct;
		}
		return t;
	}


	static RunTimer [] runTimers = {collisionMeshTimer,motorsAndFilsColTimer,brownianTimer,xLinkTimer,stepTimer,gatherTimer,moveTimer,biochemTimer,resetCtTimer,cleanupTimer1};

	
	// counters for doLoop()
	static boolean paintedThisStep;
	static double lastReportTime = System.currentTimeMillis();
	static int drawCounter = 0;
	static int toFileCounter = 0;
	static int remoteOutCounter = 0;
	static int collisionCkCounter = 0;
	static int crosslinkCkCounter = 0;   // crosslink-formation cadence counter (fires every Thing.crosslinkCheckInt)
	// Time-averaged active FilLink count (steady-state metric, 2026-06-12). The
	// instantaneous filLinkCt at run-end is a post-formation snapshot (the run
	// typically ends a step after a crosslink-fire step) that masks dissolution
	// between checks; the per-step mean is the true formation/dissolution balance.
	static long linkCtSum = 0;
	static long linkCtSamples = 0;
	static long linkCtSumSettled = 0;     // mean over the back half (after warm-up)
	static long linkCtSamplesSettled = 0;
	static int ckElasticityCounter = 0;
	static int ckPersistenceCounter = 0;
	static int applyBrownianForcesCounter = 0;
	static int drawingsMadeCounter = 0;
	static int jSonCt = (int)1e6;	// large number so it'll write at time zero
	static int jSonPlotCt = (int)1e6;	// ditto
	static int jSon2Ct = 0;  // start counting at zero so file writing starts at specified time vi Env.simJSon2StartCounter
	static int threeJSCounter = (int)1e6;	// large number so first frame writes at time zero
	static int ratchetReportCt = 0;	// gates periodic RatchetDiag.report() at frame cadence
	
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
	static final Pt3D tipFlexForce = new Pt3D();   // static tip-compliance diagnostic: force at the free tip

	// Hard plus-end / endpoint pins applied after integration each step (applyBenchmarkPins()).
	// Both the deflection benchmark (firstSeg.end1, lastSeg.end2) and the contractility assay
	// (each filament's outer plus-end terminal segment) register their pins here.
	static class Pin {
		final FilSegment seg;
		final int whichEnd;   // 1 -> end1, 2 -> end2
		final Pt3D anchor;    // fixed lab-frame point the chosen endpoint is snapped back to
		Pin(FilSegment seg, int whichEnd, Pt3D anchor) { this.seg = seg; this.whichEnd = whichEnd; this.anchor = anchor; }
	}
	static final java.util.List<Pin> pinRegistry = new java.util.ArrayList<>();

	// Minimal contractility assay state (null unless contractilityAssay is active).
	static class ContractAssay {
		FilSegment[] filA, filB;            // the two anti-parallel filament chains
		FilSegment anchorSegA, anchorSegB;  // outer (pinned) terminal segment of each
		Pt3D buildDirA = new Pt3D(), buildDirB = new Pt3D(); // unit vector pointing INWARD from each anchor
		Pt3D anchorPtA = new Pt3D(), anchorPtB = new Pt3D();  // pinned endpoint locations (box walls, inset)
		MyoMiniFilament mini;               // null in the no-motor control (and in the node assay)
		ProteinNode[] nodes;                // node load source (node contractility assay); null in the minifilament assay
		// Axial force at each anchor, projected onto the inward buildDir: positive = contractile.
		double tensionA_pN = 0, tensionB_pN = 0;
		Pt3D forceA = new Pt3D(), forceB = new Pt3D(); // raw lab-frame net force on each anchor segment (N)
		double[] jointF = new double[3];               // GPU: device chain force (jointForceSum) scratch

		// ── Running statistics for the live HUD (accumulated every step) ──
		// instBound: heads currently on actin. The two averages address the
		// user's "drifted to the wrong filament at first" observation: the
		// cumulative average is dragged down by the early transient, while the
		// EWMA tracks the current (plateau) regime.
		int    instBound = 0;                 // heads on actin this step
		long   statSamples = 0;               // step count contributing to cumulative means
		double sumBound = 0;                  // Σ instBound
		double sumTension = 0;                // Σ meanTension (= (|A|+|B|)/2 each step)
		double sumTensionA = 0, sumTensionB = 0;
		boolean ewmaInit = false;
		double ewmaBound = 0, ewmaTension = 0; // recent-window EWMA (α = STAT_EWMA_ALPHA)
		double peakTension = 0;               // max meanTension over the run
		int    peakBound = 0;                 // max instBound over the run
		int    firstBindStep = -1;            // step the first head engaged (-1 until then)
	}
	// EWMA smoothing for the HUD's "recent" averages. α=0.005 → ~200-step
	// window (~2 ms at dt=1e-5), short enough to follow the plateau, long
	// enough to smooth per-step binding noise.
	static final double STAT_EWMA_ALPHA = 0.005;
	static ContractAssay contract = null;

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
		// CLI -contractility flag: self-configure the minimal contractility assay
		// so it launches stand-alone like -bmManual (no param file required). Runs
		// only when the flag activated the assay (param-file activation happens
		// later in loadParamConfig, which then overrides any of these defaults).
		if (Env.contractilityAssay.isActive() || Env.nodeContractilityAssay.isActive()) {
			applyContractilityDefaults();
			if (Env.nodeContractilityAssay.isActive()) {
				applyNodeContractilityDefaults();
				// Route the node assay through the shared contractility plumbing
				// (per-step capture/accumulate, frame-output trigger, HUD/JSON,
				// reporter). makeInitialThings dispatches the NODE builder first
				// so makeContractilityAssay (the minifilament path) is never called.
				Env.contractilityAssay.setActive(true);
				Env.contractilityAssay.setValue(1.0);
			}
			Env.remote = true;
			Env.paused = false;                  // headless: no client to send resume
			Env.simOutsideBug.setActive(false);  // suppress Listeria bug + ActA
			if (Env.threeJSLivePort > 0) {
				Env.runTime.setValue(600);       // live: user drives termination via Kill
			}
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
		// BOA_CONCURRENT_FORCES=1 → restore the legacy concurrent dispatch of the
		// multi-pool force waves (xLink/myoJoints1/myoJoints2), re-exposing the
		// taForce[tid] cross-pool race. For A/B comparison and rollback only;
		// default is the serialized (correct) dispatch. See runForceWave().
		String concForcesEnv = System.getenv("BOA_CONCURRENT_FORCES");
		if (concForcesEnv != null && !concForcesEnv.isEmpty()
		    && !concForcesEnv.equals("0") && !concForcesEnv.equalsIgnoreCase("false")) {
			CONCURRENT_FORCES = true;
			System.err.println("[FORCES] LEGACY concurrent multi-pool dispatch (taForce race ACTIVE) via BOA_CONCURRENT_FORCES");
		}
		// BOA_MINIFIL_BROWNIAN_OFF=1 → suppress the minifilament BODY's own
		// thermal (Brownian) forces/torques in MyoMiniFilament.moveThing(). The
		// myosin rods/levers/motors keep their Brownian (GPU kernel); only the
		// rigid minifilament body the tails attach to stops flopping. Diagnostic
		// for isolating body-thermal-noise from the cohesion behaviour.
		String minifilBrownEnv = System.getenv("BOA_MINIFIL_BROWNIAN_OFF");
		if (minifilBrownEnv != null && !minifilBrownEnv.isEmpty()
		    && !minifilBrownEnv.equals("0") && !minifilBrownEnv.equalsIgnoreCase("false")) {
			Env.myoMiniFilBrownianMotionOff = true;
			System.err.println("[MINIFIL] body Brownian motion suppressed via BOA_MINIFIL_BROWNIAN_OFF");
		}
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
		// BOA_MINIFIL_COHESION_CPU=1 — keep minifilament cohesion (rod↔rod, lever, body↔rod)
		// on the CPU even on -gpu (the A/B control for the cohesion-onto-device port).
		String cohCpuEnv = System.getenv("BOA_MINIFIL_COHESION_CPU");
		if (cohCpuEnv != null && !cohCpuEnv.isEmpty()
		    && !cohCpuEnv.equals("0") && !cohCpuEnv.equalsIgnoreCase("false")) {
			GPUMoveThing.DIAG_COHESION_CPU = true;
			System.err.println("[COHESION_DIAG] DIAG_COHESION_CPU ON via env var (CPU cohesion runs; device kernel skipped)");
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

		// Node contractility assay activated via param file (rather than the -contractilityNode
		// flag, which is parsed before this and handled in the begin() block above): ensure the
		// shared contractility plumbing is on. A param-file config is self-contained, so the
		// applyContractility*Defaults() are intentionally NOT re-run here (the file supplies the
		// box/dt/turnover and node-myosin params itself), matching the minifilament assay.
		if (Env.nodeContractilityAssay.isActive()) {
			Env.contractilityAssay.setActive(true);
			Env.contractilityAssay.setValue(1.0);
		}

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
		recomputeActiveThreadSets();
		printActiveThreadSetPlan();

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
			if (args[i].equals("-contractility") || args[i].equals("-bmContractile")) {
				// Launch the minimal contractility assay as a first-class mode
				// (mirrors -bmManual). The flag activates the assay; begin()
				// then applies the validated assay defaults (box/dt/turnover-off)
				// + live-friendly setup. A -pf passed alongside still overrides
				// individual params (loaded after, in begin()).
				Env.contractilityAssay.setActive(true);
				Env.contractilityAssay.setValue(1.0);
				// Optional integer arg = dimers per minifilament END (the half-
				// bipolar head count engaging each filament), e.g.
				// `-bmContractile 4`. Read fresh at MyoMiniFilament construction,
				// so setting it here is honoured; a -pf numMyoDimersEachEndOfMiniFil
				// still overrides. Omit the arg to keep the default (8).
				if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
					int nd = Integer.parseInt(args[++i]);
					Env.numMyoDimersEachEndOfMiniFil.setValue(nd);
					Env.numMyoDimersEachEndOfMiniFil.setActive(true);
				}
			}
			if (args[i].equals("-contractilityNode") || args[i].equals("-bmContractileNode")) {
				// Node analog of -contractility: the load source is protein node(s)
				// carrying surface myosins instead of a bipolar minifilament. The flag
				// activates the node assay; begin() then applies the shared contractility
				// defaults + node-myosin defaults (applyNodeContractilityDefaults) and routes
				// it through the shared stats/JSON/HUD/output plumbing. A -pf overrides
				// individual params (loaded after, in begin()).
				Env.nodeContractilityAssay.setActive(true);
				Env.nodeContractilityAssay.setValue(1.0);
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
			// Part 2 (turnover residency): global biochem cadence aligns all FilSegment biochem
			// mutation onto the same step so the GPU per-step pose pull can be retired in favour
			// of a biochem-cadence + output-cadence pull. Default ON for the GPU path; the env
			// BOA_BIOCHEM_GLOBAL_CADENCE=1/0 forces it on/off (=1 lets the full-CPU oracle run the
			// SAME aligned cadence for a clean residency A/B; =0 restores the legacy per-step pull).
			GPUMoveThing.biochemGlobalCadence = Env.useGPU;
			String bgcEnv = System.getenv("BOA_BIOCHEM_GLOBAL_CADENCE");
			if (bgcEnv != null && !bgcEnv.isEmpty()) {
				GPUMoveThing.biochemGlobalCadence = !bgcEnv.equals("0") && !bgcEnv.equalsIgnoreCase("false");
			}
			if (GPUMoveThing.biochemGlobalCadence) {
				System.err.println("[TURNOVER] global biochem cadence ON (biochem phase-aligned; GPU pose pull at biochem+output cadence)");
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

		tSetActive = new boolean[tSets.length];
		java.util.Arrays.fill(tSetActive, true);
	}

	// Per-ThreadSet active flag. Recomputed each loop step in
	// recomputeActiveThreadSets() from the live subsystem counts so empty
	// subsystems' waves are skipped. The existing per-ThreadSet
	// divideAndConquer/regroup methods already early-return on count==0; this
	// just elides the dispatch entirely so cull is bit-exact wherever the
	// subsystem is non-empty.
	private static boolean[] tSetActive;

	// Recomputed once per loop step. ThreadSets whose count is currently 0 are
	// marked inactive; ThreadSets that always have phase work (Thing.step /
	// Thing.brownian / Mesh / MotorBindGrid3D fill / Myosin joints — read
	// population counts internally) stay active. Counts may grow during the
	// run via biochem; the per-step recompute catches that.
	private static void recomputeActiveThreadSets () {
		// Always-on (handle phase work from heterogeneous populations or have
		// internal count gates that cover all their phases).
		tSetActive[0]  = true;                                         // Thing.stepThreads
		tSetActive[1]  = true;                                         // Thing.brownianThreads
		tSetActive[2]  = true;                                         // Myosin.myoThreads
		tSetActive[8]  = true;                                         // Mesh.meshThreads (per-phase gated internally on filSegmentCt / nodeCt / motorCt)
		tSetActive[9]  = true;                                         // Mesh.ckMeshThreads (filament-filament collisions)
		tSetActive[10] = true;                                         // Mesh.ckMotsThreads
		tSetActive[16] = true;                                         // MotorBindGrid3D.FillThreads

		// Count-gated. When the count is 0 the divideAndConquer/regroup
		// switches early-return anyway; skipping dispatch saves the call.
		tSetActive[3]  = MyosinDimer.myoDimerCt > 0;
		tSetActive[4]  = ProteinNode.nodeCt > 0;
		tSetActive[5]  = MyoMiniFilament.myoMiniFilCt > 0;
		tSetActive[6]  = Env.numChamberFixedMyos.getIntValue() > 0;
		tSetActive[7]  = Env.numChamberFixedMyoDimers.getIntValue() > 0;
		tSetActive[11] = FilLink.filLinkCt > 0;
		tSetActive[12] = Arp23.arp23Ct > 0;
		tSetActive[13] = NodeLink.nodeLinkCt > 0;
		tSetActive[14] = ProteinNode.nodeCt > 0;  // StickyNode.MembraneNodeThreads gates on ProteinNode.nodeCt
		tSetActive[15] = ActA.actACt > 0;
	}

	private static void printActiveThreadSetPlan () {
		String ts = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
		System.out.printf("[%s] [phase-plan] inert-wave cull — ThreadSet activation snapshot:%n", ts);
		int onCt = 0, offCt = 0;
		for (int i = 0; i < tSets.length; i++) {
			System.out.printf("[%s]   %s  %s%n", ts,
				tSetActive[i] ? "ON " : "OFF",
				tSets[i].commandName);
			if (tSetActive[i]) onCt++; else offCt++;
		}
		System.out.printf("[%s] [phase-plan] %d active, %d inert (will skip dispatch)%n",
			ts, onCt, offCt);
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
	
	// Cross-pool taForce race fix. incForceSum accumulates into a per-thread row
	// taForce[tid], where tid is each worker's LOCAL index within its own
	// ThreadSet, and taForce has only allThreadCt (=16) rows — the design assumes
	// one pool writes forces at a time. But waves that release 2+ force-writing
	// pools concurrently (xLink: FilLink+Arp23+ActA; myoJoints1: Myosin+MyosinDimer;
	// myoJoints2: ProteinNode+MyoMiniFilament+ChamberMyo+ChamberMyoD) make worker-0
	// of each pool race on taForce[0]/dirtyCounts[0]/dirtyIndices[0], dropping
	// restoring forces. runForceWave() serializes the POOLS against each other for
	// such a wave (each pool still runs its 16 workers in parallel — only the
	// cross-pool overlap is removed). This is the default. Single-pool waves
	// (step, brownian, mesh, membrane, bind, collisions) are unaffected.
	// BOA_CONCURRENT_FORCES=1 restores the legacy concurrent dispatch for A/B and
	// rollback.
	static boolean CONCURRENT_FORCES = false;

	private static void runForceWave (int startWave, int stopWave) {
		if (CONCURRENT_FORCES) {
			startAllThreadSets(startWave);
			waitOnAllThreadSets(stopWave);
		} else {
			startAndWaitEachThreadSet(startWave, stopWave);
		}
	}

	// Dispatch each active ThreadSet for this wave and wait for it before starting
	// the next — serializes what startAllThreadSets/waitOnAllThreadSets would run
	// concurrently. Within-pool worker parallelism is preserved (spawn() still
	// fans out the pool's own 16 workers); only the overlap BETWEEN pools is
	// removed, so the per-thread taForce[tid] rows are never written by two pools
	// at once.
	private static void startAndWaitEachThreadSet (int startWave, int stopWave) {
		for (int i=0; i < tSets.length; i++) {
			if (!tSetActive[i]) continue;
			tSets[i].divideAndConquer(startWave);
			tSets[i].regroup(stopWave);
		}
	}

	private static void startAllThreadSets (int waveNum) {
		for (int i=0; i < tSets.length; i++) {
			if (!tSetActive[i]) continue;
			tSets[i].divideAndConquer(waveNum);
			//System.out.println(tSets[i].commandName + " calling wave # " + waveNum);
		}
	}

	private static void waitOnAllThreadSets(int waveNum) {
		for (int i=0; i < tSets.length; i++) {
			if (!tSetActive[i]) continue;
			tSets[i].regroup(waveNum);
		}
	}
	
	private static boolean nanScanReported = false;   // DIAG (BOA_NAN_BIRTH) one-shot per run
	public static void doLoop() {
		// timers
		double startTime;
		double collisionTime = 0;
		double myosinTime = 0;

		// Write the pristine initial condition (t=0) BEFORE any integration step, so the first frame is
		// the untouched IC (e.g. the perfectly flat membrane sheet) rather than the post-first-step state.
		if (Env.threeJSOutputDir != null || LiveFrameServer.isRunning()) {
			ThreeJSWriter.writeFrame();
		}

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

				// Part C: take the windowed-profile baseline once Env.counter (steps
				// completed) reaches the warmup boundary. Captures cumulative-so-far
				// for every phase accumulator; the window delta is final - baseline.
				if (StepProfiler.ENABLED) { maybeTakeProfileBaseline(); }

				// Inert-wave cull: refresh per-ThreadSet active flags from live
				// counts. Cheap (17 int compares); catches mid-run population
				// growth in biochem-active configs.
				// Part-2 attribution: bracket the per-step "recompute/setup"
				// region (recompute flags → setBiophys → accum-cap → force-zero
				// → fillSoa/onStepStart → cadence flag) lumped into "other".
				long _recoT0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				recomputeActiveThreadSets();
				// set biophysical values needed for this next time step
				FilSegment.setBiophysValues();
				// Per-thread force/torque accumulators need at least thingCt slots.
				// Grown lazily with 25% headroom; reallocates only when thingCt outpaces capacity.
				Thing.ensureAccumCapacity(Thing.thingCt);
				// Zero the canonical SoA force/torque slots for the active Things.
				// This replaces the per-Thing forceSum.zero()/torqueSum.zero() that
				// used to live in resetCounters — one memset over thingCt*3 floats.
				Thing.clearSoaForcesTorques(Thing.thingCt);
				// SoA sync: snapshot motor and filament positions for 3D grid (step 1a).
				// Phase 4.5 small-fix Step 2 — on the GPU path the resident bind
				// kernel reads coord/uVec/soaLengthArr directly via slot maps; no
				// MyoMotor.soaX / FilSegment.soaEnd1X consumer remains on the per-
				// step path. CPU path (no -gpu) still needs them for MotorBindGrid3D.
				long _fillSoaT0 = (Env.useGPU && GPUMotorBinding.isBindProfileEnabled())
				                  ? System.nanoTime() : 0L;
				Phase45Trace.snapshot("2_preFillSoa");
				if (!Env.useGPU) {
					MyoMotor.fillSoaArrays();
					FilSegment.fillSoaArrays();
				}
				Phase45Trace.snapshot("3_postFillSoa");
				if (Env.useGPU && GPUMotorBinding.isBindProfileEnabled()) {
					fillSoaArraysNanos += System.nanoTime() - _fillSoaT0;
					fillSoaArraysCalls++;
				}
				// iter2c: classify Things for the GPU moveThing kernel before the
				// Brownian phase, so calcRandomForces() can skip GPU-handled Things.
				// No-op on topology-stable steps after the first call.
				if (Env.useGPU) { GPUMoveThing.onStepStart(); }
				 // Crosslink-FORMATION cadence (2026-06-12): formation is a biochem-class
				 // stochastic event, fired every Thing.crosslinkCheckInt steps — not every
				 // collision step. Set the per-step flag HERE (before the CPU mesh walk below
				 // AND before the GPU move plan / drain) so all three formation sites agree.
				 // Increment-then-check, matching GPUMoveThing.advanceBiochemCadence's phase:
				 // when crosslinkCheckInt == biochemCheckInt (the default) the two fire on the
				 // SAME steps, so the fil-fil drain rides the move-phase biochem-cadence pose
				 // pull (refreshHostPoseForFilFil skips its redundant same-step transfer).
				 crosslinkCkCounter++;
				 GPUMoveThing.crosslinkFiresThisStep = (crosslinkCkCounter >= Thing.crosslinkCheckInt);
				 if (GPUMoveThing.crosslinkFiresThisStep) { crosslinkCkCounter = 0; GPUMoveThing.crosslinkFireCt++; }
				 if (StepProfiler.ENABLED) { pcRecomputeNs += System.nanoTime() - _recoT0; }
				 // Meshed Collisions
				if (collisionCkCounter >= Thing.collisionCheckInt | Env.simulationTime == 0) {
					 collisionMeshTimer.start();

					 // Fil–fil crosslink broad-phase gate (2026-06-12): on the
					 // -gpu non-membrane crosslink path the device filFilCandidate
					 // kernel (built into GPUMoveThing's chained graph) feeds the
					 // host checkToLink, so the host FILSEG_MESH fill + the fil-fil
					 // mesh walk (meshColl) are skipped. The host path is retained
					 // for CPU runs and for membrane configs (StickyNodes need
					 // FILSEG_MESH for membraneFilMeshCollisions). NODE_MESH fill is
					 // unaffected. Candidates are drained after moveThings() below.
					 if (GPUMoveThing.filFilBroadphaseActive) {
						 GPUMoveThing.filFilFillSkipCt++;
					 } else {
						 startAllThreadSets(Env.meshFilsStart);
						 waitOnAllThreadSets(Env.meshFilsStop);
					 }
					 startAllThreadSets(Env.meshNodesStart);
					 waitOnAllThreadSets(Env.meshNodesStop);
					 // meshMotors (MYOHEADS_MESH) fill removed 2026-06-12 — write-only, no consumer.

					 if (!GPUMoveThing.filFilBroadphaseActive) {
						 startAllThreadSets(Env.meshCollStart);
						 waitOnAllThreadSets(Env.meshCollStop);
					 }

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

				// Crosslinkers and Arp2/3 branches and ActAs. xLinkStart (==actAStart)
				// releases FilLink + Arp23 + ActA — 3 force-writing pools — so serialize
				// them against each other (taForce race fix; see runForceWave).
				xLinkTimer.start();
				FilSegment.zeroAllLinkCts();
				runForceWave(Env.xLinkStart, Env.xLinkStop);
				xLinkTimer.stopInc();

				// Part-2 attribution: bracket the CPU joint-force waves (membrane
				// links + myoJoints1 + myoJoints2) that sit unlabeled between xLink
				// and step. On -gpu the per-Myosin internal joints run as a device
				// kernel, but MyosinDimer / ProteinNode / MyoMiniFilament / Chamber
				// joint pools still dispatch on CPU here — ∝N with the minifilament
				// population and the dominant share of the residual.
				long _jointsT0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				// Membrane links
				startAllThreadSets(Env.membraneLinksStart);
				waitOnAllThreadSets(Env.membraneLinksStop);
				// A3 sub-attribution: split jointsCpu into membraneLinks / myoJoints1 /
				// myoJoints2 so the by-Thing-type decomposition can attribute the residual.
				long _joints1T0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				if (StepProfiler.ENABLED) { pcMembraneLinksNs += _joints1T0 - _jointsT0; }

				// actual myosin joints. On the GPU path, the per-Myosin
				// jointConstraints() kernel is the first task of the chained
				// TaskGraph in GPUMoveThing.moveThings() — it ADDS joint
				// forces/torques directly to the shared device-side forceSum/
				// torqueSum that the move kernel then reads. So nothing
				// dispatches here on the GPU path; the CPU Myosin.myoThreads
				// short-circuits when useGPU is set, and MyosinDimer
				// (cross-Myosin coupling) keeps its CPU dispatch in the
				// myoJoints1 wave.
				// myoJoints1: Myosin (internal joints) + MyosinDimer (rod coupling +
				// lever alignment) — 2 force-writing pools → serialize (taForce race fix).
				runForceWave(Env.myoJoints1Start, Env.myoJoints1Stop);
				long _joints2T0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				if (StepProfiler.ENABLED) { pcJoints1Ns += _joints2T0 - _joints1T0; }

				// connections to other things. myoJoints2: ProteinNode + MyoMiniFilament
				// + ChamberMyo + ChamberMyoD — 4 force-writing pools → serialize.
				runForceWave(Env.myoJoints2Start, Env.myoJoints2Stop);
				if (StepProfiler.ENABLED) {
					long _now = System.nanoTime();
					pcJoints2Ns += _now - _joints2T0;
					pcJointsNs  += _now - _jointsT0;
				}

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

				// Branch-constraint sub-cycling (r-RESPA): with soft forces now gathered into
				// soaForceSum, take N inner steps of dt/N on ONLY the stiff Arp2/3 constraint and
				// integrate ONLY the branch segments (excluded from the global move below). No-op
				// unless Env.arpSubcycleN > 1. CPU prototype; per-cluster GPU kernel is the port.
				if (Env.arpSubcycleN.getIntValue() > 1) { Arp23.subcycleAll(); }

				// Contractility assay: read the anchor reaction (= tension) from the gathered
				// force, before moveThing integrates and the pin snaps the endpoint back.
				if (Env.contractilityAssay.isActive()) { captureContractilityTension(); accumulateContractilityStats(); }

				// F1 benchmark: apply transverse force to midpoint segment before integration
				if (Env.benchmarkFilament && deflFil.midSeg != null && Env.benchmarkForceOn.getValue() != 0) {
					deflFil.midSeg.incForceSum(deflFil.transForce);
				}
				// Tip-flexibility STATIC mode: apply the fixed force at the free tip (last seg's end2).
				if (SingleFilDiag.STATIC && deflFil.lastSeg != null) {
					deflFil.lastSeg.incForceSum(tipFlexForce, deflFil.lastSeg.end2AsPt3D());
				}
				// Round 3 diagnostic: trace force application path
				if (Env.benchmarkFilament && deflFil.midSeg != null && benchStepCount < 10) {
					System.err.printf("[BENCH:STEP] step=%d forceSum=(%.4e,%.4e,%.4e) coordAsPt3D()=(%.4f,%.4f,%.4f) veloc.y=%.4e%n",
						benchStepCount,
						deflFil.midSeg.getForceSumX(), deflFil.midSeg.getForceSumY(), deflFil.midSeg.getForceSumZ(),
						deflFil.midSeg.getCoordX(), deflFil.midSeg.getCoordY(), deflFil.midSeg.getCoordZ(),
						deflFil.midSeg.veloc.y);
				}

				// Part 2 (turnover residency): set the global biochem-cadence flag ONCE per
				// step, before the move phase (so the GPU move-phase pose pull and the later
				// biochem phase read the same value). No-op (flag=true) unless global cadence
				// is active. See GPUMoveThing.advanceBiochemCadence / biochemGlobalCadence.
				GPUMoveThing.advanceBiochemCadence();
				// Membrane probe: apply the constant drive force + steric reaction (no-op unless enabled),
				// before the move phase integrates the probe and the pushed nodes.
				StickyNode.driveMembraneProbe();
				// Vesicle membrane: compute the volume-pressure force once (single-threaded) before the
				// multi-threaded move phase integrates it (the relaxation passes refresh it themselves).
				if (Env.membraneVesicle.getValue() > 0.5) StickyNode.computeVesiclePressure();
				// DTS membrane v2: accumulate bending + area + volume forces (Newtons) into the vertex
				// soaForceSum, single-threaded, before the move phase integrates them. No-op if no Membrane.
				if (!Membrane.theMembranes.isEmpty()) Membrane.computeAllForces();
				moveTimer.start();
				if (Env.useGPU) {
					// Iteration 2b: unified Thing.moveThing() kernel. The GPU path
					// packs eligible Things (MyoMotor/MyoRod/MyoLever/root FilSegment
					// in this first pass), runs the branchless integration kernel,
					// unpacks coordAsPt3D()/uVecAsPt3D()/yVecAsPt3D(), and runs initialize() on the affected
					// Things. The MyoMiniFilament body is now device-integrated too
					// (RULE_MINIFIL, Phase A 2026-06-11). Ineligible Things (Bug,
					// ProteinNode, branches, ActA-bound segments, etc.) fall back to
					// CPU moveThing() inside GPUMoveThing.moveThings(). Crucible/Chamber/
					// AnchorNode have empty moveThing overrides and the fallback
					// dispatch is a no-op for them.
					GPUMoveThing.moveThings();
					// Step 3 (2026-06-07) — single-graph mode: the chained
					// graph just ran its bind subgraph (segBbox/gridAssemble/
					// bind) and transferred boundSegId+arcOnFilDev back to
					// host. Drain those into ontoFilament() so the next step's
					// packMotorBinding sees the new bindings (a 1-step lag vs
					// the separate-plan path, where bind dispatched at
					// detectBindings()-time). No-op in the legacy two-plan
					// path (detectBindings already drained).
					if (GPUMoveThing.SINGLE_GRAPH) {
						GPUMotorBinding.drainBoundResults();
						// Fil–fil broad-phase: the chained graph just emitted this
						// step's proximity candidates (from the same pre-integration
						// pose the bind kernel used). Run the host checkToLink fine
						// check → makeLink on each. 1-step lag vs the CPU meshColl
						// wave (which formed links pre-step), matching the bind drain.
						if (GPUMoveThing.filFilBroadphaseActive && GPUMoveThing.crosslinkFiresThisStep) {
							// Copy-out residency (2026-06-12): the candidate buffers are now
							// UNDER_DEMAND (the ~1 GB ffCandPartner copy-out no longer rides
							// every execute). Pull them on this fire step before any host
							// consumer (parity harness + drain) reads them.
							GPUMoveThing.demandSyncFilFilCandidates();
							// Candidate-completeness harness (validation #2): the kernel just
							// emitted this fire-step's candidates; compare vs brute-force before
							// the drain consumes them. No-op unless BOA_FILFIL_PARITY==this step.
							if (Env.counter == GPUMoveThing.FILFIL_PARITY_STEP) {
								GPUMoveThing.filFilCandidateParityCheck();
							}
							crosslinkFormTimer.start();
							GPUMotorBinding.drainFilFilCandidates();
							crosslinkFormTimer.stopInc();
						}
					}
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

				// Restore pinned endpoints after integration (deflection benchmark and/or
				// contractility assay register into pinRegistry).
				if (!pinRegistry.isEmpty()) { applyBenchmarkPins(); }
				// Round 3 diagnostic: midpoint coordAsPt3D() after integration + pin correction
				if (Env.benchmarkFilament && deflFil.midSeg != null && benchStepCount < 10) {
					System.err.printf("[BENCH:POST] step=%d getCoordY()=%.6e veloc.y=%.4e%n",
						benchStepCount, deflFil.midSeg.getCoordY(), deflFil.midSeg.veloc.y);
				}
				// Per-node Arp2/3 field: one explicit diffusion+exchange step (single-threaded, cheap),
				// before biochem reads/consumes the local pools. No-op unless arpLocalField is on.
				StickyNode.diffuseArpField();
				biochemTimer.start();
				startAllThreadSets(Env.biochemStart);
				waitOnAllThreadSets(Env.biochemStop);
				biochemTimer.stopInc();

				resetCtTimer.start();
				startAllThreadSets(Env.resetCtStart);
				waitOnAllThreadSets(Env.resetCtStop);
				resetCtTimer.stopInc();

				// Membrane relaxation loop... special passes to allow forces to propogate/move nodes, especially laterally at collisions
				// Part-2 attribution: bracket the membrane relaxation block (no-op
				// in non-membrane fixtures but its threadset fan-out is unlabeled).
				long _membT0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				if (Env.membraneRelaxGpuShaped.getValue() > 0.5) {
					// GPU-shaped self-contained Jacobi relaxation (kernel-shaped; see SUBCYCLING_GPU.md)
					NodeLink.subcycleRelaxAll();
				} else {
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
				}
				// Tier-2: grow membrane area by inserting nodes where links over-stretch (single-threaded,
				// after relaxation, before cleanup). Default-off; keeps the cortex covered over a bulge.
				NodeLink.insertNodesForArea();
				if (StepProfiler.ENABLED) { pcMembraneNs += System.nanoTime() - _membT0; }

				// Part-2 attribution: bracket the post-step / safe-point region
				// (updateCounters + mem-trace + per-step diagnostics + inspect/param
				// drains + benchmark tuners) lumped into "other". Mostly no-op in the
				// dense fixture; measures the residual that is NOT GC / fan-out.
				long _safeT0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				updateCounters();

				// DIAG (BOA_NAN_BIRTH): once per run, find the first node whose coord has gone non-finite and
				// report its class/id/createdAtStep. createdAtStep == this step => born NaN (a degenerate
				// placement at construction); createdAtStep earlier => went NaN later (force/integration). This
				// disambiguates the construction guard in Thing(). Removed once the source is fixed.
				if (System.getenv("BOA_NAN_BIRTH") != null && !nanScanReported) {
					for (int i = 0; i < boxOfActin.ProteinNode.nodeCt; i++) {
						boxOfActin.ProteinNode pn = boxOfActin.ProteinNode.theNodes[i];
						if (pn == null || pn.removeMe) continue;
						if (Double.isFinite(pn.getCoordX()) && Double.isFinite(pn.getCoordY()) && Double.isFinite(pn.getCoordZ())) continue;
						nanScanReported = true;
						Thing.talkln(String.format("[NAN-SCAN] step %d  class=%s  id=%d  createdAtStep=%d  coord=[%g,%g,%g]",
								Env.counter, pn.getClass().getName(), pn.thingInstanceId, pn.createdAtStep,
								pn.getCoordX(), pn.getCoordY(), pn.getCoordZ()));
						break;
					}
				}

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
				// A2 cell-crossing diagnostic — no-op unless BOA_CROSS_PROBE set.
				// Fresh host pose (CPU run) -> measures the per-step grid
				// cell-crossing rate that drives the gridScatter rebuild.
				CrossProbe.sample();

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

				if (StepProfiler.ENABLED) { pcSafepointNs += System.nanoTime() - _safeT0; }

				// output to screen and/or files
				// Part-2 attribution: bracket the output region (logAndDraw /
				// remoteLog + the GPU output-render restore) lumped into "other".
				long _outT0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				if (!Env.remote) { logAndDraw(); } else { remoteLog(); }

				// Close the output-render episode: restore the physics-owned host
				// derived arrays / Pt3D mirrors that the frame writers' recompute
				// mutated, so emitting a frame leaves simulation state unchanged.
				// No-op when no frame was written this step (snapshot inactive).
				if (Env.useGPU) { GPUMoveThing.endOutputRender(); }
				if (StepProfiler.ENABLED) { pcOutputNs += System.nanoTime() - _outT0; }

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

				// Part-2 attribution: bracket the cleanup tail (ActA cleanup +
				// fill-node + spawn + equilibrate) lumped into "other".
				long _ctailT0 = StepProfiler.ENABLED ? System.nanoTime() : 0L;
				ActA.cleanUpActAs();

				if (Env.simulationTime > 0 && StickyNode.sphericalGeometry) { FillNode.addFillNodeToCell(); }   // fill cell as appropriate


				// create new Things
				if (Env.kRdmNuc.isActive()) { FilSegment.spawnRdmFilaments(); }
				if (Env.kNodeNuc.isActive()) { ProteinNode.spawnNodeFilaments(); }

				// equilibrateNodeNumber() maintains a frozen protein-node count by adding random nodes via the
				// legacy Listeria-bug cortex geometry (sqrt(bugRadius^2 - z^2)). For a DTS membrane the "nodes"
				// ARE the membrane vertices: their count legitimately changes on edge split/collapse, and their
				// z-range (~+/-membrane radius) far exceeds bugRadius -- so the freeze is wrong AND the re-add
				// produces NaN-positioned nodes (sqrt of a negative). A bilayer collapse dropping nodeCt by one
				// was exactly what spawned the NaN node that froze the viewer. Skip it when a DTS membrane exists.
				if (Membrane.theMembranes.isEmpty()) ProteinNode.equilibrateNodeNumber();
				MyoMiniFilament.equilibrateMyoMiniNumber();
				if (StepProfiler.ENABLED) { pcCleanupTailNs += System.nanoTime() - _ctailT0; }
			}
		}
		System.out.println("collisionTime = " + collisionTime);
		System.out.println("myosinTime = " + myosinTime);
		// 2026-05-31 conformation diagnostic: no-op when disabled.
		JointDiag.dump();
		SingleMyoDiag.dump();
		// step() per-force profile — no-op when BOA_STEP_PROFILE unset.
		StepProfiler.report();
		CrossProbe.report();
		System.out.printf("[STATS] bindEvents=%d%n", MyoMotor.totalBindEvents);
		// Fil–fil crosslink: total links formed (the parity metric, both paths) +
		// the device broad-phase activity / FILSEG_MESH-skip confirmation.
		System.out.printf("[STATS] filLinks=%d (active=%d inactive=%d)%n",
			FilLink.filLinkCt, FilLink.filLinkCt, FilLink.filLinkCt_inactive);
		// Steady-state metric: time-averaged active link population (full run + back
		// half). The back-half mean is the formation/dissolution balance, insensitive
		// to the post-formation-snapshot phase of the instantaneous final count.
		System.out.printf("[STATS] linkCtMean=%.2f (samples=%d) linkCtMeanSettled=%.2f (samples=%d) finalActive=%d%n",
			linkCtSamples > 0 ? (double) linkCtSum / linkCtSamples : 0.0, linkCtSamples,
			linkCtSamplesSettled > 0 ? (double) linkCtSumSettled / linkCtSamplesSettled : 0.0, linkCtSamplesSettled,
			FilLink.filLinkCt);
		System.out.printf("[STATS] filFilBroadphase active=%b filsegMeshFillSkipped=%d candPairs=%d candMaxPerSeg=%d overflowSegs=%d drainCalls=%d%n",
			GPUMoveThing.filFilBroadphaseActive, GPUMoveThing.filFilFillSkipCt,
			GPUMotorBinding.filFilCandPairs, GPUMotorBinding.filFilCandMaxSeg,
			GPUMotorBinding.filFilOverflowCt, GPUMotorBinding.filFilDrainCalls);
		// F-actin density readout (benchmark-contractile-dense). Sum of monomers in
		// all live filament segments * the per-monomer uM conversion = polymerized
		// actin concentration; reports alongside the free hydrolyzable pool.
		{
			long monoSum = 0; int segs = 0; double lenSum = 0;
			for (int i = 0; i < FilSegment.filSegmentCt; i++) {
				FilSegment fs = FilSegment.theFilSegments[i];
				if (fs == null || fs.removeMe) continue;
				monoSum += fs.monomerCt; segs++; lenSum += fs.length;
			}
			double fActinUM = monoSum * Crucible.microMolarChangePerMonomer;
			System.out.printf("[STATS] fActin segs=%d monomers=%d fActinUM=%.2f meanSegLenUM=%.3f freePoolUM=%.2f%n",
				segs, monoSum, fActinUM, segs > 0 ? lenSum / segs : 0.0, Env.actinConc.getValue());
		}
		// Memory ceiling readout (Part D, benchmark-contractile-dense): JVM used/max
		// heap + (GPU) device-buffer slotCap/estimate. Peak host RSS comes from an
		// external /usr/bin/time -v wrapper; true device VRAM from NVML (nvidia-smi).
		{
			Runtime rt = Runtime.getRuntime();
			long usedHeap = rt.totalMemory() - rt.freeMemory();
			long maxHeap  = rt.maxMemory();
			if (Env.useGPU) {
				System.out.printf("[STATS] mem usedHeapMB=%.1f maxHeapMB=%.1f slotCap=%d slotCount=%d myoCap=%d devBufEstMB=%.1f%n",
					usedHeap / 1.0e6, maxHeap / 1.0e6, GPUMoveThing.getSlotCap(), GPUMoveThing.getSlotCount(),
					GPUMoveThing.getMyoCap(), GPUMoveThing.getDeviceBufBytesEstimate() / 1.0e6);
			} else {
				System.out.printf("[STATS] mem usedHeapMB=%.1f maxHeapMB=%.1f%n", usedHeap / 1.0e6, maxHeap / 1.0e6);
			}
		}
		// Filament-network percolation / spanning (benchmark-contractile-dense). Read-only end-of-run probe.
		PercolationProbe.report();
		// Windowed host-phase decomposition report (Part C). No-op unless BOA_STEP_PROFILE set.
		reportStepPhaseProfile();
		// A3 residency diagnostic (2026-06-12): cpuFallback type histogram (the only
		// Things still running CPU brownian/step force work on -gpu) + dimer/minifil
		// cohesion device-vs-CPU dispatch counts (device = gated no-op, CPU = real work).
		System.out.printf("[A3] cpuFallback histogram (last classify): thingCt=%d cpuFb total=%d { FilSeg=%d MyoMini=%d ProteinNode=%d Chamber=%d other=%d }%n",
			GPUMoveThing.cpuFbThingCt, GPUMoveThing.getCpuFallbackCt(),
			GPUMoveThing.cpuFbFilSeg, GPUMoveThing.cpuFbMyoMini, GPUMoveThing.cpuFbProteinNode,
			GPUMoveThing.cpuFbChamber, GPUMoveThing.cpuFbOther);
		System.out.printf("[A3] dimer cohesion dispatch: device(noop)=%d cpu(work)=%d  | body↔rod constrain: device(noop)=%d cpu(work)=%d  | updateMyosins(host-bookkeeping)=%d%n",
			MyosinDimer.DIAG_COHESION_DEVICE_CT, MyosinDimer.DIAG_COHESION_CPU_CT,
			MyoMiniFilament.DIAG_BODYROD_DEVICE_CT, MyoMiniFilament.DIAG_BODYROD_CPU_CT,
			MyoMiniFilament.DIAG_UPDATEMYOSINS_CT);
		// Node-path Stage 2 force-exactly-once: the CPU node-surface tether must NOT
		// run for device-classified nodes (reads 0 on the GPU node path).
		System.out.printf("[NODE] cpuNodeTetherApplyCt=%d (must be 0 on GPU node path)%n",
			GPUMoveThing.cpuNodeTetherApplyCt);
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
			long pkDesync = GPUMoveThing.getPackRuleDesyncCount();
			System.out.printf("[STATS] gpuMoveThing demandSyncPose=%.3fs(calls=%d) demandSyncDerived=%.3fs(calls=%d) planRebuild=%d packRuleDesync=%d%n",
				dspN, dspC, dsdN, dsdC, prc, pkDesync);
			// Cadence measurement (2026-06-12): realized demandSyncPose calls and
			// biochem fires over the run vs total steps — settles whether the pose
			// pull fires every step (biochem-active path) or at output cadence.
			System.out.printf("[STATS] cadence steps=%d demandSyncPoseCalls=%d biochemFireCt=%d crosslinkFireCt=%d crosslinkCheckInt=%d (pull/step=%.3f biochem/step=%.3f xlink/step=%.3f)%n",
				Env.counter, dspC, GPUMoveThing.biochemFireCt, GPUMoveThing.crosslinkFireCt, Thing.crosslinkCheckInt,
				(double) dspC / Math.max(1, Env.counter),
				(double) GPUMoveThing.biochemFireCt / Math.max(1, Env.counter),
				(double) GPUMoveThing.crosslinkFireCt / Math.max(1, Env.counter));
			// Step 2 — per-step pose-delta scatter stats. avg=mean entries per
			// gathered step (excludes the freshPlan steps that snapshot only);
			// max=largest single delta; overflow=times the cap was exceeded
			// and a plan rebuild fell back. Resident-only ticks counted
			// separately (FIRST_EXECUTION carried the pose, no delta packed).
			long pdSum   = GPUMoveThing.getPoseDeltaCountSum();
			long pdMax   = GPUMoveThing.getPoseDeltaCountMax();
			long pdOver  = GPUMoveThing.getPoseDeltaOverflowCount();
			long pdFresh = GPUMoveThing.getPoseDeltaCallsResident();
			long pdCalls = Math.max(1, dspC - pdFresh);
			double pdAvg = (double) pdSum / (double) pdCalls;
			System.out.printf("[STATS] gpuMoveThing poseDelta avg=%.2f max=%d sum=%d fresh=%d overflow=%d cap=%d%n",
				pdAvg, pdMax, pdSum, pdFresh, pdOver, GPUMoveThing.POSE_DELTA_CAP);
			// Phase-A churn stats: true (pre-clamp) per-step dirty demand, split
			// by source (slot-change vs explicit pendingDirty marks). slotChangeMax
			// is the headline number for the cap-sizing decision.
			long tdc        = GPUMoveThing.getTrueDirtyCalls();
			long tdscSum    = GPUMoveThing.getTrueDirtySlotChangeSum();
			long tdscMax    = GPUMoveThing.getTrueDirtySlotChangeMax();
			long tdpSum     = GPUMoveThing.getTrueDirtyPendingSum();
			long tdpMax     = GPUMoveThing.getTrueDirtyPendingMax();
			long tdTotalMax = GPUMoveThing.getTrueDirtyTotalUpperMax();
			double tdscAvg  = tdc > 0 ? (double) tdscSum / (double) tdc : 0.0;
			double tdpAvg   = tdc > 0 ? (double) tdpSum  / (double) tdc : 0.0;
			System.out.printf("[STATS] gpuMoveThing trueDirty calls=%d slotChange avg=%.2f max=%d pending avg=%.2f max=%d totalUpperMax=%d slotCount=%d slotCap=%d%n",
				tdc, tdscAvg, tdscMax, tdpAvg, tdpMax, tdTotalMax,
				GPUMoveThing.getSlotCount(), GPUMoveThing.getSlotCap());
			GPUMoveThing.reportDerivedCheckpointSummary();
			if (GPUMoveThing.DELTASET_VERIFY) {
				System.out.printf("[STATS] A1 deltaSetVerify failSteps=%d missedSlotChanges=%d  (0/0 = skip invariant holds)%n",
					GPUMoveThing.deltaSetVerifyFailSteps, GPUMoveThing.deltaSetVerifyMissed);
			}
			System.out.printf("[STATS] A1 classify runs=%d (structural=%d lengthOnly=%d) classifyMs=%.1f poseAuditMs=%.1f fullRecompute=%b%n",
				GPUMoveThing.classifyCalls, GPUMoveThing.classifyStructuralSteps, GPUMoveThing.classifyLengthOnlySteps,
				GPUMoveThing.classifyNanos / 1.0e6, GPUMoveThing.deltaSetNanos / 1.0e6, GPUMoveThing.FULL_RECOMPUTE);
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
		for (Pin p : pinRegistry) {
			if (p.seg == null || p.seg.removeMe) continue;
			if (p.whichEnd == 1) {
				p.seg.incCoord(
					p.anchor.x - p.seg.getEnd1X(),
					p.anchor.y - p.seg.getEnd1Y(),
					p.anchor.z - p.seg.getEnd1Z());
			} else {
				p.seg.incCoord(
					p.anchor.x - p.seg.getEnd2X(),
					p.anchor.y - p.seg.getEnd2Y(),
					p.anchor.z - p.seg.getEnd2Z());
			}
			p.seg.initialize();
			// GPU residency: the snap-back is an in-place incCoord on the same
			// Thing/slot, which buildDeltaSet()'s slot-change scan does NOT catch
			// (same as biochem poly/depoly — see FilSegment.biochemStep). Without
			// an explicit markPoseDirty the device-resident pose never receives the
			// pin correction, so the anchor drifts and the assay is no longer
			// isometric (the plus-ends pull inward and the tension reads far below
			// the held-anchor value). Mark it so the scatter kernel lands the pinned
			// pose ahead of the next move integration. No-op on the CPU path.
			if (Env.useGPU) {
				GPUMoveThing.markPoseDirty(p.seg);
			}
		}
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

	// Part C — resolve the warmup boundary and snapshot all phase accumulators
	// the first time Env.counter (steps completed) reaches it.
	static void maybeTakeProfileBaseline() {
		if (profBaselineTaken) return;
		if (profWarmupSteps == -2) {
			String w = System.getenv("BOA_PROFILE_WARMUP");
			profWarmupSteps = (w != null && !w.isEmpty()) ? Long.parseLong(w.trim()) : 0;
		}
		if (Env.counter < profWarmupSteps) return;
		profWindowStartStep = Env.counter;
		profWallBaselineNs  = System.nanoTime();
		profWallLastNs      = profWallBaselineNs;
		pbExecN        = GPUMoveThing.getExecNanos();
		pbPackN        = GPUMoveThing.getPackNanos();
		pbJointPackN   = GPUMoveThing.getJointPackNanos();
		pbSyncPoseN    = GPUMoveThing.getDemandSyncPoseNanos();
		pbSyncDerivedN = GPUMoveThing.getDemandSyncDerivedNanos();
		pbMeshMs    = collisionMeshTimer.time;
		pbXlinkMs   = xLinkTimer.time;
		pbBiochemMs = biochemTimer.time;
		pbMoveMs    = moveTimer.time;
		pbFormMs    = crosslinkFormTimer.time;
		pbStepMs    = stepTimer.time;
		pbGatherMs  = gatherTimer.time;
		pbBrownMs   = brownianTimer.time;
		// Part-2 "other"-bucket attribution baselines.
		pbMotorColMs    = motorsAndFilsColTimer.time;
		pbResetMs       = resetCtTimer.time;
		pbCleanup1Ms    = cleanupTimer1.time;
		pbRecomputeNs   = pcRecomputeNs;
		pbOutputNs      = pcOutputNs;
		pbCleanupTailNs = pcCleanupTailNs;
		pbMembraneNs    = pcMembraneNs;
		pbSafepointNs   = pcSafepointNs;
		pbJointsNs      = pcJointsNs;
		pbMembraneLinksNs = pcMembraneLinksNs;
		pbJoints1Ns       = pcJoints1Ns;
		pbJoints2Ns       = pcJoints2Ns;
		pbGcMs          = totalGcCollectionMs();
		pbClassifyN          = GPUMoveThing.classifyNanos;
		pbClassifyCalls      = GPUMoveThing.classifyCalls;
		pbDeltaSetN          = GPUMoveThing.deltaSetNanos;
		pbCapacityN          = GPUMoveThing.capacityNanos;
		pbClassifyStructSteps = GPUMoveThing.classifyStructuralSteps;
		pbClassifyLenSteps    = GPUMoveThing.classifyLengthOnlySteps;
		pbBiochemFire = GPUMoveThing.biochemFireCt;
		pbXlinkFire   = GPUMoveThing.crosslinkFireCt;
		pbSyncCalls   = GPUMoveThing.getDemandSyncPoseCalls();
		profBaselineTaken = true;
	}

	// Part C — windowed host-phase decomposition. No-op unless BOA_STEP_PROFILE set.
	// Prints ms/step per phase split into every-step vs biochem-cadence groups,
	// plus total and host (= total - exec).
	static void reportStepPhaseProfile() {
		if (!StepProfiler.ENABLED) return;
		if (!profBaselineTaken) {
			System.out.println("[STEP_PROFILE] no window captured (run did not reach warmup boundary)");
			return;
		}
		long steps = Math.max(1, Env.counter - profWindowStartStep);
		double wallMs = (profWallLastNs - profWallBaselineNs) / 1.0e6;
		double M = steps;
		// Every-step phases (ms over window)
		double execMs  = (GPUMoveThing.getExecNanos()            - pbExecN)        / 1.0e6;
		double packMs  = (GPUMoveThing.getPackNanos()            - pbPackN)        / 1.0e6
		               + (GPUMoveThing.getJointPackNanos()       - pbJointPackN)   / 1.0e6;
		double xforceMs= (xLinkTimer.time          - pbXlinkMs);
		double meshMs  = (collisionMeshTimer.time  - pbMeshMs);
		double moveMs  = (moveTimer.time           - pbMoveMs);     // GPU: wraps exec+drains; CPU: cpuIntegrate
		double stepMs  = (stepTimer.time           - pbStepMs);
		double gathMs  = (gatherTimer.time         - pbGatherMs);
		double brownMs = (brownianTimer.time       - pbBrownMs);
		// Cadence phases
		double formMs    = (crosslinkFormTimer.time - pbFormMs);
		double biochemMs = (biochemTimer.time       - pbBiochemMs);
		double syncMs    = (GPUMoveThing.getDemandSyncPoseNanos()    - pbSyncPoseN)    / 1.0e6
		                 + (GPUMoveThing.getDemandSyncDerivedNanos() - pbSyncDerivedN) / 1.0e6;
		long biochemFires = GPUMoveThing.biochemFireCt    - pbBiochemFire;
		long xlinkFires   = GPUMoveThing.crosslinkFireCt  - pbXlinkFire;
		long syncCalls    = GPUMoveThing.getDemandSyncPoseCalls() - pbSyncCalls;

		boolean gpu = Env.useGPU;
		double totalPerStep = wallMs / M;
		double hostPerStep  = totalPerStep - (gpu ? execMs / M : 0.0);

		System.out.println();
		System.out.printf("*** [STEP_PROFILE] window=[%d,%d) steps=%d path=%s wall=%.1fms (%.4f ms/step) ***%n",
			profWindowStartStep, Env.counter, steps, gpu ? "GPU" : "CPU", wallMs, totalPerStep);
		System.out.println("  --- every-step phases (ms/step) ---");
		System.out.printf("    %-18s %10.5f%s%n", "exec",          execMs / M, gpu ? "" : "  (n/a CPU)");
		System.out.printf("    %-18s %10.5f%s%n", "pack",          packMs / M, gpu ? "" : "  (n/a CPU)");
		System.out.printf("    %-18s %10.5f%n",   "crosslinkForce", xforceMs / M);
		System.out.printf("    %-18s %10.5f%n",   "meshFill",       meshMs / M);
		System.out.printf("    %-18s %10.5f%s%n", "cpuIntegrate",   moveMs / M, gpu ? "  (GPU: move-wrap)" : "");
		System.out.printf("    %-18s %10.5f%n",   "  step",         stepMs / M);
		System.out.printf("    %-18s %10.5f%n",   "  gatherForces",  gathMs / M);
		System.out.printf("    %-18s %10.5f%n",   "  brownian",      brownMs / M);
		System.out.println("  --- biochem-cadence phases (amortized ms/step | per-fire ms | fires) ---");
		System.out.printf("    %-18s %10.5f | %8.4f | %d%n", "crosslinkFormation", formMs / M,    xlinkFires   > 0 ? formMs    / xlinkFires   : 0.0, xlinkFires);
		System.out.printf("    %-18s %10.5f | %8.4f | %d%n", "biochem",            biochemMs / M, biochemFires > 0 ? biochemMs / biochemFires : 0.0, biochemFires);
		System.out.printf("    %-18s %10.5f | %8.4f | %d%n", "sync",               syncMs / M,    syncCalls    > 0 ? syncMs    / syncCalls    : 0.0, syncCalls);
		// Part-2 "other"-bucket decomposition (2026-06-12). Named sub-phases that
		// were previously unlabeled. motorFilCol/resetCt/cleanup come from
		// existing ms RunTimers; recompute/output/cleanupTail from nanoTime
		// brackets. moveDrains (GPU only) = move-wrap wall minus the parts already
		// labeled that live inside it: device exec, the OOP->FloatArray pack, and
		// crosslinkFormation. pack is gathered inside moveThings() so it is part of
		// moveMs and MUST be subtracted or it double-counts (it is labeled
		// separately). What remains is the host drains (drainBoundResults, fil-fil
		// candidate demand-sync + drain, frame poison) inside moveTimer but outside
		// the device execute.
		double motorColMs  = (motorsAndFilsColTimer.time - pbMotorColMs);
		double resetMs     = (resetCtTimer.time          - pbResetMs);
		double cleanup1Ms  = (cleanupTimer1.time         - pbCleanup1Ms);
		double recompMs    = (pcRecomputeNs   - pbRecomputeNs)   / 1.0e6;
		double outputMs    = (pcOutputNs      - pbOutputNs)      / 1.0e6;
		double cleanTailMs = (pcCleanupTailNs - pbCleanupTailNs) / 1.0e6;
		double membraneMs  = (pcMembraneNs    - pbMembraneNs)    / 1.0e6;
		double safepointMs = (pcSafepointNs   - pbSafepointNs)   / 1.0e6;
		double jointsCpuMs = (pcJointsNs      - pbJointsNs)      / 1.0e6;
		double membLinksMs = (pcMembraneLinksNs - pbMembraneLinksNs) / 1.0e6;
		double joints1Ms   = (pcJoints1Ns       - pbJoints1Ns)       / 1.0e6;
		double joints2Ms   = (pcJoints2Ns       - pbJoints2Ns)       / 1.0e6;
		double moveDrainMs = gpu ? Math.max(0.0, moveMs - execMs - packMs - formMs) : 0.0;
		System.out.println("  --- 'other' decomposition (ms/step) ---");
		System.out.printf("    %-18s %10.5f%n", "jointsCpu",     jointsCpuMs / M);
		System.out.printf("    %-18s %10.5f%n", "  membraneLinks", membLinksMs / M);
		System.out.printf("    %-18s %10.5f%n", "  myoJoints1",    joints1Ms / M);
		System.out.printf("    %-18s %10.5f%n", "  myoJoints2",    joints2Ms / M);
		System.out.printf("    %-18s %10.5f%n", "motorFilCol",  motorColMs  / M);
		System.out.printf("    %-18s %10.5f%n", "recompute",     recompMs    / M);
		if (gpu) {
			double classMs   = (GPUMoveThing.classifyNanos - pbClassifyN) / 1.0e6;
			double deltaMs    = (GPUMoveThing.deltaSetNanos - pbDeltaSetN) / 1.0e6;
			double capMs      = (GPUMoveThing.capacityNanos - pbCapacityN) / 1.0e6;
			long   classRuns  = GPUMoveThing.classifyCalls - pbClassifyCalls;
			long   structRuns = GPUMoveThing.classifyStructuralSteps - pbClassifyStructSteps;
			long   lenRuns    = GPUMoveThing.classifyLengthOnlySteps  - pbClassifyLenSteps;
			System.out.printf("      %-16s %10.5f%n", "↳classify",    classMs / M);
			System.out.printf("      %-16s %10.5f%n", "↳poseAudit",   deltaMs / M);
			System.out.printf("      %-16s %10.5f%n", "↳capacity",    capMs   / M);
			System.out.printf("      ↳classifyRuns=%d/%d steps (structural=%d, lengthOnly=%d, redundant=%.1f%%)%n",
			                  classRuns, steps, structRuns, lenRuns,
			                  classRuns > 0 ? 100.0 * lenRuns / classRuns : 0.0);
		}
		System.out.printf("    %-18s %10.5f%n", "resetCt",       resetMs     / M);
		System.out.printf("    %-18s %10.5f%s%n", "moveDrains",   moveDrainMs / M, gpu ? "" : "  (n/a CPU)");
		System.out.printf("    %-18s %10.5f%n", "membrane",      membraneMs  / M);
		System.out.printf("    %-18s %10.5f%n", "safepoint",     safepointMs / M);
		System.out.printf("    %-18s %10.5f%n", "output",        outputMs    / M);
		System.out.printf("    %-18s %10.5f%n", "cleanup",       cleanup1Ms  / M);
		System.out.printf("    %-18s %10.5f%n", "cleanupTail",   cleanTailMs / M);
		// step/gather/brownian are CPU threadset phases that run on BOTH paths
		// (on -gpu the per-Thing integrate moved to device, but the CPU step()/
		// gatherForces()/Brownian-force phases still dispatch — measured ~20 ms/step
		// at 8× GPU, NOT zero). Include them in labeled for both paths so the GPU
		// residual is fully attributed (they were excluded for GPU before, which
		// dumped them into "other").
		double labeled = (gpu ? execMs + packMs : 0) + xforceMs + meshMs + (gpu ? 0 : moveMs)
		               + formMs + biochemMs + syncMs + stepMs + gathMs + brownMs
		               + motorColMs + resetMs + cleanup1Ms + recompMs + outputMs + cleanTailMs + moveDrainMs
		               + membraneMs + safepointMs + jointsCpuMs;
		double otherMs = wallMs - labeled;
		double gcMs    = (totalGcCollectionMs() - pbGcMs);
		System.out.printf("    %-18s %10.5f%s%n", "gc(of residual)", gcMs / M,
		                  "  [subset of otherResidual: JVM GC pauses]");
		System.out.printf("  --- totals (ms/step) ---%n");
		System.out.printf("    %-18s %10.5f%n", "total(wall)", totalPerStep);
		System.out.printf("    %-18s %10.5f%n", "host(=tot-exec)", hostPerStep);
		System.out.printf("    %-18s %10.5f  (of which gc=%.4f, non-gc=%.4f)%n", "otherResidual",
		                  otherMs / M, gcMs / M, (otherMs - gcMs) / M);
		System.out.println();
	}

	public static void updateCounters() {
		// Per-step host-pose consumer audit — disarm + dump before the increment
		// (so the step number matches the armed window) and before logAndDraw,
		// excluding output-frame reads from the tally.
		if (Thing.poseAuditWindow) {
			Thing.poseAuditWindow = false;
			System.err.println("[POSE_AUDIT step=" + Env.counter + "] reads=" + Thing.poseAuditReads
				+ " callers=" + Thing.poseAuditCallers);
			Thing.poseAuditReads = 0;
			Thing.poseAuditCallers.clear();
		}
		//update counters and flags
		paintedThisStep = false;
		// Part C: bracket the profile window's wall-clock exactly to the loop body.
		if (StepProfiler.ENABLED && profBaselineTaken) { profWallLastNs = System.nanoTime(); }
		Env.counter++;
		Env.simulationTime += Env.deltaT.getValue();
		remoteOutCounter++;
		collisionCkCounter++;
		// Sample the active link population for the steady-state mean (cheap; one
		// add/step). filLinkCt is the live active count at this end-of-step point.
		linkCtSum += FilLink.filLinkCt; linkCtSamples++;
		if (Env.simulationTime * 2 >= Env.runTime.getValue()) { linkCtSumSettled += FilLink.filLinkCt; linkCtSamplesSettled++; }
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

		if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning() || Env.glidingAssay.isActive() || Env.contractilityAssay.isActive()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
			// P1-P3 lamellipodium diagnostic: total segments vs ACTIVE Arp2/3 branches. A bounded,
			// turning-over network shows segs AND activeArps plateauing (P2/P3); runaway shows both
			// climbing linearly. Cheap, only at frame cadence.
			if (Env.buildMembraneSheet.isActive() || Env.buildMembraneSphere.isActive()) {
				System.out.printf("[LAM] t=%.4f segs=%d activeArps=%d%n",
					Env.simulationTime, FilSegment.filSegmentCt, Arp23.getNumberActiveArps());
				if (Env.arpLocalField.isActive()) System.out.printf("[ARPFIELD] hotMean=%.2f hotMin=%.2f uM (target=%.1f)%n", StickyNode.arpFieldHotMean, StickyNode.arpFieldHotMin, Env.arpConc.getValue());
				if (Env.membraneVesicle.getValue() > 0.5) System.out.printf("[VESICLE] V=%.4f V0=%.4f V/V0=%.4f P=%.2f Pa  push: maxExtF=%.3e N on %d nodes%n", StickyNode.lastVesicleV, StickyNode.restVolume, (StickyNode.restVolume>0?StickyNode.lastVesicleV/StickyNode.restVolume:0), StickyNode.lastVesicleP, StickyNode.lastMaxExtMembF, StickyNode.lastPushedNodeCt);
				if (StickyNode.membraneProbe != null) { double _pr=Math.sqrt(StickyNode.membraneProbe.getCoordX()*StickyNode.membraneProbe.getCoordX()+StickyNode.membraneProbe.getCoordY()*StickyNode.membraneProbe.getCoordY()+StickyNode.membraneProbe.getCoordZ()*StickyNode.membraneProbe.getCoordZ()); System.out.printf("[PROBE] r=%.4f contacts=%d minD=%.4f maxOverlap=%.4f maxPush=%.2e reactAlong=%.2e blebMaxR=%.4f%n", _pr, StickyNode.probeContactCt, StickyNode.probeMinD, StickyNode.probeMaxOverlap, StickyNode.probeNodePushMag, StickyNode.probeReactionX, StickyNode.probeBlebMaxR); }
			}
			if (Env.ratchetOn.isActive() && (ratchetReportCt++ % 25 == 0)) { RatchetDiag.report(); }
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
			reportContractilityStats();
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

		if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning() || Env.glidingAssay.isActive() || Env.contractilityAssay.isActive()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
			reportContractilityStats();
			// P1-P3 lamellipodium diagnostic: total segments vs ACTIVE Arp2/3 branches (turnover signature).
			if (Env.buildMembraneSheet.isActive() || Env.buildMembraneSphere.isActive()) {
				System.out.printf("[LAM] t=%.4f segs=%d activeArps=%d%n",
					Env.simulationTime, FilSegment.filSegmentCt, Arp23.getNumberActiveArps());
				if (Env.arpLocalField.isActive()) System.out.printf("[ARPFIELD] hotMean=%.2f hotMin=%.2f uM (target=%.1f)%n", StickyNode.arpFieldHotMean, StickyNode.arpFieldHotMin, Env.arpConc.getValue());
				if (Env.membraneVesicle.getValue() > 0.5) System.out.printf("[VESICLE] V=%.4f V0=%.4f V/V0=%.4f P=%.2f Pa  push: maxExtF=%.3e N on %d nodes%n", StickyNode.lastVesicleV, StickyNode.restVolume, (StickyNode.restVolume>0?StickyNode.lastVesicleV/StickyNode.restVolume:0), StickyNode.lastVesicleP, StickyNode.lastMaxExtMembF, StickyNode.lastPushedNodeCt);
				if (StickyNode.membraneProbe != null) { double _pr=Math.sqrt(StickyNode.membraneProbe.getCoordX()*StickyNode.membraneProbe.getCoordX()+StickyNode.membraneProbe.getCoordY()*StickyNode.membraneProbe.getCoordY()+StickyNode.membraneProbe.getCoordZ()*StickyNode.membraneProbe.getCoordZ()); System.out.printf("[PROBE] r=%.4f contacts=%d minD=%.4f maxOverlap=%.4f maxPush=%.2e reactAlong=%.2e blebMaxR=%.4f%n", _pr, StickyNode.probeContactCt, StickyNode.probeMinD, StickyNode.probeMaxOverlap, StickyNode.probeNodePushMag, StickyNode.probeReactionX, StickyNode.probeBlebMaxR); }
			}
			if (Env.ratchetOn.isActive() && (ratchetReportCt++ % 25 == 0)) { RatchetDiag.report(); }
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
			// Register the benchmark's two pinned endpoints (end1 of first seg, end2 of last seg).
			pinRegistry.clear();
			pinRegistry.add(new Pin(deflFil.firstSeg, 1, deflFil.anchor1));
			// Tip-flexibility diagnostic: pin only the first end → CANTILEVER (free tip at the last
			// segment), the geometry the membrane ratchet cares about. Otherwise pin both ends (the
			// standard deflection benchmark).
			if (!SingleFilDiag.TIPFLEX) {
				pinRegistry.add(new Pin(deflFil.lastSeg,  2, deflFil.anchor2));
			}
			double spanM = Pt3D.ptDist(deflFil.anchor1, deflFil.anchor2) * 1e-6;
			double forceN = 48.0 * Env.EI * Env.benchmarkForceFrac.getValue() / (spanM * spanM);
			deflFil.transForce.setVals(0, -forceN, 0); // negative Y: downward in default camera view
			deflFil.analyticDefl = Env.benchmarkForceFrac.getValue() * spanM * 1e6; // µm
			deflFil.segs = segs;
			deflFil.initCoords = new Pt3D[n];
			for (int i = 0; i < n; i++) {
				deflFil.initCoords[i] = new Pt3D(segs[i].getCoordX(), segs[i].getCoordY(), segs[i].getCoordZ());
			}
			// Suppress Brownian forces on deflection chain (per-segment, replacing removed global flag).
			// Tip-flexibility FLUCTUATION mode leaves Brownian ON (the thermal tip wobble is the point);
			// STATIC-compliance mode turns it OFF (deterministic deflection under a fixed tip force).
			if (!SingleFilDiag.TIPFLEX || SingleFilDiag.STATIC) {
				for (FilSegment s : segs) s.brownianOff = true;
			}
			if (SingleFilDiag.STATIC) {
				tipFlexForce.setVals(0, -SingleFilDiag.STATIC_FORCE_PN * 1e-12, 0); // N, transverse (-y)
			}

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
			// Skipped in the tip-flexibility diagnostic: the 48 µm LP chain (hundreds of segments)
			// is irrelevant to the cantilever measurement and would bloat the box + compute.
			if (!SingleFilDiag.TIPFLEX) {
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
			}

			// Box sizing: use the larger of deflection span and LP contour length (LP absent in tipflex)
			double maxSpan = Math.max(deflFil.chainSpanMicrons, lpFil != null ? lpFil.contourLength : 0.0);
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
		if (Env.nodeContractilityAssay.isActive()) {
				makeNodeContractilityAssay();
				return;
			}
		if (Env.contractilityAssay.isActive()) {
				makeContractilityAssay();
				return;
			}

			if (Env.twoNodeFormin.isActive()) {
				ProteinNode.makeForminNodePair();
				Env.equilNodes.setValue(ProteinNode.nodeCt);  // freeze node number (no spurious add/remove)
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
			
			if (Env.buildMembraneSheet.isActive()) {
				StickyNode.makeSheetHexPackedNodes();
				//FilSegment.makeXLinkFromNodePair();
			}

			if (Env.buildMembraneSphere.isActive()) {
				StickyNode.makeSphereOfNodes();
				StickyNode.markHotPatches(Env.sphereHotPatches.getIntValue(), Env.sphereHotPatchDeg.getValue());
				StickyNode.createMembraneProbe();   // constant-force isolation probe (no-op unless membraneProbeForce>0)
			}

			if (Env.buildDtsMembrane.isActive()) {
				if (Env.dtsBrownianOff.isActive()) Env.nodeBrownianMotionOff = true;   // deterministic relaxation
				Membrane.buildIcosphereMembrane();   // v2 DTS membrane (icosphere, flat SoA)
				Membrane.createDtsProbe();           // optional constant-force probe (no-op unless dtsProbeForce>0)
				Membrane.createDtsBouncers();        // optional bouncing nodes (no-op unless dtsBouncerCount>0)
			}

			if (Env.buildBranchedFils.isActive()) {
				if (Env.buildMembraneSheet.isActive()) {
					FilSegment.makeMembraneBranchedMothers();  // deterministic branched mothers seeded under the membrane
				} else {
					FilSegment.makeTestBranchedFilament();     // free-space single branched filament
				}
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
				// BOA_SINGLE_DIMER=1 → build a single free MyosinDimer (two coupled
				// myosins) instead of a single myosin — the 2-pool validation fixture
				// for the taForce race fix (Myosin internal joints + MyosinDimer
				// coupling run concurrently pre-fix). No body, no anchor.
				String dimerEnv = System.getenv("BOA_SINGLE_DIMER");
				boolean singleDimer = dimerEnv != null && !dimerEnv.isEmpty()
				    && !dimerEnv.equals("0") && !dimerEnv.equalsIgnoreCase("false");
				if (singleDimer) {
					new MyosinDimer(new Pt3D(0, 0, Env.fixedMyosinZValue.getValue()), new Pt3D(0, 0, 1));
					System.err.println("[SINGLE_DIMER] single free MyosinDimer via BOA_SINGLE_DIMER");
				} else {
					MyosinFixed.setUpSingleMyosinDiag();
				}
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

	// Minimal contractility assay. Two anti-parallel stiff filaments lie along X, offset +/- in Y,
	// overlapping in the central region. Filament A's body is on the +X side, pinned at the +X wall;
	// filament B's on the -X side, pinned at the -X wall. In the normal (non-reversed) polarity each
	// filament's PLUS (barbed) end is the pinned outer endpoint, so a single bipolar minifilament in
	// the central overlap walks both filaments plus-ward and pulls the anchors INWARD = contraction.
	// Tension is read as the axial force at each pinned anchor (captureContractilityTension()).
	// noMonomersSimd is set by the config, not forced here: CPU runs use noMonomersSimd:true
	// (rigid static rods). GPU runs MUST use noMonomersSimd:false (the biochem-sync path) — the
	// minifilament cohesion fix reads fresh host pose via the per-step demandSyncPoseToHost, which
	// is gated off under noMonomersSimd; with it off the minifilament blows apart on -gpu. Turnover
	// is suppressed via zeroed rates either way, so the filaments stay static in both modes.
	// Validated default physics for a stand-alone -contractility launch (no param
	// file). Mirrors ParameterFiles/contractilityAssay_gpu: a long narrow box, a
	// small stable timestep, turnover OFF (static phalloidin-stiff filaments), and
	// noMonomersSimd:false so the per-step host pose sync (the minifilament GPU
	// cohesion fix) stays on. Each value is overridden if a -pf supplies it (this
	// runs before loadParamConfig). force(v) sets value AND marks active so a 0
	// actually applies (vs falling back to the non-zero Java default).
	private static void applyContractilityDefaults() {
		java.util.function.BiConsumer<Parameter,Double> force = (p, v) -> { p.setValue(v); p.setActive(true); };
		force.accept(Env.deltaT, 1.0e-5);          // small, stable
		force.accept(Env.boxXDim, 4.0);            // long axis
		force.accept(Env.boxYDim, 0.3);
		force.accept(Env.boxZDim, 0.2);
		force.accept(Env.stdSegLength, 64.0);      // monomers/seg -> 13-seg filaments ~2.28 µm (overlap ~0.76 µm)
		force.accept(Env.toFileInterval, 100.0);   // ~frame cadence
		force.accept(Env.runTime, 0.2);            // 20k steps @1e-5 — reaches the tension plateau
		// Turnover OFF — keep the two filaments static for a clean isometric readout.
		force.accept(Env.initialFilaments, 0.0);
		force.accept(Env.kATPOn1, 0.0);
		force.accept(Env.kATPOn2, 0.0);
		force.accept(Env.capRate, 0.0);
		force.accept(Env.cofilinRate, 0.0);
		force.accept(Env.kHydrolysis, 0.0);
		Env.kRdmNuc.setActive(false);
		Env.actinConc.setActive(false);
		Env.noMonomersSimd.setActive(false);       // monomers simulated -> per-step pose sync ON
		System.out.println("[CONTRACT] -contractility: applied stand-alone assay defaults "
			+ "(box 4.0x0.3x0.2 µm, dt=1e-5, turnover off). Pass -pf to override; add -gpu for the device path.");
	}

	public static void makeContractilityAssay() {
		if (Env.useGPU && Env.noMonomersSimd.isActive()) {
			System.out.println("[CONTRACT][WARN] -gpu with noMonomersSimd:true gates off the per-step host "
				+ "pose sync the minifilament cohesion fix needs -> the minifilament will blow apart. Use a "
				+ "config with noMonomersSimd:false for GPU runs (e.g. ParameterFiles/contractilityAssay_gpu).");
		}
		contract = new ContractAssay();
		pinRegistry.clear();

		int n = Env.contractFilNSegs.getIntValue();
		int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
		double segLen = (monCt + 1) * FilSegment.halfmono; // µm
		double Lfil = n * segLen;
		double Lx = Env.boxXDim.getValue() / 2.0;
		double margin = 0.10; // µm: inset the pinned plus end from the box wall so the boundary force
		                      // never touches the anchor segment, keeping the axial readout clean.
		double anchorX = Lx - margin;
		double yOff = Env.contractFilYOffset.getValue();
		boolean rev = Env.contractReversePolarity.isActive();

		// Filament A: body on +X side, pinned at +X wall, +yOff in Y. buildDir points inward (-X).
		// Normal polarity uVec=+X -> end2 (plus) at the +X wall (pinned). Reversed -> uVec=-X.
		contract.buildDirA.setVals(-1, 0, 0);
		Pt3D uVecA = rev ? new Pt3D(-1, 0, 0) : new Pt3D(1, 0, 0);
		contract.anchorPtA.setVals(anchorX, yOff, 0);
		contract.filA = FilSegment.makeStraightChain(n, contract.anchorPtA, contract.buildDirA, uVecA, true);
		contract.anchorSegA = contract.filA[0];
		int pinEndA = (Pt3D.Dot(contract.buildDirA, uVecA) > 0) ? 1 : 2;
		pinRegistry.add(new Pin(contract.anchorSegA, pinEndA, contract.anchorPtA));

		// Filament B: body on -X side, pinned at -X wall, -yOff in Y. buildDir points inward (+X).
		contract.buildDirB.setVals(1, 0, 0);
		Pt3D uVecB = rev ? new Pt3D(1, 0, 0) : new Pt3D(-1, 0, 0);
		contract.anchorPtB.setVals(-anchorX, -yOff, 0);
		contract.filB = FilSegment.makeStraightChain(n, contract.anchorPtB, contract.buildDirB, uVecB, true);
		contract.anchorSegB = contract.filB[0];
		int pinEndB = (Pt3D.Dot(contract.buildDirB, uVecB) > 0) ? 1 : 2;
		pinRegistry.add(new Pin(contract.anchorSegB, pinEndB, contract.anchorPtB));

		// Single bipolar minifilament centred in the overlap, oriented along X (omitted in the
		// no-motor control). Turnover is disabled via the myoMiniLifetime parameter in the config.
		if (!Env.contractNoMotor.isActive()) {
			contract.mini = new MyoMiniFilament(new Pt3D(0, 0, 0), new Pt3D(1, 0, 0));
		}

		double overlap = 2.0 * Lfil - 2.0 * anchorX; // central x-extent shared by both filaments
		double miniSpan = MyoMiniFilament.length;
		System.out.printf("[CONTRACT] box X=%.3f µm (anchors at x=+/-%.3f, %.2f µm inset), %d-seg filaments, Lfil=%.3f µm%n",
			Env.boxXDim.getValue(), anchorX, margin, n, Lfil);
		System.out.printf("[CONTRACT] yOffset=+/-%.3f µm, overlap=%.3f µm, minifilament span=%.3f µm, polarity=%s, motor=%s%n",
			yOff, overlap, miniSpan, rev ? "REVERSED (plus ends inward)" : "normal (plus ends outward)",
			Env.contractNoMotor.isActive() ? "OFF (control)" : "ON");
		if (overlap < miniSpan) {
			System.out.printf("[CONTRACT][WARN] overlap (%.3f µm) < minifilament span (%.3f µm): the minifilament cannot bridge both filaments. "
				+ "Increase contractFilNSegs or shrink boxXDim.%n", overlap, miniSpan);
		}
	}

	// Node-assay defaults (applied on a stand-alone -contractilityNode launch, on top of
	// applyContractilityDefaults). Sets the carrier-node myosin population, radius, an
	// effectively-infinite node lifetime (no stochastic death — clean isometric oracle), a
	// dialed-down myosin thermal (avoid twirl, per the production prefs), and a still carrier
	// body. Each value is overridden if a -pf supplies it (this runs before loadParamConfig).
	private static void applyNodeContractilityDefaults() {
		java.util.function.BiConsumer<Parameter,Double> force = (p, v) -> { p.setValue(v); p.setActive(true); };
		force.accept(Env.numNodeMyos, 60.0);        // singlet myosins per carrier node (generous — see note)
		force.accept(Env.numNodeMyoDimers, 30.0);   // dimers per carrier node
		force.accept(Env.contractNodeRadius, 0.06); // bridging sphere, slightly above the default nodeRadius
		force.accept(Env.contractNodeCount, 1.0);   // one sphere centred in the overlap bridges both filaments
		force.accept(Env.contractNodeYOffset, 0.05);
		force.accept(Env.nodeLifetime, 1.0e9);      // effectively immortal — no node death over the run
		// CRITICAL: keep FULL myosin thermal (the global 1.0 default). Unlike the minifilament,
		// whose end dimers are pre-positioned ON the two filaments, the node's surface myosins start
		// at random radial points and must DIFFUSE to find the filaments. With the thermal dialed
		// down (e.g. myoBrownianAttn=0.1) the heads freeze in place and NEVER bind -> zero tension.
		// Thermal search is the essential enabler of the node bridge (validated empirically).
		force.accept(Env.myoBrownianAttn, 1.0);
		Env.nodeBrownianMotionOff = true;           // keep the carrier node BODY still (isometric); heads still search
		System.out.println("[CONTRACT] -contractilityNode: node-assay defaults applied "
			+ "(numNodeMyos=60, numNodeMyoDimers=30, contractNodeRadius=0.06 µm, 1 node, nodeLifetime=inf, "
			+ "myoBrownianAttn=1.0 [thermal search ON — required for binding], node body Brownian OFF). Pass -pf to override.");
	}

	// Node analog of makeContractilityAssay(). The anti-parallel pinned-filament scaffold and
	// the Pin registry are reused VERBATIM; only the load source differs — protein node(s)
	// carrying surface myosins (numNodeMyos singlets + numNodeMyoDimers dimers) replace the
	// bipolar minifilament. A single sphere centred in the overlap bridges both filaments: its
	// +Y-side surface myosins capture filament A (at +yOff) and its -Y-side ones capture filament
	// B (at -yOff); each head power-strokes toward its filament's plus end, pulling both anchors
	// inward = contraction. Tension is read identically via captureContractilityTension().
	public static void makeNodeContractilityAssay() {
		contract = new ContractAssay();
		pinRegistry.clear();

		int n = Env.contractFilNSegs.getIntValue();
		int monCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
		double segLen = (monCt + 1) * FilSegment.halfmono; // µm
		double Lfil = n * segLen;
		double Lx = Env.boxXDim.getValue() / 2.0;
		double margin = 0.10; // µm: inset the pinned plus end from the box wall (clean axial readout)
		double anchorX = Lx - margin;
		double yOff = Env.contractFilYOffset.getValue();
		boolean rev = Env.contractReversePolarity.isActive();

		// Filament A: body on +X side, pinned at +X wall, +yOff in Y. buildDir points inward (-X).
		contract.buildDirA.setVals(-1, 0, 0);
		Pt3D uVecA = rev ? new Pt3D(-1, 0, 0) : new Pt3D(1, 0, 0);
		contract.anchorPtA.setVals(anchorX, yOff, 0);
		contract.filA = FilSegment.makeStraightChain(n, contract.anchorPtA, contract.buildDirA, uVecA, true);
		contract.anchorSegA = contract.filA[0];
		int pinEndA = (Pt3D.Dot(contract.buildDirA, uVecA) > 0) ? 1 : 2;
		pinRegistry.add(new Pin(contract.anchorSegA, pinEndA, contract.anchorPtA));

		// Filament B: body on -X side, pinned at -X wall, -yOff in Y. buildDir points inward (+X).
		contract.buildDirB.setVals(1, 0, 0);
		Pt3D uVecB = rev ? new Pt3D(1, 0, 0) : new Pt3D(-1, 0, 0);
		contract.anchorPtB.setVals(-anchorX, -yOff, 0);
		contract.filB = FilSegment.makeStraightChain(n, contract.anchorPtB, contract.buildDirB, uVecB, true);
		contract.anchorSegB = contract.filB[0];
		int pinEndB = (Pt3D.Dot(contract.buildDirB, uVecB) > 0) ? 1 : 2;
		pinRegistry.add(new Pin(contract.anchorSegB, pinEndB, contract.anchorPtB));

		// Load source: protein node(s) carrying surface myosins, centred in the overlap (omitted
		// in the no-motor control). numNodeMyos / numNodeMyoDimers are read at ProteinNode
		// construction, so they must already be set (applyNodeContractilityDefaults or the -pf).
		double r = Env.contractNodeRadius.getValue();
		if (!Env.contractNoMotor.isActive()) {
			int nodeCount = Math.max(1, Env.contractNodeCount.getIntValue());
			double nodeYOff = Env.contractNodeYOffset.getValue();
			contract.nodes = new ProteinNode[nodeCount];
			if (nodeCount == 1) {
				// One sphere at the overlap centre — bridges both filaments.
				contract.nodes[0] = new ProteinNode(new Pt3D(0, 0, 0), r);
			} else {
				// Two (or more) spheres staggered in Y toward each filament (exploratory).
				for (int k = 0; k < nodeCount; k++) {
					double yc = (k % 2 == 0) ? nodeYOff : -nodeYOff;
					contract.nodes[k] = new ProteinNode(new Pt3D(0, yc, 0), r);
				}
			}
			Env.equilNodes.setValue(ProteinNode.nodeCt);  // freeze node number (no spurious add/remove)
		}

		double overlap = 2.0 * Lfil - 2.0 * anchorX; // central x-extent shared by both filaments
		double nodeReach = r + Env.myoRodLength.getValue() + Env.myoLeverLength.getValue(); // surface myosin radial reach
		int totalMyos = Env.numNodeMyos.getIntValue() + 2 * Env.numNodeMyoDimers.getIntValue();
		System.out.printf("[CONTRACT][NODE] box X=%.3f µm (anchors at x=+/-%.3f, %.2f µm inset), %d-seg filaments, Lfil=%.3f µm%n",
			Env.boxXDim.getValue(), anchorX, margin, n, Lfil);
		System.out.printf("[CONTRACT][NODE] yOffset=+/-%.3f µm, overlap=%.3f µm, nodes=%d radius=%.3f µm, myo heads/node=%d, reach=%.3f µm, polarity=%s, motor=%s%n",
			yOff, overlap, Env.contractNoMotor.isActive() ? 0 : Math.max(1, Env.contractNodeCount.getIntValue()),
			r, totalMyos, nodeReach,
			rev ? "REVERSED (plus ends inward)" : "normal (plus ends outward)",
			Env.contractNoMotor.isActive() ? "OFF (control)" : "ON");
		if (!Env.contractNoMotor.isActive() && nodeReach < yOff) {
			System.out.printf("[CONTRACT][NODE][WARN] surface-myosin reach (%.3f µm) < filament Y offset (%.3f µm): "
				+ "node myosins cannot reach the filaments. Increase contractNodeRadius or shrink contractFilYOffset.%n",
				nodeReach, yOff);
		}
	}

	// Read the net force on each pinned anchor segment, projected onto the inward buildDir
	// (positive = contractile). Called after the per-step force gather, before the pin snaps the
	// endpoint back: at that point forceSum on the anchor segment is exactly the reaction the pin
	// must supply = the tension transmitted down the chain from the minifilament.
	private static void captureContractilityTension() {
		if (contract == null) return;
		if (contract.anchorSegA != null) {
			contract.forceA.setVals(contract.anchorSegA.getForceSumX(), contract.anchorSegA.getForceSumY(), contract.anchorSegA.getForceSumZ());
			addDeviceJointForce(contract.anchorSegA, contract.forceA);
			contract.tensionA_pN = Pt3D.Dot(contract.forceA, contract.buildDirA) * 1e12;
		}
		if (contract.anchorSegB != null) {
			contract.forceB.setVals(contract.anchorSegB.getForceSumX(), contract.anchorSegB.getForceSumY(), contract.anchorSegB.getForceSumZ());
			addDeviceJointForce(contract.anchorSegB, contract.forceB);
			contract.tensionB_pN = Pt3D.Dot(contract.forceB, contract.buildDirB) * 1e12;
		}
	}

	// On the GPU path the anchor's chain reaction (F3/F4) is computed on-device into
	// jointForceSum and never gathered into the host soaForceSum the readout above
	// projects, so without this the GPU tension reads ~0 even while the device applies
	// the full contractile force through the chain. Add the device joint force (gathered
	// per execute when the assay is active) to the host force sum. No-op on the CPU path
	// (the chain force is already in soaForceSum there) and when the seg isn't GPU-handled.
	private static void addDeviceJointForce(FilSegment anchorSeg, Pt3D force) {
		if (!Env.useGPU) return;
		if (GPUMoveThing.readDeviceJointForce(anchorSeg.myThingNumber, contract.jointF)) {
			force.x += contract.jointF[0];
			force.y += contract.jointF[1];
			force.z += contract.jointF[2];
		}
	}

	// Accumulate the per-step running statistics that feed the live HUD and the
	// frame 'contractility' block. Called every step (right after
	// captureContractilityTension), so the averages are true time-averages.
	private static void accumulateContractilityStats() {
		if (contract == null) return;
		contract.instBound = contractBoundMotors();
		double meanTension = 0.5 * (Math.abs(contract.tensionA_pN) + Math.abs(contract.tensionB_pN));

		contract.statSamples++;
		contract.sumBound    += contract.instBound;
		contract.sumTension  += meanTension;
		contract.sumTensionA += contract.tensionA_pN;
		contract.sumTensionB += contract.tensionB_pN;

		if (!contract.ewmaInit) {
			contract.ewmaBound   = contract.instBound;
			contract.ewmaTension = meanTension;
			contract.ewmaInit = true;
		} else {
			contract.ewmaBound   += STAT_EWMA_ALPHA * (contract.instBound - contract.ewmaBound);
			contract.ewmaTension += STAT_EWMA_ALPHA * (meanTension - contract.ewmaTension);
		}
		if (meanTension > contract.peakTension) contract.peakTension = meanTension;
		if (contract.instBound > contract.peakBound) contract.peakBound = contract.instBound;
		if (contract.firstBindStep < 0 && contract.instBound > 0) contract.firstBindStep = Env.counter;
	}

	// Heads currently bound to actin, summed across whichever load source the assay uses
	// (the minifilament's dimers, or the node(s)' singlets + dimers). Indifferent to the source.
	private static int contractBoundMotors() {
		if (contract == null) return 0;
		if (contract.mini != null) return contract.mini.countBoundMotors();
		if (contract.nodes != null) {
			int c = 0;
			for (ProteinNode nd : contract.nodes) if (nd != null && !nd.removeMe) c += nd.countBoundMotors();
			return c;
		}
		return 0;
	}

	// True when the assay has a motor load source present (minifilament OR node(s)); false in
	// the no-motor control. Used by ThreeJSWriter's hasMotor stats flag.
	static boolean contractHasMotor() {
		return contract != null && (contract.mini != null || contract.nodes != null);
	}

	// Cumulative mean helpers (used by the reporter and the frame writer).
	static double contractAvgBound()    { return (contract != null && contract.statSamples > 0) ? contract.sumBound   / contract.statSamples : 0; }
	static double contractAvgTension()  { return (contract != null && contract.statSamples > 0) ? contract.sumTension / contract.statSamples : 0; }
	static double contractAvgTensionA() { return (contract != null && contract.statSamples > 0) ? contract.sumTensionA / contract.statSamples : 0; }
	static double contractAvgTensionB() { return (contract != null && contract.statSamples > 0) ? contract.sumTensionB / contract.statSamples : 0; }

	// Emit the contractility tension trace at output-frame cadence. tensionA/B are the axial
	// reaction at each anchor projected onto the inward direction: positive = contractile (anchors
	// pulled inward), negative = extensile. boundMotors counts minifilament heads currently on actin.
	private static void reportContractilityStats() {
		if (!Env.contractilityAssay.isActive() || contract == null) return;
		System.out.printf("[STATS] contractility step=%d t=%.4f tensionA=%.4f tensionB=%.4f pN boundMotors=%d "
			+ "| avgBound=%.2f ewmaBound=%.2f avgTension=%.3f ewmaTension=%.3f peakTension=%.3f pN%n",
			Env.counter, Env.simulationTime, contract.tensionA_pN, contract.tensionB_pN, contract.instBound,
			contractAvgBound(), contract.ewmaBound, contractAvgTension(), contract.ewmaTension, contract.peakTension);
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
