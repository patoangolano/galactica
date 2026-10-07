package dev.galactica.nav.autopilot;

import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.nav.target.TargetGeometry;
import dev.galactica.star.Star;
import org.jspecify.annotations.Nullable;

/**
 * The warp leg the autopilot flies next, decided the same way on the server (arrival checks) and on the pilot's client
 * (where to point and when to let go of the drive). Pure Java.
 *
 * <ul>
 *   <li>A planet or moon of another system: the leg aims at that system's star; once the craft is inside the system
 *       ({@link Neighbourhood#SYSTEM_RADIUS_M}) the next leg aims at the body itself.</li>
 *   <li>A body of the current system: aim at the body, stop at its {@link AutopilotMath#orbitAltitude}.</li>
 *   <li>A star: stop where the hyperdrive would drop out; a deep-sky object: 1.5 radii from its centre.</li>
 * </ul>
 */
public final class AutopilotLegs {
    private AutopilotLegs() {}

    /**
     * @param aim       the point flown at
     * @param radius    its surface radius, metres (the governor and the arrival measure from there)
     * @param arrival   distance from that surface where the leg ends, metres
     * @param last      true when arriving ends the course (or hands over to orbit insertion / descent)
     */
    public record Leg(GalacticPos aim, double radius, double arrival, boolean last) {
        /** Distance from {@code here} to the surface of the aim point, metres. */
        public double surfaceDistance(GalacticPos here) {
            return aim.distanceMetres(here) - radius;
        }

        /** True once the craft is close enough that the leg is over (with the drop-out's coast allowed for). */
        public boolean reached(GalacticPos here) {
            return surfaceDistance(here) <= 2.0 * arrival;
        }
    }

    /** Whether {@code here} is inside the target's own system (always true for a deep-sky target, which has none). */
    public static boolean inTargetSystem(NavTarget t, TargetGeometry.Resolved r, @Nullable Star currentSystem) {
        if (!t.kind().hasStar()) return true;
        return currentSystem != null && r.star() != null && currentSystem.id() == r.star().id();
    }

    public static Leg leg(NavTarget t, TargetGeometry.Resolved r, @Nullable Star currentSystem) {
        if (t.kind() == NavTarget.Kind.DEEP_SKY) {
            return new Leg(r.pos(), r.radius(), AutopilotMath.deepSkyArrivalDistance(r.radius()) - r.radius(), true);
        }
        Star s = r.star();
        double starR = TargetGeometry.starRadius(s);
        GalacticPos sp = Neighbourhood.starPos(s);
        double starArrival = AutopilotMath.starArrivalDistance(s, starR) - starR;
        if (t.kind() == NavTarget.Kind.STAR) return new Leg(sp, starR, starArrival, true);
        if (!inTargetSystem(t, r, currentSystem)) return new Leg(sp, starR, starArrival, false);
        return new Leg(r.pos(), r.radius(), AutopilotMath.orbitAltitude(r.body()), true);
    }

    /**
     * Distance from the surface ahead at which the pilot lets go of the warp: {@link AutopilotMath#WARP_RELEASE_FACTOR}
     * arrival distances, or more when the drive is fast (it coasts ~0.2 s worth of speed while dropping out, which
     * matters toward deep-sky objects, where no governor brakes).
     */
    public static double releaseDistance(double arrival, double warpSpeed) {
        return Math.max(AutopilotMath.WARP_RELEASE_FACTOR * arrival, arrival + 0.3 * warpSpeed);
    }
}
