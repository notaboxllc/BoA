package boxOfActin;


public class AnchorNode extends ProteinNode {
	double anchorRad = .005; // �m

	public AnchorNode (Pt3D initCoord) {
		super(initCoord,false);
		// set drags to some semblence of immobile
		bTransGam.setVals(1e6,1e6,1e6);
		
	}
	
	public void step() {
		
	}
	
	public void moveThing() { 
		
	}
	
	public double getRadius () {
		return anchorRad;
	}
	
	public void makeMyosinSinglets () {}
	
	public void makeMyosinDimers() {}
	
	public void updateMyosinPositions() {}
	
	public void keepMyosinsOnSurface() {}
	
	public void updateMyosinDimerPositions() {}
	
	public void keepMyosinDimersOnSurface() {}
	
	
}
