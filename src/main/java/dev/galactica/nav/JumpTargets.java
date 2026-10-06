package dev.galactica.nav;

import dev.galactica.Galactica;
import dev.galactica.net.MapNetworking;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The player's hyperjump target (persistent, kept on death, synced to the owning client so the map
 * and HUD can draw it) and the star chart that sets it. {@link #init()} registers everything this
 * area owns: the item, the C2S payload and its server receiver.
 */
public final class JumpTargets {
    private JumpTargets() {}

    public static final AttachmentType<JumpTarget> TYPE = AttachmentRegistry.create(Galactica.id("jump_target"),
            b -> b.persistent(JumpTarget.CODEC)
                    .copyOnDeath()
                    .syncWith(JumpTarget.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

    private static final ResourceKey<Item> STAR_CHART_KEY = ResourceKey.create(Registries.ITEM, Galactica.id("star_chart"));
    public static final Item STAR_CHART = Registry.register(BuiltInRegistries.ITEM, STAR_CHART_KEY,
            new StarChartItem(new Item.Properties().stacksTo(1).setId(STAR_CHART_KEY)));

    public static void init() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(out -> out.accept(STAR_CHART));
        MapNetworking.init();
    }

    /**
     * Galactic position of a player on either side. In space it is the floating origin plus the
     * block offset; elsewhere (overworld, planet surfaces) the frame's origin, which is where the
     * player re-enters space, is the best position the nav state knows. Server side only: it
     * creates the NAV attachment when missing; the client uses {@link #knownGalacticPosition}.
     */
    public static GalacticPos galacticPosition(Player p) {
        NavState nav = p.getAttachedOrCreate(Galactica.NAV);
        return p.level().dimension() == Galactica.SPACE ? Frames.playerPos(nav, p.getX(), p.getY(), p.getZ()) : nav.origin();
    }

    /**
     * Client-safe variant of {@link #galacticPosition}: {@code null} until the NAV attachment has
     * synced, instead of creating a local {@link NavState#initial()} that would place the player at
     * a made-up position (and that the sync would then have to overwrite).
     */
    public static @Nullable GalacticPos knownGalacticPosition(Player p) {
        NavState nav = p.getAttached(Galactica.NAV);
        if (nav == null) return null;
        return p.level().dimension() == Galactica.SPACE ? Frames.playerPos(nav, p.getX(), p.getY(), p.getZ()) : nav.origin();
    }

    public static @Nullable JumpTarget get(Player p) {
        return p.getAttached(TYPE);
    }
}
