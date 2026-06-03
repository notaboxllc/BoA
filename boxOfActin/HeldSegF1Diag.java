package boxOfActin;

// HeldSegF1Diag — DIAGNOSTIC ONLY (Phase 2 F1 box-boundary port).
//
// Build a held FilSegment at a non-axis-aligned angle near a Chamber wall and
// evaluate the CPU formula (Chamber.amICollidingOuter + FilSegment.
// bugForcesFromInside) and the device-kernel formula (GPUMoveThing.
// boundaryBoxKernel) on the SAME frozen pose. Dumps F / T per endpoint so
// the planner can confirm float-noise-level match before the run-time bench.
//
// Pure Java, no TornadoVM — the device kernel uses only basic arithmetic +
// Math.sqrt, both of which are pure-Java on the host. Cheap probe; if it
// passes we still need the -3js viewer check (the run-time bench / wall
// observation) to confirm no second-order effects, per the F3 lesson.
//
// Usage:
//   javac the file with the rest of boxOfActin, then
//     java -cp ".:libs/*" boxOfActin.HeldSegF1Diag
//
// Default sweep places the segment so end1 / end2 / both fall just past the
// +x wall at a tilt of 30° in the x-y plane.

public class HeldSegF1Diag {

    // ---- box / physical constants ----
    static final double boxXDim   = 2.0;     // µm
    static final double boxYDim   = 2.0;     // µm
    static final double boxZDim   = 0.1;     // µm  (Env defaults)
    static final double aeta      = 0.1;     // Pa·s
    static final double dt        = 1.0e-4;  // s
    static final double actinMonoRadius = 0.0027;     // µm
    static final double actinFilRadius  = 0.0035;     // µm  (Env.actinWidth/2)
    static final int    monCt          = 32;          // bench default
    static final double segLen         = (monCt + 1) * actinMonoRadius;  // ≈ 0.0891 µm
    static final double aParallel      = -0.20;
    static final double aOrthog        =  0.84;
    static final double aTurning       = -0.662;
    static final double fnormScale     = 0.1;

    // ---- per-segment scratch ----
    static class Seg {
        double cx, cy, cz;
        double ux, uy, uz;
        double length;
        double bTransGam_x, bTransGam_y;
        double bRotGam_y;
        String tag;
        Seg(String tag, double cx, double cy, double cz,
                        double ux, double uy, double uz, double length) {
            this.tag = tag;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.ux = ux; this.uy = uy; this.uz = uz;
            this.length = length;
            calcDrag();
        }
        void calcDrag() {
            double L_m = 1.0e-6 * length;
            double r_m = 1.0e-6 * actinFilRadius;
            double denomLog = Math.log(L_m / (2.0 * r_m));
            bTransGam_x = (2.0 * Math.PI * aeta * L_m) / (denomLog + aParallel);
            bTransGam_y = (4.0 * Math.PI * aeta * L_m) / (denomLog + aOrthog);
            bRotGam_y   = (Math.PI * aeta * L_m * L_m * L_m) / (3.0 * (denomLog + aTurning));
        }
        double e1x() { return cx - 0.5 * length * ux; }
        double e1y() { return cy - 0.5 * length * uy; }
        double e1z() { return cz - 0.5 * length * uz; }
        double e2x() { return cx + 0.5 * length * ux; }
        double e2y() { return cy + 0.5 * length * uy; }
        double e2z() { return cz + 0.5 * length * uz; }
    }

