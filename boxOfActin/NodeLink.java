package boxOfActin;

/*
FilLink .... the links between filaments
*/


import java.awt.*;
import javax.swing.*;
import java.lang.Math.*;
import java.util.concurrent.ThreadLocalRandom;

import ec.util.MersenneTwisterFast;
import edu.cornell.lassp.houle.RngPack.RanMT;

public class NodeLink {
	static final int maxNodeLinks = 1000000;
	static NodeLink [] nodeLinks = new NodeLink[maxNodeLinks];
	static NodeLink [] nodeLinks_inactive = new NodeLink[maxNodeLinks];
	static int nodeLinkCt = 0;
	static int nodeLinkCt_inactive = 0;
	static int nodeLinkRenderCt = 0;
	static double maxStrain = 0;
	StickyNode node1,node2; 
	int loc1,loc2;
	Pt3D pt1 = new Pt3D();
	Pt3D pt2 = new Pt3D();
	boolean active = false;
	double linkLength;
	double restLength = 0;   // Tier-1 elastic mesh: rest length captured at creation (0 => legacy zero-rest contractile spring)
	int createdStep = -1;    // Env.counter when this link was (re)activated -- Tier-2 young-link split cooldown
	ValueTracker forceMag = new ValueTracker(Env.filLinkForcesToAve);
	ValueTracker torqueMag = new ValueTracker(Env.filLinkForcesToAve);
	ValueTracker strainTrack = new ValueTracker(Env.filLinkStrainToAve);
	double simTimeFormed;
	Pt3D linkVec = new Pt3D();
	Pt3D torsionVec = new Pt3D();
	Pt3D forceVec = new Pt3D();
	Pt3D R = new Pt3D();
	Pt3D RCrossF = new Pt3D();
	int filLinkNum;
	boolean removeMe = false;
	boolean orientSame = true;
	
	// multithreading
	static NodeLinkThreads nodeLinkThreads = new NodeLinkThreads();
	//MersenneTwisterFast myPRNG = new MersenneTwisterFast((long)(Long.MAX_VALUE*Math.random()));
	
	boolean farAway = false;
	static Pt3D farPt = new Pt3D();
	
	public NodeLink (StickyNode node1, int loc1, StickyNode node2, int loc2) {
		set(node1,loc1,node2,loc2);
		addNodeLink(this);
	}
	
	public class RetObj {
		// this is the object passed by from line-line and line-point intersect tests
		Pt3D conPt1, conPt2, ray1, ray2, ray3, ray4;
		double conDist = 0;
		double alpha, beta;	 // the coefficients of ray1 and ray2, respectively, to define contact pts from end1s
		boolean collision = false;
		
		public RetObj () {
			conPt1 = new Pt3D();
			conPt2 = new Pt3D();
			ray1 = new Pt3D();
			ray2 = new Pt3D();
			ray3 = new Pt3D();
			ray4 = new Pt3D();
		}
		
		public void reset () {
			collision = false;
		}
	}
	
	static class NodeLinkThreads extends ThreadSet {
		NodeLinkThreads () {
			super (Env.numMembraneLinkThreads, "NodeLink Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.membraneLinksStart:
					if (nodeLinkCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*nodeLinkCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.membraneLinksStop:
					if (nodeLinkCt == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.membraneLinksStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						if (nodeLinks[i] == null) { break; }		// protects from null pointer exception when we have no NodeLinks
						if (nodeLinks[i].active) { nodeLinks[i].enforceNodeLink(); }
					}
					break;
			}
		}
	}
	
	public void sepaku () {
		node1 = null;
		node2 = null;
		pt1 = null;
		pt2 = null;
		forceMag = null;
		torqueMag = null;
		strainTrack = null;
		linkVec = null;
		torsionVec = null;
	}
	
	synchronized static void makeNodeLink (StickyNode node1, int loc1, StickyNode node2, int loc2) {
		if (!node1.canBind(loc1,node2)) { return; }  // check if binding locations available, and also if these two nodes already bound to each other
		if (!node2.canBind(loc2,node1)) { return; }

		if (nodeLinkCt_inactive > 0) {
			NodeLink lnk = nodeLinks_inactive[nodeLinkCt_inactive-1];
			nodeLinkCt_inactive--;
			lnk.set(node1, loc1, node2, loc2);
		} else {
			new NodeLink(node1,loc1,node2,loc2);
		}
		
	}
	
