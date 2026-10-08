package dev.galactica.ship;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The scanner array: every {@link #PERIOD} ticks, for each built ship with a powered scanner ({@code ShipSystems
 * #scannerRange}) and a player aboard, the server lists what is around it (other craft, unmanned ones as derelicts,
 * and on a planet the ore in the ground below) and sends it to everyone aboard ({@link Payload}). The client adds the
 * nearest bodies from its own sky model and shows the list on the cockpit display's second page and in the map panel.
 */
public final class ShipScanner {
    private ShipScanner() {}

    public static final int PERIOD = 40;
    /** Ore is searched within this many blocks of the hull (sections without ore are skipped by their palette). */
    public static final int ORE_RADIUS = 40;
    static final int MAX_CONTACTS = 10;

    public enum Kind { SHIP, DERELICT, ORE, BODY }

    /** One contact: kind, name, offset from the scanning ship (blocks, world frame), and a count (ore blocks). */
    public record Contact(Kind kind, String name, float dx, float dy, float dz, int count) {
        public double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        static final StreamCodec<FriendlyByteBuf, Contact> CODEC = StreamCodec.of(
                (buf, c) -> {
                    buf.writeByte(c.kind.ordinal());
                    buf.writeUtf(c.name, 64);
                    buf.writeFloat(c.dx);
                    buf.writeFloat(c.dy);
                    buf.writeFloat(c.dz);
                    buf.writeVarInt(c.count);
                },
                buf -> new Contact(Kind.values()[Math.clamp(buf.readByte(), 0, Kind.values().length - 1)], buf.readUtf(64),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));
    }

    /** Server → client: the scan of the ship {@code shipId}. */
    public record Payload(int shipId, float range, List<Contact> contacts) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Galactica.id("ship_scan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.shipId);
                    buf.writeFloat(p.range);
                    buf.writeVarInt(p.contacts.size());
                    for (Contact c : p.contacts) Contact.CODEC.encode(buf, c);
                },
                buf -> {
                    int id = buf.readVarInt();
                    float range = buf.readFloat();
                    int n = Math.min(buf.readVarInt(), 64);
                    List<Contact> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(Contact.CODEC.decode(buf));
                    return new Payload(id, range, list);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 26.3 keeps only gold, iron and copper ores as BlockTags constants; the others are the same data tags by id.
    private static final List<TagKey<Block>> ORES = List.of(tag("diamond_ores"), tag("emerald_ores"), BlockTags.GOLD_ORES,
            tag("redstone_ores"), tag("lapis_ores"), BlockTags.IRON_ORES, BlockTags.COPPER_ORES, tag("coal_ores"));

    private static TagKey<Block> tag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.withDefaultNamespace(path));
    }
    private static final String[] ORE_NAMES = {"diamond", "emerald", "gold", "redstone", "lapis", "iron", "copper", "coal"};

    /** Server tick end. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % PERIOD != 0) return;
        java.util.Map<Integer, Payload> done = new java.util.HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ShipStructure ship = scanningShip(p);
            if (ship == null) continue;
            Payload scan = done.computeIfAbsent(ship.getId(), id -> scan(ship));
            ServerPlayNetworking.send(p, scan);
        }
    }

    /** The ship whose scanner serves {@code p}: the one they ride, else the carrier it is docked in, if powered. */
    static ShipStructure scanningShip(Player p) {
        for (Entity e = p.getVehicle(); e != null; e = e.getVehicle()) {
            if (e instanceof ShipStructure s && s.systems().scannerRange() > 0) return s;
        }
        return null;
    }

    /** One scan around {@code ship}. */
    public static Payload scan(ShipStructure ship) {
        double range = ship.systems().scannerRange();
        List<Contact> out = new ArrayList<>();
        Entity root = ship.getRootVehicle();
        AABB box = ship.getBoundingBox().inflate(range);
        for (Starship other : ship.level().getEntitiesOfClass(Starship.class, box, o -> o.getRootVehicle() != root && !o.isRemoved())) {
            double dx = other.getX() - ship.getX(), dy = other.getY() - ship.getY(), dz = other.getZ() - ship.getZ();
            if (dx * dx + dy * dy + dz * dz > range * range) continue;
            boolean manned = false;
            for (Entity e : other.getRootVehicle().getIndirectPassengers()) if (e instanceof Player) manned = true;
            String name = other.hasCustomName() ? other.getCustomName().getString()
                    : other instanceof ShipStructure s ? "Ship (" + s.blocks().size() + " blocks)" : "Kestrel";
            out.add(new Contact(manned ? Kind.SHIP : Kind.DERELICT, name, (float) dx, (float) dy, (float) dz, 0));
        }
        if (ship.level().dimension() != Galactica.SPACE && ship.level() instanceof ServerLevel level) ores(level, ship, out);
        out.sort(Comparator.comparingDouble(Contact::distance));
        if (out.size() > MAX_CONTACTS) out = new ArrayList<>(out.subList(0, MAX_CONTACTS));
        return new Payload(ship.getId(), (float) range, List.copyOf(out));
    }

    /** Ore within {@link #ORE_RADIUS} of the hull: one contact per kind, at its nearest block, with the count. */
    static void ores(ServerLevel level, ShipStructure ship, List<Contact> out) {
        int r = ORE_RADIUS;
        int cx0 = Mth.floor(ship.getX() - r) >> 4, cx1 = Mth.floor(ship.getX() + r) >> 4;
        int cz0 = Mth.floor(ship.getZ() - r) >> 4, cz1 = Mth.floor(ship.getZ() + r) >> 4;
        int y0 = Math.max(level.getMinY(), Mth.floor(ship.getY() - r)), y1 = Math.min(level.getMaxY(), Mth.floor(ship.getY() + r));
        int[] count = new int[ORES.size()];
        double[] best = new double[ORES.size()];
        float[][] at = new float[ORES.size()][];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                for (int si = level.getSectionIndex(y0); si <= level.getSectionIndex(y1); si++) {
                    LevelChunkSection section = chunk.getSection(si);
                    if (section.hasOnlyAir() || !section.getStates().maybeHas(s -> isOre(s.getBlock()))) continue;
                    int base = level.getSectionYFromSectionIndex(si) << 4;
                    for (int i = 0; i < 4096; i++) {
                        int x = i & 15, z = i >> 4 & 15, y = i >> 8;
                        var state = section.getBlockState(x, y, z);
                        int k = oreIndex(state.getBlock());
                        if (k < 0) continue;
                        double dx = (cx << 4) + x + 0.5 - ship.getX(), dy = base + y + 0.5 - ship.getY(), dz = (cz << 4) + z + 0.5 - ship.getZ();
                        double d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 > r * r) continue;
                        count[k]++;
                        if (d2 < best[k]) {
                            best[k] = d2;
                            at[k] = new float[] {(float) dx, (float) dy, (float) dz};
                        }
                    }
                }
            }
        }
        for (int k = 0; k < ORES.size(); k++) {
            if (count[k] > 0) out.add(new Contact(Kind.ORE, ORE_NAMES[k], at[k][0], at[k][1], at[k][2], count[k]));
        }
    }

    private static boolean isOre(Block b) {
        return oreIndex(b) >= 0;
    }

    private static int oreIndex(Block b) {
        var holder = b.builtInRegistryHolder();
        for (int k = 0; k < ORES.size(); k++) if (holder.is(ORES.get(k))) return k;
        return -1;
    }
}
