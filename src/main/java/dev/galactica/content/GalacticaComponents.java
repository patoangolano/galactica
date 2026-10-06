package dev.galactica.content;

import dev.galactica.Galactica;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;

/** Item data components (spec §1). */
public final class GalacticaComponents {
    private GalacticaComponents() {}

    /**
     * Breathable oxygen held by a tank or a suit, in seconds of breathing. Synced so the HUD and tooltips can
     * read the equipped suit; life support writes it at most once per second.
     */
    public static final DataComponentType<Integer> OXYGEN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Galactica.id("oxygen"),
            DataComponentType.<Integer>builder().persistent(ExtraCodecs.NON_NEGATIVE_INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** Forces class initialisation; must run before {@link GalacticaItems#init()}. */
    public static void init() {}
}
