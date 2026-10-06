package dev.galactica.sky;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.Blackbody;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The resolved stars seen from a point: every star within {@link #FULL_RADIUS_PC}, every luminous
 * star (≥ 1 M☉ initial mass) within {@link #LUMINOUS_RADIUS_PC}, and the real catalog. Light beyond
 * those shells is already in the diffuse {@link GalaxySkyBaker} band, which starts at 25 pc.
 *
 * <p>Apparent magnitude includes dust extinction integrated along the line of sight.
 */
public final class Starfield {
    public static final double FULL_RADIUS_PC = 20.0;
    public static final double LUMINOUS_RADIUS_PC = 250.0;
    /** Faintest star kept: a little below the naked-eye limit, so dark skies look rich. */
    public static final double LIMITING_MAGNITUDE = 7.5;
    public static final int MAX_STARS = 16_000;

    /** One visible star. Direction is a unit vector in Minecraft axes; colour is linear sRGB. */
    public record VisibleStar(Star star, float dx, float dy, float dz, double distancePc, double apparentMag,
                              float r, float g, float b) {}

    private Starfield() {}

    /**
     * Star-generation budgets. Near the Sun they give the radii above; in the bulge, where stars are
     * ~100× denser, the shells shrink so the work stays bounded (a few hundred thousand stars) and
     * the diffuse band takes over closer in, which is also what a real observer there would see.
     */
    static final double FULL_BUDGET = 3_500, LUMINOUS_BUDGET = 650_000;

    public static List<VisibleStar> compute(Galaxy galaxy, double ox, double oy, double oz) {
        List<VisibleStar> out = new ArrayList<>();
        float[] rgb = new float[3];
        for (Star s : StarCatalog.all()) consider(s, ox, oy, oz, out, rgb, Double.MAX_VALUE);

        double n = Math.max(1e-4, dev.galactica.galaxy.GalaxyModel.starDensity(ox, oy, oz));
        double fullR = Math.min(FULL_RADIUS_PC, Math.cbrt(FULL_BUDGET / (n * 4.18879)));
        double lumR = Math.max(fullR, Math.min(LUMINOUS_RADIUS_PC, Math.cbrt(LUMINOUS_BUDGET / (n * 0.097 * 4.18879))));
        SectorKey c = SectorKey.containing(ox, oy, oz);
        int reachFull = (int) Math.ceil(fullR / Galaxy.SECTOR_SIZE_PC);
        int reachLum = (int) Math.ceil(lumR / Galaxy.SECTOR_SIZE_PC);
        double half = Galaxy.SECTOR_SIZE_PC * 0.8660254; // sector half-diagonal
        for (int dx = -reachLum; dx <= reachLum; dx++) {
            for (int dy = -reachLum; dy <= reachLum; dy++) {
                for (int dz = -reachLum; dz <= reachLum; dz++) {
                    SectorKey k = c.offset(dx, dy, dz);
                    double cd = Math.sqrt(sq(k.centerX() - ox) + sq(k.centerY() - oy) + sq(k.centerZ() - oz));
                    if (cd - half > lumR) continue;
                    boolean full = Math.abs(dx) <= reachFull && Math.abs(dy) <= reachFull && Math.abs(dz) <= reachFull;
                    if (full) {
                        int count = galaxy.starCount(k);
                        for (int i = 0; i < count; i++) consider(galaxy.star(k, i), ox, oy, oz, out, rgb, lumR);
                    } else {
                        int count = galaxy.luminousCount(k);
                        for (int j = 0; j < count; j++) consider(galaxy.luminousStar(k, j), ox, oy, oz, out, rgb, lumR);
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.apparentMag, b.apparentMag));
        return out.size() > MAX_STARS ? new ArrayList<>(out.subList(0, MAX_STARS)) : out;
    }

    private static void consider(Star s, double ox, double oy, double oz, List<VisibleStar> out, float[] rgb, double maxPc) {
        if (s.kind() == StarKind.BLACK_HOLE) return;
        double gx = s.x() - ox, gy = s.y() - oy, gz = s.z() - oz;
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (d > maxPc || d < 1e-9) return; // d < 1e-9 pc: we are at the star, it is drawn as a disc elsewhere
        double mag = s.absoluteMagnitude() + 5 * Math.log10(d / 10.0) + extinction(ox, oy, oz, gx / d, gy / d, gz / d, d);
        if (mag > LIMITING_MAGNITUDE) return;
        Blackbody.linearRgb(s.temperature(), rgb);
        // Galactic (x, y, z) → Minecraft (x, z, −y).
        out.add(new VisibleStar(s, (float) (gx / d), (float) (gz / d), (float) (-gy / d), d, mag, rgb[0], rgb[1], rgb[2]));
    }

    /** V-band extinction to distance d (pc), 4-point midpoint integration of the dust model. */
    static double extinction(double ox, double oy, double oz, double ux, double uy, double uz, double d) {
        double av = 0, step = d / 4;
        for (int i = 0; i < 4; i++) {
            double t = (i + 0.5) * step;
            av += GalaxyModel.dustExtinctionPerPc(ox + ux * t, oy + uy * t, oz + uz * t) * step;
        }
        return av;
    }

    private static double sq(double v) {
        return v * v;
    }
}
