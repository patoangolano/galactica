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
 *   <li>{@link #resolvedClusters} and {@link StarCluster#members} feed resolved cluster stars
 *       to the starfield, with the same per-cluster magnitude limit ({@link #resolution}) the
 *       view uses to dim each cluster's glow;</li>
 *   <li>{@link #rebakeDistancePc} tells the sky manager how far the viewer may move before the
 *       bake must follow the starfield near deep-sky objects;</li>
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
    /**
     * Discrete procedural H II regions fade out between this distance and {@link #VIEW_RADIUS_PC}
     * while the baker's statistical H II glow fades in over the same band
     * ({@link #discreteHiiWeight} + {@link #statisticalHiiWeight} = 1).
     */
    public static final double HII_CROSSFADE_START_PC = 0.8 * VIEW_RADIUS_PC;
    /** The starfield is recomputed after this much travel; the sky bake never needs to be finer. */
    public static final double STARFIELD_STEP_PC = 0.25;
    private static final int MAX_CACHED_CELLS = 30_000;
    private static final Map<Long, DeepSky> INSTANCES = new ConcurrentHashMap<>();

    private final long seed;
    private volatile List<DeepSkyObject> globulars;
    private final Map<Long, List<DeepSkyObject>> cells = new ConcurrentHashMap<>();
    /** Last dust view built: the sky bake and the starfield at the same point share it. */
    private volatile DeepSkyView lastDust;

    private DeepSky(long seed) {
        this.seed = seed;
    }

    public static DeepSky of(long seed) {
        return INSTANCES.computeIfAbsent(seed, DeepSky::new);
    }

    /**
     * Client init: warms the catalogue, the globular system and the populations of the named
     * clusters on a background daemon thread, so the first sky bake does not pay for them.
     */
    public static void initClient(long galaxySeed) {
        Thread t = new Thread(() -> {
            DeepSky ds = of(galaxySeed);
            ds.globulars();
            for (DeepSkyObject o : DeepSkyCatalog.all()) {
                o.blobs();
                o.fields();
            }
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

    /** Weight of a discrete procedural H II region whose centre is {@code dPc} from the observer. */
    public static double discreteHiiWeight(double dPc) {
        return 1 - statisticalHiiWeight(dPc);
    }

    /** Weight of the baker's statistical H II glow at distance {@code sPc} along a ray. */
    public static double statisticalHiiWeight(double sPc) {
        return DeepMath.smoothstep(HII_CROSSFADE_START_PC, VIEW_RADIUS_PC, sPc);
    }

    /**
     * Prepared volumes for a sky bake whose pixels subtend {@code pixelAngle} radians. Each
     * cluster's glow carries exactly the light of the members {@link #resolvedClusters} does not
     * hand to the starfield from the same point.
     */
    public DeepSkyView view(double ox, double oy, double oz, double pixelAngle) {
        DeepSkyView dust = dustView(ox, oy, oz);
        List<DeepSkyObject> objects = objectsFor(ox, oy, oz);
        return new DeepSkyView(ox, oy, oz, objects, pixelAngle, false,
                c -> c.unresolvedFraction(resolution(c, ox, oy, oz, dust).limitMv()));
    }

    /** Dust volumes only, unblurred, for the extinction of resolved stars. Cached for the last point. */
    public DeepSkyView dustView(double ox, double oy, double oz) {
        DeepSkyView d = lastDust;
        if (d != null && d.ox == ox && d.oy == oy && d.oz == oz) return d;
        d = new DeepSkyView(ox, oy, oz, objectsFor(ox, oy, oz), 0, true);
        lastDust = d;
        return d;
    }

    /**
     * How a cluster is seen from a point: distance, V extinction to its centre (smooth dust model
     * plus discrete dark clouds) and the absolute magnitude down to which its members are drawn as
     * stars ({@code limitMv} below {@link StarCluster#brightestMv} means none are).
     */
    public record ClusterResolution(StarCluster cluster, double distancePc, double avMag, double limitMv) {
        public boolean resolvesAny() {
            return limitMv >= cluster.brightestMv();
        }
    }

    /**
     * The single definition of a cluster's resolution limit, used by both the sky bake (glow
     * scale) and the starfield (members drawn), so their light adds up to the cluster's.
     * Extinction only lowers the limit, so it is skipped when the cluster is unresolved anyway.
     */
    public static ClusterResolution resolution(StarCluster c, double ox, double oy, double oz, DeepSkyView dust) {
        double d = Math.max(1e-6, c.distanceTo(ox, oy, oz));
        double unextincted = c.resolvedMagnitudeLimit(d, 0);
        if (unextincted < c.brightestMv()) return new ClusterResolution(c, d, 0, unextincted);
        double ux = (c.x - ox) / d, uy = (c.y - oy) / d, uz = (c.z - oz) / d;
        double av = DeepMath.smoothExtinction(ox, oy, oz, ux, uy, uz, d) + (dust != null ? dust.extinction(ux, uy, uz, d) : 0);
        return new ClusterResolution(c, d, av, c.resolvedMagnitudeLimit(d, av));
    }

    private List<StarCluster> clusterCandidates(double ox, double oy, double oz, double proceduralRadius) {
        List<StarCluster> out = new ArrayList<>();
        List<DeepSkyObject> cands = new ArrayList<>(DeepSkyCatalog.all());
        cands.addAll(globulars());
        cands.addAll(proceduralNear(ox, oy, oz, proceduralRadius));
        for (DeepSkyObject o : cands) if (o instanceof StarCluster c) out.add(c);
        return out;
    }

    /** Clusters with at least one member drawn as a star from (ox, oy, oz), with their resolution. */
    public List<ClusterResolution> resolvedClusters(double ox, double oy, double oz) {
        DeepSkyView dust = dustView(ox, oy, oz);
        List<ClusterResolution> out = new ArrayList<>();
        for (StarCluster c : clusterCandidates(ox, oy, oz, CLUSTER_RADIUS_PC)) {
            ClusterResolution r = resolution(c, ox, oy, oz, dust);
            if (r.resolvesAny()) out.add(r);
        }
        return out;
    }

    /**
     * Cluster member stars within {@code radiusPc} of a point (all members, no brightness cut),
     * nearest first, so a navigation layer can treat them as visitable systems. Members outside
     * the query sphere are rejected from their position alone, before stellar evolution
     * ({@link StarCluster#membersNear}).
     */
    public List<Star> clusterStarsNear(double x, double y, double z, double radiusPc, int maxStars) {
        List<Star> out = new ArrayList<>();
        for (StarCluster c : clusterCandidates(x, y, z, Math.min(CLUSTER_RADIUS_PC, radiusPc + 50))) {
            if (c.distanceTo(x, y, z) > c.radiusPc + radiusPc) continue;
            out.addAll(c.membersNear(x, y, z, radiusPc, maxStars));
        }
        out.sort((a, b) -> Double.compare(a.distanceTo(x, y, z), b.distanceTo(x, y, z)));
        return out.size() > maxStars ? new ArrayList<>(out.subList(0, maxStars)) : out;
    }

    /**
     * Travel (pc) after which the sky bake should be redone at (x, y, z), given the manager's
     * usual distance {@code defaultPc}. Near a deep-sky object the bake must keep up with the
     * starfield (recomputed every {@link #STARFIELD_STEP_PC}): both derive a cluster's resolution
     * limit from the observer's position, and a nebula a few parsecs across shifts visibly against
     * the stars. Each object allows a tenth of max(distance to its centre, a quarter of its
     * radius): at most ~0.2 mag of change in a cluster's limit and ~6 degrees of parallax.
     */
    public double rebakeDistancePc(double x, double y, double z, double defaultPc) {
        double step = defaultPc;
        double reach = Math.min(10 * defaultPc, CLUSTER_RADIUS_PC);
        List<DeepSkyObject> cands = new ArrayList<>(DeepSkyCatalog.all());
        cands.addAll(globulars());
        cands.addAll(proceduralNear(x, y, z, reach));
        for (DeepSkyObject o : cands) {
            if (o.type == DeepSkyType.GALAXY) continue; // tens of kiloparsecs from anywhere in the Milky Way
            double d = o.distanceTo(x, y, z);
            if (d - o.radiusPc > reach) continue;
            step = Math.min(step, 0.1 * Math.max(d, 0.25 * o.radiusPc));
        }
        return Math.max(STARFIELD_STEP_PC, step);
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
