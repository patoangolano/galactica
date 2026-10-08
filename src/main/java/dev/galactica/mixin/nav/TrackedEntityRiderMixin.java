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

    /**
     * The same for the distance test. A craft's rider is placed at their seat only when the craft ticks on the server,
     * while the pilot's move packets keep moving the craft itself; in a fast descent through space chunks still loading
     * that left the server-side pilot up to ~160 blocks behind their own ship, past the tracking range, and the ship was
     * removed from the pilot's client mid-flight. A rider of the same root vehicle is measured as standing on it.
     */
    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;position()Lnet/minecraft/world/phys/Vec3;"))
    private net.minecraft.world.phys.Vec3 galactica$riderRange(Entity tracked, Operation<net.minecraft.world.phys.Vec3> original,
                                                               @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ServerPlayer player) {
        if (player.getRootVehicle() != player && tracked.getRootVehicle() == player.getRootVehicle()) return player.position();
        return original.call(tracked);
    }
}
