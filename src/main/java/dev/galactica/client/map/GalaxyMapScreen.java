package dev.galactica.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.client.hud.OrbitReadout;
import dev.galactica.client.nav.AutopilotClient;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Conics;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.OrbitalFrame;
import dev.galactica.nav.autopilot.AutopilotLegs;
import dev.galactica.nav.autopilot.AutopilotMath;
import dev.galactica.nav.autopilot.AutopilotServer;
import dev.galactica.nav.autopilot.AutopilotState;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.nav.target.NavTargets;
import dev.galactica.nav.target.TargetGeometry;
import dev.galactica.nav.target.TargetSearch;
import dev.galactica.net.SetNavTargetPayload;
import dev.galactica.science.Units;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.Starship;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The 3D Galaxy Map (key M, on foot, on EVA or at the stick). A real 3D view drawn with Galactica's own pipelines
 * ({@link MapRenderer}) that zooms smoothly from the whole Milky Way (the disk impostor, catalog stars, nebulae,
 * clusters, black holes) through the stars around the focus (streamed per sector, {@link MapStarField}) down to a
 * star system (planets and moons where they are now, orbits from their elements, the player's craft and its conic)
 * and a single planet's moons.
 *
 * <p><b>Controls.</b> Left-drag orbits the camera round the focus, right-drag pans it, WASD/QE fly the focus, the wheel
 * zooms (log scale), a click selects, a double-click flies to the object, Home returns to the player, Enter (or the
 * button) locks the selection as the navigation target, which the server validates and syncs ({@link NavTargets});
 * "Engage" hands it to the autopilot in the chosen mode (orbit or land). The search box finds catalog stars, planets
 * ("Mars"), deep-sky objects and procedural designations ({@link TargetSearch}).
 */
public class GalaxyMapScreen extends Screen {
    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, ACCENT = 0xFF7FE0FF, TARGET = 0xFFFFD54F, WARN = 0xFFFF7A6E,
            PANEL = 0xC0000814, OK = 0xFF7FFFB0;
    private static final int PANEL_W = 214, MARGIN = 6;
    /** Kept across openings, like a chart left open at the same page. */
    private static @Nullable MapCamera keptCamera;

    private final MapCamera camera;
    private final MapScene scene = new MapScene();
    private final MapStarField field = new MapStarField();
    private EditBox search;
    private Button lockButton, engageButton, modeButton, clearButton;
    private List<NavTarget> results = List.of();
    private @Nullable MapObject selected, hovered;
    private long lastNanos;
    private double dragX, dragY, dragDistance;
    private int dragButton = -1;
    private int guiScale = 1;
    private @Nullable GalacticPos player;
    private double clock;

    public GalaxyMapScreen() {
        super(Component.translatable("screen.galactica.galaxy_map"));
        MapCamera c = keptCamera;
        if (c == null) {
            c = new MapCamera(GalacticPos.ofParsecs(0, 0, 0), 40_000 * Units.PARSEC, Math.toRadians(200), Math.toRadians(35));
            keptCamera = c;
            playerPosition();
            if (player != null) c.snapTo(player, homeDistance());
        }
        camera = c;
        MapRenderer.install();
    }

    MapCamera camera() {
        return camera;
    }

    MapStarField field() {
        return field;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        MapRenderer.attach(this);
        FaceOnTexture.request();
        search = new EditBox(font, MARGIN, MARGIN + 12, 170, 16, Component.translatable("map.galactica.search"));
        search.setHint(Component.translatable("map.galactica.search.hint"));
        search.setResponder(this::runSearch);
        addRenderableWidget(search);
        int bx = width - PANEL_W - MARGIN, bw = (PANEL_W - 4) / 2, by = height - MARGIN - 20 * 3 - 4;
        lockButton = addRenderableWidget(Button.builder(Component.translatable("map.galactica.button.lock"), b -> lockSelected())
                .bounds(bx, by, bw, 18).build());
        clearButton = addRenderableWidget(Button.builder(Component.translatable("map.galactica.button.clear"), b -> clearTarget())
                .bounds(bx + bw + 4, by, bw, 18).build());
        engageButton = addRenderableWidget(Button.builder(Component.translatable("map.galactica.button.engage"), b -> toggleAutopilot())
                .bounds(bx, by + 20, bw, 18).build());
        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
                    AutopilotClient.setMode(AutopilotClient.mode() == AutopilotState.Mode.ORBIT ? AutopilotState.Mode.LAND : AutopilotState.Mode.ORBIT);
                    b.setMessage(modeLabel());
                }).bounds(bx + bw + 4, by + 20, bw, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("map.galactica.button.home"), b -> home())
                .bounds(bx, by + 40, PANEL_W, 18).build());
        lastNanos = System.nanoTime();
    }

    private Component modeLabel() {
        return Component.translatable(AutopilotClient.mode() == AutopilotState.Mode.LAND ? "map.galactica.button.mode_land" : "map.galactica.button.mode_orbit");
    }

    @Override
    public void removed() {
        MapRenderer.attach(null);
        super.removed();
    }

    @Override
    public void tick() {
        playerPosition();
        boolean engaged = minecraft.player != null && AutopilotServer.state(TargetReadoutAccess.owner(minecraft.player)).engaged();
        engageButton.setMessage(Component.translatable(engaged ? "map.galactica.button.disengage" : "map.galactica.button.engage"));
        lockButton.active = selected != null && selected.toTarget() != null;
        clearButton.active = minecraft.player != null && NavTargets.get(minecraft.player) != null;
    }

    // ---------------------------------------------------------------- frame (MapRenderer calls this at END_MAIN)

    /** Eases the camera, flies the focus with the keys, streams the field and builds this frame's scene. */
    MapScene prepareFrame() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (search == null || !search.isFocused()) {
            double f = 0, s = 0, l = 0;
            if (InputConstants.isKeyDown(InputConstants.KEY_W)) f += 1;
            if (InputConstants.isKeyDown(InputConstants.KEY_S)) f -= 1;
            if (InputConstants.isKeyDown(InputConstants.KEY_D)) s += 1;
            if (InputConstants.isKeyDown(InputConstants.KEY_A)) s -= 1;
            if (InputConstants.isKeyDown(InputConstants.KEY_E)) l += 1;
            if (InputConstants.isKeyDown(InputConstants.KEY_Q)) l -= 1;
            if (f != 0 || s != 0 || l != 0) camera.fly(f * dt * 0.8, s * dt * 0.8, l * dt * 0.8);
        }
        camera.update(dt);
        field.request(camera.focus(), camera.distance());
        playerPosition();
        scene.build(camera, field.current(), clock, player);
        return scene;
    }

    private void playerPosition() {
        if (minecraft == null || minecraft.player == null) return;
        NavState nav = minecraft.player.getAttached(Galactica.NAV);
        if (nav == null) {
            player = null;
            return;
        }
        clock = nav.timeSeconds();
        var root = minecraft.player.getRootVehicle();
        player = minecraft.level.dimension() == Galactica.SPACE ? Frames.playerPos(nav, root.getX(), root.getY(), root.getZ()) : nav.origin();
    }

    /** Distance Home frames: the player's system when in one, else the stars around. */
    private double homeDistance() {
        if (player == null) return 40_000 * Units.PARSEC;
        Star s = SkyManager.get().neighbourhood().systemStar(player);
        return s != null ? 6 * Units.AU : 3 * Units.PARSEC;
    }

    public void home() {
        playerPosition();
        if (player != null) camera.flyTo(player, homeDistance());
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var view = MapRenderer.view();
        if (view == null) {
            g.fill(0, 0, width, height, 0xFF010208);
            return;
        }
        g.blit(view, MapRenderer.sampler(), 0, 0, width, height, 0F, 1F, 1F, 0F);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            draw(g, mouseX, mouseY);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            drawResults(g, mouseX, mouseY);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.MAP, perf);
        }
    }

    private void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        guiScale = Math.max(1, (int) Math.round((double) camera.width() / Math.max(1, width)));
        double k = 1.0 / guiScale; // camera pixels → GUI units
        NavTarget target = minecraft.player != null ? NavTargets.get(TargetReadoutAccess.owner(minecraft.player)) : null;

        for (MapScene.Polyline l : scene.lines) polyline(g, l.xy(), k, l.argb());
        drawTrajectory(g, k);

        // Hover under the cursor (the map area, not the panel).
        hovered = null;
        if (mouseX < width - PANEL_W - MARGIN * 2 || mouseY < MARGIN) {
            List<MapPicking.Hit<MapObject>> hits = new ArrayList<>(scene.drawn.size());
            for (MapScene.Drawn d : scene.drawn) {
                if (d.object().kind() == MapObject.Kind.PLAYER) continue;
                hits.add(new MapPicking.Hit<>(d.object(), d.x() * k, d.y() * k, Math.max(2, d.radiusPx() * k), d.object().importance()));
            }
            MapPicking.Hit<MapObject> h = MapPicking.pick(hits, mouseX, mouseY);
            if (h != null) hovered = h.object();
        }

        // Labels, markers.
        List<MapPicking.Label<MapObject>> labels = new ArrayList<>();
        double distPc = camera.distance() / Units.PARSEC;
        MapScene.Drawn playerDrawn = null, targetDrawn = null;
        for (MapScene.Drawn d : scene.drawn) {
            MapObject o = d.object();
            if (o.kind() == MapObject.Kind.PLAYER) {
                playerDrawn = d;
                continue;
            }
            if (o.matches(target)) targetDrawn = d;
            double x = d.x() * k, y = d.y() * k;
            if (x < 0 || y < 0 || x > width || y > height) continue;
            double imp = o.importance();
            if (o.kind() == MapObject.Kind.STAR && !o.star().catalog() && imp < 8 && distPc > 4) continue;
            if (o.kind() == MapObject.Kind.DEEP_SKY && distPc < 20 && o.deepSky().distanceTo(camera.focus().xPc(), camera.focus().yPc(), camera.focus().zPc()) > 600) continue;
            int lw = font.width(o.name());
            labels.add(new MapPicking.Label<>(o, o.name(), x + d.radiusPx() * k + 3, y - 4, lw, 9, imp));
        }
        for (MapPicking.Label<MapObject> l : MapPicking.placeLabels(labels, 45)) {
            int c = l.object().kind() == MapObject.Kind.STAR || l.object().kind() == MapObject.Kind.BLACK_HOLE
                    ? 0xFF000000 | brighten(l.object().argb()) : l.object().kind() == MapObject.Kind.DEEP_SKY ? 0xFFB8C4E0 : TEXT;
            g.text(font, l.text(), (int) l.x(), (int) l.y(), c, true);
        }
        if (playerDrawn != null) {
            int x = (int) (playerDrawn.x() * k), y = (int) (playerDrawn.y() * k);
            g.fill(x - 5, y, x + 6, y + 1, ACCENT);
            g.fill(x, y - 5, x + 1, y + 6, ACCENT);
            g.text(font, Component.translatable("map.galactica.you"), x + 7, y + 3, ACCENT, true);
        }
        if (targetDrawn != null) {
            int x = (int) (targetDrawn.x() * k), y = (int) (targetDrawn.y() * k);
            double r = Math.max(7, targetDrawn.radiusPx() * k + 4);
            diamond(g, x, y, r, TARGET);
            if (playerDrawn != null) line(g, playerDrawn.x() * k, playerDrawn.y() * k, x, y, 0x90FFD54F);
        }
        MapObject sel = selected;
        for (MapScene.Drawn d : scene.drawn) {
            if (sel != null && d.object().same(sel)) circle(g, d.x() * k, d.y() * k, Math.max(6, d.radiusPx() * k + 4), ACCENT);
            else if (hovered != null && d.object().same(hovered)) circle(g, d.x() * k, d.y() * k, Math.max(5, d.radiusPx() * k + 3), 0xA0D8E4FF);
        }

        g.text(font, title, MARGIN, MARGIN, ACCENT, false);
        drawScaleBar(g);
        drawPanel(g, target);
        drawHints(g);
    }

    private static int brighten(int argb) {
        int r = argb >> 16 & 0xFF, gr = argb >> 8 & 0xFF, b = argb & 0xFF;
        int m = Math.max(r, Math.max(gr, b));
        double f = m < 200 ? 200.0 / Math.max(1, m) : 1;
        return (int) Math.min(255, r * f) << 16 | (int) Math.min(255, gr * f) << 8 | (int) Math.min(255, b * f);
    }

    /** The craft's conic around its dominant body (dashed), when one is in view. */
    private void drawTrajectory(GuiGraphicsExtractor g, double k) {
        OrbitalFrame.Dominant[] d = new OrbitalFrame.Dominant[1];
        double[][] rv = OrbitReadout.relativeState(minecraft, d);
        if (rv == null) return;
        double[] pts = Conics.points(rv[0], rv[1], d[0].mu(), 160, Math.min(d[0].soi(), 1e13));
        if (pts.length == 0) return;
        double[] xy = MapScene.projectPolyline(camera, d[0].pos(), pts);
        polylineDashed(g, xy, k, 0xC07FE0FF);
    }

    private void drawScaleBar(GuiGraphicsExtractor g) {
        double pxPerM = camera.pixelsPerMetreAtFocus() / guiScale;
        double[][] units = {{1, 0}, {1e3, 1}, {Units.AU, 2}, {Units.LIGHT_YEAR, 3}, {Units.PARSEC, 4}, {1e3 * Units.PARSEC, 5}};
        String[] names = {"m", "km", "AU", "ly", "pc", "kpc"};
        double want = 90 / pxPerM;
        int ui = 0;
        for (int i = 0; i < units.length; i++) if (want >= units[i][0] * (i == 3 ? 0.3 : 1)) ui = i;
        if (ui == 3 && want >= Units.PARSEC) ui = 4;
        double inUnit = want / units[ui][0];
        double p = Math.pow(10, Math.floor(Math.log10(inUnit)));
        double nice = inUnit / p >= 5 ? 5 * p : inUnit / p >= 2 ? 2 * p : p;
        int len = (int) Math.round(nice * units[ui][0] * pxPerM);
        int x0 = MARGIN, y = height - MARGIN - 4;
        g.fill(x0, y, x0 + len, y + 1, TEXT);
        g.fill(x0, y - 3, x0 + 1, y + 1, TEXT);
        g.fill(x0 + len - 1, y - 3, x0 + len, y + 1, TEXT);
        String label = (nice >= 1 ? String.format(Locale.ROOT, "%,.0f", nice) : String.format(Locale.ROOT, "%.2g", nice)) + " " + names[ui];
        g.text(font, label, x0, y - 12, TEXT, true);
        g.text(font, Component.translatable("map.galactica.camera", distanceText(camera.distance())), x0 + Math.max(len, 60) + 8, y - 4, DIM, true);
    }

    private void drawHints(GuiGraphicsExtractor g) {
        int y = MARGIN + 34 + Math.min(results.size(), 8) * 11;
        String[] keys = {"map.galactica.hint.orbit", "map.galactica.hint.pan", "map.galactica.hint.zoom3d", "map.galactica.hint.pick",
                "map.galactica.hint.keys"};
        for (String key : keys) {
            g.text(font, Component.translatable(key), MARGIN, y, 0xFF6A7898, true);
            y += 10;
        }
    }

    /** The info panel: the hovered (else selected) object, the locked target, the autopilot. */
    private void drawPanel(GuiGraphicsExtractor g, @Nullable NavTarget target) {
        List<Row> rows = new ArrayList<>();
        MapObject o = hovered != null ? hovered : selected;
        if (o != null) describe(o, rows, target);
        else rows.add(new Row(Component.translatable("map.galactica.panel.none"), DIM));
        rows.add(Row.GAP);
        if (target != null) {
            rows.add(new Row(Component.translatable("map.galactica.panel.target", target.name()), TARGET));
            var r = dev.galactica.client.nav.TargetReadout.current();
            if (r != null) {
                rows.add(new Row(Component.translatable("map.galactica.panel.target_distance", distanceText(Math.max(0, r.surface())),
                        r.eta() >= 0 ? AutopilotMath.formatDuration(r.eta()) : "?"), TEXT));
            }
        } else {
            rows.add(new Row(Component.translatable("map.galactica.panel.no_target"), DIM));
        }
        AutopilotState ap = minecraft.player != null ? AutopilotServer.state(TargetReadoutAccess.owner(minecraft.player)) : AutopilotState.OFF;
        rows.add(new Row(ap.engaged() ? Component.translatable("map.galactica.panel.autopilot", Component.translatable(ap.phase().translationKey()))
                : Component.translatable("map.galactica.panel.autopilot_off"), ap.engaged() ? OK : DIM));
        Starship ship = minecraft.player != null && minecraft.player.getVehicle() instanceof Starship s ? s : null;
        if (ship != null) {
            rows.add(new Row(Component.translatable("map.galactica.panel.fuel", String.format(Locale.ROOT, "%.0f", ship.hydrogen()), ship.exotic(),
                    String.format(Locale.ROOT, "%.0f", DriveRules.rangeLy(ship.exotic()))), DIM));
        }
        int h = 8;
        for (Row r : rows) h += r.height();
        int x = width - PANEL_W - MARGIN, y = MARGIN;
        int bottom = height - MARGIN - 20 * 3 - 8;
        g.fill(x, y, x + PANEL_W, Math.min(bottom, y + h), PANEL);
        g.fill(x, y, x + 2, Math.min(bottom, y + h), ACCENT);
        int yy = y + 5;
        for (Row r : rows) {
            if (yy + 10 > bottom) break;
            if (r.text() != null) g.text(font, r.text(), x + 6, yy, r.colour(), false);
            yy += r.height();
        }
    }

    private record Row(Component text, int colour, int height) {
        static final Row GAP = new Row(null, 0, 4);

        Row(Component text, int colour) {
            this(text, colour, 10);
        }
    }

    private void describe(MapObject o, List<Row> rows, @Nullable NavTarget target) {
        rows.add(new Row(Component.literal(o.name()), 0xFF000000 | brighten(o.argb())));
        switch (o.kind()) {
            case STAR, BLACK_HOLE -> {
                Star s = o.star();
                if (s.kind() == StarKind.BLACK_HOLE) {
                    rows.add(new Row(Component.translatable("map.galactica.info.black_hole",
                            String.format(Locale.ROOT, "%.3g", s.mass())), TEXT));
                } else {
                    rows.add(new Row(Component.translatable("map.galactica.info.star", s.spectralType(),
                            String.format(Locale.ROOT, "%,.0f", s.temperature())), TEXT));
                    rows.add(new Row(Component.translatable("map.galactica.info.star_physics", fmt(s.mass()), fmt(s.radius()), fmt(s.luminosity())), DIM));
                }
                int planets = TargetGeometry.system(s).size();
                rows.add(new Row(Component.translatable("map.galactica.info.planets", planets), DIM));
            }
            case PLANET, MOON -> {
                Body b = o.body();
                rows.add(new Row(Component.translatable(o.kind() == MapObject.Kind.MOON ? "map.galactica.info.moon" : "map.galactica.info.planet",
                        b.type().label, o.parent() != null ? o.parent().name() : o.star() != null ? o.star().name() : "?"), TEXT));
                rows.add(new Row(Component.translatable("map.galactica.info.body_physics", fmt(b.massEarth()), fmt(b.radiusEarth()),
                        String.format(Locale.ROOT, "%.2f", b.surfaceGravityG())), DIM));
                rows.add(new Row(Component.translatable("map.galactica.info.climate", fmt(b.surfacePressureBar()),
                        String.format(Locale.ROOT, "%.0f", b.surfaceTemp())), DIM));
                double a = b.orbit().semiMajorAxis();
                rows.add(new Row(Component.translatable("map.galactica.info.orbit", o.kind() == MapObject.Kind.MOON
                        ? String.format(Locale.ROOT, "%,.0f km", a / 1e3) : String.format(Locale.ROOT, "%.3f AU", a / Units.AU),
                        String.format(Locale.ROOT, "%.1f", b.orbit().periodSeconds() / 86400)), DIM));
                if (AutopilotMath.landable(b)) rows.add(new Row(Component.translatable("map.galactica.info.landable"), DIM));
            }
            case DEEP_SKY -> {
                DeepSkyObject d = o.deepSky();
                rows.add(new Row(Component.translatable(d.type.translationKey()), TEXT));
                rows.add(new Row(Component.translatable("map.galactica.info.deepsky_size", fmt(d.radiusPc)), DIM));
            }
            default -> { }
        }
        if (player != null) {
            double dist = o.pos().distanceMetres(player);
            rows.add(new Row(Component.translatable("map.galactica.info.distance", distanceText(dist)), TEXT));
            travel(o, dist, rows);
        }
        if (o.matches(target)) rows.add(new Row(Component.translatable("map.galactica.info.locked"), TARGET));
        else if (o.toTarget() != null) rows.add(new Row(Component.translatable("map.galactica.info.lock_hint"), DIM));
    }

    /** Travel-time estimates under the real drive rules: warp (governed), hyperjump chain, sublight burn. */
    private void travel(MapObject o, double dist, List<Row> rows) {
        NavState nav = minecraft.player.getAttached(Galactica.NAV);
        double max = nav != null ? nav.maxSpeed() : Frames.DEFAULT_MAX_SPEED;
        double radius = o.isBody() ? o.body().radiusMetres() : o.star() != null ? TargetGeometry.starRadius(o.star())
                : o.deepSky() != null ? o.deepSky().radiusPc * Units.PARSEC : 0;
        double arrival = o.isBody() ? AutopilotMath.orbitAltitude(o.body()) : o.star() != null
                ? AutopilotMath.starArrivalDistance(o.star(), radius) - radius : radius * 0.5;
        double warp = AutopilotMath.warpSeconds(Math.max(0, dist - radius), arrival, max);
        rows.add(new Row(Component.translatable("map.galactica.info.eta_warp", AutopilotMath.formatDuration(warp)), DIM));
        double ly = dist / Units.LIGHT_YEAR;
        if (ly > 0.05) {
            int jumps = (int) Math.ceil(ly / DriveRules.TIER_RANGE_LY);
            rows.add(new Row(Component.translatable("map.galactica.info.eta_jump", jumps, (int) Math.ceil(ly),
                    AutopilotMath.formatDuration(AutopilotMath.hyperjumpSeconds(jumps))), DIM));
        }
        double sub = AutopilotMath.sublightSeconds(Math.max(0, dist - radius));
        rows.add(new Row(Component.translatable("map.galactica.info.eta_sublight", sub < 3e8 ? AutopilotMath.formatDuration(sub) : "—"), DIM));
    }

    private static String fmt(double v) {
        if (v == 0) return "0";
        double a = Math.abs(v);
        if (a >= 1e4 || a < 1e-2) return String.format(Locale.ROOT, "%.2e", v);
        if (a >= 100) return String.format(Locale.ROOT, "%,.0f", v);
        return String.format(Locale.ROOT, "%.3g", v);
    }

    static String distanceText(double m) {
        if (m >= 1e3 * Units.PARSEC) return String.format(Locale.ROOT, "%.2f kpc", m / Units.PARSEC / 1e3);
        if (m >= 0.3 * Units.PARSEC) return String.format(Locale.ROOT, "%.2f pc (%.1f ly)", m / Units.PARSEC, m / Units.LIGHT_YEAR);
        return AutopilotServer.distance(m);
    }

    private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (results.isEmpty() || search == null || search.getValue().isBlank()) return;
        int x = MARGIN, y = MARGIN + 30;
        int n = Math.min(results.size(), 8);
        g.fill(x, y, x + 230, y + n * 11 + 2, 0xE0000814);
        for (int i = 0; i < n; i++) {
            NavTarget t = results.get(i);
            int ry = y + 1 + i * 11;
            boolean over = mouseX >= x && mouseX < x + 230 && mouseY >= ry && mouseY < ry + 11;
            if (over) g.fill(x, ry, x + 230, ry + 11, 0x603A5A8A);
            g.text(font, Component.literal(t.name()).append(Component.literal("  ")).append(Component.translatable(t.kind().translationKey())
                    .withColor(DIM & 0xFFFFFF)), x + 3, ry + 1, TEXT, false);
        }
    }

    // ---------------------------------------------------------------- search, selection, target

    private void runSearch(String text) {
        if (text == null || text.isBlank()) {
            results = List.of();
            return;
        }
        Star cur = scene.system != null ? scene.system : player != null ? SkyManager.get().neighbourhood().systemStar(player) : null;
        results = TargetSearch.search(Galactica.GALAXY, text, cur, 8);
    }

    /** Selects a search hit (or a target) and flies to it. Public for the autotest. */
    public boolean selectAndFocus(NavTarget t) {
        MapObject o = toObject(t);
        if (o == null) return false;
        selected = o;
        focus(o);
        return true;
    }

    /** Searches and focuses the first hit (autotest). */
    public boolean searchAndFocus(String text) {
        search.setValue(text);
        runSearch(text);
        if (results.isEmpty()) return false;
        boolean ok = selectAndFocus(results.getFirst());
        results = List.of();
        search.setValue("");
        return ok;
    }

    private @Nullable MapObject toObject(NavTarget t) {
        switch (t.kind()) {
            case STAR -> {
                Star s = TargetGeometry.star(Galactica.GALAXY, t);
                if (s == null) return null;
                return MapObject.star(s, t.index() == NavTarget.CATALOG ? null : t.sector(), t.index(), 60);
            }
            case PLANET, MOON -> {
                return scene.findBody(t, clock);
            }
            case DEEP_SKY -> {
                DeepSkyObject d = TargetGeometry.deepSky(Galactica.GALAXY.seed(), t.deepSkyId());
                if (d == null) return null;
                return new MapObject(MapObject.Kind.DEEP_SKY, d.name, null, null, 0, null, null, d, GalacticPos.ofParsecs(d.x, d.y, d.z),
                        MapScene.deepSkyColour(d.type), 40);
            }
        }
        return null;
    }

    /** Flies the camera to an object at a distance that frames it: a system, a planet with its moons, a nebula. */
    public void focus(MapObject o) {
        double d;
        switch (o.kind()) {
            case STAR, BLACK_HOLE -> {
                Star s = o.star();
                double apo = 0;
                for (Body b : TargetGeometry.system(s)) apo = Math.max(apo, b.orbit().apoapsis());
                d = apo > 0 ? Math.max(2 * Units.AU, apo * 2.6) : Math.max(1.5 * Units.AU, TargetGeometry.starRadius(s) * 400);
                if (s == StarCatalog.SGR_A_STAR) d = 0.05 * Units.PARSEC;
            }
            case PLANET, MOON -> {
                Body b = o.body();
                double apo = 0;
                for (Body m : b.moons()) apo = Math.max(apo, m.orbit().apoapsis());
                d = apo > 0 ? apo * 3.2 : b.radiusMetres() * 14;
            }
            case DEEP_SKY -> d = o.deepSky().radiusPc * Units.PARSEC * 4;
            default -> d = 6 * Units.AU;
        }
        camera.flyTo(o.pos(), d);
    }

    private void lockSelected() {
        MapObject o = selected;
        if (o == null) return;
        NavTarget t = o.toTarget();
        if (t == null || !ClientPlayNetworking.canSend(SetNavTargetPayload.TYPE)) return;
        ClientPlayNetworking.send(switch (t.kind()) {
            case STAR -> SetNavTargetPayload.star(t.starId(), t.sector(), t.index());
            case PLANET, MOON -> SetNavTargetPayload.body(t.kind(), t.starId(), t.sector(), t.index(), t.bodyId());
            case DEEP_SKY -> SetNavTargetPayload.deepSky(t.deepSkyId());
        });
    }

    /** Locks the current selection (autotest and the Enter key). */
    public boolean lockSelection() {
        if (selected == null || selected.toTarget() == null) return false;
        lockSelected();
        return true;
    }

    private void clearTarget() {
        if (ClientPlayNetworking.canSend(SetNavTargetPayload.TYPE)) ClientPlayNetworking.send(SetNavTargetPayload.clear());
    }

    private void toggleAutopilot() {
        if (minecraft.player == null) return;
        if (AutopilotServer.state(minecraft.player).engaged()) AutopilotClient.disengage();
        else AutopilotClient.engage(AutopilotClient.mode());
    }

    public @Nullable MapObject selected() {
        return selected;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        // Search results first: they float over the map.
        if (!results.isEmpty() && search != null && !search.getValue().isBlank()) {
            int x = MARGIN, y = MARGIN + 30, n = Math.min(results.size(), 8);
            if (e.x() >= x && e.x() < x + 230 && e.y() >= y && e.y() < y + n * 11 + 2) {
                int i = (int) ((e.y() - y - 1) / 11);
                if (i >= 0 && i < n) {
                    selectAndFocus(results.get(i));
                    results = List.of();
                    search.setValue("");
                    setFocused(null);
                    return true;
                }
            }
        }
        if (super.mouseClicked(e, doubleClick)) return true;
        if (search != null) search.setFocused(false);
        setFocused(null);
        if (e.x() >= width - PANEL_W - MARGIN * 2 && e.y() >= MARGIN) return false;
        if (doubleClick && e.button() == 0 && hovered != null) {
            selected = hovered;
            focus(hovered);
            dragButton = -1;
            return true;
        }
        dragButton = e.button();
        dragX = e.x();
        dragY = e.y();
        dragDistance = 0;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragButton < 0) return super.mouseDragged(e, dx, dy);
        dragDistance += Math.abs(dx) + Math.abs(dy);
        double s = guiScale;
        if (dragButton == 0) camera.orbit(dx * s, dy * s);
        else camera.pan(dx * s, dy * s);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragButton == 0 && dragDistance < 3) selected = hovered;
        dragButton = -1;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        camera.zoom(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        boolean typing = search != null && search.isFocused();
        if (typing && e.key() == InputConstants.KEY_RETURN) {
            if (!results.isEmpty()) {
                selectAndFocus(results.getFirst());
                results = List.of();
                search.setValue("");
            }
            search.setFocused(false);
            setFocused(null);
            return true;
        }
        if (!typing) {
            if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
                lockSelected();
                return true;
            }
            if (e.key() == InputConstants.KEY_HOME) {
                home();
                return true;
            }
            if (GalaxyMapClient.openMap != null && GalaxyMapClient.openMap.matches(e)) {
                onClose();
                return true;
            }
        }
        return super.keyPressed(e);
    }

    // ---------------------------------------------------------------- GUI primitives

    private static void polyline(GuiGraphicsExtractor g, double[] xy, double k, int argb) {
        for (int i = 2; i + 1 < xy.length; i += 2) segment(g, xy[i - 2] * k, xy[i - 1] * k, xy[i] * k, xy[i + 1] * k, argb);
    }

    private static void polylineDashed(GuiGraphicsExtractor g, double[] xy, double k, int argb) {
        for (int i = 2; i + 1 < xy.length; i += 2) if ((i / 2) % 2 == 0) segment(g, xy[i - 2] * k, xy[i - 1] * k, xy[i] * k, xy[i + 1] * k, argb);
    }

    private static void segment(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int argb) {
        if (Double.isNaN(x0) || Double.isNaN(x1)) return;
        if (Math.max(Math.abs(x0), Math.abs(x1)) > 1e4 || Math.max(Math.abs(y0), Math.abs(y1)) > 1e4) return;
        line(g, x0, y0, x1, y1, argb);
    }

    /** A 1 px line as a rotated fill: the GUI has no line primitive, but fills honour the pose. */
    static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int colour) {
        double dx = x1 - x0, dy = y1 - y0;
        int len = (int) Math.round(Math.sqrt(dx * dx + dy * dy));
        if (len < 1) return;
        g.pose().pushMatrix();
        g.pose().translate((float) x0, (float) y0);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.fill(0, 0, len, 1, colour);
        g.pose().popMatrix();
    }

    static void circle(GuiGraphicsExtractor g, double cx, double cy, double r, int colour) {
        int n = (int) Math.max(16, Math.min(64, r * 0.8));
        double px = cx + r, py = cy;
        for (int i = 1; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            line(g, px, py, x, y, colour);
            px = x;
            py = y;
        }
    }

    private static void diamond(GuiGraphicsExtractor g, double cx, double cy, double r, int colour) {
        line(g, cx - r, cy, cx, cy - r, colour);
        line(g, cx, cy - r, cx + r, cy, colour);
        line(g, cx + r, cy, cx, cy + r, colour);
        line(g, cx, cy + r, cx - r, cy, colour);
    }

    /** Whose course the map shows: the pilot's for a passenger. */
    private static final class TargetReadoutAccess {
        static net.minecraft.world.entity.player.Player owner(net.minecraft.world.entity.player.Player p) {
            return dev.galactica.client.nav.TargetReadout.courseOwner(p);
        }
    }
}