	synchronized static void registerStrain (double strain) {
		if (strain > maxStrain) { maxStrain = strain; }
	}
	
	public void set (StickyNode node1, int loc1, StickyNode node2, int loc2) {
		this.node1 = node1;
		this.node2 = node2;
		this.loc1 = loc1;
		this.loc2 = loc2;
		updatePts();
		simTimeFormed = Env.simulationTime;
		
		// tell the nodes where and to whom they are bound now
		node1.bind(loc1, node2);
		node2.bind(loc2, node1);

		// Tier-1 elastic mesh: capture the rest length from the natural spacing at creation.
		// restFrac=0 keeps the legacy zero-rest contractile spring; >0 makes an elastic spring
		// with rest length = restFrac * (this link's length right now). Tier-2 node insertion sets
		// pendingRestOverride>=0 so a freshly-split link gets the mesh's NOMINAL rest (not the
		// stretched half-edge it was born on) -- so the new diamond pulls back to natural spacing.
		double restFrac = Env.membraneLinkRestFrac.getValue();
		if (pendingRestOverride >= 0) { restLength = pendingRestOverride; }
		else { restLength = (restFrac > 0) ? restFrac * linkLength : 0; }
		createdStep = Env.counter;

		active = true;
	}
	
	public void unSet () {
		node1.unBind(loc1);
		node2.unBind(loc2);
		active = false;
	}
	
	public void updatePts () {
		node1.getCurrentLinkLoc(pt1,loc1);
		node2.getCurrentLinkLoc(pt2,loc2);
		linkLength = Pt3D.ptDist(pt1, pt2);
		linkVec.sub(pt2,pt1);
		registerStrain(linkLength/node1.getRadius());
	}
	
	public void updateNodeLink () {
		// conditions for link dissolution
		if ((node1 == null) | (node1.removeMe)) { active = false; return; }
		if ((node2 == null) | (node2.removeMe)) { active = false; return; }
		
		
		updatePts();
	}
	
	public boolean ckLinkBreak() {
		double aveStrain = strainTrack.averageVal();
		
		double releaseProb = (Env.linkOffConst.getValue() + Env.linkOffCoeff.getValue()*Math.exp(aveStrain*Env.linkOffExp.getValue()))*Env.deltaT.getValue();
		if (ThreadLocalRandom.current().nextDouble() < releaseProb) {
			unSet();
			return true;
		}
		return false;
	}
	
	public void applyForces() {
		applyTransForce();
		//if (Env.filLinkTorqSpring.isActive()) { applyTorsionForce(); }
	}
	
	public void applyTransForce () {
		// strains... here we take care of BOTH stretch away from and collision with neighbors.
		// Tier-1: with a rest length, the spring is elastic (force ~ length-rest, resists stretch AND
		// compression -> stable lattice spacing). Legacy (restLength==0) is the zero-rest contractile
		// spring (force ~ full length, no in-plane ground state).
		double curStretchDist = linkLength - restLength;

		//forces and accompanying torques
		double fracMove = Env.membraneLinkFracMove.getValue();  // membrane in-plane stiffness; lower = more compliant/stretchy (was hardcoded 2.0)
		// Effective stiffness k = fracMove/(kDt*mobility). kDt = live deltaT (legacy, k~1/dt, a
		// per-step position correction) unless membraneFixedStiffnessDt>0, which pins k to a
		// dt-independent value (explicit penalty spring). See SUBCYCLING_GPU.md.
		double kDt = (Env.membraneFixedStiffnessDt.getValue() > 0) ? Env.membraneFixedStiffnessDt.getValue() : Env.deltaT.getValue();
		double curForceMag= (fracMove*1.0e-6*curStretchDist/kDt)/(1/node1.bTransGam.x+1/node2.bTransGam.x);
		// Tier-1 stabilizers:
		//  - centerAttach: apply the force at the node CENTER (no torque) instead of the off-center
		//    sticky point, killing the spin/jitter mode of the lightly rotationally-damped node.
		//  - avgVal: divide each node's incident link force by its active-link count -> averaged /
		//    under-relaxed Jacobi, which can't over-relax (the un-normalized valence-~6 sum did).
		boolean centerAttach = Env.membraneLinkCenterAttach.getValue() > 0.5;
		boolean avgVal = Env.membraneRelaxAvgValence.getValue() > 0.5;
		// linkVec points node1->node2; +mag*linkVec on node1 pulls it toward node2 when stretched
		// (curStretchDist>0) and pushes it away when compressed (elastic, curStretchDist<0).
		double s1 = (avgVal && node1.boundCt > 1) ? 1.0/node1.boundCt : 1.0;
		forceVec.scale(curForceMag*s1,linkVec);
		if (centerAttach) { node1.incForceSum(forceVec); } else { node1.incForceSum(forceVec,pt1); }

		double s2 = (avgVal && node2.boundCt > 1) ? 1.0/node2.boundCt : 1.0;
		forceVec.scale(-curForceMag*s2,linkVec);
		if (centerAttach) { node2.incForceSum(forceVec); } else { node2.incForceSum(forceVec,pt2); }
		//System.out.println("node link force is " + curForceMag);
	}

