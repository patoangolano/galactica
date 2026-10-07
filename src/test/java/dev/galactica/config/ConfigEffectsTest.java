package dev.galactica.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigEffectsTest {

    @Test
    void skyPanoramaStaysTwoToOne() {
        for (int w : GalacticaConfig.SKY_BAKE_WIDTHS) {
            int h = ConfigEffects.skyHeight(w);
            assertEquals(w, 2 * h);
            assertEquals(w, ConfigEffects.equirectWidth(w * h), "upload infers the bake width from its pixel count");
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigEffects.equirectWidth(1000 * 1000));
    }

    @Test
    void spriteSizeAtDefaultMatchesTheShippedFormula() {
        for (double px = 0; px < 2000; px += 3.7) {
            int old = Integer.highestOneBit((int) Math.max(64, Math.min(512, px * 1.2)) * 2 - 1);
            old = Math.max(64, Math.min(512, old));
            assertEquals(old, ConfigEffects.spriteSize(px, 512), "pixels " + px);
        }
    }

    @Test
    void spriteSizeFollowsPlanetTextureMax() {
        assertEquals(128, ConfigEffects.spriteSize(5000, 128));
        assertEquals(1024, ConfigEffects.spriteSize(5000, 1024));
        assertEquals(1024, ConfigEffects.spriteSize(700, 1024), "700 px × 1.2 rounds up to 1024");
        assertEquals(64, ConfigEffects.spriteSize(3, 1024));
        assertEquals(128, ConfigEffects.spriteSize(Double.NaN, 128));
        for (int max = 128; max <= 1024; max *= 2) {
            for (double px = 0; px < 3000; px += 11) {
                int s = ConfigEffects.spriteSize(px, max);
                assertEquals(Integer.bitCount(s), 1, "power of two");
                assertTrue(s >= 64 && s <= max);
            }
        }
    }

    @Test
    void hudExtentKeepsAnchoredPanelsOnScreen() {
        assertEquals(480, ConfigEffects.scaledExtent(480, 1.0));
        assertEquals(240, ConfigEffects.scaledExtent(480, 2.0));
        assertEquals(960, ConfigEffects.scaledExtent(480, 0.5));
        assertEquals(320, ConfigEffects.scaledExtent(481, 1.5));
        assertTrue(ConfigEffects.scaledExtent(481, 1.5) * 1.5 <= 481);
        assertEquals(480, ConfigEffects.scaledExtent(480, 0.0), "a bad scale leaves the HUD unscaled");
    }

    @Test
    void entryEffectsScaleTheGlow() {
        assertEquals(0.8, ConfigEffects.entryGlow(0.8, 1.0));
        assertEquals(0.4, ConfigEffects.entryGlow(0.8, 0.5), 1e-12);
        assertEquals(0.0, ConfigEffects.entryGlow(0.8, 0.0), "0 disables entry effects");
        assertEquals(0.0, ConfigEffects.entryGlow(-1.0, 1.0));
    }

    @Test
    void qualityLevels() {
        assertEquals(0, ConfigEffects.exhaustSegments(GalacticaConfig.ExhaustQuality.OFF));
        assertEquals(12, ConfigEffects.exhaustSegments(GalacticaConfig.ExhaustQuality.MEDIUM), "medium is the shipped plume");
        assertTrue(ConfigEffects.exhaustSegments(GalacticaConfig.ExhaustQuality.LOW)
                < ConfigEffects.exhaustSegments(GalacticaConfig.ExhaustQuality.HIGH));
        assertEquals(0f, ConfigEffects.starQualityLevel(GalacticaConfig.Quality.LOW));
        assertEquals(1f, ConfigEffects.starQualityLevel(GalacticaConfig.Quality.MEDIUM));
        assertEquals(2f, ConfigEffects.starQualityLevel(GalacticaConfig.Quality.HIGH));
    }
}
