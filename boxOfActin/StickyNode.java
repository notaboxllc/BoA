package boxOfActin;

import java.awt.Color;
import java.awt.Font;

import boxOfActin.ProteinNode.ProteinNodeThreads;

public class StickyNode extends ProteinNode {
	static int maxStickies = 9;
	Pt3D [] stickyPointsInx = new Pt3D[maxStickies];
	Pt3D [] stickyPointsInX = new Pt3D[maxStickies];
	boolean [] isBound = new boolean[maxStickies];
	StickyNode [] boundTo = new StickyNode[maxStickies];
	int boundCt = 0;
	int valence = 0;
	String myIJ;
	boolean iAmConstricting = false;  // for testing, specify ring of nodes in center to get inward force
	double hotTime = 0;
	double arpLocal = 0;       // per-node Arp2/3 concentration (uM): the localized-depletion field
	double arpLocalNext = 0;   // Jacobi scratch for the diffusion update
	static boolean arpFieldInit = false;
	static boolean sphericalGeometry = false;
	static Pt3D centerOfSphere = new Pt3D(0,0,0);
	static int maxHotSpots = 6;
	static int hotSpotCt = 0;
	boolean iAmHotSpotOriginator = false;
	StickyNode myHotSpotOriginator;
	
	boolean hotRhoGraphicsOn = false;
	
	// for multithreading
	static MembraneNodeThreads membraneNodeThreads = new MembraneNodeThreads();
	
	// hexagonal packing
	static double packRAdj = 0.7;
	
	// descriptions for valence/bond placement
	final static int DIMER = 1; 
	final static int LINEAR = 2;
	final static int PLANAR_3 = 3;
	final static int PLANAR_SQUARE = 4;
	final static int PLANAR_5 = 5;
	final static int PLANAR_HEX = 6;
	final static int RING_O_9 = 9;
	final static int SPHERE = 42;	// 4 in plane, 2 out of plane, 6 total
	
	public StickyNode (Pt3D initCoord, double radius, int valence) {
		super(initCoord,false);
		this.radius = radius;
		this.valence = valence;
		this.arpLocal = Env.arpConc.getValue();   // init local Arp2/3 field to the bulk concentration
		makeStickyPoints();
		updateStickyPointsInX();
	}

	public StickyNode (Pt3D initCoord, Pt3D initUVec, Pt3D initZVec, double radius, int valence) {
		super(initCoord,initUVec,initZVec,false);
		this.radius = radius;
		this.valence = valence;
		this.arpLocal = Env.arpConc.getValue();   // init local Arp2/3 field to the bulk concentration
		makeStickyPoints();
		updateStickyPointsInX();
	}

	public void makeStickyPoints() {
		double sepAng;
		double adjR = packRAdj*radius;
		switch(valence) {
		case 0:
			break;
		case 1: // makes dimers
			stickyPointsInx[0] = new Pt3D(adjR,0,0);
			break;
		case 2: // makes a linear chain
			stickyPointsInx[0] = new Pt3D(adjR,0,0);
			stickyPointsInx[1] = new Pt3D(-adjR,0,0);
			break;
		case 3: // planar triangular(?) packing
			sepAng = 120*Math.PI/180;
			stickyPointsInx[0] = new Pt3D(adjR,0,0);
			stickyPointsInx[1] = new Pt3D(adjR*Math.cos(sepAng),adjR*Math.sin(sepAng),0);
			stickyPointsInx[2] = new Pt3D(adjR*Math.cos(2*sepAng),adjR*Math.sin(2*sepAng),0);
			break;
		case 4: // planar square packing
			stickyPointsInx[0] = new Pt3D(adjR,0,0);
			stickyPointsInx[1] = new Pt3D(-adjR,0,0);
			stickyPointsInx[2] = new Pt3D(0,adjR,0);
			stickyPointsInx[3] = new Pt3D(0,-adjR,0);
			break;
		case 5: // planar pentagonal(?) packing 
			stickyPointsInx[0] = new Pt3D(adjR,0,0);
			stickyPointsInx[1] = new Pt3D(-adjR,0,0);
			stickyPointsInx[2] = new Pt3D(0,adjR,0);
			stickyPointsInx[3] = new Pt3D(0,-adjR,0);
			stickyPointsInx[4] = new Pt3D(0,0,adjR);
			break;
		case 6: // planar hexagonal packing
			sepAng = 60*Math.PI/180;
			stickyPointsInx[0] = new Pt3D(0,adjR,0);
			stickyPointsInx[1] = new Pt3D(adjR*Math.sin(sepAng),adjR*Math.cos(sepAng),0);
			stickyPointsInx[2] = new Pt3D(adjR*Math.sin(2*sepAng),adjR*Math.cos(2*sepAng),0);
			stickyPointsInx[3] = new Pt3D(0,-adjR,0);
			stickyPointsInx[4] = new Pt3D(adjR*Math.sin(4*sepAng),adjR*Math.cos(4*sepAng),0);
			stickyPointsInx[5] = new Pt3D(adjR*Math.sin(5*sepAng),adjR*Math.cos(5*sepAng),0);
			break;
		case 42: // 3D sphere packing
			stickyPointsInx[0] = new Pt3D(adjR,0,0);
			stickyPointsInx[1] = new Pt3D(-adjR,0,0);
			stickyPointsInx[2] = new Pt3D(0,adjR,0);
			stickyPointsInx[3] = new Pt3D(0,-adjR,0);
			stickyPointsInx[4] = new Pt3D(0,0,adjR);
			stickyPointsInx[5] = new Pt3D(0,0,-adjR);
			break;
		case 9: // planar hexagonal packing
			sepAng = 40*Math.PI/180;
			stickyPointsInx[0] = new Pt3D(0,adjR,0);
			stickyPointsInx[1] = new Pt3D(adjR*Math.sin(sepAng),adjR*Math.cos(sepAng),0);
			stickyPointsInx[2] = new Pt3D(adjR*Math.sin(2*sepAng),adjR*Math.cos(2*sepAng),0);
			stickyPointsInx[3] = new Pt3D(adjR*Math.sin(3*sepAng),adjR*Math.cos(3*sepAng),0);
			stickyPointsInx[4] = new Pt3D(adjR*Math.sin(4*sepAng),adjR*Math.cos(4*sepAng),0);
			stickyPointsInx[5] = new Pt3D(adjR*Math.sin(5*sepAng),adjR*Math.cos(5*sepAng),0);
			stickyPointsInx[6] = new Pt3D(adjR*Math.sin(6*sepAng),adjR*Math.cos(6*sepAng),0);
			stickyPointsInx[7] = new Pt3D(adjR*Math.sin(7*sepAng),adjR*Math.cos(7*sepAng),0);
			stickyPointsInx[8] = new Pt3D(adjR*Math.sin(8*sepAng),adjR*Math.cos(8*sepAng),0);
			break;
		}
		// initialize stickyPointsInX
		for (int i=0; i<valence;i++) {
			stickyPointsInX[i] = new Pt3D();
		}
		// initialize isBound array
		for (int i=0; i<valence;i++) {
			isBound[i] = false;
		}
	
	}
	
