package dev.galactica.galaxy;

import dev.galactica.science.SeedRng;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.star.StellarPhysics;

import java.util.ArrayList;
import java.util.List;

/**
 * The procedural Milky Way. Nothing is stored: a sector's star count is a Poisson draw on the
 * integrated {@link GalaxyModel} density, and star {@code i} of a sector is a pure function of
 * (galaxy seed, sector, i). ~10<sup>11</sup> stars exist, but only the handful near the player are
 * ever instantiated. Real catalog stars ({@link StarCatalog}) replace procedural ones near the Sun.
 */
public final class Galaxy {
    public static final double SECTOR_SIZE_PC = 10.0;
    /** Inside this radius around the Sun the procedural density is reduced by what the catalog supplies. */
    public static final double CATALOG_RADIUS_PC = 5.0;

    private final long seed;

    public Galaxy(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    public long sectorSeed(SectorKey key) {
        return SeedRng.hash(seed, key.ix(), key.iy(), key.iz());
    }

    /** Expected stars in a sector (density sampled at 8 sub-cell points). */
    public double expectedStarCount(SectorKey key) {
        double s = SECTOR_SIZE_PC, sum = 0;
        for (int i = 0; i < 8; i++) {
            double px = key.minX() + s * (((i & 1) == 0) ? 0.25 : 0.75);
            double py = key.minY() + s * (((i & 2) == 0) ? 0.25 : 0.75);
            double pz = key.minZ() + s * (((i & 4) == 0) ? 0.25 : 0.75);
            sum += GalaxyModel.starDensity(px, py, pz);
        }
        return sum / 8.0 * s * s * s;
    }

    /**
     * Initial mass separating the two streams of a sector. Stars at or above it are ~10 % of all
     * stars but include every giant and everything else visible from hundreds of parsecs, so the
     * sky can enumerate them alone.
     */
    public static final double LUMINOUS_MASS = 1.0;
    private static final double LUMINOUS_FRACTION = StellarPhysics.fractionAbove(LUMINOUS_MASS);

    /** Number of stars with initial mass below {@link #LUMINOUS_MASS}: indices [0, commonCount). */
    public int commonCount(SectorKey key) {
        SeedRng rng = new SeedRng(sectorSeed(key) ^ 0x5EC7_0C0D_E000L);
        int n = rng.poisson(expectedStarCount(key) * (1 - LUMINOUS_FRACTION));
        if (key.equals(StarCatalog.SOL_SECTOR)) {
            // The Sun's sector already holds the real neighbours; thin out the procedural fill to keep the census right.
            n = Math.max(0, n - StarCatalog.countInSector(key));
        }
        return n;
    }

    /** Number of stars at or above {@link #LUMINOUS_MASS}: indices [commonCount, starCount). */
    public int luminousCount(SectorKey key) {
        SeedRng rng = new SeedRng(sectorSeed(key) ^ 0x1B41_6470L);
        return rng.poisson(expectedStarCount(key) * LUMINOUS_FRACTION);
    }

    public int starCount(SectorKey key) {
        return commonCount(key) + luminousCount(key);
    }

    /** Luminous star {@code j} of a sector, without counting the common stream first. */
    public Star luminousStar(SectorKey key, int j) {
        return star(key, j, true);
    }

    /** Procedural star {@code index} of a sector. Deterministic. */
    public Star star(SectorKey key, int index) {
        int common = commonCount(key);
        return index < common ? star(key, index, false) : star(key, index - common, true);
    }

    private Star star(SectorKey key, int streamIndex, boolean luminous) {
        // The luminous stream hashes from a disjoint index range so the two streams never share a seed.
        long sseed = SeedRng.hash(sectorSeed(key), luminous ? 0x7FFF_0000L + streamIndex : streamIndex);
        SeedRng rng = new SeedRng(sseed);
        double x = key.minX() + rng.nextDouble() * SECTOR_SIZE_PC;
        double y = key.minY() + rng.nextDouble() * SECTOR_SIZE_PC;
        double z = key.minZ() + rng.nextDouble() * SECTOR_SIZE_PC;

        double feh = GalaxyModel.metallicity(x, y, z) + 0.15 * rng.gaussian();
        double ageGyr = sampleAge(rng, x, y, z);
        double m0 = luminous
                ? StellarPhysics.sampleInitialMass(rng, LUMINOUS_MASS, StellarPhysics.M_MAX)
                : StellarPhysics.sampleInitialMass(rng, StellarPhysics.M_MIN, LUMINOUS_MASS);
        // The real census within 5 pc of the Sun is complete for bright stars and has no giants:
        // procedural fill there may only add the faint M dwarfs that surveys are still finding.
        double dSol = Math.sqrt(sq(x - StarCatalog.SOL.x()) + sq(y - StarCatalog.SOL.y()) + sq(z - StarCatalog.SOL.z()));
        if (dSol < CATALOG_RADIUS_PC) {
            m0 = rng.range(0.08, 0.35);
            ageGyr = Math.min(ageGyr, 9.0);
        }

        return evolve(sseed, StarNames.procedural(sseed, key, luminous ? streamIndex + 100_000 : streamIndex),
                x, y, z, m0, ageGyr, feh, rng);
    }

    private static double sampleAge(SeedRng rng, double x, double y, double z) {
        double thin = GalaxyModel.starDensity(x, y, z) - GalaxyModel.bulgeDensity(x, y, z) - GalaxyModel.haloDensity(x, y, z);
        double total = GalaxyModel.starDensity(x, y, z);
        double old = 1.0 - Math.max(0, thin) / Math.max(1e-12, total);
        if (rng.nextDouble() < old) return rng.range(8.0, 12.5);           // bulge, halo: old populations
        // O/B stars then appear by themselves: the IMF makes them, short lifetimes keep them only where stars are young.
        if (rng.nextDouble() < GalaxyModel.youngFraction(x, y, z)) return rng.range(0.001, 0.1);
        return rng.range(0.1, 10.0);                                       // thin disk: ~constant star formation
    }

    static Star evolve(long seed, String name, double x, double y, double z,
                       double m0, double ageGyr, double feh, SeedRng rng) {
        double tms = StellarPhysics.mainSequenceLifetimeGyr(m0);
        StarKind kind;
        double mass, radius, lum, teff;
        if (ageGyr < tms) {
            // Stars brighten by ~40 % across the main sequence (the Sun is 30 % brighter than at ZAMS).
            double f = ageGyr / tms;
            lum = StellarPhysics.mainSequenceLuminosity(m0) * (1.0 + 0.4 * f);
            radius = StellarPhysics.mainSequenceRadius(m0) * (1.0 + 0.25 * f);
            mass = m0;
            kind = StarKind.MAIN_SEQUENCE;
            teff = StellarPhysics.effectiveTemperature(lum, radius);
        } else if (ageGyr < tms * 1.12) {
            mass = m0 * 0.9;
            kind = m0 > 8 ? StarKind.SUPERGIANT : StarKind.GIANT;
            teff = m0 > 8 ? rng.range(3_400, 25_000) : rng.range(3_600, 5_100);
            lum = m0 > 8 ? rng.range(2e4, 3e5) : 50.0 * Math.pow(m0, 1.5) * rng.range(0.5, 3.0);
            radius = Math.sqrt(lum) * Math.pow(5772.0 / teff, 2);
        } else if (m0 < 8.0) {
            kind = StarKind.WHITE_DWARF;
            mass = StellarPhysics.whiteDwarfMass(m0);
            radius = StellarPhysics.whiteDwarfRadius(mass);
            teff = StellarPhysics.whiteDwarfTemperature(ageGyr - tms * 1.12);
            lum = radius * radius * Math.pow(teff / 5772.0, 4);
        } else if (m0 < 20.0) {
            kind = StarKind.NEUTRON_STAR;
            mass = rng.range(1.2, 2.1);
            radius = 12_000.0 / 6.957e8;
            teff = 5e5 * Math.pow(Math.max(1e-3, ageGyr - tms), -0.3);
            lum = radius * radius * Math.pow(teff / 5772.0, 4);
        } else {
            kind = StarKind.BLACK_HOLE;
            mass = m0 * rng.range(0.25, 0.45);
            radius = StellarPhysics.schwarzschildRadius(mass);
            teff = 0;
            lum = 0;
        }
        return new Star(seed, name, x, y, z, kind, mass, radius, lum, teff, ageGyr, feh,
                SeedRng.hash(seed, 0x5157_E3L), false);
    }

    private static double sq(double v) {
        return v * v;
    }

    /** All stars (catalog + procedural) within {@code radiusPc} of a point, nearest sectors only. */
    public List<Star> starsNear(double x, double y, double z, double radiusPc, int maxStars) {
        List<Star> out = new ArrayList<>();
        SectorKey c = SectorKey.containing(x, y, z);
        int reach = (int) Math.ceil(radiusPc / SECTOR_SIZE_PC);
        double r2 = radiusPc * radiusPc;
        for (Star s : StarCatalog.all()) {
            double dx = s.x() - x, dy = s.y() - y, dz = s.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(s);
        }
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    SectorKey k = c.offset(dx, dy, dz);
                    int n = starCount(k);
                    for (int i = 0; i < n && out.size() < maxStars; i++) {
                        Star s = star(k, i);
                        if (s.distanceTo(x, y, z) <= radiusPc) out.add(s);
                    }
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.distanceTo(x, y, z), b.distanceTo(x, y, z)));
        return out;
    }
}
