package boxOfActin;
import java.awt.Font;

import com.sun.j3d.utils.geometry.Box;
import com.sun.j3d.utils.geometry.Cone;
import com.sun.j3d.utils.geometry.Sphere;
import javax.media.j3d.*;
import javax.vecmath.*;

public class Monomer {
	// monomer array.. used only in drawing from QK files
	// the following are only used in rendering... not in the sim itself
	static final int maxMons = (int)2e6;
	static final Monomer [] theMonomers = new Monomer[maxMons];
	static int monCt = 0;
	static int monRenderCt = 0; // counter for how many mons rendered in a QK file read
	boolean graphicsIn = false;	// flag for graphics currently in scene or not
	boolean hydrolyzable = true;		// flag to indicate if this is a hydrolyzable monomer
	boolean cofilinOn = false;	// flag for cofilin biding to this monomer
	boolean tropoOn	= false;  // flag for tropomyosin binding to this monomer
	boolean tropoCenter = false; // special flag to mark the monomer at center of a bound tropomyosin

	static final int MINUSEND = 0;
	static final int PLUSEND = 1;
	static final int MINUSSEED = 4;
	
	static final int ATPstate = 10;
	static final int ADPPistate = 11;
	static final int ADPstate = 12;
	
	int endType;
	int nucleotideState = ATPstate;
	boolean removeMe = false;
	
	// for 3D graphics
	static float shiny = 128.0f;
	static final Color3f atpColor = new Color3f(1.0f,1.0f,0.4f);
	static final Color3f adppiColor = new Color3f(1.0f,0.5f,0.0f);
	static final Color3f adpColor = new Color3f(1.0f,0.0f,0.0f);
	static final Color3f isArpColor = new Color3f(0.3f,0.3f,0.3f);
	static final Color3f cofColor = new Color3f(0.7f,0.7f,0.7f);
	static final Color3f tropoColor = new Color3f(0.0f,0.7f,1.0f);
	static final ColoringAttributes atpCA = new ColoringAttributes(atpColor, ColoringAttributes.FASTEST);
	static final ColoringAttributes adppiCA = new ColoringAttributes(adppiColor, ColoringAttributes.FASTEST);
	static final ColoringAttributes adpCA = new ColoringAttributes(adpColor, ColoringAttributes.FASTEST);
	static final ColoringAttributes isArpCA = new ColoringAttributes(isArpColor, ColoringAttributes.FASTEST);
	static final Material atpMat = new Material(atpColor,atpColor,atpColor,atpColor,shiny);
	static final Material adppiMat = new Material(adppiColor,adppiColor,adppiColor,adppiColor,shiny);
	static final Material adpMat = new Material(adpColor,adpColor,adpColor,adpColor,shiny);
	static final Material isArpMat = new Material(isArpColor,isArpColor,isArpColor,isArpColor,shiny);
	static final Material cofMat = new Material(cofColor,cofColor,cofColor,cofColor,shiny);
	static final Material tropoMat = new Material(tropoColor,tropoColor,tropoColor,tropoColor,shiny);
	static Appearance atpApp = new Appearance();
	static Appearance adppiApp = new Appearance();
	static Appearance adpApp = new Appearance();
	static Appearance isArpApp = new Appearance();
	static final LineAttributes monLineAttributes = new LineAttributes(2.0f,LineAttributes.PATTERN_SOLID,true);
	boolean graphicsInitialized = false;
	boolean cofilinMarkOn = false; // for graphics only
	boolean tropoMarkOn	= false; 	// for graphics only
	boolean plusCapMarkOn = false;  // for graphics bookkeeping only
	boolean isArp = false;  // special flag for denoting first two monomers in Arp2/3 bound filament as ARPs
	BranchGroup monHelixG;
	BranchGroup monSphereG;
	LineArray theMonLine;
	Shape3D monShape;
	Sphere monSphere;
	Box capBox; // for marking plus-end caps
	TransformGroup monTG;
	Transform3D monT3D;
	Vector3d monVec3D;
	BranchGroup cofilinMarkBG;
	BranchGroup tropoMarkBG;
	BranchGroup plusCapMarkBG;
	double xRotAng = 0;
	static double xRotAngInc = 0.7*Env.helixAngInc;
	Transform3D rotT3D;
	Matrix3d rotMat;
	Matrix3d curMat = new Matrix3d();
	
