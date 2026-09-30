package logisticspipes.crafting.pattern;

import logisticspipes.crafting.patternStack.PatternFluidStack;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.utils.gui.DummyContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

public class PatternContainer extends DummyContainer {

    private static final int HIDDEN_SLOT_X = -1000;
    private static final int HIDDEN_SLOT_Y = -1000;

    private final List<Slot> patternSlots = new ArrayList<>();
    private final boolean clientSide;
    private int inputLeft;
    private int inputTop;
    private int outputLeft;
    private int outputTop;

    public PatternContainer(IInventory playerInventory, IInventory dummyInventory) {
        super(playerInventory, dummyInventory);
        clientSide = playerInventory instanceof InventoryPlayer inventory && inventory.player.worldObj.isRemote;
    }

    /**
     * Adds the full editable pattern slot range once and lays out only the slots supported by the current pattern type.
     *
     * @param pattern    pattern whose crafting or processing layout should be displayed
     * @param inputLeft  left position of the first visible input slot
     * @param inputTop   top position of the first visible input slot
     * @param outputLeft left position of the first visible output slot
     * @param outputTop  top position of the first visible output slot
     */
    public void addPatternSlots(
        AbstractPattern pattern, int inputLeft, int inputTop, int outputLeft, int outputTop) {
        if (patternSlots.isEmpty()) {
            for (int slot = 0; slot < ItemPattern.MAX_ITEM_SLOT_COUNT; slot++) {
                patternSlots.add(addSlotToContainer(
                    new PatternSlot(_dummyInventory, slot, HIDDEN_SLOT_X, HIDDEN_SLOT_Y, clientSide)));
            }
        }
        updatePatternSlotLayout(pattern, inputLeft, inputTop, outputLeft, outputTop);
    }

    /**
     * Updates the slot anchor positions and then applies the current pattern layout.
     *
     * @param pattern    pattern whose active input and output slots should be visible
     * @param inputLeft  left position of the first visible input slot
     * @param inputTop   top position of the first visible input slot
     * @param outputLeft left position of the first visible output slot
     * @param outputTop  top position of the first visible output slot
     */
    public void updatePatternSlotLayout(
        AbstractPattern pattern, int inputLeft, int inputTop, int outputLeft, int outputTop) {
        this.inputLeft = inputLeft;
        this.inputTop = inputTop;
        this.outputLeft = outputLeft;
        this.outputTop = outputTop;
        updatePatternSlotLayout(pattern);
    }

    /**
     * Repositions pattern slots after the selected pattern or the pattern type changed.
     *
     * @param pattern pattern whose active input and output slots should be visible
     */
    public void updatePatternSlotLayout(AbstractPattern pattern) {
        if (patternSlots.isEmpty()) {
            return;
        }
        if (pattern == null) {
            pattern = ItemPattern.fromStack(null);
        }
        PatternSlotLayout layout = new PatternSlotLayout(pattern, inputLeft, inputTop, outputLeft, outputTop);
        for (int slot = 0; slot < patternSlots.size(); slot++) {
            Slot containerSlot = patternSlots.get(slot);
            if (slot < pattern.getIngredientSlotCount()) {
                moveSlot(containerSlot, layout.inputX(slot), layout.inputY(slot));
            } else if (slot >= pattern.getResultSlotStart() && slot < pattern.getItemSlotCount()) {
                int outputSlot = slot - pattern.getResultSlotStart();
                moveSlot(containerSlot, layout.outputX(outputSlot), layout.outputY(outputSlot));
            } else {
                moveSlot(containerSlot, HIDDEN_SLOT_X, HIDDEN_SLOT_Y);
            }
        }
    }

