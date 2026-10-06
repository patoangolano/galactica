package dev.galactica.planet;

import dev.galactica.system.Body;
import org.junit.jupiter.api.Test;

import static dev.galactica.planet.SurfaceAtmosphereTest.sol;
import static org.junit.jupiter.api.Assertions.*;

/** Gravity-scaled fall rules, EVA descent, entry heating, pole crossing and the vacuum contract. */
class SurfaceRulesTest {

    /** Vanilla fall damage before armour: floor((h − safe) × multiplier). */
    private static int fallDamage(double h, double g) {
        return (int) Math.floor(Math.max(0, h - SurfaceGravity.safeFallDistance(g)) * SurfaceGravity.fallDamageMultiplier(g));
    }

    @Test
    void jumpingNeverHurtsOnAnyLandableBody() {
        assertEquals(1.25, SurfaceGravity.jumpApex(1.0), 0.01);
        assertEquals(4.97, SurfaceGravity.jumpApex(0.165), 0.05);
        for (String name : new String[] {"Moon", "Mars", "Titan", "Europa", "Phobos", "Earth", "Venus"}) {
            double g = sol(name).surfaceGravityG();
            double apex = SurfaceGravity.jumpApex(g);
            assertTrue(apex > 1.0 && apex < SurfaceGravity.safeFallDistance(g), name + " apex " + apex);
            assertEquals(0, fallDamage(apex, g), name);
        }
    }

    @Test
    void fallDamageFollowsImpactEnergy() {
        assertEquals(3.0, SurfaceGravity.safeFallDistance(1.0), 1e-12);
        assertEquals(1.0, SurfaceGravity.fallDamageMultiplier(1.0), 1e-12);
        // 61 blocks on the Moon hits like 10 blocks on Earth: 7 damage both.
        assertEquals(fallDamage(10, 1.0), fallDamage(10 / 0.165, 0.165));
        assertEquals(fallDamage(23, 1.0), fallDamage(23 / 0.38, 0.38));
        // A 2.5 g super-Earth: a 2-block drop already hurts.
        assertTrue(fallDamage(2, 2.5) > 0);
        // Tiny moons stay inside the attribute ranges.
        assertTrue(SurfaceGravity.safeFallDistance(1e-4) <= 1024);
        assertTrue(SurfaceGravity.fallDamageMultiplier(1e-4) > 0);
    }

    @Test
    void evaDescentEndsOnTouchdownClimbOrTimeout() {
        assertFalse(EvaDescent.ends(false, 300, 300, 200));
        assertFalse(EvaDescent.ends(false, 301.5, 300, 200));
        assertTrue(EvaDescent.ends(true, 120, 120, 1500));
        assertTrue(EvaDescent.ends(false, 302.5, 300, 200));
        assertTrue(EvaDescent.ends(false, 300, 300, EvaDescent.MAX_TICKS + 1));
        // The timeout outlasts a straight creative-speed drop from Y 600 to sea level (≈ 0.375 blocks/tick).
        assertTrue(EvaDescent.MAX_TICKS > (PlanetEntry.ENTRY_Y - 96) / 0.375);
    }

    @Test
    void entryHeatingNeedsAirAndFades() {
        assertEquals(0, EntryHeating.intensity(sol("Mars").surfacePressureBar(), 0));
        assertEquals(0, EntryHeating.intensity(0.0, 0));
        assertEquals(0, EntryHeating.intensity(0.1, 0));
        double earth = EntryHeating.intensity(1.0, 0), venus = EntryHeating.intensity(92, 0), titan = EntryHeating.intensity(1.5, 0);
        assertTrue(earth > 0.3 && earth < venus && venus <= 1.0, earth + " " + venus);
        assertTrue(titan > earth);
        assertTrue(EntryHeating.intensity(1.0, 50) < earth);
        assertEquals(0, EntryHeating.intensity(1.0, EntryHeating.DURATION_TICKS));
        assertEquals(0, EntryHeating.intensity(Double.NaN, 0));
    }

    @Test
    void walkingOverAPoleArrivesOnTheFarMeridian() {
        SurfaceGrid g = SurfaceGrid.of(sol("Moon").radiusMetres());
        double pole = g.poleZ();
        assertNull(g.overPole(100, pole - 1));
        assertNull(g.overPole(100, -pole));
        double[] north = g.overPole(100, -pole - 3);
        assertEquals(-pole + 3, north[1], 1e-9);
        assertEquals(g.wrapX(100 + g.halfWidth()), north[0], 1e-9);
        assertEquals(g.latitude(-pole - 3 + 6), g.latitude(north[1]), 1e-12);
        assertEquals(Math.PI, Math.abs(g.longitude(north[0]) - g.longitude(100)), 1e-9);
        double[] south = g.overPole(-g.halfWidth() + 10, pole + 0.5);
        assertEquals(pole - 0.5, south[1], 1e-9);
        assertTrue(Math.abs(south[0]) <= g.halfWidth());
    }

    @Test
    void vacuumHasNoContactTemperature() {
        PlanetEnvironment v = PlanetEnvironment.VACUUM;
        assertFalse(v.hasContactTemperature());
        assertFalse(v.tempK() < 200 || v.tempK() > 330, "naive survival thresholds must not fire in open space");
        Body moon = sol("Moon");
        PlanetEnvironment surface = PlanetEnvironment.of(moon);
        assertTrue(surface.hasContactTemperature());
        assertEquals(moon.surfaceTemp(), surface.tempK());
    }
}
