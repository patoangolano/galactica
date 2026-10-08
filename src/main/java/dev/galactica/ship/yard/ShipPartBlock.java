package dev.galactica.ship.yard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A ship-part block ({@link ShipPart}). Three placement styles:
 * <ul>
 *   <li>{@link Facing#NONE}: tanks, RCS, cores, gear, plating;</li>
 *   <li>{@link Facing#HORIZONTAL} (helm, crew seat): faces where the player looks, so a pilot sitting on it looks the
 *       way the builder did; the helm's facing is the ship's nose;</li>
 *   <li>{@link Facing#ALL} (thruster, docking port): the nozzle (or the port's mating face) points back at the player
 *       who places it against the hull.</li>
 * </ul>
 * Right-clicking the helm assembles the ship ({@link ShipAssembler}).
 */
public class ShipPartBlock extends Block {
    public enum Facing { NONE, HORIZONTAL, ALL }

    public static final EnumProperty<Direction> HORIZONTAL = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Direction> ALL = BlockStateProperties.FACING;

    /** Seat: a cushion up to the seated hips and a back rest on the aft side (facing north: the south edge). */
    private static final VoxelShape[] SEAT = new VoxelShape[4];

    static {
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 4; i++) {
            Direction d = dirs[i];
            VoxelShape cushion = Block.box(1, 0, 1, 15, 7, 15);
            // The back rest sits on the side opposite the facing.
            VoxelShape back = switch (d) {
                case NORTH -> Block.box(1, 7, 13, 15, 16, 15);
                case SOUTH -> Block.box(1, 7, 1, 15, 16, 3);
                case EAST -> Block.box(1, 7, 1, 3, 16, 15);
                default -> Block.box(13, 7, 1, 15, 16, 15);
            };
            SEAT[d.get2DDataValue()] = Shapes.or(cushion, back);
        }
    }

    private final ShipPart part;
    private final Facing facing;

    public ShipPartBlock(Properties props, ShipPart part, Facing facing) {
        super(props);
        this.part = part;
        this.facing = facing;
        if (facing == Facing.HORIZONTAL) registerDefaultState(stateDefinition.any().setValue(HORIZONTAL, Direction.NORTH));
        else if (facing == Facing.ALL) registerDefaultState(stateDefinition.any().setValue(ALL, Direction.NORTH));
    }

    public ShipPart part() {
        return part;
    }

    /** The facing of a placed part, or null for parts without one. */
    public static @Nullable Direction facingOf(BlockState state) {
        if (state.hasProperty(ALL)) return state.getValue(ALL);
        if (state.hasProperty(HORIZONTAL)) return state.getValue(HORIZONTAL);
        return null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // Called from the Block constructor, before our fields are set: decide by the subclass-independent marker.
        Facing f = PENDING.get();
        if (f == Facing.HORIZONTAL) builder.add(HORIZONTAL);
        else if (f == Facing.ALL) builder.add(ALL);
    }

    /** The facing style for the block being constructed (createBlockStateDefinition runs inside super()). */
    static final ThreadLocal<Facing> PENDING = ThreadLocal.withInitial(() -> Facing.NONE);

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s = defaultBlockState();
        if (facing == Facing.HORIZONTAL) return s.setValue(HORIZONTAL, ctx.getHorizontalDirection());
        if (facing == Facing.ALL) return s.setValue(ALL, ctx.getNearestLookingDirection().getOpposite());
        return s;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (state.hasProperty(HORIZONTAL)) return state.setValue(HORIZONTAL, rotation.rotate(state.getValue(HORIZONTAL)));
        if (state.hasProperty(ALL)) return state.setValue(ALL, rotation.rotate(state.getValue(ALL)));
        return state;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (state.hasProperty(HORIZONTAL)) return state.rotate(mirror.getRotation(state.getValue(HORIZONTAL)));
        if (state.hasProperty(ALL)) return state.rotate(mirror.getRotation(state.getValue(ALL)));
        return state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (part == ShipPart.HELM || part == ShipPart.CREW_SEAT) return SEAT[state.getValue(HORIZONTAL).get2DDataValue()];
        return Shapes.block();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (part != ShipPart.HELM) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ShipAssembler.assemble((net.minecraft.server.level.ServerLevel) level, pos, player);
        return InteractionResult.SUCCESS_SERVER;
    }
}
