package dev.galactica.client.ship;

import org.joml.Quaternionf;

/**
 * Framing of the detached (third-person) pilot camera, applied by {@code CameraShipMixin}.
 *
 * <p>The chase view sits {@link #CHASE_DISTANCE} blocks from the hull centre, looking down at it by
 * {@link #CHASE_TILT_DEG}, and is raised {@link #CHASE_LIFT} along its own up axis so the ship sits a little below
 * the middle of the screen: the 12.2-block wingspan then spans about 30 % of a 16:9 frame (70° FOV). An optional orbit
 * (development screenshots) turns the camera about the ship's up axis and tilts it, still aimed at the hull centre.
 */
public final class ShipCamera {
    private ShipCamera() {}

    public static final float CHASE_DISTANCE = 16.0F;
    public static final float CHASE_TILT_DEG = 11.0F;
    public static final float CHASE_LIFT = 1.1F;

    private static boolean orbiting;
    private static float orbitYawDeg, orbitPitchDeg, orbitDistance = CHASE_DISTANCE, orbitTargetZ;

    /**
     * Orbits the third-person camera: {@code yawDeg} about the ship's up axis from the chase position (180 looks at
     * the nose from ahead), {@code pitchDeg} below the ship's horizon, {@code distance} blocks from the hull centre.
     */
    public static void orbit(float yawDeg, float pitchDeg, float distance) {
        orbit(yawDeg, pitchDeg, distance, 0F);
    }

    /** As {@link #orbit(float, float, float)}, aimed at a point {@code targetZ} blocks along the nose from the hull centre. */
    public static void orbit(float yawDeg, float pitchDeg, float distance, float targetZ) {
        orbitTargetZ = targetZ;
        orbiting = true;
        orbitYawDeg = yawDeg;
        orbitPitchDeg = pitchDeg;
        orbitDistance = distance;
    }

    /** Back to the plain chase view. */
    public static void clearOrbit() {
        orbiting = false;
    }

    /** Turns the camera rotation (already the ship's attitude, facing the chase direction) for the detached view. */
    public static void aim(Quaternionf rotation) {
        float yaw = orbiting ? orbitYawDeg : 0F;
        float down = orbiting ? orbitPitchDeg : CHASE_TILT_DEG;
        rotation.rotateY((float) Math.toRadians(yaw)).rotateX((float) Math.toRadians(-down));
    }

    /** The aim point in the body frame (blocks from the hull centre): the centre itself unless an orbit moved it. */
    public static org.joml.Vector3f target(org.joml.Vector3f dest) {
        return dest.set(0F, 0F, orbiting ? orbitTargetZ : 0F);
    }

    /** Distance from the hull centre along the view axis, blocks. */
    public static float distance() {
        return orbiting ? orbitDistance : CHASE_DISTANCE;
    }

    /** Offset along the camera's own up axis, blocks (none while orbiting: the hull centre is the frame centre). */
    public static float lift() {
        return orbiting ? 0F : CHASE_LIFT;
    }
}