	// testing
	static double kHydroRate;
	static double kDissocRate;
	
	Monomer frontMon = null;
	Monomer backMon = null;
	
	static Monomer plusGhost = new Monomer ();
	static Monomer minusGhost = new Monomer ();
	
	public Monomer(){
		if ((!Env.noMonomersRendered.isActive()) & (!Env.remote)) { initializeGraphics(); }
	}
	
	public Monomer (Monomer myNeighbor, int endType){
	   switch (endType) {
	     case MINUSEND :
		    	frontMon = myNeighbor;
		    	myNeighbor.backMon = this;
		    	this.backMon=minusGhost;
		    	break;
	     case PLUSEND :
		    	backMon = myNeighbor;
		    	myNeighbor.frontMon = this;
		    	this.frontMon=plusGhost;
		    	break;
	     case MINUSSEED:
	    		this.backMon=minusGhost;
	    		break;
	     }
	   setStateATP ();
	   if ((!Env.noMonomersRendered.isActive()) & (!Env.remote)) { initializeGraphics(); }
	   
	}
	
	public Monomer (Monomer myNeighbor, int endType, int state){
		   switch (endType) {
		     case MINUSEND :
		    	 	frontMon = myNeighbor;
		    	 	myNeighbor.backMon = this;
		    	 	this.backMon=minusGhost;
		    	 	break;
		     case PLUSEND :
		    	 	backMon = myNeighbor;
		    	 	myNeighbor.frontMon = this;
		    	 	this.frontMon=plusGhost;
		    	 	break;
		     case MINUSSEED:
		    		this.backMon=minusGhost;
		    		break;
		   }
		   switch (state) {
		   case ATPstate :
			  setStateATP ();
			  //addATPMon(this);
			  break;
		   case ADPPistate :
			  setStateADPPi ();
			  //addADPPiMon(this);
			  break;
		   case ADPstate :
			  setStateADP ();
			  break;
		}   
		   if ((!Env.noMonomersRendered.isActive()) & (!Env.remote)) { initializeGraphics(); }
	}
	
	
	synchronized static void polymerize (Monomer myNeighbor, FilSegment myFilament, int endType, boolean hydrolyzable){
		Monomer brandnewMon = new Monomer(myNeighbor, endType);
		switch (endType) {
		case Monomer.MINUSEND:
			myFilament.minusMon = brandnewMon;
			break;
		case Monomer.PLUSEND:
			myFilament.plusMon = brandnewMon;
			break;
		case Monomer.MINUSSEED:
			myFilament.minusMon = brandnewMon;
			break;
		}
		// set orientation angle of this monomer relative to neighbor
		if (brandnewMon.backMon != minusGhost) { brandnewMon.xRotAng = brandnewMon.backMon.xRotAng+Math.PI+xRotAngInc; }
		//brandnewMon.xRotAng = Env.mtRNG.nextDouble()*2*Math.PI;
		
		if ((!Env.noMonomersRendered.isActive()) & (!Env.remote)) { brandnewMon.addGraphics(myFilament); }
		
		brandnewMon.hydrolyzable = hydrolyzable;
	}
	
	
	synchronized void  depolymerize (FilSegment myFilament, int endType){
		switch (endType) {
	     case MINUSEND :
	    	 	if (frontMon != plusGhost) { 
	    	 		frontMon.backMon=minusGhost; 
	    	 		myFilament.minusMon = frontMon;
	    	 	}
	    	 	break;
	     case PLUSEND :
	    	 	if (backMon != minusGhost) { 
	    	 		backMon.frontMon=plusGhost; 
	    	 		myFilament.plusMon = backMon;
	    	 	}
	    	 	break;
	     }
		frontMon = null;
		backMon = null;
		sepaku();
	}
	
	public void setState (int state) {
		switch(state) {
		case Monomer.ATPstate:
			setStateATP();
			return;
		case Monomer.ADPPistate:
			setStateADPPi();
			return;
		case Monomer.ADPstate:
			setStateADP();
			return;
		}
		setStateATP();	// default if we somehow fall through cases
	}
	
