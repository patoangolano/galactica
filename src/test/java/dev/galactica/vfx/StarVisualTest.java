package dev.galactica.vfx;

import dev.galactica.client.vfx.Vec4Block;
import dev.galactica.client.vfx.VfxClock;
import dev.galactica.client.vfx.star.StarUniforms;
import dev.galactica.client.vfx.star.StarVisual;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarVisualTest {
    private static Star star(StarKind kind, double m, double r, double l, double t, double age, long seed) {
        return new Star(seed, "test", 0, 0, 0, kind, m, r, l, t, age, 0, seed * 31 + 7, false);
    }

    private static final Star SUN = star(StarKind.MAIN_SEQUENCE, 1, 1, 1, 5772, 4.6, 1);

    @Test
    void sunLikeStar() {
        StarVisual v = StarVisual.from(SUN);
        assertTrue(v.drawable());
        assertFalse(v.smooth());
        assertEquals(4.438, v.logG(), 0.01);
        assertTrue(v.cellsPerRadius() > 30);
        assertEquals(20, v.lifetimeS(), 1);
        assertTrue(v.contrast() > 0.14);
        assertTrue(v.r() >= v.b(), "a G star is warm white");
        assertTrue(v.corona() > 0 && v.chromosphere() > 0);
        assertTrue(v.spots().size() <= StarUniforms.MAX_SPOTS);
        for (StarVisual.Spot s : v.spots()) assertTrue(Math.abs(s.latitude()) < Math.toRadians(40));
        assertEquals(0, v.flareRate(), 1e-12);
    }

    @Test
    void deterministicPerStar() {
        assertEquals(StarVisual.from(SUN), StarVisual.from(SUN));
        assertNotEquals(StarVisual.from(SUN).seed(), StarVisual.from(star(StarKind.MAIN_SEQUENCE, 1, 1, 1, 5772, 4.6, 2)).seed());
    }

    @Test
    void giantsShowFewCellsDwarfsMany() {
        StarVisual betelgeuse = StarVisual.from(star(StarKind.SUPERGIANT, 18, 900, 1e5, 3600, 0.01, 3));
        StarVisual proxima = StarVisual.from(star(StarKind.MAIN_SEQUENCE, 0.122, 0.154, 0.0017, 3042, 4.85, 4));
        StarVisual sun = StarVisual.from(SUN);
        assertTrue(betelgeuse.cellsPerRadius() < 8);
        assertTrue(proxima.cellsPerRadius() > sun.cellsPerRadius());
        assertTrue(betelgeuse.lifetimeS() > sun.lifetimeS());
        assertTrue(proxima.flareRate() > 0, "M dwarfs flare");
        assertTrue(betelgeuse.corona() < sun.corona(), "cool supergiants lie beyond the Linsky-Haisch line");
    }

    @Test
    void hotStarsHaveNoConvectionFeatures() {
        StarVisual sirius = StarVisual.from(star(StarKind.MAIN_SEQUENCE, 2.06, 1.71, 25.4, 9940, 0.24, 5));
        assertEquals(0, sirius.contrast(), 1e-9);
        assertTrue(sirius.spots().isEmpty());
        assertEquals(0, sirius.prominences());
        assertTrue(sirius.b() >= sirius.r(), "an A star is blue-white");
    }

    @Test
    void remnants() {
        StarVisual wd = StarVisual.from(star(StarKind.WHITE_DWARF, 0.6, 0.012, 0.01, 15000, 1, 6));
        assertTrue(wd.smooth() && wd.drawable() && wd.spots().isEmpty() && wd.contrast() == 0);
        StarVisual ns = StarVisual.from(star(StarKind.NEUTRON_STAR, 1.4, 1.7e-5, 0.1, 600000, 0.01, 7));
        assertTrue(ns.smooth());
        assertEquals(0, ns.limbA(), 0);
        assertEquals(0, ns.limbB(), 0);
        assertFalse(StarVisual.from(star(StarKind.BLACK_HOLE, 10, 4e-5, 0, 0, 5, 8)).drawable());
    }

    @Test
    void uniformLayoutMatchesTheGlslBlock() {
        assertEquals(16, StarUniforms.VEC4_COUNT);
        assertEquals(256, StarUniforms.SIZE_BYTES);
        assertEquals(7, StarUniforms.PHASE);
        assertEquals(8, StarUniforms.SPOTS);
        StarVisual v = StarVisual.from(SUN);
        Vec4Block b = StarUniforms.pack(v, 0.05, 0.5f, 12.5, new Vec4Block(StarUniforms.VEC4_COUNT));
        assertEquals(256, b.sizeBytes());
        assertEquals(0.5f, b.get(StarUniforms.COLOR, 3));
        assertEquals((float) v.limbA(), b.get(StarUniforms.LIMB, 0));
        assertEquals((float) v.cellsPerRadius(), b.get(StarUniforms.GRAN, 0));
        assertEquals(0.05f, b.get(StarUniforms.GEOM, 0));
        assertEquals(12.5f, b.get(StarUniforms.GEOM, 2));
        assertTrue(b.get(StarUniforms.ACTIVE, 3) >= 0 && b.get(StarUniforms.ACTIVE, 3) < 1000);
        // Unused spot slots are zero (radius 0 = skipped by the shader).
        for (int i = v.spots().size(); i < StarUniforms.MAX_SPOTS; i++) assertEquals(0f, b.get(StarUniforms.SPOTS + i, 2));
        // Byte layout: slot s component c at 16 s + 4 c, native order.
        assertEquals((float) StarUniforms.equatorialPhase(v.rotationRate(), 12.5), b.get(StarUniforms.PHASE, 0));
        assertEquals((float) StarUniforms.cyclePhase(v.lifetimeS(), 12.5), b.get(StarUniforms.PHASE, 2));
        ByteBuffer buf = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        b.write(buf);
        assertEquals(256, buf.position());
        assertEquals(12.5f, buf.getFloat(16 * StarUniforms.GEOM + 8));
        assertEquals((float) v.contrast(), buf.getFloat(16 * StarUniforms.GRAN + 8));
    }

    @Test
    void quadSizing() {
        // Well resolved: corona margin of 3.5 radii.
        assertEquals(0.05 * StarUniforms.CORONA_MARGIN, StarUniforms.quadHalfAngle(0.05), 1e-12);
        // The Sun from Earth (4 px radius): the halo still spans >= 14 px.
        double sunFromEarth = 0.00465;
        assertTrue(StarUniforms.quadHalfAngle(sunFromEarth) * StarUniforms.PX_PER_RAD >= 14 - 1e-9);
        // Close to a giant: capped so the flat quad stays finite.
        assertEquals(StarUniforms.MAX_QUAD_HALF_ANGLE, StarUniforms.quadHalfAngle(0.9), 1e-12);
    }

    @Test
    void clockWraps() {
        assertEquals(0f, VfxClock.wrap(0));
        assertEquals(1.5f, VfxClock.wrap(3_601_500_000_000L), 1e-4);
        assertEquals(3599f, VfxClock.wrap(-1_000_000_000L), 1e-3);
        float now = VfxClock.seconds();
        assertTrue(now >= 0 && now < VfxClock.PERIOD_S);
        assertEquals(1.5f, VfxClock.wrapSeconds(3601.5), 1e-4);
        assertEquals(3599f, VfxClock.wrapSeconds(-1.0), 1e-3);
        assertTrue(VfxClock.elapsedSeconds() >= 0);
    }

    @Test
    void rotationAndCyclePhasesAreContinuousAcrossTheClockWrap() {
        StarVisual v = StarVisual.from(SUN);
        double rate = v.rotationRate();
        double dt = 1.0 / 60.0;
        // Step frame by frame through the 1-hour float-clock wrap and well past several shear turnarounds.
        double[] starts = {VfxClock.PERIOD_S - 2.0, 7 * VfxClock.PERIOD_S - 2.0, 1e6};
        for (double t0 : starts) {
            for (double t = t0; t < t0 + 4.0; t += dt) {
                double de = angleDiff(StarUniforms.equatorialPhase(rate, t + dt), StarUniforms.equatorialPhase(rate, t));
                assertEquals(rate * dt, de, 1e-6, "equatorial phase steps by rate*dt at t=" + t);
                double ds = Math.abs(StarUniforms.shearPhase(rate, t + dt) - StarUniforms.shearPhase(rate, t));
                assertTrue(ds <= StarUniforms.DIFF_B * rate * dt + 1e-9, "shear phase is continuous at t=" + t);
                double dg = angleDiff(StarUniforms.cyclePhase(v.lifetimeS(), t + dt), StarUniforms.cyclePhase(v.lifetimeS(), t));
                assertEquals(2 * Math.PI * dt / v.lifetimeS(), dg, 1e-6, "granule phase steps evenly at t=" + t);
            }
        }
        // Shear stays bounded and reaches its maximum.
        double max = 0;
        double turn = StarUniforms.SHEAR_MAX / (StarUniforms.DIFF_B * rate);
        for (double t = 0; t < 3 * turn; t += turn / 500) {
            double s = StarUniforms.shearPhase(rate, t);
            assertTrue(s >= 0 && s <= StarUniforms.SHEAR_MAX + 1e-12);
            max = Math.max(max, s);
        }
        assertEquals(StarUniforms.SHEAR_MAX, max, 0.01);
        assertEquals(0.0, StarUniforms.cyclePhase(0.0, 5.0), 0.0, "zero period (smooth remnants) is safe");
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    @Test
    void vec4BlockValueSemantics() {
        Vec4Block a = new Vec4Block(2).put(1, 1f, 2f, 3f, 4f);
        Vec4Block b = a.copy();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.put(0, 0f, 0f, 0f, 1f);
        assertNotEquals(a, b);
        assertEquals(32, a.sizeBytes());
        Vec4Block c = new Vec4Block(2).copyFrom(b);
        assertEquals(b, c);
        assertThrows(IllegalArgumentException.class, () -> new Vec4Block(3).copyFrom(b));
    }
}
