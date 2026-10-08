package dev.galactica.client.ship;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.ship.ShipCargo;
import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.deck.Deck;
import dev.galactica.ship.deck.DeckPayloads;
import dev.galactica.ship.deck.DeckPos;
import dev.galactica.ship.deck.DeckWalker;
import dev.galactica.ship.deck.EvaDrift;
import dev.galactica.ship.deck.HullSweep;
import dev.galactica.ship.deck.ShipPose;
import dev.galactica.ship.deck.ShipVoxelCollision;
import dev.galactica.ship.yard.ShipPart;
import dev.galactica.ship.yard.ShipPartBlocks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Client half of deck walking ({@link Deck}) for the local player: their walking intent and magnetic boots, the deck
 * position reported every tick, boarding a deck they stand on (or that sweeps into them), stepping off it, using the
 * helm, a seat or a container by clicking it, and the EVA drift that keeps a ship's velocity after stepping off in
 * space ({@link EvaDrift}).
 */
public final class DeckClient {
    private DeckClient() {}

    public static final KeyMapping MAGBOOTS = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.galactica.magboots", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, ShipClient.CATEGORY, 8));

    private static boolean magboots = true;
    private static @Nullable Vec3 drift;
    private static int boardCooldown;
    private static final boolean AUTOTEST = Boolean.getBoolean("galactica.autotest");

    /** Dev autotest: counts of what happened, read by the scripted checks. */
    public static int boards, leaves, uses;

    public static void initClient() {
        Deck.clientSimulates = e -> e == Minecraft.getInstance().player;
        Deck.clientMagboots = e -> magboots;
        Deck.clientInput = DeckClient::input;
        Deck.clientAfterStep = (ship, e) -> {
            if (!(e instanceof LocalPlayer p) || !ClientPlayNetworking.canSend(DeckPayloads.Move.TYPE)) return;
            DeckPos now = Deck.clientState(p);
            if (now != null) ClientPlayNetworking.send(new DeckPayloads.Move(ship.getId(), now.x(), now.y(), now.z(), now.epoch()));
        };
        Deck.clientLeave = DeckClient::leave;
        ClientTickEvents.START_CLIENT_TICK.register(DeckClient::beforeTick);
        ClientTickEvents.END_CLIENT_TICK.register(DeckClient::afterTick);
    }

    /** The local player's walking intent in the ship's frame. */
    private static DeckWalker.Input input(net.minecraft.world.entity.Entity e) {
        if (!(e instanceof LocalPlayer p) || !(p.getVehicle() instanceof ShipStructure ship)) return DeckWalker.Input.NONE;
        var move = p.input.getMoveVector();
        var keys = p.input.keyPresses;
        double speed = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        return new DeckWalker.Input(move.y, move.x, Deck.localYaw(ship, p.getYRot()), keys.jump(), keys.sprint() && move.y > 0, keys.shift(),
                speed, 0.42);
    }

