package dev.galactica.client.map;

import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import dev.galactica.system.Body;
import org.jspecify.annotations.Nullable;

/**
 * Something the 3D map draws and lets the player pick: a star (with its galaxy address), a planet or moon (with its
 * system star's address), a deep-sky object, or the player's own craft.
 *
 * @param star     the star itself, or the system star of a body
 * @param sector   the star's sector (unused for catalog stars)
 * @param index    the star's global index in its sector, {@link NavTarget#CATALOG} for a catalog star
 * @param parent   the planet a moon orbits
 * @param pos      where it is (bodies: at the map's clock)
 * @param argb     marker colour
 * @param importance label and pick priority
 */
public record MapObject(Kind kind, String name, @Nullable Star star, @Nullable SectorKey sector, int index,
                        @Nullable Body body, @Nullable Body parent, @Nullable DeepSkyObject deepSky,
                        GalacticPos pos, int argb, double importance) {

    public enum Kind { STAR, BLACK_HOLE, PLANET, MOON, DEEP_SKY, PLAYER }

    public static MapObject star(Star s, @Nullable SectorKey sector, int index, double importance) {
        boolean bh = s.kind() == StarKind.BLACK_HOLE;
        int colour = bh ? 0xFFB388FF : 0xFF000000 | dev.galactica.science.Blackbody.srgbPacked(Math.max(1000, s.temperature()));
        return new MapObject(bh ? Kind.BLACK_HOLE : Kind.STAR, s.name(), s, sector, index, null, null, null,
                dev.galactica.nav.Neighbourhood.starPos(s), colour, importance);
    }

    public boolean isBody() {
        return kind == Kind.PLANET || kind == Kind.MOON;
    }

    /** The same physical object (stars by id, bodies by id, deep-sky by id). */
    public boolean same(@Nullable MapObject o) {
        if (o == null || o.kind != kind) return false;
        return switch (kind) {
            case STAR, BLACK_HOLE -> star != null && o.star != null && star.id() == o.star.id();
            case PLANET, MOON -> body != null && o.body != null && body.id() == o.body.id();
            case DEEP_SKY -> deepSky != null && o.deepSky != null && deepSky.id.equals(o.deepSky.id);
            case PLAYER -> true;
        };
    }

    /** The navigation target this object would lock, or null (the player marker, a star with no address). */
    public @Nullable NavTarget toTarget() {
        SectorKey k = sector != null ? sector : new SectorKey(0, 0, 0);
        return switch (kind) {
            case STAR, BLACK_HOLE -> star == null ? null : new NavTarget(NavTarget.Kind.STAR, star.name(), star.id(), k.ix(), k.iy(), k.iz(),
                    index, 0L, "", star.x(), star.y(), star.z());
            case PLANET, MOON -> star == null || body == null ? null : new NavTarget(kind == Kind.PLANET ? NavTarget.Kind.PLANET : NavTarget.Kind.MOON,
                    body.name(), star.id(), k.ix(), k.iy(), k.iz(), index, body.id(), "", star.x(), star.y(), star.z());
            case DEEP_SKY -> deepSky == null ? null : new NavTarget(NavTarget.Kind.DEEP_SKY, deepSky.name, 0L, 0, 0, 0, 0, 0L, deepSky.id,
                    deepSky.x, deepSky.y, deepSky.z);
            case PLAYER -> null;
        };
    }

    /** True when this object is the given target. */
    public boolean matches(@Nullable NavTarget t) {
        if (t == null) return false;
        return switch (kind) {
            case STAR, BLACK_HOLE -> t.kind() == NavTarget.Kind.STAR && star != null && star.id() == t.starId();
            case PLANET, MOON -> t.kind().isBody() && body != null && body.id() == t.bodyId();
            case DEEP_SKY -> t.kind() == NavTarget.Kind.DEEP_SKY && deepSky != null && deepSky.id.equals(t.deepSkyId());
            case PLAYER -> false;
        };
    }
}
