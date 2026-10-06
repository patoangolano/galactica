package dev.galactica.survival;

import org.junit.jupiter.api.Test;

import static dev.galactica.survival.LifeSupportModel.*;
import static org.junit.jupiter.api.Assertions.*;

class LifeSupportModelTest {

    private static final Environment VENUS = new Environment(92.0, 737.0, false);
    private static final Environment MARS = new Environment(0.006, 210.0, false);
    private static final Environment TITAN = new Environment(1.47, 94.0, false);

    @Test
    void earthIsSafeWithOrWithoutSuit() {
        assertTrue(assess(Environment.EARTH, false, 0).safe());
        assertTrue(assess(Environment.EARTH, true, 0).safe());
        assertFalse(assess(Environment.EARTH, true, SUIT_CAPACITY).suitBreathing(), "suit idles in breathable air");
    }

    @Test
    void vacuumSuffocatesAndFreezesUnsuited() {
        Assessment a = assess(Environment.VACUUM, false, SUIT_CAPACITY);
        assertTrue(a.suffocating());
        assertTrue(a.cold());
        assertFalse(a.crushed());
    }

    @Test
    void fullSuitWithOxygenMakesVacuumSafe() {
        Assessment a = assess(Environment.VACUUM, true, 1);
        assertTrue(a.safe());
        assertTrue(a.suitBreathing());
        assertTrue(assess(Environment.VACUUM, true, 0).suffocating(), "an empty suit still keeps the cold out but not the vacuum");
        assertFalse(assess(Environment.VACUUM, true, 0).cold());
    }

    @Test
    void venusCrushesAndBurnsUnsuited() {
        Assessment a = assess(VENUS, false, 0);
        assertTrue(a.crushed());
        assertTrue(a.hot());
        assertTrue(a.suffocating());
        assertTrue(assess(VENUS, true, 100).safe());
    }

    @Test
    void marsIsThinButNotCold() {
        Assessment a = assess(MARS, false, 0);
        assertTrue(a.suffocating());
        assertFalse(a.cold(), "210 K is above the 200 K limit");
        assertTrue(assess(TITAN, false, 0).cold());
        assertFalse(canRecharge(MARS));
        assertTrue(canRecharge(Environment.EARTH));
    }

    @Test
    void suitDrainsOneSecondPerSecondAndPullsFromTanksInOrder() {
        int[] tanks = {5, TANK_CAPACITY};
        int suit = breatheOneSecond(SUIT_CAPACITY, tanks);
        assertEquals(SUIT_CAPACITY, suit);
        assertArrayEquals(new int[]{4, TANK_CAPACITY}, tanks);
        for (int i = 0; i < 10; i++) suit = breatheOneSecond(suit, tanks);
        assertEquals(SUIT_CAPACITY, suit);
        assertArrayEquals(new int[]{0, TANK_CAPACITY - 6}, tanks);
    }

    @Test
    void reserveIsConservedWhileBreathing() {
        int[] tanks = {TANK_CAPACITY, 100};
        int suit = 50;
        int start = reserve(suit, tanks);
        for (int s = 0; s < 1000; s++) suit = breatheOneSecond(suit, tanks);
        assertEquals(start - 1000, reserve(suit, tanks));
    }

    @Test
    void suitAloneLastsItsCapacity() {
        int[] none = {};
        int suit = SUIT_CAPACITY, seconds = 0;
        while (suit > 0) {
            suit = breatheOneSecond(suit, none);
            seconds++;
        }
        assertEquals(SUIT_CAPACITY, seconds);
    }

    @Test
    void rechargeFillsSuitThenTanksOneAtATime() {
        int[] tanks = {0, 0};
        int suit = rechargeOneSecond(0, tanks);
        assertEquals(SUIT_RECHARGE_PER_SECOND, suit);
        assertArrayEquals(new int[]{TANK_RECHARGE_PER_SECOND, 0}, tanks);
        for (int s = 0; s < TANK_CAPACITY / TANK_RECHARGE_PER_SECOND; s++) suit = rechargeOneSecond(suit, tanks);
        assertEquals(SUIT_CAPACITY, suit);
        assertEquals(TANK_CAPACITY, tanks[0]);
        assertEquals(TANK_RECHARGE_PER_SECOND, tanks[1]);
    }

    @Test
    void environmentRejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new Environment(-1, 300, true));
        assertTrue(Environment.VACUUM.isVacuum());
        assertFalse(Environment.EARTH.isVacuum());
    }
}
