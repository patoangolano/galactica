package dev.galactica.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.Galactica;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.planet.SlotPolicy.Entry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Stream;

/**
 * The pool of surface dimensions. Datapack dimensions cannot be created at runtime, so
 * {@link #COUNT} of them ({@code galactica:surface_0..15}) exist permanently and are lent to bodies.
 *
 * <p><b>Persistence.</b> {@code data/galactica/planet_slots.dat} records, per slot, the body's
 * {@link PlanetAddress} and the game time it was last occupied. The terrain is a pure function of
 * the body, so nothing else needs saving.
 *
 * <p><b>Recycling.</b> A slot's chunks keep whatever body generated them, so a slot can only change
 * owner once its region files are gone, and a datapack level cannot be unloaded on a live server.
 * Eviction therefore happens at startup only, before levels exist: if fewer than half the slots are
 * free, the least recently used are wiped until half are. A session can always reach at least
 * {@link #COUNT}/2 new bodies; revisiting a body reuses its slot and keeps what was built there.
 *
 * <p><b>Threads.</b> Chunk generation reads {@link #runtime} from worker threads; entries are
 * immutable and published through an {@link AtomicReferenceArray}.
 */
public final class PlanetSlots extends SavedData {
    public static final int COUNT = 16;

    /** Everything generation and gameplay need about an assigned slot, rebuilt from its address. */
    public record Runtime(int slot, PlanetAddress address, PlanetAddress.Resolved resolved, SurfaceGrid grid,
                          TerrainSampler terrain, SurfaceAtmosphere.SkyClass sky) {
        public dev.galactica.system.Body body() {
            return resolved.body();
        }

        static Runtime build(int slot, PlanetAddress a, Galaxy galaxy) {
            PlanetAddress.Resolved r = a.resolve(galaxy);
            if (r == null || !r.body().type().solidSurface) return null;
            SurfaceGrid grid = SurfaceGrid.of(r.body().radiusMetres());
            return new Runtime(slot, a, r, grid, TerrainSampler.of(r.body(), grid), SurfaceAtmosphere.skyClass(r.body()));
        }
    }

