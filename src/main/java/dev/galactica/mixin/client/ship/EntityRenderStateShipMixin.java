package dev.galactica.mixin.client.ship;

import dev.galactica.client.ship.ShipRiderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Storage for {@link ShipRiderState} on every entity render state. */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateShipMixin implements ShipRiderState {
    @Unique private final Quaternionf galactica$attitude = new Quaternionf();
    @Unique private boolean galactica$rides;
    @Unique private float galactica$seat;
    @Unique private final float[] galactica$head = new float[2];

    @Override
    public float[] galactica$headTurn() {
        return galactica$head;
    }

    @Override
    public Quaternionf galactica$shipAttitude() {
        return galactica$attitude;
    }

    @Override
    public boolean galactica$ridesShip() {
        return galactica$rides;
    }

    @Override
    public void galactica$setRidesShip(boolean rides) {
        galactica$rides = rides;
    }

    @Override
    public float galactica$seatHeight() {
        return galactica$seat;
    }

    @Override
    public void galactica$setSeatHeight(float height) {
        galactica$seat = height;
    }
}
