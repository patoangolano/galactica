package dev.galactica.ship.yard;

import dev.galactica.Galactica;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/** The ship-part blocks and their items (spec: block-built ships). */
public final class ShipPartBlocks {
    private ShipPartBlocks() {}

    public static final List<ShipPartBlock> ALL = new ArrayList<>();

    public static final ShipPartBlock HELM = block("ship_helm", ShipPart.HELM, ShipPartBlock.Facing.HORIZONTAL, MapColor.COLOR_LIGHT_GRAY, 3F, 2);
    public static final ShipPartBlock CREW_SEAT = block("ship_crew_seat", ShipPart.CREW_SEAT, ShipPartBlock.Facing.HORIZONTAL, MapColor.COLOR_LIGHT_GRAY, 2F, 0);
    public static final ShipPartBlock THRUSTER = block("ship_thruster", ShipPart.THRUSTER, ShipPartBlock.Facing.ALL, MapColor.COLOR_GRAY, 4F, 3);
    public static final ShipPartBlock RCS = block("ship_rcs", ShipPart.RCS, ShipPartBlock.Facing.NONE, MapColor.COLOR_LIGHT_GRAY, 3F, 0);
    public static final ShipPartBlock HYDROGEN_TANK = block("ship_hydrogen_tank", ShipPart.HYDROGEN_TANK, ShipPartBlock.Facing.NONE, MapColor.SNOW, 3F, 0);
    public static final ShipPartBlock HYPERDRIVE_CORE = block("ship_hyperdrive_core", ShipPart.HYPERDRIVE_CORE, ShipPartBlock.Facing.NONE, MapColor.COLOR_PURPLE, 5F, 10);
    public static final ShipPartBlock LANDING_GEAR = block("ship_landing_gear", ShipPart.LANDING_GEAR, ShipPartBlock.Facing.NONE, MapColor.COLOR_GRAY, 3F, 0);
    public static final ShipPartBlock HULL_PLATING = block("ship_hull_plating", ShipPart.HULL_PLATING, ShipPartBlock.Facing.NONE, MapColor.COLOR_LIGHT_GRAY, 5F, 0);
    public static final ShipPartBlock DOCKING_PORT = block("ship_docking_port", ShipPart.DOCKING_PORT, ShipPartBlock.Facing.ALL, MapColor.COLOR_YELLOW, 4F, 4);
    public static final ShipPartBlock HANGAR_BAY = block("ship_hangar_bay", ShipPart.HANGAR_BAY, ShipPartBlock.Facing.NONE, MapColor.COLOR_GRAY, 4F, 2);
    public static final ShipPartBlock SHIELD_GENERATOR = block("ship_shield_generator", ShipPart.SHIELD_GENERATOR, ShipPartBlock.Facing.NONE, MapColor.COLOR_CYAN, 4F, 7);
    public static final ShipPartBlock REACTOR = block("ship_reactor", ShipPart.REACTOR, ShipPartBlock.Facing.NONE, MapColor.COLOR_ORANGE, 5F, 12);
    public static final ShipPartBlock SCANNER = block("ship_scanner", ShipPart.SCANNER, ShipPartBlock.Facing.NONE, MapColor.COLOR_LIGHT_BLUE, 3F, 5);

    private static ShipPartBlock block(String name, ShipPart part, ShipPartBlock.Facing facing, MapColor colour, float strength, int light) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Galactica.id(name));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key).mapColor(colour).strength(strength, 6F)
                .sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> light);
        if (part == ShipPart.HELM || part == ShipPart.CREW_SEAT) props = props.noOcclusion();
        ShipPartBlock.PENDING.set(facing);
        ShipPartBlock b;
        try {
            b = Registry.register(BuiltInRegistries.BLOCK, key, new ShipPartBlock(props, part, facing));
        } finally {
            ShipPartBlock.PENDING.remove();
        }
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Galactica.id(name));
        Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(b, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        ALL.add(b);
        return b;
    }

    /** The part a block plays aboard a ship. */
    public static ShipPart partOf(BlockState state) {
        return state.getBlock() instanceof ShipPartBlock p ? p.part() : ShipPart.STRUCTURE;
    }

    /** Forces registration. Call once from the main initializer. */
    public static void init() {}
}