	static class MembraneNodeThreads extends ThreadSet {
		MembraneNodeThreads () {
			super (Env.numMembraneThreads, "Membrane Node Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.membraneMoveStart:
					if (nodeCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*nodeCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.membraneMoveStop:
					if (nodeCt == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.membraneMoveStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						theNodes[i].membraneNodesMove(); 
					}
					break;
			}
		}
	}
	
	public void calculateProperties () {
			double tstDiffFactor = Env.membraneNodeDragScale.getValue();
			double radiusM = getRadius()*1.0e-6;
			bTransGam.x = tstDiffFactor*6*Math.PI*Env.aeta.getValue()*radiusM;
			bTransGam.y = bTransGam.x;
			bTransGam.z = bTransGam.x;
			bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
			//System.out.println ("nodeTransDiff = " + bTransDiff.x);

			bRotGam.x = tstDiffFactor*8*Math.PI*Env.aeta.getValue()*(radiusM*radiusM*radiusM);	// drag for turning about x
			bRotGam.y = bRotGam.x;
			bRotGam.z = bRotGam.x;
			bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);		// Einstein's relation gamma=kT/D
			pushDragToSoa();
	}
	
	public void biochemStep () {
		super.biochemStep();
		if (fixedNode) { return; }  // not letting unmovable modes get Rho Hot
		// P1: a permanent NPF activator patch (Rac/Cdc42/PIP2 leading-edge zone). Stays hot for the whole
		// run — does NOT expire on rhoHotLifetime — so branching stays localized at the real membrane
		// surface. Barbed ends within branchZone of these nodes get end2NearArpFactor (via the membrane
		// collision wiring) and become branch-eligible; the node also seeds de-novo Arp2/3 filaments here.
		if (permanentHotRho) {
			iAmHotRho = true;
			deNovoNucleate();
			return;
		}
		if (Math.abs(getCoordX()) > .4*Env.membraneCellRadius.getValue()) { return; }

		// hot spot origination
		double getHotProb = 0;//2e-3*Env.biochemDeltaT.getValue();
		if (hotSpotCt < maxHotSpots && !iAmHotRho) { 
			if (currentScratch().rng.nextDouble() < getHotProb) {  // testing only...
				iAmHotRho = true;
				iAmHotSpotOriginator = true;
				myHotSpotOriginator = this;
				hotTime = Env.simulationTime;
				hotSpotCt++;
			}
		}
		
		// hot spot spread, must have a rho hot neighbor
		double hotSpotSpreadProb = 0;//2e-2*Env.biochemDeltaT.getValue();
		if (rhoHotNeighbor() && !iAmHotRho) { 
			if (currentScratch().rng.nextDouble() < hotSpotSpreadProb) {  // testing only...
				iAmHotRho = true;
				myHotSpotOriginator = getOriginatorFromNeighbor();
				hotTime = Env.simulationTime;
			}
		}
		
		// turning off rho when originator has gone dark
		if (iAmHotRho && !iAmHotSpotOriginator && !myHotSpotOriginator.iAmHotRho) { 
			iAmHotRho = false; 
			myHotSpotOriginator = null;
		}
		
		// turning off hot spots, fail-safe all hot zones will go off after lifetime
		if (iAmHotRho && Env.simulationTime-hotTime > Env.rhoHotLifetime.getValue()) { // turn off after certain time
			iAmHotRho = false; 
			if (iAmHotSpotOriginator) { 
				hotSpotCt--;
				iAmHotSpotOriginator = false;
			}
			myHotSpotOriginator = null;
		}  
		
		if (iAmHotRho) {
			deNovoNucleate();
		}
	}

