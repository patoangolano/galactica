package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.galactica.Galactica;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.Starship;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

/**
 * Draws the ship rotated by its full attitude quaternion about the hull centre, in three layers plus exhaust:
 * <ol>
 *   <li><b>Hull.</b> In space it is lit by the host star ({@link ShipEnvironment}): per-face Lambert shading toward
 *       the star under auto-exposure, through {@link ShipRenderTypes#HULL_LIT} and {@link ShadedVertexConsumer},
 *       so the ship is never the black silhouette the lightmap gives it there. Elsewhere, and whenever a shader pack
 *       is active (the pack lights entities itself) or the lit pipeline is unavailable, the vanilla entity render
 *       type with the world's light.</li>
 *   <li><b>Lights</b> (nav, strobes, cockpit): emissive, always full-bright.</li>
 *   <li><b>Engine cores</b>: emissive, tinted by throttle, pulsing while the hyperdrive spools.</li>
 *   <li><b>Exhaust</b>: plumes, nozzle glow and RCS puffs ({@link ExhaustRenderer}).</li>
 * </ol>
 */
public class StarshipRenderer extends EntityRenderer<Starship, StarshipRenderState> {
    public static final Identifier TEXTURE = Galactica.id("textures/entity/ship/starship.png");
    static final int FULL_BRIGHT = 0xF000F0;
    /** Hull centre (the model pivot) above the entity origin, blocks. */
    public static final float PIVOT_BLOCKS = 0.75F;
    private final StarshipModel hull, lights, cores;

    public StarshipRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.hull = new StarshipModel(context.bakeLayer(StarshipModel.HULL_LAYER));
        this.lights = new StarshipModel(context.bakeLayer(StarshipModel.LIGHTS_LAYER));
        this.cores = new StarshipModel(context.bakeLayer(StarshipModel.CORES_LAYER));
        this.shadowRadius = 1.5F;
    }

    @Override
    public StarshipRenderState createRenderState() {
        return new StarshipRenderState();
    }

    @Override
    public void extractRenderState(Starship ship, StarshipRenderState state, float partialTicks) {
        super.extractRenderState(ship, state, partialTicks);
        extractShip(ship, state, partialTicks);
    }

    /** Everything the ship-specific layers need; shared with the GeckoLib renderer. */
    public static void extractShip(Starship ship, StarshipRenderState state, float partialTicks) {
        ship.getAttitude(partialTicks, state.attitude);
        state.hurtTime = Math.max(ship.getHurtTime() - partialTicks, 0F);
        state.hurtDir = ship.getHurtDir();
        state.thrust = Math.abs(ship.throttle());
        state.spool = ship.isSpooling() ? 1F - (ship.spoolTicks() - partialTicks) / DriveRules.SPOOL_TICKS : 0F;
        state.gear = ship.gearExtension(partialTicks);
        state.canopy = ship.canopyOpening(partialTicks);
        state.gimbalYaw = ship.gimbalYaw(partialTicks);
        state.gimbalPitch = ship.gimbalPitch(partialTicks);
        state.rcs = ship.rcsMask();
        ShipEnvironment.sample(ship, state);
        if (state.starLit) {
            // For the lightmap routes (shader pack, GeckoLib): bright inside a system, dim but visible far from stars.
            int level = Math.round(5F + 10F * state.direct);
            state.lightCoords = level << 20 | level << 4;
        }
    }

    /** Keeps the model and a full-length plume inside the frustum test: wings span 5 blocks, plumes up to ~7. */
    @Override
    protected AABB getBoundingBoxForCulling(Starship ship, float partialTicks) {
        double plume = 1.0 + 7.0 * Math.abs(ship.throttle());
        return ship.getBoundingBox().inflate(1.5 + plume, 1.0 + plume, 1.5 + plume);
    }

    @Override
    public void submit(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        bodyPose(state, pose);
        pose.scale(-1F, -1F, 1F);
        submitHull(state, pose, out);
        pose.popPose();
        submitOverlays(state, pose, out, camera, lights, cores);
        super.submit(state, pose, out, camera);
    }

    /** Entity pose → body frame: hull-centre offset, attitude, and the hit wobble. */
    private static void bodyPose(StarshipRenderState state, PoseStack pose) {
        pose.translate(0F, PIVOT_BLOCKS, 0F);
        pose.rotate(state.attitude);
        if (state.hurtTime > 0F) pose.rotateDegrees(Axis.ZP, Mth.sin(state.hurtTime) * state.hurtTime * 0.4F * state.hurtDir);
    }

    /** Exhaust and the emissive layers, from the entity pose; shared with the GeckoLib renderer. */
    public static void submitOverlays(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera,
                                      StarshipModel lights, StarshipModel cores) {
        pose.pushPose();
        bodyPose(state, pose);
        cameraInBody(state, camera);
        if (ExhaustRenderer.active(state) && ShipRenderTypes.exhaustUsable()) ExhaustRenderer.submit(state, pose, out);
        pose.scale(-1F, -1F, 1F);
        submitGlow(state, pose, out, lights, cores);
        pose.popPose();
    }


    private void submitHull(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out) {
        if (state.starLit && !IrisCompat.shaderPackActive() && ShipRenderTypes.hullUsable()) {
            StarshipModel model = hull;
            int overlay = OverlayTexture.NO_OVERLAY;
            out.submitCustomGeometry(pose, ShipRenderTypes.HULL_LIT_TYPE, (p, buf) -> {
                model.setupAnim(state);
                PoseStack local = new PoseStack();
                local.last().set(p);
                model.root().render(local, new ShadedVertexConsumer(buf, state.toStar, state.direct, state.tint),
                        FULL_BRIGHT, overlay, -1);
            });
        } else {
            out.submitModel(hull, state, pose, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
    }

    /** The emissive layers: lights always on, cores following thrust and the hyperdrive spool. */
    private static void submitGlow(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, StarshipModel lights, StarshipModel cores) {
        // A slow swell on the light layer reads as live electronics without strobing the whole ship.
        float breathe = 0.85F + 0.15F * Mth.sin(state.ageInTicks * 0.15F);
        out.submitModel(lights, state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE), FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, grey(breathe), null, state.outlineColor);
        float pulse = state.spool > 0 ? 0.5F + 0.5F * Mth.sin(state.ageInTicks * (0.4F + 1.6F * state.spool)) : 0F;
        float bright = Math.min(1F, 0.15F + 0.85F * Math.max(state.thrust, pulse * state.spool));
        out.submitModel(cores, state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE), FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, grey(bright), null, state.outlineColor);
    }

    private static int grey(float k) {
        int c = Math.round(Math.clamp(k, 0F, 1F) * 255F);
        return 0xFF000000 | c << 16 | c << 8 | c;
    }

    /** Camera position and billboard axes in the ship's body frame, for the exhaust geometry. */
    static void cameraInBody(StarshipRenderState state, CameraRenderState camera) {
        Quaternionf inv = new Quaternionf(state.attitude).conjugate();
        state.cameraBody.set((float) (camera.pos.x - state.x), (float) (camera.pos.y - state.y - PIVOT_BLOCKS), (float) (camera.pos.z - state.z));
        inv.transform(state.cameraBody);
        Quaternionf view = new Quaternionf(inv).mul(camera.orientation);
        view.transform(state.camRightBody.set(1, 0, 0));
        view.transform(state.camUpBody.set(0, 1, 0));
    }
}
