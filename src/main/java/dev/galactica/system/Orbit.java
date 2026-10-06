package dev.galactica.system;

import dev.galactica.science.Units;

/**
 * Keplerian orbit around a parent of mass {@code parentMassKg}. Angles in radians, distances in
 * metres, time in seconds. The reference plane is the parent's equator/system ecliptic with +Y as
 * the orbit normal at zero inclination, matching Minecraft's up axis.
 */
public record Orbit(
        double semiMajorAxis,
        double eccentricity,
        double inclination,
        double longitudeOfAscendingNode,
        double argumentOfPeriapsis,
        double meanAnomalyAtEpoch,
        double parentMassKg) {

    public double mu() {
        return Units.G * parentMassKg;
    }

    public double periodSeconds() {
        return 2.0 * Math.PI * Math.sqrt(semiMajorAxis * semiMajorAxis * semiMajorAxis / mu());
    }

    public double meanMotion() {
        return Math.sqrt(mu() / (semiMajorAxis * semiMajorAxis * semiMajorAxis));
    }

    public double periapsis() {
        return semiMajorAxis * (1 - eccentricity);
    }

    public double apoapsis() {
        return semiMajorAxis * (1 + eccentricity);
    }

    /** Solves Kepler's equation M = E − e·sin E by Newton iteration (≤ 1e-12 rad). */
    public static double eccentricAnomaly(double meanAnomaly, double e) {
        double m = meanAnomaly % (2 * Math.PI);
        if (m < 0) m += 2 * Math.PI;
        double ea = e < 0.8 ? m : Math.PI;
        for (int i = 0; i < 30; i++) {
            double f = ea - e * Math.sin(ea) - m;
            double d = f / (1 - e * Math.cos(ea));
            ea -= d;
            if (Math.abs(d) < 1e-12) break;
        }
        return ea;
    }

    /** Position relative to the parent at time t (seconds since epoch), written to out[0..2] as x, y, z. */
    public void positionAt(double t, double[] out) {
        double ea = eccentricAnomaly(meanAnomalyAtEpoch + meanMotion() * t, eccentricity);
        double cosE = Math.cos(ea), sinE = Math.sin(ea);
        // Perifocal coordinates.
        double px = semiMajorAxis * (cosE - eccentricity);
        double pq = semiMajorAxis * Math.sqrt(1 - eccentricity * eccentricity) * sinE;
        rotate(px, pq, out);
    }

    /** Velocity relative to the parent at time t, m/s. */
    public void velocityAt(double t, double[] out) {
        double ea = eccentricAnomaly(meanAnomalyAtEpoch + meanMotion() * t, eccentricity);
        double cosE = Math.cos(ea), sinE = Math.sin(ea);
        double edot = meanMotion() / (1 - eccentricity * cosE);
        double vx = -semiMajorAxis * sinE * edot;
        double vq = semiMajorAxis * Math.sqrt(1 - eccentricity * eccentricity) * cosE * edot;
        rotate(vx, vq, out);
    }

    /** Perifocal (P, Q) → reference frame with Y up: standard 3-1-3 rotation, then (x, y, z)_astro → (x, z, -y)_mc. */
    private void rotate(double p, double q, double[] out) {
        double cO = Math.cos(longitudeOfAscendingNode), sO = Math.sin(longitudeOfAscendingNode);
        double cw = Math.cos(argumentOfPeriapsis), sw = Math.sin(argumentOfPeriapsis);
        double ci = Math.cos(inclination), si = Math.sin(inclination);
        double x = (cO * cw - sO * sw * ci) * p + (-cO * sw - sO * cw * ci) * q;
        double y = (sO * cw + cO * sw * ci) * p + (-sO * sw + cO * cw * ci) * q;
        double z = (sw * si) * p + (cw * si) * q;
        out[0] = x;
        out[1] = z;
        out[2] = -y;
    }

    /** Vis-viva speed at distance r. */
    public double speedAtDistance(double r) {
        return Math.sqrt(mu() * (2.0 / r - 1.0 / semiMajorAxis));
    }

    /** Hill sphere radius for a body of mass m on this orbit. */
    public double hillRadius(double bodyMassKg) {
        return semiMajorAxis * (1 - eccentricity) * Math.cbrt(bodyMassKg / (3 * parentMassKg));
    }

    /** Laplace sphere of influence — where patched-conic flight switches its reference body. */
    public double sphereOfInfluence(double bodyMassKg) {
        return semiMajorAxis * Math.pow(bodyMassKg / parentMassKg, 0.4);
    }
}
