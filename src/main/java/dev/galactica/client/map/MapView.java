package dev.galactica.client.map;

/**
 * Face-on projection of the galactic plane onto a square screen viewport. The map is seen from
 * the north galactic pole with the Sun below the centre, as in the standard face-on Milky Way
 * charts: galactocentric +x (GC → Sun) points down the screen and +y points right, which makes
 * (x down, y right, z towards the viewer) right-handed and puts l = 90° (the direction of rotation
 * at the Sun, −y) on the left, so the galaxy turns clockwise as it really does seen from the NGP.
 *
 * <p>Pure Java: no Minecraft types, unit-tested.
 */
public final class MapView {
    /** View widths of the zoom levels, pc: whole disk, local arm, local bubble, nearest stars. */
    public static final double[] ZOOM_WIDTHS_PC = {30_000.0, 3_000.0, 300.0, 30.0};
    /** Half the side of the face-on background bake, pc (the disk's light is gone by ~15 kpc). */
    public static final double BACKGROUND_HALF_PC = 15_000.0;

    private final double centreX, centreY, widthPc;
    private final int left, top, size;

    public MapView(double centreX, double centreY, double widthPc, int left, int top, int size) {
        this.centreX = centreX;
        this.centreY = centreY;
        this.widthPc = widthPc;
        this.left = left;
        this.top = top;
        this.size = size;
    }

    public static int clampLevel(int level) {
        return Math.max(0, Math.min(ZOOM_WIDTHS_PC.length - 1, level));
    }

    /** Scrolling up zooms in (one level per notch, any fractional scroll still counts as one). */
    public static int levelAfterScroll(int level, double scrollY) {
        if (scrollY > 0) return clampLevel(level + 1);
        if (scrollY < 0) return clampLevel(level - 1);
        return level;
    }

    public double widthPc() { return widthPc; }
    public double pcPerPixel() { return widthPc / size; }
    public int left() { return left; }
    public int top() { return top; }
    public int size() { return size; }

    public double screenX(double gx, double gy) {
        return left + size * 0.5 + (gy - centreY) / widthPc * size;
    }

    public double screenY(double gx, double gy) {
        return top + size * 0.5 + (gx - centreX) / widthPc * size;
    }

    /** Galactocentric x (pc) under a screen point. */
    public double galacticX(double sx, double sy) {
        return centreX + (sy - top - size * 0.5) / size * widthPc;
    }

    /** Galactocentric y (pc) under a screen point. */
    public double galacticY(double sx, double sy) {
        return centreY + (sx - left - size * 0.5) / size * widthPc;
    }

    public boolean contains(double sx, double sy) {
        return sx >= left && sx < left + size && sy >= top && sy < top + size;
    }

    /** Galactocentric bounds of the viewport: {xMin, xMax, yMin, yMax}. */
    public double[] bounds() {
        double h = widthPc * 0.5;
        return new double[] {centreX - h, centreX + h, centreY - h, centreY + h};
    }

    /**
     * The part of the background texture that falls inside the viewport, as integer screen
     * corners and the matching UVs: {x0, y0, x1, y1, u0, u1, v0, v1}, or null when it is off
     * screen. UVs are derived from the rounded corners so the image does not swim when panning.
     * Texture u runs along +y and v along +x, matching the projection.
     */
    public float[] backgroundBlit() {
        double sx0 = screenX(0, -BACKGROUND_HALF_PC), sx1 = screenX(0, BACKGROUND_HALF_PC);
        double sy0 = screenY(-BACKGROUND_HALF_PC, 0), sy1 = screenY(BACKGROUND_HALF_PC, 0);
        int x0 = (int) Math.max(left, Math.round(sx0)), x1 = (int) Math.min(left + size, Math.round(sx1));
        int y0 = (int) Math.max(top, Math.round(sy0)), y1 = (int) Math.min(top + size, Math.round(sy1));
        if (x1 <= x0 || y1 <= y0) return null;
        float u0 = (float) ((x0 - sx0) / (sx1 - sx0)), u1 = (float) ((x1 - sx0) / (sx1 - sx0));
        float v0 = (float) ((y0 - sy0) / (sy1 - sy0)), v1 = (float) ((y1 - sy0) / (sy1 - sy0));
        return new float[] {x0, y0, x1, y1, u0, u1, v0, v1};
    }

    /** A 1-2-5 scale bar about a fifth of the view wide: {length pc, length px}. */
    public double[] scaleBar() {
        double target = widthPc / 5;
        double p = Math.pow(10, Math.floor(Math.log10(target)));
        double nice = target / p >= 5 ? 5 * p : target / p >= 2 ? 2 * p : p;
        return new double[] {nice, nice / widthPc * size};
    }

    /** "5 kpc", "200 pc", "2 pc". */
    public static String formatPc(double pc) {
        if (pc >= 1000) return (pc % 1000 == 0 ? String.valueOf((long) (pc / 1000)) : String.format(java.util.Locale.ROOT, "%.1f", pc / 1000)) + " kpc";
        return (pc == Math.floor(pc) ? String.valueOf((long) pc) : String.format(java.util.Locale.ROOT, "%.1f", pc)) + " pc";
    }

    /**
     * Marker half-size in pixels from absolute visual magnitude: the Sun (M = 4.8) and fainter get a
     * single pixel, a B star (M ≈ −2) two, a supergiant (M ≤ −6) the maximum of three.
     */
    public static int markerRadius(double absoluteMagnitude) {
        if (!Double.isFinite(absoluteMagnitude)) return 1;
        return (int) Math.max(0, Math.min(3, Math.round((6.0 - absoluteMagnitude) / 4.0)));
    }
}
