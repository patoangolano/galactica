package dev.galactica.planet;

import dev.galactica.science.Noise;
import dev.galactica.science.SeedRng;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;

/**
 * Surface terrain of a landable body as a pure function of block (x, z), plus a thin 3D band around
 * that surface for cliffs and overhangs. Every 2D noise field is sampled on the map sphere
 * ({@link SurfaceGrid#spherePoint}), so the surface closes on itself at the x seam and shrinks
 * honestly towards the poles.
 *
 * <p><b>One source of truth.</b> Planet-scale shape (continents, seas, deserts, ice caps, maria,
 * impact basins) comes from {@link PlanetMacro}, the same fields the orbital imager paints; this
 * class adds the macro field's fractal tail and the regional and block-scale relief. On bodies with
 * a sea the coastline is exactly where the macro elevation crosses zero, so the sea you saw from
 * orbit is the sea you land in.
 *
 * <p><b>Vertical scale (heights in blocks).</b> The map is 1 block = {@code mpb} metres horizontally
 * ({@link SurfaceGrid#metresPerBlock}: Earth-size worlds 10 m, Mars 5.3 m, the Moon 2.7 m, small bodies
 * 1 m), and the sky above it uses the same scale for altitude ({@code TransferAltitude}). Terrain uses
 * that scale too, <i>until it no longer fits</i>:
 * <ul>
 *   <li>Physical peak relief above the datum is the height a crust supports before it flows,
 *       σ/(ρg) (Melosh 2011, ch. 4): Everest's 8.85 km at 1 g, so {@value #EVEREST_M}/g metres. It is
 *       capped at Olympus Mons' 21.9 km above the Martian datum (the tallest edifice known) and at
 *       {@value #MAX_RELIEF_TO_RADIUS} of the body's radius (Vesta's Rheasilvia peak, 22 km on a 262 km
 *       body, is about that), so small bodies do not grow mountains taller than they can hold.</li>
 *   <li>That height in blocks at true scale is relief/mpb: Earth-like worlds 885 blocks, Mars 4 130,
 *       the Moon 8 100. Up to {@value #PEAK_BUDGET} blocks the terrain is drawn at <b>true scale</b>
 *       (vertical = horizontal map scale). Beyond it the whole relief of the body is compressed by one
 *       factor κ = {@value #PEAK_BUDGET}/true ({@link #verticalScale}), so shapes keep their proportions:
 *       Mars is drawn at κ ≈ 0.22 (one vertical block ≈ 24 m), the Moon at κ ≈ 0.11 (≈ 24 m).
 *       A soft knee from {@value #PEAK_KNEE} to {@value #PEAK_CAP} blocks above sea level only
 *       catches the rare stack of mountain, volcano and detail above the budget.</li>
 *   <li>Below the datum, sea floors reach {@value #TRENCH_TO_PEAK_SEA}× the peak (the Mariana trench
 *       is 1.25 Everests deep) and dry basins {@value #BASIN_TO_PEAK_DRY}× (Hellas −7 km against
 *       Olympus +22 km; the Moon's SPA −9 km against +10.8 km), both budgeted at {@value #DEPTH_BUDGET}
 *       blocks with a soft knee to {@value #DEPTH_CAP}.</li>
 * </ul>
 * The generator range is {@link #MIN_Y} to {@link #MAX_Y} (dimension_type galactica:surface, min_y −512,
 * height 1792): sea level 96, peaks at most 96 + 1120 = 1216, floors at least 96 − 560 = −464.
 *
 * <p><b>Tectonic-style separation of scales.</b> As in the Tectonic mod's noise router, vertical scale,
 * continents, erosion and ridges are independent knobs: continents are the macro field (planet scale);
 * mountain ranges are squared ridged noise whose wavelength grows with the relief ({@link #featureScale})
 * so slopes stay climbable however tall the body's mountains are; an erosion field switches ranges on and
 * off regionally, leaving plains between them; deserts get flat-floored canyon systems and volcanic worlds
 * shield volcanoes with summit calderas. Mountain flanks and canyon walls get a 3D density band
 * ({@link #carve}) for overhangs, cliffs and arches.
 *
 * <p><b>Craters.</b> Bodies whose air cannot burn up impactors ({@link SurfaceAtmosphere#cratered})
 * get two populations of simple craters from a cellular stamp: bowl depth ≈ 0.2 D (the
 * depth/diameter of fresh lunar simple craters, Pike 1977) with a raised rim. Crater sizes are in
 * blocks, not metres, so they read the same on every body regardless of map compression. The big
 * basins visible from orbit are part of the macro elevation.
 */
public final class TerrainSampler {

