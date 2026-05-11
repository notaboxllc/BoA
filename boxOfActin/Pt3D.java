package boxOfActin;
/* the POINT IN 3D class... just a 3D point {x,y,z} */

import java.lang.Math;
import java.io.*;
import java.text.*;
import edu.cornell.lassp.houle.RngPack.RanMT;
import ec.util.*;


public class Pt3D {
	public double x, y, z;
	static final Pt3D zeroPt3D = new Pt3D(0,0,0);
	static final Pt3D farfarAway = new Pt3D(10000,10000,10000);
	
	public Pt3D () { this.x = 0; this.y = 0; this.z = 0; }
	public Pt3D (double [] pt) { this.x = pt[0]; this.y = pt[1]; this.z = pt[2]; }
	public Pt3D (double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
	public Pt3D (Pt3D pt) { this.x = pt.x; this.y= pt.y; this.z = pt.z; }
	DecimalFormat pt3DFormat = new DecimalFormat ("#0.000000000000#;#000.000000000000#");
	
	
	// methods for calculating the distance between two points
	public static double ptDist (Pt3D pt1, Pt3D pt2) {
		// calculates the distance between two points.
		double distsqrd = Math.pow(pt1.x-pt2.x,2) + Math.pow(pt1.y-pt2.y,2) + Math.pow(pt1.z-pt2.z,2);
		return Math.sqrt(distsqrd);
	}
	
	public static double ptDistSqrd (Pt3D pt1, Pt3D pt2) {
		// calculates the square of the distance between two points
		return Math.pow(pt1.x-pt2.x, 2) + Math.pow(pt1.y-pt2.y, 2) + Math.pow(pt1.z-pt2.z, 2);
	}
	
	// methods for magnitude and magnitude squared of a 3-vector
	public static double vecMag (Pt3D vec) {
		return Math.sqrt(vec.x*vec.x + vec.y*vec.y + vec.z*vec.z);
	}
	
	public static double vecMagSqrd (Pt3D vec) {
		return vec.x*vec.x + vec.y*vec.y + vec.z*vec.z;
	}
	
	// sqrt of vector components
	public static Pt3D VecSqrt (Pt3D vec) {
		if ((vec.x < 0) | (vec.y < 0) | (vec.z < 0)) { } //talkln ("Error in Pt3D.VecSqrt.... number is negative"); }
		return new Pt3D (Math.sqrt(vec.x), Math.sqrt(vec.y), Math.sqrt(vec.z));
	}
	
	public void vecSqrt (Pt3D vec) {
		if ((vec.x < 0) | (vec.y < 0) | (vec.z < 0)) { }//talkln("Error in Pt3D.vecSqrt.... number is negative"); }
		this.x = Math.sqrt(vec.x); this.y = Math.sqrt(vec.y); this.z = Math.sqrt(vec.z);
	}
	
	// *** ANGLES FROM UNIT VECTOR ***  return Euler type 1 angles from a unit vector
	public static Pt3D EulerAnglesFromUVec (Pt3D uVec) {
		double angleX = Math.atan2(uVec.y, uVec.x);
		double angleY = Math.asin(-uVec.z);
		double angleZ = 0;		// set Phi to zero...why not
		Pt3D eulerAngs = new Pt3D (angleX, angleY, angleZ);
		if (eulerAngs.checkPt3D()) {
			return new Pt3D (angleX, angleY, angleZ);
		} else {
			talkln ("Something wrong with eulerAngs in Pt3D.EulerAnglesFromUVec (Pt3D)");
			return new Pt3D (0,0,0);
		}
	}
	
	public void eulerAnglesFromUVec (Pt3D uVec) {
		this.x = Math.atan2(uVec.y, uVec.x);
		this.y = Math.asin(-uVec.z);
		//leave phi alone?
		//this.z = 0;		// set Phi to zero...why not
	}
	
	// *** ANGLES FROM ANY VECTOR *** return Euler type 1 angles given any vector
	public static Pt3D EulerAnglesFromVec (Pt3D anyVec) {
		Pt3D uVec = UnitVec (anyVec);
		return EulerAnglesFromUVec (uVec);
	}
	
	// *** UNIT VECTOR *** finds the unit vector between two points or from any vector
	// **** Static methods first
	public static Pt3D UnitVec (Pt3D pt1, Pt3D pt2) {
		double xdiff = pt1.x - pt2.x;
		double ydiff = pt1.y - pt2.y;
		double zdiff = pt1.z - pt2.z;
		double mag = Math.sqrt(xdiff*xdiff + ydiff*ydiff + zdiff*zdiff);
		if (mag == 0) {
			return RandomUnitVec (Env.mtRNG);
		} else {
			return new Pt3D (xdiff/mag, ydiff/mag, zdiff/mag);
		}
	}
	
	public static Pt3D UnitVec (double mag, Pt3D pt1, Pt3D pt2) {
	// if the distance between points is known, then use this version of the method
		if (mag == 0) {
			return RandomUnitVec (Env.mtRNG);
		} else {
			return new Pt3D ((pt1.x-pt2.x)/mag, (pt1.y-pt2.y)/mag, (pt1.z-pt2.z)/mag);
		}
	}
	
	public static Pt3D UnitVec (Pt3D anyVec) {
		double mag = vecMag (anyVec);
		if (mag == 0) {
			return RandomUnitVec (Env.mtRNG);
		} else {
			return new Pt3D(anyVec.x/mag, anyVec.y/mag, anyVec.z/mag);
		}
	}
	
	public void unitVec (Pt3D pt1, Pt3D pt2) {
		double xdiff = pt1.x - pt2.x;
		double ydiff = pt1.y - pt2.y;
		double zdiff = pt1.z - pt2.z;
		double mag = Math.sqrt(xdiff*xdiff + ydiff*ydiff + zdiff*zdiff);
		if (mag == 0) { 
			randomUnitVec(Env.mtRNG);
		} else {
			this.x = xdiff/mag;
			this.y = ydiff/mag;
			this.z = zdiff/mag;
		}
	}
	
	public void unitVec (double mag, Pt3D pt1, Pt3D pt2) {
	// if the distance between points is known, then use this version of the method
		if (mag == 0) {
			randomUnitVec(Env.mtRNG);
		} else {
			this.x = (pt1.x-pt2.x)/mag;
			this.y = (pt1.y-pt2.y)/mag;
			this.z = (pt1.z-pt2.z)/mag;
		}
	}
	
	public void unitVec (Pt3D anyVec) {
		double mag = vecMag (anyVec);
		if (mag == 0) {
			randomUnitVec(Env.mtRNG);
		} else {
			this.x = this.x/mag;
			this.y = this.y/mag;
			this.z = this.z/mag;
		}
	}
	
	public void unitVec () {
		double mag = vecMag (this);
		if (mag == 0) {
			randomUnitVec(Env.mtRNG);
		} else {
			this.x = this.x/mag;
			this.y = this.y/mag;
			this.z = this.z/mag;
		}
	}
	
	public static Pt3D RandomUnitVec (MersenneTwister prng) {
		Pt3D uVec = Pt3D.Random(prng);
		uVec.unitVec();
		return uVec;
	}
	
	public static Pt3D RandomUnitVec (MersenneTwisterFast prng) {
		Pt3D uVec = Pt3D.Random(prng);
		uVec.unitVec();
		return uVec;
	}
	
	public void randomUnitVec (MersenneTwister prng) {
		random(prng);
		unitVec();
	}
	
	public void randomUnitVec (MersenneTwisterFast prng) {
		random(prng);
		unitVec();
	}

	// *** CROSS *** methods for determining 3D vector cross products
	public static Pt3D Cross (Pt3D vec1, Pt3D vec2) {
		// cross product of 3-space vectors
		double vecX = vec1.y*vec2.z - vec1.z*vec2.y;
		double vecY = vec1.z*vec2.x - vec1.x*vec2.z;
		double vecZ = vec1.x*vec2.y - vec1.y*vec2.x;
		return new Pt3D(vecX,vecY,vecZ);
	}
	
	public void cross (Pt3D vec1, Pt3D vec2) {
	// cross product of 3-space vectors
		this.x = vec1.y*vec2.z - vec1.z*vec2.y;
		this.y = vec1.z*vec2.x - vec1.x*vec2.z;
		this.z = vec1.x*vec2.y - vec1.y*vec2.x;
	}
	
	public static double CrossMag (Pt3D vec1, Pt3D vec2) {
		// Magnitude of a cross product (useful for sin(angle) between unit vectors)
		// cross product of 3-space vectors
		double vecX = vec1.y*vec2.z - vec1.z*vec2.y;
		double vecY = vec1.z*vec2.x - vec1.x*vec2.z;
		double vecZ = vec1.x*vec2.y - vec1.y*vec2.x;
		double vecMagnitude = Math.sqrt(vecX*vecX+vecY*vecY+vecZ*vecZ);
		return vecMagnitude;
	}
	
	public static double CrossMagSqrd (Pt3D vec1, Pt3D vec2) {
		// Sqrd Magnitude of a cross product (useful for sin(angle) between unit vectors)
		// cross product of 3-space vectors
		double vecX = vec1.y*vec2.z - vec1.z*vec2.y;
		double vecY = vec1.z*vec2.x - vec1.x*vec2.z;
		double vecZ = vec1.x*vec2.y - vec1.y*vec2.x;
		double vecMagnitude = (vecX*vecX+vecY*vecY+vecZ*vecZ);
		return vecMagnitude;
	}
	
	// ********************************************************
	
	
	// *** DOT *** method for determining 3D vector dot products
	public static double Dot (Pt3D vec1, Pt3D vec2) {
		return vec1.x*vec2.x + vec1.y*vec2.y + vec1.z*vec2.z;
	}
	
	public static double PlusDot (Pt3D vec1, Pt3D vec2) {
		return  Math.abs(vec1.x*vec2.x) + Math.abs(vec1.y*vec2.y) + Math.abs(vec1.z*vec2.z);
	}
		
	// *********************************************************
	
	
	// *** SINE and COSINE ***  cosine and sine of vectors
	public static Pt3D Cosine (Pt3D vec) {
		return new Pt3D (Math.cos(vec.x), Math.cos(vec.y), Math.cos(vec.z));
	}
	
	public static Pt3D Sine (Pt3D vec) {
		return new Pt3D (Math.sin(vec.x), Math.sin(vec.y), Math.sin(vec.z));
	}
					// non-static methods //
	public void cosine (Pt3D vec) {
		this.x = Math.cos(vec.x); this.y = Math.cos(vec.y); this.z = Math.cos(vec.z);
	}
	
	public void sine (Pt3D vec) {
		this.x = Math.sin(vec.x); this.y = Math.sin(vec.y); this.z = Math.sin(vec.z);
	}
	// **********************************************************
	
	
	// *** ADD *** static methods for addition and scaled addition of points or vectors in 3-space
	public static Pt3D Add (Pt3D vec1, Pt3D vec2) {
		return new Pt3D (vec1.x+vec2.x, vec1.y+vec2.y, vec1.z+vec2.z);
	}
	
	public static Pt3D Add (double s1, Pt3D vec1, double s2, Pt3D vec2) {
		return new Pt3D (s1*vec1.x+s2*vec2.x, s1*vec1.y+s2*vec2.y, s1*vec1.z+s2*vec2.z);
	}
	
	public static Pt3D Add (Pt3D vec1, double s2, Pt3D vec2) {
		return new Pt3D (vec1.x+s2*vec2.x, vec1.y+s2*vec2.y, vec1.z+s2*vec2.z);
	}
	
	public static Pt3D Add (double sc, Pt3D vec1, Pt3D vec2) {
		return new Pt3D (sc*(vec1.x+vec2.x), sc*(vec1.y+vec2.y), sc*(vec1.z+vec2.z));
	}
					// non-static methods //
	public void add (Pt3D vec1, Pt3D vec2) {
		this.x = vec1.x+vec2.x; this.y = vec1.y+vec2.y; this.z = vec1.z+vec2.z;
	}
	
	public void add (Pt3D vec1, double s2, Pt3D vec2) {
		this.x = vec1.x+s2*vec2.x; this.y = vec1.y+s2*vec2.y; this.z = vec1.z+s2*vec2.z;
	}
	
	public void add (double s1, Pt3D vec1, double s2, Pt3D vec2) {
		this.x = s1*vec1.x+s2*vec2.x; this.y = s1*vec1.y+s2*vec2.y; this.z = s1*vec1.z+s2*vec2.z;
	}
	
	public void add (double sc, Pt3D vec1, Pt3D vec2) {
		this.x = sc*(vec1.x+vec2.x); this.y = sc*(vec1.y+vec2.y); this.z = sc*(vec1.z+vec2.z);
	}

	public void add (Pt3D vec) {
		this.x += vec.x; this.y += vec.y; this.z += vec.z;
	}
	// ************************************************************
	
	
	// *** SUB *** methods for subtraction of points or vectors in 3-space
	public static Pt3D Sub (Pt3D vec1, Pt3D vec2) {
		return new Pt3D (vec1.x-vec2.x, vec1.y-vec2.y, vec1.z-vec2.z);
	}
	
	public static Pt3D Sub (double sc, Pt3D vec1, Pt3D vec2) {
		return new Pt3D(sc*(vec1.x-vec2.x), sc*(vec1.y-vec2.y), sc*(vec1.z-vec2.z));
	}
	
	public void sub (Pt3D vec1, Pt3D vec2) {
		this.x = vec1.x-vec2.x; this.y = vec1.y-vec2.y; this.z = vec1.z-vec2.z;
	}
	
	public void sub (double sc, Pt3D vec1, Pt3D vec2) {
		this.x = sc*(vec1.x-vec2.x); this.y = sc*(vec1.y-vec2.y); this.z = sc*(vec1.z-vec2.z);
	}
	// *************************************************************
	
	
	//  *** INC ***  method for incrementing a vector.... can't be static the way it's written
	public void inc (Pt3D inc) {
		this.x += inc.x; this.y += inc.y; this.z += inc.z;
	}
	
	public void inc (double sc, Pt3D inc) {
		this.x += sc*inc.x; this.y += sc*inc.y; this.z += sc*inc.z;
	}
	// *************************************************************
	
	
	// *** SCALE *** methods to scale a vector or point in 3-space
	public static Pt3D Scale (double scalar, Pt3D vec) {
		return new Pt3D (vec.x*scalar, vec.y*scalar, vec.z*scalar);
	}
	
	public void scale (double scalar, Pt3D vec) {
		this.x = vec.x*scalar; this.y = vec.y*scalar; this.z = vec.z*scalar;
	}

	public void scale (double sc) {
		this.x = sc*this.x; this.y = sc*this.y; this.z = sc*this.z;
	}
	// ***************************************************************
	
	
	// **** DIV **** divide vec by vec or scalar by vec, etc... element by element
	public static Pt3D Div (Pt3D vec1, Pt3D vec2) {
		return new Pt3D (vec1.x/vec2.x, vec1.y/vec2.y, vec1.z/vec2.z);
	}
	
	public static Pt3D Div (double sc, Pt3D vec1, Pt3D vec2) {
		return new Pt3D (sc*vec1.x/vec2.x, sc*vec1.y/vec2.y, sc*vec1.z/vec2.z);
	}
	
	public static Pt3D Div (double sc, Pt3D vec2) {
		return new Pt3D (sc/vec2.x, sc/vec2.y, sc/vec2.z);
	}
	
					// non-static //
	public void div (Pt3D vec1, Pt3D vec2) {
		this.x = vec1.x/vec2.x; this.y = vec1.y/vec2.y; this.z = vec1.z/vec2.z;
	}
	
	public void div (double sc, Pt3D vec1, Pt3D vec2) {
		this.x = sc*vec1.x/vec2.x; this.y = sc*vec1.y/vec2.y; this.z = sc*vec1.z/vec2.z;
	}
	
	public void div (double sc, Pt3D vec2) {
		this.x = sc/vec2.x; this.y = sc/vec2.y; this.z = sc/vec2.z;
	}
	
	//***************************************************
	
	// *** MULT *** multiply vec by vec... element by element
	public static Pt3D Mult (Pt3D vec1, Pt3D vec2) {
		return new Pt3D (vec1.x*vec2.x, vec1.y*vec2.y, vec1.z*vec2.z);
	}
	
	public static Pt3D Mult (double sc, Pt3D vec1, Pt3D vec2) {
		return new Pt3D (sc*vec1.x*vec2.x, sc*vec1.y*vec2.y, sc*vec1.z*vec2.z);
	}
	
	public static Pt3D Mult (Pt3D vec1, Pt3D vec2, Pt3D vec3) {
		return new Pt3D (vec1.x*vec2.x*vec3.x, vec1.y*vec2.y*vec3.y, vec1.z*vec2.z*vec3.z);
	}
	
	public static Pt3D Mult (double sc, Pt3D vec1, Pt3D vec2, Pt3D vec3) {
		return new Pt3D (sc*vec1.x*vec2.x*vec3.x, sc*vec1.y*vec2.y*vec3.y, sc*vec1.z*vec2.z*vec3.z);
	}
					// non-static methods //
	public void mult (Pt3D vec1, Pt3D vec2) {
		this.x = vec1.x*vec2.x; this.y = vec1.y*vec2.y; this.z = vec1.z*vec2.z;
	}
	
	public void mult (double sc, Pt3D vec1, Pt3D vec2) {
		this.x = sc*vec1.x*vec2.x; this.y = sc*vec1.y*vec2.y; this.z = sc*vec1.z*vec2.z;
	}
	
	public void mult (Pt3D vec1, Pt3D vec2, Pt3D vec3) {
		this.x = vec1.x*vec2.x*vec3.x; this.y = vec1.y*vec2.y*vec3.y; this.z = vec1.z*vec2.z*vec3.z;
	}
	
	public void mult (double sc, Pt3D vec1, Pt3D vec2, Pt3D vec3) {
		this.x = sc*vec1.x*vec2.x*vec3.x; this.y = sc*vec1.y*vec2.y*vec3.y; this.z = sc*vec1.z*vec2.z*vec3.z;
	}
	// ******************************************************
		// *** TRANSFORMATIONS ***
	// method to transform from a players body-fixed coordinate system to fixed coord frame
	public static Pt3D xToNewX (Thing player, Pt3D ptInx) {
		double [] ptInX = new double [3];
		for (int i = 0; i < 3; i ++) {
			ptInX[i] = player.transxToX[i][0]*ptInx.x + player.transxToX[i][1]*ptInx.y + player.transxToX[i][2]*ptInx.z;
		}
		return new Pt3D (ptInX);
	}
	
	public void xToX (Thing p, Pt3D ptInx) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInx is also this point
		tempx = p.transxToX[0][0]*ptInx.x + p.transxToX[0][1]*ptInx.y + p.transxToX[0][2]*ptInx.z;
		tempy = p.transxToX[1][0]*ptInx.x + p.transxToX[1][1]*ptInx.y + p.transxToX[1][2]*ptInx.z;
		tempz = p.transxToX[2][0]*ptInx.x + p.transxToX[2][1]*ptInx.y + p.transxToX[2][2]*ptInx.z;
		this.x = tempx;
		this.y = tempy;
		this.z = tempz;
	}
	
