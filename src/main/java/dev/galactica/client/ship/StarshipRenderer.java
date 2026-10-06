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

/**
 * Draws the ship rotated by its full attitude quaternion about the hull centre, then the exhaust layer
 * emissive (full-bright, translucent) with a grey tint that brightens with thrust and pulses while the
 * hyperdrive spools.
 */
public class StarshipRenderer extends EntityRenderer<Starship, StarshipRenderState> {
    public static final Identifier TEXTURE = Galactica.id("textures/entity/ship/starship.png");
    private static final int FULL_BRIGHT = 0xF000F0;
    private final StarshipModel hull;
    private final StarshipModel.Glow glow;

    public StarshipRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.hull = new StarshipModel(context.bakeLayer(StarshipModel.HULL_LAYER));
        this.glow = new StarshipModel.Glow(context.bakeLayer(StarshipModel.GLOW_LAYER));
        this.shadowRadius = 1.5F;
    }

    @Override
    public StarshipRenderState createRenderState() {
        return new StarshipRenderState();
    }

    @Override
    public void extractRenderState(Starship ship, StarshipRenderState state, float partialTicks) {
        super.extractRenderState(ship, state, partialTicks);
        ship.getAttitude(partialTicks, state.attitude);
        state.hurtTime = Math.max(ship.getHurtTime() - partialTicks, 0F);
        state.hurtDir = ship.getHurtDir();
        state.thrust = Math.abs(ship.throttle());
        state.spool = ship.isSpooling() ? 1F - (ship.spoolTicks() - partialTicks) / DriveRules.SPOOL_TICKS : 0F;
    }

    /** The model reaches 2.9 blocks fore and aft of the 3 × 2 box; cull on the real extent. */
    @Override
    protected AABB getBoundingBoxForCulling(Starship ship, float partialTicks) {
        return ship.getBoundingBox().inflate(1.5, 1.0, 1.5);
    }

    @Override
    public void submit(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0F, 0.75F, 0F);
        pose.rotate(state.attitude);
        if (state.hurtTime > 0F) pose.rotateDegrees(Axis.ZP, Mth.sin(state.hurtTime) * state.hurtTime * 0.4F * state.hurtDir);
        pose.scale(-1F, -1F, 1F);
        out.submitModel(hull, state, pose, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);

        float pulse = state.spool > 0 ? 0.5F + 0.5F * Mth.sin(state.ageInTicks * (0.4F + 1.6F * state.spool)) : 0F;
        float bright = Math.min(1F, 0.3F + 0.7F * Math.max(state.thrust, pulse * state.spool));
        int c = Math.round(bright * 255F);
        int tint = 0xFF000000 | c << 16 | c << 8 | c;
        out.submitModel(glow, state, pose, RenderTypes.entityTranslucentEmissive(TEXTURE), FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, tint, null, state.outlineColor);
        pose.popPose();
        super.submit(state, pose, out, camera);
    }
}
