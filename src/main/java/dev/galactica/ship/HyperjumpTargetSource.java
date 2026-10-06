package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.star.Star;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Where the hyperdrive points. The Galaxy Map owns the real choice (a per-player attachment); until it is
 * plugged in through {@link Hyperdrive#setTargetSource}, the drive aims at the nearest other star — the same
 * star {@code /galactica jump 1} would pick.
 */
@FunctionalInterface
public interface HyperjumpTargetSource {
    /** The selected destination, or null when the player has none. */
    @Nullable JumpTargetInfo target(ServerPlayer p);

    HyperjumpTargetSource NEAREST_STAR = p -> {
        GalacticPos here = Frames.playerPos(p.getAttachedOrCreate(Galactica.NAV), p.getX(), p.getY(), p.getZ());
        double x = here.xPc(), y = here.yPc(), z = here.zPc();
        Star best = null;
        double bestD = Double.MAX_VALUE;
        for (Star s : NavServer.neighbourhood(p).nearbyStars(here)) {
            // Skip the star we are parked at (inside 1e9 m), and Sgr A*, which is its own region.
            if (Neighbourhood.starPos(s).distanceMetres(here) <= 1e9) continue;
            if (s.kind() == dev.galactica.star.StarKind.BLACK_HOLE && s.mass() > 1e5) continue;
            double d = s.distanceTo(x, y, z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) return null;
        GalacticPos sp = Neighbourhood.starPos(best);
        return new JumpTargetInfo(best.name(), sp.xPc(), sp.yPc(), sp.zPc());
    };
}
