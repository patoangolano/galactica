package dev.galactica.mixin.nav;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player always tracks the craft they ride. Vanilla stops tracking an entity whose chunk has not been sent to the
 * player yet, and a moved craft (floating-origin recentre, take-off into the space frame) sits in exactly such a
 * chunk for a few ticks: the server then removed the pilot's own ship from their client and re-added it once the
 * chunk arrived, so the ship blinked out, the pilot was dismounted client-side and the camera jumped to the bare
 * player and back. Riders of the same root vehicle (the vehicle itself, its other passengers) now stay tracked.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityRiderMixin {
    @Shadow @Final private Entity entity;

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean galactica$keepOwnCraft(ChunkMap map, ServerPlayer player, int chunkX, int chunkZ, Operation<Boolean> original) {
        return original.call(map, player, chunkX, chunkZ)
                || (player.getRootVehicle() != player && entity.getRootVehicle() == player.getRootVehicle());
    }
}
