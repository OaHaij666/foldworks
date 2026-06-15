package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.block.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(PocketHomestead.MODID);

    // 供货箱 - 继承木桶式储物方块
    public static final DeferredBlock<SupplyChestBlock> SUPPLY_CHEST = BLOCKS.register("supply_chest",
            () -> new SupplyChestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).noOcclusion()));

    // 取货箱 - 继承木桶式储物方块
    public static final DeferredBlock<PickupChestBlock> PICKUP_CHEST = BLOCKS.register("pickup_chest",
            () -> new PickupChestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).noOcclusion()));
}
