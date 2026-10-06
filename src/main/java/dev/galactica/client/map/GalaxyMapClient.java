package dev.galactica.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.nav.StarChartItem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Client wiring of the Galaxy Map: the open key (M), the star-chart hook and the screen. */
public final class GalaxyMapClient {
    private GalaxyMapClient() {}

    /**
     * The shared {@code galactica:main} category (api_m3_items.md §6). {@code Category.register}
     * throws when another area registered it first; {@code Category} is a record compared by id,
     * so an equal instance then sorts and groups with the registered one.
     */
    public static final KeyMapping.Category CATEGORY = sharedCategory();
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

    private static KeyMapping.Category sharedCategory() {
        Identifier id = Galactica.id("main");
        try {
            return KeyMapping.Category.register(id);
        } catch (IllegalArgumentException alreadyRegistered) {
            return new KeyMapping.Category(id);
        }
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        FaceOnTexture.request();
        mc.gui.setScreen(new GalaxyMapScreen());
    }
}
