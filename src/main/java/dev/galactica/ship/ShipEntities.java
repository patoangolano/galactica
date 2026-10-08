package dev.galactica.ship;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/** Registration for the ship area: entity type, beacon item, serverbound payloads and their handlers, the pilot's correction payload. */
public final class ShipEntities {
    private ShipEntities() {}

    public static final ResourceKey<EntityType<?>> SHIP_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Galactica.id("ship"));
    /** Square collision box around fuselage and pods ({@link ShipDimensions#BOX_WIDTH}; the wings and length only show in the model). */
    public static final EntityType<Starship> SHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, SHIP_KEY,
            EntityType.Builder.<Starship>of(Starship::new, MobCategory.MISC)
                    .noLootTable().sized(ShipDimensions.BOX_WIDTH, ShipDimensions.BOX_HEIGHT).eyeHeight(ShipDimensions.PIVOT).clientTrackingRange(10).build(SHIP_KEY));

    public static final ResourceKey<EntityType<?>> STRUCTURE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Galactica.id("built_ship"));
    /** Block-built ships ({@link ShipStructure}): the box is sized per ship from its blocks, this is only the default. */
    public static final EntityType<ShipStructure> STRUCTURE = Registry.register(BuiltInRegistries.ENTITY_TYPE, STRUCTURE_KEY,
            EntityType.Builder.<ShipStructure>of(ShipStructure::new, MobCategory.MISC)
                    .noLootTable().sized(1F, 1F).clientTrackingRange(10).build(STRUCTURE_KEY));

    public static final ResourceKey<Item> SHIP_BEACON_KEY = ResourceKey.create(Registries.ITEM, Galactica.id("ship_beacon"));
    public static final Item SHIP_BEACON = Registry.register(BuiltInRegistries.ITEM, SHIP_BEACON_KEY,
            new ShipBeaconItem(new Item.Properties().stacksTo(1).setId(SHIP_BEACON_KEY)));

    /** Call once from {@code Galactica.onInitialize}. */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ShipControlPayload.TYPE, ShipControlPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HyperjumpPayload.TYPE, HyperjumpPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipCorrectionPayload.TYPE, ShipCorrectionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipBlocksPayload.TYPE, ShipBlocksPayload.CODEC);
        // A block-built ship's blocks go to every client that starts tracking it (after its spawn packet).
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (entity instanceof ShipStructure s) s.sendBlocksTo(player);
        });
        if (Boolean.getBoolean("galactica.autotest")) {
            net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.STOP_TRACKING.register((entity, player) -> {
                if (entity instanceof Starship s) Galactica.LOG.info("[autotest] {} stops tracking ship {} at {} (rides {}, player at {})",
                        player.getName().getString(), s.getId(), s.position(), player.getVehicle() == null ? "nothing" : player.getVehicle().getId(),
                        player.position());
            });
        }
        dev.galactica.ship.build.ShipPartBlocks.init();
        dev.galactica.ship.build.ShipBlueprintItem.init();
        dev.galactica.ship.build.ShipBuildCommands.init();
        ServerPlayNetworking.registerGlobalReceiver(ShipControlPayload.TYPE, (msg, ctx) -> {
            if (ctx.player().level().getEntity(msg.entityId()) instanceof Starship ship && ship.getControllingPassenger() == ctx.player()) {
                ship.acceptPilotReport(ctx.player(), msg.seq(), msg.attitude(), msg.velocity(), msg.coupled(), msg.rebase());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HyperjumpPayload.TYPE, (msg, ctx) -> {
            if (ctx.player().level().getEntity(msg.entityId()) instanceof Starship ship && ship.getControllingPassenger() == ctx.player()) {
                Hyperdrive.request(ship, ctx.player());
            }
        });
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(out -> out.accept(SHIP_BEACON));
    }
}
