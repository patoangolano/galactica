package dev.galactica.planet;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.science.SeedRng;
import dev.galactica.sky.PlanetImager;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.galactica.planet.SurfaceAtmosphereTest.sol;
import static org.junit.jupiter.api.Assertions.*;

/** Orbit view and landing site come from one source: {@link PlanetMacro}. */
class PlanetMacroTest {

    private static double[] randomDir(SeedRng rng) {
        double z = rng.nextDouble() * 2 - 1, a = rng.nextDouble() * 2 * Math.PI, c = Math.sqrt(1 - z * z);
        return new double[] {c * Math.cos(a), c * Math.sin(a), z};
    }

    /** Fraction of 1000 random directions where the imager's sea and the generator's sea agree. */
    private static double agreement(Body b, long salt) {
        SurfaceGrid grid = SurfaceGrid.of(b.radiusMetres());
        TerrainSampler t = TerrainSampler.of(b, grid);
        SeedRng rng = new SeedRng(SeedRng.hash(salt, 1, 2, 3));
        int agree = 0, sea = 0;
        for (int i = 0; i < 1000; i++) {
            double[] d = randomDir(rng);
            double lat = Math.asin(d[2]), lon = Math.atan2(d[1], d[0]);
            boolean imagerSea = PlanetImager.isSea(b, d[0], d[1], d[2]);
            boolean groundSea = !(t.height(grid.blockX(lon), grid.blockZ(lat)) > TerrainSampler.SEA_LEVEL);
            if (imagerSea == groundSea) agree++;
            if (imagerSea) sea++;
        }
        assertTrue(sea > 20 && sea < 990, "the test needs both land and sea, sea=" + sea);
        System.out.println("land/sea agreement " + b.type() + " " + b.name() + ": " + agree / 10.0 + " % (sea " + sea / 10.0 + " %)");
        return agree / 1000.0;
    }

    private static Body synthetic(BodyType type, long seed, double albedo) {
        return new Body(seed, "test-" + seed, type, null, 1.0, 1.0, albedo, 280, 280, 1.0,
                Body.Atmosphere.N2_O2, 24, 20, false, false, Body.Life.NONE, seed, List.of());
    }

    @Test
    void earthLandAndSeaFromOrbitMatchTheGround() {
        double a = agreement(sol("Earth"), 7);
        assertTrue(a >= 0.98, "agreement " + a);
    }

    @Test
    void oceanAndLavaWorldsAgreeToo() {
        assertTrue(agreement(synthetic(BodyType.OCEAN, 4242, 0.3), 11) >= 0.98);
        assertTrue(agreement(synthetic(BodyType.TERRAN, 777, 0.3), 13) >= 0.98);
        assertTrue(agreement(synthetic(BodyType.LAVA, 31337, 0.15), 17) >= 0.98);
    }

    @Test
    void imagerFrameIsTheSurfaceMapFrame() {
        // A galactic direction seen from Earth's centre: BodyFrame lat/lon must equal the body-fixed
        // coordinates the imager computes from its own prime-meridian basis.
        Body earth = sol("Earth");
        var star = StarCatalog.SOL;
        SeedRng rng = new SeedRng(99);
        for (int i = 0; i < 50; i++) {
            double t = rng.nextDouble() * 3e8;
            double[] g = randomDir(rng);
            double[] ll = BodyFrame.latLon(star, earth, t, g);
            double[] pole = BodyFrame.pole(star, earth);
            double[] s = Frames.galacticToMc(pole[0], pole[1], pole[2]);
            double[] e1 = new double[3], e2 = new double[3];
            PlanetImager.primeMeridian(s, e1, e2);
            double th = BodyFrame.spinAngle(earth, t), c = Math.cos(th), sn = Math.sin(th);
            double[] b1 = new double[3], b2 = new double[3];
            for (int k = 0; k < 3; k++) {
                b1[k] = c * e1[k] + sn * e2[k];
                b2[k] = -sn * e1[k] + c * e2[k];
            }
            double[] d = Frames.galacticToMc(g[0], g[1], g[2]);
            double qx = dot(d, b1), qy = dot(d, b2), qz = dot(d, s);
            assertEquals(ll[0], Math.asin(qz), 1e-9, "latitude");
            assertEquals(0, BodyFrame.wrapPi(ll[1] - Math.atan2(qy, qx)), 1e-9, "longitude");
        }
    }

