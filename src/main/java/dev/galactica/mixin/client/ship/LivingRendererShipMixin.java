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
        // The turn already carries the look: the pilot's pitch is the hull's, so a head pitched by it again would nod
        // twice. Crew heads face along the hull too (their free look is a camera matter, not the model's).
        state.xRot = 0F;
        float pivot = rider.galactica$seatHeight() / Math.max(1e-3F, entityScale);
        Quaternionf q = GALACTICA$TURN.set(rider.galactica$shipAttitude()).rotateY((float) Math.toRadians(bodyRot));
        pose.translate(0F, pivot, 0F);
        pose.rotate(q);
        pose.translate(0F, -pivot, 0F);
    }
}
