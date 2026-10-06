package dev.galactica.deepsky;

import java.util.ArrayList;
import java.util.List;

import dev.galactica.sky.GalaxySkyBaker;
import dev.galactica.sky.SkyTonemap;

/**
 * A close-range impostor of a nebula: when the viewer is within a few radii of a catalogued
 * nebula its structure spans tens of degrees, far more than the 0.18° pixels of the 2048 × 1024
 * sky bake can show. This paints a gnomonic close-up of that patch of sky at screen resolution
 * (~885 px per radian at 70° FOV) with the same physics as the sky bake ({@link GalaxySkyBaker#bakeGrid}:
 * galaxy, deep-sky volumes, local adaptation), tonemapped to match the sky bake and feathered at
 * its rim so it blends into it. Pure Java; the client draws it as a textured quad on the sky shell.
 *
 * <p>The image is gnomonic (rectilinear), so it is exactly the view through a flat quad tangent to
 * the sky sphere at the target: pixel (u, v) looks along f + (2u − 1) tan(h) e₁ + (2v − 1) tan(h) e₂.
 */
public final class CloseUp {
    private CloseUp() {}

    /** Angular radius range (rad) of a nebula's volume for which a close-up is worth painting. */
    public static final double MIN_ANGULAR_RADIUS = Math.toRadians(1.2), MAX_ANGULAR_RADIUS = Math.toRadians(26);
    /**
     * Only compact nebulae get a close-up: their structure (fronts, lanes, the Trapezium's
     * neighbourhood, the Merope wisps) is what the bake cannot resolve. Bubbles tens of parsecs
     * across (Barnard's Loop, λ Ori) and dark clouds are smooth at the bake's scale.
     */
    public static final double MAX_RADIUS_PC = 12;
    /** Largest half-angle of the painted patch (rad): keeps the quad well inside the far plane. */
    public static final double MAX_HALF_ANGLE = 0.55;
    /** Patch half-angle over the volume's angular radius: room for the feathered rim. */
    public static final double MARGIN = 1.3;
    /** Fraction of the patch radius over which the image fades into the sky bake. */
    public static final double FEATHER_START = 0.72;

    /** The nebula to paint: its object, the unit galactocentric direction to its volume and the patch half-angle. */
    public record Target(DeepSkyObject object, double[] direction, double distancePc, double halfAngle) {}

    /**
     * The catalogued object with continuous volumes ({@link DeepSkyObject#fields}) that looks
     * largest from (ox, oy, oz) within [{@link #MIN_ANGULAR_RADIUS}, {@link #MAX_ANGULAR_RADIUS}],
     * the viewer outside it, no larger than {@link #MAX_RADIUS_PC}; null when there is none.
     * Galaxies and dark clouds are excluded.
     */
    public static Target target(double ox, double oy, double oz) {
        List<Target> t = targets(ox, oy, oz, 1);
        return t.isEmpty() ? null : t.get(0);
    }

    /**
     * Up to {@code max} close-up targets seen from (ox, oy, oz), largest on the sky first (near
     * M42: M42 itself and the Horsehead's IC 434 a few degrees away). See {@link #target}.
     */
    public static List<Target> targets(double ox, double oy, double oz, int max) {
        List<Target> out = new ArrayList<>();
        for (DeepSkyObject o : DeepSkyCatalog.all()) {
            if (o.type == DeepSkyType.GALAXY || o.type == DeepSkyType.DARK_NEBULA || o.fields().isEmpty()) continue;
            // Bounding sphere of all the object's volumes, centred on the first.
            NebulaField f0 = o.fields().get(0);
            double cx = f0.cx, cy = f0.cy, cz = f0.cz, r = 0;
            for (NebulaField f : o.fields()) {
                double dx = f.cx - cx, dy = f.cy - cy, dz = f.cz - cz;
                r = Math.max(r, Math.sqrt(dx * dx + dy * dy + dz * dz) + f.radius);
            }
            if (r > MAX_RADIUS_PC) continue;
            double vx = cx - ox, vy = cy - oy, vz = cz - oz;
            double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (d < 1.15 * r) continue;
            double theta = Math.asin(r / d);
            if (theta < MIN_ANGULAR_RADIUS || theta > MAX_ANGULAR_RADIUS) continue;
            out.add(new Target(o, new double[] {vx / d, vy / d, vz / d}, d, Math.min(MAX_HALF_ANGLE, MARGIN * theta)));
        }
        out.sort((x, y) -> Double.compare(y.halfAngle(), x.halfAngle()));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Paints the close-up: {@code size} × {@code size} ARGB, row 0 at v = 0. {@code e1}, {@code e2}
     * are unit galactocentric vectors across the patch (u and v directions), {@code forward} its
     * centre. Exposure, black point and local adaptation are anchored on a small all-sky bake from
     * the same point, as the sky bake's are on itself, so the patch matches the sky around it.
     */
    public static int[] paint(GalaxySkyBaker baker, double ox, double oy, double oz, double[] forward, double[] e1, double[] e2,
                              double halfAngle, int size) {
        // Reference sky: radiance p99 (local-adaptation threshold) and the tonemap of the adapted sky.
        int rw = 256, rh = 128;
        float[] ref = new float[rw * rh * 3];
        baker.bakeEquirectRadiance(ox, oy, oz, rw, rh, ref);
        double[] lum = new double[rw * rh];
        for (int p = 0; p < lum.length; p++) lum[p] = SkyTonemap.luminance(ref[p * 3], ref[p * 3 + 1], ref[p * 3 + 2]);
        java.util.Arrays.sort(lum);
        double p99 = lum[(int) (lum.length * 0.99)];
        GalaxySkyBaker.compressHighlights(ref, rw, rh, true);
        SkyTonemap tm = SkyTonemap.fit(ref);

        double t = Math.tan(halfAngle);
        double pixel = 2 * t / size;
        DeepSkyView view = baker.prepare(ox, oy, oz, pixel);
        float[] hdr = new float[size * size * 3];
        baker.bakeGrid(view, ox, oy, oz, size, size, hdr, new GalaxySkyBaker.DirectionGrid() {
            @Override
            public void direction(double u, double v, double[] g) {
                double a = (2 * u - 1) * t, b = (2 * v - 1) * t;
                double x = forward[0] + a * e1[0] + b * e2[0], y = forward[1] + a * e1[1] + b * e2[1], z = forward[2] + a * e1[2] + b * e2[2];
                double n = Math.sqrt(x * x + y * y + z * z);
                g[0] = x / n;
                g[1] = y / n;
                g[2] = z / n;
            }

            @Override
            public boolean wrapsU() {
                return false;
            }
        });
        GalaxySkyBaker.compressHighlights(hdr, size, size, pixel, false, p99);
        int[] out = new int[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int p = j * size + i;
                double u = (i + 0.5) / size * 2 - 1, v = (j + 0.5) / size * 2 - 1;
                double alpha = 1 - DeepMath.smoothstep(FEATHER_START, 1.0, Math.sqrt(u * u + v * v));
                if (alpha <= 0) continue;
                int rgb = tm.toArgb(hdr[p * 3], hdr[p * 3 + 1], hdr[p * 3 + 2]) & 0xFFFFFF;
                out[p] = ((int) Math.round(255 * alpha) << 24) | rgb;
            }
        }
        return out;
    }
}
