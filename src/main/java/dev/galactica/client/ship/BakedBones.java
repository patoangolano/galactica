package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A model tree drawn as {@code root.render(pose, out, light, overlay, -1)} draws it, with every bone except the
 * {@code animated} ones (direct children of the root) baked once into a flat vertex list. Walking the ship's
 * ModelPart tree (every cube, polygon and vertex through the bone matrices) was the largest CPU cost of a frame with
 * the ship in view; now only the few moving bones are walked. The static bones must never move, hide or change: the
 * root's own pose is applied at bake time. Render thread only.
 */
final class BakedBones {
    private final ModelPart root;
    private final ModelPart[] animated;
    /** x, y, z, u, v, nx, ny, nz per vertex in the root's parent frame; null until first drawn. */
    private float[] mesh;
    private final Vector3f scratch = new Vector3f();

    BakedBones(ModelPart root, ModelPart... animated) {
        this.root = root;
        this.animated = animated;
    }

    void render(PoseStack pose, VertexConsumer out, int light, int overlay) {
        if (mesh == null) bake();
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        float[] b = mesh;
        Vector3f v = scratch;
        // A face's four vertices share its normal: transform it once per face, as ModelPart does.
        float rx = Float.NaN, ry = 0, rz = 0, nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < b.length; i += 8) {
            if (b[i + 5] != rx || b[i + 6] != ry || b[i + 7] != rz) {
                rx = b[i + 5];
                ry = b[i + 6];
                rz = b[i + 7];
                last.transformNormal(rx, ry, rz, v);
                nx = v.x;
                ny = v.y;
                nz = v.z;
            }
            m.transformPosition(b[i], b[i + 1], b[i + 2], v);
            out.addVertex(v.x, v.y, v.z, -1, b[i + 3], b[i + 4], overlay, light, nx, ny, nz);
        }
        pose.pushPose();
        root.translateAndRotate(pose);
        for (ModelPart part : animated) part.render(pose, out, light, overlay, -1);
        pose.popPose();
    }

    private void bake() {
        boolean[] shown = new boolean[animated.length];
        for (int i = 0; i < animated.length; i++) {
            shown[i] = animated[i].visible;
            animated[i].visible = false;
        }
        try {
            Recorder r = new Recorder();
            root.render(new PoseStack(), r, 0, 0, -1);
            mesh = r.data.toFloatArray();
        } finally {
            for (int i = 0; i < animated.length; i++) animated[i].visible = shown[i];
        }
    }

    /** Collects what ModelPart emits (it only uses the one-call vertex form). */
    private static final class Recorder implements VertexConsumer {
        final FloatArrayList data = new FloatArrayList();

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords,
                              float nx, float ny, float nz) {
            data.add(x); data.add(y); data.add(z); data.add(u); data.add(v); data.add(nx); data.add(ny); data.add(nz);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
