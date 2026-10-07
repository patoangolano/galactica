package dev.galactica.nav;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.science.Units;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.system.Body;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrbitalFrameTest {
    private static final double MU_EARTH = Units.G * Units.EARTH_MASS;
    private static final double T0 = 1.0e6;
    private static final double DT = 0.05;

    private final Neighbourhood nb = new Neighbourhood(new Galaxy(Frames.GALAXY_SEED));

    private Neighbourhood.Located body(String name, double t) {
        for (Neighbourhood.Located l : nb.locateBodies(Frames.nearEarth(t), t)) if (l.body().name().equals(name)) return l;
        throw new AssertionError(name + " not found");
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    @Test
    void spheresOfInfluenceAreTheLaplaceRadii() {
        Body earth = body("Earth", T0).body(), moon = body("Moon", T0).body();
        assertEquals(9.24e8, earth.orbit().sphereOfInfluence(earth.massKg()), 0.01 * 9.24e8, "Earth ~924 000 km");
        assertEquals(6.6e7, moon.orbit().sphereOfInfluence(moon.massKg()), 0.02 * 6.6e7, "Moon ~66 000 km");
    }

    @Test
    void dominantBodyIsTheInnermostSphere() {
        var earth = body("Earth", T0);
        var moon = body("Moon", T0);
        GalacticPos leo = earth.pos().plusMetres(0, 0, earth.body().radiusMetres() + 400e3);
        assertEquals("Earth", OrbitalFrame.dominant(nb, leo, T0).name());
        GalacticPos nearMoon = moon.pos().plusMetres(1.0e7, 0, 0);
        assertEquals("Moon", OrbitalFrame.dominant(nb, nearMoon, T0).name(), "the Moon's sphere nests inside Earth's");
        GalacticPos outside = earth.pos().plusMetres(2.0e9, 0, 0);
        assertTrue(OrbitalFrame.dominant(nb, outside, T0).isStar(), "beyond 924 000 km the Sun dominates");
    }

    @Test
    void aFrameAtRestRelativeToEarthStaysWithEarth() {
        // The reported bug: with a galaxy-fixed frame, Earth ran away at ~1.5 km per tick.
        var earth = body("Earth", T0);
        double alt0 = 2_000e3;
        GalacticPos here = earth.pos().plusMetres(earth.body().radiusMetres() + alt0, 0, 0);
        double[] v = new double[3];
        long anchor = OrbitalFrame.ANCHOR_UNSET;
        double t = T0;
        int ticks = 200;
        for (int i = 0; i < ticks; i++) {
            OrbitalFrame.Advance a = OrbitalFrame.advance(nb, here, v, anchor, t, DT, false);
            here = here.plusMetres(a.originDelta()[0], a.originDelta()[1], a.originDelta()[2]);
            v = a.frameVelocity();
            anchor = a.anchor();
            t += DT;
        }
        assertEquals(earth.body().id(), anchor);
        double r = here.distanceMetres(body("Earth", t).pos());
        double g = MU_EARTH / Math.pow(earth.body().radiusMetres() + alt0, 2);
        double fall = 0.5 * g * Math.pow(ticks * DT, 2);
        double dropped = earth.body().radiusMetres() + alt0 - r;
        assertEquals(fall, dropped, 0.05 * fall, "free fall towards Earth, ½ g t² = " + fall + " m");
    }

    @Test
    void circularLowOrbitHoldsItsAltitudeAndEnergy() {
        double r0 = 6.371e6 + 400e3;
        double[] r = {r0, 0, 0};
        double[] v = OrbitalFrame.circularVelocity(r, MU_EARTH, new double[] {0, 0, 1});
        double e0 = OrbitalFrame.apsides(r, v, MU_EARTH)[2];
        double period = 2 * Math.PI * Math.sqrt(r0 * r0 * r0 / MU_EARTH);
        int steps = (int) (10 * period / DT);
        int firstOrbit = (int) (period / DT);
        double minR = r0, maxR = r0, driftFirst = 0, driftLast = 0;
        for (int i = 0; i < steps; i++) {
            OrbitalFrame.step(r, v, MU_EARTH, 6.371e6, DT);
            double rm = OrbitalFrame.len(r);
            minR = Math.min(minR, rm);
            maxR = Math.max(maxR, rm);
            if (i % 500 == 0) {
                double drift = Math.abs(OrbitalFrame.apsides(r, v, MU_EARTH)[2] / e0 - 1);
                if (i < firstOrbit) driftFirst = Math.max(driftFirst, drift);
                else if (i > steps - firstOrbit) driftLast = Math.max(driftLast, drift);
            }
        }
        // Symplectic Euler: the orbit comes out very slightly eccentric (e ≈ ω·dt/2) but never decays or grows.
        assertTrue(maxR - minR < 1_000, "ten orbits stay within 1 km of the 400 km circle: " + (minR - r0) + " .. " + (maxR - r0));
        assertTrue(driftFirst < 2e-4, "energy error bounded (" + driftFirst + ")");
        assertTrue(driftLast < 1.5 * driftFirst + 1e-9, "no secular drift over ten orbits: " + driftFirst + " -> " + driftLast);
    }

    @Test
    void apsidesOfAnEllipse() {
        double rp = 6.771e6, ra = 4.2164e7;
        double a = (rp + ra) / 2;
        double vp = Math.sqrt(MU_EARTH * (2 / rp - 1 / a));
        double[] el = OrbitalFrame.apsides(new double[] {rp, 0, 0}, new double[] {0, vp, 0}, MU_EARTH);
        assertEquals(rp, el[0], 1);
        assertEquals(ra, el[1], 10);
        double[] radial = OrbitalFrame.apsides(new double[] {2e7, 0, 0}, new double[] {-10, 0, 0}, MU_EARTH);
        assertEquals(0, radial[0], 1, "a straight fall meets the centre");
        assertEquals(2e7, radial[1], 2e3, "and its apoapsis is (about) where it falls from");
        double vEsc = Math.sqrt(2 * MU_EARTH / rp) * 1.01;
        assertEquals(Double.POSITIVE_INFINITY, OrbitalFrame.apsides(new double[] {rp, 0, 0}, new double[] {0, vEsc, 0}, MU_EARTH)[1]);
    }

    @Test
    void handOverKeepsTheInertialVelocity() {
        // Climbing out of Earth's sphere: heliocentric velocity is the same just before and just after the boundary.
        var earth = body("Earth", T0);
        double soi = earth.body().orbit().sphereOfInfluence(earth.body().massKg());
        GalacticPos here = earth.pos().plusMetres(soi - 500, 0, 0);
        double[] v = {1_000, 0, 0};
        long anchor = earth.body().id();
        double t = T0;
        double[] vEarth = OrbitalFrame.anchorVelocity(nb, here, anchor, t);
        double[] inertialBefore = add(v, vEarth);
        for (int i = 0; i < 40 && anchor == earth.body().id(); i++) {
            OrbitalFrame.Advance a = OrbitalFrame.advance(nb, here, v, anchor, t, DT, false);
            here = here.plusMetres(a.originDelta()[0], a.originDelta()[1], a.originDelta()[2]);
            if (a.anchor() == OrbitalFrame.ANCHOR_STAR) {
                // This tick's step re-anchored: the frame velocity is now heliocentric.
                double[] dv = {a.frameVelocity()[0] - inertialBefore[0], a.frameVelocity()[1] - inertialBefore[1],
                        a.frameVelocity()[2] - inertialBefore[2]};
                assertTrue(OrbitalFrame.len(dv) < 0.5, "velocity continuous across the boundary, |Δv| = " + OrbitalFrame.len(dv));
            }
            v = a.frameVelocity();
            anchor = a.anchor();
            t += DT;
            inertialBefore = anchor == OrbitalFrame.ANCHOR_STAR ? v : add(v, OrbitalFrame.anchorVelocity(nb, here, anchor, t));
        }
        assertEquals(OrbitalFrame.ANCHOR_STAR, anchor, "crossed out of Earth's sphere");
        assertEquals(29_800, OrbitalFrame.len(v), 1_500, "heliocentric speed ≈ Earth's orbital speed");
    }

    @Test
    void warpCoMovesWithTheBody() {
        var earth = body("Earth", T0);
        GalacticPos here = earth.pos().plusMetres(5e7, 0, 0);
        OrbitalFrame.Advance a = OrbitalFrame.advance(nb, here, new double[] {7_000, 0, 0}, earth.body().id(), T0, DT, true);
        assertArrayEquals(new double[3], a.frameVelocity(), "the warp bubble drops out at rest relative to the body");
    }

    @Test
    void krakensbaneMovesOnlyTheExcessAndKeepsTheSum() {
        Vector3d v = new Vector3d(0, 0, ShipFlightModel.KRAKENSBANE_SPEED + 12.5);
        Vector3d sum = new Vector3d(v);
        Vector3d moved = ShipFlightModel.krakensbane(v);
        assertEquals(ShipFlightModel.KRAKENSBANE_SPEED, v.length(), 1e-9);
        assertEquals(0, new Vector3d(v).add(moved).distance(sum), 1e-9);
        Vector3d slow = new Vector3d(100, 0, 0);
        assertEquals(0, ShipFlightModel.krakensbane(slow).length());
        assertEquals(100, slow.x);
    }
}
