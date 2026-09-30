package logisticspipes.crafting.pattern;

import logisticspipes.crafting.PatternSatelliteInfo;
import logisticspipes.crafting.PatternSatelliteSelectorGui;
import logisticspipes.crafting.patternStack.IPatternStack;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.crafting.PatternSatelliteAssignmentPacket;
import logisticspipes.network.packets.gui.PatternSlotActionPacket;
import logisticspipes.proxy.MainProxy;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.LogisticsBaseGuiScreen;
import logisticspipes.utils.gui.SmallGuiButton;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PatternGui extends LogisticsBaseGuiScreen {

    private final PatternInventory patternInventory;
    private final List<PatternSatelliteInfo> satellites;
    public static final int CLEAR_BUTTON_ID = 0;
    public static final int MULTIPLE_BUTTON_ID = 1;
    public static final int TYPE_BUTTON_ID = 2;
    public static final int ORE_DICT_BUTTON_ID = 3;
    public static final int IGNORE_NBT_BUTTON_ID = 4;
    private static final int INGREDIENT_LEFT = 25;
    private static final int INGREDIENT_TOP = 16;
    private static final int OUTPUT_LEFT = 115;
    private static final int OUTPUT_TOP = 34;
    private static final int PLAYER_INV_TOP = 116;
    private static final int SATELLITE_ICON_SIZE = 7;

    public PatternGui(EntityPlayer player, IInventory inventory, List<PatternSatelliteInfo> satellites) {
        super(220, 200, 0, 0);
        patternInventory = (PatternInventory) inventory;
        this.satellites = satellites == null ? Collections.emptyList() : new ArrayList<>(satellites);
        PatternContainer dummy = new PatternContainer(player.inventory, inventory);
        PatternGuiProvider.addPatternSlots(dummy, currentPattern());
        dummy.addNormalSlotsForPlayerInventory(8, PLAYER_INV_TOP);
        inventorySlots = dummy;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        addActionButtons();
    }

    private void addActionButtons() {
        GuiButton clearButton = new SmallGuiButton(CLEAR_BUTTON_ID, guiLeft + 174, guiTop + 18, 38, 12, "Clear");
        addButton(clearButton);

        GuiButton multiplierButton = new SmallGuiButton(MULTIPLE_BUTTON_ID, guiLeft + 174, guiTop + 34, 38, 12, "2x");
        addButton(multiplierButton);

        GuiButton typeButton = new SmallGuiButton(TYPE_BUTTON_ID, guiLeft + 174, guiTop + 50, 38, 12, typeLabel());
        addButton(typeButton);

        GuiButton oreDictButton = new SmallGuiButton(
            ORE_DICT_BUTTON_ID,
            guiLeft + 174,
            guiTop + 66,
            38,
            12,
            oreDictLabel());
        addButton(oreDictButton);

        GuiButton ignoreNbtButton = new SmallGuiButton(
            IGNORE_NBT_BUTTON_ID,
            guiLeft + 174,
            guiTop + 82,
            38,
            12,
            ignoreNbtLabel());
        addButton(ignoreNbtButton);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        updatePatternSlotLayout();
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        GuiGraphics.drawPlayerInventoryBackground(mc, guiLeft + 8, guiTop + PLAYER_INV_TOP);
        AbstractPattern pattern = currentPattern();
        PatternSlotLayout layout = layout(pattern);
        for (int slot = 0; slot < pattern.getIngredientSlotCount(); slot++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + layout.inputX(slot), guiTop + layout.inputY(slot));
        }
        for (int slot = 0; slot < pattern.getResultSlotCount(); slot++) {
            GuiGraphics.drawSlotBackground(mc, guiLeft + layout.outputX(slot), guiTop + layout.outputY(slot));
        }
        PatternOutputSelection.drawMainOutput(pattern, layout, guiLeft, guiTop);
        drawSatelliteIcons();
        mc.fontRenderer.drawString("Pattern", guiLeft + 8, guiTop + 6, 0x404040);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!hasSubGui()) {
            drawSatelliteButtonTooltip(mouseX, mouseY);
            drawFlagButtonTooltip(mouseX, mouseY);
            PatternOutputSelection.drawTooltip(currentPattern(), layout(currentPattern()),
                guiLeft, guiTop, mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (!hasSubGui() && mouseButton == 0 && PatternOutputSelection.isAltDown()) {
            AbstractPattern pattern = currentPattern();
            int outputSlot = PatternOutputSelection.hoveredOutput(pattern, layout(pattern),
                guiLeft, guiTop, mouseX, mouseY);
            if (outputSlot >= 0) {
                pattern.setMainOutputSlot(outputSlot);
                MainProxy.sendPacketToServer(PacketHandler.getPacket(PatternSlotActionPacket.class)
                    .setInventorySlot(patternInventory.getInventorySlot())
                    .setAction(PatternSlotActionPacket.Action.SELECT_MAIN_OUTPUT.ordinal())
                    .setOutputSlot(outputSlot));
                return;
            }
        }
        if (mouseButton == 0 && isCtrlKeyDown() && !hasSubGui()) {
            SatelliteSlot satelliteSlot = getSatelliteSlot(mouseX, mouseY);
            if (satelliteSlot != null) {
                openSatelliteSelector(satelliteSlot);
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case CLEAR_BUTTON_ID:
                ItemPattern.fromStack(patternInventory.getPatternStack()).clear();
                MainProxy.sendPacketToServer(
                        PacketHandler.getPacket(PatternSlotActionPacket.class)
                                .setInventorySlot(patternInventory.getInventorySlot())
                                .setAction(PatternSlotActionPacket.Action.CLEAR.ordinal()));
                break;
            case MULTIPLE_BUTTON_ID:
                ItemPattern.fromStack(patternInventory.getPatternStack()).multiply(2);
                MainProxy.sendPacketToServer(
                        PacketHandler.getPacket(PatternSlotActionPacket.class)
                                .setInventorySlot(patternInventory.getInventorySlot())
                                .setAction(PatternSlotActionPacket.Action.MULTIPLY_TWO.ordinal()));
                break;
            case TYPE_BUTTON_ID:
                ItemPattern.toggleProcessingPattern(patternInventory.getPatternStack());
                updatePatternSlotLayout();
                MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PatternSlotActionPacket.class)
                        .setInventorySlot(patternInventory.getInventorySlot())
                        .setAction(PatternSlotActionPacket.Action.TOGGLE_PROCESSING.ordinal()));
                initGui();
                break;
            case ORE_DICT_BUTTON_ID:
                currentPattern().toggleOreDictSubstitution();
                button.displayString = oreDictLabel();
                MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PatternSlotActionPacket.class)
                        .setInventorySlot(patternInventory.getInventorySlot())
                        .setAction(PatternSlotActionPacket.Action.TOGGLE_ORE_DICT.ordinal()));
                break;
            case IGNORE_NBT_BUTTON_ID:
                currentPattern().toggleIgnoreNbt();
                button.displayString = ignoreNbtLabel();
                MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(PatternSlotActionPacket.class)
                        .setInventorySlot(patternInventory.getInventorySlot())
                        .setAction(PatternSlotActionPacket.Action.TOGGLE_IGNORE_NBT.ordinal()));
                break;

        }
    }

    private void drawFlagButtonTooltip(int mouseX, int mouseY) {
        GuiButton hoveredButton = getHoveredFlagButton(mouseX, mouseY);
        if (hoveredButton == null) {
            return;
        }
        List<String> tooltip = new ArrayList<>();
        if (hoveredButton.id == ORE_DICT_BUTTON_ID) {
            tooltip.add("OreDict substitution");
            tooltip.add(currentPattern().isOreDictSubstitutionEnabled() ? "Enabled" : "Disabled");
        } else if (hoveredButton.id == IGNORE_NBT_BUTTON_ID) {
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
            if (button.id != ORE_DICT_BUTTON_ID && button.id != IGNORE_NBT_BUTTON_ID) {
                continue;
            }
            if (mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height) {
                return button;
            }
        }
        return null;
    }

    private void openSatelliteSelector(SatelliteSlot target) {
        boolean fluidTarget = isFluidSatelliteSlot(target);
        int currentSatelliteId = getSatelliteId(target, fluidTarget);
        String currentSatelliteUuid = getSatelliteUuid(target, fluidTarget);
        setSubGui(
                new PatternSatelliteSelectorGui(
                    target.slot,
                    target.output,
                        currentSatelliteId,
                    currentSatelliteUuid,
                    fluidTarget ? PatternSatelliteInfo.SatelliteType.FLUID
                        : PatternSatelliteInfo.SatelliteType.ITEM,
                        satellites,
                    (satelliteId, satelliteUuid) -> setSatelliteForSlot(
                        target,
                        satelliteId,
                        satelliteUuid,
                        fluidTarget)));
    }

    private void setSatelliteForSlot(SatelliteSlot target, int satelliteId, String satelliteUuid,
                                     boolean fluidTarget) {
        if (target.output && fluidTarget) {
            ItemPattern.fromStack(patternInventory.getPatternStack())
                .setFluidByproductSatelliteTargetForOutputSlot(target.slot, satelliteId, satelliteUuid);
        } else if (target.output) {
            ItemPattern.fromStack(patternInventory.getPatternStack())
                .setByproductSatelliteTargetForOutputSlot(target.slot, satelliteId, satelliteUuid);
        } else if (fluidTarget) {
            ItemPattern.fromStack(patternInventory.getPatternStack())
                .setFluidSatelliteTargetForInputSlot(target.slot, satelliteId, satelliteUuid);
        } else {
            ItemPattern.fromStack(patternInventory.getPatternStack())
                .setSatelliteTargetForInputSlot(target.slot, satelliteId, satelliteUuid);
        }
        MainProxy.sendPacketToServer(
                PacketHandler.getPacket(PatternSatelliteAssignmentPacket.class)
                    .setInventorySlot(patternInventory.getInventorySlot()).setInputSlot(target.slot)
                    .setSatelliteId(satelliteId).setSatelliteUuid(satelliteUuid).setFluidTarget(fluidTarget)
                    .setOutputTarget(target.output));
    }

    private void drawSatelliteButtonTooltip(int mouseX, int mouseY) {
        SatelliteSlot target = getSatelliteSlot(mouseX, mouseY);
        if (target == null) {
            return;
        }
        boolean fluidTarget = isFluidSatelliteSlot(target);
        int satelliteId = getSatelliteId(target, fluidTarget);
        String satelliteUuid = getSatelliteUuid(target, fluidTarget);
        List<String> tooltip = new ArrayList<>();
        tooltip.add("Ctrl + left-click to configure satellite");
        if (satelliteId <= 0 && satelliteUuid.isEmpty()) {
            tooltip.add(target.output ? "Extract byproduct locally" : "Local inventory");
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
            } else {
                tooltip.add("Not loaded in this GUI snapshot");
            }
        }
        if (target.output) {
            tooltip.add("Requires a byproduct extraction upgrade");
        }
        GuiGraphics.drawToolTip(mouseX, mouseY, tooltip, EnumChatFormatting.WHITE);
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

    private void drawSatelliteIcons() {
        AbstractPattern pattern = currentPattern();
        PatternSlotLayout layout = layout(pattern);
        for (int inputSlot = 0; inputSlot < getInputSize(); inputSlot++) {
            drawSatelliteIcon(new SatelliteSlot(inputSlot, false), layout.inputX(inputSlot), layout.inputY(inputSlot));
        }
        for (int outputSlot = 0; outputSlot < pattern.getResultSlotCount(); outputSlot++) {
            drawSatelliteIcon(new SatelliteSlot(outputSlot, true), layout.outputX(outputSlot), layout.outputY(outputSlot));
        }
    }

    private void drawSatelliteIcon(SatelliteSlot target, int relativeX, int relativeY) {
        boolean fluidTarget = isFluidSatelliteSlot(target);
        int satelliteId = getSatelliteId(target, fluidTarget);
        String satelliteUuid = getSatelliteUuid(target, fluidTarget);
        int x = guiLeft + relativeX;
        int y = guiTop + relativeY;
        boolean assigned = satelliteId > 0 || !satelliteUuid.isEmpty();
        int color = target.output ? (fluidTarget ? 0xffb05bd8 : 0xffd87928)
            : (fluidTarget ? 0xff00a8cc : 0xff2b6ee8);
        Gui.drawRect(x, y, x + SATELLITE_ICON_SIZE, y + SATELLITE_ICON_SIZE,
            assigned ? color : 0xff777777);
        mc.fontRenderer.drawString(assigned ? (target.output ? (fluidTarget ? "F" : "B")
            : (fluidTarget ? "F" : "S")) : "+", x + 1, y, 0xffffff);
    }

    private SatelliteSlot getSatelliteSlot(int mouseX, int mouseY) {
        AbstractPattern pattern = currentPattern();
        PatternSlotLayout layout = layout(pattern);
        int hotspotSize = isCtrlKeyDown() ? PatternSlotLayout.SLOT_SIZE : SATELLITE_ICON_SIZE;
        for (int inputSlot = 0; inputSlot < getInputSize(); inputSlot++) {
            int x = guiLeft + layout.inputX(inputSlot);
            int y = guiTop + layout.inputY(inputSlot);
            if (mouseX >= x && mouseX < x + hotspotSize && mouseY >= y && mouseY < y + hotspotSize) {
                return new SatelliteSlot(inputSlot, false);
            }
        }
        for (int outputSlot = 0; outputSlot < pattern.getResultSlotCount(); outputSlot++) {
            int x = guiLeft + layout.outputX(outputSlot);
            int y = guiTop + layout.outputY(outputSlot);
            if (mouseX >= x && mouseX < x + hotspotSize && mouseY >= y && mouseY < y + hotspotSize) {
                return new SatelliteSlot(outputSlot, true);
            }
        }
        return null;
    }

    public int getInventorySlot() {
        return patternInventory.getInventorySlot();
    }

    public int getInputSize() {
        return currentPattern().getIngredientSlotCount();
    }

    private AbstractPattern currentPattern() {
        return ItemPattern.fromStack(patternInventory.getPatternStack());
    }

    private PatternSlotLayout layout(AbstractPattern pattern) {
        return new PatternSlotLayout(pattern, INGREDIENT_LEFT, INGREDIENT_TOP, OUTPUT_LEFT, OUTPUT_TOP);
    }

    private void updatePatternSlotLayout() {
        for (Object entry : buttonList) {
            if (entry instanceof GuiButton button && button.id == TYPE_BUTTON_ID) {
                button.displayString = typeLabel();
            }
        }
        if (inventorySlots instanceof PatternContainer) {
            ((PatternContainer) inventorySlots)
                .updatePatternSlotLayout(
                    currentPattern(),
                    INGREDIENT_LEFT + 1,
                    INGREDIENT_TOP + 1,
                    OUTPUT_LEFT + 1,
                    OUTPUT_TOP + 1);
        }
    }

    private boolean isFluidSatelliteSlot(SatelliteSlot target) {
        AbstractPattern pattern = currentPattern();
        int patternSlot = target.output ? pattern.getResultSlotStart() + target.slot : target.slot;
        IPatternStack stack = pattern.getPatternStackInSlot(patternSlot);
        return PatternStackHelper.isFluid(stack);
    }

    private int getSatelliteId(SatelliteSlot target, boolean fluidTarget) {
        AbstractPattern pattern = currentPattern();
        if (target.output) {
            return fluidTarget ? pattern.getFluidByproductSatelliteIdForOutputSlot(target.slot)
                : pattern.getByproductSatelliteIdForOutputSlot(target.slot);
        }
        return fluidTarget ? pattern.getFluidSatelliteIdForInputSlot(target.slot)
            : pattern.getSatelliteIdForInputSlot(target.slot);
    }

    private String getSatelliteUuid(SatelliteSlot target, boolean fluidTarget) {
        AbstractPattern pattern = currentPattern();
        if (target.output) {
            return fluidTarget ? pattern.getFluidByproductSatelliteUuidForOutputSlot(target.slot)
                : pattern.getByproductSatelliteUuidForOutputSlot(target.slot);
        }
        return fluidTarget ? pattern.getFluidSatelliteUuidForInputSlot(target.slot)
            : pattern.getSatelliteUuidForInputSlot(target.slot);
    }

    private static final class SatelliteSlot {

        private final int slot;
        private final boolean output;

        private SatelliteSlot(int slot, boolean output) {
            this.slot = slot;
            this.output = output;
        }
    }

    private String typeLabel() {
        return ItemPattern.isProcessingPattern(patternInventory.getPatternStack()) ? "Craft" : "Proc";
    }

    private String oreDictLabel() {
        return currentPattern().isOreDictSubstitutionEnabled() ? "Ore+" : "Ore-";
    }

    private String ignoreNbtLabel() {
        return currentPattern().isIgnoreNbtEnabled() ? "NBT+" : "NBT-";
    }

}
