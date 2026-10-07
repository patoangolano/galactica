package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import dev.galactica.nav.FloatingOrigin;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Caps the chunk view distance of galactica:space ({@link FloatingOrigin#SPACE_VIEW_DISTANCE}). Space chunks are
 * empty; what a player sees there is the sky and the ships, and a ship is only tracked within its own range (10
 * chunks) anyway. At the default 16 a craft at cruise speed, recentred every few seconds, made the server
 * generate, send and save ~1 100 columns per recentre and the client rebuild as many, which stalled both and
 * filled the heap.
 *
 * <p>The cap sits on {@code ChunkMap.setServerViewDistance}, the one place every path goes through: the
 * {@code ChunkMap} constructor (which is how a dedicated server applies {@code view-distance} at start-up, before
 * {@code PlayerList.setViewDistance} has any level to reach) and {@code ServerChunkCache.setViewDistance} (runtime
 * changes, and the integrated server's render-distance sync). {@code ChunkMap.level} is assigned before the
 * constructor's call, so it is set here.
 */
@Mixin(ChunkMap.class)
public abstract class SpaceViewDistanceMixin {
    @Shadow @Final private ServerLevel level;

    @ModifyVariable(method = "setServerViewDistance", at = @At("HEAD"), argsOnly = true)
    private int galactica$capSpace(int distance) {
        return level.dimension() == Galactica.SPACE ? Math.min(distance, FloatingOrigin.SPACE_VIEW_DISTANCE) : distance;
    }
}
