package dev.galactica;

import dev.galactica.command.GalacticaCommands;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.nav.Frames;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.ReturnPoint;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Galactica implements ModInitializer {
    public static final String MOD_ID = "galactica";
    public static final Logger LOG = LoggerFactory.getLogger("Galactica");

    public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, id("space"));

    /** The one Milky Way, shared by server logic and (separately instantiated) client rendering. */
    public static final Galaxy GALAXY = new Galaxy(Frames.GALAXY_SEED);

    public static final AttachmentType<NavState> NAV = AttachmentRegistry.create(id("nav"),
            b -> b.persistent(NavState.CODEC)
                    .copyOnDeath()
                    .initializer(NavState::initial)
                    .syncWith(NavState.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

    public static final AttachmentType<ReturnPoint> RETURN_POINT = AttachmentRegistry.create(id("return_point"),
            b -> b.persistent(ReturnPoint.CODEC).copyOnDeath());

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        // Content first: the ship and planet areas look items up by id, and the tab lists everyone's items.
        dev.galactica.content.GalacticaComponents.init();
        dev.galactica.content.GalacticaItems.init();
        dev.galactica.discovery.Discovery.init();
        dev.galactica.ship.ShipEntities.init();
        dev.galactica.nav.JumpTargets.init();
        dev.galactica.content.GalacticaTab.init();
        dev.galactica.survival.LifeSupport.init();
        dev.galactica.planet.PlanetEntry.init();
        dev.galactica.nav.FloatingOrigin.init();

        // The galaxy map's chosen star feeds the hyperdrive; without one it jumps to the nearest star.
        dev.galactica.ship.Hyperdrive.setTargetSource(p -> {
            var t = p.getAttached(dev.galactica.nav.JumpTargets.TYPE);
            return t != null ? new dev.galactica.ship.JumpTargetInfo(t.name(), t.xPc(), t.yPc(), t.zPc())
                    : dev.galactica.ship.HyperjumpTargetSource.NEAREST_STAR.target(p);
        });
        // Planet surfaces answer for their own air, pressure and ground temperature.
        dev.galactica.survival.SurvivalEnvironment.provider = p -> {
            var e = dev.galactica.planet.PlanetEntry.environmentOf(p);
            if (e == null || p.level().dimension() == SPACE) return null;
            return new dev.galactica.survival.Environment(e.pressureBar(), e.tempK(), e.breathable());
        };
        // Ships feel the real surface gravity of the planet they are on (space handles its own).
        dev.galactica.ship.Starship.setGravityModel(level -> 9.80665 * dev.galactica.planet.PlanetGravity.scale(level));

        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> GalacticaCommands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(NavServer::tick);
        LOG.info("Galactica: {} pc of procedural Milky Way ready (seed {})", 60_000, Long.toHexString(Frames.GALAXY_SEED));
    }
}
