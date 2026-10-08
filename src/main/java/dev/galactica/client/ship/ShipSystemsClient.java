package dev.galactica.client.ship;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.ship.ShipActionPayload;
import dev.galactica.ship.ShipScanner;
import dev.galactica.ship.Starship;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Client half of the ship systems: the dock key (G), the cargo key (B), the cockpit display page key (N), and the
 * latest scanner sweep ({@link ShipScanner.Payload}) with the nearest bodies from this client's own sky model added.
 */
public final class ShipSystemsClient {
    private ShipSystemsClient() {}

    public static final KeyMapping DOCK = key("dock", InputConstants.KEY_G, 4);
    public static final KeyMapping CARGO = key("cargo", InputConstants.KEY_B, 5);
    public static final KeyMapping PAGE = key("cockpit_page", InputConstants.KEY_N, 6);

    private static KeyMapping key(String name, int code, int order) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galactica." + name, InputConstants.Type.KEYBOARD, code, ShipClient.CATEGORY, order));
    }

    private static volatile ShipScanner.@Nullable Payload scan;
    private static long scanTick;
    /** Cockpit display page: 0 flight, 1 systems (shields, dock, scanner). */
    private static int page;

    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(ShipScanner.Payload.TYPE, (msg, ctx) -> {
            scan = msg;
            scanTick = ctx.client().level != null ? ctx.client().level.getGameTime() : 0;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> scan = null);
        ClientTickEvents.END_CLIENT_TICK.register(ShipSystemsClient::tick);
    }

    private static void tick(Minecraft mc) {
        boolean aboard = mc.player != null && mc.player.getVehicle() instanceof Starship;
        while (DOCK.consumeClick()) if (aboard) send(ShipActionPayload.DOCK);
        while (CARGO.consumeClick()) if (aboard) send(ShipActionPayload.CARGO);
        while (PAGE.consumeClick()) if (aboard) page = (page + 1) % 2;
        if (scan != null && mc.level != null && mc.level.getGameTime() - scanTick > 4L * ShipScanner.PERIOD) scan = null;
    }

    public static void send(byte action) {
        if (ClientPlayNetworking.canSend(ShipActionPayload.TYPE)) ClientPlayNetworking.send(new ShipActionPayload(action));
    }

    public static int page() {
        return page;
    }

    public static void setPage(int p) {
        page = p;
    }

    /** The last sweep (null when none came lately: no scanner aboard, or no power). */
    public static ShipScanner.@Nullable Payload scan() {
        return scan;
    }

    /** One line per contact for the displays, nearest first, bodies from the sky model included; at most {@code max}. */
    public static List<String> lines(Minecraft mc, int max) {
        List<Object[]> rows = new ArrayList<>();
        ShipScanner.Payload p = scan;
        if (p != null) {
            for (ShipScanner.Contact c : p.contacts()) {
                String label = switch (c.kind()) {
                    case SHIP -> "SHIP " + c.name();
                    case DERELICT -> "DERELICT " + c.name();
                    case ORE -> "ORE " + c.name() + " x" + c.count();
                    case BODY -> c.name();
                };
                rows.add(new Object[] {c.distance(), label + " " + metres(c.distance())});
            }
        }
        // Bodies: in space the nearest two surfaces from the sky model.
        if (mc.level != null && mc.level.dimension() == Galactica.SPACE) {
            var sky = SkyManager.get();
            var eye = sky.viewer();
            var nav = sky.nav();
            if (eye != null && nav != null) {
                List<Object[]> bodies = new ArrayList<>();
                for (Neighbourhood.Located l : sky.neighbourhood().locateBodies(eye, nav.timeSeconds())) {
                    double[] g = l.pos().metresFrom(eye);
                    double alt = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) - l.body().radiusMetres();
                    bodies.add(new Object[] {Math.max(0, alt), "BODY " + l.body().name() + " " + metres(Math.max(0, alt))});
                }
                bodies.sort(Comparator.comparingDouble(o -> (double) o[0]));
                rows.addAll(bodies.subList(0, Math.min(2, bodies.size())));
            }
        }
        rows.sort(Comparator.comparingDouble(o -> (double) o[0]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, rows.size()); i++) out.add((String) rows.get(i)[1]);
        return out;
    }

    static String metres(double m) {
        if (m < 1e4) return String.format(Locale.ROOT, "%.0fM", m);
        if (m < 1e7) return String.format(Locale.ROOT, "%.0fKM", m / 1e3);
        return String.format(Locale.ROOT, "%.0fMM", m / 1e6);
    }
}
