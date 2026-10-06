package dev.galactica.planet;

import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemFrame;

/**
 * Body-fixed geography in the galactic frame. The spin axis is the system's plane normal tipped by
 * the axial tilt towards the system's reference direction, the same axis the planet imager spins
 * its textures about, so what you see from orbit is what you land on. Longitude 0 is a fixed
 * inertial reference rotated by the body's spin, 2π·t/P; a negative period (retrograde) turns it
 * backwards.
 *
 * <p>All vectors are unit length in galactic axes (z towards the north galactic pole), as
 * {@link dev.galactica.nav.GalacticPos#metresFrom} returns them.
 */
public final class BodyFrame {
    private BodyFrame() {}

    /** North pole of the body: system normal tipped by the axial tilt. Moons share their system's plane. */
    public static double[] pole(Star star, Body b) {
        double tilt = Math.toRadians(b.axialTiltDeg());
        double[] g = new double[3];
        SystemFrame.toGalactic(star, Math.sin(tilt), Math.cos(tilt), 0, g);
        return normalize(g);
    }

    /** Inertial prime-meridian direction at t = 0, perpendicular to the pole. */
    public static double[] meridianZero(double[] pole) {
        double[] ref = Math.abs(pole[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0};
        return normalize(cross(ref, pole));
    }

    /** Spin angle at time t (seconds since J2000), radians in [0, 2π). */
    public static double spinAngle(Body b, double t) {
        double period = b.rotationHours() * 3600.0;
        if (period == 0) return 0;
        double a = (2 * Math.PI * (t / period)) % (2 * Math.PI);
        return a < 0 ? a + 2 * Math.PI : a;
    }

    /** {lat, lon} in radians of the surface point under unit direction {@code dir} (centre → point). */
    public static double[] latLon(Star star, Body b, double t, double[] dir) {
        double[] n = pole(star, b);
        double[] e1 = meridianZero(n), e2 = cross(n, e1);
        double[] d = normalize(dir.clone());
        double lat = Math.asin(Math.max(-1, Math.min(1, dot(d, n))));
        double lon = Math.atan2(dot(d, e2), dot(d, e1)) - spinAngle(b, t);
        return new double[] {lat, wrapPi(lon)};
    }

    /** Unit direction (centre → surface) of latitude/longitude at time t. Inverse of {@link #latLon}. */
    public static double[] direction(Star star, Body b, double t, double lat, double lon) {
        double[] n = pole(star, b);
        double[] e1 = meridianZero(n), e2 = cross(n, e1);
        double a = lon + spinAngle(b, t);
        double c = Math.cos(lat), s = Math.sin(lat), ca = Math.cos(a) * c, sa = Math.sin(a) * c;
        return new double[] {
                e1[0] * ca + e2[0] * sa + n[0] * s,
                e1[1] * ca + e2[1] * sa + n[1] * s,
                e1[2] * ca + e2[2] * sa + n[2] * s};
    }

    /**
     * Galactic-frame offset in metres of a planet (moon == null) or one of its moons from the star at
     * time t, exactly as {@link dev.galactica.nav.Neighbourhood#locateBodies} places them.
     */
    public static double[] offsetFromStar(Star star, Body planet, Body moon, double t) {
        double[] buf = new double[3], g = new double[3], out = new double[3];
        planet.orbit().positionAt(t, buf);
        SystemFrame.toGalactic(star, buf[0], buf[1], buf[2], out);
        if (moon != null) {
            moon.orbit().positionAt(t, buf);
            SystemFrame.toGalactic(star, buf[0], buf[1], buf[2], g);
            out[0] += g[0];
            out[1] += g[1];
            out[2] += g[2];
        }
        return out;
    }

    static double wrapPi(double a) {
        double r = (a + Math.PI) % (2 * Math.PI);
        if (r < 0) r += 2 * Math.PI;
        return r - Math.PI;
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
}
