package boxOfActin;
/**
 * Bug.java... a rod shaped bacteria with cylindrical midsection and hemispherical caps
 *
 * @author Created by Omnicore CodeGuide
 */

import java.awt.*;
import java.io.File;

import javax.swing.*;
import java.lang.Math.*;
import java.text.DecimalFormat;
import java.util.Random;



public class Bug extends Crucible {
	static double length;			// length from end to end
	static double radius;			// radius of spherical caps and cylindrical section
	static double innerRadius;		// radius of inner bug used for collisions
	static double bigSphRadSqrd;	// used in faster collision elimination, square of a radius for big sphere around bug
	static double corticalDepth = 0.02;	// depth of collision space between inner and outer radius
	static double cellThickness = 0.00;		// thickness of the bacterial cell wall
	static double cylLength;
	static double halfCylLength;
	static double cylVolume;		// volume of the cylinder
	static double sphVolume;		// volume of the 2 hemispheres
	static double bugVolume;		// entire volume contained within the rod-shaped bug
	static double microMolarChangePerMonomer;	// how much to change concentration when one monomer is used or returned
	static Color bugColor = Color.white;
	static float initialTrans = 0.6f;	// initial transparency
	static float initialEmit = 0.1f;	// initial emmissive color
	
	// end1AsPt3D() (cap1 tip) / end2AsPt3D() (cap2 tip) live on Thing now;
	// bridgeDerivedToPt3D writes them after every GPU step.
	Pt3D rec1 = new Pt3D();						// location of cap1 center
	Pt3D rec2 = new Pt3D();						// location of cap2 center
	
	Pt3D pathCoord = new Pt3D();				// tracking displacement along path, whatever it is
	
	Pt3D colForceSum = new Pt3D();		// for debugging
	Pt3D linkForceSum = new Pt3D();
	static double maxMove = Env.actinMonoDiam;  // control motion from multiple tips/links
	Pt3D tstBVeloc = new Pt3D();
	Pt3D tstBMove = new Pt3D();
	
	static DecimalFormat timeFormat = new DecimalFormat ("0000.0000");
	static DecimalFormat concFormat = new DecimalFormat ("0.0000");
	
	static Random nodeGen = new Random((long)Math.random());
	
	// tracking forces and events
	static double pathCollisionForce = 0;
	static double pathTetherForce = 0;
	static double pathFrictionForce = 0;
	static int filamentCollisions = 0;
	static int filamentTethers = 0;
	static int tethersFormed = 0;
	static int tethersBroken = 0;
	static double tetherAgeSum = 0;
	static int nucleations = 0;
	// pressure on bug cylinder
	static double zNormalPlus = 0; 		// these track normal forces on cylinder to figure friction force
	static double zNormalMinus = 0;
	static double yNormalPlus = 0;
	static double yNormalMinus = 0;
	
	// ActA management
	HistogramPlus2 actAProbs, actALocs;
	
	// for graphics
	static boolean bugInScene = false;
	static boolean coordSysInScene = false;
	static boolean appearanceChanged = false;
	static boolean useWireAppearance = false;
	
	Pt3D testForce = new Pt3D(2e-11,0,0);	// constant force applied for testing
	
	// adjusting viscous drag
	static double bugDragScale = 1;
	static Pt3D bTransGam_base = new Pt3D();	// shape-based values adjusted later by fluid coupling approx. etc
	static Pt3D bRotGam_base = new Pt3D();
	static int tipsInShell = 0;
	Pt3D randForcesInX = new Pt3D();
	Pt3D randTorquesInX = new Pt3D();
	static ValueTracker bugDragAve = new ValueTracker(6);
	double normalForceSum = 0;
	Pt3D fricForceTmp = new Pt3D();  // used in calc. of friction force from normal force

	
	public Bug (Pt3D initCoord, double len, double rad) {
		super (initCoord);
		setUVec(0,1,0);	// point bug in positive y-direction
		setYVec(1,0,0);
		length = len;
		radius = rad;
		calculateProperties();
		initialize();
	}
	
