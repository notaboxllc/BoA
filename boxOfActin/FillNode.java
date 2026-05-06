package boxOfActin;

import java.awt.Color;
import java.awt.Font;

import javax.media.j3d.Appearance;
import javax.media.j3d.ColoringAttributes;
import javax.media.j3d.Font3D;
import javax.media.j3d.FontExtrusion;
import javax.media.j3d.Material;
import javax.media.j3d.Shape3D;
import javax.media.j3d.Text3D;
import javax.media.j3d.Transform3D;
import javax.media.j3d.TransformGroup;
import javax.media.j3d.TransparencyAttributes;
import javax.vecmath.Color3f;
import javax.vecmath.Vector3d;

import com.sun.j3d.utils.geometry.Sphere;

import boxOfActin.ProteinNode.ProteinNodeThreads;

public class FillNode extends ProteinNode {
	static int fillNodeCt = 0;
	boolean iAmGrowing = false;

	
	// graphics
	static Color3f ambientC = new Color3f(0.0f,0.0f,1.0f);
	static Color3f diffuseC = new Color3f(0.0f,0.0f,1.0f);
	static Color3f specularC = new Color3f(0.0f,0.0f,1.0f);
	static Color3f emissiveC = new Color3f(0.0f,0.0f,1.0f);
	static float shiny = 128.0f;
	
	public FillNode (Pt3D initCoord, double radius) {
		super(initCoord,false);
		this.radius = radius;
		calculateProperties();
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

			bRotGam.x = tstDiffFactor*8*Math.PI*Env.aeta.getValue()*Math.pow(radiusM,3);	// drag for turning about x
			bRotGam.y = bRotGam.x;
			bRotGam.z = bRotGam.x;
			bRotDiff.div(Env.Boltz*Env.tempK, bRotGam);		// Einstein's relation gamma=kT/D
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
	
	public void makeGraphics () {
		// make material
		Color3f fillNodeColor = new Color3f(Color.DARK_GRAY);
		m = new Material(fillNodeColor,fillNodeColor,fillNodeColor,fillNodeColor,shiny);
		// set capabilities
		setGraphicsCapabilities();
		
		ColoringAttributes cA = new ColoringAttributes(fillNodeColor, ColoringAttributes.FASTEST);
		a.setColoringAttributes(cA);
		TransparencyAttributes tA = new TransparencyAttributes ();
		tA.setTransparency(0.5f);
		tA.setTransparencyMode(tA.FASTEST);
		a.setTransparencyAttributes(tA);
		a.setMaterial(m);
		
		//a.setPolygonAttributes(new PolygonAttributes(PolygonAttributes.POLYGON_LINE,PolygonAttributes.CULL_BACK,0.0f));
		mySphere = new Sphere(1.0f,Sphere.GENERATE_NORMALS, Env.nodeTessalation, a);
		mySphere.setCapability(Sphere.ALLOW_BOUNDS_READ);
		
		coord.copyToVector3d(coordVec3d);
		t3d.setScale(radius);
		t3d.setTranslation(coordVec3d);
		t3d.setRotation(mxToX);
		g3d.setTransform(t3d);
		
		g3d.addChild(mySphere);
		G.addChild(g3d);
		graphicsMade = true;
	}
	
	public void updateGraphics () {
		if (iAmGrowing) { t3d.setScale(radius); }
		coord.copyToVector3d(coordVec3d);
		//coordVec3d.z += 0.8*radius;  // shift where we visualize spheres to plane of contact at inner edge of cell
		t3d.setTranslation(coordVec3d);
		t3d.setRotation(mxToX);
		g3d.setTransform(t3d);
	}
	
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