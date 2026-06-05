package boxOfActin;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.*;


public class Mesh {
	public static Mesh FILSEG_MESH;
	public static Mesh NODE_MESH;
	public static Mesh MYOHEADS_MESH;
	public static final float SHIFT=0.49f*0;
	
	public static float MIN_LENGTH_FOR_LINE_ALGORITHM=200;
	public static float SIZE=(float)0.2;	// nm
	public static float X_BIN_WIDTH=SIZE;
	public static float Y_BIN_WIDTH=SIZE;
	public static float Z_BIN_WIDTH=SIZE;
	public static int BIN_DEPTH=1000;
	public static boolean OVERLAP=false;
	
	public double[][][] meshpoints;
	public int [][] timeStamps;
	public static int lastWriteTime = 0;
	public int [][] activeCts;
	public int[][] sections;
	public Object[][] mSync;	// array of objects for synchronizing mesh filling
	
	public static MeshThreads meshThreads = new MeshThreads();
	public static CkMeshThreads ckMeshThreads = new CkMeshThreads();
	public static CkMotsThreads ckMotsThreads = new CkMotsThreads();

	// Phase 4.5 diag (2026-06-05): per-step counters for end1AsPt3D reads from
	// MeshThreads.execute. fillFilSegMesh is dispatched every
	// collisionCheckInt steps from meshFilsStart. Its consumers (filSegMeshCollisions,
	// membraneFilMeshCollisions) are gated inert in gliding, but the FILL itself
	// reads end1AsPt3D / end2AsPt3D => Thing.soaEnd1[]/soaEnd2[] (frame-stale).
	public static long DIAG_MESH_FILL_FILSEG_CT = 0;
	
	public static float xBinWidth=X_BIN_WIDTH;
	public static float yBinWidth=Y_BIN_WIDTH;
	public static float zBinWidth=Z_BIN_WIDTH;
	
	public static float nXbinsFractional=Env.boxXDim.getFloatValue()/xBinWidth;
	public static float nYbinsFractional=Env.boxYDim.getFloatValue()/yBinWidth;
	public static int nXBins=1+(int)Math.ceil(nXbinsFractional);
	public static int nYBins=1+(int)Math.ceil(nYbinsFractional);
	public static int binDepth=BIN_DEPTH;
	
	public float xMinValue=-Env.boxXDim.getFloatValue()/2;
	public float xMaxValue=Env.boxXDim.getFloatValue()/2;
	
	public float yMinValue=-Env.boxYDim.getFloatValue()/2;
	public float yMaxValue=Env.boxYDim.getFloatValue()/2;
	
	public boolean showGraphics=false;

	public static void createMeshes(){
		System.out.print(
				"**************************************************************"+"\n"
				+"Eulerian Mesh stats:"+"\n"
				+"nXBins = "+nXBins+";"+" xBinWidth = "+xBinWidth+" µm"+"\n"
				+"nYBins = "+nYBins+";"+" yBinWidth = "+yBinWidth+" µm"+"\n"
				+"binDepth = "+binDepth+"\n"
				+"**************************************************************"+"\n"
		);
		
		FILSEG_MESH=new Mesh();
		NODE_MESH= new Mesh();
		MYOHEADS_MESH= new Mesh();
	}
	
	public Mesh(){
		meshpoints = new double[nXBins][nYBins][binDepth];
		timeStamps = new int[nXBins][nYBins];
		activeCts = new int[nXBins][nYBins];
		mSync = new Object[nXBins][nYBins];
	
		int totalCt=0;
		for(int x=0;x<meshpoints.length;x++){
			for(int y=0;y<meshpoints[x].length;y++){
				for(int i=0;i<meshpoints[x][y].length;i++){
					meshpoints[x][y][i]=-1;
					totalCt++;
				}
				mSync[x][y] = new Object();
			}
		}
	}
	
