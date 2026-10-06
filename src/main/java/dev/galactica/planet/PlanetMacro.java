package dev.galactica.planet;

import dev.galactica.science.Noise;
import dev.galactica.science.SeedRng;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;

/**
 * Planet-scale geography of a solid body as a pure function of the body-fixed unit direction: the
 * single source of truth that both the orbital imager ({@link dev.galactica.sky.PlanetImager}) and
 * the surface generator ({@link TerrainSampler}) read, so the continents seen from orbit are the
 * continents you land on.
 *
 * <p><b>Frame.</b> (x, y, z) with z along the spin axis (north), x through longitude 0 and y through
 * longitude 90° east: the frame of {@link BodyFrame#latLon} and of {@link SurfaceGrid#spherePoint}
 * (whose (x, y = north, z) components are this frame's (x, z, y)).
 *
 * <p><b>Fields</b> ({@link #sample}): normalised macro elevation (sea level 0 on bodies with a sea),
 * a hard land/sea decision, aridity (Hadley-cell desert belts near ±25° where descending dry air sits,
 * Held &amp; Hou 1980), polar ice caps, mare-type dark lowlands, big impact basins and, for ice moons,
 * lineae. All are fBm of value noise with the lattice rotated every octave, the octave series the
 * imager has always used.
 *
 * <p><b>Fractal continuation.</b> The imager resolves the first {@link #IMAGER_OCTAVES} octaves of
 * the elevation series; the generator adds the rest of the same series ({@link #tail}) down to a
 * ~{@link #TAIL_WAVELENGTH_BLOCKS}-block wavelength, so a coastline seen from orbit keeps its shape
 * and only gains the finer bays and headlands a fractal coast has (Mandelbrot 1967). The tail's
 * amplitude is 2^−6 of the field, so it moves the coast across only ~1 % of the surface.
 */
public final class PlanetMacro {

    /** Octaves of the elevation series the orbital imager resolves. */
    public static final int IMAGER_OCTAVES = 6;
    /** The fractal continuation stops at features of about this size, blocks. */
    public static final double TAIL_WAVELENGTH_BLOCKS = 64;
    /** Lacunarity of every fBm here (non-integer so octaves never share lattice lines). */
    public static final double LACUNARITY = 2.03;

    /** Rotation (axis (0.48, 0.60, 0.64), 1.1 rad) applied before each octave: no lattice-aligned coasts. */
    private static final double M00, M01, M02, M10, M11, M12, M20, M21, M22;

    static {
        double ax = 0.48, ay = 0.60, az = 0.64, c = Math.cos(1.1), s = Math.sin(1.1), k = 1 - c;
        M00 = c + ax * ax * k;      M01 = ax * ay * k - az * s; M02 = ax * az * k + ay * s;
        M10 = ay * ax * k + az * s; M11 = c + ay * ay * k;      M12 = ay * az * k - ax * s;
        M20 = az * ax * k - ay * s; M21 = az * ay * k + ax * s; M22 = c + az * az * k;
    }

    /** One evaluation of every macro field at a direction. Reused; no allocation per sample. */
    public static final class Sample {
        /** Normalised macro elevation; on bodies with a sea, negative is under it. */
        public double elevation;
        /** The primary octave field (imager's {@code h}), before sea offset. */
        public double base;
        /** Secondary moisture/tone field (imager's {@code m}). */
        public double moisture;
        /** True where the macro surface is below the sea (TERRAN, OCEAN) or a lava sea (LAVA). */
        public boolean sea;
        /** 0 wet … 1 desert, on bodies with life; mineral tone on lifeless ones. */
        public double aridity;
        /** Polar ice-cap weight, 0 … 1. */
        public double iceCap;
        /** Dark lowland (mare) weight, 0 … 1. */
        public double mare;
        /** Big impact-basin profile: negative bowl, positive rim, ~[−0.3, 0.3]. */
        public double craters;
        /** Ice-moon lineae weight, 0 … 1. */
        public double crack;
        /** Dirt fraction of a low-albedo ice moon, 0 … 1. */
        public double dirt;
        /** Lava glow weight, 0 … 1. */
        public double glow;
        /** |sin latitude|: the imager's latitude measure. */
        public double lat;
    }

