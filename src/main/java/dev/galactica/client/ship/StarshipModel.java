package dev.galactica.client.ship;

import dev.galactica.Galactica;
import dev.galactica.ship.ShipAnimator;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;

import java.util.function.Consumer;

/**
 * The Starship's cuboid model (v4): a 13.6-block fighter ({@link dev.galactica.ship.ShipDimensions}) with twin rear thruster pods, swept wings with canted
 * tip fins, a tandem glass cockpit and tricycle gear. Geometry and the 1024² material-atlas texture come from one part
 * table in {@code assets-src/ship/gen_starship.py}; the boxes are its output ({@link StarshipGeometry}). Units are
 * 1/16 block, Y down (the renderer applies {@code scale(-1,-1,1)}), +Z is the nose.
 *
 * <p>Six layers share the texture and the bone tree: the opaque hull, the translucent canopy glass, the always-on
 * lights, the engine cores, and the cockpit interior with its emissive keys.
 * {@link #setupAnim} poses the moving bones (gear, canopy, nozzle gimbal) from the render state, in every layer
 * alike, using {@link ShipAnimator}'s angles converted to ModelPart space (rotations about X and Y change sign
 * under the renderer's 180° turn about Z).
 */
public class StarshipModel extends EntityModel<StarshipRenderState> {
    public static final ModelLayerLocation HULL_LAYER = new ModelLayerLocation(Galactica.id("ship"), "main");
    public static final ModelLayerLocation LIGHTS_LAYER = new ModelLayerLocation(Galactica.id("ship"), "lights");
    public static final ModelLayerLocation CORES_LAYER = new ModelLayerLocation(Galactica.id("ship"), "cores");
    public static final ModelLayerLocation GLASS_LAYER = new ModelLayerLocation(Galactica.id("ship"), "glass");
    public static final ModelLayerLocation COCKPIT_LAYER = new ModelLayerLocation(Galactica.id("ship"), "cockpit");
    public static final ModelLayerLocation COCKPIT_LIGHTS_LAYER = new ModelLayerLocation(Galactica.id("ship"), "cockpit_lights");
    private static final int TEX = StarshipGeometry.TEXTURE_SIZE;
    private static final float DEG = (float) (Math.PI / 180.0);

    private final ModelPart canopy, gearFront, gearLeft, gearRight, nozzleLeft, nozzleRight;

    public StarshipModel(ModelPart root) {
        super(root);
        this.canopy = root.getChild("canopy");
        this.gearFront = root.getChild("gear_front");
        this.gearLeft = root.getChild("gear_left");
        this.gearRight = root.getChild("gear_right");
        this.nozzleLeft = root.getChild("nozzle_left");
        this.nozzleRight = root.getChild("nozzle_right");
    }

    private static LayerDefinition layer(Consumer<net.minecraft.client.model.geom.builders.PartDefinition> parts) {
        MeshDefinition mesh = new MeshDefinition();
        parts.accept(mesh.getRoot());
        return LayerDefinition.create(mesh, TEX, TEX);
    }

    public static LayerDefinition createHullLayer() {
        return layer(StarshipGeometry::hull);
    }

    public static LayerDefinition createLightsLayer() {
        return layer(StarshipGeometry::lights);
    }

    public static LayerDefinition createCoresLayer() {
        return layer(StarshipGeometry::cores);
    }

    public static LayerDefinition createGlassLayer() {
        return layer(StarshipGeometry::glass);
    }

    public static LayerDefinition createCockpitLayer() {
        return layer(StarshipGeometry::cockpit);
    }

    public static LayerDefinition createCockpitLightsLayer() {
        return layer(StarshipGeometry::cockpitLights);
    }

    @Override
    public void setupAnim(StarshipRenderState state) {
        super.setupAnim(state);
        float stow = ShipAnimator.ease(1F - state.gear);
        float open = ShipAnimator.ease(state.canopy);
        // Design-space rotation θ about X or Y becomes −θ in ModelPart space.
        gearFront.xRot = -ShipAnimator.GEAR_FRONT_FOLD_DEG * stow * DEG;
        gearLeft.xRot = gearRight.xRot = -ShipAnimator.GEAR_MAIN_FOLD_DEG * stow * DEG;
        // Stowed legs sit inside the pods and nose: hide them once fully folded so nothing pokes through the skin.
        gearFront.visible = gearLeft.visible = gearRight.visible = stow < 0.98F;
        canopy.xRot = -ShipAnimator.CANOPY_OPEN_DEG * open * DEG;
        // From the cockpit the exterior hoops and glass would cut across the view: the interior draws its own.
        canopy.visible = !state.cockpitView;
        nozzleLeft.xRot = nozzleRight.xRot = -state.gimbalPitch * DEG;
        nozzleLeft.yRot = nozzleRight.yRot = -state.gimbalYaw * DEG;
    }
}
