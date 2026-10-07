package dev.galactica.ship;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The Kestrel's size, in one place. The part table in {@code assets-src/ship/gen_starship.py} is laid out in design
 * units (1/16 block before scaling) and exported at {@link #SCALE}; every Java constant that depends on where a part
 * is (seats, pivot, nozzles, RCS jets, culling, camera) is written in design units times {@link #PX}.
 *
 * <p><b>Why 2.5.</b> At 1:1 the hull was 5.4 blocks long with a canopy 0.45 block wide, so a 1.8-block pilot sat on
 * top of it like a toy. Light single-engine fighters run 12–15 m (F-16 15.0 m long, 9.8 m span; Gripen 14.1 m,
 * 8.4 m; the twin-pod Kestrel is wider). At 2.5 the Kestrel is 13.6 m long with a 12.2 m span, the canopy is 1.1 m
 * wide inside and 0.66 m tall over the sill: the seated pilot's helmet (0.47 block) clears the glass by 0.13 block
 * and the torso sits in the fuselage well. Larger would only make the hitbox and landing footprint worse.
 */
public final class ShipDimensions {
    private ShipDimensions() {}

    /** Design units to model pixels (must equal the generator's SCALE and {@code StarshipGeometry.SCALE}). */
    public static final float SCALE = 2.5F;
    /** Blocks per design unit. */
    public static final float PX = SCALE / 16F;
    /** Hull centre (the model and attitude pivot) above the entity origin: the gear feet sit 12 design units below it. */
    public static final float PIVOT = 12F * PX;
    /**
     * Collision box. Vanilla boxes are square in XZ and do not turn with the hull, so the box covers the fuselage
     * and the engine pods (±3.1 blocks), not the wings (span 12.2) or the full 13.6-block length; it reaches from
     * the gear feet to the canopy top (1.28 above the pivot).
     */
    public static final float BOX_WIDTH = 6.0F, BOX_HEIGHT = 3.2F;
    /** Farthest point of the hull from the hull centre at any attitude (nose probe 45 units, nozzle lips 42 at 14 off-axis). */
    public static final float HULL_REACH = 46F * PX;
    /** Seated hips (the passenger attachment point), body frame, blocks from the hull centre: tandem, pilot forward. */
    public static final float SEAT_Y = -0.77F * PX;
    public static final Vector3fc PILOT_SEAT = new Vector3f(0F, SEAT_Y, 11.0F * PX);
    public static final Vector3fc PASSENGER_SEAT = new Vector3f(0F, SEAT_Y, 4.6F * PX);
}