	// De-novo Arp2/3 nucleation at an activated (hot-Rho/NPF) membrane node: seed a filament just
	// inside the cortex, growing along the node's inward normal. Shared by the transient hot-spot path
	// and the P1 permanent-patch path.
	public void deNovoNucleate () {
		// De-novo nucleation draws on this node's activated-Arp2/3 budget, exactly like branching: a patch
		// that has spent its activated pool nucleates more slowly (rate scaled by the local fraction), and
		// each new filament consumes a quantum. Self-governing, and the heat-map drain now reflects
		// nucleation as well as branching.
		double localArp = Env.arpLocalField.isActive() ? arpLocal : Env.arpConc.getValue();
		double nucFilProb = Env.nucRateNearArpFactors.getValue()*(localArp/Env.arpConc.getValue())*Env.biochemDeltaT.getValue();
		if (currentScratch().rng.nextDouble() < nucFilProb) {
			// Dendritic de-novo nucleation: the NPF-activated Arp2/3 nucleates a filament, caps its pointed
			// (minus) end, AND tethers that capped end to the membrane node — exactly as Arp2/3 holds a
			// daughter's pointed end on its mother. The barbed (plus) end grows INWARD into the cytoplasm,
			// so the filament pushes off the cortex rather than threading out through the gaps between nodes
			// (which is how the un-anchored, outward-growing seeds were escaping the sphere).
			// Use the GEOMETRIC inward radial (sphere center - node position), NOT the node's body-frame
			// zVec: membrane nodes rotate during the sim, so a node's zVec drifts off-radial and can even
			// flip inward -> then -zVec points OUT and the filament nucleates/grows out through the cortex.
			// The geometric radial is rotation-proof.
			Pt3D inward;
			if (sphericalGeometry) {
				inward = Pt3D.UnitVec(centerOfSphere, coordAsPt3D());  // from node toward center = inward
			} else {
				inward = zVecAsPt3D();
				inward.scale(-1.0);                                   // flat sheet: node normal is stable
			}
			// Place the seed just inside the inner steric face (nodeR+filTipR), so its pointed end is born
			// at the membrane surface (where it gets tethered), not in the shell outside the rendered cortex.
			double seedDepth = Env.membraneNodeRadius.getValue() + Env.filTipRadiusForCollisions.getValue() + 0.02;
			Pt3D nucPt = Pt3D.Add(coordAsPt3D(), seedDepth, inward);
			FilSegment dFil = FilSegment.makeArp23NucFilament(nucPt, inward);
			FilSegment.linkEnd1Node(dFil, this);             // hold the Arp2/3 (pointed) end at the activated node
			if (Env.arpLocalField.isActive()) {              // spend a quantum of activated Arp2/3 on this nucleation
				arpLocal -= Env.arpConsumePerBranch.getValue();
				if (arpLocal < 0) arpLocal = 0;
			}
		}
	}
	
	public boolean rhoHotNeighbor () {
		for (int i=0;i<valence;i++) {
			if (isBound[i]) {
				if (boundTo[i].iAmHotRho) { 
					return true; 
				}
			}
		}
		return false;
	}
	
	public StickyNode getOriginatorFromNeighbor () {
		for (int i=0;i<valence;i++) {
			if (isBound[i]) {
				if (boundTo[i].iAmHotRho) { 
					return boundTo[i]; 
				}
			}
		}
		return null;
	}
	
	public void moveThing () {
		if (!fixedNode) {
			if (sphericalGeometry) {
				// Hold the closed shape: radial restoring to membraneCellRadius. This used to be applied ONLY
				// during the (negative-time) pre-equilibration, so during the actual sim the sphere had outward
				// turgor (internalPressure) but nothing setting its radius -> it inflated without bound. Apply
				// it always so the cortex is held; pre-equilibration just uses different Brownian scaling.
				addSphericalConstraintForce();
				if (Env.simulationTime < 0) {            // pre-equilibration: settle node distribution
					randForces.scale(1e-6,randForces);		// some random translation to find minimum energy state
					randTorques.scale(1e-40,randTorques);	// no random rotation, essentially
				} else {
					randForces.scale(Env.membraneBrownianScale.getValue(),randForces);
					randTorques.scale(Env.membraneBrownianScale.getValue(),randTorques);
				}
				internalPressure();
			} else {
				randForces.scale(Env.membraneBrownianScale.getValue(),randForces);
				randTorques.scale(Env.membraneBrownianScale.getValue(),randTorques);
			}
			if (iAmConstricting && Env.simulationTime > Env.deltaT.getValue()*10) { fakeConstrictingRing(); }
			super.moveThing();
			updateStickyPointsInX();
		}
	}
	
	public void internalPressure () {
		Pt3D outVec = Pt3D.UnitVec(coordAsPt3D(),centerOfSphere);
		outVec.scale(Env.outwardCellForce.getValue());
		// Direct slot increment: this runs inside moveThing on the membraneMove
		// worker pass AFTER gatherThreadAccumulators(), so we cannot route this
		// add through taForce (it would not be gathered until the next pass).
		incForceSumSlot(outVec.x, outVec.y, outVec.z);
	}

	public void fakeConstrictingRing () {
		Pt3D inVec = Pt3D.UnitVec(centerOfSphere,coordAsPt3D());
		inVec.scale(1e-20);
		incForceSumSlot(inVec.x, inVec.y, inVec.z);
	}
	
	public void addSphericalConstraintForce () {
		Pt3D forceVec = Pt3D.Sub(coordAsPt3D(), centerOfSphere);  // radially outward
		forceVec.unitVec();	// make unit vec
		double radialDisp = Env.membraneCellRadius.getValue()-Pt3D.ptDist(coordAsPt3D(), centerOfSphere);
		double forceMag = 0.4*(1.0e-6*radialDisp/Env.collisionDeltaT.getValue())/(1/bTransGam.x);
		incForceSum(Pt3D.Scale(forceMag,forceVec));
	}
	
	
	public void updateStickyPointsInX () {
		for (int i=0;i<valence; i++) {
			stickyPointsInX[i].xToXPlusxOrigin(this,stickyPointsInx[i]);
		}
	}
	
	public boolean fullyBound () {
		return (boundCt == valence);
	}
	
	public boolean isLinkedTo (StickyNode other) {  // is this node directly NodeLink-bound to other? (for membrane face/triangle lookup)
		for (int k=0; k<maxStickies; k++) { if (boundTo[k] == other) { return true; } }
		return false;
	}

	public boolean canBind (int loc, StickyNode bNode) {
		if (boundCt == valence) { return false; }
		if (isBound[loc]) { return false; }		// can't bind if this location is already bound
		for (int i=0; i<maxStickies; i++) {
			if (boundTo[i] == bNode) { return false; } // can't bind if already bound to this node at some other point
		}
		return true;
	}
	
