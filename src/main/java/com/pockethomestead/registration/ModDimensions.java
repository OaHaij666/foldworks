package com.pockethomestead.registration;

import com.mojang.serialization.MapCodec;
import com.pockethomestead.PocketHomestead;
import com.pockethomestead.dimension.PocketChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModDimensions {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, PocketHomestead.MODID);

    static {
        CHUNK_GENERATORS.register("pocket", () -> PocketChunkGenerator.CODEC);
    }
}
