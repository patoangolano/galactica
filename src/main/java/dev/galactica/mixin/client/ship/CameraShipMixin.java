package dev.galactica.mixin.client.ship;

import dev.galactica.client.ship.ShipCamera;
import dev.galactica.client.ship.StarshipRenderer;
import dev.galactica.ship.Starship;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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
 * In third person the camera chases from behind and above the hull, tilted down onto it (or looks back at the
 * nose in the mirrored view, with the same tilt and lift in the camera's own frame, so both views look down onto the
 * hull), framed by {@link ShipCamera}; in first person the eye rides the rotated seat, so it stays in the canopy when rolled.
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
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;

    @Shadow protected abstract void move(float forwards, float up, float right);

    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
    private void galactica$shipAttitude(float partialTicks, CallbackInfo ci) {
        if (this.entity == null || !(this.entity.getVehicle() instanceof Starship ship) || ship.getControllingPassenger() != this.entity) {
            ShipCamera.clearOrbit(); // a screenshot orbit never outlives the flight it was set for
            return;
        }
        if (!this.detached) ShipCamera.clearOrbit();
        boolean mirrored = this.detached && Minecraft.getInstance().options.getCameraType().isMirrored();
        // Ship forward is +Z, the camera looks down −Z: append the same half-turn vanilla folds into "π − yRot".
        // The mirrored (front) view skips it and looks back at the nose, as vanilla turns that view by 180°.
        ship.getAttitude(partialTicks, this.rotation);
        if (!mirrored) this.rotation.rotateY((float) Math.PI);
        if (this.detached) ShipCamera.aim(this.rotation);
        FORWARDS.rotate(this.rotation, this.forwards);
        UP.rotate(this.rotation, this.up);
        LEFT.rotate(this.rotation, this.left);
        this.matrixPropertiesDirty |= 3;
        if (this.detached) {
            // Aimed at the hull centre (StarshipRenderer.PIVOT_BLOCKS above the entity origin), framed by ShipCamera.
            this.setPosition(ship.getPosition(partialTicks).add(0.0, StarshipRenderer.PIVOT_BLOCKS, 0.0));
            this.move(-ShipCamera.distance(), ShipCamera.lift(), 0.0F);
        } else {
            this.setPosition(ship.pilotEyePosition(this.entity, partialTicks, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight)));
        }
    }
}
