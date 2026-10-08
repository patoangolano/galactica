package dev.galactica.ship.yard;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipEntities;
import dev.galactica.ship.ShipStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Turns blocks into a {@link ShipStructure} and back (server only, one tick each; the fill is bounded).
 *
 * <p><b>Frames.</b> The ship grid is body-oriented: the helm's facing is +Z (the nose). A helm facing {@code f} maps
 * the grid onto the world by {@link #toWorld}: body +Z → {@code f}, a quarter turn about Y at a time, and every state
 * is turned the same way ({@link BlockState#rotate}), so stored states are as they would stand on a ship pointing
 * south, and a ship disassembled facing another way gets its stairs, thrusters and seats turned with it.
 */
public final class ShipAssembler {
    private ShipAssembler() {}

    /** Natural ground the fill stops at (data-driven: {@code data/galactica/tags/block/ship_assembly_stop.json}). */
    public static final TagKey<Block> STOP = TagKey.create(Registries.BLOCK, Galactica.id("ship_assembly_stop"));
    /** Flags that move blocks without drops, container spills, neighbour updates or shape updates. */
    private static final int MOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
    /** A ship is still enough to disassemble below this speed, blocks per tick (1 m/s). */
    private static final double STILL = 0.05;
    /** Most the hull may pitch or roll from level and still be set down on the grid, degrees. */
    private static final double MAX_TILT_DEG = 25;

    // ------------------------------------------------------------------ frames

    /** Body → world turn for a helm facing {@code facing}. */
    public static Rotation toWorld(Direction facing) {
        return ShipFrames.toWorld(facing);
    }

    public static Rotation inverse(Rotation r) {
        return ShipFrames.inverse(r);
    }

    /** Turns a horizontal offset (x, z) by {@code r} ({@link ShipFrames#turn}). */
    public static double[] turn(Rotation r, double x, double z) {
        return ShipFrames.turn(r, x, z);
    }

    private static BlockPos worldCell(BlockPos helm, Rotation r, int x, int y, int z) {
        double[] t = turn(r, x, z);
        return helm.offset((int) Math.round(t[0]), y, (int) Math.round(t[1]));
    }

    // ------------------------------------------------------------------ assembly

    /** What the fill sees of the world, in the ship grid of a helm at {@code helm} facing {@code facing}. */
    private record WorldGrid(ServerLevel level, BlockPos helm, Rotation r) implements AssemblyFill.Grid {
        BlockState at(int x, int y, int z) {
            return level.getBlockState(worldCell(helm, r, x, y, z));
        }

        @Override
        public AssemblyFill.Kind kind(int x, int y, int z) {
            BlockPos p = worldCell(helm, r, x, y, z);
            if (!level.isInWorldBounds(p)) return AssemblyFill.Kind.OUTSIDE;
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || s.is(STOP)) return AssemblyFill.Kind.OUTSIDE;
            if (s.getDestroySpeed(level, p) < 0) return AssemblyFill.Kind.UNMOVABLE;
            return AssemblyFill.Kind.SHIP;
        }

        @Override
        public ShipPart part(int x, int y, int z) {
            return ShipPartBlocks.partOf(at(x, y, z));
        }

        @Override
        public boolean blocksHead(int x, int y, int z) {
            BlockPos p = worldCell(helm, r, x, y, z);
            return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
        }
    }

    /**
     * Right-click on a helm: fills, validates, lifts the blocks out of the world (no drops, containers keep their
     * contents) and spawns the ship where they stood. Tells {@code player} what is wrong otherwise.
     *
     * @return the new ship, or null
     */
    public static @Nullable ShipStructure assemble(ServerLevel level, BlockPos helm, @Nullable Player player) {
        BlockState helmState = level.getBlockState(helm);
        if (ShipPartBlocks.partOf(helmState) != ShipPart.HELM) return null;
        Direction facing = ShipPartBlock.facingOf(helmState);
        if (facing == null || facing.getAxis().isVertical()) facing = Direction.SOUTH;
        Rotation r = toWorld(facing), back = inverse(r);
        WorldGrid grid = new WorldGrid(level, helm, r);
        AssemblyFill.Result fill = AssemblyFill.fill(grid);
        if (!fill.ok()) return fail(player, fill, helm, r);
        AssemblyFill.Result bad = AssemblyFill.validate(grid, fill.positions(), p -> {
            BlockState s = grid.at(ShipCell.unpackX(p), ShipCell.unpackY(p), ShipCell.unpackZ(p)).rotate(back);
            Direction d = ShipPartBlock.facingOf(s);
            return d == null ? -1 : d.ordinal();
        });
        if (bad != null) return fail(player, bad, helm, r);

        ShipBlocks.Builder b = new ShipBlocks.Builder();
        var registries = level.registryAccess();
        for (int i = 0; i < fill.positions().size(); i++) {
            int p = fill.positions().getInt(i);
            int x = ShipCell.unpackX(p), y = ShipCell.unpackY(p), z = ShipCell.unpackZ(p);
            BlockPos wp = worldCell(helm, r, x, y, z);
            BlockState s = level.getBlockState(wp);
            BlockEntity be = level.getBlockEntity(wp);
            CompoundTag tag = be != null ? be.saveWithFullMetadata(registries) : null;
            b.add(x, y, z, s.rotate(back), tag);
        }
        ShipBlocks blocks = b.build();
        // Top down, so nothing standing on a block is left floating mid-removal (no updates fire anyway).
        int[] order = fill.positions().toIntArray();
        java.util.Arrays.sort(order);
        for (int k = order.length - 1; k >= 0; k--) {
            int p = order[k];
            BlockPos wp = worldCell(helm, r, ShipCell.unpackX(p), ShipCell.unpackY(p), ShipCell.unpackZ(p));
            level.setBlock(wp, Blocks.AIR.defaultBlockState(), MOVE_FLAGS);
        }
        ShipStructure ship = spawn(level, blocks, Vec3.atBottomCenterOf(helm), facing);
        if (ship == null) {
            // Should not happen (the entity type always creates); put everything back rather than lose it.
            place(level, blocks, helm, facing);
            return null;
        }
        var spec = ship.spec();
        Galactica.LOG.info("Assembled ship {} at {}: {} blocks, {} kg, main {} N, lift {} N, {} probes", ship.getId(), helm,
                blocks.size(), Math.round(spec.massKg()), Math.round(spec.mainThrustN()), Math.round(spec.liftThrustN()), spec.probes().length);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("message.galactica.ship.assembled", blocks.size(),
                    String.format(Locale.ROOT, "%.1f", spec.massKg() / 1000), String.format(Locale.ROOT, "%.1f", spec.mainAccel() / 9.80665),
                    String.format(Locale.ROOT, "%.1f", spec.liftAccel() / 9.80665), Math.round(spec.maxHydrogen()),
                    spec.hasHyperdrive() ? String.format(Locale.ROOT, "%.0f", spec.jumpRangeLy()) : "-"));
        }
        return ship;
    }

    private static @Nullable ShipStructure fail(@Nullable Player player, AssemblyFill.Result r, BlockPos helm, Rotation rot) {
        BlockPos at = worldCell(helm, rot, r.errorX(), r.errorY(), r.errorZ());
        String key = "message.galactica.ship.error." + r.error().name().toLowerCase(Locale.ROOT);
        Galactica.LOG.info("Ship assembly at {} refused: {} at {}", helm, r.error(), at.toShortString());
        if (player != null) player.sendSystemMessage(Component.translatable(key, at.getX(), at.getY(), at.getZ(),
                AssemblyFill.MAX_BLOCKS, AssemblyFill.MAX_SPAN, AssemblyFill.MAX_HEIGHT));
        return null;
    }

    /**
     * Spawns a ship from body-grid blocks with its helm cell's bottom centre at {@code helmBottom} and its nose
     * toward {@code facing} (horizontal). Tanks start empty.
     */
    public static @Nullable ShipStructure spawn(ServerLevel level, ShipBlocks blocks, Vec3 helmBottom, Direction facing) {
        ShipStructure ship = ShipEntities.STRUCTURE.create(level, EntitySpawnReason.TRIGGERED);
        if (ship == null) return null;
        ship.setBlocks(blocks);
        var com = ship.centreOfMass();
        Rotation r = toWorld(facing);
        double[] t = turn(r, com.x() - 0.5, com.z() - 0.5);
        double x = helmBottom.x + t[0], z = helmBottom.z + t[1];
        double y = helmBottom.y + com.y() - ship.pivot();
        ship.snapTo(x, y, z, facing.toYRot(), 0F);
        ship.setAttitude(new Quaternionf().rotationY((float) Math.toRadians(-facing.toYRot())));
        ship.setHull(ship.maxHull());
        level.addFreshEntity(ship);
        return ship;
    }

    // ------------------------------------------------------------------ disassembly

    /**
     * Puts a still, empty ship back on the block grid: its yaw snapped to the nearest quarter turn, every cell checked
     * free first (else nothing moves and the player is told which block is in the way). Fuel and exotic matter come
     * out as items at the helm.
     */
    public static boolean disassemble(ShipStructure ship, @Nullable Player player) {
        if (!(ship.level() instanceof ServerLevel level)) return false;
        if (!ship.getPassengers().isEmpty()) return refuse(player, "crew");
        if (ship.getDeltaMovement().length() > STILL) return refuse(player, "moving");
        if (level.dimension() != Galactica.SPACE && !ship.restingContact()) return refuse(player, "airborne");
        Quaternionf q = new Quaternionf(ship.attitude());
        Vector3f up = q.transform(new Vector3f(0, 1, 0)), fwd = q.transform(new Vector3f(0, 0, 1));
        if (up.y < Math.cos(Math.toRadians(MAX_TILT_DEG))) return refuse(player, "tilted");
        Direction facing = Direction.getApproximateNearest(fwd.x, 0, fwd.z);
        if (facing.getAxis().isVertical()) facing = Direction.SOUTH;
        BlockPos helm = helmCell(ship, facing);
        ShipBlocks blocks = ship.blocks();
        Rotation r = toWorld(facing);
        for (int i = 0; i < blocks.size(); i++) {
            int c = blocks.cell(i);
            BlockPos wp = worldCell(helm, r, ShipCell.unpackX(c), ShipCell.unpackY(c), ShipCell.unpackZ(c));
            BlockState there = level.getBlockState(wp);
            if (!level.isInWorldBounds(wp) || !(there.isAir() || there.canBeReplaced())) {
                if (player != null) player.sendSystemMessage(Component.translatable("message.galactica.ship.disassemble.blocked",
                        wp.getX(), wp.getY(), wp.getZ(), there.getBlock().getName()));
                return false;
            }
        }
        place(level, blocks, helm, facing);
        dropCargo(level, ship, Vec3.atCenterOf(helm.above()));
        if (player != null) player.sendSystemMessage(Component.translatable("message.galactica.ship.disassembled", blocks.size()));
        Galactica.LOG.info("Disassembled ship {} at {} facing {}: {} blocks", ship.getId(), helm, facing, blocks.size());
        ship.discard();
        return true;
    }

    /** The world cell the helm returns to, facing {@code facing}. */
    public static BlockPos helmCell(ShipStructure ship, Direction facing) {
        var com = ship.centreOfMass();
        double[] t = turn(toWorld(facing), com.x() - 0.5, com.z() - 0.5);
        double comY = ship.getY() + ship.pivot();
        double hx = ship.getX() - t[0], hz = ship.getZ() - t[1], hy = comY - com.y();
        return BlockPos.containing(Math.floor(hx), Math.round(hy), Math.floor(hz));
    }

    private static boolean refuse(@Nullable Player player, String why) {
        if (player != null) player.sendSystemMessage(Component.translatable("message.galactica.ship.disassemble." + why));
        return false;
    }

    /** Places body-grid blocks with the helm at {@code helm} facing {@code facing}, block entities restored. */
    public static void place(ServerLevel level, ShipBlocks blocks, BlockPos helm, Direction facing) {
        Rotation r = toWorld(facing);
        var registries = level.registryAccess();
        for (int i = 0; i < blocks.size(); i++) {
            int c = blocks.cell(i);
            BlockPos wp = worldCell(helm, r, ShipCell.unpackX(c), ShipCell.unpackY(c), ShipCell.unpackZ(c));
            level.setBlock(wp, blocks.state(i).rotate(r), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag tag = blocks.blockEntity(i);
            if (tag == null) continue;
            int c = blocks.cell(i);
            BlockPos wp = worldCell(helm, r, ShipCell.unpackX(c), ShipCell.unpackY(c), ShipCell.unpackZ(c));
            BlockEntity be = BlockEntity.loadStatic(wp, level.getBlockState(wp), tag, registries);
            if (be != null) level.setBlockEntity(be);
        }
    }

    private static void dropCargo(ServerLevel level, ShipStructure ship, Vec3 at) {
        drop(level, item("hydrogen_cell"), (int) Math.floor(ship.hydrogen()), at);
        drop(level, item("exotic_matter"), ship.exotic(), at);
    }

    private static void drop(ServerLevel level, Item item, int count, Vec3 at) {
        if (item == Items.AIR) return;
        while (count > 0) {
            int n = Math.min(count, item.getDefaultMaxStackSize());
            count -= n;
            var e = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z, new ItemStack(item, n));
            level.addFreshEntity(e);
        }
    }

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.getValue(Galactica.id(path));
    }
}
