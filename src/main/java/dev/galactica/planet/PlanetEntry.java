package dev.galactica.planet;

import dev.galactica.Galactica;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.OrbitalFrame;
import dev.galactica.planet.transfer.EarthOverworld;
import dev.galactica.planet.transfer.EntryAerothermo;
import dev.galactica.planet.transfer.PlasmaStatePayload;
import dev.galactica.planet.transfer.TransferAltitude;
import dev.galactica.planet.transfer.TransferKinematics;
import dev.galactica.planet.transfer.TransferKinematics.LocalFrame;
import dev.galactica.planet.transfer.TransferNet;
import dev.galactica.planet.transfer.TransferPreparePayload;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.ship.Starship;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Landing and take-off between galactica:space and the surface slots (spec §5), made seamless in M7.
 *
 * <p><b>Approach.</b> A craft (ship, or a suited player) that drops below the top of a landable body's
 * atmosphere ({@link Body#atmosphereTopMetres()}, or {@link #AIRLESS_ENTRY_M} for airless bodies) is on
 * approach: its slot is claimed, chunks under the projected switch point are loaded through
 * {@link ApproachTickets}, the client is armed to skip the loading screen ({@link TransferPreparePayload}) and
 * the plasma state is streamed ({@link PlasmaStatePayload}).
 *
 * <p><b>Switch.</b> Altitude is one continuous scale ({@link TransferAltitude}): the switch fires when the
 * real altitude falls to that of surface row {@link #ENTRY_Y}, and the craft arrives at the same altitude above
 * the sub-point, with no portal sound ({@link TeleportTransition#DO_NOTHING}), its velocity converted to surface
 * axes (minus the ground's own rotation, slowed by Allen–Eggers drag down to terminal speed,
 * {@link EntryAerothermo#speedAt}) and its attitude re-expressed in the local east/up/south frame
 * ({@link TransferKinematics}).
 *
 * <p><b>Exit.</b> Rising above {@link #EXIT_Y} returns to space on the same radial at the same real altitude,
 * with velocity and attitude carried back; re-entry into that body is held off only while the craft is still
 * climbing below 1.3 × the switch altitude ({@link TransferAltitude#reentryBlocked}).
 *
 * <p><b>Lifecycle.</b> Approach state, pre-load tickets and cooldowns are dropped on leaving space by any route
 * (death, command, portal), on becoming a passenger or spectator, on respawn and on disconnect; the client is
 * disarmed at the same time ({@link TransferPreparePayload#disarm()}).
 *
 * <p><b>Earth.</b> Earth of Sol lands in minecraft:overworld ({@link EarthOverworld}); a ship climbing above
 * {@link #EXIT_Y} in the Overworld launches to orbit above Earth at the matching point.
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

    public static final double ENTRY_Y = TransferAltitude.ENTRY_Y, EXIT_Y = TransferAltitude.EXIT_Y;
    /** Entry shell of a body with no air: low orbit altitude for the Moon, high enough to brake in. */
    public static final double AIRLESS_ENTRY_M = 100_000;
    /** No re-entry for 5 s after leaving, so a take-off cannot bounce straight back down. */
    private static final int REENTRY_COOLDOWN_TICKS = 100;
    /** A climb within this many blocks of {@link #EXIT_Y} arms the client for the take-off switch. */
    private static final double EXIT_ARM_BAND = 120;
    /**
     * Fastest entry hand-off speed, blocks/tick: the same bound the take-off path hands over
     * ({@link ShipFlightModel#MAX_SPEED} × {@link ShipFlightModel#TICK} = 15 blocks/tick, under one chunk per tick
     * so chunk streaming keeps up). There is no packet limit: on 26.3 {@code ClientboundAddEntityPacket} and the
     * teleport packet encode velocity with {@code Vec3.LP_STREAM_CODEC}, which has no ±3.9 clamp.
     */
    public static final double MAX_TRANSFER_BLOCKS_PER_TICK = ShipFlightModel.MAX_SPEED * ShipFlightModel.TICK;
    /** Longest look-ahead for the approach ticket, seconds. */
    private static final double MAX_ETA_SECONDS = 60;

    private static final Identifier GRAVITY = Galactica.id("planet_gravity");
    private static final Identifier SAFE_FALL = Galactica.id("planet_safe_fall");
    private static final Identifier FALL_DAMAGE = Galactica.id("planet_fall_damage");
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    /** Per-player arrival bookkeeping: lowest Y of an EVA descent, when they arrived, plasma glow. */
    private static final Map<UUID, Arrival> ARRIVALS = new HashMap<>();
    /** Per-leader approach state while inside an entry shell. */
    private static final Map<UUID, Approach> APPROACHES = new HashMap<>();
    /** After a take-off: re-entry into that body waits until the craft is above 1.3 × the switch altitude. */
    private static final Map<UUID, Rearm> REARM = new HashMap<>();
    private static final Map<UUID, Long> NO_SLOT_WARNED = new HashMap<>();

    private static final class Arrival {
        final long tick;
        double lowestY;
        /** Overworld EVA descents keep their own flag; slots use {@link SurfaceState#descending()}. */
        boolean overworldDescent;

        Arrival(long tick, double y) {
            this.tick = tick;
            this.lowestY = y;
        }
    }

    private record Rearm(long bodyId, double switchAltitude) {}

    /** Where an entry lands: a slot level, or the Overworld for Earth. */
    private record Dest(ServerLevel level, int slot, SurfaceGrid grid, double seaLevel, EarthOverworld earth) {
        double x(double lon) {
            return earth != null ? earth.blockX(lon) : grid.blockX(lon);
        }

        double z(double lat) {
            return earth != null ? earth.blockZ(lat) : grid.blockZ(lat);
        }

        double switchAltitude() {
            return TransferAltitude.switchAltitude(seaLevel, grid.metresPerBlock());
        }
    }

    private static final class Approach {
        final long bodyId;
        final PlanetAddress address;
        final PlanetAddress.Resolved resolved;
        final Dest dest;
        long lastPrepare = Long.MIN_VALUE;

        /** False once the slot this approach claimed no longer belongs to its body (recycled meanwhile). */
        boolean stillValid() {
            if (dest.earth() != null) return true;
            PlanetSlots.Runtime rt = PlanetSlots.runtime(dest.slot());
            return rt != null && address.equals(rt.address());
        }

        Approach(long bodyId, PlanetAddress address, PlanetAddress.Resolved resolved, Dest dest) {
            this.bodyId = bodyId;
            this.address = address;
            this.resolved = resolved;
            this.dest = dest;
        }
    }

    public static final AttachmentType<SurfaceState> SURFACE = AttachmentRegistry.create(Galactica.id("surface"),
            b -> b.persistent(SurfaceState.CODEC)
                    .copyOnDeath()
                    .syncWith(SurfaceState.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

    /**
     * Registers the generator and biome source codecs, the slot lifecycle, the approach ticket type, the
     * transfer packets and the plasma particle. Call from onInitialize.
     */
    public static void init() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Galactica.id("planet"), PlanetChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Galactica.id("planet"), PlanetBiomeSource.CODEC);
        ApproachTickets.init();
        TransferNet.init();
        ServerLifecycleEvents.SERVER_STARTING.register(PlanetSlots::onServerStarting);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player.getUUID()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // Death or End-exit respawn: whatever approach was running is over. The client already ignores an arm
            // whose destination is not the new level (TransferClient); the disarm makes it explicit.
            forget(newPlayer.getUUID());
            ServerPlayNetworking.send(newPlayer, TransferPreparePayload.disarm());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            PlanetSlots.onServerStopped(s);
            COOLDOWN.clear();
            NO_SLOT_WARNED.clear();
            ARRIVALS.clear();
            APPROACHES.clear();
            REARM.clear();
            ApproachTickets.clear();
            earth = null;
            earthAddress = null;
            earthMissing = false;
            overworldMap = null;
            overworldSpawn = null;
        });
    }

    /** Drops every per-player transfer record (no packets: the player may be gone). */
    private static void forget(UUID id) {
        APPROACHES.remove(id);
        ApproachTickets.release(id);
        REARM.remove(id);
        COOLDOWN.remove(id);
        ARRIVALS.remove(id);
        NO_SLOT_WARNED.remove(id);
    }

    /** Altitude above the surface where the approach (heating, chunk pre-load) begins. */
    public static double entryAltitude(Body b) {
        double top = b.atmosphereTopMetres();
        return top > 0 ? top : AIRLESS_ENTRY_M;
    }

    // ---------------------------------------------------------------- Earth

    private static PlanetAddress.Resolved earth;
    private static PlanetAddress earthAddress;
    /** Memo of a failed lookup, so a system without an "Earth" is generated once, not every tick. */
    private static boolean earthMissing;
    private static EarthOverworld overworldMap;
    private static BlockPos overworldSpawn;

    /** Earth of Sol, resolved once (and a miss remembered). */
    static PlanetAddress.Resolved earth() {
        if (earth == null && !earthMissing) {
            for (Body b : SystemGenerator.generate(StarCatalog.SOL)) {
                if ("Earth".equals(b.name())) {
                    earth = new PlanetAddress.Resolved(StarCatalog.SOL, b, null);
                    earthAddress = PlanetAddress.of(Galactica.GALAXY, StarCatalog.SOL, b);
                }
            }
            earthMissing = earth == null;
        }
        return earth;
    }

    static boolean isEarth(PlanetAddress a) {
        return a != null && EarthOverworld.isEarth(a.catalog(), a.planet(), a.moon()) && earth() != null;
    }

    /** Earth's map onto the Overworld, cached until the world spawn moves. Requires {@link #earth()} != null. */
    private static EarthOverworld overworldMap(MinecraftServer server) {
        BlockPos spawn = server.overworld().getRespawnData().pos();
        if (overworldMap == null || !spawn.equals(overworldSpawn)) {
            overworldMap = new EarthOverworld(SurfaceGrid.of(earth().body().radiusMetres()), spawn.getX() + 0.5, spawn.getZ() + 0.5);
            overworldSpawn = spawn.immutable();
        }
        return overworldMap;
    }

    // ---------------------------------------------------------------- approach and entry

    /** Called every tick for players in galactica:space; approaches and lands them when inside an entry shell. */
    public static boolean tryEnter(ServerPlayer p) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            return tryEnterTimed(p);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.PLANET_ENTRY, perf);
        }
    }

    private static boolean tryEnterTimed(ServerPlayer p) {
        Entity root = p.getRootVehicle();
        if (p.level().dimension() != Galactica.SPACE || p.isSpectator() || !isLeader(p, root)) {
            abortApproach(p);
            return false;
        }
        MinecraftServer server = p.level().getServer();
        Integer until = COOLDOWN.get(p.getUUID());
        if (until != null) {
            if (server.getTickCount() < until) return false;
            COOLDOWN.remove(p.getUUID());
        }
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double t = nav.timeSeconds();
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        Neighbourhood nb = NavServer.neighbourhood(p);
        Neighbourhood.Located hit = null;
        double best = Double.MAX_VALUE;
        for (Neighbourhood.Located l : nb.locateBodies(here, t)) {
            Body b = l.body();
            if (!b.type().solidSurface) continue;
            double alt = l.pos().distanceMetres(here) - b.radiusMetres();
            if (alt < entryAltitude(b) && alt < best) {
                best = alt;
                hit = l;
            }
        }
        if (hit == null) {
            abortApproach(p);
            return false;
        }
        Body body = hit.body();
        // Local up and the craft's velocity relative to the body (galactic m/s): its block velocity plus the space
        // frame's own velocity (OrbitalFrame, which co-moves with the dominant body). Spin is horizontal, so the radial
        // rate needs no ground correction.
        double[] radial = here.metresFrom(hit.pos());
        double rMag = Math.sqrt(radial[0] * radial[0] + radial[1] * radial[1] + radial[2] * radial[2]);
        double[] up = {radial[0] / rMag, radial[1] / rMag, radial[2] / rMag};
        double[] vGal = TransferKinematics.spaceMcToGalactic(new double[] {root.getDeltaMovement().x, root.getDeltaMovement().y, root.getDeltaMovement().z});
        if (nav.anchor() == body.id()) {
            double[] vf = nav.frameVelocity();
            for (int i = 0; i < 3; i++) vGal[i] += vf[i];
        } else if (nav.anchor() != OrbitalFrame.ANCHOR_UNSET) {
            // The frame is anchored elsewhere (a moon's shell reaching past its sphere of influence): re-express it.
            double[] vf = OrbitalFrame.handOver(nav.frameVelocity(), OrbitalFrame.anchorVelocity(nb, here, nav.anchor(), t),
                    OrbitalFrame.anchorVelocity(nb, here, body.id(), t));
            for (int i = 0; i < 3; i++) vGal[i] += vf[i];
        }
        if (nav.speed() > 0) {
            Vec3 look = p.getLookAngle();
            double[] g = Frames.mcToGalactic(look.x, look.y, look.z);
            for (int i = 0; i < 3; i++) vGal[i] += g[i] * nav.speed();
        }
        Rearm rearm = REARM.get(p.getUUID());
        if (rearm != null) {
            if (rearm.bodyId() == body.id() && TransferAltitude.reentryBlocked(best, rearm.switchAltitude(), dot(vGal, up))) {
                abortApproach(p);
                return false;
            }
            REARM.remove(p.getUUID());
        }

        Approach a = APPROACHES.get(p.getUUID());
        if (a != null && !a.stillValid()) abortApproach(p); // its slot was recycled for another body meanwhile
        a = APPROACHES.get(p.getUUID());
        if (a == null || a.bodyId != body.id()) {
            abortApproach(p);
            Star star = nb.systemStar(here);
            PlanetAddress addr = star == null ? null : PlanetAddress.of(Galactica.GALAXY, star, body);
            if (addr == null) {
                COOLDOWN.put(p.getUUID(), server.getTickCount() + REENTRY_COOLDOWN_TICKS);
                return false;
            }
            PlanetAddress.Resolved r = new PlanetAddress.Resolved(star, hit.parent() != null ? hit.parent() : body,
                    hit.parent() != null ? body : null);
            Dest dest = destination(p, addr, r);
            if (dest == null) return false;
            a = new Approach(body.id(), addr, r, dest);
            APPROACHES.put(p.getUUID(), a);
        }

        // Local frame and ground-relative velocity (TransferKinematics).
        double[] pole = BodyFrame.pole(a.resolved.star(), body);
        LocalFrame frame = LocalFrame.at(pole, up);
        double[] spin = TransferKinematics.spinVelocity(pole, TransferKinematics.spinRate(body.rotationHours()), radial);
        double[] vGround = TransferKinematics.groundRelative(vGal, spin);
        // The space dimension has no air: the drag met since the atmosphere top is applied analytically
        // (Allen–Eggers, the same model the switch uses), so heating peaks near rho* instead of rising at orbital
        // speed all the way down, and the speed is continuous across the switch.
        double[] vDragged = dragged(vGround, frame, body, eva(root), best);
        double speed = Math.sqrt(dot(vDragged, vDragged));

        double[] ll = BodyFrame.latLon(a.resolved.star(), body, t, radial);
        double hSwitch = a.dest.switchAltitude();
        List<ServerPlayer> riders = ridersOf(p);
        streamPlasma(riders, root, body, best, speed, normalize(TransferKinematics.galacticToSpaceMc(vDragged)), eva(root));

        if (best <= hSwitch) {
            return enter(p, a.address, a.resolved, a.dest, ll[0], ll[1], best, vGround, frame);
        }
        // Projected switch point: sub-point plus ground track × time to the switch, on the dragged velocity.
        double vEast = dot(vDragged, frame.east()), vNorth = dot(vDragged, frame.north()), vUp = dot(vDragged, frame.up());
        double eta = TransferAltitude.secondsToSwitch(best, hSwitch, -vUp, MAX_ETA_SECONDS);
        double radius = body.radiusMetres();
        double lat = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, ll[0] + vNorth * eta / radius));
        double lon = ll[1] + vEast * eta / (radius * Math.max(0.05, Math.cos(ll[0])));
        long now = server.getTickCount();
        ApproachTickets.hold(p.getUUID(), a.dest.level(), a.dest.x(lon), a.dest.z(lat), now);
        int etaTicks = (int) Math.ceil(eta * 20);
        if (now - a.lastPrepare >= 5 || etaTicks < 40) {
            a.lastPrepare = now;
            prepare(riders, a.dest.slot(), a.address, lat, lon, hSwitch, etaTicks, true);
        }
        return false;
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
                PlanetAddress.Resolved r = new PlanetAddress.Resolved(s, planet, moon);
                Dest dest = destination(p, addr, r);
                if (dest == null) return false;
                abortApproach(p);
                double[] up = BodyFrame.direction(s, match, t, lat, lon);
                LocalFrame frame = LocalFrame.at(BodyFrame.pole(s, match), up);
                return enter(p, addr, r, dest, lat, lon, dest.switchAltitude(), new double[3], frame);
            }
        }
        p.sendSystemMessage(Component.translatable("galactica.planet.unknown", bodyName));
        return false;
    }

    /** The level an entry lands in: Earth's Overworld or a claimed slot; null (with a warning) when none is free. */
    private static Dest destination(ServerPlayer p, PlanetAddress addr, PlanetAddress.Resolved r) {
        MinecraftServer server = p.level().getServer();
        if (isEarth(addr)) {
            EarthOverworld map = overworldMap(server);
            return new Dest(server.overworld(), TransferPreparePayload.OVERWORLD, map.grid(), TransferAltitude.OVERWORLD_SEA_LEVEL, map);
        }
        PlanetSlots slots = PlanetSlots.get(server);
        long now = server.overworld().getGameTime();
        int slot = slots.claim(addr, now, Galactica.GALAXY);
        ServerLevel level = slot < 0 ? null : server.getLevel(PlanetSlots.key(slot));
        if (level == null) {
            // Back off: the address lookup scans a whole sector for procedural stars.
            COOLDOWN.put(p.getUUID(), server.getTickCount() + REENTRY_COOLDOWN_TICKS);
            Long warned = NO_SLOT_WARNED.get(p.getUUID());
            if (warned == null || now - warned > 200) {
                NO_SLOT_WARNED.put(p.getUUID(), now);
                p.sendSystemMessage(Component.translatable("galactica.planet.no_slot", PlanetSlots.COUNT));
            }
            return null;
        }
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        SurfaceGrid grid = rt != null ? rt.grid() : SurfaceGrid.of(r.body().radiusMetres());
        return new Dest(level, slot, grid, TerrainSampler.SEA_LEVEL, null);
    }

    /**
     * The switch. {@code vGround} is the ground-relative velocity in galactic m/s; it is slowed by drag
     * from the atmosphere top down to {@code altitude}, converted to surface blocks/tick and handed over with
     * the attitude, without a portal sound.
     */
    private static boolean enter(ServerPlayer p, PlanetAddress addr, PlanetAddress.Resolved r, Dest dest,
                                 double lat, double lon, double altitude, double[] vGround, LocalFrame frame) {
        Body body = r.body();
        double mpb = dest.grid().metresPerBlock();
        double y = Math.max(ENTRY_Y - 64, Math.min(ENTRY_Y, TransferAltitude.blockY(altitude, dest.seaLevel(), mpb)));
        Vec3 target = new Vec3(Math.floor(dest.x(lon)) + 0.5, y, Math.floor(dest.z(lat)) + 0.5);

        Entity root = p.getRootVehicle();
        boolean eva = root == p;
        double[] vSlowed = dragged(vGround, frame, body, eva, altitude);
        double[] v = clamp(TransferKinematics.toSurface(vSlowed, frame, mpb), MAX_TRANSFER_BLOCKS_PER_TICK);
        Vec3 velocity = new Vec3(v[0], v[1], v[2]);
        double[] look = TransferKinematics.directionToSurface(TransferKinematics.lookVector(root.getYRot(), root.getXRot()), frame);
        double[] angles = TransferKinematics.lookAngles(look);
        double[] attitude = null;
        if (root instanceof Starship ship) {
            var q = ship.attitude();
            attitude = TransferKinematics.attitudeToSurface(new double[] {q.x(), q.y(), q.z(), q.w()}, frame);
        }

        List<ServerPlayer> riders = ridersOf(p);
        Galactica.LOG.info("Entry: {} into {} at y={} v={} (altitude {} m)", p.getName().getString(), body.name(),
                y, velocity, altitude);
        // The arming packet must precede the respawn packet: same connection, same order.
        prepare(riders, dest.slot(), addr, lat, lon, dest.switchAltitude(), 0, true);
        ApproachTickets.release(p.getUUID());
        APPROACHES.remove(p.getUUID());

        Entity arrived = root.teleport(new TeleportTransition(dest.level(), target, velocity, (float) angles[0], (float) angles[1],
                TeleportTransition.DO_NOTHING));
        if (arrived instanceof Starship ship) {
            if (attitude != null) ship.setAttitude(new Quaternionf((float) attitude[0], (float) attitude[1], (float) attitude[2], (float) attitude[3]));
            ship.setDeltaMovement(velocity);
        }
        for (ServerPlayer rider : riders) {
            // The orbit ends here: a later launch or take-off sets the frame afresh.
            rider.setAttached(Galactica.NAV, rider.getAttachedOrCreate(Galactica.NAV).resetFrame());
            arrive(rider, dest, addr, body, eva && rider == p);
        }
        return true;
    }

    /**
     * Ground-relative velocity {@code vGround} (m/s) slowed by the drag met between the atmosphere top and
     * {@code altitude}: Allen–Eggers along the current flight-path angle, never below terminal speed
     * ({@link EntryAerothermo#speedAt}). Unchanged for an airless body.
     */
    private static double[] dragged(double[] vGround, LocalFrame frame, Body body, boolean eva, double altitude) {
        double speed = Math.sqrt(dot(vGround, vGround));
        if (!(speed > 0)) return vGround.clone();
        double sinGamma = Math.max(0, -dot(vGround, frame.up())) / speed;
        double after = EntryAerothermo.speedAt(speed, sinGamma, EntryAerothermo.surfaceDensity(body), body.scaleHeight(),
                eva ? EntryAerothermo.EVA_BALLISTIC : EntryAerothermo.SHIP_BALLISTIC, body.surfaceGravity(), altitude);
        double k = after / speed;
        return new double[] {vGround[0] * k, vGround[1] * k, vGround[2] * k};
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

    /** One player decides for a vehicle: its driver, else the first player aboard; on foot, the player. */
    private static boolean isLeader(ServerPlayer p, Entity root) {
        if (root == p) return true;
        Entity driver = root.getControllingPassenger();
        return driver != null ? driver == p : firstPlayerAboard(root) == p;
    }

    private static boolean eva(Entity root) {
        return !(root instanceof Starship);
    }

    private static void arrive(ServerPlayer p, Dest dest, PlanetAddress addr, Body body, boolean eva) {
        // Velocity now lives in the entity's delta movement: the warp drive in NavState is not zeroed here,
        // and NavServer re-anchors the frame (speed 0) on take-off.
        boolean keepsFlight = p.isCreative() || p.isSpectator();
        if (!keepsFlight) {
            p.getAbilities().mayfly = eva;
            p.getAbilities().flying = eva;
            p.onUpdateAbilities();
        }
        Arrival arrival = new Arrival(p.level().getServer().getTickCount(), Math.max(ENTRY_Y, p.getRootVehicle().getY()));
        if (dest.earth() != null) {
            p.removeAttached(SURFACE);
            arrival.overworldDescent = eva && !keepsFlight;
            clearGravity(p);
        } else {
            p.setAttached(SURFACE, new SurfaceState(dest.slot(), addr, eva && !keepsFlight));
            applyGravity(p, body);
        }
        // The descent starts at the entry altitude. Reading the position here is unsafe: a cross-dimension
        // teleport may not have moved the player yet, and a stale low Y from the last planet would end the
        // descent flight at once and drop the player from ENTRY_Y.
        ARRIVALS.put(p.getUUID(), arrival);
        // Locale.ROOT: the server's locale must not decide the decimal mark every client reads.
        p.sendOverlayMessage(Component.translatable("galactica.planet.entering", body.name(),
                String.format(Locale.ROOT, "%.2f", body.surfaceGravityG()), String.format(Locale.ROOT, "%.3g", body.surfacePressureBar())));
    }

    /** Ends an approach that did not switch: drops the pre-load and disarms the riders' clients. */
    private static void abortApproach(ServerPlayer p) {
        boolean had = APPROACHES.remove(p.getUUID()) != null;
        ApproachTickets.release(p.getUUID());
        if (had && !p.hasDisconnected()) {
            TransferPreparePayload off = TransferPreparePayload.disarm();
            for (ServerPlayer rider : ridersOf(p)) ServerPlayNetworking.send(rider, off);
        }
    }

    // ---------------------------------------------------------------- on the surface

    /**
     * Call every tick for every player. On a surface: keeps gravity at the body's value, wraps the
     * longitude seam and the poles, ends EVA descent flight, streams entry plasma and takes off above
     * {@link #EXIT_Y}. In the Overworld it runs Earth's descents and launches. Anywhere else it only removes
     * the gravity modifiers.
     */
    public static void tickSurface(ServerPlayer p) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            tickSurfaceTimed(p);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.PLANET_ENTRY, perf);
        }
    }

    private static void tickSurfaceTimed(ServerPlayer p) {
        // Left space without switching (death, command, portal): the approach is over.
        if (p.level().dimension() != Galactica.SPACE && APPROACHES.containsKey(p.getUUID())) abortApproach(p);
        int slot = PlanetSlots.slotOf(p.level().dimension());
        if (slot < 0) {
            clearGravity(p);
            if (p.level().dimension() == Level.OVERWORLD) {
                // Only Galactica traffic costs anything: arrivals from space and Starships that may climb out.
                if (ARRIVALS.containsKey(p.getUUID()) || p.getRootVehicle() instanceof Starship) tickOverworld(p);
            } else {
                ARRIVALS.remove(p.getUUID());
            }
            return;
        }
        PlanetSlots.Runtime rt = PlanetSlots.runtime(slot);
        MinecraftServer server = p.level().getServer();
        if (rt == null) {
            // The slot was recycled under a logged-out player: nothing to stand on here any more.
            // Operators who /execute into an empty slot on purpose are left alone.
            if (!p.isCreative() && !p.isSpectator()) bareExit(p);
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
            if (endDescent(p, arrival, since)) p.setAttached(SURFACE, state = state.landed());
        }
        SurfaceGrid g = rt.grid();
        if (isLeader(p, root)) {
            surfacePlasma(p, root, rt.body(), g, TerrainSampler.SEA_LEVEL);
            if (root.getY() > EXIT_Y) {
                toSpace(p, root, rt.resolved(), rt.address(), g, TerrainSampler.SEA_LEVEL, g.latitude(root.getZ()), g.longitude(root.getX()));
                return;
            }
            armExit(p, root, slot, rt.address(), g, TerrainSampler.SEA_LEVEL, g.latitude(root.getZ()), g.longitude(root.getX()));
        }
        if (!drives) return; // the driver moves the vehicle across the map edges
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

    /** Earth in the Overworld: EVA descents from orbit, entry plasma, and ships climbing out to space. */
    private static void tickOverworld(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        Arrival arrival = ARRIVALS.get(p.getUUID());
        if (arrival != null && arrival.overworldDescent) {
            if (endDescent(p, arrival, server.getTickCount() - arrival.tick)) arrival.overworldDescent = false;
        }
        PlanetAddress.Resolved r = earth();
        if (r == null || earthAddress == null) return;
        Entity root = p.getRootVehicle();
        if (!isLeader(p, root)) return;
        EarthOverworld map = overworldMap(server);
        // Only arrivals from space glow: a vanilla elytra flight is far too slow to heat anyway.
        if (arrival != null) surfacePlasma(p, root, r.body(), map.grid(), TransferAltitude.OVERWORLD_SEA_LEVEL);
        // Climbing a ship out of the Overworld launches to orbit; players on foot or elytra stay home.
        if (!(root instanceof Starship)) return;
        double lat = map.latitude(root.getZ()), lon = map.longitude(root.getX());
        if (root.getY() > EXIT_Y) {
            toSpace(p, root, r, earthAddress, map.grid(), TransferAltitude.OVERWORLD_SEA_LEVEL, lat, lon);
            return;
        }
        armExit(p, root, TransferPreparePayload.OVERWORLD, earthAddress, map.grid(), TransferAltitude.OVERWORLD_SEA_LEVEL, lat, lon);
    }

    /**
     * Standing on this planet's ground. The on-ground flag survives a cross-dimension teleport until the
     * client's next movement packet, so a player sent here from another surface still reads as grounded
     * at the entry altitude; trusting it there ended the descent flight at once and dropped the player
     * from the entry row. Arrivals sit within 64 blocks below {@link #ENTRY_Y}, and no terrain reaches
     * 100 blocks below it.
     */
    private static boolean touchedDown(ServerPlayer p) {
        return p.onGround() && p.getY() < ENTRY_Y - 100;
    }

    /** Ends an EVA descent flight on touchdown, climb or timeout; true when it ended. */
    private static boolean endDescent(ServerPlayer p, Arrival arrival, long since) {
        if (arrival != null) arrival.lowestY = Math.min(arrival.lowestY, p.getY());
        // No bookkeeping (server restarted mid-descent) counts as timed out: never fly for free.
        boolean ends = arrival == null || p.getVehicle() != null || EvaDescent.ends(touchedDown(p), p.getY(), arrival.lowestY, since);
        if (!ends) return false;
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            if (!p.onGround()) p.sendOverlayMessage(Component.translatable("galactica.planet.descent_over"));
        }
        return true;
    }

    private static ServerPlayer firstPlayerAboard(Entity root) {
        for (Entity e : root.getIndirectPassengers()) if (e instanceof ServerPlayer sp) return sp;
        return null;
    }

    /** A climb close to the take-off row arms the riders' clients so the switch shows no loading screen. */
    private static void armExit(ServerPlayer p, Entity root, int slot, PlanetAddress addr, SurfaceGrid g, double seaLevel,
                                double lat, double lon) {
        double climb = root.getDeltaMovement().y;
        if (root.getY() < EXIT_Y - EXIT_ARM_BAND || climb <= 0) return;
        if ((p.tickCount % 5) != 0) return;
        int eta = (int) Math.ceil((EXIT_Y - root.getY()) / Math.max(climb, 0.05));
        prepare(ridersOf(p), TransferPreparePayload.SPACE, addr, lat, lon,
                TransferAltitude.exitAltitude(seaLevel, g.metresPerBlock()), Math.min(eta, 1200), false);
    }

    /** Takes off: back to galactica:space on the local radial, at the same real altitude, keeping velocity and attitude. */
    private static void toSpace(ServerPlayer p, Entity root, PlanetAddress.Resolved r, PlanetAddress addr, SurfaceGrid grid,
                                double seaLevel, double lat, double lon) {
        MinecraftServer server = p.level().getServer();
        ServerLevel space = server.getLevel(Galactica.SPACE);
        if (space == null) return;
        Galactica.LOG.info("Take-off: {} from {} at y={} v={}", p.getName().getString(), r.body().name(), root.getY(), root.getDeltaMovement());
        List<ServerPlayer> riders = ridersOf(p);
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double t = nav.timeSeconds();
        Body b = r.body();
        double mpb = grid.metresPerBlock();
        double altitude = TransferAltitude.realAltitude(root.getY(), seaLevel, mpb);
        double[] dir = BodyFrame.direction(r.star(), b, t, lat, lon);
        double[] off = BodyFrame.offsetFromStar(r.star(), r.planet(), r.moon(), t);
        double d = b.radiusMetres() + altitude;
        GalacticPos target = Neighbourhood.starPos(r.star()).plusMetres(off[0] + dir[0] * d, off[1] + dir[1] * d, off[2] + dir[2] * d);

        double[] pole = BodyFrame.pole(r.star(), b);
        LocalFrame frame = LocalFrame.at(pole, dir);
        Vec3 dm = root.getDeltaMovement();
        double[] vGround = TransferKinematics.fromSurface(new double[] {dm.x, dm.y, dm.z}, frame, mpb);
        double[] spin = TransferKinematics.spinVelocity(pole, TransferKinematics.spinRate(b.rotationHours()),
                new double[] {dir[0] * d, dir[1] * d, dir[2] * d});
        // The hull keeps its ground-relative velocity (capped at what the space flight model accepts); the rest of the
        // inertial velocity (the ground's own spin, anything over the cap) goes to the space frame, which co-moves with
        // this body (OrbitalFrame): leaving the surface puts the craft on the trajectory it is really on.
        double[] vInertial = TransferKinematics.inertial(vGround, spin);
        double[] v = clamp(TransferKinematics.galacticToSpaceMc(vGround), ShipFlightModel.KRAKENSBANE_SPEED * ShipFlightModel.TICK);
        Vec3 velocity = new Vec3(v[0], v[1], v[2]);
        double[] hull = TransferKinematics.spaceMcToGalactic(v);
        double[] frameVelocity = {vInertial[0] - hull[0], vInertial[1] - hull[1], vInertial[2] - hull[2]};
        double[] angles = TransferKinematics.lookAngles(TransferKinematics.directionToSpace(
                TransferKinematics.lookVector(root.getYRot(), root.getXRot()), frame));
        double[] attitude = null;
        if (root instanceof Starship ship) {
            var q = ship.attitude();
            attitude = TransferKinematics.attitudeToSpace(new double[] {q.x(), q.y(), q.z(), q.w()}, frame);
        }

        prepare(riders, TransferPreparePayload.SPACE, addr, lat, lon,
                TransferAltitude.exitAltitude(seaLevel, mpb), 0, false);
        Vec3 spot = new Vec3(0.5, 0, 0.5);
        if (root != p && root instanceof LivingEntity mount) clearGravity(mount);
        Entity arrived = root.teleport(new TeleportTransition(space, spot, velocity, (float) angles[0], (float) angles[1],
                TeleportTransition.DO_NOTHING));
        if (arrived instanceof Starship ship) {
            if (attitude != null) ship.setAttitude(new Quaternionf((float) attitude[0], (float) attitude[1], (float) attitude[2], (float) attitude[3]));
            ship.setDeltaMovement(velocity);
        }
        int until = server.getTickCount() + REENTRY_COOLDOWN_TICKS;
        double hSwitch = TransferAltitude.switchAltitude(seaLevel, mpb);
        for (ServerPlayer rider : riders) {
            if (arrived == rider) {
                NavServer.moveTo(rider, target);
            } else {
                // Every rider's frame is anchored on the vehicle, so the ship sits exactly on the radial.
                double[] g = Frames.mcToGalactic(spot.x, spot.y, spot.z);
                rider.setAttached(Galactica.NAV, rider.getAttachedOrCreate(Galactica.NAV)
                        .withOrigin(target.plusMetres(-g[0], -g[1], -g[2])).withSpeed(0));
            }
            rider.setAttached(Galactica.NAV, rider.getAttachedOrCreate(Galactica.NAV).withFrame(frameVelocity, b.id()));
            rider.removeAttached(SURFACE);
            ARRIVALS.remove(rider.getUUID());
            clearGravity(rider);
            COOLDOWN.put(rider.getUUID(), until);
            REARM.put(rider.getUUID(), new Rearm(b.id(), hSwitch));
            rider.sendOverlayMessage(Component.translatable("galactica.planet.leaving", b.name()));
        }
    }

    /** A recycled slot: back to the space frame as it was, with no radial to return to. */
    private static void bareExit(ServerPlayer p) {
        ServerLevel space = p.level().getServer().getLevel(Galactica.SPACE);
        if (space == null) return;
        Galactica.LOG.info("Bare exit: {} from recycled slot {}", p.getName().getString(), p.level().dimension().identifier());
        Entity root = p.getRootVehicle();
        List<ServerPlayer> riders = ridersOf(p);
        if (root != p && root instanceof LivingEntity mount) clearGravity(mount);
        prepare(riders, TransferPreparePayload.SPACE, p.getAttached(SURFACE) != null ? p.getAttached(SURFACE).address()
                : new PlanetAddress("", 0, 0, 0, 0, 0, -1), 0, 0, 0, 0, false);
        root.teleport(new TeleportTransition(space, new Vec3(0.5, 0, 0.5), Vec3.ZERO, root.getYRot(), root.getXRot(),
                TeleportTransition.DO_NOTHING));
        int until = p.level().getServer().getTickCount() + REENTRY_COOLDOWN_TICKS;
        for (ServerPlayer rider : riders) {
            rider.removeAttached(SURFACE);
            ARRIVALS.remove(rider.getUUID());
            clearGravity(rider);
            COOLDOWN.put(rider.getUUID(), until);
        }
    }

    // ---------------------------------------------------------------- plasma

    /** Plasma from the craft's real altitude and speed on a surface (or in the Overworld above Earth). */
    private static void surfacePlasma(ServerPlayer p, Entity root, Body body, SurfaceGrid g, double seaLevel) {
        if (!(body.surfacePressureBar() > 0)) return;
        Vec3 dm = root.getDeltaMovement();
        double mpb = g.metresPerBlock();
        double speedBlocks = dm.length();
        if (speedBlocks < 1e-4) return;
        double speed = speedBlocks * mpb * TransferKinematics.TICKS_PER_SECOND;
        double alt = TransferAltitude.realAltitude(root.getY(), seaLevel, mpb);
        streamPlasma(ridersOf(p), root, body, alt, speed, new double[] {dm.x / speedBlocks, dm.y / speedBlocks, dm.z / speedBlocks}, eva(root));
    }

    /** Sends the plasma state to the riders and embers to everyone else nearby. */
    private static void streamPlasma(List<ServerPlayer> riders, Entity root, Body body, double altitude, double speed,
                                     double[] flow, boolean eva) {
        EntryAerothermo.Plasma pl = EntryAerothermo.plasma(body, altitude, speed,
                eva ? EntryAerothermo.EVA_NOSE_RADIUS : EntryAerothermo.SHIP_NOSE_RADIUS);
        if (pl.intensity() <= 0) return;
        PlasmaStatePayload msg = new PlasmaStatePayload((float) pl.intensity(), (float) pl.temperature(), (float) pl.mach(),
                (float) flow[0], (float) flow[1], (float) flow[2], (float) speed);
        for (ServerPlayer rider : riders) ServerPlayNetworking.send(rider, msg);
        // In galactica:space MC coordinates are relative to each player's own NavState origin, so block distance
        // says nothing about real distance: no observer particles there.
        if (!(root.level() instanceof ServerLevel level) || level.dimension() == Galactica.SPACE) return;
        int count = (int) Math.ceil(10 * pl.intensity());
        double w = root.getBbWidth() * 0.6 + 0.4;
        for (ServerPlayer viewer : level.players()) {
            if (riders.contains(viewer) || viewer.distanceToSqr(root) > 256 * 256) continue;
            level.sendParticles(viewer, TransferNet.PLASMA_EMBER, true, true, root.getX(), root.getY() + root.getBbHeight() * 0.5,
                    root.getZ(), count, w, root.getBbHeight() * 0.5 + 0.2, w, 0.08);
        }
    }

    private static void prepare(List<ServerPlayer> riders, int slot, PlanetAddress addr, double lat, double lon, double hSwitch,
                                int etaTicks, boolean entry) {
        TransferPreparePayload msg = new TransferPreparePayload(slot, addr, (float) lat, (float) lon, (float) hSwitch,
                Math.max(0, etaTicks), entry);
        for (ServerPlayer rider : riders) ServerPlayNetworking.send(rider, msg);
    }

    // ---------------------------------------------------------------- gravity

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

    // ---------------------------------------------------------------- small vectors

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalize(double[] a) {
        double r = Math.sqrt(dot(a, a));
        return r > 0 ? new double[] {a[0] / r, a[1] / r, a[2] / r} : new double[] {0, -1, 0};
    }

    private static double[] clamp(double[] v, double max) {
        double r = Math.sqrt(dot(v, v));
        return r > max ? new double[] {v[0] * max / r, v[1] * max / r, v[2] * max / r} : v;
    }

    // ---------------------------------------------------------------- queries

    /** The body a player stands on in a surface slot, or null (the Overworld keeps vanilla rules). */
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
