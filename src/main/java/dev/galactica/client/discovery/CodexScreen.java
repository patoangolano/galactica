package dev.galactica.client.discovery;

import dev.galactica.discovery.DiscoveryLedger;
import dev.galactica.discovery.SurveyValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Discovery Codex (key K): every discovery on the server, newest first or by value or name,
 * optionally only your own, with the selected entry's record on the right and your survey account
 * at the bottom.
 */
public class CodexScreen extends Screen {
    private static final int TEXT = 0xFFD8E4FF, DIM = 0xFF8C9AB8, ACCENT = 0xFF7FE0FF, GOLD = 0xFFFFD54F,
            MINE = 0xFF8CFF9E, PANEL = 0xC0000814, SELECTED = 0x553A6EA5, HOVER = 0x22FFFFFF;
    private static final int MARGIN = 8, ROW = 12, HEADER = 24, FOOTER = 34;

    private enum Sort { TIME, POINTS, NAME }

    private static Sort sort = Sort.TIME;
    private static boolean mineOnly;

    private List<DiscoveryLedger.Entry> shown = List.of();
    private int builtRevision = -1;
    private String selected;
    private int scroll;
    private int listLeft, listTop, listW, listH, panelLeft, panelW;

    public CodexScreen() {
        super(Component.translatable("screen.galactica.codex"));
    }

    @Override
    protected void init() {
        listLeft = MARGIN;
        listTop = MARGIN + HEADER;
        panelW = Math.min(220, Math.max(150, width / 3));
        panelLeft = width - panelW - MARGIN;
        listW = panelLeft - listLeft - MARGIN;
        listH = height - listTop - FOOTER;
        addRenderableWidget(Button.builder(sortLabel(), b -> {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            b.setMessage(sortLabel());
            builtRevision = -1;
        }).bounds(listLeft + listW - 190, MARGIN, 90, 18).build());
        addRenderableWidget(Button.builder(mineLabel(), b -> {
            mineOnly = !mineOnly;
            b.setMessage(mineLabel());
            builtRevision = -1;
            scroll = 0;
        }).bounds(listLeft + listW - 96, MARGIN, 96, 18).build());
    }

    private static Component sortLabel() {
        return Component.translatable("galactica.codex.sort." + sort.name().toLowerCase(Locale.ROOT));
    }