    public static final Codec<PlanetSlots> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().fieldOf("slots").forGetter(d -> d.entries),
            Codec.INT.optionalFieldOf("terrain_version", 1).forGetter(d -> d.terrainVersion)
    ).apply(i, PlanetSlots::new));

    // A non-null DataFixTypes is required: SavedDataStorage.readTagFromDisk calls update() on it.
    public static final SavedDataType<PlanetSlots> TYPE = new SavedDataType<>(Galactica.id("planet_slots"),
            PlanetSlots::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private static final AtomicReferenceArray<Runtime> RUNTIME = new AtomicReferenceArray<>(COUNT);
    private static final TerrainSampler PLACEHOLDER = TerrainSampler.placeholder();

    private final List<Entry> entries;
    /** {@link TerrainSampler#GENERATION} the slots' saved chunks were generated with. */
    private int terrainVersion;

    public PlanetSlots() {
        entries = new ArrayList<>(COUNT);
        for (int k = 0; k < COUNT; k++) entries.add(Entry.FREE);
        terrainVersion = TerrainSampler.GENERATION;
    }

    private PlanetSlots(List<Entry> loaded, int terrainVersion) {
        this();
        for (int k = 0; k < Math.min(COUNT, loaded.size()); k++) entries.set(k, loaded.get(k));
        this.terrainVersion = terrainVersion;
    }

    public static PlanetSlots get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Entry entry(int slot) {
        return entries.get(slot);
    }

    // ---------------------------------------------------------------- dimension keys

    public static ResourceKey<Level> key(int slot) {
        return ResourceKey.create(Registries.DIMENSION, Galactica.id("surface_" + slot));
    }

    /** Slot index of a surface dimension, or −1 for any other dimension. */
    public static int slotOf(ResourceKey<Level> dim) {
        var id = dim.identifier();
        if (!Galactica.MOD_ID.equals(id.getNamespace()) || !id.getPath().startsWith("surface_")) return -1;
        try {
            int k = Integer.parseInt(id.getPath().substring("surface_".length()));
            return k >= 0 && k < COUNT ? k : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- runtime view

    /** The body in a slot, or null when the slot is free. Safe from any thread. */
    public static Runtime runtime(int slot) {
        return slot >= 0 && slot < COUNT ? RUNTIME.get(slot) : null;
    }

    /** Terrain for a slot; a free slot generates neutral barren ground rather than failing. */
    public static TerrainSampler terrain(int slot) {
        Runtime r = runtime(slot);
        return r != null ? r.terrain() : PLACEHOLDER;
    }

    // ---------------------------------------------------------------- assignment

    /**
     * Slot holding {@code a}, claiming a free one if needed, or −1 if every slot is taken by another
     * body. Then the least recently used is queued for the next restart's wipe by the startup pass.
     */
    public int claim(PlanetAddress a, long now, Galaxy galaxy) {
        int k = SlotPolicy.choose(entries, a);
        if (k < 0) return -1;
        if (entries.get(k).free() || RUNTIME.get(k) == null) {
            Runtime rt = Runtime.build(k, a, galaxy);
            if (rt == null) return -1;
            RUNTIME.set(k, rt);
        }
        entries.set(k, new Entry(Optional.of(a), now));
        setDirty();
        return k;
    }

    /** Marks a slot as used now, so LRU eviction spares it. */
    public void touch(int slot, long now) {
        Entry e = entries.get(slot);
        if (e.free() || e.lastUsed() >= now) return;
        entries.set(slot, new Entry(e.address(), now));
        setDirty();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * SERVER_STARTING: runs before {@code createLevels}, so no surface level is open. Wipes slots
     * over budget, then publishes the runtime view of every remaining one.
     */
    static void onServerStarting(MinecraftServer server) {
        for (int k = 0; k < COUNT; k++) RUNTIME.set(k, null);
        PlanetSlots data = get(server);
        Path root = server.getWorldPath(LevelResource.ROOT);
        if (data.terrainVersion != TerrainSampler.GENERATION) {
            // Chunks saved by another generator (the old 768-block slots) would show the old terrain over a void
            // and seams against new chunks: drop every slot's chunks, keep which body owns which slot.
            for (int k = 0; k < COUNT; k++) deleteTree(DimensionType.getStorageFolder(key(k), root));
            Galactica.LOG.info("Planet terrain generation {} -> {}: regenerating every landing slot", data.terrainVersion, TerrainSampler.GENERATION);
            data.terrainVersion = TerrainSampler.GENERATION;
            data.setDirty();
        }
        for (int k : SlotPolicy.evictionPlan(data.entries, COUNT / 2, s -> false)) {
            deleteTree(DimensionType.getStorageFolder(key(k), root));
            data.entries.set(k, Entry.FREE);
            data.setDirty();
            Galactica.LOG.info("Recycled landing slot surface_{}", k);
        }
        for (int k = 0; k < COUNT; k++) {
            Entry e = data.entries.get(k);
            if (e.free()) {
                // A slot with no owner must not keep chunks from a previous body.
                deleteTree(DimensionType.getStorageFolder(key(k), root));
                continue;
            }
            Runtime rt = Runtime.build(k, e.address().get(), Galactica.GALAXY);
            if (rt == null) {
                Galactica.LOG.warn("Landing slot surface_{} points at a body that no longer exists; freeing it", k);
                deleteTree(DimensionType.getStorageFolder(key(k), root));
                data.entries.set(k, Entry.FREE);
                data.setDirty();
            }
            RUNTIME.set(k, rt);
        }
    }

    static void onServerStopped(MinecraftServer server) {
        for (int k = 0; k < COUNT; k++) RUNTIME.set(k, null);
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            Galactica.LOG.error("Could not wipe {}", dir, e);
        }
    }
}
