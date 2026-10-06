package dev.galactica.map;

import dev.galactica.client.map.MapView;
import dev.galactica.galaxy.GalaxyModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapViewTest {

    @Test
    void projectionRoundTripsAndKeepsTheSunBelowTheCentre() {
        MapView v = new MapView(0, 0, 30_000, 10, 20, 600);
        // Galactic centre at the middle of the viewport.
        assertEquals(310, v.screenX(0, 0), 1e-9);
        assertEquals(320, v.screenY(0, 0), 1e-9);
        // The Sun (+x) is straight below it, 8.178 kpc / 30 kpc of the map lower.
        assertEquals(310, v.screenX(GalaxyModel.R0, 0), 1e-9);
        assertEquals(320 + 600 * GalaxyModel.R0 / 30_000, v.screenY(GalaxyModel.R0, 0), 1e-9);
        // l = 90° (galactocentric −y) is on the left: rotation is clockwise seen from the NGP.
        assertTrue(v.screenX(0, -1000) < 310);
        double sx = v.screenX(1234.5, -987.6), sy = v.screenY(1234.5, -987.6);
        assertEquals(1234.5, v.galacticX(sx, sy), 1e-6);
        assertEquals(-987.6, v.galacticY(sx, sy), 1e-6);
        assertEquals(50.0, v.pcPerPixel(), 1e-12);
    }

    @Test
    void zoomStepsThroughFourLevelsAndClamps() {
        assertArrayEquals(new double[] {30_000, 3_000, 300, 30}, MapView.ZOOM_WIDTHS_PC);
        assertEquals(1, MapView.levelAfterScroll(0, 1.0));
        assertEquals(1, MapView.levelAfterScroll(0, 0.1), "a fractional notch still zooms");
        assertEquals(3, MapView.levelAfterScroll(3, 1.0));
        assertEquals(0, MapView.levelAfterScroll(0, -1.0));
        assertEquals(2, MapView.levelAfterScroll(3, -2.0));
        assertEquals(2, MapView.levelAfterScroll(2, 0.0));
    }

    @Test
    void wholeGalaxyViewBlitsTheWholeTexture() {
        float[] b = new MapView(0, 0, 30_000, 0, 0, 512).backgroundBlit();
        assertNotNull(b);
        assertArrayEquals(new float[] {0, 0, 512, 512, 0, 1, 0, 1}, b, 1e-6f);
    }

    @Test
    void zoomedViewBlitsTheMatchingSubRectangle() {
        // 3 kpc around the Sun: u follows +y (centred, 0.45..0.55), v follows +x around 23178/30000.
        MapView v = new MapView(GalaxyModel.R0, 0, 3_000, 0, 0, 300);
        float[] b = v.backgroundBlit();
        assertNotNull(b);
        assertEquals(0, b[0], 0);
        assertEquals(300, b[2], 0);
        assertEquals(0.45, b[4], 1e-6);
        assertEquals(0.55, b[5], 1e-6);
        assertEquals((GalaxyModel.R0 - 1500 + 15_000) / 30_000, b[6], 1e-6);
        assertEquals((GalaxyModel.R0 + 1500 + 15_000) / 30_000, b[7], 1e-6);
    }

    @Test
    void viewHangingOffTheTextureIsClipped() {
        // Centred on the right edge of the texture: only the left half of the viewport is covered.
        float[] b = new MapView(0, 15_000, 3_000, 0, 0, 200).backgroundBlit();
        assertNotNull(b);
        assertEquals(0, b[0], 0);
        assertEquals(100, b[2], 0);
        assertEquals(1.0, b[5], 1e-6);
        assertNull(new MapView(0, 20_000, 3_000, 0, 0, 200).backgroundBlit());
    }

    @Test
    void scaleBarIsANiceFifthOfTheView() {
        MapView v = new MapView(0, 0, 30_000, 0, 0, 500);
        double[] bar = v.scaleBar();
        assertEquals(5_000, bar[0], 1e-9);
        assertEquals(500 * 5_000 / 30_000.0, bar[1], 1e-9);
        assertEquals(5, new MapView(0, 0, 30, 0, 0, 500).scaleBar()[0], 1e-9);
        assertEquals(50, new MapView(0, 0, 300, 0, 0, 500).scaleBar()[0], 1e-9);
        assertEquals("5 kpc", MapView.formatPc(5000));
        assertEquals("1.5 kpc", MapView.formatPc(1500));
        assertEquals("200 pc", MapView.formatPc(200));
    }

    @Test
    void markerSizeGrowsWithLuminosity() {
        assertEquals(0, MapView.markerRadius(4.83), "the Sun is a single pixel");
        assertEquals(0, MapView.markerRadius(15), "red dwarfs too");
        assertEquals(2, MapView.markerRadius(-2));
        assertEquals(3, MapView.markerRadius(-8));
        assertEquals(1, MapView.markerRadius(Double.POSITIVE_INFINITY), "black holes keep a marker");
    }
}
