package com.foldworks.registration;

import com.mojang.serialization.MapCodec;
import com.foldworks.Foldworks;
import com.foldworks.dimension.ProductionSpaceChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModDimensions {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, Foldworks.MODID);
    static {
        CHUNK_GENERATORS.register("production_space", () -> ProductionSpaceChunkGenerator.CODEC);
    }
}
