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
    /** 3 × 2 block box (spec 3 × 2 × 5; vanilla boxes are square in XZ, so the 5-block length only shows in the model). */
    public static final EntityType<Starship> SHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, SHIP_KEY,
            EntityType.Builder.<Starship>of(Starship::new, MobCategory.MISC)
                    .noLootTable().sized(3.0F, 2.0F).eyeHeight(0.75F).clientTrackingRange(10).build(SHIP_KEY));

    public static final ResourceKey<Item> SHIP_BEACON_KEY = ResourceKey.create(Registries.ITEM, Galactica.id("ship_beacon"));
    public static final Item SHIP_BEACON = Registry.register(BuiltInRegistries.ITEM, SHIP_BEACON_KEY,
            new ShipBeaconItem(new Item.Properties().stacksTo(1).setId(SHIP_BEACON_KEY)));

    /** Call once from {@code Galactica.onInitialize}. */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(ShipControlPayload.TYPE, ShipControlPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HyperjumpPayload.TYPE, HyperjumpPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipCorrectionPayload.TYPE, ShipCorrectionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ShipControlPayload.TYPE, (msg, ctx) -> {
            if (ctx.player().level().getEntity(msg.entityId()) instanceof Starship ship && ship.getControllingPassenger() == ctx.player()) {
                ship.acceptPilotReport(ctx.player(), msg.seq(), msg.attitude(), msg.velocity(), msg.coupled());
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
