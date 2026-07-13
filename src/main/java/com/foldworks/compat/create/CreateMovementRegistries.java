package com.foldworks.compat.create;

import com.foldworks.Foldworks;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import com.simibubi.create.api.registry.CreateRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CreateMovementRegistries {
    private static final DeferredRegister<MountedItemStorageType<?>> ITEM_STORAGE_TYPES =
            DeferredRegister.create(CreateRegistries.MOUNTED_ITEM_STORAGE_TYPE, Foldworks.MODID);
    private static final DeferredRegister<MountedFluidStorageType<?>> FLUID_STORAGE_TYPES =
            DeferredRegister.create(CreateRegistries.MOUNTED_FLUID_STORAGE_TYPE, Foldworks.MODID);

    static final DeferredHolder<MountedItemStorageType<?>, FoldworksMountedItemStorageType> FOLDWORKS_ITEM_STORAGE_TYPE =
            ITEM_STORAGE_TYPES.register("foldworks_chest", FoldworksMountedItemStorageType::new);
    static final DeferredHolder<MountedFluidStorageType<?>, FoldworksMountedFluidStorageType> FOLDWORKS_FLUID_STORAGE_TYPE =
            FLUID_STORAGE_TYPES.register("foldworks_chest", FoldworksMountedFluidStorageType::new);

    private static boolean registered;

    private CreateMovementRegistries() {
    }

    public static void register(IEventBus modEventBus) {
        if (registered) return;
        ITEM_STORAGE_TYPES.register(modEventBus);
        FLUID_STORAGE_TYPES.register(modEventBus);
        registered = true;
    }

    public static FoldworksMountedItemStorageType itemStorageType() {
        return FOLDWORKS_ITEM_STORAGE_TYPE.get();
    }

    public static FoldworksMountedFluidStorageType fluidStorageType() {
        return FOLDWORKS_FLUID_STORAGE_TYPE.get();
    }
}
