package boxOfActin;

import java.io.*;
import java.util.*;
import java.util.function.IntConsumer;

/**
 * Per-filament velocity and duty-ratio evaluator for the gliding assay.
 * Patterned after ANM3's GlidingAssayEvaluator, adapted for BoA's 3D MyosinFixed setup.
 *
 * Lifecycle:
 *   GlidingAssayEvaluator.create() called from BoxOfActin.begin() when glidingAssay is active.
 *   sampleStep() called every timestep (in logAndDraw / remoteLog) to accumulate bound counts.
 *   outputInterval() called at every 3JS output interval to compute velocity and write/dispatch.
 */
public class GlidingAssayEvaluator {

    // Reach from a MyosinFixed pin point to a filament axis (µm).
    // 0.1 µm default: actual geometric reach ≈ myoRodLength + myoLeverLength + myoMotorLength/2
    // ≈ 0.218 µm, but orientation varies; 0.1 µm is a conservative cylinder radius.
    static final double MOTOR_REACH_UM = 0.1;

    // Target window for the long-window (smoothed) velocity estimate in seconds.
    // Buffer size is computed at first outputInterval() call from toFileInterval * deltaT.
    // If toFileInterval changes mid-run, newer samples have different spacing — the approximation
    // degrades but does not break (the endpoint slope is still a valid distance/time estimate).
    static final double LONG_WINDOW_SECONDS = 1.0;

    private static class FilamentState {
        double boundMotorSum;
        int sampleCount;
        final Pt3D prevPos = new Pt3D();
        double prevTime;
        boolean initialized;

        // Long-window velocity ring buffer — allocated once bufCap is known.
        double[] lwBufX, lwBufY, lwBufZ, lwBufTime;
        int lwBufHead;    // index of next write slot
        int lwBufCount;   // number of valid entries (0..bufCap)
        double longWindowSpeedXY;
        boolean settling; // true until buffer has filled for the first time
    }

    private static GlidingAssayEvaluator instance;

    public static GlidingAssayEvaluator getInstance() { return instance; }

    public java.util.Set<? extends Map.Entry<Integer, ?>> filStatesEntrySet() { return filStates.entrySet(); }

    public double getLongWindowSpeedXY(int fid) {
        FilamentState s = filStates.get(fid);
        return s == null ? 0.0 : s.longWindowSpeedXY;
    }

    public static void create() {
        instance = new GlidingAssayEvaluator();
    }

    // Per-filament state keyed by filID.
    private final Map<Integer, FilamentState> filStates = new LinkedHashMap<>();
    private PrintWriter dataWriter;
    private boolean headerWritten;
    public int densityIndex;

    // Ring buffer capacity; computed once on first outputInterval() call.
    // -1 means not yet computed.
    private int bufCap = -1;

    // Spatial grid of MyosinFixed pin positions. Pins are set at construction
    // (myFixedPt is copied from rodEnd1) and never move, so the grid is built
    // lazily on first outputInterval() call and reused. Rebuilt only if
    // MyoMotor.motorCt changes (which doesn't happen in pure gliding).
    // Replaces the O(M·F·S) nested loops at lines 142+ and 238+ with O(M_seg ·
    // neighbours) — at 8× this cut step-0 wall from ~800 s to negligible.
    private static final double PIN_BIN_SIZE = MOTOR_REACH_UM;
    private int[][] pinGridBins;                // flat-indexed bin → motor indices
    private int pinGridNx, pinGridNy, pinGridNz;
    private double pinGridX0, pinGridY0, pinGridZ0;
    private int pinGridBuiltAtMotorCt = -1;

    // Per-call scratch (sized motorCt). motorInReach tracks the population set;
    // motorFidStamp gives each filament a unique "visit stamp" so we count
    // distinct motors per filament without per-fil resets.
    private boolean[] motorInReach;
    private int[] motorFidStamp;

    private GlidingAssayEvaluator() {
        densityIndex = 0;
    }

    // Opens the data file lazily after ThreeJSWriter has finalized the output directory name.
    private void ensureFileOpen() {
        if (dataWriter != null) return;
        String dir = Env.threeJSOutputDir != null ? Env.threeJSOutputDir
                   : Env.logFolderPath != null ? Env.logFolderPath : ".";
        try {
            dataWriter = new PrintWriter(new BufferedWriter(
                new FileWriter(dir + File.separator + "gliding_assay.dat")));
        } catch (IOException e) {
            System.err.println("[GlidingAssay] Cannot open data file: " + e.getMessage());
        }
    }

