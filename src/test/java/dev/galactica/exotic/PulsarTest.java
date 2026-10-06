package dev.galactica.exotic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PulsarTest {

    @Test
    void periodsFollowTheObservedPopulation() {
        int msp = 0, n = 4000;
        double logSum = 0;
        for (int k = 0; k < n; k++) {
            Pulsar p = Pulsar.fromSeed(k * 7919L + 13);
            assertTrue(p.periodS() >= 1.4e-3 && p.periodS() <= 13, "P = " + p.periodS());
            assertTrue(p.obliquityRad() >= 0 && p.obliquityRad() <= Math.PI / 2);
            assertEquals(Math.min(Math.toRadians(45), Math.toRadians(5.75) / Math.sqrt(p.periodS())), p.beamHalfRad(), 1e-12);
            if (p.periodS() < 0.03) msp++;
            else logSum += Math.log10(p.periodS());
        }
        assertEquals(Pulsar.MSP_FRACTION, msp / (double) n, 0.02);
        assertEquals(-0.30, logSum / (n - msp), 0.05);
    }

    @Test
    void deterministicFromSeed() {
        assertEquals(Pulsar.fromSeed(123), Pulsar.fromSeed(123));
        assertNotEquals(Pulsar.fromSeed(123), Pulsar.fromSeed(124));
    }

    @Test
    void beamSweepsOnlyObserversInsideTheCone() {
        Pulsar p = new Pulsar(1.0, Math.toRadians(45), Math.toRadians(5.75), 0);
        double[] spin = {0, 0, 1};
        // An observer 45° from the spin axis lies on the cone swept by the magnetic pole.
        double s = Math.sin(Math.toRadians(45));
        double[] onCone = {s, 0, s};
        double duty = p.dutyCycle(spin, onCone);
        // Beam width 2ρ on a circle of radius sin χ: duty ≈ 2ρ / (2π sin χ).
        assertEquals(2 * Math.toRadians(5.75) / (2 * Math.PI * s), duty, 0.01);
        // Looking straight down the spin axis, 45° from the pole, the beam never arrives.
        assertEquals(0, p.dutyCycle(spin, new double[] {0, 0, 1}));
        // Magnetic axis stays a unit vector at angle χ from the spin axis.
        double[] m = new double[3];
        p.magneticAxis(spin, 0.37, m);
        assertEquals(1, Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]), 1e-12);
        assertEquals(Math.cos(Math.toRadians(45)), m[2], 1e-12);
    }

    @Test
    void phaseRepeatsEveryPeriod() {
        Pulsar p = Pulsar.fromSeed(55);
        assertEquals(p.phase(10), p.phase(10 + 3 * p.periodS()), 1e-6);
    }
}
