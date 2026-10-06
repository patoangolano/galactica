package dev.galactica.nav;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.science.Units;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The star a player has selected for the next hyperjump, set from the Galaxy Map and validated by
 * the server. The position is stored rather than re-derived so the hyperdrive and HUD need no
 * galaxy lookup; it is the star's galactocentric position in parsecs.
 */
public record JumpTarget(String name, double xPc, double yPc, double zPc) {

    /** Light years per parsec (IAU: 1 pc = 648 000/π AU, 1 ly = Julian year × c). */
    public static final double LY_PER_PC = Units.PARSEC / Units.LIGHT_YEAR;
    /** Hyperdrive tier range, ly (spec §2: range = min(exotic × 1 ly, 10 ly)). */
    public static final double TIER_RANGE_LY = 10.0;
    /** Farthest selectable target, pc: the map plans ahead, the drive still limits each jump. */
    public static final double MAX_SELECT_PC = 1_000.0;

    public static final Codec<JumpTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(JumpTarget::name),
            Codec.DOUBLE.fieldOf("x").forGetter(JumpTarget::xPc),
            Codec.DOUBLE.fieldOf("y").forGetter(JumpTarget::yPc),
            Codec.DOUBLE.fieldOf("z").forGetter(JumpTarget::zPc)
    ).apply(i, JumpTarget::new));

    public static final StreamCodec<ByteBuf, JumpTarget> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, JumpTarget::name,
            ByteBufCodecs.DOUBLE, JumpTarget::xPc,
            ByteBufCodecs.DOUBLE, JumpTarget::yPc,
            ByteBufCodecs.DOUBLE, JumpTarget::zPc,
            JumpTarget::new);

    public double distancePc(double x, double y, double z) {
        double dx = xPc - x, dy = yPc - y, dz = zPc - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double lightYears(double pc) {
        return pc * LY_PER_PC;
    }

    /**
     * Exotic matter a jump of {@code pc} costs: 1 unit per started light year (spec §2). A tiny
     * tolerance keeps an exact 4.0000000001 ly from rounding to 5 through float noise.
     */
    public static int exoticCost(double pc) {
        return (int) Math.ceil(lightYears(pc) - 1e-9);
    }

    /** Jump range in ly for a given exotic-matter supply. */
    public static double rangeLy(int exotic) {
        return Math.min(Math.max(0, exotic), TIER_RANGE_LY);
    }
}
