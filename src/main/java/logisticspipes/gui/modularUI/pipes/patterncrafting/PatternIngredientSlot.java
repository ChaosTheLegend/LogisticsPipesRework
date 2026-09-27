package logisticspipes.gui.modularUI.pipes.patterncrafting;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.widgets.slot.PhantomItemSlot;

import logisticspipes.crafting.patternStack.PatternFluidStack;
import logisticspipes.items.LogisticsFluidContainer;

/**
 * Phantom slot showing one entry of the edited pattern.
 * <p>
 * Fluid entries show their amount in the bottom right corner, and the crafting progress (buffered ingredients or
 * requested outputs) is drawn in blue in the top right corner.
 */
public class PatternIngredientSlot extends PhantomItemSlot {

    private static final int FLUID_AMOUNT_COLOR = 0xffffff;
    private static final int PROGRESS_COLOR = 0x55aaff;

    private Supplier<String> progressText = () -> null;
    private Consumer<RichTooltip> extraTooltip = tooltip -> {};

    public PatternIngredientSlot progressText(Supplier<String> progressText) {
        this.progressText = progressText;
        return this;
    }

    public PatternIngredientSlot extraTooltip(Consumer<RichTooltip> extraTooltip) {
        this.extraTooltip = extraTooltip;
        return this;
    }

    @Override
    protected void drawOverlay() {
        super.drawOverlay();
        ItemStack stack = getSlot().getStack();
        if (stack != null && stack.getItem() instanceof LogisticsFluidContainer) {
            PatternFluidStack fluid = PatternFluidStack.fromItemStack(stack);
            if (fluid != null) {
                PatternGuiDraw
                        .drawCornerText(PatternGuiDraw.formatFluidAmount(fluid.getAmount()), FLUID_AMOUNT_COLOR, false);
            }
        }
        PatternGuiDraw.drawCornerText(progressText.get(), PROGRESS_COLOR, true);
    }

    @Override
    public void buildTooltip(ItemStack stack, RichTooltip tooltip) {
        super.buildTooltip(stack, tooltip);
        if (stack != null) {
            extraTooltip.accept(tooltip);
        }
    }
}
