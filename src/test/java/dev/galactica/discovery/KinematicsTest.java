package dev.galactica.discovery;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.science.spectra.Kinematics;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SolarSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KinematicsTest {

    @Test
    void rotationCurveIsFlatOutsideThreeKpcAndSolidInside() {
        assertEquals(233, Kinematics.circularSpeed(8178), 1e-9);
        assertEquals(233, Kinematics.circularSpeed(20_000), 1e-9);
        assertEquals(116.5, Kinematics.circularSpeed(1500), 1e-9);
        assertEquals(0, Kinematics.circularSpeed(0), 1e-9);
    }

    @Test
    void sunMovesClockwiseWithItsPeculiarMotion() {
        double[] v = Kinematics.solVelocity();
        // At (R0, 0) rotation is towards −y; V⊙ = 12.24 km/s adds to it, U⊙ = 11.1 km/s points at the GC (−x).
        assertEquals(-(233 + 12.24), v[1], 1e-6);
        assertEquals(-11.1, v[0], 0.5);
        assertEquals(7.25, v[2], 1e-9);
    }

    @Test
    void catalogStarsReadTheirMeasuredRadialVelocityFromTheSun() {
        for (String name : new String[] {"Sirius A", "Barnard's Star", "Betelgeuse", "Vega"}) {
            Star s = StarCatalog.byName(name);
            double[] los = {s.x() - StarCatalog.SOL.x(), s.y() - StarCatalog.SOL.y(), s.z() - StarCatalog.SOL.z()};
            double rv = Kinematics.radialVelocity(Kinematics.starVelocity(s), Kinematics.solVelocity(), los);
            assertEquals(Kinematics.catalogRadialVelocity(s.id()), rv, 1e-9, name);
        }
    }

    @Test
    void peculiarVelocitiesHaveTwentyKmPerSecondRms() {
        double sum = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            double[] p = Kinematics.peculiar(i * 7919L + 1);
            sum += p[0] * p[0] + p[1] * p[1] + p[2] * p[2];
        }
        assertEquals(20, Math.sqrt(sum / n), 0.5);
    }

    @Test
    void nearbyProceduralStarsHaveDiskLikeRadialVelocities() {
        Galaxy g = new Galaxy(Frames.GALAXY_SEED);
        SectorKey k = SectorKey.containing(8178 + 40, 30, 20);
        double sumSq = 0;
        int n = 0;
        for (int i = 0; i < g.starCount(k); i++) {
            Star s = g.star(k, i);
            double[] los = {s.x() - StarCatalog.SOL.x(), s.y() - StarCatalog.SOL.y(), s.z() - StarCatalog.SOL.z()};
            double rv = Kinematics.radialVelocity(Kinematics.starVelocity(s), Kinematics.solVelocity(), los);
            sumSq += rv * rv;
            n++;
        }
        assertTrue(n > 20);
        double rms = Math.sqrt(sumSq / n);
        assertTrue(rms > 5 && rms < 40, "1-D rms relative to the Sun should be ~10–25 km/s, was " + rms);
    }

    @Test
    void earthsOrbitalVelocityIsThirtyKmPerSecond() {
        Body earth = SolarSystem.planets().get(2);
        double[] v = ScanPhysics.orbitalVelocity(StarCatalog.SOL, earth, null, 0);
        double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / 1000;
        assertEquals(29.8, speed, 0.6);
    }
}