    /** Block palette; the chunk generator maps each to a vanilla block state. */
    public enum Material {
        AIR, BEDROCK, STONE, GRAVEL, TUFF, RED_SAND, TERRACOTTA, PACKED_ICE, SNOW_BLOCK, BLUE_ICE, ICE,
        BASALT, BLACKSTONE, MAGMA_BLOCK, GRASS_BLOCK, DIRT, SAND, SANDSTONE, YELLOW_TERRACOTTA, SMOOTH_BASALT,
        WATER, LAVA, COARSE_DIRT
    }

    /**
     * Version of the generator's output. Saved slot chunks from another version are dropped at startup
     * ({@code PlanetSlots}): 1 = the 768-block slots, 2 = the 1792-block slots with the cliff band.
     */
    public static final int GENERATION = 2;

    /** Fluid surface and the world Y every body's datum sits at. */
    public static final int SEA_LEVEL = 96;
    /** Generator vertical range, matching dimension_type galactica:surface (min_y −512, height 1792). */
    public static final int MIN_Y = -512, MAX_Y = 1279;

    /** Everest above sea level, metres: the peak relief of a 1 g crust. */
    public static final double EVEREST_M = 8848;
    /** Olympus Mons above the Martian datum, metres: the tallest edifice known, the cap of the 1/g law. */
    public static final double OLYMPUS_M = 21_900;
    /** Peak relief never exceeds this fraction of the body's radius (Rheasilvia on Vesta). */
    public static final double MAX_RELIEF_TO_RADIUS = 0.085;
    /** Deepest sea floor relative to the peak (Challenger Deep / Everest). */
    public static final double TRENCH_TO_PEAK_SEA = 1.25;
    /** Deepest dry basin relative to the peak. */
    public static final double BASIN_TO_PEAK_DRY = 0.6;
    /** Peak height (blocks above sea level) drawn at true scale; beyond it the relief is compressed. */
    public static final double PEAK_BUDGET = 900;
    /** Soft knee and hard ceiling of the final surface, blocks above sea level. */
    public static final double PEAK_KNEE = 760, PEAK_CAP = 1120;
    /** The same below sea level. */
    public static final double DEPTH_BUDGET = 480, DEPTH_KNEE = 420, DEPTH_CAP = 560;
    /** Smallest peak relief, blocks: heavy worlds still get hills. */
    public static final double MIN_PEAK_BLOCKS = 40;
    /** Normalised elevation the highest ground reaches; the up amplitude is peak / this. */
    static final double E_PEAK = 1.5;
    /** Normalised depth of the deepest floors. */
    static final double E_DEPTH = 1.2;
    /** Half-height of the 3D cliff band at full weight, blocks. */
    public static final int BAND_MAX = 24;
    /** Fixed block-scale roughness, blocks (independent of the body's relief so the ground is never glassy). */
    static final double FINE_BLOCKS = 3;

    /** One column's layering, filled by {@link #column} and optionally {@link #carve}. Reused, allocation-free. */
    public static final class Column {
        /** 2.5D surface height (the heightmap the LOD, clipmap and imager share). */
        public int height;
        public Material top, filler, under;
        public int topDepth, fillerDepth;
        /** Fluid fills (height, fluidTop]; NONE when fluid == AIR. */
        public Material fluid = Material.AIR;
        public int fluidTop;
        /** Frozen sea surface replaces the top fluid block. */
        public boolean iceCap;
        /** Highest solid block once the band is carved (== height without a band). */
        public int solidTop;
        /** 3D band [bandLo, bandLo + bandLen): solid flags and local depth below the nearest air above. */
        public int bandLo, bandLen;
        final boolean[] bandSolid = new boolean[2 * BAND_MAX + 4];
        final int[] bandDepth = new int[2 * BAND_MAX + 4];

        /** The material seen from above: frozen sea, fluid surface or the top solid block. */
        public Material visible() {
            if (fluid != Material.AIR) return iceCap ? Material.ICE : fluid;
            return top;
        }

        /** True when y lies inside the carved band. */
        public boolean inBand(int y) {
            return y >= bandLo && y < bandLo + bandLen;
        }

        /** Band solidity at y (y must be {@link #inBand}). */
        public boolean bandSolid(int y) {
            return bandSolid[y - bandLo];
        }
    }

    private final BodyType type;
    private final long seed;
    private final SurfaceGrid grid;
    private final double peakBlocks, depthBlocks;
    private final double amplitude, depthAmplitude;
    private final double verticalScale;
    private final double featureScale;
    private final boolean cratered;
    private final boolean frozenSeas;
    private final boolean banded;
    private final PlanetMacro macro;
    private static final ThreadLocal<PlanetMacro.Sample> SAMPLE = ThreadLocal.withInitial(PlanetMacro.Sample::new);
    private static final ThreadLocal<double[]> POINT = ThreadLocal.withInitial(() -> new double[3]);

    /** A body without life and with a mid albedo; for tests and synthetic worlds. */
    public TerrainSampler(BodyType type, long seed, double gravityG, SurfaceGrid grid, boolean cratered, boolean frozenSeas) {
        this(new PlanetMacro(type, seed, false, 0.3), gravityG, grid, cratered, frozenSeas);
    }