    @Test
    void tailContinuesTheSeriesBelowTheImagerScale() {
        PlanetMacro m = PlanetMacro.of(sol("Earth"));
        double r = SurfaceGrid.of(sol("Earth").radiusMetres()).radiusBlocks();
        assertTrue(m.tailOctaves(r) > PlanetMacro.IMAGER_OCTAVES + 4, "reaches block scale: " + m.tailOctaves(r));
        // The tail is small: at most 2^-6 of the full field on average.
        SeedRng rng = new SeedRng(5);
        double sum = 0;
        for (int i = 0; i < 500; i++) {
            double[] d = randomDir(rng);
            sum += Math.abs(m.tail(d[0], d[1], d[2], r));
        }
        assertTrue(sum / 500 < 0.02, "mean |tail| " + sum / 500);
        // Same series: the 6-octave field plus tail equals a longer fBm with the same normalisation.
        double[] d = {0.3, -0.5, Math.sqrt(1 - 0.34)};
        PlanetMacro.Sample s = new PlanetMacro.Sample();
        m.sample(d[0], d[1], d[2], s);
        int n = m.tailOctaves(r);
        double full = PlanetMacro.fbmRange(sol("Earth").terrainSeed(), d[0] * 1.6, d[1] * 1.6, d[2] * 1.6, 0, n)
                / (1 - Math.pow(0.5, 6));
        assertEquals(full, s.base + m.tail(d[0], d[1], d[2], r), 1e-12);
    }

    @Test
    void earthHasDesertBeltsAndIceCaps() {
        PlanetMacro m = PlanetMacro.of(sol("Earth"));
        PlanetMacro.Sample s = new PlanetMacro.Sample();
        SeedRng rng = new SeedRng(3);
        int capPolar = 0, capTropic = 0, aridBelt = 0, aridEquator = 0, nBelt = 0, nEq = 0;
        for (int i = 0; i < 4000; i++) {
            double[] d = randomDir(rng);
            m.sample(d[0], d[1], d[2], s);
            double lat = Math.abs(d[2]);
            if (lat > 0.95 && s.iceCap > 0.5) capPolar++;
            if (lat < 0.6 && s.iceCap > 0.5) capTropic++;
            if (Math.abs(lat - 0.42) < 0.05) { nBelt++; aridBelt += s.aridity > 0.5 ? 1 : 0; }
            if (lat < 0.08) { nEq++; aridEquator += s.aridity > 0.5 ? 1 : 0; }
        }
        assertTrue(capPolar > 20, "polar caps " + capPolar);
        assertEquals(0, capTropic, "no ice caps in the tropics");
        assertTrue((double) aridBelt / nBelt > (double) aridEquator / nEq + 0.1, "Hadley belts are drier than the equator");
    }

    @Test
    void lunarMariaAreLowGround() {
        Body moon = sol("Moon");
        SurfaceGrid grid = SurfaceGrid.of(moon.radiusMetres());
        TerrainSampler t = TerrainSampler.of(moon, grid);
        PlanetMacro.Sample s = new PlanetMacro.Sample();
        SeedRng rng = new SeedRng(21);
        double hMare = 0, hHigh = 0;
        int nMare = 0, nHigh = 0;
        for (int i = 0; i < 1500; i++) {
            double[] d = randomDir(rng);
            t.macro().sample(d[0], d[1], d[2], s);
            int h = t.height(grid.blockX(Math.atan2(d[1], d[0])), grid.blockZ(Math.asin(d[2])));
            if (s.mare > 0.8) { hMare += h; nMare++; }
            if (s.mare < 0.05) { hHigh += h; nHigh++; }
        }
        assertTrue(nMare > 20 && nHigh > 20);
        assertTrue(hMare / nMare < hHigh / nHigh - 30, "maria " + hMare / nMare + " vs highlands " + hHigh / nHigh);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    void elevationOnlyPathEqualsTheFullSample() {
        PlanetMacro.Sample o = new PlanetMacro.Sample();
        SeedRng rng = new SeedRng(99);
        for (BodyType type : BodyType.values()) {
            if (!type.solidSurface) continue;
            for (double albedo : new double[] {0.15, 0.3, 0.6}) {
                PlanetMacro m = new PlanetMacro(type, 1234 + type.ordinal(), type == BodyType.TERRAN, albedo);
                for (int i = 0; i < 300; i++) {
                    double[] d = randomDir(rng);
                    m.sample(d[0], d[1], d[2], o);
                    assertEquals(o.elevation, m.elevation(d[0], d[1], d[2]), 0, type + " albedo " + albedo);
                }
            }
        }
    }

    @Test
    void generatorPassThroughMatchesSeparateCalls() {
        Body earth = sol("Earth");
        SurfaceGrid grid = SurfaceGrid.of(earth.radiusMetres());
        TerrainSampler t = TerrainSampler.of(earth, grid);
        PlanetMacro.Sample full = new PlanetMacro.Sample(), ref = new PlanetMacro.Sample();
        TerrainSampler.Column a = new TerrainSampler.Column(), b = new TerrainSampler.Column();
        for (int i = 0; i < 400; i++) {
            int x = i * 9_973 - 2_000_000, z = (i * 31_337) % 900_000 - 450_000;
            int h = t.height(x, z, full);
            assertEquals(t.height(x, z), h);
            t.macroAt(x, z, ref);
            assertEquals(ref.aridity, full.aridity, 0);
            assertEquals(ref.iceCap, full.iceCap, 0);
            t.column(x, z, h, 0, a);
            t.column(x, z, h, 0, b, full);
            assertEquals(a.visible(), b.visible());
            assertEquals(a.filler, b.filler);
        }
    }
}