    private static void beforeTick(Minecraft mc) {
        while (MAGBOOTS.consumeClick()) {
            magboots = !magboots;
            if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable(magboots ? "hud.galactica.deck.magboots_on" : "hud.galactica.deck.magboots_off"));
        }
    }

    /**
     * {@code Minecraft.startUseItem} for a deck walker: a click on the helm, a seat or a container of the ship they walk
     * on (a ray through the ship's own grid) uses it; true if it did (vanilla's use is then skipped).
     */
    public static boolean useFromDeck(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !(p.getVehicle() instanceof ShipStructure ship) || !Deck.isWalker(p)) return false;
        int index = ShipCargo.raycast(ship, p.getEyePosition(), p.getViewVector(1F), Deck.USE_REACH);
        if (index < 0 || !usable(ship, index) || !ClientPlayNetworking.canSend(DeckPayloads.Use.TYPE)) return false;
        uses++;
        ClientPlayNetworking.send(new DeckPayloads.Use(ship.getId(), index));
        return true;
    }

    /** Whether a click on ship block {@code index} means something from the deck. */
    public static boolean usable(ShipStructure ship, int index) {
        ShipPart part = ShipPartBlocks.partOf(ship.blocks().state(index));
        return part == ShipPart.HELM || part == ShipPart.CREW_SEAT || ShipCargo.isContainer(ship.blocks(), index);
    }

    private static void afterTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            drift = null;
            return;
        }
        if (boardCooldown > 0) boardCooldown--;
        if (p.isPassenger()) {
            drift = null;
            return;
        }
        evaDrift(mc, p);
        if (p.isSpectator() || boardCooldown > 0) return;
        for (ShipStructure ship : ShipVoxelCollision.ships(mc.level)) {
            if (ship.blocks().isEmpty()) continue;
            double reach = ship.spec().hullReach() + 3;
            if (p.distanceToSqr(ship.getX(), ship.getY() + ship.pivot(), ship.getZ()) > (reach + 16) * (reach + 16)) continue;
            ShipPose from = Deck.prevPose(ship), to = Deck.pose(ship);
            boolean moved = from.maxTravel(to, reach) > 1e-4;
            if (moved) {
                HullSweep.Result r = HullSweep.sweep(new Vector3d(p.getX(), p.getY(), p.getZ()), DeckWalker.Size.PLAYER, from, to, reach, Deck.blocks(ship));
                if (r.hit()) {
                    p.setPos(r.world().x, r.world().y, r.world().z);
                    p.setDeltaMovement(Deck.pointVelocity(ship, r.local()));
                    if (AUTOTEST) Galactica.LOG.info("[autotest] the hull of ship {} swept the player to {}", ship.getId(), p.position());
                }
            }
            Vector3d spot = Deck.boardingSpot(ship, p, p.position(), p.onGround());
            if (spot != null) {
                board(ship, p, spot);
                return;
            }
        }
    }

    private static void board(ShipStructure ship, LocalPlayer p, Vector3d local) {
        if (!ClientPlayNetworking.canSend(DeckPayloads.Board.TYPE)) return;
        DeckPos was = p.getAttached(Deck.DECK);
        int epoch = was == null ? 0 : was.epoch();
        Vec3 v = p.getDeltaMovement();
        if (!p.startRiding(ship, true, false)) return;
        Deck.clientBoarded(ship, p, local, epoch, v);
        drift = null;
        boards++;
        ClientPlayNetworking.send(new DeckPayloads.Board(ship.getId(), (float) local.x, (float) local.y, (float) local.z, epoch));
        p.sendOverlayMessage(Component.translatable("hud.galactica.deck.on_deck", ship.getDisplayName()));
        if (AUTOTEST) Galactica.LOG.info("[autotest] client boards the deck of ship {} at {}", ship.getId(), local);
    }

    private static void leave(ShipStructure ship, net.minecraft.world.entity.Entity e, Vec3 at, Vec3 v, Vec3 shipV) {
        if (!(e instanceof LocalPlayer p)) return;
        p.stopRiding();
        p.setPos(at);
        boolean space = p.level().dimension() == Galactica.SPACE;
        drift = space && shipV.length() >= EvaDrift.MIN ? shipV : null;
        p.setDeltaMovement(drift != null ? v.subtract(shipV) : v);
        boardCooldown = 10;
        leaves++;
        if (ClientPlayNetworking.canSend(DeckPayloads.Leave.TYPE)) ClientPlayNetworking.send(new DeckPayloads.Leave(at.x, at.y, at.z, v.x, v.y, v.z));
        if (AUTOTEST) Galactica.LOG.info("[autotest] client steps off ship {} at {} with {} m/s", ship.getId(), at, String.format(java.util.Locale.ROOT, "%.2f", v.length() * 20));
    }

    /** After stepping off a moving ship in space the player keeps its velocity (inertia) until braking with sneak. */
    private static void evaDrift(Minecraft mc, LocalPlayer p) {
        Vec3 d = drift;
        if (d == null) return;
        if (p.level().dimension() != Galactica.SPACE) {
            drift = null;
            return;
        }
        p.move(MoverType.SELF, d);
        drift = EvaDrift.next(d, p.input.keyPresses.shift());
    }

    /** The EVA drift the local player keeps, blocks per tick (zero if none). */
    public static Vec3 drift() {
        Vec3 d = drift;
        return d == null ? Vec3.ZERO : d;
    }

    public static boolean magbootsOn() {
        return magboots;
    }
}
