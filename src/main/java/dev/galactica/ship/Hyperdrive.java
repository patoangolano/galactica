package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The jump drive (spec §2): J while piloting → {@link HyperjumpPayload} → range and exotic-matter check →
 * 5 s spool (synced, cancelled if the pilot leaves the seat; J again aborts) → arrival at the target star.
 * Arrival moves the floating origin only: ship, crew and their block positions stay put while the galaxy
 * re-centres around them.
 */
public final class Hyperdrive {
    private Hyperdrive() {}

    private static HyperjumpTargetSource targetSource = HyperjumpTargetSource.NEAREST_STAR;

    /** The Galaxy Map plugs its per-player selection in here. */
    public static void setTargetSource(HyperjumpTargetSource source) {
        targetSource = Objects.requireNonNull(source);
    }

    public static HyperjumpTargetSource targetSource() {
        return targetSource;
    }

    static void request(Starship ship, ServerPlayer pilot) {
        if (pilot.level().dimension() != Galactica.SPACE) {
            pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.not_in_space"));
            return;
        }
        if (ship.isSpooling()) {
            ship.cancelSpool();
            pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.aborted"));
            return;
        }
        JumpTargetInfo t = targetSource.target(pilot);
        if (t == null) {
            pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.no_target"));
            return;
        }
        double ly = distanceLy(pilot, t);
        if (!DriveRules.canJump(ly, ship.exotic())) {
            pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.out_of_range", t.name(),
                    String.format("%.2f", ly), String.format("%.1f", DriveRules.rangeLy(ship.exotic())), DriveRules.exoticCost(ly)));
            return;
        }
        ship.beginSpool(t);
        pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.spooling", t.name(),
                String.format("%.2f", ly), DriveRules.exoticCost(ly)));
    }

    /** End of spool: re-check (fuel can be pulled out mid-spool), pay, arrive, bring the crew along. */
    static void complete(Starship ship, ServerPlayer pilot, JumpTargetInfo t) {
        double ly = distanceLy(pilot, t);
        if (!DriveRules.canJump(ly, ship.exotic())) {
            pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.aborted"));
            return;
        }
        ship.setExotic(ship.exotic() - DriveRules.exoticCost(ly));
        Star s = resolve(t);
        if (s != null) arriveAt(pilot, s);
        else arriveNear(pilot, GalacticPos.ofParsecs(t.xPc(), t.yPc(), t.zPc()), 0.05 * Units.AU);
        GalacticPos origin = pilot.getAttachedOrCreate(Galactica.NAV).origin();
        for (Entity e : ship.getPassengers()) {
            if (e instanceof ServerPlayer crew && crew != pilot) {
                NavState nav = crew.getAttachedOrCreate(Galactica.NAV);
                crew.setAttached(Galactica.NAV, nav.withOrigin(origin).withSpeed(0));
            }
        }
        pilot.removeAttached(dev.galactica.nav.JumpTargets.TYPE); // the star reached is no longer a target
        pilot.sendOverlayMessage(Component.translatable("hud.galactica.hyperjump.complete", t.name()));
    }

    public static double distanceLy(ServerPlayer p, JumpTargetInfo t) {
        GalacticPos here = Frames.playerPos(p.getAttachedOrCreate(Galactica.NAV), p.getX(), p.getY(), p.getZ());
        return here.distanceMetres(GalacticPos.ofParsecs(t.xPc(), t.yPc(), t.zPc())) / Units.LIGHT_YEAR;
    }

    /** The star at a target position (within 0.01 pc), or null for an empty point in space. */
    public static @Nullable Star resolve(JumpTargetInfo t) {
        GalacticPos pos = GalacticPos.ofParsecs(t.xPc(), t.yPc(), t.zPc());
        Star s = new Neighbourhood(Galactica.GALAXY).nearestStar(pos);
        return s != null && Neighbourhood.starPos(s).distanceMetres(pos) < 0.01 * Units.PARSEC ? s : null;
    }

    /**
     * Drops out of hyperspace where the star appears ~10° across — a safe, dramatic arrival — and never closer
     * than 0.05 AU; black holes are approached to 40 Schwarzschild radii. (Moved from GalacticaCommands.)
     */
    public static void arriveAt(ServerPlayer p, Star s) {
        double r = s.radiusMetres();
        double d = s.kind() == StarKind.BLACK_HOLE ? 40 * r : Math.max(0.05 * Units.AU, r / Math.tan(Math.toRadians(5)));
        arriveNear(p, Neighbourhood.starPos(s), d);
    }

    /** Parks the player {@code d} metres from {@code target} on the side facing where they came from, slightly above the plane. */
    public static void arriveNear(ServerPlayer p, GalacticPos target, double d) {
        GalacticPos here = Frames.playerPos(p.getAttachedOrCreate(Galactica.NAV), p.getX(), p.getY(), p.getZ());
        double[] from = here.metresFrom(target);
        double len = Math.sqrt(from[0] * from[0] + from[1] * from[1] + from[2] * from[2]);
        double ux = len > 0 ? from[0] / len : 1, uy = len > 0 ? from[1] / len : 0, uz = len > 0 ? from[2] / len : 0;
        uz += 0.3;
        double n = Math.sqrt(ux * ux + uy * uy + uz * uz);
        NavServer.moveTo(p, target.plusMetres(ux / n * d, uy / n * d, uz / n * d));
    }
}
