package dev.galactica.client.discovery;

import com.mojang.blaze3d.platform.InputConstants;
import dev.galactica.Galactica;
import dev.galactica.discovery.DiscoveryPayloads;
import dev.galactica.discovery.SpectrometerItem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client wiring of the science loop: the spectrometer's target picker, the scan-result screen,
 * the codex sync and the codex key (K, category {@code galactica:main}).
 */
public final class DiscoveryClient {
    private DiscoveryClient() {}

    public static final KeyMapping.Category CATEGORY = sharedCategory();
    public static KeyMapping openCodex;

    public static void initClient() {
        openCodex = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.galactica.codex", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, CATEGORY));
        SpectrometerItem.scanHook = TargetFinder::scan;
        ClientPlayNetworking.registerGlobalReceiver(DiscoveryPayloads.ScanResult.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            Minecraft mc = ctx.client();
            if (!payload.accepted()) {
                if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable(payload.refusal()));
                return;
            }
            mc.gui.setScreen(new ScanResultScreen(payload));
        }));
        ClientPlayNetworking.registerGlobalReceiver(DiscoveryPayloads.Codex.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ClientCodex.apply(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(ClientCodex::clear));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openCodex.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new CodexScreen());
            }
        });
    }

    /** Same shared-category pattern as {@code GalaxyMapClient}: whoever registers first wins, the rest reuse the id. */
    private static KeyMapping.Category sharedCategory() {
        Identifier id = Galactica.id("main");
        try {
            return KeyMapping.Category.register(id);
        } catch (IllegalArgumentException alreadyRegistered) {
            return new KeyMapping.Category(id);
        }
    }
}
