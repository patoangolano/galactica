package dev.galactica.client.planet;

import dev.galactica.Galactica;
import dev.galactica.planet.PlanetGravity;
import dev.galactica.system.Body;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;

/** Client wiring of the planet surfaces area. Call {@link #initClient()} from onInitializeClient. */
public final class PlanetClient {
    private PlanetClient() {}

    public static void initClient() {
        // Client-side physics of items, arrows and vehicles must fall like the server's, or they rubber-band.
        PlanetGravity.client = level -> {
            if (level != Minecraft.getInstance().level) return 1.0;
            Body body = SurfaceSkyOverlay.currentBody();
            return body != null ? PlanetGravity.scale(body) : 1.0;
        };
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Galactica.id("entry_heating"), EntryHeatingHud::extract);
    }
}
