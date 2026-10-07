package dev.galactica.client.ship;

import org.joml.Quaternionf;

/**
 * Extra render-state fields for an entity riding a Starship, mixed into {@code EntityRenderState}
 * ({@code EntityRenderStateShipMixin}): the hull's attitude, which {@code LivingRendererShipMixin} turns the rider
 * by, and the pivot it turns about (the seat, i.e. the rider's vehicle attachment point above its feet). Without it
 * a seated pilot stays world-upright while the hull pitches and rolls, and pokes out through the canopy.
 */
public interface ShipRiderState {
    /** The ship's interpolated attitude, valid while {@link #galactica$ridesShip()}. */
    Quaternionf galactica$shipAttitude();

    boolean galactica$ridesShip();

    void galactica$setRidesShip(boolean rides);

    /** Seat height above the rider's feet, blocks (unscaled). */
    float galactica$seatHeight();

    void galactica$setSeatHeight(float height);

    /**
     * Where the rider looks relative to the hull, degrees: {yaw, pitch} as vanilla's net head yaw and head pitch
     * (as {@code yHeadRot - yBodyRot} with the body facing the nose; pitch positive looking down). Zero for the pilot,
     * whose look is the nose.
     */
    float[] galactica$headTurn();
}
