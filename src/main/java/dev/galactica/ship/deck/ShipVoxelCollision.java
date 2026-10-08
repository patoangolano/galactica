package dev.galactica.ship.deck;

import dev.galactica.ship.ShipStructure;
import dev.galactica.ship.Starship;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Built ships' hulls are solid for every ordinary entity that does not ride them: players walking up to a parked
 * ship, its boarding steps and its roof, items falling onto it, mobs. Hooked after vanilla's own collision
 * ({@code Entity.collide}): the move that survived the terrain is collided again in each nearby ship's frame against
 * its block shapes ({@link BoxSweep#collideWithStep}, so steps and slabs climb as on the ground), and turned back into
 * the world. The body's box is taken axis-aligned in the ship's frame (a player is a cylinder for this). Only ships
 * within reach are looked at: a per-level list, a distance test, then the ship's own blocks near the body.
 */
public final class ShipVoxelCollision {
    private ShipVoxelCollision() {}

    private static final Map<Level, List<ShipStructure>> SHIPS = new WeakHashMap<>();

    /** Records a live built ship of its level (called from its tick). */
    public static void track(ShipStructure ship) {
        synchronized (SHIPS) {
            List<ShipStructure> list = SHIPS.computeIfAbsent(ship.level(), l -> new ArrayList<>());
            if (!list.contains(ship)) list.add(ship);
        }
    }

    /** The live built ships of a level (a copy). */
    public static List<ShipStructure> ships(Level level) {
        synchronized (SHIPS) {
            List<ShipStructure> list = SHIPS.get(level);
            if (list == null || list.isEmpty()) return List.of();
            list.removeIf(s -> s.isRemoved() || s.level() != level);
            return new ArrayList<>(list);
        }
    }

    /** Whether {@code e} collides with built hulls through this hook (riders and ships themselves do not). */
    public static boolean applies(Entity e) {
        return !(e instanceof Starship) && !e.isPassenger() && !e.noPhysics && !(e instanceof Player p && p.isSpectator());
    }

    /** {@code movement} (already clipped by the terrain) clipped by every built hull near {@code e}. */
    public static Vec3 clip(Entity e, Vec3 movement) {
        List<ShipStructure> ships = ships(e.level());
        if (ships.isEmpty()) return movement;
        AABB box = e.getBoundingBox();
        Vec3 m = movement;
        for (ShipStructure s : ships) {
            if (s.blocks().isEmpty() || e.getRootVehicle() == s.getRootVehicle()) continue;
            double reach = s.spec().hullReach() + 2 + m.length() + box.getXsize() + box.getYsize();
            double dx = s.getX() - e.getX(), dy = s.getY() + s.pivot() - e.getY(), dz = s.getZ() - e.getZ();
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            m = clip(s, e, box, m);
        }
        return m;
    }

    private static Vec3 clip(ShipStructure s, Entity e, AABB box, Vec3 m) {
        ShipPose pose = Deck.pose(s);
        Vector3d feet = pose.toLocal(new Vector3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2));
        double width = Math.max(box.getXsize(), box.getZsize());
        Aabb local = Aabb.feet(feet.x, feet.y, feet.z, width, box.getYsize());
        Vector3d d = pose.dirToLocal(new Vector3d(m.x, m.y, m.z));
        double step = e.maxUpStep();
        List<Aabb> boxes = new ArrayList<>();
        Deck.blocks(s).collect(local.expandTowards(d.x, d.y, d.z).expandTowards(0, step, 0).inflate(1e-3), boxes);
        if (boxes.isEmpty()) return m;
        double[] c = BoxSweep.collideWithStep(d.x, d.y, d.z, local, boxes, step, e.onGround());
        if (c[0] == d.x && c[1] == d.y && c[2] == d.z) return m;
        Vector3d w = pose.dirToWorld(new Vector3d(c[0], c[1], c[2]));
        // Keep vanilla's exact components where the hull changed nothing (a level ship keeps world Y exact).
        return new Vec3(Math.abs(w.x - m.x) < 1e-9 ? m.x : w.x, Math.abs(w.y - m.y) < 1e-9 ? m.y : w.y, Math.abs(w.z - m.z) < 1e-9 ? m.z : w.z);
    }
}
