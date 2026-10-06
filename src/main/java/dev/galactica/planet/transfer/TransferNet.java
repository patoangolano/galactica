package dev.galactica.planet.transfer;

import dev.galactica.Galactica;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

/** Common registration of the seamless-transfer packets and the plasma ember particle. */
public final class TransferNet {
    private TransferNet() {}

    /**
     * Soft, additive plasma ember. Always shown (long-range flag), since an entry is seen from far below.
     * The client provider lives in {@code client.planet.TransferFx}.
     */
    public static final SimpleParticleType PLASMA_EMBER = FabricParticleTypes.simple(true);

    private static boolean done;

    /** Idempotent; called from {@code PlanetEntry.init()}. */
    public static void init() {
        if (done) return;
        done = true;
        PayloadTypeRegistry.clientboundPlay().register(TransferPreparePayload.TYPE, TransferPreparePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlasmaStatePayload.TYPE, PlasmaStatePayload.CODEC);
        Registry.register(BuiltInRegistries.PARTICLE_TYPE, Galactica.id("plasma_ember"), PLASMA_EMBER);
    }
}
