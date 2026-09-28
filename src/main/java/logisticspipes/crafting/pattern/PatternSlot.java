package logisticspipes.crafting.pattern;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import codechicken.nei.recipe.StackInfo;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.items.LogisticsFluidContainer;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.utils.gui.DummySlot;
import logisticspipes.utils.item.ItemIdentifierStack;

/** Exposes searchable fluid display items to the client while retaining LP stacks for storage and editing. */
class PatternSlot extends DummySlot {

    private final boolean clientSide;
    private boolean editing;

    PatternSlot(IInventory inventory, int slot, int x, int y, boolean clientSide) {
        super(inventory, slot, x, y);
        this.clientSide = clientSide;
    }

    @Override
    public ItemStack getStack() {
        ItemStack stack = super.getStack();
        if (clientSide && !editing && stack != null && stack.getItem() instanceof LogisticsFluidContainer) {
            return makeFluidDisplayStack(stack);
        }
        return stack;
    }

    @Override
    public void setRedirectCall(boolean redirectCall) {
        super.setRedirectCall(redirectCall);
        // DummyContainer brackets its slot edits with this flag. Keep display tokens out of those writes.
        editing = redirectCall;
    }

    @SideOnly(Side.CLIENT)
    private ItemStack makeFluidDisplayStack(ItemStack storedStack) {
        FluidStack fluid = SimpleServiceLocator.logisticsFluidManager
            .getFluidFromContainer(ItemIdentifierStack.getFromStack(storedStack));
        if (fluid == null) return storedStack;

        // NEI's fluid serializer creates the same GT display item used in recipe and usage searches.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("gtFluidName", fluid.getFluid().getName());
        tag.setInteger("Count", fluid.amount);
        ItemStack displayStack = StackInfo.loadFromNBT(tag);
        // NEI installations without a fluid display provider can still edit and display the LP representation.
        return displayStack == null ? storedStack : displayStack;
    }
}