    // ---- CPU mirror: Chamber.amICollidingOuter + bugForcesFromInside ----
    // Returns {Fx, Fy, Fz, Tx, Ty, Tz} for the given endpoint; zero vector
    // if no collision. Chamber center at origin.
    static double[] cpuBoundaryAtEnd(Seg s, double endX, double endY, double endZ) {
        double R = actinFilRadius;
        double dx = endX, dy = endY, dz = endZ;   // ctr - chamberCoord = ctr
        // forceUVec = sign(d_i) * (halfDim_i - R) - d_i
        double halfX = 0.5 * boxXDim, halfY = 0.5 * boxYDim, halfZ = 0.5 * boxZDim;
        double sx = Math.signum(dx), sy = Math.signum(dy), sz = Math.signum(dz);
        double fux = sx * (halfX - R) - dx;
        double fuy = sy * (halfY - R) - dy;
        double fuz = sz * (halfZ - R) - dz;
        // zero axes where the segment is still inside
        if (Math.signum(fux) == sx) fux = 0;
        if (Math.signum(fuy) == sy) fuy = 0;
        if (Math.signum(fuz) == sz) fuz = 0;
        double delta = Math.sqrt(fux*fux + fuy*fuy + fuz*fuz);
        double[] out = new double[6];
        if (delta == 0) return out;
        fux /= delta; fuy /= delta; fuz /= delta;
        // bugForcesFromInside: R_lever = (end - coord) * 1e-6
        double Rx = (endX - s.cx) * 1.0e-6;
        double Ry = (endY - s.cy) * 1.0e-6;
        double Rz = (endZ - s.cz) * 1.0e-6;
        double cxR = Ry * fuz - Rz * fuy;
        double cyR = Rz * fux - Rx * fuz;
        double czR = Rx * fuy - Ry * fux;
        double RxFuVecSqrd = cxR*cxR + cyR*cyR + czR*czR;
        double fturn  = (1.0e-6 * delta * s.bRotGam_y) / (RxFuVecSqrd * dt);
        double ftrans = (1.0e-6 * delta * s.bTransGam_x) / dt;
        double fnorm  = fnormScale * Math.min(fturn, ftrans);
        out[0] = fnorm * fux;
        out[1] = fnorm * fuy;
        out[2] = fnorm * fuz;
        out[3] = Ry * out[2] - Rz * out[1];
        out[4] = Rz * out[0] - Rx * out[2];
        out[5] = Rx * out[1] - Ry * out[0];
        return out;
    }

    // ---- device kernel mirror (per-thread, single segment), matches the
    //      body of GPUMoveThing.boundaryBoxKernel verbatim ----
    static double[] devBoundary(Seg s) {
        double R = actinFilRadius;
        double dimX = boxXDim, dimY = boxYDim, dimZ = boxZDim;
        double halfX = 0.5 * dimX - R;
        double halfY = 0.5 * dimY - R;
        double halfZ = 0.5 * dimZ - R;
        double cx = s.cx, cy = s.cy, cz = s.cz;
        double ux = s.ux, uy = s.uy, uz = s.uz;
        double halfLen_um = 0.5 * s.length;
        double bTGx = s.bTransGam_x;
        double bRGy = s.bRotGam_y;
        double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
        // end1
        {
            double dx = cx - halfLen_um * ux;
            double dy = cy - halfLen_um * uy;
            double dz = cz - halfLen_um * uz;
            double sx = (dx > 0.0) ? 1.0 : ((dx < 0.0) ? -1.0 : 0.0);
            double sy = (dy > 0.0) ? 1.0 : ((dy < 0.0) ? -1.0 : 0.0);
            double sz = (dz > 0.0) ? 1.0 : ((dz < 0.0) ? -1.0 : 0.0);
            double fux = sx * halfX - dx;
            double fuy = sy * halfY - dy;
            double fuz = sz * halfZ - dz;
            double fsx = (fux > 0.0) ? 1.0 : ((fux < 0.0) ? -1.0 : 0.0);
            double fsy = (fuy > 0.0) ? 1.0 : ((fuy < 0.0) ? -1.0 : 0.0);
            double fsz = (fuz > 0.0) ? 1.0 : ((fuz < 0.0) ? -1.0 : 0.0);
            if (fsx == sx) fux = 0;
            if (fsy == sy) fuy = 0;
            if (fsz == sz) fuz = 0;
            double delta2 = fux*fux + fuy*fuy + fuz*fuz;
            if (delta2 > 0.0) {
                double delta = Math.sqrt(delta2);
                double invDelta = 1.0 / delta;
                double luX = fux * invDelta, luY = fuy * invDelta, luZ = fuz * invDelta;
                double Rx = -halfLen_um * ux * 1.0e-6;
                double Ry = -halfLen_um * uy * 1.0e-6;
                double Rz = -halfLen_um * uz * 1.0e-6;
                double cxR = Ry * luZ - Rz * luY;
                double cyR = Rz * luX - Rx * luZ;
                double czR = Rx * luY - Ry * luX;
                double RxFuVecSqrd = cxR*cxR + cyR*cyR + czR*czR;
                double fturn = (RxFuVecSqrd > 1.0e-30)
                    ? (1.0e-6 * delta * bRGy) / (RxFuVecSqrd * dt)
                    : 1.0e30;
                double ftrans = (1.0e-6 * delta * bTGx) / dt;
                double fnorm  = fnormScale * ((fturn < ftrans) ? fturn : ftrans);
                double Fx = fnorm * luX, Fy = fnorm * luY, Fz = fnorm * luZ;
                fx += Fx; fy += Fy; fz += Fz;
                tx += Ry * Fz - Rz * Fy;
                ty += Rz * Fx - Rx * Fz;
                tz += Rx * Fy - Ry * Fx;
            }
        }
        // end2
        {
            double dx = cx + halfLen_um * ux;
            double dy = cy + halfLen_um * uy;
            double dz = cz + halfLen_um * uz;
            double sx = (dx > 0.0) ? 1.0 : ((dx < 0.0) ? -1.0 : 0.0);
            double sy = (dy > 0.0) ? 1.0 : ((dy < 0.0) ? -1.0 : 0.0);
            double sz = (dz > 0.0) ? 1.0 : ((dz < 0.0) ? -1.0 : 0.0);
            double fux = sx * halfX - dx;
            double fuy = sy * halfY - dy;
            double fuz = sz * halfZ - dz;
            double fsx = (fux > 0.0) ? 1.0 : ((fux < 0.0) ? -1.0 : 0.0);
            double fsy = (fuy > 0.0) ? 1.0 : ((fuy < 0.0) ? -1.0 : 0.0);
            double fsz = (fuz > 0.0) ? 1.0 : ((fuz < 0.0) ? -1.0 : 0.0);
            if (fsx == sx) fux = 0;
            if (fsy == sy) fuy = 0;
            if (fsz == sz) fuz = 0;
            double delta2 = fux*fux + fuy*fuy + fuz*fuz;
            if (delta2 > 0.0) {
                double delta = Math.sqrt(delta2);
                double invDelta = 1.0 / delta;
                double luX = fux * invDelta, luY = fuy * invDelta, luZ = fuz * invDelta;
                double Rx = halfLen_um * ux * 1.0e-6;
                double Ry = halfLen_um * uy * 1.0e-6;
                double Rz = halfLen_um * uz * 1.0e-6;
                double cxR = Ry * luZ - Rz * luY;
                double cyR = Rz * luX - Rx * luZ;
                double czR = Rx * luY - Ry * luX;
                double RxFuVecSqrd = cxR*cxR + cyR*cyR + czR*czR;
                double fturn = (RxFuVecSqrd > 1.0e-30)
                    ? (1.0e-6 * delta * bRGy) / (RxFuVecSqrd * dt)
                    : 1.0e30;
                double ftrans = (1.0e-6 * delta * bTGx) / dt;
                double fnorm  = fnormScale * ((fturn < ftrans) ? fturn : ftrans);
                double Fx = fnorm * luX, Fy = fnorm * luY, Fz = fnorm * luZ;
                fx += Fx; fy += Fy; fz += Fz;
                tx += Ry * Fz - Rz * Fy;
                ty += Rz * Fx - Rx * Fz;
                tz += Rx * Fy - Ry * Fx;
            }
        }
        return new double[]{fx, fy, fz, tx, ty, tz};
    }

