package dev.galactica.map;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.JumpTarget;
import dev.galactica.net.JumpTargetRules;
import dev.galactica.net.SetJumpTargetPayload;
import dev.galactica.star.Star;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JumpTargetTest {
    private static final Galaxy GALAXY = new Galaxy(Frames.GALAXY_SEED);
    private static final Star SOL = StarCatalog.SOL;

    @Test
    void costIsOneExoticPerStartedLightYear() {
        assertEquals(3.26156, JumpTarget.LY_PER_PC, 1e-5);
        Star proxima = StarCatalog.byName("Proxima Centauri");
        // Proxima is 1.302 pc = 4.25 ly from the Sun: 5 units.
        assertEquals(5, JumpTarget.exoticCost(proxima.distanceTo(SOL.x(), SOL.y(), SOL.z())));
        assertEquals(4, JumpTarget.exoticCost(4.0 / JumpTarget.LY_PER_PC), "an exact 4 ly is 4, not 5");
        assertEquals(1, JumpTarget.exoticCost(0.01));
        assertEquals(0, JumpTarget.exoticCost(0));
    }

    @Test
    void rangeIsCappedByTheDriveTier() {
        assertEquals(0, JumpTarget.rangeLy(0));
        assertEquals(6, JumpTarget.rangeLy(6));
        assertEquals(10, JumpTarget.rangeLy(64));
        assertEquals(0, JumpTarget.rangeLy(-3));
    }

    @Test
    void catalogStarsResolveById() {
        Star sirius = StarCatalog.byName("Sirius A");
        JumpTarget t = JumpTargetRules.validate(GALAXY, sirius.id(), new SectorKey(0, 0, 0), SetJumpTargetPayload.CATALOG,
                SOL.x(), SOL.y(), SOL.z());
        assertNotNull(t);
        assertEquals("Sirius A", t.name());
        assertEquals(sirius.x(), t.xPc(), 0);
        assertNull(JumpTargetRules.validate(GALAXY, -999, new SectorKey(0, 0, 0), SetJumpTargetPayload.CATALOG,
                SOL.x(), SOL.y(), SOL.z()), "unknown catalog id");
    }

    @Test
    void proceduralStarsMustMatchTheirAddress() {
        SectorKey k = StarCatalog.SOL_SECTOR.offset(3, -2, 0);
        int n = GALAXY.starCount(k);
        assertTrue(n > 5, "a disk sector near the Sun holds stars");
        int index = n - 1; // last index: a luminous-stream star if the sector has any
        Star s = GALAXY.star(k, index);
        JumpTarget t = JumpTargetRules.validate(GALAXY, s.id(), k, index, SOL.x(), SOL.y(), SOL.z());
        assertNotNull(t);
        assertEquals(s.name(), t.name());
        assertEquals(s.z(), t.zPc(), 0);

        assertNull(JumpTargetRules.validate(GALAXY, s.id() ^ 1, k, index, SOL.x(), SOL.y(), SOL.z()), "wrong id");
        assertNull(JumpTargetRules.validate(GALAXY, s.id(), k, n, SOL.x(), SOL.y(), SOL.z()), "index past the end");
        assertNull(JumpTargetRules.validate(GALAXY, s.id(), k.offset(1, 0, 0), index, SOL.x(), SOL.y(), SOL.z()), "wrong sector");
        assertNull(JumpTargetRules.validate(GALAXY, s.id(), k, SetJumpTargetPayload.CLEAR, SOL.x(), SOL.y(), SOL.z()));
    }

    @Test
    void targetsBeyondOneKiloparsecAreRefused() {
        Star deneb = StarCatalog.byName("Deneb");          // 802 pc
        Star etaCar = StarCatalog.byName("Eta Carinae");   // 2.3 kpc
        assertNotNull(JumpTargetRules.validate(GALAXY, deneb.id(), new SectorKey(0, 0, 0), SetJumpTargetPayload.CATALOG,
                SOL.x(), SOL.y(), SOL.z()));
        assertNull(JumpTargetRules.validate(GALAXY, etaCar.id(), new SectorKey(0, 0, 0), SetJumpTargetPayload.CATALOG,
                SOL.x(), SOL.y(), SOL.z()));
        // A real star in a far sector is rejected before its sector is even generated.
        SectorKey far = SectorKey.containing(SOL.x() - 3000, SOL.y(), SOL.z());
        Star s = GALAXY.star(far, 0);
        assertNull(JumpTargetRules.validate(GALAXY, s.id(), far, 0, SOL.x(), SOL.y(), SOL.z()));
        assertNotNull(JumpTargetRules.validate(GALAXY, s.id(), far, 0, s.x() + 10, s.y(), s.z()), "fine from next door");
    }
}
