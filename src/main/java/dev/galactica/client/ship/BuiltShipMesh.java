package dev.galactica.client.ship;

import dev.galactica.ship.build.ShipBlocks;
import dev.galactica.ship.build.ShipCell;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * A block-built ship's blocks baked once into flat vertex lists in the body frame (blocks from the pivot), the way
 * a chunk section is meshed: every block's model quads, with the faces two ship blocks share culled, tinted as in an
 * inventory (no biome), sorted into opaque, glowing (light-emitting blocks) and translucent lists. A second, coarse
 * list for distance (LOD) draws each block as at most six cube faces with its particle texture. Rebuilt only when the
 * blocks change. Render thread only (the model set lives there).
 */
final class BuiltShipMesh {
    /** Floats per vertex: x, y, z, u, v, nx, ny, nz, colour (ARGB bits). */
    static final int STRIDE = 9;

    final float[] solid, glow, translucent, lod;
    /** Thruster nozzles: body-frame exit centre and outward direction, 6 floats each. */
    final float[] nozzles;
    final int quads;

    private BuiltShipMesh(float[] solid, float[] glow, float[] translucent, float[] lod, float[] nozzles, int quads) {
        this.solid = solid;
        this.glow = glow;
        this.translucent = translucent;
        this.lod = lod;
        this.nozzles = nozzles;
        this.quads = quads;
    }

    static BuiltShipMesh bake(ShipBlocks blocks, Vector3dc com) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        BlockColors colors = mc.getBlockColors();
        Int2ObjectOpenHashMap<BlockState> grid = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < blocks.size(); i++) grid.put(blocks.cell(i), blocks.state(i));
        FloatArrayList solid = new FloatArrayList(), glow = new FloatArrayList(), translucent = new FloatArrayList(),
                lod = new FloatArrayList(), nozzles = new FloatArrayList();
        List<BlockStateModelPart> parts = new ArrayList<>();
        RandomSource random = RandomSource.create();
        Vector3f n = new Vector3f();
        int quads = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockState state = blocks.state(i);
            int c = blocks.cell(i);
            int x = ShipCell.unpackX(c), y = ShipCell.unpackY(c), z = ShipCell.unpackZ(c);
            float ox = (float) (x - com.x()), oy = (float) (y - com.y()), oz = (float) (z - com.z());
            BlockStateModel model = models.get(state);
            TextureAtlasSprite particle = model.particleMaterial().sprite();
            boolean emissive = state.getLightEmission() > 0;
            if (state.getBlock() instanceof dev.galactica.ship.build.ShipPartBlock
                    && dev.galactica.ship.build.ShipPartBlocks.partOf(state) == dev.galactica.ship.build.ShipPart.THRUSTER) {
                Direction f = dev.galactica.ship.build.ShipPartBlock.facingOf(state);
                if (f != null) {
                    nozzles.add(ox + 0.5F + 0.5F * f.getStepX()); nozzles.add(oy + 0.5F + 0.5F * f.getStepY()); nozzles.add(oz + 0.5F + 0.5F * f.getStepZ());
                    nozzles.add(f.getStepX()); nozzles.add(f.getStepY()); nozzles.add(f.getStepZ());
                }
            }
            // Coarse LOD: exposed cube faces with the particle texture, tinted like the block.
            int lodTint = tint(colors, state, 0);
            for (Direction d : Direction.values()) {
                if (occludes(grid, x, y, z, d)) continue;
                cubeFace(lod, d, ox, oy, oz, particle, lodTint);
            }
            if (state.getRenderShape() != RenderShape.MODEL) {
                // Block-entity-drawn blocks (chests, signs…): a plain cube of their particle texture stands in.
                for (Direction d : Direction.values()) {
                    if (occludes(grid, x, y, z, d)) continue;
                    cubeFace(solid, d, ox, oy, oz, particle, -1);
                    quads++;
                }
                continue;
            }
            parts.clear();
            random.setSeed(42L * c);
            model.collectParts(random, parts);
            for (BlockStateModelPart part : parts) {
                for (int k = 0; k <= 6; k++) {
                    Direction cull = k < 6 ? Direction.values()[k] : null;
                    if (cull != null && occludes(grid, x, y, z, cull)) continue;
                    for (BakedQuad q : part.getQuads(cull)) {
                        var info = q.materialInfo();
                        FloatArrayList out = info.layer() == ChunkSectionLayer.TRANSLUCENT ? translucent : emissive ? glow : solid;
                        int colour = info.isTinted() ? tint(colors, state, info.tintIndex()) : -1;
                        normal(q, n);
                        for (int v = 0; v < 4; v++) {
                            Vector3fc p = q.position(v);
                            long uv = q.packedUV(v);
                            out.add(ox + p.x()); out.add(oy + p.y()); out.add(oz + p.z());
                            out.add(UVPair.unpackU(uv)); out.add(UVPair.unpackV(uv));
                            out.add(n.x); out.add(n.y); out.add(n.z);
                            out.add(Float.intBitsToFloat(colour));
                        }
                        quads++;
                    }
                }
            }
        }
        return new BuiltShipMesh(solid.toFloatArray(), glow.toFloatArray(), translucent.toFloatArray(), lod.toFloatArray(),
                nozzles.toFloatArray(), quads);
    }

    /** True if the ship block beyond face {@code d} of cell (x, y, z) hides it (a full opaque block). */
    private static boolean occludes(Int2ObjectOpenHashMap<BlockState> grid, int x, int y, int z, Direction d) {
        BlockState other = grid.get(ShipCell.pack(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()));
        return other != null && other.isSolidRender();
    }

    private static int tint(BlockColors colors, BlockState state, int index) {
        if (index < 0) return -1;
        BlockTintSource source = colors.getTintSource(state, index);
        if (source == null) return -1;
        return 0xFF000000 | source.color(state);
    }

    private static void normal(BakedQuad q, Vector3f out) {
        Vector3fc a = q.position0(), b = q.position1(), c = q.position2();
        float ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
        float vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
        out.set(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
        if (out.lengthSquared() < 1e-12F) out.set(q.direction().getStepX(), q.direction().getStepY(), q.direction().getStepZ());
        else out.normalize();
    }

    /** One face of the unit cube at (ox, oy, oz), counter-clockwise seen from outside, the whole sprite on it. */
    private static void cubeFace(FloatArrayList out, Direction d, float ox, float oy, float oz, TextureAtlasSprite s, int colour) {
        float[][] v = switch (d) {
            case DOWN -> new float[][] {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
            case UP -> new float[][] {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
            case NORTH -> new float[][] {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}};
            case SOUTH -> new float[][] {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}};
            case WEST -> new float[][] {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}};
            case EAST -> new float[][] {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}};
        };
        float[][] uv = {{s.getU0(), s.getV0()}, {s.getU0(), s.getV1()}, {s.getU1(), s.getV1()}, {s.getU1(), s.getV0()}};
        for (int i = 0; i < 4; i++) {
            out.add(ox + v[i][0]); out.add(oy + v[i][1]); out.add(oz + v[i][2]);
            out.add(uv[i][0]); out.add(uv[i][1]);
            out.add(d.getStepX()); out.add(d.getStepY()); out.add(d.getStepZ());
            out.add(Float.intBitsToFloat(colour));
        }
    }
}
