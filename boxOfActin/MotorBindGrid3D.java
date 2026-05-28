package boxOfActin;

/**
 * MotorBindGrid3D — 3D spatial hash grid for motor-filament collision detection.
 *
 * Replaces the 2D MYOHEADS_MESH / FILSEG_MESH pair with a true 3D cubic grid
 * at the same cell size (0.2 µm). Filled once per step from SoA arrays; queried
 * by CkMotsThreads in place of motorFilMeshCollisions().
 *
 * CPU rewrite step 1a.
 */
public class MotorBindGrid3D {

    public static MotorBindGrid3D INSTANCE;

    // Cell size matches Mesh.SIZE
    static final float CELL_SIZE = Mesh.SIZE;   // 0.2 µm
    static final int   BIN_DEPTH = Mesh.BIN_DEPTH;

    // Grid dimensions (computed in create())
    final int nXBins, nYBins, nZBins;
    final float xMin, yMin, zMin;

    // Filament segments per cell
    final int[][][][] filCells;
    final int[][][]   filActiveCts;
    final int[][][]   filTimeStamps;

    // Motor heads per cell
    final int[][][][] motorCells;
    final int[][][]   motorActiveCts;
    final int[][][]   motorTimeStamps;

    // Per-cell sync objects
    final Object[][][] filCellSync;
    final Object[][][] motorCellSync;

    // Timestamp of last fill (compared to Env.counter to detect stale cells)
    static int lastWriteTime = 0;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private MotorBindGrid3D() {
        float xDim = Env.boxXDim.getFloatValue();
        float yDim = Env.boxYDim.getFloatValue();
        float zDim = Env.boxZDim.getFloatValue();

        xMin = -xDim / 2f;
        yMin = -yDim / 2f;
        zMin = -zDim / 2f;

        nXBins = 1 + (int) Math.ceil(xDim / CELL_SIZE);
        nYBins = 1 + (int) Math.ceil(yDim / CELL_SIZE);
        nZBins = 1 + (int) Math.ceil(zDim / CELL_SIZE);

        System.out.println("MotorBindGrid3D: " + nXBins + " x " + nYBins + " x " + nZBins
                + " cells (" + CELL_SIZE + " µm each)");

        filCells      = new int[nXBins][nYBins][nZBins][BIN_DEPTH];
        filActiveCts  = new int[nXBins][nYBins][nZBins];
        filTimeStamps = new int[nXBins][nYBins][nZBins];

        motorCells      = new int[nXBins][nYBins][nZBins][BIN_DEPTH];
        motorActiveCts  = new int[nXBins][nYBins][nZBins];
        motorTimeStamps = new int[nXBins][nYBins][nZBins];

        filCellSync   = new Object[nXBins][nYBins][nZBins];
        motorCellSync = new Object[nXBins][nYBins][nZBins];
        for (int x = 0; x < nXBins; x++) {
            for (int y = 0; y < nYBins; y++) {
                for (int z = 0; z < nZBins; z++) {
                    filCellSync[x][y][z]   = new Object();
                    motorCellSync[x][y][z] = new Object();
                }
            }
        }
    }

    public static void create() {
        INSTANCE = new MotorBindGrid3D();
    }

    // -----------------------------------------------------------------------
    // Bin-index helpers
    // -----------------------------------------------------------------------

    int getBinX(double val) {
        int b = (int) ((val - xMin) / CELL_SIZE);
        if (b < 0) b = 0; else if (b >= nXBins) b = nXBins - 1;
        return b;
    }

    int getBinY(double val) {
        int b = (int) ((val - yMin) / CELL_SIZE);
        if (b < 0) b = 0; else if (b >= nYBins) b = nYBins - 1;
        return b;
    }

    int getBinZ(double val) {
        int b = (int) ((val - zMin) / CELL_SIZE);
        if (b < 0) b = 0; else if (b >= nZBins) b = nZBins - 1;
        return b;
    }

    // -----------------------------------------------------------------------
    // Cell addition helpers (timestamp pattern from Mesh.addToMesh)
    // -----------------------------------------------------------------------

    void addFilToCell(int cx, int cy, int cz, int filSegIdx) {
        int ts = Env.counter;
        lastWriteTime = ts;
        synchronized (filCellSync[cx][cy][cz]) {
            if (filTimeStamps[cx][cy][cz] != ts) {
                filTimeStamps[cx][cy][cz] = ts;
                filCells[cx][cy][cz][0] = filSegIdx;
                filActiveCts[cx][cy][cz] = 1;
            } else {
                int ct = filActiveCts[cx][cy][cz];
                if (ct < BIN_DEPTH) {
                    filCells[cx][cy][cz][ct] = filSegIdx;
                    filActiveCts[cx][cy][cz] = ct + 1;
                }
            }
        }
    }

