package dev.galactica.mixin.client.ship;

import dev.galactica.ship.Starship;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sneak is the ship's down-thruster, but the server dismounts any rider whose input says "shift"
 * ({@code Player.rideTick → wantsToStopRiding}). While piloting a ship that is not resting on the ground,
 * the shift bit is cleared from the input packet, so the pilot can descend without stepping out into
 * vacuum; landed, sneak exits as usual. Client prediction still reads the real key state.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerShipInputMixin {
    @ModifyArg(method = "sendChanges()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerInputPacket;<init>(Lnet/minecraft/world/entity/player/Input;)V"))
    private Input galactica$holdSeat(Input in) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!in.shift() || !(self.getVehicle() instanceof Starship ship) || ship.getControllingPassenger() != self) return in;
        // Docked (in a hangar or at a port) the craft is held by its carrier: stepping out is safe.
        boolean parked = ship.isDocked() || ship.onGround() && ship.getDeltaMovement().lengthSqr() < 0.0025; // < 1 m/s
        return parked ? in : new Input(in.forward(), in.backward(), in.left(), in.right(), in.jump(), false, in.sprint());
    }
}
