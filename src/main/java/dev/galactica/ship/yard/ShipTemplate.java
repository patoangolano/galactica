package dev.galactica.ship.yard;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prefab ships as block layouts in the ship grid (helm at 0,0,0, nose +Z), pure Java so their stats are unit-tested;
 * {@link ShipTemplates#blocks} turns one into real block states. A prefab is just a template: spawned, it is an
 * ordinary {@code ShipStructure}, and disassembled it can be rebuilt by hand.
 */
public final class ShipTemplate {
    /**
     * Materials: a block id, its hardness (for {@link ShipBlocks#structureMass}), its part, and for parts with a facing
     * the body-frame direction ({@code Direction} ordinal) it is placed with.
     */
    public enum Mat {
        HULL("minecraft:white_concrete", 1.8F, ShipPart.STRUCTURE, -1),
        HULL_DARK("minecraft:light_gray_concrete", 1.8F, ShipPart.STRUCTURE, -1),
        GREY("minecraft:gray_concrete", 1.8F, ShipPart.STRUCTURE, -1),
        TRIM("minecraft:orange_concrete", 1.8F, ShipPart.STRUCTURE, -1),
        GLASS("minecraft:light_blue_stained_glass", 0.3F, ShipPart.STRUCTURE, -1),
        LAMP("minecraft:sea_lantern", 0.3F, ShipPart.STRUCTURE, -1),
        CARGO("minecraft:barrel", 2.5F, ShipPart.STRUCTURE, -1),
        PLATING("galactica:ship_hull_plating", 5F, ShipPart.HULL_PLATING, -1),
        HELM("galactica:ship_helm", 3F, ShipPart.HELM, 3),
        SEAT("galactica:ship_crew_seat", 2F, ShipPart.CREW_SEAT, 3),
        /** Main engine: nozzle aft (north, −Z). */
        ENGINE("galactica:ship_thruster", 4F, ShipPart.THRUSTER, 2),
        /** Brake: nozzle forward (south, +Z). */
        BRAKE("galactica:ship_thruster", 4F, ShipPart.THRUSTER, 3),
        /** VTOL lift: nozzle down. */
        LIFT("galactica:ship_thruster", 4F, ShipPart.THRUSTER, 0),
        RCS("galactica:ship_rcs", 3F, ShipPart.RCS, -1),
        TANK("galactica:ship_hydrogen_tank", 3F, ShipPart.HYDROGEN_TANK, -1),
        CORE("galactica:ship_hyperdrive_core", 5F, ShipPart.HYPERDRIVE_CORE, -1),
        GEAR("galactica:ship_landing_gear", 3F, ShipPart.LANDING_GEAR, -1);

        public final String block;
        public final float hardness;
        public final ShipPart part;
        public final int facing;

        Mat(String block, float hardness, ShipPart part, int facing) {
            this.block = block;
            this.hardness = hardness;
            this.part = part;
            this.facing = facing;
        }

        public double massKg() {
            return part == ShipPart.STRUCTURE ? ShipBlocks.massForHardness(hardness) : part.massKg;
        }
    }

    public record Placement(int x, int y, int z, Mat mat) {}

    public final String name;
    private final Int2ObjectLinkedOpenHashMap<Mat> cells = new Int2ObjectLinkedOpenHashMap<>();

    public ShipTemplate(String name) {
        this.name = name;
    }

    public ShipTemplate put(int x, int y, int z, Mat m) {
        cells.put(ShipCell.pack(x, y, z), m);
        return this;
    }

    /** Fills the box between two corners (inclusive). */
    public ShipTemplate box(int x0, int y0, int z0, int x1, int y1, int z1, Mat m) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) put(x, y, z, m);
        return this;
    }

    /** The box's faces only (a hollow hull). */
    public ShipTemplate shell(int x0, int y0, int z0, int x1, int y1, int z1, Mat m) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), ay = Math.min(y0, y1), by = Math.max(y0, y1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        for (int x = ax; x <= bx; x++)
            for (int y = ay; y <= by; y++)
                for (int z = az; z <= bz; z++)
                    if (x == ax || x == bx || y == ay || y == by || z == az || z == bz) put(x, y, z, m);
        return this;
    }

    /** Empties the box (cut a cabin out of a hull). */
    public ShipTemplate clear(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) cells.remove(ShipCell.pack(x, y, z));
        return this;
    }

    /** Mirrors every block at x > 0 to −x (ships are symmetric port to starboard). */
    public ShipTemplate mirrorX() {
        List<Placement> list = placements();
        for (Placement p : list) if (p.x() > 0) put(-p.x(), p.y(), p.z(), p.mat());
        return this;
    }

    public List<Placement> placements() {
        List<Placement> out = new ArrayList<>(cells.size());
        for (var e : cells.int2ObjectEntrySet()) {
            int c = e.getIntKey();
            out.add(new Placement(ShipCell.unpackX(c), ShipCell.unpackY(c), ShipCell.unpackZ(c), e.getValue()));
        }
        return out;
    }

    /** What the stats see. */
    public List<ShipCell> cells() {
        List<ShipCell> out = new ArrayList<>(cells.size());
        for (Placement p : placements()) out.add(new ShipCell(p.x(), p.y(), p.z(), p.mat().part, p.mat().massKg(), p.mat().facing));
        return out;
    }

    public int size() {
        return cells.size();
    }

    // ------------------------------------------------------------------ the prefabs

    public static final List<String> NAMES = List.of("kestrel", "shuttle", "freighter", "explorer");

    public static ShipTemplate byName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "kestrel" -> kestrel();
            case "shuttle" -> shuttle();
            case "freighter" -> freighter();
            case "explorer" -> explorer();
            default -> throw new IllegalArgumentException("unknown ship template " + name);
        };
    }

    /**
     * The Kestrel in blocks: a 14-long fighter with swept wings, a glass canopy over the pilot and one passenger, twin
     * engines, four tanks (400 H₂, like the modelled Kestrel), one hyperdrive core and VTOL lift jets under the belly.
     */
    public static ShipTemplate kestrel() {
        ShipTemplate t = new ShipTemplate("kestrel");
        t.box(0, -1, -6, 1, -1, 4, Mat.HULL).box(0, -1, 5, 0, -1, 6, Mat.HULL_DARK);   // belly and nose
        t.box(1, 0, -6, 1, 0, -2, Mat.HULL).box(0, 0, -6, 0, 0, -3, Mat.HULL);          // fuselage aft of the cabin
        t.box(0, 0, 2, 1, 0, 4, Mat.HULL).put(0, 0, 5, Mat.HULL_DARK);                   // nose cone
        t.box(1, 1, -6, 1, 1, -3, Mat.HULL).box(0, 1, -6, 0, 1, -3, Mat.HULL_DARK);      // spine
        t.box(1, 0, -1, 1, 1, 1, Mat.GLASS).box(0, 1, 2, 1, 1, 2, Mat.GLASS);            // canopy sides and front
        t.box(0, 2, -2, 1, 2, 1, Mat.GLASS);                                              // canopy roof
        t.put(0, 0, 0, Mat.HELM).put(0, 0, -2, Mat.SEAT);
        t.box(2, 0, -5, 4, 0, -1, Mat.HULL_DARK).box(5, 0, -5, 6, 0, -3, Mat.HULL_DARK); // wings
        t.put(6, 0, -2, Mat.TRIM).put(4, 0, 0, Mat.TRIM);
        t.put(6, 0, -4, Mat.RCS);
        t.put(0, 2, -6, Mat.HULL_DARK).put(0, 3, -6, Mat.TRIM);                           // fin
        t.box(1, 0, -6, 1, 0, -5, Mat.TANK);                                              // four tanks once mirrored
        t.put(0, 0, -4, Mat.CORE);
        t.put(1, 0, -7, Mat.ENGINE);
        t.put(1, 0, 5, Mat.BRAKE);
        t.put(1, -1, -3, Mat.LIFT).put(1, -1, 2, Mat.LIFT);
        t.put(0, 0, 6, Mat.RCS);
        t.put(1, -2, -4, Mat.GEAR).put(0, -2, 3, Mat.GEAR);
        t.mirrorX();
        return t;
    }

    /** Shuttle: a boxy 7 × 5 × 13 people-mover with a glass bridge, four passenger seats, RCS on every corner. */
    public static ShipTemplate shuttle() {
        ShipTemplate t = new ShipTemplate("shuttle");
        t.shell(-3, -1, -7, 3, 3, 5, Mat.HULL);
        t.box(-3, 1, -7, 3, 1, 5, Mat.TRIM);                // waist stripe
        t.clear(-2, 0, -6, 2, 2, 4);                       // cabin
        t.box(-2, 1, 5, 2, 2, 5, Mat.GLASS).box(-3, 1, 2, -3, 2, 4, Mat.GLASS).box(3, 1, 2, 3, 2, 4, Mat.GLASS);
        t.put(0, 0, 3, Mat.HELM);
        t.put(-1, 0, 0, Mat.SEAT).put(1, 0, 0, Mat.SEAT).put(-1, 0, -3, Mat.SEAT).put(1, 0, -3, Mat.SEAT);
        t.put(0, 3, 0, Mat.LAMP).put(0, 3, -4, Mat.LAMP);
        t.box(-2, 0, -6, 2, 0, -6, Mat.TANK);
        t.box(-2, -1, -8, 2, 1, -8, Mat.GREY);
        t.put(-1, 0, -8, Mat.ENGINE).put(1, 0, -8, Mat.ENGINE).put(0, 1, -8, Mat.ENGINE);
        t.put(0, 0, 6, Mat.BRAKE);
        t.put(-2, -1, -4, Mat.LIFT).put(2, -1, -4, Mat.LIFT).put(-2, -1, 2, Mat.LIFT).put(2, -1, 2, Mat.LIFT);
        t.put(-3, 3, -7, Mat.RCS).put(3, 3, -7, Mat.RCS).put(-3, 3, 5, Mat.RCS).put(3, 3, 5, Mat.RCS);
        t.put(-2, -2, -5, Mat.GEAR).put(2, -2, -5, Mat.GEAR).put(-2, -2, 3, Mat.GEAR).put(2, -2, 3, Mat.GEAR);
        return t.rebase();
    }

    /**
     * Freighter: a 15 × 9 × 33 hull around a cargo hold of barrels, a bridge forward, a bank of twelve engines, twenty
     * lift jets under the keel (it hovers at 1 g, just), plated sides and two cores.
     */
    public static ShipTemplate freighter() {
        ShipTemplate t = new ShipTemplate("freighter");
        t.shell(-7, -2, -16, 7, 6, 16, Mat.HULL_DARK);
        t.box(-7, 2, -16, -7, 3, 16, Mat.PLATING).box(7, 2, -16, 7, 3, 16, Mat.PLATING);
        t.box(-7, 4, -16, 7, 4, -16, Mat.TRIM);
        t.clear(-6, -1, -15, 6, 5, 15);
        t.box(-6, -1, -15, 6, -1, 15, Mat.GREY);                   // deck
        // Bridge: glass across the bow at eye height, the helm and two seats in front of the hold.
        t.box(-4, 1, 16, 4, 2, 16, Mat.GLASS);
        t.put(0, 0, 13, Mat.HELM).put(-2, 0, 12, Mat.SEAT).put(2, 0, 12, Mat.SEAT);
        // The hold: barrels along both sides.
        t.box(-6, 0, -12, -5, 2, 8, Mat.CARGO).box(5, 0, -12, 6, 2, 8, Mat.CARGO);
        t.put(0, 5, -8, Mat.LAMP).put(0, 5, 0, Mat.LAMP).put(0, 5, 8, Mat.LAMP);
        t.box(-6, 0, -15, 6, 1, -14, Mat.TANK);                    // 26 tanks across the stern
        t.put(-3, 0, -13, Mat.CORE).put(3, 0, -13, Mat.CORE);
        t.box(-5, -1, -17, 5, 5, -17, Mat.GREY);
        for (int x = -4; x <= 4; x += 2) for (int y = 0; y <= 4; y += 4) t.put(x, y, -17, Mat.ENGINE);
        t.put(-5, 2, -17, Mat.ENGINE).put(5, 2, -17, Mat.ENGINE);
        for (int x = -6; x <= 6; x += 3) for (int z = -12; z <= 12; z += 8) t.put(x, -2, z, Mat.LIFT);
        t.put(0, 6, -16, Mat.RCS).put(0, 6, 16, Mat.RCS).put(-7, 6, 0, Mat.RCS).put(7, 6, 0, Mat.RCS)
                .put(-7, -2, -16, Mat.RCS).put(7, -2, 16, Mat.RCS);
        t.put(0, 0, 17, Mat.BRAKE).put(-3, 0, 17, Mat.BRAKE).put(3, 0, 17, Mat.BRAKE);
        for (int x = -5; x <= 5; x += 10) for (int z = -13; z <= 13; z += 13) t.put(x, -3, z, Mat.GEAR);
        return t.rebase();
    }

    /**
     * Explorer: a long spine of tanks between a glass observation nose and a drive section with three hyperdrive cores
     * (20 ly jumps), outrigger RCS pods and four lift jets.
     */
    public static ShipTemplate explorer() {
        ShipTemplate t = new ShipTemplate("explorer");
        t.shell(-2, -1, 2, 2, 3, 10, Mat.HULL);                     // forward module
        t.clear(-1, 0, 3, 1, 2, 9);
        t.box(-1, 0, 10, 1, 2, 10, Mat.GLASS).box(-2, 1, 6, -2, 2, 9, Mat.GLASS).box(2, 1, 6, 2, 2, 9, Mat.GLASS);
        t.put(0, 0, 8, Mat.HELM).put(0, 0, 5, Mat.SEAT);
        t.put(0, 2, 4, Mat.LAMP);
        t.box(-1, 0, -18, 1, 2, 1, Mat.HULL_DARK);                  // spine
        t.box(-1, 1, -14, 1, 1, -2, Mat.TANK);                      // 39 tanks
        t.box(-1, 1, -16, 1, 1, -16, Mat.CORE);                     // three cores: 20 ly
        t.box(-3, -1, -19, 3, 3, -19, Mat.GREY);
        t.put(-2, 0, -20, Mat.ENGINE).put(2, 0, -20, Mat.ENGINE).put(0, 2, -20, Mat.ENGINE).put(0, 0, -20, Mat.ENGINE);
        t.box(-6, 1, -10, -2, 1, -8, Mat.HULL).box(2, 1, -10, 6, 1, -8, Mat.HULL);   // outriggers
        t.put(-6, 1, -9, Mat.RCS).put(6, 1, -9, Mat.RCS).put(0, 3, 9, Mat.RCS).put(0, 3, -18, Mat.RCS);
        t.put(-1, -1, -6, Mat.LIFT).put(1, -1, -6, Mat.LIFT).put(-1, -2, 6, Mat.LIFT).put(1, -2, 6, Mat.LIFT);
        t.put(-1, -1, -16, Mat.LIFT).put(1, -1, -16, Mat.LIFT);
        t.put(0, 0, 11, Mat.TRIM).put(0, 0, 12, Mat.BRAKE);
        t.put(-2, -2, -12, Mat.GEAR).put(2, -2, -12, Mat.GEAR).put(-2, -2, 4, Mat.GEAR).put(2, -2, 4, Mat.GEAR);
        t.box(-2, -1, -12, 2, -1, -12, Mat.HULL);
        return t.rebase();
    }

    private void removeNulls() {
        cells.values().removeIf(java.util.Objects::isNull);
    }

    /** Moves the layout so the helm is at 0,0,0. */
    private ShipTemplate rebase() {
        removeNulls();
        Placement helm = null;
        for (Placement p : placements()) if (p.mat() == Mat.HELM) helm = p;
        if (helm == null || helm.x() == 0 && helm.y() == 0 && helm.z() == 0) return this;
        List<Placement> all = placements();
        cells.clear();
        for (Placement p : all) put(p.x() - helm.x(), p.y() - helm.y(), p.z() - helm.z(), p.mat());
        return this;
    }
}
