package dev.galactica.deepsky;

import dev.galactica.science.Blackbody;
import dev.galactica.star.Star;

import java.util.ArrayList;
import java.util.List;

/**
 * The continuous volumes ({@link NebulaField}) of named nebulae, laid out from their observed
 * structure. Lengths are parsecs in a frame aligned with the sky as seen from the Sun (x towards
 * increasing right ascension = east, y north, z along the line of sight away from the Sun), so
 * the features land where photographs show them.
 */
public final class NebulaShapes {
    private NebulaShapes() {}

    /** Equatorial frame at {@code c}: x east, y north, z away from the Sun, scaled per axis. */
    static Morphology.Frame equatorial(double[] c, double se, double sn, double sd) {
        double[][] b = DeepSkyCatalog.equatorialBasis(c);
        return new Morphology.Frame(c, b[0], b[1], b[2], se, sn, sd);
    }

    /** Offset of {@code c} by (east, north, depth) pc in its equatorial frame. */
    static double[] offset(double[] c, double east, double north, double depth) {
        double[][] b = DeepSkyCatalog.equatorialBasis(c);
        return new double[] {
                c[0] + b[0][0] * east + b[1][0] * north + b[2][0] * depth,
                c[1] + b[0][1] * east + b[1][1] * north + b[2][1] * depth,
                c[2] + b[0][2] * east + b[1][2] * north + b[2][2] * depth};
    }