    // ---- CPU sum over both endpoints (forces accumulate, axial side
    //      effects ignored — they are dead in production workloads) ----
    static double[] cpuBoundarySegSum(Seg s) {
        double[] e1 = cpuBoundaryAtEnd(s, s.e1x(), s.e1y(), s.e1z());
        double[] e2 = cpuBoundaryAtEnd(s, s.e2x(), s.e2y(), s.e2z());
        return new double[]{
            e1[0]+e2[0], e1[1]+e2[1], e1[2]+e2[2],
            e1[3]+e2[3], e1[4]+e2[4], e1[5]+e2[5]};
    }

    static void runCase(String name, Seg s) {
        double[] cpu = cpuBoundarySegSum(s);
        double[] dev = devBoundary(s);
        System.out.printf("==== %s ====%n", name);
        System.out.printf("  seg: coord=(%.4e,%.4e,%.4e) uVec=(%.6f,%.6f,%.6f) len=%.4e%n",
            s.cx, s.cy, s.cz, s.ux, s.uy, s.uz, s.length);
        System.out.printf("  end1=(%.4e,%.4e,%.4e) end2=(%.4e,%.4e,%.4e)%n",
            s.e1x(), s.e1y(), s.e1z(), s.e2x(), s.e2y(), s.e2z());
        System.out.printf("  CPU sum: F=(%.6e,%.6e,%.6e) T=(%.6e,%.6e,%.6e)%n",
            cpu[0], cpu[1], cpu[2], cpu[3], cpu[4], cpu[5]);
        System.out.printf("  DEV sum: F=(%.6e,%.6e,%.6e) T=(%.6e,%.6e,%.6e)%n",
            dev[0], dev[1], dev[2], dev[3], dev[4], dev[5]);
        double dFx = dev[0] - cpu[0], dFy = dev[1] - cpu[1], dFz = dev[2] - cpu[2];
        double dTx = dev[3] - cpu[3], dTy = dev[4] - cpu[4], dTz = dev[5] - cpu[5];
        double absF = Math.sqrt(cpu[0]*cpu[0]+cpu[1]*cpu[1]+cpu[2]*cpu[2]);
        double absT = Math.sqrt(cpu[3]*cpu[3]+cpu[4]*cpu[4]+cpu[5]*cpu[5]);
        double dF  = Math.sqrt(dFx*dFx+dFy*dFy+dFz*dFz);
        double dT  = Math.sqrt(dTx*dTx+dTy*dTy+dTz*dTz);
        double relF = absF > 0 ? dF/absF : 0;
        double relT = absT > 0 ? dT/absT : 0;
        System.out.printf("  diff:    |dF|=%.6e (rel=%.6e) |dT|=%.6e (rel=%.6e)%n",
            dF, relF, dT, relT);
        boolean pass = relF < 1.0e-12 && relT < 1.0e-12 && dF < 1.0e-20 && dT < 1.0e-30;
        System.out.printf("  verdict: %s%n%n", pass ? "PASS" : "WARN");
    }

