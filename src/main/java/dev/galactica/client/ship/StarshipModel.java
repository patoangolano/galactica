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
    private static final int TEX = StarshipGeometry.TEXTURE_SIZE;

    public StarshipModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createHullLayer() {
        MeshDefinition mesh = new MeshDefinition();
        StarshipGeometry.hull(mesh.getRoot());
        return LayerDefinition.create(mesh, TEX, TEX);
    }

    public static LayerDefinition createGlowLayer() {
        MeshDefinition mesh = new MeshDefinition();
        StarshipGeometry.glow(mesh.getRoot());
        return LayerDefinition.create(mesh, TEX, TEX);
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
