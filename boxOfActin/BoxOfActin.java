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
	
	public BoxOfActin (String[] args) {
		
	}
	
	// Entry point: the default-package BoxOfActin.java at the project root has the main() method;
	// it parses no arguments itself and immediately calls this begin(args). Run with: java -Xmx800M BoxOfActin
	public static void begin (String[] args) {
		parseArgs(args);
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
		}
	}
	
	static class TimeLoop extends Thread {
	
		public void run() {
			doLoop();
			
			FileOps.closeJSons();
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
		
		while (Env.simulationTime <= (Env.runTime.getValue()+Env.runBump)) {
			if (Env.paused) {
				try { Thread.sleep(1000); } catch (InterruptedException e) { talkln ("error sleeping"); }
			} else {
				synchronized(Env.safeO) {
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
					
					moveTimer.start();
					startAllThreadSets(Env.moveStart);
					waitOnAllThreadSets(Env.moveStop);
					moveTimer.stopInc();
					
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
		}
		System.out.println("collisionTime = " + collisionTime);
		System.out.println("myosinTime = " + myosinTime);
		reportAllThreadSetTimes();
		
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

		if (Env.threeJSOutputDir != null && threeJSCounter >= Env.toFileInterval.getIntValue()) {
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

		if (Env.threeJSOutputDir != null && threeJSCounter >= Env.toFileInterval.getIntValue()) {
			ThreeJSWriter.writeFrame();
			threeJSCounter = 0;
		}
	}

	public static void makeInitialThings() {
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
