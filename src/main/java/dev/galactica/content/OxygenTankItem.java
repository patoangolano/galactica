package dev.galactica.content;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * An item that stores {@link GalacticaComponents#OXYGEN}: the oxygen tank, and the EVA suit torso with its
 * built-in buffer. Shows the fill as a durability-style bar and as "mm:ss" in the tooltip.
 */
public class OxygenTankItem extends Item {
    private final int capacity;

    public OxygenTankItem(Properties props, int capacity) {
        super(props.component(GalacticaComponents.OXYGEN, capacity));
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public static int oxygen(ItemStack stack) {
        return stack.getOrDefault(GalacticaComponents.OXYGEN, 0);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return stack.isDamaged() || oxygen(stack) < capacity;
    }

    // A damaged suit shows wear first: a breach matters more than how much air is left.
    @Override
    public int getBarWidth(ItemStack stack) {
        if (stack.isDamaged()) return super.getBarWidth(stack);
        return Mth.clamp(Math.round(13.0F * oxygen(stack) / capacity), 0, 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return stack.isDamaged() ? super.getBarColor(stack) : 0x4FC3FF;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        int s = oxygen(stack);
        out.accept(Component.translatable("tooltip.galactica.oxygen", clock(s), clock(capacity)).withStyle(ChatFormatting.AQUA));
        if (this == GalacticaItems.EVA_SUIT) {
            out.accept(Component.translatable("tooltip.galactica.eva_suit").withStyle(ChatFormatting.GRAY));
        }
    }

    public static String clock(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
