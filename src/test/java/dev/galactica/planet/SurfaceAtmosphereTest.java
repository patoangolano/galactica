package dev.galactica.planet;

import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import dev.galactica.system.SolarSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceAtmosphereTest {

    static Body sol(String name) {
        for (Body p : SolarSystem.planets()) {
            if (p.name().equals(name)) return p;
            for (Body m : p.moons()) if (m.name().equals(name)) return m;
        }
        throw new AssertionError(name);
    }

    private static int r(int c) { return (c >> 16) & 0xFF; }
    private static int g(int c) { return (c >> 8) & 0xFF; }
    private static int b(int c) { return c & 0xFF; }

    @Test
    void earthIsRayleighBlue() {
        int c = SurfaceAtmosphere.skyColour(sol("Earth"));
        assertTrue(b(c) > r(c) + 60 && b(c) > g(c), Integer.toHexString(c));
    }

    @Test
    void marsIsButterscotch() {
        int c = SurfaceAtmosphere.skyColour(sol("Mars"));
        assertEquals(SurfaceAtmosphere.SkyClass.BUTTERSCOTCH, SurfaceAtmosphere.skyClass(sol("Mars")));
        assertTrue(r(c) > g(c) && g(c) > b(c) && b(c) > 64, "warm tan, not black: " + Integer.toHexString(c));
    }

    @Test
    void titanIsOrange() {
        int c = SurfaceAtmosphere.skyColour(sol("Titan"));
        assertTrue(r(c) > 0xB0 && r(c) - b(c) > 100 && g(c) > b(c), Integer.toHexString(c));
    }

    @Test
    void airlessAndTenuousSkiesAreBlack() {
        for (String n : new String[] {"Moon", "Mercury", "Europa", "Io", "Triton"}) {
            assertEquals(0x000000, SurfaceAtmosphere.skyColour(sol(n)), n);
        }
    }

    @Test
    void venusIsSulphurYellow() {
        assertEquals(SurfaceAtmosphere.SkyClass.YELLOW, SurfaceAtmosphere.skyClass(sol("Venus")));
    }

    @Test
    void marsHasCratersEarthAndTitanDoNot() {
        assertTrue(SurfaceAtmosphere.cratered(sol("Mars")));
        assertTrue(SurfaceAtmosphere.cratered(sol("Moon")));
        assertFalse(SurfaceAtmosphere.cratered(sol("Earth")));
        assertFalse(SurfaceAtmosphere.cratered(sol("Titan")));
    }

    @Test
    void everyBiomeCombinationShipsAsJson() {
        Path dir = Path.of("src/main/resources/data/galactica/worldgen/biome");
        for (BodyType t : BodyType.values()) {
            if (!t.solidSurface) continue;
            for (SurfaceAtmosphere.SkyClass s : SurfaceAtmosphere.SkyClass.values()) {
                Path f = dir.resolve(SurfaceAtmosphere.biomePath(t, s) + ".json");
                assertTrue(Files.exists(f), f.toString());
            }
        }
        assertEquals("planet_desert_butterscotch", SurfaceAtmosphere.biomePath(sol("Mars")));
        assertTrue(Files.exists(dir.resolve(PlanetBiomeSourceFallback.PATH + ".json")));
    }

    /** Keeps the fallback name checked without loading the Minecraft-bound biome source class. */
    private static final class PlanetBiomeSourceFallback {
        static final String PATH = "planet_barren_black";
    }
}