    /**
     * M42 and M43 at 412 pc (1′ = 0.12 pc), after O'Dell 2001 (ARA&amp;A 39, 99) and O'Dell et al.
     * 2009/2017 for the 3D structure:
     * <ul>
     *   <li>a blister: the ionised layer lies on the concave face of OMC-1 about 0.3 pc behind
     *       θ¹ Ori C (local origin), open towards the Sun, the emission falling from the
     *       ~10⁴ cm⁻³ Huygens region outwards (n ∝ r^−1.4, Wen &amp; O'Dell 1995) into the faint
     *       Extended Orion Nebula, which opens to the south-west;</li>
     *   <li>the Orion Bar, 2′ south-east of the Trapezium, running north-east–south-west: the
     *       front seen edge-on where the cloud surface rises towards us;</li>
     *   <li>the Dark Bay ("Fish's Mouth"): foreground dust (A_V ≈ 3–5, the Veil's densest part)
     *       intruding from the east to within 1.5′ of the Trapezium, and the lane that runs west
     *       from it separating M42 from M43;</li>
     *   <li>[O III]-bright inner zone (teal) around θ¹ Ori C, Hα-red outside;</li>
     *   <li>M43, ionised by NU Ori 7′ north, a small blister of its own.</li>
     * </ul>
     * Line luminosity L(Hα+Hβ) ≈ 3.6 × 10³ L☉ from Q(H⁰) ≈ 10⁴⁹ s⁻¹ of θ¹ Ori C.
     */
    static List<NebulaField> orionNebula(long seed, double[] trapezium) {
        List<NebulaField> out = new ArrayList<>();
        HiiField.Spec s = new HiiField.Spec();
        s.front = 2.4;
        s.frontRough = 0.3;
        // The Extended Orion Nebula opens to the south-west; the bright Huygens region is
        // displaced the same way from θ¹ Ori C.
        s.frontBias = new double[] {-0.25, -0.35, 0};
        s.edgeFade = 0.55;
        s.coreOffset = new double[] {-0.06, -0.08, 0};
        s.fingers = 0.15;
        s.core = 0.13;
        s.slope = 1.8;
        s.turbulence = 0.45;
        s.turbScale = 0.7;
        s.wisps = 0.6;
        s.oiii = 1.2;
        s.oiiiRadius = 0.35;
        s.blister = true;
        s.blisterDepth = 0.3;
        s.layer = 0.22;
        s.wrinkle = 0.12;
        // A cup: the cloud surface curves towards the Sun away from the Trapezium, so its rims are
        // seen obliquely and limb-brightened (the "wings").
        s.bowl = -0.09;
        s.cloudAv = 60;
        s.cloudRadius = 2.0;
        s.cloudDepth = 0.8;
        // Orion Bar: 0.24 pc SE, along PA 50° (NE–SW).
        s.barPoint = new double[] {0.17, -0.17};
        double pa = Math.toRadians(50);
        s.barDir = new double[] {Math.sin(pa), Math.cos(pa)};
        s.barHeight = 0.45;
        s.barWidth = 0.035;
        s.barLength = 0.35;
        s.neutralAv = 2.0;
        s.neutralCover = 0.15;
        s.lanes = 2;
        s.laneAv = 1.2;
        s.bays = new double[][] {
                // Dark Bay: from 0.18 pc ENE of the Trapezium out to the east-north-east.
                {0.20, 0.10, -0.45, 1.20, 0.45, -0.6, 0.16, 4.5},
                // The lane between M42 and M43, running west from the bay's root.
                {0.25, 0.42, -0.4, -0.75, 0.62, -0.45, 0.11, 3.0},
                // Its continuation curving south-west along the western wing.
                {-0.75, 0.62, -0.45, -1.7, 0.05, -0.5, 0.14, 1.6}};
        s.diffuse = 0.03;
        s.bound = 3.4;
        // The Extended Orion Nebula reaches further south than north: an ellipsoid elongated N–S.
        out.add(new HiiField(seed, equatorial(trapezium, 1.0, 1.25, 1.0), s, 3_600 * Morphology.LINE_GAIN * 1.6));

        HiiField.Spec m43 = new HiiField.Spec();
        m43.front = 0.32;
        m43.frontRough = 0.2;
        m43.fingers = 0.1;
        m43.core = 0.06;
        m43.slope = 1.6;
        m43.turbulence = 0.7;
        m43.turbScale = 0.12;
        m43.oiii = 0.1;
        m43.oiiiRadius = 0.1;
        m43.blister = true;
        m43.blisterDepth = 0.12;
        m43.layer = 0.08;
        m43.wrinkle = 0.04;
        m43.bowl = 0.3;
        m43.cloudAv = 20;
        m43.cloudRadius = 0.4;
        m43.cloudDepth = 0.2;
        m43.neutralAv = 2.0;
        m43.neutralCover = 0.4;
        m43.edgeFade = 0.4;
        m43.wisps = 0.4;
        m43.bound = 1.5;
        out.add(new HiiField(seed ^ 43, equatorial(offset(trapezium, 0.05, 0.84, 0.15), 1, 1, 1), m43, 90 * Morphology.LINE_GAIN * 1.1));
        return out;
    }

    /**
     * Barnard's Loop: a 14°-wide arc of Hα east of Orion, ~55 pc in radius at ~380 pc, the edge
     * of the Orion–Eridanus superbubble (Ochsendorf et al. 2015, ApJ 808, 111). Filamentary and
     * broken, brightest on the eastern side.
     */
    static NebulaField barnardsLoop(long seed, Morphology.Frame f) {
        ShellField.Spec s = new ShellField.Spec();
        s.thickness = 0.045;
        s.patchiness = 0.45;
        s.filaments = 0.65;
        s.arcDir = DeepMath.normalize(new double[] {1, 0.6, 0});
        s.arcCos = 0.05;
        s.oiii = 0.02;
        s.bound = 1.3;
        return new ShellField(seed, f, s, 12_000 * Morphology.LINE_GAIN);
    }

