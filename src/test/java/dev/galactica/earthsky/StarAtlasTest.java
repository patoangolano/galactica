package dev.galactica.earthsky;

import dev.galactica.client.earthsky.SkyMesh;
import dev.galactica.client.earthsky.StarAtlas;
import dev.galactica.science.Blackbody;
import dev.galactica.sky.GalaxySkyBaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StarAtlasTest {

    @Test
    void columnsFollowTemperature() {
        float[] rgb = new float[3];
        Blackbody.linearRgb(3_600, rgb); // Betelgeuse
        int red = StarAtlas.column(rgb[0], rgb[1], rgb[2]);
        Blackbody.linearRgb(11_000, rgb); // Rigel
        int blue = StarAtlas.column(rgb[0], rgb[1], rgb[2]);
        assertTrue(red < blue, red + " vs " + blue);
        assertEquals(0, StarAtlas.column(1f, 0.2f, 0f));
        Blackbody.linearRgb(StarAtlas.temperature(5), rgb);
        assertEquals(5, StarAtlas.column(rgb[0], rgb[1], rgb[2]));
    }

    @Test
    void rowsCarryPogsonBrightness() {
        assertEquals(0, StarAtlas.row(-1.46));
        assertEquals(0, StarAtlas.row(4.0));
        assertEquals(StarAtlas.LEVELS - 1, StarAtlas.row(6.5));
        int prev = -1;
        for (double m = 3.5; m <= 6.5; m += 0.05) {
            int r = StarAtlas.row(m);
            assertTrue(r >= prev);
            prev = r;
            // Quantisation error below the eye's ~0.2 mag discrimination.
            double baked = -2.5 * Math.log10(StarAtlas.rowAlpha(r)), wanted = -2.5 * Math.log10(StarAtlas.spriteAlpha(m));
            assertEquals(wanted, baked, 0.09, "m = " + m);
        }
    }

    @Test
    void cellsDoNotBleed() {
        int[] px = StarAtlas.pixels();
        assertEquals(StarAtlas.WIDTH * StarAtlas.HEIGHT, px.length);
        int n = StarAtlas.CELL;
        for (int row = 0; row < StarAtlas.LEVELS; row++) {
            for (int col = 0; col < StarAtlas.COLOURS; col++) {
                for (int k = 0; k < n; k++) {
                    assertEquals(0, alpha(px, col * n + k, row * n), "top edge");
                    assertEquals(0, alpha(px, col * n, row * n + k), "left edge");
                    assertEquals(0, alpha(px, col * n + k, row * n + n - 1), "bottom edge");
                    assertEquals(0, alpha(px, col * n + n - 1, row * n + k), "right edge");
                }
            }
        }
        // Centre of the brightest row is opaque; of the faintest, ~MIN_ALPHA.
        assertTrue(alpha(px, n / 2, n / 2) > 240);
        assertEquals(255 * StarAtlas.MIN_ALPHA, alpha(px, n / 2, (StarAtlas.LEVELS - 1) * n + n / 2), 6);
        float[] uv = StarAtlas.uv(3, 7);
        assertTrue(uv[0] > 3.0 * n / StarAtlas.WIDTH && uv[2] < 4.0 * n / StarAtlas.WIDTH);
        assertTrue(uv[1] > 7.0 * n / StarAtlas.HEIGHT && uv[3] < 8.0 * n / StarAtlas.HEIGHT);
    }

    private static int alpha(int[] px, int x, int y) {
        return px[y * StarAtlas.WIDTH + x] >>> 24;
    }

    /**
     * The top latitude row of the Milky Way sphere has two corners at the pole. An edge-based normal
     * vanished there and the quad was left facing out (culled: a hole at RA 0h, Dec 0). The diagonal
     * normal must turn every quad of the sphere to face the centre, pole rows included.
     */
    @Test
    void everySphereQuadFacesInwards() {
        int lon = 96, lat = 48;
        double[] d = new double[3];
        for (int j = 0; j < lat; j++) {
            for (int i = 0; i < lon; i++) {
                float[][] uv = {{(float) i / lon, (float) j / lat}, {(float) (i + 1) / lon, (float) j / lat},
                        {(float) (i + 1) / lon, (float) (j + 1) / lat}, {(float) i / lon, (float) (j + 1) / lat}};
                float[][] v = new float[4][];
                for (int k = 0; k < 4; k++) {
                    GalaxySkyBaker.equirectDirection(uv[k][0], uv[k][1], d);
                    v[k] = new float[] {(float) d[0] * 100, (float) d[1] * 100, (float) d[2] * 100};
                }
                int[] order = SkyMesh.inwardOrder(v);
                float[][] w = {v[order[0]], v[order[1]], v[order[2]], v[order[3]]};
                // Both triangles the GPU builds from a quad, (0,1,2) and (2,3,0): every non-degenerate one faces in.
                assertFalse(outward(w[0], w[1], w[2]) || outward(w[2], w[3], w[0]), "quad " + i + "," + j);
                assertTrue(area(w[0], w[1], w[2]) + area(w[2], w[3], w[0]) > 0, "quad " + i + "," + j);
            }
        }
    }

    private static boolean outward(float[] a, float[] b, float[] c) {
        float[] n = cross(a, b, c);
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len < 1e-4) return false; // degenerate: draws nothing
        return n[0] * (a[0] + b[0] + c[0]) + n[1] * (a[1] + b[1] + c[1]) + n[2] * (a[2] + b[2] + c[2]) > 0;
    }

    private static double area(float[] a, float[] b, float[] c) {
        float[] n = cross(a, b, c);
        return Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    }

    private static float[] cross(float[] a, float[] b, float[] c) {
        float ax = b[0] - a[0], ay = b[1] - a[1], az = b[2] - a[2];
        float bx = c[0] - a[0], by = c[1] - a[1], bz = c[2] - a[2];
        return new float[] {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }
}