	public void xToX (Thing p) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInx is also this point
		tempx = p.transxToX[0][0]*this.x + p.transxToX[0][1]*this.y + p.transxToX[0][2]*this.z;
		tempy = p.transxToX[1][0]*this.x + p.transxToX[1][1]*this.y + p.transxToX[1][2]*this.z;
		tempz = p.transxToX[2][0]*this.x + p.transxToX[2][1]*this.y + p.transxToX[2][2]*this.z;
		this.x = tempx;
		this.y = tempy;
		this.z = tempz;
	}
	
	public void xToXPlusxOrigin (Thing p, Pt3D ptInx) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInx is also this point
		tempx = p.transxToX[0][0]*ptInx.x + p.transxToX[0][1]*ptInx.y + p.transxToX[0][2]*ptInx.z;
		tempy = p.transxToX[1][0]*ptInx.x + p.transxToX[1][1]*ptInx.y + p.transxToX[1][2]*ptInx.z;
		tempz = p.transxToX[2][0]*ptInx.x + p.transxToX[2][1]*ptInx.y + p.transxToX[2][2]*ptInx.z;
		this.x = tempx + p.coord.x;
		this.y = tempy + p.coord.y;
		this.z = tempz + p.coord.z;
	}
	
	public void xToXPlusxOrigin (Thing p) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInx is also this point
		tempx = p.transxToX[0][0]*this.x + p.transxToX[0][1]*this.y + p.transxToX[0][2]*this.z;
		tempy = p.transxToX[1][0]*this.x + p.transxToX[1][1]*this.y + p.transxToX[1][2]*this.z;
		tempz = p.transxToX[2][0]*this.x + p.transxToX[2][1]*this.y + p.transxToX[2][2]*this.z;
		this.x = tempx + p.coord.x;
		this.y = tempy + p.coord.y;
		this.z = tempz + p.coord.z;
	}
	
	public void xToXPlusPoint (Thing p, Pt3D ptInx, Pt3D addPt) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInx is also this point
		tempx = p.transxToX[0][0]*ptInx.x + p.transxToX[0][1]*ptInx.y + p.transxToX[0][2]*ptInx.z;
		tempy = p.transxToX[1][0]*ptInx.x + p.transxToX[1][1]*ptInx.y + p.transxToX[1][2]*ptInx.z;
		tempz = p.transxToX[2][0]*ptInx.x + p.transxToX[2][1]*ptInx.y + p.transxToX[2][2]*ptInx.z;
		this.x = tempx + addPt.x;
		this.y = tempy + addPt.y;
		this.z = tempz + addPt.z;
	}
	
	// method to transform from a players fixed coordinate system to body-fixed coord frame
	public static Pt3D XToNewx (Thing player, Pt3D ptInX) {
		double [] ptInx = new double [3];
		for (int i = 0; i < 3; i ++) {
			ptInx[i] = player.transXTox[i][0]*ptInX.x + player.transXTox[i][1]*ptInX.y + player.transXTox[i][2]*ptInX.z;
		}
		return new Pt3D (ptInx);
	}
	
	public void XTox (Thing p, Pt3D ptInX) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInX is also this point
		tempx = p.transXTox[0][0]*ptInX.x + p.transXTox[0][1]*ptInX.y + p.transXTox[0][2]*ptInX.z;
		tempy = p.transXTox[1][0]*ptInX.x + p.transXTox[1][1]*ptInX.y + p.transXTox[1][2]*ptInX.z;
		tempz = p.transXTox[2][0]*ptInX.x + p.transXTox[2][1]*ptInX.y + p.transXTox[2][2]*ptInX.z;
		this.x = tempx;
		this.y = tempy;
		this.z = tempz;
	}
	
	public void XTox (Thing p) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInX is also this point
		tempx = p.transXTox[0][0]*this.x + p.transXTox[0][1]*this.y + p.transXTox[0][2]*this.z;
		tempy = p.transXTox[1][0]*this.x + p.transXTox[1][1]*this.y + p.transXTox[1][2]*this.z;
		tempz = p.transXTox[2][0]*this.x + p.transXTox[2][1]*this.y + p.transXTox[2][2]*this.z;
		this.x = tempx;
		this.y = tempy;
		this.z = tempz;
	}
	
	public void XToxFromxOrigin (Thing p, Pt3D ptInX) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInX is also this point
		double ptFromOx = ptInX.x-p.coord.x;
		double ptFromOy = ptInX.y-p.coord.y;
		double ptFromOz = ptInX.z-p.coord.z;
		tempx = p.transXTox[0][0]*ptFromOx + p.transXTox[0][1]*ptFromOy + p.transXTox[0][2]*ptFromOz;
		tempy = p.transXTox[1][0]*ptFromOx + p.transXTox[1][1]*ptFromOy + p.transXTox[1][2]*ptFromOz;
		tempz = p.transXTox[2][0]*ptFromOx + p.transXTox[2][1]*ptFromOy + p.transXTox[2][2]*ptFromOz;
		this.x = tempx;
		this.y = tempy;
		this.z = tempz;
	}
	
	public void XToxFromxOrigin (Thing p) {
		double tempx, tempy, tempz;	// necessary for proper function if ptInX is also this point
		double ptFromOx = this.x-p.coord.x;
		double ptFromOy = this.y-p.coord.y;
		double ptFromOz = this.z-p.coord.z;
		tempx = p.transXTox[0][0]*ptFromOx + p.transXTox[0][1]*ptFromOy + p.transXTox[0][2]*ptFromOz;
		tempy = p.transXTox[1][0]*ptFromOx + p.transXTox[1][1]*ptFromOy + p.transXTox[1][2]*ptFromOz;
		tempz = p.transXTox[2][0]*ptFromOx + p.transXTox[2][1]*ptFromOy + p.transXTox[2][2]*ptFromOz;
		this.x = tempx;
		this.y = tempy;
		this.z = tempz;
	}
	

	// *******************************************************************
	// *** REVERSE *** returns the opposite directed vector with same magnitude
	public static Pt3D Reverse (Pt3D reverseMe) {
		return new Pt3D (-1.0*reverseMe.x, -1.0*reverseMe.y, -1*reverseMe.z);
	}
	public void reverse () {
		this.x = -1*this.x;this.y = -1*this.y; this.z=-1*this.z;
	}
	// **************************************************************
	
	// *** COPY ***  copies values of another Pt3D or list of doubles
	public void copy (Pt3D vec) {
		this.x = vec.x; this.y = vec.y; this.z = vec.z;
	}
	
	public void copy (double x, double y, double z) {
		this.x = x; this.y = y; this.z = z;
	}
	// ************************************************
	
	// *** SET ***
	public void setVals (double newX, double newY, double newZ) {
		this.x = newX; this.y = newY; this.z = newZ;
	}
	//***********
	
	// *** RANDOM *** make a random Pt3D
	public void random (MersenneTwister prng) {
		x = 2*prng.nextDouble()-1;
		y = 2*prng.nextDouble()-1;
		z = 2*prng.nextDouble()-1;
	}
	
	public void random (MersenneTwisterFast prng) {
		x = 2*prng.nextDouble()-1;
		y = 2*prng.nextDouble()-1;
		z = 2*prng.nextDouble()-1;
	}
	
	public static Pt3D Random(MersenneTwister prng) {
		return new Pt3D(2*prng.nextDouble()-1, 2*prng.nextDouble()-1, 2*prng.nextDouble()-1);
	}
	
	public static Pt3D Random(MersenneTwisterFast prng) {
		return new Pt3D(2*prng.nextDouble()-1, 2*prng.nextDouble()-1, 2*prng.nextDouble()-1);
	}
	
	// *** RandomPositive *** make a random Pt3D with all positive values
	public static Pt3D RandomPositive(MersenneTwisterFast prng) {
		return new Pt3D(prng.nextDouble(),prng.nextDouble(),prng.nextDouble());
	}

	// ************************************************
	
	// *** ZERO *** zero a pt
	public void zero () {
		this.x = 0; this.y = 0; this.z = 0;
	}
	// *****************************************
	
	// *** REPORT COORDINATES ***
	public static String ReportCoords(Pt3D pt) {
		return "(" + String.valueOf(pt.x)+","+String.valueOf(pt.y)+","+String.valueOf(pt.z)+")";
	}
	
	public String reportCoords() {
		return "(" + String.valueOf(pt3DFormat.format(this.x))+","+String.valueOf(pt3DFormat.format(this.y))+","+String.valueOf(pt3DFormat.format(this.z))+")";
	}
	
	// *** Write and Read the Pt3D to/from a file ***
	public static void writePt3D (DataOutputStream ds, Pt3D pt) {
		try {
			ds.writeFloat((float) pt.x);
			ds.writeFloat((float) pt.y);
			ds.writeFloat((float) pt.z);
		} catch (IOException ioe) { talkln ("some sort of error writing binary file in writePt3D"); }
	}
	
	public static Pt3D ReadPt3D (DataInputStream ds) {
		try {
			float f1 = ds.readFloat();
			float f2 = ds.readFloat();
			float f3 = ds.readFloat();
			return new Pt3D(f1,f2,f3);
		} catch (IOException ioe) { talkln ("some sort of error reading binary file in ReadPt3D"); }
		return null;
	}
	
	public void readPt3D (DataInputStream ds) {
		try {
			this.x = ds.readFloat();
			this.y = ds.readFloat();
			this.z = ds.readFloat();
		} catch (IOException ioe) { talkln ("some sort of error reading binary file in readPt3D"); }
	}
	
	public static void writeNullVec3d (DataOutputStream ds) {
		try {
			ds.writeFloat((float) farfarAway.x);
			ds.writeFloat((float) farfarAway.y);
			ds.writeFloat((float) farfarAway.z);
		} catch (IOException ioe) { talkln ("some sort of error writing binary file in writePt3D"); }
	}
	
	// ********************************************
	
	//**** CHECK PT3D ***  make sure everything is kosher, so to speak
	public static boolean CheckPt3D (Pt3D ckMe) {
		if ((Double.isNaN(ckMe.x)) | (Double.isNaN(ckMe.y)) | (Double.isNaN(ckMe.z))) {
			return false;
		} else {
			return true;
		}
	}
	
	public boolean checkPt3D () {
		if ((Double.isNaN(this.x)) | (Double.isNaN(this.y)) | (Double.isNaN(this.z))) {
			return false;
		} else {
			return true;
		}
	}
	
	public static void talkln (String info) {
		System.out.println(info);
	}
	
	public static void talk (String info) {
		System.out.print(info);
	}
}
