package boxOfActin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        copyInputParamFile();
        writeEffectiveParams();
    }

    private static void copyInputParamFile() {
        if (Env.paramFile == null) {
            System.out.println("ThreeJSWriter: no -pf file given; skipping params_input");
            return;
        }
        File src = Env.paramFile;
        File dst = new File(Env.threeJSOutputDir + File.separator + "params_input." + src.getName());
        try {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("ThreeJSWriter: wrote " + dst.getName());
        } catch (IOException e) {
            System.err.println("ThreeJSWriter: could not copy param file: " + e.getMessage());
        }
    }

    private static void writeEffectiveParams() {
        File out = new File(Env.threeJSOutputDir + File.separator + "params_effective.txt");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(out)))) {
            pw.println("# params_effective.txt — t=0 snapshot of Parameter registry at first frame write.");
            pw.println("# Authoritative record of what the sim actually used: getValue() and isActive()");
            pw.println("# for every Parameter. A line present in the -pf file with isActive=false falls");
            pw.println("# back to the Java default; that gap is visible here, not in params_input.");
            pw.println("# Mid-run mutable params (see Env.setMutableAtRuntime()) may change after t=0.");
            pw.println("# Format: <label> = <value>  (active=<bool>, type=<BOOLEAN|INT|DOUBLE>, default=<v>, units=<u>)");
            pw.println();
            for (int i = 0; i < Parameter.paramCt; i++) {
                Parameter p = Parameter.theParams[i];
                if (p == null) continue;
                String typeStr;
                String valStr;
                switch (p.type) {
                    case Parameter.BOOLEAN:
                        typeStr = "BOOLEAN";
                        valStr = (p.getValue() != 0) ? "true" : "false";
                        break;
                    case Parameter.INT:
                        typeStr = "INT";
                        valStr = Integer.toString(p.getIntValue());
                        break;
                    default:
                        typeStr = "DOUBLE";
                        valStr = String.valueOf(p.getValue());
                        break;
                }
                String defStr = (p.type == Parameter.INT)
                        ? Integer.toString((int) p.defaultValue)
                        : (p.type == Parameter.BOOLEAN
                                ? ((p.defaultValue != 0) ? "true" : "false")
                                : String.valueOf(p.defaultValue));
                pw.printf("%s = %s  (active=%b, type=%s, default=%s, units=%s)%n",
                        p.label, valStr, p.isActive(), typeStr, defStr, p.units);
            }
            System.out.println("ThreeJSWriter: wrote params_effective.txt (" + Parameter.paramCt + " parameters)");
        } catch (IOException e) {
            System.err.println("ThreeJSWriter: could not write params_effective: " + e.getMessage());
        }
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
        // Phase 4 flip — frame readers below pull getEnd1X/getEnd2X/getZVec*
        // and friends, which read Thing.soaEnd1/End2/ZVec/TransXTox. The
        // per-step CPU recomputeDerivedSoA was retired on the GPU path; refresh
        // here at the output-frame cadence so the JSON reflects this step's
        // integrated pose. No-op on the CPU path (initialize() per-Thing
        // keeps derived current there).
        if (Env.useGPU) { GPUMoveThing.refreshHostMirrorsForOutput(); }
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"frame\":").append(frameNumber);
        sb.append(String.format(",\"t\":%.6g", Env.simulationTime));
        // Filament-tip collision radius: the membrane steric surface sits this far beyond the node
        // surface, so the viewer can offset the rendered membrane discs to coincide with where tips
        // actually stop (otherwise the flattened discs float ~nodeRadius+filTipR above the contact).
        sb.append(String.format(",\"filTipR\":%.5g", Env.filTipRadiusForCollisions.getValue()));
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
            // Actin radius = FilSegment.radius (Env.actinWidth/2 = 0.0035 µm = 3.5 nm,
            // i.e. the real 7 nm filament diameter). Was hardcoded 0.035 µm — 10× too
            // thick (70 nm diameter) and inconsistent with the myosin parts, which emit
            // their true radii.
            sb.append(String.format("{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g",
                    fs.thingInstanceId,
                    fs.getEnd1X(), fs.getEnd1Y(), fs.getEnd1Z(),
                    fs.getEnd2X(), fs.getEnd2Y(), fs.getEnd2Z(),
                    FilSegment.radius));
            // Nucleotide age for the viewer's age-coloring (red=old ADP -> yellow=young ATP).
            // notADPRatio = fraction of monomers NOT in ADP state (1.0 young, 0.0 fully aged);
            // cofilinCount marks severing-prone (cofilin-decorated) segments. Maintained on CPU
            // biochem even on the GPU residency path.
            sb.append(String.format(",\"notADPRatio\":%.3g,\"cofilinCount\":%d",
                    fs.notADPFraction(), fs.cofilinCt));
            if (Env.benchmarkFilament) {
                sb.append(fs.isLpSeg ? ",\"chainType\":\"lp\"" : ",\"chainType\":\"defl\"");
            }
            if (!fs.isLpSeg) {
                sb.append(String.format(
                    ",\"axisX\":[%.4g,%.4g,%.4g],\"axisY\":[%.4g,%.4g,%.4g],\"axisZ\":[%.4g,%.4g,%.4g]",
                    fs.getUVecX(), fs.getUVecY(), fs.getUVecZ(),
                    fs.getYVecX(), fs.getYVecY(), fs.getYVecZ(),
                    fs.getZVecX(), fs.getZVecY(), fs.getZVecZ()));
                if (fs.end2Fil == null) {
                    sb.append(",\"isBarbedEnd\":true");
                }
            }
            if (fs.childOfArp23) { sb.append(",\"branch\":true"); }   // Arp2/3 daughter vs formin mother
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
                    m.myoRod.getEnd1X(), m.myoRod.getEnd1Y(), m.myoRod.getEnd1Z(),
                    m.myoRod.getEnd2X(), m.myoRod.getEnd2Y(), m.myoRod.getEnd2Z(),
                    MyoRod.radius, m.myoRod.rodInvisible));
            sb.append(String.format(
                    ",\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g}",
                    m.myoLever.getEnd1X(), m.myoLever.getEnd1Y(), m.myoLever.getEnd1Z(),
                    m.myoLever.getEnd2X(), m.myoLever.getEnd2Y(), m.myoLever.getEnd2Z(),
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
                    mf.getEnd1X(), mf.getEnd1Y(), mf.getEnd1Z(),
                    mf.getEnd2X(), mf.getEnd2Y(), mf.getEnd2Z(),
                    MyoMiniFilament.radius));
            firstMiniFil = false;
        }
        sb.append("]");

        // Protein nodes — spherical bodies that carry myosin singlets/dimers on
        // their surface. Emitted as {id, center, r} so the viewer can draw the
        // sphere the node-attached myosins sit on. Membrane nodes (StickyNode)
        // carry a "membrane":true discriminator so the viewer can style them as a
        // translucent flattened membrane surface and resolve NodeLink connectivity.
        sb.append(",\"nodes\":[");
        boolean firstNode = true;
        for (int i = 0; i < ProteinNode.nodeCt; i++) {
            ProteinNode pn = ProteinNode.theNodes[i];
            if (pn == null || pn.removeMe) continue;
            // DTS membrane vertices are rendered as a triangulated surface (the "membranes"
            // array below), not as individual node spheres — skip them here.
            if (pn instanceof MembraneVertex) continue;
            if (!firstNode) sb.append(",");
            // Hot-Rho (NPF/Arp2/3 activator) membrane nodes carry "hotRho":true so the viewer can
            // highlight the activated patch where branching is localized.
            String memberFlags = "";
            if (pn instanceof StickyNode) {
                memberFlags = ",\"membrane\":true";
                if (pn.iAmHotRho) memberFlags += ",\"hotRho\":true";
                // The node's own surface normal (its zVec) -- the sim's ground-truth orientation, so the
                // viewer offsets the membrane disc inward along it instead of inferring a normal from node
                // positions (which is sign-ambiguous on any curved/bulged surface). Consistent for any
                // geometry (sheet, cylinder, sphere).
                memberFlags += String.format(",\"n\":[%.4g,%.4g,%.4g]", pn.getZVecX(), pn.getZVecY(), pn.getZVecZ());
                // Activated-Arp2/3 field value, so the viewer can heat-map the diffusing field.
                if (Env.arpLocalField.isActive()) memberFlags += String.format(",\"arp\":%.4g", ((StickyNode)pn).arpLocal);
            }
            sb.append(String.format("{\"id\":%d,\"center\":[%.5g,%.5g,%.5g],\"r\":%.5g%s}",
                    pn.thingInstanceId,
                    pn.getCoordX(), pn.getCoordY(), pn.getCoordZ(),
                    pn.getRadius(),
                    memberFlags));
            firstNode = false;
        }
        sb.append("]");

        // Membrane connectivity — one [node1Id, node2Id] pair per active NodeLink
        // (sticky-point spring between two membrane nodes). Emitted every frame
        // because auto-linking adds/removes links as the sheet self-assembles. The
        // viewer resolves the IDs against the "nodes" array (centers), builds the
        // per-node neighbour set to derive surface normals, and draws toggleable
        // connectivity lines. Node-id pairs are emitted rather than duplicated
        // coords so the viewer can both render lines and infer the membrane normal.
        sb.append(",\"membraneLinks\":[");
        boolean firstLink = true;
        for (int i = 0; i < NodeLink.nodeLinkCt; i++) {
            NodeLink ln = NodeLink.nodeLinks[i];
            if (ln == null) break;
            if (!ln.active || ln.removeMe) continue;
            if (ln.node1 == null || ln.node2 == null) continue;
            if (ln.node1.removeMe || ln.node2.removeMe) continue;
            if (!firstLink) sb.append(",");
            sb.append(String.format("[%d,%d]", ln.node1.thingInstanceId, ln.node2.thingInstanceId));
            firstLink = false;
        }
        sb.append("]");

        // DTS membranes (v2 dynamically-triangulated surfaces). One object per Membrane:
        // a flat vertex list (live pose) + a flat triangle-index list, which the viewer
        // turns into a THREE.Mesh (BufferGeometry position attribute + index). Vertices
        // and faces come straight from the Membrane's flat SoA topology. Emitted for both
        // live and file-based -3js since this single JSON feeds both consumers.
        if (!Membrane.theMembranes.isEmpty()) {
            sb.append(",\"membranes\":[");
            for (int m = 0; m < Membrane.theMembranes.size(); m++) {
                Membrane mem = Membrane.theMembranes.get(m);
                if (m > 0) sb.append(",");
                sb.append("{\"id\":").append(mem.membraneId).append(",\"vertices\":[");
                for (int v = 0; v < mem.nv; v++) {
                    if (v > 0) sb.append(",");
                    sb.append(String.format("[%.5g,%.5g,%.5g]", mem.vx(v), mem.vy(v), mem.vz(v)));
                }
                sb.append("],\"faces\":[");
                for (int f = 0; f < mem.nf; f++) {
                    if (f > 0) sb.append(",");
                    sb.append(mem.faceVert[3 * f]).append(",")
                      .append(mem.faceVert[3 * f + 1]).append(",")
                      .append(mem.faceVert[3 * f + 2]);
                }
                sb.append("]");
                // Per-vertex activated-Arp2/3 concentration (for the viewer heat-map), when the field is on.
                if (mem.arpLocal != null) {
                    sb.append(",\"arp\":[");
                    for (int v = 0; v < mem.nv; v++) {
                        if (v > 0) sb.append(",");
                        sb.append(String.format("%.4g", mem.arpLocal[v]));
                    }
                    sb.append("]");
                }
                sb.append("}");
            }
            sb.append("]");
        }

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
                double ax = BoxOfActin.deflFil.midSeg.getCoordX();
                double ay = BoxOfActin.deflFil.midSeg.getCoordY();
                double az = BoxOfActin.deflFil.midSeg.getCoordZ();
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

        // Contractility assay overlay: the two anchor tensions (pN, contractile positive) and the
        // pinned anchor points, so the viewer can show the isometric load building at each end.
        if (Env.contractilityAssay.isActive() && BoxOfActin.contract != null) {
            BoxOfActin.ContractAssay c = BoxOfActin.contract;
            sb.append(String.format(
                ",\"contractility\":{\"tensionA_pN\":%.5g,\"tensionB_pN\":%.5g,"
                + "\"anchorA\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g},\"anchorB\":{\"x\":%.5g,\"y\":%.5g,\"z\":%.5g}",
                c.tensionA_pN, c.tensionB_pN,
                c.anchorPtA.x, c.anchorPtA.y, c.anchorPtA.z,
                c.anchorPtB.x, c.anchorPtB.y, c.anchorPtB.z));
            // Live-HUD stats block (instantaneous + cumulative + recent-EWMA).
            sb.append(String.format(
                ",\"stats\":{\"step\":%d,\"simTime\":%.5g,\"boundHeads\":%d,\"peakBound\":%d,"
                + "\"avgBound\":%.4g,\"ewmaBound\":%.4g,\"meanTension_pN\":%.5g,\"avgTension_pN\":%.5g,"
                + "\"ewmaTension_pN\":%.5g,\"peakTension_pN\":%.5g,\"firstBindStep\":%d,\"hasMotor\":%b}",
                Env.counter, Env.simulationTime, c.instBound, c.peakBound,
                BoxOfActin.contractAvgBound(), c.ewmaBound,
                0.5 * (Math.abs(c.tensionA_pN) + Math.abs(c.tensionB_pN)),
                BoxOfActin.contractAvgTension(), c.ewmaTension, c.peakTension,
                c.firstBindStep, BoxOfActin.contractHasMotor()));
            sb.append("}");
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
        // Step 4 (2026-06-07) — even when JSON build is skipped, the
        // per-step demand-sync is now gated off in noMonomersSimd configs,
        // so output-frame readers further down logAndDraw (notably
        // GlidingAssayEvaluator.outputInterval) need a refresh here. Cheap
        // when no GPU run.
        if (Env.threeJSOutputDir == null && !LiveFrameServer.isRunning()) {
            if (Env.useGPU) { GPUMoveThing.refreshHostMirrorsForOutput(); }
            frameNumber++;
            return;
        }

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
        // Phase 4 flip — inspect payload reads end1/end2 from soaEnd1/End2,
        // which are stale on the GPU path between output frames. Refresh on
        // demand. Cheap (one demand-sync + CPU recompute, runs at most once
        // per safe-point inspect drain).
        if (Env.useGPU) { GPUMoveThing.refreshHostMirrorsForOutput(); }
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
                fs.getCoordX(), fs.getCoordY(), fs.getCoordZ()));
        sb.append(String.format(",\"orientation\":{\"ux\":%.5g,\"uy\":%.5g,\"uz\":%.5g}",
                fs.getUVecX(), fs.getUVecY(), fs.getUVecZ()));
        sb.append(String.format(",\"end1\":[%.5g,%.5g,%.5g]", fs.getEnd1X(), fs.getEnd1Y(), fs.getEnd1Z()));
        sb.append(String.format(",\"end2\":[%.5g,%.5g,%.5g]", fs.getEnd2X(), fs.getEnd2Y(), fs.getEnd2Z()));
        sb.append(",\"filamentId\":").append(fs.filID);
        // filArrayPos is position in global theFilSegments[] — not an intra-filament index;
        // computing intra-filament index requires walking the end1AsPt3D()/end2AsPt3D() chain (omitted, C2)
        sb.append(",\"segmentArrayPos\":").append(fs.filArrayPos);
        sb.append(",\"monomerCount\":").append(fs.monomerCt);
        // notADPRatio is the fraction of monomers NOT in ADP state (aggregate nucleotide state proxy)
        sb.append(String.format(",\"notADPRatio\":%.4g", fs.notADPFraction()));
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
                motor.getCoordX(), motor.getCoordY(), motor.getCoordZ()));
        sb.append(String.format(",\"orientation\":{\"ux\":%.5g,\"uy\":%.5g,\"uz\":%.5g}",
                motor.getUVecX(), motor.getUVecY(), motor.getUVecZ()));
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
                mf.getCoordX(), mf.getCoordY(), mf.getCoordZ()));
        sb.append(String.format(",\"orientation\":{\"ux\":%.5g,\"uy\":%.5g,\"uz\":%.5g}",
                mf.getUVecX(), mf.getUVecY(), mf.getUVecZ()));
        sb.append(String.format(",\"end1\":[%.5g,%.5g,%.5g]", mf.getEnd1X(), mf.getEnd1Y(), mf.getEnd1Z()));
        sb.append(String.format(",\"end2\":[%.5g,%.5g,%.5g]", mf.getEnd2X(), mf.getEnd2Y(), mf.getEnd2Z()));
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
            mo.getEnd1X(), mo.getEnd1Y(), mo.getEnd1Z(),
            mo.getEnd2X(), mo.getEnd2Y(), mo.getEnd2Z(),
            MyoMotor.radius, state, mo.onFil);
    }
}
