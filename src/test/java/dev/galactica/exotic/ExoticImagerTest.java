package dev.galactica.exotic;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ExoticImagerTest {

    private static final int N = 256;

    private static Star bhStar(double mass) {
        return new Star(42, "test BH", 100, 0, 0, StarKind.BLACK_HOLE, mass, 0, 0, 0, 0.1, 0, 1234L, false);
    }

    private static ExoticObject hole(ExoticObject.Accretion acc, double peakK) {
        double rs = ExoticObject.schwarzschildRadius(10);
        return new ExoticObject(bhStar(10), StarKind.BLACK_HOLE, acc, rs, 3, 40, peakK, new double[] {0, 1, 0}, 0, rs, null);
    }

    /** Camera looking down −z at a hole {@code distRs} Rs away, sprite reaching {@code extentRs}. */
    private static ExoticImager.View view(ExoticObject o, double distRs, double extentRs, double inclDeg) {
        ExoticImager.View v = new ExoticImager.View();
        v.forward = new double[] {0, 0, -1};
        v.right = new double[] {1, 0, 0};
        v.up = new double[] {0, 1, 0};
        double i = Math.toRadians(inclDeg);
        v.axis = new double[] {0, Math.sin(i), Math.cos(i)};
        v.distanceMetres = distRs * o.rsMetres();
        v.halfAngle = Math.atan(extentRs / distRs);
        v.pxPerRad = 885;
        return v;
    }

    private static final ExoticImager.SkySampler WHITE = (x, y, z, rgb) -> {
        rgb[0] = 0.5f;
        rgb[1] = 0.5f;
        rgb[2] = 0.5f;
    };

    /** Linear-light luminance (alpha-weighted), decoding the sRGB channels. */
    private static double luminance(int argb) {
        double a = (argb >>> 24) / 255.0;
        return a * (0.2126 * lin((argb >> 16) & 0xFF) + 0.7152 * lin((argb >> 8) & 0xFF) + 0.0722 * lin(argb & 0xFF));
    }

    private static double lin(int c) {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    @Test
    void criticalImpactParameterIsRootTwentySevenOverTwo() {
        assertEquals(2.598076, ExoticImager.SHADOW_RS, 1e-6);
        assertTrue(Double.isInfinite(ExoticImager.deflection(2.5)));
        // Weak-field limit: 4GM/(c²b) = 2 Rs / b.
        assertEquals(2.0 / 1e4, ExoticImager.deflection(1e4), 1e-7);
        assertTrue(ExoticImager.deflection(2.7) > ExoticImager.deflection(4.0));
    }

    @Test
    void shadowHasTheCriticalRadius() {
        ExoticObject o = hole(ExoticObject.Accretion.NONE, 0);
        double dist = 1e4, ext = 8;
        ExoticImager.View v = view(o, dist, ext, 60);
        int[] img = new int[N * N];
        ExoticImager.render(o, v, N, img, WHITE);
        int row = N / 2;
        // Walk right from the centre to the first lit pixel: that is the shadow's edge.
        int edge = -1;
        for (int px = N / 2; px < N; px++) {
            int c = img[row * N + px];
            if ((c & 0xFFFFFF) != 0) {
                edge = px;
                break;
            }
            assertEquals(0xFF, c >>> 24, "shadow must be opaque black");
        }
        assertTrue(edge > N / 2);
        double tanH = Math.tan(v.halfAngle);
        double aIn = ((edge - 1 + 0.5) / N * 2 - 1) * tanH, aOut = ((edge + 0.5) / N * 2 - 1) * tanH;
        double bIn = Math.sin(Math.atan(aIn)) * dist, bOut = Math.sin(Math.atan(aOut)) * dist;
        assertTrue(bIn < ExoticImager.SHADOW_RS && ExoticImager.SHADOW_RS <= bOut + 1e-9,
                "shadow edge between " + bIn + " and " + bOut + " Rs");
    }

    @Test
    void approachingSideOfTheDiskIsBrighter() {
        ExoticObject o = hole(ExoticObject.Accretion.THIN_DISK, 1e7);
        // Axis (0, sin i, cos i): the disk velocity J × p points towards the observer (+z) where p_x < 0,
        // i.e. on the left half of the image.
        double[] lr = halves(o, view(o, 1e6, 44, 60));
        assertTrue(lr[0] > 1.3 * lr[1], "left (approaching) " + lr[0] + " vs right " + lr[1]);
        // Reversing the spin swaps the bright side.
        ExoticImager.View flipped = view(o, 1e6, 44, 60);
        for (int k = 0; k < 3; k++) flipped.axis[k] = -flipped.axis[k];
        double[] rl = halves(o, flipped);
        assertTrue(rl[1] > 1.3 * rl[0], "after flip left " + rl[0] + " vs right " + rl[1]);
    }

    @Test
    void dopplerFactorSign() {
        // Photon angular momentum along the orbital motion → blueshift; against → redshift.
        assertTrue(ExoticImager.redshiftFactor(6, 3) > 1);
        assertTrue(ExoticImager.redshiftFactor(6, -3) < ExoticImager.redshiftFactor(6, 0));
        // Pure gravitational + transverse term at Lz = 0: √(1 − 3M/r) = √(1 − 1.5/r).
        assertEquals(Math.sqrt(0.75), ExoticImager.redshiftFactor(6, 0), 1e-12);
    }

    @Test
    void shakuraSunyaevProfile() {
        double peak = 1e7, rIn = 3;
        assertEquals(0, ExoticImager.diskTemperature(3, rIn, peak));
        assertEquals(peak, ExoticImager.diskTemperature(rIn * 49 / 36, rIn, peak), 1e-6 * peak);
        // Far out T ∝ r^(−3/4).
        double t1 = ExoticImager.diskTemperature(3000, rIn, peak), t2 = ExoticImager.diskTemperature(48000, rIn, peak);
        assertEquals(Math.pow(16, -0.75), t2 / t1, 0.01);
    }

    @Test
    void beloborodovRadiusReducesToGeometryFarAway() {
        // Face-on (ψ = 90°, c = 0): r² + Rs r − b² = 0, so r → b for b ≫ Rs.
        assertEquals(1000, ExoticImager.diskRadius(1000, 0), 1.0);
        double r = ExoticImager.diskRadius(10, 0);
        assertEquals(10 * 10, r * r + r, 1e-9);
    }

    @Test
    void deterministic() {
        Star cyg = StarCatalog.byName("Cygnus X-1");
        ExoticObject a = ExoticObject.of(cyg), b = ExoticObject.of(cyg);
        assertArrayEquals(a.axis(), b.axis());
        ExoticImager.View v = view(a, 1e6, 70, 30);
        v.axis = new double[] {0.3, 0.8, 0.52};
        int[] i1 = new int[N * N], i2 = new int[N * N];
        ExoticImager.render(a, v, N, i1, WHITE);
        ExoticImager.render(b, v, N, i2, WHITE);
        assertArrayEquals(i1, i2);
        Star procedural = new Star(77, "BH", 10, 20, 0, StarKind.BLACK_HOLE, 8, 0, 0, 0, 1, 0, 99L, false);
        assertEquals(ExoticObject.of(procedural).accretion(), ExoticObject.of(procedural).accretion());
        assertArrayEquals(ExoticObject.of(procedural).axis(), ExoticObject.of(procedural).axis());
    }

    @Test
    void namedObjectsUseTheirPublishedFlows() {
        assertEquals(ExoticObject.Accretion.RIAF, ExoticObject.of(StarCatalog.SGR_A_STAR).accretion());
        ExoticObject cyg = ExoticObject.of(StarCatalog.byName("Cygnus X-1"));
        assertEquals(ExoticObject.Accretion.THIN_DISK, cyg.accretion());
        // Rs = 2953 m per solar mass.
        assertEquals(2953.25 * 21.2, cyg.rsMetres(), 2.0);
        assertNull(ExoticObject.of(StarCatalog.SOL));
    }

    @Test
    void lensingRingBrightensTheBackground() {
        ExoticObject o = hole(ExoticObject.Accretion.NONE, 0);
        double dist = 1e6;
        double thetaE = ExoticImager.einsteinAngle(o.rsMetres(), dist * o.rsMetres());
        ExoticImager.View v = view(o, dist, 0, 0);
        v.halfAngle = 3 * thetaE;
        v.pxPerRad = 1e7; // every pixel fully lensed: compare the ring boost alone
        int[] img = new int[N * N];
        ExoticImager.render(o, v, N, img, WHITE);
        double tanH = Math.tan(v.halfAngle);
        int ringPx = (int) Math.round((Math.tan(thetaE) / tanH + 1) / 2 * N - 0.5);
        int outPx = (int) Math.round((Math.tan(2.5 * thetaE) / tanH + 1) / 2 * N - 0.5);
        int row = N / 2;
        assertTrue(luminance(img[row * N + ringPx]) > luminance(img[row * N + outPx]),
                "Einstein ring brighter than the sky further out");
    }

    @Test
    void renderUnderThirtyMillisecondsAt256() {
        ExoticObject o = ExoticObject.of(StarCatalog.byName("Cygnus X-1"));
        ExoticImager.View v = view(o, 1e5, 66, 30);
        int[] img = new int[N * N];
        for (int k = 0; k < 5; k++) ExoticImager.render(o, v, N, img, WHITE); // JIT warm-up
        long best = Long.MAX_VALUE;
        for (int k = 0; k < 5; k++) {
            long t0 = System.nanoTime();
            ExoticImager.render(o, v, N, img, WHITE);
            best = Math.min(best, System.nanoTime() - t0);
        }
        assertTrue(best < 30_000_000L, "black-hole sprite took " + best / 1e6 + " ms");

        Star ns = new Star(5, "PSR", 1, 2, 3, StarKind.NEUTRON_STAR, 1.4, 1.7e-5, 1e-4, 8e5, 1, 0, 777L, false);
        ExoticObject p = ExoticObject.of(ns);
        ExoticImager.View pv = view(p, 1, 1, 70);
        pv.halfAngle = 0.02;
        pv.timeAveraged = true;
        int[] small = new int[128 * 128];
        for (int k = 0; k < 3; k++) ExoticImager.render(p, pv, 128, small, null);
        long t0 = System.nanoTime();
        ExoticImager.render(p, pv, 128, small, null);
        assertTrue(System.nanoTime() - t0 < 30_000_000L);
    }

    @Test
    void lensIsTransparentOutsideItsBand() {
        ExoticObject o = hole(ExoticObject.Accretion.NONE, 0);
        double dist = 1e6;
        double rsOverD = 1 / dist;
        double thetaE = ExoticImager.einsteinAngle(o.rsMetres(), dist * o.rsMetres());
        // Close in, the 3 θE band is the limit; far out, the one-pixel deflection radius is.
        assertEquals(3 * thetaE, ExoticImager.lensExtent(rsOverD, 1e7), 1e-15);
        assertEquals(2 * rsOverD * 885, ExoticImager.lensExtent(rsOverD, 885), 1e-15);

        ExoticImager.View v = view(o, dist, 0, 0);
        v.halfAngle = 3 * thetaE;
        v.pxPerRad = 1e7;
        v.lensOuter = 1.5 * thetaE;
        int[] img = new int[N * N];
        ExoticImager.render(o, v, N, img, WHITE);
        double tanH = Math.tan(v.halfAngle);
        int row = N / 2;
        int inPx = (int) Math.round((Math.tan(thetaE) / tanH + 1) / 2 * N - 0.5);
        int outPx = (int) Math.round((Math.tan(2.5 * thetaE) / tanH + 1) / 2 * N - 0.5);
        assertTrue((img[row * N + inPx] >>> 24) > 0, "lensed sky inside the band is painted");
        assertEquals(0, img[row * N + outPx] >>> 24, "outside the band the star layer shows through");

        // Where the deflection is under one pixel nothing is painted either.
        v.lensOuter = Double.POSITIVE_INFINITY;
        v.pxPerRad = 0.5 / ExoticImager.deflection(2.5 * thetaE / rsOverD);
        ExoticImager.render(o, v, N, img, WHITE);
        assertEquals(0, img[row * N + outPx] >>> 24);
    }

    @Test
    void pulsarWithoutSteadyCoreOnlyAddsTheBeams() {
        Star ns = new Star(5, "PSR", 1, 2, 3, StarKind.NEUTRON_STAR, 1.4, 1.7e-5, 1e-4, 8e5, 1, 0, 777L, false);
        ExoticObject p = ExoticObject.of(ns);
        ExoticImager.View v = view(p, 1, 1, 70);
        v.halfAngle = 0.02;
        v.timeAveraged = true;
        int[] with = new int[64 * 64], without = new int[64 * 64];
        ExoticImager.render(p, v, 64, with, null);
        v.steadyCore = false;
        ExoticImager.render(p, v, 64, without, null);
        int c = 32 * 64 + 32;
        assertTrue(luminance(with[c]) > luminance(without[c]), "the steady point is left to the star layer");
    }

    @Test
    void writesPreviewSheet() throws IOException {
        File dir = new File("build/exotic-previews");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        ExoticImager.SkySampler stripes = (x, y, z, rgb) -> {
            double s = 0.5 + 0.5 * Math.sin(40 * Math.atan2(y, x)) * Math.sin(40 * x);
            rgb[0] = (float) (0.05 + 0.25 * s);
            rgb[1] = (float) (0.05 + 0.2 * s);
            rgb[2] = (float) (0.08 + 0.3 * s);
        };
        Star psr = new Star(9, "PSR", 1, 2, 3, StarKind.NEUTRON_STAR, 1.4, 1.7e-5, 1e-4, 8e5, 1, 0, 31L, false);
        Star wd = new Star(10, "WD", 1, 2, 3, StarKind.WHITE_DWARF, 0.6, 0.012, 0.01, 25000, 1, 0, 5L, false);
        ExoticObject[] objs = {ExoticObject.of(StarCatalog.byName("Cygnus X-1")), ExoticObject.of(StarCatalog.SGR_A_STAR),
                hole(ExoticObject.Accretion.NONE, 0), ExoticObject.of(psr), ExoticObject.of(wd)};
        BufferedImage sheet = new BufferedImage(N * objs.length, N, BufferedImage.TYPE_INT_ARGB);
        int[] img = new int[N * N];
        for (int k = 0; k < objs.length; k++) {
            ExoticObject o = objs[k];
            ExoticImager.View v;
            if (o.kind() == StarKind.BLACK_HOLE) {
                v = view(o, 1e5, ExoticImager.coreExtentRs(o), 80);
            } else {
                v = view(o, 1, 1, 70);
                v.halfAngle = 0.02;
            }
            Arrays.fill(img, 0);
            ExoticImager.render(o, v, N, img, o.kind() == StarKind.BLACK_HOLE ? stripes : null);
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) sheet.setRGB(k * N + x, N - 1 - y, img[y * N + x]);
        }
        ImageIO.write(sheet, "png", new File(dir, "sheet.png"));
        assertTrue(new File(dir, "sheet.png").length() > 0);
    }

    private static double[] halves(ExoticObject o, ExoticImager.View v) {
        int[] img = new int[N * N];
        ExoticImager.render(o, v, N, img, null);
        double left = 0, right = 0;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                double l = luminance(img[y * N + x]);
                if (x < N / 2) left += l;
                else right += l;
            }
        }
        return new double[] {left, right};
    }
}
