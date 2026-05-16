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

	// F1 benchmark state — populated by makeInitialThings() when Env.benchmarkFilament is set
	static FilSegment benchFirstSeg = null;
	static FilSegment benchLastSeg = null;
	static FilSegment benchMidSeg = null;
	static Pt3D benchAnchor1 = new Pt3D();
	static Pt3D benchAnchor2 = new Pt3D();
	static Pt3D benchTransForce = new Pt3D();
	static int benchStepCount = 0;
	static double benchAnalyticDefl = 0;

	// F1 Step 2: search-loop constants
	static final double BENCH_SEARCH_TOL            = 0.01;   // ±1% deflection ratio tolerance
	static final int    BENCH_SETTLE_BASE            = 5000;   // base settle steps (calibrated for monomerCt=32)
	static final int    BENCH_SETTLE_REF_MONOMER_CT  = 32;
	static final int    BENCH_SETTLE_CHECK_INTERVAL  = 1000;   // steps between consecutive settle checks
	static final double BENCH_SETTLE_CONV_TOL        = 0.005;  // 0.5% consecutive-check convergence
	static final int    BENCH_SEARCH_MAX_SETTLE      = 500000; // absolute per-eval step cap
	static final double BENCH_SEARCH_GEO_FACTOR      = 4.0;   // geometric step multiplier for bracketing phase
	static final double BENCH_SEARCH_MAX_COEFF       = 100.0;  // bail-out ceiling for fracMoveTorq

	// F1 Step 2: search-loop state — all set by makeInitialThings() before first step
	static FilSegment[] benchSegs           = null;        // full segment array for per-evaluation reset
	static Pt3D[]       benchInitCoords     = null;        // stored straight-line center positions
	static int          benchSearchIter     = 0;
	static double       benchSearchLo       = 0.0;         // fracMoveTorq giving ratio > 1+tol (too soft)
	static double       benchSearchHi       = -1.0;        // fracMoveTorq giving ratio < 1-tol; -1 = not yet found
	static double       benchSearchCand     = 0.0;         // fracMoveTorq currently under evaluation
	static int          benchMinSettleSteps = BENCH_SETTLE_BASE; // computed from monomerCt in makeInitialThings
	static double       benchPrevCheckRatio = Double.NaN;  // ratio at previous settle check

	public BoxOfActin (String[] args) {
		
	}
	
	// Entry point: the default-package BoxOfActin.java at the project root has the main() method;
	// it parses no arguments itself and immediately calls this begin(args). Run with: java -Xmx800M BoxOfActin
	public static void begin (String[] args) {
		parseArgs(args);
		if (Env.benchmarkFilament) {
			Env.brownianFilMotionOff = true;
			Env.remote = true;
			Env.paused = false; // benchmark runs headless; no WebSocket client to send resume
			Env.simOutsideBug.setActive(false); // suppress Listeria bug + ActA creation
		}
		if (Env.benchmarkDiag) {
			Env.runTime.setValue(600); // 6M steps at deltaT=1e-4; diag exits at 5M via System.exit
		}
		if (Env.threeJSLivePort > 0) {
			LiveFrameServer.startServer(Env.threeJSLivePort);
		}
		System.err.println("[TELEPORT_DIAG] enabled=" + Env.myoMiniTeleportDiag
    + " threshold=" + Env.myoMiniTeleportThreshold);
		
		if (Env.paramFile != null) { FileOps.loadParamConfig(Env.paramFile, false); }
		if (Env.logFiles) { FileOps.remoteParamConfigSave(); }
		
		// reset dependent parameters, etc
		Env.setTimeStepCounts();
		Env.setDependencies();
		FileOps.recalcJSonValues();
		
		// make Things, etc
		makeCrucible();
		makeInitialThings();
		Mesh.createMeshes(); 	// for 2D grid collision detection
		
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
		tSets = new ThreadSet [16];
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
				startAllThreadSets(Env.motCollStart);
				waitOnAllThreadSets(Env.motCollStop);
				motorsAndFilsColTimer.stopInc();

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
				if (Env.benchmarkFilament && benchMidSeg != null) {
					benchMidSeg.incForceSum(benchTransForce);
				}

				moveTimer.start();
				startAllThreadSets(Env.moveStart);
				waitOnAllThreadSets(Env.moveStop);
				moveTimer.stopInc();

				// F1 benchmark: restore pinned endpoints after integration
				if (Env.benchmarkFilament) { applyBenchmarkPins(); }

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
						double defl = benchAnalyticDefl * ratio;
						double dRatio = Double.isNaN(benchPrevCheckRatio) ? Double.NaN : ratio - benchPrevCheckRatio;
						System.out.printf("[BMDIAG] step=%8d  simT=%8.2fs  ratio=%.6f  defl=%.6fµm  dRatio=%s%n",
							benchStepCount, Env.simulationTime, ratio, defl,
							Double.isNaN(dRatio) ? "      N/A" : String.format("%+.6f", dRatio));
						System.out.flush();
						benchPrevCheckRatio = ratio;
					}
					if (benchStepCount >= 5_000_000) {
						System.out.printf("[BMDIAG] DONE: %d steps  simT=%.1fs  final ratio=%.6f%n",
							benchStepCount, Env.simulationTime, computeDeflectionRatio());
						System.exit(0);
					}
				}

				// F1 Step 2: bisection search on fracMoveTorq — advances at safe point when chain has settled
				if (Env.benchmarkFilament && !Env.benchmarkDiag) {
					benchStepCount++;

					// Settle convergence check: every BENCH_SETTLE_CHECK_INTERVAL steps after minimum warmup
					boolean doEval = false;
					double evalRatio = Double.NaN;
					if (benchStepCount >= benchMinSettleSteps && benchStepCount % BENCH_SETTLE_CHECK_INTERVAL == 0) {
						double r = computeDeflectionRatio();
						if (!Double.isNaN(benchPrevCheckRatio)) {
							double avg = (r + benchPrevCheckRatio) / 2.0;
							if (avg > 0 && Math.abs(r - benchPrevCheckRatio) / avg <= BENCH_SETTLE_CONV_TOL) {
								doEval = true;
								evalRatio = r;
							}
						}
						benchPrevCheckRatio = r;
					}

					// Hard cap: force eval if max settle steps reached
					if (!doEval && benchStepCount >= BENCH_SEARCH_MAX_SETTLE) {
						evalRatio = computeDeflectionRatio();
						doEval = true;
						System.out.println("[BENCH:WARN] settle cap hit at step " + benchStepCount
							+ " for iter=" + benchSearchIter + "; ratio=" + String.format("%.4f", evalRatio));
					}

					if (doEval) {
						double ratio = evalRatio;

						// convergence check (bisection has converged to target)
						if (Math.abs(ratio - 1.0) <= BENCH_SEARCH_TOL) {
							System.out.println("[BENCH:SEARCH] iter=" + benchSearchIter
								+ "  fracMoveTorq=" + expFormat.format(benchSearchCand)
								+ "  ratio=" + String.format("%.4f", ratio)
								+ "  lo=" + expFormat.format(benchSearchLo)
								+ "  hi=" + (benchSearchHi < 0 ? "?" : expFormat.format(benchSearchHi))
								+ "  settleSteps=" + benchStepCount);
							System.out.println("[BENCH] CONVERGED"
								+ "  fracMoveTorq=" + expFormat.format(benchSearchCand)
								+ "  ratio=" + String.format("%.4f", ratio)
								+ "  iters=" + (benchSearchIter + 1)
								+ "  fracR=" + expFormat.format(Env.fracR.getValue()));
							System.exit(0);
						}

						// update brackets
						if (ratio > 1.0) {
							benchSearchLo = benchSearchCand; // too soft: raise lower bound
						} else {
							benchSearchHi = benchSearchCand; // too stiff: lower upper bound
						}

						// per-evaluation progress line
						System.out.println("[BENCH:SEARCH] iter=" + benchSearchIter
							+ "  fracMoveTorq=" + expFormat.format(benchSearchCand)
							+ "  ratio=" + String.format("%.4f", ratio)
							+ "  lo=" + expFormat.format(benchSearchLo)
							+ "  hi=" + (benchSearchHi < 0 ? "?" : expFormat.format(benchSearchHi))
							+ "  settleSteps=" + benchStepCount);
						benchSearchIter++;

						// pick next candidate
						double next;
						if (benchSearchHi < 0) {
							// geometric stepping to find upper bracket
							next = benchSearchCand * BENCH_SEARCH_GEO_FACTOR;
							if (next > BENCH_SEARCH_MAX_COEFF) {
								System.out.println("[BENCH] FAIL: bracketing failed — fracMoveTorq="
									+ expFormat.format(next) + " exceeds ceiling "
									+ BENCH_SEARCH_MAX_COEFF + "; last ratio=" + String.format("%.4f", ratio));
								System.exit(1);
							}
						} else {
							next = (benchSearchLo + benchSearchHi) / 2.0;
						}

						benchSearchCand = next;
						Env.fracMoveTorq.setValue(next);
						resetBenchmarkChain();
						benchStepCount = 0;
						benchPrevCheckRatio = Double.NaN;
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
		reportAllThreadSetTimes();
		
	}
	
	// F1 benchmark: translate terminal segments so their pinned endpoints return to anchors.
	// Called after moveThing() each step. Post-moveThing() position correction handles both
	// centroid translation and rotation (rotation pivots about centroid, not pin, so the
	// endpoint drifts; the correction below restores it exactly regardless of the source).
	private static void applyBenchmarkPins() {
		if (benchFirstSeg == null || benchLastSeg == null) return;
		benchFirstSeg.coord.x += benchAnchor1.x - benchFirstSeg.end1.x;
		benchFirstSeg.coord.y += benchAnchor1.y - benchFirstSeg.end1.y;
		benchFirstSeg.coord.z += benchAnchor1.z - benchFirstSeg.end1.z;
		benchFirstSeg.initialize();
		benchLastSeg.coord.x += benchAnchor2.x - benchLastSeg.end2.x;
		benchLastSeg.coord.y += benchAnchor2.y - benchLastSeg.end2.y;
		benchLastSeg.coord.z += benchAnchor2.z - benchLastSeg.end2.z;
		benchLastSeg.initialize();
	}

	// F1 benchmark: compute midpoint perpendicular deflection ratio (measured / analytic).
	private static double computeDeflectionRatio() {
		if (benchMidSeg == null) return Double.NaN;
		double ax = benchAnchor2.x - benchAnchor1.x;
		double ay = benchAnchor2.y - benchAnchor1.y;
		double az = benchAnchor2.z - benchAnchor1.z;
		double aLen = Math.sqrt(ax*ax + ay*ay + az*az);
		ax /= aLen; ay /= aLen; az /= aLen;
		double px = benchMidSeg.coord.x - benchAnchor1.x;
		double py = benchMidSeg.coord.y - benchAnchor1.y;
		double pz = benchMidSeg.coord.z - benchAnchor1.z;
		double proj = px*ax + py*ay + pz*az;
		double perpX = px - proj*ax, perpY = py - proj*ay, perpZ = pz - proj*az;
		double deflMeas = Math.sqrt(perpX*perpX + perpY*perpY + perpZ*perpZ);
		return benchAnalyticDefl > 0 ? deflMeas / benchAnalyticDefl : Double.NaN;
	}

	// F1 benchmark: print midpoint perpendicular deflection vs analytic FL³/(48EI).
	private static void reportBenchmarkDeflection(String label) {
		if (benchMidSeg == null) return;
		double ratio = computeDeflectionRatio();
		double ax = benchAnchor2.x - benchAnchor1.x;
		double ay = benchAnchor2.y - benchAnchor1.y;
		double az = benchAnchor2.z - benchAnchor1.z;
		double aLen = Math.sqrt(ax*ax + ay*ay + az*az);
		double px = benchMidSeg.coord.x - benchAnchor1.x;
		double py = benchMidSeg.coord.y - benchAnchor1.y;
		double pz = benchMidSeg.coord.z - benchAnchor1.z;
		double proj = px*(ax/aLen) + py*(ay/aLen) + pz*(az/aLen);
		double perpX = px - proj*(ax/aLen), perpY = py - proj*(ay/aLen), perpZ = pz - proj*(az/aLen);
		double deflMeas = Math.sqrt(perpX*perpX + perpY*perpY + perpZ*perpZ);
		System.out.println("[BENCH:" + label + "] step=" + benchStepCount
			+ "  meas=" + deflectionFormat.format(deflMeas) + " µm"
			+ "  analytic=" + deflectionFormat.format(benchAnalyticDefl) + " µm"
			+ "  ratio=" + String.format("%.4f", ratio));
	}

	// F1 Step 2: restore all benchmark segments to their initial straight-line configuration.
	// Called at the safe point before each new search evaluation.
	private static void resetBenchmarkChain() {
		if (benchSegs == null || benchInitCoords == null) return;
		for (int i = 0; i < benchSegs.length; i++) {
			FilSegment s = benchSegs[i];
			s.coord.setVals(benchInitCoords[i].x, benchInitCoords[i].y, benchInitCoords[i].z);
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

		if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
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

		if ((Env.threeJSOutputDir != null || LiveFrameServer.isRunning()) && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
			threeJSCounter = 0;
		}
	}

	public static void makeInitialThings() {
		if (Env.benchmarkFilament) {
			Env.noMonomersSimd.setActive(true);
			FilSegment[] segs = FilSegment.makeBenchmarkChain(Env.benchmarkNSegs);
			int n = segs.length;
			benchFirstSeg = segs[0];
			benchLastSeg  = segs[n - 1];
			benchMidSeg   = segs[n / 2];
			benchAnchor1.setVals(segs[0].end1.x, segs[0].end1.y, segs[0].end1.z);
			benchAnchor2.setVals(segs[n-1].end2.x, segs[n-1].end2.y, segs[n-1].end2.z);
			double spanM = Pt3D.ptDist(benchAnchor1, benchAnchor2) * 1e-6;
			double forceN = 48.0 * Env.EI * Env.benchmarkForceFrac / (spanM * spanM);
			benchTransForce.setVals(0, 0, forceN);
			benchAnalyticDefl = Env.benchmarkForceFrac * spanM * 1e6; // µm
			System.out.println("[BENCH] " + n + "-seg chain"
				+ ", span=" + String.format("%.4f", spanM * 1e6) + " µm"
				+ ", F=" + String.format("%.3e", forceN) + " N"
				+ ", analytic δ=" + String.format("%.4f", benchAnalyticDefl) + " µm");
			// Step 2: store segments and initial straight-line positions for per-evaluation reset
			benchSegs = segs;
			benchInitCoords = new Pt3D[n];
			for (int i = 0; i < n; i++) {
				benchInitCoords[i] = new Pt3D(segs[i].coord.x, segs[i].coord.y, segs[i].coord.z);
			}

			// Settle window scales as L_seg³ ∝ monomerCt³: τ_slow ∝ ζ_rot/fracMoveTorq ∝ L_seg³/fracMoveTorq
			int benchMonCt = (Env.benchmarkMonomerCt > 0) ? Env.benchmarkMonomerCt : Env.stdSegLength.getIntValue();
			benchMinSettleSteps = (int)Math.min(
				BENCH_SETTLE_BASE * Math.pow((double)benchMonCt / BENCH_SETTLE_REF_MONOMER_CT, 3.0),
				BENCH_SEARCH_MAX_SETTLE * 2.0 / 3.0);
			System.out.println("[BENCH] minSettleSteps=" + benchMinSettleSteps + "  monomerCt=" + benchMonCt);

			// Fix 2: auto-size box to 3× chain span along X and Y so wall collisions never contaminate
			double totalSpan = Pt3D.ptDist(benchAnchor1, benchAnchor2); // µm
			double benchBoxDim = Math.max(totalSpan * 3.0, Env.boxXDim.getValue());
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

			// Fix 3: lo=0 ensures a downward-search path is always available if starting cand is too stiff
			benchSearchLo       = 0.0;
			benchSearchHi       = -1.0;
			benchSearchCand     = Env.fracMoveTorq.getValue();
			benchSearchIter     = 0;
			benchStepCount      = 0;
			benchPrevCheckRatio = Double.NaN;
			if (Env.benchmarkDiag) {
				System.out.printf("[BMDIAG] fixed-param diagnostic: fracMoveTorq=%.4f  monomerCt=%d  fracR=%.4f  span=%.4f µm%n",
					Env.fracMoveTorq.getValue(), benchMonCt, Env.fracR.getValue(), totalSpan);
				System.out.printf("[BMDIAG] analytic δ=%.6f µm  reporting every 5000 steps  cap=5,000,000 steps%n",
					benchAnalyticDefl);
			}
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