	public void bind (int loc, StickyNode bNode) {
		isBound[loc] = true;
		boundTo[loc] = bNode;
		boundCt++;
	}
	
	public void unBind (int loc) {
		isBound[loc] = false;
		boundTo[loc] = null;
		boundCt--;
	}
	
	public void getCurrentLinkLoc (Pt3D pt, int loc) {
		pt.copy(stickyPointsInX[loc]);
	}
	
	public void registerNodeLink (double loc, StickyNode node) {
		
	}
	
	public void setIJ (String ij) {  // for getting node linking right on pre-made structures... the i,j of this node in some array
		myIJ = ij;
	}
	
	public float getMembraneTransparency() {
		float trans = Env.membraneTransparency.getFloatValue();
		if (trans > 1) { trans = 1.0f; }
		if (trans < 0) { trans = 0.0f; }
		return trans;
	}
	
	
	public static synchronized void ckToLink (StickyNode iNode, StickyNode jNode) {
		if (iNode.fullyBound() || jNode.fullyBound()) { return; } // exit if one or both nodes already fully bound
		// this is inefficient... checking all available combinations of binding points between nodes to find only one closest possible
		double closestDist = 1e6;  	// will temp. hold closest distance between binding points
		int iNodePos = -1;			// identify iNode binding point
		int jNodePos = -1;			// identify jNode binding point
		double minBindDist = iNode.radius;
		for (int i=0;i<iNode.valence;i++) {
			if (!iNode.isBound[i]) {	// if available binding point not bound, check distance to any others possible on jNode
				for (int j=0;j<jNode.valence;j++) {
					if (!jNode.isBound[j]) {
						double ptDist = Pt3D.ptDist(iNode.stickyPointsInX[i], jNode.stickyPointsInX[j]);
						//talkln("i,j=" + i + "," + j + " and ptDist =" + ptDist);
						if (ptDist < minBindDist && ptDist < closestDist) {
							closestDist = ptDist;
							iNodePos = i;
							jNodePos = j;
						}
					}
				}
			}
		}
		
		if (iNodePos != -1) {	// in other words, if at least one available binding point is close enough
			NodeLink.makeNodeLink(iNode, iNodePos, jNode, jNodePos);
			//boolean sameNode = false;
			//if (iNode == jNode) { sameNode = true; } 
			//talkln ("Made a node link, sameNode = " + sameNode + ", now there are " + NodeLink.nodeLinkCt);
		}
	}
	
	
	public static void makeTestStickyNodes () {
		/*double rad1 = 0.02;
		double rad2 = 0.04;
		double rad3 = 0.03;
		Pt3D pt1 = new Pt3D(0,0,0);
		Pt3D pt2 = new Pt3D(rad1+rad2,0,0);
		Pt3D pt3 = new Pt3D(rad1+2*rad2+rad3,0,0);
		StickyNode node1 = new StickyNode(pt1,rad1,6);
		StickyNode node2 = new StickyNode(pt2,rad2,6);
		StickyNode node3 = new StickyNode(pt3,rad3,6);
		NodeLink.makeNodeLink(node1, 0, node2, 1);
		NodeLink.makeNodeLink(node2, 0, node3, 1);
		*/
		
		/*
		int numNodes = 1000;
		Pt3D nuCoord = new Pt3D();
		Pt3D lastCoord = new Pt3D();
		StickyNode curNode,lastNode;
		double curRad;
		double radMin = 0.01; double radMax = 0.1;
		double lastRad = radMin + Math.random()*(radMax-radMin);
		lastNode = new StickyNode(lastCoord,lastRad,2);
		for (int i=0;i<numNodes;i++) {
			curRad = radMin + Math.random()*(radMax-radMin);
			nuCoord.x = lastCoord.x + curRad + lastRad;
			lastCoord.copy(nuCoord);
			curNode = new StickyNode(nuCoord,curRad,2);
			NodeLink.makeNodeLink(lastNode, 0, curNode, 1);
			lastNode = curNode;
			lastRad = curRad;
		}
		*/
		makeIcosahedronOfNodes();
		//makeSheetSquarePackedNodes();
		//makeSheetHexPackedNodes();
		//makeSphereOfNodes();
		//makeCylinderOfNodes();
		//makeSphereLikeCylinderOfNodes();
		//makeTestCollisionNode();
		//makeTestCollisionFilament();
		//makeTestCollisionBranchedNetwork();
		//FillNode.makeExpandingInnerSphere();
		//FillNode.fillCellWithSpheres();
		//makeLooseStickies(100);
	
	}
	
	
	public static void makeTestCollisionFilament () {
		// test filament
		double filLength = 0.5;
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(0,0,0.6*filLength);
		Pt3D ang = new Pt3D(0,0,-1);
		FilSegment newSeg = new FilSegment (loc,ang,-1,monCt,false);
	}
	
	public static void makeTestCollisionBranchedNetwork() {
		FilSegment mFil;
		double filLength = 0.5;
		double bLoc;
		int monCt = (int)(filLength/Env.actinMonoRadius);
		Pt3D loc = new Pt3D(0,0,0.6*filLength);
		Pt3D ang = new Pt3D(0,0,-1);
		double xMin = -0.1; double xMax = 0.1;
		double yMin = -.02; double yMax = .02;
		double angWiggle = 1;
		int numMothers = 12;
		for (int j=0;j<numMothers;j++) {
			loc.x = Math.random()*(xMax-xMin) + xMin;
			loc.y = Math.random()*(yMax-yMin) + yMin;
			ang.x = (2*Math.random()-1)*angWiggle;
			ang.x = (2*Math.random()-1)*angWiggle;
			ang.y = (2*Math.random()-1)*angWiggle;
			ang.unitVec();
			mFil = new FilSegment (loc,ang,-1,monCt,false);
			for (int i=0;i<0;i++) {
				bLoc = Math.random()*filLength;
				mFil.makeArpBranch(bLoc);
			}   
		}
	}
	
