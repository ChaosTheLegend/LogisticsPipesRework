package logisticspipes.nei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.StackInfo;
import cpw.mods.fml.common.Loader;
import gregtech.nei.GTNEIDefaultHandler.FixedPositionedStack;
import logisticspipes.crafting.pattern.DefaultPattern;
import logisticspipes.crafting.pattern.ProcessingPattern;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternFluidStack;
import logisticspipes.crafting.patternStack.PatternItemStack;
import lombok.Getter;

/** Converts NEI display stacks into recipe resources without modifying NEI's cached recipes. */
@Getter
class NEIPatternRecipe {

    private final List<IPatternStack> inputs = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();
    private final List<IPatternStack> outputs = new ArrayList<>();
    private final boolean processing;
    private boolean valid = true;

    private NEIPatternRecipe(boolean processing) {
        this.processing = processing;
    }

    static NEIPatternRecipe read(IRecipeHandler recipe, int recipeIndex) {
        String identifier = recipe.getOverlayIdentifier();
        NEIPatternRecipe result = new NEIPatternRecipe(
            !"crafting".equals(identifier) && !"crafting2x2".equals(identifier));
        for (PositionedStack positioned : recipe.getIngredientStacks(recipeIndex)) {
            IPatternStack input = convert(positioned, result.processing);
            if (input == null) continue;
            if (result.processing) {
                // Keep distinct machine slots: some machines care about the input arrangement.
                result.indices.add(result.inputs.size());
            } else {
                int x = positioned.relx - 25;
                int y = positioned.rely - 6;
                if (x < 0 || y < 0 || x % 18 != 0 || y % 18 != 0 || x / 18 >= 3 || y / 18 >= 3) {
                    result.valid = false;
                    continue;
                }
                int slot = x / 18 + y / 18 * 3;
                if (result.indices.contains(slot)) result.valid = false;
                result.indices.add(slot);
            }
            result.inputs.add(input);
        }
        result.addOutput(recipe.getResultStack(recipeIndex));
        // Furnace "other stacks" are fuel. For processing handlers they can contain actual byproducts.
        if (result.processing && !"smelting".equals(identifier)) {
            List<PositionedStack> otherStacks = recipe.getOtherStacks(recipeIndex);
            if (otherStacks != null) {
                for (PositionedStack other : otherStacks) result.addOutput(other);
            }
        }
        int inputLimit = result.processing ? ProcessingPattern.INGREDIENT_SLOTS : DefaultPattern.INGREDIENT_SLOTS;
        int outputLimit = result.processing ? ProcessingPattern.RESULT_SLOTS : DefaultPattern.RESULT_SLOTS;
        result.valid &= !result.outputs.isEmpty() && result.inputs.size() <= inputLimit
            && result.outputs.size() <= outputLimit;
        return result;
    }

    private void addOutput(PositionedStack positioned) {
        IPatternStack output = convert(positioned, processing);
        if (output == null) return;
        for (IPatternStack existing : outputs) {
            if (existing.canMerge(output)) {
                existing.addAmount(output.getAmount());
                return;
            }
        }
        outputs.add(output);
    }

    static IPatternStack convert(PositionedStack positioned, boolean processing) {
        if (positioned == null || positioned.item == null || positioned.item.getItem() == null) return null;
        ItemStack item = positioned.item.copy();
        if (Loader.isModLoaded("gregtech")) {
            // GT can hide the real item count in its display permutations, including zero-sized catalysts.
            item.stackSize = GregTechStacks.getAmount(positioned, item.stackSize);
        }
        if (item.stackSize <= 0) return null;
        // Filled buckets/cells are real item ingredients. Only display tokens represent uncontained fluids.
        if (processing && !StackInfo.isFluidContainer(item)) {
            FluidStack fluid = StackInfo.getFluid(item);
            if (fluid != null) return PatternFluidStack.fromFluidStack(fluid.copy());
        }
        return PatternItemStack.fromItemStack(item);
    }

    private static class GregTechStacks {

        static int getAmount(PositionedStack stack, int fallback) {
            return stack instanceof FixedPositionedStack gtStack ? gtStack.realStackSize : fallback;
        }
    }
}
