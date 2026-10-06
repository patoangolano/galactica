package dev.galactica.deepsky;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.star.Star;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSkyTest {
    private static final double[] SUN = {StarCatalog.SOL.x(), StarCatalog.SOL.y(), StarCatalog.SOL.z()};

    private static DeepSkyObject byId(String id) {
        for (DeepSkyObject o : DeepSkyCatalog.all()) if (o.id.equals(id)) return o;
        throw new AssertionError(id);
    }

    @Test
    void erfMatchesTabulatedValues() {
        assertEquals(0.0, DeepMath.erf(0), 1e-7);
        assertEquals(0.5204998778, DeepMath.erf(0.5), 2e-7);
        assertEquals(0.8427007929, DeepMath.erf(1), 2e-7);
        assertEquals(-0.9953222650, DeepMath.erf(-2), 2e-7);
    }

    @Test
    void equatorialToGalacticMatchesKnownObjects() {
        double[] m42 = DeepMath.equatorialToGalactic(DeepMath.ra(5, 35, 17.3), DeepMath.dec(-5, 23, 28));
        assertEquals(209.01, m42[0], 0.05);
        assertEquals(-19.38, m42[1], 0.05);
        double[] m31 = DeepMath.equatorialToGalactic(DeepMath.ra(0, 42, 44.3), DeepMath.dec(41, 16, 9));
        assertEquals(121.17, m31[0], 0.05);
        assertEquals(-21.57, m31[1], 0.05);
        double[] ngp = DeepMath.equatorialToGalactic(192.85948, 27.12825); // IAU north galactic pole
        assertEquals(90.0, ngp[1], 0.01);
    }

    @Test
    void dustColumnThroughGaussianEqualsPeakExtinction() {
        double[] c = DeepMath.galactocentric(0, 0, 100);
        double sigma = 2.0;
        Blob b = Blob.sphere(c, sigma, new double[3], 0, Blob.dustFor(2.0, sigma, sigma));
        DeepSkyObject o = DeepSkyObject.of("t", "t", DeepSkyType.DARK_NEBULA, c, 8, false, 1, x -> List.of(b));
        DeepSkyView v = new DeepSkyView(SUN[0], SUN[1], SUN[2], List.of(o), 0, true);
        double[] d = DeepMath.direction(0, 0);
        assertEquals(2.0, v.extinction(d[0], d[1], d[2], 300), 0.01, "full column");
        assertEquals(1.0, v.extinction(d[0], d[1], d[2], 100), 0.01, "half the cloud is in front of its centre");
        assertEquals(0.0, v.extinction(d[0], d[1], d[2], 50), 1e-6, "nothing in front of the cloud");
    }

    @Test
    void pixelBlurConservesFlux() {
        // A Gaussian much smaller than the pixel footprint: ∫ I dΩ must still equal L / (4π d²).
        double d = 1000, lum = 1000;
        double[] c = DeepMath.galactocentric(0, 0, d);
        Blob b = Blob.sphere(c, 0.05, new double[] {1, 1, 1}, lum, 0);
        DeepSkyObject o = DeepSkyObject.of("t", "t", DeepSkyType.EMISSION_NEBULA, c, 1, false, 1, x -> List.of(b));
        double pix = Math.toRadians(0.5);
        DeepSkyView v = new DeepSkyView(SUN[0], SUN[1], SUN[2], List.of(o), pix, false);
        double step = Math.toRadians(0.02), sum = 0;
        for (double l = -1.0; l <= 1.0; l += 0.02) {
            for (double bb = -1.0; bb <= 1.0; bb += 0.02) {
                double[] dir = DeepMath.direction(l, bb);
                DeepSkyView.RayEvents ev = v.trace(dir[0], dir[1], dir[2]);
                for (int i = 0; i < ev.count; i++) sum += ev.g[i] * step * step;
            }
        }
        assertEquals(lum / (4 * Math.PI * d * d), sum, 0.03 * lum / (4 * Math.PI * d * d));
    }

    @Test
    void kingProfileAndItsGaussianExpansion() {
        KingProfile k = new KingProfile(1.0, Math.pow(10, 1.5));
        assertTrue(k.radiusAt(0.1) < k.radiusAt(0.5) && k.radiusAt(0.5) < k.radiusAt(0.9));
        double rh = k.projectedHalfRadius();
        assertTrue(rh > 2 && rh < 8, "r_h/r_c for c = 1.5 is ~3-5, got " + rh);
        // The MGE follows the normalised density within 25 % between 0.3 r_c and r_t / 3.
        double total = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            double r = k.rt * (i + 0.5) / n;
            total += 4 * Math.PI * r * r * k.density(r) * k.rt / n;
        }
        Mge m = k.mge();
        for (double r : new double[] {0.3, 1, 3, 10}) {
            double exact = k.density(r) / total, fit = m.value(r, 3);
            assertEquals(1.0, fit / exact, 0.25, "MGE at r = " + r);
        }
    }

    @Test
    void omegaCentauriMatchesHarris() {
        StarCluster w = (StarCluster) byId("ngc5139");
        double mv = 4.83 - 2.5 * Math.log10(w.luminosityV());
        assertEquals(-10.26, mv, 0.1, "integrated M_V");
        assertTrue(w.nStars > 5e5 && w.nStars < 2e7, "member count " + w.nStars);
        double[] lb = DeepMath.galacticFromSun(w.x, w.y, w.z);
        assertEquals(5200, lb[2], 5);
        assertEquals(1.0, w.unresolvedFraction(5200), 1e-9, "unresolved from the Sun");
        assertTrue(w.unresolvedFraction(40) < 0.95, "giants resolved from 40 pc");
    }

    @Test
    void pleiadesLookLikeThePleiadesFromEarth() {
        StarCluster p = (StarCluster) byId("m45");
        double d = p.distanceTo(SUN[0], SUN[1], SUN[2]);
        List<double[]> mags = new ArrayList<>();
        p.members(p.resolvedMagnitudeLimit(d), s -> mags.add(new double[] {
                s.absoluteMagnitude() + 5 * Math.log10(s.distanceTo(SUN[0], SUN[1], SUN[2]) / 10), s.catalog() ? 1 : 0}));
        mags.sort((a, b) -> Double.compare(a[0], b[0]));
        assertEquals(2.87 - 0.12, mags.get(0)[0], 0.25, "Alcyone is the brightest (V = 2.87 incl. extinction)");
        long nakedEye = mags.stream().filter(m -> m[0] < 6.0).count();
        assertTrue(nakedEye >= 8 && nakedEye <= 20, "naked-eye members: " + nakedEye);
        long brightB = mags.stream().filter(m -> m[0] < 4.5).count();
        assertTrue(brightB >= 5 && brightB <= 8, "stars brighter than 4.5: " + brightB);
    }

    @Test
    void membersAreDeterministic() {
        StarCluster p = (StarCluster) byId("m44");
        Star a = p.member(3, 7), b = p.member(3, 7);
        assertEquals(a, b);
        assertEquals(p.binCount(3), p.binCount(3));
    }

    @Test
    void globularSystemHasAboutOneHundredFiftyClusters() {
        List<DeepSkyObject> gcs = new ArrayList<>(DeepSky.of(Frames.GALAXY_SEED).globulars());
        for (DeepSkyObject o : DeepSkyCatalog.all()) if (o.type == DeepSkyType.GLOBULAR_CLUSTER) gcs.add(o);
        assertEquals(ProceduralDeepSky.GLOBULAR_TOTAL, gcs.size());
        List<Double> r = new ArrayList<>();
        for (DeepSkyObject o : gcs) r.add(Math.sqrt(o.x * o.x + o.y * o.y + o.z * o.z));
        r.sort(Double::compare);
        double median = r.get(r.size() / 2);
        assertTrue(median > 2_000 && median < 12_000, "median galactocentric radius " + median); // Harris: ~5 kpc
        assertEquals(DeepSky.of(Frames.GALAXY_SEED).globulars().get(5).name, ProceduralDeepSky.globulars(Frames.GALAXY_SEED).get(5).name);
    }

    @Test
    void proceduralObjectsFollowTheArmsAndAvoidTheLocalBubble() {
        DeepSky ds = DeepSky.of(Frames.GALAXY_SEED);
        int hii = 0, oc = 0, dark = 0;
        for (DeepSkyObject o : ds.proceduralNear(SUN[0], SUN[1], SUN[2], 3_000)) {
            double d = o.distanceTo(SUN[0], SUN[1], SUN[2]);
            switch (o.type) {
                case EMISSION_NEBULA -> { hii++; assertTrue(d > 590); }
                case OPEN_CLUSTER -> { oc++; assertTrue(d > 290); }
                case DARK_NEBULA -> { dark++; assertTrue(d > 120); }
                default -> fail();
            }
        }
        // Local surface densities ~8, ~150 and ~18 kpc⁻² over ~28 kpc² (open clusters within 2 kpc).
        assertTrue(hii > 60 && hii < 800, "H II regions " + hii);
        assertTrue(oc > 600 && oc < 5_000, "open clusters " + oc);
        assertTrue(dark > 150 && dark < 2_000, "dark clouds " + dark);
    }

    @Test
    void nearNamesTheClosestCataloguedObject() {
        DeepSkyObject m42 = byId("m42");
        assertEquals("Trapezium Cluster", DeepSky.near(m42.x, m42.y, m42.z + 0.5, 10));
        DeepSkyObject m45 = byId("m45");
        assertEquals("Pleiades (M45)", DeepSky.near(m45.x + 3, m45.y, m45.z, 10));
        assertNull(DeepSky.near(SUN[0], SUN[1], SUN[2] + 40, 5));
        assertEquals("deepsky.galactica.m45", m45.translationKey());
    }

    /** Radiance (luminance) seen from the Sun towards galactic (l, b). */
    private static double[] sky(GalaxySkyBaker baker, DeepSkyView v, double l, double b) {
        double[] d = DeepMath.direction(l, b), rgb = new double[3];
        baker.march(v, SUN[0], SUN[1], SUN[2], d[0], d[1], d[2], rgb);
        return rgb;
    }

    private static double lum(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    @Test
    void landmarksAreVisibleFromEarth() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        DeepSkyView v = baker.prepare(SUN[0], SUN[1], SUN[2], 2 * Math.PI / 2048);
        // Magellanic Clouds stand out from the southern sky.
        assertTrue(lum(sky(baker, v, 280.47, -32.89)) > 2 * lum(sky(baker, v, 280.47, -52)), "LMC");
        assertTrue(lum(sky(baker, v, 302.81, -44.33)) > 1.5 * lum(sky(baker, v, 302.81, -60)), "SMC");
        // Andromeda: a fuzzy oval brighter than the sky around it.
        assertTrue(lum(sky(baker, v, 121.17, -21.57)) > 2 * lum(sky(baker, v, 125.0, -25.0)), "M31");
        // The Coalsack is a dark patch in the band next to Crux.
        assertTrue(lum(sky(baker, v, 301.0, -1.0)) < 0.75 * lum(sky(baker, v, 309.0, -1.0)), "Coalsack");
        // The Orion Nebula: bright, and Hα-red overall ([O III] teal only in the core), over a 1.2° box.
        double[] m42 = new double[3];
        for (double dl = -0.6; dl <= 0.6; dl += 0.1) {
            for (double db = -0.6; db <= 0.6; db += 0.1) {
                double[] c = sky(baker, v, 209.01 + dl, -19.38 + db);
                for (int k = 0; k < 3; k++) m42[k] += c[k] / 169;
            }
        }
        double[] off = sky(baker, v, 212.0, -24.0);
        assertTrue(lum(m42) > 2 * lum(off), "M42 bright");
        assertTrue(m42[0] / m42[1] > 1.1 * off[0] / off[1], "M42 redder than the sky");
    }

    @Test
    void twoScaleBakeMatchesTheFullMarch() {
        GalaxySkyBaker baker = new GalaxySkyBaker(Frames.GALAXY_SEED);
        int w = 128, h = 64;
        float[] fast = new float[w * h * 3];
        baker.bakeEquirect(SUN[0], SUN[1], SUN[2], w, h, fast);
        DeepSkyView v = baker.prepare(SUN[0], SUN[1], SUN[2], 2 * Math.PI / w);
        double err = 0, ref = 0;
        double[] d = new double[3], rgb = new double[3];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                GalaxySkyBaker.equirectDirection((i + 0.5) / w, (j + 0.5) / h, d);
                baker.march(v, SUN[0], SUN[1], SUN[2], d[0], -d[2], d[1], rgb);
                int k = (j * w + i) * 3;
                err += Math.abs(fast[k + 1] - rgb[1]);
                ref += rgb[1];
            }
        }
        // At 128 px the half-res galaxy is coarse (2.8° per sample); in game (2048 px) it is far closer.
        assertTrue(err / ref < 0.15, "mean relative difference " + err / ref);
    }
}