	public static void makeTestCollisionNode () {
		double testNodeR = Env.membraneTstBallRadius.getValue();
		//StickyNode tstNode = new StickyNode(new Pt3D(0,0,1.1*testNodeR),testNodeR,0);
		ProteinNode tstNode = new ProteinNode(new Pt3D(0,0,1.5*testNodeR),false);
		tstNode.radius = testNodeR;
		tstNode.constantF = new Pt3D(0,0,-1e-11); // force in Newtons
	}
	
	public static void makeLooseStickies (int numStickies) {
		double sR = 0.05; // microns
		Pt3D coordPt;
		for (int i=0; i<numStickies; i++) {
			coordPt = theBox.rdmPtInside(sR);
			new StickyNode (coordPt, sR, 2);
		}
	}
	
	public static void makeIcosahedronOfNodes () {
		/*
		 * 	from math import sin,cos,acos,sqrt,pi
			s,c = 2/sqrt(5),1/sqrt(5)
			topPoints = [(0,0,1)] + [(s*cos(i*2*pi/5.), s*sin(i*2*pi/5.), c) for i in range(5)]
			bottomPoints = [(-x,y,-z) for (x,y,z) in topPoints]
			icoPoints = topPoints + bottomPoints
			icoTriangs = [(0,i+1,(i+1)%5+1) for i in range(5)] +\
             [(6,i+7,(i+1)%5+7) for i in range(5)] +\
             [(i+1,(i+1)%5+1,(7-i)%5+7) for i in range(5)] +\
             [(i+1,(7-i)%5+7,(8-i)%5+7) for i in range(5)]
		 */
		double nodeR = Env.membraneNodeRadius.getValue();
		double s = 2.0/Math.sqrt(5);
		double c = 1.0/Math.sqrt(5);
		Pt3D[] topPts = new Pt3D[6];
		Pt3D[] bottomPts = new Pt3D[6];
		Pt3D[] icoPts = new Pt3D[6];
		Pt3D curZVec;
		Pt3D origin = new Pt3D();
		Pt3D allUVec = new Pt3D(1,0,0);
		Pt3D topConstant = new Pt3D(0,0,1);
		Pt3D botScale = new Pt3D(-1,1,-1);
		for (int i=0;i<=5;i++) {
			topPts[i] = new Pt3D(s*Math.cos(i*2*Math.PI/5.0), s*Math.sin(i*2*Math.PI/5.0),c);
			topPts[i].add(topConstant);
			bottomPts[i] = Pt3D.Mult(botScale, topPts[i]);
			curZVec = Pt3D.UnitVec(topPts[i], origin);
			new StickyNode(topPts[i],allUVec,curZVec,nodeR,6);
			curZVec = Pt3D.UnitVec(bottomPts[i],origin);
			new StickyNode(bottomPts[i],allUVec,curZVec,nodeR,6);
		}
	}
	
	public static void makeSphereOfNodes () {
		sphericalGeometry = true;
		// used "How to generate equidistributed points on the surface of a sphere" by Markus Deserno as a reference for this code
		// was available at https://www.cmu.edu/biolphys/deserno/pdf/sphere_equi.pdf
		double sphereR = Env.membraneCellRadius.getValue();
		double nodeR = Env.membraneNodeRadius.getValue();
		double N = Env.membraneCellPackingFactor.getValue()*4*Math.PI*sphereR*sphereR/(Math.PI*nodeR*nodeR*packRAdj*packRAdj);
		double areaA = 4*Math.PI*sphereR*sphereR/N;
		double d = Math.sqrt(areaA);
		double mTheta = Math.round(Math.PI/d);
		double dTheta = Math.PI/mTheta;
		double dPhi = areaA/dTheta;
		
		Pt3D lastCoord = new Pt3D();
		double theta,phi;
		for (int m=0; m<mTheta; m++) {
			theta = Math.PI*(m+0.5)/mTheta;
			double mPhi = Math.round(2*Math.PI*Math.sin(theta)/dPhi);
			for (int n=0; n<=mPhi-1; n++) {
				phi = 2*Math.PI*n/mPhi;
				Pt3D nodeC = new Pt3D(sphereR*Math.cos(phi)*Math.sin(theta),sphereR*Math.sin(phi)*Math.sin(theta),sphereR*Math.cos(theta));
				Pt3D nodeUVec = Pt3D.UnitVec(nodeC, lastCoord);
				Pt3D nodeZVec = Pt3D.UnitVec(nodeC, centerOfSphere);
				lastCoord.copy(nodeC);
				new StickyNode (nodeC,nodeUVec,nodeZVec,nodeR,6);
			}
		}

	}

	// Designate a few hot-Rho (NPF) patches on a closed membrane: mark every node within angRadiusDeg of
	// each fixed patch direction as a permanent NPF source. Used by the spherical-membrane IC so the
	// activated-Arp2/3 field has localized sources to diffuse out from.
	public static void markHotPatches (int nPatches, double angRadiusDeg) {
		// Fixed, well-spread directions (deterministic): poles + a ring + tetra-ish points.
		double[][] dirs = {
			{0,0,1}, {0,0,-1}, {1,0,0}, {0,1,0}, {-1,0,0}, {0,-1,0},
			{0.577,0.577,0.577}, {-0.577,-0.577,0.577}
		};
		int patches = Math.max(1, Math.min(nPatches, dirs.length));
		double cosThresh = Math.cos(Math.toRadians(angRadiusDeg));
		int marked = 0;
		for (int i=0;i<ProteinNode.nodeCt;i++) {
			if (!(ProteinNode.theNodes[i] instanceof StickyNode)) continue;
			StickyNode s = (StickyNode)ProteinNode.theNodes[i];
			double nx=s.getCoordX(), ny=s.getCoordY(), nz=s.getCoordZ();
			double r=Math.sqrt(nx*nx+ny*ny+nz*nz);
			if (r < 1e-9) continue;
			nx/=r; ny/=r; nz/=r;
			for (int p=0;p<patches;p++) {
				if (nx*dirs[p][0] + ny*dirs[p][1] + nz*dirs[p][2] >= cosThresh) {
					s.iAmHotRho = true; s.permanentHotRho = true; s.myHotSpotOriginator = s; marked++;
					break;
				}
			}
		}
		System.out.println("[SPHERE] hot-Rho: " + patches + " patches (" + angRadiusDeg + " deg), " + marked + " nodes marked");
	}
	
