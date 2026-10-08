package dev.galactica.ship.yard;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Prefab ships ({@link ShipTemplate}) as real blocks: spawned flying-ready, or placed on the ground to build on. */
public final class ShipTemplates {
    private ShipTemplates() {}

    /** The template's blocks in the ship grid (body orientation: helm facing south, +Z). */
    public static ShipBlocks blocks(ShipTemplate t) {
        ShipBlocks.Builder b = new ShipBlocks.Builder();
        for (ShipTemplate.Placement p : t.placements()) b.add(p.x(), p.y(), p.z(), state(p.mat()), null);
        return b.build();
    }

    static BlockState state(ShipTemplate.Mat m) {
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(m.block));
        BlockState s = block.defaultBlockState();
        if (m.facing >= 0) {
            Direction d = Direction.values()[m.facing];
            if (s.hasProperty(ShipPartBlock.ALL)) s = s.setValue(ShipPartBlock.ALL, d);
            else if (s.hasProperty(ShipPartBlock.HORIZONTAL) && d.getAxis().isHorizontal()) s = s.setValue(ShipPartBlock.HORIZONTAL, d);
        }
        return s;
    }

    /**
     * Spawns a prefab with its helm's bottom centre at {@code helmBottom}, nose toward {@code facing}, tanks full and
     * a hyperdrive load of exotic matter when it has a drive.
     */
    public static @Nullable ShipStructure spawn(ServerLevel level, String name, Vec3 helmBottom, Direction facing) {
        ShipTemplate t = ShipTemplate.byName(name);
        ShipStructure ship = ShipAssembler.spawn(level, blocks(t), helmBottom, facing);
        if (ship == null) return null;
        ship.setHydrogen(ship.maxHydrogen());
        ship.setExotic(ship.maxExotic());
        Galactica.LOG.info("Spawned prefab {} ({} blocks) as ship {}", name, t.size(), ship.getId());
        return ship;
    }

    /** Places a prefab as blocks, helm at {@code helm} facing {@code facing}: to modify and assemble by hand. */
    public static void place(ServerLevel level, String name, BlockPos helm, Direction facing) {
        ShipAssembler.place(level, blocks(ShipTemplate.byName(name)), helm, facing);
    }
}
