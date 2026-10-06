package dev.galactica.client.ship;

import dev.galactica.Galactica;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * The Starship's cuboid model: a 3 × 1.5 × 5 block single-seater (fuselage, canopy, wings with tip pods,
 * twin engines, landing struts). Geometry and the 128² box-UV texture come from one table in
 * {@code assets-src/ship/gen_starship.py}, which also writes the Blockbench project; the boxes below are its
 * output. Units are 1/16 block, Y down (the renderer applies {@code scale(-1,-1,1)}), +Z is the nose.
 *
 * <p>Two layers share the texture: the opaque hull, and the exhaust (nozzles and plumes) drawn emissive and
 * tinted by thrust, with the plumes stretched aft by {@code zScale}.
 */
public class StarshipModel extends EntityModel<StarshipRenderState> {
    public static final ModelLayerLocation HULL_LAYER = new ModelLayerLocation(Galactica.id("ship"), "main");
    public static final ModelLayerLocation GLOW_LAYER = new ModelLayerLocation(Galactica.id("ship"), "glow");
    private static final int TEX = 128;

    public StarshipModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createHullLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition r = mesh.getRoot();
        box(r, "fuselage_front", 0, 0, -6, -5, 0, 12, 10, 28);
        box(r, "fuselage_rear", 0, 0, -6, -5, -28, 12, 10, 28);
        box(r, "nose", 0, 102, -4, -3, 28, 8, 7, 8);
        box(r, "nose_tip", 32, 86, -2, -1.5F, 36, 4, 4, 4);
        box(r, "canopy", 48, 82, -4, -9, 4, 8, 4, 16);
        box(r, "spine", 0, 65, -3, -8, -14, 6, 3, 18);
        box(r, "fin", 96, 82, -1, -16, -26, 2, 8, 12);
        box(r, "belly", 54, 38, -4, 5, -16, 8, 2, 20);
        box(r, "wing_left", 54, 60, -22, 1, -20, 16, 2, 20);
        box(r, "wing_right", 54, 60, 6, 1, -20, 16, 2, 20);
        box(r, "wingtip_left", 80, 0, -24, 0, -21, 2, 4, 20);
        box(r, "wingtip_right", 80, 0, 22, 0, -21, 2, 4, 20);
        box(r, "engine_left", 0, 38, -14, -2, -34, 7, 7, 20);
        box(r, "engine_right", 0, 38, 7, -2, -34, 7, 7, 20);
        box(r, "strut_front", 32, 94, -1, 5, 20, 2, 6, 2);
        box(r, "strut_left", 32, 94, -11, 5, -24, 2, 6, 2);
        box(r, "strut_right", 32, 94, 9, 5, -24, 2, 6, 2);
        box(r, "foot_front", 44, 102, -2, 11, 19, 4, 1, 4);
        box(r, "foot_left", 44, 102, -12, 11, -25, 4, 1, 4);
        box(r, "foot_right", 44, 102, 8, 11, -25, 4, 1, 4);
        return LayerDefinition.create(mesh, TEX, TEX);
    }

    public static LayerDefinition createGlowLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition r = mesh.getRoot();
        box(r, "nozzle_left", 32, 102, -13, -1, -35, 5, 5, 1);
        box(r, "nozzle_right", 32, 102, 8, -1, -35, 5, 5, 1);
        // Plumes pivot on the nozzle plane so zScale lengthens them aft only.
        r.addOrReplaceChild("plume_left", CubeListBuilder.create().texOffs(0, 86).addBox(-2, -2, -12, 4, 4, 12),
                PartPose.offset(-10.5F, 1.5F, -35F));
        r.addOrReplaceChild("plume_right", CubeListBuilder.create().texOffs(0, 86).addBox(-2, -2, -12, 4, 4, 12),
                PartPose.offset(10.5F, 1.5F, -35F));
        return LayerDefinition.create(mesh, TEX, TEX);
    }

    private static void box(PartDefinition root, String name, int u, int v, float x, float y, float z, float w, float h, float d) {
        root.addOrReplaceChild(name, CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d), PartPose.ZERO);
    }

    /** Exhaust layer: plume length follows thrust, with a little flicker so a held burn looks alive. */
    public static class Glow extends EntityModel<StarshipRenderState> {
        private final ModelPart plumeLeft, plumeRight;

        public Glow(ModelPart root) {
            super(root);
            this.plumeLeft = root.getChild("plume_left");
            this.plumeRight = root.getChild("plume_right");
        }

        @Override
        public void setupAnim(StarshipRenderState state) {
            super.setupAnim(state);
            float flicker = 1F + 0.08F * (float) Math.sin(state.ageInTicks * 2.3F);
            float len = (0.08F + 1.5F * state.thrust) * flicker;
            float girth = 0.7F + 0.3F * state.thrust;
            for (ModelPart p : new ModelPart[] {plumeLeft, plumeRight}) {
                p.visible = state.thrust > 0.01F || state.spool > 0;
                p.zScale = state.spool > 0 && state.thrust < 0.01F ? 0.1F + 0.3F * state.spool : len;
                p.xScale = p.yScale = girth;
            }
        }
    }
}
