package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.client.ship.ShipClient;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Autotest of a real main-engine burn (W held through the key mapping, nothing forced): the pilot's predicted
 * velocity along the nose must grow every tick and add up to what the engine gives, and after the burn the
 * client's and the server's copies of the velocity must agree. A server velocity echo overwriting the
 * prediction shows up as a tick that loses speed; a server clamp the client never hears of shows up as a gap.
 */
public final class ShipBurnAutoTest {
    private ShipBurnAutoTest() {}

    private static final double LOSS_TOLERANCE = 1.0; // m/s per tick
    private static final double SYNC_TOLERANCE = 1.0; // m/s

    private static boolean installed, active;
    private static int burnTicks, losses;
    private static double firstAlong = Double.NaN, lastAlong = Double.NaN;
    private static final Probe PROBE = new Probe();

    /** Call right after W is pressed. */
    static void start() {
        if (!installed) {
            installed = true;
            ClientTickEvents.END_CLIENT_TICK.register(ShipBurnAutoTest::tick);
        }
        burnTicks = losses = 0;
        firstAlong = lastAlong = Double.NaN;
        PROBE.reset();
        Starship.netProbe = PROBE;
        active = true;
    }

    /** Call some ticks after W is released, once the ship coasts. */
    static void finish() {
        active = false;
        Starship.netProbe = null;
        Minecraft mc = Minecraft.getInstance();
        Starship ship = ShipClient.piloted(mc);
        if (ship == null) {
            Galactica.LOG.warn("[autotest] FAIL ship burn: not piloting");
            return;
        }
        double gain = lastAlong - firstAlong;
        double expected = ShipFlightModel.MAIN_ACCEL * ShipFlightModel.TICK * Math.max(0, burnTicks - 1);
        expected = Math.min(expected, ShipFlightModel.MAX_SPEED - firstAlong);
        Vec3 client = ship.getDeltaMovement().scale(1 / ShipFlightModel.TICK);
        Starship server = serverShip(mc, ship.getId());
        double gap = server == null ? Double.NaN : client.distanceTo(server.getDeltaMovement().scale(1 / ShipFlightModel.TICK));
        boolean ok = burnTicks > 20 && losses == 0 && gain >= 0.85 * expected && gap < SYNC_TOLERANCE;
        Galactica.LOG.info(String.format(Locale.ROOT,
                "[autotest] %s ship burn: burnTicks=%d gain=%.1f m/s (engine %.1f) losses=%d clientServerGap=%.2f m/s "
                        + "echoesDropped=%d staleEchoes=%d corrections=%d",
                ok ? "PASS" : "FAIL", burnTicks, gain, expected, losses, gap, PROBE.echoes, PROBE.stale, PROBE.corrections));
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        Starship ship = ShipClient.piloted(mc);
        if (ship == null || !mc.options.keyUp.isDown()) return;
        Vector3f nose = ship.attitude().transform(new Vector3f(0, 0, 1));
        Vec3 v = ship.getDeltaMovement().scale(1 / ShipFlightModel.TICK);
        double along = v.x * nose.x + v.y * nose.y + v.z * nose.z;
        burnTicks++;
        if (!Double.isNaN(lastAlong) && along < lastAlong - LOSS_TOLERANCE && losses++ < 5)
            Galactica.LOG.warn(String.format(Locale.ROOT, "[autotest] ship burn: tick %d speed along the nose fell %.2f -> %.2f m/s",
                    burnTicks, lastAlong, along));
        if (Double.isNaN(firstAlong)) firstAlong = along;
        lastAlong = along;
    }

    /** The integrated server's copy of a ship, or {@code null}. */
    static @Nullable Starship serverShip(Minecraft mc, int id) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.level == null) return null;
        ServerLevel level = server.getLevel(mc.level.dimension());
        return level != null && level.getEntity(id) instanceof Starship s ? s : null;
    }

    /** Counts what the pilot's client did with the server's velocity messages. */
    static final class Probe implements Starship.NetProbe {
        int echoes, stale, corrections;

        void reset() {
            echoes = stale = corrections = 0;
        }

        @Override
        public void droppedEcho(Starship ship, Vec3 echo) {
            echoes++;
            // An echo that would have changed the step by more than a block is one that, applied, moves the camera.
            Vec3 own = ship.getDeltaMovement();
            if (echo.distanceTo(own) > 1.0 && stale++ < 5)
                Galactica.LOG.info(String.format(Locale.ROOT, "[autotest] dropped stale velocity echo (%.2f, %.2f, %.2f), own (%.2f, %.2f, %.2f) blocks/tick",
                        echo.x, echo.y, echo.z, own.x, own.y, own.z));
        }

        @Override
        public void corrected(Starship ship, Vec3 removed) {
            if (corrections++ < 5)
                Galactica.LOG.info(String.format(Locale.ROOT, "[autotest] server clamp applied: removed (%.3f, %.3f, %.3f) blocks/tick",
                        removed.x, removed.y, removed.z));
        }
    }
}
