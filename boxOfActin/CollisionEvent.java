package boxOfActin;
/**
 * CollisionEvent.java
 *
 * @author Created by Omnicore CodeGuide
 */


public class CollisionEvent {
	Pt3D forceUVec = new Pt3D();		// unit vector of the force direction
	double delta = 0;		// impinge dist of collision
	Pt3D tmpPt1 = new Pt3D();
	Pt3D tmpPt2 = new Pt3D();
	boolean collision = false; // flag so we don't get confused using value of delta to determine if collision has occurred
	
	// classify loc, for listeria sim only
	static int END1 = 1;
	static int END2 = 2;
	static int CYLINDER = 3;
	int type;
	
	public CollisionEvent (double delta, Pt3D forceUVec) {
		this.delta = delta;
		this.forceUVec.copy(forceUVec);
	}
	
	public CollisionEvent () {
		
	}
	
	public void zeroDelta() {
		delta = 0;
	}
	
	public void bigDelta() {
		delta = 1e6;
	}
	
	public void setCollision(boolean col) {
		collision = col;
	}
	
	public boolean isColliding() {
		return collision;
	}
	
	
	public void zeroAll() {
		delta = 0;
		forceUVec.zero();
		tmpPt1.zero();
		tmpPt2.zero();
	}
	
}

