package dev.galactica.content;

import dev.galactica.Galactica;
import dev.galactica.survival.LifeSupportModel;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/** Items owned by the content area (spec §1). Other areas register their own and show up in {@link GalacticaTab}. */
public final class GalacticaItems {
    private GalacticaItems() {}

    public static final ResourceKey<EquipmentAsset> EVA_SUIT_ASSET = ResourceKey.create(EquipmentAssets.ROOT_ID, Galactica.id("eva_suit"));

    /**
     * Iron-equivalent protection (spec: "armour stats ≈ iron"): the suit is a pressure garment with a hard
     * upper torso, not plate. Same durability multiplier (15), defence and repair material as iron.
     */
    public static final ArmorMaterial EVA_MATERIAL = new ArmorMaterial(15, ironDefense(), 9,
            SoundEvents.ARMOR_EQUIP_IRON, 0.0F, 0.0F, ItemTags.REPAIRS_IRON_ARMOR, EVA_SUIT_ASSET);

    static {
        GalacticaComponents.init();
    }

    public static final Item EVA_HELMET = item("eva_helmet", Item::new,
            new Item.Properties().humanoidArmor(EVA_MATERIAL, ArmorType.HELMET));
    public static final Item EVA_SUIT = item("eva_suit", p -> new OxygenTankItem(p, LifeSupportModel.SUIT_CAPACITY),
            new Item.Properties().humanoidArmor(EVA_MATERIAL, ArmorType.CHESTPLATE));
    public static final Item EVA_LEGGINGS = item("eva_leggings", Item::new,
            new Item.Properties().humanoidArmor(EVA_MATERIAL, ArmorType.LEGGINGS));
    public static final Item EVA_BOOTS = item("eva_boots", Item::new,
            new Item.Properties().humanoidArmor(EVA_MATERIAL, ArmorType.BOOTS));

    public static final Item OXYGEN_TANK = item("oxygen_tank", p -> new OxygenTankItem(p, LifeSupportModel.TANK_CAPACITY),
            new Item.Properties().stacksTo(1));
    public static final Item HYDROGEN_CELL = item("hydrogen_cell", p -> new LoreItem(p, "hydrogen_cell"),
            new Item.Properties().stacksTo(64));
    public static final Item EXOTIC_MATTER = item("exotic_matter", p -> new LoreItem(p, "exotic_matter"),
            new Item.Properties().stacksTo(64).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));

    /** Forces registration of every item above. Call once from the main initializer. */
    public static void init() {}

    /** All four EVA pieces worn, in their own slots. */
    public static boolean wearsFullSuit(Player p) {
        return p.getItemBySlot(EquipmentSlot.HEAD).is(EVA_HELMET) && p.getItemBySlot(EquipmentSlot.CHEST).is(EVA_SUIT)
                && p.getItemBySlot(EquipmentSlot.LEGS).is(EVA_LEGGINGS) && p.getItemBySlot(EquipmentSlot.FEET).is(EVA_BOOTS);
    }

    /** The worn suit torso, or {@link ItemStack#EMPTY}. */
    public static ItemStack wornSuit(Player p) {
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(EVA_SUIT) ? chest : ItemStack.EMPTY;
    }

    private static Map<ArmorType, Integer> ironDefense() {
        Map<ArmorType, Integer> m = new EnumMap<>(ArmorType.class);
        m.put(ArmorType.BOOTS, 2);
        m.put(ArmorType.LEGGINGS, 5);
        m.put(ArmorType.CHESTPLATE, 6);
        m.put(ArmorType.HELMET, 2);
        m.put(ArmorType.BODY, 5);
        return m;
    }

    private static Item item(String name, Function<Item.Properties, Item> factory, Item.Properties props) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Galactica.id(name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
    }
}
