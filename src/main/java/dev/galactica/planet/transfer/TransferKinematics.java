package dev.galactica.planet.transfer;

/**
 * Velocity and attitude across the space ↔ surface hand-off, so the switch keeps momentum and
 * heading instead of stopping dead.
 *
 * <p><b>Frames.</b>
 * <ul>
 *   <li><i>Galactic</i>: metres and m/s, z towards the north galactic pole, as
 *       {@link dev.galactica.nav.GalacticPos#metresFrom} returns them.</li>
 *   <li><i>Space MC</i>: block axes of galactica:space, 1 m per block (Units.METRES_PER_BLOCK),
 *       galactic (x, y, z) = MC (x, −z, y), the same proper rotation {@code Frames} uses.</li>
 *   <li><i>Surface MC</i>: block axes of a landing slot (or the Overworld for Earth). +x is east,
 *       +y up, +z south (Minecraft north is −Z), horizontally scaled by {@code SurfaceGrid.metresPerBlock()}.
 *       Vertical blocks use the same scale, so altitude and climb rate stay physical.</li>
 * </ul>
 *
 * <p><b>Reference frame of the hand-off.</b> The space dimension does not integrate orbital momentum:
 * a player at rest there sees bodies sweep past. The hand-off therefore treats space-side velocity as
 * relative to the body centre in a non-rotating frame (the "Krakensbane" choice of Kerbal Space Program,
 * which also re-bases velocity on the nearest body), and the surface as rotating with the body at
 * ω = 2π/P_sidereal. Ground-relative velocity is v_ground = v_inertial − ω × r
 * (Goldstein, <i>Classical Mechanics</i>, 3rd ed., §4.9, velocity in a rotating frame), and the exit
 * adds ω × r back, so entry followed by exit is an exact round trip.
 *
 * <p>Ticks are 1/20 s; a velocity in blocks/tick is metres per second ÷ (metres per block × 20).
 */
public final class TransferKinematics {
    private TransferKinematics() {}

    public static final double TICKS_PER_SECOND = 20.0;

    /**
     * Local east/north/up unit vectors (galactic axes) at a surface point.
     *
     * @param east  local east, tangent to the parallel
     * @param north local north, tangent to the meridian
     * @param up    outward radial
     */
    public record LocalFrame(double[] east, double[] north, double[] up) {

        /**
         * Frame at radial {@code upDir} on a body with spin axis {@code pole}. At a pole, where east is
         * undefined, the body's prime-meridian reference is used so the frame stays orthonormal.
         */
        public static LocalFrame at(double[] pole, double[] upDir) {
            double[] u = normalize(upDir.clone());
            double[] e = cross(pole, u);
            if (dot(e, e) < 1e-18) {
                double[] ref = Math.abs(u[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0};
                e = cross(ref, u);
            }
            normalize(e);
            double[] n = cross(u, e);
            return new LocalFrame(e, n, u);
        }
    }

    // ---------------------------------------------------------------- rotation of the body

    /** Spin rate of a body with sidereal period {@code rotationHours} (negative = retrograde), rad/s. */
    public static double spinRate(double rotationHours) {
        return rotationHours == 0 ? 0 : 2 * Math.PI / (rotationHours * 3600.0);
    }

    /** ω × r, m/s in galactic axes: ground speed of the surface at {@code r} (metres from the centre). */
    public static double[] spinVelocity(double[] pole, double omega, double[] r) {
        double[] c = cross(pole, r);
        return new double[] {c[0] * omega, c[1] * omega, c[2] * omega};
    }

    // ---------------------------------------------------------------- space side

    /** Space-dimension velocity, blocks/tick, to galactic m/s. */
    public static double[] spaceMcToGalactic(double[] vPerTick) {
        return new double[] {vPerTick[0] * TICKS_PER_SECOND, -vPerTick[2] * TICKS_PER_SECOND, vPerTick[1] * TICKS_PER_SECOND};
    }

    /** Galactic m/s to space-dimension blocks/tick. Inverse of {@link #spaceMcToGalactic}. */
    public static double[] galacticToSpaceMc(double[] vGal) {
        return new double[] {vGal[0] / TICKS_PER_SECOND, vGal[2] / TICKS_PER_SECOND, -vGal[1] / TICKS_PER_SECOND};
    }

    // ---------------------------------------------------------------- surface side

    /** Galactic m/s to surface blocks/tick: (east, up, −north) / (metres per block × 20). */
    public static double[] toSurface(double[] vGal, LocalFrame f, double metresPerBlock) {
        double k = 1.0 / (metresPerBlock * TICKS_PER_SECOND);
        return new double[] {dot(vGal, f.east) * k, dot(vGal, f.up) * k, -dot(vGal, f.north) * k};
    }

    /** Surface blocks/tick to galactic m/s. Inverse of {@link #toSurface}. */
    public static double[] fromSurface(double[] vSurf, LocalFrame f, double metresPerBlock) {
        double k = metresPerBlock * TICKS_PER_SECOND;
        double e = vSurf[0] * k, u = vSurf[1] * k, n = -vSurf[2] * k;
        return new double[] {
                f.east[0] * e + f.up[0] * u + f.north[0] * n,
                f.east[1] * e + f.up[1] * u + f.north[1] * n,
                f.east[2] * e + f.up[2] * u + f.north[2] * n};
    }

