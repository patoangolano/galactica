package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.planet.PlanetEntry;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.planet.TerrainSampler;
import dev.galactica.planet.transfer.TransferAltitude;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import dev.galactica.system.SystemGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntBinaryOperator;

/**
 * Autotest scripts for the tall planet terrain and for Earth under Tectonic, kept out of {@link AutoTest}'s main route.
 *
 * <p>{@code mode=terrain}: on Mars, an Earth-like world of a nearby system and the Moon, the server searches a
 * 48 000-block square around the landing point for a feature (highest peak, shield volcano, canyon, banded cliff,
 * coast), parks a spectator camera beside it aimed at it, and shoots a near view (inside the render distance) and a
 * wide one (far terrain, or Distant Horizons LODs with {@code tag=dh}). Each body also checks that the generated
 * chunks' ground matches {@link TerrainSampler} column for column and that the terrain uses the tall slot.
 *
 * <p>{@code mode=tectonic}: the Overworld's live build height and the entry/exit rows derived from it, Earth seen
 * from a Tectonic mountain, then the orbital-frame route ({@link AutoTest#soi}): approach and entry into Earth,
 * take-off from the Overworld, all against the taller Overworld.
 */
final class TerrainAutoTest {
    private TerrainAutoTest() {}

    private enum Feature { PEAK, VOLCANO, CANYON, CLIFF, COAST }

    /** Ticks to let chunks (and LODs) build before a shot. */
    private static int settle(String tag) {
        return "dh".equals(tag) ? 700 : 260;
    }

