package dev.galactica.nav.target;

import dev.galactica.deepsky.DeepSky;
import dev.galactica.deepsky.DeepSkyCatalog;
import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds navigation targets by name, for the map's search box and {@code /galactica target}: catalog stars ("Sol",
 * "Sirius A"), planets and moons of the current system, of the Solar System and of every catalog star's system
 * ("Mars", "Proxima Centauri b"), deep-sky objects (the catalogue and the globular clusters, by name or id) and
 * procedural stars by survey designation ("GX S+0817.+0000.+002-12"). Exact names first, then prefixes, then
 * substrings. Pure Java.
 */
public final class TargetSearch {
    private TargetSearch() {}

    /** "GX S+0817.+0000.+002-12": sector code then the global index. */
    private static final Pattern DESIGNATION = Pattern.compile("(?i)GX\\s*S([+-]\\d+)\\.([+-]\\d+)\\.([+-]\\d+)-(\\d+)");

    public static List<NavTarget> search(Galaxy galaxy, String query, @Nullable Star currentSystem, int max) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<NavTarget> exact = new ArrayList<>(), prefix = new ArrayList<>(), inner = new ArrayList<>();
        if (q.isEmpty()) return List.of();
        NavTarget designated = designation(galaxy, query.trim());
        if (designated != null) return List.of(designated);

        List<Star> systems = new ArrayList<>();
        if (currentSystem != null) systems.add(currentSystem);
        if (currentSystem != StarCatalog.SOL) systems.add(StarCatalog.SOL);
        for (Star s : StarCatalog.all()) {
            classify(q, s.name(), star(s), exact, prefix, inner);
            if (!systems.contains(s)) systems.add(s);
        }
        for (Star s : systems) {
            int[] a = addressOf(galaxy, s);
            if (a == null) continue;
            SectorKey k = new SectorKey(a[0], a[1], a[2]);
            for (Body planet : TargetGeometry.system(s)) {
                classify(q, planet.name(), body(s, k, a[3], planet, NavTarget.Kind.PLANET), exact, prefix, inner);
                for (Body moon : planet.moons()) classify(q, moon.name(), body(s, k, a[3], moon, NavTarget.Kind.MOON), exact, prefix, inner);
            }
            if (!s.catalog()) classify(q, s.name(), star(s, k, a[3]), exact, prefix, inner);
        }
        for (DeepSkyObject o : DeepSkyCatalog.all()) {
            NavTarget t = new NavTarget(NavTarget.Kind.DEEP_SKY, o.name, 0L, 0, 0, 0, 0, 0L, o.id, o.x, o.y, o.z);
            if (o.id.equalsIgnoreCase(q)) exact.add(t);
            else classify(q, o.name, t, exact, prefix, inner);
        }
        for (DeepSkyObject o : DeepSky.of(galaxy.seed()).globulars()) {
            classify(q, o.name, new NavTarget(NavTarget.Kind.DEEP_SKY, o.name, 0L, 0, 0, 0, 0, 0L, o.id, o.x, o.y, o.z),
                    exact, prefix, inner);
        }
        List<NavTarget> out = new ArrayList<>();
        for (List<NavTarget> l : List.of(exact, prefix, inner)) {
            for (NavTarget t : l) {
                if (out.size() >= max) return out;
                boolean dup = false;
                for (NavTarget o : out) dup |= o.sameObject(t);
                if (!dup) out.add(t);
            }
        }
        return out;
    }

    private static void classify(String q, String name, NavTarget t, List<NavTarget> exact, List<NavTarget> prefix, List<NavTarget> inner) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals(q)) exact.add(t);
        else if (n.startsWith(q)) prefix.add(t);
        else if (q.length() >= 3 && n.contains(q)) inner.add(t);
    }

    public static NavTarget star(Star s) {
        if (s.catalog()) return new NavTarget(NavTarget.Kind.STAR, s.name(), s.id(), 0, 0, 0, NavTarget.CATALOG, 0L, "", s.x(), s.y(), s.z());
        throw new IllegalArgumentException("procedural stars need their address");
    }

    /** A procedural star's target from its galaxy address. */
    public static NavTarget star(Star s, SectorKey k, int index) {
        if (s.catalog()) return star(s);
        return new NavTarget(NavTarget.Kind.STAR, s.name(), s.id(), k.ix(), k.iy(), k.iz(), index, 0L, "", s.x(), s.y(), s.z());
    }

    /** A planet or moon of a catalog star's system. */
    public static NavTarget body(Star s, Body b, NavTarget.Kind kind) {
        return new NavTarget(kind, b.name(), s.id(), 0, 0, 0, NavTarget.CATALOG, b.id(), "", s.x(), s.y(), s.z());
    }

    /** A planet or moon of any star, with the star's address. */
    public static NavTarget body(Star s, SectorKey k, int index, Body b, NavTarget.Kind kind) {
        if (s.catalog()) return body(s, b, kind);
        return new NavTarget(kind, b.name(), s.id(), k.ix(), k.iy(), k.iz(), index, b.id(), "", s.x(), s.y(), s.z());
    }

    /**
     * The galaxy address {ix, iy, iz, index} of a star (index {@link NavTarget#CATALOG} for catalog stars), found by
     * scanning its sector for its id; null if it is not there (the S-stars and other synthetic stars).
     */
    public static int @Nullable [] addressOf(Galaxy galaxy, Star s) {
        if (s.catalog()) return StarCatalog.all().contains(s) ? new int[] {0, 0, 0, NavTarget.CATALOG} : null;
        SectorKey k = SectorKey.containing(s.x(), s.y(), s.z());
        int n = galaxy.starCount(k);
        for (int i = 0; i < n; i++) {
            if (galaxy.star(k, i).id() == s.id()) return new int[] {k.ix(), k.iy(), k.iz(), i};
        }
        return null;
    }

    /** A procedural star named by its survey designation, or null. */
    public static @Nullable NavTarget designation(Galaxy galaxy, String text) {
        Matcher m = DESIGNATION.matcher(text);
        if (!m.matches()) return null;
        try {
            SectorKey k = new SectorKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            int index = Integer.parseInt(m.group(4));
            if (Math.abs(k.centerX()) > NavTargetRules.MAX_RADIUS_PC || Math.abs(k.centerY()) > NavTargetRules.MAX_RADIUS_PC
                    || Math.abs(k.centerZ()) > NavTargetRules.MAX_RADIUS_PC) return null;
            if (index < 0 || index >= galaxy.starCount(k)) return null;
            Star s = galaxy.star(k, index);
            return star(s, k, index);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
