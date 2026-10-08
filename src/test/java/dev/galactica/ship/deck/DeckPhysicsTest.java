package dev.galactica.ship.deck;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local-frame collision maths: the box sweep, the deck walker, the ship pose and the hull sweep. */
class DeckPhysicsTest {
    private static final double G = -0.08;

    // ------------------------------------------------------------------ box sweep

    @Test
    void clipStopsAtAWallAndIgnoresBoxesAlreadyOverlapped() {
        List<Aabb> wall = List.of(Aabb.cell(2, 0, 0));
        Aabb box = Aabb.feet(0.5, 0, 0.5, 0.6, 1.8);
        assertEquals(2 - 0.8, BoxSweep.clip(0, box, wall, 5), 1e-9);
        assertEquals(-5, BoxSweep.clip(0, box, wall, -5), 1e-9);
        // A box already inside the wall can leave it.
        Aabb inside = Aabb.feet(2.5, 0, 0.5, 0.6, 1.8);
        assertEquals(3, BoxSweep.clip(0, inside, wall, 3), 1e-9);
    }

    @Test
    void collideGoesVerticalFirstThenSlidesAlongTheFloor() {
        List<Aabb> floor = new ArrayList<>();
        new DeckTestGrids().box(-5, 0, -5, 5, 0, 5).collect(new Aabb(-10, -10, -10, 10, 10, 10), floor);
        Aabb box = Aabb.feet(0, 1.2, 0, 0.6, 1.8);
        double[] m = BoxSweep.collide(0.5, -1, 0.3, box, floor);
        assertEquals(-0.2, m[1], 1e-9);
        assertEquals(0.5, m[0], 1e-9);
        assertEquals(0.3, m[2], 1e-9);
    }

    @Test
    void stepUpClimbsASlabStepButNotAFullBlock() {
        DeckTestGrids g = DeckTestGrids.deck();
        List<Aabb> boxes = new ArrayList<>();
        g.collect(new Aabb(-10, -10, -10, 10, 10, 10), boxes);
        boxes.add(new Aabb(1, 1, -2, 3, 1.5, 2)); // a half-block step
        Aabb box = Aabb.feet(0.5, 1, 0, 0.6, 1.8);
        double[] m = BoxSweep.collideWithStep(0.4, 0, 0, box, boxes, 0.6, true);
        assertEquals(0.4, m[0], 1e-9);
        assertEquals(0.5, m[1], 1e-9);
        boxes.add(Aabb.cell(1, 1, 0)); // a full block in the way
        boxes.add(Aabb.cell(1, 2, 0));
        double[] b = BoxSweep.collideWithStep(0.4, 0, 0, box, boxes, 0.6, true);
        assertEquals(0.2, b[0], 1e-9);
        assertEquals(0, b[1], 1e-9);
    }

    @Test
    void depenetratePrefersStandingOnTheFloor() {
        List<Aabb> floor = List.of(Aabb.cell(0, 0, 0));
        double[] push = BoxSweep.depenetrate(Aabb.feet(0.5, 0.7, 0.5, 0.6, 1.8), floor, 0.6, 4);
        assertNotNull(push);
        assertEquals(0.3, push[1], 1e-3);
        assertNull(BoxSweep.depenetrate(Aabb.feet(0.5, 1, 0.5, 0.6, 1.8), floor, 0.6, 4));
    }

    // ------------------------------------------------------------------ deck walker

