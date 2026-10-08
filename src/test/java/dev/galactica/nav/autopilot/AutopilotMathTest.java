package dev.galactica.nav.autopilot;

import dev.galactica.nav.OrbitalFrame;
import dev.galactica.science.Units;
import dev.galactica.ship.ShipFlightModel;
import dev.galactica.system.Body;
import dev.galactica.system.SolarSystem;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutopilotMathTest {
    private static Body body(String name) {
        for (Body p : SolarSystem.planets()) {
            if (p.name().equals(name)) return p;
            for (Body m : p.moons()) if (m.name().equals(name)) return m;
        }
        throw new AssertionError(name);
    }

    private static double len(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    @Test
    void parkingAltitudesClearTheEntryShell() {
        for (String n : new String[] {"Mars", "Earth", "Moon", "Venus", "Jupiter"}) {
            Body b = body(n);
            double alt = AutopilotMath.orbitAltitude(b);
            assertTrue(alt >= 2.5 * AutopilotMath.entryAltitude(b) - 1e-6, n);
            assertTrue(alt >= 0.15 * b.radiusMetres() - 1e-6, n);
        }
        assertEquals(AutopilotMath.AIRLESS_ENTRY_M, AutopilotMath.entryAltitude(body("Moon")), "airless: 100 km");
        assertTrue(AutopilotMath.entryAltitude(body("Mars")) > 50e3 && AutopilotMath.entryAltitude(body("Mars")) < 150e3, "Mars ~95 km");
    }

    @Test
    void landingIsForSolidBodiesTheRcsCanHoverOn() {
        assertTrue(AutopilotMath.landable(body("Mars")));
        assertTrue(AutopilotMath.landable(body("Moon")));
        assertFalse(AutopilotMath.landable(body("Earth")), "9.8 m/s² is beyond 0.6 × the 10 m/s² RCS");
        assertFalse(AutopilotMath.landable(body("Jupiter")), "no surface");
    }

    @Test
    void circularVelocityIsTangentialInTheCurrentPlane() {
        double mu = Units.G * body("Mars").massKg();
        double[] r = {4.0e6, 1.0e6, 0.5e6};
        double[] v = {-100, 300, 50};
        double[] w = AutopilotMath.circularVelocity(r, v, mu, new double[] {0, 0, 1});
        assertEquals(Math.sqrt(mu / len(r)), len(w), 1e-6 * len(w));
        assertEquals(0, (r[0] * w[0] + r[1] * w[1] + r[2] * w[2]) / (len(r) * len(w)), 1e-9, "perpendicular to r");
        double[] h = AutopilotMath.cross(r, v), hw = AutopilotMath.cross(r, w);
        assertTrue(AutopilotMath.dot(h, hw) > 0.999 * len(h) * len(hw), "same plane and sense as the current motion");
        // At rest: the fallback plane, still a proper circular velocity.
        double[] w0 = AutopilotMath.circularVelocity(r, new double[3], mu, new double[] {0, 0, 1});
        assertEquals(Math.sqrt(mu / len(r)), len(w0), 1e-6 * len(w0));
        assertEquals(0, w0[2], 1e-9, "perpendicular to galactic north");
    }

    @Test
    void descentSpeedFallsAsTheGroundNears() {
        assertEquals(250, AutopilotMath.descentSpeed(15e3, 0, 3.7), 1e-9, "held to 250 m/s near the entry switch");
        assertTrue(AutopilotMath.descentSpeed(500e3, 0, 3.7) > 2_000, "fast from orbit altitude");
        assertEquals(20, AutopilotMath.descentSpeed(10, 0, 3.7));
        assertTrue(AutopilotMath.descentSpeed(800, 0, 3.7) < AutopilotMath.descentSpeed(1500, 0, 3.7));
        assertEquals(-2.5, AutopilotMath.touchdownSpeed(5));
        assertEquals(-60, AutopilotMath.touchdownSpeed(5000));
    }

    @Test
    void steeringSignsMatchTheShipControls() {
        Quaterniond level = new Quaterniond();
        double[] right = AutopilotMath.steer(level, new Vector3d(-1, 0, 1), 1);
        assertTrue(right[0] > 0, "pilot's right is −X: yaw positive (nose right)");
        double[] down = AutopilotMath.steer(level, new Vector3d(0, -1, 1), 1);
        assertTrue(down[1] > 0, "pitch positive is nose down");
        double[] behind = AutopilotMath.steer(level, new Vector3d(0, 0, -1), 1);
        assertEquals(AutopilotMath.MAX_STEER_DEG, Math.abs(behind[0]), 1e-9, "dead astern: full-rate turn");
    }

    @Test
    void steeringConvergesThroughTheFlightModel() {
        Vector3d[] wants = {new Vector3d(0, 0, -1), new Vector3d(1, 1, 0), new Vector3d(-0.3, -1, 0.2), new Vector3d(0, 1, 0)};
        for (Vector3d want : wants) {
            Quaterniond q = new Quaterniond().rotateY(0.4).rotateX(-0.2).rotateZ(0.7);
            Vector3d v = new Vector3d();
            int ticks = 0;
            while (AutopilotMath.noseError(q, want) > 1.0 && ticks < 200) {
                double[] t = AutopilotMath.steer(q, want, 0.7);
                ShipFlightModel.step(q, v, new ShipFlightModel.Controls(0, 0, t[0], t[1], 0, false), new Vector3d(), 0, ShipFlightModel.TICK);
                ticks++;
            }
            assertTrue(ticks < 80, "aligned with " + want + " in " + ticks + " ticks");
        }
    }

    @Test
    void levelRollBringsTheWingsLevel() {
        Quaterniond q = new Quaterniond().rotateY(0.8).rotateZ(Math.toRadians(40)); // right wing down 40°
        assertTrue(AutopilotMath.levelRoll(q) < 0, "roll left to level");
        Vector3d v = new Vector3d();
        for (int i = 0; i < 60; i++) {
            double roll = AutopilotMath.levelRoll(q) * ShipFlightModel.KEY_ROLL_RATE * ShipFlightModel.TICK;
            ShipFlightModel.step(q, v, new ShipFlightModel.Controls(0, 0, 0, 0, roll, false), new Vector3d(), 0, ShipFlightModel.TICK);
        }
        Vector3d up = q.transform(new Vector3d(0, 1, 0)), right = q.transform(new Vector3d(-1, 0, 0));
        assertEquals(0, Math.toDegrees(Math.atan2(-right.y, up.y)), 1.2);
    }

    @Test
    void warpTimeFollowsTheGovernor() {
        double max = 10 * Units.PARSEC;
        double earthMars = 0.6 * Units.AU, arrival = 500e3;
        double t = AutopilotMath.warpSeconds(earthMars, arrival, max);
        // Spool (~1.5 s) then the governor: 2 ln(d / 1.5 arrival) ≈ 2 ln(1.2e5) ≈ 23 s.
        assertTrue(t > 15 && t < 40, "Earth to Mars " + t + " s");
        assertTrue(AutopilotMath.warpSeconds(1000 * Units.PARSEC, Units.AU, max) > 100, "a kiloparsec cruises at 10 pc/s");
        assertEquals(0, AutopilotMath.warpSeconds(arrival, arrival, max));
        assertTrue(AutopilotMath.warpSeconds(2 * earthMars, arrival, max) > t, "monotonic");
        assertEquals(2 * Math.sqrt(1e6 / ShipFlightModel.MAIN_ACCEL), AutopilotMath.sublightSeconds(1e6), 1e-9);
        assertEquals(10, AutopilotMath.hyperjumpSeconds(2), 1e-9);
    }

    @Test
    void orbitCheckNeedsANearCircleAboveTheFloor() {
        double mu = Units.G * body("Mars").massKg(), r0 = body("Mars").radiusMetres() + 500e3;
        double[] r = {r0, 0, 0};
        double vc = Math.sqrt(mu / r0);
        assertTrue(AutopilotMath.orbitReached(OrbitalFrame.apsides(r, new double[] {0, vc, 0}, mu), body("Mars").radiusMetres() + 100e3));
        assertFalse(AutopilotMath.orbitReached(OrbitalFrame.apsides(r, new double[] {0, 0.8 * vc, 0}, mu), body("Mars").radiusMetres()),
                "too slow: eccentric");
        assertFalse(AutopilotMath.orbitReached(OrbitalFrame.apsides(r, new double[] {0, vc, 0}, mu), r0 + 1), "below the floor");
        assertFalse(AutopilotMath.orbitReached(OrbitalFrame.apsides(r, new double[] {0, 1.5 * vc, 0}, mu), 0), "escape");
    }

    // ---------------------------------------------------------------- closed-loop simulations of the guidance law

    /**
     * A ship flown by the autopilot's insertion law through the real flight model, with gravity on the frame and the
     * Krakensbane hand-off exactly as in game: starting at rest over Mars at the parking altitude it settles into a
     * near-circular orbit that clears the entry shell, within the server's time limit and the fuel aboard.
     */
    @Test
    void orbitInsertionFromRestReachesAStableOrbit() {
        Body mars = body("Mars");
        double mu = Units.G * mars.massKg(), alt = AutopilotMath.orbitAltitude(mars);
        double[] r = {mars.radiusMetres() + alt, 0, 0};
        Sim s = new Sim(r, new double[3], new Quaterniond().rotateY(1.0));
        int settled = 0, ticks = 0;
        double burned = 0;
        while (settled < 40 && ticks < 20 * 180) {
            double[] v = s.velocity();
            double[] want = AutopilotMath.circularVelocity(s.r, v, mu, new double[] {0, 0, 1});
            burned += s.fly(new double[] {want[0] - v[0], want[1] - v[1], want[2] - v[2]}, mu, mars.radiusMetres());
            double[] aps = OrbitalFrame.apsides(s.r, s.velocity(), mu);
            settled = AutopilotMath.orbitReached(aps, mars.radiusMetres() + 1.05 * AutopilotMath.entryAltitude(mars)) ? settled + 1 : 0;
            ticks++;
        }
        assertTrue(settled >= 40, "settled in orbit after " + ticks / 20 + " s");
        double[] aps = OrbitalFrame.apsides(s.r, s.velocity(), mu);
        assertTrue(AutopilotMath.eccentricity(aps[0], aps[1]) < AutopilotMath.ORBIT_MAX_ECCENTRICITY);
        // Δv ≈ the circular speed (3.3 km/s) plus gravity losses; one hydrogen cell feeds 20 s of burn (1 km/s).
        assertTrue(burned < 10, "hydrogen used " + burned);
        assertTrue(ticks / 20.0 < 150, "within the 180 s limit: " + ticks / 20.0);
    }

    /** The descent law from rest at parking altitude: a controlled vertical fall that reaches the entry shell under 300 m/s. */
    @Test
    void poweredDescentArrivesSlowAndVertical() {
        Body moon = body("Moon");
        double mu = Units.G * moon.massKg(), R = moon.radiusMetres();
        double[] r = {0, R + AutopilotMath.orbitAltitude(moon), 0};
        Sim s = new Sim(r, new double[3], new Quaterniond());
        double maxSpeed = 0;
        int ticks = 0;
        while (len(s.r) - R > 2_000 && ticks < 20 * 600) {
            double[] v = s.velocity();
            double alt = len(s.r) - R;
            double[] want = AutopilotMath.descentVelocity(s.r, alt, 0, mu / (len(s.r) * len(s.r)));
            s.fly(new double[] {want[0] - v[0], want[1] - v[1], want[2] - v[2]}, mu, R);
            maxSpeed = Math.max(maxSpeed, len(s.velocity()));
            ticks++;
        }
        assertTrue(len(s.r) - R <= 2_000, "came down in " + ticks / 20 + " s");
        double[] v = s.velocity();
        assertTrue(len(v) < 300, "at the switch altitude " + len(v) + " m/s");
        double radial = -(s.r[0] * v[0] + s.r[1] * v[1] + s.r[2] * v[2]) / len(s.r);
        assertTrue(radial > 0.97 * len(v), "vertical");
        assertTrue(ticks / 20.0 < 400, "within the server limit: " + ticks / 20.0 + " s");
    }

    /** A point ship in the body's frame: block velocity (capped, as in game) plus the frame velocity it hands off to. */
    private static final class Sim {
        final double[] r;
        final Vector3d block = new Vector3d();
        final Vector3d frame = new Vector3d();
        final Quaterniond q;
        boolean burning;

        Sim(double[] r, double[] v, Quaterniond q) {
            this.r = r;
            this.frame.set(v[0], v[1], v[2]);
            this.q = q;
        }

        double[] velocity() {
            return new double[] {block.x + frame.x, block.y + frame.y, block.z + frame.z};
        }

        /** One tick of the client's powered guidance (AutopilotClient.powered): turn along dv, fire when aligned. Returns hydrogen. */
        double fly(double[] dv, double mu, double radius) {
            double dvm = len(dv);
            Vector3d dir = new Vector3d(dv[0], dv[1], dv[2]);
            if (dvm > 1e-9) dir.normalize();
            double[] turn = AutopilotMath.steer(q, dir, 0.7);
            double err = AutopilotMath.noseError(q, dir);
            burning = burning ? dvm > 1.0 && err < 2 * AutopilotMath.BURN_CONE_DEG
                    : dvm > 1.5 * AutopilotMath.BURN_DONE_MS && err < AutopilotMath.BURN_CONE_DEG;
            // Gravity acts on the frame (OrbitalFrame), not on the hull.
            double[] g = OrbitalFrame.gravity(r, mu, radius);
            frame.add(g[0] * ShipFlightModel.TICK, g[1] * ShipFlightModel.TICK, g[2] * ShipFlightModel.TICK);
            double h = ShipFlightModel.step(q, block, new ShipFlightModel.Controls(burning ? 1 : 0, 0, turn[0], turn[1], 0, false),
                    new Vector3d(), 400, ShipFlightModel.TICK, frame);
            frame.add(ShipFlightModel.krakensbane(block));
            for (int i = 0; i < 3; i++) r[i] += (block.get(i) + frame.get(i)) * ShipFlightModel.TICK;
            return h;
        }
    }
}