	public void calculateProperties () {
		corticalDepth = 2.1*Env.nodeRadius.getValue();	// reset cortical depth to something slightly larger than node radius
		innerRadius = radius-corticalDepth;
		cylLength = length-2*radius;
		halfCylLength = cylLength/2.0;
		cylVolume = (length-2*radius)*(radius*radius)*Math.PI;
		sphVolume = 4*Math.PI*(radius*radius*radius)/3;
		bugVolume = cylVolume + sphVolume;  // volume in cubic microns
		microMolarChangePerMonomer = ((1e5*1e5*1e5)*1e6/(bugVolume*Env.AvogadroNum));		// (1e-6)^4 is for units.
		bigSphRadSqrd = 1.05*(length/2)*(length/2);	// used for eliminating possible collisions
		
		// viscous resistance definitions... for prolate ellipsoid of revolution ... see Berg p. 57 and 84
		// Diffusivity should be (m^2/s)
		double a = 1.0e-6*length/2;
		double b = 1.0e-6*radius;
		bTransGam.x = 4*Math.PI*Env.aeta.getValue()*a/(Math.log(2*a/b) - .5);
		bTransGam.y = 8*Math.PI*Env.aeta.getValue()*a/(Math.log(2*a/b) + .5);
		bTransGam.z = bTransGam.y;
		bRotGam.x = 16*Math.PI*Env.aeta.getValue()*a*b*b/3;
		bRotGam.y = (8*Math.PI*Env.aeta.getValue()*(a*a*a)/3)/(Math.log(2*a/b) - .5);
		bRotGam.z = bRotGam.y;
	
		bTransGam_base.copy(bTransGam);
		bRotGam_base.copy(bRotGam);
		
		// *************************
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
		
	}
	
	public void initialize () {
		// Canonical pose lives in SoA arrays; derived end1/end2/zVec/transXTox
		// computed by recomputeDerivedSoA from coord/uVec/yVec/length.
		pushLengthToSoa(length);
		Thing.recomputeDerivedSoA(myThingNumber, myThingNumber + 1);
		// Cap hemisphere centers, recomputed inline (no SoA equivalent yet).
		rec1.add(coordAsPt3D(), -(length/2 - radius), uVecAsPt3D());
		rec2.add(coordAsPt3D(), length/2 - radius, uVecAsPt3D());
	}
	
	public void step () {
		long _spT = StepProfiler.t0();
		setViscousDrag();
		StepProfiler.add(StepProfiler.FX_BUG_DRAG_TENSOR, _spT);
	}
	
	public void calcRandomForces (WorkerScratch ws) {  // override Thing.calRandomForces to share bug random forces/torques with small attached filaments
		super.calcRandomForces(ws);
		randForcesInX.xToX(this,randForces);
		randTorquesInX.xToX(this,randTorques);
	}
	
	public void addFrictionFromTips() {  // impending motion sans friction determines sign and scale of friction
		if (bForceSum.x < 0) { bFricForceSum.reverse(); bFricTorqueSum.reverse(); }  // sign:  assumed positive x motion in earlier friction signing
		double fricScale = 1;
		double bForceSx = Math.abs(bForceSum.x);
		double bFricSx = Math.abs(bFricForceSum.x);
		if (bFricSx > bForceSx) { fricScale = bForceSx/bFricSx; } // scale: friction can at most completely stop motion
		bFricForceSum.scale(fricScale);
		bFricTorqueSum.scale(fricScale);
		
		bForceSum.inc(bFricForceSum);
		bTorqueSum.inc(bFricTorqueSum);
		
		addPathFrictionForceOnBug(bFricForceSum.x);
		
	}
	
