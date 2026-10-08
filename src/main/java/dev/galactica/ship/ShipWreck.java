package dev.galactica.ship;

import dev.galactica.Galactica;
import dev.galactica.ship.yard.ShipBlocks;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A block-built ship whose hull reaches zero with {@code shipDestruction} on: it breaks up. Everyone aboard is set
 * down beside it ({@link Starship#ejectCrewSafely}: slow falling on a planet), docked craft fly free, every
 * container's contents spill, and {@code wreckDropShare} of the blocks survive as items (chosen at random, so a big
 * ship drops a fair sample of what it was built from); fuel and exotic matter are lost with the tanks.
 */
public final class ShipWreck {
    private ShipWreck() {}

    /** Most item entities a wreck spawns: stacks are merged per item, so this only caps pathological ships. */
    static final int MAX_DROPS = 256;

    public static void breakUp(ShipStructure ship, ServerLevel level) {
        if (ship.isRemoved()) return;
        double share = dev.galactica.config.GalacticaConfigs.get().wreckDropShare();
        ship.ejectCrewSafely();
        ShipBlocks blocks = ship.blocks();
        // Same seed for the same ship: a test (or a replay) sees the same drops.
        Random rnd = new Random(ship.getUUID().getLeastSignificantBits());
        Object2IntLinkedOpenHashMap<Item> counts = new Object2IntLinkedOpenHashMap<>();
        List<ItemStack> spilled = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (ShipCargo.isContainer(blocks, i)) spilled.addAll(ShipCargo.contents(ship, i));
            if (rnd.nextDouble() >= share) continue;
            Item item = blocks.state(i).getBlock().asItem();
            if (item != Items.AIR) counts.addTo(item, 1);
        }
        int dropped = 0, entities = 0;
        var centre = ship.position().add(0, ship.pivot(), 0);
        for (var e : counts.object2IntEntrySet()) {
            int left = e.getIntValue();
            while (left > 0 && entities < MAX_DROPS) {
                int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                left -= n;
                dropped += n;
                spawn(level, ship, centre.x, centre.y, centre.z, new ItemStack(e.getKey(), n), rnd);
                entities++;
            }
        }
        for (ItemStack s : spilled) {
            if (entities >= MAX_DROPS) break;
            spawn(level, ship, centre.x, centre.y, centre.z, s, rnd);
            entities++;
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 2, 1, 1, 1, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, centre.x, centre.y, centre.z, 60, ship.spec().hullReach() / 3, 1, ship.spec().hullReach() / 3, 0.05);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 4F, 0.8F);
        Galactica.LOG.info("{}ship {} destroyed: {} of {} blocks dropped, {} container stacks spilled",
                Boolean.getBoolean("galactica.autotest") ? "[autotest] " : "", ship.getId(), dropped, blocks.size(), spilled.size());
        ship.discard();
    }

    /** An item entity scattered over the hull's extent, drifting with the wreck. */
    private static void spawn(ServerLevel level, ShipStructure ship, double x, double y, double z, ItemStack stack, Random rnd) {
        double r = Math.max(1, ship.spec().hullReach() * 0.6);
        Vector3d off = new Vector3d(rnd.nextGaussian(), rnd.nextGaussian() * 0.3, rnd.nextGaussian()).mul(r / 2);
        ItemEntity e = new ItemEntity(level, x + off.x, y + off.y, z + off.z, stack);
        e.setDeltaMovement(ship.getDeltaMovement().add(rnd.nextGaussian() * 0.05, 0.1, rnd.nextGaussian() * 0.05));
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }
}
