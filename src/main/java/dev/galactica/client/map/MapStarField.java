package dev.galactica.client.map;

import dev.galactica.Galactica;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.nav.GalacticPos;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The procedural stars the 3D map streams around its focus, listed and packed into a vertex buffer on a worker thread
 * from the same deterministic galaxy the server uses. What is listed depends on the zoom:
 *
 * <ul>
 *   <li>camera beyond {@link #REGION_MAX_PC}: none (the disk impostor and the catalog carry the view);</li>
 *   <li>a region view: the luminous stream (≥ 1 M☉ initial mass, the stars visible across such distances) within a
 *       sphere up to {@link #REGION_RADIUS_PC} around the focus;</li>
 *   <li>a neighbourhood view (camera within {@link #LOCAL_MAX_PC}): every star within up to {@link #LOCAL_RADIUS_PC}.</li>
 * </ul>
 *
 * Sector lists are cached (LRU), so panning re-lists only the sectors that came into reach, and at most
 * {@link #MAX_STARS} of the brightest are kept. The render thread only uploads the finished buffer.
 */
public final class MapStarField {
    public static final double REGION_MAX_PC = 2_500, LOCAL_MAX_PC = 25;
    public static final double REGION_RADIUS_PC = 150, LOCAL_RADIUS_PC = 22;
    public static final int MAX_STARS = 15_000;
    /** POSITION_TEX_COLOR: 3 floats, 2 floats, 4 bytes. */
    public static final int VERTEX_BYTES = 24;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica map stars");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final int SECTORS_CACHED = 30_000;
    /** Per sector: every star (full) or the luminous stream, with their global indices. Worker thread only. */
    private static final Map<Long, Listed[]> FULL = lru(), LUMINOUS = lru();

    private record Listed(Star star, int index) {}

    private static Map<Long, Listed[]> lru() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Listed[]> e) {
                return size() > SECTORS_CACHED;
            }
        };
    }

    /**
     * A listed field: the stars (as pickable objects) and their sprites packed relative to {@code origin} in parsecs.
     *
     * @param key      the request it answers
     * @param vertices direct buffer of {@code stars.size() × 4} vertices, or null when empty
     */
    public record Batch(String key, GalacticPos origin, List<MapObject> stars, @Nullable ByteBuffer vertices) {}

    private CompletableFuture<Batch> pending;
    private String pendingKey = "", wantedKey = "";
    private Batch current = new Batch("", GalacticPos.ofParsecs(0, 0, 0), List.of(), null);
    private boolean fresh;

    public Batch current() {
        return current;
    }

    /** Asks the renderer to upload the current batch again (its GPU copy was dropped). */
    public void markFresh() {
        fresh = true;
    }

    /** True once after a new batch arrived (the renderer re-uploads it). */
    public boolean takeFresh() {
        boolean f = fresh;
        fresh = false;
        return f;
    }

    /** The radius (pc) and stream of the field for a camera at {@code distancePc} from its focus, or null for none. */
    public static double @Nullable [] reach(double distancePc) {
        if (distancePc > REGION_MAX_PC) return null;
        if (distancePc <= LOCAL_MAX_PC) return new double[] {Math.clamp(distancePc * 1.5, 4, LOCAL_RADIUS_PC), 1};
        return new double[] {Math.clamp(distancePc * 0.8, 20, REGION_RADIUS_PC), 0};
    }

    /** Asks for the field around the focus at this zoom; a new list is started when the focus has moved a quarter radius. */
    public void request(GalacticPos focus, double distanceMetres) {
        if (pending != null && pending.isDone()) {
            Batch b = pending.getNow(null);
            if (b != null && b.key().equals(pendingKey)) {
                current = b;
                fresh = true;
            }
            pending = null;
        }
        double[] reach = reach(distanceMetres / Units.PARSEC);
        if (reach == null) {
            if (!current.key().isEmpty()) {
                current = new Batch("", GalacticPos.ofParsecs(0, 0, 0), List.of(), null);
                fresh = true;
            }
            wantedKey = "";
            return;
        }
        double radius = reach[0];
        boolean full = reach[1] > 0;
        double cell = radius / 4;
        double fx = focus.xPc(), fy = focus.yPc(), fz = focus.zPc();
        // Radii quantised to powers of 1.5 so a slow zoom does not re-list every frame.
        double qr = Math.pow(1.5, Math.ceil(Math.log(radius) / Math.log(1.5)));
        String key = (full ? "F" : "L") + (int) Math.round(qr * 10) + ":" + Math.floor(fx / cell) + ":" + Math.floor(fy / cell) + ":" + Math.floor(fz / cell);
        wantedKey = key;
        if (key.equals(current.key()) || pending != null) return;
        pendingKey = key;
        double cx = (Math.floor(fx / cell) + 0.5) * cell, cy = (Math.floor(fy / cell) + 0.5) * cell, cz = (Math.floor(fz / cell) + 0.5) * cell;
        pending = CompletableFuture.supplyAsync(() -> build(Galactica.GALAXY, key, cx, cy, cz, qr, full), WORKER)
                .exceptionally(e -> {
                    Galactica.LOG.error("Galactica: listing 3D map stars failed", e);
                    return null;
                });
    }

    /** Lists and packs a field (worker thread). */
    static Batch build(Galaxy galaxy, String key, double cx, double cy, double cz, double radius, boolean full) {
        List<MapObject> out = new ArrayList<>();
        List<Listed> found = list(galaxy, cx, cy, cz, radius, full);
        found.sort(Comparator.comparingDouble(e -> e.star().absoluteMagnitude()));
        if (found.size() > MAX_STARS) found = found.subList(0, MAX_STARS);
        GalacticPos origin = GalacticPos.ofParsecs(cx, cy, cz);
        if (found.isEmpty()) return new Batch(key, origin, List.of(), null);
        ByteBuffer buf = ByteBuffer.allocateDirect(found.size() * 4 * VERTEX_BYTES).order(ByteOrder.nativeOrder());
        for (Listed e : found) {
            Star s = e.star();
            MapObject o = MapObject.star(s, SectorKey.containing(s.x(), s.y(), s.z()), e.index(), importance(s));
            out.add(o);
            putSprite(buf, (float) (s.x() - cx), (float) (s.y() - cy), (float) (s.z() - cz), (float) spritePx(s), o.argb(), alpha(s));
        }
        buf.flip();
        return new Batch(key, origin, List.copyOf(out), buf);
    }

    static List<Listed> list(Galaxy galaxy, double cx, double cy, double cz, double radius, boolean full) {
        List<Listed> found = new ArrayList<>();
        SectorKey lo = SectorKey.containing(cx - radius, cy - radius, cz - radius);
        SectorKey hi = SectorKey.containing(cx + radius, cy + radius, cz + radius);
        double r2 = radius * radius, half = Galaxy.SECTOR_SIZE_PC * 0.8660254;
        for (int ix = lo.ix(); ix <= hi.ix(); ix++) {
            for (int iy = lo.iy(); iy <= hi.iy(); iy++) {
                for (int iz = lo.iz(); iz <= hi.iz(); iz++) {
                    SectorKey k = new SectorKey(ix, iy, iz);
                    double dx = k.centerX() - cx, dy = k.centerY() - cy, dz = k.centerZ() - cz;
                    double dc = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dc - half > radius) continue;
                    for (Listed e : sector(galaxy, k, full)) {
                        Star s = e.star();
                        double ex = s.x() - cx, ey = s.y() - cy, ez = s.z() - cz;
                        if (ex * ex + ey * ey + ez * ez <= r2) found.add(e);
                    }
                }
            }
        }
        return found;
    }

    private static Listed[] sector(Galaxy galaxy, SectorKey k, boolean full) {
        Map<Long, Listed[]> cache = full ? FULL : LUMINOUS;
        long id = ((long) k.ix() & 0x1FFFFF) << 42 | ((long) k.iy() & 0x1FFFFF) << 21 | ((long) k.iz() & 0x1FFFFF);
        Listed[] e = cache.get(id);
        if (e != null) return e;
        int common = galaxy.commonCount(k);
        if (full) {
            int n = common + galaxy.luminousCount(k);
            e = new Listed[n];
            for (int i = 0; i < n; i++) e[i] = new Listed(galaxy.star(k, i), i);
        } else {
            int n = galaxy.luminousCount(k);
            e = new Listed[n];
            for (int j = 0; j < n; j++) e[j] = new Listed(galaxy.luminousStar(k, j), common + j);
        }
        cache.put(id, e);
        return e;
    }

    /** Sprite half-size in pixels from absolute visual magnitude: dwarfs 1.5 px, the Sun ~2 px, supergiants 7 px. */
    public static double spritePx(Star s) {
        double m = s.absoluteMagnitude();
        if (!Double.isFinite(m)) return 3.5;
        return Math.clamp(2.0 + (5.0 - m) * 0.45, 1.5, 7.0);
    }

    static float alpha(Star s) {
        double m = s.absoluteMagnitude();
        if (!Double.isFinite(m)) return 1F;
        return (float) Math.clamp(0.45 + (9.0 - m) * 0.06, 0.3, 1.0);
    }

    /** Label priority of a field star: named (pronounceable) stars before survey designations, brighter first. */
    static double importance(Star s) {
        double m = Double.isFinite(s.absoluteMagnitude()) ? s.absoluteMagnitude() : 10;
        return (s.name().startsWith("GX ") ? 0 : 10) + Math.clamp((10 - m) * 0.5, 0, 10);
    }

    /** One camera-facing quad: the centre four times, corners in UV (±size px), the colour with alpha. */
    static void putSprite(ByteBuffer b, float x, float y, float z, float sizePx, int argb, float alpha) {
        byte r = (byte) (argb >> 16), g = (byte) (argb >> 8), bl = (byte) argb, a = (byte) Math.round(Math.clamp(alpha, 0F, 1F) * 255);
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] c : corners) {
            b.putFloat(x).putFloat(y).putFloat(z).putFloat(c[0] * sizePx).putFloat(c[1] * sizePx);
            b.put(r).put(g).put(bl).put(a);
        }
    }
}