	static class MeshThreads extends ThreadSet {
		MeshThreads () {
			super (Env.numMeshThreads, "Mesh Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.meshFilsStart:
					if (FilSegment.filSegmentCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*FilSegment.filSegmentCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
				case Env.meshNodesStart:
					if (ProteinNode.nodeCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*ProteinNode.nodeCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
				case Env.meshMotorsStart:
					if (MyoMotor.motorCt == 0) return;
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*MyoMotor.motorCt/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.meshFilsStop:
					if (FilSegment.filSegmentCt == 0) return;
					gather(); break;
				case Env.meshNodesStop:
					if (ProteinNode.nodeCt == 0) return;
					gather(); break;
				case Env.meshMotorsStop:
					if (MyoMotor.motorCt == 0) return;
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.meshFilsStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						FilSegment curSeg = FilSegment.theFilSegments[i];
						if (Env.useGPU) DIAG_MESH_FILL_FILSEG_CT++;
						Mesh.FILSEG_MESH.fillFilSegMesh(curSeg.filArrayPos, curSeg.end1AsPt3D(), curSeg.end2AsPt3D());
					}
					break;
				case Env.meshNodesStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						Mesh.NODE_MESH.fillNodeMesh(ProteinNode.theNodes[i]);
					}
					break;
				case Env.meshMotorsStart:
					for (int i = jobDiv[threadId]; i < jobDiv[threadId+1]; i++) {
						Mesh.MYOHEADS_MESH.fillMotorMesh(MyoMotor.theMotors[i]);
					}
					break;
			}
		}
	}
	
	
	static class CkMeshThreads extends ThreadSet {
		CkMeshThreads () {
			super (Env.numMeshCollThreads, "Ck Mesh Threads");
		}
	
		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.meshCollStart:
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*Mesh.nXBins/numThreads;	// divide the job amongst threads
					}
					spawn(); break;
			}
			
		}
		
		public void regroup (int jobId) {
			switch (jobId) {
				case Env.meshCollStop:
					gather(); break;
			}
		}
		
		public void execute (int threadId) {
			switch (jobId) {
				case Env.meshCollStart:
					int xStart = jobDiv[threadId];
					int xStop = jobDiv[threadId+1];
					FilSegment.filSegMeshCollisions(xStart, xStop);
					if (Env.collideProteinNodes.isActive()) {ProteinNode.nodeMeshCollisions(xStart, xStop);}
					FilSegment.membraneFilMeshCollisions(xStart, xStop);
					break;
			}
		}
	}
	
	static class CkMotsThreads extends ThreadSet {
		CkMotsThreads () {
			super (Env.numMeshCollThreads, "Ck Mots Threads");
		}

		public void divideAndConquer (int jobId) {
			this.jobId = jobId;
			switch (jobId) {
				case Env.motCollStart:
					if (MyoMotor.motorCt == 0) return;
					// Divide by motor count for motor-centric 3D grid query
					for (int i=0; i <= numThreads; i++) {
						jobDiv[i] = i*MyoMotor.motorCt/numThreads;
					}
					spawn(); break;
			}

		}

		public void regroup (int jobId) {
			switch (jobId) {
				case Env.motCollStop:
					if (MyoMotor.motorCt == 0) return;
					gather(); break;
			}
		}

		public void execute (int threadId) {
			switch (jobId) {
				case Env.motCollStart:
					int motorStart = jobDiv[threadId];
					int motorStop  = jobDiv[threadId+1];
					MotorBindGrid3D.INSTANCE.motorFilCollisions(motorStart, motorStop);
					break;
			}
		}
	}
	
	public void lineBresenham(int x0, int y0, int x1, int y1,int id){
        int dy = y1 - y0;
        int dx = x1 - x0;
        int stepx, stepy;

        if (dy < 0) { dy = -dy;  stepy = -1; } else { stepy = 1; }
        if (dx < 0) { dx = -dx;  stepx = -1; } else { stepx = 1; }
        dy <<= 1;                                                  // dy is now 2*dy
        dx <<= 1;                                                  // dx is now 2*dx

        fillMeshCell(id,x0,y0);
        if (dx > dy) {
            int fraction = dy - (dx >> 1);                         // same as 2*dy - dx
            while (x0 != x1) {
                if (fraction >= 0) {
                    y0 += stepy;
                    fraction -= dx;                                // same as fraction -= 2*dx
                }
                x0 += stepx;
                fraction += dy;                                    // same as fraction -= 2*dy
                fillMeshCell(id,x0,y0);
            }
        } else {
            int fraction = dx - (dy >> 1);
            while (y0 != y1) {
                if (fraction >= 0) {
                    x0 += stepx;
                    fraction -= dy;
                }
                y0 += stepy;
                fraction += dx;
                fillMeshCell(id,x0,y0);
            }
        }
	}

	public void fillMeshCell(int id,int x0,int y0){
		for(int x=x0-1;x<x0+1;x++){
			for(int y=y0-1;y<y0+1;y++){
				if(x>=0&&x<nXBins&&y>=0&&y<nYBins){
					addToMesh(x,y,id);
				}
			}
		}
	}
	
	//the mesh needs to be changed so that someone can remove themself from the mesh and then add themselves as well what about when someone
	//changes there id because they have been swapped an easy solution is to have everyone be removed at the begining of the timestep
	//that way they can be removed if need be.  it does not matter if you are removed at the begining of the timestep
	//the problem is that you move and then report yourself to the mesh, well what if someone was to have removed you, you died
	//at the end of your step, then you should not report yourself to the mesh.  so if you are reporting yourself to the mesh
	//then you should not be removed during this time step.  we could also have a meshinfopacket telling our start stops and id
	//then if you are reported to the mesh and you die the remove thing method removes you from the mesh
	
	public void addToMesh (int x, int y, int id) {
		int timeToSet = Env.counter;
		lastWriteTime = timeToSet;
		synchronized (mSync[x][y]) {
			if(timeStamps[x][y]!=timeToSet){
				timeStamps[x][y]=timeToSet;
				meshpoints[x][y][0]=id;
				activeCts[x][y]=1;
			} else {
				meshpoints[x][y][activeCts[x][y]]=id;
				activeCts[x][y]++;
				if (activeCts[x][y] >= Mesh.BIN_DEPTH) { activeCts[x][y]=Mesh.BIN_DEPTH-1; }
			}
		}
	}
	
	public int getBinX (double val) {
		int binX=(int)(((val-xMinValue)/xBinWidth)+SHIFT);
		if(binX<0)binX=0;
		else if(binX>=nXBins)binX=nXBins-1;
		return binX;
	}
	
	public int getBinY (double val) {
		int binY=(int)(((val-yMinValue)/yBinWidth)+SHIFT);
		if(binY<0)binY=0;
		else if(binY>=nYBins)binY=nYBins-1;
		return binY;
	}
	
	public void fillFilSegMesh (int filSegNum, Pt3D startPt, Pt3D stopPt){
		int id=filSegNum;
		double dxLen = startPt.x-stopPt.x, dyLen = startPt.y-stopPt.y;
		double segLength = Math.sqrt(dxLen*dxLen + dyLen*dyLen);
		boolean useLineAlgorithm=segLength>MIN_LENGTH_FOR_LINE_ALGORITHM;
		
		//X AXIS
		int startBinX = getBinX(startPt.x);
		int stopBinX = getBinX(stopPt.x);
		
		if(!useLineAlgorithm&&startBinX>stopBinX){
			int temp=stopBinX;
			stopBinX=startBinX;
			startBinX=temp;
		}
		
		//Y AXIS
		int startBinY = getBinY(startPt.y);
		int stopBinY = getBinY(stopPt.y);
		
		if(!useLineAlgorithm&&startBinY>stopBinY){
			int temp=stopBinY;
			stopBinY=startBinY;
			startBinY=temp;
		}
		
		if(OVERLAP){
			startBinX--;
			stopBinX++;
			if(startBinX<0)startBinX=0;
			if(stopBinX>=nXBins)stopBinX=nXBins-1;
			
			startBinY--;
			stopBinY++;
			if(startBinY<0)startBinY=0;
			if(stopBinY>=nYBins)stopBinY=nYBins-1;
		}
	
		//System.out.println("start = (" + startBinX + "," + startBinY + ") - stop = (" + stopBinX + "," + stopBinY + ")");
		//FILL BINS
		if(useLineAlgorithm){
			lineBresenham(startBinX,startBinY,stopBinX,stopBinY,id);
			//lineTwoStep(startBinX,startBinY,stopBinX,stopBinY,id);
			//s(startBinX,startBinY,stopBinX,stopBinY,id);
		} else {
			for(int x=startBinX;x<=stopBinX;x++){
				for(int y=startBinY;y<=stopBinY;y++){
					addToMesh(x,y,id);
				}
			}
		}
	}
	
	
	public void fillNodeMesh (ProteinNode node){
	
		int id=node.myNodeNumber;
	
		//X AXIS
		double startValue=node.getCoordX()-node.getRadius();
		double stopValue=node.getCoordX()+node.getRadius();			
		
		int startBinX = getBinX(startValue);
		int stopBinX = getBinX(stopValue);
				
		if(startBinX>stopBinX){
			int temp=stopBinX;
			stopBinX=startBinX;
			startBinX=temp;
		}
	
		//Y AXIS
		startValue=node.getCoordY()-node.getRadius();
		stopValue=node.getCoordY()+node.getRadius();			
		
		int startBinY = getBinY(startValue);
		int stopBinY = getBinY(stopValue);
					
		if(startBinY>stopBinY){
			int temp=stopBinY;
			stopBinY=startBinY;
			startBinY=temp;
		}
		
		if(OVERLAP){
			startBinX--;
			stopBinX++;
			if(startBinX<0)startBinX=0;
			if(stopBinX>=nXBins)stopBinX=nXBins-1;
			
			startBinY--;
			stopBinY++;
			if(startBinY<0)startBinY=0;
			if(stopBinY>=nYBins)stopBinY=nYBins-1;
		}
		
		//FILL BINS
		for(int x=startBinX;x<=stopBinX;x++){
			for(int y=startBinY;y<=stopBinY;y++){
				addToMesh(x,y,id);				
			}
		}
	}
	
	public void fillMotorMesh (MyoMotor motor){
		
		int id=motor.myMotorNumber;
	
		//X AXIS
		double startValue=motor.bindTip.x-Env.myoColTol.getValue();
		double stopValue=motor.bindTip.x+Env.myoColTol.getValue();			
		
		int startBinX = getBinX(startValue);
		int stopBinX = getBinX(stopValue);
				
		if(startBinX>stopBinX){
			int temp=stopBinX;
			stopBinX=startBinX;
			startBinX=temp;
		}
	
		//Y AXIS
		startValue=motor.bindTip.y-Env.myoColTol.getValue();
		stopValue=motor.bindTip.y+Env.myoColTol.getValue();			
		
		int startBinY = getBinY(startValue);
		int stopBinY = getBinY(stopValue);
					
		if(startBinY>stopBinY){
			int temp=stopBinY;
			stopBinY=startBinY;
			startBinY=temp;
		}
		
		if(OVERLAP){
			startBinX--;
			stopBinX++;
			if(startBinX<0)startBinX=0;
			if(stopBinX>=nXBins)stopBinX=nXBins-1;
			
			startBinY--;
			stopBinY++;
			if(startBinY<0)startBinY=0;
			if(stopBinY>=nYBins)stopBinY=nYBins-1;
		}
		
		//FILL BINS
		for(int x=startBinX;x<=stopBinX;x++){
			for(int y=startBinY;y<=stopBinY;y++){
				addToMesh(x,y,id);				
			}
		}
	}
	
	
	public static void reportMeshActives () {
		//System.out.println ("CortSeg_Mesh actives = " + CORTSEG_MESH.countActiveEntries());
		NODE_MESH.tableOfActives();
		System.out.println ("Total Cadherin_Mesh actives = " + NODE_MESH.countActiveEntries());
	}
	
	public int countActiveEntries () {
		int numActive = 0;
		for (int x=0;x<nXBins;x++) {
			for (int y=0;y<nYBins;y++) {
				if (timeStamps[x][y] == Env.counter) {
					numActive += activeCts[x][y];
				}
			}
		}
		return numActive;
	}
	
	public void tableOfActives () {
		for (int x=0;x<nXBins;x++) {
			for (int y=0;y<nYBins;y++) {
				if (timeStamps[x][y] == Env.counter) {
					System.out.print("{" + x + "," + y + "} = " + activeCts[x][y]);
				} else {
					System.out.print("{" + x + "," + y + "} = " + 0);
				}
				System.out.println();
			}
		}
	}
	
	public void print(int x,int y,int z){
		System.out.println("ENV.SIM_TIME="+Env.simulationTime+"**************************************************************");
		
		System.out.println("["+x+"]"+"["+y+"]"+"["+z+"]"+"\n"
				+"\t"+"[TIMESTAMP]"+" = "+timeStamps[x][y]
				+"  [COUNT]"+" = "+activeCts[x][y]);

		int idStop=activeCts[x][y];
		if(idStop>=meshpoints[x][y].length)idStop=meshpoints[x][y].length;
		for(int id=0;id<idStop;id++){
			System.out.println("\t"+"i="+id+"["+x+"]"+"["+y+"]"+"["+z+"]"+"[ID]"+" = "+meshpoints[x][y][id]);
		}
		System.out.println("**************************************************************");
	}
	
	public void print(){
		System.out.println("ENV.SIM_TIME="+Env.simulationTime+"**************************************************************");
		for(int x=0;x<meshpoints.length;x++){
			for(int y=0;y<meshpoints[x].length;y++){
				for(int z=0;z<meshpoints[x][y].length;z++){
					System.out.println("["+x+"]"+"["+y+"]"+"["+z+"]"+"\n"
							+"\t"+"[TIMESTAMP]"+" = "+timeStamps[x][y]
							+"  [COUNT]"+" = "+activeCts[x][y]
					);
					int idStop=activeCts[x][y];
					//idStop=meshpoints[x][y][z].length;
					for(int id=0;id<idStop;id++){
						System.out.println("\t"+"i="+id+"["+x+"]"+"["+y+"]"+"["+z+"]"+"[ID]"+" = "+meshpoints[x][y][id]);
					}
				}
			}
		}
		System.out.println("**************************************************************");
	}
	
}