    static void tour(String tag) {
        AutoTest.cmd(0, "clear");
        AutoTest.cmd(5, "gamemode creative");
        AutoTest.cmd(5, "galactica kit");
        AutoTest.hud(5, false);
        AutoTest.cmd(10, "galactica launch");
        AutoTest.cmd(40, "galactica goto Sol");
        visit(tag, "Mars", Feature.VOLCANO, Feature.CANYON, Feature.CLIFF);
        earthLike(tag);
        visit(tag, "Moon", Feature.PEAK);
        // Earth: the Overworld from 400 blocks up, for the Earth LOD path (DH's own generator) and the Earth sky.
        AutoTest.cmd(5, "gamemode creative");
        AutoTest.cmd(20, "galactica launch");
        AutoTest.cmd(60, "galactica goto Sol");
        AutoTest.step(20, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Earth"));
        AutoTest.cmd(80, "gamemode spectator");
        AutoTest.server(5, "Earth vista", sp -> park(sp, sp.getX(), Math.max(sp.level().getSeaLevel(), sp.level()
                .getHeight(Heightmap.Types.WORLD_SURFACE, (int) sp.getX(), (int) sp.getZ())) + 400, sp.getZ(), 90, 25));
        AutoTest.cmd(5, "time set noon");
        AutoTest.shot(settle(tag), "terrain_earth_vista");
        AutoTest.cmd(5, "gamemode creative");
        AutoTest.hud(5, true);
        AutoTest.cmd(20, "galactica launch");
        AutoTest.cmd(40, "galactica goto Sol");
    }

    /** Lands on a Sol body by name and shoots each feature. */
    private static void visit(String tag, String body, Feature... features) {
        AutoTest.cmd(5, "gamemode creative");
        AutoTest.cmd(20, "galactica launch");
        AutoTest.cmd(60, "galactica goto Sol");
        AutoTest.step(20, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land " + body));
        AutoTest.cmd(60, "galactica ground");
        shootFeatures(tag, body.toLowerCase(Locale.ROOT), features);
    }

    private static void shootFeatures(String tag, String label, Feature... features) {
        AutoTest.cmd(20, "gamemode spectator");
        AutoTest.cmd(5, "time set 3000");
        boolean first = true;
        for (Feature f : features) {
            AutoTest.server(5, label + " " + f, sp -> scout(sp, label, f, false));
            AutoTest.shot(settle(tag), "terrain_" + label + "_" + f.name().toLowerCase(Locale.ROOT) + "_near");
            if (first) {
                AutoTest.server(1, label + " chunks vs sampler", sp -> checkChunks(sp, label));
                first = false;
            }
            AutoTest.server(5, label + " " + f + " wide", sp -> scout(sp, label, f, true));
            AutoTest.shot(settle(tag), "terrain_" + label + "_" + f.name().toLowerCase(Locale.ROOT) + "_wide");
        }
    }

    /** Hyperjumps to the nearest system with a temperate (TERRAN) world and lands on it. */
    private static volatile String earthLikeName;

    private static void earthLike(String tag) {
        AutoTest.cmd(5, "gamemode creative");
        AutoTest.cmd(20, "galactica launch");
        AutoTest.cmd(60, "galactica goto Sol");
        AutoTest.server(20, "find an Earth-like world", TerrainAutoTest::jumpToEarthLike);
        AutoTest.step(200, () -> {
            String n = earthLikeName;
            if (n != null) Minecraft.getInstance().player.connection.sendCommand("galactica land " + n);
        });
        AutoTest.cmd(60, "galactica ground");
        shootFeatures(tag, "earthlike", Feature.PEAK, Feature.COAST, Feature.CLIFF);
    }

    private static void jumpToEarthLike(ServerPlayer sp) {
        earthLikeName = null;
        GalacticPos g = Frames.playerPos(sp.getAttachedOrCreate(Galactica.NAV), sp.getX(), sp.getY(), sp.getZ());
        double x = g.xPc(), y = g.yPc(), z = g.zPc();
        List<Star> stars = NavServer.neighbourhood(sp).nearbyStars(g).stream()
                .sorted(Comparator.comparingDouble(s -> s.distanceTo(x, y, z))).toList();
        for (Star s : stars) {
            if ("Sol".equals(s.name())) continue;
            for (Body b : SystemGenerator.generate(s)) {
                if (b.type() != BodyType.TERRAN) continue;
                earthLikeName = b.name();
                dev.galactica.ship.Hyperdrive.arriveAt(sp, s);
                Galactica.LOG.info("[autotest] Earth-like world {} ({} g, life {}) at {}", b.name(),
                        String.format(Locale.ROOT, "%.2f", b.surfaceGravityG()), b.life(), s.name());
                return;
            }
        }
        Galactica.LOG.warn("[autotest] FAIL no Earth-like (TERRAN) world among {} nearby systems", stars.size());
    }

    // ---------------------------------------------------------------- server side

    /** Spectator perf scene: {@code offset} blocks east, {@code above} blocks over the ground there (or the sea). */
    static void hoverOverTerrain(ServerPlayer sp, long offset, int above) {
        double x = sp.getX() + offset, z = sp.getZ();
        int slot = PlanetSlots.slotOf(sp.level().dimension());
        int ground = slot >= 0 ? Math.max(PlanetSlots.terrain(slot).solidTop((int) x, (int) z), TerrainSampler.SEA_LEVEL) : 300;
        park(sp, x, ground + above, z, sp.getYRot(), sp.getXRot());
        Galactica.LOG.info("[autotest] hovering {} blocks over fresh ground at y={}", above, ground);
    }

    private static void park(ServerPlayer sp, double x, double y, double z, float yaw, float pitch) {
        sp.teleport(new TeleportTransition((ServerLevel) sp.level(), new Vec3(x, y, z), Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING));
    }

    /**
     * Finds {@code f} within 24 000 blocks of the player and parks the camera beside it: {@code wide} views from
     * 1 600 blocks and well above, the near ones from 200 blocks (inside the render distance).
     */
    private static void scout(ServerPlayer sp, String label, Feature f, boolean wide) {
        int slot = PlanetSlots.slotOf(sp.level().dimension());
        if (slot < 0 || PlanetSlots.runtime(slot) == null) {
            Galactica.LOG.warn("[autotest] FAIL terrain {} {}: not on a planet slot ({})", label, f, sp.level().dimension().identifier());
            return;
        }
        TerrainSampler t = PlanetSlots.terrain(slot);
        Target target = Target.find(t, (int) sp.getX(), (int) sp.getZ(), f, wide ? Target.last(label, f) : null);
        if (target == null) {
            Galactica.LOG.warn("[autotest] FAIL terrain {} {}: no such feature within 24 000 blocks", label, f);
            return;
        }
        Target.remember(label, f, target);
        aimFrom(sp, (x, z) -> t.solidTop(x, z), target, wide, Math.max(TerrainSampler.SEA_LEVEL, 0));
        Galactica.LOG.info("[autotest] PASS terrain {} {}: {} at ({}, {}) y={} ({} blocks above sea; peak law {} blocks, vertical scale {})",
                label, f.name().toLowerCase(Locale.ROOT), wide ? "wide view" : "near view", target.x, target.z, target.y,
                target.y - TerrainSampler.SEA_LEVEL, String.format(Locale.ROOT, "%.0f", t.peakBlocks()),
                String.format(Locale.ROOT, "%.3f", t.verticalScale()));
    }

    /** Parks the camera {@code wide ? 1600 : 200} blocks from the target on its lowest side and aims at it. */
    private static void aimFrom(ServerPlayer sp, IntBinaryOperator ground, Target target, boolean wide, int floorY) {
        double dist = wide ? 1600 : 200;
        double bestX = 0, bestZ = 0;
        int bestH = Integer.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            double x = target.x + dist * Math.cos(a), z = target.z + dist * Math.sin(a);
            int h = Math.max(floorY, ground.applyAsInt((int) x, (int) z));
            if (h < bestH) {
                bestH = h;
                bestX = x;
                bestZ = z;
            }
        }
        double rise = Math.max(0, target.y - bestH);
        double camY = wide ? Math.max(bestH, target.y) + 0.35 * dist : bestH + 0.45 * rise + 25;
        double aimY = target.feature == Feature.CANYON || target.feature == Feature.COAST ? target.y : target.y - (wide ? 0.4 * rise : 0.15 * rise);
        double dx = target.x - bestX, dz = target.z - bestZ, dy = aimY - camY;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        park(sp, bestX, camY, bestZ, yaw, pitch);
    }

    /** A located feature: its column and surface Y. */
    private record Target(int x, int z, int y, Feature feature) {
        private static final java.util.Map<String, Target> LAST = new java.util.concurrent.ConcurrentHashMap<>();

        static Target last(String label, Feature f) {
            return LAST.get(label + f);
        }

        static void remember(String label, Feature f, Target t) {
            LAST.put(label + f, t);
        }

        /** Best grid point (400-block steps over ±24 000 blocks) for the feature, or {@code reuse}. */
        static Target find(TerrainSampler t, int cx, int cz, Feature f, Target reuse) {
            if (reuse != null) return reuse;
            int n = 120, step = 400;
            int[] h = new int[n * n];
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++) h[j * n + i] = t.height(cx + (i - n / 2) * step, cz + (j - n / 2) * step);
            double best = Double.NEGATIVE_INFINITY;
            int bi = -1, bj = -1;
            for (int j = 2; j < n - 2; j++) {
                for (int i = 2; i < n - 2; i++) {
                    int x = cx + (i - n / 2) * step, z = cz + (j - n / 2) * step, y = h[j * n + i];
                    double score = switch (f) {
                        case PEAK -> y;
                        case VOLCANO -> t.volcanoAt(x, z);
                        case CANYON -> t.canyonAt(x, z);
                        case CLIFF -> t.bandWeight(x, z) > 0.6 ? y : Double.NEGATIVE_INFINITY;
                        case COAST -> {
                            if (y < TerrainSampler.SEA_LEVEL + 1 || y > TerrainSampler.SEA_LEVEL + 12) yield Double.NEGATIVE_INFINITY;
                            int high = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
                            for (int dj = -2; dj <= 2; dj++)
                                for (int di = -2; di <= 2; di++) {
                                    int v = h[(j + dj) * n + i + di];
                                    high = Math.max(high, v);
                                    low = Math.min(low, v);
                                }
                            yield low < TerrainSampler.SEA_LEVEL - 20 ? high : Double.NEGATIVE_INFINITY;
                        }
                    };
                    if (score > best) {
                        best = score;
                        bi = i;
                        bj = j;
                    }
                }
            }
            if (bi < 0 || best == Double.NEGATIVE_INFINITY || (f != Feature.PEAK && f != Feature.CLIFF && f != Feature.COAST && best <= 1)) return null;
            int x = cx + (bi - n / 2) * step, z = cz + (bj - n / 2) * step;
            // Refine on a 25-block grid around the coarse hit: the summit, the canyon floor, the coast line.
            int rx = x, rz = z;
            double rb = Double.NEGATIVE_INFINITY;
            for (int dz = -200; dz <= 200; dz += 25) {
                for (int dx = -200; dx <= 200; dx += 25) {
                    double s = switch (f) {
                        case PEAK, CLIFF -> t.height(x + dx, z + dz);
                        case VOLCANO -> t.volcanoAt(x + dx, z + dz);
                        case CANYON -> t.canyonAt(x + dx, z + dz) - 0.001 * Math.abs(t.height(x + dx, z + dz));
                        case COAST -> -Math.abs(t.height(x + dx, z + dz) - TerrainSampler.SEA_LEVEL - 2);
                    };
                    if (s > rb) {
                        rb = s;
                        rx = x + dx;
                        rz = z + dz;
                    }
                }
            }
            return new Target(rx, rz, t.solidTop(rx, rz), f);
        }
    }

