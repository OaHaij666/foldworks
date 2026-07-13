package com.foldworks.registration;

import com.foldworks.Foldworks;
import com.foldworks.compat.create.CreateCompat;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, Foldworks.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlockEntity>> FOLDWORKS_CHEST = BLOCK_ENTITIES.register(
            "foldworks_chest", () -> BlockEntityType.Builder.of(CreateCompat::createFoldworksChestBlockEntity, ModBlocks.FOLDWORKS_CHEST.get()).build(null));
}
