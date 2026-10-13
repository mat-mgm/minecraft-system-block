package com.systemblock;

import com.systemblock.client.SystemBlockScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

public class SystemBlock extends Block implements EntityBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED; // Input power state
    public static final BooleanProperty EMITTING = BooleanProperty.create("emitting"); // Output pulse state

    public SystemBlock(BlockBehaviour.Properties properties) {
        super(properties.sound(SoundType.METAL).strength(1.5f));
        this.registerDefaultState(this.defaultBlockState()
                .setValue(POWERED, false)
                .setValue(EMITTING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, EMITTING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SystemBlockEntity(pos, state);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(EMITTING) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(EMITTING) ? 15 : 0;
    }

    public static void triggerSuccessPulse(ServerLevel level, BlockPos pos, BlockState state) {
        // Turn on emitting and schedule a tick 4 redstone ticks later to turn it off
        level.setBlock(pos, state.setValue(EMITTING, true), Block.UPDATE_ALL);
        updateNeighbors(level, pos);
        level.scheduleTick(pos, state.getBlock(), 4);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(EMITTING)) {
            level.setBlock(pos, state.setValue(EMITTING, false), Block.UPDATE_ALL);
            updateNeighbors(level, pos);
        }
    }

    private static void updateNeighbors(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            level.neighborChanged(pos.relative(dir), level.getBlockState(pos).getBlock(), null);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, Orientation orientation, boolean isMoving) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, isMoving);
        if (level.isClientSide()) {
            return;
        }

        boolean hasSignal = level.hasNeighborSignal(pos);
        boolean isPowered = state.getValue(POWERED);

        if (hasSignal != isPowered) {
            level.setBlock(pos, state.setValue(POWERED, hasSignal), Block.UPDATE_ALL);
            if (hasSignal) {
                // Rising edge trigger: start process or send newline to running session
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof SystemBlockEntity systemBlockEntity) {
                    if (systemBlockEntity.isProcessRunning()) {
                        systemBlockEntity.sendInputToProcess(""); // pulse sends Enter
                    } else {
                        systemBlockEntity.startProcess(null);
                    }
                }
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SystemBlockEntity systemBlockEntity) {
                Minecraft.getInstance().setScreenAndShow(new SystemBlockScreen(systemBlockEntity));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
