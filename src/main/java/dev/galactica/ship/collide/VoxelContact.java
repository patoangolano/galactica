package dev.galactica.ship.collide;

import dev.galactica.ship.deck.ShipPose;
import dev.galactica.ship.yard.ShipCell;
import dev.galactica.ship.yard.ShipOccupancy;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jspecify.annotations.Nullable;

/**
 * Voxel-against-voxel narrow phase between two posed ship grids (pure Java). Every cell of the smaller grid is sampled
 * at its centre and at six points {@link #SAMPLE} out along its own axes; a sample inside a cell of the other grid is a
 * contact. Touching faces do not count (a ship resting on another's deck overlaps by less than
 * {@code 0.5 − SAMPLE}), anything deeper does.
 *
 * <p>The contact normal comes from both surfaces: at each contact, the directions from the struck cell to its empty
 * neighbours (the other hull's outward normal there) and the reverse of the same for the sampled cell, summed over all
 * contacts. The depth is how far the deepest contact must travel along that normal to leave the other hull.
 */
public final class VoxelContact {
    private VoxelContact() {}

    /** Offset of the six face samples from a cell centre, cells. */
    public static final double SAMPLE = 0.35;
    private static final int[][] STEP = ShipCell.STEP;

    /** A grid at a pose. */
    public record Posed(ShipOccupancy grid, ShipPose pose) {}

    /**
     * @param point  centroid of the contacts, world
     * @param normal unit, world, pointing from body B towards body A (the way A must move to separate)
     * @param depth  penetration along the normal, blocks
     * @param count  contact samples
     */
    public record Contact(Vector3d point, Vector3d normal, double depth, int count) {}

    /** World bounds of a posed grid: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public static double[] worldBounds(Posed p) {
        ShipOccupancy g = p.grid();
        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        Vector3d v = new Vector3d();
        for (int k = 0; k < 8; k++) {
            v.set((k & 1) == 0 ? g.minX() : g.maxX(), (k & 2) == 0 ? g.minY() : g.maxY(), (k & 4) == 0 ? g.minZ() : g.maxZ());
            Vector3d w = p.pose().toWorld(v);
            out[0] = Math.min(out[0], w.x);
            out[1] = Math.min(out[1], w.y);
            out[2] = Math.min(out[2], w.z);
            out[3] = Math.max(out[3], w.x);
            out[4] = Math.max(out[4], w.y);
            out[5] = Math.max(out[5], w.z);
        }
        return out;
    }

    public static boolean boundsOverlap(double[] a, double[] b, double margin) {
        return a[0] - margin < b[3] && a[3] + margin > b[0] && a[1] - margin < b[4] && a[4] + margin > b[1]
                && a[2] - margin < b[5] && a[5] + margin > b[2];
    }

    /** True if the two grids overlap (any sample of the smaller inside the larger). */
    public static boolean overlaps(Posed a, Posed b) {
        if (!boundsOverlap(worldBounds(a), worldBounds(b), 0)) return false;
        return scan(a, b, true) != null;
    }

    /** The contact between A and B, or null if they do not overlap. */
    public static @Nullable Contact find(Posed a, Posed b) {
        if (!boundsOverlap(worldBounds(a), worldBounds(b), 0)) return null;
        return scan(a, b, false);
    }

    private static @Nullable Contact scan(Posed a, Posed b, boolean any) {
        boolean aSmall = a.grid().size() <= b.grid().size();
        Posed s = aSmall ? a : b, l = aSmall ? b : a;
        ShipOccupancy sg = s.grid(), lg = l.grid();
        // S's axes and positions in L's cell frame: p_L = rel · (p_S − comS) + (pivotS − pivotL in L axes) + comL.
        Quaterniond rel = new Quaterniond(l.pose().attitude()).conjugate().mul(s.pose().attitude());
        Vector3d[] axes = new Vector3d[6];
        for (int i = 0; i < 6; i++) axes[i] = rel.transform(new Vector3d(STEP[i][0], STEP[i][1], STEP[i][2]));
        Vector3dc comS = s.pose().com(), comL = l.pose().com();
        Vector3d offset = l.pose().toLocal(s.pose().pivot()); // S's pivot in L's cells
        double x0 = lg.minX() - 1, y0 = lg.minY() - 1, z0 = lg.minZ() - 1, x1 = lg.maxX() + 1, y1 = lg.maxY() + 1, z1 = lg.maxZ() + 1;
        Vector3d c = new Vector3d(), q = new Vector3d();
        Vector3d sumPoint = new Vector3d(), sumNormalL = new Vector3d(), sumNormalS = new Vector3d();
        int count = 0;
        java.util.List<Vector3d> hits = any ? null : new java.util.ArrayList<>();
        for (int packed : sg.packedCells()) {
            int cx = ShipCell.unpackX(packed), cy = ShipCell.unpackY(packed), cz = ShipCell.unpackZ(packed);
            c.set(cx + 0.5 - comS.x(), cy + 0.5 - comS.y(), cz + 0.5 - comS.z());
            rel.transform(c).add(offset);
            if (c.x < x0 || c.x > x1 || c.y < y0 || c.y > y1 || c.z < z0 || c.z > z1) continue;
            for (int k = 0; k < 7; k++) {
                if (k == 0) q.set(c);
                else q.set(c).fma(SAMPLE, axes[k - 1]);
                int lx = (int) Math.floor(q.x), ly = (int) Math.floor(q.y), lz = (int) Math.floor(q.z);
                if (!lg.occupied(lx, ly, lz)) continue;
                if (any) return new Contact(new Vector3d(), new Vector3d(0, 1, 0), 0, 1);
                count++;
                sumPoint.add(q);
                hits.add(new Vector3d(q));
                // L's outward surface at the struck cell (in L's axes)...
                for (int[] st : STEP) if (!lg.occupied(lx + st[0], ly + st[1], lz + st[2])) sumNormalL.add(st[0], st[1], st[2]);
                // ...and S's outward surface at the sampling cell (in S's axes, turned into L's).
                for (int i = 0; i < 6; i++) {
                    int[] st = STEP[i];
                    if (!sg.occupied(cx + st[0], cy + st[1], cz + st[2])) sumNormalS.add(axes[i]);
                }
            }
        }
        if (count == 0) return null;
        // Direction to push S out of L, in L's axes: along L's surface normal, against S's.
        Vector3d nL = new Vector3d(sumNormalL).sub(sumNormalS);
        Vector3d centroidL = sumPoint.div(count);
        if (nL.lengthSquared() < 1e-9) {
            nL.set(offset).sub(comL);
            if (nL.lengthSquared() < 1e-9) nL.set(0, 1, 0);
        }
        nL.normalize();
        // Depth: the deepest contact's travel along the normal out of L.
        double depth = 0;
        for (Vector3d h : hits) {
            double d = 0;
            Vector3d p = new Vector3d(h);
            while (d < MAX_DEPTH && lg.occupied((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z))) {
                p.fma(DEPTH_STEP, nL);
                d += DEPTH_STEP;
            }
            depth = Math.max(depth, d);
        }
        Vector3d normal = l.pose().dirToWorld(nL);
        Vector3d point = l.pose().toWorld(centroidL);
        if (!aSmall) normal.negate(); // S was B: the push on S (B) points from A to B; flip to "from B to A"
        return new Contact(point, normal, depth, count);
    }

    /** Depth search step and limit, cells. */
    static final double DEPTH_STEP = 0.1, MAX_DEPTH = 6;
}
