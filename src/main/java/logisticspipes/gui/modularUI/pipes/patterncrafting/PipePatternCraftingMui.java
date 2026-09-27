package logisticspipes.gui.modularUI.pipes.patterncrafting;

import java.util.Locale;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.item.IItemHandlerModifiable;
import com.cleanroommc.modularui.utils.item.InvWrapper;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.ScrollingTextWidget;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import logisticspipes.compat.ModularUIHelper;
import logisticspipes.crafting.PatternCraftingHudState;
import logisticspipes.crafting.PatternSatelliteInfo;
import logisticspipes.crafting.pattern.AbstractPattern;
import logisticspipes.crafting.pattern.DefaultPattern;
import logisticspipes.crafting.pattern.ProcessingPattern;
import logisticspipes.crafting.patternStack.PatternStackHelper;
import logisticspipes.gui.modularUI.LogisticsPipeMUI;
import logisticspipes.gui.modularUI.pipes.patterncrafting.PatternCraftingSyncHandler.PatternAction;
import logisticspipes.pipes.PipeItemsPatternCraftingLogistics;

/**
 * ModularUI GUI of the pattern crafting pipe.
 * <p>
 * Layout, top to bottom: the nine pattern slots (click to select), the editor for the selected pattern (inputs,
 * crafting target, outputs, pattern options), a status line and the player inventory. NEI recipes can be transferred
 * into the selected pattern, see {@link PatternCraftingContainer}.
 */
public class PipePatternCraftingMui extends LogisticsPipeMUI {

    private static final int WIDTH = 196;
    private static final int HEIGHT = 226;
    private static final int SLOT = 18;

    private static final int PATTERN_X = 17;
    private static final int PATTERN_Y = 17;

    private static final int EDITOR_X = 7;
    private static final int EDITOR_Y = 38;
    private static final int EDITOR_WIDTH = 182;
    private static final int EDITOR_HEIGHT = 80;

    private static final int PROCESSING_INPUT_X = 11;
    private static final int PROCESSING_INPUT_Y = 42;
    private static final int CRAFTING_INPUT_X = 20;
    private static final int CRAFTING_INPUT_Y = 51;
    private static final int PROCESSING_OUTPUT_X = 111;
    private static final int PROCESSING_OUTPUT_Y = 60;
    private static final int CRAFTING_OUTPUT_X = 120;
    private static final int CRAFTING_OUTPUT_Y = 51;
    private static final int ARROW_X = 87;
    private static final int ARROW_Y = 50;
    private static final int TARGET_X = 89;
    private static final int TARGET_Y = 78;
    private static final int BUTTONS_X = 151;
    private static final int BUTTON_WIDTH = 36;
    private static final int HALF_BUTTON_WIDTH = 17;
    private static final int STATUS_Y = 121;

    private static final int TEXT_COLOR = 0x404040;
    private static final int FLAG_ON_COLOR = 0x55ff55;
    private static final int FLAG_OFF_COLOR = 0xa0a0a0;
    private static final String PATTERN_SLOT_GROUP = "pattern_crafting_patterns";
    private static final String LANG = "gui.patterncrafting.";

    private final PipeItemsPatternCraftingLogistics patternPipe;

    private PatternEditorState state;
    private PatternCraftingSyncHandler actions;
    private IPanelHandler satelliteSelector;
    private int selectorInputSlot;
    private boolean selectorFluid;

    public PipePatternCraftingMui(PipeItemsPatternCraftingLogistics pipe) {
        super(pipe);
        this.patternPipe = pipe;
    }

