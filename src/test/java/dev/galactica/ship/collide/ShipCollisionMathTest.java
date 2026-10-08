package dev.galactica.ship.collide;

import dev.galactica.ship.deck.ShipPose;
import dev.galactica.ship.yard.ShipCell;
import dev.galactica.ship.yard.ShipOccupancy;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Voxel-against-voxel narrow phase and the impulse solve. */
class ShipCollisionMathTest {
    private static ShipOccupancy cube(int n) {
        List<ShipCell> cells = new ArrayList<>();
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) cells.add(ShipCell.structure(x, y, z, 100));
        return new ShipOccupancy(cells);
    }

    private static VoxelContact.Posed at(ShipOccupancy g, double x, double y, double z, Quaterniond q) {
        double c = (g.maxX() - g.minX()) / 2.0;
        return new VoxelContact.Posed(g, new ShipPose(new Vector3d(x, y, z), q, new Vector3d(c, c, c)));
    }

    // ------------------------------------------------------------------ narrow phase

    @Test
    void overlappingCubesGiveTheAxisNormalAndDepth() {
        ShipOccupancy g = cube(5);
        // A at x = 0, B at x = 4.2: overlap 0.8 along x; B is to the +x side, so A must move −x.
        var a = at(g, 0, 50, 0, new Quaterniond());
        var b = at(g, 4.2, 50, 0, new Quaterniond());
        VoxelContact.Contact c = VoxelContact.find(a, b);
        assertNotNull(c);
        assertEquals(-1, c.normal().x, 0.05);
        assertEquals(0, c.normal().y, 0.05);
        assertTrue(c.depth() > 0.3 && c.depth() < 1.3, "depth " + c.depth());
        assertTrue(c.point().x > 1.3 && c.point().x < 2.9, "contact between the hulls: " + c.point());
        // Swapping the bodies flips the normal.
        VoxelContact.Contact r = VoxelContact.find(b, a);
        assertNotNull(r);
        assertEquals(1, r.normal().x, 0.05);
    }

    @Test
    void touchingFacesAreNotAContactAndSeparatedHullsAreNot() {
        ShipOccupancy g = cube(4);
        var a = at(g, 0, 50, 0, new Quaterniond());
        assertNull(VoxelContact.find(a, at(g, 4.0, 50, 0, new Quaterniond())));
        assertFalse(VoxelContact.overlaps(a, at(g, 0, 54.1, 0, new Quaterniond())));
        // Resting on top with a little sink still is not deep enough to count...
        assertNull(VoxelContact.find(a, at(g, 0, 54 - 0.1, 0, new Quaterniond())));
        // ...a real overlap is, with the normal pointing up out of the lower hull.
        VoxelContact.Contact c = VoxelContact.find(at(g, 0, 53.4, 0, new Quaterniond()), a);
        assertNotNull(c);
        assertEquals(1, c.normal().y, 0.05);
    }

    @Test
    void rotatedHullsStillMeet() {
        ShipOccupancy g = cube(6);
        var a = at(g, 0, 50, 0, new Quaterniond().rotateY(Math.PI / 4));
        // A 45° cube reaches 3·√2 = 4.24 from its centre along x; B's face is 3 from its centre.
        assertTrue(VoxelContact.overlaps(a, at(g, 6.5, 50, 0, new Quaterniond())));
        assertFalse(VoxelContact.overlaps(a, at(g, 7.6, 50, 0, new Quaterniond())));
        VoxelContact.Contact c = VoxelContact.find(a, at(g, 6.5, 50, 0, new Quaterniond()));
        assertNotNull(c);
        assertTrue(c.normal().x < -0.6, "A pushed back along −x: " + c.normal());
    }

    // ------------------------------------------------------------------ impulse

    private static RigidImpulse.Body body(double m, double i, Vector3d centre, Vector3d v, Vector3d w) {
        return RigidImpulse.Body.of(m, new Vector3d(i, i, i), new Quaterniond(), centre, v, w);
    }

    @Test
    void headOnEqualMassesElasticSwapVelocities() {
        var a = body(1000, 1e4, new Vector3d(-2, 0, 0), new Vector3d(5, 0, 0), new Vector3d());
        var b = body(1000, 1e4, new Vector3d(2, 0, 0), new Vector3d(-5, 0, 0), new Vector3d());
        var r = RigidImpulse.resolve(a, b, new Vector3d(0, 0, 0), new Vector3d(-1, 0, 0), 1.0, 0.0);
        assertNotNull(r);
        assertEquals(-5, a.velocity.x, 1e-9);
        assertEquals(5, b.velocity.x, 1e-9);
        assertEquals(10, r.closingSpeed(), 1e-9);
        assertEquals(0, a.omega.length(), 1e-12);
    }

    @Test
    void inelasticHitOnAHeavyHullConservesMomentum() {
        var a = body(50_000, 4e5, new Vector3d(-10, 0, 0), new Vector3d(20, 0, 0), new Vector3d());
        var b = body(1_300_000, 5e8, new Vector3d(10, 0, 0), new Vector3d(), new Vector3d());
        Vector3d p0 = a.momentum().add(b.momentum());
        RigidImpulse.resolve(a, b, new Vector3d(0, 0, 0), new Vector3d(-1, 0, 0), 0.0, 0.5);
        assertEquals(0, p0.distance(a.momentum().add(b.momentum())), 1e-6 * p0.length());
        // Perfectly inelastic along the normal: both end with the same normal velocity.
        assertEquals(a.velocity.x, b.velocity.x, 1e-9);
        assertTrue(a.velocity.x > 0 && a.velocity.x < 1, "the shuttle almost stops, the freighter barely moves");
    }

    @Test
    void randomContactsConserveMomentumAndNeverGainEnergy() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 500; trial++) {
            Quaterniond qa = new Quaterniond().rotateXYZ(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
            Quaterniond qb = new Quaterniond().rotateXYZ(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
            Vector3d ia = new Vector3d(1e4 + rnd.nextDouble() * 1e6, 1e4 + rnd.nextDouble() * 1e6, 1e4 + rnd.nextDouble() * 1e6);
            Vector3d ib = new Vector3d(1e4 + rnd.nextDouble() * 1e6, 1e4 + rnd.nextDouble() * 1e6, 1e4 + rnd.nextDouble() * 1e6);
            double ma = 1e3 + rnd.nextDouble() * 1e6, mb = 1e3 + rnd.nextDouble() * 1e6;
            var a = RigidImpulse.Body.of(ma, ia, qa, rv(rnd, 10), rv(rnd, 30), rv(rnd, 1));
            var b = RigidImpulse.Body.of(mb, ib, qb, rv(rnd, 10), rv(rnd, 30), rv(rnd, 1));
            Matrix3d wa = RigidImpulse.worldInertia(ia, qa), wb = RigidImpulse.worldInertia(ib, qb);
            Vector3d p0 = a.momentum().add(b.momentum());
            Vector3d l0 = a.angularMomentum(wa).add(b.angularMomentum(wb));
            double e0 = a.kineticEnergy(wa) + b.kineticEnergy(wb);
            double e = rnd.nextDouble(), mu = rnd.nextDouble();
            var r = RigidImpulse.resolve(a, b, rv(rnd, 10), rv(rnd, 1).normalize(), e, mu);
            Vector3d p1 = a.momentum().add(b.momentum());
            Vector3d l1 = a.angularMomentum(wa).add(b.angularMomentum(wb));
            double e1 = a.kineticEnergy(wa) + b.kineticEnergy(wb);
            assertEquals(0, p0.distance(p1), 1e-7 * (p0.length() + 1), "momentum, trial " + trial);
            assertEquals(0, l0.distance(l1), 1e-7 * (l0.length() + 1), "angular momentum, trial " + trial);
            assertTrue(e1 <= e0 * (1 + 1e-9) + 1e-6, "energy grew in trial " + trial + ": " + e0 + " -> " + e1);
            if (r == null) assertEquals(e0, e1, 1e-9 * e0);
        }
    }

    private static Vector3d rv(Random r, double s) {
        return new Vector3d(r.nextGaussian() * s, r.nextGaussian() * s, r.nextGaussian() * s);
    }

    @Test
    void aGlancingOffCentreHitSpinsTheHull() {
        // B moves along −x and clips A's nose: the contact is 8 blocks ahead of A's centre.
        var a = body(100_000, 2e6, new Vector3d(0, 0, 0), new Vector3d(), new Vector3d());
        var b = body(100_000, 2e6, new Vector3d(12, 0, 8), new Vector3d(-15, 0, 0), new Vector3d());
        var r = RigidImpulse.resolve(a, b, new Vector3d(4, 0, 8), new Vector3d(-1, 0, 0), 0.3, 0.3);
        assertNotNull(r);
        assertTrue(Math.abs(a.omega.y) > 0.05, "A turns: " + a.omega);
        assertTrue(a.velocity.x < 0);
    }

    @Test
    void separatingBodiesAreLeftAlone() {
        var a = body(1000, 1e4, new Vector3d(-2, 0, 0), new Vector3d(-1, 0, 0), new Vector3d());
        var b = body(1000, 1e4, new Vector3d(2, 0, 0), new Vector3d(1, 0, 0), new Vector3d());
        assertNull(RigidImpulse.resolve(a, b, new Vector3d(), new Vector3d(-1, 0, 0), 0.5, 0.5));
        assertEquals(-1, a.velocity.x, 0);
    }

    @Test
    void damageOnlyAboveTheThreshold() {
        assertEquals(0, RigidImpulse.hullDamage(1e5, 1e6, 2.5), 0);
        double slow = RigidImpulse.hullDamage(1.56e5, 1.29e6, 10), fast = RigidImpulse.hullDamage(1.56e5, 1.29e6, 20);
        assertTrue(slow > 0 && fast > 3 * slow, slow + " " + fast);
    }
}
