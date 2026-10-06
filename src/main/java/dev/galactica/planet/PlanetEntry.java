package dev.galactica.planet;

import dev.galactica.Galactica;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Landing and take-off between galactica:space and the surface slots (spec §5).
 *
 * <p><b>Entry.</b> A player (or the ship they ride) that drops below the top of a landable body's
 * atmosphere ({@link Body#atmosphereTopMetres()}, or {@link #AIRLESS_ENTRY_M} for airless bodies) is
 * moved, vehicle and passengers together, to the body's slot dimension at {@link #ENTRY_Y} above the
 * sub-observer point: the latitude and longitude directly beneath them at that instant, through
 * {@link BodyFrame} and {@link SurfaceGrid}. A suited player without a ship keeps flight for the
 * descent and loses it on touchdown, on climbing or after a timeout in survival ({@link EvaDescent}),
 * so an EVA landing is a controlled one. Air thicker than 0.1 bar glows for the first seconds
 * ({@link EntryHeating}): flame and smoke around the arrival here, a HUD tint on the client.
 *
 * <p><b>Exit.</b> Rising above {@link #EXIT_Y} returns to space on the same radial, two radii from the
 * centre (farther for tiny moons, so the exit never lands inside the entry shell again).
 *
 * <p><b>Gravity.</b> The GRAVITY attribute gets a total multiplier of g/g⊕, the same lever
 * {@code NavServer} uses for zero-g: Mars falls at 0.38 of vanilla's 0.08 blocks/tick². Safe fall
 * distance and fall damage follow impact energy ({@link SurfaceGravity}), for the player and a living
 * mount alike; every other entity is scaled in {@code Entity.getGravity()} ({@link PlanetGravity}).
 *
 * <p><b>Map edges.</b> East and west wrap; walking over a pole arrives on the far meridian
 * ({@link SurfaceGrid#overPole}).
 */
public final class PlanetEntry {
    private PlanetEntry() {}

    public static final double ENTRY_Y = 600, EXIT_Y = 1200;
    /** Entry shell of a body with no air: low orbit altitude for the Moon, high enough to brake in. */
    public static final double AIRLESS_ENTRY_M = 100_000;
    /** No re-entry for 5 s after leaving, so a take-off cannot bounce straight back down. */
    private static final int REENTRY_COOLDOWN_TICKS = 100;

    private static final Identifier GRAVITY = Galactica.id("planet_gravity");
    private static final Identifier SAFE_FALL = Galactica.id("planet_safe_fall");
    private static final Identifier FALL_DAMAGE = Galactica.id("planet_fall_damage");
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    /** Per-player arrival bookkeeping: lowest Y of an EVA descent, and when they arrived (heating, timeout). */
    private static final Map<UUID, Arrival> ARRIVALS = new HashMap<>();

    private static final class Arrival {
        final long tick;
        double lowestY;

        Arrival(long tick, double y) {
            this.tick = tick;
            this.lowestY = y;
        }
    }
    private static final Map<UUID, Long> NO_SLOT_WARNED = new HashMap<>();

    public static final AttachmentType<SurfaceState> SURFACE = AttachmentRegistry.create(Galactica.id("surface"),
            b -> b.persistent(SurfaceState.CODEC)
                    .copyOnDeath()
                    .syncWith(SurfaceState.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

    /** Registers the generator and biome source codecs and the slot lifecycle. Call from onInitialize. */
    public static void init() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Galactica.id("planet"), PlanetChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Galactica.id("planet"), PlanetBiomeSource.CODEC);
        ServerLifecycleEvents.SERVER_STARTING.register(PlanetSlots::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            PlanetSlots.onServerStopped(s);
            COOLDOWN.clear();
            NO_SLOT_WARNED.clear();
            ARRIVALS.clear();
        });
    }

    /** Altitude above the surface where entry triggers. */
    public static double entryAltitude(Body b) {
        double top = b.atmosphereTopMetres();
        return top > 0 ? top : AIRLESS_ENTRY_M;
    }

    /** Distance from the centre where a take-off puts you: two radii, or clear of the entry shell. */
    public static double exitDistance(Body b) {
        return Math.max(2 * b.radiusMetres(), b.radiusMetres() + 1.5 * entryAltitude(b));
    }

    // ---------------------------------------------------------------- entry

    /** Called every tick for players in galactica:space; lands them if they are inside an entry shell. */
    public static boolean tryEnter(ServerPlayer p) {
        if (p.level().dimension() != Galactica.SPACE || p.isSpectator()) return false;
        Integer until = COOLDOWN.get(p.getUUID());
        if (until != null) {
            if (p.level().getServer().getTickCount() < until) return false;
            COOLDOWN.remove(p.getUUID());
        }
        Entity root = p.getRootVehicle();
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        Neighbourhood nb = NavServer.neighbourhood(p);
        Neighbourhood.Located hit = null;
        double best = Double.MAX_VALUE;
        for (Neighbourhood.Located l : nb.locateBodies(here, nav.timeSeconds())) {
            Body b = l.body();
            if (!b.type().solidSurface) continue;
            double alt = l.pos().distanceMetres(here) - b.radiusMetres();
            if (alt < entryAltitude(b) && alt < best) {
                best = alt;
                hit = l;
            }
        }
        if (hit == null) return false;
        Star star = nb.systemStar(here);
        PlanetAddress addr = star == null ? null : PlanetAddress.of(Galactica.GALAXY, star, hit.body());
        if (addr == null) {
            COOLDOWN.put(p.getUUID(), p.level().getServer().getTickCount() + REENTRY_COOLDOWN_TICKS);
            return false;
        }
        PlanetAddress.Resolved r = new PlanetAddress.Resolved(star, hit.parent() != null ? hit.parent() : hit.body(),
                hit.parent() != null ? hit.body() : null);
        double[] dir = here.metresFrom(hit.pos());
        double[] ll = BodyFrame.latLon(star, hit.body(), nav.timeSeconds(), dir);
        return enter(p, addr, r, ll[0], ll[1]);
    }

    /**
     * {@code /galactica land <body>}: lands on a body by name, searched in the current system, then
     * the Solar System, then every catalog star's system. From space near the body the sub-observer
     * point is used; otherwise the landing is at latitude 0, longitude 0.
     */
    public static boolean forceLand(ServerPlayer p, String bodyName) {
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double t = nav.timeSeconds();
        List<Star> stars = new ArrayList<>();
        GalacticPos here = null;
        if (p.level().dimension() == Galactica.SPACE) {
            Entity root = p.getRootVehicle();
            here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
            Star local = NavServer.neighbourhood(p).systemStar(here);
            if (local != null) stars.add(local);
        }
        stars.add(StarCatalog.SOL);
        for (Star s : StarCatalog.all()) if (!stars.contains(s)) stars.add(s);

        for (Star s : stars) {
            for (Body planet : SystemGenerator.generate(s)) {
                Body moon = null;
                Body match = planet.name().equalsIgnoreCase(bodyName) ? planet : null;
                for (Body m : planet.moons()) {
                    if (match == null && m.name().equalsIgnoreCase(bodyName)) {
                        match = m;
                        moon = m;
                    }
                }
                if (match == null) continue;
                if (!match.type().solidSurface) {
                    p.sendSystemMessage(Component.translatable("galactica.planet.not_landable", match.name(), match.type().label));
                    return false;
                }
                PlanetAddress addr = PlanetAddress.of(Galactica.GALAXY, s, match);
                if (addr == null) {
                    p.sendSystemMessage(Component.translatable("galactica.planet.unreachable", match.name()));
                    return false;
                }
                double lat = 0, lon = 0;
                if (here != null) {
                    double[] off = BodyFrame.offsetFromStar(s, planet, moon, t);
                    GalacticPos bodyPos = Neighbourhood.starPos(s).plusMetres(off[0], off[1], off[2]);
                    double[] dir = here.metresFrom(bodyPos);
                    if (dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2] < Math.pow(50 * match.radiusMetres(), 2)) {
                        double[] ll = BodyFrame.latLon(s, match, t, dir);
                        lat = ll[0];
                        lon = ll[1];
                    }
                }
                return enter(p, addr, new PlanetAddress.Resolved(s, planet, moon), lat, lon);
            }
        }
        p.sendSystemMessage(Component.translatable("galactica.planet.unknown", bodyName));
        return false;
    }

    private static boolean enter(ServerPlayer p, PlanetAddress addr, PlanetAddress.Resolved r, double lat, double lon) {
        MinecraftServer server = p.level().getServer();
        PlanetSlots slots = PlanetSlots.get(server);
        long now = server.overworld().getGameTime();
        int slot = slots.claim(addr, now, Galactica.GALAXY);
        ServerLevel dest = slot < 0 ? null : server.getLevel(PlanetSlots.key(slot));
        if (dest == null) {
            // Back off: the address lookup scans a whole sector for procedural stars.
            COOLDOWN.put(p.getUUID(), server.getTickCount() + REENTRY_COOLDOWN_TICKS);
            Long warned = NO_SLOT_WARNED.get(p.getUUID());
            if (warned == null || now - warned > 200) {
                NO_SLOT_WARNED.put(p.getUUID(), now);
                p.sendSystemMessage(Component.translatable("galactica.planet.no_slot", PlanetSlots.COUNT));
            }
            return false;
        }
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        SurfaceGrid grid = rt != null ? rt.grid() : SurfaceGrid.of(r.body().radiusMetres());
        Vec3 target = new Vec3(Math.floor(grid.blockX(lon)) + 0.5, ENTRY_Y, Math.floor(grid.blockZ(lat)) + 0.5);

        Entity root = p.getRootVehicle();
        boolean eva = root == p;
        if (eva) {
            p.teleport(new TeleportTransition(dest, target, Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.PLAY_PORTAL_SOUND));
        } else {
            root.teleport(new TeleportTransition(dest, target, Vec3.ZERO, root.getYRot(), root.getXRot(), TeleportTransition.PLAY_PORTAL_SOUND));
        }
        for (ServerPlayer rider : ridersOf(p)) arrive(rider, slot, addr, r.body(), eva && rider == p);
        return true;
    }

    /** {@code p} plus every player sharing its vehicle stack. */
    private static List<ServerPlayer> ridersOf(ServerPlayer p) {
        List<ServerPlayer> out = new ArrayList<>();
        out.add(p);
        Entity root = p.getRootVehicle();
        if (root != p) {
            for (Entity e : root.getIndirectPassengers()) if (e instanceof ServerPlayer sp && sp != p) out.add(sp);
        }
        return out;
    }

    private static void arrive(ServerPlayer p, int slot, PlanetAddress addr, Body body, boolean eva) {
        p.setAttached(Galactica.NAV, p.getAttachedOrCreate(Galactica.NAV).withSpeed(0));
        boolean keepsFlight = p.isCreative() || p.isSpectator();
        if (!keepsFlight) {
            p.getAbilities().mayfly = eva;
            p.getAbilities().flying = eva;
            p.onUpdateAbilities();
        }
        p.setAttached(SURFACE, new SurfaceState(slot, addr, eva && !keepsFlight));
        // The descent starts at the entry altitude. Reading the position here is unsafe: a cross-dimension
        // teleport may not have moved the player yet, and a stale low Y from the last planet would end the
        // descent flight at once and drop the player from ENTRY_Y.
        ARRIVALS.put(p.getUUID(), new Arrival(p.level().getServer().getTickCount(), Math.max(ENTRY_Y, p.getRootVehicle().getY())));
        applyGravity(p, body);
        // Locale.ROOT: the server's locale must not decide the decimal mark every client reads.
        p.sendOverlayMessage(Component.translatable("galactica.planet.entering", body.name(),
                String.format(Locale.ROOT, "%.2f", body.surfaceGravityG()), String.format(Locale.ROOT, "%.3g", body.surfacePressureBar())));
    }

    // ---------------------------------------------------------------- on the surface

    /**
     * Call every tick for every player. On a surface: keeps gravity at the body's value, wraps the
     * longitude seam and the poles, ends EVA descent flight, shows entry heating and takes off above
     * {@link #EXIT_Y}. Anywhere else it only removes the gravity modifiers.
     */
    public static void tickSurface(ServerPlayer p) {
        int slot = PlanetSlots.slotOf(p.level().dimension());
        if (slot < 0) {
            clearGravity(p);
            ARRIVALS.remove(p.getUUID());
            return;
        }
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        MinecraftServer server = p.level().getServer();
        if (rt == null) {
            // The slot was recycled under a logged-out player: nothing to stand on here any more.
            // Operators who /execute into an empty slot on purpose are left alone.
            if (!p.isCreative() && !p.isSpectator()) toSpace(p, null, null, null);
            return;
        }
        SurfaceState state = p.getAttached(SURFACE);
        if (state == null || state.slot() != slot || !state.address().equals(rt.address())) {
            p.setAttached(SURFACE, state = new SurfaceState(slot, rt.address(), false));
        }
        Entity root = p.getRootVehicle();
        boolean drives = root == p || root.getControllingPassenger() == p;
        applyGravity(p, rt.body());
        if (root != p && drives && root instanceof LivingEntity mount) applyFallRules(mount, rt.body());
        if ((p.tickCount % 20) == 0) PlanetSlots.get(server).touch(slot, server.overworld().getGameTime());

        Arrival arrival = ARRIVALS.get(p.getUUID());
        long since = arrival != null ? server.getTickCount() - arrival.tick : Long.MAX_VALUE;
        if (state.descending()) {
            if (arrival != null) arrival.lowestY = Math.min(arrival.lowestY, p.getY());
            // No bookkeeping (server restarted mid-descent) counts as timed out: never fly for free.
            boolean ends = arrival == null || p.getVehicle() != null
                    || EvaDescent.ends(p.onGround(), p.getY(), arrival.lowestY, since);
            if (ends) {
                if (!p.isCreative() && !p.isSpectator()) {
                    p.getAbilities().mayfly = false;
                    p.getAbilities().flying = false;
                    p.onUpdateAbilities();
                    if (!p.onGround()) p.sendOverlayMessage(Component.translatable("galactica.planet.descent_over"));
                }
                p.setAttached(SURFACE, state = state.landed());
            }
        }
        // One emitter per vehicle: its driver, or each player on foot.
        if (arrival != null && since < EntryHeating.DURATION_TICKS && (drives || root.getControllingPassenger() == null && firstPlayerAboard(root) == p)) {
            heat(root, rt.body(), since);
        }

        if (root.getY() > EXIT_Y) {
            toSpace(p, rt, root, state);
            return;
        }
        if (!drives) return; // the driver moves the vehicle across the map edges
        SurfaceGrid g = rt.grid();
        double[] pole = g.overPole(root.getX(), root.getZ());
        if (pole != null) {
            // Over the pole: same latitude on the far meridian, now facing back towards the equator.
            Vec3 v = root.getDeltaMovement();
            root.teleport(new TeleportTransition((ServerLevel) root.level(), new Vec3(pole[0], root.getY(), pole[1]),
                    new Vec3(-v.x, v.y, -v.z), root.getYRot() + 180f, root.getXRot(), TeleportTransition.DO_NOTHING));
        } else if (Math.abs(root.getX()) >= g.halfWidth() + 1) {
            // Walking off the east edge of the map arrives from the west: the surface is a closed sphere.
            double wrapped = g.wrapX(root.getX());
            root.teleport(new TeleportTransition((ServerLevel) root.level(), new Vec3(wrapped, root.getY(), root.getZ()),
                    root.getDeltaMovement(), root.getYRot(), root.getXRot(), TeleportTransition.DO_NOTHING));
        }
    }

    private static ServerPlayer firstPlayerAboard(Entity root) {
        for (Entity e : root.getIndirectPassengers()) if (e instanceof ServerPlayer sp) return sp;
        return null;
    }

    /** Flame and smoke streaming up past the arrival, seen by everyone nearby. */
    private static void heat(Entity root, Body body, long since) {
        double intensity = EntryHeating.intensity(body.surfacePressureBar(), since);
        if (intensity <= 0) return;
        ServerLevel level = (ServerLevel) root.level();
        double w = root.getBbWidth() * 0.6 + 0.4;
        int flames = (int) Math.ceil(14 * intensity), smoke = (int) Math.ceil(6 * intensity);
        level.sendParticles(ParticleTypes.FLAME, root.getX(), root.getY() - 0.3, root.getZ(), flames, w, 0.4, w, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, root.getX(), root.getY() + root.getBbHeight(), root.getZ(), smoke, w, 0.3, w, 0.03);
    }

    /** Takes off: back to galactica:space on the local radial at {@link #exitDistance}. */
    private static void toSpace(ServerPlayer p, PlanetSlots.Runtime rt, Entity root, SurfaceState state) {
        MinecraftServer server = p.level().getServer();
        ServerLevel space = server.getLevel(Galactica.SPACE);
        if (space == null) return;
        if (root == null) root = p.getRootVehicle();
        List<ServerPlayer> riders = ridersOf(p);
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        GalacticPos target = null;
        if (rt != null) {
            double t = nav.timeSeconds();
            PlanetAddress.Resolved r = rt.resolved();
            Body b = r.body();
            double lat = rt.grid().latitude(root.getZ()), lon = rt.grid().longitude(root.getX());
            double[] dir = BodyFrame.direction(r.star(), b, t, lat, lon);
            double[] off = BodyFrame.offsetFromStar(r.star(), r.planet(), r.moon(), t);
            double d = exitDistance(b);
            target = Neighbourhood.starPos(r.star()).plusMetres(off[0] + dir[0] * d, off[1] + dir[1] * d, off[2] + dir[2] * d);
        }
        Vec3 spot = new Vec3(0.5, 0, 0.5);
        if (root != p && root instanceof LivingEntity mount) clearGravity(mount);
        Entity arrived = root.teleport(new TeleportTransition(space, spot, Vec3.ZERO, root.getYRot(), root.getXRot(),
                TeleportTransition.PLAY_PORTAL_SOUND));
        int until = server.getTickCount() + REENTRY_COOLDOWN_TICKS;
        for (ServerPlayer rider : riders) {
            if (target != null) {
                if (arrived == rider) {
                    NavServer.moveTo(rider, target);
                } else {
                    // Every rider's frame is anchored on the vehicle, so the ship sits exactly on the radial.
                    double[] g = Frames.mcToGalactic(spot.x, spot.y, spot.z);
                    rider.setAttached(Galactica.NAV, rider.getAttachedOrCreate(Galactica.NAV)
                            .withOrigin(target.plusMetres(-g[0], -g[1], -g[2])).withSpeed(0));
                }
            }
            rider.removeAttached(SURFACE);
            ARRIVALS.remove(rider.getUUID());
            clearGravity(rider);
            COOLDOWN.put(rider.getUUID(), until);
            if (rt != null) rider.sendOverlayMessage(Component.translatable("galactica.planet.leaving", rt.body().name()));
        }
    }

    /** Gravity, safe fall distance and fall damage of the body, as total multipliers on vanilla's. */
    private static void applyGravity(ServerPlayer p, Body body) {
        setModifier(p, Attributes.GRAVITY, GRAVITY, SurfaceGravity.factor(body.surfaceGravityG()) - 1.0);
        applyFallRules(p, body);
    }

    /** A mount's own gravity comes from {@link PlanetGravity}; its legs still need the fall rules. */
    private static void applyFallRules(LivingEntity e, Body body) {
        double g = body.surfaceGravityG();
        setModifier(e, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL, SurfaceGravity.safeFallDistance(g) / SurfaceGravity.VANILLA_SAFE_FALL - 1.0);
        setModifier(e, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_DAMAGE, SurfaceGravity.fallDamageMultiplier(g) - 1.0);
    }

    private static void setModifier(LivingEntity e, Holder<Attribute> attribute, Identifier id, double amount) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a == null) return;
        AttributeModifier current = a.getModifier(id);
        if (current == null || current.amount() != amount) {
            a.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void clearGravity(LivingEntity e) {
        removeModifier(e, Attributes.GRAVITY, GRAVITY);
        removeModifier(e, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL);
        removeModifier(e, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_DAMAGE);
    }

    private static void removeModifier(LivingEntity e, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a != null && a.hasModifier(id)) a.removeModifier(id);
    }

    // ---------------------------------------------------------------- queries

    /** The body a player stands on, or null off-planet. */
    public static Body bodyOf(ServerPlayer p) {
        PlanetSlots.Runtime rt = PlanetSlots.runtime(PlanetSlots.slotOf(p.level().dimension()));
        return rt != null ? rt.body() : null;
    }

    /**
     * Ambient conditions for life support: the body's surface values on a planet,
     * {@link PlanetEnvironment#VACUUM} in galactica:space, null in any other dimension (vanilla rules).
     */
    public static PlanetEnvironment environmentOf(ServerPlayer p) {
        if (p.level().dimension() == Galactica.SPACE) return PlanetEnvironment.VACUUM;
        Body b = bodyOf(p);
        return b != null ? PlanetEnvironment.of(b) : null;
    }
}
