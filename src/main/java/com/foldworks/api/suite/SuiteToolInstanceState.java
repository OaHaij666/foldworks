package com.foldworks.api.suite;

import net.minecraft.world.item.ItemStack;

/**
 * Public snapshot shape for a suite tool instance.
 * Built-in tools currently expose fuel ticks internally; external adapters can
 * use this shape when presenting comparable state to Foldworks.
 */
public record SuiteToolInstanceState(ItemStack tool, int instanceIndex, int fuelTicks) {
}
