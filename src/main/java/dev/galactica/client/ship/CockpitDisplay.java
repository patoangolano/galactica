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
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.Locale;

/**
 * The cockpit's live display: the flight readouts painted on the CPU into one {@link #W}×{@link #H} canvas, and an
 * attitude indicator. Both textures are created and uploaded at the end of a client tick, while no render pass is
 * open, and the canvas only when something on it changed. Text uses a built-in 5×7 pixel font, so nothing depends on
 * AWT or the font atlas. {@link CockpitRenderer} maps the canvas onto the panel.
 *
 * <p>The attitude ball moves at frame rate: the canvas leaves its window transparent, and {@link CockpitRenderer}
 * draws a disc behind it from a static pitch tape ({@link #ADI_ID}), turned and shifted every frame by the
 * interpolated attitude the camera itself uses. Under a shader pack (or if our display pipeline does not compile)
 * the ball is painted into the canvas instead, at tick rate.
 *
 * <p>Readouts: flight-assist mode; speed (m/s relative to the body whose frame the ship flies in, with that orbit's
 * periapsis and apoapsis in km, or the warp drive's speed); the nearest body or star and the distance
 * to its surface in space, or the body and height above ground on a planet; hydrogen, exotic matter (and its jump
 * range), hull; gear and throttle; pitch and roll under the ball. The ball shows pitch and roll against the local
 * vertical: away from the nearest body's (or star's) centre in space while it is within 20 radii, world up on a
 * planet.
 */
public final class CockpitDisplay {
    private CockpitDisplay() {}

    public static final Identifier ID = Galactica.id("dynamic/cockpit_display");
    public static final Identifier ADI_ID = Galactica.id("dynamic/cockpit_adi");
    /** Canvas size: 3.5:1, the panel display's aspect ({@link StarshipGeometry#SCREEN}). */
    static final int W = 896, H = 256;
    /** The attitude ball on the canvas: centre and radius, canvas pixels (y down), and its pitch scale. */
    static final int BALL_CX = 118, BALL_CY = 106, BALL_R = 96;
    static final float PX_PER_DEG = 3F;
    /** The pitch tape: sky above the horizon, ground below, ±120° over its height (pitch ±90° plus the ball's radius), ladder every 10°. */
    static final int TAPE_W = 256, TAPE_H = 1024;
    static final float TAPE_PX_PER_DEG = TAPE_H / 240F;

    private static final int BG = 0xFF050C14, FRAME = 0xFF1C3442, TEXT = 0xFF9FF0FF, DIM = 0xFF4F8CA0, AMBER = 0xFFFFB234,
            WARN = 0xFFFF4A3A, GOOD = 0xFF5CFF8A, GROUND = 0xFF7A4A22, WHITE = 0xFFF2F6FF, CLEAR = 0x00000000;

    private static DynamicTexture texture, tape;
    private static final int[] PX = new int[W * H];
    /** Everything the canvas shows, as text: the canvas is repainted and uploaded only when this changes. */
    private static String shown = "";
    /** Local vertical for the attitude ball, world (block) frame, set each tick. */
    private static final Vector3f UP = new Vector3f(0, 1, 0);
    /** Whether the renderer draws the ball (true) or the canvas carries it (false), as of the last tick. */
    private static boolean gpuBall;

    /** True once the canvas exists (the renderer skips the display before that). */
    static boolean ready() {
        return texture != null;
    }

    /** True when the renderer must draw the ball behind the canvas (and the tape exists). */
    static boolean ballOnGpu() {
        return gpuBall && tape != null;
    }

