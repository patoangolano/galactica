package dev.galactica.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.galactica.Galactica;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.ShipStructure;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a block-built ship ({@link ShipStructure}) from its baked mesh ({@link BuiltShipMesh}): one pose (pivot
 * offset, attitude, hit wobble) and a flat walk over the vertex lists each frame, like the Kestrel's static bones.
 * <ul>
 *   <li>In space the hull is lit by the host star exactly as the Kestrel is ({@link ShadedVertexConsumer}: Lambert
 *       per face toward the star, star tint, ambient floor) through {@link ShipRenderTypes#BLOCK_HULL_LIT_TYPE}, the
 *       Kestrel's hull shader on the block atlas; light-emitting blocks keep their full colour. Elsewhere, under a
 *       shader pack or if that pipeline failed, the vanilla entity cutout type with the world's light.</li>
 *   <li>Translucent blocks (stained glass, ice) through the vanilla entity translucent type.</li>
 *   <li>Beyond {@link #LOD_DISTANCE} blocks (plus the ship's own reach), the coarse cube-face mesh.</li>
 *   <li>Thruster flames on every nozzle while the main engine burns, and the live cockpit display in front of the
 *       pilot when the local player sits in the ship in first person.</li>
 * </ul>
 * An entity renderer, so Sodium's chunk meshing never touches it and Iris treats it as an entity.
 */
public class BuiltShipRenderer extends EntityRenderer<ShipStructure, BuiltShipRenderer.State> {
    public static final Identifier FLAME = Galactica.id("textures/entity/ship/thruster_flame.png");
    static final double LOD_DISTANCE = 96;
    private static final int FULL_BRIGHT = 0xF000F0;

    public static final class State extends StarshipRenderState {
        @Nullable BuiltShipMesh mesh;
        boolean lod;
        float pivot;
        final float[] screen = new float[5];
    }

    private record Cached(int version, BuiltShipMesh mesh) {}

    /** Meshes by ship, rebuilt when its block set changes; ships that leave are collected with their entity. */
    private final Map<ShipStructure, Cached> meshes = new WeakHashMap<>();

    public BuiltShipRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0F;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ShipStructure ship, State state, float partialTicks) {
        super.extractRenderState(ship, state, partialTicks);
        ship.getAttitude(partialTicks, state.attitude);
        state.hurtTime = Math.max(ship.getHurtTime() - partialTicks, 0F);
        state.hurtDir = ship.getHurtDir();
        state.thrust = Math.abs(ship.throttle());
        state.spool = ship.isSpooling() ? 1F - (ship.spoolTicks() - partialTicks) / DriveRules.SPOOL_TICKS : 0F;
        state.cockpitView = StarshipRenderer.cockpitView(ship);
        state.pivot = ship.pivot();
        ShipEnvironment.sample(ship, state);
        if (state.starLit) state.lightCoords = ShipEnvironment.lightCoords(state.direct);
        state.mesh = mesh(ship);
        double d = Math.sqrt(state.distanceToCameraSq);
        state.lod = d > LOD_DISTANCE + ship.spec().hullReach() && !state.cockpitView;
        Vector3fc seat = ship.seat(0);
        // The pilot's screen: 0.85 block ahead of the helm seat, at chest-to-eye height (the Kestrel panel's place
        // relative to its pilot), 0.78 × 0.22 block.
        state.screen[0] = seat.x() - 0.39F;
        state.screen[1] = seat.x() + 0.39F;
        state.screen[2] = seat.y() + 0.68F;
        state.screen[3] = seat.y() + 0.90F;
        state.screen[4] = seat.z() + 0.85F;
    }

    private @Nullable BuiltShipMesh mesh(ShipStructure ship) {
        if (ship.blocks().isEmpty()) return null;
        Cached c = meshes.get(ship);
        if (c == null || c.version() != ship.blocksVersion()) {
            long t0 = System.nanoTime();
            BuiltShipMesh m = BuiltShipMesh.bake(ship.blocks(), ship.centreOfMass());
            Galactica.LOG.info("Baked ship {} mesh: {} blocks, {} quads in {} ms", ship.getId(), ship.blocks().size(), m.quads,
                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e6));
            c = new Cached(ship.blocksVersion(), m);
            meshes.put(ship, c);
        }
        return c.mesh();
    }

    @Override
    protected AABB getBoundingBoxForCulling(ShipStructure ship, float partialTicks) {
        double reach = ship.spec().hullReach() + 6;
        double x = ship.getX(), y = ship.getY() + ship.pivot(), z = ship.getZ();
        return new AABB(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        BuiltShipMesh mesh = state.mesh;
        if (mesh != null) {
            pose.pushPose();
            pose.translate(0F, state.pivot, 0F);
            pose.rotate(state.attitude);
            if (state.hurtTime > 0F) pose.rotateDegrees(Axis.ZP, Mth.sin(state.hurtTime) * state.hurtTime * 0.25F * state.hurtDir);
            boolean lit = state.starLit && state.outlineColor == 0 && !IrisCompat.shaderPackActive() && ShipRenderTypes.blockHullUsable();
            if (state.lod) {
                opaque(state, pose, out, mesh.lod, lit, 0F, state.lightCoords);
            } else {
                opaque(state, pose, out, mesh.solid, lit, 0F, state.lightCoords);
                opaque(state, pose, out, mesh.glow, lit, 1F, FULL_BRIGHT);
                if (mesh.translucent.length > 0) {
                    float[] t = mesh.translucent;
                    int light = state.lightCoords;
                    out.submitCustomGeometry(pose, RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS),
                            (p, buf) -> emit(t, p, buf, light));
                }
                if (state.thrust > 0.01F && mesh.nozzles.length > 0) flames(state, pose, out, mesh.nozzles);
                if (state.cockpitView) CockpitRenderer.submitDisplay(state, pose, out, state.screen);
            }
            pose.popPose();
        }
        super.submit(state, pose, out, camera);
    }

    private static void opaque(State state, PoseStack pose, SubmitNodeCollector out, float[] mesh, boolean lit, float floor, int light) {
        if (mesh.length == 0) return;
        if (lit) {
            Vector3f toStar = new Vector3f(state.toStar);
            float direct = state.direct;
            float[] tint = state.tint.clone();
            out.submitCustomGeometry(pose, ShipRenderTypes.BLOCK_HULL_LIT_TYPE,
                    (p, buf) -> emit(mesh, p, new ShadedVertexConsumer(buf, toStar, direct, tint, floor), FULL_BRIGHT));
        } else {
            RenderType type = RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS);
            out.submitCustomGeometry(pose, type, (p, buf) -> emit(mesh, p, buf, light));
        }
    }

    /** Walks a baked list into {@code out} under {@code pose} (normals turned once per face, as BakedBones does). */
    private static void emit(float[] b, PoseStack.Pose pose, VertexConsumer out, int light) {
        Matrix4f m = pose.pose();
        Vector3f v = new Vector3f();
        float rx = Float.NaN, ry = 0, rz = 0, nx = 0, ny = 0, nz = 0;
        int stride = BuiltShipMesh.STRIDE;
        for (int i = 0; i < b.length; i += stride) {
            if (b[i + 5] != rx || b[i + 6] != ry || b[i + 7] != rz) {
                rx = b[i + 5];
                ry = b[i + 6];
                rz = b[i + 7];
                pose.transformNormal(rx, ry, rz, v);
                nx = v.x;
                ny = v.y;
                nz = v.z;
            }
            m.transformPosition(b[i], b[i + 1], b[i + 2], v);
            out.addVertex(v.x, v.y, v.z, Float.floatToRawIntBits(b[i + 8]), b[i + 3], b[i + 4], OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
        }
    }

    /**
     * A flame out of every nozzle while the main engine burns: two crossed additive quads along the nozzle axis,
     * longer with throttle and in thinner air.
     */
    private static void flames(State state, PoseStack pose, SubmitNodeCollector out, float[] nozzles) {
        float thrust = state.thrust;
        float length = (1.2F + 2.8F * thrust) * (state.starLit ? 1.6F : 1F);
        float flicker = 0.9F + 0.1F * Mth.sin(state.ageInTicks * 2.3F);
        out.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(FLAME), (p, buf) -> {
            for (int i = 0; i < nozzles.length; i += 6) {
                float cx = nozzles[i], cy = nozzles[i + 1], cz = nozzles[i + 2];
                float dx = nozzles[i + 3], dy = nozzles[i + 4], dz = nozzles[i + 5];
                // Two axes across the nozzle.
                float ax = dy != 0 ? 1 : 0, ay = 0, az = dy != 0 ? 0 : 1;
                if (dz != 0) { ax = 1; az = 0; }
                float bx = dy * az - dz * ay, by = dz * ax - dx * az, bz = dx * ay - dy * ax;
                float w = 0.42F, l = length * flicker;
                int colour = 0xFFFFFFFF;
                for (int k = 0; k < 2; k++) {
                    float ux = (k == 0 ? ax : bx) * w, uy = (k == 0 ? ay : by) * w, uz = (k == 0 ? az : bz) * w;
                    flameQuad(p, buf, cx - ux, cy - uy, cz - uz, cx + ux, cy + uy, cz + uz, dx * l, dy * l, dz * l, colour);
                }
            }
        });
    }

    private static void flameQuad(PoseStack.Pose p, VertexConsumer buf, float ax, float ay, float az, float bx, float by, float bz,
                                  float lx, float ly, float lz, int colour) {
        // Both windings, so the flame shows from either side (the emissive type culls back faces).
        float[][] v = {{ax, ay, az, 0, 0}, {bx, by, bz, 1, 0}, {bx + lx, by + ly, bz + lz, 1, 1}, {ax + lx, ay + ly, az + lz, 0, 1}};
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 4; i++) {
                float[] q = v[pass == 0 ? i : 3 - i];
                buf.addVertex(p, q[0], q[1], q[2]).setColor(colour).setUv(q[3], q[4]).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(FULL_BRIGHT).setNormal(p, 0, 1, 0);
            }
        }
    }
}
