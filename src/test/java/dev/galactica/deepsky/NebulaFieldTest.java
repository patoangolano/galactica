package dev.galactica.deepsky;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.science.SeedRng;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The continuous nebula volumes: noise, normalisation, ray integration, scattering, close-ups. */
class NebulaFieldTest {
    private static final double[] SUN = {StarCatalog.SOL.x(), StarCatalog.SOL.y(), StarCatalog.SOL.z()};

    @Test
    void noiseIsBoundedAndLevelOfDetailKeepsTheMean() {
        NebulaNoise n = new NebulaNoise(42);
        SeedRng r = new SeedRng(7);
        double sumFine = 0, sumCoarse = 0, sumFbm = 0;
        int k = 20_000;
        for (int i = 0; i < k; i++) {
            double x = r.range(-50, 50), y = r.range(-50, 50), z = r.range(-50, 50);
            double v = n.noise(x, y, z);
            assertTrue(Math.abs(v) <= 1.0, "noise " + v);
            double fine = n.ridged(x, y, z, 8), coarse = n.ridged(x, y, z, 1.5);
            assertTrue(fine >= 0 && fine <= 1 && coarse >= 0 && coarse <= 1);
            sumFine += fine;
            sumCoarse += coarse;
            sumFbm += n.fbm(x, y, z, 6);
        }
        // Dropped octaves are replaced by their mean: the far (coarse) view holds the same dust.
        assertEquals(sumFine / k, sumCoarse / k, 0.04 * sumFine / k);
        assertEquals(0, sumFbm / k, 0.03);
        assertEquals(3.0, NebulaNoise.octavesFor(1.0, 0.0, 3.0));
        assertTrue(NebulaNoise.octavesFor(1.0, 0.1, 9) > NebulaNoise.octavesFor(1.0, 0.2, 9));
    }

    private static HiiField thinRegion(double oiii) {
        HiiField.Spec s = new HiiField.Spec();
        s.oiii = oiii;
        s.neutralAv = 0;
        s.lanes = 0;
        return new HiiField(11, Morphology.Frame.random(new double[] {0, 0, 0}, new SeedRng(3), 2, 1.5, 1.2), s, 1_000);
    }

    @Test
    void hiiRegionEmitsItsCatalogueLuminance() {
        HiiField f = thinRegion(0);
        // Hα only: the red channel (H_ALPHA r = 1) integrates to the line luminosity.
        double[] o = new double[4];
        SeedRng r = new SeedRng(5);
        double sum = 0, box = f.radius;
        int n = 400_000;
        for (int i = 0; i < n; i++) {
            double x = r.range(-box, box), y = r.range(-box, box), z = r.range(-box, box);
            f.sample(x, y, z, 0.05, o);
            sum += o[0];
        }
        double lum = sum * Math.pow(2 * box, 3) / n;
        assertEquals(1_000, lum, 120, "integrated L = " + lum);
    }

    @Test
    void marchMatchesDirectQuadratureForAThinField() {
        HiiField f = thinRegion(0.5);
        double[] o = {-30, 0.3, 0.2};
        double[] d = {1, 0, 0};
        DeepSkyView.RayEvents ev = new DeepSkyView.RayEvents();
        f.march(o[0], o[1], o[2], d[0], d[1], d[2], 1e-4, 0.5, 1.0, ev);
        double marched = 0;
        for (int i = 0; i < ev.count; i++) marched += ev.r[i] * Math.exp(-0.5 * 0.921 * 0.8 * ev.av[i]);
        double direct = 0, ds = 0.002;
        double[] s = new double[4];
        for (double t = 0; t < 60; t += ds) {
            f.sample(o[0] + t, o[1], o[2], 0.01, s);
            direct += s[0] * ds / (4 * Math.PI);
        }
        assertTrue(direct > 0);
        assertEquals(direct, marched, 0.12 * direct, "march " + marched + " vs quadrature " + direct);
    }