	// GPU-shaped membrane relaxation (Jacobi iterative projection). Self-contained replacement
	// for the ThreadSet while-loop in doLoop: each pass zeros the membrane nodes' force, sums
	// every active NodeLink's (fixed-stiffness) force at the current pose into its two endpoints
	// (Jacobi -- positions held fixed during the sum), then integrates every membrane node by dt.
	// Repeats up to maxMembranePasses or until maxStrain < membraneMaxLinkStrain -- the same
	// termination as the legacy loop. This is the exact structure a per-mesh GPU kernel with a
	// bounded internal pass-loop would take (resident node pose; Jacobi gather avoids the
	// shared-node write race; deterministic). Serial here on the main thread (tid==-1 so
	// incForceSum writes straight to soaForceSum); the kernel parallelizes links then nodes.
	static final java.util.LinkedHashSet<StickyNode> membraneNodes = new java.util.LinkedHashSet<>();

	static void subcycleRelaxAll () {
		membraneNodes.clear();
		for (int i=0;i<nodeLinkCt;i++) {
			NodeLink nl = nodeLinks[i];
			if (nl != null && nl.active) {
				if (!nl.node1.fixedNode) membraneNodes.add(nl.node1);
				if (!nl.node2.fixedNode) membraneNodes.add(nl.node2);
			}
		}
		if (membraneNodes.isEmpty()) return;
		int maxPasses = Env.maxMembranePasses.getIntValue();
		double tol = Env.membraneMaxLinkStrain.getValue();
		int pass = 0;
		maxStrain = 10;
		boolean yield = Env.membraneYield.getValue() > 0.5;
		if (yield) {
			// PROTRUSION path: integrate the membrane (link springs + sustained actin push + radial pin)
			// over one full dt as N small sub-steps of dt/N (the Arp2/3 sub-cycle pattern). The legacy
			// full-dt Jacobi RE-application overshot the stiff mesh each pass (nodes shooting around); small
			// sub-steps converge smoothly to the force-balanced bulge. Link force eval at dtFull (fixed
			// stiffness), integration at dtSub.
			int N = Math.max(1, Env.membraneYieldSubN.getIntValue());
			double dtFull = Env.deltaT.getValue();
			double dtSub  = dtFull / N;
			for (int s=0; s<N; s++) {
				for (StickyNode nd : membraneNodes) {   // reset to the sustained actin push (constant over the sub-cycle)
					int b = nd.myThingNumber*3;
					Thing.soaForceSum[b]=(float)nd.extMembFx; Thing.soaForceSum[b+1]=(float)nd.extMembFy; Thing.soaForceSum[b+2]=(float)nd.extMembFz;
					Thing.soaTorqueSum[b]=0; Thing.soaTorqueSum[b+1]=0; Thing.soaTorqueSum[b+2]=0;
				}
				Env.deltaT.setValue(dtFull);            // link spring eval (fixed-stiffness denominator)
				for (int i=0;i<nodeLinkCt;i++) {
					NodeLink nl = nodeLinks[i];
					if (nl == null || !nl.active) continue;
					if (nl.node1.fixedNode && nl.node2.fixedNode) continue;
					nl.updateNodeLink(); nl.applyForces();
				}
				// Vesicle: refresh the volume-pressure at the current pose; moveThing adds presF per node so
				// each sub-step integrates push (extMembF) + link tension + pressure together -> a real bleb.
				if (Env.membraneVesicle.getValue() > 0.5) StickyNode.computeVesiclePressure();
				Env.deltaT.setValue(dtSub);             // integrate the fine sub-step (radial pin added in moveThing)
				for (StickyNode nd : membraneNodes) { nd.moveThing(); }
			}
			Env.deltaT.setValue(dtFull);
			double maxExt=0; int pushedCt=0;
			for (StickyNode nd : membraneNodes) {
				double m=Math.sqrt(nd.extMembFx*nd.extMembFx+nd.extMembFy*nd.extMembFy+nd.extMembFz*nd.extMembFz);
				if (m>1e-30) pushedCt++;
				if (m>maxExt) maxExt=m;
				int b = nd.myThingNumber*3;
				Thing.soaForceSum[b]=0; Thing.soaForceSum[b+1]=0; Thing.soaForceSum[b+2]=0;
				Thing.soaTorqueSum[b]=0; Thing.soaTorqueSum[b+1]=0; Thing.soaTorqueSum[b+2]=0;
				nd.resetExtMembForce();   // consumed this step; re-accumulated next step's collision phase
			}
			StickyNode.lastMaxExtMembF = maxExt; StickyNode.lastPushedNodeCt = pushedCt;
			return;
		}
		while (maxStrain > tol && pass < maxPasses) {
			maxStrain = 0;   // re-registered by updateNodeLink() below
			// Jacobi step 1: zero the membrane nodes' force/torque accumulators
			for (StickyNode nd : membraneNodes) {
				int b = nd.myThingNumber*3;
				Thing.soaForceSum[b]=0; Thing.soaForceSum[b+1]=0; Thing.soaForceSum[b+2]=0;
				Thing.soaTorqueSum[b]=0; Thing.soaTorqueSum[b+1]=0; Thing.soaTorqueSum[b+2]=0;
			}
			// Jacobi step 2: sum every link's force at the (held-fixed) current pose
			for (int i=0;i<nodeLinkCt;i++) {
				NodeLink nl = nodeLinks[i];
				if (nl == null || !nl.active) continue;
				if (nl.node1.fixedNode && nl.node2.fixedNode) continue;
				nl.updateNodeLink();   // recompute pt1/pt2/linkLength/linkVec; registerStrain -> maxStrain
				nl.applyForces();      // accumulate fixed-stiffness force into both endpoints
			}
			// Vesicle: refresh the volume-pressure force at the current (mid-relaxation) pose so the
			// pressure balances the link tension as the bulge forms (moveThing adds presF per node).
			if (Env.membraneVesicle.getValue() > 0.5) StickyNode.computeVesiclePressure();
			// Jacobi step 3: integrate all membrane nodes by dt
			for (StickyNode nd : membraneNodes) { nd.moveThing(); }
			pass++;
		}
		// leave node forces zeroed-ish; the next loop step's resetCt wave clears them anyway
		for (StickyNode nd : membraneNodes) {
			int b = nd.myThingNumber*3;
			Thing.soaForceSum[b]=0; Thing.soaForceSum[b+1]=0; Thing.soaForceSum[b+2]=0;
			Thing.soaTorqueSum[b]=0; Thing.soaTorqueSum[b+1]=0; Thing.soaTorqueSum[b+2]=0;
		}
	}


