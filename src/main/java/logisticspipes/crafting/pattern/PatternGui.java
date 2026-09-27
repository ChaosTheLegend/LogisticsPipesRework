package logisticspipes.crafting.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
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

@SideOnly(Side.CLIENT)
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
        drawSatelliteIcons();
        mc.fontRenderer.drawString("Pattern", guiLeft + 8, guiTop + 6, 0x404040);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!hasSubGui()) {
            drawSatelliteButtonTooltip(mouseX, mouseY);
            drawFlagButtonTooltip(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0 && !hasSubGui()) {
            int inputSlot = getSatelliteHotspotSlot(mouseX, mouseY);
            if (inputSlot >= 0) {
                openSatelliteSelector(inputSlot);
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
                    && mouseY >= button.yPosition
                    && mouseY < button.yPosition + button.height) {
                return button;
            }
        }
        return null;
    }

    private void openSatelliteSelector(int inputSlot) {
        boolean fluidTarget = isFluidSatelliteSlot(inputSlot);
        int currentSatelliteId = getSatelliteId(inputSlot, fluidTarget);
        String currentSatelliteUuid = getSatelliteUuid(inputSlot, fluidTarget);
        setSubGui(
                new PatternSatelliteSelectorGui(
                        inputSlot,
                        currentSatelliteId,
                        currentSatelliteUuid,
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
            ItemPattern.fromStack(patternInventory.getPatternStack())
                    .setFluidSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
        } else {
            ItemPattern.fromStack(patternInventory.getPatternStack())
                    .setSatelliteTargetForInputSlot(inputSlot, satelliteId, satelliteUuid);
        }
        MainProxy.sendPacketToServer(
                PacketHandler.getPacket(PatternSatelliteAssignmentPacket.class)
                        .setInventorySlot(patternInventory.getInventorySlot()).setInputSlot(inputSlot)
                        .setSatelliteId(satelliteId).setSatelliteUuid(satelliteUuid).setFluidTarget(fluidTarget));
    }

    private void drawSatelliteButtonTooltip(int mouseX, int mouseY) {
        int inputSlot = getSatelliteHotspotSlot(mouseX, mouseY);
        if (inputSlot < 0) {
            return;
        }
        boolean fluidTarget = isFluidSatelliteSlot(inputSlot);
        int satelliteId = getSatelliteId(inputSlot, fluidTarget);
        String satelliteUuid = getSatelliteUuid(inputSlot, fluidTarget);
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
            } else {
                tooltip.add("Not loaded in this GUI snapshot");
            }
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
            boolean fluidTarget = isFluidSatelliteSlot(inputSlot);
            int satelliteId = getSatelliteId(inputSlot, fluidTarget);
            String satelliteUuid = getSatelliteUuid(inputSlot, fluidTarget);
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

    private int getSatelliteHotspotSlot(int mouseX, int mouseY) {
        PatternSlotLayout layout = layout(currentPattern());
        for (int inputSlot = 0; inputSlot < getInputSize(); inputSlot++) {
            int x = guiLeft + layout.inputX(inputSlot);
            int y = guiTop + layout.inputY(inputSlot);
            if (mouseX >= x && mouseX < x + SATELLITE_ICON_SIZE && mouseY >= y && mouseY < y + SATELLITE_ICON_SIZE) {
                return inputSlot;
            }
        }
        return -1;
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

    private int getSatelliteId(int inputSlot, boolean fluidTarget) {
        AbstractPattern pattern = currentPattern();
        return fluidTarget ? pattern.getFluidSatelliteIdForInputSlot(inputSlot)
                : pattern.getSatelliteIdForInputSlot(inputSlot);
    }

    private String getSatelliteUuid(int inputSlot, boolean fluidTarget) {
        AbstractPattern pattern = currentPattern();
        return fluidTarget ? pattern.getFluidSatelliteUuidForInputSlot(inputSlot)
                : pattern.getSatelliteUuidForInputSlot(inputSlot);
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
