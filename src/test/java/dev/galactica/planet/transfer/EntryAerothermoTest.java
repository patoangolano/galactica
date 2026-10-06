package dev.galactica.planet.transfer;

import dev.galactica.planet.EntryHeating;
import dev.galactica.system.Body;
import org.junit.jupiter.api.Test;

import static dev.galactica.planet.transfer.TransferAltitudeTest.sol;
import static org.junit.jupiter.api.Assertions.*;

class EntryAerothermoTest {

    @Test
    void earthSeaLevelDensityIsStandardAtmosphere() {
        // ISA sea level 1.225 kg/m³; Earth's model surface is a bit warmer than 288 K.
        assertEquals(1.2, EntryAerothermo.surfaceDensity(sol("Earth")), 0.06);
        assertEquals(0.02, EntryAerothermo.surfaceDensity(sol("Mars")), 0.01);
        assertEquals(0, EntryAerothermo.surfaceDensity(sol("Moon")));
    }

    @Test
    void suttonGravesMatchesTheFormulaAndScalings() {
        double k = EntryAerothermo.suttonGravesConstant(Body.Atmosphere.N2_O2);
        assertEquals(1.7415e-4, k, 0);
        double q = EntryAerothermo.heatFlux(k, 1e-3, 1.0, 7500);
        assertEquals(1.7415e-4 * Math.sqrt(1e-3) * Math.pow(7500, 3), q, 1e-6 * q);
        assertEquals(2.32e6, q, 0.01e6);
        assertEquals(8.0, EntryAerothermo.heatFlux(k, 1e-3, 1, 15000) / q, 1e-9); // ∝ v³
        assertEquals(2.0, EntryAerothermo.heatFlux(k, 4e-3, 1, 7500) / q, 1e-9); // ∝ √ρ
        assertEquals(2.0, EntryAerothermo.heatFlux(k, 1e-3, 0.25, 7500) / q, 1e-9); // ∝ 1/√r_n
        assertEquals(0, EntryAerothermo.heatFlux(k, 0, 1, 7500));
    }

    @Test
    void heatingCurveAlongABallisticEntryPeaksWhereTheoryPredicts() {
        // Allen–Eggers + Sutton–Graves: q(h) peaks at ρ* = β·sinγ/(3H).
        Body earth = sol("Earth");
        double rho0 = EntryAerothermo.surfaceDensity(earth), H = earth.scaleHeight();
        double beta = EntryAerothermo.SHIP_BALLISTIC, sinGamma = Math.sin(Math.toRadians(30)), vE = 7800;
        double b = EntryAerothermo.allenEggersB(rho0, H, beta, sinGamma);
        double bestQ = 0, bestH = 0;
        double previous = -1;
        boolean rising = true;
        for (double h = 120_000; h >= 0; h -= 50) {
            double v = vE * EntryAerothermo.allenEggersFactor(b, H, h);
            double q = EntryAerothermo.heatFlux(EntryAerothermo.suttonGravesConstant(earth.atmosphere()),
                    EntryAerothermo.density(rho0, H, h), 1.0, v);
            if (q > bestQ) {
                bestQ = q;
                bestH = h;
            }
            if (previous >= 0 && q < previous) rising = false;
            if (!rising) assertTrue(q <= previous + 1e-9, "single peak");
            previous = q;
        }
        double hStar = H * Math.log(rho0 / EntryAerothermo.peakHeatingDensity(beta, sinGamma, H));
        assertEquals(hStar, bestH, 100);
        assertTrue(bestQ > 5e5 && bestQ < 5e6, "Apollo-class peak, got " + bestQ);
        // Visual intensity is monotonic and saturates.
        assertEquals(0, EntryAerothermo.intensity(EntryAerothermo.Q_VISIBLE));
        assertEquals(1, EntryAerothermo.intensity(EntryAerothermo.Q_FULL * 3));
        assertTrue(EntryAerothermo.intensity(1e5) < EntryAerothermo.intensity(1e6));
    }

    @Test
    void wallTemperatureFollowsStefanBoltzmann() {
        double t = EntryAerothermo.wallTemperature(1e6, 0.85);
        assertEquals(1e6, 0.85 * 5.670374419e-8 * Math.pow(t, 4), 1);
        assertTrue(EntryAerothermo.wallTemperature(4e6, 0.85) > 2800);
    }

    @Test
    void handOffSpeedIsDraggedToTerminalButPoweredFlightIsKept() {
        Body earth = sol("Earth");
        double rho0 = EntryAerothermo.surfaceDensity(earth), H = earth.scaleHeight(), g = earth.surfaceGravity();
        double h = 14_000;
        double vt = EntryAerothermo.terminalVelocity(EntryAerothermo.SHIP_BALLISTIC, g, EntryAerothermo.density(rho0, H, h));
        double fast = EntryAerothermo.speedAt(7800, 0.5, rho0, H, EntryAerothermo.SHIP_BALLISTIC, g, h);
        assertTrue(fast >= vt && fast < vt * 1.25,"orbital entry arrives near terminal speed, got " + fast + " vs " + vt);
        assertEquals(120, EntryAerothermo.speedAt(120, 0.5, rho0, H, EntryAerothermo.SHIP_BALLISTIC, g, h), 1e-12);
        // Airless: nothing slows the craft.
        Body moon = sol("Moon");
        assertEquals(1600, EntryAerothermo.speedAt(1600, 0.5, EntryAerothermo.surfaceDensity(moon), 0, 350, 1.62, 3800), 1e-12);
    }

    @Test
    void billigShockSitsAheadAndSweepsBack() {
        double r = 1.0;
        assertEquals(r + EntryAerothermo.shockStandoff(r, 20), EntryAerothermo.shockAxial(r, 20, 0), 1e-12);
        assertTrue(EntryAerothermo.shockStandoff(r, 20) < EntryAerothermo.shockStandoff(r, 2));
        assertTrue(EntryAerothermo.shockAxial(r, 20, 3) < EntryAerothermo.shockAxial(r, 20, 1));
        assertEquals(0.143, EntryAerothermo.shockStandoff(1, 1e3), 1e-3);
    }

    @Test
    void glowHoldsAcrossTheSwitchAndCools() {
        double g = EntryHeating.glow(0, 0.9, 0.05);
        assertEquals(0.9, g, 1e-12);
        double later = EntryHeating.glow(g, 0, 1.5);
        assertEquals(0.9 / Math.E, later, 1e-9);
        assertEquals(0, EntryHeating.glow(1e-4, 0, 0.05));
    }
}
