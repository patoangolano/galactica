package dev.galactica.client.earthsky;

import java.util.List;

/**
 * The bright naked-eye stars, from real catalogues, so the Overworld's constellations are the real
 * ones. Every star of V ≤ 2.5 is listed, plus the fainter members that complete the familiar
 * figures (Orion, the Plough, Cassiopeia, Crux, Scorpius, the Teapot, Leo, Lyra, Cygnus, the
 * Pleiades).
 *
 * <p>Positions are ICRS / J2000.0 from the Hipparcos catalogue (ESA 1997; van Leeuwen 2007,
 * A&amp;A 474, 653), rounded to 0.1 s / 1″. V magnitudes and B−V colours are from the Yale Bright
 * Star Catalogue, 5th ed. (Hoffleit &amp; Warren 1991); combined light is used for unresolved
 * doubles (α Cen, Mizar, γ Leo) and the mean for variables (Betelgeuse, γ Cas).
 *
 * <p>{@link #catalogue()} merges this named list with every star of the Yale Bright Star Catalogue
 * down to {@link #CATALOGUE_LIMIT_MAG} ({@link YaleBrightStars}); the named entries win where both
 * have a star (they carry combined light for close doubles). The procedural galaxy also produces
 * stars around the Sun; {@link EarthSkyBake} drops the procedural ones brighter than
 * {@link #CATALOGUE_LIMIT_MAG}, so every star a naked eye picks out first is a real one at its real
 * place.
 */
public final class BrightStars {
    private BrightStars() {}

    /** The named list is complete down to this V magnitude. */
    public static final double COMPLETE_TO_MAG = 2.5;
    /** {@link #catalogue()} is complete (Yale BSC) down to this V magnitude. */
    public static final double CATALOGUE_LIMIT_MAG = 4.5;
    /**
     * Yale entries closer than this to a named star are that star or an unresolved component of it,
     * degrees. 3′ is above the naked eye's ~1–2′ resolution yet below naked-eye pairs such as
     * μ¹/μ² Sco (6′) or δ Cen and its 4.5 mag neighbour (5′), which stay separate stars.
     */
    public static final double SAME_STAR_DEG = 0.05;
    /** B−V assumed when the catalogue has none (a Sun-like colour). */
    private static final double DEFAULT_BV = 0.65;

