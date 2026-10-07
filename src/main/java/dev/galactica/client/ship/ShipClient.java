package dev.galactica.client.ship;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.ship.HyperjumpPayload;
import dev.galactica.ship.ShipControlPayload;
import dev.galactica.ship.ShipCorrectionPayload;
import dev.galactica.ship.ShipEntities;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
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
        Starship.setPilotReady(() -> {
            var connection = Minecraft.getInstance().getConnection();
            return connection == null || connection.hasClientLoaded();
        });
        ModelLayerRegistry.registerModelLayer(StarshipModel.HULL_LAYER, StarshipModel::createHullLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.LIGHTS_LAYER, StarshipModel::createLightsLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.CORES_LAYER, StarshipModel::createCoresLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.GLASS_LAYER, StarshipModel::createGlassLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.COCKPIT_LAYER, StarshipModel::createCockpitLayer);
        ModelLayerRegistry.registerModelLayer(StarshipModel.COCKPIT_LIGHTS_LAYER, StarshipModel::createCockpitLightsLayer);
        ShipRenderTypes.init();
        // GeckoLib is optional: its classes are only loaded through GeckoShipRendering, and only for the API line
        // this mod was compiled against.
        EntityRenderers.register(ShipEntities.SHIP, geckoLibUsable() ? ShipClient::geckoOrVanilla : StarshipRenderer::new);
        EntityRenderers.register(ShipEntities.STRUCTURE, BuiltShipRenderer::new);
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("ship_hud"), ShipHud::extract);
        // A block-built ship's blocks: applied to the entity, or held until its spawn packet has been handled.
        ClientPlayNetworking.registerGlobalReceiver(dev.galactica.ship.ShipBlocksPayload.TYPE, (msg, ctx) -> {
            if (ctx.client().level != null && ctx.client().level.getEntity(msg.entityId()) instanceof dev.galactica.ship.ShipStructure s) {
                s.setBlocks(msg.blocks());
            } else {
                PENDING_BLOCKS.put(msg.entityId(), msg.blocks());
            }
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            var blocks = PENDING_BLOCKS.remove(entity.getId());
            if (blocks != null && entity instanceof dev.galactica.ship.ShipStructure s) s.setBlocks(blocks);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PENDING_BLOCKS.clear());

        // Fabric runs play payload handlers on the client thread, in packet order.
        ClientPlayNetworking.registerGlobalReceiver(ShipCorrectionPayload.TYPE, (msg, ctx) -> {
            if (ctx.client().level != null && ctx.client().level.getEntity(msg.entityId()) instanceof Starship ship)
                ship.applyCorrection(msg.seq(), msg.velocity());
        });
        ClientTickEvents.START_CLIENT_TICK.register(ShipClient::beforeTick);
        ClientTickEvents.END_CLIENT_TICK.register(ShipClient::afterTick);
        // The cockpit display repaints and uploads here, between frames, never while a render pass is open.
        ClientTickEvents.END_CLIENT_TICK.register(CockpitDisplay::tick);
    }

    /** Block sets that arrived before their ship's spawn was handled, by entity id (client thread only). */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<dev.galactica.ship.build.ShipBlocks> PENDING_BLOCKS =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    /** GeckoLib versions whose API {@code GeckoShipRendering} was compiled against (5.5.7, MC 26.3). */
    static final String GECKOLIB_RANGE = ">=5.5.7 <5.6";

    /** True if GeckoLib is installed in a version this mod's renderer links against. */
    private static boolean geckoLibUsable() {
        var container = FabricLoader.getInstance().getModContainer("geckolib");
        if (container.isEmpty()) return false;
        Version version = container.get().getMetadata().getVersion();
        try {
            if (VersionPredicate.parse(GECKOLIB_RANGE).test(version)) return true;
        } catch (VersionParsingException e) {
            throw new IllegalStateException("bad GeckoLib range " + GECKOLIB_RANGE, e);
        }
        Galactica.LOG.warn("GeckoLib {} is outside {}; the Starship uses its built-in renderer", version.getFriendlyString(), GECKOLIB_RANGE);
        return false;
    }

    /**
     * Builds the GeckoLib renderer at renderer bake, falling back to the built-in one if GeckoLib's classes do not
     * link (a build that kept the version number but changed the API).
     */
    private static EntityRenderer<Starship, ?> geckoOrVanilla(EntityRendererProvider.Context context) {
        try {
            return dev.galactica.client.ship.geckolib.GeckoShipRendering.create(context);
        } catch (LinkageError e) {
            Galactica.LOG.warn("GeckoLib renderer failed to link; the Starship uses its built-in renderer", e);
            return new StarshipRenderer(context);
        }
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
        if (ship == null || !ClientPlayNetworking.canSend(ShipControlPayload.TYPE) || !Starship.pilotReady() || !ship.flightReady()) return;
        int seq = ship.recordReport(ship.getDeltaMovement());
        Vec3 v = ship.getDeltaMovement().scale(20.0);
        ClientPlayNetworking.send(new ShipControlPayload(ship.getId(), seq, new Quaternionf(ship.attitude()),
                new Vector3f((float) v.x, (float) v.y, (float) v.z), ship.coupled(), ship.takeRebase(seq)));
    }
}
