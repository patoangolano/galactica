package dev.galactica.client.ship;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class StarshipRenderState extends EntityRenderState {
    public final Quaternionf attitude = new Quaternionf();
    public float hurtTime;
    public int hurtDir;
    /** |main-engine throttle|, 0 … 1. */
    public float thrust;
    /** Hyperdrive spool progress, 0 idle … 1 about to jump. */
    public float spool;
    /** Landing gear 0 stowed … 1 down, canopy 0 shut … 1 open (eased), nozzle gimbal in degrees. */
    public float gear = 1F, canopy, gimbalYaw, gimbalPitch;
    /** RCS command bits ({@link dev.galactica.ship.ShipAnimator}). */
    public int rcs;
    /**
     * True when the local camera sits in this ship's cockpit in first person: the exterior canopy (glass and frame
     * hoops) is hidden and the interior glass and live display are drawn instead.
     */
    public boolean cockpitView;
    /** Static pressure around the ship, bar (0 in space). */
    public float ambientBar;

    /** True in space: the hull is lit by the host star ({@link ShipEnvironment}) instead of the lightmap. */
    public boolean starLit;
    /** World-frame unit vector toward the host star (zero in interstellar space). */
    public final Vector3f toStar = new Vector3f();
    /** Auto-exposed direct-light strength, 0 … 1 ({@link dev.galactica.ship.ShipLighting#directStrength}). */
    public float direct;
    /** Light colour, max component 1. */
    public final float[] tint = {1F, 1F, 1F};
    /** Camera position relative to the hull centre, body frame, blocks: drives the exhaust billboards. */
    public final Vector3f cameraBody = new Vector3f();
    /** Camera right and up vectors, body frame. */
    public final Vector3f camRightBody = new Vector3f(1, 0, 0), camUpBody = new Vector3f(0, 1, 0);
}
