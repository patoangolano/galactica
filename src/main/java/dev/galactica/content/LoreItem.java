package dev.galactica.content;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** A plain item with one grey explanatory tooltip line, {@code tooltip.galactica.<path>}. */
public class LoreItem extends Item {
    private final String tooltipKey;

    public LoreItem(Properties props, String path) {
        super(props);
        this.tooltipKey = "tooltip.galactica." + path;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable(tooltipKey).withStyle(ChatFormatting.GRAY));
    }
}