    /** Ground-relative velocity at entry: inertial galactic m/s minus the surface's own ω × r. */
    public static double[] groundRelative(double[] vGal, double[] spin) {
        return new double[] {vGal[0] - spin[0], vGal[1] - spin[1], vGal[2] - spin[2]};
    }

    /** Inertial galactic velocity at exit: ground-relative m/s plus ω × r. */
    public static double[] inertial(double[] vGround, double[] spin) {
        return new double[] {vGround[0] + spin[0], vGround[1] + spin[1], vGround[2] + spin[2]};
    }

    // ---------------------------------------------------------------- attitude

    /**
     * Quaternion (x, y, z, w) of the rotation taking space-MC vectors to surface-MC vectors at frame
     * {@code f}: its matrix rows are the surface axes (east, up, south) written in space-MC axes.
     */
    public static double[] spaceToSurfaceRotation(LocalFrame f) {
        double[] ex = galToMc(f.east), ey = galToMc(f.up), ez = galToMc(neg(f.north));
        return fromMatrix(ex[0], ex[1], ex[2], ey[0], ey[1], ey[2], ez[0], ez[1], ez[2]);
    }

    /** A ship attitude (x, y, z, w; body → space MC) re-expressed as body → surface MC. */
    public static double[] attitudeToSurface(double[] qSpace, LocalFrame f) {
        return normalizeQ(mul(spaceToSurfaceRotation(f), qSpace));
    }

    /** A ship attitude body → surface MC re-expressed as body → space MC. Inverse of {@link #attitudeToSurface}. */
    public static double[] attitudeToSpace(double[] qSurface, LocalFrame f) {
        return normalizeQ(mul(conjugate(spaceToSurfaceRotation(f)), qSurface));
    }

    /** Rotates an MC direction from space axes to surface axes. */
    public static double[] directionToSurface(double[] dirSpaceMc, LocalFrame f) {
        double[] g = mcToGal(dirSpaceMc);
        return new double[] {dot(g, f.east), dot(g, f.up), -dot(g, f.north)};
    }

    /** Rotates an MC direction from surface axes to space axes. */
    public static double[] directionToSpace(double[] dirSurfaceMc, LocalFrame f) {
        double e = dirSurfaceMc[0], u = dirSurfaceMc[1], n = -dirSurfaceMc[2];
        double[] g = {
                f.east[0] * e + f.up[0] * u + f.north[0] * n,
                f.east[1] * e + f.up[1] * u + f.north[1] * n,
                f.east[2] * e + f.up[2] * u + f.north[2] * n};
        return galToMc(g);
    }

    /** Minecraft look vector of yaw/pitch in degrees (Entity.calculateViewVector convention). */
    public static double[] lookVector(double yawDeg, double pitchDeg) {
        double p = Math.toRadians(pitchDeg), y = Math.toRadians(-yawDeg);
        double cp = Math.cos(p);
        return new double[] {Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp};
    }

    /** {yaw, pitch} in degrees of a Minecraft look vector. Inverse of {@link #lookVector}. */
    public static double[] lookAngles(double[] dir) {
        double[] d = normalize(dir.clone());
        double yaw = Math.toDegrees(Math.atan2(-d[0], d[2]));
        double pitch = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d[1]))));
        return new double[] {yaw, pitch};
    }

    // ---------------------------------------------------------------- small linear algebra

    static double[] mcToGal(double[] m) {
        return new double[] {m[0], -m[2], m[1]};
    }

    static double[] galToMc(double[] g) {
        return new double[] {g[0], g[2], -g[1]};
    }

    static double[] neg(double[] a) {
        return new double[] {-a[0], -a[1], -a[2]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] normalize(double[] a) {
        double r = Math.sqrt(dot(a, a));
        if (r > 0) {
            a[0] /= r;
            a[1] /= r;
            a[2] /= r;
        }
        return a;
    }

    /** Hamilton product a ⊗ b of (x, y, z, w) quaternions: apply b, then a. */
    static double[] mul(double[] a, double[] b) {
        return new double[] {
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    static double[] conjugate(double[] q) {
        return new double[] {-q[0], -q[1], -q[2], q[3]};
    }

    static double[] normalizeQ(double[] q) {
        double r = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        return r > 0 ? new double[] {q[0] / r, q[1] / r, q[2] / r, q[3] / r} : new double[] {0, 0, 0, 1};
    }

    /** Rotates v by unit quaternion q (x, y, z, w). */
    public static double[] rotate(double[] q, double[] v) {
        double[] p = mul(mul(q, new double[] {v[0], v[1], v[2], 0}), conjugate(q));
        return new double[] {p[0], p[1], p[2]};
    }

    /**
     * Unit quaternion of an orthonormal rotation matrix given by rows, Shepperd's method
     * (S. W. Shepperd, "Quaternion from rotation matrix", J. Guidance &amp; Control 1(3), 1978).
     */
    static double[] fromMatrix(double m00, double m01, double m02, double m10, double m11, double m12,
                               double m20, double m21, double m22) {
        double tr = m00 + m11 + m22, x, y, z, w;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1.0) * 2;
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        return normalizeQ(new double[] {x, y, z, w});
    }
}
