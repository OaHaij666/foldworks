package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.blockentity.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, PocketHomestead.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SupplyChestBlockEntity>> SUPPLY_CHEST = BLOCK_ENTITIES.register(
            "supply_chest", () -> BlockEntityType.Builder.of(SupplyChestBlockEntity::new, ModBlocks.SUPPLY_CHEST.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PickupChestBlockEntity>> PICKUP_CHEST = BLOCK_ENTITIES.register(
            "pickup_chest", () -> BlockEntityType.Builder.of(PickupChestBlockEntity::new, ModBlocks.PICKUP_CHEST.get()).build(null));
}
