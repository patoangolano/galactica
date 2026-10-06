package dev.galactica.planet;

import com.mojang.serialization.JsonOps;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.galactica.planet.SurfaceAtmosphereTest.sol;
import static org.junit.jupiter.api.Assertions.*;

class PlanetGeometryTest {

    @Test
    void gridIsTrueScaleForSmallBodiesAndCappedForLargeOnes() {
        SurfaceGrid earth = SurfaceGrid.of(sol("Earth").radiusMetres());
        assertEquals(SurfaceGrid.MAX_WIDTH, earth.width(), 32);
        assertEquals(10.02, earth.metresPerBlock(), 0.02);
        SurfaceGrid phobos = SurfaceGrid.of(sol("Phobos").radiusMetres());
        assertEquals(1.0, phobos.metresPerBlock(), 0.001);
        assertEquals(0, phobos.width() % 32, 1e-9);
    }

    @Test
    void gridMapsLatLonBothWays() {
        SurfaceGrid g = SurfaceGrid.of(3.39e6);
        for (double lat = -1.5; lat <= 1.5; lat += 0.25) {
            for (double lon = -3.1; lon <= 3.1; lon += 0.4) {
                assertEquals(lat, g.latitude(g.blockZ(lat)), 1e-9);
                assertEquals(lon, g.longitude(g.blockX(lon)), 1e-9);
            }
        }
        assertTrue(g.blockZ(0.5) < 0, "north is −Z");
        assertEquals(g.wrapX(g.halfWidth() + 10), -g.halfWidth() + 10, 1e-9);
    }

    @Test
    void latLonRoundTripsThroughBodyFrame() {
        Body mars = sol("Mars");
        for (double t : new double[] {0, 3.7e5, -1e8}) {
            for (double lat = -1.4; lat <= 1.4; lat += 0.35) {
                for (double lon = -3.0; lon <= 3.0; lon += 0.5) {
                    double[] d = BodyFrame.direction(StarCatalog.SOL, mars, t, lat, lon);
                    double[] ll = BodyFrame.latLon(StarCatalog.SOL, mars, t, d);
                    assertEquals(lat, ll[0], 1e-9);
                    assertEquals(lon, ll[1], 1e-9);
                }
            }
        }
    }

    @Test
    void surfaceRotatesUnderAFixedDirection() {
        Body earth = sol("Earth");
        double[] fixed = {1, 0, 0};
        double lon0 = BodyFrame.latLon(StarCatalog.SOL, earth, 0, fixed)[1];
        double quarterDay = earth.rotationHours() * 3600 / 4;
        double lon1 = BodyFrame.latLon(StarCatalog.SOL, earth, quarterDay, fixed)[1];
        assertEquals(-Math.PI / 2, BodyFrame.wrapPi(lon1 - lon0), 1e-9, "prograde spin carries the ground east");
        // Venus spins retrograde (negative period).
        Body venus = sol("Venus");
        double v0 = BodyFrame.latLon(StarCatalog.SOL, venus, 0, fixed)[1];
        double v1 = BodyFrame.latLon(StarCatalog.SOL, venus, Math.abs(venus.rotationHours()) * 900, fixed)[1];
        assertEquals(Math.PI / 2, BodyFrame.wrapPi(v1 - v0), 1e-9);
    }

    @Test
    void poleIsTiltedFromTheSystemNormal() {
        Body earth = sol("Earth");
        double[] n = dev.galactica.system.SystemFrame.normalGalactic(StarCatalog.SOL);
        double[] p = BodyFrame.pole(StarCatalog.SOL, earth);
        assertEquals(Math.cos(Math.toRadians(earth.axialTiltDeg())), BodyFrame.dot(n, p), 1e-9);
    }