	public void moveThing () {
		// Given the forces/torques at this time point... move with explicit Euler approximation to ODE solution
		
		// first check that forceSum and torqueSum aren't wacky... exit method if they are
		if (!isForceSumFinite()) { return; }
		if (!isTorqueSumFinite()) { return; }

		// Work in coordinates aligned with the bug... transform forces and torques into body-fixed axis....
		int sBase = myThingNumber * 3;
		bForceSum.XToxFromFloats(this, Thing.soaForceSum, sBase);
		bTorqueSum.XToxFromFloats(this, Thing.soaTorqueSum, sBase);
		
		if (!Env.bugBrownianMotionOff) {
			// add random forces, given in body-fixed frame
			bForceSum.inc(randForces);
			bTorqueSum.inc(randTorques);
		}
		
		addFrictionFromTips ();
		// add constant force for testing ** should comment out!
		//bForceSum.inc(testForce);
		
		// scale motion is forces too large
		tstBVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
		tstBMove.scale(Env.deltaT.getValue(),tstBVeloc);
		double tstMove = Pt3D.vecMag(tstBMove);
		if (tstMove > maxMove) {
			double fScale = (maxMove/tstMove);
			bForceSum.scale(fScale);
			bTorqueSum.scale(fScale);
			talkln("** Scaling force/torque on bug by " + fScale);
		}
		
		// register total x-direction force
		Env.addPathForceWBrownianInx(bForceSum.x);
		
		// now that the forces and torques are in the body fixed frame, we apply the eoms....
		bVeloc.div(1.0e6, bForceSum, bTransGam);		// in micron/sec
		bAngVeloc.div(bTorqueSum, bRotGam);			// in radians/sec
	
		// ** before progressing .... check that bVeloc and bAngVeloc are not NaN... exit if wacky
		if (!bVeloc.checkPt3D()) { 
			talkln ("** problem with bVeloc for filament " + String.valueOf(this)); return; }
		if (!bAngVeloc.checkPt3D()) { talkln ("** problem with bAngVeloc for filament " + String.valueOf(this)); return; }
		
		// ....then transform back into fixed coordinate frame
		veloc.xToX(this, bVeloc);
		incCoord(Env.deltaT.getValue(), veloc);
		
		// to apply body-fixed angular velocities, build the new unit vectors
		// in a scratch Pt3D, transform body→fixed, normalise, then write back to SoA.
		Pt3D scratch = new Pt3D();
		double uVecTransInZ = -bAngVeloc.y * Env.deltaT.getValue();	// arclength out at 1 micron
		double uVecTransInY = bAngVeloc.z * Env.deltaT.getValue();
		scratch.setVals(1, uVecTransInY, uVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setUVec(scratch);

		double yVecTransInX = -uVecTransInY;
		double yVecTransInZ = bAngVeloc.x * Env.deltaT.getValue();
		scratch.setVals(yVecTransInX, 1, yVecTransInZ);
		scratch.xToX(this);
		scratch.unitVec();
		setYVec(scratch);

		// update path coordinate
		pathCoord.inc(Env.deltaT.getValue(), bVeloc);

		initialize();
	}
	
	public void resetCounters () {
		super.resetCounters();
		tipsInShell = 0;
	}
	
	public double getLength() {
		return length;
	}
	
	public double getRadius() {
		return radius;
	}
	
	public double getVolume () {  // spherical part + cylindrical part
		return (4/3)*Math.PI*(radius*radius*radius) + Math.PI*radius*radius*length;
	}
	
	public double getMonomerConc () {
		return Env.actinConc.getValue();
	}
	
	public double getNonHydroMonomerConc () {
		return Env.actinConcNonHydro.getValue();
	}
	
	public void takeMonomer (int numMon) {
		Env.actinConc.addToValue(-numMon*Env.actinConcX.getValue()*microMolarChangePerMonomer);
	}
	
	public void takeNonHydroMonomer (int numMon) {
		Env.actinConcNonHydro.addToValue(-numMon*Env.actinConcX.getValue()*microMolarChangePerMonomer);
	}
	
	public void putMonomer (int numMon) {
		Env.actinConc.addToValue(numMon*Env.actinConcX.getValue()*microMolarChangePerMonomer);
	}
	
	public void putNonHydroMonomer (int numMon) {
		Env.actinConcNonHydro.addToValue(numMon*Env.actinConcX.getValue()*microMolarChangePerMonomer);
	}
	
	public static void makeABugCrucible () {
		Bug nuBug = new Bug(new Pt3D(0,0,0), Env.bugLength.getValue(), Env.bugRadius.getValue());
		Thing.theBox = nuBug;
	}
	
	public static void makeListeriaBug() {
		Thing.lmBug = new Bug(new Pt3D(0,-5,0), Env.bugLength.getValue(), Env.bugRadius.getValue());
		Pt3D dispV = new Pt3D(halfCylLength*1.2, radius/2, radius/2);
		// (unused locals end1AsPt3D()/end2AsPt3D() removed during Pt3D field cleanup; the commented
		// `new ProteinNode(end1AsPt3D(),false)` lines were dead.)
		Pt3D webOrigin = Pt3D.Add(lmBug.end2AsPt3D(), 4*radius,lmBug.uVecAsPt3D());
		//FilSegment.makeWebOfActin(webOrigin,radius);
		Pt3D filCoord = Pt3D.Add(lmBug.end1AsPt3D(), -radius/2,lmBug.uVecAsPt3D());
		//FilSegment.makeTestBranchedFilament(filCoord);
		//FilSegment.firstActATest(filCoord, lmBug);
		Thing.lmBug.makeActAs();
		//Thing.lmBug.makeSimpleActADist();
	}
	
	public synchronized void addNormalForce (Pt3D normalForce, Pt3D forcePoint) {
		// normal force generates friction force
		double normalMag = Pt3D.vecMag(normalForce);
		Env.addNormForce(normalMag);
		normalForceSum += normalMag;
		double fricMag = -Env.bugFrictionCoeff.getValue()*normalMag;
		fricForceTmp.setVals(fricMag,0,0);  // friction force in bug's coordinate frame, assume friction in negative body-fixed x direction
		incFrictionSum(fricForceTmp,forcePoint); // send force in body-fixed frame, point in fixed frame.
		
	}
	
	public void reportTethersFormedAndBroken () {
		talkln ("Tethers formed: " + tethersFormed + " ; Tethers broken: " + tethersBroken + " with ave. age = " + String.format("%.4f",tetherAgeSum/tethersBroken) + " s AND nucleations: " + nucleations);
		tethersFormed = 0;
		tethersBroken = 0;
		tetherAgeSum = 0;
		nucleations = 0;
	}
	
	public static synchronized void addTetherFormed () {
		tethersFormed ++;
		Env.registerNewTether();
	}
	
	public static synchronized void addTetherBroken (double age) {
		tetherAgeSum += age;	// in seconds
		tethersBroken ++;
		Env.registerCutTether();
	}
	
	public void reportColAndTetherPathForces () {
		talkln("Path forces on bug: collision force from " + filamentCollisions+ " = " + pathCollisionForce + " N ; tether force from " + filamentTethers + " = " + pathTetherForce + " N ; friction force signed total = " + pathFrictionForce + " N");
		zeroColAndTetherForceSums();
	}
	
	public synchronized void addPathColForceOnBug (Pt3D forceVec) {
		double pCForce = Pt3D.Dot(forceVec,uVecAsPt3D());
		Env.addColForceInx(pCForce);
		pathCollisionForce += pCForce;
		filamentCollisions ++;
	}
	
	public synchronized void addPathTetherForceOnBug (Pt3D forceVec) {
		double pTForce = Pt3D.Dot(forceVec,uVecAsPt3D());
		Env.addLinkForce(pTForce);
		pathTetherForce += pTForce;
		filamentTethers ++;
	}
	
	public synchronized void addPathFrictionForceOnBug (double fMag) {
		Env.addFricForceInx(fMag);
		pathFrictionForce += fMag;
	}
	
	public static synchronized void addNucleation () {
		nucleations ++;
	}
	
	public void zeroColAndTetherForceSums () {
		pathCollisionForce = 0;
		pathTetherForce = 0;
		pathFrictionForce = 0;
		filamentCollisions = 0;
		filamentTethers = 0;
	}
	
	public Pt3D rdmPtInside () {
		Pt3D pt = new Pt3D();
		double fracVolInCyl = cylVolume/bugVolume;
		if (Env.mtRNG.nextDouble() < fracVolInCyl) {  //random point in cylinder
			double yC = Env.mtRNG.nextDouble()*2-1;
			double zC = Env.mtRNG.nextDouble()*2-1;
			while ((yC*yC + zC+zC > 1)) { zC = Env.mtRNG.nextDouble()*2-1; }
			pt.y = yC*radius;
			pt.z = zC*radius;
			pt.x = (Env.mtRNG.nextDouble()*2-1)*halfCylLength;
		} else {							// random point in one of the hemispheres
			pt = Pt3D.RandomUnitVec(myPRNG);
			pt.scale(radius*Env.mtRNG.nextDouble());
			if (pt.x > 0) {
				pt.x += halfCylLength;
			} else {
				pt.x += -1*halfCylLength;
			}
		}
		// F1b: pt is in body frame (long axis = body-x); rotate into global so the
		// placement matches the uVec the constructor set, then translate by bug center.
		pt.xToX(this);
		pt.inc(coordAsPt3D());
		return pt;
	}
	
	/*public Pt3D rdmPtInside (double objRadius) {  
		// Cube Reject Method
		// more uniform distribution **Sphere Only**... pick point in cube encompassing sphere, reject if actually outside sphere
		Pt3D pt = new Pt3D();
		Pt3D centerPt = new Pt3D(0,0,0);
		double radToPoint = 1e6; // start out with large value
		while (radToPoint > (radius-objRadius)) { 
			pt = new Pt3D((2*Math.random()-1)*(radius-objRadius),(2*Math.random()-1)*(radius-objRadius),(2*Math.random()-1)*(radius-objRadius));
			radToPoint = Pt3D.vecMag(Pt3D.Sub(pt, centerPt));
		}		
		return pt;
	}*/
	
	public Pt3D rdmPtInside (double objRadius) {
		Pt3D pt = new Pt3D();
		double fracVolInCyl = cylVolume/bugVolume;
		if (Env.mtRNG.nextDouble() < fracVolInCyl) {  //random point in cylinder
			double yC = Env.mtRNG.nextDouble()*2-1;
			double zC = Env.mtRNG.nextDouble()*2-1;
			while ((yC*yC + zC*zC > 1)) { zC = Env.mtRNG.nextDouble()*2-1; }
			pt.y = yC*(radius-objRadius);
			pt.z = zC*(radius-objRadius);

			pt.x = (Env.mtRNG.nextDouble()*2-1)*halfCylLength;
		} else {							// random point in one of the hemispheres
			pt = Pt3D.RandomUnitVec(myPRNG);
			pt.scale((radius-objRadius)*Env.mtRNG.nextDouble());
			if (pt.x > 0) {
				pt.x += halfCylLength;
			} else {
				pt.x += -1*halfCylLength;
			}
		}
		// F1b: pt is in body frame — rotate into global before translating.
		pt.xToX(this);
		pt.inc(coordAsPt3D());
		return pt;
	}
	
	
	public Pt3D rdmPtInsideNodeZone () {
		Pt3D pt = Pt3D.RandomUnitVec(myPRNG);
		//random point in cylinder
		pt.x = 0;
		pt.unitVec();
		pt.scale(Env.bugRadius.getValue()-2*Env.nodeRadius.getValue());
		pt.x = nodeGen.nextGaussian()*(Env.nodeZone.getValue()/2); // gaussian dist.
		//pt.x = 2*Env.mtRNG.nextDouble()*Env.nodeZone - Env.nodeZone;	// uniform dist.
		if (Math.abs(pt.x) > Env.nodeZone.getValue()) { pt.x *= Env.nodeZone.getValue()/Math.abs(pt.x); } // bring in outliers from gaussian
		// F1b: pt is in body frame — rotate into global before translating.
		pt.xToX(this);
		pt.inc(coordAsPt3D());
		return pt;
	}
	
	public Pt3D rdmPtOnRearHemisphere () {
		Pt3D ptOnRear = Pt3D.RandomUnitVec(myPRNG);
		ptOnRear.scale(radius);
		ptOnRear.x = -Math.abs(ptOnRear.x);
		ptOnRear.x += -halfCylLength;
		return ptOnRear;
	}
	
	public Pt3D rdmPtOnCylinder () { 
		Pt3D ptOnCyl = Pt3D.RandomUnitVec(myPRNG);
		double xVal = ptOnCyl.x;
		ptOnCyl.x = 0;
		ptOnCyl.unitVec();
		ptOnCyl.scale(radius); 
		ptOnCyl.x = -Math.abs(xVal*halfCylLength);
		return ptOnCyl;
	}
	
	public void amICollidingOuter (CollisionEvent lcE, Pt3D ctr, double R) {
		// F1b axis-convention fix (2026-06-03): the pill long axis lives along uVec
		// (set to (0,1,0) by the constructor — global +y), not global +x. Transform
		// the global-frame query point into body frame so the cylinder/cap geometry
		// math is consistent. The cylinder force is built in body frame and rotated
		// back to global at the end; cap forces use rec1/rec2 which are already in
		// global frame (placed along uVec by initialize()).
		lcE.zeroDelta();	// set cE.delta = 0.... no collision if unchanged below
		lcE.tmpPt1.sub(ctr, coordAsPt3D());
		lcE.tmpPt1.XTox(this);	// global -> body; now tmpPt1.x is along pill long axis
		if (Math.abs(lcE.tmpPt1.x) < halfCylLength) {	// check collisions in cylindrical section of bug
			lcE.forceUVec.copy(0, lcE.tmpPt1.y, lcE.tmpPt1.z);	// outward radial in body frame
			double yzDist = Pt3D.vecMag(lcE.forceUVec);
			if (yzDist+R < radius) {
				return;
			} else {
				lcE.delta = Math.abs(yzDist + R - radius);
				lcE.forceUVec.unitVec();
				lcE.forceUVec.reverse();  // inward (toward axis), still body frame
				lcE.forceUVec.xToX(this); // body -> global
				return;
			 }

		} else {		// check collisions in hemispherical caps
			if (lcE.tmpPt1.x > 0) {	// positive body-x cap (rec2)
				double topdist = Pt3D.ptDist (ctr, rec2);
				if (topdist + R < radius) {
					return;
				} else {
					lcE.delta = Math.abs(topdist + R - radius);
					lcE.forceUVec.sub(rec2, ctr); // inward: contact -> cap center, global frame
					lcE.forceUVec.unitVec();
					return;
				}
			}
			if (lcE.tmpPt1.x < 0) {  // negative body-x cap (rec1)
				double botdist = Pt3D.ptDist (ctr, rec1);
				if (botdist + R < radius) {
					return;
				} else {
					lcE.delta = Math.abs(botdist + R - radius);
					lcE.forceUVec.sub(rec1, ctr); // inward, global frame
					lcE.forceUVec.unitVec();
					return;
				}
			}

		}
	}
	
	public void amICollidingInner (CollisionEvent lcE, Pt3D ctr, double R) {
		// F1b axis-convention fix — mirror of amICollidingOuter; collision is against
		// the inner cortex shell (innerRadius). Force points OUTWARD (away from axis /
		// away from cap center) to push objects out of the cortex zone.
		lcE.zeroDelta();
		lcE.tmpPt1.sub(ctr, coordAsPt3D());
		lcE.tmpPt1.XTox(this); // global -> body
		if (Math.abs(lcE.tmpPt1.x) < halfCylLength) {	// cylindrical section
			lcE.forceUVec.copy(0, lcE.tmpPt1.y, lcE.tmpPt1.z); // outward radial in body
			double yzDist = Pt3D.vecMag(lcE.forceUVec);
			if (yzDist+R > innerRadius) {
				return;
			} else {
				lcE.delta = Math.abs(yzDist + R - innerRadius);
				lcE.forceUVec.unitVec();
				lcE.forceUVec.xToX(this); // body -> global; force points outward
				return;
			 }

		} else {		// hemispherical caps
			if (lcE.tmpPt1.x > 0) {	// positive body-x cap (rec2)
				double topdist = Pt3D.ptDist (ctr, rec2);
				if (topdist + R > innerRadius) {
					return;
				} else {
					lcE.delta = Math.abs(topdist + R - innerRadius);
					lcE.forceUVec.sub(ctr, rec2); // outward: cap center -> contact
					lcE.forceUVec.unitVec();
					return;
				}
			}
			if (lcE.tmpPt1.x < 0) {  // negative body-x cap (rec1)
				double botdist = Pt3D.ptDist (ctr, rec1);
				if (botdist + R > innerRadius) {
					return;
				} else {
					lcE.delta = Math.abs(botdist + R - innerRadius);
					lcE.forceUVec.sub(ctr, rec1); // outward
					lcE.forceUVec.unitVec();
					return;
				}
			}

		}
	}
	
	public void amICollidingFromOutside (CollisionEvent lcE, Pt3D ctr, double R) {
		// big difference from amICollidingInner/Outer: bug moves.  Probably should make those methods more general like this one
		// CollisionEvent leaves this method with:
		//		tmpPt1: vector from bug.coordAsPt3D() to contact point, in bugs coordinate frame (i.e. small x)
		//		tmpPt2: unused except in cylinder collision where we store normal force unit vec in x
		//		delta: impinge distance in microns
		//		forceUVec: outward normal unit vector, either normal to cylinder or hemispheres, depending on collision case
		lcE.bigDelta();	// set cE.delta to large number
		lcE.setCollision(false);; // set to no collision
		lcE.tmpPt1.sub(ctr, coordAsPt3D()); // vector to object point in X
		// fast check to eliminate distant objects: if distSqrd from object to bug coordAsPt3D() > a big sphere around bug then return
		if (Pt3D.vecMagSqrd(lcE.tmpPt1) > bigSphRadSqrd) { return; }
		lcE.tmpPt1.XTox(this);	// convert to bug's coordinate system x
		if (Math.abs(lcE.tmpPt1.x) < halfCylLength) {	// check collisions in cylindrical section of bug
			lcE.forceUVec.copy(0, lcE.tmpPt1.y, lcE.tmpPt1.z); // outward force vector for cyl. section only
			double yzDist = Pt3D.vecMag(lcE.forceUVec);	// magnitude of y-z vector out to contact point
			lcE.delta = yzDist-R-radius;
			possibleCloseTip(lcE.delta);
			if (lcE.delta > 0) {	// if vector mag. from cylinder midline to contact point, plus R (radius of node etc), greater than radius of cyl. then no contact
				return;
			} else {
				lcE.setCollision(true);
				lcE.type = CollisionEvent.CYLINDER;
				lcE.tmpPt2.copy(lcE.forceUVec); // store force direction in small x... for calculating pressure on bug
				lcE.forceUVec.xToX(this); // convert force direction to fixed frame X
				lcE.forceUVec.unitVec();  // make force direction a unit vector
				return;	
			 }
			
		} else {		// check collisions in hemispherical caps
			if (lcE.tmpPt1.x > 0) {	// positive x hemisphere
				lcE.forceUVec.sub(ctr,rec2);  // force direction is outward from center of hemisphere at end2AsPt3D()
				double topdist = Pt3D.vecMag(lcE.forceUVec);  // distance from center of hemisphere to object point
				lcE.delta = topdist-R-radius;
				if (lcE.delta > 0) {  // return if outside hemisphere
					return;
				} else {
					lcE.setCollision(true);
					lcE.type = CollisionEvent.END2;
					lcE.forceUVec.unitVec();
					return;
				}
			}
			if (lcE.tmpPt1.x < 0) {  // negative x hemisphere
				lcE.forceUVec.sub(ctr,rec1);  // force direction is outward from center of hemisphere at end1AsPt3D()
				double botdist = Pt3D.vecMag(lcE.forceUVec);  // distance from center of hemisphere to object point
				lcE.delta = botdist-R-radius;
				if (lcE.delta > 0) {
					return;
				} else {
					lcE.setCollision(true);
					lcE.type = CollisionEvent.END1;
					lcE.forceUVec.unitVec();
					return;
				}
			}
		
		}  
	}

	
	public void possibleCloseTip (double dist) {	// a single filament can count twice here if both end1AsPt3D() and end2AsPt3D() are close
		if (dist < Env.viscShellDist) { tipsInShell ++; }
	}
	
	public void setViscousDrag () {
		if (!Env.linearDragSlope.isActive() && !Env.quadraticDragSlope.isActive()) { return; }  // only adjust drag if feature is on
		bugDragScale=1;
		if (Env.linearDragSlope.isActive()) {
			bugDragScale = Env.bugBaseDragScale.getValue() + Env.linearDragSlope.getValue()*tipsInShell;
		} else {
			bugDragScale = Env.bugBaseDragScale.getValue() + Env.quadraticDragSlope.getValue()*tipsInShell*tipsInShell;
		}
		if (bugDragScale > Env.maxBugDragScale.getValue()) { bugDragScale = Env.maxBugDragScale.getValue(); }
		bugDragAve.registerValue(bugDragScale);
		//System.out.println ("Setting drag on bug: tipsInShell = " + tipsInShell + " and drag increase is " + dragFactor);
		double dScale = bugDragScale;
		if (Env.useAveBugDragScale.isActive()) {
			dScale = bugDragAve.averageVal();
		}
		bTransGam.scale(dScale,bTransGam_base);
		bRotGam.scale(dScale,bRotGam_base);
		bTransDiff.div(Env.Boltz*Env.tempK, bTransGam);	// Einstein's relation D=kT/gamma
		bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);
	}
	
	
	public void makeActAs () {
		if (!Env.playingWithSymBead) {
			mkActAHistogram();	// histogram of ActA locations
			for (int i=0; i < Env.totalActACt.getIntValue(); i++) { 
				createRdmActiveActAFromHist();
			}
		} else {
			mkSymActAOnBead();
		}
		
	}
	