    public TerrainSampler(PlanetMacro macro, double gravityG, SurfaceGrid grid, boolean cratered, boolean frozenSeas) {
        if (!macro.type().solidSurface) throw new IllegalArgumentException(macro.type() + " has no surface");
        this.type = macro.type();
        this.seed = macro.seed();
        this.macro = macro;
        this.grid = grid;
        double mpb = grid.metresPerBlock();
        double peakM = reliefPeakMetres(gravityG, grid.radiusMetres());
        double truePeak = peakM / mpb;
        this.peakBlocks = Math.max(MIN_PEAK_BLOCKS, Math.min(PEAK_BUDGET, truePeak));
        this.verticalScale = Math.min(1, peakBlocks / Math.max(1e-9, truePeak));
        double trueDepth = peakM * (macro.hasSea() ? TRENCH_TO_PEAK_SEA : BASIN_TO_PEAK_DRY) / mpb;
        this.depthBlocks = Math.max(MIN_PEAK_BLOCKS * 0.5, Math.min(DEPTH_BUDGET, trueDepth));
        this.amplitude = peakBlocks / E_PEAK;
        this.depthAmplitude = depthBlocks / E_DEPTH;
        this.featureScale = featureScaleFor(amplitude);
        this.cratered = cratered;
        this.frozenSeas = frozenSeas;
        this.banded = switch (type) {
            case TERRAN, DESERT, GREENHOUSE, LAVA -> true;
            default -> false;
        };
    }

    public static TerrainSampler of(Body b, SurfaceGrid grid) {
        return new TerrainSampler(PlanetMacro.of(b), b.surfaceGravityG(), grid,
                SurfaceAtmosphere.cratered(b), b.surfaceTemp() < 271);
    }

    /** Stand-in for a slot with no body assigned: rolling grey barren ground. */
    public static TerrainSampler placeholder() {
        return new TerrainSampler(BodyType.BARREN, 0L, 1.0, SurfaceGrid.of(1.0e5), false, false);
    }

    /**
     * Physical peak relief above the datum, metres: {@value #EVEREST_M}/g (σ/ρg), capped at Olympus Mons and
     * at {@value #MAX_RELIEF_TO_RADIUS} of the radius.
     */
    public static double reliefPeakMetres(double gravityG, double radiusMetres) {
        return Math.min(Math.min(EVEREST_M / Math.max(1e-6, gravityG), OLYMPUS_M), MAX_RELIEF_TO_RADIUS * radiusMetres);
    }

    /**
     * Horizontal stretch of the regional relief (mountain ranges, erosion, canyons) for an amplitude, ≥ 1:
     * taller relief gets proportionally wider ranges (√(A/40)) so slopes stay of the same order on every body.
     */
    static double featureScaleFor(double amplitude) {
        return Math.max(1, Math.min(8, Math.sqrt(amplitude / 40)));
    }

    /** Blocks per unit of normalised elevation above the datum. */
    public double amplitude() {
        return amplitude;
    }

    /** Blocks per unit of normalised elevation below the datum (sea floors, basins). */
    public double depthAmplitude() {
        return depthAmplitude;
    }

    /** Highest peak the relief law gives this body, blocks above sea level (before the knee). */
    public double peakBlocks() {
        return peakBlocks;
    }

    /** Deepest floor the law gives, blocks below sea level. */
    public double depthBlocks() {
        return depthBlocks;
    }

    /**
     * Vertical compression κ: 1 when the terrain is drawn at the map's true scale, below 1 when the body's
     * physical relief exceeds {@value #PEAK_BUDGET} blocks. One vertical block then stands for mpb/κ metres.
     */
    public double verticalScale() {
        return verticalScale;
    }

    /** Horizontal stretch of mountain ranges and canyons relative to the 1 g reference ({@link #featureScaleFor}). */
    public double featureScale() {
        return featureScale;
    }

    public BodyType type() {
        return type;
    }

    public SurfaceGrid grid() {
        return grid;
    }

    public PlanetMacro macro() {
        return macro;
    }

    public boolean hasSea() {
        return macro.hasSea();
    }

    /** True for body types whose mountains and canyon walls get the 3D cliff band. */
    public boolean banded() {
        return banded;
    }

    /**
     * Body-fixed unit direction ({@link PlanetMacro} frame: z north, x longitude 0, y 90° east) under
     * map position (x, z). The map sphere point is (x, y = north, z), so its components are re-ordered.
     */
    public void direction(double x, double z, double[] out) {
        grid.spherePoint(x, z, out);
        double r = grid.radiusBlocks();
        double sx = out[0] / r, north = out[1] / r, sz = out[2] / r;
        out[0] = sx;
        out[1] = sz;
        out[2] = north;
    }

