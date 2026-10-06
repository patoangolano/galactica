package dev.galactica.client.earthsky;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.science.Blackbody;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;
import dev.galactica.sky.Starfield;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The Overworld night sky, computed once from the Sun's position in the procedural galaxy and laid
 * out in vanilla's star-buffer frame ({@link EquatorialFrame}). Pure Java: runs on a worker thread,
 * and in unit tests.
 * <ul>
 *   <li>Milky Way: an equirectangular image whose pixel (u, v) is the star-buffer direction
 *       {@link GalaxySkyBaker#equirectDirection}; each pixel ray-marches the galaxy model from Sol
 *       along the matching galactocentric direction ({@link GalaxySkyBaker#march}), then
 *       {@link SkyTonemap} maps it to 8-bit. Alpha carries the light so the band can be blended over
 *       the vanilla sky colour without darkening it.</li>
 *   <li>Stars: {@link BrightStars} (real, every star to V = 2.5) plus {@link Starfield#compute} from
 *       Sol for everything fainter, down to the naked-eye limit. StarCatalog stars come out of
 *       Starfield at their real l, b; procedural stars brighter than the real catalogue's
 *       completeness limit are dropped so the bright sky is exactly the real one.</li>
 * </ul>
 */
public final class EarthSkyBake {
    private EarthSkyBake() {}

    /** Naked-eye limit under a dark sky (Schaefer 1993, Vistas Astron. 36, 311: ~6.5 for most observers). */
    public static final double LIMITING_MAG = 6.5;
    /** The band is a faint glow to the eye; this caps how opaque its brightest pixel may be. */
    public static final double GALAXY_OPACITY = 0.85;
    /** StarCatalog stars brighter than this are in {@link BrightStars} too; drop the duplicate. */
    static final double CATALOG_DUPLICATE_MAG = 3.0;

    /** A star sprite: unit direction in the star-buffer frame, V magnitude, linear-sRGB colour. */
    public record Sprite(String name, float bx, float by, float bz, double mag, float r, float g, float b) {}

    /** Everything the renderer needs. {@code galaxyArgb} is width × height, row-major, 0xAARRGGBB. */
    public record Baked(int width, int height, int[] galaxyArgb, List<Sprite> stars) {}

    public static Baked bake(int width, int height) {
        return new Baked(width, height, galaxy(width, height), stars(new Galaxy(Frames.GALAXY_SEED)));
    }

    /** The Milky Way as seen from Sol, in star-buffer equirect layout, with light-carrying alpha. */
    public static int[] galaxy(int width, int height) {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        double ox = StarCatalog.SOL.x(), oy = StarCatalog.SOL.y(), oz = StarCatalog.SOL.z();
        float[] hdr = new float[width * height * 3];
        IntStream.range(0, height).parallel().forEach(j -> {
            double[] b = new double[3];
            double[] rgb = new double[3];
            for (int i = 0; i < width; i++) {
                GalaxySkyBaker.equirectDirection((i + 0.5) / width, (j + 0.5) / height, b);
                double[] g = EquatorialFrame.starToGalactocentric(b);
                baker.march(ox, oy, oz, g[0], g[1], g[2], rgb);
                int k = (j * width + i) * 3;
                hdr[k] = (float) rgb[0];
                hdr[k + 1] = (float) rgb[1];
                hdr[k + 2] = (float) rgb[2];
            }
        });
        SkyTonemap tm = SkyTonemap.fit(hdr);
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) argb[i] = lightAlpha(tm.toArgb(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2]));
        return argb;
    }

    /**
     * Opaque colour c → (c / a, a) with a = max channel × {@link #GALAXY_OPACITY}. Under the
     * translucent blend, c/a · a + dst · (1 − a) adds the band's light while black sky stays fully
     * transparent (vanilla's night-blue sky shows through, never a black dome).
     */
    static int lightAlpha(int opaque) {
        int r = (opaque >> 16) & 0xFF, g = (opaque >> 8) & 0xFF, b = opaque & 0xFF;
        int m = Math.max(r, Math.max(g, b));
        if (m == 0) return 0;
        int a = (int) Math.round(m * GALAXY_OPACITY);
        int rr = Math.min(255, Math.round(r * 255f / m)), gg = Math.min(255, Math.round(g * 255f / m)), bb = Math.min(255, Math.round(b * 255f / m));
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** Real bright stars plus the galaxy's own stars as seen from Sol, brightest first. */
    public static List<Sprite> stars(Galaxy galaxy) {
        List<Sprite> out = new ArrayList<>();
        float[] rgb = new float[3];
        for (BrightStars.Entry e : BrightStars.all()) {
            double[] b = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(e.raDeg(), e.decDeg()));
            Blackbody.linearRgb(e.temperature(), rgb);
            out.add(new Sprite(e.name(), (float) b[0], (float) b[1], (float) b[2], e.vmag(), rgb[0], rgb[1], rgb[2]));
        }
        for (Starfield.VisibleStar v : Starfield.compute(galaxy, StarCatalog.SOL.x(), StarCatalog.SOL.y(), StarCatalog.SOL.z())) {
            double m = v.apparentMag();
            if (m > LIMITING_MAG || m < BrightStars.COMPLETE_TO_MAG) continue;
            if (v.star().id() < 0 && m < CATALOG_DUPLICATE_MAG) continue;
            double[] b = EquatorialFrame.spaceToStar(v.dx(), v.dy(), v.dz());
            double n = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            out.add(new Sprite(v.star().name(), (float) (b[0] / n), (float) (b[1] / n), (float) (b[2] / n), m, v.r(), v.g(), v.b()));
        }
        out.sort((x, y) -> Double.compare(x.mag(), y.mag()));
        return out;
    }

    /** Pixel (column, row) of a star-buffer direction in the equirect layout; inverse of equirectDirection. */
    public static int[] pixelOf(double[] b, int width, int height) {
        double lat = Math.asin(Math.max(-1, Math.min(1, b[1])));
        double phi = Math.atan2(b[0], -b[2]);
        double u = phi / (2 * Math.PI);
        u -= Math.floor(u);
        double v = (Math.PI / 2 - lat) / Math.PI;
        return new int[] {Math.min(width - 1, (int) (u * width)), Math.min(height - 1, (int) (v * height))};
    }
}
