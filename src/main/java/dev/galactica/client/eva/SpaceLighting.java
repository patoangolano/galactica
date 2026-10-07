package dev.galactica.client.eva;

import com.mojang.blaze3d.platform.Lighting;
import dev.galactica.Galactica;
import dev.galactica.client.ship.ShipEnvironment;
import dev.galactica.client.ship.StarshipRenderState;
import dev.galactica.mixin.client.eva.LightingAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;

/**
 * In galactica:space, vanilla's two fixed "sky" diffuse lights (from above, front and back) are replaced by the
 * system's star: both level lights point at it, so entities (an EVA player's body and first-person arm, items,
 * mobs) are lit on the side facing the star and fall to vanilla's 0.4 ambient term on the night side, instead of
 * being lit from a sky that is not there. The brightness comes from the lightmap route
 * ({@link ShipEnvironment#evaLight}); this only sets the direction. Far from any star (or in any other dimension)
 * vanilla's lights are restored. Updated once a tick, outside any render pass: the star moves by far less than a
 * degree per tick even at full impulse.
 *
 * <p>The level lights are global (vanilla has one pair for every entity in the level), so this also turns the
 * diffuse shading of every other entity in space toward the star, a ship's crew and anything else drawn with
 * entity lighting included. That is deliberate: it is the light they are all actually in, and it matches the
 * hull's own star shading ({@link ShipEnvironment#sample}); how bright each one is still comes from its own
 * lightmap route (the crew's {@link ShipEnvironment#passengerLight}, an EVA player's occlusion-tested
 * {@link ShipEnvironment#evaLight}).
 */
public final class SpaceLighting {
    private SpaceLighting() {}

    private static final StarshipRenderState SCRATCH = new StarshipRenderState();
    private static final Vector3f APPLIED = new Vector3f();
    private static boolean applied;

    public static void initClient() {
        ClientTickEvents.END_CLIENT_TICK.register(SpaceLighting::tick);
    }

    private static void tick(Minecraft mc) {
        Lighting lighting = mc.gameRenderer.lighting();
        if (mc.level == null || mc.player == null || mc.level.dimension() != Galactica.SPACE) {
            // Changing dimension already resets the lights (GameRenderer.setLevel); restore anyway if still marked.
            if (applied && mc.level != null) restore(mc, lighting);
            else if (mc.level == null) applied = false;
            return;
        }
        var cam = mc.gameRenderer.mainCamera().position();
        StarshipRenderState s = ShipEnvironment.sampleSpace(mc.level, cam.x, cam.y, cam.z, SCRATCH);
        if (s.toStar.lengthSquared() < 0.5f) {
            if (applied) restore(mc, lighting);
            return;
        }
        if (applied && APPLIED.dot(s.toStar) > 0.99999f) return;
        APPLIED.set(s.toStar);
        ((LightingAccessor) lighting).galactica$updateBuffer(Lighting.Entry.LEVEL, new Vector3f(APPLIED), new Vector3f(APPLIED));
        applied = true;
    }

    private static void restore(Minecraft mc, Lighting lighting) {
        lighting.updateLevel(mc.level.dimensionType().cardinalLightType());
        applied = false;
    }

    /** Star direction the level lights point at, or null while vanilla's lights are in use (autotest probe). */
    public static Vector3f appliedDirection() {
        return applied ? new Vector3f(APPLIED) : null;
    }
}
