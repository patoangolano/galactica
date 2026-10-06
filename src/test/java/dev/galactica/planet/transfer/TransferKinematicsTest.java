package dev.galactica.planet.transfer;

import dev.galactica.planet.transfer.TransferKinematics.LocalFrame;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TransferKinematicsTest {

    private static double[] unit(Random r) {
        double[] v = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / n, v[1] / n, v[2] / n};
    }

    @Test
    void frameIsOrthonormalAndRightHanded() {
        Random r = new Random(1);
        for (int k = 0; k < 200; k++) {
            LocalFrame f = LocalFrame.at(unit(r), unit(r));
            assertEquals(1, TransferKinematics.dot(f.east(), f.east()), 1e-12);
            assertEquals(1, TransferKinematics.dot(f.north(), f.north()), 1e-12);
            assertEquals(0, TransferKinematics.dot(f.east(), f.north()), 1e-12);
            assertEquals(0, TransferKinematics.dot(f.east(), f.up()), 1e-12);
            // east × north = up
            double[] c = TransferKinematics.cross(f.east(), f.north());
            for (int i = 0; i < 3; i++) assertEquals(f.up()[i], c[i], 1e-12);
        }
    }

    @Test
    void frameSurvivesThePole() {
        double[] pole = {0, 0, 1};
        LocalFrame f = LocalFrame.at(pole, new double[] {0, 0, 1});
        assertEquals(1, TransferKinematics.dot(f.east(), f.east()), 1e-12);
        assertEquals(0, TransferKinematics.dot(f.east(), f.up()), 1e-12);
    }

    @Test
    void velocityRoundTripSurfaceAndSpace() {
        Random r = new Random(2);
        for (int k = 0; k < 500; k++) {
            LocalFrame f = LocalFrame.at(unit(r), unit(r));
            double mpb = 1 + r.nextDouble() * 9;
            double[] vGal = {r.nextGaussian() * 300, r.nextGaussian() * 300, r.nextGaussian() * 300};
            double[] back = TransferKinematics.fromSurface(TransferKinematics.toSurface(vGal, f, mpb), f, mpb);
            for (int i = 0; i < 3; i++) assertEquals(vGal[i], back[i], 1e-9);
            double[] mc = TransferKinematics.galacticToSpaceMc(vGal);
            double[] g2 = TransferKinematics.spaceMcToGalactic(mc);
            for (int i = 0; i < 3; i++) assertEquals(vGal[i], g2[i], 1e-9);
        }
    }

    @Test
    void entryThenExitRestoresSpaceVelocity() {
        // Space velocity → ground-relative → surface blocks/tick → back to space, spin included.
        Random r = new Random(3);
        double omega = TransferKinematics.spinRate(23.9345);
        for (int k = 0; k < 200; k++) {
            double[] pole = unit(r), up = unit(r);
            LocalFrame f = LocalFrame.at(pole, up);
            double radius = 6.371e6;
            double[] rv = {up[0] * radius, up[1] * radius, up[2] * radius};
            double[] spin = TransferKinematics.spinVelocity(pole, omega, rv);
            double[] vSpaceMc = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
            double[] vGal = TransferKinematics.spaceMcToGalactic(vSpaceMc);
            double[] surf = TransferKinematics.toSurface(TransferKinematics.groundRelative(vGal, spin), f, 10.0);
            double[] out = TransferKinematics.galacticToSpaceMc(
                    TransferKinematics.inertial(TransferKinematics.fromSurface(surf, f, 10.0), spin));
            for (int i = 0; i < 3; i++) assertEquals(vSpaceMc[i], out[i], 1e-9);
        }
    }

    @Test
    void verticalAndEasterlyMotionMapToMinecraftAxes() {
        double[] pole = {0, 0, 1};
        LocalFrame f = LocalFrame.at(pole, new double[] {1, 0, 0}); // equator
        double[] down = {-200, 0, 0}; // falling at 200 m/s
        double[] s = TransferKinematics.toSurface(down, f, 10);
        assertEquals(-1.0, s[1], 1e-12); // 200 m/s / (10 m/block · 20) = 1 block/tick down
        double[] e = TransferKinematics.toSurface(f.east(), f, 1);
        assertEquals(1 / 20.0, e[0], 1e-12);
        double[] n = TransferKinematics.toSurface(f.north(), f, 1);
        assertEquals(-1 / 20.0, n[2], 1e-12); // north is −Z
    }

    @Test
    void equatorialSpinSpeedIsEarthsFourHundredSixtyFiveMetres() {
        double omega = TransferKinematics.spinRate(23.9345);
        double[] v = TransferKinematics.spinVelocity(new double[] {0, 0, 1}, omega, new double[] {6.3781e6, 0, 0});
        assertEquals(465.1, Math.hypot(v[0], v[1]), 0.5);
    }

    @Test
    void attitudeAndLookRoundTrip() {
        Random r = new Random(4);
        for (int k = 0; k < 200; k++) {
            LocalFrame f = LocalFrame.at(unit(r), unit(r));
            double[] q = TransferKinematics.normalizeQ(new double[] {r.nextGaussian(), r.nextGaussian(), r.nextGaussian(), r.nextGaussian()});
            double[] back = TransferKinematics.attitudeToSpace(TransferKinematics.attitudeToSurface(q, f), f);
            double sign = Math.signum(q[3] * back[3] + q[0] * back[0] + q[1] * back[1] + q[2] * back[2]);
            for (int i = 0; i < 4; i++) assertEquals(q[i], sign * back[i], 1e-9);
            // The surface attitude rotates the ship's nose exactly like the direction mapping does.
            double[] nose = {0, 0, 1};
            double[] viaQ = TransferKinematics.rotate(TransferKinematics.attitudeToSurface(q, f), nose);
            double[] viaDir = TransferKinematics.directionToSurface(TransferKinematics.rotate(q, nose), f);
            for (int i = 0; i < 3; i++) assertEquals(viaDir[i], viaQ[i], 1e-9);
            double yaw = r.nextDouble() * 360 - 180, pitch = r.nextDouble() * 170 - 85;
            double[] a = TransferKinematics.lookAngles(TransferKinematics.lookVector(yaw, pitch));
            assertEquals(yaw, a[0], 1e-9);
            assertEquals(pitch, a[1], 1e-9);
        }
    }
}
