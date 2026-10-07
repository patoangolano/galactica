package dev.galactica.client.ship;

import dev.galactica.ship.ShipDimensions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated model and the hand-written dimensions must agree on the scale, and the crew must fit inside. */
class ShipScaleTest {
    /** Vanilla player model: feet hang 0.6 under the attachment point, the head top is 1.875 above the feet. */
    private static final float ATTACH = 0.6F, HEAD_TOP = 1.875F, HEAD_HALF_WIDTH = 0.25F;
    /** Armour layer (the EVA helmet) over the bare head, and the least gap left between helmet and glass. */
    private static final float HELMET = 0.06F, CLEARANCE = 0.1F;

    @Test
    void generatorAndDimensionsAgree() {
        assertEquals(ShipDimensions.SCALE, StarshipGeometry.SCALE, 0F);
    }

    @Test
    void crewHeadsFitUnderTheCanopy() {
        for (var seat : new org.joml.Vector3fc[] {ShipDimensions.PILOT_SEAT, ShipDimensions.PASSENGER_SEAT}) {
            float top = seat.y() - ATTACH + HEAD_TOP + HELMET;
            boolean covered = false;
            for (float[] c : StarshipGeometry.CANOPY) {
                // Every section over the head (half a block deep), not just the one over the hips.
                if (seat.z() + HEAD_HALF_WIDTH >= c[0] && seat.z() - HEAD_HALF_WIDTH <= c[1]) {
                    covered = true;
                    assertTrue(top + CLEARANCE < c[4], "helmet top " + top + " under canopy top " + c[4] + " at z " + seat.z());
                    assertTrue(HEAD_HALF_WIDTH < c[2], "head inside the canopy sides");
                }
            }
            assertTrue(covered, "seat at z " + seat.z() + " lies under the canopy");
        }
    }

    @Test
    void displayFacesTheCrewInsideTheCanopy() {
        float[] s = StarshipGeometry.SCREEN;
        assertTrue(s[0] < s[1] && s[2] < s[3]);
        assertTrue(s[4] > ShipDimensions.PILOT_SEAT.z(), "display ahead of the pilot");
        assertEquals(CockpitDisplay.W / (float) CockpitDisplay.H, (s[1] - s[0]) / (s[3] - s[2]), 0.05F, "display aspect matches the canvas");
    }
}