    /**
     * The chunks the server generated around the camera against the sampler: the ocean-floor heightmap of every
     * loaded column within 96 blocks must sit right above {@link TerrainSampler#solidTop} (vegetation aside), and
     * the slot must span the new range.
     */
    private static void checkChunks(ServerPlayer sp, String label) {
        ServerLevel level = (ServerLevel) sp.level();
        int slot = PlanetSlots.slotOf(level.dimension());
        if (slot < 0) return;
        TerrainSampler t = PlanetSlots.terrain(slot);
        int checked = 0, match = 0, maxSeen = Integer.MIN_VALUE;
        for (int dz = -96; dz <= 96; dz += 6) {
            for (int dx = -96; dx <= 96; dx += 6) {
                int x = (int) Math.floor(sp.getX()) + dx, z = (int) Math.floor(sp.getZ()) + dz;
                LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (c == null) continue;
                int got = c.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
                int want = t.solidTop(x, z);
                checked++;
                if (got == want) match++;
                maxSeen = Math.max(maxSeen, got);
            }
        }
        boolean range = level.getMinY() == TerrainSampler.MIN_Y && level.getMaxY() == TerrainSampler.MAX_Y;
        boolean ok = checked >= 100 && match >= 0.9 * checked && range;
        Galactica.LOG.info("[autotest] {} terrain {} chunks match the sampler: {}/{} columns, slot Y [{}, {}], highest ground seen {}",
                ok ? "PASS" : "FAIL", label, match, checked, level.getMinY(), level.getMaxY(), maxSeen);
    }

