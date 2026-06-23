package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.compat.create.CreateCompat;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, PocketHomestead.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlockEntity>> HOMESTEAD_CHEST = BLOCK_ENTITIES.register(
            "homestead_chest", () -> BlockEntityType.Builder.of(CreateCompat::createHomesteadChestBlockEntity, ModBlocks.HOMESTEAD_CHEST.get()).build(null));
}
