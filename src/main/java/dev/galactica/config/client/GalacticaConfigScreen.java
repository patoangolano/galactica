package dev.galactica.config.client;

import dev.galactica.config.GalacticaConfig;
import dev.galactica.config.GalacticaConfigs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Plain vanilla config screen: one cycling button per setting, two columns, Done/Cancel. Used by
 * the Mod Menu entry when YACL is not installed, so the settings are reachable with zero extra
 * dependencies. Edits go to a {@link GalacticaConfig.Builder} and are applied on Done only.
 */
public final class GalacticaConfigScreen extends Screen {
    private static final int BUTTON_W = 200, BUTTON_H = 20, GAP = 4;
    private static final int[] PLANET_SIZES = {128, 256, 512, 1024};
    private static final double[] ENTRY_STEPS = {0.0, 0.25, 0.5, 0.75, 1.0};
    /**
     * Warp caps across the config's whole legal range (1e-9 pc/s ≈ 0.10 c up to 1000 pc/s): coarse
     * decades below 0.1 pc/s, finer steps where players usually pick.
     */
    static final double[] WARP_STEPS = {1e-9, 1e-7, 1e-5, 1e-3, 0.01, 0.1, 0.3, 1, 3, 10, 30, 100, 300, 1000};
    private static final double[] HUD_STEPS = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
    private static final double[] WRECK_STEPS = {0.0, 0.1, 0.25, 0.5, 1.0};

    private final Screen parent;
    private final GalacticaConfig.Builder draft;
    private final List<Row> rows = new ArrayList<>();

    /** One setting: label key, how to show the draft value, how to advance it. */
    private record Row(String key, Function<GalacticaConfig.Builder, String> show, java.util.function.Consumer<GalacticaConfig.Builder> next) {
    }

    public GalacticaConfigScreen(Screen parent) {
        super(Component.translatable("config.galactica.title"));
        this.parent = parent;
        this.draft = GalacticaConfigs.get().toBuilder();
        rows.add(new Row("sky_quality", b -> b.skyBakeWidth + " px",
                b -> b.skyBakeWidth = nextInt(GalacticaConfig.SKY_BAKE_WIDTHS, b.skyBakeWidth)));
        rows.add(new Row("planet_texture_max", b -> b.planetTextureMax + " px",
                b -> b.planetTextureMax = nextInt(PLANET_SIZES, b.planetTextureMax)));
        rows.add(new Row("star_plasma", b -> onOff(b.starPlasma), b -> b.starPlasma = !b.starPlasma));
        rows.add(new Row("star_plasma_quality", b -> quality(b.starPlasmaQuality.name()),
                b -> b.starPlasmaQuality = nextEnum(GalacticaConfig.Quality.values(), b.starPlasmaQuality)));
        rows.add(new Row("exhaust_quality", b -> quality(b.exhaustQuality.name()),
                b -> b.exhaustQuality = nextEnum(GalacticaConfig.ExhaustQuality.values(), b.exhaustQuality)));
        rows.add(new Row("entry_effects", b -> Math.round(b.entryEffects * 100) + "%",
                b -> b.entryEffects = nextDouble(ENTRY_STEPS, b.entryEffects)));
        rows.add(new Row("real_night_sky", b -> onOff(b.realNightSky), b -> b.realNightSky = !b.realNightSky));
        rows.add(new Row("seamless_transitions", b -> onOff(b.seamlessTransitions), b -> b.seamlessTransitions = !b.seamlessTransitions));
        rows.add(new Row("warp_max", b -> String.format(Locale.ROOT, "%.3g pc/s", b.warpMaxPcPerS),
                b -> b.warpMaxPcPerS = nextDouble(WARP_STEPS, b.warpMaxPcPerS)));
        rows.add(new Row("hud_scale", b -> String.format(Locale.ROOT, "%.2f×", b.hudScale),
                b -> b.hudScale = nextDouble(HUD_STEPS, b.hudScale)));
        rows.add(new Row("show_legend", b -> onOff(b.showLegend), b -> b.showLegend = !b.showLegend));
        rows.add(new Row("ship_destruction", b -> onOff(b.shipDestruction), b -> b.shipDestruction = !b.shipDestruction));
        rows.add(new Row("wreck_drop_share", b -> Math.round(b.wreckDropShare * 100) + "%",
                b -> b.wreckDropShare = nextDouble(WRECK_STEPS, b.wreckDropShare)));
    }

    @Override
    protected void init() {
        int perColumn = (rows.size() + 1) / 2;
        int totalW = BUTTON_W * 2 + GAP;
        int x0 = (width - totalW) / 2;
        int y0 = Math.max(32, (height - perColumn * (BUTTON_H + GAP) - BUTTON_H - 16) / 2);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int col = i / perColumn, line = i % perColumn;
            addRenderableWidget(Button.builder(label(row), btn -> {
                        row.next().accept(draft);
                        btn.setMessage(label(row));
                    })
                    .bounds(x0 + col * (BUTTON_W + GAP), y0 + line * (BUTTON_H + GAP), BUTTON_W, BUTTON_H)
                    .build());
        }
        int yDone = y0 + perColumn * (BUTTON_H + GAP) + 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            GalacticaConfigs.apply(draft.build());
            onClose();
        }).bounds(x0, yDone, BUTTON_W, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x0 + BUTTON_W + GAP, yDone, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private Component label(Row row) {
        return Component.translatable("config.galactica." + row.key())
                .append(Component.literal(": " + row.show().apply(draft)));
    }

    private static String onOff(boolean v) {
        return v ? "ON" : "OFF";
    }

    private static String quality(String name) {
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    static int nextInt(int[] steps, int v) {
        for (int s : steps) if (s > v) return s;
        return steps[0];
    }

    static double nextDouble(double[] steps, double v) {
        // Relative tolerance: the warp steps span twelve decades, so a fixed epsilon would skip 1e-9.
        for (double s : steps) if (s > v * (1 + 1e-9)) return s;
        return steps[0];
    }

    static <E extends Enum<E>> E nextEnum(E[] values, E v) {
        return values[(v.ordinal() + 1) % values.length];
    }
}
