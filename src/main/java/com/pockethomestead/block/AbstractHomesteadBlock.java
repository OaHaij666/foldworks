package com.pockethomestead.block;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.permission.AccessControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 口袋家园方块基类 - 提取4个方块类的共同逻辑：
 * - 渲染形状为 MODEL
 * - 右键打开菜单
 * - 支持红石比较器输出
 * - 水平朝向（facing属性）
 *
 * 子类只需实现 codec() 和 newBlockEntity()
 */
public abstract class AbstractHomesteadBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Direction.Axis> STRESS_AXIS = BlockStateProperties.AXIS;

    protected AbstractHomesteadBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(STRESS_AXIS, Direction.Axis.Z));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STRESS_AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction front = context.getHorizontalDirection().getOpposite();
        return this.defaultBlockState()
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
            BaseChestBlockEntity chest = HomesteadChestAccess.resolve(be);
            if (chest != null && player instanceof ServerPlayer serverPlayer && !AccessControl.canUseChest(serverPlayer, chest)) {
                AccessControl.deny(serverPlayer);
                return InteractionResult.SUCCESS;
            }
            if (be instanceof MenuProvider) {
                player.openMenu((MenuProvider) be, pos);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    /**
     * 放置时自动设置所有者UUID并生成默认箱子ID
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            BaseChestBlockEntity chest = HomesteadChestAccess.resolve(be);
            if (chest != null) {
                chest.loadFromItem(stack, level.registryAccess());
                if (placer instanceof Player player && (chest.getOwnerUUID() == null || chest.getChestId().isEmpty())) {
                    chest.setOwnerUUID(player.getUUID());
                    String autoId = com.pockethomestead.registry.ChestRegistryManager.getInstance().generateNextChestId(player.getUUID());
                    chest.setChestId(autoId);
                }
                // onLoad 早于 setPlacedBy，此时 owner/id 才就绪，需在此补注册
                chest.registerIfReady();
            }
        }
        super.setPlacedBy(level, pos, state, placer, stack);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(blockEntity);
        if (chest != null) {
            ItemStack packed = new ItemStack(asItem());
            chest.saveToItem(packed, params.getLevel().registryAccess());
            return List.of(packed);
        }
        return super.getDrops(state, params);
    }

    /**
     * 为箱子BlockEntity提供ticker，驱动物品传输逻辑
     */
    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<BaseChestBlockEntity>) BaseChestBlockEntity::serverTick;
    }
}
