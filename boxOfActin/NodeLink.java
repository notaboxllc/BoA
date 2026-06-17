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
		// strains... here we take care of BOTH stretch away from and collision with neighbors
		double curStretchDist = linkLength;
		
		//forces and accompanying torques
		double fracMove = Env.membraneLinkFracMove.getValue();  // membrane in-plane stiffness; lower = more compliant/stretchy (was hardcoded 2.0)
		// Effective stiffness k = fracMove/(kDt*mobility). kDt = live deltaT (legacy, k~1/dt, a
		// per-step position correction) unless membraneFixedStiffnessDt>0, which pins k to a
		// dt-independent value (explicit penalty spring). See SUBCYCLING_GPU.md.
		double kDt = (Env.membraneFixedStiffnessDt.getValue() > 0) ? Env.membraneFixedStiffnessDt.getValue() : Env.deltaT.getValue();
		double curForceMag= (fracMove*1.0e-6*curStretchDist/kDt)/(1/node1.bTransGam.x+1/node2.bTransGam.x);
		forceVec.scale(curForceMag,linkVec);
		node1.incForceSum(forceVec,pt1);

		forceVec.reverse();
		node2.incForceSum(forceVec,pt2);
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
				Env.deltaT.setValue(dtSub);             // integrate the fine sub-step (radial pin added in moveThing)
				for (StickyNode nd : membraneNodes) { nd.moveThing(); }
			}
			Env.deltaT.setValue(dtFull);
			for (StickyNode nd : membraneNodes) {
				int b = nd.myThingNumber*3;
				Thing.soaForceSum[b]=0; Thing.soaForceSum[b+1]=0; Thing.soaForceSum[b+2]=0;
				Thing.soaTorqueSum[b]=0; Thing.soaTorqueSum[b+1]=0; Thing.soaTorqueSum[b+2]=0;
				nd.resetExtMembForce();   // consumed this step; re-accumulated next step's collision phase
			}
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



