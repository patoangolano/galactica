package dev.galactica.planet;

import dev.galactica.system.Body;

/**
 * Sky colour of a body's atmosphere as a function of view zenith angle, scattering angle, sun
 * elevation and the observer's altitude: single scattering by gas (Rayleigh) and aerosol/haze (Mie,
 * Henyey–Greenstein phase) in a plane-parallel exponential atmosphere. Used on both sides of the
 * space ↔ surface switch, so the sky above you darkens towards black as you climb and the two
 * dimensions show the same sky at the switch altitude.
 *
 * <p><b>Model.</b> Above altitude h the vertical optical depth is τ(h) = τ₀·e^(−h/H) (scale height H
 * from {@link Body#scaleHeight()}). For a slab of optical depth τ seen upward with air mass m_v and
 * lit by the sun at air mass m_s, the single-scattered radiance is (Chandrasekhar 1960, §1.5;
 * Liou 2002, §3.4) <pre>
 *   L = F/(4π) · Σ ω_i τ_i P_i(Θ) · m_v (e^(−τ m_v) − e^(−τ m_s)) / ((m_s − m_v) τ)
 * </pre> and for the slab seen from above (the ground below a high observer)
 * <pre>
 *   L = F/(4π) · Σ ω_i τ_i P_i(Θ) · m_v (1 − e^(−τ (m_s + m_v))) / ((m_s + m_v) τ).
 * </pre> Air masses use Kasten &amp; Young (1989), finite at the horizon (≈ 38). Rayleigh optical
 * depth of 1 bar of air at 550 nm is 0.0975 (Bucholtz 1995) and scales as λ^−4.08; the RGB channels
 * use effective wavelengths 615 / 550 / 465 nm. Phase functions: Rayleigh 3(1+cos²Θ)/(16π); aerosol
 * Henyey–Greenstein.
 *
 * <p><b>Classes</b> ({@link SurfaceAtmosphere.SkyClass}): Earth-like air adds τ_a = 0.1 continental
 * aerosol (Ångström α 1.3, g 0.76); Martian dust τ 0.5, grey (α 0), g 0.63 and blue-absorbing
 * single-scattering albedo 0.97/0.90/0.65 (Lemmon et al. 2004; Tomasko et al. 1999); Titan tholin
 * haze τ 5 with ω 0.95/0.75/0.45 (Tomasko et al. 2005); Venus and steam worlds are cloud decks of
 * τ ≫ 10. Multiple scattering inside a thick deck makes the overcast nearly uniform (Bohren 1987);
 * this single-scattering model reproduces that by capping the column it scatters through at
 * {@link #THICK_CAP}, keeping only the altitude falloff.
 *
 * <p><b>Calibration.</b> Absolute radiance is scaled per channel so that the zenith at sea level with
 * the sun 60° high is exactly the class's daytime sky colour, the colour the surface biome already
 * uses: at the ground nothing changes, and everything else (gradient, sunsets, altitude) follows
 * the physics.
 */
public final class AtmosphereGradient {

    /** Effective wavelengths of the R, G, B channels, nm. */
    static final double[] LAMBDA = {615, 550, 465};
    /** Rayleigh optical depth of 1 bar of N₂/O₂ at 550 nm (Bucholtz 1995). */
    public static final double TAU_RAYLEIGH_1BAR = 0.0975;
    /** CO₂ scatters ~2.4× more than air per molecule (Sneep &amp; Ubachs 2005). */
    static final double CO2_RAYLEIGH_FACTOR = 2.4;
    /** Largest column (550 nm) scattered through: stands in for multiple scattering in thick decks. */
    public static final double THICK_CAP = 3.0;
    /** Kasten–Young air mass at the horizon. */
    static final double HORIZON_AIRMASS = airmass(0);

    /** Vacuum: no sky at all. */
    public static final AtmosphereGradient NONE = new AtmosphereGradient(1, new double[3], new double[3], new double[3], 0.7,
            new double[3]);

    private final double scaleHeight;
    /** Vertical optical depth at the surface, per channel: gas and aerosol. */
    private final double[] tauR, tauA;
    /** Aerosol single-scattering albedo per channel. */
    private final double[] omegaA;
    private final double g;
    /** Per-channel calibration onto the class sky colour (linear). */
    private final double[] calib = new double[3];

