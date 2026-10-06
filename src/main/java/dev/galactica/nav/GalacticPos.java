package dev.galactica.nav;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.science.Units;

/**
 * A position anywhere in the galaxy with sub-millimetre precision. Galactocentric metres (~10^21)
 * in one double resolve only ~100 km, and even a 10 pc cell offset resolves just 64 m, so the
 * position is an integer count of 1 AU cells (60 kpc ≈ 1.2 × 10^10 AU, fits a long easily) plus a
 * double offset in metres inside the cell (≤ 1.5 × 10^11 m → ulp ≈ 3 × 10^-5 m).
 * Axes are the galactic frame of {@code GalaxyModel} (z towards the north galactic pole).
 */
public record GalacticPos(long ax, long ay, long az, double ox, double oy, double oz) {

    public static final double CELL = Units.AU;

    public static final Codec<GalacticPos> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("ax").forGetter(GalacticPos::ax),
            Codec.LONG.fieldOf("ay").forGetter(GalacticPos::ay),
            Codec.LONG.fieldOf("az").forGetter(GalacticPos::az),
            Codec.DOUBLE.fieldOf("ox").forGetter(GalacticPos::ox),
            Codec.DOUBLE.fieldOf("oy").forGetter(GalacticPos::oy),
            Codec.DOUBLE.fieldOf("oz").forGetter(GalacticPos::oz)
    ).apply(i, GalacticPos::new));

    /** From galactocentric parsecs. Precision is that of the double input (~1 km at 8 kpc). */
    public static GalacticPos ofParsecs(double x, double y, double z) {
        return new GalacticPos(0, 0, 0, 0, 0, 0).plusMetresAbsolute(x * Units.PARSEC, y * Units.PARSEC, z * Units.PARSEC);
    }

    private GalacticPos plusMetresAbsolute(double mx, double my, double mz) {
        long cx = (long) Math.floor(mx / CELL), cy = (long) Math.floor(my / CELL), cz = (long) Math.floor(mz / CELL);
        return new GalacticPos(cx, cy, cz, mx - cx * CELL, my - cy * CELL, mz - cz * CELL).normalized();
    }

    public double xPc() { return (ax + ox / CELL) * (CELL / Units.PARSEC); }
    public double yPc() { return (ay + oy / CELL) * (CELL / Units.PARSEC); }
    public double zPc() { return (az + oz / CELL) * (CELL / Units.PARSEC); }

    public SectorKey sector() {
        return SectorKey.containing(xPc(), yPc(), zPc());
    }

    /** Moves by a displacement in metres (galactic frame). Any size works; small ones stay exact. */
    public GalacticPos plusMetres(double dx, double dy, double dz) {
        return new GalacticPos(ax, ay, az, ox + dx, oy + dy, oz + dz).normalized();
    }

    /** Displacement in metres from {@code other} to this position; exact (to the offset ulp) when they are close. */
    public double[] metresFrom(GalacticPos other) {
        return new double[] {
                (ax - other.ax) * CELL + (ox - other.ox),
                (ay - other.ay) * CELL + (oy - other.oy),
                (az - other.az) * CELL + (oz - other.oz)};
    }

    public double distanceMetres(GalacticPos other) {
        double[] d = metresFrom(other);
        return Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
    }

    public GalacticPos normalized() {
        long fx = (long) Math.floor(ox / CELL), fy = (long) Math.floor(oy / CELL), fz = (long) Math.floor(oz / CELL);
        if (fx == 0 && fy == 0 && fz == 0) return this;
        return new GalacticPos(ax + fx, ay + fy, az + fz, ox - fx * CELL, oy - fy * CELL, oz - fz * CELL);
    }
}
