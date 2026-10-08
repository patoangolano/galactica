package dev.galactica.gametest;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.collide.ShipCollisions;
import dev.galactica.ship.collide.VoxelContact;
import dev.galactica.ship.deck.Deck;
import dev.galactica.ship.deck.DeckPos;
import dev.galactica.ship.yard.ShipBlocks;
import dev.galactica.ship.yard.ShipPart;
import dev.galactica.ship.yard.ShipPartBlocks;
import dev.galactica.ship.yard.ShipTemplates;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Server GameTests of deck walking and ship-to-ship collision ({@code ./gradlew runGameTest}). Mock players walk on the
 * server ({@link Deck#serverSimulatesPlayer}), driven through their last client input like a real player's keys.
 * A ship held by a mock pilot is never moved by the server (its client would fly it): the tests move it themselves.
 */
public final class GalacticaDeckGameTest {
    private static final int PAD = 56;

    private static ShipStructure spawn(GameTestHelper h, String name, double dx, double up, double dz, Direction facing) {
        Vec3 at = h.absoluteVec(new Vec3(4.5 + dx, up, 4.5 + dz));
        int cx = net.minecraft.util.Mth.floor(at.x) >> 4, cz = net.minecraft.util.Mth.floor(at.z) >> 4;
        for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) h.getLevel().setChunkForced(x, z, true);
        ShipStructure s = ShipTemplates.spawn(h.getLevel(), name, at, facing);
        if (s == null) throw h.assertionException("could not spawn " + name);
        return s;
    }

    private static ServerPlayer seat(GameTestHelper h, Starship ship) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.snapTo(ship.getX(), ship.getY(), ship.getZ());
        if (!p.startRiding(ship, true, true)) throw h.assertionException("could not board ship " + ship.getId());
        return p;
    }

    /** A mock player walking on {@code ship}'s deck at cell position {@code local}. */
    private static ServerPlayer walker(GameTestHelper h, ShipStructure ship, Vector3d local) {
        Deck.serverSimulatesPlayer = p -> true;
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vector3d w = Deck.pose(ship).toWorld(local);
        p.snapTo(w.x, w.y, w.z);
        if (!Deck.board(ship, p, local, 0, Vec3.ZERO)) throw h.assertionException("could not board the deck of ship " + ship.getId());
        return p;
    }

    private static Vector3d local(ShipStructure ship, net.minecraft.world.entity.Entity e) {
        return Deck.pose(ship).toLocal(new Vector3d(e.getX(), e.getY(), e.getZ()));
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ------------------------------------------------------------------ deck walking

    @GameTest(maxTicks = 200, padding = PAD)
    public void walkerRidesAMovingTurningShipAndWalksOnIt(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0, Direction.SOUTH);
        seat(h, freighter); // the pilot: the server leaves the ship's motion to its client, the test moves it
        Vector3d start = new Vector3d(-2.5, 0, -3.5);
        ServerPlayer w = walker(h, freighter, start);
        h.assertTrue(Deck.isWalker(w) && freighter.walkers().contains(w), "a deck walker");
        h.assertTrue(freighter.getControllingPassenger() != w && !freighter.crew().contains(w), "a walker is not crew");
        int[] t = {0};
        h.onEachTick(() -> {
            int k = t[0]++;
            if (k < 40) {
                // Cruise and turn: 0.6 blocks a tick and 3° of yaw a tick, with a little roll.
                freighter.setPos(freighter.position().add(0.6, 0.05, 0.3));
                freighter.setAttitude(new Quaternionf(freighter.attitude()).rotateY((float) Math.toRadians(3)).rotateZ((float) Math.toRadians(0.2)));
            } else if (k == 41) {
                Vector3d l = local(freighter, w);
                DeckPos at = w.getAttached(Deck.DECK);
                h.assertTrue(at != null && new Vector3d(at.x(), at.y(), at.z()).distance(start) < 0.02, "stood still on the deck: " + at);
                h.assertTrue(l.distance(start) < 0.05, "world position follows the ship: local " + l + " vs " + start);
                // Walk: forward in the ship's frame (yaw 0 faces the nose, +Z).
                w.setYRot((float) Math.toDegrees(dev.galactica.ship.deck.ShipPose.heading(Deck.pose(freighter).attitude())));
                w.setLastClientInput(new Input(true, false, false, false, false, false, false));
            } else if (k == 51) {
                w.setLastClientInput(Input.EMPTY);
            } else if (k == 70) {
                DeckPos at = w.getAttached(Deck.DECK);
                double walked = at.z() - start.z;
                Galactica.LOG.info("[gametest] deck walker: carried 40 ticks through a turn, then walked {} blocks forward on the deck (x drift {}, y {})",
                        f(walked), f(at.x() - start.x), f(at.y()));
                h.assertTrue(walked > 1.5 && walked < 3.5, "walked about 2 blocks forward in 10 ticks: " + walked);
                h.assertTrue(Math.abs(at.x() - start.x) < 0.05 && Math.abs(at.y()) < 1e-3, "straight, on the deck: " + at);
                h.assertTrue(Deck.isWalker(w), "still aboard");
                h.succeed();
            }
        });
    }

    @GameTest(maxTicks = 400, padding = PAD)
    public void walkerStepsOutOfTheDoorDownTheStepsOntoTheGround(GameTestHelper h) {
        // A stone floor under the whole ship and its boarding steps.
        for (int x = -24; x <= 30; x++) for (int z = -40; z <= 24; z++)
            h.getLevel().setBlockAndUpdate(h.absolutePos(new net.minecraft.core.BlockPos(4 + x, 0, 4 + z)), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        ShipStructure freighter = spawn(h, "freighter", 0, 4.5, 0, Direction.SOUTH);
        int[] t = {0};
        ServerPlayer[] w = {null};
        h.onEachTick(() -> {
            int k = t[0]++;
            if (k == 60) {
                h.assertTrue(freighter.restingContact(), "the freighter settled on its gear");
                // Inside, just before the port door (cells x 7, z −3), looking out of it (yaw −90 faces +X).
                w[0] = walker(h, freighter, new Vector3d(5.5, 0, -2.5));
                w[0].setYRot((float) (Math.toDegrees(dev.galactica.ship.deck.ShipPose.heading(Deck.pose(freighter).attitude())) - 90));
                w[0].setLastClientInput(new Input(true, false, false, false, false, false, false));
            } else if (k > 60 && !w[0].isPassenger()) {
                Vector3d l = local(freighter, w[0]);
                Galactica.LOG.info("[gametest] walked out of the door and down the boarding steps: off the deck at local ({}, {}, {}), world y {}",
                        f(l.x), f(l.y), f(l.z), f(w[0].getY()));
                h.assertTrue(!w[0].hasAttached(Deck.DECK), "no deck position any more");
                h.assertTrue(l.x > 12, "past the bottom step: x " + l.x);
                h.assertTrue(l.y < -2.4 && l.y > -3.2, "standing on the ground beside the ship: y " + l.y);
                h.succeed();
            } else if (k == 300) {
                throw h.assertionException("never stepped off: " + local(freighter, w[0]));
            }
        });
    }

    @GameTest(maxTicks = 200, padding = PAD)
    public void seatedRiderStandsUpThenTakesTheHelm(GameTestHelper h) {
        Deck.serverSimulatesPlayer = q -> true;
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0, Direction.SOUTH);
        ServerPlayer p = seat(h, freighter);
        h.assertTrue(freighter.getControllingPassenger() == p, "seated at the helm");
        p.setShiftKeyDown(true);
        h.runAfterDelay(2, () -> {
            h.assertTrue(p.getVehicle() == freighter && Deck.isWalker(p), "stood up on the deck instead of leaving the ship");
            h.assertTrue(freighter.getControllingPassenger() == null, "the helm is free");
            ShipBlocks blocks = freighter.blocks();
            int helm = -1;
            for (int i = 0; i < blocks.size(); i++) {
                if (ShipPartBlocks.partOf(blocks.state(i)) == ShipPart.HELM) helm = i;
            }
            h.assertTrue(Deck.use(p, freighter, helm), "used the helm");
            h.assertTrue(!Deck.isWalker(p) && freighter.getControllingPassenger() == p, "flying the ship again");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200, padding = PAD)
    public void anItemOnTheDeckStaysAboardWhenTheShipLifts(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0, Direction.SOUTH);
        seat(h, freighter);
        Vector3d above = Deck.pose(freighter).toWorld(new Vector3d(-2.5, 1.5, -3.5));
        ItemEntity item = new ItemEntity(h.getLevel(), above.x, above.y, above.z, new ItemStack(Items.DIAMOND, 2));
        item.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(item);
        int[] t = {0};
        h.onEachTick(() -> {
            int k = t[0]++;
            if (k == 30) {
                h.assertTrue(item.getVehicle() == freighter, "the item came to rest on the deck and rides it: " + local(freighter, item));
            } else if (k > 30 && k < 70) {
                freighter.setPos(freighter.position().add(0.2, 0.8, 0)); // lift off: 16 m/s up
            } else if (k == 75) {
                Vector3d l = local(freighter, item);
                Galactica.LOG.info("[gametest] item on the deck after a 32-block lift: local ({}, {}, {})", f(l.x), f(l.y), f(l.z));
                h.assertTrue(item.getVehicle() == freighter && Math.abs(l.y) < 0.05, "still on the deck: " + l);
                item.discard();
                h.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ ship to ship

    @GameTest(maxTicks = 200, padding = PAD)
    public void shipsCollideHeadOnBounceAndTakeDamage(GameTestHelper h) {
        // High enough that neither reaches the ground (which would take their momentum) during the test.
        ShipStructure a = spawn(h, "shuttle", -16, 90, 0, Direction.SOUTH);
        ShipStructure b = spawn(h, "explorer", 16, 90, 0, Direction.SOUTH);
        a.setDeltaMovement(new Vec3(0.75, 0, 0)); // 15 m/s
        b.setDeltaMovement(new Vec3(-0.25, 0, 0)); // 5 m/s
        double ma = a.spec().massKg(), mb = b.spec().massKg();
        double p0 = ma * 15 - mb * 5;
        float hullA = a.hull(), hullB = b.hull();
        int[] t = {0};
        int[] hitAt = {-1};
        h.onEachTick(() -> {
            int k = t[0]++;
            h.assertTrue(a.getX() < b.getX(), "the shuttle stays on its side");
            VoxelContact.Contact c = VoxelContact.find(ShipCollisions.posed(a), ShipCollisions.posed(b));
            h.assertTrue(c == null || c.depth() < 1.5, "never deep inside each other: " + (c == null ? 0 : c.depth()));
            if (hitAt[0] < 0 && a.impacts > 0) hitAt[0] = k;
            if (hitAt[0] >= 0 && k == hitAt[0] + 12) {
                double va = a.getDeltaMovement().x * 20, vb = b.getDeltaMovement().x * 20;
                double p1 = ma * va + mb * vb;
                double e0 = 0.5 * ma * 225 + 0.5 * mb * 25, e1 = 0.5 * ma * va * va + 0.5 * mb * vb * vb;
                Galactica.LOG.info("[gametest] head-on: shuttle 15 -> {} m/s, explorer -5 -> {} m/s, momentum {} -> {} kg m/s, energy {} -> {} MJ, "
                                + "hull {} -> {}, {} -> {}, impacts {}", f(va), f(vb), f(p0), f(p1), f(e0 / 1e6), f(e1 / 1e6),
                        hullA, a.hull(), hullB, b.hull(), a.impacts);
                h.assertTrue(va < 15 - 5 && vb > -5 + 2, "the shuttle was thrown back and the explorer pushed: " + va + ", " + vb);
                h.assertTrue(Math.abs(p1 - p0) < 0.08 * ma * 15, "momentum kept along x: " + p0 + " -> " + p1);
                h.assertTrue(e1 < e0, "energy lost, never gained: " + e0 + " -> " + e1);
                h.assertTrue(a.hull() < hullA && b.hull() < hullB, "both hulls damaged");
                h.assertTrue(c == null || c.depth() < 0.6, "apart, or just touching");
                h.succeed();
            }
        });
    }

    @GameTest(maxTicks = 200, padding = PAD)
    public void aGlancingBlowDeflectsAndSpins(GameTestHelper h) {
        ShipStructure a = spawn(h, "shuttle", -14, 90, -9, Direction.SOUTH);
        ShipStructure b = spawn(h, "shuttle", 14, 90, 2, Direction.SOUTH);
        a.setDeltaMovement(new Vec3(0.6, 0, 0));
        b.setDeltaMovement(new Vec3(-0.6, 0, 0));
        int[] t = {0};
        int[] hitAt = {-1};
        double[] spin = {0};
        h.onEachTick(() -> {
            int k = t[0]++;
            if (hitAt[0] < 0 && a.impacts > 0) hitAt[0] = k;
            if (hitAt[0] >= 0) spin[0] = Math.max(spin[0], a.spin().length() + b.spin().length());
            if (hitAt[0] >= 0 && k == hitAt[0] + 20) {
                VoxelContact.Contact c = VoxelContact.find(ShipCollisions.posed(a), ShipCollisions.posed(b));
                Galactica.LOG.info("[gametest] glancing blow: peak spin {} rad/s, velocities {} / {} m/s, impacts {}", f(spin[0]),
                        a.getDeltaMovement().scale(20), b.getDeltaMovement().scale(20), a.impacts);
                h.assertTrue(spin[0] > 0.02, "the off-centre hit turns the hulls: " + spin[0]);
                h.assertTrue(a.getDeltaMovement().x < 0.55 && b.getDeltaMovement().x > -0.55, "both slowed along the line of travel");
                h.assertTrue(c == null || c.depth() < 0.6, "apart again");
                h.succeed();
            }
        });
    }

    @GameTest(maxTicks = 300, padding = PAD)
    public void aShipRestsOnAnotherShipsRoof(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0, Direction.SOUTH);
        seat(h, freighter); // held still
        // The shuttle across the freighter's roof forward of the hatch (nose to port), its gear 0.3 above the roof.
        Vector3d roof = Deck.pose(freighter).toWorld(new Vector3d(5.5, 7.3 + 2, -0.5));
        ShipStructure shuttle = ShipTemplates.spawn(h.getLevel(), "shuttle", new Vec3(roof.x, roof.y, roof.z), Direction.EAST);
        double[] ys = new double[2];
        int[] t = {0};
        h.onEachTick(() -> {
            int k = t[0]++;
            if (k == 120) ys[0] = shuttle.getY();
            if (k == 160) {
                ys[1] = shuttle.getY();
                double bottom = VoxelContact.worldBounds(ShipCollisions.posed(shuttle))[1];
                double roofTop = Deck.pose(freighter).toWorld(new Vector3d(0.5, 7, -1)).y;
                VoxelContact.Contact c = VoxelContact.find(ShipCollisions.posed(shuttle), ShipCollisions.posed(freighter));
                Galactica.LOG.info("[gametest] shuttle resting on the freighter's roof: bottom {} over a roof at {}, drift over 2 s {}, speed {} m/s, overlap {}",
                        f(bottom), f(roofTop), f(ys[1] - ys[0]), f(shuttle.getDeltaMovement().length() * 20), c == null ? "none" : f(c.depth()));
                h.assertTrue(Math.abs(ys[1] - ys[0]) < 0.05, "stays put: " + ys[0] + " -> " + ys[1]);
                h.assertTrue(shuttle.getDeltaMovement().length() * 20 < 0.5, "at rest");
                h.assertTrue(bottom > roofTop - 0.6, "on top of the roof, not through it: " + bottom + " vs " + roofTop);
                h.assertTrue(shuttle.restingContact(), "resting contact on the other hull");
                h.succeed();
            }
        });
    }
}