    private AtmosphereGradient(double scaleHeight, double[] tauR, double[] tauA, double[] omegaA, double g, double[] targetLinear) {
        this.scaleHeight = Math.max(1, scaleHeight);
        this.tauR = tauR;
        this.tauA = tauA;
        this.omegaA = omegaA;
        this.g = g;
        double[] ref = new double[3];
        double mu60 = Math.sin(Math.toRadians(60));
        rawSky(1, mu60, mu60, 0, ref);
        for (int c = 0; c < 3; c++) calib[c] = ref[c] > 1e-12 ? targetLinear[c] / ref[c] : 0;
    }

    public static AtmosphereGradient of(Body b) {
        return of(SurfaceAtmosphere.skyClass(b), b.surfacePressureBar(), b.scaleHeight());
    }

    /** Builds the model for a sky class at a surface pressure (bar) and scale height (m). */
    public static AtmosphereGradient of(SurfaceAtmosphere.SkyClass sky, double pressureBar, double scaleHeightM) {
        if (sky == SurfaceAtmosphere.SkyClass.BLACK || !(pressureBar > 0)) return NONE;
        double[] tR = new double[3], tA = new double[3], w = new double[3];
        double gasTau, aerTau, alpha, gg;
        double[] omega;
        switch (sky) {
            case BLUE -> {
                gasTau = TAU_RAYLEIGH_1BAR * pressureBar;
                aerTau = pressureBar >= SurfaceAtmosphere.MIN_HAZE_BAR ? 0.1 : 0.0;
                alpha = 1.3; gg = 0.76; omega = new double[] {0.95, 0.95, 0.95};
            }
            case BUTTERSCOTCH -> {
                gasTau = TAU_RAYLEIGH_1BAR * CO2_RAYLEIGH_FACTOR * pressureBar;
                aerTau = 0.5; alpha = 0.0; gg = 0.63; omega = new double[] {0.97, 0.90, 0.65};
            }
            case ORANGE -> {
                gasTau = TAU_RAYLEIGH_1BAR * pressureBar;
                aerTau = 5.0; alpha = 1.0; gg = 0.6; omega = new double[] {0.95, 0.75, 0.45};
            }
            case YELLOW -> {
                gasTau = TAU_RAYLEIGH_1BAR * CO2_RAYLEIGH_FACTOR * pressureBar;
                aerTau = 30; alpha = 0.0; gg = 0.75; omega = new double[] {0.999, 0.99, 0.93};
            }
            case STEAM -> {
                gasTau = TAU_RAYLEIGH_1BAR * 0.7 * pressureBar;
                aerTau = 20; alpha = 0.0; gg = 0.85; omega = new double[] {0.999, 0.999, 0.999};
            }
            default -> {
                return NONE;
            }
        }
        double total550 = gasTau + aerTau;
        double cap = total550 > THICK_CAP ? THICK_CAP / total550 : 1;
        for (int c = 0; c < 3; c++) {
            double l = LAMBDA[1] / LAMBDA[c];
            tR[c] = gasTau * Math.pow(l, 4.08) * cap;
            tA[c] = aerTau * Math.pow(l, alpha) * cap;
            w[c] = omega[c];
        }
        int rgb = sky.sky;
        double[] target = {decode((rgb >> 16) & 0xFF), decode((rgb >> 8) & 0xFF), decode(rgb & 0xFF)};
        return new AtmosphereGradient(scaleHeightM, tR, tA, w, gg, target);
    }

    public boolean vacuum() {
        return this == NONE || tauR[1] + tauA[1] <= 0;
    }

    public double scaleHeight() {
        return scaleHeight;
    }

    /** Fraction of the atmosphere's column above altitude h (m): e^(−h/H). */
    public double columnAbove(double altitudeM) {
        return Math.exp(-Math.max(0, altitudeM) / scaleHeight);
    }

    /**
     * Linear RGB sky radiance looking up/out along a direction with cosine-of-zenith {@code muView},
     * scattering angle cosine {@code cosScatter} (view · sun) and sun elevation sine {@code muSun},
     * from altitude {@code altitudeM}. Calibrated: the sea-level zenith with the sun 60° high equals
     * the class sky colour.
     */
    public void sky(double muView, double cosScatter, double muSun, double altitudeM, double[] rgb) {
        rawSky(muView, cosScatter, muSun, altitudeM, rgb);
        for (int c = 0; c < 3; c++) rgb[c] *= calib[c];
    }

