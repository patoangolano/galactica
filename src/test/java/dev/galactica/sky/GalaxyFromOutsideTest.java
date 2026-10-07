package dev.galactica.sky;

import dev.galactica.nav.Frames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Milky Way seen from the stellar halo: bounded march, no horizon cut, exposure that does not burn out. */
class GalaxyFromOutsideTest {
    private static final double OX = -45_000, OY = 0, OZ = 3_000;

    @Test
    void boundingChordOfTheGalaxyCylinder() {
        double[] c = new double[2];
        // Straight at the centre along +x: enters at x = −30 kpc, leaves at +30 kpc.
        assertTrue(GalaxySkyBaker.boundingChord(-45_000, 0, 0, 1, 0, 0, c));
        assertEquals(15_000, c[0], 1e-6);
        assertEquals(75_000, c[1], 1e-6);
        // Away from the galaxy: no chord.
        assertFalse(GalaxySkyBaker.boundingChord(-45_000, 0, 0, -1, 0, 0, c));
        // From the Sun the chord starts at the observer.
        assertTrue(GalaxySkyBaker.boundingChord(-8178, 0, 20.8, 0, 0, 1, c));
        assertEquals(0, c[0], 1e-9);
        assertEquals(12_000 - 20.8, c[1], 1e-6);
    }

    /**
     * Seen from z = +3 kpc the galactic plane's horizon crosses the disc. Rays just above it used to
     * end at once ("outside the cylinder and moving away from the plane"), so the disc ended in a
     * hard edge there. Brightness must now vary smoothly across the horizon.
     */
    @Test
    void noCutAtTheHorizonOfThePlane() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        double[] rgb = new double[3];
        double prev = -1;
        // Towards the Galactic Centre, sweeping the elevation through the plane's horizon (dz = 0).
        for (int k = -8; k <= 8; k++) {
            double el = Math.toRadians(-Math.toDegrees(Math.atan2(OZ, -OX)) + k * 0.1);
            baker.march(OX, OY, OZ, Math.cos(el), 0, Math.sin(el), rgb);
            double y = SkyTonemap.luminance(rgb[0], rgb[1], rgb[2]);
            assertTrue(y > 0, "dark at k=" + k);
            if (prev > 0) assertTrue(y / prev < 3 && prev / y < 3, "jump at k=" + k + ": " + prev + " -> " + y);
            prev = y;
        }
        // Above the horizon (dz > 0) the bulge and thick disk are still there.
        baker.march(OX, OY, OZ, Math.cos(0.02), 0, Math.sin(0.02), rgb);
        assertTrue(SkyTonemap.luminance(rgb[0], rgb[1], rgb[2]) > 0.1, "nothing above the plane's horizon");
    }

    /** The galaxy covers ~1 % of the sky from 45 kpc: exposure must follow it, not the black sky. */
    @Test
    void exposureFollowsTheGalaxyNotTheEmptySky() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        int w = 512, h = 256;
        float[] hdr = new float[w * h * 3];
        baker.bakeEquirectRadiance(OX, OY, OZ, w, h, hdr);
        double[] lum = new double[w * h];
        for (int p = 0; p < lum.length; p++) lum[p] = SkyTonemap.luminance(hdr[p * 3], hdr[p * 3 + 1], hdr[p * 3 + 2]);
        java.util.Arrays.sort(lum);
        double p99 = lum[(int) (lum.length * 0.99)];
        double anchor = SkyTonemap.anchor(lum, lum.length);
        assertTrue(anchor > 20 * p99, "anchor " + anchor + " vs sky p99 " + p99);
        // Most of the galaxy's pixels stay below white.
        SkyTonemap tm = SkyTonemap.fit(hdr);
        int lit = 0, white = 0;
        for (int p = 0; p < w * h; p++) {
            int argb = tm.toArgb(hdr[p * 3], hdr[p * 3 + 1], hdr[p * 3 + 2]);
            int g = argb >> 8 & 255;
            if (g > 20) lit++;
            if (g >= 250) white++;
        }
        assertTrue(lit > 200, "galaxy too small: " + lit);
        assertTrue(white < 0.05 * lit, "burned out: " + white + " of " + lit);
    }

    /**
     * From the halo the bright disc must not sit on the highlight shoulder as a flat plateau: at the
     * game's 2048 x 1024 bake (highlight compression and tonemap as in game) under 8 % of the
     * galaxy's lit pixels reach 200 (p95 anchoring gave 17 %), none reach white. Writes the sky to
     * build/galaxy-previews for eyeballing.
     */
    @Test
    void discFromTheHaloIsNotAPlateau() throws Exception {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        int w = 2048, h = 1024;
        float[] hdr = new float[w * h * 3];
        baker.bakeEquirect(OX, OY, OZ, w, h, hdr);
        SkyTonemap tm = SkyTonemap.fit(hdr);
        int lit = 0, bright = 0, white = 0;
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < w * h; p++) {
            int argb = tm.toArgb(hdr[p * 3], hdr[p * 3 + 1], hdr[p * 3 + 2]);
            bi.setRGB(p % w, p / w, argb & 0xFFFFFF);
            int g = argb >> 8 & 255;
            if (g > 20) lit++;
            if (g >= 200) bright++;
            if (g >= 250) white++;
        }
        System.out.printf("from 45 kpc: %d lit, %d >= 200 (%.1f%%), %d white%n", lit, bright, 100.0 * bright / lit, white);
        java.io.File dir = new java.io.File("build/galaxy-previews");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        javax.imageio.ImageIO.write(bi, "png", new java.io.File(dir, "halo_45kpc.png"));
        assertTrue(lit > 2000, "galaxy too small: " + lit);
        assertTrue(bright < 0.08 * lit, "plateau: " + bright + " of " + lit + " at >= 200");
        assertEquals(0, white);
    }

    /** From the Sun the galaxy fills the sky: the anchor is still the sky's own p99 (the view is unchanged). */
    @Test
    void anchorFromTheSunIsTheSkyP99() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        int w = 512, h = 256;
        float[] hdr = new float[w * h * 3];
        baker.bakeEquirectRadiance(-8178, 0, 20.8, w, h, hdr);
        double[] lum = new double[w * h];
        for (int p = 0; p < lum.length; p++) lum[p] = SkyTonemap.luminance(hdr[p * 3], hdr[p * 3 + 1], hdr[p * 3 + 2]);
        java.util.Arrays.sort(lum);
        assertEquals(lum[(int) (lum.length * 0.99)], SkyTonemap.anchor(lum, lum.length), 1e-12);
    }

    /** Inside the disk the anchor is the sky's own p99, as before. */
    @Test
    void anchorFromInsideIsTheSkyP99() {
        double[] lum = new double[1000];
        for (int i = 0; i < lum.length; i++) lum[i] = 1 + i * 0.01;
        assertEquals(lum[990], SkyTonemap.anchor(lum, lum.length), 1e-12);
    }
}
