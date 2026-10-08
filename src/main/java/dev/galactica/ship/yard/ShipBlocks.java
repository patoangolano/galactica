package dev.galactica.ship.yard;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks of a block-built ship, in the ship grid: body orientation (+Z nose, +Y dorsal, +X port; states already
 * rotated so), the helm's cell at 0,0,0. A palette of states, one packed cell ({@link ShipCell#pack}) and palette
 * index per block, and the block-entity data (chests, barrels…) of the blocks that had any, keyed by block index.
 * Immutable once built.
 */
public final class ShipBlocks {
    public static final ShipBlocks EMPTY = new ShipBlocks(List.of(), new int[0], new int[0], new Int2ObjectOpenHashMap<>());

    private final List<BlockState> palette;
    private final int[] cells, states;
    private final Int2ObjectMap<CompoundTag> blockEntities;
    private List<ShipCell> shipCells;

    private ShipBlocks(List<BlockState> palette, int[] cells, int[] states, Int2ObjectMap<CompoundTag> blockEntities) {
        this.palette = palette;
        this.cells = cells;
        this.states = states;
        this.blockEntities = blockEntities;
    }

    public int size() { return cells.length; }
    public boolean isEmpty() { return cells.length == 0; }
    public int cell(int i) { return cells[i]; }
    public BlockState state(int i) { return palette.get(states[i]); }
    public CompoundTag blockEntity(int i) { return blockEntities.get(i); }
    public int blockEntityCount() { return blockEntities.size(); }

    /** Indices of the blocks that carry block-entity data, in block order. */
    public int[] blockEntityIndices() {
        int[] out = blockEntities.keySet().toIntArray();
        java.util.Arrays.sort(out);
        return out;
    }

    /**
     * Server: replaces one block's block-entity data (a cargo container closed after use in flight). The only mutation
     * a built ship's blocks allow: the data never reaches clients ({@link #write}), so nothing has to be re-sent.
     */
    public void setBlockEntity(int i, CompoundTag tag) {
        if (i < 0 || i >= cells.length) throw new IndexOutOfBoundsException(i);
        if (this == EMPTY) throw new IllegalStateException("the empty block set is shared");
        blockEntities.put(i, tag);
    }

    /** Index of the block at ship-grid cell (x, y, z), or −1. */
    public int indexAt(int x, int y, int z) {
        if (Math.abs(x) > 511 || Math.abs(y) > 511 || Math.abs(z) > 511) return -1;
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap map = byCell;
        if (map == null) {
            map = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(cells.length);
            map.defaultReturnValue(-1);
            for (int i = 0; i < cells.length; i++) map.put(cells[i], i);
            byCell = map;
        }
        return map.get(ShipCell.pack(x, y, z));
    }

    private it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap byCell;

    /** What the stats see: part, mass and thruster nozzle direction of every block. */
    public List<ShipCell> shipCells() {
        if (shipCells == null) {
            List<ShipCell> out = new ArrayList<>(cells.length);
            for (int i = 0; i < cells.length; i++) {
                BlockState s = state(i);
                ShipPart part = s.getBlock() instanceof ShipPartBlock p ? p.part() : ShipPart.STRUCTURE;
                double mass = part == ShipPart.STRUCTURE ? structureMass(s) : part.massKg;
                Direction f = part == ShipPart.THRUSTER || part == ShipPart.DOCKING_PORT ? ShipPartBlock.facingOf(s) : null;
                int c = cells[i];
                out.add(new ShipCell(ShipCell.unpackX(c), ShipCell.unpackY(c), ShipCell.unpackZ(c), part, mass, f == null ? -1 : f.ordinal()));
            }
            shipCells = List.copyOf(out);
        }
        return shipCells;
    }

    /**
     * Mass of an ordinary block, kg: a hull is a shell, not solid material, so this is a density table by toughness
     * (glass ~245 kg, planks ~500, stone bricks ~425, iron blocks ~950, obsidian 3 t).
     */
    public static double structureMass(BlockState s) {
        return massForHardness(s.getBlock().defaultDestroyTime());
    }