    /** One catalogue star: J2000 right ascension and declination in degrees, V magnitude, B−V. */
    public record Entry(String name, double raDeg, double decDeg, double vmag, double bv) {
        /**
         * Effective temperature from B−V, Ballesteros (2012, EPL 97, 34008):
         * T = 4600 K · (1/(0.92(B−V) + 1.7) + 1/(0.92(B−V) + 0.62)).
         */
        public double temperature() {
            double bv = Double.isNaN(this.bv) ? DEFAULT_BV : this.bv;
            return 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62));
        }
    }

    private static final List<Entry> ALL = List.of(
            //  name                    RA (h m s)      Dec (° ′ ″)     V      B−V
            s("Sirius", "06 45 08.9", "-16 42 58", -1.46, 0.00),
            s("Canopus", "06 23 57.1", "-52 41 45", -0.74, 0.15),
            s("Rigil Kentaurus", "14 39 36.5", "-60 50 02", -0.27, 0.71),
            s("Arcturus", "14 15 39.7", "+19 10 57", -0.05, 1.23),
            s("Vega", "18 36 56.3", "+38 47 01", 0.03, 0.00),
            s("Capella", "05 16 41.4", "+45 59 53", 0.08, 0.80),
            s("Rigel", "05 14 32.3", "-08 12 06", 0.13, -0.03),
            s("Procyon", "07 39 18.1", "+05 13 30", 0.34, 0.42),
            s("Achernar", "01 37 42.8", "-57 14 12", 0.46, -0.16),
            s("Betelgeuse", "05 55 10.3", "+07 24 25", 0.50, 1.85),
            s("Hadar", "14 03 49.4", "-60 22 23", 0.61, -0.23),
            s("Altair", "19 50 47.0", "+08 52 06", 0.76, 0.22),
            s("Acrux", "12 26 35.9", "-63 05 57", 0.76, -0.24),
            s("Aldebaran", "04 35 55.2", "+16 30 33", 0.86, 1.54),
            s("Antares", "16 29 24.5", "-26 25 55", 0.96, 1.83),
            s("Spica", "13 25 11.6", "-11 09 41", 0.97, -0.23),
            s("Pollux", "07 45 18.9", "+28 01 34", 1.14, 1.00),
            s("Fomalhaut", "22 57 39.0", "-29 37 20", 1.16, 0.09),
            s("Deneb", "20 41 25.9", "+45 16 49", 1.25, 0.09),
            s("Mimosa", "12 47 43.3", "-59 41 19", 1.25, -0.23),
            s("Regulus", "10 08 22.3", "+11 58 02", 1.40, -0.11),
            s("Adhara", "06 58 37.5", "-28 58 20", 1.50, -0.21),
            s("Castor", "07 34 36.0", "+31 53 18", 1.58, 0.03),
            s("Shaula", "17 33 36.5", "-37 06 14", 1.62, -0.22),
            s("Gacrux", "12 31 10.0", "-57 06 48", 1.63, 1.60),
            s("Bellatrix", "05 25 07.9", "+06 20 59", 1.64, -0.22),
            s("Elnath", "05 26 17.5", "+28 36 27", 1.65, -0.13),
            s("Miaplacidus", "09 13 12.0", "-69 43 02", 1.67, 0.07),
            s("Alnilam", "05 36 12.8", "-01 12 07", 1.69, -0.18),
            s("Alnair", "22 08 14.0", "-46 57 40", 1.74, -0.13),
            s("Regor", "08 09 31.9", "-47 20 12", 1.78, -0.22),
            s("Alnitak", "05 40 45.5", "-01 56 34", 1.77, -0.21),
            s("Alioth", "12 54 01.7", "+55 57 35", 1.77, -0.02),
            s("Dubhe", "11 03 43.7", "+61 45 03", 1.79, 1.07),
            s("Mirfak", "03 24 19.4", "+49 51 40", 1.79, 0.48),
            s("Wezen", "07 08 23.5", "-26 23 36", 1.84, 0.68),
            s("Kaus Australis", "18 24 10.3", "-34 23 05", 1.85, -0.03),
            s("Sargas", "17 37 19.1", "-42 59 52", 1.86, 0.40),
            s("Avior", "08 22 30.8", "-59 30 34", 1.86, 1.28),
            s("Alkaid", "13 47 32.4", "+49 18 48", 1.86, -0.19),
            s("Menkalinan", "05 59 31.7", "+44 56 51", 1.90, 0.08),
            s("Atria", "16 48 39.9", "-69 01 40", 1.91, 1.45),
            s("Alhena", "06 37 42.7", "+16 23 57", 1.92, 0.00),
            s("Peacock", "20 25 38.9", "-56 44 06", 1.94, -0.20),
            s("Alsephina", "08 44 42.2", "-54 42 30", 1.96, 0.04),
            s("Mirzam", "06 22 42.0", "-17 57 21", 1.98, -0.24),
            s("Alphard", "09 27 35.2", "-08 39 31", 1.98, 1.44),
            s("Polaris", "02 31 49.1", "+89 15 51", 1.98, 0.60),
            s("Hamal", "02 07 10.4", "+23 27 45", 2.00, 1.15),
            s("Algieba", "10 19 58.4", "+19 50 29", 2.01, 1.13),
            s("Diphda", "00 43 35.4", "-17 59 12", 2.04, 1.02),
            s("Nunki", "18 55 15.9", "-26 17 48", 2.05, -0.13),
            s("Menkent", "14 06 41.0", "-36 22 12", 2.06, 1.01),
            s("Saiph", "05 47 45.4", "-09 40 11", 2.07, -0.17),
            s("Alpheratz", "00 08 23.3", "+29 05 26", 2.07, -0.11),
            s("Mirach", "01 09 43.9", "+35 37 14", 2.07, 1.58),
            s("Rasalhague", "17 34 56.1", "+12 33 36", 2.08, 0.15),
            s("Kochab", "14 50 42.3", "+74 09 20", 2.08, 1.47),
            s("Algol", "03 08 10.1", "+40 57 20", 2.09, -0.05),
            s("Almach", "02 03 54.0", "+42 19 47", 2.10, 1.37),
            s("Denebola", "11 49 03.6", "+14 34 19", 2.14, 0.09),
            s("Tiaki", "22 42 40.1", "-46 53 05", 2.15, 1.60),
            s("Muhlifain", "12 41 31.0", "-48 57 35", 2.17, -0.01),
            s("Naos", "08 03 35.0", "-40 00 12", 2.21, -0.27),
            s("Aspidiske", "09 17 05.4", "-59 16 31", 2.21, 0.18),
            s("Alphecca", "15 34 41.3", "+26 42 53", 2.22, -0.02),
            s("Suhail", "09 07 59.8", "-43 25 57", 2.23, 1.66),
            s("Mizar", "13 23 55.5", "+54 55 31", 2.23, 0.02),
            s("Sadr", "20 22 13.7", "+40 15 24", 2.23, 0.67),
            s("Mintaka", "05 32 00.4", "-00 17 57", 2.23, -0.22),
            s("Schedar", "00 40 30.4", "+56 32 14", 2.24, 1.17),
            s("Eltanin", "17 56 36.4", "+51 29 20", 2.24, 1.52),
            s("Caph", "00 09 10.7", "+59 08 59", 2.28, 0.34),
            s("Dschubba", "16 00 20.0", "-22 37 18", 2.29, -0.12),
            s("Larawag", "16 50 09.8", "-34 17 36", 2.29, 1.15),
            s("Alpha Lupi", "14 41 55.8", "-47 23 17", 2.30, -0.15),
            s("Epsilon Centauri", "13 39 53.3", "-53 27 59", 2.30, -0.17),
            s("Eta Centauri", "14 35 30.4", "-42 09 28", 2.31, -0.19),
            s("Merak", "11 01 50.5", "+56 22 57", 2.37, -0.02),
            s("Izar", "14 44 59.2", "+27 04 27", 2.37, 0.97),
            s("Enif", "21 44 11.2", "+09 52 30", 2.39, 1.53),
            s("Girtab", "17 42 29.3", "-39 01 48", 2.39, -0.22),
            s("Ankaa", "00 26 17.0", "-42 18 22", 2.40, 1.09),
            s("Scheat", "23 03 46.5", "+28 04 58", 2.42, 1.67),
            s("Sabik", "17 10 22.7", "-15 43 29", 2.43, 0.06),
            s("Phecda", "11 53 49.8", "+53 41 41", 2.44, 0.04),
            s("Aludra", "07 24 05.7", "-29 18 11", 2.45, -0.08),
            s("Alderamin", "21 18 34.8", "+62 35 08", 2.45, 0.22),
            s("Markeb", "09 22 06.8", "-55 00 39", 2.47, -0.14),
            s("Navi", "00 56 42.5", "+60 43 00", 2.47, -0.15),
            s("Markab", "23 04 45.7", "+15 12 19", 2.48, -0.04),
            s("Aljanah", "20 46 12.7", "+33 58 13", 2.48, 1.03),
            // Fainter stars that complete the well-known figures.
            s("Acrab", "16 05 26.2", "-19 48 19", 2.62, -0.07),
            s("Arneb", "05 32 43.8", "-17 49 20", 2.58, 0.21),
            s("Ascella", "19 02 36.7", "-29 52 49", 2.60, 0.08),
            s("Zosma", "11 14 06.5", "+20 31 25", 2.56, 0.12),
            s("Ruchbah", "01 25 49.0", "+60 14 07", 2.68, 0.13),
            s("Kaus Media", "18 20 59.6", "-29 49 41", 2.70, 1.38),
            s("Lesath", "17 30 45.8", "-37 17 45", 2.70, -0.22),
            s("Nair al Saif", "05 35 26.0", "-05 54 36", 2.77, -0.24),
            s("Imai", "12 15 08.7", "-58 44 56", 2.79, -0.23),
            s("Kaus Borealis", "18 27 58.2", "-25 25 18", 2.81, 1.04),
            s("Paikauhale", "16 35 53.0", "-28 12 58", 2.82, -0.25),
            s("Fang", "15 58 51.1", "-26 06 51", 2.89, -0.19),
            s("Al Niyat", "16 21 11.3", "-25 35 34", 2.89, 0.13),
            s("Alcyone", "03 47 29.1", "+24 06 18", 2.87, -0.09),
            s("Fawaris", "19 44 58.5", "+45 07 51", 2.87, -0.03),
            s("Alnasl", "18 05 48.5", "-30 25 27", 2.99, 1.00),
            s("Algenubi", "09 45 51.1", "+23 46 27", 2.98, 0.81),
            s("Iota1 Scorpii", "17 47 35.1", "-40 07 37", 3.03, 0.51),
            s("Pherkad", "15 20 43.7", "+71 50 02", 3.05, 0.05),
            s("Albireo", "19 30 43.3", "+27 57 35", 3.08, 1.13),
            s("Xamidimura", "16 51 52.2", "-38 02 51", 3.08, -0.20),
            s("Phi Sagittarii", "18 45 39.4", "-26 59 27", 3.17, -0.11),
            s("Tabit", "04 49 50.4", "+06 57 41", 3.19, 0.45),
            s("Sulafat", "18 58 56.6", "+32 41 22", 3.25, -0.05),
            s("Megrez", "12 15 25.6", "+57 01 57", 3.31, 0.08),
            s("Tau Sagittarii", "19 06 56.4", "-27 40 13", 3.32, 1.19),
            s("Eta Scorpii", "17 12 09.2", "-43 14 21", 3.33, 0.41),
            s("Chertan", "11 14 14.4", "+15 25 46", 3.33, -0.01),
            s("Segin", "01 54 23.7", "+63 40 12", 3.38, -0.15),
            s("Meissa", "05 35 08.3", "+09 56 03", 3.39, -0.18),
            s("Adhafera", "10 16 41.4", "+23 25 02", 3.44, 0.31),
            s("Eta Leonis", "10 07 20.0", "+16 45 45", 3.48, -0.03),
            s("Sheliak", "18 50 04.8", "+33 21 46", 3.52, 0.00),
            s("Ginan", "12 21 21.6", "-60 24 04", 3.59, 1.42),
            s("Atlas", "03 49 09.7", "+24 03 12", 3.62, -0.07),
            s("Zeta2 Scorpii", "16 54 35.0", "-42 21 41", 3.62, 1.37),
            s("Electra", "03 44 52.5", "+24 06 48", 3.70, -0.11),
            s("Maia", "03 45 49.6", "+24 22 04", 3.87, -0.07),
            s("Rasalas", "09 52 45.8", "+26 00 25", 3.88, 1.22),
            s("Merope", "03 46 19.6", "+23 56 54", 4.18, -0.06),
            s("Taygeta", "03 45 12.5", "+24 28 02", 4.30, -0.11));

    private static volatile List<Entry> catalogue;

    /** The named stars only. */
    public static List<Entry> all() {
        return ALL;
    }

    /** Every Yale BSC star of V ≤ {@link #CATALOGUE_LIMIT_MAG}, named stars substituted, brightest first. */
    public static List<Entry> catalogue() {
        List<Entry> c = catalogue;
        if (c == null) catalogue = c = buildCatalogue();
        return c;
    }

    /** The raw Yale rows (V ≤ {@link #CATALOGUE_LIMIT_MAG}) as entries named "HR n". */
    public static List<Entry> yale() {
        List<Entry> out = new java.util.ArrayList<>(YaleBrightStars.ROWS.length);
        for (String row : YaleBrightStars.ROWS) {
            String[] f = row.split(",", 6);
            double bv = f[4].isEmpty() ? Double.NaN : Double.parseDouble(f[4]);
            out.add(new Entry("HR " + f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), bv));
        }
        return out;
    }

    private static List<Entry> buildCatalogue() {
        List<Entry> out = new java.util.ArrayList<>(ALL);
        double[][] named = new double[ALL.size()][];
        for (int i = 0; i < named.length; i++) named[i] = EquatorialFrame.equatorial(ALL.get(i).raDeg(), ALL.get(i).decDeg());
        double cosLimit = Math.cos(Math.toRadians(SAME_STAR_DEG));
        for (Entry y : yale()) {
            double[] d = EquatorialFrame.equatorial(y.raDeg(), y.decDeg());
            boolean dup = false;
            for (double[] n : named) {
                if (n[0] * d[0] + n[1] * d[1] + n[2] * d[2] > cosLimit) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(y);
        }
        out.sort((a, b) -> Double.compare(a.vmag(), b.vmag()));
        return List.copyOf(out);
    }

    public static Entry byName(String name) {
        for (Entry e : ALL) if (e.name().equalsIgnoreCase(name)) return e;
        return null;
    }

    private static Entry s(String name, String ra, String dec, double v, double bv) {
        return new Entry(name, parseRa(ra), parseDec(dec), v, bv);
    }

    /** "hh mm ss.s" → degrees. */
    static double parseRa(String hms) {
        String[] p = hms.trim().split("\\s+");
        return 15.0 * (Double.parseDouble(p[0]) + Double.parseDouble(p[1]) / 60.0 + Double.parseDouble(p[2]) / 3600.0);
    }

    /** "±dd mm ss" → degrees; the sign is read from the text so "-00 17 57" stays negative. */
    static double parseDec(String dms) {
        String t = dms.trim();
        double sign = t.startsWith("-") ? -1 : 1;
        String[] p = t.replace("+", "").replace("-", "").split("\\s+");
        return sign * (Double.parseDouble(p[0]) + Double.parseDouble(p[1]) / 60.0 + Double.parseDouble(p[2]) / 3600.0);
    }
}
