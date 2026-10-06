package dev.galactica.discovery;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.spectra.Absorber;
import dev.galactica.science.spectra.Kinematics;
import dev.galactica.science.spectra.PlanetSpectrum;
import dev.galactica.science.spectra.Spectrum;
import dev.galactica.science.spectra.StellarSpectrum;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import dev.galactica.system.Orbit;
import dev.galactica.system.SolarSystem;
import dev.galactica.science.Units;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpectraTest {

    private static Star star(double teff, StarKind kind) {
        return new Star(42, "Test", 8000, 0, 0, kind, 1, 1, 1, teff, 1, 0, 7, false);
    }

    private static double depth(Star s, String label) {
        double d = 0;
        for (Absorber a : StellarSpectrum.absorbers(s)) if (a.label().equals(label)) d = Math.max(d, a.depth());
        return d;
    }

    @Test
    void continuumPeakFollowsWien() {
        Spectrum s = Spectrum.build(l -> Spectrum.planck(l, 5772), List.of(), 0);
        assertEquals(2.897771955e6 / 5772, s.peakNm(), 2.0);
        Spectrum m = Spectrum.build(l -> Spectrum.planck(l, 3000), List.of(), 0);
        assertEquals(2.897771955e6 / 3000, m.peakNm(), 2.0);
    }

    @Test
    void balmerPeaksAtA0() {
        double a0 = depth(star(9_500, StarKind.MAIN_SEQUENCE), "Hα");
        double g2 = depth(star(5_772, StarKind.MAIN_SEQUENCE), "Hα");
        double o5 = depth(star(40_000, StarKind.MAIN_SEQUENCE), "Hα");
        double m5 = depth(star(3_100, StarKind.MAIN_SEQUENCE), "Hα");
        assertTrue(a0 > g2 && g2 > m5, "A0 > G2 > M5 in Balmer strength");
        assertTrue(a0 > o5, "Balmer weaker in O stars");
        assertTrue(m5 < 0.05, "no Balmer in M dwarfs: " + m5);
    }

    @Test
    void classDiagnosticsAppearWhereTheyShould() {
        assertTrue(depth(star(3_100, StarKind.MAIN_SEQUENCE), "TiO") > 0.5, "TiO defines M");
        assertEquals(0, depth(star(5_772, StarKind.MAIN_SEQUENCE), "TiO"));
        assertTrue(depth(star(20_000, StarKind.MAIN_SEQUENCE), "He I") > 0.3, "He I peaks at B2");
        assertEquals(0, depth(star(5_772, StarKind.MAIN_SEQUENCE), "He I"));
        assertTrue(depth(star(40_000, StarKind.MAIN_SEQUENCE), "He II") > 0.2, "He II in O stars");
        assertEquals(0, depth(star(20_000, StarKind.MAIN_SEQUENCE), "He II"));
        assertTrue(depth(star(5_000, StarKind.MAIN_SEQUENCE), "Ca II K") > depth(star(9_500, StarKind.MAIN_SEQUENCE), "Ca II K"),
                "Ca II K grows from A to G/K");
        assertTrue(depth(star(3_100, StarKind.MAIN_SEQUENCE), "FeH") > 0, "Wing-Ford band in M dwarfs");
        assertEquals(0, depth(star(3_400, StarKind.GIANT), "FeH"), "but not in M giants");
    }

    @Test
    void remnantsAndBlackHoles() {
        Star da = star(20_000, StarKind.WHITE_DWARF);
        assertTrue(depth(da, "Hβ") > 0.4, "DA white dwarfs show broad Balmer lines");
        assertTrue(StellarSpectrum.absorbers(star(8_000, StarKind.WHITE_DWARF)).isEmpty(), "DC are featureless");
        Star bh = new Star(1, "BH", 0, 0, 0, StarKind.BLACK_HOLE, 10, 0, 0, 0, 1, 0, 1, false);
        assertTrue(StellarSpectrum.of(bh, 0).dark());
    }

    @Test
    void metalLinesScaleWithMetallicity() {
        Star rich = new Star(1, "a", 8000, 0, 0, StarKind.MAIN_SEQUENCE, 1, 1, 1, 5000, 1, 0.4, 1, false);
        Star poor = new Star(1, "b", 8000, 0, 0, StarKind.MAIN_SEQUENCE, 1, 1, 1, 5000, 1, -1.5, 1, false);
        assertTrue(depth(rich, "Fe I") > depth(poor, "Fe I") * 1.5);
    }

    @Test
    void dopplerShiftMovesLines() {
        Star a0 = star(9_500, StarKind.MAIN_SEQUENCE);
        Spectrum rest = StellarSpectrum.of(a0, 0), moving = StellarSpectrum.of(a0, 300);
        double h0 = rest.lines().stream().filter(m -> m.label().equals("Hα")).findFirst().orElseThrow().observedNm();
        double h1 = moving.lines().stream().filter(m -> m.label().equals("Hα")).findFirst().orElseThrow().observedNm();
        assertEquals(656.28, h0, 1e-9);
        assertEquals(656.28 * 300.0 / 299_792.458, h1 - h0, 1e-3, "Δλ/λ = v/c to first order");
        // The flux minimum sits at the shifted line.
        assertTrue(moving.fluxAt(h1) < moving.fluxAt(h0 - 0.66));
        assertEquals(Math.sqrt((1 + 0.1) / (1 - 0.1)), Kinematics.dopplerFactor(0.1 * Units.C / 1000), 1e-12);
    }

    @Test
    void earthShowsABiosignatureAndMarsDoesNot() {
        Body earth = SolarSystem.planets().get(2);
        PlanetSpectrum.Result r = PlanetSpectrum.of(StarCatalog.SOL, earth, earth.orbit().semiMajorAxis(), 0);
        assertTrue(r.gases().containsAll(List.of(PlanetSpectrum.O2, PlanetSpectrum.H2O, PlanetSpectrum.CO2, PlanetSpectrum.CH4)), r.gases().toString());
        assertTrue(r.biosignature());
        Spectrum s = r.spectrum();
        assertTrue(s.fluxAt(760.5) < 0.8 * s.fluxAt(752), "O2 A-band is a deep dip");
        assertTrue(s.fluxAt(800) > s.fluxAt(690), "vegetation red edge brightens the near-IR");

        Body mars = SolarSystem.planets().get(3);
        PlanetSpectrum.Result m = PlanetSpectrum.of(StarCatalog.SOL, mars, mars.orbit().semiMajorAxis(), 0);
        assertEquals(List.of(PlanetSpectrum.CO2), m.gases());
        assertFalse(m.biosignature());
        assertTrue(m.spectrum().fluxAt(400) < m.spectrum().fluxAt(700), "Mars is red");
    }

    @Test
    void titanVenusAndJupiter() {
        Body saturn = SolarSystem.planets().get(5);
        Body titan = saturn.moons().get(1);
        PlanetSpectrum.Result t = PlanetSpectrum.of(StarCatalog.SOL, titan, saturn.orbit().semiMajorAxis(), 0);
        assertTrue(t.gases().contains(PlanetSpectrum.CH4));
        assertFalse(t.biosignature(), "methane alone is not a biosignature");
        assertTrue(PlanetSpectrum.gases(SolarSystem.planets().get(1)).contains(PlanetSpectrum.CO2), "Venus");
        assertTrue(PlanetSpectrum.gases(SolarSystem.planets().get(4)).contains(PlanetSpectrum.CH4), "Jupiter");
    }

    @Test
    void hotJupiterGlowsInTheInfraredAndShowsAlkalis() {
        Orbit o = new Orbit(0.04 * Units.AU, 0, 0, 0, 0, 0, Units.SOLAR_MASS);
        Body hot = new Body(1, "hot", BodyType.HOT_JUPITER, o, 300, 13, 0.1, 1500, 1500, 1, Body.Atmosphere.H2_HE,
                80, 0, true, false, Body.Life.NONE, 1, List.of());
        PlanetSpectrum.Result r = PlanetSpectrum.of(StarCatalog.SOL, hot, o.semiMajorAxis(), 0);
        assertTrue(r.gases().contains(PlanetSpectrum.NA) && r.gases().contains(PlanetSpectrum.K));
        assertFalse(r.gases().contains(PlanetSpectrum.CH4), "CH4 is converted to CO in hot envelopes");
        // Thermal emission at 1500 K dominates the K band; a 300 K twin is pure reflected starlight there.
        Body cold = new Body(1, "cold", BodyType.HOT_JUPITER, o, 300, 13, 0.1, 300, 300, 1, Body.Atmosphere.H2_HE,
                80, 0, true, false, Body.Life.NONE, 1, List.of());
        Spectrum h = r.spectrum(), c = PlanetSpectrum.of(StarCatalog.SOL, cold, o.semiMajorAxis(), 0).spectrum();
        double hotRatio = h.fluxAt(2200) / h.fluxAt(600), coldRatio = c.fluxAt(2200) / c.fluxAt(600);
        assertTrue(hotRatio > 5 * coldRatio, hotRatio + " vs " + coldRatio);
    }
}
