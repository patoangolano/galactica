package dev.galactica.earthsky;

import dev.galactica.client.earthsky.BrightStars;
import dev.galactica.client.earthsky.EquatorialFrame;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.system.SystemFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquatorialFrameTest {

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] world(String star, double theta) {
        BrightStars.Entry e = BrightStars.byName(star);
        assertNotNull(e, star);
        return EquatorialFrame.starToWorld(EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(e.raDeg(), e.decDeg())), theta);
    }

    @Test
    void everyMappingIsAProperRotation() {
        double[][] m = EquatorialFrame.spaceToStarMatrix();
        assertEquals(1.0, EquatorialFrame.determinant(m), 1e-8);
        // Orthonormal: columns are unit and mutually perpendicular.
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double d = m[0][i] * m[0][j] + m[1][i] * m[1][j] + m[2][i] * m[2][j];
                assertEquals(i == j ? 1 : 0, d, 1e-8);
            }
        }
        // starToWorld at an arbitrary angle preserves handedness too.
        double[] x = EquatorialFrame.starToWorld(new double[] {1, 0, 0}, 0.7);
        double[] y = EquatorialFrame.starToWorld(new double[] {0, 1, 0}, 0.7);
        double[] z = EquatorialFrame.starToWorld(new double[] {0, 0, 1}, 0.7);
        assertEquals(1.0, EquatorialFrame.determinant(new double[][] {
                {x[0], y[0], z[0]}, {x[1], y[1], z[1]}, {x[2], y[2], z[2]}}), 1e-12);
    }

    @Test
    void galacticPolesAndCentreMatchIau() {
        // NGP at α = 192.85948°, δ = +27.12825°; GC at α = 266.40500°, δ = −28.93617° (ICRS, ESA 1997).
        double[] ngp = EquatorialFrame.equatorialToGalactic(EquatorialFrame.equatorial(192.85948, 27.12825));
        double[] gc = EquatorialFrame.equatorialToGalactic(EquatorialFrame.equatorial(266.40500, -28.93617));
        assertEquals(1.0, ngp[2], 1e-6);
        assertEquals(1.0, gc[0], 1e-6);
    }

    @Test
    void consistentWithSystemFrameEcliptic() {
        // SystemFrame derives the ecliptic pole from the same matrix; ours must agree (galactocentric directions).
        double e = Math.toRadians(EquatorialFrame.OBLIQUITY_J2000_DEG);
        double[] h = EquatorialFrame.equatorialToGalactic(new double[] {0, -Math.sin(e), Math.cos(e)});
        double[] mine = {-h[0], -h[1], h[2]};
        double[] theirs = SystemFrame.normalGalactic(StarCatalog.SOL);
        assertEquals(1.0, dot(mine, theirs), 1e-9);
    }

    @Test
    void polarisSitsOnTheNorthHorizonAllNight() {
        for (double theta = 0; theta < 2 * Math.PI; theta += 0.5) {
            double[] w = world("Polaris", theta);
            // Within 1° of world north (−Z): Polaris is 0.74° from the pole at J2000.
            assertTrue(dot(w, new double[] {0, 0, -1}) > Math.cos(Math.toRadians(1.0)), "theta " + theta);
        }
        // And the catalogue's Polaris (real l, b) lands within 0.1° of BrightStars' Polaris.
        double[] cat = galactocentricDir(StarCatalog.byName("Polaris"));
        BrightStars.Entry p = BrightStars.byName("Polaris");
        double[] b = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(p.raDeg(), p.decDeg()));
        double[] viaSpace = EquatorialFrame.spaceToStar(cat[0], cat[2], -cat[1]);
        assertTrue(dot(b, viaSpace) > Math.cos(Math.toRadians(0.1)));
    }

    private static double[] galactocentricDir(dev.galactica.star.Star s) {
        double gx = s.x() - StarCatalog.SOL.x(), gy = s.y() - StarCatalog.SOL.y(), gz = s.z() - StarCatalog.SOL.z();
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        return new double[] {gx / d, gy / d, gz / d};
    }

    @Test
    void starsRiseInTheEastAndSetInTheWest() {
        // A star on the celestial equator: θ slightly before it culminates it is east (+X), after it is west (−X).
        double[] b = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(0, 0));
        assertTrue(EquatorialFrame.starToWorld(b, -0.3)[0] > 0);
        assertTrue(EquatorialFrame.starToWorld(b, 0.3)[0] < 0);
        assertEquals(1.0, EquatorialFrame.starToWorld(b, 0)[1], 1e-12); // α = 0h at the zenith at θ = 0
    }

    @Test
    void orionIsNotMirrored() {
        // Find θ at which Alnilam crosses the meridian (world x = 0, above the horizon).
        BrightStars.Entry alnilam = BrightStars.byName("Alnilam");
        double[] b = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(alnilam.raDeg(), alnilam.decDeg()));
        double theta = Math.atan2(-b[2], b[1]); // rotates b's (y, z) onto +y
        double[] mid = EquatorialFrame.starToWorld(b, theta);
        assertEquals(0.0, mid[0], 1e-9);
        assertTrue(mid[1] > 0.99);

        double[] alnitak = world("Alnitak", theta), mintaka = world("Mintaka", theta);
        double[] betelgeuse = world("Betelgeuse", theta), rigel = world("Rigel", theta);
        // Facing south (+Z) at culmination, east (+X) is on the left. Higher RA is further east:
        // Alnitak (5h40m) east of Alnilam east of Mintaka (5h32m); Betelgeuse (5h55m) east of Rigel (5h14m).
        assertTrue(alnitak[0] > mid[0] && mid[0] > mintaka[0], "belt order");
        assertTrue(betelgeuse[0] > rigel[0], "Betelgeuse east of Rigel");
        // Northern stars are towards −Z: Betelgeuse (+7°) north of the belt, Rigel (−8°) south, Mintaka north of Alnitak.
        assertTrue(betelgeuse[2] < mid[2] && mid[2] < rigel[2], "north-south");
        assertTrue(mintaka[2] < alnitak[2], "belt slope");
        // Handedness invariant: the sense of the triangle Betelgeuse → Rigel → Alnilam as seen from
        // inside the sphere matches the real sky's (same sign of the triple product as in equatorial axes).
        double realSign = Math.signum(triple(eq("Betelgeuse"), eq("Rigel"), eq("Alnilam")));
        assertEquals(realSign, Math.signum(triple(betelgeuse, rigel, mid)));
    }

    private static double[] eq(String star) {
        BrightStars.Entry e = BrightStars.byName(star);
        return EquatorialFrame.equatorial(e.raDeg(), e.decDeg());
    }

    private static double triple(double[] a, double[] b, double[] c) {
        return a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0]);
    }

    @Test
    void catalogueStarsAgreeBetweenBothSources() {
        // StarCatalog (galactic l, b) and BrightStars (RA, Dec) must put the same star at the same place.
        for (String[] pair : new String[][] {{"Sirius A", "Sirius"}, {"Betelgeuse", "Betelgeuse"}, {"Rigel", "Rigel"},
                {"Vega", "Vega"}, {"Deneb", "Deneb"}, {"Antares", "Antares"}}) {
            assertTrue(separationDeg(pair[0], pair[1]) < 0.1, pair[1]);
        }
        // StarCatalog lists Arcturus at l = 15.38°; SIMBAD gives l = 15.03°, b = 69.10° — 0.13° on the sky.
        assertTrue(separationDeg("Arcturus", "Arcturus") < 0.2);
    }

    private static double separationDeg(String catalogName, String brightName) {
        double[] g = galactocentricDir(StarCatalog.byName(catalogName));
        double[] viaSpace = EquatorialFrame.spaceToStar(g[0], g[2], -g[1]);
        BrightStars.Entry e = BrightStars.byName(brightName);
        double[] b = EquatorialFrame.equatorialToStar(EquatorialFrame.equatorial(e.raDeg(), e.decDeg()));
        return Math.toDegrees(Math.acos(Math.min(1, dot(b, viaSpace))));
    }

    @Test
    void sunRightAscensionThroughTheYear() {
        assertEquals(0.0, EquatorialFrame.sunRightAscensionDeg(0), 1e-9);
        assertEquals(90.0, EquatorialFrame.sunRightAscensionDeg(0.25), 1e-9);
        assertEquals(180.0, EquatorialFrame.sunRightAscensionDeg(0.5), 1e-9);
        assertEquals(270.0, EquatorialFrame.sunRightAscensionDeg(0.75), 1e-9);
        // Equation of time from obliquity: at λ = 45°, α = atan(cos ε) = 42.536°.
        assertEquals(42.536, EquatorialFrame.sunRightAscensionDeg(0.125), 0.001);
    }
}
