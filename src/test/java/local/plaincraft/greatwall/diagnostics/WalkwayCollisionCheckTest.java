package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WalkwayCollisionCheckTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void savedWestStairHasPlayerSupportEvenThoughTheOldFullFaceCheckRejectsIt() {
        var state = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        assertFalse(state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP),
                "preserve the exact old false-negative witness");
        assertTrue(clearWithBase(shape(state)).valid());
    }

    @Test void allFourBottomStairDirectionsRetainNativePlayerSupport() {
        for (var facing : Direction.Plane.HORIZONTAL) {
            var state = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            assertTrue(clearWithBase(shape(state)).valid(), facing.toString());
        }
    }

    @Test void fullBlocksAndTopSlabsSupportIntegerFeet() {
        assertTrue(clearWithBase(shape(Blocks.STONE.defaultBlockState())).valid());
        assertTrue(clearWithBase(shape(Blocks.STONE_BRICK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP))).valid());
    }

    @Test void airAndBottomSlabsDoNotFakeSupportAtIntegerFeet() {
        assertFalse(clearWithBase(Shapes.empty()).valid());
        assertFalse(clearWithBase(shape(Blocks.STONE_BRICK_SLAB.defaultBlockState())).valid());
    }

    @Test void footCollisionStillRejectsEvenWithSolidGround() {
        var result = WalkwayCollisionCheck.inspect(Shapes.block(), Shapes.empty(), Shapes.block());
        assertFalse(result.feetClear());
        assertFalse(result.valid());
    }

    @Test void headCollisionStillRejectsEvenWithSolidGround() {
        var result = WalkwayCollisionCheck.inspect(Shapes.empty(), Shapes.block(), Shapes.block());
        assertFalse(result.headClear());
        assertFalse(result.valid());
    }

    @Test void edgeSliverOutsidePlayerFootprintDoesNotCountAsGround() {
        assertFalse(clearWithBase(Shapes.box(.9, 0, 0, 1, 1, 1)).supported());
    }

    @Test void tallBaseShapeCannotInvadePlayerBodyToFakeSupport() {
        var result = clearWithBase(Shapes.box(.375, 0, .375, .625, 1.5, .625));
        assertTrue(result.supported());
        assertFalse(result.baseBodyClear());
        assertFalse(result.valid());
    }

    @Test void groundFarBelowFeetDoesNotCountAsAnEntrancePlatform() {
        assertFalse(clearWithBase(Shapes.box(0, 0, 0, 1, .9, 1)).valid());
    }

    private static VoxelShape shape(BlockState state) {
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
    private static WalkwayCollisionCheck.Result clearWithBase(VoxelShape shape) {
        return WalkwayCollisionCheck.inspect(Shapes.empty(), Shapes.empty(), shape);
    }
}
