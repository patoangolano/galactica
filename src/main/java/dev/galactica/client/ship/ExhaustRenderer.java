package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.galactica.ship.ExhaustPhysics;
import dev.galactica.ship.ShipAnimator;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Builds the main-engine plumes, nozzle glows and RCS puffs as additive geometry for {@link ShipRenderTypes#EXHAUST}.
 *
 * <p>Each jet is drawn as three planes that all contain the jet axis, 60° apart about it, the first turned to face
 * the camera (a cylindrical billboard) and the others adding parallax; their weights follow how squarely each faces
 * the viewer and sum to one. The fragment shader ray-marches each plane's line of sight through the round jet, so
 * the sum reads as a volume. Seen end-on the planes vanish edge-on and a camera-facing disc of the integrated
 * column takes over. Plume length, radius profile and shock-cell count come from {@link ExhaustPhysics}.
 *
 * <p>Geometry is in the body frame (blocks from the hull centre, +Z nose, +X port), under a pose that is the
 * entity pose with the hull-centre offset and the attitude applied but not the model's Y-down flip.
 */
final class ExhaustRenderer {
    private ExhaustRenderer() {}

    private static final float PX = 1F / 16F;
    /** Nozzle throat pivots (gimbal points) and exit plane, from the part table of gen_starship.py. */
    private static final float POD_X = 14F * PX, POD_Y = -0.5F * PX, THROAT_Z = -35F * PX, EXIT_FROM_THROAT = 6.8F * PX;
    /** Nozzle exit radius: the core plate's apothem, 4.4 px. */
    static final float EXIT_RADIUS = 4.4F * PX;
    /** Largest end-on disc, blocks: the integrated column is brightest within a few nozzle radii of the axis. */
    private static final float MAX_DISC_HALF = 1.5F;
    private static final float RCS_RADIUS = 0.06F, RCS_LENGTH = 0.7F, RCS_HALF_ANGLE_TAN = 0.32F;
    /** Render-thread scratch (the custom-geometry callbacks run one at a time on it). */
    private static final Matrix3f GIMBAL = new Matrix3f();
    private static final float[] WEIGHTS = new float[3];
    private static final Vector3f[] PLANES = {new Vector3f(), new Vector3f(), new Vector3f()};
    private static final Vector3f NORMAL = new Vector3f();
    /** The three axial planes sit 0°, 60° and 120° about the jet axis. */
    private static final float[] PLANE_COS = {1F, 0.5F, -0.5F}, PLANE_SIN = {0F, (float) Math.sqrt(0.75), (float) Math.sqrt(0.75)};
    private static final int KIND_PLUME = 0, KIND_DISC = 1, KIND_GLOW = 2, KIND_RCS = 3, KIND_RCS_DISC = 4;

    /** True if this ship has anything to draw this frame (never with the exhaustQuality setting at Off). */
    static boolean active(StarshipRenderState s) {
        if (dev.galactica.config.GalacticaConfigs.get().exhaustQuality() == dev.galactica.config.GalacticaConfig.ExhaustQuality.OFF) return false;
        return s.thrust > ExhaustPhysics.MIN_THROTTLE || s.spool > 0F || s.rcs != 0;
    }

    static void submit(StarshipRenderState s, PoseStack pose, SubmitNodeCollector out) {
        if (!active(s)) return;
        out.submitCustomGeometry(pose, ShipRenderTypes.EXHAUST_TYPE, (p, buf) -> build(s, p, buf));
    }

    private static void build(StarshipRenderState s, PoseStack.Pose pose, VertexConsumer buf) {
        ExhaustPhysics.Plume plume = ExhaustPhysics.plume(s.thrust, s.ambientBar, EXIT_RADIUS);
        float pulse = s.spool > 0 ? 0.5F + 0.5F * (float) Math.sin(s.ageInTicks * (0.4F + 1.6F * s.spool)) : 0F;
        float glow = Math.max(s.thrust, pulse * s.spool * 0.7F);
        Matrix3f gimbal = GIMBAL.rotationYXZ(s.gimbalYaw * (float) (Math.PI / 180), s.gimbalPitch * (float) (Math.PI / 180), 0F);
        for (int side = 0; side < 2; side++) {
            float sx = side == 0 ? POD_X : -POD_X;
            Vector3f axis = gimbal.transform(new Vector3f(0, 0, -1));
            Vector3f exit = new Vector3f(axis).mul(EXIT_FROM_THROAT).add(sx, POD_Y, THROAT_Z);
            if (plume.regime() != ExhaustPhysics.Regime.OFF) jet(buf, pose, s, side, exit, axis, plume);
            if (glow > 0.01F) {
                Vector3f c = new Vector3f(axis).mul(0.04F).add(exit);
                float half = EXIT_RADIUS * (2.2F + 1.6F * glow);
                disc(buf, pose, s, c, half, side, KIND_GLOW, glow, glow);
            }
        }
        int jetIndex = 0;
        for (ShipAnimator.RcsJet j : ShipAnimator.JETS) {
            jetIndex++;
            if ((s.rcs & j.command()) == 0) continue;
            Vector3f pos = new Vector3f(j.x(), j.y(), j.z());
            Vector3f dir = new Vector3f(j.dx(), j.dy(), j.dz());
            rcs(buf, pose, s, pos, dir, 2 + jetIndex);
        }
    }

    /** One main-engine plume: three weighted axial planes and the end-on disc. */
    private static void jet(VertexConsumer buf, PoseStack.Pose pose, StarshipRenderState s, int seed, Vector3f exit,
                            Vector3f axis, ExhaustPhysics.Plume plume) {
        Vector3f toCam = new Vector3f(s.cameraBody).sub(exit);
        if (toCam.lengthSquared() < 1e-6F) return;
        toCam.normalize();
        float cosV = axis.dot(toCam);
        float sinV = (float) Math.sqrt(Math.max(0F, 1F - cosV * cosV));
        Vector3f u0 = perpendicular(axis, toCam);
        Vector3f w0 = new Vector3f(axis).cross(u0).normalize();
        float side = smoothstep(0.08F, 0.45F, sinV) * (float) plume.intensity();
        float cells = (float) plume.shockCells();
        float[] weights = WEIGHTS;
        Vector3f[] us = PLANES;
        float total = 0F;
        for (int k = 0; k < 3; k++) {
            float c = PLANE_COS[k], sn = PLANE_SIN[k];
            us[k].set(u0.x * c + w0.x * sn, u0.y * c + w0.y * sn, u0.z * c + w0.z * sn);
            Vector3f n = NORMAL.set(axis).cross(us[k]);
            weights[k] = Math.abs(n.dot(toCam));
            total += weights[k];
        }
        if (side > 0.002F && total > 1e-4F) {
            for (int k = 0; k < 3; k++) {
                float wgt = weights[k] / total * side;
                if (wgt > 0.002F) strip(buf, pose, exit, axis, us[k], plume, seed, s.thrust, cells, KIND_PLUME, wgt);
            }
        }
        float end = smoothstep(0.55F, 0.95F, cosV) * (float) plume.intensity() * 0.7F;
        if (end > 0.002F) {
            Vector3f c = new Vector3f(axis).mul((float) plume.length() * 0.1F).add(exit);
            float half = Math.min(MAX_DISC_HALF, (float) plume.radiusAt(plume.length() * 0.15) * 1.4F);
            disc(buf, pose, s, c, half, seed, KIND_DISC, s.thrust, end);
        }
    }

    /** RCS puff: two weighted axial planes (camera-facing and its perpendicular) and an end-on disc. */
    private static void rcs(VertexConsumer buf, PoseStack.Pose pose, StarshipRenderState s, Vector3f at, Vector3f dir, int seed) {
        Vector3f toCam = new Vector3f(s.cameraBody).sub(at);
        if (toCam.lengthSquared() < 1e-6F) return;
        toCam.normalize();
        float cosV = dir.dot(toCam);
        float sinV = (float) Math.sqrt(Math.max(0F, 1F - cosV * cosV));
        Vector3f u0 = perpendicular(dir, toCam);
        Vector3f w0 = new Vector3f(dir).cross(u0).normalize();
        float wa = Math.abs(new Vector3f(dir).cross(u0).dot(toCam)), wb = Math.abs(new Vector3f(dir).cross(w0).dot(toCam));
        float side = smoothstep(0.08F, 0.45F, sinV);
        float sum = wa + wb;
        if (sum > 1e-4F && side > 0.002F) {
            strip(buf, pose, at, dir, u0, null, seed, 0F, 0F, KIND_RCS, wa / sum * side);
            strip(buf, pose, at, dir, w0, null, seed, 0F, 0F, KIND_RCS, wb / sum * side);
        }
        float end = smoothstep(0.55F, 0.95F, Math.abs(cosV));
        if (end > 0.002F) disc(buf, pose, s, new Vector3f(dir).mul(0.1F).add(at), RCS_RADIUS * 3.5F, seed, KIND_RCS_DISC, 0F, end);
    }

    /**
     * A quad strip along {@code axis} from {@code start}, half-width from the plume profile (or the RCS cone when
     * {@code plume} is null), spanned by {@code across}.
     */
    private static void strip(VertexConsumer buf, PoseStack.Pose pose, Vector3f start, Vector3f axis, Vector3f across,
                              ExhaustPhysics.Plume plume, int seed, float throttle, float cells, int kind, float weight) {
        float length = plume != null ? (float) plume.length() : RCS_LENGTH;
        int color = params(throttle, cells, kind, weight);
        float u = 4F * seed;
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f();
        // Config: exhaustQuality picks the strip resolution (LOW 6, MEDIUM 12 as shipped, HIGH 20).
        int segments = Math.max(1, dev.galactica.config.ConfigEffects.exhaustSegments(dev.galactica.config.GalacticaConfigs.get().exhaustQuality()));
        for (int i = 0; i < segments; i++) {
            float t0 = (float) i / segments, t1 = (float) (i + 1) / segments;
            float r0 = radius(plume, t0 * length), r1 = radius(plume, t1 * length);
            point(start, axis, across, t0 * length, -r0, a);
            point(start, axis, across, t0 * length, r0, b);
            point(start, axis, across, t1 * length, r1, c);
            point(start, axis, across, t1 * length, -r1, d);
            buf.addVertex(pose, a.x, a.y, a.z).setUv(u + t0, -1F).setColor(color);
            buf.addVertex(pose, b.x, b.y, b.z).setUv(u + t0, 1F).setColor(color);
            buf.addVertex(pose, c.x, c.y, c.z).setUv(u + t1, 1F).setColor(color);
            buf.addVertex(pose, d.x, d.y, d.z).setUv(u + t1, -1F).setColor(color);
        }
    }

    private static float radius(ExhaustPhysics.Plume plume, float x) {
        return plume != null ? (float) plume.radiusAt(x) : RCS_RADIUS + x * RCS_HALF_ANGLE_TAN;
    }

    private static void point(Vector3f start, Vector3f axis, Vector3f across, float along, float off, Vector3f dest) {
        dest.set(start).add(axis.x * along + across.x * off, axis.y * along + across.y * off, axis.z * along + across.z * off);
    }

    /** A camera-facing quad (the camera's right and up, in the body frame) with UV -1..1 about the centre. */
    private static void disc(VertexConsumer buf, PoseStack.Pose pose, StarshipRenderState s, Vector3f centre, float half,
                             int seed, int kind, float throttle, float weight) {
        int color = params(throttle, 0F, kind, weight);
        Vector3f r = new Vector3f(s.camRightBody).mul(half), up = new Vector3f(s.camUpBody).mul(half);
        float u = 4F * seed;
        vertex(buf, pose, centre, r, up, -1, -1, u, color);
        vertex(buf, pose, centre, r, up, 1, -1, u, color);
        vertex(buf, pose, centre, r, up, 1, 1, u, color);
        vertex(buf, pose, centre, r, up, -1, 1, u, color);
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose pose, Vector3f c, Vector3f r, Vector3f up, int i, int j,
                               float u, int color) {
        buf.addVertex(pose, c.x + r.x * i + up.x * j, c.y + r.y * i + up.y * j, c.z + r.z * i + up.z * j)
                .setUv(u + i, j).setColor(color);
    }

    /** Vertex colour as shader parameters: r throttle, g shock cells / 16, b kind / 4, a weight. */
    static int params(float throttle, float cells, int kind, float weight) {
        int r = Math.round(Math.clamp(throttle, 0F, 1F) * 255F);
        int g = Math.round(Math.clamp(cells / 16F, 0F, 1F) * 255F);
        int b = Math.round(kind / 4F * 255F);
        int a = Math.round(Math.clamp(weight, 0F, 1F) * 255F);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** Unit vector perpendicular to {@code axis}, in the plane facing {@code toCam} (any perpendicular if parallel). */
    private static Vector3f perpendicular(Vector3f axis, Vector3f toCam) {
        Vector3f u = new Vector3f(axis).cross(toCam);
        if (u.lengthSquared() < 1e-8F) u = new Vector3f(axis).cross(Math.abs(axis.y) < 0.9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0));
        return u.normalize();
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0F, 1F);
        return t * t * (3 - 2 * t);
    }
}
