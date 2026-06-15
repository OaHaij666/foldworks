package com.pockethomestead.item;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 尘歌玉盘：合并了创建与管理功能，右键打开口袋家园主界面。 */
public class HomesteadTabletItem extends Item {
    public HomesteadTabletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            openClientScreen();
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @OnlyIn(Dist.CLIENT)
    private static void openClientScreen() {
        com.pockethomestead.client.ClientScreenHooks.openHomestead();
    }
}
