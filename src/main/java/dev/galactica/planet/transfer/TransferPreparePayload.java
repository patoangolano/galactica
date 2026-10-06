package dev.galactica.planet.transfer;

import dev.galactica.Galactica;
import dev.galactica.planet.PlanetAddress;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C, sent while a craft approaches a dimension switch (entry from space, or a climb towards take-off).
 * It arms a one-shot client flag so the vanilla level-loading screen is skipped for that one respawn, and
 * tells the transition effect how far away the switch is. Re-sent every few ticks while the approach lasts;
 * the client flag expires on its own if the switch never happens.
 *
 * @param slot      destination surface slot, {@link #OVERWORLD} for Earth, {@link #SPACE} for a take-off
 * @param address   the body being entered or left
 * @param lat       latitude of the (projected) switch point, radians
 * @param lon       longitude of the (projected) switch point, radians
 * @param hSwitch   altitude of the switch, metres
 * @param etaTicks  estimated ticks until the switch (0 = this tick); {@link #DISARM} cancels any armed transfer
 * @param entry     true for space → surface, false for surface → space
 */
public record TransferPreparePayload(int slot, PlanetAddress address, float lat, float lon, float hSwitch,
                                     int etaTicks, boolean entry) implements CustomPacketPayload {

    public static final int OVERWORLD = -1, SPACE = -2;
    /** {@code etaTicks} of a cancel: the approach ended without a switch (pull-up, death, respawn, logout). */
    public static final int DISARM = -1;

    /** A packet that clears the client's arm. */
    public static TransferPreparePayload disarm() {
        return new TransferPreparePayload(SPACE, new PlanetAddress("", 0, 0, 0, 0, 0, -1), 0, 0, 0, DISARM, false);
    }

    /** True for a {@link #disarm()} packet. */
    public boolean isDisarm() {
        return etaTicks < 0;
    }

    public static final Type<TransferPreparePayload> TYPE = new Type<>(Galactica.id("transfer_prepare"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransferPreparePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TransferPreparePayload::slot,
            PlanetAddress.STREAM_CODEC.cast(), TransferPreparePayload::address,
            ByteBufCodecs.FLOAT, TransferPreparePayload::lat,
            ByteBufCodecs.FLOAT, TransferPreparePayload::lon,
            ByteBufCodecs.FLOAT, TransferPreparePayload::hSwitch,
            ByteBufCodecs.VAR_INT, TransferPreparePayload::etaTicks,
            ByteBufCodecs.BOOL, TransferPreparePayload::entry,
            TransferPreparePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
