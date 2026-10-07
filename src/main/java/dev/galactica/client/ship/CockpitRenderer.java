package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * What only the crew sees, from inside the cockpit in first person (the model layers draw the rest):
 * <ul>
 *   <li>the canopy glass seen from inside: the inner faces of the stepped glass sections
 *       ({@link StarshipGeometry#CANOPY}), faintly tinted with the glass swatch's streaks. The exterior glass cubes
 *       are hidden from here: their partitions between sections would show as panes across the view;</li>
 *   <li>the live instrument display ({@link CockpitDisplay}) on the panel face ({@link StarshipGeometry#SCREEN}),
 *       full-bright.</li>
 * </ul>
 * Geometry is in the body frame (blocks from the hull centre, +Y dorsal, +X port, +Z nose), under the entity pose
 * with the hull-centre offset and attitude applied but not the model's Y-down flip.
 */
final class CockpitRenderer {
    private CockpitRenderer() {}

    /** The glass swatch of the atlas, as UV (gen_starship.py MATERIALS["glass"] × UV_K, over TEXTURE_SIZE). */
    private static final float GLASS_U0 = 640F / 1024F, GLASS_U1 = 1024F / 1024F, GLASS_V0 = 416F / 1024F, GLASS_V1 = 576F / 1024F;
    /** Vertex alpha on the glass texture (itself ~0.38 opaque, streaks ~0.6): a faint tint, never a fog. */
    private static final int GLASS_TINT = 0x38D8E8FF;

    static void submit(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out) {
        int light = state.lightCoords;
        out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(StarshipRenderer.TEXTURE), (p, buf) -> glass(p, buf, light));
        if (CockpitDisplay.ready()) {
            out.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(CockpitDisplay.ID), CockpitRenderer::display);
        }
    }

    /** The display quad, facing aft (toward the crew): port (+X) is the crew's left, so u runs from +X to −X. */
    private static void display(PoseStack.Pose pose, VertexConsumer buf) {
        float[] s = StarshipGeometry.SCREEN;
        float x0 = s[0], x1 = s[1], y0 = s[2], y1 = s[3], z = s[4];
        vertex(buf, pose, x1, y1, z, 0F, 0F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
        vertex(buf, pose, x1, y0, z, 0F, 1F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
        vertex(buf, pose, x0, y0, z, 1F, 1F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
        vertex(buf, pose, x0, y1, z, 1F, 0F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
    }

    /**
     * Inner faces of the canopy: each section's roof and sides, the step where a taller or wider section meets the
     * next, the front of the last and the back of the first. The render type does not cull, so winding is free;
     * normals point inward for the lightmap shading.
     */
    private static void glass(PoseStack.Pose pose, VertexConsumer buf, int light) {
        float[][] c = StarshipGeometry.CANOPY;
        for (int i = 0; i < c.length; i++) {
            float z0 = c[i][0], z1 = c[i][1], hw = c[i][2], yb = c[i][3], yt = c[i][4];
            quad(buf, pose, light, -hw, yt, z0, hw, yt, z0, hw, yt, z1, -hw, yt, z1, 0, -1, 0);
            quad(buf, pose, light, hw, yb, z0, hw, yt, z0, hw, yt, z1, hw, yb, z1, -1, 0, 0);
            quad(buf, pose, light, -hw, yb, z0, -hw, yt, z0, -hw, yt, z1, -hw, yb, z1, 1, 0, 0);
            if (i == 0) quad(buf, pose, light, -hw, yb, z0, hw, yb, z0, hw, yt, z0, -hw, yt, z0, 0, 0, 1);
            if (i + 1 < c.length) {
                float nhw = c[i + 1][2], nyt = c[i + 1][4];
                // The part of this section's front not covered by the next: a roof band and two side bands.
                if (yt > nyt) quad(buf, pose, light, -hw, nyt, z1, hw, nyt, z1, hw, yt, z1, -hw, yt, z1, 0, 0, -1);
                if (hw > nhw) {
                    float top = Math.min(yt, nyt);
                    quad(buf, pose, light, nhw, yb, z1, hw, yb, z1, hw, top, z1, nhw, top, z1, 0, 0, -1);
                    quad(buf, pose, light, -hw, yb, z1, -nhw, yb, z1, -nhw, top, z1, -hw, top, z1, 0, 0, -1);
                }
            } else {
                quad(buf, pose, light, -hw, yb, z1, hw, yb, z1, hw, yt, z1, -hw, yt, z1, 0, 0, -1);
            }
        }
    }

    private static void quad(VertexConsumer buf, PoseStack.Pose pose, int light,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz) {
        // The whole glass swatch stretched over each pane: a faint gradient with a streak or two.
        vertex(buf, pose, ax, ay, az, GLASS_U0, GLASS_V1, GLASS_TINT, light, nx, ny, nz);
        vertex(buf, pose, bx, by, bz, GLASS_U1, GLASS_V1, GLASS_TINT, light, nx, ny, nz);
        vertex(buf, pose, cx, cy, cz, GLASS_U1, GLASS_V0, GLASS_TINT, light, nx, ny, nz);
        vertex(buf, pose, dx, dy, dz, GLASS_U0, GLASS_V0, GLASS_TINT, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               int color, int light, float nx, float ny, float nz) {
        buf.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
