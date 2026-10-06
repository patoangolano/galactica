package dev.galactica.ship;

import org.junit.jupiter.api.Test;

import static dev.galactica.ship.ExhaustPhysics.*;
import static org.junit.jupiter.api.Assertions.*;

class ExhaustPhysicsTest {
    private static final double R = 0.275;

    @Test
    void isentropicRelationsMatchTheTextbook() {
        // γ = 1.2, M = 3: 1 + 0.1·9 = 1.9; p0/p = 1.9^6, T0/T = 1.9.
        assertEquals(Math.pow(1.9, 6), pressureRatio(3), 1e-9);
        assertEquals(1.9, temperatureRatio(3), 1e-12);
        assertEquals(1.0, areaRatio(1), 1e-12, "sonic throat");
        assertEquals(Math.pow(2 / 2.2 * 1.9, 5.5) / 3, areaRatio(3), 1e-9);
        assertTrue(areaRatio(4) > areaRatio(3) && areaRatio(3) > areaRatio(2));
    }

    @Test
    void fullyExpandedMachInvertsThePressureRatio() {
        double pe = CHAMBER_PRESSURE_BAR / pressureRatio(EXIT_MACH);
        assertEquals(EXIT_MACH, fullyExpandedMach(CHAMBER_PRESSURE_BAR, pe), 1e-9);
        assertEquals(0, fullyExpandedMach(1, 2), "no flow into higher pressure");
    }

    @Test
    void prandtlMeyerAndTheVacuumFan() {
        assertEquals(0, prandtlMeyer(1), 0);
        assertEquals(1.111, prandtlMeyer(3), 2e-3, "ν(3) for γ = 1.2");
        double limit = vacuumTurningLimit();
        assertEquals(Math.toRadians(145), limit, Math.toRadians(1.5));
        double quarter = simonsHalfAngle(0.25);
        assertTrue(quarter > 0 && quarter < limit);
        assertTrue(simonsHalfAngle(0.05) > quarter, "fainter contours lie further out");
    }

    @Test
    void engineOffDrawsNothing() {
        assertSame(Plume.OFF, plume(0, 1, R));
        assertSame(Plume.OFF, plume(0.01, 1, R));
        assertSame(Plume.OFF, plume(Double.NaN, 1, R));
        assertEquals(0, Plume.OFF.radiusAt(1));
    }

    @Test
    void vacuumPlumeIsWideFaintAndHasNoDiamonds() {
        Plume vac = plume(1, 0, R);
        Plume air = plume(1, 1.01325, R);
        assertEquals(Regime.VACUUM, vac.regime());
        assertEquals(0, vac.shockCells());
        assertTrue(vac.halfAngleRad() > air.halfAngleRad());
        assertTrue(vac.halfAngleRad() <= MAX_VISIBLE_HALF_ANGLE_RAD + 1e-12);
        assertTrue(vac.length() > air.length());
        assertTrue(vac.intensity() < air.intensity());
        assertEquals(Regime.VACUUM, plume(1, DIAMOND_MIN_PRESSURE_BAR * 0.5, R).regime());
    }

    @Test
    void seaLevelFullThrottleIsUnderExpandedWithPrandtlPackCells() {
        Plume p = plume(1, 1.01325, R);
        assertEquals(Regime.UNDER_EXPANDED, p.regime());
        assertEquals(CHAMBER_PRESSURE_BAR / pressureRatio(EXIT_MACH), p.exitPressureBar(), 1e-9);
        assertEquals(2.126, p.exitPressureBar(), 1e-3);
        assertTrue(p.jetMach() > EXIT_MACH, "an under-expanded jet keeps expanding");
        assertTrue(p.jetRadius() > R);
        double beta = Math.sqrt(p.jetMach() * p.jetMach() - 1);
        assertEquals(1.306 * beta * 2 * p.jetRadius(), p.shockSpacing(), 1e-9);
        assertTrue(p.shockCells() >= 1 && p.shockCells() <= 8, "visible diamonds: " + p.shockCells());
        assertEquals(CHAMBER_TEMPERATURE_K / 1.9, p.exitTemperatureK(), 1e-9);
    }

    @Test
    void lowThrottleAtSeaLevelIsOverExpanded() {
        Plume p = plume(0.3, 1.01325, R);
        assertEquals(Regime.OVER_EXPANDED, p.regime());
        assertTrue(p.jetMach() < EXIT_MACH);
        assertTrue(p.jetRadius() < R, "an over-expanded jet necks down");
    }

    @Test
    void lengthAndBrightnessFollowThrottle() {
        for (double pa : new double[] {0, 1.01325, 90}) {
            Plume lo = plume(0.25, pa, R), hi = plume(1, pa, R);
            assertTrue(hi.length() > lo.length(), "length at " + pa);
            assertTrue(hi.intensity() > lo.intensity(), "brightness at " + pa);
        }
    }

    @Test
    void radiusProfileStartsAtTheExitAndGrows() {
        for (double pa : new double[] {0, 0.3, 1.01325}) {
            Plume p = plume(1, pa, R);
            assertEquals(R, p.radiusAt(0), 1e-12);
            assertTrue(p.radiusAt(p.length()) > p.radiusAt(p.length() * 0.5));
        }
    }

    @Test
    void barometricFormula() {
        assertEquals(1.01325 / Math.E, barometric(1.01325, 8500, 8500), 1e-12);
        assertEquals(0, barometric(0, 10, 8500));
        assertEquals(92 * Math.exp(-0.5), barometric(92, 7900, 15800), 1e-9, "Venus-like");
    }
}
