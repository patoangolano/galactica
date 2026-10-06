package dev.galactica.planet;

import dev.galactica.science.Noise;
import dev.galactica.science.SeedRng;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;

/**
 * Surface terrain of a landable body as a pure function of block (x, z). Every noise field is
 * sampled on the map sphere ({@link SurfaceGrid#spherePoint}), so the surface closes on itself at
 * the x seam and shrinks honestly towards the poles.
 *
 * <p><b>One source of truth.</b> Planet-scale shape (continents, seas, deserts, ice caps, maria,
 * impact basins) comes from {@link PlanetMacro}, the same fields the orbital imager paints; this
 * class adds the macro field's fractal tail and block-scale relief. On bodies with a sea the
 * coastline is exactly where the macro elevation crosses zero, so the sea you saw from orbit is
 * the sea you land in.
 *
 * <p><b>Relief.</b> Topographic amplitude scales as 1/g: the height a crust can support before it
 * flows goes as σ/(ρg) (Melosh 2011, ch. 4), which is why Olympus Mons (Mars, 0.38 g) stands 2.5× as
 * tall as Mauna Kea's base-to-peak. It is clamped to 20–250 blocks so Earth-like worlds still have
 * hills and airless pebbles stay walkable: Earth 60, Mars 158, the Moon 250.
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

    /** Fluid surface and the world Y every body's datum sits at. */
    public static final int SEA_LEVEL = 96;
    /** Generator vertical range, matching dimension_type galactica:surface (min_y −256, height 768). */
    public static final int MIN_Y = -256, MAX_Y = 511;
    public static final int MIN_RELIEF = 20, MAX_RELIEF = 250;
    /** Relief of a 1 g world, blocks. */
    public static final double RELIEF_AT_1G = 60;

    /** One column's layering, filled by {@link #column}. Reused to keep generation allocation-free. */
    public static final class Column {
        public int height;
        public Material top, filler, under;
        public int topDepth, fillerDepth;
        /** Fluid fills (height, fluidTop]; NONE when fluid == AIR. */
        public Material fluid = Material.AIR;
        public int fluidTop;
        /** Frozen sea surface replaces the top fluid block. */
        public boolean iceCap;

        /** The material seen from above: frozen sea, fluid surface or the top solid block. */
        public Material visible() {
            if (fluid != Material.AIR) return iceCap ? Material.ICE : fluid;
            return top;
        }
    }

    private final BodyType type;
    private final long seed;
    private final SurfaceGrid grid;
    private final double amplitude;
    private final boolean cratered;
    private final boolean frozenSeas;
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
        this.amplitude = reliefFor(gravityG);
        this.cratered = cratered;
        this.frozenSeas = frozenSeas;
    }

    public static TerrainSampler of(Body b, SurfaceGrid grid) {
        return new TerrainSampler(PlanetMacro.of(b), b.surfaceGravityG(), grid,
                SurfaceAtmosphere.cratered(b), b.surfaceTemp() < 271);
    }

    /** Stand-in for a slot with no body assigned: rolling grey barren ground. */
    public static TerrainSampler placeholder() {
        return new TerrainSampler(BodyType.BARREN, 0L, 1.0, SurfaceGrid.of(1.0e5), false, false);
    }

    /** Relief amplitude in blocks for a surface gravity in Earth g: 60/g clamped to [20, 250]. */
    public static double reliefFor(double gravityG) {
        double r = RELIEF_AT_1G / Math.max(1e-6, gravityG);
        return Math.max(MIN_RELIEF, Math.min(MAX_RELIEF, r));
    }

    public double amplitude() {
        return amplitude;
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

    /** Solid surface Y (top solid block) of column (x, z). */
    public int height(int x, int z) {
        return height((double) x, (double) z);
    }

    /**
     * Solid surface Y at a (possibly fractional) map position. On bodies with a sea the land/sea
     * decision is exactly the sign of the macro elevation plus its fractal tail, and local relief is
     * capped so it never pushes ground across sea level: land sits at least one block above the sea
     * surface, sea floors at least one block below it.
     */
    public int height(double x, double z) {
        double[] s = POINT.get();
        grid.spherePoint(x, z, s);
        double r = grid.radiusBlocks();
        double ux = s[0] / r, uy = s[2] / r, uz = s[1] / r;
        PlanetMacro.Sample m = SAMPLE.get();
        macro.sample(ux, uy, uz, m);
        double macroE = m.elevation + macro.tail(ux, uy, uz, r);
        double e = elevation(s[0], s[1], s[2], macroE);
        double h;
        if (macro.hasSea()) {
            if (macroE >= 0) h = Math.floor(SEA_LEVEL + 1 + e * amplitude);
            else h = Math.min(SEA_LEVEL - 1, Math.floor(SEA_LEVEL + e * amplitude));
            if (cratered) h = macroE >= 0 ? Math.max(SEA_LEVEL + 1, h + craters(s[0], s[1], s[2]))
                    : Math.min(SEA_LEVEL - 1, h + craters(s[0], s[1], s[2]));
        } else {
            h = SEA_LEVEL + e * amplitude;
            if (cratered) h += craters(s[0], s[1], s[2]);
        }
        return (int) Math.max(MIN_Y + 8, Math.min(MAX_Y - 16, Math.floor(h)));
    }

    /**
     * Normalised elevation, ~[−1.5, 1.5], before scaling by the relief amplitude: the macro elevation
     * (planet scale, shared with the imager) plus block-scale relief sampled on the map sphere at
     * (x, y, z). On sea-bearing bodies the result keeps the sign of {@code macroE}.
     */
    double elevation(double x, double y, double z, double macroE) {
        double detail = Noise.fbm(seed + 101, x / 220, y / 220, z / 220, 4);
        double ridged = 1 - 2 * Math.abs(Noise.fbm(seed + 202, x / 700, y / 700, z / 700, 4));
        double mountains = ridged > 0 ? ridged * ridged : 0;
        return switch (type) {
            case TERRAN -> coastal(1.6, macroE, 0.25 * detail, 0.9 * mountains);
            case OCEAN -> coastal(1.4, macroE, 0.2 * detail, 0.4 * mountains);
            case LAVA -> coastal(1.2, macroE, 0.35 * detail, 0.5 * mountains);
            case DESERT -> 1.6 * macroE + 0.3 * detail + 0.6 * mountains
                    + 0.08 * Math.abs(Noise.fbm(seed + 303, x / 36, y / 36, z / 36, 2)) * 4; // dunes
            case BARREN -> 1.2 * macroE + 0.3 * detail + 0.4 * mountains;
            case ICE -> {
                // Europa-like lineae at block scale: narrow grooves where a mid-scale field crosses zero.
                double l = Noise.fbm(seed + 404, x / 300, y / 300, z / 300, 3);
                double groove = Math.max(0, 1 - Math.abs(l) * 14);
                yield 1.2 * macroE + 0.15 * detail - 0.25 * groove + 0.2 * mountains;
            }
            case GREENHOUSE -> {
                // Venusian tesserae: plateaus broken into terraces.
                double e0 = 1.4 * macroE + 0.3 * detail;
                double step = Math.floor(e0 * 6) / 6;
                yield step + 0.35 * (e0 - step) + 0.3 * mountains;
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

    /** Macro fields at map position (x, z), written into {@code out} (imager resolution, no tail). */
    public void macroAt(double x, double z, PlanetMacro.Sample out) {
        double[] d = POINT.get();
        direction(x, z, d);
        macro.sample(d[0], d[1], d[2], out);
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
     * highest ground, dark maria, ice-moon lineae.
     */
    public void column(int x, int z, int h, int slope, Column c) {
        c.height = h;
        c.fluid = Material.AIR;
        c.fluidTop = h;
        c.iceCap = false;
        c.under = Material.STONE;
        boolean steep = slope >= 3;
        double tint = Noise.value(seed + 505, x / 9.0, 0, z / 9.0);
        PlanetMacro.Sample m = SAMPLE.get();
        macroAt(x, z, m);
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

    /** Material at world Y in a column filled by {@link #column}; bedrock floor 1–3 blocks thick. */
    public Material materialAt(Column c, int y, int x, int z) {
        if (y <= MIN_Y + bedrockDepth(x, z)) return Material.BEDROCK;
        if (y > c.height) {
            if (c.fluid != Material.AIR && y <= c.fluidTop) return c.iceCap && y == c.fluidTop ? Material.ICE : c.fluid;
            return Material.AIR;
        }
        int depth = c.height - y;
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