    /** 2.5D surface Y (top solid block before the cliff band) of column (x, z). */
    public int height(int x, int z) {
        return height((double) x, (double) z);
    }

    /**
     * Surface Y at a (possibly fractional) map position. On bodies with a sea the land/sea decision is
     * exactly the sign of the macro elevation plus its fractal tail, and local relief is capped so it
     * never pushes ground across sea level: land sits at least one block above the sea surface, sea
     * floors at least one block below it.
     */
    public int height(double x, double z) {
        double[] s = POINT.get();
        grid.spherePoint(x, z, s);
        double r = grid.radiusBlocks();
        double ux = s[0] / r, uy = s[2] / r, uz = s[1] / r;
        return heightAt(s, macro.elevation(ux, uy, uz) + macro.tail(ux, uy, uz, r));
    }

    /**
     * {@link #height(int, int)} that also leaves every macro field of the column in {@code out}, for
     * {@link #column(int, int, int, int, Column, PlanetMacro.Sample)}: the generator evaluates the
     * macro series once per column instead of twice.
     */
    public int height(int x, int z, PlanetMacro.Sample out) {
        double[] s = POINT.get();
        grid.spherePoint(x, z, s);
        double r = grid.radiusBlocks();
        double ux = s[0] / r, uy = s[2] / r, uz = s[1] / r;
        macro.sample(ux, uy, uz, out);
        return heightAt(s, out.elevation + macro.tail(ux, uy, uz, r));
    }

    /** Surface Y at map-sphere point s (blocks) whose macro elevation, tail included, is {@code macroE}. */
    private int heightAt(double[] s, double macroE) {
        double x = s[0], y = s[1], z = s[2];
        double e = elevation(x, y, z, macroE);
        boolean land = !macro.hasSea() || macroE >= 0;
        // Normalised elevation to blocks: separate up and down amplitudes (taller mountains need not mean
        // deeper seas, Tectonic's "vertical scale" acts above sea level only).
        double b = e >= 0 ? e * amplitude : e * depthAmplitude;
        if (land) {
            b += FINE_BLOCKS * Noise.fbm(seed + 111, x / 48, y / 48, z / 48, 2);
            b += volcanoes(x, y, z);
            b -= canyon(x, y, z);
        }
        if (cratered) b += craters(x, y, z);
        b = b >= 0 ? softCap(b, PEAK_KNEE, PEAK_CAP) : -softCap(-b, DEPTH_KNEE, DEPTH_CAP);
        double h;
        if (macro.hasSea()) {
            if (macroE >= 0) h = Math.max(SEA_LEVEL + 1, Math.floor(SEA_LEVEL + 1 + b));
            else h = Math.min(SEA_LEVEL - 1, Math.floor(SEA_LEVEL + b));
        } else {
            h = Math.floor(SEA_LEVEL + b);
        }
        return (int) Math.max(MIN_Y + 8, Math.min(MAX_Y - 16, h));
    }

    /** Identity up to {@code knee}, then a tanh roll-off that never exceeds {@code cap}. */
    static double softCap(double v, double knee, double cap) {
        if (v <= knee) return v;
        double room = cap - knee;
        return knee + room * Math.tanh((v - knee) / room);
    }

    /** Squared ridged noise of the mountain ranges, 0 … 1, wavelength 700 × feature scale. */
    private double ridges(double x, double y, double z) {
        double k = 700 * featureScale;
        double ridged = 1 - 2 * Math.abs(Noise.fbm(seed + 202, x / k, y / k, z / k, 4));
        return ridged > 0 ? ridged * ridged : 0;
    }

    /** Erosion gate (Tectonic's erosion): 1 where ranges stand, 0 on eroded plains, at the regional scale. */
    private double erosionGate(double x, double y, double z) {
        double k = 2400 * featureScale;
        return PlanetMacro.smooth(-0.15, 0.30, Noise.fbm(seed + 707, x / k, y / k, z / k, 3));
    }

