package dev.galactica.nav;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

/** Where a player left from when launching into space, so landing brings them back. */
public record ReturnPoint(Identifier dimension, double x, double y, double z) {
    public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(ReturnPoint::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(ReturnPoint::x),
            Codec.DOUBLE.fieldOf("y").forGetter(ReturnPoint::y),
            Codec.DOUBLE.fieldOf("z").forGetter(ReturnPoint::z)
    ).apply(i, ReturnPoint::new));
}
