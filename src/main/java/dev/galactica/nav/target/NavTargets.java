package dev.galactica.nav.target;

import dev.galactica.Galactica;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.JumpTarget;
import dev.galactica.nav.JumpTargets;
import dev.galactica.nav.autopilot.AutopilotServer;
import dev.galactica.net.AutopilotPayload;
import dev.galactica.net.SetNavTargetPayload;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The locked navigation target: a persistent player attachment (kept on death) synced to every client that tracks the
 * player, so the pilot's crew sees the course too. {@link #init()} registers it with its C2S payloads.
 */
public final class NavTargets {
    private NavTargets() {}

    public static final AttachmentType<NavTarget> TYPE = AttachmentRegistry.create(Galactica.id("nav_target"),
            b -> b.persistent(NavTarget.CODEC)
                    .copyOnDeath()
                    .syncWith(NavTarget.STREAM_CODEC, AttachmentSyncPredicate.all()));

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(SetNavTargetPayload.TYPE, SetNavTargetPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AutopilotPayload.TYPE, AutopilotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetNavTargetPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> handle(ctx.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(AutopilotPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> AutopilotServer.handle(ctx.player(), payload)));
        AutopilotServer.init();
    }

    public static @Nullable NavTarget get(Player p) {
        return p.getAttached(TYPE);
    }

    static void handle(ServerPlayer p, SetNavTargetPayload payload) {
        if (payload.isClear()) {
            clear(p);
            return;
        }
        NavTarget.Kind kind = NavTarget.Kind.values()[Math.floorMod(payload.kind(), NavTarget.Kind.values().length)];
        NavTarget t = NavTargetRules.validate(Galactica.GALAXY, kind, payload.starId(), payload.sector(), payload.index(),
                payload.bodyId(), payload.deepSkyId());
        if (t == null) {
            p.sendSystemMessage(Component.translatable("nav.galactica.target.refused"));
            return;
        }
        lock(p, t);
    }

    /** Stores the target, and for a star within the map's selection limit also the hyperdrive's (J) target. */
    public static void lock(ServerPlayer p, NavTarget t) {
        p.setAttached(TYPE, t);
        if (t.kind() == NavTarget.Kind.STAR) {
            GalacticPos here = JumpTargets.galacticPosition(p);
            if (t.distancePc(here.xPc(), here.yPc(), here.zPc()) <= JumpTarget.MAX_SELECT_PC) {
                p.setAttached(JumpTargets.TYPE, new JumpTarget(t.name(), t.xPc(), t.yPc(), t.zPc()));
            }
        }
        p.sendOverlayMessage(Component.translatable("nav.galactica.target.locked", t.name()));
    }

    public static void clear(ServerPlayer p) {
        p.removeAttached(TYPE);
        AutopilotServer.disengage(p, "hud.galactica.autopilot.off.no_target");
    }
}