    /**
     * Normalised elevation, ~[−1.5, 1.5], before scaling by the relief amplitudes: the macro elevation
     * (planet scale, shared with the imager) plus regional and block-scale relief sampled on the map
     * sphere at (x, y, z). On sea-bearing bodies the result keeps the sign of {@code macroE}.
     */
    double elevation(double x, double y, double z, double macroE) {
        double kd = 220 * featureScale;
        double detail = Noise.fbm(seed + 101, x / kd, y / kd, z / kd, 4);
        double gate = erosionGate(x, y, z);
        double mountains = ridges(x, y, z) * gate;
        return switch (type) {
            case TERRAN -> coastal(1.6, macroE, 0.25 * detail, 1.0 * mountains);
            case OCEAN -> coastal(1.4, macroE, 0.2 * detail, 0.5 * mountains);
            case LAVA -> coastal(1.2, macroE, 0.35 * detail, 0.6 * mountains);
            case DESERT -> 1.6 * macroE + 0.3 * detail + 0.7 * mountains
                    + 0.08 * Math.abs(Noise.fbm(seed + 303, x / 36, y / 36, z / 36, 2)) * 4 * 60 / amplitude; // dunes, ~20 blocks
            case BARREN -> 1.2 * macroE + 0.3 * detail + 0.45 * ridges(x, y, z) * (0.4 + 0.6 * gate);
            case ICE -> {
                // Europa-like lineae at block scale: narrow grooves where a mid-scale field crosses zero.
                double l = Noise.fbm(seed + 404, x / 300, y / 300, z / 300, 3);
                double groove = Math.max(0, 1 - Math.abs(l) * 14);
                yield 1.2 * macroE + 0.15 * detail - 0.25 * groove * Math.min(1, 150 / amplitude) + 0.25 * mountains;
            }
            case GREENHOUSE -> {
                // Venusian tesserae: plateaus broken into terraces ~16 blocks high whatever the relief.
                double e0 = 1.4 * macroE + 0.3 * detail;
                double k = Math.max(6, amplitude / 16);
                double step = Math.floor(e0 * k) / k;
                yield step + 0.35 * (e0 - step) + 0.4 * mountains;
            }
            default -> 0;
        };
    }

    /**
     * Sign-preserving relief on a coastal body: {@code k·macroE} plus local detail clamped to
     * ±|k·macroE| (coastal plains flatten towards the shore), plus mountains on land only, growing
     * inland over the first 0.3 of macro elevation.
     */
    static double coastal(double k, double macroE, double detail, double mountains) {
        double base = k * macroE, reach = Math.abs(base);
        double e = base + Math.max(-reach, Math.min(reach, detail));
        if (macroE >= 0) return Math.max(0, e) + mountains * clamp01(macroE / 0.3);
        return Math.min(0, e);
    }

    // ---------------------------------------------------------------- canyons and volcanoes

    /** Canyon profile 0 … 1 (1 on the flat floor) of desert worlds; 0 elsewhere. */
    double canyonProfile(double x, double y, double z) {
        if (type != BodyType.DESERT) return 0;
        double k = 1800 * featureScale;
        double region = PlanetMacro.smooth(0.05, 0.25, Noise.fbm(seed + 809, x / (4 * k), y / (4 * k), z / (4 * k), 2));
        if (region <= 0) return 0;
        double c = Math.abs(Noise.fbm(seed + 808, x / k, y / k, z / k, 3));
        // Where the field crosses zero: steep walls (smoothstep over a narrow band) and a flat floor.
        return region * PlanetMacro.smooth(0.07, 0.035, c);
    }

    /** Canyon depth, blocks: Valles Marineris is ~7 km deep against Olympus' 22 km, about a third of the peak. */
    private double canyon(double x, double y, double z) {
        double p = canyonProfile(x, y, z);
        return p <= 0 ? 0 : 0.35 * peakBlocks * p;
    }

    private static final double VOLCANO_CELL = 9000;

    /**
     * Shield volcanoes of volcanic worlds (deserts like Mars, lava worlds, greenhouse worlds like Venus):
     * broad cones (flank slopes of a few degrees at true scale) with a summit caldera, up to 0.9 of the
     * body's peak relief. Blocks of height added at map-sphere point (x, y, z).
     */
    double volcanoes(double x, double y, double z) {
        if (type != BodyType.DESERT && type != BodyType.LAVA && type != BodyType.GREENHOUSE) return 0;
        double cell = VOLCANO_CELL * featureScale;
        long cx = (long) Math.floor(x / cell), cy = (long) Math.floor(y / cell), cz = (long) Math.floor(z / cell);
        double rs = grid.radiusBlocks();
        double sum = 0;
        for (long i = cx - 1; i <= cx + 1; i++) {
            for (long j = cy - 1; j <= cy + 1; j++) {
                for (long k = cz - 1; k <= cz + 1; k++) {
                    SeedRng rng = new SeedRng(SeedRng.hash(seed ^ 0x701C_A40EL, i, j, k));
                    if (rng.nextDouble() >= 0.14) continue;
                    double px = (i + rng.nextDouble()) * cell, py = (j + rng.nextDouble()) * cell, pz = (k + rng.nextDouble()) * cell;
                    double n = Math.sqrt(px * px + py * py + pz * pz);
                    if (n < 1e-9 || Math.abs(n - rs) > cell) continue;
                    px *= rs / n;
                    py *= rs / n;
                    pz *= rs / n;
                    double u = rng.nextDouble();
                    double radius = cell * (0.12 + 0.3 * u);
                    double height = peakBlocks * (0.3 + 0.6 * u * u);
                    double dx = x - px, dy = y - py, dz = z - pz;
                    double t = Math.sqrt(dx * dx + dy * dy + dz * dz) / radius;
                    if (t >= 1) continue;
                    sum += volcanoProfile(t, height);
                }
            }
        }
        return sum;
    }

