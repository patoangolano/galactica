package dev.galactica.discovery;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The spectrometer: right-click to take a spectrum of whatever is under the crosshair. Picking the
 * target needs the client's sky, so the common item only calls a hook the client installs
 * ({@code DiscoveryClient.initClient}); the server learns the target through a scan packet and
 * checks it there.
 */
public class SpectrometerItem extends Item {
    /** Set by the client initialiser; null on a dedicated server. */
    public static volatile Runnable scanHook;

    public SpectrometerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            Runnable hook = scanHook;
            if (hook != null) hook.run();
        }
        return InteractionResult.SUCCESS;
    }
}
