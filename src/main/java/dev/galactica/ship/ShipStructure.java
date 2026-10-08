package dev.galactica.ship;

import dev.galactica.ship.yard.ShipAssembler;
import dev.galactica.ship.yard.ShipBlocks;
import dev.galactica.ship.yard.ShipCell;
import dev.galactica.ship.yard.ShipPart;
import dev.galactica.ship.yard.ShipPartBlocks;
import dev.galactica.ship.yard.ShipOccupancy;
import dev.galactica.ship.yard.ShipStats;
import dev.galactica.ship.yard.ShipSystems;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A block-built ship: a {@link Starship} whose hull is the blocks it was assembled from ({@link ShipBlocks}) and
 * whose mass, engines, tanks, drive, seats, size and hull probes come from them ({@link ShipStats}). Everything else
 * is the Starship's, unchanged: the pilot's client-authoritative flight through {@link ShipControlPayload} and the
 * server's clamps (fed by this spec, which the server computes itself), Krakensbane, the floating origin, the orbital
 * frame, planet entry and exit, warp and the hyperjump; every {@code instanceof Starship} in the mod applies.
 *
 * <p>The blocks live in the entity, so recentres and dimension changes move them with it. Clients get them in a
 * {@link ShipBlocksPayload} when they start tracking the ship; until then the ship is not drawn and its pilot's
 * client does not fly it ({@link #flightReady}). Disassembly ({@link ShipAssembler#disassemble}) puts them back on
 * the grid when the ship stands still: sneak and use the ship from outside.
 */
public class ShipStructure extends Starship {
    /** Spec of a ship whose blocks have not arrived (client) or that has none: one seat, no engines. */
    private static final ShipSpec EMPTY_SPEC = ShipStats.compute(List.of(ShipCell.structure(0, 0, 0, 1_000))).spec();

    private ShipBlocks blocks = ShipBlocks.EMPTY;
    private ShipSpec spec = EMPTY_SPEC;
    /** Client: bumped whenever the blocks change, so the renderer rebuilds its mesh. */
    private int blocksVersion;
    /** Body-frame centre of mass in ship-grid cell coordinates (where the blocks sit relative to the pivot). */
    private org.joml.Vector3d com = new org.joml.Vector3d();

    public ShipStructure(EntityType<? extends ShipStructure> type, Level level) {
        super(type, level);
    }

    @Override
    public ShipSpec spec() {
        return spec;
    }

    public ShipBlocks blocks() {
        return blocks;
    }

    public int blocksVersion() {
        return blocksVersion;
    }

    /** Centre of mass, ship-grid cell coordinates: block cell c sits at {@code c + 0.5 − com} in the body frame. */
    public org.joml.Vector3dc centreOfMass() {
        return com;
    }

    /** Docking ports, hangar, shields, reactors, scanners. */
    public ShipSystems systems() {
        return systems;
    }

    @Override
    public float maxShield() {
        return systems.shieldCapacity();
    }

    @Override
    public float shieldRecharge() {
        return systems.shieldRecharge();
    }

    /** Replaces the block set and recomputes the spec (server on assembly or load, client on the payload). */
    public void setBlocks(ShipBlocks blocks) {
        this.blocks = blocks;
        if (blocks.isEmpty()) {
            spec = EMPTY_SPEC;
            com = new org.joml.Vector3d(0.5, 0.5, 0.5);
            systems = ShipSystems.NONE;
            occupancy = null;
            grid = null;
            cellMin = new int[3];
            cellMax = new int[] {1, 1, 1};
        } else {
            ShipStats.Result r = ShipStats.compute(blocks.shipCells());
            spec = r.spec();
            com = r.com();
            systems = ShipSystems.compute(blocks.shipCells());
            occupancy = blocks.size() >= OCCUPANCY_BLOCKS ? new ShipOccupancy(blocks.shipCells()) : null;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (ShipCell c : blocks.shipCells()) {
                x0 = Math.min(x0, c.x()); x1 = Math.max(x1, c.x() + 1);
                y0 = Math.min(y0, c.y()); y1 = Math.max(y1, c.y() + 1);
                z0 = Math.min(z0, c.z()); z1 = Math.max(z1, c.z() + 1);
            }
            bodyMin = new org.joml.Vector3d(x0, y0, z0).sub(com);
            bodyMax = new org.joml.Vector3d(x1, y1, z1).sub(com);
            cellMin = new int[] {x0, y0, z0};
            cellMax = new int[] {x1, y1, z1};
            grid = new ShipOccupancy(blocks.shipCells());
        }
        blocksVersion++;
        refreshDimensions();
        if (!level().isClientSide()) {
            setHydrogen(hydrogen());
            setExotic(exotic());
            setHull(Math.min(hull(), maxHull()));
            setShield(Math.min(shield(), maxShield()));
        }
    }

    // ------------------------------------------------------------------ occupancy-grid terrain collision

    /** Ships of at least this many blocks also collide through the occupancy grid ({@link ShipOccupancy}). */
    public static final int OCCUPANCY_BLOCKS = 256;
    private ShipSystems systems = ShipSystems.NONE;
    private @org.jspecify.annotations.Nullable ShipOccupancy occupancy;
    private org.joml.Vector3d bodyMin = new org.joml.Vector3d(), bodyMax = new org.joml.Vector3d();
    /** The blocks' extent in the ship grid: lowest cell, and one past the highest. */
    private int[] cellMin = new int[3], cellMax = {1, 1, 1};
    /** Every block's cell, for ship-to-ship contact ({@link dev.galactica.ship.collide.ShipCollisions}); null while empty. */
    private @org.jspecify.annotations.Nullable ShipOccupancy grid;

    /** Lowest occupied cell of the ship grid (x, y, z); do not modify. */
    public int[] cellMin() {
        return cellMin;
    }

    /** One past the highest occupied cell (x, y, z); do not modify. */
    public int[] cellMax() {
        return cellMax;
    }

    /** All blocks as an occupancy grid (every ship, whatever its size), or null before the blocks arrive. */
    public @org.jspecify.annotations.Nullable ShipOccupancy grid() {
        return grid;
    }

    /**
     * Artificial gravity on deck in space: a gravity generator, a reactor to power it, hydrogen aboard for the
     * reactor and a working hull.
     */
    public boolean artificialGravityOn() {
        return systems.artificialGravity() && hydrogen() > 0 && !enginesDead();
    }

    @Override
    public void tick() {
        super.tick();
        if (isRemoved()) return;
        dev.galactica.ship.deck.ShipVoxelCollision.track(this);
        if (!level().isClientSide()) dev.galactica.ship.deck.Deck.serverTick(this);
    }

    /** Hulls are solid through their blocks ({@link dev.galactica.ship.deck.ShipVoxelCollision}), not a box. */
    @Override
    public boolean canBeCollidedWith(net.minecraft.world.entity.@org.jspecify.annotations.Nullable Entity other) {
        return false;
    }
    /** Dev counters: occupancy tests run, and world blocks examined by them. */
    public static long occupancyTests, occupancyBlocks;

    public boolean usesOccupancyGrid() {
        return occupancy != null;
    }

    /**
     * The probes first (cheap, exact at the outline); then, for a large hull, every solid world block inside the
     * hull's rotated bounding box is tested against the occupancy grid. Chunk sections that hold only air are skipped
     * whole, so in open space or high above the ground the test costs a handful of section lookups.
     */
    @Override
    public boolean hullClearAt(Level level, Vec3 origin, org.joml.Quaternionfc q) {
        if (!super.hullClearAt(level, origin, q)) return false;
        ShipOccupancy occ = occupancy;
        return occ == null || occupancyClear(level, origin, q, occ);
    }

    private boolean occupancyClear(Level level, Vec3 origin, org.joml.Quaternionfc q, ShipOccupancy occ) {
        occupancyTests++;
        org.joml.Vector3d pivotW = new org.joml.Vector3d(origin.x, origin.y + pivot(), origin.z);
        // World AABB of the rotated body box.
        double ax = Double.MAX_VALUE, ay = Double.MAX_VALUE, az = Double.MAX_VALUE, bx = -Double.MAX_VALUE, by = -Double.MAX_VALUE, bz = -Double.MAX_VALUE;
        org.joml.Vector3d v = new org.joml.Vector3d();
        for (int k = 0; k < 8; k++) {
            v.set((k & 1) == 0 ? bodyMin.x : bodyMax.x, (k & 2) == 0 ? bodyMin.y : bodyMax.y, (k & 4) == 0 ? bodyMin.z : bodyMax.z);
            q.transform(v);
            ax = Math.min(ax, v.x); bx = Math.max(bx, v.x);
            ay = Math.min(ay, v.y); by = Math.max(by, v.y);
            az = Math.min(az, v.z); bz = Math.max(bz, v.z);
        }
        int x0 = net.minecraft.util.Mth.floor(pivotW.x + ax), x1 = net.minecraft.util.Mth.floor(pivotW.x + bx);
        int y0 = Math.max(level.getMinY(), net.minecraft.util.Mth.floor(pivotW.y + ay)), y1 = Math.min(level.getMaxY(), net.minecraft.util.Mth.floor(pivotW.y + by));
        int z0 = net.minecraft.util.Mth.floor(pivotW.z + az), z1 = net.minecraft.util.Mth.floor(pivotW.z + bz);
        if (y0 > y1) return true;
        org.joml.Quaternionf inverse = new org.joml.Quaternionf(q).conjugate();
        org.joml.Vector3d scratch = new org.joml.Vector3d();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                var access = level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (!(access instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) continue;
                for (int sy = level.getSectionIndex(y0); sy <= level.getSectionIndex(y1); sy++) {
                    var section = chunk.getSection(sy);
                    if (section.hasOnlyAir()) continue;
                    int base = net.minecraft.core.SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(sy));
                    int lx0 = Math.max(x0, cx << 4), lx1 = Math.min(x1, (cx << 4) + 15);
                    int ly0 = Math.max(y0, base), ly1 = Math.min(y1, base + 15);
                    int lz0 = Math.max(z0, cz << 4), lz1 = Math.min(z1, (cz << 4) + 15);
                    for (int y = ly0; y <= ly1; y++) {
                        for (int x = lx0; x <= lx1; x++) {
                            for (int z = lz0; z <= lz1; z++) {
                                BlockState s = section.getBlockState(x & 15, y & 15, z & 15);
                                if (s.isAir()) continue;
                                occupancyBlocks++;
                                pos.set(x, y, z);
                                if (s.getCollisionShape(level, pos).isEmpty()) continue;
                                if (occ.overlapsWorldBlock(x, y, z, pivotW, inverse, com, scratch)) return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        ShipSpec s = spec;
        return EntityDimensions.fixed(s.boxWidth(), s.boxHeight());
    }

    @Override
    public boolean flightReady() {
        return !blocks.isEmpty() && super.flightReady();
    }

    /** Number of a part aboard. */
    public int count(ShipPart part) {
        int n = 0;
        for (ShipCell c : blocks.shipCells()) if (c.part() == part) n++;
        return n;
    }

    private InteractionResult interactBase(Player player, InteractionHand hand, Vec3 location) {
        if (player.getVehicle() != this && player.isSecondaryUseActive() && player.getItemInHand(hand).isEmpty()) {
            if (level().isClientSide()) return InteractionResult.SUCCESS;
            ShipAssembler.disassemble(this, player);
            return InteractionResult.SUCCESS_SERVER;
        }
        return super.interact(player, hand, location);
    }

    @Override
    protected int serviceBatch() {
        return 64;
    }

    /**
     * Damage drains the shields first, then the hull. At zero hull the engines die ({@link #enginesDead}); with
     * {@code shipDestruction} on (the default) the ship then breaks up ({@link ShipWreck}): crew set down safely, docked
     * craft released, a share of the blocks and every container's contents dropped. With it off the ship stays a
     * wreck to repair with iron ingots. Creative players never wreck a ship by hitting it.
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved()) return true;
        if (isInvulnerableToBase(source)) return false;
        setHurtDir(-getHurtDir());
        setHurtTime(10);
        markHurt();
        if (source.getEntity() instanceof Player p && p.getAbilities().instabuild) return true;
        setHull(hull() - absorbByShield(amount * 5F));
        if (hull() <= 0 && dev.galactica.config.GalacticaConfigs.get().shipDestruction()) ShipWreck.breakUp(this, level);
        return true;
    }

    /**
     * Using the ship from outside: sneak with an empty hand disassembles; a click whose ray meets a container block of
     * the hull ({@link ShipCargo#raycast}) opens that container; anything else boards or services as usual.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (player.getVehicle() != this && !player.isSecondaryUseActive() && player.getItemInHand(hand).isEmpty()) {
            int index = ShipCargo.raycast(this, player.getEyePosition(), player.getViewVector(1F), 8.0);
            if (index >= 0 && ShipCargo.isContainer(blocks, index)) {
                if (level().isClientSide()) return InteractionResult.SUCCESS;
                ShipCargo.open((ServerPlayer) player, this, index);
                return InteractionResult.SUCCESS_SERVER;
            }
        }
        return interactBase(player, hand, location);
    }

    @Override
    protected Item getDropItem() {
        return ShipPartBlocks.HELM.asItem();
    }

    /** Server: sends the blocks to one tracking player. */
    public void sendBlocksTo(ServerPlayer player) {
        if (!blocks.isEmpty()) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new ShipBlocksPayload(getId(), blocks));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        blocks.save(out.child("ship_blocks"));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        // Blocks first: the spec sets the tank, drive and hull limits the Starship's values are clamped to.
        in.child("ship_blocks").ifPresent(c -> setBlocks(ShipBlocks.load(c)));
        super.readAdditionalSaveData(in);
    }
}
