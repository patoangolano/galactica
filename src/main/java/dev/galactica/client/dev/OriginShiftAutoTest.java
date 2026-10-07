package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.nav.OriginShiftClient;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Autotest of the floating-origin recentre. While a phase runs, the craft (the piloted ship, or the player on EVA)
 * is held at a fixed high velocity so it crosses the {@code FloatingOrigin} limit several times, and two
 * invariants are checked:
 * <ul>
 *   <li>per rendered frame, the camera's <i>galactic</i> position (origin + camera, exactly what the sky is drawn
 *       from) moves no more than the velocity allows for the frame time, and never backwards along it — a
 *       recentre whose block shift and origin change did not land together, or a rubber-band, breaks this;</li>
 *   <li>per tick, a pilot stays mounted on the same ship <i>instance</i>, and that instance is still the one in the
 *       client level and not removed — a ship the server removed and re-added (the reported blinking) changes
 *       instance even when the pilot ends up remounted within the tick;</li>
 *   <li>per frame, the ship (or the EVA player) moves exactly one step per tick: a second "strict" counter with
 *       almost no slack is logged for diagnosis (a double tick or a half-applied shift shows up there first).</li>
 * </ul>
 * Per-tick positions are logged for the 3 ticks before and after every recentre.
 */
public final class OriginShiftAutoTest {
    private OriginShiftAutoTest() {}

    private static boolean installed, active;
    private static String phase = "";
    private static Vec3 velocity = Vec3.ZERO;
    private static int ticks, recentres, shiftsAtStart, frames, frameFailures, backwardFailures, mountFailures, logAfter;
    private static int shipId = -1, shipLost, strictJumps;
    private static Starship ship;
    private static double worstRatio;
    private static GalacticPos start, lastFrame;
    private static double lastFrameTime;
    private static Vec3 lastLocal;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();

    /** Starts a phase: {@code ship} (piloted ship) or {@code eva}; velocity in blocks per tick. */
    static void start(String name, Vec3 blocksPerTick) {
        if (!installed) {
            installed = true;
            ClientTickEvents.END_CLIENT_TICK.register(OriginShiftAutoTest::tick);
        }
        LocalPlayer p = Minecraft.getInstance().player;
        phase = name;
        velocity = blocksPerTick;
        ticks = recentres = frames = frameFailures = backwardFailures = mountFailures = logAfter = shipLost = strictJumps = 0;
        worstRatio = 0;
        shiftsAtStart = OriginShiftClient.applied;
        ship = p.getVehicle() instanceof Starship s ? s : null;
        shipId = ship != null ? ship.getId() : -1;
        start = lastFrame = null;
        lastLocal = null;
        RECENT.clear();
        active = true;
        SkyManager.frameProbe = OriginShiftAutoTest::frame;
        Galactica.LOG.info("[autotest] origin-recentre {}: start at {} with {} blocks/tick (ship id {})", name,
                fmt(p.getRootVehicle().position()), fmt(blocksPerTick), shipId);
    }

    /** Ends the phase, stops the craft and logs PASS/FAIL. */
    static void finish() {
        active = false;
        SkyManager.frameProbe = null;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.getRootVehicle().setDeltaMovement(Vec3.ZERO);
        int shifts = OriginShiftClient.applied - shiftsAtStart;
        boolean ok = recentres >= 3 && shifts >= 3 && frameFailures == 0 && backwardFailures == 0 && mountFailures == 0
                && shipLost == 0 && frames > 100;
        Galactica.LOG.info(String.format(Locale.ROOT,
                "[autotest] %s origin-recentre %s: recentres=%d shiftsApplied=%d ticks=%d frames=%d worstFrameJump=%.2f of allowed, "
                        + "frameJumps=%d backwards=%d dismounts=%d shipRemoved=%d strictJumps=%d", ok ? "PASS" : "FAIL", phase, recentres,
                shifts, ticks, frames, worstRatio, frameFailures, backwardFailures, mountFailures, shipLost, strictJumps));
    }

