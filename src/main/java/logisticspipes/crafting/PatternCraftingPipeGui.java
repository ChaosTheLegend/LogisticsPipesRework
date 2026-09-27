package logisticspipes.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.ItemPattern;
import logisticspipes.crafting.pattern.PatternContainer;
import logisticspipes.crafting.pattern.PatternGuiProvider;
import logisticspipes.crafting.pattern.PatternSlotLayout;
import logisticspipes.crafting.pattern.PipePatternInventory;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.crafting.PatternPipeSatelliteAssignmentPacket;
import logisticspipes.network.packets.gui.PatternCraftingPipeCancel;
import logisticspipes.network.packets.gui.PatternCraftingPipeMode;
import logisticspipes.network.packets.gui.PatternCraftingPipeReturnInputs;
import logisticspipes.network.packets.gui.PatternPipeSelectPacket;
import logisticspipes.network.packets.gui.PatternPipeSlotActionPacket;
import logisticspipes.network.packets.gui.PatternSlotActionPacket;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;
import logisticspipes.proxy.MainProxy;
import logisticspipes.renderer.PatternItemRenderer;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.LogisticsBaseGuiScreen;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.string.StringUtils;

@SideOnly(Side.CLIENT)
public class PatternCraftingPipeGui extends LogisticsBaseGuiScreen {

    private static final int MODE_BUTTON = 0;
    private static final int CLEAR_BUTTON = 1;
    private static final int MULTIPLY_BUTTON = 2;
    private static final int CANCEL_BUTTON = 3;
    private static final int TYPE_BUTTON = 4;
    private static final int ORE_DICT_BUTTON = 5;
    private static final int IGNORE_NBT_BUTTON = 6;
    private static final int RETURN_INPUTS_BUTTON = 7;
    private static final int TAB_BUTTON_BASE = 100;

    private static final int TAB_LEFT = 32;
    private static final int TAB_TOP = 20;
    private static final int TAB_SELECT_TOP = 40;
    private static final int INGREDIENT_LEFT = 26;
    private static final int INGREDIENT_TOP = 56;
    private static final int OUTPUT_LEFT = 116;
    private static final int OUTPUT_TOP = 74;
    private static final int PLAYER_INV_LEFT = 32;
    private static final int PLAYER_INV_TOP = 156;
    private static final int SLOT_SIZE = 18;
    private static final int SATELLITE_ICON_SIZE = 7;
    private static final int BUFFER_BLUE = 0xff55aaff;

    private final PipeItemsPatternCraftingLogistics pipe;
    private final PipePatternInventory editedPatternInventory;
    private final List<PatternSatelliteInfo> satellites;
    private int selectedPatternSlot;
    private boolean watching;