    /**
     * Selects the pipe pattern slot edited by this container and refreshes the visible pattern slot positions.
     *
     * @param patternSlot pipe pattern inventory slot to edit
     */
    public void setSelectedPatternSlot(int patternSlot) {
        if (_dummyInventory instanceof PipePatternInventory patternInventory) {
            patternInventory.setPatternSlot(patternSlot);
            updatePatternSlotLayout(ItemPattern.fromStack(patternInventory.getPatternStack()));
            detectAndSendChanges();
        }
    }

    @Override
    public void handleDummyClick(Slot slot, int slotId, ItemStack currentlyEquippedStack, int mouseButton, int isShift,
            EntityPlayer entityplayer) {
        if (isPatternSlot(slot)) {
            FluidStack fluid = getFluidFromItem(currentlyEquippedStack);
            if (fluid != null && fluid.amount > 0) {
                // Handle fluid insertion
                switch (mouseButton) {
                    case 0: // Left click
                        slot.putStack(
                                SimpleServiceLocator.logisticsFluidManager.getFluidContainer(fluid).makeNormalStack());
                        syncSlot(slot, slotId, entityplayer);
                        return;
                    case 1: // Right click
                        // Maybe split or something, but for now, same as left
                        slot.putStack(
                                SimpleServiceLocator.logisticsFluidManager.getFluidContainer(fluid).makeNormalStack());
                        syncSlot(slot, slotId, entityplayer);
                        return;
                }
            }
        }
        super.handleDummyClick(slot, slotId, currentlyEquippedStack, mouseButton, isShift, entityplayer);
        syncSlot(slot, slotId, entityplayer);
    }

    private boolean isPatternSlot(Slot slot) {
        return slot != null && slot.inventory == _dummyInventory
                && slot.getSlotIndex() >= 0
                && slot.getSlotIndex() < ItemPattern.MAX_ITEM_SLOT_COUNT;
    }

    private FluidStack getFluidFromItem(ItemStack stack) {
        if (stack == null) return null;

        FluidStack fluid = null;

        fluid = PatternFluidStack.getFluidStack(stack, fluid);

        if (fluid != null) {
            FluidStack fluid0 = fluid.copy();
            fluid0.amount *= stack.stackSize;
            return fluid0;
        }
        return null;
    }

    private void syncSlot(Slot slot, int slotId, EntityPlayer entityplayer) {
        if (entityplayer instanceof EntityPlayerMP && MainProxy.isServer(entityplayer.worldObj)) {
            ((EntityPlayerMP) entityplayer).sendSlotContents(this, slotId, slot.getStack());
            detectAndSendChanges();
        }
    }

    public boolean isEditingPattern(ItemStack stack) {
        if (stack == null) return false;
        return (_dummyInventory instanceof PatternInventory inventory && inventory.getPatternStack() == stack)
            || (_dummyInventory instanceof PipePatternInventory pipeInventory && pipeInventory.getPatternStack() == stack);
    }

    /**
     * Reloads the dummy inventory contents from the supplied pattern after an external recipe import.
     *
     * @param pattern pattern data that should be mirrored into this container
     */
    public void reloadFromPattern(AbstractPattern pattern) {
        if (pattern == null) {
            pattern = ItemPattern.fromStack(null);
        }
        updatePatternSlotLayout(pattern);
        if (isEditingPattern(pattern.getPatternStack())) {
            // Both pattern inventories read directly from the updated NBT. Re-inserting display stacks would
            // convert item ingredients such as filled buckets into fluids.
            detectAndSendChanges();
            return;
        }
        boolean change = false;

        for (int i = 0; i < _dummyInventory.getSizeInventory(); ++i) {
            var oldSlot = _dummyInventory.getStackInSlot(i);
            var newSlot = pattern.getStackInSlot(i);

            if (newSlot != oldSlot) {
                _dummyInventory.setInventorySlotContents(i, newSlot);
                change = true;
            }
        }

        if (change) detectAndSendChanges();

    }

    private void moveSlot(Slot slot, int x, int y) {
        slot.xDisplayPosition = x;
        slot.yDisplayPosition = y;
    }
}
