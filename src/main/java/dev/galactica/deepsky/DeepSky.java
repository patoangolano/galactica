package dev.galactica.deepsky;

import dev.galactica.star.Star;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the deep-sky module: nebulae, star clusters and galaxies, real and procedural.
 *
 * <ul>
 *   <li>{@link #view} prepares every volume visible from a point for the galaxy sky baker;</li>
 *   <li>{@link #clustersForStarfield} and {@link StarCluster#members} feed resolved cluster stars
 *       to the starfield;</li>
 *   <li>{@link #near} names the closest catalogued object for the HUD.</li>
 * </ul>
 * Pure Java, thread-safe, deterministic in the galaxy seed. Real objects
 * ({@link DeepSkyCatalog}) are the same in every world.
 */
public final class DeepSky {
    /** Procedural H II regions and dark clouds are enumerated out to this distance from the observer. */
    public static final double VIEW_RADIUS_PC = 3_000;
    /** Procedural open clusters are enumerated out to this distance (beyond, they are sub-pixel and resolve no stars). */
    public static final double CLUSTER_RADIUS_PC = 2_000;
    private static final int MAX_CACHED_CELLS = 30_000;
    private static final Map<Long, DeepSky> INSTANCES = new ConcurrentHashMap<>();

    private final long seed;
    private volatile List<DeepSkyObject> globulars;
    private final Map<Long, List<DeepSkyObject>> cells = new ConcurrentHashMap<>();

    private DeepSky(long seed) {
        this.seed = seed;
    }

    public static DeepSky of(long seed) {
        return INSTANCES.computeIfAbsent(seed, DeepSky::new);
    }

    /** Common init: nothing to register; builds the catalogue so the first lookup is instant. */
    public static void init() {
        DeepSkyCatalog.all();
    }

    /**
     * Client init: warms the catalogue, the globular system and the populations of the named
     * clusters on a background daemon thread, so the first sky bake does not pay for them.
     */
    public static void initClient(long galaxySeed) {
        Thread t = new Thread(() -> {
            DeepSky ds = of(galaxySeed);
            ds.globulars();
            for (DeepSkyObject o : DeepSkyCatalog.all()) o.blobs();
        }, "Galactica deep-sky warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public long seed() {
        return seed;
    }

    public List<DeepSkyObject> globulars() {
        List<DeepSkyObject> g = globulars;
        if (g == null) {
            synchronized (this) {
                g = globulars;
                if (g == null) {
                    g = List.copyOf(ProceduralDeepSky.globulars(seed));
                    globulars = g;
                }
            }
        }
        return g;
    }

    private List<DeepSkyObject> cell(int ix, int iy) {
        long key = ProceduralDeepSky.cellKey(ix, iy);
        List<DeepSkyObject> c = cells.get(key);
        if (c == null) {
            if (cells.size() > MAX_CACHED_CELLS) cells.clear();
            c = List.copyOf(ProceduralDeepSky.cell(seed, ix, iy));
            cells.put(key, c);
        }
        return c;
    }

    /** Procedural disk objects whose centre lies within {@code radius} of (ox, oy, oz). */
    public List<DeepSkyObject> proceduralNear(double ox, double oy, double oz, double radius) {
        List<DeepSkyObject> out = new ArrayList<>();
        double cs = ProceduralDeepSky.CELL_PC;
        if (Math.abs(oz) > radius + 600) return out;
        int i0 = (int) Math.floor((ox - radius) / cs), i1 = (int) Math.floor((ox + radius) / cs);
        int j0 = (int) Math.floor((oy - radius) / cs), j1 = (int) Math.floor((oy + radius) / cs);
        double r2 = radius * radius;
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                double cx = Math.max(i * cs, Math.min(ox, (i + 1) * cs)), cy = Math.max(j * cs, Math.min(oy, (j + 1) * cs));
                if ((cx - ox) * (cx - ox) + (cy - oy) * (cy - oy) > r2) continue;
                for (DeepSkyObject o : cell(i, j)) {
                    double d = o.distanceTo(ox, oy, oz);
                    double lim = o.type == DeepSkyType.OPEN_CLUSTER ? Math.min(radius, CLUSTER_RADIUS_PC) : radius;
                    if (d <= lim) out.add(o);
                }
            }
        }
        return out;
    }

    /** Every object relevant to a sky seen from (ox, oy, oz): catalogue, globulars, nearby procedural objects. */
    public List<DeepSkyObject> objectsFor(double ox, double oy, double oz) {
        List<DeepSkyObject> out = new ArrayList<>(DeepSkyCatalog.all());
        out.addAll(globulars());
        out.addAll(proceduralNear(ox, oy, oz, VIEW_RADIUS_PC));
        return out;
    }

    /** Prepared volumes for a sky bake whose pixels subtend {@code pixelAngle} radians. */
    public DeepSkyView view(double ox, double oy, double oz, double pixelAngle) {
        return new DeepSkyView(ox, oy, oz, objectsFor(ox, oy, oz), pixelAngle, false);
    }

    /** Dust volumes only, unblurred, for the extinction of resolved stars. */
    public DeepSkyView dustView(double ox, double oy, double oz) {
        return new DeepSkyView(ox, oy, oz, objectsFor(ox, oy, oz), 0, true);
    }

    /**
     * Clusters that have at least one member bright enough to be drawn as a star from
     * (ox, oy, oz), i.e. whose resolved magnitude limit reaches their brightest member.
     */
    public List<StarCluster> clustersForStarfield(double ox, double oy, double oz) {
        List<StarCluster> out = new ArrayList<>();
        List<DeepSkyObject> cands = new ArrayList<>(DeepSkyCatalog.all());
        cands.addAll(globulars());
        cands.addAll(proceduralNear(ox, oy, oz, CLUSTER_RADIUS_PC));
        for (DeepSkyObject o : cands) {
            if (!(o instanceof StarCluster c)) continue;
            double d = c.distanceTo(ox, oy, oz);
            double lim = c.resolvedMagnitudeLimit(d);
            double brightest = c.population.brightestMv();
            for (Star s : c.anchorStars()) brightest = Math.min(brightest, s.absoluteMagnitude());
            if (lim >= brightest) out.add(c);
        }
        return out;
    }

    /**
     * Cluster member stars within {@code radiusPc} of a point (all members, no brightness cut),
     * so a navigation layer can treat them as visitable systems. Bounded by {@code maxStars}.
     */
    public List<Star> clusterStarsNear(double x, double y, double z, double radiusPc, int maxStars) {
        List<Star> out = new ArrayList<>();
        for (StarCluster c : clustersForStarfield(x, y, z)) {
            if (c.distanceTo(x, y, z) > c.radiusPc + radiusPc) continue;
            c.members(99, s -> {
                if (out.size() < maxStars && s.distanceTo(x, y, z) <= radiusPc) out.add(s);
            });
        }
        out.sort((a, b) -> Double.compare(a.distanceTo(x, y, z), b.distanceTo(x, y, z)));
        return out;
    }

    // ------------------------------------------------------------------ HUD lookup

    /** A catalogued object and the distance from the query point to its edge (0 when inside). */
    public record Nearby(DeepSkyObject object, double distancePc) {}

    /**
     * The catalogued object nearest to (x, y, z), measured to its bounding radius, if within
     * {@code radiusPc}. When the point is inside several objects the smallest one wins (inside
     * the Trapezium Cluster that is also inside the Orion Nebula, the cluster is reported).
     */
    public static Nearby nearest(double x, double y, double z, double radiusPc) {
        Nearby best = null;
        for (DeepSkyObject o : DeepSkyCatalog.all()) {
            double d = Math.max(0, o.distanceTo(x, y, z) - o.radiusPc);
            if (d > radiusPc) continue;
            if (best == null || d < best.distancePc
                    || (d == 0 && best.distancePc == 0 && o.radiusPc < best.object.radiusPc)) {
                best = new Nearby(o, d);
            }
        }
        return best;
    }

    /** Name of the nearest catalogued object within {@code radiusPc} of (x, y, z), or null. */
    public static String near(double x, double y, double z, double radiusPc) {
        Nearby n = nearest(x, y, z, radiusPc);
        return n == null ? null : n.object.name;
    }
}
