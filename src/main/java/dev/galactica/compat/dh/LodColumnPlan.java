package dev.galactica.compat.dh;

import dev.galactica.planet.TerrainSampler;
import dev.galactica.planet.TerrainSampler.Material;

/**
 * Pure-Java layering of one Distant Horizons LOD column, straight from {@link TerrainSampler}: the
 * same height, layers, fluids and 3D cliff band {@code PlanetChunkGenerator} places, collapsed to a few
 * vertical spans (bedrock, under, filler, top, fluid, ice, air; banded mountain columns add one run per
 * overhang or ledge) instead of 1 792 blocks.
 *
 * <p>Spans are bottom-up, contiguous, and in DH's coordinates: Y relative to the level's minimum
 * build height, {@code [bottom, top)}, every height ≥ 1. That is exactly what DH 3.3.4's
 * {@code LodDataBuilder.validateOrThrowApiDataColumn} accepts (read from the decompiled jar).
 *
 * <p><b>Sky light.</b> Each span carries the sky light at its top block: 15 minus one per
 * light-dampening block above it. Open air and an open fluid surface get 15. Water and ice both
 * dampen sky light by 1 per block (vanilla {@code BlockBehaviour.getLightDampening}: neither is a
 * solid render shape nor propagates sky light down), the same rule DH's own rough-surface generator
 * uses. So under an ice cap the fluid gets 14, and a submerged floor gets 15 − (water + ice depth);
 * the ice block is already counted in that depth. Solid spans below the surface get 0.
 *
 * <p><b>Slope.</b> {@code TerrainSampler.column} bares rock where the height step to a neighbour is
 * ≥ 3 blocks. A coarse LOD column spans {@code step} blocks, so the slope is the neighbour
 * difference divided by {@code step} and rounded to the nearest block: the mean per-block gradient
 * across the column. Rounding up instead would bare rock on every coarse column steeper than
 * 2 blocks/block, more rock than the full-detail terrain it fades into.
 */
public final class LodColumnPlan {
    /** Upper bound of spans per column: bedrock, under, filler, top, fluid, ice, air, plus the cliff band runs. */
    public static final int MAX_SPANS = 8 + 2 * (2 * TerrainSampler.BAND_MAX + 1);

    /** One output span. Reused between columns to keep generation allocation-free. */
    public static final class Span {
        public Material material;
        public int bottom, top;
        public int skyLight;

        void set(Material m, int bottom, int top, int skyLight) {
            this.material = m;
            this.bottom = bottom;
            this.top = top;
            this.skyLight = skyLight;
        }
    }

    private final TerrainSampler.Column column = new TerrainSampler.Column();
    private final Span[] spans = new Span[MAX_SPANS];
    private int count;

    public LodColumnPlan() {
        for (int i = 0; i < MAX_SPANS; i++) spans[i] = new Span();
    }

    public int count() {
        return count;
    }

    public Span span(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException(i);
        return spans[i];
    }

    /**
     * Plans the column at block (x, z) whose 2.5D surface height is {@code h}, including the 3D cliff band
     * ({@link TerrainSampler#carve}) sampled at this column's point, as the chunk generator carves it.
     *
     * @param slope     larger height step to the +x / +z neighbour LOD column, in blocks
     * @param step      LOD column width in blocks (2^detailLevel)
     * @param minY      level minimum build Y (−512 for galactica:surface)
     * @param height    level build height (1792 for galactica:surface)
     */
    public void plan(TerrainSampler t, int x, int z, int h, int slope, int step, int minY, int height) {
        count = 0;
        int maxY = minY + height; // exclusive
        h = Math.max(minY + 1, Math.min(maxY - 2, h));
        int perBlockSlope = perBlockSlope(slope, step);
        t.column(x, z, h, perBlockSlope, column);
        t.carve(x, z, column);
        TerrainSampler.Column c = column;

        boolean wet = c.fluid != Material.AIR && c.fluidTop > h;
        int waterDepth = wet ? c.fluidTop - h : 0;
        int surfaceLight = Math.max(0, 15 - waterDepth);
        int bedrockTop = minY + 1 + t.bedrockDepth(x, z);

        if (c.bandLen == 0) {
            // Solid layers, bottom-up. Depths follow TerrainSampler.materialAt: top occupies depths
            // [0, topDepth), filler [topDepth, topDepth + fillerDepth), under below, bedrock floor.
            layers(c, Math.min(h + 1, bedrockTop), h, h + 1, surfaceLight, minY);
            int airBottom = h + 1;
            if (wet) {
                int fluidTop = Math.min(maxY - 1, c.fluidTop + 1); // exclusive
                if (c.iceCap) {
                    add(c.fluid, h + 1, fluidTop - 1, 14, minY); // one ice block above
                    add(Material.ICE, Math.max(h + 1, fluidTop - 1), fluidTop, 15, minY);
                } else {
                    add(c.fluid, h + 1, fluidTop, 15, minY);
                }
                airBottom = fluidTop;
            }
            add(Material.AIR, airBottom, maxY, 15, minY);
            return;
        }

        // Banded column: below the band the layering follows a virtual surface hv (depth = hv − y), as
        // TerrainSampler.depthBelowAir continues it; inside the band, block by block; above it, open air.
        int lo = c.bandLo, hi = c.bandLo + c.bandLen; // [lo, hi)
        int hv = c.bandSolid(lo) ? lo + TerrainSampler.depthBelowAir(c, lo) : lo - 1;
        layers(c, Math.min(lo, bedrockTop), hv, lo, 0, minY);
        for (int y = lo; y < hi; y++) {
            Material m = t.materialAt(c, y, x, z);
            // Sky light at the top of each run: open sky above the highest solid, a little under an overhang
            // (light from the side), none for buried rock.
            boolean openAbove = y >= c.solidTop;
            boolean airAbove = y + 1 >= hi || !c.bandSolid(y + 1);
            int light = m == Material.AIR ? (y > c.solidTop ? 15 : 12) : openAbove ? 15 : airAbove ? 12 : 0;
            add(m, y, y + 1, light, minY);
        }
        add(Material.AIR, hi, maxY, 15, minY);
    }

