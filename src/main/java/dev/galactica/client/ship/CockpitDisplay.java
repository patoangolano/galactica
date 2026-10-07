package dev.galactica.client.ship;

import com.mojang.blaze3d.platform.NativeImage;
import dev.galactica.Galactica;
import dev.galactica.client.planet.SurfaceSkyOverlay;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Units;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.Starship;
import dev.galactica.system.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * The cockpit's live display: an attitude indicator and the flight readouts, painted on the CPU into one
 * {@link #W}×{@link #H} texture and uploaded at the end of each client tick, while no render pass is open (creating
 * a {@link DynamicTexture} uploads too, so it is created here as well, never from a render callback). Text uses a
 * built-in 5×7 pixel font, so nothing depends on AWT or the font atlas. {@link CockpitRenderer} maps it onto the panel.
 *
 * <p>Readouts: flight-assist mode; speed (m/s, or the warp drive's speed); the nearest body and the distance to its
 * surface in space, or the body and height above ground on a planet; hydrogen, exotic matter (and its jump range),
 * hull; gear and throttle. The attitude indicator shows pitch and roll against the local vertical: away from the
 * nearest body's centre in space, world up on a planet.
 */
public final class CockpitDisplay {
    private CockpitDisplay() {}

    public static final Identifier ID = Galactica.id("dynamic/cockpit_display");
    /** Canvas size: 3:1, the panel display's aspect ({@link StarshipGeometry#SCREEN}). */
    static final int W = 768, H = 256;

    private static final int BG = 0xFF050C14, FRAME = 0xFF1C3442, TEXT = 0xFF9FF0FF, DIM = 0xFF4F8CA0, AMBER = 0xFFFFB234,
            WARN = 0xFFFF4A3A, GOOD = 0xFF5CFF8A, SKY = 0xFF1E6FB0, GROUND = 0xFF7A4A22, WHITE = 0xFFF2F6FF;

    private static DynamicTexture texture;
    private static final int[] PX = new int[W * H];

    /** True once the texture exists (the renderer skips the display quad before that). */
    static boolean ready() {
        return texture != null;
    }

    /** Client tick end: repaints and uploads while the local player rides a ship and looks out of it. */
    static void tick(Minecraft mc) {
        if (mc.player == null || !(mc.player.getVehicle() instanceof Starship ship) || mc.level == null) return;
        if (!mc.options.getCameraType().isFirstPerson()) return;
        paint(mc, ship);
        if (texture == null) {
            texture = new DynamicTexture(() -> "galactica:cockpit_display", new NativeImage(W, H, true));
            mc.getTextureManager().register(ID, texture);
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        for (int y = 0; y < H; y++) {
            int row = y * W;
            for (int x = 0; x < W; x++) img.setPixel(x, y, PX[row + x]);
        }
        texture.upload();
    }

    // ---------------------------------------------------------------- content

    private static void paint(Minecraft mc, Starship ship) {
        fill(0, 0, W, H, BG);
        rectOutline(0, 0, W, H, 3, FRAME);
        Vector3f up = new Vector3f(0, 1, 0);
        String[] place = place(mc, ship, up);
        attitude(ship, up, 128, 128, 112);
        line(262, 12, 262, H - 12, FRAME);

        int x = 280, y = 16, row = 27;
        boolean assist = ship.coupled();
        text(x, y, "MODE", DIM);
        text(x + 90, y, assist ? "ASSIST" : "FREE", assist ? GOOD : AMBER);
        if (ship.isSpooling()) text(x + 240, y, String.format(Locale.ROOT, "JUMP %.1fS", ship.spoolTicks() / 20.0), AMBER);
        y += row;
        double speed = Math.sqrt(sq(ship.getX() - ship.xo) + sq(ship.getY() - ship.yo) + sq(ship.getZ() - ship.zo)) * 20.0;
        NavState nav = SkyManager.get().nav();
        if (mc.level.dimension() == Galactica.SPACE && nav != null && nav.speed() > 0) {
            text(x, y, "WARP", DIM);
            text(x + 90, y, NavServer.formatSpeed(nav.speed()), AMBER);
        } else {
            text(x, y, "SPD", DIM);
            text(x + 90, y, String.format(Locale.ROOT, "%.1f M/S", speed), TEXT);
        }
        y += row;
        text(x, y, place[0], DIM);
        text(x + 90, y, place[1], TEXT);
        y += row;
        float h2 = ship.hydrogen();
        text(x, y, "H2", DIM);
        text(x + 90, y, String.format(Locale.ROOT, "%.1f/%.0f", h2, Starship.MAX_HYDROGEN), h2 < 5 ? WARN : TEXT);
        bar(x + 330, y + 3, 140, 14, h2 / Starship.MAX_HYDROGEN, h2 < 5 ? WARN : TEXT);
        y += row;
        text(x, y, "EXO", DIM);
        text(x + 90, y, String.format(Locale.ROOT, "%d  %.0f LY", ship.exotic(), DriveRules.rangeLy(ship.exotic())), TEXT);
        bar(x + 330, y + 3, 140, 14, ship.exotic() / (float) Starship.MAX_EXOTIC, 0xFFC08CFF);
        y += row;
        float hull = ship.hull();
        int hullColour = hull < 25 ? WARN : hull < 60 ? AMBER : GOOD;
        text(x, y, "HULL", DIM);
        text(x + 90, y, String.format(Locale.ROOT, "%d%%", Math.round(hull / Starship.MAX_HULL * 100F)), hullColour);
        bar(x + 330, y + 3, 140, 14, hull / Starship.MAX_HULL, hullColour);
        y += row;
        text(x, y, "GEAR", DIM);
        boolean gear = ship.gearExtension(1F) > 0.5F;
        text(x + 90, y, gear ? "DOWN" : "UP", gear ? GOOD : DIM);
        text(x + 240, y, String.format(Locale.ROOT, "THR %d%%", Math.round(Math.abs(ship.throttle()) * 100F)),
                Math.abs(ship.throttle()) > 0.01F ? AMBER : DIM);
    }

    /**
     * The place line ({label, value}) and the local vertical for the attitude indicator, written into {@code up}:
     * on a planet the body and the height above ground; in space the body whose surface is nearest and the distance
     * to it, with up pointing away from its centre.
     */
    private static String[] place(Minecraft mc, Starship ship, Vector3f up) {
        Level level = mc.level;
        if (level.dimension() != Galactica.SPACE) {
            Body body = SurfaceSkyOverlay.currentBody();
            String name = body != null ? body.name() : level.dimension() == Level.OVERWORLD ? "Earth" : "Surface";
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(ship.getX()), Mth.floor(ship.getZ()));
            double agl = Math.max(0, ship.getY() - ground);
            return new String[] {"ALT", String.format(Locale.ROOT, "%s %.0f M AGL", name, agl)};
        }
        SkyManager sky = SkyManager.get();
        GalacticPos eye = sky.viewer();
        NavState nav = sky.nav();
        if (eye == null || nav == null) return new String[] {"NEAR", "--"};
        Neighbourhood.Located best = null;
        double bestAlt = Double.MAX_VALUE;
        double[] bestDir = null;
        for (Neighbourhood.Located l : sky.neighbourhood().locateBodies(eye, nav.timeSeconds())) {
            double[] g = l.pos().metresFrom(eye);
            double d = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
            double alt = d - l.body().radiusMetres();
            if (alt < bestAlt) {
                bestAlt = alt;
                best = l;
                bestDir = g;
            }
        }
        if (best == null) {
            var star = sky.neighbourhood().nearestStar(eye);
            if (star == null) return new String[] {"NEAR", "DEEP SPACE"};
            double[] g = Neighbourhood.starPos(star).metresFrom(eye);
            return new String[] {"NEAR", star.name() + " " + distance(Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]))};
        }
        // Up is away from the body's centre while it fills a sizeable part of the sky (within ~20 radii).
        if (bestAlt < 20 * best.body().radiusMetres()) {
            double[] mc3 = Frames.galacticToMc(-bestDir[0], -bestDir[1], -bestDir[2]);
            up.set((float) mc3[0], (float) mc3[1], (float) mc3[2]).normalize();
        }
        return new String[] {"NEAR", best.body().name() + " " + distance(Math.max(0, bestAlt))};
    }

    private static String distance(double m) {
        if (m < 1e4) return String.format(Locale.ROOT, "%.0f M", m);
        if (m < 1e7) return String.format(Locale.ROOT, "%.0f KM", m / 1e3);
        if (m < 0.05 * Units.AU) return String.format(Locale.ROOT, "%.0f MM", m / 1e6);
        if (m < 1e5 * Units.AU) return String.format(Locale.ROOT, "%.2f AU", m / Units.AU);
        return String.format(Locale.ROOT, "%.2f PC", m / Units.PARSEC);
    }

    /** Pitch-and-roll ball, drawn per pixel: sky above the horizon, ground below, a 10° ladder, the fixed symbol. */
    private static void attitude(Starship ship, Vector3f up, int cx, int cy, int r) {
        Quaternionf q = ship.getAttitude(1F, new Quaternionf());
        Vector3f fwd = q.transform(new Vector3f(0, 0, 1)), shipUp = q.transform(new Vector3f(0, 1, 0)),
                right = q.transform(new Vector3f(-1, 0, 0)); // +X is port, so starboard is −X
        float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(fwd.dot(up), -1F, 1F)));
        float roll = (float) Math.atan2(-right.dot(up), shipUp.dot(up)); // right wing down is positive
        // Horizon direction and its sky-side normal on screen (y down): a right roll tilts the horizon counter-clockwise.
        float hx = (float) Math.cos(roll), hy = (float) -Math.sin(roll), nx = (float) -Math.sin(roll), ny = (float) -Math.cos(roll);
        float pxPerDeg = 3F;
        float pitchPx = pitch * pxPerDeg;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                float dx = x - cx, dy = y - cy;
                float d2 = dx * dx + dy * dy;
                if (d2 > r * r) continue;
                float s = dx * nx + dy * ny + pitchPx;      // distance above the horizon, px
                float a = dx * hx + dy * hy;                 // along the horizon
                int c = s > 0 ? shadeSky(s) : GROUND;
                if (Math.abs(s) < 1.5F) c = WHITE;
                else {
                    float rung = s / (10F * pxPerDeg);
                    float frac = Math.abs(rung - Math.round(rung)) * 10F * pxPerDeg;
                    int width = Math.round(rung) % 3 == 0 ? 40 : 22;
                    if (frac < 1.1F && Math.abs(a) < width) c = WHITE;
                }
                if (d2 > (r - 4) * (r - 4)) c = FRAME;
                PX[y * W + x] = c;
            }
        }
        // Fixed aircraft symbol: wings and centre dot, amber.
        fill(cx - 52, cy - 2, 34, 5, AMBER);
        fill(cx + 18, cy - 2, 34, 5, AMBER);
        fill(cx - 21, cy - 2, 5, 14, AMBER);
        fill(cx + 16, cy - 2, 5, 14, AMBER);
        fill(cx - 3, cy - 3, 7, 7, AMBER);
        text(cx - 96, cy + r - 34, String.format(Locale.ROOT, "P%+.0f", pitch), TEXT);
        text(cx + 30, cy + r - 34, String.format(Locale.ROOT, "R%+.0f", Math.toDegrees(roll)), TEXT);
    }

    private static int shadeSky(float above) {
        float k = Math.clamp(above / 200F, 0F, 1F);
        int r = Math.round(0x1E * (1 - 0.5F * k)), g = Math.round(0x6F * (1 - 0.5F * k)), b = Math.round(0xB0 * (1 - 0.3F * k));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ---------------------------------------------------------------- raster primitives

    private static void fill(int x0, int y0, int w, int h, int c) {
        for (int y = Math.max(0, y0); y < Math.min(H, y0 + h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(W, x0 + w); x++) PX[y * W + x] = c;
        }
    }

    private static void rectOutline(int x0, int y0, int w, int h, int t, int c) {
        fill(x0, y0, w, t, c);
        fill(x0, y0 + h - t, w, t, c);
        fill(x0, y0, t, h, c);
        fill(x0 + w - t, y0, t, h, c);
    }

    private static void line(int x0, int y0, int x1, int y1, int c) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = x0 + (x1 - x0) * i / Math.max(1, n), y = y0 + (y1 - y0) * i / Math.max(1, n);
            fill(x - 1, y - 1, 3, 3, c);
        }
    }

    private static void bar(int x, int y, int w, int h, float f, int c) {
        rectOutline(x, y, w, h, 2, DIM);
        fill(x + 3, y + 3, Math.round((w - 6) * Math.clamp(f, 0F, 1F)), h - 6, c);
    }

    private static final int SCALE = 3;

    /** Upper-case 5×7 text at 3× (18 px per character). */
    static void text(int x, int y, String s, int c) {
        String t = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < t.length(); i++) {
            int[] g = Font5x7.glyph(t.charAt(i));
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    if ((g[row] >> (4 - col) & 1) != 0) fill(x + col * SCALE, y + row * SCALE, SCALE, SCALE, c);
                }
            }
            x += 6 * SCALE;
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