    private final BodyType type;
    private final long seed;
    private final boolean life;
    private final double albedo;

    public PlanetMacro(BodyType type, long seed, boolean life, double albedo) {
        this.type = type;
        this.seed = seed;
        this.life = life;
        this.albedo = albedo;
    }

    public static PlanetMacro of(Body b) {
        return new PlanetMacro(b.type(), b.terrainSeed(), b.life() != Body.Life.NONE, b.albedo());
    }

    public BodyType type() {
        return type;
    }

    public long seed() {
        return seed;
    }

    public boolean life() {
        return life;
    }

    public double albedo() {
        return albedo;
    }

    /** Sea threshold on the primary field: Earth-like worlds are ~70 % ocean, ocean worlds nearly all. */
    public double seaThreshold() {
        return type == BodyType.OCEAN ? 0.30 : 0.05;
    }

    /** True for body types whose low ground is flooded (water, or lava on LAVA worlds). */
    public boolean hasSea() {
        return type == BodyType.TERRAN || type == BodyType.OCEAN || type == BodyType.LAVA;
    }

    /** Primary-field frequency on the unit sphere, per body type (the imager's established scales). */
    double baseFrequency() {
        return switch (type) {
            case TERRAN, OCEAN -> 1.6;
            case DESERT -> 0.9;
            case BARREN -> 1.2;
            case ICE -> 1.5;
            case LAVA -> 2.5;
            case GREENHOUSE -> 2.0;
            default -> 1.0;
        };
    }

    private int baseOctaves() {
        return switch (type) {
            case TERRAN, OCEAN -> 6;
            case BARREN -> 4;
            default -> 3;
        };
    }

    /** Seed of the primary series (DESERT's continental field is its {@code big} series). */
    private long baseSeed() {
        return type == BodyType.DESERT ? seed ^ 0xDE5E_0003L : seed;
    }

