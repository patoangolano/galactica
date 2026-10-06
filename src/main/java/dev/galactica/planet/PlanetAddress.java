package dev.galactica.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemGenerator;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * Where a body lives, in terms the deterministic galaxy can rebuild without storing anything: the
 * host star (a catalog name, or sector + index of a procedural star), the planet's index in
 * {@link SystemGenerator#generate} order and the moon's index in {@link Body#moons()} (−1 for the
 * planet itself). Two worlds with the same seed resolve the same address to the same body.
 *
 * @param catalog   catalog star name, or "" for a procedural star
 * @param starIndex index in {@link Galaxy#star(SectorKey, int)} order (ignored for catalog stars)
 */
public record PlanetAddress(String catalog, int sx, int sy, int sz, int starIndex, int planet, int moon) {

    public static final Codec<PlanetAddress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("catalog", "").forGetter(PlanetAddress::catalog),
            Codec.INT.optionalFieldOf("sx", 0).forGetter(PlanetAddress::sx),
            Codec.INT.optionalFieldOf("sy", 0).forGetter(PlanetAddress::sy),
            Codec.INT.optionalFieldOf("sz", 0).forGetter(PlanetAddress::sz),
            Codec.INT.optionalFieldOf("star", 0).forGetter(PlanetAddress::starIndex),
            Codec.INT.fieldOf("planet").forGetter(PlanetAddress::planet),
            Codec.INT.optionalFieldOf("moon", -1).forGetter(PlanetAddress::moon)
    ).apply(i, PlanetAddress::new));

    public static final StreamCodec<ByteBuf, PlanetAddress> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlanetAddress::catalog,
            ByteBufCodecs.VAR_INT, PlanetAddress::sx,
            ByteBufCodecs.VAR_INT, PlanetAddress::sy,
            ByteBufCodecs.VAR_INT, PlanetAddress::sz,
            ByteBufCodecs.VAR_INT, PlanetAddress::starIndex,
            ByteBufCodecs.VAR_INT, PlanetAddress::planet,
            ByteBufCodecs.VAR_INT, PlanetAddress::moon,
            PlanetAddress::new);

    /** A rebuilt address: the star, the planet and, for moons, the moon. */
    public record Resolved(Star star, Body planet, Body moon) {
        /** The body you stand on. */
        public Body body() {
            return moon != null ? moon : planet;
        }
    }

    /**
     * Address of {@code body} (a planet of {@code star}, or a moon of one of them). Procedural stars
     * are found by scanning their sector, once per landing; returns null if the body is not part of
     * the star's system.
     */
    public static PlanetAddress of(Galaxy galaxy, Star star, Body body) {
        List<Body> planets = SystemGenerator.generate(star);
        int pi = -1, mi = -1;
        for (int i = 0; i < planets.size() && pi < 0; i++) {
            Body p = planets.get(i);
            if (p.id() == body.id()) {
                pi = i;
                break;
            }
            for (int j = 0; j < p.moons().size(); j++) {
                if (p.moons().get(j).id() == body.id()) {
                    pi = i;
                    mi = j;
                    break;
                }
            }
        }
        if (pi < 0) return null;
        if (star.catalog()) return new PlanetAddress(star.name(), 0, 0, 0, 0, pi, mi);
        SectorKey k = SectorKey.containing(star.x(), star.y(), star.z());
        int n = galaxy.starCount(k);
        for (int i = 0; i < n; i++) {
            if (galaxy.star(k, i).id() == star.id()) return new PlanetAddress("", k.ix(), k.iy(), k.iz(), i, pi, mi);
        }
        return null;
    }

    public Star star(Galaxy galaxy) {
        if (!catalog.isEmpty()) return StarCatalog.byName(catalog);
        SectorKey k = new SectorKey(sx, sy, sz);
        return starIndex >= 0 && starIndex < galaxy.starCount(k) ? galaxy.star(k, starIndex) : null;
    }

    /** Rebuilds the body, or null if the address no longer points at anything (e.g. a changed generator). */
    public Resolved resolve(Galaxy galaxy) {
        Star s = star(galaxy);
        if (s == null) return null;
        List<Body> planets = SystemGenerator.generate(s);
        if (planet < 0 || planet >= planets.size()) return null;
        Body p = planets.get(planet);
        if (moon < 0) return new Resolved(s, p, null);
        return moon < p.moons().size() ? new Resolved(s, p, p.moons().get(moon)) : null;
    }
}
