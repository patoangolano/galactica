package dev.galactica.compat.yacl;

import dev.galactica.config.GalacticaConfig;
import dev.galactica.config.GalacticaConfigs;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * YACL 3.9.7 config screen (API read from the 26.3 jar with javap). Loaded only when
 * {@code yet_another_config_lib_v3} is present. Options bind to a {@link GalacticaConfig.Builder}
 * draft; YACL's Save button builds and applies it through {@link GalacticaConfigs#apply}, which
 * validates, writes the file and fires change listeners.
 */
public final class GalacticaYaclScreen {
    private GalacticaYaclScreen() {
    }

    public static Screen create(Screen parent) {
        GalacticaConfig.Builder draft = GalacticaConfigs.get().toBuilder();
        GalacticaConfig.Builder def = GalacticaConfig.defaults().toBuilder();

        ConfigCategory graphics = ConfigCategory.createBuilder()
                .name(Component.translatable("config.galactica.category.graphics"))
                .option(Option.<Integer>createBuilder()
                        .name(name("sky_quality"))
                        .description(desc("sky_quality"))
                        // Slider over log2 of the width (10..12 → 1024..4096) so it never stops on an illegal 3072.
                        .binding(log2(def.skyBakeWidth), () -> log2(draft.skyBakeWidth), v -> draft.skyBakeWidth = 1 << v)
                        .controller(o -> IntegerSliderControllerBuilder.create(o).range(10, 12).step(1)
                                .formatValue(v -> Component.literal((1 << v) + " × " + (1 << (v - 1)) + " px")))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(name("planet_texture_max"))
                        .description(desc("planet_texture_max"))
                        // Same trick: 7..10 → 128..1024 px.
                        .binding(log2(def.planetTextureMax), () -> log2(draft.planetTextureMax), v -> draft.planetTextureMax = 1 << v)
                        .controller(o -> IntegerSliderControllerBuilder.create(o).range(7, 10).step(1)
                                .formatValue(v -> Component.literal((1 << v) + " px")))
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(name("star_plasma"))
                        .description(desc("star_plasma"))
                        .binding(def.starPlasma, () -> draft.starPlasma, v -> draft.starPlasma = v)
                        .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                        .build())
                .option(Option.<GalacticaConfig.Quality>createBuilder()
                        .name(name("star_plasma_quality"))
                        .description(desc("star_plasma_quality"))
                        .binding(def.starPlasmaQuality, () -> draft.starPlasmaQuality, v -> draft.starPlasmaQuality = v)
                        .controller(o -> EnumControllerBuilder.create(o).enumClass(GalacticaConfig.Quality.class)
                                .formatValue(v -> enumName(v.name())))
                        .build())
                .option(Option.<GalacticaConfig.ExhaustQuality>createBuilder()
                        .name(name("exhaust_quality"))
                        .description(desc("exhaust_quality"))
                        .binding(def.exhaustQuality, () -> draft.exhaustQuality, v -> draft.exhaustQuality = v)
                        .controller(o -> EnumControllerBuilder.create(o).enumClass(GalacticaConfig.ExhaustQuality.class)
                                .formatValue(v -> enumName(v.name())))
                        .build())
                .option(Option.<Double>createBuilder()
                        .name(name("entry_effects"))
                        .description(desc("entry_effects"))
                        .binding(def.entryEffects, () -> draft.entryEffects, v -> draft.entryEffects = v)
                        .controller(o -> DoubleSliderControllerBuilder.create(o).range(0.0, 1.0).step(0.05)
                                .formatValue(v -> Component.literal(Math.round(v * 100) + "%")))
                        .build())
                .build();

        ConfigCategory gameplay = ConfigCategory.createBuilder()
                .name(Component.translatable("config.galactica.category.gameplay"))
                .option(Option.<Boolean>createBuilder()
                        .name(name("real_night_sky"))
                        .description(desc("real_night_sky"))
                        .binding(def.realNightSky, () -> draft.realNightSky, v -> draft.realNightSky = v)
                        .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(name("seamless_transitions"))
                        .description(desc("seamless_transitions"))
                        .binding(def.seamlessTransitions, () -> draft.seamlessTransitions, v -> draft.seamlessTransitions = v)
                        .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                        .build())
                .option(Option.<Double>createBuilder()
                        .name(name("warp_max"))
                        .description(desc("warp_max"))
                        // Logarithmic slider over the config's whole legal range (log10 pc/s from -9 to 3),
                        // so any value the file or /galactica maxwarp allows round-trips through the screen.
                        .binding(Math.log10(def.warpMaxPcPerS),
                                () -> Math.log10(Math.max(GalacticaConfig.WARP_MIN_PC_PER_S, draft.warpMaxPcPerS)),
                                v -> draft.warpMaxPcPerS = Math.pow(10, v))
                        .controller(o -> DoubleSliderControllerBuilder.create(o)
                                .range(Math.log10(GalacticaConfig.WARP_MIN_PC_PER_S), Math.log10(GalacticaConfig.WARP_MAX_PC_PER_S))
                                .step(0.05)
                                .formatValue(v -> Component.literal(String.format(Locale.ROOT, "%.3g pc/s", Math.pow(10, v)))))
                        .build())
                .build();

        ConfigCategory hud = ConfigCategory.createBuilder()
                .name(Component.translatable("config.galactica.category.hud"))
                .option(Option.<Double>createBuilder()
                        .name(name("hud_scale"))
                        .description(desc("hud_scale"))
                        .binding(def.hudScale, () -> draft.hudScale, v -> draft.hudScale = v)
                        .controller(o -> DoubleSliderControllerBuilder.create(o).range(0.5, 2.0).step(0.05)
                                .formatValue(v -> Component.literal(String.format(Locale.ROOT, "%.2f×", v))))
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(name("show_legend"))
                        .description(desc("show_legend"))
                        .binding(def.showLegend, () -> draft.showLegend, v -> draft.showLegend = v)
                        .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                        .build())
                .option(Option.<Boolean>createBuilder()
                        .name(name("ship_destruction"))
                        .description(desc("ship_destruction"))
                        .binding(def.shipDestruction, () -> draft.shipDestruction, v -> draft.shipDestruction = v)
                        .controller(o -> BooleanControllerBuilder.create(o).onOffFormatter().coloured(true))
                        .build())
                .option(Option.<Double>createBuilder()
                        .name(name("wreck_drop_share"))
                        .description(desc("wreck_drop_share"))
                        .binding(def.wreckDropShare, () -> draft.wreckDropShare, v -> draft.wreckDropShare = v)
                        .controller(o -> DoubleSliderControllerBuilder.create(o).range(0.0, 1.0).step(0.05)
                                .formatValue(v -> Component.literal(Math.round(v * 100) + "%")))
                        .build())
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("config.galactica.title"))
                .category(graphics)
                .category(gameplay)
                .category(hud)
                .save(() -> GalacticaConfigs.apply(draft.build()))
                .build()
                .generateScreen(parent);
    }

    private static Component name(String key) {
        return Component.translatable("config.galactica." + key);
    }

    private static OptionDescription desc(String key) {
        return OptionDescription.of(Component.translatable("config.galactica." + key + ".desc"));
    }

    private static int log2(int powerOfTwo) {
        return 31 - Integer.numberOfLeadingZeros(powerOfTwo);
    }

    private static Component enumName(String n) {
        return Component.literal(n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT));
    }
}