    public PatternCraftingPipeGui(EntityPlayer player, PipeItemsPatternCraftingLogistics pipe, int selectedPatternSlot,
            List<PatternSatelliteInfo> satellites) {
        super(238, 238, 0, 0);
        this.pipe = pipe;
        this.selectedPatternSlot = Math.max(0, Math.min(8, selectedPatternSlot));
        this.satellites = satellites == null ? Collections.emptyList() : new ArrayList<>(satellites);
        editedPatternInventory = new PipePatternInventory(pipe, this.selectedPatternSlot);
        PatternContainer dummy = new PatternContainer(player.inventory, editedPatternInventory);
        PatternGuiProvider.addPatternSlots(
                dummy,
                currentPattern(),
                INGREDIENT_LEFT + 1,
                INGREDIENT_TOP + 1,
                OUTPUT_LEFT + 1,
                OUTPUT_TOP + 1);
        PatternCraftingPipeGuiProvider.addPatternSlots(dummy, pipe);
        dummy.addNormalSlotsForPlayerInventory(PLAYER_INV_LEFT, PLAYER_INV_TOP);
        inventorySlots = dummy;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        GuiButton modeButton = new SmallGuiButton(MODE_BUTTON, guiLeft + 166, guiTop + 54, 62, 12, modeLabel());
        modeButton.enabled = !pipe.isBlockingModeFixed();
        buttonList.add(modeButton);
        buttonList.add(new SmallGuiButton(CLEAR_BUTTON, guiLeft + 166, guiTop + 70, 30, 12, "Clear"));
        buttonList.add(new SmallGuiButton(MULTIPLY_BUTTON, guiLeft + 198, guiTop + 70, 30, 12, "2x"));
        buttonList.add(new SmallGuiButton(TYPE_BUTTON, guiLeft + 166, guiTop + 86, 62, 12, typeLabel()));
        buttonList.add(new SmallGuiButton(ORE_DICT_BUTTON, guiLeft + 166, guiTop + 102, 30, 12, oreDictLabel()));
        buttonList.add(new SmallGuiButton(IGNORE_NBT_BUTTON, guiLeft + 198, guiTop + 102, 30, 12, ignoreNbtLabel()));
        buttonList.add(new SmallGuiButton(CANCEL_BUTTON, guiLeft + 166, guiTop + 118, 62, 12, "Cancel"));
        buttonList.add(new SmallGuiButton(RETURN_INPUTS_BUTTON, guiLeft + 166, guiTop + 134, 62, 12, "Return"));
        for (int slot = 0; slot < 9; slot++) {
            buttonList.add(
                    new SmallGuiButton(
                            TAB_BUTTON_BASE + slot,
                            guiLeft + TAB_LEFT + slot * SLOT_SIZE,
                            guiTop + TAB_SELECT_TOP,
                            16,
                            8,
                            Integer.toString(slot + 1)));
        }
        updateButtons();
        if (!watching) {
            pipe.startWatching();
            watching = true;
        }
    }

