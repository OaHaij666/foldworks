package com.pockethomestead.compat.create;

import com.mojang.serialization.MapCodec;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CreateHomesteadChestBlock extends KineticBlock implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Direction.Axis> STRESS_AXIS = BlockStateProperties.AXIS;
    public static final MapCodec<CreateHomesteadChestBlock> CODEC = simpleCodec(CreateHomesteadChestBlock::new);

    public CreateHomesteadChestBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(STRESS_AXIS, Direction.Axis.Z));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STRESS_AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction front = context.getHorizontalDirection().getOpposite();
        return defaultBlockState()
                .setValue(FACING, front)
                .setValue(STRESS_AXIS, front.getAxis());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MenuProvider provider) player.openMenu(provider, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(level.getBlockEntity(pos));
        return chest == null ? 0 : AbstractContainerMenu.getRedstoneSignalFromBlockEntity(chest);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (placer instanceof Player player && !level.isClientSide) {
            BaseChestBlockEntity chest = HomesteadChestAccess.resolve(level.getBlockEntity(pos));
            if (chest != null) {
                if (chest.getOwnerUUID() == null || chest.getChestId().isEmpty()) {
                    chest.setOwnerUUID(player.getUUID());
                    String autoId = com.pockethomestead.registry.ChestRegistryManager.getInstance().generateNextChestId(player.getUUID());
                    chest.setChestId(autoId);
                }
                chest.registerIfReady();
            }
        }
        super.setPlacedBy(level, pos, state, placer, stack);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY));
        if (chest != null) {
            ItemStack packed = new ItemStack(asItem());
            chest.saveToItem(packed, params.getLevel().registryAccess());
            return List.of(packed);
        }
        return super.getDrops(state, params);
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis() != state.getValue(STRESS_AXIS)) return false;
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(level.getBlockEntity(pos));
        if (chest == null || !chest.hasStressUpgrade()) return false;
        return isConfiguredStressAxis(chest.getConfiguredStressInputWorldSide(), face)
                || isConfiguredStressAxis(chest.getConfiguredStressOutputWorldSide(), face);
    }

    private boolean isConfiguredStressAxis(@Nullable Direction configuredSide, Direction face) {
        return configuredSide != null && configuredSide.getAxis() == face.getAxis();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(STRESS_AXIS);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CreateHomesteadChestBlockEntity(pos, state);
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (tickerLevel, tickerPos, tickerState, blockEntity) -> {
            if (blockEntity instanceof CreateHomesteadChestBlockEntity chest) chest.tick();
        };
    }
}
