package dev.galactica.survival;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the owning client needs for the life-support HUD: the server-side environment (the client cannot
 * evaluate the planet provider) and the hazards currently applied. Synced to its owner only, never saved.
 *
 * @param reserveO2 suit plus tank O₂, seconds
 * @param flags     bit set of the {@code F_*} constants
 */
public record LifeSupportStatus(int suitO2, int reserveO2, float pressureBar, float tempK, int flags) {
    public static final int F_BREATHABLE = 1, F_FULL_SUIT = 1 << 1, F_SUIT_BREATHING = 1 << 2, F_SUFFOCATING = 1 << 3,
            F_COLD = 1 << 4, F_HOT = 1 << 5, F_CRUSHED = 1 << 6, F_CABIN = 1 << 7;

    public static final StreamCodec<ByteBuf, LifeSupportStatus> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LifeSupportStatus::suitO2,
            ByteBufCodecs.VAR_INT, LifeSupportStatus::reserveO2,
            ByteBufCodecs.FLOAT, LifeSupportStatus::pressureBar,
            ByteBufCodecs.FLOAT, LifeSupportStatus::tempK,
            ByteBufCodecs.VAR_INT, LifeSupportStatus::flags,
            LifeSupportStatus::new);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public static int flags(Environment env, LifeSupportModel.Assessment a, boolean fullSuit, boolean cabin) {
        int f = 0;
        if (env.breathable()) f |= F_BREATHABLE;
        if (fullSuit) f |= F_FULL_SUIT;
        if (a.suitBreathing()) f |= F_SUIT_BREATHING;
        if (a.suffocating()) f |= F_SUFFOCATING;
        if (a.cold()) f |= F_COLD;
        if (a.hot()) f |= F_HOT;
        if (a.crushed()) f |= F_CRUSHED;
        if (cabin) f |= F_CABIN;
        return f;
    }
}