	public static void makeCylinderOfNodes () {
		sphericalGeometry = true;
		double sheetNodeR = Env.membraneNodeRadius.getValue();
		int nodeCols = Env.membraneXNodeCt.getIntValue();
		int nodeRows = (int)(2*Math.PI*Env.membraneCellRadius.getValue()/(2*Env.membraneNodeRadius.getValue()*packRAdj));
		Pt3D curCoord = new Pt3D();
		double xStart = -nodeCols*sheetNodeR*packRAdj;
		double yStart = -nodeRows*sheetNodeR*packRAdj;
		StickyNode[][] sheetONodes = new StickyNode[nodeCols][nodeRows];
		double dTheta = 2*Math.PI/nodeRows;  // angle increment per node
		double theta = 0;
		Pt3D allUVec = new Pt3D(1,0,0);
		Pt3D curZVec = new Pt3D();
		Pt3D curXOrigin = new Pt3D();
		
		// make all the StickyNodes in the cylinder
		for (int i=0;i<nodeCols;i++) {  
			curCoord.x = xStart + i*2*sheetNodeR*packRAdj;
			curXOrigin.x = curCoord.x;
			for (int j=0;j<nodeRows;j++) {
				theta = j*dTheta;  
				if (i%2 == 0) { theta += dTheta/2; } 
				curCoord.y = Env.membraneCellRadius.getValue()*Math.cos(theta);
				curCoord.z = Env.membraneCellRadius.getValue()*Math.sin(theta);
				curZVec = Pt3D.UnitVec(curCoord, curXOrigin);
				sheetONodes[i][j] = new StickyNode(curCoord,allUVec,curZVec,sheetNodeR,6);
				String ij = String.valueOf(i) + "," + String.valueOf(j);
				sheetONodes[i][j].setIJ(ij);
				if (i==0 || i==nodeCols-1) { sheetONodes[i][j].fixedNode = true; }
			}
		}
	}
	
	public static void makeSphereLikeCylinderOfNodes () {
		sphericalGeometry = true;
		int nodeCt = 0;
		int fixedNodeCt = 0;
		double nodeR = Env.membraneNodeRadius.getValue();
		double rad = Env.membraneCellRadius.getValue();
		int nodeCols = Env.membraneXNodeCt.getIntValue();
		int nodeRows = (int)(2*Math.PI*rad/(2*nodeR*packRAdj));
		double arcLength = nodeCols*(2*nodeR*packRAdj);
		double initPhi = -arcLength/(2*rad); // + (nodeR*packRAdj)?
		double dPhi = arcLength/(rad*nodeCols);
		Pt3D curCoord = new Pt3D();
		StickyNode[][] sheetONodes = new StickyNode[nodeCols][nodeRows];
		double dTheta = 2*Math.PI/nodeRows;  // angle increment per node
		double theta = 0;
		
		double curR;
		Pt3D allUVec = new Pt3D(1,0,0);
		Pt3D curZVec = new Pt3D();
		Pt3D origin = new Pt3D();
		
		int constrictingRing = nodeCols/2;
		
		// make all the StickyNodes in the cylinder
		for (int i=0;i<nodeCols;i++) {  
			curCoord.x = rad*Math.sin(initPhi + i*dPhi);
			curR = Math.sqrt(rad*rad - curCoord.x*curCoord.x);
			for (int j=0;j<nodeRows;j++) {
				theta = j*dTheta;
				if (i%2 == 0) { theta += dTheta/2; } 
				curCoord.y = curR*Math.cos(theta);
				curCoord.z = curR*Math.sin(theta);
				curZVec = Pt3D.UnitVec(curCoord, origin);
				sheetONodes[i][j] = new StickyNode(curCoord,allUVec,curZVec,nodeR,6);
				nodeCt++;
				String ij = String.valueOf(i) + "," + String.valueOf(j);
				sheetONodes[i][j].setIJ(ij);
				if (Math.abs(curCoord.x) > .7*rad) { sheetONodes[i][j].fixedNode = true; fixedNodeCt++;}
				//if (i==0 || i==nodeCols-1) { sheetONodes[i][j].fixedNode = true; }
				//if (i==constrictingRing) { sheetONodes[i][j].iAmConstricting = true; }
			}
		}
		talkln ("Made a sphere-like cylinder with " + nodeCt + " nodes, of which " + fixedNodeCt + " are fixed.");
	}
	
