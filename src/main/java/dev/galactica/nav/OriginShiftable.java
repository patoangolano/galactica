package dev.galactica.nav;

/**
 * Implemented on {@code ServerGamePacketListenerImpl} by a mixin: the hooks {@link FloatingOrigin} needs on a
 * player's connection.
 */
public interface OriginShiftable {
    /** Moves the anti-cheat reference positions (player and controlled vehicle) by the shift, in blocks. */
    void galactica$shiftReferences(double dx, double dy, double dz);

    /** True while a vanilla teleport waits for the client's acknowledgement. */
    boolean galactica$awaitingTeleport();
}
