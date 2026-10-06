package dev.galactica.client.map;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.star.Star;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The procedural stars a zoomed-in map shows, with the address ({@code sector}, {@code index})
 * the server needs to re-derive and validate a jump target. Stars come from the same
 * deterministic {@link Galaxy} the server uses, so nothing is sent but the address.
 *
 * <p>What is drawn per level is bounded by work, not by taste: the 300 pc view would hold ~10⁶
 * stars in a ±50 pc slab, so it enumerates only the luminous stream (≥ 1 M☉ initial mass, the
 * stars visible across that distance anyway, ~5 × 10⁴), while the 30 pc view enumerates every star
 * of a ±15 pc slab (~3 000). Either way the brightest {@link #MAX_STARS} by absolute magnitude are kept.
 */
public final class MapStars {
    private MapStars() {}

    public static final int MAX_STARS = 2_500;
    /** Views at most this wide (pc) list stars at all; wider views show only the catalog. */
    public static final double MAX_VIEW_PC = 300.0;
    /** Views at most this wide list every star; wider ones only the luminous stream. */
    public static final double FULL_STREAM_VIEW_PC = 30.0;

    /** One listed star with its galaxy address; {@code index} is the global index in the sector. */
    public record MapStar(Star star, SectorKey sector, int index) {}

    /** Vertical half-thickness (pc) of the slab around the observer that a view of this width lists. */
    public static double slabHalfPc(double viewWidthPc) {
        return viewWidthPc <= FULL_STREAM_VIEW_PC ? 15.0 : 50.0;
    }

    /**
     * The listed square is this much wider than the view, so a list computed for one player
     * position still fills the view until the screen asks for the next one (it refreshes after an
     * eighth of a view of movement).
     */
    public static final double MARGIN = 1.25;

    /**
     * Procedural stars inside the (margin-widened) view square centred on (cx, cy) and within the slab around cz.
     * Catalog stars are not included (the map draws {@link StarCatalog#all()} itself).
     */
    public static List<MapStar> list(Galaxy galaxy, double cx, double cy, double cz, double viewWidthPc) {
        List<MapStar> out = new ArrayList<>();
        if (viewWidthPc > MAX_VIEW_PC) return out;
        boolean full = viewWidthPc <= FULL_STREAM_VIEW_PC;
        double h = viewWidthPc * 0.5 * MARGIN, slab = slabHalfPc(viewWidthPc);
        SectorKey lo = SectorKey.containing(cx - h, cy - h, cz - slab);
        SectorKey hi = SectorKey.containing(cx + h, cy + h, cz + slab);
        for (int ix = lo.ix(); ix <= hi.ix(); ix++) {
            for (int iy = lo.iy(); iy <= hi.iy(); iy++) {
                for (int iz = lo.iz(); iz <= hi.iz(); iz++) {
                    SectorKey k = new SectorKey(ix, iy, iz);
                    int common = galaxy.commonCount(k);
                    if (full) {
                        int n = common + galaxy.luminousCount(k);
                        for (int i = 0; i < n; i++) keep(galaxy.star(k, i), k, i, cx, cy, cz, h, slab, out);
                    } else {
                        int n = galaxy.luminousCount(k);
                        for (int j = 0; j < n; j++) keep(galaxy.luminousStar(k, j), k, common + j, cx, cy, cz, h, slab, out);
                    }
                }
            }
        }
        if (out.size() > MAX_STARS) {
            out.sort(Comparator.comparingDouble(m -> m.star().absoluteMagnitude()));
            return new ArrayList<>(out.subList(0, MAX_STARS));
        }
        return out;
    }

    private static void keep(Star s, SectorKey k, int index, double cx, double cy, double cz, double h, double slab,
                             List<MapStar> out) {
        if (Math.abs(s.x() - cx) <= h && Math.abs(s.y() - cy) <= h && Math.abs(s.z() - cz) <= slab) {
            out.add(new MapStar(s, k, index));
        }
    }
}