	public static void makeSphereLikeCylinderOfNodes2 () {
		sphericalGeometry = true;
		int nodeCt = 0;
		int fixedNodeCt = 0;
		double nodeR = Env.membraneNodeRadius.getValue();
		double rad = Env.membraneCellRadius.getValue();
		int nodeCols = Env.membraneXNodeCt.getIntValue();
		int nodeRows = (int)(2*Math.PI*rad/(2*nodeR*packRAdj));
		double arcLength = nodeCols*(2*nodeR*packRAdj);
		double initPhi = -arcLength/(2*rad); // + (nodeR*packRAdj)?
		double dPhi = arcLength/(rad*nodeCols);
		Pt3D curCoord = new Pt3D();
		StickyNode[][] sheetONodes = new StickyNode[nodeCols][nodeRows];
		double dTheta = 2*Math.PI/nodeRows;  // angle increment per node
		double theta = 0;
		
		double curR;
		Pt3D allUVec = new Pt3D(1,0,0);
		Pt3D curZVec = new Pt3D();
		Pt3D origin = new Pt3D();
		
		int constrictingRing = nodeCols/2;
		
		// make all the StickyNodes in the cylinder
		for (int i=0;i<nodeCols;i++) {  
			curCoord.x = rad*Math.sin(initPhi + i*dPhi);
			curR = Math.sqrt(rad*rad - curCoord.x*curCoord.x);
			for (int j=0;j<nodeRows;j++) {
				theta = j*dTheta;
				if (i%2 == 0) { theta += dTheta/2; } 
				curCoord.y = curR*Math.cos(theta);
				curCoord.z = curR*Math.sin(theta);
				curZVec = Pt3D.UnitVec(curCoord, origin);
				sheetONodes[i][j] = new StickyNode(curCoord,allUVec,curZVec,nodeR,6);
				nodeCt++;
				String ij = String.valueOf(i) + "," + String.valueOf(j);
				sheetONodes[i][j].setIJ(ij);
				if (Math.abs(curCoord.x) > .7*rad) { sheetONodes[i][j].fixedNode = true; fixedNodeCt++;}
				//if (i==0 || i==nodeCols-1) { sheetONodes[i][j].fixedNode = true; }
				//if (i==constrictingRing) { sheetONodes[i][j].iAmConstricting = true; }
			}
		}
		talkln ("Made a sphere-like cylinder with " + nodeCt + " nodes, of which " + fixedNodeCt + " are fixed.");
	}
	
	public static void makeSheetHexPackedNodes () {
		sphericalGeometry = false;
		double sheetNodeR = Env.membraneNodeRadius.getValue();
		int nodeCols = Env.membraneXNodeCt.getIntValue();
		int nodeRows = Env.membraneYNodeCt.getIntValue();
		Pt3D curCoord = new Pt3D();
		double xStart = -nodeCols*sheetNodeR*packRAdj;
		double yStart = -nodeRows*sheetNodeR*packRAdj;
		StickyNode[][] sheetONodes = new StickyNode[nodeCols][nodeRows];
		
		// designate arp activators
		int arpIRange = 8;
		int arpActivatorImin = nodeCols/2 - arpIRange/2;
		int arpActivatorImax = arpActivatorImin + arpIRange;
		int arpJRange = 8;
		int arpActivatorJmin = nodeRows/2 - arpJRange/2;
		int arpActivatorJmax = arpActivatorJmin + arpJRange;
		
		// make all the StickyNodes in the sheet
		for (int i=0;i<nodeCols;i++) {  
			curCoord.x = xStart + i*2*sheetNodeR*packRAdj;
			for (int j=0;j<nodeRows;j++) {
				curCoord.y = yStart + j*2*sheetNodeR*packRAdj;
				if (i%2 == 0) { curCoord.y += sheetNodeR; } // 
				sheetONodes[i][j] = new StickyNode(curCoord,sheetNodeR,6);
				String ij = String.valueOf(i) + "," + String.valueOf(j);
				sheetONodes[i][j].setIJ(ij);
				if ((arpActivatorImin < i && i < arpActivatorImax) && (arpActivatorJmin < j && j < arpActivatorJmax)) {
					sheetONodes[i][j].iAmHotRho = true;
					sheetONodes[i][j].permanentHotRho = true;  // P1: stable NPF patch — does not expire on rhoHotLifetime
					sheetONodes[i][j].myHotSpotOriginator = sheetONodes[i][j];  // point to self, hack for this kind of hot spot creation
				}
				
				if (i==0 || i==nodeCols-1) { sheetONodes[i][j].fixedNode = true; }  // lock down x edges
				if (j==0 || j==nodeRows-1) { sheetONodes[i][j].fixedNode = true; }	// lock down y edges
			}
		}
		
		
		
		/*// link everything together manually
		for (int i=0;i<nodeCols;i++) {  
			for (int j=0;j<nodeRows;j++) {
				if (i%2 != 0) {
					if (j!=nodeCols-1) { NodeLink.makeNodeLink(sheetONodes[i][j], 0, sheetONodes[i][j+1], 3 ); }
					if (i!=0) {
						NodeLink.makeNodeLink(sheetONodes[i][j], 5, sheetONodes[i-1][j], 2);
						if (j!=0) { NodeLink.makeNodeLink(sheetONodes[i][j], 4, sheetONodes[i-1][j-1], 1); }
					}
					if (j!=0) { NodeLink.makeNodeLink(sheetONodes[i][j], 3, sheetONodes[i][j-1], 0); }
					if (i!=nodeCols-1) {
						if (j!=0) { NodeLink.makeNodeLink(sheetONodes[i][j], 2, sheetONodes[i+1][j-1], 5);}
						NodeLink.makeNodeLink(sheetONodes[i][j], 1, sheetONodes[i+1][j], 4);
					}
				} else {
					if (j!=nodeCols-1) { NodeLink.makeNodeLink(sheetONodes[i][j], 0, sheetONodes[i][j+1], 3 ); }
					if (j!=0) { NodeLink.makeNodeLink(sheetONodes[i][j], 3, sheetONodes[i][j-1], 0); }
				}
			}
		} */
	}
	
	
	public static void makeSheetSquarePackedNodes () {
		double sheetNodeR = Env.membraneNodeRadius.getValue();
		int nodeCols = Env.membraneXNodeCt.getIntValue();
		int nodeRows = Env.membraneYNodeCt.getIntValue();
		double xStart = -nodeCols*sheetNodeR*packRAdj;
		double yStart = -nodeRows*sheetNodeR*packRAdj;
		Pt3D curCoord = new Pt3D();
		StickyNode[][] sheetONodes = new StickyNode[nodeCols][nodeRows];
		
		// designate arp activators
		int arpIRange = 10;
		int arpActivatorImin = nodeCols/2 - arpIRange/2;
		int arpActivatorImax = arpActivatorImin + arpIRange;
		int arpJRange = 4;
		int arpActivatorJmin = nodeRows/2 - arpJRange/2;
		int arpActivatorJmax = arpActivatorJmin + arpJRange;
				
		// make all the StickyNodes in the sheet
		for (int i=0;i<nodeCols;i++) {  
			for (int j=0;j<nodeRows;j++) {
				curCoord.x = xStart + i*2*sheetNodeR*packRAdj;
				curCoord.y = yStart + j*2*sheetNodeR*packRAdj;
				sheetONodes[i][j] = new StickyNode(curCoord,sheetNodeR,4);
				if ((arpActivatorImin < i && i < arpActivatorImax) && (arpActivatorJmin < j && j < arpActivatorJmax)) { sheetONodes[i][j].iAmHotRho = true; }
			}
		}

		
		// link all the StickyNodes
		for (int i=0;i<nodeCols;i++) {  
			for (int j=0;j<nodeRows;j++) {
				if (i!=nodeCols-1) { NodeLink.makeNodeLink(sheetONodes[i][j], 0, sheetONodes[i+1][j], 1);} 		// forward x node
				if (i!=0) { NodeLink.makeNodeLink(sheetONodes[i][j], 1, sheetONodes[i-1][j], 0); }				// backward x node
				if (j!=nodeRows-1) { NodeLink.makeNodeLink(sheetONodes[i][j], 2, sheetONodes[i][j+1], 3); } 	// forward y node
				if (j!=0) { NodeLink.makeNodeLink(sheetONodes[i][j], 3, sheetONodes[i][j-1], 2); }			 	// backward y node	
				
				//if (j==nodeRows-1) { NodeLink.makeNodeLink(sheetONodes[i][j], 2, sheetONodes[i][0], 3); } 	// cylinder
			}
		}
	}
	
