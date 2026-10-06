package dev.galactica.discovery;

import dev.galactica.galaxy.Galaxy;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.sky.Starfield;
import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

/**
 * Pure checks shared by the client's target picker and the server's validation. The client picks
 * within {@link #PICK_DEG} of the crosshair; the server accepts within {@link #ACCEPT_DEG}, which
 * covers network latency and the motion of a planet during it.
 */
public final class ScanRules {
    private ScanRules() {}

    public static final double PICK_DEG = 1.5;
    public static final double ACCEPT_DEG = 5.0;
    /** Farthest resolved star the sky lists (Starfield's luminous shell) plus a sector of slack. */
    public static final double MAX_STAR_PC = Starfield.LUMINOUS_RADIUS_PC + Galaxy.SECTOR_SIZE_PC;
    /**
     * Faintest star accepted: the sky lists stars to V = 7.5 after dust; the server checks the
     * dust-free magnitude, which is never fainter, with 2 magnitudes of slack.
     */
    public static final double LIMIT_MAG = Starfield.LIMITING_MAGNITUDE + 2.0;
    /** Ticks between two accepted scans of one player. */
    public static final int COOLDOWN_TICKS = 10;

    /** Server acceptance cone: {@link #ACCEPT_DEG}, widened to the angular radius plus 2° for a nearby disc. */
    public static double acceptDeg(double radiusMetres, double distanceMetres) {
        double ang = distanceMetres > radiusMetres ? Math.toDegrees(Math.asin(radiusMetres / distanceMetres)) : 90;
        return Math.max(ACCEPT_DEG, ang + 2.0);
    }

    /** Angle in degrees between two (not necessarily unit) vectors. */
    public static double angleDeg(double[] a, double[] b) {
        double na = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double nb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (na == 0 || nb == 0) return 180;
        double c = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    public static double apparentMagnitude(Star s, double distancePc) {
        return s.absoluteMagnitude() + 5 * Math.log10(Math.max(1e-12, distancePc) / 10.0);
    }

    /** A star outside the observer's system is visible to the spectrometer. */
    public static boolean starVisible(Star s, double distancePc) {
        if (s.kind() == StarKind.BLACK_HOLE) return false;
        return distancePc <= MAX_STAR_PC && apparentMagnitude(s, distancePc) <= LIMIT_MAG;
    }

    /** A procedural star's sector is near enough to be worth generating (O(1) rejection of forged addresses). */
    public static boolean sectorInReach(SectorKey k, double px, double py, double pz) {
        double reach = MAX_STAR_PC + Galaxy.SECTOR_SIZE_PC;
        return Math.abs(k.centerX() - px) <= reach && Math.abs(k.centerY() - py) <= reach && Math.abs(k.centerZ() - pz) <= reach;
    }
}
