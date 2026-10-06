package dev.galactica.ship;

import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Units;
import dev.galactica.star.Star;

import java.util.ArrayList;
import java.util.List;

/**
 * Space gravity at the ship (spec §2): GM/r² toward the single dominant body, chosen patched-conic style. A
 * planet or moon dominates inside its Laplace sphere of influence ({@code Orbit.sphereOfInfluence}); nested
 * spheres resolve to the innermost (a moon inside its planet's sphere); everywhere else the nearest star pulls.
 * One body at a time is what patched-conic flight assumes, and it keeps the client prediction and the server
 * bound agreeing on a single vector. Pure Java, galactic frame, metres.
 */
public final class ShipGravity {
    private ShipGravity() {}

    /**
     * A body that can pull the ship.
     *
     * @param offset metres from the ship to the body's centre, galactic frame
     * @param mu     GM, m³/s²
     * @param soi    sphere-of-influence radius, metres ({@link Double#POSITIVE_INFINITY} for the star)
     * @param radius surface radius, metres: below it the pull is held at its surface value
     */
    public record Attractor(double[] offset, double mu, double soi, double radius) {}

    /** Acceleration on the ship, m/s², galactic frame; zero when nothing is listed. */
    public static double[] pull(List<Attractor> bodies) {
        Attractor best = null;
        for (Attractor a : bodies) {
            if (length(a.offset()) < a.soi() && (best == null || a.soi() < best.soi())) best = a;
        }
        if (best == null) return new double[3];
        double r = length(best.offset());
        if (r == 0) return new double[3];
        double g = best.mu() / (Math.max(r, best.radius()) * Math.max(r, best.radius()));
        return new double[] {best.offset()[0] / r * g, best.offset()[1] / r * g, best.offset()[2] / r * g};
    }

    /** The attractors of the system around {@code here}: its star, then every planet and moon at time t. */
    public static List<Attractor> attractors(Neighbourhood n, GalacticPos here, double t) {
        List<Attractor> out = new ArrayList<>();
        Star s = n.nearestStar(here);
        if (s == null) return out;
        out.add(new Attractor(Neighbourhood.starPos(s).metresFrom(here), Units.G * s.massKg(), Double.POSITIVE_INFINITY, s.radiusMetres()));
        for (Neighbourhood.Located l : n.locateBodies(here, t)) {
            var b = l.body();
            out.add(new Attractor(l.pos().metresFrom(here), Units.G * b.massKg(), b.orbit().sphereOfInfluence(b.massKg()), b.radiusMetres()));
        }
        return out;
    }

    private static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