	public void setStateATP () {
		nucleotideState = ATPstate;
	}
	
	public void setStateADPPi () {
		nucleotideState = ADPPistate;
	}
	
	public void setStateADP () {
		nucleotideState = ADPstate;
	}
		
	
	public void checkHydrolysisCofilinTropo (FilSegment fil){
		if (tropoOn) { tropoUnbinding(fil);} else { tropoBinding(fil);}
		switch (nucleotideState) {
		case Monomer.ADPstate:
			cofilinBinding(fil);
			break;
		case Monomer.ATPstate:
			hydrolize(fil);
			break;
		case Monomer.ADPPistate:
			dissociate(fil);
			break;
		}
	}
	
	
	public void hydrolize (FilSegment fil){
		if (!hydrolyzable) { return; }
		if (fil.myPRNG.nextDouble() < Env.kHydrolysis.getValue()*Env.biochemDeltaT.getValue()) {
			setStateADPPi ();
         }
	}

	public void dissociate(FilSegment fil) {
		if (fil.myPRNG.nextDouble() < Env.kDissociation.getValue()*Env.biochemDeltaT.getValue()) {
		     setStateADP ();
		}
	}	  

	public int getState () {
		return nucleotideState;
	}
	
	public void tropoBinding (FilSegment fil) {
		if (cofilinOn) { return; } // can't bind monomer if cofilin already present
		if (tropoOn) { return; } // return if already tropomyosin bound
		if (fil.myPRNG.nextDouble() < Env.tropoConc.getValue()*Env.tropoOnRate.getValue()*Env.biochemDeltaT.getValue()) {
			tropoOn = true;
			tropoCenter = true;  // mark this monomer as special, center of a tropomyosin protein
			// tropomyosin spans seven actin monomers... set tropoOn for three monomers in each direction
			int numToMarkEachDir = 3;
			Monomer plusMon = this;
			Monomer minusMon = this;
			for (int i=0;i<numToMarkEachDir;i++) {
				if (plusMon.frontMon != plusGhost) { plusMon = plusMon.frontMon; }
				plusMon.tropoOn = true;
				plusMon.cofilinOn = false;  // kick off cofilin if present
				if (minusMon.backMon != minusGhost) { minusMon = minusMon.backMon; }
				minusMon.tropoOn = true;
				plusMon.cofilinOn = false;  // kick off cofilin if present
			}
		}
	}
	
	public void tropoUnbinding (FilSegment fil) {
		if (!tropoOn) { return; } // return if no tropomyosin bound
		if (!tropoCenter) { return; } // only the center monomer will simulate tropomyosin unbinding
		if (fil.myPRNG.nextDouble() < Env.tropoOffRate.getValue()*Env.biochemDeltaT.getValue()) {
			tropoOn = false;
			tropoCenter = false;
			// tropomyosin spans seven actin monomers... set tropoOff for three monomers in each direction
			int numToMarkEachDir = 3;
			Monomer plusMon = this;
			Monomer minusMon = this;
			for (int i=0;i<numToMarkEachDir;i++) {
				if (plusMon.frontMon != plusGhost) { plusMon = plusMon.frontMon; }
				plusMon.tropoOn = false;
				if (minusMon.backMon != minusGhost) { minusMon = minusMon.backMon; }
				minusMon.tropoOn = false;
			}
		}
	}
	
	public void cofilinBinding (FilSegment fil) {
		if (cofilinOn) { return; } // return if already cofilin bound
		if (tropoOn) { return; } // no cofilin binding if protected by tropomyosin
		double cofRate = Env.cofilinRate.getValue();
		if ((fil.linkCt > 0) & (Env.bundleStableFactor.isActive())) { cofRate=cofRate/(Env.bundleStableFactor.getValue()*fil.linkCt); }		// alter cofilin binding for bundled fils
		if (fil.myPRNG.nextDouble() < Env.cofilinConc.getValue()*cofRate*Env.biochemDeltaT.getValue()) {
			cofilinOn = true;
		}
	}
	
	public void getPosition (Vector3d vec3d) {
		monT3D.get(vec3d);
	}
	
