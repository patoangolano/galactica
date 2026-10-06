package dev.galactica.nav;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * What is near a point: the closest star (catalog or procedural) and, when it is close enough to
 * matter, that star's planets and moons with their current positions. Used by the drive governor
 * on the server and by the HUD and renderer on the client. Not thread-safe; one instance per user.
 */
public final class Neighbourhood {
    /** Inside this distance of a star its planetary system is resolved (≈ 2 light-years). */
    public static final double SYSTEM_RADIUS_M = 0.6 * Units.PARSEC;

    private static final double DENSE_SECTOR = 2_000;

    public record Located(Body body, Body parent, GalacticPos pos) {}

    private final Galaxy galaxy;
    private SectorKey cachedSector;
    private List<Star> cachedStars = List.of();
    private Star systemStar;
    private List<Body> systemBodies = List.of();

    public Neighbourhood(Galaxy galaxy) {
        this.galaxy = galaxy;
    }

    /** All stars in the 3×3×3 block of sectors around the point (cached per sector). */
    public List<Star> nearbyStars(GalacticPos p) {
        SectorKey k = p.sector();
        if (!k.equals(cachedSector)) {
            List<Star> stars = new ArrayList<>();
            for (Star s : StarCatalog.all()) {
                SectorKey sk = SectorKey.containing(s.x(), s.y(), s.z());
                if (Math.abs(sk.ix() - k.ix()) <= 1 && Math.abs(sk.iy() - k.iy()) <= 1 && Math.abs(sk.iz() - k.iz()) <= 1) stars.add(s);
            }
            // In the bulge a sector holds thousands of stars, so the nearest one is almost surely in
            // our own sector; scanning 26 neighbours there would stall the server for no gain.
            int reach = galaxy.expectedStarCount(k) > DENSE_SECTOR ? 0 : 1;
            for (int dx = -reach; dx <= reach; dx++)
                for (int dy = -reach; dy <= reach; dy++)
                    for (int dz = -reach; dz <= reach; dz++) {
                        SectorKey sk = k.offset(dx, dy, dz);
                        int n = galaxy.starCount(sk);
                        for (int i = 0; i < n; i++) stars.add(galaxy.star(sk, i));
                    }
            cachedStars = stars;
            cachedSector = k;
        }
        return cachedStars;
    }

    public Star nearestStar(GalacticPos p) {
        Star best = null;
        double bestD = Double.MAX_VALUE;
        double x = p.xPc(), y = p.yPc(), z = p.zPc();
        for (Star s : nearbyStars(p)) {
            if (s.kind() == dev.galactica.star.StarKind.BLACK_HOLE && s.mass() > 1e5) continue; // Sgr A* handled by its own region
            double d = s.distanceTo(x, y, z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    public static GalacticPos starPos(Star s) {
        if (s == StarCatalog.SOL) return Frames.SOL_POS;
        return GalacticPos.ofParsecs(s.x(), s.y(), s.z());
    }

    /** The star whose system we are in, or null in interstellar space. */
    public Star systemStar(GalacticPos p) {
        Star s = nearestStar(p);
        if (s == null || starPos(s).distanceMetres(p) > SYSTEM_RADIUS_M) return null;
        if (s != systemStar) {
            systemStar = s;
            systemBodies = SystemGenerator.generate(s);
        }
        return s;
    }

    public List<Body> systemBodies() {
        return systemBodies;
    }

    /** Every planet and moon of the current system at time t, as galactic positions. */
    public List<Located> locateBodies(GalacticPos p, double t) {
        Star s = systemStar(p);
        if (s == null) return List.of();
        GalacticPos sp = starPos(s);
        List<Located> out = new ArrayList<>();
        double[] buf = new double[3];
        for (Body planet : systemBodies) {
            planet.orbit().positionAt(t, buf);
            double[] g = Frames.mcToGalactic(buf[0], buf[1], buf[2]);
            GalacticPos pp = sp.plusMetres(g[0], g[1], g[2]);
            out.add(new Located(planet, null, pp));
            for (Body moon : planet.moons()) {
                moon.orbit().positionAt(t, buf);
                double[] gm = Frames.mcToGalactic(buf[0], buf[1], buf[2]);
                out.add(new Located(moon, planet, pp.plusMetres(gm[0], gm[1], gm[2])));
            }
        }
        return out;
    }

    /**
     * Distance in metres to the nearest surface (star photosphere or planet radius). The warp
     * governor caps speed to a fraction of it, so travel always slows down on arrival.
     */
    public double distanceToNearestSurface(GalacticPos p, double t) {
        Star s = nearestStar(p);
        double best = Double.MAX_VALUE;
        if (s != null) best = starPos(s).distanceMetres(p) - s.radiusMetres();
        for (Located l : locateBodies(p, t)) {
            best = Math.min(best, l.pos().distanceMetres(p) - l.body().radiusMetres());
        }
        return Math.max(0, best);
    }
}
