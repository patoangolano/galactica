package dev.galactica.ship.yard;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The block set survives entity NBT and the network payload; the assembly turns are reversible. */
class ShipBlocksCodecTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static ShipBlocks sample(int n) {
        ShipBlocks.Builder b = new ShipBlocks.Builder();
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        CompoundTag barrel = new CompoundTag();
        barrel.putString("id", "minecraft:barrel");
        barrel.putString("CustomName", "\"cargo\"");
        for (int i = 0; i < n; i++) {
            int x = i % 16 - 8, y = i / 256 - 4, z = i / 16 % 16 - 8;
            BlockState s = i % 3 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : i % 3 == 1 ? stairs : Blocks.BARREL.defaultBlockState();
            b.add(x, y, z, s, i % 3 == 2 && i < 30 ? barrel : null);
        }
        return b.build();
    }

    private static void assertSame(ShipBlocks a, ShipBlocks b, boolean withBlockEntities) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.cell(i), b.cell(i));
            assertEquals(a.state(i), b.state(i));
            if (withBlockEntities) assertEquals(a.blockEntity(i), b.blockEntity(i));
        }
    }

    @Test
    void entityNbtRoundTrip() {
        ShipBlocks a = sample(500);
        TagValueOutput out = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        a.save(out);
        CompoundTag tag = out.buildResult();
        ShipBlocks b = ShipBlocks.load(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag));
        assertSame(a, b, true);
        assertEquals(a.blockEntityCount(), b.blockEntityCount());
        assertTrue(a.blockEntityCount() > 0);
    }

    @Test
    void payloadRoundTripAndSize() {
        ShipBlocks a = sample(AssemblyFill.MAX_BLOCKS);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        a.write(buf);
        int bytes = buf.readableBytes();
        assertTrue(bytes <= 64 * 1024, "a 4 096-block ship fits 64 kB: " + bytes);
        ShipBlocks b = ShipBlocks.read(buf);
        assertSame(a, b, false);
        assertNull(b.blockEntity(2), "block-entity data stays on the server");
    }

    @Test
    void assemblyTurnsAreReversible() {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        for (Direction f : Direction.Plane.HORIZONTAL) {
            Rotation r = ShipFrames.toWorld(f), back = ShipFrames.inverse(r);
            assertEquals(stairs, stairs.rotate(r).rotate(back));
            // Body +Z (the nose) lands on the helm's facing.
            assertEquals(f, r.rotate(Direction.SOUTH));
            double[] t = ShipFrames.turn(r, 0, 1);
            assertEquals(f.getStepX(), t[0], 1e-12);
            assertEquals(f.getStepZ(), t[1], 1e-12);
            double[] u = ShipFrames.turn(back, t[0], t[1]);
            assertEquals(0, u[0], 1e-12);
            assertEquals(1, u[1], 1e-12);
        }
    }
}