    /**
     * Evaluates every macro field at unit direction (x, y, z) into {@code o}. Exactly what the imager
     * paints from orbit; the generator adds {@link #tail} for block-scale coasts.
     */
    public void sample(double x, double y, double z, Sample o) {
        o.lat = Math.abs(z);
        o.moisture = 0;
        o.aridity = 0;
        o.iceCap = 0;
        o.mare = 0;
        o.craters = 0;
        o.crack = 0;
        o.dirt = 0;
        o.glow = 0;
        o.sea = false;
        double lat = o.lat;
        switch (type) {
            case TERRAN, OCEAN -> {
                double h = fbmRot(seed, x * 1.6, y * 1.6, z * 1.6, 6);
                double m = fbmRot(seed ^ 0x5EED_0001L, x * 3.1, y * 3.1, z * 3.1, 3);
                o.base = h;
                o.moisture = m;
                o.elevation = h - seaThreshold();
                o.sea = o.elevation < 0;
                if (life) {
                    // Hadley-cell desert belts near ±25° (|sin lat| ≈ 0.42), wetter tropics and mid-latitudes.
                    double arid = 0.9 * Math.exp(-sq((lat - 0.42) / 0.13)) - 1.5 * m + 0.05;
                    o.aridity = smooth(0.15, 0.6, arid);
                } else {
                    o.aridity = smooth(-0.3, 0.3, m);
                }
                o.iceCap = smooth(0.80 + 0.07 * m, 0.86 + 0.07 * m, lat);
            }
            case DESERT -> {
                double h = fbmRot(seed, x * 2.0, y * 2.0, z * 2.0, 5);
                double big = fbmRot(seed ^ 0xDE5E_0003L, x * 0.9, y * 0.9, z * 0.9, 3);
                o.base = big;
                o.moisture = h;
                o.aridity = smooth(-0.3, 0.3, h);
                o.mare = 0.75 * smooth(-0.02, -0.25, big);
                o.craters = 0.6 * craterField(seed ^ 0xC4A7_0004L, x * 7, y * 7, z * 7);
                o.iceCap = smooth(0.92, 0.95, lat + 0.02 * h);
                // Dark lowlands are low ground (Mars' basaltic northern plains), bright terrain high.
                o.elevation = 0.6 * big + 0.15 * h + 0.5 * o.craters;
            }
            case BARREN -> {
                double base = fbmRot(seed, x * 1.2, y * 1.2, z * 1.2, 4);
                o.base = base;
                o.mare = smooth(-0.05, -0.30, base);
                o.craters = craterField(seed ^ 0xC4A7_000AL, x * 4, y * 4, z * 4)
                        + 0.6 * craterField(seed ^ 0xC4A7_000BL, x * 11, y * 11, z * 11);
                o.moisture = fbmRot(seed ^ 0xBA4E_0009L, x * 8, y * 8, z * 8, 2);
                // Lunar maria are basalt-flooded basin floors, the lowest ground (Wilhelms 1987).
                o.elevation = base + 0.5 * o.craters;
            }
            case ICE -> {
                double tone = fbmRot(seed, x * 1.5, y * 1.5, z * 1.5, 3);
                o.base = tone;
                o.dirt = clamp((0.62 - albedo) / 0.4, 0, 1);
                double r1 = 1 - Math.abs(fbmRot(seed ^ 0x1CE0_0006L, x * 5, y * 5, z * 5, 1));
                double r2 = 1 - Math.abs(fbmRot(seed ^ 0x1CE0_0007L, x * 11, y * 11, z * 11, 1));
                o.crack = 0.75 * smooth(0.90, 0.985, Math.max(r1, r2 * 0.98)) * (1 - 0.6 * o.dirt);
                if (o.dirt > 0) o.craters = o.dirt * 1.2 * craterField(seed ^ 0xC4A7_0008L, x * 6, y * 6, z * 6);
                o.elevation = 0.4 * tone - 0.35 * o.crack + 0.5 * o.craters;
            }
            case LAVA -> {
                double tone = fbmRot(seed, x * 2.5, y * 2.5, z * 2.5, 3);
                o.base = tone;
                if (albedo > 0.4) { // Io: sulfur plains, SO₂ frost, dark hot paterae
                    o.moisture = smooth(0.25, 0.45, fbmRot(seed ^ 0x50F2_000CL, x * 3, y * 3, z * 3, 2));
                    double pat = smooth(-0.05, -0.25, craterField(seed ^ 0xC4A7_000DL, x * 5, y * 5, z * 5));
                    o.mare = pat;
                    o.glow = 0.6 * pat * pat;
                    o.elevation = 0.25 + 0.2 * tone - 0.6 * pat;
                } else {
                    double c1 = 1 - Math.abs(fbmRot(seed ^ 0x1A7A_000EL, x * 4, y * 4, z * 4, 1));
                    double c2 = 1 - Math.abs(fbmRot(seed ^ 0x1A7A_000FL, x * 9, y * 9, z * 9, 1));
                    double crack = smooth(0.93, 0.995, Math.max(c1, c2 * 0.985));
                    double lake = smooth(0.38, 0.52, tone);
                    o.crack = crack;
                    o.glow = Math.max(crack, lake);
                    // Lava lakes are the low ground: the lake contour (tone = 0.45) is the lava sea level.
                    o.elevation = 0.45 - tone;
                }
                o.sea = o.elevation < 0;
            }
            case GREENHOUSE -> {
                double w = fbmRot(seed, x * 2, y * 2, z * 2, 3);
                o.base = w;
                o.elevation = w;
            }
            default -> {
                o.base = 0;
                o.elevation = 0;
            }
        }
    }

    /**
     * The rest of the primary octave series beyond what the imager resolves, from
     * {@link #IMAGER_OCTAVES} (or the type's own octave count) down to a ~64-block wavelength on a
     * map sphere of {@code radiusBlocks}, in the same normalisation as {@link Sample#elevation}.
     */
    public double tail(double x, double y, double z, double radiusBlocks) {
        int first = baseOctaves();
        int last = tailOctaves(radiusBlocks);
        if (last <= first) return 0;
        double f = baseFrequency();
        double sum = fbmRange(baseSeed(), x * f, y * f, z * f, first, last) / norm(first);
        return switch (type) {
            case DESERT -> 0.6 * sum;
            case ICE -> 0.4 * sum;
            case LAVA -> albedo > 0.4 ? 0.2 * sum : -0.5 * sum; // only 3 resolved octaves: a gentler tail
            default -> sum;
        };
    }

