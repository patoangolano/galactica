package dev.galactica;

import dev.galactica.science.SeedRng;
import dev.galactica.sky.PlanetImager;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import dev.galactica.system.Orbit;
import dev.galactica.system.SolarSystem;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanetImagerTest {

    private static Body body(BodyType type, boolean rings, double albedo, double pBar, Body.Atmosphere atm,
                             double tSurf, Body.Life life, long seed) {
        Orbit o = new Orbit(1.5e11, 0, 0, 0, 0, 0, 2e30);
        boolean giant = !type.solidSurface;
        double radius = giant ? 10 : 1, mass = giant ? 300 : 1;
        return new Body(seed, type.name(), type, o, mass, radius, albedo, tSurf, tSurf, pBar, atm, 24, 20, false,
                rings, life, SeedRng.hash(seed, 7), List.of());
    }

    private static Body sample(BodyType t) {
        return switch (t) {
            case LAVA -> body(t, false, 0.10, 0, Body.Atmosphere.NONE, 1500, Body.Life.NONE, 1);
            case BARREN -> body(t, false, 0.12, 0, Body.Atmosphere.NONE, 300, Body.Life.NONE, 2);
            case DESERT -> body(t, false, 0.25, 0.006, Body.Atmosphere.TRACE_CO2, 210, Body.Life.NONE, 3);
            case GREENHOUSE -> body(t, false, 0.76, 92, Body.Atmosphere.CO2_THICK, 737, Body.Life.NONE, 4);
            case TERRAN -> body(t, false, 0.30, 1.0, Body.Atmosphere.N2_O2, 288, Body.Life.COMPLEX, 5);
            case OCEAN -> body(t, false, 0.25, 2.0, Body.Atmosphere.N2_O2, 290, Body.Life.MICROBIAL, 6);
            case ICE -> body(t, false, 0.67, 0, Body.Atmosphere.NONE, 100, Body.Life.NONE, 7);
            case SUB_NEPTUNE -> body(t, false, 0.35, 1, Body.Atmosphere.H2_HE, 300, Body.Life.NONE, 8);
            case ICE_GIANT -> body(t, true, 0.30, 1, Body.Atmosphere.H2_HE, 70, Body.Life.NONE, 9);
            case GAS_GIANT -> body(t, true, 0.34, 1, Body.Atmosphere.H2_HE, 130, Body.Life.NONE, 10);
            case HOT_JUPITER -> body(t, false, 0.10, 1, Body.Atmosphere.H2_HE, 1800, Body.Life.NONE, 11);
        };
    }

    /** Looking along +Z with up = +Y; the star sits at phase angle {@code phaseDeg} toward +X. */
    private static PlanetImager.View view(double phaseDeg, double[] spin) {
        PlanetImager.View v = new PlanetImager.View();
        double p = Math.toRadians(phaseDeg);
        v.toStar = new double[]{Math.sin(p), 0, -Math.cos(p)};
        v.spinAxis = spin;
        return v;
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static double luma(int argb) {
        return 0.2126 * (argb >> 16 & 255) + 0.7152 * (argb >> 8 & 255) + 0.0722 * (argb & 255);
    }

    @Test
    void ringlessPlanetIsTransparentAtCornersAndOpaqueAtCentre() {
        int n = 64;
        int[] px = new int[n * n];
        PlanetImager.render(sample(BodyType.TERRAN), view(30, new double[]{0, 1, 0}), n, px);
        assertEquals(0, alpha(px[0]));
        assertEquals(0, alpha(px[n - 1]));
        assertEquals(0, alpha(px[n * n - 1]));
        assertEquals(255, alpha(px[n / 2 * n + n / 2]));
        assertEquals(1.35, PlanetImager.extent(sample(BodyType.TERRAN)), 1e-12);
    }

    @Test
    void phaseFollowsTheStarDirection() {
        // toStar is planet → star and forward is eye → planet: toStar == −forward means the star is
        // behind the viewer (full phase), toStar == +forward means the planet is backlit (new phase).
        int n = 32, c = n / 2 * n + n / 2;
        int[] px = new int[n * n];
        for (BodyType t : new BodyType[]{BodyType.BARREN, BodyType.TERRAN, BodyType.GAS_GIANT}) {
            PlanetImager.View v = view(0, new double[]{0, 1, 0});
            PlanetImager.render(sample(t), v, n, px);
            double full = luma(px[c]);
            v.toStar = new double[]{0, 0, 1};
            PlanetImager.render(sample(t), v, n, px);
            double dark = luma(px[c]);
            assertTrue(full > 25, t + " full phase too dark: " + full);
            assertTrue(dark < 3, t + " backlit centre should be night: " + dark);
            assertEquals(255, alpha(px[c]));
        }
    }

    @Test
    void defaultViewIsRightHanded() {
        PlanetImager.View v = new PlanetImager.View();
        double[] f = v.forward, u = v.up, r = v.right;
        assertArrayEquals(new double[]{f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]},
                r, 1e-12, "right must be forward × up");
    }

    @Test
    void nearVacuumAtmosphereHasNoLimbGlow() {
        // Triton: 14 µbar of N2/CH4 is invisible from orbit, even backlit.
        Body triton = body(BodyType.ICE, false, 0.76, 1.4e-5, Body.Atmosphere.N2_CH4, 38, Body.Life.NONE, 12);
        int n = 128;
        int[] px = new int[n * n];
        PlanetImager.render(triton, view(160, new double[]{0, 1, 0}), n, px);
        double ext = PlanetImager.extent(triton);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                double a = ((x + 0.5) / n * 2 - 1) * ext, b = ((y + 0.5) / n * 2 - 1) * ext;
                if (Math.hypot(a, b) > 1 + 2 * ext / n) assertEquals(0, alpha(px[y * n + x]), "glow at " + x + "," + y);
            }
    }

    @Test
    void lavaGlowsOnTheNightSide() {
        int n = 96;
        int[] px = new int[n * n];
        PlanetImager.View v = view(180, new double[]{0, 1, 0});
        PlanetImager.render(sample(BodyType.LAVA), v, n, px);
        double max = 0;
        for (int p : px) if (alpha(p) == 255) max = Math.max(max, (p >> 16 & 255));
        assertTrue(max > 120, "molten cracks should emit: " + max);
    }

    @Test
    void edgeOnRingsAppearAsALineAcrossTheQuad() {
        Body saturn = sample(BodyType.GAS_GIANT);
        double ext = PlanetImager.extent(saturn);
        assertEquals(PlanetImager.RING_OUTER * 1.05, ext, 1e-12);
        int n = 128;
        int[] px = new int[n * n];
        PlanetImager.render(saturn, view(40, new double[]{0, 1, 0}), n, px); // ring normal == up: edge-on
        int col = (int) Math.floor((2.0 / ext + 1) / 2 * n);
        int best = 0;
        for (int row = n / 2 - 2; row <= n / 2 + 1; row++) best = Math.max(best, alpha(px[row * n + col]));
        assertTrue(best > 0, "edge-on ring at 2.0 R should be visible");
        assertEquals(0, alpha(px[(n / 2 + 20) * n + col]), "nothing above the edge-on ring at 2 R");

        // Tilted 30°: the far half of the ring passes behind the disc, the near half in front of it.
        double t = Math.toRadians(30);
        PlanetImager.render(saturn, view(40, new double[]{0, Math.cos(t), -Math.sin(t)}), n, px);
        int ringPixels = 0;
        for (int p : px) if (alpha(p) > 0 && alpha(p) < 255) ringPixels++;
        assertTrue(ringPixels > n * n / 20, "open rings should cover a band of translucent pixels: " + ringPixels);
    }

    @Test
    void ringShadowFallsOnTheDisc() {
        Body saturn = sample(BodyType.GAS_GIANT);
        int n = 128;
        int[] lit = new int[n * n], shadowed = new int[n * n];
        // Viewer sees the rings' southern face, the star lights the northern face: the near ring
        // crosses the upper disc while its shadow falls, unobstructed, just below it.
        double t = Math.toRadians(25);
        double[] spin = {0, Math.cos(t), Math.sin(t)};
        PlanetImager.View v = view(0, spin);
        Body plain = new Body(saturn.id(), saturn.name(), saturn.type(), saturn.orbit(), saturn.massEarth(),
                saturn.radiusEarth(), saturn.albedo(), saturn.equilibriumTemp(), saturn.surfaceTemp(),
                saturn.surfacePressureBar(), saturn.atmosphere(), saturn.rotationHours(), saturn.axialTiltDeg(),
                saturn.tidallyLocked(), false, saturn.life(), saturn.terrainSeed(), saturn.moons());
        double ln = Math.sqrt(0.4 * 0.4 + 0.6 * 0.6 + 0.69 * 0.69);
        v.toStar = new double[]{0.4 / ln, 0.6 / ln, -0.69 / ln};
        PlanetImager.render(saturn, v, n, shadowed);
        PlanetImager.render(plain, v, n, lit);
        double extR = PlanetImager.extent(saturn), extP = PlanetImager.extent(plain);
        int darker = 0;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                double a = ((x + 0.5) / n * 2 - 1) * extR, b = ((y + 0.5) / n * 2 - 1) * extR;
                if (a * a + b * b > 0.8) continue;
                double z = Math.sqrt(1 - a * a - b * b), u = -(b * spin[1]) / spin[2]; // ring-plane hit along forward
                double rho = Math.sqrt(a * a + b * b + u * u);
                if (u < -z && rho > PlanetImager.RING_INNER && rho < PlanetImager.RING_OUTER) continue; // ring in front
                int xp = (int) ((a / extP + 1) / 2 * n), yp = (int) ((b / extP + 1) / 2 * n);
                if (luma(shadowed[y * n + x]) < 0.6 * luma(lit[yp * n + xp])) darker++;
            }
        assertTrue(darker > 20, "ring shadow should darken part of the disc: " + darker);
    }

    @Test
    void renders256InUnder40Milliseconds() {
        int n = 256;
        int[] px = new int[n * n];
        for (Body b : List.of(sample(BodyType.TERRAN), sample(BodyType.GAS_GIANT), sample(BodyType.BARREN))) {
            PlanetImager.View v = view(60, new double[]{0, 0.9, -0.43589});
            for (int i = 0; i < 6; i++) PlanetImager.render(b, v, n, px); // JIT warm-up
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                PlanetImager.render(b, v, n, px);
                best = Math.min(best, System.nanoTime() - t0);
            }
            double ms = best / 1e6;
            System.out.printf("PlanetImager %s 256²: %.1f ms%n", b.type(), ms);
            assertTrue(ms < 40, b.type() + " took " + ms + " ms");
        }
    }

    /** Dev harness: one PNG per body type plus a contact sheet on black, for eyeballing. */
    @Test
    void writesPreviews() throws IOException {
        File dir = new File("build/planet-previews");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        int n = 256;
        double[] spin = {0, Math.cos(Math.toRadians(20)), -Math.sin(Math.toRadians(20))};
        double[] phases = {35, 100, 160};
        List<Body> bodies = new ArrayList<>();
        for (BodyType t : BodyType.values()) bodies.add(sample(t));
        for (Body b : SolarSystem.planets()) {
            bodies.add(b);
            bodies.addAll(b.moons());
        }
        BufferedImage sheet = new BufferedImage(n * phases.length, n * bodies.size(), BufferedImage.TYPE_INT_RGB);
        int[] px = new int[n * n];
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            for (int k = 0; k < phases.length; k++) {
                PlanetImager.View v = view(phases[k], spin);
                v.rotationRad = 0.7 * k;
                PlanetImager.render(b, v, n, px);
                BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++) {
                        int p = px[y * n + x], a = p >>> 24;
                        img.setRGB(x, n - 1 - y, p); // row 0 is −up: flip for a top-down image
                        int r = (p >> 16 & 255) * a / 255, g = (p >> 8 & 255) * a / 255, bl = (p & 255) * a / 255;
                        sheet.setRGB(k * n + x, i * n + n - 1 - y, r << 16 | g << 8 | bl);
                    }
                if (k == 0) ImageIO.write(img, "png", new File(dir, i + "-" + b.name().replace(' ', '_') + ".png"));
            }
        }
        ImageIO.write(sheet, "png", new File(dir, "sheet.png"));
        assertTrue(new File(dir, "sheet.png").length() > 0);
    }

    /** Cost of one sprite repaint of the Moon (crater relief included), printed for the record. */
    @Test
    void moonSpriteRepaintCost() {
        Body moon = null;
        for (Body p : SolarSystem.planets()) for (Body m : p.moons()) if (m.name().equals("Moon")) moon = m;
        assertNotNull(moon);
        PlanetImager.View v = view(60, new double[]{0, 1, 0});
        for (int n : new int[]{256, 512}) {
            int[] px = new int[n * n];
            PlanetImager.render(moon, v, n, px); // warm-up
            long t0 = System.nanoTime();
            for (int k = 0; k < 3; k++) PlanetImager.render(moon, v, n, px);
            System.out.printf("Moon sprite %d^2: %.1f ms%n", n, (System.nanoTime() - t0) / 3e6);
        }
    }
}