	public void mkSymActAOnBead() {
		Pt3D rdmOnSphere = new Pt3D();
		for (int i=0; i < Env.totalActACt.getIntValue(); i++) { 
			rdmOnSphere.randomUnitVec(myPRNG);
			rdmOnSphere.scale(radius);
			new ActA(rdmOnSphere,this);
		}
	}
	
	private ActA createRdmActiveActAFromHist () {
		Random actARnd = new Random();	// set seed to 1825 make identical distributions
		double theta=0, psi;
		double xRad=0, yRad, zRad;
		double lnRdm;
		// generate random location along length... check histogram if OK
		boolean seekingSlot = true;
		while (seekingSlot) {
			//lnRdm = Rdm.uRdm(this);
			lnRdm = actARnd.nextDouble();
			xRad = lnRdm*length - radius; // zero is at rec1 (center of cap at end1AsPt3D())
			if (actALocs.decValue(xRad)) { seekingSlot = false; }  // found a valid location for a new ActA
		}
		if ((xRad < 0) | (xRad > cylLength)) {	// on hemispherical caps
			if (xRad < 0) { theta = Math.acos(-xRad/radius); }
			if (xRad > cylLength) { theta = Math.acos(-(xRad-cylLength)/radius); }
		} else {		// on cylinder
			theta = Math.PI/2.0;
		}
		psi = 2*Math.PI*actARnd.nextDouble();
		yRad = radius*Math.sin(theta)*Math.cos(psi);
		zRad = radius*Math.sin(theta)*Math.sin(psi);
		Pt3D locOnLis = new Pt3D(xRad,yRad,zRad);
		locOnLis.x += -halfCylLength;  // conversion to make this code (from RocketBugs) match my zero at center of bug
		ActA newActA = new ActA (locOnLis,this);
		return newActA;
	}
	
