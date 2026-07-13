package com.foldworks.item;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.permission.AccessControl;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** 工造终端：合并了创建与管理功能，右键打开维度工造主界面。 */
public class FoldworksTabletItem extends Item {

    public FoldworksTabletItem(Properties properties) {
        super(properties);
    }

    public static ItemAttributeModifiers createTabletAttributes(double attackDamage, double attackSpeed) {
        var builder = ItemAttributeModifiers.builder();
        builder.add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("foldworks", "tablet_attack_damage"),
                        attackDamage, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);
        builder.add(Attributes.ATTACK_SPEED,
                new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("foldworks", "tablet_attack_speed"),
                        attackSpeed, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);
        return builder.build();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            BlockEntity blockEntity = level.getBlockEntity(context.getClickedPos());
            BaseChestBlockEntity chest = FoldworksChestAccess.resolve(blockEntity);
            if (chest == null) return InteractionResult.PASS;

            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                if (!AccessControl.canUseChest(serverPlayer, chest)) {
                    AccessControl.deny(serverPlayer);
                    return InteractionResult.SUCCESS;
                }
                FoldworksTabletBinding.bind(context.getItemInHand(), chest);
                player.displayClientMessage(Component.translatable("foldworks.tablet.bound", chest.getChestId()), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level.isClientSide) openClientScreen();
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            openClientScreen();
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        FoldworksTabletBinding.read(stack).ifPresent(bound -> {
            tooltip.add(Component.translatable("foldworks.tablet.bound_tooltip", bound.chestId()).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("foldworks.tablet.bound_location", bound.dimensionKey(), bound.pos().getX(), bound.pos().getY(), bound.pos().getZ()).withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void openClientScreen() {
        com.foldworks.client.ClientScreenHooks.openFoldworks();
    }
}
