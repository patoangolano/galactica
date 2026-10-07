package dev.galactica.client.earthsky;

/**
 * How the Sun looks through Earth's atmosphere, free of Minecraft types: the colour filter that airmass
 * extinction puts on its (saturated) disc and how strongly its glare shows.
 *
 * <p>Airmass from Kasten &amp; Young (1989), valid down to the horizon (X ≈ 38 at h = 0). Extinction
 * coefficients are those of a clear sea-level sky in magnitudes per airmass at the centres of the sRGB
 * primaries (Rayleigh plus a light aerosol load; Hayes &amp; Latham 1975 give 0.10 at 620 nm, 0.16 at
 * 550 nm, 0.30 at 450 nm). The Sun is far brighter than anything a screen shows, so its disc is clipped
 * white at any useful height: the transmitted light is treated as an exposure {@link #EXPOSURE} times
 * display white and tone-mapped with {@code 1 − e^(−x)}, the same curve the star shader uses. That keeps
 * the noon Sun white, turns it yellow below ~5°, orange at 2° and deep red, dimmed, on the horizon, which
 * is what the eye sees, instead of a linear filter that would make it vanish long before it sets.
 */
public final class SunAppearance {
    private SunAppearance() {}

    /** Extinction, magnitudes per airmass, for R, G, B. */
    public static final double K_R = 0.10, K_G = 0.16, K_B = 0.30;
    /** Sun's brightness over display white, after the eye's or camera's adaptation to the daylit sky. */
    public static final double EXPOSURE = 30.0;
    /** Below this altitude (degrees) the Sun has fully set behind the sea-level horizon (its upper limb plus refraction). */
    public static final double SET_DEG = -0.9, RISE_FULL_DEG = 0.3;

    /** Relative optical airmass at apparent altitude {@code hDeg} (Kasten &amp; Young 1989); 1 at the zenith. */
    public static double airmass(double hDeg) {
        double h = Math.max(-0.5, Math.min(90, hDeg));
        return 1.0 / (Math.sin(Math.toRadians(h)) + 0.50572 * Math.pow(h + 6.07995, -1.6364));
    }

    /**
     * Display colour filter (0..1 per channel) for a Sun at altitude {@code hDeg}, already including how much of
     * the disc is above the horizon. Writes R, G, B into {@code out} and returns it.
     */
    public static float[] filter(double hDeg, float[] out) {
        double x = airmass(hDeg);
        double vis = visibility(hDeg);
        out[0] = (float) (vis * channel(K_R, x));
        out[1] = (float) (vis * channel(K_G, x));
        out[2] = (float) (vis * channel(K_B, x));
        return out;
    }

    private static double channel(double k, double airmass) {
        double t = Math.pow(10, -0.4 * k * airmass);
        double zenith = Math.pow(10, -0.4 * k);
        return (1 - Math.exp(-EXPOSURE * t)) / (1 - Math.exp(-EXPOSURE * zenith));
    }

    /** Share of the Sun still above the horizon, 0 once set, 1 when clear of it. */
    public static double visibility(double hDeg) {
        double s = (hDeg - SET_DEG) / (RISE_FULL_DEG - SET_DEG);
        s = Math.max(0, Math.min(1, s));
        return s * s * (3 - 2 * s);
    }

    /** Relative luminance of a filter (Rec. 709 weights): how strongly the glare around the disc shows. */
    public static double luminance(float[] f) {
        return 0.2126 * f[0] + 0.7152 * f[1] + 0.0722 * f[2];
    }

    /**
     * Glare added to the sky (fraction of display white, before the airmass filter) at {@code thetaDeg} from the Sun's
     * centre, for a sprite reaching {@code edgeDeg}. Three parts: the bloom a daylight exposure gives a source some
     * 10⁵ times the sky's surface brightness, a Gaussian blown out to white within ~1° (so on screen the Sun is a
     * glowing ball a few degrees wide around its true 0.53° disc); the solar aureole, forward scattering by aerosols
     * falling off roughly as θ^−1.3 (Deepak 1977); and a broad glow over the next tens of degrees. Tapered to zero at
     * the sprite edge.
     */
    public static double glare(double thetaDeg, double edgeDeg) {
        if (thetaDeg >= edgeDeg) return 0;
        double core = 1.1 * Math.exp(-Math.pow(thetaDeg / 0.9, 2));
        double aureole = 0.35 * Math.pow(0.6 / Math.max(thetaDeg, 0.6), 1.3);
        double broad = 0.10 * Math.exp(-thetaDeg / 7.0);
        double e = 1 - Math.pow(thetaDeg / edgeDeg, 2);
        return Math.min(1, (core + aureole + broad) * e * e);
    }
}
