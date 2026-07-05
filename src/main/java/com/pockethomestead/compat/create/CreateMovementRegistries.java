package com.pockethomestead.compat.create;

import com.pockethomestead.PocketHomestead;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import com.simibubi.create.api.registry.CreateRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CreateMovementRegistries {
    private static final DeferredRegister<MountedItemStorageType<?>> ITEM_STORAGE_TYPES =
            DeferredRegister.create(CreateRegistries.MOUNTED_ITEM_STORAGE_TYPE, PocketHomestead.MODID);
    private static final DeferredRegister<MountedFluidStorageType<?>> FLUID_STORAGE_TYPES =
            DeferredRegister.create(CreateRegistries.MOUNTED_FLUID_STORAGE_TYPE, PocketHomestead.MODID);

    static final DeferredHolder<MountedItemStorageType<?>, HomesteadMountedItemStorageType> HOMESTEAD_ITEM_STORAGE_TYPE =
            ITEM_STORAGE_TYPES.register("homestead_chest", HomesteadMountedItemStorageType::new);
    static final DeferredHolder<MountedFluidStorageType<?>, HomesteadMountedFluidStorageType> HOMESTEAD_FLUID_STORAGE_TYPE =
            FLUID_STORAGE_TYPES.register("homestead_chest", HomesteadMountedFluidStorageType::new);

    private static boolean registered;

    private CreateMovementRegistries() {
    }

    public static void register(IEventBus modEventBus) {
        if (registered) return;
        ITEM_STORAGE_TYPES.register(modEventBus);
        FLUID_STORAGE_TYPES.register(modEventBus);
        registered = true;
    }

    public static HomesteadMountedItemStorageType itemStorageType() {
        return HOMESTEAD_ITEM_STORAGE_TYPE.get();
    }

    public static HomesteadMountedFluidStorageType fluidStorageType() {
        return HOMESTEAD_FLUID_STORAGE_TYPE.get();
    }
}
