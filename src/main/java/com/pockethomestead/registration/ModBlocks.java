package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.compat.create.CreateCompat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(PocketHomestead.MODID);

    public static final DeferredBlock<Block> HOMESTEAD_CHEST = BLOCKS.register("homestead_chest",
            () -> CreateCompat.createHomesteadChestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).noOcclusion()));
}