    /** Client tick end: repaints and uploads while the local player rides a ship and looks out of it. */
    static void tick(Minecraft mc) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            tickTimed(mc);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.COCKPIT, perf);
        }
    }

    private static void tickTimed(Minecraft mc) {
        if (mc.player == null || !(mc.player.getVehicle() instanceof Starship ship) || mc.level == null) return;
        if (!mc.options.getCameraType().isFirstPerson()) return;
        gpuBall = ShipRenderTypes.displayUsable();
        if (tape == null) {
            NativeImage img = new NativeImage(TAPE_W, TAPE_H, true);
            paintTape(img);
            tape = new DynamicTexture(() -> "galactica:cockpit_adi", img);
            mc.getTextureManager().register(ADI_ID, tape);
        }
        String[] place = place(mc, ship, UP);
        float[] att = angles(ship.getAttitude(1F, new Quaternionf()), UP, new float[2]);
        String key = state(mc, ship, place, att);
        if (texture != null && key.equals(shown)) return;
        shown = key;
        paint(mc, ship, place, att);
        if (texture == null) {
            texture = new DynamicTexture(() -> "galactica:cockpit_display", new NativeImage(W, H, true));
            mc.getTextureManager().register(ID, texture);
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        // One bulk copy into the RGBA image (ABGR as little-endian ints), not W·H setPixel calls.
        var dst = MemoryUtil.memIntBuffer(img.getPointer(), W * H);
        for (int i = 0; i < PX.length; i++) dst.put(i, abgr(PX[i]));
        texture.upload();
    }

    private static int abgr(int argb) {
        return argb & 0xFF00FF00 | argb >> 16 & 0xFF | (argb & 0xFF) << 16;
    }

    /**
     * Pitch (degrees, nose above the horizon positive) and roll (radians, right wing down positive) of attitude
     * {@code q} against the vertical {@code up}, into {@code out}.
     */
    static float[] angles(Quaternionfc q, Vector3fc up, float[] out) {
        Vector3f fwd = q.transform(new Vector3f(0, 0, 1)), shipUp = q.transform(new Vector3f(0, 1, 0)),
                right = q.transform(new Vector3f(-1, 0, 0)); // +X is port, so starboard is −X
        out[0] = (float) Math.toDegrees(Math.asin(Math.clamp(fwd.dot(up), -1F, 1F)));
        out[1] = (float) Math.atan2(-right.dot(up), shipUp.dot(up));
        return out;
    }

    /** The local vertical the ball was last measured against (render thread reads it, tick writes it). */
    static Vector3fc up() {
        return UP;
    }

    // ---------------------------------------------------------------- content

    private static String state(Minecraft mc, Starship ship, String[] place, float[] att) {
        StringBuilder b = new StringBuilder(160);
        b.append(gpuBall).append('|').append(ship.coupled()).append('|').append(ship.isSpooling() ? ship.spoolTicks() / 2 : -1)
                .append('|').append(String.join(" ", speedLine(mc, ship))).append('|').append(place[0]).append(place[1])
                .append('|').append(Math.round(ship.hydrogen() * 10)).append('|').append(ship.exotic())
                .append('|').append(Math.round(ship.hull())).append('|').append(ship.gearExtension(1F) > 0.5F)
                .append('|').append(Math.round(Math.abs(ship.throttle()) * 100F))
                .append('|').append(Math.round(att[0])).append('|').append(Math.round(Math.toDegrees(att[1])));
        String[] tgt = targetLine();
        b.append('|').append(tgt[0]).append('|').append(tgt[1]);
        // A painted ball moves with every tenth of a degree; a GPU ball leaves the canvas still.
        if (!gpuBall) b.append('|').append(Math.round(att[0] * 10)).append('|').append(Math.round(att[1] * 1000));
        return b.toString();
    }

    private static String[] speedLine(Minecraft mc, Starship ship) {
        NavState nav = SkyManager.get().nav();
        if (mc.level.dimension() == Galactica.SPACE && nav != null && nav.speed() > 0) {
            return new String[] {"WARP", NavServer.formatSpeed(nav.speed())};
        }
        if (mc.level.dimension() == Galactica.SPACE) {
            // In space: speed relative to the body whose frame the ship flies in, and its orbit's apsides.
            var o = dev.galactica.client.hud.OrbitReadout.current(mc);
            if (o != null) {
                String orbit = o.star() ? "" : "PE " + apsisShort(o.periapsis()) + " AP " + apsisShort(o.apoapsis());
                return new String[] {"SPD", o.speed() < 1e4 ? String.format(Locale.ROOT, "%.1f M/S", o.speed())
                        : String.format(Locale.ROOT, "%.2f KM/S", o.speed() / 1e3), orbit};
            }
        }
        double speed = Math.sqrt(sq(ship.getX() - ship.xo) + sq(ship.getY() - ship.yo) + sq(ship.getZ() - ship.zo)) * 20.0;
        return new String[] {"SPD", String.format(Locale.ROOT, "%.1f M/S", speed)};
    }

    /**
     * The target row: name and distance (truncated to the display), and on the right the autopilot's phase or the ETA.
     * The display's 5x7 font has no lower case: everything is upper-cased by {@link #text}.
     */
    private static String[] targetLine() {
        var r = dev.galactica.client.nav.TargetReadout.current();
        if (r == null) return new String[] {"NONE", ""};
        String name = r.target().name();
        if (name.length() > 8) name = name.substring(0, 8);
        String left = name + " " + dev.galactica.client.nav.TargetReadout.distanceText(Math.max(0, r.surface())).replace(",", "");
        String right;
        if (r.state().engaged()) right = "AP " + r.state().phase().name();
        else right = r.eta() >= 0 ? "ETA " + shortDuration(r.eta()) : "";
        return new String[] {left, right};
    }

    /** "42S", "3M05S", "2H10M", "5.2D": an ETA in the display's eight cells. */
    private static String shortDuration(double s) {
        if (!Double.isFinite(s)) return "--";
        if (s < 60) return String.format(Locale.ROOT, "%.0fS", s);
        if (s < 3600) return String.format(Locale.ROOT, "%dM%02dS", (int) (s / 60), (int) (s % 60));
        if (s < 86400) return String.format(Locale.ROOT, "%dH%02dM", (int) (s / 3600), (int) (s % 3600 / 60));
        return s < 86400 * 999 ? String.format(Locale.ROOT, "%.1fD", s / 86400) : "LONG";
    }

    /** An apsis for the display: kilometres above the surface, "ESC" or "IMP". */
    private static String apsisShort(double m) {
        if (!Double.isFinite(m)) return "ESC";
        if (m < 0) return "IMP";
        return m < 1e8 ? String.format(Locale.ROOT, "%.0f", m / 1e3) : String.format(Locale.ROOT, "%.0fK", m / 1e6);
    }

    private static void paint(Minecraft mc, Starship ship, String[] place, float[] att) {
        fill(0, 0, W, H, BG);
        rectOutline(0, 0, W, H, 3, FRAME);
        ball(att);
        text(BALL_CX - BALL_R, H - 40, String.format(Locale.ROOT, "P%+.0f", att[0]), TEXT);
        text(BALL_CX + 6, H - 40, String.format(Locale.ROOT, "R%+.0f", Math.toDegrees(att[1])), TEXT);
        line(BALL_CX + BALL_R + 18, 12, BALL_CX + BALL_R + 18, H - 12, FRAME);

        int x = BALL_CX + BALL_R + 36, y = 12, row = 29, val = x + 90, barX = x + 380, barW = 230;
        boolean assist = ship.coupled();
        text(x, y, "MODE", DIM);
        text(val, y, assist ? "ASSIST" : "FREE", assist ? GOOD : AMBER);
        if (ship.isSpooling()) text(val + 200, y, String.format(Locale.ROOT, "JUMP %.1fS", ship.spoolTicks() / 20.0), AMBER);
        y += row;
        String[] speed = speedLine(mc, ship);
        text(x, y, speed[0], DIM);
        text(val, y, speed[1], "WARP".equals(speed[0]) ? AMBER : TEXT);
        if (speed.length > 2 && !speed[2].isEmpty()) text(val + 200, y, speed[2], speed[2].contains("IMP") ? AMBER : DIM);
        y += row;
        text(x, y, place[0], DIM);
        text(val, y, place[1], TEXT);
        y += row;
        float h2 = ship.hydrogen();
        text(x, y, "H2", DIM);
        text(val, y, String.format(Locale.ROOT, "%.1f/%.0f", h2, Starship.MAX_HYDROGEN), h2 < 5 ? WARN : TEXT);
        bar(barX, y + 3, barW, 14, h2 / Starship.MAX_HYDROGEN, h2 < 5 ? WARN : TEXT);
        y += row;
        text(x, y, "EXO", DIM);
        text(val, y, String.format(Locale.ROOT, "%d  %.0f LY", ship.exotic(), DriveRules.rangeLy(ship.exotic())), TEXT);
        bar(barX, y + 3, barW, 14, ship.exotic() / (float) Starship.MAX_EXOTIC, 0xFFC08CFF);
        y += row;
        float hull = ship.hull();
        int hullColour = hull < 25 ? WARN : hull < 60 ? AMBER : GOOD;
        text(x, y, "HULL", DIM);
        text(val, y, String.format(Locale.ROOT, "%d%%", Math.round(hull / Starship.MAX_HULL * 100F)), hullColour);
        bar(barX, y + 3, barW, 14, hull / Starship.MAX_HULL, hullColour);
        y += row;
        text(x, y, "GEAR", DIM);
        boolean gear = ship.gearExtension(1F) > 0.5F;
        text(val, y, gear ? "DOWN" : "UP", gear ? GOOD : DIM);
        text(val + 200, y, String.format(Locale.ROOT, "THR %d%%", Math.round(Math.abs(ship.throttle()) * 100F)),
                Math.abs(ship.throttle()) > 0.01F ? AMBER : DIM);
        y += row;
        String[] tgt = targetLine();
        text(x, y, "TGT", DIM);
        text(val, y, tgt[0], tgt[0].equals("NONE") ? DIM : 0xFFFFD54F);
        if (!tgt[1].isEmpty()) text(val + 330, y, tgt[1], tgt[1].startsWith("AP") ? GOOD : DIM);
    }

    /**
     * The place line ({label, value}) and the local vertical for the attitude indicator, written into {@code up}:
     * on a planet the body and the height above ground; in space whichever surface is nearest, a planet's, a moon's
     * or the star's photosphere, and the distance to it, with up pointing away from that body's centre while it is
     * within 20 of its radii (otherwise the previous vertical stands, world up at first).
     */
    private static String[] place(Minecraft mc, Starship ship, Vector3f up) {
        Level level = mc.level;
        if (level.dimension() != Galactica.SPACE) {
            up.set(0, 1, 0);
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
        String bestName = null;
        double bestAlt = Double.MAX_VALUE, bestRadius = 0;
        double[] bestDir = null;
        var star = sky.neighbourhood().nearestStar(eye);
        if (star != null) {
            double[] g = Neighbourhood.starPos(star).metresFrom(eye);
            bestAlt = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) - star.radiusMetres();
            bestName = star.name();
            bestRadius = star.radiusMetres();
            bestDir = g;
        }
        for (Neighbourhood.Located l : sky.neighbourhood().locateBodies(eye, nav.timeSeconds())) {
            double[] g = l.pos().metresFrom(eye);
            double alt = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) - l.body().radiusMetres();
            if (alt < bestAlt) {
                bestAlt = alt;
                bestName = l.body().name();
                bestRadius = l.body().radiusMetres();
                bestDir = g;
            }
        }
        if (bestName == null) return new String[] {"NEAR", "DEEP SPACE"};
        if (bestAlt < 20 * bestRadius) {
            double[] mc3 = Frames.galacticToMc(-bestDir[0], -bestDir[1], -bestDir[2]);
            up.set((float) mc3[0], (float) mc3[1], (float) mc3[2]).normalize();
        }
        return new String[] {"NEAR", bestName + " " + distance(Math.max(0, bestAlt))};
    }

    private static String distance(double m) {
        if (m < 1e4) return String.format(Locale.ROOT, "%.0f M", m);
        if (m < 1e7) return String.format(Locale.ROOT, "%.0f KM", m / 1e3);
        if (m < 0.05 * Units.AU) return String.format(Locale.ROOT, "%.0f MM", m / 1e6);
        if (m < 1e5 * Units.AU) return String.format(Locale.ROOT, "%.2f AU", m / Units.AU);
        return String.format(Locale.ROOT, "%.2f PC", m / Units.PARSEC);
    }

    /**
     * The ball's window on the canvas: the bezel ring, the fixed aircraft symbol, and inside either a transparent
     * hole (the renderer draws the moving ball behind it) or, without our pipeline, the ball itself at {@code att}.
     */
    private static void ball(float[] att) {
        int cx = BALL_CX, cy = BALL_CY, r = BALL_R;
        float roll = att[1];
        float hx = (float) Math.cos(roll), hy = (float) -Math.sin(roll), nx = (float) -Math.sin(roll), ny = (float) -Math.cos(roll);
        float pitchPx = att[0] * PX_PER_DEG;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                float dx = x - cx, dy = y - cy;
                float d2 = dx * dx + dy * dy;
                if (d2 > r * r) continue;
                int c;
                if (d2 > (r - 4) * (r - 4)) c = FRAME;
                else if (gpuBall) c = CLEAR;
                else c = tapeColour(dx * hx + dy * hy, dx * nx + dy * ny + pitchPx);
                PX[y * W + x] = c;
            }
        }
        // Fixed aircraft symbol: wings and centre dot, amber, over the ball.
        fill(cx - 52, cy - 2, 34, 5, AMBER);
        fill(cx + 18, cy - 2, 34, 5, AMBER);
        fill(cx - 21, cy - 2, 5, 14, AMBER);
        fill(cx + 16, cy - 2, 5, 14, AMBER);
        fill(cx - 3, cy - 3, 7, 7, AMBER);
    }

    /**
     * The ball's colour at {@code along} canvas pixels along the horizon and {@code above} canvas pixels above it:
     * sky shading upward, ground, the horizon line, and a pitch ladder every 10° (long rungs every 30°).
     */
    private static int tapeColour(float along, float above) {
        if (Math.abs(above) < 1.5F) return WHITE;
        float rung = above / (10F * PX_PER_DEG);
        float frac = Math.abs(rung - Math.round(rung)) * 10F * PX_PER_DEG;
        int width = Math.round(rung) % 3 == 0 ? 40 : 22;
        if (frac < 1.1F && Math.abs(along) < width) return WHITE;
        return above > 0 ? shadeSky(above) : GROUND;
    }

    /** The static pitch tape for the GPU ball: {@link #tapeColour} sampled in canvas units, ±120° top to bottom. */
    private static void paintTape(NativeImage img) {
        float k = PX_PER_DEG / TAPE_PX_PER_DEG; // canvas pixels per tape pixel
        for (int y = 0; y < TAPE_H; y++) {
            for (int x = 0; x < TAPE_W; x++) {
                float along = (x + 0.5F - TAPE_W / 2F) * k, above = (TAPE_H / 2F - y - 0.5F) * k;
                img.setPixel(x, y, tapeColour(along, above));
            }
        }
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
