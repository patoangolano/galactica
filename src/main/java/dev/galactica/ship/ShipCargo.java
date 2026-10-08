package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.ship.yard.ShipBlocks;
import dev.galactica.ship.yard.ShipCell;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cargo containers aboard a block-built ship in flight. A chest, barrel, shulker box, dispenser or any other
 * container block keeps its block-entity data inside the ship ({@link ShipBlocks}); opening one rebuilds the block
 * entity from that data, shows its slots in a chest menu, and writes the data back when the menu closes. Two ways in:
 * the cargo key (B) aboard, which steps through the ship's containers, or a click on a container block of the hull
 * from outside ({@link #raycast}, a ray through the ship's own grid).
 */
public final class ShipCargo {
    private ShipCargo() {}

    /** Last container each player opened through the cargo key, by ship id, so the key steps to the next one. */
    private static final Map<UUID, int[]> LAST = new ConcurrentHashMap<>();

    /** Whether block {@code i} of the set is a container. */
    public static boolean isContainer(ShipBlocks blocks, int i) {
        if (i < 0 || i >= blocks.size()) return false;
        BlockState s = blocks.state(i);
        return s.getBlock() instanceof EntityBlock eb && eb.newBlockEntity(BlockPos.ZERO, s) instanceof Container;
    }

    /** Indices of every container block, in block order. */
    public static int[] containers(ShipBlocks blocks) {
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int i = 0; i < blocks.size(); i++) if (isContainer(blocks, i)) out.add(i);
        return out.toIntArray();
    }

    /**
     * The first ship block a ray from {@code eye} along {@code dir} (world) meets within {@code max} blocks, by index,
     * or −1: a voxel walk through the ship grid in its body frame.
     */
    public static int raycast(ShipStructure ship, Vec3 eye, Vec3 dir, double max) {
        var com = ship.centreOfMass();
        Vector3d o = ShipDocking.toBody(ship, new Vector3d(eye.x, eye.y, eye.z)).add(com.x(), com.y(), com.z());
        Vector3d d = new Quaternionf(ship.attitude()).conjugate().transform(new Vector3d(dir.x, dir.y, dir.z)).normalize();
        int x = (int) Math.floor(o.x), y = (int) Math.floor(o.y), z = (int) Math.floor(o.z);
        int sx = d.x > 0 ? 1 : -1, sy = d.y > 0 ? 1 : -1, sz = d.z > 0 ? 1 : -1;
        double tx = boundary(o.x, d.x), ty = boundary(o.y, d.y), tz = boundary(o.z, d.z);
        double dx = d.x == 0 ? Double.MAX_VALUE : Math.abs(1 / d.x), dy = d.y == 0 ? Double.MAX_VALUE : Math.abs(1 / d.y),
                dz = d.z == 0 ? Double.MAX_VALUE : Math.abs(1 / d.z);
        ShipBlocks blocks = ship.blocks();
        double t = 0;
        while (t <= max) {
            int i = blocks.indexAt(x, y, z);
            if (i >= 0) return i;
            if (tx <= ty && tx <= tz) {
                t = tx;
                tx += dx;
                x += sx;
            } else if (ty <= tz) {
                t = ty;
                ty += dy;
                y += sy;
            } else {
                t = tz;
                tz += dz;
                z += sz;
            }
        }
        return -1;
    }

    private static double boundary(double p, double d) {
        if (d == 0) return Double.MAX_VALUE;
        double next = d > 0 ? Math.floor(p) + 1 - p : p - Math.floor(p);
        return next / Math.abs(d);
    }

    /** The cargo key: opens the ship's next container for a player aboard it. */
    public static void openNext(ServerPlayer player) {
        if (!(player.getVehicle() instanceof ShipStructure ship)) return;
        int[] list = containers(ship.blocks());
        if (list.length == 0) {
            player.sendOverlayMessage(Component.translatable("hud.galactica.cargo.none"));
            return;
        }
        int[] last = LAST.get(player.getUUID());
        int k = 0;
        if (last != null && last[0] == ship.getId()) {
            for (int j = 0; j < list.length; j++) if (list[j] == last[1]) k = (j + 1) % list.length;
        }
        open(player, ship, list[k]);
    }

    /** Opens container block {@code index} of {@code ship} for {@code player}. */
    public static void open(ServerPlayer player, ShipStructure ship, int index) {
        ShipBlocks blocks = ship.blocks();
        if (!isContainer(blocks, index)) return;
        BlockEntity be = blockEntity(ship, index);
        if (!(be instanceof Container stored)) return;
        int n = stored.getContainerSize();
        int rows = Math.clamp((n + 8) / 9, 1, 6);
        int[] list = containers(blocks);
        int ordinal = 1;
        for (int j = 0; j < list.length; j++) if (list[j] == index) ordinal = j + 1;
        CargoView view = new CargoView(rows * 9, ship, index, be, n);
        for (int i = 0; i < Math.min(n, rows * 9); i++) view.setItem(i, stored.getItem(i).copy());
        LAST.put(player.getUUID(), new int[] {ship.getId(), index});
        Component title = Component.translatable("container.galactica.ship_cargo", blocks.state(index).getBlock().getName(), ordinal, list.length);
        MenuType<?> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(type, id, inv, view, rows), title));
        Galactica.LOG.info("{} opened cargo {} ({}) of ship {}", player.getName().getString(), index, blocks.state(index).getBlock().getName().getString(), ship.getId());
    }

    /** The container's block entity, rebuilt from the ship's data (or fresh, for a container that never had any). */
    static @Nullable BlockEntity blockEntity(ShipStructure ship, int index) {
        ShipBlocks blocks = ship.blocks();
        BlockState state = blocks.state(index);
        CompoundTag tag = blocks.blockEntity(index);
        int c = blocks.cell(index);
        BlockPos pos = new BlockPos(ShipCell.unpackX(c), ShipCell.unpackY(c), ShipCell.unpackZ(c));
        if (tag != null) return BlockEntity.loadStatic(pos, state, tag, ship.registryAccess());
        return state.getBlock() instanceof EntityBlock eb ? eb.newBlockEntity(pos, state) : null;
    }

    /** Items currently stored in container block {@code index} (a copy), for wreck drops and tests. */
    public static java.util.List<ItemStack> contents(ShipStructure ship, int index) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        if (blockEntity(ship, index) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) out.add(c.getItem(i).copy());
        }
        return out;
    }

    /** Puts {@code stacks} into container block {@code index} from slot 0 (tests and prefab loading). */
    public static void store(ShipStructure ship, int index, java.util.List<ItemStack> stacks) {
        if (!(blockEntity(ship, index) instanceof Container c)) return;
        for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, i < stacks.size() ? stacks.get(i).copy() : ItemStack.EMPTY);
        ship.blocks().setBlockEntity(index, ((BlockEntity) c).saveWithFullMetadata(ship.registryAccess()));
    }

    /** The menu's container: the stored slots plus padding to whole rows; written back on close. */
    static final class CargoView extends SimpleContainer {
        private final ShipStructure ship;
        private final int index, stored;
        private final BlockEntity be;

        CargoView(int size, ShipStructure ship, int index, BlockEntity be, int stored) {
            super(size);
            this.ship = ship;
            this.index = index;
            this.be = be;
            this.stored = stored;
        }

        @Override
        public boolean stillValid(Player player) {
            if (ship.isRemoved() || !player.isAlive()) return false;
            if (player.getRootVehicle() == ship.getRootVehicle()) return true;
            return player.distanceTo(ship) < ship.spec().hullReach() + 8;
        }

        @Override
        public void stopOpen(ContainerUser user) {
            super.stopOpen(user);
            Container c = (Container) be;
            for (int i = 0; i < stored; i++) c.setItem(i, i < getContainerSize() ? getItem(i) : ItemStack.EMPTY);
            // Padding slots past the container's own size are not stored: hand anything put there back.
            for (int i = stored; i < getContainerSize(); i++) {
                ItemStack extra = getItem(i);
                if (extra.isEmpty() || !(user instanceof Player p) || p.getInventory().add(extra)) continue;
                p.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), extra));
            }
            if (!ship.isRemoved()) ship.blocks().setBlockEntity(index, be.saveWithFullMetadata(ship.registryAccess()));
        }
    }
}
