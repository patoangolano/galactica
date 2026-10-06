package dev.galactica.deepsky;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.Starfield;
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
        assertEquals(1.0, w.unresolvedFraction(w.resolvedMagnitudeLimit(5200, 0)), 1e-9, "unresolved from the Sun");
        assertTrue(w.unresolvedFraction(w.resolvedMagnitudeLimit(40, 0)) < 0.95, "giants resolved from 40 pc");
    }

    @Test
    void pleiadesLookLikeThePleiadesFromEarth() {
        StarCluster p = (StarCluster) byId("m45");
        DeepSky ds = DeepSky.of(Frames.GALAXY_SEED);
        DeepSky.ClusterResolution r = DeepSky.resolution(p, SUN[0], SUN[1], SUN[2], ds.dustView(SUN[0], SUN[1], SUN[2]));
        List<double[]> mags = new ArrayList<>();
        p.members(r.limitMv(), s -> mags.add(new double[] {
                s.absoluteMagnitude() + 5 * Math.log10(s.distanceTo(SUN[0], SUN[1], SUN[2]) / 10) + r.avMag(), s.catalog() ? 1 : 0}));
        mags.sort((a, b) -> Double.compare(a[0], b[0]));
        assertEquals(2.87, mags.get(0)[0], 0.3, "Alcyone is the brightest (V = 2.87)");
        assertEquals(1, mags.get(0)[1], "the brightest is a catalogued anchor");
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

    /** Total V light (L☉) of the members {@link StarCluster#members} emits down to {@code lim}. */
    private static double drawnLight(StarCluster c, double lim) {
        double[] l = {0};
        c.members(lim, s -> l[0] += Math.pow(10, -0.4 * (s.absoluteMagnitude() - 4.83)));
        return l[0];
    }

    private static double anchorLight(StarCluster c) {
        double l = 0;
        for (Star s : c.anchorStars()) l += Math.pow(10, -0.4 * (s.absoluteMagnitude() - 4.83));
        return l;
    }

    @Test
    void clusterGlowAndDrawnMembersAddUpToTheCluster() {
        // Expectation identity: glow + expected bright procedural light + bright anchors = total, at any limit.
        for (String id : new String[] {"ngc6611", "ngc6530", "ngc2244", "m44", "onc", "m45", "ngc5139"}) {
            StarCluster c = (StarCluster) byId(id);
            double proc = c.nStars * c.population.lumPerStar;
            for (double lim : new double[] {-8, -4, -1, 1, 3, 5}) {
                double brightAnchors = 0;
                for (Star s : c.anchorStars()) if (s.absoluteMagnitude() <= lim) brightAnchors += Math.pow(10, -0.4 * (s.absoluteMagnitude() - 4.83));
                double sum = c.unresolvedFraction(lim) * proc + c.nStars * c.population.lumBrighter(lim) + brightAnchors;
                assertEquals(proc + anchorLight(c), sum, 1e-9 * sum, id + " at " + lim);
            }
        }
        // The expected bright light used by the glow agrees with the members actually drawn, on average.
        // Clusters older than 20 Myr only: younger ones are dominated by a rare O star, so their mean
        // converges far too slowly for a test (the identity above still holds for them exactly).
        DeepSky ds = DeepSky.of(Frames.GALAXY_SEED);
        double expected = 0, drawn = 0;
        int n = 0;
        for (DeepSkyObject o : ds.proceduralNear(SUN[0], SUN[1], SUN[2], 2_000)) {
            if (!(o instanceof StarCluster c) || c.ageGyr < 0.02 || n >= 600) continue;
            double lim = c.resolvedMagnitudeLimit(150, 0);
            expected += c.nStars * c.population.lumBrighter(lim);
            drawn += drawnLight(c, lim);
            n++;
        }
        assertTrue(n > 100, "clusters sampled " + n);
        assertEquals(1.0, drawn / expected, 0.1, "drawn / expected bright light over " + n + " clusters");
    }

    @Test
    void extinctionLowersTheResolutionLimitSharedByBothLayers() {
        DeepSky ds = DeepSky.of(Frames.GALAXY_SEED);
        DeepSkyView dust = ds.dustView(SUN[0], SUN[1], SUN[2]);
        // The Eagle's cluster sits behind A_V ~ 3 (real: ~2.5); its brightest star (an O3 at V = 8.2) is
        // below the naked eye, so none of its members may be drawn from Earth.
        StarCluster eagle = (StarCluster) byId("ngc6611");
        DeepSky.ClusterResolution r = DeepSky.resolution(eagle, SUN[0], SUN[1], SUN[2], dust);
        assertTrue(r.avMag() > 1.5, "the Eagle is reddened, A_V = " + r.avMag());
        assertEquals(eagle.resolvedMagnitudeLimit(r.distancePc(), 0) - r.avMag(), r.limitMv(), 1e-9);
        // Every member brighter than the shared limit reaches the starfield (none is lost between the layers).
        List<Starfield.VisibleStar> stars = Starfield.compute(new Galaxy(Frames.GALAXY_SEED), SUN[0], SUN[1], SUN[2]);
        int checked = 0;
        for (DeepSky.ClusterResolution c : ds.resolvedClusters(SUN[0], SUN[1], SUN[2])) {
            if (c.cluster().catalogued) continue; // anchors share names with catalogue stars
            List<Star> members = new ArrayList<>();
            c.cluster().members(c.limitMv(), members::add);
            // Compare the brighter ones only: the field is cut at MAX_STARS from the faint end.
            long want = members.stream().filter(s -> s.absoluteMagnitude() + 5 * Math.log10(s.distanceTo(SUN[0], SUN[1], SUN[2]) / 10)
                    + c.avMag() < 7.0).count();
            String prefix = c.cluster().name + " ";
            long got = stars.stream().filter(v -> v.star().name().startsWith(prefix) && v.apparentMag() < 7.0).count();
            assertEquals(want, got, c.cluster().name);
            checked += members.isEmpty() ? 0 : 1;
        }
        assertTrue(checked > 3, "procedural clusters with resolved members " + checked);
    }

    @Test
    void membersNearMatchesBruteForceAndIsFast() {
        StarCluster p = (StarCluster) byId("m44");
        double qx = p.x + 1.5, qy = p.y - 0.5, qz = p.z + 0.3, rad = 1.2;
        List<Star> brute = new ArrayList<>();
        p.members(99, s -> { if (s.distanceTo(qx, qy, qz) <= rad) brute.add(s); });
        brute.sort((a, b) -> Double.compare(a.distanceTo(qx, qy, qz), b.distanceTo(qx, qy, qz)));
        List<Star> fast = p.membersNear(qx, qy, qz, rad, 10_000);
        assertEquals(brute, fast);
        List<Star> capped = p.membersNear(qx, qy, qz, rad, 5);
        assertEquals(brute.subList(0, Math.min(5, brute.size())), capped);

        StarCluster w = (StarCluster) byId("ngc5139");
        DeepSky ds = DeepSky.of(Frames.GALAXY_SEED);
        ds.clusterStarsNear(w.x + 1, w.y, w.z, 0.5, 64); // warm
        long t0 = System.nanoTime();
        List<Star> near = ds.clusterStarsNear(w.x + 1, w.y, w.z, 0.5, 64);
        double ms = (System.nanoTime() - t0) / 1e6;
        assertEquals(64, near.size());
        assertTrue(ms < 400, "query inside omega Cen took " + ms + " ms");
    }

    @Test
    void skyRebakesFollowTheViewerNearDeepSkyObjects() {
        DeepSky ds = DeepSky.of(Frames.GALAXY_SEED);
        assertEquals(3.0, ds.rebakeDistancePc(SUN[0], SUN[1], SUN[2], 3.0), 1e-9, "open space near the Sun");
        DeepSkyObject onc = byId("onc");
        assertEquals(DeepSky.STARFIELD_STEP_PC, ds.rebakeDistancePc(onc.x, onc.y, onc.z, 3.0), 1e-9, "inside the Trapezium");
        DeepSkyObject m45 = byId("m45");
        double step = ds.rebakeDistancePc(m45.x + 20, m45.y, m45.z, 3.0);
        assertTrue(step > 1.5 && step <= 2.0, "20 pc from the Pleiades " + step);
    }

    @Test
    void hiiCrossFadeWeightsSumToOne() {
        for (double d = 0; d < 4_000; d += 37) {
            assertEquals(1.0, DeepSky.discreteHiiWeight(d) + DeepSky.statisticalHiiWeight(d), 1e-12);
        }
        assertEquals(1.0, DeepSky.discreteHiiWeight(DeepSky.HII_CROSSFADE_START_PC), 1e-12);
        assertEquals(0.0, DeepSky.discreteHiiWeight(DeepSky.VIEW_RADIUS_PC), 1e-12);
    }

    @Test
    void anchorsAreNotDuplicatedByProceduralMembers() {
        for (String id : new String[] {"m45", "onc"}) {
            StarCluster c = (StarCluster) byId(id);
            double minAnchor = Double.POSITIVE_INFINITY;
            for (Star s : c.anchorStars()) minAnchor = Math.min(minAnchor, s.mass());
            assertTrue(c.population.massCap < minAnchor, id + " mass cap " + c.population.massCap + " vs " + minAnchor);
        }
        assertEquals(DeepSky.near(byId("lambda_ori").x, byId("lambda_ori").y, byId("lambda_ori").z, 1), "Lambda Orionis Ring");
    }
}
