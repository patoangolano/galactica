package dev.galactica.content;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Galactica" creative tab. Contents are resolved from the item registry when the tab is built, not
 * from class references, so items registered by the ship, map and planet areas appear without this class
 * depending on them: known ids first in a curated order, then any other {@code galactica:} item by id.
 */
public final class GalacticaTab {
    private GalacticaTab() {}

    /** Curated order. Ids from other areas are simply skipped while those areas are absent. */
    private static final List<String> ORDER = List.of(
            "ship_beacon", "star_chart", "spectrometer",
            "eva_helmet", "eva_suit", "eva_leggings", "eva_boots",
            "oxygen_tank", "hydrogen_cell", "exotic_matter");

    public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Galactica.id("main"),
            FabricCreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.galactica"))
                    .icon(GalacticaTab::icon)
                    .displayItems((params, out) -> {
                        for (Item it : contents()) out.accept(it);
                    })
                    .build());

    public static void init() {}

    static List<Item> contents() {
        Set<Item> items = new LinkedHashSet<>();
        for (String path : ORDER) {
            BuiltInRegistries.ITEM.getOptional(Galactica.id(path)).ifPresent(items::add);
        }
        List<Identifier> rest = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(Galactica.MOD_ID)) rest.add(id);
        }
        rest.sort(Identifier::compareTo);
        for (Identifier id : rest) items.add(BuiltInRegistries.ITEM.getValue(id));
        return new ArrayList<>(items);
    }

    private static ItemStack icon() {
        return BuiltInRegistries.ITEM.getOptional(Galactica.id("ship_beacon"))
                .map(ItemStack::new)
                .orElseGet(() -> new ItemStack(GalacticaItems.EVA_HELMET));
    }
}
