package boxOfActin;

import java.io.*;
import java.util.*;

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

        // Population-level heads-within-reach duty ratio across all filaments.
        int withinReachTotal = 0, withinReachBound = 0;
        for (int i = 0; i < MyoMotor.motorCt; i++) {
            MyoMotor m = MyoMotor.theMotors[i];
            if (m == null || !(m.myMyosin instanceof MyosinFixed)) continue;
            MyosinFixed mf = (MyosinFixed) m.myMyosin;
            boolean inReach = false;
            outer:
            for (List<FilSegment> segs : filGroups.values()) {
                for (FilSegment fs : segs) {
                    if (distToAxis(mf.myFixedPt, fs) <= MOTOR_REACH_UM) {
                        inReach = true;
                        break outer;
                    }
                }
            }
            if (inReach) {
                withinReachTotal++;
                if (m.onFil) withinReachBound++;
            }
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

            // Footprint: MyosinFixed motors whose pin is within MOTOR_REACH_UM of this filament.
            int footprintMotors = 0;
            for (int i = 0; i < MyoMotor.motorCt; i++) {
                MyoMotor m = MyoMotor.theMotors[i];
                if (m == null || !(m.myMyosin instanceof MyosinFixed)) continue;
                MyosinFixed mf = (MyosinFixed) m.myMyosin;
                for (FilSegment fs : segs) {
                    if (distToAxis(mf.myFixedPt, fs) <= MOTOR_REACH_UM) {
                        footprintMotors++;
                        break;
                    }
                }
            }
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
