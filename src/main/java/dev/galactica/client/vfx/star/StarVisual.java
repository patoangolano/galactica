package dev.galactica.client.vfx.star;

import dev.galactica.star.Star;
import dev.galactica.star.StarKind;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Everything the star shader needs to draw one star's surface, derived once from a
 * {@link Star} by {@link #from(Star)}. Pure data with value semantics: two calls for the same star
 * give equal visuals, so per-star GPU buffers can be keyed by it.
 *
 * @param kind            stellar kind (remnants are drawn as smooth discs, black holes are not drawn)
 * @param teff            effective temperature, K
 * @param radiusSolar     radius, R☉
 * @param logG            log10 surface gravity, cgs
 * @param ageGyr          age, Gyr
 * @param seed            deterministic seed for every random surface feature
 * @param r               blackbody colour, linear sRGB (max channel 1)
 * @param limbA           quadratic limb-darkening coefficient a (V band)
 * @param limbB           quadratic limb-darkening coefficient b (V band)
 * @param smooth          true for compact remnants: no convection, spots or atmosphere
 * @param cellsPerRadius  granulation cells per stellar radius actually drawn
 * @param lifetimeS       displayed granule lifetime, s
 * @param contrast        granulation rms intensity contrast
 * @param superCells      supergranulation cells per radius
 * @param activity        current magnetic activity relative to the mean Sun (cycle included)
 * @param faculae         facular brightening strength
 * @param flareRate       flares per displayed minute (M dwarfs only)
 * @param chromosphere    chromosphere rim strength
 * @param prominence      prominence brightness
 * @param prominences     number of limb prominences drawn
 * @param corona          K-corona brightness at the limb
 * @param coronaIndex     power-law index n of the K-corona, I ∝ r^-n
 * @param streamers       number of coronal streamers
 * @param rotationRate    displayed equatorial angular speed, rad/s
 * @param spots           starspots (at most {@link StarUniforms#MAX_SPOTS})
 */
public record StarVisual(
        StarKind kind, double teff, double radiusSolar, double logG, double ageGyr, long seed,
        float r, float g, float b,
        double limbA, double limbB, boolean smooth,
        double cellsPerRadius, double lifetimeS, double contrast, double superCells,
        double activity, double faculae, double flareRate,
        double chromosphere, double prominence, int prominences,
        double corona, double coronaIndex, int streamers,
        double rotationRate,
        List<Spot> spots) {

    /**
     * A starspot on the rotating surface.
     *
     * @param latitude  heliographic latitude, rad
     * @param longitude longitude at animation time 0, rad (0 = facing the viewer)
     * @param radius    penumbra radius, in stellar radii
     * @param umbra     umbra radius / penumbra radius (Sun ≈ 0.42, Jha et al. 2019)
     */
    public record Spot(double latitude, double longitude, double radius, double umbra) {}

    public StarVisual {
        spots = List.copyOf(spots);
    }

    /** False for black holes: there is no surface to draw. */
    public boolean drawable() {
        return kind != StarKind.BLACK_HOLE;
    }

    /** Builds the visual for a star. Deterministic in the star's ids. */
    public static StarVisual from(Star s) {
        long seed = s.systemSeed() * 0x9E3779B97F4A7C15L ^ s.id();
        SplittableRandom rng = new SplittableRandom(seed);
        double teff = Math.max(1000, s.temperature());
        double radius = Math.max(1e-6, s.radius());
        double logG = StarSurfaceModel.logG(Math.max(1e-3, s.mass()), radius);
        float[] c = StarSurfaceModel.colour(teff);
        boolean remnant = s.kind().isRemnant();

        if (remnant) {
            // Neutron stars: strong light bending makes nearly the whole surface visible and the
            // profile almost flat (Pechenick, Ftaclas & Cohen 1983), so no limb darkening.
            double[] ld = s.kind() == StarKind.NEUTRON_STAR ? new double[] {0, 0} : StarSurfaceModel.limbCoefficients(teff);
            return new StarVisual(s.kind(), teff, radius, logG, s.ageGyr(), seed, c[0], c[1], c[2],
                    ld[0], ld[1], true, 2, 30, 0, 1, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, List.of());
        }

        double[] ld = StarSurfaceModel.limbCoefficients(teff);
        double phys = StarSurfaceModel.granulesPerRadius(radius, teff, logG);
        double cells = StarSurfaceModel.visualCellsPerRadius(phys);
        double life = StarSurfaceModel.visualLifetimeSeconds(StarSurfaceModel.granuleLifetimeSeconds(teff, logG));
        double contrast = StarSurfaceModel.granulationContrast(teff, logG);
        double conv = StarSurfaceModel.convectiveFraction(teff);

        double phase = rng.nextDouble();
        double cycle = StarSurfaceModel.cycleShape(phase);
        double activity = StarSurfaceModel.meanActivity(s.ageGyr(), teff) * cycle;
        double faculae = StarSurfaceModel.clamp(0.3 + 0.5 * activity, 0, 1) * conv;
        double flares = StarSurfaceModel.flareRatePerMinute(teff, activity);

        // Coronae need a dynamo; evolved giants beyond the Linsky–Haisch (1979) dividing line
        // (cooler than ≈ K2 III) have none and show only cool winds.
        boolean coolGiant = logG < 3.5 && teff < 4600;
        double corona = conv * (0.35 + 0.45 * Math.min(1.5, activity) / 1.5) * (coolGiant ? 0.2 : 1.0);
        // Baumbach (1937) K-corona: steep near the limb; at maximum the corona is rounder and more extended.
        double coronaIndex = 6.0 - 2.0 * Math.min(1.0, activity);
        int streamers = (int) Math.round(2 + 5 * Math.min(1.0, activity));
        double chromo = conv * (0.5 + 0.5 * Math.min(1.0, activity));
        int proms = conv > 0.5 ? (int) Math.round(Math.min(4.0, 1 + 3 * Math.min(1.0, activity))) : 0;
        double promBright = conv * 0.8;

        double rot = StarSurfaceModel.visualRotationRate(StarSurfaceModel.rotationPeriodDays(s.ageGyr(), radius, teff));

        List<Spot> spots = new ArrayList<>();
        if (conv > 0.2) {
            int n = (int) Math.min(StarUniforms.MAX_SPOTS, Math.round(activity * 5));
            double belt = StarSurfaceModel.sporerLatitude(phase);
            for (int i = 0; i < n; i++) {
                double hemi = rng.nextBoolean() ? 1 : -1;
                double lat = hemi * (belt + Math.toRadians(rng.nextDouble(-5, 5)));
                double lon = rng.nextDouble(0, 2 * Math.PI);
                double size = 0.025 + 0.045 * rng.nextDouble() * Math.min(1.5, activity) / 1.5;
                spots.add(new Spot(lat, lon, size, 0.36 + 0.1 * rng.nextDouble()));
            }
            // Active M dwarfs and young stars carry large high-latitude spots (Doppler imaging, Strassmeier 2009).
            if ((teff < 4000 || s.ageGyr() < 0.5) && spots.size() < StarUniforms.MAX_SPOTS && activity > 1.0) {
                spots.add(new Spot(Math.toRadians(rng.nextDouble(55, 75)), rng.nextDouble(0, 2 * Math.PI), 0.12, 0.5));
            }
        }
        return new StarVisual(s.kind(), teff, radius, logG, s.ageGyr(), seed, c[0], c[1], c[2],
                ld[0], ld[1], false, cells, life, contrast, Math.max(1.5, cells / 5.0),
                activity, faculae, flares, chromo, promBright, proms, corona, coronaIndex, streamers, rot, spots);
    }
}
