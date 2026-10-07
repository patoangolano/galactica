package dev.galactica.nav.target;

import dev.galactica.deepsky.DeepSky;
import dev.galactica.deepsky.DeepSkyCatalog;
import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.net.JumpTargetRules;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemFrame;
import dev.galactica.system.SystemGenerator;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a {@link NavTarget} is at a given time and what it is physically: the system star re-derived from its galaxy
 * address, the planet or moon placed by its Kepler orbit through the system's {@link SystemFrame} (the same arithmetic
 * as {@link Neighbourhood#locateBodies}), or a deep-sky object's centre. Pure Java, thread-safe (the system cache is
 * synchronised), shared by the server's autopilot, the client's guidance, the HUD and the map.
 */
public final class TargetGeometry {
    private TargetGeometry() {}

    /**
     * A resolved target.
     *
     * @param star    the system star, null for a deep-sky target
     * @param body    the planet or moon, null otherwise
     * @param parent  the planet a moon orbits, null otherwise
     * @param deepSky the deep-sky object, null otherwise
     * @param pos     the object's centre at the time asked
     * @param radius  surface radius, metres (photosphere for a star, Schwarzschild radius for a black hole, the
     *                object's extent for a deep-sky target)
     * @param mu      GM, m³/s² (0 for a deep-sky target)
     */
    public record Resolved(@Nullable Star star, @Nullable Body body, @Nullable Body parent, @Nullable DeepSkyObject deepSky,
                           GalacticPos pos, double radius, double mu) {}

    private static final int SYSTEMS_CACHED = 16;
    private static final Map<Long, List<Body>> SYSTEMS = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<Body>> e) {
            return size() > SYSTEMS_CACHED;
        }
    };

    /** The planets of {@code s} (moons inside them), generated once per star and cached. */
    public static List<Body> system(Star s) {
        synchronized (SYSTEMS) {
            List<Body> b = SYSTEMS.get(s.id());
            if (b == null) {
                b = SystemGenerator.generate(s);
                SYSTEMS.put(s.id(), b);
            }
            return b;
        }
    }

    /** The target's system star, or null for a deep-sky target or an address that does not hold it. */
    public static @Nullable Star star(Galaxy galaxy, NavTarget t) {
        if (!t.kind().hasStar()) return null;
        return JumpTargetRules.resolve(galaxy, t.starId(), t.sector(), t.index());
    }

    /** A catalogued or globular deep-sky object by id, or null. */
    public static @Nullable DeepSkyObject deepSky(long galaxySeed, String id) {
        if (id == null || id.isEmpty()) return null;
        for (DeepSkyObject o : DeepSkyCatalog.all()) if (o.id.equals(id)) return o;
        for (DeepSkyObject o : DeepSky.of(galaxySeed).globulars()) if (o.id.equals(id)) return o;
        return null;
    }

    /** Resolves {@code t} at time {@code time} (seconds since J2000), or null when it no longer exists. */
    public static @Nullable Resolved resolve(Galaxy galaxy, NavTarget t, double time) {
        if (t.kind() == NavTarget.Kind.DEEP_SKY) {
            DeepSkyObject o = deepSky(galaxy.seed(), t.deepSkyId());
            if (o == null) return null;
            return new Resolved(null, null, null, o, GalacticPos.ofParsecs(o.x, o.y, o.z), o.radiusPc * Units.PARSEC, 0);
        }
        Star s = star(galaxy, t);
        if (s == null) return null;
        GalacticPos sp = Neighbourhood.starPos(s);
        if (t.kind() == NavTarget.Kind.STAR) return new Resolved(s, null, null, null, sp, starRadius(s), Units.G * s.massKg());
        for (Body planet : system(s)) {
            if (planet.id() == t.bodyId()) {
                return new Resolved(s, planet, null, null, bodyPos(s, sp, planet, null, time), planet.radiusMetres(), Units.G * planet.massKg());
            }
            for (Body moon : planet.moons()) {
                if (moon.id() == t.bodyId()) {
                    return new Resolved(s, moon, planet, null, bodyPos(s, sp, planet, moon, time), moon.radiusMetres(), Units.G * moon.massKg());
                }
            }
        }
        return null;
    }

    /** A star's surface for arrival and the warp governor: the photosphere, or the horizon of a black hole. */
    public static double starRadius(Star s) {
        return s.radiusMetres() > 0 ? s.radiusMetres() : 2 * Units.G * s.massKg() / (Units.C * Units.C);
    }

    /** A planet ({@code moon == null}) or a moon of it at time t, as {@link Neighbourhood} places them. */
    public static GalacticPos bodyPos(Star s, GalacticPos starPos, Body planet, @Nullable Body moon, double t) {
        double[] buf = new double[3], g = new double[3];
        planet.orbit().positionAt(t, buf);
        SystemFrame.toGalactic(s, buf[0], buf[1], buf[2], g);
        GalacticPos pp = starPos.plusMetres(g[0], g[1], g[2]);
        if (moon == null) return pp;
        moon.orbit().positionAt(t, buf);
        SystemFrame.toGalactic(s, buf[0], buf[1], buf[2], g);
        return pp.plusMetres(g[0], g[1], g[2]);
    }

    /** Heliocentric velocity of a body (central difference over ±0.5 s), galactic m/s. */
    public static double[] bodyVelocity(Star s, Body planet, @Nullable Body moon, double t) {
        GalacticPos sp = Neighbourhood.starPos(s);
        double[] d = bodyPos(s, sp, planet, moon, t + 0.5).metresFrom(bodyPos(s, sp, planet, moon, t - 0.5));
        return d;
    }
}