    @Test
    void standsOnTheDeckAndNeverFallsThrough() {
        DeckTestGrids g = DeckTestGrids.deck();
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 3, 0.5);
        for (int i = 0; i < 200; i++) w.step(DeckWalker.Input.NONE, 0, G, 0, g);
        assertEquals(1, w.y, 1e-9);
        assertTrue(w.onGround);
    }

    @Test
    void walksAtVanillaSpeedAndStopsAtAWall() {
        DeckTestGrids g = DeckTestGrids.deck().box(-10, 1, 6, 10, 3, 6);
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 1, 0.5);
        w.step(DeckWalker.Input.NONE, 0, G, 0, g);
        var fwd = new DeckWalker.Input(1, 0, 0, false, false, false, 0.1, 0.42);
        double z0 = 0;
        for (int i = 0; i < 20; i++) {
            if (i == 10) z0 = w.z;
            w.step(fwd, 0, G, 0, g);
        }
        // Vanilla walking: 0.2158 blocks per tick at steady state (4.3 m/s).
        assertEquals(0.2158, (w.z - z0) / 10, 0.003);
        for (int i = 0; i < 60; i++) w.step(fwd, 0, G, 0, g);
        assertEquals(6 - 0.3, w.z, 1e-6);
        assertTrue(w.horizontalCollision);
        assertEquals(1, w.y, 1e-9);
    }

    @Test
    void yawTurnsTheWalkDirection() {
        DeckTestGrids g = DeckTestGrids.deck();
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 1, 0.5);
        var west = new DeckWalker.Input(1, 0, 90, false, false, false, 0.1, 0.42); // yaw 90 faces −X
        for (int i = 0; i < 20; i++) w.step(west, 0, G, 0, g);
        assertTrue(w.x < -2, "walked towards −X: " + w.x);
        assertEquals(0.5, w.z, 1e-6);
    }

    @Test
    void jumpsAndLandsOnTheDeck() {
        DeckTestGrids g = DeckTestGrids.deck();
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 1, 0.5);
        w.step(DeckWalker.Input.NONE, 0, G, 0, g);
        w.step(DeckWalker.Input.NONE, 0, G, 0, g);
        var jump = new DeckWalker.Input(0, 0, 0, true, false, false, 0.1, 0.42);
        w.step(jump, 0, G, 0, g);
        double peak = 0;
        for (int i = 0; i < 40; i++) {
            w.step(DeckWalker.Input.NONE, 0, G, 0, g);
            peak = Math.max(peak, w.y);
        }
        assertEquals(1 + 1.25, peak, 0.05); // vanilla jump height ~1.25
        assertEquals(1, w.y, 1e-9);
        assertTrue(w.onGround);
    }

    @Test
    void climbsAHalfStepWhileWalking() {
        DeckTestGrids g = DeckTestGrids.deck();
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 1, 0.5);
        DeckWalker.Blocks withSlab = (r, out) -> {
            g.collect(r, out);
            Aabb slab = new Aabb(-10, 1, 3, 10, 1.5, 10);
            if (slab.intersects(r)) out.add(slab);
        };
        var fwd = new DeckWalker.Input(1, 0, 0, false, false, false, 0.1, 0.42);
        for (int i = 0; i < 40; i++) w.step(fwd, 0, G, 0, withSlab);
        assertEquals(1.5, w.y, 1e-9);
        assertTrue(w.z > 5);
    }

    @Test
    void weightlessDriftsAndKeysSteer() {
        DeckTestGrids g = DeckTestGrids.deck();
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 3, 0.5);
        w.vy = 0.05;
        for (int i = 0; i < 5; i++) w.step(DeckWalker.Input.NONE, 0, 0, 0, g);
        assertTrue(w.y > 3.1, "kept drifting up");
        var down = new DeckWalker.Input(0, 0, 0, false, false, true, 0.1, 0.42);
        for (int i = 0; i < 200; i++) w.step(down, 0, 0, 0, g);
        assertEquals(1, w.y, 1e-6); // came down onto the deck, did not pass through
    }

    @Test
    void tiltedGravitySlidesTheWalkerAlongTheFloor() {
        DeckTestGrids g = DeckTestGrids.deck();
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 0.5, 1, 0.5);
        for (int i = 0; i < 20; i++) w.step(DeckWalker.Input.NONE, 0.03, G, 0, g);
        assertTrue(w.x > 0.6);
        assertEquals(1, w.y, 1e-9);
    }

    // ------------------------------------------------------------------ ship pose

    @Test
    void localAndWorldAreInverse() {
        ShipPose p = new ShipPose(new Vector3d(100.25, 64, -3), new Quaterniond().rotateXYZ(0.3, 1.1, -0.4), new Vector3d(0.5, 1.5, 3));
        Vector3d world = new Vector3d(97, 66.5, 2);
        Vector3d back = p.toWorld(p.toLocal(world));
        assertEquals(0, back.distance(world), 1e-9);
        // The pivot is at the centre of mass.
        assertEquals(0, p.toLocal(new Vector3d(100.25, 64, -3)).distance(new Vector3d(0.5, 1.5, 3)), 1e-9);
    }

    @Test
    void aRiderKeepsItsLocalPositionThroughAFastTurnAndCruise() {
        ShipPose a = new ShipPose(new Vector3d(0, 100, 0), new Quaterniond(), new Vector3d(0.5, 0.5, 0.5));
        Vector3d rider = a.toWorld(new Vector3d(5.5, 1, -12));
        ShipPose now = a;
        for (int t = 0; t < 40; t++) {
            // 285 m/s forward and 2.5 rad/s of yaw.
            ShipPose next = new ShipPose(new Vector3d(now.pivot()).add(now.dirToWorld(new Vector3d(0, 0, 14.25))),
                    new Quaterniond(now.attitude()).rotateY(0.125), now.com());
            rider = now.carry(rider, next);
            now = next;
        }
        assertEquals(0, now.toLocal(rider).distance(new Vector3d(5.5, 1, -12)), 1e-9);
    }

    @Test
    void headingDeltaFollowsTheTurn() {
        ShipPose a = new ShipPose(new Vector3d(), new Quaterniond(), new Vector3d());
        ShipPose b = new ShipPose(new Vector3d(), new Quaterniond().rotateY(Math.toRadians(-10)), new Vector3d());
        // Minecraft yaw grows turning from +Z towards −X, which is a negative rotation about +Y.
        assertEquals(10, a.headingDelta(b), 1e-6);
    }

    @Test
    void walkerOnARotatingShipStaysOnDeck() {
        DeckTestGrids g = DeckTestGrids.deck();
        ShipPose pose = new ShipPose(new Vector3d(0, 80, 0), new Quaterniond(), new Vector3d(0.5, 0.5, 0.5));
        DeckWalker w = new DeckWalker(DeckWalker.Size.PLAYER, 3.5, 1, 3.5);
        var fwd = new DeckWalker.Input(1, 0, 0, false, false, false, 0.1, 0.42);
        Vector3d world = pose.toWorld(new Vector3d(w.x, w.y, w.z));
        for (int t = 0; t < 60; t++) {
            ShipPose next = new ShipPose(new Vector3d(pose.pivot()).add(10, 0.5, 3), new Quaterniond(pose.attitude()).rotateY(0.1), pose.com());
            world = pose.carry(world, next);
            Vector3d local = next.toLocal(world);
            w.x = local.x;
            w.y = local.y;
            w.z = local.z;
            w.step(t < 20 ? fwd : DeckWalker.Input.NONE, 0, G, 0, g);
            world = next.toWorld(new Vector3d(w.x, w.y, w.z));
            pose = next;
        }
        assertEquals(1, w.y, 1e-9);
        assertTrue(w.onGround);
        assertEquals(0, pose.toLocal(world).distance(new Vector3d(w.x, w.y, w.z)), 1e-9);
    }

    // ------------------------------------------------------------------ hull sweep

    @Test
    void aFastTurningWallDoesNotJumpOverABody() {
        // A one-block wall 20 blocks out along +X, 4 tall.
        DeckTestGrids g = new DeckTestGrids().box(20, 0, -1, 20, 3, 1);
        ShipPose from = new ShipPose(new Vector3d(0, 0, 0), new Quaterniond(), new Vector3d());
        ShipPose to = new ShipPose(new Vector3d(0, 0, 0), new Quaterniond().rotateY(0.4), new Vector3d()); // the wall moves ~8 blocks
        // The body stands on the wall's arc, 4 blocks along it: one test at the end of the tick would miss it.
        double ang = 0.2;
        Vector3d body = new Vector3d(20.5 * Math.cos(ang), 0.5, -20.5 * Math.sin(ang));
        assertTrue(HullSweep.substeps(from, to, 21) >= 20);
        var r = HullSweep.sweep(body, DeckWalker.Size.PLAYER, from, to, 21, g);
        assertTrue(r.hit());
        assertTrue(r.fraction() > 0.3 && r.fraction() < 0.7, "met mid-tick: " + r.fraction());
        Aabb box = Aabb.feet(r.local().x, r.local().y, r.local().z, 0.6, 1.8);
        List<Aabb> near = new ArrayList<>();
        g.collect(box.inflate(1), near);
        assertFalse(BoxSweep.overlaps(box, near, 1e-4), "pushed clear of the wall");
    }

    @Test
    void aShipAt285MetresPerSecondHitsABodyInItsPath() {
        DeckTestGrids g = new DeckTestGrids().box(-2, 0, 4, 2, 3, 4); // a bow plate
        ShipPose from = new ShipPose(new Vector3d(0, 0, 0), new Quaterniond(), new Vector3d());
        ShipPose to = new ShipPose(new Vector3d(0, 0, 14.25), new Quaterniond(), new Vector3d());
        var r = HullSweep.sweep(new Vector3d(0.5, 0.5, 12), DeckWalker.Size.PLAYER, from, to, 6, g);
        assertTrue(r.hit());
        // Ends ahead of the bow, carried with it.
        assertTrue(r.world().z > 14.25 + 5 - 0.01, "ahead of the plate: " + r.world().z);
        var miss = HullSweep.sweep(new Vector3d(10.5, 0.5, 12), DeckWalker.Size.PLAYER, from, to, 6, g);
        assertFalse(miss.hit());
    }
}
