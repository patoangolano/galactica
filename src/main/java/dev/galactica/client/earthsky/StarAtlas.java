package dev.galactica.client.earthsky;

import dev.galactica.science.Blackbody;

/**
 * A texture atlas of star images in which every cell already carries a star's colour and
 * brightness, so a star sprite needs only position and UV. Shader packs map vanilla's
 * {@code END_SKY} pipeline to Iris' {@code SKY_TEXTURED} key, whose vertex format is
 * {@code POSITION_TEX}; a pack may therefore ignore vertex colour. With colour and brightness in the
 * texture the stars keep their magnitudes and colours either way. Pure Java.
 *
 * <ul>
 *   <li>Columns: {@link #COLOURS} blackbody colours, temperatures log-spaced from
 *       {@link #T_MIN} to {@link #T_MAX} (M giants to early B stars). A sprite takes the column whose
 *       chromaticity is nearest its own.</li>
 *   <li>Rows: {@link #LEVELS} opacity levels from {@link #MIN_ALPHA} to 1, log-spaced, which is
 *       uniform in magnitude: 0.156 mag per row between V = 4.0 (full opacity) and the naked-eye
 *       limit V = 6.5, below the eye's ~0.2 mag brightness discrimination.</li>
 * </ul>
 * Each cell is an Airy-like core (Gaussian plus a faint 1/r² wing, as {@code SkyTextures.psf})
 * that reaches exactly zero at the cell border, and UVs are inset half a texel, so linear filtering
 * never bleeds one cell into the next.
 */
public final class StarAtlas {
    private StarAtlas() {}

    public static final int COLOURS = 12, LEVELS = 16, CELL = 32;
    public static final int WIDTH = COLOURS * CELL, HEIGHT = LEVELS * CELL;
    public static final double T_MIN = 2_400, T_MAX = 30_000;
    /** Opacity of a star at the naked-eye limit (see {@link #spriteAlpha}). */
    public static final double MIN_ALPHA = 0.1;

    /** Half-size of a faint star sprite, radians: ~2 px at 70° FOV on a 1080-line screen, as vanilla's 0.15–0.25 / 100. */
    public static final double STAR_SPRITE = 0.0022;
    public static final double MAX_SWELL = 5.0;

    private static final float[][] PALETTE = new float[COLOURS][];

    static {
        for (int c = 0; c < COLOURS; c++) {
            float[] rgb = new float[3];
            Blackbody.linearRgb(temperature(c), rgb);
            PALETTE[c] = normalise(rgb[0], rgb[1], rgb[2]);
        }
    }

    /** Temperature of column {@code c}, kelvin. */
    public static double temperature(int c) {
        return T_MIN * Math.pow(T_MAX / T_MIN, c / (double) (COLOURS - 1));
    }

    /**
     * Opacity of a star of V magnitude {@code mag}: faint stars dim at constant size (Pogson:
     * flux ∝ 10^(−0.4 m)), {@link #MIN_ALPHA} at the naked-eye limit, full opacity from V ≈ 4.
     */
    public static double spriteAlpha(double mag) {
        return Math.min(1.0, MIN_ALPHA * Math.pow(10, -0.4 * (mag - EarthSkyBake.LIMITING_MAG)));
    }

    /** Brighter than V ≈ 3.5 a star also swells, like the eye's/camera's saturated point-spread function. */
    public static double spriteSize(double mag) {
        return STAR_SPRITE * Math.min(MAX_SWELL, Math.max(1.0, Math.pow(10, -0.4 * (mag - 3.5) * 0.4)));
    }

    /** Column whose blackbody chromaticity is nearest the given linear colour. */
    public static int column(float r, float g, float b) {
        float[] n = normalise(r, g, b);
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int c = 0; c < COLOURS; c++) {
            float[] p = PALETTE[c];
            double d = sq(p[0] - n[0]) + sq(p[1] - n[1]) + sq(p[2] - n[2]);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Row for V magnitude {@code mag}: row 0 is full opacity, row {@code LEVELS − 1} is {@link #MIN_ALPHA}. */
    public static int row(double mag) {
        double a = Math.max(MIN_ALPHA, spriteAlpha(mag));
        double t = Math.log(a) / Math.log(MIN_ALPHA); // 0 at a = 1, 1 at MIN_ALPHA
        return (int) Math.max(0, Math.min(LEVELS - 1, Math.round(t * (LEVELS - 1))));
    }

    /** Opacity baked into row {@code row}. */
    public static double rowAlpha(int row) {
        return Math.pow(MIN_ALPHA, row / (double) (LEVELS - 1));
    }

    /** {u0, v0, u1, v1} of a cell, inset half a texel so linear filtering stays inside it. */
    public static float[] uv(int column, int row) {
        float hu = 0.5f / WIDTH, hv = 0.5f / HEIGHT;
        return new float[] {
                (float) (column * CELL) / WIDTH + hu, (float) (row * CELL) / HEIGHT + hv,
                (float) ((column + 1) * CELL) / WIDTH - hu, (float) ((row + 1) * CELL) / HEIGHT - hv};
    }

    /** Star image profile on a cell, (dx, dy) ∈ [−1, 1]²: zero on and beyond the inscribed circle. */
    static double profile(double dx, double dy) {
        double r2 = dx * dx + dy * dy;
        double core = Math.exp(-r2 / (2 * 0.12 * 0.12));
        double wing = 0.04 / (1 + r2 * 60);
        return Math.min(1, (core + wing) * Math.max(0, 1 - r2));
    }

    /** The whole atlas, row-major 0xAARRGGBB, {@link #WIDTH} × {@link #HEIGHT}; colour is sRGB-encoded. */
    public static int[] pixels() {
        int[] out = new int[WIDTH * HEIGHT];
        for (int c = 0; c < COLOURS; c++) {
            float[] p = PALETTE[c];
            int rgb = (enc(p[0]) << 16) | (enc(p[1]) << 8) | enc(p[2]);
            for (int row = 0; row < LEVELS; row++) {
                double level = rowAlpha(row);
                for (int y = 0; y < CELL; y++) {
                    for (int x = 0; x < CELL; x++) {
                        // Pixel centres span (−1, 1) and the outermost ring of texels is forced to 0.
                        double dx = (x + 0.5) / CELL * 2 - 1, dy = (y + 0.5) / CELL * 2 - 1;
                        boolean border = x == 0 || y == 0 || x == CELL - 1 || y == CELL - 1;
                        int a = border ? 0 : (int) Math.round(255 * level * profile(dx, dy));
                        out[(row * CELL + y) * WIDTH + c * CELL + x] = (a << 24) | rgb;
                    }
                }
            }
        }
        return out;
    }

    private static int enc(float linear) {
        return (int) Math.round(255 * Blackbody.encode(linear));
    }

    private static float[] normalise(float r, float g, float b) {
        float m = Math.max(1e-6f, Math.max(r, Math.max(g, b)));
        return new float[] {r / m, g / m, b / m};
    }

    private static double sq(double x) {
        return x * x;
    }
}
