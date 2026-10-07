package dev.galactica.mixin.client.ship;

import dev.galactica.client.ship.ShipCamera;
import dev.galactica.client.ship.StarshipRenderer;
import dev.galactica.ship.Starship;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
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

    @Shadow public abstract Vec3 position();

    /** Clearance kept between the pulled-in camera and the block it hit, blocks (vanilla keeps 0.1 plus its probe box). */
    private static final double GALACTICA$CLIP_MARGIN = 0.3;

    /**
     * Pulls a chase or orbit camera in toward the ship when terrain lies between them, as vanilla's
     * {@code getMaxZoom} does for the player: a block ray (visual shapes, so glass and leaves count as in vanilla)
     * from the aim point, plus four rays offset ±0.1 across the view so the near plane does not clip into a wall
     * the centre ray just missed. The ship's own hull is an entity and never stops the ray.
     */
    private static Vec3 galactica$clipToTerrain(Entity ship, Vec3 aim, Vec3 wanted) {
        Vec3 span = wanted.subtract(aim);
        double length = span.length();
        if (length < 1e-3) return wanted;
        double best = length;
        for (int i = 0; i < 5; i++) {
            Vec3 off = i == 0 ? Vec3.ZERO : new Vec3((i & 1) == 0 ? 0.1 : -0.1, (i & 2) == 0 ? 0.1 : -0.1, 0.0);
            Vec3 from = aim.add(off), to = wanted.add(off);
            BlockHitResult hit = ship.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) best = Math.min(best, hit.getLocation().distanceTo(from) - GALACTICA$CLIP_MARGIN);
        }
        return best >= length ? wanted : aim.add(span.scale(Math.max(0.0, best) / length));
    }

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
    private void galactica$shipAttitude(float partialTicks, CallbackInfo ci) {
        if (this.entity == null || !(this.entity.getVehicle() instanceof Starship ship)) {
            ShipCamera.clearOrbit(); // a screenshot orbit never outlives the flight it was set for
            return;
        }
        if (ship.getControllingPassenger() != this.entity) {
            // Crew keep their own look, but in first person their eye rides the rotated seat like the pilot's.
            if (!this.detached) this.setPosition(ship.riderEyePosition(this.entity, partialTicks, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight)));
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
            Vector3f target = ship.getAttitude(partialTicks, new Quaternionf()).transform(ShipCamera.target(new Vector3f()));
            Vec3 aim = ship.getPosition(partialTicks).add(target.x, StarshipRenderer.PIVOT_BLOCKS + target.y, target.z);
            this.setPosition(aim);
            this.move(-ShipCamera.distance(), ShipCamera.lift(), 0.0F);
            this.setPosition(galactica$clipToTerrain(ship, aim, this.position()));
        } else {
            this.setPosition(ship.pilotEyePosition(this.entity, partialTicks, Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight)));
        }
    }
}
