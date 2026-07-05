package com.pockethomestead.api.suite;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Declares an item-to-resource conversion for the suite order system.
 * The first built-in resource is fuel ticks.
 */
public interface SuiteResourceProvider {
    int fuelTicks(ItemStack stack, RecipeType<?> recipeType);

    default ItemStack craftingRemainder(ItemStack stack) {
        return stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
    }
}
