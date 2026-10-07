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
 *   <li>per tick, a pilot stays mounted on the same ship.</li>
 * </ul>
 * Per-tick positions are logged for the 3 ticks before and after every recentre.
 */
public final class OriginShiftAutoTest {
    private OriginShiftAutoTest() {}

    private static boolean installed, active;
    private static String phase = "";
    private static Vec3 velocity = Vec3.ZERO;
    private static int ticks, recentres, shiftsAtStart, frames, frameFailures, backwardFailures, mountFailures, logAfter;
    private static int shipId = -1;
    private static double worstRatio;
    private static GalacticPos start, lastFrame;
    private static long lastFrameNs;
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
        ticks = recentres = frames = frameFailures = backwardFailures = mountFailures = logAfter = 0;
        worstRatio = 0;
        shiftsAtStart = OriginShiftClient.applied;
        shipId = p.getVehicle() instanceof Starship s ? s.getId() : -1;
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
        boolean ok = recentres >= 3 && shifts >= 3 && frameFailures == 0 && backwardFailures == 0 && mountFailures == 0 && frames > 100;
        Galactica.LOG.info(String.format(Locale.ROOT,
                "[autotest] %s origin-recentre %s: recentres=%d shiftsApplied=%d ticks=%d frames=%d worstFrameJump=%.2f of allowed, "
                        + "frameJumps=%d backwards=%d dismounts=%d", ok ? "PASS" : "FAIL", phase, recentres, shifts, ticks, frames,
                worstRatio, frameFailures, backwardFailures, mountFailures));
    }

    private static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        LocalPlayer p = mc.player;
        Entity root = p.getRootVehicle();
        root.setDeltaMovement(velocity);
        ticks++;
        if ("ship".equals(phase) && !(p.getVehicle() instanceof Starship s && s.getId() == shipId)) {
            mountFailures++;
            Galactica.LOG.warn("[autotest] origin-recentre {}: tick {} pilot not aboard ship {} (vehicle {})", phase, ticks, shipId, p.getVehicle());
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
        long now = System.nanoTime();
        if (start == null) start = viewer;
        if (lastFrame != null) {
            frames++;
            double[] d = viewer.metresFrom(lastFrame);
            double dist = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            double dtTicks = (now - lastFrameNs) / 5.0e7;
            double speed = velocity.length();
            double allowed = speed * Math.max(1.0, dtTicks) * 1.5 + 2.0;
            worstRatio = Math.max(worstRatio, dist / allowed);
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
        lastFrameNs = now;
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
