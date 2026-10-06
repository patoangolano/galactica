package dev.galactica.discovery;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import dev.galactica.system.SolarSystem;
import dev.galactica.system.SystemGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SurveyAndLedgerTest {

    @Test
    void radialVelocitySemiAmplitudesOfTheSolarSystem() {
        Body earth = SolarSystem.planets().get(2), jupiter = SolarSystem.planets().get(4);
        assertEquals(0.0895, SurveyMath.semiAmplitude(StarCatalog.SOL, earth, 1), 0.003);
        assertEquals(12.5, SurveyMath.semiAmplitude(StarCatalog.SOL, jupiter, 1), 0.3);
    }

    @Test
    void earthTransitProbabilityIsHalfAPercent() {
        Body earth = SolarSystem.planets().get(2);
        // (R☉ + R⊕)/a = 0.00469; eccentricity factor ≈ 1.
        assertEquals(0.00469, SurveyMath.transitProbability(StarCatalog.SOL, earth), 0.0002);
    }

    @Test
    void transitFractionMatchesGeometricProbability() {
        Galaxy g = new Galaxy(Frames.GALAXY_SEED);
        double expected = 0, observed = 0;
        int planets = 0;
        for (int dx = 0; dx < 6 && planets < 3000; dx++) {
            SectorKey k = SectorKey.containing(8178 + 30 + dx * 10, 40, 20);
            for (int i = 0; i < g.starCount(k); i++) {
                Star s = g.star(k, i);
                for (Body p : SystemGenerator.generate(s)) {
                    expected += SurveyMath.transitProbability(s, p);
                    if (SurveyMath.transits(s, p)) observed++;
                    planets++;
                }
            }
        }
        assertTrue(planets > 300, "sample " + planets);
        double sigma = Math.sqrt(expected);
        assertEquals(expected, observed, 4 * sigma + 2, "transits are a fair draw of p = R★/a");
    }

    @Test
    void surveyIsDeterministicAndRespectsLimits() {
        List<Body> sol = SolarSystem.planets();
        var a = SurveyMath.survey(StarCatalog.SOL, sol);
        var b = SurveyMath.survey(StarCatalog.SOL, sol);
        assertEquals(a, b);
        for (SurveyMath.Detection d : a) {
            assertTrue(d.periodDays() < 3653);
            assertTrue(d.transit() || d.kMs() > SurveyMath.RV_LIMIT_MS);
            assertNotEquals(2, d.planetIndex(), "Earth's 0.09 m/s cannot be seen and it transits only 0.5 % of the time");
        }
        Star bh = new Star(1, "BH", 0, 0, 0, StarKind.BLACK_HOLE, 10, 1e-5, 0, 0, 1, 0, 1, false);
        assertTrue(SurveyMath.survey(bh, sol).isEmpty());
    }

    @Test
    void rarityOrdersThePoints() {
        int o = SurveyValue.starPoints(star(40_000)), b = SurveyValue.starPoints(star(20_000)),
                a = SurveyValue.starPoints(star(9_000)), g = SurveyValue.starPoints(star(5_800)),
                m = SurveyValue.starPoints(star(3_200));
        assertTrue(o > b && b > a && a > g && g > m);
        assertEquals(1, m);
        Body earth = SolarSystem.planets().get(2), mars = SolarSystem.planets().get(3);
        assertTrue(SurveyValue.bodyPoints(earth, false) >= 100, "biosignature world");
        assertTrue(SurveyValue.bodyPoints(mars, false) < 10);
        assertEquals(5, SurveyValue.affordableExotic(5 * SurveyValue.POINTS_PER_EXOTIC + SurveyValue.POINTS_PER_EXOTIC - 1));
    }

    private static Star star(double t) {
        return new Star(1, "x", 8000, 0, 0, StarKind.MAIN_SEQUENCE, 1, 1, 1, t, 1, 0, 1, false);
    }

    @Test
    void ledgerPaysFirstDiscovererOnceAndSurveysThenCharacterises() {
        DiscoveryLedger l = new DiscoveryLedger();
        UUID alice = new UUID(1, 1), bob = new UUID(2, 2);
        var first = l.recordScan("s1", DiscoveryLedger.KIND_STAR, "Star 1", "", "G2V", alice, "Alice", 100, 5);
        assertTrue(first.first());
        assertEquals(5, first.awarded());
        var again = l.recordScan("s1", DiscoveryLedger.KIND_STAR, "Star 1", "", "G2V", bob, "Bob", 200, 5);
        assertFalse(again.first());
        assertEquals(0, again.awarded());
        assertEquals(alice, again.entry().discoverer());

        var survey = l.recordSurvey("b1", DiscoveryLedger.KIND_PLANET, "Star 1 b", "Star 1", "Hot Jupiter", alice, "Alice", 100, 11,
                DiscoveryLedger.METHOD_TRANSIT);
        assertEquals(6, survey.awarded());
        assertFalse(survey.entry().characterized());
        var characterise = l.recordScan("b1", DiscoveryLedger.KIND_PLANET, "Star 1 b", "Star 1", "Hot Jupiter", bob, "Bob", 300, 11);
        assertEquals(5, characterise.awarded(), "the characteriser earns the rest");
        assertTrue(characterise.entry().characterized());
        assertEquals(alice, characterise.entry().discoverer());
        assertEquals("transit+spectrum", characterise.entry().method());

        assertEquals(11, l.account(alice).balance());
        assertEquals(5, l.account(bob).balance());
        assertTrue(l.spend(alice, 10));
        assertFalse(l.spend(alice, 2));
        assertEquals(1, l.account(alice).balance());
        assertEquals(11, l.account(alice).lifetime());
    }

    @Test
    void onlyTheFirstDiscovererMayRenameWithAValidUniqueName() {
        DiscoveryLedger l = new DiscoveryLedger();
        UUID alice = new UUID(1, 1), bob = new UUID(2, 2);
        l.recordScan("s1", 0, "HD 1", "", "", alice, "Alice", 0, 1);
        l.recordScan("s2", 0, "HD 2", "", "", bob, "Bob", 0, 1);
        assertEquals(DiscoveryLedger.RenameResult.NOT_DISCOVERER, l.rename("s1", bob, "Mine"));
        assertEquals(DiscoveryLedger.RenameResult.BAD_NAME, l.rename("s1", alice, "§cRed"));
        assertEquals(DiscoveryLedger.RenameResult.BAD_NAME, l.rename("s1", alice, "x".repeat(40)));
        assertEquals(DiscoveryLedger.RenameResult.TAKEN, l.rename("s1", alice, "hd 2"));
        assertEquals(DiscoveryLedger.RenameResult.OK, l.rename("s1", alice, "  Nova   Terra "));
        assertEquals("Nova Terra", l.get("s1").displayName());
        assertEquals(DiscoveryLedger.RenameResult.TAKEN, l.rename("s2", bob, "NOVA TERRA"));
        assertEquals(DiscoveryLedger.RenameResult.NOT_FOUND, l.rename("s9", alice, "X"));
        assertEquals("Nova Terra", l.customNames().get("s1"));
    }

    @Test
    void keysAndNames() {
        assertEquals("s-1", DiscoveryKeys.star(StarCatalog.SOL));
        assertTrue(DiscoveryKeys.isStar(DiscoveryKeys.star(5)));
        assertFalse(DiscoveryKeys.isStar(DiscoveryKeys.body(5)));
        assertEquals("Ária-3 (b)", DiscoveryKeys.cleanName("Ária-3 (b)"));
        assertNull(DiscoveryKeys.cleanName("   "));
        assertNull(DiscoveryKeys.cleanName("a\u0007b"));
        assertEquals("a b", DiscoveryKeys.cleanName("a\n b"));
    }

    @Test
    void scanRules() {
        assertEquals(90, ScanRules.angleDeg(new double[] {1, 0, 0}, new double[] {0, 2, 0}), 1e-9);
        assertTrue(ScanRules.starVisible(StarCatalog.byName("Sirius A"), 2.64));
        assertFalse(ScanRules.starVisible(StarCatalog.byName("Cygnus X-1"), 2220), "black holes have no light to disperse");
        assertFalse(ScanRules.starVisible(StarCatalog.byName("Proxima Centauri"), 200), "an M dwarf at 200 pc is far too faint");
        assertTrue(ScanRules.starVisible(StarCatalog.byName("Deneb"), 802), "catalog stars are scannable at any distance, like the sky lists them");
        assertTrue(ScanRules.starVisible(StarCatalog.byName("Eta Carinae"), 2300));
        Star farBright = new Star(9, "far", 8000, 0, 0, StarKind.SUPERGIANT, 20, 100, 1e5, 9000, 1, 0, 7, false);
        assertFalse(ScanRules.starVisible(farBright, ScanRules.MAX_STAR_PC + 50), "procedural stars stop at the luminous shell");
        assertTrue(ScanRules.acceptDeg(6.96e8, 1.5e11) == ScanRules.ACCEPT_DEG);
        assertTrue(ScanRules.acceptDeg(6.4e6, 1.0e7) > 40, "a planet filling the view is accepted across its disc");
    }
}
