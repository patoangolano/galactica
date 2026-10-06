package dev.galactica.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GalacticaConfigTest {

    @Test
    void defaultsAreLegalAndMatchShippedBehaviour() {
        GalacticaConfig d = GalacticaConfig.defaults();
        assertEquals(2048, d.skyBakeWidth(), "M1–M6 sky baked at 2048");
        assertEquals(512, d.planetTextureMax());
        assertTrue(d.starPlasma());
        assertEquals(GalacticaConfig.Quality.MEDIUM, d.starPlasmaQuality());
        assertEquals(GalacticaConfig.ExhaustQuality.MEDIUM, d.exhaustQuality());
        assertEquals(1.0, d.entryEffects());
        assertTrue(d.realNightSky());
        assertTrue(d.seamlessTransitions());
        assertEquals(10.0, d.warpMaxPcPerS(), "full warp in nav.Frames");
        assertEquals(1.0, d.hudScale());
        assertTrue(d.showLegend());
    }

    @Test
    void jsonRoundTripIsLossless() {
        GalacticaConfig.Builder b = GalacticaConfig.builder();
        b.skyBakeWidth = 4096;
        b.planetTextureMax = 256;
        b.starPlasma = false;
        b.starPlasmaQuality = GalacticaConfig.Quality.HIGH;
        b.exhaustQuality = GalacticaConfig.ExhaustQuality.OFF;
        b.entryEffects = 0.35;
        b.realNightSky = false;
        b.seamlessTransitions = false;
        b.warpMaxPcPerS = 0.3;
        b.hudScale = 1.25;
        b.showLegend = false;
        GalacticaConfig c = b.build();
        GalacticaConfig.ParseResult r = GalacticaConfig.parse(c.toJson());
        assertEquals(c, r.config());
        assertTrue(r.repairs().isEmpty(), r.repairs().toString());
        assertEquals(GalacticaConfig.SCHEMA_VERSION, r.fileVersion());
        assertFalse(r.needsRewrite());
        assertTrue(c.toJson().contains("\"version\": 1"));
    }

    @Test
    void outOfRangeValuesAreClampedAndReported() {
        String json = """
                {"version":1,"skyQuality":3000,"planetTextureMax":5000,"entryEffects":7,
                 "warpMaxPcPerS":-4,"hudScale":0.1,"exhaustQuality":"ULTRA","starPlasma":"yes"}
                """;
        GalacticaConfig.ParseResult r = GalacticaConfig.parse(json);
        GalacticaConfig c = r.config();
        assertEquals(2048, c.skyBakeWidth(), "3000 is 952 from 2048 and 1096 from 4096");
        assertEquals(1024, c.planetTextureMax());
        assertEquals(1.0, c.entryEffects());
        assertEquals(GalacticaConfig.WARP_MIN_PC_PER_S, c.warpMaxPcPerS());
        assertEquals(0.5, c.hudScale());
        assertEquals(GalacticaConfig.ExhaustQuality.MEDIUM, c.exhaustQuality(), "unknown enum keeps default");
        assertTrue(c.starPlasma(), "non-boolean keeps default");
        assertTrue(r.needsRewrite());
        assertTrue(r.repairs().size() >= 7, r.repairs().toString());
    }

    @Test
    void namedSkyQualityAndLowercaseEnumsAreAccepted() {
        GalacticaConfig c = GalacticaConfig.parse("{\"version\":1,\"skyQuality\":\"high\",\"starPlasmaQuality\":\"low\"}").config();
        assertEquals(4096, c.skyBakeWidth());
        assertEquals(GalacticaConfig.Quality.LOW, c.starPlasmaQuality());
    }

    @Test
    void garbageAndEmptyInputFallBackToDefaults() {
        for (String s : new String[]{"", "not json {", "[1,2,3]", "null"}) {
            GalacticaConfig.ParseResult r = GalacticaConfig.parse(s);
            assertEquals(GalacticaConfig.defaults(), r.config(), s);
            assertTrue(r.needsRewrite(), s);
        }
    }

    @Test
    void unversionedFileIsMigrated() {
        GalacticaConfig.ParseResult r = GalacticaConfig.parse("{\"hudScale\":1.5}");
        assertEquals(0, r.fileVersion());
        assertEquals(1.5, r.config().hudScale());
        assertTrue(r.needsRewrite());
    }

    @Test
    void powerOfTwoSnappingIsLogarithmic() {
        assertEquals(512, GalacticaConfig.snapPow2(700, 128, 1024), "700/512 = 1.37 < 1024/700 = 1.46");
        assertEquals(1024, GalacticaConfig.snapPow2(800, 128, 1024));
        assertEquals(128, GalacticaConfig.snapPow2(1, 128, 1024));
        assertEquals(256, GalacticaConfig.snapPow2(256, 128, 1024));
        assertEquals(4096, GalacticaConfig.snapSkyWidth(1_000_000));
        assertEquals(1024, GalacticaConfig.snapSkyWidth(-5));
        assertEquals(2048, GalacticaConfig.snapSkyWidth(3072), "ties go to the cheaper width");
    }

    @Test
    void nonFiniteNumbersNeverLeak() {
        GalacticaConfig.Builder b = GalacticaConfig.builder();
        b.hudScale = Double.NaN;
        b.entryEffects = Double.POSITIVE_INFINITY;
        GalacticaConfig c = b.build();
        assertEquals(1.0, c.hudScale());
        assertEquals(1.0, c.entryEffects());
    }

    @Test
    void storeCreatesRepairsAndNotifies(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config").resolve(GalacticaConfigs.FILE_NAME);
        GalacticaConfigs.resetForTests(f);
        assertEquals(GalacticaConfig.defaults(), GalacticaConfigs.get());
        assertTrue(Files.exists(f), "missing file is created with defaults");

        AtomicReference<GalacticaConfig> seen = new AtomicReference<>();
        GalacticaConfigs.onChange(seen::set);
        GalacticaConfig.Builder b = GalacticaConfigs.get().toBuilder();
        b.skyBakeWidth = 4096;
        GalacticaConfigs.apply(b.build());
        assertEquals(4096, seen.get().skyBakeWidth());
        assertEquals(4096, GalacticaConfig.parse(Files.readString(f)).config().skyBakeWidth());

        // A hand-broken file is repaired on the next load, and the original is kept as .bak.
        Files.writeString(f, "{\"version\":1,\"hudScale\":99}", StandardCharsets.UTF_8);
        GalacticaConfigs.resetForTests(f);
        assertEquals(2.0, GalacticaConfigs.get().hudScale());
        assertTrue(Files.exists(f.resolveSibling(GalacticaConfigs.FILE_NAME + ".bak")));
        assertFalse(GalacticaConfig.parse(Files.readString(f)).needsRewrite());
        GalacticaConfigs.resetForTests(null);
    }
}
