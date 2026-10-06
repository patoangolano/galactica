package dev.galactica.discovery;

import dev.galactica.Galactica;
import dev.galactica.content.GalacticaItems;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.planet.BodyFrame;
import dev.galactica.planet.PlanetAddress;
import dev.galactica.planet.PlanetEntry;
import dev.galactica.planet.PlanetSlots;
import dev.galactica.planet.SkyAlignment;
import dev.galactica.planet.SurfaceState;
import dev.galactica.science.Units;
import dev.galactica.science.spectra.Kinematics;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M5 science loop, server side: the spectrometer item, scan validation, the discovery ledger,
 * survey points and their exchange for exotic matter, and custom names.
 *
 * <p><b>A scan.</b> The client picks what is under the crosshair and sends its address
 * ({@link ScanTarget}). The server rebuilds the object from the deterministic galaxy, checks that
 * it exists and is plausibly in view (within {@link ScanRules#ACCEPT_DEG} of the player's look
 * direction from their galactic position, bright enough, in the same system for planets), then
 * computes the radial velocity ({@link Kinematics}), records the discovery, runs the transit/RV
 * survey ({@link SurveyMath}) when the star is scanned from outside its system, and answers with
 * {@link DiscoveryPayloads.ScanResult}. Codex changes go to every player.
 *
 * <p><b>Integration.</b> {@link #init()} from {@code Galactica.onInitialize}; commands call
 * {@link #rename} and {@link #redeem}.
 */
public final class Discovery {
    private Discovery() {}

    private static final ResourceKey<Item> SPECTROMETER_KEY = ResourceKey.create(Registries.ITEM, Galactica.id("spectrometer"));
    public static final Item SPECTROMETER = Registry.register(BuiltInRegistries.ITEM, SPECTROMETER_KEY,
            new SpectrometerItem(new Item.Properties().stacksTo(1).setId(SPECTROMETER_KEY)));

    /** Custom names known to the integrated/dedicated server, by {@link DiscoveryKeys} key. */
    private static final Map<String, String> SERVER_NAMES = new ConcurrentHashMap<>();
    /** Custom names synced to this client (filled by the client codex). */
    private static final Map<String, String> CLIENT_NAMES = new ConcurrentHashMap<>();

    private static final Map<UUID, String> LAST_SCAN = new HashMap<>();
    private static final Map<UUID, Integer> LAST_TICK = new HashMap<>();

    private static final int CODEX_MAX_BYTES = 8 * 1024 * 1024;

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(DiscoveryPayloads.Scan.TYPE, DiscoveryPayloads.Scan.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DiscoveryPayloads.ScanResult.TYPE, DiscoveryPayloads.ScanResult.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(DiscoveryPayloads.Codex.TYPE, DiscoveryPayloads.Codex.CODEC, CODEX_MAX_BYTES);
        ServerPlayNetworking.registerGlobalReceiver(DiscoveryPayloads.Scan.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> handleScan(ctx.player(), payload.target())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> sendFullCodex(handler.player)));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER_NAMES.clear();
            SERVER_NAMES.putAll(DiscoveryLog.get(server).ledger().customNames());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SERVER_NAMES.clear();
            LAST_SCAN.clear();
            LAST_TICK.clear();
        });
    }

    // ---------------------------------------------------------------- names

    /** The name to show for a star: its custom name if its discoverer gave one, else its designation. */
    public static String displayName(Star s) {
        return displayName(DiscoveryKeys.star(s), s.name());
    }

    /** The name to show for a planet or moon. */
    public static String displayName(Body b) {
        return displayName(DiscoveryKeys.body(b), b.name());
    }

    public static String displayName(String key, String fallback) {
        String n = CLIENT_NAMES.get(key);
        if (n == null) n = SERVER_NAMES.get(key);
        return n != null ? n : fallback;
    }

    /** Client-side name table, written only by the client codex sync. */
    public static Map<String, String> clientNames() {
        return CLIENT_NAMES;
    }

    // ---------------------------------------------------------------- commands

    /**
     * {@code /galactica name <new name>}: renames the object the player scanned last, if they were
     * its first discoverer. Returns 1 on success, 0 otherwise (with a message either way).
     */
    public static int rename(ServerPlayer p, String newName) {
        String key = LAST_SCAN.get(p.getUUID());
        if (key == null) {
            p.sendSystemMessage(Component.translatable("galactica.discovery.name.no_scan"));
            return 0;
        }
        DiscoveryLog log = DiscoveryLog.get(server(p));
        DiscoveryLedger.RenameResult r = log.ledger().rename(key, p.getUUID(), newName);
        switch (r) {
            case OK -> {
                log.setDirty();
                DiscoveryLedger.Entry e = log.ledger().get(key);
                SERVER_NAMES.put(key, e.customName());
                p.sendSystemMessage(Component.translatable("galactica.discovery.name.ok", e.label(), e.customName()));
                broadcast(server(p), List.of(e));
                return 1;
            }
            case NOT_FOUND -> p.sendSystemMessage(Component.translatable("galactica.discovery.name.no_scan"));
            case NOT_DISCOVERER -> p.sendSystemMessage(Component.translatable("galactica.discovery.name.not_first",
                    log.ledger().get(key).discovererName()));
            case BAD_NAME -> p.sendSystemMessage(Component.translatable("galactica.discovery.name.bad", DiscoveryKeys.MAX_NAME_LENGTH));
            case TAKEN -> p.sendSystemMessage(Component.translatable("galactica.discovery.name.taken"));
        }
        return 0;
    }

    /**
     * {@code /galactica redeem <units>}: buys exotic matter at {@link SurveyValue#POINTS_PER_EXOTIC}
     * points per unit. Returns the units delivered.
     */
    public static int redeem(ServerPlayer p, int units) {
        if (units <= 0) return 0;
        DiscoveryLog log = DiscoveryLog.get(server(p));
        int cost = units * SurveyValue.POINTS_PER_EXOTIC;
        if (!log.ledger().spend(p.getUUID(), cost)) {
            p.sendSystemMessage(Component.translatable("galactica.discovery.redeem.short", cost,
                    log.ledger().account(p.getUUID()).balance()));
            return 0;
        }
        log.setDirty();
        int left = units;
        while (left > 0) {
            int n = Math.min(left, GalacticaItems.EXOTIC_MATTER.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(GalacticaItems.EXOTIC_MATTER, n);
            // Adds what fits and drops the rest at the player's feet.
            p.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
            left -= n;
        }
        DiscoveryLedger.Account a = log.ledger().account(p.getUUID());
        p.sendSystemMessage(Component.translatable("galactica.discovery.redeem.ok", units, cost, a.balance()));
        sendCodex(p, false, List.of());
        return units;
    }

    // ---------------------------------------------------------------- scanning

    /** Where and how fast the observer is, and the target seen from there. */
    private record View(GalacticPos targetPos, double[] targetVel, GalacticPos observer, double[] observerVel, boolean inside,
                        double radiusMetres) {}

    static void handleScan(ServerPlayer p, ScanTarget t) {
        MinecraftServer server = server(p);
        int now = server.getTickCount();
        Integer last = LAST_TICK.get(p.getUUID());
        if (last != null && now - last < ScanRules.COOLDOWN_TICKS) return;
        LAST_TICK.put(p.getUUID(), now);

        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        double time = nav.timeSeconds();
        View view;
        ScanTarget.Resolved r;
        boolean inSpace = p.level().dimension() == Galactica.SPACE;
        if (inSpace) {
            GalacticPos here = Frames.playerPos(nav, p.getX(), p.getEyeY(), p.getZ());
            if (t.index() != ScanTarget.CATALOG && !ScanRules.sectorInReach(t.sector(), here.xPc(), here.yPc(), here.zPc())) {
                refuse(p, t, "galactica.scan.refused.unseen");
                return;
            }
            r = t.resolve(Galactica.GALAXY);
            if (r == null) {
                refuse(p, t, "galactica.scan.refused.unknown");
                return;
            }
            view = spaceView(p, here, time, r);
        } else if (PlanetSlots.slotOf(p.level().dimension()) >= 0) {
            r = t.resolve(Galactica.GALAXY);
            if (r == null) {
                refuse(p, t, "galactica.scan.refused.unknown");
                return;
            }
            view = surfaceView(p, time, r);
        } else {
            refuse(p, t, "galactica.scan.refused.dimension");
            return;
        }
        if (view == null) {
            refuse(p, t, "galactica.scan.refused.unseen");
            return;
        }
        double[] los = view.targetPos().metresFrom(view.observer());
        double[] look = Frames.mcToGalactic(p.getLookAngle().x, p.getLookAngle().y, p.getLookAngle().z);
        if (ScanRules.angleDeg(los, look) > ScanRules.acceptDeg(view.radiusMetres(), view.targetPos().distanceMetres(view.observer()))) {
            refuse(p, t, "galactica.scan.refused.aim");
            return;
        }
        double rv = Kinematics.radialVelocity(view.targetVel(), view.observerVel(), los);
        double distPc = view.targetPos().distanceMetres(view.observer()) / Units.PARSEC;

        DiscoveryLog log = DiscoveryLog.get(server);
        DiscoveryLedger ledger = log.ledger();
        UUID who = p.getUUID();
        String whoName = p.getName().getString();
        long gameTime = p.level().getGameTime();
        List<DiscoveryLedger.Entry> changed = new ArrayList<>();
        DiscoveryLedger.Outcome main;
        String key;
        if (r.isStar()) {
            Star s = r.star();
            key = DiscoveryKeys.star(s);
            main = ledger.recordScan(key, DiscoveryLedger.KIND_STAR, s.name(), "", s.spectralType(), who, whoName, gameTime,
                    SurveyValue.starPoints(s));
        } else {
            Body b = r.body();
            boolean moon = r.moon() != null;
            key = DiscoveryKeys.body(b);
            String detail = b.type().label + (moon ? " / " + r.planet().name() : "");
            main = ledger.recordScan(key, moon ? DiscoveryLedger.KIND_MOON : DiscoveryLedger.KIND_PLANET, b.name(),
                    r.star().name(), detail, who, whoName, gameTime, SurveyValue.bodyPoints(b, moon));
        }
        changed.add(main.entry());
        int awarded = main.awarded();

        List<DiscoveryPayloads.Revealed> revealed = new ArrayList<>();
        if (r.isStar() && !view.inside()) {
            List<Body> planets = SystemGenerator.generate(r.star());
            for (SurveyMath.Detection d : SurveyMath.survey(r.star(), planets)) {
                Body pl = planets.get(d.planetIndex());
                String method = d.transit() && d.rv() ? DiscoveryLedger.METHOD_TRANSIT_RV
                        : d.transit() ? DiscoveryLedger.METHOD_TRANSIT : DiscoveryLedger.METHOD_RV;
                DiscoveryLedger.Outcome o = ledger.recordSurvey(DiscoveryKeys.body(pl), DiscoveryLedger.KIND_PLANET, pl.name(),
                        r.star().name(), pl.type().label, who, whoName, gameTime, SurveyValue.bodyPoints(pl, false), method);
                if (o.first()) changed.add(o.entry());
                awarded += o.awarded();
                revealed.add(new DiscoveryPayloads.Revealed(d.planetIndex(), d.transit(), d.rv(), d.kMs(), d.periodDays(), d.depthPpm(), o.first()));
            }
        }
        if (awarded > 0 || main.first()) log.setDirty();
        LAST_SCAN.put(who, key);

        DiscoveryLedger.Entry e = main.entry();
        ServerPlayNetworking.send(p, new DiscoveryPayloads.ScanResult(t, "", rv, distPc, view.inside(), e.discovererName(),
                e.gameTime(), main.first(), awarded, ledger.account(who).balance(), e.customName(),
                List.copyOf(revealed.subList(0, Math.min(DiscoveryPayloads.MAX_REVEALED, revealed.size())))));
        if (awarded > 0 || main.first()) broadcast(server, changed);
    }

    private static View spaceView(ServerPlayer p, GalacticPos here, double time, ScanTarget.Resolved r) {
        Neighbourhood nb = NavServer.neighbourhood(p);
        Star sys = nb.systemStar(here);
        boolean inside = sys != null && sys.id() == r.star().id();
        double[] obsVel = sys != null ? Kinematics.starVelocity(sys) : Kinematics.circularVelocity(here.xPc(), here.yPc(), here.zPc());
        if (r.isStar()) {
            Star s = r.star();
            GalacticPos pos = Neighbourhood.starPos(s);
            double distPc = pos.distanceMetres(here) / Units.PARSEC;
            // Inside a system its star is always in view (a black hole by its accretion light); outside, it must be bright enough.
            if (!inside && !ScanRules.starVisible(s, distPc)) return null;
            return new View(pos, Kinematics.starVelocity(s), here, obsVel, inside, s.radiusMetres());
        }
        if (!inside) return null;
        Body target = r.body();
        for (Neighbourhood.Located l : nb.locateBodies(here, time)) {
            if (l.body().id() == target.id()) {
                return new View(l.pos(), ScanPhysics.bodyVelocity(r.star(), r.planet(), r.moon(), time), here, obsVel, true,
                        target.radiusMetres());
            }
        }
        return null;
    }

    private static View surfaceView(ServerPlayer p, double time, ScanTarget.Resolved r) {
        SurfaceState st = p.getAttached(PlanetEntry.SURFACE);
        if (st == null || !r.isStar()) return null;
        PlanetAddress.Resolved here = st.address().resolve(Galactica.GALAXY);
        if (here == null || here.star().id() != r.star().id() || r.star().kind() == StarKind.BLACK_HOLE) return null;
        // The surface sky puts the host star on vanilla's sun (SkyAlignment): the aim check is against that direction.
        float sunDeg = p.level().environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, p.position());
        double[] sunMc = SkyAlignment.vanillaSun(Math.toRadians(sunDeg));
        if (sunMc[1] < -0.05) return null;
        Star s = r.star();
        GalacticPos starPos = Neighbourhood.starPos(s);
        double[] off = BodyFrame.offsetFromStar(s, here.planet(), here.moon(), time);
        GalacticPos observer = starPos.plusMetres(off[0], off[1], off[2]);
        double[] obsVel = ScanPhysics.bodyVelocity(s, here.planet(), here.moon(), time);
        // Express the target along the sky direction so the look check compares like with like.
        double dist = starPos.distanceMetres(observer);
        double[] g = Frames.mcToGalactic(sunMc[0], sunMc[1], sunMc[2]);
        GalacticPos apparent = observer.plusMetres(g[0] * dist, g[1] * dist, g[2] * dist);
        double[] trueLos = starPos.metresFrom(observer);
        double[] v = Kinematics.starVelocity(s);
        // Radial velocity must use the true geometry; shift the target velocity so that its projection on
        // the apparent line of sight equals the true one.
        double rvTrue = Kinematics.radialVelocity(v, obsVel, trueLos);
        double[] u = {g[0], g[1], g[2]};
        double[] tv = {obsVel[0] + rvTrue * u[0], obsVel[1] + rvTrue * u[1], obsVel[2] + rvTrue * u[2]};
        return new View(apparent, tv, observer, obsVel, true, s.radiusMetres());
    }

    private static void refuse(ServerPlayer p, ScanTarget t, String key) {
        ServerPlayNetworking.send(p, DiscoveryPayloads.ScanResult.refused(t, key));
    }

    // ---------------------------------------------------------------- codex sync

    private static void sendFullCodex(ServerPlayer p) {
        if (!ServerPlayNetworking.canSend(p, DiscoveryPayloads.Codex.TYPE)) return;
        sendCodex(p, true, DiscoveryLog.get(server(p)).ledger().snapshot());
    }

    private static void sendCodex(ServerPlayer p, boolean replace, List<DiscoveryLedger.Entry> entries) {
        if (!ServerPlayNetworking.canSend(p, DiscoveryPayloads.Codex.TYPE)) return;
        DiscoveryLedger.Account a = DiscoveryLog.get(server(p)).ledger().account(p.getUUID());
        ServerPlayNetworking.send(p, new DiscoveryPayloads.Codex(replace, entries, a.balance(), a.lifetime()));
    }

    private static void broadcast(MinecraftServer server, List<DiscoveryLedger.Entry> changed) {
        List<DiscoveryLedger.Entry> copy = List.copyOf(changed);
        for (ServerPlayer q : server.getPlayerList().getPlayers()) sendCodex(q, false, copy);
    }

    private static MinecraftServer server(ServerPlayer p) {
        return p.level().getServer();
    }
}
