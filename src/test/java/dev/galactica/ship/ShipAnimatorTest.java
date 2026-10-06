package dev.galactica.ship;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.galactica.ship.ShipAnimator.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipAnimatorTest {
    @Test
    void gearTravelTakesItsFullTime() {
        float g = 1F;
        int ticks = 0;
        while (g > 0F && ticks < 1000) {
            g = approach(g, 0F, 0.05F, GEAR_SECONDS);
            ticks++;
        }
        assertEquals(Math.round(GEAR_SECONDS / 0.05F), ticks);
        assertEquals(0.5F, approach(0.5F, 0.5F, 0.05F, GEAR_SECONDS));
    }

    @Test
    void easingIsMonotoneAndPinned() {
        assertEquals(0F, ease(0F));
        assertEquals(1F, ease(1F));
        assertEquals(0.5F, ease(0.5F), 1e-6);
        assertEquals(1F, ease(3F));
        float prev = 0F;
        for (int i = 1; i <= 20; i++) {
            float e = ease(i / 20F);
            assertTrue(e >= prev);
            prev = e;
        }
    }

    @Test
    void gearHasHysteresis() {
        assertTrue(gearDown(false, true, 50), "on the ground the gear is down");
        assertTrue(gearDown(false, false, GEAR_DOWN_HEIGHT - 1));
        assertFalse(gearDown(true, false, GEAR_UP_HEIGHT + 1));
        assertFalse(gearDown(true, false, Double.POSITIVE_INFINITY), "nothing below: gear up");
        double between = (GEAR_DOWN_HEIGHT + GEAR_UP_HEIGHT) / 2;
        assertTrue(gearDown(true, false, between));
        assertFalse(gearDown(false, false, between));
    }

    @Test
    void canopyOpensOnlyWhenEmpty() {
        assertTrue(canopyOpen(0));
        assertFalse(canopyOpen(1));
        assertFalse(canopyOpen(2));
    }

    @Test
    void gimbalOpposesTheTurnWithinItsRangeAndSlewLimit() {
        assertEquals(0F, gimbalTarget(0F), 1e-9F);
        assertTrue(gimbalTarget(2F) < 0F && gimbalTarget(-2F) > 0F);
        assertEquals(-GIMBAL_MAX_DEG, gimbalTarget(100F));
        assertEquals(GIMBAL_MAX_DEG, gimbalTarget(-100F));
        assertEquals(GIMBAL_SLEW_PER_TICK, gimbalStep(0F, 8F));
        assertEquals(1F, gimbalStep(0.5F, 1F));
    }

    @Test
    void rcsMaskBits() {
        assertEquals(0, rcsMask(0, 0, 0));
        assertEquals(RCS_UP | RCS_YAW_RIGHT | RCS_ROLL_LEFT, rcsMask(1, 1, -1));
        assertEquals(RCS_DOWN | RCS_YAW_LEFT | RCS_ROLL_RIGHT, rcsMask(-1, -1, 1));
    }

    /** Net force and torque (about the hull centre) of all jets answering one command; force = −exhaust direction. */
    private static double[] wrench(int command) {
        double[] w = new double[6];
        int n = 0;
        for (RcsJet j : JETS) {
            if (j.command() != command) continue;
            n++;
            double fx = -j.dx(), fy = -j.dy(), fz = -j.dz();
            assertEquals(1.0, Math.sqrt(fx * fx + fy * fy + fz * fz), 1e-6, "unit direction");
            w[0] += fx;
            w[1] += fy;
            w[2] += fz;
            w[3] += j.y() * fz - j.z() * fy;
            w[4] += j.z() * fx - j.x() * fz;
            w[5] += j.x() * fy - j.y() * fx;
        }
        assertTrue(n >= 2, "command " + command + " has a jet pair");
        return w;
    }

    @Test
    void rcsJetsPushTheRightWay() {
        assertTrue(wrench(RCS_UP)[1] > 0, "lift fires downward");
        assertTrue(wrench(RCS_DOWN)[1] < 0);
        // Body frame: +Y up, +Z nose, +X port. A positive turn about +Y swings the nose to port, so "nose right"
        // (to starboard, −X) is a negative Y torque.
        assertTrue(wrench(RCS_YAW_RIGHT)[4] < 0);
        assertTrue(wrench(RCS_YAW_LEFT)[4] > 0);
        // A positive turn about +Z (the nose) drops the starboard (−X) wing: roll right.
        assertTrue(wrench(RCS_ROLL_RIGHT)[5] > 0);
        assertTrue(wrench(RCS_ROLL_LEFT)[5] < 0);
        double[] yaw = wrench(RCS_YAW_RIGHT);
        assertEquals(0, yaw[0], 1e-9, "yaw couple has no net side force");
    }

    @Test
    void anglesMatchTheGeneratedGeckoLibAnimations() throws Exception {
        String gen = Files.readString(Path.of("assets-src/ship/gen_starship.py"));
        Matcher m = Pattern.compile("GEAR_FRONT_FOLD, GEAR_MAIN_FOLD, CANOPY_OPEN = ([-0-9.]+), ([-0-9.]+), ([-0-9.]+)").matcher(gen);
        assertTrue(m.find(), "angle line in gen_starship.py");
        assertEquals(GEAR_FRONT_FOLD_DEG, Float.parseFloat(m.group(1)));
        assertEquals(GEAR_MAIN_FOLD_DEG, Float.parseFloat(m.group(2)));
        assertEquals(CANOPY_OPEN_DEG, Float.parseFloat(m.group(3)));
        Matcher t = Pattern.compile("GEAR_SECONDS, CANOPY_SECONDS = ([0-9.]+), ([0-9.]+)").matcher(gen);
        assertTrue(t.find());
        assertEquals(GEAR_SECONDS, Float.parseFloat(t.group(1)));
        assertEquals(CANOPY_SECONDS, Float.parseFloat(t.group(2)));
    }
}
