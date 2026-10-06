package dev.galactica.sky;

import dev.galactica.deepsky.DeepMath;
import dev.galactica.deepsky.DeepSky;
import dev.galactica.deepsky.DeepSkyView;
import dev.galactica.deepsky.StarCluster;
import dev.galactica.galaxy.Galaxy;
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
 * <p>Apparent magnitude includes dust extinction integrated along the line of sight: the smooth
 * dust model plus the discrete dark clouds and nebular dust of {@link DeepSky}.
 *
 * <p>Members of star clusters ({@link StarCluster}) are added when the cluster is near enough for
 * them to be resolved: each cluster supplies exactly its members brighter than the absolute
 * magnitude limit of {@link DeepSky#resolution} (which includes the extinction to the cluster), and
 * the sky bake's glow of that cluster carries exactly the members fainter than the same limit, so
 * no light is counted twice or lost when both layers are computed at the same point. Members are
 * therefore not re-cut at {@link #LIMITING_MAGNITUDE}: a member on the far side of a cluster (or, from
 * inside one, where the limit is set at the half-mass radius, any member beyond it) may come out
 * fainter than the limit and is still drawn, with the correspondingly dimmer sprite.
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
        DeepSky deepSky = DeepSky.of(galaxy.seed());
        DeepSkyView dust = deepSky.dustView(ox, oy, oz);
        for (Star s : StarCatalog.all()) consider(s, ox, oy, oz, out, rgb, Double.MAX_VALUE, dust);

        // Resolved members of nearby clusters, with the line-of-sight extinction of the cluster centre.
        for (DeepSky.ClusterResolution r : deepSky.resolvedClusters(ox, oy, oz)) {
            double av = r.avMag();
            r.cluster().members(r.limitMv(), s -> considerMember(s, ox, oy, oz, out, rgb, av));
        }

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
                        for (int i = 0; i < count; i++) consider(galaxy.star(k, i), ox, oy, oz, out, rgb, lumR, dust);
                    } else {
                        int count = galaxy.luminousCount(k);
                        for (int j = 0; j < count; j++) consider(galaxy.luminousStar(k, j), ox, oy, oz, out, rgb, lumR, dust);
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.apparentMag, b.apparentMag));
        return out.size() > MAX_STARS ? new ArrayList<>(out.subList(0, MAX_STARS)) : out;
    }

    private static void consider(Star s, double ox, double oy, double oz, List<VisibleStar> out, float[] rgb, double maxPc,
                                 DeepSkyView dust) {
        if (s.kind() == StarKind.BLACK_HOLE) return;
        double gx = s.x() - ox, gy = s.y() - oy, gz = s.z() - oz;
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (d > maxPc || d < 1e-9) return; // d < 1e-9 pc: we are at the star, it is drawn as a disc elsewhere
        double mag = s.absoluteMagnitude() + 5 * Math.log10(d / 10.0) + extinction(ox, oy, oz, gx / d, gy / d, gz / d, d);
        if (mag > LIMITING_MAGNITUDE) return;
        // Dark clouds only ever dim, so they are evaluated for the few stars that pass the cheap test.
        if (dust != null) {
            mag += dust.extinction(gx / d, gy / d, gz / d, d);
            if (mag > LIMITING_MAGNITUDE) return;
        }
        add(s, gx, gy, gz, d, mag, out, rgb);
    }

    /** A cluster member: the cluster's line-of-sight extinction {@code av} is shared by all members. */
    private static void considerMember(Star s, double ox, double oy, double oz, List<VisibleStar> out, float[] rgb, double av) {
        if (s.kind() == StarKind.BLACK_HOLE) return;
        double gx = s.x() - ox, gy = s.y() - oy, gz = s.z() - oz;
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (d < 1e-9) return;
        add(s, gx, gy, gz, d, s.absoluteMagnitude() + 5 * Math.log10(d / 10.0) + av, out, rgb);
    }

    private static void add(Star s, double gx, double gy, double gz, double d, double mag, List<VisibleStar> out, float[] rgb) {
        Blackbody.linearRgb(s.temperature(), rgb);
        // Galactic (x, y, z) → Minecraft (x, z, −y).
        out.add(new VisibleStar(s, (float) (gx / d), (float) (gz / d), (float) (-gy / d), d, mag, rgb[0], rgb[1], rgb[2]));
    }

    /** V-band extinction to distance d (pc) of the smooth dust model ({@link DeepMath#smoothExtinction}). */
    static double extinction(double ox, double oy, double oz, double ux, double uy, double uz, double d) {
        return DeepMath.smoothExtinction(ox, oy, oz, ux, uy, uz, d);
    }

    private static double sq(double v) {
        return v * v;
    }
}
