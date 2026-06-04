package boxOfActin;

// HeldBoundMotorDiag — DIAGNOSTIC ONLY (Phase 2 F8/F9/F10 cheap probe).
//
// Build a held single-bound-motor configuration in a prescribed
// non-degenerate geometry. Evaluate THREE formulas on the SAME frozen pose:
//   1. CPU formula (MyoFilLink.addForces / alignUVecTorque / alignYVecTorque).
//   2. Device motor-side kernel formula (GPUMoveThing.motorForceKernel).
//   3. Device seg-side kernel formula (GPUMoveThing.segMotorForceKernel).
//
// Verifies (per JOURNAL "Motor force port (F8-F10) — survey" §9, Option A,
// extended to the cheap-probe-then-ensemble discipline):
//   A. Device motor force + both alignment torques match CPU to the float-
//      noise floor.
//   B. forceDotFil sign AND magnitude match CPU (load-bearing for catch/slip
//      release kinetics).
//   C. Motor-side +F and seg-side -F sum to zero (Newton-3 pair-symmetry).
//   D. F9 motor-side torque and F9 seg-side torque sum to zero (alignment
//      pair is anti-parallel); same for F10.
//
// Pure Java, no TornadoVM init — physical constants are hard-coded to match
// Env defaults at the time of writing. Float32 lowering of the device
// kernels is not under test here; the test is the ALGEBRAIC equivalence
// in double precision. Final float32 behaviour is exercised by the paired
// ensemble that follows this probe.
//
// Usage: javac the file with the rest of boxOfActin, then
//   java -cp ".:libs/*" boxOfActin.HeldBoundMotorDiag
//
// Exit code 0 = PASS (all checks under the tolerance). Non-zero = FAIL.

public class HeldBoundMotorDiag {

    // ---- physical constants (Env defaults) ----
    static final double aeta            = 0.1;       // Pa·s
    static final double dt              = 1.0e-4;    // s
    static final double actinMonoRadius = 0.0027;    // µm
    static final double actinFilRadius  = 0.0035;    // µm  (Env.actinWidth / 2)
    static final int    monCt           = 32;        // bench default
    static final double segLen          = (monCt + 1) * actinMonoRadius;  // µm
    // MyoFilLink hard-coded constant.
    static final double myoSpring       = 1.0e-9;    // N/µm
    // Myosin parameters.
    static final double motorLen        = 0.020;     // µm — Env.myoMotorLength default
    static final double motorRadius     = 0.01;      // µm — MyoMotor.radius
    static final double j1FracMoveTorq  = 0.5;       // Env.myoJ1FracMoveTorq default
    static final double uncockedAng     = 90.0;      // degrees — Myosin.uncockedMotor_ActinAngle
    static final double cockedAng       = 120.0;     // degrees — Myosin.cockedMotor_ActinAngle
    // Drag coefficient empirical fits (mirror Thing / MyoMotor).
    static final double aParallel       = -0.20;
    static final double aOrthog         =  0.84;
    static final double aTurning        = -0.662;

    // ---- pose containers ----
    static class Pose {
        double cx, cy, cz;     // µm
        double ux, uy, uz;     // unit
        double yx_, yy_, yz_;  // unit
        double length;         // µm — for FilSegment; ignored for motor (uses motorLen)
        double bTransGam_x, bTransGam_y;
        double bRotGam_x, bRotGam_y;
        String tag;
        Pose(String tag, double cx, double cy, double cz,
                          double ux, double uy, double uz,
                          double yx, double yy, double yz,
                          double length) {
            this.tag = tag;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.ux = ux; this.uy = uy; this.uz = uz;
            this.yx_ = yx; this.yy_ = yy; this.yz_ = yz;
            this.length = length;
        }
    }

    static Pose makeFilSegPose(double length) {
        Pose p = new Pose("seg", 0, 0, 0, 1, 0, 0, 0, 1, 0, length);
        double L_m = 1.0e-6 * length;
        double r_m = 1.0e-6 * actinFilRadius;
        double denomLog = Math.log(L_m / (2.0 * r_m));
        p.bTransGam_x = (2.0 * Math.PI * aeta * L_m) / (denomLog + aParallel);
        p.bTransGam_y = (4.0 * Math.PI * aeta * L_m) / (denomLog + aOrthog);
        // FilSegment uses bRotGam_y = (π·aeta·L^3)/(3·(denomLog + aTurning))
        // and bRotGam_x = (8/3)·π·aeta·r^3 (the rod's bRotGam_x is around the
        // long axis, smaller).
        p.bRotGam_y = (Math.PI * aeta * L_m * L_m * L_m) / (3.0 * (denomLog + aTurning));
        p.bRotGam_x = 8.0 / 3.0 * Math.PI * aeta * r_m * r_m * r_m;
        return p;
    }

    static Pose makeMotorPose() {
        // Motor uses the spherical-bead model: bTransGam.x = bTransGam.y =
        // bTransGam.z = 6πη·r and bRotGam.x = bRotGam.y = bRotGam.z = 8πη·r³.
        Pose p = new Pose("motor", 0, 0, 0, 0, 0, 1, 1, 0, 0, motorLen);
        double r_m = 1.0e-6 * motorRadius;
        p.bTransGam_x = 6.0 * Math.PI * aeta * r_m;
        p.bTransGam_y = p.bTransGam_x;
        p.bRotGam_x = 8.0 * Math.PI * aeta * r_m * r_m * r_m;
        p.bRotGam_y = p.bRotGam_x;
        return p;
    }

