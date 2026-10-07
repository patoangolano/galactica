package dev.galactica.client.hud;

import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.OrbitalFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The craft's state relative to the body whose frame it flies in ({@link OrbitalFrame}): altitude, speed, vertical
 * speed and the apsides of its current conic. Velocity is the frame's (synced in the NAV attachment) plus the craft's
 * own block velocity. Client only; shared by the nav HUD and the cockpit display.
 *
 * @param body     dominant body (or star) name
 * @param star     true in the star's frame (heliocentric)
 * @param altitude above the surface (photosphere for a star), metres
 * @param speed    relative to the body, m/s
 * @param vertical radial component of that velocity, m/s, positive away from the body
 * @param periapsis lowest point of the conic above the surface, metres (negative: it meets the surface)
 * @param apoapsis highest point above the surface, metres ({@link Double#POSITIVE_INFINITY} on an escape path)
 */
public record OrbitReadout(String body, boolean star, double altitude, double speed, double vertical, double periapsis, double apoapsis) {

    /** The readout for the local player's craft this frame, or null outside space, in interstellar space or while warping. */
    public static @Nullable OrbitReadout current(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.level.dimension() != dev.galactica.Galactica.SPACE) return null;
        SkyManager mgr = SkyManager.get();
        GalacticPos eye = mgr.viewer();
        NavState nav = mgr.nav();
        if (eye == null || nav == null || nav.speed() > 0) return null;
        double t = nav.timeSeconds();
        OrbitalFrame.Dominant d = OrbitalFrame.dominant(mgr.neighbourhood(), eye, t);
        if (d == null) return null;
        double[] v;
        if (nav.anchor() == d.id()) {
            v = nav.frameVelocity();
        } else if (nav.anchor() == OrbitalFrame.ANCHOR_UNSET) {
            v = new double[3];
        } else {
            // The server re-anchors on its next tick; show the same inertial velocity meanwhile.
            v = OrbitalFrame.handOver(nav.frameVelocity(), OrbitalFrame.anchorVelocity(mgr.neighbourhood(), eye, nav.anchor(), t),
                    OrbitalFrame.anchorVelocity(mgr.neighbourhood(), eye, d.id(), t));
        }
        Entity root = mc.player.getRootVehicle();
        double[] own = Frames.mcToGalactic((root.getX() - root.xo) * 20, (root.getY() - root.yo) * 20, (root.getZ() - root.zo) * 20);
        for (int i = 0; i < 3; i++) v[i] += own[i];
        double[] r = eye.metresFrom(d.pos());
        double rm = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double vertical = rm > 0 ? (r[0] * v[0] + r[1] * v[1] + r[2] * v[2]) / rm : 0;
        double[] apsides = OrbitalFrame.apsides(r, v, d.mu());
        return new OrbitReadout(d.name(), d.isStar(), rm - d.radius(), speed, vertical, apsides[0] - d.radius(), apsides[1] - d.radius());
    }

    /** True when the conic is closed and clears the surface. */
    public boolean orbiting() {
        return periapsis > 0 && Double.isFinite(apoapsis);
    }

    public static String speed(double ms) {
        double a = Math.abs(ms);
        return a < 1_000 ? String.format(Locale.ROOT, "%.0f m/s", ms) : String.format(Locale.ROOT, "%.2f km/s", ms / 1e3);
    }

    /** Apsis altitude as text: kilometres, "escape" or (below the surface) "impact". */
    public static String apsis(double m) {
        if (!Double.isFinite(m)) return "escape";
        if (m < 0) return "impact";
        return m < 1e9 ? String.format(Locale.ROOT, "%,.0f km", m / 1e3) : String.format(Locale.ROOT, "%.2f AU", m / dev.galactica.science.Units.AU);
    }
}