    /**
     * Bedrock, under, filler and top for a solid stack whose top block (depth 0) is {@code surface}, clipped
     * to end below {@code limit} (exclusive); {@code topLight} is the sky light of the top span.
     */
    private void layers(TerrainSampler.Column c, int bedrockTop, int surface, int limit, int topLight, int minY) {
        int surfaceTop = surface + 1;
        int fillerBottom = Math.max(bedrockTop, surfaceTop - c.topDepth - c.fillerDepth);
        int topBottom = Math.max(bedrockTop, surfaceTop - c.topDepth);
        add(Material.BEDROCK, minY, bedrockTop, 0, minY);
        add(c.under, bedrockTop, Math.min(limit, fillerBottom), 0, minY);
        add(c.filler, fillerBottom, Math.min(limit, topBottom), 0, minY);
        add(c.top, topBottom, Math.min(limit, surfaceTop), surfaceTop <= limit ? topLight : 0, minY);
    }

    /**
     * Mean per-block height gradient of a column {@code step} blocks wide whose neighbour differs
     * by {@code slope} blocks, rounded half up to whole blocks (the unit {@code TerrainSampler.column}
     * compares against its bare-rock threshold).
     */
    public static int perBlockSlope(int slope, int step) {
        return step <= 1 ? slope : (int) Math.round(slope / (double) step);
    }

    /** Appends a span if it has positive height; Y converted to DH's level-relative coordinates. */
    private void add(Material m, int bottomAbs, int topAbs, int skyLight, int minY) {
        if (topAbs <= bottomAbs) return;
        if (count > 0) {
            Span last = spans[count - 1];
            // Runs of one material merge (band blocks come one at a time); the merged run keeps the light of its top.
            if (last.material == m && last.top == bottomAbs - minY && (last.skyLight == skyLight || m != Material.AIR)) {
                last.top = topAbs - minY;
                last.skyLight = skyLight;
                return;
            }
        }
        spans[count++].set(m, bottomAbs - minY, topAbs - minY, skyLight);
    }

    /** Vanilla light emission of the block (lava 15, magma block 3), so lava worlds glow in the LOD. */
    public static int blockLight(Material m) {
        return switch (m) {
            case LAVA -> 15;
            case MAGMA_BLOCK -> 3;
            default -> 0;
        };
    }

    /** Registry id of the vanilla block each material maps to (mirrors PlanetChunkGenerator's table). */
    public static String blockId(Material m) {
        return switch (m) {
            case AIR -> "minecraft:air";
            case BEDROCK -> "minecraft:bedrock";
            case STONE -> "minecraft:stone";
            case GRAVEL -> "minecraft:gravel";
            case TUFF -> "minecraft:tuff";
            case RED_SAND -> "minecraft:red_sand";
            case TERRACOTTA -> "minecraft:terracotta";
            case PACKED_ICE -> "minecraft:packed_ice";
            case SNOW_BLOCK -> "minecraft:snow_block";
            case BLUE_ICE -> "minecraft:blue_ice";
            case ICE -> "minecraft:ice";
            case BASALT -> "minecraft:basalt";
            case BLACKSTONE -> "minecraft:blackstone";
            case MAGMA_BLOCK -> "minecraft:magma_block";
            case GRASS_BLOCK -> "minecraft:grass_block";
            case DIRT -> "minecraft:dirt";
            case SAND -> "minecraft:sand";
            case SANDSTONE -> "minecraft:sandstone";
            case YELLOW_TERRACOTTA -> "minecraft:yellow_terracotta";
            case SMOOTH_BASALT -> "minecraft:smooth_basalt";
            case WATER -> "minecraft:water";
            case LAVA -> "minecraft:lava";
            case COARSE_DIRT -> "minecraft:coarse_dirt";
        };
    }
}
