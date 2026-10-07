package dev.galactica.nav.autopilot;

import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.OrbitalFrame;
import dev.galactica.nav.target.TargetGeometry;

/**
 * A craft's position and velocity relative to the target body, galactic frame, metres and m/s: the frame velocity the
 * NAV state carries (relative to its anchor, {@link OrbitalFrame}), plus the craft's own block velocity, re-expressed
 * from the anchor to the target body with heliocentric body velocities (the hand-over rule). Used the same way by the
 * server (arrival check) and the pilot's client (guidance), so both judge the same orbit.
 */
public final class AutopilotKinematics {
    private AutopilotKinematics() {}

    /** {r, v}: craft minus body. {@code ownGalactic} is the craft's block velocity in galactic m/s. */
    public static double[][] relative(Neighbourhood nb, GalacticPos here, NavState nav, double[] ownGalactic,
                                      TargetGeometry.Resolved target, double t) {
        double[] r = here.metresFrom(target.pos());
        long anchor = nav.anchor();
        double[] v;
        if (anchor == OrbitalFrame.ANCHOR_UNSET) {
            // The next space tick adopts the dominant body at rest.
            OrbitalFrame.Dominant d = OrbitalFrame.dominant(nb, here, t);
            anchor = d == null ? OrbitalFrame.ANCHOR_STAR : d.id();
            v = new double[3];
        } else {
            v = nav.frameVelocity();
        }
        double[] va = OrbitalFrame.anchorVelocity(nb, here, anchor, t);
        double[] vb = target.body() == null || target.star() == null ? new double[3]
                : TargetGeometry.bodyVelocity(target.star(), target.parent() != null ? target.parent() : target.body(),
                target.parent() != null ? target.body() : null, t);
        for (int i = 0; i < 3; i++) v[i] += ownGalactic[i] + va[i] - vb[i];
        return new double[][] {r, v};
    }
}