    @Override
    public void onGuiClosed() {
        if (watching) {
            pipe.stopWatching();
            watching = false;
        }
        super.onGuiClosed();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == MODE_BUTTON && !pipe.isBlockingModeFixed()) {
            PipeItemsPatternCraftingLogistics.BlockingMode[] values = PipeItemsPatternCraftingLogistics.BlockingMode
                    .values();
            PipeItemsPatternCraftingLogistics.BlockingMode next = values[(pipe.getBlockingMode().ordinal() + 1)
                    % values.length];
            pipe.setBlockingMode(next);
            button.displayString = modeLabel();
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PatternCraftingPipeMode.class).setMode(next.ordinal())
                            .setTilePos(pipe.container));
        } else if (button.id == CLEAR_BUTTON) {
            AbstractPattern pattern = ItemPattern.fromStack(editedPatternInventory.getPatternStack());
            pattern.clear();
            sendPatternAction(PatternSlotActionPacket.Action.CLEAR);
        } else if (button.id == MULTIPLY_BUTTON) {
            AbstractPattern pattern = ItemPattern.fromStack(editedPatternInventory.getPatternStack());
            pattern.multiply(2);
            sendPatternAction(PatternSlotActionPacket.Action.MULTIPLY_TWO);
        } else if (button.id == TYPE_BUTTON) {
            ItemPattern.toggleProcessingPattern(editedPatternInventory.getPatternStack());
            updatePatternSlotLayout();
            sendPatternAction(PatternSlotActionPacket.Action.TOGGLE_PROCESSING);
            initGui();
        } else if (button.id == ORE_DICT_BUTTON) {
            currentPattern().toggleOreDictSubstitution();
            button.displayString = oreDictLabel();
            sendPatternAction(PatternSlotActionPacket.Action.TOGGLE_ORE_DICT);
        } else if (button.id == IGNORE_NBT_BUTTON) {
            currentPattern().toggleIgnoreNbt();
            button.displayString = ignoreNbtLabel();
            sendPatternAction(PatternSlotActionPacket.Action.TOGGLE_IGNORE_NBT);
        } else if (button.id == CANCEL_BUTTON) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PatternCraftingPipeCancel.class).setInteger(selectedPatternSlot)
                            .setTilePos(pipe.container));
        } else if (button.id == RETURN_INPUTS_BUTTON) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PatternCraftingPipeReturnInputs.class).setTilePos(pipe.container));
        } else if (button.id >= TAB_BUTTON_BASE && button.id < TAB_BUTTON_BASE + 9) {
            selectPatternSlot(button.id - TAB_BUTTON_BASE);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        updatePatternSlotLayout();
        updateButtons();
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        GuiGraphics.drawPlayerInventoryBackground(mc, guiLeft + PLAYER_INV_LEFT, guiTop + PLAYER_INV_TOP);
        for (int i = 0; i < 9; i++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + TAB_LEFT + i * SLOT_SIZE - 1, guiTop + TAB_TOP - 1);
        }
        drawSelectedTabFrame();
        AbstractPattern pattern = currentPattern();
        PatternSlotLayout layout = layout(pattern);
        for (int slot = 0; slot < pattern.getIngredientSlotCount(); slot++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + layout.inputX(slot), guiTop + layout.inputY(slot));
        }
        for (int slot = 0; slot < pattern.getResultSlotCount(); slot++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + layout.outputX(slot), guiTop + layout.outputY(slot));
        }
        drawSatelliteIcons();
        mc.fontRenderer.drawString("Pattern Crafting Pipe", guiLeft + 8, guiTop + 6, 0x404040);
        drawStatusPanel();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!hasSubGui()) {
            drawHudAmounts();
            drawSatelliteTooltip(mouseX, mouseY);
            drawFlagButtonTooltip(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (!hasSubGui()) {
            int satelliteSlot = getSatelliteHotspotSlot(mouseX, mouseY);
            if (mouseButton == 0 && satelliteSlot >= 0) {
                openSatelliteSelector(satelliteSlot);
                return;
            }
            int tabSlot = getTabSlot(mouseX, mouseY);
            if (mouseButton == 0 && tabSlot >= 0 && mc.thePlayer.inventory.getItemStack() == null) {
                selectPatternSlot(tabSlot);
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void func_146977_a(Slot slot) {
        boolean renderPatternResult = slot != null && slot.inventory == pipe.getPatternModule().getPatternInventory();
        PatternItemRenderer.setForceResultRender(renderPatternResult);
        try {
            super.func_146977_a(slot);
        } finally {
            PatternItemRenderer.clearForceResultRender();
        }
    }

    private void selectPatternSlot(int slot) {
        selectedPatternSlot = Math.max(0, Math.min(8, slot));
        editedPatternInventory.setPatternSlot(selectedPatternSlot);
        if (inventorySlots instanceof PatternContainer) {
            ((PatternContainer) inventorySlots).setSelectedPatternSlot(selectedPatternSlot);
        }
        updatePatternSlotLayout();
        MainProxy.sendPacketToServer(
                PacketHandler.getPacket(PatternPipeSelectPacket.class).setInteger(selectedPatternSlot)
                        .setTilePos(pipe.container));
    }

    private void openSatelliteSelector(int inputSlot) {
        AbstractPattern pattern = currentPattern();
        boolean fluidTarget = isFluidSatelliteSlot(inputSlot);
        setSubGui(
                new PatternSatelliteSelectorGui(
                        inputSlot,
                        getSatelliteId(pattern, inputSlot, fluidTarget),
                        getSatelliteUuid(pattern, inputSlot, fluidTarget),
                        fluidTarget ? PatternSatelliteInfo.SatelliteType.FLUID
                                : PatternSatelliteInfo.SatelliteType.ITEM,
                        satellites,
                        (satelliteId, satelliteUuid) -> setSatelliteForInputSlot(
                                inputSlot,
                                satelliteId,
                                satelliteUuid,
                                fluidTarget)));
    }

    private void setSatelliteForInputSlot(int inputSlot, int satelliteId, String satelliteUuid, boolean fluidTarget) {
        if (fluidTarget) {
            currentPattern().setFluidSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
        } else {
            currentPattern().setSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
        }
        PatternPipeSatelliteAssignmentPacket packet = PacketHandler
                .getPacket(PatternPipeSatelliteAssignmentPacket.class);
        packet.setTilePos(pipe.container);
        packet.setPatternSlot(selectedPatternSlot);
        packet.setInputSlot(inputSlot);
        packet.setSatelliteId(satelliteId);
        packet.setSatelliteUuid(satelliteUuid);
        packet.setFluidTarget(fluidTarget);
        MainProxy.sendPacketToServer(packet);
    }

    private void sendPatternAction(PatternSlotActionPacket.Action action) {
        PatternPipeSlotActionPacket packet = PacketHandler.getPacket(PatternPipeSlotActionPacket.class);
        packet.setTilePos(pipe.container);
        packet.setPatternSlot(selectedPatternSlot);
        packet.setAction(action.ordinal());
        MainProxy.sendPacketToServer(packet);
    }

    private void drawSelectedTabFrame() {
        int x = guiLeft + TAB_LEFT + selectedPatternSlot * SLOT_SIZE - 2;
        int y = guiTop + TAB_TOP - 2;
        Gui.drawRect(x, y, x + 20, y + 2, 0xff55aaff);
        Gui.drawRect(x, y + 19, x + 20, y + 21, 0xff55aaff);
        Gui.drawRect(x, y, x + 2, y + 21, 0xff55aaff);
        Gui.drawRect(x + 18, y, x + 20, y + 21, 0xff55aaff);
    }

    private void drawStatusPanel() {
        PatternCraftingHudState.PatternInfo info = getSelectedPatternInfo();
        String status = info == null ? "Empty" : info.getStatus();
        mc.fontRenderer.drawString("Mode: " + modeLabel(), guiLeft + 166, guiTop + 150, 0x404040);
        List<String> lines = mc.fontRenderer.listFormattedStringToWidth(status == null ? "" : status, 62);
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            mc.fontRenderer.drawString(lines.get(i), guiLeft + 166, guiTop + 162 + i * 9, 0x404040);
        }
    }

    private void drawHudAmounts() {
        PatternCraftingHudState.PatternInfo info = getSelectedPatternInfo();
        if (info == null) {
            return;
        }
        PatternSlotLayout layout = layout(currentPattern());
        for (PatternCraftingHudState.IngredientInfo ingredient : info.getIngredients()) {
            if (ingredient.bufferedAmount() > 0 && ingredient.slot() >= 0) {
                int x = guiLeft + layout.inputX(ingredient.slot());
                int y = guiTop + layout.inputY(ingredient.slot());
                drawAmount(ingredient.bufferedAmount(), x, y);
            }
        }
        for (PatternCraftingHudState.OutputInfo output : info.getOutputs()) {
            if (output.requestedAmount() > 0 && output.slot() >= 0) {
                int x = guiLeft + layout.outputX(output.slot());
                int y = guiTop + layout.outputY(output.slot());
                drawAmount(output.requestedAmount(), x, y);
            }
        }
    }

    private void drawAmount(int amount, int x, int y) {
        String amountString = StringUtils.getFormatedStackSize(amount, true);
        mc.fontRenderer.drawStringWithShadow(
                amountString,
                x + 17 - mc.fontRenderer.getStringWidth(amountString),
                y - 2,
                BUFFER_BLUE);
    }

    private PatternCraftingHudState.PatternInfo getSelectedPatternInfo() {
        for (PatternCraftingHudState.PatternInfo info : pipe.getHudState().getPatterns()) {
            if (info.getSlot() == selectedPatternSlot) {
                return info;
            }
        }
        return null;
    }

    private void drawSatelliteIcons() {
        AbstractPattern pattern = currentPattern();
        PatternSlotLayout layout = layout(pattern);
        for (int inputSlot = 0; inputSlot < pattern.getIngredientSlotCount(); inputSlot++) {
            boolean fluidTarget = isFluidSatelliteSlot(inputSlot);
            int satelliteId = getSatelliteId(pattern, inputSlot, fluidTarget);
            String satelliteUuid = getSatelliteUuid(pattern, inputSlot, fluidTarget);
            int x = guiLeft + layout.inputX(inputSlot);
            int y = guiTop + layout.inputY(inputSlot);
            boolean assigned = satelliteId > 0 || !satelliteUuid.isEmpty();
            Gui.drawRect(
                    x,
                    y,
                    x + SATELLITE_ICON_SIZE,
                    y + SATELLITE_ICON_SIZE,
                    assigned ? (fluidTarget ? 0xff00a8cc : 0xff2b6ee8) : 0xff777777);
            mc.fontRenderer.drawString(assigned ? (fluidTarget ? "F" : "S") : "+", x + 1, y, 0xffffff);
        }
    }

    private void drawSatelliteTooltip(int mouseX, int mouseY) {
        int inputSlot = getSatelliteHotspotSlot(mouseX, mouseY);
        if (inputSlot < 0) {
            return;
        }
        AbstractPattern pattern = currentPattern();
        boolean fluidTarget = isFluidSatelliteSlot(inputSlot);
        int satelliteId = getSatelliteId(pattern, inputSlot, fluidTarget);
        String satelliteUuid = getSatelliteUuid(pattern, inputSlot, fluidTarget);
        List<String> tooltip = new ArrayList<>();
        if (satelliteId <= 0 && satelliteUuid.isEmpty()) {
            tooltip.add("Local inventory");
        } else {
            PatternSatelliteInfo satellite = getSatelliteInfo(satelliteId, satelliteUuid, fluidTarget);
            tooltip.add(
                    (fluidTarget ? "Fluid satellite " : "Pattern satellite ")
                            + (satellite == null ? "#" + satelliteId : satellite.displayName()));
            if (satellite != null) {
                tooltip.add(
                        "Dim " + satellite
                                .dimension() + " at " + satellite.x() + ", " + satellite.y() + ", " + satellite.z());
                tooltip.add(satellite.distance() >= 0 ? satellite.distance() + "m away" : "Other dimension");
                if (satellite.favorite()) {
                    tooltip.add("Stored on memory chip");
                }
            }
        }
        GuiGraphics.drawToolTip(mouseX, mouseY, tooltip, EnumChatFormatting.WHITE);
    }

    private void drawFlagButtonTooltip(int mouseX, int mouseY) {
        GuiButton hoveredButton = getHoveredFlagButton(mouseX, mouseY);
        if (hoveredButton == null) {
            return;
        }
        List<String> tooltip = new ArrayList<>();
        if (hoveredButton.id == ORE_DICT_BUTTON) {
            tooltip.add("OreDict substitution");
            tooltip.add(currentPattern().isOreDictSubstitutionEnabled() ? "Enabled" : "Disabled");
        } else if (hoveredButton.id == IGNORE_NBT_BUTTON) {
            tooltip.add("Ignore ingredient NBT");
            tooltip.add(currentPattern().isIgnoreNbtEnabled() ? "Enabled" : "Disabled");
        }
        GuiGraphics.drawToolTip(mouseX, mouseY, tooltip, EnumChatFormatting.WHITE);
    }

    private GuiButton getHoveredFlagButton(int mouseX, int mouseY) {
        for (Object buttonObject : buttonList) {
            if (!(buttonObject instanceof GuiButton button)) {
                continue;
            }
            if (button.id != ORE_DICT_BUTTON && button.id != IGNORE_NBT_BUTTON) {
                continue;
            }
            if (mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                    && mouseY >= button.yPosition
                    && mouseY < button.yPosition + button.height) {
                return button;
            }
        }
        return null;
    }

    private PatternSatelliteInfo getSatelliteInfo(int satelliteId, String satelliteUuid, boolean fluidTarget) {
        PatternSatelliteInfo.SatelliteType type = fluidTarget ? PatternSatelliteInfo.SatelliteType.FLUID
                : PatternSatelliteInfo.SatelliteType.ITEM;
        for (PatternSatelliteInfo satellite : satellites) {
            if (satellite.type() == type && ((!satelliteUuid.isEmpty() && satelliteUuid.equals(satellite.uuid()))
                    || (satelliteUuid.isEmpty() && satellite.id() == satelliteId))) {
                return satellite;
            }
        }
        return null;
    }

    private int getSatelliteHotspotSlot(int mouseX, int mouseY) {
        AbstractPattern pattern = currentPattern();
        PatternSlotLayout layout = layout(pattern);
        for (int inputSlot = 0; inputSlot < pattern.getIngredientSlotCount(); inputSlot++) {
            int x = guiLeft + layout.inputX(inputSlot);
            int y = guiTop + layout.inputY(inputSlot);
            if (mouseX >= x && mouseX < x + SATELLITE_ICON_SIZE && mouseY >= y && mouseY < y + SATELLITE_ICON_SIZE) {
                return inputSlot;
            }
        }
        return -1;
    }

    private int getTabSlot(int mouseX, int mouseY) {
        for (int slot = 0; slot < 9; slot++) {
            int x = guiLeft + TAB_LEFT + slot * SLOT_SIZE - 1;
            int y = guiTop + TAB_TOP - 1;
            if (mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= y && mouseY < y + SLOT_SIZE) {
                return slot;
            }
        }
        return -1;
    }

    private String modeLabel() {
        return switch (pipe.getBlockingMode()) {
            case BLOCKING -> "Blocking";
            case SMART -> "Smart";
            default -> "No Block";
        };
    }

    private AbstractPattern currentPattern() {
        return ItemPattern.fromStack(editedPatternInventory.getPatternStack());
    }

    private PatternSlotLayout layout(AbstractPattern pattern) {
        return new PatternSlotLayout(pattern, INGREDIENT_LEFT, INGREDIENT_TOP, OUTPUT_LEFT, OUTPUT_TOP);
    }

    private void updatePatternSlotLayout() {
        if (inventorySlots instanceof PatternContainer) {
            ((PatternContainer) inventorySlots).updatePatternSlotLayout(
                    currentPattern(),
                    INGREDIENT_LEFT + 1,
                    INGREDIENT_TOP + 1,
                    OUTPUT_LEFT + 1,
                    OUTPUT_TOP + 1);
        }
    }

    private boolean isFluidSatelliteSlot(int inputSlot) {
        IPatternStack stack = currentPattern().getPatternStackInSlot(inputSlot);
        return PatternStackHelper.isFluid(stack);
    }

    private int getSatelliteId(AbstractPattern pattern, int inputSlot, boolean fluidTarget) {
        return fluidTarget ? pattern.getFluidSatelliteIdForInputSlot(inputSlot)
                : pattern.getSatelliteIdForInputSlot(inputSlot);
    }

    private String getSatelliteUuid(AbstractPattern pattern, int inputSlot, boolean fluidTarget) {
        return fluidTarget ? pattern.getFluidSatelliteUuidForInputSlot(inputSlot)
                : pattern.getSatelliteUuidForInputSlot(inputSlot);
    }

    private String typeLabel() {
        return ItemPattern.isProcessingPattern(editedPatternInventory.getPatternStack()) ? "Crafting" : "Processing";
    }

    private void updateButtons() {
        ItemStack pattern = editedPatternInventory.getPatternStack();
        for (Object buttonObject : buttonList) {
            if (!(buttonObject instanceof GuiButton button)) {
                continue;
            }
            if (button.id == MODE_BUTTON) {
                button.displayString = modeLabel();
                button.enabled = !pipe.isBlockingModeFixed();
            } else if (button.id == TYPE_BUTTON) {
                button.displayString = typeLabel();
                button.enabled = pattern != null;
            } else if (button.id == ORE_DICT_BUTTON) {
                button.displayString = oreDictLabel();
                button.enabled = pattern != null;
            } else if (button.id == IGNORE_NBT_BUTTON) {
                button.displayString = ignoreNbtLabel();
                button.enabled = pattern != null;
            } else if (button.id == CLEAR_BUTTON || button.id == MULTIPLY_BUTTON || button.id == CANCEL_BUTTON) {
                button.enabled = pattern != null;
            } else if (button.id == RETURN_INPUTS_BUTTON) {
                button.enabled = true;
            } else if (button.id >= TAB_BUTTON_BASE && button.id < TAB_BUTTON_BASE + 9) {
                int slot = button.id - TAB_BUTTON_BASE;
                button.enabled = slot != selectedPatternSlot;
            }
        }
    }

    private String oreDictLabel() {
        return currentPattern().isOreDictSubstitutionEnabled() ? "Ore+" : "Ore-";
    }

    private String ignoreNbtLabel() {
        return currentPattern().isIgnoreNbtEnabled() ? "NBT+" : "NBT-";
    }
}
