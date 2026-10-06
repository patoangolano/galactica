package dev.galactica.client.map;

import dev.galactica.galaxy.GalaxyModel;

/**
 * Spiral-arm legend. Arms are identified exactly as {@link GalaxyModel#nearestArm} numbers them
 * (the same names the nav HUD shows), and each label sits on its arm's ridge, found numerically
 * as the maximum of {@link GalaxyModel#armStrength} along a circle: the arm geometry stays owned
 * by the model and the legend cannot drift from the rendered arms.
 */
public final class ArmLegend {
    private ArmLegend() {}

    public static final int ARM_COUNT = 4;
    /** Lang keys in {@link GalaxyModel#nearestArm} order. */
    public static final String[] KEYS = {
            "map.galactica.arm.norma", "map.galactica.arm.scutum_centaurus",
            "map.galactica.arm.sagittarius_carina", "map.galactica.arm.perseus"};
    /** Legend swatch colours (ARGB), distinct hues that read on the warm galaxy background. */
    public static final int[] COLOURS = {0xFFFF8A65, 0xFF4FC3F7, 0xFFAED581, 0xFFCE93D8};
    /** Labels sit at 11 kpc: outside the bar, inside the radius where the arms fade. */
    static final double LABEL_RADIUS_PC = 11_000.0;

    private static double[][] anchors;

    /** Galactocentric (x, y) of each arm's label, indexed like {@link #KEYS}. */
    public static synchronized double[][] anchors() {
        if (anchors == null) anchors = computeAnchors(LABEL_RADIUS_PC);
        return anchors;
    }

    static double[][] computeAnchors(double radiusPc) {
        double[][] out = new double[ARM_COUNT][];
        double[] best = new double[ARM_COUNT];
        int steps = 1440;
        for (int i = 0; i < steps; i++) {
            double th = 2 * Math.PI * i / steps;
            double x = radiusPc * Math.cos(th), y = radiusPc * Math.sin(th);
            int arm = GalaxyModel.nearestArm(x, y);
            double s = GalaxyModel.armStrength(x, y);
            if (s > best[arm]) {
                best[arm] = s;
                out[arm] = new double[] {x, y};
            }
        }
        for (int a = 0; a < ARM_COUNT; a++) if (out[a] == null) out[a] = new double[] {0, 0};
        return out;
    }
}