    /** Total octave count whose finest wavelength reaches {@link #TAIL_WAVELENGTH_BLOCKS} on this sphere. */
    public int tailOctaves(double radiusBlocks) {
        double ratio = radiusBlocks / (baseFrequency() * TAIL_WAVELENGTH_BLOCKS);
        if (ratio <= 1) return baseOctaves();
        return Math.min(16, Math.max(baseOctaves(), (int) Math.ceil(Math.log(ratio) / Math.log(LACUNARITY))));
    }

    // ---------------------------------------------------------------- noise

    /**
     * fBm of value noise with the lattice rotated before every octave (Quilez's rotated-octave trick),
     * normalised to ~[−1, 1]: kills the axis-aligned blockiness of trilinear value noise.
     */
    public static double fbmRot(long seed, double x, double y, double z, int octaves) {
        return fbmRange(seed, x, y, z, 0, octaves) / norm(octaves);
    }

    /** Σ of octaves [from, to) of the rotated series, unnormalised (octave i has amplitude 0.5^(i+1)). */
    static double fbmRange(long seed, double x, double y, double z, int from, int to) {
        double sum = 0, amp = 0.5;
        for (int i = 0; i < to; i++) {
            double nx = M00 * x + M01 * y + M02 * z, ny = M10 * x + M11 * y + M12 * z, nz = M20 * x + M21 * y + M22 * z;
            if (i >= from) sum += amp * Noise.value(seed + i * 31L, nx, ny, nz);
            amp *= 0.5;
            x = nx * LACUNARITY;
            y = ny * LACUNARITY;
            z = nz * LACUNARITY;
        }
        return sum;
    }

    private static double norm(int octaves) {
        return 1 - Math.pow(0.5, octaves);
    }

    /**
     * Worley-cell impact basins: dark bowl floor (−0.3), bright raised rim (+0.25) and faint ejecta.
     * Radii up to 0.42 cell so a 3×3×3 neighbourhood covers every crater. Large basins stay shallow:
     * complex-crater depth grows only as D^0.3 (Pike 1977), so the profile is used at small vertical scale.
     */
    public static double craterField(long seed, double x, double y, double z) {
        long cx = (long) Math.floor(x), cy = (long) Math.floor(y), cz = (long) Math.floor(z);
        double acc = 0;
        for (long i = cx - 1; i <= cx + 1; i++)
            for (long j = cy - 1; j <= cy + 1; j++)
                for (long k = cz - 1; k <= cz + 1; k++) {
                    long h = SeedRng.mix(seed ^ (i * 0x8DA6B343L) ^ (j * 0xD8163841L) ^ (k * 0xCB1AB31FL));
                    if ((h & 3) == 0) continue; // a quarter of cells empty: irregular spacing
                    double rad = 0.10 + 0.32 * sq(((h >>> 2) & 0x3F) / 63.0); // many small, few large
                    double dx = x - (i + ((h >>> 8) & 0xFFFF) / 65536.0);
                    double dy = y - (j + ((h >>> 24) & 0xFFFF) / 65536.0);
                    double dz = z - (k + ((h >>> 40) & 0xFFFF) / 65536.0);
                    double d2 = dx * dx + dy * dy + dz * dz, reach = rad * 2.2;
                    if (d2 > reach * reach) continue;
                    double t = Math.sqrt(d2) / rad;
                    if (t < 0.85) acc -= 0.30 * (1 - sq(t / 0.85));
                    acc += 0.25 * Math.exp(-sq((t - 1) / 0.12));
                    if (t > 1) acc += 0.06 * (2.2 - t) / 1.2;
                }
        return acc;
    }

    // ---------------------------------------------------------------- helpers

    static double sq(double x) {
        return x * x;
    }

    /** NaN maps to {@code lo}. */
    static double clamp(double x, double lo, double hi) {
        return x > hi ? hi : x > lo ? x : lo;
    }

    /** Hermite smoothstep; edges may be given in either order. */
    public static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
