package logisticspipes.crafting.pattern;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.utils.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;

/** Shared main-output selection controls for the handheld and pipe pattern editors. */
@SideOnly(Side.CLIENT)
public final class PatternOutputSelection {

    private PatternOutputSelection() {
    }

    public static boolean isAltDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    public static int hoveredOutput(AbstractPattern pattern, PatternSlotLayout layout,
                                    int guiLeft, int guiTop, int mouseX, int mouseY) {
        for (int slot = 0; slot < pattern.getResultSlotCount(); slot++) {
            int x = guiLeft + layout.outputX(slot);
            int y = guiTop + layout.outputY(slot);
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18
                && pattern.getPatternStackInSlot(pattern.getResultSlotStart() + slot) != null) {
                return slot;
            }
        }
        return -1;
    }

    public static void drawMainOutput(AbstractPattern pattern, PatternSlotLayout layout, int guiLeft, int guiTop) {
        int slot = pattern.getMainOutputSlot();
        if (slot < 0) {
            return;
        }
        int x = guiLeft + layout.outputX(slot);
        int y = guiTop + layout.outputY(slot);
        int color = 0xff55cc66;
        Gui.drawRect(x, y, x + 18, y + 1, color);
        Gui.drawRect(x, y + 17, x + 18, y + 18, color);
        Gui.drawRect(x, y, x + 1, y + 18, color);
        Gui.drawRect(x + 17, y, x + 18, y + 18, color);
    }

    public static void drawTooltip(AbstractPattern pattern, PatternSlotLayout layout,
                                   int guiLeft, int guiTop, int mouseX, int mouseY) {
        int slot = hoveredOutput(pattern, layout, guiLeft, guiTop, mouseX, mouseY);
        if (slot < 0) {
            return;
        }
        // Leave the small satellite hotspot's tooltip visible.
        if (mouseX < guiLeft + layout.outputX(slot) + 7 && mouseY < guiTop + layout.outputY(slot) + 7) {
            return;
        }
        IPatternStack stack = pattern.getPatternStackInSlot(pattern.getResultSlotStart() + slot);
        GuiGraphics.drawToolTip(mouseX, mouseY, Arrays.asList(
            stack.makeDisplayItemStack().getDisplayName(),
            slot == pattern.getMainOutputSlot() ? "Main output" : "Byproduct",
            "Alt + left-click to select main output"), EnumChatFormatting.WHITE);
    }
}
