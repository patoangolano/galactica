package dev.galactica.map;

import dev.galactica.client.map.MapCamera;
import dev.galactica.client.map.MapPicking;
import dev.galactica.nav.Conics;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.OrbitalFrame;
import dev.galactica.science.Units;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapCameraTest {
    private static final GalacticPos SUN = GalacticPos.ofParsecs(8178, 0, 20.8);

    private static MapCamera camera(double distance) {
        MapCamera c = new MapCamera(SUN, distance, Math.toRadians(30), Math.toRadians(25));
        c.setViewport(1600, 900);
        return c;
    }

    @Test
    void theFocusProjectsToTheCentreAtEveryScale() {
        for (double d : new double[] {2e5, Units.AU, Units.PARSEC, 30_000 * Units.PARSEC}) {
            MapCamera c = camera(d);
            double[] p = c.project(SUN);
            assertEquals(800, p[0], 1e-6, "x at " + d);
            assertEquals(450, p[1], 1e-6, "y at " + d);
            assertEquals(1, p[2], 1e-9, "the eye sits one camera unit away");
            assertEquals(d, c.eye().distanceMetres(SUN), 1e-6 * d);
        }
    }

    @Test
    void projectionKeepsPrecisionKiloparsecsFromTheOrigin() {
        // The same offset seen by a 1 000 km camera at the Galactic Centre and 8 kpc out lands on the same pixel.
        MapCamera far = camera(1e6), home = new MapCamera(GalacticPos.ofParsecs(0, 0, 0), 1e6, far.yaw(), far.pitch());
        home.setViewport(1600, 900);
        double[] pa = far.project(SUN.plusMetres(1e5, -2e5, 3e5)), pb = home.project(GalacticPos.ofParsecs(0, 0, 0).plusMetres(1e5, -2e5, 3e5));
        assertEquals(pb[0], pa[0], 1e-3);
        assertEquals(pb[1], pa[1], 1e-3);
        assertTrue(pa[1] < 450 && pa[1] > 0, "above the centre, on screen");
    }

    @Test
    void upAndRightLandWhereExpected() {
        MapCamera c = camera(Units.AU);
        Vector3d r = c.right(), u = c.up();
        double[] pr = c.project(SUN.plusMetres(r.x * 1e10, r.y * 1e10, r.z * 1e10));
        double[] pu = c.project(SUN.plusMetres(u.x * 1e10, u.y * 1e10, u.z * 1e10));
        assertTrue(pr[0] > 800 && Math.abs(pr[1] - 450) < 1e-3, "camera right is screen right");
        assertTrue(pu[1] < 450 && Math.abs(pu[0] - 800) < 1e-3, "camera up is screen up");
        Vector3d e = c.toEye();
        assertNull(c.project(c.eye().plusMetres(e.x * 1e11, e.y * 1e11, e.z * 1e11)), "behind the eye: no screen position");
    }

    @Test
    void zoomIsLogarithmicAndClamped() {
        MapCamera c = camera(Units.AU);
        c.zoom(4);
        assertEquals(Units.AU / Math.pow(MapCamera.ZOOM_STEP, 4), c.distanceTarget(), 1e-6 * Units.AU);
        c.zoom(-1000);
        assertEquals(MapCamera.MAX_DISTANCE, c.distanceTarget());
        c.zoom(1000);
        assertEquals(MapCamera.MIN_DISTANCE, c.distanceTarget());
        for (int i = 0; i < 200; i++) c.update(0.016);
        assertEquals(MapCamera.MIN_DISTANCE, c.distance(), 1e-3 * MapCamera.MIN_DISTANCE, "eases to the target");
        assertFalse(c.moving());
    }

    @Test
    void orbitClampsPitchAndPanFollowsTheCursor() {
        MapCamera c = camera(Units.AU);
        c.orbit(0, 10_000);
        assertTrue(c.pitch() <= Math.toRadians(89.0001));
        MapCamera p = camera(Units.AU);
        double perPx = 1 / p.pixelsPerMetreAtFocus();
        p.pan(-100, 0); // drag left by 100 px: the focus moves right by 100 px worth
        for (int i = 0; i < 300; i++) p.update(0.016);
        assertEquals(100 * perPx, p.focus().distanceMetres(SUN), 1e-3 * 100 * perPx);
        double[] old = p.project(SUN);
        assertEquals(700, old[0], 0.5, "the old focus is now 100 px left of centre");
    }

    @Test
    void longFlightsZoomOutOnTheWay() {
        MapCamera c = camera(Units.AU);
        GalacticPos far = GalacticPos.ofParsecs(8178 + 5, 0, 20.8);
        c.flyTo(far, 2 * Units.AU);
        double maxD = 0;
        for (int i = 0; i < 400; i++) {
            c.update(0.016);
            maxD = Math.max(maxD, c.distance());
        }
        assertTrue(maxD > Units.PARSEC, "pulled out to " + maxD / Units.PARSEC + " pc");
        assertEquals(0, c.focus().distanceMetres(far), Units.AU * 1e-3);
        assertEquals(2 * Units.AU, c.distance(), 0.01 * Units.AU);
    }

    @Test
    void pickingPrefersTheMarkerUnderTheCursorThenImportance() {
        var star = new MapPicking.Hit<>("star", 100, 100, 6, 100);
        var planet = new MapPicking.Hit<>("planet", 103, 100, 3, 80);
        var field = new MapPicking.Hit<>("field", 140, 100, 2, 1);
        assertEquals("planet", MapPicking.pick(List.of(star, planet, field), 101, 100).object(), "inside both: the smaller, the planet");
        assertEquals("star", MapPicking.pick(List.of(star, planet, field), 96, 100).object(), "inside the star, only near the planet");
        assertEquals("field", MapPicking.pick(List.of(star, planet, field), 144, 101).object(), "within the slack");
        assertNull(MapPicking.pick(List.of(star, planet, field), 300, 300));
    }

    @Test
    void labelsNeverOverlapAndTheImportantComeFirst() {
        var a = new MapPicking.Label<>("Sol", "Sol", 10, 10, 30, 9, 100);
        var b = new MapPicking.Label<>("GX", "GX S+0817", 15, 12, 60, 9, 1);
        var c = new MapPicking.Label<>("Vega", "Vega", 10, 40, 30, 9, 50);
        List<MapPicking.Label<String>> placed = MapPicking.placeLabels(List.of(b, a, c), 10);
        assertEquals(List.of(a, c), placed);
        assertEquals(1, MapPicking.placeLabels(List.of(a, b, c), 1).size());
        double[] bar = MapPicking.scaleBar(0.01, 100); // 100 px = 10 000 m
        assertEquals(10_000, bar[0], 1e-9);
        assertEquals(100, bar[1], 1e-9);
        assertEquals(5_000, MapPicking.scaleBar(0.01, 60)[0], 1e-9, "1-2-5 steps");
    }

    @Test
    void conicsTraceTheOrbitTheApsidesDescribe() {
        double mu = Units.G * Units.EARTH_MASS, r0 = 7e6;
        double[] r = {r0, 0, 0};
        double vc = Math.sqrt(mu / r0);
        double[] circle = Conics.points(r, new double[] {0, vc, 0}, mu, 64, 1e12);
        for (int i = 0; i < 64; i++) {
            double rr = Math.sqrt(circle[i * 3] * circle[i * 3] + circle[i * 3 + 1] * circle[i * 3 + 1] + circle[i * 3 + 2] * circle[i * 3 + 2]);
            assertEquals(r0, rr, 1e-6 * r0);
        }
        double[] v = {0, 1.2 * vc, 0.1 * vc};
        double[] aps = OrbitalFrame.apsides(r, v, mu);
        double[] ellipse = Conics.points(r, v, mu, 721, 1e12);
        double min = Double.MAX_VALUE, max = 0;
        for (int i = 0; i < 721; i++) {
            double rr = Math.sqrt(ellipse[i * 3] * ellipse[i * 3] + ellipse[i * 3 + 1] * ellipse[i * 3 + 1] + ellipse[i * 3 + 2] * ellipse[i * 3 + 2]);
            min = Math.min(min, rr);
            max = Math.max(max, rr);
        }
        assertEquals(aps[0], min, 1e-4 * aps[0], "periapsis");
        assertEquals(aps[1], max, 1e-3 * aps[1], "apoapsis");
        double[] escape = Conics.points(r, new double[] {0, 2 * vc, 0}, mu, 50, 1e8);
        for (int i = 0; i < 50; i++) {
            double rr = Math.sqrt(escape[i * 3] * escape[i * 3] + escape[i * 3 + 1] * escape[i * 3 + 1] + escape[i * 3 + 2] * escape[i * 3 + 2]);
            assertTrue(rr <= 1e8 * 1.000001, "open branch clipped at the max radius");
        }
        assertEquals(0, Conics.points(r, new double[] {-100, 0, 0}, mu, 10, 1e9).length, "radial fall: no conic to draw");
    }
}
