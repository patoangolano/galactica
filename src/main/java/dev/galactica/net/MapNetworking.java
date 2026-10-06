package dev.galactica.net;

import dev.galactica.Galactica;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.JumpTarget;
import dev.galactica.nav.JumpTargets;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Common-side registration of the Galaxy Map payloads and the server handler. */
public final class MapNetworking {
    private MapNetworking() {}

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(SetJumpTargetPayload.TYPE, SetJumpTargetPayload.CODEC);
        // Fabric re-queues the handler onto the server thread, so touching the player here is safe.
        ServerPlayNetworking.registerGlobalReceiver(SetJumpTargetPayload.TYPE, (payload, ctx) -> handle(ctx.player(), payload));
    }

    static void handle(ServerPlayer p, SetJumpTargetPayload payload) {
        if (payload.isClear()) {
            p.removeAttached(JumpTargets.TYPE);
            return;
        }
        GalacticPos pos = JumpTargets.galacticPosition(p);
        JumpTarget t = JumpTargetRules.validate(Galactica.GALAXY, payload.starId(), payload.sector(), payload.index(),
                pos.xPc(), pos.yPc(), pos.zPc());
        if (t == null) {
            p.sendSystemMessage(Component.translatable("map.galactica.target.refused"));
            return;
        }
        p.setAttached(JumpTargets.TYPE, t);
    }
}