	private void mkActAHistogram () {
		double [] SRMeasure,SRProb;
		if (Env.ultrapolarActA.isActive()) {
			System.out.print ("Using ultrapolar ActA distribution...");
			SRMeasure = Env.upActAMeasure;
			SRProb = Env.upActAProb;
		} else {
			SRMeasure = Env.nmActAMeasure;
			SRProb = Env.nmActAProb;
			System.out.print ("Using normal ActA distribution...");
		}
		
		actAProbs = new HistogramPlus2 (SRProb.length,-radius,length-radius,null,"ActAProbs",null);	// the normalized probability distribution ....check dimensions of histogram... changes with diff. exp. measurements
		actALocs = new HistogramPlus2 (SRMeasure.length,-radius,length-radius,null,"ActALocs",null);	// the discrete location of ActA nucleators
		// load up histogram with ActAs based on Susanne Rafelski measurements (SRMeasure)
		for (int i=0;i<SRMeasure.length;i++) {
			actAProbs.addValue(i,SRProb[i]);
			int curBinSum = (int) (Math.floor(SRMeasure[i]*Env.totalActACt.getIntValue()));
			actALocs.addValue(i,curBinSum);
		}

		// check ptCt versus desired number of ActAs
		if (actALocs.ptCt < Env.totalActACt.getIntValue()) { 
			int numToAdd = Env.totalActACt.getIntValue() - actALocs.ptCt;
			for (int j=0;j<numToAdd;j++) { actALocs.addRandom(); }
		}
		if (actALocs.ptCt > Env.totalActACt.getIntValue()) {
			int numToDec = actALocs.ptCt - Env.totalActACt.getIntValue();
			for (int j=0;j<numToDec;j++) { actALocs.decRandom(); }
		}
			
		talkln ("placed " + String.valueOf(actALocs.ptCt) + " ActAs in histogram.");
	}
	
	
	public String getJSonString () {
		/* Format for LM JSON Serialization for Simularium
          1000.0,// visualization type : default
          0.0,   // agent instance ID
          0,   	 // agent type ID
          getCoordX(),  // position X
          getCoordY(),  // position Y
          getCoordZ(),  // position Z  
          angle.x,  // rotation X
          angle.y,  // rotation Y
          angle.z,  // rotation Z
          capRad,   // radius
          0.0,   // number of subpoint values following this number
		 */
		String coordXStr = String.format("%.2f",Env.simJSonsScale*getCoordX());
		String coordYStr = String.format("%.2f",Env.simJSonsScale*getCoordY());
		String coordZStr = String.format("%.2f",Env.simJSonsScale*getCoordZ());
		// end1AsPt3D()
		String rec1XStr = String.format("%.2f",Env.simJSonsScale*rec1.x);
		String rec1YStr = String.format("%.2f",Env.simJSonsScale*rec1.y);
		String rec1ZStr = String.format("%.2f",Env.simJSonsScale*rec1.z);
		// end2AsPt3D()
		String rec2XStr = String.format("%.2f",Env.simJSonsScale*rec2.x);
		String rec2YStr = String.format("%.2f",Env.simJSonsScale*rec2.y);
		String rec2ZStr = String.format("%.2f",Env.simJSonsScale*rec2.z);
		
		if (Env.lmAsFiberForJSon) {
			String lmString = "1001,"+myThingNumber+",0,";
			lmString += "0.0,0.0,0.0,";//coordXStr + "," + coordYStr + "," + coordZStr + ",";
			lmString += "0.0,0.0,0.0,";
			lmString += String.format("%.2f",Env.simJSonsScale*2*radius);
			lmString += ",6.0,";
			lmString += rec1XStr+","+rec1YStr+","+rec1ZStr+",";
			lmString += rec2XStr+","+rec2YStr+","+rec2ZStr;
			return lmString;
		} else { // snowman listeria... three spheres
			String lmString = "1000,"+(int)(myThingNumber+1000)+",0,";
			lmString += rec1XStr + "," + rec1YStr + "," + rec1ZStr + ",";
			lmString += "0.0,0.0,0.0,"; //String.format("%.2f",angle.x) + "," + String.format("%.2f",angle.y) + "," + String.format("%.2f",angle.z) + ",";
			lmString += String.format("%.2f",Env.simJSonsScale*2*radius);
			lmString += ",0.0,";
			
			lmString += "1000,"+(int)(myThingNumber*1001)+",0,";
			lmString += coordXStr + "," + coordYStr + "," + coordZStr + ",";
			lmString += "0.0,0.0,0.0,"; //String.format("%.2f",angle.x) + "," + String.format("%.2f",angle.y) + "," + String.format("%.2f",angle.z) + ",";
			lmString += String.format("%.2f",Env.simJSonsScale*2*radius);
			lmString += ",0.0,";
			
			lmString += "1000,"+(int)(myThingNumber*1002)+",0,";
			lmString += rec2XStr + "," + rec2YStr + "," + rec2ZStr + ",";
			lmString += "0.0,0.0,0.0,"; //String.format("%.2f",angle.x) + "," + String.format("%.2f",angle.y) + "," + String.format("%.2f",angle.z) + ",";
			lmString += String.format("%.2f",Env.simJSonsScale*2*radius);
			lmString += ",0.0";
			
			
			return lmString;
		}
	}
}
