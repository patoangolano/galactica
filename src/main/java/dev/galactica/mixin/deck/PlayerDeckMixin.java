package dev.galactica.mixin.deck;

import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.deck.Deck;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sneak on a built ship: a deck walker just walks slowly (sneak is not "leave the ship": walkers step off its edge);
 * a seated rider stands up on the deck beside the seat ({@link Deck#standUp}) when there is room, and only leaves the
 * ship the vanilla way when there is not.
 */
@Mixin(Player.class)
public abstract class PlayerDeckMixin {
    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void galactica$deck(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (!(self.getVehicle() instanceof ShipStructure ship)) return;
        if (Deck.isWalker(self)) {
            cir.setReturnValue(false);
            return;
        }
        if (self.isShiftKeyDown() && self instanceof ServerPlayer sp && Deck.standUp(sp, ship)) {
            sp.setShiftKeyDown(false);
            cir.setReturnValue(false);
        }
    }
}
