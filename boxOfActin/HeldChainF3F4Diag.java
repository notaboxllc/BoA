package boxOfActin;

// HeldChainF3F4Diag — DIAGNOSTIC ONLY (Phase 2 F3/F4 deficit investigation).
//
// Build a held 3-segment chain in a prescribed bent shape and evaluate the
// CPU formula (FilSegment.addLinkForces / addTorsionSpringForces) and the
// device-kernel formula (GPUMoveThing.chainPairForcesKernel) on the SAME
// frozen pose. Dumps ordered intermediate quantities so the planner can
// locate where the two paths first diverge term-by-term.
//
// Pure Java, no TornadoVM, no Env init — physical constants are hard-coded
// to match Env defaults at the time of writing (aeta=0.1 Pa·s, deltaT=1e-4
// s, actinMonoRadius=0.0027 µm, monCt=32 → segLen=0.0891 µm). The device
// kernel's PTX-lowering is irrelevant to this diagnostic: we are looking
// for an algebraic / partition divergence, not a float-precision drift.
//
// Usage: javac the file with the rest of boxOfActin, then
//     java -cp ".:libs/*" boxOfActin.HeldChainF3F4Diag [theta_deg ...]
//
// If no thetas are passed, runs the default sweep: 1°, 5°, 10°, 20°, 45°.

public class HeldChainF3F4Diag {

    // ---- physical constants (Env defaults) ----
    static final double aeta             = 0.1;        // Pa·s
    static final double dt               = 1.0e-4;     // s
    static final double fracMove         = 0.5;
    static final double fracR            = 0.1;
    static final double fracMoveTorq     = 0.265;
    static final double actinMonoRadius  = 0.0027;     // µm  (Env.actinMonoRadius)
    static final double actinFilRadius   = 0.0035;     // µm  (Env.actinWidth / 2)
    static final int    monCt            = 32;         // bench default (Env.stdSegLength)
    static final double segLen           = (monCt + 1) * actinMonoRadius;  // µm
    static final boolean filTorqSpringActive = false;
    static final double filTorqSpring    = 0.0;
    static final double aParallel        = -0.20;
    static final double aOrthog          =  0.84;
    static final double aTurning         = -0.662;