	// ============================ Tier-2: membrane AREA GROWTH (node insertion) ============================
	// A growing bulge spreads a fixed node count apart -> coverage thins -> the cortex tears open and the
	// dendritic net excavates through. Fix: when a link over-stretches, EDGE-SPLIT it -- insert a node at
	// the midpoint and wire it to the two endpoints + the two shared triangle-apex neighbours (a 2->4
	// triangle split), with rest length = the mesh nominal. The dome gains nodes and stays closed.
	// Requires membraneLinkCenterAttach (links act center-to-center; inserted node needs no sticky-point
	// geometry). Runs single-threaded after the membrane relaxation, before cleanup.
	static double nominalRest = -1;          // cached mesh nominal rest length (restFrac * natural spacing)
	static double pendingRestOverride = -1;  // >=0 forces the next created link's restLength (set() reads it)
	static int insertedTotal = 0;            // running count of inserted nodes (diagnostic)

	static double computeNominalRest () {
		// Mean restLength over active links. restLength is fixed at creation, so stretched links don't
		// skew it; the original mesh + inserted links all sit at ~restFrac*naturalSpacing.
		double sum = 0; int n = 0;
		for (int i=0;i<nodeLinkCt;i++) {
			NodeLink nl = nodeLinks[i];
			if (nl != null && nl.active && nl.restLength > 0) { sum += nl.restLength; n++; }
		}
		return (n > 0) ? sum/n : 0;
	}

