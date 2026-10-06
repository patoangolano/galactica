package dev.galactica.discovery;

import dev.galactica.science.spectra.Kinematics;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.SystemFrame;

/**
 * Velocities of bodies for the Doppler shift, in the same galactic frame as their positions
 * ({@code BodyFrame.offsetFromStar}): the host star's space velocity plus the Keplerian orbital
 * velocity of the planet, plus the moon's around it. Orbital speeds (30 km/s for the Earth) are
 * comparable to stellar peculiar motions, so scanning Venus from Earth shows a real shift.
 */
public final class ScanPhysics {
    private ScanPhysics() {}

    /** Orbital velocity of a planet (moon == null) or moon relative to the star at time t, galactic m/s. */
    public static double[] orbitalVelocity(Star star, Body planet, Body moon, double t) {
        double[] buf = new double[3], out = new double[3], g = new double[3];
        planet.orbit().velocityAt(t, buf);
        SystemFrame.toGalactic(star, buf[0], buf[1], buf[2], out);
        if (moon != null) {
            moon.orbit().velocityAt(t, buf);
            SystemFrame.toGalactic(star, buf[0], buf[1], buf[2], g);
            for (int i = 0; i < 3; i++) out[i] += g[i];
        }
        return out;
    }

    /** Space velocity of a body, km/s. */
    public static double[] bodyVelocity(Star star, Body planet, Body moon, double t) {
        return Kinematics.plusMetresPerSecond(Kinematics.starVelocity(star), orbitalVelocity(star, planet, moon, t));
    }
}
