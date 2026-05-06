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

import com.sun.j3d.utils.geometry.*;
import com.sun.j3d.utils.geometry.Box;
import com.sun.j3d.utils.universe.*;

import javax.media.j3d.*;
import javax.vecmath.*;

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
	BranchGroup boxG,infoG;
	Box myBox;
	static Color3f ambientC = new Color3f(1.0f,1.0f,1.0f);
	static Color3f diffuseC = new Color3f(0.9f,0.9f,0.9f);
	static Color3f specularC = new Color3f(1.0f,1.0f,1.0f);
	static Color3f emissiveC = new Color3f(1.0f,1.0f,1.0f);
	static float shiny = 128.0f;
	Appearance aWire = new Appearance();
	static Appearance aSphCaps, bonyApp;
	static TransparencyAttributes noSeeMeTA, bonyTA, tA;
	static Text3D timeTextGeom,concTextGeom;
	static boolean bugInScene = false;
	static boolean coordSysInScene = false;
	static boolean appearanceChanged = false;
	static boolean useWireAppearance = false;
//	 for coord sys drawing
	static Color3f xLineColor = new Color3f(1.0f,0.0f,0.0f);
	static Color3f yLineColor = new Color3f(0.0f,1.0f,0.0f);
	static Color3f zLineColor = new Color3f(0.0f,0.0f,1.0f);
	static Appearance xLineApp = new Appearance();
	static Appearance yLineApp = new Appearance();
	static Appearance zLineApp = new Appearance();
	static ColoringAttributes xLineCA = new ColoringAttributes(xLineColor, ColoringAttributes.FASTEST);
	static ColoringAttributes yLineCA = new ColoringAttributes(yLineColor, ColoringAttributes.FASTEST);
	static ColoringAttributes zLineCA = new ColoringAttributes(zLineColor, ColoringAttributes.FASTEST);
	double coordLineLength = 20*Env.actinMonoDiam;
	LineArray xLine, yLine, zLine;
	Shape3D xLineShape, yLineShape, zLineShape;
	Pt3D xLineEndPt = new Pt3D();
	Pt3D yLineEndPt = new Pt3D();
	Pt3D zLineEndPt = new Pt3D();
	
	
	
	// Pt3Ds reused in collision calculations
	//Pt3D forceVec = new Pt3D();
	//Pt3D hemDist = new Pt3D();
	//Pt3D toVec = new Pt3D();
	//Pt3D tmp1 = new Pt3D();
	//Pt3D tmp2 = new Pt3D();

	public Chamber (Pt3D coord, double xDim, double yDim, double zDim) {
		super (coord);
		dimX = xDim;
		dimY = yDim;
		dimZ = zDim;
		dims = new Pt3D(dimX,dimY,dimZ);	// dimensions of the box
		boxVolume = 8*dimX*dimY*dimZ;  // volume in cubic microns.  Note 2*dimX*2*dimY*2*dimZ
		microMolarChangePerMonomer = (Math.pow(1e5,3)*1e6/(boxVolume*Env.AvogadroNum));		// (1e-6)^4 is for units.
	
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
		lcE.tmpPt1.sub(ctr, coord);
		lcE.forceUVec.set(Math.signum(lcE.tmpPt1.x)*(dims.x/2-R),Math.signum(lcE.tmpPt1.y)*(dims.y/2-R),Math.signum(lcE.tmpPt1.z)*(dims.z/2-R));
		lcE.forceUVec.sub(lcE.forceUVec, lcE.tmpPt1);
		if (Math.signum(lcE.forceUVec.x)==Math.signum(lcE.tmpPt1.x)) { lcE.forceUVec.x = 0; }
		if (Math.signum(lcE.forceUVec.y)==Math.signum(lcE.tmpPt1.y)) { lcE.forceUVec.y = 0; }
		if (Math.signum(lcE.forceUVec.z)==Math.signum(lcE.tmpPt1.z)) { lcE.forceUVec.z = 0; }
		
		lcE.delta = Pt3D.vecMag(lcE.forceUVec);
		if (lcE.delta != 0) {	// don't bother with forceUVec if mag is zero
			lcE.forceUVec.unitVec();
		}
	}
	
	public void setWireFrameAppearance () {
		a.setLineAttributes(new LineAttributes(1.0f, LineAttributes.PATTERN_SOLID,true));
		PolygonAttributes wireAtts = new PolygonAttributes(PolygonAttributes.POLYGON_LINE,PolygonAttributes.CULL_NONE,0.0f,true);
		a.setPolygonAttributes(wireAtts);
	}
	
	public void setFillAppearance () {
		a.setLineAttributes(new LineAttributes(1.0f, LineAttributes.PATTERN_SOLID,true));
		PolygonAttributes filAtts = new PolygonAttributes(PolygonAttributes.POLYGON_FILL,PolygonAttributes.CULL_FRONT,0.0f);
		filAtts.setBackFaceNormalFlip(true);
		a.setPolygonAttributes(filAtts);
		PolygonAttributes filSphCapAtts = new PolygonAttributes(PolygonAttributes.POLYGON_FILL,PolygonAttributes.CULL_BACK,0.0f);
		filSphCapAtts.setBackFaceNormalFlip(true);
	}
	
	public void setCoarseWireAppearance () {
		if (Env.bugWiredCoarse) {
			a.setTransparencyAttributes(noSeeMeTA);
			bonyApp.setTransparencyAttributes(bonyTA);
		} else {
			a.setTransparencyAttributes(tA);
			bonyApp.setTransparencyAttributes(noSeeMeTA);
		}
	}
			
	
	
	public void makeGraphics () {
		setGraphicsCapabilities();	// set capabilities for BranchGroup, TransformGroup, and Appearance
		// no see um TransparencyAttributes
		noSeeMeTA = new TransparencyAttributes(TransparencyAttributes.NICEST,1.0f);
		
		// the main appearance
		m = new Material (ambientC,emissiveC,diffuseC,specularC,shiny);
		m.setCapability(Material.ALLOW_COMPONENT_WRITE);
		ColoringAttributes cA = new ColoringAttributes(1.0f,1.0f,1.0f,ColoringAttributes.NICEST);
		tA = new TransparencyAttributes (TransparencyAttributes.NICEST,Chamber.initialTrans );
		tA.setCapability(TransparencyAttributes.ALLOW_VALUE_WRITE);
		a.setMaterial(m);
		a.setColoringAttributes(cA);
		a.setTransparencyAttributes(tA);
		
		// make alternate transparent appearance for cylinder end caps
		Appearance capA = new Appearance();
		ColoringAttributes capCA = new ColoringAttributes(0.0f,0.0f,0.0f,ColoringAttributes.NICEST);
		capA.setColoringAttributes(capCA);
		capA.setTransparencyAttributes(noSeeMeTA);
		capA.setLineAttributes(new LineAttributes(0.5f, LineAttributes.PATTERN_DASH_DOT,true));
		capA.setPolygonAttributes(new PolygonAttributes(PolygonAttributes.POLYGON_POINT,PolygonAttributes.CULL_BACK,0.0f));
				
		// make another appearance for second cylinder
		bonyApp = new Appearance ();
		bonyApp.setCapability(Appearance.ALLOW_POLYGON_ATTRIBUTES_WRITE);
		bonyApp.setCapability(Appearance.ALLOW_LINE_ATTRIBUTES_WRITE);
		bonyApp.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_WRITE);
		bonyApp.setMaterial(m);
		bonyApp.setColoringAttributes(cA);
		bonyTA = new TransparencyAttributes(TransparencyAttributes.NICEST,0.8f);
		bonyApp.setTransparencyAttributes(bonyTA);
		bonyApp.setLineAttributes(new LineAttributes(1.0f, LineAttributes.PATTERN_SOLID,true));
		PolygonAttributes bonyAtts = new PolygonAttributes(PolygonAttributes.POLYGON_LINE,PolygonAttributes.CULL_NONE,0.0f);
		bonyAtts.setBackFaceNormalFlip(true);
		bonyApp.setPolygonAttributes(bonyAtts);
		
		setFillAppearance();
		if (Env.bugWired){
			setWireFrameAppearance ();  // start out with the wireframe appearance
		} else {
			setFillAppearance();
		}
		
		//build the box shape... 
		myBox = new Box((float)dimX/2,(float)dimY/2,(float)dimZ/2,Primitive.GENERATE_NORMALS,a);
		myBox.setCapability(myBox.ENABLE_APPEARANCE_MODIFY);
		//myBox.setAppearance(myBox.TOP,capA);
		//myBox.setAppearance(myBox.BOTTOM,capA);
		Transform3D boxT3D = new Transform3D();
		TransformGroup boxTG = new TransformGroup(boxT3D);
		boxTG.addChild(myBox);
		
	
		Appearance textAppear = new Appearance();
		Color3f noColor = new Color3f (0f,0f,0f);
		Color3f textEmitColor = new Color3f(0.7f,0.7f,0.7f);
		Color3f textDiffColor = new Color3f(0.7f,0.7f,0.7f);
		Color3f textSpecColor = new Color3f(1.0f,1.0f,1.0f);
	    textAppear.setMaterial(new Material(noColor,textEmitColor,textDiffColor,textSpecColor,128));
	    Font textFont = new Font("Helvetica",Font.ITALIC,2);
	    Font3D font3D = new Font3D(textFont,0.001,new FontExtrusion());
		
	    timeTextGeom = new Text3D(font3D, new String("Time: " + timeFormat.format(0) + " s"));
	    timeTextGeom.setCapability(Text3D.ALLOW_STRING_WRITE);
	    timeTextGeom.setAlignment(Text3D.ALIGN_FIRST);
	    Shape3D timeTextShape = new Shape3D();
	    timeTextShape.setGeometry(timeTextGeom);
	    timeTextShape.setAppearance(textAppear);
	    Transform3D timeTextT3D = new Transform3D();
	    timeTextT3D.rotX(Math.PI/4);
	    TransformGroup timeTextTG = new TransformGroup();
	    timeTextT3D.setScale(.2);
	    timeTextT3D.setTranslation(new Vector3d(1.5,dimY/2+0.1,dimZ/2+0.1));
	    timeTextTG.setTransform(timeTextT3D);
	    timeTextTG.addChild(timeTextShape);
	    
	    concTextGeom = new Text3D(font3D, new String("Actin: " + concFormat.format(Env.actinConc.getValue()) + " �M"));
	    concTextGeom.setCapability(Text3D.ALLOW_STRING_WRITE);
	    concTextGeom.setAlignment(Text3D.ALIGN_FIRST);
	    Shape3D concTextShape = new Shape3D();
	    concTextShape.setGeometry(concTextGeom);
	    concTextShape.setAppearance(textAppear);
	    Transform3D concTextT3D = new Transform3D();
	    TransformGroup concTextTG = new TransformGroup();
	    concTextT3D.setScale(0.03);//(0.1);
	    concTextT3D.setTranslation(new Vector3d(-0.45,dimY+0.03,0));  // use -1.5 for actual size pombe
	    concTextTG.setTransform(concTextT3D);
	    concTextTG.addChild(concTextShape);
	    
	    infoG = new BranchGroup();
	    infoG.setCapability(BranchGroup.ALLOW_DETACH);
	    infoG.addChild(timeTextTG);
	    //infoG.addChild(concTextTG);
		
		g3d.setTransform(t3d);
		
		g3d.addChild(boxTG);
		
		if (Env.infoIn3D) { g3d.addChild(infoG);}
		
		// coordinate system
		xLineApp.setCapability(Appearance.ALLOW_COLORING_ATTRIBUTES_WRITE);
		yLineApp.setCapability(Appearance.ALLOW_COLORING_ATTRIBUTES_WRITE);
		zLineApp.setCapability(Appearance.ALLOW_COLORING_ATTRIBUTES_WRITE);
		xLineApp.setColoringAttributes(xLineCA);
		yLineApp.setColoringAttributes(yLineCA);
		zLineApp.setColoringAttributes(zLineCA);
		// x-axis
		xLine = new LineArray(2,LineArray.COORDINATES);
		xLine.setCapability(LineArray.ALLOW_COORDINATE_WRITE);
		xLineEndPt.add(coord,coordLineLength,uVec);
		xLine.setCoordinate(0,coord);
		xLine.setCoordinate(1,xLineEndPt);
		xLineShape = new Shape3D();
		xLineShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		xLineShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		xLineShape.setGeometry(xLine);
		xLineShape.setAppearance(xLineApp);
		// y-axis
		yLine = new LineArray(2,LineArray.COORDINATES);
		yLine.setCapability(LineArray.ALLOW_COORDINATE_WRITE);
		yLineEndPt.add(coord,coordLineLength,yVec);
		yLine.setCoordinate(0,coord);
		yLine.setCoordinate(1,yLineEndPt);
		yLineShape = new Shape3D();
		yLineShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		yLineShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		yLineShape.setGeometry(yLine);
		yLineShape.setAppearance(yLineApp);
		// z-axis
		zLine = new LineArray(2,LineArray.COORDINATES);
		zLine.setCapability(LineArray.ALLOW_COORDINATE_WRITE);
		zLineEndPt.add(coord,coordLineLength,zVec);
		zLine.setCoordinate(0,coord);
		zLine.setCoordinate(1,zLineEndPt);
		zLineShape = new Shape3D();
		zLineShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		zLineShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		zLineShape.setGeometry(zLine);
		zLineShape.setAppearance(zLineApp);
		BranchGroup coordSysG = new BranchGroup();
		coordSysG.addChild(xLineShape);
		coordSysG.addChild(yLineShape);
		coordSysG.addChild(zLineShape);
	
		boxG = new BranchGroup();
		boxG.setCapability(BranchGroup.ALLOW_DETACH);
		boxG.addChild(g3d);

		G.addChild(boxG);
		bugInScene = true;
		
		if (Env.bugCoordSysOn) {
			boxG.addChild(coordSysG);
		}
		
		G.compile();
		graphicsMade = true;
	}
	public void updateGraphics () {
		if (Env.showCrucible.isActive()) { 
			if (bugInScene) {
				boxG.detach();
				bugInScene = false;
			}
		} else {
			if (!bugInScene) {
				G.addChild(boxG);
				bugInScene = true;
			}
		}
		
		if (Env.infoIn3D) {
			timeTextGeom.setString("Time: " + timeFormat.format(Env.simulationTime) + " s");
			concTextGeom.setString("Actin: " + concFormat.format(Env.actinConc.getValue()) + " �M");
		}
		
		
		if (appearanceChanged) {
				setCoarseWireAppearance();
				if (Env.bugWired) { setWireFrameAppearance(); } else { setFillAppearance(); }
				appearanceChanged = false;
		}
	}
}
