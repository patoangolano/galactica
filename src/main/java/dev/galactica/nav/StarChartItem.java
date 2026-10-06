package dev.galactica.nav;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Opens the Galaxy Map. The screen is client-only, so this common class only calls a hook the
 * client installs ({@code GalaxyMapClient.initClient}); a dedicated server never loads screen classes.
 */
public class StarChartItem extends Item {
    /** Set by the client initialiser; null on a dedicated server. */
    public static volatile Runnable openMapHook;

    public StarChartItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            Runnable hook = openMapHook;
            if (hook != null) hook.run();
        }
        return InteractionResult.SUCCESS;
    }
}
