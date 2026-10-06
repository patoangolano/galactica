package dev.galactica.planet.transfer;

import dev.galactica.Galactica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C, every tick a rider's craft is in an atmosphere fast enough to heat: the instantaneous entry plasma
 * state computed on the server ({@link EntryAerothermo#plasma}). The client holds the glow with thermal
 * inertia, so a missed packet or the dimension switch never makes it blink.
 *
 * @param intensity   0..1 plasma strength (Sutton–Graves heat flux, logarithmic)
 * @param temperature radiative-equilibrium wall temperature, K
 * @param mach        flight Mach number (bow-shock shape)
 * @param flowX       unit direction of the craft's ground-relative velocity, current dimension's block axes
 * @param flowY       idem
 * @param flowZ       idem
 * @param speed       ground-relative speed, m/s
 */
public record PlasmaStatePayload(float intensity, float temperature, float mach, float flowX, float flowY, float flowZ,
                                 float speed) implements CustomPacketPayload {

    public static final Type<PlasmaStatePayload> TYPE = new Type<>(Galactica.id("plasma_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlasmaStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, PlasmaStatePayload::intensity,
            ByteBufCodecs.FLOAT, PlasmaStatePayload::temperature,
            ByteBufCodecs.FLOAT, PlasmaStatePayload::mach,
            ByteBufCodecs.FLOAT, PlasmaStatePayload::flowX,
            ByteBufCodecs.FLOAT, PlasmaStatePayload::flowY,
            ByteBufCodecs.FLOAT, PlasmaStatePayload::flowZ,
            ByteBufCodecs.FLOAT, PlasmaStatePayload::speed,
            PlasmaStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
