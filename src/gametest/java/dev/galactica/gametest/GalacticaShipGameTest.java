package dev.galactica.gametest;

import dev.galactica.Galactica;
import dev.galactica.nav.NavState;
import dev.galactica.ship.ShipCargo;
import dev.galactica.ship.ShipDocking;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import dev.galactica.ship.yard.ShipStats;
import dev.galactica.ship.yard.ShipSystems;
import dev.galactica.ship.yard.ShipTemplate;
import dev.galactica.ship.yard.ShipTemplates;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

/**
 * Server GameTests of the ship systems ({@code ./gradlew runGameTest}, headless), with mock server players standing in
 * for a crew: two players aboard one ship, port-to-port docking and undocking, a hangar capture, a docked pair carried
 * through a cross-dimension teleport (the same vanilla path as planet entry and exit), shields, the wreck at hull 0,
 * cargo, the lift jets' hydrogen and the occupancy-grid collision. The ships float well above the test floor, held still
 * by their (mock) pilots: a client-authoritative pilot's ship is never moved by the server.
 */
public final class GalacticaShipGameTest {
    private static final int PAD = 56;

    /** A prefab with its helm's bottom centre {@code up} blocks over the test origin (plus an x/z offset), nose south. */
    private static ShipStructure spawn(GameTestHelper h, String name, double dx, double up, double dz) {
        Vec3 at = h.absoluteVec(new Vec3(4.5 + dx, up, 4.5 + dz));
        // Big hulls reach past the test's own chunks, and an entity only ticks in an entity-ticking chunk: force the
        // chunks around the spot (the test server is thrown away afterwards).
        int cx = net.minecraft.util.Mth.floor(at.x) >> 4, cz = net.minecraft.util.Mth.floor(at.z) >> 4;
        for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++) h.getLevel().setChunkForced(x, z, true);
        ShipStructure s = ShipTemplates.spawn(h.getLevel(), name, at, Direction.SOUTH);
        if (s == null) throw h.assertionException("could not spawn " + name);
        return s;
    }

    private static ServerPlayer board(GameTestHelper h, Starship ship) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.snapTo(ship.getX(), ship.getY(), ship.getZ());
        if (!p.startRiding(ship, true, true)) throw h.assertionException("could not board ship " + ship.getId());
        return p;
    }

    private static double distance(Vector3d a, Vector3d b) {
        return a.distance(b);
    }

    // ------------------------------------------------------------------ two players aboard

    @GameTest(maxTicks = 100, padding = PAD)
    public void twoPlayersCrewOneShip(GameTestHelper h) {
        ShipStructure shuttle = spawn(h, "shuttle", 0, 20, 0);
        ServerPlayer pilot = board(h, shuttle), crew = board(h, shuttle);
        h.assertTrue(shuttle.getControllingPassenger() == pilot, "the first player aboard flies");
        h.assertValueEqual(shuttle.crew().size(), 2, "crew aboard");
        h.assertTrue(Starship.isPiloting(pilot) && !Starship.isPiloting(crew), "pilot / crew roles");
        h.assertTrue(Starship.isCrew(crew) && Starship.leadsCraft(pilot) && !Starship.leadsCraft(crew), "the pilot leads the frame");
        h.assertTrue(Starship.courseOwner(crew) == pilot, "the crew follows the pilot's course (target, autopilot)");
        // The crew's frame follows the pilot's: an origin shift of the pilot moves the crew's origin the same way.
        NavState pilotNav = pilot.getAttachedOrCreate(Galactica.NAV), crewNav = crew.getAttachedOrCreate(Galactica.NAV);
        NavState moved = pilotNav.withOrigin(pilotNav.origin().plusMetres(1_000, 0, 0));
        pilot.setAttached(Galactica.NAV, moved);
        Starship.carryCrew(pilot, pilotNav.origin(), moved);
        double[] d = crew.getAttachedOrCreate(Galactica.NAV).origin().metresFrom(crewNav.origin());
        h.assertTrue(Math.abs(d[0] - 1_000) < 1e-6 && Math.abs(d[1]) < 1e-6, "crew origin carried: " + d[0]);
        h.runAfterDelay(3, () -> {
            Vector3f seat = shuttle.attitude().transform(new Vector3f(shuttle.seat(1)));
            Vec3 attach = crew.getVehicleAttachmentPoint(shuttle);
            Vec3 want = new Vec3(shuttle.getX() + seat.x - attach.x, shuttle.getY() + shuttle.pivot() + seat.y - attach.y,
                    shuttle.getZ() + seat.z - attach.z);
            h.assertTrue(crew.position().distanceTo(want) < 0.05, "crew seated at seat 1: " + crew.position() + " vs " + want);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ docking ports

    @GameTest(maxTicks = 100, padding = PAD)
    public void portDockingCarriesAndReleases(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0);
        ShipStructure shuttle = spawn(h, "shuttle", 0, 40, 0);
        ServerPlayer carrierPilot = board(h, freighter), shuttlePilot = board(h, shuttle);
        // Bring the shuttle's belly port two blocks above the freighter's dorsal port.
        ShipSystems.Port fp = freighter.systems().ports().getFirst(), sp = shuttle.systems().ports().getFirst();
        Vector3d target = ShipDocking.portWorld(freighter, fp).add(0, 2, 0);
        Vector3d now = ShipDocking.portWorld(shuttle, sp);
        shuttle.setPos(shuttle.position().add(target.x - now.x, target.y - now.y, target.z - now.z));
        ShipDocking.toggle(shuttlePilot);
        h.assertTrue(shuttle.getVehicle() == freighter, "the shuttle rides the freighter after the dock key");
        h.assertValueEqual(shuttle.dockKind(), ShipDocking.Kind.PORT, "dock kind");
        double gap = distance(ShipDocking.portWorld(freighter, fp), ShipDocking.portWorld(shuttle, sp));
        h.assertTrue(gap < 0.05, "the ports meet: gap " + gap);
        h.assertTrue(!Starship.isPiloting(shuttlePilot) && Starship.isCrew(shuttlePilot), "a docked pilot is the carrier's crew");
        h.assertTrue(Starship.leadsCraft(carrierPilot) && !Starship.leadsCraft(shuttlePilot), "the carrier's pilot leads");
        h.assertTrue(Starship.courseOwner(shuttlePilot) == shuttlePilot, "a docked pilot keeps their own course");
        h.assertTrue(!shuttle.flightReady(), "a docked craft does not fly");
        Vector3d offset = ShipDocking.toBody(freighter, ShipDocking.pivotWorld(shuttle));
        // The carrier moves (a recentre is a translation of the whole tree, a burn a move of the root): the shuttle follows.
        freighter.setPos(freighter.position().add(37, 3, -11));
        freighter.setDeltaMovement(new Vec3(0.4, 0, 0));
        h.runAfterDelay(2, () -> {
            Vector3d after = ShipDocking.toBody(freighter, ShipDocking.pivotWorld(shuttle));
            h.assertTrue(distance(offset, after) < 0.01, "pose kept in the carrier frame: " + offset + " -> " + after);
            ShipDocking.toggle(shuttlePilot);
            h.assertTrue(!shuttle.isPassenger(), "undocked");
            Vec3 rel = shuttle.getDeltaMovement().subtract(freighter.getDeltaMovement());
            h.assertTrue(Math.abs(rel.length() - 1.5 * ShipFlightModel.TICK) < 1e-6, "leaves with the carrier's velocity and a 1.5 m/s push: " + rel);
            h.assertTrue(Starship.isPiloting(shuttlePilot), "flying again");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ hangar

    @GameTest(maxTicks = 100, padding = PAD)
    public void hangarCapturesALighterShipAndLetsItGo(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0);
        ShipStructure shuttle = spawn(h, "shuttle", 0, 45, 0);
        board(h, freighter);
        ShipSystems.Hangar hangar = freighter.systems().hangar();
        h.assertTrue(hangar != null, "the freighter has a hangar");
        var com = freighter.centreOfMass();
        double cx = (hangar.minX() + hangar.maxX() + 1) / 2.0, cz = (hangar.minZ() + hangar.maxZ() + 1) / 2.0;
        Vector3d pivot = ShipDocking.toWorld(freighter, new Vector3d(cx - com.x(), hangar.floorY() + 1.3 + shuttle.pivot() - com.y(), cz - com.z()));
        shuttle.setPos(pivot.x, pivot.y - shuttle.pivot(), pivot.z);
        shuttle.setDeltaMovement(freighter.getDeltaMovement());
        ShipDocking.captureIntoHangar(freighter);
        h.assertTrue(shuttle.getVehicle() == freighter && shuttle.dockKind() == ShipDocking.Kind.HANGAR, "captured into the hangar");
        h.runAfterDelay(2, () -> {
            Vector3d b = ShipDocking.toBody(freighter, ShipDocking.pivotWorld(shuttle)).add(com.x(), com.y(), com.z());
            double bottom = b.y - shuttle.pivot();
            h.assertTrue(Math.abs(bottom - (hangar.floorY() + 1)) < 0.01, "set on the bay floor: bottom " + bottom + " floor " + (hangar.floorY() + 1));
            Vector3f up = new org.joml.Quaternionf(shuttle.attitude()).transform(new Vector3f(0, 1, 0));
            h.assertTrue(up.y > 0.999F, "level in the hangar");
            ShipDocking.undock(shuttle);
            ShipDocking.captureIntoHangar(freighter);
            h.assertTrue(!shuttle.isPassenger(), "not caught again straight after release");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ dimension transfer

    @GameTest(maxTicks = 200, padding = PAD)
    public void dockedPairAndCargoCrossDimensions(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            Galactica.LOG.warn("[gametest] no nether on the test server: the dimension crossing was not exercised");
            h.succeed();
            return;
        }
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0);
        ShipStructure shuttle = spawn(h, "shuttle", 0, 60, 0);
        ServerPlayer crew = board(h, shuttle);
        int box = ShipCargo.containers(freighter.blocks())[0];
        ShipCargo.store(freighter, box, List.of(new ItemStack(Items.DIAMOND, 7)));
        ShipDocking.dock(freighter, shuttle, ShipDocking.Kind.PORT, Vec3.ZERO);
        Vector3d offset = ShipDocking.toBody(freighter, ShipDocking.pivotWorld(shuttle));
        Vec3 there = new Vec3(0.5, 100, 0.5);
        Entity moved = freighter.teleport(new TeleportTransition(nether, there, Vec3.ZERO, 0F, 0F, TeleportTransition.DO_NOTHING));
        h.assertTrue(moved instanceof ShipStructure, "the freighter crossed");
        ShipStructure f2 = (ShipStructure) moved;
        h.assertTrue(f2.level() == nether && f2.dockedCraft().size() == 1, "it arrives with its docked craft");
        Starship s2 = f2.dockedCraft().getFirst();
        h.assertTrue(s2 instanceof ShipStructure ss && ss.blocks().size() == ShipTemplate.shuttle().size(), "the shuttle's blocks crossed too");
        h.assertValueEqual(s2.dockKind(), ShipDocking.Kind.PORT, "dock kind after the crossing");
        h.assertTrue(crew.getVehicle() == s2, "the shuttle's crew crossed in it");
        List<ItemStack> cargo = ShipCargo.contents(f2, box);
        h.assertTrue(cargo.size() == 1 && cargo.getFirst().is(Items.DIAMOND) && cargo.getFirst().getCount() == 7, "cargo crossed: " + cargo);
        h.runAfterDelay(3, () -> {
            Vector3d after = ShipDocking.toBody(f2, ShipDocking.pivotWorld(s2));
            h.assertTrue(after.distance(offset) < 0.01, "docked pose kept across the crossing: " + offset + " -> " + after);
            // Clean up the other dimension: the test framework only clears its own area.
            Galactica.LOG.info("[gametest] freighter, docked shuttle, its crew and 7 diamonds of cargo crossed into {}; docked pose drift {} blocks",
                    nether.dimension().identifier(), after.distance(offset));
            crew.stopRiding();
            for (Entity e : List.of(s2, f2)) e.discard();
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ shields, wreck, engines

    @GameTest(maxTicks = 100, padding = PAD)
    public void shieldsTakeTheHitAndRecharge(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0);
        board(h, freighter);
        h.assertTrue(freighter.maxShield() > 0 && freighter.shieldRecharge() > 0, "shield generators and a reactor aboard");
        freighter.setShield(freighter.maxShield());
        float hull = freighter.hull();
        freighter.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 5F);
        h.assertValueEqual(freighter.hull(), hull, "the shield took the hit");
        h.assertTrue(Math.abs(freighter.shield() - (freighter.maxShield() - 25F)) < 1e-3, "25 shield points spent: " + freighter.shield());
        float shield = freighter.shield(), hydrogen = freighter.hydrogen();
        h.runAfterDelay(20, () -> {
            h.assertTrue(freighter.shield() > shield, "the reactor recharges the shield: " + shield + " -> " + freighter.shield());
            h.assertTrue(freighter.hydrogen() < hydrogen, "and burns hydrogen for it");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100, padding = PAD)
    public void wreckDropsBlocksAndCargoAndSetsTheCrewDownSafely(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0);
        ShipStructure shuttle = spawn(h, "shuttle", 0, 60, 0);
        ServerPlayer pilot = board(h, freighter), crew = board(h, freighter);
        int box = ShipCargo.containers(freighter.blocks())[0];
        ShipCargo.store(freighter, box, List.of(new ItemStack(Items.DIAMOND, 3)));
        ShipDocking.dock(freighter, shuttle, ShipDocking.Kind.PORT, Vec3.ZERO);
        h.assertTrue(dev.galactica.config.GalacticaConfigs.get().shipDestruction(), "destruction is on by default");
        Vec3 at = freighter.position();
        freighter.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1e6F);
        h.assertTrue(freighter.isRemoved(), "the freighter broke up");
        h.assertTrue(!pilot.isPassenger() && !crew.isPassenger(), "everyone set down");
        h.assertTrue(crew.hasEffect(MobEffects.SLOW_FALLING), "with slow falling on a planet");
        h.assertTrue(!shuttle.isPassenger() && !shuttle.isRemoved(), "the docked shuttle flies free");
        List<ItemEntity> drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(64));
        boolean diamonds = drops.stream().anyMatch(e -> e.getItem().is(Items.DIAMOND) && e.getItem().getCount() == 3);
        int blocks = drops.stream().filter(e -> !e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(diamonds, "the cargo spilled");
        int size = ShipTemplate.freighter().size();
        h.assertTrue(blocks > size * 0.15 && blocks < size * 0.35, String.format(Locale.ROOT, "about a quarter of %d blocks dropped: %d", size, blocks));
        for (ItemEntity e : drops) e.discard();
        h.succeed();
    }

    @GameTest(maxTicks = 100, padding = PAD)
    public void zeroHullKillsTheEnginesAndLiftJetsBurnHydrogen(GameTestHelper h) {
        ShipStructure shuttle = spawn(h, "shuttle", 0, 20, 0);
        ServerPlayer pilot = board(h, shuttle);
        pilot.setLastClientInput(new Input(false, false, false, false, true, false, false)); // lift key
        float before = shuttle.hydrogen();
        h.runAfterDelay(20, () -> {
            float burned = before - shuttle.hydrogen();
            double want = shuttle.spec().liftHydrogenPerSecond(); // 20 ticks = 1 s on the lift jets
            h.assertTrue(Math.abs(burned - want) < 0.15 * want, String.format(Locale.ROOT, "lift jets burned %.4f cells in 1 s (expected %.4f)", burned, want));
            shuttle.setHull(0F);
            h.assertTrue(shuttle.enginesDead(), "hull 0: engines dead");
            h.assertTrue(!Starship.consumeWarpFuel(pilot, 1e9), "no warp from a wreck");
            float wreck = shuttle.hydrogen();
            pilot.setLastClientInput(new Input(true, false, false, false, true, false, false)); // main engine and lift
            h.runAfterDelay(20, () -> {
                h.assertValueEqual(shuttle.hydrogen(), wreck, "a wreck burns nothing");
                h.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ occupancy grid

    @GameTest(maxTicks = 40, padding = PAD)
    public void occupancyGridCatchesTerrainInsideALargeHull(GameTestHelper h) {
        ShipStructure freighter = spawn(h, "freighter", 0, 20, 0);
        board(h, freighter);
        h.assertTrue(freighter.usesOccupancyGrid(), "a 2 400-block hull collides through the occupancy grid");
        h.assertTrue(freighter.hullClearAt(h.getLevel(), freighter.position(), freighter.attitude()), "clear in open air");
        // A block in the middle of the deck (under the hangar floor's centre): inside the hull, far from its outline.
        ShipSystems.Hangar hangar = freighter.systems().hangar();
        var com = freighter.centreOfMass();
        Vector3d cell = ShipDocking.toWorld(freighter, new Vector3d((hangar.minX() + hangar.maxX() + 1) / 2.0 - com.x(),
                hangar.floorY() + 0.5 - com.y(), (hangar.minZ() + hangar.maxZ() + 1) / 2.0 - com.z()));
        BlockPos spire = BlockPos.containing(cell.x, cell.y, cell.z);
        h.getLevel().setBlockAndUpdate(spire, Blocks.STONE.defaultBlockState());
        boolean clear = freighter.hullClearAt(h.getLevel(), freighter.position(), freighter.attitude());
        h.getLevel().setBlockAndUpdate(spire, Blocks.AIR.defaultBlockState());
        h.assertTrue(!clear, "a block inside the hull is a collision");
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) freighter.hullClearAt(h.getLevel(), freighter.position().add(0, i * 0.01, 0), freighter.attitude());
        double ms = (System.nanoTime() - t0) / 1e6 / 200;
        Galactica.LOG.info(String.format(Locale.ROOT, "[gametest] occupancy hull test %.3f ms each over open ground (%d probes)", ms,
                freighter.spec().probes().length));
        h.assertTrue(ms < 2.0, "fast enough per test: " + ms + " ms");
        h.succeed();
    }

    /** Spec sanity for the prefab the tests lean on. */
    @GameTest(maxTicks = 20)
    public void freighterOutweighsShuttle(GameTestHelper h) {
        h.assertTrue(ShipStats.compute(ShipTemplate.freighter().cells()).spec().massKg()
                > ShipStats.compute(ShipTemplate.shuttle().cells()).spec().massKg(), "carrier is heavier");
        h.succeed();
    }

}