    public static void main(String[] args) {
        System.out.println("HeldSegF1Diag — box-boundary CPU vs device probe");
        System.out.printf("  box dims (µm): X=%.3f Y=%.3f Z=%.3f  R=%.4f  segLen=%.4e%n",
            boxXDim, boxYDim, boxZDim, actinFilRadius, segLen);
        System.out.println();

        // Wall-inset positions: wall at +halfX, wall force activates when
        // |end_x| > halfX - R = 0.9965.
        double xWall = 0.5 * boxXDim - actinFilRadius;       // 0.9965 µm
        // Case 1 — segment tilted 30° in x-y plane, end2 just past +x wall,
        // end1 well inside. Non-axis-aligned uVec, so forceUVec direction
        // is purely (-x) (single-axis wall hit) but cross-products with
        // diagonal R produce non-trivial torque components.
        double th = Math.toRadians(30.0);
        double ux = Math.cos(th), uy = Math.sin(th), uz = 0;
        double midX = xWall + 0.5*segLen*ux - 0.005;  // end2 past wall by 5 nm
        double midY = 0.0,                  midZ = 0.0;
        runCase("Case 1: 30° tilt, end2 ~5 nm past +x wall",
                new Seg("c1", midX, midY, midZ, ux, uy, uz, segLen));

        // Case 2 — same tilt, segment pushed further so end1 also past +x.
        // Should still produce a single-axis wall force on each endpoint.
        runCase("Case 2: 30° tilt, both ends past +x wall",
                new Seg("c2", xWall + 0.02, 0.05, 0.0, ux, uy, uz, segLen));

        // Case 3 — segment near the +x,+y corner at 45°, end2 past both
        // x and y walls. forceUVec should have two non-zero components,
        // exercising the per-axis containment logic.
        double th2 = Math.toRadians(45.0);
        double ux2 = Math.cos(th2), uy2 = Math.sin(th2), uz2 = 0;
        // pick midpoint so end2 lands just past (xWall, yWall) corner
        double tip2X = xWall + 0.003, tip2Y = (0.5*boxYDim - actinFilRadius) + 0.002;
        double mid3X = tip2X - 0.5*segLen*ux2;
        double mid3Y = tip2Y - 0.5*segLen*uy2;
        runCase("Case 3: 45° tilt, end2 past +x,+y corner",
                new Seg("c3", mid3X, mid3Y, 0.0, ux2, uy2, uz2, segLen));

        // Case 4 — segment fully inside (no collision). Both formulas
        // should return zero.
        runCase("Case 4: fully inside box (no collision expected)",
                new Seg("c4", 0.0, 0.0, 0.0, ux, uy, uz, segLen));

        // Case 5 — non-axis-aligned 3D tilt (uVec has nonzero z), end2 past
        // +x wall. Lever-arm cross-products are fully 3D, exercising all
        // torque components.
        double phi  = Math.toRadians(20.0);   // tilt out of xy plane
        double th3  = Math.toRadians(30.0);
        double ux3 = Math.cos(phi) * Math.cos(th3);
        double uy3 = Math.cos(phi) * Math.sin(th3);
        double uz3 = Math.sin(phi);
        double mid5X = xWall + 0.5*segLen*ux3 - 0.005;
        runCase("Case 5: full 3D tilt (φ=20°, θ=30°), end2 ~5 nm past +x",
                new Seg("c5", mid5X, 0.01, 0.0, ux3, uy3, uz3, segLen));
    }
}
