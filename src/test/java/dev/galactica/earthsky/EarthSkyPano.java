package dev.galactica.earthsky;

import dev.galactica.client.earthsky.EarthSkyBake;
import dev.galactica.client.earthsky.EquatorialFrame;
import dev.galactica.client.earthsky.NightSkyModel;
import dev.galactica.client.earthsky.StarAtlas;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.Frames;
import dev.galactica.science.Blackbody;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

/**
 * Visual harness (not a unit test): the Overworld night sky as the player sees it, composited the
 * way the renderer blends its layers (black night sky, the Milky Way texture with light-carrying
 * alpha, the stars, vanilla's Moon as a grey disc, then the atmosphere veil), as a fisheye of the
 * whole sky (zenith at the centre, horizon on the rim, north up, east left as seen looking up) or
 * a view towards one azimuth. Blending is done on gamma-encoded values, as the GPU blends
 * into the (non-sRGB) framebuffer.
 *
 * <pre>
 *   java -cp ... dev.galactica.earthsky.EarthSkyPano out.png &lt;skyAngleDeg&gt; &lt;moonAngleDeg|none&gt; &lt;moonPhase 0-7&gt; [fisheye|az,alt,fov]
 * </pre>
 * skyAngle is vanilla's star angle plus the seasonal turn (θ + φ, degrees); moonAngle is vanilla's
 * moon angle (180 = midnight zenith).
 */
