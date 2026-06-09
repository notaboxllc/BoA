package boxOfActin;

import java.awt.Color;
import java.awt.Font;

import boxOfActin.ProteinNode.ProteinNodeThreads;

public class FillNode extends ProteinNode {
	static int fillNodeCt = 0;
	boolean iAmGrowing = false;

	
	
	public FillNode (Pt3D initCoord, double radius) {
		super(initCoord,false);
		this.radius = radius;
		calculateProperties();
		pushPoseToSoa();
		initialize();
		fillNodeCt++;
	}
	
	public void calculateProperties () {
			double tstDiffFactor = Env.fillNodeDragScale.getValue();
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
	
	public void moveThing () {
		randForces.scale(Env.fillNodeBrownianScale.getValue(),randForces);
		randTorques.scale(Env.fillNodeBrownianScale.getValue(),randTorques);
		super.moveThing();
	}
	
	public void step () {
		super.step();
		if (iAmGrowing) { radius += Env.nodeGrowthPerStep.getValue(); }
	}
	
	public static void removeAll() {
		fillNodeCt = 0;  // right now the ProteinNode.removeAll nullifies FillNodes, just reset counter here
	}
	
	public void makeGraphics () {}

	public void updateGraphics () {}
	
	public static void makeExpandingInnerSphere() {
		double initialR = 0.8;
		FillNode expNode = new FillNode(new Pt3D(),initialR);
		expNode.iAmGrowing = true;
	}
	
	static public Pt3D rdmPtInside (double objRadius) {  
		// Cube Reject Method
		// more uniform distribution **Sphere Only**... pick point in cube encompassing sphere, reject if actually outside sphere
		double rad = Env.membraneCellRadius.getValue();
		Pt3D pt = new Pt3D();
		Pt3D centerPt = new Pt3D(0,0,0);
		double radToPoint = 1e6; // start out with large value
		while (radToPoint > (rad-objRadius)) { 
			pt = new Pt3D((2*Math.random()-1)*(rad-objRadius),(2*Math.random()-1)*(rad-objRadius),(2*Math.random()-1)*(rad-objRadius));
			radToPoint = Pt3D.vecMag(Pt3D.Sub(pt, centerPt));
		}		
		return pt;
	}
	
	public static void fillCellWithSpheres() {
		Pt3D nodePt;
		for (int i=0; i<Env.fillNodeCt.getIntValue(); i++) {
			nodePt = rdmPtInside (Env.fillNodeRadius.getValue());
			new FillNode(nodePt,Env.fillNodeRadius.getValue());
		}
	}
	
	public static void addFillNodeToCell() {
		Pt3D nodePt;
		for (int i=0;i<500;i++) {
			if (fillNodeCt < Env.fillNodeCt.getIntValue()) {
				nodePt = rdmPtInside (Env.fillNodeRadius.getValue());
				new FillNode(nodePt,Env.fillNodeRadius.getValue());
			}
		}
	}
	

}