package dev.galactica.nav;

import dev.galactica.science.Units;
import dev.galactica.ship.ShipGravity;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemFrame;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Patched-conic reference frame of a craft in space (Krakensbane style). Pure Java, galactic frame, metres.
 *
 * <p><b>Why.</b> Space block coordinates are offsets from a per-player galactic origin ({@link NavState#origin()}).
 * When that origin stood still in the galaxy, a craft leaving Earth kept only its ground-relative block velocity
 * (a few hundred m/s) while Earth went on at 29.8 km/s round the Sun: the planet ran away at 1.5 km per tick and
 * nothing pulled the craft back.
 *
 * <p><b>Model.</b> Inside a body's Laplace sphere of influence ({@code Orbit.sphereOfInfluence}; nested spheres
 * resolve to the innermost, so the Moon's inside Earth's) that body is <i>dominant</i>, otherwise the system's star
 * is. The frame co-moves with the dominant body: each tick the body's own displacement is folded into the origin, so
 * a craft at rest relative to Earth stays there. On top of that the frame carries the craft's inertial velocity
 * relative to the dominant body ({@link NavState#frameVelocity()}), and it falls freely: the dominant body's
 * GM/r² acts on the frame, not on the block velocities, so everything in it (ship, crew, a suited player) falls
 * together, as in a real orbit. Integration is semi-implicit (symplectic) Euler at tick rate, kick then drift, whose
 * energy error stays bounded over any number of orbits instead of spiralling.
 *
 * <p><b>Hand-over.</b> Crossing a sphere boundary re-expresses the frame velocity relative to the new dominant body
 * (v' = v + v_old − v_new, both body velocities heliocentric), so the inertial velocity is continuous. The warp
 * drive is the exception at a boundary: a warp that carries the craft into another body's sphere drops it out at
 * rest relative to that body instead of with a planet's orbital speed of difference. Within one sphere the warp
 * bubble only freezes the frame (no gravity while it moves, the frame velocity kept as it was), so the drive is not a
 * free brake: an orbit's 7.7 km/s is still there when the drive stops. A teleport (commands, hyperjump) leaves the anchor unset, and the next tick adopts the
 * dominant body at rest.
 *
 * <p><b>Krakensbane.</b> A ship's own block velocity is capped well under a chunk per tick; whatever a burn adds
 * beyond {@code ShipFlightModel.KRAKENSBANE_SPEED} is moved into the frame velocity by the pilot's client and
 * reported with its velocity, so a ship can reach orbital speed while its blocks stay slow.
 */
public final class OrbitalFrame {
    private OrbitalFrame() {}

    /** No anchor yet: the next tick adopts the dominant body with the frame at rest relative to it. */
    public static final long ANCHOR_UNSET = Long.MIN_VALUE;
    /** The star (heliocentric frame), or no body at all in interstellar space. Stars do not move. */
    public static final long ANCHOR_STAR = Long.MIN_VALUE + 1;
    /** Half-width of the central difference for body velocities, seconds. */
    private static final double VELOCITY_STEP = 0.5;

    /**
     * The body whose frame a craft flies in.
     *
     * @param id     body id, or {@link #ANCHOR_STAR}
     * @param body   the planet or moon, null for the star
     * @param pos    its centre at the time asked
     * @param mu     GM, m³/s²
     * @param radius surface radius (photosphere for the star), metres
     * @param soi    sphere-of-influence radius, metres (infinite for the star)
     */
    public record Dominant(long id, @Nullable Body body, String name, GalacticPos pos, double mu, double radius, double soi) {
        public boolean isStar() {
            return body == null;
        }
    }

    /** Result of one frame tick. */
    public record Advance(double[] originDelta, double[] frameVelocity, long anchor, @Nullable Dominant dominant) {}

    /** The dominant body at {@code here}: the innermost sphere of influence containing it, else the star; null outside any system. */
    public static @Nullable Dominant dominant(Neighbourhood n, GalacticPos here, double t) {
        Star s = n.systemStar(here);
        if (s == null) return null;
        Dominant best = new Dominant(ANCHOR_STAR, null, s.name(), Neighbourhood.starPos(s), Units.G * s.massKg(), s.radiusMetres(),
                Double.POSITIVE_INFINITY);
        for (Neighbourhood.Located l : n.locateBodies(here, t)) {
            Body b = l.body();
            double soi = b.orbit().sphereOfInfluence(b.massKg());
            if (soi < best.soi() && l.pos().distanceMetres(here) < soi) {
                best = new Dominant(b.id(), b, b.name(), l.pos(), Units.G * b.massKg(), b.radiusMetres(), soi);
            }
        }
        return best;
    }

    /** Where anchor {@code id} of the system around {@code here} is at time t, or null when it is not in this system. */
    public static @Nullable GalacticPos anchorPos(Neighbourhood n, GalacticPos here, long id, double t) {
        Star s = n.systemStar(here);
        if (s == null) return null;
        GalacticPos sp = Neighbourhood.starPos(s);
        if (id == ANCHOR_STAR) return sp;
        double[] buf = new double[3], g = new double[3];
        for (Body planet : n.systemBodies()) {
            boolean isPlanet = planet.id() == id;
            Body moon = null;
            if (!isPlanet) {
                for (Body m : planet.moons()) {
                    if (m.id() == id) moon = m;
                }
                if (moon == null) continue;
            }
            planet.orbit().positionAt(t, buf);
            SystemFrame.toGalactic(s, buf[0], buf[1], buf[2], g);
            GalacticPos pp = sp.plusMetres(g[0], g[1], g[2]);
            if (isPlanet) return pp;
            // Moons share the system frame (Neighbourhood.locateBodies).
            moon.orbit().positionAt(t, buf);
            SystemFrame.toGalactic(s, buf[0], buf[1], buf[2], g);
            return pp.plusMetres(g[0], g[1], g[2]);
        }
        return null;
    }

    /** Velocity of anchor {@code id} at time t, galactic m/s (zero for the star or an unknown body). */
    public static double[] anchorVelocity(Neighbourhood n, GalacticPos here, long id, double t) {
        if (id == ANCHOR_STAR || id == ANCHOR_UNSET) return new double[3];
        GalacticPos a = anchorPos(n, here, id, t - VELOCITY_STEP), b = anchorPos(n, here, id, t + VELOCITY_STEP);
        if (a == null || b == null) return new double[3];
        double[] d = b.metresFrom(a);
        return new double[] {d[0] / (2 * VELOCITY_STEP), d[1] / (2 * VELOCITY_STEP), d[2] / (2 * VELOCITY_STEP)};
    }

    /** GM/r² toward the body's centre at offset {@code rel} (craft minus body), held at its surface value inside the body. */
    public static double[] gravity(double[] rel, double mu, double radius) {
        return ShipGravity.pull(List.of(new ShipGravity.Attractor(new double[] {-rel[0], -rel[1], -rel[2]}, mu,
                Double.POSITIVE_INFINITY, radius)));
    }

    /** Frame velocity re-expressed for a new dominant body: inertial velocity is continuous across the boundary. */
    public static double[] handOver(double[] vFrame, double[] vOldBody, double[] vNewBody) {
        return new double[] {vFrame[0] + vOldBody[0] - vNewBody[0], vFrame[1] + vOldBody[1] - vNewBody[1],
                vFrame[2] + vOldBody[2] - vNewBody[2]};
    }

    /**
     * One semi-implicit Euler step of a body-centred two-body orbit, in place: v += a(r)·dt, then r += v·dt. Used by
     * the tests to bound the integrator; the frame tick does the same with the origin as r.
     */
    public static void step(double[] r, double[] v, double mu, double radius, double dt) {
        double[] a = gravity(r, mu, radius);
        for (int i = 0; i < 3; i++) {
            v[i] += a[i] * dt;
            r[i] += v[i] * dt;
        }
    }

    /**
     * One tick of the frame of a craft at {@code here} (its galactic position at time t): picks the dominant body,
     * hands the velocity over if it changed (matching the new body when warping), applies its gravity (frozen while warping) and returns how far the origin moves:
     * the body's own displacement over the tick plus the frame velocity times dt.
     */
    public static Advance advance(Neighbourhood n, GalacticPos here, double[] vFrame, long anchor, double t, double dt, boolean warping) {
        Dominant d = dominant(n, here, t);
        double[] v = vFrame.clone();
        if (d == null) {
            // Interstellar space: an inertial frame (the galactic potential is not modelled).
            if (warping) v = new double[3];
            return new Advance(new double[] {v[0] * dt, v[1] * dt, v[2] * dt}, v, ANCHOR_STAR, null);
        }
        if (anchor == ANCHOR_UNSET || !finite(v)) {
            v = new double[3];
        } else if (anchor != d.id()) {
            // Warping into another body's sphere matches that body (supercruise drop-out); coasting keeps the
            // inertial velocity.
            boolean known = anchor == ANCHOR_STAR || anchorPos(n, here, anchor, t) != null;
            v = known && !warping ? handOver(v, anchorVelocity(n, here, anchor, t), anchorVelocity(n, here, d.id(), t)) : new double[3];
        }
        if (!warping) {
            double[] a = gravity(here.metresFrom(d.pos()), d.mu(), d.radius());
            for (int i = 0; i < 3; i++) v[i] += a[i] * dt;
        }
        double[] move = new double[3];
        if (!d.isStar()) {
            GalacticPos later = anchorPos(n, here, d.id(), t + dt);
            if (later != null) move = later.metresFrom(d.pos());
        }
        return new Advance(new double[] {move[0] + v[0] * dt, move[1] + v[1] * dt, move[2] + v[2] * dt}, v, d.id(), d);
    }

    /**
     * Periapsis and apoapsis distances from the body's centre and the specific orbital energy for a craft at
     * {@code r} (metres from the centre) moving at {@code v} (m/s relative to the body): {rp, ra, energy}. The
     * apoapsis is infinite on an escape trajectory.
     */
    public static double[] apsides(double[] r, double[] v, double mu) {
        double rm = len(r), v2 = dot(v, v);
        double energy = v2 / 2 - mu / rm;
        double[] h = cross(r, v);
        double h2 = dot(h, h);
        double[] vxh = cross(v, h);
        double[] e = {vxh[0] / mu - r[0] / rm, vxh[1] / mu - r[1] / rm, vxh[2] / mu - r[2] / rm};
        double ecc = len(e);
        double p = h2 / mu; // semi-latus rectum
        double rp = p / (1 + ecc);
        // From the energy rather than p/(1 − e): a radial fall (e → 1) still has a finite apoapsis, its current height.
        double ra = energy < 0 ? 2 * (-mu / (2 * energy)) - rp : Double.POSITIVE_INFINITY;
        return new double[] {rp, ra, energy};
    }

    /**
     * Velocity of a circular orbit through {@code r} (metres from the centre): √(μ/r) along {@code normal} × r̂, the
     * orbit plane being perpendicular to {@code normal} (any vector not parallel to r).
     */
    public static double[] circularVelocity(double[] r, double mu, double[] normal) {
        double[] dir = cross(normal, r);
        double l = len(dir);
        double s = Math.sqrt(mu / len(r));
        return new double[] {dir[0] / l * s, dir[1] / l * s, dir[2] / l * s};
    }

    private static boolean finite(double[] v) {
        return Double.isFinite(v[0]) && Double.isFinite(v[1]) && Double.isFinite(v[2]);
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