    @Test
    void mergedEventsCompositeLikeTheOriginals() {
        // Two events A (in front) and B: composite separately, then merged with CoarseFieldGrid.merge.
        double eA = 2.0, aA = 1.5, eB = 3.0, aB = 0.7;
        double k = 0.4 * Math.log(10) * NebulaField.KG;
        double separate = eA * Math.exp(-0.5 * k * aA) + eB * Math.exp(-k * aA) * Math.exp(-0.5 * k * aB);
        double[] s = new double[5];
        CoarseFieldGrid.merge(s, 0, 10, eA, eA, eA, aA);
        CoarseFieldGrid.merge(s, 0, 12, eB, eB, eB, aB);
        double merged = s[2] * Math.exp(-0.5 * k * s[4]);
        assertEquals(separate, merged, 1e-9);
        assertEquals(aA + aB, s[4], 1e-12);
    }

    @Test
    void scatteringIsNormalisedForwardAndBlue() {
        double sum = 0;
        int n = 4000;
        for (int i = 0; i < n; i++) {
            double mu = -1 + (i + 0.5) * 2.0 / n;
            sum += ReflectionField.henyeyGreenstein(mu) * 2 * Math.PI * 2.0 / n;
        }
        assertEquals(1.0, sum, 1e-3);
        assertTrue(ReflectionField.henyeyGreenstein(1) > 10 * ReflectionField.henyeyGreenstein(-1));
        // A white star behind the dust (seen through it) lights it more than one in front of it.
        double[] c = {0, 0, 0};
        Morphology.Frame frame = new Morphology.Frame(c, new double[] {1, 0, 0}, new double[] {0, 1, 0}, new double[] {0, 0, 1}, 3, 3, 1);
        ReflectionField behind = (ReflectionField) new ReflectionField(1, frame, 0.3, 0.3,
                List.of(new ReflectionField.Light(0, 0, 1.0, 100, 100, 100))).bind(0, 0, -20);
        ReflectionField front = (ReflectionField) new ReflectionField(1, frame, 0.3, 0.3,
                List.of(new ReflectionField.Light(0, 0, -1.0, 100, 100, 100))).bind(0, 0, -20);
        double[] a = new double[4], b = new double[4];
        behind.sample(0.2, 0.1, 0, 0.05, a);
        front.sample(0.2, 0.1, 0, 0.05, b);
        assertTrue(a[3] > 0);
        assertTrue(a[1] > 2 * b[1], "forward " + a[1] + " vs back " + b[1]);
        assertTrue(a[2] > a[0], "scattered light is bluer than the white star");
    }

