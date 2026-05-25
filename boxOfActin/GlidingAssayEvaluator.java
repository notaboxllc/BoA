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

    private static class FilamentState {
        double boundMotorSum;
        int sampleCount;
        final Pt3D prevPos = new Pt3D();
        double prevTime;
        boolean initialized;
    }

    private static GlidingAssayEvaluator instance;

    public static GlidingAssayEvaluator getInstance() { return instance; }

    public static void create() {
        instance = new GlidingAssayEvaluator();
    }

    // Per-filament state keyed by filID.
    private final Map<Integer, FilamentState> filStates = new LinkedHashMap<>();
    private PrintWriter dataWriter;
    private boolean headerWritten;
    public int densityIndex;

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
                "instantaneousSpeed\tavgBoundMotors\tfootprintMotors\tfootprintDutyRatio\theadsWithinReachDR");
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
                cx += fs.coord.x; cy += fs.coord.y; cz += fs.coord.z;
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
                    "%.5g\t%d\t%.2f\t%d\t%.4f\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.5g\t%.4f\t%d\t%.4f\t%.4f%n",
                    simTime, densityIndex, surfaceDensity, fid, totalLen,
                    cx, cy, cz, distMoved, vmX, vmY, vmZ,
                    speed, avgBound, footprintMotors, footprintDR, headsWithinReachDR);
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
                "\"distMoved\":%.5g,\"speed\":%.5g,\"avgBoundMotors\":%.3f," +
                "\"footprintMotors\":%d,\"footprintDutyRatio\":%.4f}",
                fid, totalLen, cx, cy, cz, distMoved, speed, avgBound,
                footprintMotors, footprintDR));
        }

        json.append("]}");
        return json.toString();
    }

    /** Perpendicular distance from pt to the finite line segment defined by fs.coord/uVec/length. */
    private static double distToAxis(Pt3D pt, FilSegment fs) {
        double dx = pt.x - fs.coord.x;
        double dy = pt.y - fs.coord.y;
        double dz = pt.z - fs.coord.z;
        double proj = dx*fs.uVec.x + dy*fs.uVec.y + dz*fs.uVec.z;
        double halfLen = fs.length * 0.5;
        proj = Math.max(-halfLen, Math.min(halfLen, proj));
        double px = dx - proj*fs.uVec.x;
        double py = dy - proj*fs.uVec.y;
        double pz = dz - proj*fs.uVec.z;
        return Math.sqrt(px*px + py*py + pz*pz);
    }

    public void close() {
        if (dataWriter != null) { dataWriter.flush(); dataWriter.close(); }
    }
}
