package dev.galactica.sky;

/** Cubemap face ↔ direction conversion (OpenGL convention, faces +X, −X, +Y, −Y, +Z, −Z). */
public final class CubeMath {
    private CubeMath() {}

    /** u, v in [−1, 1]; writes a unit direction into out. */
    public static void faceDirection(int face, double u, double v, double[] out) {
        double x, y, z;
        switch (face) {
            case 0 -> { x = 1; y = -v; z = -u; }
            case 1 -> { x = -1; y = -v; z = u; }
            case 2 -> { x = u; y = 1; z = v; }
            case 3 -> { x = u; y = -1; z = -v; }
            case 4 -> { x = u; y = -v; z = 1; }
            default -> { x = -u; y = -v; z = -1; }
        }
        double inv = 1.0 / Math.sqrt(x * x + y * y + z * z);
        out[0] = x * inv;
        out[1] = y * inv;
        out[2] = z * inv;
    }
}