	public boolean isATP() {
		if (nucleotideState == ATPstate) { return true; } else { return false; }
	}
	public boolean isADPPi () {
		if (nucleotideState == ADPPistate) { return true;} else { return false; }
	}
	public boolean isADP () {
		if (nucleotideState == ADPstate){ return true; } else { return false; }
	}
	
	public boolean notATP() {
		if (nucleotideState != ATPstate) { return true; } else { return false; }
	}
	public boolean notADPPi () {
		if (nucleotideState != ADPPistate) { return true;} else { return false; }
	}
	public boolean notADP () {
		if (nucleotideState != ADPstate){ return true; } else { return false; }
	}
		
	
	public void chooseBiochemAppearance() {
		  
		try {
			if (isArp) {
				monShape.setAppearance(isArpApp);
				monSphere.getShape().setAppearance(isArpApp);
				monT3D.setScale(1.5);
				return;
			}  
			switch (nucleotideState) {
				case ATPstate:
					monShape.setAppearance(atpApp);
					monSphere.getShape().setAppearance(atpApp);
					break;
				case ADPPistate:
					monShape.setAppearance(adppiApp);
					monSphere.getShape().setAppearance(adppiApp);
					break;
				case ADPstate:
					monShape.setAppearance(adpApp);
					monSphere.getShape().setAppearance(adpApp);
					break;
				}
		} catch (Exception e) { System.out.println ("Exception in chooseBiochemAppearance()"); }
	}
	
	public static void initializeAllAppearances () {
		atpApp.setColoringAttributes(atpCA);
		atpApp.setMaterial(atpMat);
		atpApp.setLineAttributes(monLineAttributes);
		adppiApp.setColoringAttributes(adppiCA);
		adppiApp.setMaterial(adppiMat);
		adppiApp.setLineAttributes(monLineAttributes);
		adpApp.setColoringAttributes(adpCA);
		adpApp.setMaterial(adpMat);
		adpApp.setLineAttributes(monLineAttributes);
		isArpApp.setColoringAttributes(isArpCA);
		isArpApp.setMaterial(isArpMat);
		isArpApp.setLineAttributes(monLineAttributes);
	}
	
