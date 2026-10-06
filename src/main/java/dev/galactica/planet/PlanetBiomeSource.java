package dev.galactica.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.Galactica;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * One biome for the whole surface, chosen by the slot's current body: {@code planet_<type>_<sky>}
 * ({@link SurfaceAtmosphere#biomePath}). Every combination is listed in the dimension JSON so the
 * registry knows them all and feature ordering is fixed at load.
 */
public final class PlanetBiomeSource extends BiomeSource {
    public static final MapCodec<PlanetBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(s -> s.biomes),
            Codec.intRange(0, PlanetSlots.COUNT - 1).fieldOf("slot").forGetter(s -> s.slot)
    ).apply(i, i.stable(PlanetBiomeSource::new)));

    /** Biome used by a free slot and by any body whose combination is missing from the list. */
    static final String FALLBACK = "planet_barren_black";

    private final HolderSet<Biome> biomes;
    private final int slot;

    public PlanetBiomeSource(HolderSet<Biome> biomes, int slot) {
        this.biomes = biomes;
        this.slot = slot;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.stream();
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        Holder<Biome> chosen = current();
        return (x, y, z) -> chosen;
    }

    /** The biome for the slot's body right now; looked up by key, not list position. */
    Holder<Biome> current() {
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        String want = rt != null ? SurfaceAtmosphere.biomePath(rt.body()) : FALLBACK;
        Holder<Biome> fallback = null;
        for (Holder<Biome> h : biomes) {
            var key = h.unwrapKey().orElse(null);
            if (key == null || !Galactica.MOD_ID.equals(key.identifier().getNamespace())) continue;
            String path = key.identifier().getPath();
            if (path.equals(want)) return h;
            if (path.equals(FALLBACK)) fallback = h;
        }
        return fallback != null ? fallback : biomes.get(0);
    }
}
