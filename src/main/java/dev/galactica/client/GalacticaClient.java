package dev.galactica.client;

import dev.galactica.Galactica;
import dev.galactica.client.hud.NavHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public final class GalacticaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        dev.galactica.client.hud.LifeSupportHud.initClient();
        dev.galactica.client.ship.ShipClient.initClient();
        dev.galactica.client.map.GalaxyMapClient.initClient();
        dev.galactica.client.planet.PlanetClient.initClient();
        dev.galactica.client.discovery.DiscoveryClient.initClient();
        dev.galactica.client.exotic.ExoticOverlay.initClient();
        dev.galactica.client.earthsky.EarthSky.initClient();
        dev.galactica.deepsky.DeepSky.initClient(dev.galactica.nav.Frames.GALAXY_SEED);
        dev.galactica.client.vfx.star.StarDiscRenderer.initClient();
        dev.galactica.client.planet.TransferClient.initClient();
        dev.galactica.client.nav.OriginShiftClient.initClient();
        dev.galactica.client.dev.AutoTest.install();
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("nav_hud"), NavHud::extract);
    }
}
