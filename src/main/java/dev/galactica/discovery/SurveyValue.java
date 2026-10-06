package dev.galactica.discovery;

import dev.galactica.science.spectra.PlanetSpectrum;
import dev.galactica.star.SpectralClass;
import dev.galactica.star.Star;
import dev.galactica.system.Body;

/**
 * Survey points for first discoveries, weighted by rarity. The weights follow real number
 * fractions so that a point roughly measures "how unusual a find is": among stars near the Sun
 * M dwarfs are 76 %, K 12 %, G 7.6 %, F 3 %, A 0.6 %, B 0.13 %, O 0.00003 % (Ledrew 2001, JRASC 95,
 * 32, from the RECONS/Hipparcos census); white dwarfs ~6 %, giants ~0.4 %. Neutron stars and
 * stellar black holes are counted in the 10⁸ but almost never seen. Planets: rocky worlds are
 * common (η⊕ ≈ 0.1–0.4, Bryson et al. 2021), temperate terrestrial/ocean worlds less so, hot
 * Jupiters ~1 % of stars (Wright et al. 2012), and an O₂ + CH₄ biosignature is the prize.
 *
 * <p>A planet found only by a transit/RV survey earns {@link #surveyShare}; characterising it with
 * a direct spectrum later earns the rest.
 */
public final class SurveyValue {
    private SurveyValue() {}

    /** Survey points per unit of exotic matter (a 10 ly hyperjump costs 10 units = 200 points). */
    public static final int POINTS_PER_EXOTIC = 20;

    public static int starPoints(Star s) {
        return switch (s.kind()) {
            case BLACK_HOLE -> s.mass() > 1e5 ? 1000 : 200;
            case NEUTRON_STAR -> 120;
            case SUPERGIANT -> 80;
            case GIANT -> 30;
            case WHITE_DWARF -> 25;
            case MAIN_SEQUENCE -> switch (SpectralClass.letter(s.temperature())) {
                case 'O' -> 100;
                case 'B' -> 40;
                case 'A' -> 12;
                case 'F' -> 6;
                case 'G' -> 5;
                case 'K' -> 3;
                case 'L', 'T' -> 8;
                default -> 1;
            };
        };
    }

    public static int bodyPoints(Body b, boolean moon) {
        int base = switch (b.type()) {
            case BARREN -> 2;
            case DESERT, ICE -> 3;
            case LAVA, SUB_NEPTUNE, ICE_GIANT -> 4;
            case GREENHOUSE, GAS_GIANT -> 5;
            case HOT_JUPITER -> 10;
            case TERRAN, OCEAN -> 25;
        };
        if (b.rings()) base += 3;
        if (b.breathable()) base += 25;
        if (PlanetSpectrum.biosignature(b)) base += 100;
        return moon ? Math.max(1, base - 1) : base;
    }

    /** Points for a survey (transit/RV) detection: half the full value, rounded up. */
    public static int surveyShare(int fullPoints) {
        return (fullPoints + 1) / 2;
    }

    /** Units of exotic matter {@code points} can buy. */
    public static int affordableExotic(int points) {
        return Math.max(0, points) / POINTS_PER_EXOTIC;
    }
}
