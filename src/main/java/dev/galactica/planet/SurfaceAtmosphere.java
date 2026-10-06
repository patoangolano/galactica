package dev.galactica.planet;

import dev.galactica.system.Body;

/**
 * What the sky of a landable body looks like from the ground. Colours are the dominant daytime sky
 * hue as photographed in situ (or modelled where nobody has been yet):
 * <ul>
 *   <li><b>Rayleigh blue</b>: N₂/O₂ at ≳ 0.1 bar scatters short wavelengths, λ⁻⁴ (Earth).</li>
 *   <li><b>Butterscotch</b>: a thin CO₂ sky is dark by gas scattering alone, but suspended ~1.5 µm
 *       iron-oxide dust gives Mars its tan sky (Pathfinder/Curiosity Mastcam; Lemmon et al. 2004).
 *       Any thin CO₂ air above 1 mbar is taken to hold dust; below that the sky is black.</li>
 *   <li><b>Tholin orange</b>: photochemical haze in N₂/CH₄ (Titan, Huygens DISR, Tomasko et al. 2005).
 *       Triton's 14 µbar of the same gas is far too thin and stays black.</li>
 *   <li><b>Sulphuric yellow-white</b>: thick CO₂ under H₂SO₄ cloud decks (Venera 13/14 colour work).</li>
 *   <li><b>Steam white</b>: water-vapour greenhouse worlds, overcast and bright.</li>
 *   <li><b>Black</b>: no atmosphere or a trace one; the sun and stars share the sky.</li>
 * </ul>
 * The classes are discrete because each maps to a datapack biome: a biome's sky colour is what
 * the vanilla renderer reads, and biomes cannot be created at runtime.
 */
public final class SurfaceAtmosphere {
    private SurfaceAtmosphere() {}

    /** Below this a thin atmosphere scatters too little light to tint the sky (≈ 1/6 of Mars). */
    public static final double MIN_SCATTERING_BAR = 1e-3;
    /** Haze needs a column like Titan's (1.45 bar) or at least a tenth of Earth's. */
    public static final double MIN_HAZE_BAR = 0.1;

    public enum SkyClass {
        BLUE(0x78A7FF, 0xC0D8FF, 1.0),
        BUTTERSCOTCH(0xC9A37A, 0xD9B48F, 0.55),
        ORANGE(0xD2873A, 0xC0782F, 0.9),
        YELLOW(0xE6D8A2, 0xE9DDB0, 1.0),
        STEAM(0xD9DCDF, 0xE4E6E8, 1.0),
        BLACK(0x000000, 0x050505, 0.0);

        /** Daytime sky colour, 0xRRGGBB. */
        public final int sky;
        /** Horizon fog colour, 0xRRGGBB. */
        public final int fog;
        /** How strongly daylight in this sky washes out other bodies and the galaxy (0 = never). */
        public final double daylightOpacity;

        SkyClass(int sky, int fog, double daylightOpacity) {
            this.sky = sky;
            this.fog = fog;
            this.daylightOpacity = daylightOpacity;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static SkyClass skyClass(Body b) {
        double p = b.surfacePressureBar();
        return switch (b.atmosphere()) {
            case N2_O2 -> p >= MIN_HAZE_BAR ? SkyClass.BLUE : p >= MIN_SCATTERING_BAR ? SkyClass.BUTTERSCOTCH : SkyClass.BLACK;
            case N2_CH4 -> p >= MIN_HAZE_BAR ? SkyClass.ORANGE : SkyClass.BLACK;
            case CO2_THICK -> SkyClass.YELLOW;
            case H2O_STEAM -> SkyClass.STEAM;
            case TRACE_CO2 -> p >= MIN_SCATTERING_BAR ? SkyClass.BUTTERSCOTCH : SkyClass.BLACK;
            case H2_HE -> SkyClass.BLUE; // giants are not landable; a cloud-top view would be pale blue
            case NONE -> SkyClass.BLACK;
        };
    }

    /** Daytime sky colour of a body, 0xRRGGBB. */
    public static int skyColour(Body b) {
        return skyClass(b).sky;
    }

    public static int fogColour(Body b) {
        return skyClass(b).fog;
    }

    /** Datapack biome path for a body: {@code planet_<type>_<sky>}. */
    public static String biomePath(Body b) {
        return biomePath(b.type(), skyClass(b));
    }

    public static String biomePath(dev.galactica.system.BodyType type, SkyClass sky) {
        return "planet_" + type.name().toLowerCase(java.util.Locale.ROOT) + "_" + sky.id();
    }

    /** True where meteoroids reach the ground unburnt, so the surface keeps its craters (Mars included). */
    public static boolean cratered(Body b) {
        return b.surfacePressureBar() < 0.01;
    }
}