	static int countMovableMembraneNodes () {
		int c = 0;
		for (int i=0;i<ProteinNode.nodeCt;i++) {
			ProteinNode p = ProteinNode.theNodes[i];
			if (p instanceof StickyNode && !p.fixedNode) c++;
		}
		return c;
	}

	static final java.util.ArrayList<NodeLink> insertCand = new java.util.ArrayList<>();
	static final java.util.HashSet<StickyNode> touchedNodes = new java.util.HashSet<>();

	static void insertNodesForArea () {
		if (Env.membraneAreaGrow.getValue() < 0.5) return;
		if (Env.membraneLinkCenterAttach.getValue() < 0.5) return;   // center-to-center links required
		if (Env.membraneLinkRestFrac.getValue() <= 0) return;        // elastic mesh required (rest meaningful)
		int every = Math.max(1, Env.membraneInsertEveryNSteps.getIntValue());
		if (Env.counter % every != 0) return;                        // METERED: a trickle, not a per-step flood
		if (nominalRest < 0) nominalRest = computeNominalRest();
		if (nominalRest <= 0) return;
		// COVERAGE trigger: split a link only once it is an actual hole (absolute length), so a stretched-
		// but-covered bulge is left alone and insertion CONVERGES. Falls back to strain-relative if gap<=0.
		double gapAbs = Env.membraneInsertGapUm.getValue();
		double thresh = (gapAbs > 0) ? gapAbs : Env.membraneInsertStrain.getValue() * nominalRest;
		int perTick  = Env.membraneInsertPerStep.getIntValue();
		int maxNodes = Env.membraneMaxNodes.getIntValue();
		int cooldown = Env.membraneInsertCooldown.getIntValue();
		if (countMovableMembraneNodes() >= maxNodes) return;
		// Gather eligible "hole" links, then split the LONGEST first (fix the biggest holes), none sharing
		// a node within one tick (avoids tangling adjacent edges in a single pass).
		insertCand.clear();
		for (int i=0;i<nodeLinkCt;i++) {
			NodeLink nl = nodeLinks[i];
			if (nl == null || !nl.active) continue;
			if (nl.node1.fixedNode && nl.node2.fixedNode) continue;
			if (nl.createdStep >= 0 && Env.counter - nl.createdStep < cooldown) continue;  // young-link cooldown
			nl.updatePts();
			if (nl.linkLength > thresh) insertCand.add(nl);
		}
		if (insertCand.isEmpty()) return;
		insertCand.sort((a,b) -> Double.compare(b.linkLength, a.linkLength));
		touchedNodes.clear();
		int done = 0;
		for (NodeLink nl : insertCand) {
			if (done >= perTick) break;
			if (countMovableMembraneNodes() >= maxNodes) break;
			if (touchedNodes.contains(nl.node1) || touchedNodes.contains(nl.node2)) continue;
			if (splitEdge(nl)) {
				touchedNodes.add(nl.node1);
				touchedNodes.add(nl.node2);
				done++;
			}
		}
		insertedTotal += done;
	}

	// Edge-split link L=(A,B): insert midpoint node M, retriangulate A-M, B-M, C-M, D-M (C,D = the two
	// nodes linked to BOTH A and B). Returns true if a node was inserted.
	static boolean splitEdge (NodeLink L) {
		StickyNode A = L.node1, B = L.node2;
		if (A == null || B == null) return false;
		// shared triangle-apex neighbours (linked to both A and B)
		StickyNode C = null, D = null;
		for (int k=0;k<StickyNode.maxStickies;k++) {
			StickyNode X = A.boundTo[k];
			if (X == null || X == B) continue;
			if (B.isLinkedTo(X)) {
				if (C == null) C = X;
				else if (X != C) { D = X; break; }
			}
		}
		// midpoint node, oriented with zVec = outward radial (the emitted membrane normal)
		Pt3D mc   = Pt3D.Scale(0.5, Pt3D.Add(A.coordAsPt3D(), B.coordAsPt3D()));
		Pt3D zOut = Pt3D.Sub(mc, StickyNode.centerOfSphere);
		if (Pt3D.vecMag(zOut) < 1e-9) zOut = new Pt3D(0,0,1);
		Pt3D uTan = Pt3D.Sub(A.coordAsPt3D(), mc);
		if (Pt3D.vecMag(uTan) < 1e-9) uTan = new Pt3D(1,0,0);
		StickyNode M = new StickyNode(mc, uTan, zOut, A.getRadius(), 6);
		M.calculateProperties();   // drag with the correct (post-ctor) radius

		// drop the over-stretched edge (frees A.loc1 and B.loc2), then wire the diamond at nominal rest
		L.unSet();
		L.removeMe = true;
		pendingRestOverride = nominalRest;
		linkMtoNode(M, A);
		linkMtoNode(M, B);
		if (C != null) linkMtoNode(M, C);
		if (D != null) linkMtoNode(M, D);
		pendingRestOverride = -1;
		return true;
	}