    /** {@link #structureMass} by a block's hardness (destroy time; negative = unbreakable). */
    public static double massForHardness(float hardness) {
        float h = hardness < 0 ? 50 : hardness;
        return Math.clamp(200 + 150 * h, 30, 3_000);
    }

    // ------------------------------------------------------------------ building

    public static final class Builder {
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> index = new IdentityHashMap<>();
        private final it.unimi.dsi.fastutil.ints.IntArrayList cells = new it.unimi.dsi.fastutil.ints.IntArrayList();
        private final it.unimi.dsi.fastutil.ints.IntArrayList states = new it.unimi.dsi.fastutil.ints.IntArrayList();
        private final Int2ObjectMap<CompoundTag> blockEntities = new Int2ObjectOpenHashMap<>();

        public Builder add(int x, int y, int z, BlockState state, CompoundTag blockEntity) {
            int idx = index.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            if (blockEntity != null) blockEntities.put(cells.size(), blockEntity);
            cells.add(ShipCell.pack(x, y, z));
            states.add(idx);
            return this;
        }

        public ShipBlocks build() {
            return new ShipBlocks(List.copyOf(palette), cells.toIntArray(), states.toIntArray(), blockEntities);
        }
    }

    // ------------------------------------------------------------------ persistence (entity NBT)

    public void save(ValueOutput out) {
        var list = out.list("palette", BlockState.CODEC);
        for (BlockState s : palette) list.add(s);
        out.putIntArray("cells", cells);
        out.putIntArray("states", states);
        var bes = out.list("block_entities", CompoundTag.CODEC);
        for (var e : blockEntities.int2ObjectEntrySet()) {
            CompoundTag t = e.getValue().copy();
            t.putInt("galactica_index", e.getIntKey());
            bes.add(t);
        }
    }

    public static ShipBlocks load(ValueInput in) {
        List<BlockState> palette = new ArrayList<>();
        for (BlockState s : in.listOrEmpty("palette", BlockState.CODEC)) palette.add(s);
        int[] cells = in.getIntArray("cells").orElse(new int[0]);
        int[] states = in.getIntArray("states").orElse(new int[0]);
        if (cells.length != states.length || palette.isEmpty()) return EMPTY;
        for (int s : states) if (s < 0 || s >= palette.size()) return EMPTY;
        Int2ObjectMap<CompoundTag> bes = new Int2ObjectOpenHashMap<>();
        for (CompoundTag t : in.listOrEmpty("block_entities", CompoundTag.CODEC)) {
            int i = t.getIntOr("galactica_index", -1);
            if (i < 0 || i >= cells.length) continue;
            CompoundTag copy = t.copy();
            copy.remove("galactica_index");
            bes.put(i, copy);
        }
        return new ShipBlocks(List.copyOf(palette), cells, states, bes);
    }

    // ------------------------------------------------------------------ network (render data only: states and cells)

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(palette.size());
        for (BlockState s : palette) buf.writeVarInt(Block.getId(s));
        buf.writeVarInt(cells.length);
        for (int i = 0; i < cells.length; i++) {
            buf.writeInt(cells[i]);
            buf.writeVarInt(states[i]);
        }
    }

    /** Most blocks a payload may claim (a corrupt or hostile length must not allocate gigabytes). */
    private static final int READ_LIMIT = AssemblyFill.MAX_BLOCKS * 4;

    public static ShipBlocks read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > READ_LIMIT) throw new IllegalArgumentException("ship palette of " + n);
        List<BlockState> palette = new ArrayList<>(n);
        for (int i = 0; i < n; i++) palette.add(Block.stateById(buf.readVarInt()));
        int m = buf.readVarInt();
        if (m < 0 || m > READ_LIMIT) throw new IllegalArgumentException("ship of " + m + " blocks");
        int[] cells = new int[m], states = new int[m];
        for (int i = 0; i < m; i++) {
            cells[i] = buf.readInt();
            states[i] = buf.readVarInt();
            if (states[i] < 0 || states[i] >= n) throw new IllegalArgumentException("bad palette index");
        }
        return new ShipBlocks(List.copyOf(palette), cells, states, new Int2ObjectOpenHashMap<>());
    }
}
