package dev.galactica.nav.target;

import dev.galactica.deepsky.DeepSkyCatalog;
import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.autopilot.AutopilotLegs;
import dev.galactica.nav.autopilot.AutopilotMath;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavTargetTest {
    private static final Galaxy G = new Galaxy(Frames.GALAXY_SEED);
    private static final double T = 1.0e6;

    private static NavTarget find(String q) {
        List<NavTarget> r = TargetSearch.search(G, q, StarCatalog.SOL, 5);
        assertFalse(r.isEmpty(), "found " + q);
        return r.getFirst();
    }

    @Test
    void searchFindsStarsPlanetsMoonsAndDeepSky() {
        NavTarget sol = find("Sol");
        assertEquals(NavTarget.Kind.STAR, sol.kind());
        assertEquals(StarCatalog.SOL.id(), sol.starId());
        NavTarget mars = find("mars");
        assertEquals(NavTarget.Kind.PLANET, mars.kind());
        assertEquals("Mars", mars.name());
        assertEquals(NavTarget.Kind.MOON, find("Moon").kind());
        assertEquals(NavTarget.Kind.MOON, find("Titan").kind());
        DeepSkyObject m42 = DeepSkyCatalog.all().getFirst();
        NavTarget ds = find(m42.name);
        assertEquals(NavTarget.Kind.DEEP_SKY, ds.kind());
        assertEquals(m42.id, ds.deepSkyId());
        assertEquals("Sirius A", find("siri").name(), "prefix match");
        assertTrue(TargetSearch.search(G, "zzzzqqq", StarCatalog.SOL, 5).isEmpty());
    }

    @Test
    void proceduralDesignationsRoundTrip() {
        SectorKey k = StarCatalog.SOL_SECTOR.offset(1, 0, 0);
        Star s = G.star(k, 3);
        String designation = "GX " + k.code() + "-3";
        NavTarget t = TargetSearch.designation(G, designation);
        assertNotNull(t);
        assertEquals(s.id(), t.starId());
        assertEquals(3, t.index());
        assertEquals(k, t.sector());
        assertArrayEquals(new int[] {k.ix(), k.iy(), k.iz(), 3}, TargetSearch.addressOf(G, s));
        assertNull(TargetSearch.designation(G, "GX " + k.code() + "-999999"), "no such index");
    }

    @Test
    void theServerRederivesAndNamesEveryTarget() {
        NavTarget mars = find("Mars");
        NavTarget v = NavTargetRules.validate(G, NavTarget.Kind.PLANET, mars.starId(), mars.sector(), mars.index(), mars.bodyId(), "");
        assertNotNull(v);
        assertEquals("Mars", v.name());
        assertNull(NavTargetRules.validate(G, NavTarget.Kind.PLANET, mars.starId(), mars.sector(), mars.index(), mars.bodyId() + 1, ""),
                "a forged body id is refused");
        assertNull(NavTargetRules.validate(G, NavTarget.Kind.MOON, mars.starId(), mars.sector(), mars.index(), mars.bodyId(), ""),
                "a planet is not a moon");
        assertNull(NavTargetRules.validate(G, NavTarget.Kind.STAR, 12345L, new SectorKey(817, 0, 2), 0, 0, ""), "wrong id at that address");
        assertNull(NavTargetRules.validate(G, NavTarget.Kind.STAR, 1L, new SectorKey(100_000, 0, 0), 0, 0, ""), "beyond the galaxy");
        DeepSkyObject o = DeepSkyCatalog.all().getFirst();
        assertEquals(o.name, NavTargetRules.validate(G, NavTarget.Kind.DEEP_SKY, 0, new SectorKey(0, 0, 0), 0, 0, o.id).name());
        assertNull(NavTargetRules.validate(G, NavTarget.Kind.DEEP_SKY, 0, new SectorKey(0, 0, 0), 0, 0, "nope"));
        SectorKey k = StarCatalog.SOL_SECTOR.offset(0, 1, 0);
        Star s = G.star(k, 0);
        NavTarget p = NavTargetRules.validate(G, NavTarget.Kind.STAR, s.id(), k, 0, 0, "");
        assertEquals(s.name(), p.name());
    }

    @Test
    void geometryPlacesBodiesWhereTheNeighbourhoodDoes() {
        Neighbourhood nb = new Neighbourhood(G);
        GalacticPos near = Frames.nearEarth(T);
        for (String name : new String[] {"Mars", "Moon", "Europa"}) {
            NavTarget t = find(name);
            TargetGeometry.Resolved r = TargetGeometry.resolve(G, t, T);
            assertNotNull(r);
            Neighbourhood.Located l = nb.locateBodies(near, T).stream().filter(x -> x.body().name().equals(name)).findFirst().orElseThrow();
            assertEquals(0, r.pos().distanceMetres(l.pos()), 1.0, name);
            assertEquals(l.body().radiusMetres(), r.radius(), 1e-6);
        }
        Body mars = TargetGeometry.resolve(G, find("Mars"), T).body();
        double[] v = TargetGeometry.bodyVelocity(StarCatalog.SOL, mars, null, T);
        double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        assertTrue(speed > 21e3 && speed < 27e3, "Mars orbits at ~24 km/s: " + speed);
    }

    @Test
    void legsAimAtTheBodyInSystemAndAtItsStarFromAfar() {
        NavTarget mars = find("Mars");
        TargetGeometry.Resolved r = TargetGeometry.resolve(G, mars, T);
        AutopilotLegs.Leg here = AutopilotLegs.leg(mars, r, StarCatalog.SOL);
        assertTrue(here.last());
        assertEquals(AutopilotMath.orbitAltitude(r.body()), here.arrival());
        assertEquals(0, here.aim().distanceMetres(r.pos()));
        AutopilotLegs.Leg away = AutopilotLegs.leg(mars, r, StarCatalog.byName("Sirius A"));
        assertFalse(away.last(), "from another system: first the star");
        assertEquals(0, away.aim().distanceMetres(Neighbourhood.starPos(StarCatalog.SOL)), 1.0);
        NavTarget star = find("Alpha Centauri A");
        TargetGeometry.Resolved rs = TargetGeometry.resolve(G, star, T);
        AutopilotLegs.Leg toStar = AutopilotLegs.leg(star, rs, StarCatalog.SOL);
        assertTrue(toStar.last());
        assertTrue(toStar.arrival() > 0.04 * Units.AU, "the hyperdrive's safe distance");
        assertTrue(AutopilotLegs.releaseDistance(1e6, 0) >= 1.5e6);
        assertTrue(AutopilotLegs.releaseDistance(1e6, 3e16) > 9e15, "fast warp lets go early");
    }

    @Test
    void codecsRoundTrip() {
        NavTarget t = find("Europa");
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        NavTarget.STREAM_CODEC.encode(buf, t);
        assertEquals(t, NavTarget.STREAM_CODEC.decode(buf));
        var json = NavTarget.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, t).getOrThrow();
        assertEquals(t, NavTarget.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow());
    }
}