    void addMotorToCell(int cx, int cy, int cz, int motorIdx) {
        int ts = Env.counter;
        synchronized (motorCellSync[cx][cy][cz]) {
            if (motorTimeStamps[cx][cy][cz] != ts) {
                motorTimeStamps[cx][cy][cz] = ts;
                motorCells[cx][cy][cz][0] = motorIdx;
                motorActiveCts[cx][cy][cz] = 1;
            } else {
                int ct = motorActiveCts[cx][cy][cz];
                if (ct < BIN_DEPTH) {
                    motorCells[cx][cy][cz][ct] = motorIdx;
                    motorActiveCts[cx][cy][cz] = ct + 1;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fill methods — bounding-box approach (no Bresenham needed for step 1a)
    // -----------------------------------------------------------------------

    void fillFilSeg(int filSegIdx, Pt3D end1, Pt3D end2) {
        int x0 = getBinX(Math.min(end1.x, end2.x));
        int x1 = getBinX(Math.max(end1.x, end2.x));
        int y0 = getBinY(Math.min(end1.y, end2.y));
        int y1 = getBinY(Math.max(end1.y, end2.y));
        int z0 = getBinZ(Math.min(end1.z, end2.z));
        int z1 = getBinZ(Math.max(end1.z, end2.z));
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    addFilToCell(x, y, z, filSegIdx);
                }
            }
        }
    }

    void fillMotor(int motorIdx, Pt3D bindTip) {
        double tol = Env.myoColTol.getValue();
        int x0 = getBinX(bindTip.x - tol);
        int x1 = getBinX(bindTip.x + tol);
        int y0 = getBinY(bindTip.y - tol);
        int y1 = getBinY(bindTip.y + tol);
        int z0 = getBinZ(bindTip.z - tol);
        int z1 = getBinZ(bindTip.z + tol);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    addMotorToCell(x, y, z, motorIdx);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Motor query — called from CkMotsThreads.execute()
    // -----------------------------------------------------------------------

    void motorFilCollisions(int motorStart, int motorStop) {
        for (int i = motorStart; i < motorStop; i++) {
            if (MyoMotor.soaOnFil[i]) continue;
            int cx = getBinX(MyoMotor.soaX[i]);
            int cy = getBinY(MyoMotor.soaY[i]);
            int cz = getBinZ(MyoMotor.soaZ[i]);
            for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx;
                if (nx < 0 || nx >= nXBins) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = cy + dy;
                    if (ny < 0 || ny >= nYBins) continue;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = cz + dz;
                        if (nz < 0 || nz >= nZBins) continue;
                        if (filTimeStamps[nx][ny][nz] != lastWriteTime) continue;
                        int ct = filActiveCts[nx][ny][nz];
                        for (int j = 0; j < ct; j++) {
                            // Flat-array fine check (step 1b): no object dereferencing in the hot path
                            MyoMotor.checkFilSegCollision(i, filCells[nx][ny][nz][j]);
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // FillThreads inner class — single-threaded fill of the 3D grid
    // -----------------------------------------------------------------------

    static class FillThreads extends ThreadSet {

        FillThreads() {
            super(1, "MotorBindGrid3D Fill");
        }

        @Override
        public void divideAndConquer(int jobId) {
            if (jobId == Env.motorBindGrid3DStart) {
                jobDiv[0] = 0;
                jobDiv[1] = 1;
                spawn();
            }
        }

        @Override
        public void regroup(int jobId) {
            if (jobId == Env.motorBindGrid3DStop) {
                gather();
            }
        }

        @Override
        public void execute(int threadId) {
            MotorBindGrid3D grid = INSTANCE;
            for (int i = 0; i < FilSegment.filSegmentCt; i++) {
                FilSegment fs = FilSegment.theFilSegments[i];
                grid.fillFilSeg(fs.filArrayPos, fs.end1, fs.end2);
            }
            for (int i = 0; i < MyoMotor.motorCt; i++) {
                MyoMotor m = MyoMotor.theMotors[i];
                grid.fillMotor(m.myMotorNumber, m.bindTip);
            }
        }

        static FillThreads fillThreads = new FillThreads();
    }
}