	public static void stickyBoundStats () {
		int stickyCt = 0;
		int stickyFullCt = 0;
		StickyNode curSticky;
		for (int i=0; i<ProteinNode.nodeCt; i++) {
			if (ProteinNode.theNodes[i] instanceof StickyNode) {
				curSticky = (StickyNode)ProteinNode.theNodes[i];
				if (curSticky.fullyBound()) { stickyFullCt++; }
				stickyCt++;
			}
		}
		//talkln("StickyStats: " + stickyFullCt + "/" + stickyCt + " fully bound");
	}

	// ACTIVATED Arp2/3 field — one explicit Jacobi step of the NPF-source / bulk-sink model:
	//   dc_i/dt = [hot-Rho ? ke*target : 0]  +  D * Σ_neighbours(c_j - c_i)  -  ke * c_i
	// Hot-Rho (NPF) nodes are the ONLY source of activated Arp2/3 (production toward `target`=arpConc);
	// it diffuses laterally as a slow membrane-bound species (D ~ membrane-protein scale, not the fast
	// free complex); and it is LOST everywhere at rate ke (escape to / deactivation in the inactive bulk,
	// modeled as a sink at 0 — activated complex that reaches the deep cytoplasm resumes fast 3-D
	// diffusion and disperses). Branching consumes it with NO return (incorporated/released Arp2/3
	// rejoins the inactive pool), so branching is ACTIVATION-RATE-LIMITED. Decay length ~ sqrt(D/ke).
	// Stable at the sim dt (D*valence*dt << 1); do NOT call at biochem cadence. Single-threaded.
	static double arpFieldHotMean = 0, arpFieldHotMin = 0;  // readout: activated conc over hot-Rho nodes
	public static void diffuseArpField () {
		if (!Env.arpLocalField.isActive()) return;
		double target = Env.arpConc.getValue();   // activated conc the NPF source drives toward (uM)
		double D  = Env.arpDiffusion.getValue();   // lateral (membrane-bound) diffusion
		double ke = Env.arpBulkExchange.getValue(); // loss/deactivation rate (escape to the bulk sink at 0)
		double dt = Env.deltaT.getValue();
		int n = ProteinNode.nodeCt;
		if (!arpFieldInit) {   // seed steady state (no consumption): hot=target, elsewhere 0 — avoids a slow startup ramp
			for (int i=0;i<n;i++) if (ProteinNode.theNodes[i] instanceof StickyNode) {
				StickyNode s = (StickyNode)ProteinNode.theNodes[i];
				s.arpLocal = s.iAmHotRho ? target : 0.0;
			}
			arpFieldInit = true;
		}
		for (int i=0;i<n;i++) {
			if (!(ProteinNode.theNodes[i] instanceof StickyNode)) continue;
			StickyNode s = (StickyNode)ProteinNode.theNodes[i];
			double lap = 0;
			for (int b=0;b<s.valence;b++) {
				if (s.isBound[b] && s.boundTo[b] != null) { lap += s.boundTo[b].arpLocal - s.arpLocal; }
			}
			double src = s.iAmHotRho ? ke*target : 0.0;        // production only at NPF (hot-Rho) nodes
			s.arpLocalNext = s.arpLocal + dt*(src + D*lap - ke*s.arpLocal);  // bulk sink at 0 => -ke*c loss
		}
		double sum=0, min=Double.MAX_VALUE; int hot=0;
		for (int i=0;i<n;i++) {
			if (!(ProteinNode.theNodes[i] instanceof StickyNode)) continue;
			StickyNode s = (StickyNode)ProteinNode.theNodes[i];
			s.arpLocal = s.arpLocalNext;
			if (s.iAmHotRho) { sum += s.arpLocal; if (s.arpLocal < min) min = s.arpLocal; hot++; }
		}
		arpFieldHotMean = hot>0 ? sum/hot : 0;
		arpFieldHotMin  = hot>0 ? min : 0;
	}
	

}