	static void linkMtoNode (StickyNode M, StickyNode X) {
		int lm = M.freeSlot();
		int lx = X.freeSlot();
		if (lm < 0 || lx < 0) return;
		makeNodeLink(M, lm, X, lx);
	}

	public void enforceNodeLink () {
		if (node1.fixedNode && node2.fixedNode) { return; }  // don't waste computation if fixed nodes
		updateNodeLink();
		if (active) { 
			applyForces();
		}
	}
	
	synchronized static void addNodeLink (NodeLink addMe) {
		nodeLinks[nodeLinkCt] = addMe;
		addMe.filLinkNum = nodeLinkCt;
		nodeLinkCt++;
	}
	
	synchronized static void addInactive (NodeLink addMe) {
		nodeLinks_inactive[nodeLinkCt_inactive] = addMe;
		nodeLinkCt_inactive++;
	}
	
	synchronized static void removeNodeLink (NodeLink rmMe) {
		int swapId = rmMe.filLinkNum;				// here we swap from end of list to keep a compact array of FilLinks
		nodeLinks[swapId] = nodeLinks[nodeLinkCt-1];
		nodeLinks[swapId].filLinkNum = swapId;
		rmMe.sepaku();
		nodeLinkCt--;
	}
	
	synchronized static void removeDeadNodeLinks () {
		for (int i=0;i<nodeLinkCt;i++) {
			if (nodeLinks[i] == null) { break; }		// this means we've gotten to the end of our shortening list of things
			if (nodeLinks[i].removeMe) {
				removeNodeLink(nodeLinks[i]);
			}
		}
	}
	
	synchronized static void setInactiveNodeLinks () {
		nodeLinkCt_inactive = 0;
		for (int i=0;i<nodeLinkCt;i++) {
			try {
				if (!nodeLinks[i].active) {
				addInactive(nodeLinks[i]);
				}	
			}
			catch (NullPointerException npe)
			{}
		}
	}		
	
	public static void removeAll () {
		for (int i=0;i<nodeLinkCt;i++) {
			nodeLinks[i].unSet();
		}
		setInactiveNodeLinks();
		nodeLinkCt = 0;
		nodeLinkCt_inactive = 0;  // let's start with clean slate... been some linker weirdness after restart
	}
		
	public static void pointAndLineIntersectTest (Pt3D point, Pt3D ptA, Pt3D ptB, RetObj retO) {
		// Point and Line Segment Intersection test... 
		// see derivation of the following formulae in work book... uses dot product as zero to enforce
		// the perpendicularity and parameterization of line segment to check if the perpendicular drop
		// from sphere to line is on the line segment.
		// A line segment is {x1,y1,z1,x2,y2,z2}
		// A point is {x,y,z}
		retO.reset();
		
		retO.ray1.sub(ptB,ptA);
		retO.ray2.sub(point,ptA);
		double numer = Pt3D.Dot(retO.ray2,retO.ray1);
		double denom = Pt3D.vecMagSqrd(retO.ray1);
		double alpha = numer/denom;
		if ((alpha <= 1) & (alpha >= 0)) {	// the perpendicular projection is on the line segment...  then check distance
			retO.collision = true;
			retO.conPt1.add(ptA, alpha,retO.ray1);		// define perpendicular point
			retO.conDist = Pt3D.ptDist (retO.conPt1,point);
		} else {
			retO.alpha = alpha;
		}	
	}
	
	
	
}



