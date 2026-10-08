package dev.galactica.ship.yard;

/**
 * What a block does in a block-built ship. Ordinary blocks are {@link #STRUCTURE} (mass only); the ship-part blocks
 * ({@code ShipPartBlocks}) carry the rest. Masses are for a 1 m³ part block, kg; a structure block's mass comes from
 * its material ({@code ShipAssembler.structureMass}).
 */
public enum ShipPart {
    STRUCTURE(0),
    /** Pilot seat and console: the assembly anchor; its facing is the ship's nose. */
    HELM(800),
    CREW_SEAT(150),
    /** Main engine: thrust opposite its nozzle (the block's facing), burning hydrogen. */
    THRUSTER(2_000),
    /** Reaction-control quad: translation authority on every axis, and turning torque. */
    RCS(300),
    HYDROGEN_TANK(600),
    /** Enables the hyperdrive and holds exotic matter; more cores, longer jumps. */
    HYPERDRIVE_CORE(3_000),
    /** Ground contact: its feet are always hull probes. */
    LANDING_GEAR(400),
    /** Armoured hull: heavy, adds hull points. */
    HULL_PLATING(900),
    /** Docking port: its face (the block's facing) mates with another ship's port; the lighter ship rides the heavier. */
    DOCKING_PORT(700),
    /** Hangar bay floor: the empty space above a run of these holds a smaller ship that settles into it slowly. */
    HANGAR_BAY(600),
    /** Shield generator: a shield pool that absorbs damage before the hull, recharged from the reactors. */
    SHIELD_GENERATOR(1_200),
    /** Fusion reactor: the power source for shields and scanners; burns a little hydrogen while it works. */
    REACTOR(2_500),
    /** Scanner array: lists bodies, ships, derelicts and ore around the ship on the cockpit display and the map. */
    SCANNER(500),
    /** Artificial gravity: in space, one ship-down g on deck while a reactor powers it and there is hydrogen aboard. */
    GRAVITY_GENERATOR(1_500);

    public final double massKg;

    ShipPart(double massKg) {
        this.massKg = massKg;
    }

    /** Main-engine thrust of one thruster block, N (a 100 t ship with four of them pulls 2.4 g). */
    public static final double THRUSTER_N = 0.6e6;
    /** RCS thrust of one RCS block, per axis, N (the Kestrel's whole RCS). */
    public static final double RCS_N = 0.5e6;
    /** The helm's own cold-gas trim jets, per axis, N: enough to turn a small hull, not to hover it. */
    public static final double HELM_RCS_N = 0.05e6;
    /** Hydrogen cells per tank block. */
    public static final float TANK_CELLS = 100F;
    /** Exotic-matter units per hyperdrive core. */
    public static final int CORE_EXOTIC = 16;
    /** Jump tier: 10 ly with one core, +5 ly per extra core, at most {@link #MAX_JUMP_LY}. */
    public static final double FIRST_CORE_LY = 10, EXTRA_CORE_LY = 5, MAX_JUMP_LY = 30;
    /** Hull points per plating block, and per any other block. */
    public static final float PLATING_HULL = 3F, BLOCK_HULL = 0.25F, BASE_HULL = 40F, MAX_HULL = 1_000F;
    /** Shield points per generator (hull-point units: one point of damage costs 5). */
    public static final float SHIELD_PER_GENERATOR = 60F;
    /** Shield points per second one reactor recharges, and hydrogen cells per shield point recharged. */
    public static final float REACTOR_RECHARGE = 4F, HYDROGEN_PER_SHIELD = 1F / 200F;
    /** Scanner range, blocks: the first array, plus this per extra one, capped. */
    public static final double SCANNER_RANGE = 1_024, EXTRA_SCANNER_RANGE = 512, MAX_SCANNER_RANGE = 4_096;
}
