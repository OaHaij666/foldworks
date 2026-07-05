package com.pockethomestead.api.suite;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SuiteToolRegistry {
    private static final List<SuiteToolAdapter> ADAPTERS = new ArrayList<>();
    private static final List<SuiteResourceProvider> RESOURCE_PROVIDERS = new ArrayList<>();

    private SuiteToolRegistry() {
    }

    public static void registerAdapter(SuiteToolAdapter adapter) {
        if (adapter != null && !ADAPTERS.contains(adapter)) ADAPTERS.add(adapter);
    }

    public static void registerResourceProvider(SuiteResourceProvider provider) {
        if (provider != null && !RESOURCE_PROVIDERS.contains(provider)) RESOURCE_PROVIDERS.add(provider);
    }

    public static List<SuiteToolAdapter> adapters() {
        return Collections.unmodifiableList(ADAPTERS);
    }

    public static boolean isSupportedTool(ItemStack tool) {
        if (tool == null || tool.isEmpty()) return false;
        for (SuiteToolAdapter adapter : ADAPTERS) {
            if (adapter.supportsTool(tool)) return true;
        }
        return false;
    }

    public static List<SuiteOperationTemplate> operationsFor(ServerLevel level, ItemStack desiredOutput) {
        List<SuiteOperationTemplate> result = new ArrayList<>();
        for (SuiteToolAdapter adapter : ADAPTERS) {
            result.addAll(adapter.operationsFor(level, desiredOutput));
        }
        return result;
    }

    public static int fuelTicks(ItemStack stack, net.minecraft.world.item.crafting.RecipeType<?> recipeType) {
        if (stack == null || stack.isEmpty()) return 0;
        int best = stack.getBurnTime(recipeType);
        for (SuiteResourceProvider provider : RESOURCE_PROVIDERS) {
            best = Math.max(best, provider.fuelTicks(stack, recipeType));
        }
        return Math.max(0, best);
    }
}