    /** Shield profile at normalised distance t: H (1 − t)^1.5, minus a caldera 0.12 H deep inside t < 0.08. */
    static double volcanoProfile(double t, double height) {
        double cone = height * Math.pow(1 - t, 1.5);
        if (t < 0.08) cone -= 0.12 * height * (1 - sq(t / 0.08));
        return cone;
    }

    /** Shield-volcano height at column (x, z), blocks (0 off volcanic worlds); for tours and tests. */
    public double volcanoAt(int x, int z) {
        double[] p = POINT.get();
        grid.spherePoint(x, z, p);
        return volcanoes(p[0], p[1], p[2]);
    }

    /** Canyon depth at column (x, z), blocks (0 off desert worlds); for tours and tests. */
    public double canyonAt(int x, int z) {
        double[] p = POINT.get();
        grid.spherePoint(x, z, p);
        return canyon(p[0], p[1], p[2]);
    }

    /** Macro fields at map position (x, z), written into {@code out} (imager resolution, no tail). */
    public void macroAt(double x, double z, PlanetMacro.Sample out) {
        double[] d = POINT.get();
        direction(x, z, d);
        macro.sample(d[0], d[1], d[2], out);
    }

    // ---------------------------------------------------------------- 3D cliff band

    /**
     * Weight 0 … 1 of the 3D band at column (x, z): mountain ranges (ridges under the erosion gate) and
     * canyon walls. 0 on bodies without a band.
     */
    public double bandWeight(int x, int z) {
        if (!banded) return 0;
        double[] s = POINT.get();
        grid.spherePoint(x, z, s);
        double w = 1.4 * ridges(s[0], s[1], s[2]) * erosionGate(s[0], s[1], s[2]);
        if (type == BodyType.DESERT) {
            double p = canyonProfile(s[0], s[1], s[2]);
            w = Math.max(w, 4 * p * (1 - p) * 1.5); // walls, not the floor
        }
        return clamp01(w);
    }

    /**
     * Carves the 3D band of column (x, z) into {@code c} (after {@link #column}): within ±B blocks of the
     * 2.5D surface h (B = {@value #BAND_MAX} × {@link #bandWeight}) a block is solid where
     * (h + ½ − y)/B + 0.85 n(x, y, z) &gt; 0, n a two-octave 3D value noise of 22 and 9 block wavelength.
     * That is the vanilla terrain test (a vertical gradient plus 3D noise, as Tectonic's {@code final_density})
     * confined to a thin shell, so the rest of the column keeps the 2.5D speed. Bodies with a sea only carve
     * above sea level, so no air pocket forms under a fluid. Records the local depth below the nearest air
     * above for the layering, so ground under an overhang gets its topsoil.
     */
    public void carve(int x, int z, Column c) {
        c.bandLen = 0;
        c.solidTop = c.height;
        if (!banded) return;
        int h = c.height;
        if (macro.hasSea() && h < SEA_LEVEL + 3) return;
        int b = (int) Math.round(BAND_MAX * bandWeight(x, z));
        if (b < 4) return;
        int lo = h - b, hi = h + b; // inclusive
        if (macro.hasSea()) lo = Math.max(lo, SEA_LEVEL + 2);
        lo = Math.max(lo, MIN_Y + 8);
        hi = Math.min(hi, MAX_Y - 1);
        if (hi < lo) return;
        c.bandLo = lo;
        c.bandLen = hi - lo + 1;
        double wx = grid.wrapX(x);
        int top = Integer.MIN_VALUE;
        for (int y = hi; y >= lo; y--) {
            double n = 0.65 * Noise.value(seed + 909, wx / 22, y / 15.0, z / 22.0)
                    + 0.35 * Noise.value(seed + 910, wx / 9, y / 7.0, z / 9.0);
            boolean solid = (h + 0.5 - y) / b + 0.85 * n > 0;
            c.bandSolid[y - lo] = solid;
            if (solid && top == Integer.MIN_VALUE) top = y;
        }
        // Local depth: blocks of solid between y and the nearest air above it (0 for an exposed top block).
        int run = -1;
        for (int y = hi; y >= lo; y--) {
            int i = y - lo;
            run = c.bandSolid[i] ? run + 1 : -1;
            c.bandDepth[i] = run;
        }
        c.solidTop = top == Integer.MIN_VALUE ? lo - 1 : top;
    }

    /** Highest solid Y of column (x, z), cliff band included (where a player or a ship can stand). */
    public int solidTop(int x, int z) {
        Column c = new Column();
        c.height = height(x, z);
        carve(x, z, c);
        return c.solidTop;
    }

    // ---------------------------------------------------------------- craters

    private static final double BIG_CELL = 480, SMALL_CELL = 120;

