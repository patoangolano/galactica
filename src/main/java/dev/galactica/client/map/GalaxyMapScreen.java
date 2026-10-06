package dev.galactica.client.map;

import dev.galactica.Galactica;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.JumpTarget;
import dev.galactica.nav.JumpTargets;
import dev.galactica.net.SetJumpTargetPayload;
import dev.galactica.science.Blackbody;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Galaxy Map (spec §4). A face-on Milky Way ({@link FaceOnTexture}) under markers for the
 * player, Sol, the real catalog and, at the 300 pc and 30 pc zooms, the procedural stars around
 * the player ({@link MapStars}, listed on a worker from the client's own deterministic galaxy).
 * Clicking a star asks the server to make it the hyperjump target; the synced
 * {@link JumpTargets#TYPE} attachment is what the map then draws, so the highlight always shows the
 * server's answer, never the click.
 */
public class GalaxyMapScreen extends Screen {
    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, ACCENT = 0xFF7FE0FF, TARGET = 0xFFFFD54F,
            WARN = 0xFFFF7A6E, SOL = 0xFFFFE9A0, PANEL = 0xC0000814;
    private static final int MARGIN = 8, PANEL_W = 200, HOVER_PX = 6;
    private static final Identifier EXOTIC_MATTER = Galactica.id("exotic_matter");

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica map stars");
        t.setDaemon(true);
        return t;
    });
    /** Last zoom level, kept across openings like a real chart left open at the same page. */
    private static int lastLevel = 1;

    private int level = lastLevel;
    private int mapLeft, mapTop, mapSize, panelLeft;
    private double px, py, pz;
    private boolean havePos;

    private List<MapStars.MapStar> stars = List.of();
    private CompletableFuture<List<MapStars.MapStar>> pendingStars;
    private String starsKey = "";

    private final List<Hit> hits = new ArrayList<>();

    private record Hit(double sx, double sy, Star star, SectorKey sector, int index) {}

    public GalaxyMapScreen() {
        super(Component.translatable("screen.galactica.galaxy_map"));
    }

    @Override
    protected void init() {
        int avail = Math.min(width - 3 * MARGIN - PANEL_W, height - 2 * MARGIN - 12);
        mapSize = Math.max(96, avail);
        mapLeft = MARGIN;
        mapTop = MARGIN + 12;
        panelLeft = Math.min(width - PANEL_W - MARGIN, mapLeft + mapSize + MARGIN);
        updatePlayer();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        FaceOnTexture.resolve();
        updatePlayer();
        refreshStars();
    }

    private void updatePlayer() {
        if (minecraft == null || minecraft.player == null) return;
        GalacticPos p = JumpTargets.galacticPosition(minecraft.player);
        px = p.xPc();
        py = p.yPc();
        pz = p.zPc();
        havePos = true;
    }

    private void refreshStars() {
        if (pendingStars != null && pendingStars.isDone()) {
            stars = pendingStars.getNow(List.of());
            pendingStars = null;
        }
        double w = MapView.ZOOM_WIDTHS_PC[level];
        if (!havePos || w > MapStars.MAX_VIEW_PC) {
            stars = List.of();
            starsKey = "";
            return;
        }
        double cell = w / 8, zCell = MapStars.slabHalfPc(w) / 4;
        String key = level + ":" + Math.floor(px / cell) + ":" + Math.floor(py / cell) + ":" + Math.floor(pz / zCell);
        if (key.equals(starsKey) || pendingStars != null) return;
        starsKey = key;
        double cx = px, cy = py, cz = pz;
        pendingStars = CompletableFuture.supplyAsync(() -> MapStars.list(Galactica.GALAXY, cx, cy, cz, w), WORKER)
                .exceptionally(e -> {
                    Galactica.LOG.error("Galactica: listing map stars failed", e);
                    return List.of();
                });
    }

    private MapView view() {
        boolean centred = level == 0 || !havePos;
        return new MapView(centred ? 0 : px, centred ? 0 : py, MapView.ZOOM_WIDTHS_PC[level], mapLeft, mapTop, mapSize);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Opaque: the chart replaces the world view instead of blurring it.
        g.fill(0, 0, width, height, 0xF0000208);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        MapView v = view();
        hits.clear();
        g.text(font, title, mapLeft, MARGIN, ACCENT, false);

        g.enableScissor(mapLeft, mapTop, mapLeft + mapSize, mapTop + mapSize);
        g.fill(mapLeft, mapTop, mapLeft + mapSize, mapTop + mapSize, 0xFF000000);
        drawBackground(g, v);
        if (level <= 1) drawArmLabels(g, v);
        if (havePos) drawRanges(g, v);
        for (MapStars.MapStar m : stars) drawStar(g, v, m.star(), m.sector(), m.index(), false);
        drawCatalog(g, v);
        JumpTarget target = minecraft.player != null ? JumpTargets.get(minecraft.player) : null;
        if (target != null && havePos) drawTarget(g, v, target);
        if (havePos) drawPlayer(g, v);
        g.disableScissor();

        g.outline(mapLeft - 1, mapTop - 1, mapSize + 2, mapSize + 2, 0xFF2A3A5A);
        drawScaleBar(g, v);
        drawPanel(g, target);

        Hit h = hitAt(mouseX, mouseY);
        if (h != null) g.setComponentTooltipForNextFrame(font, tooltip(h.star()), mouseX, mouseY);
    }

    private void drawBackground(GuiGraphicsExtractor g, MapView v) {
        if (!FaceOnTexture.ready()) {
            String key = FaceOnTexture.failed() ? "map.galactica.bake_failed" : "map.galactica.baking";
            g.centeredText(font, Component.translatable(key), mapLeft + mapSize / 2, mapTop + mapSize / 2, DIM);
            return;
        }
        float[] b = v.backgroundBlit();
        if (b != null) g.blit(FaceOnTexture.ID, (int) b[0], (int) b[1], (int) b[2], (int) b[3], b[4], b[5], b[6], b[7]);
        // At 300 pc a background texel is ~29 pc, at 30 pc it covers the view: dim it to a tint
        // so the individual stars carry the picture.
        if (level == 2) g.fill(mapLeft, mapTop, mapLeft + mapSize, mapTop + mapSize, 0x99000000);
        if (level == 3) g.fill(mapLeft, mapTop, mapLeft + mapSize, mapTop + mapSize, 0xCC000000);
    }

    private void drawArmLabels(GuiGraphicsExtractor g, MapView v) {
        double[][] a = ArmLegend.anchors();
        for (int i = 0; i < ArmLegend.ARM_COUNT; i++) {
            int sx = (int) v.screenX(a[i][0], a[i][1]), sy = (int) v.screenY(a[i][0], a[i][1]);
            if (!v.contains(sx, sy)) continue;
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, ArmLegend.COLOURS[i]);
            g.centeredText(font, Component.translatable(ArmLegend.KEYS[i]), sx, sy + 4, ArmLegend.COLOURS[i]);
        }
    }

    /** Jump range from the exotic matter carried, and the 1 kpc limit on selectable targets. */
    private void drawRanges(GuiGraphicsExtractor g, MapView v) {
        double sx = v.screenX(px, py), sy = v.screenY(px, py);
        double rangePx = JumpTarget.rangeLy(exoticCarried()) / JumpTarget.LY_PER_PC / v.pcPerPixel();
        if (rangePx >= 3) circle(g, sx, sy, rangePx, 0xA07FE0FF);
        double selectPx = JumpTarget.MAX_SELECT_PC / v.pcPerPixel();
        if (selectPx >= 3 && selectPx < mapSize * 2) circle(g, sx, sy, selectPx, 0x608C9AB8);
    }

    private void drawCatalog(GuiGraphicsExtractor g, MapView v) {
        List<int[]> labels = new ArrayList<>();
        for (Star s : StarCatalog.all()) {
            double sx = v.screenX(s.x(), s.y()), sy = v.screenY(s.x(), s.y());
            if (!v.contains(sx, sy)) continue;
            drawStar(g, v, s, null, SetJumpTargetPayload.CATALOG, true);
            if (s == StarCatalog.SOL) circle(g, sx, sy, 4, SOL);
            // Greedy labels in catalog order (Sol, Sgr A*, then by distance): skip any that would overlap.
            int lx = (int) sx + 5, ly = (int) sy - 4, lw = font.width(s.name());
            boolean free = true;
            for (int[] r : labels) {
                if (lx < r[0] + r[2] && lx + lw > r[0] && Math.abs(ly - r[1]) < 10) {
                    free = false;
                    break;
                }
            }
            if (free) {
                labels.add(new int[] {lx, ly, lw});
                g.text(font, s.name(), lx, ly, s == StarCatalog.SOL ? SOL : TEXT, true);
            }
        }
    }

    private void drawStar(GuiGraphicsExtractor g, MapView v, Star s, SectorKey sector, int index, boolean catalog) {
        double sx = v.screenX(s.x(), s.y()), sy = v.screenY(s.x(), s.y());
        if (!v.contains(sx, sy)) return;
        int x = (int) sx, y = (int) sy;
        if (s.kind() == StarKind.BLACK_HOLE) {
            g.outline(x - 2, y - 2, 5, 5, 0xFFB388FF);
        } else {
            int r = Math.max(catalog ? 1 : 0, MapView.markerRadius(s.absoluteMagnitude()));
            int c = 0xFF000000 | Blackbody.srgbPacked(Math.max(1000, s.temperature()));
            g.fill(x - r, y - r, x + r + 1, y + r + 1, c);
        }
        hits.add(new Hit(sx, sy, s, sector, index));
    }

    private void drawTarget(GuiGraphicsExtractor g, MapView v, JumpTarget t) {
        double sx = v.screenX(t.xPc(), t.yPc()), sy = v.screenY(t.xPc(), t.yPc());
        line(g, v.screenX(px, py), v.screenY(px, py), sx, sy, 0xC0FFD54F);
        circle(g, sx, sy, 5, TARGET);
        if (v.contains(sx, sy)) {
            double d = t.distancePc(px, py, pz);
            String s = String.format("%s  %.2f ly", t.name(), JumpTarget.lightYears(d));
            g.text(font, s, (int) sx + 7, (int) sy + 3, TARGET, true);
        }
    }

    private void drawPlayer(GuiGraphicsExtractor g, MapView v) {
        int x = (int) v.screenX(px, py), y = (int) v.screenY(px, py);
        g.fill(x - 4, y, x + 5, y + 1, ACCENT);
        g.fill(x, y - 4, x + 1, y + 5, ACCENT);
    }

    private void drawScaleBar(GuiGraphicsExtractor g, MapView v) {
        double[] bar = v.scaleBar();
        int x0 = mapLeft + 6, y = mapTop + mapSize - 8, len = (int) Math.round(bar[1]);
        g.fill(x0, y, x0 + len, y + 1, TEXT);
        g.fill(x0, y - 3, x0 + 1, y + 1, TEXT);
        g.fill(x0 + len - 1, y - 3, x0 + len, y + 1, TEXT);
        g.text(font, MapView.formatPc(bar[0]), x0, y - 12, TEXT, true);
    }

    private void drawPanel(GuiGraphicsExtractor g, JumpTarget target) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.text(Component.translatable("map.galactica.view", MapView.formatPc(MapView.ZOOM_WIDTHS_PC[level])), ACCENT));
        if (havePos) {
            rows.add(Row.text(Component.translatable("map.galactica.position",
                    String.format("%.3f", Math.sqrt(px * px + py * py) / 1000), String.format("%+.1f", pz)), TEXT));
        }
        rows.add(Row.GAP);
        rows.add(Row.text(Component.translatable("map.galactica.legend"), DIM));
        for (int i = 0; i < ArmLegend.ARM_COUNT; i++) {
            rows.add(new Row(Component.translatable(ArmLegend.KEYS[i]), TEXT, ArmLegend.COLOURS[i], 11));
        }
        rows.add(Row.GAP);
        int exotic = exoticCarried();
        double range = JumpTarget.rangeLy(exotic);
        rows.add(Row.text(Component.translatable("map.galactica.exotic", exotic, String.format("%.0f", range)), DIM));
        if (target != null && havePos) {
            double d = target.distancePc(px, py, pz);
            double ly = JumpTarget.lightYears(d);
            rows.add(Row.text(Component.translatable("map.galactica.target", target.name()), TARGET));
            rows.add(Row.text(Component.translatable("map.galactica.target.distance",
                    String.format("%.2f", ly), String.format("%.2f", d)), TEXT));
            rows.add(Row.text(Component.translatable("map.galactica.target.cost", JumpTarget.exoticCost(d)), ly <= range ? TEXT : WARN));
        } else {
            rows.add(Row.text(Component.translatable("map.galactica.target.none"), DIM));
        }
        rows.add(Row.GAP);
        rows.add(Row.text(Component.translatable("map.galactica.hint.zoom"), DIM));
        rows.add(Row.text(Component.translatable("map.galactica.hint.select"), DIM));
        rows.add(Row.text(Component.translatable("map.galactica.hint.clear"), DIM));

        int h = 10;
        for (Row r : rows) h += r.height();
        int x = panelLeft, y = mapTop;
        g.fill(x, y, x + PANEL_W, y + h, PANEL);
        g.fill(x, y, x + 2, y + h, ACCENT);
        int yy = y + 6;
        for (Row r : rows) {
            if (r.text() != null) {
                int tx = x + 6;
                if (r.swatch() != 0) {
                    g.fill(tx, yy + 1, tx + 7, yy + 8, r.swatch());
                    tx += 11;
                }
                g.text(font, r.text(), tx, yy, r.colour(), false);
            }
            yy += r.height();
        }
    }

    /** One panel line: text with an optional colour swatch, or an empty spacer. */
    private record Row(Component text, int colour, int swatch, int height) {
        static final Row GAP = new Row(null, 0, 0, 4);

        static Row text(Component c, int colour) {
            return new Row(c, colour, 0, 11);
        }
    }

    private List<Component> tooltip(Star s) {
        List<Component> out = new ArrayList<>();
        int colour = s.kind() == StarKind.BLACK_HOLE ? 0xB388FF : Blackbody.srgbPacked(Math.max(1000, s.temperature()));
        out.add(Component.literal(s.name()).withColor(colour));
        out.add(Component.translatable("map.galactica.tooltip.type", s.spectralType()));
        if (havePos) {
            double d = s.distanceTo(px, py, pz);
            out.add(Component.translatable("map.galactica.tooltip.distance", String.format("%.2f", JumpTarget.lightYears(d))));
            if (d > JumpTarget.MAX_SELECT_PC) {
                out.add(Component.translatable("map.galactica.tooltip.too_far").withColor(WARN & 0xFFFFFF));
            } else {
                out.add(Component.translatable("map.galactica.tooltip.cost", JumpTarget.exoticCost(d)));
            }
        }
        return out;
    }

    private Hit hitAt(double mx, double my) {
        if (!view().contains(mx, my)) return null;
        Hit best = null;
        double bestD = HOVER_PX * HOVER_PX;
        for (Hit h : hits) {
            double d = (h.sx() - mx) * (h.sx() - mx) + (h.sy() - my) * (h.sy() - my);
            // Catalog stars win ties: they sit on top and are what a player aims at.
            if (d < bestD || (d == bestD && h.index() == SetJumpTargetPayload.CATALOG)) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (view().contains(e.x(), e.y()) && ClientPlayNetworking.canSend(SetJumpTargetPayload.TYPE)) {
            if (e.button() == 1) {
                ClientPlayNetworking.send(SetJumpTargetPayload.clear());
                return true;
            }
            Hit h = hitAt(e.x(), e.y());
            if (e.button() == 0 && h != null && havePos && h.star().distanceTo(px, py, pz) <= JumpTarget.MAX_SELECT_PC) {
                ClientPlayNetworking.send(h.index() == SetJumpTargetPayload.CATALOG
                        ? SetJumpTargetPayload.catalog(h.star().id())
                        : SetJumpTargetPayload.procedural(h.star().id(), h.sector(), h.index()));
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int next = MapView.levelAfterScroll(level, scrollY);
        if (next != level) {
            level = next;
            lastLevel = next;
            stars = List.of();
            starsKey = "";
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (GalaxyMapClient.openMap != null && GalaxyMapClient.openMap.matches(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    private int exoticCarried() {
        if (minecraft == null || minecraft.player == null || !BuiltInRegistries.ITEM.containsKey(EXOTIC_MATTER)) return 0;
        return minecraft.player.getInventory().countItem(BuiltInRegistries.ITEM.getValue(EXOTIC_MATTER));
    }

    /** A 1 px line as a rotated fill: the GUI has no line primitive, but fills honour the pose. */
    private static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int colour) {
        double dx = x1 - x0, dy = y1 - y0;
        int len = (int) Math.round(Math.sqrt(dx * dx + dy * dy));
        if (len < 1) return;
        g.pose().pushMatrix();
        g.pose().translate((float) x0, (float) y0);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.fill(0, 0, len, 1, colour);
        g.pose().popMatrix();
    }

    private static void circle(GuiGraphicsExtractor g, double cx, double cy, double r, int colour) {
        int n = (int) Math.max(16, Math.min(96, r * 0.8));
        double px = cx + r, py = cy;
        for (int i = 1; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            line(g, px, py, x, y, colour);
            px = x;
            py = y;
        }
    }
}
