package dev.galactica.survival;

import dev.galactica.Galactica;
import dev.galactica.content.GalacticaComponents;
import dev.galactica.content.GalacticaItems;
import dev.galactica.content.OxygenTankItem;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server tick of spec §3: oxygen, temperature and pressure for every player.
 *
 * <ul>
 *   <li><b>Oxygen.</b> Once per second the worn suit spends 1 O₂ when it is what the player breathes, then tops
 *   itself up from tanks in the inventory; in breathable air it recharges instead. Without suit air in an
 *   unbreathable environment the vanilla air bar drains as underwater and drowning damage follows.</li>
 *   <li><b>Temperature.</b> Unsuited below 200 K: 1 freeze damage per 2 s, with the powder-snow frost overlay
 *   held just short of "fully frozen" so vanilla does not add its own freeze damage on top. Above 330 K:
 *   1 fire damage per 2 s.</li>
 *   <li><b>Pressure.</b> Unsuited above 10 bar: 1 cramming ("squished") damage per second.</li>
 * </ul>
 * Hazards are dealt with the damage cooldown cleared first: each is an independent environmental process, and
 * vanilla's 10-tick invulnerability window would otherwise drop or shrink a second hazard landing inside it.
 * Creative players see the environment on the HUD but take no hazards, see no hazard warnings and draw no tank
 * oxygen. Spectators carry no status at all.
 */
public final class LifeSupport {
    private LifeSupport() {}

    public static final AttachmentType<LifeSupportStatus> STATUS = AttachmentRegistry.create(Galactica.id("life_support"),
            b -> b.syncWith(LifeSupportStatus.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

    /** Vanilla refills +4 air per tick out of water; −5 leaves the underwater rate of −1 per tick. */
    private static final int VACUUM_AIR_STEP = 5;
    /** Vanilla drowning: at −20 air, reset to 0 and deal 2 damage. */
    private static final int DROWN_AT = -20;
    private static final float DROWN_DAMAGE = 2.0F;
    /** Entity event the client turns into the drowning bubble burst ({@code LivingEntity.handleEntityEvent}). */
    private static final byte DROWN_BUBBLES = 67;
    private static final int HAZARD_FLAGS = LifeSupportStatus.F_SUFFOCATING | LifeSupportStatus.F_COLD
            | LifeSupportStatus.F_HOT | LifeSupportStatus.F_CRUSHED;

    /** Registers the tick and the status attachment. Call once from the main initializer, after the items. */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LifeSupport::tickAll);
    }

    private static void tickAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) tick(p);
    }

    public static void tick(ServerPlayer p) {
        if (p.isSpectator()) {
            if (p.hasAttached(STATUS)) p.removeAttached(STATUS);
            return;
        }
        if (!p.isAlive()) return;
        boolean hazards = !p.isCreative();
        ServerLevel level = p.level();
        Environment env = SurvivalEnvironment.of(p);
        boolean cabin = env == Environment.CABIN;
        boolean fullSuit = GalacticaItems.wearsFullSuit(p);
        ItemStack suit = GalacticaItems.wornSuit(p);
        int suitO2 = suit.isEmpty() ? 0 : OxygenTankItem.oxygen(suit);
        LifeSupportModel.Assessment a = LifeSupportModel.assess(env, fullSuit, suitO2);

        boolean secondTick = p.tickCount % 20 == 0;
        if (secondTick && !suit.isEmpty()) exchangeOxygen(p, env, a, suit, suitO2, hazards);

        if (hazards) {
            // Eyes under water: vanilla already drains air and drowns the player, so a second drain would double it.
            if (a.suffocating() && !p.isEyeInFluid(FluidTags.WATER)) {
                int air = p.getAirSupply() - VACUUM_AIR_STEP;
                if (air <= DROWN_AT) {
                    p.setAirSupply(0);
                    level.broadcastEntityEvent(p, DROWN_BUBBLES);
                    hazard(p, level.damageSources().drown(), DROWN_DAMAGE);
                } else {
                    p.setAirSupply(air);
                }
            } else if (a.suitBreathing()) {
                p.setAirSupply(p.getMaxAirSupply());
            }
            if (a.cold()) {
                p.setTicksFrozen(Math.min(p.getTicksFrozen() + 3, p.getTicksRequiredToFreeze() - 1));
                if (p.tickCount % 40 == 0) hazard(p, level.damageSources().freeze(), 1.0F);
            }
            if (a.hot() && p.tickCount % 40 == 0 && !p.fireImmune()) {
                hazard(p, level.damageSources().onFire(), 1.0F);
            }
            if (a.crushed() && secondTick) {
                hazard(p, level.damageSources().cramming(), 1.0F);
            }
        }

        // Attachment writes sync to the owner, so write on a hazard change or once per second.
        LifeSupportStatus prev = p.getAttached(STATUS);
        int flags = LifeSupportStatus.flags(env, a, fullSuit, cabin);
        if (!hazards) flags &= ~HAZARD_FLAGS;
        if (prev == null || prev.flags() != flags || secondTick) {
            int shownSuit = suit.isEmpty() ? 0 : OxygenTankItem.oxygen(suit);
            int reserve = secondTick || prev == null ? LifeSupportModel.reserve(shownSuit, tankLevels(tanks(p))) : prev.reserveO2();
            LifeSupportStatus next = new LifeSupportStatus(shownSuit, reserve, (float) env.pressureBar(), (float) env.tempK(), flags);
            if (!next.equals(prev)) p.setAttached(STATUS, next);
        }
    }

    /** One hazard hit with the damage cooldown cleared, so simultaneous hazards each land in full. */
    private static void hazard(ServerPlayer p, DamageSource source, float amount) {
        p.damageCooldownTime = 0;
        p.hurtServer(p.level(), source, amount);
    }

    /**
     * One second of suit bookkeeping. Writes item components only when a value changed. With {@code spend} false
     * (creative) nothing is drawn from tanks; the suit may still recharge from breathable air.
     */
    private static void exchangeOxygen(ServerPlayer p, Environment env, LifeSupportModel.Assessment a, ItemStack suit,
                                      int suitO2, boolean spend) {
        List<ItemStack> tanks = tanks(p);
        int[] levels = tankLevels(tanks);
        int[] before = levels.clone();
        int nextSuit = suitO2;
        if (a.suitBreathing() && spend) {
            nextSuit = LifeSupportModel.breatheOneSecond(suitO2, levels);
        } else if (LifeSupportModel.canRecharge(env)) {
            nextSuit = LifeSupportModel.rechargeOneSecond(suitO2, levels);
        } else if (spend && suitO2 < LifeSupportModel.SUIT_CAPACITY) {
            nextSuit = LifeSupportModel.refillSuit(suitO2, levels);
        }
        if (nextSuit != suitO2) suit.set(GalacticaComponents.OXYGEN, nextSuit);
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] != before[i]) tanks.get(i).set(GalacticaComponents.OXYGEN, levels[i]);
        }
    }

    private static List<ItemStack> tanks(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(GalacticaItems.OXYGEN_TANK)) out.add(s);
        }
        return out;
    }

    private static int[] tankLevels(List<ItemStack> tanks) {
        int[] levels = new int[tanks.size()];
        for (int i = 0; i < levels.length; i++) levels[i] = OxygenTankItem.oxygen(tanks.get(i));
        return levels;
    }
}
