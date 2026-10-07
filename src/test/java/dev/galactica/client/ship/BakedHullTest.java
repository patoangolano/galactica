package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The baked hull must emit exactly what walking the ModelPart tree emits, whatever the bones' animation. */
class BakedHullTest {
    @Test
    void bakedEqualsWalkedTree() {
        // The layer as StarshipModel builds it (StarshipModel itself needs the mod bootstrapped).
        net.minecraft.client.model.geom.builders.MeshDefinition mesh = new net.minecraft.client.model.geom.builders.MeshDefinition();
        StarshipGeometry.hull(mesh.getRoot());
        net.minecraft.client.model.geom.ModelPart root = net.minecraft.client.model.geom.builders.LayerDefinition
                .create(mesh, StarshipGeometry.TEXTURE_SIZE, StarshipGeometry.TEXTURE_SIZE).bakeRoot();
        var r = root;
        BakedBones model = new BakedBones(root, r.getChild("canopy"), r.getChild("gear_front"), r.getChild("gear_left"),
                r.getChild("gear_right"), r.getChild("nozzle_left"), r.getChild("nozzle_right"));
        // Animated bones posed away from rest, and the canopy hidden, as setupAnim does in flight.
        root.getChild("gear_front").xRot = -1.1F;
        root.getChild("gear_left").visible = false;
        root.getChild("nozzle_left").yRot = 0.2F;
        root.getChild("canopy").xRot = -0.4F;
        for (int pass = 0; pass < 2; pass++) {
            PoseStack pose = new PoseStack();
            pose.translate(3.5F, -2F, 7F);
            pose.rotate(Axis.YP.rotationDegrees(37F + pass * 50F));
            pose.rotate(Axis.XP.rotationDegrees(-21F));
            pose.scale(-1F, -1F, 1F);
            List<float[]> walked = new ArrayList<>(), baked = new ArrayList<>();
            root.render(pose, sink(walked), 0xF000F0, 0, -1);
            model.render(pose, sink(baked), 0xF000F0, 0);
            assertTrue(walked.size() > 1000, "the hull has geometry");
            assertEquals(walked.size(), baked.size());
            sort(walked);
            sort(baked);
            for (int i = 0; i < walked.size(); i++) {
                float[] a = walked.get(i), b = baked.get(i);
                for (int k = 0; k < a.length; k++) assertEquals(a[k], b[k], 1e-4F, "vertex " + i + " component " + k);
            }
            root.getChild("canopy").visible = false;
        }
    }

    /** Not an assertion (timings vary by machine): prints walked vs baked time per frame for the record. */
    @Test
    void bakedIsFaster() {
        net.minecraft.client.model.geom.builders.MeshDefinition mesh = new net.minecraft.client.model.geom.builders.MeshDefinition();
        StarshipGeometry.hull(mesh.getRoot());
        net.minecraft.client.model.geom.ModelPart r = net.minecraft.client.model.geom.builders.LayerDefinition
                .create(mesh, StarshipGeometry.TEXTURE_SIZE, StarshipGeometry.TEXTURE_SIZE).bakeRoot();
        BakedBones baked = new BakedBones(r, r.getChild("canopy"), r.getChild("gear_front"), r.getChild("gear_left"),
                r.getChild("gear_right"), r.getChild("nozzle_left"), r.getChild("nozzle_right"));
        float[] acc = new float[1];
        VertexConsumer sink = new VertexConsumer() {
            @Override
            public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
                acc[0] += x + y + z + u + v + nx + ny + nz;
            }

            @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
            @Override public VertexConsumer setColor(int rr, int g, int b, int a) { return this; }
            @Override public VertexConsumer setColor(int color) { return this; }
            @Override public VertexConsumer setUv(float u, float v) { return this; }
            @Override public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }
            @Override public VertexConsumer setUv3(float u, float v) { return this; }
            @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
            @Override public VertexConsumer setLineWidth(float width) { return this; }
        };
        PoseStack pose = new PoseStack();
        pose.rotate(Axis.YP.rotationDegrees(37F));
        double walked = 0, bakedT = 0;
        for (int round = 0; round < 3; round++) {
            long t = System.nanoTime();
            for (int i = 0; i < 2000; i++) r.render(pose, sink, 0xF000F0, 0, -1);
            walked = (System.nanoTime() - t) / 2000e3;
            t = System.nanoTime();
            for (int i = 0; i < 2000; i++) baked.render(pose, sink, 0xF000F0, 0);
            bakedT = (System.nanoTime() - t) / 2000e3;
        }
        System.out.printf(java.util.Locale.ROOT, "[bench] ship hull per draw: ModelPart walk %.1f us, baked %.1f us (%.0f)%n", walked, bakedT, acc[0]);
    }

    private static void sort(List<float[]> v) {
        v.sort((a, b) -> {
            for (int k = 0; k < a.length; k++) {
                // Rounded so float noise between the two routes cannot reorder equal vertices.
                int c = Long.compare(Math.round(a[k] * 1e3), Math.round(b[k] * 1e3));
                if (c != 0) return c;
            }
            return 0;
        });
    }

    private static VertexConsumer sink(List<float[]> out) {
        return new VertexConsumer() {
            @Override
            public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
                out.add(new float[] {x, y, z, u, v, nx, ny, nz, color, overlay, light});
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
        };
    }
}
