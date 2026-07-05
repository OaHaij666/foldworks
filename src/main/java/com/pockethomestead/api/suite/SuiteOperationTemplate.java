package com.pockethomestead.api.suite;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

/**
 * A deterministic operation visible to the suite planner.
 */
public record SuiteOperationTemplate(
        ResourceLocation id,
        ItemStack tool,
        List<Ingredient> inputs,
        ItemStack output,
        int timeTicks,
        int fuelTicks,
        RecipeType<?> fuelRecipeType,
        RecipeHolder<?> recipe
) {
}
