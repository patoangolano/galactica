package dev.galactica.mixin.client.ship;

import dev.galactica.ship.Starship;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the pilot's camera the ship's full attitude, roll included. Vanilla's {@code setRotation} hard-codes
 * roll to 0; this runs at the tail of {@code alignWithEntity}, inside {@code Camera.update} and before
 * {@code prepareCullFrustum}, so the view matrix, frustum and first-person hand all see the same rotation.
 * In third person the camera chases from behind and above the hull.
 */
@Mixin(Camera.class)
public abstract class CameraShipMixin {
    @Shadow @Final private static Vector3fc FORWARDS;
    @Shadow @Final private static Vector3fc UP;
    @Shadow @Final private static Vector3fc LEFT;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private int matrixPropertiesDirty;
    @Shadow private Entity entity;
    @Shadow private boolean detached;

    @Shadow protected abstract void move(float forwards, float up, float right);

    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
    private void galactica$shipAttitude(float partialTicks, CallbackInfo ci) {
        if (this.entity == null || !(this.entity.getVehicle() instanceof Starship ship) || ship.getControllingPassenger() != this.entity) return;
        // Ship forward is +Z, the camera looks down −Z: append the same half-turn vanilla folds into "π − yRot".
        ship.getAttitude(partialTicks, this.rotation).rotateY((float) Math.PI);
        FORWARDS.rotate(this.rotation, this.forwards);
        UP.rotate(this.rotation, this.up);
        LEFT.rotate(this.rotation, this.left);
        this.matrixPropertiesDirty |= 3;
        if (this.detached) {
            this.setPosition(ship.getPosition(partialTicks).add(0.0, 0.75, 0.0));
            this.move(-9.0F, 2.0F, 0.0F);
        }
    }
}