public final class EarthSkyPano {
    public static void main(String[] a) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (a[0].equals("profile")) {
            // Radiance across the band (b = 0…80° at l = 90° and 180°) from Sol, galaxy model only.
            dev.galactica.sky.GalaxySkyBaker baker = new dev.galactica.sky.GalaxySkyBaker(Frames.GALAXY_SEED);
            double[] rgb = new double[3];
            for (double l : new double[] {30, 90, 180}) {
                StringBuilder sb = new StringBuilder("l=" + l + ":");
                for (int b = 0; b <= 80; b += 5) {
                    double[] d = dev.galactica.deepsky.DeepMath.direction(l, b);
                    baker.march(dev.galactica.galaxy.StarCatalog.SOL.x(), dev.galactica.galaxy.StarCatalog.SOL.y(),
                            dev.galactica.galaxy.StarCatalog.SOL.z(), d[0], d[1], d[2], rgb);
                    sb.append(String.format(" %d:%.2f", b, 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]));
                }
                System.out.println(sb);
            }
            return;
        }
        String out = a[0];
        double sky = Math.toRadians(Double.parseDouble(a[1]));
        double[] moon = a[2].equals("none") ? null : NightSkyModel.moonDirection(Math.toRadians(Double.parseDouble(a[2])));
        double phase = NightSkyModel.phaseAngle(Integer.parseInt(a[3]));
        String view = a.length > 4 ? a[4] : "fisheye";
        int w = 2048, h = 1024;
        long t0 = System.nanoTime();
        int[] mw = EarthSkyBake.galaxy(w, h, new ForkJoinPool(8));
        System.out.printf("milky way bake %.2f s%n", (System.nanoTime() - t0) / 1e9);
        BufferedImage tex = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < w * h; p++) {
            int c = mw[p], al = c >>> 24;
            tex.setRGB(p % w, p / w, ((c >> 16 & 255) * al / 255 << 16) | ((c >> 8 & 255) * al / 255 << 8) | ((c & 255) * al / 255));
        }
        ImageIO.write(tex, "png", new File(out.replace(".png", "_texture.png")));
        List<EarthSkyBake.Sprite> stars = EarthSkyBake.stars(new Galaxy(Frames.GALAXY_SEED));
        int size = 1000;
        boolean fish = view.equals("fisheye");
        double az0 = 0, alt0 = 0, fov = 0;
        if (!fish) {
            String[] p = view.split(",");
            az0 = Math.toRadians(Double.parseDouble(p[0]));
            alt0 = Math.toRadians(Double.parseDouble(p[1]));
            fov = Math.toRadians(Double.parseDouble(p[2]));
        }
        int iw = fish ? size : 1600, ih = fish ? size : 900;
        double[] img = new double[iw * ih * 3];
        double[][] dirs = new double[iw * ih][];
        // Camera basis for the perspective view: forward at (azimuth from north towards east, altitude).
        double[] fwd = {Math.sin(az0) * Math.cos(alt0), Math.sin(alt0), -Math.cos(az0) * Math.cos(alt0)};
        double[] right = norm(cross(fwd, new double[] {0, 1, 0}));
        double[] up = cross(right, fwd);
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                double[] d;
                if (fish) {
                    double u = (x + 0.5) / iw * 2 - 1, v = (y + 0.5) / ih * 2 - 1;
                    double r = Math.sqrt(u * u + v * v);
                    if (r > 1) continue;
                    double zen = r * Math.PI / 2, azm = Math.atan2(-u, -v); // north up, east left
                    d = new double[] {Math.sin(zen) * Math.sin(azm), Math.cos(zen), -Math.sin(zen) * Math.cos(azm)};
                } else {
                    double t = Math.tan(fov / 2);
                    double u = ((x + 0.5) / iw * 2 - 1) * t, v = (1 - (y + 0.5) / ih * 2) * t * ih / iw;
                    d = norm(new double[] {fwd[0] + u * right[0] + v * up[0], fwd[1] + u * right[1] + v * up[1], fwd[2] + u * right[2] + v * up[2]});
                }
                dirs[y * iw + x] = d;
                // Milky Way: world → star buffer, sample bilinear, blend with its light alpha.
                double[] b = worldToStar(d, sky);
                double phi = Math.atan2(b[0], -b[2]) / (2 * Math.PI);
                if (phi < 0) phi += 1;
                double vv = Math.acos(Math.max(-1, Math.min(1, b[1]))) / Math.PI;
                double fx = phi * w - 0.5, fy = vv * h - 0.5;
                int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
                double tx = fx - x0, ty = fy - y0, ca = 0, cr = 0, cg = 0, cb = 0;
                for (int k = 0; k < 4; k++) {
                    int xi = Math.floorMod(x0 + (k & 1), w), yi = Math.max(0, Math.min(h - 1, y0 + (k >> 1)));
                    double wt = ((k & 1) == 0 ? 1 - tx : tx) * ((k >> 1) == 0 ? 1 - ty : ty);
                    int p = mw[yi * w + xi];
                    double al = (p >>> 24) / 255.0;
                    ca += wt * al;
                    cr += wt * al * (p >> 16 & 255) / 255.0;
                    cg += wt * al * (p >> 8 & 255) / 255.0;
                    cb += wt * al * (p & 255) / 255.0;
                }
                int o = (y * iw + x) * 3;
                img[o] = cr;
                img[o + 1] = cg;
                img[o + 2] = cb;
                // Moon: a flat grey disc of vanilla's 20/100 size.
                if (moon != null && phase < 179 && dot(d, moon) > Math.cos(Math.atan(0.1))) {
                    double lit = 0.8 * (1 - phase / 180);
                    img[o] = img[o + 1] = img[o + 2] = lit;
                }
            }
        }
        // Stars: atlas opacity per magnitude, drawn as small Gaussians.
        for (EarthSkyBake.Sprite s : stars) {
            double[] d = EquatorialFrame.starToWorld(new double[] {s.bx(), s.by(), s.bz()}, sky);
            double px, py;
            if (fish) {
                if (d[1] < 0) continue;
                double zen = Math.acos(d[1]), azm = Math.atan2(d[0], -d[2]);
                double r = zen / (Math.PI / 2);
                px = (-Math.sin(azm) * r + 1) / 2 * iw;
                py = (-Math.cos(azm) * r + 1) / 2 * ih;
            } else {
                double f = dot(d, fwd);
                if (f <= 0) continue;
                double t = Math.tan(fov / 2);
                px = (dot(d, right) / f / t + 1) / 2 * iw;
                py = (1 - dot(d, up) / f / (t * ih / iw)) / 2 * ih;
            }
            double alpha = StarAtlas.spriteAlpha(s.mag());
            double sig = 0.7 * StarAtlas.spriteSize(s.mag()) / StarAtlas.STAR_SPRITE;
            int r0 = (int) Math.ceil(3 * sig);
            for (int yy = (int) py - r0; yy <= (int) py + r0; yy++) {
                for (int xx = (int) px - r0; xx <= (int) px + r0; xx++) {
                    if (xx < 0 || yy < 0 || xx >= iw || yy >= ih) continue;
                    double q = ((xx + 0.5 - px) * (xx + 0.5 - px) + (yy + 0.5 - py) * (yy + 0.5 - py)) / (2 * sig * sig);
                    double k = alpha * Math.exp(-q);
                    int o = (yy * iw + xx) * 3;
                    double mx = Math.max(s.r(), Math.max(s.g(), s.b()));
                    img[o] = img[o] * (1 - k) + k * Blackbody.encode(s.r() / mx);
                    img[o + 1] = img[o + 1] * (1 - k) + k * Blackbody.encode(s.g() / mx);
                    img[o + 2] = img[o + 2] * (1 - k) + k * Blackbody.encode(s.b() / mx);
                }
            }
        }
        // Atmosphere veil over everything.
        float[] t = new float[4];
        for (int p = 0; p < iw * ih; p++) {
            if (dirs[p] == null) continue;
            NightSkyModel.veil(dirs[p], moon, phase, t);
            for (int k = 0; k < 3; k++) img[p * 3 + k] = t[k] * t[3] + img[p * 3 + k] * (1 - t[3]);
        }
        BufferedImage bi = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < iw * ih; p++) {
            int r = (int) Math.round(255 * Math.min(1, img[p * 3]));
            int g = (int) Math.round(255 * Math.min(1, img[p * 3 + 1]));
            int b = (int) Math.round(255 * Math.min(1, img[p * 3 + 2]));
            bi.setRGB(p % iw, p / iw, (r << 16) | (g << 8) | b);
        }
        ImageIO.write(bi, "png", new File(out));
    }

    /** Inverse of {@link EquatorialFrame#starToWorld}: undo R_y(−90°), then R_x(−θ). */
    static double[] worldToStar(double[] wd, double theta) {
        double x = wd[2], y = wd[1], z = -wd[0];
        double c = Math.cos(theta), s = Math.sin(theta);
        return new double[] {x, y * c + z * s, -y * s + z * c};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] a) {
        double n = Math.sqrt(dot(a, a));
        return new double[] {a[0] / n, a[1] / n, a[2] / n};
    }
}