    @Override
    public String getId() {
        return "pipe_pattern_crafting";
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public ParentWidget addWidgets(ParentWidget widget, boolean addPlayerInventory) {
        // the panel is built in getPanel, it needs the sync manager and UI settings
        return widget;
    }

    @Override
    public ModularPanel getPanel(GuiData guiData, PanelSyncManager syncManager) {
        return getPanel(guiData, syncManager, null);
    }

    @Override
    public ModularPanel getPanel(GuiData guiData, PanelSyncManager syncManager, UISettings settings) {
        // the client learns the selection from the server once the GUI opened
        int initialSlot = syncManager.isClient() ? 0 : PatternEditorState.findInitialSlot(patternPipe, 0);
        state = new PatternEditorState(patternPipe, initialSlot);
        actions = new PatternCraftingSyncHandler(state);
        syncManager.syncValue("pattern_crafting", actions);
        syncManager.registerSlotGroup(PATTERN_SLOT_GROUP, PatternEditorState.PATTERN_SLOTS);
        if (settings != null && syncManager.isClient()) {
            // only the client needs the NEI hooks; the server keeps the default container (slot sync is identical)
            settings.customContainer(PatternCraftingContainer.supplier(actions));
        }

        ModularPanel panel = ModularPanel.defaultPanel(getId(), WIDTH, HEIGHT)
                .background(ModularUIHelper.BACKGROUND_TEXTURE);
        satelliteSelector = IPanelHandler.simple(panel, this::buildSatelliteSelector, true);

        panel.child(IKey.lang(LANG + "title").asWidget().pos(8, 6).color(TEXT_COLOR));
        panel.child(buildBlockingModeButton());
        addPatternSlots(panel);
        addEditor(panel);
        panel.child(buildStatusLine());
        panel.child(SlotGroupWidget.playerInventory(true));
        return panel;
    }

    // region header and pattern slots

    private IWidget buildBlockingModeButton() {
        return new ButtonWidget<>().pos(WIDTH - 7 - 62, 4).size(62, 12)
                .overlay(IKey.lang(() -> LANG + "mode." + modeKey()).scale(0.8f)).onMousePressed(mouseButton -> {
                    if (mouseButton != 0) return false;
                    actions.cycleBlockingMode();
                    return true;
                }).tooltipAutoUpdate(true).tooltipBuilder(tooltip -> {
                    tooltip.addLine(IKey.lang(LANG + "mode.title"));
                    tooltip.addLine(IKey.lang(LANG + "mode." + modeKey() + ".tip").style(EnumChatFormatting.GRAY));
                    if (actions.isBlockingModeFixed()) {
                        tooltip.addLine(IKey.lang(LANG + "mode.fixed").style(EnumChatFormatting.YELLOW));
                    }
                });
    }

    private String modeKey() {
        return actions.getBlockingMode().name().toLowerCase(Locale.ROOT);
    }

    private void addPatternSlots(ModularPanel panel) {
        IItemHandlerModifiable patterns = new InvWrapper(patternPipe.getPatternModule().getPatternInventory());
        for (int i = 0; i < PatternEditorState.PATTERN_SLOTS; i++) {
            ModularSlot slot = new ModularSlot(patterns, i).slotGroup(PATTERN_SLOT_GROUP)
                    .filter(PatternEditorState::isPattern)
                    // ModularSlot does not forward onSlotChanged, so notify the module (HUD, interests, saving) here
                    .changeListener((stack, onlyAmountChanged, client, init) -> {
                        if (!client && !init) {
                            patternPipe.getPatternModule().markPatternInventoryDirty();
                        }
                    });
            panel.child(
                    new PatternSelectSlot(i, state, actions).slot(slot).pos(PATTERN_X + i * SLOT, PATTERN_Y)
                            .tooltipBuilder(
                                    tooltip -> tooltip.addLine(IKey.lang(LANG + "pattern.empty")).addLine(
                                            IKey.lang(LANG + "pattern.empty.tip").style(EnumChatFormatting.GRAY))));
        }
    }

    // endregion

    // region editor

    private void addEditor(ModularPanel panel) {
        panel.child(
                new Widget<>().pos(EDITOR_X, EDITOR_Y).size(EDITOR_WIDTH, EDITOR_HEIGHT)
                        .background(new Rectangle().color(0x28000000)));
        panel.child(
                IKey.lang(LANG + "editor.nopattern").alignment(Alignment.Center).asWidget().pos(EDITOR_X, EDITOR_Y)
                        .size(EDITOR_WIDTH, EDITOR_HEIGHT).color(0x606060).setEnabledIf(w -> !state.hasPattern()));

        IItemHandlerModifiable edited = new InvWrapper(state.getEditedInventory());
        BooleanSupplier crafting = () -> state.hasPattern() && !state.isProcessing();
        BooleanSupplier processing = () -> state.hasPattern() && state.isProcessing();
        for (int i = 0; i < DefaultPattern.INGREDIENT_SLOTS; i++) {
            addInputSlot(panel, edited, i, CRAFTING_INPUT_X + i % 3 * SLOT, CRAFTING_INPUT_Y + i / 3 * SLOT, crafting);
        }
        for (int i = 0; i < ProcessingPattern.INGREDIENT_SLOTS; i++) {
            addInputSlot(
                    panel,
                    edited,
                    i,
                    PROCESSING_INPUT_X + i % 4 * SLOT,
                    PROCESSING_INPUT_Y + i / 4 * SLOT,
                    processing);
        }
        for (int i = 0; i < DefaultPattern.RESULT_SLOTS; i++) {
            addOutputSlot(
                    panel,
                    edited,
                    DefaultPattern.INGREDIENT_SLOTS + i,
                    i,
                    CRAFTING_OUTPUT_X,
                    CRAFTING_OUTPUT_Y + i * SLOT,
                    crafting);
        }
        for (int i = 0; i < ProcessingPattern.RESULT_SLOTS; i++) {
            addOutputSlot(
                    panel,
                    edited,
                    ProcessingPattern.INGREDIENT_SLOTS + i,
                    i,
                    PROCESSING_OUTPUT_X + i % 2 * SLOT,
                    PROCESSING_OUTPUT_Y + i / 2 * SLOT,
                    processing);
        }

        panel.child(
                new Widget<>().pos(ARROW_X, ARROW_Y).size(20, 20)
                        .background(GuiTextures.PROGRESS_ARROW.getSubArea(0, 0, 1, 0.5f))
                        .setEnabledIf(w -> state.hasPattern()));
        panel.child(buildTargetDisplay());
        addEditorButtons(panel);
    }

    private void addInputSlot(ModularPanel panel, IItemHandlerModifiable edited, int inputSlot, int x, int y,
            BooleanSupplier visible) {
        ModularSlot slot = new ModularSlot(edited, inputSlot).ignoreMaxStackSize(true);
        panel.child(
                new PatternIngredientSlot().progressText(() -> bufferedText(inputSlot))
                        .extraTooltip(tooltip -> addInputTooltip(tooltip, inputSlot))
                        .syncHandler(new PatternIngredientSlotSH(slot)).pos(x, y)
                        .setEnabledIf(w -> visible.getAsBoolean()));
        panel.child(
                new ButtonWidget<>().pos(x + 1, y + 1).size(7, 7).background(IDrawable.EMPTY)
                        .hoverBackground(IDrawable.EMPTY).overlay(satelliteBadge(inputSlot))
                        .setEnabledIf(w -> visible.getAsBoolean() && hasEntry(inputSlot))
                        .onMousePressed(mouseButton -> {
                            if (mouseButton != 0) return false;
                            openSatelliteSelector(inputSlot);
                            return true;
                        }).tooltipAutoUpdate(true).tooltipBuilder(tooltip -> addSatelliteTooltip(tooltip, inputSlot)));
    }

    private void addOutputSlot(ModularPanel panel, IItemHandlerModifiable edited, int patternSlot, int outputIndex,
            int x, int y, BooleanSupplier visible) {
        ModularSlot slot = new ModularSlot(edited, patternSlot).ignoreMaxStackSize(true);
        panel.child(
                new PatternIngredientSlot().progressText(() -> requestedText(outputIndex))
                        .extraTooltip(tooltip -> addEntryHint(tooltip, patternSlot))
                        .syncHandler(new PatternIngredientSlotSH(slot)).pos(x, y)
                        .setEnabledIf(w -> visible.getAsBoolean()));
    }

    private IWidget buildTargetDisplay() {
        IDrawable targetIcon = (context, x, y, width, height, widgetTheme) -> {
            ItemStack target = actions.getTargetStack();
            if (target != null) {
                GuiDraw.drawItem(target, x, y, width, height, context.getCurrentDrawingZ());
            } else {
                GuiDraw.drawRect(x + 3, y + 3, width - 6, height - 6, 0x40000000);
            }
        };
        return new Widget<>().pos(TARGET_X, TARGET_Y).size(16, 16).overlay(targetIcon)
                .setEnabledIf(w -> state.hasPattern()).tooltipAutoUpdate(true).tooltipBuilder(tooltip -> {
                    ItemStack target = actions.getTargetStack();
                    if (target == null) {
                        tooltip.addLine(IKey.lang(LANG + "target.none"));
                        tooltip.addLine(IKey.lang(LANG + "target.none.tip").style(EnumChatFormatting.GRAY));
                        return;
                    }
                    tooltip.addLine(IKey.lang(LANG + "target", target.getDisplayName()));
                    ForgeDirection side = actions.getTargetSide();
                    if (side != ForgeDirection.UNKNOWN) {
                        tooltip.addLine(
                                IKey.lang(LANG + "target.side", side.name().toLowerCase(Locale.ROOT))
                                        .style(EnumChatFormatting.GRAY));
                    }
                    tooltip.addLine(IKey.lang(LANG + "target.tip").style(EnumChatFormatting.GRAY));
                });
    }

    private void addEditorButtons(ModularPanel panel) {
        int y = PROCESSING_INPUT_Y;
        panel.child(
                editorButton(BUTTONS_X, y, BUTTON_WIDTH, 14)
                        .overlay(IKey.lang(() -> LANG + (state.isProcessing() ? "type.processing" : "type.crafting")))
                        .onMousePressed(patternAction(PatternAction.TOGGLE_TYPE)).tooltipAutoUpdate(true)
                        .tooltipBuilder(tooltip -> {
                            tooltip.addLine(
                                    IKey.lang(
                                            LANG + (state.isProcessing() ? "type.processing" : "type.crafting")
                                                    + ".long"));
                            tooltip.addLine(IKey.lang(LANG + "type.tip").style(EnumChatFormatting.GRAY));
                        }));
        y += 16;
        panel.child(
                editorButton(BUTTONS_X, y, HALF_BUTTON_WIDTH, 14)
                        .overlay(flagLabel("oredict.short", () -> state.getPattern().isOreDictSubstitutionEnabled()))
                        .onMousePressed(patternAction(PatternAction.TOGGLE_ORE_DICT)).tooltipAutoUpdate(true)
                        .tooltipBuilder(
                                tooltip -> addFlagTooltip(
                                        tooltip,
                                        "oredict",
                                        state.getPattern().isOreDictSubstitutionEnabled())));
        panel.child(
                editorButton(BUTTONS_X + HALF_BUTTON_WIDTH + 2, y, HALF_BUTTON_WIDTH, 14)
                        .overlay(flagLabel("nbt.short", () -> state.getPattern().isIgnoreNbtEnabled()))
                        .onMousePressed(patternAction(PatternAction.TOGGLE_IGNORE_NBT)).tooltipAutoUpdate(true)
                        .tooltipBuilder(
                                tooltip -> addFlagTooltip(tooltip, "nbt", state.getPattern().isIgnoreNbtEnabled())));
        y += 16;
        panel.child(
                editorButton(BUTTONS_X, y, HALF_BUTTON_WIDTH, 14).overlay(IKey.str("x2"))
                        .onMousePressed(patternAction(PatternAction.MULTIPLY)).tooltip(
                                tooltip -> tooltip.addLine(IKey.lang(LANG + "multiply"))
                                        .addLine(IKey.lang(LANG + "multiply.tip").style(EnumChatFormatting.GRAY))));
        panel.child(
                editorButton(BUTTONS_X + HALF_BUTTON_WIDTH + 2, y, HALF_BUTTON_WIDTH, 14)
                        .overlay(GuiTextures.CROSS_TINY).onMousePressed(patternAction(PatternAction.CLEAR)).tooltip(
                                tooltip -> tooltip.addLine(IKey.lang(LANG + "clear"))
                                        .addLine(IKey.lang(LANG + "clear.tip").style(EnumChatFormatting.GRAY))));
        y += 18;
        panel.child(
                editorButton(BUTTONS_X, y, BUTTON_WIDTH, 12).overlay(IKey.lang(LANG + "cancel").scale(0.8f))
                        .onMousePressed(mouseButton -> {
                            if (mouseButton != 0) return false;
                            actions.cancelCraft();
                            return true;
                        }).tooltip(
                                tooltip -> tooltip.addLine(IKey.lang(LANG + "cancel"))
                                        .addLine(IKey.lang(LANG + "cancel.tip").style(EnumChatFormatting.GRAY))));
        y += 14;
        panel.child(
                new ButtonWidget<>().pos(BUTTONS_X, y).size(BUTTON_WIDTH, 12)
                        .overlay(IKey.lang(LANG + "return").scale(0.8f)).onMousePressed(mouseButton -> {
                            if (mouseButton != 0) return false;
                            actions.returnInputs();
                            return true;
                        }).tooltip(
                                tooltip -> tooltip.addLine(IKey.lang(LANG + "return"))
                                        .addLine(IKey.lang(LANG + "return.tip").style(EnumChatFormatting.GRAY))));
    }

    private ButtonWidget<?> editorButton(int x, int y, int width, int height) {
        return new ButtonWidget<>().pos(x, y).size(width, height).setEnabledIf(w -> state.hasPattern());
    }

    private com.cleanroommc.modularui.api.widget.IGuiAction.MousePressed patternAction(PatternAction action) {
        return mouseButton -> {
            if (mouseButton != 0) return false;
            actions.patternAction(action);
            return true;
        };
    }

    private IDrawable flagLabel(String key, BooleanSupplier enabled) {
        return IKey.lang(LANG + key).scale(0.75f).color(() -> enabled.getAsBoolean() ? FLAG_ON_COLOR : FLAG_OFF_COLOR);
    }

    private static void addFlagTooltip(RichTooltip tooltip, String key, boolean enabled) {
        tooltip.addLine(IKey.lang(LANG + key));
        tooltip.addLine(IKey.lang(LANG + key + ".tip").style(EnumChatFormatting.GRAY));
        tooltip.addLine(
                IKey.lang(LANG + (enabled ? "enabled" : "disabled"))
                        .style(enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED));
    }

    // endregion

    // region status

    private IWidget buildStatusLine() {
        return new ScrollingTextWidget(IKey.dynamic(this::statusText)).pos(8, STATUS_Y).size(WIDTH - 16, 10)
                .color(TEXT_COLOR).tooltipAutoUpdate(true).tooltipBuilder(tooltip -> {
                    tooltip.addLine(IKey.str(statusText()));
                    tooltip.addLine(
                            IKey.lang(LANG + "status.mode", StatCollector.translateToLocal(LANG + "mode." + modeKey()))
                                    .style(EnumChatFormatting.GRAY));
                });
    }

    private String statusText() {
        if (!state.hasPattern()) {
            return StatCollector.translateToLocal(LANG + "status.nopattern");
        }
        if (!state.getPattern().isConfigured()) {
            return StatCollector.translateToLocal(LANG + "status.unconfigured");
        }
        if (actions.isPatternUnsupported(state.getSelectedSlot())) {
            return EnumChatFormatting.RED + StatCollector.translateToLocal(LANG + "status.fluidupgrade");
        }
        PatternCraftingHudState.PatternInfo info = actions.getSelectedPatternInfo();
        if (info == null || info.getStatus().isEmpty()) {
            return StatCollector.translateToLocal(LANG + "status.idle");
        }
        return (info.isActive() ? EnumChatFormatting.DARK_GREEN + "● " + EnumChatFormatting.RESET : "")
                + info.getStatus();
    }

    // endregion

    // region slot helpers

    private boolean hasEntry(int patternSlot) {
        return state.getPattern().getPatternStackInSlot(patternSlot) != null;
    }

    private boolean isFluidEntry(int patternSlot) {
        return PatternStackHelper.isFluid(state.getPattern().getPatternStackInSlot(patternSlot));
    }

    private String bufferedText(int inputSlot) {
        PatternCraftingHudState.PatternInfo info = actions.getSelectedPatternInfo();
        if (info == null) {
            return null;
        }
        int buffered = 0;
        for (PatternCraftingHudState.IngredientInfo ingredient : info.getIngredients()) {
            if (ingredient.slot() == inputSlot) {
                buffered += ingredient.bufferedAmount();
            }
        }
        return buffered > 0 ? formatAmount(buffered, isFluidEntry(inputSlot)) : null;
    }

    private String requestedText(int outputIndex) {
        PatternCraftingHudState.PatternInfo info = actions.getSelectedPatternInfo();
        if (info == null) {
            return null;
        }
        int requested = 0;
        for (PatternCraftingHudState.OutputInfo output : info.getOutputs()) {
            if (output.slot() == outputIndex) {
                requested += output.requestedAmount();
            }
        }
        AbstractPattern pattern = state.getPattern();
        boolean fluid = PatternStackHelper
                .isFluid(pattern.getPatternStackInSlot(pattern.getResultSlotStart() + outputIndex));
        return requested > 0 ? formatAmount(requested, fluid) : null;
    }

    private static String formatAmount(int amount, boolean fluid) {
        return fluid ? PatternGuiDraw.formatFluidAmount(amount) : PatternGuiDraw.formatCount(amount);
    }

    private void addInputTooltip(RichTooltip tooltip, int inputSlot) {
        String buffered = bufferedText(inputSlot);
        if (buffered != null) {
            tooltip.addLine(IKey.lang(LANG + "buffered", buffered).style(EnumChatFormatting.AQUA));
        }
        addSatelliteLine(tooltip, inputSlot);
        addEntryHint(tooltip, inputSlot);
    }

    private void addEntryHint(RichTooltip tooltip, int patternSlot) {
        tooltip.addLine(
                IKey.lang(LANG + (isFluidEntry(patternSlot) ? "entry.fluid.tip" : "entry.item.tip"))
                        .style(EnumChatFormatting.DARK_GRAY));
    }

    // endregion

    // region satellites

    private IDrawable satelliteBadge(int inputSlot) {
        return (context, x, y, width, height, widgetTheme) -> {
            boolean fluid = isFluidEntry(inputSlot);
            boolean assigned = isSatelliteAssigned(inputSlot, fluid);
            int fill = !assigned ? 0xff707070 : fluid ? 0xff00a8cc : 0xff2b6ee8;
            GuiDraw.drawRect(x, y, width, height, 0xff1a1a1a);
            GuiDraw.drawRect(x + 1, y + 1, width - 2, height - 2, fill);
            if (assigned) {
                GuiDraw.drawRect(x + 2, y + 2, width - 4, height - 4, 0xffffffff);
                GuiDraw.drawRect(x + 3, y + 3, width - 6, height - 6, fill);
            } else {
                GuiDraw.drawRect(x + 3, y + 2, 1, height - 4, 0xffffffff);
                GuiDraw.drawRect(x + 2, y + 3, width - 4, 1, 0xffffffff);
            }
        };
    }

    private boolean isSatelliteAssigned(int inputSlot, boolean fluid) {
        return getSatelliteId(inputSlot, fluid) > 0 || !getSatelliteUuid(inputSlot, fluid).isEmpty();
    }

    private int getSatelliteId(int inputSlot, boolean fluid) {
        AbstractPattern pattern = state.getPattern();
        return fluid ? pattern.getFluidSatelliteIdForInputSlot(inputSlot)
                : pattern.getSatelliteIdForInputSlot(inputSlot);
    }

    private String getSatelliteUuid(int inputSlot, boolean fluid) {
        AbstractPattern pattern = state.getPattern();
        return fluid ? pattern.getFluidSatelliteUuidForInputSlot(inputSlot)
                : pattern.getSatelliteUuidForInputSlot(inputSlot);
    }

    private void addSatelliteLine(RichTooltip tooltip, int inputSlot) {
        boolean fluid = isFluidEntry(inputSlot);
        if (!isSatelliteAssigned(inputSlot, fluid)) {
            tooltip.addLine(IKey.lang(LANG + "satellite.local").style(EnumChatFormatting.GRAY));
            return;
        }
        tooltip.addLine(
                IKey.lang(LANG + "satellite.assigned", satelliteName(inputSlot, fluid))
                        .style(fluid ? EnumChatFormatting.DARK_AQUA : EnumChatFormatting.BLUE));
    }

    private void addSatelliteTooltip(RichTooltip tooltip, int inputSlot) {
        boolean fluid = isFluidEntry(inputSlot);
        if (!isSatelliteAssigned(inputSlot, fluid)) {
            tooltip.addLine(IKey.lang(LANG + "satellite.local"));
        } else {
            tooltip.addLine(IKey.lang(LANG + "satellite.assigned", satelliteName(inputSlot, fluid)));
            PatternSatelliteInfo satellite = actions
                    .findSatellite(getSatelliteId(inputSlot, fluid), getSatelliteUuid(inputSlot, fluid), fluid);
            if (satellite != null) {
                tooltip.addLine(IKey.str(describeLocation(satellite)).style(EnumChatFormatting.GRAY));
            }
        }
        tooltip.addLine(IKey.lang(LANG + "satellite.click").style(EnumChatFormatting.DARK_GRAY));
    }

    private String satelliteName(int inputSlot, boolean fluid) {
        int id = getSatelliteId(inputSlot, fluid);
        PatternSatelliteInfo satellite = actions.findSatellite(id, getSatelliteUuid(inputSlot, fluid), fluid);
        return satellite != null ? satellite.displayName() : "#" + id;
    }

    private static String describeLocation(PatternSatelliteInfo satellite) {
        String distance = satellite.distance() >= 0 ? satellite.distance() + "m"
                : StatCollector.translateToLocal(LANG + "satellite.otherdim");
        return distance + ", D"
                + satellite.dimension()
                + " ("
                + satellite.x()
                + ", "
                + satellite.y()
                + ", "
                + satellite.z()
                + ")";
    }

    private void openSatelliteSelector(int inputSlot) {
        selectorInputSlot = inputSlot;
        selectorFluid = isFluidEntry(inputSlot);
        actions.refreshSatellites();
        satelliteSelector.deleteCachedPanel();
        satelliteSelector.openPanel();
    }

    private ModularPanel buildSatelliteSelector(ModularPanel parent, EntityPlayer player) {
        int inputSlot = selectorInputSlot;
        boolean fluid = selectorFluid;
        ModularPanel panel = new ModularPanel("pattern_satellite_selector").size(184, 178)
                .background(ModularUIHelper.BACKGROUND_TEXTURE);
        panel.child(
                IKey.lang(LANG + (fluid ? "satellite.title.fluid" : "satellite.title.item"), inputSlot + 1).asWidget()
                        .pos(8, 7).color(TEXT_COLOR));
        panel.child(ButtonWidget.panelCloseButton());
        StringValue search = new StringValue("");
        panel.child(
                new TextFieldWidget().value(search).pos(8, 20).size(168, 14)
                        .hintText(StatCollector.translateToLocal(LANG + "satellite.search")));
        ListWidget<IWidget, ?> list = new ListWidget<>();
        list.pos(8, 38).size(168, 132);
        int[] builtRevision = { actions.getSatelliteRevision() };
        fillSatelliteRows(panel, list, search, inputSlot, fluid);
        list.onUpdateListener(w -> {
            if (builtRevision[0] != actions.getSatelliteRevision()) {
                builtRevision[0] = actions.getSatelliteRevision();
                w.removeAll();
                fillSatelliteRows(panel, w, search, inputSlot, fluid);
            }
        });
        panel.child(list);
        return panel;
    }

    private void fillSatelliteRows(ModularPanel panel, ListWidget<IWidget, ?> list, StringValue search, int inputSlot,
            boolean fluid) {
        int currentId = getSatelliteId(inputSlot, fluid);
        String currentUuid = getSatelliteUuid(inputSlot, fluid);
        list.addChild(
                satelliteRow(
                        panel,
                        search,
                        StatCollector.translateToLocal(LANG + "satellite.local.row"),
                        "local none",
                        currentId <= 0 && currentUuid.isEmpty(),
                        inputSlot,
                        0,
                        "",
                        fluid),
                -1);
        PatternSatelliteInfo.SatelliteType type = fluid ? PatternSatelliteInfo.SatelliteType.FLUID
                : PatternSatelliteInfo.SatelliteType.ITEM;
        for (PatternSatelliteInfo satellite : actions.getSatellites()) {
            if (satellite.type() != type) {
                continue;
            }
            boolean current = !currentUuid.isEmpty() ? currentUuid.equals(satellite.uuid())
                    : satellite.id() == currentId;
            String label = (satellite.favorite() ? "★ " : "") + satellite.displayName()
                    + EnumChatFormatting.GRAY
                    + "  "
                    + describeLocation(satellite);
            list.addChild(
                    satelliteRow(
                            panel,
                            search,
                            label,
                            satellite.getSearchText(),
                            current,
                            inputSlot,
                            satellite.id(),
                            satellite.uuid(),
                            fluid),
                    -1);
        }
    }

    private IWidget satelliteRow(ModularPanel panel, StringValue search, String label, String searchText,
            boolean current, int inputSlot, int satelliteId, String satelliteUuid, boolean fluid) {
        String text = (current ? EnumChatFormatting.YELLOW + "» " + EnumChatFormatting.RESET : "") + label;
        return new ButtonWidget<>().width(162).height(14)
                .overlay(IKey.str(text).alignment(Alignment.CenterLeft).scale(0.8f))
                .setEnabledIf(w -> matchesSearch(searchText, search.getStringValue())).onMousePressed(mouseButton -> {
                    if (mouseButton != 0) return false;
                    actions.assignSatellite(inputSlot, satelliteId, satelliteUuid, fluid);
                    panel.closeIfOpen();
                    return true;
                });
    }

    private static boolean matchesSearch(String searchText, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String haystack = searchText.toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    // endregion
}
