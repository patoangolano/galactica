package dev.galactica.client.planet;

import dev.galactica.planet.EntryHeating;
import dev.galactica.planet.transfer.PlasmaStatePayload;
import dev.galactica.planet.transfer.TransferPreparePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client state of the seamless transfer.
 *
 * <ul>
 *   <li><b>Arming.</b> {@link TransferPreparePayload} arms a one-shot flag for {@link #ARM_SECONDS}. The next
 *       respawn consumes it ({@code LoadingScreenMixin} → {@link #consumeArmed()}), which skips the vanilla
 *       {@code LevelLoadingScreen} for that switch only and timestamps the switch. The level-load tracker keeps
 *       running (it is ticked by {@code ClientPacketListener.tick}, not by the screen), so
 *       {@code notifyPlayerLoaded()} and {@code setClientLoaded(true)} still fire.</li>
 *   <li><b>Plasma.</b> {@link PlasmaStatePayload} gives the instantaneous Sutton–Graves strength; the visible
 *       glow follows it through {@link EntryHeating#glow} (thermal lag), frame by frame, so it carries across the
 *       switch without a blink.</li>
 *   <li><b>Mask.</b> A smooth veil that peaks (0.9) at the switch: rising over the last
 *       {@link #MASK_RISE_SECONDS} of the server's ETA and falling over {@link #MASK_FALL_SECONDS} after it,
 *       with the copied last frame cross-faded out over {@link #CROSSFADE_SECONDS} ({@link TransferFx}).</li>
 * </ul>
 * Input is never frozen by us; during the round trip the craft keeps its velocity locally (the ship
 * prediction integrates its own delta movement), so motion is continuous through the switch.
 */
public final class TransferClient {
    private TransferClient() {}

    public static final double ARM_SECONDS = 10.0;
    public static final double MASK_PEAK = 0.9;
    public static final double MASK_RISE_SECONDS = 0.6;
    public static final double MASK_FALL_SECONDS = 0.8;
    public static final double CROSSFADE_SECONDS = 0.5;
    /** A plasma packet older than this no longer feeds the glow (it then cools on its own). */
    private static final double PLASMA_STALE_SECONDS = 0.2;
    /** A prepare packet older than this no longer predicts the switch. */
    private static final double PREPARE_STALE_SECONDS = 1.5;

    private static long armedUntil;
    private static boolean armedEntry;
    private static long prepareAt;
    private static int prepareEta = Integer.MAX_VALUE;
    private static long switchedAt;

    private static float plasmaInstant, plasmaTemperature, plasmaMach, plasmaSpeed;
    private static Vec3 flow = new Vec3(0, -1, 0);
    private static long plasmaAt;
    private static double glow;
    private static long lastFrame;

    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(TransferPreparePayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> onPrepare(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PlasmaStatePayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> onPlasma(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(TransferClient::tick);
        TransferFx.init();
    }

    private static void onPrepare(TransferPreparePayload p) {
        long now = System.nanoTime();
        armedUntil = now + (long) (ARM_SECONDS * 1e9);
        armedEntry = p.entry();
        prepareAt = now;
        prepareEta = p.etaTicks();
    }

    private static void onPlasma(PlasmaStatePayload p) {
        plasmaAt = System.nanoTime();
        plasmaInstant = Math.clamp(p.intensity(), 0F, 1F);
        plasmaTemperature = p.temperature();
        plasmaMach = p.mach();
        plasmaSpeed = p.speed();
        Vec3 f = new Vec3(p.flowX(), p.flowY(), p.flowZ());
        if (f.lengthSqr() > 1e-6) flow = f.normalize();
    }

    /**
     * Called by {@code LoadingScreenMixin} inside {@code ClientPacketListener.startWaitingForNewLevel}: true (once)
     * when a transfer armed the client, in which case the loading screen is not shown. Marks the switch instant.
     */
    public static boolean consumeArmed() {
        long now = System.nanoTime();
        boolean armed = armedUntil != 0 && now < armedUntil;
        armedUntil = 0;
        prepareEta = Integer.MAX_VALUE;
        if (armed) switchedAt = now;
        return armed;
    }

    /** True while armed and the switch is under a second away: the frame should be copied for the cross-fade. */
    public static boolean captureWanted() {
        long now = System.nanoTime();
        return armedUntil != 0 && now < armedUntil && secondsToSwitch(now) < 1.0;
    }

    private static double secondsToSwitch(long now) {
        if (prepareEta == Integer.MAX_VALUE || (now - prepareAt) / 1e9 > PREPARE_STALE_SECONDS) return Double.POSITIVE_INFINITY;
        return Math.max(0, prepareEta / 20.0 - (now - prepareAt) / 1e9);
    }

    private static double smooth(double x) {
        x = Math.clamp(x, 0.0, 1.0);
        return x * x * (3 - 2 * x);
    }

    // ---------------------------------------------------------------- per-frame view of the state

    /** Advances the thermal glow to now. Call once per frame before reading the getters. */
    public static void frame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1e9);
        lastFrame = now;
        double instant = (now - plasmaAt) / 1e9 < PLASMA_STALE_SECONDS ? plasmaInstant : 0;
        glow = EntryHeating.glow(glow, instant, dt);
    }

    /** Visible plasma strength, 0..1. */
    public static double glow() {
        return glow;
    }

    /** Wall temperature normalised to the colour ramp: 900 K (dull red) → 3 500 K (white-violet). */
    public static double temperatureNorm() {
        return Math.clamp((plasmaTemperature - 900.0) / 2600.0, 0.0, 1.0);
    }

    public static double mach() {
        return plasmaMach;
    }

    public static double speed() {
        return plasmaSpeed;
    }

    /** Unit direction of motion of the craft (block axes of the current dimension). */
    public static Vec3 flow() {
        return flow;
    }

    /** The switch veil, 0..{@link #MASK_PEAK}: rising before the predicted switch, falling after it. */
    public static double mask() {
        long now = System.nanoTime();
        double pre = 0;
        if (armedUntil != 0 && now < armedUntil) pre = MASK_PEAK * smooth(1 - secondsToSwitch(now) / MASK_RISE_SECONDS);
        double post = 0;
        if (switchedAt != 0) {
            double since = (now - switchedAt) / 1e9;
            post = since < MASK_FALL_SECONDS ? MASK_PEAK * (1 - smooth(since / MASK_FALL_SECONDS)) : 0;
        }
        return Math.max(pre, post);
    }

    /** Opacity of the copied pre-switch frame, 1 at the switch fading to 0. */
    public static double crossfade() {
        if (switchedAt == 0) return 0;
        double since = (System.nanoTime() - switchedAt) / 1e9;
        return since < CROSSFADE_SECONDS ? 1 - smooth(since / CROSSFADE_SECONDS) : 0;
    }

    /** Nanotime of the last switch, 0 if none. */
    public static long switchedAt() {
        return switchedAt;
    }

    /** True for an entry (space → ground) switch, false for a take-off. */
    public static boolean armedEntry() {
        return armedEntry;
    }

    // ---------------------------------------------------------------- embers

    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;

    private static void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            // A level change that did not pass through the loading-screen call (a loading screen was already
            // up) still ends the armed window, so a later unrelated respawn shows its screen as usual.
            if (lastLevel != null && mc.level != null && armedUntil != 0) consumeArmed();
            lastLevel = mc.level;
        }
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        if (armedUntil != 0 && System.nanoTime() >= armedUntil) armedUntil = 0;
        if (glow < 0.05) return;
        Entity root = mc.player.getRootVehicle();
        RandomSource rnd = mc.level.getRandom();
        int n = (int) Math.ceil(glow * 7);
        double r = Math.max(root.getBbWidth(), root.getBbHeight()) * 0.6 + 0.4;
        Vec3 centre = root.position().add(0, root.getBbHeight() * 0.5, 0);
        Vec3 f = flow;
        double streak = 0.35 + 0.6 * glow;
        for (int k = 0; k < n; k++) {
            // Born on the windward hemisphere of the shock, carried downstream by the flow.
            Vec3 d = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
            if (d.dot(f) < 0) d = d.subtract(f.scale(2 * d.dot(f)));
            Vec3 at = centre.add(d.scale(r * (1.05 + 0.25 * rnd.nextDouble())));
            Vec3 v = f.scale(-streak * (0.6 + 0.8 * rnd.nextDouble())).add(d.scale(0.05));
            mc.level.addParticle(dev.galactica.planet.transfer.TransferNet.PLASMA_EMBER, true, true, at.x, at.y, at.z, v.x, v.y, v.z);
        }
    }
}