    /** Sum of crater profiles near point (x, y, z) of the map sphere, blocks of height change. */
    double craters(double x, double y, double z) {
        return craterLayer(x, y, z, BIG_CELL, 0.35, 40, 200, 0xC4A7E4L)
                + craterLayer(x, y, z, SMALL_CELL, 0.5, 8, 45, 0x5A11C4L);
    }

    private double craterLayer(double x, double y, double z, double cell, double chance, double rMin, double rMax, long salt) {
        long cx = (long) Math.floor(x / cell), cy = (long) Math.floor(y / cell), cz = (long) Math.floor(z / cell);
        double rs = grid.radiusBlocks();
        double sum = 0;
        for (long i = cx - 1; i <= cx + 1; i++) {
            for (long j = cy - 1; j <= cy + 1; j++) {
                for (long k = cz - 1; k <= cz + 1; k++) {
                    SeedRng rng = new SeedRng(SeedRng.hash(seed ^ salt, i, j, k));
                    if (rng.nextDouble() >= chance) continue;
                    // Random point in the cell, projected onto the sphere: only cells the surface crosses get craters.
                    double px = (i + rng.nextDouble()) * cell, py = (j + rng.nextDouble()) * cell, pz = (k + rng.nextDouble()) * cell;
                    double n = Math.sqrt(px * px + py * py + pz * pz);
                    if (n < 1e-9 || Math.abs(n - rs) > cell) continue;
                    px *= rs / n;
                    py *= rs / n;
                    pz *= rs / n;
                    // Power-law size distribution: small craters dominate, as N(>D) ∝ D^−2.
                    double r = rMin / Math.sqrt(1 - rng.nextDouble() * (1 - (rMin * rMin) / (rMax * rMax)));
                    double dx = x - px, dy = y - py, dz = z - pz;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz) / r;
                    if (d > 1.8) continue;
                    sum += craterProfile(d, r);
                }
            }
        }
        return sum;
    }

    /** Simple-crater profile at normalised distance d = dist/R: parabolic bowl 0.4 R deep, rim 0.06 R high. */
    static double craterProfile(double d, double radius) {
        double depth = 0.4 * radius, rim = 0.06 * radius;
        double rimTerm = rim * Math.exp(-sq((d - 1) / 0.22));
        return d < 1 ? depth * (d * d - 1) + rimTerm : rimTerm;
    }

    // ---------------------------------------------------------------- materials

    /**
     * Fills the layering of a column whose surface Y is {@code h}; {@code slope} is the larger height
     * step to a neighbouring column, used to bare rock on steep ground. Surface materials follow the
     * same macro fields the imager paints: deserts in the Hadley belts, polar caps, snow on the
     * highest ground, dark maria, ice-moon lineae. Clears any band ({@link #carve} adds it).
     */
    public void column(int x, int z, int h, int slope, Column c) {
        PlanetMacro.Sample m = SAMPLE.get();
        macroAt(x, z, m);
        column(x, z, h, slope, c, m);
    }

    /** {@link #column(int, int, int, int, Column)} with the column's macro fields already sampled. */
    public void column(int x, int z, int h, int slope, Column c, PlanetMacro.Sample m) {
        c.height = h;
        c.solidTop = h;
        c.bandLen = 0;
        c.fluid = Material.AIR;
        c.fluidTop = h;
        c.iceCap = false;
        c.under = Material.STONE;
        boolean steep = slope >= 3;
        double tint = Noise.value(seed + 505, x / 9.0, 0, z / 9.0);
        switch (type) {
            case TERRAN -> {
                // Highland measure on the imager's scale: e = (h − sea)/(1.6·A) ≈ macro elevation.
                double e = (h - SEA_LEVEL) / (1.6 * amplitude);
                boolean cap = m.iceCap > 0.5;
                if (h < SEA_LEVEL - 1) {
                    set(c, tint > 0.2 ? Material.GRAVEL : Material.SAND, 2, Material.SAND, 3);
                } else if (h <= SEA_LEVEL + 1) {
                    set(c, cap ? Material.SNOW_BLOCK : Material.SAND, cap ? 1 : 3, Material.SANDSTONE, 3);
                } else if (cap || e + m.lat * 0.25 > 0.53) {
                    set(c, steep ? Material.STONE : Material.SNOW_BLOCK, 1, Material.STONE, 1);
                } else if (steep || e > 0.32) {
                    set(c, Material.STONE, 1, Material.STONE, 1);
                } else if (macro.life()) {
                    if (m.aridity > 0.5) set(c, Material.SAND, 3, Material.SANDSTONE, 4);
                    else set(c, Material.GRASS_BLOCK, 1, Material.DIRT, 3);
                } else {
                    set(c, m.aridity > 0.5 ? Material.COARSE_DIRT : Material.GRAVEL, 2, Material.DIRT, 3);
                }
                water(c, h, Material.WATER, m.iceCap > 0.5);
            }
            case OCEAN -> {
                boolean cap = m.iceCap > 0.5;
                if (cap && h >= SEA_LEVEL) set(c, Material.SNOW_BLOCK, 1, Material.PACKED_ICE, 3);
                else set(c, h < SEA_LEVEL - 1 && tint > 0.3 ? Material.GRAVEL : Material.SAND, 3, Material.SANDSTONE, 4);
                water(c, h, Material.WATER, cap);
            }
            case DESERT -> {
                if (m.iceCap > 0.5) set(c, Material.SNOW_BLOCK, 2, Material.PACKED_ICE, 6);
                else if (steep) set(c, Material.TERRACOTTA, 3, Material.TERRACOTTA, 8);
                else if (m.mare > 0.35) set(c, Material.COARSE_DIRT, 2, Material.TERRACOTTA, 8); // dark basaltic sand
                else set(c, Material.RED_SAND, 3, Material.TERRACOTTA, 8);
            }
            case BARREN -> {
                if (m.mare > 0.5) set(c, steep ? Material.BASALT : Material.SMOOTH_BASALT, 1, Material.BASALT, 6);
                else set(c, steep ? Material.TUFF : Material.GRAVEL, 1, Material.TUFF, 6);
            }
            case ICE -> {
                if (m.crack > 0.35) set(c, Material.TERRACOTTA, 1, Material.PACKED_ICE, 6); // reddish lineae
                else if (m.dirt > 0.5 && tint > 0) set(c, Material.GRAVEL, 1, Material.PACKED_ICE, 6); // dirty ice
                else set(c, steep ? Material.PACKED_ICE : Material.SNOW_BLOCK, 2, Material.BLUE_ICE, 6);
            }
            case LAVA -> {
                if (macro.albedo() > 0.4 && m.mare < 0.4) {
                    set(c, m.moisture > 0.5 ? Material.SNOW_BLOCK : Material.YELLOW_TERRACOTTA, 1, Material.BASALT, 4);
                } else {
                    set(c, tint > 0 ? Material.BASALT : Material.BLACKSTONE, 2, Material.MAGMA_BLOCK, 4);
                }
                c.under = Material.BLACKSTONE;
                water(c, h, Material.LAVA, false);
            }
            case GREENHOUSE -> set(c, steep ? Material.SMOOTH_BASALT : Material.YELLOW_TERRACOTTA, 2, Material.SMOOTH_BASALT, 6);
            default -> set(c, Material.STONE, 1, Material.STONE, 1);
        }
    }

    private void water(Column c, int h, Material fluid, boolean polarIce) {
        if (h >= SEA_LEVEL) return;
        c.fluid = fluid;
        c.fluidTop = SEA_LEVEL;
        c.iceCap = (frozenSeas || polarIce) && fluid == Material.WATER;
    }

    private static void set(Column c, Material top, int topDepth, Material filler, int fillerDepth) {
        c.top = top;
        c.topDepth = topDepth;
        c.filler = filler;
        c.fillerDepth = fillerDepth;
    }

    /**
     * Material at world Y in a column filled by {@link #column} (and {@link #carve}); bedrock floor 1–3 blocks
     * thick. Layers follow the depth below the nearest air above: without a band that is h − y; inside the band
     * it is the carved local depth, and below the band it continues from the band's lowest block.
     */
    public Material materialAt(Column c, int y, int x, int z) {
        if (y <= MIN_Y + bedrockDepth(x, z)) return Material.BEDROCK;
        if (y > c.solidTop) {
            if (y <= c.height) return Material.AIR; // carved off the top of the 2.5D column
            if (c.fluid != Material.AIR && y <= c.fluidTop) return c.iceCap && y == c.fluidTop ? Material.ICE : c.fluid;
            return Material.AIR;
        }
        return layer(c, depthBelowAir(c, y));
    }

    /** Depth of solid Y below the nearest air above it, or −1 when y is air inside the band. */
    public static int depthBelowAir(Column c, int y) {
        if (c.bandLen == 0) return c.height - y;
        int i = y - c.bandLo;
        if (i >= 0) return i < c.bandLen ? c.bandDepth[i] : -1;
        // Below the band: continue from its lowest block (solid → its depth + distance; air → distance − 1).
        return c.bandSolid[0] ? c.bandDepth[0] - i : -i - 1;
    }

    /** Layer material at a local depth (−1 = air). */
    static Material layer(Column c, int depth) {
        if (depth < 0) return Material.AIR;
        if (depth < c.topDepth) return c.top;
        if (depth < c.topDepth + c.fillerDepth) return c.filler;
        return c.under;
    }

    public int bedrockDepth(int x, int z) {
        return (int) (SeedRng.mix(seed ^ (x * 0x9E3779B1L) ^ (z * 0x85EBCA77L)) >>> 62);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double sq(double v) {
        return v * v;
    }
}
