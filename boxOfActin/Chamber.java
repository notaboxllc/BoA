package boxOfActin;
/**
 * Bug.java... a rod shaped bacteria with cylindrical midsection and hemispherical caps
 *
 * @author Created by Omnicore CodeGuide
 */

import java.awt.*;

import javax.swing.*;
import java.lang.Math.*;
import java.text.DecimalFormat;
import java.util.Random;


public class Chamber extends Crucible {
	static double dimX;			// one dimension of planar box
	static double dimY;			// second dimension of planar box
	static double dimZ; 		// depth of box
	static Pt3D dims;		// dims of box (this dim in both +/- directions)
	static Color bugColor = Color.white;
	static float initialTrans = 0.8f;	// initial transparency
	static float initialEmit = 0.1f;	// initial emmissive color
	
	static DecimalFormat timeFormat = new DecimalFormat ("0000.0000");
	static DecimalFormat concFormat = new DecimalFormat ("0.0000");
	
	
	// for graphics
	static float shiny = 128.0f;
	static boolean bugInScene = false;
	static boolean coordSysInScene = false;
	static boolean appearanceChanged = false;
	static boolean useWireAppearance = false;
	
	
	
	// Pt3Ds reused in collision calculations
	//Pt3D forceVec = new Pt3D();
	//Pt3D hemDist = new Pt3D();
	//Pt3D toVec = new Pt3D();
	//Pt3D tmp1 = new Pt3D();
	//Pt3D tmp2 = new Pt3D();

	public Chamber (Pt3D initCoord, double xDim, double yDim, double zDim) {
		super (initCoord);
		dimX = xDim;
		dimY = yDim;
		dimZ = zDim;
		dims = new Pt3D(dimX,dimY,dimZ);	// dimensions of the box
		boxVolume = 8*dimX*dimY*dimZ;  // volume in cubic microns.  Note 2*dimX*2*dimY*2*dimZ
		microMolarChangePerMonomer = ((1e5*1e5*1e5)*1e6/(boxVolume*Env.AvogadroNum));		// (1e-6)^4 is for units.
	
		makeMyosinHeads();
		makeMyosinDimers();
	}
	
	
	
	public double getXDim() {
		return dimX;
	}
	
	public double getYDim() {
		return dimY;
	}
	
	public double getZDim() {
		return dimZ;
	}
	
	public double getVolume () {
		return dimX*dimY*dimZ;
	}
	
	public void makeMyosinHeads () {
		for (int i=0;i<Env.numChamberFixedMyos.getIntValue();i++) {
			myoPtsInX[i] = new Pt3D();
			myoPtsInX[i].x = (2*Env.mtRNG.nextDouble()-1)*0.5*Env.boxXDim.getValue();
			myoPtsInX[i].y = (2*Env.mtRNG.nextDouble()-1)*0.5*Env.boxYDim.getValue();
			myoPtsInX[i].z = -0.5*Env.boxZDim.getValue();
			
			myosins[i] = new Myosin(myoPtsInX[i]);
			//myosins[i].rodInvisible = true;
		}
	}
	
	public void makeMyosinDimers () {
		for (int i=0;i<Env.numChamberFixedMyoDimers.getIntValue();i++) {
			myoDimerPtsInX[i] = new Pt3D();
			myoDimerPtsInX[i].x = (2*myPRNG.nextDouble()-1)*0.5*Env.boxXDim.getValue();
			myoDimerPtsInX[i].y = (2*myPRNG.nextDouble()-1)*0.5*Env.boxYDim.getValue();
			myoDimerPtsInX[i].z = -0.5*Env.boxZDim.getValue();
		
			myodimers[i] = new MyosinDimer(myoDimerPtsInX[i]);
			//myosins[i].rodInvisible = true;
		}
	}
	
	
	
	public static void makeABox () {
		Thing.theBox = new Chamber(new Pt3D(0,0,0), Env.boxXDim.getValue(), Env.boxYDim.getValue(),Env.boxZDim.getValue());
	}
	
	public Pt3D rdmPtInside () {
		Pt3D pt = new Pt3D((2*Math.random()-1)*dims.x/2,(2*Math.random()-1)*dims.y/2,(2*Math.random()-1)*dims.z/2);
		return pt;
	}
	
	public Pt3D rdmPtInside (double objRadius) {
		Pt3D pt = new Pt3D((2*Math.random()-1)*dims.x/2,(2*Math.random()-1)*dims.y/2,(2*Math.random()-1)*dims.z/2);
		if (Math.abs(pt.x)>dims.x/2-objRadius) { pt.x*=(dims.x/2-objRadius)/(Math.abs(pt.x)); }
		if (Math.abs(pt.y)>dims.y/2-objRadius) { pt.y*=(dims.y/2-objRadius)/(Math.abs(pt.y)); }
		//if (Math.abs(pt.z)>dims.z/2-objRadius) { pt.z*=(dims.z/2-objRadius)/(Math.abs(pt.z)); }
		return pt;
	}
	
	public Pt3D rdmPtInsideSpawnFraction () {
		double spawnFrac = Env.boxSpawnFraction.getValue();
		Pt3D pt = new Pt3D((2*Math.random()-1)*(dims.x/2)*spawnFrac,(2*Math.random()-1)*(dims.y/2)*spawnFrac,(2*Math.random()-1)*(dims.z/2)*spawnFrac);
		return pt;
	}
	
	public void amICollidingOuter (CollisionEvent lcE, Pt3D ctr, double R) {
		lcE.zeroDelta();
		lcE.tmpPt1.sub(ctr, coordAsPt3D());
		lcE.forceUVec.setVals(Math.signum(lcE.tmpPt1.x)*(dims.x/2-R),Math.signum(lcE.tmpPt1.y)*(dims.y/2-R),Math.signum(lcE.tmpPt1.z)*(dims.z/2-R));
		lcE.forceUVec.sub(lcE.forceUVec, lcE.tmpPt1);
		if (Math.signum(lcE.forceUVec.x)==Math.signum(lcE.tmpPt1.x)) { lcE.forceUVec.x = 0; }
		if (Math.signum(lcE.forceUVec.y)==Math.signum(lcE.tmpPt1.y)) { lcE.forceUVec.y = 0; }
		if (Math.signum(lcE.forceUVec.z)==Math.signum(lcE.tmpPt1.z)) { lcE.forceUVec.z = 0; }
		
		lcE.delta = Pt3D.vecMag(lcE.forceUVec);
		if (lcE.delta != 0) {	// don't bother with forceUVec if mag is zero
			lcE.forceUVec.unitVec();
		}
	}
	
	
	
}
