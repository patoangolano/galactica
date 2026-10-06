package dev.galactica.discovery;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/**
 * Address of something the spectrometer points at, rebuilt from the deterministic galaxy on both
 * sides. Only the address travels: the server never trusts client physics.
 *
 * @param starId  {@code Star.id()} of the star (or of the body's host)
 * @param index   global index of a procedural star in its sector, {@link #CATALOG} for a catalog star
 * @param planet  planet index in {@link SystemGenerator#generate} order, −1 when the target is the star
 * @param moon    moon index in {@link Body#moons()}, −1 for the planet itself
 */
public record ScanTarget(long starId, int ix, int iy, int iz, int index, int planet, int moon) {
    public static final int CATALOG = -1;

    public static ScanTarget catalogStar(long id) {
        return new ScanTarget(id, 0, 0, 0, CATALOG, -1, -1);
    }

    public static ScanTarget proceduralStar(long id, SectorKey k, int index) {
        return new ScanTarget(id, k.ix(), k.iy(), k.iz(), index, -1, -1);
    }

    public ScanTarget withBody(int planetIndex, int moonIndex) {
        return new ScanTarget(starId, ix, iy, iz, index, planetIndex, moonIndex);
    }

    public ScanTarget starOnly() {
        return new ScanTarget(starId, ix, iy, iz, index, -1, -1);
    }

    public boolean isStar() {
        return planet < 0;
    }

    public SectorKey sector() {
        return new SectorKey(ix, iy, iz);
    }

    /** Address of {@code s}, scanning its sector once for a procedural star; null if not found. */
    public static ScanTarget of(Galaxy galaxy, Star s) {
        if (s.catalog()) return catalogStar(s.id());
        SectorKey k = SectorKey.containing(s.x(), s.y(), s.z());
        int common = galaxy.commonCount(k), lum = galaxy.luminousCount(k);
        // Luminous stars are the visible ones: look there first, without generating the common stream.
        for (int j = 0; j < lum; j++) if (galaxy.luminousStar(k, j).id() == s.id()) return proceduralStar(s.id(), k, common + j);
        for (int i = 0; i < common; i++) if (galaxy.star(k, i).id() == s.id()) return proceduralStar(s.id(), k, i);
        return null;
    }

    /** The star this address names, or null if it does not hold a star with {@link #starId}. */
    public Star resolveStar(Galaxy galaxy) {
        if (index == CATALOG) {
            for (Star s : StarCatalog.all()) if (s.id() == starId) return s;
            return null;
        }
        SectorKey k = sector();
        if (index < 0 || index >= galaxy.starCount(k)) return null;
        Star s = galaxy.star(k, index);
        return s.id() == starId ? s : null;
    }

    /** The resolved target: star, and for bodies the planet and (for moons) the moon. */
    public record Resolved(Star star, Body planet, Body moon) {
        public Body body() {
            return moon != null ? moon : planet;
        }

        public boolean isStar() {
            return planet == null;
        }
    }

    public Resolved resolve(Galaxy galaxy) {
        Star s = resolveStar(galaxy);
        if (s == null) return null;
        if (planet < 0) return new Resolved(s, null, null);
        List<Body> planets = SystemGenerator.generate(s);
        if (planet >= planets.size()) return null;
        Body p = planets.get(planet);
        if (moon < 0) return new Resolved(s, p, null);
        return moon < p.moons().size() ? new Resolved(s, p, p.moons().get(moon)) : null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(starId);
        buf.writeInt(ix);
        buf.writeInt(iy);
        buf.writeInt(iz);
        buf.writeVarInt(index);
        buf.writeVarInt(planet);
        buf.writeVarInt(moon);
    }

    public static ScanTarget read(FriendlyByteBuf buf) {
        return new ScanTarget(buf.readLong(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
}
