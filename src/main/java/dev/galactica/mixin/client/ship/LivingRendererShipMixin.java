package dev.galactica.mixin.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.galactica.client.ship.ShipRiderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns a Starship's riders with the hull. Vanilla then applies its own yaw ({@code 180 - bodyRot}); prepending
 * {@code Q = attitude · Ry(bodyRot)} about the seat makes the total orientation the ship's attitude (the ship's yaw
 * field is {@code -heading}, so for a level ship {@code Q = 1}). A pitched, rolled or inverted ship then keeps its
 * crew seated in the cockpit instead of world-upright and through the canopy.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingRendererShipMixin {
    @Unique private static final Quaternionf GALACTICA$TURN = new Quaternionf();

    @Inject(method = "setupRotations", at = @At("HEAD"))
    private void galactica$turnWithShip(LivingEntityRenderState state, PoseStack pose, float bodyRot, float entityScale, CallbackInfo ci) {
        ShipRiderState rider = (ShipRiderState) state;
        if (!rider.galactica$ridesShip()) return;
        // The body faces the nose (below), so the head turns by the look in the hull's frame: zero for the pilot,
        // whose look is the hull's (a head pitched by the hull's pitch again would nod twice); for crew, where they
        // look, so other players see a passenger's head follow their view. Vanilla's own values here are relative to
        // the rider's bodyRot, which the turn below overrides.
        float[] head = rider.galactica$headTurn();
        state.yRot = head[0];
        state.xRot = head[1];
        float pivot = rider.galactica$seatHeight() / Math.max(1e-3F, entityScale);
        Quaternionf q = GALACTICA$TURN.set(rider.galactica$shipAttitude()).rotateY((float) Math.toRadians(bodyRot));
        pose.translate(0F, pivot, 0F);
        pose.rotate(q);
        pose.translate(0F, -pivot, 0F);
    }
}
