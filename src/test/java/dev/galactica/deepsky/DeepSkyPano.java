package dev.galactica.deepsky;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;
import dev.galactica.sky.Starfield;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Visual harness (not a unit test): renders what an observer sees — the baked Milky Way with its
 * deep-sky volumes plus the resolved starfield — to PNG, either as an all-sky map in galactic
 * coordinates (l = 0 at the centre, l increasing to the left, as in astronomical atlases) or as a
 * gnomonic close-up. Also times the game's real 2048 × 1024 equirectangular bake on 8 threads.
 *
 * <pre>
 *   java -cp build/classes/java/main;build/classes/java/test dev.galactica.deepsky.DeepSkyPano \
 *        allsky out.png sol 2048
 *   ... zoom out.png sol 1024 &lt;l&gt; &lt;b&gt; &lt;fovDeg&gt;
 *   ... time sol
 * </pre>
 * Observers: {@code sol}, {@code orion} (inside M42, 1.5 pc from the core), {@code omegacen}
 * (40 pc from ω Centauri), {@code pleiades} (inside the cluster), or {@code x,y,z} galactocentric pc.
 */
public final class DeepSkyPano {
    public static void main(String[] a) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String mode = a[0];
        if (mode.equals("time")) {
            double[] o = observer(a[1]);
            time(o);
            return;
        }
        String out = a[1];
        double[] o = observer(a[2]);
        int w = Integer.parseInt(a[3]);
        if (mode.equals("game") || mode.equals("gamefull")) {
            // Exactly what SkyManager uploads (Minecraft-axis equirect), two-scale or full-res march.
            GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
            int h = w / 2;
            float[] hdr = new float[w * h * 3];
            if (mode.equals("game")) {
                baker.bakeEquirect(o[0], o[1], o[2], w, h, hdr);
            } else {
                DeepSkyView view = baker.prepare(o[0], o[1], o[2], 2 * Math.PI / w);
                IntStream.range(0, h).parallel().forEach(j -> {
                    double[] d = new double[3], rgb = new double[3];
                    for (int i = 0; i < w; i++) {
                        GalaxySkyBaker.equirectDirection((i + 0.5) / w, (j + 0.5) / h, d);
                        baker.march(view, o[0], o[1], o[2], d[0], -d[2], d[1], rgb);
                        int k = (j * w + i) * 3;
                        hdr[k] = (float) rgb[0];
                        hdr[k + 1] = (float) rgb[1];
                        hdr[k + 2] = (float) rgb[2];
                    }
                });
            }
            SkyTonemap tm = SkyTonemap.fit(hdr);
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int p = 0; p < w * h; p++) bi.setRGB(p % w, p / w, tm.toArgb(hdr[p * 3], hdr[p * 3 + 1], hdr[p * 3 + 2]) & 0xFFFFFF);
            ImageIO.write(bi, "png", new File(out));
            return;
        }
        if (mode.equals("allsky")) {
            render(out, o, w, w / 2, 0, 0, 0, false);
        } else {
            render(out, o, w, w * 2 / 3, Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6]), true);
        }
    }

    static double[] observer(String s) {
        double[] sun = {StarCatalog.SOL.x(), StarCatalog.SOL.y(), StarCatalog.SOL.z()};
        switch (s) {
            case "sol":
                return sun;
            case "orion": {
                double[] m = DeepSkyCatalog.lb(209.01, -19.38, 410.5);
                return m;
            }
            case "omegacen":
                return DeepSkyCatalog.lb(309.10, 14.97, 5_160);
            case "pleiades":
                return DeepSkyCatalog.lb(166.57, -23.52, 131);
            default: {
                String[] p = s.split(",");
                return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
            }
        }
    }

    static void time(double[] o) throws Exception {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        float[] hdr = new float[2048 * 1024 * 3];
        ForkJoinPool pool = new ForkJoinPool(8);
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            pool.submit(() -> baker.bakeEquirect(o[0], o[1], o[2], 2048, 1024, hdr)).get();
            long t1 = System.nanoTime();
            DeepSkyView v = baker.prepare(o[0], o[1], o[2], 2 * Math.PI / 2048);
            long t2 = System.nanoTime();
            System.out.printf("bake %.2f s (view prep %.0f ms, %d volumes)%n", (t1 - t0) / 1e9, (t2 - t1) / 1e6, v.volumeCount());
        }
        // Baseline: the same bake without deep-sky volumes, and the mean per-ray candidate count.
        long tb = System.nanoTime();
        pool.submit(() -> IntStream.range(0, 1024).parallel().forEach(j -> {
            double[] d = new double[3], rgb = new double[3];
            for (int i = 0; i < 2048; i++) {
                GalaxySkyBaker.equirectDirection((i + 0.5) / 2048, (j + 0.5) / 1024, d);
                baker.march(o[0], o[1], o[2], d[0], -d[2], d[1], rgb);
            }
        })).get();
        System.out.printf("baseline (no deep sky) %.2f s%n", (System.nanoTime() - tb) / 1e9);
        DeepSkyView v = baker.prepare(o[0], o[1], o[2], 2 * Math.PI / 2048);
        long cand = 0;
        double[] d = new double[3];
        for (int j = 0; j < 256; j++) for (int i = 0; i < 512; i++) {
            GalaxySkyBaker.equirectDirection((i + 0.5) / 512, (j + 0.5) / 256, d);
            cand += v.candidates(d[0], -d[2], d[1]);
        }
        System.out.printf("mean candidates per ray %.1f%n", cand / (512.0 * 256));
        java.util.Map<DeepSkyType, int[]> by = new java.util.TreeMap<>();
        for (DeepSkyObject ob : DeepSky.of(Frames.GALAXY_SEED).objectsFor(o[0], o[1], o[2])) {
            int[] c = by.computeIfAbsent(ob.type, k -> new int[2]);
            c[0]++;
            c[1] += ob.blobs().size();
        }
        by.forEach((k, c) -> System.out.printf("  %-18s %5d objects %6d volumes%n", k, c[0], c[1]));
        long t3 = System.nanoTime();
        List<Starfield.VisibleStar> st = Starfield.compute(new Galaxy(Frames.GALAXY_SEED), o[0], o[1], o[2]);
        System.out.printf("starfield %.2f s, %d stars%n", (System.nanoTime() - t3) / 1e9, st.size());
        pool.shutdown();
    }

    /** Heliocentric-oriented galactic (l, b) of a galactocentric direction. */
    static double[] lb(double gx, double gy, double gz) {
        double l = Math.toDegrees(Math.atan2(-gy, -gx));
        if (l < 0) l += 360;
        return new double[] {l, Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, gz))))};
    }

    static void render(String file, double[] o, int w, int h, double l0, double b0, double fov, boolean zoom) throws Exception {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        double pix = zoom ? Math.toRadians(fov) / w : 2 * Math.PI / w;
        long t0 = System.nanoTime();
        DeepSkyView view = baker.prepare(o[0], o[1], o[2], pix);
        float[] buf = new float[w * h * 3];
        // Gnomonic basis around (l0, b0).
        double[] c = DeepMath.direction(l0, b0);
        double[] e = DeepMath.normalize(DeepMath.cross(new double[] {0, 0, 1}, c)); // towards +l
        double[] nn = DeepMath.cross(c, e);
        if (DeepMath.dot(nn, DeepMath.direction(l0, b0 + 1)) < 0) nn = new double[] {-nn[0], -nn[1], -nn[2]};
        final double[] nrt = nn;
        double half = Math.tan(Math.toRadians(fov) / 2);
        IntStream.range(0, h).parallel().forEach(j -> {
            double[] rgb = new double[3];
            for (int i = 0; i < w; i++) {
                double dx, dy, dz;
                if (zoom) {
                    double u = ((i + 0.5) / w * 2 - 1) * half, v = (1 - (j + 0.5) / h * 2) * half * h / w;
                    // l increases to the left.
                    dx = c[0] - u * e[0] + v * nrt[0];
                    dy = c[1] - u * e[1] + v * nrt[1];
                    dz = c[2] - u * e[2] + v * nrt[2];
                    double nrm = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= nrm;
                    dy /= nrm;
                    dz /= nrm;
                } else {
                    double l = Math.toRadians(180 - (i + 0.5) / w * 360), b = Math.PI / 2 - (j + 0.5) / h * Math.PI;
                    double[] d = DeepMath.direction(Math.toDegrees(l), Math.toDegrees(b));
                    dx = d[0];
                    dy = d[1];
                    dz = d[2];
                }
                baker.march(view, o[0], o[1], o[2], dx, dy, dz, rgb);
                int k = (j * w + i) * 3;
                buf[k] = (float) rgb[0];
                buf[k + 1] = (float) rgb[1];
                buf[k + 2] = (float) rgb[2];
            }
        });
        System.out.printf("render %.2f s, %d volumes%n", (System.nanoTime() - t0) / 1e9, view.volumeCount());
        // Exposure: fit on an all-sky bake from the same point (as the game does), so close-ups keep the in-game look.
        float[] ref = new float[512 * 256 * 3];
        baker.bakeEquirect(o[0], o[1], o[2], 512, 256, ref);
        SkyTonemap tm = SkyTonemap.fit(ref);
        double[] img = new double[w * h * 3];
        for (int p = 0; p < w * h; p++) {
            int argb = tm.toArgb(buf[p * 3], buf[p * 3 + 1], buf[p * 3 + 2]);
            img[p * 3] = (argb >> 16 & 255) / 255.0;
            img[p * 3 + 1] = (argb >> 8 & 255) / 255.0;
            img[p * 3 + 2] = (argb & 255) / 255.0;
        }
        List<Starfield.VisibleStar> stars = Starfield.compute(new Galaxy(Frames.GALAXY_SEED), o[0], o[1], o[2]);
        System.out.printf("%d stars, brightest %s%n", stars.size(), stars.isEmpty() ? "-" :
                stars.get(0).star().name() + String.format(" %.2f", stars.get(0).apparentMag()));
        double pxDeg = zoom ? fov / w : 360.0 / w;
        for (Starfield.VisibleStar s : stars) {
            // Minecraft (x, y, z) → galactic (x, −z, y).
            double[] g = lb(s.dx(), -s.dz(), s.dy());
            double px, py;
            if (zoom) {
                double[] d = DeepMath.direction(g[0], g[1]);
                double dc = DeepMath.dot(d, c);
                if (dc <= 0) continue;
                double u = -DeepMath.dot(d, e) / dc, v = DeepMath.dot(d, nrt) / dc;
                px = (u / half + 1) / 2 * w;
                py = (1 - v / (half * h / w)) / 2 * h;
            } else {
                double l = g[0] > 180 ? g[0] - 360 : g[0];
                px = (180 - l) / 360 * w;
                py = (90 - g[1]) / 180 * h;
            }
            double flux = Math.pow(10, -0.4 * (s.apparentMag() - 1.0));
            double sigma = Math.max(0.55, Math.min(4.0, 0.6 + 0.35 * Math.max(0, 4.5 - s.apparentMag()))) * Math.max(1, 0.05 / pxDeg);
            double amp = Math.min(1.6, 0.12 * flux / (sigma * sigma) + 0.05 * Math.pow(10, -0.4 * (s.apparentMag() - 7.5)));
            int r0 = (int) Math.ceil(sigma * 3);
            for (int yy = (int) py - r0; yy <= (int) py + r0; yy++) {
                if (yy < 0 || yy >= h) continue;
                for (int xx = (int) px - r0; xx <= (int) px + r0; xx++) {
                    int xw = zoom ? xx : Math.floorMod(xx, w);
                    if (xw < 0 || xw >= w) continue;
                    double q = ((xx + 0.5 - px) * (xx + 0.5 - px) + (yy + 0.5 - py) * (yy + 0.5 - py)) / (2 * sigma * sigma);
                    double k = amp * Math.exp(-q);
                    int p = (yy * w + xw) * 3;
                    img[p] += k * Math.pow(s.r(), 0.45);
                    img[p + 1] += k * Math.pow(s.g(), 0.45);
                    img[p + 2] += k * Math.pow(s.b(), 0.45);
                }
            }
        }
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < w * h; p++) {
            int r = (int) Math.round(255 * Math.min(1, img[p * 3]));
            int g = (int) Math.round(255 * Math.min(1, img[p * 3 + 1]));
            int b = (int) Math.round(255 * Math.min(1, img[p * 3 + 2]));
            bi.setRGB(p % w, p / w, (r << 16) | (g << 8) | b);
        }
        ImageIO.write(bi, "png", new File(file));
    }
}
