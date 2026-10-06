package dev.galactica.galaxy;

import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.List;

/**
 * Real stars layered over the procedural galaxy. Positions come from galactic longitude/latitude
 * and distance (Gaia DR3 / SIMBAD), converted to the galactocentric frame of {@link GalaxyModel}.
 * Ids are small negative numbers so they never collide with procedural hashes.
 */
public final class StarCatalog {
    private StarCatalog() {}

    public static final Star SOL = make(-1, "Sol", 0, 0, 0, StarKind.MAIN_SEQUENCE, 1.0, 1.0, 1.0, 5772, 4.6, 0.0);
    public static final SectorKey SOL_SECTOR = SectorKey.containing(SOL.x(), SOL.y(), SOL.z());

    /** Sgr A*: 4.297 × 10^6 M☉ (GRAVITY 2023), at the dynamical centre. */
    public static final Star SGR_A_STAR = new Star(-2, "Sagittarius A*", 0, 0, 0, StarKind.BLACK_HOLE,
            4.297e6, 4.297e6 * 4.2453e-6, 0, 0, 13.0, 0.0, 0x5A6A_57A5L, true);

    private static final List<Star> ALL = List.of(
            SOL,
            SGR_A_STAR,
            //            id   name                    l°       b°      d(pc)   kind                     M      R      L        Teff   age   [Fe/H]
            make(-3, "Alpha Centauri A", 315.73, -0.68, 1.3474, StarKind.MAIN_SEQUENCE, 1.079, 1.2175, 1.519, 5790, 5.3, 0.20),
            make(-4, "Alpha Centauri B", 315.73, -0.68, 1.3474, StarKind.MAIN_SEQUENCE, 0.909, 0.8591, 0.5002, 5260, 5.3, 0.23),
            make(-5, "Proxima Centauri", 313.94, -1.93, 1.3020, StarKind.MAIN_SEQUENCE, 0.122, 0.1542, 0.00155, 2992, 4.85, 0.21),
            make(-6, "Barnard's Star", 31.01, 14.06, 1.8282, StarKind.MAIN_SEQUENCE, 0.162, 0.187, 0.0035, 3195, 10.0, -0.40),
            make(-7, "Wolf 359", 244.06, 56.12, 2.4086, StarKind.MAIN_SEQUENCE, 0.110, 0.144, 0.00106, 2749, 0.4, 0.0),
            make(-8, "Lalande 21185", 185.13, 65.43, 2.5469, StarKind.MAIN_SEQUENCE, 0.390, 0.392, 0.0195, 3547, 8.0, -0.20),
            make(-9, "Sirius A", 227.23, -8.89, 2.6371, StarKind.MAIN_SEQUENCE, 2.063, 1.711, 25.4, 9940, 0.242, 0.50),
            make(-10, "Sirius B", 227.23, -8.89, 2.6371, StarKind.WHITE_DWARF, 1.018, 0.0084, 0.056, 25000, 0.242, 0.0),
            make(-11, "Epsilon Eridani", 195.84, -48.05, 3.2198, StarKind.MAIN_SEQUENCE, 0.82, 0.735, 0.34, 5084, 0.6, -0.13),
            make(-12, "Tau Ceti", 173.10, -73.44, 3.6519, StarKind.MAIN_SEQUENCE, 0.783, 0.793, 0.52, 5344, 5.8, -0.55),
            make(-13, "Procyon A", 213.70, 13.02, 3.5142, StarKind.MAIN_SEQUENCE, 1.499, 2.048, 6.93, 6530, 1.87, -0.05),
            make(-14, "Altair", 47.74, -8.91, 5.1295, StarKind.MAIN_SEQUENCE, 1.86, 1.79, 10.6, 7670, 0.1, -0.2),
            make(-15, "Vega", 67.45, 19.24, 7.68, StarKind.MAIN_SEQUENCE, 2.135, 2.36, 40.1, 9602, 0.455, -0.5),
            make(-16, "Fomalhaut", 20.49, -64.91, 7.70, StarKind.MAIN_SEQUENCE, 1.92, 1.84, 16.6, 8590, 0.44, -0.03),
            make(-17, "TRAPPIST-1", 69.71, -56.64, 12.43, StarKind.MAIN_SEQUENCE, 0.0898, 0.1192, 0.000553, 2566, 7.6, 0.04),
            make(-18, "Arcturus", 15.38, 69.11, 11.26, StarKind.GIANT, 1.08, 25.4, 170.0, 4286, 7.1, -0.52),
            make(-19, "Polaris", 123.28, 26.46, 133.0, StarKind.SUPERGIANT, 5.4, 37.5, 1260.0, 6015, 0.07, 0.07),
            make(-20, "Betelgeuse", 199.79, -8.96, 168.0, StarKind.SUPERGIANT, 18.0, 764.0, 126000.0, 3600, 0.008, 0.05),
            make(-21, "Rigel", 209.24, -25.25, 260.0, StarKind.SUPERGIANT, 21.0, 78.9, 120000.0, 12100, 0.008, -0.06),
            make(-22, "Deneb", 84.28, 1.99, 802.0, StarKind.SUPERGIANT, 19.0, 203.0, 196000.0, 8525, 0.01, -0.2),
            make(-23, "Antares", 351.95, 15.06, 170.0, StarKind.SUPERGIANT, 13.5, 680.0, 75900.0, 3660, 0.012, 0.1),
            make(-24, "Eta Carinae", 287.60, -0.63, 2300.0, StarKind.SUPERGIANT, 100.0, 240.0, 4.6e6, 9400, 0.003, 0.0),
            make(-25, "Cygnus X-1", 71.33, 3.07, 2220.0, StarKind.BLACK_HOLE, 21.2, 0, 0, 0, 0.005, 0.0));

    public static List<Star> all() {
        return ALL;
    }

    public static Star byName(String name) {
        for (Star s : ALL) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }

    public static int countInSector(SectorKey key) {
        int n = 0;
        for (Star s : ALL) if (SectorKey.containing(s.x(), s.y(), s.z()).equals(key)) n++;
        return n;
    }

    /**
     * Heliocentric galactic coordinates → galactocentric frame. x_h points to the GC, y_h to
     * l = 90° (direction of rotation), z_h to the NGP. In {@link GalaxyModel} the GC is at the origin,
     * the Sun on +x, and rotation at the Sun is towards −y, so x = R0 − x_h, y = −y_h, z = Z0 + z_h.
     */
    public static double[] galacticToGalactocentric(double lDeg, double bDeg, double dPc) {
        double l = Math.toRadians(lDeg), b = Math.toRadians(bDeg);
        double xh = dPc * Math.cos(b) * Math.cos(l);
        double yh = dPc * Math.cos(b) * Math.sin(l);
        double zh = dPc * Math.sin(b);
        return new double[] {GalaxyModel.R0 - xh, -yh, GalaxyModel.Z0 + zh};
    }

    private static Star make(long id, String name, double l, double b, double d, StarKind kind,
                             double m, double r, double lum, double teff, double age, double feh) {
        double[] p = galacticToGalactocentric(l, b, d);
        double radius = kind == StarKind.BLACK_HOLE ? m * 4.2453e-6 : r;
        return new Star(id, name, p[0], p[1], p[2], kind, m, radius, lum, teff, age, feh,
                dev.galactica.science.SeedRng.hash(0xCA7A_10C0L, id), true);
    }
}