    /**
     * Called every timestep to accumulate per-filament bound-motor counts.
     * O(filSegmentCt + motorCt) — fast for typical gliding-assay populations.
     */
    public void sampleStep() {
        // Increment sampleCount for each live filament group (one increment per filID per step).
        Set<Integer> seenFids = new HashSet<>();
        for (int i = 0; i < FilSegment.filSegmentCt; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null) continue;
            int fid = fs.filID;
            if (seenFids.add(fid)) {
                filStates.computeIfAbsent(fid, k -> new FilamentState()).sampleCount++;
            }
        }

        // Count bound MyosinFixed motors and accumulate per filament.
        for (int i = 0; i < MyoMotor.motorCt; i++) {
            MyoMotor m = MyoMotor.theMotors[i];
            if (m == null || !m.onFil) continue;
            if (!(m.myMyosin instanceof MyosinFixed)) continue;
            if (m.tipLink == null || m.tipLink.mySeg == null) continue;
            int fid = m.tipLink.mySeg.filID;
            FilamentState state = filStates.get(fid);
            if (state != null) state.boundMotorSum++;
        }
    }

    /**
     * Called at each 3JS output interval. Computes per-filament velocity and duty ratio,
     * writes one data row per filament, and returns a JSON string for WebSocket dispatch.
     * Returns null if no gliding filaments are present yet.
     */
    public String outputInterval() {
        ensureFileOpen();

        // Compute ring-buffer capacity on first call using current parameter values.
        // toFileInterval is mutable at runtime; we snapshot it here once.
        if (bufCap < 0) {
            double dtPerInterval = Env.toFileInterval.getIntValue() * Env.deltaT.getValue();
            bufCap = Math.max(2, (int) Math.round(LONG_WINDOW_SECONDS / dtPerInterval));
        }

        double simTime = Env.simulationTime;
        double surfaceDensity = Env.fixedMyosinDensity.getValue();

        // Collect live filament segments grouped by filID.
        Map<Integer, List<FilSegment>> filGroups = new LinkedHashMap<>();
        for (int i = 0; i < FilSegment.filSegmentCt; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null) continue;
            filGroups.computeIfAbsent(fs.filID, k -> new ArrayList<>()).add(fs);
        }
        if (filGroups.isEmpty()) return null;

        // Build / refresh the spatial grid of MyosinFixed pin positions and
        // resize per-call scratch arrays.
        ensurePinGrid();
        int motorCtNow = MyoMotor.motorCt;
        if (motorInReach == null || motorInReach.length < motorCtNow) {
            motorInReach = new boolean[motorCtNow];
        } else {
            Arrays.fill(motorInReach, 0, motorCtNow, false);
        }
        if (motorFidStamp == null || motorFidStamp.length < motorCtNow) {
            motorFidStamp = new int[motorCtNow];
        } else {
            Arrays.fill(motorFidStamp, 0, motorCtNow, 0);
        }

        // Population-level heads-within-reach via spatial walk over segments.
        // Each segment queries pin bins overlapping its AABB extended by
        // MOTOR_REACH_UM; the visitor marks every pin truly within reach.
        for (List<FilSegment> segs : filGroups.values()) {
            for (FilSegment fs : segs) {
                collectMotorsNearSegment(fs, motorIdx -> motorInReach[motorIdx] = true);
            }
        }
        int withinReachTotal = 0, withinReachBound = 0;
        for (int i = 0; i < motorCtNow; i++) {
            if (!motorInReach[i]) continue;
            withinReachTotal++;
            MyoMotor m = MyoMotor.theMotors[i];
            if (m != null && m.onFil) withinReachBound++;
        }
        double headsWithinReachDR = withinReachTotal > 0 ? (double) withinReachBound / withinReachTotal : 0.0;

        // Write header on first output.
        if (!headerWritten && dataWriter != null) {
            dataWriter.println(
                "simTime\tdensityIndex\tsurfaceDensity\tfilamentId\tfilamentLength\t" +
                "posX\tposY\tposZ\tdistMoved\tvecMovedX\tvecMovedY\tvecMovedZ\t" +
                "instantaneousSpeed\tlongWindowSpeedXY\tlongWindowSettling\t" +
                "avgBoundMotors\tfootprintMotors\tfootprintDutyRatio\theadsWithinReachDR");
            headerWritten = true;
        }

        // Build JSON payload.
        StringBuilder json = new StringBuilder(256);
        json.append(String.format(
            "{\"simTime\":%.5g,\"densityIndex\":%d,\"surfaceDensity\":%.2f," +
            "\"headsWithinReachDR\":%.4f,\"filaments\":[",
            simTime, densityIndex, surfaceDensity, headsWithinReachDR));

        boolean firstFil = true;
        int filStamp = 0; // unique per-filament visit stamp for footprint counting
        for (Map.Entry<Integer, List<FilSegment>> entry : filGroups.entrySet()) {
            int fid = entry.getKey();
            List<FilSegment> segs = entry.getValue();

            // Filament center-of-mass and total contour length.
            double cx = 0, cy = 0, cz = 0, totalLen = 0;
            for (FilSegment fs : segs) {
                cx += fs.getCoordX(); cy += fs.getCoordY(); cz += fs.getCoordZ();
                totalLen += fs.length;
            }
            cx /= segs.size(); cy /= segs.size(); cz /= segs.size();

            FilamentState state = filStates.get(fid);
            if (state == null) { state = new FilamentState(); filStates.put(fid, state); }

            double avgBound = state.sampleCount > 0 ? state.boundMotorSum / state.sampleCount : 0.0;
            double distMoved = 0, vmX = 0, vmY = 0, vmZ = 0, speed = 0;

            if (state.initialized) {
                double dt = simTime - state.prevTime;
                vmX = cx - state.prevPos.x;
                vmY = cy - state.prevPos.y;
                vmZ = cz - state.prevPos.z;
                distMoved = Math.sqrt(vmX*vmX + vmY*vmY + vmZ*vmZ);
                speed = dt > 1e-12 ? distMoved / dt : 0.0;
            }

            // Long-window ring buffer: add current position/time, then compute endpoint slope.
            if (state.lwBufX == null) {
                state.lwBufX    = new double[bufCap];
                state.lwBufY    = new double[bufCap];
                state.lwBufZ    = new double[bufCap];
                state.lwBufTime = new double[bufCap];
            }
            state.lwBufX[state.lwBufHead]    = cx;
            state.lwBufY[state.lwBufHead]    = cy;
            state.lwBufZ[state.lwBufHead]    = cz;
            state.lwBufTime[state.lwBufHead] = simTime;
            state.lwBufHead = (state.lwBufHead + 1) % bufCap;
            if (state.lwBufCount < bufCap) state.lwBufCount++;
            state.settling = (state.lwBufCount < bufCap);

            if (state.lwBufCount >= 2) {
                int newest = (state.lwBufHead - 1 + bufCap) % bufCap;
                int oldest = (state.lwBufHead - state.lwBufCount + bufCap) % bufCap;
                double lwDx = state.lwBufX[newest] - state.lwBufX[oldest];
                double lwDy = state.lwBufY[newest] - state.lwBufY[oldest];
                // XY-only: matches 2D microscopy; Z stored in buffer for diagnostics
                double lwDist = Math.sqrt(lwDx*lwDx + lwDy*lwDy);
                double lwDt   = state.lwBufTime[newest] - state.lwBufTime[oldest];
                state.longWindowSpeedXY = lwDt > 1e-12 ? lwDist / lwDt : 0.0;
            } else {
                state.longWindowSpeedXY = 0.0;
            }

            // Footprint: MyosinFixed motors whose pin is within MOTOR_REACH_UM
            // of this filament. Spatial walk over this filament's segments; a
            // per-filament stamp dedupes motors touched by more than one seg.
            final int stamp = ++filStamp;
            final int[] footprintCt = { 0 };
            for (FilSegment fs : segs) {
                collectMotorsNearSegment(fs, motorIdx -> {
                    if (motorFidStamp[motorIdx] != stamp) {
                        motorFidStamp[motorIdx] = stamp;
                        footprintCt[0]++;
                    }
                });
            }
            int footprintMotors = footprintCt[0];
            double footprintDR = footprintMotors > 0 ? avgBound / footprintMotors : 0.0;

            // Write data row (skip first interval — no previous position yet).
            if (state.initialized && dataWriter != null) {
                dataWriter.printf(
                    "%.5g\t%d\t%.2f\t%d\t%.4f\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%d\t%.4f\t%d\t%.4f\t%.4f%n",
                    simTime, densityIndex, surfaceDensity, fid, totalLen,
                    cx, cy, cz, distMoved, vmX, vmY, vmZ,
                    speed, state.longWindowSpeedXY, state.settling ? 1 : 0,
                    avgBound, footprintMotors, footprintDR, headsWithinReachDR);
                dataWriter.flush();
            }

            // Update state for next interval.
            state.prevPos.setVals(cx, cy, cz);
            state.prevTime = simTime;
            state.boundMotorSum = 0;
            state.sampleCount = 0;
            state.initialized = true;

            // Append filament entry to JSON.
            if (!firstFil) json.append(",");
            firstFil = false;
            json.append(String.format(
                "{\"id\":%d,\"length\":%.4f,\"pos\":[%.5g,%.5g,%.5g]," +
                "\"distMoved\":%.5g,\"speed\":%.5g,\"longWindowSpeedXY\":%.5g,\"settling\":%s," +
                "\"avgBoundMotors\":%.3f,\"footprintMotors\":%d,\"footprintDutyRatio\":%.4f}",
                fid, totalLen, cx, cy, cz, distMoved, speed,
                state.longWindowSpeedXY, state.settling ? "true" : "false",
                avgBound, footprintMotors, footprintDR));
        }

        json.append("]}");
        return json.toString();
    }

    /**
     * Build (or rebuild) the spatial grid of MyosinFixed pin positions. Pins
     * are static so we cache and reuse across calls; the grid is rebuilt only
     * when MyoMotor.motorCt changes (no-op in pure gliding).
     */
    private void ensurePinGrid() {
        int curMotorCt = MyoMotor.motorCt;
        if (pinGridBuiltAtMotorCt == curMotorCt && pinGridBins != null) return;

        double xMin = Double.POSITIVE_INFINITY, yMin = Double.POSITIVE_INFINITY, zMin = Double.POSITIVE_INFINITY;
        double xMax = Double.NEGATIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY, zMax = Double.NEGATIVE_INFINITY;
        int fixedCt = 0;
        for (int i = 0; i < curMotorCt; i++) {
            MyoMotor m = MyoMotor.theMotors[i];
            if (m == null || !(m.myMyosin instanceof MyosinFixed)) continue;
            Pt3D p = ((MyosinFixed) m.myMyosin).myFixedPt;
            if (p.x < xMin) xMin = p.x; if (p.x > xMax) xMax = p.x;
            if (p.y < yMin) yMin = p.y; if (p.y > yMax) yMax = p.y;
            if (p.z < zMin) zMin = p.z; if (p.z > zMax) zMax = p.z;
            fixedCt++;
        }
        if (fixedCt == 0) {
            pinGridBins = null;
            pinGridBuiltAtMotorCt = curMotorCt;
            return;
        }
        pinGridX0 = xMin - PIN_BIN_SIZE;
        pinGridY0 = yMin - PIN_BIN_SIZE;
        pinGridZ0 = zMin - PIN_BIN_SIZE;
        pinGridNx = (int) Math.ceil((xMax - pinGridX0) / PIN_BIN_SIZE) + 2;
        pinGridNy = (int) Math.ceil((yMax - pinGridY0) / PIN_BIN_SIZE) + 2;
        pinGridNz = (int) Math.ceil((zMax - pinGridZ0) / PIN_BIN_SIZE) + 2;
        int totalBins = pinGridNx * pinGridNy * pinGridNz;
        int[] counts = new int[totalBins];
        for (int i = 0; i < curMotorCt; i++) {
            MyoMotor m = MyoMotor.theMotors[i];
            if (m == null || !(m.myMyosin instanceof MyosinFixed)) continue;
            Pt3D p = ((MyosinFixed) m.myMyosin).myFixedPt;
            counts[pinBinIndex(p.x, p.y, p.z)]++;
        }
        pinGridBins = new int[totalBins][];
        for (int b = 0; b < totalBins; b++) {
            if (counts[b] > 0) pinGridBins[b] = new int[counts[b]];
        }
        int[] heads = new int[totalBins];
        for (int i = 0; i < curMotorCt; i++) {
            MyoMotor m = MyoMotor.theMotors[i];
            if (m == null || !(m.myMyosin instanceof MyosinFixed)) continue;
            Pt3D p = ((MyosinFixed) m.myMyosin).myFixedPt;
            int b = pinBinIndex(p.x, p.y, p.z);
            pinGridBins[b][heads[b]++] = i;
        }
        pinGridBuiltAtMotorCt = curMotorCt;
    }

    private int pinBinIndex(double x, double y, double z) {
        int bx = (int) ((x - pinGridX0) / PIN_BIN_SIZE);
        int by = (int) ((y - pinGridY0) / PIN_BIN_SIZE);
        int bz = (int) ((z - pinGridZ0) / PIN_BIN_SIZE);
        if (bx < 0) bx = 0; else if (bx >= pinGridNx) bx = pinGridNx - 1;
        if (by < 0) by = 0; else if (by >= pinGridNy) by = pinGridNy - 1;
        if (bz < 0) bz = 0; else if (bz >= pinGridNz) bz = pinGridNz - 1;
        return (bz * pinGridNy + by) * pinGridNx + bx;
    }

    /**
     * Walk all MyosinFixed motors whose pin lies within MOTOR_REACH_UM of the
     * segment's finite axis, invoking accept(motorIdx) for each. Uses the
     * cached pin grid; the segment's AABB (extended by reach) is intersected
     * with the grid to bound the visit set.
     */
    private void collectMotorsNearSegment(FilSegment fs, IntConsumer accept) {
        if (pinGridBins == null) return;

        double cx = fs.getCoordX(), cy = fs.getCoordY(), cz = fs.getCoordZ();
        double halfLen = fs.length * 0.5;
        double ux = fs.getUVecX(), uy = fs.getUVecY(), uz = fs.getUVecZ();
        double r = MOTOR_REACH_UM;
        double sxHalf = halfLen * Math.abs(ux) + r;
        double syHalf = halfLen * Math.abs(uy) + r;
        double szHalf = halfLen * Math.abs(uz) + r;

        int bx0 = (int) ((cx - sxHalf - pinGridX0) / PIN_BIN_SIZE);
        int bx1 = (int) ((cx + sxHalf - pinGridX0) / PIN_BIN_SIZE);
        int by0 = (int) ((cy - syHalf - pinGridY0) / PIN_BIN_SIZE);
        int by1 = (int) ((cy + syHalf - pinGridY0) / PIN_BIN_SIZE);
        int bz0 = (int) ((cz - szHalf - pinGridZ0) / PIN_BIN_SIZE);
        int bz1 = (int) ((cz + szHalf - pinGridZ0) / PIN_BIN_SIZE);
        if (bx0 < 0) bx0 = 0; if (bx1 >= pinGridNx) bx1 = pinGridNx - 1;
        if (by0 < 0) by0 = 0; if (by1 >= pinGridNy) by1 = pinGridNy - 1;
        if (bz0 < 0) bz0 = 0; if (bz1 >= pinGridNz) bz1 = pinGridNz - 1;
        if (bx0 > bx1 || by0 > by1 || bz0 > bz1) return;

        double rSq = r * r;
        for (int bz = bz0; bz <= bz1; bz++) {
            for (int by = by0; by <= by1; by++) {
                int rowBase = (bz * pinGridNy + by) * pinGridNx;
                for (int bx = bx0; bx <= bx1; bx++) {
                    int[] bin = pinGridBins[rowBase + bx];
                    if (bin == null) continue;
                    for (int motorIdx : bin) {
                        MyoMotor m = MyoMotor.theMotors[motorIdx];
                        if (m == null || !(m.myMyosin instanceof MyosinFixed)) continue;
                        Pt3D pt = ((MyosinFixed) m.myMyosin).myFixedPt;
                        double dx = pt.x - cx, dy = pt.y - cy, dz = pt.z - cz;
                        double proj = dx * ux + dy * uy + dz * uz;
                        if (proj > halfLen) proj = halfLen;
                        else if (proj < -halfLen) proj = -halfLen;
                        double pxd = dx - proj * ux;
                        double pyd = dy - proj * uy;
                        double pzd = dz - proj * uz;
                        if (pxd * pxd + pyd * pyd + pzd * pzd <= rSq) {
                            accept.accept(motorIdx);
                        }
                    }
                }
            }
        }
    }

    /** Perpendicular distance from pt to the finite line segment defined by fs.coordAsPt3D()/uVecAsPt3D()/length. */
    private static double distToAxis(Pt3D pt, FilSegment fs) {
        double dx = pt.x - fs.getCoordX();
        double dy = pt.y - fs.getCoordY();
        double dz = pt.z - fs.getCoordZ();
        double proj = dx*fs.getUVecX() + dy*fs.getUVecY() + dz*fs.getUVecZ();
        double halfLen = fs.length * 0.5;
        proj = Math.max(-halfLen, Math.min(halfLen, proj));
        double px = dx - proj*fs.getUVecX();
        double py = dy - proj*fs.getUVecY();
        double pz = dz - proj*fs.getUVecZ();
        return Math.sqrt(px*px + py*py + pz*pz);
    }

    public void close() {
        if (dataWriter != null) { dataWriter.flush(); dataWriter.close(); }
    }
}
