package com.pockethomestead.item;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.permission.AccessControl;
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

/** 尘歌玉盘：合并了创建与管理功能，右键打开口袋家园主界面。 */
public class HomesteadTabletItem extends Item {

    public HomesteadTabletItem(Properties properties) {
        super(properties);
    }

    public static ItemAttributeModifiers createTabletAttributes(double attackDamage, double attackSpeed) {
        var builder = ItemAttributeModifiers.builder();
        builder.add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("pockethomestead", "tablet_attack_damage"),
                        attackDamage, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);
        builder.add(Attributes.ATTACK_SPEED,
                new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("pockethomestead", "tablet_attack_speed"),
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
            BaseChestBlockEntity chest = HomesteadChestAccess.resolve(blockEntity);
            if (chest == null) return InteractionResult.PASS;

            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                if (!AccessControl.canUseChest(serverPlayer, chest)) {
                    AccessControl.deny(serverPlayer);
                    return InteractionResult.SUCCESS;
                }
                HomesteadTabletBinding.bind(context.getItemInHand(), chest);
                player.displayClientMessage(Component.translatable("pockethomestead.tablet.bound", chest.getChestId()), true);
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
        HomesteadTabletBinding.read(stack).ifPresent(bound -> {
            tooltip.add(Component.translatable("pockethomestead.tablet.bound_tooltip", bound.chestId()).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("pockethomestead.tablet.bound_location", bound.dimensionKey(), bound.pos().getX(), bound.pos().getY(), bound.pos().getZ()).withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void openClientScreen() {
        com.pockethomestead.client.ClientScreenHooks.openHomestead();
    }
}
