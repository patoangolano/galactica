package dev.galactica.client.planet;

import dev.galactica.planet.SurfaceGrid;
import dev.galactica.system.Body;
import dev.galactica.system.SolarSystem;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sun-lit globe and clipmap level of an airless body as the near renderer shows them from
 * space, rendered on the CPU: the Moon from 3 040 km (the altitude of {@code /galactica orbit Moon})
 * at several phase angles, written to build/moon-previews for eyeballing, with checks that the
 * terminator follows the star and the craters are lit.
 */
class SpaceLitPreviewTest {
    private static final int W = 640, H = 480;

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        // Material colours come from vanilla block map colours.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private static final double FOV = Math.toRadians(70);

    private static Body body(String name) {
        for (Body p : SolarSystem.planets()) {
            if (p.name().equals(name)) return p;
            for (Body m : p.moons()) if (m.name().equals(name)) return m;
        }
        throw new IllegalArgumentException(name);
    }

    /** Renders the body from altitude {@code altM} at phase angle {@code phaseDeg}; returns linear-ish luma per pixel. */
    private static double[] render(Body b, SurfaceClipmap clip, SurfaceClipmap.SpaceInputs globe, double altM, double phaseDeg,
                                   String file) throws IOException {
        double d = 1 + altM / b.radiusMetres();
        // Eye above (lat 10°, lon 30°), sun turned by the phase angle about the body's z axis.
        double lat0 = Math.toRadians(10), lon0 = Math.toRadians(30);
        double[] e = {Math.cos(lat0) * Math.cos(lon0) * d, Math.cos(lat0) * Math.sin(lon0) * d, Math.sin(lat0) * d};
        double p = Math.toRadians(phaseDeg);
        double[] s = {Math.cos(lat0) * Math.cos(lon0 + p), Math.cos(lat0) * Math.sin(lon0 + p), Math.sin(lat0)};
        SurfaceClipmap.SpaceLight light = new SurfaceClipmap.SpaceLight(s[0], s[1], s[2], e[0], e[1], e[2]);
        int[] litGlobe = SurfaceClipmap.relight(globe, light);
        // The clipmap level the near renderer would draw at this altitude, centred under the eye.
        SurfaceGrid grid = clip.grid();
        double footprint = altM / grid.metresPerBlock() / 885.0;
        int k = 0;
        while (k < SurfaceClipmap.LEVELS - 1 && clip.level(k).spacing < footprint * 0.5) k++;
        int[] litLevel = null;
        double cx = 0, cz = 0, span = 0;
        if (clip.usable(k)) {
            double snap = clip.level(k).spacing * 16;
            cx = grid.wrapX(Math.round(grid.blockX(lon0) / snap) * snap);
            cz = Math.round(grid.blockZ(lat0) / snap) * snap;
            SurfaceClipmap.SpaceInputs lv = clip.bakeLevelNow(k, cx, cz);
            litLevel = SurfaceClipmap.relight(lv, light);
            span = clip.level(k).span();
        }
        // Camera looking at the centre, up = body z.
        double[] f = {-e[0] / d, -e[1] / d, -e[2] / d};
        double[] r = norm(cross(f, new double[] {0, 0, 1}));
        double[] u = cross(r, f);
        double tanH = Math.tan(FOV / 2);
        double[] luma = new double[W * H];
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < H; py++) {
            for (int px = 0; px < W; px++) {
                double sx = ((px + 0.5) / W * 2 - 1) * tanH * W / H, sy = (1 - (py + 0.5) / H * 2) * tanH;
                double[] dir = norm(new double[] {f[0] + sx * r[0] + sy * u[0], f[1] + sx * r[1] + sy * u[1], f[2] + sx * r[2] + sy * u[2]});
                double bq = e[0] * dir[0] + e[1] * dir[1] + e[2] * dir[2];
                double cq = e[0] * e[0] + e[1] * e[1] + e[2] * e[2] - 1;
                double disc = bq * bq - cq;
                if (disc < 0) continue;
                double t = -bq - Math.sqrt(disc);
                double nx = e[0] + t * dir[0], ny = e[1] + t * dir[1], nz = e[2] + t * dir[2];
                double lat = Math.asin(Math.max(-1, Math.min(1, nz))), lon = Math.atan2(ny, nx);
                double[] c = sample(litGlobe, SurfaceClipmap.GLOBE_W, SurfaceClipmap.GLOBE_H,
                        (lon + Math.PI) / (2 * Math.PI) * SurfaceClipmap.GLOBE_W - 0.5, (Math.PI / 2 - lat) / Math.PI * SurfaceClipmap.GLOBE_H - 0.5, true);
                if (litLevel != null) {
                    double mx = grid.blockX(lon), mz = grid.blockZ(lat);
                    double fu = grid.wrapX(mx - cx) / span + 0.5, fv = (mz - cz) / span + 0.5;
                    if (fu > 0 && fu < 1 && fv > 0 && fv < 1) {
                        int n = SurfaceClipmap.N;
                        double[] lc = sample(litLevel, n, n, fu * n - 0.5, fv * n - 0.5, false);
                        double a = lc[3];
                        for (int q = 0; q < 3; q++) c[q] = c[q] * (1 - a) + lc[q] * a;
                    }
                }
                int rgb = (int) c[0] << 16 | (int) c[1] << 8 | (int) c[2];
                img.setRGB(px, py, rgb);
                luma[py * W + px] = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
            }
        }
        File dir = new File("build/moon-previews");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        ImageIO.write(img, "png", new File(dir, file));
        return luma;
    }

    /** Bilinear sample of packed ARGB: {r, g, b, a} with r, g, b in 0..255 and a in 0..1. */
    private static double[] sample(int[] argb, int w, int h, double x, double y, boolean wrap) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double[] o = new double[4];
        for (int k = 0; k < 4; k++) {
            int xi = x0 + (k & 1), yi = y0 + (k >> 1);
            xi = wrap ? Math.floorMod(xi, w) : Math.max(0, Math.min(w - 1, xi));
            yi = Math.max(0, Math.min(h - 1, yi));
            double wt = ((k & 1) == 0 ? 1 - fx : fx) * ((k >> 1) == 0 ? 1 - fy : fy);
            int p = argb[yi * w + xi];
            o[0] += wt * (p >> 16 & 255);
            o[1] += wt * (p >> 8 & 255);
            o[2] += wt * (p & 255);
            o[3] += wt * (p >>> 24) / 255.0;
        }
        return o;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / n, v[1] / n, v[2] / n};
    }

    @Test
    void moonFrom3040KmFollowsTheSunAndShowsCraters() throws IOException {
        Body moon = body("Moon");
        SurfaceClipmap clip = SurfaceClipmap.detached(moon);
        assertTrue(clip.spaceLit());
        long t0 = System.nanoTime();
        SurfaceClipmap.SpaceInputs globe = clip.bakeGlobeNow();
        System.out.printf("Moon globe bake %.2f s%n", (System.nanoTime() - t0) / 1e9);
        assertNotNull(globe);
        render(moon, clip, globe, 3.04e6, 28, "moon_3040km_phase28.png");
        double[] quarter = render(moon, clip, globe, 3.04e6, 90, "moon_3040km_phase90.png");
        render(moon, clip, globe, 3.04e6, 135, "moon_3040km_phase135.png");
        // Quarter phase with the sun to the right (+r): the right half is lit, the left half is night.
        double left = 0, right = 0;
        int nl = 0, nr = 0;
        for (int py = H / 2 - 40; py < H / 2 + 40; py++) {
            for (int px = W / 2 - 150; px < W / 2 - 30; px++) { left += quarter[py * W + px]; nl++; }
            for (int px = W / 2 + 30; px < W / 2 + 150; px++) { right += quarter[py * W + px]; nr++; }
        }
        left /= nl;
        right /= nr;
        System.out.printf("quarter Moon: lit side %.1f, night side %.1f%n", right, left);
        assertTrue(right > 8 * Math.max(1, left), "terminator: lit " + right + " vs night " + left);
        // Near the terminator the relief must break the light up: craters, not a smooth gradient.
        double sum = 0, sum2 = 0;
        int n = 0;
        for (int py = H / 2 - 60; py < H / 2 + 60; py++) {
            for (int px = W / 2 + 5; px < W / 2 + 60; px++) {
                double v = quarter[py * W + px];
                sum += v;
                sum2 += v * v;
                n++;
            }
        }
        double mean = sum / n, sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
        System.out.printf("terminator band: mean %.1f, sd %.1f%n", mean, sd);
        assertTrue(sd > 0.25 * mean, "no relief at the terminator: sd " + sd + " mean " + mean);
    }

    @Test
    void otherAirlessBodies() throws IOException {
        for (String name : new String[] {"Mercury", "Callisto", "Phobos"}) {
            Body b = body(name);
            SurfaceClipmap clip = SurfaceClipmap.detached(b);
            SurfaceClipmap.SpaceInputs g = clip.bakeGlobeNow();
            assertNotNull(g, name);
            // The same apparent size as the Moon from 3 040 km: 2.75 radii from the centre.
            render(b, clip, g, 1.75 * b.radiusMetres(), 70, name.toLowerCase() + "_phase70.png");
        }
    }
}
