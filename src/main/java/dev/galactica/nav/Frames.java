package dev.galactica.nav;

import dev.galactica.galaxy.StarCatalog;
import dev.galactica.science.Units;
import dev.galactica.system.SystemFrame;

/**
 * Axis conventions. The galactic frame has z towards the north galactic pole; Minecraft has y up.
 * The mapping galactic (x, y, z) ↔ Minecraft (x, z, −y) is a proper rotation (no mirroring), so the
 * galactic plane is Minecraft's horizontal plane and "up" in space is galactic north.
 */
public final class Frames {
    private Frames() {}

    /** Galaxy seed shared by every world: there is one Milky Way. */
    public static final long GALAXY_SEED = 0x6A1A_C71CL;

    /** 10 pc/s ≈ 1.03 × 10^9 c: crossing the 30 kpc disk takes about 50 minutes at full warp. */
    public static final double DEFAULT_MAX_SPEED = 10.0 * Units.PARSEC;

    /** The Sun, exactly at its catalog position. */
    public static final GalacticPos SOL_POS = GalacticPos.ofParsecs(StarCatalog.SOL.x(), StarCatalog.SOL.y(), StarCatalog.SOL.z());

    /**
     * 100 000 km sunward of Earth at time t (seconds since J2000) — Earth fills ~7° of the sky there.
     * Earth's J2000 ecliptic orbit is rotated onto the galactic frame by {@link SystemFrame}.
     */
    public static GalacticPos nearEarth(double t) {
        var earth = dev.galactica.system.SolarSystem.planets().get(2);
        double[] p = new double[3];
        earth.orbit().positionAt(t, p);
        double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        double k = (r - 1.0e8) / r;
        double[] g = new double[3];
        SystemFrame.toGalactic(StarCatalog.SOL, p[0] * k, p[1] * k, p[2] * k, g);
        return SOL_POS.plusMetres(g[0], g[1], g[2]);
    }

    public static double[] mcToGalactic(double mx, double my, double mz) {
        return new double[] {mx, -mz, my};
    }

    public static double[] galacticToMc(double gx, double gy, double gz) {
        return new double[] {gx, gz, -gy};
    }

    /** True galactic position of a player standing at block coordinates (mx, my, mz) of their space frame. */
    public static GalacticPos playerPos(NavState nav, double mx, double my, double mz) {
        double[] g = mcToGalactic(mx, my, mz);
        return nav.origin().plusMetres(g[0] * Units.METRES_PER_BLOCK, g[1] * Units.METRES_PER_BLOCK, g[2] * Units.METRES_PER_BLOCK);
    }
}
