package boxOfActin;
/* the POINT IN 3D class... just a 3D point {x,y,z} */

import java.lang.Math;
import java.io.*;
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
	
	
	// methods for calculating the distance between two points
	public static double ptDist (Pt3D pt1, Pt3D pt2) {
		// calculates the distance between two points.
		double dx = pt1.x-pt2.x, dy = pt1.y-pt2.y, dz = pt1.z-pt2.z;
		return Math.sqrt(dx*dx + dy*dy + dz*dz);
	}

	public static double ptDistSqrd (Pt3D pt1, Pt3D pt2) {
		// calculates the square of the distance between two points
		double dx = pt1.x-pt2.x, dy = pt1.y-pt2.y, dz = pt1.z-pt2.z;
		return dx*dx + dy*dy + dz*dz;
	}
	
	// Fast acos with small-angle approximation. Branches near ±1 use
	// θ ≈ √(2(1-|cosθ|)), <0.6% error at |cosθ|=0.95. Falls back to
	// Math.acos elsewhere. Caller is responsible for clamping into [-1,1].
	public static double fastAcos (double dot) {
		if (dot > 0.95) {
			double t = 1.0 - dot;
			if (t < 0) t = 0;
			return Math.sqrt(2.0 * t);
		} else if (dot < -0.95) {
			double t = 1.0 + dot;
			if (t < 0) t = 0;
			return Math.PI - Math.sqrt(2.0 * t);
		}
		return Math.acos(dot);
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
	// Transformation matrices live in Thing.soaTransXTox (row-major, 9 floats
	// per Thing). transxToX is the transpose; we read transXTox via index
	// swapping rather than storing both. All transform methods inline the
	// matrix reads so the JIT can keep the 9 floats in registers.
	// method to transform from a players body-fixed coordinate system to fixed coord frame
	public static Pt3D xToNewX (Thing player, Pt3D ptInx) {
		final int b9 = player.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = ptInx.x, py = ptInx.y, pz = ptInx.z;
		return new Pt3D(
			m[b9  ]*px + m[b9+3]*py + m[b9+6]*pz,
			m[b9+1]*px + m[b9+4]*py + m[b9+7]*pz,
			m[b9+2]*px + m[b9+5]*py + m[b9+8]*pz);
	}

	public void xToX (Thing p, Pt3D ptInx) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = ptInx.x, py = ptInx.y, pz = ptInx.z;	// snapshot in case ptInx == this
		this.x = m[b9  ]*px + m[b9+3]*py + m[b9+6]*pz;
		this.y = m[b9+1]*px + m[b9+4]*py + m[b9+7]*pz;
		this.z = m[b9+2]*px + m[b9+5]*py + m[b9+8]*pz;
	}

	public void xToX (Thing p) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = this.x, py = this.y, pz = this.z;
		this.x = m[b9  ]*px + m[b9+3]*py + m[b9+6]*pz;
		this.y = m[b9+1]*px + m[b9+4]*py + m[b9+7]*pz;
		this.z = m[b9+2]*px + m[b9+5]*py + m[b9+8]*pz;
	}

	public void xToXPlusxOrigin (Thing p, Pt3D ptInx) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final int b3 = p.myThingNumber * 3;
		final float[] m = Thing.soaTransXTox;
		final float[] c = Thing.soaCoord;
		double px = ptInx.x, py = ptInx.y, pz = ptInx.z;
		this.x = m[b9  ]*px + m[b9+3]*py + m[b9+6]*pz + c[b3];
		this.y = m[b9+1]*px + m[b9+4]*py + m[b9+7]*pz + c[b3+1];
		this.z = m[b9+2]*px + m[b9+5]*py + m[b9+8]*pz + c[b3+2];
	}

	public void xToXPlusxOrigin (Thing p) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final int b3 = p.myThingNumber * 3;
		final float[] m = Thing.soaTransXTox;
		final float[] c = Thing.soaCoord;
		double px = this.x, py = this.y, pz = this.z;
		this.x = m[b9  ]*px + m[b9+3]*py + m[b9+6]*pz + c[b3];
		this.y = m[b9+1]*px + m[b9+4]*py + m[b9+7]*pz + c[b3+1];
		this.z = m[b9+2]*px + m[b9+5]*py + m[b9+8]*pz + c[b3+2];
	}

	public void xToXPlusPoint (Thing p, Pt3D ptInx, Pt3D addPt) {
		final int b9 = p.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = ptInx.x, py = ptInx.y, pz = ptInx.z;
		this.x = m[b9  ]*px + m[b9+3]*py + m[b9+6]*pz + addPt.x;
		this.y = m[b9+1]*px + m[b9+4]*py + m[b9+7]*pz + addPt.y;
		this.z = m[b9+2]*px + m[b9+5]*py + m[b9+8]*pz + addPt.z;
	}

	// method to transform from a players fixed coordinate system to body-fixed coord frame
	public static Pt3D XToNewx (Thing player, Pt3D ptInX) {
		final int b9 = player.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = ptInX.x, py = ptInX.y, pz = ptInX.z;
		return new Pt3D(
			m[b9  ]*px + m[b9+1]*py + m[b9+2]*pz,
			m[b9+3]*px + m[b9+4]*py + m[b9+5]*pz,
			m[b9+6]*px + m[b9+7]*py + m[b9+8]*pz);
	}

	public void XTox (Thing p, Pt3D ptInX) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = ptInX.x, py = ptInX.y, pz = ptInX.z;
		this.x = m[b9  ]*px + m[b9+1]*py + m[b9+2]*pz;
		this.y = m[b9+3]*px + m[b9+4]*py + m[b9+5]*pz;
		this.z = m[b9+6]*px + m[b9+7]*py + m[b9+8]*pz;
	}

	// SoA bridge: same as XTox(Thing, Pt3D) but reads the input vector from
	// a float[] starting at `base`. Used by moveThing readers after the SoA
	// canonical force/torque storage conversion (Thing.soaForceSum/soaTorqueSum).
	public void XToxFromFloats (Thing p, float[] arr, int base) {
		final int b9 = p.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = arr[base], py = arr[base + 1], pz = arr[base + 2];
		this.x = m[b9  ]*px + m[b9+1]*py + m[b9+2]*pz;
		this.y = m[b9+3]*px + m[b9+4]*py + m[b9+5]*pz;
		this.z = m[b9+6]*px + m[b9+7]*py + m[b9+8]*pz;
	}

	public void XTox (Thing p) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final float[] m = Thing.soaTransXTox;
		double px = this.x, py = this.y, pz = this.z;
		this.x = m[b9  ]*px + m[b9+1]*py + m[b9+2]*pz;
		this.y = m[b9+3]*px + m[b9+4]*py + m[b9+5]*pz;
		this.z = m[b9+6]*px + m[b9+7]*py + m[b9+8]*pz;
	}

	public void XToxFromxOrigin (Thing p, Pt3D ptInX) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final int b3 = p.myThingNumber * 3;
		final float[] m = Thing.soaTransXTox;
		final float[] c = Thing.soaCoord;
		double ptFromOx = ptInX.x - c[b3];
		double ptFromOy = ptInX.y - c[b3+1];
		double ptFromOz = ptInX.z - c[b3+2];
		this.x = m[b9  ]*ptFromOx + m[b9+1]*ptFromOy + m[b9+2]*ptFromOz;
		this.y = m[b9+3]*ptFromOx + m[b9+4]*ptFromOy + m[b9+5]*ptFromOz;
		this.z = m[b9+6]*ptFromOx + m[b9+7]*ptFromOy + m[b9+8]*ptFromOz;
	}

	public void XToxFromxOrigin (Thing p) {
		if (Thing.POSE_AUDIT && Thing.poseAuditWindow) Thing.poseAuditHit();
		final int b9 = p.myThingNumber * 9;
		final int b3 = p.myThingNumber * 3;
		final float[] m = Thing.soaTransXTox;
		final float[] c = Thing.soaCoord;
		double ptFromOx = this.x - c[b3];
		double ptFromOy = this.y - c[b3+1];
		double ptFromOz = this.z - c[b3+2];
		this.x = m[b9  ]*ptFromOx + m[b9+1]*ptFromOy + m[b9+2]*ptFromOz;
		this.y = m[b9+3]*ptFromOx + m[b9+4]*ptFromOy + m[b9+5]*ptFromOz;
		this.z = m[b9+6]*ptFromOx + m[b9+7]*ptFromOy + m[b9+8]*ptFromOz;
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
		return String.format("(%.12f,%.12f,%.12f)", this.x, this.y, this.z);
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
