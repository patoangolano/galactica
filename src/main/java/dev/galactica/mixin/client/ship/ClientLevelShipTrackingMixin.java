package dev.galactica.mixin.client.ship;

import dev.galactica.Galactica;
import dev.galactica.ship.Starship;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the local player in their ship when the client briefly stops tracking it. A ship flying ~14 blocks a tick
 * can enter a chunk section the client has not received yet, a few blocks ahead of the pilot (a block-built ship's
 * centre sits well away from its helm, and in a retrograde burn it leads the pilot); the client entity manager then
 * ends its tracking and {@code onTrackingEnd} unrides everyone, although the server never dismounted anybody, so it
 * sends nothing to remount them. The pilot was left floating beside a ship the autopilot still thought it flew.
 * Here the local player's craft is remembered when that happens, and the player is put back in their seat as soon
 * as the ship is tracked again (within 10 s, and only if the player has not boarded anything meanwhile).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks")
public abstract class ClientLevelShipTrackingMixin {
    @Unique private static int galactica$lostShip = -1;
    @Unique private static long galactica$lostAt;

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void galactica$rememberCraft(Entity entity, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player != null && entity instanceof Starship ship && !ship.isRemoved() && player.getVehicle() == ship) {
            galactica$lostShip = ship.getId();
            galactica$lostAt = player.level().getGameTime();
            if (Boolean.getBoolean("galactica.autotest")) Galactica.LOG.info("[autotest] client stopped tracking the piloted ship {} at {}", ship.getId(), ship.position());
        }
    }

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void galactica$reboard(Entity entity, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || entity.getId() != galactica$lostShip || !(entity instanceof Starship ship)) return;
        galactica$lostShip = -1;
        if (player.getVehicle() != null || ship.isRemoved() || player.level().getGameTime() - galactica$lostAt > 200) return;
        boolean ok = player.startRiding(ship, true, false);
        if (Boolean.getBoolean("galactica.autotest")) Galactica.LOG.info("[autotest] client tracks ship {} again: pilot reboarded {}", ship.getId(), ok);
    }
}