	public void initializeGraphics () {
		// construct
		theMonLine = new LineArray(2,LineArray.COORDINATES);
		monShape = new Shape3D();
		monHelixG = new BranchGroup();
		monSphereG = new BranchGroup();
		monTG = new TransformGroup();
		monT3D = new Transform3D();
		monVec3D = new Vector3d();
		cofilinMarkBG = new BranchGroup();
		tropoMarkBG = new BranchGroup();
		plusCapMarkBG = new BranchGroup();
		rotT3D = new Transform3D();
		rotMat = new Matrix3d();
		// set capabilities
		monHelixG.setCapability(BranchGroup.ALLOW_DETACH);
		monSphereG.setCapability(BranchGroup.ALLOW_DETACH);
		theMonLine.setCapability(LineArray.ALLOW_COORDINATE_WRITE);
		monShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		monShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		monTG.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
		monSphere = new Sphere((float)(Env.actinMonoRadius),Sphere.GENERATE_NORMALS, Env.monomerTessalation, atpApp);
		monSphere.getShape().setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		
		monShape.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
		monShape.setCapability(Shape3D.ALLOW_GEOMETRY_WRITE);
		theMonLine.setCoordinate(0,Env.farfarAway);
		theMonLine.setCoordinate(1,Env.farfarAway);
		monShape.setGeometry(theMonLine);
		monHelixG.addChild(monShape);
		
		// Cofilin mark, currently using a text "V"
		Appearance textAppear = new Appearance();
	    textAppear.setMaterial(cofMat);
	    // Create a simple shape leaf node, add it to the scene graph.
	    Font3D font3D = new Font3D(new Font("Helvetica", Font.PLAIN, 1),new FontExtrusion());
	    Text3D cofilinText = new Text3D(font3D, new String("V"));
	    cofilinText.setAlignment(Text3D.ALIGN_CENTER);
	    Shape3D cofilinShape = new Shape3D();
	    cofilinShape.setGeometry(cofilinText);
	    cofilinShape.setAppearance(textAppear);
	    Transform3D cofilinT3D = new Transform3D();
	    TransformGroup cofilinTG = new TransformGroup();
	    cofilinT3D.rotX(Math.PI/2.0);
	    cofilinT3D.setScale(0.01);
	    cofilinT3D.setTranslation(new Vector3d(0,.000,0.002));
	    cofilinTG.setTransform(cofilinT3D);
	    cofilinTG.addChild(cofilinShape);
	    cofilinMarkBG.setCapability(BranchGroup.ALLOW_DETACH);
	    cofilinMarkBG.addChild(cofilinTG);
	    
	 // Tropomyosin mark, currently using a text "-"
 		Appearance tropoTextAppear = new Appearance();
 	    tropoTextAppear.setMaterial(tropoMat);
 	    // Create a simple shape leaf node, add it to the scene graph.
 	    Font3D tropoFont3D = new Font3D(new Font("Helvetica", Font.PLAIN, 2),new FontExtrusion());
 	    Text3D tropoText = new Text3D(tropoFont3D, new String("|"));
 	    tropoText.setAlignment(Text3D.ALIGN_CENTER);
 	    Shape3D tropoShape = new Shape3D();
 	    tropoShape.setGeometry(tropoText);
 	    tropoShape.setAppearance(tropoTextAppear);
 	    Transform3D tropoT3D = new Transform3D();
 	    TransformGroup tropoTG = new TransformGroup();
 	    tropoT3D.rotZ(Math.PI/2.0);
 	    tropoT3D.setScale(0.008);
 	    tropoT3D.setTranslation(new Vector3d(0,.000,0.003));
 	    tropoTG.setTransform(tropoT3D);
 	    tropoTG.addChild(tropoShape);
 	    tropoMarkBG.setCapability(BranchGroup.ALLOW_DETACH);
 	    tropoMarkBG.addChild(tropoTG);
 	    
 	 // Plus-End Cap mark, currently using a transparent box
 		Appearance plusCapApp = new Appearance();
 	    plusCapApp.setMaterial(cofMat);
 	    TransparencyAttributes capTA = new TransparencyAttributes(TransparencyAttributes.FASTEST,0.3f);
 	    plusCapApp.setTransparencyAttributes(capTA);
 	    capBox = new Box((float)0.02,(float)0.02,(float)0.02,plusCapApp);
 	    //Shape3D plusCapShape = new Shape3D();
 	    //plusCapShape.setGeometry(plusCapText);
 	    //plusCapShape.setAppearance(plusCapTextAppear);
 	    Transform3D plusCapT3D = new Transform3D();
 	    TransformGroup plusCapTG = new TransformGroup();
 	    plusCapT3D.rotZ(Math.PI/2.0);
 	    plusCapT3D.setScale(0.2);
 	    //plusCapT3D.setTranslation(new Vector3d(0.00,0.0,0.0));
 	    plusCapTG.setTransform(plusCapT3D);
 	    plusCapTG.addChild(capBox);
 	    plusCapMarkBG.setCapability(BranchGroup.ALLOW_DETACH);
 	    plusCapMarkBG.addChild(plusCapTG);
	
		monTG.addChild(monSphere);
		monTG.setCapability(TransformGroup.ALLOW_CHILDREN_EXTEND);
		monTG.setCapability(TransformGroup.ALLOW_CHILDREN_WRITE);
		monTG.setTransform(monT3D);
		monSphereG.addChild(monTG);
		
			
		chooseBiochemAppearance();
		
		graphicsInitialized = true;
	}
	
	public void resetGraphics (FilSegment fil) {
		if (Env.helixSpheres) {
			monHelixG.detach();
			fil.G.addChild(monSphereG);
		} else {
			monSphereG.detach();
			fil.G.addChild(monHelixG);
		}
	}
	
	public void reassignGraphics (FilSegment oldFil, FilSegment newFil) {
		if (Env.helixSpheres) {
			oldFil.G.removeChild(monSphereG);
			newFil.G.addChild(monSphereG);
		} else {
			oldFil.G.removeChild(monHelixG);
			newFil.G.addChild(monHelixG);
		}
	}
	
