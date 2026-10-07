package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * What only the crew sees, from inside the cockpit in first person (the model layers draw the rest):
 * <ul>
 *   <li>the canopy glass seen from inside, as one smooth shell lofted through the stepped glass sections
 *       ({@link StarshipGeometry#CANOPY}): from the eye, which is inside it, every line of sight crosses exactly one
 *       pane, so the tint is uniform. The exterior glass cubes are hidden from here (their steps and the faces
 *       between sections would stack as darker bands and rectangles across the view);</li>
 *   <li>the live instrument display ({@link CockpitDisplay}) on the panel face ({@link StarshipGeometry#SCREEN}), and
 *       behind its window the attitude ball, turned every frame by the same interpolated attitude the camera uses.
 *       Both through {@link ShipRenderTypes#COCKPIT_DISPLAY}: full-bright and unfogged. Under a shader pack the
 *       canvas carries the ball and draws with the vanilla emissive type.</li>
 * </ul>
 * Geometry is in the body frame (blocks from the hull centre, +Y dorsal, +X port, +Z nose), under the entity pose
 * with the hull-centre offset and attitude applied but not the model's Y-down flip.
 */
final class CockpitRenderer {
    private CockpitRenderer() {}

    /**
     * One texel of plain glass (gen_starship.py MATERIALS["glass"] × UV_K: the swatch's top-left, off its reflection
     * streaks), so the panes are a uniform faint tint: stretched streaks read as scratches across the whole view.
     */
    private static final float GLASS_U = 641.5F / 1024F, GLASS_V = 417.5F / 1024F;
    /** Vertex colour on the glass texel (itself ~0.38 opaque): a faint cool tint, never a fog. */
    private static final int GLASS_TINT = 0x50C8DCFF;
    /** The ball sits this far behind the canvas (toward the panel, inside the bezel), blocks. */
    private static final float BALL_DEPTH = 0.004F;
    private static final int BALL_SEGMENTS = 40;
    private static final float[] ATT = new float[2];

    static void submit(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out) {
        int light = state.lightCoords;
        out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(StarshipRenderer.TEXTURE), (p, buf) -> glass(p, buf, light));
        submitDisplay(state, pose, out, StarshipGeometry.SCREEN);
    }

    /**
     * The live display and its attitude ball on a screen rectangle {@code s} = {x0, x1, y0, y1, z} (body frame, facing
     * aft toward the crew): the Kestrel's panel, or the screen above a block-built ship's helm.
     */
    static void submitDisplay(StarshipRenderState state, PoseStack pose, SubmitNodeCollector out, float[] s) {
        if (!CockpitDisplay.ready()) return;
        if (CockpitDisplay.ballOnGpu()) {
            CockpitDisplay.angles(state.attitude, CockpitDisplay.up(), ATT);
            float pitch = ATT[0], roll = ATT[1];
            out.submitCustomGeometry(pose, ShipRenderTypes.COCKPIT_ADI_TYPE, (p, buf) -> ball(p, buf, pitch, roll, s));
            out.submitCustomGeometry(pose, ShipRenderTypes.COCKPIT_DISPLAY_TYPE, (p, buf) -> display(p, buf, false, s));
        } else {
            out.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(CockpitDisplay.ID), (p, buf) -> display(p, buf, true, s));
        }
    }

    /**
     * The display quad, facing aft (toward the crew): port (+X) is the crew's left, so u runs from +X to −X.
     * {@code entity}: the vanilla entity vertex layout (light, overlay, normal) instead of position-uv-colour.
     */
    private static void display(PoseStack.Pose pose, VertexConsumer buf, boolean entity, float[] s) {
        float x0 = s[0], x1 = s[1], y0 = s[2], y1 = s[3], z = s[4];
        if (entity) {
            vertex(buf, pose, x1, y1, z, 0F, 0F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
            vertex(buf, pose, x1, y0, z, 0F, 1F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
            vertex(buf, pose, x0, y0, z, 1F, 1F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
            vertex(buf, pose, x0, y1, z, 1F, 0F, -1, StarshipRenderer.FULL_BRIGHT, 0, 0, -1);
        } else {
            plain(buf, pose, x1, y1, z, 0F, 0F);
            plain(buf, pose, x1, y0, z, 0F, 1F);
            plain(buf, pose, x0, y0, z, 1F, 1F);
            plain(buf, pose, x0, y1, z, 1F, 0F);
        }
    }

    /**
     * The attitude ball: a disc in the canvas's ball window, its texture coordinates taken from the pitch tape turned
     * by {@code roll} (radians, right wing down positive) and shifted by {@code pitch} (degrees). The tape mapping is
     * affine, so a fan of quads samples it exactly; the canvas ring hides the disc's polygon edge.
     */
    private static void ball(PoseStack.Pose pose, VertexConsumer buf, float pitch, float roll, float[] s) {
        float perPx = (s[1] - s[0]) / CockpitDisplay.W;
        float cx = s[1] - CockpitDisplay.BALL_CX * perPx, cy = s[3] - CockpitDisplay.BALL_CY * perPx, z = s[4] + BALL_DEPTH;
        float r = CockpitDisplay.BALL_R;
        float hx = (float) Math.cos(roll), hy = (float) -Math.sin(roll), nx = (float) -Math.sin(roll), ny = (float) -Math.cos(roll);
        float pitchPx = pitch * CockpitDisplay.PX_PER_DEG;
        float k = CockpitDisplay.TAPE_PX_PER_DEG / CockpitDisplay.PX_PER_DEG; // tape pixels per canvas pixel
        float[] u = new float[BALL_SEGMENTS + 1], v = new float[BALL_SEGMENTS + 1], bx = new float[BALL_SEGMENTS + 1], by = new float[BALL_SEGMENTS + 1];
        for (int i = 0; i <= BALL_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / BALL_SEGMENTS;
            float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r; // canvas pixels, y down
            u[i] = (CockpitDisplay.TAPE_W / 2F + (dx * hx + dy * hy) * k) / CockpitDisplay.TAPE_W;
            v[i] = (CockpitDisplay.TAPE_H / 2F - (dx * nx + dy * ny + pitchPx) * k) / CockpitDisplay.TAPE_H;
            bx[i] = cx - dx * perPx; // canvas x grows toward −X
            by[i] = cy - dy * perPx;
        }
        float uc = 0.5F, vc = (CockpitDisplay.TAPE_H / 2F - pitchPx * k) / CockpitDisplay.TAPE_H;
        for (int i = 0; i < BALL_SEGMENTS; i++) {
            plain(buf, pose, cx, cy, z, uc, vc);
            plain(buf, pose, bx[i], by[i], z, u[i], v[i]);
            plain(buf, pose, bx[i + 1], by[i + 1], z, u[i + 1], v[i + 1]);
            plain(buf, pose, bx[i + 1], by[i + 1], z, u[i + 1], v[i + 1]);
        }
    }

    /**
     * The inside of the canopy: a shell through stations at the ends of the glass and at each joint between two
     * sections (where it takes the mean of their widths and heights), each bay a roof and two sides, closed at both
     * ends. The render type does not cull, so winding is free; normals point inward for the lightmap shading.
     */
    private static void glass(PoseStack.Pose pose, VertexConsumer buf, int light) {
        float[][] c = StarshipGeometry.CANOPY;
        int n = c.length;
        float[][] st = new float[n + 1][]; // z, half-width, y-bottom, y-top
        st[0] = new float[] {c[0][0], c[0][2], c[0][3], c[0][4]};
        for (int i = 1; i < n; i++) {
            st[i] = new float[] {c[i][0], (c[i - 1][2] + c[i][2]) / 2, (c[i - 1][3] + c[i][3]) / 2, (c[i - 1][4] + c[i][4]) / 2};
        }
        st[n] = new float[] {c[n - 1][1], c[n - 1][2], c[n - 1][3], c[n - 1][4]};
        for (int i = 0; i < n; i++) {
            float[] a = st[i], b = st[i + 1];
            quad(buf, pose, light, -a[1], a[3], a[0], a[1], a[3], a[0], b[1], b[3], b[0], -b[1], b[3], b[0], 0, -1, 0);
            quad(buf, pose, light, a[1], a[2], a[0], a[1], a[3], a[0], b[1], b[3], b[0], b[1], b[2], b[0], -1, 0, 0);
            quad(buf, pose, light, -a[1], a[2], a[0], -a[1], a[3], a[0], -b[1], b[3], b[0], -b[1], b[2], b[0], 1, 0, 0);
        }
        float[] r = st[0], f = st[n];
        quad(buf, pose, light, -r[1], r[2], r[0], r[1], r[2], r[0], r[1], r[3], r[0], -r[1], r[3], r[0], 0, 0, 1);
        quad(buf, pose, light, -f[1], f[2], f[0], f[1], f[2], f[0], f[1], f[3], f[0], -f[1], f[3], f[0], 0, 0, -1);
    }

    private static void quad(VertexConsumer buf, PoseStack.Pose pose, int light,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz) {
        vertex(buf, pose, ax, ay, az, GLASS_U, GLASS_V, GLASS_TINT, light, nx, ny, nz);
        vertex(buf, pose, bx, by, bz, GLASS_U, GLASS_V, GLASS_TINT, light, nx, ny, nz);
        vertex(buf, pose, cx, cy, cz, GLASS_U, GLASS_V, GLASS_TINT, light, nx, ny, nz);
        vertex(buf, pose, dx, dy, dz, GLASS_U, GLASS_V, GLASS_TINT, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               int color, int light, float nx, float ny, float nz) {
        buf.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    /** Position, uv, colour (white): the layout of {@link ShipRenderTypes#COCKPIT_DISPLAY}. */
    private static void plain(VertexConsumer buf, PoseStack.Pose pose, float x, float y, float z, float u, float v) {
        buf.addVertex(pose, x, y, z).setUv(u, v).setColor(-1);
    }
}
