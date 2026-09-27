package logisticspipes.gui.modularUI.pipes.patterncrafting;

import net.minecraft.item.ItemStack;

import logisticspipes.LogisticsPipes;
import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.ItemPattern;
import logisticspipes.crafting.pattern.PipePatternInventory;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;

/**
 * Per-GUI editing state of a pattern crafting pipe: which of the nine pattern slots is being edited.
 * <p>
 * One instance exists on each side; the selection is kept in sync by {@link PatternCraftingSyncHandler}.
 */
public class PatternEditorState {

    public static final int PATTERN_SLOTS = 9;

    private final PipeItemsPatternCraftingLogistics pipe;
    private final PipePatternInventory editedInventory;
    private int selectedSlot;

    public PatternEditorState(PipeItemsPatternCraftingLogistics pipe, int selectedSlot) {
        this.pipe = pipe;
        this.selectedSlot = clamp(selectedSlot);
        this.editedInventory = new PipePatternInventory(pipe, this.selectedSlot);
    }

    public PipeItemsPatternCraftingLogistics getPipe() {
        return pipe;
    }

    /**
     * Inventory view of the selected pattern's ingredient and result slots.
     */
    public PipePatternInventory getEditedInventory() {
        return editedInventory;
    }

    public int getSelectedSlot() {
        return selectedSlot;
    }

    public void select(int slot) {
        selectedSlot = clamp(slot);
        editedInventory.setPatternSlot(selectedSlot);
    }

    public ItemStack getPatternStack() {
        return getPatternStack(selectedSlot);
    }

    public ItemStack getPatternStack(int slot) {
        return pipe.getPatternModule().getPatternItemStack(slot);
    }

    public boolean hasPattern() {
        return isPattern(getPatternStack());
    }

    public AbstractPattern getPattern() {
        return ItemPattern.fromStack(getPatternStack());
    }

    public boolean isProcessing() {
        return ItemPattern.isProcessingPattern(getPatternStack());
    }

    /**
     * Picks the slot the GUI should open on: the preferred slot if it holds a pattern, otherwise the first slot that
     * does.
     */
    public static int findInitialSlot(PipeItemsPatternCraftingLogistics pipe, int preferredSlot) {
        if (isPattern(pipe.getPatternModule().getPatternItemStack(clamp(preferredSlot)))) {
            return clamp(preferredSlot);
        }
        for (int slot = 0; slot < PATTERN_SLOTS; slot++) {
            if (isPattern(pipe.getPatternModule().getPatternItemStack(slot))) {
                return slot;
            }
        }
        return 0;
    }

    public static boolean isPattern(ItemStack stack) {
        return stack != null && stack.getItem() == LogisticsPipes.LogisticsPattern;
    }

    private static int clamp(int slot) {
        return Math.max(0, Math.min(PATTERN_SLOTS - 1, slot));
    }
}