    private static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        LocalPlayer p = mc.player;
        Entity root = p.getRootVehicle();
        root.setDeltaMovement(velocity);
        ticks++;
        if ("ship".equals(phase) && !(p.getVehicle() instanceof Starship s && s.getId() == shipId)) {
            if (mountFailures++ < 5)
                Galactica.LOG.warn("[autotest] origin-recentre {}: tick {} pilot not aboard ship {} (vehicle {})", phase, ticks, shipId, p.getVehicle());
        }
        if (ship != null && (ship.isRemoved() || mc.level.getEntity(shipId) != ship || p.getVehicle() != ship)) {
            if (shipLost++ < 5)
                Galactica.LOG.warn("[autotest] origin-recentre {}: tick {} ship {} was removed or replaced on the client (removed={}, now {})",
                        phase, ticks, shipId, ship.isRemoved(), mc.level.getEntity(shipId));
            if (p.getVehicle() instanceof Starship again) ship = again; // count each loss once
        }
        Vec3 local = root.position();
        GalacticPos viewer = SkyManager.get().viewer();
        double[] gal = viewer == null || start == null ? new double[3] : viewer.metresFrom(start);
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        String line = String.format(Locale.ROOT, "tick %d craft=%s cam=%s galFromStart=(%.1f, %.1f, %.1f) v=%.2f",
                ticks, fmt(local), fmt(cam), gal[0], gal[1], gal[2], root.getDeltaMovement().length());
        if (lastLocal != null && local.distanceTo(lastLocal) > 500) {
            recentres++;
            Galactica.LOG.info("[autotest] origin-recentre {} #{} (shift {})", phase, recentres, fmt(local.subtract(lastLocal)));
            for (String s : RECENT) Galactica.LOG.info("[autotest]   before: {}", s);
            logAfter = 4;
        }
        if (logAfter > 0) {
            logAfter--;
            Galactica.LOG.info("[autotest]   {}: {}", logAfter == 3 ? "at" : "after", line);
        }
        RECENT.addLast(line);
        if (RECENT.size() > 3) RECENT.removeFirst();
        lastLocal = local;
    }

    /** Once per rendered frame with the camera's galactic position (SkyManager). */
    private static void frame(GalacticPos viewer) {
        if (!active || ticks < 10) return;
        // Game time of this frame: the camera is interpolated between the last two ticks by exactly this partial
        // tick, so the motion allowed since the previous frame is velocity x elapsed game time (no wall-clock jitter).
        double now = ticks + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (start == null) start = viewer;
        if (lastFrame != null) {
            frames++;
            double[] d = viewer.metresFrom(lastFrame);
            double dist = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            double dtTicks = Math.max(0.0, now - lastFrameTime);
            double speed = velocity.length();
            double allowed = speed * dtTicks * 1.25 + 3.0;
            worstRatio = Math.max(worstRatio, dist / allowed);
            if (dist > speed * dtTicks * 1.05 + 0.5 && strictJumps++ < 5)
                Galactica.LOG.info(String.format(Locale.ROOT, "[autotest] origin-recentre %s: strict: %.2f blocks in %.2f ticks at tick %d",
                        phase, dist, dtTicks, ticks));
            if (dist > allowed) {
                frameFailures++;
                Galactica.LOG.warn(String.format(Locale.ROOT, "[autotest] origin-recentre %s: camera jumped %.2f blocks in %.2f ticks (allowed %.2f) at tick %d",
                        phase, dist, dtTicks, allowed, ticks));
            }
            double[] dir = Frames.mcToGalactic(velocity.x / speed, velocity.y / speed, velocity.z / speed);
            double along = d[0] * dir[0] + d[1] * dir[1] + d[2] * dir[2];
            if (along < -2.0) {
                backwardFailures++;
                Galactica.LOG.warn(String.format(Locale.ROOT, "[autotest] origin-recentre %s: camera moved %.2f blocks backwards at tick %d",
                        phase, -along, ticks));
            }
        }
        lastFrame = viewer;
        lastFrameTime = now;
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
