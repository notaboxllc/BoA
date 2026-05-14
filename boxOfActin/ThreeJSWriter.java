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
        sb.append(String.format(",\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g}",
                Env.boxXDim.getValue(), Env.boxYDim.getValue(), Env.boxZDim.getValue()));
        sb.append(",\"segments\":[");

        boolean first = true;
        for (int i = 0; i < FilSegment.filSegmentCt; i++) {
            FilSegment fs = FilSegment.theFilSegments[i];
            if (fs == null || fs.removeMe) continue;
            if (!first) sb.append(",");
            sb.append(String.format("{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.035}",
                    fs.thingInstanceId,
                    fs.end1.x, fs.end1.y, fs.end1.z,
                    fs.end2.x, fs.end2.y, fs.end2.z));
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
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Generates frame JSON once and dispatches to all active consumers:
     * file-based output (if -3js was given) and/or WebSocket (if -3jsLive was
     * given).  Increments frameNumber after dispatch.
     */
    public static void writeFrame() {
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
