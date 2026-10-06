package dev.galactica.client.ship;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.ship.HyperjumpPayload;
import dev.galactica.ship.ShipControlPayload;
import dev.galactica.ship.ShipEntities;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Client half of the ship: model layers, pipelines, renderer (vanilla, or GeckoLib when installed), key mappings, HUD, and the per-tick pilot loop
 * (keys → ship before the level ticks it; predicted attitude and velocity → server after).
 */
public final class ShipClient {
    private ShipClient() {}

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Galactica.id("ship"));
    public static final KeyMapping ROLL_LEFT = key("roll_left", InputConstants.KEY_Q, 0);
    public static final KeyMapping ROLL_RIGHT = key("roll_right", InputConstants.KEY_E, 1);
    public static final KeyMapping TOGGLE_COUPLED = key("toggle_coupled", InputConstants.KEY_C, 2);
    public static final KeyMapping HYPERJUMP = key("hyperjump", InputConstants.KEY_J, 3);

    private static KeyMapping key(String name, int code, int order) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galactica." + name, InputConstants.Type.KEYBOARD, code, CATEGORY, order));
    }

    /** Call once from {@code GalacticaClient.onInitializeClient}. */
    public static void initClient() {
        ModelLayerRegistry.registerModelLayer(StarshipModel.HULL_LAYER, StarshipModel::createHullLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.LIGHTS_LAYER, StarshipModel::createLightsLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.CORES_LAYER, StarshipModel::createCoresLayer);
        ShipRenderTypes.init();
        // GeckoLib is optional: its classes are only loaded through GeckoShipRendering when the mod is present.
        if (FabricLoader.getInstance().isModLoaded("geckolib")) {
            dev.galactica.client.ship.geckolib.GeckoShipRendering.register();
        } else {
            EntityRenderers.register(ShipEntities.SHIP, StarshipRenderer::new);
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("ship_hud"), ShipHud::extract);

        ClientTickEvents.START_CLIENT_TICK.register(ShipClient::beforeTick);
        ClientTickEvents.END_CLIENT_TICK.register(ShipClient::afterTick);
    }

    /** The ship the local player is flying, or null. */
    public static @Nullable Starship piloted(Minecraft mc) {
        LocalPlayer p = mc.player;
        return p != null && p.getVehicle() instanceof Starship s && s.getControllingPassenger() == p ? s : null;
    }

    private static void beforeTick(Minecraft mc) {
        Starship ship = piloted(mc);
        if (ship == null) {
            while (TOGGLE_COUPLED.consumeClick()) { /* not flying: drop stale presses */ }
            while (HYPERJUMP.consumeClick()) { }
            return;
        }
        // Default roll keys share Q/E with drop and inventory; at the stick those vanilla actions are swallowed.
        if (ROLL_LEFT.same(mc.options.keyDrop) || ROLL_RIGHT.same(mc.options.keyDrop)) while (mc.options.keyDrop.consumeClick()) { }
        if (ROLL_LEFT.same(mc.options.keyInventory) || ROLL_RIGHT.same(mc.options.keyInventory)) while (mc.options.keyInventory.consumeClick()) { }

        float roll = (ROLL_RIGHT.isDown() ? 1F : 0F) - (ROLL_LEFT.isDown() ? 1F : 0F);
        ship.setPilotInput(mc.player.input.keyPresses, roll);
        while (TOGGLE_COUPLED.consumeClick()) {
            ship.toggleCoupled();
            mc.player.sendOverlayMessage(Component.translatable(ship.coupled() ? "hud.galactica.ship.assist_on" : "hud.galactica.ship.assist_off"));
        }
        while (HYPERJUMP.consumeClick()) {
            if (ClientPlayNetworking.canSend(HyperjumpPayload.TYPE)) ClientPlayNetworking.send(new HyperjumpPayload(ship.getId()));
        }
    }

    /** After the tick the prediction for this tick is final: report it. */
    private static void afterTick(Minecraft mc) {
        Starship ship = piloted(mc);
        if (ship == null || !ClientPlayNetworking.canSend(ShipControlPayload.TYPE)) return;
        Vec3 v = ship.getDeltaMovement().scale(20.0);
        ClientPlayNetworking.send(new ShipControlPayload(ship.getId(), new Quaternionf(ship.attitude()),
                new Vector3f((float) v.x, (float) v.y, (float) v.z), ship.coupled()));
    }
}
