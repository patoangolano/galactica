package dev.galactica;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.science.SeedRng;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.SolarSystem;
import dev.galactica.system.SystemFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemFrameTest {

    private static Star procedural(long id) {
        return new Star(id, "P" + id, 8000, 0, 20, StarKind.MAIN_SEQUENCE, 1, 1, 1, 5772, 4.6, 0, SeedRng.hash(42, id), false);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    void eclipticPoleIsTilted60DegreesFromGalacticNorth() {
        double[] n = SystemFrame.normalGalactic(StarCatalog.SOL);
        assertEquals(60.19, Math.toDegrees(Math.acos(n[2])), 0.01);
        // Galactocentric (−x_h, −y_h, z_h) back to heliocentric l, b of the north ecliptic pole.
        double l = Math.toDegrees(Math.atan2(-n[1], -n[0]));
        assertEquals(96.3833, l < 0 ? l + 360 : l, 0.01);
        assertEquals(29.8113, Math.toDegrees(Math.asin(n[2])), 0.01);
    }

    @Test
    void toGalacticIsAProperRotation() {
        for (Star s : new Star[] {StarCatalog.SOL, procedural(7), procedural(123_456)}) {
            double[] a = SystemFrame.toGalactic(s, new double[] {1, 0, 0});
            double[] b = SystemFrame.toGalactic(s, new double[] {0, 1, 0});
            double[] c = SystemFrame.toGalactic(s, new double[] {0, 0, 1});
            for (double[] v : new double[][] {a, b, c}) assertEquals(1, Math.sqrt(dot(v, v)), 1e-12);
            assertEquals(0, dot(a, b), 1e-12);
            assertEquals(0, dot(b, c), 1e-12);
            assertEquals(0, dot(a, c), 1e-12);
            double det = a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0]);
            assertEquals(1, det, 1e-12);
            // The system's y (Orbit's normal axis) is the plane normal.
            assertArrayEquals(SystemFrame.normalGalactic(s), b, 1e-15);
            double[] v = {3.1e11, -2.2e10, 7.7e10};
            assertEquals(Math.sqrt(dot(v, v)), Math.sqrt(dot(SystemFrame.toGalactic(s, v), SystemFrame.toGalactic(s, v))), 1e-3);
        }
    }

    @Test
    void earthStaysOneAuFromTheSun() {
        double[] p = new double[3];
        for (double t = 0; t < 3.2e7; t += 2.6e6) {
            SolarSystem.planets().get(2).orbit().positionAt(t, p);
            double[] g = SystemFrame.toGalactic(StarCatalog.SOL, p);
            double au = Math.sqrt(dot(g, g)) / Units.AU;
            assertTrue(au > 0.98 && au < 1.02, "Earth at " + au + " AU");
            // The orbit lies in the ecliptic plane.
            assertEquals(0, dot(g, SystemFrame.normalGalactic(StarCatalog.SOL)) / Units.AU, 1e-6);
        }
        GalacticPos near = Frames.nearEarth(0);
        double r = near.distanceMetres(Frames.SOL_POS) / Units.AU;
        assertTrue(r > 0.97 && r < 1.02, "nearEarth at " + r + " AU");
    }

    @Test
    void proceduralOrientationIsDeterministicAndDistinct() {
        Star a = procedural(99), b = procedural(100);
        double[] na = SystemFrame.normalGalactic(a);
        // Evict the cache slot by touching many other stars, then recompute.
        for (long i = 1000; i < 1300; i++) SystemFrame.normalGalactic(procedural(i));
        assertArrayEquals(na, SystemFrame.normalGalactic(procedural(99)), 0.0);
        assertTrue(dot(na, SystemFrame.normalGalactic(b)) < 0.999999);
    }

    @Test
    void proceduralNormalsAreIsotropic() {
        int n = 20_000;
        double sumAbsZ = 0, sx = 0, sy = 0, sz = 0;
        for (long i = 1; i <= n; i++) {
            double[] v = SystemFrame.normalGalactic(procedural(i));
            sumAbsZ += Math.abs(v[2]);
            sx += v[0];
            sy += v[1];
            sz += v[2];
        }
        assertEquals(0.5, sumAbsZ / n, 0.01);
        assertEquals(0, Math.sqrt(sx * sx + sy * sy + sz * sz) / n, 0.02);
    }
}