    // ---------------------------------------------------------------- Tectonic Earth

    static void tectonic(String tag) {
        AutoTest.cmd(0, "clear");
        AutoTest.cmd(5, "gamemode creative");
        AutoTest.cmd(5, "execute in minecraft:overworld run tp @s 0.5 400 0.5");
        AutoTest.server(40, "Overworld build height and transfer rows", TerrainAutoTest::checkRows);
        AutoTest.cmd(5, "gamemode spectator");
        AutoTest.cmd(5, "time set 3000");
        AutoTest.hud(5, false);
        AutoTest.server(5, "highest Tectonic ground near spawn", sp -> earthPeak(sp, false));
        AutoTest.shot(settle(tag), "tectonic_earth_mountain_near");
        AutoTest.server(5, "Tectonic mountains wide", sp -> earthPeak(sp, true));
        AutoTest.shot(settle(tag), "tectonic_earth_mountain_wide");
        AutoTest.hud(5, true);
        AutoTest.cmd(5, "gamemode creative");
        // Earth entry from orbit onto the taller Overworld, suited in survival (the descent flight holds the arrival
        // row): the arrival row and the ground under it.
        AutoTest.cmd(5, "gamemode survival");
        AutoTest.cmd(5, "galactica kit");
        AutoTest.cmd(10, "galactica launch");
        AutoTest.cmd(40, "galactica goto Sol");
        AutoTest.step(10, () -> Minecraft.getInstance().player.connection.sendCommand("galactica land Earth"));
        AutoTest.server(15, "Earth arrival row", TerrainAutoTest::checkArrival);
        AutoTest.step(5, () -> dev.galactica.client.planet.PlanetAutoTest.checkLanded("Earth", 1.0));
        AutoTest.cmd(5, "galactica ground");
        AutoTest.server(20, "Earth ground", TerrainAutoTest::checkGround);
        // Approach, entry, take-off and EVA against the taller Overworld.
        AutoTest.soi();
    }

