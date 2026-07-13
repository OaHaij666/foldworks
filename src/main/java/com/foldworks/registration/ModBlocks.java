package com.foldworks.registration;

import com.foldworks.Foldworks;
import com.foldworks.compat.create.CreateCompat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Foldworks.MODID);

    public static final DeferredBlock<Block> FOLDWORKS_CHEST = BLOCKS.register("foldworks_chest",
            () -> CreateCompat.createFoldworksChestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).noOcclusion()));
}