    static class Seg {
        double cx, cy, cz;       // center, µm
        double ux, uy, uz;       // uVec
        double length;           // µm
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
            bRotGam_y   = (Math.PI * aeta * L_m*L_m*L_m) / (3.0 * (denomLog + aTurning));
        }
        double e1x() { return cx - 0.5*length*ux; }
        double e1y() { return cy - 0.5*length*uy; }
        double e1z() { return cz - 0.5*length*uz; }
        double e2x() { return cx + 0.5*length*ux; }
        double e2y() { return cy + 0.5*length*uy; }
        double e2z() { return cz + 0.5*length*uz; }
    }

    // ---- acos helpers ----
    static double fastAcos(double x) {       // CPU's Pt3D.fastAcos
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        if (x > 0.95) {
            double t = 1.0 - x; if (t < 0) t = 0;
            return Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x; if (t < 0) t = 0;
            return Math.PI - Math.sqrt(2.0 * t);
        }
        return Math.acos(x);
    }
    static double accurateAcos(double x) {   // device GPUMoveThing.accurateAcos
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) {
            double t = 1.0 - x; if (t < 0) t = 0;
            y = Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x; if (t < 0) t = 0;
            y = Math.PI - Math.sqrt(2.0 * t);
        } else {
            double ax = (x < 0) ? -x : x;
            double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
            p = (p * ax + 1.5707963);
            p = p * Math.sqrt(1.0 - ax);
            y = (x < 0) ? (Math.PI - p) : p;
        }
        double s = Math.sin(y);
        if (s > 1e-12 || s < -1e-12) y = y + (Math.cos(y) - x) / s;
        s = Math.sin(y);
        if (s > 1e-12 || s < -1e-12) y = y + (Math.cos(y) - x) / s;
        return y;
    }

    // ----------------------------------------------------------
    // CPU side: mirror FilSegment.addLinkForces (end2 block).
    // Owner = me. Pair partner = nbr. e2Side: 0 if ptAtEnd2 == nbr.end1Pt.
    // Returns {F_me, T_me, F_nbr, T_nbr} as 12 doubles (owner applies BOTH).
    // ----------------------------------------------------------
    static double[] cpuF3End2(Seg me, Seg nbr, int e2Side, boolean dump) {
        // linkPt = end2 - actinMonoRadius * uVec  (i.e. = me.end2Pt + r * uVecR)
        double lpx = me.e2x() - actinMonoRadius * me.ux;
        double lpy = me.e2y() - actinMonoRadius * me.uy;
        double lpz = me.e2z() - actinMonoRadius * me.uz;
        double pax = (e2Side == 0) ? nbr.e1x() : nbr.e2x();
        double pay = (e2Side == 0) ? nbr.e1y() : nbr.e2y();
        double paz = (e2Side == 0) ? nbr.e1z() : nbr.e2z();
        double dx = pax - lpx, dy = pay - lpy, dz = paz - lpz;
        double strainDist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double inv = (strainDist > 0) ? 1.0/strainDist : 0;
        double luX = dx*inv, luY = dy*inv, luZ = dz*inv;
        double lurX = -luX, lurY = -luY, lurZ = -luZ;

        // me.moveCoeff(2, linkUVec): cosBeta = uVec · linkUVec
        double cosB1 = me.ux*luX + me.uy*luY + me.uz*luZ;
        if (cosB1 >  1) cosB1 =  1;
        if (cosB1 < -1) cosB1 = -1;
        double beta1 = fastAcos(cosB1);
        double cosA1 = Math.sin(beta1);
        double lSq1  = 1e-12 * me.length * me.length;
        double moveC1 = cosB1*cosB1/me.bTransGam_x
                      + cosA1*cosA1/me.bTransGam_y
                      + lSq1*cosA1*cosA1/(4.0*me.bRotGam_y);

        // nbr.moveCoeff(end, linkUVecR), end=1 if e2Side==0 else 2
        double cosB2;
        if (e2Side == 0) cosB2 = -(nbr.ux*lurX + nbr.uy*lurY + nbr.uz*lurZ);  // uVecR · lur
        else             cosB2 =  (nbr.ux*lurX + nbr.uy*lurY + nbr.uz*lurZ);  // uVec  · lur
        if (cosB2 >  1) cosB2 =  1;
        if (cosB2 < -1) cosB2 = -1;
        double beta2 = fastAcos(cosB2);
        double cosA2 = Math.sin(beta2);
        double lSq2  = 1e-12 * nbr.length * nbr.length;
        double moveC2 = cosB2*cosB2/nbr.bTransGam_x
                      + cosA2*cosA2/nbr.bTransGam_y
                      + lSq2*cosA2*cosA2/(4.0*nbr.bRotGam_y);

        double forceMag = (fracMove * 1e-6 * strainDist) / (dt * (moveC1 + moveC2));
        double Fx = forceMag*luX, Fy = forceMag*luY, Fz = forceMag*luZ;

        // Self torque: R = 0.5e-6 * length * fracR * uVec, T = R × F.
        double Rscale = 0.5e-6 * me.length * fracR;
        double Rx = Rscale*me.ux, Ry = Rscale*me.uy, Rz = Rscale*me.uz;
        double Tx = Ry*Fz - Rz*Fy;
        double Ty = Rz*Fx - Rx*Fz;
        double Tz = Rx*Fy - Ry*Fx;

        // Neighbor: Fopp = -F. R_nbr = 0.5e-6 * nbr.length * fracR * (uVecR if e2Side==0 else uVec)
        double Foppx = -Fx, Foppy = -Fy, Foppz = -Fz;
        double Rnscale = 0.5e-6 * nbr.length * fracR * ((e2Side == 0) ? -1.0 : 1.0);
        double Rnx = Rnscale*nbr.ux, Rny = Rnscale*nbr.uy, Rnz = Rnscale*nbr.uz;
        double Tnx = Rny*Foppz - Rnz*Foppy;
        double Tny = Rnz*Foppx - Rnx*Foppz;
        double Tnz = Rnx*Foppy - Rny*Foppx;

        if (dump) {
            System.out.printf("    [CPU.F3.end2]   linkPt    = (%.6e, %.6e, %.6e)%n", lpx, lpy, lpz);
            System.out.printf("    [CPU.F3.end2]   ptAtEnd2  = (%.6e, %.6e, %.6e)%n", pax, pay, paz);
            System.out.printf("    [CPU.F3.end2]   strainDist= %.6e µm%n", strainDist);
            System.out.printf("    [CPU.F3.end2]   linkUVec  = (%.6e, %.6e, %.6e)%n", luX, luY, luZ);
            System.out.printf("    [CPU.F3.end2]   cosB(self)= %.6e  moveC(self) = %.6e%n", cosB1, moveC1);
            System.out.printf("    [CPU.F3.end2]   cosB(nbr) = %.6e  moveC(nbr)  = %.6e%n", cosB2, moveC2);
            System.out.printf("    [CPU.F3.end2]   forceMag  = %.6e N%n", forceMag);
            System.out.printf("    [CPU.F3.end2]   F_self    = (%.6e, %.6e, %.6e) N%n", Fx, Fy, Fz);
            System.out.printf("    [CPU.F3.end2]   T_self    = (%.6e, %.6e, %.6e) N·µm%n", Tx, Ty, Tz);
            System.out.printf("    [CPU.F3.end2]   F_nbr     = (%.6e, %.6e, %.6e) N    (= -F_self)%n", Foppx, Foppy, Foppz);
            System.out.printf("    [CPU.F3.end2]   T_nbr     = (%.6e, %.6e, %.6e) N·µm%n", Tnx, Tny, Tnz);
        }
        return new double[]{Fx, Fy, Fz, Tx, Ty, Tz, Foppx, Foppy, Foppz, Tnx, Tny, Tnz};
    }

    // ----------------------------------------------------------
    // CPU torsion (end2 block): owner applies +T to self, -T to nbr.
    // ----------------------------------------------------------
    static double[] cpuF4End2(Seg me, Seg nbr, int e2Side, boolean dump) {
        double nuxE, nuyE, nuzE;
        if (e2Side == 0) { nuxE =  nbr.ux; nuyE =  nbr.uy; nuzE =  nbr.uz; }
        else             { nuxE = -nbr.ux; nuyE = -nbr.uy; nuzE = -nbr.uz; }
        double tvx = me.uy * nuzE - me.uz * nuyE;
        double tvy = me.uz * nuxE - me.ux * nuzE;
        double tvz = me.ux * nuyE - me.uy * nuxE;
        double mag = Math.sqrt(tvx*tvx + tvy*tvy + tvz*tvz);
        double tvUx = 0, tvUy = 0, tvUz = 0;
        if (mag > 0) { tvUx = tvx/mag; tvUy = tvy/mag; tvUz = tvz/mag; }
        double dot = me.ux*nuxE + me.uy*nuyE + me.uz*nuzE;
        if (dot >  1) dot =  1;
        if (dot < -1) dot = -1;
        double angDeg = fastAcos(dot) * 180.0 / Math.PI;
        double torsionMag;
        if (filTorqSpringActive) {
            torsionMag = fracMoveTorq * filTorqSpring * angDeg;
        } else {
            double invBRG = 1.0/me.bRotGam_y + 1.0/nbr.bRotGam_y;
            torsionMag = fracMoveTorq * (Math.PI/180.0) * angDeg / (invBRG * dt);
        }
        double Tx = tvUx * torsionMag, Ty = tvUy * torsionMag, Tz = tvUz * torsionMag;
        double Tnx = -Tx, Tny = -Ty, Tnz = -Tz;

        if (dump) {
            System.out.printf("    [CPU.F4.end2]   cross(uVec,nbr.uVec_signed) raw = (%.6e, %.6e, %.6e)%n", tvx, tvy, tvz);
            System.out.printf("    [CPU.F4.end2]   |cross|                          = %.6e%n", mag);
            System.out.printf("    [CPU.F4.end2]   unit torsionVec                  = (%.6e, %.6e, %.6e)%n", tvUx, tvUy, tvUz);
            System.out.printf("    [CPU.F4.end2]   dot(uVec, nbr.uVec_signed)       = %.6e%n", dot);
            System.out.printf("    [CPU.F4.end2]   angTween (deg, fastAcos)         = %.6e°%n", angDeg);
            System.out.printf("    [CPU.F4.end2]   torsionMag                       = %.6e N·µm%n", torsionMag);
            System.out.printf("    [CPU.F4.end2]   T_self (= +tvU · torsionMag)     = (%.6e, %.6e, %.6e)%n", Tx, Ty, Tz);
            System.out.printf("    [CPU.F4.end2]   T_nbr  (= -T_self)               = (%.6e, %.6e, %.6e)%n", Tnx, Tny, Tnz);
        }
        return new double[]{Tx, Ty, Tz, Tnx, Tny, Tnz};
    }

    // ----------------------------------------------------------
    // Device kernel side: mirror chainPairForcesKernel exactly for the
    // owner thread's e2 OR e1 block. Returns per-thread contribution
    // (F, T) for ONLY itself (no neighbor application — that is what the
    // neighbor thread computes independently in its own block).
    //
    // meIsOwner: deterministic from slot indices in the kernel
    //   (selfIsOwner = i < neighbour.slot). Both threads on a pair arrive
    //   at the same OWNER-perspective linkUVec; non-owner applies −F.
    //
    // dumpTag: e.g. "DEV.slot=0.end2" for clean labeling.
    // ----------------------------------------------------------
    static double[] deviceChainEnd2(Seg me, Seg nbr, int e2Side, boolean meIsOwner, boolean dump, String dumpTag) {
        // Self's offset linkPt (end2 case): end2 - r·uVec_self.
        double selfLpx = me.e2x() - actinMonoRadius * me.ux;
        double selfLpy = me.e2y() - actinMonoRadius * me.uy;
        double selfLpz = me.e2z() - actinMonoRadius * me.uz;
        // Neighbour's connecting tip.
        double nbrTipx = (e2Side == 0) ? nbr.e1x() : nbr.e2x();
        double nbrTipy = (e2Side == 0) ? nbr.e1y() : nbr.e2y();
        double nbrTipz = (e2Side == 0) ? nbr.e1z() : nbr.e2z();
        // Neighbour's offset linkPt (offset INTO neighbour from its connecting tip):
        //   e2Side==0 (nbr via end1) → +r·uVec_nbr; e2Side==1 (nbr via end2) → −r·uVec_nbr.
        double nbrLpx = (e2Side == 0) ? (nbrTipx + actinMonoRadius * nbr.ux)
                                       : (nbrTipx - actinMonoRadius * nbr.ux);
        double nbrLpy = (e2Side == 0) ? (nbrTipy + actinMonoRadius * nbr.uy)
                                       : (nbrTipy - actinMonoRadius * nbr.uy);
        double nbrLpz = (e2Side == 0) ? (nbrTipz + actinMonoRadius * nbr.uz)
                                       : (nbrTipz - actinMonoRadius * nbr.uz);

        // Owner-perspective linkPt and ptAtEnd: owner's monomer-back point and
        // non-owner's connecting tip. Both threads compute the same line.
        double lpx, lpy, lpz, pax, pay, paz;
        if (meIsOwner) {
            lpx = selfLpx; lpy = selfLpy; lpz = selfLpz;
            pax = nbrTipx; pay = nbrTipy; paz = nbrTipz;
        } else {
            lpx = nbrLpx;  lpy = nbrLpy;  lpz = nbrLpz;
            pax = me.e2x(); pay = me.e2y(); paz = me.e2z();
        }
        double dx = pax - lpx, dy = pay - lpy, dz = paz - lpz;
        double strainDist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double inv = (strainDist > 0) ? 1.0/strainDist : 0;
        double luX = dx*inv, luY = dy*inv, luZ = dz*inv;

        // self moveCoeff: cosB squared, sign irrelevant.
        double cosB1 = me.ux*luX + me.uy*luY + me.uz*luZ;
        if (cosB1 >  1) cosB1 =  1;
        if (cosB1 < -1) cosB1 = -1;
        double cosA1 = Math.sin(accurateAcos(cosB1));
        double lSq1 = 1e-12 * me.length * me.length;
        double moveC1 = cosB1*cosB1/me.bTransGam_x
                      + cosA1*cosA1/me.bTransGam_y
                      + lSq1*cosA1*cosA1/(4.0 * me.bRotGam_y);

        double cosB2 = nbr.ux*luX + nbr.uy*luY + nbr.uz*luZ;
        if (cosB2 >  1) cosB2 =  1;
        if (cosB2 < -1) cosB2 = -1;
        double cosA2 = Math.sin(accurateAcos(cosB2));
        double lSq2 = 1e-12 * nbr.length * nbr.length;
        double moveC2 = cosB2*cosB2/nbr.bTransGam_x
                      + cosA2*cosA2/nbr.bTransGam_y
                      + lSq2*cosA2*cosA2/(4.0 * nbr.bRotGam_y);

        double forceMag = (fracMove * 1e-6 * strainDist) / (dt * (moveC1 + moveC2));
        double fSign = meIsOwner ? 1.0 : -1.0;
        double Fx = fSign*forceMag*luX, Fy = fSign*forceMag*luY, Fz = fSign*forceMag*luZ;

        // self torque: R = 0.5e-6 * length * fracR * uVec
        double Rscale = 0.5e-6 * me.length * fracR;
        double Rx = Rscale*me.ux, Ry = Rscale*me.uy, Rz = Rscale*me.uz;
        double Tx = Ry*Fz - Rz*Fy;
        double Ty = Rz*Fx - Rx*Fz;
        double Tz = Rx*Fy - Ry*Fx;

        // F4 — same end2 path:
        double nuxE = (e2Side == 0) ?  nbr.ux : -nbr.ux;
        double nuyE = (e2Side == 0) ?  nbr.uy : -nbr.uy;
        double nuzE = (e2Side == 0) ?  nbr.uz : -nbr.uz;
        double tvx = me.uy*nuzE - me.uz*nuyE;
        double tvy = me.uz*nuxE - me.ux*nuzE;
        double tvz = me.ux*nuyE - me.uy*nuxE;
        double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
        double T4x = 0, T4y = 0, T4z = 0;
        double angDeg = 0, torsionMag = 0, dotV = 0;
        double tvUx = 0, tvUy = 0, tvUz = 0;
        if (tvMag2 > 1e-30) {
            double imag = 1.0/Math.sqrt(tvMag2);
            tvUx = tvx*imag; tvUy = tvy*imag; tvUz = tvz*imag;
            dotV = me.ux*nuxE + me.uy*nuyE + me.uz*nuzE;
            if (dotV >  1) dotV =  1;
            if (dotV < -1) dotV = -1;
            angDeg = accurateAcos(dotV) * 180.0/Math.PI;
            if (filTorqSpringActive) {
                torsionMag = fracMoveTorq * filTorqSpring * angDeg;
            } else {
                double invBRG = 1.0/me.bRotGam_y + 1.0/nbr.bRotGam_y;
                torsionMag = fracMoveTorq * (Math.PI/180.0) * angDeg / (invBRG * dt);
            }
            T4x = tvUx*torsionMag; T4y = tvUy*torsionMag; T4z = tvUz*torsionMag;
        }
        double Ttotx = Tx + T4x, Ttoty = Ty + T4y, Ttotz = Tz + T4z;

        if (dump) {
            System.out.printf("    [%s.F3] linkPt    = (%.6e, %.6e, %.6e)%n", dumpTag, lpx, lpy, lpz);
            System.out.printf("    [%s.F3] ptAtSide  = (%.6e, %.6e, %.6e)%n", dumpTag, pax, pay, paz);
            System.out.printf("    [%s.F3] strainDist= %.6e µm%n", dumpTag, strainDist);
            System.out.printf("    [%s.F3] linkUVec  = (%.6e, %.6e, %.6e)%n", dumpTag, luX, luY, luZ);
            System.out.printf("    [%s.F3] cosB(self)= %.6e  moveC(self)= %.6e%n", dumpTag, cosB1, moveC1);
            System.out.printf("    [%s.F3] cosB(nbr) = %.6e  moveC(nbr) = %.6e%n", dumpTag, cosB2, moveC2);
            System.out.printf("    [%s.F3] forceMag  = %.6e N    (applied to SELF only)%n", dumpTag, forceMag);
            System.out.printf("    [%s.F3] F_self    = (%.6e, %.6e, %.6e)%n", dumpTag, Fx, Fy, Fz);
            System.out.printf("    [%s.F3] T_self_F3 = (%.6e, %.6e, %.6e)%n", dumpTag, Tx, Ty, Tz);
            System.out.printf("    [%s.F4] tvUnit    = (%.6e, %.6e, %.6e)%n", dumpTag, tvUx, tvUy, tvUz);
            System.out.printf("    [%s.F4] dotV      = %.6e  angTween= %.6e° (accurateAcos)%n", dumpTag, dotV, angDeg);
            System.out.printf("    [%s.F4] torsionMag= %.6e N·µm%n", dumpTag, torsionMag);
            System.out.printf("    [%s.F4] T_self_F4 = (%.6e, %.6e, %.6e)%n", dumpTag, T4x, T4y, T4z);
            System.out.printf("    [%s.TOT] F_self   = (%.6e, %.6e, %.6e)%n", dumpTag, Fx, Fy, Fz);
            System.out.printf("    [%s.TOT] T_self   = (%.6e, %.6e, %.6e)%n", dumpTag, Ttotx, Ttoty, Ttotz);
        }
        return new double[]{Fx, Fy, Fz, Ttotx, Ttoty, Ttotz, forceMag, angDeg, torsionMag};
    }

    static double[] deviceChainEnd1(Seg me, Seg nbr, int e1Side, boolean meIsOwner, boolean dump, String dumpTag) {
        // Self's offset linkPt (end1 case): end1 + r·uVec_self.
        double selfLpx = me.e1x() + actinMonoRadius * me.ux;
        double selfLpy = me.e1y() + actinMonoRadius * me.uy;
        double selfLpz = me.e1z() + actinMonoRadius * me.uz;
        // Neighbour's connecting tip.
        double nbrTipx = (e1Side == 0) ? nbr.e1x() : nbr.e2x();
        double nbrTipy = (e1Side == 0) ? nbr.e1y() : nbr.e2y();
        double nbrTipz = (e1Side == 0) ? nbr.e1z() : nbr.e2z();
        // Neighbour's offset linkPt.
        double nbrLpx = (e1Side == 0) ? (nbrTipx + actinMonoRadius * nbr.ux)
                                       : (nbrTipx - actinMonoRadius * nbr.ux);
        double nbrLpy = (e1Side == 0) ? (nbrTipy + actinMonoRadius * nbr.uy)
                                       : (nbrTipy - actinMonoRadius * nbr.uy);
        double nbrLpz = (e1Side == 0) ? (nbrTipz + actinMonoRadius * nbr.uz)
                                       : (nbrTipz - actinMonoRadius * nbr.uz);

        double lpx, lpy, lpz, pax, pay, paz;
        if (meIsOwner) {
            lpx = selfLpx; lpy = selfLpy; lpz = selfLpz;
            pax = nbrTipx; pay = nbrTipy; paz = nbrTipz;
        } else {
            lpx = nbrLpx;  lpy = nbrLpy;  lpz = nbrLpz;
            pax = me.e1x(); pay = me.e1y(); paz = me.e1z();
        }
        double dx = pax - lpx, dy = pay - lpy, dz = paz - lpz;
        double strainDist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double inv = (strainDist > 0) ? 1.0/strainDist : 0;
        double luX = dx*inv, luY = dy*inv, luZ = dz*inv;

        double cosB1 = me.ux*luX + me.uy*luY + me.uz*luZ;
        if (cosB1 >  1) cosB1 =  1;
        if (cosB1 < -1) cosB1 = -1;
        double cosA1 = Math.sin(accurateAcos(cosB1));
        double lSq1 = 1e-12 * me.length * me.length;
        double moveC1 = cosB1*cosB1/me.bTransGam_x
                      + cosA1*cosA1/me.bTransGam_y
                      + lSq1*cosA1*cosA1/(4.0 * me.bRotGam_y);

        double cosB2 = nbr.ux*luX + nbr.uy*luY + nbr.uz*luZ;
        if (cosB2 >  1) cosB2 =  1;
        if (cosB2 < -1) cosB2 = -1;
        double cosA2 = Math.sin(accurateAcos(cosB2));
        double lSq2 = 1e-12 * nbr.length * nbr.length;
        double moveC2 = cosB2*cosB2/nbr.bTransGam_x
                      + cosA2*cosA2/nbr.bTransGam_y
                      + lSq2*cosA2*cosA2/(4.0 * nbr.bRotGam_y);

        double forceMag = (fracMove * 1e-6 * strainDist) / (dt * (moveC1 + moveC2));
        double fSign = meIsOwner ? 1.0 : -1.0;
        double Fx = fSign*forceMag*luX, Fy = fSign*forceMag*luY, Fz = fSign*forceMag*luZ;

        // self torque: R = -0.5e-6 * length * fracR * uVec  (i.e. +half_length*fracR*uVecR)
        double Rscale = -0.5e-6 * me.length * fracR;
        double Rx = Rscale*me.ux, Ry = Rscale*me.uy, Rz = Rscale*me.uz;
        double Tx = Ry*Fz - Rz*Fy;
        double Ty = Rz*Fx - Rx*Fz;
        double Tz = Rx*Fy - Ry*Fx;

        double nuxE = (e1Side == 0) ?  nbr.ux : -nbr.ux;
        double nuyE = (e1Side == 0) ?  nbr.uy : -nbr.uy;
        double nuzE = (e1Side == 0) ?  nbr.uz : -nbr.uz;
        double mux = -me.ux, muy = -me.uy, muz = -me.uz;
        double tvx = muy*nuzE - muz*nuyE;
        double tvy = muz*nuxE - mux*nuzE;
        double tvz = mux*nuyE - muy*nuxE;
        double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
        double T4x = 0, T4y = 0, T4z = 0;
        double tvUx = 0, tvUy = 0, tvUz = 0, dotV = 0, angDeg = 0, torsionMag = 0;
        if (tvMag2 > 1e-30) {
            double imag = 1.0/Math.sqrt(tvMag2);
            tvUx = tvx*imag; tvUy = tvy*imag; tvUz = tvz*imag;
            dotV = mux*nuxE + muy*nuyE + muz*nuzE;
            if (dotV >  1) dotV =  1;
            if (dotV < -1) dotV = -1;
            angDeg = accurateAcos(dotV) * 180.0/Math.PI;
            if (filTorqSpringActive) {
                torsionMag = fracMoveTorq * filTorqSpring * angDeg;
            } else {
                double invBRG = 1.0/me.bRotGam_y + 1.0/nbr.bRotGam_y;
                torsionMag = fracMoveTorq * (Math.PI/180.0) * angDeg / (invBRG * dt);
            }
            T4x = tvUx*torsionMag; T4y = tvUy*torsionMag; T4z = tvUz*torsionMag;
        }
        double Ttotx = Tx + T4x, Ttoty = Ty + T4y, Ttotz = Tz + T4z;

        if (dump) {
            System.out.printf("    [%s.F3] linkPt    = (%.6e, %.6e, %.6e)%n", dumpTag, lpx, lpy, lpz);
            System.out.printf("    [%s.F3] ptAtSide  = (%.6e, %.6e, %.6e)%n", dumpTag, pax, pay, paz);
            System.out.printf("    [%s.F3] strainDist= %.6e µm%n", dumpTag, strainDist);
            System.out.printf("    [%s.F3] linkUVec  = (%.6e, %.6e, %.6e)%n", dumpTag, luX, luY, luZ);
            System.out.printf("    [%s.F3] cosB(self)= %.6e  moveC(self)= %.6e%n", dumpTag, cosB1, moveC1);
            System.out.printf("    [%s.F3] cosB(nbr) = %.6e  moveC(nbr) = %.6e%n", dumpTag, cosB2, moveC2);
            System.out.printf("    [%s.F3] forceMag  = %.6e N    (applied to SELF only)%n", dumpTag, forceMag);
            System.out.printf("    [%s.F3] F_self    = (%.6e, %.6e, %.6e)%n", dumpTag, Fx, Fy, Fz);
            System.out.printf("    [%s.F3] T_self_F3 = (%.6e, %.6e, %.6e)%n", dumpTag, Tx, Ty, Tz);
            System.out.printf("    [%s.F4] tvUnit    = (%.6e, %.6e, %.6e)%n", dumpTag, tvUx, tvUy, tvUz);
            System.out.printf("    [%s.F4] dotV      = %.6e  angTween= %.6e° (accurateAcos)%n", dumpTag, dotV, angDeg);
            System.out.printf("    [%s.F4] torsionMag= %.6e N·µm%n", dumpTag, torsionMag);
            System.out.printf("    [%s.F4] T_self_F4 = (%.6e, %.6e, %.6e)%n", dumpTag, T4x, T4y, T4z);
            System.out.printf("    [%s.TOT] F_self   = (%.6e, %.6e, %.6e)%n", dumpTag, Fx, Fy, Fz);
            System.out.printf("    [%s.TOT] T_self   = (%.6e, %.6e, %.6e)%n", dumpTag, Ttotx, Ttoty, Ttotz);
        }
        return new double[]{Fx, Fy, Fz, Ttotx, Ttoty, Ttotz, forceMag, angDeg, torsionMag};
    }

    // ----------------------------------------------------------
    // Build a 3-seg chain with one interior bending joint between
    // seg0 and seg1 of angle theta (radians), and seg1-seg2 straight
    // (zero bend) extension. Tips coincide (zero gap at links).
    //
    // seg0:  uVec = (1,0,0), end2 at origin
    // seg1:  uVec = (cos θ, sin θ, 0), end1 at origin
    // seg2:  uVec = (cos θ, sin θ, 0), end1 at seg1.end2
    // ----------------------------------------------------------
    static Seg[] buildHeldChain(double thetaRad) {
        // seg0: end2 = (0,0,0), uVec = +x → center = -0.5*segLen * (1,0,0)
        Seg s0 = new Seg("seg0", -0.5*segLen, 0, 0,  1, 0, 0,  segLen);
        // seg1: end1 = (0,0,0), uVec = (cosθ, sinθ, 0) → center = 0.5*segLen * uVec
        double u1x = Math.cos(thetaRad), u1y = Math.sin(thetaRad);
        Seg s1 = new Seg("seg1", 0.5*segLen*u1x, 0.5*segLen*u1y, 0,  u1x, u1y, 0,  segLen);
        // seg2: end1 = seg1.end2; uVec = (cosθ, sinθ, 0) → center = end1 + 0.5*segLen*uVec
        double s2e1x = segLen*u1x, s2e1y = segLen*u1y;
        Seg s2 = new Seg("seg2", s2e1x + 0.5*segLen*u1x, s2e1y + 0.5*segLen*u1y, 0,
                                  u1x, u1y, 0, segLen);
        return new Seg[]{s0, s1, s2};
    }

    static void dumpHeader(String title) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + title);
        System.out.println("================================================================");
    }

    static void runAtTheta(double thetaDeg, boolean dumpVerbose) {
        double thetaRad = thetaDeg * Math.PI / 180.0;
        Seg[] segs = buildHeldChain(thetaRad);
        Seg s0 = segs[0], s1 = segs[1], s2 = segs[2];
        if (dumpVerbose) {
            dumpHeader(String.format("theta = %.6f° (%.6e rad)", thetaDeg, thetaRad));
            System.out.printf("  seg0: c=(%.6e,%.6e,%.6e) u=(%.6e,%.6e,%.6e) L=%.6e%n",
                s0.cx, s0.cy, s0.cz, s0.ux, s0.uy, s0.uz, s0.length);
            System.out.printf("  seg1: c=(%.6e,%.6e,%.6e) u=(%.6e,%.6e,%.6e) L=%.6e%n",
                s1.cx, s1.cy, s1.cz, s1.ux, s1.uy, s1.uz, s1.length);
            System.out.printf("  seg2: c=(%.6e,%.6e,%.6e) u=(%.6e,%.6e,%.6e) L=%.6e%n",
                s2.cx, s2.cy, s2.cz, s2.ux, s2.uy, s2.uz, s2.length);
            System.out.printf("  drag: bTransGam.x=%.6e  bTransGam.y=%.6e  bRotGam.y=%.6e%n",
                s0.bTransGam_x, s0.bTransGam_y, s0.bRotGam_y);
        }

        // ---- F3 / F4 at the BENT joint (seg0 ↔ seg1) ----
        if (dumpVerbose) {
            System.out.println();
            System.out.println("  --- BENT JOINT (seg0.end2 <-> seg1.end1) ---");
            System.out.println();
            System.out.println("  CPU FORMULA (owner = seg0, seg0.end2Side=0 because ptAtEnd2 == seg1.end1Pt):");
        }
        double[] cpuF3 = cpuF3End2(s0, s1, /*e2Side=*/0, dumpVerbose);
        double[] cpuF4 = cpuF4End2(s0, s1, /*e2Side=*/0, dumpVerbose);
        // CPU totals at bent joint
        // seg0 (self): F3.F + F3.T + F4.T (and F4 contributes only torque)
        double cpuF_s0x = cpuF3[0], cpuF_s0y = cpuF3[1], cpuF_s0z = cpuF3[2];
        double cpuT_s0x = cpuF3[3] + cpuF4[0];
        double cpuT_s0y = cpuF3[4] + cpuF4[1];
        double cpuT_s0z = cpuF3[5] + cpuF4[2];
        // seg1 (nbr): -F3.F applied + F3.T_nbr + F4.T_nbr
        double cpuF_s1x = cpuF3[6], cpuF_s1y = cpuF3[7], cpuF_s1z = cpuF3[8];
        double cpuT_s1x = cpuF3[9]  + cpuF4[3];
        double cpuT_s1y = cpuF3[10] + cpuF4[4];
        double cpuT_s1z = cpuF3[11] + cpuF4[5];

        if (dumpVerbose) {
            System.out.println();
            System.out.println("  DEVICE FORMULA (seg0 thread, end2 block):");
        }
        // BENT joint owner = seg0 (slot 0 < slot 1).
        double[] devS0 = deviceChainEnd2(s0, s1, /*e2Side=*/0, /*meIsOwner=*/true,  dumpVerbose, "DEV.seg0.end2");
        if (dumpVerbose) {
            System.out.println();
            System.out.println("  DEVICE FORMULA (seg1 thread, end1 block — the OTHER side of the same pair):");
        }
        double[] devS1 = deviceChainEnd1(s1, s0, /*e1Side=*/1, /*meIsOwner=*/false, dumpVerbose, "DEV.seg1.end1");

        // ---- F3 / F4 at the STRAIGHT joint (seg1 ↔ seg2) ----
        // For straight, angTween = 0 and torsion contributes 0; strainDist = actinMonoRadius
        // (uVec aligned at both ends).
        if (dumpVerbose) {
            System.out.println();
            System.out.println("  --- STRAIGHT JOINT (seg1.end2 <-> seg2.end1) ---");
            System.out.println();
            System.out.println("  CPU FORMULA (owner = seg1, e2Side=0):");
        }
        double[] cpuF3b = cpuF3End2(s1, s2, /*e2Side=*/0, dumpVerbose);
        double[] cpuF4b = cpuF4End2(s1, s2, /*e2Side=*/0, dumpVerbose);
        if (dumpVerbose) {
            System.out.println();
            System.out.println("  DEVICE FORMULA (seg1 thread, end2 block):");
        }
        // STRAIGHT joint owner = seg1 (slot 1 < slot 2).
        double[] devS1_e2 = deviceChainEnd2(s1, s2, /*e2Side=*/0, /*meIsOwner=*/true,  dumpVerbose, "DEV.seg1.end2");
        if (dumpVerbose) {
            System.out.println();
            System.out.println("  DEVICE FORMULA (seg2 thread, end1 block):");
        }
        double[] devS2 = deviceChainEnd1(s2, s1, /*e1Side=*/1, /*meIsOwner=*/false, dumpVerbose, "DEV.seg2.end1");

        // ---- Comparison summary (always dumped, even when not verbose) ----
        System.out.printf("%n  --- SUMMARY @ theta=%.4f° ---%n", thetaDeg);
        // BENT JOINT (seg0 ↔ seg1)
        System.out.printf("  BENT joint: CPU F3 forceMag = %.6e ; DEV(seg0) forceMag = %.6e ; DEV(seg1) forceMag = %.6e%n",
            Math.sqrt(cpuF3[0]*cpuF3[0]+cpuF3[1]*cpuF3[1]+cpuF3[2]*cpuF3[2])
                / (segLen == 0 ? 1 : 1),  // already magnitude basically
            Math.sqrt(devS0[0]*devS0[0]+devS0[1]*devS0[1]+devS0[2]*devS0[2]),
            Math.sqrt(devS1[0]*devS1[0]+devS1[1]*devS1[1]+devS1[2]*devS1[2]));
        System.out.printf("  BENT joint: CPU F4 angTween = %.6e° ; DEV(seg0) angTween = %.6e° ; DEV(seg1) angTween = %.6e°%n",
            thetaDeg,           // CPU computes the same angTween regardless of side
            devS0[7], devS1[7]);
        System.out.printf("  BENT joint: CPU F4 torsionMag = %.6e ; DEV(seg0) torsionMag = %.6e ; DEV(seg1) torsionMag = %.6e%n",
            // CPU torsionMag at bent joint
            cpuF4Cells_torsionMag(s0, s1, /*e2Side=*/0),
            devS0[8], devS1[8]);
        // Force pair-symmetry (Newton 3) — device only
        double sumFx = devS0[0] + devS1[0];
        double sumFy = devS0[1] + devS1[1];
        double sumFz = devS0[2] + devS1[2];
        double sumTx = devS0[3] + devS1[3];
        double sumTy = devS0[4] + devS1[4];
        double sumTz = devS0[5] + devS1[5];
        System.out.printf("  BENT joint: DEV pair-force sum (F_s0+F_s1) = (%.6e, %.6e, %.6e)  ← should be (0,0,0) for Newton-3%n", sumFx, sumFy, sumFz);
        System.out.printf("  BENT joint: DEV pair-torque sum (T_s0+T_s1) = (%.6e, %.6e, %.6e)  ← NOT expected to be 0 (lever arms differ)%n", sumTx, sumTy, sumTz);

        // CPU "pair-force sum" by construction = 0 (Fopp = -F applied by hand)
        double cpuSumFx = cpuF_s0x + cpuF_s1x;
        double cpuSumFy = cpuF_s0y + cpuF_s1y;
        double cpuSumFz = cpuF_s0z + cpuF_s1z;
        System.out.printf("  BENT joint: CPU pair-force sum             = (%.6e, %.6e, %.6e)  ← 0 by FIAT (owner applies +F/-F)%n", cpuSumFx, cpuSumFy, cpuSumFz);

        // F4 owner-vs-neighbor torque sum: CPU applies +T/-T, so sum is 0.
        // Device pair F4 sums = (T_self_F4 + T_nbr_F4). nbr thread independently
        // produces -tvU_self * τ_self if angle and τ match.
        // Print CPU vs device torque-on-seg0 (the slot reading the cross-product).
        double cpuT0x_F4 = cpuF4[0], cpuT0y_F4 = cpuF4[1], cpuT0z_F4 = cpuF4[2];
        // Device seg0 F4 contribution = devS0_T_F4 = devS0[3..5] - devS0_T_F3
        // We need just F4. We can isolate by zeroing-F3 in dump (handled below).
        // Just print F4 torsionMag for direct comparison — sufficient for sweep.

        // STRAIGHT JOINT (should yield identical F3, zero F4 in BOTH)
        double cpuMagB = Math.sqrt(cpuF3b[0]*cpuF3b[0]+cpuF3b[1]*cpuF3b[1]+cpuF3b[2]*cpuF3b[2]);
        double devMagB_s1 = Math.sqrt(devS1_e2[0]*devS1_e2[0]+devS1_e2[1]*devS1_e2[1]+devS1_e2[2]*devS1_e2[2]);
        double devMagB_s2 = Math.sqrt(devS2[0]*devS2[0]+devS2[1]*devS2[1]+devS2[2]*devS2[2]);
        System.out.printf("  STRAIGHT joint: CPU F3 |F| = %.6e ; DEV(seg1) |F| = %.6e ; DEV(seg2) |F| = %.6e%n",
            cpuMagB, devMagB_s1, devMagB_s2);
        System.out.printf("  STRAIGHT joint: F4 torsionMag — CPU %.6e ; DEV(seg1) %.6e ; DEV(seg2) %.6e%n",
            cpuF4Cells_torsionMag(s1, s2, /*e2Side=*/0),
            devS1_e2[8], devS2[8]);
        System.out.println();
    }

    // Helper: pull CPU torsionMag (same logic as cpuF4End2 but only needs the magnitude).
    static double cpuF4Cells_torsionMag(Seg me, Seg nbr, int e2Side) {
        double nuxE = (e2Side == 0) ?  nbr.ux : -nbr.ux;
        double nuyE = (e2Side == 0) ?  nbr.uy : -nbr.uy;
        double nuzE = (e2Side == 0) ?  nbr.uz : -nbr.uz;
        double dot = me.ux*nuxE + me.uy*nuyE + me.uz*nuzE;
        if (dot >  1) dot =  1;
        if (dot < -1) dot = -1;
        double angDeg = fastAcos(dot) * 180.0 / Math.PI;
        if (filTorqSpringActive) return fracMoveTorq * filTorqSpring * angDeg;
        double invBRG = 1.0/me.bRotGam_y + 1.0/nbr.bRotGam_y;
        return fracMoveTorq * (Math.PI/180.0) * angDeg / (invBRG * dt);
    }

    public static void main(String[] args) {
        System.out.println("HeldChainF3F4Diag — instrument F3/F4 intermediates, CPU vs device kernel formula.");
        System.out.printf("Constants: aeta=%g  dt=%g  fracMove=%g  fracR=%g  fracMoveTorq=%g%n",
                          aeta, dt, fracMove, fracR, fracMoveTorq);
        System.out.printf("Geometry: actinMonoRadius=%g  filRadius=%g  monCt=%d  segLen=%g µm%n",
                          actinMonoRadius, actinFilRadius, monCt, segLen);

        double[] thetas;
        if (args.length == 0) {
            thetas = new double[]{1.0, 5.0, 10.0, 20.0, 45.0};
        } else {
            thetas = new double[args.length];
            for (int i = 0; i < args.length; i++) thetas[i] = Double.parseDouble(args[i]);
        }

        // First: verbose dump at theta = 10° (mid-range, easy to read).
        runAtTheta(10.0, true);

        // Then: terse sweep across all thetas (verbose=false at the others).
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  SWEEP: terse summary across angles");
        System.out.println("================================================================");
        for (double th : thetas) {
            runAtTheta(th, false);
        }
    }
}