    private static void checkRows(ServerPlayer sp) {
        ServerLevel ow = sp.level().getServer().overworld();
        double entry = PlanetEntry.entryY(ow), exit = PlanetEntry.exitY(ow);
        boolean ok = entry - 64 > ow.getMaxY() && entry - 100 > ow.getMaxY() && exit - entry == TransferAltitude.EXIT_ABOVE_ENTRY;
        Galactica.LOG.info("[autotest] {} tectonic rows: Overworld Y [{}, {}] (vanilla [-64, 319]), sea level {}, entry row {}, exit row {}",
                ok ? "PASS" : "FAIL", ow.getMinY(), ow.getMaxY(), ow.getSeaLevel(), entry, exit);
    }


    private static void checkArrival(ServerPlayer sp) {
        Level l = sp.level();
        double y = sp.getRootVehicle().getY(), entry = PlanetEntry.entryY(l);
        boolean ok = l.dimension() == Level.OVERWORLD && y > l.getMaxY() && y <= entry + 1 && y >= entry - 80;
        Galactica.LOG.info("[autotest] {} tectonic Earth arrival at y={} (entry row {}, Overworld top {})", ok ? "PASS" : "FAIL",
                String.format(Locale.ROOT, "%.1f", y), entry, l.getMaxY());
    }

    private static void checkGround(ServerPlayer sp) {
        Level l = sp.level();
        int x = (int) Math.floor(sp.getX()), z = (int) Math.floor(sp.getZ());
        int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        boolean ok = l.dimension() == Level.OVERWORLD && Math.abs(sp.getY() - top) < 1.5 && top > l.getMinY() && top <= l.getMaxY() + 1;
        Galactica.LOG.info("[autotest] {} tectonic Earth ground at y={} (heightmap {}, Overworld Y [{}, {}])", ok ? "PASS" : "FAIL",
                String.format(Locale.ROOT, "%.1f", sp.getY()), top, l.getMinY(), l.getMaxY());
    }

    /** Highest Overworld ground within 6 000 blocks of spawn, from the generator's own height query (no chunks). */
    private static void earthPeak(ServerPlayer sp, boolean wide) {
        ServerLevel ow = sp.level().getServer().overworld();
        var gen = ow.getChunkSource().getGenerator();
        var rs = ow.getChunkSource().randomState();
        IntBinaryOperator ground = (x, z) -> gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ow, rs) - 1;
        Target t = Target.last("earth", Feature.PEAK);
        if (t == null) {
            int bestY = Integer.MIN_VALUE, bx = 0, bz = 0;
            for (int z = -6000; z <= 6000; z += 300)
                for (int x = -6000; x <= 6000; x += 300) {
                    int y = ground.applyAsInt(x, z);
                    if (y > bestY) {
                        bestY = y;
                        bx = x;
                        bz = z;
                    }
                }
            int rx = bx, rz = bz;
            for (int dz = -150; dz <= 150; dz += 15)
                for (int dx = -150; dx <= 150; dx += 15) {
                    int y = ground.applyAsInt(bx + dx, bz + dz);
                    if (y > bestY) {
                        bestY = y;
                        rx = bx + dx;
                        rz = bz + dz;
                    }
                }
            t = new Target(rx, rz, bestY, Feature.PEAK);
            Target.remember("earth", Feature.PEAK, t);
        }
        if (sp.level() != ow) sp.teleport(new TeleportTransition(ow, sp.position(), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        aimFrom(sp, ground, t, wide, ow.getSeaLevel());
        boolean ok = t.y > 200 && t.y <= ow.getMaxY();
        Galactica.LOG.info("[autotest] {} tectonic Earth highest ground near spawn y={} at ({}, {}) ({} view; vanilla terrain tops out near 256)",
                ok ? "PASS" : "FAIL", t.y, t.x, t.z, wide ? "wide" : "near");
    }
}
