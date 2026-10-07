package dev.galactica.client.ship;

import dev.galactica.Galactica;
import dev.galactica.client.planet.SurfaceSkyOverlay;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.planet.TerrainSampler;
import dev.galactica.science.Blackbody;
import dev.galactica.science.Units;
import dev.galactica.ship.ExhaustPhysics;
import dev.galactica.ship.ShipLighting;
import dev.galactica.ship.Starship;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * What surrounds a ship, for its renderer: the host star's direction, flux and colour in space, and the ambient
 * pressure (space, a Galactica planet's barometric atmosphere, or a vanilla world's sea-level air).
 *
 * <p>The star lookup is the sky's own ({@link SkyManager}'s {@link Neighbourhood}); its result is cached for one
 * client tick, since every ship near the viewer shares the host star.
 */
public final class ShipEnvironment {
    private ShipEnvironment() {}

    /** Vanilla worlds: ISA sea-level pressure at Y 63, scale height 8.5 km at 1 block = 1 m (US Std. Atm. 1976). */
    private static final double EARTH_SEA_LEVEL_BAR = 1.01325, EARTH_SCALE_HEIGHT_M = 8500.0;
    private static final int VANILLA_SEA_LEVEL = 63;

    private static long cachedTick = Long.MIN_VALUE;
    private static Star cachedStar;
    /** Render-thread scratch for the star's blackbody colour. */
    private static final float[] BLACKBODY = new float[3];

    /** Fills the lighting and pressure fields of {@code state} for a ship at its interpolated position. */
    public static void sample(Starship ship, StarshipRenderState state) {
        Level level = ship.level();
        state.starLit = level.dimension() == Galactica.SPACE;
        state.toStar.zero();
        state.direct = 0F;
        state.tint[0] = state.tint[1] = state.tint[2] = 1F;
        if (state.starLit) {
            state.ambientBar = 0F;
            starLight(level, state);
            return;
        }
        Body body = SurfaceSkyOverlay.currentBody();
        if (body != null) {
            state.ambientBar = (float) ExhaustPhysics.barometric(body.surfacePressureBar(),
                    state.y - TerrainSampler.SEA_LEVEL, body.scaleHeight());
        } else {
            state.ambientBar = (float) ExhaustPhysics.barometric(EARTH_SEA_LEVEL_BAR, state.y - seaLevel(level), EARTH_SCALE_HEIGHT_M);
        }
    }

    /** The level's own sea level (63 in vanilla and Tectonic), falling back to vanilla's when the level reports none. */
    private static int seaLevel(Level level) {
        int s = level.getSeaLevel();
        return s > level.getMinY() ? s : VANILLA_SEA_LEVEL;
    }

    /** Packed light for the lightmap routes in space: bright inside a system, dim but visible far from stars. */
    public static int lightCoords(float direct) {
        int level = Math.round(5F + 10F * Math.clamp(direct, 0F, 1F));
        return level << 20 | level << 4;
    }

    /** Render-thread scratch for {@link #passengerLight}. */
    private static final StarshipRenderState PASSENGER = new StarshipRenderState();

    /**
     * Light for an entity riding {@code ship} (the pilot's body and first-person arm), or -1 outside space. The
     * vanilla lightmap is zero there, which draws the pilot as a black silhouette against a sunlit hull.
     */
    public static int passengerLight(Starship ship, float partialTicks) {
        if (ship.level().dimension() != Galactica.SPACE) return -1;
        var pos = ship.getPosition(partialTicks);
        StarshipRenderState s = PASSENGER;
        s.x = pos.x;
        s.y = pos.y;
        s.z = pos.z;
        sample(ship, s);
        return lightCoords(s.direct);
    }

    /** Render-thread scratch for {@link #evaLight}. */
    private static final StarshipRenderState EVA = new StarshipRenderState();

    /**
     * Light for an entity in open space, not aboard a ship (an EVA player's body and first-person arm), or -1 outside
     * space: the same starlight route as a ship's crew, from the system's star at the entity's position, with the
     * {@link #lightCoords} floor far from any star. Starlight is direct light only (there is no sky to scatter it), so
     * an entity whose line to the star is blocked within {@link #EVA_SHADOW_REACH} blocks (inside a station, behind an
     * asteroid) gets just the ambient floor; vanilla's block light (torches, lamps) still adds on top of it.
     */
    public static int evaLight(net.minecraft.world.entity.Entity entity, float partialTicks) {
        Level level = entity.level();
        if (level.dimension() != Galactica.SPACE) return -1;
        var pos = entity.getPosition(partialTicks);
        StarshipRenderState s = sampleSpace(level, pos.x, pos.y, pos.z, EVA);
        if (s.direct > 0F && s.toStar.lengthSquared() > 0.5F && starBlocked(level, entity.getEyePosition(partialTicks), s.toStar)) s.direct = 0F;
        return lightCoords(s.direct);
    }

    /** How far (blocks) a build can shade an EVA player from the star. */
    public static final double EVA_SHADOW_REACH = 96.0;

    private static boolean starBlocked(Level level, net.minecraft.world.phys.Vec3 eye, org.joml.Vector3fc toStar) {
        net.minecraft.world.phys.Vec3 to = eye.add(toStar.x() * EVA_SHADOW_REACH, toStar.y() * EVA_SHADOW_REACH, toStar.z() * EVA_SHADOW_REACH);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Fills the starlight fields of {@code state} (toStar, direct, tint) for a point in space; returns {@code state}. */
    public static StarshipRenderState sampleSpace(Level level, double x, double y, double z, StarshipRenderState state) {
        state.x = x;
        state.y = y;
        state.z = z;
        state.starLit = true;
        state.toStar.zero();
        state.direct = 0F;
        state.tint[0] = state.tint[1] = state.tint[2] = 1F;
        state.ambientBar = 0F;
        starLight(level, state);
        return state;
    }

    private static void starLight(Level level, StarshipRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        SkyManager sky = SkyManager.get();
        NavState nav = sky.nav() != null ? sky.nav() : mc.player.getAttached(Galactica.NAV);
        if (nav == null) return;
        GalacticPos here = Frames.playerPos(nav, state.x, state.y, state.z);
        long tick = level.getGameTime();
        if (tick != cachedTick) {
            cachedTick = tick;
            cachedStar = sky.neighbourhood().nearestStar(here);
        }
        Star star = cachedStar;
        if (star == null || star.kind() == StarKind.BLACK_HOLE) return;
        GalacticPos sp = Neighbourhood.starPos(star);
        double[] g = sp.metresFrom(here);
        double dist = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (dist > Neighbourhood.SYSTEM_RADIUS_M || dist <= 0) return; // interstellar: ambient floor only
        double[] mc3 = Frames.galacticToMc(g[0], g[1], g[2]);
        state.toStar.set((float) mc3[0], (float) mc3[1], (float) mc3[2]).normalize();
        double s = ShipLighting.insolation(star.luminosity(), dist / Units.AU);
        state.direct = (float) (ShipLighting.directStrength(s) * ShipLighting.systemFade(dist, Neighbourhood.SYSTEM_RADIUS_M));
        float[] bb = BLACKBODY;
        Blackbody.linearRgb(Math.max(1000, star.temperature()), bb);
        ShipLighting.tint(bb, state.tint);
    }
}
