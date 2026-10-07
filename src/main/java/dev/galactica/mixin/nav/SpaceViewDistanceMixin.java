package dev.galactica.mixin.nav;

import dev.galactica.Galactica;
import dev.galactica.nav.FloatingOrigin;
import net.minecraft.server.level.ServerChunkCache;
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
 * filled the heap. {@code PlayerList.setViewDistance} sets every level, so the cap is applied here, per level.
 */
@Mixin(ServerChunkCache.class)
public abstract class SpaceViewDistanceMixin {
    @Shadow @Final private ServerLevel level;

    @ModifyVariable(method = "setViewDistance", at = @At("HEAD"), argsOnly = true)
    private int galactica$capSpace(int distance) {
        return level.dimension() == Galactica.SPACE ? Math.min(distance, FloatingOrigin.SPACE_VIEW_DISTANCE) : distance;
    }
}