    private void rawSky(double muView, double cosScatter, double muSun, double altitudeM, double[] rgb) {
        double col = columnAbove(altitudeM);
        double mv = airmass(muView), ms = airmass(muSun);
        double sun = sunVisibility(muSun);
        double pr = rayleighPhase(cosScatter), pa = hgPhase(cosScatter, g);
        double below = muView < 0 ? 0.85 : 1; // below the horizon from altitude: ground haze, a shade darker
        for (int c = 0; c < 3; c++) {
            double tr = tauR[c] * col, ta = tauA[c] * col, t = tr + ta;
            double scat = tr * pr + omegaA[c] * ta * pa;
            rgb[c] = scat <= 0 ? 0 : sun * below * scat * slabUp(t, mv, ms);
        }
    }

    /**
     * Seen from altitude {@code altitudeM} looking down at the ground with air mass of view cosine
     * {@code muView} (cosine between the local up and the direction back to the observer): the
     * transmittance of the column between and the light it scatters into the line of sight.
     */
    public void lookDown(double muView, double cosScatter, double muSun, double altitudeM, double[] inscatter, double[] transmittance) {
        double above = columnAbove(altitudeM), between = 1 - above;
        double mv = airmass(muView), ms = airmass(muSun);
        double sun = sunVisibility(muSun);
        double pr = rayleighPhase(cosScatter), pa = hgPhase(cosScatter, g);
        for (int c = 0; c < 3; c++) {
            double tr = tauR[c] * between, ta = tauA[c] * between, t = tr + ta;
            double scat = tr * pr + omegaA[c] * ta * pa;
            double sunAbove = Math.exp(-(tauR[c] + tauA[c]) * above * ms);
            transmittance[c] = Math.exp(-t * mv);
            inscatter[c] = scat <= 0 ? 0 : calib[c] * sun * sunAbove * scat * slabDown(t, mv, ms);
        }
    }

    /** Direct-sun transmittance to the ground (per channel) at sun elevation sine {@code muSun}. */
    public void sunTransmittance(double muSun, double[] rgb) {
        double ms = airmass(muSun), sun = sunVisibility(muSun);
        for (int c = 0; c < 3; c++) rgb[c] = sun * Math.exp(-(tauR[c] + tauA[c]) * ms);
    }

    /**
     * Aerial perspective over a near-horizontal path of {@code pathM} metres at mean altitude
     * {@code altitudeM}: Koschmieder's airlight law (1924), L = L_horizon·(1 − e^(−β d)) with the
     * extinction coefficient β = τ₀/H · e^(−h/H) of an exponential atmosphere.
     */
    public void horizontal(double pathM, double altitudeM, double cosScatter, double muSun, double[] inscatter, double[] transmittance) {
        sky(0, cosScatter, muSun, altitudeM, inscatter);
        double dens = columnAbove(altitudeM) / scaleHeight;
        for (int c = 0; c < 3; c++) {
            double t = Math.exp(-(tauR[c] + tauA[c]) * dens * Math.max(0, pathM));
            transmittance[c] = t;
            inscatter[c] *= 1 - t;
        }
    }

    /**
     * Zenith sky luminance at altitude h relative to sea level (sun 60° high): 1 on the ground, → 0
     * towards space. Scales how much daylight washes out stars and planets.
     */
    public double brightness(double altitudeM) {
        if (vacuum()) return 0;
        double mu60 = Math.sin(Math.toRadians(60));
        double[] a = new double[3], b = new double[3];
        sky(1, mu60, mu60, 0, a);
        sky(1, mu60, mu60, altitudeM, b);
        double la = luminance(a);
        return la <= 0 ? 0 : Math.min(1.5, luminance(b) / la);
    }

    // ---------------------------------------------------------------- surface altitude

