package boxOfActin;
import java.awt.Font;


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
	
	boolean graphicsInitialized = false;
	boolean cofilinMarkOn = false; // for graphics only
	boolean tropoMarkOn	= false; 	// for graphics only
	boolean plusCapMarkOn = false;  // for graphics bookkeeping only
	boolean isArp = false;  // special flag for denoting first two monomers in Arp2/3 bound filament as ARPs
	double xRotAng = 0;
	static double xRotAngInc = 0.7*Env.helixAngInc;
	
	// testing
	static double kHydroRate;
	static double kDissocRate;
	
	Monomer frontMon = null;
	Monomer backMon = null;
	
	static Monomer plusGhost = new Monomer ();
	static Monomer minusGhost = new Monomer ();
	
	public Monomer(){}
	
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
		
	
	public void chooseBiochemAppearance() {}

	public static void initializeAllAppearances () {}
	
	public void initializeGraphics () {}
	
	public void resetGraphics (FilSegment fil) {}

	public void reassignGraphics (FilSegment oldFil, FilSegment newFil) {}

	public void updateGraphics (FilSegment myFil, Pt3D start, Pt3D stop, boolean endCap) {}

	public void setFromQKInfo (Pt3D loc, int state, double monRot, boolean cofilinIsOn) {}

	public void setToFarAway () {}

	public void updatePosition (Pt3D loc) {}

	public void addGraphics (FilSegment fil) {}

	public void detachGraphics () {}

	public void sepaku () {
		removeMe = true;
		frontMon = null;
		backMon = null;
	}
	
	public static void removeAll() {
		for (int i=0;i<monCt;i++) {
			theMonomers[i].detachGraphics();
			theMonomers[i].sepaku();
		}
	}
	
	
}