	public void updateGraphics (FilSegment myFil, Pt3D start, Pt3D stop, boolean endCap) {
		if (!Env.helixSpheres) {
			theMonLine.setCoordinate(0,start);
			theMonLine.setCoordinate(1,stop);
			chooseBiochemAppearance();
		} else {
			chooseBiochemAppearance();
			
			// cofilin graphics on and off, as appropriate
			if (cofilinOn & !cofilinMarkOn) { monTG.addChild(cofilinMarkBG); cofilinMarkOn = true; } 
			if (!cofilinOn & cofilinMarkOn) { monTG.removeChild(cofilinMarkBG); cofilinMarkOn = false; }
			
			// tropomyosin graphics on and off, as appropriate
			if (tropoOn & !tropoMarkOn) { monTG.addChild(tropoMarkBG); tropoMarkOn = true; }
			if (!tropoOn & tropoMarkOn) { monTG.removeChild(tropoMarkBG); tropoMarkOn = false; }
			
			// end cap graphics on and off, as appropriate
			if (endCap & !plusCapMarkOn) { monTG.addChild(plusCapMarkBG); plusCapMarkOn = true; }
			if (!endCap & plusCapMarkOn) { monTG.removeChild(plusCapMarkBG); plusCapMarkOn = false; }
			
			start.copyToVector3d(monVec3D);
			monT3D.setTranslation(monVec3D);
			curMat.set(myFil.mxToX);
			curMat.mul(rotMat);
			monT3D.setRotation(curMat);
			monTG.setTransform(monT3D);
		}
	}  
	
	public void setFromQKInfo (Pt3D loc, int state, double monRot, boolean cofilinIsOn) {
		loc.copyToVector3d(monVec3D);
		setState(state);
		cofilinOn = cofilinIsOn;
		chooseBiochemAppearance();
		if (cofilinOn & !cofilinMarkOn) { monTG.addChild(cofilinMarkBG); cofilinMarkOn = true; }
		if (!cofilinOn) { cofilinMarkBG.detach(); cofilinMarkOn = false; }
		monT3D.setTranslation(monVec3D);
		xRotAng = monRot;
		rotT3D.rotX(xRotAng);
		rotT3D.getRotationScale(rotMat);
		monT3D.setRotation(rotMat);
		monTG.setTransform(monT3D);
	}
	
	public void setToFarAway () {
		monVec3D.set(1e6,1e6,1e6);
		monT3D.setTranslation(monVec3D);
		monTG.setTransform(monT3D);
	}
	
	public void updatePosition (Pt3D loc) {
		loc.copyToVector3d(monVec3D);
	}
	
	
	public void addGraphics (FilSegment fil) {
		if (Env.helixSpheres) {
			rotT3D.rotX(xRotAng);
			rotT3D.getRotationScale(rotMat);
			fil.G.addChild(monSphereG);
		} else {
			fil.G.addChild(monHelixG);
		}
	}
	
	public void addGraphics (BranchGroup baseG) {
		if (Env.helixSpheres) {
			baseG.addChild(monSphereG);
		} else {
			baseG.addChild(monHelixG);
		}
		graphicsIn = true;
	}
	
	public void detachGraphics () {
		if (Env.monomerGraphics) {
			monSphereG.detach();
			graphicsIn = false;
		}
	}

	public void sepaku () {
		removeMe = true;  
		// nullify objects so this object can be garbage collected
		frontMon = null;
		backMon = null;
		
		// graphics
		if (Env.monomerGraphics) {
			monHelixG.detach();
			monSphereG.detach();
		}
		monHelixG = null;
		monSphereG = null;
		theMonLine = null;
		monShape = null;
		monSphere = null;
		capBox = null;
		monTG = null;
		monT3D = null;
		monVec3D = null;
		cofilinMarkBG = null;
		tropoMarkBG = null;
		plusCapMarkBG = null;
		rotT3D = null;
		rotMat = null;
	}
	
	public static void removeAll() {
		for (int i=0;i<monCt;i++) {
			theMonomers[i].detachGraphics();
			theMonomers[i].sepaku();
		}
	}
	
	
}

