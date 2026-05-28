package boxOfActin;

import java.io.*;
import java.util.Arrays;
import java.util.zip.*;

public class ThreeJSWriter {

    private static boolean dirResolved = false;
    private static int frameNumber = 0;

    private static void resolveOutputDir() {
        File dir = new File(Env.threeJSOutputDir);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("ThreeJSWriter: created directory " + dir.getAbsolutePath());
        } else {
            for (int n = 1; n <= 999; n++) {
                File candidate = new File(String.format("%s.%03d", Env.threeJSOutputDir, n));
                if (!candidate.exists()) {
                    candidate.mkdirs();
                    Env.threeJSOutputDir = candidate.getAbsolutePath();
                    break;
                }
            }
        }
        System.out.println("ThreeJSWriter: output directory " + Env.threeJSOutputDir);
        dirResolved = true;
        archiveSource();
    }

    private static void archiveSource() {
        File srcDir = new File(".");
        File[] javaFiles = srcDir.listFiles((d, name) -> name.endsWith(".java"));
        if (javaFiles == null || javaFiles.length == 0) return;
        Arrays.sort(javaFiles);

        File archive = new File(Env.threeJSOutputDir + File.separator + "source.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(archive))) {
            byte[] buf = new byte[4096];
            for (File f : javaFiles) {
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            System.out.println("ThreeJSWriter: archived " + javaFiles.length + " source files to source.zip");
        } catch (IOException e) {
            System.err.println("ThreeJSWriter: could not archive source: " + e.getMessage());
        }
    }

    /**
     * Builds the frame JSON string for the current frame number without any
     * side effects (no file I/O, no frameNumber increment).  Called by
     * writeFrame() so both consumers (file and WebSocket) share one build.
     */
    public static String buildFrameJson() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"frame\":").append(frameNumber);
        sb.append(String.format(",\"t\":%.6g", Env.simulationTime));
        if (!Env.benchmarkFilament) {
            sb.append(String.format(",\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g}",
                    Env.boxXDim.getValue(), Env.boxYDim.getValue(), Env.boxZDim.getValue()));
        }
        sb.append(",\"segments\":[");

        boolean first = true;
        for (int i = 0; i < FilSegment.filSegmentCt; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            if (!first) sb.append(",");
            sb.append(String.format("{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.035",
                    fs.thingInstanceId,
                    fs.end1.x, fs.end1.y, fs.end1.z,
                    fs.end2.x, fs.end2.y, fs.end2.z));
            if (Env.benchmarkFilament) {
                sb.append(fs.isLpSeg ? ",\"chainType\":\"lp\"" : ",\"chainType\":\"defl\"");
            }
            if (!fs.isLpSeg) {
                sb.append(String.format(
                    ",\"axisX\":[%.4g,%.4g,%.4g],\"axisY\":[%.4g,%.4g,%.4g],\"axisZ\":[%.4g,%.4g,%.4g]",
                    fs.uVec.x, fs.uVec.y, fs.uVec.z,
                    fs.yVec.x, fs.yVec.y, fs.yVec.z,
                    fs.zVec.x, fs.zVec.y, fs.zVec.z));
                if (fs.end2Fil == null) {
                    sb.append(",\"isBarbedEnd\":true");
                }
            }
            sb.append("}");
            first = false;
        }

        sb.append("],\"myosins\":[");
        boolean firstMyo = true;
        for (int i = 0; i < Myosin.myoCt; i++) {
            Myosin m = Myosin.theMyosins[i];
            if (m == null || m.removeMe) continue;
            if (!firstMyo) sb.append(",");
            sb.append(String.format(
                    "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"invisible\":%b}",
                    m.myoMotor.thingInstanceId,  // motor's stable ID; carries the biologically interesting state
                    m.myoRod.end1.x, m.myoRod.end1.y, m.myoRod.end1.z,
                    m.myoRod.end2.x, m.myoRod.end2.y, m.myoRod.end2.z,
                    MyoRod.radius, m.myoRod.rodInvisible));
            sb.append(String.format(
                    ",\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g}",
                    m.myoLever.end1.x, m.myoLever.end1.y, m.myoLever.end1.z,
                    m.myoLever.end2.x, m.myoLever.end2.y, m.myoLever.end2.z,
                    MyoLever.radius));
            sb.append(",\"motor\":").append(motorJson(m.myoMotor)).append("}");
            firstMyo = false;
        }
        sb.append("],\"minifilaments\":[");
        boolean firstMiniFil = true;
        for (int i = 0; i < MyoMiniFilament.myoMiniFilCt; i++) {
            MyoMiniFilament mf = MyoMiniFilament.myoMiniFils[i];
            if (mf == null || mf.removeMe) continue;
            if (!firstMiniFil) sb.append(",");
            sb.append(String.format("{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g}",
                    mf.thingInstanceId,
                    mf.end1.x, mf.end1.y, mf.end1.z,
                    mf.end2.x, mf.end2.y, mf.end2.z,
                    MyoMiniFilament.radius));
            firstMiniFil = false;
        }
        sb.append("]");

        // Benchmark overlay: pinned endpoint anchors and force arrows (absent in non-benchmark frames).
        if (Env.benchmarkFilament) {
            sb.append(",\"pinnedEndpoints\":");
            if (BoxOfActin.deflFil.firstSeg != null && BoxOfActin.deflFil.lastSeg != null) {
                sb.append(String.format("[{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g},{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}]",
                    BoxOfActin.deflFil.anchor1.x, BoxOfActin.deflFil.anchor1.y, BoxOfActin.deflFil.anchor1.z,
                    BoxOfActin.deflFil.anchor2.x, BoxOfActin.deflFil.anchor2.y, BoxOfActin.deflFil.anchor2.z));
            } else {
                sb.append("null");
            }
            sb.append(",\"forceArrows\":[");
            if (BoxOfActin.deflFil.midSeg != null) {
                boolean forceOn = Env.benchmarkForceOn.getValue() != 0;
                double ax = BoxOfActin.deflFil.midSeg.coord.x;
                double ay = BoxOfActin.deflFil.midSeg.coord.y;
                double az = BoxOfActin.deflFil.midSeg.coord.z;
                double fx = BoxOfActin.deflFil.transForce.x;
                double fy = BoxOfActin.deflFil.transForce.y;
                double fz = BoxOfActin.deflFil.transForce.z;
                double fmag = Math.sqrt(fx*fx + fy*fy + fz*fz);
                double dnx = fmag > 1e-30 ? fx/fmag : 0.0;
                double dny = fmag > 1e-30 ? fy/fmag : -1.0;
                double dnz = fmag > 1e-30 ? fz/fmag : 0.0;
                sb.append(String.format(
                    "{\"point\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g},\"direction\":{\"x\":%.4g,\"y\":%.4g,\"z\":%.4g},\"magnitude\":%.5g,\"label\":\"F\",\"visible\":%b}",
                    ax, ay, az, dnx, dny, dnz, fmag, forceOn));
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Generates frame JSON once and dispatches to all active consumers:
     * file-based output (if -3js was given) and/or WebSocket (if -3jsLive was
     * given).  Increments frameNumber after dispatch.
     */
    public static void writeFrame() {
        // Skip expensive JSON build when there is no consumer (no -3js dir, no live WebSocket).
        if (Env.threeJSOutputDir == null && !LiveFrameServer.isRunning()) { frameNumber++; return; }

        String json = buildFrameJson();

        if (Env.threeJSOutputDir != null) {
            if (!dirResolved) resolveOutputDir();
            String path = String.format("%s%sframe_%06d.json",
                    Env.threeJSOutputDir, File.separator, frameNumber);
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(path)))) {
                pw.print(json);
            } catch (IOException e) {
                System.err.println("ThreeJSWriter: " + e.getMessage());
            }
        }

        LiveFrameServer.dispatchFrame(json);

        frameNumber++;
    }

    // ── C2: click-to-inspect ─────────────────────────────────────────────────

    /**
     * Build an inspectResult JSON payload for the given thingInstanceId.
     * Returns a notFound payload if the ID is unknown or the Thing is flagged
     * for removal (its state would be unreliable).
     */
    public static String buildInspectJson(int requestedId) {
        Thing t = Thing.findByInstanceId(requestedId);
        if (t == null || t.removeMe) {
            return "{\"id\":" + requestedId + ",\"kind\":\"notFound\"}";
        }
        if (t instanceof FilSegment)      return inspectFilSegment((FilSegment) t);
        if (t instanceof MyoMotor)        return inspectMyoMotor((MyoMotor) t);
        if (t instanceof MyoMiniFilament) return inspectMyoMiniFilament((MyoMiniFilament) t);
        return String.format("{\"id\":%d,\"kind\":\"unknown\"}", t.thingInstanceId);
    }

    private static String inspectFilSegment(FilSegment fs) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(fs.thingInstanceId);
        sb.append(",\"kind\":\"filSegment\"");
        sb.append(String.format(",\"position\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}",
                fs.coord.x, fs.coord.y, fs.coord.z));
        sb.append(String.format(",\"orientation\":{\"ux\":%.5g,\"uy\":%.5g,\"uz\":%.5g}",
                fs.uVec.x, fs.uVec.y, fs.uVec.z));
        sb.append(String.format(",\"end1\":[%.5g,%.5g,%.5g]", fs.end1.x, fs.end1.y, fs.end1.z));
        sb.append(String.format(",\"end2\":[%.5g,%.5g,%.5g]", fs.end2.x, fs.end2.y, fs.end2.z));
        sb.append(",\"filamentId\":").append(fs.filID);
        // filArrayPos is position in global theFilSegments[] — not an intra-filament index;
        // computing intra-filament index requires walking the end1/end2 chain (omitted, C2)
        sb.append(",\"segmentArrayPos\":").append(fs.filArrayPos);
        sb.append(",\"monomerCount\":").append(fs.monomerCt);
        // notADPRatio is the fraction of monomers NOT in ADP state (aggregate nucleotide state proxy)
        sb.append(String.format(",\"notADPRatio\":%.4g", fs.notADPRatio));
        sb.append(",\"cofilinCount\":").append(fs.cofilinCt);
        sb.append(",\"end2Capped\":").append(fs.end2Capped);
        sb.append(",\"ageSteps\":").append(Env.counter - fs.createdAtStep);
        if (fs.end1Fil != null)
            sb.append(",\"prevSegId\":").append(fs.end1Fil.thingInstanceId);
        else
            sb.append(",\"prevSegId\":null");
        if (fs.end2Fil != null)
            sb.append(",\"nextSegId\":").append(fs.end2Fil.thingInstanceId);
        else
            sb.append(",\"nextSegId\":null");
        sb.append("}");
        return sb.toString();
    }

    private static String inspectMyoMotor(MyoMotor motor) {
        String state;
        switch (motor.nucleotideState) {
            case MyoMotor.ATP:   state = "ATP";   break;
            case MyoMotor.ADPPi: state = "ADPPi"; break;
            case MyoMotor.ADP:   state = "ADP";   break;
            default:             state = "NONE";  break;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(motor.thingInstanceId);
        sb.append(",\"kind\":\"myosin\"");
        sb.append(String.format(",\"position\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}",
                motor.coord.x, motor.coord.y, motor.coord.z));
        sb.append(String.format(",\"orientation\":{\"ux\":%.5g,\"uy\":%.5g,\"uz\":%.5g}",
                motor.uVec.x, motor.uVec.y, motor.uVec.z));
        sb.append(",\"nucleotideState\":\"").append(state).append("\"");
        sb.append(",\"onFil\":").append(motor.onFil);
        sb.append(",\"inRigor\":").append(motor.inRigor);
        // lever angle requires computing the rod-lever angle — omitted (C2); add as C2+ if needed
        if (motor.onFil && motor.tipLink != null && motor.tipLink.mySeg != null)
            sb.append(",\"boundSegId\":").append(motor.tipLink.mySeg.thingInstanceId);
        else
            sb.append(",\"boundSegId\":null");
        sb.append(",\"ageSteps\":").append(Env.counter - motor.createdAtStep);
        sb.append("}");
        return sb.toString();
    }

    private static String inspectMyoMiniFilament(MyoMiniFilament mf) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(mf.thingInstanceId);
        sb.append(",\"kind\":\"myoMiniFilament\"");
        sb.append(String.format(",\"position\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}",
                mf.coord.x, mf.coord.y, mf.coord.z));
        sb.append(String.format(",\"orientation\":{\"ux\":%.5g,\"uy\":%.5g,\"uz\":%.5g}",
                mf.uVec.x, mf.uVec.y, mf.uVec.z));
        sb.append(String.format(",\"end1\":[%.5g,%.5g,%.5g]", mf.end1.x, mf.end1.y, mf.end1.z));
        sb.append(String.format(",\"end2\":[%.5g,%.5g,%.5g]", mf.end2.x, mf.end2.y, mf.end2.z));
        sb.append(",\"ageSteps\":").append(Env.counter - mf.createdAtStep);
        // Collect IDs of motors currently bound to actin (onFil == true)
        sb.append(",\"attachedMotorIds\":[");
        boolean firstId = true;
        for (int e = 0; e < 2; e++) {
            MyosinDimer[] dimers = (e == 0) ? mf.myoDimersEnd1 : mf.myoDimersEnd2;
            for (int d = 0; d < mf.numMyoDimersEachEnd; d++) {
                MyosinDimer dimer = dimers[d];
                if (dimer == null || dimer.removeMe) continue;
                Myosin[] myos = { dimer.myo1, dimer.myo2 };
                for (Myosin myo : myos) {
                    if (myo == null || myo.removeMe || myo.myoMotor == null) continue;
                    if (myo.myoMotor.onFil) {
                        if (!firstId) sb.append(",");
                        sb.append(myo.myoMotor.thingInstanceId);
                        firstId = false;
                    }
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String motorJson(MyoMotor mo) {
        String state;
        switch (mo.nucleotideState) {
            case MyoMotor.ATP:   state = "ATP";   break;
            case MyoMotor.ADPPi: state = "ADPPi"; break;
            case MyoMotor.ADP:   state = "ADP";   break;
            default:             state = "NONE";  break;
        }
        return String.format(
            "{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"state\":\"%s\",\"onFil\":%b}",
            mo.end1.x, mo.end1.y, mo.end1.z,
            mo.end2.x, mo.end2.y, mo.end2.z,
            MyoMotor.radius, state, mo.onFil);
    }
}