    // ---- CPU acos (Pt3D.fastAcos) ----
    static double fastAcos(double x) {
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

    // ---- Device acos (GPUMoveThing.accurateAcos) ----
    static double accurateAcos(double x) {
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
    // CPU result (mirrors MyoFilLink.addForces + alignUVecTorque + alignYVecTorque).
    // Returns: forceMag, forceDotFil,
    //          F_motor (x,y,z), T_motor_F8 (x,y,z), T_motor_F9 (x,y,z), T_motor_F10 (x,y,z),
    //          F_seg  (x,y,z), T_seg_F8  (x,y,z), T_seg_F9  (x,y,z), T_seg_F10  (x,y,z)
    // (26 values).
    // ----------------------------------------------------------
    static double[] cpuFormula(Pose seg, Pose motor, double posOnSeg, boolean cocked, boolean dump) {
        // motorPt = motor.coord + 0.5·motorLen·motor.uVec
        double mpx = motor.cx + 0.5*motorLen*motor.ux;
        double mpy = motor.cy + 0.5*motorLen*motor.uy;
        double mpz = motor.cz + 0.5*motorLen*motor.uz;
        // attachPt = seg.end1 + posOnSeg·seg.uVec; seg.end1 = seg.coord − 0.5·segLen·seg.uVec.
        double ax = seg.cx - 0.5*seg.length*seg.ux + posOnSeg*seg.ux;
        double ay = seg.cy - 0.5*seg.length*seg.uy + posOnSeg*seg.uy;
        double az = seg.cz - 0.5*seg.length*seg.uz + posOnSeg*seg.uz;

        // F8 — spring force.
        double dist = Math.sqrt((mpx-ax)*(mpx-ax) + (mpy-ay)*(mpy-ay) + (mpz-az)*(mpz-az));
        if (dist < 0) dist = 0;
        double forceMag = dist * myoSpring;
        // unitVec(attachPt, motorPt) = (attachPt − motorPt) / dist
        double invd = (dist > 0) ? 1.0/dist : 0;
        double fux = (ax - mpx) * invd;
        double fuy = (ay - mpy) * invd;
        double fuz = (az - mpz) * invd;
        double Fx = forceMag * fux;
        double Fy = forceMag * fuy;
        double Fz = forceMag * fuz;
        // Motor side: incForceSum(F, motorPt).
        //   R_motor = (motorPt − motor.coord) · 1e-6 = 0.5·motorLen·motor.uVec · 1e-6.
        double Rmx = 0.5*motorLen*motor.ux*1.0e-6;
        double Rmy = 0.5*motorLen*motor.uy*1.0e-6;
        double Rmz = 0.5*motorLen*motor.uz*1.0e-6;
        double Tm_F8x = Rmy*Fz - Rmz*Fy;
        double Tm_F8y = Rmz*Fx - Rmx*Fz;
        double Tm_F8z = Rmx*Fy - Rmy*Fx;
        // forceDotFil: dot(F, seg.uVec) — BEFORE seg-side F-flip.
        double forceDotFil = Fx*seg.ux + Fy*seg.uy + Fz*seg.uz;
        // Seg side: scale(-1), incForceSum(-F, attachPt).
        //   R_seg = (attachPt − seg.coord) · 1e-6 = (posOnSeg − 0.5·segLen)·seg.uVec · 1e-6.
        double Rsx = (posOnSeg - 0.5*seg.length)*seg.ux*1.0e-6;
        double Rsy = (posOnSeg - 0.5*seg.length)*seg.uy*1.0e-6;
        double Rsz = (posOnSeg - 0.5*seg.length)*seg.uz*1.0e-6;
        double Ts_F8x = Rsy*(-Fz) - Rsz*(-Fy);
        double Ts_F8y = Rsz*(-Fx) - Rsx*(-Fz);
        double Ts_F8z = Rsx*(-Fy) - Rsy*(-Fx);

        // F9 — uVec alignment torque.
        double tvx = seg.uy*motor.uz - seg.uz*motor.uy;
        double tvy = seg.uz*motor.ux - seg.ux*motor.uz;
        double tvz = seg.ux*motor.uy - seg.uy*motor.ux;
        double tvmag = Math.sqrt(tvx*tvx + tvy*tvy + tvz*tvz);
        double tvUx = 0, tvUy = 0, tvUz = 0;
        double Tm_F9x = 0, Tm_F9y = 0, Tm_F9z = 0;
        double Ts_F9x = 0, Ts_F9y = 0, Ts_F9z = 0;
        if (tvmag > 0) {
            tvUx = tvx/tvmag; tvUy = tvy/tvmag; tvUz = tvz/tvmag;
            double dotV = seg.ux*motor.ux + seg.uy*motor.uy + seg.uz*motor.uz;
            if (dotV >  1) dotV =  1;
            if (dotV < -1) dotV = -1;
            double angTween = fastAcos(dotV)*180.0/Math.PI;
            double angRelaxed = cocked ? cockedAng : uncockedAng;
            double angD = angTween - angRelaxed;
            double invBRG = 1.0/motor.bRotGam_y + 1.0/seg.bRotGam_y;
            double torsionMag = j1FracMoveTorq * (Math.PI/180.0) * angD / (invBRG * dt);
            Ts_F9x = tvUx*torsionMag; Ts_F9y = tvUy*torsionMag; Ts_F9z = tvUz*torsionMag;
            Tm_F9x = -Ts_F9x;          Tm_F9y = -Ts_F9y;          Tm_F9z = -Ts_F9z;
        }

        // F10 — yVec alignment torque.
        double yvx = seg.yy_*motor.yz_ - seg.yz_*motor.yy_;
        double yvy = seg.yz_*motor.yx_ - seg.yx_*motor.yz_;
        double yvz = seg.yx_*motor.yy_ - seg.yy_*motor.yx_;
        double yvmag = Math.sqrt(yvx*yvx + yvy*yvy + yvz*yvz);
        double Tm_F10x = 0, Tm_F10y = 0, Tm_F10z = 0;
        double Ts_F10x = 0, Ts_F10y = 0, Ts_F10z = 0;
        if (yvmag > 0) {
            double yvUx = yvx/yvmag, yvUy = yvy/yvmag, yvUz = yvz/yvmag;
            double dotV = seg.yx_*motor.yx_ + seg.yy_*motor.yy_ + seg.yz_*motor.yz_;
            if (dotV >  1) dotV =  1;
            if (dotV < -1) dotV = -1;
            double angTween = fastAcos(dotV)*180.0/Math.PI;
            // No angRelaxed offset.
            double angD = angTween;
            double invBRG = 1.0/motor.bRotGam_x + 1.0/seg.bRotGam_x;
            double torsionMag = j1FracMoveTorq * (Math.PI/180.0) * angD / (invBRG * dt);
            Ts_F10x = yvUx*torsionMag; Ts_F10y = yvUy*torsionMag; Ts_F10z = yvUz*torsionMag;
            Tm_F10x = -Ts_F10x;         Tm_F10y = -Ts_F10y;         Tm_F10z = -Ts_F10z;
        }

        if (dump) {
            System.out.printf("    [CPU] forceMag    = %.6e N%n", forceMag);
            System.out.printf("    [CPU] forceDotFil = %.6e N%n", forceDotFil);
            System.out.printf("    [CPU] F_motor     = (%.6e, %.6e, %.6e)%n", Fx, Fy, Fz);
            System.out.printf("    [CPU] T_motor_F8  = (%.6e, %.6e, %.6e)%n", Tm_F8x, Tm_F8y, Tm_F8z);
            System.out.printf("    [CPU] T_motor_F9  = (%.6e, %.6e, %.6e)%n", Tm_F9x, Tm_F9y, Tm_F9z);
            System.out.printf("    [CPU] T_motor_F10 = (%.6e, %.6e, %.6e)%n", Tm_F10x, Tm_F10y, Tm_F10z);
            System.out.printf("    [CPU] F_seg       = (%.6e, %.6e, %.6e)%n", -Fx, -Fy, -Fz);
            System.out.printf("    [CPU] T_seg_F8    = (%.6e, %.6e, %.6e)%n", Ts_F8x, Ts_F8y, Ts_F8z);
            System.out.printf("    [CPU] T_seg_F9    = (%.6e, %.6e, %.6e)%n", Ts_F9x, Ts_F9y, Ts_F9z);
            System.out.printf("    [CPU] T_seg_F10   = (%.6e, %.6e, %.6e)%n", Ts_F10x, Ts_F10y, Ts_F10z);
        }
        return new double[]{
            forceMag, forceDotFil,
            Fx, Fy, Fz, Tm_F8x, Tm_F8y, Tm_F8z, Tm_F9x, Tm_F9y, Tm_F9z, Tm_F10x, Tm_F10y, Tm_F10z,
            -Fx, -Fy, -Fz, Ts_F8x, Ts_F8y, Ts_F8z, Ts_F9x, Ts_F9y, Ts_F9z, Ts_F10x, Ts_F10y, Ts_F10z
        };
    }

    // ----------------------------------------------------------
    // Device motor-side kernel formula (mirrors GPUMoveThing.motorForceKernel).
    // Returns: forceMag, forceDotFil, F_motor (x,y,z), T_motor_F8 (x,y,z),
    //          T_motor_F9 (x,y,z), T_motor_F10 (x,y,z)
    // (14 values).
    // ----------------------------------------------------------
    static double[] deviceMotor(Pose seg, Pose motor, double posOnSeg, boolean cocked, boolean dump) {
        double halfMotor = 0.5 * motorLen;
        double halfSeg   = 0.5 * seg.length;
        double mpx = motor.cx + halfMotor*motor.ux;
        double mpy = motor.cy + halfMotor*motor.uy;
        double mpz = motor.cz + halfMotor*motor.uz;
        double posOff = posOnSeg - halfSeg;
        double apx = seg.cx + posOff*seg.ux;
        double apy = seg.cy + posOff*seg.uy;
        double apz = seg.cz + posOff*seg.uz;
        double dxv = mpx - apx, dyv = mpy - apy, dzv = mpz - apz;
        double dist = Math.sqrt(dxv*dxv + dyv*dyv + dzv*dzv);
        double forceMag = dist * myoSpring;
        double invd = (dist > 0) ? 1.0/dist : 0;
        double fux = -dxv*invd, fuy = -dyv*invd, fuz = -dzv*invd;
        double Fx = forceMag*fux, Fy = forceMag*fuy, Fz = forceMag*fuz;
        double forceDotFil = Fx*seg.ux + Fy*seg.uy + Fz*seg.uz;
        double Rmx = halfMotor*motor.ux*1.0e-6;
        double Rmy = halfMotor*motor.uy*1.0e-6;
        double Rmz = halfMotor*motor.uz*1.0e-6;
        double Tm_F8x = Rmy*Fz - Rmz*Fy;
        double Tm_F8y = Rmz*Fx - Rmx*Fz;
        double Tm_F8z = Rmx*Fy - Rmy*Fx;

        double Tm_F9x = 0, Tm_F9y = 0, Tm_F9z = 0;
        {
            double tvx = seg.uy*motor.uz - seg.uz*motor.uy;
            double tvy = seg.uz*motor.ux - seg.ux*motor.uz;
            double tvz = seg.ux*motor.uy - seg.uy*motor.ux;
            double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
            if (tvMag2 > 0) {
                double invMag = 1.0/Math.sqrt(tvMag2);
                tvx *= invMag; tvy *= invMag; tvz *= invMag;
                double dotV = seg.ux*motor.ux + seg.uy*motor.uy + seg.uz*motor.uz;
                if (dotV > 1) dotV = 1; if (dotV < -1) dotV = -1;
                double angTween = accurateAcos(dotV)*180.0/Math.PI;
                double angRelaxed = cocked ? cockedAng : uncockedAng;
                double angD = angTween - angRelaxed;
                double invBRG = 1.0/motor.bRotGam_y + 1.0/seg.bRotGam_y;
                double torsionMag = j1FracMoveTorq*(Math.PI/180.0)*angD/(invBRG*dt);
                Tm_F9x = -tvx*torsionMag; Tm_F9y = -tvy*torsionMag; Tm_F9z = -tvz*torsionMag;
            }
        }
        double Tm_F10x = 0, Tm_F10y = 0, Tm_F10z = 0;
        {
            double tvx = seg.yy_*motor.yz_ - seg.yz_*motor.yy_;
            double tvy = seg.yz_*motor.yx_ - seg.yx_*motor.yz_;
            double tvz = seg.yx_*motor.yy_ - seg.yy_*motor.yx_;
            double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
            if (tvMag2 > 0) {
                double invMag = 1.0/Math.sqrt(tvMag2);
                tvx *= invMag; tvy *= invMag; tvz *= invMag;
                double dotV = seg.yx_*motor.yx_ + seg.yy_*motor.yy_ + seg.yz_*motor.yz_;
                if (dotV > 1) dotV = 1; if (dotV < -1) dotV = -1;
                double angTween = accurateAcos(dotV)*180.0/Math.PI;
                double angD = angTween;
                double invBRG = 1.0/motor.bRotGam_x + 1.0/seg.bRotGam_x;
                double torsionMag = j1FracMoveTorq*(Math.PI/180.0)*angD/(invBRG*dt);
                Tm_F10x = -tvx*torsionMag; Tm_F10y = -tvy*torsionMag; Tm_F10z = -tvz*torsionMag;
            }
        }
        if (dump) {
            System.out.printf("    [DEV.motor] forceMag    = %.6e N%n", forceMag);
            System.out.printf("    [DEV.motor] forceDotFil = %.6e N%n", forceDotFil);
            System.out.printf("    [DEV.motor] F_motor     = (%.6e, %.6e, %.6e)%n", Fx, Fy, Fz);
            System.out.printf("    [DEV.motor] T_motor_F8  = (%.6e, %.6e, %.6e)%n", Tm_F8x, Tm_F8y, Tm_F8z);
            System.out.printf("    [DEV.motor] T_motor_F9  = (%.6e, %.6e, %.6e)%n", Tm_F9x, Tm_F9y, Tm_F9z);
            System.out.printf("    [DEV.motor] T_motor_F10 = (%.6e, %.6e, %.6e)%n", Tm_F10x, Tm_F10y, Tm_F10z);
        }
        return new double[]{
            forceMag, forceDotFil,
            Fx, Fy, Fz, Tm_F8x, Tm_F8y, Tm_F8z, Tm_F9x, Tm_F9y, Tm_F9z, Tm_F10x, Tm_F10y, Tm_F10z
        };
    }

    // ----------------------------------------------------------
    // Device seg-side kernel formula (mirrors GPUMoveThing.segMotorForceKernel
    // for a single bound motor).
    // Returns: F_seg (x,y,z), T_seg_F8 (x,y,z), T_seg_F9 (x,y,z), T_seg_F10 (x,y,z).
    // (12 values).
    // ----------------------------------------------------------
    static double[] deviceSeg(Pose seg, Pose motor, double posOnSeg, boolean cocked, boolean dump) {
        double halfMotor = 0.5 * motorLen;
        double halfSeg   = 0.5 * seg.length;
        double mpx = motor.cx + halfMotor*motor.ux;
        double mpy = motor.cy + halfMotor*motor.uy;
        double mpz = motor.cz + halfMotor*motor.uz;
        double posOff = posOnSeg - halfSeg;
        double apx = seg.cx + posOff*seg.ux;
        double apy = seg.cy + posOff*seg.uy;
        double apz = seg.cz + posOff*seg.uz;
        double dxv = mpx - apx, dyv = mpy - apy, dzv = mpz - apz;
        double dist = Math.sqrt(dxv*dxv + dyv*dyv + dzv*dzv);
        double forceMag = dist * myoSpring;
        double invd = (dist > 0) ? 1.0/dist : 0;
        double fux = -dxv*invd, fuy = -dyv*invd, fuz = -dzv*invd;
        double Fx = forceMag*fux, Fy = forceMag*fuy, Fz = forceMag*fuz;
        // Seg side: -F.
        double Fsx = -Fx, Fsy = -Fy, Fsz = -Fz;
        double Rsx = posOff*seg.ux*1.0e-6;
        double Rsy = posOff*seg.uy*1.0e-6;
        double Rsz = posOff*seg.uz*1.0e-6;
        double Ts_F8x = Rsy*Fsz - Rsz*Fsy;
        double Ts_F8y = Rsz*Fsx - Rsx*Fsz;
        double Ts_F8z = Rsx*Fsy - Rsy*Fsx;

        double Ts_F9x = 0, Ts_F9y = 0, Ts_F9z = 0;
        {
            double tvx = seg.uy*motor.uz - seg.uz*motor.uy;
            double tvy = seg.uz*motor.ux - seg.ux*motor.uz;
            double tvz = seg.ux*motor.uy - seg.uy*motor.ux;
            double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
            if (tvMag2 > 0) {
                double invMag = 1.0/Math.sqrt(tvMag2);
                tvx *= invMag; tvy *= invMag; tvz *= invMag;
                double dotV = seg.ux*motor.ux + seg.uy*motor.uy + seg.uz*motor.uz;
                if (dotV > 1) dotV = 1; if (dotV < -1) dotV = -1;
                double angTween = accurateAcos(dotV)*180.0/Math.PI;
                double angRelaxed = cocked ? cockedAng : uncockedAng;
                double angD = angTween - angRelaxed;
                double invBRG = 1.0/motor.bRotGam_y + 1.0/seg.bRotGam_y;
                double torsionMag = j1FracMoveTorq*(Math.PI/180.0)*angD/(invBRG*dt);
                Ts_F9x = tvx*torsionMag; Ts_F9y = tvy*torsionMag; Ts_F9z = tvz*torsionMag;
            }
        }
        double Ts_F10x = 0, Ts_F10y = 0, Ts_F10z = 0;
        {
            double tvx = seg.yy_*motor.yz_ - seg.yz_*motor.yy_;
            double tvy = seg.yz_*motor.yx_ - seg.yx_*motor.yz_;
            double tvz = seg.yx_*motor.yy_ - seg.yy_*motor.yx_;
            double tvMag2 = tvx*tvx + tvy*tvy + tvz*tvz;
            if (tvMag2 > 0) {
                double invMag = 1.0/Math.sqrt(tvMag2);
                tvx *= invMag; tvy *= invMag; tvz *= invMag;
                double dotV = seg.yx_*motor.yx_ + seg.yy_*motor.yy_ + seg.yz_*motor.yz_;
                if (dotV > 1) dotV = 1; if (dotV < -1) dotV = -1;
                double angTween = accurateAcos(dotV)*180.0/Math.PI;
                double angD = angTween;
                double invBRG = 1.0/motor.bRotGam_x + 1.0/seg.bRotGam_x;
                double torsionMag = j1FracMoveTorq*(Math.PI/180.0)*angD/(invBRG*dt);
                Ts_F10x = tvx*torsionMag; Ts_F10y = tvy*torsionMag; Ts_F10z = tvz*torsionMag;
            }
        }
        if (dump) {
            System.out.printf("    [DEV.seg] F_seg     = (%.6e, %.6e, %.6e)%n", Fsx, Fsy, Fsz);
            System.out.printf("    [DEV.seg] T_seg_F8  = (%.6e, %.6e, %.6e)%n", Ts_F8x, Ts_F8y, Ts_F8z);
            System.out.printf("    [DEV.seg] T_seg_F9  = (%.6e, %.6e, %.6e)%n", Ts_F9x, Ts_F9y, Ts_F9z);
            System.out.printf("    [DEV.seg] T_seg_F10 = (%.6e, %.6e, %.6e)%n", Ts_F10x, Ts_F10y, Ts_F10z);
        }
        return new double[]{
            Fsx, Fsy, Fsz, Ts_F8x, Ts_F8y, Ts_F8z, Ts_F9x, Ts_F9y, Ts_F9z, Ts_F10x, Ts_F10y, Ts_F10z
        };
    }

    // ---- compare helpers ----
    static double maxAbs(double... v) {
        double m = 0;
        for (double x : v) { double ax = Math.abs(x); if (ax > m) m = ax; }
        return m;
    }
    static double maxAbsDiff(double[] a, int aOff, double[] b, int bOff, int n) {
        double m = 0;
        for (int i = 0; i < n; i++) {
            double d = Math.abs(a[aOff + i] - b[bOff + i]);
            if (d > m) m = d;
        }
        return m;
    }
    static double relErr(double diff, double scale) {
        if (scale < 1e-300) return diff;
        return diff / scale;
    }

    // ---- case runner ----
    // tolerance: absolute equality up to RELATIVE_TOL of the magnitude scale.
    // accurateAcos vs fastAcos can differ by ~5e-5 ABS in radians; angD then
    // differs by ~6e-3 deg; torsionMag scales by ~1e7 at gliding params, so
    // ABS divergence of ~5e1 N·µm is expected on alignment torques. We use a
    // RELATIVE tolerance of 1e-3 to absorb this acos divergence (this is the
    // same precision regime as the chain F4 port).
    static final double RELATIVE_TOL = 1.0e-3;

    static int runCase(String name, Pose seg, Pose motor, double posOnSeg, boolean cocked, boolean dumpVerbose) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + name);
        System.out.println("================================================================");
        if (dumpVerbose) {
            System.out.printf("  seg:   c=(%.6e,%.6e,%.6e) u=(%.6e,%.6e,%.6e) y=(%.6e,%.6e,%.6e) L=%.6e%n",
                seg.cx, seg.cy, seg.cz, seg.ux, seg.uy, seg.uz, seg.yx_, seg.yy_, seg.yz_, seg.length);
            System.out.printf("  motor: c=(%.6e,%.6e,%.6e) u=(%.6e,%.6e,%.6e) y=(%.6e,%.6e,%.6e)%n",
                motor.cx, motor.cy, motor.cz, motor.ux, motor.uy, motor.uz, motor.yx_, motor.yy_, motor.yz_);
            System.out.printf("  posOnSeg = %.6e µm   cocked=%b%n", posOnSeg, cocked);
            System.out.printf("  seg drag: bTransGam.x=%.6e bTransGam.y=%.6e bRotGam.x=%.6e bRotGam.y=%.6e%n",
                seg.bTransGam_x, seg.bTransGam_y, seg.bRotGam_x, seg.bRotGam_y);
            System.out.printf("  motor drag: bTransGam.x=%.6e bRotGam.y=%.6e%n",
                motor.bTransGam_x, motor.bRotGam_y);
            System.out.println();
        }
        double[] cpu  = cpuFormula(seg, motor, posOnSeg, cocked, dumpVerbose);
        double[] devM = deviceMotor(seg, motor, posOnSeg, cocked, dumpVerbose);
        double[] devS = deviceSeg(seg, motor, posOnSeg, cocked, dumpVerbose);

        // --- Check A: device motor-side matches CPU motor-side ---
        // cpu indices: forceMag=0, forceDotFil=1, F=2..4, T_F8=5..7, T_F9=8..10, T_F10=11..13
        // devM indices: same layout 0..13
        double scaleF      = maxAbs(cpu[2], cpu[3], cpu[4]);
        double scaleT_F8   = maxAbs(cpu[5], cpu[6], cpu[7]);
        double scaleT_F9   = maxAbs(cpu[8], cpu[9], cpu[10]);
        double scaleT_F10  = maxAbs(cpu[11], cpu[12], cpu[13]);
        double scaleFmag   = Math.abs(cpu[0]);
        double scaleFdot   = Math.abs(cpu[1]);

        double diffFmag    = Math.abs(cpu[0] - devM[0]);
        double diffFdot    = Math.abs(cpu[1] - devM[1]);
        double diffMotorF  = maxAbsDiff(cpu, 2, devM, 2, 3);
        double diffMotorT8 = maxAbsDiff(cpu, 5, devM, 5, 3);
        double diffMotorT9 = maxAbsDiff(cpu, 8, devM, 8, 3);
        double diffMotorT10= maxAbsDiff(cpu, 11, devM, 11, 3);

        // --- Check B: forceDotFil sign matches CPU ---
        boolean signOK = (Math.signum(cpu[1]) == Math.signum(devM[1])) || (Math.abs(cpu[1]) < 1e-300);

        // --- Check C: device seg-side matches CPU seg-side ---
        // cpu indices: F_seg=14..16, T_F8=17..19, T_F9=20..22, T_F10=23..25
        // devS indices: F_seg=0..2, T_F8=3..5, T_F9=6..8, T_F10=9..11
        double diffSegF   = maxAbsDiff(cpu, 14, devS, 0, 3);
        double diffSegT8  = maxAbsDiff(cpu, 17, devS, 3, 3);
        double diffSegT9  = maxAbsDiff(cpu, 20, devS, 6, 3);
        double diffSegT10 = maxAbsDiff(cpu, 23, devS, 9, 3);
        double scaleSegF  = maxAbs(cpu[14], cpu[15], cpu[16]);
        double scaleSegT8 = maxAbs(cpu[17], cpu[18], cpu[19]);
        double scaleSegT9 = maxAbs(cpu[20], cpu[21], cpu[22]);
        double scaleSegT10= maxAbs(cpu[23], cpu[24], cpu[25]);

        // --- Check D: motor-side +F + seg-side -F sums to ~0 (Newton-3) ---
        double pairSumFx = devM[2] + devS[0];
        double pairSumFy = devM[3] + devS[1];
        double pairSumFz = devM[4] + devS[2];
        double pairSumF  = maxAbs(pairSumFx, pairSumFy, pairSumFz);

        // F9 alignment pair sums to 0 (motor side -T, seg side +T, same |T|).
        double pairF9x = devM[8]  + devS[6];
        double pairF9y = devM[9]  + devS[7];
        double pairF9z = devM[10] + devS[8];
        double pairF9  = maxAbs(pairF9x, pairF9y, pairF9z);

        // F10 alignment pair sums to 0 (same shape).
        double pairF10x = devM[11] + devS[9];
        double pairF10y = devM[12] + devS[10];
        double pairF10z = devM[13] + devS[11];
        double pairF10  = maxAbs(pairF10x, pairF10y, pairF10z);

        // Reporting
        System.out.printf("  [A] CPU vs DEV motor side (|Δ| / scale):%n");
        System.out.printf("      forceMag    Δ=%.3e   |CPU|=%.3e   rel=%.3e%n", diffFmag,    scaleFmag,  relErr(diffFmag, scaleFmag));
        System.out.printf("      forceDotFil Δ=%.3e   |CPU|=%.3e   rel=%.3e   signMatch=%s%n", diffFdot, scaleFdot, relErr(diffFdot, scaleFdot), signOK ? "YES" : "NO");
        System.out.printf("      F_motor     Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffMotorF, scaleF, relErr(diffMotorF, scaleF));
        System.out.printf("      T_motor_F8  Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffMotorT8, scaleT_F8, relErr(diffMotorT8, scaleT_F8));
        System.out.printf("      T_motor_F9  Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffMotorT9, scaleT_F9, relErr(diffMotorT9, scaleT_F9));
        System.out.printf("      T_motor_F10 Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffMotorT10, scaleT_F10, relErr(diffMotorT10, scaleT_F10));
        System.out.printf("  [C] CPU vs DEV seg side  (|Δ| / scale):%n");
        System.out.printf("      F_seg       Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffSegF, scaleSegF, relErr(diffSegF, scaleSegF));
        System.out.printf("      T_seg_F8    Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffSegT8, scaleSegT8, relErr(diffSegT8, scaleSegT8));
        System.out.printf("      T_seg_F9    Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffSegT9, scaleSegT9, relErr(diffSegT9, scaleSegT9));
        System.out.printf("      T_seg_F10   Δmax=%.3e |CPU|max=%.3e rel=%.3e%n", diffSegT10, scaleSegT10, relErr(diffSegT10, scaleSegT10));
        System.out.printf("  [D] Newton-3 pair sums (|Δ| / scale):%n");
        System.out.printf("      F_motor + F_seg          max=%.3e   |F|=%.3e   rel=%.3e%n", pairSumF, scaleF, relErr(pairSumF, scaleF));
        System.out.printf("      T_motor_F9 + T_seg_F9    max=%.3e   |T9|=%.3e  rel=%.3e%n", pairF9,  scaleT_F9, relErr(pairF9, scaleT_F9));
        System.out.printf("      T_motor_F10 + T_seg_F10  max=%.3e   |T10|=%.3e rel=%.3e%n", pairF10, scaleT_F10, relErr(pairF10, scaleT_F10));

        // Verdict
        boolean okFmag   = relErr(diffFmag,    scaleFmag)  < RELATIVE_TOL || diffFmag    < 1.0e-30;
        boolean okFdot   = relErr(diffFdot,    scaleFdot)  < RELATIVE_TOL || diffFdot    < 1.0e-30;
        boolean okMF     = relErr(diffMotorF,  scaleF)     < RELATIVE_TOL || diffMotorF  < 1.0e-30;
        boolean okMT8    = relErr(diffMotorT8, scaleT_F8)  < RELATIVE_TOL || diffMotorT8 < 1.0e-30;
        boolean okMT9    = relErr(diffMotorT9, scaleT_F9)  < RELATIVE_TOL || diffMotorT9 < 1.0e-30;
        boolean okMT10   = relErr(diffMotorT10,scaleT_F10) < RELATIVE_TOL || diffMotorT10< 1.0e-30;
        boolean okSF     = relErr(diffSegF,    scaleSegF)  < RELATIVE_TOL || diffSegF    < 1.0e-30;
        boolean okST8    = relErr(diffSegT8,   scaleSegT8) < RELATIVE_TOL || diffSegT8   < 1.0e-30;
        boolean okST9    = relErr(diffSegT9,   scaleSegT9) < RELATIVE_TOL || diffSegT9   < 1.0e-30;
        boolean okST10   = relErr(diffSegT10,  scaleSegT10)< RELATIVE_TOL || diffSegT10  < 1.0e-30;
        // Pair-sum: should be EXACTLY zero in double precision (same arithmetic
        // on both sides). Allow a tiny ULP buffer.
        boolean okPairF  = pairSumF < 1.0e-22;
        boolean okPairF9 = pairF9   < 1.0e-22;
        boolean okPairF10= pairF10  < 1.0e-22;

        boolean allOK = signOK && okFmag && okFdot && okMF && okMT8 && okMT9 && okMT10
                      && okSF && okST8 && okST9 && okST10 && okPairF && okPairF9 && okPairF10;
        System.out.println("  VERDICT: " + (allOK ? "PASS" : "FAIL"));
        if (!allOK) {
            System.out.println("    failures: "
                + (signOK    ? "" : "signFdot ")
                + (okFmag    ? "" : "forceMag ")
                + (okFdot    ? "" : "|forceDotFil| ")
                + (okMF      ? "" : "F_motor ")
                + (okMT8     ? "" : "T_motor_F8 ")
                + (okMT9     ? "" : "T_motor_F9 ")
                + (okMT10    ? "" : "T_motor_F10 ")
                + (okSF      ? "" : "F_seg ")
                + (okST8     ? "" : "T_seg_F8 ")
                + (okST9     ? "" : "T_seg_F9 ")
                + (okST10    ? "" : "T_seg_F10 ")
                + (okPairF   ? "" : "PAIR_F ")
                + (okPairF9  ? "" : "PAIR_F9 ")
                + (okPairF10 ? "" : "PAIR_F10"));
        }
        return allOK ? 0 : 1;
    }

    public static void main(String[] args) {
        System.out.println("HeldBoundMotorDiag — Phase 2 F8/F9/F10 cheap probe.");
        System.out.printf("Constants: aeta=%g  dt=%g  motorLen=%g  segLen=%g µm  myoSpring=%g N/µm%n",
                          aeta, dt, motorLen, segLen, myoSpring);
        System.out.printf("           j1FracMoveTorq=%g  uncockedAng=%g°  cockedAng=%g°  RELATIVE_TOL=%g%n",
                          j1FracMoveTorq, uncockedAng, cockedAng, RELATIVE_TOL);

        int failures = 0;

        // Case 1 — non-degenerate geometry: seg at origin pointing +x, motor
        //          near a binding site that lies at posOnSeg = segLen/3.
        //          Tilt the motor a bit so motorPt ≠ attachPt → nonzero F.
        //          Cocked.
        {
            Pose seg = makeFilSegPose(segLen);
            Pose motor = makeMotorPose();
            // Place seg.end1 at origin, seg goes +x.
            // Actually our makeFilSegPose puts seg.coord at origin and uVec=+x.
            //   seg.end1 = (-0.5*segLen, 0, 0)
            //   seg.end2 = (+0.5*segLen, 0, 0)
            // Set motor at offset = 1/3 along seg, with a small perpendicular
            // displacement so dist > 0 and direction is non-degenerate.
            double posOnSeg = segLen / 3.0;          // ≈ 0.0297 µm
            double attachX = -0.5*segLen + posOnSeg; // µm
            // Motor center placed such that motor.end2 sits 5 nm above attachPt,
            // shifted slightly in x and z so all dx components are nonzero.
            motor.ux = 0.1;
            motor.uy = 0.9;
            motor.uz = 0.42426406871; // normalized below
            double um = Math.sqrt(motor.ux*motor.ux + motor.uy*motor.uy + motor.uz*motor.uz);
            motor.ux /= um; motor.uy /= um; motor.uz /= um;
            // pick coord so that motor.end2 is at (attachX + 0.001, 0.005, 0.002)
            motor.cx = attachX + 0.001 - 0.5*motorLen*motor.ux;
            motor.cy = 0.005           - 0.5*motorLen*motor.uy;
            motor.cz = 0.002           - 0.5*motorLen*motor.uz;
            // Motor.yVec — orthonormal pick (a different orientation from seg's
            // (0,1,0) to make F10 angTween non-zero).
            // Take z-cross-uVec as a basis, normalize.
            double zCrossU_x = -motor.uy;
            double zCrossU_y =  motor.ux;
            double zCrossU_z = 0;
            double zcmag = Math.sqrt(zCrossU_x*zCrossU_x + zCrossU_y*zCrossU_y + zCrossU_z*zCrossU_z);
            if (zcmag > 0) { zCrossU_x /= zcmag; zCrossU_y /= zcmag; zCrossU_z /= zcmag; }
            motor.yx_ = zCrossU_x;
            motor.yy_ = zCrossU_y;
            motor.yz_ = zCrossU_z;
            // Re-orthonormalize so that y ⊥ u.
            double dotuy = motor.ux*motor.yx_ + motor.uy*motor.yy_ + motor.uz*motor.yz_;
            motor.yx_ -= dotuy*motor.ux;
            motor.yy_ -= dotuy*motor.uy;
            motor.yz_ -= dotuy*motor.uz;
            double ymag = Math.sqrt(motor.yx_*motor.yx_ + motor.yy_*motor.yy_ + motor.yz_*motor.yz_);
            if (ymag > 0) { motor.yx_ /= ymag; motor.yy_ /= ymag; motor.yz_ /= ymag; }
            failures += runCase("Case 1 — tilted bound motor at posOnSeg=segLen/3, COCKED", seg, motor, posOnSeg, true, true);
        }

        // Case 2 — UN-cocked variant of the same geometry — exercises F9's
        //          angRelaxed=90° branch.
        {
            Pose seg = makeFilSegPose(segLen);
            Pose motor = makeMotorPose();
            double posOnSeg = segLen / 3.0;
            double attachX = -0.5*segLen + posOnSeg;
            motor.ux = 0.1; motor.uy = 0.9; motor.uz = 0.42426406871;
            double um = Math.sqrt(motor.ux*motor.ux + motor.uy*motor.uy + motor.uz*motor.uz);
            motor.ux /= um; motor.uy /= um; motor.uz /= um;
            motor.cx = attachX + 0.001 - 0.5*motorLen*motor.ux;
            motor.cy = 0.005           - 0.5*motorLen*motor.uy;
            motor.cz = 0.002           - 0.5*motorLen*motor.uz;
            double zCrossU_x = -motor.uy, zCrossU_y =  motor.ux, zCrossU_z = 0;
            double zcmag = Math.sqrt(zCrossU_x*zCrossU_x + zCrossU_y*zCrossU_y + zCrossU_z*zCrossU_z);
            if (zcmag > 0) { zCrossU_x /= zcmag; zCrossU_y /= zcmag; zCrossU_z /= zcmag; }
            motor.yx_ = zCrossU_x; motor.yy_ = zCrossU_y; motor.yz_ = zCrossU_z;
            double dotuy = motor.ux*motor.yx_ + motor.uy*motor.yy_ + motor.uz*motor.yz_;
            motor.yx_ -= dotuy*motor.ux; motor.yy_ -= dotuy*motor.uy; motor.yz_ -= dotuy*motor.uz;
            double ymag = Math.sqrt(motor.yx_*motor.yx_ + motor.yy_*motor.yy_ + motor.yz_*motor.yz_);
            if (ymag > 0) { motor.yx_ /= ymag; motor.yy_ /= ymag; motor.yz_ /= ymag; }
            failures += runCase("Case 2 — same geometry, UN-COCKED", seg, motor, posOnSeg, false, false);
        }

        // Case 3 — large lateral strain (motor pulled away from binding) to
        //          exercise a higher-force regime + flipped forceDotFil sign.
        //          Motor end2 placed 30 nm AHEAD of attachPt along seg.uVec
        //          → forceDotFil > 0 (motor "wants" to slide toward seg.end2).
        {
            Pose seg = makeFilSegPose(segLen);
            Pose motor = makeMotorPose();
            double posOnSeg = segLen / 2.0;
            double attachX = -0.5*segLen + posOnSeg;
            motor.ux = 0; motor.uy = 0; motor.uz = 1;   // motor points +z
            // Place motor center so end2 is at (attachX + 0.030, 0, 0.020)
            motor.cx = attachX + 0.030 - 0.5*motorLen*motor.ux;   // = attachX + 0.030
            motor.cy = 0.0;
            motor.cz = 0.020          - 0.5*motorLen*motor.uz;    // = 0.020 - 0.01
            motor.yx_ = 1; motor.yy_ = 0; motor.yz_ = 0;
            failures += runCase("Case 3 — 30 nm lateral strain along seg.uVec (forceDotFil > 0)", seg, motor, posOnSeg, true, false);
        }

        // Case 4 — strain ANTI-parallel to seg.uVec → forceDotFil < 0.
        {
            Pose seg = makeFilSegPose(segLen);
            Pose motor = makeMotorPose();
            double posOnSeg = segLen / 2.0;
            double attachX = -0.5*segLen + posOnSeg;
            motor.ux = 0; motor.uy = 0; motor.uz = 1;
            // Place motor center so end2 is at (attachX - 0.030, 0, 0.020)
            motor.cx = attachX - 0.030 - 0.5*motorLen*motor.ux;
            motor.cy = 0.0;
            motor.cz = 0.020          - 0.5*motorLen*motor.uz;
            motor.yx_ = 1; motor.yy_ = 0; motor.yz_ = 0;
            failures += runCase("Case 4 — −30 nm strain along seg.uVec (forceDotFil < 0)", seg, motor, posOnSeg, true, false);
        }

        System.out.println();
        System.out.println("================================================================");
        if (failures == 0) {
            System.out.println("  CHEAP PROBE: PASS (all cases)");
            System.out.println("================================================================");
            System.exit(0);
        } else {
            System.out.println("  CHEAP PROBE: FAIL (" + failures + " case(s) failed)");
            System.out.println("================================================================");
            System.exit(1);
        }
    }
}
