package dev.galactica.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.nav.StarChartItem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Client wiring of the Galaxy Map: the open key (M), the star-chart hook and the screen. */
public final class GalaxyMapClient {
    private GalaxyMapClient() {}

    /** Own category id: {@code Category.register} throws on duplicates, and other areas add theirs. */
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Galactica.id("map"));
    public static KeyMapping openMap;

    public static void initClient() {
        openMap = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.galactica.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));
        StarChartItem.openMapHook = GalaxyMapClient::open;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openMap.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null) open();
            }
        });
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        FaceOnTexture.request();
        mc.gui.setScreen(new GalaxyMapScreen());
    }
}
