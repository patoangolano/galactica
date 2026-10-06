package dev.galactica.earthsky;

import dev.galactica.client.earthsky.BrightStars;
import dev.galactica.client.earthsky.EquatorialFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The named list against the Yale Bright Star Catalogue (CDS V/50) it is merged with. */
class BrightStarsTest {

    /** Every star of V ≤ 2.5 by name (Yale BSC), including the two once missing: Regor and η Cen. */
    private static final String[] V_LE_2_5 = {
            "Sirius", "Canopus", "Rigil Kentaurus", "Arcturus", "Vega", "Capella", "Rigel", "Procyon", "Achernar",
            "Betelgeuse", "Hadar", "Altair", "Acrux", "Aldebaran", "Antares", "Spica", "Pollux", "Fomalhaut", "Deneb",
            "Mimosa", "Regulus", "Adhara", "Castor", "Shaula", "Gacrux", "Bellatrix", "Elnath", "Miaplacidus", "Alnilam",
            "Regor", "Alnair", "Alnitak", "Alioth", "Dubhe", "Mirfak", "Wezen", "Kaus Australis", "Sargas", "Avior",
            "Alkaid", "Menkalinan", "Atria", "Alhena", "Peacock", "Alsephina", "Mirzam", "Alphard", "Polaris", "Hamal",
            "Algieba", "Diphda", "Nunki", "Menkent", "Saiph", "Alpheratz", "Mirach", "Rasalhague", "Kochab", "Algol",
            "Almach", "Denebola", "Tiaki", "Muhlifain", "Naos", "Aspidiske", "Alphecca", "Suhail", "Mizar", "Sadr",
            "Mintaka", "Schedar", "Eltanin", "Caph", "Dschubba", "Larawag", "Alpha Lupi", "Epsilon Centauri",
            "Eta Centauri", "Merak", "Izar", "Enif", "Girtab", "Ankaa", "Scheat", "Sabik", "Phecda", "Aludra",
            "Alderamin", "Markeb", "Navi", "Markab", "Aljanah"};

    /**
     * Close doubles whose named V is the modern combined light while the Yale table here (cut at
     * V 4.5) lacks the faint component or uses older photometry: ε Boo (Yale A 2.70, B 5.12;
     * Hipparcos combined 2.37), γ Leo (Yale 2.61 + 3.80 = 2.30; Hipparcos 2.01–2.08).
     */
    private static final java.util.Set<String> COMBINED_LIGHT = java.util.Set.of("Izar", "Algieba");

    @Test
    void everyKnownStarBrighterThan2_5IsNamed() {
        for (String n : V_LE_2_5) assertNotNull(BrightStars.byName(n), n + " missing");
    }

    /** Combined V of every Yale entry within {@code deg} of a direction (close doubles add their light). */
    private static double yaleCombinedMag(double ra, double dec, double deg) {
        double[] d = EquatorialFrame.equatorial(ra, dec);
        double cos = Math.cos(Math.toRadians(deg)), flux = 0;
        for (BrightStars.Entry y : BrightStars.yale()) {
            double[] e = EquatorialFrame.equatorial(y.raDeg(), y.decDeg());
            if (d[0] * e[0] + d[1] * e[1] + d[2] * e[2] > cos) flux += Math.pow(10, -0.4 * y.vmag());
        }
        return flux == 0 ? Double.NaN : -2.5 * Math.log10(flux);
    }

    /** The hand-typed named list agrees with the machine-read catalogue: position and magnitude. */
    @Test
    void namedStarsMatchTheYaleCatalogue() {
        List<String> bad = new ArrayList<>();
        for (BrightStars.Entry e : BrightStars.all()) {
            double near = yaleCombinedMag(e.raDeg(), e.decDeg(), 0.02); // 72″: position check
            double comb = yaleCombinedMag(e.raDeg(), e.decDeg(), BrightStars.SAME_STAR_DEG);
            double tol = COMBINED_LIGHT.contains(e.name()) ? 0.35 : 0.2;
            if (Double.isNaN(near)) bad.add(e.name() + ": no Yale star within 0.02°");
            else if (Math.abs(comb - e.vmag()) > tol) bad.add(e.name() + ": V " + e.vmag() + " vs Yale " + comb);
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** No Yale star (or close pair) of V ≤ 2.5 lacks a named entry: the named list is complete. */
    @Test
    void namedListIsCompleteTo2_5() {
        List<String> missing = new ArrayList<>();
        for (BrightStars.Entry y : BrightStars.yale()) {
            if (yaleCombinedMag(y.raDeg(), y.decDeg(), BrightStars.SAME_STAR_DEG) > BrightStars.COMPLETE_TO_MAG) continue;
            double[] d = EquatorialFrame.equatorial(y.raDeg(), y.decDeg());
            boolean named = BrightStars.all().stream().anyMatch(e -> {
                double[] n = EquatorialFrame.equatorial(e.raDeg(), e.decDeg());
                return n[0] * d[0] + n[1] * d[1] + n[2] * d[2] > Math.cos(Math.toRadians(BrightStars.SAME_STAR_DEG));
            });
            if (!named) missing.add(y.name() + " V " + y.vmag());
        }
        assertTrue(missing.isEmpty(), "unnamed bright stars: " + missing);
    }

    @Test
    void catalogueIsYaleToV4_5WithNamedStarsSubstituted() {
        List<BrightStars.Entry> c = BrightStars.catalogue();
        // The Yale BSC holds ~900 stars of V ≤ 4.5; merging close doubles into named entries removes a few.
        assertTrue(c.size() > 860 && c.size() <= 903 + BrightStars.all().size(), "n = " + c.size());
        assertEquals("Sirius", c.get(0).name());
        for (BrightStars.Entry e : c) assertTrue(e.vmag() <= BrightStars.CATALOGUE_LIMIT_MAG + 1e-9, e.name());
        // T CrB (HR 5958) is listed by the BSC at its 1866 nova peak; it must not shine at V 2.
        assertTrue(c.stream().noneMatch(e -> e.name().equals("HR 5958")));
        // μ¹ and μ² Sco (6′ apart) are both there.
        assertTrue(c.stream().anyMatch(e -> e.name().equals("HR 6252")), "mu2 Sco");
        // Orion's belt appears once each, under its proper names.
        assertEquals(1, c.stream().filter(e -> e.name().equals("Alnilam")).count());
        assertTrue(c.stream().noneMatch(e -> e.name().startsWith("HR ") && Math.abs(e.raDeg() - 84.0533) < 0.01 && Math.abs(e.decDeg() + 1.2019) < 0.01));
        // Missing B−V falls back to a Sun-like temperature instead of NaN.
        assertEquals(5800, new BrightStars.Entry("x", 0, 0, 4, Double.NaN).temperature(), 200);
    }
}
