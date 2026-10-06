package dev.galactica.client.earthsky;

/** Winding helpers for sky-shell quads seen from the centre. Pure Java. */
public final class SkyMesh {
    private SkyMesh() {}

    /**
     * True when quad v0..v3 (each {x, y, z, ...}) is wound so its front face points away from the
     * origin, i.e. a back-face-culling pipeline would hide it from a camera at the centre.
     *
     * <p>The normal is the cross product of the diagonals, (v2 − v0) × (v3 − v1), not of two edges:
     * for the quads that touch a pole of a latitude/longitude sphere two corners coincide, an edge
     * has zero length and an edge-based normal vanishes, while both diagonals stay non-zero. For a
     * planar quad the diagonal cross product equals twice the area-weighted normal of the polygon.
     */
    public static boolean facesOutward(float[][] v) {
        float ax = v[2][0] - v[0][0], ay = v[2][1] - v[0][1], az = v[2][2] - v[0][2];
        float bx = v[3][0] - v[1][0], by = v[3][1] - v[1][1], bz = v[3][2] - v[1][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float cx = v[0][0] + v[1][0] + v[2][0] + v[3][0];
        float cy = v[0][1] + v[1][1] + v[2][1] + v[3][1];
        float cz = v[0][2] + v[1][2] + v[2][2] + v[3][2];
        return nx * cx + ny * cy + nz * cz > 0;
    }

    /** Corner order that makes the quad face the origin: identity, or reversed when it faces out. */
    public static int[] inwardOrder(float[][] v) {
        return facesOutward(v) ? new int[] {3, 2, 1, 0} : new int[] {0, 1, 2, 3};
    }
}
