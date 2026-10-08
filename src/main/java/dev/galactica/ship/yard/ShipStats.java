package dev.galactica.ship.yard;

import dev.galactica.ship.ShipSpec;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A block-built ship's {@link ShipSpec}, computed from its cells alone (pure Java): the server and every client run it
 * on the same blocks, so nothing a client sends ever feeds the server's clamps.
 *
 * <ul>
 *   <li><b>Mass</b>, <b>centre of mass</b> (the pivot: the ship turns about it) and the <b>inertia</b> diagonal about
 *       it (point masses plus each block's own m/6).</li>
 *   <li><b>Thrust per direction</b>: each thruster pushes opposite its nozzle; nose-ward pushes are the main engine,
 *       tail-ward ones the brake (S), dorsal ones add VTOL lift. RCS blocks (and the helm's trim jets) give
 *       translation on every axis and the torque that sets the turn rate against the inertia.</li>
 *   <li><b>Tanks, cores, plating</b>: hydrogen and exotic capacity, the jump tier, hull points.</li>
 *   <li><b>Shape</b>: pivot height above the lowest face (the entity origin), a square collision box around the
 *       pivot that never reaches past the hull, the farthest corner, seats, and the hull probes
 *       ({@link #probes}).</li>
 * </ul>
 */
public final class ShipStats {
    private ShipStats() {}

    /** Most probes a ship gets: each costs a block lookup per swept sample. */
    public static final int MAX_PROBES = 384;
    /** Reference angular acceleration, rad/s², of a ship that turns at the Kestrel's full rates. */
    static final double REFERENCE_ALPHA = 2.5;
    /** Turn-rate floor: even a huge freighter answers the stick, slowly. */
    static final double MIN_TURN = 0.2;
    /** A seated rider's hips above the floor of its seat block. */
    static final float SEAT_HIP = 0.45F;

    /**
     * @param spec        the spec (body frame about the centre of mass)
     * @param com         centre of mass in cell coordinates (cell x spans x..x+1)
     * @param minY        lowest face, cell coordinates (the entity origin's height)
     * @param thrustByDir thrust along each {@code Direction} ordinal, N
     * @param counts      blocks per part (by ordinal)
     */
    public record Result(ShipSpec spec, Vector3d com, int minY, double[] thrustByDir, int[] counts) {}

    public static Result compute(Collection<ShipCell> cells) {
        if (cells.isEmpty()) throw new IllegalArgumentException("a ship needs at least one block");
        double mass = 0, cx = 0, cy = 0, cz = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int[] counts = new int[ShipPart.values().length];
        double[] thrust = new double[6];
        for (ShipCell c : cells) {
            double m = Math.max(1, c.massKg());
            mass += m;
            cx += m * (c.x() + 0.5);
            cy += m * (c.y() + 0.5);
            cz += m * (c.z() + 0.5);
            minX = Math.min(minX, c.x()); maxX = Math.max(maxX, c.x() + 1);
            minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y() + 1);
            minZ = Math.min(minZ, c.z()); maxZ = Math.max(maxZ, c.z() + 1);
            counts[c.part().ordinal()]++;
            if (c.part() == ShipPart.THRUSTER && c.facing() >= 0 && c.facing() < 6) thrust[opposite(c.facing())] += ShipPart.THRUSTER_N;
        }
        Vector3d com = new Vector3d(cx / mass, cy / mass, cz / mass);
        double ixx = 0, iyy = 0, izz = 0, reach = 0;
        for (ShipCell c : cells) {
            double m = Math.max(1, c.massKg());
            double dx = c.x() + 0.5 - com.x, dy = c.y() + 0.5 - com.y, dz = c.z() + 0.5 - com.z;
            ixx += m * (dy * dy + dz * dz + 1.0 / 6);
            iyy += m * (dx * dx + dz * dz + 1.0 / 6);
            izz += m * (dx * dx + dy * dy + 1.0 / 6);
            for (int k = 0; k < 8; k++) {
                double px = c.x() + (k & 1) - com.x, py = c.y() + (k >> 1 & 1) - com.y, pz = c.z() + (k >> 2 & 1) - com.z;
                reach = Math.max(reach, Math.sqrt(px * px + py * py + pz * pz));
            }
        }

        int rcs = counts[ShipPart.RCS.ordinal()], helms = counts[ShipPart.HELM.ordinal()];
        double rcsN = ShipPart.HELM_RCS_N * helms + ShipPart.RCS_N * rcs;
        double main = thrust[3], reverse = thrust[2], lift = rcsN + thrust[1];
        float hydrogen = ShipPart.TANK_CELLS * counts[ShipPart.HYDROGEN_TANK.ordinal()];
        int cores = counts[ShipPart.HYPERDRIVE_CORE.ordinal()];
        int exotic = ShipPart.CORE_EXOTIC * cores;
        double jump = cores == 0 ? 0 : Math.min(ShipPart.MAX_JUMP_LY, ShipPart.FIRST_CORE_LY + ShipPart.EXTRA_CORE_LY * (cores - 1));
        float hull = Math.min(ShipPart.MAX_HULL, ShipPart.BASE_HULL + ShipPart.BLOCK_HULL * cells.size()
                + ShipPart.PLATING_HULL * counts[ShipPart.HULL_PLATING.ordinal()]);

        // Square box about the pivot, inside the hull's extent on every side; at most as tall as it is wide, so a
        // pitched hull's box does not stand up into empty air (the probes cover everything beyond it).
        double half = Math.min(Math.min(com.x - minX, maxX - com.x), Math.min(com.z - minZ, maxZ - com.z));
        half = Math.max(0.45, half);
        float boxWidth = (float) (2 * half);
        float boxHeight = (float) Math.max(1.0, Math.min(maxY - minY, Math.max(2 * half, 1.0)));
        float pivot = (float) (com.y - minY);

        // Turn rate: RCS torque about a lever of half the hull's reach against the largest moment.
        double torque = rcsN * Math.max(1.0, reach / 2);
        double alpha = torque / Math.max(1e-9, Math.max(ixx, Math.max(iyy, izz)));
        double turn = Math.clamp(alpha / REFERENCE_ALPHA, MIN_TURN, 1.0);

        List<Vector3fc> seats = new ArrayList<>();
        for (ShipPart p : new ShipPart[] {ShipPart.HELM, ShipPart.CREW_SEAT}) {
            for (ShipCell c : cells) {
                if (c.part() == p) seats.add(new Vector3f((float) (c.x() + 0.5 - com.x), (float) (c.y() + SEAT_HIP - com.y), (float) (c.z() + 0.5 - com.z)));
            }
        }
        if (seats.isEmpty()) seats.add(new Vector3f(0F, (float) (maxY - com.y), 0F)); // a hulk: sit on top

        ShipSpec spec = new ShipSpec(mass, main, reverse, rcsN, lift, hydrogen, exotic, hull, jump,
                pivot, boxWidth, boxHeight, (float) reach, List.copyOf(seats), probes(cells, com),
                new Vector3d(), new Vector3d(ixx, iyy, izz), turn);
        return new Result(spec, com, minY, thrust, counts);
    }

    static int opposite(int dir) {
        return dir ^ 1;
    }

    /**
     * Hull probes, body frame (blocks from the centre of mass): the corners and face centres of every exposed face
     * (a face with no ship block beyond it). Within {@link #MAX_PROBES} they are all kept; above it, landing-gear
     * feet first, then the farthest point in each of 32 × 16 azimuth/elevation sectors (the outline the terrain can
     * touch), then every other bottom-layer point until the cap. Points are nudged 1/64 block into their face so
     * two coplanar probes never straddle a block edge differently from the hull itself.
     */
    public static float[][] probes(Collection<ShipCell> cells, Vector3d com) {
        IntOpenHashSet occupied = new IntOpenHashSet();
        int minY = Integer.MAX_VALUE;
        for (ShipCell c : cells) {
            occupied.add(c.packed());
            minY = Math.min(minY, c.y());
        }
        // Points on a doubled grid (exact halves) so duplicates merge.
        it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet points = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet gear = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        for (ShipCell c : cells) {
            for (int d = 0; d < 6; d++) {
                int[] s = ShipCell.STEP[d];
                if (occupied.contains(ShipCell.pack(c.x() + s[0], c.y() + s[1], c.z() + s[2]))) continue;
                // Face centre in doubled coordinates, then its four corners.
                int fx = 2 * c.x() + 1 + s[0], fy = 2 * c.y() + 1 + s[1], fz = 2 * c.z() + 1 + s[2];
                int ax = s[0] == 0 ? 1 : 0, ay = s[1] == 0 ? 1 : 0, az = s[2] == 0 ? 1 : 0;
                long[] face = new long[5];
                face[0] = key(fx, fy, fz);
                int n = 1;
                for (int i = -1; i <= 1; i += 2) {
                    for (int j = -1; j <= 1; j += 2) {
                        // Two tangent axes: the first free axis gets i, the second j.
                        int tx = 0, ty = 0, tz = 0;
                        boolean first = true;
                        if (ax == 1) { tx = i; first = false; }
                        if (ay == 1) { if (first) { ty = i; first = false; } else ty = j; }
                        if (az == 1) { if (first) tz = i; else tz = j; }
                        face[n++] = key(fx + tx, fy + ty, fz + tz);
                    }
                }
                boolean gearFoot = c.part() == ShipPart.LANDING_GEAR && d == 0;
                for (long k : face) {
                    points.add(k);
                    if (gearFoot) gear.add(k);
                }
            }
        }
        List<long[]> chosen = new ArrayList<>();
        if (points.size() <= MAX_PROBES) {
            for (long k : points) chosen.add(new long[] {k});
        } else {
            it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet keep = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
            for (long k : gear) if (keep.size() < MAX_PROBES / 4) keep.add(k);
            int az = 32, el = 16;
            long[] best = new long[az * el];
            double[] far = new double[az * el];
            java.util.Arrays.fill(far, -1);
            for (long k : points) {
                double x = kx(k) / 2.0 - com.x, y = ky(k) / 2.0 - com.y, z = kz(k) / 2.0 - com.z;
                double r = Math.sqrt(x * x + y * y + z * z);
                if (r < 1e-9) continue;
                int a = (int) Math.floor((Math.atan2(z, x) + Math.PI) / (2 * Math.PI) * az) % az;
                int e = Math.min(el - 1, (int) Math.floor((Math.asin(Math.clamp(y / r, -1, 1)) + Math.PI / 2) / Math.PI * el));
                int bin = a * el + e;
                if (r > far[bin]) {
                    far[bin] = r;
                    best[bin] = k;
                }
            }
            for (int b = 0; b < best.length && keep.size() < MAX_PROBES; b++) if (far[b] >= 0) keep.add(best[b]);
            // The underside the ship lands on: every other point of the lowest layer, then the rest of it.
            for (int pass = 0; pass < 2 && keep.size() < MAX_PROBES; pass++) {
                for (long k : points) {
                    if (keep.size() >= MAX_PROBES) break;
                    if (ky(k) != 2 * minY) continue;
                    boolean even = ((kx(k) + kz(k)) / 2 & 1) == 0;
                    if (pass == 0 && !even) continue;
                    keep.add(k);
                }
            }
            for (long k : keep) chosen.add(new long[] {k});
        }
        float[][] out = new float[chosen.size()][];
        double nudge = 1.0 / 64;
        for (int i = 0; i < out.length; i++) {
            long k = chosen.get(i)[0];
            double x = kx(k) / 2.0, y = ky(k) / 2.0, z = kz(k) / 2.0;
            // Pull every point slightly toward the hull's interior along each axis it sits on a face of: a probe
            // exactly on a block boundary would count the neighbouring block as touched.
            x += nudgeToward(x, com.x, nudge);
            y += nudgeToward(y, com.y, nudge);
            z += nudgeToward(z, com.z, nudge);
            out[i] = new float[] {(float) (x - com.x), (float) (y - com.y), (float) (z - com.z)};
        }
        return out;
    }

    private static double nudgeToward(double v, double centre, double nudge) {
        if (v != Math.floor(v)) return 0; // only points on a block boundary
        return v > centre ? -nudge : v < centre ? nudge : 0;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) << 42) | ((long) (y + (1 << 20)) << 21) | (z + (1 << 20));
    }

    private static int kx(long k) { return (int) (k >>> 42 & 0x1FFFFF) - (1 << 20); }
    private static int ky(long k) { return (int) (k >>> 21 & 0x1FFFFF) - (1 << 20); }
    private static int kz(long k) { return (int) (k & 0x1FFFFF) - (1 << 20); }
}
