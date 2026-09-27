package logisticspipes.crafting.pattern;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import logisticspipes.LogisticsPipes;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.SimpleStackInventory;

public class PatternHandler {

    private final SimpleStackInventory patternInventory;

    public PatternHandler(SimpleStackInventory patternInventory) {
        this.patternInventory = patternInventory;
    }

    public int size() {
        return patternInventory.getSizeInventory();
    }

    public ItemStack getConfiguredPatternStack(int slot) {
        if (slot < 0 || slot >= size()) {
            return null;
        }
        ItemStack stack = patternInventory.getStackInSlot(slot);
        if (stack == null || stack.getItem() != LogisticsPipes.LogisticsPattern
                || !ItemPattern.fromStack(stack).isConfigured()) {
            return null;
        }
        return stack;
    }

    public List<ItemStack> getConfiguredPatterns() {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            ItemStack pattern = getConfiguredPatternStack(i);
            if (pattern != null) {
                result.add(pattern);
            }
        }
        return result;
    }

    public int findPatternSlotForResult(ItemIdentifier item) {
        for (int slot = 0; slot < size(); slot++) {
            ItemStack pattern = getConfiguredPatternStack(slot);
            if (pattern == null) {
                continue;
            }
            if (resultAmount(slot, item) > 0) {
                return slot;
            }
        }
        return -1;
    }

    public int resultAmount(int patternSlot, ItemIdentifier item) {
        ItemStack pattern = getConfiguredPatternStack(patternSlot);
        if (pattern == null || item == null) {
            return 0;
        }
        int amount = 0;
        for (IPatternStack result : ItemPattern.fromStack(pattern).getOutputs()) {
            if (PatternStackHelper.matches(result, item)) {
                amount += result.getAmount();
            }
        }
        return amount;
    }

    public int fluidIngredientAmount(ItemStack pattern, FluidIdentifier fluid) {
        int amount = 0;
        if (pattern == null || fluid == null) {
            return amount;
        }
        for (IPatternStack ingredient : ItemPattern.fromStack(pattern).getInputs()) {
            if (PatternStackHelper.matches(ingredient, fluid)) {
                amount += ingredient.getAmount();
            }
        }
        return amount;
    }

    public List<IPatternStack> getAggregatedInputs(ItemStack pattern) {
        if (pattern == null) {
            return new ArrayList<>();
        }
        return ItemPattern.fromStack(pattern).getAggregatedInputs();
    }

}
