package dev.galactica.client;

import dev.galactica.Galactica;
import dev.galactica.client.hud.NavHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public final class GalacticaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        dev.galactica.client.dev.AutoTest.install();
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("nav_hud"), NavHud::extract);
    }
}