    /**
     * The λ Orionis ring: an H II region ~28 pc in radius ionised by λ Ori (O8 III), filled with
     * diffuse Hα and brightest towards its rim, inside a broken ring of dust and molecular gas
     * (B30, B35, Lang et al. 2000, A&amp;A 357, 1001; Dolan &amp; Mathieu 2002).
     */
    static NebulaField lambdaOrionis(long seed, Morphology.Frame f) {
        ShellField.Spec s = new ShellField.Spec();
        s.thickness = 0.16;
        s.fill = 0.55;
        s.patchiness = 0.55;
        s.filaments = 0.45;
        s.oiii = 0.05;
        s.dustAv = 1.3;
        s.dustInner = 1.0;
        s.dustOuter = 1.4;
        s.dustCover = 0.3;
        s.dustRing = 0.22;
        s.bound = 1.5;
        return new ShellField(seed, f, s, 2_000 * Morphology.LINE_GAIN);
    }

    /**
     * Generic H II region (the shape of {@link Morphology#hiiRegion}, as a field): turbulent
     * ionised gas inside a perturbed front with neutral fingers and clumps around it.
     *
     * @param lHa    Hα + Hβ luminosity, L☉ (before {@link Morphology#LINE_GAIN})
     * @param oiii   [O III]/Hα near the star
     * @param dustAv peak A_V of the neutral clumps and lanes
     * @param lanes  foreground dust lanes across the face
     */
    static HiiField hii(long seed, Morphology.Frame f, double lHa, double oiii, double dustAv, int lanes, double fingers) {
        HiiField.Spec s = new HiiField.Spec();
        s.front = 0.85;
        s.frontRough = 0.3;
        s.fingers = fingers;
        s.core = 0.35;
        s.slope = 1.6;
        s.turbulence = 0.65;
        s.turbScale = 0.4;
        s.wisps = 0.45;
        s.oiii = oiii;
        s.oiiiRadius = 0.4;
        s.neutralAv = dustAv;
        s.neutralCover = 0.4;
        s.lanes = lanes;
        s.laneAv = 0.6 * dustAv;
        s.diffuse = 0.06;
        s.bound = 1.25;
        return new HiiField(seed, f, s, lHa * Morphology.LINE_GAIN * (1 + 0.6 * oiii));
    }

    /** A procedural H II region (see {@code ProceduralDeepSky}): the generic shape. */
    static HiiField proceduralHii(long seed, Morphology.Frame f, double lHa, double oiii, double dustAv, int lanes) {
        return hii(seed, f, lHa, oiii, dustAv, lanes, 0.12 + 0.12 * ((seed >>> 7) & 1));
    }

    /**
     * The Carina Nebula: Q ≈ 10⁵¹ s⁻¹ from Tr 14/16 and η Car (Smith 2006, MNRAS 367, 763),
     * cut by the dark V-shaped lane and the Keyhole's dust, rimmed with pillars where the
     * front eats into the surrounding cloud (Smith et al. 2010, MNRAS 406, 952).
     */
    static NebulaField carina(long seed, Morphology.Frame f) {
        HiiField h = hii(seed, f, 280_000, 0.8, 5.0, 3, 0.28);
        return h;
    }

    /** The Lagoon Nebula: one broad lane (the "lagoon") across a bright H II region (Tothill et al. 2008). */
    static NebulaField lagoon(long seed, Morphology.Frame f) {
        HiiField.Spec s = new HiiField.Spec();
        s.front = 0.85;
        s.frontRough = 0.3;
        s.fingers = 0.15;
        s.core = 0.3;
        s.slope = 1.6;
        s.turbulence = 0.6;
        s.turbScale = 0.4;
        s.wisps = 0.45;
        s.oiii = 0.35;
        s.oiiiRadius = 0.35;
        s.neutralAv = 3.0;
        s.neutralCover = 0.35;
        s.bays = new double[][] {{-0.9, 0.12, -0.5, 0.9, -0.08, -0.5, 0.12, 3.5}};
        s.diffuse = 0.06;
        s.bound = 1.25;
        return new HiiField(seed, f, s, 35_000 * Morphology.LINE_GAIN * 1.2);
    }