    @Test
    void orionNebulaHasABrightTealCoreAndRedWings() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        double[] eye = DeepSkyCatalog.lb(209.01, -19.38, 359);
        DeepSkyView v = baker.prepare(eye[0], eye[1], eye[2], Math.toRadians(0.05));
        double[] core = sky(baker, v, eye, 209.01, -19.38), wing = sky(baker, v, eye, 209.01, -18.2);
        assertTrue(lum(core) > 10 * lum(wing), "core " + lum(core) + " vs wing " + lum(wing));
        assertTrue(core[1] / core[0] > wing[1] / wing[0], "[O III] makes the core greener than the Hα wings");
        assertTrue(wing[0] > wing[2], "the wings are Hα-red");
    }

    @Test
    void pleiadesSitInABlueNebula() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        double[] eye = DeepSkyCatalog.lb(166.57, -23.52, 118);
        DeepSkyView v = baker.prepare(eye[0], eye[1], eye[2], Math.toRadians(0.1));
        double[] in = new double[3];
        for (double dl = -1.5; dl <= 1.5; dl += 0.5) {
            for (double db = -1.5; db <= 1.5; db += 0.5) {
                double[] c = sky(baker, v, eye, 166.57 + dl, -23.52 + db);
                for (int k = 0; k < 3; k++) in[k] += c[k];
            }
        }
        double[] off = sky(baker, v, eye, 166.57 + 15, -23.52 - 10);
        assertTrue(in[2] / 49 > 2 * off[2], "nebula " + in[2] / 49 + " vs sky " + off[2]);
        assertTrue(in[2] > in[0], "blue reflection nebula");
    }

    @Test
    void closeUpsFollowTheViewerToCompactNebulae() {
        double[] eye = DeepSkyCatalog.lb(209.01, -19.38, 359);
        List<CloseUp.Target> ts = CloseUp.targets(eye[0], eye[1], eye[2], 2);
        CloseUp.Target t = ts.stream().filter(x -> x.object().id.equals("m42")).findFirst().orElse(null);
        assertNotNull(t, "M42 is among the close-ups 53 pc in front of it");
        assertTrue(t.halfAngle() > Math.toRadians(3) && t.halfAngle() <= CloseUp.MAX_HALF_ANGLE);
        // From the Sun M42 is ~1° across: the bake suffices. (The Pleiades' dust, 3° wide, still qualifies.)
        for (CloseUp.Target s : CloseUp.targets(SUN[0], SUN[1], SUN[2], 5)) assertNotEquals("m42", s.object().id);
    }

    @Test
    void closeUpSelectorRescansOnlyAfterARealMove() {
        double[] eye = DeepSkyCatalog.lb(209.01, -19.38, 359);
        CloseUp.Selector sel = new CloseUp.Selector();
        List<CloseUp.Target> first = sel.targets(eye[0], eye[1], eye[2], 2);
        assertEquals(1, sel.scans());
        assertEquals(CloseUp.targets(eye[0], eye[1], eye[2], 2).size(), first.size());
        // A few metres of flight (5e-14 pc ≈ 1.5 m) reuses the scan; the same list comes back.
        for (int i = 0; i < 100; i++) assertSame(first, sel.targets(eye[0] + i * 5e-14, eye[1], eye[2], 2));
        assertEquals(1, sel.scans());
        // A 1 pc hop (2 % of the 53 pc to M42) rescans and matches a direct scan.
        List<CloseUp.Target> moved = sel.targets(eye[0] + 1, eye[1], eye[2], 2);
        assertEquals(2, sel.scans());
        List<CloseUp.Target> direct = CloseUp.targets(eye[0] + 1, eye[1], eye[2], 2);
        assertEquals(direct.size(), moved.size());
        for (int i = 0; i < direct.size(); i++) assertEquals(direct.get(i).object().id, moved.get(i).object().id);
        sel.reset();
        sel.targets(eye[0] + 1, eye[1], eye[2], 2);
        assertEquals(3, sel.scans());
    }

    @Test
    void highlightCompressionKeepsTheBandAndOrder() {
        int w = 64, h = 32;
        float[] img = new float[w * h * 3];
        for (int p = 0; p < w * h; p++) {
            float v = p % w < 32 ? 1f : 400f * (1 + (p % 7) / 7f);
            img[p * 3] = img[p * 3 + 1] = img[p * 3 + 2] = v;
        }
        float[] before = img.clone();
        GalaxySkyBaker.compressHighlights(img, w, h, Math.toRadians(0.5), false, 10);
        // Far from the bright half, the faint sky is untouched (no dark halo).
        assertEquals(before[(5 * w + 2) * 3], img[(5 * w + 2) * 3], 1e-6);
        // Inside it, brightness is compressed but its order is kept.
        int a = (5 * w + 50) * 3, b = (5 * w + 51) * 3;
        assertTrue(img[a] < before[a]);
        assertEquals(Math.signum(before[a] - before[b]), Math.signum(img[a] - img[b]));
    }

    @Test
    void tonemapRollsHighlightsOffInsteadOfClipping() {
        SkyTonemap tm = SkyTonemap.of(1.0, 0.0);
        int prev = -1;
        for (double x : new double[] {0.3, 1, 3, 10, 30, 100}) {
            int argb = tm.toArgb(x, x, x);
            int g = argb >> 8 & 255;
            assertTrue(g > prev, "monotonic at " + x);
            prev = g;
        }
        assertTrue(prev < 255, "100x the band's brightest is still below white");
        // A saturated Hα colour keeps its hue: red stays the largest channel.
        int red = tm.toArgb(30, 5.4, 9);
        assertTrue((red >> 16 & 255) > (red >> 8 & 255));
    }

    private static double[] sky(GalaxySkyBaker baker, DeepSkyView v, double[] eye, double l, double b) {
        double[] d = DeepMath.direction(l, b), rgb = new double[3];
        baker.march(v, eye[0], eye[1], eye[2], d[0], d[1], d[2], rgb);
        return rgb;
    }

    private static double lum(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
