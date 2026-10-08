package dev.galactica.ship.deck;

import java.util.Arrays;
import java.util.List;

/**
 * Moving a box through a set of fixed boxes, one axis at a time, the way vanilla's {@code Entity.collide} does it
 * (vertical first, then the larger horizontal component), with vanilla's step-up onto ledges up to a step height.
 * Pure Java: the deck physics runs it in a ship's local frame against the ship's own blocks.
 */
public final class BoxSweep {
    private BoxSweep() {}

    /** Vanilla's collision tolerance ({@code Shapes} uses 1e-7). */
    public static final double EPS = 1.0E-7;

    /**
     * How far {@code box} can move along {@code axis} (signed {@code delta}) before it touches one of {@code boxes}.
     * Boxes it already overlaps along that axis are ignored, as vanilla does, so a box that starts inside a block can
     * still leave it.
     */
    public static double clip(int axis, Aabb box, List<Aabb> boxes, double delta) {
        if (Math.abs(delta) < EPS) return 0;
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        for (Aabb o : boxes) {
            if (box.max(a1) - EPS <= o.min(a1) || box.min(a1) + EPS >= o.max(a1)) continue;
            if (box.max(a2) - EPS <= o.min(a2) || box.min(a2) + EPS >= o.max(a2)) continue;
            if (delta > 0) {
                double gap = o.min(axis) - box.max(axis);
                if (gap >= -EPS && gap < delta) delta = Math.max(0, gap);
            } else {
                double gap = o.max(axis) - box.min(axis);
                if (gap <= EPS && gap > delta) delta = Math.min(0, gap);
            }
        }
        return delta;
    }

    /** The move {@code (dx, dy, dz)} of {@code box} clipped against {@code boxes}, per axis (vanilla order). */
    public static double[] collide(double dx, double dy, double dz, Aabb box, List<Aabb> boxes) {
        double[] d = {dx, dy, dz};
        double[] out = new double[3];
        if (boxes.isEmpty()) return d;
        Aabb b = box;
        for (int axis : order(dx, dz)) {
            if (d[axis] == 0) continue;
            double c = clip(axis, b, boxes, d[axis]);
            out[axis] = c;
            b = b.move(axis, c);
        }
        return out;
    }

    /** Vanilla's {@code Direction.axisStepOrder}: Y, then the larger of X and Z, then the other. */
    static int[] order(double dx, double dz) {
        return Math.abs(dx) < Math.abs(dz) ? new int[] {1, 2, 0} : new int[] {1, 0, 2};
    }

    /**
     * {@link #collide} plus vanilla's step-up: a box on the ground (or landing this move) that is stopped sideways
     * tries to climb onto what stopped it, up to {@code stepHeight}, and keeps the climb if it gets further sideways.
     */
    public static double[] collideWithStep(double dx, double dy, double dz, Aabb box, List<Aabb> boxes, double stepHeight, boolean onGround) {
        double[] m = collide(dx, dy, dz, box, boxes);
        boolean xCol = m[0] != dx, yCol = m[1] != dy, zCol = m[2] != dz;
        boolean landed = yCol && dy < 0;
        if (stepHeight <= 0 || !(landed || onGround) || !(xCol || zCol)) return m;
        Aabb grounded = landed ? box.move(0, m[1], 0) : box;
        double[] heights = stepHeights(grounded, boxes, stepHeight, m[1]);
        double best = m[0] * m[0] + m[2] * m[2];
        for (double h : heights) {
            double[] s = collide(dx, h, dz, grounded, boxes);
            if (s[0] * s[0] + s[2] * s[2] > best) {
                double toGround = box.minY() - grounded.minY();
                return new double[] {s[0], s[1] - toGround, s[2]};
            }
        }
        return m;
    }

    /** The heights of box tops and bottoms within {@code max} above the box's feet, ascending (vanilla's candidates). */
    static double[] stepHeights(Aabb box, List<Aabb> boxes, double max, double skip) {
        double[] out = new double[boxes.size() * 2 + 1];
        int n = 0;
        out[n++] = max;
        for (Aabb o : boxes) {
            for (double y : new double[] {o.minY(), o.maxY()}) {
                double h = y - box.minY();
                if (h >= 0 && h <= max && (float) h != (float) skip) out[n++] = h;
            }
        }
        double[] r = Arrays.copyOf(out, n);
        Arrays.sort(r);
        return r;
    }

    /** Whether {@code box} overlaps any of {@code boxes} by more than {@code eps}. */
    public static boolean overlaps(Aabb box, List<Aabb> boxes, double eps) {
        for (Aabb o : boxes) if (box.intersects(o, eps)) return true;
        return false;
    }

    /**
     * The smallest push that takes {@code box} out of every box it overlaps, as {dx, dy, dz}, or null if it overlaps
     * nothing. Each round pushes along the axis direction that needs the least travel to clear the boxes overlapped
     * now; a push up of at most {@code preferUp} wins over any sideways push, so a box sunk into a floor stands on it.
     * Gives up (returns the pushes so far) after {@code rounds} rounds.
     */
    public static double[] depenetrate(Aabb box, List<Aabb> boxes, double preferUp, int rounds) {
        double tx = 0, ty = 0, tz = 0;
        boolean any = false;
        Aabb b = box;
        for (int r = 0; r < rounds; r++) {
            double[] need = new double[6]; // +x -x +y -y +z -z
            boolean hit = false;
            for (Aabb o : boxes) {
                if (!b.intersects(o, 1e-6)) continue;
                hit = true;
                need[0] = Math.max(need[0], o.maxX() - b.minX());
                need[1] = Math.max(need[1], b.maxX() - o.minX());
                need[2] = Math.max(need[2], o.maxY() - b.minY());
                need[3] = Math.max(need[3], b.maxY() - o.minY());
                need[4] = Math.max(need[4], o.maxZ() - b.minZ());
                need[5] = Math.max(need[5], b.maxZ() - o.minZ());
            }
            if (!hit) break;
            any = true;
            int best = -1;
            if (need[2] <= preferUp) best = 2;
            else {
                for (int i = 0; i < 6; i++) if (best < 0 || need[i] < need[best]) best = i;
            }
            double s = need[best] + 1e-4;
            double dx = best == 0 ? s : best == 1 ? -s : 0, dy = best == 2 ? s : best == 3 ? -s : 0, dz = best == 4 ? s : best == 5 ? -s : 0;
            b = b.move(dx, dy, dz);
            tx += dx;
            ty += dy;
            tz += dz;
        }
        return any ? new double[] {tx, ty, tz} : null;
    }
}