    private static Component mineLabel() {
        return Component.translatable(mineOnly ? "galactica.codex.mine" : "galactica.codex.all");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuild() {
        if (builtRevision == ClientCodex.revision()) return;
        builtRevision = ClientCodex.revision();
        UUID me = me();
        List<DiscoveryLedger.Entry> list = new ArrayList<>();
        for (DiscoveryLedger.Entry e : ClientCodex.entries()) if (!mineOnly || e.discoverer().equals(me)) list.add(e);
        Comparator<DiscoveryLedger.Entry> c = switch (sort) {
            case TIME -> Comparator.comparingLong(DiscoveryLedger.Entry::gameTime).reversed();
            case POINTS -> Comparator.comparingInt(DiscoveryLedger.Entry::points).reversed();
            case NAME -> Comparator.comparing((DiscoveryLedger.Entry e) -> e.displayName().toLowerCase(Locale.ROOT));
        };
        list.sort(c.thenComparing(DiscoveryLedger.Entry::key));
        shown = list;
        if (selected == null && !list.isEmpty()) selected = list.get(0).key();
    }

    private static UUID me() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getUUID() : new UUID(0, 0);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0000208);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rebuild();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, title, MARGIN, MARGIN + 5, ACCENT, false);
        g.text(font, Component.translatable("galactica.codex.count", shown.size()), MARGIN + font.width(title) + 10, MARGIN + 5, DIM, false);

        g.fill(listLeft, listTop, listLeft + listW, listTop + listH, 0x80000000);
        g.outline(listLeft - 1, listTop - 1, listW + 2, listH + 2, 0xFF2A3A5A);
        if (shown.isEmpty()) {
            g.centeredText(font, Component.translatable("galactica.codex.empty"), listLeft + listW / 2, listTop + listH / 2, DIM);
        }
        UUID me = me();
        g.enableScissor(listLeft, listTop, listLeft + listW, listTop + listH);
        int y = listTop + 2 - scroll;
        for (DiscoveryLedger.Entry e : shown) {
            if (y + ROW >= listTop && y <= listTop + listH) {
                boolean hover = mouseX >= listLeft && mouseX < listLeft + listW && mouseY >= y && mouseY < y + ROW;
                if (e.key().equals(selected)) g.fill(listLeft, y, listLeft + listW, y + ROW, SELECTED);
                else if (hover) g.fill(listLeft, y, listLeft + listW, y + ROW, HOVER);
                g.fill(listLeft + 3, y + 3, listLeft + 8, y + 8, kindColour(e.kind()));
                int nameColour = e.discoverer().equals(me) ? MINE : TEXT;
                g.text(font, ellipsize(e.displayName(), listW / 2 - 14), listLeft + 12, y + 2, nameColour, false);
                g.text(font, ellipsize(e.detail(), listW / 3), listLeft + listW / 2, y + 2, DIM, false);
                String pts = Integer.toString(e.points());
                g.text(font, pts, listLeft + listW - font.width(pts) - 4, y + 2, e.characterized() ? GOLD : DIM, false);
            }
            y += ROW;
        }
        g.disableScissor();

        drawPanel(g);
        int fy = height - FOOTER + 6;
        g.text(font, Component.translatable("galactica.codex.balance", ClientCodex.balance(), ClientCodex.lifetime(),
                SurveyValue.POINTS_PER_EXOTIC), MARGIN, fy, TEXT, false);
        g.text(font, Component.translatable("galactica.codex.hint"), MARGIN, fy + 12, DIM, false);
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        int top = listTop, h = listH;
        g.fill(panelLeft, top, panelLeft + panelW, top + h, PANEL);
        g.fill(panelLeft, top, panelLeft + 2, top + h, ACCENT);
        DiscoveryLedger.Entry e = selected == null ? null : ClientCodex.get(selected);
        if (e == null) return;
        int x = panelLeft + 8, y = top + 6;
        g.enableScissor(panelLeft, top, panelLeft + panelW, top + h);
        y = wrap(g, e.displayName(), x, y, ACCENT);
        if (!e.customName().isEmpty()) y = line(g, Component.translatable("galactica.codex.designation", e.label()), x, y, DIM);
        y = line(g, Component.translatable("galactica.codex.kind." + kindKey(e.kind())), x, y, TEXT);
        if (!e.detail().isEmpty()) y = wrap(g, e.detail(), x, y, TEXT);
        if (!e.host().isEmpty()) y = line(g, Component.translatable("galactica.codex.host", e.host()), x, y, TEXT);
        y += 4;
        y = line(g, Component.translatable("galactica.codex.by", e.discovererName(), e.gameTime() / 24000L), x, y, TEXT);
        y = line(g, Component.translatable("galactica.codex.method", Component.translatable("galactica.codex.method." + e.method().replace('+', '_'))), x, y, TEXT);
        y = line(g, Component.translatable("galactica.codex.points", e.points()), x, y, GOLD);
        if (!e.characterized()) y = line(g, Component.translatable("galactica.codex.surveyed"), x, y, DIM);
        g.disableScissor();
    }

    private int line(GuiGraphicsExtractor g, Component c, int x, int y, int colour) {
        g.text(font, c, x, y, colour, false);
        return y + ROW;
    }

    private int wrap(GuiGraphicsExtractor g, String s, int x, int y, int colour) {
        return line(g, Component.literal(ellipsize(s, panelW - 14)), x, y, colour);
    }

    private String ellipsize(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + dots) > maxW) end--;
        return s.substring(0, end) + dots;
    }

    private static int kindColour(int kind) {
        return switch (kind) {
            case DiscoveryLedger.KIND_STAR -> 0xFFFFE9A0;
            case DiscoveryLedger.KIND_PLANET -> 0xFF7FB8FF;
            default -> 0xFFB0B0B0;
        };
    }

    private static String kindKey(int kind) {
        return switch (kind) {
            case DiscoveryLedger.KIND_STAR -> "star";
            case DiscoveryLedger.KIND_PLANET -> "planet";
            default -> "moon";
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0 && e.x() >= listLeft && e.x() < listLeft + listW && e.y() >= listTop && e.y() < listTop + listH) {
            int i = (int) ((e.y() - listTop - 2 + scroll) / ROW);
            if (i >= 0 && i < shown.size()) {
                selected = shown.get(i).key();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, shown.size() * ROW + 4 - listH);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(scrollY * ROW * 3)));
        return true;
    }
}