    /**
     * The Eagle Nebula around NGC 6611: an H II region whose front is fingered into pillars
     * (the Pillars of Creation are the most famous of many, Hester et al. 1996).
     */
    static NebulaField eagle(long seed, Morphology.Frame f) {
        return hii(seed, f, 50_000, 0.6, 4.0, 1, 0.32);
    }

    /**
     * The Rosette: a thick shell of ionised gas around a wind-blown central cavity (NGC 2244's
     * O stars), with dark globules and elephant trunks along its inner edge (Schneps et al. 1980).
     */
    static NebulaField rosette(long seed, Morphology.Frame f) {
        ShellField.Spec s = new ShellField.Spec();
        s.thickness = 0.2;
        s.patchiness = 0.35;
        s.filaments = 0.45;
        s.oiii = 0.25;
        s.dustAv = 2.5;
        s.dustInner = 0.62;
        s.dustOuter = 0.85;
        s.dustCover = 0.22;
        s.bound = 1.6;
        return new ShellField(seed, f, s, 25_000 * Morphology.LINE_GAIN * 1.25);
    }

    /**
     * The North America and Pelican nebulae: one H II region (W80) split by the foreground dust
     * lane L935, which draws the "Gulf of Mexico" (Kuhn et al. 2020; Bally &amp; Scoville 1980).
     */
    static NebulaField northAmerica(long seed, Morphology.Frame f) {
        HiiField.Spec s = new HiiField.Spec();
        s.front = 0.95;
        s.frontRough = 0.35;
        s.fingers = 0.1;
        s.core = 0.6;
        s.slope = 1.4;
        s.turbulence = 0.55;
        s.turbScale = 0.45;
        s.wisps = 0.4;
        s.oiii = 0.15;
        s.neutralAv = 3.0;
        s.neutralCover = 0.3;
        // L935: from north to south-west between the continent (east) and the Pelican (west).
        s.bays = new double[][] {{0.05, 0.9, -0.6, -0.15, -0.2, -0.6, 0.22, 4.5}, {-0.15, -0.2, -0.6, 0.35, -0.75, -0.55, 0.2, 3.5}};
        s.diffuse = 0.1;
        s.bound = 1.3;
        return new HiiField(seed, f, s, 4_000 * Morphology.LINE_GAIN * 1.15);
    }

    /**
     * The dust the Pleiades are passing through, lit by the cluster's B stars: A_V ≈ 0.1–0.3 over
     * the cluster (E(B−V) ≈ 0.04, with denser knots near Merope; Gibson &amp; Nordsieck 2003), a
     * sheet ~0.6 pc thick slightly on the Sun's side of the cluster, striated along the stream.
     */
    static NebulaField pleiadesDust(long seed, StarCluster cluster, double[] centre) {
        List<ReflectionField.Light> lights = new ArrayList<>();
        float[] rgb = new float[3];
        for (Star st : cluster.anchorStars()) {
            double lv = Math.pow(10, -0.4 * (st.absoluteMagnitude() - 4.83));
            Blackbody.linearRgb(st.temperature(), rgb);
            double m = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            lights.add(new ReflectionField.Light(st.x(), st.y(), st.z(), lv * rgb[0] / m, lv * rgb[1] / m, lv * rgb[2] / m));
        }
        double[] c = offset(centre, 0.1, -0.15, -0.25);
        // Stream direction ~PA 120° on the sky (the striations of NGC 1435 run SE–NW).
        double[][] b = DeepSkyCatalog.equatorialBasis(c);
        double pa = Math.toRadians(125);
        double[] along = new double[3], across = new double[3];
        for (int k = 0; k < 3; k++) {
            along[k] = Math.sin(pa) * b[0][k] + Math.cos(pa) * b[1][k];
            across[k] = Math.cos(pa) * b[0][k] - Math.sin(pa) * b[1][k];
        }
        Morphology.Frame f = new Morphology.Frame(c, along, across, b[2], 3.2, 2.6, 1.0);
        return new ReflectionField(seed, f, 0.3, 0.3, lights);
    }
}
