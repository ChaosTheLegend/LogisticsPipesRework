package logisticspipes.crafting.patternStack;

import logisticspipes.utils.item.ItemIdentifierStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public class PatternItemStack implements IPatternStack {

    private final ItemIdentifierStack stack;

    public PatternItemStack(ItemIdentifierStack stack) {
        this.stack = stack;
    }

    public static PatternItemStack fromItemStack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return null;
        }
        return new PatternItemStack(ItemIdentifierStack.getFromStack(stack));
    }

    public static PatternItemStack readFromNBT(NBTTagCompound tag) {
        ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
        if (stack != null) stack.stackSize = tag.getInteger("Count");
        return fromItemStack(stack);
    }

    public ItemIdentifierStack getItemIdentifierStack() {
        return stack;
    }

    @Override
    public int getAmount() {
        return stack.getStackSize();
    }

    @Override
    public void addAmount(int amount) {
        stack.setStackSize(stack.getStackSize() + amount);
    }

    @Override
    public boolean canMerge(IPatternStack other) {
        return other instanceof PatternItemStack
            && stack.getItem().equals(((PatternItemStack) other).stack.getItem());
    }

    @Override
    public PatternItemStack copy() {
        return new PatternItemStack(stack.clone());
    }

    @Override
    public ItemStack makePatternStack() {
        return stack.makeNormalStack();
    }

    @Override
    public ItemStack makeDisplayItemStack() {
        return makePatternStack();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        makePatternStack().writeToNBT(tag);
        tag.setInteger("Count", stack.getStackSize());
        tag.setString(TYPE_TAG, TYPE_SOLID);
    }

    @Override
    public String toString() {
        return stack.toString();
    }

    @Override
    public Item getItem() {
        return stack.getItem().item;
    }
}
