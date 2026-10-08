package dev.galactica.client.nav;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.client.map.GalaxyMapClient;
import dev.galactica.client.ship.ShipClient;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.autopilot.AutopilotKinematics;
import dev.galactica.nav.autopilot.AutopilotLegs;
import dev.galactica.nav.autopilot.AutopilotMath;
import dev.galactica.nav.autopilot.AutopilotServer;
import dev.galactica.nav.autopilot.AutopilotState;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.nav.target.NavTargets;
import dev.galactica.nav.target.TargetGeometry;
import dev.galactica.net.AutopilotPayload;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * The pilot's half of the autopilot: it flies the phase the server names ({@link AutopilotServer}) through the very
 * controls a pilot uses. The key state goes into the player's input record ({@code KeyboardInputAutopilotMixin}), which
 * both feeds the ship's prediction and travels to the server in the vanilla input packet (main engine, warp, RCS);
 * turns go through {@link Starship#addMouseInput} and flight assist through {@link Starship#toggleCoupled}, so the
 * ship's own report ({@code ShipControlPayload}) carries the result and the server validates it as for any pilot.
 *
 * <p>Any manual input hands control back: a movement, jump, sneak or sprint key, the roll keys, or a deliberate mouse
 * movement. The client stops flying at once and tells the server, which clears the state.
 */
public final class AutopilotClient {
    private AutopilotClient() {}

    public static KeyMapping toggleKey;
    /** The mode the P key engages; the map's Orbit / Land choice updates it. */
    private static AutopilotState.Mode mode = AutopilotState.Mode.ORBIT;

    private static @Nullable Input override;
    private static boolean suppressed, warping, burning, stageTouchdown;
    private static int engagedTicks;
    private static double mouseAccum;
    private static final boolean AUTOTEST = Boolean.getBoolean("galactica.autotest");
    /** Mouse counts within a second that take control back (0.15° each, as Entity.turn scales them). */
    private static final double MOUSE_OVERRIDE = 150;
    private static int mouseWindow;
    /** Last guidance numbers, for the autotest log. */
    public static volatile String debug = "";

    public static void initClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galactica.autopilot", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_P, GalaxyMapClient.CATEGORY));
        ClientTickEvents.START_CLIENT_TICK.register(AutopilotClient::tick);
    }

    public static AutopilotState.Mode mode() {
        return mode;
    }

    public static void setMode(AutopilotState.Mode m) {
        mode = m;
    }

    public static void engage(AutopilotState.Mode m) {
        mode = m;
        suppressed = false;
        if (ClientPlayNetworking.canSend(AutopilotPayload.TYPE)) {
            ClientPlayNetworking.send(new AutopilotPayload(m == AutopilotState.Mode.LAND ? AutopilotPayload.ENGAGE_LAND : AutopilotPayload.ENGAGE_ORBIT));
        }
    }

    public static void disengage() {
        stopFlying();
        if (ClientPlayNetworking.canSend(AutopilotPayload.TYPE)) ClientPlayNetworking.send(new AutopilotPayload(AutopilotPayload.DISENGAGE));
    }

    /** Manual input while flying: stop now, and tell the server (it replies with the state turned off). */
    public static void manual(String why) {
        if (override == null) return;
        Galactica.LOG.info("Autopilot: manual input ({}), handing control back", why);
        stopFlying();
        suppressed = true;
        if (ClientPlayNetworking.canSend(AutopilotPayload.TYPE)) ClientPlayNetworking.send(new AutopilotPayload(AutopilotPayload.DISENGAGE_MANUAL));
    }

    private static void stopFlying() {
        override = null;
        warping = burning = stageTouchdown = false;
    }

    /** True while this client flies the course (its input record is the autopilot's). */
    public static boolean flying() {
        return override != null;
    }

    /**
     * From the end of {@code KeyboardInput.tick}: the real keys, which hand control back when any is held after the
     * engagement settled; otherwise the autopilot's input record, or null to keep the real keys.
     */
    public static @Nullable Input filterInput(Input raw) {
        if (override == null) return null;
        boolean any = raw.forward() || raw.backward() || raw.left() || raw.right() || raw.jump() || raw.shift() || raw.sprint();
        if (any && engagedTicks > 10) {
            manual("key " + raw);
            return null;
        }
        return override;
    }

    /** From {@code Entity.turn} of the local player: true swallows the movement (small jitter); a real one disengages. */
    public static boolean onMouse(double dx, double dy) {
        if (override == null) return false;
        // Dev autotest: the desk's mouse is not the test's pilot (the scripted override uses a key mapping instead).
        if (AUTOTEST) return true;
        mouseAccum += Math.abs(dx) + Math.abs(dy);
        // A deliberate movement (over ~20° of turn within a second), not a nudge of the desk.
        if (mouseAccum > MOUSE_OVERRIDE && engagedTicks > 10) {
            manual(String.format(java.util.Locale.ROOT, "mouse %.0f", mouseAccum));
            return false;
        }
        return true;
    }

    private static void tick(Minecraft mc) {
        TargetReadout.tick(mc);
        if (++mouseWindow >= 20) {
            mouseWindow = 0;
            mouseAccum = 0;
        }
        LocalPlayer pl = mc.player;
        if (pl == null) {
            stopFlying();
            return;
        }
        while (toggleKey.consumeClick()) {
            if (AutopilotServer.state(pl).engaged()) disengage();
            else engage(mode);
        }
        AutopilotState s = AutopilotServer.state(pl);
        if (!s.engaged()) {
            stopFlying();
            suppressed = false;
            engagedTicks = 0;
            return;
        }
        if (suppressed) return;
        engagedTicks++;
        Starship ship = ShipClient.piloted(mc);
        boolean eva = ship == null && pl.getVehicle() == null;
        if (ship == null && !eva) {
            stopFlying();
            return;
        }
        if (ship != null && engagedTicks > 10 && (ShipClient.ROLL_LEFT.isDown() || ShipClient.ROLL_RIGHT.isDown())) {
            override = new Input(false, false, false, false, false, false, false);
            manual("roll keys");
            return;
        }
        try {
            fly(mc, pl, ship, s);
        } catch (RuntimeException e) {
            Galactica.LOG.error("Autopilot guidance failed", e);
            disengage();
        }
    }

    private static final Input IDLE = new Input(false, false, false, false, false, false, false);

    private static void fly(Minecraft mc, LocalPlayer pl, @Nullable Starship ship, AutopilotState s) {
        NavTarget t = NavTargets.get(pl);
        NavState nav = pl.getAttached(Galactica.NAV);
        if (t == null || nav == null) {
            override = IDLE;
            return;
        }
        boolean space = mc.level.dimension() == Galactica.SPACE;
        if (!space) {
            if (ship != null && s.phase() == AutopilotState.Phase.LANDING || ship != null && s.phase() == AutopilotState.Phase.DESCENT) {
                land(mc, ship);
            } else {
                override = IDLE;
            }
            return;
        }
        var root = pl.getRootVehicle();
        GalacticPos here = Frames.playerPos(nav, root.getX(), root.getY(), root.getZ());
        double time = nav.timeSeconds();
        TargetGeometry.Resolved r = TargetGeometry.resolve(Galactica.GALAXY, t, time);
        if (r == null) {
            override = IDLE;
            return;
        }
        Neighbourhood nb = SkyManager.get().neighbourhood();
        switch (s.phase()) {
            case ALIGN, WARP -> warp(pl, ship, nav, here, t, r, nb);
            case INSERT, DESCENT -> {
                if (ship == null) {
                    override = IDLE;
                    return;
                }
                powered(ship, nav, here, r, nb, s.phase() == AutopilotState.Phase.DESCENT);
            }
            default -> {
                // Jump spool (or idle): hands off, assist on so the ship holds still.
                override = IDLE;
                if (ship != null) {
                    if (!ship.coupled()) ship.toggleCoupled();
                    ship.setPilotInput(IDLE, 0F);
                }
            }
        }
    }

    /** Points at the leg's aim and holds the warp key while aligned, releasing it short of the arrival. */
    private static void warp(LocalPlayer pl, @Nullable Starship ship, NavState nav, GalacticPos here, NavTarget t,
                             TargetGeometry.Resolved r, Neighbourhood nb) {
        AutopilotLegs.Leg leg = AutopilotLegs.leg(t, r, nb.systemStar(here));
        double[] g = leg.aim().metresFrom(here);
        double[] m = Frames.galacticToMc(g[0], g[1], g[2]);
        Vector3d want = new Vector3d(m[0], m[1], m[2]).normalize();
        double surface = leg.surfaceDistance(here);
        boolean release = leg.last() && surface <= AutopilotLegs.releaseDistance(leg.arrival(), nav.speed());
        double err;
        if (ship != null) {
            Quaterniond q = attitude(ship);
            double[] turn = AutopilotMath.steer(q, want, 0.7);
            ship.addMouseInput((float) turn[0], (float) turn[1]);
            err = AutopilotMath.noseError(q, want);
            if (!ship.coupled()) ship.toggleCoupled();
        } else {
            err = steerHead(pl, want);
            pl.getAbilities().flying = true;
        }
        warping = !release && (warping ? err < 8.0 : err < AutopilotMath.WARP_CONE_DEG);
        override = new Input(false, false, false, false, false, false, warping);
        if (ship != null) ship.setPilotInput(override, 0F);
        debug = String.format(java.util.Locale.ROOT, "warp err %.1f deg surface %.0f m release %s speed %.3g", err, surface, release, nav.speed());
    }

    /** Orbit insertion or powered descent: turn along the velocity still to gain and fire the main engine once aligned. */
    private static void powered(Starship ship, NavState nav, GalacticPos here, TargetGeometry.Resolved r, Neighbourhood nb, boolean descent) {
        Vec3 dm = ship.getDeltaMovement();
        Vector3d f = ship.frameReference();
        double[] own = Frames.mcToGalactic(dm.x * 20 + f.x, dm.y * 20 + f.y, dm.z * 20 + f.z);
        // frameReference already holds the frame velocity: hand AutopilotKinematics a NAV with none, anchored the same.
        NavState bare = nav.withFrame(new double[3], nav.anchor());
        double[][] rv = AutopilotKinematics.relative(nb, here, bare, own, r, nav.timeSeconds());
        double[] rr = rv[0], v = rv[1];
        double rm = Math.sqrt(rr[0] * rr[0] + rr[1] * rr[1] + rr[2] * rr[2]);
        double[] want = descent
                ? AutopilotMath.descentVelocity(rr, rm - r.radius(), 0, r.mu() / (rm * rm), ship.spec().mainAccel())
                : AutopilotMath.circularVelocity(rr, v, r.mu(), new double[] {0, 0, 1});
        double[] dv = {want[0] - v[0], want[1] - v[1], want[2] - v[2]};
        double dvm = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1] + dv[2] * dv[2]);
        Vector3d dir;
        if (dvm > 2.0 || burning && dvm > 1.0) {
            double[] m = Frames.galacticToMc(dv[0], dv[1], dv[2]);
            dir = new Vector3d(m[0], m[1], m[2]).normalize();
        } else {
            // Nothing to gain: hold the attitude the next burn will most likely want, instead of chasing a noise vector.
            // Descending, gravity keeps adding speed downward, so that is radially up; in orbit, along the motion.
            double[] hold = descent ? rr : want;
            double[] m = Frames.galacticToMc(hold[0], hold[1], hold[2]);
            dir = new Vector3d(m[0], m[1], m[2]);
            if (dir.lengthSquared() < 1e-9) dir.set(0, 1, 0);
            dir.normalize();
        }
        Quaterniond q = attitude(ship);
        double[] turn = AutopilotMath.steer(q, dir, 0.7);
        ship.addMouseInput((float) turn[0], (float) turn[1]);
        double err = AutopilotMath.noseError(q, dir);
        burning = burning ? dvm > 1.0 && err < 2 * AutopilotMath.BURN_CONE_DEG
                : dvm > 1.5 * AutopilotMath.BURN_DONE_MS && err < AutopilotMath.BURN_CONE_DEG;
        if (ship.hydrogen() <= 0) burning = false;
        // Decoupled: flight assist would null the orbital velocity across the nose.
        if (ship.coupled()) ship.toggleCoupled();
        override = new Input(burning, false, false, false, false, false, false);
        ship.setPilotInput(override, 0F);
        debug = String.format(java.util.Locale.ROOT, "%s dv %.1f m/s err %.1f deg alt %.0f km burn %s", descent ? "descent" : "insert",
                dvm, err, (rm - r.radius()) / 1e3, burning);
    }

    /**
     * On the body's surface level: brake nose-up on the main engine to a slow vertical descent, then level the wings
     * and settle on the RCS (flight assist on, holding the drift), and let go of everything on touchdown.
     */
    private static void land(Minecraft mc, Starship ship) {
        Vec3 v = ship.getDeltaMovement().scale(20);
        int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(ship.getX()), (int) Math.floor(ship.getZ()));
        double h = ground <= mc.level.getMinY() ? 2000 : ship.getY() - ground;
        double vDes = AutopilotMath.touchdownSpeed(h);
        Quaterniond q = attitude(ship);
        if (!ship.coupled()) ship.toggleCoupled();
        if (ship.onGround() || ship.restingContact()) {
            override = IDLE;
            ship.setPilotInput(IDLE, 0F);
            debug = "landing: on the ground";
            return;
        }
        if (stageTouchdown ? v.length() > 18 : h > 40 || v.length() > 10) {
            stageTouchdown = false;
            Vector3d dv = new Vector3d(-v.x, vDes - v.y, -v.z);
            Vector3d dir = dv.length() > 0.8 ? new Vector3d(dv).normalize() : new Vector3d(0, 1, 0);
            double[] turn = AutopilotMath.steer(q, dir, 0.7);
            ship.addMouseInput((float) turn[0], (float) turn[1]);
            double err = AutopilotMath.noseError(q, dir);
            burning = burning ? dv.length() > 1.0 && err < 2 * AutopilotMath.BURN_CONE_DEG
                    : dv.length() > 2.5 && err < AutopilotMath.BURN_CONE_DEG;
            override = new Input(burning, false, false, false, false, false, false);
            ship.setPilotInput(override, 0F);
            debug = String.format(java.util.Locale.ROOT, "landing brake h %.0f vy %.1f want %.1f err %.1f burn %s", h, v.y, vDes, err, burning);
            return;
        }
        stageTouchdown = true;
        Vector3d nose = q.transform(new Vector3d(0, 0, 1));
        Vector3d level = new Vector3d(nose.x, 0, nose.z);
        if (level.lengthSquared() < 1e-4) level.set(-Math.sin(Math.toRadians(ship.getYRot())), 0, Math.cos(Math.toRadians(ship.getYRot())));
        level.normalize();
        double[] turn = AutopilotMath.steer(q, level, 0.7);
        ship.addMouseInput((float) turn[0], (float) turn[1]);
        float roll = (float) AutopilotMath.levelRoll(q);
        boolean down = v.y > vDes + 0.4, up = v.y < vDes - 0.4;
        override = new Input(false, false, false, false, up, down, false);
        ship.setPilotInput(override, roll);
        debug = String.format(java.util.Locale.ROOT, "touchdown h %.1f vy %.1f want %.1f", h, v.y, vDes);
    }

    private static Quaterniond attitude(Starship ship) {
        var a = ship.attitude();
        return new Quaterniond(a.x(), a.y(), a.z(), a.w());
    }

    /** EVA: turns the head toward {@code want} (8° a tick at most per axis); returns the remaining error in degrees. */
    private static double steerHead(LocalPlayer pl, Vector3d want) {
        double yaw = Math.toDegrees(Math.atan2(-want.x, want.z));
        double pitch = -Math.toDegrees(Math.asin(Math.clamp(want.y, -1, 1)));
        double dy = net.minecraft.util.Mth.wrapDegrees(yaw - pl.getYRot()), dp = pitch - pl.getXRot();
        float ny = (float) (pl.getYRot() + Math.clamp(dy, -8, 8)), np = (float) (pl.getXRot() + Math.clamp(dp, -8, 8));
        pl.setYRot(ny);
        pl.setYHeadRot(ny);
        pl.setXRot(np);
        Vec3 look = pl.getViewVector(1F);
        return Math.toDegrees(Math.acos(Math.clamp(look.x * want.x + look.y * want.y + look.z * want.z, -1, 1)));
    }

    /** A line for the player when the P key or the map cannot engage. */
    public static void hint(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable(key, args));
    }
}
