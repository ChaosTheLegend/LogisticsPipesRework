package logisticspipes.crafting;

import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.utils.gui.DummyContainer;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.LogisticsBaseGuiScreen;

@SideOnly(Side.CLIENT)
public class PatternCraftingTableGui extends LogisticsBaseGuiScreen {

    private final PatternLogisticsCraftingTableTileEntity tile;

    public PatternCraftingTableGui(EntityPlayer player, PatternLogisticsCraftingTableTileEntity tile) {
        super(176, 184, 0, 0);
        this.tile = tile;
        DummyContainer dummy = new DummyContainer(player.inventory, tile.getInputInventory());
        PatternCraftingTableGuiProvider.addInputSlots(dummy, tile);
        PatternCraftingTableGuiProvider.addOutputSlots(dummy, tile);
        PatternCraftingTableGuiProvider.addUpgradeSlots(dummy, tile);
        dummy.addNormalSlotsForPlayerInventory(8, 102);
        inventorySlots = dummy;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        GuiGraphics.drawPlayerInventoryBackground(mc, guiLeft + 8, guiTop + 102);
        drawInputSlots();
        drawOutputSlots();
        drawUpgradeSlots();
        drawProgressBar();
        mc.fontRenderer.drawString("Pattern Crafting Table", guiLeft + 8, guiTop + 6, 0x404040);
        mc.fontRenderer.drawString("Speed", guiLeft + 116, guiTop + 59, 0x404040);
    }

    private void drawInputSlots() {
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                GuiGraphics.drawSlotBackground(mc, guiLeft + 25 + x * 18, guiTop + 17 + y * 18);
            }
        }
    }

    private void drawOutputSlots() {
        for (int i = 0; i < 3; i++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + 115 + i * 18, guiTop + 35);
        }
    }

    private void drawUpgradeSlots() {
        for (int i = 0; i < 3; i++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + 115 + i * 18, guiTop + 68);
        }
    }

    private void drawProgressBar() {
        int left = guiLeft + 86;
        int top = guiTop + 40;
        Gui.drawRect(left, top, left + 22, top + 8, 0xff4f4f4f);
        Gui.drawRect(left + 1, top + 1, left + 21, top + 7, 0xff202020);
        int progress = tile.getProgressScaled(20);
        if (progress > 0) {
            Gui.drawRect(left + 1, top + 1, left + 1 + progress, top + 7, 0xff5aa36f);
        }
    }
}
