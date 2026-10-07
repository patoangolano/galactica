package dev.galactica.config;

/**
 * How each {@link GalacticaConfig} setting turns into a renderer number. Pure Java so the mapping is
 * unit-tested without a game; the client code calls these with {@code GalacticaConfigs.get()} at the
 * point of use, so every change applies on the next frame (the sky panorama on its next bake).
 */
public final class ConfigEffects {
    private ConfigEffects() {}

    /** Smallest planet sprite edge: below this a disc of a few pixels is already fully resolved. */
    public static final int SPRITE_MIN = 64;

    // ---------------------------------------------------------------- sky panorama

    /** Height of the 2:1 equirectangular panorama for a bake width. */
    public static int skyHeight(int width) {
        return width / 2;
    }

    /** Width of a 2:1 equirectangular image holding {@code pixelCount} pixels (w · w/2 = n). */
    public static int equirectWidth(int pixelCount) {
        int w = (int) Math.round(Math.sqrt(2.0 * pixelCount));
        if (w * (w / 2) != pixelCount) throw new IllegalArgumentException(pixelCount + " pixels is not a 2:1 panorama");
        return w;
    }

    // ---------------------------------------------------------------- planet sprites

    /**
     * Texture edge for a body drawn {@code pixels} wide on screen: 1.2× oversampled, rounded up to a
     * power of two, between {@link #SPRITE_MIN} and {@code max} ({@link GalacticaConfig#planetTextureMax}).
     * With the default max of 512 this is the size the sprite caches always used.
     */
    public static int spriteSize(double pixels, int max) {
        int cap = Math.max(SPRITE_MIN, max);
        double want = Double.isFinite(pixels) ? pixels * 1.2 : cap;
        int v = (int) Math.max(SPRITE_MIN, Math.min(cap, want));
        int size = Integer.highestOneBit(v * 2 - 1); // round up to a power of two
        return Math.max(SPRITE_MIN, Math.min(Integer.highestOneBit(cap), size));
    }

    // ---------------------------------------------------------------- HUD

    /**
     * GUI extent in HUD units when the HUD is drawn scaled by {@code scale}: anchoring to this width
     * (or height) keeps a right- or bottom-aligned panel on screen after the pose is scaled.
     */
    public static int scaledExtent(int guiExtent, double scale) {
        if (!(scale > 0)) return guiExtent;
        return (int) Math.floor(guiExtent / scale);
    }

    /**
     * HUD scale for a panel {@code panelExtent} units wide (at scale 1) that must stay within
     * {@code available} units: the {@code hudScale} setting, lowered until the panel fits, but never
     * below 1 (or the setting, when that is smaller) so a large panel on a small screen is not shrunk
     * below its normal size by the clamp.
     */
    public static double fitScale(double setting, double panelExtent, double available) {
        if (!(setting > 0)) return 1.0;
        if (!(panelExtent > 0) || panelExtent * setting <= available) return setting;
        return Math.max(Math.min(setting, 1.0), available / panelExtent);
    }

    // ---------------------------------------------------------------- effects

    /** Visible entry plasma after the user's {@code entryEffects} factor (0 switches it off). */
    public static double entryGlow(double physicalGlow, double entryEffects) {
        double f = Math.max(0.0, Math.min(1.0, entryEffects));
        return Math.max(0.0, physicalGlow) * f;
    }

    /**
     * Whether one plasma ember survives the {@code entryEffects} factor, for a uniform {@code roll} in
     * [0, 1): the ember count scales by the factor and 0 drops every ember.
     */
    public static boolean keepEmber(double entryEffects, double roll) {
        double f = Math.max(0.0, Math.min(1.0, entryEffects));
        return roll < f;
    }

    /** Segments along each exhaust plume strip; 0 when exhaust is off. MEDIUM is the shipped 12. */
    public static int exhaustSegments(GalacticaConfig.ExhaustQuality q) {
        return switch (q) {
            case OFF -> 0;
            case LOW -> 6;
            case MEDIUM -> 12;
            case HIGH -> 20;
        };
    }

    /**
     * Value sent to the star shader in {@code StarGeom.w}: 0 low (no supergranulation, faculae or
     * spot filaments, at most two prominences), 1 medium (the shipped shader), 2 high (granulation
     * kept down to smaller on-screen cells).
     */
    public static float starQualityLevel(GalacticaConfig.Quality q) {
        return switch (q) {
            case LOW -> 0f;
            case MEDIUM -> 1f;
            case HIGH -> 2f;
        };
    }
}
