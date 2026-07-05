package com.pockethomestead.suite;

import com.pockethomestead.api.suite.SuiteOperationTemplate;
import com.pockethomestead.api.suite.SuiteToolAdapter;
import com.pockethomestead.api.suite.SuiteToolRegistry;
import com.pockethomestead.config.ModConfig;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class VanillaSuiteAdapters {
    private static boolean registered;

    private VanillaSuiteAdapters() {
    }

    public static void registerBuiltIns() {
        if (registered) return;
        registered = true;
        SuiteToolRegistry.registerAdapter(new CraftingAdapter());
        SuiteToolRegistry.registerAdapter(new CookingAdapter(Items.FURNACE.getDefaultInstance(), RecipeType.SMELTING));
        SuiteToolRegistry.registerAdapter(new CookingAdapter(Items.BLAST_FURNACE.getDefaultInstance(), RecipeType.BLASTING));
        SuiteToolRegistry.registerAdapter(new CookingAdapter(Items.SMOKER.getDefaultInstance(), RecipeType.SMOKING));
        SuiteToolRegistry.registerAdapter(new StonecuttingAdapter());
        SuiteToolRegistry.registerAdapter(new SmithingAdapter());
    }

    private static boolean sameOutput(ItemStack produced, ItemStack desired) {
        return produced != null && desired != null && !produced.isEmpty() && !desired.isEmpty()
                && ItemStack.isSameItemSameComponents(produced, desired);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("pockethomestead", "suite/" + path);
    }

    private static final class CraftingAdapter implements SuiteToolAdapter {
        @Override
        public boolean supportsTool(ItemStack tool) {
            return tool.is(Items.CRAFTING_TABLE);
        }

        @Override
        public List<SuiteOperationTemplate> operationsFor(ServerLevel level, ItemStack desiredOutput) {
            List<SuiteOperationTemplate> result = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe recipe = holder.value();
                if (recipe.isSpecial() || recipe.isIncomplete()) continue;
                ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
                if (!sameOutput(output, desiredOutput)) continue;
                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients.isEmpty()) continue;
                result.add(new SuiteOperationTemplate(id("crafting/" + holder.id().toString().replace(':', '/')),
                        new ItemStack(Items.CRAFTING_TABLE), List.copyOf(ingredients), output,
                        Math.max(1, ModConfig.SUITE_INSTANT_OPERATION_TICKS.get()), 0, null, holder));
            }
            return result;
        }
    }

    private static final class CookingAdapter implements SuiteToolAdapter {
        private final ItemStack tool;
        private final RecipeType<? extends AbstractCookingRecipe> type;

        private CookingAdapter(ItemStack tool, RecipeType<? extends AbstractCookingRecipe> type) {
            this.tool = tool;
            this.type = type;
        }

        @Override
        public boolean supportsTool(ItemStack tool) {
            return ItemStack.isSameItemSameComponents(this.tool, tool);
        }

        @Override
        public List<SuiteOperationTemplate> operationsFor(ServerLevel level, ItemStack desiredOutput) {
            List<SuiteOperationTemplate> result = new ArrayList<>();
            for (RecipeHolder<? extends AbstractCookingRecipe> holder : level.getRecipeManager().getAllRecipesFor(type)) {
                AbstractCookingRecipe recipe = holder.value();
                ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
                if (!sameOutput(output, desiredOutput)) continue;
                result.add(new SuiteOperationTemplate(id("cooking/" + holder.id().toString().replace(':', '/')),
                        tool.copyWithCount(1), List.copyOf(recipe.getIngredients()), output,
                        Math.max(1, recipe.getCookingTime()), Math.max(1, recipe.getCookingTime()), type, holder));
            }
            return result;
        }
    }

    private static final class StonecuttingAdapter implements SuiteToolAdapter {
        @Override
        public boolean supportsTool(ItemStack tool) {
            return tool.is(Items.STONECUTTER);
        }

        @Override
        public List<SuiteOperationTemplate> operationsFor(ServerLevel level, ItemStack desiredOutput) {
            List<SuiteOperationTemplate> result = new ArrayList<>();
            for (RecipeHolder<StonecutterRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING)) {
                Recipe<?> recipe = holder.value();
                ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
                if (!sameOutput(output, desiredOutput)) continue;
                result.add(new SuiteOperationTemplate(id("stonecutting/" + holder.id().toString().replace(':', '/')),
                        new ItemStack(Items.STONECUTTER), List.copyOf(recipe.getIngredients()), output,
                        Math.max(1, ModConfig.SUITE_INSTANT_OPERATION_TICKS.get()), 0, null, holder));
            }
            return result;
        }
    }

    private static final class SmithingAdapter implements SuiteToolAdapter {
        @Override
        public boolean supportsTool(ItemStack tool) {
            return tool.is(Items.SMITHING_TABLE);
        }

        @Override
        public List<SuiteOperationTemplate> operationsFor(ServerLevel level, ItemStack desiredOutput) {
            List<SuiteOperationTemplate> result = new ArrayList<>();
            for (RecipeHolder<SmithingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING)) {
                SmithingRecipe recipe = holder.value();
                if (!(recipe instanceof SmithingTransformRecipe)) continue;
                ItemStack output = recipe.getResultItem(level.registryAccess()).copy();
                if (output.isEmpty() || !sameOutput(output, desiredOutput)) continue;
                List<Ingredient> ingredients = smithingTransformIngredients(recipe);
                if (ingredients.size() != 3) continue;
                result.add(new SuiteOperationTemplate(id("smithing/" + holder.id().toString().replace(':', '/')),
                        new ItemStack(Items.SMITHING_TABLE), ingredients, output,
                        Math.max(1, ModConfig.SUITE_INSTANT_OPERATION_TICKS.get()), 0, null, holder));
            }
            return result;
        }
    }

    private static List<Ingredient> smithingTransformIngredients(SmithingRecipe recipe) {
        try {
            Field template = recipe.getClass().getDeclaredField("template");
            Field base = recipe.getClass().getDeclaredField("base");
            Field addition = recipe.getClass().getDeclaredField("addition");
            template.setAccessible(true);
            base.setAccessible(true);
            addition.setAccessible(true);
            return List.of((Ingredient) template.get(recipe), (Ingredient) base.get(recipe), (Ingredient) addition.get(recipe));
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return List.of();
        }
    }
}
