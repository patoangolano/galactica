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
        // After ShipClient: its tick hands the pilot keys to the ship first, the autopilot then overrides them.
        dev.galactica.client.nav.AutopilotClient.initClient();
        dev.galactica.client.planet.PlanetClient.initClient();
        dev.galactica.client.discovery.DiscoveryClient.initClient();
        dev.galactica.client.exotic.ExoticOverlay.initClient();
        dev.galactica.client.sky.SkyTextures.initClient();
        dev.galactica.client.earthsky.EarthSky.initClient();
        dev.galactica.client.eva.SpaceLighting.initClient();
        dev.galactica.deepsky.DeepSky.initClient(dev.galactica.nav.Frames.GALAXY_SEED);
        dev.galactica.client.vfx.star.StarDiscRenderer.initClient();
        dev.galactica.client.planet.TransferClient.initClient();
        dev.galactica.client.nav.OriginShiftClient.initClient();
        if (dev.galactica.compat.OptionalMods.loaded(dev.galactica.compat.OptionalMods.ENTITY_CULLING)) {
            dev.galactica.compat.entityculling.EntityCullingCompat.install();
        }
        StringBuilder perf = new StringBuilder();
        for (String id : dev.galactica.compat.OptionalMods.PERFORMANCE) {
            net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id).ifPresent(m ->
                    perf.append(' ').append(id).append(' ').append(m.getMetadata().getVersion().getFriendlyString()));
        }
        if (!perf.isEmpty()) Galactica.LOG.info("Performance mods present:{}", perf);
        dev.galactica.client.dev.AutoTest.install();
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("nav_hud"), NavHud::extract);
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Galactica.id("target_hud"), dev.galactica.client.hud.TargetHud::extract);
    }
}
