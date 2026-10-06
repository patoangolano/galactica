package dev.galactica.exotic;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExoticCatalogTest {

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    void s2PeriapsisAndKeplerPeriod() {
        SkyOrbit s2 = ExoticCatalog.S2;
        // GRAVITY 2020: r_peri = a(1 − e) ≈ 120 AU.
        assertEquals(120, s2.periapsisAu(), 1.0);
        // Kepler's third law with M = 4.297e6 M☉ reproduces the measured 16.05 yr within 1 %.
        double p = SkyOrbit.keplerPeriodYears(s2.aAu(), ExoticCatalog.SGR_A_MASS);
        assertEquals(16.05, p, 0.01 * 16.05);
    }

    @Test
    void s2PositionHitsPeriAndApoapsis() {
        SkyOrbit s2 = ExoticCatalog.S2;
        double[] off = new double[3];
        double tp = SkyOrbit.epochToSeconds(s2.tPeriYear());
        s2.galacticOffsetAu(tp, 0, 0, 0, off);
        assertEquals(s2.periapsisAu(), norm(off), 1e-6);
        s2.galacticOffsetAu(tp + 0.5 * s2.periodYears() * Units.SECONDS_PER_YEAR, 0, 0, 0, off);
        assertEquals(s2.apoapsisAu(), norm(off), 1e-6);
        // One full period later it is back at periapsis.
        double[] again = new double[3];
        s2.galacticOffsetAu(tp + s2.periodYears() * Units.SECONDS_PER_YEAR, 0, 0, 0, again);
        assertEquals(s2.periapsisAu(), norm(again), 1e-6);
    }

    @Test
    void skyBasisIsOrthonormalAndPointsAway() {
        double[][] b = SkyOrbit.skyBasis(0, 0, 0);
        for (int i = 0; i < 3; i++) {
            assertEquals(1, norm(b[i]), 1e-12);
            for (int j = i + 1; j < 3; j++) assertEquals(0, SkyOrbit.dot(b[i], b[j]), 1e-12);
        }
        // Line of sight from the Sun to the GC points to −x in the galactocentric frame.
        assertTrue(b[2][0] < -0.99);
        // North at the GC has a positive z (towards the NGP) component: the NCP is at b = +27°.
        assertTrue(b[0][2] > 0);
    }

    @Test
    void s2PeriapsisRadialVelocityIsReceding() {
        // At periapsis v_z ∝ sin i · cos ω · (1 + e) > 0 for i = 133.8°, ω = 66.1°: S2 receded
        // at its 2018 periapsis passage (redshift peak, GRAVITY 2018 A&A 615, L15).
        SkyOrbit s2 = ExoticCatalog.S2;
        double tp = SkyOrbit.epochToSeconds(s2.tPeriYear());
        double[] a = new double[3], b = new double[3];
        s2.skyOffsetAu(tp - 3600, a);
        s2.skyOffsetAu(tp + 3600, b);
        assertTrue(b[2] > a[2]);
    }

    @Test
    void sStarsAreCatalogStarsAroundSgrA() {
        List<Star> stars = ExoticCatalog.sStars(SkyOrbit.epochToSeconds(2026.0));
        assertEquals(3, stars.size());
        Star c = StarCatalog.SGR_A_STAR;
        for (Star s : stars) {
            double dPc = s.distanceTo(c.x(), c.y(), c.z());
            assertTrue(dPc > 50 * Units.AU / Units.PARSEC && dPc < 7000 * Units.AU / Units.PARSEC, s.name() + " at " + dPc + " pc");
            assertTrue(s.catalog());
            assertTrue(s.id() <= -101 && s.id() >= -103);
            assertTrue(s.luminosity() > 100);
        }
        assertEquals("S2", stars.get(0).name());
        // S55 has the shortest period of the three (≈ 12.2 yr).
        assertEquals(12.2, ExoticCatalog.S55.periodYears(), 0.2);
        assertEquals(91, ExoticCatalog.S29.periodYears(), 1.5);
        // S2's radius from L and T_eff is a few R☉, as for a B0–2.5 V star.
        double r = ExoticCatalog.S_STARS.get(0).radiusSolar();
        assertTrue(r > 4 && r < 8, "S2 radius " + r);
    }

    @Test
    void keplerSolverIsExact() {
        for (double e : new double[] {0, 0.3, 0.884, 0.9693}) {
            for (double m = -7; m < 7; m += 0.37) {
                double ecc = SkyOrbit.eccentricAnomaly(m, e);
                double mm = ((m % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                assertEquals(mm, ecc - e * Math.sin(ecc), 1e-10);
            }
        }
    }

    @Test
    void sgrAFlowAxisIsNearlyAlongTheLineOfSight() {
        // i = 158°: the flow is seen almost face-on, rotating clockwise, so its axis points away from us.
        double[] h = ExoticObject.of(StarCatalog.SGR_A_STAR).axis();
        double[] los = SkyOrbit.skyBasis(0, 0, 0)[2];
        assertEquals(Math.cos(Math.toRadians(158)), -SkyOrbit.dot(h, los), 1e-9);
    }
}