    /**
     * Physical altitude of a camera in a surface slot. The slot's world is only a few hundred blocks
     * tall, while the space ↔ surface switch happens at the entry shell (Earth: H·ln(10⁶) ≈ 117 km).
     * Near the ground one block is {@code metresPerBlock} metres of height, as it is horizontally;
     * higher up the scale grows exponentially so that the entry Y lands exactly on the entry altitude:
     * <pre>
     *   z(y) = m·s·(e^(y/s) − 1),   with s solving  m·s·(e^(Y_e/s) − 1) = z_e
     * </pre> (y blocks above the datum, m metres per block, Y_e the entry height in blocks, z_e the
     * entry altitude). dz/dy = m at the ground, z(Y_e) = z_e, monotonic in between, so the sky both
     * sides of the switch is computed at the same altitude. When z_e ≤ m·Y_e the map is linear with
     * slope z_e/Y_e instead.
     */
    public record AltitudeMap(double metresPerBlock, double scaleBlocks, double linearSlope) {

        public static AltitudeMap of(double metresPerBlock, double entryBlocks, double entryAltitudeM) {
            double m = Math.max(1e-6, metresPerBlock), d = Math.max(1, entryBlocks), z = Math.max(0, entryAltitudeM);
            if (z <= m * d) return new AltitudeMap(m, Double.POSITIVE_INFINITY, z / d);
            // f(s) = m·s·(e^(d/s) − 1) falls monotonically from ∞ (s → 0) to m·d (s → ∞): bisect ln s.
            double lo = Math.log(d / 700), hi = Math.log(d * 1e9);
            for (int i = 0; i < 200; i++) {
                double mid = 0.5 * (lo + hi), s = Math.exp(mid);
                double f = m * s * Math.expm1(d / s);
                if (f > z) lo = mid;
                else hi = mid;
            }
            return new AltitudeMap(m, Math.exp(0.5 * (lo + hi)), m);
        }

        /** Altitude in metres of a point {@code blocksAboveDatum} above the datum (0 at or below it). */
        public double metres(double blocksAboveDatum) {
            double y = Math.max(0, blocksAboveDatum);
            if (Double.isInfinite(scaleBlocks)) return linearSlope * y;
            return metresPerBlock * scaleBlocks * Math.expm1(Math.min(600, y / scaleBlocks));
        }
    }

    // ---------------------------------------------------------------- physics helpers

    /** Kasten &amp; Young (1989) relative air mass for elevation sine μ (μ ≤ 0 → horizon value ≈ 38). */
    public static double airmass(double mu) {
        double m = Math.max(0, Math.min(1, mu));
        double zenithDeg = Math.toDegrees(Math.acos(m));
        return 1 / (m + 0.50572 * Math.pow(96.07995 - zenithDeg, -1.6364));
    }

    /** Sun disc above the horizon, fading through civil twilight (sun down to −6°): the day weight. */
    public static double sunVisibility(double muSun) {
        return PlanetMacro.smooth(-0.105, 0.02, muSun);
    }

    static double rayleighPhase(double c) {
        return 3.0 / (16 * Math.PI) * (1 + c * c);
    }

    static double hgPhase(double c, double g) {
        double d = 1 + g * g - 2 * g * c;
        return (1 - g * g) / (4 * Math.PI * d * Math.sqrt(d));
    }

    /** m_v (e^(−τ m_v) − e^(−τ m_s)) / ((m_s − m_v) τ): the upward-view slab term per unit τ. */
    static double slabUp(double tau, double mv, double ms) {
        if (tau < 1e-12) return mv;
        double d = ms - mv;
        if (Math.abs(d) < 1e-6) return mv * Math.exp(-tau * mv);
        return mv * (Math.exp(-tau * mv) - Math.exp(-tau * ms)) / (d * tau);
    }

    /** m_v (1 − e^(−τ (m_s + m_v))) / ((m_s + m_v) τ): the downward-view slab term per unit τ. */
    static double slabDown(double tau, double mv, double ms) {
        double s = ms + mv;
        if (tau * s < 1e-9) return mv;
        return mv * (1 - Math.exp(-tau * s)) / (s * tau);
    }

    static double luminance(double[] rgb) {
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    /** 8-bit sRGB → linear (IEC 61966-2-1). */
    static double decode(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Linear → sRGB in [0, 1] (IEC 61966-2-1), clamped. */
    public static double encode(double linear) {
        double c = Math.max(0, Math.min(1, linear));
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }
}
