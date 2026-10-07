package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.galactica.ship.ShipLighting;
import org.joml.Vector3fc;

/**
 * Wraps the hull's vertex sink and replaces each vertex colour with the face's brightness under the host star:
 * {@link ShipLighting#shade} of the world-frame normal (ModelPart transforms normals by the pose, and entity poses
 * are world-oriented with the view rotation kept in the model-view matrix) against the direction to the star,
 * times the star's tint (0 … 1, so it fits the 8-bit vertex colour). Paired with
 * {@link ShipRenderTypes#HULL_LIT}, whose shader multiplies the gain back in (with a highlight shoulder) and uses no
 * other light.
 */
final class ShadedVertexConsumer implements VertexConsumer {
    private final VertexConsumer out;
    private final float lx, ly, lz, direct, tr, tg, tb, floor;

    ShadedVertexConsumer(VertexConsumer out, Vector3fc toStar, float direct, float[] tint) {
        this(out, toStar, direct, tint, 0F);
    }

    /** {@code floor}: least brightness of any face (the cockpit's instrument glow), before the tint. */
    ShadedVertexConsumer(VertexConsumer out, Vector3fc toStar, float direct, float[] tint, float floor) {
        this.out = out;
        this.lx = toStar.x();
        this.ly = toStar.y();
        this.lz = toStar.z();
        this.direct = direct;
        this.tr = tint[0];
        this.tg = tint[1];
        this.tb = tint[2];
        this.floor = floor;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords,
                          float nx, float ny, float nz) {
        float k = Math.max(floor, (float) ShipLighting.shade(nx * lx + ny * ly + nz * lz, direct));
        int a = color >>> 24, r = color >> 16 & 0xFF, g = color >> 8 & 0xFF, b = color & 0xFF;
        int lit = a << 24 | channel(r, k * tr) << 16 | channel(g, k * tg) << 8 | channel(b, k * tb);
        out.addVertex(x, y, z, lit, u, v, overlayCoords, lightCoords, nx, ny, nz);
    }

    private static int channel(int c, float k) {
        return Math.min(255, Math.round(c * k));
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) { return out.addVertex(x, y, z); }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return out.setColor(r, g, b, a); }
    @Override public VertexConsumer setColor(int color) { return out.setColor(color); }
    @Override public VertexConsumer setUv(float u, float v) { return out.setUv(u, v); }
    @Override public VertexConsumer setUv1(int u, int v) { return out.setUv1(u, v); }
    @Override public VertexConsumer setUv2(int u, int v) { return out.setUv2(u, v); }
    @Override public VertexConsumer setUv3(float u, float v) { return out.setUv3(u, v); }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return out.setNormal(x, y, z); }
    @Override public VertexConsumer setLineWidth(float width) { return out.setLineWidth(width); }
}
