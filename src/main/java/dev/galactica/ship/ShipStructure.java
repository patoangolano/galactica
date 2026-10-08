package dev.galactica.ship;

import dev.galactica.ship.yard.ShipAssembler;
import dev.galactica.ship.yard.ShipBlocks;
import dev.galactica.ship.yard.ShipCell;
import dev.galactica.ship.yard.ShipPart;
import dev.galactica.ship.yard.ShipPartBlocks;
import dev.galactica.ship.yard.ShipStats;
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

    /** Replaces the block set and recomputes the spec (server on assembly or load, client on the payload). */
    public void setBlocks(ShipBlocks blocks) {
        this.blocks = blocks;
        if (blocks.isEmpty()) {
            spec = EMPTY_SPEC;
            com = new org.joml.Vector3d(0.5, 0.5, 0.5);
        } else {
            ShipStats.Result r = ShipStats.compute(blocks.shipCells());
            spec = r.spec();
            com = r.com();
        }
        blocksVersion++;
        refreshDimensions();
        if (!level().isClientSide()) {
            setHydrogen(hydrogen());
            setExotic(exotic());
            setHull(Math.min(hull(), maxHull()));
        }
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        ShipSpec s = spec;
        return EntityDimensions.fixed(s.boxWidth(), s.boxHeight());
    }

    @Override
    public boolean flightReady() {
        return !blocks.isEmpty();
    }

    /** Number of a part aboard. */
    public int count(ShipPart part) {
        int n = 0;
        for (ShipCell c : blocks.shipCells()) if (c.part() == part) n++;
        return n;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
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
     * Damage wears the hull down but never scatters the blocks: at zero hull the ship is a wreck to repair with iron
     * ingots, not a lost build. Creative players remove it only by disassembling it.
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved()) return true;
        if (isInvulnerableToBase(source)) return false;
        setHurtDir(-getHurtDir());
        setHurtTime(10);
        markHurt();
        if (!(source.getEntity() instanceof Player p && p.getAbilities().instabuild)) setHull(hull() - amount * 5F);
        return true;
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
