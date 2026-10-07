package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import dev.galactica.perf.Perf;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Empty space is never written to disk. A craft at cruise crosses ~400 chunk columns a second, and each one was
 * generated (flat, no layers), serialised, compressed and written to a region file only to be thrown away: that was
 * most of the server's allocation in flight and made the save grow with every trip.
 *
 * <p>A galactica:space chunk is skipped when it holds only air, no block entity, and no full chunk of it was ever on
 * disk ({@code chunkTypeCache} is −1: created empty this session, or only a proto-chunk on disk). Regenerating it is
 * exact, so nothing is lost; a chunk that was saved full once (a station, a wreck) is always saved, so breaking its
 * last block still reaches the disk. Priority above the default so that, with C2ME's {@code @Overwrite} of
 * {@code save}, this applies to the overwritten body; if a chunk system never calls it, chunks are saved as before.
 */
@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class SpaceChunkSaveMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ByteMap chunkTypeCache;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void galactica$skipEmptySpace(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (level.dimension() != Galactica.SPACE) return;
        if (chunkTypeCache.get(chunk.getPos().pack()) != (byte) -1) return;
        if (!chunk.getBlockEntitiesPos().isEmpty()) return;
        for (LevelChunkSection s : chunk.getSections()) if (!s.hasOnlyAir()) return;
        if (chunk.tryMarkSaved()) Perf.spaceSavesSkipped.increment();
        cir.setReturnValue(false);
    }
}