    @Test
    void solarSystemAddressesResolve() {
        Galaxy galaxy = new Galaxy(Frames.GALAXY_SEED);
        PlanetAddress mars = PlanetAddress.of(galaxy, StarCatalog.SOL, sol("Mars"));
        assertEquals(new PlanetAddress("Sol", 0, 0, 0, 0, 3, -1), mars);
        assertEquals("Mars", mars.resolve(galaxy).body().name());
        PlanetAddress titan = PlanetAddress.of(galaxy, StarCatalog.SOL, sol("Titan"));
        PlanetAddress.Resolved r = titan.resolve(galaxy);
        assertEquals("Titan", r.body().name());
        assertEquals("Saturn", r.planet().name());
        var json = PlanetAddress.CODEC.encodeStart(JsonOps.INSTANCE, titan).getOrThrow();
        assertEquals(titan, PlanetAddress.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void proceduralAddressesRoundTrip() {
        Galaxy galaxy = new Galaxy(Frames.GALAXY_SEED);
        SectorKey k = StarCatalog.SOL_SECTOR.offset(2, 1, 0);
        int found = 0;
        for (int i = 0; i < galaxy.starCount(k) && found < 3; i++) {
            Star s = galaxy.star(k, i);
            for (Body p : SystemGenerator.generate(s)) {
                List<Body> bodies = new ArrayList<>(p.moons());
                bodies.add(p);
                for (Body b : bodies) {
                    PlanetAddress a = PlanetAddress.of(galaxy, s, b);
                    assertNotNull(a, b.name());
                    assertEquals(b.id(), a.resolve(galaxy).body().id(), b.name());
                    found++;
                }
            }
        }
        assertTrue(found > 0, "some procedural planets near the Sun");
    }

    @Test
    void slotChoiceReusesThenFillsLowestFree() {
        PlanetAddress a = new PlanetAddress("Sol", 0, 0, 0, 0, 3, -1), b = new PlanetAddress("Sol", 0, 0, 0, 0, 2, 0);
        List<SlotPolicy.Entry> e = new ArrayList<>(List.of(SlotPolicy.Entry.FREE, new SlotPolicy.Entry(Optional.of(a), 10),
                SlotPolicy.Entry.FREE));
        assertEquals(1, SlotPolicy.choose(e, a));
        assertEquals(0, SlotPolicy.choose(e, b));
        List<SlotPolicy.Entry> full = List.of(new SlotPolicy.Entry(Optional.of(a), 1));
        assertEquals(-1, SlotPolicy.choose(full, b));
    }

    @Test
    void evictionTakesLeastRecentlyUsedFirst() {
        List<SlotPolicy.Entry> e = new ArrayList<>();
        long[] used = {50, 10, 40, 20, 30, 60};
        for (int k = 0; k < used.length; k++) e.add(new SlotPolicy.Entry(Optional.of(new PlanetAddress("", k, 0, 0, 0, 0, -1)), used[k]));
        e.add(SlotPolicy.Entry.FREE);
        assertEquals(List.of(1, 3), SlotPolicy.evictionPlan(e, 3, s -> false));
        assertEquals(List.of(3, 4), SlotPolicy.evictionPlan(e, 3, s -> s == 1), "pinned slots survive");
        assertEquals(List.of(), SlotPolicy.evictionPlan(e, 1, s -> false));
    }

    @Test
    void skyAlignmentPutsTheStarOnTheSun() {
        double[] star = {0.3, -0.2, 0.9}, pole = {0.1, 0.95, 0.2};
        for (double a = -3; a <= 3; a += 0.7) {
            double[] sun = SkyAlignment.vanillaSun(a);
            double[] m = SkyAlignment.matrix(star, pole, sun);
            double[] s = SkyAlignment.apply(m, BodyFrame.normalize(star.clone()));
            for (int i = 0; i < 3; i++) assertEquals(sun[i], s[i], 1e-9);
            double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
            assertEquals(1.0, det, 1e-9, "proper rotation");
        }
        // A pole perpendicular to the star lands exactly on vanilla's celestial pole, −Z.
        double[] m = SkyAlignment.matrix(new double[] {1, 0, 0}, new double[] {0, 1, 0}, SkyAlignment.vanillaSun(0.4));
        double[] p = SkyAlignment.apply(m, new double[] {0, 1, 0});
        assertEquals(-1, p[2], 1e-9);
    }
}
