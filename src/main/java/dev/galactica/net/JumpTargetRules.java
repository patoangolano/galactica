package dev.galactica.net;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.JumpTarget;
import dev.galactica.star.Star;

/**
 * Server-side validation of a requested jump target, pure so it can be tested without a server.
 * A request names a star by id plus address; it is accepted only if that address really holds a
 * star with that id in the deterministic galaxy, and the star is within
 * {@link JumpTarget#MAX_SELECT_PC} of the player. Every check is O(1): a forged sector costs the
 * server one Poisson draw, never a scan.
 */
public final class JumpTargetRules {
    private JumpTargetRules() {}

    /**
     * The star a request names, or null if the address does not hold that star. {@code index} is
     * {@link SetJumpTargetPayload#CATALOG} for catalog stars, otherwise the global sector index.
     */
    public static Star resolve(Galaxy galaxy, long starId, SectorKey sector, int index) {
        if (index == SetJumpTargetPayload.CATALOG) {
            for (Star s : StarCatalog.all()) if (s.id() == starId) return s;
            return null;
        }
        if (index < 0 || index >= galaxy.starCount(sector)) return null;
        Star s = galaxy.star(sector, index);
        return s.id() == starId ? s : null;
    }

    /** The target to store for a player at galactocentric (px, py, pz) pc, or null if refused. */
    public static JumpTarget validate(Galaxy galaxy, long starId, SectorKey sector, int index,
                                      double px, double py, double pz) {
        if (index != SetJumpTargetPayload.CATALOG) {
            // Reject far sectors before generating anything in them.
            double reach = JumpTarget.MAX_SELECT_PC + Galaxy.SECTOR_SIZE_PC;
            if (Math.abs(sector.centerX() - px) > reach || Math.abs(sector.centerY() - py) > reach
                    || Math.abs(sector.centerZ() - pz) > reach) return null;
        }
        Star s = resolve(galaxy, starId, sector, index);
        if (s == null || s.distanceTo(px, py, pz) > JumpTarget.MAX_SELECT_PC) return null;
        return new JumpTarget(s.name(), s.x(), s.y(), s.z());
    }
}
