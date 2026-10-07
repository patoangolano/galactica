package dev.galactica.nav.target;

import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.net.JumpTargetRules;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import org.jspecify.annotations.Nullable;

/**
 * Server-side validation of a requested navigation target, pure so it is tested without a server. The client sends
 * only an address; the server re-derives the object from its own deterministic galaxy, names it, and refuses anything
 * the address does not hold. Every check is bounded: a forged sector costs one Poisson draw, a forged body one system
 * generation (cached), a forged deep-sky id one list scan.
 */
public final class NavTargetRules {
    private NavTargetRules() {}

    /** Targets may be anywhere in the disk and halo; beyond this a sector is refused before anything is generated. */
    public static final double MAX_RADIUS_PC = 60_000.0;

    public static @Nullable NavTarget validate(Galaxy galaxy, NavTarget.Kind kind, long starId, SectorKey sector, int index,
                                               long bodyId, String deepSkyId) {
        if (kind == NavTarget.Kind.DEEP_SKY) {
            DeepSkyObject o = TargetGeometry.deepSky(galaxy.seed(), deepSkyId);
            if (o == null) return null;
            return new NavTarget(kind, o.name, 0L, 0, 0, 0, 0, 0L, o.id, o.x, o.y, o.z);
        }
        if (index != NavTarget.CATALOG && (Math.abs(sector.centerX()) > MAX_RADIUS_PC || Math.abs(sector.centerY()) > MAX_RADIUS_PC
                || Math.abs(sector.centerZ()) > MAX_RADIUS_PC)) return null;
        Star s = JumpTargetRules.resolve(galaxy, starId, sector, index);
        if (s == null) return null;
        SectorKey k = index == NavTarget.CATALOG ? new SectorKey(0, 0, 0) : sector;
        if (kind == NavTarget.Kind.STAR) {
            return new NavTarget(kind, s.name(), s.id(), k.ix(), k.iy(), k.iz(), index, 0L, "", s.x(), s.y(), s.z());
        }
        for (Body planet : TargetGeometry.system(s)) {
            if (kind == NavTarget.Kind.PLANET && planet.id() == bodyId) {
                return new NavTarget(kind, planet.name(), s.id(), k.ix(), k.iy(), k.iz(), index, bodyId, "", s.x(), s.y(), s.z());
            }
            if (kind == NavTarget.Kind.MOON) {
                for (Body moon : planet.moons()) {
                    if (moon.id() == bodyId) {
                        return new NavTarget(kind, moon.name(), s.id(), k.ix(), k.iy(), k.iz(), index, bodyId, "", s.x(), s.y(), s.z());
                    }
                }
            }
        }
        return null;
    }
}
