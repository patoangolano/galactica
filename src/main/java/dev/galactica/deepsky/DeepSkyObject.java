package dev.galactica.deepsky;

import java.util.List;
import java.util.function.Function;

/**
 * A nebula, cluster or galaxy. Position is galactocentric (pc, {@code GalaxyModel} frame) and
 * {@link #radiusPc} bounds all of its volumes. The volumes ({@link Blob}s) are built lazily and
 * deterministically from {@link #seed}, so nothing is stored.
 */
public class DeepSkyObject {
    public final String id;
    public final String name;
    public final DeepSkyType type;
    public final double x, y, z;
    public final double radiusPc;
    /** True for real objects from the catalogue (named, translatable), false for procedural ones. */
    public final boolean catalogued;
    public final long seed;
    private final Function<DeepSkyObject, List<Blob>> builder;
    private volatile List<Blob> blobs;

    protected DeepSkyObject(String id, String name, DeepSkyType type, double[] pos, double radiusPc,
                            boolean catalogued, long seed, Function<DeepSkyObject, List<Blob>> builder) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.x = pos[0];
        this.y = pos[1];
        this.z = pos[2];
        this.radiusPc = radiusPc;
        this.catalogued = catalogued;
        this.seed = seed;
        this.builder = builder;
    }

    public static DeepSkyObject of(String id, String name, DeepSkyType type, double[] pos, double radiusPc,
                                   boolean catalogued, long seed, Function<DeepSkyObject, List<Blob>> builder) {
        return new DeepSkyObject(id, name, type, pos, radiusPc, catalogued, seed, builder);
    }

    public double[] position() {
        return new double[] {x, y, z};
    }

    /** Translation key of the object's name for catalogued objects, e.g. {@code deepsky.galactica.m42}. */
    public String translationKey() {
        return catalogued ? "deepsky.galactica." + id : null;
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The object's volumes, built once. */
    public List<Blob> blobs() {
        List<Blob> b = blobs;
        if (b == null) {
            synchronized (this) {
                b = blobs;
                if (b == null) {
                    b = List.copyOf(builder.apply(this));
                    blobs = b;
                }
            }
        }
        return b;
    }

    @Override
    public String toString() {
        return name + " (" + type.key + ")";
    }
}
