package com.pockethomestead.api.suite;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Third-party mods can register adapters to expose deterministic tool
 * operations to Pocket Homestead's suite order planner.
 */
public interface SuiteToolAdapter {
    boolean supportsTool(ItemStack tool);

    List<SuiteOperationTemplate> operationsFor(ServerLevel level, ItemStack desiredOutput);

    default String displayName() {
        return getClass().getSimpleName();
    }
}
