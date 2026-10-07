package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.galactica.Galactica;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.ExhaustPhysics;
import dev.galactica.ship.ShipDimensions;
import dev.galactica.ship.Starship;
import net.minecraft.client.Minecraft;
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
 * Draws the ship rotated by its full attitude quaternion about the hull centre, in layers plus exhaust:
 * <ol>
 *   <li><b>Hull.</b> In space it is lit by the host star ({@link ShipEnvironment}): per-face Lambert shading toward
 *       the star under auto-exposure, through {@link ShipRenderTypes#HULL_LIT} and {@link ShadedVertexConsumer},
 *       so the ship is never the black silhouette the lightmap gives it there. Elsewhere, and whenever a shader pack
 *       is active (the pack lights entities itself) or the lit pipeline is unavailable, the vanilla entity render
 *       type with the world's light.</li>
 *   <li><b>Cockpit interior</b>: seats, consoles, stick, throttle and panel, lit the same way with a small
 *       instrument-glow floor so it reads at night; its keys are emissive.</li>
 *   <li><b>Canopy glass</b>: translucent, so the crew shows through it. Hidden from inside, where
 *       {@link CockpitRenderer} draws the inner glass and the live display instead.</li>
 *   <li><b>Lights</b> (nav, strobes): emissive, always full-bright.</li>
 *   <li><b>Engine cores</b>: emissive, tinted by throttle, pulsing while the hyperdrive spools.</li>
 *   <li><b>Exhaust</b>: plumes, nozzle glow and RCS puffs ({@link ExhaustRenderer}).</li>
 * </ol>
 */
public class StarshipRenderer extends EntityRenderer<Starship, StarshipRenderState> {
    public static final Identifier TEXTURE = Galactica.id("textures/entity/ship/starship.png");
    static final int FULL_BRIGHT = 0xF000F0;
    /** Hull centre (the model pivot) above the entity origin, blocks. */
    public static final float PIVOT_BLOCKS = ShipDimensions.PIVOT;
    /** Least brightness of a cockpit face under the star-lit route: panel backlight and console glow. */
    private static final float COCKPIT_GLOW = 0.32F;
    /** Least block light of the cockpit under the lightmap route (night, shader packs): the same glow. */
    private static final int COCKPIT_BLOCK_LIGHT = 9;
    private final StarshipModel hull;
    private final Layers layers;
    /** Render-thread scratch for {@link #cameraInBody}. */
    private static final Quaternionf INV = new Quaternionf(), VIEW = new Quaternionf();
    /** Scratch pose for the lit-route callbacks, which run one at a time on the render thread. */
    private static final PoseStack LIT_POSE = new PoseStack();

    /** The layers both renderers (built-in and GeckoLib) draw from the shared code. */
    public record Layers(StarshipModel lights, StarshipModel cores, StarshipModel glass, StarshipModel cockpit,
                         StarshipModel cockpitLights) {
        public static Layers bake(EntityRendererProvider.Context context) {
            return new Layers(new StarshipModel(context.bakeLayer(StarshipModel.LIGHTS_LAYER)),
                    new StarshipModel(context.bakeLayer(StarshipModel.CORES_LAYER)),
                    new StarshipModel(context.bakeLayer(StarshipModel.GLASS_LAYER)),
                    new StarshipModel(context.bakeLayer(StarshipModel.COCKPIT_LAYER)),
                    new StarshipModel(context.bakeLayer(StarshipModel.COCKPIT_LIGHTS_LAYER)));
        }
    }

    public StarshipRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.hull = new StarshipModel(context.bakeLayer(StarshipModel.HULL_LAYER));
        this.layers = Layers.bake(context);
        this.shadowRadius = 3.5F;
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
        state.cockpitView = cockpitView(ship);
        ShipEnvironment.sample(ship, state);
        // For the lightmap routes (shader pack, GeckoLib): never the zero lightmap of space.
        if (state.starLit) state.lightCoords = ShipEnvironment.lightCoords(state.direct);
    }

    /** True when the local player rides this ship and looks out of it in first person. */
    public static boolean cockpitView(Starship ship) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() == ship && mc.options.getCameraType().isFirstPerson();
    }

    @Override
    protected AABB getBoundingBoxForCulling(Starship ship, float partialTicks) {
        return cullingBox(ship);
    }

    /** Nozzle exit plane behind the hull centre (throat 35 + bell 6.8 design units), plus the pod offset, blocks. */
    private static final double EXIT_BEHIND_CENTRE = (35.0 + 6.8 + 1.0) * ShipDimensions.PX;
    /** Farthest hull point from the hull centre: the hull alone, any attitude. */
    private static final double HULL_REACH = ShipDimensions.HULL_REACH;
    /** RCS puff reach beyond its jet, blocks (ExhaustRenderer's RCS_LENGTH plus its end radius). */
    private static final double RCS_REACH = ExhaustRenderer.RCS_LENGTH + 0.3 * ShipDimensions.SCALE;

    /**
     * Frustum-culling box for the ship and everything it draws: the hull at any attitude, and the plume out to its
     * tip ({@link ExhaustPhysics#maxReach}, which also covers the tip's radius), inflated on every axis because the
     * attitude can point the jet anywhere. Shared with the GeckoLib renderer.
     */
    public static AABB cullingBox(Starship ship) {
        double plume = ExhaustPhysics.maxReach(Math.abs(ship.throttle()), ExhaustRenderer.EXIT_RADIUS);
        double reach = Math.max(HULL_REACH, EXIT_BEHIND_CENTRE + Math.max(plume, RCS_REACH));
        // A cube of half-size `reach` about the hull centre (PIVOT_BLOCKS above the entity origin).
        double x = ship.getX(), y = ship.getY() + PIVOT_BLOCKS, z = ship.getZ();
        return new AABB(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach);
    }

    @Override
    public void submit(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        bodyPose(state, pose);
        pose.scale(-1F, -1F, 1F);
        submitHull(state, pose, out);
        pose.popPose();
        submitOverlays(state, pose, out, camera, layers);
        super.submit(state, pose, out, camera);
    }

    /** Entity pose → body frame: hull-centre offset, attitude, and the hit wobble. */
    private static void bodyPose(StarshipRenderState state, PoseStack pose) {
        pose.translate(0F, PIVOT_BLOCKS, 0F);
        pose.rotate(state.attitude);
        if (state.hurtTime > 0F) pose.rotateDegrees(Axis.ZP, Mth.sin(state.hurtTime) * state.hurtTime * 0.4F * state.hurtDir);
    }

    /**
     * Exhaust, the cockpit interior, the canopy glass and the emissive layers, from the entity pose; shared with the
     * GeckoLib renderer (which draws only the hull itself).
     */
    public static void submitOverlays(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera,
                                      Layers layers) {
        pose.pushPose();
        bodyPose(state, pose);
        cameraInBody(state, camera);
        if (ExhaustRenderer.active(state) && ShipRenderTypes.exhaustUsable()) ExhaustRenderer.submit(state, pose, out);
        if (state.cockpitView) CockpitRenderer.submit(state, pose, out);
        pose.scale(-1F, -1F, 1F);
        submitLit(state, pose, out, layers.cockpit(), COCKPIT_GLOW, cockpitLight(state.lightCoords));
        submitGlow(state, pose, out, layers);
        // The glass lives on the canopy bone, which setupAnim hides in the cockpit view.
        out.submitModel(layers.glass(), state, pose, RenderTypes.entityTranslucentCull(TEXTURE), state.lightCoords,
                OverlayTexture.NO_OVERLAY, -1, null, state.outlineColor);
        pose.popPose();
    }

    /** The lightmap-route light of the cockpit: the world's, but never below the instrument glow. */
    static int cockpitLight(int lightCoords) {
        int block = Math.max(lightCoords & 0xFFFF, COCKPIT_BLOCK_LIGHT << 4);
        return lightCoords & 0xFFFF0000 | block;
    }

    private void submitHull(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out) {
        submitLit(state, pose, out, hull, 0F, state.lightCoords);
    }

    /**
     * One opaque layer: star-lit in space ({@link ShipRenderTypes#HULL_LIT_TYPE}, with {@code floor} the least face
     * brightness), otherwise the vanilla entity type at {@code light}.
     */
    private static void submitLit(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, StarshipModel model,
                                  float floor, int light) {
        // A glowing ship (spectral arrow) needs the outline that only the model route submits.
        if (state.starLit && state.outlineColor == 0 && !IrisCompat.shaderPackActive() && ShipRenderTypes.hullUsable()) {
            int overlay = OverlayTexture.NO_OVERLAY;
            out.submitCustomGeometry(pose, ShipRenderTypes.HULL_LIT_TYPE, (p, buf) -> {
                model.setupAnim(state);
                PoseStack local = LIT_POSE;
                local.last().set(p);
                model.root().render(local, new ShadedVertexConsumer(buf, state.toStar, state.direct, state.tint, floor),
                        FULL_BRIGHT, overlay, -1);
            });
        } else {
            out.submitModel(model, state, pose, TEXTURE, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
    }

    /** The emissive layers: lights and cockpit keys always on, cores following thrust and the hyperdrive spool. */
    private static void submitGlow(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, Layers layers) {
        // A slow swell on the light layer reads as live electronics without strobing the whole ship.
        float breathe = 0.85F + 0.15F * Mth.sin(state.ageInTicks * 0.15F);
        out.submitModel(layers.lights(), state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE), FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, grey(breathe), null, state.outlineColor);
        out.submitModel(layers.cockpitLights(), state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE), FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, grey(0.9F), null, state.outlineColor);
        float pulse = state.spool > 0 ? 0.5F + 0.5F * Mth.sin(state.ageInTicks * (0.4F + 1.6F * state.spool)) : 0F;
        float bright = Math.min(1F, 0.15F + 0.85F * Math.max(state.thrust, pulse * state.spool));
        out.submitModel(layers.cores(), state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE), FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, grey(bright), null, state.outlineColor);
    }

    private static int grey(float k) {
        int c = Math.round(Math.clamp(k, 0F, 1F) * 255F);
        return 0xFF000000 | c << 16 | c << 8 | c;
    }

    /** Camera position and billboard axes in the ship's body frame, for the exhaust geometry. */
    static void cameraInBody(StarshipRenderState state, CameraRenderState camera) {
        Quaternionf inv = INV.set(state.attitude).conjugate();
        state.cameraBody.set((float) (camera.pos.x - state.x), (float) (camera.pos.y - state.y - PIVOT_BLOCKS), (float) (camera.pos.z - state.z));
        inv.transform(state.cameraBody);
        Quaternionf view = VIEW.set(inv).mul(camera.orientation);
        view.transform(state.camRightBody.set(1, 0, 0));
        view.transform(state.camUpBody.set(0, 1, 0));
    }
}
